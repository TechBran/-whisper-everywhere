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
     * Per-band rolling peak for AGC: the speaker's own voice defines "loud".
     *
     * Startup direction matters (user feedback 2026-07-18: ribbons "so fast and aggressive"
     * for the first seconds, then settle): initialized LOW, every early chunk was a new
     * record and normalized to exactly 1.0 — maximum slam until calibration caught up. Now
     * the reference starts at a realistic speech level (calm first seconds) and ADAPTS DOWN
     * for quiet speakers via the decay within a few seconds.
     */
    private val peaks = FloatArray(PROBES.size) { 0.30f }

    val ZERO = FloatArray(PROBES.size)

    fun analyze(pcm: ByteArray, lenBytes: Int): FloatArray {
        val n = lenBytes / 2
        val out = FloatArray(PROBES.size)
        if (n < 64) return out
        for (b in PROBES.indices) {
            var sum = 0f
            for (f in PROBES[b]) sum += goertzelAmplitude(pcm, n, f)
            val v = sum / PROBES[b].size * GAINS[b]
            // AGC: normalize against a slowly-decaying per-band peak. Absolute mic levels vary
            // wildly between devices/distances; without this the bands sit at 0.1-0.3 and the
            // visuals look sleepy. With it, the user's OWN dynamics span the full 0..1 range.
            // The peak RISES on a blend (not a snap): one plosive still spikes the ribbon once
            // — as it should — but can't instantly rescale the session and crush what follows.
            val decayed = maxOf(peaks[b] * 0.995f, 0.08f)
            peaks[b] = if (v > decayed) decayed + (v - decayed) * 0.6f else decayed
            out[b] = (v / peaks[b]).coerceIn(0f, 1f)
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
