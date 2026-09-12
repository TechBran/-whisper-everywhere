package com.whispereverywhere.transcription.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * The three catalogue fields that are facts about a FILE rather than decisions — `decode_chunk_len`
 * (the cadence) and `T` (the frames one forward pass consumes, and therefore the commit pad) —
 * pinned as literals AND, wherever the pack payload is on disk, re-read from the encoder's own
 * `metadata_props` and held equal to them (qualification table E1: *"add `decodeChunkLen` to
 * `StreamingPack`, derive `padMs` from `T`, and assert both against the Range-read metadata"*).
 *
 * ### Why the literals are the pin and the file is the corroboration
 *
 * The four pack files are a BUILD artifact — `tools/build_asset_packs.py preview` places them and
 * the module's `.gitignore` keeps them structurally uncommittable — so a clean clone has an empty
 * payload directory and this file cannot be a required input to anything. The literals therefore
 * carry the census, exactly as the byte counts and the sha256s do, and the live re-read runs where
 * the payload exists (a machine that has placed the pack) and is SKIPPED where it does not.
 *
 * That is not a hole: the payload can only change under a changed `sha256`, and the sha256s ARE
 * pinned inputs in three places (the catalogue, `verifyPreviewPack`'s table, the build script's
 * placement table, all held equal by `PreviewPackLayoutTest`). A payload that disagrees with these
 * literals is a payload that disagrees with its own digest first. This test is the only reader in
 * the suite that can catch the OTHER direction — a literal typed wrong against bytes that are
 * right — which is the direction a second language will make easy to get wrong, since `T` is
 * `decodeChunkLen + 13` on a `zipformer2` export and `decodeChunkLen + 7` on a v1 one (32 → 45
 * here, 32 → **39** for French) and neither is derivable from the other.
 */
class PreviewPackMetadataTest {

    private val pack = StreamingPackCatalog.EN

    // ---------------------------------------------------------------- the literals

    @Test fun theCatalogRecordsTheShippingEncodersCadenceAndFrameCount() {
        assertEquals("model_type, read off the encoder's metadata_props", "zipformer2", pack.modelType)
        assertEquals("decode_chunk_len, read off the encoder's metadata_props", 32, pack.decodeChunkLen)
        assertEquals("T, read off the same place", 45, pack.encoderT)
        assertEquals("320 ms per forward pass after the first", 320L, pack.cadenceMs)
    }

    @Test fun thePadIsDerivedFromThatFrameCountAndReproducesTheMeasuredFiveHundred() {
        // The one pad ever measured on a device (rung 1 §4, rung 3 §5.3) is 500 ms, and it was
        // measured on THIS pack. The derivation must return it, or 4.4.1's validated behaviour
        // has quietly moved under a refactor that claimed to be a generalisation.
        assertEquals(500L, pack.padMs)
        assertEquals(StreamingPreviewTuning.padMsFor(pack.encoderT), pack.padMs)
    }

    // ---------------------------------------------------------------- the file itself

    @Test fun theRecordedCadenceAndFrameCountAreWhatTheEncoderFileSays() {
        val encoder = payloadEncoder()
        assumeTrue(
            "the pack payload is a build artifact and is absent from a clean clone — " +
                "tools/build_asset_packs.py preview places it",
            encoder != null,
        )
        val meta = OnnxMetadata.read(encoder!!)
        assertEquals("model_type", pack.modelType, meta["model_type"])
        assertEquals("decode_chunk_len", pack.decodeChunkLen.toString(), meta["decode_chunk_len"])
        assertEquals("T", pack.encoderT.toString(), meta["T"])
        // And the export is the STREAMING one. The offline tell (qualification table §5.4): an
        // offline zipformer2 export writes `comment = "non-streaming zipformer2"` and OMITS
        // decode_chunk_len, query_head_dims and value_head_dims. Four plausible ja/vi candidates
        // died on exactly this read, so it is asserted here rather than remembered.
        assertEquals("streaming zipformer2", meta["comment"])
        assertTrue("query_head_dims is present on a streaming export", meta.containsKey("query_head_dims"))
        assertTrue("value_head_dims is present on a streaming export", meta.containsKey("value_head_dims"))
    }

    @Test fun everyRowsFamilyIsOneTheShippedAarCanConstructAtAll() {
        // The loader passes `modelType = ""` so the FILE chooses the family (see
        // SherpaPreviewLoaderPinTest for why anything else is a process kill). This field is the
        // catalogue's record of which choice the file will make — and the gate on adding a row
        // whose family the shipped AAR has no class for at all. A CTC row (fa) needs a second
        // config branch and a different file count; a Moonshine row (the ja candidate that looks
        // streaming and apache-2.0) has no `OnlineMoonshine*` class and 5-8 files against this
        // pack's 4 slots. Neither is a catalogue row — both are a new pack SHAPE.
        for (p in StreamingPackCatalog.packs) {
            assertTrue(
                "${p.language}: model_type '${p.modelType}' is not a transducer family this AAR " +
                    "builds — a row outside {zipformer, zipformer2} is a new pack shape, not a row",
                p.modelType in setOf("zipformer", "zipformer2"),
            )
        }
    }

    // ---------------------------------------------------------------- the copy flags

    @Test fun theCatalogRecordsTheFlagsTheCopyDerivesFrom() {
        // The flag matrix's home (qualification table §4.1). English is the row that folds, in the
        // locale chosen for English INPUT, with no punctuation and no digits — which is exactly why
        // the fields are not a free abstraction: `et` would be the FIRST row to set both booleans
        // true, `ko`/`zh` are the first `Keep` rows, and `tr` is the first row where the fold
        // locale decides anything.
        assertEquals(CaseFold.Fold, pack.caseFold)
        assertEquals(false, pack.emitsPunctuation)
        assertEquals(false, pack.emitsDigits)
    }

    @Test fun everyFoldRowFoldsInItsOwnLanguageAndEveryRowsLanguageIsATagTheJdkCanParse() {
        // The row-by-row half of the case fix. A `Fold` row's locale is DERIVED from its language
        // (`PreviewText.normalize`), so the only way this arm can be wrong is for the language
        // itself to be a tag `forLanguageTag` cannot parse — which returns `und`, folds like ROOT,
        // and is silently right everywhere except `tr`/`az`/`lt`, i.e. silently wrong in exactly
        // the place the derivation exists for. `Locale.forLanguageTag("tr_TR")` is that mistake
        // (underscores are not language-tag syntax), and it is the one this loop can still catch.
        val probe = "ISPARTA İSTANBUL NBA"
        for (p in StreamingPackCatalog.packs) {
            assertTrue(
                "${p.language}: a row's language must be a tag forLanguageTag can parse — the fold " +
                    "locale IS this tag, and an unparseable one folds like ROOT",
                Locale.forLanguageTag(p.language).language.isNotEmpty(),
            )
            when (p.caseFold) {
                CaseFold.Fold -> assertEquals(
                    "${p.language}: a Fold row folds in its OWN language",
                    probe.lowercase(Locale.forLanguageTag(p.language)),
                    PreviewText.normalize(probe, p),
                )
                CaseFold.Keep -> assertEquals(
                    "${p.language}: a Keep row emits the case the model produced",
                    probe,
                    PreviewText.normalize(probe, p),
                )
            }
        }
    }

    @Test fun aFoldRowsLocaleIsNotAValueAnyRowCanCarrySoItCannotDisagreeWithTheLanguage() {
        // The structural half, and the half round 1 left open: making an INERT locale
        // unrepresentable did not make a WRONG one unrepresentable. `Fold(Locale.US)` on a `tr` row
        // compiled, rendered `i̇stanbul` (i + U+0307, a stray mark on the strip) for every capital
        // İ, and no assertion in the suite could tell it from a deliberate choice — the loop that
        // looked like it pinned the locale computed its expected value with the row's own
        // `fold.locale`, so it passed for whatever the row carried. There is now no locale to
        // carry: the same answer on two rows folds two ways, and the LANGUAGE is what decides.
        val probe = "ISPARTA İSTANBUL NBA"
        val tr = StreamingPackCatalog.EN.copy(language = "tr")
        assertEquals(CaseFold.Fold, tr.caseFold)
        assertEquals("ısparta istanbul nba", PreviewText.normalize(probe, tr))
        assertEquals("isparta i̇stanbul nba", PreviewText.normalize(probe, StreamingPackCatalog.EN))
        assertEquals(probe, PreviewText.normalize(probe, StreamingPackCatalog.EN.copy(caseFold = CaseFold.Keep)))
        // And 4.4.1's rendering, asserted against the locale the row used to name rather than
        // against the row: `forLanguageTag("en")` and `Locale.US` fold the same characters, which
        // is the whole reason dropping the field cannot have moved the shipping strip.
        assertEquals(probe.lowercase(Locale.US), PreviewText.normalize(probe, StreamingPackCatalog.EN))
        // Finally the claim "there is no locale to carry", asserted instead of asserted in prose,
        // because it is one field away from stopping being true and the failure it re-opens is
        // silent. A third answer carrying a locale on purpose (the `crh` case CaseFold.Fold's KDoc
        // records) is a deliberate edit that adds a `when` branch; this pin covers the two answers
        // that exist and the row they sit on.
        for (c in listOf(CaseFold::class.java, CaseFold.Fold::class.java, CaseFold.Keep::class.java, StreamingPack::class.java)) {
            assertTrue(
                "${c.simpleName} declares a Locale field: a hand-authored fold locale is back, and " +
                    "Fold(Locale.US) on a tr/az/lt row is then a typo no test can see",
                c.declaredFields.none { Locale::class.java.isAssignableFrom(it.type) },
            )
        }
    }

    @Test fun theRecordedFlagsAreWhatThePacksOwnTokensFileSays() {
        val tokens = payloadFile(pack, pack.tokens.name, pack.tokens.bytes)
        assumeTrue("the pack payload is absent from a clean clone", tokens != null)
        val facts = PackTokenFacts.of(tokens!!)
        // The census, line by line — and the correction to 4.4.0's pinned comment, which said
        // "497 uppercase pieces, no lowercase, no digits" and was wrong three times over. 497 is
        // the EMITTABLE count; the uppercase-bearing count is 495; there are 3 lowercase-bearing
        // pieces and 2 digit-bearing ones, and every one of those five is a piece no decode emits.
        assertEquals(502, facts.lines)
        assertEquals(495, facts.uppercaseInFile)
        assertEquals(3, facts.lowercaseInFile)
        assertEquals(2, facts.digitsInFile)
        // And the set the flags are actually about: the three lowercase pieces are the specials
        // and the two digit-bearing ones are the placeholders, so NOTHING emittable carries either.
        assertEquals(497, facts.emittable)
        assertEquals(495, facts.uppercaseEmittable)
        assertEquals(0, facts.lowercaseEmittable)
        assertEquals(0, facts.digitsEmittable)
        assertEquals("one punctuation piece, the apostrophe", listOf("'"), facts.punctuationOnly)
        // (4.5.0 T4) The word-boundary census the NOUN rests on: 338 pieces carry the marker and
        // start a word, the bare marker is in the file (at id 34), and only 27 unmarked pieces are
        // single characters — 5.4% of the emittable vocabulary, against ko's 100% and zh-en's
        // 92.5%. So this file PROVES the strip shows words, which is the claim `ADDITIVE` makes.
        assertEquals(338, facts.wordMarkedEmittable)
        assertTrue(facts.bareWordMarker)
        assertEquals(27, facts.unmarkedSingleCharEmittable)
        assertTrue(facts.wordsAreProvablyTheUnit)
        assertEquals(StripUnit.WORDS, pack.stripUnit)
        // And the derivation agrees with what the catalogue claims, for the two flags a token file
        // can actually compute.
        assertEquals(pack.emitsPunctuation, facts.emitsPunctuation)
        assertEquals(pack.emitsDigits, facts.emitsDigits)
        // The case decision is not one of them — a `tokens.txt` cannot compute it, because English
        // is 495 uppercase / 0 lowercase and MUST fold while zh is 0 / 0 and must NOT. What the
        // file can prove is SUFFICIENCY, one-directionally: single-case AND no byte fallback means
        // folding this pack cannot lose a character. English satisfies both conjuncts, which is
        // why this row takes the suggestion rather than overriding it.
        assertEquals("no byte fallback: English BPE has no <0xNN> piece", false, facts.hasByteFallback)
        assertEquals("single-case: 495 uppercase-bearing, 0 lowercase", false, facts.vocabularyIsMixedCase)
        assertTrue("so the fold this row takes is PROVABLY lossless", facts.foldIsProvablyLossless)
        assertTrue("and the row takes it", pack.caseFold is CaseFold.Fold)
    }

    // ------------------------------------------------------- every row, not just the shipping one

    @Test fun everyRowsRecordedMetadataIsWhatItsOwnEncoderFileSays() {
        // (4.5.0 T1) The same corroboration as the English arm above, over the whole catalogue —
        // and it is the ONLY reader in the suite that can catch a literal typed wrong against
        // bytes that are right, which is exactly the direction six new rows make easy. It runs
        // wherever `tools/build_asset_packs.py` has placed a payload and is SKIPPED where it has
        // not, so a clean clone is green and a machine that has built the packs is strict.
        var checked = 0
        for (p in StreamingPackCatalog.packs) {
            val encoder = payloadFile(p, p.encoder.name, p.encoder.bytes) ?: continue
            checked++
            val meta = OnnxMetadata.read(encoder)
            assertEquals("${p.language} model_type", p.modelType, meta["model_type"])
            assertEquals("${p.language} decode_chunk_len", p.decodeChunkLen.toString(), meta["decode_chunk_len"])
            assertEquals("${p.language} T", p.encoderT.toString(), meta["T"])
            // The STREAMING tell, per family. A zipformer2 online export writes
            // `comment = "streaming zipformer2"` and the two head-dims keys; its OFFLINE twin
            // writes `comment = "non-streaming zipformer2"` and omits decode_chunk_len and both
            // head-dims keys — one Range read separates them, and it is how four plausible ja/vi
            // candidates a name search would have promoted were killed.
            //
            // A `zipformer` V1 export writes NEITHER a comment NOR the head-dims keys, and that
            // is not a defect: `OnlineZipformerTransducerModel` does not read them. For v1 the
            // tell is `decode_chunk_len` itself, asserted just above.
            when (p.modelType) {
                "zipformer2" -> {
                    assertEquals("${p.language} comment", "streaming zipformer2", meta["comment"])
                    for (key in listOf("query_head_dims", "value_head_dims", "num_heads")) {
                        assertTrue("${p.language}: $key is present on a zipformer2 export", meta.containsKey(key))
                    }
                }
                "zipformer" -> {
                    // The keys the v1 encoder reader takes, all seven, each through a macro whose
                    // miss path is `_Exit(-1)`. A row missing one of these is an uncatchable
                    // process kill and not a caught load failure, which is why it is asserted
                    // against the FILE and not remembered.
                    for (key in listOf(
                        "encoder_dims", "attention_dims", "num_encoder_layers",
                        "cnn_module_kernels", "left_context_len", "T", "decode_chunk_len",
                    )) {
                        assertTrue("${p.language}: v1 encoder key $key is absent", meta.containsKey(key))
                    }
                    assertFalse("${p.language}: a v1 export carries no comment", meta.containsKey("comment"))
                    assertFalse("${p.language}: a v1 export carries no query_head_dims", meta.containsKey("query_head_dims"))
                }
                else -> throw AssertionError("${p.language}: unhandled family '${p.modelType}'")
            }
        }
        assumeTrue("no pack payload has been placed on this machine", checked > 0)
    }

    @Test fun everyRowsDECODERCarriesTheTwoKeysTheSameReaderTakesOffIt() {
        // The half the qualification table did not check. The transducer readers take NINE keys,
        // not seven: seven off the encoder and `vocab_size` + `context_size` off the DECODER,
        // through the same `SHERPA_ONNX_READ_META_DATA` macro and therefore the same `_Exit(-1)`.
        // A row whose encoder is complete and whose decoder is not dies the same uncatchable way.
        var checked = 0
        for (p in StreamingPackCatalog.packs) {
            val decoder = payloadFile(p, p.decoder.name, p.decoder.bytes) ?: continue
            checked++
            val meta = OnnxMetadata.read(decoder)
            assertTrue("${p.language}: the decoder must carry vocab_size", meta.containsKey("vocab_size"))
            assertEquals("${p.language}: context_size", "2", meta["context_size"])
        }
        assumeTrue("no pack payload has been placed on this machine", checked > 0)
    }

    @Test fun everyRowsCopyFlagsAreWhatItsOwnTokensFileSays() {
        // The flags are DERIVED, per row, from the row's own vocabulary — so adding a language is
        // a mechanical read and not a judgement copied forward. The two booleans are computable
        // from a token file; the case decision is not (English is 495 upper / 0 lower and MUST
        // fold while the monolingual zh row is 0 / 0 and must NOT), so what is asserted for it is
        // SUFFICIENCY, one-directionally.
        var checked = 0
        for (p in StreamingPackCatalog.packs) {
            val tokens = payloadFile(p, p.tokens.name, p.tokens.bytes) ?: continue
            checked++
            val facts = PackTokenFacts.of(tokens)
            assertEquals("${p.language} emitsPunctuation", p.emitsPunctuation, facts.emitsPunctuation)
            assertEquals("${p.language} emitsDigits", p.emitsDigits, facts.emitsDigits)
            // (4.5.0 T4) THE NOUN, held to the same rule as the case decision: the file proves
            // it, or the row owes a measurement. `wordsAreProvablyTheUnit` is one-directional, so
            // a `true` here is a `WORDS` row and nothing else, while a `false` leaves two shapes
            // and each has to be the one the row claims.
            if (facts.wordsAreProvablyTheUnit) {
                assertEquals(
                    "${p.language}: its own vocabulary proves the strip shows words — " +
                        "${facts.wordMarkedEmittable} marked pieces and " +
                        "${facts.unmarkedSingleCharEmittable} unmarked single characters of " +
                        "${facts.emittable} emittable",
                    StripUnit.WORDS, p.stripUnit,
                )
            } else when (p.stripUnit) {
                // ko. The vocabulary has NO marked piece at all, so the only boundary it can
                // produce is the bare marker — and that a decode emits one per word is the
                // measurement in `PreviewCanaryClipsTest`, not anything this file can say. What
                // is asserted here is the SHAPE that leaves the measurement as the only evidence
                // available: a row claiming words while it has marked pieces AND a
                // character-dominated vocabulary would be claiming both units and calling it one.
                StripUnit.WORDS -> {
                    assertEquals(
                        "${p.language}: a WORDS row its own file cannot prove must have no " +
                            "marked piece at all — its boundary is the bare marker, and the " +
                            "measurement is what says the decode emits it",
                        0, facts.wordMarkedEmittable,
                    )
                    assertTrue(
                        "${p.language}: ...and that bare marker has to be IN the vocabulary",
                        facts.bareWordMarker,
                    )
                }
                // zh-en. Both halves are read off the file: marked pieces exist (the Latin word
                // starts) and single characters dominate (the Han pieces, not one of them marked).
                StripUnit.CHARACTERS_AND_WORDS -> {
                    assertTrue(
                        "${p.language}: the WORDS half needs marked pieces to come from",
                        facts.wordMarkedEmittable > 0,
                    )
                    assertTrue(
                        "${p.language}: and the CHARACTERS half needs the characters to dominate",
                        facts.unmarkedSingleCharEmittable * 2 > facts.emittable,
                    )
                }
            }
            when (p.caseFold) {
                // No row in THIS catalogue overrides the suggestion: every folding row's own
                // vocabulary proves the fold cannot lose a character. `tr` would be the first
                // override (448 lower / 34 upper is mixed and the table rules Fold anyway), and
                // this assertion is what would make that row have to carry its reason in writing.
                CaseFold.Fold -> assertTrue(
                    "${p.language}: a Fold row here must be one its own tokens.txt proves is " +
                        "lossless — single-case AND no byte fallback",
                    facts.foldIsProvablyLossless,
                )
                CaseFold.Keep -> assertFalse(
                    "${p.language}: a Keep row's vocabulary must have case worth keeping",
                    facts.foldIsProvablyLossless,
                )
            }
        }
        assumeTrue("no pack payload has been placed on this machine", checked > 0)
    }

    /** The encoder under the pack module's payload directory, or null when it has not been placed. */
    private fun payloadEncoder(): File? = payloadFile(pack, pack.encoder.name, pack.encoder.bytes)

    /**
     * One payload file at its pinned byte count, or null — the payload is a BUILD artifact.
     *
     * The directory is the pack's OWN (`<packName>/src/main/assets/<packName>/`), because Play
     * strips a `#group_` suffix on delivery and these packs carry none, so the delivered directory
     * is the pack's name — and no two packs may ship the same entry path. A row with no pack
     * module is fallback-only and has no payload directory to look in.
     */
    private fun payloadFile(pack: StreamingPack, name: String, bytes: Long): File? {
        val module = pack.packName ?: return null
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        val relative = "$module/src/main/assets/$module/$name"
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile && candidate.length() == bytes) return candidate
            dir = dir.parentFile
        }
        return null
    }
}
