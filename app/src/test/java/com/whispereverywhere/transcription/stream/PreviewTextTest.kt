package com.whispereverywhere.transcription.stream

import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewTextTest {

    @Test fun normalizeTrimsAndLowercases() {
        // The model emits ALL CAPS (rung 1 §1.3: 497 uppercase pieces, no lowercase, no digits);
        // the strip must not shout. sherpa's text may carry a leading space from the first token.
        assertEquals("one two three four five", PreviewText.normalize(" ONE TWO THREE FOUR FIVE"))
        assertEquals("", PreviewText.normalize("   "))
    }

    @Test fun beforeKeepsTheTokensThatEndBeforeTheCutAndJoinsByConcatenation() {
        // The AAR's tokens carry a LEADING SPACE (" F", "OUR" — rung 3 §2.2): a word is the run of
        // pieces from one leading-space piece to the next, so the join is a bare concatenation.
        val r = PreviewResult(
            "ONE TWO THREE FOUR FIVE",
            listOf(" ONE", " TWO", " THREE", " F", "OUR", " FI", "VE"),
            floatArrayOf(0.96f, 1.28f, 1.48f, 2.04f, 2.20f, 2.40f, 2.68f),   // rung 3 §5.3's word ends
        )
        assertEquals("one two three four", PreviewText.before(r, 2.30f))
        assertEquals("one two three f", PreviewText.before(r, 2.10f))
        assertEquals("", PreviewText.before(r, 0.5f))
        assertEquals("one two three four five", PreviewText.before(r, 9f))
    }

    @Test fun beforeFallsBackToTheWholeTextWhenTimestampsAreShort() {
        // A result whose timestamps do not cover its tokens cannot be trimmed honestly; the whole
        // (normalized) text is better than a silently truncated one.
        val r = PreviewResult("A B", listOf(" A", " B"), floatArrayOf(0.5f))
        assertEquals("a b", PreviewText.before(r, 0.1f))
    }
}
