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

    // ── review B1: the freeze is asynchronous, the skipped segment's resolution is not ──────────

    @Test fun aFreezeForAnAlreadyResolvedSeqStoresNothing() {
        // On a segment under EndpointerTuning.MIN_SPEECH_EVIDENCE_MS whisper does not decode at
        // all: it resolves EmptyExpected within ~1 ms of the cut (LocalWhisperEngine.kt:375-382),
        // while onFrozen comes back only after the pack's pad + drain + decode
        // (StreamingPreviewEngine.kt:166-205). So THIS is the normal order, not a race — and
        // stored unconditionally the late freeze would lead the strip with words whisper has
        // declared it will never type until some LATER seq resolves.
        val c = PreviewComposer()
        c.onPartial("yeah")
        // The resolution sweeps the frozen window; the LIVE partial is the stream's own business
        // and only the freeze it supersedes clears it.
        assertEquals("yeah", c.resolve(0L))
        assertEquals("the late freeze for seq 0 paints nothing", "", c.freeze(0L, "yeah"))
        assertEquals(0, c.pending())
        // and it is gone for good: nothing can sweep what was never stored.
        assertEquals("", c.compose())
        // the NEXT segment is unaffected — the mark refuses only what is at or below it.
        assertEquals("hello there", c.freeze(1L, "hello there"))
        assertEquals(1, c.pending())
    }

    @Test fun theMarkRefusesOnlyAtOrBelowItAndNeverTouchesWhatWasFrozenInTime() {
        val c = PreviewComposer()
        c.freeze(0L, "one")
        c.freeze(1L, "two")
        assertEquals("two", c.resolve(0L))             // mark = 0; seq 1 was frozen in time, stays
        assertEquals("two", c.freeze(0L, "one again")) // at the mark: refused, seq 1 untouched
        assertEquals("two three", c.freeze(2L, "three"))
        assertEquals(2, c.pending())
        assertEquals("", c.resolve(2L))
        assertEquals(0, c.pending())
    }

    @Test fun resetForgetsTheResolvedThroughMarkToo() {
        // Seqs restart at 0 every session (LocalWhisperEngine.kt:135), so a mark carried across a
        // reset would refuse the new session's first segments outright.
        val c = PreviewComposer()
        assertEquals("", c.resolve(7L))
        c.reset()
        assertEquals("a", c.freeze(0L, "a"))
        assertEquals(1, c.pending())
    }
}
