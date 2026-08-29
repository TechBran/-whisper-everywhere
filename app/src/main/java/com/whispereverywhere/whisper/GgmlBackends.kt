package com.whispereverywhere.whisper

/**
 * The ggml backend registry's one-time population — the process-wide precondition of **every**
 * native whisper entry point (4.0, Q9b).
 *
 * ### The crash this exists to prevent, and why nine reviews did not see it
 *
 * This app builds whisper.cpp with `-DGGML_BACKEND_DL=ON`, so backends are standalone `dlopen`
 * modules and **the registry starts EMPTY**. Nothing populates it implicitly; the only thing that
 * ever does is [WhisperNative.loadBackends], and until Q9b its only caller lived inside
 * `WhisperNativeBackend.load` — the *CPU tiers'* load path. Every 3.7 session loaded a CPU tier
 * first, so the registry was populated **as a side effect** of a component the VAD probe has no
 * relationship with, and the dependency was invisible everywhere: it is not a call, not a type,
 * and not a field.
 *
 * The 4.0 npu tier is the first session shape that never loads a CPU tier, and the bill came due
 * at the first thing to ask the registry for a device:
 *
 * ```
 * VadProbeLifecycle.ensureReady -> vadProbeInit -> whisper_vad_init_with_params
 *   -> make_buft_list                              (fork src/whisper.cpp:5126, :1363)
 *   -> ggml_backend_dev_by_type(CPU)               -> nullptr on an empty registry   (:1387)
 *   -> ggml_backend_dev_backend_reg(nullptr)       -> GGML_ASSERT(device)            (:1388)
 *   -> ggml_abort -> SIGABRT
 * ```
 *
 * Every npu session, from the first captured frame — a crash loop, found in minutes by the first
 * device session (Q10a) after ten task reviews that could each only see their own component.
 *
 * ### Why it is an object of its own
 *
 * **One implementation, reachable by everyone who needs it.** The registry is process state, not
 * any one backend's state, and the defect was precisely that its population was *owned* by one
 * tier. Both backends and the probe lifecycle now assert the precondition at their own entry
 * points, and `NativeVadSourceContractTest` pins that [WhisperNative.loadBackends] is called from
 * exactly one place in main sources — because a second implementation would be a second thing that
 * can be true on one path and false on another, which is the bug restated.
 *
 * The double-checked lock is carried over verbatim from `WhisperNativeBackend`'s private version:
 * the volatile read is the whole cost on the hot path, and the monitor makes the load happen once
 * even if two services race their first native call.
 *
 * **It cannot throw.** `runCatching` covers the whole body — a missing `libggml-cpu.so`, an
 * `UnsatisfiedLinkError` from a stripped build, or an `IllegalStateException` from an
 * `Application` that has not run `onCreate` yet. Callers include the audio capture thread, where an
 * escape is a lost session, and the JNI defense below is what turns a *failed* population into a
 * degraded session rather than the crash. The flag is set either way, deliberately: a registry that
 * could not be loaded will not load on the next frame either, and retrying it 31.25 times a second
 * on the capture thread is its own defect.
 */
object GgmlBackends {

    @Volatile
    private var loaded = false

    /**
     * Registers the ggml backend modules, once per process. Idempotent, non-throwing, and cheap
     * after the first call (one volatile read).
     *
     * Call it **before** any native whisper entry point — model load, VAD probe init, or mel — at
     * each entry point that has one, rather than trusting that some other component ran first.
     * That trust is exactly what the SIGABRT was.
     */
    fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            runCatching {
                WhisperNative.loadBackends(
                    com.whispereverywhere.WhisperEverywhereApp.getInstance()
                        .applicationInfo.nativeLibraryDir
                )
            }
            loaded = true
        }
    }
}
