package com.whispereverywhere.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InjectionAnchorTest {

    private fun place(a: InjectionAnchor, field: String, seg: String): InjectionAnchor.Placement {
        val p = a.plan(field, seg)
        a.commit(p)
        return p
    }

    // --- start --------------------------------------------------------------

    @Test fun start_on_empty_field_anchors_at_zero() {
        val a = InjectionAnchor()
        a.start("", -1, -1)
        assertEquals(0, a.anchor)
    }

    @Test fun start_with_no_selection_anchors_at_end() {
        val a = InjectionAnchor()
        a.start("Hello", -1, -1)
        assertEquals(5, a.anchor)
    }

    @Test fun start_with_a_caret_anchors_there_for_deliberate_mid_text() {
        val a = InjectionAnchor()
        a.start("Hello there", 5, 5)   // caret after "Hello"
        assertEquals(5, a.anchor)
    }

    // --- append flow (the common case) --------------------------------------

    @Test fun successive_segments_append_with_one_space_each() {
        val a = InjectionAnchor()
        a.start("", -1, -1)
        assertEquals("Hello", place(a, "", "Hello").newFieldText)
        assertEquals("Hello world", place(a, "Hello", "world").newFieldText)
        assertEquals("Hello world again", place(a, "Hello world", "again").newFieldText)
    }

    @Test fun the_pinned_cursor_sits_at_the_end_of_what_we_wrote() {
        val a = InjectionAnchor()
        a.start("Hello", -1, -1)
        val p = place(a, "Hello", "world")
        assertEquals("Hello world".length, p.newSelection)
    }

    // --- THE bug: app moved the cursor between segments ---------------------

    @Test fun a_moved_live_cursor_is_ignored_when_the_field_is_unchanged() {
        // plan takes NO live-cursor argument by design — the app can teleport the caret to 0 or
        // mid-text and we still append at OUR anchor. This is the fix.
        val a = InjectionAnchor()
        a.start("Hello", -1, -1)
        place(a, "Hello", "there")           // field now "Hello there", anchor at end
        val p = place(a, "Hello there", "friend")
        assertEquals("Hello there friend", p.newFieldText)
    }

    // --- both-boundary guard (mid-text) -------------------------------------

    @Test fun a_mid_text_insert_spaces_both_sides() {
        val a = InjectionAnchor()
        a.start("Hello there", 5, 5)          // caret after "Hello"
        val p = place(a, "Hello there", "big")
        assertEquals("Hello big there", p.newFieldText)
    }

    @Test fun a_mid_text_insert_creates_no_double_space() {
        val a = InjectionAnchor()
        a.start("Hello  there".take(6), 6, 6) // field "Hello " caret at 6 (trailing space)
        val p = place(a, "Hello ", "big")
        assertEquals("Hello big", p.newFieldText)
        assertTrue(!p.newFieldText.contains("  "))
    }

    @Test fun a_closing_punctuation_segment_attaches_clean() {
        val a = InjectionAnchor()
        a.start("Hello", -1, -1)
        val p = place(a, "Hello", ".")
        assertEquals("Hello.", p.newFieldText)
    }

    // --- one-shot replace ---------------------------------------------------

    @Test fun a_start_selection_range_is_replaced_exactly_once() {
        val a = InjectionAnchor()
        a.start("delete me keep", 0, 9)       // "delete me" selected
        val first = place(a, "delete me keep", "new")
        assertEquals("new keep", first.newFieldText)
        // Second segment must NOT delete anything — replace was one-shot.
        val second = place(a, "new keep", "again")
        assertEquals("new again keep", second.newFieldText)
    }

    @Test fun no_start_selection_means_no_deletion_ever() {
        val a = InjectionAnchor()
        a.start("keep all", 4, 4)             // caret only, no range (word boundary after "keep")
        val p = place(a, "keep all", "x")
        assertTrue("nothing deleted", p.newFieldText.contains("keep"))
        assertTrue(p.newFieldText.contains("all"))
    }

    // --- re-sync on user edit: NEVER deletes --------------------------------

    @Test fun a_user_edit_after_the_anchor_keeps_the_anchor() {
        val a = InjectionAnchor()
        a.start("abc", 3, 3)                  // anchor at end
        place(a, "abc", "d")                  // -> "abc d", anchor 5
        // User types " zzz" at the very end (after our anchor).
        val p = place(a, "abc d zzz", "e")
        assertTrue("user text intact", p.newFieldText.contains("zzz"))
        assertTrue(p.newFieldText.contains("abc"))
        assertTrue("nothing deleted", p.newFieldText.length >= "abc d zzz".length + 1)
    }

    @Test fun a_user_edit_before_the_anchor_shifts_the_anchor_forward() {
        val a = InjectionAnchor()
        a.start("abc", 3, 3)                  // anchor at end of "abc"
        // User prepends "X " before dictating again -> field "X abc".
        val p = place(a, "X abc", "d")
        assertEquals("X abc d", p.newFieldText)
    }

    @Test fun an_unrecognizable_edit_falls_back_to_the_end_and_never_deletes() {
        val a = InjectionAnchor()
        a.start("hello world", 11, 11)
        place(a, "hello world", "one")        // -> "hello world one"
        // User rewrites the middle wholesale (mention expansion): straddles the anchor.
        val edited = "hello @SomeName replaced entirely"
        val p = place(a, edited, "two")
        assertTrue("appended, nothing deleted", p.newFieldText.startsWith(edited))
        assertTrue(p.newFieldText.endsWith("two"))
    }

    // --- empty segment ------------------------------------------------------

    @Test fun an_empty_segment_is_a_no_op_that_leaves_the_field_and_anchor() {
        val a = InjectionAnchor()
        a.start("Hello", -1, -1)
        val p = place(a, "Hello", "   ")
        assertEquals("Hello", p.newFieldText)
        assertEquals(5, a.anchor)
    }

    // --- the property: placement never deletes, never melts -----------------

    @Test fun sequential_placement_never_shrinks_and_never_melts() {
        val segs = listOf("Hello", "world", ".", "again", ")", "more")
        val a = InjectionAnchor()
        a.start("", -1, -1)
        var field = ""
        for (s in segs) {
            val before = field.length
            val p = place(a, field, s)
            assertTrue("never shrinks", p.newFieldText.length >= before)
            assertTrue("no double space", !p.newFieldText.contains("  "))
            field = p.newFieldText
        }
        assertEquals("Hello world. again) more", field)
    }
}
