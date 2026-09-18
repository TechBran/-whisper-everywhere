package com.whispereverywhere.service

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Pure geometry for the transcript-window resize handle (W3). The handle sits at the preview's
 * TOP-RIGHT; the overlay window is TOP-LEFT anchored (Gravity.TOP or Gravity.START) and grows
 * downward, so:
 *  - width follows the finger horizontally (dragDxPx > 0 = wider);
 *  - height grows when dragging UP (dragDyPx < 0 = taller);
 *  - [Result.windowDyPx] moves params.y by exactly the height change in px, so the TOP edge
 *    follows the finger while the mic pill below stays put.
 *
 * **Axis lock (4.9.1, owner 2026-09-17: "touching the resize portion and moving up should resize
 * and lock the window vertically, and the same horizontally, and moving in combination should of
 * course also work").** A drag that is clearly along one axis holds the other: when
 * `|dx| >= AXIS_LOCK_RATIO * |dy|` only the width changes (height = start, so `windowDyPx` is 0
 * and the window does not walk), when `|dy| >= AXIS_LOCK_RATIO * |dx|` only the height changes,
 * and anything between is the diagonal, where both change. 2.5 is about 22 degrees off the axis —
 * wide enough that a thumb's natural wobble stays locked, narrow enough that a deliberate
 * diagonal is one. The test is made against the TOTAL drag from the start point on every call,
 * never incrementally, so a drag that starts vertical and then heads for the corner becomes a
 * diagonal on the move that makes it one, and a drag that straightens out locks again.
 *
 * Bounds are re-derived from the LIVE screen metrics on every call (rotation-safe), and each
 * screen-derived max is floored at its min so a pathological screen can never make coerceIn
 * throw (coerceIn(min, max) throws IllegalArgumentException when max < min).
 */
object ResizeMath {
    const val MIN_WIDTH_DP = 200f
    const val MAX_WIDTH_DP_CAP = 560f
    const val MIN_HEIGHT_DP = 80f
    const val DEFAULT_WIDTH_DP = 280f
    const val DEFAULT_HEIGHT_DP = 120f

    /** One axis's travel must be this many times the other's before the other is held. */
    const val AXIS_LOCK_RATIO = 2.5f

    data class Result(val widthDp: Float, val heightDp: Float, val windowDyPx: Int)

    fun maxWidthDp(screenWidthPx: Int, density: Float): Float =
        minOf(0.95f * screenWidthPx / density, MAX_WIDTH_DP_CAP)

    fun maxHeightDp(screenHeightPx: Int, density: Float): Float =
        0.60f * screenHeightPx / density

    fun resize(
        startWidthDp: Float,
        startHeightDp: Float,
        dragDxPx: Float,
        dragDyPx: Float,
        density: Float,
        screenWidthPx: Int,
        screenHeightPx: Int,
    ): Result {
        val ax = abs(dragDxPx)
        val ay = abs(dragDyPx)
        // The axis lock: a clearly-horizontal drag contributes no dy, a clearly-vertical one no
        // dx. A zero drag satisfies both and is the identity either way.
        val dx = if (ay >= AXIS_LOCK_RATIO * ax) 0f else dragDxPx
        val dy = if (ax >= AXIS_LOCK_RATIO * ay) 0f else dragDyPx
        val widthDp = (startWidthDp + dx / density).coerceIn(
            MIN_WIDTH_DP,
            maxWidthDp(screenWidthPx, density).coerceAtLeast(MIN_WIDTH_DP),
        )
        val heightDp = (startHeightDp - dy / density).coerceIn(
            MIN_HEIGHT_DP,
            maxHeightDp(screenHeightPx, density).coerceAtLeast(MIN_HEIGHT_DP),
        )
        val windowDyPx = -((heightDp - startHeightDp) * density).roundToInt()
        return Result(widthDp, heightDp, windowDyPx)
    }
}
