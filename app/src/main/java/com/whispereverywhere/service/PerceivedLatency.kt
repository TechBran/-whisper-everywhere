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
 * the next one — a diagnostic that lies in precisely the sessions worth diagnosing. [onVisible]
 * also prunes every EARLIER seq, which is sound because delivery is strictly in seq order
 * (SegmentOrderer), and is what keeps the map bounded through a session of quiet commits.
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
