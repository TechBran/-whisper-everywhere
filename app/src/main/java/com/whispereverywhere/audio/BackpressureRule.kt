package com.whispereverywhere.audio

/**
 * THE BACKPRESSURE GOVERNOR's pure rule (build 85): two floors, one bit of mode, no clock.
 *
 * The cost governor in [SileroEndpointer] paces commits at ONE floor per session, and on
 * npu-turbo that floor is an owner ruling OVER the duty rule (see
 * `CommitCadencePolicy.MIN_COMMIT_INTERVAL_TURBO_MS`): one sentence per chunk at ~98 % saturated
 * duty, 110 % on a hot phone. The guard that ruling named is this: keep the fast floor while the
 * segment queue is at most one deep, and step up to a SLOW floor — the bounded-duty value — once a
 * second segment is waiting behind the one in flight. Two states with hysteresis, so the boundary
 * cannot flap: ENTER at a depth of [ENTER_DEPTH] or more, LEAVE at [LEAVE_DEPTH] or fewer, and
 * keep the current mode in between.
 *
 * Why it lives HERE and not in `CommitCadencePolicy`. The endpointer steps this rule on the
 * CAPTURE thread at every real endpoint, and the endpointer's package (Workstream C) compiles
 * without the service package — the same reason `SileroEndpointer` spells its pre-session floor
 * as a literal. `CommitCadencePolicy` re-exports the two thresholds under its own names
 * (`BACKPRESSURE_ENTER_DEPTH` / `BACKPRESSURE_LEAVE_DEPTH`) and composes [slowActive] and [floorMs]
 * into `floorFor`, which is the surface the service and its tests read; both test files pin the
 * two surfaces EQUAL, so the rule has one implementation and two names, never two implementations.
 *
 * PURE. No field, no clock, no allocation: [SileroEndpointer.currentFloorMs] calls [slowActive]
 * with a `@Volatile` depth the service published and the mode as it stands, stores the answer,
 * and selects the floor with [floorMs]. That keeps the step inside [Endpointer]'s "allocation-free
 * and lock-light on the onFrame path" obligation, and — more to the point — it keeps the only
 * Main-written field a plain integer: the MODE is written on the capture thread and nowhere else.
 */
object BackpressureRule {

    /**
     * The depth at which the slow floor ENGAGES: one segment in flight AND one waiting. Owner on
     * 83: "I have never seen more than two queued"; the acceptance sheet's E2 bound is 0-2. Two is
     * therefore the first depth that says the tier is behind rather than merely busy.
     */
    const val ENTER_DEPTH = 2

    /**
     * The depth at or below which the slow floor RELEASES: at most one segment in flight, nothing
     * waiting. Strictly below [ENTER_DEPTH] so the two edges never coincide; at the shipped pair
     * (2, 1) the keep band between them is EMPTY — every observation decides the mode — and
     * `BackpressureRuleTest` records that fact so a widening of ENTER opens the band knowingly.
     */
    const val LEAVE_DEPTH = 1

    /**
     * THE MODE STEP. Given the depth just observed and the mode as it stands, the mode after:
     * enter slow at `depth >= ENTER_DEPTH`, leave it at `depth <= LEAVE_DEPTH`, otherwise keep.
     */
    fun slowActive(depth: Int, slowActive: Boolean): Boolean = when {
        depth >= ENTER_DEPTH -> true
        depth <= LEAVE_DEPTH -> false
        else -> slowActive
    }

    /**
     * The floor a mode selects. No clamp: [slowMs] is chosen whenever the mode is on, so a caller
     * handing in a slow floor BELOW its fast floor would make backpressure commit faster —
     * `CommitCadencePolicyTest.theSlowFloorIsNeverBelowTheFastFloor` is where that is forbidden,
     * at the table that produces the two numbers.
     */
    fun floorMs(slowActive: Boolean, fastMs: Long, slowMs: Long): Long =
        if (slowActive) slowMs else fastMs
}
