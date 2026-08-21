package com.whispereverywhere.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch

/**
 * Pins the two capture-thread contracts 3.7 Workstream E makes load-bearing: the priority every
 * PCM capture thread runs at, and stop-BEFORE-join teardown ordering.
 *
 * The ordering test uses a REAL background thread, never a same-thread stub: the bug it pins is
 * a scheduling bug, and a same-thread fake cannot express "the reader is blocked right now".
 */
class CaptureThreadPolicyTest {

    /**
     * Reads a repo source file from the test's working directory — the locator
     * NativeVadSourceContractTest uses, for the same reason: the contract lives in a file no JVM
     * test can execute (AudioRecord / AudioPlaybackCaptureConfiguration are Android-only), so the
     * source itself is the only place the wiring can be observed.
     *
     * Line endings are normalized at this single read site (the N1 lesson: readText() does not
     * normalize, and a CRLF checkout silently defeats anything anchored on "\n").
     */
    private fun source(relative: String): String {
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir")!!).absoluteFile
        while (dir != null) {
            for (candidate in listOf(java.io.File(dir, relative), java.io.File(dir, "app/$relative"))) {
                if (candidate.isFile) return candidate.readText().replace("\r\n", "\n")
            }
            dir = dir.parentFile
        }
        throw AssertionError("cannot locate $relative from ${System.getProperty("user.dir")}")
    }

    /**
     * Character index of the first LIVE line of [scope] containing [needle], or -1. Never a raw
     * indexOf: `// CaptureThreadPolicy.enterCaptureThread()` left behind by a refactor keeps a
     * plain contains() green while the capture thread runs at default priority, and in an ordering
     * comparison a commented-out mention would decide the order.
     */
    private fun liveIndexOf(scope: String, needle: String): Int {
        var offset = 0
        for (line in scope.lineSequence()) {
            val trimmed = line.trimStart()
            val commented = trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*")
            if (!commented && line.contains(needle)) return offset + line.indexOf(needle)
            offset += line.length + 1
        }
        return -1
    }

    private fun containsLiveLine(scope: String, needle: String): Boolean = liveIndexOf(scope, needle) >= 0

    @Test
    fun captureThreadsRunAtUrgentAudioPriority_not_plainAudio() {
        // THREAD_PRIORITY_URGENT_AUDIO == -19; THREAD_PRIORITY_AUDIO == -16. The distinction is
        // the whole point (TtsEngine.kt:302 is the in-repo precedent), so pin the VALUE, which a
        // future edit to the softer constant would silently change.
        assertEquals(-19, CaptureThreadPolicy.CAPTURE_THREAD_PRIORITY)
    }

    @Test
    fun enterCaptureThread_appliesTheCapturePriority_notNothing() {
        // Closes a mutant that survived the rest of the suite: because
        // android.os.Process.setThreadPriority is a no-op under returnDefaultValues, an EMPTY
        // enterCaptureThread() body is invisible to every other test here. The injection seam is
        // the only way this JVM suite can see the value that reaches the setter.
        val applied = mutableListOf<Int>()
        CaptureThreadPolicy.enterCaptureThread { applied += it }
        assertEquals(listOf(CaptureThreadPolicy.CAPTURE_THREAD_PRIORITY), applied)
    }

    @Test
    fun enterCaptureThread_isSafeToCallOnARealBackgroundThread() {
        val thrown = arrayOfNulls<Throwable>(1)
        val t = Thread {
            try {
                CaptureThreadPolicy.enterCaptureThread()
            } catch (e: Throwable) {
                thrown[0] = e
            }
        }
        t.start()
        t.join(2_000)
        assertFalse(t.isAlive)
        assertEquals(null, thrown[0])
    }

    @Test
    fun stopThenJoin_stopsTheRecordFirst_soTheJoinNeverWaitsOutItsTimeout() {
        // Models AudioRecord: read() blocks until a buffer fills, and only stop() unblocks it.
        // Join-before-stop (the pre-3.7 order in StreamingAudioRecorder.stop) therefore waits the
        // FULL join timeout on the MAIN thread — the ANR vector this ordering closes.
        val joinMs = 2_000L
        val recordStopped = CountDownLatch(1)
        val events = java.util.Collections.synchronizedList(mutableListOf<String>())
        val captureThread = Thread {
            recordStopped.await()
            events += "capture-exit"
        }
        captureThread.start()

        val startNs = System.nanoTime()
        CaptureThreadPolicy.stopThenJoin(
            joinMs = joinMs,
            stopRecord = { events += "stop"; recordStopped.countDown() },
            joinThread = { ms -> captureThread.join(ms) },
        )
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000

        assertEquals(listOf("stop", "capture-exit"), events)
        assertFalse("stopThenJoin must have JOINED, not merely returned", captureThread.isAlive)
        // Bound DERIVED from the timeout under test, not a second hand-typed number: raising the
        // join bound here must move the failure threshold with it, or this assertion quietly
        // becomes "finished within half a second" and stops meaning "did not wait out the join".
        assertTrue(
            "join waited out its timeout (elapsed=${elapsedMs}ms of a ${joinMs}ms bound)",
            elapsedMs < joinMs / 2,
        )
    }

    @Test
    fun stopThenJoin_stillJoins_whenStoppingTheRecordThrows() {
        // AudioRecord.stop() throws IllegalStateException on an uninitialized record. Swallowing
        // that must not skip the join, or the capture thread outlives release().
        val done = CountDownLatch(1)
        val t = Thread { done.await() }
        t.start()
        var joined = false

        CaptureThreadPolicy.stopThenJoin(
            joinMs = 2_000L,
            stopRecord = { done.countDown(); throw IllegalStateException("uninitialized") },
            joinThread = { ms -> t.join(ms); joined = true },
        )

        assertTrue(joined)
        assertFalse(t.isAlive)
    }

    @Test
    fun stopThenJoin_doesNotPropagate_whenTheJoinItselfThrows() {
        // The other half of "both callbacks are individually guarded": Thread.join throws
        // InterruptedException, and teardown runs inside stop() paths that must not blow up on it.
        // Without this, only the stopRecord guard is pinned and the join guard is a KDoc claim.
        val outcome = runCatching {
            CaptureThreadPolicy.stopThenJoin(
                joinMs = CaptureThreadPolicy.CAPTURE_JOIN_MS,
                stopRecord = {},
                joinThread = { throw InterruptedException("interrupted mid-teardown") },
            )
        }
        assertTrue(
            "a throwing join must not escape teardown: ${outcome.exceptionOrNull()}",
            outcome.isSuccess,
        )
    }

    @Test
    fun stopThenJoin_handsItsBoundToTheJoin_unchanged() {
        // Closes a mutant that survived the rest of the suite: joinThread(0L) passes every
        // ordering test above (both fake threads exit promptly) while meaning Thread.join(0) —
        // an UNBOUNDED wait. On Main that is the ANR this ordering exists to prevent, so pin the
        // value that actually reaches the join, not merely that a join happened.
        val seen = mutableListOf<Long>()
        CaptureThreadPolicy.stopThenJoin(
            joinMs = 1_234L,
            stopRecord = {},
            joinThread = { ms -> seen += ms },
        )
        assertEquals(listOf(1_234L), seen)
    }

    @Test
    fun captureJoinMs_keepsThePre37Bound() {
        // Both pre-3.7 capture sites joined on a bare `2000` literal (StreamingAudioRecorder.kt:107
        // as of E1, PlaybackAudioCapturer.kt:99 still). E2 replaces the recorder's; the capturer's
        // stop() is fenced off by the plan as the precedent this policy is named after. Pin the
        // value either way: extracting a literal into a constant must not quietly change what it
        // was.
        assertEquals(2_000L, CaptureThreadPolicy.CAPTURE_JOIN_MS)
    }

    @Test
    fun bothCaptureThreadsEnterThePolicy_andTheRecorderStopsBeforeItJoins() {
        // THE load-bearing red for this task. Neither wiring can be reached from a JVM test
        // (AudioRecord and AudioPlaybackCaptureConfiguration are Android-only), so the contract is
        // pinned STRUCTURALLY on the source — the CapSeamPinTest pattern this plan uses at every
        // service seam. Without it the reorder has no regression protection at all: `assembleDebug`
        // compiles the un-wired recorder perfectly happily, so it cannot express this contract.
        val recorder = source("src/main/java/com/whispereverywhere/util/StreamingAudioRecorder.kt")
        val playback = source("src/main/java/com/whispereverywhere/audio/PlaybackAudioCapturer.kt")

        assertTrue(
            "StreamingAudioRecorder.stop() must go through CaptureThreadPolicy.stopThenJoin",
            containsLiveLine(recorder, "CaptureThreadPolicy.stopThenJoin("),
        )
        assertEquals(
            "no bare join survives: the record must be stopped first, and only the policy orders that",
            0,
            recorder.split("thread?.join(2000)").size - 1,
        )
        // Each argument pinned for its own reason: a call that reaches stopThenJoin with any one of
        // them hollowed out passes the assertion above while meaning something else entirely.
        assertTrue(
            "the recorder must hand the NAMED bound: a literal 0L here means Thread.join(0) — unbounded, on Main",
            containsLiveLine(recorder, "joinMs = CaptureThreadPolicy.CAPTURE_JOIN_MS"),
        )
        assertTrue(
            "stopRecord must really halt the record — an empty lambda restores the pre-3.7 bug wearing the new shape",
            containsLiveLine(recorder, "stopRecord = { audioRecord?.stop() }"),
        )
        assertTrue(
            "joinThread must really join the capture thread, with the bound it was handed",
            containsLiveLine(recorder, "joinThread = { ms -> thread?.join(ms) }"),
        )
        assertTrue(
            "the mic capture thread must raise its priority as its FIRST statement",
            containsLiveLine(recorder, "CaptureThreadPolicy.enterCaptureThread()"),
        )
        assertTrue(
            "the device-audio capture thread must raise its priority too",
            containsLiveLine(playback, "CaptureThreadPolicy.enterCaptureThread()"),
        )
        // ...and INSIDE the thread body, ahead of the read loop. setThreadPriority applies to the
        // CALLING thread, so the same call sitting outside `Thread { }` raises the wrong thread's
        // priority and one sitting after the loop raises it when capture is already over — both
        // leave every presence assertion above green.
        for ((name, src) in listOf("StreamingAudioRecorder" to recorder, "PlaybackAudioCapturer" to playback)) {
            val bodyOpen = liveIndexOf(src, "thread = Thread {")
            val readLoop = liveIndexOf(src, "while (recording)")
            val enter = liveIndexOf(src, "CaptureThreadPolicy.enterCaptureThread()")
            assertTrue(
                "$name: expected a live capture thread body and read loop (open=$bodyOpen loop=$readLoop)",
                bodyOpen >= 0 && bodyOpen < readLoop,
            )
            assertTrue(
                "$name must enter the policy inside its capture thread body and before the read loop (at $enter)",
                enter > bodyOpen && enter < readLoop,
            )
        }
    }

    @Test
    fun captureThreadPolicySource_pinsWhatNoJvmCallerCanReach() {
        val policy = source("src/main/java/com/whispereverywhere/util/CaptureThreadPolicy.kt")

        // The injection seam displaced the empty-body mutant rather than killing it outright:
        // emptying enterCaptureThread's BODY is caught by
        // enterCaptureThread_appliesTheCapturePriority_notNothing, but emptying its DEFAULT
        // (`= {}`) is invisible to this whole suite — every JVM caller either passes its own lambda
        // or observes nothing, since android.os.Process.setThreadPriority is a returnDefaultValues
        // no-op. The default is structurally-only reachable, so pin it structurally.
        assertTrue(
            "enterCaptureThread's zero-arg DEFAULT must be the real setter — nothing else here reaches it",
            containsLiveLine(policy, "android.os.Process.setThreadPriority(it)"),
        )

        // Same argument for the two teardown guards: android.util.Log is a returnDefaultValues
        // no-op, so "it reported what it swallowed" cannot be observed at runtime from here. A
        // silent runCatching is exactly how a capture thread that refused to die becomes
        // undebuggable, and both halves must report — the stop guard and the join guard.
        assertTrue(
            "the stop guard must report what it swallowed, not swallow it silently",
            containsLiveLine(policy, ".onFailure { android.util.Log.w(\"WE-DIAG\", \"capture teardown: stop failed\", it) }"),
        )
        assertTrue(
            "the join guard must report what it swallowed, not swallow it silently",
            containsLiveLine(policy, ".onFailure { android.util.Log.w(\"WE-DIAG\", \"capture teardown: join failed\", it) }"),
        )
    }
}
