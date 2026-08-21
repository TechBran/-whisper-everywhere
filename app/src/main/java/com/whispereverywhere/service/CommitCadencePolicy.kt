package com.whispereverywhere.service

import com.whispereverywhere.audio.Endpointer

/**
 * The 3.7 commit-cadence governor (Workstream D3) — ONE policy object, all values MEASURED on the
 * Fold6 at vc77 with the 512 audio_ctx floor and production backends.
 *
 * A real endpointer cuts at real pauses; what it must NOT do is cut faster than the tier can pay
 * for. The arithmetic is `F*N + m*S <= 0.70 * 60 s` per minute of session, where F is the fixed
 * per-commit cost, m the steady rtf and S the speech seconds (conserved — same speech, same
 * tokens, only the number of encoder passes changes).
 *
 * - pro: F = 0.77-1.0 s (GPU) -> true utterance cadence. Below ~1.1 s a commit is zero-padded to
 *   the same encoder cost as a 2.4 s one, so merging strictly beats committing.
 * - multi: F = 2.3 s (CPU) -> N <= ~10.7 commits/min -> a 6 s floor. Predictable ~2.8 s
 *   speech-end-to-text at the paced boundary, and no 15 s walls.
 * - extreme/ultra (539-574 MB): UNMEASURED. 8 s is the conservative placeholder; H2 may revise it.
 * - cloud batch: every commit is one HTTP POST (Semaphore(3) in flight, shed at 24). Same
 *   reasoning that made the 4 s first cap LOCAL-only.
 *
 * Pure and Context-free so every number is JVM-pinned (CommitCadencePolicyTest).
 */
object CommitCadencePolicy {

    /** pro / eco / base — any tier whose measured F is at or under ~1.2 s. */
    const val MIN_COMMIT_INTERVAL_FAST_MS = 1_200L

    /** multi: derived from F = 2.3 s at a 0.70 duty ceiling. */
    const val MIN_COMMIT_INTERVAL_MULTI_MS = 6_000L

    /**
     * extreme / ultra, and any tier this build does not recognise: assume the expensive end.
     *
     * This row stays live configuration after Workstream H retires those tiers from the CHOOSER:
     * retirement hides a tier from fresh installs and changes nothing for the users who already
     * have one, and those are exactly the users this number paces.
     *
     * It is also the value [Endpointer.onSessionStart]'s KDoc obliges an endpointer to assume
     * BEFORE it has been given a session — `SileroEndpointer` spells that default as a literal,
     * because Workstream C compiles without this package. The two are joined behaviourally by
     * `CommitCadencePolicyTest.theEndpointersPreSessionFloorIsThisObjectsLargeInterval`, which
     * drives a never-started endpointer to its merge/commit boundary and reads THIS constant as
     * the expected value. Changing this number without changing that literal fails it.
     */
    const val MIN_COMMIT_INTERVAL_LARGE_MS = 8_000L

    /** cloud batch: the provider-request floor, orthogonal to the local tier. */
    const val MIN_COMMIT_INTERVAL_CLOUD_MS = 3_000L

    /**
     * The oldest micro-pause the wall cap will still cut at. An offer older than this is not the
     * boundary near where the cap fired — taking it would defer most of the window into the next
     * one and push the effective wall bound from 15 s to ~28 s. Owner-tunable knob.
     */
    const val CAP_CUT_MAX_RETAIN_MS = 3_000L

    /**
     * The minimum interval between endpoint-driven commits for this session.
     *
     * **Cloud batch wins outright — a FLAT 3 000 for every tier**, exactly as the spec's tuning
     * table lists it. In a cloud-batch session the cloud engine is the primary transcriber and the
     * local mirror only runs on a rescue, so pacing the whole session at the local tier's floor
     * would slow the engine doing the work in order to protect one that usually does none; the
     * cost of the failure path is bounded by the drain reserve, not by this interval. (Owner
     * acceptance watches the other side of that trade: the multi-tier cloud sessions'
     * `finalize-timing: local-drain` in the Task S3 sheet is the evidence that would reopen it.)
     *
     * [tierId] is `WhisperModel.id`; null/unrecognised assumes the expensive end. The app cannot
     * reach a recording session without an installed model, so that branch is defensive only.
     */
    fun minCommitIntervalMs(tierId: String?, isCloudBatch: Boolean): Long {
        if (isCloudBatch) return MIN_COMMIT_INTERVAL_CLOUD_MS
        return when (tierId) {
            "eco", "base", "pro" -> MIN_COMMIT_INTERVAL_FAST_MS
            "multi" -> MIN_COMMIT_INTERVAL_MULTI_MS
            "extreme", "ultra" -> MIN_COMMIT_INTERVAL_LARGE_MS
            else -> MIN_COMMIT_INTERVAL_LARGE_MS
        }
    }

    /**
     * How many trailing milliseconds the wall-cap commit should RETAIN, given the endpointer's
     * remembered micro-pause [cutPointMs] (wall clock) at cap time [nowMs].
     *
     * Returns 0 — i.e. "commit everything, exactly as 3.6.0 did" — for no offer, a
     * future/equal offer, and a stale offer. That zero is what makes the cap path byte-identical
     * whenever the endpointer never fired, which is the untouchable this function must not break.
     */
    fun capCutRetainMs(nowMs: Long, cutPointMs: Long): Long {
        if (cutPointMs <= Endpointer.NO_CUT_POINT) return 0L
        val retain = nowMs - cutPointMs
        if (retain <= 0L) return 0L
        return if (retain > CAP_CUT_MAX_RETAIN_MS) 0L else retain
    }
}
