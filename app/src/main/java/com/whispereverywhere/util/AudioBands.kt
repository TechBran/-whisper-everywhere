package com.whispereverywhere.util

import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Lightweight 4-band spectral analysis of one PCM16 mono @16kHz frame via Goertzel probes.
 * Costs microseconds per 32ms frame — no FFT needed for UI-grade band energies.
 *
 * Bands (speech-tuned):
 *   [0] LOW      ~120-220 Hz  — voicing / bass body
 *   [1] MID-LOW  ~500-800 Hz  — vowel energy (the "voice" itself)
 *   [2] MID-HIGH ~1.6-2.6 kHz — formants / consonant color
 *   [3] HIGH     ~5-6.8 kHz   — sibilance ("s", "sh" sparkle)
 *
 * Output: 4 floats, roughly 0..1 (per-band gains compensate speech's spectral tilt).
 */
object AudioBands {

    private val PROBES = arrayOf(
        floatArrayOf(120f, 220f),
        floatArrayOf(500f, 800f),
        floatArrayOf(1600f, 2600f),
        floatArrayOf(5000f, 6800f),
    )

    /** Speech energy falls off with frequency — lift the upper bands into a comparable range. */
    private val GAINS = floatArrayOf(1.0f, 1.4f, 2.6f, 4.0f)

    private const val SAMPLE_RATE = 16000f

    /**
     * Per-band AGC constants, set from an on-device acoustic measurement (Fold 6 speaker->mic
     * loop of real speech, 2026-07-18, 305 voiced chunks): far-field p90 per band =
     * [0.017, 0.045, 0.077, 0.006]; near-field dictation runs several times hotter. INIT sits
     * ~2-3x the far-field p90 (mid-ground toward near-field); FLOOR ≈ p90/6 so quiet-room
     * noise can't define "loud" — the old global 0.08 floor sat ABOVE the sibilance band's
     * measured maximum and kept it permanently dead in far-field use.
     */
    private val PEAK_INIT = floatArrayOf(0.05f, 0.10f, 0.12f, 0.02f)
    private val PEAK_FLOOR = floatArrayOf(0.004f, 0.008f, 0.012f, 0.002f)

    private val peaks = FloatArray(PROBES.size) { PEAK_INIT[it] }

    // Session warm-up (user feedback: startup "fast and aggressive... adjustment period needs
    // tuning"): no fixed reference fits both far-field and close dictation, so the OUTPUT
    // itself ramps 35% -> 100% over the first ~2 s of each session — a deterministic, smooth
    // crescendo instead of a calibration slam. A >1 s gap in analyze() calls = new session.
    private var warmupFrames = 0
    private var lastAnalyzeNs = 0L

    val ZERO = FloatArray(PROBES.size)

    /** Calibration-only observer (band, raw, peak, out); null in production. */
    @Volatile var calibrationTap: ((Int, Float, Float, Float) -> Unit)? = null

    /** Calibration-only: reset the AGC to its cold-start state for a measurement run. */
    fun resetForCalibration() {
        for (b in peaks.indices) peaks[b] = PEAK_INIT[b]
        warmupFrames = 0
        lastAnalyzeNs = 0L
    }

    /** ~2 s of 32 ms chunks. */
    private const val WARMUP_CHUNKS = 60

    /**
     * @param rawOut optional: filled with the PRE-AGC band energies (calibration tooling —
     * the instrumented level-measurement test reads these; production callers pass nothing).
     */
    fun analyze(pcm: ByteArray, lenBytes: Int, rawOut: FloatArray? = null): FloatArray {
        val n = lenBytes / 2
        val out = FloatArray(PROBES.size)
        if (n < 64) return out
        val nowNs = System.nanoTime()
        if (nowNs - lastAnalyzeNs > 1_000_000_000L) warmupFrames = 0
        lastAnalyzeNs = nowNs
        if (warmupFrames < WARMUP_CHUNKS) warmupFrames++
        val warmScale = 0.35f + 0.65f * (warmupFrames / WARMUP_CHUNKS.toFloat())
        for (b in PROBES.indices) {
            var sum = 0f
            for (f in PROBES[b]) sum += goertzelAmplitude(pcm, n, f)
            val v = sum / PROBES[b].size * GAINS[b]
            rawOut?.set(b, v)
            // AGC: normalize against a slowly-decaying per-band peak. Absolute mic levels vary
            // wildly between devices/distances; without this the bands sit at 0.1-0.3 and the
            // visuals look sleepy. With it, the user's OWN dynamics span the full 0..1 range.
            // The peak RISES on a blend (not a snap): one plosive still spikes the ribbon once
            // — as it should — but can't instantly rescale the session and crush what follows.
            val decayed = maxOf(peaks[b] * 0.995f, PEAK_FLOOR[b])
            peaks[b] = if (v > decayed) decayed + (v - decayed) * 0.6f else decayed
            out[b] = ((v / peaks[b]) * warmScale).coerceIn(0f, 1f)
            calibrationTap?.invoke(b, v, peaks[b], out[b])
        }
        return out
    }

    /** Normalized amplitude (≈0..1 for a full-scale sine at [freq]) of one frequency probe. */
    private fun goertzelAmplitude(pcm: ByteArray, n: Int, freq: Float): Float {
        val coeff = (2.0 * cos(2.0 * Math.PI * freq / SAMPLE_RATE)).toFloat()
        var s1 = 0f
        var s2 = 0f
        var i = 0
        while (i < n) {
            val lo = pcm[2 * i].toInt() and 0xFF
            val hi = pcm[2 * i + 1].toInt()
            val sample = ((hi shl 8) or lo) / 32768f
            val s0 = sample + coeff * s1 - s2
            s2 = s1
            s1 = s0
            i++
        }
        val power = (s1 * s1 + s2 * s2 - coeff * s1 * s2).coerceAtLeast(0f)
        // sqrt(power)/n ≈ amplitude/2 for an on-bin sine; x4 maps full-scale to ~1 with headroom.
        return sqrt(power / (n.toFloat() * n)) * 4f
    }
}
