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
 *
 * ### Every member takes a [WhisperTokenFamily], and none of them defaults it (4.1 L2)
 *
 * A default would be a convenience with one consequence: a call site written for a second npu tier
 * would silently build its prompt out of `whisper-small`'s ids. Under `large-v3` those ids mean
 * something else — `50359` is `<|transcribe|>` in one family and `<|translate|>` in the other, and
 * `50358` is `<|translate|>` in one and `<|yue|>` (Cantonese) in the other. Every one of them is a
 * legal, unsuppressed, perfectly decodable token in the vocabulary that contains it, so **no
 * per-id check anywhere can tell the two apart**: the model simply runs the task it was asked for
 * and returns fluent text nobody wanted. Only the family knows which meaning is intended, so the
 * family is required, everywhere, with no default — see [WhisperTokenFamily].
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
     * @param family the vocabulary these ids belong to. Required, never defaulted — see the
     *        object KDoc for the wrong-task failure a default produces.
     * @throws IllegalArgumentException if [languageCode] is not one of [family]'s codes. There is
     *         no English fallback here, deliberately: see [WhisperTokenFamily.langToken].
     */
    fun promptTokens(family: WhisperTokenFamily, languageCode: String): IntArray =
        promptTokens(family, family.langToken(languageCode))

    /**
     * The same four-token prompt, built from an already-resolved `<|xx|>` **token id**.
     *
     * This is the overload the tier actually calls, because [resolveLangToken] answers in ids: the
     * auto-detect path's answer *is* an id (`nativeDetectLanguage` argmaxes over the language
     * block), and routing it back through a code and forward through [WhisperTokenFamily.langToken]
     * would be two lookups that can disagree.
     *
     * @throws IllegalArgumentException if [langToken] is outside [family]'s own language band.
     *         The band is per-family and that is the point: 50358 is a language token under
     *         `large-v3` and a TASK token under `whisper-small`, so the same id is accepted here by
     *         one family and refused by the other. The language slot of the prompt is not a place a
     *         stray id may land: `<|transcribe|>` or a timestamp there is not a crash and not
     *         garbage — the model reads whatever embedding row it points at and produces fluent
     *         text under it.
     */
    fun promptTokens(family: WhisperTokenFamily, langToken: Int): IntArray {
        require(family.codeForToken(langToken) != null) {
            "$langToken is not a <|xx|> language token of this family (${family.langFirst}.." +
                "${family.langLast}). Putting a non-language id in the prompt's language slot does " +
                "not fail: the decoder reads that embedding row and transcribes under it."
        }
        return intArrayOf(
            family.sot,
            langToken,
            family.transcribe,
            family.noTimestamps
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
    fun suppressList(family: WhisperTokenFamily): IntArray =
        (family.suppress.toList() + (family.timestampBegin until family.vocab))
            .distinct().sorted().toIntArray()

    /**
     * `[220, 50257]` — applied at the **first generated step only** (`position == promptLen - 1`),
     * which is not `position == 0`. At position 0 the model is still being fed the prompt and its
     * argmax is discarded.
     */
    fun beginSuppressList(family: WhisperTokenFamily): IntArray = family.beginSuppress

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
    fun maxTokensFor(family: WhisperTokenFamily, promptLen: Int): Int {
        require(promptLen in 1 until family.maxPositions) {
            "promptLen $promptLen is outside 1..${family.maxPositions - 1}: a prompt that fills " +
                "the whole ${family.maxPositions}-position context leaves nothing to generate, " +
                "and a non-positive budget returns zero tokens, which is indistinguishable from " +
                "silence."
        }
        return family.maxPositions - promptLen
    }

    // ---------------------------------------------------------------- the decode guards (4.3.1 A)

    /**
     * whisper.cpp's three quality gates, as DATA handed to `nativeDecodeSegment` beside the
     * suppress lists — the same shape, for the same reason: native applies them because only the
     * loop can act on them (a rung must be re-run; a runaway must be stopped mid-loop), and Kotlin
     * owns the numbers so they are pinned where a JVM test can read them and there is exactly one
     * copy. Values are `whisper_full_default_params`' (whisper.cpp:6235-6238) — the CPU tier's.
     *
     * Why the NPU tier needs them at all: its loop was a bare greedy argmax with two terminators,
     * EOT or the 196-token budget. A greedy decode that enters a cycle cannot leave it — the argmax
     * is deterministic — so it ran to the budget ("one word × 70-80", owner 2026-09-01), and
     * `<|nospeech|>` was masked but never READ, so dead-time segments typed "Thank you."
     */
    /** Entropy of the last [ENTROPY_WINDOW] token ids below this is a repetition loop. */
    const val ENTROPY_THOLD = 2.4f
    /** Mean per-token log-probability below this is a low-confidence rung. */
    const val LOGPROB_THOLD = -1.0f
    /** `p(<|nospeech|>)` at the SOT step above this says the segment is silence. */
    const val NO_SPEECH_THOLD = 0.6f
    /** `whisper_sequence_score`'s n: the entropy is over the last 32 ids (whisper.cpp:6885). */
    const val ENTROPY_WINDOW = 32
    /** The fallback ladder, `temperature = 0` then `+= temperature_inc` (whisper.cpp:7134). */
    val TEMPERATURES: FloatArray = floatArrayOf(0.0f, 0.2f, 0.4f, 0.6f, 0.8f, 1.0f)

    /**
     * whisper.cpp:7865, both comparisons strict. NaN or a negative sentinel (native could not read
     * the logits' scale, so no probability was computed) answers false: a guard that cannot
     * measure must not blank a segment.
     */
    fun isNoSpeech(noSpeechProb: Float, avgLogprob: Float): Boolean =
        noSpeechProb > NO_SPEECH_THOLD && avgLogprob < LOGPROB_THOLD

    // ---------------------------------------------------------------- the language policy (NEW-C2)

    /**
     * How a language was arrived at. **The provenance is a field, not a spelling of the note**, so
     * that the one consumer who must branch on it — the `detectedLanguage(ctx)` seam — branches on
     * a closed set the compiler checks rather than on a string it has to parse.
     *
     * The distinction it exists to keep is the difference between an ANSWER and a GUESS.
     * [SELECTED] and [DETECTED] are answers: a user said so, or the model did. [LOCALE] and
     * [FALLBACK] are guesses this tier makes because the prompt needs *some* language token and
     * whisper has no "unknown" one — and a guess must never be reported upward as a detection.
     */
    enum class LangSource { SELECTED, DETECTED, LOCALE, FALLBACK }

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
     * @param code the whisper language code for [token].
     * @param note the `lang=` field of the `npu:` diag line. Exactly one of four shapes; see
     *        [resolveLangToken].
     * @param source the provenance. See [reportable] — this is the field that stops a guess being
     *        laundered into a session-wide pin.
     */
    data class LangResolution(
        val token: Int,
        val code: String,
        val note: String,
        val source: LangSource,
    ) {
        /**
         * The code that may cross the `WhisperBackend.detectedLanguage(ctx)` seam, or **null when
         * this tier merely guessed**.
         *
         * WHY THE GATE EXISTS, in the words of the failure it prevents. `LocalWhisperEngine` calls
         * `detectedLanguage(ctx)` after a non-blank auto-language segment and feeds the answer to
         * `LanguagePin.onDetected`, which latches the **first** usable code and never revises it.
         * `lang == null` is this policy's own shipped premise, so that path is the normal one. Let a
         * `(fallback)` guess through and a single failed detect pass on segment 1 — one
         * `graphExecute` hiccup, one near-silent utterance — becomes a session-wide pin on English:
         * every later segment is prompted `en`, the detect pass stops running at all, and the diag
         * line starts printing the **bare** `en` note that this file's own tests assert means *the
         * user chose English*. The policy's central invariant, enforced carefully one call earlier
         * and then thrown away.
         *
         * `null` is exactly what `LanguagePin.onDetected`'s `isNullOrBlank()` branch is for, and it
         * restores `WhisperBackend.detectedLanguage`'s own documented meaning — "ISO code whisper
         * auto-detected" — which a device-locale guess does not satisfy.
         *
         * [SELECTED][LangSource.SELECTED] reports the code and is not a leak: `WhisperNativeBackend`
         * answers the same way (whisper sets `lang_id` to the language it was told to use), and the
         * engine's own guard means this value is only ever *read* on an auto session, where the
         * resolution cannot be `SELECTED` in the first place. It is here so the member means the
         * same thing on both tiers.
         */
        val reportable: String?
            get() = when (source) {
                LangSource.SELECTED, LangSource.DETECTED -> code
                LangSource.LOCALE, LangSource.FALLBACK -> null
            }
    }

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
     * @param family the vocabulary every id here belongs to. Required, never defaulted.
     * @param requested the user's explicit selection, or null for auto. **Must be a whisper code**
     *        — `"auto"` is not one and is refused by [WhisperTokenFamily.langToken] like any other
     *        unknown string, because a caller that got as far as passing the literal `"auto"` has
     *        skipped the mapping that turns it into a null.
     * @param detected `QnnAsrNative.nativeDetectLanguage()`'s return: a token id, or a negative
     *        number on failure. **Any id outside the language block is treated as failure**, not
     *        trusted — that gate is [WhisperTokenFamily.codeForToken], and it is what stops a decoder
     *        that argmaxed to `<|transcribe|>` from smuggling that id into the prompt.
     * @param deviceLocale an IETF tag such as `de-DE`, or null. Only its primary subtag is read.
     * @throws IllegalArgumentException if [requested] is non-null and not one of whisper's 99
     *         codes. Deliberate: a user who told us the answer must never be quietly overridden.
     */
    fun resolveLangToken(
        family: WhisperTokenFamily,
        requested: String?,
        detected: Int,
        deviceLocale: String?,
    ): LangResolution {
        if (requested != null) {
            // Throws on an unknown code rather than falling back — see WhisperTokenFamily.langToken.
            return LangResolution(
                family.langToken(requested), requested, requested, LangSource.SELECTED
            )
        }
        val detectedCode = family.codeForToken(detected)
        if (detectedCode != null) {
            return LangResolution(
                detected, detectedCode, "auto->$detectedCode(detected)", LangSource.DETECTED
            )
        }
        val localeCode = whisperCodeForLocale(family, deviceLocale)
        if (localeCode != null) {
            return LangResolution(
                family.langToken(localeCode), localeCode, "auto->$localeCode(locale)",
                LangSource.LOCALE
            )
        }
        return LangResolution(
            family.langToken(EN), EN, "auto->$EN(fallback)", LangSource.FALLBACK
        )
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
    internal fun whisperCodeForLocale(
        family: WhisperTokenFamily,
        deviceLocale: String?,
    ): String? {
        if (deviceLocale.isNullOrBlank()) return null
        val primary = deviceLocale.substringBefore('-').substringBefore('_').lowercase()
        val code = LOCALE_ALIASES[primary] ?: primary
        return if (family.languageCodes.contains(code)) code else null
    }
}
