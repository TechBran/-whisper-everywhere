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

    /** Fraction (0..1) of synthesized audio to jump to. Set by the owner. */
    var onSeek: ((Float) -> Unit)? = null

    @Volatile private var playedFrac = 0f      // of synthesized audio
    @Volatile private var synthesisDone = true
    private var dragging = false
    private var dragFrac = 0f

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x4DFFFFFF // faint white track
        strokeWidth = dp(2f)
        strokeCap = Paint.Cap.ROUND
    }
    private val growPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x33FFFFFF // even fainter: synthesis still extending the audio
        strokeWidth = dp(2f)
        strokeCap = Paint.Cap.ROUND
    }
    private val playedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF4FC3F7.toInt() // matches the speaker lobe accent
        strokeWidth = dp(2f)
        strokeCap = Paint.Cap.ROUND
    }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
    }

    fun setProgress(played: Long, available: Long, done: Boolean) {
        playedFrac = if (available > 0) (played.toDouble() / available).toFloat() else 0f
        synthesisDone = done
        if (!dragging) postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val pad = dp(8f)
        val y = height / 2f
        val left = pad
        val right = width - pad
        if (right <= left) return
        // Full track: solid where audio exists; a short faint tail hints "still growing".
        canvas.drawLine(left, y, right, y, trackPaint)
        if (!synthesisDone) {
            canvas.drawLine(right - dp(10f), y, right, y, growPaint)
        }
        val frac = if (dragging) dragFrac else playedFrac
        val x = left + (right - left) * frac.coerceIn(0f, 1f)
        canvas.drawLine(left, y, x, y, playedPaint)
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
                onSeek?.invoke(frac)
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
