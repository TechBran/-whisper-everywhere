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
    fun promptTokens(languageCode: String): IntArray =
        promptTokens(WhisperTokens.langToken(languageCode))

    /**
     * The same four-token prompt, built from an already-resolved `<|xx|>` **token id**.
     *
     * This is the overload the tier actually calls, because [resolveLangToken] answers in ids: the
     * auto-detect path's answer *is* an id (`nativeDetectLanguage` argmaxes over the language
     * block), and routing it back through a code and forward through [WhisperTokens.langToken]
     * would be two lookups that can disagree.
     *
     * @throws IllegalArgumentException if [langToken] is outside `50259..50357`. The language slot
     *         of the prompt is not a place a stray id may land: `<|transcribe|>` or a timestamp
     *         there is not a crash and not garbage — the model reads whatever embedding row it
     *         points at and produces fluent text under it.
     */
    fun promptTokens(langToken: Int): IntArray {
        require(WhisperTokens.codeForToken(langToken) != null) {
            "$langToken is not a <|xx|> language token (${WhisperTokens.LANG_FIRST}.." +
                "${WhisperTokens.LANG_LAST}). Putting a non-language id in the prompt's language " +
                "slot does not fail: the decoder reads that embedding row and transcribes under it."
        }
        return intArrayOf(
            WhisperTokens.SOT,
            langToken,
            WhisperTokens.TRANSCRIBE,
            WhisperTokens.NO_TIMESTAMPS
        )
    }

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

    // ---------------------------------------------------------------- the language policy (NEW-C2)

    /**
     * Which language the tier will prompt with, **and the note that says how it was decided**.
     *
     * The note is not decoration and it is not a log format that happens to live here: it is
     * carried in the same value the token is, decided in the same expression, and asserted in the
     * same test. That is what turns *"no path silently yields English"* from a claim in prose into
     * a property the suite enforces — a fallback that produced `en` without saying `(fallback)`
     * would have to change this type to compile.
     *
     * @param token the `<|xx|>` id to put in the prompt's language slot.
     * @param code the whisper language code for [token] — what `detectedLanguage(ctx)` reports back
     *        to the app's existing language plumbing.
     * @param note the `lang=` field of the `npu:` diag line. Exactly one of four shapes; see
     *        [resolveLangToken].
     */
    data class LangResolution(val token: Int, val code: String, val note: String)

    /**
     * THE LANGUAGE POLICY. `requested == null` is the **shipped default**, not an edge case:
     * `PreferencesManager` defaults the selected language to `"auto"` and maps `"auto"` to `null`,
     * so every user who has not explicitly picked a language arrives here with a null.
     *
     * ```
     * requested != null                       -> use it directly       lang=es
     * requested == null, detection succeeded   -> the detected token    lang=auto->fr(detected)
     * requested == null, detection failed,
     *                    device locale maps    -> the locale's token    lang=auto->de(locale)
     * requested == null, neither               -> en                    lang=auto->en(fallback)
     * ```
     *
     * **Detection rather than device locale first.** The locale is a poor proxy for the language
     * being *spoken*, and precisely for this tier's audience: a multilingual user on an
     * English-locale phone is the normal case, not the exception. Guessing from the locale would
     * mis-transcribe them fluently with only a diagnostic line as consolation — the GPU-trap shape
     * this project has already paid for once. The detection pass costs one extra `graphExecute`,
     * ~4.5 ms against a ~405 ms encode.
     *
     * **English is reachable, but never silently.** The fourth row exists because the prompt needs
     * *some* language token and there is no "unknown" one; what it may not do is arrive without
     * saying so, which is why `en` from this row carries `(fallback)` and `en` from an explicit
     * selection carries the bare code.
     *
     * @param requested the user's explicit selection, or null for auto. **Must be a whisper code**
     *        — `"auto"` is not one and is refused by [WhisperTokens.langToken] like any other
     *        unknown string, because a caller that got as far as passing the literal `"auto"` has
     *        skipped the mapping that turns it into a null.
     * @param detected `QnnAsrNative.nativeDetectLanguage()`'s return: a token id, or a negative
     *        number on failure. **Any id outside the language block is treated as failure**, not
     *        trusted — that gate is [WhisperTokens.codeForToken], and it is what stops a decoder
     *        that argmaxed to `<|transcribe|>` from smuggling that id into the prompt.
     * @param deviceLocale an IETF tag such as `de-DE`, or null. Only its primary subtag is read.
     * @throws IllegalArgumentException if [requested] is non-null and not one of whisper's 99
     *         codes. Deliberate: a user who told us the answer must never be quietly overridden.
     */
    fun resolveLangToken(
        requested: String?,
        detected: Int,
        deviceLocale: String?,
    ): LangResolution {
        if (requested != null) {
            // Throws on an unknown code rather than falling back — see WhisperTokens.langToken.
            return LangResolution(WhisperTokens.langToken(requested), requested, requested)
        }
        val detectedCode = WhisperTokens.codeForToken(detected)
        if (detectedCode != null) {
            return LangResolution(detected, detectedCode, "auto->$detectedCode(detected)")
        }
        val localeCode = whisperCodeForLocale(deviceLocale)
        if (localeCode != null) {
            return LangResolution(
                WhisperTokens.langToken(localeCode), localeCode, "auto->$localeCode(locale)"
            )
        }
        return LangResolution(WhisperTokens.langToken(EN), EN, "auto->$EN(fallback)")
    }

    /** `"en"`, named once so the fallback row and its note cannot drift apart. */
    private const val EN = "en"

    /**
     * whisper's 99 codes are not quite the JDK's, and the differences are all in this tier's own
     * audience. `java.util.Locale` still normalises three languages to their pre-1989 ISO 639
     * codes on the way in, and Android reports them that way; two more are simply spelled
     * differently by whisper than by CLDR.
     *
     * Every entry here is a language the app's own picker offers, so getting one wrong is a user
     * who selected nothing, speaks Hebrew, and is transcribed as English with `(fallback)` in a log
     * they will never read.
     */
    private val LOCALE_ALIASES: Map<String, String> = mapOf(
        "iw" to "he",   // Locale("he").language == "iw" — the JDK's legacy Hebrew code
        "in" to "id",   // …and legacy Indonesian
        "ji" to "yi",   // …and legacy Yiddish
        "jv" to "jw",   // whisper spells Javanese jw; ISO 639-1 and CLDR spell it jv
        "nb" to "no",   // Bokmål is the written standard; whisper's table has no/nn, not nb
        "fil" to "tl",  // Android reports Filipino as fil; whisper's table has tl
    )

    /**
     * The whisper code for a device locale tag, or null when it maps to nothing.
     *
     * Only the **primary subtag** is read — `de-DE`, `de_DE`, `de` and `de-Latn-AT` all answer
     * `de`, because whisper's table is per-language and has no regional entries. A tag whose
     * primary subtag is not one of the 99 (`xx-XX`, `""`, a script-only tag) answers null, which
     * is the row that hands the decision to the `en` fallback — and to its `(fallback)` note.
     */
    internal fun whisperCodeForLocale(deviceLocale: String?): String? {
        if (deviceLocale.isNullOrBlank()) return null
        val primary = deviceLocale.substringBefore('-').substringBefore('_').lowercase()
        val code = LOCALE_ALIASES[primary] ?: primary
        return if (WhisperTokens.LANGUAGE_CODES.contains(code)) code else null
    }
}
