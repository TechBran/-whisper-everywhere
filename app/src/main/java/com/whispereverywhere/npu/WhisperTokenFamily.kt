package com.whispereverywhere.npu

/**
 * One whisper vocabulary's token layout, derived from the size of its language table (4.1 L2).
 *
 * ### Why a derivation and not a second table of literals
 *
 * Whisper's special ids are not arbitrary: they are appended to the BPE vocabulary in a fixed order
 * — `<|endoftext|>`, `<|startoftranscript|>`, the language tokens, then six control tokens, then
 * 1,501 timestamps — so **every id above the language table is a function of how many languages
 * there are**. `whisper-small` has 99 and `large-v3` has 100, and that single difference shifts
 * seven ids and the vocabulary size by exactly one.
 *
 * That off-by-one is the whole reason this class exists, because of what it does *silently*:
 *
 * ```
 *              whisper-small        large-v3 / turbo
 *   50356      <|jw|>               <|jw|>           the tables agree up to here…
 *   50357      <|su|>               <|su|>           …including whisper-small's LAST language
 *   50358      <|translate|>        <|yue|>          …and diverge at the first id past it
 *   50359      <|transcribe|>       <|translate|>
 *   50360      <|startoflm|>        <|transcribe|>
 * ```
 *
 * Read the boundary carefully, because the obvious reading of it is wrong. The two tables are
 * **identical for every id they share** — `whisper-small`'s table is a strict PREFIX of
 * `large-v3`'s, so `<|jw|>` is 50356 and `<|su|>` is 50357 in both. The divergence begins at the
 * first id *past* whisper-small's last language: 50358, where one family has appended a 100th
 * language (`<|yue|>`) and the other has already moved on to its control tokens. Provenance, read
 * out of the vendored `whisper.cpp` `g_lang` table rather than inferred: `jw` is index 97, `su` is
 * 98, `yue` is 99 — so `50259 + index` puts them exactly there.
 *
 * **The same integer is a task token in one family and a language token in the other, and both are
 * legal.** A prompt built with `whisper-small`'s `<|transcribe|>` (50359) and fed to a `large-v3`
 * decoder puts that model in TRANSLATE mode: no id is out of range, nothing is suppressed that
 * should not be, no bounds check anywhere can fire, and the user receives a fluent translation of
 * words they wanted transcribed. There is no per-id validation that catches this — only knowing
 * which family the ids belong to. Hence [NpuDecodePolicy]'s required, un-defaulted `family`
 * parameter, and hence this type.
 *
 * ### Two readings, and neither is privileged
 *
 * [WhisperTokens] keeps 4.0's literals — every id, all 99 codes, all 88 suppressed tokens — written
 * out as they were read off the shipped asset. This class computes the same values. `WhisperTokens`
 * is **not** refactored into a call to this class, deliberately: `WhisperTokenFamilyTest` compares
 * the two id by id and entry by entry, and that comparison is worth nothing if one side is defined
 * as the other. It is the same discipline as native's `kEotToken` beside Kotlin's
 * [WhisperTokens.EOT] — two independent transcriptions of one asset fact, and a disagreement that
 * is visible instead of silent.
 *
 * @param langCount how many `<|xx|>` tokens this vocabulary carries — 99 for `whisper-small`, 100
 *        for `large-v3` and `large-v3-turbo`. Every id above the table is derived from it.
 * @param maxPositions the decoder's context window, i.e. `attention_mask`'s width. **It lives here
 *        rather than on [NpuModelSpec] because it is the decode loop's bound**, and the decode
 *        loop's configuration ([NpuDecodePolicy]) is family-parametrised: putting it on the spec
 *        would make `maxTokensFor` the one question in this design that took two objects to answer.
 *        The spec reads it back through [NpuModelSpec.maxPositions], so there is one home for it.
 */
class WhisperTokenFamily(
    val langCount: Int,
    val maxPositions: Int,
) {

    init {
        require(langCount in 1..CANONICAL_LANGUAGE_CODES.size) {
            "langCount $langCount is outside 1..${CANONICAL_LANGUAGE_CODES.size}. The codes are " +
                "taken as a PREFIX of whisper's canonical order, which is the order the model's " +
                "embedding rows are in, so a count past the end of that table has no codes to name " +
                "and a count below 1 has no language slot for the prompt to fill."
        }
        require(maxPositions in 2..1024) {
            "maxPositions $maxPositions is outside 2..1024. Below 2 there is no position left to " +
                "generate in once the prompt is fed; above 1024 the self-KV allocation this implies " +
                "is larger than any published whisper decoder's, which means the number is a typo " +
                "rather than a context window."
        }
    }

    // ---------------------------------------------------------------- the twelve ids

    /** `<|endoftext|>` — below the language table, so it is the same in every family. */
    val eot: Int = EOT

    /** `<|startoftranscript|>` — likewise fixed, and prompt token 0. */
    val sot: Int = SOT

    /** `<|en|>` — the table always starts here. */
    val langFirst: Int = LANG_FIRST

    /** The last `<|xx|>`: `<|su|>` at 50357 for 99 languages, `<|yue|>` at 50358 for 100. */
    val langLast: Int = LANG_FIRST + langCount - 1

    /** `<|translate|>`. Named so it can be asserted ABSENT: no npu tier translates. */
    val translate: Int = LANG_FIRST + langCount

    /** `<|transcribe|>` — prompt token 2, and one above [translate] in every family. */
    val transcribe: Int = translate + 1

    /** `<|startoflm|>`. Suppressed, never prompted. */
    val startOfLm: Int = translate + 2

    /** `<|startofprev|>`. Suppressed; this tier does not carry previous-segment context. */
    val startOfPrev: Int = translate + 3

    /** `<|nospeech|>`. Suppressed — a silent segment must terminate on EOT, not announce itself. */
    val noSpeech: Int = translate + 4

    /** `<|notimestamps|>` — prompt token 3, and the reason a timestamp is a decode fault. */
    val noTimestamps: Int = translate + 5

    /** `<|0.00|>`. Every id from here to [vocab]` - 1` is a timestamp: [TIMESTAMP_SLOTS] of them. */
    val timestampBegin: Int = translate + 6

    /** The decoder's `logits` dimension — 51,865 for `whisper-small`, 51,866 for `large-v3`. */
    val vocab: Int = timestampBegin + TIMESTAMP_SLOTS

    // The third scalar bound, and it is HERE rather than in the init block above because Kotlin
    // runs initialisers in declaration order: read one line earlier, `vocab` is still 0 and the
    // check would pass on every input. It bounds what native's uint16 argmax scans, so it belongs
    // to the same refusal table as the two above even though it is derived rather than given.
    init {
        require(vocab in 1..65535) {
            "vocab $vocab (from langCount $langCount) is outside 1..65535. It bounds a uint16 " +
                "argmax over the logits buffer, so a value past 65,535 is a scan past the end of " +
                "an allocation native sized from the same number."
        }
    }

    // ---------------------------------------------------------------- the language table

    /**
     * This family's codes **in id order**: `langToken(languageCodes[i]) == 50259 + i`.
     *
     * A PREFIX of [CANONICAL_LANGUAGE_CODES], never a filtered or re-sorted copy. The order is the
     * order whisper's own tokenizer appended the tokens in, which is the order the model's
     * embedding rows are in; a table that is right as a set and wrong as a sequence prompts every
     * language with some other language's embedding row and transcribes fluently under it.
     */
    val languageCodes: List<String> = CANONICAL_LANGUAGE_CODES.subList(0, langCount).toList()

    private val tokenByCode: Map<String, Int> =
        languageCodes.withIndex().associate { (index, code) -> code to LANG_FIRST + index }

    /**
     * The `<|xx|>` id for a whisper language code.
     *
     * **Throws on an unknown code**, exactly as [WhisperTokens.langToken] does and for the same
     * reason: returning English would make an unsupported locale indistinguishable from an English
     * one at every layer above, and the symptom is a fluent transcript in the wrong language.
     * `"auto"` is not a whisper code and is refused like any other unknown string.
     */
    fun langToken(code: String): Int = tokenByCode[code] ?: throw IllegalArgumentException(
        "'$code' is not one of this family's $langCount language codes. It cannot be mapped to a " +
            "<|lang|> token, and falling back to English here would produce a fluent transcript in " +
            "the wrong language rather than an error."
    )

    /**
     * The code for a `<|xx|>` id, or `null` outside `langFirst..langLast`.
     *
     * The null is the gate on the detect pass: a decoder that argmaxed to a control token must not
     * be able to smuggle that id through as a "detected language". Note that the band's width is
     * per-family, which is the point — 50358 answers `null` here for `whisper-small` and `"yue"`
     * for `large-v3`.
     */
    fun codeForToken(id: Int): String? =
        if (id in langFirst..langLast) languageCodes[id - langFirst] else null

    // ---------------------------------------------------------------- the suppression sets

    /**
     * `generation_config.json`'s `suppress_tokens` for this family — ascending, duplicate-free.
     *
     * [WhisperTokens.BASE_SUPPRESS]'s 82 BPE ids (identical across both published families —
     * measured, not assumed) plus the six control ids that MOVE: `sot`, `translate`, `transcribe`,
     * `startOfLm`, `startOfPrev`, `noSpeech`. [eot] is deliberately absent; it belongs to
     * [beginSuppress], and masking it at every step would leave the decode loop no terminator short
     * of the position cap.
     *
     * **`by lazy`, and that is structural rather than an optimisation.** [WhisperTokens.SMALL] IS a
     * `WhisperTokenFamily`, so an eager read of [WhisperTokens.BASE_SUPPRESS] here would make the
     * two initialisers mutually dependent — which happens to work today only because of the order
     * the declarations are in, i.e. safe by a property of a different object, which is the shape
     * this branch has paid for repeatedly. Deferring the read breaks the cycle outright.
     */
    val suppress: IntArray by lazy {
        (WhisperTokens.BASE_SUPPRESS.toList() +
            listOf(sot, translate, transcribe, startOfLm, startOfPrev, noSpeech))
            .distinct().sorted().toIntArray()
    }

    /**
     * `[220, eot]` — applied at the **first generated step only**, which is
     * `position == promptLen - 1` and not `position == 0`.
     *
     * 220 is the leading-space BPE token and [eot] sits below the language table, so this pair is
     * the same in both published families. It is still asked per family rather than assumed: a
     * value that agrees today is a coincidence, not a constant, and nothing obliges the next
     * vocabulary to preserve it.
     */
    val beginSuppress: IntArray = intArrayOf(LEADING_SPACE, eot)

    override fun toString(): String =
        "WhisperTokenFamily(langCount=$langCount, maxPositions=$maxPositions, vocab=$vocab)"

    companion object {

        /** `<|endoftext|>` — below the language table in every published family. */
        const val EOT: Int = 50257

        /** `<|startoftranscript|>` — likewise. */
        const val SOT: Int = 50258

        /** `<|en|>` — every family's table starts at the same id. */
        const val LANG_FIRST: Int = 50259

        /** `<|0.00|>` through `<|30.00|>` at whisper's 0.02 s granularity: 1,501 ids. */
        const val TIMESTAMP_SLOTS: Int = 1501

        /** The leading-space BPE token. A transcript must not open with a space. */
        const val LEADING_SPACE: Int = 220

        /**
         * Whisper's **canonical language order, all 100 of it** — index 0..99, and it is a
         * sequence rather than a set.
         *
         * PROVENANCE: the vendored `app/src/main/cpp/whisper.cpp/src/whisper.cpp` `g_lang` table,
         * read directly. Index 98 is `su` (Sundanese) and index 99 is `yue` (Cantonese), both
         * verified in that source; `yue` is the entry `large-v3` added and `whisper-small` does not
         * have, which is the entire difference between the two families.
         *
         * A family takes the first `langCount` of these. That is what makes `whisper-small`'s table
         * a strict prefix of `large-v3`'s rather than a separate list that happens to agree — and
         * it is why adding a language to this table is a change to BOTH families' id layouts, which
         * `WhisperTokenFamilyTest` will report as twelve moved ids rather than as one new code.
         */
        val CANONICAL_LANGUAGE_CODES: List<String> = listOf(
            "en", "zh", "de", "es", "ru", "ko", "fr", "ja", "pt", "tr",
            "pl", "ca", "nl", "ar", "sv", "it", "id", "hi", "fi", "vi",
            "he", "uk", "el", "ms", "cs", "ro", "da", "hu", "ta", "no",
            "th", "ur", "hr", "bg", "lt", "la", "mi", "ml", "cy", "sk",
            "te", "fa", "lv", "bn", "sr", "az", "sl", "kn", "et", "mk",
            "br", "eu", "is", "hy", "ne", "mn", "bs", "kk", "sq", "sw",
            "gl", "mr", "pa", "si", "km", "sn", "yo", "so", "af", "oc",
            "ka", "be", "tg", "sd", "gu", "am", "yi", "lo", "uz", "fo",
            "ht", "ps", "tk", "nn", "mt", "sa", "lb", "my", "bo", "tl",
            "mg", "as", "tt", "haw", "ln", "ha", "ba", "jw", "su", "yue"
        )
    }
}
