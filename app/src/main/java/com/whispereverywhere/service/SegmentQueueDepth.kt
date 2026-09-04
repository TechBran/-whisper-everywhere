package com.whispereverywhere.service

/**
 * The committed-but-unresolved segment backlog (3.7 Workstream F).
 *
 * Under 3.7's utterance cadence the backlog is the difference between "the model is slower than
 * you speak" and "the model is fine and the last utterance is just in flight" — and before this
 * counter that difference was only visible at stop, in `finalize-timing: local-drain`. This makes
 * it visible while it grows.
 *
 * Tracks the SET of in-flight seqs rather than a bare int, so a duplicate resolution, an unknown
 * seq, or a re-committed seq can never drive the depth negative or strand it above zero — the
 * failure mode a counter would have, and the one that would make the diagnostic lie exactly when
 * it matters.
 *
 * THREADING: commits arrive from the capture thread AND from Main (switchSource, projection
 * consent, stopRecording); resolutions arrive on Main. Every method is synchronized on the
 * instance — the whole class is a few set operations per segment, ~16 times a minute.
 *
 * THE FEED (build 85). [onDepth] hears the depth after EVERY mutation — commit, resolution and
 * reset, changed or not — and hears it from INSIDE the monitor, so the values arrive in exactly
 * the order the set changed. Its one production listener is the endpointer's depth member
 * (`Endpointer.onQueueDepth`, THE BACKPRESSURE GOVERNOR's signal), bound at construction in
 * `FloatingBubbleService`; a listener must be lock-light, because it runs with this instance's
 * monitor held on whichever thread mutated — a volatile write is the whole of what the endpointer
 * does with it. Publishing from the two call sites instead, after each had released the lock,
 * would let a capture-thread commit and a Main-thread resolution interleave into a STALE depth
 * at the listener: fed a stale 2 the governor latches its slow floor until the next change, fed
 * a stale 1 it misses the backlog it exists to see. `SegmentQueueDepthTest` pins the ordering
 * property as a four-thread storm. Defaulted to a no-op, so every existing construction stands.
 *
 * A resolution of ANY outcome decrements — text, `EmptyExpected`, `Lost` alike — because the
 * decrement is keyed on `onSegmentResolved` arriving, which every seq reaches exactly once
 * (`TranscriptionEngine.commit`'s contract), not on whether the release carried text.
 */
class SegmentQueueDepth(private val onDepth: (Int) -> Unit = {}) {

    private val inFlight = HashSet<Long>()

    /**
     * Records a commit and returns the new depth. A [seq] below zero is [TranscriptionEngine]'s
     * "nothing was cut" answer: it will never resolve, so counting it would strand the depth.
     */
    @Synchronized
    fun onCommitted(seq: Long): Int {
        if (seq >= 0L) inFlight.add(seq)
        return publish()
    }

    /** Records a resolution and returns the new depth. Unknown/duplicate seqs are no-ops. */
    @Synchronized
    fun onResolved(seq: Long): Int {
        inFlight.remove(seq)
        return publish()
    }

    @Synchronized
    fun depth(): Int = inFlight.size

    /** Per-session reset: seq numbering restarts at 0 on every engine connect(). Publishes 0. */
    @Synchronized
    fun reset() {
        inFlight.clear()
        publish()
    }

    /** Under the monitor — every caller above is `@Synchronized` — so publishes are ordered. */
    private fun publish(): Int {
        val depth = inFlight.size
        onDepth(depth)
        return depth
    }
}
