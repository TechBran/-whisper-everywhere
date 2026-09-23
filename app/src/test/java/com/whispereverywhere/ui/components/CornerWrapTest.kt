package com.whispereverywhere.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The corner wrap's pure half (owner ruling 2026-09-22: the corner controls each "hold their own
 * little bubble ... and the text should still just flow around it to the top"). [CornerWrap.wrap]
 * learns where the opening lines END from a layout indented at both corners; [CornerWrap.plan]
 * turns those ends into hard breaks and the reach of the left margin. What must hold:
 *  - every wrapped line becomes its own paragraph, so the TextView cannot re-flow it back under a
 *    disc — a break after each wrapped line that does not already end one;
 *  - the leading margin covers exactly those paragraphs and ends on a paragraph boundary (just
 *    past a break, or at the end of the text), since a paragraph style cut mid-paragraph is not
 *    a margin Android applies.
 */
class CornerWrapTest {

    /** Apply a plan the way [CornerWrap.wrap] does, on a plain String. */
    private fun apply(text: String, plan: CornerWrap.Plan): String {
        val sb = StringBuilder(text)
        for (at in plan.insertAt.asReversed()) sb.insert(at, '\n')
        return sb.toString()
    }

    @Test
    fun eachWrappedLineBecomesItsOwnParagraph() {
        val text = "hello world this is a test of the corner wrap"
        val plan = CornerWrap.plan(text, listOf(6, 12))
        assertEquals(listOf(6, 12), plan.insertAt)
        val wrapped = apply(text, plan)
        assertEquals("hello \nworld \nthis is a test of the corner wrap", wrapped)
        assertEquals("the margin ends just past the second break", 14, plan.marginEnd)
        assertEquals('\n', wrapped[plan.marginEnd - 1])
    }

    @Test
    fun aLineThatAlreadyEndsAParagraphNeedsNoBreak() {
        // A speaker label on its own line is already a paragraph; only the line after it breaks.
        val text = "Speaker 1:\nhello there my good friend"
        val plan = CornerWrap.plan(text, listOf(11, 17))
        assertEquals(listOf(17), plan.insertAt)
        val wrapped = apply(text, plan)
        assertEquals("Speaker 1:\nhello \nthere my good friend", wrapped)
        assertEquals(18, plan.marginEnd)
        assertEquals('\n', wrapped[plan.marginEnd - 1])
    }

    @Test
    fun textThatEndsInsideTheWrappedLinesIsMarginedToItsEnd() {
        val short = CornerWrap.plan("hi", listOf(2))
        assertTrue(short.insertAt.isEmpty())
        assertEquals(2, short.marginEnd)

        val text = "hello world"
        val plan = CornerWrap.plan(text, listOf(6, 11))
        assertEquals("the first line breaks; the second IS the end of the text", listOf(6), plan.insertAt)
        val wrapped = apply(text, plan)
        assertEquals("hello \nworld", wrapped)
        assertEquals("the margin runs to the very end", wrapped.length, plan.marginEnd)
    }

    @Test
    fun nothingToWrapPlansNothing() {
        val plan = CornerWrap.plan("anything at all", emptyList())
        assertTrue(plan.insertAt.isEmpty())
        assertEquals(0, plan.marginEnd)
    }

    @Test
    fun aTwoCharacterBreakStillEndsTheMarginOnTheNewlineNotOnTheMark() {
        // Each real break is a newline AND a direction mark (CornerWrap.BREAK_LENGTH). The mark
        // is the first character of the NEXT paragraph, so the margin must stop right after the
        // newline — one character past it and the margin would cover the rest of the transcript.
        val text = "hello world this is a test of the corner wrap"
        val plan = CornerWrap.plan(text, listOf(6, 12), CornerWrap.BREAK_LENGTH)
        assertEquals(listOf(6, 12), plan.insertAt)
        val sb = StringBuilder(text)
        for (at in plan.insertAt.asReversed()) sb.insert(at, "\nM")
        val wrapped = sb.toString()
        assertEquals("hello \nMworld \nMthis is a test of the corner wrap", wrapped)
        assertEquals('\n', wrapped[plan.marginEnd - 1])
        assertEquals("the mark after it is outside the margin", 'M', wrapped[plan.marginEnd])

        // A natural paragraph end as the last wrapped line: the margin ends after ITS newline.
        val labelled = "ab cd\nef gh"
        val p2 = CornerWrap.plan(labelled, listOf(3, 6), CornerWrap.BREAK_LENGTH)
        assertEquals(listOf(3), p2.insertAt)
        val w2 = StringBuilder(labelled).insert(3, "\nM").toString()
        assertEquals("ab \nMcd\nef gh", w2)
        assertEquals('\n', w2[p2.marginEnd - 1])
        assertEquals('e', w2[p2.marginEnd])

        // And a wrap that reaches the end of the text margins to the very end.
        val p3 = CornerWrap.plan("hello world", listOf(6, 11), CornerWrap.BREAK_LENGTH)
        assertEquals("hello world".length + 2, p3.marginEnd)
    }

    @Test
    fun aRepeatedOrBackwardsEndIsIgnoredRatherThanBreakingTwice() {
        val plan = CornerWrap.plan("one two three four", listOf(4, 4, 8))
        assertEquals(listOf(4, 8), plan.insertAt)
        assertEquals(10, plan.marginEnd)
    }
}
