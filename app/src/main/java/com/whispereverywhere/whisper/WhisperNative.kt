package com.whispereverywhere.whisper

import java.nio.ByteBuffer

/**
 * JNI bridge to the native whisper.cpp engine (libwhisper_jni.so).
 *
 * The functions map 1:1 to whisper_jni.cpp:
 *   - init()             -> whisper_init_from_file_with_params(); returns whisper_context* as Long (0 = failure)
 *   - transcribe()       -> whisper_full() with WHISPER_SAMPLING_GREEDY; returns concatenated segment text
 *   - detectedLanguage() -> whisper_lang_str(whisper_full_lang_id()): the LAST completed transcribe's detection
 *   - free()             -> whisper_free()
 *   - vadProbe*()        -> a dedicated streaming Silero VAD context (3.7 endpointing; see below)
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
     * Receives the in-flight transcribe's FULL running text after each newly decoded native
     * segment. Invoked by whisper_jni's new-segment trampoline (CallVoidMethod on a global
     * ref) ON THE THREAD THAT CALLED [transcribeRaw], while whisper_full is still executing.
     * Raw UTF-8 bytes for the same reason [transcribeRaw] returns them: NewStringUTF aborts
     * on 4-byte UTF-8. Implementations must be fast and lock-free (they run inside the native
     * decode loop, and the process-global NativeComputeGate is held by this thread) and must
     * never call back into [WhisperNative].
     *
     * RELEASE builds: onRunningText is resolved via JNI GetMethodID BY NAME — the matching
     * keep rules in app/proguard-rules.pro must stay, or R8 renames it and deltas silently
     * vanish in release only.
     */
    fun interface NewSegmentCallback {
        fun onRunningText(textUtf8: ByteArray)
    }

    /**
     * Runs whisper_full on float32 PCM (mono, 16 kHz, [-1,1]). Returns raw UTF-8 bytes:
     * NewStringUTF in JNI aborts on 4-byte UTF-8 (emoji / rare CJK from multilingual models),
     * so the native side hands bytes across and [transcribe] decodes them safely here.
     * [callback] (nullable) streams incremental running text — see [NewSegmentCallback].
     */
    external fun transcribeRaw(
        ctxPtr: Long,
        samples: FloatArray,
        lang: String?,
        translate: Boolean,
        vadModelPath: String?,
        callback: NewSegmentCallback?,
    ): ByteArray

    /**
     * @param ctxPtr handle from [init]
     * @param lang   ISO code (e.g. "en"), or null/"auto" for auto-detect
     * @param translate true to translate to English; false for transcribe-in-language
     * @param vadModelPath path to a ggml Silero VAD model, or null to run without VAD.
     *        With VAD, silence/non-speech is trimmed natively before the encoder runs.
     * @param onNewSegment optional preview stream: the full text decoded so far in THIS call,
     *        delivered on the calling thread mid-inference (see [NewSegmentCallback]); null = off.
     */
    fun transcribe(
        ctxPtr: Long,
        samples: FloatArray,
        lang: String?,
        translate: Boolean,
        vadModelPath: String?,
        onNewSegment: ((String) -> Unit)? = null,
    ): String {
        val callback = onNewSegment?.let { emit ->
            NewSegmentCallback { bytes -> emit(String(bytes, Charsets.UTF_8)) }
        }
        return String(transcribeRaw(ctxPtr, samples, lang, translate, vadModelPath, callback), Charsets.UTF_8)
    }

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

    // ---------------------------------------------------------------------------------------
    // 3.7 Workstream A — streaming VAD probe.
    //
    // Four externs over a DEDICATED native Silero context, entirely separate from the batch VAD
    // filter [transcribeRaw] runs on every commit. Different jobs: the probe decides WHEN to cut
    // a segment, the filter decides WHAT audio inside that cut reaches the encoder. Independent
    // contexts, independent tuning — the filter keeps its own 0.40 / 150 ms onset knobs.
    //
    // These four are the ONLY whisper calls in this process not wrapped by NativeComputeGate.
    // The safety argument is recorded at the surface itself, in the "PROBE SAFETY" comment block
    // in whisper_jni.cpp — read it before moving any of this.
    //
    // All four serialise on ONE native mutex (g_probe_mutex), so any one of them can be made to
    // wait by any other. That is why every one of them is a capture-thread call and none is a
    // Main-thread call.
    // ---------------------------------------------------------------------------------------

    /**
     * Loads the bundled Silero model into a dedicated native VAD context pinned to ONE thread,
     * and reports whether it is ready. Call once per recording session, on the capture thread,
     * before the first [vadProbeFrame]; pass the path from `VadModel.path()`.
     *
     * false is a NORMAL, expected outcome — model missing, extraction failed, unloadable file —
     * not an error: the caller then runs the amplitude endpointer, whose behavior is
     * byte-identical to 3.6.0. Idempotent: a second call frees the previous context first, so a
     * session restart or model swap cannot leak ~2.6 MB.
     *
     * Holds the probe mutex across the WHOLE model load — file I/O plus tensor allocation — so a
     * [vadProbeFree] or [vadProbeFrame] racing this call blocks for that entire duration, not for
     * a frame's sub-millisecond. It is the widest hold on this surface and the one that sizes the
     * ANR risk if any of these four ever reaches Main.
     */
    external fun vadProbeInit(modelPath: String): Boolean

    /**
     * Speech probability in `[0,1]` for EXACTLY ONE 512-sample Silero window — or **-1.0f,
     * meaning "no verdict"**.
     *
     * [pcm] must be a DIRECT buffer (`ByteBuffer.allocateDirect`) in `ByteOrder.nativeOrder()`,
     * holding little-endian 16-bit mono PCM at 16 kHz. Bytes `[0, nBytes)` are read straight from
     * its base address — position, limit and mark are ignored — so ONE buffer is allocated per
     * session and refilled forever: no per-frame FloatArray, no JNI array copy, no callback.
     *
     * BYTE ORDER IS A LIVE TRAP: `ByteBuffer.allocateDirect` returns a BIG_ENDIAN buffer on every
     * platform, whatever the hardware. Fill it with `put(ByteArray)`, which is byte-verbatim and
     * unaffected by order, or call `order(ByteOrder.nativeOrder())` FIRST if `putShort` or
     * `asShortBuffer` is used. Getting it wrong byte-swaps every sample and the probe reads
     * plausible-looking noise — no exception, no sentinel, just wrong probabilities.
     *
     * NOT THREAD-SAFE AGAINST ITS OWN BUFFER: nothing here copies [pcm] or locks it, so the
     * caller must not refill it concurrently with this call. The contract is fill, then call, on
     * the same thread.
     *
     * [nBytes] must be exactly **1024** (512 samples × 2 bytes = one 32 ms mic callback at
     * 16 kHz). Anything else returns -1.0f. That is a hard refusal, not a convenience: the native
     * frame loop zero-pads a short frame and STILL advances the LSTM one step, poisoning the
     * recurrence for every frame after it — silent, gradual accuracy loss with no symptom at the
     * call site. `AudioRecord.read` returns *up to* the buffer size and the 48 kHz decimator emits
     * "~1024" bytes, so one chunk = one frame is the common case and never the contract: the
     * caller MUST accumulate to exact 512-sample boundaries.
     *
     * **-1.0f is never "silence".** Treat it as "keep the previous state" — it must neither open
     * nor close the speech gate. It is also what an uninitialised or failed probe returns, so the
     * fallback path needs no separate signal.
     *
     * WHAT -1.0f DOES NOT COVER, stated honestly: a mid-graph compute failure. The native window
     * loop breaks out and still reports success, leaving the probability slot unwritten — so this
     * returns 0.0f on the first frame and the PREVIOUS frame's value on every frame after that,
     * i.e. a plausible probability rather than "no verdict". A native ERROR log is the only
     * signal it happened. Curing it changes the batch filter's behavior too and is deferred to
     * its own ticket; see the vadProbeFrame comment block in whisper_jni.cpp.
     *
     * Returns a RAW probability on purpose: threshold, hysteresis, hangover and min-speech policy
     * live in Kotlin where they are JVM-pinnable, the same split `SegmentCapPolicy` already uses.
     *
     * Runs INLINE on the audio capture thread, ~31.25×/second, holding a native mutex for the
     * duration (0.2–1.5 ms expected against a 32 ms budget). Never call it from Main.
     */
    external fun vadProbeFrame(pcm: ByteBuffer, nBytes: Int): Float

    /**
     * Zeroes the probe's LSTM hidden/cell state — the "a new utterance starts here" signal. Model
     * weights live in a different buffer, so this is one backend buffer clear: cheap enough to
     * call on every commit.
     *
     * Must run after EVERY commit — that is the five reset sites Workstream D wires (Task D10),
     * all of them reached through the endpointer's own `reset()` — and at every acoustic-source
     * change. Carrying recurrence across a mic ↔ device-audio switch is a correctness bug, not
     * merely suboptimal. No-op when the probe was never initialised.
     */
    external fun vadProbeReset()

    /**
     * Frees the probe context (~2.6 MB RSS). Call at record stop. Safe to call twice, and safe
     * after [vadProbeInit] returned false.
     *
     * Blocks until any in-flight [vadProbeFrame] **or in-flight [vadProbeInit] model load**
     * completes — the load is the wide case, not the frame — which is why this belongs on the
     * capture-thread teardown path and never on Main.
     *
     * ORDERING IS BINDING ON THE CALLER, because idempotent is not order-free: a stop that wins
     * the race against a still-running [vadProbeInit] takes the mutex first, finds no context,
     * frees nothing and returns — and the init behind it then publishes a live context that
     * nothing will ever release, until the next [vadProbeInit], which may be never. The wiring
     * must guarantee this runs AFTER any in-flight init has published.
     */
    external fun vadProbeFree()

    /**
     * BENCH-ONLY override of the encoder audio_ctx floor (whisper_jni.cpp; the production
     * default is 512). Deliberately absent from this object's 1:1 KDoc list above — it maps to
     * no production behavior. Clamped natively to 64..1500 and process-global — it affects EVERY
     * subsequent transcribe in this process, which is why production code must never call it.
     * WhisperBenchTest's floor A-B is the sole caller and restores the production value in a
     * finally block.
     */
    external fun setAudioCtxFloor(floor: Int)

    /**
     * Cost counters for the LAST [transcribeRaw] in this process, as
     * `[ctxFrames, vadInSamples, vadOutSamples]` (3.7 Workstream F).
     *
     * - `ctxFrames` — the encoder audio context actually used. 0 means whisper_full never ran
     *   (the VAD found zero speech, or the energy gate fired). This is the cost driver 3.7's
     *   cadence arithmetic turns on and was entirely invisible from Kotlin before 3.7.
     * - `vadInSamples` / `vadOutSamples` — we_vad_filter's before/after sample counts. Both 0
     *   means no VAD ran; `vadIn > 0` with `vadOut == 0` is the probe-vs-batch-filter
     *   disagreement, which is the one thing `cut=vad` cannot tell you on its own.
     *
     * PROCESS-GLOBAL, like [detectedLanguage]: only meaningful when read on the same thread that
     * just ran the transcribe, while that thread still holds NativeComputeGate. WhisperNativeBackend
     * snapshots it inside the gate and tags the snapshot with its ctx; nothing else may call it.
     * Diagnostics only — never read for a decision.
     */
    external fun lastSegmentStats(): IntArray

    /** Frees the native whisper_context. Safe to call once per non-zero handle. */
    external fun free(ctxPtr: Long)
}
