package com.whispereverywhere.transcription.stream

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PcmRingTest {

    private fun bytes(from: Int, n: Int) = ByteArray(n) { (from + it).toByte() }

    @Test fun lastReturnsTheNewestBytesAcrossTheWrap() {
        val ring = PcmRing(8)
        ring.write(bytes(0, 6))          // 0..5
        ring.write(bytes(6, 6))          // 6..11 — wraps
        assertArrayEquals(bytes(4, 8), ring.last(8))
        assertArrayEquals(bytes(8, 4), ring.last(4))
    }

    @Test fun lastNeverReturnsMoreThanWasWrittenAndIsEvenSized() {
        val ring = PcmRing(8)
        ring.write(bytes(0, 3))
        assertArrayEquals(bytes(1, 2), ring.last(100))   // 3 written; the NEWEST even count (a PCM16 sample is never split)
        assertEquals(0, PcmRing(8).last(4).size)
    }

    @Test fun aWriteLargerThanTheRingKeepsItsTail() {
        val ring = PcmRing(4)
        ring.write(bytes(0, 10))
        assertArrayEquals(bytes(6, 4), ring.last(4))
    }

    @Test fun clearForgetsEverything() {
        val ring = PcmRing(8)
        ring.write(bytes(0, 8))
        ring.clear()
        assertEquals(0, ring.last(8).size)
    }
}
