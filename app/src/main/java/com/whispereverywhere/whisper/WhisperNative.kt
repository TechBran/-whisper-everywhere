package com.whispereverywhere.whisper

/**
 * JNI bridge to the native whisper.cpp engine (libwhisper_jni.so).
 *
 * All three functions map 1:1 to whisper_jni.cpp:
 *   - init()       -> whisper_init_from_file_with_params(); returns whisper_context* as Long (0 = failure)
 *   - transcribe() -> whisper_full() with WHISPER_SAMPLING_GREEDY; returns concatenated segment text
 *   - free()       -> whisper_free()
 *
 * The returned Long is an opaque native pointer handle owned by the caller
 * (LocalWhisperEngine caches it). Never dereference it in Kotlin.
 */
object WhisperNative {
    init {
        System.loadLibrary("whisper_jni")
    }

    /**
     * Loads a ggml model file into a native whisper_context. Returns 0L on failure.
     * @param useGpu attempt the ggml OpenCL (Adreno) backend. The caller (GpuPolicy via
     *        WhisperNativeBackend) is responsible for device allowlisting — pass false for
     *        the proven CPU path. ggml still auto-falls back to CPU if OpenCL init fails.
     */
    external fun init(modelPath: String, useGpu: Boolean): Long

    /**
     * Runs whisper_full on float32 PCM (mono, 16 kHz, [-1,1]). Returns raw UTF-8 bytes:
     * NewStringUTF in JNI aborts on 4-byte UTF-8 (emoji / rare CJK from multilingual models),
     * so the native side hands bytes across and [transcribe] decodes them safely here.
     */
    external fun transcribeRaw(
        ctxPtr: Long,
        samples: FloatArray,
        lang: String?,
        translate: Boolean,
        vadModelPath: String?,
    ): ByteArray

    /**
     * @param ctxPtr handle from [init]
     * @param lang   ISO code (e.g. "en"), or null/"auto" for auto-detect
     * @param translate true to translate to English; false for transcribe-in-language
     * @param vadModelPath path to a ggml Silero VAD model, or null to run without VAD.
     *        With VAD, silence/non-speech is trimmed natively before the encoder runs.
     */
    fun transcribe(
        ctxPtr: Long,
        samples: FloatArray,
        lang: String?,
        translate: Boolean,
        vadModelPath: String?,
    ): String = String(transcribeRaw(ctxPtr, samples, lang, translate, vadModelPath), Charsets.UTF_8)

    /** Frees the native whisper_context. Safe to call once per non-zero handle. */
    external fun free(ctxPtr: Long)
}
