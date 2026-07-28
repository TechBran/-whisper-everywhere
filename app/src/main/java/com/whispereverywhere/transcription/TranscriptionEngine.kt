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
    fun transcribe(ctx: Long, samples: FloatArray, lang: String?): String
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

    override fun load(modelPath: String): Long {
        ensureBackendsLoaded()
        val useGpu = GpuPolicy.decideUseGpuForLoad(modelPath)
        if (!useGpu) return WhisperNative.init(modelPath, false)
        // finally (not sequential code): a survivable Java exception between arm and disarm must
        // still disarm — only true process death may leave the sentinel behind.
        try {
            return WhisperNative.init(modelPath, true)
        } finally {
            GpuPolicy.onGpuLoadReturned()
        }
    }

    override fun transcribe(ctx: Long, samples: FloatArray, lang: String?): String {
        val validating = GpuPolicy.needsComputeValidation()
        if (!validating) {
            return WhisperNative.transcribe(
                ctx, samples, lang, translate = false, vadModelPath = VadModel.path()
            )
        }
        GpuPolicy.onGpuComputeStarting()
        var ok = false
        try {
            val text = WhisperNative.transcribe(
                ctx, samples, lang, translate = false, vadModelPath = VadModel.path()
            )
            ok = true
            return text
        } finally {
            GpuPolicy.onGpuComputeFinished(ok)
        }
    }

    override fun release(ctx: Long) = WhisperNative.free(ctx)
}

/**
 * Narrow seam the engine uses to resolve the installed model path. Task 5's
 * WhisperModelManager implements this, so LocalWhisperEngine depends only on this
 * interface (not on Task 5) and its unit tests need no Android Context.
 */
interface ModelPathProvider {
    fun installedModelPath(): String?
}
