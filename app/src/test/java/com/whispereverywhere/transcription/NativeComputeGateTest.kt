package com.whispereverywhere.transcription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The gate's whole job is mutual exclusion of native whisper work across the two service executors.
 * These tests exercise the primitive directly (the real native calls it guards cannot run in a JVM
 * unit test), proving no two [NativeComputeGate.serialized] blocks are ever in flight at once — the
 * property that stops two concurrent whisper_full calls and the GpuPolicy sentinel race.
 */
class NativeComputeGateTest {

    @Test fun concurrent_callers_never_overlap_inside_the_gate() {
        val threads = 8
        val itersPerThread = 200
        val inside = AtomicInteger(0)
        val maxObserved = AtomicInteger(0)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)

        repeat(threads) {
            Thread {
                start.await()
                repeat(itersPerThread) {
                    NativeComputeGate.serialized {
                        // If two blocks ever ran at once, cur would exceed 1 and maxObserved would
                        // record it. Asserted on the main thread after join (an assert thrown here
                        // would not fail the test runner).
                        val cur = inside.incrementAndGet()
                        maxObserved.updateAndGet { m -> if (cur > m) cur else m }
                        // A tiny spin to widen any race window.
                        var acc = 0L
                        for (i in 0 until 50) acc += i
                        if (acc < 0) throw AssertionError() // keep the loop live; never taken
                        inside.decrementAndGet()
                    }
                }
                done.countDown()
            }.start()
        }

        start.countDown()
        assertTrue("threads did not finish", done.await(30, TimeUnit.SECONDS))
        assertEquals("no concurrent entry into the gate", 1, maxObserved.get())
        assertEquals(0, inside.get())
    }

    @Test fun serialized_returns_the_blocks_value_and_propagates_exceptions() {
        assertEquals(42, NativeComputeGate.serialized { 42 })

        var threw = false
        try {
            NativeComputeGate.serialized { throw IllegalStateException("boom") }
        } catch (e: IllegalStateException) {
            threw = true
            assertEquals("boom", e.message)
        }
        assertTrue("exception must propagate out of the gate", threw)
        // Lock must have been released despite the throw: a subsequent call still runs.
        assertEquals("ok", NativeComputeGate.serialized { "ok" })
    }
}
