package com.whispereverywhere.npu

/**
 * JNI seam onto Qualcomm's QAIRT (QNN) C API — `libqnnasr.so`, the 4.0 NPU tier.
 *
 * The encoder and decoder are driven through our own native library talking directly to
 * libQnnHtp.so / libQnnSystem.so, with no ONNX Runtime anywhere (ORT 1.29's QNN EP declined the AI
 * Hub EPContext node in every wrapper variant and both binding routes, so the layer was removed
 * rather than worked around). This mirrors how whisper.cpp is already driven at
 * [com.whispereverywhere.whisper.WhisperNative] — an owned JNI boundary with a narrow, explicit
 * surface.
 *
 * ERROR CONVENTION: every `String` return is `""` on success, or `"stage: detail"` on failure —
 * never null, never an exception. The owner has no adb, so a failure has to arrive as text that
 * names the stage it happened at. The same text stays readable afterwards via [nativeLastError].
 *
 * THREADING: the native side holds one process-global session behind one mutex. Every entry point
 * here is safe to call from any thread and none of them may be called from Main — [nativeInit]
 * reads 342 MB from disk and deserialises it.
 *
 * LIFECYCLE: [nativeInit] is idempotent (an existing session is released first), and
 * [nativeRelease] must be called before the CPU `multi` tier re-arms. The NPU path needs ~376 MiB
 * and the two are never co-resident, in either direction.
 *
 * LOADING: the `init` block below throws `UnsatisfiedLinkError` when `libqnnasr.so` is absent from
 * the APK, which happens by design when the Qualcomm headers could not be fetched at build time
 * (see the guard in `app/src/main/cpp/CMakeLists.txt`). Callers reach this object only through the
 * NPU gate, which treats a load failure as "tier unavailable" — the CPU and GPU tiers never touch
 * it. That is also why no JVM unit test may reference this object at all: there is no
 * `libqnnasr.so` on the unit-test classpath, so merely naming `QnnAsrNative` in a test kills it.
 * `NpuNativeContractTest` therefore asserts over SOURCE TEXT, and the real behaviour is verified
 * on device at Q10a.
 *
 * ARRIVING LATER, and deliberately not declared yet — an `external fun` with no native
 * implementation links fine and fails only when someone calls it, which is exactly the kind of
 * "surface that looks finished" this project keeps paying for:
 *  - Q3: `nativeInputQuant(): FloatArray`, `nativeEncode(mel: java.nio.ByteBuffer): String`
 *  - Q4: `nativeDetectLanguage(): Int`, `nativeDecodeSegment(prompt: IntArray, suppress: IntArray,
 *    beginSuppress: IntArray, maxTokens: Int, out: IntArray): Int`
 */
object QnnAsrNative {
    init {
        System.loadLibrary("qnnasr")
    }

    /**
     * The gating probe: dlopens libQnnSystem.so and libQnnHtp.so from [libDir] and confirms each
     * exposes at least one provider through `QnnInterface_getProviders` /
     * `QnnSystemInterface_getProviders`.
     *
     * Cheap and side-effect-free in the sense that matters: it creates NO backend, NO device and NO
     * context, allocates nothing device-side, and votes no power configuration. It is the check the
     * backend selector runs before offering the tier at all, so it also runs on devices where the
     * answer is no.
     *
     * The two libraries do stay mapped afterwards — dlopen is refcounted and [nativeInit] reuses
     * the same handles, so probing then arming costs one real load rather than two.
     *
     * @param libDir the app's native library directory (`applicationInfo.nativeLibraryDir`), which
     *        is where the bundled QNN .so set lives.
     * @return `""` when the HTP stack is usable on this device, else `"probe: stage: detail"`.
     */
    external fun nativeProbe(libDir: String): String

    /**
     * Loads both precompiled QAIRT context binaries — 127 MB encoder, 215 MB decoder — creates the
     * backend, device and contexts, retrieves both graphs, and logs every graph's IO by name, dtype
     * and shape along with the binary-info, graph-info and tensor struct versions it read them
     * through.
     *
     * Costs ~342 MiB of resident memory and (spike-measured, encoder) ~525 ms of cold load; the
     * decoder's cost is first measured on device at Q10a. Never call this from Main.
     *
     * Idempotent: an already-initialised session is released first, so a model swap or a restarted
     * session cannot leak a context.
     *
     * @return `""` on success, else `"init: <stage>: <detail>"`.
     */
    external fun nativeInit(encoderPath: String, decoderPath: String, libDir: String): String

    /**
     * The last `"stage: detail"` recorded by any entry point, or `""` if none. Exists because Q4's
     * decode loop reports failure as a negative token count and needs somewhere to put the words.
     */
    external fun nativeLastError(): String

    /**
     * Releases the contexts, device and backend, and frees the system context that owns the tensor
     * metadata. Safe to call twice and safe to call on a partially-initialised session.
     *
     * The QNN libraries themselves stay mapped on purpose: dlclose on a backend holding process-wide
     * FastRPC state is not something the API promises is safe, and re-arming the tier is free.
     */
    external fun nativeRelease()
}
