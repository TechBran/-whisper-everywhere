package com.whispereverywhere.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WhisperCatalogHelpersTest {

    @Test
    fun catalog_hasFiveEntries_withExpectedIds() {
        val ids = WhisperCatalog.entries.map { it.id }
        assertEquals(5, WhisperCatalog.entries.size)
        assertEquals(listOf("eco", "pro", "extreme", "multi", "ultra"), ids)
    }

    @Test
    fun catalog_scopesAndMinRam_areCorrect() {
        fun m(id: String) = WhisperCatalog.byId(id)!!

        assertEquals(ModelScope.ENGLISH, m("eco").scope)
        assertEquals(0L, m("eco").minRamBytes)

        assertEquals(ModelScope.ENGLISH, m("pro").scope)
        assertEquals(0L, m("pro").minRamBytes)

        assertEquals(ModelScope.ENGLISH, m("extreme").scope)
        assertEquals(6_000_000_000L, m("extreme").minRamBytes)

        assertEquals(ModelScope.MULTILINGUAL, m("multi").scope)
        assertEquals(0L, m("multi").minRamBytes)

        assertEquals(ModelScope.MULTILINGUAL, m("ultra").scope)
        assertEquals(8_000_000_000L, m("ultra").minRamBytes)
    }

    @Test
    fun catalog_urlsAndApproxBytes_matchContract() {
        val base = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/"
        val eco = WhisperCatalog.byId("eco")!!
        assertEquals("ggml-base.en-q5_1.bin", eco.fileName)
        assertEquals(base + "ggml-base.en-q5_1.bin", eco.url)
        assertEquals(57_000_000L, eco.approxBytes)

        assertEquals(190_000_000L, WhisperCatalog.byId("pro")!!.approxBytes)
        assertEquals(539_000_000L, WhisperCatalog.byId("extreme")!!.approxBytes)
        assertEquals(190_000_000L, WhisperCatalog.byId("multi")!!.approxBytes)
        assertEquals(574_000_000L, WhisperCatalog.byId("ultra")!!.approxBytes)
    }

    @Test
    fun modelById_returnsNull_forUnknownId() {
        assertNull(WhisperCatalog.byId("nope"))
    }

    @Test
    fun isRecommended_boundary_atMinRam() {
        val ultra = WhisperCatalog.byId("ultra")!! // minRam 8_000_000_000

        // just below -> not recommended
        assertFalse(WhisperCatalog.isRecommendedForDevice(ultra, 7_999_999_999L))
        // exactly at threshold -> recommended (>=)
        assertTrue(WhisperCatalog.isRecommendedForDevice(ultra, 8_000_000_000L))
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
}
