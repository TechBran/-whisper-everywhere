package com.whispereverywhere.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

private const val B = EndpointerTuning.FRAME_BYTES
private const val FRAME_MS = EndpointerTuning.FRAME_MS

/** Non-zero: 0L is the endpointer's "no micro-pause remembered" sentinel. */
private const val BASE = 1_000_000L

private val HANGOVER_FRAMES = EndpointerGrid.HANGOVER_FRAMES
private val MICRO_PAUSE_FRAMES = EndpointerGrid.MICRO_PAUSE_FRAMES
private val UNKNOWN = Endpointer.UNKNOWN_SPEECH_EVIDENCE_MS
private val FLOOR = EndpointerTuning.MIN_SPEECH_EVIDENCE_MS

private const val P_SPEECH = 0.9f
private const val P_DEAD_BAND = 0.40f
private const val P_SILENCE = 0.1f
private const val SPEECH_RMS = 3_000
private const val GATED_RMS = 0

private class EvidenceProbe(var next: Float = 0f) : (ByteArray) -> Float {
    override fun invoke(frame: ByteArray): Float = next
}

/** `SileroEndpointerTest.Pump`, with the chunk RMS beside the probability for the flat fixture. */
private class EvidencePump(val ep: SileroEndpointer, val probe: EvidenceProbe, var t: Long = BASE) {
    val commitFrames = mutableListOf<Long>()

    fun run(p: Float, frames: Int, amp: Int = SPEECH_RMS): Boolean {
        probe.next = p
        var fired = false
        repeat(frames) {
            if (ep.onFrame(ByteArray(B), amp, t)) {
                fired = true
                commitFrames += t
            }
            t += FRAME_MS
        }
        return fired
    }
}

/**
 * THE SPEECH EVIDENCE (4.3.2): the endpointer counts, per uncommitted buffer, the frames the probe
 * scored at or above ONSET, and the commit funnel reads that count once — through
 * [Endpointer.speechEvidenceMs] — before handing the buffer to the engine, which skips the ENCODE
 * of a buffer under [EndpointerTuning.MIN_SPEECH_EVIDENCE_MS].
 *
 * Two properties carry this file. The count is EVIDENCE ONLY — it never changes a cut, which the
 * last section shows on the grid fixtures and pins in the source — and it is BUFFER knowledge: a
 * discard or a merge leaves it standing (that audio is still in the buffer), only the funnel's
 * [Endpointer.onBufferCommitted] and a session start re-base it, and a retaining cap cut hands the
 * next buffer exactly the tail's onset frames. The VAD-cut ORDER is the reason the funnel does the
 * re-basing rather than `clearForNextSegment`: `onFrame` clears the gate before it returns true,
 * and the funnel reads the count after that — cleared with the gate, every real utterance would
 * report zero and be skipped.
 *
 * Reference twin: `tools/vadsim/tests/test_evidence.py`, test for test by name.
 */
class SileroEndpointerEvidenceTest {

    private fun fresh(minCommitIntervalMs: Long = 0L): Pair<SileroEndpointer, EvidencePump> {
        val probe = EvidenceProbe()
        val ep = SileroEndpointer(probe = probe)
        ep.onSessionStart(nowMs = BASE, minCommitIntervalMs = minCommitIntervalMs)
        return ep to EvidencePump(ep, probe)
    }

    // ---------------------------------------------------------------------------------------
    // 1. What is counted.
    // ---------------------------------------------------------------------------------------

    @Test fun evidence_counts_only_frames_at_or_above_ONSET_and_the_dead_band_and_silence_add_nothing() {
        val (ep, pump) = fresh()
        pump.run(P_SPEECH, 10)
        assertEquals(10 * FRAME_MS, ep.speechEvidenceMs())
        // The dead band is inert for the cut AND for the count.
        pump.run(P_DEAD_BAND, 5)
        assertEquals(10 * FRAME_MS, ep.speechEvidenceMs())
        // Five silent frames: under the hangover, so no cut, and no evidence either.
        pump.run(P_SILENCE, 5)
        assertEquals(10 * FRAME_MS, ep.speechEvidenceMs())
        // Exactly ONSET is an onset frame — the same inclusive comparison the gate uses.
        pump.run(EndpointerTuning.ONSET_THRESHOLD, 1)
        assertEquals(11 * FRAME_MS, ep.speechEvidenceMs())
        assertTrue("nothing committed in this fixture", pump.commitFrames.isEmpty())
    }

    @Test fun a_bare_endpointer_is_UNKNOWN_until_its_probe_scores_a_frame_and_a_scored_silence_is_KNOWN_zero() {
        val (ep, pump) = fresh()
        assertEquals("no frame scored yet: the engine must transcribe", UNKNOWN, ep.speechEvidenceMs())
        // A probe that never answers — a failed init, a stale capture thread refused by the epoch
        // gate — leaves the whole buffer UNKNOWN, so the session is never skipped on a count
        // nobody took. This is the case an amplitude fallback answers by default, reached from
        // inside a Silero endpointer.
        pump.run(EndpointerTuning.NO_VERDICT, 50)
        assertEquals(UNKNOWN, ep.speechEvidenceMs())
        // One real verdict — silence — and the buffer is KNOWN to hold no speech: 0, not UNKNOWN.
        pump.run(P_SILENCE, 1)
        assertEquals(0L, ep.speechEvidenceMs())
        assertTrue(ep.speechEvidenceMs() < FLOOR)
    }

    @Test fun a_probe_that_never_answered_makes_the_amplitude_fallbacks_answer_from_inside_a_silero_endpointer() {
        // The interface default IS the amplitude fallback's answer; the two must agree on the
        // sentinel or the engine would read one of them as a count.
        assertEquals(UNKNOWN, AmplitudeEndpointer().speechEvidenceMs())
        assertTrue("UNKNOWN is negative so that 0 stays a legal count", UNKNOWN < 0L)
        val (ep, _) = fresh()
        assertEquals(AmplitudeEndpointer().speechEvidenceMs(), ep.speechEvidenceMs())
    }

    // ---------------------------------------------------------------------------------------
    // 2. Buffer knowledge: survives the gate closing, dies only with the buffer.
    // ---------------------------------------------------------------------------------------

    @Test fun the_count_survives_a_MIN_SPEECH_discard_because_that_audio_is_still_in_the_buffer() {
        val (ep, pump) = fresh()
        // Nine onset frames span 288 ms: under the 300 ms span floor, so the hangover DISCARDS it
        // (closeGate) rather than committing. The audio waits for the wall cap — and so does its
        // evidence, which is why the cap's commit of a burst like this is still ENCODED.
        pump.run(P_SPEECH, 9)
        assertFalse(pump.run(P_SILENCE, HANGOVER_FRAMES))
        assertFalse("a discarded burst never latches pendingSpeech", ep.hasPendingSpeech())
        assertEquals(9 * FRAME_MS, ep.speechEvidenceMs())
        assertTrue("288 >= 256: a lone quiet word's cap commit is encoded", ep.speechEvidenceMs() >= FLOOR)
    }

    @Test fun the_count_survives_a_governor_merge() {
        val (ep, pump) = fresh(minCommitIntervalMs = 8_000L)
        // The session's free first cut.
        pump.run(P_SPEECH, 20)
        assertTrue(pump.run(P_SILENCE, HANGOVER_FRAMES))
        ep.onBufferCommitted(tailRetained = false)
        // A second utterance inside the 8 s floor: a real endpoint, MERGED (closeGate, buffer
        // kept). Its evidence is kept with it.
        pump.run(P_SPEECH, 20)
        assertFalse(pump.run(P_SILENCE, HANGOVER_FRAMES))
        assertEquals(1, pump.commitFrames.size)
        assertEquals(20 * FRAME_MS, ep.speechEvidenceMs())
    }

    @Test fun a_VAD_cut_leaves_the_count_readable_until_the_funnel_re_bases_it() {
        // THE ORDER this whole design turns on. onFrame runs commitAt -> clearForNextSegment and
        // THEN returns true; the funnel reads the count after that. So the count must NOT die in
        // clearForNextSegment — or every real utterance would report zero and be skipped.
        val (ep, pump) = fresh()
        pump.run(P_SPEECH, 20)
        assertTrue(pump.run(P_SILENCE, HANGOVER_FRAMES))
        assertEquals("readable after the cut: the funnel reads it now", 20 * FRAME_MS, ep.speechEvidenceMs())
        ep.onBufferCommitted(tailRetained = false)
        assertEquals("the funnel re-based it: the next buffer has no scored frame yet", UNKNOWN, ep.speechEvidenceMs())
        pump.run(P_SPEECH, 1)
        assertEquals(FRAME_MS, ep.speechEvidenceMs())
    }

    @Test fun a_flat_cut_leaves_the_count_readable_too() {
        val (ep, pump) = fresh()
        ep.armFlatline(true)
        pump.run(P_SPEECH, 12)
        // An editor's gate: dead-band p on digital silence, five chunks — the flat close.
        assertTrue(pump.run(P_DEAD_BAND, EndpointerTuning.FLATLINE_CHUNKS, amp = GATED_RMS))
        assertEquals(EndpointCutKind.FLAT, ep.lastCut()?.kind)
        assertEquals(12 * FRAME_MS, ep.speechEvidenceMs())
    }

    @Test fun without_a_retained_tail_the_re_base_is_UNKNOWN_not_zero() {
        // A stop flush that lands before the next verdict must be transcribed, not skipped as
        // "no evidence": the buffer it commits was never scored.
        val (ep, pump) = fresh()
        pump.run(P_SPEECH, 20)
        assertTrue(pump.run(P_SILENCE, HANGOVER_FRAMES))
        ep.onBufferCommitted(tailRetained = false)
        assertEquals(UNKNOWN, ep.speechEvidenceMs())
    }

    @Test fun onSessionStart_opens_the_count_UNKNOWN() {
        val (ep, pump) = fresh()
        pump.run(P_SPEECH, 10)
        assertEquals(10 * FRAME_MS, ep.speechEvidenceMs())
        ep.onSessionStart(nowMs = pump.t, minCommitIntervalMs = 0L)
        assertEquals(UNKNOWN, ep.speechEvidenceMs())
    }

    @Test fun reset_leaves_the_count_standing_because_the_funnel_already_re_based_it() {
        // Every service-side reset follows a funnel commit, and on the cap site that commit's
        // re-base CARRIED the retained tail; a clear in reset() would erase exactly that carry.
        val (ep, pump) = fresh()
        pump.run(P_SPEECH, 10)
        ep.reset()
        assertEquals(10 * FRAME_MS, ep.speechEvidenceMs())
    }

    // ---------------------------------------------------------------------------------------
    // 3. The floor, on the two fixtures the constant was chosen for.
    // ---------------------------------------------------------------------------------------

    @Test fun the_shortest_burst_that_commits_ten_onset_frames_is_also_encoded() {
        // MIN_SPEECH_EVIDENCE_MS <= MIN_SPEECH_MS, made concrete: the shortest burst the span floor
        // lets through — ten onset frames, speechMs = 320 > 300 — carries 320 ms of evidence, over
        // the 256 ms floor. A VAD cut that passed the span floor is not skippable on evidence in
        // the normal case. (Nine frames span 288 ms and are DISCARDED, not committed — see the
        // discard test above, where the cap later commits them, encoded.)
        val (ep, pump) = fresh()
        pump.run(P_SPEECH, 10)
        assertTrue(pump.run(P_SILENCE, HANGOVER_FRAMES))
        assertEquals(10 * FRAME_MS, ep.speechEvidenceMs())
        assertTrue(ep.speechEvidenceMs() >= FLOOR)
    }

    @Test fun a_fifteen_second_bed_where_silero_flickered_for_six_frames_reads_192_under_the_floor() {
        // The owner's report: silence with a little background nudges the probe over ONSET for a
        // frame here and there. Each flicker opens the gate, the dip after it discards the burst,
        // and nothing commits — until the 15 s cap. Six such frames are 192 ms of evidence, and
        // the cap's commit is SKIPPED at the engine instead of paying a full encode for room tone.
        val (ep, pump) = fresh()
        repeat(6) {
            pump.run(P_SILENCE, 80)          // 2.56 s of room tone
            pump.run(P_SPEECH, 1)            // one flicker
        }
        pump.run(P_SILENCE, 12)
        assertTrue("no cut on this bed: every flicker was discarded", pump.commitFrames.isEmpty())
        assertEquals(6 * FRAME_MS, ep.speechEvidenceMs())
        assertTrue(ep.speechEvidenceMs() < FLOOR)
        assertTrue("the bed ran past a 15 s wall", pump.t - BASE > 15_000L)
    }

    // ---------------------------------------------------------------------------------------
    // 4. The retained tail.
    // ---------------------------------------------------------------------------------------

    @Test fun a_retaining_cap_cut_carries_exactly_the_tails_onset_frames_into_the_next_count() {
        // The tail a cap cut keeps is [offered cut point, now]. Twenty onset frames, a dip long
        // enough to be offered (the fifth dip frame promotes), then eight more onset frames: the
        // committed segment is credited with all 28 (over-count is safe), and the next buffer opens
        // with exactly the eight that came after the offer.
        val (ep, pump) = fresh()
        pump.run(P_SPEECH, 20)
        pump.run(P_SILENCE, MICRO_PAUSE_FRAMES)
        val offer = ep.pendingCutPointMs()
        assertTrue("the dip was offered", offer > Endpointer.NO_CUT_POINT)
        pump.run(P_SPEECH, 8)
        assertEquals("the offer survives the gate re-opening", offer, ep.pendingCutPointMs())
        assertEquals(28 * FRAME_MS, ep.speechEvidenceMs())

        ep.onBufferCommitted(tailRetained = true)
        assertEquals(8 * FRAME_MS, ep.speechEvidenceMs())
        assertTrue("the tail alone clears the floor: the speaker's last words are safe", ep.speechEvidenceMs() >= FLOOR)
        // The cap site calls reset() next; the carry must survive it.
        ep.reset()
        assertEquals(8 * FRAME_MS, ep.speechEvidenceMs())
        pump.run(P_SPEECH, 1)
        assertEquals(9 * FRAME_MS, ep.speechEvidenceMs())
    }

    @Test fun a_flicker_before_the_offered_dip_carries_nothing() {
        // The owner's scenario with a retain: the flicker precedes the dip that was offered, so
        // the tail holds no onset frame and the next silent window opens at zero — skippable.
        val (ep, pump) = fresh()
        pump.run(P_SILENCE, 30)
        pump.run(P_SPEECH, 1)
        pump.run(P_SILENCE, MICRO_PAUSE_FRAMES)
        assertTrue(ep.pendingCutPointMs() > Endpointer.NO_CUT_POINT)
        assertEquals(FRAME_MS, ep.speechEvidenceMs())
        ep.onBufferCommitted(tailRetained = true)
        assertEquals(0L, ep.speechEvidenceMs())
        assertTrue(ep.speechEvidenceMs() < FLOOR)
    }

    @Test fun an_offer_that_outlived_a_re_base_carries_the_whole_next_buffer_not_a_stale_difference() {
        // The consent flush commits through the funnel WITHOUT a reset(), so prevEndMs can
        // outlive a re-base. Every frame of the next buffer then follows that offer, and a later
        // retain against it must carry them all: the offer's count re-bases with the buffer.
        val (ep, pump) = fresh()
        pump.run(P_SPEECH, 20)
        pump.run(P_SILENCE, MICRO_PAUSE_FRAMES)
        ep.onBufferCommitted(tailRetained = false)      // the flush; no reset
        assertTrue("the offer survived", ep.pendingCutPointMs() > Endpointer.NO_CUT_POINT)
        pump.run(P_SPEECH, 4)
        ep.onBufferCommitted(tailRetained = true)
        assertEquals("4, not max(0, 4 - 20)", 4 * FRAME_MS, ep.speechEvidenceMs())
    }

    @Test fun a_retain_on_an_unscored_buffer_stays_UNKNOWN() {
        val (ep, _) = fresh()
        ep.onBufferCommitted(tailRetained = true)
        assertEquals(UNKNOWN, ep.speechEvidenceMs())
    }

    // ---------------------------------------------------------------------------------------
    // 5. The slow-probe cutout.
    // ---------------------------------------------------------------------------------------

    @Test fun a_latched_off_probe_makes_the_count_UNKNOWN_even_with_onset_frames_behind_it() {
        // After the latch the buffer keeps filling with frames nobody scores, so a count taken
        // before it describes part of the audio: UNKNOWN, and the engine transcribes.
        var nanos = 0L
        val probe = EvidenceProbe(P_SPEECH)
        val ep = SileroEndpointer(
            probe = { f -> nanos += (EndpointerTuning.PROBE_BUDGET_US + 1) * 1_000L; probe(f) },
            nanoClock = { nanos },
        )
        ep.onSessionStart(nowMs = BASE, minCommitIntervalMs = 0L)
        val pump = EvidencePump(ep, probe)
        pump.run(P_SPEECH, EndpointerTuning.PROBE_CUTOUT_FRAMES - 1)
        assertFalse(ep.isProbeCutout())
        assertEquals((EndpointerTuning.PROBE_CUTOUT_FRAMES - 1) * FRAME_MS, ep.speechEvidenceMs())
        pump.run(P_SPEECH, 1)
        assertTrue(ep.isProbeCutout())
        assertEquals(UNKNOWN, ep.speechEvidenceMs())
    }

    // ---------------------------------------------------------------------------------------
    // 6. EVIDENCE ONLY: no cut decision reads it.
    // ---------------------------------------------------------------------------------------

    @Test fun the_count_changes_no_cut_on_the_grid_fixtures_whether_or_not_the_funnel_re_bases_it() {
        // Two identical endpointers through one mixed trace — utterances, a discard, a merge, a
        // dead-band mumble, a retaining cap's re-base — one with the funnel's re-base after every
        // commit, one never re-based. The commits must land on the same frames: the count is
        // read at the funnel and nowhere inside the machine.
        fun trace(rebase: Boolean): List<Long> {
            val (ep, pump) = fresh(minCommitIntervalMs = 2_000L)
            fun cut(): Boolean {
                val fired = pump.run(P_SILENCE, HANGOVER_FRAMES)
                if (fired && rebase) ep.onBufferCommitted(tailRetained = false)
                return fired
            }
            pump.run(P_SPEECH, 20); cut()                         // free first cut
            pump.run(P_SPEECH, 9); cut()                          // discard
            pump.run(P_SPEECH, 20); cut()                         // merge (inside 2 s)
            pump.run(P_DEAD_BAND, 30)                             // inert
            pump.run(P_SPEECH, 40); cut()                         // a real cut
            pump.run(P_SPEECH, 20); pump.run(P_SILENCE, MICRO_PAUSE_FRAMES)
            if (rebase) ep.onBufferCommitted(tailRetained = true) // a retaining cap's re-base
            ep.reset()
            pump.run(P_SPEECH, 60); cut()                         // past the 2 s floor from reset
            return pump.commitFrames.toList()
        }
        val with = trace(rebase = true)
        val without = trace(rebase = false)
        assertEquals(with, without)
        assertEquals("the fixture really cut", 3, with.size)
    }

    /**
     * The structural half of EVIDENCE ONLY, pinned in the source: every code line of
     * `SileroEndpointer.kt` that touches `evidenceFrames` is one of the known shapes — the
     * declaration, the KNOWN transition, the increment, the offer snapshot, the two accessors'
     * bodies and the session-start re-base. A new read anywhere — an `if` in a cut branch, a
     * term in the MIN_SPEECH test, a merge memory rebuilt on this count — fails here with the
     * offending line, which is the 4.4 review's rejection made mechanical.
     */
    @Test fun no_branch_of_the_state_machine_reads_the_evidence() {
        val allowed = setOf(
            "@Volatile private var evidenceFrames = NO_EVIDENCE_YET",
            "if (evidenceFrames < 0) evidenceFrames = 0",
            "evidenceFrames++",
            "evidenceFramesAtOffer = evidenceFrames",
            "if (probeCutout || evidenceFrames < 0) Endpointer.UNKNOWN_SPEECH_EVIDENCE_MS",
            "else evidenceFrames * EndpointerTuning.FRAME_MS",
            "val frames = evidenceFrames",
            "evidenceFrames =",
            "evidenceFrames = NO_EVIDENCE_YET",
        )
        // The offer's own count is a different field with this one's name as a prefix; strip it
        // before looking, so `frames - evidenceFramesAtOffer` is not mistaken for a read of it.
        val offenders = code()
            .filter { it.replace("evidenceFramesAtOffer", "").contains("evidenceFrames") }
            .filterNot { it in allowed }
        assertEquals(
            "SileroEndpointer.kt reads or writes `evidenceFrames` on a line this test does not " +
                "know: $offenders. The count is EVIDENCE ONLY — it gates the encode at the commit " +
                "funnel and may not feed a cut. If this is a new legitimate site, enrol its exact " +
                "line here and say in the field's KDoc why it is not a cut decision.",
            emptyList<String>(),
            offenders,
        )
        assertTrue("the increment is still there", code().any { it == "evidenceFrames++" })
    }

    // ---------------------------------------------------------------------------------------
    // Source-reading helpers, the shape SileroEndpointerTest uses.
    // ---------------------------------------------------------------------------------------

    private fun repoFile(relative: String): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            File(dir, relative).let { if (it.isFile) return it }
            File(dir, "app/$relative").let { if (it.isFile) return it }
            dir = dir.parentFile
        }
        throw AssertionError("could not locate $relative from ${System.getProperty("user.dir")}")
    }

    private val src: String by lazy {
        repoFile("src/main/java/com/whispereverywhere/audio/SileroEndpointer.kt")
            .readText().replace("\r\n", "\n")
    }

    private fun code(): List<String> =
        src.lines()
            .map { it.trim() }
            .filterNot {
                it.startsWith("*") || it.startsWith("/**") || it.startsWith("//") ||
                    it.startsWith("*/")
            }
            .map { it.substringBefore("//").trim() }
            .filter { it.isNotEmpty() }
}
