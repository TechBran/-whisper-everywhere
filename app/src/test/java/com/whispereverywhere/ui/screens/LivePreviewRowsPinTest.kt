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
        return if (b < 0) text.substring(a) else text.substring(a, b)
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
    fun theRouteIsDecidedOnceByThePureReduction_neverInTheComposeTree() {
        assertEquals(
            "ONE routing decision, and it is the pure one: a second `when` over " +
                "StreamingPackState in a composable is a branch no JVM test can reach, and the " +
                "one that falls through goes to the third-party download",
            1, liveLineCount(rows, "StreamingPackInstall.sourceOf("),
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
    fun theTapIsRefusedWhileTheShellIsWorking_andTheGuardComesFirst() {
        assertEquals(
            "the one action refuses while the fetch shell is working — TtsPackShellPinTest's " +
                "own pin, for the defect this row would otherwise repeat",
            1, liveLineCount(rows, "StreamingPackController.isBusy()) return@start"),
        )
        val refused = offsetOfLive(rows, "StreamingPackController.isBusy()) return@start")
        val routed = offsetOfLive(rows, "when (StreamingPackInstall.sourceOf(")
        assertTrue("the refusal must be IN the action", refused >= 0)
        assertTrue(
            "and it must be a GUARD: read at TAP time and answered before the route is acted " +
                "on, never a composition-time flag the row could be left dead by",
            refused in 0 until routed,
        )
        assertEquals(
            "the in-flight row is tappable only where a tap does something — the terminal retry " +
                "and Play's own dialog. Without this the ~73 MB copy+hash takes a second tap " +
                "straight back into installFromPack, into the same temp dir",
            1, liveLineCount(rows, "StreamingPackCopy.fetchLineTappable("),
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
        // (fix round 1, H-B3.) The progress row and the Play row describe a transfer the USER
        // STARTED, not an offer. Inside the selection gate, a language change mid-transfer hid a
        // running 73 MB everywhere in the app — Home renders nothing for a selection with no pack
        // — and took Play's *"tap to answer"* with it, which is the one gesture the r1 B3 fix
        // exists to provide. So they sit OUTSIDE the gate, under their own not-installed guard.
        val gate = offsetOfLive(rows, "if (selectedPack == previewPack) {")
        val offer = offsetOfLive(rows, "onClick = startPreviewInstall,")
        val inFlight = offsetOfLive(rows, "if (!previewState.isInstalled) {")
        val ourProgress = offsetOfLive(rows, "subtitle = previewInstallStatus ?: \"\",")
        val playLine = offsetOfLive(rows, "subtitle = previewFetchLine,")
        assertTrue("the selection gate must still be there", gate >= 0)
        assertTrue(
            "and the two in-flight rows must sit after it, under their own bytes-not-yet guard, " +
                "so a selection with no pack still sees the transfer it started",
            offer in 0 until inFlight,
        )
        assertTrue("ours first", inFlight in 0 until ourProgress)
        assertTrue("then Play's", ourProgress < playLine)
        assertEquals(
            "and the gate renders NEITHER of them: the two must never both draw, and the offer " +
                "must never appear over work already running",
            1, liveLineCount(rows, "previewInstallStatus != null || previewFetchLine != null -> Unit"),
        )
        assertEquals(
            "the terminal RETRY is the one tap that does not survive the selection moving — a " +
                "fresh 73 MB for a language the gate has already refused is the offer this " +
                "section stopped making",
            1, liveLineCount(rows, "selectedPack == previewPack ||"),
        )
        assertEquals(
            "while PLAY'S OWN confirmation still answers, because that is the transfer in flight",
            1, liveLineCount(rows, "previewFetch is NpuPackFetch.FetchState.NeedsConfirmation"),
        )
    }

    @Test
    fun theDeleteRowFollowsTheBYTESAndNotTheSelection() {
        assertEquals(
            "ONE delete site, wherever it sits", 1, liveLineCount(rows, "StreamingPackCopy.DELETE_TITLE"),
        )
        val lastOffer = offsetOfLive(rows, "onClick = startPreviewInstall,")
        // (fix round 1, H-B2) THE GUARD IS THE BYTES AND NOT THE VERDICT. `isInstalled` is
        // `this is Installed` only, and `Repair` — marker gone after a load failure, or a file
        // gone short — is the one state where the bytes are on disk and that property is false.
        // With the repair row inside the selection gate, `isInstalled` alone left a damaged
        // install's 73 MB with no reclaim path in the app for a selection with no pack.
        val guard = offsetOfLive(
            rows,
            "if (previewState.isInstalled || previewState is StreamingPackState.Repair) {",
        )
        val delete = offsetOfLive(rows, "StreamingPackCopy.DELETE_TITLE")
        assertTrue("the offer branches must still be there", lastOffer >= 0)
        assertTrue(
            "the delete is about DISK, not about the selection: 73 MB installed for English has " +
                "to stay reclaimable after the user picks French, so it sits OUTSIDE the " +
                "selection gate — after the last offer branch — under its own bytes-are-here guard",
            lastOffer < guard && guard < delete,
        )
        assertEquals(
            "and that guard answers for the DAMAGED install too — `markCorrupt` removes the " +
                "marker and leaves the bytes, so a delete keyed on the verdict strands them",
            1,
            liveLineCount(rows, "previewState is StreamingPackState.Repair) {"),
        )
        assertEquals(
            "and the decision it records is still the PACK's language, which is what it deletes",
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
