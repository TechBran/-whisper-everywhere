package com.whispereverywhere.util

/**
 * Token-level Word Error Rate and the audio_ctx floor's accuracy gate (3.6.0 Workstream G).
 *
 * The stop-tail fragment pays a 768-frame minimum audio_ctx for ~119 frames of audio — a 6.45x
 * overshoot on the finalize critical path. The floor was raised 256 -> 768 for a REAL, documented
 * accuracy regression (stock whisper models garble short phrases under aggressive audio_ctx
 * reduction), so it is lowered ONLY if a per-tier bench proves accuracy holds. That proof is
 * arithmetic, and arithmetic belongs in a pure, tested object rather than in an instrumented
 * test nobody can run on CI.
 *
 * WER = edit distance between token sequences / reference token count. Tokens are lowercased and
 * stripped of punctuation: two decodes of the same audio differ in casing and punctuation
 * constantly, and that is not an error worth failing a floor over.
 */
object WerMath {

    /**
     * The accuracy gate a candidate audio_ctx floor must clear on EVERY benched slice of EVERY
     * benched tier: 10 % word error against the same tier's transcription at the production
     * floor. Chosen to be tight enough that the documented garbling regression cannot slip
     * through, loose enough that ordinary decode jitter on a short slice does not fail a good
     * floor.
     */
    const val FLOOR_WER_GATE = 0.10

    private val NON_WORD = Regex("[^\\p{L}\\p{Nd}']+")

    /** Lowercased word tokens, punctuation stripped. */
    fun tokens(text: String): List<String> =
        text.lowercase().replace('’', '\'').split(NON_WORD).filter { it.isNotEmpty() }

    /**
     * Word Error Rate of [hypothesis] against [reference], in [0, ∞) — values above 1 are
     * possible when the hypothesis inserts more words than the reference contains. An empty
     * reference scores 0.0 against an empty hypothesis and 1.0 against any non-empty one
     * (a degenerate case the bench asserts away, but it must never divide by zero).
     */
    fun wer(reference: String, hypothesis: String): Double {
        val ref = tokens(reference)
        val hyp = tokens(hypothesis)
        if (ref.isEmpty()) return if (hyp.isEmpty()) 0.0 else 1.0
        return editDistance(ref, hyp).toDouble() / ref.size.toDouble()
    }

    /** Levenshtein distance over token lists; two rolling rows, so memory is O(hyp). */
    private fun editDistance(ref: List<String>, hyp: List<String>): Int {
        var prev = IntArray(hyp.size + 1) { it }
        var curr = IntArray(hyp.size + 1)
        for (i in 1..ref.size) {
            curr[0] = i
            for (j in 1..hyp.size) {
                val substitution = prev[j - 1] + if (ref[i - 1] == hyp[j - 1]) 0 else 1
                val deletion = prev[j] + 1
                val insertion = curr[j - 1] + 1
                curr[j] = minOf(substitution, deletion, insertion)
            }
            val swap = prev; prev = curr; curr = swap
        }
        return prev[hyp.size]
    }

    /**
     * The floor-candidate verdict: a candidate audio_ctx floor QUALIFIES only when at least one
     * measurement exists and EVERY recorded WER is at or under [FLOOR_WER_GATE]. Task G4 is gated on
     * this function's verdict as logged by the bench and recorded by the owner — never on
     * eyeballing.
     */
    fun floorQualifies(wers: List<Double>): Boolean =
        wers.isNotEmpty() && wers.all { it <= FLOOR_WER_GATE }
}
