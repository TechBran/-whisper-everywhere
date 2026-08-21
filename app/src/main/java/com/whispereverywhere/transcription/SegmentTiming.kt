package com.whispereverywhere.transcription

import java.util.Locale

/**
 * The permanent per-segment RTF diagnostic (3.6.0, Workstream A3).
 *
 * One line per resolved local segment, emitted by [LocalWhisperEngine.runSegment] around
 * `backend.transcribe`:
 *
 *     segment-timing: seq=<n> audio=<ms> transcribe=<ms> rtf=<x.xx> vadIn=<n> vadOut=<n> ctxFrames=<n>
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
     *
     * [seq] is PREPENDED, not appended and not emitted as a sibling line (3.7 Workstream F): the
     * pre-3.7 substring `audio=… transcribe=… rtf=…` survives byte-identically, so every
     * `findstr segment-timing` grep and every parser written against 3.6.0 keeps working, while a
     * capture can now be joined against `endpoint:` / `perceived:` on the shared seq — which is
     * what makes "why was this segment cut and how long did the user wait" answerable from one log.
     *
     * [stats] is APPENDED and OPTIONAL, for the same compatibility reason from the other end: a
     * backend with no native counters (every fake, any future non-native backend) emits exactly
     * the seq-only form. `ctxFrames` is the encoder cost driver 3.7's cadence arithmetic turns on;
     * `vadIn`/`vadOut` instrument the rare probe-vs-batch-filter disagreement (`cut=vad` with
     * `vadOut=0`), which otherwise surfaces only as a silent EmptyExpected.
     *
     * The suffix is omitted for a NULL [stats] and for nothing else. An all-zero reading is a
     * measurement ("a transcribe ran and cost nothing"), not an absence, and prints in full —
     * see [NativeSegmentStats]. Field order here is deliberately NOT the data class's: the line
     * reads in pipeline order (VAD in, VAD out, then what the encoder was given).
     *
     * MIND THE DENOMINATORS ACROSS RETRIES: [transcribeMs] spans EVERY attempt this segment took
     * (LocalWhisperEngine measures around the whole retry), while the counters describe only the
     * LAST attempt. On a retried segment rtf is therefore high against a `ctxFrames` that only
     * paid for one pass — that is a retry, not a lying encoder; cross-check `WE-DIAG` retry lines
     * before reading such a pair as a per-frame cost.
     */
    fun line(
        seq: Long,
        audioMs: Long,
        transcribeMs: Long,
        stats: NativeSegmentStats? = null,
    ): String {
        val rtf = if (audioMs > 0) transcribeMs.toDouble() / audioMs.toDouble() else 0.0
        val head = "segment-timing: seq=$seq audio=$audioMs transcribe=$transcribeMs rtf=" +
            String.format(Locale.US, "%.2f", rtf)
        return if (stats == null) {
            head
        } else {
            head + " vadIn=${stats.vadInSamples} vadOut=${stats.vadOutSamples}" +
                " ctxFrames=${stats.ctxFrames}"
        }
    }
}
