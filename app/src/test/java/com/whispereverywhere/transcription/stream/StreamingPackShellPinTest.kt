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

    private val controller: String by lazy {
        source("src/main/java/com/whispereverywhere/transcription/stream/StreamingPackController.kt")
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
