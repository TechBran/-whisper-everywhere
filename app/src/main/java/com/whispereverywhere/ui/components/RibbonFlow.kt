package com.whispereverywhere.ui.components

import kotlin.math.exp

/**
 * The aurora ribbon's motion, expressed per SECOND instead of per frame.
 *
 * **Why this object exists.** [BarWaveformView] used to advance `phase += 0.13f` on every
 * animator callback, under a comment reading "60fps continuous animation". That comment was an
 * assumption about hardware, and on an adaptive-refresh panel it is false in both directions:
 * the owner's Fold6 runs 120 Hz while there is touch activity and settles to 60 Hz once there
 * is not, so the ribbon flowed at DOUBLE speed for the first seconds of every session and then
 * "mellowed out" (owner report, 2026-09-02). The envelope constants had the same defect — an
 * attack of 0.65 *per frame* is twice as eager per second at 120 Hz — which is the "matches the
 * voice much better afterwards" half of the same report. One cause, two symptoms.
 *
 * [BlobView] already integrates against `dt` (`flowPhase1 += dt * 1.9f`, with a comment on why),
 * so the ribbon was the last frame-counting animation in the UI.
 *
 * **The rates are derived, not chosen.** Each is `-60 * ln(1 - k)` for the per-frame constant `k`
 * it replaces, so at 60 Hz the motion is arithmetically identical to what shipped in 4.3.0 — the
 * speed the owner likes is preserved rather than re-tuned, and a 60 Hz phone sees no change at
 * all. What changes is that 90 Hz, 120 Hz and a thermally throttled panel now look the same as
 * 60 Hz instead of each being its own animation. `RibbonFlowTest` pins both halves: the 60 Hz
 * equivalence, and that two 120 Hz steps do what one 60 Hz step did.
 *
 * Pure: no Android types, so the arithmetic is JVM-testable without a View.
 */
object RibbonFlow {

    /** Flow speed of the sheets. `0.13` per frame at 60 fps — the settled, liked speed. */
    const val PHASE_PER_SEC = 7.8f

    /** Syllables hit within a frame or two, at any refresh rate. Was `ATTACK = 0.65f`. */
    const val ATTACK_PER_SEC = 62.99f

    /** The composite envelope's drift back down. Was `RELEASE = 0.10f`. */
    const val RELEASE_PER_SEC = 6.3216f

    /** Per band: bass lingers, sibilance vanishes fast. Was `0.055/0.085/0.115/0.16` per frame. */
    val BAND_RELEASES_PER_SEC = floatArrayOf(3.3942f, 5.3298f, 7.3303f, 10.4612f)

    /** The default first-frame step: one 60 Hz tick, matching [BlobView]'s own cold start. */
    private const val FIRST_FRAME_DT = 0.016f

    /** Longest step any single frame may take — the off-screen / stalled-view bound. */
    private const val MAX_DT = 0.1f

    /**
     * Seconds since the previous frame, bounded so no single frame can teleport the flow.
     *
     * `lastFrameNs == 0L` means "no previous frame": the raw difference would be the whole
     * process uptime, which would jump the phase on the first draw of every session. A stall
     * (the view off-screen, the device asleep) is clamped to [MAX_DT]; a repeated or backwards
     * timestamp yields zero rather than a negative step, which would run the ribbon in reverse.
     */
    fun frameDt(lastFrameNs: Long, nowNs: Long): Float =
        if (lastFrameNs == 0L) FIRST_FRAME_DT
        else ((nowNs - lastFrameNs) / 1e9f).coerceIn(0f, MAX_DT)

    /**
     * The weight for `x += (target - x) * k` over [dt] seconds at [ratePerSec].
     *
     * `1 - exp(-rate * dt)` is the exponential approach the per-frame lerp was approximating, so
     * composing it over sub-steps is exact: two half-frames land where one whole frame landed.
     * Always in `0..1`, so the call site can never overshoot its target or reverse.
     */
    fun lerpFactor(ratePerSec: Float, dt: Float): Float = 1f - exp(-ratePerSec * dt)
}
