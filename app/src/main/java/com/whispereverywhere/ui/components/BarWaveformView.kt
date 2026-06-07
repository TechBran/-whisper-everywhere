package com.whispereverywhere.ui.components

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.sqrt

/**
 * Modern scrolling waveform (voice-memo / pro-recorder style).
 *
 * The live mic level enters as a new bar on the RIGHT and the whole trace
 * scrolls LEFT at a steady cadence, leaving a short visible history of what was
 * just said. Bars are mirrored around the vertical center and tinted with the
 * app's red→purple→blue gradient. Unlike the old version there is no idle
 * "shimmer" overriding the signal — what you see is your actual voice.
 *
 * Drive it with [updateAmplitude] (0..32767); call [start]/[stop] around use.
 * Feel is controlled entirely by the constants in the companion object.
 */
class BarWaveformView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val gradientColors = intArrayOf(
        Color.parseColor("#EF4444"), Color.parseColor("#EC4899"),
        Color.parseColor("#8B5CF6"), Color.parseColor("#3B82F6")
    )

    private var barWidthPx = 0f
    private var gapPx = 0f
    private var slotPx = 0f
    private var radiusPx = 0f

    /** Normalized levels (0..1); index 0 = oldest (left edge), last = newest (right edge). */
    private var levels = FloatArray(0)

    /** Latest smoothed mic level; pushed into the trace each scroll frame. */
    private var currentLevel = BASELINE

    private var running = false
    private var ticker: ValueAnimator? = null
    private var lastPushMs = 0L

    private val density get() = resources.displayMetrics.density

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        barWidthPx = BAR_WIDTH_DP * density
        gapPx = GAP_DP * density
        slotPx = barWidthPx + gapPx
        radiusPx = barWidthPx / 2f
        val count = (w / slotPx).toInt().coerceAtLeast(1)
        levels = FloatArray(count) { BASELINE }
        paint.shader = LinearGradient(0f, 0f, w.toFloat(), 0f, gradientColors, null, Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cy = height / 2f
        val maxH = height * 0.92f
        for (i in levels.indices) {
            val barH = (maxH * levels[i]).coerceAtLeast(barWidthPx) // never thinner than a dot
            val left = i * slotPx
            canvas.drawRoundRect(left, cy - barH / 2f, left + barWidthPx, cy + barH / 2f, radiusPx, radiusPx, paint)
        }
    }

    /** Feed a live amplitude (0..32767). Updates the newest level; scrolling is time-driven. */
    fun updateAmplitude(amplitude: Int) {
        val gated = if (amplitude < NOISE_FLOOR) 0 else amplitude
        val raw = (gated / 32767f).coerceIn(0f, 1f)
        val level = sqrt(raw * GAIN).coerceIn(0f, 1f)
        currentLevel += (level - currentLevel) * SMOOTHING
    }

    private fun pushLevel(level: Float) {
        if (levels.isEmpty()) return
        System.arraycopy(levels, 1, levels, 0, levels.size - 1)
        levels[levels.size - 1] = level.coerceAtLeast(BASELINE)
        invalidate()
    }

    fun start() {
        running = true
        lastPushMs = 0L
        ticker?.cancel()
        // Repeats at vsync; we throttle pushes to PUSH_INTERVAL_MS for a controlled scroll speed.
        ticker = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                if (!running) return@addUpdateListener
                val now = System.currentTimeMillis()
                if (now - lastPushMs >= PUSH_INTERVAL_MS) {
                    lastPushMs = now
                    pushLevel(currentLevel)
                }
            }
            start()
        }
    }

    fun stop() {
        running = false
        ticker?.cancel()
        ticker = null
        currentLevel = BASELINE
        if (levels.isNotEmpty()) levels.fill(BASELINE)
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stop()
    }

    companion object {
        /** Bar geometry. */
        private const val BAR_WIDTH_DP = 3f
        private const val GAP_DP = 2f

        /** Resting height of a bar when silent (fraction of view height). */
        private const val BASELINE = 0.05f

        /** Loudness mapping: noise gate, gain, and perceptual curve (sqrt). Tune these for feel. */
        private const val NOISE_FLOOR = 350      // amplitudes below this read as silence
        private const val GAIN = 4f              // higher = bars react to quieter speech

        /** Response smoothing toward the newest level (0..1; higher = snappier). */
        private const val SMOOTHING = 0.5f

        /** Scroll speed: one new bar every this many ms (~22 bars/sec). */
        private const val PUSH_INTERVAL_MS = 45L
    }
}
