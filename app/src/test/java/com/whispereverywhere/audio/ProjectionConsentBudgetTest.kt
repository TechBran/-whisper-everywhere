package com.whispereverywhere.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-session cap on the screen-capture consent dialog (4.3.1 D). The owner's report: cancel
 * the dialog and the app raised it again at once — every cancel resumed the video, every resume
 * asked — with no bound. Two asks per session: the second is the "I cancelled by mistake"
 * recovery; after that the session is the microphone's.
 */
class ProjectionConsentBudgetTest {

    @Test fun two_asks_then_the_session_is_the_microphones() {
        val b = ProjectionConsentBudget()
        assertTrue(b.mayAsk()); b.noteAsked()
        assertEquals(1, b.asked)
        assertTrue(b.mayAsk()); b.noteAsked()
        assertEquals(2, b.asked)
        assertFalse("the third ask is the trap", b.mayAsk())
        b.noteAsked() // a caller that ignores mayAsk() still cannot make it true
        assertFalse(b.mayAsk())
    }

    @Test fun a_new_session_starts_a_fresh_budget() {
        val b = ProjectionConsentBudget()
        b.noteAsked(); b.noteAsked()
        assertFalse(b.mayAsk())
        b.reset()
        assertEquals(0, b.asked)
        assertTrue(b.mayAsk())
    }

    @Test fun the_cap_is_two_and_honoured_when_overridden() {
        assertEquals(2, ProjectionConsentBudget.MAX_ASKS_PER_SESSION)
        val once = ProjectionConsentBudget(maxAsks = 1)
        assertTrue(once.mayAsk()); once.noteAsked()
        assertFalse(once.mayAsk())
    }
}
