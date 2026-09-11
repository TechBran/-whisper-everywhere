package com.whispereverywhere.audio

import com.whispereverywhere.service.SegmentCapPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val B = EndpointerTuning.FRAME_BYTES

/** Non-zero: 0L is the endpointer's "no micro-pause remembered" sentinel. */
private const val TAP = 1_000_000L

private const val P_SPEECH = 0.9f
private const val P_SILENCE = 0.1f
private const val SPEECH_RMS = 3_000
private const val ROOM_TONE_RMS = 100
private const val GATED_RMS = 0

/**
 * THE REPLAYED CLOCK — the amendment's condition 3, executed against the real state machine
 * rather than argued in a comment.
 *
 * S2's ring hands the engine up to 6 s of audio that was captured before the engine existed.
 * Each replayed chunk can carry either of two stamps, and the amendment requires the choice to be
 * stated and tested:
 *
 *  - **ORIGINAL (shipped):** the chunk's own capture `nowMs`. The audio's true clock.
 *  - **DRAIN-TIME (rejected):** `System.currentTimeMillis()` at the moment of replay. Because the
 *    drain is paced at [StartupRing.DRAIN_CHUNKS_PER_TICK] replayed chunks per live chunk, that
 *    clock runs the buffered audio at 1/4 speed in stamp terms: 4 s of dips arrive stamped as
 *    ~1 s of dips.
 *
 * The endpointer has **no internal clock** for its decisions — `SileroEndpointer`'s Threading
 * section states the rule ("ONE clock: the caller's `nowMs`, stamped on the chunk the frames came
 * from") and the injected `nanoClock` is the probe BUDGET's alone — so its verdicts are a pure
 * function of the `(chunk, amp, nowMs)` sequence. **That is exactly why the original stamps are
 * not merely "conservative" but IDENTICAL to live delivery**, and it is also why the drain-time
 * clock is not a small error: it is a different input.
 *
 * Each row below runs the SAME `(p, amp)` sequence under both clocks and shows what changes. The
 * terms the amendment names, and where each is checked:
 *
 *  | term | site | under original stamps | under a drain-time clock |
 *  |---|---|---|---|
 *  | hangover (`nowMs - tempEndMs`) | [theHangoverCutsOnTheAudiosOwnClockAndAFourFoldCompressionLosesEveryCut] | every cut lands where live delivery lands | NO cut in the whole 4 s burst |
 *  | cadence floor (`nowMs - lastCommitMs`) | [theCadenceFloorMeasuresTheAudiosRealIntervalNotTheDrainsWallTime] | the true 896 ms interval | a quarter of it, so real cuts merge |
 *  | `SegmentCapPolicy` | [theFirstSegmentCapFiresDuringTheReplayBecauseItsWindowOpensAtTheTap] | fires inside the replay, as if warm | never fires on the buffered audio |
 *  | flatline trigger | [theFlatlineTriggerIsAFrameCountSoBothClocksFireOnTheSameFrame] | frame-count: unaffected | fires on the same frame — but its own MIN_SPEECH discard is wall-clock, and that DOES flip |
 *  | speech-evidence gate | [theSpeechEvidenceGateIsAFrameCountSoBothClocksAgree] | frame-count: unaffected | unaffected |
 *
 * So: **replay with the ORIGINAL stamps.** No test here shows a concrete failure of that choice;
 * three show concrete failures of the alternative.
 */
class StartupReplayClockTest {

    private class Probe(var next: Float = 0f) : (ByteArray) -> Float {
        override fun invoke(frame: ByteArray): Float = next
    }

    /**
     * One frame of audio as the capture thread describes it. A fixture is a list of these, and the
     * two clocks are two different ways of STAMPING the same list — which is the whole point.
     */
    private data class Frame(val p: Float, val amp: Int)

    private fun word(frames: Int, amp: Int = SPEECH_RMS) = List(frames) { Frame(P_SPEECH, amp) }
    private fun dip(frames: Int, amp: Int = ROOM_TONE_RMS) = List(frames) { Frame(P_SILENCE, amp) }

    /** The endpointer's verdicts for one stamped run: the frame index of every commit. */
    private fun commits(
        frames: List<Frame>,
        stampStepMs: Long,
        minCommitIntervalMs: Long = 0L,
        armFlatline: Boolean = false,
        onEachCut: (Int, EndpointCut?) -> Unit = { _, _ -> },
    ): List<Int> {
        val probe = Probe()
        val ep = SileroEndpointer(probe = probe)
        ep.onSessionStart(nowMs = TAP, minCommitIntervalMs = minCommitIntervalMs)
        ep.armFlatline(armFlatline)
        val fired = ArrayList<Int>()
        var t = TAP
        frames.forEachIndexed { i, f ->
            probe.next = f.p
            if (ep.onFrame(ByteArray(B), f.amp, t)) {
                fired += i
                onEachCut(i, ep.lastCut())
            }
            t += stampStepMs
        }
        return fired
    }

    /** The evidence the endpointer reports after one stamped run. */
    private fun evidenceMs(frames: List<Frame>, stampStepMs: Long): Long {
        val probe = Probe()
        val ep = SileroEndpointer(probe = probe)
        ep.onSessionStart(nowMs = TAP, minCommitIntervalMs = Long.MAX_VALUE)
        var t = TAP
        for (f in frames) {
            probe.next = f.p
            ep.onFrame(ByteArray(B), f.amp, t)
            t += stampStepMs
        }
        return ep.speechEvidenceMs()
    }

    /**
     * The drain-time clock's stamp step: the pace replays [StartupRing.DRAIN_CHUNKS_PER_TICK]
     * buffered chunks inside one live 32 ms period, so buffered audio advances the wall clock at
     * 1/pace of its own rate.
     */
    private val drainStepMs = EndpointerTuning.FRAME_MS / StartupRing.DRAIN_CHUNKS_PER_TICK

    /**
     * The cycle every fixture below is built from: one utterance over MIN_SPEECH_MS plus one dip
     * long enough to cut. 23 frames = 736 ms at the shipped hangover.
     */
    private val cycleFrames = EndpointerGrid.SPEECH_FRAMES_OVER_MIN + EndpointerGrid.HANGOVER_FRAMES

    /**
     * The number of cycles that covers the measured 4,107 ms worst case and still fits the ring —
     * derived, not a literal, so a hangover A/B moves it instead of invalidating the fixture.
     */
    private val burstCycles: Int =
        ((4_107L + cycleFrames * EndpointerTuning.FRAME_MS - 1) / (cycleFrames * EndpointerTuning.FRAME_MS)).toInt()

    /** The measured worst-case connect as speech: ~4.4 s at the shipped grid. */
    private val fourSecondBurst: List<Frame> = buildList {
        repeat(burstCycles) {
            addAll(word(EndpointerGrid.SPEECH_FRAMES_OVER_MIN))
            addAll(dip(EndpointerGrid.HANGOVER_FRAMES))
        }
    }

    @Test
    fun theBurstIsTheMeasuredWorstCaseAndFitsTheRing() {
        val ms = fourSecondBurst.size * EndpointerTuning.FRAME_MS
        assertTrue(
            "the fixture must be the size of the problem: the measured 4,107 ms cold npu-turbo load",
            ms >= 4_107L,
        )
        assertTrue("and inside the ring's cap, so it is a burst the ring really delivers", ms <= StartupRing.CAPACITY_MS)
    }

    @Test
    fun theHangoverCutsOnTheAudiosOwnClockAndAFourFoldCompressionLosesEveryCut() {
        val cycle = cycleFrames
        val expected = List(burstCycles) { cycle * it + cycle - 1 }

        // ORIGINAL STAMPS: the four utterances cut exactly where live delivery cuts them — the
        // last frame of each dip, which is the smallest k with (k - 1) * FRAME_MS >= HANGOVER_MS.
        val original = ArrayList<Long>()
        assertEquals(
            "a replayed burst carrying its own stamps is indistinguishable from live audio",
            expected,
            commits(fourSecondBurst, stampStepMs = EndpointerTuning.FRAME_MS) { _, cut ->
                original += cut!!.trailMs
            },
        )
        assertEquals(
            "and each cut reports the real trailing silence",
            List(burstCycles) { EndpointerGrid.HANGOVER_TRAIL_MS },
            original,
        )

        // DRAIN-TIME CLOCK: the same dips are stamped a quarter as far apart, so no dip ever
        // reaches HANGOVER_MS and the entire 4 s pre-roll lands in ONE segment — the first-segment
        // boundary the user would have had is gone, silently.
        assertEquals(
            "a re-stamped replay cannot end an utterance at all",
            emptyList<Int>(),
            commits(fourSecondBurst, stampStepMs = drainStepMs),
        )
    }

    @Test
    fun theCadenceFloorMeasuresTheAudiosRealIntervalNotTheDrainsWallTime() {
        // The floor is consulted only inside the `hasCommitted &&` guard, so the session's FIRST
        // cut is free under either clock; what the clock decides is whether the SECOND one stands.
        val interval = EndpointerGrid.FIXTURE_INTERVAL_MS      // 736 ms at the shipped hangover
        val cycle = cycleFrames

        // Under the audio's own clock the governor sees the TRUE interval. A floor one frame UNDER
        // it lets every cut stand...
        assertEquals(
            List(burstCycles) { cycle * it + cycle - 1 },
            commits(fourSecondBurst, EndpointerTuning.FRAME_MS, minCommitIntervalMs = interval - EndpointerTuning.FRAME_MS),
        )
        // ...and one frame OVER it merges every second one: each merge closes the gate, so the cut
        // after it is two intervals from the last commit and clears the floor again. That
        // alternation is the signature of a floor being measured against the audio's real spacing.
        assertEquals(
            (0 until burstCycles).filter { it % 2 == 0 }.map { cycle * it + cycle - 1 },
            commits(fourSecondBurst, EndpointerTuning.FRAME_MS, minCommitIntervalMs = interval + EndpointerTuning.FRAME_MS),
        )

        // A drain-time clock would report a quarter of that interval, so a floor the audio really
        // clears would merge its utterances — but the compression has already cost every cut (the
        // hangover row), which is what makes this term unobservable rather than merely wrong: the
        // governor cannot merge endpoints that no longer exist.
        assertEquals(
            emptyList<Int>(),
            commits(fourSecondBurst, drainStepMs, minCommitIntervalMs = interval - EndpointerTuning.FRAME_MS),
        )
    }

    @Test
    fun theFirstSegmentCapFiresDuringTheReplayBecauseItsWindowOpensAtTheTap() {
        // S2 anchors the window at the TAP (FloatingBubbleService: segmentCapPolicy
        // .onSessionStart(sessionStartMs)), which is what it always meant to measure — the
        // uncommitted stretch of AUDIO, not the stretch of engine-ready wall time.
        val policy = SegmentCapPolicy()
        policy.onSessionStart(TAP)

        // Replayed stamps: the cap fires inside the burst, at the instant it would have fired had
        // the engine been warm. That is a deliberate behaviour change and the right one — it is
        // the cut the user would have got.
        val capFrame = fourSecondBurst.indices.first { i ->
            policy.capExceeded(TAP + i * EndpointerTuning.FRAME_MS)
        }
        assertEquals(
            "the 4 s first cap lands on the first frame at or past 4,000 ms of captured audio",
            (SegmentCapPolicy.FIRST_SEGMENT_WALL_MS / EndpointerTuning.FRAME_MS).toInt(),
            capFrame,
        )
        assertEquals(SegmentCapPolicy.FIRST_SEGMENT_WALL_MS, policy.currentCapMs())

        // A drain-time clock compresses the same audio to a quarter of its length, so a full ring
        // never reaches the cap and the whole pre-roll waits for the 15 s later cap instead.
        val fresh = SegmentCapPolicy()
        fresh.onSessionStart(TAP)
        assertFalse(
            "a re-stamped replay of the whole ring would not even reach the first cap",
            fresh.capExceeded(TAP + (StartupRing.CAPACITY_MS / StartupRing.DRAIN_CHUNKS_PER_TICK)),
        )
    }

    @Test
    fun theFlatlineTriggerIsAFrameCountSoBothClocksFireOnTheSameFrame() {
        // Device audio, edited: a word, then an editor's gate — digital silence far too short for
        // the hangover. The flat run is a COUNT (SileroEndpointer.flatRun, machine.py DECISION 5,
        // "because the device stamps one bursty currentTimeMillis() per chunk"), so the FIRE is
        // stamp-independent, and that is the half of this term the amendment asks about.
        val trace = word(EndpointerGrid.SPEECH_FRAMES_OVER_MIN) + dip(EndpointerTuning.FLATLINE_CHUNKS, GATED_RMS)
        val fireFrame = EndpointerGrid.SPEECH_FRAMES_OVER_MIN + EndpointerTuning.FLATLINE_CHUNKS - 1

        val kinds = ArrayList<EndpointCutKind>()
        assertEquals(
            listOf(fireFrame),
            commits(trace, EndpointerTuning.FRAME_MS, armFlatline = true) { _, cut -> kinds += cut!!.kind },
        )
        assertEquals(listOf(EndpointCutKind.FLAT), kinds)

        // BUT the flat close's own MIN_SPEECH discard is wall-clock (`speechMs = tempEndMs -
        // speechStartMs`), and under a compressed clock the same real word measures a quarter as
        // long and is DISCARDED as too short to be an utterance. The trigger fires identically;
        // what it produces does not. One more concrete failure of the rejected alternative.
        assertEquals(
            "a re-stamped replay discards a word the audio really contained",
            emptyList<Int>(),
            commits(trace, drainStepMs, armFlatline = true),
        )
        assertNotEquals(
            "the two clocks are not interchangeable even where the trigger is a count",
            commits(trace, EndpointerTuning.FRAME_MS, armFlatline = true),
            commits(trace, drainStepMs, armFlatline = true),
        )
    }

    @Test
    fun theSpeechEvidenceGateIsAFrameCountSoBothClocksAgree() {
        // `speechEvidenceMs() = evidenceFrames * FRAME_MS` — a pure frame count, incremented once
        // per onset frame. Indifferent to the stamps, which is what the amendment asks.
        val trace = word(EndpointerGrid.SPEECH_FRAMES_OVER_MIN) + dip(EndpointerGrid.HOLD_FRAMES)
        val onOwnClock = evidenceMs(trace, EndpointerTuning.FRAME_MS)
        assertEquals(
            EndpointerGrid.SPEECH_FRAMES_OVER_MIN * EndpointerTuning.FRAME_MS,
            onOwnClock,
        )
        assertEquals(
            "the evidence the commit funnel gates the encode on is stamp-independent",
            onOwnClock,
            evidenceMs(trace, drainStepMs),
        )
    }
}
