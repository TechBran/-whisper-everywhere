package com.whispereverywhere.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextJoinTest {

    // --- needsSpace ---------------------------------------------------------

    @Test fun two_alphanumeric_runs_need_a_space() {
        assertTrue(TextJoin.needsSpace("Hello", "world"))
    }

    @Test fun an_already_trailing_space_needs_none() {
        assertFalse(TextJoin.needsSpace("Hello ", "world"))
    }

    @Test fun an_already_leading_space_needs_none() {
        assertFalse(TextJoin.needsSpace("Hello", " world"))
    }

    @Test fun closing_punctuation_attaches_without_a_space() {
        for (p in listOf(".", ",", "!", "?", ";", ":", ")", "]", "}")) {
            assertFalse("chunk starting '$p' must attach", TextJoin.needsSpace("Hello", p))
        }
    }

    @Test fun an_opening_bracket_still_needs_a_space() {
        assertTrue(TextJoin.needsSpace("Hello", "(note)"))
    }

    // --- non-spacing scripts: the inverse of a melt (a STRAY space) --------------

    @Test fun two_han_characters_need_no_space() {
        // '你好' + '世界' must stay '你好世界' — CJK is not space-delimited.
        assertFalse(TextJoin.needsSpace("你好", "世界"))
        assertEquals("你好世界", TextJoin.join("你好", "世界"))
        assertEquals("你好世界", TextJoin.assemble(listOf("你好", "世界")))
    }

    @Test fun japanese_kana_needs_no_space() {
        assertFalse(TextJoin.needsSpace("こんにち", "は"))       // hiragana
        assertFalse(TextJoin.needsSpace("カタ", "カナ"))         // katakana
    }

    @Test fun thai_needs_no_space() {
        assertFalse(TextJoin.needsSpace("สวัส", "ดี"))
    }

    @Test fun a_mixed_latin_cjk_boundary_still_spaces() {
        // Only a BOTH-sides non-spacing boundary is suppressed; Latin↔CJK keeps the space.
        assertTrue(TextJoin.needsSpace("hello", "世界"))
        assertTrue(TextJoin.needsSpace("世界", "hello"))
    }

    // --- pad: the clipboard/paste both-boundary guard ---------------------------

    @Test fun pad_leads_a_space_when_appending_to_an_alnum_field() {
        // The Facebook/Keep paste melt in miniature: existing "Hello", segment "there".
        assertEquals(" there", TextJoin.pad("Hello", "there", ""))
    }

    @Test fun pad_adds_nothing_against_a_trailing_space_or_empty_field() {
        assertEquals("there", TextJoin.pad("Hello ", "there", ""))
        assertEquals("there", TextJoin.pad("", "there", ""))
    }

    @Test fun pad_guards_both_boundaries_for_a_mid_caret_paste() {
        assertEquals(" big ", TextJoin.pad("Hello", "big", "there"))
    }

    @Test fun pad_attaches_closing_punctuation_without_a_lead_space() {
        assertEquals(".", TextJoin.pad("Hello", ".", ""))
    }

    @Test fun pad_does_not_space_a_cjk_paste_boundary() {
        assertEquals("世界", TextJoin.pad("你好", "世界", ""))
    }

    @Test fun empty_either_side_needs_no_space() {
        assertFalse(TextJoin.needsSpace("", "world"))
        assertFalse(TextJoin.needsSpace("Hello", ""))
    }

    // --- join ---------------------------------------------------------------

    @Test fun join_inserts_exactly_one_space_between_words() {
        assertEquals("Hello world", TextJoin.join("Hello", "world"))
    }

    @Test fun join_never_doubles_a_space() {
        assertEquals("Hello world", TextJoin.join("Hello ", "world"))
        assertEquals("Hello world", TextJoin.join("Hello", " world"))
    }

    @Test fun join_attaches_closing_punctuation() {
        assertEquals("Wait.", TextJoin.join("Wait", "."))
    }

    // --- assemble -----------------------------------------------------------

    @Test fun assemble_trims_drops_blanks_and_single_spaces() {
        // Pins RecordingStoreTest's "one three" (blank dropped, single space).
        assertEquals("one three", TextJoin.assemble(listOf("one ", "  ", " three")))
    }

    @Test fun assemble_would_fail_under_a_bare_concatenation() {
        // The Gemini melt bug in miniature: bare joinToString("") would give "Helloworld".
        assertEquals("Hello world", TextJoin.assemble(listOf("Hello", "world")))
    }

    @Test fun assemble_attaches_a_punctuation_chunk() {
        // Differs from an unconditional " " join, which would give "hello .".
        assertEquals("hello.", TextJoin.assemble(listOf("hello", ".")))
    }

    // --- no-op-safety proof for pre-spaced token streams (Soniox) -----------

    @Test fun join_is_a_no_op_over_a_pre_spaced_token_stream() {
        // Soniox emits its own spacing (space tokens and/or leading-space tokens). A defensive
        // reduce(join) must neither strip nor double it — this pins that guarantee so Soniox's
        // existing joinToString("") stays provably safe.
        val tokens = listOf("Hello", " ", "world")   // dedicated space token
        assertEquals("Hello world", tokens.reduce { a, b -> TextJoin.join(a, b) })
        val leading = listOf("Hello", " are", " you")  // leading-space tokens
        assertEquals("Hello are you", leading.reduce { a, b -> TextJoin.join(a, b) })
    }

    // --- the melt invariant, property-style ---------------------------------

    @Test fun melt_invariant_holds_across_generated_pairs() {
        val shapes = listOf("word", "cat", "a", ".", ",", "!", ")", "]", "(", "word ", " word")
        for (a in shapes) for (b in shapes) {
            val na = TextJoin.normalize(a)
            val nb = TextJoin.normalize(b)
            if (na.isEmpty() || nb.isEmpty()) continue
            val joined = TextJoin.assemble(listOf(na, nb))
            // No double space anywhere.
            assertFalse("double space in '$joined' from '$a'+'$b'", joined.contains("  "))
            // No two alphanumerics touching across the original boundary.
            val boundary = joined.indexOf(nb.first(), na.length - 1)
            if (na.last().isLetterOrDigit() && nb.first().isLetterOrDigit()) {
                assertTrue("melt in '$joined' from '$a'+'$b'", joined.contains("${na.last()} ${nb.first()}"))
            }
            assertTrue(boundary >= 0)
        }
    }

    // --- accumulation equivalence (W2 final-only commit) ------------------------
    // The accumulating window joins segments ONE AT A TIME as they resolve; the final delivery
    // reads the whole thing at once. These pin that the two shapes produce the same string —
    // N segments folded through join() == assemble() of the whole list — so final-only commit
    // delivers character-for-character what per-segment injection used to type.

    private fun incrementalJoin(segments: List<String>): String =
        segments.fold("") { acc, seg ->
            val n = TextJoin.normalize(seg)
            if (n.isEmpty()) acc else TextJoin.join(acc, n)
        }

    @Test fun incremental_join_equals_assemble_at_once() {
        val segments = listOf("Hello world", "this is a test", ".", "Right", "?", "OK then")
        assertEquals(TextJoin.assemble(segments), incrementalJoin(segments))
    }

    @Test fun incremental_join_equals_assemble_with_blanks_and_cjk() {
        val segments = listOf("你好", "世界", "  ", "hello", "", "world", "!")
        assertEquals(TextJoin.assemble(segments), incrementalJoin(segments))
    }

    @Test fun accumulation_equivalence_holds_across_generated_segment_lists() {
        val shapes = listOf("word", "two words", ".", ",", ")", "(", "你好", " padded ", "a")
        for (a in shapes) for (b in shapes) for (c in shapes) {
            val segs = listOf(a, b, c)
            assertEquals("segments=$segs", TextJoin.assemble(segs), incrementalJoin(segs))
        }
    }
}
