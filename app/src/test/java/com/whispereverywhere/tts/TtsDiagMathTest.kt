package com.whispereverywhere.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsDiagMathTest {

    @Test fun audio_ms_converts_samples_at_the_track_rate() {
        // 24 000 samples at 24 kHz is exactly one second.
        assertEquals(1000L, TtsDiagMath.audioMs(24_000, 24_000))
        assertEquals(500L, TtsDiagMath.audioMs(12_000, 24_000))
        assertEquals(0L, TtsDiagMath.audioMs(0, 24_000))
    }

    @Test fun audio_ms_is_zero_for_a_nonsense_rate_instead_of_dividing_by_zero() {
        assertEquals(0L, TtsDiagMath.audioMs(24_000, 0))
    }

    @Test fun rtf_is_synthesis_time_over_audio_produced() {
        // The bench figure: 8106 ms of synthesis for 14 050 ms of audio.
        assertEquals(0.577, TtsDiagMath.rtf(8_106, 14_050), 0.001)
        assertEquals(1.0, TtsDiagMath.rtf(3_000, 3_000), 0.0001)
    }

    @Test fun rtf_is_zero_when_no_audio_was_produced() {
        // A callback that yields nothing must not produce Infinity and poison a percentile.
        assertEquals(0.0, TtsDiagMath.rtf(500, 0), 0.0001)
    }

    @Test fun audible_silence_is_wall_time_minus_what_the_hardware_still_rendered() {
        // THE load-bearing formula. Stalled 1846 ms while the track still had 160 ms queued:
        // the user heard 1686 ms of silence, not 1846.
        assertEquals(1_686L, TtsDiagMath.audibleSilenceMs(1_846, 160))
    }

    @Test fun audible_silence_never_goes_negative() {
        // The track can report more rendered than we stalled (coarse HAL head reporting).
        assertEquals(0L, TtsDiagMath.audibleSilenceMs(100, 400))
    }

    @Test fun percentile_picks_the_expected_ranks() {
        val xs = listOf(0.50, 0.55, 0.58, 0.60, 0.95)
        assertEquals(0.58, TtsDiagMath.percentile(xs, 0.50), 0.0001)
        assertEquals(0.95, TtsDiagMath.percentile(xs, 0.95), 0.0001)
        assertEquals(0.50, TtsDiagMath.percentile(xs, 0.0), 0.0001)
    }

    @Test fun percentile_of_empty_is_zero() {
        assertEquals(0.0, TtsDiagMath.percentile(emptyList(), 0.5), 0.0001)
    }

    @Test fun duty_pct_is_speech_over_wall_clock() {
        // 12.5 s of speech across 14.2965 s of wall clock = 87%.
        assertEquals(87, TtsDiagMath.dutyPct(12_500, 14_296))
        assertEquals(100, TtsDiagMath.dutyPct(5_000, 5_000))
        assertEquals(0, TtsDiagMath.dutyPct(5_000, 0))
    }

    @Test fun duty_pct_has_no_upper_clamp_so_over_synthesis_self_identifies() {
        // The real capture (I1): 96 362 ms synthesized against 65 429 ms wall clock — the run
        // ended with audio banked but never played. The old coerceIn(0, 100) rendered this as
        // "gapless" (100) on the same summary line as underMs=9031; the fix must let it read
        // above 100 instead.
        val pct = TtsDiagMath.dutyPct(96_362, 65_429)
        assertTrue("expected > 100, got $pct", pct > 100)
        assertEquals(147, pct)
    }

    @Test fun duty_pct_still_floors_at_zero() {
        // Only the floor is clamped now — a pathological/negative numerator must not read as
        // a negative percentage.
        assertEquals(0, TtsDiagMath.dutyPct(-1_000, 5_000))
    }
}
