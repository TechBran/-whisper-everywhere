package com.whispereverywhere.ui.components

import kotlin.math.roundToInt

/**
 * Pure geometry for [TranscriptScrubberView] (4.9.1) — the grabbable scrollbar beside the
 * transcript window's two TextViews — so the mapping between a finger on the bar and the
 * TextView's `scrollY` is testable without a View. (Not [ScrubberMath]: that is the TTS pill's
 * seek line, a different model — a fraction of synthesized audio, not a scroll position.)
 *
 * The model is the TextView's own scroll model, stated once:
 *  - `contentHeight = layout.height + paddingTop + paddingBottom` — the text's full extent;
 *  - `maxScroll = contentHeight - viewHeight`, floored at 0 — where the service's own
 *    scroll-to-newest lands (`getLineBottom(last) - height`, the same number when the paddings
 *    are 0, which on both transcript views they are);
 *  - the bar's `fraction = scrollY / maxScroll`.
 * The thumb is proportional (view/content of the track, floored at a grabbable minimum), and a
 * finger maps back through the thumb's TOP so that grabbing the thumb off-centre does not make
 * it jump: [grabOffset] remembers where on the thumb the finger landed.
 */
object TranscriptScrubberMath {

    fun contentHeight(layoutHeight: Int, paddingTop: Int, paddingBottom: Int): Int =
        layoutHeight + paddingTop + paddingBottom

    /** How far the view can scroll; 0 when the content fits (or nothing is laid out yet). */
    fun maxScroll(contentHeight: Int, viewHeight: Int): Int =
        (contentHeight - viewHeight).coerceAtLeast(0)

    /** The content fits when there is nothing to scroll to — including before first layout. */
    fun fits(contentHeight: Int, viewHeight: Int): Boolean = maxScroll(contentHeight, viewHeight) == 0

    /**
     * THE VISIBILITY CONTRACT (owner ruling 2026-09-17, carried from 4.8.0: the bar is SEEN
     * whenever there is something to scroll). The scrubber shows exactly when its TextView is
     * itself visible and the content does not fit; otherwise it is out of the way — and, being
     * hidden, takes no touches, so a finger where the bar would be reaches the text or the root.
     */
    fun visible(textViewVisible: Boolean, contentHeight: Int, viewHeight: Int): Boolean =
        textViewVisible && !fits(contentHeight, viewHeight)

    fun fraction(scrollY: Int, maxScroll: Int): Float =
        if (maxScroll <= 0) 0f else (scrollY.toFloat() / maxScroll).coerceIn(0f, 1f)

    /** Proportional thumb, never shorter than [minThumbPx] and never longer than the track. */
    fun thumbHeight(trackHeightPx: Float, viewHeight: Int, contentHeight: Int, minThumbPx: Float): Float {
        if (trackHeightPx <= 0f) return 0f
        val proportional = if (contentHeight <= 0) trackHeightPx
        else trackHeightPx * viewHeight.toFloat() / contentHeight
        return proportional.coerceAtLeast(minThumbPx).coerceAtMost(trackHeightPx)
    }

    fun thumbTop(trackHeightPx: Float, thumbHeightPx: Float, fraction: Float): Float =
        (trackHeightPx - thumbHeightPx).coerceAtLeast(0f) * fraction.coerceIn(0f, 1f)

    /** The inverse of [thumbTop]: where the thumb's top is, as a `scrollY`. */
    fun scrollYForThumbTop(thumbTopPx: Float, trackHeightPx: Float, thumbHeightPx: Float, maxScroll: Int): Int {
        val travel = trackHeightPx - thumbHeightPx
        if (travel <= 0f || maxScroll <= 0) return 0
        val fraction = (thumbTopPx / travel).coerceIn(0f, 1f)
        return (fraction * maxScroll).roundToInt()
    }

    /**
     * Where on the thumb the finger landed, so the thumb follows without jumping. A finger OFF
     * the thumb (on the track) centres the thumb under it — the bar's usual "tap to jump".
     */
    fun grabOffset(fingerY: Float, thumbTopPx: Float, thumbHeightPx: Float): Float =
        if (fingerY >= thumbTopPx && fingerY <= thumbTopPx + thumbHeightPx) fingerY - thumbTopPx
        else thumbHeightPx / 2f
}
