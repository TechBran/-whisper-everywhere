package com.whispereverywhere.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentCapPolicyTest {

    @Test
    fun shippedCapsAre4And15Seconds() {
        assertEquals(4_000L, SegmentCapPolicy.FIRST_SEGMENT_WALL_MS)
        assertEquals(15_000L, SegmentCapPolicy.MAX_SEGMENT_WALL_MS)
        val policy = SegmentCapPolicy()
        policy.onSessionStart(nowMs = 0L)
        assertEquals(4_000L, policy.currentCapMs())
    }

    @Test
    fun firstUncommittedStretchCapsAt4000ms() {
        val policy = SegmentCapPolicy()
        policy.onSessionStart(nowMs = 10_000L)
        assertFalse("one ms under the first cap must not cut", policy.capExceeded(nowMs = 13_999L))
        assertTrue("the first cap fires at exactly 4 000 ms", policy.capExceeded(nowMs = 14_000L))
    }

    @Test
    fun laterStretchesKeepThe15000msCap() {
        val policy = SegmentCapPolicy()
        policy.onSessionStart(nowMs = 0L)
        policy.onCommit(nowMs = 4_000L)          // the first wall-cap commit
        assertEquals(15_000L, policy.currentCapMs())
        assertFalse(policy.capExceeded(nowMs = 18_999L))
        assertTrue(policy.capExceeded(nowMs = 19_000L))
    }

    @Test
    fun aPauseCommitAlsoEndsTheFirstCapWindow() {
        // The 800 ms pause cut still wins when a real pause happens (untouched semantics);
        // once ANY commit has cut the first segment, the later cap governs the next stretch.
        val policy = SegmentCapPolicy()
        policy.onSessionStart(nowMs = 0L)
        policy.onCommit(nowMs = 1_200L)          // VAD pause commit, well before 4 s
        assertEquals(15_000L, policy.currentCapMs())
        assertFalse("the 4 s cap must NOT fire on the second stretch", policy.capExceeded(nowMs = 5_000L))
        assertTrue(policy.capExceeded(nowMs = 16_200L))
    }

    @Test
    fun everyCommitRestartsTheClock() {
        val policy = SegmentCapPolicy()
        policy.onSessionStart(nowMs = 0L)
        policy.onCommit(nowMs = 4_000L)
        policy.onCommit(nowMs = 19_000L)
        assertFalse(policy.capExceeded(nowMs = 33_999L))
        assertTrue(policy.capExceeded(nowMs = 34_000L))
    }

    @Test
    fun aNewSessionResetsToTheFirstCap() {
        // Per-session reset (the RECORDING anchor at FloatingBubbleService onOpen): session 2's
        // first segment gets the 4 s cap again, measured from ITS start.
        val policy = SegmentCapPolicy()
        policy.onSessionStart(nowMs = 0L)
        policy.onCommit(nowMs = 4_000L)
        policy.onCommit(nowMs = 19_000L)
        policy.onSessionStart(nowMs = 60_000L)
        assertEquals(4_000L, policy.currentCapMs())
        assertFalse(policy.capExceeded(nowMs = 63_999L))
        assertTrue(policy.capExceeded(nowMs = 64_000L))
    }

    @Test
    fun capsAreInjectableForTests() {
        val policy = SegmentCapPolicy(firstSegmentCapMs = 100L, laterSegmentCapMs = 200L)
        policy.onSessionStart(nowMs = 0L)
        assertTrue(policy.capExceeded(nowMs = 100L))
        policy.onCommit(nowMs = 100L)
        assertFalse(policy.capExceeded(nowMs = 299L))
        assertTrue(policy.capExceeded(nowMs = 300L))
    }
}
