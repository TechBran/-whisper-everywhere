package com.whispereverywhere.service

import com.whispereverywhere.service.BubbleHidePolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one rule every hide of the floating bubble goes through (4.3.1 B). Pure, so the order of
 * the three guards is a truth table here and not a reading of a 3,500-line service.
 */
class BubbleHidePolicyTest {

    @Test fun always_on_is_never_hidden_whatever_else_is_true() {
        for (speaking in listOf(true, false)) for (visible in listOf(true, false)) {
            assertEquals(Decision.IGNORE, BubbleHidePolicy.decide(speaking, alwaysOn = true, visible = visible))
        }
    }

    @Test fun a_hidden_bubble_has_nothing_to_hide_and_nothing_to_park() {
        // Not DEFER: parking a reason while nothing is visible would replay a hide the user never saw.
        assertEquals(Decision.IGNORE, BubbleHidePolicy.decide(speaking = true, alwaysOn = false, visible = false))
        assertEquals(Decision.IGNORE, BubbleHidePolicy.decide(speaking = false, alwaysOn = false, visible = false))
    }

    @Test fun a_read_in_progress_defers_the_hide_instead_of_taking_the_bubble_away() {
        // The owner's bug: any window event mid-read hid the pill and the audio kept playing.
        assertEquals(Decision.DEFER, BubbleHidePolicy.decide(speaking = true, alwaysOn = false, visible = true))
    }

    @Test fun otherwise_the_hide_proceeds() {
        assertEquals(Decision.HIDE, BubbleHidePolicy.decide(speaking = false, alwaysOn = false, visible = true))
    }

    @Test fun a_parked_hide_replays_only_where_the_bubble_would_have_hidden_anyway() {
        // A clipboard-summoned bubble (context NONE) still leaves after the read, as the summon
        // comment promises; a bubble on a focused field stays; always-on never hides.
        assertTrue(BubbleHidePolicy.replay(contextIsTextField = false, alwaysOn = false))
        assertFalse(BubbleHidePolicy.replay(contextIsTextField = true, alwaysOn = false))
        assertFalse(BubbleHidePolicy.replay(contextIsTextField = false, alwaysOn = true))
        assertFalse(BubbleHidePolicy.replay(contextIsTextField = true, alwaysOn = true))
    }
}
