package com.whispereverywhere

import com.whispereverywhere.util.AudioMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertArrayEquals
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

    @Test
    fun pcm16ToFloat_zero_is_zero() {
        val floats = AudioMath.pcm16ToFloat(byteArrayOf(0x00, 0x00))
        assertArrayEquals(floatArrayOf(0f), floats, 0f)
    }

    @Test
    fun pcm16ToFloat_positive_fullscale() {
        // +32767 (0x7FFF) little-endian -> 32767/32768 = 0.99997
        val floats = AudioMath.pcm16ToFloat(byteArrayOf(0xFF.toByte(), 0x7F))
        assertEquals(1, floats.size)
        assertEquals(0.99996948f, floats[0], 1e-6f)
    }

    @Test
    fun pcm16ToFloat_negative_fullscale_is_minus_one() {
        // -32768 (0x8000) little-endian -> -32768/32768 = -1.0 exactly
        val floats = AudioMath.pcm16ToFloat(byteArrayOf(0x00, 0x80.toByte()))
        assertArrayEquals(floatArrayOf(-1f), floats, 0f)
    }

    @Test
    fun pcm16ToFloat_negative_sample() {
        // -1 (0xFFFF) little-endian -> -1/32768 = -0.000030517578
        val floats = AudioMath.pcm16ToFloat(byteArrayOf(0xFF.toByte(), 0xFF.toByte()))
        assertEquals(1, floats.size)
        assertEquals(-3.0517578e-5f, floats[0], 1e-9f)
    }

    @Test
    fun pcm16ToFloat_multiple_known_samples() {
        // Samples: 0 (0x0000), +16384 (0x4000), -16384 (0xC000)
        val bytes = byteArrayOf(
            0x00, 0x00,          // 0
            0x00, 0x40,          // +16384 -> 0.5
            0x00, 0xC0.toByte(), // -16384 -> -0.5
        )
        val floats = AudioMath.pcm16ToFloat(bytes)
        assertArrayEquals(floatArrayOf(0f, 0.5f, -0.5f), floats, 0f)
    }

    @Test
    fun pcm16ToFloat_odd_length_drops_trailing_byte() {
        // 3 bytes -> one full sample (+16384 = 0.5) + one dangling byte ignored
        val floats = AudioMath.pcm16ToFloat(byteArrayOf(0x00, 0x40, 0x11))
        assertArrayEquals(floatArrayOf(0.5f), floats, 0f)
    }

    @Test
    fun pcm16ToFloat_empty_is_empty() {
        assertEquals(0, AudioMath.pcm16ToFloat(ByteArray(0)).size)
    }

    @Test
    fun pcm16ToFloat_single_byte_is_empty() {
        // odd-length guard: a lone byte is not a full sample
        assertEquals(0, AudioMath.pcm16ToFloat(byteArrayOf(0x42)).size)
    }

    @Test fun peak_is_the_largest_absolute_sample_normalised() {
        // PCM16 LE. 0x7FFF = 32767 -> ~1.0
        val pcm = byteArrayOf(0x00, 0x00, 0xFF.toByte(), 0x7F)
        assertEquals(1.0f, AudioMath.peak(pcm), 0.001f)
    }

    @Test fun peak_of_silence_is_zero() {
        assertEquals(0.0f, AudioMath.peak(ByteArray(64)), 0.0001f)
    }

    @Test fun peak_of_empty_is_zero_not_a_crash() {
        assertEquals(0.0f, AudioMath.peak(ByteArray(0)), 0.0001f)
    }

    @Test fun peak_handles_a_trailing_odd_byte_without_throwing() {
        // AudioRecord can hand back an odd length; a naive stride-2 read would run off the end.
        assertEquals(0.5f, AudioMath.peak(byteArrayOf(0x00, 0x40, 0x11)), 0.01f)
    }

    @Test fun peak_treats_negative_full_scale_as_full_scale() {
        // -32768 has no positive counterpart; abs() of it overflows in Int if done naively.
        val pcm = byteArrayOf(0x00, 0x80.toByte())
        assertTrue(AudioMath.peak(pcm) > 0.99f)
    }
}
