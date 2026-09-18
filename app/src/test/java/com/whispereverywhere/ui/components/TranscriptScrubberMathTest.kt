package com.whispereverywhere.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure model behind the transcript window's grabbable scrollbar (4.9.1). The View is a
 * finger and a canvas around these numbers; what a JVM test can hold is that the bar's fraction
 * is the TextView's own scroll fraction, that a finger maps back through the thumb's top without
 * a jump, and that "fits" — the rule that hides the bar — is exactly "nothing to scroll to".
 */
class TranscriptScrubberMathTest {

    // A 120dp-tall panel at density 2.0 holding 1,000px of text: 240px view, 760px of travel.
    private val view = 240
    private val content = 1000
    private val maxScroll = 760
    private val track = 400f
    private val minThumb = 48f

    @Test fun content_height_is_the_layout_plus_both_vertical_paddings() {
        assertEquals(1000, TranscriptScrubberMath.contentHeight(980, 12, 8))
        // The transcript views carry no padding, so there it IS the layout height — the same
        // number the service's scroll-to-newest reads as getLineBottom(last).
        assertEquals(980, TranscriptScrubberMath.contentHeight(980, 0, 0))
    }

    @Test fun max_scroll_is_the_overflow_and_never_negative() {
        assertEquals(maxScroll, TranscriptScrubberMath.maxScroll(content, view))
        assertEquals(0, TranscriptScrubberMath.maxScroll(200, view))
        assertEquals(0, TranscriptScrubberMath.maxScroll(view, view))
    }

    @Test fun fits_is_exactly_nothing_to_scroll_to() {
        assertTrue(TranscriptScrubberMath.fits(200, view))
        assertTrue("equal is a fit", TranscriptScrubberMath.fits(view, view))
        assertTrue("before first layout everything fits", TranscriptScrubberMath.fits(0, 0))
        assertFalse(TranscriptScrubberMath.fits(view + 1, view))
    }

    @Test fun the_bar_is_visible_only_for_a_visible_text_view_whose_content_overflows() {
        // THE VISIBILITY CONTRACT the 4.8.0 pin test held as "fadeScrollbars=false": the bar is
        // seen whenever there is something to scroll — and not when its view is hidden, so a
        // GONE committed text (the CONNECTING label path) or an INVISIBLE parked strip carries
        // no stray bar and takes no stray touch.
        assertTrue(TranscriptScrubberMath.visible(textViewVisible = true, contentHeight = content, viewHeight = view))
        assertFalse(TranscriptScrubberMath.visible(textViewVisible = true, contentHeight = 200, viewHeight = view))
        assertFalse(TranscriptScrubberMath.visible(textViewVisible = false, contentHeight = content, viewHeight = view))
        assertFalse(TranscriptScrubberMath.visible(textViewVisible = true, contentHeight = 0, viewHeight = 0))
    }

    @Test fun fraction_is_scroll_over_travel_clamped_and_zero_when_there_is_no_travel() {
        assertEquals(0f, TranscriptScrubberMath.fraction(0, maxScroll), 0f)
        assertEquals(0.5f, TranscriptScrubberMath.fraction(380, maxScroll), 0.0001f)
        assertEquals(1f, TranscriptScrubberMath.fraction(maxScroll, maxScroll), 0f)
        assertEquals("overscroll clamps", 1f, TranscriptScrubberMath.fraction(5000, maxScroll), 0f)
        assertEquals("no travel, no fraction", 0f, TranscriptScrubberMath.fraction(100, 0), 0f)
    }

    @Test fun the_thumb_is_proportional_floored_at_a_grabbable_minimum_and_capped_at_the_track() {
        // 240/1000 of a 400px track = 96px.
        assertEquals(96f, TranscriptScrubberMath.thumbHeight(track, view, content, minThumb), 0.001f)
        // A 240px view over 10,000px of text would be a 9.6px thumb — floored so it can be held.
        assertEquals(minThumb, TranscriptScrubberMath.thumbHeight(track, view, 10_000, minThumb), 0f)
        // Content that fits (or nothing laid out) fills the track; a zero track is a zero thumb.
        assertEquals(track, TranscriptScrubberMath.thumbHeight(track, view, 200, minThumb), 0f)
        assertEquals(track, TranscriptScrubberMath.thumbHeight(track, view, 0, minThumb), 0f)
        assertEquals(0f, TranscriptScrubberMath.thumbHeight(0f, view, content, minThumb), 0f)
    }

    @Test fun the_thumb_top_runs_the_travel_the_thumb_leaves_free() {
        val thumb = 96f
        assertEquals(0f, TranscriptScrubberMath.thumbTop(track, thumb, 0f), 0f)
        assertEquals(152f, TranscriptScrubberMath.thumbTop(track, thumb, 0.5f), 0.001f)
        assertEquals(304f, TranscriptScrubberMath.thumbTop(track, thumb, 1f), 0.001f)
        assertEquals("a thumb as long as the track never moves", 0f, TranscriptScrubberMath.thumbTop(track, track, 1f), 0f)
    }

    @Test fun a_finger_maps_back_through_the_thumb_top_to_the_same_scroll_and_clamps_at_both_ends() {
        val thumb = 96f
        for (scrollY in listOf(0, 1, 380, 759, maxScroll)) {
            val top = TranscriptScrubberMath.thumbTop(track, thumb, TranscriptScrubberMath.fraction(scrollY, maxScroll))
            assertEquals("round trip at $scrollY", scrollY, TranscriptScrubberMath.scrollYForThumbTop(top, track, thumb, maxScroll))
        }
        assertEquals("above the track", 0, TranscriptScrubberMath.scrollYForThumbTop(-50f, track, thumb, maxScroll))
        assertEquals("below the track", maxScroll, TranscriptScrubberMath.scrollYForThumbTop(900f, track, thumb, maxScroll))
        assertEquals("no travel", 0, TranscriptScrubberMath.scrollYForThumbTop(100f, track, track, maxScroll))
        assertEquals("nothing to scroll", 0, TranscriptScrubberMath.scrollYForThumbTop(100f, track, thumb, 0))
    }

    @Test fun grabbing_the_thumb_remembers_where_on_it_the_finger_landed_so_it_does_not_jump() {
        // Thumb at 152..248; a finger at 200 is 48px into it.
        assertEquals(48f, TranscriptScrubberMath.grabOffset(200f, 152f, 96f), 0f)
        assertEquals("its very top", 0f, TranscriptScrubberMath.grabOffset(152f, 152f, 96f), 0f)
        assertEquals("its very bottom", 96f, TranscriptScrubberMath.grabOffset(248f, 152f, 96f), 0f)
        // Off the thumb, on the track: the thumb centres under the finger — a tap-to-jump.
        assertEquals(48f, TranscriptScrubberMath.grabOffset(20f, 152f, 96f), 0f)
        assertEquals(48f, TranscriptScrubberMath.grabOffset(390f, 152f, 96f), 0f)
    }

    @Test fun a_grab_then_a_move_scrolls_by_the_finger_travel_scaled_to_the_content() {
        // Start at scrollY 380 (thumb top 152), grab 48px into the thumb (finger 200), move the
        // finger to 300: the thumb top goes to 252, fraction 252/304, scrollY 630.
        val thumb = 96f
        val grab = TranscriptScrubberMath.grabOffset(200f, 152f, thumb)
        val newTop = 300f - grab
        assertEquals(252f, newTop, 0f)
        assertEquals(630, TranscriptScrubberMath.scrollYForThumbTop(newTop, track, thumb, maxScroll))
    }
}
