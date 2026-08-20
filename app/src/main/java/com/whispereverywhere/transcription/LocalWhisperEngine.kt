package com.whispereverywhere.transcription

import android.util.Log
import com.whispereverywhere.util.AudioMath
import com.whispereverywhere.util.RetryPolicy
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * On-device whisper.cpp engine. Buffers PCM16 audio, and on commit runs one batch
 * transcription of the buffered segment on a single-thread executor (segments serialize).
 * No intra-segment deltas are emitted — exactly one onSegmentResolved per committed segment.
 *
 * Because [executor] is single-threaded, segments resolve in the order they were committed, so a
 * downstream [SegmentOrderer] is a provable pass-through here: results always arrive with
 * seq == head and delivery timing is identical to having no orderer at all.
 *
 * IMPORTANT: [executor] MUST be single-threaded. All native whisper context ([ctxPtr]) reads,
 * writes, loads, and frees happen exclusively on that thread, which is what serializes them
 * safely. The default [Executors.newSingleThreadExecutor] satisfies this contract — callers
 * must NOT pass a multi-threaded executor.
 */
class LocalWhisperEngine(
    private val modelPathProvider: ModelPathProvider,
    private val retry: RetryPolicy = RetryPolicy(maxAttempts = 3),
    private val backend: WhisperBackend = WhisperNativeBackend,
    /**
     * MUST be single-threaded. All native whisper context ([ctxPtr]) reads, writes, loads,
     * and frees are serialized by executing exclusively on this thread. Passing a
     * multi-threaded executor will cause data races on the native context pointer.
     */
    private val executor: ExecutorService = Executors.newSingleThreadExecutor(),
) : TranscriptionEngine {

    // Model load is retried once (transient FS/mmap) using the injected policy's timing.
    private val loadRetry = RetryPolicy(
        maxAttempts = 2,
        baseDelayMs = retry.baseDelayMs,
        maxDelayMs = retry.maxDelayMs,
        rng = retry.rng,
    )

    private val bufferLock = Any()
    private val buffer = ByteArrayOutputStream()

    /**
     * Monotonic segment identity for the CURRENT session. Allocated INSIDE [bufferLock] together
     * with the PCM snapshot — see [commit] — so a segment's identity is fixed by audio order, not
     * by the order two threads happen to reach the executor. Reset per session in [connect].
     */
    private var nextSeq = 0L

    private companion object {
        /** 30 s of PCM16 @ 16 kHz — hard ceiling on audio buffered between commits. */
        const val MAX_BUFFER_BYTES = 30 * 16000 * 2

        /** commit() cut nothing, so no seq was allocated and nothing is owed a resolution. */
        const val NO_SEGMENT = -1L

        /** [SegmentOutcome.Lost] reasons. Fixed strings — a reason must never quote user audio. */
        const val NO_MODEL = "speech model not loaded"
        const val TRANSCRIBE_FAILED = "transcription failed"
    }

    /**
     * Lightweight control executor used ONLY to deliver connect() readiness callbacks
     * (onOpen/onError). It NEVER touches the native context. Keeping these off the native
     * [executor] means CONNECTING is not blocked behind a slow in-flight transcribe when the
     * engine is reused across sessions (a large-model transcribe can take many seconds).
     */
    private val controlExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    @Volatile private var listener: TranscriptionEngine.Listener? = null
    @Volatile private var language: String? = null

    // Process-lifetime cached native context (0 = not loaded).
    @Volatile private var ctxPtr: Long = 0L

    // Absolute path of the model currently loaded into [ctxPtr]; used to detect a model switch so
    // the reused engine reloads the newly-selected model instead of silently reusing the old one.
    @Volatile private var loadedModelPath: String? = null

    override fun connect(language: String?, listener: TranscriptionEngine.Listener) {
        this.listener = listener
        this.language = language
        // Per-session state: a fresh SegmentOrderer starts at head 0, so seq numbering must
        // restart with it — otherwise the new session's first segment looks like a late duplicate
        // of the old session's and is dropped. Under bufferLock because commit() reads it there.
        synchronized(bufferLock) { nextSeq = 0L }

        val modelPath = modelPathProvider.installedModelPath()
        android.util.Log.i("WE-DIAG", "connect: modelPath=$modelPath ctxPtr=$ctxPtr loaded=$loadedModelPath")
        if (modelPath == null) {
            // No native work involved; route through the native executor (keeps callback ordering
            // consistent and deterministic for tests using a same-thread executor).
            executor.execute {
                if (this.listener === listener) listener.onError("No speech model installed")
            }
            return
        }

        // Fast path: the SAME model is already loaded (reused engine). Signal readiness on the
        // lightweight control executor so onOpen() is NOT queued behind a slow in-flight transcribe
        // on the native executor — otherwise a prior session's transcribe would stall CONNECTING.
        if (ctxPtr != 0L && modelPath == loadedModelPath) {
            controlExecutor.execute {
                android.util.Log.i("WE-DIAG", "onOpen (ctx already loaded)")
                if (this.listener === listener) listener.onOpen()
            }
            return
        }

        // Nothing loaded yet, OR the installed model CHANGED since we loaded (user switched models).
        // (Re)load on the native executor. If a stale context for a DIFFERENT model is present, free
        // it first so we never transcribe with the wrong (or a heavier-than-selected) model.
        executor.execute {
            try {
                if (ctxPtr != 0L && modelPath != loadedModelPath) {
                    android.util.Log.i("WE-DIAG", "model changed ($loadedModelPath -> $modelPath); releasing old ctx")
                    try {
                        backend.release(ctxPtr)
                    } catch (t: Throwable) {
                        Log.w("LocalWhisperEngine", "release on model switch failed", t)
                    }
                    ctxPtr = 0L
                    loadedModelPath = null
                }
                if (ctxPtr == 0L) {
                    // Retry a transient load failure once before giving up.
                    android.util.Log.i("WE-DIAG", "loading model from $modelPath")
                    val loaded = runBlocking { loadRetry.retry { backend.load(modelPath) } }
                    android.util.Log.i("WE-DIAG", "model load returned ctx=$loaded")
                    if (loaded == 0L) {
                        if (this.listener === listener) listener.onError("Failed to load speech model (may be corrupt - re-download)")
                        return@execute
                    }
                    ctxPtr = loaded
                    loadedModelPath = modelPath
                }
                android.util.Log.i("WE-DIAG", "onOpen (ctx loaded)")
                if (this.listener === listener) listener.onOpen()
            } catch (t: Throwable) {
                android.util.Log.w("WE-DIAG", "model load threw", t)
                if (this.listener === listener) listener.onError(t.message ?: "Model load failed")
            }
        }
    }

    override fun sendAudio(pcm: ByteArray) {
        val overflow = synchronized(bufferLock) {
            val hadAudio = buffer.size() > 0
            buffer.write(pcm)
            // Trips on ACCUMULATION only — many small capture chunks growing past the cap, which is
            // the runaway this backstop exists to bound.
            //
            // A single write that is ITSELF over the cap is deliberately exempt, and that exemption
            // is the 2026-07-31 fix for an owner-reported data loss (Soniox live, twice on the
            // wire). Such a write is never capture; it is a caller handing over one complete
            // segment and committing it in the same breath — specifically
            // [com.whispereverywhere.transcription.cloud.FallbackTranscriptionEngine.localRetry]
            // rescuing a long cloud turn (56 s and 82 s were measured). Cutting that behind the
            // caller's back saved no memory — the bytes are already here, and the fallback's mirror
            // is holding them anyway — while leaving the commit() that arrived microseconds later
            // staring at an empty buffer. localRetry reads that NO_SEGMENT as "the local engine
            // refused this", gives up, and surfaces the cloud's loss: on the wire that cost the
            // user 586 characters of speech and stamped a "[…]" marker over them, while whisper
            // was transcribing the very same audio successfully in the background.
            hadAudio && buffer.size() >= MAX_BUFFER_BYTES
        }
        if (overflow) {
            android.util.Log.i("WE-DIAG", "sendAudio: buffer cap reached -> forced commit")
            commit()
        }
    }

    /**
     * Cuts the buffered audio into one segment and returns its seq, or [NO_SEGMENT] if there was
     * nothing to cut.
     *
     * The [NO_SEGMENT] contract is load-bearing beyond this class, and getting a segment cut out
     * from under it is what caused the 2026-07-31 data loss:
     * [com.whispereverywhere.transcription.cloud.FallbackTranscriptionEngine.localRetry] reads it as
     * "the local engine refused this rescue" and gives up, surfacing the cloud's loss. Keeping that
     * reading honest is [sendAudio]'s job — see the accumulation-only condition there.
     */
    override fun commit(): Long {
        val myListener = this.listener
        if (myListener == null) {
            android.util.Log.i("WE-DIAG", "commit: no listener (session ended), skipped")
            return NO_SEGMENT
        }
        val lang = this.language

        // seq is allocated INSIDE bufferLock with the snapshot. That alone fixes a pre-existing
        // race: commit() previously snapshotted under the lock but called executor.execute
        // OUTSIDE it, and commit() is invoked from the audio thread AND the main thread
        // (switchSource, projection consent, stopRecording) — so two callers could snapshot
        // A-then-B and enqueue B-then-A, emitting the transcript out of order. Ordering is now a
        // function of audio order, not enqueue order.
        val (seq, pcm) = synchronized(bufferLock) {
            val snapshot = buffer.toByteArray()
            if (snapshot.isEmpty()) {
                android.util.Log.i("WE-DIAG", "commit: pcmBytes=0 -> nothing to cut")
                return NO_SEGMENT
            }
            buffer.reset()
            (nextSeq++) to snapshot
        }
        android.util.Log.i("WE-DIAG", "commit: seq=$seq pcmBytes=${pcm.size} samples=${pcm.size / 2}")
        executor.execute { runSegment(seq, pcm, lang, myListener) }
        return seq
    }

    /**
     * Runs one segment to a terminal outcome. EVERY path through this function must call
     * onSegmentResolved exactly once — a seq that never resolves permanently stalls the orderer
     * head and holds every later segment with it. That is why the blank case, which previously
     * just logged "dropped" and emitted nothing at all, now resolves explicitly, and why the
     * catch is deliberately broad: any escape here is an unresolvable seq.
     *
     * The two failure branches (no context loaded, transcribe threw) resolve as
     * [SegmentOutcome.Lost] — NOT [SegmentOutcome.EmptyExpected] — and additionally call
     * [myListener]'s onError, guarded by the same listener-identity check as the terminal
     * onSegmentResolved call below.
     *
     * That split is load-bearing and was the 2026-07-31 fix for the owner-reported "[…] at the end
     * of every message". This engine is also the safety net under the cloud engines, and
     * [com.whispereverywhere.transcription.cloud.FallbackPolicy.reconcile] now TRUSTS an
     * EmptyExpected from here as a real verdict ("whisper ran on this audio and heard no speech")
     * so the trailing silence between the user's last word and the stop tap stops being marked as
     * a lost sentence. That trust is only sound if EmptyExpected means exactly that one thing — so
     * the branches where whisper never ran, and therefore reached no verdict, must say Lost.
     *
     * Standalone-local users are not newly exposed to the marker by this: [connect] reports
     * onError and never fires onOpen when the model cannot load, so recording never starts and
     * ctx == 0 here is only reachable through a mid-session unload race. A genuine throw after
     * [retry] has exhausted its attempts IS a lost sentence, which is precisely what the marker is
     * for.
     */
    private fun runSegment(
        seq: Long,
        pcm: ByteArray,
        lang: String?,
        myListener: TranscriptionEngine.Listener,
    ) {
        val outcome: SegmentOutcome = try {
            val ctx = ctxPtr
            if (ctx == 0L) {
                android.util.Log.w("WE-DIAG", "commit: ctx==0 (model not loaded)")
                // Lost, NOT EmptyExpected: whisper never ran, so it reached no verdict about this
                // audio. Saying "no speech" here would let FallbackPolicy.reconcile swallow the
                // cloud's loss on every device with no model installed — the sentence would vanish
                // with nothing to show for it. See the KDoc above.
                if (listener === myListener) myListener.onError("Speech model not loaded")
                SegmentOutcome.Lost(NO_MODEL)
            } else {
                val samples = AudioMath.pcm16ToFloat(pcm)
                android.util.Log.i("WE-DIAG", "transcribe START seq=$seq samples=${samples.size} lang=$lang")
                val transcribeStartNs = System.nanoTime()
                val text = runBlocking {
                    retry.retry { backend.transcribe(ctx, samples, lang) }
                }
                // Permanent per-segment RTF instrumentation (3.6.0, Workstream A3): the number
                // the tier-consolidation and GPU decision gates read, measured on the owner's
                // device instead of estimated. Includes retry time deliberately — it is the wall
                // cost the user actually paid for this segment. Numbers only, never transcript
                // content. Grep "segment-timing:".
                android.util.Log.i(
                    "WE-DIAG",
                    SegmentTiming.line(
                        audioMs = SegmentTiming.audioMs(samples.size),
                        transcribeMs = (System.nanoTime() - transcribeStartNs) / 1_000_000,
                    ),
                )
                // Strip whisper's non-speech markers ([BLANK_AUDIO], [ Silence ], (music), …) so
                // they are never typed into the user's field.
                val cleaned = TranscriptText.clean(text)
                // Never log transcript content — logcat is readable by adb/other tooling and the
                // product promise is that transcriptions stay on-device. Lengths only.
                android.util.Log.i(
                    "WE-DIAG",
                    "transcribe DONE seq=$seq rawLen=${text.length} cleanLen=${cleaned.length}",
                )
                if (cleaned.isBlank()) {
                    // EmptyExpected, NEVER EmptyUnexpected, for the on-device engine.
                    //
                    // A blank here means the NATIVE side already decided there was nothing to
                    // transcribe, and in production that decision is Silero VAD's (whisper_jni.cpp
                    // returns empty as soon as the VAD filter yields zero speech). VAD is far
                    // stricter than any amplitude test: the 800 ms of room tone that sits in the
                    // buffer between the last VAD-triggered commit and the user's stop tap is
                    // "no speech" to the VAD while its PEAK is several times the native
                    // peak-energy gate (0.005) — that gate is only the fallback for when no VAD
                    // model is available. Classifying blanks by peak would therefore stamp a lost-
                    // segment marker on the ordinary end of ordinary sessions.
                    //
                    // Kotlin cannot tell "VAD proved silence" from "whisper genuinely produced
                    // nothing" through the returned empty string, so the honest answer for local
                    // is EmptyExpected — which is also byte-for-byte the pre-existing behaviour:
                    // nothing typed, no marker. AudioMath.peak's own contract names its use as a
                    // gate "before an expensive or billable operation", i.e. an engine with no VAD
                    // of its own; that is where the peak split belongs.
                    android.util.Log.i("WE-DIAG", "transcribe result blank/non-speech -> empty")
                    SegmentOutcome.EmptyExpected
                } else {
                    SegmentOutcome.Text(cleaned)
                }
            }
        } catch (t: Throwable) {
            android.util.Log.w("WE-DIAG", "transcribe THREW", t)
            // See the ctx==0 branch above. The reason string is a fixed constant, never the
            // exception's message: a native message can quote the input it choked on.
            if (listener === myListener) myListener.onError(t.message ?: "Transcription failed")
            SegmentOutcome.Lost(TRANSCRIBE_FAILED)
        }
        // Guard: only fire if the listener hasn't been replaced/nulled since commit().
        if (listener === myListener) myListener.onSegmentResolved(seq, outcome)
    }

    /**
     * Ends the current session. Detaches the listener (any already-queued transcriptions become
     * no-ops via the identity guard) and clears the audio buffer. Delivers [Listener.onClosed]
     * synchronously to the caller before returning.
     *
     * NOTE: this does NOT forcibly cancel native work that is already executing on the executor
     * thread; it only prevents stale callbacks from being delivered once that work eventually
     * completes.
     */
    override fun close() {
        val listener = this.listener
        synchronized(bufferLock) { buffer.reset() }
        this.listener = null
        listener?.onClosed()
    }

    /**
     * Loads the native context ahead of the first session so the first recording doesn't pay
     * the model load + GPU kernel compile (~7 s on Adreno OpenCL) inside CONNECTING. Silent on
     * failure: connect() retries the load with full error reporting. A model switch between
     * prewarm and connect is also connect()'s job — this only fills an EMPTY context slot.
     */
    override fun prewarm() {
        val modelPath = modelPathProvider.installedModelPath() ?: return
        if (ctxPtr != 0L) return
        executor.execute {
            if (ctxPtr != 0L) return@execute
            try {
                val loaded = runBlocking { loadRetry.retry { backend.load(modelPath) } }
                if (loaded != 0L) {
                    ctxPtr = loaded
                    loadedModelPath = modelPath
                    android.util.Log.i("WE-DIAG", "prewarm: ctx loaded")
                }
            } catch (t: Throwable) {
                Log.w("LocalWhisperEngine", "prewarm load failed (connect() will retry)", t)
            }
        }
    }

    /**
     * Frees the cached native context (e.g. from onTrimMemory under memory pressure).
     * The context reloads lazily on the next connect(). Runs on the executor so it never
     * races an in-flight transcription.
     */
    override fun releaseContext() {
        executor.execute {
            val ctx = ctxPtr
            if (ctx != 0L) {
                try {
                    backend.release(ctx)
                } catch (t: Throwable) {
                    Log.w("LocalWhisperEngine", "releaseContext failed", t)
                }
                ctxPtr = 0L
                loadedModelPath = null
            }
        }
    }

    /**
     * Blocks the CALLING thread until all work already queued on the native [executor] (notably a
     * final commit()'s transcribe) has finished, or [timeoutMs] elapses. Returns true if it
     * drained. The caller uses this to ensure the final segment's onSegmentResolved has been
     * delivered — while the listener is still attached — BEFORE close() detaches it. MUST be
     * called off the main thread. Submitting an empty fence task preserves the single-thread
     * native-access contract (it never touches ctxPtr).
     */
    override fun awaitIdle(timeoutMs: Long): Boolean {
        val startNs = System.nanoTime()
        val latch = java.util.concurrent.CountDownLatch(1)
        try {
            executor.execute { latch.countDown() }
        } catch (t: java.util.concurrent.RejectedExecutionException) {
            return true  // executor already shut down — nothing is in flight
        }
        val drained = try {
            latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (t: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        // C1 finalize-timing: everything queued on the native executor ahead of the fence —
        // retries, or (first session) the safety-net model load — is paid for inside this number.
        android.util.Log.i(
            "WE-DIAG",
            "finalize-timing: local-drain=${(System.nanoTime() - startNs) / 1_000_000}ms",
        )
        return drained
    }

    /**
     * Full teardown for service/process end. Frees the native context (submitted on the executor,
     * so it never races in-flight work) and THEN shuts the executor down so its single worker
     * thread does not leak for the lifetime of a long-running foreground service. shutdown() lets
     * the already-queued release task finish before the thread terminates. After this call the
     * engine must not be reused.
     */
    override fun shutdown() {
        executor.execute {
            val ctx = ctxPtr
            if (ctx != 0L) {
                try {
                    backend.release(ctx)
                } catch (t: Throwable) {
                    Log.w("LocalWhisperEngine", "shutdown release failed", t)
                }
                ctxPtr = 0L
                loadedModelPath = null
            }
        }
        executor.shutdown()
        controlExecutor.shutdown()
    }
}
