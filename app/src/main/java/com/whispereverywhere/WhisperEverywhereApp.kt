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
import com.whispereverywhere.model.WhisperModelManager

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
