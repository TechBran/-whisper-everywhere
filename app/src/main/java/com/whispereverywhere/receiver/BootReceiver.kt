package com.whispereverywhere.receiver

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.whispereverywhere.MainActivity
import com.whispereverywhere.R
import com.whispereverywhere.WhisperEverywhereApp
import com.whispereverywhere.service.FloatingBubbleService

/**
 * Restores the floating bubble after reboots and app updates.
 *
 * BOOT_COMPLETED: Android 15 (targetSdk 35) forbids launching a microphone-type foreground
 * service from a boot receiver, and on Android 12-14 a boot-started mic FGS records SILENCE
 * until the app is next foregrounded. So on boot we post a tap-to-restart notification instead;
 * the tap opens MainActivity, which starts the service from the foreground — fully allowed,
 * microphone usable.
 *
 * MY_PACKAGE_REPLACED: an allowed background-start exemption, so the service is restarted
 * directly (synchronously — a Handler.postDelayed lambda in a manifest receiver can be killed
 * with the receiver's process before it ever runs). If the start is rejected anyway, we degrade
 * to the same notification.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
        const val EXTRA_START_BUBBLE = "com.whispereverywhere.START_BUBBLE"
        private const val RESTART_NOTIFICATION_ID = 1002
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d(TAG, "Received broadcast: $action")

        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON", // HTC devices
            "com.htc.intent.action.QUICKBOOT_POWERON" -> {
                if (eligible(context)) postRestartNotification(context)
            }

            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                if (eligible(context)) {
                    try {
                        FloatingBubbleService.start(context)
                        Log.d(TAG, "Bubble restarted after app update")
                    } catch (t: Throwable) {
                        Log.w(TAG, "Service start after update rejected; posting notification", t)
                        postRestartNotification(context)
                    }
                }
            }
        }
    }

    /** The bubble should come back only if it was on AND everything it needs is still granted. */
    private fun eligible(context: Context): Boolean {
        return try {
            val app = context.applicationContext as? WhisperEverywhereApp ?: return false
            if (!app.preferencesManager.isBubbleEnabled()) {
                Log.d(TAG, "Bubble was not enabled, skipping restart")
                return false
            }
            if (!Settings.canDrawOverlays(context)) {
                Log.w(TAG, "Overlay permission not granted, cannot restore bubble")
                return false
            }
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                Log.w(TAG, "RECORD_AUDIO revoked, cannot restore bubble")
                return false
            }
            if (app.whisperModelManager.installedModel() == null) {
                Log.w(TAG, "No speech model installed, cannot restore bubble")
                return false
            }
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Eligibility check failed", t)
            false
        }
    }

    private fun postRestartNotification(context: Context) {
        try {
            val tap = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(EXTRA_START_BUBBLE, true)
            }
            val pendingIntent = PendingIntent.getActivity(
                context, 0, tap,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notification =
                NotificationCompat.Builder(context, WhisperEverywhereApp.NOTIFICATION_CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle("Restart your dictation bubble")
                    .setContentText("Tap to bring back Whisper Everywhere after the restart.")
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .build()
            val canPost = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
            if (canPost) {
                NotificationManagerCompat.from(context)
                    .notify(RESTART_NOTIFICATION_ID, notification)
            } else {
                Log.w(TAG, "POST_NOTIFICATIONS not granted; boot-restart notification skipped")
            }
        } catch (t: Throwable) {
            // Belt-and-braces for revocation races — nothing else to do.
            Log.w(TAG, "Could not post restart notification", t)
        }
    }
}
