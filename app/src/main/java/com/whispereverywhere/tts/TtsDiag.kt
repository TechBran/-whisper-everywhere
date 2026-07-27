package com.whispereverywhere.tts

/**
 * TTSDIAG log-line formatting. Pure strings — no Android, no I/O — so the format is pinned by
 * unit tests and the owner's paste-back stays machine-readable.
 *
 * Format contract: `TTSDIAG <kind> key=value key=value ...`, space-separated, NO COMMAS, so a
 * whole session is recoverable with `adb logcat -s WE-TTS | grep TTSDIAG` and splittable on
 * whitespace. Kinds: open, sent, play, under, end.
 */
object TtsDiag {

    const val TAG = "WE-TTS"

    private fun d2(v: Double): String = String.format(java.util.Locale.US, "%.2f", v)

    /** Track created. Records what the framework actually GRANTED, not what we asked for. */
    fun open(gen: Long, bufFrames: Int, perfMode: Int, chars: Int, sampleRate: Int): String =
        "TTSDIAG open gen=$gen bufFrames=$bufFrames bufMs=${TtsDiagMath.audioMs(bufFrames, sampleRate)} " +
            "perfMode=$perfMode rate=$sampleRate chars=$chars"

    /** One sherpa callback landed: a whole sentence of audio. */
    fun sent(gen: Long, seq: Int, samples: Int, audMs: Long, synthMs: Long): String =
        "TTSDIAG sent gen=$gen seq=$seq samples=$samples audMs=$audMs synthMs=$synthMs " +
            "rtf=${d2(TtsDiagMath.rtf(synthMs, audMs))}"

    /** Playback cursor crossed into sentence [seq]; [leadMs] is the bank ahead of the cursor. */
    fun play(gen: Long, seq: Int, leadMs: Long): String =
        "TTSDIAG play gen=$gen seq=$seq leadMs=$leadMs"

    /**
     * A stall ended. [wallMs] is how long the loop waited; [renderMs] is how much audio the
     * track still rendered during that wait; the difference is what the user actually heard as
     * silence. [hwUnderD] is the getUnderrunCount() delta across the stall.
     */
    fun under(gen: Long, seq: Int, atMs: Long, wallMs: Long, renderMs: Long, hwUnderD: Int): String =
        "TTSDIAG under gen=$gen seq=$seq atMs=$atMs wallMs=$wallMs renderMs=$renderMs " +
            "audibleMs=${TtsDiagMath.audibleSilenceMs(wallMs, renderMs)} hwUnderD=$hwUnderD"

    /** Utterance summary. [rtfs] may arrive in any order; sorted here. */
    fun end(
        gen: Long,
        ttfwMs: Long,
        underN: Int,
        underMs: Long,
        maxGapMs: Long,
        audioMs: Long,
        wallMs: Long,
        rtfs: List<Double>,
        hwUnderTotal: Int,
    ): String {
        val sorted = rtfs.sorted()
        return "TTSDIAG end gen=$gen ttfwMs=$ttfwMs underN=$underN underMs=$underMs " +
            "maxGapMs=$maxGapMs audioMs=$audioMs wallMs=$wallMs " +
            "dutyPct=${TtsDiagMath.dutyPct(audioMs, wallMs)} " +
            "rtfP50=${d2(TtsDiagMath.percentile(sorted, 0.50))} " +
            "rtfP95=${d2(TtsDiagMath.percentile(sorted, 0.95))} " +
            "rtfMax=${d2(TtsDiagMath.percentile(sorted, 1.0))} " +
            "hwUnder=$hwUnderTotal"
    }
}
