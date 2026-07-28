package com.whispereverywhere.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * One `NET_CAPABILITY_VALIDATED` lookup — no round trip. The app already declares
 * ACCESS_NETWORK_STATE but had ZERO connectivity awareness before this.
 *
 * VALIDATED rather than merely connected: a captive-portal wifi reports connected while every
 * request fails, which would otherwise present to the user as "your key is broken".
 */
class ConnectivityMonitor(private val context: Context) {
    fun hasValidatedNetwork(): Boolean = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }.getOrDefault(false)
}
