package com.whispereverywhere.service

import com.whispereverywhere.npu.NpuModelSpec

/**
 * The wall-clock commit cap for one recording session (3.6.0, Workstream A; per tier since 4.16.1).
 *
 * Continuous loud audio (media playback, music, uninterrupted speech) never dips below the
 * segmenter's silence floor, so the 800 ms pause cut never fires — the wall cap is what bounds an
 * uncommitted stretch. Before 3.6.0 that bound was a flat 15 s, which meant ~15 s + a full-segment
 * inference before the FIRST visible text (owner report: ~17 s on multi). The fix: the session's
 * FIRST stretch cuts at [FIRST_SEGMENT_WALL_MS] (4 s) so first text lands fast; every LATER
 * stretch cuts at the session tier's sustained wall — the pre-existing [MAX_SEGMENT_WALL_MS] (15 s)
 * on every CPU tier and in every cloud session, and [NPU_SUSTAINED_WALL_MS] (5 s) on the AI-chip
 * tiers since 4.16.1, by owner ruling ([laterWallMsFor] is the rule, [onSessionTier] the per-session
 * handover). The pause cut is untouched and still wins whenever a real pause happens — any commit,
 * whatever cut it, ends the first-cap window and restarts the clock.
 *
 * Pure and Compose/Context-free so the first-vs-later rule, the per-tier rule and the per-session
 * reset are JVM-pinned (SegmentCapPolicyTest). Threading: [onSessionStart]/[onCommit] are called
 * from Main (session open, switchSource) AND the audio-capture thread (per-chunk VAD path), same as
 * the plain `lastCommitWallMs` long this replaces; [onSessionTier] from Main alone, at the session
 * open and before that session's capture thread starts. Fields are @Volatile; the two writes in
 * [onCommit] are not atomic together, but a torn observation costs at most one ~32 ms audio chunk
 * of cap slack — the exact tolerance the old field had.
 */
class SegmentCapPolicy(
    private val firstSegmentCapMs: Long = FIRST_SEGMENT_WALL_MS,
    laterSegmentCapMs: Long = MAX_SEGMENT_WALL_MS,
) {
    @Volatile private var anchorMs = 0L
    @Volatile private var firstCommitDone = false

    /**
     * The LATER wall in force: the constructor's until the first [onSessionTier], the session
     * tier's after it. A `var` because one policy lives as long as the service while the tier is a
     * per-session fact — the service builds this object once and never with a tier in it.
     */
    @Volatile private var laterSegmentCapMs = laterSegmentCapMs

    /** RECORDING start: the cap clock restarts and the FIRST-segment cap applies again. */
    fun onSessionStart(nowMs: Long) {
        anchorMs = nowMs
        firstCommitDone = false
    }

    /**
     * THIS SESSION's later wall — [laterWallMsFor]'s answer for the session's tier — handed over at
     * every session open (4.16.1). The only writer after construction, and deliberately separate
     * from [onSessionStart]: that is also the LOCAL-silence RE-ARM the cap branch runs inside a
     * session, which must reopen the 4 s window without forgetting which tier the session is on.
     */
    fun onSessionTier(laterSegmentCapMs: Long) {
        this.laterSegmentCapMs = laterSegmentCapMs
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
         * The same 4 s on every tier: 4.16.1 moved the LATER wall only.
         */
        const val FIRST_SEGMENT_WALL_MS = 4_000L

        /**
         * Every later uncommitted stretch on every CPU tier and in every cloud session keeps the
         * pre-3.6.0 cap, byte-identical semantics. The AI-chip tiers pace on
         * [NPU_SUSTAINED_WALL_MS] instead since 4.16.1; [laterWallMsFor] is the rule.
         */
        const val MAX_SEGMENT_WALL_MS = 15_000L

        /**
         * THE AI-CHIP TIERS' SUSTAINED WALL (4.16.1): speech with no break long enough for the
         * endpointer commits every 5 s on `npu` and `npu-turbo` — whichever vendor's accelerator
         * runs them; the Tab S10+'s APU runs `npu-turbo` too — instead of at the 15 s wall.
         * **An OWNER RULING (2026-09-25), not a duty derivation:** *"a fair compromise would be
         * five seconds to start… we can set it straight across the board for all of the NPU
         * accelerator tiers."*
         *
         * WHAT IT BUYS, in his terms: the finalized large-v3-turbo text appears sooner and more
         * evenly, and the preview strip never builds up, so nobody fixates on its lower-quality
         * words while whisper's are still a long chunk away. He had listened to a podcast at the
         * ~15 s chunks before choosing this.
         *
         * THE DUTY, per chip, at one cap commit per 5 s — the most this wall ever adds; VAD cuts
         * are paced by [CommitCadencePolicy]'s floors, not by it: per-commit cost / 5 000 ms.
         *  - Fold6 (8 Gen 3) ≈ 0.6 s per commit → **~12 %** (measured on the v0.63.0 pack: the
         *    2026-09-24 refresh sheet, §12);
         *  - S23 Ultra (8 Gen 2) ≈ 0.9 s → **~18 %** (an estimate: the repo holds no in-app v0.63.0
         *    commit from that phone yet);
         *  - Tab S10+ (MT6989 APU) ≈ 2.5 s → **~50 %** (measured: the 2026-09-25 Tab ship sheet's
         *    per-commit row, a 23-token commit ≈ 2.5 s). The owner accepted the tablet near half
         *    duty "to see how bad it is".
         * At the 15 s wall the same three were ~4 %, ~6 % and ~17 %.
         *
         * THE TWO COSTS, stated where the number is:
         *  1. **Sentence context at the seams.** Every cut is a context-free boundary — the CPU
         *     decodes with `no_context = true` and the NPU prompt carries no previous text — so
         *     unbroken speech is split three times as often as at 15 s, and a sentence that
         *     straddles a seam is decoded as two halves. The owner accepted that cost for the
         *     latency. The micro-pause retain ([CommitCadencePolicy.CAP_CUT_MAX_RETAIN_MS]) still
         *     moves each seam back to the last pause: it decides WHERE a seam falls, not whether
         *     there is one.
         *  2. **Commits per minute → battery.** Unbroken speech now costs 12 cap commits a minute
         *     instead of 4, and every commit pays the encoder's FIXED 30 s mel window, so the
         *     accelerator's cap-driven work triples — on the tablet ~30 s of APU time a minute
         *     instead of ~10.
         *
         * **NEVER BELOW THE TIER'S FLOOR.** The cap is not paced by the governor — it fires on the
         * wall alone — so a wall under a tier's commit floor would commit faster than that floor
         * allows and walk straight past [CommitCadencePolicy]. 5 000 clears `npu`'s 1 200 and
         * `npu-turbo`'s 2 000 fast / 3 200 slow rows (both floors unchanged by this ruling), and
         * the 3 000 retain is shorter than it, so every cap cut still commits at least 2 s of its
         * own window. `SegmentCapPolicyTest` executes both over every catalog tier and session kind.
         */
        const val NPU_SUSTAINED_WALL_MS = 5_000L

        /**
         * THE PER-TIER LATER WALL (4.16.1) — the rule the service asks at every session open, with
         * the two facts its commit floors are asked with: the installed tier, and whether the
         * session is a cloud one (`cloudWrapper != null`, batch and live alike).
         *
         *  - **A cloud session keeps [MAX_SEGMENT_WALL_MS] whatever the tier**, as it already
         *    keeps 15 s for its first stretch. There the cloud engine transcribes and the local
         *    tier is only the rescue mirror, so 5 s would triple the billable requests under
         *    unbroken speech without the AI chip doing the work — a request-rate decision the
         *    ruling did not make. `cap=5000ms` in a cloud session's log is therefore a regression
         *    signature, the same kind as `cap=4000ms` there.
         *  - **An AI-chip tier — [NpuModelSpec.forTier] answers a row — takes
         *    [NPU_SUSTAINED_WALL_MS].** That is the router's own membership test (the first clause
         *    of `NpuBackendSelector.routesToNpu`), so the two ids have one home each, the catalog's
         *    two gated rows are exactly its answer today, and a third NPU tier joins by getting a
         *    spec row. It is keyed on the TIER, like the floors, and not on which backend armed: a
         *    session running on the CPU fallback after its NPU declined keeps this wall exactly as
         *    it keeps the tier's commit floors.
         *  - **Every other tier — every CPU rung, retired ones included — and an unknown or null id
         *    keep [MAX_SEGMENT_WALL_MS].** The ruling names the NPU accelerator tiers only.
         */
        fun laterWallMsFor(tierId: String?, isCloudSession: Boolean): Long =
            if (!isCloudSession && NpuModelSpec.forTier(tierId) != null) NPU_SUSTAINED_WALL_MS
            else MAX_SEGMENT_WALL_MS
    }
}
