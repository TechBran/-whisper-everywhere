package com.whispereverywhere.transcription

import android.content.SharedPreferences
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.util.Log
import com.whispereverywhere.BuildConfig
import com.whispereverywhere.WhisperEverywhereApp

/**
 * Decides whether the native whisper engine may use the GPU (ggml OpenCL backend).
 *
 * Production gating, modeled on Google's LiteRT delegate pattern:
 *
 *  1. **Static allowlist** — GPU is only attempted on Adreno 7xx/8xx/X-series, the generations
 *     Qualcomm's ggml OpenCL backend officially supports. Mali, Xclipse, PowerVR, older Adreno,
 *     and unknown renderers all take the (fast, dotprod/i8mm) CPU path. The renderer string is
 *     read once via an off-screen EGL context and cached.
 *
 *  2. **Crash sentinels** — a prefs flag is synchronously committed to disk immediately before
 *     each *risky first* native call (first GPU model load, first GPU transcribe of this app
 *     version) and cleared right after it returns. If the process dies inside the native call,
 *     the stale flag survives; the next launch sees it and permanently locks THIS app version
 *     to CPU. A new versionCode (app update, possibly with a fixed driver/backend) re-trials
 *     once. GPU failures on Android include silent process death — this converts "crash loop"
 *     into "one crash, then clean CPU fallback forever".
 *
 * All methods fail SAFE: any exception (missing Application in JVM unit tests, EGL failure)
 * results in the CPU path.
 */
object GpuPolicy {
    private const val TAG = "WE-DIAG"
    private const val PREFS = "gpu_policy"

    private const val KEY_RENDERER = "renderer"

    // All state is keyed by BOTH app version and model identity: validation with one model does
    // not generalize (different vocab shapes hit different GPU kernels — e.g. the multilingual
    // models exercise Adreno paths the .en models never touch). A post-validation crash with a
    // NEWLY selected model must still be caught and blocked per-model, not become a crash loop.
    private fun keyBlocked(vc: Int, m: String) = "gpu_blocked_v${vc}_$m"
    private fun keyValidated(vc: Int, m: String) = "gpu_validated_v${vc}_$m"
    private fun keyInFlight(vc: Int, m: String, phase: String) = "gpu_inflight_${phase}_v${vc}_$m"

    /**
     * The CANARY verdict for a multilingual model on this device (3.6.0 Workstream C). Absent =
     * never run. Keyed by app version AND model like every other flag here, so a new app version
     * (possibly a new driver/backend) re-trials once instead of inheriting a stale verdict.
     */
    private fun keyCanary(vc: Int, m: String) = "gpu_canary_v${vc}_$m"

    /** Stable per-model key from the model path (file name, sanitized for prefs keys). */
    private fun modelKey(modelPath: String): String =
        modelPath.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_")

    /** true = canary passed, false = permanent CPU latch, null = never run for this version+model. */
    private fun canaryVerdict(p: SharedPreferences, vc: Int, m: String): Boolean? =
        if (!p.contains(keyCanary(vc, m))) null else p.getBoolean(keyCanary(vc, m), false)

    /**
     * Set while a GPU load was allowed for a model whose canary has NOT run yet — the signal
     * WhisperNativeBackend.load reads to run the canary immediately after the load returns.
     * Written under [stateLock] together with [activeModelKey]. Cleared by [onCanaryResult] when
     * a verdict is recorded, and re-decided from scratch at the top of every
     * [decideUseGpuForLoad] — so an exit that records no verdict (C5's `ctx == 0L` and
     * "canary asset unavailable" paths) can never leave a stale `true` behind.
     */
    @Volatile private var canaryPending = false

    /** True when the load just decided needs its canary run before any user audio touches it. */
    fun needsCanary(): Boolean = canaryPending

    /**
     * Records the canary verdict for the model of the load that set [needsCanary]. A FAIL is a
     * permanent per-(version, model) CPU latch: the corruption is silent and reproducible, so
     * one demonstration is enough. commit() (not apply()) — the verdict must survive a process
     * death that a later GPU call might cause.
     *
     * A FAIL also CLEARS `gpu_validated` for that (version, model), in the same edit. The canary
     * runs through the same compute path a user segment does, so its non-throwing transcribe has
     * already set `keyValidated(vc, m) = true` — that flag means "a GPU compute completed without
     * killing us", and leaving it set while latching the model to CPU would disarm the crash
     * sentinel for a model whose GPU output is known garbage. Two prefs flags in direct
     * contradiction is worse than either. Never called when the canary could not be RUN (see
     * CanaryAudio.samples): an absent asset must leave the verdict unrecorded, not latch.
     */
    fun onCanaryResult(passed: Boolean) = runCatching {
      synchronized(stateLock) {
        canaryPending = false
        val p = prefs() ?: return@runCatching
        val vc = BuildConfig.VERSION_CODE
        val m = activeModelKey ?: return@runCatching
        if (passed) {
            p.edit().putBoolean(keyCanary(vc, m), true).commit()
            Log.i(TAG, "GpuPolicy: canary PASSED for v$vc/$m — GPU allowed for this model+device")
        } else {
            p.edit()
                .putBoolean(keyCanary(vc, m), false)
                .putBoolean(keyValidated(vc, m), false)
                .commit()
            Log.w(TAG, "GpuPolicy: canary FAILED for v$vc/$m — permanent CPU latch, session continues on CPU")
            gpuActiveInProcess = false
        }
      }
    }.let { }

    /** Adreno 7xx / 8xx / X-series (X1E, X2 …) — the officially supported set. */
    private val ALLOWED_RENDERERS = Regex("""Adreno \(TM\) (7\d\d|8\d\d|X\d)""")

    /**
     * GPU is allowed ONLY for English (.en) models. Multilingual models are EMPIRICALLY broken
     * on the Adreno OpenCL backend WITHOUT crashing — proven on the Fold 6 (2026-07-17) via
     * instrumented A/B against CPU controls on identical audio:
     *  - ggml-small-q5_1 (vocab 51865, %4==1): GPU decodes to garbage tokens
     *  - ggml-large-v3-turbo-q5_0 (vocab 51866, %4==2): GPU returns empty transcriptions
     * CPU transcribes both correctly. The .en vocab (51864) is %4==0, dodging the upstream
     * whisper.cpp #3708 N%4 Adreno-matmul family; .en tiers have extensive on-device
     * validation. (Multilingual tiny passes on GPU — its dims stay under the Adreno-optimized
     * kernel threshold — but no catalog tier is that small, so the gate keys on scope.)
     * Crash sentinels can never catch silent corruption, hence this static gate.
     */
    private fun isGpuSafeModel(modelKey: String): Boolean = modelKey.contains(".en")

    /** True once load() ran with GPU enabled in this process (compute validation still pending). */
    @Volatile private var gpuActiveInProcess = false

    private fun prefs(): SharedPreferences? = runCatching {
        WhisperEverywhereApp.getInstance().getSharedPreferences(PREFS, 0)
    }.getOrNull()

    // Model key of the load this process last enabled GPU for (compute validation follows it).
    @Volatile private var activeModelKey: String? = null

    // Serializes the sentinel state machine's read-modify-write transitions (arm load/compute, clear,
    // mark validated) AND the activeModelKey/gpuActiveInProcess writes that pair with them. In
    // production every caller reaches these through the gated WhisperNativeBackend, so
    // NativeComputeGate already admits one native call at a time; this lock is defense-in-depth so
    // the transitions stay atomic even for a caller that bypasses the backend — a concurrent
    // onGpuComputeFinished() can never clear the compute sentinel and write validated=true while
    // another risky-first compute for the same model is still in flight.
    private val stateLock = Any()

    /** Decide once per model load. Also arms the load-phase sentinel when GPU is chosen. */
    fun decideUseGpuForLoad(modelPath: String): Boolean = runCatching {
      synchronized(stateLock) {
        // 3.6.0 C, stale-flag hygiene: this function is the ONLY place canaryPending is armed,
        // so it decides the flag afresh on every call — cleared here, set at the tail (Step 3)
        // only when a multilingual model still needs validating. Without this, an exit that
        // never reaches the tail (any early return here, or either of C5's no-verdict exits —
        // `ctx == 0L` and "canary asset unavailable", both of which deliberately record
        // nothing) could leave a stale `true` paired with a freshly written activeModelKey.
        canaryPending = false
        val p = prefs() ?: return false
        val vc = BuildConfig.VERSION_CODE
        val m = modelKey(modelPath)

        if (!isGpuSafeModel(m)) {
            // 3.6.0 Workstream C: multilingual models are no longer banned outright — they are
            // CANARY-GATED, and only when the owner has enabled the experimental developer
            // toggle. Default (toggle off) behavior is byte-identical to 3.5.0.
            val experiment = runCatching {
                com.whispereverywhere.WhisperEverywhereApp.getInstance()
                    .preferencesManager.isGpuMultilingualExperimentEnabled()
            }.getOrDefault(false)
            if (!experiment) {
                Log.i(TAG, "GpuPolicy: $m is multilingual — silent GPU corruption on Adreno -> CPU")
                return false
            }
            when (canaryVerdict(p, vc, m)) {
                false -> {
                    Log.i(TAG, "GpuPolicy: $m failed the canary on this device (latched) -> CPU")
                    return false
                }
                true -> Log.i(TAG, "GpuPolicy: $m passed the canary on this device -> GPU allowed")
                null -> Log.i(TAG, "GpuPolicy: $m canary not yet run -> GPU load armed for validation")
            }
        }
        if (p.getBoolean(keyBlocked(vc, m), false)) {
            Log.i(TAG, "GpuPolicy: blocked for v$vc/$m (previous GPU crash) -> CPU")
            return false
        }
        // A stale in-flight flag from a previous process = we died inside a GPU-phase native
        // call for THIS model. Block it and fall back. (Java-level exceptions disarm via the
        // caller's finally blocks, so a surviving flag really does mean process death.)
        val staleLoad = p.getBoolean(keyInFlight(vc, m, "load"), false)
        val staleCompute = p.getBoolean(keyInFlight(vc, m, "compute"), false)
        if (staleLoad || staleCompute) {
            Log.w(TAG, "GpuPolicy: stale sentinel for $m (load=$staleLoad compute=$staleCompute) -> blocking GPU (v$vc)")
            p.edit()
                .putBoolean(keyBlocked(vc, m), true)
                .putBoolean(keyInFlight(vc, m, "load"), false)
                .putBoolean(keyInFlight(vc, m, "compute"), false)
                .commit()
            return false
        }

        val renderer = rendererString(p)
        if (!ALLOWED_RENDERERS.containsMatchIn(renderer)) {
            Log.i(TAG, "GpuPolicy: renderer '$renderer' not allowlisted -> CPU")
            return false
        }

        if (!p.getBoolean(keyValidated(vc, m), false)) {
            // First GPU attempt for this (version, model): arm the load sentinel BEFORE the
            // native call. commit() (not apply()) — must be on disk if the load kills us.
            p.edit().putBoolean(keyInFlight(vc, m, "load"), true).commit()
            Log.i(TAG, "GpuPolicy: renderer '$renderer' allowlisted; GPU trial armed (v$vc, $m)")
        }
        activeModelKey = m
        gpuActiveInProcess = true
        // 3.6.0 C: a multilingual model with no recorded verdict must transcribe the canary
        // before any user audio reaches it. Written with activeModelKey under stateLock so
        // onCanaryResult always latches the model this decision was about.
        canaryPending = !isGpuSafeModel(m) && canaryVerdict(p, vc, m) == null
        true
      }
    }.getOrDefault(false)

    /**
     * Call from the caller's FINALLY around the GPU load: disarms the load sentinel. finally
     * does not run on process death, so crash detection is preserved, while survivable Java
     * exceptions (loadLibrary failure, JNI OOM) disarm correctly instead of falsely blocking.
     */
    fun onGpuLoadReturned() = runCatching {
      synchronized(stateLock) {
        val p = prefs() ?: return@runCatching
        val vc = BuildConfig.VERSION_CODE
        val m = activeModelKey ?: return@runCatching
        if (p.getBoolean(keyInFlight(vc, m, "load"), false)) {
            p.edit().putBoolean(keyInFlight(vc, m, "load"), false).commit()
        }
      }
    }.let { }

    /** True when the next transcribe is this (version, model)'s first on GPU. */
    fun needsComputeValidation(): Boolean = runCatching {
      synchronized(stateLock) {
        if (!gpuActiveInProcess) return false
        val m = activeModelKey ?: return false
        val p = prefs() ?: return false
        !p.getBoolean(keyValidated(BuildConfig.VERSION_CODE, m), false)
      }
    }.getOrDefault(false)

    /** Arm the compute sentinel just before the first GPU transcribe of this (version, model). */
    fun onGpuComputeStarting() = runCatching {
      synchronized(stateLock) {
        val p = prefs() ?: return@runCatching
        val m = activeModelKey ?: return@runCatching
        p.edit().putBoolean(keyInFlight(BuildConfig.VERSION_CODE, m, "compute"), true).commit()
      }
    }.let { }

    /**
     * Call from the caller's FINALLY around the first GPU transcribe. Always disarms; marks the
     * (version, model) validated only when the transcribe actually succeeded.
     */
    fun onGpuComputeFinished(success: Boolean) = runCatching {
      synchronized(stateLock) {
        val p = prefs() ?: return@runCatching
        val vc = BuildConfig.VERSION_CODE
        val m = activeModelKey ?: return@runCatching
        val e = p.edit().putBoolean(keyInFlight(vc, m, "compute"), false)
        if (success) {
            e.putBoolean(keyValidated(vc, m), true)
            Log.i(TAG, "GpuPolicy: GPU validated for v$vc/$m")
        }
        e.commit()
      }
    }.let { }

    // ---------------------------------------------------------------------------------------------

    private fun rendererString(p: SharedPreferences): String {
        p.getString(KEY_RENDERER, null)?.let { return it }
        val r = queryGlRenderer()
        // Cache only a REAL result: a transient EGL failure (device busy, early boot) returning
        // "" must not permanently disable GPU for this install — retry on the next load instead.
        if (r.isNotEmpty()) {
            p.edit().putString(KEY_RENDERER, r).apply()
        }
        Log.i(TAG, "GpuPolicy: GL_RENDERER = '$r'")
        return r
    }

    /** Reads GL_RENDERER via a tiny off-screen EGL context (a few ms, once per install). */
    private fun queryGlRenderer(): String {
        var display = EGL14.EGL_NO_DISPLAY
        var context = EGL14.EGL_NO_CONTEXT
        var surface = EGL14.EGL_NO_SURFACE
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) return ""
            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) return ""
            val configAttrs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val num = IntArray(1)
            if (!EGL14.eglChooseConfig(display, configAttrs, 0, configs, 0, 1, num, 0) || num[0] == 0) return ""
            surface = EGL14.eglCreatePbufferSurface(
                display, configs[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0
            )
            context = EGL14.eglCreateContext(
                display, configs[0], EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
            )
            if (surface == EGL14.EGL_NO_SURFACE || context == EGL14.EGL_NO_CONTEXT) return ""
            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) return ""
            return GLES20.glGetString(GLES20.GL_RENDERER) ?: ""
        } catch (t: Throwable) {
            return ""
        } finally {
            runCatching {
                if (display != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(
                        display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
                    )
                    if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                    if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
                    EGL14.eglTerminate(display)
                }
            }
        }
    }
}
