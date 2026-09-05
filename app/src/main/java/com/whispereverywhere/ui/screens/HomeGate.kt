package com.whispereverywhere.ui.screens

/**
 * The bubble's START gate on Home, and the typing-status line under the control (4.3.3,
 * accessibility-optional-spec §3). Pure and top-level in the [mainControlLabels] family so the
 * rule is a JVM test ([HomeGateTest]) rather than a conjunction inside a composable.
 *
 * Until 4.3.3 the inline gate read `hasSpeechModel && mic && overlay && hasAccessibilityEnabled`,
 * and a user who could not enable the accessibility service — every Galaxy XR user, whose device
 * policy forbids third-party accessibility services outright — could not start the bubble at
 * all. The service is what TYPING needs, not what the bubble needs: without it the bubble still
 * transcribes, shows, and copies (FinalDeliveryPolicy's service-off row), so its absence is a
 * STATUS under the control, not a lock on it.
 */
object HomeGate {

    /** Model on disk, mic, overlay — and nothing else. */
    fun canEnable(
        hasSpeechModel: Boolean,
        hasMicrophonePermission: Boolean,
        hasOverlayPermission: Boolean,
    ): Boolean = hasSpeechModel && hasMicrophonePermission && hasOverlayPermission

    /**
     * The status line while the service is off (brief §3, verbatim). It names the trade and
     * nothing else — Home has no returned-from-Settings signal, so it makes no claim about WHY;
     * the tap-through lands on Settings' accessibility row, which carries the Enable path and
     * the platform-aware guidance. Pinned by HomeGateTest.
     */
    const val TYPING_OFF_STATUS = "Typing into apps: off — transcripts are copied to the clipboard"

    /** [TYPING_OFF_STATUS] while the service is off; null once it is on (the dashboard stays clean). */
    fun typingStatusLine(accessibilityEnabled: Boolean): String? =
        if (accessibilityEnabled) null else TYPING_OFF_STATUS
}
