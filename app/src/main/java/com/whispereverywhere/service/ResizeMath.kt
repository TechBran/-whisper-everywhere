package com.whispereverywhere.service

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
        val widthDp = (startWidthDp + dragDxPx / density).coerceIn(
            MIN_WIDTH_DP,
            maxWidthDp(screenWidthPx, density).coerceAtLeast(MIN_WIDTH_DP),
        )
        val heightDp = (startHeightDp - dragDyPx / density).coerceIn(
            MIN_HEIGHT_DP,
            maxHeightDp(screenHeightPx, density).coerceAtLeast(MIN_HEIGHT_DP),
        )
        val windowDyPx = -((heightDp - startHeightDp) * density).roundToInt()
        return Result(widthDp, heightDp, windowDyPx)
    }
}
