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

    @Test fun onlyEnglishHasAPackAndAutoHasNone() {
        assertSame(StreamingPackCatalog.EN, StreamingPackCatalog.forLanguage("en"))
        assertNull(StreamingPackCatalog.forLanguage("es"))
        assertNull("auto (null) never resolves to a pack — the gate reads the RESOLVED language", StreamingPackCatalog.forLanguage(null))
        assertEquals(listOf(StreamingPackCatalog.EN), StreamingPackCatalog.packs)
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
            decodeChunkLen = 64, encoderT = 77,
        )
        assertNull(fallbackOnly.packName)
    }
}
