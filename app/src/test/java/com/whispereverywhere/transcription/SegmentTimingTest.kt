package com.whispereverywhere.transcription

import org.junit.Assert.assertEquals
import org.junit.Test

class SegmentTimingTest {

    @Test
    fun lineMatchesTheGreppableFormatExactly() {
        assertEquals(
            "segment-timing: audio=4000 transcribe=6000 rtf=1.50",
            SegmentTiming.line(audioMs = 4_000L, transcribeMs = 6_000L),
        )
    }

    @Test
    fun rtfBelowOneMeansFasterThanRealTime() {
        assertEquals(
            "segment-timing: audio=15000 transcribe=3000 rtf=0.20",
            SegmentTiming.line(audioMs = 15_000L, transcribeMs = 3_000L),
        )
    }

    @Test
    fun rtfRoundsToTwoDecimals() {
        assertEquals(
            "segment-timing: audio=3000 transcribe=1000 rtf=0.33",
            SegmentTiming.line(audioMs = 3_000L, transcribeMs = 1_000L),
        )
    }

    @Test
    fun zeroAudioNeverDividesByZero() {
        // A degenerate commit (near-zero samples) must report a parseable line, not NaN/crash.
        assertEquals(
            "segment-timing: audio=0 transcribe=500 rtf=0.00",
            SegmentTiming.line(audioMs = 0L, transcribeMs = 500L),
        )
    }

    @Test
    fun audioMsConvertsSampleCountAt16kHz() {
        assertEquals(4_000L, SegmentTiming.audioMs(sampleCount = 64_000))
        assertEquals(1_000L, SegmentTiming.audioMs(sampleCount = 16_000))
        assertEquals(0L, SegmentTiming.audioMs(sampleCount = 0))
    }

    @Test fun formatIsLocaleIndependent() {
        val prior = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY) // comma decimal separator
            assertEquals(
                "segment-timing: audio=4000 transcribe=6000 rtf=1.50",
                SegmentTiming.line(audioMs = 4_000L, transcribeMs = 6_000L),
            )
        } finally {
            java.util.Locale.setDefault(prior)
        }
    }

    @Test fun rtfRoundsHalfUpNotTruncated() {
        // 2000/3000 = 0.666... -> 0.67 under HALF_UP; 0.66 under truncation.
        assertEquals(
            "segment-timing: audio=3000 transcribe=2000 rtf=0.67",
            SegmentTiming.line(audioMs = 3_000L, transcribeMs = 2_000L),
        )
    }
}
