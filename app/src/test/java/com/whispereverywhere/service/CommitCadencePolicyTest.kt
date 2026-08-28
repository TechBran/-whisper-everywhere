package com.whispereverywhere.service

import com.whispereverywhere.audio.Endpointer
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
private const val FIXTURE_FLOOR_MS = 896L

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
        assertEquals(6_000L, CommitCadencePolicy.MIN_COMMIT_INTERVAL_MULTI_MS)
        assertEquals(8_000L, CommitCadencePolicy.MIN_COMMIT_INTERVAL_LARGE_MS)
        assertEquals(3_000L, CommitCadencePolicy.MIN_COMMIT_INTERVAL_CLOUD_MS)
        assertEquals(3_000L, CommitCadencePolicy.CAP_CUT_MAX_RETAIN_MS)
    }

    @Test
    fun proRunsTrueUtteranceCadence() {
        // F = 0.77-1.0 s measured on GPU: below ~1.1 s a commit is zero-padded to the same
        // encoder cost anyway, so merging beats committing.
        assertEquals(1_200L, CommitCadencePolicy.minCommitIntervalMs("pro", isCloudBatch = false))
    }

    @Test
    fun theSixtyMbTiersRideTheSameFastCadenceAsPro() {
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
        val expected = mapOf(
            "eco" to 1_200L, "base" to 1_200L, "pro" to 1_200L,
            "multi" to 6_000L, "extreme" to 8_000L, "ultra" to 8_000L,
            "npu" to 1_200L,
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
            CROSS_CHECK_BASE + 1_152L + CommitCadencePolicy.MIN_COMMIT_INTERVAL_LARGE_MS,
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
     * The arithmetic, all of it on the 32 ms frame grid: 20 speech frames open the gate at
     * [CROSS_CHECK_BASE], 17 silence frames end that utterance (the 17th is the first at or past
     * [EndpointerTuning.HANGOVER_MS]) and commit 1 lands at `BASE + 1152` — the session's first cut
     * is free on every floor. From the next frame the gate is shut, so `padFrames` of silence pass
     * through `onProb`'s `!speaking` return doing nothing but moving the clock; then 11 speech
     * frames (352 ms, over [EndpointerTuning.MIN_SPEECH_MS]) and 17 more silence frames put the
     * SECOND endpoint at `commit1 + 896 + 32 * padFrames`. [FIXTURE_FLOOR_MS] is that 896.
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
        assertTrue("the session's first cut is free on every floor", pump.run(SILENCE, 17))
        assertEquals(CROSS_CHECK_BASE + 1_152L, pump.lastCommitMs)
        val padFrames = ((intervalMs - FIXTURE_FLOOR_MS) / EndpointerTuning.FRAME_MS).toInt()
        pump.run(SILENCE, padFrames)
        pump.run(SPEECH, 11)
        pump.run(SILENCE, 17)
        return pump
    }
}
