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

    /** Transcribe everything buffered since the last commit, now. */
    fun commit()

    /** Release the session (cancel pending work). */
    fun close()

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
        fun onCompleted(text: String) // final transcript for one committed segment
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
    override fun load(modelPath: String): Long {
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
