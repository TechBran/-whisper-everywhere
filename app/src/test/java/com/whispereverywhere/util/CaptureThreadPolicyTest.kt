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
        val recordStopped = CountDownLatch(1)
        val events = java.util.Collections.synchronizedList(mutableListOf<String>())
        val captureThread = Thread {
            recordStopped.await()
            events += "capture-exit"
        }
        captureThread.start()

        val startNs = System.nanoTime()
        CaptureThreadPolicy.stopThenJoin(
            joinMs = 2_000L,
            stopRecord = { events += "stop"; recordStopped.countDown() },
            joinThread = { ms -> captureThread.join(ms) },
        )
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000

        assertEquals(listOf("stop", "capture-exit"), events)
        assertFalse("stopThenJoin must have JOINED, not merely returned", captureThread.isAlive)
        assertTrue("join waited out its timeout (elapsed=${elapsedMs}ms)", elapsedMs < 1_000L)
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
        // Both pre-3.7 capture sites joined on a bare `2000` literal (StreamingAudioRecorder.kt:107,
        // PlaybackAudioCapturer.kt:96). E2 replaces both with this constant, so pin the value:
        // extracting a literal into a constant must not quietly change what it was.
        assertEquals(2_000L, CaptureThreadPolicy.CAPTURE_JOIN_MS)
    }
}
