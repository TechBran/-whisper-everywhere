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
        if (ctx == 0L || !GpuPolicy.needsCanary()) return@serialized ctx

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
            android.util.Log.w("WE-DIAG", "gpu-canary: asset unavailable — no verdict recorded")
            runCatching { WhisperNative.free(ctx) }
            return@serialized WhisperNative.init(modelPath, false)
        }
        val text = runCatching {
            transcribeInternal(ctx, samples, "en", useVad = false, onNewSegment = null)
        }.getOrDefault("")
        val passed = GpuCanaryPolicy.canaryPasses(text)
        // Length only — the canary text is known audio, but the no-transcript-logging rule is
        // absolute so the habit never erodes.
        android.util.Log.i(
            "WE-DIAG",
            "gpu-canary: passed=$passed outLen=${text.length}",
        )
        GpuPolicy.onCanaryResult(passed)
        if (passed) return@serialized ctx

        // Failed: drop the GPU context and reload on CPU inside this same call, so the caller
        // gets a working context and the session proceeds exactly as it does today. The latch
        // GpuPolicy just wrote means no later load for this model even attempts the GPU.
        runCatching { WhisperNative.free(ctx) }
        WhisperNative.init(modelPath, false)
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
        val vad = if (useVad) VadModel.path() else null   // batch passes false -> no native VAD
        val validating = GpuPolicy.needsComputeValidation()
        if (!validating) {
            return@serialized WhisperNative.transcribe(
                ctx, samples, lang, translate = false, vadModelPath = vad, onNewSegment = onNewSegment
            )
        }
        GpuPolicy.onGpuComputeStarting()
        var ok = false
        try {
            val text = WhisperNative.transcribe(
                ctx, samples, lang, translate = false, vadModelPath = vad, onNewSegment = onNewSegment
            )
            ok = true
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
