package com.whispereverywhere.receiver

import android.Manifest
import android.annotation.SuppressLint
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
import com.whispereverywhere.npu.NpuDiag
import com.whispereverywhere.npu.NpuRefreshNotice
import com.whispereverywhere.service.FloatingBubbleService
import com.whispereverywhere.whisper.WhisperNative

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
 *
 * (4.15) WHEN THE BUBBLE CANNOT COME BACK BECAUSE ITS MODEL IS GONE, SAY WHY. The v0.63.0 refresh
 * makes a phone's old AI-chip pair fail this build's census; the launch sweep removes it and
 * records the re-download (`NpuStalePairSweep`), and until the new pack is fetched
 * `installedModel()` is null. That used to end here in one log line and a bubble that silently
 * never returned. Now the same branch hands [NpuRefreshNotice.decide] the facts, and when the
 * record says the selected tier needs a re-download the user gets the refresh notice in the shade
 * instead — once per stale event on the update, and on every boot while the record stands, in
 * place of the restart notification that boot would have posted (owner ruling 2026-09-24).
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
        const val EXTRA_START_BUBBLE = "com.whispereverywhere.START_BUBBLE"
        private const val RESTART_NOTIFICATION_ID = 1002

        /**
         * The refresh notice's own id (4.15): 1001 is the bubble's foreground notice, 1002 the
         * restart notice above, 1003 the batch service's. Distinct, so posting one never replaces
         * another in the shade.
         */
        private const val MODEL_UPDATE_NOTIFICATION_ID = 1004

        /**
         * Take the refresh notice down once the pair it asked for has landed (112). The notice is
         * AUTO_CANCEL, which only a TAP clears — and the tap is not the only way to the Download
         * button. A user who opens the app anyway lands on the same button through the app-wide
         * gate, and on the owner's Fold6 (2026-09-24) the pack re-downloaded that way at about
         * 19:50 while the notice stood in the shade untouched at 21:10. The shared finalise calls
         * this on the line that clears the re-download record, and only when the record it
         * announced was the one that cleared ([com.whispereverywhere.model.WhisperModelManager]).
         * Cancelling an id that is not posted is a no-op, so the call is unconditional there.
         */
        fun cancelRefreshNotice(context: Context) {
            try {
                NotificationManagerCompat.from(context).cancel(MODEL_UPDATE_NOTIFICATION_ID)
            } catch (t: Throwable) {
                Log.w(TAG, "Could not cancel the model refresh notice", t)
            }
        }
    }

    /** What the bubble's eligibility answered — three states, because "no model" has a notice. */
    private enum class Eligibility {
        /** It was on and everything it needs is still granted and on disk: bring it back. */
        RESTORE,

        /** Eligible in every way but one: the selected model is not installed. */
        MODEL_MISSING,

        /** It was off, or a permission it needs is gone: nothing to restore, nothing to say. */
        NONE,
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d(TAG, "Received broadcast: $action")

        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON", // HTC devices
            "com.htc.intent.action.QUICKBOOT_POWERON" -> {
                when (eligible(context)) {
                    Eligibility.RESTORE -> postRestartNotification(context)
                    Eligibility.MODEL_MISSING ->
                        postRefreshNoticeIfDue(context, NpuRefreshNotice.Trigger.BOOT_COMPLETED)
                    Eligibility.NONE -> Unit
                }
            }

            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                when (eligible(context)) {
                    Eligibility.RESTORE -> {
                        try {
                            FloatingBubbleService.start(context)
                            Log.d(TAG, "Bubble restarted after app update")
                        } catch (t: Throwable) {
                            Log.w(TAG, "Service start after update rejected; posting notification", t)
                            postRestartNotification(context)
                        }
                    }
                    Eligibility.MODEL_MISSING ->
                        postRefreshNoticeIfDue(context, NpuRefreshNotice.Trigger.PACKAGE_REPLACED)
                    Eligibility.NONE -> Unit
                }
            }
        }
    }

    /** The bubble should come back only if it was on AND everything it needs is still granted. */
    private fun eligible(context: Context): Eligibility {
        return try {
            val app = context.applicationContext as? WhisperEverywhereApp ?: return Eligibility.NONE
            if (!app.preferencesManager.isBubbleEnabled()) {
                Log.d(TAG, "Bubble was not enabled, skipping restart")
                return Eligibility.NONE
            }
            if (!Settings.canDrawOverlays(context)) {
                Log.w(TAG, "Overlay permission not granted, cannot restore bubble")
                return Eligibility.NONE
            }
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                Log.w(TAG, "RECORD_AUDIO revoked, cannot restore bubble")
                return Eligibility.NONE
            }
            if (app.whisperModelManager.installedModel() == null) {
                Log.w(TAG, "No speech model installed, cannot restore bubble")
                return Eligibility.MODEL_MISSING
            }
            Eligibility.RESTORE
        } catch (t: Throwable) {
            Log.e(TAG, "Eligibility check failed", t)
            Eligibility.NONE
        }
    }

    /**
     * The POST_NOTIFICATIONS guard both notices share: below 13 there is nothing to grant. One
     * function, so the refresh notice's guard is the restart notice's by construction. (Lint's
     * MissingPermission check cannot follow a guard into a helper, hence the suppressions on the
     * two posting methods; each one's `notify` is reached only through this answer.)
     */
    private fun canPostNotifications(context: Context): Boolean =
        android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
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
            if (canPostNotifications(context)) {
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

    /**
     * The refresh notice (4.15): ask [NpuRefreshNotice.decide] with the real facts and post what it
     * answers. Reached ONLY from [Eligibility.MODEL_MISSING] — the bubble was on and would have come
     * back but for its model — so `installedModelNull` is that verdict, not a second read.
     *
     * The tap opens [MainActivity] WITHOUT [EXTRA_START_BUBBLE]: the app-wide gate lands the user on
     * the model step and its Download button, which is the fix, and starting a bubble with no model
     * behind it would only show a red flash. The PendingIntent's request code is the notice's own
     * id, not the restart notice's 0: both intents name MainActivity and differ only in an extra,
     * which `filterEquals` ignores, so sharing a request code would make them ONE PendingIntent and
     * `FLAG_UPDATE_CURRENT` would rewrite the other notice's extras.
     */
    @SuppressLint("MissingPermission")
    private fun postRefreshNoticeIfDue(context: Context, trigger: NpuRefreshNotice.Trigger) {
        try {
            val app = context.applicationContext as? WhisperEverywhereApp ?: return
            val prefs = app.preferencesManager
            val record = prefs.npuRedownload
            val decision = NpuRefreshNotice.decide(
                trigger = trigger,
                installedModelNull = true,
                selectedTierId = prefs.selectedModelId,
                record = record,
                notifiedCensusKey = prefs.npuRefreshNotifiedCensusKey,
                downloadBytes = NpuRefreshNotice.downloadBytesFor(app.npuSocFamily, record?.tierId),
                canPost = canPostNotifications(context),
            )
            // Native, like the sweep's line: the phone this is about runs the Play build, where R8
            // has stripped android.util.Log, and "nothing appeared" has six causes this names.
            runCatching { WhisperNative.diag(NpuDiag.refreshNotice(trigger, decision)) }
            if (decision !is NpuRefreshNotice.Decision.Post || record == null) return
            val tap = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            val pendingIntent = PendingIntent.getActivity(
                context, MODEL_UPDATE_NOTIFICATION_ID, tap,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notification =
                NotificationCompat.Builder(context, WhisperEverywhereApp.MODEL_UPDATES_CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(decision.title)
                    .setContentText(decision.text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(decision.text))
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .build()
            NotificationManagerCompat.from(context).notify(MODEL_UPDATE_NOTIFICATION_ID, notification)
            // Recorded only after the notify: the update's once-per-event rule counts notices that
            // actually went out, never ones a permission race dropped.
            prefs.npuRefreshNotifiedCensusKey = record.censusKey
        } catch (t: Throwable) {
            // The same belt as the restart notice: a revocation race costs the notice, never the
            // receiver.
            Log.w(TAG, "Could not post the model refresh notice", t)
        }
    }
}
