package com.whispereverywhere.service

import com.whispereverywhere.model.ModelScope
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The previewer's gate (spec §5), as a truth table — and its composition with
 * [sessionLanguageFor], because RULING ASSUMED (R4) is a statement about the RESOLVED language:
 * Auto = whisper only on multilingual tiers; Auto on `pro` = English, which `sessionLanguageFor`
 * already answers (FloatingBubbleService.kt:203-209). A flip to "Auto + pack ⇒ English partials
 * regardless" is one accepted value (`null`) in the gate and one row here.
 */
class LocalPreviewGateTest {

    private fun arms(
        lang: String?,
        pack: Boolean = true,
        cloud: Boolean = false,
        batch: Boolean = false,
        enabled: Boolean = true,
        ready: Boolean = true,
    ) = localPreviewArms(
        sessionLanguage = lang, packInstalled = pack, isCloudSession = cloud,
        batchJobActive = batch, userEnabled = enabled, previewReady = ready,
    )

    @Test fun fixedEnglishWithThePackArms() {
        assertTrue(arms("en"))
    }

    @Test fun autoOnAnEnglishOnlyTierResolvesToEnglishAndArms() {
        // R4's second half: `pro` (small.en) already forces "en" for the local engine.
        val lang = sessionLanguageFor(ModelScope.ENGLISH, null, TranscribingEngine.LOCAL)
        assertEquals("en", lang)
        assertTrue(arms(lang))
    }

    @Test fun autoOnAMultilingualTierIsWhisperOnly() {
        // R4's first half: English partials over Spanish speech would be garbage. Byte-identical
        // to 4.3.4 for every Auto + multi / npu / npu-turbo session.
        val lang = sessionLanguageFor(ModelScope.MULTILINGUAL, null, TranscribingEngine.LOCAL)
        assertNull(lang)
        assertFalse(arms(lang))
    }

    @Test fun aNonEnglishPickNeverArms() {
        assertFalse(arms("es"))
        assertFalse(arms(sessionLanguageFor(ModelScope.MULTILINGUAL, "es", TranscribingEngine.LOCAL)))
        assertFalse(arms(sessionLanguageFor(null, "fr", TranscribingEngine.LOCAL)))
    }

    @Test fun aSpanishPickOnAnEnglishOnlyTierArms_becauseWhisperTypesEnglishThere() {
        // The scope override wins for the local engine; the preview matches the typed language.
        assertTrue(arms(sessionLanguageFor(ModelScope.ENGLISH, "es", TranscribingEngine.LOCAL)))
    }

    @Test fun everyOtherInputIsAVeto() {
        assertFalse("no pack", arms("en", pack = false))
        assertFalse("a cloud session (batch or live) keeps today's strip", arms("en", cloud = true))
        assertFalse("a batch file job is running", arms("en", batch = true))
        assertFalse("the switch is off (R3 makes it default-on; off is still off)", arms("en", enabled = false))
        assertFalse("the canary failed, or the recognizer is not warm yet", arms("en", ready = false))
    }

    // ------------------------------------------------------------------ R3, the switch's default

    /**
     * The preference the gate's `userEnabled` reads, pinned as SOURCE because
     * `PreferencesManager` takes a `Context`: there is no JVM path to its accessor, and R3 is one
     * boolean literal. `PreferencesManager.kt` is already in `sourcePinnedInputs`
     * (`app/build.gradle.kts`), so an edit confined to it re-runs this.
     */
    @Test fun theSwitchTheGateReadsIsDefaultOn() {
        val src = File("src/main/java/com/whispereverywhere/data/local/PreferencesManager.kt")
            .readText().replace("\r\n", "\n")
        assertTrue(
            "R3: the previewer is default-ON for a user who has the pack — the PACK is the " +
                "opt-in, and a second switch to find would make the feature invisible",
            src.contains("prefs.getBoolean(KEY_LOCAL_PREVIEW_ENABLED, true)"),
        )
        assertTrue(
            "under its own key, so nothing else can flip it",
            src.contains("private const val KEY_LOCAL_PREVIEW_ENABLED = \"local_preview_enabled\""),
        )
        assertEquals(
            "and read in exactly one place: a second getBoolean of this key is a second default",
            1,
            Regex("getBoolean\\(KEY_LOCAL_PREVIEW_ENABLED").findAll(src).count(),
        )
    }
}
