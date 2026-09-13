package com.whispereverywhere.service

import com.whispereverywhere.model.ModelScope
import com.whispereverywhere.transcription.stream.PreviewPhase
import com.whispereverywhere.transcription.stream.StreamingPack
import com.whispereverywhere.transcription.stream.StreamingPackCatalog
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
 *
 * ### And the WARM site asks the same question (CHANGE 5)
 *
 * [previewPackToWarm] is the gate's twin — *"warm the pack for the selected language, or warm
 * nothing"* — so the last section holds the two EQUAL over the same cross product rather than
 * trusting two call sites to have been read. That equality is what hands an Auto user back the
 * 802-860 ms load and the +169 MB RSS the old English-literal warm spent on a recognizer the
 * gate refused.
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
            StreamingPackCatalog.forLanguage("auto") != null,
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

    /**
     * THE FOURTH CELL (4.5.0 T4 fix round 1, review r1's B1). The feature said in nine places
     * that with no on-device speech model *"every session is a cloud session, so this gate can
     * never fire"*. It can: `decideEngineChoice` answers `LOCAL_ONLY` for a null `sttProviderId`
     * whatever the key and the network say, that arm never assigns `cloudWrapper`, and this gate
     * has no tier term among its six inputs. So on a modelless phone with no provider configured
     * — the default shape of a tier deleted in Settings or an Auto-Backup restore — the gate
     * ARMS, and the previewer's copy is true for a different reason: the session dies at connect
     * (`LocalPreviewWiringPinTest` pins that, and `PreviewUnreachable`'s KDoc states it).
     *
     * This test is the executable half of that correction. If it ever fails, the enumeration in
     * the task report is right and this comment is wrong — which is the direction the axis was
     * wrong in before.
     */
    @Test fun theGateItselfArmsWithNoTierAndNoProviderConfigured() {
        for (hasKey in listOf(false, true)) {
            for (network in listOf(false, true)) {
                for (live in listOf(false, true)) {
                    assertEquals(
                        "no provider selected is the one-way valve: never a cloud session",
                        EngineChoice.LOCAL_ONLY,
                        decideEngineChoice(
                            sttProviderId = null,
                            hasKey = hasKey,
                            hasValidatedNetwork = network,
                            liveMode = live,
                        ),
                    )
                }
            }
        }
        // ...so `cloudWrapper` is null, `isCloudSession` is false, and with the pack installed,
        // the language picked, the switch on and the recognizer warm every remaining term is met.
        assertTrue(
            "the gate arms on a device that has no speech model at all — the tier is not one of " +
                "its inputs, and this is the cell the enumeration declared impossible",
            arms("en"),
        )
    }

    @Test fun everyOtherInputIsAVeto() {
        assertFalse("no pack", arms("en", packs = emptySet()))
        assertFalse("a cloud session (batch or live) keeps today's strip", arms("en", cloud = true))
        assertFalse("a batch file job is running", arms("en", batch = true))
        assertFalse("the switch is off (R3 makes it default-on; off is still off)", arms("en", enabled = false))
        assertFalse("the canary failed, or the recognizer is not warm yet", arms("en", ready = false))
    }

    // --------------------------------------------------- the WARM site's gate (CHANGE 5, B2)

    private fun warms(
        lang: String?,
        packs: Set<String> = setOf("en"),
        enabled: Boolean = true,
    ) = previewPackToWarm(
        previewLanguage = lang, installedPackLanguages = packs, userEnabled = enabled,
    )

    @Test fun theWarmSiteAsksOfTheLANGUAGEExactlyWhatTheArmSiteAsks() {
        // CONTROLLER RULING CHANGE 5: *"the warm site takes the SAME catalogue lookup as the arm
        // site. Warm the pack for the selected language, or warm nothing."* Held as an EQUALITY
        // over the whole cross product, so a warm site that drifts back to "is the English pack
        // on disk" fails here rather than being caught by reading two call sites.
        val languages = listOf(null, "auto", "en", "es", "zh")
        val sets = listOf(emptySet<String>(), setOf("en"), setOf("es"), everyPack)
        for (lang in languages) for (packs in sets) for (enabled in listOf(true, false)) {
            val case = "lang=$lang installed=$packs enabled=$enabled"
            val warm = warms(lang, packs = packs, enabled = enabled)
            if (warm != null) assertEquals("never another language's model: $case", lang, warm.language)
            if (lang == null || StreamingPackCatalog.forLanguage(lang) != null) {
                // The reachable shape: `installedLanguages()` can only answer with catalogue rows,
                // so for every language that HAS a row the two gates agree exactly.
                assertEquals("warm == arm: $case", arms(lang, packs = packs, enabled = enabled), warm != null)
            } else {
                // "es" and "zh" are fabricated here (the catalogue has one row today). A set that
                // claims them cannot make the warm invent a pack — and that is the safe direction:
                // nothing loads, and the gate refuses anyway because nothing is warm.
                assertNull("no catalogue row means nothing to load: $case", warm)
            }
        }
    }

    @Test fun autoWarmsNothingAtAll_whichIsTheLoadAndThe169MbHandedBack() {
        // CHANGE 5's own argument: both warm sites used to ask only *is the ENGLISH pack on
        // disk*, so a user on Auto — the owner's own habit — paid the 802-860 ms load and
        // +169 MB RSS for a recognizer `localPreviewArms` then refused on its first conjunct.
        // The bytes bought nothing at all.
        assertNull(warms(null, packs = everyPack))
        assertNull("and the raw picker code, if it ever reached here", warms("auto", packs = everyPack))
        assertNull("the switch off is still off", warms("en", enabled = false))
        assertNull("nothing on disk is nothing to load", warms("en", packs = emptySet()))
    }

    @Test fun theWarmedPackIsTheSelectedLanguagesOwnCatalogueRow() {
        // Not "the first row", not "EN": the row for THIS language, so the warm can never paint
        // one language's model behind another language's gate.
        assertEquals(StreamingPackCatalog.EN, warms("en"))
        assertEquals(StreamingPackCatalog.EN, warms("en", packs = everyPack))
        assertEquals("en", warms("en", packs = everyPack)?.language)
    }

    @Test fun aSelectionWithNothingToWARMIsASelectionWithNothingToKEEP() {
        // (4.4.1 pass 3, ITEM 2 — review r2's nit 2.) CHANGE 5 stopped the boot warm for a user
        // on Auto, so nothing is LOADED for them at boot; but nothing released a RESIDENT engine
        // when the selection moved away from an installed pack language, so a user who dictated
        // in English and then switched to Auto kept the recognizer and its +169 MB until
        // onTrimMemory or onDestroy. CHANGE 5's words were met and its purpose was not.
        //
        // The release condition is this gate's own answer and nothing new: nothing to warm for
        // the new selection is nothing that should stay resident for it. The expectations are
        // written out rather than derived, so a change to the warm gate has to be agreed here.
        val expectRelease = listOf(
            null to true,       // Auto — the case the nit is about, and the owner's own habit
            "auto" to true,     // the raw picker code, if it ever reached here
            "es" to true,       // a language picked with no pack of its own
            "zh" to true,
            "en" to false,      // the resident pack's own language: keep it, and warm() no-ops
        )
        for ((lang, release) in expectRelease) {
            assertEquals(
                "lang=$lang with the English pack installed",
                release,
                warms(lang) == null,
            )
        }
        assertTrue("the switch going off is the same answer", warms("en", enabled = false) == null)
        assertTrue("nothing on disk is nothing to keep", warms("en", packs = emptySet()) == null)
        // The COMPLEMENT is `warmStreamingPreview`'s own release (`streamingPreviewPack != pack`):
        // that one frees the old recognizer when the new selection has a DIFFERENT pack, this one
        // frees it when the new selection has NONE. Between them a language change can never
        // leave the wrong model — or an unused one — in memory.
        assertEquals(StreamingPackCatalog.EN, warms("en", packs = everyPack))
    }

    // ------------------------------------------ the INSTALL's own warm (4.5.1 Task 1, the first
    // ------------------------------------------ session fix)

    /**
     * [warmOnPackInstalled] — the third gate, and the only one whose trigger is an EVENT rather
     * than a session or a boot.
     *
     * The defect it retires: an install that completes mid-process warmed nothing, because
     * `warmStreamingPreview` was called only from the boot prewarm and from the wrap site — and the
     * wrap site's own KDoc says it *"arms NEXT session, not this one"*. So the user who picked a
     * language, watched 73-128 MB arrive and tapped got no words, and the session AFTER that one
     * worked. 4.5.0's acceptance sheet recorded that as expected (AF6); the owner is right that a
     * user meets it as *"this doesn't work"*.
     *
     * Every row below is the SAME question the other two gates ask about WHICH pack — the
     * delegation to [previewPackToWarm] is pinned as an equality in
     * [theInstallWarmAndTheSessionWarmAgreeAboutWhichPackIsResident] — plus the three terms only an
     * event has: the phase that just landed, whether a session or a batch job is running right now,
     * and whether the engine is already warm for that very pack.
     */
    private fun warmsOnInstall(
        phase: PreviewPhase = PreviewPhase.INSTALLED,
        recordLanguage: String = "en",
        selection: String? = "en",
        packs: Set<String> = setOf("en"),
        enabled: Boolean = true,
        session: Boolean = false,
        batch: Boolean = false,
        resident: StreamingPack? = null,
    ) = warmOnPackInstalled(
        installedLanguage = recordLanguage,
        phase = phase,
        previewLanguage = selection,
        installedPackLanguages = packs,
        userEnabled = enabled,
        sessionActive = session,
        batchJobActive = batch,
        residentWarmPack = resident,
    )

    @Test fun anInstallThatCOMPLETESWarmsTheSelectedLanguagesOwnPack() {
        // The whole point: this is the answer 4.5.0 had no way to produce, and it is produced at
        // the moment the bytes land rather than at the end of the next session.
        assertEquals(StreamingPackCatalog.EN, warmsOnInstall())
        assertEquals(
            "the row for THIS language, never the first row — the fr install must not load en",
            "en",
            warmsOnInstall(packs = everyPack)?.language,
        )
    }

    @Test fun noPhaseBUTInstalledWarmsAnything() {
        // A 73 MB transfer passes through six phases before it lands and can end in three
        // terminal ones. Only the phase whose own KDoc says *"the marker landed and the recognizer
        // can open the install"* has a model to load; the `when` is written out so a phase added
        // to the machine has to be answered here rather than inheriting a warm by default.
        for (phase in PreviewPhase.entries) {
            val warm = warmsOnInstall(phase = phase)
            if (phase == PreviewPhase.INSTALLED) {
                assertEquals("the landing warms: $phase", StreamingPackCatalog.EN, warm)
            } else {
                assertNull("nothing to load yet, or ever: $phase", warm)
            }
        }
    }

    @Test fun aLanguageThatIsNotTheSELECTIONIsNeverWarmed() {
        // The brief's own refusal — *"never warm a language that is not the selection"*. Reachable
        // and not theoretical: the board is keyed per language and two packs can arrive at once,
        // so an install completing for a language the user has since moved off must not evict the
        // model for the one they are on. Auto (null) gets no live words at all, by owner ruling, so
        // it gets no load either.
        assertNull("a record for another language", warmsOnInstall(recordLanguage = "fr", packs = setOf("en", "fr")))
        assertNull("Auto — no live words, and so no +169 MB", warmsOnInstall(selection = null))
        assertNull("the raw picker code, if it ever reached here", warmsOnInstall(selection = "auto"))
        assertNull("the switch off is still off", warmsOnInstall(enabled = false))
        assertNull("a marker that is not on disk is nothing to load", warmsOnInstall(packs = emptySet()))
    }

    @Test fun aLiveSessionOrARunningBatchJobRefusesTheLoad() {
        // The existing refusals, inherited rather than re-decided: a second 802-860 ms load and
        // +169 MB beside a live session would run under the very recognizer `PreviewTeeEngine` has
        // BORROWED (the release collector's own reason), and a batch file job is the research's
        // §3.9 refusal — two CPU consumers beside whisper's bursts.
        //
        // SKIPPED, not deferred, exactly like the trim re-prewarm and the model-switch collector:
        // the next session's wrap site is the thing that fills this slot. What it costs is stated
        // in the report, because it is the one shape where this build's promise is delayed.
        assertNull("a session is running", warmsOnInstall(session = true))
        assertNull("a batch file job is running", warmsOnInstall(batch = true))
        assertNull("both", warmsOnInstall(session = true, batch = true))
    }

    @Test fun aPackTheEngineIsAlreadyWARMForIsNotReloaded() {
        // A repair install over a resident, warm pack is the reachable shape. `warm()` is
        // idempotent on the pack and would no-op anyway, so this is a skip and not a mechanism —
        // it spares Main a posted task and the log a line that would read as a second load.
        //
        // The term is the ENGINE's `isWarmFor(pack)` and deliberately NOT `streamingPreviewPack`:
        // after an `onTrimMemory` that field still names the pack while the recognizer is freed,
        // and refusing on it would leave the previewer cold with a receipt promising words.
        assertNull("already warm for it", warmsOnInstall(resident = StreamingPackCatalog.EN))
        assertEquals(
            "warm for ANOTHER language's pack is exactly when this load must happen — the engine " +
                "releases that one inside its own task before it loads this",
            StreamingPackCatalog.EN,
            warmsOnInstall(resident = StreamingPackCatalog.forLanguage("fr")),
        )
    }

    @Test fun theInstallWarmAndTheSessionWarmAgreeAboutWhichPackIsResident() {
        // ONE OWNER FOR *"which pack should be resident"*, held as an equality rather than by
        // reading two call sites — the same discipline
        // [theWarmSiteAsksOfTheLANGUAGEExactlyWhatTheArmSiteAsks] applies to the other pair. This
        // gate adds terms about WHEN; it must add none about WHICH, or an install could load a pack
        // the release collector (which branches on `previewPackToWarm == null`) then frees, and the
        // two would thrash the 802-860 ms load between them.
        val languages = listOf(null, "auto", "en", "es", "zh")
        val sets = listOf(emptySet<String>(), setOf("en"), setOf("es"), everyPack)
        for (lang in languages) for (packs in sets) for (enabled in listOf(true, false)) {
            val case = "lang=$lang installed=$packs enabled=$enabled"
            // The record is the SELECTED language's, which is the only shape the call site can
            // produce (the board is keyed by language and it looks the selection up).
            val onInstall = lang?.let {
                warmsOnInstall(recordLanguage = it, selection = it, packs = packs, enabled = enabled)
            }
            val onSession = warms(lang, packs = packs, enabled = enabled)
            assertEquals("install == session: $case", onSession, onInstall)
        }
    }

    /**
     * **AF6 IS RETIRED BY BEING INVERTED, AND ITS OLD TEXT STAYS READABLE** (4.5.1 Task 1).
     *
     * That row told the owner to expect the first session after an install to show no words. It was
     * the sheet documenting a defect as a design, and it is the reason he raised this as *"people
     * are going to think that it doesn't work"* rather than as a bug: the sheet had already agreed
     * with the behaviour. So the row is rewritten to assert the opposite — and kept, struck
     * through, because a row that once PASSED on device and now says the opposite is how a
     * regression gets mistaken for a fix. That rule is AF2's, earned in 4.5.0's own review round 1,
     * and this is its second application.
     *
     * Pinned here rather than in `StreamingPackClearanceTest` because this is the class that owns
     * the behaviour the row is about; the sheet is already in `sourcePinnedInputs`
     * (`app/build.gradle.kts`), so an edit to it re-runs this.
     */
    @Test fun theAcceptanceSheetsAF6NowAssertsTheOppositeAndStillShowsWhatItSaid() {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile
        val sheet = File(dir, "docs/superpowers/sdd/2026-09-02-431-guards-tts/acceptance.md")
            .readText().replace("\r\n", "\n")
        val row = sheet.substringAfter("AF6. ").substringBefore("AF7. ")
        assertTrue("AF6 must still exist — a deleted row is not a retired one", row.isNotEmpty())
        assertTrue(
            "and must be marked REWRITTEN, AF2's rule: the row passed on device and now says the " +
                "opposite",
            row.contains("REWRITTEN"),
        )
        assertTrue(
            "the 4.5.0 expectation stays readable, struck through — it is the only record of what " +
                "a device session actually confirmed, and of what the sheet once told the owner " +
                "to accept",
            row.contains("~~What it said in 91 and 4.5.0:") &&
                row.contains("NOT to show live words, and the second to.**"),
        )
        assertTrue(
            "the new expectation is the OPPOSITE and says so in the FIRST-session terms the old " +
                "row used",
            row.contains("**and the FIRST session after the download finishes shows live words.**"),
        )
        assertTrue(
            "and it names the mechanism that makes it true, so a device session can tell this " +
                "row from a wish",
            row.contains("warmOnPackInstalled"),
        )
        assertTrue(
            "...and the owner's own words, because this row is the one the ruling was about",
            row.contains("That's a friction point for users."),
        )
        assertTrue(
            "the two deliberate misses are noted on the row, so neither is reported as this row " +
                "failing: an install that lands during a session or a batch job, and a re-pick of " +
                "a pack that was already installed",
            row.contains("while a dictation or a batch file job is running") &&
                row.contains("ALREADY installed"),
        )
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
