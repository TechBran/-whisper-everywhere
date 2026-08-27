package com.whispereverywhere.service

/**
 * speech-end -> text-visible, per segment (3.7 Workstream F).
 *
 * This is the number the mandate is about. Today later-segment latency is uniformly distributed
 * over 0-15 s depending on where in the cap window the user stopped talking; under 3.7 it should
 * be a constant `HANGOVER + C(u)`. The headline is therefore the VARIANCE, which means the stamp
 * must be per segment — a running average would hide exactly the property being claimed.
 *
 * KEYED BY SEQ, not a positional queue. Segments that resolve to silence release no text, so a
 * FIFO pop would drift one entry per silent segment and start attributing one utterance's wait to
 * the next one — a diagnostic that lies in precisely the sessions worth diagnosing.
 *
 * **[onVisible] also prunes every EARLIER seq, and the caller must only call it on a NON-BLANK
 * release.** That is the whole soundness argument and the first version of this KDoc got it wrong:
 * it said pruning is safe "because delivery is strictly in seq order", but `onVisible` is called at
 * RESOLUTION, and resolutions are not in order — `SegmentOrderer` exists precisely because
 * `CloudTranscriptionEngine` runs `Semaphore(3)` and completes in whatever order the network
 * returns. What IS true is the corrected premise: a non-blank release means the orderer drained,
 * which means every seq up to and including this one has been delivered, so the earlier stamps are
 * genuinely spent. Calling this on a blank release — an overtaking seq the orderer is still
 * holding — would consume a stamp that has rendered nothing AND prune the predecessor that is
 * about to render, losing both numbers. Measured on a cloud-shaped probe; the wiring in
 * `FloatingBubbleService.onSegmentResolved` gates on `release.text.isNotBlank()` and
 * `PerceivedLatencyTest`'s resolution-order rows pin it.
 *
 * **KNOWN RESIDUAL LIMIT, accepted deliberately.** When a SILENT head drains a later segment's
 * text, the release is non-blank but the words on screen belong to the tail, while the seq handed
 * to [onVisible] is the head's. The line is then emitted under the head's seq and times the head's
 * speech-end against the tail's render — an over-report, never a lost line. Closing it requires
 * `SegmentOrderer.Release` to name the seq range it drained, which is a change to a class this
 * workstream is not allowed to touch. It is pinned as a characterisation row in
 * `PerceivedLatencyTest` so it cannot be forgotten, and it is listed as an absence/attribution
 * cause in the S3 acceptance block.
 *
 * Only endpoint (`cut=vad`) commits are stamped: a cap/stop/switch cut has no speech-end instant,
 * so there is no honest number to report and [onVisible] returns null rather than inventing one.
 *
 * THREADING: stamps are written from the capture thread (the endpoint cut) and read on Main
 * (delivery). Synchronized on the instance; a handful of map operations per segment.
 */
class PerceivedLatency(private val maxTracked: Int = MAX_TRACKED) {

    /** seq -> wall-clock ms of the frame that ended speech for that segment. */
    private val stamps = java.util.TreeMap<Long, Long>()

    /** Records an endpoint cut. Negative [seq] ("nothing was cut") is ignored. */
    @Synchronized
    fun onCommitted(seq: Long, speechEndMs: Long) {
        if (seq < 0L) return
        stamps[seq] = speechEndMs
        while (stamps.size > maxTracked) stamps.remove(stamps.firstKey())
    }

    /**
     * [seq]'s text just became visible. Returns the wait in ms, or null when this seq carried no
     * speech-end stamp. Consumes the stamp and prunes every earlier one.
     *
     * **Call this ONLY when the release was non-blank** — see the class KDoc. On a blank release
     * the orderer held the segment and nothing rendered, and calling here would destroy two stamps
     * to report zero lines.
     */
    @Synchronized
    fun onVisible(seq: Long, nowMs: Long): Long? {
        val stamp = stamps.remove(seq)
        while (stamps.isNotEmpty() && stamps.firstKey() < seq) stamps.remove(stamps.firstKey())
        return if (stamp == null) null else nowMs - stamp
    }

    /** Per-session reset: seq numbering restarts at 0 on every engine connect(). */
    @Synchronized
    fun reset() {
        stamps.clear()
    }

    companion object {
        /**
         * ~4 minutes of utterance-cadence commits. A bound, not a budget: [onVisible]'s pruning is
         * what actually keeps the map small, and this only backstops a pathological session where
         * nothing ever resolves.
         *
         * The eviction test is `size > maxTracked`, never `>=`: the oldest in-flight seq is the
         * one that has been waiting longest, i.e. systematically the SLOWEST sample, and dropping
         * it a commit early would bias S3 Check 2's p95 low by exactly the tail it measures.
         */
        const val MAX_TRACKED = 64
    }
}
