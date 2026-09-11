package com.whispereverywhere.ui.screens

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The previewer's two SURFACES, pinned as source — `TtsPackShellPinTest`'s instrument turned on
 * the row the 2026-09-10 amendment rewrote (4.4.0, Task 6).
 *
 * Neither surface can be executed by a JVM test: both are `@Composable`, and the Settings row
 * additionally reaches `AssetPackManager` through [com.whispereverywhere.transcription.stream
 * .StreamingPackController]. Every DECISION they make is pure and lives elsewhere — the source
 * triage is `StreamingPackInstall.resolve`, its reduction to one action is
 * `StreamingPackInstall.sourceOf`, every word is `StreamingPackCopy`, the single-flight guard is
 * `StreamingPackController.isBusy`, the tap guard is `StreamingPackCopy.fetchLineTappable` — so
 * what remains here is WHICH call sits where, and in WHICH order. Those are exactly the facts
 * that would be invisible to every behavioural test and wrong on a device:
 *
 *  - a hand-written sentence on the row, which is how the amendment's whole point ("included
 *    with the app", never "download", on a Play install) gets undone one edit later;
 *  - a per-composition `StreamingPackManager`, whose Play-refusal latch the fetch shell could
 *    never see, so a refusal Play named would never move the row to the fallback;
 *  - a tap that is not guarded, starting a SECOND install into the same temp dir
 *    (`TtsModelManager.fetchLineTappable`'s B1, which this row inherits);
 *  - a language step that offers the English chip to a device with no pack installed.
 *
 * Both files are in the test task's `sourcePinnedInputs` in `app/build.gradle.kts`; without those
 * entries a Compose-shaped or comment-shaped edit would leave `:app:testDebugUnitTest` UP-TO-DATE
 * and these pins would pass against the files as they used to be.
 */
class LivePreviewRowsPinTest {

    // ------------------------------------------------------------------ source helpers
    // (TtsPackShellPinTest's own, verbatim: the same walk, the same LF normalisation, the same
    // comment-blind live-line rule — a pin a commented-out line can satisfy is not a pin.)

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

    /** [from] up to the next [to], so a count or an order pin can name ONE member's body. */
    private fun scopeOf(text: String, from: String, to: String): String {
        val a = text.indexOf(from)
        assertTrue("cannot find `$from`", a >= 0)
        val b = text.indexOf(to, a + from.length)
        // Both ends must be FOUND. A missing `to` used to fall back to "the rest of the file",
        // which silently widens every assertion made against the scope instead of failing loudly
        // (review r3, nits) — renaming the closing anchor must break this test, not weaken it.
        assertTrue("cannot find `$to` after `$from`", b >= 0)
        return text.substring(a, b)
    }

    private val settings: String by lazy {
        source("src/main/java/com/whispereverywhere/ui/screens/SettingsScreen.kt")
    }

    private val onboardingFlow: String by lazy {
        source("src/main/java/com/whispereverywhere/ui/screens/OnboardingFlowScreen.kt")
    }

    private val rows: String by lazy {
        scopeOf(settings, "private fun LivePreviewRows(", "\n@Composable")
    }

    // ------------------------------------------------------------------ the row's words

    @Test
    fun theRowSpellsNoSentenceOfItsOwn() {
        // The amendment's point survives exactly as long as the words stay in one pure table:
        // "the previewer's install row says 'included with the app' on Play builds … the
        // fallback wording only on non-Play builds". A literal here is a sentence no
        // StreamingPackCopyTest assertion can reach, on the surface that actually ships.
        for (fragment in listOf(
            "Live words",
            "preview model",
            "Included with the app",
            "Download a ",
            "MB",
        )) {
            assertEquals(
                "<<$fragment>> is hand-written on the row instead of coming from StreamingPackCopy",
                0, liveLineCount(rows, "\"$fragment"),
            )
        }
        assertTrue(
            "the title comes from the route table, which is what makes it change with the source",
            liveLineCount(rows, "StreamingPackCopy.settingsTitle(") >= 1,
        )
        assertTrue(
            "and so does the subtitle",
            liveLineCount(rows, "StreamingPackCopy.settingsSubtitle(") >= 1,
        )
    }

    // ------------------------------------------------------------------ the row's one action

    @Test
    fun theRowOwnsNoActuatorAtAllAndRoutesNothingInTheComposeTree() {
        // (4.5.0 Task 1, review r3's H3-B2.) This row used to hold a route `when` of its own, run
        // it in `rememberCoroutineScope()` and guard it on `StreamingPackController.isBusy()` —
        // which could not see `PreviewAutoFetchController`'s two routes, so the row offered and
        // STARTED a second 73 MB over work the controller was already doing. The strongest form
        // of that fix is not a wider guard here: it is NO actuator here. One `start`, whose guard
        // spans all three starters, on a process scope that outlives this screen.
        assertEquals(
            "ZERO routing decisions in the tree — the reduction happens once, inside the one " +
                "actuator, where a JVM test can reach it",
            0, liveLineCount(rows, "StreamingPackInstall.sourceOf("),
        )
        assertEquals(
            "and no install of its own on any route",
            0,
            liveLineCount(rows, "installFromPack(") + liveLineCount(rows, ".download("),
        )
        assertEquals(
            "one actuation, and it is the same object Home's card uses",
            1, liveLineCount(rows, "PreviewAutoFetchController.start("),
        )
        assertEquals(
            "a tap is a PICK, never an unasked top-up: it is exempt from the once-per-launch " +
                "latch and recorded on the board as the user's own",
            1, liveLineCount(rows, "PreviewTrigger.TAP"),
        )
        assertEquals(
            "no scope that dies with the screen: leaving Settings mid-download used to cancel " +
                "the transfer while the DownloadManager row kept going",
            0, liveLineCount(rows, "rememberCoroutineScope("),
        )
        assertEquals(
            "the state itself is read through the manager's own triage, not re-derived here",
            0, liveLineCount(rows, "BuildConfig.DEBUG"),
        )
        assertEquals(
            "and Play is never asked whether it can deliver by any second route",
            0, liveLineCount(rows, "playCanDeliver("),
        )
    }

    @Test
    fun theRowReadsTheONEObservableAndNothingElseAboutWorkInFlight() {
        // The defect, in one assertion each. 4.4.1 answered "is work running?" from TWO
        // composition-local values — this composable's own `previewInstallStatus`, which Home
        // could not see, and `StreamingPackController.state`, which knew nothing about the two
        // routes the actuator runs. Every blocker of rounds 1-3 was a consequence of that gap.
        assertEquals(
            "one collector, and it is the board's",
            1, liveLineCount(rows, "PreviewWorkboard.work.collectAsState()"),
        )
        assertEquals(
            "the row's own progress var is GONE — it is the half Home could never see",
            0, liveLineCount(settings, "previewInstallStatus"),
        )
        assertEquals(
            "and so is the second flow of Play's fetch: the shell's machine is private now",
            0, liveLineCount(rows, "StreamingPackController.state"),
        )
        assertEquals(
            "the in-flight row is tappable only where a tap does something — the terminal retry " +
                "and Play's own dialog. Without this the ~73 MB copy+hash takes a second tap " +
                "straight back into the install, into the same temp dir",
            1, liveLineCount(rows, "StreamingPackCopy.workLineTappable("),
        )
        assertEquals(
            "and the cellular/size consent is PLAY'S own dialog, never a re-ask of ours",
            1, liveLineCount(rows, "StreamingPackController.confirm("),
        )
    }

    @Test
    fun theManagerIsTheApplicationsSoTheRefusalLatchIsTheSameObjectTheShellLatched() {
        assertEquals(
            "a per-composition manager carries a private Play-refusal latch, so a refusal Play " +
                "named would never reach the row that has to offer the fallback instead",
            0, liveLineCount(settings, "StreamingPackManager("),
        )
        assertEquals(
            1, liveLineCount(rows, "app.streamingPackManager"),
        )
    }

    @Test
    fun theSwitchIsTheOnePreferenceAndItIsReadReactively() {
        assertEquals(
            "the switch writes the preference the gate reads (R3), and nothing else",
            1, liveLineCount(rows, "preferencesManager.localPreviewEnabled = it"),
        )
        assertEquals(
            "and renders from its flow, so a change made anywhere shows here without a refresh",
            1, liveLineCount(rows, "localPreviewEnabledFlow"),
        )
        assertEquals(
            "the switch row exists only where the model does: an off switch over an absent " +
                "model is a control with nothing behind it",
            1, liveLineCount(rows, "StreamingPackCopy.SWITCH_TITLE"),
        )
        val installedBranch = offsetOfLive(rows, "previewState.isInstalled ->")
        val switch = offsetOfLive(rows, "StreamingPackCopy.SWITCH_TITLE")
        assertTrue("the installed branch must exist", installedBranch >= 0)
        assertTrue("and the switch must be inside it", installedBranch in 0 until switch)
    }

    // ------------------------------------------- a selection the previewer has no pack for

    @Test
    fun aSelectionWithNoPackIsToldTheTruthAndIsOfferedNothing() {
        // (4.4.1 pass 3, ITEM 1 — review r2's nit 3.) The caveat row used to be gated on
        // `selectedLanguage == "auto"`, so a user who picked French read "Get the English preview
        // model", spent 73 MB, and was then told "Installed. Words appear on the bubble as you
        // speak English" — which owner ruling 1 had already decided can never happen for them on
        // any tier. The honest predicate is the CATALOGUE's answer for the selection.
        assertEquals(
            "`== \"auto\"` is not the question: a French picker has no pack either",
            0, liveLineCount(rows, "selectedLanguage == \"auto\""),
        )
        assertEquals(
            "the selection's own pack, resolved once, by the catalogue",
            1, liveLineCount(rows, "StreamingPackCatalog.forLanguage(selectedLanguage)"),
        )
        assertEquals(
            "and the two cases (Auto is a choice, a language with no row is a gap) are chosen " +
                "by the copy's own pair, not by a sentence assembled here",
            1, liveLineCount(rows, "StreamingPackCopy.noLiveWordsTitle("),
        )
        assertEquals(1, liveLineCount(rows, "StreamingPackCopy.noLiveWordsSubtitle("))
        assertEquals(
            "so the row names neither case itself",
            0,
            liveLineCount(rows, "StreamingPackCopy.AUTO_ROW_TITLE") +
                liveLineCount(rows, "StreamingPackCopy.AUTO_NO_LIVE_WORDS"),
        )
        val caveat = offsetOfLive(rows, "StreamingPackCopy.noLiveWordsTitle(")
        val gate = offsetOfLive(rows, "if (selectedPack == previewPack) {")
        val offer = offsetOfLive(rows, "onClick = startPreviewInstall,")
        assertTrue("the offer branch must still be there", offer >= 0)
        assertTrue("the caveat must be in the section", caveat >= 0)
        assertTrue(
            "and every row that describes or offers THIS pack must be gated on the selection " +
                "being the language it is for — offering a model the gate has already refused " +
                "takes a user's storage for a feature they cannot have",
            gate >= 0,
        )
        assertTrue("the caveat is FIRST: a caveat read after the offer changed nothing", caveat < gate)
        assertTrue("and the offer is inside that gate", gate < offer)
    }

    @Test
    fun workAlreadyInFlightKeepsItsSurfaceWhereverTheSelectionGoes() {
        // (fix round 1, H-B3; fix round 2, H2-B1.) The progress row describes a transfer the USER
        // STARTED, not an offer. Inside the selection gate, a language change mid-transfer hid a
        // running 73 MB everywhere in the app — Home renders nothing for a selection with no pack,
        // and neither Play route raises a system notification. So it sits OUTSIDE the gate, under
        // its own not-installed guard. What survives off-selection is the SENTENCE and not the
        // TAP: `workLineTappable`'s two phases are `FAILED` (a fresh 73 MB) and `AWAITING_ANSWER`,
        // which is Play's state BEFORE it has moved a byte (`NpuPackFetch.kt:183-184` maps
        // WAITING_FOR_WIFI and REQUIRES_USER_CONFIRMATION onto it), so answering it AUTHORISES
        // the 73 MB. Both are the spend this pass exists to stop.
        //
        // (4.5.0 Task 1) ONE row where there were two, because there is one observable: the pair
        // existed only because Play's fetch and our install narrated through different values.
        val gate = offsetOfLive(rows, "if (selectedPack == previewPack) {")
        val offer = offsetOfLive(rows, "onClick = startPreviewInstall,")
        val inFlight = offsetOfLive(rows, "if (!previewState.isInstalled && previewWorkLine != null) {")
        val workRow = offsetOfLive(rows, "subtitle = previewWorkLine,")
        assertTrue("the selection gate must still be there", gate >= 0)
        assertTrue(
            "and the in-flight row must sit after it, under its own bytes-not-yet guard, so a " +
                "selection with no pack still sees the transfer it started",
            offer in 0 until inFlight,
        )
        assertTrue("and it renders the one line", inFlight in 0 until workRow)
        assertEquals(
            "the gate renders it NOT AT ALL: the offer must never appear over work already " +
                "running, and ONE condition answers that now",
            1, liveLineCount(rows, "previewWorkLine != null -> Unit"),
        )
        // The guard is decided ONCE, beside the sentence it also gates (H3-B3), so the scope ends
        // at previewWorkLine rather than at the row's tap. Both markers are asserted present by
        // scopeOf now — r3's nit: a `to` marker that silently misses widens the scope to the rest
        // of the file, where `||` appears, and the next two assertions would pass by accident.
        val tapGuard = scopeOf(rows, "val previewTappable =", "val previewWorkLine")
        assertEquals(
            "the tappable answer is spelled ONCE — a second spelling is how the sentence and the " +
                "gesture came to disagree in the first place",
            1, liveLineCount(rows, "val previewTappable ="),
        )
        assertEquals(
            "and the sentence is handed that same answer, never its own reading of the phase — " +
                "as of fix round 2 it is one of THREE surface answers (review r2's N1), so what " +
                "is pinned is that this row's is derived from the hoisted value and from nothing " +
                "else",
            1, liveLineCount(rows, "answer = if (previewTappable) {"),
        )
        assertEquals(
            "and that this row never claims the NO-GESTURE form, which belongs to the strip: " +
                "this row HAS the tap whenever it says it has, and says RE_PICK otherwise",
            0, liveLineCount(rows, "AnswerGesture.NONE"),
        )
        assertEquals(
            "NO tap survives the selection moving: the tap guard's second conjunct is the " +
                "selection itself, and it is the WHOLE of the second conjunct",
            1, liveLineCount(tapGuard, "selectedPack == previewPack"),
        )
        assertEquals(
            "and it is not an OR with anything — an AWAITING_ANSWER disjunct here is the tap " +
                "that AUTHORISES a fresh 73 MB for a language the gate has already refused " +
                "(fix round 2, H2-B1), not a receipt for bytes already moving",
            0, liveLineCount(tapGuard, "||"),
        )
        assertEquals(
            "so AWAITING_ANSWER is named ONCE in the section — inside the tap, which routes it " +
                "to PLAY'S own dialog — and never as a reason the tap is alive off-selection",
            1, liveLineCount(rows, "PreviewPhase.AWAITING_ANSWER"),
        )
    }

    @Test
    fun theDeleteRowFollowsTheBYTESAndNotTheSelection() {
        assertEquals(
            "ONE delete site, wherever it sits", 1, liveLineCount(rows, "StreamingPackCopy.DELETE_TITLE"),
        )
        val lastOffer = offsetOfLive(rows, "onClick = startPreviewInstall,")
        // (fix round 1, H-B2; 4.5.0 Task 1) THE GUARD IS THE BYTES AND NOT THE VERDICT, and it is
        // now DERIVED rather than assembled here: `PreviewDeleteCase.of` answers null where there
        // is nothing under `filesDir` to free, and `isInstalled` is `this is Installed` only, so
        // a `Repair` — marker gone after a load failure, or a file gone short — has to be its own
        // case. With the repair row inside the selection gate, `isInstalled` alone left a damaged
        // install's 73 MB with no reclaim path in the app for a selection with no pack.
        val guard = offsetOfLive(rows, "PreviewDeleteCase.of(")
        val delete = offsetOfLive(rows, "StreamingPackCopy.DELETE_TITLE")
        assertTrue("the offer branches must still be there", lastOffer >= 0)
        assertTrue(
            "the delete is about DISK, not about the selection: 73 MB installed for English has " +
                "to stay reclaimable after the user picks French, so it sits OUTSIDE the " +
                "selection gate — after the last offer branch — under its own derived case",
            lastOffer < guard && guard < delete,
        )
        assertEquals(
            "ONE derivation, and it is the pure one: a `when` over the state plus the selection " +
                "plus what is running, assembled in a composable, is a rule no JVM test can reach",
            1, liveLineCount(rows, "PreviewDeleteCase.of("),
        )
        assertEquals(
            "and the sentence is the case's own — 4.4.1 rendered ONE across all four facts, and " +
                "three of them made it false",
            1, liveLineCount(rows, "StreamingPackCopy.deleteSubtitle("),
        )
        assertEquals(
            "the size is the PACK's own bytes, never a literal: English is 73 MB, German 71 MB " +
                "and French 128 MB. THREE readings since 4.5.0 Task 3d — the delete row's, and " +
                "the two subtitles above it, which drew their figure from one shared class-init " +
                "`val` over the English row until that task and would have described a second " +
                "row wrongly",
            3, liveLineCount(rows, "previewPack.totalBytes"),
        )
        // (fix round 2, H2-B2; review r3's H3-B1) ...and the row cannot render OVER A WRITE.
        // `previewState` is remembered on keys our own install does not change, so through a
        // repair install it stays `Repair` and this row drew beside the running copy. `delete`
        // clears the install dir under a copy that is not cancellation-cooperative, so the copy
        // lands anyway: *"Frees 73 MB"* frees nothing and the pack ends up installed AND declined.
        // 4.4.1 withdrew the row on this composable's own `var`, which saw one of three starters.
        assertEquals(
            "the work answer comes from the ONE observable, which sees all three starters",
            1, liveLineCount(rows, "work = previewWork,"),
        )
        // (fix round 1, review r1's B2) ...AND IT CANNOT CONTRADICT THE SWITCH ONE ROW ABOVE.
        // With "Show live words" off, English selected and the pack installed, the case was LIVE
        // and the row read *"Frees 73 MB. Live words stop"* immediately under the OFF switch.
        // Nothing stopped. The switch is a first-class arming term everywhere else in the feature
        // (`localPreviewArms` conjoins it; `PreviewAutoFetch.card` returns NONE on it) and this
        // was the one sentence that did not ask.
        assertEquals(
            "the SWITCH is a term of the derivation",
            1, liveLineCount(rows, "showLiveWords = previewEnabled,"),
        )
        assertEquals(
            "and it is the SAME value the switch row is drawn from — two spellings is how two " +
                "adjacent rows come to disagree about whether words are showing",
            1, liveLineCount(rows, "checked = previewEnabled,"),
        )
        // (4.5.0 Task 4) ...AND SO IS THE DEVICE. With no on-device speech model the case was
        // LIVE and the row read *"Frees 73 MB. Live words stop"* on a phone where no word has
        // ever appeared — `localPreviewArms` refuses on `!isCloudSession` and every session is a
        // cloud session. It is the third arming fact this row can be wrong about and the only one
        // whose remedy is not on this screen.
        assertEquals(
            "the DEVICE is a term of the derivation",
            1, liveLineCount(rows, "localTierInstalled = localTierInstalled,"),
        )
        assertEquals(
            "and the section is TOLD it, never re-reading the tier itself: this is the same " +
                "`modelRefreshKey`-keyed value the model rows and the 'Delete <tier>' dialog " +
                "above are drawn from, so the two sections cannot disagree about whether the " +
                "tier the user just deleted is gone",
            0,
            liveLineCount(rows, "whisperModelManager") + liveLineCount(rows, "installedModel("),
        )
        assertEquals(
            "handed down from the ONE read, at the one call site",
            1, liveLineCount(settings, "localTierInstalled = installedModel != null,"),
        )
        assertEquals(
            "and the WRITE is the one case with no tap — the row stays, saying what is true, " +
                "which is this feature's answer for every other in-flight row",
            1, liveLineCount(rows, "if (deleteCase == PreviewDeleteCase.WORKING) {"),
        )
        val noTap = offsetOfLive(rows, "if (deleteCase == PreviewDeleteCase.WORKING) {")
        val recorded = offsetOfLive(rows, "setLivePreviewDeclined(previewPack.language, true)")
        assertTrue(
            "and that branch gates the onClick, not something earlier",
            delete < noTap && noTap < recorded,
        )
        assertEquals(
            "the decision it records is still the PACK's language, which is what it deletes",
            1, liveLineCount(rows, "setLivePreviewDeclined(previewPack.language, true)"),
        )
    }

    // ------------------------------------------------------------------ the language step

    @Test
    fun theLanguageStepSaysWhatThePickCostsBeforeItOffersAnyLanguage() {
        // 4.4.1's acquisition amendment rewrote this sentence (owner ruling 1, 2026-09-11: Auto
        // gets no live words, "a very fair trade-off"), so what it must say changed; WHERE it
        // must be said did not, and that is what this pin is for. The words themselves are
        // `StreamingPackCopyTest`'s.
        val step = scopeOf(onboardingFlow, "private fun LanguageStep(", "private fun LanguageRow(")
        assertEquals(
            "the trade is stated once, where the language is chosen (spec §5, §9)",
            1, liveLineCount(step, "StreamingPackCopy.LANGUAGE_STEP_SENTENCE"),
        )
        val sentence = offsetOfLive(step, "StreamingPackCopy.LANGUAGE_STEP_SENTENCE")
        val firstRow = offsetOfLive(step, "OnboardingLogic.languageRows(languageTag).forEach")
        assertTrue("the sentence must be in the step", sentence >= 0)
        assertTrue(
            "and BEFORE the rows: a caveat read after the tap is a caveat that changed nothing",
            sentence in 0 until firstRow,
        )
    }

    @Test
    fun theEnglishChipIsOfferedOnlyWhereThePackIsActuallyInstalled() {
        val step = scopeOf(onboardingFlow, "private fun LanguageStep(", "private fun LanguageRow(")
        assertEquals(
            1, liveLineCount(step, "StreamingPackCopy.LANGUAGE_CHIP"),
        )
        assertEquals(
            "the chip claims an INSTALLED model — offering it on a device with no pack is a " +
                "promise the first session would break",
            1, liveLineCount(step, "code == \"en\" && livePackInstalled ->"),
        )
        assertEquals(
            "and the pack is read ONCE, at flow level, like the language tag beside it: a read " +
                "per recomposition is four File.length() calls on the composition thread",
            1, liveLineCount(onboardingFlow, "streamingPackManager"),
        )
        assertEquals(
            1, liveLineCount(onboardingFlow, "livePackInstalled = livePackInstalled"),
        )
    }
}
