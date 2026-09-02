package com.whispereverywhere.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one bit that separates "this app blocks capture" from "the video is paused".
 *
 * Before this rule the watchdog fired on silence alone, so a paused YouTube video looked exactly
 * like Netflix refusing to be captured, and the service handed the live session to the microphone
 * — bleeding the room into a transcript that was supposed to hold only the video (owner rule,
 * 2026-09-02). These cases are the two sides of that mistake.
 */
class SilentStreamPolicyTest {

    private val timeout = SilentStreamPolicy.SILENT_TIMEOUT_MS

    @Test fun a_stream_silent_from_its_first_buffer_is_a_blocked_app() {
        // Netflix and friends: zeroes from the start, forever. This is the case the fallback exists
        // for — the only route to that audio is the speakers into the microphone.
        assertTrue(SilentStreamPolicy.isBlockedByApp(everCarriedAudio = false, silentForMs = timeout))
        assertTrue(SilentStreamPolicy.isBlockedByApp(everCarriedAudio = false, silentForMs = timeout * 10))
    }

    @Test fun a_paused_video_is_never_blocked_however_long_it_stays_quiet() {
        // THE REGRESSION THIS FILE EXISTS FOR. A pause, a silent passage, a gap between tracks —
        // the stream carried audio, so the session stays on device audio where the user put it.
        assertFalse(SilentStreamPolicy.isBlockedByApp(everCarriedAudio = true, silentForMs = timeout))
        assertFalse(SilentStreamPolicy.isBlockedByApp(everCarriedAudio = true, silentForMs = 60_000L))
        assertFalse(SilentStreamPolicy.isBlockedByApp(everCarriedAudio = true, silentForMs = Long.MAX_VALUE))
    }

    @Test fun a_new_stream_gets_the_full_window_before_it_is_judged() {
        // Capture opening during a quiet moment must not be called blocked on the first buffer.
        assertFalse(SilentStreamPolicy.isBlockedByApp(everCarriedAudio = false, silentForMs = 0L))
        assertFalse(SilentStreamPolicy.isBlockedByApp(everCarriedAudio = false, silentForMs = timeout - 1))
    }

    @Test fun the_window_is_three_seconds() {
        // Long enough to sit through a quiet opening, short enough that a blocked app is caught
        // before the user wonders why nothing is appearing.
        assertTrue(SilentStreamPolicy.SILENT_TIMEOUT_MS == 3000L)
    }
}
