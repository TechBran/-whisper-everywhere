package com.whispereverywhere.ui.components

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * The FrameLayout around a transcript TextView and its [TranscriptScrubberView] (4.9.1). Its
 * one job: the scrubber is as tall as the TextView beside it, in the same measure pass.
 *
 * ### Why a plain FrameLayout cannot do this
 * A `wrap_content` frame measures each child once with the parent's AT_MOST spec and takes the
 * tallest; it re-measures `match_parent` children to that height only when there is MORE THAN
 * ONE of them (`FrameLayout.onMeasure`: `if (count > 1)`). Each frame here has exactly one
 * scrubber, so the framework's re-measure never runs, and a scrubber that answers AT_MOST with
 * "nothing" (the only safe answer — the whole available height would balloon the panel to the
 * screen) is laid out 0 tall: never drawn, never hit. And a scrubber that measured ITSELF to
 * the TextView would go stale: `View.measure` skips `onMeasure` when a child's spec is
 * unchanged, and the scrubber's spec is unchanged whatever the TextView did — a strip that
 * grew, shrank or went GONE would leave the scrubber at its old height (a GONE strip leaves a
 * five-line phantom). So the frame does the re-measure the framework declines to: after
 * `super.onMeasure` has measured every child, each scrubber is measured EXACTLY to
 * [TranscriptScrubberMath.scrubberHeight] of the TextView it is bound to, whose
 * `measuredHeight` is fresh from that same pass. Every change to the TextView reaches this
 * frame as a `requestLayout`, so the scrubber follows every one of them.
 *
 * The frame's own height is untouched: the scrubber's height plus its margin never exceeds
 * the TextView's plus its margin (the invariant in the maths), so the frame is exactly as tall
 * as its TextView (or, for the committed text, its resize handle) makes it — and a GONE strip
 * still collapses its frame to nothing.
 */
class TranscriptScrubberFrame @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        for (i in 0 until childCount) {
            val scrubber = getChildAt(i) as? TranscriptScrubberView ?: continue
            if (scrubber.visibility == GONE) continue
            val target = scrubber.target ?: continue
            val height = TranscriptScrubberMath.scrubberHeight(
                targetGone = target.visibility == GONE,
                targetMeasuredHeight = target.measuredHeight,
                targetTopMargin = TranscriptScrubberView.topMarginOf(target),
                ownTopMargin = TranscriptScrubberView.topMarginOf(scrubber),
            )
            scrubber.measure(
                MeasureSpec.makeMeasureSpec(scrubber.measuredWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
            )
        }
    }
}
