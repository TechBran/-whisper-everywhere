package com.whispereverywhere.whisper

/**
 * JNI bridge to the native whisper.cpp engine (libwhisper_jni.so).
 *
 * The functions map 1:1 to whisper_jni.cpp:
 *   - init()             -> whisper_init_from_file_with_params(); returns whisper_context* as Long (0 = failure)
 *   - transcribe()       -> whisper_full() with WHISPER_SAMPLING_GREEDY; returns concatenated segment text
 *   - detectedLanguage() -> whisper_lang_str(whisper_full_lang_id()): the LAST completed transcribe's detection
 *   - free()             -> whisper_free()
 *
 * The returned Long is an opaque native pointer handle owned by the caller
 * (LocalWhisperEngine caches it). Never dereference it in Kotlin.
 */
object WhisperNative {
    init {
        System.loadLibrary("whisper_jni")
    }

    /**
     * Scans [nativeLibDir] (the APK's native library directory) and registers every ggml
     * backend module that can load on this device — CPU always; OpenCL only where the vendor
     * ships libOpenCL.so. MUST be called once before [init]; failed backends are skipped
     * silently (that is the mechanism that lets one APK run on non-OpenCL devices).
     */
    external fun loadBackends(nativeLibDir: String)

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

    /**
     * ISO code (e.g. "de") whisper auto-detected during the LAST completed whisper_full on
     * [ctxPtr]. Null is NOT a "nothing detected yet" signal: state->lang_id starts at 0, so a
     * ctx that never completed whisper_full returns "en" — null covers only an id outside
     * whisper's language table, which auto-detect cannot produce. Only meaningful IMMEDIATELY
     * after a transcribe that actually ran whisper on an auto-language call: the native
     * early-return paths (VAD found zero speech, the energy gate,
     * empty input) never reach whisper_full, and the underlying
     * state->lang_id then still holds a PREVIOUS call's detection — possibly a previous
     * session's. Callers guard this by querying only after a non-blank transcribe (see
     * LocalWhisperEngine.runSegment). Call on the same single thread that runs transcribe.
     */
    external fun detectedLanguage(ctxPtr: Long): String?

    /** Frees the native whisper_context. Safe to call once per non-zero handle. */
    external fun free(ctxPtr: Long)
}
