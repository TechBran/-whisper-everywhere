package com.whispereverywhere.transcription.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The four pinned files of `streaming-zipformer-en-2023-06-26` at HF commit 672fbf1b…, sizes and
 * sha256s as re-hashed on the PC (rung 1 §1.2) and on the Tab (rung 3 §1.2, §7). Literals here,
 * literals in the catalog: a drift in either is a red test, never a quiet re-pin. The encoder's
 * hash was "oid only" in the research doc and is VERIFIED since rung 1.
 *
 * (AMENDMENT 2026-09-10) The same four files now ride a Play Asset Delivery pack — `preview_en`,
 * on-demand, one untargeted variant — so the catalog carries the PACK NAME beside the commit-
 * pinned URL. The URL did not go away: it is the non-Play fallback (a debug/sideload build has no
 * Play to talk to), and both sources land through ONE verification.
 */
class StreamingPackCatalogTest {

    @Test fun theEnglishPackIsTheFourFilesAtTheImmutableCommit() {
        val p = StreamingPackCatalog.EN
        assertEquals("en", p.language)
        assertEquals("en-2023-06-26", p.dirName)
        assertEquals(
            "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26/resolve/672fbf1b30579d6585301139bb363f42a0ad4a24/",
            p.baseUrl,
        )
        assertEquals(PackFile("encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx", 71_083_163L, "563fde436d16cf7607cf408cd6b30909819d03162652ef389c2450ced3f45ac1"), p.encoder)
        assertEquals(PackFile("decoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx", 1_307_236L, "98da299f471e38bb4e1a8df579b8cc9122d6039576a77e357b3c60f17dd83b02"), p.decoder)
        assertEquals(PackFile("joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx", 259_335L, "d944208d660d67c8d72cd2acaeac971fa5ceb8c80e76c1968148846fedd6e297"), p.joiner)
        assertEquals(PackFile("tokens.txt", 5_048L, "49e3c2646595fd907228b3c6787069658f67b17377c60aeb8619c4551b2316fb"), p.tokens)
        assertEquals(listOf(p.encoder, p.decoder, p.joiner, p.tokens), p.files)
        assertEquals(72_654_782L, p.totalBytes)
    }

    @Test fun theCadenceAndTheFrameCountAreTheEncodersOwnMetadataAndThePadFollowsThem() {
        val p = StreamingPackCatalog.EN
        // Read off this encoder's `metadata_props` (PreviewPackMetadataTest re-reads the file
        // itself wherever the payload is placed): decode_chunk_len = 32, T = 45.
        assertEquals("zipformer2", p.modelType)
        assertEquals(32, p.decodeChunkLen)
        assertEquals(45, p.encoderT)
        // 320 ms is the cadence the 0.401 s / p95 0.523 s word lag was measured at — and the only
        // cadence any copy may quote that number for (qualification table §4.2).
        assertEquals(320L, p.cadenceMs)
        // The derived pad reproduces the measured 500 for this pack, exactly.
        assertEquals(500L, p.padMs)
        assertEquals(StreamingPreviewTuning.MEASURED_PAD_MS, p.padMs)
    }

    @Test fun aSixHundredFortyMillisecondRowWouldGetAnEightHundredTwentyMillisecondPad() {
        // The route, proven on a row this build does NOT ship: ru/id/tr/et/pt are all
        // decode_chunk_len 64 / T 77, and the flat 500 ms pad is 320 ms short of one forward pass
        // there — the last word of every utterance would silently never emit (§6(4), E1).
        val sixForty = StreamingPackCatalog.EN.copy(language = "xx", decodeChunkLen = 64, encoderT = 77)
        assertEquals(640L, sixForty.cadenceMs)
        assertEquals(820L, sixForty.padMs)
    }

    @Test fun theUrlIsTheCommitPinnedBasePlusTheFileName() {
        val p = StreamingPackCatalog.EN
        assertEquals(p.baseUrl + "tokens.txt", p.urlOf(p.tokens))
        // resolve/main is a MUTABLE ref (WhisperCatalog's own rule): the base must carry the sha.
        assertTrue(p.baseUrl.contains("/resolve/672fbf1b30579d6585301139bb363f42a0ad4a24/"))
        p.files.forEach { assertTrue(it.sha256.matches(Regex("[0-9a-f]{64}"))) }
    }

    // ------------------------------------------------- the REMOTE path vs the LOCAL name (T1)

    @Test fun aFilesRemotePathDefaultsToItsLocalNameSoTheShippingRowIsUnchanged() {
        // Every file of the English pack is flat at its repo root, so the two spellings coincide
        // and the field is invisible on this row — which is the point of the default: the split
        // exists for the rows whose upstream layout is NOT flat, and it must cost the shipping
        // row nothing at all.
        for (f in StreamingPackCatalog.EN.files) assertEquals(f.name, f.path)
        assertEquals(
            StreamingPackCatalog.EN.baseUrl + "encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx",
            StreamingPackCatalog.EN.urlOf(StreamingPackCatalog.EN.encoder),
        )
    }

    @Test fun theDownloadFollowsThePathAndTheDiskFollowsTheName() {
        // The German trap, in one assertion. `daniel-dona`'s four files live under
        // `exp/epoch-30/` and `lang_bpe_500/` with COMMAS in the ONNX filenames, while
        // `StreamingPackInstall` writes ONE FLAT NAME per file (`File(dir, f.name)`) and
        // `SherpaPreviewRecognizer` opens that same flat name. Before the split, `urlOf` was
        // `baseUrl + name` — so a row could either download (path in `name`, and then the
        // installer writes a subdirectory that does not exist) or load (flat `name`, and then
        // the download 404s), never both.
        val nested = PackFile(
            name = "encoder-epoch-30-avg-5.int8.onnx",
            bytes = 70_133_342L,
            sha256 = "e0163b48f89a81fafc4eb1804a77cdd33646970160f5d954a82774dc86e93fa5",
            path = "exp/epoch-30/encoder-epoch-30-avg-5-chunk-16,32,64,-1-left-64,128,256,-1.int8.onnx",
        )
        val row = StreamingPackCatalog.EN.copy(baseUrl = "https://example.invalid/resolve/abc/", encoder = nested)
        assertEquals(
            "https://example.invalid/resolve/abc/exp/epoch-30/encoder-epoch-30-avg-5-chunk-16,32,64,-1-left-64,128,256,-1.int8.onnx",
            row.urlOf(nested),
        )
        assertEquals("encoder-epoch-30-avg-5.int8.onnx", nested.name)
    }

    @Test fun everyPackFilesLOCALNameIsFlatAndInTheConservativeAlphabet() {
        // The local name is a filesystem entry, an AAB asset entry AND a line in the `.installed`
        // marker, so it must be a single flat segment — and it is held to `[A-Za-z0-9._-]`
        // deliberately, one alphabet narrower than any of those three actually requires. The
        // reason is that the widest of the three is the one this repo CANNOT test: a comma in an
        // asset-pack entry path would first be exercised by `bundleRelease`, a 5.5 GB build that
        // happens once, at upload time, on the controller's machine. Keeping the upstream comma
        // in `path` (where it is only ever a URL) and off `name` (where it would be an AAB entry)
        // removes that risk instead of betting on it.
        for (p in StreamingPackCatalog.packs) for (f in p.files) {
            assertTrue(
                "${p.language}: '${f.name}' must be one flat path segment in [A-Za-z0-9._-] — " +
                    "the upstream path belongs in `path`, which only ever becomes a URL",
                f.name.matches(Regex("[A-Za-z0-9._-]+")),
            )
            assertTrue("${p.language}: '${f.path}' must be a relative path", !f.path.startsWith("/"))
        }
    }

    @Test fun theBadgeFollowsTheHouseDecimalConvention() {
        // ModelTierCopy says "190 MB" for 190,085,487 B; 72,654,782 B is "73 MB", never "70 MB".
        assertEquals("73 MB", StreamingPackCatalog.sizeBadge(72_654_782L))
        assertEquals("190 MB", StreamingPackCatalog.sizeBadge(190_085_487L))
    }

    @Test fun theMarkerTextIsTheFourHashLines() {
        val p = StreamingPackCatalog.EN
        val expected = p.files.joinToString("") { "${it.sha256}  ${it.name}\n" }
        assertEquals(expected, StreamingPackCatalog.markerText(p))
        assertEquals(4, StreamingPackCatalog.markerText(p).lines().count { it.isNotBlank() })
    }

    @Test fun aLanguageResolvesToItsOwnRowAndAutoResolvesToNone() {
        assertSame(StreamingPackCatalog.EN, StreamingPackCatalog.forLanguage("en"))
        assertSame(StreamingPackCatalog.FR, StreamingPackCatalog.forLanguage("fr"))
        assertSame(StreamingPackCatalog.DE, StreamingPackCatalog.forLanguage("de"))
        assertSame(StreamingPackCatalog.RU, StreamingPackCatalog.forLanguage("ru"))
        assertSame(StreamingPackCatalog.ID, StreamingPackCatalog.forLanguage("id"))
        assertNull("a language with no row is not sold another language's model", StreamingPackCatalog.forLanguage("es"))
        assertNull("auto (null) never resolves to a pack — the gate reads the RESOLVED language", StreamingPackCatalog.forLanguage(null))
        assertNull("nor does the raw picker code, if it ever reached here", StreamingPackCatalog.forLanguage("auto"))
        // English stays FIRST: `installedLanguages()` and every ordered surface read this list, and
        // the shipping row is the one whose position has been validated on a device.
        assertSame(StreamingPackCatalog.EN, StreamingPackCatalog.packs.first())
        assertEquals(
            "the catalogue is exactly its declared rows — a row added to the object and not to " +
                "this list is a language that silently never arms",
            StreamingPackCatalog.packs.size, StreamingPackCatalog.packs.map { it.language }.distinct().size,
        )
    }

    @Test fun theLayoutConstantsAreFixed() {
        assertEquals("zf-stream", StreamingPackCatalog.ROOT_DIR)
        assertEquals(".installed", StreamingPackCatalog.MARKER)
        assertEquals(".tmp", StreamingPackCatalog.TMP_SUFFIX)
    }

    // ------------------------------------------------------- the Play pack (AMENDMENT 2026-09-10)

    @Test fun theEnglishPackRidesThePreviewEnPlayPack() {
        // The Play-side identity: fetch(), getPackLocation() and the delivered directory name all
        // key on it. Play strips a `#group_<g>` suffix on delivery and this pack has none (one
        // untargeted variant, every device), so the delivered directory is the pack's own name —
        // which is also the entry-clash rule (4.2 F8): no two modules may ship the same path.
        assertEquals("preview_en", StreamingPackCatalog.EN.packName)
        assertEquals("preview_en", StreamingPackCatalog.PACK_EN)
    }

    @Test fun aRowWithoutAPlayPackIsRepresentable_soAFallbackOnlyLanguageNeedsNoPackModule() {
        // A second language whose licence cell goes green before its pack module exists is a
        // fallback-only row, not a compile error: packName is nullable and the state machine
        // reads it (a null can never resolve to PackDelivered or PackFetchable).
        val fallbackOnly = StreamingPack(
            language = "xx", dirName = "xx-test", packName = null,
            baseUrl = "https://example.invalid/resolve/abc/",
            encoder = PackFile("e", 1L, "0".repeat(64)),
            decoder = PackFile("d", 1L, "1".repeat(64)),
            joiner = PackFile("j", 1L, "2".repeat(64)),
            tokens = PackFile("t", 1L, "3".repeat(64)),
            modelType = "zipformer2", decodeChunkLen = 64, encoderT = 77,
            caseFold = CaseFold.Fold,
            emitsPunctuation = false, emitsDigits = false,
            canary = StreamingPackCatalog.EN.canary,
        )
        assertNull(fallbackOnly.packName)
    }
}
