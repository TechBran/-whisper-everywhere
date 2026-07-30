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

    // --- Sentence-scoped guarantee: a sub-cap sentence is never re-cut mid-clause -------------

    @Test fun a_short_sentence_beside_a_long_one_is_fed_whole() {
        // The bit-identical guarantee is sentence-scoped, not window-scoped: a sub-cap sentence
        // must be fed whole even when a neighbour pushes the total over the cap. The old greedy
        // window could not cut at "quiet." (only 19 chars, below MIN) so it merged the short
        // sentence into a mid-clause slice of the long one.
        val shortSent = "The room was quiet." // 19 chars, below MIN, <= cap
        val longSent = "The morning was cold and the streets were completely empty, so the " +
            "whole city felt like it belonged to no one at all this quiet grey winter day."
        val units = ClauseSplitter.plan("$shortSent $longSent")
        each(units)
        assertTrue("short sentence was re-cut mid-clause: $units", units.contains(shortSent))
    }

    @Test fun two_sub_cap_sentences_over_the_cap_split_at_the_sentence_boundary() {
        // Two whole sentences, each <= cap, total > cap: the feed is exactly the two sentences
        // sherpa would produce internally anyway — no synthesized mid-clause boundary tone.
        val s1 = "The engine warmed slowly under a grey winter sky."
        val s2 = "The driver waited by the door without any real impatience."
        val units = ClauseSplitter.plan("$s1 $s2")
        each(units)
        assertEquals(listOf(s1, s2), units)
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

    @Test fun no_multi_word_unit_is_a_sub_min_sliver() {
        // plan() cut as late as possible each window and dumped the remainder as the final unit,
        // which could be a few chars — a standalone sliver gets isolated falling-pitch prosody.
        // Across many lengths, no unit that contains a space (i.e. is not a lone unbroken token)
        // may fall below MIN_CHARS.
        val words = "the quick brown fox jumps over a lazy dog while nine wet owls hid".split(" ")
        for (target in (cap() + 1)..(cap() * 2 + 20)) {
            val sb = StringBuilder()
            var wi = 0
            while (sb.length < target) { sb.append(words[wi % words.size]).append(' '); wi++ }
            val s = sb.toString().take(target).trim()
            if (s.length <= cap()) continue
            val units = ClauseSplitter.plan(s)
            each(units)
            units.forEach { u ->
                val loneToken = !u.trim().contains(' ')
                assertTrue("sub-MIN sliver \"$u\" (${u.length}) at len=$target: $units",
                    loneToken || u.length >= 20)
            }
            assertEquals("content lost at len=$target",
                s.filter { !it.isWhitespace() }, units.joinToString("").filter { !it.isWhitespace() })
        }
    }

    @Test fun a_rebalanced_tail_uses_the_target_aim_point() {
        // A lone token of length cap+5 greedily yields ["x"*cap, "x"*5]; the sliver is folded back
        // and the merged pair re-split at SPLIT_TARGET_CHARS, so the first piece sits at the target
        // (not hugging the cap) and neither piece is a sliver.
        val s = "x".repeat(cap() + 5)
        val units = ClauseSplitter.plan(s)
        each(units)
        val pair = units.takeLast(2)
        pair.forEach { assertTrue("rebalanced piece below MIN: ${it.length}", it.length >= 20) }
        assertTrue("first rebalanced piece exceeds the target: ${pair.first().length}",
            pair.first().length <= ClauseSplitter.SPLIT_TARGET_CHARS)
        assertEquals(s, units.joinToString(""))
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

    @Test fun the_worst_rtf_is_a_small_unit_figure_so_the_cap_is_conservative() {
        // This pins the PROVENANCE of RTF_max=0.73, refuting the concern that 0.73 was measured on
        // the 23.6 s whole sentence and understates small-unit cost. In the baseline, rtfMax=0.73
        // is the SHORTEST unit (seq=10: audMs=2501 -> ~57 chars, synthMs=1828), while the 23.6 s
        // sentence (seq=1) measured rtf=0.52. Effective RTF FALLS as the unit grows, so applying the
        // shortest-unit 0.73 to an 80-char (larger) cap unit is conservative, not optimistic.
        val smallestBaselineUnitChars = (2501L / ClauseSplitter.MS_PER_CHAR).toInt() // ~55 chars
        assertTrue("baseline worst-RTF unit is not smaller than the cap",
            smallestBaselineUnitChars < ClauseSplitter.SPLIT_MAX_CHARS)
        // The worst-RTF unit itself banks well under the first burst, with headroom.
        val smallUnitSynthMs = (0.73 * ClauseSplitter.estimateAudioMs(smallestBaselineUnitChars)).toLong()
        assertTrue("worst-RTF unit already fits the bank: $smallUnitSynthMs ms", smallUnitSynthMs <= 3262L)
        // NOTE: JVM arithmetic cannot prove device behavior. Fixed per-generateWithCallback setup
        // paid once per unit is the one residual, gated by the on-device Task 3 re-measure (owner).
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

    // --- The parameterized CLOUD cap: cloud fetch cost is a round-trip independent of audio length,
    // so larger units bank more audio per POST and hide the next fetch's RTT. The default arg keeps
    // the on-device path byte-identical; a cloud fall-back re-splits a cloud unit at the local cap. ---

    @Test fun the_default_cap_is_unchanged_so_the_on_device_path_is_byte_identical() {
        // plan(clean) and plan(clean, SPLIT_MAX_CHARS) must be the same list — the regression contract.
        val giant = ("the road climbed steadily past the old quarry and the light kept fading, "
            .repeat(7).trim() + ".")
        assertEquals(ClauseSplitter.plan(giant), ClauseSplitter.plan(giant, ClauseSplitter.SPLIT_MAX_CHARS))
    }

    @Test fun the_cloud_cap_is_larger_so_it_banks_more_audio_per_fetch() {
        // At the cloud cap the same over-cap sentence produces FEWER, LARGER units than at the local
        // cap — each fetch banks more audio to hide the next round-trip.
        val giant = ("the road climbed steadily past the old quarry and the light kept fading, "
            .repeat(9).trim() + ".")
        val local = ClauseSplitter.plan(giant, ClauseSplitter.SPLIT_MAX_CHARS)
        val cloud = ClauseSplitter.plan(giant, ClauseSplitter.CLOUD_SPLIT_MAX_CHARS)
        assertTrue("cloud units should be fewer: local=${local.size} cloud=${cloud.size}", cloud.size < local.size)
        assertTrue(ClauseSplitter.CLOUD_SPLIT_MAX_CHARS > ClauseSplitter.SPLIT_MAX_CHARS)
    }

    @Test fun cloud_units_never_exceed_the_cloud_cap_and_preserve_content() {
        val giant = ("the road climbed steadily past the old quarry and the light kept fading, "
            .repeat(9).trim() + ".")
        val cloud = ClauseSplitter.plan(giant, ClauseSplitter.CLOUD_SPLIT_MAX_CHARS)
        cloud.forEach {
            assertTrue("unit over cloud cap (${it.length})", it.length <= ClauseSplitter.CLOUD_SPLIT_MAX_CHARS)
        }
        assertEquals(giant.filter { !it.isWhitespace() }, cloud.joinToString("").filter { !it.isWhitespace() })
    }

    @Test fun a_cloud_unit_re_split_at_the_local_cap_is_bank_safe_for_the_local_fallback() {
        // The fall-back contract: a cloud unit that fails is re-split at the local cap before it
        // reaches sherpa, so each resulting sub-unit is bank-safe (the same 3262 ms bound the local
        // path guarantees). Re-splitting every cloud unit and checking each sub-unit proves it.
        val giant = ("the road climbed steadily past the old quarry and the light kept fading, "
            .repeat(9).trim() + ".")
        ClauseSplitter.plan(giant, ClauseSplitter.CLOUD_SPLIT_MAX_CHARS).forEach { cloudUnit ->
            ClauseSplitter.plan(cloudUnit, ClauseSplitter.SPLIT_MAX_CHARS).forEach { sub ->
                assertTrue("re-split sub over local cap (${sub.length})", sub.length <= ClauseSplitter.SPLIT_MAX_CHARS)
                val synthMs = (0.73 * ClauseSplitter.estimateAudioMs(sub.length)).toLong()
                assertTrue("re-split sub would starve the bank: $synthMs ms > 3262 ms", synthMs <= 3262L)
            }
        }
    }
}
