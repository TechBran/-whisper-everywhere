package com.whispereverywhere.npu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Golden vectors for the byte-level BPE detokeniser, run against the **shipped assets** — both of
 * them, since 4.1 L4: `whisper_vocab.json` (51,865 entries, the `whisper-small` family) and
 * `whisper_vocab_turbo.json` (51,866, `large-v3`/turbo).
 *
 * WHY THE SHIPPED ASSETS AND NOT FIXTURES. `app/src/main/assets/` is not on the JVM test
 * classpath and `AssetManager` needs a `Context`, so the assets cannot be opened the way the app
 * opens them. A hand-written fixture would test the algorithm and leave the files that actually
 * ride in the APK unpinned — and the failure this suite exists to catch is "the wrong vocabulary
 * shipped" at least as much as "the byte table is wrong". The house `source(relative)` walker
 * reads the real files off disk, so every id, every language code and the entry counts below are
 * assertions about the artefacts, not about copies of them.
 *
 * WHERE THE VECTORS CAME FROM. Every id here was produced by running the real GPT-2 byte-level BPE
 * (`openai/whisper-small`'s `vocab.json` + `merges.txt`) over the quoted text. `merges.txt` is a
 * generation-time aid and is deliberately **not** shipped: this tier detokenises and never
 * tokenises. The byte breakdown is written out beside each vector because the whole point of the
 * multi-byte case is a fact about bytes that the strings themselves hide. The same vectors run
 * against BOTH decoders, and that is a claim, not a convenience: the two families share their
 * 50,257-token base element for element (asserted in `TurboVocabAssetTest`), so identical base ⇒
 * identical text for any all-base id sequence.
 */
class WhisperBpeDecoderTest {

    private val decoder: WhisperBpeDecoder = WhisperBpeDecoder.fromJson(
        source("src/main/assets/${NpuModelSpec.SMALL.vocabAsset}"),
        WhisperTokens.SMALL.vocab,
    )

    /** The turbo decoder, built lazily because only the cross-family tests read it. */
    private val turboDecoder: WhisperBpeDecoder by lazy {
        WhisperBpeDecoder.fromJson(
            source("src/main/assets/${NpuModelSpec.TURBO.vocabAsset}"),
            WhisperTokens.LARGE_V3.vocab,
        )
    }

    // ------------------------------------------------------------------ the four brief cases

    @Test
    fun decodesPlainAscii() {
        // 15947 'Hello'  = 48 65 6C 6C 6F
        //  1002 'Ġworld' = 20 77 6F 72 6C 64
        assertEquals("Hello world", decoder.decode(intArrayOf(15947, 1002)))
    }

    @Test
    fun theGAndCMarkersAreBytesNotLiteralCharacters() {
        // The two markers the byte-level table is usually named after. 'Ġ' is U+0120 = 256 + 32,
        // the re-encoding of byte 0x20 (space); 'Ċ' is U+010A = 256 + 10, byte 0x0A (newline).
        // A decoder that treats token strings as text emits the marker glyphs themselves, which is
        // the single most recognisable symptom of getting this wrong.
        assertEquals(" hello", decoder.decode(intArrayOf(7751)))   // 7751 'Ġhello' = 20 68 65 6C 6C 6F
        assertEquals("\n", decoder.decode(intArrayOf(198)))        //  198 'Ċ'      = 0A

        // Stated as an inequality too, because " hello" == "Ġhello" is exactly the confusion:
        assertNotEquals("Ġhello", decoder.decode(intArrayOf(7751)))
    }

    /**
     * THE CASE THIS FILE EXISTS FOR: a multi-byte UTF-8 character split **across** two BPE tokens.
     *
     * `안녕하세요` = `[49200, 12831, 15377]`:
     *
     * ```
     *  49200  'ìķĪë'      EC 95 88 EB      <- ends on the FIRST byte of 녕 (EB 85 95)
     *  12831  'ħķ'        85 95            <- carries the other two
     *  15377  'íķĺìĦ¸ìļĶ'  ED 95 98 EC 84 B8 EC 9A 94
     * ```
     *
     * Two of the three tokens are not valid UTF-8 on their own. Decode each token to text and
     * concatenate — the obvious implementation, and the one this test was written red against —
     * and the result is `안���하세요`: three replacement characters where `녕` should be. The bytes
     * have to be joined FIRST and decoded ONCE.
     *
     * The emoji case is the same property one byte wider: `🎉` is U+1F389, four UTF-8 bytes
     * `F0 9F 8E 89` split 3/1 across `[28864, 231]`, and one codepoint above the BMP — so a correct
     * decode also has to produce a Java **surrogate pair** out of bytes that arrived in two pieces.
     */
    @Test
    fun decodesMultiByteUtf8AcrossTokenBoundary() {
        assertEquals("안녕하세요", decoder.decode(intArrayOf(49200, 12831, 15377)))

        val party = decoder.decode(intArrayOf(28864, 231))
        assertEquals("🎉", party)
        assertEquals("the emoji is one codepoint above the BMP, i.e. two Java chars", 2, party.length)
        assertEquals(0x1F389, party.codePointAt(0))
    }

    @Test
    fun specialsOnlyInputDecodesToEmptyString() {
        // The whole decode prompt plus a terminator and a timestamp: nothing here is speech.
        val specials = intArrayOf(
            WhisperTokens.SOT,
            WhisperTokens.langToken("en"),
            WhisperTokens.TRANSCRIBE,
            WhisperTokens.NO_TIMESTAMPS,
            WhisperTokens.TIMESTAMP_BEGIN,
            WhisperTokens.EOT,
        )
        assertEquals("", decoder.decode(specials))
        assertEquals("", decoder.decode(intArrayOf()))
    }

    // ------------------------------------------------------------------ the Q4 token contract

    @Test
    fun everyIdAtOrAboveEotIsDroppedWhereverItAppears() {
        // Q4's handoff §5.2: language ids 50259..50357 are deliberately NEITHER suppressed NOR
        // terminal — `nativeDetectLanguage` argmaxes over them, so masking them would break it.
        // A decoder that emits one MID-TRANSCRIPT is dropped here, not native side. `<|notimestamps|>`
        // is likewise unsuppressed. So the cutoff has to apply at every position, not just the head.
        val withIntruders = intArrayOf(
            15947,                          // 'Hello'
            WhisperTokens.langToken("fr"),  // 50265, mid-transcript
            WhisperTokens.NO_TIMESTAMPS,    // 50363, mid-transcript
            1002,                           // 'Ġworld'
            WhisperTokens.TIMESTAMP_BEGIN,  // 50364, trailing
        )
        assertEquals("Hello world", decoder.decode(withIntruders))

        // And the cutoff is EOT itself, not "somewhere near it": 50256 is the last id that is still
        // vocabulary. (It is whisper's empty-string slot, so it contributes nothing — which is the
        // point: it must be READ, not refused.)
        assertEquals("", decoder.decode(intArrayOf(50256)))
        assertEquals("Hello", decoder.decode(intArrayOf(15947, 50256)))
    }

    @Test
    fun anIdOutsideTheVocabularyIsRefusedRatherThanDropped() {
        // -1 is `nativeDecodeSegment`'s FAILURE return. If a caller ever forgets the sign check and
        // hands the raw buffer over, dropping it silently would render a plausible transcript out of
        // an error. Same for an id at or past the logits width: native cannot produce one, so its
        // arrival means the contract broke.
        for (bad in intArrayOf(-1, WhisperTokens.VOCAB, Int.MIN_VALUE, Int.MAX_VALUE)) {
            try {
                decoder.decode(intArrayOf(15947, bad))
                fail("id $bad is outside 0 until ${WhisperTokens.VOCAB} and must be refused, not dropped")
            } catch (expected: IllegalArgumentException) {
                assertTrue(
                    "the message must name the offending id: ${expected.message}",
                    expected.message!!.contains(bad.toString()),
                )
            }
        }
    }

    @Test
    fun aTruncatedMultiByteTailBecomesReplacementRatherThanThrowing() {
        // Reachable, not hypothetical: `NpuDecodePolicy.maxTokensFor` caps a segment at 196 tokens
        // and the position cap at 199, either of which can cut mid-character. 49200 alone is
        // EC 95 88 EB — `안` followed by a lone lead byte. The user gets one bad glyph; they must
        // not get an exception from a finished transcription.
        assertEquals("안�", decoder.decode(intArrayOf(49200)))
    }

    // ------------------------------------------------------------------ the second family (4.1 L4)

    /**
     * The turbo decoder round-trips the SAME golden vectors as the small one.
     *
     * This is the shared-base claim made executable: every id in these vectors is below 50,257,
     * `TurboVocabAssetTest` asserts the two bases are element-for-element identical, and therefore
     * the same ids must render the same text through both decoders. A turbo asset regenerated from
     * the wrong base would pass every size check and fail here, on the words.
     */
    @Test
    fun aTurboDecoderRoundTripsTheSameGoldenVectorsBecauseTheBaseIsShared() {
        assertEquals(51_866, turboDecoder.size)
        assertEquals("Hello world", turboDecoder.decode(intArrayOf(15947, 1002)))
        assertEquals(" hello", turboDecoder.decode(intArrayOf(7751)))
        assertEquals("\n", turboDecoder.decode(intArrayOf(198)))
        assertEquals("안녕하세요", turboDecoder.decode(intArrayOf(49200, 12831, 15377)))
        assertEquals("🎉", turboDecoder.decode(intArrayOf(28864, 231)))
        assertEquals("안�", turboDecoder.decode(intArrayOf(49200)))
    }

    /**
     * The turbo decoder drops ITS OWN specials wherever they appear — including `50358`, which
     * under this family is `<|yue|>` (a language token) and under `whisper-small` is
     * `<|translate|>` (a task token). Both are at-or-above EOT, so the drop rule needs no family
     * knowledge — EOT is 50257 in every published family, below the language table — and that is
     * asserted here rather than assumed, because it is the one place this class touches ids at all.
     */
    @Test
    fun theTurboDecoderDropsItsOwnSpecialsWhereverTheyAppear() {
        val family = WhisperTokens.LARGE_V3
        // The whole turbo prompt plus a terminator and a timestamp: nothing here is speech.
        assertEquals(
            "",
            turboDecoder.decode(
                intArrayOf(
                    family.sot, family.langToken("yue"), family.transcribe,
                    family.noTimestamps, family.timestampBegin, family.eot,
                )
            ),
        )
        // Mid-transcript intruders, under THIS family's ids: <|yue|> (50358) after the first word,
        // <|notimestamps|> (50364 here, a TIMESTAMP under whisper-small) after the second, and the
        // top timestamp id 51865 — which does not exist in the small vocabulary at all.
        assertEquals(
            "Hello world",
            turboDecoder.decode(intArrayOf(15947, 50_358, 1002, 50_364, 51_865)),
        )
        // The cutoff is EOT itself, same as the small family, because EOT sits below the language
        // table and does not move: 50256 is still readable, still empty.
        assertEquals("Hello", turboDecoder.decode(intArrayOf(15947, 50_256)))
        // And an id at the turbo logits width is refused, not dropped — the bound is THIS
        // vocabulary's size, not a process constant.
        try {
            turboDecoder.decode(intArrayOf(51_866))
            fail("51866 is outside 0 until 51866 and must be refused")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("51866"))
        }
    }

    /**
     * **A vocabulary of the wrong size is the WRONG FILE, and it is refused in both directions**
     * — the turbo asset at `expectedSize = 51865` and the small asset at `51866` (4.1 L4, closing
     * the L2 micro-round's named carry: `WhisperBpeDecoder` hardcoded `WhisperTokens.VOCAB`, which
     * was correct for exactly as long as one vocabulary existed).
     *
     * A vocabulary of the wrong size still binds, still decodes, and produces fluent wrong text —
     * every special id lands one off, so the other family's `<|translate|>` reads as a language
     * and its timestamps bleed into the words. The refusal has to be at construction, from the
     * size the CALLER's spec demands, not from a constant the class remembers.
     */
    @Test
    fun aVocabularyOfTheWrongSizeIsTheWrongFileInBothDirections() {
        val smallJson = source("src/main/assets/${NpuModelSpec.SMALL.vocabAsset}")
        val turboJson = source("src/main/assets/${NpuModelSpec.TURBO.vocabAsset}")
        listOf(
            Triple(turboJson, 51_865, 51_866),  // the turbo file where whisper-small was expected
            Triple(smallJson, 51_866, 51_865),  // the small file where large-v3/turbo was expected
        ).forEach { (json, expected, actual) ->
            try {
                WhisperBpeDecoder.fromJson(json, expected)
                fail("a $actual-entry vocabulary must be refused at expectedSize=$expected")
            } catch (refused: IllegalStateException) {
                assertTrue(
                    "the message must name both sizes — the one the spec demanded and the one " +
                        "the file resolves. Got: ${refused.message}",
                    refused.message!!.contains(expected.toString()) &&
                        refused.message!!.contains(actual.toString()),
                )
            }
        }

        // AND THE CLASS NO LONGER KNOWS EITHER FAMILY — source pins, because "the constructor
        // honours its parameter" cannot be told apart at the small family from "the constructor
        // still reads WhisperTokens.VOCAB": they agree at 51,865 and diverge only on the file
        // that has not been loaded yet. Live-scoped (the L3 lesson: a whole-file count is
        // answered by comments).
        val src = source("src/main/java/com/whispereverywhere/npu/WhisperBpeDecoder.kt")
        assertTrue(
            "WhisperBpeDecoder must take expectedSize on the constructor and on fromJson — the " +
                "logits width is the SPEC's fact, threaded in, never a constant remembered here",
            liveLines(src, "class WhisperBpeDecoder(vocabulary: List<String>, expectedSize: Int)")
                .isNotEmpty() &&
                liveLines(src, "fun fromJson(json: String, expectedSize: Int): WhisperBpeDecoder")
                    .isNotEmpty(),
        )
        assertTrue(
            "…and NEITHER may default it. `expectedSize: Int =` is the L2 no-default hazard " +
                "arriving here: a defaulted 51,865 keeps every call site compiling while the " +
                "turbo tier's decoder refuses its own correct asset. Found: " +
                liveLines(src, "expectedSize: Int ="),
            liveLines(src, "expectedSize: Int =").isEmpty(),
        )
        assertTrue(
            "and no live line of WhisperBpeDecoder.kt may read WhisperTokens — the decoder is " +
                "family-agnostic by construction: it is handed a size and drops ids at or above " +
                "WhisperTokenFamily.EOT, the one special below every family's language table. A " +
                "WhisperTokens read here is whisper-small's table leaking back into a class both " +
                "families share. Found: " + liveLines(src, "WhisperTokens."),
            liveLines(src, "WhisperTokens.").isEmpty(),
        )
    }

    // ------------------------------------------------------------------ the asset itself

    @Test
    fun theShippedAssetResolvesTheWholeVocabularyAndAnyOtherSizeIsRefused() {
        assertEquals(
            "the asset must resolve exactly ids 0 until ${WhisperTokens.VOCAB}; 51866 is " +
                "large-v3/turbo and is the wrong file",
            WhisperTokens.VOCAB,
            decoder.size,
        )

        // The load-time refusal, both directions. A vocabulary of the wrong size binds, decodes and
        // produces fluent wrong text, so it has to fail at construction.
        for (wrong in listOf(WhisperTokens.VOCAB - 1, WhisperTokens.VOCAB + 1)) {
            try {
                WhisperBpeDecoder(List(wrong) { "a" }, WhisperTokens.SMALL.vocab)
                fail("a $wrong-entry vocabulary must be refused")
            } catch (expected: IllegalStateException) {
                assertTrue(
                    "the message must name both sizes: ${expected.message}",
                    expected.message!!.contains(wrong.toString()) &&
                        expected.message!!.contains(WhisperTokens.VOCAB.toString()),
                )
            }
        }

        // A corrupt asset is an IllegalStateException too, so Q6 has ONE thing to catch. The
        // message names the material rather than a file: since 4.1 L4 this class no longer knows
        // which asset it is parsing — the caller does, and prefixes its own stage detail.
        try {
            WhisperBpeDecoder.fromJson("{\"not\":\"an array\"}", WhisperTokens.SMALL.vocab)
            fail("a non-array asset must be refused")
        } catch (expected: IllegalStateException) {
            assertTrue(
                "the message must say what the material failed to be: ${expected.message}",
                expected.message!!.contains("vocabulary"),
            )
        }

        // THE THIRD CONSTRUCTOR GUARANTEE, and the one the other two rest on: the decoder must not
        // alias the caller's list. `List<String>` is a read-only INTERFACE, not an immutable type —
        // without a defensive copy the size check and the alphabet walk above are both verdicts
        // about a moment the caller can undo afterwards, and the decoder would go on rendering
        // whatever the list became. Lives in this test rather than its own because it is the same
        // subject as the other two: what the constructor guarantees about the vocabulary it holds.
        val caller = ArrayList(List(WhisperTokens.VOCAB) { "a" })
        val decoderOverCallerList = WhisperBpeDecoder(caller, WhisperTokens.SMALL.vocab)
        caller[0] = "zzz"
        caller[1] = "zzz"
        assertEquals(
            "the decoder must hold a copy; mutating the caller's list must not change decode()",
            "aa",
            decoderOverCallerList.decode(intArrayOf(0, 1)),
        )
    }

    /**
     * THE CROSS-CHECK Q4 ASKED FOR, kept as a test rather than spent as a one-off report line.
     *
     * `WhisperTokens.LANGUAGE_CODES` was generated at Q4 from the vendored whisper.cpp `g_lang`
     * table because `added_tokens.json` was not available locally — a **second-hand** reading of the
     * tokenizer, flagged as note N-Q4-1. This asset carries the first-hand one. Diffing them once in
     * a report would confirm today's file; asserting it here also catches the next edit to either
     * side, which is the failure that matters (a wrong code produces a fluent transcript in the
     * wrong language, never an error).
     */
    @Test
    fun theShippedAssetsLanguageBlockIsExactlyWhisperTokensLanguageCodes() {
        val fromAsset = (WhisperTokens.LANG_FIRST..WhisperTokens.LANG_LAST).map { id ->
            val token = decoder.tokenAt(id)
            // `token.length >= 4` is load-bearing (4.1 L4, folding Q5 M3): "<|>" satisfies both
            // affix checks below on three characters — startsWith and endsWith may OVERLAP — and
            // substring(2, length - 2) then throws StringIndexOutOfBoundsException instead of
            // this assertion failing with its message.
            assertTrue("id $id should be a <|xx|> token but is '$token'",
                token.length >= 4 && token.startsWith("<|") && token.endsWith("|>"))
            token.substring(2, token.length - 2)
        }
        assertEquals(99, fromAsset.size)
        assertEquals(WhisperTokens.LANGUAGE_CODES, fromAsset)

        // ...and the block's edges are edges: 50358 is <|translate|>, not a 100th language.
        assertEquals("<|translate|>", decoder.tokenAt(WhisperTokens.LANG_LAST + 1))
        assertEquals("<|endoftext|>", decoder.tokenAt(WhisperTokens.LANG_FIRST - 2))
    }

    @Test
    fun theShippedAssetsSpecialIdsAreExactlyWhisperTokensSpecialIds() {
        assertEquals("<|endoftext|>", decoder.tokenAt(WhisperTokens.EOT))
        assertEquals("<|startoftranscript|>", decoder.tokenAt(WhisperTokens.SOT))
        assertEquals("<|translate|>", decoder.tokenAt(WhisperTokens.TRANSLATE))
        assertEquals("<|transcribe|>", decoder.tokenAt(WhisperTokens.TRANSCRIBE))
        assertEquals("<|notimestamps|>", decoder.tokenAt(WhisperTokens.NO_TIMESTAMPS))
        assertEquals("<|0.00|>", decoder.tokenAt(WhisperTokens.TIMESTAMP_BEGIN))

        // The 1501 timestamps run to the top of the vocabulary and nothing follows them, which is
        // what makes "drop everything at or above EOT" a complete rule rather than a mostly-complete
        // one.
        assertEquals("<|30.00|>", decoder.tokenAt(WhisperTokens.VOCAB - 1))
        assertEquals(1501, WhisperTokens.VOCAB - WhisperTokens.TIMESTAMP_BEGIN)
    }

    /**
     * THE SHIPPED LICENCE PAGE ATTRIBUTES THIS ASSET, AND UNDER THE RIGHT LICENCE (4.0, Q8).
     *
     * The Q5 review's I1, recorded there as a **4.0 ship gate**: `oss_licenses.html` attributed
     * whisper as MIT and described "model weights (all tiers)", while what this class loads is
     * 563 KB of **tokenizer vocabulary** built from `openai/whisper-small`, whose model card
     * declares `license: apache-2.0`. Two things were wrong for the material that now ships — the
     * licence, and the description that was supposed to cover it — on the app's user-facing legal
     * surface, where both licences require attribution.
     *
     * Pinned HERE because it is the same artefact this class already pins byte-for-byte: the file
     * that ships and the page that describes it are one subject, and a test in either place alone
     * lets them drift apart. Measured as a survivor first — battery row X18 reverted the line to
     * MIT and all 1,489 tests stayed green, which is what a ship gate that nothing enforces
     * actually looks like.
     */
    @Test
    fun theShippedLicencePageAttributesThisVocabularyUnderApache2() {
        val page = source("src/main/assets/oss_licenses.html")
        assertTrue(
            "the licence page names the tokenizer vocabulary as its own item, separate from the " +
                "model weights: \"model weights (all tiers)\" does not cover tokenizer DATA on its " +
                "face, which is half of what the Q5 review found wrong",
            page.contains("OpenAI Whisper tokenizer vocabulary"),
        )
        val entry = page.substringAfter("OpenAI Whisper tokenizer vocabulary")
            .substringBefore("</div>")
        assertTrue(
            "and states Apache License 2.0 for it — NOT MIT, which is what the page said before " +
                "this asset shipped and what a careless merge would restore. Found: $entry",
            entry.contains("Apache License 2.0"),
        )
        assertTrue(
            "the attribution names the upstream repository the declaration comes from, so the " +
                "claim is checkable rather than remembered",
            entry.contains("openai/whisper-small"),
        )
        assertTrue(
            "and it names the two source objects, which is what the NOTICE block in " +
                "WhisperBpeDecoder.kt records: $entry",
            entry.contains("vocab.json") && entry.contains("added_tokens.json"),
        )
        assertTrue(
            "the MIT attribution for the model WEIGHTS is untouched — this is an addition, not a " +
                "correction of the weights' licence, and conflating the two would replace one " +
                "wrong statement with another",
            page.contains("<b>OpenAI Whisper models</b>") &&
                page.substringAfter("<b>OpenAI Whisper models</b>")
                    .substringBefore("</div>").contains("MIT License"),
        )
    }

    // ------------------------------------------------------------------ the house source walker

    /**
     * Reads a repo file from the test's working directory — the locator `SegmentTimingTest`,
     * `CaptureThreadPolicyTest`, `NativeVadSourceContractTest` and `ProbeStatsTest` share
     * (`SegmentTimingTest.kt:87-96`), replicated here in its minimal form. Line endings are
     * normalized at this single read site; the vocab assets contain no newline at all, so the
     * normalisation is a no-op for them and the bytes read are the bytes shipped.
     *
     * (4.1 L4, folding Q8 M7: this KDoc sat two declarations up, orphaned onto the licence test
     * by an insertion — the same defect class as Q6's fix-round KDoc swap. It belongs to the
     * function it describes.)
     */
    private fun source(relative: String): String {
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir")!!).absoluteFile
        while (dir != null) {
            for (candidate in listOf(java.io.File(dir, relative), java.io.File(dir, "app/$relative"))) {
                if (candidate.isFile) return candidate.readText().replace("\r\n", "\n")
            }
            dir = dir.parentFile
        }
        throw AssertionError("cannot locate $relative from ${System.getProperty("user.dir")}")
    }

    /** The LIVE (non-comment) lines of [scope] containing [needle], trimmed — the house idiom. */
    private fun liveLines(scope: String, needle: String): List<String> =
        scope.split("\n").map { it.trimStart() }.filter { line ->
            !(line.startsWith("//") || line.startsWith("/*") || line.startsWith("*")) &&
                line.contains(needle)
        }
}
