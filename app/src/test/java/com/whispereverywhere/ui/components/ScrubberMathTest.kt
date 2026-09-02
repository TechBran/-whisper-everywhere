package com.whispereverywhere.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/** The scrubber's geometry, pure (4.3.1 C): the bar spans the ESTIMATED read, seeks stay in the bank. */
class ScrubberMathTest {
    @Test fun the_bar_spans_the_larger_of_estimate_and_bank() {
        assertEquals(1_000L, ScrubberMath.span(available = 400, estimatedTotal = 1_000))
        assertEquals(1_200L, ScrubberMath.span(available = 1_200, estimatedTotal = 1_000)) // estimate ran short
        assertEquals(0L, ScrubberMath.span(available = 0, estimatedTotal = 0))
    }
    @Test fun fractions_are_clamped_and_zero_span_is_zero() {
        assertEquals(0.4f, ScrubberMath.frac(400, 1_000), 1e-6f)
        assertEquals(1f, ScrubberMath.frac(1_500, 1_000), 1e-6f)
        assertEquals(0f, ScrubberMath.frac(400, 0), 1e-6f)
    }
    @Test fun a_seek_on_the_bar_is_rebased_to_the_synthesized_audio() {
        // Bar spans 1000, bank holds 400: the bar's 0.2 is the bank's 0.5; past the bank clamps to 1.
        assertEquals(0.5f, ScrubberMath.seekFracOfSynthesized(barFrac = 0.2f, available = 400, span = 1_000), 1e-6f)
        assertEquals(1f, ScrubberMath.seekFracOfSynthesized(barFrac = 0.9f, available = 400, span = 1_000), 1e-6f)
        assertEquals(0f, ScrubberMath.seekFracOfSynthesized(barFrac = 0.5f, available = 0, span = 1_000), 1e-6f)
        // When the bank IS the bar (done), the mapping is identity.
        assertEquals(0.7f, ScrubberMath.seekFracOfSynthesized(barFrac = 0.7f, available = 1_000, span = 1_000), 1e-6f)
    }
}
