package com.whispereverywhere.tts

import kotlin.math.roundToInt

/**
 * Pure arithmetic behind the TTSDIAG records. No Android, no state — unit-tested on the JVM in
 * the SpeechSegmenter / AudioSourcePolicy idiom.
 *
 * Every helper is total: a zero denominator returns 0 rather than throwing or producing
 * Infinity, because a single poisoned sample would corrupt the percentile summary that the
 * whole diagnostic pass depends on.
 */
object TtsDiagMath {

    /** Milliseconds of audio in [samples] at [sampleRate] Hz (mono). */
    fun audioMs(samples: Int, sampleRate: Int): Long =
        if (sampleRate <= 0) 0L else samples.toLong() * 1000L / sampleRate.toLong()

    /**
     * Real-time factor: wall-clock synthesis time divided by audio produced.
     * < 1 means synthesis outruns playback. >= 1 means the pipeline cannot be smooth by
     * arithmetic, regardless of buffering (spec 6A.4).
     */
    fun rtf(synthMs: Long, audioMs: Long): Double =
        if (audioMs <= 0L) 0.0 else synthMs.toDouble() / audioMs.toDouble()

    /**
     * The only honest silence measurement. While the playback loop is stalled, the AudioTrack
     * keeps rendering whatever was already queued — so the user hears silence only for the
     * remainder. underrunCount alone says we fed the track late; it never says how much silence
     * reached the ear.
     */
    fun audibleSilenceMs(wallStalledMs: Long, renderedDuringStallMs: Long): Long =
        (wallStalledMs - renderedDuringStallMs).coerceAtLeast(0L)

    /** Nearest-rank percentile of an ASCENDING-sorted list. [p] in 0..1. */
    fun percentile(sorted: List<Double>, p: Double): Double {
        if (sorted.isEmpty()) return 0.0
        val idx = (p.coerceIn(0.0, 1.0) * (sorted.size - 1)).roundToInt()
        return sorted[idx]
    }

    /**
     * Percentage of wall clock that was [audioMs] SYNTHESIZED audio, over [wallMs] elapsed.
     * 100 means gapless. A value ABOVE 100 is not a bug: it means more audio was synthesized
     * than the run had time to play — synthesis outran playback and the excess is still banked,
     * unplayed. Clamping that to 100 would render a real gap (see `end`'s `playedMs`, which is
     * always <= wallMs and can never exceed it) as indistinguishable from a perfect run, so only
     * the floor is clamped, at 0.
     */
    fun dutyPct(audioMs: Long, wallMs: Long): Int =
        if (wallMs <= 0L) 0 else ((audioMs.toDouble() / wallMs.toDouble()) * 100.0)
            .roundToInt().coerceAtLeast(0)
}
