package com.whispereverywhere.util

/**
 * Per-frame cost and overrun accounting for the inline Silero probe (3.7 Workstream E3; surfaced
 * by Workstream F's `probe:` line).
 *
 * The probe runs INLINE on the capture thread, 31.25 times a second, inside a 32 ms frame period —
 * of which the probe's own cost budget is 8 ms ([budgetUs]), and `overruns` counts against THAT.
 * The decision to keep it there instead of handing frames to a dedicated thread was explicitly
 * conditional on measuring whether it ever misses — this is that measurement, and
 * `overruns` is the number the promotion decision reads. Owner acceptance: overruns = 0 on the
 * Fold6.
 *
 * ALLOCATION-FREE PER FRAME: one pre-sized histogram, no lists, no boxing. Percentiles are
 * session-cumulative (not windowed) because the acceptance question is "did this session ever
 * miss", not "is it missing right now" — the overrun counter answers the latter.
 *
 * THREADING: [record] runs on the CAPTURE thread (the probe records, and that thread also emits the
 * line when [record] says one is due); [reset] and [line] are called from MAIN, at session start and
 * session end respectively (Task C10 wires both). Every method is therefore `@Synchronized` on the
 * instance. This is not defensive: E1's teardown join is TIMED, so a timed-out join leaves the
 * capture thread free to mutate `frameCount` / `overrunCount` / `hist` while Main is reading them —
 * and an under-reported `overruns` is exactly the number the S3 acceptance sheet gates on. The lock
 * is re-entrant, so [line] calling [percentileUs] is safe, and it is a handful of int ops taken
 * ~31 times a second, uncontended.
 */
class ProbeStats(
    /**
     * The probe's own cost budget, in microseconds. A frame strictly ABOVE it counts as an
     * overrun; a frame exactly at it does not.
     *
     * This is `EndpointerTuning.PROBE_BUDGET_MS` (8 ms) converted at the wiring site — Task C10
     * passes it. Named in plain text rather than as a KDoc link on purpose: C10 binds the two
     * without ever touching this file, which takes the budget as a parameter precisely so that it
     * carries no dependency on Workstream C. A KDoc link to that symbol would name something this
     * file cannot see, and would stay unresolvable for good rather than only until C1 lands.
     */
    private val budgetUs: Long,
    /**
     * How often [record] may signal that a `probe:` line is due. MUST be positive: at zero or
     * negative, every frame after the arming one reports a line as due, which is a log write at
     * 31.25 Hz on the capture thread — precisely the cost this class exists to measure, inflicted
     * by the measurement itself.
     *
     * Not validated, because it is not reachable today: the only production construction is Task
     * C10's, and it takes this default. If it ever becomes configurable — a debug toggle, a
     * remote-config value, a per-tier override — add `require(emitIntervalMs > 0L)` here. The
     * constructor is the last point at which a bad value is still cheap to reject.
     */
    private val emitIntervalMs: Long = EMIT_INTERVAL_MS,
) {
    // Index i counts frames in [i*16, (i+1)*16) us; index BUCKETS is the overflow bucket.
    private val hist = IntArray(BUCKETS + 1)
    private var frameCount = 0L
    private var overrunCount = 0L
    private var lastEmitMs = 0L
    private var armed = false

    /**
     * Records one probe call and returns true when a `probe:` line is due (at most one per
     * [emitIntervalMs]). The first frame only ARMS the clock — a line on frame one would report
     * a one-sample distribution.
     *
     * @param nowMs a WALL clock (`System.currentTimeMillis()` at the call site), read only to pace
     *   the emit cadence and never to measure cost. A wall clock can step — NTP, a manual clock
     *   change — and the worst a step does here is emit one line early or hold one back by the size
     *   of the step. The counters and the histogram are driven entirely by [elapsedUs], so no clock
     *   movement can corrupt the numbers the line reports.
     */
    @Synchronized
    fun record(elapsedUs: Long, nowMs: Long): Boolean {
        frameCount++
        if (elapsedUs > budgetUs) overrunCount++
        val us = if (elapsedUs < 0L) 0L else elapsedUs
        val bucket = (us / BUCKET_WIDTH_US)
        hist[if (bucket >= BUCKETS) BUCKETS else bucket.toInt()]++
        if (!armed) {
            armed = true
            lastEmitMs = nowMs
            return false
        }
        if (nowMs - lastEmitMs < emitIntervalMs) return false
        lastEmitMs = nowMs
        return true
    }

    @Synchronized
    fun frames(): Long = frameCount

    @Synchronized
    fun overruns(): Long = overrunCount

    /**
     * The lower edge of the bucket holding the [q]-quantile, in microseconds; 0 when no frame was
     * ever recorded. Quantisation is 16 us against an expected 200-1500 us cost — a factor of 500
     * below the 8 ms budget the number is read against.
     */
    @Synchronized
    fun percentileUs(q: Double): Long {
        if (frameCount == 0L) return 0L
        var rank = Math.ceil(q * frameCount).toLong()
        if (rank < 1L) rank = 1L
        var cumulative = 0L
        for (i in hist.indices) {
            cumulative += hist[i].toLong()
            if (cumulative >= rank) return i * BUCKET_WIDTH_US
        }
        return BUCKETS * BUCKET_WIDTH_US
    }

    /**
     * The greppable line. `\u00B5` is MICRO SIGN written as an escape so this source file stays
     * pure ASCII and the emitted byte cannot drift with the file's on-disk encoding.
     */
    @Synchronized
    fun line(): String =
        "probe: frames=$frameCount p50=${percentileUs(0.50)}\u00B5s " +
            "p99=${percentileUs(0.99)}\u00B5s overruns=$overrunCount"

    /** Per-session reset; the endpointer calls this from its own session reset, on Main. */
    @Synchronized
    fun reset() {
        java.util.Arrays.fill(hist, 0)
        frameCount = 0L
        overrunCount = 0L
        lastEmitMs = 0L
        armed = false
    }

    companion object {
        const val BUCKET_WIDTH_US = 16L
        /** 1024 buckets = 0..16383 us, twice the 8 ms budget; index BUCKETS is the overflow. */
        const val BUCKETS = 1024
        const val EMIT_INTERVAL_MS = 10_000L
    }
}
