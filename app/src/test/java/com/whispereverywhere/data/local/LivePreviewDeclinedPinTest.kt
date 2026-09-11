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

    // ------------------------------------------------------------------ the flag

    @Test fun theUserSaidNoFlagDefaultsToFalse() {
        assertEquals(
            "shipped true this declines the feature for everyone on first launch and the " +
                "auto-fetch silently never happens",
            1, liveLineCount(prefs, "prefs.getBoolean(KEY_LIVE_PREVIEW_DECLINED, false)"),
        )
        assertEquals(
            "and there is exactly one reader, so no second read can carry a different default",
            1, liveLineCount(prefs, "getBoolean(KEY_LIVE_PREVIEW_DECLINED"),
        )
        assertEquals(
            "and exactly one writer",
            1, liveLineCount(prefs, "putBoolean(KEY_LIVE_PREVIEW_DECLINED, value)"),
        )
    }

    @Test fun theFlagsStorageKeyIsSpelledExactlyOnce() {
        // A renamed key forgets every user's recorded decision, and the first thing that happens
        // after it is forgotten is a 73 MB fetch of a model somebody deleted on purpose.
        assertEquals(
            1, liveLineCount(prefs, "KEY_LIVE_PREVIEW_DECLINED = \"live_preview_declined\""),
        )
        assertEquals(
            1,
            liveLineCount(
                prefs,
                "KEY_LIVE_PREVIEW_AUTOFETCH_FAILED_AT = \"live_preview_autofetch_failed_at\"",
            ),
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
        assertEquals(1, liveLineCount(prefs, "var livePreviewDeclined: Boolean"))
        assertEquals(1, liveLineCount(prefs, "var livePreviewAutoFetchFailedAt: Long"))
    }

    // ------------------------------------------------------------------ a delete is a decision

    @Test fun theDeleteRecordsTheDecisionBeforeItRemovesTheBytes() {
        assertEquals(
            "the brief's own rule: a DELETE is a decision and must not be undone by an " +
                "auto-fetch (AF3)",
            1, liveLineCount(rows, "livePreviewDeclined = true"),
        )
        val decision = offsetOfLive(rows, "livePreviewDeclined = true")
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

    @Test fun theSettingsRowsOwnBehaviourIsOtherwiseUntouched() {
        // "The Settings rows STAY exactly as they are — they are the manual path and the owner
        // likes them." The one line above is the whole of 4.4.1's edit to them: the row must not
        // grow an auto-fetch, a card, or a second opinion about metering.
        for (needle in listOf(
            "PreviewAutoFetch.decide(",
            "PreviewAutoFetch.card(",
            "PreviewAutoFetchController.",
            "isUnmetered(",
        )) {
            assertEquals(
                "<<$needle>> belongs to the Home card's hook, not to the manual path",
                0, liveLineCount(settings, needle),
            )
        }
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
