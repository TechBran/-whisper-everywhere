package com.whispereverywhere.recording

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SampleMathTest {

    // ---------------- Downmix ----------------

    @Test fun mono_input_passes_through_untouched() {
        val mono = shortArrayOf(1, -2, 3, -4)
        assertArrayEquals(mono, Downmix.toMono(mono, channels = 1))
    }

    @Test fun stereo_downmix_averages_the_pair() {
        // L,R interleaved: (100,200) -> 150; (-100,100) -> 0; (5,6) -> 5 (integer floor is fine).
        val stereo = shortArrayOf(100, 200, -100, 100, 5, 6)
        assertArrayEquals(shortArrayOf(150, 0, 5), Downmix.toMono(stereo, channels = 2))
    }

    @Test fun downmix_of_full_scale_stereo_does_not_overflow() {
        // Short.MAX + Short.MAX averaged in Int space must come back as Short.MAX, not wrap.
        val loud = shortArrayOf(Short.MAX_VALUE, Short.MAX_VALUE, Short.MIN_VALUE, Short.MIN_VALUE)
        assertArrayEquals(shortArrayOf(Short.MAX_VALUE, Short.MIN_VALUE), Downmix.toMono(loud, 2))
    }

    // ---------------- Resampler ----------------

    @Test fun sixteen_k_input_is_the_identity() {
        val pcm = shortArrayOf(10, 20, 30)
        assertArrayEquals(pcm, Resampler.to16k(pcm, srcRate = 16_000))
    }

    @Test fun output_length_matches_the_rate_ratio() {
        // 48 kHz -> 16 kHz is exactly 3:1.
        val out = Resampler.to16k(ShortArray(48_000), srcRate = 48_000)
        assertEquals(16_000, out.size)
        // 44.1 kHz -> 16 kHz: 44_100 / 2.75625; one second in -> one second out (±1 sample).
        val out441 = Resampler.to16k(ShortArray(44_100), srcRate = 44_100)
        assertTrue("expected ~16000, got ${out441.size}", abs(out441.size - 16_000) <= 1)
    }

    @Test fun a_constant_signal_stays_constant_through_resampling() {
        // Linear interpolation between equal values is that value — any deviation is a math bug.
        val dc = ShortArray(44_100) { 1000 }
        Resampler.to16k(dc, 44_100).forEach { assertEquals(1000, it.toInt()) }
    }

    @Test fun a_linear_ramp_resamples_onto_the_same_line() {
        // Values lie on y = x (in source-sample units); after resampling, sample k of the output
        // must sit at y ≈ k * (srcRate/16000), because linear interpolation reproduces lines exactly.
        val ramp = ShortArray(4_800) { it.toShort() }
        val out = Resampler.to16k(ramp, srcRate = 48_000)
        for (k in out.indices) {
            val expected = k * 3.0
            assertTrue("sample $k: ${out[k]} !~ $expected", abs(out[k] - expected) <= 1.0)
        }
    }
}
