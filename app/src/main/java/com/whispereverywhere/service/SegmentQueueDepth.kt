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
 */
class SegmentQueueDepth {

    private val inFlight = HashSet<Long>()

    /**
     * Records a commit and returns the new depth. A [seq] below zero is [TranscriptionEngine]'s
     * "nothing was cut" answer: it will never resolve, so counting it would strand the depth.
     */
    @Synchronized
    fun onCommitted(seq: Long): Int {
        if (seq >= 0L) inFlight.add(seq)
        return inFlight.size
    }

    /** Records a resolution and returns the new depth. Unknown/duplicate seqs are no-ops. */
    @Synchronized
    fun onResolved(seq: Long): Int {
        inFlight.remove(seq)
        return inFlight.size
    }

    @Synchronized
    fun depth(): Int = inFlight.size

    /** Per-session reset: seq numbering restarts at 0 on every engine connect(). */
    @Synchronized
    fun reset() {
        inFlight.clear()
    }
}
