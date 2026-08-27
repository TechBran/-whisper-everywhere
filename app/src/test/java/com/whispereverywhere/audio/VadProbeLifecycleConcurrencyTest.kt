package com.whispereverywhere.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * REAL background executors, never a same-thread stub: the two threads this class exists to
 * separate are genuinely different threads in production — the capture thread calling
 * ensureReady() 31.25 times a second, and Main calling release() from stopRecording. A
 * same-thread stub cannot express either race.
 *
 * The first three tests pin the MONITOR (how many contexts, and in what order). The next two pin
 * the SESSION EPOCH — the half the monitor cannot reach, because a capture thread that outlived
 * its best-effort join calls back in on a legitimately `ARMED` lifecycle and the monitor lets it
 * through. The last pins the steady state's non-blocking read, which is a performance property the
 * other five cannot see: delete the early return and every frame queues behind the teardown.
 *
 * Every wait here is BOUNDED and every ordering is established by a latch rather than by a sleep,
 * so nothing below can pass or fail on scheduling luck. `releaseNeverFreesAContextThatIsStillBeing`
 * `Created` is deliberately race-TOLERANT in the same spirit: both admissible interleavings of its
 * two workers produce the same `[init-enter, init-exit, free]` order once the monitor exists.
 */
class VadProbeLifecycleConcurrencyTest {

    /** init/free take real time, so an unsynchronised lifecycle loses the race deterministically. */
    private class SlowProbe(private val initMs: Long = 150L) : VadProbe {
        val order = CopyOnWriteArrayList<String>()
        val initCalls = AtomicInteger(0)
        val frameCalls = AtomicInteger(0)
        val freeCalls = AtomicInteger(0)
        override fun init(modelPath: String): Boolean {
            initCalls.incrementAndGet()
            order += "init-enter"
            Thread.sleep(initMs)
            order += "init-exit"
            return true
        }
        override fun frame(pcm: ByteBuffer, nBytes: Int): Float {
            frameCalls.incrementAndGet()
            return 0.9f
        }
        override fun reset() { order += "reset" }
        override fun free() { freeCalls.incrementAndGet(); order += "free" }
    }

    @Test
    fun twoCaptureThreadsRacingTheFirstFrameInitialiseExactlyOnce() {
        val probe = SlowProbe()
        val life = VadProbeLifecycle(probe)
        life.arm("/data/vad/model.bin")

        val pool = Executors.newFixedThreadPool(2)
        try {
            val go = CountDownLatch(1)
            val done = CountDownLatch(2)
            val results = CopyOnWriteArrayList<Boolean>()
            repeat(2) {
                pool.execute {
                    go.await(5, TimeUnit.SECONDS)
                    results += life.ensureReady()
                    done.countDown()
                }
            }
            go.countDown()
            assertTrue("both workers must finish", done.await(10, TimeUnit.SECONDS))
            assertEquals(listOf(true, true), results.toList())
            assertEquals("the native probe context must be created exactly once", 1, probe.initCalls.get())
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun releaseNeverFreesAContextThatIsStillBeingCreated() {
        val probe = SlowProbe(initMs = 300L)
        val life = VadProbeLifecycle(probe)
        life.arm("/data/vad/model.bin")

        val pool = Executors.newFixedThreadPool(2)
        try {
            val initStarted = CountDownLatch(1)
            val done = CountDownLatch(2)
            pool.execute {
                initStarted.countDown()
                life.ensureReady()
                done.countDown()
            }
            assertTrue(initStarted.await(5, TimeUnit.SECONDS))
            Thread.sleep(50)                     // land inside the in-flight init
            pool.execute { life.release(); done.countDown() }
            assertTrue(done.await(10, TimeUnit.SECONDS))

            assertEquals(
                "free must never interleave with an in-flight init",
                listOf("init-enter", "init-exit", "free"),
                probe.order.toList(),
            )
            assertEquals(1, probe.freeCalls.get())
            assertEquals(VadProbeLifecycle.State.IDLE, life.state())
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun aFrameArrivingAfterReleaseDoesNotResurrectTheProbe() {
        val probe = SlowProbe(initMs = 0L)
        val life = VadProbeLifecycle(probe)
        life.arm("/data/vad/model.bin")
        life.ensureReady()
        life.release()

        val pool = Executors.newSingleThreadExecutor()
        try {
            val done = CountDownLatch(1)
            val late = CopyOnWriteArrayList<Boolean>()
            pool.execute { late += life.ensureReady(); done.countDown() }
            assertTrue(done.await(5, TimeUnit.SECONDS))
            assertEquals(listOf(false), late.toList())
            assertEquals("a late frame must not re-init a released probe", 1, probe.initCalls.get())
        } finally {
            pool.shutdownNow()
        }
    }

    /**
     * The stale capture thread, half one: it must not BUILD the next session's context.
     *
     * `StreamingAudioRecorder.kt:132-140` names this case in tree — "the `vadProbeInit` of the NEXT
     * session starting while the previous capture thread is still unwinding" — and the monitor
     * cannot refuse it, because by then the lifecycle is legitimately `ARMED` for session N+1 and
     * the stale thread takes the lock like any other caller. Only the epoch can tell them apart.
     */
    @Test
    fun aStaleCaptureThreadsEnsureReadyDoesNotBuildTheNextSessionsContext() {
        val probe = SlowProbe(initMs = 0L)
        val life = VadProbeLifecycle(probe)

        val stale = life.arm("/data/vad/model.bin")          // session N
        assertTrue(life.ensureReady(stale))
        life.release()                                        // Main tears N down behind a bounded join
        val fresh = life.arm("/data/vad/model.bin")           // session N+1 opens: ARMED again
        assertEquals(VadProbeLifecycle.State.ARMED, life.state())

        val pool = Executors.newSingleThreadExecutor()
        try {
            val done = CountDownLatch(1)
            val late = CopyOnWriteArrayList<Boolean>()
            pool.execute { late += life.ensureReady(stale); done.countDown() }
            assertTrue("the stale worker must finish", done.await(5, TimeUnit.SECONDS))

            assertEquals(listOf(false), late.toList())
            assertEquals(
                "a capture thread that outlived its join must not build the NEXT session's context",
                1,
                probe.initCalls.get(),
            )
            assertEquals(
                "and refusing it must not move the lifecycle on",
                VadProbeLifecycle.State.ARMED,
                life.state(),
            )

            // The live session is untouched: its own token still initialises, exactly once.
            assertTrue(life.ensureReady(fresh))
            assertEquals(2, probe.initCalls.get())
        } finally {
            pool.shutdownNow()
        }
    }

    /**
     * The stale capture thread, half two: it must not FEED the next session's live context.
     *
     * This is the half an `ensureReady`-only gate would miss, because D8's lambda calls the probe
     * directly. A stale thread reaching a READY session's context writes session N's audio through
     * the ONE shared direct buffer concurrently with N+1's real capture thread — torn frames (T8)
     * and cross-session LSTM contamination (T9), with no exception and no log. The refusal must be
     * NO_VERDICT and never 0.0f: -1.0f is "keep the previous state" (T10), whereas 0.0f is
     * confident silence and would cut a live utterance.
     */
    @Test
    fun aStaleCaptureThreadsFrameIsRefusedWhileTheNextSessionIsReady() {
        val probe = SlowProbe(initMs = 0L)
        val life = VadProbeLifecycle(probe)
        val buffer = ByteBuffer.allocateDirect(VadProbe.FRAME_BYTES)

        val stale = life.arm("/data/vad/model.bin")          // session N
        assertTrue(life.ensureReady(stale))
        life.release()
        val fresh = life.arm("/data/vad/model.bin")          // session N+1
        assertTrue(life.ensureReady(fresh))                  // ...and it is READY: a LIVE context
        assertEquals(VadProbeLifecycle.State.READY, life.state())

        val pool = Executors.newSingleThreadExecutor()
        try {
            val done = CountDownLatch(1)
            val verdicts = CopyOnWriteArrayList<Float>()
            pool.execute {
                verdicts += life.frame(stale, buffer, VadProbe.FRAME_BYTES)
                done.countDown()
            }
            assertTrue("the stale worker must finish", done.await(5, TimeUnit.SECONDS))

            assertEquals(
                "a stale frame must be NO_VERDICT — never 0.0f, which reads as confident silence",
                listOf(VadProbe.NO_VERDICT),
                verdicts.toList(),
            )
            assertEquals(
                "the stale thread must never reach the live context",
                0,
                probe.frameCalls.get(),
            )

            // The live session's own token still gets through, unchanged.
            assertEquals(0.9f, life.frame(fresh, buffer, VadProbe.FRAME_BYTES), 0.0f)
            assertEquals(1, probe.frameCalls.get())
        } finally {
            pool.shutdownNow()
        }
    }

    /**
     * The monitor must not reach the steady state. This is a PERFORMANCE property no assertion
     * about contexts or ordering can see, so it is pinned by the one thing that is observable:
     * with the volatile early return, a frame arriving during a teardown returns immediately;
     * without it, that frame queues behind `probe.free()` for as long as the free takes — on the
     * audio thread, at 31.25 Hz, behind an operation that blocks on the native probe mutex.
     *
     * Deterministic, not timed: the teardown signals from INSIDE `free()` and then parks, so the
     * monitor is provably held before the hot call is made, and it stays held until this test says
     * otherwise. The mutant cannot win the race; it has no race to win.
     */
    @Test
    fun theSteadyStateFramePathDoesNotQueueBehindATeardown() {
        val entered = CountDownLatch(1)
        val letFreeFinish = CountDownLatch(1)
        val probe = object : VadProbe {
            val frees = AtomicInteger(0)
            override fun init(modelPath: String): Boolean = true
            override fun frame(pcm: ByteBuffer, nBytes: Int): Float = 0.9f
            override fun reset() {}
            override fun free() {
                frees.incrementAndGet()
                entered.countDown()
                letFreeFinish.await(10, TimeUnit.SECONDS)
            }
        }
        val life = VadProbeLifecycle(probe)
        val session = life.arm("/data/vad/model.bin")
        assertTrue(life.ensureReady(session))

        val pool = Executors.newFixedThreadPool(2)
        try {
            val releaseDone = CountDownLatch(1)
            pool.execute { life.release(); releaseDone.countDown() }
            assertTrue(
                "the teardown must reach free() and be holding the monitor",
                entered.await(5, TimeUnit.SECONDS),
            )

            val hotDone = CountDownLatch(1)
            val hot = CopyOnWriteArrayList<Boolean>()
            pool.execute { hot += life.ensureReady(session); hotDone.countDown() }
            assertTrue(
                "the 31.25 Hz steady state must return on its volatile READY read, NOT queue " +
                    "behind an in-flight teardown holding the monitor",
                hotDone.await(2, TimeUnit.SECONDS),
            )
            assertEquals(listOf(true), hot.toList())

            letFreeFinish.countDown()
            assertTrue(releaseDone.await(5, TimeUnit.SECONDS))
            assertEquals(VadProbeLifecycle.State.IDLE, life.state())
            assertEquals(1, probe.frees.get())
        } finally {
            letFreeFinish.countDown()
            pool.shutdownNow()
        }
    }
}
