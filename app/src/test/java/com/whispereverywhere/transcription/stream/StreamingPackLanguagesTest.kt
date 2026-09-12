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

    @Test fun theFrenchCanaryIsSYNTHESIZEDFromTheVoiceModelThisAppAlreadyShips() {
        // This case read `assertNull(FR.canary)` until 4.5.0 T3 — the row recorded "no clip yet"
        // rather than inventing one, and the vocabulary precondition it recorded (`▁UN 50`,
        // `▁DEUX 156`, `▁TROIS 304`, `▁QUATRE 353`, `▁CINQ 386` all whole pieces) is what made
        // the clip a synthesis job instead of a hunt. It is now synthesized: kokoro-multi-lang-v1_0
        // speaker 30 (`ff_siwis`), "un deux trois quatre cinq", 1.482 s, and the real pack decodes
        // it exactly. `PreviewCanaryClipsTest` holds the clip's bytes, format and measurement.
        val canary = StreamingPackCatalog.FR.canary!!
        assertEquals("canary_fr_digits.wav", canary.asset)
        assertEquals(
            listOf(setOf("un"), setOf("deux"), setOf("trois"), setOf("quatre"), setOf("cinq")),
            canary.rule.expected,
        )
        assertEquals("one drop of slack, the English row's own tolerance", 4, canary.rule.minMatches)
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

    // ---------------------------------------------------------------------------- Russian

    @Test fun theRussianRowIsTheFourFilesAtCommit31fa603eAndIsTheCheapestInTheCatalogue() {
        val p = StreamingPackCatalog.RU
        assertEquals("ru", p.language)
        assertEquals("ru-vosk-2025-08-16", p.dirName)
        assertEquals("preview_ru", p.packName)
        assertEquals(
            "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-small-ru-vosk-int8-2025-08-16/resolve/31fa603e4f31279c6e1f7600fed13dc4312663ab/",
            p.baseUrl,
        )
        assertEquals(PackFile("encoder.int8.onnx", 26_214_060L, "e0db705e94ec35d803b1df4f40cda23d064e1142977c80ab288430b109777a9d"), p.encoder)
        // The one row whose decoder is not quantized — upstream ships no int8 decoder.
        assertEquals(PackFile("decoder.onnx", 2_093_080L, "89b3088a9e20e1ef7f2e85ce1a3478afe6a9c4ac57369cabcc4beb8e95328ea0"), p.decoder)
        assertEquals(PackFile("joiner.int8.onnx", 259_417L, "b55784b071ab7512eab4c7c44e4f5478284ef33c83562cc6a249b972515a31e5"), p.joiner)
        assertEquals(PackFile("tokens.txt", 6_388L, "93bbbc0bae6b78c0bbb743d4aa9fded3bb5ff3aac5f0200e3a769a5a05e0fdf6"), p.tokens)
        assertEquals(28_572_945L, p.totalBytes)
        assertEquals("29 MB", StreamingPackCatalog.sizeBadge(p.totalBytes))
        assertTrue(
            "and it is the smallest pack in the catalogue — the cheapest possible proof of the " +
                "multi-pack machinery",
            StreamingPackCatalog.packs.minByOrNull { it.totalBytes } === p,
        )
    }

    @Test fun theRussianRowIsTheFirstSixHundredFortyMillisecondRowAndItsPadIsEightHundredTwenty() {
        val p = StreamingPackCatalog.RU
        assertEquals("zipformer2", p.modelType)
        assertEquals(64, p.decodeChunkLen)
        assertEquals(77, p.encoderT)
        assertEquals("640 ms per forward pass after the first", 640L, p.cadenceMs)
        // The defect this row exists to force: 77 frames need 770 ms of feature, so the flat 500
        // the shipping row measured is 270 ms SHORT — `isReady` stays false, the tail chunk never
        // decodes, and the last word of every utterance silently never emits.
        assertEquals(820L, p.padMs)
        assertTrue("a flat 500 ms pad would not cover one forward pass", 500L < p.encoderT * 10L)
        assertEquals(StreamingPreviewTuning.padMsFor(77), p.padMs)
    }

    @Test fun theRussianVocabularysONLYMarkIsTheJoinerHyphen() {
        val p = StreamingPackCatalog.RU
        // ZERO uppercase / 495 lowercase-bearing emittable, so the fold is a no-op on the
        // characters; one punctuation-only piece and it is `-` (какой-то, по-русски), which is a
        // word-internal joiner exactly as the apostrophe is — see PackTokenFacts.JOINERS.
        assertEquals(CaseFold.Fold, p.caseFold)
        assertFalse(p.emitsPunctuation)
        assertFalse(p.emitsDigits)
    }

    // ---------------------------------------------------------------------------- Indonesian

    @Test fun theIndonesianRowIsTheFourFilesAtCommit4e5a13cb() {
        val p = StreamingPackCatalog.ID
        assertEquals("id", p.language)
        assertEquals("id-iter-100000", p.dirName)
        assertEquals("preview_id", p.packName)
        assertEquals(
            "https://huggingface.co/spacewave/sherpa-onnx-streaming-zipformer2-id/resolve/4e5a13cbe3e9cd4e3775447d86178ef51759096f/",
            p.baseUrl,
        )
        assertEquals(PackFile("encoder-iter-100000-avg-15-chunk-32-left-256.int8.onnx", 70_103_186L, "3a6f85f5d199ad0d495562988af017a75d4ae81b51126db240d959b065d6eaad"), p.encoder)
        assertEquals(PackFile("decoder-iter-100000-avg-15-chunk-32-left-256.int8.onnx", 540_688L, "6544848ca80b557ec3c8569169522dc6243b5bc9b296ea73791728510caacb4a"), p.decoder)
        assertEquals(PackFile("joiner-iter-100000-avg-15-chunk-32-left-256.int8.onnx", 259_417L, "4b89d96292460a92dedcb39e0b17904f10dbad335a8ab11015e567b4864102e0"), p.joiner)
        assertEquals(PackFile("tokens.txt", 5_403L, "f0b6f5bf602d96f60d79bb17192c51eeffb6189250f132b1dfdf72b130d66968"), p.tokens)
        assertEquals(70_908_694L, p.totalBytes)
        assertEquals("71 MB", StreamingPackCatalog.sizeBadge(p.totalBytes))
    }

    @Test fun theIndonesianRowIsTheSecondSixHundredFortyMillisecondRow() {
        val p = StreamingPackCatalog.ID
        assertEquals("zipformer2", p.modelType)
        assertEquals(64, p.decodeChunkLen)
        assertEquals(77, p.encoderT)
        assertEquals(640L, p.cadenceMs)
        assertEquals(820L, p.padMs)
        // The two 640 ms rows agree on the pad because the pad is DERIVED from their shared T —
        // not because someone wrote 820 twice.
        assertEquals(StreamingPackCatalog.RU.padMs, p.padMs)
    }

    @Test fun theIndonesianVocabularyCarriesNoMarkAndNoNumeralAtAll() {
        val p = StreamingPackCatalog.ID
        // 500 lines — three fewer than every other lang_bpe_500 row, because this file has no
        // `#0`/`#1` placeholders — and ZERO digit-bearing pieces in the whole file, which is one
        // cleaner than English. Its only punctuation-shaped piece is `<sos/eos>`, a special.
        assertEquals(CaseFold.Fold, p.caseFold)
        assertFalse(p.emitsPunctuation)
        assertFalse(p.emitsDigits)
    }

    @Test fun neitherSixHundredFortyMillisecondRowHasACanaryYetAndTheORDERIsWhyRuMatters() {
        // The pad had to be right BEFORE either canary could be run once: a Fail on a 500 ms pad
        // would be a verdict on a configuration the feature never runs. The pad landed with the
        // route; the clips land with T3, from FLEURS (Kokoro has no ru or id voice).
        assertNull(StreamingPackCatalog.RU.canary)
        assertNull(StreamingPackCatalog.ID.canary)
    }

    // ---------------------------------------------------------------------------- Korean

    @Test fun theKoreanRowIsTheChunk16ExportAtCommitDb24b58d() {
        val p = StreamingPackCatalog.KO
        assertEquals("ko", p.language)
        assertEquals("ko-72m-chunk-16", p.dirName)
        assertEquals("preview_ko", p.packName)
        assertEquals(
            "https://huggingface.co/kangkyu/icefall-asr-ko-streaming-zipformer-72m/resolve/db24b58d22736349eaeb34cc181ad0f3debf9903/",
            p.baseUrl,
        )
        // The chunk-16 trio, NOT chunk-32 or chunk-64: the repo ships all three, their decoders
        // and joiners are byte-identical, and the 8.25 CER / RTF 0.038 pair everyone quotes is
        // the chunk-64 export = 1.28 s cadence, a different model at 4× the word lag.
        assertEquals(PackFile("encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx", 70_133_869L, "5f2b6e5e92834849cfdbda3aaa355e6f39ff993f067794e4ab9d5cb993b15311"), p.encoder)
        assertEquals(PackFile("decoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx", 1_544_210L, "40c3c57ad27b45b59e27bec8bfc02f04d27c060aeb8e964ae2f87bd9f356bf7d"), p.decoder)
        assertEquals(PackFile("joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx", 1_270_777L, "7d3bd9c1e9cf60efa5d5fed728fbd52d0f08139775f9e9002fd088b4d78f3e73"), p.joiner)
        assertEquals(PackFile("tokens.txt", 20_844L, "435dfb9e0a2b6a79124f1a4d8f0f33a951b25384726e2e0d854f081533e6ec9d"), p.tokens)
        assertEquals(72_969_700L, p.totalBytes)
        assertEquals("73 MB", StreamingPackCatalog.sizeBadge(p.totalBytes))
        // Its file names are the English pack's, to the character — which is safe only because
        // every pack owns a directory: the install dir (`dirName`), the Play delivery dir
        // (`packName`) and the AAB asset dir are all per-row.
        assertEquals(
            StreamingPackCatalog.EN.files.map { it.name },
            p.files.map { it.name },
        )
        assertEquals(320L, p.cadenceMs)
        assertEquals(500L, p.padMs)
        assertEquals("zipformer2", p.modelType)
        assertEquals(32, p.decodeChunkLen)
        assertEquals(45, p.encoderT)
    }

    @Test fun koreanIsTheONLYRowThatKeepsItsCaseAndSetsBOTHCopyFlags() {
        val p = StreamingPackCatalog.KO
        // The census: 2,460 lines, 2,457 emittable of which 2,456 are single characters and 2,301
        // bear Hangul; 10 standalone ASCII digits; 58 punctuation-only pieces (31 of them pure
        // Unicode-P); 27 uppercase-bearing / 34 lowercase-bearing emittable pieces = genuinely
        // MIXED, so the fold is not provably lossless.
        assertEquals(CaseFold.Keep, p.caseFold)
        assertTrue("the Korean strip carries . ? , ! that the English strip never does", p.emitsPunctuation)
        assertTrue("and standalone numerals, so \"no numerals\" is a FALSE sentence here", p.emitsDigits)
        // And it is the only such row, which is what makes those two `true` branches real rather
        // than a free abstraction — the copy has to have a sentence for exactly this shape.
        assertEquals(listOf("ko"), StreamingPackCatalog.packs.filter { it.emitsPunctuation }.map { it.language })
        assertEquals(listOf("ko"), StreamingPackCatalog.packs.filter { it.caseFold == CaseFold.Keep }.map { it.language })
    }

    // ---------------------------------------------------------------------------- Chinese

    @Test fun theChineseRowIsTheFiftyMegabyteBilingualExportAtCommitE2382758() {
        val p = StreamingPackCatalog.ZH
        assertEquals("the row serves the picker's Chinese, and carries the bilingual model", "zh", p.language)
        assertEquals("zh-en-t-chunk-32", p.dirName)
        assertEquals("preview_zh", p.packName)
        assertEquals(
            "https://huggingface.co/csukuangfj/k2fsa-zipformer-bilingual-zh-en-t/resolve/e2382758de9a0219b4efe682b95af30b399db3b8/",
            p.baseUrl,
        )
        // The export directory is load-bearing: `exp/64/` and `exp/96/` hold encoders 11 and 12
        // bytes larger, which is the kind of neighbour a flat name cannot tell apart.
        assertEquals("exp/32/encoder-epoch-99-avg-1.int8.onnx", p.encoder.path)
        assertEquals("exp/32/decoder-epoch-99-avg-1.int8.onnx", p.decoder.path)
        assertEquals("exp/32/joiner-epoch-99-avg-1.int8.onnx", p.joiner.path)
        assertEquals("data/lang_char_bpe/tokens.txt", p.tokens.path)
        assertEquals(42_980_793L, p.encoder.bytes)
        assertEquals("db6f51551762e40e549166fe041ea3e45464370b595e9ad23f06478ec3794fbb", p.encoder.sha256)
        assertEquals(3_486_740L, p.decoder.bytes)
        assertEquals("4b618d383af304cfae281dbf0a53e8bf442c2f0502256cd5694bd6567ebdd834", p.decoder.sha256)
        assertEquals(3_228_485L, p.joiner.bytes)
        assertEquals("bdda356d6f9b8c2d7cee9ee0e26075fa537490f7fd06520be408d287073667b9", p.joiner.sha256)
        // Byte-identical to the 198 MB sibling's tokens.txt — one audit covers both rows, and the
        // copy is identical, at a quarter of the bytes.
        assertEquals(56_317L, p.tokens.bytes)
        assertEquals("a8e0e4ec53810e433789b54a5c0134a7eaa2ffca595a6334d54c00da858841d3", p.tokens.sha256)
        // NOT the 198 MB the first survey quoted for this language.
        assertEquals(49_752_335L, p.totalBytes)
        assertEquals("50 MB", StreamingPackCatalog.sizeBadge(p.totalBytes))
    }

    @Test fun theChineseRowIsTheSecondV1ExportAndItsHeadDimsAreABSENT() {
        val p = StreamingPackCatalog.ZH
        // The table said "verify"; the Range read settles it. model_type=zipformer, version=1,
        // T = chunk + 7, and no comment / query_head_dims / value_head_dims / num_heads — the
        // French shape exactly, which is why the empty `modelType` makes this row loadable.
        assertEquals("zipformer", p.modelType)
        assertEquals(32, p.decodeChunkLen)
        assertEquals(39, p.encoderT)
        assertEquals("the app's own cadence, so the measured lag is not disturbed", 320L, p.cadenceMs)
        assertEquals(500L, p.padMs)
        // The two v1 rows agree on the frame count because both exporters write `chunk + 7`.
        assertEquals(StreamingPackCatalog.FR.encoderT, p.encoderT)
        assertEquals(
            "and they are the only two — every zipformer2 row here writes chunk + 13",
            listOf("fr", "zh"),
            StreamingPackCatalog.packs.filter { it.modelType == "zipformer" }.map { it.language },
        )
    }

    @Test fun theChineseRowEmitsONENumeralAndFoldsItsCaseUnlikeTheMonolingualZhRow() {
        val p = StreamingPackCatalog.ZH
        // 494 all-caps Latin BPE pieces at ids 3-496, ZERO lowercase, ZERO punctuation-only
        // pieces, NO byte fallback. That last conjunct is the whole difference from the
        // monolingual `zh` row the table also priced: that one is 0 upper / 0 lower WITH byte
        // fallback and must Keep, because its Latin acronyms arrive through `<0xNN>` where no
        // census can see them. This row's Latin is IN the vocabulary and single-case, so the fold
        // is provably lossless and the strip renders `always` exactly as the English pack does.
        assertEquals(CaseFold.Fold, p.caseFold)
        assertFalse(p.emitsPunctuation)
        // `2` at id 4883 — the shipping pack has zero emittable numerals and this breaks that
        // property by exactly one piece.
        assertTrue(p.emitsDigits)
        assertEquals(
            "two rows can put a numeral on the strip, for very different reasons",
            listOf("ko", "zh"),
            StreamingPackCatalog.packs.filter { it.emitsDigits }.map { it.language },
        )
    }

    @Test fun theBilingualRowsCanaryIsTheBUNDLEDEnglishClipAtZeroAssetCost() {
        // This case read `assertNull(ZH.canary)` and recorded a PREDICTION: that the clip was free
        // because the vocabulary carries `▁ONE 4`, `▁TWO 376`, `▁THREE 377`, `▁FOUR 478` whole and
        // splits FIVE as `▁FI`+`VE` "exactly as the English pack does". 4.5.0 T3 ran the decode.
        // The conclusion held — `ONE TWO THREE FOUR FIVE`, exact, 5 of 5 positions in nine cells —
        // and the split prediction did not: this pack emits `▁F 273`+`IVE 361`. Both spell FIVE,
        // which is why the shared clip works; that the file CAN spell a rendering and that a
        // decode DOES are two different facts, and only the second one is a canary.
        val canary = StreamingPackCatalog.ZH.canary!!
        assertEquals(
            "the same asset the English row names — this row adds zero bytes",
            StreamingPackCatalog.EN.canary!!.asset, canary.asset,
        )
        assertEquals(
            "and the English rule's values, RESTATED on this row: the alias set for position two " +
                "carries \"2\", which is this pack's single emittable numeral (id 4883) and would " +
                "otherwise be scored a miss",
            StreamingPackCatalog.EN.canary!!.rule, canary.rule,
        )
    }

    @Test fun theKoreanRowHasNoCanaryYetAndTheReasonIsItsCLIPNotItsRule() {
        // What this row needed was thought to be a different RULE: both its number series are
        // single characters, `하나` and `다섯` are not whole pieces, and `result.text` collapses the
        // whole utterance to one token. The last of those is real and measured — but it is a
        // property of the TEXT, and the canary now scores the STRIP, where the tokens' word spaces
        // survive `RemoveSpaceBetweenCjk`. So what is outstanding is only the clip, and it must
        // NOT come from the k2-fsa mirror's `test_wavs`, which is AI-Hub audio.
        assertNull(StreamingPackCatalog.KO.canary)
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
