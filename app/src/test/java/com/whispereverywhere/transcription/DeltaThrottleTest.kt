package com.whispereverywhere.transcription

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeltaThrottleTest {

    @Test
    fun firstEmitAlwaysPasses() {
        val throttle = DeltaThrottle(minIntervalMs = 150, now = { 0L })
        assertTrue(throttle.shouldEmit())
    }

    @Test
    fun emitsInsideTheWindowAreSuppressed() {
        var t = 0L
        val throttle = DeltaThrottle(minIntervalMs = 150, now = { t })
        assertTrue(throttle.shouldEmit())
        t = 100
        assertFalse(throttle.shouldEmit())
        t = 149
        assertFalse(throttle.shouldEmit())
    }

    @Test
    fun anEmitAfterTheWindowPasses_andReopensTheWindow() {
        var t = 0L
        val throttle = DeltaThrottle(minIntervalMs = 150, now = { t })
        assertTrue(throttle.shouldEmit())
        t = 150
        assertTrue(throttle.shouldEmit())
        t = 200                                  // only 50 ms after the second emit
        assertFalse(throttle.shouldEmit())
    }

    @Test
    fun theWindowAnchorsOnEmitsNotAttempts_atTheDefaultInterval() {
        // The PRODUCTION construction (LocalWhisperEngine passes only `now`), so the shipped
        // 150 ms default is pinned here too — and the window is measured from the last EMIT, not
        // from the last attempt: the suppressed t=100 attempt must not push the window to t=250.
        var t = 0L
        val throttle = DeltaThrottle(now = { t })
        assertTrue(throttle.shouldEmit())
        t = 100
        assertFalse(throttle.shouldEmit())
        t = 160                                   // 160 ms after the EMIT at t=0
        assertTrue(throttle.shouldEmit())
    }

    @Test
    fun reset_allowsAnImmediateEmit() {
        val throttle = DeltaThrottle(minIntervalMs = 150, now = { 0L })
        assertTrue(throttle.shouldEmit())
        throttle.reset()                          // new segment
        assertTrue(throttle.shouldEmit())         // same clock instant, still passes
    }
}
