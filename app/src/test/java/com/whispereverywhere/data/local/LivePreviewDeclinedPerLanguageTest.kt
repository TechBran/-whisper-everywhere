package com.whispereverywhere.data.local

import com.whispereverywhere.data.local.PreferencesManager.Companion.livePreviewDeclinedKey
import com.whispereverywhere.data.local.PreferencesManager.Companion.readLivePreviewDeclined
import com.whispereverywhere.transcription.stream.StreamingPackCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE NEVER-AGAIN FLAG IS PER LANGUAGE (owner rulings 2026-09-11, consequence 5) — pinned through
 * the real production read seam, [readLivePreviewDeclined], which the live accessor calls. The
 * `CloudDisclosureConsentTest` shape, for the same reason: `PreferencesManager` needs a `Context`,
 * so a pure `(String, Boolean) -> Boolean` store is the only way to execute the rule that matters.
 *
 * Why it is keyed NOW rather than in the multilingual build: a global flag persisted by 4.4.1 and
 * re-keyed later needs a migration for every user who set it — and getting that migration wrong
 * means a 73 MB fetch of a model somebody deleted on purpose. Nothing has shipped with the global
 * key, so this is a re-key, not a migration.
 *
 * The property this file exists for is the one the ruling names: *"Deleting the Spanish pack must
 * not let the foreground top-up re-download it on the next launch"* — and, symmetrically, must not
 * silence English.
 */
class LivePreviewDeclinedPerLanguageTest {

    /** Simulate SharedPreferences.getBoolean over an in-memory store. */
    private fun store(vararg entries: Pair<String, Boolean>): (String, Boolean) -> Boolean {
        val map = entries.toMap()
        return { key, default -> map[key] ?: default }
    }

    @Test fun aFreshStoreHasDeclinedNothingInAnyLanguage() {
        // Shipped true this declines the feature for every user in the world on first launch and
        // the auto-fetch silently never happens — a no-op no behavioural test could see, because
        // every behavioural test passes its own value for that input.
        val empty = store()
        for (code in listOf("en", "es", "zh", "auto")) {
            assertFalse(code, readLivePreviewDeclined(code, empty))
        }
    }

    @Test fun aDeclineIsHonouredForTheLanguageItWasMadeIn() {
        val declinedEnglish = store(livePreviewDeclinedKey("en") to true)
        assertTrue(readLivePreviewDeclined("en", declinedEnglish))
    }

    @Test fun aDeclineInOneLanguageLeavesEveryOtherLanguageUntouched() {
        // AF8's first half. A user who deletes the Spanish pack has said nothing about English,
        // and under the old global flag they had said it about everything at once.
        val declinedSpanish = store(livePreviewDeclinedKey("es") to true)
        assertTrue(readLivePreviewDeclined("es", declinedSpanish))
        assertFalse("English is not declined by a Spanish delete", readLivePreviewDeclined("en", declinedSpanish))
        assertFalse(readLivePreviewDeclined("zh", declinedSpanish))
    }

    @Test fun everyLanguageGetsItsOwnKeyAndNoneOfThemIsTheOldGlobalOne() {
        val codes = listOf("en", "es", "fr", "zh", "auto")
        assertEquals(
            "one key per language — a shared suffix is how two languages start disagreeing " +
                "about one stored decision",
            codes.size, codes.map { livePreviewDeclinedKey(it) }.distinct().size,
        )
        for (code in codes) {
            assertEquals("live_preview_declined_$code", livePreviewDeclinedKey(code))
            assertNotEquals(
                "and none of them collides with 4.4.1's pre-amendment global key, so a store " +
                    "written by an intermediate build cannot decline a language by accident",
                "live_preview_declined", livePreviewDeclinedKey(code),
            )
        }
    }

    @Test fun theKeyIsTheCatalogsOwnLanguageCodeSoTheFlagAndThePackCannotDrift() {
        // The flag is written by the card's X and the Settings delete, both of which hold a
        // StreamingPack; the decision reads it for the SELECTED language. Those two agree only
        // because the key is the pack's own `language` field and not a second spelling of it.
        for (pack in StreamingPackCatalog.packs) {
            assertEquals(
                "live_preview_declined_${pack.language}",
                livePreviewDeclinedKey(pack.language),
            )
        }
        assertTrue(
            "and the catalogue is the authority on which codes exist at all",
            StreamingPackCatalog.forLanguage("en") != null,
        )
    }
}
