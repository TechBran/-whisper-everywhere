package com.whispereverywhere

import com.whispereverywhere.util.AudioMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioMathTest {

    @Test
    fun silence_returns_zero() {
        val buf = ByteArray(8) // all zeros = silence
        assertEquals(0, AudioMath.amplitude(buf, buf.size))
    }

    @Test
    fun fullscale_returns_near_max() {
        // Two samples at +32767 (0x7FFF) little-endian: 0xFF, 0x7F
        val buf = byteArrayOf(0xFF.toByte(), 0x7F, 0xFF.toByte(), 0x7F)
        val amp = AudioMath.amplitude(buf, buf.size)
        assertTrue("expected near max, got $amp", amp in 32000..32767)
    }

    @Test
    fun only_reads_validLength() {
        // Buffer larger than valid data; bytes past `length` must be ignored.
        val buf = ByteArray(16)
        buf[0] = 0xFF.toByte(); buf[1] = 0x7F // one loud sample in first 2 bytes
        // length = 2 means only that one sample counts
        val amp = AudioMath.amplitude(buf, 2)
        assertTrue("expected near max, got $amp", amp in 32000..32767)
    }
}
