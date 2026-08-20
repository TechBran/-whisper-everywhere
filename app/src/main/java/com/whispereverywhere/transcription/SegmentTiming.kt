package com.whispereverywhere.transcription

import java.util.Locale

/**
 * The permanent per-segment RTF diagnostic (3.6.0, Workstream A3).
 *
 * One line per resolved local segment, emitted by [LocalWhisperEngine.runSegment] around
 * `backend.transcribe`:
 *
 *     segment-timing: audio=<ms> transcribe=<ms> rtf=<x.xx>
 *
 * rtf = transcribe/audio — RTF < 1 means faster than real time. This converts the deep-analysis
 * report's last big ESTIMATE (the multi tier's real RTF on the owner's device) into MEASURED
 * data, and is what the tier-consolidation and GPU-default decision gates read (spec Decision
 * Gates 1-2). Pure so the exact greppable format is JVM-pinned (SegmentTimingTest) — a format
 * drift would silently break every future report that parses it.
 *
 * READ THE DENOMINATOR LITERALLY: [audioMs] is the PRE-VAD wall-clock duration of the committed
 * buffer (every sample the user's segment held, silence included), while the numerator is the
 * whole transcribe call — native VAD trimming, encode, decode AND any retry attempts. So this is
 * a speech-in/compute-out ratio for one committed segment, NOT a pure model RTF: it is
 * deliberately the wall cost the user actually paid, which is the number the decision gates want.
 *
 * Content discipline: numbers only, NEVER transcript text — logcat is readable by adb/other
 * tooling and the product promise is that transcriptions stay on-device.
 */
object SegmentTiming {

    /** PCM16 mono sample rate the entire capture pipeline runs at. */
    const val SAMPLE_RATE_HZ = 16_000

    /** Audio duration in ms for [sampleCount] float samples at [sampleRateHz]. */
    fun audioMs(sampleCount: Int, sampleRateHz: Int = SAMPLE_RATE_HZ): Long =
        sampleCount * 1000L / sampleRateHz

    /**
     * The line itself. A zero/negative [audioMs] (degenerate commit) reports rtf=0.00 instead of
     * dividing by zero. Locale.US so the decimal separator is always a point, never a comma.
     */
    fun line(audioMs: Long, transcribeMs: Long): String {
        val rtf = if (audioMs > 0) transcribeMs.toDouble() / audioMs.toDouble() else 0.0
        return "segment-timing: audio=$audioMs transcribe=$transcribeMs rtf=" +
            String.format(Locale.US, "%.2f", rtf)
    }
}
