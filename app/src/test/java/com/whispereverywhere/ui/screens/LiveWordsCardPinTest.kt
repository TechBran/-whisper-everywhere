package com.whispereverywhere.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 4.4.1's two unexecutable surfaces, pinned as source — `LivePreviewRowsPinTest`'s instrument
 * turned on the discovery path.
 *
 * Neither can be run by a JVM test: Home's card is a `@Composable` that reads
 * `ConnectivityManager` and the Application, and the actuator reaches `AssetPackManager` through
 * [com.whispereverywhere.transcription.stream.StreamingPackController]. Every DECISION they make
 * is pure and lives in `PreviewAutoFetch`, executed cell by cell by `PreviewAutoFetchTest` — so
 * what remains here is WHICH call sits where, and in WHICH order. Those are exactly the facts
 * that would be invisible to every behavioural test and wrong on a device:
 *
 *  - **a second decision.** The whole safety of a silent 73 MB transfer is that ONE pure function
 *    answers whether it may happen. A composable that re-tested `unmetered` itself, or an
 *    actuator that decided to "just go ahead" on a route the decision refused, is a consent rule
 *    with two readings — and the second one is the one no test covers.
 *  - **a hook that decides.** The hook exists to PERFORM the decision. An `if` of its own beside
 *    the `when` is how "never during a session" becomes "usually not during a session".
 *  - **a hand-written sentence on the card.** The card is the surface most users will ever read
 *    about the previewer; a literal here is a sentence `StreamingPackCopyTest` cannot reach, on
 *    the surface that actually ships.
 *  - **a metered read of its own.** Two spellings of the consent question is one too many.
 *  - **a `state()` read per recomposition.** It is a Play `getPackLocation` plus five `File`
 *    reads, and a `Downloading` tick arrives several times a second for the whole 73 MB (the
 *    voice row's review nit 2, which the Settings row already learned).
 *  - **a back-off that is never written.** The failure path's one write is what stops the retry
 *    loop on a bad network; a `runCatching {}` swallowing it compiles clean and loops forever.
 *
 * Both files are in the test task's `sourcePinnedInputs` (`app/build.gradle.kts`): HomeScreen.kt
 * since 4.4.0 Task 2b, and `PreviewAutoFetchController.kt` added with this test. Without those
 * entries a Compose-shaped or comment-shaped edit would leave `:app:testDebugUnitTest`
 * UP-TO-DATE and these pins would pass against the files as they used to be.
 */
class LiveWordsCardPinTest {

    // ------------------------------------------------------------------ source helpers
    // LivePreviewRowsPinTest's own, verbatim.

    private fun source(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            for (candidate in listOf(File(dir, relative), File(dir, "app/$relative"))) {
                if (candidate.isFile) return candidate.readText().replace("\r\n", "\n")
            }
            dir = dir.parentFile
        }
        throw AssertionError("cannot locate $relative from ${System.getProperty("user.dir")}")
    }

    private fun liveLineCount(scope: String, needle: String): Int =
        scope.lineSequence().count { line ->
            val trimmed = line.trimStart()
            val commented =
                trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*")
            !commented && line.contains(needle)
        }

    private fun offsetOfLive(scope: String, needle: String): Int {
        var at = 0
        for (line in scope.split("\n")) {
            val trimmed = line.trimStart()
            val commented =
                trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*")
            if (!commented && line.contains(needle)) return at
            at += line.length + 1
        }
        return -1
    }

    private fun scopeOf(text: String, from: String, to: String): String {
        val a = text.indexOf(from)
        assertTrue("cannot find `$from`", a >= 0)
        val b = text.indexOf(to, a + from.length)
        return if (b < 0) text.substring(a) else text.substring(a, b)
    }

    private val home: String by lazy {
        source("src/main/java/com/whispereverywhere/ui/screens/HomeScreen.kt")
    }

    /** The hook and the card's state — up to the layout shell, which holds no decisions. */
    private val card: String by lazy {
        scopeOf(home, "private fun LiveWordsCard(", "private fun LiveWordsNote(")
    }

    private val actuator: String by lazy {
        source("src/main/java/com/whispereverywhere/transcription/stream/PreviewAutoFetchController.kt")
    }

    // ------------------------------------------------------------------ one decision, one mapping

    @Test fun thereIsExactlyOneDecisionAndExactlyOneCardMapping() {
        assertEquals(
            "a silent 73 MB transfer is safe only because ONE pure function answers whether it " +
                "may happen",
            1, liveLineCount(card, "PreviewAutoFetch.decide("),
        )
        assertEquals(
            "and the card renders the same answer rather than recomputing it — the two can never " +
                "disagree about what is about to happen",
            1, liveLineCount(card, "PreviewAutoFetch.card("),
        )
        assertEquals(
            "the actuator decides only HOW, never WHETHER",
            0, liveLineCount(actuator, "PreviewAutoFetch.decide("),
        )
    }

    @Test fun theHookPerformsTheDecisionAndTestsNothingOfItsOwn() {
        assertEquals(
            "the auto path is entered from exactly one place, and it says so",
            1, liveLineCount(card, "auto = true"),
        )
        val decided = offsetOfLive(card, "PreviewAutoFetch.decide(")
        val performed = offsetOfLive(card, "auto = true")
        assertTrue("the decision must exist", decided >= 0)
        assertTrue(
            "and be taken BEFORE it is performed: a hook that starts a fetch and then asks " +
                "whether it should have is not a gate",
            decided in 0 until performed,
        )
        assertEquals(
            "the fetch is started through the actuator and nowhere else — twice: the hook, and " +
                "the offer card's tap",
            2, liveLineCount(card, "PreviewAutoFetchController.start("),
        )
        for (needle in listOf(
            "StreamingPackController.start(",
            "installFromPack(",
            "StreamingPackInstall.sourceOf(",
            ".download(",
        )) {
            assertEquals(
                "<<$needle>> is the actuator's, not the card's: a second route here is a branch " +
                    "no test can reach, and the one that falls through goes to the third-party " +
                    "download",
                0, liveLineCount(card, needle),
            )
        }
    }

    @Test fun theGateReadsTheHousesOwnOwnersOfEachInputAndReDerivesNone() {
        assertEquals(
            "a session in flight is AudioArbiter's question, and it already owns it",
            1, liveLineCount(card, "AudioArbiter.isCapturing()"),
        )
        assertEquals(
            "a batch job is BatchJobController's, the same input localPreviewArms reads",
            1, liveLineCount(card, "BatchJobController.active != null"),
        )
        assertEquals(
            "the switch comes from its own flow, so a change made in Settings shows here",
            1, liveLineCount(card, "localPreviewEnabledFlow"),
        )
        assertEquals(
            "and it reaches the CARD as well as the decision — twice, once each: with live " +
                "words off there is no true sentence left to spell, least of all \"Live words " +
                "are on\" over an install that landed before the switch did (review r1, B2)",
            2, liveLineCount(card, "showLiveWords = showLiveWords"),
        )
        assertEquals(
            "and the previewer's session gate is NOT re-derived here — the owner tests on Auto " +
                "deliberately and the gate is unchanged (4.4.1 brief, §3)",
            0, liveLineCount(home, "localPreviewArms"),
        )
        assertEquals(
            "the manager is the APPLICATION's: a per-composition one carries a private " +
                "Play-refusal latch the fetch shell could never see",
            0, liveLineCount(home, "StreamingPackManager("),
        )
    }

    // ------------------------------------------------------------------ the ONE collector

    @Test fun theCollectorIsTheSelectedLanguagesOwnStateFlowAndThereIsOnlyOne() {
        // Owner rulings 2026-09-11. The language selection funnels through ONE writer, so one
        // collector on its StateFlow serves the picker's change, onboarding's Continue and the
        // value already in place at foreground — the last of which is the whole top-up, because
        // a user whose language was set before this update never re-selects it.
        assertEquals(
            1, liveLineCount(card, "app.preferencesManager.selectedLanguage.collectAsState()"),
        )
        assertEquals(
            "the RAW picker code, not getLanguageForApi()'s null-for-auto: the rule is one " +
                "comparison against a pack's own language, and \"auto\" refuses by being no " +
                "pack's language",
            0, liveLineCount(card, "getLanguageForApi"),
        )
        for (needle in listOf(".collect {", ".collect(", "while (true)")) {
            assertEquals(
                "<<$needle>>: a second collector is a second chance to start the same fetch, and " +
                    "a hand-rolled one loses the conflation that makes an unchanged value a no-op",
                0, liveLineCount(card, needle),
            )
        }
        assertEquals(
            "the collector READS the selection and never writes it — a card that set the " +
                "language would be choosing for the user",
            0, liveLineCount(card, "setSelectedLanguage"),
        )
    }

    @Test fun thePackIsTheCatalogsAnswerForTheSelectedLanguageAndNeverTheEnglishRow() {
        assertEquals(
            "one lookup, and it is the catalogue's: null for Auto and for every language with no " +
                "row, which is how \"no selected language, no live words\" costs no predicate",
            1, liveLineCount(card, "StreamingPackCatalog.forLanguage(selectedLanguage)"),
        )
        assertEquals(
            "and the English row is NOT named here any more — that literal is exactly what the " +
                "amendment removes, and a card pinned to it would fetch English for a Chinese " +
                "user's phone",
            0, liveLineCount(card, "StreamingPackCatalog.EN"),
        )
        assertEquals(
            "the decision is told which language the state it was handed belongs to",
            1, liveLineCount(card, "packLanguage = pack?.language"),
        )
        assertEquals(
            "and the same selected code keys the flag the X writes, so the pack, the flag and " +
                "the decision cannot name three different languages",
            1, liveLineCount(card, "selectedLanguage = selectedLanguage"),
        )
    }

    @Test fun aSelectionAsksAgainAndAnUnchangedValueAsksNothing() {
        assertEquals(
            "the actuation is keyed on the selection, so picking a language asks ON THE SPOT " +
                "(ruling 2) rather than at the next foreground",
            1, liveLineCount(card, "LaunchedEffect(resumeTick, selectedLanguage, decision)"),
        )
        assertEquals(
            "and so is the pack-state read, because a different language is a different pack",
            1, liveLineCount(card, "null, resumeTick, selectedLanguage, previewPhase,"),
        )
        assertEquals(
            "the once-per-launch latch is asked PER LANGUAGE: a global one would answer a " +
                "second language's first selection with the offer card",
            1, liveLineCount(card, "PreviewAutoFetchController.attemptedThisLaunch(pack)"),
        )
    }

    @Test fun neitherSelectionSiteFetchesAnythingOfItsOwn() {
        // The amendment's own rule: *"do NOT put pack fetching inside setSelectedLanguage — it is
        // a SharedPreferences writer called from Compose click handlers and has no business
        // owning a download"*, and by the same argument neither does a click handler. Both sites
        // write the ONE pref and the collector does the rest.
        val picker = scopeOf(home, "fun LanguageSelectionCard(", "fun StatItem(")
        assertEquals(
            "the in-app picker's one write",
            1, liveLineCount(picker, "setSelectedLanguage(code)"),
        )
        for (needle in listOf(
            "PreviewAutoFetchController",
            "PreviewAutoFetch.decide(",
            "StreamingPackController.start(",
            "streamingPackManager",
        )) {
            assertEquals(
                "<<$needle>> in the language picker: the selection writes a preference, and the " +
                    "collector is what turns that into an arrival",
                0, liveLineCount(picker, needle),
            )
        }
        val onboarding =
            source("src/main/java/com/whispereverywhere/ui/screens/OnboardingFlowScreen.kt")
        assertEquals(
            "onboarding's Continue writes the same one pref",
            1, liveLineCount(onboarding, ".preferencesManager.setSelectedLanguage(picked)"),
        )
        for (needle in listOf(
            "PreviewAutoFetchController",
            "PreviewAutoFetch.decide(",
            "StreamingPackController.start(",
            "StreamingPackCopy.cardOffer(",
        )) {
            assertEquals(
                "<<$needle>> in the onboarding flow: the pack arrives on Home, where the card " +
                    "that reports it lives — not beside the 190 MB speech model the user is " +
                    "already waiting for",
                0, liveLineCount(onboarding, needle),
            )
        }
    }

    @Test fun theMeteredReadingIsTheMonitorsOneCallMadeOncePerForeground() {
        assertEquals(
            1, liveLineCount(card, "ConnectivityMonitor(context).isUnmetered()"),
        )
        assertTrue(
            "keyed on the resume tick: a system call on every recomposition of the dashboard is " +
                "the cost this card must not add",
            liveLineCount(card, "produceState<Boolean?>(null, resumeTick)") >= 1,
        )
        for (needle in listOf("NetworkCapabilities", "isActiveNetworkMetered", "NET_CAPABILITY")) {
            assertEquals(
                "<<$needle>>: the consent question has one spelling and one home",
                0, liveLineCount(home, needle),
            )
        }
    }

    @Test fun thePackStateIsReadOnceAKeyedRefreshAndNotPerRecomposition() {
        assertEquals(
            1, liveLineCount(card, "streamingPackManager.state("),
        )
        val produced = offsetOfLive(card, "produceState<StreamingPackState?>(")
        val read = offsetOfLive(card, "streamingPackManager.state(")
        assertTrue("the read must exist", read >= 0)
        assertTrue("and be inside the keyed producer", produced in 0..read)
        assertEquals(
            "keyed on the resume tick, the selected language and the PHASE — never on the bytes, " +
                "which tick several times a second for 73 MB, and never on the record itself for " +
                "the same reason. ONE key where 4.4.1 needed two (4.5.0 Task 1), because one " +
                "observable covers Play's fetch and our own install alike",
            1, liveLineCount(card, "null, resumeTick, selectedLanguage, previewPhase,"),
        )
        assertEquals(
            "and the phase is read from the one observable, once",
            1, liveLineCount(card, "previewWork?.phase"),
        )
        assertEquals(
            "no status word of Play's own machine survives on this card",
            0, liveLineCount(card, "NpuPackFetch.statusWord("),
        )
    }

    @Test fun neitherSystemReadHappensOnTheCompositionThread() {
        // Review r1, B4. This card sits on the app's START DESTINATION, and both reads are paid
        // on the first frame and on every resume by EVERY user — including one who deleted the
        // model, dismissed the card, or has no local tier, for an answer that is discarded.
        // state() is nine File stats plus a Play getPackLocation (PlayPacks.assetsPath);
        // isUnmetered() is a getSystemService plus a getNetworkCapabilities. HomeScreen's own
        // pattern for this shape of read is produceState + Dispatchers.IO (the keystore and
        // installedModel snapshots, 700 lines above), and the Settings row's own comment says
        // the pack read is too expensive even for a recomposition.
        assertEquals(
            "both reads are taken off the composition thread, each in its own producer",
            2, liveLineCount(card, "withContext(Dispatchers.IO)"),
        )
        for (needle in listOf(
            "remember(resumeTick, statusWord",
            "remember(resumeTick) { ConnectivityMonitor",
        )) {
            assertEquals(
                "<<$needle>>: neither read is taken synchronously in composition any more",
                0, liveLineCount(card, needle),
            )
        }
        assertEquals(
            "the not-yet-known frame answers NONE rather than defaulting to a 73 MB transfer " +
                "decided on inputs that have not been read",
            1, liveLineCount(card, "if (packState == null || unmetered == null)"),
        )
    }

    @Test fun aReRunningProducerHoldsTheCardSilentRatherThanShowingAStaleOffer() {
        // CONTROLLER RULING 2026-09-11, CHANGE 3 (review r2's nit 2, itself a consequence of B4).
        // `produceState` keeps its PREVIOUS value across a key change, and our own install's
        // `finally { _line.value = null }` flips `working` — which re-keys this producer. In that
        // window the snapshot still read PackDelivered/Downloadable while busy() was false and
        // attemptedThisLaunch was true, so `decide` answered OFFER and the card rendered
        // "Install / Download the English preview model" over a model that had just finished
        // installing. Brief and self-correcting, and still a false sentence.
        //
        // The fix is the first of the two the nit named: clear the snapshot, so the frame the
        // producer is re-running in takes the not-yet-known branch (Decision.NONE / Card.NONE)
        // and the card says nothing at all until the new state lands.
        val cleared = offsetOfLive(card, "value = null")
        val read = offsetOfLive(card, "streamingPackManager.state(")
        assertTrue("the snapshot must be cleared while the producer re-runs", cleared >= 0)
        assertTrue(
            "and cleared BEFORE the read it is waiting for — after it, it would blank the card " +
                "every time instead of only while the answer is unknown",
            cleared in 0 until read,
        )
        assertEquals(
            "exactly once: the metered snapshot deliberately keeps its previous value, because a " +
                "stale Boolean there cannot spell a false sentence — it only decides whether the " +
                "offer or the silent fetch is reached, and the not-yet-known guard covers the " +
                "first frame",
            1, liveLineCount(card, "value = null"),
        )
    }

    // ------------------------------------------------------------------ the card's words

    @Test fun theCardSpellsNoSentenceOfItsOwn() {
        for (fragment in listOf(
            "Live words",
            "live words",
            "preview model",
            "English",
            "MB",
            "Pick ",
            "Get ",
            "Dismiss",
        )) {
            assertEquals(
                "<<$fragment>> is hand-written on the card instead of coming from " +
                    "StreamingPackCopy, where the previewer's copy is reviewed and pinned",
                0, liveLineCount(card, "\"$fragment"),
            )
        }
        for (needle in listOf(
            "StreamingPackCopy.CARD_TITLE",
            "StreamingPackCopy.cardWorking(languageName)",
            "StreamingPackCopy.CARD_INSTALLED_TITLE",
            "StreamingPackCopy.cardInstalled(languageName)",
            "StreamingPackCopy.CARD_DISMISS",
            "StreamingPackCopy.cardOffer(offered, languageName)",
            "StreamingPackCopy.cardAction(offered, languageName)",
            "StreamingPackCopy.cardLanguageNote(languageName)",
        )) {
            assertTrue("<<$needle>> must be what the card renders", liveLineCount(home, needle) >= 1)
        }
        assertEquals(
            "every language-bearing sentence takes the SELECTED language's name, from the " +
                "picker's one table — so the card cannot name a language the decision did not " +
                "act on (CONTROLLER RULING 2026-09-11)",
            1,
            liveLineCount(
                card,
                "PreferencesManager.languageDisplayName(selectedLanguage) ?: selectedLanguage",
            ),
        )
        assertEquals(
            "and what Auto costs is said on every card state, from one sentence rather than two " +
                "wordings of it",
            2, liveLineCount(card, "StreamingPackCopy.cardLanguageNote(languageName)"),
        )
    }

    @Test fun theProgressLineIsTheONEObservablesAndNotASecondNarration() {
        // (4.5.0 Task 1) The card used to read a PAIR — `PreviewAutoFetchController.line` for our
        // two routes and `StreamingPackController.state` for Play's fetch — and the Settings row
        // collected only one of them. That is the two-variable answer three review rounds proved
        // wrong; both surfaces now read one record per pack.
        assertEquals(
            "one collector, and it is the board's",
            1, liveLineCount(card, "PreviewWorkboard.work.collectAsState()"),
        )
        assertEquals(
            "one line, whichever route is carrying the bytes",
            1, liveLineCount(card, "StreamingPackCopy.workLine("),
        )
        assertEquals(
            "and neither half of the old pair survives here",
            0,
            liveLineCount(card, "PreviewAutoFetchController.line") +
                liveLineCount(card, "StreamingPackController.state") +
                liveLineCount(card, "StreamingPackInstall.fetchInFlight("),
        )
        assertEquals(
            "whether work is running is the record's own predicate, not a disjunction assembled " +
                "here out of two half-answers",
            1, liveLineCount(card, "previewWork?.inFlight == true"),
        )
    }

    @Test fun theDismissWritesThePersistedNoForTheLanguageTheCardIsAbout() {
        assertEquals(
            "one write, and it carries the language: dismissing the Spanish card says nothing " +
                "about English (owner ruling 2026-09-11, consequence 5)",
            1, liveLineCount(card, "setLivePreviewDeclined(selectedLanguage, true)"),
        )
        assertEquals(
            "read once into a local mirror, the house convention for plain-var prefs in " +
                "composition (cloudNoteDismissed is the precedent and the same card shape) — and " +
                "for the SAME code it writes, which is also the code the pack was resolved from, " +
                "so the X cannot hide a card it never silenced",
            1, liveLineCount(card, "app.preferencesManager.livePreviewDeclined(selectedLanguage)"),
        )
    }

    @Test fun theDismissalAbandonsTheArrivalItWasPressedOn() {
        // CONTROLLER RULING 2026-09-11, CHANGE 2 (the auto-fetch round's C4). The X is the same
        // gesture that writes the permanent no, and a "no" that lets 73 MB finish landing is not
        // a no. The metered path is what makes this reachable and deliberate: a WORKING card on
        // a metered connection exists only because the user tapped, so the X is them changing
        // their mind about their own data.
        assertEquals(
            "one cancel, and it is the actuator's — the card owns no route and no transfer",
            1, liveLineCount(card, "PreviewAutoFetchController.cancel(selectedLanguage)"),
        )
        assertEquals(
            "and it is keyed by the SAME language the declined flag is written for, so the X on " +
                "one language's card can no longer abandon another language's transfer (4.5.0)",
            1, liveLineCount(card, "setLivePreviewDeclined(selectedLanguage, true)"),
        )
        val recorded = offsetOfLive(card, "setLivePreviewDeclined(")
        val cancelled = offsetOfLive(card, "PreviewAutoFetchController.cancel(selectedLanguage)")
        assertTrue("the dismissal must record the no", recorded >= 0)
        assertTrue(
            "and record it BEFORE it cancels: a cancellation that threw would otherwise leave a " +
                "device that re-fetches what the user just refused (the delete row's own rule)",
            recorded in 0 until cancelled,
        )
    }

    /**
     * THE X IS OFFERED ONLY WHERE THE RECORD SAYS THE ABANDON WOULD HAPPEN (4.5.0 Task 1, fix
     * round 2 — review r2's B1c).
     *
     * The dismissal is ONE gesture with TWO halves and only the second is refusable: `cancel`
     * returns on `!work.cancellable`, while the permanent no above it is written
     * unconditionally. Fix round 1 made `TRANSFERRING` and `INSTALLING` uncancellable on every
     * route and left the X live there, so pressing it during a Play install — a streamed sha256
     * of 72,654,782 B plus a copy — wrote the declined flag, hid this card (`card`'s `userSaidNo`
     * outranks `workInFlight`), let the install land, and then armed live words anyway, because
     * `localPreviewArms` has no declined term. *Installed AND declined*, from the only control on
     * the screen.
     *
     * The brief's second half is *"or the UI must not offer a cancel on the route where it
     * cannot"* — a property of the UI, so it is pinned on the UI: the gesture the card hands its
     * layout is derived from the one observable, and the layout draws no X when it is withdrawn.
     */
    @Test fun theDismissIsWithdrawnWhereTheRecordSaysItCannotBeHonoured() {
        assertEquals(
            "the X is DERIVED from the one observable, not handed over unconditionally",
            1,
            liveLineCount(card, "if (previewWork?.dismissable == false) null else dismiss"),
        )
        assertEquals(
            "and every card state takes that derived value — all three, so none of them can " +
                "keep a live X over an install nothing can stop",
            3,
            liveLineCount(card, "onDismiss = dismissWhereItWouldMeanSomething"),
        )
        assertEquals(
            "no state is handed the raw gesture any more",
            0,
            liveLineCount(card, "onDismiss = dismiss,"),
        )
        assertEquals(
            "the layout accepts the withdrawal — a nullable gesture is the whole of it",
            1,
            liveLineCount(home, "onDismiss: (() -> Unit)?,"),
        )
        assertEquals(
            "and DRAWS no X when it is withdrawn: an IconButton over a null gesture would be a " +
                "dead control, which is the same defect as the offer row that started this round",
            1,
            liveLineCount(home, "if (onDismiss != null) {"),
        )
    }

    @Test fun theActuatorsCancelIsGuardedByTheOneObservableAndActsPerRoute() {
        // (4.5.0 Task 1) ONE CANCEL, ONE MEANING. 4.4.1 guarded on `busy()` — global, so the X on
        // one language's card could abandon another language's transfer — and then cancelled BOTH
        // paths unconditionally, on every route, including the delivered pack where nothing can be
        // stopped and a published `Cancelled` describes nothing. The guard is now the record's own
        // `cancellable`, whose table is `PreviewRoute.stopsBeforeTheCopy`'s KDoc.
        assertEquals(1, liveLineCount(actuator, "fun cancel(language: String)"))
        assertEquals(
            "the guard is the one observable's, for the ONE pack this gesture is about",
            1, liveLineCount(actuator, "if (!work.cancellable) {"),
        )
        assertEquals(
            "and the global one is gone",
            0, liveLineCount(actuator, "if (!busy()) return"),
        )
        val looked = offsetOfLive(actuator, "val work = PreviewWorkboard.of(language) ?: return")
        val guard = offsetOfLive(actuator, "if (!work.cancellable) {")
        val routed = offsetOfLive(actuator, "when (work.route) {")
        val ours = offsetOfLive(actuator, "PreviewRoute.DIRECT_DOWNLOAD -> job?.cancel()")
        val plays = offsetOfLive(actuator, "StreamingPackController.cancel()")
        assertTrue("the record is looked up first", looked in 0 until guard)
        assertTrue(
            "the guard answers BEFORE any route is acted on: a cancel that stops nothing must " +
                "not publish one that says it did",
            guard in 0 until routed,
        )
        assertTrue("the fallback download is ours to stop", ours > routed)
        assertTrue("and Play's fetch is Play's", plays > routed)
        assertEquals(
            "the route that cannot be stopped is answered EXPLICITLY, so a fourth route cannot " +
                "fall through into Play's branch and cancel someone else's fetch",
            1, liveLineCount(actuator, "PreviewRoute.DELIVERED_PACK -> return"),
        )
        assertEquals(
            "one cancel of each, so no second path can abandon half the work",
            1, liveLineCount(actuator, "StreamingPackController.cancel()"),
        )
    }

    @Test fun theAnnouncementsRetirementIsReadOncePerForegroundAndReachesOnlyTheCard() {
        // CONTROLLER RULING 2026-09-11, CHANGE 4. The flag is written by the previewer's gate in
        // the service — i.e. while this screen is in the background — so it must be re-read on
        // resume like `saidNo` is, or a user who has just watched live words appear comes back to
        // the announcement they have outgrown.
        assertEquals(
            1, liveLineCount(card, "remember(resumeTick) { app.preferencesManager.livePreviewArmedOnce }"),
        )
        assertEquals(
            "it reaches the card and NOT the decision: having seen live words is not a reason to " +
                "stop fetching a model the user does not have",
            1, liveLineCount(card, "previewHasArmed = hasArmed"),
        )
        assertEquals(
            "and nothing on this screen writes it — the arm path is its only author",
            0, liveLineCount(home, "livePreviewArmedOnce ="),
        )
    }

    @Test fun theAnnouncementAndTheFetchAskTheSameQuestionAboutTheLocalTier() {
        // (4.4.1 pass 3, ITEM 3 — review r1's nit 2.) "Live words are on" is permanently false
        // for a user with the pack and no on-device tier. The card must ask the question the
        // decision already asks, from the SAME value, or the two disagree about who the feature
        // is for — and the hook is the only place that value is read on this screen.
        assertEquals(
            "the decision reads it, and so does the card: ONE value, read once, spent twice",
            2, liveLineCount(card, "localTierInstalled = localTierInstalled,"),
        )
        assertEquals(
            "and it is the screen's own input, never re-derived here from a model list",
            1, liveLineCount(card, "localTierInstalled: Boolean,"),
        )
        val decided = offsetOfLive(card, "PreviewAutoFetch.decide(")
        val mapped = offsetOfLive(card, "PreviewAutoFetch.card(")
        val first = offsetOfLive(card, "localTierInstalled = localTierInstalled,")
        assertTrue("the decision comes first", decided in 0 until mapped)
        assertTrue("and its copy of the input is the one inside it", first in decided until mapped)
    }

    @Test fun theCellularConsentIsPlaysOwnDialogNeverAReAskOfOurs() {
        assertEquals(
            "this card can start a 73 MB Play fetch, and Play raises its own dialog for a " +
                "transfer that size — the missing-voice row's rule, for the same reason",
            1, liveLineCount(card, "StreamingPackController.confirm("),
        )
        assertEquals(
            "read once, so the raise and the card's action cannot disagree about the phase",
            1, liveLineCount(card, "PreviewPhase.AWAITING_ANSWER"),
        )
        assertEquals(
            "and it is the BOARD's phase, not a second reading of Play's own machine (4.5.0)",
            0, liveLineCount(card, "NpuPackFetch.FetchState.NeedsConfirmation"),
        )
    }

    @Test fun theWorkingCardIsNeverADeadEndUnderASentenceAskingForATap() {
        // Review r1, B3: fetchLine(NeedsConfirmation) ends in "tap to answer", the dialog is
        // raised once per ENTRY into that state, and the card had action = null — so a user who
        // back-pressed out of Play's dialog was parked on an instruction naming a gesture the
        // card did not have, with the permanent-no X as the only thing left to press. The same
        // gesture, reached from both places: one lambda, used by the effect and by the action.
        assertEquals(
            "the answer is one lambda, so the button and the effect raise the same dialog",
            1, liveLineCount(card, "val answerPlay: () -> Unit"),
        )
        assertEquals(
            "the effect raises it once per entry into the state...",
            1, liveLineCount(card, "if (playAwaitsAnAnswer) answerPlay()"),
        )
        assertEquals(
            "...and the working card offers it for as long as Play is still waiting",
            1,
            liveLineCount(
                card,
                "action = if (playAwaitsAnAnswer) StreamingPackCopy.CARD_ANSWER_PLAY else null",
            ),
        )
        assertEquals(
            "wired, not decorative — the working card's onAction is that same answer",
            1, liveLineCount(card, "onAction = answerPlay"),
        )
        assertEquals(
            "and the label is StreamingPackCopy's, like every other word on this card",
            1, liveLineCount(card, "StreamingPackCopy.CARD_ANSWER_PLAY"),
        )
    }

    // ------------------------------------------------------------------ the actuator

    @Test fun theActuatorGuardsSingleFlightBeforeItRoutes() {
        assertEquals(
            "one route reduction, and it is the pure one",
            1, liveLineCount(actuator, "StreamingPackInstall.sourceOf("),
        )
        val guard = offsetOfLive(actuator, "if (busy()) return false")
        val routed = offsetOfLive(actuator, "when (StreamingPackInstall.sourceOf(")
        assertTrue("the guard must exist", guard >= 0)
        assertTrue(
            "and come first: two installs write the same staging paths (the import " +
                "controller's N4 lesson, inherited)",
            guard in 0 until routed,
        )
        assertEquals(
            "the once-per-launch latch is set in the AUTO path only — a tap is consent and may " +
                "be repeated — and it is tested and set in ONE step, keyed by LANGUAGE, so two " +
                "auto attempts for one pack cannot both pass and a second language's first " +
                "selection is not refused by the first language's attempt",
            1, liveLineCount(actuator, "if (auto && !autoAttempted.add(pack.language)) return false"),
        )
        val latch = offsetOfLive(actuator, "autoAttempted.add(pack.language)")
        assertTrue("and it is set before the work starts", latch in 0 until routed)
        assertEquals(
            "no global latch survives beside it: a Boolean here silences every other language",
            0, liveLineCount(actuator, "autoAttempted = true"),
        )
    }

    @Test fun eachOfTheThreeRoutesIsActuatedExactlyOnce() {
        assertEquals(1, liveLineCount(actuator, "StreamingPackController.start("))
        assertEquals(1, liveLineCount(actuator, "installFromPack("))
        assertEquals(1, liveLineCount(actuator, ".download("))
        assertEquals(
            "and the pack is NEVER handed back here: the manager gives it back strictly after a " +
                "landed install, which is what makes a failed install a costless retry",
            0, liveLineCount(actuator, "PlayPacks."),
        )
    }

    @Test fun theBackOffIsWrittenOnEveryFailurePathAndNowhereElse() {
        assertEquals(
            "one write site, so no route can fail quietly and loop",
            1, liveLineCount(actuator, "livePreviewAutoFetchFailedAt ="),
        )
        assertEquals(
            "Play's own terminal failure reaches it through the ONE observable's in-flight " +
                "predicate — the same one the card, the row and the delete guard read — never a " +
                "hand-rolled list of terminal states (4.5.0 Task 1)",
            1, liveLineCount(actuator, "it != null && !it.inFlight"),
        )
        assertEquals(
            "and no second reading of Play's own machine survives here",
            0,
            liveLineCount(actuator, "StreamingPackInstall.fetchInFlight(") +
                liveLineCount(actuator, "StreamingPackController.state"),
        )
        assertEquals(
            "and a cancellation is rethrown, never recorded as a failure: a user who cancelled " +
                "has not hit a bad network",
            1, liveLineCount(actuator, "throw cancelled"),
        )
    }

    @Test fun theActuatorNarratesItselfOnOneLineWithNoContentOnIt() {
        assertEquals(
            "one emission site, the fetch shell's own discipline",
            1, liveLineCount(actuator, "Log.i("),
        )
        assertEquals(
            1, liveLineCount(actuator, "\"stream-auto: "),
        )
        // A route name, a flag and an outcome word, and that is the whole line: one `route=`,
        // one `auto=`, one `outcome=`, all on it. Nothing the user said can reach this file — the
        // actuator moves a model file and asks Play for a pack — and nothing it CAN see (a path,
        // an exception message) is put on the line either.
        assertEquals(1, liveLineCount(actuator, "route="))
        assertEquals(1, liveLineCount(actuator, "outcome="))
        assertEquals(0, liveLineCount(actuator, "onDelta"))
        assertEquals(0, liveLineCount(actuator, "absolutePath"))
        // The emitter's own body is where the ban has to hold, and it holds by construction:
        // three named parameters and a format string with nothing else in it.
        val emitter = scopeOf(actuator, "private fun log(", "\n}")
        for (needle in listOf(".message", "absolutePath", "reason")) {
            assertEquals(
                "<<$needle>> must not reach the one emission site",
                0, liveLineCount(emitter, needle),
            )
        }
        assertTrue(
            "and the line is the three fields and nothing more",
            actuator.contains(
                "\"stream-auto: route=\$route auto=\${if (auto) 1 else 0} outcome=\$outcome\"",
            ),
        )
        // (4.5.0 Task 1) The ONE `.message` read in this file goes to the BOARD, not to a log:
        // `StreamingPackException`'s message IS this feature's user-facing refusal (the storage
        // gate, a size or hash mismatch), and the Settings row renders it. 4.4.1 showed it in a
        // Toast that was gone by the time the user looked.
        assertEquals(
            1, liveLineCount(actuator, "(t as? StreamingPackException)?.message"),
        )
        val messageRead = offsetOfLive(actuator, "(t as? StreamingPackException)?.message")
        val boardWrite = offsetOfLive(actuator, "PreviewPhase.FAILED,")
        assertTrue(
            "and it is read INSIDE the failed board write, so it cannot drift onto a log line",
            boardWrite in 0 until messageRead,
        )
    }
}
