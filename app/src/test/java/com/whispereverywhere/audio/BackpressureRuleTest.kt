package com.whispereverywhere.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE BACKPRESSURE GOVERNOR's pure rule (build 85), pinned where it lives — in the audio package,
 * beside the endpointer that steps it on the capture thread. `CommitCadencePolicyTest` pins the
 * SAME rule again through the service object's surface (`floorFor`, the two `BACKPRESSURE_*`
 * constants), which is the one the brief names and the one the service reads; the two files
 * agreeing is what keeps the rule from being re-derived on either side of the package seam.
 *
 * Exhaustive on purpose: depth 0..4 x mode on/off is ten rows, and the hysteresis band between
 * [BackpressureRule.LEAVE_DEPTH] and [BackpressureRule.ENTER_DEPTH] is EMPTY at the shipped
 * constants (2 and 1 are adjacent), so "otherwise keep the current mode" is unreachable today.
 * That branch is kept, and pinned by name, because widening ENTER is the retune this rule is
 * built to absorb — without a keep band a two-state rule with distinct thresholds is a one-line
 * threshold wearing two constants.
 */
class BackpressureRuleTest {

    @Test fun the_thresholds_are_the_ruled_ones() {
        // Owner on 83: "I have never seen more than two queued"; sheet E2's bound is 0-2. Two is
        // one segment in flight AND one waiting — the first depth that says the tier is behind.
        assertEquals(2, BackpressureRule.ENTER_DEPTH)
        // Leave once at most one segment is in flight and nothing waits.
        assertEquals(1, BackpressureRule.LEAVE_DEPTH)
        assertTrue(
            "a two-state rule needs ENTER above LEAVE or it flaps at one depth",
            BackpressureRule.ENTER_DEPTH > BackpressureRule.LEAVE_DEPTH,
        )
    }

    @Test fun the_mode_step_is_pinned_exhaustively() {
        // (depth, mode before) -> mode after. Every row, both directions.
        val table = listOf(
            Triple(0, false, false), Triple(0, true, false),
            Triple(1, false, false), Triple(1, true, false),
            Triple(2, false, true), Triple(2, true, true),
            Triple(3, false, true), Triple(3, true, true),
            Triple(4, false, true), Triple(4, true, true),
        )
        for ((depth, before, after) in table) {
            assertEquals("depth=$depth slow=$before", after, BackpressureRule.slowActive(depth, before))
        }
    }

    @Test fun the_keep_band_is_empty_at_the_shipped_constants_and_that_is_stated() {
        // ENTER - LEAVE == 1 means no depth falls between them: every observation decides the
        // mode outright. Widening ENTER to 3 would open the band {2}, where the mode is KEPT —
        // this test is the place that fact is recorded, so the retune changes it knowingly.
        assertEquals(1, BackpressureRule.ENTER_DEPTH - BackpressureRule.LEAVE_DEPTH)
    }

    @Test fun the_floor_follows_the_mode_and_nothing_else() {
        assertEquals(2_000L, BackpressureRule.floorMs(slowActive = false, fastMs = 2_000L, slowMs = 3_200L))
        assertEquals(3_200L, BackpressureRule.floorMs(slowActive = true, fastMs = 2_000L, slowMs = 3_200L))
        // Inert when the two floors are equal — the Endpointer.onSessionStart default.
        assertEquals(6_000L, BackpressureRule.floorMs(slowActive = true, fastMs = 6_000L, slowMs = 6_000L))
        assertEquals(6_000L, BackpressureRule.floorMs(slowActive = false, fastMs = 6_000L, slowMs = 6_000L))
    }

    @Test fun a_negative_depth_is_treated_as_empty() {
        // SegmentQueueDepth can never report one (it counts a set), but the rule must not turn a
        // defensive impossibility into a latched slow floor.
        assertEquals(false, BackpressureRule.slowActive(-1, true))
    }
}
