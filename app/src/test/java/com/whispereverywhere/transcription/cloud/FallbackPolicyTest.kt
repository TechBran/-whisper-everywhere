package com.whispereverywhere.transcription.cloud

import com.whispereverywhere.transcription.SegmentOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FallbackPolicyTest {

    // ------------------------------------------------------------ shouldFallBack

    @Test fun a_lost_segment_falls_back_to_local() {
        assertTrue(FallbackPolicy.shouldFallBack(SegmentOutcome.Lost("offline")))
    }

    @Test fun successful_text_never_falls_back() {
        assertFalse(FallbackPolicy.shouldFallBack(SegmentOutcome.Text("hello")))
    }

    @Test fun an_empty_transcript_does_NOT_fall_back() {
        // The user said nothing. Re-running silence locally burns 2-6 seconds to produce the same
        // empty string, and on cloud it would have cost money too.
        assertFalse(FallbackPolicy.shouldFallBack(SegmentOutcome.EmptyExpected))
    }

    @Test fun an_unexpected_empty_does_fall_back() {
        // Real voiced audio that came back empty is a lost sentence worth a second attempt.
        assertTrue(FallbackPolicy.shouldFallBack(SegmentOutcome.EmptyUnexpected))
    }

    // ------------------------------------------------------------ reconcile

    @Test fun a_local_transcript_replaces_the_cloud_loss_that_armed_the_retry() {
        assertEquals(
            SegmentOutcome.Text("recovered"),
            FallbackPolicy.reconcile(SegmentOutcome.Lost("offline"), SegmentOutcome.Text("recovered")),
        )
    }

    @Test fun whispers_silence_verdict_erases_the_clouds_loss_marker() {
        // THE fix for the owner-reported "[…] at the very end of every message" (2026-07-31). The
        // tail turn — the trailing room tone between the user's last word and the stop tap — is
        // resolved Lost by the live engine at stop, because no provider VAD can endpoint it once
        // the mic is closed. The mirror then hands that exact PCM to whisper, whose Silero VAD
        // finds no speech and answers EmptyExpected. A segment with no speech in it is not a lost
        // sentence, so the marker must not survive: the user typed nothing there and should see
        // nothing there.
        assertEquals(
            SegmentOutcome.EmptyExpected,
            FallbackPolicy.reconcile(SegmentOutcome.Lost("session ended"), SegmentOutcome.EmptyExpected),
        )
    }

    @Test fun a_local_engine_that_never_ran_leaves_the_clouds_visible_loss_alone() {
        // The other half of the rule above, and what keeps trusting EmptyExpected sound.
        // LocalWhisperEngine reserves EmptyExpected for "whisper ran and heard no speech"; the
        // branches where it never ran at all — no model installed, transcribe threw — resolve
        // Lost. Those must NOT erase the cloud's marker, or a real sentence would silently vanish
        // on exactly the devices with no safety net: cloud users with no model installed.
        assertEquals(
            SegmentOutcome.Lost("offline"),
            FallbackPolicy.reconcile(SegmentOutcome.Lost("offline"), SegmentOutcome.Lost("speech model not loaded")),
        )
    }

    @Test fun an_unexpected_local_empty_does_not_erase_the_clouds_visible_loss() {
        // EmptyUnexpected is the "real voiced audio produced no text" verdict — a lost sentence in
        // its own right, not a silence proof. It has no business overwriting the cloud's marker.
        assertEquals(
            SegmentOutcome.Lost("offline"),
            FallbackPolicy.reconcile(SegmentOutcome.Lost("offline"), SegmentOutcome.EmptyUnexpected),
        )
    }

    @Test fun a_blank_local_transcript_is_not_treated_as_a_recovery() {
        // Defensive: a blank Text would type nothing while claiming the retry succeeded.
        assertEquals(
            SegmentOutcome.EmptyUnexpected,
            FallbackPolicy.reconcile(SegmentOutcome.EmptyUnexpected, SegmentOutcome.Text("   ")),
        )
    }
}
