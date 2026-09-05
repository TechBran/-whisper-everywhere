package com.whispereverywhere.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The bubble's START gate on Home (4.3.3, accessibility-optional-spec §3), extracted from the
 * composable's inline conjunction so that it CAN be pinned: a model on disk, the mic, and the
 * overlay — and not the accessibility service, which is what typing needs, not what the bubble
 * needs. Exhaustive over the three inputs; the status line the same brief adds under the control
 * is pinned beside it.
 */
class HomeGateTest {

    private val tf = listOf(true, false)

    @Test fun the_bubble_starts_on_model_mic_and_overlay_and_nothing_else() {
        // Before 4.3.3 the inline gate read `... && hasAccessibilityEnabled`, and a user who
        // could not enable the service — every Galaxy XR user — could not start the bubble at
        // all. The signature is the pin: there is no fourth parameter to consult.
        for (model in tf) for (mic in tf) for (overlay in tf) {
            assertEquals(
                "model=$model mic=$mic overlay=$overlay",
                model && mic && overlay,
                HomeGate.canEnable(
                    hasSpeechModel = model,
                    hasMicrophonePermission = mic,
                    hasOverlayPermission = overlay,
                ),
            )
        }
    }

    @Test fun the_typing_status_line_shows_exactly_while_the_service_is_off() {
        // Brief §3, verbatim. It names the trade — off, copied — and nothing else; it makes no
        // claim about WHY the service is off, because Home has no returned-from-Settings signal.
        assertEquals(
            "Typing into apps: off — transcripts are copied to the clipboard",
            HomeGate.TYPING_OFF_STATUS,
        )
        assertEquals(HomeGate.TYPING_OFF_STATUS, HomeGate.typingStatusLine(accessibilityEnabled = false))
        assertNull("the clean dashboard stays clean", HomeGate.typingStatusLine(accessibilityEnabled = true))
        assertFalse(HomeGate.TYPING_OFF_STATUS.lowercase().contains("required"))
    }

    @Test fun the_control_copy_composes_with_the_gate_as_the_screen_composes_it() {
        // A closed gate still reads "Complete setup first" and points at Settings; an open one
        // reads "Tap to Enable" — with or without the service, which is the whole point.
        val closed = mainControlLabels(
            isEnabled = false,
            canEnable = HomeGate.canEnable(hasSpeechModel = true, hasMicrophonePermission = true, hasOverlayPermission = false),
        )
        assertEquals("Complete setup first", closed.title)
        val open = mainControlLabels(
            isEnabled = false,
            canEnable = HomeGate.canEnable(hasSpeechModel = true, hasMicrophonePermission = true, hasOverlayPermission = true),
        )
        assertEquals("Tap to Enable", open.title)
    }
}
