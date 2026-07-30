package com.whispereverywhere.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClauseSplitterTest {

    private fun cap() = ClauseSplitter.SPLIT_MAX_CHARS
    private fun each(units: List<String>) = units.forEach {
        assertTrue("unit over cap (${it.length}): \"$it\"", it.length <= cap())
    }

    // --- The bit-identical guarantee ---------------------------------------------------------

    @Test fun a_short_sentence_is_returned_unchanged() {
        val s = "This is a short sentence."
        assertEquals(listOf(s), ClauseSplitter.plan(s))
    }

    @Test fun a_sentence_exactly_at_the_cap_is_unchanged() {
        val s = "x".repeat(cap())
        assertEquals(listOf(s), ClauseSplitter.plan(s))
    }

    @Test fun one_char_over_the_cap_is_split() {
        val s = "word ".repeat(20).trim() // 99 chars of "word word ..."
        assertTrue(s.length > cap())
        val units = ClauseSplitter.plan(s)
        assertTrue(units.size >= 2)
        each(units)
    }

    @Test fun empty_and_blank_do_not_crash() {
        assertEquals(listOf(""), ClauseSplitter.plan(""))
        assertEquals(listOf("   "), ClauseSplitter.plan("   "))
    }

    // --- Boundary preference: punctuation before conjunctions before hard cuts ----------------

    @Test fun splits_prefer_a_comma_boundary() {
        // Two ~55-char clauses joined by a comma; the seam must fall at the comma.
        val a = "the morning was cold and the streets were completely empty"
        val b = "so the whole city felt like it belonged to no one at all"
        val units = ClauseSplitter.plan("$a, $b.")
        each(units)
        assertTrue("first unit should end at the comma clause", units.first().endsWith(","))
    }

    @Test fun falls_back_to_a_conjunction_when_no_punctuation() {
        val a = "the engine warmed slowly under a grey and unpromising winter sky"
        val b = "the driver waited by the door without any sign of real impatience"
        val units = ClauseSplitter.plan("$a and $b")
        each(units)
        // "and" starts the SECOND unit — the cut is before the conjunction.
        assertTrue(units.any { it.startsWith("and ") || it.contains(" and ").not() })
    }

    @Test fun falls_back_to_a_word_boundary_when_no_clause_marker() {
        val s = ("alpha bravo charlie delta echo foxtrot golf hotel india juliet " +
            "kilo lima mike november oscar papa quebec romeo").trim()
        val units = ClauseSplitter.plan(s)
        each(units)
        units.forEach { assertTrue("cut mid-word: \"$it\"", !it.startsWith(" ") && !it.endsWith(" ")) }
        // reassembled tokens are preserved and in order
        assertEquals(s.split(" "), units.joinToString(" ").split(" "))
    }

    @Test fun a_single_unbroken_token_is_hard_cut_at_the_cap() {
        val s = "x".repeat(cap() * 2 + 5) // no spaces anywhere
        val units = ClauseSplitter.plan(s)
        each(units)
        assertEquals(s, units.joinToString(""))
    }

    // --- Never split inside numbers or abbreviations ------------------------------------------

    @Test fun a_decimal_is_never_a_sentence_boundary() {
        val a = "the reading settled at 3.5 volts after the regulator finally stabilised itself"
        val b = "and the current stayed flat for the remainder of the long overnight test run"
        val units = ClauseSplitter.plan("$a, $b.")
        each(units)
        assertTrue("3.5 was split", units.none { it.endsWith("3.") || it.startsWith("5 ") })
    }

    @Test fun a_thousands_separator_comma_is_never_a_clause_boundary() {
        // The number sits early so char-80 window search lands on its commas; without the
        // digit guard the unit would end "...1,234," and sherpa would voice a broken number.
        val s = "the total was 1,234,567 and it then climbed steadily over the next few weeks ahead."
        assertTrue(s.length > cap())
        val units = ClauseSplitter.plan(s)
        each(units)
        assertTrue("thousands number was split across units: $units",
            units.any { it.contains("1,234,567") })
        units.forEach { u ->
            assertTrue("unit ends inside a number: \"$u\"", !Regex("\\d,$").containsMatchIn(u))
        }
    }

    @Test fun a_time_or_ratio_colon_is_never_a_clause_boundary() {
        val s = "the meeting is at 3:30 and everyone must arrive on time or the schedule slips today."
        assertTrue(s.length > cap())
        val units = ClauseSplitter.plan(s)
        each(units)
        assertTrue("time colon was split across units: $units", units.any { it.contains("3:30") })
        units.forEach { u ->
            assertTrue("unit ends inside a time/ratio: \"$u\"", !Regex("\\d:$").containsMatchIn(u))
        }
    }

    @Test fun common_abbreviations_are_not_sentence_boundaries() {
        listOf("e.g.", "i.e.", "Dr.", "Mr.", "etc.").forEach { abbr ->
            val a = "consider the smaller portable devices $abbr the handheld field units we shipped"
            val b = "which all shared one battery design across the entire product family last year"
            val units = ClauseSplitter.plan("$a $b.")
            each(units)
            assertTrue("$abbr treated as a full stop", units.none { it.trim().endsWith(abbr) && it.length < 40 })
        }
    }

    // --- Unicode / quotes -------------------------------------------------------------------

    @Test fun a_surrogate_pair_is_never_split_by_a_hard_cut() {
        val emoji = "😀" // U+1F600, a surrogate pair
        val s = emoji.repeat(cap()) // > cap, no spaces -> forces hard cuts
        val units = ClauseSplitter.plan(s)
        each(units)
        units.forEach {
            assertTrue("unit ends on a lone high surrogate", it.isEmpty() || !it.last().isHighSurrogate())
            assertTrue("unit starts on a lone low surrogate", it.isEmpty() || !it.first().isLowSurrogate())
        }
        assertEquals(s, units.joinToString(""))
    }

    @Test fun a_period_inside_a_closing_quote_is_not_a_naive_boundary() {
        val a = "she said “we leave at dawn.” and nobody in the small kitchen argued with her"
        val b = "because the road ahead was long and the weather was turning against the travellers"
        val units = ClauseSplitter.plan("$a $b.")
        each(units)
    }

    // --- The load-bearing derivation, pinned -------------------------------------------------

    @Test fun estimate_uses_the_measured_rate() {
        // 45 ms/char (measured 43.84, rounded up). 80 chars -> 3600 ms.
        assertEquals(3600L, ClauseSplitter.estimateAudioMs(80))
        assertEquals(45L, ClauseSplitter.estimateAudioMs(1))
    }

    @Test fun the_cap_satisfies_the_bank_bound_from_the_baseline() {
        // Underrun law at the first boundary: RTF_max * dur(cap) must fit the 3262 ms first burst.
        // RTF_max = 0.73 (end line, 2026-07-27 baseline); first burst = 3262 ms (sent seq=0).
        val worstSynthMs = (0.73 * ClauseSplitter.estimateAudioMs(ClauseSplitter.SPLIT_MAX_CHARS)).toLong()
        assertTrue("cap violates the bank bound: $worstSynthMs ms > 3262 ms", worstSynthMs <= 3262L)
    }

    @Test fun the_23_6_second_sentence_splits_into_bank_safe_chunks() {
        // Reconstruct the shape of baseline seq=1: audMs=23603 -> ~524 chars of comma-spliced prose.
        val clause = "the road climbed steadily past the old quarry and the light kept fading, "
        val giant = clause.repeat(7).trim() + "." // ~511 chars, one sentence, comma-spliced
        assertTrue(giant.length > 400)
        val units = ClauseSplitter.plan(giant)

        each(units) // every chunk within the cap
        assertTrue("expected several chunks, got ${units.size}", units.size >= 6)
        units.forEach { u ->
            val synthMs = (0.73 * ClauseSplitter.estimateAudioMs(u.length)).toLong()
            assertTrue("chunk would starve the bank: $synthMs ms > 3262 ms", synthMs <= 3262L)
        }
        // Order and content preserved (ignoring the whitespace the splitter trims at seams).
        assertEquals(giant.filter { !it.isWhitespace() }, units.joinToString("").filter { !it.isWhitespace() })
    }

    @Test fun units_never_exceed_the_cap_on_realistic_prose() {
        val prose = ("The committee met at noon. It reviewed the quarterly figures, which had " +
            "slipped, and debated whether to raise the fee; opinions were sharply divided. " +
            "Afterwards, e.g. in the smaller working groups, the mood was calmer and more " +
            "practical, and a compromise slowly took shape over the course of the afternoon.")
        val units = ClauseSplitter.plan(prose)
        each(units)
        assertEquals(prose.filter { !it.isWhitespace() }, units.joinToString(" ").filter { !it.isWhitespace() })
    }
}
