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

    /**
     * Whether the active network is one this app may spend WITHOUT ASKING — 4.4.1's auto-fetch of
     * the previewer pack, and the only predicate the CONTROLLER RULING on data hangs on
     * (`PreviewAutoFetch`'s `unmetered`). The platform's own `NET_CAPABILITY_NOT_METERED`, in the
     * same shape as [hasValidatedNetwork] above rather than a second idiom.
     *
     * THE DEFAULT IS THE SAFE SIDE, twice over: no active network, no capabilities, or a throwing
     * `ConnectivityManager` all read as METERED, so the card shows its tap-to-fetch instead of a
     * silent 73 MB transfer starting on a phone that could not finish it.
     *
     * `NET_CAPABILITY_NOT_METERED`, not `isActiveNetworkMetered()`: the capability is the reading
     * Play's own asset-delivery consent uses, it does not invert the sense, and one spelling of a
     * consent question is one more than this app needs to be able to disagree with itself about.
     *
     * AND VALIDATED, by the CONTROLLER RULING of 2026-09-11 (CHANGE 1, answering the auto-fetch
     * round's own C6). A captive-portal wifi — a hotel, an airport, a coffee shop — reports
     * NOT_METERED while every request fails. Reading that as "spend freely" starts a transfer
     * that cannot finish, and the 24 h back-off that failure writes then withholds the model for
     * a DAY after the user reaches a network that would have worked. The cost of requiring
     * VALIDATED is that an unvalidated wifi shows the card's tap instead of fetching silently —
     * a correct wait. The cost of not requiring it was a day of silence.
     *
     * (4.5.0 Task 3b) It is read on the UNASKED path only. A transfer the user caused by picking
     * a language never consults it — the pick IS the consent — so a `false` here still means
     * *"ask before you spend"* and never *"say nothing"*.
     */
    fun isUnmetered(): Boolean = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }.getOrDefault(false)
}
