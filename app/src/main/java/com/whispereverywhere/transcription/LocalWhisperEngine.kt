package com.whispereverywhere.transcription

import android.util.Log
import com.whispereverywhere.audio.EndpointerTuning
import com.whispereverywhere.transcription.speakers.SpeakerAssigner
import com.whispereverywhere.transcription.speakers.SpeakerSpans
import com.whispereverywhere.util.AudioMath
import com.whispereverywhere.util.RetryPolicy
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * On-device whisper.cpp engine. Buffers PCM16 audio, and on commit runs one batch
 * transcription of the buffered segment on a single-thread executor (segments serialize).
 * Intra-segment deltas (3.6.0) are PREVIEW-ONLY: the native new-segment callback streams the
 * in-flight text to onDelta, throttled (~150 ms), but committed text still comes exclusively
 * from the exactly-one onSegmentResolved per committed segment — the final-only commit contract.
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
    /**
     * The tier this engine runs on. `WhisperNativeBackend` — whisper.cpp — is the default and is
     * what every caller but one passes; `FloatingBubbleService.warmLocalEngine` passes
     * `NpuBackendSelector.backendFor(…)`, which answers `NpuWhisperBackend` for the 4.0 npu tier
     * (Q9).
     *
     * **It is a `val`, and that is load-bearing rather than incidental.** Changing tiers means
     * building a NEW engine, not reassigning this field, and the service does exactly that. A
     * `var` would let a mid-session fallback swap the backend while [executor] is inside
     * `transcribeStreaming` — handing a running segment a native context that is being torn down —
     * and it would leave [ctxPtr] and [loadedModelPath] describing the previous backend's session
     * with nothing in the type system objecting. The cost of the `val` is one rebuilt engine per
     * tier change; the cost of the `var` is a class of races that only a device can find.
     */
    private val backend: WhisperBackend = WhisperNativeBackend,
    /**
     * MUST be single-threaded. All native whisper context ([ctxPtr]) reads, writes, loads,
     * and frees are serialized by executing exclusively on this thread. Passing a
     * multi-threaded executor will cause data races on the native context pointer.
     */
    private val executor: ExecutorService = Executors.newSingleThreadExecutor(),
    /**
     * Clock feeding the preview-delta throttle (3.6.0 Workstream D). Injectable so JVM tests
     * drive throttling deterministically. Milliseconds; only differences are used.
     */
    private val deltaClock: () -> Long = System::currentTimeMillis,
    /**
     * The bundled Silero model's path, for the NPU tier's speaker route ONLY (4.10, the Fold6
     * defect) — the same path the backend seam hands `transcribeRaw`, read through a lambda for
     * the same reason the clock above is: the resolver reaches
     * `WhisperEverywhereApp.getInstance()` and answers null off a device, which would make the
     * route untestable rather than merely untested.
     *
     * Null means this device has no VAD model at all. The route is then SKIPPED — never handed an
     * invented path, which native would answer with an init failure and a log line per chunk.
     */
    private val vadModelPath: () -> String? = { VadModel.path() },
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

        /** PCM16 mono @16 kHz: 16 000 samples/s * 2 bytes = 32 bytes per millisecond. */
        const val BYTES_PER_MS = 32
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

    /**
     * THIS SESSION'S SPEAKER ASSIGNER, or null when there is none (4.10 Task 3, spec §3.2).
     *
     * The service owns the lifecycle and this engine owns the one thing the service cannot reach:
     * the chunk's raw samples. They exist inside [runSegment], on the native executor, for the
     * length of one transcribe — the buffer the VAD's `origStart`/`origEnd` bounds index, and the
     * only audio in the process where a speaker's own voice is unstitched. Copying it across the
     * service seam to be sliced there would mean shipping 30 s of PCM per commit to produce a
     * paragraph break; instead nothing new crosses the seam at all and the assigner is handed
     * samples where they already are.
     *
     * **A `var`, set per session, and re-pointed even when the answer is null.** The service
     * caches ONE engine across sessions (`warmLocalEngine` — it owns the native context), so an
     * engine left holding the previous session's assigner would fingerprint a new session's
     * voices into the old session's numbering, which is the spec's *"speaker numbers restart at 1
     * each session"* broken with every unit test still green. `SpeakerWiringPinTest` pins both
     * halves: the re-point at session start and the detach at teardown, in that order.
     *
     * Null is the ordinary case and is every pre-4.10 behaviour exactly: speaker detection off,
     * a cloud session, or a backend that publishes no geometry.
     */
    @Volatile
    var speakerAssigner: SpeakerAssigner? = null

    /**
     * Session-scoped language pin (3.6.0 Workstream B). Auto-language sessions only: once the
     * first speech-producing segment's detection lands, later segments pass the concrete code
     * and skip multilingual whisper's per-segment detect-encode pass. Reset in [connect];
     * written only behind the stale-listener guard in [runSegment], so a previous session's
     * late segment can never pin the new session. 4.1 L7: consulted and fed only while the
     * LIVE backend does not declare [WhisperBackend.detectsPerUtterance] — see [runSegment] —
     * so a session that falls back mid-life finds it exactly as a fresh 3.7 session would:
     * empty, and fed by the first post-fallback speech segment.
     */
    private val languagePin = LanguagePin()

    /**
     * Preview-delta rate limiter (3.6.0 Workstream D). Touched only on the native executor
     * thread: reset at segment start, checked inside the native new-segment callback, which
     * whisper_full invokes on that same thread.
     */
    private val deltaThrottle = DeltaThrottle(now = deltaClock)

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
        // Per-session language detection (spec Workstream B): the pin never outlives a session,
        // so a user switching languages BETWEEN sessions always re-detects.
        languagePin.reset()

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
     *
     * THE SPEECH EVIDENCE (4.3.2): this no-argument form carries [SpeechEvidence.UNKNOWN] and is
     * therefore never skipped. It is what every engine-internal commit runs through — the 30 s
     * overflow backstop in [sendAudio], the cloud fallback's local rescue — and what any caller
     * that has no endpointer behind it gets: the pre-4.3.2 behaviour, byte for byte.
     */
    override fun commit(): Long = commit(SpeechEvidence.UNKNOWN)

    /**
     * [commit] with THE SPEECH EVIDENCE the funnel read for this buffer (4.3.2). The seq is
     * allocated and the buffer cut exactly as before; what the evidence decides is only whether
     * the cut segment RUNS — see [dispatch]. A KNOWN count under
     * [EndpointerTuning.MIN_SPEECH_EVIDENCE_MS] resolves [SegmentOutcome.EmptyExpected] without
     * a backend call; everything else is the path above.
     */
    override fun commit(evidence: SpeechEvidence): Long {
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
        dispatch(seq, pcm, lang, evidence, myListener)
        return seq
    }

    /**
     * [commit], minus a trailing tail (3.7, Workstream D). See the interface KDoc for why
     * `retainMs <= 0` must be indistinguishable from [commit] — the first line here is that
     * guarantee, not an optimisation.
     *
     * The split is computed INSIDE bufferLock together with the seq, for the same reason [commit]
     * allocates its seq there: the capture thread is still calling sendAudio, and a snapshot taken
     * outside the lock would let a chunk land between the read and the rewrite.
     *
     * THE SPEECH EVIDENCE (4.3.2): the no-argument form is [SpeechEvidence.UNKNOWN], never
     * skipped, exactly as [commit]'s is.
     */
    override fun commitRetainingTailMs(retainMs: Long): Long =
        commitRetainingTailMs(retainMs, SpeechEvidence.UNKNOWN)

    /**
     * [commitRetainingTailMs] with THE SPEECH EVIDENCE for the whole buffer (4.3.2), tail
     * included. The committed part is judged on that whole-buffer count through [dispatch] —
     * which can only over-credit it, never skip a part whose evidence sat in the tail — and the
     * tail stays in the buffer whether or not the part ran: a skipped cap cut retains exactly what
     * an encoded one would, and the endpointer carries the tail's own evidence forward for it
     * (`Endpointer.onBufferCommitted`).
     */
    override fun commitRetainingTailMs(retainMs: Long, evidence: SpeechEvidence): Long {
        if (retainMs <= 0L) return commit(evidence)

        val myListener = this.listener
        if (myListener == null) {
            android.util.Log.i("WE-DIAG", "commit: no listener (session ended), skipped")
            return NO_SEGMENT
        }
        val lang = this.language

        val (seq, pcm, retainedBytes) = synchronized(bufferLock) {
            val snapshot = buffer.toByteArray()
            if (snapshot.isEmpty()) {
                android.util.Log.i("WE-DIAG", "commit: pcmBytes=0 -> nothing to cut")
                return NO_SEGMENT
            }
            // retainMs * 32 is always a multiple of 32, so this mask is a NO-OP on any buffer that
            // honours sendAudio's PCM16-frame contract. It bites only when the coerce clamps to an
            // ODD snapshot.size (a caller that wrote a half frame): the orphan byte is pushed into
            // the COMMITTED segment so the retained tail stays a whole number of frames. That can
            // leave the committed segment as short as 1 byte / 0 samples — pinned by
            // theRetainedTailIsAlignedDownToAWholeFrameOnAnOddBuffer.
            val retain = (retainMs * BYTES_PER_MS)
                .coerceAtMost(snapshot.size.toLong())
                .toInt() and 1.inv()
            val cut = snapshot.size - retain
            if (cut <= 0) {
                // The offer covers the whole window. A cap that has already fired must never
                // defer its entire buffer, so this degrades to a plain full commit. On an ODD
                // snapshot this branch is NOT reached — the alignment above leaves cut == 1 and
                // size-1 bytes are deferred — but odd buffers violate sendAudio's frame contract
                // and are unreachable from capture. See the ruling in review-D6-verdict.md §4.
                buffer.reset()
                Triple(nextSeq++, snapshot, 0)
            } else {
                buffer.reset()
                buffer.write(snapshot, cut, retain)
                Triple(nextSeq++, snapshot.copyOfRange(0, cut), retain)
            }
        }
        android.util.Log.i("WE-DIAG", "commit: seq=$seq pcmBytes=${pcm.size} samples=${pcm.size / 2}")
        android.util.Log.i(
            "WE-DIAG",
            "cap-cut split: seq=$seq retainedTailBytes=$retainedBytes retainedMs=${retainedBytes / BYTES_PER_MS}",
        )
        dispatch(seq, pcm, lang, evidence, myListener)
        return seq
    }

    /**
     * THE ONE DISPATCH (4.3.2): a cut segment either RUNS, or — under the speech-evidence floor —
     * resolves [SegmentOutcome.EmptyExpected] without running. Both paths are entered with a seq
     * the caller allocated under `bufferLock`; both go through [executor], so a skipped seq
     * resolves in commit order behind the segments queued ahead of it (the single-thread contract
     * the class KDoc gives, and what keeps `SegmentOrderer` a pass-through); and both end in
     * [resolve], the single `onSegmentResolved` site, so the every-seq-resolves-exactly-once
     * contract has one body to hold.
     *
     * The skip is `EmptyExpected` and not a new outcome, deliberately: downstream it means "this
     * audio held no speech", which is precisely the claim — the endpointer scored every frame of
     * it and found under [EndpointerTuning.MIN_SPEECH_EVIDENCE_MS] of onset. `SegmentOrderer`
     * releases nothing for it and moves on; `SegmentQueueDepth` decrements on its resolution like
     * any other; `FallbackPolicy.reconcile` trusts it as a verdict. An UNKNOWN count, or a KNOWN
     * one at or over the floor, is today's path.
     *
     * The line says a skip happened and why — numbers only, never content: the audio is dropped
     * here without a String ever existing for it.
     */
    private fun dispatch(
        seq: Long,
        pcm: ByteArray,
        lang: String?,
        evidence: SpeechEvidence,
        myListener: TranscriptionEngine.Listener,
    ) {
        if (evidence.isUnder(EndpointerTuning.MIN_SPEECH_EVIDENCE_MS)) {
            android.util.Log.i(
                "WE-DIAG",
                "commit: seq=$seq skipped=no-speech-evidence speechMs=${evidence.speechMs} " +
                    "pcmMs=${pcm.size / BYTES_PER_MS}",
            )
            executor.execute { resolve(seq, SegmentOutcome.EmptyExpected, clearPreview = false, myListener) }
            return
        }
        executor.execute { runSegment(seq, pcm, lang, myListener) }
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
        // D (3.6.0): true once at least one preview delta was forwarded for THIS segment, so
        // the strip is cleared exactly when something was put on it — and never otherwise.
        var streamedPreview = false
        // 4.10 Task 3: the chunk's own samples, kept reachable for the speaker pass at the bottom
        // of this function. Hoisted rather than re-decoded: `AudioMath.pcm16ToFloat(pcm)` a second
        // time would allocate another 30 s buffer per commit, and a re-decode is also a second
        // chance to disagree with the buffer the native VAD bounds were measured against. Null for
        // every branch where whisper never ran.
        var chunkSamples: FloatArray? = null
        // 4.10 (the Fold6 defect): whether the LIVE backend publishes segment geometry AT ALL —
        // a property of the backend, not of this chunk — hoisted for the same reason the samples
        // are, and snapshotted beside the geometry read so the two describe the same instant.
        //
        // It answers neither of the two questions that look like it, and the VAD route below is
        // wrong if it is given either of them instead:
        //  - "does the outcome carry windows" would drag in every CPU chunk whose geometry
        //    produced no windows or no spans (an empty VAD, a geometry that maps onto no
        //    surviving text);
        //  - "was THIS chunk's geometry null" would drag in every CPU chunk whose snapshot was
        //    lost, and `WhisperNativeBackend` has two ordinary ways to lose one: `captureGeometry`
        //    swallowing an allocation failure, and the process-global slot being re-tagged by an
        //    interleaved batch chunk between the transcribe and the read (see the read below).
        // Both of those are chunks that must keep 4.9's answer — no labels, no second VAD pass —
        // rather than quietly collapse to one speaker for the whole chunk.
        //
        // Starts TRUE, which is the conservative value: a segment that never reaches the read
        // (whisper never ran, or the transcribe threw) must not take the VAD route on the
        // strength of a flag nobody set.
        var backendPublishesGeometry = true
        // 4.11 Task 4: the NPU tier's SENTENCE BOUNDS for this chunk — `[t0cs, t1cs, byteStart,
        // byteEnd]` per sentence, or empty. Hoisted for the reason the two above are, and for
        // one more that is stronger than either: the TEXT is cut by this array here, on this
        // thread, and the AUDIO is cut by it ~60 ms later on the embed thread, and the halves
        // are matched by POSITION. ONE read, ONE array, handed to both — a second read of a
        // process-global slot could answer differently and put one sentence's speaker on
        // another's words. Empty for every tier but the live NPU arm, which is what keeps the
        // CPU and GPU routes byte-for-byte what they were.
        var backendSentences: IntArray = IntArray(0)
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
                val samples = AudioMath.pcm16ToFloat(pcm).also { chunkSamples = it }
                // B (3.6.0) / L7 (4.1): an explicit language passes through untouched under
                // BOTH arms — the ruling's absolute half. A per-utterance backend (the live NPU
                // tier, where detect is ~4.5 ms against a ~405 ms encode) is handed `lang`
                // UNCHANGED: null stays null, so an auto session re-detects EVERY segment and
                // may honestly start in one language and finish in another. A latching backend
                // rides the session pin once the first speech segment detected it — the 3.7
                // behaviour byte-for-byte, because whisper.cpp's detect pass is roughly half of
                // multi's steady-state cost and the latch is what amortises it. The property is
                // read off the LIVE backend PER SEGMENT, never snapshotted at connect:
                // NpuWhisperBackend answers fallbackBackend == null, so the first segment after
                // a mid-life decline lands in the else arm and re-acquires the CPU latch from
                // that point.
                val effectiveLang =
                    if (backend.detectsPerUtterance) lang else languagePin.languageFor(lang)
                android.util.Log.i(
                    "WE-DIAG",
                    "transcribe START seq=$seq samples=${samples.size} lang=$lang effective=$effectiveLang",
                )
                // D (3.6.0 partial streaming): whisper.cpp's new-segment callback arrives HERE,
                // on this same executor thread, WHILE backend.transcribeStreaming is still
                // executing — and while WhisperNativeBackend holds the process-global
                // NativeComputeGate — so this closure stays strictly lock-free: throttle check
                // + listener forward, nothing else. Never bufferLock, never a backend re-entry,
                // never logging (delta text IS user speech). Deltas are PREVIEW-ONLY: committed
                // text comes exclusively from the returned String via segment resolution below
                // (the final-only commit contract, untouched). Stale sessions are dropped by
                // the exact guard resolutions use: `listener === myListener`.
                val transcribeStartNs = System.nanoTime()
                val text = runBlocking {
                    retry.retry {
                        // INSIDE the retry lambda, not above it: a retried attempt re-decodes
                        // this segment from scratch, so its first delta must render immediately
                        // too. Resetting once per segment would leave the retry's opening words
                        // inside the previous attempt's throttle window and swallow them.
                        deltaThrottle.reset()
                        backend.transcribeStreaming(ctx, samples, effectiveLang) { running ->
                            if (listener === myListener && deltaThrottle.shouldEmit()) {
                                streamedPreview = true
                                myListener.onDelta(running)
                            }
                        }
                    }
                }
                // Permanent per-segment RTF instrumentation (3.6.0, Workstream A3; extended by
                // 3.7 Workstream F with seq and the native cost counters): the number the
                // tier-consolidation, GPU and cadence decision gates read, measured on the owner's
                // device instead of estimated. Includes retry time deliberately — it is the wall
                // cost the user actually paid for this segment. Numbers only, never transcript
                // content. Grep "segment-timing:".
                //
                // transcribeMs is taken BEFORE the counters are read, so the diagnostic query can
                // never inflate the number it is annotating. lastSegmentStats describes the call
                // that just returned and is null for any backend without native counters, in
                // which case the line degrades to the seq-only form rather than forging zeros.
                //
                // READ ONCE, HERE, AND NOWHERE ELSE. The position is the whole correctness
                // argument, on three axes:
                //   - AFTER the retry returns, on this same executor thread: the counters are a
                //     one-slot snapshot tagged with the ctx that last ran, so they describe the
                //     LAST attempt (see SegmentTiming.line's retry paragraph). A read hoisted
                //     above or inside retry.retry{} would report a previous segment's encoder
                //     cost with nothing about the numbers looking wrong.
                //   - On the SUCCESS path only, never in a finally. A transcribe that threw is
                //     handled below as Lost, and its counters are — correctly — invalidated by
                //     the backend seam; emitting there would pair a timing line with a segment
                //     that produced no transcript.
                //   - A NULL answer after a SUCCESSFUL transcribe is NORMAL, not an anomaly: the
                //     native counters are process-global, so an interleaved batch chunk can
                //     re-tag the slot between the two calls. The line simply omits the fields.
                //     Never warn, never assert, never re-read — a second read is a second answer.
                val transcribeMs = (System.nanoTime() - transcribeStartNs) / 1_000_000
                val nativeStats = backend.lastSegmentStats(ctx)
                android.util.Log.i(
                    "WE-DIAG",
                    SegmentTiming.line(
                        seq = seq,
                        audioMs = SegmentTiming.audioMs(samples.size),
                        transcribeMs = transcribeMs,
                        stats = nativeStats,
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
                // B (3.6.0 language pinning): query the detection only for segments that PAID the
                // native detect pass (auto session, nothing pinned yet) and only when whisper
                // demonstrably ran on THIS audio — a non-blank result. Every native early return
                // (VAD-empty, energy gate) yields a blank, and whisper_full_lang_id would then be
                // STALE (it persists on the ctx across calls, even across sessions). The stale-
                // listener guard — the exact `listener === myListener` identity check resolutions
                // use — keeps a dead session's late segment from pinning the new session.
                //
                // L7 (4.1): and NEVER for a per-utterance backend. Its resolutions are
                // per-segment answers, not a session latch, and feeding the pin from them would
                // hand a STALE code to the first post-fallback segment — the false latch the
                // fallback edge must not inherit. The clause reads the LIVE backend AFTER the
                // transcribe returned, so a backend that fell back DURING this very segment
                // already answers false here, and this segment's detection is the one that
                // re-acquires the CPU latch.
                if (lang == null && effectiveLang == null && cleaned.isNotBlank() &&
                    listener === myListener && !backend.detectsPerUtterance
                ) {
                    val detected = backend.detectedLanguage(ctx)
                    languagePin.onDetected(sessionLanguage = lang, detected = detected)
                    // Language code only — never transcript content.
                    android.util.Log.i("WE-DIAG", "language-pin: detected=$detected")
                }
                // 4.10 speaker labels: the segment geometry of the transcribe that just returned,
                // read HERE for the same three reasons the stats read above is here — after the
                // retry (so it describes the LAST attempt), on the success path only, and exactly
                // once. With one difference that raises the stakes rather than lowering them:
                // this is read FOR A DECISION, not for a log line, so a stale snapshot does not
                // print a wrong number, it puts a speaker's label on another speaker's sentence.
                //
                // A null answer is normal and is the pre-4.10 path: no native geometry (the NPU
                // tier while it is live), or the process-global slot was re-tagged by an
                // interleaved batch chunk between the two calls. The outcome then carries no
                // spans and every delivery surface behaves exactly as it did in 4.9.
                //
                // `publishesGeometry` is read in the SAME breath, off the same live backend, and
                // it is what separates those two causes — a tier that never publishes from a
                // chunk that lost its snapshot. Only the first takes the VAD route at the foot of
                // this function; the second keeps 4.9's answer. Reading it here rather than down
                // there is what stops a mid-segment NPU decline from being answered by a flag
                // that has since flipped.
                backendPublishesGeometry = backend.publishesGeometry
                val geometry = backend.lastGeometry(ctx)
                // 4.11 Task 4, read in the SAME breath and off the same live backend as the two
                // above, for the same reason: all three describe one instant, and a sentence
                // array from a later decode would name bytes this chunk's text does not have.
                backendSentences = backend.lastSentences(ctx)
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
                    textOutcome(cleaned, text, geometry, backendSentences)
                }
            }
        } catch (t: Throwable) {
            android.util.Log.w("WE-DIAG", "transcribe THREW", t)
            // See the ctx==0 branch above. The reason string is a fixed constant, never the
            // exception's message: a native message can quote the input it choked on.
            if (listener === myListener) myListener.onError(t.message ?: "Transcription failed")
            SegmentOutcome.Lost(TRANSCRIBE_FAILED)
        }
        resolve(seq, outcome, clearPreview = streamedPreview, myListener)
        // ===================== 4.10 Task 3 — THE SPEAKER PASS, AND IT IS LAST =====================
        // BELOW the delivery, deliberately and structurally. Everything above this line is the
        // user's text reaching the user; this is a paragraph break and a label, and spec §3.3's
        // whole cost argument is that the commit floors (6 000 / 8 000 ms) were measured with this
        // thread doing whisper's work and nothing else. `assign` only queues — it returns before
        // the first sample is read, on the assigner's own `speaker-embed` thread — so even here
        // the cost to this thread is one submission. `SpeakerWiringPinTest` pins the order, the
        // single call site, and that this engine never blocks on that executor.
        //
        // Three guards, one for each way this can be wrong:
        //  - a null assigner is the ordinary case (detection off, cloud, or a session with none);
        //  - `listener === myListener` is the SAME stale-session guard the resolution path uses: a
        //    dead session's late segment must never feed the new session's tracker, which is the
        //    one way a speaker's number could carry across the session boundary;
        //  - a null `vad` means no geometry (cloud, the NPU tier while it is live, or a chunk the
        //    VAD found no speech in), and spec §2 forbids reading that as one speaker.
        //
        // ===================== TWO ROUTES SINCE 4.10's NPU FIX, IN THIS ORDER =====================
        // GEOMETRY FIRST, AND UNCHANGED. Whenever whisper.cpp ran its VAD filter and its decoder
        // this is the call that was here before and the only one that fires — per window, per
        // sentence, with the chunk's text cut against the same window list. The CPU and GPU tiers
        // must behave EXACTLY as they do today, and `SpeakerWiringPinTest` fails if this branch
        // moves, loses its guards, or stops being first.
        //
        // THE VAD ROUTE SECOND, and only for a BACKEND THAT PUBLISHES NO GEOMETRY AT ALL. That is
        // the NPU tier while its arm is live: it runs its own encoder and decoder on the HTP,
        // never calls the whisper.cpp VAD filter, and therefore publishes nothing for the
        // assigner to stand on — which is why the owner's Fold6, a device the 4.3 one-tier rule
        // offers no CPU rung, produced no speaker changes at all. `assignVadRoute` re-runs the
        // VAD on the embed thread and labels the chunk as a whole.
        //
        // THE TEST IS A BACKEND CAPABILITY, and neither of the two per-chunk tests that look like
        // it (round 1 of review corrected this from the second of them):
        //  - `windows == null` would drag in every CPU chunk whose geometry produced no windows
        //    or no spans;
        //  - "this chunk's geometry was null" would drag in every CPU chunk whose snapshot was
        //    LOST, which `WhisperNativeBackend` has two ordinary ways to do — a swallowed
        //    allocation failure in `captureGeometry`, and an interleaved batch chunk re-tagging
        //    the process-global slot between the transcribe and the read. Neither of those says
        //    the tier cannot split a chunk's text, and both must keep 4.9's answer.
        // `WhisperBackend.publishesGeometry` says the structural thing instead, it is read beside
        // the geometry itself, and `SpeakerWiringPinTest` fails if this gate becomes a per-chunk
        // one again.
        //
        // The path handed over below is the same one the backend seam gives `transcribeRaw`; a
        // null one means this device has no VAD model at all, and the route is SKIPPED rather
        // than given an invented path.
        val assigner = speakerAssigner
        if (assigner != null && listener === myListener) {
            val committed = outcome as? SegmentOutcome.Text
            val windows = committed?.windows
            val samples = chunkSamples
            if (windows != null && samples != null) {
                assigner.assign(seq, samples, windows)
            } else if (!backendPublishesGeometry && samples != null && committed != null &&
                committed.text.isNotBlank()
            ) {
                vadModelPath()?.let { assigner.assignVadRoute(seq, samples, it, backendSentences) }
            }
        }
    }

    /**
     * Builds the `Text` outcome, attaching the 4.10 speaker geometry when this chunk has any.
     *
     * [cleaned] is the committed text and is passed through UNTOUCHED — the whole point of this
     * function is that the spans ride ALONGSIDE it. [raw] is the pre-clean text, and it is the
     * one the byte offsets belong to: the native side reports offsets into the UTF-8 it returned,
     * and `TranscriptText.clean` collapses whitespace and deletes marker groups, so every offset
     * past the first edit would be wrong against the cleaned string. `raw.toByteArray(UTF_8)`
     * reproduces the buffer `transcribeRaw` returned, because the decode that produced [raw] was
     * the inverse of this encode.
     *
     * BOTH FIELDS OR NEITHER. Three separate conditions collapse to "no geometry", and all three
     * produce the byte-identical pre-4.10 outcome rather than an empty list:
     *  - the backend published none (null) — no native VAD filter ran under it;
     *  - the VAD produced no segments (an empty array) — which the spec forbids reading as one
     *    speaker, so there is nothing to attach;
     *  - the segments produced no spans — a geometry that maps onto no surviving text, which is
     *    what a stale snapshot looks like from here.
     * A half-attached outcome (`windows` set, `spans` empty) would make the service's "does this
     * chunk have speakers" test true for a chunk with nothing to label.
     *
     * The WINDOW list is built here, once, and handed to BOTH halves: the spans are cut against
     * it and the assigner fingerprints it. Spike session 4 made that sharing load-bearing — a
     * long VAD segment is now several windows, so a second list computed independently
     * downstream could disagree with this one and put one window's speaker on another's
     * sentence.
     *
     * ### THE NPU TIER'S HALF — SPANS WITHOUT WINDOWS (4.11 Task 4)
     *
     * [sentences] is `WhisperBackend.lastSentences`, and it is read only where [geometry] is
     * null, which on a backend that publishes geometry means a LOST snapshot and on the live
     * NPU arm means the structural absence the tier was always going to have. Only the second
     * ever carries sentences: `WhisperNativeBackend` does not implement the member and
     * `NpuWhisperBackend` deliberately does not delegate it after a decline, so the CPU and GPU
     * tiers reach this branch with an empty array and keep their answer byte for byte. Its own
     * geometry is strictly finer anyway — per TOKEN since Task 1.
     *
     * **`windows` stays NULL on that path, and that is the routing decision rather than an
     * omission.** `runSegment`'s fork reads `outcome.windows != null` as "this chunk's windows
     * are already built, fingerprint exactly them"; the NPU route's windows cannot be built
     * here, because they need a VAD pass this thread must not pay — it is the ~60 ms
     * `assignVadRoute` spends on the embed thread, below delivered text, and moving it here
     * would put it back on the whisper thread whose cadence floors were measured without it. So
     * the two halves are cut from the SAME sentence array in two places, which is exactly why
     * `runSegment` reads that array once and hands the same object to both.
     *
     * "BOTH FIELDS OR NEITHER" therefore reads "spans or nothing" here. The service's
     * "does this chunk have speakers" test is on `spans`, and the half that would be wrong is
     * the other one — windows with no spans, a chunk to fingerprint with nothing to label.
     */
    private fun textOutcome(
        cleaned: String,
        raw: String,
        geometry: SegmentGeometry?,
        sentences: IntArray = IntArray(0),
    ): SegmentOutcome.Text {
        if (geometry == null) return sentenceOutcome(cleaned, raw, sentences)
        val vad = SpeakerSpans.vadSegments(geometry.vadSegments)
        if (vad.isEmpty()) return SegmentOutcome.Text(cleaned)
        val windows = SpeakerSpans.windows(
            raw = geometry.whisperSegments,
            vad = vad,
            tokenTimes = geometry.tokenTimes,
        )
        if (windows.isEmpty()) return SegmentOutcome.Text(cleaned)
        val spans = SpeakerSpans.spans(
            raw = geometry.whisperSegments,
            bytes = raw.toByteArray(Charsets.UTF_8),
            vad = vad,
            // 4.11: the SAME array both halves were built from. The windows may now be cut at a
            // word, and the spans are what makes such a window addressable in the text — hand
            // one of them the tokens and not the other and the extra windows exist with nothing
            // in them.
            tokenTimes = geometry.tokenTimes,
            windows = windows,
        )
        if (spans.isEmpty()) return SegmentOutcome.Text(cleaned)
        // Numbers only — a span's text IS user speech and never reaches a log line.
        android.util.Log.i(
            "WE-DIAG",
            "speaker-spans: vad=${vad.size} windows=${windows.size} spans=${spans.size} " +
                "tokens=${geometry.tokenTimes.size / 4}",
        )
        return SegmentOutcome.Text(cleaned, spans = spans, windows = windows)
    }

    /**
     * The NPU tier's spans, cut at the sentences its own decoder timestamped (4.11 Task 4) — or
     * the byte-identical pre-4.11 outcome when it timestamped none.
     *
     * Session 7 is why this exists: on the owner's Fold6 a pause-free clip gave the standalone
     * VAD one 9-15 s segment per chunk, the chunk took ONE label, and two people shared it for
     * a minute at a time (`docs/measurements/2026-09-18-speaker-spike.md`). The decoder always
     * knew where each sentence ended; since Task 3 it says so, and this is where the text is cut
     * by it.
     *
     * Empty [sentences] is the ordinary answer and produces the pre-4.11 `Text(cleaned)`: a
     * decode that emitted no timestamps, a parse that refused a backwards stream, a blanked
     * segment, or any tier but the live NPU arm. So is a sentence array that maps onto no
     * surviving text, for the same reason the geometry path refuses an empty span list — a
     * chunk with nothing to label must not look like a chunk with speakers.
     */
    private fun sentenceOutcome(
        cleaned: String,
        raw: String,
        sentences: IntArray,
    ): SegmentOutcome.Text {
        if (sentences.isEmpty()) return SegmentOutcome.Text(cleaned)
        val spans = SpeakerSpans.sentenceSpans(sentences, raw.toByteArray(Charsets.UTF_8))
        if (spans.isEmpty()) return SegmentOutcome.Text(cleaned)
        // Numbers only — a span's text IS user speech and never reaches a log line.
        android.util.Log.i(
            "WE-DIAG",
            "speaker-sentences: sentences=${sentences.size / 4} spans=${spans.size}",
        )
        // No WINDOW list: see this function's caller. The windows are cut from the same array on
        // the embed thread, where the VAD pass they need is already being paid for.
        return SegmentOutcome.Text(cleaned, spans = spans)
    }

    /**
     * THE RESOLUTION PATH — the one `onSegmentResolved` site in this engine, reached by
     * [runSegment] for every segment that ran and by [dispatch] for every segment the
     * speech-evidence floor skipped (4.3.2). On the native executor thread in both cases.
     *
     * [clearPreview] — D (3.6.0): the segment reached a terminal outcome, so the in-flight
     * preview is stale; a blank delta clears the strip (the service's onDelta hides it on blank)
     * before the resolution lands in the accumulating window; both hop to Main via the same
     * FIFO, so the clear always renders first. Emitted only when this segment actually streamed,
     * so non-streaming backends keep the exact 3.5.0 callback sequence — and a skipped segment
     * never streamed, so it never clears.
     */
    private fun resolve(
        seq: Long,
        outcome: SegmentOutcome,
        clearPreview: Boolean,
        myListener: TranscriptionEngine.Listener,
    ) {
        if (clearPreview && listener === myListener) myListener.onDelta("")
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
     * Prewarm that also handles a MODEL SWITCH (3.6.0, Workstream E1). [prewarm] deliberately
     * fills only an EMPTY slot, so after the user switched tiers the STALE context sat loaded and
     * the next session paid the release+load (~7 s on the GPU path) inline in CONNECTING. This
     * runs the same release-then-load sequence connect() would — ahead of time, on the native
     * executor, so it serializes with any queued work and the next connect() takes its fast path.
     *
     * Same-model and empty-slot calls converge on the right thing (no-op / plain load); a null
     * installed path (model deleted) no-ops entirely, exactly like [prewarm]. Silent on failure,
     * also like [prewarm]: connect() retries the load with full error reporting.
     *
     * Callers must NOT invoke this while a session is live: releasing the context mid-session
     * would resolve every later segment Lost. The bubble's debounced collector gates on IDLE.
     */
    fun prewarmModelSwitch() {
        executor.execute {
            // A live session owns this ctx: freeing it here resolves every later segment Lost.
            // Dropping the switch is safe — connect() runs the same release-then-load itself.
            if (listener != null) return@execute
            val modelPath = modelPathProvider.installedModelPath() ?: return@execute
            if (ctxPtr != 0L && modelPath == loadedModelPath) return@execute
            val ctx = ctxPtr
            if (ctx != 0L) {
                android.util.Log.i(
                    "WE-DIAG",
                    "prewarmModelSwitch: releasing stale ctx ($loadedModelPath -> $modelPath)",
                )
                try {
                    backend.release(ctx)
                } catch (t: Throwable) {
                    Log.w("LocalWhisperEngine", "prewarmModelSwitch release failed", t)
                }
                ctxPtr = 0L
                loadedModelPath = null
            }
            try {
                val loaded = runBlocking { loadRetry.retry { backend.load(modelPath) } }
                if (loaded != 0L) {
                    ctxPtr = loaded
                    loadedModelPath = modelPath
                    android.util.Log.i("WE-DIAG", "prewarmModelSwitch: ctx loaded")
                }
            } catch (t: Throwable) {
                Log.w("LocalWhisperEngine", "prewarmModelSwitch load failed (connect() will retry)", t)
            }
        }
    }

    /**
     * True when the NEXT connect() will take its fast path: a context is loaded AND it is for the
     * currently installed model — the exact condition connect() checks before skipping the load.
     * Surfaced as a flag (3.6.0, Workstream E3) so the bubble can show the honest
     * "Loading speech model…" CONNECTING label on cold starts instead of parsing which branch
     * the engine logged. Cheap volatile reads plus one path lookup; callable from any thread.
     * A race with an in-flight load only UNDER-promises (label shows, connect lands warm) —
     * the safe direction.
     */
    fun isWarm(): Boolean {
        val modelPath = modelPathProvider.installedModelPath() ?: return false
        return ctxPtr != 0L && modelPath == loadedModelPath
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
