package com.whispereverywhere.npu

/**
 * The decode configuration `QnnAsrNative.nativeDecodeSegment` consumes: the prompt, the two
 * suppression sets, and the token budget.
 *
 * **This is the config source, not a post-hoc filter.** The suppression mask is applied to the
 * logits *before* the argmax scan, on the native side of the JNI boundary, because a caller holding
 * only the argmax has no runner-up: it can see that the model wants a suppressed token and can do
 * nothing useful about it — re-running the step is deterministic and returns the same token, so the
 * loop either emits the suppressed token or hangs. So these arrays are handed **in**, once per
 * segment, and native does the masking. That is what makes them worth pinning here: they are the
 * only lever, and they are pure data.
 *
 * Everything in this object is pure Kotlin with no reference to [QnnAsrNative] — that object
 * carries `System.loadLibrary("qnnasr")` and would kill any JVM test that named it.
 */
object NpuDecodePolicy {

    /**
     * `[<|startoftranscript|>, <|lang|>, <|transcribe|>, <|notimestamps|>]` — the four-token prompt,
     * in the order the model was trained to see it.
     *
     * Each of these consumes a self-KV slot: they are fed through the same execute path as the
     * generated tokens, at positions 0..3, and the argmax produced at position 3 is the **first
     * generated token**. That is why [maxTokensFor] takes the prompt's length rather than assuming
     * four.
     *
     * `<|notimestamps|>` is the one that goes missing. Without it the model interleaves timestamp
     * tokens with the words; Q5's detokeniser drops every id above `EOT`, so they vanish silently
     * and take their positions in the budget with them — a short, plausible, word-dropping
     * transcript and no fault reported anywhere.
     *
     * @throws IllegalArgumentException if [languageCode] is not one of whisper's 99 codes. There is
     *         no English fallback here, deliberately: see [WhisperTokens.langToken].
     */
    fun promptTokens(languageCode: String): IntArray = intArrayOf(
        WhisperTokens.SOT,
        WhisperTokens.langToken(languageCode),
        WhisperTokens.TRANSCRIBE,
        WhisperTokens.NO_TIMESTAMPS
    )

    /**
     * The always-on mask: `generation_config.json`'s 88 `suppress_tokens` **plus all 1501 timestamp
     * ids**, ascending and duplicate-free.
     *
     * The timestamps are ours, not whisper's — whisper relies on the `<|notimestamps|>` prompt
     * alone. We prompt it too, and then also mask, because a timestamp arriving anyway is a decode
     * fault that costs a position and leaves no trace: Q5 drops every id above `EOT`, so the only
     * symptom is a transcript that came back shorter than the speech.
     *
     * `EOT` is **not** here. It is in [beginSuppressList], which applies at one step; masking it at
     * every step would leave the loop with no terminator short of the 199-position cap, and every
     * segment would run to the cap emitting filler.
     *
     * Ascending and unique is a native precondition, not tidiness: the mask loop writes
     * `logits[id]` for each entry without re-validating, so a duplicate is a wasted store and an
     * out-of-range id is a write past the end of a 103,730-byte buffer.
     */
    val suppressList: IntArray =
        (WhisperTokens.SUPPRESS.toList() + (WhisperTokens.TIMESTAMP_BEGIN until WhisperTokens.VOCAB))
            .distinct().sorted().toIntArray()

    /**
     * `[220, 50257]` — applied at the **first generated step only** (`position == promptLen - 1`),
     * which is not `position == 0`. At position 0 the model is still being fed the prompt and its
     * argmax is discarded.
     */
    val beginSuppressList: IntArray = WhisperTokens.BEGIN_SUPPRESS

    /**
     * How many tokens a prompt of [promptLen] can generate: `MAX_POSITIONS - promptLen`, which is
     * **196** for the four-token prompt.
     *
     * The arithmetic, spelled out because every part of it is off-by-one bait. `position` is the
     * single counter and the prompt consumes it too. Positions 0..198 execute — 199 of them, an
     * exact fit for the 199-deep self-KV, using 199 of the mask's 200 columns; 199 is the
     * termination threshold and never executes. The first generated token lands at
     * `position == promptLen - 1`. So the generated positions are `promptLen - 1 .. 198`, which is
     * `198 - (promptLen - 1) + 1 == MAX_POSITIONS - promptLen` tokens: `198 - 2 == 196` for a
     * four-token prompt.
     *
     * The caller sizes `nativeDecodeSegment`'s `out` array to at least this. Native bounds-checks
     * rather than trusting it.
     *
     * @throws IllegalArgumentException for a prompt that could never generate. A non-positive
     *         budget would make `nativeDecodeSegment` return zero tokens, which reads exactly like
     *         a segment of silence.
     */
    fun maxTokensFor(promptLen: Int): Int {
        require(promptLen in 1 until WhisperTokens.MAX_POSITIONS) {
            "promptLen $promptLen is outside 1..${WhisperTokens.MAX_POSITIONS - 1}: a prompt that " +
                "fills the whole 200-position context leaves nothing to generate, and a " +
                "non-positive budget returns zero tokens, which is indistinguishable from silence."
        }
        return WhisperTokens.MAX_POSITIONS - promptLen
    }
}
