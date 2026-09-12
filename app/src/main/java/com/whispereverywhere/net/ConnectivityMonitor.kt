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
 *
 * **ONE PREDICATE, AND IT IS NOT A CONSENT QUESTION (4.5.0 pass 2, Fix 1).** This class also held
 * `isUnmetered()` — `NOT_METERED && VALIDATED` — for the previewer pack's auto-fetch, under a
 * CONTROLLER ruling that a metered connection should become a card with a tap. **The owner
 * overruled that: *"Yes. I wanted to silently download on cellular and Wi Fi."*** (2026-09-11,
 * asked directly whether both acquisition paths should simply download.) So the metering read is
 * deleted from the app entirely — `NET_CAPABILITY_NOT_METERED` appears nowhere in `src/main`, and
 * `LivePreviewDeclinedPinTest` walks the tree to keep it that way.
 *
 * **The VALIDATED half deliberately survives, in this function, and it is a different question.**
 * "May this app spend these bytes" is gone; "is there a network at all" remains, because a fetch
 * started on a captive portal fails and then parks the previewer pack behind its 24 h back-off —
 * withholding the model for a day after the user reaches a network that would have worked. So a
 * reader who finds `ConnectivityMonitor` still referenced from the previewer has not found a
 * half-applied ruling: metered went, validated stayed, and this is the line that says so.
 */
class ConnectivityMonitor(private val context: Context) {
    fun hasValidatedNetwork(): Boolean = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }.getOrDefault(false)
}
