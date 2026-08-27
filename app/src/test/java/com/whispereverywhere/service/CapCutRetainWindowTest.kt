package com.whispereverywhere.service

import com.whispereverywhere.audio.Endpointer
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The wall-cap cut's RETAIN WINDOW, extracted from FloatingBubbleService's `else if` and pinned by
 * VALUE (3.7, Task F8 — the follow-up D9 opened as M4b and F7 re-routed here).
 *
 * **Why this class exists rather than a source needle.** `capCutRetainWindowMs` hands
 * [CommitCadencePolicy.capCutRetainMs] two same-typed `Long`s, `nowMs` and `cutPointMs`. Swapping
 * them returns `0L` for EVERY input — the cap-cut split silently reverts to 3.6.0's arbitrary
 * mid-word boundary, which `no_context = true` makes permanently unrepairable — and it does so with
 * the whole suite green: D2's M22 was exactly that mutant and it survived. Named arguments make a
 * POSITIONAL swap inert but say nothing about a mis-bound VALUE, and `FloatingBubbleService` cannot
 * be instantiated in a JVM test, so until this class the only guard was an exact-match source
 * needle in `CapSeamPinTest` — a restatement of the code, written in the same session as the code.
 *
 * Three rows are enough, and each one is load-bearing:
 *
 *  - **no offer** -> 0, the byte-identical-to-3.6.0 path an endpointer that never fires always
 *    takes. A swap passes this row (`0 - now` is negative, which also clamps to 0), which is
 *    precisely why it cannot be the only row.
 *  - **a fresh micro-pause** -> the elapsed ms. This is the row a swap DIES on: it is the only one
 *    that asserts a non-zero number, and the swapped form answers 0.
 *  - **the reversed pair** -> 0. A cut point in the future is not a boundary behind us; this
 *    pins the ORDER of the subtraction from the other side, so the swap dies twice.
 *
 * The arithmetic itself, the 3 s staleness ceiling and the sentinel are `CommitCadencePolicy`'s and
 * are pinned in `CommitCadencePolicyTest`. What is pinned HERE is the wiring: which of the two
 * numbers is "now" and which is the endpointer's offer.
 */
class CapCutRetainWindowTest {

    /**
     * The offer, and nothing else. `Endpointer`'s three abstract members are stubbed inert;
     * `pendingCutPointMs` is the one the wall-cap branch reads and the one under test.
     */
    private class OfferingEndpointer(private val cutPointMs: Long) : Endpointer {
        override fun onFrame(chunk: ByteArray, amp: Int, nowMs: Long): Boolean = false
        override fun hasPendingSpeech(): Boolean = false
        override fun reset() {}
        override fun pendingCutPointMs(): Long = cutPointMs
    }

    @Test
    fun anEndpointerWithNoMicroPauseToOfferRetainsNothing() {
        // The amplitude endpointer always, and Silero before its first dip: commit the whole
        // buffer, exactly as 3.6.0 did.
        assertEquals(
            0L,
            capCutRetainWindowMs(
                nowMs = 1_700_000_010_000L,
                endpointer = OfferingEndpointer(Endpointer.NO_CUT_POINT),
            ),
        )
    }

    @Test
    fun aFreshMicroPauseRetainsExactlyTheMillisecondsSinceIt() {
        // 500 ms of tail is kept for the next segment, so the cap cuts at the pause and not
        // mid-word. This is the row a swapped nowMs/cutPointMs binding fails.
        assertEquals(
            500L,
            capCutRetainWindowMs(
                nowMs = 1_700_000_010_000L,
                endpointer = OfferingEndpointer(1_700_000_009_500L),
            ),
        )
    }

    @Test
    fun aCutPointAheadOfNowIsNotABoundaryBehindUsAndRetainsNothing() {
        // The reversed pair. Nothing in the tree can produce it honestly, which is the point: it
        // is what a swapped binding LOOKS like from the inside.
        assertEquals(
            0L,
            capCutRetainWindowMs(
                nowMs = 1_700_000_009_500L,
                endpointer = OfferingEndpointer(1_700_000_010_000L),
            ),
        )
    }
}
