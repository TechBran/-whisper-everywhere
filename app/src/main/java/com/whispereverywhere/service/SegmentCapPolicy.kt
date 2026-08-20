package com.whispereverywhere.service

/**
 * The wall-clock commit cap for one recording session (3.6.0, Workstream A).
 *
 * Continuous loud audio (media playback, music, uninterrupted speech) never dips below the
 * segmenter's silence floor, so the 800 ms pause cut never fires — the wall cap is what bounds an
 * uncommitted stretch. Before 3.6.0 that bound was a flat 15 s, which meant ~15 s + a full-segment
 * inference before the FIRST visible text (owner report: ~17 s on multi). The fix: the session's
 * FIRST stretch cuts at [FIRST_SEGMENT_WALL_MS] (4 s) so first text lands fast; every LATER
 * stretch keeps the pre-existing [MAX_SEGMENT_WALL_MS] (15 s). The pause cut is untouched and
 * still wins whenever a real pause happens — any commit, whatever cut it, ends the first-cap
 * window and restarts the clock.
 *
 * Pure and Compose/Context-free so the first-vs-later rule and the per-session reset are
 * JVM-pinned (SegmentCapPolicyTest). Threading: [onSessionStart]/[onCommit] are called from Main
 * (session open, switchSource) AND the audio-capture thread (per-chunk VAD path), same as the
 * plain `lastCommitWallMs` long this replaces. Fields are @Volatile; the two writes in [onCommit]
 * are not atomic together, but a torn observation costs at most one ~32 ms audio chunk of cap
 * slack — the exact tolerance the old field had.
 */
class SegmentCapPolicy(
    private val firstSegmentCapMs: Long = FIRST_SEGMENT_WALL_MS,
    private val laterSegmentCapMs: Long = MAX_SEGMENT_WALL_MS,
) {
    @Volatile private var anchorMs = 0L
    @Volatile private var firstCommitDone = false

    /** RECORDING start: the cap clock restarts and the FIRST-segment cap applies again. */
    fun onSessionStart(nowMs: Long) {
        anchorMs = nowMs
        firstCommitDone = false
    }

    /** Any commit — pause cut, wall cap, source switch — restarts the clock; later caps apply. */
    fun onCommit(nowMs: Long) {
        anchorMs = nowMs
        firstCommitDone = true
    }

    /** The cap currently in force (first vs later), for the WE-DIAG line. */
    fun currentCapMs(): Long = if (firstCommitDone) laterSegmentCapMs else firstSegmentCapMs

    /** True when the current uncommitted stretch has outlived its cap. */
    fun capExceeded(nowMs: Long): Boolean = nowMs - anchorMs >= currentCapMs()

    companion object {
        /**
         * The session's first commit: 4 s, so first visible text under continuous speech is
         * ~4 s + one short segment's inference instead of 15 s + a long one (spec A1). Free
         * consequence (spec A2): a short first cap also shrinks the stop-tap tail for short
         * sessions — the buffer holds at most this much never-transcribed audio until then.
         */
        const val FIRST_SEGMENT_WALL_MS = 4_000L

        /** Every later uncommitted stretch keeps the pre-3.6.0 cap, byte-identical semantics. */
        const val MAX_SEGMENT_WALL_MS = 15_000L
    }
}
