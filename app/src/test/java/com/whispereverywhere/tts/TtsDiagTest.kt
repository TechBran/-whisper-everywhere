package com.whispereverywhere.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsDiagTest {

    @Test fun every_line_starts_with_the_grep_prefix_and_a_kind() {
        assertTrue(TtsDiag.open(1, 7744, 1, 250, 24_000).startsWith("TTSDIAG open "))
        assertTrue(TtsDiag.sent(1, 0, 24_000, 1000, 577).startsWith("TTSDIAG sent "))
        assertTrue(TtsDiag.play(1, 0, 1200).startsWith("TTSDIAG play "))
        assertTrue(TtsDiag.under(1, 3, 10_240, 1846, 160, 1).startsWith("TTSDIAG under "))
        assertTrue(
            TtsDiag.end(1, 1882, 2, 2400, 1800, 12_500, 14_296, listOf(0.55, 0.58), 3)
                .startsWith("TTSDIAG end "),
        )
    }

    @Test fun no_line_contains_a_comma_so_the_format_stays_splittable() {
        // Space-separated key=value only. A comma would break naive parsing of the paste-back.
        val lines = listOf(
            TtsDiag.open(1, 7744, 1, 250, 24_000),
            TtsDiag.sent(1, 0, 24_000, 1000, 577),
            TtsDiag.play(1, 0, 1200),
            TtsDiag.under(1, 3, 10_240, 1846, 160, 1),
            TtsDiag.end(1, 1882, 2, 2400, 1800, 12_500, 14_296, listOf(0.55, 0.58), 3),
        )
        lines.forEach { assertTrue("comma in: $it", !it.contains(",")) }
    }

    @Test fun sent_line_reports_the_derived_per_sentence_rtf() {
        // 577 ms of synthesis for 1000 ms of audio => rtf 0.58 at two decimals.
        val line = TtsDiag.sent(gen = 4, seq = 2, samples = 24_000, audMs = 1000, synthMs = 577)
        assertTrue(line, line.contains("gen=4"))
        assertTrue(line, line.contains("seq=2"))
        assertTrue(line, line.contains("audMs=1000"))
        assertTrue(line, line.contains("synthMs=577"))
        assertTrue(line, line.contains("rtf=0.58"))
    }

    @Test fun under_line_reports_audible_silence_not_just_wall_time() {
        // Stalled 1846 ms with 160 ms still queued in the track => 1686 ms actually heard.
        val line = TtsDiag.under(gen = 7, seq = 3, atMs = 10_240, wallMs = 1846, renderMs = 160, hwUnderD = 1)
        assertTrue(line, line.contains("wallMs=1846"))
        assertTrue(line, line.contains("renderMs=160"))
        assertTrue(line, line.contains("audibleMs=1686"))
        assertTrue(line, line.contains("hwUnderD=1"))
    }

    @Test fun end_line_summarises_percentiles_and_duty() {
        val rtfs = listOf(0.50, 0.55, 0.58, 0.60, 0.95)
        val line = TtsDiag.end(
            gen = 1, ttfwMs = 1882, underN = 2, underMs = 2400, maxGapMs = 1800,
            audioMs = 12_500, wallMs = 14_296, rtfs = rtfs, hwUnderTotal = 3,
        )
        assertTrue(line, line.contains("ttfwMs=1882"))
        assertTrue(line, line.contains("underN=2"))
        assertTrue(line, line.contains("underMs=2400"))
        assertTrue(line, line.contains("maxGapMs=1800"))
        assertTrue(line, line.contains("dutyPct=87"))
        assertTrue(line, line.contains("rtfP50=0.58"))
        assertTrue(line, line.contains("rtfP95=0.95"))
        assertTrue(line, line.contains("rtfMax=0.95"))
        assertTrue(line, line.contains("hwUnder=3"))
    }

    @Test fun end_line_survives_an_utterance_with_no_sentences() {
        // Cancelled before the first callback: must not throw, must not print NaN.
        val line = TtsDiag.end(1, 0, 0, 0, 0, 0, 0, emptyList(), 0)
        assertTrue(line, !line.contains("NaN"))
        assertTrue(line, !line.contains("Infinity"))
        assertTrue(line, line.contains("rtfP50=0.00"))
    }

    @Test fun rtfs_are_sorted_internally_so_callers_need_not_be_careful() {
        val unsorted = listOf(0.95, 0.50, 0.58)
        val line = TtsDiag.end(1, 0, 0, 0, 0, 1000, 1000, unsorted, 0)
        assertTrue(line, line.contains("rtfP50=0.58"))
        assertTrue(line, line.contains("rtfMax=0.95"))
    }

    @Test fun tag_is_the_existing_engine_tag() {
        assertEquals("WE-TTS", TtsDiag.TAG)
    }
}
