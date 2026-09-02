package com.whispereverywhere.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsBufferPolicyTest {

    // Local unit cap: 80 chars * 45 ms = 3600 ms max unit.
    private fun localPolicy() = TtsBufferPolicy(dMaxMs = 3_600)

    // ---------------------------------------------------------------- the watermark

    @Test fun playback_holds_until_the_watermark_and_then_proceeds() {
        val p = localPolicy()
        // Default RTF 0.75: target = 1.25 * 0.75 * 3600 = 3375 ms.
        assertEquals(3_375, p.targetMs())
        assertFalse(p.shouldProceed(bufferedMs = 1_000, waitedMs = 0, done = false))
        assertTrue(p.shouldProceed(bufferedMs = 3_400, waitedMs = 0, done = false))
    }

    @Test fun the_watermark_is_clamped_to_the_floor_and_ceiling() {
        // A tiny dMax still banks the floor (scheduling jitter insurance)...
        assertEquals(TtsBufferPolicy.MIN_WM_MS, TtsBufferPolicy(dMaxMs = 200).targetMs())
        // ...and a huge cloud unit (220 chars ~ 9.9 s) cannot demand more than the ceiling.
        assertEquals(TtsBufferPolicy.MAX_WM_MS, TtsBufferPolicy(dMaxMs = 9_900).targetMs())
    }

    @Test fun a_fast_measured_engine_lowers_the_watermark() {
        val p = localPolicy()
        // Feed steady measurements at the Fold 6's real RTF (~0.58): the EWMA converges down and
        // the target drops below the conservative default's 3375 ms.
        repeat(20) { p.recordRtf(synthMs = 580, audMs = 1_000) }
        assertTrue("target ${p.targetMs()} should reflect the measured RTF", p.targetMs() < 2_900)
    }

    @Test fun rtf_samples_with_no_audio_are_ignored_not_divided_by_zero() {
        val p = localPolicy()
        p.recordRtf(synthMs = 500, audMs = 0)
        assertEquals(3_375, p.targetMs()) // unchanged
    }

    // ---------------------------------------------------------------- the guards

    @Test fun an_empty_bank_never_proceeds_even_past_the_cap() {
        // THE flap guard from the spec: without it a long drought lets the cap fire on an empty
        // store and the loop strobes play/pause/onBuffering at ~0.4 Hz.
        val p = localPolicy()
        assertFalse(p.shouldProceed(bufferedMs = 0, waitedMs = 60_000, done = false))
    }

    @Test fun a_finished_producer_bypasses_the_watermark() {
        // Once the producer is done the bank is all there will ever be; holding the tail against
        // an unreachable target would strand it.
        val p = localPolicy()
        assertTrue(p.shouldProceed(bufferedMs = 100, waitedMs = 0, done = true))
    }

    @Test fun the_cap_bounds_added_latency_when_audio_exists() {
        // A slow cloud producer: after CAP_MS with SOMETHING banked, start anyway.
        val p = localPolicy()
        assertFalse(p.shouldProceed(bufferedMs = 500, waitedMs = 2_000, done = false))
        assertTrue(p.shouldProceed(bufferedMs = 500, waitedMs = TtsBufferPolicy.CAP_MS, done = false))
    }

    // ---------------------------------------------------------------- resume hysteresis

    @Test fun each_stall_raises_the_resume_target_up_to_a_bound() {
        val p = localPolicy()
        val before = p.targetMs()
        p.onStall()
        assertEquals(before + TtsBufferPolicy.STALL_BUMP_MS, p.targetMs())
        // The bump saturates: a pathologically stalling read cannot push the target past the
        // ceiling-plus-max-bump into never-resuming territory.
        repeat(10) { p.onStall() }
        assertEquals(TtsBufferPolicy.MAX_WM_MS, p.targetMs())
    }

    // ---------------------------------------------------------------- the start gate (4.3.1 C)

    // A 2-minute read: 120 000 ms of audio estimated. Local RTF 0.58 after 20 samples.
    private fun measuredLocal(): TtsBufferPolicy = localPolicy().also { p ->
        repeat(20) { p.recordRtf(synthMs = 580, audMs = 1_000) }
    }

    @Test fun an_empty_bank_never_starts_whatever_the_rule() {
        val p = measuredLocal()
        assertNull(p.startDecision(bufferedMs = 0, remainingMs = 0, totalMs = 120_000, noGrowthMs = 60_000, done = true))
    }

    @Test fun a_finished_producer_starts_at_once() {
        assertEquals(StartRule.DONE, measuredLocal().startDecision(bufferedMs = 300, remainingMs = 0, totalMs = 5_000, noGrowthMs = 0, done = true))
    }

    @Test fun projected_complete_starts_at_47_percent_for_the_measured_local_voice() {
        // banked >= 1.5 * 0.58 * remaining  <=>  banked/total >= 0.87/1.87 = 46.5 %
        val p = measuredLocal()
        val total = 120_000
        assertNull("46 % is short", p.startDecision(bufferedMs = 55_000, remainingMs = 65_000, totalMs = total, noGrowthMs = 0, done = false))
        assertEquals(StartRule.PROJECTED, p.startDecision(bufferedMs = 57_000, remainingMs = 63_000, totalMs = total, noGrowthMs = 0, done = false))
    }

    @Test fun a_slow_producer_needs_more_but_never_everything() {
        val p = localPolicy()
        repeat(20) { p.recordRtf(synthMs = 2_000, audMs = 1_000) }   // rtf 2.0
        val total = 120_000
        // 1.5 * 2.0 / (1 + 3.0) = 75 %
        assertNull(p.startDecision(bufferedMs = 89_000, remainingMs = 31_000, totalMs = total, noGrowthMs = 0, done = false))
        assertEquals(StartRule.PROJECTED, p.startDecision(bufferedMs = 91_000, remainingMs = 29_000, totalMs = total, noGrowthMs = 0, done = false))
    }

    @Test fun rtf_one_starts_at_60_percent() {
        val p = localPolicy()
        repeat(20) { p.recordRtf(synthMs = 1_000, audMs = 1_000) }
        // 1.5 * 1.0 / (1 + 1.5) = 60 % of 120 000 = 72 000 banked / 48 000 remaining.
        assertNull(p.startDecision(bufferedMs = 71_000, remainingMs = 49_000, totalMs = 120_000, noGrowthMs = 0, done = false))
        assertEquals(StartRule.PROJECTED, p.startDecision(bufferedMs = 73_000, remainingMs = 47_000, totalMs = 120_000, noGrowthMs = 0, done = false))
    }

    @Test fun a_short_read_completes_first_even_when_the_projection_would_pass() {
        val p = measuredLocal()
        // 19 s total, 18 s banked, 1 s remaining: projection passes, floor says wait for done.
        assertNull(p.startDecision(bufferedMs = 18_000, remainingMs = 1_000, totalMs = 19_000, noGrowthMs = 0, done = false))
        assertEquals(StartRule.DONE, p.startDecision(bufferedMs = 19_000, remainingMs = 0, totalMs = 19_000, noGrowthMs = 0, done = true))
        // 21 s total is not short: the projection applies.
        assertEquals(StartRule.PROJECTED, p.startDecision(bufferedMs = 18_000, remainingMs = 3_000, totalMs = 21_000, noGrowthMs = 0, done = false))
    }

    @Test fun the_start_cap_fires_on_no_growth_time_only_and_needs_audio() {
        val p = measuredLocal()
        assertEquals(StartRule.CAP, p.startDecision(bufferedMs = 800, remainingMs = 100_000, totalMs = 120_000, noGrowthMs = TtsBufferPolicy.START_CAP_MS, done = false))
        assertNull(p.startDecision(bufferedMs = 800, remainingMs = 100_000, totalMs = 120_000, noGrowthMs = TtsBufferPolicy.START_CAP_MS - 1, done = false))
        assertNull(p.startDecision(bufferedMs = 0, remainingMs = 100_000, totalMs = 120_000, noGrowthMs = 60_000, done = false))
        // The cap also frees a SHORT read whose producer stopped growing.
        assertEquals(StartRule.CAP, p.startDecision(bufferedMs = 800, remainingMs = 5_000, totalMs = 10_000, noGrowthMs = TtsBufferPolicy.START_CAP_MS, done = false))
    }

    @Test fun should_start_is_the_decision_made_boolean_and_the_constants_are_the_specs() {
        val p = measuredLocal()
        assertTrue(p.shouldStart(bufferedMs = 300, remainingMs = 0, totalMs = 5_000, noGrowthMs = 0, done = true))
        assertFalse(p.shouldStart(bufferedMs = 0, remainingMs = 0, totalMs = 5_000, noGrowthMs = 0, done = true))
        assertEquals(20_000, TtsBufferPolicy.SHORT_READ_MS)
        assertEquals(1.5, TtsBufferPolicy.PROJECTED_SAFETY, 0.0)
        assertEquals(12_000L, TtsBufferPolicy.START_CAP_MS)
        assertEquals(0.58, p.rtf(), 0.02)
    }

    @Test fun the_resume_rule_is_untouched() {
        // shouldProceed is the stall-resume gate and keeps its watermark semantics byte-for-byte.
        val p = localPolicy()
        assertEquals(3_375, p.targetMs())
        assertFalse(p.shouldProceed(bufferedMs = 1_000, waitedMs = 0, done = false))
        assertTrue(p.shouldProceed(bufferedMs = 3_400, waitedMs = 0, done = false))
    }
}
