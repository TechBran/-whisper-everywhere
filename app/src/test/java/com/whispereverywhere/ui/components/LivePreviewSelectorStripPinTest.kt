package com.whispereverywhere.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * THE TWO SELECTION SITES' OWN PREVIEWER SURFACE, pinned as source (4.5.0 Task 3c and 3d) —
 * `LiveWordsCardPinTest`'s instrument, turned on the places a language is CHOSEN.
 *
 * It is named for the strip because the strip is the component it protects, but it holds the
 * whole of what rulings 3c and 3d put at a selector: the progress ABOVE the control, at both
 * sites; the deal stated BEFORE the menu opens; the size on the per-language ROW and nowhere
 * else; and the no-pack sentence reaching every language rather than only Auto.
 *
 * Nothing here can be executed by a JVM test: the strip is a `@Composable` and its two call sites
 * are inside composables that read the Application. Every WORD it renders is pure and executed by
 * `StreamingPackCopyTest`; what is left is the four facts no behavioural test could see and each
 * of which would be wrong on a device:
 *
 *  - **the PLACEMENT.** The ruling is *"right there above the language selector"*. A strip that
 *    drifted BELOW the dropdown, or below the onboarding rows, would still render every correct
 *    sentence and would still be the silent spend ruling 3c exists to close — the user would pick,
 *    the transfer would start, and the receipt would be off the bottom of the card.
 *  - **BOTH sites.** The ruling names two, and AF5's compromise (Home only) is what it supersedes.
 *    One site is exactly as invisible as none for the user who onboarded and never returned.
 *  - **one collector, no second read.** The strip exists because there is ONE observable; a
 *    surface that asked `PreviewWorkboard.of(selectedLanguage)` instead of reading every record
 *    would re-create the per-pack blindness Task 1 retired, on the newest surface.
 *  - **no sentence and no gesture of its own.** A literal here is copy no test can reach on the
 *    surface a picking user actually reads; a tap here would be a fourth actuator.
 *
 * The strip's own file joins `sourcePinnedInputs` in `app/build.gradle.kts` with this test, by
 * that list's stated rule: these pins are ORDER and ZERO/ONE-count claims over a Compose file, so
 * without the entry a comment- or layout-shaped edit would leave `:app:testDebugUnitTest`
 * UP-TO-DATE and every pin below green against the file as it used to be.
 */
class LivePreviewSelectorStripPinTest {

    // ------------------------------------------------------------------ source helpers
    // LiveWordsCardPinTest's own, verbatim.

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

    private val strip: String by lazy {
        source("src/main/java/com/whispereverywhere/ui/components/LivePreviewSelectorStrip.kt")
    }

    private val home: String by lazy {
        source("src/main/java/com/whispereverywhere/ui/screens/HomeScreen.kt")
    }

    private val onboarding: String by lazy {
        source("src/main/java/com/whispereverywhere/ui/screens/OnboardingFlowScreen.kt")
    }

    // ------------------------------------------------------------------ the placement

    @Test fun theStripIsABOVETheInAppSelectorAndInsideTheCardThatHoldsIt() {
        val picker = scopeOf(home, "fun LanguageSelectionCard(", "fun StatItem(")
        assertEquals(
            "one strip in the picker, and exactly one",
            1, liveLineCount(picker, "LivePreviewSelectorStrip("),
        )
        val stripAt = offsetOfLive(picker, "LivePreviewSelectorStrip(")
        val selectorAt = offsetOfLive(picker, "ExposedDropdownMenuBox(")
        assertTrue("the selector must be in this card", selectorAt >= 0)
        assertTrue(
            "and the strip ABOVE it: *\"right there above the language selector\"* is the ruling, " +
                "and a receipt under the control that caused the spend is a receipt the picking " +
                "user scrolls past",
            stripAt in 0 until selectorAt,
        )
    }

    @Test fun thePickerStatesTheDealBeforeTheMenuAndBadgesEachRowFromItsOwnPack() {
        // (4.5.0 Task 3d) The deal is read BEFORE the menu opens — the language step's own rule,
        // *"a caveat read after the tap is a caveat that changed nothing"* — and the SIZE is on
        // the per-language row, from that pack's own byte count, because the brief forbids a fixed
        // number and English/German/French are 73/71/128 MB.
        val picker = scopeOf(home, "fun LanguageSelectionCard(", "fun StatItem(")
        assertEquals(
            "one deal sentence, and it is the copy object's",
            1, liveLineCount(picker, "StreamingPackCopy.PICKER_DEAL"),
        )
        val dealAt = offsetOfLive(picker, "StreamingPackCopy.PICKER_DEAL")
        val selectorAt = offsetOfLive(picker, "ExposedDropdownMenuBox(")
        assertTrue("the deal must be stated above the control that makes it", dealAt in 0 until selectorAt)
        assertEquals(
            "one badge, on the row, from the PACK's own size — never a shared constant",
            1, liveLineCount(picker, "StreamingPackCopy.pickerRowBadge(pack.totalBytes)"),
        )
        assertEquals(
            "and the row asks the CATALOGUE which languages have one, so a language with no pack " +
                "gets no badge rather than a \"no model\" chip on fifty rows",
            1, liveLineCount(picker, "StreamingPackCatalog.forLanguage(code)?.let { pack ->"),
        )
        assertEquals(
            "no size literal anywhere in the picker",
            0, liveLineCount(picker, "\"73 MB\"") + liveLineCount(picker, "sizeBadge("),
        )
    }

    @Test fun thePickerTellsAnyLanguageWithNoPackAndNotOnlyAuto() {
        // (4.5.0 Task 3d) *"A language with no pack still says so — 4.4.1's AF8 sentence stands
        // and must not be collapsed into Auto's."* Until this task the picker's caveat was gated
        // on `selectedLanguage == "auto"`, so the user who picked French was told nothing here at
        // all. The honest predicate is the catalogue's — 4.4.1 pass 3's own ITEM 1, applied to
        // the third surface — and the `noLiveWords` pair answers both cases from one input.
        val picker = scopeOf(home, "fun LanguageSelectionCard(", "fun StatItem(")
        assertEquals(
            1, liveLineCount(picker, "if (StreamingPackCatalog.forLanguage(selectedLanguage) == null) {"),
        )
        assertEquals(
            "and the sentence is the pair's, which returns Auto's own arm for null",
            1, liveLineCount(picker, "StreamingPackCopy.noLiveWordsSubtitle(pickedLanguage)"),
        )
        assertEquals(
            "so the previewer's caveat is no longer behind an == \"auto\" test",
            0, liveLineCount(picker, "StreamingPackCopy.AUTO_NO_LIVE_WORDS"),
        )
        assertEquals(
            "the language's own word comes from the picker's one table, null on Auto — the " +
                "Settings row's derivation verbatim, so the two surfaces cannot disagree",
            1,
            liveLineCount(picker, "val pickedLanguage = selectedLanguage.takeIf { it != \"auto\" }"),
        )
    }

    @Test fun theStripIsABOVETheOnboardingStepsRowsToo() {
        // The ruling names two selection sites, and this is the one AF5's compromise left out:
        // "the progress no longer hides on Home". The rows ARE this step's selector.
        val step = scopeOf(onboarding, "private fun LanguageStep(", "private fun LanguageRow(")
        assertEquals(
            1, liveLineCount(step, "LivePreviewSelectorStrip("),
        )
        val stripAt = offsetOfLive(step, "LivePreviewSelectorStrip(")
        val rowsAt = offsetOfLive(step, "OnboardingLogic.languageRows(languageTag).forEach")
        assertTrue("the rows must be in this step", rowsAt >= 0)
        assertTrue("and the strip above them", stripAt in 0 until rowsAt)
        // ...and after the step's own caveat, which is read BEFORE any row is offered and is a
        // different job: that sentence says what the pick buys, this strip says what is arriving.
        val caveatAt = offsetOfLive(step, "StreamingPackCopy.LANGUAGE_STEP_SENTENCE")
        assertTrue("the caveat must still be there", caveatAt >= 0)
        assertTrue(caveatAt in 0 until stripAt)
    }

    @Test fun theStripIsTheSameComponentAtBothSitesAndThereIsOnlyOneOfIt() {
        assertEquals(
            "one declaration, so the two sites cannot drift apart",
            1, liveLineCount(strip, "fun LivePreviewSelectorStrip("),
        )
        assertEquals(
            "and exactly two call sites in the app — the two places a language is chosen",
            2,
            liveLineCount(home, "LivePreviewSelectorStrip(") +
                liveLineCount(onboarding, "LivePreviewSelectorStrip("),
        )
    }

    // ------------------------------------------------------------------ what it reads

    @Test fun theStripReadsTheONEObservableAndEveryRecordInIt() {
        assertEquals(
            "one collector, and it is the board's",
            1, liveLineCount(strip, "PreviewWorkboard.work.collectAsState()"),
        )
        assertEquals(
            "and it reads EVERY record, not the selected language's: the board is keyed by " +
                "language so two arrivals can be seen separately, and a transfer keeps its " +
                "surface when the selection moves off it",
            1, liveLineCount(strip, "board.values.mapNotNull"),
        )
        for (needle in listOf(
            "PreviewWorkboard.of(",
            "PreviewWorkboard.inFlight(",
            "StreamingPackController.state",
            "streamingPackManager",
            "StreamingPackCatalog.EN",
            // (fix round 1, review r1's B2) The three facts the sentences depend on arrive as
            // PARAMETERS, which is the opposite of a second read: no preferences instance, no
            // second flow, and therefore nothing that can go stale behind the observable. The
            // needles below are the READS this file must never grow — `getInstance`, a
            // `preferencesManager`, a `whisperModelManager` — not the inputs it is handed.
            "preferencesManager",
            "whisperModelManager",
            "getInstance(",
        )) {
            assertEquals(
                "<<$needle>>: the strip asks the one observable and nothing else — a second read " +
                    "is the two-variable answer three review rounds proved wrong",
                0, liveLineCount(strip, needle),
            )
        }
        assertEquals(
            "ONE collector in the whole file, and it is the board's: the facts are parameters " +
                "precisely so there is no second source of truth to fall behind it",
            1, liveLineCount(strip, "collectAsState()"),
        )
        assertEquals(
            "the language's WORD comes from the picker's one table, so three surfaces cannot " +
                "name one language three ways",
            1, liveLineCount(strip, "PreferencesManager.languageDisplayName(work.language)"),
        )
    }

    @Test fun theStripDecidesNothingAndActuatesNothing() {
        for (needle in listOf(
            "PreviewAutoFetch.decide(",
            "PreviewAutoFetch.card(",
            "PreviewAutoFetchController",
            "PreviewPicks",
            "isUnmetered",
            "setSelectedLanguage",
            "clickable",
            "onClick",
            "Button",
            "remember",
        )) {
            assertEquals(
                "<<$needle>> on the strip: it is a receipt. A decision here would be a second " +
                    "consent rule, and a tap would be a fourth actuator",
                0, liveLineCount(strip, needle),
            )
        }
        assertEquals(
            "no Application handle either, which is what lets it be dropped into onboarding's " +
                "tree as-is",
            0,
            liveLineCount(strip, "WhisperEverywhereApp") + liveLineCount(strip, "viewModel("),
        )
    }

    @Test fun theStripSpellsNoSentenceOfItsOwn() {
        for (fragment in listOf(
            "Live words",
            "live words",
            "preview model",
            "English",
            "MB",
            "Ready",
            "Downloading",
            "Fetching",
        )) {
            assertEquals(
                "<<$fragment>> is hand-written on the strip instead of coming from " +
                    "StreamingPackCopy, where the previewer's copy is reviewed and pinned",
                0, liveLineCount(strip, "\"$fragment"),
            )
        }
        assertEquals(
            "the line is the copy object's one function over the one observable",
            1, liveLineCount(strip, "StreamingPackCopy.selectorLine("),
        )
        // (fix round 1, review r1's B2) ...and the strip does not JUDGE the three facts it is
        // handed. A conjunction here — `if (showLiveWords && localTierInstalled)` — would be a
        // rule no JVM test can reach on a Compose file, which is `PreviewAutoFetch.card`'s own
        // founding reason. They go into the pure function whole, and it answers null.
        for (rule in listOf("if (showLiveWords", "&& localTierInstalled", "if (localTierInstalled")) {
            assertEquals(
                "<<$rule>> on the strip: which sentence is true is selectorLine's answer",
                0, liveLineCount(strip, rule),
            )
        }
        for (fact in listOf("selectedLanguage = selectedLanguage", "showLiveWords = showLiveWords", "localTierInstalled = localTierInstalled")) {
            assertEquals(
                "<<$fact>>: handed through by name, so a call site cannot pass one of them in " +
                    "another's place",
                1, liveLineCount(strip, fact),
            )
        }
        assertEquals(
            "and the title is the feature's own name, the SAME string the Settings in-flight row " +
                "renders — so the two surfaces describing one transfer cannot head it differently",
            1, liveLineCount(strip, "StreamingPackCopy.featureTitle(language)"),
        )
    }

    @Test fun bothSitesHandTheStripTheThreeFactsAndNeitherOfThemFakesOne() {
        // (fix round 1, review r1's B2) The defect was not a sentence, it was a component built to
        // hold nothing that had three unstated assumptions. The facts now arrive from the call
        // site, and the edit this pin catches is the cheap one: a `true` literal to make it
        // compile, which restores the promise the switch and the tier had already withdrawn.
        val picker = scopeOf(home, "fun LanguageSelectionCard(", "fun StatItem(")
        val step = scopeOf(onboarding, "private fun LanguageStep(", "private fun LanguageRow(")
        for ((site, scope) in listOf("picker" to picker, "onboarding step" to step)) {
            for (literal in listOf(
                "showLiveWords = true",
                "localTierInstalled = true",
                "selectedLanguage = \"",
            )) {
                assertEquals(
                    "$site: <<$literal>> is the assumption coming back as a constant",
                    0, liveLineCount(scope, literal),
                )
            }
        }
        assertEquals(
            "the picker's selection is the one it renders the field and the badge from",
            1, liveLineCount(picker, "selectedLanguage = selectedLanguage,"),
        )
        assertEquals(
            "and the switch is the SAME flow the live-words card reads, not a second read",
            1, liveLineCount(picker, "app.preferencesManager.localPreviewEnabledFlow.collectAsState()"),
        )
        assertEquals(
            "the tier is PASSED DOWN from the screen's own resume-refreshed read, so this card " +
                "and the live-words card above it cannot disagree about whether a device can arm",
            1, liveLineCount(home, "LanguageSelectionCard(localTierInstalled = hasSpeechModel)"),
        )
        assertEquals(
            "at the onboarding step the PICK is the selection — there is no stored selection " +
                "until Continue",
            1, liveLineCount(step, "selectedLanguage = picked,"),
        )
        assertEquals(1, liveLineCount(step, "showLiveWords = liveWordsSwitchOn,"))
        assertEquals(1, liveLineCount(step, "localTierInstalled = liveTierInstalled,"))
    }

    @Test fun theStripDrawsNothingWhenThereIsNothingToSay() {
        // It sits above a selector on the app's start destination and on the onboarding step, so
        // the no-work case is the case: every user whose pack is already installed, on every
        // visit. An empty Column would still take its padding and its Spacer.
        assertEquals(
            1, liveLineCount(strip, "if (rows.isEmpty()) return"),
        )
        val guard = offsetOfLive(strip, "if (rows.isEmpty()) return")
        val drawn = offsetOfLive(strip, "Column(modifier.fillMaxWidth())")
        assertTrue("the guard must exist", guard >= 0)
        assertTrue("and come before anything is drawn", guard in 0 until drawn)
    }
}
