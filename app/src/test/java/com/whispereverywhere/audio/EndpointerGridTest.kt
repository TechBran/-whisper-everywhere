package com.whispereverywhere.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val B = EndpointerTuning.FRAME_BYTES
private const val GRID_BASE = 2_000_000L
private val BELOW_LARGE_MS = EndpointerGrid.BELOW_LARGE_MS
private val ABOVE_LARGE_MS = EndpointerGrid.ABOVE_LARGE_MS

/**
 * Where `SileroEndpointerTest.reset_anchors_the_governor_on_the_last_frame_seen`'s endpoint lands
 * measured from the SESSION OPEN rather than from the cap cut: its opening run of 20 speech frames,
 * then the [EndpointerGrid.SPEECH_FRAMES_OVER_MIN] frames of the utterance under test, then one
 * hangover's trail. The reset-anchored twin of this quantity is
 * [EndpointerGrid.FIXTURE_INTERVAL_MS], and the fixture only discriminates while the 1200 ms floor
 * it drives sits strictly between the two.
 */
private val SESSION_ANCHORED_MS =
    (20 + EndpointerGrid.SPEECH_FRAMES_OVER_MIN) * EndpointerTuning.FRAME_MS +
        EndpointerGrid.HANGOVER_TRAIL_MS

/**
 * THE TUNABILITY CONTRACT for [EndpointerTuning.HANGOVER_MS].
 *
 * Two obligations live here and nowhere else, and together they are what makes the hangover a knob
 * an owner can turn without a suite-wide edit:
 *
 *  1. [the_grid_matches_the_machine] — [EndpointerGrid]'s integer arithmetic and the state
 *     machine's own `nowMs - tempEndMs < HANGOVER_MS` agree about which frame cuts and how much
 *     trail it reports. Two independent derivations agreeing is the property; either alone is a
 *     restatement, which is why the arithmetic is not simply asserted against literals.
 *
 *  2. [the_fixture_grid_is_valid_for_this_hangover] — the scenarios the rest of the suite builds
 *     on this grid are still REACHABLE at the chosen value. This is the half that does not exist
 *     anywhere else in the codebase and is the whole reason a derived fixture is not automatically
 *     a safe fixture: a run derived as "HOLD_FRAMES silent frames" cannot cut too early, but a
 *     hangover short enough that the micro-pause promotion no longer fits INSIDE a non-cutting dip
 *     turns four micro-pause tests into tests of something else, silently and greenly. Each
 *     assertion below names the fixtures it protects, so a failure hands the author the list of
 *     tests to re-derive instead of a number to change.
 */
class EndpointerGridTest {

    /**
     * The grid's arithmetic, cross-checked against the machine at the shipped value.
     *
     * Driven through the same 32 ms cadence the fixtures use, because that cadence — not the
     * millisecond — is what the counts describe. The boundary itself (`<` versus `<=`) is
     * `SileroEndpointerTest.the_hangover_fires_at_exactly_HANGOVER_MS`'s to state; this one asks
     * only "which frame, and how much trail", which is exactly what [EndpointerGrid] claims.
     */
    @Test fun the_grid_matches_the_machine() {
        val probe = GridProbe()
        val ep = SileroEndpointer(probe = probe)
        var t = GRID_BASE
        probe.next = 0.9f
        repeat(20) { ep.onFrame(ByteArray(B), 0, t); t += EndpointerTuning.FRAME_MS }
        val dipStart = t
        probe.next = 0.1f
        var cutOn = 0
        var frame = 0
        while (cutOn == 0 && frame < 4 * EndpointerGrid.HANGOVER_FRAMES) {
            frame++
            if (ep.onFrame(ByteArray(B), 0, t)) cutOn = frame
            t += EndpointerTuning.FRAME_MS
        }
        assertEquals(
            "EndpointerGrid.HANGOVER_FRAMES says which frame of the dip cuts; the machine " +
                "disagrees. One of the two derivations is wrong — the ceil() here, or the " +
                "`nowMs - tempEndMs < HANGOVER_MS` guard in SileroEndpointer.onProb.",
            EndpointerGrid.HANGOVER_FRAMES,
            cutOn,
        )
        val cut = requireNotNull(ep.lastCut())
        assertEquals(
            "EndpointerGrid.HANGOVER_TRAIL_MS is what EndpointCut.trailMs actually reports",
            EndpointerGrid.HANGOVER_TRAIL_MS,
            cut.trailMs,
        )
        assertEquals(
            "the trail is measured from the dip's FIRST frame, so the commit lands there plus it",
            dipStart + EndpointerGrid.HANGOVER_TRAIL_MS,
            t - EndpointerTuning.FRAME_MS,
        )
        assertEquals(
            "and HOLD_FRAMES is the longest run that cannot cut",
            EndpointerGrid.HANGOVER_FRAMES - 1,
            EndpointerGrid.HOLD_FRAMES,
        )
    }

    /**
     * The fixtures' preconditions, each with the tests it protects named in its own message.
     *
     * A retune that trips one of these has not broken the endpointer — it has moved the hangover
     * out of the range in which some OTHER property can still be demonstrated. That is a real
     * decision and it belongs to whoever turns the knob, which is why it fails here, once, with
     * the affected fixtures listed, instead of as a scatter of unrelated-looking reds.
     */
    @Test fun the_fixture_grid_is_valid_for_this_hangover() {
        val h = EndpointerTuning.HANGOVER_MS

        // --- production invariants, not fixture ones, but this is where they become visible ---
        assertTrue(
            "HANGOVER_MS ($h) is below the ACOUSTIC floor HANGOVER_MIN_MS " +
                "(${EndpointerTuning.HANGOVER_MIN_MS}). Nothing in the machine objects — the " +
                "merge-path invariant holds down to 98 ms and the batch pad down to ~288 — which " +
                "is exactly why this floor has to be stated: below it the hangover stops ending " +
                "utterances and starts cutting inside them (word junctures 100-200 ms, stop " +
                "closures 50-150 ms), and `no_context = true` makes that unrepairable in both " +
                "directions. Moving this floor is a claim about SPEECH, not about the fixtures.",
            h >= EndpointerTuning.HANGOVER_MIN_MS,
        )
        assertTrue(
            "HANGOVER_MS ($h) must exceed MICRO_PAUSE_MS (${EndpointerTuning.MICRO_PAUSE_MS}). " +
                "SileroEndpointer.onProb's cost-governor branch DELIBERATELY omits " +
                "`prevEndMs = tempEndMs` on the strength of the micro-pause promotion having " +
                "already run in the same call, which is true only while the hangover is the " +
                "longer of the two. Below this the 15 s wall cap silently stops being offered " +
                "cut points on the merge path.",
            h > EndpointerTuning.MICRO_PAUSE_MS,
        )
        assertTrue(
            "HANGOVER_TRAIL_MS (${EndpointerGrid.HANGOVER_TRAIL_MS}) is the trailing audio a " +
                "commit hands the batch VAD filter, whose speech_pad_ms is 150 " +
                "(whisper_jni.cpp:191-192). Shrink it past that and the pad is clamped to the " +
                "buffer at whisper.cpp:5682-5684 — silently, on the CPU tiers only. See " +
                "NativeVadSourceContractTest.",
            EndpointerGrid.HANGOVER_TRAIL_MS >= 150L,
        )

        // --- fixture reachability ---
        assertTrue(
            "the micro-pause promotion must fit INSIDE a non-cutting dip: MICRO_PAUSE_FRAMES " +
                "(${EndpointerGrid.MICRO_PAUSE_FRAMES}) <= HOLD_FRAMES " +
                "(${EndpointerGrid.HOLD_FRAMES}). Below this, no_micro_pause_is_offered_until_a_" +
                "dip_outlives_98ms, the_micro_pause_floor_is_exclusive_at_exactly_MICRO_PAUSE_MS, " +
                "the_micro_pause_survives_a_re_onset_within_the_same_stretch and " +
                "reset_clears_the_micro_pause_memory stop testing the micro-pause and start " +
                "testing the hangover.",
            EndpointerGrid.MICRO_PAUSE_FRAMES <= EndpointerGrid.HOLD_FRAMES,
        )
        assertTrue(
            "dead_band_frames_do_not_stall_the_hangover_hard_timer splits a non-cutting dip into " +
                "one stamping frame, ${EndpointerGrid.DEAD_BAND_FRAMES} dead-band frames and the " +
                "rest silence; it needs at least one of each.",
            EndpointerGrid.DEAD_BAND_FRAMES >= 1 &&
                EndpointerGrid.HOLD_FRAMES - 1 - EndpointerGrid.DEAD_BAND_FRAMES >= 1,
        )
        assertTrue(
            "the_frame_that_trips_the_latch_has_its_verdict_discarded places " +
                "PROBE_CUTOUT_FRAMES (${EndpointerTuning.PROBE_CUTOUT_FRAMES}) frames one " +
                "millisecond apart INSIDE the dip, so they must not themselves reach the " +
                "hangover.",
            EndpointerTuning.PROBE_CUTOUT_FRAMES < h,
        )
        assertTrue(
            "pro_merges_an_utterance_that_endpoints_inside_1200ms needs its second endpoint " +
                "INSIDE the FAST row's 1200 ms floor and its third OUTSIDE, on an interval of " +
                "${EndpointerGrid.FIXTURE_INTERVAL_MS} ms. (1200 is quoted from " +
                "CommitCadencePolicy.MIN_COMMIT_INTERVAL_FAST_MS, not imported, exactly as that " +
                "fixture quotes it.)",
            EndpointerGrid.FIXTURE_INTERVAL_MS < 1_200L &&
                2 * EndpointerGrid.FIXTURE_INTERVAL_MS >= 1_200L,
        )
        assertEquals(
            "CommitCadencePolicyTest.secondCutAttemptAfter pads FIXTURE_INTERVAL_MS out to a " +
                "tier floor in whole 32 ms frames, so the interval must sit ON the grid.",
            0L,
            EndpointerGrid.FIXTURE_INTERVAL_MS % EndpointerTuning.FRAME_MS,
        )
        assertTrue(
            "reset_anchors_the_governor_on_the_last_frame_seen needs 1200 STRICTLY BETWEEN its " +
                "reset-anchored interval (${EndpointerGrid.FIXTURE_INTERVAL_MS} ms, the clause " +
                "above) and its session-anchored one (${SESSION_ANCHORED_MS} ms). Below this both " +
                "anchors merge and that test passes against an endpointer that re-anchors on " +
                "nothing at all. It is NOT implied by the pro_merges clause: the two reduce to " +
                "the same integer boundary today by coincidence, not by derivation.",
            SESSION_ANCHORED_MS >= 1_200L,
        )
        assertTrue(
            "SPEECH_FRAMES_OVER_MIN (${EndpointerGrid.SPEECH_FRAMES_OVER_MIN} frames = " +
                "${EndpointerGrid.SPEECH_FRAMES_OVER_MIN * EndpointerTuning.FRAME_MS} ms) must " +
                "clear MIN_SPEECH_MS (${EndpointerTuning.MIN_SPEECH_MS}) strictly, or every " +
                "cadence fixture's speech run is DISCARDED instead of endpointed and the whole " +
                "governor family goes green while testing nothing.",
            EndpointerGrid.SPEECH_FRAMES_OVER_MIN * EndpointerTuning.FRAME_MS >
                EndpointerTuning.MIN_SPEECH_MS,
        )
        assertTrue(
            "before_any_session_start_the_floor_is_the_conservative_8000 solves for the silence " +
                "padding between its two brackets, and `coerceAtLeast(0)` silently degrades the " +
                "second bracket from EXACT to OVERSHOOT once one full endpoint no longer fits in " +
                "the ${ABOVE_LARGE_MS - BELOW_LARGE_MS} ms between them. Overshoot still passes " +
                "that assertion, so nothing would go red — this clause is what makes the clamp a " +
                "DECISION rather than an accident.",
            (ABOVE_LARGE_MS - BELOW_LARGE_MS) / EndpointerTuning.FRAME_MS >=
                EndpointerGrid.HANGOVER_FRAMES + EndpointerGrid.SPEECH_FRAMES_OVER_MIN,
        )

        // --- prose that would go false without going red ---
        assertTrue(
            "HANGOVER_MS ($h) is a multiple of the 32 ms frame. Nothing BREAKS — but two KDocs " +
                "now lie: the_hangover_fires_at_exactly_HANGOVER_MS says the pump 'can never " +
                "land ON HANGOVER_MS', which is the entire reason that test drives onFrame " +
                "directly, and exactly_the_interval_commits_and_one_millisecond_more_merges says " +
                "'none of 300 / $h / 98 is a multiple of the 32 ms frame'. Choose an off-grid " +
                "value, or rewrite both sentences and delete this assertion deliberately.",
            h % EndpointerTuning.FRAME_MS != 0L,
        )
    }
}

private class GridProbe(var next: Float = 0f) : (ByteArray) -> Float {
    override fun invoke(frame: ByteArray): Float = next
}
