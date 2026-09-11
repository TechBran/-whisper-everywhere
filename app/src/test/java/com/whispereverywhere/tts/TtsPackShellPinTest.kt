package com.whispereverywhere.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The voice's fetch shell and its one Settings action, pinned as SOURCE — `NpuDiagTest`'s
 * instrument for the FOURTH `AssetPackManager`-bound object in this app (4.4.0, the 2026-09-10
 * amendment, Task 2b).
 *
 * [TtsPackController] cannot be constructed by a JVM test: `AssetPackManagerFactory`,
 * `AssetPackStateUpdateListener` and `Task` all need Play on a device. Every DECISION it makes is
 * pure and executed elsewhere — the single-flight predicate is
 * `StreamingPackInstall.fetchInFlight`, the fallback classifier is
 * `StreamingPackInstall.playRefusedThisInstall` (reached through
 * [TtsModelManager.notePlayFailure]), the status mapping is `NpuPackFetch.advance`, the words are
 * [TtsModelManager.packRefusal]'s — and what remains here is WHICH call sits where, and in WHICH
 * ORDER. Those are exactly the facts that, if they moved, would be invisible to every behavioural
 * test and fatal on a device:
 *
 *  - a fetch that is never requested (the 350 MB never arrives, and the row's Fetch route is a
 *    dead button),
 *  - a Failed that never reaches the latch (the download fallback stays unreachable forever, on
 *    every sideload),
 *  - an `Installed` published before the archive was extracted (the row lies and `TtsEngine`
 *    opens nothing),
 *  - a second `AssetPackManager` instance (a listener registered on a throwaway narrates
 *    nothing),
 *  - and, on the Settings side, a per-composition `TtsModelManager`: the fallback latch and the
 *    fetch shell would then be looking at different objects, so a refusal Play named would never
 *    reach the row that has to offer the download instead.
 *
 * Every file this class reads is in the test task's `sourcePinnedInputs` in `app/build.gradle.kts`;
 * without those entries an edit confined to either could leave `:app:testDebugUnitTest` UP-TO-DATE
 * and these pins would pass against the files as they used to be.
 */
class TtsPackShellPinTest {

    // ------------------------------------------------------------------ source helpers
    // (StreamingPackShellPinTest's own, verbatim: the same walk, the same LF normalisation, the
    // same comment-blind live-line rule — a pin a commented-out line can satisfy is not a pin.)

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

    private val controller: String by lazy {
        source("src/main/java/com/whispereverywhere/tts/TtsPackController.kt")
    }

    private val settings: String by lazy {
        source("src/main/java/com/whispereverywhere/ui/screens/SettingsScreen.kt")
    }

    // ------------------------------------------------------------------ the fetch

    @Test
    fun thePackIsRequestedOnceThroughTheSharedManagerAndTheListenerIsRegisteredFirst() {
        assertEquals(
            "the manager comes from the ONE shared helper — a second " +
                "AssetPackManagerFactory.getInstance in this app is a second instance, and a " +
                "listener registered on a throwaway narrates nothing",
            1, liveLineCount(controller, "PlayPacks.managerFor("),
        )
        assertEquals(
            "and the factory is never reached directly from here",
            0, liveLineCount(controller, "AssetPackManagerFactory"),
        )
        assertEquals(1, liveLineCount(controller, "registerListener(listener)"))
        assertEquals(
            "exactly one fetch call site: two would ask Play for the same 350 MB twice",
            1, liveLineCount(controller, ".fetch(listOf("),
        )
        val register = offsetOfLive(controller, "registerListener(listener)")
        val fetch = offsetOfLive(controller, ".fetch(listOf(")
        assertTrue(
            "REGISTER BEFORE FETCH: a fetch issued before the listener exists can complete " +
                "against nobody, and the row would sit on Pending until the process died",
            register in 0 until fetch,
        )
    }

    @Test
    fun theShellInterpretsNoStatusOfItsOwn() {
        assertEquals(
            "every AssetPackState goes through the ONE pure mapping — no status is read twice " +
                "and none is interpreted here, which is what keeps the shell too boring to be " +
                "wrong",
            1, liveLineCount(controller, "NpuPackFetch.advance("),
        )
        assertEquals(
            "and no status is compared by hand anywhere in it (deliveryRefusal is handed the " +
                "number and decides in a tested pure function)",
            0, liveLineCount(controller, "packState.status() =="),
        )
    }

    @Test
    fun bothPlayFailureRoutesReachTheFallbackLatch() {
        assertEquals(
            "ONE latch site, and it is a named function so both routes cannot drift apart",
            1, liveLineCount(controller, "private fun latchRefusal(errorCode: Int)"),
        )
        assertEquals(
            "called twice: the listener's mapped Failed, and a fetch Task that fails before any " +
                "AssetPackState exists — which is EXACTLY how a sideloaded install fails, and " +
                "therefore the one failure that must reach the latch or the download fallback is " +
                "unreachable forever",
            2,
            liveLineCount(controller, "latchRefusal(") -
                liveLineCount(controller, "private fun latchRefusal("),
        )
        assertEquals(
            "and the latch is the manager's own, applied once",
            1, liveLineCount(controller, "notePlayFailure("),
        )
    }

    @Test
    fun theArchiveIsExtractedBeforeAnythingCallsItInstalled() {
        assertEquals(
            "COMPLETED means DELIVERED: the terminal Play success starts OUR verify + extract, " +
                "and the only publisher of Installed is the code after it returns",
            1, liveLineCount(controller, "installFromPack("),
        )
        val install = offsetOfLive(controller, "installFromPack(")
        val installed = offsetOfLive(controller, "NpuPackFetch.FetchState.Installed")
        assertTrue("the install must happen at all", install >= 0)
        assertTrue("Installed must be published at all", installed >= 0)
        assertTrue(
            "an Installed published before the extract landed is a row that lies while " +
                "TtsEngine opens nothing",
            install < installed,
        )
        assertEquals(
            "and the give-back stays the MANAGER's, strictly after the land — never a second " +
                "removePack here, which would delete Play's only copy mid-extract",
            0, liveLineCount(controller, "removePack("),
        )
    }

    @Test
    fun everyRefusalThisShellPublishesCarriesTheVoicesOwnWords() {
        assertEquals(
            "ZERO NpuPackFetch.failureReason call sites: that table's sentences send the user to " +
                "'Import model pair…', the NPU chooser's SAF importer for whisper ggml pairs, " +
                "which is not on the read-aloud row and cannot read a Kokoro archive. The " +
                "classifier still applies it — once, inside TtsModelManager.notePlayFailure.",
            0, liveLineCount(controller, "NpuPackFetch.failureReason("),
        )
        assertEquals(
            "the listener's Failed is re-told from the whole AssetPackState reading",
            1, liveLineCount(controller, "TtsModelManager.deliveryRefusal("),
        )
        assertEquals(
            "and the fetch Task's own failure by its error code",
            1, liveLineCount(controller, "TtsModelManager.packRefusal("),
        )
        // AND THE RE-TOLD STATE IS THE ONE PUBLISHED. Calling the re-teller and discarding its
        // answer would leave the NPU words on the card with every other pin green.
        val onState = scopeOf(controller, "private fun onPackState(", "private fun latchRefusal(")
        val retold = offsetOfLive(onState, "TtsModelManager.deliveryRefusal(")
        val published = offsetOfLive(onState, "publish(shown)")
        assertTrue("the re-telling must be in the listener's own path", retold >= 0)
        assertTrue(
            "and what reaches publish() must be the re-told state, not the mapping's own",
            retold in 0 until published,
        )
    }

    @Test
    fun theShellNarratesItselfUnderOneGreppablePrefixAndBorrowsNoOtherFeaturesLine() {
        assertEquals(
            "one Log site, throttled by NpuPackFetch.shouldLogProgress — a per-tick line would " +
                "bury the run-book's landmarks under hundreds of lines per fetch",
            1, liveLineCount(controller, "Log.i("),
        )
        assertEquals(1, liveLineCount(controller, "NpuPackFetch.shouldLogProgress("))
        assertEquals(
            "under its OWN greppable prefix: `voice-pack:`",
            1, liveLineCount(controller, "\"voice-pack: pack="),
        )
        assertEquals(
            "and it borrows neither the NPU flow's `pack:` lines nor the previewer's " +
                "`stream-pack:` ones — one prefix per flow is what makes a logcat readable",
            0, liveLineCount(controller, "stream-pack:"),
        )
        assertEquals(
            "the line carries numbers, a status word and a pack name — never transcript content",
            1, liveLineCount(controller, "status=\$word soFar=\$soFar total=\$total"),
        )
    }

    // ------------------------------------------------------------------ the Settings action

    @Test
    fun theVoiceRowsOneActionRoutesThroughTheOneTestedRouteFunction() {
        assertEquals(
            "the row does not decide where the voice comes from — installRoute does, and it is " +
                "total over StreamingPackState so a new state cannot fall through into the " +
                "third-party download",
            1, liveLineCount(settings, "TtsModelManager.installRoute("),
        )
        for (arm in listOf(
            "VoiceInstallRoute.FromPack ->",
            "VoiceInstallRoute.Fetch ->",
            "VoiceInstallRoute.Download ->",
            "VoiceInstallRoute.None ->",
        )) {
            assertEquals("the action answers $arm exactly once", 1, liveLineCount(settings, arm))
        }
        assertEquals(
            "the Fetch arm asks PLAY through the shell, from exactly one call site",
            1, liveLineCount(settings, "TtsPackController.start("),
        )
        assertEquals(
            "and Play's own confirmation dialog is re-shown, never re-asked by a dialog of ours",
            1, liveLineCount(settings, "TtsPackController.confirm("),
        )
        assertEquals(
            "the FromPack arm installs from the delivered pack, with no network at any point",
            1, liveLineCount(settings, "ttsManager.installFromPack("),
        )
        assertEquals(
            "and the download survives as exactly ONE call site, for the builds with no Play",
            1, liveLineCount(settings, "ttsManager.download("),
        )
        // AND THE RETRY TAKES THE SAME ROUTE AS THE OFFER. The refusal sentence for the sideload
        // family promises the direct download, and the fallback latch has already flipped by the
        // time it renders — so a retry that re-asked Play would fail again under a promise the
        // row was ready to keep. One lambda, two taps: the offer row's and the refusal row's.
        assertEquals(1, liveLineCount(settings, "val startVoiceInstall: () -> Unit"))
        assertEquals(
            "used by both rows, and by nothing else",
            2, liveLineCount(settings, "startVoiceInstall()"),
        )
    }

    @Test
    fun noTapOnTheInFlightRowCanStartASecondInstall() {
        // Fix round 1, B1. Three claims, and each of them is load-bearing on its own:
        //  1. the in-flight/refusal row asks the PURE predicate whether a tap does anything, and
        //     hands SettingsItem a null onClick when it does not (SettingsItem wraps itself in
        //     Modifier.clickable whenever onClick != null, so a lambda is a clickable row);
        //  2. the row's one action refuses while the fetch SHELL is working, read at tap time;
        //  3. the manager serializes the verify+extract+swap whatever the surfaces do.
        assertEquals(
            "the row does not decide tappability either — fetchLineTappable does, and it is " +
                "total over the fetch machine",
            1, liveLineCount(settings, ".fetchLineTappable(voiceFetch)"),
        )
        assertEquals(
            "and its answer is what reaches SettingsItem: a lambda here for an in-flight state " +
                "is a clickable row, and the tap lands on startVoiceInstall() with the route " +
                "still FromPack — a second installFromPack into the same temp dir",
            1, liveLineCount(settings, "onClick = if (voiceTappable) voiceRowTap else null"),
        )
        val action = scopeOf(settings, "val startVoiceInstall: () -> Unit", "var ttsSpeedState")
        assertEquals(
            "the one action refuses while the shell is working",
            1, liveLineCount(action, "TtsPackController.isBusy()) return@start"),
        )
        val refused = offsetOfLive(action, "TtsPackController.isBusy()) return@start")
        val routed = offsetOfLive(action, "when (voiceRoute) {")
        assertTrue("the refusal must be IN the action", refused >= 0)
        assertTrue(
            "and it must be a GUARD: read at tap time and answered before the route is acted " +
                "on, never a composition-time flag the row could be left dead by",
            refused in 0 until routed,
        )
        val manager = source("src/main/java/com/whispereverywhere/tts/TtsModelManager.kt")
        assertEquals(
            "ONE lock, and it is the manager's own instance state — the manager is " +
                "process-scoped, so one monitor covers the row, the shell's own install after a " +
                "delivery, and onboarding's auto-setup",
            1, liveLineCount(manager, "private val installLock = Any()"),
        )
        assertEquals(
            "held around the WHOLE verify+extract+swap, inside the one function both routes " +
                "call — around its call sites instead and the next call site added is unguarded",
            1, liveLineCount(manager, "synchronized(installLock)"),
        )
        val guarded = offsetOfLive(manager, "synchronized(installLock)")
        val extract = offsetOfLive(manager, "extractTarBz2(tar, tmp, stripLeadingComponent = true)")
        val swap = offsetOfLive(manager, "if (!tmp.renameTo(final))")
        assertTrue("the extract must be inside the lock", guarded in 0 until extract)
        assertTrue(
            "and so must the swap: extractTarBz2 opens by deleting and recreating the temp " +
                "tree, so two of these interleaved land a truncated model.onnx under a valid " +
                ".installed marker — an install isInstalled() calls good and TtsEngine cannot open",
            guarded in 0 until swap,
        )
    }

    @Test
    fun theRowAndTheFetchShellShareOneProcessScopedManager() {
        assertEquals(
            "the manager is the APPLICATION's. A per-composition TtsModelManager(context) would " +
                "hold its own playRefused latch, so a refusal Play named on the shell's instance " +
                "would never reach the row that has to offer the download instead — and the row " +
                "would keep asking Play forever.",
            1, liveLineCount(settings, "val ttsManager = app.ttsModelManager"),
        )
        assertEquals(
            "and nothing in Settings constructs one of its own",
            0, liveLineCount(settings, "TtsModelManager(context)"),
        )
        assertEquals(
            "the shell reads the same one off the Application, never a new instance",
            0, liveLineCount(controller, "TtsModelManager("),
        )
        assertEquals(1, liveLineCount(controller, ".ttsModelManager"))
    }
}
