package com.whispereverywhere.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import com.whispereverywhere.WhisperEverywhereApp
import com.whispereverywhere.service.FloatingBubbleService

/**
 * Receives boot completed and app update broadcasts to automatically
 * restart the floating bubble service if it was previously enabled.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d(TAG, "Received broadcast: $action")

        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON", // For HTC devices
            "com.htc.intent.action.QUICKBOOT_POWERON" -> {
                restartBubbleIfEnabled(context)
            }
        }
    }

    private fun restartBubbleIfEnabled(context: Context) {
        try {
            // Get preferences to check if bubble was enabled
            val app = context.applicationContext as? WhisperEverywhereApp
            val preferencesManager = app?.preferencesManager

            if (preferencesManager == null) {
                Log.w(TAG, "PreferencesManager not available")
                return
            }

            val wasBubbleEnabled = preferencesManager.isBubbleEnabled()
            Log.d(TAG, "Bubble was enabled: $wasBubbleEnabled")

            if (!wasBubbleEnabled) {
                Log.d(TAG, "Bubble was not enabled, skipping restart")
                return
            }

            // Check if we have the required permissions
            if (!Settings.canDrawOverlays(context)) {
                Log.w(TAG, "Overlay permission not granted, cannot restart bubble")
                return
            }

            if (!preferencesManager.hasApiKey()) {
                Log.w(TAG, "API key not set, cannot restart bubble")
                return
            }

            // Small delay to let the system settle after boot
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                Log.d(TAG, "Restarting FloatingBubbleService...")

                // Stop first (in case it's in a bad state)
                FloatingBubbleService.stop(context)

                // Small delay then restart
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    FloatingBubbleService.start(context)
                    Log.d(TAG, "FloatingBubbleService restart initiated")
                }, 500)
            }, 3000) // Wait 3 seconds after boot for system to stabilize

        } catch (e: Exception) {
            Log.e(TAG, "Error restarting bubble service", e)
        }
    }
}
