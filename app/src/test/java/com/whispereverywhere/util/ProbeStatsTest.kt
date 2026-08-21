package com.whispereverywhere.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 3.7 probe cost/overrun accounting (Workstream E3, surfaced by Workstream F's `probe:` line).
 * Everything here is pure: the class is fed from the capture thread at 31.25 Hz, so it must be
 * allocation-free per frame and must not need a clock of its own.
 */
class ProbeStatsTest {

    /** 8 ms, the spec's PROBE_BUDGET_MS, expressed in the microseconds record() takes. */
    private val budgetUs = 8_000L

    /**
     * Reads a repo source file from the test's working directory — the locator
     * `CaptureThreadPolicyTest` and `NativeVadSourceContractTest` share, replicated here in its
     * minimal form. Line endings are normalized at this single read site (the N1 lesson:
     * `readText()` does not normalize, and a CRLF checkout silently defeats anything anchored
     * on a newline).
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
     * How many LIVE (non-comment) lines of [scope] contain [needle]. Never a raw count: the class
     * KDoc of the file under inspection says the word `@Synchronized` in prose, so a naive count
     * reads 7 where the truth is 6 — and, worse, a commented-out annotation would keep it green
     * while the lock was gone.
     */
    private fun liveLineCount(scope: String, needle: String): Int =
        scope.lineSequence().count { line ->
            val trimmed = line.trimStart()
            val commented = trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*")
            !commented && line.contains(needle)
        }

    @Test
    fun countsEveryFrame_andOnlyBudgetOverrunsAsOverruns() {
        val s = ProbeStats(budgetUs = budgetUs)
        s.record(800L, 0L)
        s.record(8_000L, 32L)     // exactly at budget is NOT an overrun
        s.record(8_001L, 64L)     // one microsecond over IS
        s.record(9_600L, 96L)

        assertEquals(4L, s.frames())
        assertEquals(2L, s.overruns())
    }

    @Test
    fun p50AndP99AreTakenOverTheWholeSession_andP99CatchesTheTail() {
        val s = ProbeStats(budgetUs = budgetUs)
        // 98 fast frames (800 us -> bucket 50) and 2 slow ones (9600 us -> bucket 600).
        var t = 0L
        repeat(98) { s.record(800L, t); t += 32L }
        repeat(2) { s.record(9_600L, t); t += 32L }

        assertEquals(800L, s.percentileUs(0.50))
        // rank(0.99) = 99 > 98 fast frames, so p99 must land in the slow bucket.
        assertEquals(9_600L, s.percentileUs(0.99))
        assertEquals(2L, s.overruns())

        // Rank ROUNDING, which the 98/2 split above cannot see: 0.99 * 100 is EXACTLY 99.0 in
        // IEEE-754, so ceil and floor agree on it and the split is blind to the difference between
        // them. Ten frames make the rank 9.9 — ceil gives 10, which lands in the slow bucket, while
        // floor gives 9 and answers with the ninth FAST frame, under-reporting the very tail the
        // p99 exists to expose.
        val r = ProbeStats(budgetUs = budgetUs)
        repeat(9) { r.record(800L, it * 32L) }
        r.record(9_600L, 9 * 32L)
        assertEquals(9_600L, r.percentileUs(0.99))

        // Bucket WIDTH, which nothing else in this file can see either: every other sample here is
        // a multiple of 32, so a 32 us (or 64 us) bucket would report them all unchanged. 1616 us
        // is 101 * 16 and deliberately NOT a multiple of 32 — only a 16 us bucket reports it
        // exactly, and any other width answers with a neighbouring edge.
        val w = ProbeStats(budgetUs = budgetUs)
        w.record(1_616L, 0L)
        assertEquals(1_616L, w.percentileUs(0.50))
    }

    @Test
    fun percentilesOfAnEmptySessionAreZero_neverNaNOrCrash() {
        val s = ProbeStats(budgetUs = budgetUs)
        assertEquals(0L, s.percentileUs(0.50))
        assertEquals(0L, s.percentileUs(0.99))
        assertEquals("probe: frames=0 p50=0\u00B5s p99=0\u00B5s overruns=0", s.line())
    }

    @Test
    fun lineMatchesTheGreppableFormatExactly() {
        val s = ProbeStats(budgetUs = budgetUs)
        repeat(10) { s.record(1_600L, it * 32L) }
        // \u00B5 is MICRO SIGN: written as an escape so the source stays ASCII and the emitted
        // byte is identical regardless of how the file is encoded on disk.
        assertEquals("probe: frames=10 p50=1600\u00B5s p99=1600\u00B5s overruns=0", s.line())
    }

    @Test
    fun recordSignalsALineIsDue_atMostOncePerEmitInterval() {
        val s = ProbeStats(budgetUs = budgetUs, emitIntervalMs = 10_000L)
        assertFalse("the very first frame only arms the clock", s.record(800L, 1_000L))
        assertFalse(s.record(800L, 5_000L))
        assertFalse(s.record(800L, 10_999L))
        assertTrue(s.record(800L, 11_000L))      // 10 s after the arming frame
        assertFalse(s.record(800L, 11_032L))     // window restarts, nothing due yet
        assertTrue(s.record(800L, 21_000L))

        // The PRODUCTION cadence. Every assertion above passes emitIntervalMs explicitly, which
        // leaves the SHIPPED default — the one Task C10 gets, because it constructs with budgetUs
        // alone — exercised by nothing at all. Same boundary, taken against the default this time.
        val d = ProbeStats(budgetUs = budgetUs)
        assertFalse("the first frame arms the clock at the default cadence too", d.record(800L, 1_000L))
        assertFalse(d.record(800L, 10_999L))
        assertTrue(d.record(800L, 11_000L))
    }

    @Test
    fun overBucketRangeFramesAreClampedIntoTheOverflowBucket_notLost() {
        val s = ProbeStats(budgetUs = budgetUs)
        s.record(5_000_000L, 0L)      // 5 s: a pathological stall, far past the histogram
        assertEquals(1L, s.frames())
        assertEquals(1L, s.overruns())
        // Overflow reports the histogram ceiling, which is unambiguous ("at least this bad").
        assertEquals(ProbeStats.BUCKETS * ProbeStats.BUCKET_WIDTH_US, s.percentileUs(0.50))

        // And the span in ABSOLUTE terms. The assertion above multiplies the two constants by each
        // other, so it holds for any pair of them and says nothing whatever about their values:
        // 1023 buckets, or 4096, or a 32 us width, all satisfy it.
        assertEquals(
            "the histogram spans 0..16_383 us, twice the 8 ms budget",
            16_384L,
            ProbeStats.BUCKETS * ProbeStats.BUCKET_WIDTH_US,
        )

        // The OTHER end of the same clamp. A negative elapsed — a fake clock stepped backwards, or
        // a nanoClock swapped for a non-monotonic one — divides to a NEGATIVE bucket index, and an
        // ArrayIndexOutOfBounds thrown inside record() kills the capture thread outright. It is
        // clamped into bucket 0: counted, never dropped, never thrown. (Magnitude deliberately
        // exceeds one bucket width — Kotlin truncates -5/16 toward zero, which would hide this.)
        s.record(-1_000L, 32L)
        assertEquals(2L, s.frames())
        assertEquals(1L, s.overruns())          // a negative cost is not over budget
        assertEquals(0L, s.percentileUs(0.50))  // rank 1 of 2 lands in bucket 0, where it was put
    }

    @Test
    fun resetClearsCountersAndHistogram_forTheNextSession() {
        val s = ProbeStats(budgetUs = budgetUs)
        repeat(5) { s.record(800L, it * 32L) }           // fast frames, bucket 50
        repeat(5) { s.record(9_600L, (5 + it) * 32L) }   // slow frames, bucket 600 — and overruns
        s.reset()
        assertEquals(0L, s.frames())
        assertEquals(0L, s.overruns())
        assertEquals(0L, s.percentileUs(0.99))

        // The HISTOGRAM too, not only the counters — and the counters alone cannot prove it,
        // because percentileUs short-circuits to 0 on frames()==0 and so reads clean either way.
        // One slow frame in the new session must read as slow: a reset that cleared only the
        // counters leaves the PREVIOUS session's fast bucket ahead of it in the scan, and the new
        // session reports 800us for a frame that took 9600.
        s.record(9_600L, 1_000L)
        assertEquals(9_600L, s.percentileUs(0.50))
    }

    @Test
    fun aRealCaptureThreadRecordingWhileMainReadsAndResetsNeverTearsTheCounters() {
        // The production shape, and the reason every method is @Synchronized: record() runs on the
        // AudioRecord capture thread while reset() (onSessionStart) and line() (onSessionEnd) are
        // called from Main — and E1's join is TIMED, so a timed-out teardown genuinely leaves the
        // capture thread inside record() while Main is inside line(). REAL executor per the Global
        // Constraints' concurrency rule; never a same-thread stub.
        val capture = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val s = ProbeStats(budgetUs = budgetUs)
            val n = 4_000
            val done = java.util.concurrent.CountDownLatch(1)
            capture.execute {
                for (i in 0 until n) s.record(if (i % 4 == 0) 9_600L else 800L, i * 32L)
                done.countDown()
            }
            // Main hammers the readers throughout — a torn read would surface as an exception
            // (IndexOutOfBounds from a half-cleared histogram) or as an impossible invariant.
            while (done.count > 0L) {
                s.line()
                assertTrue("overruns can never exceed frames", s.overruns() <= s.frames())
                // The spin is a reader, not a barrier: yielding keeps it from monopolising a core
                // on a single-core CI box and starving the very writer it is racing.
                Thread.yield()
            }
            assertTrue("workers did not finish", done.await(20, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(n.toLong(), s.frames())
            assertEquals((n / 4).toLong(), s.overruns())

            // And a reset from Main leaves the instance usable, not half-cleared.
            s.reset()
            assertEquals(0L, s.frames())
            assertEquals(0L, s.overruns())

            // The other half of the same contract, and the half no JVM test can reach: dropping a
            // @Synchronized does not FAIL this race, it only makes it lose on someone else's
            // machine, some other day. Count form, so that adding a seventh method without the
            // annotation trips it just as loudly as deleting one of the six.
            assertEquals(
                "all six of record/frames/overruns/percentileUs/line/reset must stay " +
                    "@Synchronized: record() is the lone WRITER and runs on the capture thread, " +
                    "while Main reads line()/frames()/overruns() and writes reset() at session " +
                    "start - E1's join is TIMED, so both threads really are inside this object " +
                    "at once rather than merely in theory",
                6,
                liveLineCount(
                    source("src/main/java/com/whispereverywhere/util/ProbeStats.kt"),
                    "@Synchronized",
                ),
            )
        } finally {
            capture.shutdownNow()
        }
    }
}
