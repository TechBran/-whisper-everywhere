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
     * Session-scoped language pin (3.6.0 Workstream B). Auto-language sessions only: once the
     * first speech-producing segment's detection lands, later segments pass the concrete code
     * and skip multilingual whisper's per-segment detect-encode pass. Reset in [connect];
     * written only behind the stale-listener guard in [runSegment], so a previous session's
     * late segment can never pin the new session.
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
     * [commit], minus a trailing tail (3.7, Workstream D). See the interface KDoc for why
     * `retainMs <= 0` must be indistinguishable from [commit] — the first line here is that
     * guarantee, not an optimisation.
     *
     * The split is computed INSIDE bufferLock together with the seq, for the same reason [commit]
     * allocates its seq there: the capture thread is still calling sendAudio, and a snapshot taken
     * outside the lock would let a chunk land between the read and the rewrite.
     */
    override fun commitRetainingTailMs(retainMs: Long): Long {
        if (retainMs <= 0L) return commit()

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
            // Aligned DOWN to a whole PCM16 frame so a split can never land mid-sample.
            val retain = (retainMs * BYTES_PER_MS)
                .coerceAtMost(snapshot.size.toLong())
                .toInt() and 1.inv()
            val cut = snapshot.size - retain
            if (cut <= 0) {
                // The offer covers the whole window. A cap that has already fired must never
                // defer its entire buffer, so this degrades to a plain full commit.
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
        // D (3.6.0): true once at least one preview delta was forwarded for THIS segment, so
        // the strip is cleared exactly when something was put on it — and never otherwise.
        var streamedPreview = false
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
                // B (3.6.0): an explicit language passes through untouched; an auto session
                // (lang == null) rides the session pin once the first speech segment detected it.
                val effectiveLang = languagePin.languageFor(lang)
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
                if (lang == null && effectiveLang == null && cleaned.isNotBlank() && listener === myListener) {
                    val detected = backend.detectedLanguage(ctx)
                    languagePin.onDetected(sessionLanguage = lang, detected = detected)
                    // Language code only — never transcript content.
                    android.util.Log.i("WE-DIAG", "language-pin: detected=$detected")
                }
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
        // D (3.6.0): the segment reached a terminal outcome, so the in-flight preview is stale —
        // a blank delta clears the strip (the service's onDelta hides it on blank) before the
        // resolution lands in the accumulating window; both hop to Main via the same FIFO, so
        // the clear always renders first. Emitted only when this segment actually streamed, so
        // non-streaming backends keep the exact 3.5.0 callback sequence.
        if (streamedPreview && listener === myListener) myListener.onDelta("")
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
