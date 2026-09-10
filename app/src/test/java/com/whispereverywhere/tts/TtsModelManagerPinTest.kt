package com.whispereverywhere.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The voice archive's integrity pin, pinned. The 2026-09-08 incident: k2-fsa re-uploaded the
 * archive under the ROLLING `tts-models` release tag, the size stayed inside the +-5 % band, the
 * single pinned sha256 stopped matching, and every fresh voice install on every build failed for
 * two days with nothing in this suite to notice. These pins do not catch a future re-upload either
 * (nothing offline can) — they catch the two ways the FIX could be undone: the gate collapsing back
 * to one hash, and the known-good set losing the archive that is actually served today.
 */
class TtsModelManagerPinTest {

    private val src = File("src/main/java/com/whispereverywhere/tts/TtsModelManager.kt").readText()

    @Test fun the_gate_accepts_a_known_good_set_not_one_hash() {
        assertTrue(src.contains("KNOWN_GOOD_TAR_SHA256.none { it.equals(actual, ignoreCase = true) }"))
        assertTrue("the marker must record the archive actually extracted", src.contains("marker.writeText(actual.lowercase())"))
    }

    @Test fun the_known_good_set_carries_both_archives_and_the_served_one_first() {
        assertEquals(
            listOf(
                "c5f7e2d2caf082bc1d20fb70334a61d99d20b484500aad32e7cf84c128ea3298",
                "c133d26353d776da730870dac7da07dbfc9a5e3bc80cc5e8e83ab6e823be7046",
            ),
            TtsModelManager.KNOWN_GOOD_TAR_SHA256,
        )
        // Both are 64 lowercase hex chars — a typo here is a permanently failing install.
        TtsModelManager.KNOWN_GOOD_TAR_SHA256.forEach { assertTrue(it, it.matches(Regex("[0-9a-f]{64}"))) }
    }

    @Test fun the_size_band_covers_both_archives() {
        assertEquals(349_906_910L, TtsModelManager.TAR_BYTES)
        assertTrue(TtsModelManager.sizeWithinTolerance(349_906_910L))
        assertTrue("the 2026-07-18 archive must still pass the size gate", TtsModelManager.sizeWithinTolerance(349_418_188L))
    }
}
