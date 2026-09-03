package com.whispereverywhere.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val B = EndpointerTuning.FRAME_BYTES

// THE GRID, as SileroEndpointerTest aliases it. Every frame count below is derived; the values
// themselves are pinned once, in EndpointerTuningTest.
private val HANGOVER_FRAMES = EndpointerGrid.HANGOVER_FRAMES
private val HOLD_FRAMES = EndpointerGrid.HOLD_FRAMES
private val HANGOVER_TRAIL_MS = EndpointerGrid.HANGOVER_TRAIL_MS

/** The flat run's firing chunk, and the trail a flat cut reports when Silero's stamp coincides. */
private val FLAT_CHUNKS = EndpointerTuning.FLATLINE_CHUNKS
private val FLAT_TRAIL_MS: Long = (FLAT_CHUNKS - 1) * EndpointerTuning.FRAME_MS

/** Non-zero: 0L is the endpointer's "no micro-pause remembered" sentinel. */
private const val BASE = 1_000_000L

/**
 * The amplitude vocabulary of `tools/vadsim/tests/test_flatline.py`, in AudioMath's 0..32767 units:
 * a spoken word, room tone in a natural pause (50-300 is the band; never zero), and an editor's gate
 * — digital silence, the thing this trigger exists to see.
 */
private const val SPEECH_RMS = 3_000
private const val ROOM_TONE_RMS = 100
private const val GATED_RMS = 0

/**
 * The p values. The gated fixtures put the GAP in the DEAD BAND on purpose: a dead-band frame is
 * inert (`onProb` returns having written nothing), so Silero can never cut those traces at ANY
 * hangover — every commit in them is the flat trigger's, or there is none.
 */
private const val P_SPEECH = 0.9f
private const val P_GAP_DEADBAND = 0.40f
private const val P_SILENCE = 0.1f

private class FlatProbe(var next: Float = 0f) : (ByteArray) -> Float {
    override fun invoke(frame: ByteArray): Float = next
}

/**
 * `SileroEndpointerTest.Pump` with an amplitude beside the probability — the same shape as the
 * simulator's `test_flatline.Pump.run(p, rms, frames)`. Whole frames at the 32 ms cadence, ONE
 * clock, and `amp` is the chunk RMS the capture thread hands `onFrame` for every frame of a chunk.
 */
private class FlatPump(val ep: SileroEndpointer, val probe: FlatProbe, var t: Long = BASE) {
    var commits = 0
    var lastCommitMs = -1L

    fun run(p: Float, amp: Int, frames: Int, bytes: Int = B): Boolean {
        probe.next = p
        var fired = false
        repeat(frames) {
            if (ep.onFrame(ByteArray(bytes), amp, t)) {
                fired = true
                commits++
                lastCommitMs = t
            }
            t += EndpointerTuning.FRAME_MS
        }
        return fired
    }

    /** One WORD of edited video: `wordFrames` of speech, then `gapFrames` of digital silence. */
    fun gatedWord(wordFrames: Int = 12, gapFrames: Int = FLAT_CHUNKS): Boolean {
        run(P_SPEECH, SPEECH_RMS, wordFrames)
        return run(P_GAP_DEADBAND, GATED_RMS, gapFrames)
    }
}

/**
 * THE FLATLINE CUT (4.4), tested one design decision at a time against the reference twin,
 * `tools/vadsim/vadsim/machine.py`'s `SileroEndpointerSim._on_flat`. Every test here mirrors a test
 * in `tests/test_flatline.py` or `tests/test_flatline_verify.py` BY NAME and drives the same
 * `(p, rms)` trace; the Kotlin must produce the same commits at the same frames with the same
 * `speechMs` / `trailMs`. Where a fixture reads the simulator's private state (`ep.speaking`,
 * `ep.flat_run_frames`) this file reads the same fact off BEHAVIOUR instead — what the next frames
 * do — because the endpointer exposes no such accessors and should not grow them for a test.
 *
 * `minCommitIntervalMs = 0` throughout unless a test is ABOUT the governor: the shipped 2 000 ms
 * turbo floor would merge every second cut in a 4-word fixture and hide the trigger.
 */
class SileroEndpointerFlatlineTest {

    private fun fresh(
        minCommitIntervalMs: Long = 0L,
        armed: Boolean = true,
    ): Triple<SileroEndpointer, FlatProbe, FlatPump> {
        val probe = FlatProbe()
        val ep = SileroEndpointer(probe = probe)
        ep.onSessionStart(nowMs = BASE, minCommitIntervalMs = minCommitIntervalMs)
        ep.armFlatline(armed)
        return Triple(ep, probe, FlatPump(ep, probe))
    }

    // ---------------------------------------------------------------------------------------
    // 1. Natural speech is UNTOUCHED — the property the whole trigger rests on.
    // ---------------------------------------------------------------------------------------

    @Test fun natural_speech_with_room_tone_never_fires_the_flat_trigger() {
        val (ep, _, pump) = fresh()
        // Four words with the longest dip the hangover CANNOT cut, full of room tone: nothing may
        // commit — the flat run can never even START at 100 RMS.
        repeat(4) {
            pump.run(P_SPEECH, SPEECH_RMS, 12)
            assertFalse(pump.run(P_SILENCE, ROOM_TONE_RMS, HOLD_FRAMES))
        }
        assertEquals(0, pump.commits)
        assertNull(ep.lastCut())
        // And a pause of ANY length in room tone is Silero's to cut, exactly as before: the
        // hangover on its own twelfth frame, kind VAD, trail HANGOVER_TRAIL_MS. The gate has been
        // open since frame 0 (no dip above cut it), so speech is measured from there.
        pump.run(P_SPEECH, SPEECH_RMS, 12)
        assertTrue(pump.run(P_SILENCE, ROOM_TONE_RMS, 60))
        assertEquals(1, pump.commits)
        assertEquals(
            EndpointCut(
                speechMs = (4 * (12 + HOLD_FRAMES) + 12) * EndpointerTuning.FRAME_MS,
                trailMs = HANGOVER_TRAIL_MS,
                prob = P_SILENCE,
            ),
            ep.lastCut(),
        )
        assertEquals(EndpointCutKind.VAD, ep.lastCut()!!.kind)
    }

    // ---------------------------------------------------------------------------------------
    // 2. Gated (edited) audio IS cut — on the fifth flat chunk, and not on the fourth.
    // ---------------------------------------------------------------------------------------

    @Test fun gated_audio_is_cut_on_the_fifth_flat_chunk_with_cut_flat_and_the_hangovers_own_bookkeeping() {
        val (ep, _, pump) = fresh()
        assertFalse(pump.run(P_SPEECH, SPEECH_RMS, 12))
        assertFalse("four flat chunks are one short", pump.run(P_GAP_DEADBAND, GATED_RMS, FLAT_CHUNKS - 1))
        assertTrue("the fifth fires", pump.run(P_GAP_DEADBAND, GATED_RMS, 1))
        // The first gap begins at frame 12 and the fifth of its frames is frame 16. Silero wrote
        // nothing during the dead-band gap, so `tempEndMs` was 0 at fire time and the pending end
        // is the run's first flat frame: speech is measured to it, trail from it (DECISION 6).
        assertEquals(BASE + 16 * EndpointerTuning.FRAME_MS, pump.lastCommitMs)
        assertEquals(
            EndpointCut(
                speechMs = 12 * EndpointerTuning.FRAME_MS,
                trailMs = FLAT_TRAIL_MS,
                prob = P_GAP_DEADBAND,
                kind = EndpointCutKind.FLAT,
            ),
            ep.lastCut(),
        )
        // One per gap, at a floor of 0.
        repeat(3) { assertTrue(pump.gatedWord()) }
        assertEquals(4, pump.commits)
    }

    @Test fun a_four_chunk_128ms_zero_run_does_not_fire_and_the_next_run_starts_afresh() {
        val (ep, _, pump) = fresh()
        pump.run(P_SPEECH, SPEECH_RMS, 12)
        assertFalse(pump.run(P_GAP_DEADBAND, GATED_RMS, FLAT_CHUNKS - 1))
        // Speech resumes: the four are forgotten (DECISION 4 — a chunk above the floor resets the
        // count to zero), and the gate has stayed open the whole time.
        assertFalse(pump.run(P_SPEECH, SPEECH_RMS, 12))
        assertEquals(0, pump.commits)
        assertFalse(pump.run(P_GAP_DEADBAND, GATED_RMS, FLAT_CHUNKS - 1))
        assertTrue(pump.run(P_GAP_DEADBAND, GATED_RMS, 1))
        // The gate opened at frame 0 and never closed, so speech runs to the SECOND run's first
        // frame: 12 + 4 + 12 = 28 frames.
        assertEquals(28 * EndpointerTuning.FRAME_MS, ep.lastCut()!!.speechMs)
        assertEquals(FLAT_TRAIL_MS, ep.lastCut()!!.trailMs)
    }

    @Test fun the_same_gated_audio_commits_nothing_without_the_trigger() {
        val (ep, _, pump) = fresh(armed = false)
        repeat(4) { assertFalse(pump.gatedWord()) }
        assertEquals(0, pump.commits)
        assertNull(ep.lastCut())
    }

    // ---------------------------------------------------------------------------------------
    // 3. A stop closure inside a word must survive — and the fixture is not vacuous.
    // ---------------------------------------------------------------------------------------

    @Test fun a_96ms_stop_closure_inside_a_word_is_not_cut() {
        val (ep, _, pump) = fresh()
        pump.run(P_SPEECH, SPEECH_RMS, 12)
        // A plosive: 50-150 ms of near-silence in the MIDDLE of a word, Silero still calling it
        // speech. Three flat chunks are two short of five.
        assertFalse(pump.run(P_SPEECH, GATED_RMS, 3))
        assertFalse(pump.run(P_SPEECH, SPEECH_RMS, 12))
        assertEquals(0, pump.commits)
        assertNull(ep.lastCut())
    }

    @Test fun mid_word_risk_counts_a_cut_silero_would_have_called_speech() {
        // DECISION 4, from the other side: a frame at or above ONSET does NOT reset the run,
        // because a `p` veto would restore exactly the blindness the trigger exists to work
        // around. So five flat chunks that Silero calls speech DO fire — the cut the owner is
        // afraid of, made possible on purpose and bounded by the amplitude floor alone.
        val (ep, _, pump) = fresh()
        pump.run(P_SPEECH, SPEECH_RMS, 12)
        assertFalse(pump.run(P_SPEECH, GATED_RMS, FLAT_CHUNKS - 1))
        assertTrue(pump.run(P_SPEECH, GATED_RMS, 1))
        // Every onset frame zeroed `tempEndMs` (`:540`), so the pending end is the run's start.
        assertEquals(
            EndpointCut(12 * EndpointerTuning.FRAME_MS, FLAT_TRAIL_MS, P_SPEECH, EndpointCutKind.FLAT),
            ep.lastCut(),
        )
    }

    // ---------------------------------------------------------------------------------------
    // 4. The governor's MERGE branch, reached from the flat side.
    // ---------------------------------------------------------------------------------------

    @Test fun a_flat_endpoint_inside_the_governor_window_merges_and_keeps_the_pending_buffer() {
        val (ep, _, pump) = fresh(minCommitIntervalMs = 2_000L)
        assertTrue("the first cut is free", pump.gatedWord())
        val first = ep.lastCut()
        assertEquals(BASE + 16 * EndpointerTuning.FRAME_MS, pump.lastCommitMs)
        // The second flat endpoint arrives 544 ms after the first commit — inside the floor.
        assertFalse("inside the floor: merged", pump.gatedWord())
        assertEquals(1, pump.commits)
        assertTrue("the merged audio is still in the buffer", ep.hasPendingSpeech())
        assertEquals("a merge is not a cut and leaves the record alone", first, ep.lastCut())
        // The gate was CLOSED by the merge (`closeGate`, as onProb's merge does), so the next
        // pause is judged afresh: with no onset since, more flat chunks count nothing.
        assertFalse(pump.run(P_GAP_DEADBAND, GATED_RMS, FLAT_CHUNKS))
        // And `lastCommitMs` did NOT move to the merge: a third endpoint 2 016 ms after the FIRST
        // commit (frame 79 - frame 16 = 63 frames) but only 1 472 ms after the merge (frame 33)
        // COMMITS. Had the merge re-anchored the governor it would merge again.
        val speechFrames = 79 - (12 + 5 + 12 + 5 + FLAT_CHUNKS) - FLAT_CHUNKS + 1
        assertFalse(pump.run(P_SPEECH, SPEECH_RMS, speechFrames))
        assertTrue(pump.run(P_GAP_DEADBAND, GATED_RMS, FLAT_CHUNKS))
        assertEquals(BASE + 79 * EndpointerTuning.FRAME_MS, pump.lastCommitMs)
        assertEquals(2, pump.commits)
        assertEquals(EndpointCutKind.FLAT, ep.lastCut()!!.kind)
    }

    // ---------------------------------------------------------------------------------------
    // 5. MIN_SPEECH, reached from the flat side.
    // ---------------------------------------------------------------------------------------

    @Test fun a_flat_run_after_too_little_speech_is_discarded_like_a_short_burst() {
        val (ep, _, pump) = fresh()
        pump.run(P_SPEECH, SPEECH_RMS, 5)                       // 160 ms <= MIN_SPEECH_MS (300)
        assertFalse(pump.run(P_GAP_DEADBAND, GATED_RMS, FLAT_CHUNKS))
        assertEquals(0, pump.commits)
        assertNull("a discard is not a cut", ep.lastCut())
        assertFalse("160 ms never latched pending speech", ep.hasPendingSpeech())
        // The gate is SHUT (closeGate): more flat chunks with no onset count nothing...
        assertFalse(pump.run(P_GAP_DEADBAND, GATED_RMS, FLAT_CHUNKS))
        // ...and the next real word is judged from its own onset.
        assertTrue(pump.gatedWord())
        assertEquals(12 * EndpointerTuning.FRAME_MS, ep.lastCut()!!.speechMs)
    }

    @Test fun exactly_MIN_SPEECH_MS_of_speech_is_discarded_by_the_flat_close_too() {
        // STRICT, as `:584` is: exactly MIN_SPEECH_MS is discarded. Driven OFF the frame grid so
        // `<=` versus `<` is visible: the gate opens at BASE and the flat run starts at BASE + 300.
        val probe = FlatProbe(P_SPEECH)
        val ep = SileroEndpointer(probe = probe)
        ep.onSessionStart(nowMs = BASE, minCommitIntervalMs = 0L)
        ep.armFlatline(true)
        assertFalse(ep.onFrame(ByteArray(B), SPEECH_RMS, BASE))
        probe.next = P_GAP_DEADBAND
        for (k in 0 until FLAT_CHUNKS) {
            assertFalse(
                "flat chunk ${k + 1}: exactly MIN_SPEECH_MS of speech is discarded, not committed",
                ep.onFrame(ByteArray(B), GATED_RMS, BASE + EndpointerTuning.MIN_SPEECH_MS + k * EndpointerTuning.FRAME_MS),
            )
        }
        assertNull(ep.lastCut())

        // One frame more of speech (332 ms) COMMITS: the fixture measures the boundary, not the
        // trigger's absence.
        val probe2 = FlatProbe(P_SPEECH)
        val ep2 = SileroEndpointer(probe = probe2)
        ep2.onSessionStart(nowMs = BASE, minCommitIntervalMs = 0L)
        ep2.armFlatline(true)
        assertFalse(ep2.onFrame(ByteArray(B), SPEECH_RMS, BASE))
        probe2.next = P_GAP_DEADBAND
        val runStart = BASE + EndpointerTuning.MIN_SPEECH_MS + EndpointerTuning.FRAME_MS
        var fired = false
        for (k in 0 until FLAT_CHUNKS) {
            fired = ep2.onFrame(ByteArray(B), GATED_RMS, runStart + k * EndpointerTuning.FRAME_MS)
        }
        assertTrue(fired)
        assertEquals(EndpointerTuning.MIN_SPEECH_MS + EndpointerTuning.FRAME_MS, ep2.lastCut()!!.speechMs)
    }

    @Test fun a_flat_discard_keeps_pending_speech_exactly_as_onProbs_discard_does() {
        // The discard path is `closeGate()` and nothing more: `pendingSpeech` describes the
        // uncommitted BUFFER, and a merged utterance's audio really is still sitting there when a
        // short burst after it is thrown away.
        val (ep, _, pump) = fresh(minCommitIntervalMs = 2_000L)
        assertTrue(pump.gatedWord())                            // free
        assertFalse(pump.gatedWord())                           // merged: pendingSpeech = true
        assertTrue(ep.hasPendingSpeech())
        assertFalse(pump.gatedWord(wordFrames = 5))             // 160 ms burst: discarded
        assertEquals(1, pump.commits)
        assertTrue("the discard left the merged buffer's latch standing", ep.hasPendingSpeech())
    }

    // ---------------------------------------------------------------------------------------
    // 6. Silero and the flat hold on ONE frame — Silero wins, one commit.
    // ---------------------------------------------------------------------------------------

    @Test fun the_hangover_and_the_flat_hold_on_the_same_chunk_produce_exactly_one_commit_and_it_is_sileros() {
        check(HANGOVER_FRAMES > FLAT_CHUNKS) { "the fixture needs the hangover to outlast the flat hold" }
        val (ep, _, pump) = fresh()
        pump.run(P_SPEECH, SPEECH_RMS, 20)
        // A dip below RELEASE that is room tone for (12 - 5) frames and then digital zero, so the
        // hangover's twelfth frame is also the flat run's fifth. `onFrame` evaluates the flat
        // trigger only after `onProb` has declined the frame, so Silero takes it (DECISION 2).
        assertFalse(pump.run(P_SILENCE, ROOM_TONE_RMS, HANGOVER_FRAMES - FLAT_CHUNKS))
        assertFalse(pump.run(P_SILENCE, GATED_RMS, FLAT_CHUNKS - 1))
        assertTrue(pump.run(P_SILENCE, GATED_RMS, 1))
        assertEquals(1, pump.commits)
        assertEquals(BASE + (20 + HANGOVER_FRAMES - 1) * EndpointerTuning.FRAME_MS, pump.lastCommitMs)
        assertEquals(
            EndpointCut(speechMs = 640L, trailMs = HANGOVER_TRAIL_MS, prob = P_SILENCE, kind = EndpointCutKind.VAD),
            ep.lastCut(),
        )
        // The commit took the flat run with it (commitAt -> clearForNextSegment -> closeGate):
        // with the gate shut, more zeros count nothing.
        assertFalse(pump.run(P_SILENCE, GATED_RMS, FLAT_CHUNKS))
        assertEquals(1, pump.commits)
    }

    @Test fun a_shorter_hold_lets_the_flat_trigger_win_the_same_dip_earlier() {
        val (ep, _, pump) = fresh()
        pump.run(P_SPEECH, SPEECH_RMS, 20)
        // Digital zero from the dip's first frame: Silero stamps the pending end there too, so the
        // two coincide — the measured case on real gated audio (`jfk-gated.wav`, max p on a zero
        // frame 0.075). The flat close fires on dip frame 5, seven frames before the hangover.
        assertFalse(pump.run(P_SILENCE, GATED_RMS, FLAT_CHUNKS - 1))
        assertTrue(pump.run(P_SILENCE, GATED_RMS, 1))
        assertEquals(BASE + (20 + FLAT_CHUNKS - 1) * EndpointerTuning.FRAME_MS, pump.lastCommitMs)
        assertEquals(
            EndpointCut(speechMs = 640L, trailMs = FLAT_TRAIL_MS, prob = P_SILENCE, kind = EndpointCutKind.FLAT),
            ep.lastCut(),
        )
    }

    @Test fun a_flat_run_that_ends_early_does_not_disturb_a_pending_end_silero_stamped() {
        // DECISION 7. Dip frames 1-2 room tone (Silero stamps the pending end at frame 1), 3-5
        // digital zero (three flat chunks — two short), then room tone again: the hangover still
        // fires on its own twelfth frame with its own trail, untouched.
        val (ep, _, pump) = fresh()
        pump.run(P_SPEECH, SPEECH_RMS, 20)
        assertFalse(pump.run(P_SILENCE, ROOM_TONE_RMS, 2))
        assertFalse(pump.run(P_SILENCE, GATED_RMS, 3))
        assertTrue(pump.run(P_SILENCE, ROOM_TONE_RMS, HANGOVER_FRAMES - 5))
        assertEquals(1, pump.commits)
        assertEquals(BASE + (20 + HANGOVER_FRAMES - 1) * EndpointerTuning.FRAME_MS, pump.lastCommitMs)
        assertEquals(
            EndpointCut(speechMs = 640L, trailMs = HANGOVER_TRAIL_MS, prob = P_SILENCE, kind = EndpointCutKind.VAD),
            ep.lastCut(),
        )
    }

    // ---------------------------------------------------------------------------------------
    // 7. The pending end when Silero and the flat run disagree (test_flatline_verify §1).
    // ---------------------------------------------------------------------------------------

    @Test fun a_later_silero_stamp_is_kept_so_speech_includes_the_flat_frames_before_it() {
        // The flat run starts at frame 20 while Silero is still in the DEAD BAND (LSTM inertia);
        // Silero drops below RELEASE two frames later and stamps at 22. The hold comes due at 24
        // with `tempEndMs` already set, so the flat close keeps Silero's LATER stamp: speech runs
        // to 22 (it includes two digitally silent frames) and the trail is 64 — SHORTER than the
        // hold. `tempEndMs` is stamped once and never moved (DECISION 6).
        val (ep, _, pump) = fresh()
        pump.run(P_SPEECH, SPEECH_RMS, 20)
        assertFalse(pump.run(P_GAP_DEADBAND, GATED_RMS, 2))
        assertFalse(pump.run(P_SILENCE, GATED_RMS, 2))
        assertTrue("fires on the fifth flat frame regardless of Silero", pump.run(P_SILENCE, GATED_RMS, 1))
        assertEquals(BASE + 24 * EndpointerTuning.FRAME_MS, pump.lastCommitMs)
        assertEquals(
            EndpointCut(
                speechMs = 22 * EndpointerTuning.FRAME_MS,
                trailMs = 2 * EndpointerTuning.FRAME_MS,
                prob = P_SILENCE,
                kind = EndpointCutKind.FLAT,
            ),
            ep.lastCut(),
        )
    }

    @Test fun an_earlier_silero_stamp_is_kept_so_trail_is_longer_than_the_hold() {
        // Room tone for three frames (Silero stamps at 20), THEN digital zero. The flat run starts
        // at 23 and fires at 27; the pending end stays at 20, so the trail is 224 — the hangover
        // would have taken this same dip four frames later, at 31, with trail 352.
        val (ep, _, pump) = fresh()
        pump.run(P_SPEECH, SPEECH_RMS, 20)
        assertFalse(pump.run(P_SILENCE, ROOM_TONE_RMS, 3))
        assertFalse(pump.run(P_SILENCE, GATED_RMS, FLAT_CHUNKS - 1))
        assertTrue(pump.run(P_SILENCE, GATED_RMS, 1))
        assertEquals(BASE + 27 * EndpointerTuning.FRAME_MS, pump.lastCommitMs)
        assertEquals(
            EndpointCut(
                speechMs = 20 * EndpointerTuning.FRAME_MS,
                trailMs = 7 * EndpointerTuning.FRAME_MS,
                prob = P_SILENCE,
                kind = EndpointCutKind.FLAT,
            ),
            ep.lastCut(),
        )
    }

    @Test fun silero_onset_frames_inside_the_run_do_not_reset_it_and_the_end_is_the_run_start() {
        // DECISION 4 the way it can actually happen: Silero calls the first two digitally silent
        // frames SPEECH (`p >= ONSET`, zeroing `tempEndMs` at :540) and then parks in the dead
        // band, so it never stamps a pending end at all. The run survives those two frames and,
        // with `tempEndMs` 0 at fire time, the pending end is the run's first frame.
        val (ep, _, pump) = fresh()
        pump.run(P_SPEECH, SPEECH_RMS, 20)
        assertFalse(pump.run(P_SPEECH, GATED_RMS, 2))
        assertFalse(pump.run(P_GAP_DEADBAND, GATED_RMS, 2))
        assertTrue(pump.run(P_GAP_DEADBAND, GATED_RMS, 1))
        assertEquals(BASE + 24 * EndpointerTuning.FRAME_MS, pump.lastCommitMs)
        assertEquals(
            EndpointCut(speechMs = 640L, trailMs = FLAT_TRAIL_MS, prob = P_GAP_DEADBAND, kind = EndpointCutKind.FLAT),
            ep.lastCut(),
        )
    }

    @Test fun a_no_verdict_frame_with_a_flat_amp_still_counts_because_the_trigger_is_purely_amplitude_driven() {
        // DECISION 4: `p` cannot veto the count, and that includes "no verdict". The record then
        // carries -1.0 honestly — that frame HAD no verdict.
        val (ep, _, pump) = fresh()
        pump.run(P_SPEECH, SPEECH_RMS, 12)
        assertFalse(pump.run(EndpointerTuning.NO_VERDICT, GATED_RMS, FLAT_CHUNKS - 1))
        assertTrue(pump.run(EndpointerTuning.NO_VERDICT, GATED_RMS, 1))
        assertEquals(
            EndpointCut(12 * EndpointerTuning.FRAME_MS, FLAT_TRAIL_MS, EndpointerTuning.NO_VERDICT, EndpointCutKind.FLAT),
            ep.lastCut(),
        )
    }

    // ---------------------------------------------------------------------------------------
    // 8. The run is GATE state: not counted while shut, cleared by every clear.
    // ---------------------------------------------------------------------------------------

    @Test fun the_flat_run_is_not_counted_while_the_gate_is_shut() {
        // DECISION 3. Leading digital silence must not arm the trigger: the run only starts once
        // an onset has opened the gate, so `speechMs` can never come out negative. The onset that
        // opens the gate here is itself FLAT (Silero calling a zero frame speech) so that a run
        // counted through the lead-in would fire on it — with `speechMs` negative — and discard
        // the first word's opening frame by bookkeeping. Cleared, the word is measured whole.
        val (ep, _, pump) = fresh()
        assertFalse(pump.run(P_SILENCE, GATED_RMS, 40))         // 1.28 s of digital silence
        assertFalse(pump.run(P_SPEECH, GATED_RMS, 1))           // the word's first frame, flat
        assertFalse(pump.run(P_SPEECH, SPEECH_RMS, 11))
        assertFalse("four chunks is one short of the hold", pump.run(P_GAP_DEADBAND, GATED_RMS, FLAT_CHUNKS - 1))
        assertTrue("the fifth fires, measured from the gap", pump.run(P_GAP_DEADBAND, GATED_RMS, 1))
        assertEquals(12 * EndpointerTuning.FRAME_MS, ep.lastCut()!!.speechMs)
    }

    /**
     * The clears are observed the only way they can be without an accessor: through the PHASE of
     * the next cut. After the clear under test, the very next frame is an onset with a FLAT
     * amplitude. A run that survived the clear would reach five ON that frame and fire a
     * negative-`speechMs` discard, shutting the gate; the following frame would re-open it one
     * frame later, and the word's eventual flat cut would read 320 ms of speech instead of 352.
     */
    private fun FlatPump.speechAfterAFlatOnsetThenTenSpeechFramesThenAGap(): Long {
        assertFalse(run(P_SPEECH, GATED_RMS, 1))
        assertFalse(run(P_SPEECH, SPEECH_RMS, 10))
        assertFalse(run(P_GAP_DEADBAND, GATED_RMS, FLAT_CHUNKS - 1))
        assertTrue(run(P_GAP_DEADBAND, GATED_RMS, 1))
        return ep.lastCut()!!.speechMs
    }

    @Test fun reset_clears_the_flat_run_so_it_cannot_carry_into_the_next_segment() {
        val (ep, _, pump) = fresh()
        pump.run(P_SPEECH, SPEECH_RMS, 12)
        assertFalse(pump.run(P_GAP_DEADBAND, GATED_RMS, FLAT_CHUNKS - 1))   // four counted
        ep.reset()                                                          // the cap cut / switchSource
        assertEquals(11 * EndpointerTuning.FRAME_MS, pump.speechAfterAFlatOnsetThenTenSpeechFramesThenAGap())
    }

    @Test fun a_commit_clears_the_flat_run_so_the_next_gap_starts_it_afresh() {
        // commitAt -> clearForNextSegment -> closeGate takes the flat run with it (DECISION 3/7).
        val (_, _, pump) = fresh()
        assertTrue(pump.gatedWord())
        assertEquals(11 * EndpointerTuning.FRAME_MS, pump.speechAfterAFlatOnsetThenTenSpeechFramesThenAGap())
    }

    @Test fun onSessionStart_clears_the_flat_run_with_everything_else() {
        val (ep, _, pump) = fresh()
        pump.run(P_SPEECH, SPEECH_RMS, 12)
        assertFalse(pump.run(P_GAP_DEADBAND, GATED_RMS, FLAT_CHUNKS - 1))
        ep.onSessionStart(nowMs = pump.t, minCommitIntervalMs = 0L)
        ep.armFlatline(true)                                                // the source pick
        assertEquals(11 * EndpointerTuning.FRAME_MS, pump.speechAfterAFlatOnsetThenTenSpeechFramesThenAGap())
    }

    // ---------------------------------------------------------------------------------------
    // 9. ARMING: armed IFF the active source is captured playback.
    // ---------------------------------------------------------------------------------------

    @Test fun a_fresh_endpointer_is_not_armed_so_a_mic_session_is_identical_to_today_on_the_same_trace() {
        // No armFlatline call at all — the amplitude fallback's shape, and every existing test's.
        val probe = FlatProbe()
        val ep = SileroEndpointer(probe = probe)
        ep.onSessionStart(nowMs = BASE, minCommitIntervalMs = 0L)
        val pump = FlatPump(ep, probe)
        repeat(4) { assertFalse(pump.gatedWord()) }
        assertEquals(0, pump.commits)
        assertNull(ep.lastCut())
        // And the canonical utterance with digital zero under its dip is still Silero's cut on
        // the hangover's frame — `a_vad_cut_records_what_it_cut`, with an amplitude beside it.
        val probe2 = FlatProbe()
        val ep2 = SileroEndpointer(probe = probe2)
        ep2.onSessionStart(nowMs = BASE, minCommitIntervalMs = 0L)
        val pump2 = FlatPump(ep2, probe2)
        pump2.run(P_SPEECH, SPEECH_RMS, 20)
        assertFalse(pump2.run(P_SILENCE, GATED_RMS, HANGOVER_FRAMES - 1))
        assertTrue(pump2.run(P_SILENCE, GATED_RMS, 1))
        assertEquals(EndpointCut(speechMs = 640L, trailMs = HANGOVER_TRAIL_MS, prob = P_SILENCE), ep2.lastCut())
        assertEquals(EndpointCutKind.VAD, ep2.lastCut()!!.kind)
    }

    @Test fun a_switch_to_playback_arms_mid_session_and_the_drm_handover_back_to_the_mic_disarms() {
        val (ep, _, pump) = fresh(armed = false)
        assertFalse("mic: the gated word rides the cap", pump.gatedWord())
        // switchSource(to = PLAYBACK): the service commits, resets, then sets the source.
        ep.reset()
        ep.armFlatline(true)
        assertTrue("device audio: the gated word is cut", pump.gatedWord())
        assertEquals(EndpointCutKind.FLAT, ep.lastCut()!!.kind)
        // The one handover the latch allows (a DRM-blocked stream -> microphone) disarms.
        ep.reset()
        ep.armFlatline(false)
        assertFalse(pump.gatedWord())
        assertEquals(1, pump.commits)
    }

    @Test fun reset_neither_disarms_nor_arms() {
        val (armed, _, armedPump) = fresh(armed = true)
        armed.reset()
        assertTrue("an external commit leaves the trigger armed", armedPump.gatedWord())

        val (mic, _, micPump) = fresh(armed = false)
        mic.reset()
        assertFalse("and leaves a mic session disarmed", micPump.gatedWord())
    }

    @Test fun onSessionStart_opens_a_session_disarmed_and_the_source_pick_re_arms_it() {
        // Every session opens on the microphone by construction (stopRecording puts the raw field
        // back to MIC; connect() runs before startAudioInput() picks a capturer), and the pick
        // that follows arms the trigger if the source is captured playback.
        val (ep, _, pump) = fresh(armed = true)
        ep.onSessionStart(nowMs = pump.t, minCommitIntervalMs = 0L)
        assertFalse("a new session is disarmed until its source is picked", pump.gatedWord())
        ep.armFlatline(true)
        assertTrue(pump.gatedWord())
    }

    // ---------------------------------------------------------------------------------------
    // 10. The chunk/frame mapping: a frame inherits the RMS of the chunk that COMPLETED it.
    // ---------------------------------------------------------------------------------------

    @Test fun a_short_read_that_completes_no_frame_contributes_nothing_and_the_count_is_per_completed_frame() {
        // The simulator's `frame_rms`: one RMS per capture chunk, applied to every frame that
        // chunk completes. Half-frame chunks complete a frame every second call, so ten flat
        // half-chunks are five flat frames — and the fifth completed frame fires.
        val (ep, _, pump) = fresh()
        pump.run(P_SPEECH, SPEECH_RMS, 12)
        assertFalse(pump.run(P_GAP_DEADBAND, GATED_RMS, 2 * (FLAT_CHUNKS - 1), bytes = B / 2))
        assertFalse("the ninth half-chunk completes no frame", pump.run(P_GAP_DEADBAND, GATED_RMS, 1, bytes = B / 2))
        assertTrue("the tenth completes the fifth flat frame", pump.run(P_GAP_DEADBAND, GATED_RMS, 1, bytes = B / 2))
        assertEquals(EndpointCutKind.FLAT, ep.lastCut()!!.kind)
    }
}
