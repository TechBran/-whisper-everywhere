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

    /**
     * THE SCRUBBER'S HEIGHT, decided by [TranscriptScrubberFrame] after it has measured the
     * TextView: the scrubber's bottom meets the TextView's bottom. Both sit `top` in the frame,
     * each below its own top margin, so the TextView's bottom is `targetTopMargin +
     * targetMeasuredHeight` and the scrubber starts at `ownTopMargin`. A GONE TextView is not
     * measured (its measuredHeight is stale) and has no bottom to meet: the scrubber is 0.
     *
     * The invariant the frame's height rests on: `scrubberHeight + ownTopMargin` never exceeds
     * `targetTopMargin + targetMeasuredHeight`, so the scrubber never makes the frame taller
     * than its TextView does — with a 0 own margin (the live strip's scrubber) a GONE TextView
     * collapses the frame to nothing, exactly as the strip alone did before it had a scrubber.
     */
    fun scrubberHeight(targetGone: Boolean, targetMeasuredHeight: Int, targetTopMargin: Int, ownTopMargin: Int): Int =
        if (targetGone) 0 else (targetTopMargin + targetMeasuredHeight - ownTopMargin).coerceAtLeast(0)

    /**
     * Where the track starts inside the scrubber: at the TextView's top when the TextView
     * starts below the scrubber's top (the live strip's 4dp margin, which its scrubber does not
     * carry), else at 0 (the committed scrubber starts 28dp BELOW its text, under the handle).
     */
    fun trackTop(targetTopMargin: Int, ownTopMargin: Int): Int =
        (targetTopMargin - ownTopMargin).coerceAtLeast(0)

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

    // ------------------------------------------------------------------ following, not yanking

    /**
     * IS THE READER RIDING THE NEWEST LINE? — read against the content as it stood BEFORE the
     * repaint, which is the only moment at which the question has an answer.
     *
     * [thresholdPx] is slack, not sloppiness: a view can sit a pixel or two off its own maximum
     * after a layout pass, and a reader who is one partial line from the bottom means "keep
     * going" as plainly as one who is exactly on it. Content that fits (`maxScroll == 0`) is at
     * the bottom by definition.
     */
    fun atBottom(scrollY: Int, maxScroll: Int, thresholdPx: Int): Boolean =
        scrollY >= maxScroll - thresholdPx.coerceAtLeast(0)

    /**
     * WHERE THE PANEL SITS AFTER A REPAINT — the one rule behind the owner's 2026-09-19 report
     * that scrolling back up did not stay put.
     *
     * The panel is re-assigned its whole text on every repaint, and since the retrospective
     * reclusterer those repaints also happen when no new words arrived (a relabel every few
     * chunks, and one at stop). Scrolling to the newest line unconditionally therefore yanked a
     * reader — or a dragged scrubber — back to the bottom for a change they could not even see.
     *
     * So: follow the newest line only for a reader who was already there ([wasAtBottom]); anyone
     * else keeps the offset they chose, clamped to whatever the content is now.
     *
     * What a length-preserving relabel does to the view therefore depends on which side of
     * [atBottom]'s slack the reader sits. Exactly on `maxScroll`, or anywhere above the slack,
     * both branches return where the view already was and nothing moves — which is the case the
     * owner reported. INSIDE the slack it does move: a reader 1..(slack-1) px short of the bottom
     * is [wasAtBottom], so this returns `maxScroll` and those last pixels are taken up. With
     * `PANEL_FOLLOW_SLACK_DP = 24dp` (72px at density 3) against the panel's 14sp line (~17dp)
     * that is up to about a line and a half of travel. It is the slack's intent rather than a
     * leak — a reader that close to the end is still riding it, and the snap is toward the
     * newest word, never away from what they were reading.
     */
    fun followScrollY(wasAtBottom: Boolean, previousScrollY: Int, maxScroll: Int): Int =
        if (wasAtBottom) maxScroll else previousScrollY.coerceIn(0, maxScroll.coerceAtLeast(0))
}
