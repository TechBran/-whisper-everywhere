package com.whispereverywhere.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ribbon's flow and envelope rates, expressed per SECOND (owner report 2026-09-02: the
 * aurora "starts off super fast and then it mellows out to match the voice much better").
 *
 * The cause was frame-COUNT animation: `BarWaveformView` advanced `phase += 0.13f` per animator
 * callback under a comment that said "60fps continuous animation". On an adaptive-refresh panel
 * (the owner's Fold6 runs 120 Hz while there is touch activity and drops to 60 Hz once it
 * settles) that is literally two different animations — the ribbon flowed at double speed, and
 * the envelope attacked and released twice as fast per second, which is the "matches the voice
 * much better afterwards" half of the same report. `BlobView` already integrated against `dt`
 * (`flowPhase1 += dt * 1.9f`), so the ribbon was the one place in the UI still counting frames.
 *
 * These tests pin the two properties that make the fix a fix rather than a re-tune:
 *  1. **At 60 Hz nothing changes** — every rate reproduces the constant it replaced, so the
 *     speed the owner already likes is preserved exactly, and a 60 Hz phone sees no difference.
 *  2. **Refresh rate cannot change the motion** — two 120 Hz steps do what one 60 Hz step did.
 *     That is the property a per-frame constant can never have, and the only one worth pinning:
 *     it is what makes 90 Hz, 120 Hz and a throttled panel all look the same.
 */
class RibbonFlowTest {

    private val TICK_60 = 1f / 60f
    private val TICK_120 = 1f / 120f

    // The per-frame constants BarWaveformView used before the fix, kept here as the oracle the
    // rates are derived from: rate = -60 * ln(1 - k).
    private val legacyAttack = 0.65f
    private val legacyRelease = 0.10f
    private val legacyBandReleases = floatArrayOf(0.055f, 0.085f, 0.115f, 0.16f)

    @Test
    fun at_60hz_every_rate_reproduces_the_constant_it_replaced() {
        assertEquals(legacyAttack, RibbonFlow.lerpFactor(RibbonFlow.ATTACK_PER_SEC, TICK_60), 1e-3f)
        assertEquals(legacyRelease, RibbonFlow.lerpFactor(RibbonFlow.RELEASE_PER_SEC, TICK_60), 1e-3f)
        for (j in legacyBandReleases.indices) {
            assertEquals(
                "band $j",
                legacyBandReleases[j],
                RibbonFlow.lerpFactor(RibbonFlow.BAND_RELEASES_PER_SEC[j], TICK_60),
                1e-3f,
            )
        }
    }

    @Test
    fun at_60hz_the_phase_advances_by_the_old_per_frame_step() {
        // 0.13 per frame at 60 fps IS the settled speed the owner likes; it is now the only speed.
        assertEquals(0.13f, RibbonFlow.PHASE_PER_SEC * TICK_60, 1e-4f)
    }

    @Test
    fun two_120hz_steps_do_what_one_60hz_step_did() {
        // The property the old code could not have: the motion is a function of TIME, not of how
        // many times the panel happened to redraw. Composing two half-steps of an exponential
        // approach must land exactly where one full step lands.
        for (rate in listOf(RibbonFlow.ATTACK_PER_SEC, RibbonFlow.RELEASE_PER_SEC) +
            RibbonFlow.BAND_RELEASES_PER_SEC.toList()) {
            val oneStep = approach(from = 0f, target = 1f, rate = rate, dt = TICK_60, steps = 1)
            val twoHalfSteps = approach(from = 0f, target = 1f, rate = rate, dt = TICK_120, steps = 2)
            assertEquals("rate $rate", oneStep, twoHalfSteps, 1e-5f)
        }
        // ...and the phase, which is linear, likewise.
        assertEquals(
            RibbonFlow.PHASE_PER_SEC * TICK_60,
            2f * RibbonFlow.PHASE_PER_SEC * TICK_120,
            1e-6f,
        )
    }

    @Test
    fun the_first_frame_assumes_a_60hz_tick_rather_than_a_zero_or_a_jump() {
        // lastFrameNs == 0 means "no previous frame": a raw difference would be the whole uptime
        // and would teleport the phase on the first draw of every session.
        assertEquals(0.016f, RibbonFlow.frameDt(lastFrameNs = 0L, nowNs = 999_999_999_999L), 1e-6f)
    }

    @Test
    fun a_long_stall_is_clamped_and_a_backwards_clock_cannot_rewind_the_flow() {
        val second = 1_000_000_000L
        // The view was off-screen for two seconds: advance by the cap, not by two seconds.
        assertEquals(0.1f, RibbonFlow.frameDt(lastFrameNs = second, nowNs = 3L * second), 1e-6f)
        // Same timestamp twice, or a clock that went backwards: no advance, never negative.
        assertEquals(0f, RibbonFlow.frameDt(lastFrameNs = second, nowNs = second), 1e-6f)
        assertEquals(0f, RibbonFlow.frameDt(lastFrameNs = 2L * second, nowNs = second), 1e-6f)
    }

    @Test
    fun a_normal_frame_passes_through_unclamped() {
        val start = 5_000_000_000L
        val sixtyHz = start + 16_666_667L
        assertEquals(TICK_60, RibbonFlow.frameDt(start, sixtyHz), 1e-4f)
        val oneTwentyHz = start + 8_333_333L
        assertEquals(TICK_120, RibbonFlow.frameDt(start, oneTwentyHz), 1e-4f)
    }

    @Test
    fun a_factor_is_always_a_usable_lerp_weight() {
        // Guards the call site: `x += (target - x) * k` must never overshoot or reverse, whatever
        // dt the panel hands us (including the clamped stall).
        for (dt in listOf(0f, TICK_120, TICK_60, 0.1f)) {
            for (rate in listOf(RibbonFlow.ATTACK_PER_SEC, RibbonFlow.RELEASE_PER_SEC)) {
                val k = RibbonFlow.lerpFactor(rate, dt)
                assertTrue("k=$k for rate=$rate dt=$dt", k in 0f..1f)
            }
        }
        assertEquals("no time, no movement", 0f, RibbonFlow.lerpFactor(RibbonFlow.ATTACK_PER_SEC, 0f), 1e-6f)
    }

    /** Repeated exponential approach, the same arithmetic the view performs per frame. */
    private fun approach(from: Float, target: Float, rate: Float, dt: Float, steps: Int): Float {
        var x = from
        repeat(steps) { x += (target - x) * RibbonFlow.lerpFactor(rate, dt) }
        return x
    }
}
