package com.whispereverywhere.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WhisperCatalogHelpersTest {

    @Test
    fun catalog_hasFiveEntries_withExpectedIds() {
        // 4.0: 6 -> 7. The census pin fired when `npu` was added and is resolved here rather than
        // relaxed — the whole point of the pin is that a tier cannot arrive unannounced. `npu` is
        // last because entries order is chronological, and it is GATED, so this list growing does
        // not change what any device is offered (see the pickable/pickableFor pair below).
        val ids = WhisperCatalog.entries.map { it.id }
        assertEquals(7, WhisperCatalog.entries.size)
        assertEquals(listOf("eco", "base", "pro", "extreme", "multi", "ultra", "npu"), ids)
    }

    @Test
    fun catalog_scopesAndMinRam_areCorrect() {
        fun m(id: String) = WhisperCatalog.byId(id)!!

        assertEquals(ModelScope.ENGLISH, m("eco").scope)
        assertEquals(0L, m("eco").minRamBytes)

        assertEquals(ModelScope.ENGLISH, m("pro").scope)
        assertEquals(0L, m("pro").minRamBytes)

        assertEquals(ModelScope.ENGLISH, m("extreme").scope)
        // 5.5e9, not 6e9: ActivityManager.totalMem under-reports physical RAM; the gate
        // targets genuine 6 GB-class hardware (2026-07-17 hardening).
        assertEquals(5_500_000_000L, m("extreme").minRamBytes)

        assertEquals(ModelScope.MULTILINGUAL, m("multi").scope)
        assertEquals(0L, m("multi").minRamBytes)

        assertEquals(ModelScope.MULTILINGUAL, m("ultra").scope)
        // 7.0e9 = genuine 8 GB-class hardware after totalMem slack.
        assertEquals(7_000_000_000L, m("ultra").minRamBytes)
    }

    @Test
    fun catalog_urlsAndApproxBytes_matchContract() {
        // Pinned to an immutable revision: /resolve/main is a mutable ref that could brick
        // every APK-pinned sha256 if upstream replaced a file (2026-07-17 hardening).
        val base = "https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1/"
        val eco = WhisperCatalog.byId("eco")!!
        assertEquals("ggml-base.en-q5_1.bin", eco.fileName)
        assertEquals(base + "ggml-base.en-q5_1.bin", eco.url)
        // Exact HF LFS byte sizes (the old rounded values sat needlessly close to the ±5% gate).
        assertEquals(59_721_011L, eco.approxBytes)

        assertEquals(190_098_681L, WhisperCatalog.byId("pro")!!.approxBytes)
        assertEquals(539_225_533L, WhisperCatalog.byId("extreme")!!.approxBytes)
        assertEquals(190_085_487L, WhisperCatalog.byId("multi")!!.approxBytes)
        assertEquals(574_041_195L, WhisperCatalog.byId("ultra")!!.approxBytes)
    }

    @Test
    fun modelById_returnsNull_forUnknownId() {
        assertNull(WhisperCatalog.byId("nope"))
    }

    @Test
    fun isRecommended_boundary_atMinRam() {
        val ultra = WhisperCatalog.byId("ultra")!! // minRam 7_000_000_000

        // just below -> not recommended
        assertFalse(WhisperCatalog.isRecommendedForDevice(ultra, 6_999_999_999L))
        // exactly at threshold -> recommended (>=)
        assertTrue(WhisperCatalog.isRecommendedForDevice(ultra, 7_000_000_000L))
        // above -> recommended
        assertTrue(WhisperCatalog.isRecommendedForDevice(ultra, 12_000_000_000L))
    }

    @Test
    fun isRecommended_zeroMinRam_alwaysRecommended() {
        val eco = WhisperCatalog.byId("eco")!!
        assertTrue(WhisperCatalog.isRecommendedForDevice(eco, 0L))
        assertTrue(WhisperCatalog.isRecommendedForDevice(eco, 2_000_000_000L))
    }

    @Test
    fun sizeWithinTolerance_fivePercent() {
        val approx = 100_000_000L
        // exactly equal
        assertTrue(WhisperCatalog.sizeWithinTolerance(100_000_000L, approx))
        // +5% edge (105,000,000) inclusive
        assertTrue(WhisperCatalog.sizeWithinTolerance(105_000_000L, approx))
        // -5% edge (95,000,000) inclusive
        assertTrue(WhisperCatalog.sizeWithinTolerance(95_000_000L, approx))
        // just over +5%
        assertFalse(WhisperCatalog.sizeWithinTolerance(105_000_001L, approx))
        // just under -5%
        assertFalse(WhisperCatalog.sizeWithinTolerance(94_999_999L, approx))
    }

    @Test fun retired_tiers_remain_resolvable_by_id() {
        // The app-wide gate is installedModel() != null, which starts with byId(). If byId
        // returns null for a retired tier, every user on it is force-marched into onboarding
        // with no back navigation and their model file is orphaned. Resolvable forever.
        assertNotNull(WhisperCatalog.byId("extreme"))
        assertNotNull(WhisperCatalog.byId("ultra"))
    }

    @Test fun retired_tiers_are_not_pickable() {
        val ids = WhisperCatalog.pickable.map { it.id }
        assertFalse(ids.contains("extreme"))
        assertFalse(ids.contains("ultra"))
        // 3.7 Workstream H (owner decision 2026-08-20): the 60 MB tiers join them. "Pretty much
        // useless at this point… because of the accuracy."
        assertFalse(ids.contains("eco"))
        assertFalse(ids.contains("base"))
    }

    @Test fun pickable_is_exactly_pro_and_multi() {
        // The post-3.7 lineup: pro = the English flagship, multi = the international tier.
        assertEquals(listOf("pro", "multi"), WhisperCatalog.pickable.map { it.id })
    }

    @Test fun the_sixty_megabyte_tiers_stay_resolvable_after_retirement() {
        // Same rule that protects extreme/ultra: byId() must keep answering or every installed
        // eco/base user's installedModel() goes null and the app-wide gate force-marches them
        // into onboarding with their model file orphaned on disk.
        assertNotNull(WhisperCatalog.byId("eco"))
        assertNotNull(WhisperCatalog.byId("base"))
    }

    @Test fun retiring_a_tier_does_not_by_itself_declare_it_unsupported() {
        // THE 3.7 split. `retired` hides a tier from the chooser (fresh installs only);
        // `unsupported` is what drives Settings' migration card. eco/base are retired but
        // perfectly usable, so their installed users must see nothing at all — the spec's
        // "existing users unaffected; no re-download forced". extreme/ultra keep both flags.
        assertFalse(WhisperCatalog.byId("eco")!!.unsupported)
        assertFalse(WhisperCatalog.byId("base")!!.unsupported)
        assertTrue(WhisperCatalog.byId("extreme")!!.unsupported)
        assertTrue(WhisperCatalog.byId("ultra")!!.unsupported)
    }

    @Test fun every_unsupported_tier_is_also_retired() {
        // Offering a tier the app wants to migrate people OFF of would be incoherent.
        WhisperCatalog.entries.filter { it.unsupported }.forEach {
            assertTrue("'${it.id}' is unsupported but still offered", it.retired)
        }
    }

    @Test fun default_is_pro() {
        assertEquals("pro", WhisperCatalog.DEFAULT_MODEL_ID)
        assertNotNull(WhisperCatalog.byId(WhisperCatalog.DEFAULT_MODEL_ID))
    }

    @Test fun default_is_pickable() {
        // A retired default would be unreachable from the picker — an unshippable state.
        assertTrue(WhisperCatalog.pickable.any { it.id == WhisperCatalog.DEFAULT_MODEL_ID })
    }

    @Test fun base_multilingual_tier_has_its_pinned_lfs_values() {
        // Exact LFS byte size and digest, fetched at the catalog's pinned commit. Rounding
        // approxBytes has previously left a correct download barely inside the +/-5% gate.
        val m = WhisperCatalog.byId("base")!!
        assertEquals("ggml-base-q5_1.bin", m.fileName)
        assertEquals(59_707_625L, m.approxBytes)
        assertEquals("422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898", m.sha256)
        assertEquals(ModelScope.MULTILINGUAL, m.scope)
    }

    @Test fun every_entry_has_a_distinct_id_and_filename() {
        assertEquals(WhisperCatalog.entries.size, WhisperCatalog.entries.map { it.id }.toSet().size)
        // Paired artefacts land in the SAME models dir as the primaries, so the distinctness rule
        // covers every file name any tier writes there — two tiers sharing one file on disk would
        // make one of them delete the other's model.
        val files = WhisperCatalog.entries.flatMap { listOfNotNull(it.fileName, it.pairedArtifact?.fileName) }
        assertEquals(files.size, files.toSet().size)
    }

    @Test fun every_sha256_is_lowercase_hex_of_the_right_length() {
        // Reads the PAIRED artefact's digest too (4.0): npu's decoder is 225 MB of the 358 MB the
        // user installs, and a "PENDING"/placeholder digest there would have been unverifiable
        // bytes shipped under a verified tier's name.
        val digests = WhisperCatalog.entries.flatMap { m ->
            listOfNotNull(m.id to m.sha256, m.pairedArtifact?.let { "${m.id}:${it.fileName}" to it.sha256 })
        }
        digests.forEach { (who, sha) ->
            assertEquals("$who sha256 length", 64, sha.length)
            assertTrue("$who sha256 must be lowercase hex", sha.matches(Regex("[0-9a-f]{64}")))
        }
    }

    @Test fun the_npu_tier_states_the_asset_pair_it_actually_needs() {
        // Both digests are MEASURED off the extracted files (the spike staged and hashed them),
        // not placeholders — the tier ships verified or not at all.
        val npu = WhisperCatalog.byId("npu")!!
        assertEquals(ModelScope.MULTILINGUAL, npu.scope)      // same whisper-small weights as multi
        assertEquals(0L, npu.minRamBytes)                     // the SoC gate is the real gate
        assertTrue(npu.gated)
        assertFalse(npu.retired)
        assertFalse(npu.unsupported)
        assertEquals("encoder_qairt_context.bin", npu.fileName)
        assertEquals("3e92ac26545b6b9d22ecfab594ae57523134006e2722b09fa10e16b193e9e5ec", npu.sha256)
        assertEquals(132_927_488L, npu.primaryBytes)

        val decoder = npu.pairedArtifact!!
        assertEquals("decoder_qairt_context.bin", decoder.fileName)
        assertEquals("fda23d731e6b0ab7fb0a50373a49efe2d1792faa5dad456837624d8b8e44b0e4", decoder.sha256)
        assertEquals(225_316_864L, decoder.approxBytes)

        // The advertised size is the PAIR: what the user downloads, stores, and reads on the badge.
        assertEquals(358_244_352L, npu.approxBytes)
        assertEquals(npu.approxBytes, npu.primaryBytes + decoder.approxBytes)
        // Both files come out of one archive, so one URL is the honest answer for both.
        assertEquals(npu.url, decoder.url)
    }

    @Test fun the_encoder_is_size_gated_against_its_own_bytes_not_the_pairs() {
        // THE reason primaryBytes exists. isInstalled() size-gates models/<fileName> — the ENCODER
        // — and npu.approxBytes is the sum of both files. Gate the encoder against the sum and the
        // tier reads "not installed" forever, whatever the owner imports.
        val npu = WhisperCatalog.byId("npu")!!
        assertFalse(
            "the encoder's own length must NOT satisfy the pair's advertised size",
            WhisperCatalog.sizeWithinTolerance(npu.primaryBytes, npu.approxBytes),
        )
        assertTrue(WhisperCatalog.sizeWithinTolerance(npu.primaryBytes, npu.primaryBytes))
        // Every single-file tier is untouched: primaryBytes defaults to approxBytes.
        WhisperCatalog.entries.filter { it.pairedArtifact == null }.forEach {
            assertEquals("${it.id} primaryBytes drifted from approxBytes", it.approxBytes, it.primaryBytes)
        }
    }

    @Test fun a_gated_tier_is_never_pickable_and_is_never_the_default() {
        // The offered lineup must not change on any device that cannot answer the gate question,
        // and the fallback every no-pick-on-record path lands on must stay device-independent.
        assertEquals(listOf("npu"), WhisperCatalog.entries.filter { it.gated }.map { it.id })
        assertFalse(WhisperCatalog.pickable.any { it.gated })
        assertFalse(WhisperCatalog.byId(WhisperCatalog.DEFAULT_MODEL_ID)!!.gated)
        assertEquals("multi", ModelMigration.targetIdFor(ModelScope.MULTILINGUAL))
    }

    @Test fun pickableFor_offers_npu_only_when_the_gate_passes() {
        // false is the every-other-device answer and must be byte-identical to `pickable`.
        assertEquals(WhisperCatalog.pickable, WhisperCatalog.pickableFor(npuAvailable = false))
        assertEquals(listOf("pro", "multi"), WhisperCatalog.pickableFor(false).map { it.id })
        // true ADDS the tier, changing nothing else — retired tiers stay retired.
        assertEquals(listOf("pro", "multi", "npu"), WhisperCatalog.pickableFor(true).map { it.id })
        assertTrue(WhisperCatalog.pickableFor(true).containsAll(WhisperCatalog.pickable))
    }
}
