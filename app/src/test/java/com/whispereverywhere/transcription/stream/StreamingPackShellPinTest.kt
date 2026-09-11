package com.whispereverywhere.transcription.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The previewer's fetch shell, pinned as SOURCE — `NpuDiagTest`'s instrument for the third
 * `AssetPackManager`-bound object in this app (4.4.0, the 2026-09-10 amendment).
 *
 * [StreamingPackController] cannot be constructed by a JVM test: `AssetPackManagerFactory`,
 * `AssetPackStateUpdateListener` and `Task` all need Play on a device. Every DECISION it makes is
 * pure and executed by `StreamingPackInstallTest` ([StreamingPackInstall.fetchInFlight],
 * [StreamingPackInstall.playRefusedThisInstall]) or by `NpuPackFetchTest`
 * (`NpuPackFetch.advance` and the whole mapping). What is left is WHICH call sits where and in
 * WHICH ORDER — and those are exactly the facts that, if they moved, would be invisible to every
 * behavioural test and fatal on a device:
 *
 *  - a fetch that is never requested (the pack never arrives),
 *  - a Failed that never reaches the latch (the sideload fallback stays unreachable forever),
 *  - an `Installed` published before the bytes landed (the row lies and the recognizer opens
 *    nothing),
 *  - a second `AssetPackManager` instance (a listener registered on a throwaway narrates
 *    nothing).
 *
 * Every file this class reads is in the test task's `sourcePinnedInputs` in
 * `app/build.gradle.kts`; without that entry an edit confined to the shell could leave
 * `:app:testDebugUnitTest` UP-TO-DATE and these pins would pass against stale evidence.
 */
class StreamingPackShellPinTest {

    // ------------------------------------------------------------------ source helpers
    // (NpuDiagTest's own, verbatim: the same walk, the same LF normalisation, the same
    // comment-blind live-line rule — a pin that a commented-out line can satisfy is not a pin.)

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
        source("src/main/java/com/whispereverywhere/transcription/stream/StreamingPackController.kt")
    }

    private val manager: String by lazy {
        source("src/main/java/com/whispereverywhere/transcription/stream/StreamingPackManager.kt")
    }

    // ------------------------------------------------------------------ the fetch itself

    /**
     * THE blocker this shell exists for: on a Play install, `state()` answers `PackFetchable` and
     * only a `fetch` can move it on. One request, on the ONE manager instance the listener is
     * registered on, and the registration comes FIRST — a fetch issued before the listener exists
     * can complete unobserved, and the row would sit on "Pending" over a delivered pack.
     */
    @Test
    fun thePackIsRequestedOnceThroughTheSharedManagerAndTheListenerIsRegisteredFirst() {
        assertEquals(
            "exactly one fetch request in the shell — a second would ask Play twice for the " +
                "same 73 MB",
            1,
            liveLineCount(controller, ".fetch(listOf("),
        )
        assertEquals(
            "and the manager comes from the SHARED helper, so the app has one spelling of " +
                "'get the AssetPackManager' (PlayPacks.managerFor)",
            1,
            liveLineCount(controller, "PlayPacks.managerFor("),
        )
        assertEquals(
            "one listener registration, memoised with the instance beside it",
            1,
            liveLineCount(controller, "registerListener("),
        )
        val register = offsetOfLive(controller, "registerListener(")
        val fetch = offsetOfLive(controller, ".fetch(listOf(")
        assertTrue(
            "registerListener ($register) must precede fetch ($fetch): a fetch issued before " +
                "the listener exists can complete unobserved",
            register in 0 until fetch,
        )
    }

    /**
     * EVERY `AssetPackState` goes through the one pure mapping, and the progress throttle is
     * consulted at exactly one place — the shell interprets no status and invents no log rule.
     */
    @Test
    fun theShellInterpretsNoStatusOfItsOwn() {
        assertEquals(
            "one call to the pure mapping — every status Play reports lands in NpuPackFetch",
            1,
            liveLineCount(controller, "NpuPackFetch.advance("),
        )
        assertEquals(
            "the throttle decision has exactly ONE call site; a second would be a second " +
                "opinion about when a line may print",
            1,
            liveLineCount(controller, "NpuPackFetch.shouldLogProgress("),
        )
        assertEquals(
            "the single-flight predicate is the PURE one, not a when() re-written here",
            1,
            liveLineCount(controller, "StreamingPackInstall.fetchInFlight("),
        )
    }

    /**
     * THE LATCH INVARIANT. `playCanDeliver()` is `!BuildConfig.DEBUG && !playRefused`, and
     * `playRefused` is only ever set by [StreamingPackManager.notePlayFailure]. If a Failed can
     * reach the UI without passing through it, `Downloadable` is unreachable on every non-debug
     * build — the sideload fallback the amendment kept alive would be dead code, offering a fetch
     * Play has already refused, forever.
     *
     * Both failure routes latch: the listener's FAILED status, and a `fetch` Task that fails
     * before any `AssetPackState` exists — which is precisely how a sideloaded install fails.
     */
    @Test
    fun bothPlayFailureRoutesReachTheFallbackLatch() {
        assertEquals(
            "exactly one latch site, one spelling — the manager owns the flag",
            1,
            liveLineCount(controller, "notePlayFailure("),
        )
        val sites =
            liveLineCount(controller, "latchRefusal(") - liveLineCount(controller, "fun latchRefusal(")
        assertEquals(
            "two latch CALLS: the listener's Failed arm and the fetch Task's own failure (the " +
                "sideload's route). Dropping either strands that install on an offer Play will " +
                "never serve.",
            2,
            sites,
        )
        assertEquals(
            "the listener latches on the mapped Failed, not on a status it re-reads itself",
            1,
            liveLineCount(controller, "if (next is NpuPackFetch.FetchState.Failed) latchRefusal("),
        )
        // The latch is keyed by Play's ERROR CODE end to end: a shell that handed over its own
        // words would silently never match the NPU family's sentences.
        assertEquals(
            "the latch takes the error code, never a sentence",
            1,
            liveLineCount(controller, "private fun latchRefusal(errorCode: Int)"),
        )
    }

    /**
     * INSTALL BEFORE `Installed`, and the give-back is NOT this object's. `installFromPack`
     * verifies (exact sizes, then sha256), lands marker-last, and hands the pack back to Play
     * itself — strictly after the land. A `removePack` here would be a second give-back with no
     * ordering guarantee at all, and an `Installed` published above the install would put "73 MB
     * installed" on a row whose bytes are still Play's.
     */
    @Test
    fun theDeliveredPackIsLandedBeforeAnythingCallsItInstalled() {
        assertEquals(
            "exactly one install site, and it is the manager's one-verification entry point",
            1,
            liveLineCount(controller, ".installFromPack("),
        )
        val install = offsetOfLive(controller, ".installFromPack(")
        val installed =
            offsetOfLive(controller, "publish(packName, NpuPackFetch.FetchState.Installed)")
        assertTrue(
            "installFromPack ($install) must precede the Installed publication ($installed)",
            install in 0 until installed,
        )
        assertEquals(
            "no removePack in this shell: the manager gives the pack back strictly after the " +
                "land, and one give-back is the whole invariant",
            0,
            liveLineCount(controller, "removePack("),
        )
        assertEquals(
            "and the install is launched by the DELIVERED mapping, never by Play's own success " +
                "status read here (COMPLETED means delivered, not installed)",
            1,
            liveLineCount(controller, "if (next is NpuPackFetch.FetchState.Verifying) beginInstall("),
        )
    }

    /**
     * THE STORAGE GATE RUNS FIRST, ON BOTH ROUTES, ON ONE RULE (spec §6: "free-space gate
     * first", the `TtsModelManager.kt:66-76` shape).
     *
     * The amendment made the PACK route the primary one on the shipping build, so an ungated
     * pack route is the ungated route every real user takes: 72,654,782 B copied into `filesDir`
     * on a device that may have less, failing partway with Play's own 73 MB still on it. The gate
     * is not a `StatFs` call this test can execute — but WHERE it sits, and that both routes ask
     * the same pure function for the number rather than re-deriving 1.1 ×, is pinnable and is
     * exactly what would rot.
     */
    @Test
    fun theStorageGateRunsBeforeEitherRouteWritesAByte() {
        val fromPack = scopeOf(manager, "suspend fun installFromPack(", "suspend fun download(")
        assertEquals(
            "the pack route gates exactly once",
            1,
            liveLineCount(fromPack, "StreamingPackInstall.hasRoomFor("),
        )
        val gate = offsetOfLive(fromPack, "StreamingPackInstall.hasRoomFor(")
        val verify = offsetOfLive(fromPack, "StreamingPackInstall.verify(")
        val install = offsetOfLive(fromPack, "StreamingPackInstall.install(")
        assertTrue(
            "gate ($gate) -> verify ($verify) -> install ($install): the cheapest refusal " +
                "first, and nothing is read or written before the volume is known to fit",
            gate in 0 until verify && verify in 0 until install,
        )
        assertEquals(
            "both routes gate — the pack route's one line, and the download's one line asking " +
                "for both volumes",
            2,
            liveLineCount(manager, "StreamingPackInstall.hasRoomFor("),
        )
        assertEquals(
            "and NEITHER route re-derives the headroom: 1.1 x lives in one function " +
                "(StreamingPackInstall.requiredFreeBytes), or the two routes can disagree about " +
                "what 'enough space' means",
            0,
            liveLineCount(manager, "* 1.1"),
        )
    }

    /**
     * ONE CANCEL, ONE MEANING — on the one route where 4.4.1's did not (4.5.0 Task 1, review r1's
     * H1).
     *
     * `fetchOne` used to set `keepRow = true` on a `CancellationException`, so the X stopped the
     * poll loop and left `DownloadManager` transferring the rest of the 73 MB — while `delete()`'s
     * `removeStaleDownloads` removed that very row. The app both stopped and did not stop the same
     * transfer, depending on which control the user found. `PreviewRoute.DIRECT_DOWNLOAD
     * .stopsBeforeTheCopy` is now `true`, and these are the two lines that keep that promise: the
     * row goes on every exit, and the staged files go with it.
     *
     * Neither is reachable from a JVM test — `DownloadManager` and `getExternalFilesDir` are both
     * Android — and both are one-token edits that compile clean and change nothing any other test
     * observes, which is exactly the shape this file exists for.
     */
    @Test
    fun cancellingTheFallbackDownloadReallyStopsItAndSweepsWhatItStaged() {
        val fetchOne = scopeOf(manager, "private suspend fun fetchOne(", "private fun stagingDir(")
        assertEquals(
            "no `keepRow` survives anywhere in the manager: it is the whole of H1",
            0,
            liveLineCount(manager, "keepRow"),
        )
        assertEquals(
            "one removal, and it is unconditional",
            1,
            liveLineCount(fetchOne, "dm.remove(id)"),
        )
        val enqueued = offsetOfLive(fetchOne, "dm.enqueue(request)")
        val finallyArm = offsetOfLive(fetchOne, "} finally {")
        val removed = offsetOfLive(fetchOne, "dm.remove(id)")
        assertTrue("the row must be enqueued first", enqueued in 0 until finallyArm)
        assertTrue(
            "and removed from the FINALLY, so no exit — a cancellation least of all — can skip " +
                "it and leave the transfer running behind a UI that says it stopped",
            finallyArm in 0 until removed,
        )
        // The staged files are OURS, unlike Play's delivered pack. fetchOne's removal takes the
        // in-flight file with the row; the ones that already landed would otherwise park up to
        // 73 MB in the external staging dir until the next attempt or a delete.
        val download = scopeOf(manager, "suspend fun download(", "private fun fail(")
        assertEquals(
            "the download sweeps its own staging dir when it is cancelled",
            1,
            liveLineCount(download, "catch (cancelled: CancellationException)"),
        )
        val caught = offsetOfLive(download, "catch (cancelled: CancellationException)")
        val swept = offsetOfLive(download, "staging.deleteRecursively()")
        assertTrue("the sweep must exist", swept >= 0)
        assertTrue(
            "and the cancellation is RETHROWN: a user who cancelled has not hit a bad network, " +
                "and the actuator's back-off must not record one",
            liveLineCount(download, "throw cancelled") == 1,
        )
        assertTrue("the catch arm is in the download", caught >= 0)
    }

    /**
     * THE CARD SPEAKS THE PREVIEWER'S WORDS, not the NPU model chooser's.
     *
     * `NpuPackFetch.FetchState.Failed`'s own contract is *"[reason] is user-facing copy, rendered
     * verbatim by the card"*, and that is exactly how both NPU surfaces treat it. Six of that
     * table's codes name a control the previewer does not have — the four sideload codes render
     * one sentence ending *"Use 'Import model pair…' below instead"* (the chooser's SAF importer
     * for whisper **ggml pairs**, unreachable from a Settings previewer row and unable to read
     * these four ONNX files), APP_UNAVAILABLE and PACK_UNAVAILABLE append the same phrase, and
     * INSUFFICIENT_STORAGE offers to fetch *"the model pair"*.
     *
     * This is the pin that stops it coming back: whoever renders `state` renders `reason`
     * verbatim, so `reason` must never be that table's. The words are
     * [StreamingPackInstall.fetchRefusal]'s (pure, total over Int, banned-word scanned by
     * `StreamingPackInstallTest`); the NPU table survives here as the LATCH's classifier only,
     * applied once, inside the manager.
     */
    @Test
    fun everyRefusalThisShellPublishesCarriesThePreviewersOwnWords() {
        assertEquals(
            "zero NpuPackFetch.failureReason call sites in the shell: that table is the NPU " +
                "chooser's copy, and its sideload family tells the user to tap 'Import model " +
                "pair…', which does not exist on the previewer's row",
            0,
            liveLineCount(controller, "NpuPackFetch.failureReason("),
        )
        assertEquals(
            "the listener's failures are re-told by the previewer's own pure function, which " +
                "sees the status too (an UNKNOWN status carries no error code)",
            1,
            liveLineCount(controller, "StreamingPackInstall.deliveryRefusal("),
        )
        assertEquals(
            "and so is the fetch Task's own failure — the sideload's route, and the FIRST " +
                "refusal a release sideload reads",
            1,
            liveLineCount(controller, "StreamingPackInstall.fetchRefusal("),
        )
        assertEquals(
            "the classifier that keys the latch stays the NPU family's own, applied by the " +
                "manager (one site) and never rendered",
            1,
            liveLineCount(manager, "NpuPackFetch.failureReason("),
        )
        // Calling the re-teller is not enough: the RE-TOLD state has to be the one published.
        // `publish(packName, next)` would call the previewer's function and throw its answer
        // away, leaving the NPU words on the card with every other pin still green.
        assertEquals(
            "the publish funnel takes the re-told state",
            1,
            liveLineCount(controller, "publish(packName, shown)"),
        )
        assertEquals(
            "and never the raw mapping, whose Failed carries the NPU table's sentence",
            0,
            liveLineCount(controller, "publish(packName, next)"),
        )
    }

    /**
     * The previewer narrates itself and borrows no NPU line. `NpuDiagTest` pins the `pack:`
     * family at exactly one emitter each inside `NpuPackController.kt`; a `NpuDiag.packLine` from
     * here would put a previewer fetch under a tier's name in the run-book — and the previewer
     * has no tier identity at all (spec §6).
     */
    @Test
    fun theShellNarratesItselfUnderOneGreppablePrefixAndBorrowsNoNpuLine() {
        assertEquals(
            "zero NpuDiag emissions here — the NPU pack family stays the NPU shell's",
            0,
            liveLineCount(controller, "NpuDiag"),
        )
        assertEquals(
            "one emission site, the publish funnel's own",
            1,
            liveLineCount(controller, "Log.i("),
        )
        assertEquals(
            "and the prefix is ONE contiguous literal, so a support reply can say 'search your " +
                "log for stream-pack:'",
            1,
            liveLineCount(controller, "\"stream-pack: pack="),
        )
        // Numbers, codes and a pack name only (the SegmentTiming discipline): the line carries no
        // field that could hold a transcript.
        assertTrue(
            "the line is name + status + two byte counts",
            controller.contains("\"stream-pack: pack=\$packName status=\$word soFar=\$soFar total=\$total\""),
        )
    }
}
