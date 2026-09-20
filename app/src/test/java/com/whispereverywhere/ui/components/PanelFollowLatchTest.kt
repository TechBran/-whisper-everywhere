package com.whispereverywhere.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PanelFollowLatch] — the owner's three rules for the transcript panel, 2026-09-20:
 *
 * > "If the user scrolls to the bottom, then the bottom should be locked to automatically
 * > scrolling on committed text. But if you scroll away from the bottom … stay where you are.
 * > But if you go back down to the bottom, then you should definitely be carried with the
 * > committed text."
 *
 * …plus the fourth rule the first version of this class shipped without: never leave the view
 * stranded past the end of its own content.
 *
 * What a JVM test can hold here is the whole of it, because the latch is deliberately free of
 * Android: a boolean, a slack in pixels, and three entry points. What it cannot hold is the
 * WIRING — that the service fences its own scrolls out of [PanelFollowLatch.onUserScroll], arms
 * at the one session site, and actually READS the answer — and that is pinned as source text by
 * `BubbleScrollbarPinTest`.
 */
class PanelFollowLatchTest {

    // A 120dp panel at density 2.0: 760px of travel, and 24dp of slack is 48px.
    private val maxScroll = 760
    private val slack = 48

    private fun latch() = PanelFollowLatch()

    /** At the bottom of a [maxScroll]-tall panel, which is where a following reader sits. */
    private fun PanelFollowLatch.target(max: Int = maxScroll, at: Int = max) = targetScrollY(max, at)

    @Test fun a_new_panel_rides_its_newest_line() {
        // A session that has printed nothing is at its own bottom, and the first words a reader
        // sees should be the newest ones — so the latch starts armed rather than waiting to be
        // told by a gesture that may never come.
        assertTrue(latch().following)
        assertEquals(760, latch().target())
    }

    @Test fun scrolling_away_from_the_bottom_stops_the_panel_following() {
        val latch = latch()
        latch.onUserScroll(scrollY = 200, maxScroll = maxScroll, slackPx = slack)
        assertFalse(latch.following)
        // NULL, not "where you already are": the caller then has nothing to write, so a reader
        // who is not following is never scrolled at all — no clamp, no one-pixel nudge that a
        // later onUserScroll could misread as a deliberate landing at the bottom.
        assertNull(latch.target(at = 200))
    }

    @Test fun scrolling_back_to_the_bottom_carries_the_reader_again() {
        val latch = latch()
        latch.onUserScroll(scrollY = 200, maxScroll = maxScroll, slackPx = slack)
        assertFalse(latch.following)
        latch.onUserScroll(scrollY = maxScroll, maxScroll = maxScroll, slackPx = slack)
        assertTrue("going back down re-arms it", latch.following)
        assertEquals(760, latch.target())
    }

    @Test fun the_slack_is_how_close_to_the_bottom_still_counts_as_riding_it() {
        // The same boundary atBottom pins: inclusive at the slack, exclusive one pixel above it.
        val onTheSlack = latch().apply { onUserScroll(maxScroll - slack, maxScroll, slack) }
        assertTrue("a reader a partial line short is still riding it", onTheSlack.following)
        val pastIt = latch().apply { onUserScroll(maxScroll - slack - 1, maxScroll, slack) }
        assertFalse("one pixel further up is a reader who left", pastIt.following)
    }

    @Test fun the_slack_is_read_per_call_so_a_fold_cannot_strand_it() {
        // Density is not constant for the life of a session — the Fold6's two displays do not
        // share one — so the slack arrives with the gesture rather than being held.
        val latch = latch()
        latch.onUserScroll(scrollY = maxScroll - 60, maxScroll = maxScroll, slackPx = 48)
        assertFalse("48px of slack does not reach 60px short", latch.following)
        latch.onUserScroll(scrollY = maxScroll - 60, maxScroll = maxScroll, slackPx = 72)
        assertTrue("72px of slack does", latch.following)
    }

    @Test fun content_that_fits_can_never_disarm_the_latch() {
        // maxScroll == 0 is at the bottom by definition, so a stray touch on a session whose
        // text does not yet overflow the panel cannot silently stop it following. This is the
        // state every session spends its first seconds in.
        val latch = latch()
        latch.onUserScroll(scrollY = 0, maxScroll = 0, slackPx = slack)
        assertTrue(latch.following)
        assertEquals(0, latch.target(max = 0, at = 0))
    }

    @Test fun arming_overrides_a_reader_who_had_scrolled_away() {
        // Teardown leaves the previous session's text AND its scroll offset on the view, so a
        // new session has to be armed rather than assumed: without this, a reader who scrolled
        // up last time would start the next session already disarmed.
        val latch = latch()
        latch.onUserScroll(scrollY = 0, maxScroll = maxScroll, slackPx = slack)
        assertFalse(latch.following)
        latch.arm()
        assertTrue(latch.following)
    }

    // ------------------------------------------------- the stranded view, which review caught

    @Test fun a_reader_stranded_past_the_end_is_pulled_back_even_though_they_are_not_following() {
        // THE REGRESSION THIS CLASS SHIPPED WITH. TextView does not clamp scrollY when setText
        // shortens the content or when the view grows, and the deleted followScrollY clamped on
        // every repaint. Without this branch a reader who scrolled up and then made the panel
        // taller — or whose content shrank when a remap collapsed paragraph breaks and labels —
        // was left drawing blank space, with the scrubber hidden (it hides once the content
        // fits) and nothing to heal it until the next session.
        val latch = latch()
        latch.onUserScroll(scrollY = 600, maxScroll = 700, slackPx = slack)
        assertFalse(latch.following)
        // The panel grows past its own content: maxScroll collapses to 0 while scrollY is 600.
        assertEquals("the stranded view is pulled back to the new end", 0, latch.targetScrollY(0, 600))
        // …and it is still not following: the clamp is a rescue, not a re-arm.
        assertFalse(latch.following)
    }

    @Test fun the_clamp_fires_only_when_the_view_is_actually_stranded() {
        val latch = latch()
        latch.onUserScroll(scrollY = 200, maxScroll = maxScroll, slackPx = slack)
        assertNull("in range: leave the reader exactly where they are", latch.targetScrollY(760, 200))
        assertNull("exactly at the end is in range too", latch.targetScrollY(760, 760))
        assertEquals("one pixel past it is not", 760, latch.targetScrollY(760, 761))
    }

    @Test fun a_following_panel_never_asks_for_a_negative_offset() {
        // A repaint can measure maxScroll before the first layout, where contentHeight is 0 and
        // the overflow is negative. The panel's answer is the top, never a negative scrollY —
        // and for a stranded reader the same floor applies.
        assertEquals(0, latch().targetScrollY(-500, 0))
        val away = latch().apply { onUserScroll(0, 760, slack) }
        assertEquals(0, away.targetScrollY(-500, 300))
    }

    @Test fun the_latch_survives_repeated_reads() {
        // targetScrollY is a pure read: asking does not arm, disarm, or move anything.
        val latch = latch()
        latch.onUserScroll(scrollY = 100, maxScroll = maxScroll, slackPx = slack)
        repeat(3) { assertNull(latch.targetScrollY(maxScroll, 100)) }
        assertFalse(latch.following)
    }
}
