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
            offsetOfLive(controller, "publish(packName, pack.language, NpuPackFetch.FetchState.Installed)")
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
     * .stopsBeforeTheCopy` is now `true`, and these are the lines that keep that promise: the row
     * goes on every exit, and `download`'s own sweep takes the staged files.
     *
     * **WHICH OF THOSE TWO FACTS SWEEPS THE BYTES IS PINNED HERE, because this file asserted the
     * wrong one** (4.5.0 pass 2, Fix 2 — review r3's R3-B1). The removal's KDoc claimed *"the row
     * (and with it the partial file) now goes on EVERY exit"*, and this test's own comment
     * repeated it. `DownloadManager.remove` is documented that way, and that is exactly what made
     * it dangerous: `fetchOne` set the DownloadManager destination to the path `dest` names, so
     * the same-path guard skipped the move and the unconditional removal deleted the COMPLETE
     * file — every non-Play install then failed `verify` as `Missing` after spending 73 MB.
     * Invisible to every device session, because a Play install never takes this route.
     *
     * The fix is the `.part` sibling below: the bytes are out of DownloadManager's hands before
     * the `finally` runs, the removal keeps its H1 meaning with no effect on a landed file, and
     * the partial-byte sweep is `download`'s `staging.deleteRecursively()` — which is what this
     * test now asserts, in the CANCELLATION arm specifically.
     *
     * None of it is reachable from a JVM test — `DownloadManager` and `getExternalFilesDir` are
     * both Android — and all of it is one-token edits that compile clean and change nothing any
     * other test observes, which is exactly the shape this file exists for.
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
        // THE STAGED FILES ARE SWEPT BY `download`, NOT BY THE ROW REMOVAL (Fix 2). They are
        // OURS, unlike Play's delivered pack, and the ones that already landed would otherwise
        // park up to 73 MB in the external staging dir until the next attempt or a delete. The
        // in-flight `.part` is in that same dir, so one sweep answers for all of them — and the
        // sweep asserted here is the CANCELLATION arm's, not the success path's (review r3's
        // nit 5: `offsetOfLive` found the success one and then only asserted `>= 0`).
        val download = scopeOf(manager, "suspend fun download(", "private fun fail(")
        assertEquals(
            "the download sweeps its own staging dir when it is cancelled",
            1,
            liveLineCount(download, "catch (cancelled: CancellationException)"),
        )
        val cancelArm = scopeOf(download, "catch (cancelled: CancellationException)", "throw cancelled")
        assertEquals(
            "and the sweep is INSIDE that arm, so deleting it cannot leave this pin green",
            1,
            liveLineCount(cancelArm, "staging.deleteRecursively()"),
        )
        assertTrue(
            "and the cancellation is RETHROWN: a user who cancelled has not hit a bad network, " +
                "and the actuator's back-off must not record one",
            liveLineCount(download, "throw cancelled") == 1,
        )
    }

    /**
     * THE DOWNLOADED FILE IS OUT OF `DownloadManager`'S HANDS BEFORE THE ROW IS REMOVED (4.5.0
     * pass 2, Fix 2 — review r3's R3-B1).
     *
     * The defect this pins shut, in order: `fetchOne` asked `DownloadManager` to write straight to
     * `stagingDir(pack)/<name>`, which is the absolute path `dest` names; the `STATUS_SUCCESSFUL`
     * branch's `if (src.absolutePath != dest.absolutePath)` was therefore FALSE and skipped the
     * move; the unconditional `finally { dm.remove(id) }` then deleted the complete file, because
     * that is what `remove` documents itself as doing; and `download`'s
     * `StreamingPackInstall.verify(staging, pack)` — which runs AFTER every `fetchOne` has
     * returned — answered `Missing`, failed the install and wrote the 24 h back-off stamp, having
     * spent 73 MB. Pre-existing in 4.4.1 (`keepRow` was false on success there too) and invisible
     * to every device session, because a Play install never takes this route: only a debug build
     * or a sideload does.
     *
     * The inherited shape does not have the bug — `TtsModelManager` consumes the download INSIDE
     * the success branch, before its own `finally` — and this is the previewer's version of that:
     * a sibling `.part` destination, so no guard can ever make the move conditional again.
     */
    @Test
    fun theFallbackDownloadLandsOnASiblingAndIsMovedIntoPlaceBeforeTheRowGoes() {
        val fetchOne = scopeOf(manager, "private suspend fun fetchOne(", "private fun stagingDir(")
        assertEquals(
            "the suffix has one spelling, in the companion",
            1,
            liveLineCount(manager, "const val PART_SUFFIX"),
        )
        assertEquals(
            "the destination handed to DownloadManager is the `.part` sibling, never `dest`",
            1,
            liveLineCount(fetchOne, "val part = File(dest.parentFile, dest.name + PART_SUFFIX)"),
        )
        assertEquals(
            "and it is that file's name in the request, so the two cannot drift apart",
            1,
            liveLineCount(fetchOne, "\${pack.dirName}/\${part.name}"),
        )
        assertEquals(
            "a `.part` left by a killed process is cleared first: DownloadManager refuses a " +
                "destination that already exists",
            1,
            liveLineCount(fetchOne, "if (part.exists()) part.delete()"),
        )
        // THE MOVE IS UNCONDITIONAL. The same-path guard is what skipped it, and a guard whose
        // false branch is the defect is worse than no guard: with a `.part` destination the paths
        // cannot be equal, so the condition only gave a future reader something to satisfy.
        assertEquals(
            "no same-path guard survives — that condition WAS the bug",
            0,
            liveLineCount(fetchOne, "src.absolutePath != dest.absolutePath"),
        )
        assertEquals(
            "one rename attempt, with the copy+delete fallback for a cross-filesystem move",
            1,
            liveLineCount(fetchOne, "if (!src.renameTo(dest))"),
        )
        // ...and the ORDER is the whole of it: moved inside the success branch, removed in the
        // `finally` afterwards.
        val moved = offsetOfLive(fetchOne, "if (!src.renameTo(dest))")
        val finallyArm = offsetOfLive(fetchOne, "} finally {")
        val removed = offsetOfLive(fetchOne, "dm.remove(id)")
        assertTrue("the move must exist", moved >= 0)
        assertTrue(
            "and happen BEFORE the row is removed, or `remove` takes the complete file with it",
            moved in 0 until finallyArm && finallyArm in 0 until removed,
        )
    }

    /**
     * **EVERY WAY OUT OF `download` SWEEPS THE STAGING DIR, BECAUSE SINCE FIX 2 THOSE BYTES ARE
     * OURS** (4.5.0 pass 2, fix round 1 — review r1's B2).
     *
     * The move in [theFallbackDownloadLandsOnASiblingAndIsMovedIntoPlaceBeforeTheRowGoes] changed
     * WHO owns a landed file: `DownloadManager`'s unconditional row removal used to take it — that
     * was R3-B1's defect and, accidentally, the failure path's cleanup as well. After Fix 2 the
     * row removal reaches only the `.part` still in flight, so the app has to sweep, and it did so
     * on three exits of four. The one it missed was a throw out of `fetchOne` — `STATUS_FAILED`, a
     * vanished row, an unresolvable local URI — which is the ordinary way a transfer dies.
     *
     * The leak is not merely untidy. `download`'s free-space gate reads `StatFs(staging)` BEFORE
     * the loop deletes a stale `dest`, so leaked bytes are charged against the retry's own
     * requirement: on a phone with free space between `1.1 x totalBytes` and
     * `1.1 x totalBytes + leaked`, every retry refuses permanently with *"Not enough free
     * storage"* — a message that diagnoses the user's phone for the app's own leak. Invisible on
     * every device session, for the same reason R3-B1 was: a Play install never takes this route.
     *
     * So the pin is the PROPERTY rather than the arm: three live sweeps inside `download` (success,
     * cancellation, any other throwable) plus `fail`'s own, each rethrowing, and the cancellation
     * arm FIRST so it can never be swallowed by the general one.
     */
    @Test
    fun everyWayOutOfTheFallbackDownloadSweepsTheStagingDirItOwnsSinceTheMove() {
        val download = scopeOf(manager, "suspend fun download(", "private fun fail(")
        assertEquals(
            "three exits sweep inside `download` — the success path, the cancellation and any " +
                "other throwable — and a fourth exit without one is how 60 MB gets parked for a " +
                "day and then charged against the retry's free-space gate",
            3,
            liveLineCount(download, "staging.deleteRecursively()"),
        )
        assertEquals(
            "the non-cancellation arm exists and is spelled once",
            1,
            liveLineCount(download, "catch (t: Throwable)"),
        )
        val failureArm = scopeOf(download, "catch (t: Throwable)", "throw t")
        assertEquals(
            "and its sweep is INSIDE it, so deleting the sweep cannot leave this pin green",
            1,
            liveLineCount(failureArm, "staging.deleteRecursively()"),
        )
        assertEquals(
            "and it RETHROWS: this arm is a cleanup, not a handler — the actuator still has to " +
                "see the failure and write its back-off stamp",
            1,
            liveLineCount(download, "throw t"),
        )
        // ORDER: Kotlin takes the first matching arm, and `CancellationException` IS a
        // `Throwable`. Put the general arm first and a user's cancel becomes a recorded failure
        // with a 24 h back-off behind it — the one thing the cancellation arm's own comment
        // promises it is not.
        val cancelAt = offsetOfLive(download, "catch (cancelled: CancellationException)")
        val failureAt = offsetOfLive(download, "catch (t: Throwable)")
        assertTrue("both arms must be present", cancelAt >= 0 && failureAt >= 0)
        assertTrue(
            "the cancellation arm must come FIRST, or the general one swallows it and a cancel " +
                "is recorded as a failure",
            cancelAt < failureAt,
        )
        // `fail` sweeps too, and that double-delete is deliberate: a second `deleteRecursively`
        // on a dir that is gone is a no-op, and one sweep per exit reads better than one sweep
        // whose single home a reader has to go and find.
        val failFun = scopeOf(manager, "private fun fail(staging: File", "\n    /**")
        assertEquals(
            "the verify verdict keeps its own sweep",
            1,
            liveLineCount(failFun, "staging.deleteRecursively()"),
        )
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
            liveLineCount(controller, "publish(packName, pack.language, shown)"),
        )
        assertEquals(
            "and never the raw mapping, whose Failed carries the NPU table's sentence",
            0,
            liveLineCount(controller, "publish(packName, pack.language, next)"),
        )
    }

    /**
     * THE ONE OBSERVABLE IS WRITTEN HERE, AND ONLY HERE (4.5.0 Task 1).
     *
     * This shell is one of the three starters of a 73 MB transfer, and until 4.5.0 the only way a
     * surface could see it was `StreamingPackController.state` — a second public flow of the same
     * fetch, which Settings collected and Home did not. Both surfaces now read
     * [PreviewWorkboard]; these pins are what keep the write on the funnel every state already
     * passes through, rather than sprinkled at the interesting call sites where one arm would be
     * missed and a running fetch would go unobserved again.
     *
     * The route and the STARTER are set once, by [StreamingPackController.start], because that
     * call is the only place either is known. The phase is never interpreted here: the mapping is
     * [PreviewStep.of], pure and total over the fetch machine.
     */
    @Test
    fun theBoardIsBegunByTheStarterAndNarratedOnlyFromThePublishFunnel() {
        assertEquals(
            "the route and who asked are recorded ONCE, where they are known",
            1,
            liveLineCount(controller, "PreviewWorkboard.begin("),
        )
        assertEquals(
            "and the phase is narrated from the one funnel every state already passes through",
            1,
            liveLineCount(controller, "PreviewWorkboard.note("),
        )
        assertEquals(
            "the shell interprets no phase of its own — the mapping is pure and JVM-tested",
            1,
            liveLineCount(controller, "PreviewStep.of("),
        )
        val begun = offsetOfLive(controller, "PreviewWorkboard.begin(")
        val fetch = offsetOfLive(controller, ".fetch(listOf(")
        assertTrue(
            "begun ($begun) BEFORE the fetch ($fetch): a row that appears after the first byte " +
                "is a row that cannot describe the refusal of a build with no pack module, which " +
                "returns before the fetch is ever issued",
            begun in 0 until fetch,
        )
        assertEquals(
            "and NO second public flow of this fetch survives: that is the two-variable answer " +
                "three review rounds proved wrong",
            0,
            liveLineCount(controller, "val state: StateFlow"),
        )
        assertEquals(
            "the board write is NOT behind the log throttle — one line per 10 % is right for a " +
                "run-book and wrong for a progress bar",
            1,
            liveLineCount(controller, "NpuPackFetch.shouldLogProgress("),
        )
        val noted = offsetOfLive(controller, "PreviewWorkboard.note(")
        val throttled = offsetOfLive(controller, "NpuPackFetch.shouldLogProgress(")
        assertTrue("the note comes first, so no `return` can skip it", noted in 0 until throttled)
    }

    /**
     * THE CANCEL IS A LATCH, NOT A REQUEST — AND THE LATCH IS A PHASE OF THE ONE OBSERVABLE
     * (4.5.0 Task 1: fix round 1's review r1 B1, then fix round 2's review r2 B1a).
     *
     * 4.5.0's first cut published a terminal `Cancelled` and cleared nothing: `activePack` stayed
     * set, the listener stayed registered, and no *"this pack was abandoned"* fact existed
     * anywhere. So a `COMPLETED` that beat the cancel still ran the install and 73 MB landed
     * AFTER the X had written the permanent no — *installed AND declined* — while `Cancelled` is
     * not [StreamingPackInstall.fetchInFlight], so `isBusy()` went false the instant the X was
     * pressed and the Settings row offered a second 73 MB over a delivery Play had not finished.
     *
     * Fix round 1 answered that with a PRIVATE FIELD, which re-created Task 1's own defect one
     * level up: the fact the actuator read (`isBusy()`) and the fact the two surfaces read (the
     * board) were different things, so Settings saw no work line, fell through its in-flight arm
     * into the OFFER row, and drew a live 73 MB tap that `PreviewAutoFetchController.start` then
     * refused on `busy()` in silence. The abandon is therefore [PreviewPhase.ABANDONED] on
     * [PreviewWorkboard] — ONE fact, in the one place both surfaces already read — and `isBusy()`
     * reads it from there, so the actuator and the rows agree by construction.
     *
     * Nothing here is reachable from a JVM test — the listener, `AssetPackManager.cancel` and
     * `AssetPackState` are all Play — and the DECISIONS are pure and executed elsewhere
     * ([StreamingPackInstall.playStillHoldsTheDelivery], [PreviewWork.cancellable],
     * [PreviewWork.dismissable]). What is pinnable, and what would rot silently, is that the
     * abandon is WRITTEN before Play is asked, CONSULTED before the publish and before the
     * install, and RELEASED in one place.
     */
    @Test
    fun theCancelLatchesThePackOnTheOneObservableSoBothSurfacesSeeTheSameNo() {
        val cancel = scopeOf(controller, "fun cancel() {", "private fun release(")
        assertEquals(
            "the cancel notes the abandoned pack on the BOARD — and it is the ONE step this " +
                "shell does not take from the pure mapping, because no status Play reports means " +
                "'the user changed their mind'",
            1,
            liveLineCount(cancel, "step = PreviewStep(PreviewPhase.ABANDONED)"),
        )
        assertEquals(
            "and NO private field of this object records it any more: a second answer to 'is " +
                "work running', which the actuator reads and the surfaces cannot, is the defect " +
                "Task 1 exists to retire",
            0,
            liveLineCount(controller, "abandonedPackName"),
        )
        val latched = offsetOfLive(cancel, "step = PreviewStep(PreviewPhase.ABANDONED)")
        val asked = offsetOfLive(cancel, "manager?.cancel(listOf(packName))")
        assertTrue(
            "the abandon ($latched) is written BEFORE Play is asked ($asked): the listener runs " +
                "on the main thread and a state that raced the ask must land on the abandoned " +
                "side of it",
            latched in 0 until asked,
        )
        assertEquals(
            "activePack is NOT cleared by the cancel: the listener filters on its name, so " +
                "clearing it here would leave a delivery Play is still making unobserved",
            0,
            liveLineCount(cancel, "activePack = null"),
        )
        // The listener side: consulted before anything is published or installed, released only
        // by the pure predicate.
        val onPackState = scopeOf(controller, "private fun onPackState(", "private fun latchRefusal(")
        assertEquals(
            "one abandoned check in the listener, and it asks the BOARD",
            1,
            liveLineCount(onPackState, "if (abandoned(pack)) {"),
        )
        assertEquals(
            "and one release, by the pure predicate rather than a status re-read here",
            1,
            liveLineCount(
                onPackState,
                "if (!StreamingPackInstall.playStillHoldsTheDelivery(next)) release(pack, packName)",
            ),
        )
        val checked = offsetOfLive(onPackState, "if (abandoned(pack)) {")
        val shown = offsetOfLive(onPackState, "publish(packName, pack.language, shown)")
        val began =
            offsetOfLive(onPackState, "if (next is NpuPackFetch.FetchState.Verifying) beginInstall(")
        assertTrue(
            "the abandoned check ($checked) precedes the publish ($shown): a DOWNLOADING tick " +
                "must not put a dismissed row back on screen",
            checked in 0 until shown,
        )
        assertTrue(
            "and precedes beginInstall ($began): a COMPLETED that beat the cancel must not land " +
                "73 MB behind a permanent no",
            checked in 0 until began,
        )
        // The fallback latch stays ABOVE it, though: a refusal Play NAMES is a fact about the
        // install and not about the attempt, so a dismissed pack still teaches playCanDeliver().
        val refused = offsetOfLive(onPackState, "if (next is NpuPackFetch.FetchState.Failed) latchRefusal(")
        assertTrue(
            "the error-code latch ($refused) precedes the abandoned check ($checked), or a " +
                "sideload that the user dismissed once repeats a Play fetch Play has refused",
            refused in 0 until checked,
        )
        // And the abandon is a term of the single-flight predicate, or the Settings row falls
        // through `previewWorkLine != null -> Unit` into its OFFER row and starts a second fetch
        // over a delivery Play has not finished (H3-B2, reopened through the cancel path).
        val isBusy = scopeOf(controller, "fun isBusy(): Boolean", "private fun abandoned(")
        assertEquals(
            "the abandoned pack counts as busy until Play is done with it",
            1,
            liveLineCount(isBusy, "abandonedButUnconfirmed()"),
        )
        assertEquals(
            "and that term IS the board's own phase — one read, so busy() and the row cannot " +
                "disagree about whether this feature is doing something",
            1,
            liveLineCount(
                controller,
                "PreviewWorkboard.of(pack.language)?.phase == PreviewPhase.ABANDONED",
            ),
        )
    }

    /**
     * THE ABANDON HAS A RELEASE OF OUR OWN, AND IT IS BOUNDED (4.5.0 Task 1, fix round 2 —
     * review r2's B2).
     *
     * Fix round 1 released the abandon in exactly one place: inside `onPackState`, i.e. only when
     * Google Play delivers another `AssetPackState` for that pack. There was no timeout, no clear
     * in `start()` and no user gesture that could free it — and while it was held `isBusy()` was
     * true, so `PreviewAutoFetchController.start` refused for EVERY language and every route and
     * `PreviewAutoFetch.decide` answered NONE for every language. The whole feature was dead
     * until the process died, with no line anywhere saying so.
     *
     * The round's justification was that the release is always reachable because the cancel *"is
     * only ever reached at a phase where Play still has a download to cancel"*. But
     * [PreviewWork.cancellable] admits [PreviewPhase.AWAITING_ANSWER], which is
     * `NpuPackFetch.advance`'s mapping of `STATUS_WAITING_FOR_WIFI` and
     * `STATUS_REQUIRES_USER_CONFIRMATION` — where this feature's own copy says twice that no byte
     * has moved and there is no download — and `AssetPackManager.cancel` is documented as
     * cancelling downloads, only active ones. Whether Play stays silent there cannot be proved
     * off a device; the DEFECT was the absence of any other release, and that is verifiable here.
     *
     * So there are two release CALLS and one release FUNCTION: Play's own answer, and a watchdog
     * of ours that cannot be blocked by a third party. Neither is load-bearing alone.
     */
    @Test
    fun theAbandonIsReleasedByUsTooSoAStalledCancelCannotKillTheFeature() {
        val cancel = scopeOf(controller, "fun cancel() {", "private fun release(")
        assertEquals(
            "the cancel arms the bounded release itself — a wait on a third-party callback is " +
                "not a release",
            1,
            liveLineCount(cancel, "delay(ABANDON_GRACE_MS)"),
        )
        assertEquals(
            "and the window is a named constant with its reasoning, not a literal",
            1,
            liveLineCount(controller, "private const val ABANDON_GRACE_MS: Long"),
        )
        val asked = offsetOfLive(cancel, "manager?.cancel(listOf(packName))")
        val armed = offsetOfLive(cancel, "delay(ABANDON_GRACE_MS)")
        assertTrue(
            "Play is asked ($asked) before the watchdog is armed ($armed): the grace is for " +
                "Play's ANSWER, so the clock must not start before the question",
            asked in 0 until armed,
        )
        assertEquals(
            "TWO release calls — Play's listener and our watchdog — so neither is load-bearing " +
                "alone",
            2,
            liveLineCount(controller, "release(pack, packName)"),
        )
        assertEquals(
            "and ONE release function, so the two cannot free different things",
            1,
            liveLineCount(controller, "private fun release(pack: StreamingPack"),
        )
        val release = scopeOf(controller, "private fun release(pack: StreamingPack", "fun confirm(")
        assertEquals(
            "it is guarded on the pack it was asked about AND on the board's phase, so a " +
                "release racing a later start cannot free the wrong fetch",
            1,
            liveLineCount(release, "if (activePack !== pack || !abandoned(pack)) return"),
        )
        assertEquals(
            "and THE RELEASE is what clears activePack: after it, a delivery that lands anyway " +
                "reaches the listener's own `activePack ?: return` and neither narrates nor " +
                "installs — which is how the permanent no survives a late 73 MB",
            1,
            liveLineCount(release, "activePack = null"),
        )
        val cleared = offsetOfLive(release, "activePack = null")
        val terminal =
            offsetOfLive(release, "publish(packName, pack.language, NpuPackFetch.FetchState.Cancelled)")
        assertTrue(
            "cleared ($cleared) before the board is told it is over ($terminal): the publish " +
                "makes isBusy() false, and a state arriving in between must find no active pack",
            cleared in 0 until terminal,
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

    /**
     * A DELETE TAKES THE BOARD'S RECORD OF THE ARRIVAL WITH IT (4.5.0 Task 3 fix round 1, review
     * r1's B2).
     *
     * `PreviewWorkboard` keeps terminal records on purpose and had NO production caller of
     * `forget` at all, which was safe only while nothing rendered a present-tense sentence off
     * one. Ruling 3c's strip does — *"English is ready: words appear on the bubble whenever you
     * pick it"*, above the language selector — so after install-then-delete that promise stood
     * over a model that no longer existed, to a user who had just written `setLivePreviewDeclined`.
     *
     * It is pinned HERE, in the one production deleter, and not at the Settings row's onClick, for
     * this file's own stated rule: a guard that trusts its caller to remember is not a guard. And
     * it is pinned as SOURCE because there is no JVM path through `delete` (it stats `filesDir`
     * and asks a `DownloadManager`), so a dropped line would be invisible to every other test.
     */
    @Test
    fun theDeleteRetiresTheBoardsRecordOfTheArrivalItJustUndid() {
        val body = scopeOf(manager, "fun delete(pack: StreamingPack)", "\n    /**")
        assertEquals(
            "one retire, in the one production deleter",
            1, liveLineCount(body, "PreviewWorkboard.retire(pack.language)"),
        )
        assertEquals(
            "and it is RETIRE, never forget: `retire` leaves a RUNNING record alone, so a delete " +
                "can never blank a live progress row or make busy() answer false while Play is " +
                "still delivering",
            0, liveLineCount(body, "PreviewWorkboard.forget("),
        )
        val removes = offsetOfLive(body, "StreamingPackInstall.delete(root(), pack)")
        val retires = offsetOfLive(body, "PreviewWorkboard.retire(pack.language)")
        assertTrue("the bytes must go first", removes in 0 until retires)
    }

    /**
     * (4.5.0 Task 3 fix round 2, review r2's N2) AND THE WITHDRAWN VERDICT IS THE SAME FACT AS A
     * DELETE, for every surface that reads the board: after `markCorrupt` this app does not
     * consider the pack installed (`isInstalled` is `this is Installed` only, and `state()`
     * answers `Repair`), so the receipt for its arrival describes something that is no longer the
     * case. It is the one path to that which NOBODY performs — the previewer's load threw during
     * a dictation, `onLoadFailure` landed here — and it left the strip above the language
     * selector the only surface in the app still saying *"English is ready: words appear on the
     * bubble whenever you pick it"*, while Home's card had gone silent on `Repair` and the
     * Settings row was correctly offering the repair.
     *
     * The last assertion is the one that stops a fourth round: EVERY door in this class that
     * changes whether a pack is installed retires the record, counted rather than listed, so a
     * new one cannot be added without answering the question.
     */
    @Test
    fun theWITHDRAWNVerdictRetiresTheRecordToo_andEveryDoorThatChangesTheInstallDoes() {
        val body = scopeOf(manager, "fun markCorrupt(pack: StreamingPack) {", "\n    /**")
        assertEquals(
            "one retire, in the one production withdrawer",
            1, liveLineCount(body, "PreviewWorkboard.retire(pack.language)"),
        )
        assertEquals(
            "and it is RETIRE, never forget: a repair fetch already in flight keeps its progress " +
                "row, because `retire` leaves a RUNNING record alone",
            0, liveLineCount(body, "PreviewWorkboard.forget("),
        )
        val withdraws = offsetOfLive(body, "StreamingPackInstall.markCorrupt(root(), pack)")
        val retires = offsetOfLive(body, "PreviewWorkboard.retire(pack.language)")
        assertTrue("the marker must go first, as in delete", withdraws in 0 until retires)
        assertEquals(
            "EVERY door in this class that changes whether a pack is installed retires the " +
                "board's record: two of them today, and a third cannot be added silently",
            liveLineCount(manager, "StreamingPackInstall.delete(root(), pack)") +
                liveLineCount(manager, "StreamingPackInstall.markCorrupt(root(), pack)"),
            liveLineCount(manager, "PreviewWorkboard.retire(pack.language)"),
        )
    }
}
