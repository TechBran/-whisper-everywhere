package com.whispereverywhere.transcription

/**
 * The GPU canary's match rule (3.6.0 Workstream C).
 *
 * The multilingual GPU ban is EMPIRICAL, not theoretical: on the Fold 6 the Adreno OpenCL
 * backend decoded ggml-small-q5_1 to garbage tokens and ggml-large-v3-turbo-q5_0 to empty
 * transcriptions, with no crash to catch (GpuPolicy.kt:53-64). Crash sentinels cannot see
 * silent corruption — so before a non-".en" model is allowed on the GPU, it transcribes a
 * bundled ~1 s clip of spoken digits and its output is checked HERE.
 *
 * The rule is deliberately tolerant of ordinary ASR slack (casing, punctuation, one dropped
 * digit on a short clip) and intolerant of every observed corruption shape:
 *  - empty / whitespace-only  -> FAIL (the large-turbo signature)
 *  - fewer than [MIN_MATCHES] expected POSITIONS -> FAIL (the garbage-token signature)
 *  - a runaway token count    -> FAIL (degenerate repetition, which can contain the digits)
 *
 * Pure and JVM-pinned (GpuCanaryPolicyTest): this verdict is persisted as a permanent per
 * (app version, model, device) latch, so a wrong rule is expensive to undo.
 */
object GpuCanaryPolicy {

    /**
     * One ALIAS SET per spoken position in `assets/canary_digits.wav`, in order. The clip
     * contains the WORDS one-through-five (that is the C4 asset contract and it does not change),
     * but whisper renders spoken digits as numerals routinely — the JNI decodes with
     * no_context=true and suppress_nst=true, and on a ~1 s clip "1, 2, 3, 4, 5." is a common and
     * entirely CORRECT output. [normalize] keeps digits, so matching word forms only would score
     * that perfect transcription zero and latch a working GPU to CPU for the whole app version
     * on a formatting coin-flip. The aliases are about whisper's RENDERING, never about what the
     * owner records.
     */
    val EXPECTED_TOKENS: List<Set<String>> = listOf(
        setOf("one", "1"),
        setOf("two", "2"),
        setOf("three", "3"),
        setOf("four", "4"),
        setOf("five", "5"),
    )

    /** How many POSITIONS must appear (in any alias form). One dropped digit is ordinary; two is not. */
    const val MIN_MATCHES = 4

    /** Beyond this many tokens the output is a repetition runaway, not a transcription. */
    private const val MAX_TOKENS = 20

    private val NON_WORD = Regex("[^\\p{L}\\p{Nd}]+")

    /** Lowercased word tokens, punctuation stripped. */
    fun normalize(text: String): List<String> =
        text.lowercase().split(NON_WORD).filter { it.isNotEmpty() }

    /** True when [text] is a believable transcription of the canary clip. */
    fun canaryPasses(text: String): Boolean {
        val tokens = normalize(text)
        if (tokens.isEmpty()) return false
        if (tokens.size > MAX_TOKENS) return false
        val seen = tokens.toSet()
        // A position counts as matched when ANY of its aliases is present.
        return EXPECTED_TOKENS.count { aliases -> aliases.any { it in seen } } >= MIN_MATCHES
    }
}
