package com.whispereverywhere.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The committed-but-unresolved backlog (3.7 Workstream F). This is the only surface that makes a
 * growing multi-tier backlog visible WHILE it happens rather than at stop.
 *
 * Concurrency is not incidental: commits arrive from the capture thread AND from Main
 * (switchSource, projection consent, stopRecording) while resolutions arrive on Main. The
 * concurrent test therefore uses REAL executors, never a same-thread stub.
 */
class SegmentQueueDepthTest {

    @Test
    fun depthRisesPerCommitAndFallsPerResolution() {
        val q = SegmentQueueDepth()
        assertEquals(1, q.onCommitted(0L))
        assertEquals(2, q.onCommitted(1L))
        assertEquals(3, q.onCommitted(2L))
        assertEquals(2, q.onResolved(0L))
        assertEquals(1, q.onResolved(1L))
        assertEquals(0, q.onResolved(2L))
        assertEquals(0, q.depth())
    }

    @Test
    fun aCommitThatCutNothingDoesNotCount() {
        // TranscriptionEngine.commit() returns -1 when there was nothing to cut, and that seq will
        // never reach onSegmentResolved — counting it would strand the depth above zero forever.
        val q = SegmentQueueDepth()
        assertEquals(0, q.onCommitted(-1L))
        assertEquals(0, q.depth())
    }

    @Test
    fun anUnknownOrDuplicateResolutionNeverDrivesDepthNegative() {
        val q = SegmentQueueDepth()
        q.onCommitted(0L)
        assertEquals(0, q.onResolved(0L))
        assertEquals(0, q.onResolved(0L))   // duplicate
        assertEquals(0, q.onResolved(99L))  // never committed
        assertEquals(0, q.depth())
    }

    @Test
    fun aRepeatedCommitOfTheSameSeqCountsOnce() {
        val q = SegmentQueueDepth()
        assertEquals(1, q.onCommitted(5L))
        assertEquals(1, q.onCommitted(5L))
    }

    @Test
    fun resetClearsTheBacklogForTheNextSession() {
        val q = SegmentQueueDepth()
        q.onCommitted(0L); q.onCommitted(1L)
        q.reset()
        assertEquals(0, q.depth())
        assertEquals(1, q.onCommitted(0L))   // seq numbering restarts per session
    }

    @Test
    fun commitsAndResolutionsFromRealBackgroundThreadsSettleAtZero() {
        val commits = Executors.newSingleThreadExecutor()
        val resolutions = Executors.newSingleThreadExecutor()
        try {
            val q = SegmentQueueDepth()
            val n = 500
            val committed = CountDownLatch(n)
            val resolved = CountDownLatch(n)

            for (seq in 0 until n) {
                commits.execute {
                    q.onCommitted(seq.toLong())
                    committed.countDown()
                    // Enqueued from INSIDE the commit task, so a seq is never resolved before it
                    // was committed — that is the real production ordering (a seq only exists
                    // once commit() returned it). The two executors still overlap freely: commit
                    // N+1 runs on one thread while resolve N runs on the other, which is the
                    // interleaving the synchronization has to survive.
                    resolutions.execute { q.onResolved(seq.toLong()); resolved.countDown() }
                }
            }
            assertTrue(committed.await(10, TimeUnit.SECONDS))
            assertTrue(resolved.await(10, TimeUnit.SECONDS))

            // Every committed seq was resolved exactly once, so the backlog must be empty — and
            // must never have gone negative on the way.
            assertEquals(0, q.depth())
        } finally {
            commits.shutdownNow()
            resolutions.shutdownNow()
        }
    }

    @Test
    fun queueLineMatchesTheGreppableFormatExactly() {
        assertEquals("queue: depth=0", EndpointDiag.queueLine(0))
        assertEquals("queue: depth=7", EndpointDiag.queueLine(7))
    }

    @Test
    fun aFreshCounterIsEmpty() {
        // The strip reads depth() before anything has been committed, on every session's first
        // render pass; it must not have to defend against a garbage initial value.
        assertEquals(0, SegmentQueueDepth().depth())
    }

    @Test
    fun unorderedCommitsAndResolutionsFromFourRealThreadsStayInBounds() {
        // The ORDERED case is pinned above (each resolution enqueued from inside its own commit).
        // This is the adversarial one: two committer threads and two resolver threads with no
        // happens-before between a seq's commit and its resolution, which is what a torn-down
        // session, an error-path flush and the unconditional stop flush can genuinely produce.
        // REAL pools, never a same-thread stub: in production onCommitted() is called from the
        // AudioRecord capture thread while onResolved() runs on Main.
        val q = SegmentQueueDepth()
        val committers = Executors.newFixedThreadPool(2)
        val resolvers = Executors.newFixedThreadPool(2)
        try {
            val start = CountDownLatch(1)
            val done = CountDownLatch(4)
            repeat(2) { worker ->
                committers.execute {
                    start.await()
                    for (i in 0 until 500) q.onCommitted((worker * 500 + i).toLong())
                    done.countDown()
                }
            }
            repeat(2) { worker ->
                resolvers.execute {
                    start.await()
                    for (i in 0 until 500) q.onResolved((worker * 500 + i).toLong())
                    done.countDown()
                }
            }
            start.countDown()
            assertTrue("workers did not finish", done.await(20, TimeUnit.SECONDS))

            // 1000 distinct seqs committed and the same 1000 resolved, in any interleaving: every
            // resolution that arrived early is a no-op on an absent key, so a residue of real keys
            // is expected here. Draining all of them DETERMINISTICALLY, from this thread, is what
            // makes the assertion bite — an unsynchronized HashSet loses or duplicates entries
            // under a four-thread storm and leaves a non-zero residue that no drain can clear.
            // (A bare `depth() in 0..1000` would pass by construction: a set fed 1000 distinct keys
            // cannot exceed 1000 whether it is synchronized or not.)
            for (seq in 0 until 1000) q.onResolved(seq.toLong())
            assertEquals("the storm left entries the drain could not clear", 0, q.depth())

            // And the counter is still usable afterwards — no torn state.
            q.reset()
            assertEquals(0, q.depth())
            assertEquals(1, q.onCommitted(0L))
        } finally {
            committers.shutdownNow()
            resolvers.shutdownNow()
        }
    }
}
