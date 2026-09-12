package com.whispereverywhere.transcription.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 4.5.0 language rows, pinned file by file (4.5.0 T1). `StreamingPackCatalogTest` owns the
 * English row; this owns the six the qualification table recommended, and it owns them as
 * LITERALS — the same discipline the English row has carried since 4.4.0, because the payload is a
 * build artifact and a clean clone has nothing on disk to read.
 *
 * ### What "verified" means for each number here, and how
 *
 * Nothing below was copied from the qualification table. Every value was read from source on
 * 2026-09-12 and the method was PROVED against the shipping English row, whose answers this
 * repository already pins:
 *
 *  - **bytes + sha256** — the HF tree API with `expand=true`, taking each file's `size` and its
 *    **LFS oid** (the oid IS the sha256). `tokens.txt` is not an LFS blob in five of the six
 *    repos, so every one was downloaded and hashed locally; the English file came back
 *    `49e3c264…` at 5,048 B, which is what `StreamingPackCatalogTest` has pinned since 4.4.0.
 *    Korean corroborates twice over: its repo ships its own `SHA256SUMS`, and all four entries
 *    agree with the tree API and with the local hash.
 *  - **`modelType` / `decodeChunkLen` / `encoderT`** — an HTTP **Range read of the last 512 KiB**
 *    of each int8 encoder, `metadata_props` parsed as protobuf (field 14,
 *    `StringStringEntryProto`) exactly as [OnnxMetadata] parses it, never string-scanned. The
 *    English encoder's tail returned `zipformer2` / 32 / 45 / `comment = "streaming zipformer2"`
 *    with both head-dims keys present — this file's own literals — so the parse is the parse.
 *  - **the copy flags** — [PackTokenFacts] run over each downloaded `tokens.txt`.
 *  - **the DECODER's metadata**, which the table did not check: the v1 reader takes nine keys, not
 *    seven, and two of them (`vocab_size`, `context_size`) are on the decoder behind the same
 *    `_Exit(-1)` macro. All six were Range-read; all six carry both.
 *
 * `PreviewPackMetadataTest` is where those reads become reproducible: wherever a payload has been
 * placed, it re-reads the file and holds it equal to these literals. Until `tools/build_asset_packs.py`
 * has run for a pack, that arm is SKIPPED and these literals are the whole census — which is why
 * they are literals and not a helper.
 */
class StreamingPackLanguagesTest {

    // ---------------------------------------------------------------------------- French

    @Test fun theFrenchRowIsTheFourFilesAtCommit3db9565d() {
        val p = StreamingPackCatalog.FR
        assertEquals("fr", p.language)
        assertEquals("fr-2023-04-14", p.dirName)
        assertEquals("preview_fr", p.packName)
        assertEquals(
            "https://huggingface.co/shaojieli/sherpa-onnx-streaming-zipformer-fr-2023-04-14/resolve/3db9565d9633758d6b87b9a7b3dc09ebfb6b2c73/",
            p.baseUrl,
        )
        assertEquals(PackFile("encoder-epoch-29-avg-9-with-averaged-model.int8.onnx", 126_655_903L, "47a94a7fdc8dff63d708be4ea0535747640224467f91e238311f1ddbdd09327e"), p.encoder)
        assertEquals(PackFile("decoder-epoch-29-avg-9-with-averaged-model.int8.onnx", 1_307_157L, "e72b2b9ed36355bd0dd43433f7dd258e7226ab54c9ef42b28c73ebb785805623"), p.decoder)
        assertEquals(PackFile("joiner-epoch-29-avg-9-with-averaged-model.int8.onnx", 259_572L, "fc2f3bb851a15a532c6f2422d53eecd1ca949f12b0897e07a852021c30481711"), p.joiner)
        assertEquals(PackFile("tokens.txt", 4_819L, "37fb3f2a7bcb85e5fff3f1f66be04e6fbb05077a22f56d177fe85704e945fb31"), p.tokens)
        // The sum the badge and every install sentence derive from — and the table's own figure
        // for this row, reproduced by adding up four independently read file sizes.
        assertEquals(128_227_451L, p.totalBytes)
        assertEquals("128 MB", StreamingPackCatalog.sizeBadge(p.totalBytes))
        // Flat upstream: the two spellings coincide on all four, so `urlOf` is base + name.
        for (f in p.files) assertEquals(f.name, f.path)
    }

    @Test fun theFrenchRowIsAV1ExportAndThatIsWhyTheLoaderPassesNoFamilyAtAll() {
        val p = StreamingPackCatalog.FR
        // Read off the encoder tail: model_type=zipformer, version=1, and NO comment key, NO
        // query_head_dims, NO value_head_dims, NO num_heads. A non-empty config string would force
        // OnlineZipformer2TransducerModel, whose miss on query_head_dims is `_Exit(-1)` —
        // uncatchable, reaching neither warm()'s catch nor onLoadFailure nor markCorrupt.
        assertEquals("zipformer", p.modelType)
        assertEquals(32, p.decodeChunkLen)
        // T = decodeChunkLen + 7 on a v1 export, not + 13 — neither is derivable from the other,
        // which is why it is a READ field and not a computed one.
        assertEquals(39, p.encoderT)
        assertEquals("the measured 320 ms cadence, unchanged", 320L, p.cadenceMs)
        // 39 frames ask for 440 ms arithmetically; the derivation returns the measured floor.
        assertEquals(500L, p.padMs)
        assertEquals(StreamingPreviewTuning.MEASURED_PAD_MS, p.padMs)
    }

    @Test fun theFrenchTokenFactsAreEnglishsOwnCountsWhichIsWhyTheCommentCouldNotBeCopied() {
        val p = StreamingPackCatalog.FR
        // 502 lines / 497 emittable / 495 uppercase-bearing / 0 lowercase / 0 digit-bearing, with
        // one punctuation-only piece and it is the apostrophe: the same census as the shipping
        // English file, to the piece. 4.4.0's pinned comment claimed "497 uppercase, no lowercase,
        // no digits" for English and was wrong three ways; this row is where that wording would
        // have been inherited, so the flags here are derived, not transcribed.
        assertEquals(CaseFold.Fold, p.caseFold)
        assertFalse(p.emitsPunctuation)
        assertFalse(p.emitsDigits)
    }

    @Test fun theFrenchRowHasNoCanaryYetAndThatIsRecordedRatherThanFilledIn() {
        // T3's work. The vocabulary precondition is verified — `▁UN 50`, `▁DEUX 156`,
        // `▁TROIS 304`, `▁QUATRE 353`, `▁CINQ 386` are all whole pieces — so the clip is a
        // synthesis job from Kokoro's `ff_siwis`, not a hunt. Until it exists: NO VERDICT.
        assertNull(StreamingPackCatalog.FR.canary)
    }

    // ---------------------------------------------------------------------------- German

    @Test fun theGermanRowIsTheFourFilesAtCommit322557b0AndKeepsItsCommasInTheURLOnly() {
        val p = StreamingPackCatalog.DE
        assertEquals("de", p.language)
        assertEquals("de-cv17-epoch-30", p.dirName)
        assertEquals("preview_de", p.packName)
        assertEquals(
            "https://huggingface.co/daniel-dona/icefall-asr-commonvoice-zipformer-streaming-de/resolve/322557b0f88fc5a9823bc71027d4160f0c7612cc/",
            p.baseUrl,
        )
        // The four upstream paths, verbatim — three under `exp/epoch-30/` with commas in the
        // filename, one under `lang_bpe_500/`. This is the row [PackFile.path] exists for.
        assertEquals(
            "exp/epoch-30/encoder-epoch-30-avg-5-chunk-16,32,64,-1-left-64,128,256,-1.int8.onnx",
            p.encoder.path,
        )
        assertEquals(
            "exp/epoch-30/decoder-epoch-30-avg-5-chunk-16,32,64,-1-left-64,128,256,-1.int8.onnx",
            p.decoder.path,
        )
        assertEquals(
            "exp/epoch-30/joiner-epoch-30-avg-5-chunk-16,32,64,-1-left-64,128,256,-1.int8.onnx",
            p.joiner.path,
        )
        assertEquals("lang_bpe_500/tokens.txt", p.tokens.path)
        // The download follows the path…
        assertEquals(p.baseUrl + "lang_bpe_500/tokens.txt", p.urlOf(p.tokens))
        // …and the disk, the AAB entry and the `.installed` marker follow the FLAT name.
        assertEquals("encoder-epoch-30-avg-5.int8.onnx", p.encoder.name)
        assertEquals("decoder-epoch-30-avg-5.int8.onnx", p.decoder.name)
        assertEquals("joiner-epoch-30-avg-5.int8.onnx", p.joiner.name)
        assertEquals("tokens.txt", p.tokens.name)
        assertEquals(70_133_342L, p.encoder.bytes)
        assertEquals("e0163b48f89a81fafc4eb1804a77cdd33646970160f5d954a82774dc86e93fa5", p.encoder.sha256)
        assertEquals(540_689L, p.decoder.bytes)
        assertEquals("8e787f64f765d2d4d1e17315879bc3cc1c2f517532799d78be034a03a9bcacda", p.decoder.sha256)
        assertEquals(259_417L, p.joiner.bytes)
        assertEquals("d58379fa169af64034c127558230af63d0c08cda97a4a310b04af4b6ceb68956", p.joiner.sha256)
        assertEquals(5_086L, p.tokens.bytes)
        assertEquals("ad2da0c993128b66cead1d78adddc359bef69c50fca890bb5ca0500c97b6d23d", p.tokens.sha256)
        assertEquals(70_938_534L, p.totalBytes)
        assertEquals("71 MB", StreamingPackCatalog.sizeBadge(p.totalBytes))
    }

    @Test fun theGermanRowIsTheEnglishEncodersTwinSoTheMeasuredLagAndPadTransfer() {
        val p = StreamingPackCatalog.DE
        assertEquals("zipformer2", p.modelType)
        assertEquals(32, p.decodeChunkLen)
        assertEquals(45, p.encoderT)
        assertEquals(320L, p.cadenceMs)
        assertEquals(500L, p.padMs)
        // The identity that makes "the measured 0.401 s word lag transfers" a claim rather than a
        // hope: same family, same cadence, same frame count, same derived pad as the row the
        // measurement was taken on.
        val en = StreamingPackCatalog.EN
        assertEquals(en.modelType, p.modelType)
        assertEquals(en.decodeChunkLen, p.decodeChunkLen)
        assertEquals(en.encoderT, p.encoderT)
        assertEquals(en.padMs, p.padMs)
    }

    @Test fun theGermanVocabularyCarriesNoPunctuationAtAllAndNoDigit() {
        val p = StreamingPackCatalog.DE
        // 502 lines / 497 emittable / 496 uppercase-bearing / 0 lowercase / 0 digit-bearing, and
        // ZERO punctuation-only pieces — one cleaner than English, which has the apostrophe.
        assertEquals(CaseFold.Fold, p.caseFold)
        assertFalse(p.emitsPunctuation)
        assertFalse(p.emitsDigits)
    }

    @Test fun theGermanRowHasNoCanaryYetBecauseKokoroHasNoGermanVoice() {
        // TtsVoices.kt covers es fr hi it ja pt zh. German's clip comes from FLEURS in T3, with
        // its licence recorded beside it; nothing is invented in the meantime.
        assertNull(StreamingPackCatalog.DE.canary)
    }

    // ---------------------------------------------------------------------------- the set

    @Test fun everyRowsPackNameFollowsItsLanguageAndNoTwoRowsShareAnyIdentity() {
        val packs = StreamingPackCatalog.packs
        for (p in packs) {
            assertEquals(
                "${p.language}: the pack name is the Play identity AND the delivered directory " +
                    "AND the module directory — four places to get one language wrong unless it " +
                    "follows the language",
                "preview_${p.language}", p.packName,
            )
        }
        // The pinned rule: no two packs may share an asset path, a language, or an install dir.
        assertEquals(packs.size, packs.map { it.language }.distinct().size)
        assertEquals(packs.size, packs.map { it.packName }.distinct().size)
        assertEquals(packs.size, packs.map { it.dirName }.distinct().size)
    }

    @Test fun everyRowsDerivedPadIsAtLeastOneForwardPassOfItsOwnFeature() {
        // The defect this closes, as an invariant over the whole catalogue rather than per row: a
        // pad shorter than `T × 10` ms leaves `isReady` false, the tail chunk never decodes and
        // the last word of every utterance silently never emits.
        for (p in StreamingPackCatalog.packs) {
            assertTrue(
                "${p.language}: pad ${p.padMs} ms must cover T = ${p.encoderT} frames",
                p.padMs >= p.encoderT * StreamingPreviewTuning.FRAME_SHIFT_MS,
            )
            assertTrue(
                "${p.language}: and never below the only pad a device has confirmed",
                p.padMs >= StreamingPreviewTuning.MEASURED_PAD_MS,
            )
        }
    }
}
