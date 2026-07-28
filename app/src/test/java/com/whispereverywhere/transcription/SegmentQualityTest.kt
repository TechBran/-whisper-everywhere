package com.whispereverywhere.transcription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentQualityTest {

    @Test fun ordinary_speech_is_accepted() {
        assertEquals(
            QualityVerdict.ACCEPT,
            SegmentQuality.assess("The quick brown fox jumps over the lazy dog.", voicedMs = 3000),
        )
    }

    @Test fun a_degenerate_repetition_loop_is_rejected() {
        // The failure this app actually hit on a 10-minute YouTube capture on 2026-07-18.
        val looped = "thank you for watching ".repeat(40)
        assertEquals(QualityVerdict.REJECT_REPETITION, SegmentQuality.assess(looped, voicedMs = 8000))
    }

    @Test fun compression_ratio_separates_looped_from_natural_text() {
        // Ported from whisper.cpp's own heuristic — the gate it trips on internally, so it is
        // pre-calibrated on exactly this failure mode.
        val natural = SegmentQuality.compressionRatio(
            "She sells sea shells by the sea shore and the shells she sells are surely seashells.",
        )
        val looped = SegmentQuality.compressionRatio("ha ".repeat(200))
        assertTrue("natural=$natural must be below the 2.4 gate", natural < 2.4)
        assertTrue("looped=$looped must be above the 2.4 gate", looped > 2.4)
    }

    @Test fun an_implausible_word_rate_is_rejected() {
        // 40 words in 1 second of voiced audio is not speech.
        val words = (1..40).joinToString(" ") { "word$it" }
        assertEquals(QualityVerdict.REJECT_IMPLAUSIBLE, SegmentQuality.assess(words, voicedMs = 1000))
    }

    @Test fun a_short_utterance_is_not_penalised_for_being_short() {
        // "Yes." in 400 ms is 2.5 w/s — well within range and must not be rejected.
        assertEquals(QualityVerdict.ACCEPT, SegmentQuality.assess("Yes.", voicedMs = 400))
    }

    @Test fun blank_text_is_accepted_because_emptiness_is_not_a_quality_problem() {
        // The orderer classifies empties; this gate must not also claim them.
        assertEquals(QualityVerdict.ACCEPT, SegmentQuality.assess("", voicedMs = 1000))
    }

    @Test fun zero_voiced_ms_does_not_divide_by_zero() {
        assertEquals(QualityVerdict.ACCEPT, SegmentQuality.assess("hello", voicedMs = 0))
    }

    @Test fun a_long_legitimate_sentence_is_not_mistaken_for_a_loop() {
        val real = "In the morning we walked along the river and talked about the plans " +
            "for the summer, which seemed impossibly far away at the time."
        assertEquals(QualityVerdict.ACCEPT, SegmentQuality.assess(real, voicedMs = 9000))
    }
}
