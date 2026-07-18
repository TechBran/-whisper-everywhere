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
 * Aurora waveform (design ref: docs/design/bubble2-ribbon-reference.png).
 *
 * Layered translucent wave SHEETS — silk-like filled bodies, not stroked lines — sharing one
 * red -> magenta -> purple -> blue -> cyan gradient across the pill, each sheet drawn with a
 * soft fake-bloom pass (wide low-alpha stroke) plus a bright crest line. The whole composition
 * stays inside the pill's stadium interior: the same cap-geometry envelope as before bounds
 * every sheet, so nothing can cross the bubble's rim.
 *
 * Audio response is asymmetric: FAST attack (a syllable kicks the waves within one chunk),
 * slow release — the "spikes jump with the audio" feel from the reference.
 */
class BarWaveformView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val sheetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val crestPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val bloomPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val gradientColors = intArrayOf(
        Color.parseColor("#FF1E1E"), // vivid red — the left side owns red
        Color.parseColor("#EF4444"), // red (second stop widens the red region)
        Color.parseColor("#EC4899"), // magenta
        Color.parseColor("#8B5CF6"), // purple
        Color.parseColor("#2563EB"), // blue
        Color.parseColor("#1E3A8A"), // navy — the right side lands deep
    )

    private val sheetPath = Path()
    private val crestPath = Path()

    /** Latest smoothed mic level (0..1) */
    private var currentLevel = BASELINE
    private var targetLevel = BASELINE

    // Per-band drive (one band per sheet): [low, mid-low, mid-high, high]. Each sheet follows
    // its own slice of the spectrum so the strands move INDEPENDENTLY with the voice.
    private val bandTargets = FloatArray(SHEETS)
    private val bandLevels = FloatArray(SHEETS) { BASELINE }
    @Volatile private var bandsActive = false

    private var running = false
    private var ticker: ValueAnimator? = null
    private var phase = 0f

    // Scratch buffers for the per-frame ribbon silhouette published to RibbonProfile (the
    // blob's rim follows it — mountains inside become mountains outside).
    private val profileTop = FloatArray(RibbonProfile.N)
    private val profileBottom = FloatArray(RibbonProfile.N)

    private val density get() = resources.displayMetrics.density

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        val grad = LinearGradient(0f, 0f, w.toFloat(), 0f, gradientColors, null, Shader.TileMode.CLAMP)
        sheetPaint.shader = grad
        crestPaint.shader = grad
        bloomPaint.shader = grad
        crestPaint.strokeWidth = 2f * density
        bloomPaint.strokeWidth = 9f * density
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        val cy = height / 2f
        val w = width.toFloat()

        // Asymmetric envelopes: jump up fast, drift down slow — waves visibly hit on syllables.
        val k = if (targetLevel > currentLevel) ATTACK else RELEASE
        currentLevel += (targetLevel - currentLevel) * k
        for (j in 0 until SHEETS) {
            val kb = if (bandTargets[j] > bandLevels[j]) ATTACK else BAND_RELEASES[j]
            bandLevels[j] += (bandTargets[j] - bandLevels[j]) * kb
        }

        // Stadium-interior envelope (same as the pill body): allowed excursion at each x is the
        // interior half-height there, pinching to zero at the cap apexes — the sheets' tips
        // terminate on the bubble edge and can never cross it.
        val capR = height / 2f
        val inset = 3f * density

        java.util.Arrays.fill(profileTop, 0f)
        java.util.Arrays.fill(profileBottom, 0f)

        for (layer in 0 until SHEETS) {
            val frequency = 1.15f + layer * 0.55f
            val phaseOffset = phase * (0.8f + layer * 0.35f)
            // Each sheet rides its own frequency band; global level is the fallback until
            // band data arrives.
            val drive = if (bandsActive) {
                (bandLevels[layer].coerceAtLeast(BASELINE))
            } else currentLevel
            val layerAmp = (height * 0.46f) * drive * (1f - layer * 0.10f)
            // Each sheet drifts vertically a little so the layers weave through each other.
            val drift = (layer - (SHEETS - 1) / 2f) * height * 0.06f *
                (1f + 0.5f * sin(phase * 0.35f + layer))

            sheetPath.reset()
            crestPath.reset()
            var started = false

            // Top crest of the sheet.
            for (x in 0..width step 4) {
                val xf = x.toFloat()
                val progress = xf / w
                val halfH = stadiumHalfHeight(xf, w, capR)
                val ampX = minOf(layerAmp, (halfH - inset).coerceAtLeast(0f))
                // Clamp bound must stay non-inverted where halfH < inset (the cap apexes) —
                // coerceIn THROWS on an empty range.
                val boundTop = (halfH - inset).coerceAtLeast(0f)
                val yTop = (cy + drift + sin((progress * Math.PI * frequency) + phaseOffset).toFloat() * ampX)
                    .coerceIn(cy - boundTop, cy + boundTop)
                // Silhouette: how far this sheet's crest reaches ABOVE center, 0..1 of the
                // local bound — the max across sheets is the mountain the blob's top follows.
                if (boundTop > 1f && yTop < cy) {
                    val station = (progress * (RibbonProfile.N - 1)).toInt()
                        .coerceIn(0, RibbonProfile.N - 1)
                    val up = ((cy - yTop) / boundTop).coerceIn(0f, 1f)
                    if (up > profileTop[station]) profileTop[station] = up
                }
                if (!started) {
                    sheetPath.moveTo(xf, yTop); crestPath.moveTo(xf, yTop); started = true
                } else {
                    sheetPath.lineTo(xf, yTop); crestPath.lineTo(xf, yTop)
                }
            }
            // Bottom edge of the sheet: a second, phase-shifted wave — closing the two curves
            // makes a rippling silk body whose thickness itself undulates ("3D" depth).
            for (x in width downTo 0 step 4) {
                val xf = x.toFloat()
                val progress = xf / w
                val halfH = stadiumHalfHeight(xf, w, capR)
                val ampX = minOf(layerAmp * 0.7f, (halfH - inset).coerceAtLeast(0f))
                val boundBot = (halfH - inset).coerceAtLeast(0f)
                val yBot = (cy + drift + height * 0.055f +
                    sin((progress * Math.PI * (frequency + 0.4f)) + phaseOffset + 1.9f).toFloat() * ampX)
                    .coerceIn(cy - boundBot, cy + boundBot)
                if (boundBot > 1f && yBot > cy) {
                    val station = (progress * (RibbonProfile.N - 1)).toInt()
                        .coerceIn(0, RibbonProfile.N - 1)
                    val down = ((yBot - cy) / boundBot).coerceIn(0f, 1f)
                    if (down > profileBottom[station]) profileBottom[station] = down
                }
                sheetPath.lineTo(xf, yBot)
            }
            sheetPath.close()

            // Depth stack: translucent body, wide soft bloom, bright crest.
            sheetPaint.alpha = (70 * (1f - layer * 0.18f)).toInt()
            canvas.drawPath(sheetPath, sheetPaint)
            bloomPaint.alpha = (34 * (1f - layer * 0.15f)).toInt()
            canvas.drawPath(crestPath, bloomPaint)
            crestPaint.alpha = (200 * (1f - layer * 0.2f)).toInt()
            canvas.drawPath(crestPath, crestPaint)
        }

        // Hand the frame's silhouette to the blob (only while live — stale frames go unused).
        if (running) RibbonProfile.publish(profileTop, profileBottom)
    }

    private fun stadiumHalfHeight(x: Float, w: Float, capR: Float): Float = when {
        x < capR -> sqrt((capR * capR - (capR - x) * (capR - x)).coerceAtLeast(0f))
        x > w - capR -> sqrt((capR * capR - (x - (w - capR)) * (x - (w - capR))).coerceAtLeast(0f))
        else -> capR
    }

    /** Feed a live amplitude (0..32767). */
    fun updateAmplitude(amplitude: Int) {
        val gated = if (amplitude < NOISE_FLOOR) 0 else amplitude
        val raw = (gated / 32767f).coerceIn(0f, 1f)
        targetLevel = sqrt(raw * GAIN).coerceIn(0f, 1f).coerceAtLeast(BASELINE)
    }

    /** Feed per-frame band energies [low, mid-low, mid-high, high], each 0..1. */
    fun updateBands(bands: FloatArray) {
        if (bands.size < SHEETS) return
        bandsActive = true
        for (j in 0 until SHEETS) {
            // Perceptual lift so quiet-but-present bands still animate.
            bandTargets[j] = sqrt(bands[j].coerceIn(0f, 1f))
        }
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
                // Advance the phase for the flowing-wave effect
                phase += 0.13f
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
        private const val SHEETS = 4

        /** Resting height of the wave when silent. */
        private const val BASELINE = 0.06f

        /** Loudness mapping: noise gate, gain, and perceptual curve (sqrt). */
        private const val NOISE_FLOOR = 350
        private const val GAIN = 4f

        /** Asymmetric response: syllables hit instantly, decay drifts down. */
        private const val ATTACK = 0.65f
        private const val RELEASE = 0.10f

        /** Per-band release (per frame): bass lingers, sibilance vanishes fast. */
        private val BAND_RELEASES = floatArrayOf(0.055f, 0.085f, 0.115f, 0.16f)
    }
}
