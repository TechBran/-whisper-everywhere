package com.whispereverywhere.transcription.batch

import com.whispereverywhere.recording.BatchStatus
import com.whispereverywhere.recording.ChunkEntry
import com.whispereverywhere.recording.ChunkStatus
import com.whispereverywhere.recording.EngineUsed
import com.whispereverywhere.recording.RecordingMeta
import com.whispereverywhere.recording.RecordingStore
import com.whispereverywhere.transcription.ModelPathProvider
import com.whispereverywhere.transcription.WhisperBackend
import com.whispereverywhere.transcription.TranscriptText
import com.whispereverywhere.transcription.cloud.SttError
import com.whispereverywhere.transcription.cloud.SttProvider
import com.whispereverywhere.transcription.cloud.SttResult
import com.whispereverywhere.util.AudioMath
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.RandomAccessFile
import java.util.concurrent.Executors

data class BatchProgress(
    val recordingId: String,
    val chunkIndex: Int,
    val chunkCount: Int,
    val status: BatchStatus,
)

/**
 * Runs one recording's whole transcription, sequentially, checkpointing each chunk to the manifest.
 *
 * NOT a TranscriptionEngine — a batch job has no connect/sendAudio/commit; that contract is a lie
 * here. Output lands on a screen, not an injected IME field, so there is NO SegmentOrderer: chunk N
 * awaits before N+1, results are always in order, and the assembled text is a plain in-order join
 * held in the manifest.
 *
 * Cloud eligibility is decided UPSTREAM: the service passes a non-null [cloud] only when
 * BatchCloudGate's consent triad holds (and the cost confirm, and notifications). This class does
 * not re-derive policy — with cloud == null it is a purely local job runner. The fallback valve is
 * one-way (cloud -> local per chunk); a cloud Fatal latches.
 *
 * Threading: the native single-thread invariant is enforced INSIDE this class, not by caller
 * convention. transcribe() is a suspend function that hops threads at every cloud await, so "the
 * service launched me on a single-thread dispatcher" is NOT enough — the resume after a cloud call
 * may land elsewhere. Every native touch (load/transcribe/release) is therefore wrapped in
 * withContext(nativeDispatcher), a private single-thread executor this class owns, creates, and
 * closes; a caller cannot break the invariant by choosing a bad dispatcher. Release runs under
 * NonCancellable so a cancelled job still frees the ctx, on the confined thread.
 */
class BatchTranscriber(
    private val store: RecordingStore,
    private val cloud: SttProvider?,
    private val backend: WhisperBackend,
    private val modelPathProvider: ModelPathProvider,
    private val clock: () -> Long = System::currentTimeMillis,
    /**
     * Re-checked before EACH cloud chunk so a mid-job revocation of the CHEAP consent/notification
     * flags degrades the rest of the job to on-device (finding #6). The job's cloud eligibility was
     * resolved once at start; a long file can run for minutes, during which the user may withdraw
     * disclosure consent or disable notifications. Reads only the cheap flags — never the network
     * probe, which the service keeps lazy. Defaults to always-permitted so pure-local jobs and the
     * existing tests are unaffected.
     */
    private val cloudStillPermitted: () -> Boolean = { true },
) {
    private val _progress = MutableStateFlow<BatchProgress?>(null)
    val progress: StateFlow<BatchProgress?> = _progress.asStateFlow()

    /**
     * The ONLY thread that may touch the native whisper ctx. Owned here (created and closed by
     * this class) so the confinement cannot be broken by a caller's dispatcher choice — see the
     * class KDoc.
     */
    private val nativeDispatcher =
        Executors.newSingleThreadExecutor { r -> Thread(r, "batch-native").apply { isDaemon = true } }
            .asCoroutineDispatcher()

    @Volatile private var dispatcherClosed = false

    /** True once the owned single-thread native executor has been closed. Observability/test seam. */
    internal val nativeExecutorClosed: Boolean get() = dispatcherClosed

    /**
     * Close the owned single-thread native executor. IDEMPOTENT (guarded by [dispatcherClosed]) and
     * SELF-INVOKED at the end of [transcribe]'s finally, right after the confined release — so the
     * executor is closed exactly where the job ends, on every path, without the caller having to
     * remember. A leftover external call (e.g. from a service backstop) is a harmless no-op; the
     * previous design leaked one parked "batch-native" thread per completed job because shutdown was
     * only ever reached from the service's onDestroy, which the normal completion path skips.
     */
    fun shutdown() {
        if (dispatcherClosed) return
        dispatcherClosed = true
        nativeDispatcher.close()
    }

    @Volatile private var cancelled = false
    fun cancel() { cancelled = true }

    // Test seams (production uses the real ceilings / retry budget). Not for production callers.
    // The cloud ceiling defaults from the RESOLVED adapter's base64-aware maxRequestBytes (Gemini's
    // 14 MB, ElevenLabs capped to the 20 MB memory bound, etc.); a null cloud (local job) never uses
    // it — ceiling picks testLocalChunk. Tests still override via testCloudCeiling.
    internal var testCloudCeiling: Int =
        cloud?.let { BatchChunkCeiling.forProvider(it) } ?: ChunkPlanner.CLOUD_CEILING_BYTES
    internal var testLocalChunk: Int = ChunkPlanner.LOCAL_CHUNK_BYTES
    internal var testMaxCloudRetries: Int = MAX_CLOUD_RETRIES
    internal var onChunkDone: (BatchTranscriber.() -> Unit)? = null

    suspend fun transcribe(id: String, reset: Boolean = false) {
        cancelled = false
        var meta = store.read(id) ?: return

        // (1) The engine was decided UPSTREAM (service: BatchCloudGate + cost confirm +
        // notifications). Non-null cloud here means "this job is allowed to upload". [effectiveCloud]
        // is mutable so a mid-job flag revocation (finding #6) can degrade it to null for the rest of
        // the run; [plannedAtCloudCeiling] remembers the plan's ceiling so a degraded chunk — planned
        // large for cloud — is re-sliced to the local ceiling before it reaches the native model.
        var effectiveCloud: SttProvider? = cloud
        val plannedAtCloudCeiling = cloud != null
        // The engine a cloud-served chunk records. Derived from the ORIGINAL cloud provider (not the
        // mutable effectiveCloud), so it is non-null on every path where a cloud chunk can succeed.
        val cloudEngine = cloud?.let { EngineUsed.fromProviderId(it.id) }
        val ceiling = if (effectiveCloud != null) testCloudCeiling else testLocalChunk

        // (2) Plan (or re-plan on reset). The coarse silence scan STREAMS the file in fixed windows
        // (SilenceScanner reads ~0.94 MB at a time, reusing one buffer) — it never loads the whole
        // decoded PCM into a single ByteArray, so an hour-long (≈115 MB) or multi-hour file plans
        // without an OOM. Per-chunk work below likewise streams via the same RandomAccessFile.
        if (reset || meta.chunkPlan.isEmpty()) {
            val audio = store.audioFile(id)
            val totalBytes = audio.length()
            val boundaries = RandomAccessFile(audio, "r").use { SilenceScanner.scan(it, totalBytes) }
            val plan = ChunkPlanner.plan(totalBytes.toInt(), ceiling, minChunkBytes = 0, boundaries = boundaries)
            meta = meta.copy(chunkPlan = plan, status = BatchStatus.Transcribing, engineUsed = null)
            store.save(meta)
        } else {
            meta = meta.copy(status = BatchStatus.Transcribing); store.save(meta)
        }

        var ctx = 0L
        var usedCloud = false
        var usedLocal = false
        var fatal = false
        try {
            val raf = RandomAccessFile(store.audioFile(id), "r")
            raf.use { file ->
                for ((i, chunk) in meta.chunkPlan.withIndex()) {
                    if (chunk.status == ChunkStatus.Done) continue           // (RESUME) never re-run
                    if (cancelled) break
                    // (RE-CHECK, finding #6) The cheap consent/notification flags may have been
                    // revoked since the job started; if so, degrade to on-device for the rest of it.
                    if (effectiveCloud != null && !cloudStillPermitted()) effectiveCloud = null
                    _progress.value = BatchProgress(id, i, meta.chunkPlan.size, BatchStatus.Transcribing)

                    val pcm = ByteArray(chunk.endByte - chunk.startByte)
                    file.seek(chunk.startByte.toLong())
                    file.readFully(pcm)

                    val (text, engine) = when {
                        effectiveCloud != null -> {
                            val r = runCloud(effectiveCloud, pcm, meta.language)
                            when (r) {
                                is CloudChunkResult.Ok -> { usedCloud = true; r.text to cloudEngine!! }
                                CloudChunkResult.Fatal -> { fatal = true; break }
                                CloudChunkResult.FallBack -> {                 // (ONE-WAY VALVE)
                                    if (ctx == 0L) ctx = loadCtx()
                                    // (RE-SLICE) This chunk was planned at the CLOUD ceiling.
                                    usedLocal = true; runLocalSliced(ctx, pcm, meta.language) to EngineUsed.LOCAL
                                }
                            }
                        }
                        else -> {
                            if (ctx == 0L) ctx = loadCtx()
                            usedLocal = true
                            // A chunk planned at the CLOUD ceiling (job started cloud, then degraded
                            // mid-run) must be re-sliced to the local ceiling before the native model;
                            // a natively-local job planned small and feeds whole.
                            val t = if (plannedAtCloudCeiling) runLocalSliced(ctx, pcm, meta.language)
                                    else runLocal(ctx, pcm, meta.language)
                            t to EngineUsed.LOCAL
                        }
                    }

                    // (CHECKPOINT) persist this chunk before moving on.
                    val updated = meta.chunkPlan.toMutableList()
                    updated[i] = chunk.copy(status = ChunkStatus.Done, text = text)
                    meta = meta.copy(chunkPlan = updated)
                    store.save(meta)
                    onChunkDone?.invoke(this)
                }
            }
        } finally {
            try {
                // Confined AND NonCancellable: a cancelled job must still free the native ctx, and
                // must free it from the one thread allowed to touch it. This runs on the STILL-OPEN
                // dispatcher because shutdown() is deferred to the outer finally below.
                if (ctx != 0L) withContext(nativeDispatcher + NonCancellable) { backend.release(ctx) }
            } finally {
                // Close the owned executor exactly where the job ends — AFTER release has unwound on
                // it. On a cancelled job (service onDestroy), the release above already ran on the
                // open dispatcher; only then do we close it. No caller can race this from off-thread.
                shutdown()
            }
        }

        val allDone = meta.chunkPlan.all { it.status == ChunkStatus.Done }
        val finalStatus = when {
            fatal -> BatchStatus.Failed
            cancelled && !allDone -> BatchStatus.PartiallyDone
            allDone -> BatchStatus.Done
            else -> BatchStatus.PartiallyDone
        }
        val engineUsed = when {
            usedCloud && usedLocal -> EngineUsed.LOCAL   // mixed: at least one chunk stayed on-device
            usedCloud -> cloudEngine!!
            usedLocal -> EngineUsed.LOCAL
            else -> meta.engineUsed
        }
        meta = meta.copy(status = finalStatus, engineUsed = engineUsed,
            modelId = modelPathProvider.installedModelPath())
        store.save(meta)
        _progress.value = BatchProgress(id, meta.chunkPlan.size, meta.chunkPlan.size, finalStatus)
    }

    private sealed interface CloudChunkResult {
        data class Ok(val text: String) : CloudChunkResult
        data object FallBack : CloudChunkResult    // Offline / exhausted Transient / BadSegment
        data object Fatal : CloudChunkResult       // bad key / no credit — latch and stop
    }

    private suspend fun runCloud(provider: SttProvider, pcm: ByteArray, language: String?): CloudChunkResult {
        var attempt = 0
        while (true) {
            when (val r = provider.transcribe(pcm, language)) {
                is SttResult.Text -> return CloudChunkResult.Ok(TranscriptText.clean(r.text))
                is SttResult.Failed -> when (val e = r.error) {
                    is SttError.Fatal -> return CloudChunkResult.Fatal
                    SttError.BadSegment -> return CloudChunkResult.FallBack   // this chunk is cloud's problem
                    SttError.Offline -> return CloudChunkResult.FallBack
                    // An undecodable 200 was already billed — retrying just re-bills for the same
                    // unreadable answer, so fall to local for this chunk (never retry).
                    SttError.Undecodable -> return CloudChunkResult.FallBack
                    is SttError.Transient -> {
                        if (attempt >= testMaxCloudRetries) return CloudChunkResult.FallBack
                        delay(e.retryAfterMs ?: (BASE_BACKOFF_MS * (attempt + 1)))
                        attempt++
                    }
                }
            }
        }
    }

    private suspend fun loadCtx(): Long = withContext(nativeDispatcher) {
        val path = modelPathProvider.installedModelPath()
            ?: error("No on-device model installed — cannot transcribe locally")
        backend.load(path)
    }

    /** One local-sized piece. A local run for a PLAYBACK recording is always fine — the gate is on CLOUD dispatch. */
    private suspend fun runLocal(ctx: Long, pcm: ByteArray, language: String?): String =
        withContext(nativeDispatcher) {
            val samples = AudioMath.pcm16ToFloat(pcm)
            // BATCH: transcribe EVERYTHING — a user-chosen file must not be VAD-trimmed (quiet
            // music / low speech is kept). runLocalSliced routes through here, so both the
            // small-planned local job AND the cloud-fallback re-slice inherit the bypass. Live
            // dictation calls backend.transcribe(...) without useVad and keeps the true default.
            TranscriptText.clean(backend.transcribe(ctx, samples, language, useVad = false))
        }

    /**
     * (RE-SLICE) A chunk planned at the CLOUD ceiling (up to 20 MB / ~10.5 min) that falls back must
     * never reach the native model whole: pcm16ToFloat alone would allocate a ~40 MB FloatArray on
     * top of the 20 MB source, plus minutes of native encoder buffers, inside a foreground service —
     * the exact long-feed OOM LOCAL_CHUNK_BYTES exists to prevent. Slice to the local ceiling at an
     * even offset (a PCM16 sample is 2 bytes; an odd cut would shear every later sample) and join
     * the pieces with a space, matching assembledText's convention for trimmed chunk text.
     */
    private suspend fun runLocalSliced(ctx: Long, pcm: ByteArray, language: String?): String {
        val step = testLocalChunk - (testLocalChunk % 2)
        val parts = ArrayList<String>()
        var start = 0
        while (start < pcm.size) {
            val end = minOf(start + step, pcm.size)
            val text = runLocal(ctx, pcm.copyOfRange(start, end), language)
            if (text.isNotBlank()) parts.add(text)
            start = end
        }
        return parts.joinToString(" ")
    }

    companion object {
        private const val MAX_CLOUD_RETRIES = 3
        private const val BASE_BACKOFF_MS = 400L
    }
}
