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
        val ids = WhisperCatalog.entries.map { it.id }
        assertEquals(6, WhisperCatalog.entries.size)
        assertEquals(listOf("eco", "base", "pro", "extreme", "multi", "ultra"), ids)
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
        assertEquals(WhisperCatalog.entries.size, WhisperCatalog.entries.map { it.fileName }.toSet().size)
    }

    @Test fun every_sha256_is_lowercase_hex_of_the_right_length() {
        WhisperCatalog.entries.forEach {
            assertEquals("${it.id} sha256 length", 64, it.sha256.length)
            assertTrue("${it.id} sha256 must be lowercase hex", it.sha256.matches(Regex("[0-9a-f]{64}")))
        }
    }
}
