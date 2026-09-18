package com.whispereverywhere.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged

/**
 * A scrollbar you can GRAB, for the transcript window's two TextViews (4.9.1, owner ruling
 * 2026-09-17 on the tablet: *"if we touch the slider, we should be able to slide it up and down
 * — and not only scroll by sliding the whole text, which is fine as well"*).
 *
 * The framework's own scrollbar on a TextView is a drawing, not a control: it mirrors the scroll
 * but takes no touches. This view mirrors a bound TextView's scroll the same way — a 4dp thumb on
 * a 4dp track, the 4.8.0 colours — and additionally maps a finger to `scrollTo`. It is the ONLY
 * scrollbar the two views have now (their `android:scrollbars` is `none`).
 *
 * ### Following, not fighting
 * The scrubber never scrolls the TextView unless a finger is DOWN on it. The service auto-scrolls
 * the committed text to its newest line on every commit and the live strip to its newest words
 * on every delta; those land as `scrollTo` calls, which fire the TextView's scroll-change
 * listener, which re-syncs the thumb. A finger-scroll on the text itself (the committed view's
 * `ScrollingMovementMethod`) reaches this view the same way. Sync is also driven by layout
 * changes (the strip grows to `maxLines`), by text changes (a fixed-height view re-lays out its
 * text without a bounds change, so the layout listener alone would miss it) and by this view's
 * own size changes (the wrapper re-lays out when its TextView is hidden).
 *
 * ### Owning its gesture
 * ACTION_DOWN returns `true` and asks the parent not to intercept, so the root bubble's
 * drag / long-press-to-pin listener never sees a scrub — the same shape the resize handle and
 * the TTS pill's [TtsScrubberView] use. When the content fits, the view is INVISIBLE: it keeps
 * its slot in the layout (no re-measure of the panel) but receives no pointer events, so a
 * finger there reaches whatever is under it. It is never GONE — its height, not its
 * visibility, is what keeps a hidden strip's frame collapsed (see [onMeasure]).
 *
 * ### Its height is its TextView's
 * This view sits in a [TranscriptScrubberFrame], which measures it EXACTLY to the bound
 * TextView's height every pass ([TranscriptScrubberMath.scrubberHeight]); on its own it
 * wants nothing. The bar is drawn from [trackTop] — the TextView's top when that lies below
 * this view's top (the live strip's 4dp margin, which its scrubber does not carry so that a
 * GONE strip leaves no 4dp of phantom frame) — to the bottom, which both views share.
 *
 * The geometry is [TranscriptScrubberMath]; this class is the View around it.
 */
class TranscriptScrubberView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** The TextView this view mirrors; [TranscriptScrubberFrame] measures this view to it. */
    var target: TextView? = null
        private set

    // The mirrored model, refreshed by sync().
    private var contentHeight = 0
    private var viewHeight = 0
    private var targetScrollY = 0

    private var dragging = false
    private var grabOffset = 0f

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TRACK_COLOUR }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = THUMB_COLOUR }

    /** Mirror [textView]; call once, from the view that owns both. */
    fun bind(textView: TextView) {
        target = textView
        textView.setOnScrollChangeListener { _, _, _, _, _ -> sync() }
        textView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> sync() }
        textView.doAfterTextChanged {
            // A fixed-size TextView rebuilds its layout inside setText with no bounds change,
            // so the layout is fresh here; the post covers the wrap_content strip, whose new
            // height lands on the next layout pass.
            sync()
            post { sync() }
        }
        sync()
    }

    /** Re-read the TextView's scroll model and show or hide accordingly. */
    fun sync() {
        val tv = target ?: return
        val layout = tv.layout
        contentHeight = if (layout == null) 0
        else TranscriptScrubberMath.contentHeight(layout.height, tv.paddingTop, tv.paddingBottom)
        viewHeight = tv.height
        targetScrollY = tv.scrollY
        val show = TranscriptScrubberMath.visible(
            textViewVisible = tv.visibility == VISIBLE,
            contentHeight = contentHeight,
            viewHeight = viewHeight,
        )
        visibility = if (show) VISIBLE else INVISIBLE
        if (show) invalidate()
    }

    /**
     * Wants NOTHING of its own; its height is given. The frame measures every child once with
     * the parent's AT_MOST spec, and a plain View answers AT_MOST with the whole available
     * height — which would balloon the panel to the screen — so this view answers 0 unless the
     * spec is EXACT. The EXACT spec comes from [TranscriptScrubberFrame], which measures this
     * view a second time, to [TranscriptScrubberMath.scrubberHeight] of the TextView it just
     * measured. (A plain FrameLayout would never do that for a lone child: its match-parent
     * re-measure runs only for two or more, which is why the frame is a subclass.)
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize(suggestedMinimumHeight, heightMeasureSpec),
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        sync()
    }

    private val maxScroll: Int get() = TranscriptScrubberMath.maxScroll(contentHeight, viewHeight)

    /** Where the track starts: the TextView's top, when it lies below this view's own. */
    private val trackTop: Float
        get() = TranscriptScrubberMath.trackTop(target?.let { topMarginOf(it) } ?: 0, topMarginOf(this)).toFloat()
    private val trackHeight: Float get() = (height - trackTop).coerceAtLeast(0f)
    private val thumbHeight: Float
        get() = TranscriptScrubberMath.thumbHeight(trackHeight, viewHeight, contentHeight, dp(MIN_THUMB_DP))
    private val thumbTop: Float
        get() = TranscriptScrubberMath.thumbTop(
            trackHeight, thumbHeight, TranscriptScrubberMath.fraction(targetScrollY, maxScroll),
        )

    override fun onDraw(canvas: Canvas) {
        if (TranscriptScrubberMath.fits(contentHeight, viewHeight) || trackHeight <= 0f) return
        val barW = dp(BAR_WIDTH_DP)
        val right = width - dp(BAR_INSET_DP)
        val left = right - barW
        val r = barW / 2f
        val trackY = trackTop
        canvas.drawRoundRect(left, trackY, right, trackY + trackHeight, r, r, trackPaint)
        val top = trackY + thumbTop
        canvas.drawRoundRect(left, top, right, top + thumbHeight, r, r, thumbPaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (target == null || TranscriptScrubberMath.fits(contentHeight, viewHeight)) return false
                // Claim the gesture: the bubble's drag / long-press-to-pin never sees a scrub.
                parent?.requestDisallowInterceptTouchEvent(true)
                dragging = true
                val y = event.y - trackTop
                grabOffset = TranscriptScrubberMath.grabOffset(y, thumbTop, thumbHeight)
                scrollToFinger(y)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return false
                scrollToFinger(event.y - trackTop)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!dragging) return false
                dragging = false
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /** The one place this view scrolls its TextView — and only while [dragging]. [fingerY] is
     *  track-relative (the finger's y less [trackTop]). */
    private fun scrollToFinger(fingerY: Float) {
        val tv = target ?: return
        if (!dragging) return
        val y = TranscriptScrubberMath.scrollYForThumbTop(
            thumbTopPx = fingerY - grabOffset,
            trackHeightPx = trackHeight,
            thumbHeightPx = thumbHeight,
            maxScroll = maxScroll,
        )
        if (y != tv.scrollY) tv.scrollTo(0, y) else invalidate()
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    companion object {
        /** The 4.8.0 bar, unchanged: a 70% white thumb on a 20% white track — furniture, not
         *  content, so neither follows the user's BubbleColours. */
        const val THUMB_COLOUR: Int = 0xB3FFFFFF.toInt()
        const val TRACK_COLOUR: Int = 0x33FFFFFF.toInt()
        const val BAR_WIDTH_DP = 4f
        const val BAR_INSET_DP = 1f
        const val MIN_THUMB_DP = 24f

        /** A child's top margin in its frame — the one geometry fact the frame and the view
         *  share (the TextView's bottom is its margin plus its height). 0 when unset. */
        fun topMarginOf(view: View): Int = (view.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin ?: 0
    }
}
