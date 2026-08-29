package com.whispereverywhere

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.system.ErrnoException
import android.system.Os
import android.util.Log
import com.whispereverywhere.data.local.PreferencesManager
import com.whispereverywhere.data.local.UsageTracker
import com.whispereverywhere.model.WhisperCatalog
import com.whispereverywhere.model.WhisperModelManager
import com.whispereverywhere.transcription.NpuWhisperBackend

class WhisperEverywhereApp : Application() {

    lateinit var preferencesManager: PreferencesManager
        private set

    lateinit var usageTracker: UsageTracker
    lateinit var cloudCostTracker: com.whispereverywhere.data.local.CloudCostTracker
        private set

    /**
     * Process-lifetime model manager. Lazy so it is created on first use
     * (first recording / onboarding) after [preferencesManager] is initialized.
     */
    val whisperModelManager: WhisperModelManager by lazy {
        WhisperModelManager(this, preferencesManager)
    }

    /**
     * Whether this device's HARDWARE can run the 4.0 `npu` tier: the SoC gate, then the QNN probe.
     *
     * **`by lazy` because the probe dlopens `libQnnSystem.so` and `libQnnHtp.so`** — a real load
     * the first time, and a chooser that recomposes must not repeat it. Computed at most once per
     * process, and on the overwhelming majority of devices not at all past the first branch: the
     * SoC gate is checked first inside [NpuWhisperBackend.isTierAvailable] and a Tensor, an Exynos
     * or a MediaTek never reaches the dlopen.
     *
     * **Not Main-safe** — `QnnAsrNative`'s threading contract forbids Main for every entry point,
     * so every reader forces this off the main thread. [isNpuTierOffered] is the reader that
     * matters and its callers do exactly that.
     *
     * The API-31 guard lives HERE rather than in `NpuGate`, which is a pure two-string table on
     * purpose: `minSdk` is 26, `Build.SOC_MODEL` arrived in API 31, and the gate's null → deny
     * handles the whole pre-S population in one branch.
     *
     * This file is already where the NPU's process-scoped setup lives — see
     * [configureFastRpcLibraryPath], which is here for the same reason: it has to happen once, per
     * process, before anything touches the backend.
     */
    val npuCapableDevice: Boolean by lazy {
        NpuWhisperBackend.isTierAvailable(
            socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else null,
            socManufacturer =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MANUFACTURER else null,
            libDir = applicationInfo.nativeLibraryDir,
        )
    }

    /**
     * Whether a chooser may OFFER the gated `npu` tier: capable hardware AND both context binaries
     * already on disk.
     *
     * **The installed half is not redundant with the hardware half, and it is not Q8's job.** The
     * pair is SAF-imported from a zip, never downloaded — `WhisperModel.url` on that tier is
     * provenance, not a source — so a card offered before the assets exist would put a Download
     * button in front of a user for whom downloading cannot work. Offering a tier whose assets
     * cannot yet arrive is the one thing this gate exists to prevent.
     *
     * Re-read on every call rather than memoised: [npuCapableDevice] is a fact about the silicon
     * and cannot change within a process, but the files can — Q8's importer creates them while the
     * app is running, and a chooser that cached "not installed" would keep hiding the card the
     * import just earned. It is four `File` stats behind a memoised gate.
     *
     * **Never call from Main** — it forces [npuCapableDevice], which dlopens.
     */
    fun isNpuTierOffered(): Boolean {
        val npu = WhisperCatalog.byId("npu") ?: return false
        return npuCapableDevice && whisperModelManager.isInstalled(npu)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Must run before anything can dlopen the QNN backend.
        configureFastRpcLibraryPath()

        // Initialize managers
        preferencesManager = PreferencesManager(this)
        usageTracker = UsageTracker(this)
        cloudCostTracker = com.whispereverywhere.data.local.CloudCostTracker(this)

        // Create notification channel for foreground service
        createNotificationChannel()
    }

    /**
     * Points the FastRPC loader at the app's own native library directory (4.0 NPU tier).
     *
     * The HTP backend (libQnnHtp.so) loads its DSP-side skel — libQnnHtpV75Skel.so, which the APK
     * bundles — through the FastRPC loader, and that loader does NOT search the app's native
     * library directory. It searches `ADSP_LIBRARY_PATH`, and only that. The variable has to be set
     * before the backend is ever dlopen()ed, which is why this lives in Application.onCreate rather
     * than anywhere near the NPU code itself.
     *
     * App directory FIRST so the bundled skel wins, then the stock vendor locations, which is where
     * a device exposing its own HTP skels keeps them.
     *
     * Runs on EVERY device, including the overwhelming majority that will never arm the NPU tier:
     * it is two setenv calls and no I/O, and making it conditional would mean predicting NPU
     * support before [NpuGate] has run. A failure here is logged and swallowed — the CPU and GPU
     * tiers do not read this variable, and losing the NPU tier must never cost the app its launch.
     */
    private fun configureFastRpcLibraryPath() {
        val nativeLibDir = applicationInfo.nativeLibraryDir
        try {
            // Semicolon-separated, unlike LD_LIBRARY_PATH — this is the FastRPC loader's own format.
            Os.setenv(
                "ADSP_LIBRARY_PATH",
                nativeLibDir +
                    ";/vendor/lib/rfsa/adsp" +
                    ";/vendor/dsp/cdsp" +
                    ";/system/lib/rfsa/adsp" +
                    ";/system/vendor/lib/rfsa/adsp" +
                    ";/dsp",
                true
            )
        } catch (e: ErrnoException) {
            Log.w(TAG, "ADSP_LIBRARY_PATH setenv failed; the NPU tier will not come up", e)
        }
        try {
            // Not strictly required (the linker resolves DT_NEEDED from the app lib dir already),
            // but it makes the environment self-describing in a bug report.
            val existing = Os.getenv("LD_LIBRARY_PATH")
            val merged =
                if (existing.isNullOrEmpty()) nativeLibDir else "$nativeLibDir:$existing"
            Os.setenv("LD_LIBRARY_PATH", merged, true)
        } catch (e: ErrnoException) {
            Log.w(TAG, "LD_LIBRARY_PATH setenv failed", e)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "WhisperEverywhereApp"

        const val NOTIFICATION_CHANNEL_ID = "whisper_everywhere_service"
        const val NOTIFICATION_ID = 1001

        @Volatile
        private var instance: WhisperEverywhereApp? = null

        fun getInstance(): WhisperEverywhereApp {
            return instance ?: throw IllegalStateException("Application not initialized")
        }
    }
}
