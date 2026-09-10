package com.whispereverywhere.probe

import android.util.Log

/** Everything the probe learns goes to logcat under one tag so the PC driver can read it back. */
object ProbeLog {
    const val TAG = "PROBE"
    @Volatile var sink: ((String) -> Unit)? = null

    fun i(msg: String) { Log.i(TAG, msg); sink?.invoke(msg) }
    fun w(msg: String) { Log.w(TAG, msg); sink?.invoke("W " + msg) }
    fun e(msg: String, t: Throwable? = null) {
        Log.e(TAG, msg, t)
        sink?.invoke("E " + msg + (t?.let { " :: " + it } ?: ""))
    }
}
