package com.whispereverywhere.data.local

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two persisted values 4.4.1 adds, and the one gesture that writes the first of them —
 * pinned as SOURCE, because this project runs plain JVM unit tests with no Robolectric:
 * `PreferencesManager` needs a `Context` to reach `SharedPreferences`, and the Settings row that
 * deletes the model is a `@Composable`. Neither can be executed here, and both hold a promise a
 * device session would only catch by accident:
 *
 *  - **the default is FALSE.** `livePreviewDeclined` says "the user said no". Shipped as `true`
 *    it would decline the feature for every user in the world on first launch and the auto-fetch
 *    would simply never happen — a silent no-op no behavioural test could see, because every
 *    behavioural test passes its own value for that input.
 *  - **the key is spelled once.** A renamed key forgets every user's recorded decision, and the
 *    first thing that happens after it is forgotten is a 73 MB fetch of a model somebody deleted
 *    on purpose (AF3).
 *  - **a DELETE sets it.** The brief's own words: *"a DELETE is a decision and must not be undone
 *    by an auto-fetch"*. The delete lives in the Settings row, so this is where that coupling is
 *    held; `PreviewAutoFetchTest` holds the other half (the flag is absolute, in all 3,584 cells).
 *  - **there is NO metering read in the app at all, and the VALIDATED one has one home** (4.5.0
 *    pass 2, Fix 1). The owner: *"Yes. I wanted to silently download on cellular and Wi Fi."* So
 *    the question *"may this app spend these bytes"* is not asked anywhere, and the question that
 *    survives — *"is there a network that works"* — has one spelling, because a doomed fetch
 *    parks the pack behind a 24 h back-off. **And no SENTENCE in the main tree still states the
 *    overruled rule either** (fix round 1, review r1's B1): the code walk is comment-blind by
 *    design, so the prose gets its own walk, which is comment-inclusive by the same design.
 *  - **and *"the user has seen live words"* is written when the session OPENS** (4.5.0 T4 fix
 *    round 1, review r1's B1). It used to be written wherever the previewer's gate armed, and
 *    that gate has no tier term: on a device with no speech model and no configured provider it
 *    arms, the session then dies at connect, and a global permanent flag said that user had
 *    watched live words appear — suppressing *"Live words are on"* forever for the one reader the
 *    announcement exists for. The order pinned here is the fix.
 *
 * Both files read here are already in the test task's `sourcePinnedInputs`
 * (`app/build.gradle.kts`) except `ConnectivityMonitor.kt`, which this test adds — without those
 * entries an edit confined to one of them would leave `:app:testDebugUnitTest` UP-TO-DATE and
 * these pins would pass against the file as it used to be.
 */
class LivePreviewDeclinedPinTest {

    // ------------------------------------------------------------------ source helpers
    // LivePreviewRowsPinTest's own, verbatim: the same walk, the same LF normalisation, the same
    // comment-blind live-line rule — a pin a commented-out line can satisfy is not a pin.

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

    private val prefs: String by lazy {
        source("src/main/java/com/whispereverywhere/data/local/PreferencesManager.kt")
    }

    private val settings: String by lazy {
        source("src/main/java/com/whispereverywhere/ui/screens/SettingsScreen.kt")
    }

    private val rows: String by lazy {
        scopeOf(settings, "private fun LivePreviewRows(", "\n@Composable")
    }

    private val connectivity: String by lazy {
        source("src/main/java/com/whispereverywhere/net/ConnectivityMonitor.kt")
    }

    /** The previewer's gate call site — the one place that knows live words actually armed. */
    private val service: String by lazy {
        source("src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt")
    }

    private fun repoFile(relative: String): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            for (candidate in listOf(File(dir, relative), File(dir, "app/$relative"))) {
                if (candidate.isFile) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError("cannot locate $relative from ${System.getProperty("user.dir")}")
    }

    /**
     * Every Kotlin source under the app's MAIN tree — anchored on a FILE rather than on a
     * directory name, so a moved tree fails here loudly instead of walking an empty tree to a
     * vacuous pass (`PreviewUnreachableTest`'s own rule for the same instrument).
     *
     * MAIN only, deliberately: this file has to be able to spell the needles it forbids.
     * `sourcePinnedInputs` is not needed for it either — the absences it asserts are CODE, so
     * adding one changes `compileDebugKotlin`'s output and this task re-runs. (That is exactly
     * what the pinned-input list is for the other, comment-shaped pins in this project.)
     */
    private val everyMainSource: List<File> by lazy {
        val root = repoFile("src/main/java/com/whispereverywhere/net/ConnectivityMonitor.kt")
            .parentFile?.parentFile
        assertTrue("cannot reach the app's package root", root?.isDirectory == true)
        val files = requireNotNull(root).walkTopDown()
            .filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(
            "the main tree must be walked, and it is ~200 files: found ${files.size}",
            files.size > 100,
        )
        files
    }

    /**
     * The files with a LIVE line matching [needle] — comment-blind, like every other pin here.
     * The home of a deleted rule has to be able to name what it deleted and why; what may not
     * come back is the READ.
     */
    private fun mainSourcesSaying(needle: String): List<String> =
        everyMainSource
            .filter { liveLineCount(it.readText().replace("\r\n", "\n"), needle) > 0 }
            .map { it.name }.distinct().sorted()

    /**
     * The files whose text contains [needle] ANYWHERE — **comments included**, which is the one
     * place in this file that rule is inverted, deliberately (4.5.0 pass 2, fix round 1, review
     * r1's B1).
     *
     * Its neighbour [mainSourcesSaying] hunts a READ and must be comment-blind, so the home of a
     * deleted rule can still name what it deleted. This one hunts a SENTENCE: an overruled rule
     * restated in the present tense, in prose, is the harm — and every instance of it found so
     * far has been in a KDoc, where a comment-blind walk cannot reach by construction. A pin that
     * skips comments is no pin at all for prose.
     *
     * The escape hatch for a legitimate historical note is the PAST tense, which is also the only
     * honest tense for a rule that no longer holds: *"this comment used to say…"* passes, *"an
     * unasked top-up waits for…"* does not.
     */
    private fun mainSourcesMentioning(needle: String): List<String> =
        everyMainSource
            .filter { it.readText().contains(needle, ignoreCase = true) }
            .map { it.name }.distinct().sorted()

    // ------------------------------------------------------------------ the flag

    @Test fun theUserSaidNoFlagIsReadThroughOnePureSeamWithOneDefault() {
        // The DEFAULT and the KEY are pinned as behaviour by LivePreviewDeclinedPerLanguageTest,
        // which executes the real seam; what is pinned here is that the seam is the only path —
        // a second `getBoolean` over the same prefix would be a second default, and the accessor
        // pair the two surfaces call must be singular or the X and the delete can disagree.
        assertEquals(
            1, liveLineCount(prefs, "readLivePreviewDeclined(languageCode, prefs::getBoolean)"),
        )
        assertEquals(
            "one reader of the store, and it is the pure seam's own",
            1, liveLineCount(prefs, "getBoolean(livePreviewDeclinedKey(languageCode), false)"),
        )
        assertEquals(
            "and exactly one writer",
            1, liveLineCount(prefs, "putBoolean(livePreviewDeclinedKey(languageCode), declined)"),
        )
        assertEquals(
            "the accessor pair is spelled once each, so no surface can reach a second opinion",
            1, liveLineCount(prefs, "fun livePreviewDeclined(languageCode: String): Boolean"),
        )
        assertEquals(
            1, liveLineCount(prefs, "fun setLivePreviewDeclined(languageCode: String, declined: Boolean)"),
        )
        assertEquals(
            "and the GLOBAL flag is gone rather than left beside it: two spellings of \"the user " +
                "said no\" is how a Spanish delete silences English",
            0, liveLineCount(prefs, "var livePreviewDeclined: Boolean"),
        )
    }

    @Test fun theFlagsStorageKeyIsSpelledExactlyOnce() {
        // A renamed key forgets every user's recorded decision, and the first thing that happens
        // after it is forgotten is a 73 MB fetch of a model somebody deleted on purpose.
        assertEquals(
            1,
            liveLineCount(
                prefs,
                "KEY_LIVE_PREVIEW_DECLINED_PREFIX = \"live_preview_declined_\"",
            ),
        )
        assertEquals(
            "and composed in exactly one place — the key a surface writes and the key the " +
                "decision reads are the same function or they are not the same key",
            1,
            liveLineCount(prefs, "KEY_LIVE_PREVIEW_DECLINED_PREFIX + languageCode"),
        )
        assertEquals(
            1,
            liveLineCount(
                prefs,
                "KEY_LIVE_PREVIEW_AUTOFETCH_FAILED_AT = \"live_preview_autofetch_failed_at\"",
            ),
        )
    }

    // ------------------------------------------------------------------ the announcement retires

    @Test fun theSeenLiveWordsFlagDefaultsToFalseAndIsWrittenWhenTheSessionOPENS() {
        // CONTROLLER RULING 2026-09-11, CHANGE 4. Shipped `true` the announcement never appears
        // at all, which is the silent no-op no behavioural test can see; and if nothing ever
        // writes it, the announcement is permanent again and the X — the permanent no — is the
        // only way out, which is the defect this flag exists to remove.
        assertEquals(
            1, liveLineCount(prefs, "prefs.getBoolean(KEY_LIVE_PREVIEW_ARMED_ONCE, false)"),
        )
        assertEquals(
            "one reader, so no second read can carry a different default",
            1, liveLineCount(prefs, "getBoolean(KEY_LIVE_PREVIEW_ARMED_ONCE"),
        )
        assertEquals(
            1, liveLineCount(prefs, "putBoolean(KEY_LIVE_PREVIEW_ARMED_ONCE, value)"),
        )
        assertEquals(
            1, liveLineCount(prefs, "KEY_LIVE_PREVIEW_ARMED_ONCE = \"live_preview_armed_once\""),
        )
        assertEquals(1, liveLineCount(prefs, "var livePreviewArmedOnce: Boolean"))
        assertEquals(
            "and it is NOT the declined flag: \"I have seen this\" is not \"I do not want this\"",
            0, liveLineCount(prefs, "KEY_LIVE_PREVIEW_DECLINED = \"live_preview_armed_once\""),
        )
        // The write is guarded by the gate's own answer and never by a re-derivation of it...
        assertEquals(
            1,
            liveLineCount(
                service,
                "if (previewArmed) app.preferencesManager.livePreviewArmedOnce = true",
            ),
        )
        val gate = offsetOfLive(service, "val previewArmed = localPreviewArms(")
        val written = offsetOfLive(service, "livePreviewArmedOnce = true")
        assertTrue("the gate must exist", gate >= 0)
        assertTrue(
            "and answer BEFORE the flag is written — a write above the gate would record an arm " +
                "that never happened",
            gate in 0 until written,
        )
        // ...AND IT IS WRITTEN WHEN THE SESSION OPENS, not when the gate answers (4.5.0 T4 fix
        // round 1, review r1's B1). The gate has no tier term, so on a device with no speech model
        // and no configured provider it ARMS — `decideEngineChoice` answers LOCAL_ONLY, so the
        // session is not a cloud one — while `LocalWhisperEngine.connect` answers "No speech model
        // installed" and the session is torn down out of CONNECTING. Writing the flag at the gate
        // marked that user as having watched live words appear, and this flag is global and
        // permanent: the day they followed the feature's own advice and installed a speech model,
        // "Live words are on" was suppressed forever, for exactly the reader it exists for. onOpen
        // is the first instant a word can have appeared — before it the capture thread only fills
        // the startup ring (`StartupSeam.route` buffers until `engineReady`).
        val opened = offsetOfLive(service, "updateBubbleState(BubbleState.RECORDING)")
        assertTrue("the session must still reach RECORDING at exactly one site", opened >= 0)
        assertEquals(
            1, liveLineCount(service, "updateBubbleState(BubbleState.RECORDING)"),
        )
        assertTrue(
            "the flag is written AFTER the session reaches RECORDING, so a session that arms " +
                "and never opens writes nothing",
            opened < written,
        )
        assertTrue(
            "and the write sits inside onOpen, below the readiness flag the capture thread reads",
            offsetOfLive(service, "engineReady = true") in 0 until written,
        )
        assertEquals(
            "one write site in the whole service, so no other path can claim the user has seen " +
                "live words",
            1, liveLineCount(service, "livePreviewArmedOnce"),
        )
    }

    @Test fun theBackOffStampDefaultsToZeroWhichIsNeverABackOff() {
        // PreviewAutoFetch.backedOff treats 0 as "no failure recorded" and 0 is what an absent
        // pref reads as. Any other default here would silence the auto-fetch out of the box.
        assertEquals(
            1, liveLineCount(prefs, "prefs.getLong(KEY_LIVE_PREVIEW_AUTOFETCH_FAILED_AT, 0L)"),
        )
        assertEquals(
            1, liveLineCount(prefs, "putLong(KEY_LIVE_PREVIEW_AUTOFETCH_FAILED_AT, value)"),
        )
    }

    @Test fun neitherValueIsReachableThroughAnySecondSpellingOfItsName() {
        // The flag is read by the hook and written by two gestures (the card's X, the delete).
        // A second property over the same key is how those four sites start disagreeing.
        assertEquals(1, liveLineCount(prefs, "fun livePreviewDeclined(languageCode: String)"))
        assertEquals(1, liveLineCount(prefs, "var livePreviewAutoFetchFailedAt: Long"))
    }

    // ------------------------------------------------------------------ a delete is a decision

    @Test fun theDeleteRecordsTheDecisionBeforeItRemovesTheBytes() {
        assertEquals(
            "the brief's own rule: a DELETE is a decision and must not be undone by an " +
                "auto-fetch (AF3) — and it is recorded for THIS PACK'S language, from the pack " +
                "itself rather than a retyped code, so the flag and the bytes cannot drift (AF8)",
            1,
            liveLineCount(rows, "setLivePreviewDeclined(previewPack.language, true)"),
        )
        val decision = offsetOfLive(rows, "setLivePreviewDeclined(")
        val removal = offsetOfLive(rows, "previewManager.delete(")
        assertTrue("the delete branch must still exist", removal >= 0)
        assertTrue("and the flag must be written in it", decision >= 0)
        assertTrue(
            "the decision is recorded FIRST: a removal that failed partway must not leave a " +
                "device that re-fetches what the user asked to be rid of",
            decision in 0 until removal,
        )
        val deleteTitle = offsetOfLive(rows, "StreamingPackCopy.DELETE_TITLE")
        assertTrue(
            "and it belongs to the DELETE row, not to the switch or the install above it",
            deleteTitle in 0 until decision,
        )
    }

    @Test fun theSettingsRowGrowsNoDecisionOfItsOwn() {
        // "The Settings rows STAY exactly as they are — they are the manual path and the owner
        // likes them." What that protects is the DECISION: the row must not grow an auto-fetch, a
        // card, or a second opinion about metering. A tap on it is always the user asking, and
        // nothing on it may decide to spend their data for them.
        for (needle in listOf(
            "PreviewAutoFetch.decide(",
            "PreviewAutoFetch.card(",
            "ConnectivityMonitor(",
        )) {
            assertEquals(
                "<<$needle>> belongs to the Home card's hook, not to the manual path",
                0, liveLineCount(settings, needle),
            )
        }
        // (4.5.0 Task 1, review r3's H3-B2) The ACTUATOR is now shared, and that is the fix
        // rather than a violation of the rule above. The row held its own route `when` in a
        // `rememberCoroutineScope()`, guarded only on `StreamingPackController.isBusy()` — which
        // could not see `PreviewAutoFetchController`'s two routes — so it offered and STARTED a
        // second 73 MB over work already running. One actuator, one guard spanning all three
        // starters. What the row hands it is still the user's own tap:
        assertEquals(
            "exactly one actuation from the manual path, and it is the one actuator",
            1, liveLineCount(settings, "PreviewAutoFetchController.start("),
        )
        assertEquals(
            "declared a TAP, never an unasked top-up and never a selection: a tap is consent " +
                "that may be repeated, so it is the one cause exempt from the once-per-launch " +
                "latch, and it never waits for anything the unasked path waits for (4.5.0 T3)",
            1, liveLineCount(settings, "PreviewTrigger.TAP"),
        )
        assertEquals(
            "and the row starts nothing else and cancels nothing — the X is the card's gesture",
            0,
            liveLineCount(settings, "PreviewAutoFetchController.cancel(") +
                liveLineCount(settings, "PreviewAutoFetchController.busy("),
        )
    }

    // --------------------------------------------------------- the connectivity reading (Fix 1)

    @Test fun theAppHasNoMeteringReadAtAllAnywhereInItsMainSource() {
        // **THE OWNER'S RULING, AS A PROPERTY OF THE TREE** (4.5.0 pass 2, Fix 1). Asked directly
        // whether both acquisition paths should simply download: *"Yes. I wanted to silently
        // download on cellular and Wi Fi."*
        //
        // This replaces three pins that held the OTHER side of it — they asserted
        // `isUnmetered()`'s body line by line, and they were right against the CONTROLLER ruling
        // they were written for. That ruling is overruled, the predicate is deleted, and the
        // whole-product walk is what stops it being reinvented: a metering read anywhere is a
        // consent question this feature is not allowed to ask, and the one place it would
        // reappear is a helper somebody adds beside the one that survived.
        //
        // A WALK rather than a list of files, for the reason the mechanism walk in
        // `PreviewUnreachableTest` is one: a fix round that corrects the sites a reviewer happened
        // to name leaves the ones nobody cited.
        //
        // The last two needles are review r1's nit 1: the first three leave two holes a wifi gate
        // would fall straight through. `NET_CAPABILITY_NOT_METERED` is not a substring of
        // `NET_CAPABILITY_TEMPORARILY_NOT_METERED`, and against `DownloadManager` the gate would
        // most naturally be written as `setAllowedNetworkTypes(...NETWORK_WIFI)` rather than by
        // reading a capability at all. Neither spelling appears in the tree today.
        for (needle in listOf(
            "NET_CAPABILITY_NOT_METERED",
            "NET_CAPABILITY_TEMPORARILY_NOT_METERED",
            "isActiveNetworkMetered",
            "setAllowedOverMetered(false)",
            "setAllowedNetworkTypes(",
        )) {
            val found = mainSourcesSaying(needle)
            assertEquals(
                "<<$needle>> is a question about what the bytes COST, and the owner ruled that " +
                    "this feature does not ask it. Found: $found",
                emptyList<String>(),
                found,
            )
        }
    }

    @Test fun noSentenceInMainSourceStillStatesTheRuleTheOwnerOverruled() {
        // **THE OTHER HALF OF THE SAME INSTRUMENT** (4.5.0 pass 2, fix round 1 — review r1's B1),
        // and the root cause it closes, stated once: the ruling's CODE got a whole-tree walk in
        // pass 2 (the test above) while the ruling's PROSE got a hand-listed set of six files. So
        // three sentences asserting the DELETED rule survived the sweep — one of them in
        // `src/main`, in `PreferencesManager.setSelectedLanguage`'s KDoc, attributing the wifi
        // asymmetry to the owner BY NAME and BY DATE, which is precisely the reversal mechanism
        // `PreviewAutoFetch`'s KDoc quotes the owner in order to stop. The prose had no
        // instrument and the code did. This is the prose's.
        //
        // Comment-INCLUSIVE, unlike every other pin in this file — see [mainSourcesMentioning].
        // These four phrases cannot appear in Kotlin code, so a comment-blind version of this test
        // would be vacuous forever, which is worse than no test: it would read as coverage.
        //
        // What is NOT forbidden: Play's own dialog. `STATUS_WAITING_FOR_WIFI` is Play's wait, not
        // ours, and two KDocs describe it as *"a wait for wifi"* — still true, and deliberately
        // not matched by the needle, which is the third-person present of OUR deleted rule.
        //
        // **WHERE THIS INSTRUMENT IS WEAKER THAN ITS NEIGHBOUR, stated so nobody reads it as
        // stronger.** The walk above asserts the absence of CODE, so adding the read changes
        // `compileDebugKotlin`'s output and this task re-runs on its own (its own KDoc's C8
        // argument). This one asserts the absence of PROSE, and a comment-only edit compiles to a
        // byte-identical class — which is the whole reason `sourcePinnedInputs` exists in
        // `app/build.gradle.kts`. Of the ruling's seven prose homes, four are on that list
        // (`PreferencesManager.kt` — B1's own site — plus `PreviewAutoFetch.kt`,
        // `PreviewAutoFetchController.kt` and `ConnectivityMonitor.kt`) and three are not
        // (`PreviewWork.kt`, `PreviewPicks.kt`, and the two screens). So a comment-only
        // reintroduction in one of those three can ride a build where nothing else changed. Any
        // commit that also touches code re-runs this; adding the two small ones to that list
        // would close the rest, and is left as a controller decision because this fix round's
        // list was closed.

        for (needle in listOf(
            "waits for wifi",
            "waits for Wi-Fi",
            "waits for Wi Fi",
            "spends a metered connection",
        )) {
            val found = mainSourcesMentioning(needle)
            assertEquals(
                "<<$needle>> states the rule the owner overruled — *\"Yes. I wanted to silently " +
                    "download on cellular and Wi Fi\"* — in the present tense. Both starters " +
                    "download on any connection; what the unasked path alone waits for is a " +
                    "network that WORKS and the 24 h back-off. Found: $found",
                emptyList<String>(),
                found,
            )
        }
    }

    @Test fun theValidatedReadingHasOneHomeAndOneSpellingAndSaysWhyItSurvived() {
        // The half that DELIBERATELY SURVIVES, and the line that stops the next reader concluding
        // the ruling was applied by halves: a captive-portal wifi — a hotel, an airport, a coffee
        // shop — reports connected while every request fails, so an unasked fetch started there
        // fails and the 24 h back-off then withholds the model for a DAY after the user reaches a
        // network that would have worked.
        val scope = scopeOf(connectivity, "fun hasValidatedNetwork()", "\n}")
        assertEquals(
            "the platform's own VALIDATED capability, read once",
            1, liveLineCount(scope, "NetworkCapabilities.NET_CAPABILITY_VALIDATED"),
        )
        assertEquals(
            "and INTERNET with it, so 'a network that works' is not merely 'a network'",
            1, liveLineCount(scope, "NetworkCapabilities.NET_CAPABILITY_INTERNET"),
        )
        assertEquals(
            "no active network / no capabilities => no working network",
            1, liveLineCount(scope, "?: return false"),
        )
        assertEquals(
            "and a throwing ConnectivityManager reads the same way",
            1, liveLineCount(scope, ".getOrDefault(false)"),
        )
        assertTrue(
            "the class KDoc must record that the metered half was removed by owner ruling and " +
                "that this half survives on purpose",
            connectivity.contains("silently download on cellular and Wi Fi") &&
                connectivity.contains("metered went, validated stayed"),
        )
        assertEquals(
            "one predicate, not two: `isUnmetered` is gone rather than renamed beside it, " +
                "because two spellings of one platform question is one too many",
            0, liveLineCount(connectivity, "fun isUnmetered("),
        )
    }
}
