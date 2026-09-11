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
 *
 * ### The language term is the CATALOGUE's, since 4.4.1's acquisition amendment
 *
 * The gate used to read `sessionLanguage == "en" && packInstalled`. Owner rulings 2026-09-11 make
 * packs per language and the store a SET, so the two terms become one — *is THIS session's
 * language one of the installed pack languages?* — and the English literal is gone. The catalogue
 * lookup moved to the set's one producer (`StreamingPackManager.installedLanguages`, which can
 * only ever answer with catalogue rows), so the gate asks the question once and no row can be
 * invented here. NO OTHER TERM CHANGED: the owner tested this gate and found it correct, so the
 * cloud, batch, switch and readiness vetoes read exactly as they did.
 */
class LocalPreviewGateTest {

    /**
     * Two languages on disk. "es" has no catalogue row yet — one language ships the machinery and
     * the rest are additive — but the GATE must already be language-shaped rather than
     * English-shaped, and a set is how that is stated without waiting for the second row.
     */
    private val everyPack = setOf("en", "es")

    private fun arms(
        lang: String?,
        packs: Set<String> = setOf("en"),
        cloud: Boolean = false,
        batch: Boolean = false,
        enabled: Boolean = true,
        ready: Boolean = true,
    ) = localPreviewArms(
        sessionLanguage = lang, installedPackLanguages = packs, isCloudSession = cloud,
        batchJobActive = batch, userEnabled = enabled, previewReady = ready,
    )

    @Test fun fixedEnglishWithThePackArms() {
        assertTrue(arms("en"))
    }

    // ------------------------------------------------------------------ language x installed set

    @Test fun theGateIsTheLanguagesOwnPackBeingInstalledAndNotAnyPackBeingInstalled() {
        // The cross product the acquisition amendment asks for: {selected language} x {which
        // packs installed}. Written as an independent statement of the rule — THIS language's
        // pack must be on disk — so a gate that answered "some pack is installed", or one that
        // kept an English literal beside the set, fails here rather than passing by construction.
        val languages = listOf(null, "auto", "en", "es", "zh")
        val sets = listOf(emptySet(), setOf("en"), setOf("es"), everyPack)
        for (lang in languages) for (packs in sets) {
            val expected = lang != null && lang in packs
            assertEquals("lang=$lang installed=$packs", expected, arms(lang, packs = packs))
        }
    }

    @Test fun autoArmsNothingWithEveryPackInTheWorldInstalled() {
        // Owner ruling 2026-09-11: *"Now if they leave it in auto, then you get no live streaming
        // at all. And that will seem to be a very fair trade-off."* A session with no resolved
        // language has no pack to choose, whatever is on disk. (An ENGLISH-scope whisper tier is
        // the one case where Auto still reaches the gate AS "en" — sessionLanguageFor resolves it
        // before the gate sees it, which is R4 and is deliberately unchanged; see the row below.)
        assertFalse(arms(null, packs = everyPack))
        assertFalse(
            "and the raw picker code never reaches the gate unresolved — but if it did, no " +
                "pack's language is \"auto\", so the set can never contain it",
            arms("auto", packs = everyPack),
        )
        assertFalse(
            "the catalogue agrees: there is nothing to fetch or arm for Auto",
            com.whispereverywhere.transcription.stream.StreamingPackCatalog.forLanguage("auto") != null,
        )
    }

    @Test fun aLanguageWithNoPackNeverArmsHoweverManyOtherPacksAreInstalled() {
        assertFalse(arms("zh", packs = everyPack))
        assertFalse(arms("fr", packs = everyPack))
    }

    @Test fun eachInstalledLanguageArmsForItselfAndOnlyForItself() {
        // AF8's second half: two packs on disk, each language's own live words working, and
        // neither one borrowing the other's model.
        assertTrue(arms("en", packs = everyPack))
        assertTrue(arms("es", packs = everyPack))
        assertFalse("English alone does not arm a Spanish session", arms("es", packs = setOf("en")))
        assertFalse("nor the other way round", arms("en", packs = setOf("es")))
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
        assertFalse("no pack", arms("en", packs = emptySet()))
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
