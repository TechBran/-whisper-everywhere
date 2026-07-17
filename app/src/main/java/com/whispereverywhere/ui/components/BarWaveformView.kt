package com.whispereverywhere.ui.components

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Modern continuous overlapping waveform (voice-assistant style).
 *
 * Draws smooth overlapping sine waves that modulate based on the microphone input.
 */
class BarWaveformView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val gradientColors = intArrayOf(
        Color.parseColor("#EF4444"), Color.parseColor("#EC4899"),
        Color.parseColor("#8B5CF6"), Color.parseColor("#3B82F6")
    )

    // Three overlapping waves
    private val paths = Array(3) { Path() }

    /** Latest smoothed mic level (0..1) */
    private var currentLevel = BASELINE
    private var targetLevel = BASELINE

    private var running = false
    private var ticker: ValueAnimator? = null
    private var phase = 0f

    private val density get() = resources.displayMetrics.density

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        wavePaint.shader = LinearGradient(0f, 0f, w.toFloat(), 0f, gradientColors, null, Shader.TileMode.CLAMP)
        wavePaint.strokeWidth = 3f * density
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        val cy = height / 2f
        val w = width.toFloat()

        // Slowly animate the current level towards the target level for smoothness
        currentLevel += (targetLevel - currentLevel) * SMOOTHING

        // Calculate a dynamic amplitude multiplier based on the audio level
        val amplitudeAmp = (height * 0.4f) * currentLevel

        // Stadium-interior envelope: this view spans the full pill, so the wave's allowed
        // excursion at each x is the pill's interior half-height there — full in the straight
        // middle, following the circular caps at the ends, pinching to ZERO exactly at the cap
        // apexes. The ribbon's tips therefore always terminate ON the bubble edge and no part
        // of any wave can cross the boundary curve.
        val capR = height / 2f
        val inset = wavePaint.strokeWidth  // keep the stroke's full width inside the edge

        for (i in paths.indices) {
            val path = paths[i]
            path.reset()

            // Different frequencies, phases, and opacity for each wave
            val frequency = 1.0f + (i * 0.5f)
            val phaseOffset = phase * (1.0f + i * 0.2f)

            // Outer waves are slightly smaller in amplitude
            val layerAmp = amplitudeAmp * (1f - i * 0.2f)

            wavePaint.alpha = (255 * (1f - i * 0.25f)).toInt()

            var started = false
            for (x in 0..width step 4) {
                val xf = x.toFloat()
                val progress = xf / w

                val halfH = when {
                    xf < capR -> sqrt((capR * capR - (capR - xf) * (capR - xf)).coerceAtLeast(0f))
                    xf > w - capR -> sqrt((capR * capR - (xf - (w - capR)) * (xf - (w - capR))).coerceAtLeast(0f))
                    else -> capR
                }
                val ampX = minOf(layerAmp, (halfH - inset).coerceAtLeast(0f))

                val y = cy + sin((progress * Math.PI * frequency) + phaseOffset).toFloat() * ampX

                if (!started) {
                    path.moveTo(xf, y)
                    started = true
                } else {
                    path.lineTo(xf, y)
                }
            }
            canvas.drawPath(path, wavePaint)
        }
    }

    /** Feed a live amplitude (0..32767). */
    fun updateAmplitude(amplitude: Int) {
        val gated = if (amplitude < NOISE_FLOOR) 0 else amplitude
        val raw = (gated / 32767f).coerceIn(0f, 1f)
        targetLevel = sqrt(raw * GAIN).coerceIn(0f, 1f).coerceAtLeast(BASELINE)
    }

    fun start() {
        running = true
        ticker?.cancel()
        
        // 60fps continuous animation
        ticker = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                if (!running) return@addUpdateListener
                // Advance the phase for the scrolling wave effect
                phase += 0.15f
                invalidate()
            }
            start()
        }
    }

    fun stop() {
        running = false
        ticker?.cancel()
        ticker = null
        targetLevel = BASELINE
        currentLevel = BASELINE
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stop()
    }

    companion object {
        /** Resting height of the wave when silent. */
        private const val BASELINE = 0.05f

        /** Loudness mapping: noise gate, gain, and perceptual curve (sqrt). */
        private const val NOISE_FLOOR = 350
        private const val GAIN = 4f

        /** Response smoothing toward the newest level. */
        private const val SMOOTHING = 0.2f
    }
}
