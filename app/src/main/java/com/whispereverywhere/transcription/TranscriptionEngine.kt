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
        fun onDelta(text: String)     // unused on-device; kept for interface compatibility

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
     * ISO code whisper auto-detected during the LAST completed transcribe on [ctx], or null
     * when unavailable (never ran / unknown id / a backend with no detection). Meaningful
     * ONLY right after a transcribe(...) that returned non-blank text on an auto-language
     * call — the native early-return paths (VAD-empty, energy gate) never reach whisper_full
     * and would leave a stale id behind (see WhisperNative.detectedLanguage). Default null:
     * every existing fake keeps compiling, and a detection-less backend can never pin.
     */
    fun detectedLanguage(ctx: Long): String? = null

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
        try {
            WhisperNative.init(modelPath, true)
        } finally {
            GpuPolicy.onGpuLoadReturned()
        }
    }

    override fun transcribe(ctx: Long, samples: FloatArray, lang: String?, useVad: Boolean): String =
        NativeComputeGate.serialized {
            val vad = if (useVad) VadModel.path() else null   // batch passes false -> no native VAD
            val validating = GpuPolicy.needsComputeValidation()
            if (!validating) {
                return@serialized WhisperNative.transcribe(
                    ctx, samples, lang, translate = false, vadModelPath = vad
                )
            }
            GpuPolicy.onGpuComputeStarting()
            var ok = false
            try {
                val text = WhisperNative.transcribe(
                    ctx, samples, lang, translate = false, vadModelPath = vad
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
