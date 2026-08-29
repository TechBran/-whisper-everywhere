package com.whispereverywhere.npu

import com.whispereverywhere.data.local.PreferencesManager
import com.whispereverywhere.transcription.LanguagePin
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The decode configuration the native loop consumes: the prompt, the two suppression sets, the
 * token budget, and the 99-entry language table.
 *
 * WHY THESE ARE JVM TESTS AND NOT DEVICE TESTS. Everything here is an INPUT to
 * `nativeDecodeSegment` — an `IntArray` handed across JNI. If it is wrong, the decoder does not
 * crash and does not fail: it produces fluent, confident, **wrong** text, which is the one failure
 * shape a single-sentence acceptance run cannot see. That makes these arrays exactly the part of
 * the tier that must be pinned on a machine with no NPU in it, months before Q10a.
 *
 * `NpuDecodePolicy` and `WhisperTokens` are pure Kotlin and deliberately touch neither
 * `QnnAsrNative` nor `System.loadLibrary` — naming `QnnAsrNative` from a JVM test kills it with
 * `UnsatisfiedLinkError`, which is why the native side's own contract lives in
 * `NpuNativeContractTest` as source-text assertions.
 */
class NpuDecodePolicyTest {

    // ---------------------------------------------------------------- the prompt

    /**
     * `<|startoftranscript|> <|lang|> <|transcribe|> <|notimestamps|>` — four tokens, in that
     * order, and the `<|notimestamps|>` is the one that gets dropped.
     *
     * Dropping it is not a crash and not a garbled transcript: the model starts emitting timestamp
     * tokens (`<|0.00|>` and friends, ids >= 50364) interleaved with the words. Q5's detokeniser
     * drops every id above `EOT`, so the timestamps vanish silently — and with them the positions
     * the budget spent producing them. The transcript comes back short, plausible and missing
     * words, with nothing anywhere in the stack reporting a fault.
     */
    @Test
    fun promptIsSotLangTranscribeNoTimestamps() {
        assertArrayEquals(
            "the English prompt must be exactly [SOT, <|en|>, TRANSCRIBE, NO_TIMESTAMPS]",
            intArrayOf(50258, 50259, 50359, 50363),
            NpuDecodePolicy.promptTokens(WhisperTokens.SMALL, "en")
        )
        assertArrayEquals(
            "the French prompt differs from the English one in the language slot ONLY",
            intArrayOf(50258, 50265, 50359, 50363),
            NpuDecodePolicy.promptTokens(WhisperTokens.SMALL, "fr")
        )
        assertArrayEquals(
            "the German prompt differs from the English one in the language slot ONLY",
            intArrayOf(50258, 50261, 50359, 50363),
            NpuDecodePolicy.promptTokens(WhisperTokens.SMALL, "de")
        )
        assertEquals(
            "every prompt this policy builds is 4 tokens long — maxTokensFor() and the native " +
                "loop's `position == promptLen - 1` begin-suppress step both key off that length.",
            4,
            NpuDecodePolicy.promptTokens(WhisperTokens.SMALL, "es").size
        )
    }

    /**
     * A language code the asset cannot name must THROW, never silently become English.
     *
     * This is C1's shape in miniature. A `?: EN` fallback here passes every test that spot-checks
     * the common five and transcribes an explicitly-selected Slovenian user as English — a silent
     * wrong transcript for a user who told us the answer.
     */
    @Test
    fun anUnknownLanguageCodeIsRefusedRatherThanQuietlyBecomingEnglish() {
        for (bogus in listOf("xx", "en-US", "EN", "", "eng", "auto")) {
            try {
                val got = NpuDecodePolicy.promptTokens(WhisperTokens.SMALL, bogus)
                fail(
                    "promptTokens(\"$bogus\") must throw, but returned ${got.toList()}. A fallback " +
                        "to English here is invisible: the decode succeeds, the text is fluent, " +
                        "and it is in the wrong language."
                )
            } catch (expected: IllegalArgumentException) {
                assertTrue(
                    "the refusal must name the offending code so the caller can see which one it " +
                        "was. Got: ${expected.message}",
                    bogus.isEmpty() || expected.message?.contains(bogus) == true
                )
            }
        }
    }

    // ---------------------------------------------------------------- the suppression sets

    /** `generation_config.json`'s `begin_suppress_tokens`, verbatim and in order. */
    @Test
    fun beginSuppressListIsSpaceThenEot() {
        assertArrayEquals(
            "begin_suppress_tokens = [220, 50257]: 220 is the leading-space token (a transcript " +
                "must not open with a space) and 50257 is EOT (the model must not decline to say " +
                "anything at all on its very first generated step).",
            intArrayOf(220, 50257),
            WhisperTokens.BEGIN_SUPPRESS
        )
        assertArrayEquals(
            "NpuDecodePolicy.beginSuppressList(WhisperTokens.SMALL) is that array and nothing else",
            intArrayOf(220, 50257),
            NpuDecodePolicy.beginSuppressList(WhisperTokens.SMALL)
        )
    }

    /**
     * The 88 ids from `openai/whisper-small`'s `generation_config.json`, transcribed here a second
     * time and independently of the implementation.
     *
     * A verbatim copy is exactly the kind of requirement a single transcription satisfies while
     * being wrong: one dropped digit in the middle of 88 numbers changes which token is muted and
     * nothing downstream can tell. Two transcriptions that disagree fail here.
     */
    @Test
    fun suppressCarriesTheEightyEightGenerationConfigIdsVerbatim() {
        val expected = intArrayOf(
            1, 2, 7, 8, 9, 10, 14, 25, 26, 27,
            28, 29, 31, 58, 59, 60, 61, 62, 63, 90,
            91, 92, 93, 359, 503, 522, 542, 873, 893, 902,
            918, 922, 931, 1350, 1853, 1982, 2460, 2627, 3246, 3253,
            3268, 3536, 3846, 3961, 4183, 4667, 6585, 6647, 7273, 9061,
            9383, 10428, 10929, 11938, 12033, 12331, 12562, 13793, 14157, 14635,
            15265, 15618, 16553, 16604, 18362, 18956, 20075, 21675, 22520, 26130,
            26161, 26435, 28279, 29464, 31650, 32302, 32470, 36865, 42863, 47425,
            49870, 50254, 50258, 50358, 50359, 50360, 50361, 50362
        )
        assertEquals("suppress_tokens has 88 entries", 88, expected.size)
        assertArrayEquals(
            "WhisperTokens.SUPPRESS must be generation_config.json's suppress_tokens verbatim",
            expected,
            WhisperTokens.SUPPRESS
        )
        assertEquals("the first id is the boundary case 1", 1, WhisperTokens.SUPPRESS.first())
        assertEquals(
            "the last id is the boundary case 50362 (<|nospeech|>)",
            50362,
            WhisperTokens.SUPPRESS.last()
        )
        val policy = NpuDecodePolicy.suppressList(WhisperTokens.SMALL).toSet()
        expected.forEach {
            assertTrue("suppressList is missing generation_config id $it", policy.contains(it))
        }
    }

    /**
     * We prompt `<|notimestamps|>`, so a timestamp token coming back is a decode fault — and an
     * invisible one: Q5 drops every id above EOT, so a timestamp costs a position and leaves no
     * trace. Every one of the 1501 timestamp ids is therefore in the mask.
     */
    @Test
    fun everyTimestampIdIsSuppressed() {
        val policy = NpuDecodePolicy.suppressList(WhisperTokens.SMALL).toSet()
        val missing = (WhisperTokens.TIMESTAMP_BEGIN until WhisperTokens.VOCAB)
            .filterNot { policy.contains(it) }
        assertTrue(
            "every id from TIMESTAMP_BEGIN (${WhisperTokens.TIMESTAMP_BEGIN}) to VOCAB-1 " +
                "(${WhisperTokens.VOCAB - 1}) must be suppressed; ${missing.size} were not, " +
                "first few: ${missing.take(8)}",
            missing.isEmpty()
        )
        assertEquals(
            "whisper carries exactly 1501 timestamp tokens (0.00 s to 30.00 s in 0.02 s steps), " +
                "and the last one is the last id in the vocabulary — if this number moves, the " +
                "asset is not whisper-small any more",
            1501,
            WhisperTokens.VOCAB - WhisperTokens.TIMESTAMP_BEGIN
        )
    }

    /**
     * EOT must NEVER be in the always-on mask. It is in `beginSuppressList`, which applies at ONE
     * step; masking it at every step removes the loop's only early terminator and every segment
     * runs the full 196 tokens of hallucinated filler.
     */
    @Test
    fun eotIsSuppressedOnlyAtTheFirstGeneratedStepNeverThroughout() {
        assertTrue(
            "EOT (${WhisperTokens.EOT}) is in beginSuppressList",
            NpuDecodePolicy.beginSuppressList(WhisperTokens.SMALL).contains(WhisperTokens.EOT)
        )
        assertTrue(
            "EOT (${WhisperTokens.EOT}) must NOT be in the always-on suppressList — it is the " +
                "decode loop's only terminator short of the 199-position cap, and masking it " +
                "turns every segment into 196 tokens of filler.",
            !NpuDecodePolicy.suppressList(WhisperTokens.SMALL).contains(WhisperTokens.EOT)
        )
    }

    /**
     * M9, made mechanical. Language ids are deliberately neither suppressed nor terminal — they are
     * dropped at detokenise — so this test exists to stop a future reader "tidying" them into the
     * mask and breaking `nativeDetectLanguage`, whose whole job is to argmax inside that range.
     */
    @Test
    fun languageIdsAreNeitherSuppressedNorTerminal() {
        val policy = NpuDecodePolicy.suppressList(WhisperTokens.SMALL).toSet()
        val suppressed = (WhisperTokens.LANG_FIRST..WhisperTokens.LANG_LAST)
            .filter { policy.contains(it) }
        assertTrue(
            "no id in the language block ${WhisperTokens.LANG_FIRST}..${WhisperTokens.LANG_LAST} " +
                "may be suppressed — nativeDetectLanguage argmaxes inside exactly that range, and " +
                "a masked language id is a language the detector can never return. Found: " +
                suppressed,
            suppressed.isEmpty()
        )
        assertTrue(
            "50258 (SOT) IS suppressed and sits just below the language block; this assertion " +
                "exists so the one above cannot pass by the block being empty or misplaced.",
            policy.contains(WhisperTokens.SOT)
        )
    }

    /**
     * The native mask loop writes `logits[id]` for every entry without re-validating per token, so
     * a duplicate is a wasted store and an out-of-range id is a heap write past the logits buffer.
     * Sorted-and-unique is the cheap invariant that makes both impossible by construction.
     */
    @Test
    fun suppressListIsSortedUniqueAndInsideTheVocabulary() {
        val list = NpuDecodePolicy.suppressList(WhisperTokens.SMALL)
        assertEquals(
            "suppressList is the 88 generation_config ids plus the 1501 timestamps",
            88 + 1501,
            list.size
        )
        for (i in 1 until list.size) {
            assertTrue(
                "suppressList must be strictly ascending (so it is also duplicate-free): " +
                    "index ${i - 1}=${list[i - 1]} then $i=${list[i]}",
                list[i] > list[i - 1]
            )
        }
        assertTrue(
            "every suppressed id must lie in 0 until VOCAB (${WhisperTokens.VOCAB}) — native " +
                "indexes the logits buffer with these directly, so an out-of-range id is a write " +
                "past the end of a 103,730-byte buffer",
            list.all { it in 0 until WhisperTokens.VOCAB }
        )
    }

    // ---------------------------------------------------------------- the position budget

    /**
     * `position` is the single counter and the prompt tokens consume self-KV slots with it.
     * Positions 0..198 execute (199 of them, an exact fit for the 199-deep self-KV); the first
     * generated token lands at `promptLen - 1`; so a 4-token prompt can generate 196. A budget of
     * zero or less is a caller bug, not a zero-length transcript.
     */
    @Test
    fun maxTokensForCountsOnlyTheGeneratedPositionsAndRefusesImpossiblePrompts() {
        assertEquals(
            "a 4-token prompt generates at positions 3..198 inclusive = 196 tokens",
            196,
            NpuDecodePolicy.maxTokensFor(WhisperTokens.SMALL, 4)
        )
        assertEquals(
            "MAX_POSITIONS comes from the asset's [1,1,1,200] mask, not from whisper's 448",
            200,
            WhisperTokens.MAX_POSITIONS
        )
        assertEquals(
            "a 1-token prompt generates at positions 0..198 = 199 tokens",
            199,
            NpuDecodePolicy.maxTokensFor(WhisperTokens.SMALL, 1)
        )
        assertEquals(
            "the budget is MAX_POSITIONS - promptLen at every length",
            195,
            NpuDecodePolicy.maxTokensFor(WhisperTokens.SMALL, 5)
        )
        assertEquals(
            "the last prompt length that can still generate a single token is MAX_POSITIONS - 1",
            1,
            NpuDecodePolicy.maxTokensFor(WhisperTokens.SMALL, WhisperTokens.MAX_POSITIONS - 1)
        )
        for (bad in listOf(0, -1, WhisperTokens.MAX_POSITIONS, WhisperTokens.MAX_POSITIONS + 1)) {
            try {
                val got = NpuDecodePolicy.maxTokensFor(WhisperTokens.SMALL, bad)
                fail(
                    "maxTokensFor($bad) must throw; it returned $got. Silently handing native a " +
                        "non-positive budget makes nativeDecodeSegment return 0 tokens, which " +
                        "reads identically to a segment of silence."
                )
            } catch (expected: IllegalArgumentException) {
                // exactly right
            }
        }
    }

    // ---------------------------------------------------------------- the language table

    /**
     * Size and rule. The table is generated by `50259 + index` over whisper's language order, and
     * the boundaries are the two values that prove the whole range is in the right place.
     */
    @Test
    fun theLanguageTableHasNinetyNineEntriesAndFollowsThe50259PlusIndexRule() {
        assertEquals(
            "whisper-small carries 99 language tokens (large-v3's `yue` is the 100th and this " +
                "asset's 51,865-entry vocabulary has no room for it)",
            99,
            WhisperTokens.LANGUAGE_CODES.size
        )
        WhisperTokens.LANGUAGE_CODES.forEachIndexed { index, code ->
            assertEquals(
                "langToken(\"$code\") must be 50259 + $index",
                50259 + index,
                WhisperTokens.langToken(code)
            )
        }
        assertEquals("<|en|> is the first language token", 50259, WhisperTokens.langToken("en"))
        assertEquals(
            "<|su|> is the last, and 50259 + 98 = 50357 is the top of the language block",
            50357,
            WhisperTokens.langToken(WhisperTokens.LANGUAGE_CODES.last())
        )
        assertEquals("LANG_FIRST names the same boundary", 50259, WhisperTokens.LANG_FIRST)
        assertEquals("LANG_LAST names the same boundary", 50357, WhisperTokens.LANG_LAST)
        assertEquals(
            "no code appears twice — a duplicate would shift every id after it by one",
            99,
            WhisperTokens.LANGUAGE_CODES.toSet().size
        )
    }

    /** Q6's detect pass returns an id; the diag line needs its code back. Both directions, all 99. */
    @Test
    fun langTokenAndCodeForTokenRoundTripForEveryLanguage() {
        WhisperTokens.LANGUAGE_CODES.forEach { code ->
            assertEquals(
                "codeForToken(langToken(\"$code\")) must return \"$code\"",
                code,
                WhisperTokens.codeForToken(WhisperTokens.langToken(code))
            )
        }
        assertEquals(
            "the round trip covers the whole block with no gaps",
            99,
            (WhisperTokens.LANG_FIRST..WhisperTokens.LANG_LAST)
                .mapNotNull { WhisperTokens.codeForToken(it) }.toSet().size
        )
    }

    /**
     * `codeForToken` is the gate on Q6's detect result. If it answered outside the language block,
     * a decoder that argmaxed to `<|transcribe|>` or a timestamp would be reported as a detected
     * language and the pipeline would carry on.
     */
    @Test
    fun codeForTokenRefusesEveryIdOutsideTheLanguageBlock() {
        val outside = listOf(
            0, 220, WhisperTokens.EOT, WhisperTokens.SOT, WhisperTokens.LANG_FIRST - 1,
            WhisperTokens.LANG_LAST + 1, WhisperTokens.TRANSLATE, WhisperTokens.TRANSCRIBE,
            WhisperTokens.NO_TIMESTAMPS, WhisperTokens.TIMESTAMP_BEGIN,
            WhisperTokens.VOCAB - 1, WhisperTokens.VOCAB, -1, Int.MAX_VALUE, Int.MIN_VALUE
        )
        outside.forEach {
            assertNull(
                "codeForToken($it) must be null — it is outside " +
                    "${WhisperTokens.LANG_FIRST}..${WhisperTokens.LANG_LAST}",
                WhisperTokens.codeForToken(it)
            )
        }
    }

    /**
     * THE CENSUS ALARM. The app's own picker offers 54 real languages; every one of them must
     * resolve, or a user who explicitly selected Slovenian gets transcribed as something else.
     *
     * This fires if EITHER list moves, which is the point: a language added to the picker that the
     * table cannot name is a wrong transcript, and a language dropped from the table is the same
     * failure arriving from the other direction.
     */
    @Test
    fun everyLanguageTheAppsPickerOffersResolvesInTheTable() {
        val offered = PreferencesManager.SUPPORTED_LANGUAGES.map { it.first }.filter { it != "auto" }
        assertEquals(
            "the picker offers 55 entries — 54 real languages plus \"auto\". If this number moved, " +
                "read the assertion below before changing it: the long tail (sl, et, sr, hr, tl) " +
                "is exactly what a hand-typed language table drops.",
            54,
            offered.size
        )
        offered.forEach {
            assertNotNull(
                "picker offers $it but langToken cannot map it",
                WhisperTokens.codeForToken(WhisperTokens.langToken(it))
            )
        }
        listOf("sl", "et", "sr", "hr", "tl").forEach {
            assertTrue(
                "$it is in the picker and is one of the codes a partial table drops",
                offered.contains(it)
            )
        }
    }

    // ---------------------------------------------------------------- the pinned constants

    /**
     * The special ids, read off the asset's own vocabulary. They are pinned here because every one
     * of them ends up as a bare integer in a prompt or a comparison somewhere native, and a wrong
     * one is a transcript rather than an error.
     */
    @Test
    fun theSpecialTokenIdsAreTheOnesThisAssetsVocabularyDefines() {
        assertEquals("<|endoftext|>", 50257, WhisperTokens.EOT)
        assertEquals("<|startoftranscript|>", 50258, WhisperTokens.SOT)
        assertEquals("<|translate|>", 50358, WhisperTokens.TRANSLATE)
        assertEquals("<|transcribe|>", 50359, WhisperTokens.TRANSCRIBE)
        assertEquals("<|notimestamps|>", 50363, WhisperTokens.NO_TIMESTAMPS)
        assertEquals("<|0.00|>, the first timestamp", 50364, WhisperTokens.TIMESTAMP_BEGIN)
        assertEquals(
            "51,865 — NOT 51,866, which is large-v3/turbo. The decoder's logits tensor is " +
                "[1,51865,1,1] and native derives its argmax bound from that tensor, so a wrong " +
                "constant here disagrees with the asset rather than with itself.",
            51865,
            WhisperTokens.VOCAB
        )
        assertTrue(
            "the specials sit in one contiguous block below the timestamps: " +
                "EOT < SOT < languages < TRANSLATE < TRANSCRIBE < NO_TIMESTAMPS < timestamps",
            WhisperTokens.EOT < WhisperTokens.SOT &&
                WhisperTokens.SOT < WhisperTokens.LANG_FIRST &&
                WhisperTokens.LANG_LAST < WhisperTokens.TRANSLATE &&
                WhisperTokens.TRANSLATE < WhisperTokens.TRANSCRIBE &&
                WhisperTokens.TRANSCRIBE < WhisperTokens.NO_TIMESTAMPS &&
                WhisperTokens.NO_TIMESTAMPS < WhisperTokens.TIMESTAMP_BEGIN
        )
    }

    /**
     * Every prompt the policy can build must be runnable against the asset's limits, and it must
     * ask to TRANSCRIBE. `<|translate|>` and `<|transcribe|>` differ by one; the failure mode is a
     * perfectly fluent English transcript of speech that was not English, with no error anywhere.
     */
    @Test
    fun everyPromptIsRunnableAndAsksToTranscribeNeverToTranslate() {
        WhisperTokens.LANGUAGE_CODES.forEach { code ->
            val prompt = NpuDecodePolicy.promptTokens(WhisperTokens.SMALL, code)
            assertTrue(
                "every prompt id must be inside 0 until VOCAB for \"$code\": ${prompt.toList()}",
                prompt.all { it in 0 until WhisperTokens.VOCAB }
            )
            assertTrue(
                "a prompt must leave at least one position to generate in for \"$code\"",
                NpuDecodePolicy.maxTokensFor(WhisperTokens.SMALL, prompt.size) > 0
            )
            assertTrue(
                "the prompt for \"$code\" must carry TRANSCRIBE (${WhisperTokens.TRANSCRIBE})",
                prompt.contains(WhisperTokens.TRANSCRIBE)
            )
            assertTrue(
                "the prompt for \"$code\" must NOT carry TRANSLATE (${WhisperTokens.TRANSLATE})",
                !prompt.contains(WhisperTokens.TRANSLATE)
            )
            assertEquals(
                "the language slot of the prompt for \"$code\" is position 1",
                WhisperTokens.langToken(code),
                prompt[1]
            )
        }
    }

    // ---------------------------------------------------------------- the language policy (NEW-C2)

    /**
     * `requested != null` wins over everything, including a confident disagreeing detection.
     *
     * A user who opened the picker and chose Spanish has told us the answer. Letting a detection
     * pass over one 30 s window override that is the same class of defect as an `?: EN` fallback,
     * arriving from the opposite direction: fluent, confident, wrong, and unreportable.
     */
    @Test
    fun explicitSelectionWinsOverEveryDetectionAndLocale() {
        val spanishWithAFrenchDetection = NpuDecodePolicy.resolveLangToken(
            WhisperTokens.SMALL,
            requested = "es", detected = WhisperTokens.langToken("fr"), deviceLocale = "de-DE"
        )
        assertEquals("the token is Spanish's", WhisperTokens.langToken("es"), spanishWithAFrenchDetection.token)
        assertEquals("and so is the code", "es", spanishWithAFrenchDetection.code)
        assertEquals(
            "the note is the bare code — an explicit selection needs no explanation, and the " +
                "absence of a parenthesised reason is precisely what distinguishes it from a " +
                "fallback that happened to land on the same language",
            "es",
            spanishWithAFrenchDetection.note
        )
        // The detect pass does not even run for an explicit selection, so the sentinel the backend
        // passes in that case must not be able to change the answer.
        assertEquals(
            "a not-run detection sentinel cannot disturb an explicit selection",
            NpuDecodePolicy.resolveLangToken(WhisperTokens.SMALL, "es", -1, null),
            spanishWithAFrenchDetection
        )
        // And an explicit code the asset cannot name is REFUSED, never coerced.
        for (bogus in listOf("auto", "xx", "en-US", "EN")) {
            try {
                val got = NpuDecodePolicy.resolveLangToken(WhisperTokens.SMALL, bogus, -1, "en-US")
                fail(
                    "resolveLangToken(\"$bogus\", …) must throw; it returned $got. \"auto\" in " +
                        "particular means the caller skipped PreferencesManager's auto->null " +
                        "mapping, and silently transcribing that user in English is the bug this " +
                        "whole policy exists to prevent."
                )
            } catch (expected: IllegalArgumentException) {
                // exactly right
            }
        }
    }

    /**
     * `auto` plus a successful detection uses the detection — the shipped default path.
     *
     * `PreferencesManager` defaults the selected language to `"auto"` and maps it to null, so this
     * row and the two below it are what MOST users get, not what an edge case gets.
     */
    @Test
    fun autoWithASuccessfulDetectionUsesTheDetectedToken() {
        val french = NpuDecodePolicy.resolveLangToken(
            WhisperTokens.SMALL,
            requested = null, detected = WhisperTokens.langToken("fr"), deviceLocale = "en-US"
        )
        assertEquals("the detected token passes through unchanged", 50265, french.token)
        assertEquals("fr", french.code)
        assertEquals("auto->fr(detected)", french.note)
        assertEquals(
            "the detection beats the device locale, which is the whole reason a detect pass is " +
                "worth one extra graphExecute: a multilingual user on an English-locale phone is " +
                "this tier's normal case, not its exception",
            "fr",
            french.code
        )
        val japanese =
            NpuDecodePolicy.resolveLangToken(WhisperTokens.SMALL, null, WhisperTokens.langToken("ja"), "de-DE")
        assertEquals("auto->ja(detected)", japanese.note)
        assertEquals(WhisperTokens.langToken("ja"), japanese.token)
    }

    /**
     * `auto`, detection failed, and the device locale maps: use the locale and say so.
     *
     * A failed detect pass is a real outcome — `nativeDetectLanguage` returns `< 0` on a
     * `graphExecute` failure — and the locale is a better guess than English for the audience this
     * tier is steered at. It is still a GUESS, which is what `(locale)` in the note is for.
     */
    @Test
    fun autoWithAFailedDetectionFallsBackToADeviceLocaleThatMaps() {
        val german = NpuDecodePolicy.resolveLangToken(
            WhisperTokens.SMALL,
            requested = null, detected = -1, deviceLocale = "de-DE"
        )
        assertEquals(WhisperTokens.langToken("de"), german.token)
        assertEquals("de", german.code)
        assertEquals("auto->de(locale)", german.note)
        for (failure in listOf(-1, -2, -3, Int.MIN_VALUE)) {
            assertEquals(
                "every negative return of nativeDetectLanguage is a failure, not a token: $failure",
                "auto->de(locale)",
                NpuDecodePolicy.resolveLangToken(WhisperTokens.SMALL, null, failure, "de-DE").note
            )
        }
    }

    /**
     * `auto`, detection failed, locale unmappable: English — **and the note says why**.
     *
     * The prompt needs some language token and whisper has no "unknown" one, so English is
     * reachable. What it may never be is silent, and the difference between this row and an
     * explicit `en` is one word in a log line the owner can actually grep for.
     */
    @Test
    fun autoWithNeitherFallsBackToEnglishAndSaysSo() {
        val fallback = NpuDecodePolicy.resolveLangToken(
            WhisperTokens.SMALL,
            requested = null, detected = -1, deviceLocale = "xx-XX"
        )
        assertEquals(WhisperTokens.langToken("en"), fallback.token)
        assertEquals("en", fallback.code)
        assertEquals("auto->en(fallback)", fallback.note)
        assertEquals(
            "a null locale reaches the same row — a device that reports no locale at all is not " +
                "evidence for English either",
            "auto->en(fallback)",
            NpuDecodePolicy.resolveLangToken(WhisperTokens.SMALL, null, -1, null).note
        )
        assertEquals("and a blank one", "auto->en(fallback)", NpuDecodePolicy.resolveLangToken(WhisperTokens.SMALL, null, -1, "").note)
        assertNotEquals(
            "and it must NOT render as a bare \"en\" — that is the explicit-selection note, and " +
                "collapsing the two makes a guess indistinguishable from a user's own answer",
            "en",
            fallback.note
        )
    }

    /**
     * A detected id outside `50259..50357` is a FAILURE, not a language.
     *
     * `nativeDetectLanguage` restricts its argmax to the language block, so this should never
     * happen — which is exactly why it is checked here rather than trusted. A `<|transcribe|>` or a
     * timestamp id smuggled into the prompt's language slot is not an error: the model reads that
     * embedding row and transcribes fluently under it.
     */
    @Test
    fun aDetectedIdOutsideTheLanguageBlockIsTreatedAsFailureNotTrusted() {
        val outside = listOf(
            0, 220, WhisperTokens.EOT, WhisperTokens.SOT,
            WhisperTokens.LANG_FIRST - 1, WhisperTokens.LANG_LAST + 1,
            WhisperTokens.TRANSLATE, WhisperTokens.TRANSCRIBE, WhisperTokens.NO_TIMESTAMPS,
            WhisperTokens.TIMESTAMP_BEGIN, WhisperTokens.VOCAB, Int.MAX_VALUE
        )
        outside.forEach { id ->
            val resolved = NpuDecodePolicy.resolveLangToken(WhisperTokens.SMALL, null, id, "de-DE")
            assertEquals(
                "detected id $id is outside the language block and must be discarded, leaving the " +
                    "locale row to answer",
                "auto->de(locale)",
                resolved.note
            )
            assertEquals("and the token must be the LOCALE's, never $id", WhisperTokens.langToken("de"), resolved.token)
        }
        assertEquals(
            "both boundaries of the block ARE trusted: 50259 is <|en|>",
            "auto->en(detected)",
            NpuDecodePolicy.resolveLangToken(WhisperTokens.SMALL, null, WhisperTokens.LANG_FIRST, "de-DE").note
        )
        assertEquals(
            "and 50357 is <|su|> — this pair is what stops the test above passing on an " +
                "off-by-one block that rejects everything",
            "auto->su(detected)",
            NpuDecodePolicy.resolveLangToken(WhisperTokens.SMALL, null, WhisperTokens.LANG_LAST, "de-DE").note
        )
    }

    /**
     * THE CENSUS: no path silently yields English.
     *
     * Every resolution that lands on `en` must carry a note that says how it got there, and the
     * four notes must be four distinct shapes. This is the assertion that makes step 2's promise
     * mechanical: a future "simplification" that returns `en` with the bare note fails here.
     */
    @Test
    fun noResolutionPathSilentlyYieldsEnglish() {
        val rows = listOf(
            NpuDecodePolicy.resolveLangToken(WhisperTokens.SMALL, "en", -1, "de-DE"),
            NpuDecodePolicy.resolveLangToken(WhisperTokens.SMALL, null, WhisperTokens.langToken("en"), "de-DE"),
            NpuDecodePolicy.resolveLangToken(WhisperTokens.SMALL, null, -1, "en-GB"),
            NpuDecodePolicy.resolveLangToken(WhisperTokens.SMALL, null, -1, "xx-XX"),
        )
        assertEquals(
            listOf("en", "auto->en(detected)", "auto->en(locale)", "auto->en(fallback)"),
            rows.map { it.note }
        )
        assertEquals(
            "all four are English, which is the point: the token cannot distinguish them and the " +
                "note is the only thing that can",
            listOf(50259, 50259, 50259, 50259),
            rows.map { it.token }
        )
        assertEquals("four rows, four distinct notes", 4, rows.map { it.note }.toSet().size)
        rows.drop(1).forEach {
            assertTrue(
                "every auto-derived English answer must name its reason in parentheses: ${it.note}",
                it.note.startsWith("auto->en(") && it.note.endsWith(")")
            )
        }
    }

    /**
     * The token and the code in a resolution always name the same language, for all 99 — through
     * the detection path, which is the one that answers in ids and could drift.
     */
    @Test
    fun theResolvedTokenAndCodeAlwaysAgree() {
        WhisperTokens.LANGUAGE_CODES.forEach { code ->
            val viaDetection = NpuDecodePolicy.resolveLangToken(WhisperTokens.SMALL, null, WhisperTokens.langToken(code), null)
            assertEquals("detected $code: token", WhisperTokens.langToken(code), viaDetection.token)
            assertEquals("detected $code: code", code, viaDetection.code)
            assertEquals("detected $code: note", "auto->$code(detected)", viaDetection.note)

            val viaSelection = NpuDecodePolicy.resolveLangToken(WhisperTokens.SMALL, code, -1, null)
            assertEquals("selected $code: token", WhisperTokens.langToken(code), viaSelection.token)
            assertEquals("selected $code: code", code, viaSelection.code)

            assertEquals(
                "and the prompt built from a resolution's token is the same prompt the code builds",
                NpuDecodePolicy.promptTokens(WhisperTokens.SMALL, code).toList(),
                NpuDecodePolicy.promptTokens(WhisperTokens.SMALL, viaDetection.token).toList()
            )
        }
    }

    /**
     * Only the locale's PRIMARY SUBTAG is read: whisper's table is per-language and has no regional
     * entries. Both separators are accepted because both reach this code in practice —
     * `Locale.toLanguageTag()` produces `de-DE` and `Locale.toString()` produces `de_DE`.
     */
    @Test
    fun theDeviceLocaleIsReadAsItsPrimarySubtagOnly() {
        listOf("de", "de-DE", "de_DE", "de-Latn-AT", "DE-de").forEach {
            assertEquals(
                "\"$it\" must resolve through its primary subtag to German",
                "auto->de(locale)",
                NpuDecodePolicy.resolveLangToken(WhisperTokens.SMALL, null, -1, it).note
            )
        }
        assertEquals(
            "zh-Hans-CN is Chinese; the script and region subtags are not languages",
            "auto->zh(locale)",
            NpuDecodePolicy.resolveLangToken(WhisperTokens.SMALL, null, -1, "zh-Hans-CN").note
        )
        listOf("xx-XX", "xx", "", "   ", "-DE", "und").forEach {
            assertEquals(
                "\"$it\" maps to no whisper language and must reach the English fallback row",
                "auto->en(fallback)",
                NpuDecodePolicy.resolveLangToken(WhisperTokens.SMALL, null, -1, it).note
            )
        }
        assertNull("a null locale maps to nothing", NpuDecodePolicy.whisperCodeForLocale(WhisperTokens.SMALL, null))
        assertEquals("and a bare code maps to itself", "sl", NpuDecodePolicy.whisperCodeForLocale(WhisperTokens.SMALL, "sl-SI"))
    }

    /**
     * The JDK still normalises three languages to their pre-1989 ISO 639 codes, and whisper spells
     * three more differently from CLDR. Every one of them is a language the app's own picker
     * offers.
     *
     * Getting these wrong is not a crash: it is a Hebrew-speaking user who selected nothing being
     * transcribed as English, with `(fallback)` in a log they will never read.
     */
    @Test
    fun theJdkLegacyLanguageCodesMapToWhispersSpelling() {
        val expected = mapOf(
            "iw" to "he",   // java.util.Locale("he").language == "iw"
            "in" to "id",
            "ji" to "yi",
            "jv" to "jw",   // whisper spells Javanese jw
            "nb" to "no",   // Bokmal; whisper's table has no/nn and no nb
            "fil" to "tl",  // Android reports Filipino as fil
        )
        expected.forEach { (tag, code) ->
            assertEquals(
                "\"$tag\" is what the platform reports and \"$code\" is what whisper calls it",
                code,
                NpuDecodePolicy.whisperCodeForLocale(WhisperTokens.SMALL, "$tag-XX")
            )
            assertEquals(
                "and it must survive the whole resolution, not just the mapping helper",
                "auto->$code(locale)",
                NpuDecodePolicy.resolveLangToken(WhisperTokens.SMALL, null, -1, "$tag-XX").note
            )
        }
        expected.keys.forEach {
            assertNull(
                "the legacy spelling \"$it\" must NOT itself be a whisper code — if it ever " +
                    "becomes one, this alias is silently shadowing a real language",
                WhisperTokens.LANGUAGE_CODES.firstOrNull { code -> code == it }
            )
        }
        expected.values.forEach {
            assertNotNull("\"$it\" must be a real whisper code", WhisperTokens.codeForToken(WhisperTokens.langToken(it)))
        }
    }

    /**
     * `promptTokens(Int)` is the overload the tier calls, and its language slot is not a place a
     * stray id may land. It refuses anything outside the block for the same reason
     * `WhisperTokens.langToken` refuses an unknown code: the failure is a fluent transcript, not an
     * error.
     */
    @Test
    fun promptTokensFromAResolvedIdRefusesAnythingOutsideTheLanguageBlock() {
        assertArrayEquals(
            "the id overload builds the identical prompt the code overload does",
            intArrayOf(50258, 50262, 50359, 50363),
            NpuDecodePolicy.promptTokens(WhisperTokens.SMALL, WhisperTokens.langToken("es"))
        )
        val outside = listOf(
            WhisperTokens.SOT, WhisperTokens.EOT, WhisperTokens.TRANSCRIBE, WhisperTokens.TRANSLATE,
            WhisperTokens.NO_TIMESTAMPS, WhisperTokens.TIMESTAMP_BEGIN,
            WhisperTokens.LANG_FIRST - 1, WhisperTokens.LANG_LAST + 1, 0, -1, WhisperTokens.VOCAB
        )
        outside.forEach { id ->
            try {
                val got = NpuDecodePolicy.promptTokens(WhisperTokens.SMALL, id)
                fail(
                    "promptTokens($id) must throw; it returned ${got.toList()}. An id in the " +
                        "language slot that is not a language is not an error to the model — it " +
                        "reads that embedding row and transcribes under it."
                )
            } catch (expected: IllegalArgumentException) {
                assertTrue(
                    "the refusal must name the offending id. Got: ${expected.message}",
                    expected.message?.contains("$id") == true
                )
            }
        }
    }

    // ------------------------------------------------- what may cross the detectedLanguage seam

    /**
     * An ANSWER may be reported upward; a GUESS may not.
     *
     * `WhisperBackend.detectedLanguage(ctx)` means "ISO code whisper auto-detected", and
     * `LocalWhisperEngine` feeds it straight to `LanguagePin`, which latches the first usable code
     * for the whole session. A device-locale or English fallback is this tier deciding that the
     * prompt needs *some* token — it is not a detection, and reporting it as one is how a guess
     * becomes a session-wide fact.
     */
    @Test
    fun onlyASelectionOrARealDetectionMayCrossTheSeamAsADetectedLanguage() {
        assertEquals(
            "an explicit selection reports the selected code — WhisperNativeBackend answers the " +
                "same way (whisper sets lang_id to the language it was told to use), so the member " +
                "means the same thing on both tiers",
            "es",
            NpuDecodePolicy.resolveLangToken(WhisperTokens.SMALL, "es", -1, "de-DE").reportable
        )
        assertEquals(
            "a real detection reports the detected code",
            "fr",
            NpuDecodePolicy.resolveLangToken(WhisperTokens.SMALL, null, WhisperTokens.langToken("fr"), "de-DE").reportable
        )
        assertNull(
            "a device-locale guess reports NOTHING. It is the phone's setting, not the speech — " +
                "and letting it through pins the session to a language nobody detected.",
            NpuDecodePolicy.resolveLangToken(WhisperTokens.SMALL, null, -1, "de-DE").reportable
        )
        assertNull(
            "and the English fallback reports NOTHING, which is the row this gate exists for: one " +
                "failed detect pass on segment 1 would otherwise become a session pinned to English",
            NpuDecodePolicy.resolveLangToken(WhisperTokens.SMALL, null, -1, "xx-XX").reportable
        )
        // The provenance is a field, not a spelling of the note — so the two cannot drift.
        assertEquals(
            listOf(
                NpuDecodePolicy.LangSource.SELECTED,
                NpuDecodePolicy.LangSource.DETECTED,
                NpuDecodePolicy.LangSource.LOCALE,
                NpuDecodePolicy.LangSource.FALLBACK,
            ),
            listOf(
                NpuDecodePolicy.resolveLangToken(WhisperTokens.SMALL, "es", -1, "de-DE").source,
                NpuDecodePolicy.resolveLangToken(WhisperTokens.SMALL, null, 50265, "de-DE").source,
                NpuDecodePolicy.resolveLangToken(WhisperTokens.SMALL, null, -1, "de-DE").source,
                NpuDecodePolicy.resolveLangToken(WhisperTokens.SMALL, null, -1, "xx-XX").source,
            )
        )
        WhisperTokens.LANGUAGE_CODES.forEach { code ->
            assertEquals(
                "every one of the 99 detections reports its own code, not just the common few",
                code,
                NpuDecodePolicy.resolveLangToken(WhisperTokens.SMALL, null, WhisperTokens.langToken(code), null).reportable
            )
        }
        // The four English answers again — this time through the gate rather than the note.
        assertEquals(
            "the four English rows are one selection, one detection and two guesses, and the gate " +
                "must separate them exactly there",
            listOf("en", "en", null, null),
            listOf(
                NpuDecodePolicy.resolveLangToken(WhisperTokens.SMALL, "en", -1, "de-DE").reportable,
                NpuDecodePolicy.resolveLangToken(WhisperTokens.SMALL, null, WhisperTokens.langToken("en"), "de-DE").reportable,
                NpuDecodePolicy.resolveLangToken(WhisperTokens.SMALL, null, -1, "en-GB").reportable,
                NpuDecodePolicy.resolveLangToken(WhisperTokens.SMALL, null, -1, "xx-XX").reportable,
            )
        )
    }

    /**
     * THE LATCHING RULE, driven through the real [LanguagePin] the engine uses.
     *
     * The sequence is the failure the gate prevents, run forwards: segment 1's detect pass fails,
     * segment 2's succeeds. Segment 1 must resolve to `auto->en(fallback)` and pin NOTHING, so
     * segment 2 still arrives with `lang == null` and pays for a real detection — which then latches
     * and serves the rest of the session.
     *
     * Without the gate, segment 1 reports `en`, the pin latches it, `languageFor(null)` returns
     * `"en"` forever, the detect pass never runs again, and every later diag line prints the bare
     * `en` note that this file's own tests assert means the user chose English.
     */
    @Test
    fun aFailedThenSuccessfulDetectionLatchesTheLanguagePinOnlyOnTheRealDetection() {
        val pin = LanguagePin()
        val sessionLanguage: String? = null      // "auto" — the shipped default

        // --- segment 1: the detect pass fails, the locale maps to nothing.
        val first = NpuDecodePolicy.resolveLangToken(
            WhisperTokens.SMALL,
            requested = pin.languageFor(sessionLanguage), detected = -1, deviceLocale = "xx-XX"
        )
        assertEquals("segment 1 is an English FALLBACK and says so", "auto->en(fallback)", first.note)
        pin.onDetected(sessionLanguage = sessionLanguage, detected = first.reportable)
        assertNull(
            "segment 1 must pin NOTHING — a guess is not a detection, and LanguagePin latches the " +
                "first usable code forever (`if (pinned != null) return`)",
            pin.languageFor(sessionLanguage)
        )

        // --- segment 2: still auto, so the detect pass runs again — and succeeds.
        val second = NpuDecodePolicy.resolveLangToken(
            WhisperTokens.SMALL,
            requested = pin.languageFor(sessionLanguage),
            detected = WhisperTokens.langToken("fr"),
            deviceLocale = "xx-XX",
        )
        assertEquals("segment 2 is a real DETECTION and says so", "auto->fr(detected)", second.note)
        assertEquals("and it prompts French", WhisperTokens.langToken("fr"), second.token)
        pin.onDetected(sessionLanguage = sessionLanguage, detected = second.reportable)
        assertEquals(
            "NOW the pin latches — on the detection, which is the only thing that earned it",
            "fr",
            pin.languageFor(sessionLanguage)
        )

        // --- segment 3: the pin supplies the language, so the resolution is an explicit selection.
        val third = NpuDecodePolicy.resolveLangToken(
            WhisperTokens.SMALL,
            requested = pin.languageFor(sessionLanguage), detected = -1, deviceLocale = "xx-XX"
        )
        assertEquals("segment 3 runs pinned, with no detect pass at all", "fr", third.note)
        assertEquals(WhisperTokens.langToken("fr"), third.token)

        // And the counterfactual, so this test cannot pass by the pin simply never latching:
        // feeding it the bare code instead of the gated one pins English on segment 1.
        val laundering = LanguagePin()
        laundering.onDetected(sessionLanguage = null, detected = first.code)
        assertEquals(
            "this is the defect, demonstrated: the bare `code` of a (fallback) resolution pins the " +
                "whole session to English on the strength of one failed detect pass",
            "en",
            laundering.languageFor(null)
        )
    }

    // ================================================================ 4.1 L2 — THE TOKEN FAMILY

    /**
     * **The family parameter has no default, on any of the six members** — and that is not a style
     * rule, it is the guard.
     *
     * A default of `WhisperTokens.SMALL` would let a future call site build a prompt for
     * `npu-turbo`'s decoder out of `whisper-small`'s ids. The task token would be `50359`, which
     * under `large-v3` is `<|translate|>` — a valid, unsuppressed, perfectly decodable token that
     * puts the model in the WRONG TASK. Nothing fails; the user gets a fluent translation of the
     * words they spoke, from a default nobody typed. And no per-id check can catch it: 50358 and
     * 50359 are legal ids in both vocabularies with different meanings in each, so only the FAMILY
     * knows which is intended (see `WhisperTokenFamilyTest`).
     *
     * The parameter being required is what forced all 63 call sites in this file to name a family.
     * A defaulted one would have let every one of them keep compiling while meaning something the
     * author never chose — which is the whole difference between a compile error and a wrong
     * transcript.
     *
     * Read as SOURCE because a default's absence is not observable at runtime: a call that omitted
     * the argument simply would not compile, and a test cannot assert about code that does not
     * exist.
     */
    @Test
    fun everyMemberTakesTheFamilyAndNoneOfThemDefaultsIt() {
        val policy = source("src/main/java/com/whispereverywhere/npu/NpuDecodePolicy.kt")
        listOf(
            "fun promptTokens(family: WhisperTokenFamily, languageCode: String): IntArray",
            "fun promptTokens(family: WhisperTokenFamily, langToken: Int): IntArray",
            "fun suppressList(family: WhisperTokenFamily): IntArray",
            "fun beginSuppressList(family: WhisperTokenFamily): IntArray",
            "fun maxTokensFor(family: WhisperTokenFamily, promptLen: Int): Int",
            "family: WhisperTokenFamily,",
        ).forEach { declaration ->
            assertTrue(
                "NpuDecodePolicy must declare `$declaration` on a live line — every member takes " +
                    "the family, including resolveLangToken, whose own answer feeds the prompt.",
                liveLines(policy, declaration).isNotEmpty()
            )
        }
        assertTrue(
            "and NOT ONE of them may default it. `family: WhisperTokenFamily = ` anywhere is the " +
                "hazard this parameter exists to remove, arriving as a convenience. Found: " +
                liveLines(policy, "family: WhisperTokenFamily ="),
            liveLines(policy, "family: WhisperTokenFamily =").isEmpty()
        )
        assertTrue(
            "the two former `val`s must be gone: a per-family array is not a process constant, and " +
                "leaving `val suppressList` beside `fun suppressList(family)` compiles and lets " +
                "every existing caller keep reading whisper-small's. Found: " +
                liveLines(policy, "val suppressList"),
            liveLines(policy, "val suppressList").isEmpty() &&
                liveLines(policy, "val beginSuppressList").isEmpty()
        )

        // AND THE PARAMETER MUST BE USED, NOT MERELY TAKEN — executed, because the assertions
        // above are all about the declaration and a body that ignored its argument would satisfy
        // every one of them. This is THE hazard of the whole task in two lines: `promptTokens`
        // reading `WhisperTokens.TRANSCRIBE` instead of `family.transcribe` is invisible for the
        // small family (they are the same 50359) and puts the OTHER model in translate mode.
        // (Since 4.1 L4 the second family is the SHIPPED handle, not a local derivation.)
        val largeV3 = WhisperTokens.LARGE_V3
        assertArrayEquals(
            "a large-v3 prompt must carry LARGE-V3's task and timestamp tokens — 50360 and 50364, " +
                "one above whisper-small's. Built from whisper-small's ids instead, the task slot " +
                "holds 50359, which under this vocabulary is <|translate|>: legal, unsuppressed, " +
                "perfectly decodable, and the wrong task. The output is fluent text nobody asked " +
                "for, and no per-id check anywhere can see it.",
            intArrayOf(50258, largeV3.langToken("yue"), 50360, 50364),
            NpuDecodePolicy.promptTokens(largeV3, "yue")
        )
        assertEquals(
            "…and the small family's prompt is unchanged: 50359 / 50363",
            listOf(50258, WhisperTokens.langToken("es"), 50359, 50363),
            NpuDecodePolicy.promptTokens(WhisperTokens.SMALL, "es").toList()
        )
        assertEquals(
            "the language band is the FAMILY's too: 50358 is a legal <|xx|> slot under large-v3 " +
                "(<|yue|>) and is <|translate|> under whisper-small, so the same id must be " +
                "accepted by one and refused by the other. A prompt built with the wrong band " +
                "puts a TASK token in the language slot and the decoder transcribes under whatever " +
                "embedding row it names.",
            50358,
            NpuDecodePolicy.promptTokens(largeV3, 50358)[1]
        )
        assertThrows(IllegalArgumentException::class.java) {
            NpuDecodePolicy.promptTokens(WhisperTokens.SMALL, 50358)
        }
        assertEquals(
            "resolveLangToken reads the family too — `yue` exists in one vocabulary and in no " +
                "other, so a resolver wired to a fixed table throws on a language the caller's own " +
                "model can name",
            "yue",
            NpuDecodePolicy.resolveLangToken(largeV3, "yue", -1, "en-US").code
        )
        assertEquals(
            "…and its locale arm reads the family's own code list, not a fixed one",
            "auto->yue(locale)",
            NpuDecodePolicy.resolveLangToken(largeV3, null, -1, "yue-HK").note
        )
    }

    /**
     * `suppressList` answers per family rather than per process.
     *
     * The always-on mask is `generation_config.json`'s 88 ids **plus every timestamp id**, and both
     * halves move with the vocabulary: the six control ids shift by one and the timestamp block
     * starts one later and ends one later. A list computed once for `whisper-small` and handed to a
     * `large-v3` decoder would leave `<|yue|>` (50358) unsuppressed where a task token belongs and
     * would mask 50364 — which is `<|notimestamps|>` there, i.e. a token the PROMPT carries.
     */
    @Test
    fun suppressListAnswersPerFamilyRatherThanPerProcess() {
        val small = WhisperTokens.SMALL
        val largeV3 = WhisperTokens.LARGE_V3

        val smallList = NpuDecodePolicy.suppressList(small)
        val largeList = NpuDecodePolicy.suppressList(largeV3)
        assertNotEquals(
            "the two families must not share one list — every timestamp id and every control id " +
                "above the language band differs",
            smallList.toList(),
            largeList.toList()
        )
        assertEquals(
            "small: 88 generation_config ids + 1501 timestamps, minus the overlap",
            (WhisperTokens.SUPPRESS.toList() + (50364 until 51865)).distinct().size,
            smallList.size
        )
        assertEquals(
            "large-v3: the same construction, one id along",
            (largeV3.suppress.toList() + (50365 until 51866)).distinct().size,
            largeList.size
        )
        assertTrue(
            "each list must stay inside its OWN vocabulary — native writes logits[id] unguarded, " +
                "so an id from the other family is a heap write past the end of the buffer",
            smallList.all { it in 0 until small.vocab } && largeList.all { it in 0 until largeV3.vocab }
        )
        assertTrue(
            "and neither may suppress its own terminator",
            !smallList.contains(small.eot) && !largeList.contains(largeV3.eot)
        )
        assertTrue(
            "50364 is a TIMESTAMP under whisper-small and <|notimestamps|> under large-v3 — the " +
                "prompt's own fourth token. Masking it there would suppress a token the prompt " +
                "feeds, which is the sharpest single value in this whole comparison.",
            smallList.contains(50364) && !largeList.contains(50364)
        )
    }

    /** `beginSuppressList` too — the same question, and the answer happens to coincide. */
    @Test
    fun beginSuppressListAnswersPerFamilyEvenWhereTheAnswerCoincides() {
        val small = WhisperTokens.SMALL
        val largeV3 = WhisperTokens.LARGE_V3
        assertArrayEquals(intArrayOf(220, 50257), NpuDecodePolicy.beginSuppressList(small))
        assertArrayEquals(
            "220 is a BPE id and EOT sits below the language table, so this pair does NOT move — " +
                "which is exactly why it has to be asked per family rather than assumed: a value " +
                "that happens to agree today is not a constant, it is a coincidence, and the next " +
                "vocabulary is not required to preserve it.",
            intArrayOf(220, 50257),
            NpuDecodePolicy.beginSuppressList(largeV3)
        )
        assertArrayEquals(
            "and it is the family's own pair, not a literal minted here",
            largeV3.beginSuppress,
            NpuDecodePolicy.beginSuppressList(largeV3)
        )
    }

    /** `maxTokensFor` too — the budget is the family's context window, not a process constant. */
    @Test
    fun maxTokensForAnswersPerFamilyRatherThanPerProcess() {
        val small = WhisperTokens.SMALL
        val wide = WhisperTokenFamily(langCount = 99, maxPositions = 448)
        assertEquals(
            "196 for the shipped four-token prompt in a 200-position context",
            196,
            NpuDecodePolicy.maxTokensFor(small, 4)
        )
        assertEquals(
            "and 444 for the same prompt in a 448-position one. A budget read off a process " +
                "constant would hand the wide decoder 196 and silently truncate every segment at " +
                "44 % of its context — a short transcript, with nothing reporting a fault.",
            444,
            NpuDecodePolicy.maxTokensFor(wide, 4)
        )
        assertEquals(
            "the domain moves with it: promptLen may reach maxPositions-1, which generates exactly " +
                "one token at the last executing position. That is the SAME expression native " +
                "refuses on (`maxPromptLen = maskLen - 1`), settled together at 4.1 L2 — 4.0's " +
                "native half refused a 199-token prompt its own loop would have decoded.",
            1,
            NpuDecodePolicy.maxTokensFor(wide, 447)
        )
        listOf(0, -1, 448, 449).forEach { bad ->
            try {
                val got = NpuDecodePolicy.maxTokensFor(wide, bad)
                fail("promptLen $bad is outside 1..447 for this family; got $got")
            } catch (expected: IllegalArgumentException) {
                assertTrue(
                    "the refusal must name the FAMILY's own bound, not 200. Got: ${expected.message}",
                    expected.message.orEmpty().contains("447")
                )
            }
        }
    }

    /**
     * The two SHIPPED families' prompts, side by side: `[50258, <lang>, 50360, 50364]` under
     * `LARGE_V3` against `[50258, <lang>, 50359, 50363]` under `SMALL` (4.1 L4).
     *
     * For any code both families carry, the SOT and language slots are IDENTICAL — the shared
     * table is a strict prefix, so `es` is 50262 in both — and the task and timestamp slots sit
     * exactly one apart. That adjacency is the trap: a prompt built from the wrong family is four
     * legal ids, three of them right, and the model runs the wrong TASK rather than failing.
     */
    @Test
    fun promptsUnderTheTwoShippedFamiliesDifferOnlyInTheSlotsAboveTheLanguageBand() {
        val smallPrompt = NpuDecodePolicy.promptTokens(WhisperTokens.SMALL, "es")
        val largePrompt = NpuDecodePolicy.promptTokens(WhisperTokens.LARGE_V3, "es")
        assertArrayEquals(intArrayOf(50258, 50262, 50359, 50363), smallPrompt)
        assertArrayEquals(intArrayOf(50258, 50262, 50360, 50364), largePrompt)
        assertEquals("slot 0 (SOT) is shared", smallPrompt[0], largePrompt[0])
        assertEquals("slot 1 (the language) is shared for a shared code", smallPrompt[1], largePrompt[1])
        assertEquals("slot 2 (the task) sits one apart", smallPrompt[2] + 1, largePrompt[2])
        assertEquals("slot 3 (timestamps) sits one apart", smallPrompt[3] + 1, largePrompt[3])

        // `yue` exists in exactly one family: a prompt for it under LARGE_V3 carries 50358 in the
        // language slot, and under SMALL there is nothing to build — the refusal, not a fallback.
        assertArrayEquals(
            intArrayOf(50258, 50358, 50360, 50364),
            NpuDecodePolicy.promptTokens(WhisperTokens.LARGE_V3, "yue")
        )
        assertThrows(IllegalArgumentException::class.java) {
            NpuDecodePolicy.promptTokens(WhisperTokens.SMALL, "yue")
        }
    }

    /**
     * **Id 50358, asserted under both shipped families with its two different meanings** — the
     * sharpest cross-family confusion in the branch, and no per-id check can catch it.
     *
     * `50358` is not invalid in either family; it is *valid in both and means different things*:
     * `<|translate|>` under `SMALL` (a task token) and `<|yue|>` — Cantonese — under `LARGE_V3`
     * (a language token). A validity guard — `require(codeForToken(id) != null)`, exactly the one
     * `promptTokens` already carries — passes under one family and fails under the other for the
     * *same* id, so it cannot be the thing that keeps them apart. The family has to be threaded
     * to every caller, which is why L2 made it a required parameter with no default. Refusing
     * 50358 at the turbo family would break Cantonese; asserting it under `SMALL` alone would
     * prove nothing.
     */
    @Test
    fun id50358IsATaskTokenToOneShippedFamilyAndCantoneseToTheOther() {
        // codeForToken: null under SMALL (not a language), "yue" under LARGE_V3.
        assertNull(
            "under whisper-small, 50358 is <|translate|> and must NOT resolve as a language",
            WhisperTokens.SMALL.codeForToken(50358)
        )
        assertEquals(
            "under large-v3, the same integer IS a language",
            "yue",
            WhisperTokens.LARGE_V3.codeForToken(50358)
        )
        // promptTokens: refused by one family, accepted by the other — same id, same call.
        assertThrows(IllegalArgumentException::class.java) {
            NpuDecodePolicy.promptTokens(WhisperTokens.SMALL, 50358)
        }
        assertEquals(
            "promptTokens(LARGE_V3, 50358) must ACCEPT the id into the language slot — refusing " +
                "it here is how a Cantonese speaker loses their language to an over-general guard",
            50358,
            NpuDecodePolicy.promptTokens(WhisperTokens.LARGE_V3, 50358)[1]
        )
        // And the detect-pass gate agrees from the other side: a detected 50358 is a FAILURE
        // under SMALL (falls through to locale/fallback) and a real detection under LARGE_V3.
        assertEquals(
            "auto->en(fallback)",
            NpuDecodePolicy.resolveLangToken(WhisperTokens.SMALL, null, 50358, null).note
        )
        assertEquals(
            "auto->yue(detected)",
            NpuDecodePolicy.resolveLangToken(WhisperTokens.LARGE_V3, null, 50358, null).note
        )
    }

    // ---------------------------------------------------------------- source-reading helpers

    /**
     * Reads a repo file from the test's working directory — the same locator
     * `NpuNativeContractTest` uses, and here for the same reason: the absence of a default is a
     * property of the DECLARATION, and no call can observe it.
     */
    private fun source(relative: String): String {
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            for (candidate in listOf(java.io.File(dir, relative), java.io.File(dir, "app/$relative"))) {
                if (candidate.isFile) return candidate.readText().replace("\r\n", "\n")
            }
            dir = dir.parentFile
        }
        throw AssertionError("cannot locate $relative from ${System.getProperty("user.dir")}")
    }

    /** The LIVE (non-comment) lines of [scope] containing [needle], trimmed. */
    private fun liveLines(scope: String, needle: String): List<String> =
        scope.split("\n").map { it.trimStart() }.filter { line ->
            !(line.startsWith("//") || line.startsWith("/*") || line.startsWith("*")) &&
                line.contains(needle)
        }
}
