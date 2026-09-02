package com.whispereverywhere.npu

/**
 * The six numbers `nativeDecodeSegment` reports about the segment it just decoded, by slot.
 *
 * Native fills a `FloatArray(SIZE)` the caller hands in — an OUT array, like `out` for the ids —
 * and writes these slots by the `kStat*` literals that mirror the constants below.
 * `NpuNativeContractTest` holds the two copies equal by reading `qnn_asr.cpp` as text, because a
 * slot that moves on one side and not the other is not a crash: the diag line prints the entropy
 * where the no-speech probability should be and the no-speech gate reads a rung index.
 *
 * Never transcript content: probabilities, an entropy, a rung index, a code and a step count.
 */
object NpuDecodeStats {
    /** `p(<|nospeech|>)` from the raw logits at the SOT step; `-1` when the scale was unreadable. */
    const val NO_SPEECH_PROB = 0
    /**
     * Mean log-probability of the returned rung's ids under the masked distribution, the EOT
     * counted when it came (as `whisper_sequence_score` counts it); NaN when the scale was
     * unreadable or nothing was scored. After a `cut` it is the failing rung's pre-cut average.
     */
    const val AVG_LOGPROB = 1
    /**
     * Histogram entropy of the last [NpuDecodePolicy.ENTROPY_WINDOW] ids; NaN whenever that window
     * was never reached on the returned rung.
     */
    const val ENTROPY = 2
    /** Index into [NpuDecodePolicy.TEMPERATURES] of the rung whose output was returned. */
    const val RUNG = 3
    /** One of the `TERM_*` codes. */
    const val TERMINATOR = 4
    /** Decoder steps executed across every rung — the segment's real decode cost. */
    const val STEPS = 5
    const val SIZE = 6

    const val TERM_EOT = 0f
    const val TERM_BUDGET = 1f
    const val TERM_CAP = 2f
    const val TERM_CUT = 3f

    /** A fresh OUT array: every slot NaN, so "native never wrote this" is readable as such. */
    fun newArray(): FloatArray = FloatArray(SIZE) { Float.NaN }

    fun terminatorName(code: Float): String = when (code) {
        TERM_EOT -> "eot"
        TERM_BUDGET -> "budget"
        TERM_CAP -> "cap"
        TERM_CUT -> "cut"
        else -> "unknown"
    }
}
