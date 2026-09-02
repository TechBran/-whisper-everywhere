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

/** Which rule let playback start (4.3.1 C) — logged on the `TTSDIAG start` line. */
enum class StartRule { DONE, CAP, PROJECTED }

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

    /** The measured synthesis speed the projection uses (EWMA; DEFAULT_RTF until fed). */
    fun rtf(): Double = rtfEwma

    /**
     * THE START GATE (4.3.1 C, owner decision 2026-09-02 "projected-complete"): playback may
     * begin only when what is banked outlasts the projected remainder of the read.
     *
     *     start ⟸ bufferedMs > 0 && (
     *         done                                                         -> DONE
     *         || noGrowthMs >= START_CAP_MS                                -> CAP
     *         || (totalMs > SHORT_READ_MS
     *             && bufferedMs >= PROJECTED_SAFETY * rtf * remainingMs)   -> PROJECTED )
     *
     * Derivation: with lead L and remaining audio R the producer needs rtf·R wall-seconds and
     * playback consumes the lead in L seconds, so L ≥ 1.5·rtf·R finishes synthesis before playback
     * reaches the frontier with a 50 % margin, independent of unit granularity. As a fraction of
     * the read that is 1.5rtf/(1+1.5rtf): 47 % at the local voice's 0.58, 60 % at rtf 1, 75 % at
     * rtf 2 — "mostly all", never degenerating to everything-first. A short read (≤ SHORT_READ_MS)
     * completes first: the wait is small and certainty is free. The cap escapes a producer that
     * has stopped growing the bank with audio already banked (a cloud fetch on its 45 s timeout)
     * and counts NO-GROWTH time only. [shouldProceed] is the stall-RESUME rule and is untouched.
     */
    fun startDecision(bufferedMs: Int, remainingMs: Int, totalMs: Int, noGrowthMs: Long, done: Boolean): StartRule? {
        if (bufferedMs <= 0) return null
        if (done) return StartRule.DONE
        if (noGrowthMs >= START_CAP_MS) return StartRule.CAP
        if (totalMs <= SHORT_READ_MS) return null
        val need = PROJECTED_SAFETY * rtfEwma * remainingMs.coerceAtLeast(0)
        return if (bufferedMs >= need) StartRule.PROJECTED else null
    }

    fun shouldStart(bufferedMs: Int, remainingMs: Int, totalMs: Int, noGrowthMs: Long, done: Boolean): Boolean =
        startDecision(bufferedMs, remainingMs, totalMs, noGrowthMs, done) != null

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
        /** A read this short is generated fully before it starts (the wait is small). */
        const val SHORT_READ_MS = 20_000
        /** Margin on the projected remainder: 1.5× the measured synthesis time. */
        const val PROJECTED_SAFETY = 1.5
        /** The start gate's stuck-producer escape, on no-growth time only. */
        const val START_CAP_MS = 12_000L
    }
}
