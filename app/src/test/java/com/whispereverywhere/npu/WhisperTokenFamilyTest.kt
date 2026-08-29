package com.whispereverywhere.npu

import com.whispereverywhere.data.local.PreferencesManager
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [WhisperTokenFamily] — one derivation, checked against 4.0's literals (4.1 L2).
 *
 * ### What this suite is actually asserting
 *
 * `WhisperTokens` holds `whisper-small`'s ids as **literals**, read off the shipped asset's
 * tokenizer and `generation_config.json`. `WhisperTokenFamily` computes the same ids from one
 * number — `langCount` — by the table the plan's asset block publishes. This suite compares the
 * two, exhaustively, and that comparison is what makes the formula a **second reading of the asset**
 * rather than a new source of truth. Neither side is privileged: a disagreement means one of the
 * two is wrong, and the test says which values disagree.
 *
 * ### Why a formula at all, when 4.0's literals worked
 *
 * Because a second vocabulary is arriving (4.1 L4), and the two families' control tokens sit **one
 * id apart**. The sharpest instance is asserted below by name: **50358 is `<|translate|>` under
 * `whisper-small` and `<|yue|>` — Cantonese — under `large-v3`.** Both are legal ids in both
 * vocabularies, so no per-id range check anywhere can tell which meaning is intended; only the
 * FAMILY can. That is the whole reason `NpuDecodePolicy`'s members take a family with **no
 * default**: a defaulted `SMALL` would build a turbo prompt whose task token reads as *translate*
 * to the model it is actually fed to — valid, unsuppressed, perfectly decodable, and the wrong
 * task, producing fluent text nobody asked for.
 */
class WhisperTokenFamilyTest {

    private val small = WhisperTokens.SMALL

    /**
     * `whisper-large-v3`'s layout — since 4.1 L4 this is the SHIPPED handle, the same object
     * `NpuModelSpec.TURBO.tokens` carries, not a local derivation that happens to agree with it.
     *
     * It began (4.1 L2) as a local, because a derivation exercised at exactly one input is
     * indistinguishable from a table of constants with extra steps; now that the second family is
     * a row, asserting the handle itself is the stronger claim — a drift in what the app actually
     * threads to the decoder fails here, not just a drift in the formula.
     */
    private val largeV3 = WhisperTokens.LARGE_V3

    // ---------------------------------------------------------------- the twelve ids

    /**
     * All twelve special ids, against `WhisperTokens`' literals, one assertion each.
     *
     * Twelve, not "the interesting ones": every id in this layout is `translate + k` for some small
     * k, so a single wrong offset anywhere shifts an unbroken run of them and the run's first
     * member is the only one a spot check would find.
     */
    @Test
    fun smallReproducesEveryOneOfTheTwelveSpecialIdsTheLiteralsCarry() {
        assertEquals("<|endoftext|>", WhisperTokens.EOT, small.eot)
        assertEquals("<|startoftranscript|>", WhisperTokens.SOT, small.sot)
        assertEquals("<|en|>, the first language token", WhisperTokens.LANG_FIRST, small.langFirst)
        assertEquals("<|su|>, the last", WhisperTokens.LANG_LAST, small.langLast)
        assertEquals("<|translate|>", WhisperTokens.TRANSLATE, small.translate)
        assertEquals("<|transcribe|>", WhisperTokens.TRANSCRIBE, small.transcribe)
        assertEquals("<|notimestamps|>", WhisperTokens.NO_TIMESTAMPS, small.noTimestamps)
        assertEquals("<|0.00|>, the first timestamp", WhisperTokens.TIMESTAMP_BEGIN, small.timestampBegin)
        assertEquals("the logits dimension", WhisperTokens.VOCAB, small.vocab)
        // The three with no literal of their own in WhisperTokens — they exist only inside
        // SUPPRESS, as bare numbers, which is exactly why the derivation has to name them.
        assertEquals("<|startoflm|>", 50360, small.startOfLm)
        assertEquals("<|startofprev|>", 50361, small.startOfPrev)
        assertEquals("<|nospeech|>", 50362, small.noSpeech)
    }

    /**
     * The twelve as ABSOLUTE numbers too, because the assertions above compare one reading against
     * another and would both be green if `WhisperTokens`' literals were the thing that drifted.
     *
     * These are the values the 4.0 plan's asset block publishes and the device confirmed.
     */
    @Test
    fun smallsTwelveIdsAreTheShippedAbsoluteNumbers() {
        assertEquals(50257, small.eot)
        assertEquals(50258, small.sot)
        assertEquals(50259, small.langFirst)
        assertEquals(50357, small.langLast)
        assertEquals(50358, small.translate)
        assertEquals(50359, small.transcribe)
        assertEquals(50360, small.startOfLm)
        assertEquals(50361, small.startOfPrev)
        assertEquals(50362, small.noSpeech)
        assertEquals(50363, small.noTimestamps)
        assertEquals(50364, small.timestampBegin)
        assertEquals(51865, small.vocab)
        assertEquals("and the context window it decodes in", 200, small.maxPositions)
    }

    // ---------------------------------------------------------------- the language table

    /** The 99 codes, in id order, element for element against 4.0's literal list. */
    @Test
    fun smallReproducesTheNinetyNineLanguageCodesInIdOrder() {
        assertEquals(
            "the family must carry exactly the 99 codes WhisperTokens spells out",
            WhisperTokens.LANGUAGE_CODES.size,
            small.languageCodes.size
        )
        WhisperTokens.LANGUAGE_CODES.forEachIndexed { index, code ->
            assertEquals(
                "index $index of the canonical order — the order whisper's tokenizer appended the " +
                    "tokens in, which is the order the model's embedding rows are in. It is not " +
                    "alphabetical and it is not stable under tidying.",
                code,
                small.languageCodes[index]
            )
            assertEquals(
                "and `$code` must map to 50259 + $index",
                WhisperTokens.langToken(code),
                small.langToken(code)
            )
        }
        assertEquals("index 98 is Sundanese", "su", small.languageCodes[98])
    }

    /**
     * The round trip, both directions, and the refusals at each end.
     *
     * `langToken` THROWS on an unknown code and `codeForToken` answers null outside the band. Both
     * are load-bearing: an English fallback here would make an unsupported locale
     * indistinguishable from an English one at every layer above, and a lenient `codeForToken`
     * would let a decoder that argmaxed to `<|transcribe|>` smuggle that id through as a detected
     * language.
     */
    @Test
    fun theLanguageTableIsBidirectionalAndRefusesAtBothEnds() {
        small.languageCodes.forEach { code ->
            assertEquals(code, small.codeForToken(small.langToken(code)))
        }
        assertEquals(
            "every id in the band names a distinct language",
            99,
            (small.langFirst..small.langLast).mapNotNull { small.codeForToken(it) }.toSet().size
        )
        listOf(
            small.langFirst - 1, small.langLast + 1, small.eot, small.sot, small.translate,
            small.transcribe, small.timestampBegin, small.vocab, -1, Int.MAX_VALUE, Int.MIN_VALUE
        ).forEach {
            assertNull("$it is outside the language band and must answer null", small.codeForToken(it))
        }
        listOf("auto", "", "EN", "en-US", "yue", "klingon").forEach { code ->
            try {
                val got = small.langToken(code)
                fail(
                    "langToken(\"$code\") must THROW rather than answer $got. Falling back to " +
                        "English here produces a fluent transcript in the wrong language, which " +
                        "nothing downstream can detect."
                )
            } catch (expected: IllegalArgumentException) {
                assertTrue(
                    "and the message must name the code it refused. Got: ${expected.message}",
                    expected.message.orEmpty().contains(code) || code.isEmpty()
                )
            }
        }
    }

    // ---------------------------------------------------------------- the suppression sets

    /**
     * The 88-entry `suppress_tokens` list, element for element, against `WhisperTokens.SUPPRESS`.
     *
     * The comparison is real rather than circular because the two sides are two literal
     * transcriptions: `SUPPRESS` is all 88 written out, and the family's is `BASE_SUPPRESS`'s
     * separately-written 82 plus six ids DERIVED from `langCount`. A typo in either copy fails
     * here.
     *
     * `beginSuppress` is asserted at the end of the same test rather than in one of its own,
     * because the two sets are one decision: `EOT` is absent from the always-on list ONLY because
     * it is present in the begin-step one, and separating them lets each be true on its own while
     * the pair is wrong.
     */
    @Test
    fun smallReproducesTheEightyEightEntrySuppressListElementForElement() {
        assertArrayEquals(
            "the derived suppress list must equal generation_config.json's 88 ids exactly. " +
                "expected=${WhisperTokens.SUPPRESS.toList()} got=${small.suppress.toList()}",
            WhisperTokens.SUPPRESS,
            small.suppress
        )
        assertEquals("88 entries", 88, small.suppress.size)
        assertEquals("82 of which are the shared BPE half", 82, WhisperTokens.BASE_SUPPRESS.size)
        assertTrue(
            "and every one of those 82 is below EOT — that is the split the derivation cuts on",
            WhisperTokens.BASE_SUPPRESS.all { it < small.eot }
        )
        assertTrue(
            "the six the family adds are exactly the control ids that MOVE with the language " +
                "table: sot, translate, transcribe, startOfLm, startOfPrev, noSpeech",
            small.suppress.toSet() - WhisperTokens.BASE_SUPPRESS.toSet() ==
                setOf(
                    small.sot, small.translate, small.transcribe,
                    small.startOfLm, small.startOfPrev, small.noSpeech
                )
        )
        assertTrue(
            "EOT is deliberately ABSENT from the always-on list — it belongs to beginSuppress, and " +
                "masking it at every step removes the loop's only terminator short of the cap",
            !small.suppress.contains(small.eot)
        )
        assertTrue(
            "ascending and duplicate-free is a NATIVE precondition, not tidiness: the mask loop " +
                "writes logits[id] for each entry without re-validating",
            small.suppress.toList() == small.suppress.toList().sorted() &&
                small.suppress.size == small.suppress.toSet().size
        )
        // …and its one-step twin, asserted here rather than in a suite of its own because the two
        // sets are one decision: EOT is absent from the always-on list ONLY because it is present
        // in this one, and splitting them lets the pair be true separately and wrong together.
        assertArrayEquals(intArrayOf(220, 50257), small.beginSuppress)
        assertArrayEquals(
            "the pair does not move with the language table — 220 is a BPE id and EOT is below it",
            intArrayOf(220, 50257),
            largeV3.beginSuppress
        )
        assertArrayEquals(
            "and it is the same pair WhisperTokens' literal carries",
            WhisperTokens.BEGIN_SUPPRESS,
            small.beginSuppress
        )
    }

    // ---------------------------------------------------------------- the second family

    /**
     * **The derivation, exercised at a second `langCount` — and the collision that makes a
     * defaulted family a wrong-task bug rather than a wrong-number one.**
     *
     * `50358` is `<|translate|>` in `whisper-small` and `<|yue|>` — Cantonese — in `large-v3`.
     * Both are legal, unsuppressed, perfectly decodable ids in the vocabulary that contains them,
     * so **no per-id range check can distinguish them**: a `50358` in a prompt's task slot is a
     * valid task token to one model and a valid language token to the other. Only the family knows
     * which. That is why `NpuDecodePolicy`'s members take one with no default (4.1 L2 step 7b), and
     * it is the assertion the plan's certification round caught inverted.
     */
    @Test
    fun aSecondLanguageTableShiftsEveryControlIdByOneAndCollidesWithTheFirst() {
        assertEquals(100, largeV3.langCount)
        assertEquals("<|yue|> is index 99 of the canonical order", "yue", largeV3.languageCodes[99])
        assertEquals("…and small's table stops one short of it", 99, small.languageCodes.size)

        // The three ids BELOW the divergence do not move — the boundary the L2 micro-round
        // corrected: whisper-small's table is a strict PREFIX, so every id the two share agrees.
        assertEquals("<|endoftext|> sits below the table in every family", 50257, largeV3.eot)
        assertEquals("<|startoftranscript|> likewise", 50258, largeV3.sot)
        assertEquals("and both bands start at <|en|>", 50259, largeV3.langFirst)
        assertEquals("turbo's mask is the same [1,1,1,200]", 200, largeV3.maxPositions)

        assertEquals("the band is one wider", 50358, largeV3.langLast)
        assertEquals(50359, largeV3.translate)
        assertEquals(50360, largeV3.transcribe)
        assertEquals(50361, largeV3.startOfLm)
        assertEquals(50362, largeV3.startOfPrev)
        assertEquals(50363, largeV3.noSpeech)
        assertEquals(50364, largeV3.noTimestamps)
        assertEquals(50365, largeV3.timestampBegin)
        assertEquals("51,866 — large-v3/turbo, NOT 51,865", 51866, largeV3.vocab)

        assertEquals(
            "50358 is <|translate|> under whisper-small…",
            50358,
            small.translate
        )
        assertEquals(
            "…and <|yue|>, Cantonese, under large-v3. Same integer, two meanings, both legal in " +
                "the vocabulary that carries them: a per-id check cannot separate them and a " +
                "defaulted family would pick the wrong one silently.",
            "yue",
            largeV3.codeForToken(50358)
        )
        assertNull(
            "and under whisper-small that same id is not a language at all",
            small.codeForToken(50358)
        )
        assertEquals(
            "the base BPE half is SHARED — only the six above EOT move",
            largeV3.suppress.toSet() - WhisperTokens.BASE_SUPPRESS.toSet(),
            setOf(
                largeV3.sot, largeV3.translate, largeV3.transcribe,
                largeV3.startOfLm, largeV3.startOfPrev, largeV3.noSpeech
            )
        )
        assertEquals("…so its suppress list is 88 entries too", 88, largeV3.suppress.size)
    }

    /**
     * The `LARGE_V3` table is bidirectional over all 100 codes, and it is the small table plus
     * exactly one entry.
     *
     * The cross-reading is against [WhisperTokens.LANGUAGE_CODES] — 4.0's literal transcription —
     * plus `"yue"`, not against [WhisperTokenFamily.CANONICAL_LANGUAGE_CODES], which is what
     * `languageCodes` is *built from* and would make the comparison a list against itself.
     */
    @Test
    fun theLargeV3TableCarriesTheHundredCodesInIdOrderAndRoundTrips() {
        assertEquals(100, largeV3.languageCodes.size)
        assertEquals(
            "the 100 codes must be 4.0's literal 99 plus `yue` appended — the same order, one " +
                "longer. Any other relationship between the two tables means one of them prompts " +
                "some language with another language's embedding row.",
            WhisperTokens.LANGUAGE_CODES + listOf("yue"),
            largeV3.languageCodes
        )
        largeV3.languageCodes.forEach { code ->
            assertEquals(code, largeV3.codeForToken(largeV3.langToken(code)))
        }
        assertEquals(
            "every id in the band names a distinct language",
            100,
            (largeV3.langFirst..largeV3.langLast).mapNotNull { largeV3.codeForToken(it) }
                .toSet().size
        )
        assertEquals("`yue` maps to the id whisper-small uses for <|translate|>",
            50358, largeV3.langToken("yue"))
        listOf(
            largeV3.langFirst - 1, largeV3.langLast + 1, largeV3.eot, largeV3.sot,
            largeV3.translate, largeV3.timestampBegin, largeV3.vocab, -1
        ).forEach {
            assertNull("$it is outside this family's band and must answer null",
                largeV3.codeForToken(it))
        }
    }

    /**
     * **Both shipped families' boundary ids, pinned as absolute numbers in one place** (4.1 L4,
     * step 6). Everything else in this suite compares readings against each other; these six are
     * the anchor a symmetrical drift cannot survive — if both derivations moved together, the
     * comparisons above stay green and these go red.
     */
    @Test
    fun bothShippedFamiliesBoundaryIdsArePinnedExplicitly() {
        assertEquals(50259, WhisperTokens.SMALL.langFirst)
        assertEquals("<|su|>", 50357, WhisperTokens.SMALL.langLast)
        assertEquals(50259, WhisperTokens.LARGE_V3.langFirst)
        assertEquals("<|yue|>", 50358, WhisperTokens.LARGE_V3.langLast)
        assertEquals(51865, WhisperTokens.SMALL.vocab)
        assertEquals(51866, WhisperTokens.LARGE_V3.vocab)
        assertEquals(
            "one added language, one wider logits layer — the whole difference between the " +
                "families is this single integer",
            WhisperTokens.LARGE_V3.vocab - WhisperTokens.SMALL.vocab,
            WhisperTokens.LARGE_V3.langCount - WhisperTokens.SMALL.langCount
        )
    }

    /**
     * The two suppress lists share their 82-id BPE base **element for element** and differ by
     * exactly one at each of the five control ids that move.
     *
     * `sot` is the sixth derived id and deliberately NOT in the moving five: it sits below the
     * language table, so it is 50258 in both. Asserting the shift per id — rather than as a set
     * difference — is what catches a list that moved the wrong id by one.
     */
    @Test
    fun theSuppressListsShareTheBaseAndShiftTheFiveMovingControlIdsByOne() {
        val smallList = WhisperTokens.SMALL.suppress
        val largeList = WhisperTokens.LARGE_V3.suppress
        assertEquals("both are 88 entries", 88, smallList.size)
        assertEquals(88, largeList.size)
        assertArrayEquals(
            "the 82 base BPE ids — everything below EOT — must be identical element for element; " +
                "this half was MEASURED identical across the two published families at plan time",
            smallList.filter { it < WhisperTokens.SMALL.eot }.toIntArray(),
            largeList.filter { it < WhisperTokens.LARGE_V3.eot }.toIntArray()
        )
        assertEquals("sot does not move — it sits below the language table",
            WhisperTokens.SMALL.sot, WhisperTokens.LARGE_V3.sot)
        listOf(
            "translate" to Pair(WhisperTokens.SMALL.translate, WhisperTokens.LARGE_V3.translate),
            "transcribe" to Pair(WhisperTokens.SMALL.transcribe, WhisperTokens.LARGE_V3.transcribe),
            "startOfLm" to Pair(WhisperTokens.SMALL.startOfLm, WhisperTokens.LARGE_V3.startOfLm),
            "startOfPrev" to Pair(WhisperTokens.SMALL.startOfPrev, WhisperTokens.LARGE_V3.startOfPrev),
            "noSpeech" to Pair(WhisperTokens.SMALL.noSpeech, WhisperTokens.LARGE_V3.noSpeech),
        ).forEach { (name, ids) ->
            assertEquals(
                "$name must shift by EXACTLY one — the one added language moves every control " +
                    "id above the band by the same single slot",
                ids.first + 1,
                ids.second
            )
            assertTrue(
                "…and both families must suppress their own $name",
                smallList.contains(ids.first) && largeList.contains(ids.second)
            )
        }
        assertTrue(
            "50358 is suppressed under whisper-small (<|translate|>, a control token) and NOT " +
                "under large-v3 (<|yue|>, a language — languages are deliberately unsuppressed " +
                "because the detect pass argmaxes over them). The same integer, muted by one " +
                "family and required live by the other: nothing per-id can reconcile that, only " +
                "the family.",
            smallList.contains(50358) && !largeList.contains(50358)
        )
    }

    // ---------------------------------------------------------------- the refusals

    /**
     * The family refuses a shape it cannot describe, at construction, before anything reads it.
     *
     * `langCount` past the canonical table would index off the end of a list whose ORDER is the
     * model's embedding order; `maxPositions` below 2 is a decode with no position to generate in,
     * and past 1024 is a self-KV allocation nothing on this device would satisfy.
     */
    @Test
    fun aFamilyShapeThisTableCannotDescribeIsRefusedAtConstruction() {
        listOf(0, -1, 101, Int.MAX_VALUE).forEach { bad ->
            try {
                val got = WhisperTokenFamily(langCount = bad, maxPositions = 200)
                fail("langCount $bad must be refused; got a family claiming vocab ${got.vocab}")
            } catch (expected: IllegalArgumentException) {
                assertTrue(
                    "the refusal must name the bound. Got: ${expected.message}",
                    expected.message.orEmpty().contains("langCount")
                )
            }
        }
        listOf(0, 1, -1, 1025, Int.MAX_VALUE).forEach { bad ->
            try {
                val got = WhisperTokenFamily(langCount = 99, maxPositions = bad)
                fail("maxPositions $bad must be refused; got ${got.maxPositions}")
            } catch (expected: IllegalArgumentException) {
                assertTrue(
                    "the refusal must name the bound. Got: ${expected.message}",
                    expected.message.orEmpty().contains("maxPositions")
                )
            }
        }
        assertEquals(
            "and the canonical table is the 100 whisper publishes — 99 for small, 100 for large-v3",
            100,
            WhisperTokenFamily.CANONICAL_LANGUAGE_CODES.size
        )
    }

    // ---------------------------------------------------------------- the census alarm

    /**
     * THE CENSUS ALARM, extended across BOTH shipped families (4.1 L2 re-homed it here; 4.1 L4
     * makes it two-family).
     *
     * `NpuDecodePolicyTest` runs the same alarm against `WhisperTokens`' literal table and that
     * one stays: these are independent readings and the alarm has to hold for all of them, or the
     * tier resolves a picker language through one and not another. The 4.0 lesson it defends: a
     * partial table passes every spot-check of the common five while transcribing an
     * explicitly-selected Slovenian user as English — and with two families there are now two ways
     * to have that failure. A code the picker offers that ONE family cannot name is a user whose
     * language works on one npu tier and silently becomes English on the other.
     */
    @Test
    fun everyLanguageTheAppsPickerOffersResolvesThroughBothShippedFamilies() {
        val offered = PreferencesManager.SUPPORTED_LANGUAGES.map { it.first }.filter { it != "auto" }
        assertEquals(
            "the picker offers 55 entries — 54 real languages plus \"auto\". The long tail " +
                "(sl, et, sr, hr, tl) is exactly what a hand-typed table drops and a truncated " +
                "derivation drops the tail of the canonical order instead.",
            54,
            offered.size
        )
        offered.forEach { code ->
            assertNotNull(
                "the picker offers `$code` but the SMALL family cannot map it to a <|xx|> token",
                small.codeForToken(small.langToken(code))
            )
            assertNotNull(
                "the picker offers `$code` but the LARGE_V3 family cannot map it to a <|xx|> " +
                    "token — the silent-wrong-language failure, arriving through the second tier",
                largeV3.codeForToken(largeV3.langToken(code))
            )
        }
        assertTrue(
            "and the two families must agree on the TOKEN for every offered code — the shared " +
                "table is a strict prefix, so a shared code maps to the same id in both, and a " +
                "disagreement means one family's table is not in canonical order",
            offered.all { small.langToken(it) == largeV3.langToken(it) }
        )
    }
}
