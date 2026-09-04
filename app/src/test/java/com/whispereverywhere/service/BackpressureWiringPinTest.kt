package com.whispereverywhere.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * THE BACKPRESSURE GOVERNOR's two wires into the service (build 85), pinned structurally on the
 * SOURCE — `FloatingBubbleService` is an Android Service and cannot be instantiated on the JVM;
 * the instrument and the reasoning are `CommitFunnelPinTest`'s and `EndpointerLifecyclePinTest`'s.
 *
 * Two wires, both of which a green suite could otherwise lose silently:
 *  1. THE FEED. The endpointer learns the segment backlog through `Endpointer.onQueueDepth`, and
 *     the ONE place that calls it is the `SegmentQueueDepth` listener, constructed with
 *     `onDepth = endpointer::onQueueDepth`. That puts the publish INSIDE the counter's monitor,
 *     so a commit on the capture thread and a resolution on Main can never publish out of order
 *     (`SegmentQueueDepthTest.theListenerIsCalledUnderTheMonitorSoPublishedDepthsNeverJump`).
 *     Two bare `endpointer.onQueueDepth(...)` calls at the two sites would have been the smaller
 *     diff and the wrong one: each would read the depth after its own mutation had released the
 *     lock, and the pair can interleave into the endpointer holding a depth the queue had left.
 *     A governor fed a stale 2 latches the slow floor until the next depth change; fed a stale 1
 *     it misses the backlog it exists to see. Deleting the listener leaves `queue:` lines and the
 *     strip label intact and the governor permanently at depth 0 — inert, green, and useless.
 *  2. THE SLOW ROW. `onSessionStart` carries `slowCommitIntervalMs = CommitCadencePolicy
 *     .slowCommitIntervalMs(tierId = installedModel?.id, isCloudBatch = cloudWrapper != null)`
 *     beside the fast row, resolved from the SAME two facts, before `startAudioInput()`. The
 *     parameter is DEFAULTED on the interface (slow == fast, the inert governor), so dropping the
 *     argument compiles, passes every endpointer test, and ships turbo without the guard.
 */
class BackpressureWiringPinTest {

    private fun source(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir")!!).absoluteFile
        while (dir != null) {
            for (candidate in listOf(File(dir, relative), File(dir, "app/$relative"))) {
                if (candidate.isFile) return candidate.readText()
            }
            dir = dir.parentFile
        }
        throw AssertionError("cannot locate $relative from ${System.getProperty("user.dir")}")
    }

    private val text: String by lazy {
        source("src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt")
            .replace("\r\n", "\n")
    }

    private fun count(needle: String): Int = text.split(needle).size - 1

    private fun indexOfOrFail(needle: String): Int {
        val i = text.indexOf(needle)
        assertTrue("missing from FloatingBubbleService.kt: <<$needle>>", i >= 0)
        return i
    }

    @Test
    fun theDepthFeedIsTheEndpointersOnQueueDepthWiredOnceThroughTheCountersListener() {
        assertEquals(
            "the counter publishes to the endpointer from inside its own monitor",
            1,
            count("private val segmentQueueDepth = SegmentQueueDepth(onDepth = endpointer::onQueueDepth)"),
        )
        assertEquals(
            "ONE feed: no bare onQueueDepth call anywhere else in the service",
            1,
            count("onQueueDepth"),
        )
        // Declaration order is load-bearing: a `val` initialiser may only reference a member
        // declared above it, and `endpointer::onQueueDepth` binds the instance at construction.
        val endpointer = indexOfOrFail("    private val endpointer: Endpointer =")
        val counter = indexOfOrFail("    private val segmentQueueDepth = SegmentQueueDepth(")
        assertTrue("the endpointer must be declared before the counter that feeds it", endpointer < counter)
    }

    @Test
    fun theSlowRowRidesTheSameOnSessionStartCallAsTheFastRow() {
        val cadence = indexOfOrFail("                    endpointer.onSessionStart(")
        val fast = indexOfOrFail("                        minCommitIntervalMs = CommitCadencePolicy.minCommitIntervalMs(")
        val slow = indexOfOrFail("                        slowCommitIntervalMs = CommitCadencePolicy.slowCommitIntervalMs(")
        val startInput = text.indexOf("                    val started = startAudioInput()", cadence)
        assertTrue("fast row, then slow row, inside the one onSessionStart call", cadence < fast && fast < slow)
        assertTrue("the slow row must be armed BEFORE the first frame can arrive", slow < startInput)
        assertEquals(1, count("CommitCadencePolicy.slowCommitIntervalMs("))
        assertEquals(1, count("endpointer.onSessionStart("))
        // Both rows resolve from the SAME two facts — the installed tier and the cloud predicate.
        assertEquals(2, count("                            tierId = installedModel?.id,"))
        assertEquals(2, count("                            isCloudBatch = cloudWrapper != null,"))
    }

    @Test
    fun theCounterStillResetsOnceAtSessionStartAndNobodyHandFeedsAZero() {
        // The counter's reset publishes 0 through the listener and the endpointer's
        // onSessionStart clears its own copy: two clears, one event, no third site.
        assertEquals(1, count("segmentQueueDepth.reset()"))
        assertEquals(0, count("onQueueDepth(0)"))
    }
}
