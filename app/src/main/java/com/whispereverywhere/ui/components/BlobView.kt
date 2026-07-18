package com.whispereverywhere.ui.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * The "living blob" bubble body. Replaces the static circle/pill background:
 *
 *  - IDLE: calm circle with a slow, barely-perceptible breathing pulse.
 *  - RECORDING: the rim ripples organically with the speaker's voice — fast attack, slow decay,
 *    each perimeter point moving on its own rhythm so plosives kick visible waves and silence
 *    relaxes the shape back to rest.
 *  - PROCESSING: a gentle rotating shimmer.
 *  - ERROR: static (color carries the message).
 *
 * Geometry: the body is a true STADIUM (pill) — two semicircular caps + straight edges — so the
 * widened recording bubble stays pill-shaped rather than collapsing into a pointed ellipse.
 * Sample points are distributed uniformly along the perimeter and displaced along their outward
 * normals, then smoothed with quadratic midpoint curves. Redraws only while animated.
 */
class BlobView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    enum class Mode { IDLE, RECORDING, PROCESSING, ERROR }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.MAGENTA
    }
    private val path = Path()

    private companion object {
        // 36 points ≈ 11 along each straight edge: enough resolution for the ribbon's
        // mountains to survive the midpoint smoothing (18 flattened them into a pill).
        const val POINTS = 36
        /** Ripple headroom in dp: the body inset from the view edge that waves may occupy. */
        const val HEADROOM_DP = 12f
        /** RMS (0..32767) that maps to a full-strength ripple. */
        const val RMS_FULL_SCALE = 3500f
        /** Envelope decay time constant (per second). Fast attack happens in updateAmplitude. */
        const val DECAY_PER_SEC = 3.2f

        /** Per-band decay (per second): bass lingers, sibilance snaps away. */
        val BAND_DECAYS = floatArrayOf(2.6f, 3.4f, 4.4f, 6.0f)
    }

    // Per-point animation diversity so the ripple looks organic rather than mechanical.
    // Speeds ~0.9-1.8 Hz read as "dancing with speech"; slower looks like idle wobble.
    private val phases = FloatArray(POINTS) { it * 1.37f + 0.31f }
    private val speeds = FloatArray(POINTS) { 5.5f + (it % 4) * 1.7f }
    private val px = FloatArray(POINTS)
    private val py = FloatArray(POINTS)

    @Volatile private var envelope = 0f  // smoothed audio energy 0..1
    private var envSmooth = 0f           // frame-lerped envelope (drives swirl/amp without jumps)
    private var mode = Mode.IDLE
    private var t = 0f
    private var lastFrameNs = 0L

    // Integrated flow phases. NEVER compute phase as t * speed(t): when speed follows the voice,
    // the product jumps discontinuously each frame (phase teleport = violent shaking). Integrating
    // phase += speed * dt makes speed changes BEND the flow smoothly instead.
    private var flowPhase1 = 0f
    private var flowPhase2 = 0f
    private var flowPhase3 = 0f

    // Per-band drive mapped SPATIALLY along the rim: left cap = low band, middle = the voice
    // (mid bands, "the top axis"), right cap = sibilance — matching the aurora's gradient, so
    // different parts of the bubble respond to different parts of the spectrum.
    private val bandTargets = FloatArray(4)
    private val bandLevels = FloatArray(4)
    @Volatile private var bandsActive = false

    var fillColor: Int
        get() = paint.color
        set(value) {
            paint.color = value
            invalidate()
        }

    fun setMode(m: Mode) {
        if (mode == m) return
        mode = m
        if (m != Mode.RECORDING) envelope = 0f
        lastFrameNs = 0L
        invalidate()
    }

    /** Feed per-chunk RMS (same values the waveform gets). Fast attack; decay happens per frame. */
    fun updateAmplitude(rms: Int) {
        val target = (rms / RMS_FULL_SCALE).coerceIn(0f, 1f)
        if (target > envelope) envelope = target
    }

    /** Feed per-frame band energies [low, mid-low, mid-high, high] (0..1). Max-hold attack. */
    fun updateBands(bands: FloatArray) {
        if (bands.size < 4) return
        bandsActive = true
        for (j in 0 until 4) {
            val v = kotlin.math.sqrt(bands[j].coerceIn(0f, 1f))
            if (v > bandTargets[j]) bandTargets[j] = v
        }
    }

    /** Piecewise-linear sample of the 4 band levels along the rim's horizontal axis (0..1). */
    private fun bandValueAt(hx: Float): Float {
        val pos = (hx.coerceIn(0f, 1f)) * 3f
        val i = pos.toInt().coerceAtMost(2)
        val frac = pos - i
        return bandLevels[i] + (bandLevels[i + 1] - bandLevels[i]) * frac
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Frame timing (durations only).
        val now = System.nanoTime()
        val dt = if (lastFrameNs == 0L) 0.016f else ((now - lastFrameNs) / 1e9f).coerceIn(0f, 0.1f)
        lastFrameNs = now
        t += dt
        envelope *= exp(-dt * DECAY_PER_SEC)
        // Per-band decay of the max-hold targets: bass lingers (body), sibilance snaps away.
        // (bandLevels lerp toward these each frame — see the flow block below.)
        for (j in 0 until 4) {
            bandTargets[j] *= exp(-dt * BAND_DECAYS[j])
        }

        val density = resources.displayMetrics.density
        val headroom = HEADROOM_DP * density
        val bodyW = (width - 2f * headroom).coerceAtLeast(2f)
        val bodyH = (height - 2f * headroom).coerceAtLeast(2f)
        val cx = width / 2f
        val cy = height / 2f

        // Stadium geometry: cap radius = half height; straight run = leftover width.
        val r = bodyH / 2f
        val straight = (bodyW - bodyH).coerceAtLeast(0f)
        val capLen = (PI * r).toFloat()          // arc length of one semicircular cap
        val total = 2f * capLen + 2f * straight  // full perimeter

        val idleBreath = if (mode == Mode.IDLE) 0.02f * r * sin(t * 1.2f) else 0f
        // RECORDING/PROCESSING ripple is OUTWARD-ONLY: the rim swells from the resting stadium
        // and relaxes back to it, never dipping inside — the waveform ribbon is bounded by the
        // resting interior, so the body always contains it (fixes ribbon poking past the rim).
        // A small constant swell keeps the recording bubble slightly plump even between words.
        val baseSwell = if (mode == Mode.RECORDING) headroom * 0.16f else 0f
        val rippleAmp = when (mode) {
            Mode.RECORDING -> headroom * 0.84f * (0.35f + 0.65f * envSmooth)
            Mode.PROCESSING -> headroom * 0.30f
            // Idle is alive too: a slow, quiet version of the same liquid flow (the breathing
            // rides on top). Only the ribbon rests when there's no audio.
            Mode.IDLE -> headroom * 0.22f
            else -> 0f
        }
        val spin = if (mode == Mode.PROCESSING) t * 2.4f else 0f

        // Gentle whole-body bob while recording: bounded by baseSwell so the rim never dips
        // below the resting stadium that contains the waveform ribbon.
        val bob = if (mode == Mode.RECORDING) baseSwell * sin(t * 0.9f) else 0f

        val rightCapX = cx + straight / 2f
        val leftCapX = cx - straight / 2f

        // Xbox-orb flow: the deformation is a set of lobes TRAVELING around the perimeter in
        // opposite directions (2-lobe one way, 3-lobe the other, 5-lobe accent) — they merge and
        // split as they pass through each other, the classic liquid-orb feel. Voice accelerates
        // the swirl — via the SMOOTHED envelope and integrated phases (no teleporting).
        envSmooth += (envelope - envSmooth) * (if (envelope > envSmooth) 0.35f else 0.12f)
        val swirl = 1f + envSmooth * 1.2f
        // Idle flows at a dreamy fraction of the recording tempo.
        val tempo = if (mode == Mode.IDLE) 0.45f else 1f
        flowPhase1 += dt * 1.9f * swirl * tempo
        flowPhase2 += dt * 3.0f * swirl * tempo
        flowPhase3 += dt * 4.4f * swirl * tempo
        // Band levels get liquid inertia: lerp toward the (decaying max-hold) targets instead of
        // snapping to each 32ms value — the rim reads as heavy fluid, not jitter.
        for (j in 0 until 4) {
            bandLevels[j] += (bandTargets[j] - bandLevels[j]) * (if (bandTargets[j] > bandLevels[j]) 0.45f else 0.25f)
        }

        for (i in 0 until POINTS) {
            val d = total * i / POINTS
            val theta = (d / total) * (2f * PI.toFloat())
            // Walk the perimeter: right cap -> bottom edge -> left cap -> top edge.
            val bx: Float; val by: Float; val nx: Float; val ny: Float
            when {
                d < capLen -> {
                    val theta = -PI.toFloat() / 2f + (d / capLen) * PI.toFloat()
                    nx = cos(theta); ny = sin(theta)
                    bx = rightCapX + r * nx; by = cy + r * ny
                }
                d < capLen + straight -> {
                    val f = (d - capLen) / straight
                    nx = 0f; ny = 1f
                    bx = rightCapX - straight * f; by = cy + r
                }
                d < 2f * capLen + straight -> {
                    val theta = PI.toFloat() / 2f + ((d - capLen - straight) / capLen) * PI.toFloat()
                    nx = cos(theta); ny = sin(theta)
                    bx = leftCapX + r * nx; by = cy + r * ny
                }
                else -> {
                    val f = (d - 2f * capLen - straight) / straight
                    nx = 0f; ny = -1f
                    bx = leftCapX + straight * f; by = cy - r
                }
            }
            // Center-dominant envelope (mirrors the ribbon): full ripple power at the horizontal
            // middle of the edges, tapering toward the caps — the pill's midsection leads the
            // morph instead of reading as a stagnant bar.
            val horiz = 1f - kotlin.math.abs(bx - cx) / (bodyW / 2f).coerceAtLeast(1f)
            val weight = 0.35f + 0.65f * horiz

            // Band-by-position drive with a healthy FLOOR: multiplying weight x drive x osc
            // multiplied the motion to death (sub-dp). Floors keep the flow alive everywhere;
            // the bands add emphasis on top (left=bass ... right=sibilance).
            val hx = ((bx - (cx - bodyW / 2f)) / bodyW).coerceIn(0f, 1f)
            val band = if (mode == Mode.RECORDING && bandsActive) bandValueAt(hx) else 0.6f
            val mix = (0.45f + 0.55f * weight) * (0.45f + 0.55f * band)

            // 0..1 oscillation (never negative) so ripple displacement stays outward.
            // Counter-rotating traveling waves along the rim, amplified toward the full range
            // (raw interference of three sines rarely nears +/-1 without the boost).
            val flow = (0.45f * sin(2f * theta + flowPhase1 + spin) +
                0.33f * sin(3f * theta - flowPhase2 + 1.3f) +
                0.22f * sin(5f * theta + flowPhase3 + 2.1f)) * 1.55f
            val osc = 0.5f + 0.5f * flow.coerceIn(-1f, 1f)

            // Ribbon lockstep (user design 2026-07-18): while the aurora is live, its published
            // silhouette DICTATES the skin — this point samples the crest/trough profile at its
            // own horizontal station, blended by which way it faces (top edge follows crests,
            // bottom follows troughs, caps take both). The mapping is DIRECT onto the full
            // ripple headroom — no envelope/band damping (the silhouette already carries the
            // loudness) — because the damped version left the pill visibly rigid while the
            // ribbons spiked (user photo 2026-07-18). A thin traveling-lobe underlayer keeps
            // the skin liquid between syllables; the budget sums to exactly the headroom.
            val ribbonLive = mode == Mode.RECORDING && RibbonProfile.fresh()
            val wobble = if (ribbonLive) {
                val vertMix = ny * 0.5f + 0.5f // -1 (top) -> 0 ... +1 (bottom) -> 1
                val rib = RibbonProfile.sample(RibbonProfile.top, hx) * (1f - vertMix) +
                    RibbonProfile.sample(RibbonProfile.bottom, hx) * vertMix
                baseSwell + headroom * 0.84f * (0.82f * rib + 0.18f * osc) + idleBreath
            } else {
                baseSwell + rippleAmp * mix * osc + idleBreath
            }

            // Direction variety: the push wanders off the pure normal by up to ~20 deg on a slow
            // cycle — the middle heaves straight north-south sometimes, diagonally other times.
            val tilt = if (mode == Mode.RECORDING) 0.55f * sin(t * 0.8f + i * 1.1f) * weight else 0f
            val cosT = cos(tilt)
            val sinT = sin(tilt)
            px[i] = bx + (nx * cosT - ny * sinT) * wobble
            py[i] = by + (nx * sinT + ny * cosT) * wobble + bob
        }

        // Smooth closed curve: quadratic segments through consecutive midpoints (C1-continuous).
        path.rewind()
        path.moveTo((px[0] + px[1]) / 2f, (py[0] + py[1]) / 2f)
        for (i in 1..POINTS) {
            val p = i % POINTS
            val n = (i + 1) % POINTS
            path.quadTo(px[p], py[p], (px[p] + px[n]) / 2f, (py[p] + py[n]) / 2f)
        }
        path.close()
        canvas.drawPath(path, paint)

        when (mode) {
            Mode.RECORDING, Mode.PROCESSING -> postInvalidateOnAnimation()
            Mode.IDLE -> postInvalidateDelayed(50)  // ~20 fps is plenty for slow breathing
            Mode.ERROR -> Unit
        }
    }
}
