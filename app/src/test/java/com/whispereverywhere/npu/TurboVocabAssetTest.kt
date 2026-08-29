package com.whispereverywhere.npu

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * `whisper_vocab_turbo.json` — the `LARGE_V3` family's vocabulary, asserted against the bytes that
 * ship (4.1 L4).
 *
 * ### What this asset IS, stated once
 *
 * The two published whisper families share one base BPE: 50,257 tokens, ids `0..50256`, identical
 * byte for byte — **measured**, not assumed, against turbo's own published tokenizer table
 * (`voice_ai/vocab.bin`, parsed by `tools/build_turbo_vocab.py` with exactly one known, explained
 * mismatch — see that script). Everything above the base is a fixed special layout derived from the
 * language count: `large-v3` has 100 languages where `whisper-small` has 99, so its band runs one id
 * further (`<|yue|>` at 50358) and every control token above it sits one higher, ending at a
 * 51,866-entry file.
 *
 * So the shipped asset is **built** from the shipped small asset's base plus the `LARGE_V3` layout,
 * and **verified** against turbo's own table — `vocab.bin` is the verifier, not the source, because
 * NUL-terminated storage cannot carry the one token that IS a NUL byte (id 188). The spellings match
 * the small asset's exactly (including `<|nocaptions|>`, which is what that asset uses where
 * Hugging Face writes `<|nospeech|>`), so the two files are diffable: the turbo file is the small
 * file with ONE entry inserted after `<|su|>`.
 *
 * ### Why the assertions are the ones they are
 *
 * A wrong vocabulary here is the quiet failure shape: it parses, it binds, `WhisperBpeDecoder`
 * walks it happily, and the transcript renders another family's specials as text boundaries. So
 * the pins target the exact mutations — the base diverging from the small asset's (which would
 * falsify the shared-base claim the whole family design rests on), the special layout shifting,
 * the one NUL-casualty token going missing, and the builder's verification quietly loosening from
 * an exact count to a threshold.
 */
class TurboVocabAssetTest {

    // ---------------------------------------------------------------- the base is SHARED

    /**
     * **THE ASSERTION THIS TASK EXISTS FOR.** Entries `0 until 50257` of the turbo asset are
     * element-for-element identical to the small asset's — the claim the whole design rests on,
     * asserted rather than argued.
     *
     * If any base entry differed, the two families would not share a vocabulary, the golden
     * vectors `WhisperBpeDecoderTest` runs against both decoders could not be the same vectors,
     * and the builder's cross-check against turbo's own `vocab.bin` (exactly one mismatch, at the
     * NUL-casualty id) would be measuring a different file than this one.
     */
    @Test
    fun theTurboBaseIsTheSmallBaseElementForElement() {
        assertEquals("the small asset resolves ids 0..51864", 51_865, small.size)
        assertEquals("the turbo asset resolves ids 0..51865", 51_866, turbo.size)
        for (id in 0 until 50_257) {
            if (small[id] != turbo[id]) {
                throw AssertionError(
                    "base id $id differs between the two shipped vocabularies: small has " +
                        "'${small[id]}' and turbo has '${turbo[id]}'. The base BPE is SHARED " +
                        "between the families — measured against turbo's own vocab.bin — so a " +
                        "divergence here means one of the two files is not the vocabulary its " +
                        "family's model was trained on, and every transcript it renders is " +
                        "quietly wrong."
                )
            }
        }
        assertEquals(
            "…and the whole prefix relation holds one step further: the turbo file is the small " +
                "file with exactly ONE entry inserted after <|su|> (50357), so everything below " +
                "the insertion matches too",
            small.subList(0, 50_358),
            turbo.subList(0, 50_358),
        )
        assertEquals(
            "and everything above the insertion is the small tail shifted by one",
            small.subList(50_358, 51_865),
            turbo.subList(50_359, 51_866),
        )
    }

    // ---------------------------------------------------------------- the special layout

    /**
     * The `LARGE_V3` special layout, id by id, against the derivation in [WhisperTokens.LARGE_V3].
     *
     * Two readings: the family computes these ids from `langCount = 100`, and the asset carries a
     * token string at each index. Asserting the pair at every special is what makes the derivation
     * a reading of THIS file rather than a formula that agrees with itself.
     */
    @Test
    fun theShippedAssetCarriesTheLargeV3SpecialLayout() {
        val family = WhisperTokens.LARGE_V3
        listOf(
            family.eot to "<|endoftext|>",
            family.sot to "<|startoftranscript|>",
            family.langFirst to "<|en|>",
            family.langLast to "<|yue|>",
            family.translate to "<|translate|>",
            family.transcribe to "<|transcribe|>",
            family.startOfLm to "<|startoflm|>",
            family.startOfPrev to "<|startofprev|>",
            // The small asset's spelling, kept deliberately: Hugging Face writes <|nospeech|>
            // for this slot, the shipped small asset writes <|nocaptions|>, and the two files
            // must stay diffable — one insertion apart, not one insertion and one respelling.
            family.noSpeech to "<|nocaptions|>",
            family.noTimestamps to "<|notimestamps|>",
            family.timestampBegin to "<|0.00|>",
            (family.vocab - 1) to "<|30.00|>",
        ).forEach { (id, token) ->
            assertEquals("turbo asset id $id", token, turbo[id])
        }
        // The absolute numbers too, so the pair above cannot both drift with a wrong derivation.
        assertEquals("<|endoftext|>", turbo[50_257])
        assertEquals("<|startoftranscript|>", turbo[50_258])
        assertEquals("<|en|>", turbo[50_259])
        assertEquals("<|yue|>", turbo[50_358])
        assertEquals("<|translate|>", turbo[50_359])
        assertEquals("<|transcribe|>", turbo[50_360])
        assertEquals("<|notimestamps|>", turbo[50_364])
        assertEquals("<|0.00|>", turbo[50_365])
        assertEquals("<|30.00|>", turbo[51_865])
        assertEquals(
            "1,501 timestamps run from timestampBegin to the top of the file, so the drop rule " +
                "(everything at or above EOT) stays complete under this family too",
            1_501,
            51_866 - 50_365,
        )
    }

    /**
     * The language block is the family's 100 codes **in id order** — `<|xx|>` at `50259 + index`,
     * ending on `<|yue|>` — and its edges are edges.
     */
    @Test
    fun theLanguageBlockIsTheFamilysHundredCodesInIdOrder() {
        val family = WhisperTokens.LARGE_V3
        val fromAsset = (family.langFirst..family.langLast).map { id ->
            val token = turbo[id]
            assertTrue(
                "id $id should be a <|xx|> token but is '$token'",
                token.length >= 4 && token.startsWith("<|") && token.endsWith("|>"),
            )
            token.substring(2, token.length - 2)
        }
        assertEquals(100, fromAsset.size)
        assertEquals(
            "the block must be the family's codes in the family's order — the order the model's " +
                "embedding rows are in. Right as a set and wrong as a sequence prompts every " +
                "language with some other language's row and transcribes fluently under it.",
            family.languageCodes,
            fromAsset,
        )
        assertEquals("…and the small asset's block is its strict PREFIX",
            WhisperTokens.LANGUAGE_CODES, fromAsset.subList(0, 99))
        assertEquals(
            "the id past the band is <|translate|>, not a 101st language",
            "<|translate|>",
            turbo[family.langLast + 1],
        )
        assertEquals(
            "and the id below the band is <|startoftranscript|>",
            "<|startoftranscript|>",
            turbo[family.langFirst - 1],
        )
    }

    // ---------------------------------------------------------------- every entry decodes

    /**
     * The whole file passes the byte-level-alphabet check — by constructing the real decoder over
     * it — and the two ids with a story are pinned by name.
     *
     * Id 188 is the `0x00` byte token, stored as `U+0100` (byte-level encoding). It is **the one
     * token turbo's own NUL-terminated `vocab.bin` cannot represent**, which makes it the one id
     * the builder's cross-check must except — so its presence HERE, on the shipped side, is what
     * closes the research doc's blocker #3 in the direction that matters: the shipped file is the
     * *correct* side of the single known difference. Id 50256 is whisper's legitimately empty
     * slot in both families, so it is readable and contributes no bytes.
     */
    @Test
    fun everyEntryIsInsideTheByteLevelAlphabetAndTheNulTokenShips() {
        // The constructor IS the alphabet walk: it refuses any token containing a character the
        // byte-level table has no byte for, and it refuses any entry count other than the one
        // asked for. Constructing at 51,866 is therefore two structural claims at once.
        val decoder = WhisperBpeDecoder(turbo, 51_866)
        assertEquals(51_866, decoder.size)
        assertEquals(
            "id 188 must be U+0100 — the byte-level encoding of 0x00. A regeneration that " +
                "sourced the base from vocab.bin instead of the shipped small asset would drop " +
                "exactly this token (NUL-terminated storage cannot carry it), and the only " +
                "symptom would be one unrenderable byte in transcripts that contain a NUL.",
            "Ā",
            turbo[188],
        )
        assertEquals(
            "id 50256 is whisper's empty-string slot in BOTH families — a real, readable id " +
                "that contributes no bytes, and not a second NUL casualty",
            "",
            turbo[50_256],
        )
        assertEquals("", decoder.decode(intArrayOf(50_256)))
    }

    // ---------------------------------------------------------------- the builder

    /**
     * `tools/build_turbo_vocab.py`'s verification is EXACT, and its literals are pinned so it
     * cannot be quietly loosened to a threshold.
     *
     * The script's cross-check parses turbo's own published `vocab.bin`, byte-level-encodes each
     * token, and requires **exactly 50,257 tokens and exactly one mismatch, at id 188**. A
     * *second* mismatch anywhere would mean the two families' base vocabularies are not the same
     * vocabulary — the assumption this whole task rests on — hence the exact count. A threshold
     * ("at most a few mismatches") would accept precisely the divergence the check exists to
     * refuse, while still passing every run it has ever seen.
     */
    @Test
    fun theBuilderPinsTheExactBaseCountAndTheOneKnownMismatch() {
        val script = text("tools/build_turbo_vocab.py")
        // THE NEEDLES ARE THE CHECK LINES, NOT THE NUMBERS. `script.contains("188")` would be
        // answered by the module docstring, which discusses the mismatch at length — the L3
        // lesson (a whole-file count answered by comments is a false green), caught here while
        // DESIGNING the battery rather than by it: the loosen-to-a-threshold mutant keeps every
        // number in the prose and edits only the comparison.
        assertTrue(
            "the builder must pin the base count as a named constant AND compare with equality — " +
                "`EXPECT_BASE_TOKENS = 50257` and `len(raw_tokens) == EXPECT_BASE_TOKENS`. " +
                "Loosened to a range, a truncated vocab.bin would verify a truncated base.",
            script.contains("EXPECT_BASE_TOKENS = 50257") &&
                script.contains("len(raw_tokens) == EXPECT_BASE_TOKENS"),
        )
        assertTrue(
            "…and the ONE permitted mismatch as the exact singleton list — " +
                "`EXPECT_MISMATCH_ID = 188` and `mismatches == [EXPECT_MISMATCH_ID]`. Accepting " +
                "'any single mismatch' would accept a corrupt base entry as readily as the known " +
                "NUL casualty, and a second mismatch anywhere means the two families do not " +
                "share a base vocabulary at all.",
            script.contains("EXPECT_MISMATCH_ID = 188") &&
                script.contains("mismatches == [EXPECT_MISMATCH_ID]"),
        )
        assertTrue(
            "…and must build from the SHIPPED small asset (whisper_vocab.json), with vocab.bin " +
                "as the verifier rather than the source — vocab.bin cannot carry id 188, so a " +
                "builder that sourced from it would ship a base with a hole in it",
            script.contains("whisper_vocab.json") && script.contains("vocab.bin"),
        )
        assertTrue(
            "the builder must run under the ABSOLUTE interpreter, like tools/extract_melbank.py: " +
                "bare `python` on this machine is the Windows-Store alias stub, which resolves " +
                "first and does nothing",
            script.contains("Python313/python.exe") || script.contains("Python313\\python.exe"),
        )
        assertTrue(
            "and it must pin its OUTPUT's sha256 as a literal AND actually compare it — " +
                "`actual_output == OUTPUT_SHA256`, asserted after a re-read of the written file. " +
                "The literal alone survives a bypassed comparison (the N11 shape: a claim about " +
                "text standing in for a claim about a predicate); without either the script is a " +
                "concatenation with no opinion about what it produced.",
            script.contains(TURBO_SHA256) &&
                script.contains("actual_output == OUTPUT_SHA256"),
        )
    }

    // ---------------------------------------------------------------- the readings agree

    /**
     * The spec row, the family and the shipped file are **one value read three ways**, and
     * `vocabAsset` has no default.
     *
     * `NpuModelSpec.TURBO.vocabAsset` is the string `AssetManager.open()` receives at run time;
     * `WhisperTokens.LARGE_V3.vocab` is the entry count the decoder will demand of whatever that
     * name resolves to; and this file is what the name actually resolves to. Any pair agreeing
     * without the third is a tier that declines at load — or worse, decodes under the wrong table.
     */
    @Test
    fun theSpecTheAssetAndTheFamilyAreOneValue() {
        assertEquals(
            "NpuModelSpec.TURBO.vocabAsset must be the name of the file that actually ships — " +
                "it is the string handed to AssetManager.open() at run time, and a typo there " +
                "is a FileNotFoundException on a device rather than a red here",
            NpuModelSpec.TURBO.vocabAsset,
            assetFile.name,
        )
        assertEquals(
            "…and the SMALL row must name the 4.0 asset, unchanged",
            "whisper_vocab.json",
            NpuModelSpec.SMALL.vocabAsset,
        )
        assertEquals(
            "the family's vocab is the entry count of the file its row names — one fact, stated " +
                "by the derivation and by the artefact",
            WhisperTokens.LARGE_V3.vocab,
            turbo.size,
        )
        assertEquals(
            "the file must live in app/src/main/assets, the only directory AssetManager can " +
                "reach — a correctly-named file one directory up is invisible to the APK",
            "assets",
            assetFile.parentFile?.name,
        )
        assertEquals(
            "the shipped bytes must hash to the digest the builder refuses to write anything " +
                "else under — the same two-readings rule as melbank-128.bin",
            TURBO_SHA256,
            sha256(assetFile.readBytes()),
        )
        // AND `vocabAsset` HAS NO DEFAULT — the L2 no-default doctrine at the field that decides
        // which vocabulary a tier decodes with. `vocabAsset: String = "whisper_vocab.json"`
        // compiles, keeps SMALL working, and hands every future row whisper-small's table
        // silently — the wrong-family failure with nothing to see. A source pin because no call
        // can observe a default's absence: a construction that omitted the argument would not
        // compile, so there is nothing to execute.
        val spec = text("src/main/java/com/whispereverywhere/npu/NpuModelSpec.kt")
        assertTrue(
            "NpuModelSpec must declare `val vocabAsset: String,` as a constructor field",
            spec.contains("val vocabAsset: String,"),
        )
        assertTrue(
            "…with NO DEFAULT. Found: " +
                spec.lines().filter { it.contains("vocabAsset: String") },
            !spec.contains("val vocabAsset: String ="),
        )
    }

    // ---------------------------------------------------------------- the two ship gates

    /**
     * The shipped licence page's tokenizer-vocabulary attribution covers BOTH bundled
     * vocabularies, and names the turbo one's derivation and verifier.
     *
     * Q5's I1 made `oss_licenses.html` a pin-protected ship gate (measured there as a battery
     * survivor: reverting an attribution left every test green). This asset is the same class of
     * material as the small vocabulary — tokenizer DATA under Apache-2.0 — plus a derivation
     * claim: built from the shipped base, verified against the AI Hub package's own `vocab.bin`.
     */
    @Test
    fun theShippedLicencePageAttributesTheTurboVocabulary() {
        val page = text("src/main/assets/oss_licenses.html")
        val entry = page.substringAfter("OpenAI Whisper tokenizer vocabulary")
            .substringBefore("</div>")
        assertTrue(
            "the tokenizer-vocabulary entry must name the turbo file (or its model) — the page " +
                "attributed one vocabulary and the APK now carries two. Found: $entry",
            entry.contains("large-v3-turbo") || entry.contains("whisper_vocab_turbo"),
        )
        assertTrue(
            "…and name vocab.bin, the published table the derived file was verified against, so " +
                "the provenance is checkable rather than remembered. Found: $entry",
            entry.contains("vocab.bin"),
        )
        assertTrue(
            "the entry must still carry Apache License 2.0 — both vocabularies are tokenizer " +
                "data under the same declaration, and the turbo addition must not have displaced " +
                "the licence the small one shipped under. Found: $entry",
            entry.contains("Apache License 2.0"),
        )
    }

    /**
     * The asset and the builder are declared inputs of the test task — the stale-evidence hazard,
     * and it bites hardest exactly here: a JSON asset and a Python script are inputs to no compile
     * task at all, so regenerating either wrongly changes not one `.class` file, and without these
     * entries every assertion in this suite passes against the file as it used to be.
     */
    @Test
    fun theAssetAndTheBuilderAreDeclaredInputsOfTheTestTask() {
        val gradle = text("build.gradle.kts")
        assertTrue(
            "app/build.gradle.kts must list \"src/main/assets/whisper_vocab_turbo.json\" in " +
                "sourcePinnedInputs — this suite and WhisperBpeDecoderTest are its only readers",
            gradle.contains("\"src/main/assets/whisper_vocab_turbo.json\""),
        )
        assertTrue(
            "…and rootProject.file(\"tools/build_turbo_vocab.py\"), for the same reason and one " +
                "step further out: the script lives outside the app module, so it is not even a " +
                "candidate input by convention. Same shape as tools/extract_melbank.py beside it.",
            gradle.contains("rootProject.file(\"tools/build_turbo_vocab.py\")"),
        )
    }

    // ---------------------------------------------------------------- the house source walker

    companion object {

        /** The shipped turbo asset's sha256 — the value `tools/build_turbo_vocab.py` also pins. */
        private const val TURBO_SHA256: String =
            "9977eaba032c191dc0da8514078626fba6c49b93fe66387774c52760226fa415"

        private val JSON = Json {}

        /**
         * Locates a repo file from the test's working directory — the walker `MelbankAssetTest`,
         * `NpuNativeContractTest` and `SegmentTimingTest` share, stopping at the [File] so the
         * asset can be read as bytes where a digest is taken.
         */
        private fun locate(relative: String): File {
            var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
            while (dir != null) {
                for (candidate in listOf(File(dir, relative), File(dir, "app/$relative"))) {
                    if (candidate.isFile) return candidate
                }
                dir = dir.parentFile
            }
            throw AssertionError("cannot locate $relative from ${System.getProperty("user.dir")}")
        }

        /** A repo file's text, LF-normalised at this single read site (the 3.7 N1 lesson). */
        private fun text(relative: String): String =
            locate(relative).readText().replace("\r\n", "\n")

        private fun sha256(of: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(of).joinToString("") { "%02x".format(it) }

        private val assetFile: File by lazy {
            locate("src/main/assets/whisper_vocab_turbo.json")
        }

        /**
         * Both vocabularies, parsed ONCE for the whole suite (they are ~1.1 MB of JSON together
         * and JUnit builds a fresh instance per test). Neither list is mutated anywhere.
         */
        private val turbo: List<String> by lazy {
            JSON.decodeFromString(ListSerializer(String.serializer()), assetFile.readText())
        }

        private val small: List<String> by lazy {
            JSON.decodeFromString(
                ListSerializer(String.serializer()),
                locate("src/main/assets/whisper_vocab.json").readText(),
            )
        }
    }
}
