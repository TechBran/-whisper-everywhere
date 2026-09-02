package com.whispereverywhere.service

import com.whispereverywhere.audio.Endpointer
import com.whispereverywhere.audio.EndpointerGrid
import com.whispereverywhere.audio.EndpointerTuning
import com.whispereverywhere.audio.SileroEndpointer
import com.whispereverywhere.model.WhisperCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The session wall clock the cross-check fixture below runs on. Non-zero, for the same reason
 * `SileroEndpointerTest`'s own BASE is: 0L is [Endpointer.NO_CUT_POINT], the endpointer's
 * "no micro-pause remembered" sentinel.
 */
private const val CROSS_CHECK_BASE = 1_000_000L

/**
 * Where this fixture's FIRST commit lands: 20 speech frames open the gate at [CROSS_CHECK_BASE]
 * and end 640 ms in, and the dip that follows cuts one hangover later. Derived, not transcribed:
 * the hangover is an owner A/B knob and this file is a CADENCE cross-check, not a hangover test.
 */
private val FIRST_COMMIT_MS = CROSS_CHECK_BASE + 640L + EndpointerGrid.HANGOVER_TRAIL_MS

/** Comfortably above [EndpointerTuning.ONSET_THRESHOLD] — a frame that opens/holds the gate. */
private const val SPEECH = 0.9f

/** Comfortably below [EndpointerTuning.RELEASE_THRESHOLD] — a frame that counts as silence. */
private const val SILENCE = 0.1f

/**
 * The SHORTEST interval between two cut attempts [secondCutAttemptAfter] can produce — its
 * `padFrames = 0` case, and the value `SileroEndpointerTest`'s own on-grid boundary test
 * (`exactly_the_interval_commits_and_one_millisecond_more_merges`) is built on. Every longer
 * interval is this plus whole 32 ms frames of padding silence.
 */
private val FIXTURE_FLOOR_MS = EndpointerGrid.FIXTURE_INTERVAL_MS

/**
 * A scripted stand-in for the native Silero probe: every frame gets [next], whatever it contains.
 * It keeps no frames — the accumulator contract (`the_probe_is_handed_ONE_reused_array`) is
 * `SileroEndpointerTest`'s to pin, and nothing here looks at audio at all.
 */
private class CadenceProbe(var next: Float = 0f) : (ByteArray) -> Float {
    override fun invoke(frame: ByteArray): Float = next
}

/**
 * Drives one endpointer at the real 32 ms frame cadence, holding the wall clock between stretches.
 *
 * A deliberate near-copy of `SileroEndpointerTest`'s `Pump` rather than a shared fixture: that one
 * is a `private` top-level class in another package's test file, and widening its visibility to
 * serve one cross-package test would put a test-only type on the audio package's surface.
 */
private class CadencePump(
    private val ep: SileroEndpointer,
    private val probe: CadenceProbe,
    var t: Long = CROSS_CHECK_BASE,
) {
    var commits = 0
    var lastCommitMs = -1L

    /** Feeds [frames] complete frames of probability [p]. @return true if any of them committed. */
    fun run(p: Float, frames: Int): Boolean {
        probe.next = p
        var fired = false
        repeat(frames) {
            if (ep.onFrame(ByteArray(EndpointerTuning.FRAME_BYTES), 0, t)) {
                fired = true
                commits++
                lastCommitMs = t
            }
            t += EndpointerTuning.FRAME_MS
        }
        return fired
    }
}

/**
 * The 3.7 cost governor, JVM-pinned. Every number here is MEASURED (Fold6, vc77, floor 512,
 * production backends) or derived from a measurement — changing one is a decision, not an edit.
 */
class CommitCadencePolicyTest {

    @Test
    fun theShippedIntervalsAreTheMeasuredOnes() {
        assertEquals(1_200L, CommitCadencePolicy.MIN_COMMIT_INTERVAL_FAST_MS)
        assertEquals(3_200L, CommitCadencePolicy.MIN_COMMIT_INTERVAL_TURBO_MS)
        assertEquals(6_000L, CommitCadencePolicy.MIN_COMMIT_INTERVAL_MULTI_MS)
        assertEquals(8_000L, CommitCadencePolicy.MIN_COMMIT_INTERVAL_LARGE_MS)
        assertEquals(3_000L, CommitCadencePolicy.MIN_COMMIT_INTERVAL_CLOUD_MS)
        assertEquals(3_000L, CommitCadencePolicy.CAP_CUT_MAX_RETAIN_MS)
    }

    @Test
    fun proIsPacedAsTheSmallClassTierItIsWheneverTheGpuIsNotThere() {
        // 3.7 gave pro the FAST row on "F = 0.77-1.0 s measured on GPU". 4.4 moved it, on evidence
        // this repo already held: pro is ggml-small.en-q5_1 and multi is ggml-small-q5_1 — the
        // same whisper-small encoder 13 KB apart — and the MEASURED cost of those weights without
        // the GPU is multi's 2.3 s. GpuPolicy.ALLOWED_RENDERERS is `Adreno (TM) (7dd|8dd|Xd)`, so
        // every Mali/PowerVR/Xclipse/pre-7xx device runs pro on 4 CPU threads, and the chooser is
        // GPU-blind (pro's eligibility is RAM-only), so that is most of the fleet — including
        // exactly the devices the NPU gate declines.
        //
        // At HANGOVER_MS = 500 the wrong row was unreachable (cuts 6-11/min against a 26/min
        // service rate); the 4.4 retune is what makes it reachable.
        //
        // AND THERE IS NO TRADE, which an earlier draft of this comment got wrong: pro is
        // CPU-ONLY BY DESIGN (owner ruling 2026-09-02 — "that model does not run on GPU. It's
        // supposed to only run on CPU because GPU was much slower. So the GPU path is essentially
        // dead."), so no Adreno-7xx/8xx user loses cadence here; there is no GPU pro path for one
        // to run on. Do not split this row on the load-time GPU verdict — that would build a
        // second row and a test matrix for a case the product does not have.
        assertEquals(6_000L, CommitCadencePolicy.minCommitIntervalMs("pro", isCloudBatch = false))
        assertEquals(
            "pro takes multi's row because it IS multi without the GPU — not its own new number",
            CommitCadencePolicy.minCommitIntervalMs("multi", isCloudBatch = false),
            CommitCadencePolicy.minCommitIntervalMs("pro", isCloudBatch = false),
        )
    }

    @Test
    fun theSixtyMbTiersKeepTheFastCadence() {
        // eco/base stay at 1200, and the object KDoc records what that rests on — which is LESS
        // than the earlier draft of this comment claimed. The repo's only slice bench predates the
        // 768 -> 512 audio_ctx move so it overstates today's cost, but THE CHECK FAILS EVEN
        // OVERSTATED: base fits F = 1.15 s and eco F = 0.74 s, i.e. F/floor + m of ~1.0 and ~0.83
        // against the table's own 0.70 rule. Granting the full discount lands base at ~0.69, zero
        // margin. So this row is EXEMPTED, not cleared, and "overstating is the safe direction" is
        // only true when the overstated check passes. The re-bench is the named residual, and if a
        // legacy-tier user reports lag after the 4.4 retune this row is the first place to look.
        assertEquals(1_200L, CommitCadencePolicy.minCommitIntervalMs("eco", isCloudBatch = false))
        assertEquals(1_200L, CommitCadencePolicy.minCommitIntervalMs("base", isCloudBatch = false))
    }

    @Test
    fun multiIsPacedByItsMeasuredFixedCost() {
        // F=2.3 s, m~0.45, S=38.4 s/min: F*N + m*S <= 0.70*60 -> N <= ~10.7 commits/min.
        assertEquals(6_000L, CommitCadencePolicy.minCommitIntervalMs("multi", isCloudBatch = false))
    }

    @Test
    fun theUnmeasuredLargeTiersGetTheConservativeInterval() {
        assertEquals(8_000L, CommitCadencePolicy.minCommitIntervalMs("extreme", isCloudBatch = false))
        assertEquals(8_000L, CommitCadencePolicy.minCommitIntervalMs("ultra", isCloudBatch = false))
    }

    @Test
    fun anUnknownOrAbsentTierAssumesTheExpensiveEnd() {
        assertEquals(8_000L, CommitCadencePolicy.minCommitIntervalMs(null, isCloudBatch = false))
        assertEquals(8_000L, CommitCadencePolicy.minCommitIntervalMs("smallish", isCloudBatch = false))
    }

    @Test
    fun everyCatalogTierIsNamedExplicitly() {
        // A tier added to the catalog without a cadence decision silently inherits the 8 s
        // conservative default and nobody notices. This is the alarm for that.
        // 4.0: npu joined the catalog and this pin fired, exactly as designed. The decision it
        // asked for: 1_200L — the FAST row. Same whisper-small weights as multi, but the encoder
        // runs on the Hexagon at ~405 ms sustained (spike-measured) against multi's 2.3 s fixed
        // cost, and the decode is bounded at 196 tokens; the 6 s floor would have thrown the win
        // away. Provisional on one spike pass — Q10a measures the full tier on device.
        // 4.1: npu-turbo joined and the pin fired again. The decision: 1_200L, the FAST row —
        // see npuTurboRidesTheFastRowOnItsPublishedFigures for the reasoning and its trigger.
        val expected = mapOf(
            "eco" to 1_200L, "base" to 1_200L, "pro" to 6_000L,
            "multi" to 6_000L, "extreme" to 8_000L, "ultra" to 8_000L,
            "npu" to 1_200L, "npu-turbo" to 3_200L,
        )
        assertEquals(
            "a catalog tier gained or lost an entry — decide its cadence",
            expected.keys,
            WhisperCatalog.entries.map { it.id }.toSet(),
        )
        for ((id, interval) in expected) {
            assertEquals(id, interval, CommitCadencePolicy.minCommitIntervalMs(id, isCloudBatch = false))
        }
    }

    @Test
    fun npuTurboHasItsOwnFloorBecauseTheDeviceMeasurementCameIn() {
        // 4.1 put turbo on the FAST row on PUBLISHED 8 Gen 3 figures (~1.37-1.57 s/segment) and
        // wrote down the trigger for revisiting it: "if the owner's `npu:` lines show per-segment
        // cost above this cadence, commits will visibly lag, and that is the signal to give turbo
        // its own constant rather than to widen the FAST row." 4.4: the measurement came in at
        // 2.06 s/segment on the Fold6 (57 segments), above the whole published range. F = 1.89 s
        // fixed + 10.08 ms/token, tokens conserved at ~3.2/s of audio, so the object's own rule
        // `F*N + m*S <= 0.70*60` gives N <= 21.2 commits/min -> a 2.83 s mean interval.
        //
        // The floor is 3 200, ABOVE that answer, and the margin is the point: see the constant.
        assertEquals(3_200L, CommitCadencePolicy.minCommitIntervalMs("npu-turbo", isCloudBatch = false))
        assertEquals(
            "turbo left the FAST row rather than widening it: npu measures ~0.4 s on the same " +
                "silicon and 1200 is right for it",
            1_200L,
            CommitCadencePolicy.minCommitIntervalMs("npu", isCloudBatch = false),
        )
        // The duty arithmetic this floor IS, restated where a change to the constant must face it.
        val fixedCostMs = 1_890L
        val marginalPerMinuteMs = 1_900L
        val commitsPerMinute = 60_000L / CommitCadencePolicy.MIN_COMMIT_INTERVAL_TURBO_MS
        assertTrue(
            "a saturated npu-turbo session must stay inside the 0.70 duty ceiling this object is " +
                "derived from: $commitsPerMinute commits/min x $fixedCostMs ms + " +
                "$marginalPerMinuteMs ms of decode must not exceed 42 000 ms of every minute",
            commitsPerMinute * fixedCostMs + marginalPerMinuteMs <= 42_000L,
        )
        // AND the margin, which the strict answer does not have. This is the assertion that fails
        // if anyone lowers the floor back onto the formula: at 2 800 the saturated duty is 70.8%,
        // i.e. already over the ceiling, and there is nothing left for a thermally throttled
        // encode or for a concurrent batch job holding NativeComputeGate's fair lock. 2 140 ms is
        // F + 13%, past the capture's own worst observed encode (1 863 ms) plus a decode tail.
        val throttledFixedCostMs = 2_140L
        assertTrue(
            "the floor must still hold the 0.70 ceiling if the fixed cost rises to " +
                "$throttledFixedCostMs ms. It is derived from the WORST sustained F, not the " +
                "median one: one device in one thermal state, and the app has no thermal guard " +
                "anywhere, so this margin is the thermal policy.",
            commitsPerMinute * throttledFixedCostMs + marginalPerMinuteMs <= 42_000L,
        )
        // And cloud batch still wins outright on the npu-class tiers, exactly as it does on every
        // other row — the flat floor is about the HTTP request, not the local silicon.
        assertEquals(3_000L, CommitCadencePolicy.minCommitIntervalMs("npu-turbo", isCloudBatch = true))
        assertEquals(3_000L, CommitCadencePolicy.minCommitIntervalMs("npu", isCloudBatch = true))
    }

    @Test
    fun cloudBatchRaisesAFastTierToTheRequestFloor() {
        // Every batch commit is one HTTP POST: Semaphore(3) in flight, shed at 24.
        assertEquals(3_000L, CommitCadencePolicy.minCommitIntervalMs("pro", isCloudBatch = true))
        assertEquals(3_000L, CommitCadencePolicy.minCommitIntervalMs("eco", isCloudBatch = true))
    }

    @Test
    fun cloudBatchIsAFlatFloorForEveryTier() {
        // The spec's tuning table lists cloud batch as ONE row, not as a per-tier maximum, and
        // isCloudBatch wins outright. In CLOUD_WITH_FALLBACK the cloud engine is primary and the
        // local mirror only transcribes on a rescue, so pacing every cloud session at the slower
        // local tier's floor would slow the engine that is actually doing the work; the
        // failure-path drain is bounded by the reserve mechanics, not by this number.
        assertEquals(3_000L, CommitCadencePolicy.minCommitIntervalMs("multi", isCloudBatch = true))
        assertEquals(3_000L, CommitCadencePolicy.minCommitIntervalMs("ultra", isCloudBatch = true))
        assertEquals(3_000L, CommitCadencePolicy.minCommitIntervalMs(null, isCloudBatch = true))
    }

    @Test
    fun noOfferMeansNoSplit() {
        assertEquals(0L, CommitCadencePolicy.capCutRetainMs(nowMs = 50_000L, cutPointMs = Endpointer.NO_CUT_POINT))
        assertEquals(0L, CommitCadencePolicy.capCutRetainMs(nowMs = 50_000L, cutPointMs = -1L))
    }

    /**
     * The sentinel guard, at the ONLY clock where it is observable.
     *
     * Above, and in every other case in this class, [Endpointer.NO_CUT_POINT] is refused twice
     * over: the guard rejects it AND the staleness test would reject it anyway, because a 0L cut
     * point 50 s into a session is a 50 000 ms retain and every retain over
     * [CommitCadencePolicy.CAP_CUT_MAX_RETAIN_MS] returns 0. So the guard can be deleted — relaxed
     * to `<` — and nothing above notices.
     *
     * It only matters NEAR THE CLOCK ORIGIN, which is exactly the hazard [Endpointer.NO_CUT_POINT]'s
     * own KDoc documents: 0 is a legal wall-clock reading, merely unreachable in practice, and an
     * endpointer moved to a monotonic or session-relative clock would report the sentinel from a
     * live origin. [nowMs] must therefore sit WITHIN [CommitCadencePolicy.CAP_CUT_MAX_RETAIN_MS] of
     * the sentinel for this assertion to pin anything at all: at 2 000 ms a relaxed guard falls
     * through to a 2 000 ms retain — "split the segment at the sentinel" — instead of 0.
     */
    @Test
    fun theSentinelIsRefusedEvenWhenTheClockIsYoungerThanTheStalenessWindow() {
        assertTrue(
            "this test only pins the guard while nowMs is inside the staleness window",
            2_000L - Endpointer.NO_CUT_POINT <= CommitCadencePolicy.CAP_CUT_MAX_RETAIN_MS,
        )
        assertEquals(0L, CommitCadencePolicy.capCutRetainMs(nowMs = 2_000L, cutPointMs = Endpointer.NO_CUT_POINT))
    }

    @Test
    fun aRecentMicroPauseIsTheRetainedTail() {
        assertEquals(900L, CommitCadencePolicy.capCutRetainMs(nowMs = 50_000L, cutPointMs = 49_100L))
        assertEquals(3_000L, CommitCadencePolicy.capCutRetainMs(nowMs = 50_000L, cutPointMs = 47_000L))
    }

    @Test
    fun aStaleOfferIsRefusedRatherThanDeferringHalfTheWindow() {
        // A pause 13 s back is not "the boundary near where the cap fired": taking it would defer
        // 13 s of audio into the next cap window and push the effective wall bound to ~28 s.
        assertEquals(0L, CommitCadencePolicy.capCutRetainMs(nowMs = 50_000L, cutPointMs = 37_000L))
        assertEquals(0L, CommitCadencePolicy.capCutRetainMs(nowMs = 50_000L, cutPointMs = 46_999L))
    }

    @Test
    fun aFutureOrEqualCutPointIsRefused() {
        assertEquals(0L, CommitCadencePolicy.capCutRetainMs(nowMs = 50_000L, cutPointMs = 50_000L))
        assertEquals(0L, CommitCadencePolicy.capCutRetainMs(nowMs = 50_000L, cutPointMs = 50_500L))
    }

    /**
     * THE SEAM. [Endpointer.onSessionStart]'s KDoc promises that an implementation which has not
     * been given a cadence yet "must assume the expensive end
     * ([CommitCadencePolicy.MIN_COMMIT_INTERVAL_LARGE_MS])", and [SileroEndpointer] keeps that
     * promise with a LITERAL `8_000L` field initialiser. Two numbers, one contract, and until this
     * test nothing joined them: `SileroEndpointerTest`'s
     * `before_any_session_start_the_floor_is_the_conservative_8000` brackets the endpointer's
     * literal from its own side (Workstream C compiles without the service package, so it may not
     * import this object), and [theShippedIntervalsAreTheMeasuredOnes] pins this object's constant
     * from the other. Either side could move alone and both would stay green.
     *
     * So this one is BEHAVIOURAL and reads the CONSTANT: it drives a real [SileroEndpointer] that
     * has never been given a session, and shows its merge/commit boundary standing exactly at
     * [CommitCadencePolicy.MIN_COMMIT_INTERVAL_LARGE_MS]. Change the constant or change the
     * endpointer's default and this fails; they can only move together.
     *
     * BOTH SIDES of the boundary are stated, because neither is worth anything alone. "8 000 ms
     * commits" alone survives a default of 6 000 — a floor the attempt clears just as easily — and
     * "7 968 ms merges" alone survives a default of 60 000. The gate is
     * `nowMs - lastCommitMs < minCommitIntervalMs`, so exactly the interval has ELAPSED and
     * commits, and one FRAME less of elapsed time merges the same endpoint.
     *
     * One frame, not one millisecond, is the tightest bracket reachable here, and that is
     * structural: `SileroEndpointerTest`'s equivalent test varies the INTERVAL, which a caller
     * chooses freely, while this one must vary the cut ATTEMPT, which lands on the 32 ms frame
     * grid. 8 000 = 250 x 32 puts the boundary itself exactly on that grid — the grid guard inside
     * [secondCutAttemptAfter] fails loudly if a retune ever takes it off.
     */
    @Test
    fun theEndpointersPreSessionFloorIsThisObjectsLargeInterval() {
        val onTheBoundary = secondCutAttemptAfter(CommitCadencePolicy.MIN_COMMIT_INTERVAL_LARGE_MS)
        assertEquals(
            "exactly the interval has ELAPSED — the endpoint is not INSIDE the window",
            2,
            onTheBoundary.commits,
        )
        assertEquals(
            FIRST_COMMIT_MS + CommitCadencePolicy.MIN_COMMIT_INTERVAL_LARGE_MS,
            onTheBoundary.lastCommitMs,
        )
        val oneFrameInside = secondCutAttemptAfter(
            CommitCadencePolicy.MIN_COMMIT_INTERVAL_LARGE_MS - EndpointerTuning.FRAME_MS,
        )
        assertEquals(
            "one frame earlier and the same endpoint merges",
            1,
            oneFrameInside.commits,
        )
    }

    /**
     * Runs a fresh [SileroEndpointer] — deliberately WITHOUT [SileroEndpointer.onSessionStart],
     * which is the entire point — through two endpoints [intervalMs] apart, and returns the pump so
     * the caller can count what actually committed.
     *
     * The arithmetic, all of it on the 32 ms frame grid and all of it DERIVED from the tuning
     * table: 20 speech frames open the gate at [CROSS_CHECK_BASE],
     * [EndpointerGrid.HANGOVER_FRAMES] silence frames end that utterance (the last of them is the
     * first at or past [EndpointerTuning.HANGOVER_MS]) and commit 1 lands at [FIRST_COMMIT_MS] —
     * the session's first cut is free on every floor. From the next frame the gate is shut, so
     * `padFrames` of silence pass through `onProb`'s `!speaking` return doing nothing but moving
     * the clock; then [EndpointerGrid.SPEECH_FRAMES_OVER_MIN] speech frames (352 ms, over
     * [EndpointerTuning.MIN_SPEECH_MS]) and one more full dip put the SECOND endpoint at
     * `commit1 + FIXTURE_FLOOR_MS + 32 * padFrames`. [FIXTURE_FLOOR_MS] is
     * [EndpointerGrid.FIXTURE_INTERVAL_MS], a multiple of the frame at every hangover — which is
     * what keeps the grid guard below satisfiable without re-choosing the floors it pads out to.
     */
    private fun secondCutAttemptAfter(intervalMs: Long): CadencePump {
        assertEquals(
            "this fixture can only reach cut attempts ON the 32 ms frame grid",
            0L,
            (intervalMs - FIXTURE_FLOOR_MS) % EndpointerTuning.FRAME_MS,
        )
        val probe = CadenceProbe()
        val pump = CadencePump(SileroEndpointer(probe = probe), probe)
        pump.run(SPEECH, 20)
        assertTrue(
            "the session's first cut is free on every floor",
            pump.run(SILENCE, EndpointerGrid.HANGOVER_FRAMES),
        )
        assertEquals(FIRST_COMMIT_MS, pump.lastCommitMs)
        val padFrames = ((intervalMs - FIXTURE_FLOOR_MS) / EndpointerTuning.FRAME_MS).toInt()
        pump.run(SILENCE, padFrames)
        pump.run(SPEECH, EndpointerGrid.SPEECH_FRAMES_OVER_MIN)
        pump.run(SILENCE, EndpointerGrid.HANGOVER_FRAMES)
        return pump
    }
}
