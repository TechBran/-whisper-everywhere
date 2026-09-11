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
 *  - **the metered reading has ONE home and ONE default.** The CONTROLLER RULING hangs on this
 *    single predicate: no active network reads as METERED, so a phone with no connection shows
 *    the card instead of starting a transfer that would fail.
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

    @Test fun theSeenLiveWordsFlagDefaultsToFalseAndIsWrittenByTheGatesOwnCallSite() {
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
        // The write lives with the fact it records: the previewer gate's own call site is the one
        // place that knows an arm happened, and it is guarded by the gate's answer rather than by
        // a re-derivation of it.
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
            "isUnmetered(",
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
            "declared a PICK, never an unasked top-up: the once-per-launch latch is the auto " +
                "path's alone, and a tap is consent that may be repeated",
            1, liveLineCount(settings, "auto = false"),
        )
        assertEquals(
            "and the row starts nothing else and cancels nothing — the X is the card's gesture",
            0,
            liveLineCount(settings, "PreviewAutoFetchController.cancel(") +
                liveLineCount(settings, "PreviewAutoFetchController.busy("),
        )
    }

    // ------------------------------------------------------------------ the metered reading

    @Test fun theMeteredReadingHasOneHomeAndOneSpelling() {
        val scope = scopeOf(connectivity, "fun isUnmetered()", "\n}")
        assertEquals(
            "the platform's own NOT_METERED capability, read once",
            1, liveLineCount(scope, "NetworkCapabilities.NET_CAPABILITY_NOT_METERED"),
        )
        assertEquals(
            "one spelling of the question in the whole app: the other API " +
                "(isActiveNetworkMetered) inverts the sense, and two readings of a consent " +
                "predicate is one reading too many",
            0, liveLineCount(connectivity, "isActiveNetworkMetered"),
        )
    }

    @Test fun theMeteredReadingAlsoRequiresAValidatedNetwork() {
        // CONTROLLER RULING 2026-09-11, CHANGE 1 (the auto-fetch round's C6). A captive-portal
        // wifi — a hotel, an airport, a coffee shop — reports NOT_METERED while every request
        // fails. Without VALIDATED the auto-fetch starts there, fails, and the 24 h back-off then
        // withholds the model for a DAY after the user reaches a network that would have worked.
        // One `&&` turns a day-long silent failure into a correct wait.
        val scope = scopeOf(connectivity, "fun isUnmetered()", "\n}")
        assertEquals(
            "the same capability hasValidatedNetwork above it requires, for the same reason",
            1, liveLineCount(scope, "NetworkCapabilities.NET_CAPABILITY_VALIDATED"),
        )
        assertEquals(
            "and it is an AND with the metering read, not a second branch that could answer " +
                "unmetered on its own",
            1, liveLineCount(scope, "caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) &&"),
        )
    }

    @Test fun noNetworkAtAllReadsAsMeteredSoNothingStartsOnAPhoneThatCannotFinishIt() {
        val scope = scopeOf(connectivity, "fun isUnmetered()", "\n}")
        assertEquals(
            "no active network / no capabilities => not unmetered",
            1, liveLineCount(scope, "?: return false"),
        )
        assertEquals(
            "and a throwing ConnectivityManager reads the same way — the monitor's own " +
                "hasValidatedNetwork shape, matched rather than invented",
            1, liveLineCount(scope, ".getOrDefault(false)"),
        )
    }
}
