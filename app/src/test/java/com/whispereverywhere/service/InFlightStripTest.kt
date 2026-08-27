package com.whispereverywhere.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InFlightStripTest {

    // ------------------------------------------------------------- who owns the strip

    @Test fun a_server_driven_live_session_keeps_its_deltas_on_the_strip() {
        // CLOUD_LIVE partials stream AS SPOKEN and are the whole point of the strip there.
        // 3.7 changes nothing for them.
        assertTrue(deltaOwnsPreviewStrip(sessionIsLive = true))
    }

    @Test fun a_local_session_no_longer_lets_native_deltas_drive_the_strip() {
        // whisper.cpp fires new_segment AFTER the window's decode, so at utterance cadence the
        // whole burst — and LocalWhisperEngine's terminal onDelta("") — lands inside one
        // Choreographer frame: set and cleared before anything renders. The commit/resolve
        // in-flight line replaces it. D4's plumbing (DeltaThrottle, the JNI callback,
        // transcribeStreaming) is untouched — only this render is gated.
        assertFalse(deltaOwnsPreviewStrip(sessionIsLive = false))
    }

    // ------------------------------------------------------------- what the line says

    @Test fun an_empty_queue_has_no_line() {
        assertNull(inFlightStripLabel(0))
    }

    @Test fun a_negative_depth_is_treated_as_empty() {
        // SegmentQueueDepth floors at zero, but the label must not be the only thing standing
        // between a miscount and a "-1 in queue" on a user's screen.
        assertNull(inFlightStripLabel(-1))
    }

    @Test fun one_utterance_in_flight_says_only_that() {
        assertEquals("Transcribing…", inFlightStripLabel(1))
    }

    @Test fun a_backlog_names_its_depth() {
        // The ONLY surface that makes a growing multi backlog visible WHILE it grows.
        assertEquals("Transcribing… (2 in queue)", inFlightStripLabel(2))
        assertEquals("Transcribing… (7 in queue)", inFlightStripLabel(7))
    }

    @Test fun the_line_makes_no_speed_claim_and_names_no_provider() {
        // Same copy discipline as HowToGuide and every other user-facing string.
        listOf(0, 1, 2, 9).forEach { d ->
            val text = (inFlightStripLabel(d) ?: "").lowercase()
            listOf("faster", "fastest", "quicker", "quickest", "instant", "real-time")
                .forEach { banned ->
                    assertFalse("in-flight line contains banned word '$banned'", text.contains(banned))
                }
        }
    }
}
