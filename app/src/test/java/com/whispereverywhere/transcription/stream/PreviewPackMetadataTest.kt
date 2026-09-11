package com.whispereverywhere.transcription.stream

import org.junit.Assert.assertEquals
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
        assertEquals(CaseFold.Fold(Locale.US), pack.caseFold)
        assertEquals(false, pack.emitsPunctuation)
        assertEquals(false, pack.emitsDigits)
    }

    @Test fun aKeepRowCannotCarryAFoldLocaleAndAFoldRowCannotFoldWithoutOne() {
        // The structural half of the fix, and the reason `caseFold` is a type rather than a boolean
        // plus a `normalizeLocale` field. Under the pair, the locale was read ONLY on the fold
        // branch while `tr` derived "cased" from its 34 uppercase pieces — so the field could not
        // change one character of output on any of the fifteen rows in the table, and the two KDoc
        // sentences calling Turkish the row it was load-bearing on were both false. A locale that
        // exists only inside `Fold` cannot be inert: a row either folds and says with what, or
        // keeps and has no locale at all.
        val probe = "ISPARTA İSTANBUL NBA"
        for (p in StreamingPackCatalog.packs) {
            when (val fold = p.caseFold) {
                is CaseFold.Fold -> assertEquals(
                    "${p.language}: the row's own locale is what the strip folds in",
                    probe.lowercase(fold.locale),
                    PreviewText.normalize(probe, p),
                )
                CaseFold.Keep -> assertEquals(
                    "${p.language}: a Keep row emits the case the model produced",
                    probe,
                    PreviewText.normalize(probe, p),
                )
            }
        }
        // And the branch no catalogue row takes today, so the wiring is pinned in both directions
        // before a second row lands: the SAME text, the SAME code, two rows, two answers.
        val tr = StreamingPackCatalog.EN.copy(caseFold = CaseFold.Fold(Locale.forLanguageTag("tr")))
        assertEquals("ısparta istanbul nba", PreviewText.normalize(probe, tr))
        assertEquals("isparta i̇stanbul nba", PreviewText.normalize(probe, StreamingPackCatalog.EN))
        assertEquals(probe, PreviewText.normalize(probe, StreamingPackCatalog.EN.copy(caseFold = CaseFold.Keep)))
    }

    @Test fun theRecordedFlagsAreWhatThePacksOwnTokensFileSays() {
        val tokens = payloadFile(pack.tokens.name, pack.tokens.bytes)
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

    /** The encoder under the pack module's payload directory, or null when it has not been placed. */
    private fun payloadEncoder(): File? = payloadFile(pack.encoder.name, pack.encoder.bytes)

    /** One payload file at its pinned byte count, or null — the payload is a BUILD artifact. */
    private fun payloadFile(name: String, bytes: Long): File? {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        val relative = "${StreamingPackCatalog.PACK_EN}/src/main/assets/${StreamingPackCatalog.PACK_EN}/$name"
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile && candidate.length() == bytes) return candidate
            dir = dir.parentFile
        }
        return null
    }
}
