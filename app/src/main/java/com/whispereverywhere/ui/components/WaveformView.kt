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
import kotlin.math.sin
import kotlin.random.Random

/**
 * Custom View that displays a real-time audio waveform visualization
 * with a gradient matching the app logo (red to blue).
 */
class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // Number of bars in the waveform
    private val barCount = 7

    // Current amplitude values for each bar (0.0 to 1.0)
    private val barHeights = FloatArray(barCount) { 0.2f }
    private val targetHeights = FloatArray(barCount) { 0.2f }

    // Paint for drawing bars with gradient
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    // Paint for glow effect
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        alpha = 60
    }

    // Animation
    private var phaseAnimator: ValueAnimator? = null
    private var phase = 0f

    // Bar dimensions
    private var barWidth = 0f
    private var barSpacing = 0f
    private var maxBarHeight = 0f
    private var minBarHeight = 0f
    private var cornerRadius = 0f

    // Gradient colors matching logo
    private val gradientColors = intArrayOf(
        Color.parseColor("#EF4444"),  // Red
        Color.parseColor("#EC4899"),  // Pink
        Color.parseColor("#8B5CF6"),  // Purple
        Color.parseColor("#3B82F6")   // Blue
    )

    // Is currently animating
    private var isAnimating = false

    // Smoothing factor for amplitude changes
    private val smoothingFactor = 0.35f

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)

        // Calculate bar dimensions based on view size
        val totalSpacing = w * 0.25f
        barSpacing = totalSpacing / (barCount + 1)
        barWidth = (w - totalSpacing) / barCount

        maxBarHeight = h * 0.85f
        minBarHeight = h * 0.12f
        cornerRadius = barWidth / 2

        // Create horizontal gradient shader (red to blue, left to right)
        barPaint.shader = LinearGradient(
            0f, 0f, w.toFloat(), 0f,
            gradientColors,
            null,
            Shader.TileMode.CLAMP
        )

        // Glow gradient
        glowPaint.shader = LinearGradient(
            0f, 0f, w.toFloat(), 0f,
            gradientColors,
            null,
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val centerY = height / 2f

        for (i in 0 until barCount) {
            // Smooth transition to target height
            barHeights[i] += (targetHeights[i] - barHeights[i]) * smoothingFactor

            val barHeight = minBarHeight + (maxBarHeight - minBarHeight) * barHeights[i]
            val left = barSpacing + i * (barWidth + barSpacing)
            val top = centerY - barHeight / 2
            val right = left + barWidth
            val bottom = centerY + barHeight / 2

            // Draw glow effect
            glowPaint.alpha = (40 + barHeights[i] * 40).toInt()
            canvas.drawRoundRect(
                left - 3, top - 3, right + 3, bottom + 3,
                cornerRadius + 3, cornerRadius + 3,
                glowPaint
            )

            // Draw bar
            canvas.drawRoundRect(
                left, top, right, bottom,
                cornerRadius, cornerRadius,
                barPaint
            )
        }

        // Continue animation if active
        if (isAnimating) {
            invalidate()
        }
    }

    /**
     * Update the waveform with a new amplitude value (0-32767 range from AudioRecord)
     */
    fun updateAmplitude(amplitude: Int) {
        // Normalize amplitude to 0.0 - 1.0 range with some boost for visibility
        val normalizedAmplitude = ((amplitude / 32767f) * 1.5f).coerceIn(0f, 1f)

        // Update each bar with slightly different values for visual interest
        for (i in 0 until barCount) {
            // Add some variation based on bar position and phase
            val variation = sin((phase + i * 0.6f).toDouble()).toFloat() * 0.15f
            val randomFactor = 0.85f + Random.nextFloat() * 0.3f

            // Center bars are taller (like the logo waveform)
            val centerBoost = 1f - (kotlin.math.abs(i - barCount / 2f) / (barCount / 2f)) * 0.3f

            targetHeights[i] = (normalizedAmplitude * randomFactor * centerBoost + variation)
                .coerceIn(0.08f, 1f)
        }

        // Update phase for continuous animation
        phase += 0.15f
        if (phase > Math.PI.toFloat() * 2) {
            phase = 0f
        }

        invalidate()
    }

    /**
     * Start the waveform animation
     */
    fun startAnimation() {
        isAnimating = true

        // Start phase animator for smooth idle animation
        phaseAnimator?.cancel()
        phaseAnimator = ValueAnimator.ofFloat(0f, (Math.PI * 2).toFloat()).apply {
            duration = 2000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { animator ->
                phase = animator.animatedValue as Float

                // If no amplitude updates, show idle animation
                for (i in 0 until barCount) {
                    // Center bars are taller even in idle
                    val centerBoost = 1f - (kotlin.math.abs(i - barCount / 2f) / (barCount / 2f)) * 0.4f
                    val idleHeight = 0.12f + 0.08f * centerBoost * sin((phase + i * 0.7f).toDouble()).toFloat()
                    if (targetHeights[i] < 0.15f) {
                        targetHeights[i] = idleHeight.coerceAtLeast(0.08f)
                    }
                }
                invalidate()
            }
            start()
        }

        invalidate()
    }

    /**
     * Stop the waveform animation
     */
    fun stopAnimation() {
        isAnimating = false
        phaseAnimator?.cancel()
        phaseAnimator = null

        // Reset bars to minimum
        for (i in 0 until barCount) {
            targetHeights[i] = 0.08f
        }
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopAnimation()
    }
}
