package com.whispereverywhere.tts

import org.junit.Assert.assertEquals
import org.junit.Test

class TtsRemainingEstimateTest {
    @Test fun chars_to_ms_uses_the_splitters_constant() {
        assertEquals(45L * 100, TtsRemainingEstimate.ms(100))
        assertEquals(ClauseSplitter.MS_PER_CHAR, TtsRemainingEstimate.ms(1))
    }
    @Test fun negative_chars_clamp_to_zero() {
        assertEquals(0L, TtsRemainingEstimate.ms(-5))
        assertEquals(0L, TtsRemainingEstimate.samples(-5, 24_000))
    }
    @Test fun samples_follow_the_track_rate() {
        // 100 chars = 4 500 ms = 108 000 samples at 24 kHz
        assertEquals(108_000L, TtsRemainingEstimate.samples(100, 24_000))
    }
}
