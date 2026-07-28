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

    @Test fun a_local_empty_does_not_erase_the_clouds_visible_loss() {
        // LocalWhisperEngine resolves BOTH "VAD proved silence" and "no model loaded" as
        // EmptyExpected and documents that Kotlin cannot tell them apart. Letting that outcome
        // win would turn a loss the user can see ("[…]") into a sentence that silently vanished
        // every time the safety net has no model installed.
        assertEquals(
            SegmentOutcome.Lost("offline"),
            FallbackPolicy.reconcile(SegmentOutcome.Lost("offline"), SegmentOutcome.EmptyExpected),
        )
    }

    @Test fun a_local_loss_keeps_the_clouds_reason_rather_than_inventing_one() {
        assertEquals(
            SegmentOutcome.Lost("offline"),
            FallbackPolicy.reconcile(SegmentOutcome.Lost("offline"), SegmentOutcome.Lost("model missing")),
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
