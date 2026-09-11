package com.whispereverywhere.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * THE TRIM -> PREWARM REACH, pinned structurally (4.4.0 startup amendment, Task S1) — the
 * LocalPreviewWiringPinTest idiom: `FloatingBubbleService` cannot be instantiated on the JVM, so
 * the CALLS are pinned as LF-normalised, symbol-scoped needles inside their declaring bodies;
 * never line numbers.
 *
 * What this class holds: `onTrimMemory` re-arms the prewarm inside its three-state guard and AFTER
 * both releases; the re-arm runs the SAME path the boot prewarm runs (`warmLocalEngine().prewarm()`,
 * no rebuild permission, never `prewarmModelSwitch`); it waits the boot prewarm's own 1,500 ms and
 * re-reads the state BELOW that suspension, with nothing suspending between the read and the call;
 * a second trim replaces the pending re-arm instead of stacking a second 342 MiB–1.02 GiB load; and
 * it emits exactly ONE diag line, carrying the decision's inputs.
 *
 * The pin exists because the reach is the whole fix: the decision is already a truth table in
 * TrimPrewarmPolicyTest, and what no JVM test can otherwise see is whether the trim handler
 * actually gets there.
 */
class TrimPrewarmWiringPinTest {

    private fun source(relative: String): File {
        var dir: File? = File(System.getProperty("user.dir")!!).absoluteFile
        while (dir != null) {
            for (candidate in listOf(File(dir, relative), File(dir, "app/$relative"))) {
                if (candidate.isFile) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError("cannot locate $relative from ${System.getProperty("user.dir")}")
    }

    private val text: String by lazy {
        source("src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt")
            .readText()
            .replace("\r\n", "\n")
    }

    private fun count(haystack: String, needle: String) = haystack.split(needle).size - 1

    private fun indexOfOrFail(haystack: String, needle: String): Int {
        val i = haystack.indexOf(needle)
        assertTrue("missing from FloatingBubbleService.kt: <<$needle>>", i >= 0)
        return i
    }

    private fun body(declaration: String, closer: String): String {
        val start = indexOfOrFail(text, declaration)
        val close = text.indexOf(closer, start)
        assertTrue("the closing brace of <<$declaration>> moved", close > start)
        return text.substring(start, close + closer.length)
    }

    private val onTrim: String by lazy { body("    override fun onTrimMemory(level: Int) {", "\n    }\n") }
    private val rearm: String by lazy { body("    private fun rearmPrewarmAfterTrim(level: Int) {", "\n    }\n") }

    @Test
    fun theTrimHandlerReachesTheRearmInsideItsGuardAndAfterBothReleases() {
        // Inside the guard, at the guard's indent: a re-arm outside it would warm a context that
        // was never released, and — worse — would do so during a session.
        indexOfOrFail(onTrim, "\n            rearmPrewarmAfterTrim(level)\n")
        val local = indexOfOrFail(onTrim, "localEngine?.releaseContext()")
        val preview = indexOfOrFail(onTrim, "streamingPreview?.release()")
        val ours = indexOfOrFail(onTrim, "rearmPrewarmAfterTrim(level)")
        assertTrue("the re-arm follows the local release", local < ours)
        assertTrue("and follows the previewer release", preview < ours)
        assertEquals("ONE trigger site", 1, count(text, "rearmPrewarmAfterTrim(level)"))
        assertEquals("ONE re-arm body", 1, count(text, "    private fun rearmPrewarmAfterTrim(level: Int) {"))
    }

    @Test
    fun theRearmRunsTheSamePrewarmPathTheBootPrewarmRuns() {
        // `prewarm()` on a NON-rebuilding warmLocalEngine(), exactly as onCreate's delayed
        // coroutine does. Not prewarmModelSwitch(): the tier did not change, the slot is simply
        // empty, and prewarm() fills only an empty slot — which is also why it is safe to queue
        // behind releaseContext() on the engine's single native executor.
        indexOfOrFail(rearm, "warmLocalEngine().prewarm()")
        assertEquals("no rebuild permission — the destructive branch belongs to its two callers", 0, count(rearm, "allowRebuild"))
        assertEquals("never the model-switch prewarm", 0, count(rearm, "prewarmModelSwitch"))
        assertEquals(
            "two prewarm sites in the file: onCreate's boot prewarm and this re-arm",
            2,
            count(text, "warmLocalEngine().prewarm()"),
        )
        // Task S1 is the whisper context only. The resident previewer keeps the re-warm its own
        // KDoc promises ("the next eligible session re-warms it"), so this body must not grow one.
        assertEquals("the previewer is not this task's business", 0, count(rearm, "warmStreamingPreview"))
    }

    @Test
    fun theWaitIsTheBootPrewarmsOwnNumberAndTheStateIsReReadBelowIt() {
        // 1,500 ms is MATCHED, not invented: it is the delay the boot prewarm already takes before
        // the very same call. Pinned as a pair so a change to one is a visible change to both.
        indexOfOrFail(text, "private const val TRIM_REARM_DELAY_MS = 1_500L")
        indexOfOrFail(text, "            delay(1500)\n")
        assertEquals(1, count(rearm, "delay(TRIM_REARM_DELAY_MS)"))

        // The gate is read BELOW the suspension (the model-switch collector's fix-round-2 rule): a
        // state read taken before a 1.5 s wait is not a check. And nothing may suspend between the
        // read and the prewarm, or the read goes stale again.
        val wait = indexOfOrFail(rearm, "delay(TRIM_REARM_DELAY_MS)")
        val gate = indexOfOrFail(rearm, "prewarmRearmsAfterTrim(currentState)")
        val call = indexOfOrFail(rearm, "warmLocalEngine().prewarm()")
        assertTrue("the state is re-read after the wait", wait < gate)
        assertTrue("and the prewarm follows the gate", gate < call)
        val gateToCall = rearm.substring(gate, call)
        assertEquals("nothing suspends between the gate and the call", 0, count(gateToCall, "delay("))
        assertEquals("nothing hops dispatcher between the gate and the call", 0, count(gateToCall, "withContext"))
        assertEquals("ONE caller of the policy", 1, count(text, "prewarmRearmsAfterTrim(currentState)"))
    }

    @Test
    fun asecondTrimReplacesThePendingRearmInsteadOfStackingOne() {
        // A trim storm (RUNNING_LOW, then RUNNING_CRITICAL, then BACKGROUND) must collapse to ONE
        // load 1.5 s after the LAST trim — the debounce the model-switch collector gets from
        // collectLatest, here from the job handle the file already uses for its other timers.
        val cancel = indexOfOrFail(rearm, "trimRearmJob?.cancel()")
        val assign = indexOfOrFail(rearm, "trimRearmJob = serviceScope.launch {")
        assertTrue("the pending re-arm is cancelled before a new one replaces it", cancel < assign)
        assertEquals(1, count(text, "trimRearmJob?.cancel()"))
        assertEquals(1, count(text, "trimRearmJob = serviceScope.launch {"))
    }

    @Test
    fun oneDiagLineCarryingTheDecisionsInputs() {
        assertEquals("exactly one diag line", 1, count(rearm, "android.util.Log."))
        val line = indexOfOrFail(rearm, "\"trim re-prewarm: ")
        val logged = rearm.substring(line, rearm.indexOf('\n', line))
        listOf("level=", "state=", "rearm=").forEach {
            assertTrue("the line names $it", logged.contains(it))
        }
    }
}
