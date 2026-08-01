package com.whispereverywhere.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
