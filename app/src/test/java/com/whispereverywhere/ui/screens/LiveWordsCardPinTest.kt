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
            "keyed on the resume tick, the status WORD and our own work — never on the progress " +
                "line, which ticks several times a second for the whole 73 MB",
            1, liveLineCount(card, "null, resumeTick, statusWord, working,"),
        )
        assertEquals(
            1, liveLineCount(card, "NpuPackFetch.statusWord("),
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
            "StreamingPackCopy.CARD_WORKING",
            "StreamingPackCopy.CARD_INSTALLED_TITLE",
            "StreamingPackCopy.CARD_INSTALLED",
            "StreamingPackCopy.CARD_DISMISS",
            "StreamingPackCopy.cardOffer(",
            "StreamingPackCopy.cardAction(",
            "StreamingPackCopy.LANGUAGE_STEP_SENTENCE",
        )) {
            assertTrue("<<$needle>> must be what the card renders", liveLineCount(home, needle) >= 1)
        }
    }

    @Test fun theProgressLineIsTheFeaturesOwnAndNotASecondNarration() {
        assertEquals(
            "our own work's line, published by the actuator",
            1, liveLineCount(card, "PreviewAutoFetchController.line"),
        )
        assertEquals(
            "and Play's own fetch narrates itself through the one pure mapping",
            1, liveLineCount(card, "StreamingPackCopy.fetchLine("),
        )
        assertEquals(
            "whether that fetch counts as work in flight is the pure predicate, not a " +
                "hand-rolled list of states",
            1, liveLineCount(card, "StreamingPackInstall.fetchInFlight("),
        )
    }

    @Test fun theDismissWritesThePersistedNo() {
        assertEquals(
            1, liveLineCount(card, "livePreviewDeclined = true"),
        )
        assertEquals(
            "read once into a local mirror, the house convention for plain-var prefs in " +
                "composition (cloudNoteDismissed is the precedent and the same card shape)",
            1, liveLineCount(card, "app.preferencesManager.livePreviewDeclined)"),
        )
    }

    @Test fun theCellularConsentIsPlaysOwnDialogNeverAReAskOfOurs() {
        assertEquals(
            "this card can start a 73 MB Play fetch, and Play raises its own dialog for a " +
                "transfer that size — the missing-voice row's rule, for the same reason",
            1, liveLineCount(card, "StreamingPackController.confirm("),
        )
        assertEquals(
            "read once, so the raise and the card's action cannot disagree about the state",
            1, liveLineCount(card, "NpuPackFetch.FetchState.NeedsConfirmation"),
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
                "be repeated",
            1, liveLineCount(actuator, "autoAttempted = true"),
        )
        val latch = offsetOfLive(actuator, "autoAttempted = true")
        assertTrue("and it is set before the work starts", latch in 0 until routed)
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
            "Play's own terminal failure reaches it through the pure in-flight predicate, not a " +
                "hand-rolled list of terminal states",
            1, liveLineCount(actuator, "StreamingPackInstall.fetchInFlight("),
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
        assertEquals(0, liveLineCount(actuator, ".message"))
        assertEquals(0, liveLineCount(actuator, "absolutePath"))
    }
}
