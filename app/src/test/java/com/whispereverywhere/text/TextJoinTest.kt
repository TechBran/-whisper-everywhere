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
}
