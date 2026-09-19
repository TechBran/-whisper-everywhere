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

    @Test fun the_scrubber_is_measured_to_meet_its_text_views_bottom_and_never_taller_than_the_text_view_makes_the_frame() {
        // The live strip: 4dp margin (8px at 2.0) on the strip, none on its scrubber — the
        // scrubber is the strip's margin plus its height, and starts at the frame's top.
        assertEquals(248, TranscriptScrubberMath.scrubberHeight(targetGone = false, targetMeasuredHeight = 240, targetTopMargin = 8, ownTopMargin = 0))
        // The committed text: no margin on the text, 28dp (56px) on its scrubber so the resize
        // handle keeps its corner — the scrubber is the text's height less that.
        assertEquals(184, TranscriptScrubberMath.scrubberHeight(targetGone = false, targetMeasuredHeight = 240, targetTopMargin = 0, ownTopMargin = 56))
        // A GONE TextView is not measured (a stale measuredHeight) and has no bottom to meet.
        assertEquals(0, TranscriptScrubberMath.scrubberHeight(targetGone = true, targetMeasuredHeight = 240, targetTopMargin = 8, ownTopMargin = 0))
        // A text shorter than the scrubber's own margin (the pre-first-apply hint) is 0, not negative.
        assertEquals(0, TranscriptScrubberMath.scrubberHeight(targetGone = false, targetMeasuredHeight = 40, targetTopMargin = 0, ownTopMargin = 56))
        // THE FRAME INVARIANT: scrubber + its margin == text + its margin whenever the text is
        // there, so the frame is exactly as tall as its TextView makes it; and with no margin of
        // its own, a GONE text leaves the scrubber contributing 0 — the frame collapses.
        for ((tv, tvMargin, own) in listOf(Triple(240, 8, 0), Triple(240, 0, 56), Triple(1000, 8, 0), Triple(56, 0, 56))) {
            val h = TranscriptScrubberMath.scrubberHeight(false, tv, tvMargin, own)
            assertEquals("scrubber ($tv, $tvMargin, $own)", tv + tvMargin, h + own)
        }
        assertEquals(0, TranscriptScrubberMath.scrubberHeight(true, 1000, 8, 0) + 0)
    }

    @Test fun the_track_starts_at_the_text_views_top_when_that_lies_below_the_scrubbers_own() {
        assertEquals("the strip's 4dp margin, 8px", 8, TranscriptScrubberMath.trackTop(targetTopMargin = 8, ownTopMargin = 0))
        assertEquals("the committed scrubber starts below its text: from 0", 0, TranscriptScrubberMath.trackTop(targetTopMargin = 0, ownTopMargin = 56))
        assertEquals(0, TranscriptScrubberMath.trackTop(0, 0))
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

    // ------------------------------------------------------------------ following, not yanking

    @Test fun riding_the_newest_line_means_at_the_bottom_or_within_the_slack() {
        val slack = 48
        assertTrue("exactly there", TranscriptScrubberMath.atBottom(maxScroll, maxScroll, slack))
        assertTrue("one line short still means keep going", TranscriptScrubberMath.atBottom(maxScroll - 48, maxScroll, slack))
        assertFalse("one pixel past the slack is reading", TranscriptScrubberMath.atBottom(maxScroll - 49, maxScroll, slack))
        assertFalse("scrolled right back up", TranscriptScrubberMath.atBottom(0, maxScroll, slack))
        // Content that fits has no bottom to be away from — and neither has a view with nothing
        // laid out yet, which is the state every session's first repaint arrives in.
        assertTrue(TranscriptScrubberMath.atBottom(0, 0, slack))
        assertTrue(TranscriptScrubberMath.atBottom(0, 0, 0))
        // Zero slack is exact, not accidentally permissive.
        assertTrue(TranscriptScrubberMath.atBottom(maxScroll, maxScroll, 0))
        assertFalse(TranscriptScrubberMath.atBottom(maxScroll - 1, maxScroll, 0))
    }

    @Test fun a_repaint_carries_the_reader_only_when_the_reader_was_already_at_the_bottom() {
        // Pinned to the newest line: the panel grows by 200px and the view follows it down.
        assertEquals(960, TranscriptScrubberMath.followScrollY(wasAtBottom = true, previousScrollY = 760, maxScroll = 960))
        // Reading further up: the offset is kept, whatever the repaint did to the content.
        assertEquals(200, TranscriptScrubberMath.followScrollY(wasAtBottom = false, previousScrollY = 200, maxScroll = 960))
        // THE RELABEL CASE: the text's length did not change, so neither branch moves the view.
        assertEquals(200, TranscriptScrubberMath.followScrollY(wasAtBottom = false, previousScrollY = 200, maxScroll = 760))
        assertEquals(760, TranscriptScrubberMath.followScrollY(wasAtBottom = true, previousScrollY = 760, maxScroll = 760))
        // A repaint that SHRINKS the content clamps rather than leaving the view past its end.
        assertEquals(100, TranscriptScrubberMath.followScrollY(wasAtBottom = false, previousScrollY = 700, maxScroll = 100))
        assertEquals(0, TranscriptScrubberMath.followScrollY(wasAtBottom = false, previousScrollY = 700, maxScroll = 0))
        assertEquals(0, TranscriptScrubberMath.followScrollY(wasAtBottom = true, previousScrollY = 0, maxScroll = 0))
    }
}
