package com.whispereverywhere.audio

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.util.Log

/**
 * Bridge between the consent trampoline and the bubble service.
 *
 * ANDROID 14+ ORDERING RULE: the service must ALREADY be foregrounded with type
 * mediaProjection when MediaProjectionManager.getMediaProjection() is called. Therefore the
 * Gate does NOT create the projection itself — it hands the raw (resultCode, data) to the
 * listener (the service), which upgrades its foreground type FIRST, creates the projection,
 * then calls [storeProjection].
 */
object MediaProjectionGate {

    interface Listener {
        fun onConsentGranted(resultCode: Int, data: Intent)
        fun onConsentDenied()
    }

    @Volatile var listener: Listener? = null
    @Volatile private var projection: MediaProjection? = null

    fun hasProjection(): Boolean = projection != null
    fun projectionOrNull(): MediaProjection? = projection

    fun storeProjection(p: MediaProjection) {
        projection = p
    }

    /** Drop the token (projection stopped / service destroyed). Consent must be re-requested. */
    fun clear() {
        projection?.let { runCatching { it.stop() } }
        projection = null
    }

    fun requestConsent(context: Context) {
        Log.i("WE-DIAG", "MediaProjectionGate: launching consent trampoline")
        context.startActivity(
            Intent(context, ProjectionConsentActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** Called only by ProjectionConsentActivity. */
    internal fun deliverResult(resultCode: Int, data: Intent?) {
        val l = listener
        if (resultCode == android.app.Activity.RESULT_OK && data != null && l != null) {
            l.onConsentGranted(resultCode, data)
        } else {
            Log.i("WE-DIAG", "MediaProjectionGate: consent denied/cancelled (code=$resultCode)")
            l?.onConsentDenied()
        }
    }
}
