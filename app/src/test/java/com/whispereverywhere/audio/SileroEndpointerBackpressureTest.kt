package com.whispereverywhere.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val B = EndpointerTuning.FRAME_BYTES

// THE GRID, as SileroEndpointerTest aliases it. Every frame count below is derived from these.
private val HANGOVER_FRAMES = EndpointerGrid.HANGOVER_FRAMES
private val HANGOVER_TRAIL_MS = EndpointerGrid.HANGOVER_TRAIL_MS
private val SPEECH_FRAMES = EndpointerGrid.SPEECH_FRAMES_OVER_MIN

/** Non-zero: 0L is the endpointer's "no micro-pause remembered" sentinel. */
private const val BASE = 1_000_000L

/**
 * Turbo's two rows, QUOTED rather than imported: Workstream C compiles without the service
 * package, exactly as the 8 000 / 1 200 / 6 000 literals in `SileroEndpointerTest` are quoted.
 * `CommitCadencePolicyTest` is where the numbers are pinned; here they are only the two floors
 * a session hands over.
 */
private const val FAST_MS = 2_000L
private const val SLOW_MS = 3_200L

/**
 * The VAD fixture's endpoint period: after the first cut, every `endpoint()` below lands
 * exactly this far after the previous one — SPEECH_FRAMES of speech plus HANGOVER_FRAMES of
 * silence — whether that previous one committed or merged (a merge closes the gate, so the next
 * onset re-opens it on the same grid). `EndpointerGrid.FIXTURE_INTERVAL_MS` = 736 at the shipped
 * hangover.
 */
private val IV = EndpointerGrid.FIXTURE_INTERVAL_MS

/**
 * The endpoint index that first CLEARS a floor: endpoint k lands at `k * IV` after the last
 * commit and the guard is `nowMs - lastCommitMs < floor`, so the first k with `k * IV >= floor`
 * commits — `ceil(floor / IV)`. 3 for 2 000 (2 208 ms), 5 for 3 200 (3 680 ms). The two being
 * DIFFERENT integers is what makes every test below able to tell the two floors apart; the grid
 * test at the top asserts it.
 */
private fun kFor(floorMs: Long): Int = ((floorMs + IV - 1) / IV).toInt()
private val FAST_K = kFor(FAST_MS)
private val SLOW_K = kFor(SLOW_MS)

/**
 * The FLAT fixture's period. A gated word is 12 speech frames (384 ms, over MIN_SPEECH_MS) then
 * FLATLINE_CHUNKS frames of digital silence in the DEAD BAND (p = 0.40: Silero can never cut
 * them, so every commit is the flat trigger's). The cut fires ON the last flat frame, the next
 * word's onset re-opens the gate one frame later, so the period is `(12 + FLATLINE_CHUNKS)`
 * frames — 544 ms at five chunks. `ceil(2000 / 544)` = 4 and `ceil(3200 / 544)` = 6.
 */
private const val WORD_FRAMES = 12
private val FLAT_CHUNKS = EndpointerTuning.FLATLINE_CHUNKS
private val FLAT_IV: Long = (WORD_FRAMES + FLAT_CHUNKS) * EndpointerTuning.FRAME_MS
private fun flatKFor(floorMs: Long): Int = ((floorMs + FLAT_IV - 1) / FLAT_IV).toInt()
private val FLAT_FAST_K = flatKFor(FAST_MS)
private val FLAT_SLOW_K = flatKFor(SLOW_MS)

private const val SPEECH_RMS = 3_000
private const val GATED_RMS = 0
private const val P_SPEECH = 0.9f
private const val P_GAP_DEADBAND = 0.40f
private const val P_SILENCE = 0.1f

private class BpProbe(var next: Float = 0f) : (ByteArray) -> Float {
    override fun invoke(frame: ByteArray): Float = next
}

/** `SileroEndpointerTest.Pump` with an amplitude beside the probability, as `FlatPump` has. */
private class BpPump(val ep: SileroEndpointer, val probe: BpProbe, var t: Long = BASE) {
    var commits = 0
    var lastCommitMs = -1L

    fun run(p: Float, frames: Int, amp: Int = 0): Boolean {
        probe.next = p
        var fired = false
        repeat(frames) {
            if (ep.onFrame(ByteArray(B), amp, t)) {
                fired = true
                commits++
                lastCommitMs = t
            }
            t += EndpointerTuning.FRAME_MS
        }
        return fired
    }

    /** The session's free first cut: 20 speech frames, then the hangover. Commits at BASE + 992. */
    fun firstCut(): Long {
        run(P_SPEECH, 20)
        assertTrue("the session's first cut is free on every floor", run(P_SILENCE, HANGOVER_FRAMES))
        return lastCommitMs
    }

    /** One VAD endpoint, IV after the previous one. @return true if it committed. */
    fun endpoint(): Boolean {
        run(P_SPEECH, SPEECH_FRAMES)
        return run(P_SILENCE, HANGOVER_FRAMES)
    }

    /** One gated word: WORD_FRAMES of speech, then FLAT_CHUNKS of dead-band digital silence. */
    fun gatedWord(): Boolean {
        run(P_SPEECH, WORD_FRAMES, SPEECH_RMS)
        return run(P_GAP_DEADBAND, FLAT_CHUNKS, GATED_RMS)
    }
}

private class Fixture(val ep: SileroEndpointer, val pump: BpPump, val lines: MutableList<String>) {
    /** The governor's own diag lines, in order — the `probe:` lines share the sink and are dropped. */
    fun backpressureLines(): List<String> = lines.filter { it.startsWith("backpressure:") }
}

private fun fresh(fastMs: Long = FAST_MS, slowMs: Long? = SLOW_MS, armed: Boolean = false): Fixture {
    val probe = BpProbe()
    val lines = mutableListOf<String>()
    val ep = SileroEndpointer(probe = probe, diag = { lines += it })
    if (slowMs == null) {
        // The DEFAULTED third parameter: the two-argument call every existing caller makes.
        ep.onSessionStart(nowMs = BASE, minCommitIntervalMs = fastMs)
    } else {
        ep.onSessionStart(nowMs = BASE, minCommitIntervalMs = fastMs, slowCommitIntervalMs = slowMs)
    }
    ep.armFlatline(armed)
    return Fixture(ep, BpPump(ep, probe), lines)
}

private const val SLOW_LINE = "backpressure: depth=2 -> slow floor 3200"
private const val FAST_LINE = "backpressure: depth=1 -> fast floor 2000"

/**
 * THE BACKPRESSURE GOVERNOR (build 85) at the endpointer: the fast floor while the segment queue
 * is at most one deep, the slow floor once it reaches two, stepped on the CAPTURE thread at each
 * real endpoint from the depth the service publishes through [Endpointer.onQueueDepth].
 *
 * The reference twin is `tools/vadsim/tests/test_backpressure.py`, which drives the simulator
 * through the same fixtures BY NAME; `the_cross_trace_fixture_commits_where_the_simulator_says`
 * is the trace-level comparison the brief asks for and reads the file the simulator wrote.
 *
 * Every number is derived from the grid; the two `k` values differing is what gives each test
 * its bite — an endpoint at `FAST_K * IV` = 2 208 ms commits under 2 000 and MERGES under 3 200,
 * so the floor in force is observable from the commit count alone.
 */
class SileroEndpointerBackpressureTest {

    @Test fun the_grid_this_file_relies_on() {
        assertEquals(3, FAST_K)
        assertEquals(5, SLOW_K)
        assertEquals(4, FLAT_FAST_K)
        assertEquals(6, FLAT_SLOW_K)
        assertTrue("the fast floor must clear at an endpoint the slow floor merges", FAST_K < SLOW_K)
        assertTrue(FLAT_FAST_K < FLAT_SLOW_K)
        assertTrue("a gated word must be over MIN_SPEECH_MS or every flat close is a discard",
            WORD_FRAMES * EndpointerTuning.FRAME_MS > EndpointerTuning.MIN_SPEECH_MS)
    }

    // ---------------------------------------------------------------------------------------
    // The two floors, observed from the commit count.
    // ---------------------------------------------------------------------------------------

    @Test fun at_depth_0_and_1_a_real_endpoint_2000ms_after_the_last_commit_commits() {
        val f = fresh()
        val commit1 = f.pump.firstCut()
        assertEquals(BASE + 640 + HANGOVER_TRAIL_MS, commit1)
        // depth 0 (never published): the fast floor.
        repeat(FAST_K - 1) { assertFalse("inside 2000", f.pump.endpoint()) }
        assertTrue("${FAST_K * IV} ms clears 2000", f.pump.endpoint())
        assertEquals(commit1 + FAST_K * IV, f.pump.lastCommitMs)
        // depth 1 (one in flight, nothing waiting): still the fast floor.
        f.ep.onQueueDepth(1)
        val commit2 = f.pump.lastCommitMs
        repeat(FAST_K - 1) { assertFalse(f.pump.endpoint()) }
        assertTrue(f.pump.endpoint())
        assertEquals(commit2 + FAST_K * IV, f.pump.lastCommitMs)
        assertEquals(3, f.pump.commits)
        assertEquals("no transition, no line", emptyList<String>(), f.backpressureLines())
    }

    @Test fun at_depth_2_the_endpointer_merges_until_3200ms() {
        val f = fresh()
        val commit1 = f.pump.firstCut()
        f.ep.onQueueDepth(2)
        // Every endpoint under 3 200 merges — INCLUDING the one at FAST_K * IV = 2 208, which the
        // fast floor would have taken. That endpoint is the whole test.
        repeat(SLOW_K - 1) { k ->
            assertFalse("endpoint ${k + 1} at +${(k + 1) * IV} ms is inside 3200", f.pump.endpoint())
        }
        assertEquals(1, f.pump.commits)
        assertTrue("the merged audio is still uncommitted", f.ep.hasPendingSpeech())
        assertTrue("${SLOW_K * IV} ms clears 3200", f.pump.endpoint())
        assertEquals(2, f.pump.commits)
        assertEquals(commit1 + SLOW_K * IV, f.pump.lastCommitMs)
        assertEquals(listOf(SLOW_LINE), f.backpressureLines())
    }

    @Test fun after_depth_returns_to_1_the_next_endpoint_at_2000ms_or_more_commits() {
        val f = fresh()
        f.pump.firstCut()
        f.ep.onQueueDepth(2)
        repeat(SLOW_K - 1) { assertFalse(f.pump.endpoint()) }
        assertTrue(f.pump.endpoint())
        val commit2 = f.pump.lastCommitMs
        // The NPU caught up: one in flight, nothing waiting.
        f.ep.onQueueDepth(1)
        repeat(FAST_K - 1) { assertFalse(f.pump.endpoint()) }
        assertTrue("back on the fast floor: ${FAST_K * IV} ms commits", f.pump.endpoint())
        assertEquals(commit2 + FAST_K * IV, f.pump.lastCommitMs)
        assertEquals(3, f.pump.commits)
        assertEquals(listOf(SLOW_LINE, FAST_LINE), f.backpressureLines())
    }

    @Test fun a_depth_change_mid_interval_takes_effect_at_the_next_endpoint_never_retroactively() {
        // (a) SLOW -> FAST mid-interval. The endpoint at 2 208 was merged under the slow floor;
        // the depth then drops. Nothing commits until a NEW endpoint — the merged one is gone,
        // its gate closed — and that next endpoint (2 944, under 3 200) is judged under the FAST
        // floor and commits.
        run {
            val f = fresh()
            val commit1 = f.pump.firstCut()
            f.ep.onQueueDepth(2)
            repeat(FAST_K) { assertFalse(f.pump.endpoint()) }      // ...including 2 208: merged
            f.ep.onQueueDepth(1)                                    // mid-interval
            // A short pad of silence after the drop: nothing commits on a depth change alone.
            // Four frames, not more — the next endpoint must still land INSIDE the slow floor
            // (2 944 + 128 = 3 072 < 3 200) for the commit to prove which floor judged it.
            val pad = 4
            assertFalse("a depth drop is not a commit", f.pump.run(P_SILENCE, pad))
            assertEquals(1, f.pump.commits)
            assertTrue("the NEXT endpoint is judged under the fast floor", f.pump.endpoint())
            assertEquals(commit1 + (FAST_K + 1) * IV + pad * EndpointerTuning.FRAME_MS, f.pump.lastCommitMs)
            assertTrue("and it is inside the slow floor, so only the fast one can have taken it",
                f.pump.lastCommitMs - commit1 < SLOW_MS)
            assertEquals(listOf(SLOW_LINE, FAST_LINE), f.backpressureLines())
        }
        // (b) FAST -> SLOW mid-interval. Two endpoints merged under the fast floor; the depth
        // then rises. The endpoint at 2 208 — which the fast floor would take — is judged under
        // the SLOW floor and merges.
        run {
            val f = fresh()
            val commit1 = f.pump.firstCut()
            repeat(FAST_K - 1) { assertFalse(f.pump.endpoint()) }
            f.ep.onQueueDepth(2)                                    // mid-interval
            assertFalse("${FAST_K * IV} ms would clear 2000 — but the floor is 3200 now",
                f.pump.endpoint())
            repeat(SLOW_K - FAST_K - 1) { assertFalse(f.pump.endpoint()) }
            assertTrue(f.pump.endpoint())
            assertEquals(commit1 + SLOW_K * IV, f.pump.lastCommitMs)
            assertEquals(listOf(SLOW_LINE), f.backpressureLines())
        }
    }

    @Test fun the_flat_path_obeys_the_same_floor_as_the_vad_path() {
        val f = fresh(armed = true)
        // The free first cut, by the flat trigger: fires on the last flat frame of the first word.
        assertTrue(f.pump.gatedWord())
        val commit1 = f.pump.lastCommitMs
        assertEquals(BASE + (WORD_FRAMES + FLAT_CHUNKS - 1) * EndpointerTuning.FRAME_MS, commit1)
        assertEquals(EndpointCutKind.FLAT, f.ep.lastCut()!!.kind)
        // depth 0: the fast floor — flat closes merge until 2 176.
        repeat(FLAT_FAST_K - 1) { assertFalse(f.pump.gatedWord()) }
        assertTrue(f.pump.gatedWord())
        assertEquals(commit1 + FLAT_FAST_K * FLAT_IV, f.pump.lastCommitMs)
        assertEquals(EndpointCutKind.FLAT, f.ep.lastCut()!!.kind)
        val commit2 = f.pump.lastCommitMs
        // depth 2: the SAME slow floor, through the SAME helper — 2 176 now merges, 3 264 commits.
        f.ep.onQueueDepth(2)
        repeat(FLAT_SLOW_K - 1) { k ->
            assertFalse("flat close ${k + 1} at +${(k + 1) * FLAT_IV} ms is inside 3200", f.pump.gatedWord())
        }
        assertTrue(f.pump.gatedWord())
        assertEquals(commit2 + FLAT_SLOW_K * FLAT_IV, f.pump.lastCommitMs)
        assertEquals(EndpointCutKind.FLAT, f.ep.lastCut()!!.kind)
        assertEquals(3, f.pump.commits)
        assertEquals(listOf(SLOW_LINE), f.backpressureLines())
    }

    // ---------------------------------------------------------------------------------------
    // Lifecycle: onSessionStart clears both fields; reset() touches neither.
    // ---------------------------------------------------------------------------------------

    @Test fun onSessionStart_clears_the_mode_and_the_depth() {
        val f = fresh()
        f.pump.firstCut()
        f.ep.onQueueDepth(2)
        assertFalse(f.pump.endpoint())                              // steps into SLOW, logs it
        assertEquals(listOf(SLOW_LINE), f.backpressureLines())
        // A new session, and NO onQueueDepth call: the service resets its counter at session
        // start and the endpointer must not inherit either the depth or the mode.
        f.pump.t += 10_000
        f.ep.onSessionStart(nowMs = f.pump.t, minCommitIntervalMs = FAST_MS, slowCommitIntervalMs = SLOW_MS)
        val commit1 = f.pump.firstCut()
        repeat(FAST_K - 1) { assertFalse(f.pump.endpoint()) }
        assertTrue("a surviving depth of 2 would merge this endpoint", f.pump.endpoint())
        assertEquals(commit1 + FAST_K * IV, f.pump.lastCommitMs)
        // A surviving MODE with a cleared depth would have stepped 2 -> 0 at the first endpoint
        // and logged "fast floor". Nothing was logged: both were cleared, not stepped.
        assertEquals(listOf(SLOW_LINE), f.backpressureLines())
    }

    @Test fun reset_keeps_the_mode_and_the_depth() {
        val f = fresh()
        f.pump.firstCut()
        f.ep.onQueueDepth(2)
        assertFalse(f.pump.endpoint())                              // SLOW, logged
        // A cap cut / switchSource / stopRecording: an external commit, not a session boundary.
        val anchor = f.pump.t - EndpointerTuning.FRAME_MS           // reset anchors on the last frame
        f.ep.reset()
        assertFalse(f.ep.hasPendingSpeech())
        // Measured from the anchor, the SLOW floor still governs: 2 208 merges, 3 680 commits.
        repeat(SLOW_K - 1) { assertFalse(f.pump.endpoint()) }
        assertTrue(f.pump.endpoint())
        assertEquals(anchor + SLOW_K * IV, f.pump.lastCommitMs)
        // And no second "slow floor" line: the mode was KEPT, not re-entered.
        assertEquals(listOf(SLOW_LINE), f.backpressureLines())
    }

    // ---------------------------------------------------------------------------------------
    // Inert by default, free first cut, the diag line.
    // ---------------------------------------------------------------------------------------

    @Test fun the_governor_is_inert_when_slow_equals_fast_which_is_the_default_parameter() {
        for (explicit in listOf(false, true)) {
            val f = if (explicit) fresh(slowMs = FAST_MS) else fresh(slowMs = null)
            val commit1 = f.pump.firstCut()
            f.ep.onQueueDepth(2)
            repeat(FAST_K - 1) { assertFalse(f.pump.endpoint()) }
            assertTrue("slow == fast: depth 2 changes nothing (explicit=$explicit)", f.pump.endpoint())
            assertEquals(commit1 + FAST_K * IV, f.pump.lastCommitMs)
            // Unarmed, the mode still steps but there is no floor change to announce.
            assertEquals(emptyList<String>(), f.backpressureLines())
        }
    }

    @Test fun the_sessions_first_endpoint_is_free_at_any_depth() {
        val f = fresh()
        f.ep.onQueueDepth(2)
        f.pump.firstCut()
        assertEquals(1, f.pump.commits)
        // The step runs only where the floor is consulted, and the free first cut never consults
        // it — so the backlog a previous session left in flight cannot delay this one's first text.
        assertEquals(emptyList<String>(), f.backpressureLines())
    }

    @Test fun the_transition_line_is_emitted_once_per_transition_and_names_the_new_floor() {
        val f = fresh()
        f.pump.firstCut()
        f.ep.onQueueDepth(2); f.pump.endpoint()
        assertEquals(listOf(SLOW_LINE), f.backpressureLines())
        f.ep.onQueueDepth(3); f.pump.endpoint()
        assertEquals("deeper is not a transition", listOf(SLOW_LINE), f.backpressureLines())
        f.ep.onQueueDepth(0); f.pump.endpoint()
        assertEquals(listOf(SLOW_LINE, "backpressure: depth=0 -> fast floor 2000"), f.backpressureLines())
        f.ep.onQueueDepth(2); f.pump.endpoint()
        assertEquals(3, f.backpressureLines().size)
        assertEquals(SLOW_LINE, f.backpressureLines().last())
        // The line carries the ENDPOINT's step, not the Main-thread publish: a depth published
        // between endpoints is announced at the next endpoint, and only there.
        f.ep.onQueueDepth(1)
        assertEquals(3, f.backpressureLines().size)
        f.pump.endpoint()
        assertEquals(4, f.backpressureLines().size)
        assertEquals(FAST_LINE, f.backpressureLines().last())
    }

    @Test fun the_line_never_carries_transcript_content_or_a_clock() {
        // Depth and floor, nothing else: the release build strips it anyway, but a debug capture
        // is greppable by prefix and safe to paste.
        val f = fresh()
        f.pump.firstCut()
        f.ep.onQueueDepth(2); f.pump.endpoint()
        f.ep.onQueueDepth(1); f.pump.endpoint()
        for (line in f.backpressureLines()) {
            assertTrue(line, Regex("""^backpressure: depth=\d+ -> (slow|fast) floor \d+$""").matches(line))
        }
    }
}
