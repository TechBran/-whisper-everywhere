package com.whispereverywhere.transcription

import com.whispereverywhere.whisper.WhisperNative

/**
 * Backend-neutral streaming transcription contract. The recorder drives an engine via
 * connect / sendAudio / commit / close and receives results through [Listener].
 */
interface TranscriptionEngine {
    /** Prepare/load the session for [language] (null = auto). Calls onOpen when ready. */
    fun connect(language: String?, listener: Listener)

    /** Buffer one chunk of PCM16 mono @16kHz. Called rapidly from the recorder thread. */
    fun sendAudio(pcm: ByteArray)

    /**
     * Transcribe everything buffered since the last commit, now.
     *
     * Returns the monotonic seq allocated for the cut segment, or -1 if there was nothing to
     * commit. Every returned seq is guaranteed to reach [Listener.onSegmentResolved] exactly once.
     * Seq numbering restarts at 0 on every [connect].
     */
    fun commit(): Long

    /** Release the session (cancel pending work). */
    fun close()

    /**
     * Warm any expensive backing resource. Default no-op.
     *
     * These four were previously reached via `(engine as? LocalWhisperEngine)?` downcasts in
     * FloatingBubbleService. For any second implementation those silently no-op — and one of them
     * is [awaitIdle], the fence that drains in-flight transcribes before close() detaches the
     * listener. Skipping it drops every pending segment via the listener-identity guard, which is
     * exactly the "No speech detected despite valid audio" bug the in-code comment at that call
     * site records as already fixed once.
     */
    fun prewarm() {}

    /** Release the engine permanently. Default no-op. */
    fun shutdown() {}

    /**
     * Block until every already-submitted segment has resolved, or [timeoutMs] elapses.
     * Returns true if it drained. Default: nothing outstanding, so true.
     *
     * MUST be called off the main thread — implementations may block.
     */
    fun awaitIdle(timeoutMs: Long): Boolean = true

    /** Release heavy resources under memory pressure, keeping the engine reusable. Default no-op. */
    fun releaseContext() {}

    /**
     * Receives engine lifecycle and transcription result events.
     *
     * **Threading:** all callbacks are invoked on the engine's background executor thread,
     * NOT on the main/UI thread. Consumers are responsible for marshalling to the UI thread
     * themselves (e.g. via `Handler(Looper.getMainLooper()).post { … }` or
     * `Activity.runOnUiThread { … }`).
     */
    interface Listener {
        fun onOpen()
        /**
         * PREVIEW-ONLY running text of the in-flight segment/turn (cloud-live partials; local
         * partial streaming since 3.6.0). Blank means "clear the preview". Never committed —
         * committed text arrives exclusively via [onSegmentResolved].
         */
        fun onDelta(text: String)

        /**
         * Terminal result for exactly one committed segment. EVERY seq returned by [commit] MUST
         * arrive here exactly once — including empties and failures. A seq that never resolves
         * stalls the SegmentOrderer head forever and holds every later segment with it.
         */
        fun onSegmentResolved(seq: Long, outcome: SegmentOutcome)

        /** Session-level failure (connect-time / fatal). NOT the per-segment failure channel. */
        fun onError(message: String)
        fun onClosed()
    }
}

/**
 * The native cost counters for one completed transcribe (3.7 Workstream F), read back through the
 * backend seam so [SegmentTiming] can name them without Kotlin guessing.
 *
 * `ctxFrames == 0` is a real reading, not a missing one: it means whisper_full never ran (the VAD
 * found zero speech, or the energy gate fired). "Unknown" is expressed by a null
 * [WhisperBackend.lastSegmentStats], never by zeros.
 *
 * So an ALL-ZERO instance of this type is a MEASUREMENT — "a transcribe ran and cost nothing" —
 * and it is a different fact, at a different value, from a null. The two commits that produce it
 * are the most interesting diagnostics in this workstream, so nothing between the native counters
 * and the timing line may collapse zeros to "no data"; a `stats.ctxFrames == 0` reader must print
 * the zero, and only a `stats == null` reader may omit the fields.
 *
 * NON-ZERO IS NOT SUCCESS, either. `ctxFrames > 0` means the encoder was CONFIGURED for that many
 * frames — not that the decode succeeded. `ctxFrames > 0` alongside EMPTY text is a failed
 * whisper_full or a genuine zero-segment decode, and this type cannot tell you which: it carries
 * COST, never a verdict.
 */
data class NativeSegmentStats(
    val ctxFrames: Int,
    val vadInSamples: Int,
    val vadOutSamples: Int,
)

/** Thin seam over the native layer so the engine can be tested without JNI. */
interface WhisperBackend {
    fun load(modelPath: String): Long
    /**
     * @param useVad true (live default) runs the Silero VAD before the encoder to suppress noise
     *   hallucination on always-open mic capture. Batch passes FALSE: a user-chosen file is
     *   transcribed in full (quiet music / low speech included), so the VAD must not trim it.
     *   Threads to WhisperNative.transcribe as vadModelPath = if (useVad) VadModel.path() else null,
     *   which short-circuits we_vad_filter natively (whisper_jni.cpp:188). BATCH-ONLY bypass.
     */
    fun transcribe(ctx: Long, samples: FloatArray, lang: String?, useVad: Boolean = true): String

    /**
     * ISO code whisper auto-detected during the LAST completed transcribe on [ctx]. From the
     * native backend null means ONLY an id outside whisper's language table (unreachable via
     * auto-detect) — a ctx that never ran returns "en", the lang_id 0 default, so there is NO
     * in-band "no detection yet" signal and the caller's non-blank-transcribe guard is the only
     * thing separating a real detection from that English default. Meaningful
     * ONLY right after a transcribe(...) that returned non-blank text on an auto-language
     * call — the native early-return paths (VAD-empty, energy gate) never reach whisper_full
     * and would leave a stale id behind (see WhisperNative.detectedLanguage). Default null:
     * every existing fake keeps compiling, and a detection-less backend can never pin.
     */
    fun detectedLanguage(ctx: Long): String? = null

    /**
     * Like [transcribe], additionally delivering the in-flight call's running text after each
     * newly decoded native segment (3.6.0 Workstream D). [onNewSegment] receives the FULL text
     * decoded so far in THIS call, on the SAME thread that invoked transcribeStreaming, while
     * the native call is still executing — and, for the production backend, while this thread
     * holds the process-global [NativeComputeGate]. Callers must therefore keep the closure
     * lock-free and must never re-enter the backend from inside it. PREVIEW-ONLY: the returned
     * String remains the only authoritative result. Default: plain [transcribe], zero deltas —
     * every existing fake keeps 3.5.0 behavior untouched.
     */
    fun transcribeStreaming(
        ctx: Long,
        samples: FloatArray,
        lang: String?,
        useVad: Boolean = true,
        onNewSegment: (String) -> Unit,
    ): String = transcribe(ctx, samples, lang, useVad)

    /**
     * Cost counters for the LAST transcribe THIS backend ran on [ctx], or null when the backend
     * has none (every fake, and any future non-native backend). Default null so existing fakes
     * keep 3.6.0 behaviour byte for byte — the timing line simply omits the fields.
     *
     * Null and an all-zero [NativeSegmentStats] are DIFFERENT ANSWERS: null is "no transcribe has
     * run on this ctx", zeros are "a transcribe ran and cost zero". Callers branch on the null and
     * never on the values.
     *
     * Called by [LocalWhisperEngine.runSegment] immediately after its transcribe returns, on the
     * same single native-executor thread. Diagnostics only.
     */
    fun lastSegmentStats(ctx: Long): NativeSegmentStats? = null

    fun release(ctx: Long)
}

/**
 * Production backend: delegates to [WhisperNative] with translate = false.
 *
 * GPU usage is decided per load by [GpuPolicy] (Adreno allowlist + crash sentinels). The first
 * GPU load and the first GPU transcribe of each app version run inside sentinel windows so a
 * native GPU crash permanently falls this version back to CPU instead of crash-looping.
 */
object WhisperNativeBackend : WhisperBackend {
    @Volatile private var backendsLoaded = false

    /** One-time dynamic backend registration (GGML_BACKEND_DL) before the first model load. */
    private fun ensureBackendsLoaded() {
        if (backendsLoaded) return
        synchronized(this) {
            if (backendsLoaded) return
            runCatching {
                WhisperNative.loadBackends(
                    com.whispereverywhere.WhisperEverywhereApp.getInstance()
                        .applicationInfo.nativeLibraryDir
                )
            }
            backendsLoaded = true
        }
    }

    // All three native entry points hold the process-global [NativeComputeGate] so the bubble and
    // batch services (which share THIS singleton) can never run two native whisper calls at once —
    // see NativeComputeGate for why concurrent GPU submits and the racing GpuPolicy sentinel are
    // unsafe. The gate is released between calls, so the two paths still interleave per-call.
    override fun load(modelPath: String): Long = NativeComputeGate.serialized {
        ensureBackendsLoaded()
        val useGpu = GpuPolicy.decideUseGpuForLoad(modelPath)
        if (!useGpu) return@serialized WhisperNative.init(modelPath, false)
        // finally (not sequential code): a survivable Java exception between arm and disarm must
        // still disarm — only true process death may leave the sentinel behind.
        val ctx = try {
            WhisperNative.init(modelPath, true)
        } finally {
            GpuPolicy.onGpuLoadReturned()
        }
        if (ctx == 0L) {
            // The GPU load produced no context, so no canary is possible. If one was pending,
            // this is a NO-VERDICT exit like the two below and must clear the GPU-active state:
            // the caller will retry/fall back on CPU with activeModelKey still naming this model,
            // and an armed gpuActiveInProcess would let that CPU work write gpu_validated=true.
            if (GpuPolicy.needsCanary()) GpuPolicy.onCanaryUnavailable()
            return@serialized ctx
        }
        // KEEP: GpuPolicy.onCanaryResult has no internal guard, so this early return is the only
        // thing standing between a .en (or already-adjudicated) model and a spurious verdict.
        // It must NOT call onCanaryUnavailable — those loads are real GPU loads and legitimately
        // stay armed so their first transcribe still runs inside the compute sentinel.
        if (!GpuPolicy.needsCanary()) return@serialized ctx

        // GPU CANARY (3.6.0 Workstream C). A multilingual model reached the GPU for the first
        // time on this device: prove it decodes correctly BEFORE any user audio touches it. The
        // multilingual GPU failure mode is silent corruption, which no crash sentinel can catch
        // (GpuPolicy.isGpuSafeModel's empirical-corruption docblock) — so one bundled ~1 s clip
        // of spoken digits is transcribed and matched by the pure rule in GpuCanaryPolicy.
        //
        // WHERE THIS RUNS: load() is reached from BOTH native executors — LocalWhisperEngine's
        // (prewarm / prewarmModelSwitch / connect) AND BatchTranscriber.loadCtx — but in NEITHER
        // case from inside a transcribe of user audio, and NativeComputeGate serializes the two,
        // so the ~1 s cost can never land mid-session. At most ONCE per device+model per app
        // version.
        //
        // Routed through transcribeInternal so the canary pays the same GpuPolicy compute
        // sentinel a real segment does; useVad=false because the clip is already speech-only.
        // lang="en" is pinned for CORRECTNESS, not speed: GpuCanaryPolicy.EXPECTED_TOKENS are
        // ASCII-only, and auto-detect on a 1 s clip can pick a locale whose numerals render
        // non-ASCII — a working GPU would then false-FAIL into the permanent CPU latch.
        // Sentinel interaction, handled in GpuPolicy.onCanaryResult: that wrapper writes
        // gpu_validated=true for any transcribe that merely did not THROW, which a garbage-
        // decoding GPU does — so a failing canary would otherwise leave "validated" set on the
        // very model it latches to CPU, disarming the crash sentinel. onCanaryResult(false)
        // clears keyValidated in the same edit.
        val samples = CanaryAudio.samples()
        if (samples == null) {
            // NO VERDICT — not a failure. A missing or unreadable asset (omitted from the
            // package, wrong format, asset shrink) is evidence about the BUILD, not about this
            // GPU, and onCanaryResult(false) would write a permanent per-(versionCode, model)
            // CPU latch with no in-app recovery. Fall back to CPU for this load and record
            // nothing: canaryPending is re-decided at the top of the next decideUseGpuForLoad,
            // so the next launch simply retries once the asset is really there.
            //
            // onCanaryUnavailable (not merely "record nothing"): this call returns a CPU context
            // while GpuPolicy still has gpuActiveInProcess=true and activeModelKey set to this
            // multilingual model, so without it the session's first user segment would satisfy
            // needsComputeValidation() and write gpu_validated=true for a GPU that never ran —
            // disarming both sentinels for the next launch's real GPU attempt.
            android.util.Log.w("WE-DIAG", "gpu-canary: asset unavailable — no verdict recorded")
            GpuPolicy.onCanaryUnavailable()
            runCatching { WhisperNative.free(ctx) }
            return@serialized WhisperNative.init(modelPath, false)
        }
        // fold, not getOrDefault(""): only a RETURNED string is evidence about this GPU's decode
        // quality. A THROWN transcribe (JNI error, allocation failure) says nothing about decode
        // and must not be scored — collapsing it to "" would score it as the empty-output
        // corruption signature and write the permanent latch on a GPU that was never measured.
        // A blank string that was actually RETURNED still FAILS: that is C1's pinned
        // large-v3-turbo signature, and it is a real measurement.
        return@serialized runCatching {
            transcribeInternal(ctx, samples, "en", useVad = false, onNewSegment = null)
        }.fold(
            onSuccess = { text ->
                val passed = GpuCanaryPolicy.canaryPasses(text)
                // Length only — the canary text is known audio, but the no-transcript-logging
                // rule is absolute so the habit never erodes.
                android.util.Log.i(
                    "WE-DIAG",
                    "gpu-canary: passed=$passed outLen=${text.length}",
                )
                // After transcribeInternal has fully RETURNED (its finally already ran): a
                // compute-finished finally landing after onCanaryResult would resurrect
                // keyValidated=true on the model onCanaryResult(false) just banned.
                GpuPolicy.onCanaryResult(passed)
                if (passed) {
                    ctx
                } else {
                    // Failed: drop the GPU context and reload on CPU inside this same call, so
                    // the caller gets a working context and the session proceeds exactly as it
                    // does today. The latch GpuPolicy just wrote means no later load for this
                    // model even attempts the GPU.
                    runCatching { WhisperNative.free(ctx) }
                    WhisperNative.init(modelPath, false)
                }
            },
            onFailure = {
                // NO VERDICT, same shape as the asset-unavailable branch above: the canary could
                // not be MEASURED, so nothing permanent may be written and the GPU-active state
                // must be cleared before the CPU context goes back to the caller.
                android.util.Log.w("WE-DIAG", "gpu-canary: transcribe threw — no verdict recorded")
                GpuPolicy.onCanaryUnavailable()
                runCatching { WhisperNative.free(ctx) }
                WhisperNative.init(modelPath, false)
            },
        )
    }

    override fun transcribe(ctx: Long, samples: FloatArray, lang: String?, useVad: Boolean): String =
        transcribeInternal(ctx, samples, lang, useVad, onNewSegment = null)

    override fun transcribeStreaming(
        ctx: Long,
        samples: FloatArray,
        lang: String?,
        useVad: Boolean,
        onNewSegment: (String) -> Unit,
    ): String = transcribeInternal(ctx, samples, lang, useVad, onNewSegment)

    // 3.7 Workstream F: WhisperNative's cost counters are PROCESS-GLOBAL, so this one slot holds a
    // snapshot of them taken INSIDE the gate hold that produced them, tagged with the ctx that ran.
    // The seam needs TWO separate guarantees and each has its own mechanism:
    //
    //   1. SNAPSHOT COHERENCE comes from the GATE HOLD. The single JNI round trip is necessary but
    //      NOT sufficient: natively those three counters are three independent RELAXED atomic
    //      loads, so read outside a hold they can straddle another transcribe's writes and return
    //      a triple no single segment ever had. Only the hold makes them one segment's numbers.
    //   2. READ CORRECTNESS comes from the ctx TAG *plus* the INVALIDATION at the top of the hold.
    //      The tag alone is not enough — a transcribe that threw would leave the previous
    //      segment's numbers behind a still-matching ctx — which is why the two are documented as
    //      one mechanism and must be changed as one.
    //
    // And the tag names which ctx last RAN, never which SEGMENT: the GPU canary IS a real
    // transcribe on the ctx it just loaded, so the canary's numbers are legitimately tagged with
    // that ctx until the session's first real segment overwrites them.
    //
    // Written stats FIRST, tag LAST (both @Volatile; the tag is the guard), so a reader that sees
    // a matching tag is guaranteed to see the stats that go with it.
    @Volatile private var lastStats: NativeSegmentStats? = null
    @Volatile private var lastStatsCtx: Long = 0L

    private fun captureStats(ctx: Long) {
        // runCatching: a diagnostic must never be able to fail a transcribe that already
        // succeeded (UnsatisfiedLinkError on a stale .so, OOM on the 3-int array).
        captureStatsFrom(ctx, runCatching { WhisperNative.lastSegmentStats() }.getOrNull())
    }

    /**
     * The payload half of [captureStats], split off so its rules are testable at all:
     * WhisperNative's static initialiser calls System.loadLibrary, so on a JVM the read above can
     * only ever throw. Internal for that reason alone — production has exactly one caller,
     * [captureStats].
     */
    internal fun captureStatsFrom(ctx: Long, v: IntArray?) {
        // SIZE IS THE ONLY REJECTION. Not `v.all { it == 0 }`: an all-zero payload is the reading
        // that says a transcribe ran and whisper_full did not (the VAD found zero speech, or the
        // energy gate fired), which is a measurement and one of the two most interesting ones in
        // this workstream. Dropping it here would report those commits as "no data", identical to
        // a ctx that never transcribed. `< 3`, not `!= 3`, so a fourth native counter cannot blind
        // this seam on an app build that predates it.
        if (v == null || v.size < 3) return
        lastStats = NativeSegmentStats(ctxFrames = v[0], vadInSamples = v[1], vadOutSamples = v[2])
        lastStatsCtx = ctx   // LAST: the tag is the guard for the stats written above it
    }

    /**
     * @see WhisperBackend.lastSegmentStats. Answers only for the ctx the most recent transcribe
     * tagged. ctx 0 never matches: it is [WhisperNative.init]'s failure value, so without that
     * guard a caller passing it would match the untouched initial tag.
     */
    override fun lastSegmentStats(ctx: Long): NativeSegmentStats? =
        if (ctx != 0L && ctx == lastStatsCtx) lastStats else null

    // The one place the gate + GpuPolicy sentinel wrap a native whisper_full. [onNewSegment]
    // (nullable) is invoked by the JNI trampoline on THIS thread while the gate is held —
    // see WhisperBackend.transcribeStreaming's contract for why the closure must stay lock-free.
    private fun transcribeInternal(
        ctx: Long,
        samples: FloatArray,
        lang: String?,
        useVad: Boolean,
        onNewSegment: ((String) -> Unit)?,
    ): String = NativeComputeGate.serialized {
        // FIRST, above every path — the mirror of whisper_jni's own reset at the top of
        // transcribeRaw. A stats read may only ever be answered by the transcribe that completed
        // in THIS hold. Without this, a transcribe that THREW leaves the previous segment's
        // numbers tagged with a still-matching ctx, and a ctx freed-then-reallocated at the same
        // address (the GPU canary's failure branches free and re-init) inherits the canary's
        // numbers. Never move these below anything, and never delete them as redundant because
        // the capture overwrites them: the capture does not run on the paths that need this.
        lastStats = null
        lastStatsCtx = 0L
        val vad = if (useVad) VadModel.path() else null   // batch passes false -> no native VAD
        val validating = GpuPolicy.needsComputeValidation()
        if (!validating) {
            val text = WhisperNative.transcribe(
                ctx, samples, lang, translate = false, vadModelPath = vad, onNewSegment = onNewSegment
            )
            captureStats(ctx)
            return@serialized text
        }
        GpuPolicy.onGpuComputeStarting()
        var ok = false
        try {
            val text = WhisperNative.transcribe(
                ctx, samples, lang, translate = false, vadModelPath = vad, onNewSegment = onNewSegment
            )
            ok = true
            captureStats(ctx)   // AFTER ok = true: the sentinel's verdict is about the transcribe
            text
        } finally {
            GpuPolicy.onGpuComputeFinished(ok)
        }
    }

    // A single native field read, but still a native-ctx touch: it follows the house rule that
    // EVERY native entry point in this backend holds the process-global gate (see the comment
    // above load()). The engine calls it on its single native-executor thread, right after the
    // transcribe whose detection it reads — the gate wait is at most one interleaved batch call,
    // the same wait the next transcribe would pay anyway.
    override fun detectedLanguage(ctx: Long): String? =
        NativeComputeGate.serialized { WhisperNative.detectedLanguage(ctx) }

    override fun release(ctx: Long) = NativeComputeGate.serialized { WhisperNative.free(ctx) }
}

/**
 * Narrow seam the engine uses to resolve the installed model path. Task 5's
 * WhisperModelManager implements this, so LocalWhisperEngine depends only on this
 * interface (not on Task 5) and its unit tests need no Android Context.
 */
interface ModelPathProvider {
    fun installedModelPath(): String?
}
