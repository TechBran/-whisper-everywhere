package com.whispereverywhere.transcription.stream

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The strip's composition rule (spec §4.2): `<frozen text of every committed-but-unresolved seq,
 * oldest first> + " " + <current partial>` — "what the window does not have yet". Whisper's
 * final for seq N lands 2-6 s after N's cut, and the strip is replace-only, so without the frozen
 * prefix N's words would vanish from the strip the moment N+1's partials start and show nowhere
 * until the window caught up.
 */
class PreviewComposerTest {

    @Test fun aPartialAloneIsTheStrip() {
        val c = PreviewComposer()
        assertEquals("", c.compose())
        assertEquals("hello", c.onPartial("hello"))
        assertEquals("hello there", c.onPartial("hello there"))
    }

    @Test fun aFreezeMovesTheTextBehindTheNextPartial() {
        val c = PreviewComposer()
        c.onPartial("hello there")
        assertEquals("hello there friend", c.freeze(0L, "hello there friend"))
        assertEquals("the partial resets at a freeze", "hello there friend", c.onPartial(""))
        assertEquals("hello there friend how", c.onPartial("how"))
        assertEquals(1, c.pending())
    }

    @Test fun aResolutionDropsItsSeqAndEverythingOlder() {
        val c = PreviewComposer()
        c.freeze(0L, "one")
        c.freeze(1L, "two")
        c.freeze(2L, "three")
        c.onPartial("four")
        assertEquals("one two three four", c.compose())
        assertEquals("three four", c.resolve(1L))
        assertEquals("four", c.resolve(2L))
        assertEquals(0, c.pending())
    }

    @Test fun aResolutionBelowEveryFrozenSeqDropsNothing_aboveDropsAll() {
        val c = PreviewComposer()
        c.freeze(3L, "x")
        assertEquals("x", c.resolve(1L))
        // Local resolves in commit order, so a seq the composer never froze (the 30 s buffer
        // backstop's own commit, unreachable under the 15 s cap) can only be OLDER than what is
        // frozen — and "drop everything at or below" answers that correctly too.
        assertEquals("", c.resolve(99L))
        assertEquals(0, c.pending())
    }

    @Test fun aBlankFreezeKeepsNothingButStillClearsThePartial() {
        val c = PreviewComposer()
        c.onPartial("hmm")
        assertEquals("", c.freeze(0L, ""))
        assertEquals(0, c.pending())
        assertEquals("", c.resolve(0L))
    }

    @Test fun resetForgetsEverything() {
        val c = PreviewComposer()
        c.freeze(0L, "a")
        c.onPartial("b")
        c.reset()
        assertEquals("", c.compose())
        assertEquals(0, c.pending())
    }
}
