package com.whispereverywhere.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Thin drag-to-seek line for the TTS pill (user design 2026-07-18): a subtle track with a
 * played-portion fill and a small thumb. The right edge represents the SYNTHESIZED frontier —
 * while synthesis is still running the track ends in a lighter "growing" segment, so the bar
 * itself communicates "more audio is coming".
 *
 * Threading: setProgress is called from any thread (fields + postInvalidate); touch runs on
 * the UI thread and reports a 0..1 fraction of the synthesized audio via [onSeek].
 */
class TtsScrubberView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Fraction (0..1) of SYNTHESIZED audio to jump to — already re-based from the bar by [ScrubberMath]. */
    var onSeek: ((Float) -> Unit)? = null

    @Volatile private var playedFrac = 0f      // of the bar
    @Volatile private var readyFrac = 1f       // of the bar: synthesized so far
    @Volatile private var synthesisDone = true
    @Volatile private var lastAvailable = 0L
    @Volatile private var lastSpan = 0L
    private var dragging = false
    private var dragFrac = 0f

    // Palette (user direction 2026-07-18): played = red; audio ahead that's READY = gray;
    // the frontier still being generated = white; position = a red vertical line + white dot.
    private val readyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x889E9E9E.toInt() // gray: synthesized, waiting ahead
        strokeWidth = dp(2f)
        strokeCap = Paint.Cap.ROUND
    }
    private val growPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCCFFFFFF.toInt() // white: being generated right now
        strokeWidth = dp(2f)
        strokeCap = Paint.Cap.ROUND
    }
    private val playedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF3B30.toInt() // red: what you've heard
        strokeWidth = dp(2f)
        strokeCap = Paint.Cap.ROUND
    }
    private val positionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF3B30.toInt() // the red vertical you-are-here line
        strokeWidth = dp(2f)
        strokeCap = Paint.Cap.ROUND
    }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
    }

    /**
     * [played]/[available] in samples as before; [estimatedTotal] is the projected end of the read
     * (== [available] once [done]). The bar spans the read; gray is what is ready ahead of you,
     * white is what is still being generated (4.3.1 C — the wait is visible, not silent).
     */
    fun setProgress(played: Long, available: Long, estimatedTotal: Long, done: Boolean) {
        val span = ScrubberMath.span(available, estimatedTotal)
        playedFrac = ScrubberMath.frac(played, span)
        readyFrac = ScrubberMath.frac(available, span)
        synthesisDone = done
        lastAvailable = available
        lastSpan = span
        if (!dragging) postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val pad = dp(8f)
        val y = height / 2f
        val left = pad
        val right = width - pad
        if (right <= left) return
        val frac = if (dragging) dragFrac else playedFrac
        val x = left + (right - left) * frac.coerceIn(0f, 1f)
        // Ahead of you: gray where audio is ready; white from the synthesized frontier to the
        // estimated end while generation continues (falls back to the old 10 dp tail when the
        // estimate has no room left).
        val readyX = left + (right - left) * readyFrac.coerceIn(0f, 1f)
        if (readyX > x) canvas.drawLine(x, y, readyX, y, readyPaint)
        if (!synthesisDone) {
            val tailStart = if (readyX < right - dp(10f)) readyX else right - dp(10f)
            canvas.drawLine(tailStart, y, right, y, growPaint)
        }
        // Behind you: red.
        if (x > left) canvas.drawLine(left, y, x, y, playedPaint)
        // You are here: red vertical line with the white dot riding it.
        val tick = dp(if (dragging) 7f else 5f)
        canvas.drawLine(x, y - tick, x, y + tick, positionPaint)
        canvas.drawCircle(x, y, dp(if (dragging) 5f else 3.5f), thumbPaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val pad = dp(8f)
        val span = (width - 2 * pad).coerceAtLeast(1f)
        val frac = ((event.x - pad) / span).coerceIn(0f, 1f)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Claim the gesture so the bubble's drag handling never fights the scrub.
                parent?.requestDisallowInterceptTouchEvent(true)
                dragging = true
                dragFrac = frac
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                dragFrac = frac
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                dragging = false
                onSeek?.invoke(ScrubberMath.seekFracOfSynthesized(frac, lastAvailable, lastSpan))
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}
