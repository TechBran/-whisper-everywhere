package com.whispereverywhere.service

import com.whispereverywhere.model.ModelScope
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The previewer's gate (spec §5), as a truth table — and its DIVERGENCE from [sessionLanguageFor],
 * because the gate's language is the user's own selection while whisper's is that selection
 * resolved through the `.en` pin. 4.4.0's assumed R4 said the gate took the RESOLVED value, which
 * made Auto arm on an ENGLISH-scope tier; owner ruling 2026-09-11 retires that (see below), so the
 * rows here compose the two functions to hold them apart rather than together.
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
 *
 * ### And the language it is handed is the SELECTION (owner ruling 1, 2026-09-11)
 *
 * *"Now if they leave it in auto, then you get no live streaming at all. And that will seem to be
 * a very fair trade-off."* The pack arrives for a language the user PICKED, so the gate must ask
 * about the same value the acquisition side asks about — not whisper's `.en` resolution of it,
 * which would arm Auto for eco/pro users while every surface of this release tells them Auto
 * shows none at all. The wrap site's argument is pinned by `LocalPreviewWiringPinTest`; the rows
 * below pin what the two functions answer, and that they answer different things.
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
        // at all. And that will seem to be a very fair trade-off."* A session with no SELECTED
        // language has no pack to choose, whatever is on disk and whatever tier is installed —
        // the `.en` tiers included, which is what the two rows below hold apart.
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

    @Test fun autoOnAnEnglishOnlyTierArmsNothing_becauseTheGateReadsTheSELECTION() {
        // R4's second half, RETIRED by owner ruling 1 (2026-09-11). `pro` (small.en) and `eco`
        // (base.en) do still force "en" FOR WHISPER — that is unchanged, and it is why the typed
        // transcript is English there — but the previewer is handed the user's pick, and Auto
        // picks nothing. This is the population the ruling was written about: the default
        // selection on the default local tier, for whom the acquisition side fetches nothing
        // either, so the copy's "Auto-detect shows none at all" is true for them too.
        assertEquals("en", sessionLanguageFor(ModelScope.ENGLISH, null, TranscribingEngine.LOCAL))
        assertFalse("Auto selects nothing, so there is no language whose pack could arm", arms(null))
        assertFalse(arms(null, packs = everyPack))
    }

    @Test fun autoIsWhisperOnlyOnEveryTier() {
        // R4's first half, now the whole rule. Byte-identical to 4.3.4 for every Auto + multi /
        // npu / npu-turbo session (English partials over Spanish speech would be garbage), and
        // newly true for Auto + eco / pro by the row above.
        assertNull(sessionLanguageFor(ModelScope.MULTILINGUAL, null, TranscribingEngine.LOCAL))
        assertFalse(arms(null))
    }

    @Test fun aNonEnglishPickNeverArms() {
        // Only English has a pack today, and the gate reads the pick — so whisper's own
        // resolution of that pick is not consulted here at all.
        assertFalse(arms("es"))
        assertFalse(arms("fr"))
    }

    @Test fun aSpanishPickOnAnEnglishOnlyTierShowsNoLiveWords_thoughWhisperStillTypesEnglish() {
        // The scope override wins for whisper and ONLY for whisper. The user picked Spanish, no
        // Spanish pack exists and no card ever offered them one, so live words stay off rather
        // than running an English model under a Spanish pick — which is also what
        // `cardLanguageNote` promises: live words follow your TRANSCRIPTION LANGUAGE.
        assertEquals("en", sessionLanguageFor(ModelScope.ENGLISH, "es", TranscribingEngine.LOCAL))
        assertFalse("the English pack on disk is not the Spanish pick's pack", arms("es"))
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
