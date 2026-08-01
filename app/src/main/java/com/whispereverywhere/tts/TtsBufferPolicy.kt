package com.whispereverywhere.tts

/**
 * The spec §6A.3 prebuffer gate, finally implemented (designed 2026-07-27, deferred by D21, built
 * 2026-08-01 on the owner's "hold the audio until we have enough for a smooth stream" directive).
 * Pure Kotlin, no android.*, JUnit-tested — the ONLY gate deciding when playback may start and
 * when a stalled read may resume:
 *
 *     proceed  ⟸  bufferedMs > 0 && (done || bufferedMs >= targetMs() || waitedMs >= capMs)
 *     targetMs() = SAFETY * rtfEwma * dMaxMs + stallBumpMs,   clamped [MIN_WM_MS, MAX_WM_MS]
 *
 * Why each piece exists:
 *  - **The watermark** is the underrun law inverted: a unit of duration d needs rtf*d ms of
 *    synthesis, so playback survives it only if at least rtf*d ms is already banked. dMaxMs is
 *    the caller's estimate of its LONGEST planned unit (chars x ClauseSplitter.MS_PER_CHAR).
 *  - **`bufferedMs > 0` on resume** is a real shipping-bug guard from the spec: without it a long
 *    drought lets the cap fire on an EMPTY store and the loop flaps play/pause/onBuffering at
 *    ~0.4 Hz, strobing the "still working" ring.
 *  - **`done` bypasses the watermark**: once the producer has finished, the bank is all there
 *    will ever be — holding it against a target that can no longer be reached would strand the
 *    tail.
 *  - **The cap** bounds added latency: a slow producer (cloud RTT) starts anyway after CAP_MS
 *    with whatever is banked, trading possible later stalls for bounded time-to-first-word.
 *  - **The stall bump** is the resume hysteresis: every observed stall raises the target, so a
 *    read that stalled once rebuilds a BIGGER lead instead of resuming on the first slice and
 *    re-stalling at the next long unit (the #1 remaining gap contributor in the spec's ranking).
 *
 * RTF samples must exclude the first burst of a read (cold-start phonemization, not steady
 * state) and be measured callback-exit -> callback-entry (so AHEAD_CAP backpressure holds never
 * read as slow synthesis) — both are the CALLER's obligations, already true of the diag
 * measurement this rides on.
 */
class TtsBufferPolicy(private val dMaxMs: Int) {

    @Volatile private var rtfEwma: Double = DEFAULT_RTF
    @Volatile private var stallBumpMs: Int = 0

    /** Feed one steady-state synthesis measurement. Callers must skip the first burst. */
    fun recordRtf(synthMs: Long, audMs: Int) {
        if (audMs <= 0 || synthMs < 0) return
        val sample = synthMs.toDouble() / audMs
        rtfEwma = EWMA_ALPHA * sample + (1 - EWMA_ALPHA) * rtfEwma
    }

    /** A producer stall was observed: rebuild a bigger lead before resuming (hysteresis). */
    fun onStall() {
        stallBumpMs = (stallBumpMs + STALL_BUMP_MS).coerceAtMost(MAX_STALL_BUMP_MS)
    }

    fun targetMs(): Int =
        (SAFETY * rtfEwma * dMaxMs + stallBumpMs).toInt().coerceIn(MIN_WM_MS, MAX_WM_MS)

    /**
     * May playback start (or resume after a stall) right now? [done] = the producer has finished
     * the whole read. One predicate for both gates — the spec's deliberate always-prebuffer
     * choice (owner decision 2026-07-27: smoothness over latency, made affordable by clause
     * splitting keeping the first unit small).
     */
    fun shouldProceed(bufferedMs: Int, waitedMs: Long, done: Boolean): Boolean =
        bufferedMs > 0 && (done || bufferedMs >= targetMs() || waitedMs >= CAP_MS)

    companion object {
        /** Conservative pre-measurement RTF; the Fold 6 baseline measured 0.583 duration-weighted. */
        const val DEFAULT_RTF = 0.75
        const val EWMA_ALPHA = 0.3
        const val SAFETY = 1.25
        /** Floor: never start with less than this banked (absorbs scheduling jitter). */
        const val MIN_WM_MS = 1_200
        /** Ceiling: more lead than this adds latency without audible benefit. */
        const val MAX_WM_MS = 4_000
        /** Never hold time-to-first-word hostage longer than this while ANY audio is banked. */
        const val CAP_MS = 2_500L
        const val STALL_BUMP_MS = 500
        const val MAX_STALL_BUMP_MS = 2_000
    }
}
