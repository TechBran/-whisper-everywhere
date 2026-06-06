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
import kotlin.math.abs
import kotlin.math.sin

/**
 * Reactive EQ waveform: ~15 bars with spring-style settling, red→purple→blue
 * gradient, idle shimmer when quiet and sharp jumps on amplitude peaks.
 * Drive it with [updateAmplitude] (0..32767); call [start]/[stop] around use.
 */
class BarWaveformView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val barCount = 15
    private val heights = FloatArray(barCount) { 0.1f }
    private val targets = FloatArray(barCount) { 0.1f }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val gradientColors = intArrayOf(
        Color.parseColor("#EF4444"), Color.parseColor("#EC4899"),
        Color.parseColor("#8B5CF6"), Color.parseColor("#3B82F6")
    )

    private var barWidth = 0f
    private var gap = 0f
    private var maxH = 0f
    private var minH = 0f
    private var radius = 0f

    private var phase = 0f
    private var animating = false
    private var ticker: ValueAnimator? = null
    private val spring = 0.4f

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        gap = (w * 0.18f) / (barCount + 1)
        barWidth = (w - gap * (barCount + 1)) / barCount
        maxH = h * 0.9f
        minH = h * 0.1f
        radius = barWidth / 2
        paint.shader = LinearGradient(0f, 0f, w.toFloat(), 0f, gradientColors, null, Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cy = height / 2f
        for (i in 0 until barCount) {
            heights[i] += (targets[i] - heights[i]) * spring
            val bh = minH + (maxH - minH) * heights[i]
            val left = gap + i * (barWidth + gap)
            canvas.drawRoundRect(left, cy - bh / 2, left + barWidth, cy + bh / 2, radius, radius, paint)
        }
        if (animating) invalidate()
    }

    fun updateAmplitude(amplitude: Int) {
        val norm = ((amplitude / 32767f) * 1.6f).coerceIn(0f, 1f)
        for (i in 0 until barCount) {
            val center = 1f - (abs(i - barCount / 2f) / (barCount / 2f)) * 0.35f
            val wiggle = sin((phase + i * 0.5f).toDouble()).toFloat() * 0.12f
            targets[i] = (norm * center + wiggle).coerceIn(0.06f, 1f)
        }
        phase += 0.25f
        invalidate()
    }

    fun start() {
        animating = true
        ticker?.cancel()
        ticker = ValueAnimator.ofFloat(0f, (Math.PI * 2).toFloat()).apply {
            duration = 1600
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                phase = it.animatedValue as Float
                for (i in 0 until barCount) {
                    if (targets[i] < 0.14f) {
                        val center = 1f - (abs(i - barCount / 2f) / (barCount / 2f)) * 0.4f
                        targets[i] = (0.1f + 0.07f * center *
                            sin((phase + i * 0.6f).toDouble()).toFloat()).coerceAtLeast(0.06f)
                    }
                }
                invalidate()
            }
            start()
        }
    }

    fun stop() {
        animating = false
        ticker?.cancel()
        ticker = null
        for (i in 0 until barCount) targets[i] = 0.06f
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stop()
    }
}
