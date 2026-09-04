package com.whispereverywhere.service

import com.whispereverywhere.audio.EndpointCutKind
import com.whispereverywhere.audio.EndpointerGrid
import com.whispereverywhere.audio.EndpointerTuning
import com.whispereverywhere.audio.SileroEndpointer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review round 1 of THE BACKPRESSURE GOVERNOR (build 85, brief §A): adversarial traces run
 * through the JVM machine, beyond `SileroEndpointerBackpressureTest`'s grid. Each trace is one
 * the brief named or one the reviewer could not settle by reading:
 *
 *  R1  depth flapping at the boundary, between endpoints — only the LAST publish is judged, one
 *      line per mode change, no line for a flap that lands where the mode already is;
 *  R2  a depth published ON the commit frame (the hangover's last frame), one frame after it,
 *      and in the middle of the hangover;
 *  R3  depth 2 during the first segment's window — the 4 s cap cut (`reset()`) is a commit, the
 *      mode is stepped at the first REAL endpoint after it, never by the cap;
 *  R4  a flat close under a slow mode the VAD path entered (and the flat path releasing it);
 *  R5  a cap cut under slow mode, followed by a depth drop mid-interval; and a cap cut BEFORE
 *      the mode has been stepped;
 *  R6  the mode bit cannot outlive the depth: a stale slow bit after a long silence is
 *      re-stepped at the next endpoint, so nothing is sticky;
 *  R7  a mode stepped silently in an UNARMED session is not inherited by an armed one;
 *  R8  the simulator's decoder model on the Kotlin machine: a single-server decoder fed through
 *      the REAL `SegmentQueueDepth` listener into the REAL endpointer — the queue is bounded at
 *      ENTER, the intervals are the grid's, the ungoverned run grows.
 *
 * Lives in the service package so R8 can wire `SegmentQueueDepth` to the endpointer; the audio
 * package's tests quote the turbo rows rather than import this package, and this file respects
 * that direction by living on the side that may import both.
 */
class BackpressureReviewTracesTest {

    private companion object {
        const val B = EndpointerTuning.FRAME_BYTES
        const val FRAME = EndpointerTuning.FRAME_MS
        const val BASE = 1_000_000L
        val FAST = CommitCadencePolicy.MIN_COMMIT_INTERVAL_TURBO_MS
        val SLOW = CommitCadencePolicy.MIN_COMMIT_INTERVAL_TURBO_SLOW_MS
        val IV = EndpointerGrid.FIXTURE_INTERVAL_MS                       // 736
        val HANG = EndpointerGrid.HANGOVER_FRAMES                          // 12
        val SPEECH = EndpointerGrid.SPEECH_FRAMES_OVER_MIN                 // 11
        fun kFor(floor: Long) = ((floor + IV - 1) / IV).toInt()
        val FAST_K = kFor(FAST)                                            // 3 -> 2 208
        val SLOW_K = kFor(SLOW)                                            // 5 -> 3 680

        const val P_SPEECH = 0.9f
        const val P_SILENCE = 0.1f
        const val P_DEAD = 0.40f
        const val LOUD = 3_000
        /** Over FLATLINE_RMS_MAX: a VAD-path silence frame the flat trigger can never fire on. */
        const val QUIET = 300
        const val FLAT = 0
        const val WORD = 12
        val FLAT_CHUNKS = EndpointerTuning.FLATLINE_CHUNKS
        val FLAT_IV = (WORD + FLAT_CHUNKS) * FRAME                         // 544

        const val SLOW_LINE = "backpressure: depth=2 -> slow floor 3200"
        const val FAST_LINE = "backpressure: depth=1 -> fast floor 2000"
    }

    private class Probe(var next: Float = 0f) : (ByteArray) -> Float {
        override fun invoke(frame: ByteArray): Float = next
    }

    private class Rig(fast: Long = FAST, slow: Long? = SLOW, armed: Boolean = false) {
        val probe = Probe()
        val lines = mutableListOf<String>()
        val ep = SileroEndpointer(probe = probe, diag = { lines += it })
        var t = BASE
        var commits = 0
        var lastCommitMs = -1L
        val commitTimes = mutableListOf<Long>()
        /** R8's hooks: before every frame, and after a frame that committed. */
        var beforeFrame: (Long) -> Unit = {}
        var onCommit: (Long) -> Unit = {}

        init {
            if (slow == null) ep.onSessionStart(nowMs = BASE, minCommitIntervalMs = fast)
            else ep.onSessionStart(nowMs = BASE, minCommitIntervalMs = fast, slowCommitIntervalMs = slow)
            ep.armFlatline(armed)
        }

        fun bp(): List<String> = lines.filter { it.startsWith("backpressure:") }

        fun run(p: Float, frames: Int, amp: Int = QUIET): Boolean {
            probe.next = p
            var fired = false
            repeat(frames) {
                beforeFrame(t)
                if (ep.onFrame(ByteArray(B), amp, t)) {
                    fired = true; commits++; lastCommitMs = t; commitTimes += t
                    onCommit(t)
                }
                t += FRAME
            }
            return fired
        }

        fun firstCut(): Long {
            run(P_SPEECH, 20, LOUD)
            assertTrue("the free first cut", run(P_SILENCE, HANG))
            return lastCommitMs
        }

        fun endpoint(): Boolean {
            run(P_SPEECH, SPEECH, LOUD)
            return run(P_SILENCE, HANG)
        }

        /** The endpoint split at its commit frame: [onCommitFrame] runs just before the last hangover frame. */
        fun endpointWith(onCommitFrame: () -> Unit): Boolean {
            run(P_SPEECH, SPEECH, LOUD)
            assertFalse(run(P_SILENCE, HANG - 1))
            onCommitFrame()
            return run(P_SILENCE, 1)
        }

        fun gatedWord(): Boolean {
            run(P_SPEECH, WORD, LOUD)
            return run(P_DEAD, FLAT_CHUNKS, FLAT)
        }
    }

    @Test fun the_grid_this_file_relies_on() {
        assertEquals(736L, IV); assertEquals(12, HANG); assertEquals(11, SPEECH)
        assertEquals(3, FAST_K); assertEquals(5, SLOW_K); assertEquals(544L, FLAT_IV)
        assertTrue(QUIET > EndpointerTuning.FLATLINE_RMS_MAX)
    }

    // R1 -----------------------------------------------------------------------------------

    @Test fun r1_flapping_between_endpoints_only_the_last_publish_is_judged_and_one_line_per_change() {
        val g = Rig()
        val c1 = g.firstCut()
        fun flapEndingAt(last: Int) {
            repeat(50) { g.ep.onQueueDepth(2); g.ep.onQueueDepth(1); g.ep.onQueueDepth(3); g.ep.onQueueDepth(0) }
            g.ep.onQueueDepth(last)
        }
        flapEndingAt(2); assertFalse(g.endpoint())                          // +736: slow
        assertEquals(listOf(SLOW_LINE), g.bp())
        flapEndingAt(1); assertFalse(g.endpoint())                          // +1 472: fast, still inside 2 000
        assertEquals(listOf(SLOW_LINE, FAST_LINE), g.bp())
        flapEndingAt(2); assertFalse("2 208 would commit under fast", g.endpoint())
        assertEquals(listOf(SLOW_LINE, FAST_LINE, SLOW_LINE), g.bp())
        flapEndingAt(1); assertTrue("2 944 commits under fast", g.endpoint())
        assertEquals(c1 + 4 * IV, g.lastCommitMs)
        assertEquals(listOf(SLOW_LINE, FAST_LINE, SLOW_LINE, FAST_LINE), g.bp())
        // a flap that lands where the mode already is: no line, no change
        flapEndingAt(1); assertFalse(g.endpoint())
        assertEquals(4, g.bp().size)
        flapEndingAt(2); assertFalse(g.endpoint())                          // +1 472 from c2: slow
        assertEquals(5, g.bp().size)
        flapEndingAt(2); assertFalse(g.endpoint())                          // +2 208: slow, no line
        assertEquals(5, g.bp().size)
        repeat(SLOW_K - 4) { assertFalse(g.endpoint()) }
        assertTrue(g.endpoint())                                            // +3 680: commits under slow
        assertEquals(c1 + 4 * IV + SLOW_K * IV, g.lastCommitMs)
        assertEquals(5, g.bp().size)
    }

    // R2 -----------------------------------------------------------------------------------

    @Test fun r2_a_depth_published_on_the_commit_frame_is_the_depth_that_judges_it() {
        // (a) rises to 2 on the very frame the fast floor would commit (2 208): merges; then
        //     drops to 1 on the 2 944 frame: commits under fast, inside the slow floor.
        run {
            val g = Rig(); val c1 = g.firstCut()
            repeat(FAST_K - 1) { assertFalse(g.endpoint()) }
            assertFalse("depth 2 on the commit frame is judged on it", g.endpointWith { g.ep.onQueueDepth(2) })
            assertEquals(1, g.commits)
            assertEquals(listOf(SLOW_LINE), g.bp())
            assertTrue("depth 1 on the commit frame: fast, and 2 944 clears it", g.endpointWith { g.ep.onQueueDepth(1) })
            assertEquals(c1 + (FAST_K + 1) * IV, g.lastCommitMs)
            assertTrue(g.lastCommitMs - c1 < SLOW)
            assertEquals(listOf(SLOW_LINE, FAST_LINE), g.bp())
        }
        // (b) published one frame AFTER a commit frame: nothing retroactive, judged at the next endpoint.
        run {
            val g = Rig(); g.firstCut()
            g.ep.onQueueDepth(2)
            assertEquals(1, g.commits)
            assertEquals(emptyList<String>(), g.bp())
            assertFalse(g.endpoint())
            assertEquals(listOf(SLOW_LINE), g.bp())
        }
        // (c) published in the MIDDLE of the hangover of the endpoint that would commit: that
        //     endpoint is judged with it (the decision is on the hangover's last frame).
        run {
            val g = Rig(); g.firstCut()
            repeat(FAST_K - 1) { assertFalse(g.endpoint()) }
            g.run(P_SPEECH, SPEECH, LOUD)
            assertFalse(g.run(P_SILENCE, 5))
            g.ep.onQueueDepth(2)
            assertFalse("the 2 208 endpoint merges", g.run(P_SILENCE, HANG - 5))
            assertEquals(1, g.commits)
            assertEquals(listOf(SLOW_LINE), g.bp())
        }
    }

    // R3 -----------------------------------------------------------------------------------

    @Test fun r3_depth_2_inside_the_first_segments_window_the_cap_cut_commits_and_the_next_endpoint_paces_slow() {
        val g = Rig()
        g.run(P_SPEECH, 16, LOUD)                          // ~0.5 s of the first segment
        g.ep.onQueueDepth(2)                               // a backlog (a switch/consent flush, say)
        g.run(P_SPEECH, 125 - 16, LOUD)                    // continuous speech to 4 s: no endpoint
        assertEquals("nothing consulted the floor, nothing stepped", emptyList<String>(), g.bp())
        val anchor = g.t - FRAME
        g.ep.reset()                                       // the service's FIRST_SEGMENT 4 s cap cut
        assertEquals("reset() never steps the mode", emptyList<String>(), g.bp())
        repeat(SLOW_K - 1) { k -> assertFalse("endpoint ${k + 1} after the cap: slow", g.endpoint()) }
        assertEquals("stepped at the first REAL endpoint after the cap", listOf(SLOW_LINE), g.bp())
        assertTrue(g.endpoint())
        assertEquals(anchor + SLOW_K * IV, g.lastCommitMs)
        assertEquals(1, g.commits)
        // control: the same trace at depth 0 commits at the fast k after the cap anchor
        val h = Rig(); h.run(P_SPEECH, 125, LOUD); val a2 = h.t - FRAME; h.ep.reset()
        repeat(FAST_K - 1) { assertFalse(h.endpoint()) }
        assertTrue(h.endpoint()); assertEquals(a2 + FAST_K * IV, h.lastCommitMs)
        assertEquals(emptyList<String>(), h.bp())
        // and the free first cut itself is never merged at any depth (no cap involved)
        val f = Rig(); f.ep.onQueueDepth(2); f.firstCut(); assertEquals(1, f.commits); assertEquals(emptyList<String>(), f.bp())
    }

    // R4 -----------------------------------------------------------------------------------

    @Test fun r4_a_flat_close_under_a_slow_mode_the_vad_path_entered_merges_at_the_slow_floor_and_the_flat_path_releases_it() {
        val g = Rig(armed = true)
        val c1 = g.firstCut()
        assertEquals(EndpointCutKind.VAD, g.ep.lastCut()!!.kind)
        g.ep.onQueueDepth(2)
        assertFalse(g.endpoint())                          // VAD endpoint at +736: steps SLOW
        assertEquals(listOf(SLOW_LINE), g.bp())
        // Gated words: flat closes every 544 ms. Under fast the first close past 2 000 commits;
        // under slow every close under 3 200 merges.
        var mergedPastFast = 0
        var committedAt = -1L
        while (committedAt < 0) {
            val fired = g.gatedWord()
            val closeMs = g.t - FRAME
            if (fired) committedAt = g.lastCommitMs else if (closeMs - c1 >= FAST) mergedPastFast++
            assertTrue("runaway", g.t - c1 < 6_000)
        }
        assertTrue("a flat close past 2 000 merged under the slow floor: $mergedPastFast", mergedPastFast >= 1)
        assertTrue("and the commit cleared 3 200: ${committedAt - c1}", committedAt - c1 >= SLOW)
        assertEquals(EndpointCutKind.FLAT, g.ep.lastCut()!!.kind)
        assertEquals("the flat path READ the mode; it did not re-enter it", listOf(SLOW_LINE), g.bp())
        // The flat path releases it: depth drops, the next flat close steps FAST (and merges,
        // 544 < 2 000); the VAD endpoint that follows is judged under fast.
        val c2 = committedAt
        g.ep.onQueueDepth(1)
        assertFalse(g.gatedWord())
        assertEquals(listOf(SLOW_LINE, FAST_LINE), g.bp())
        assertEquals(EndpointCutKind.FLAT, g.ep.lastCut()!!.kind)  // lastCut is the last CUT, unchanged by a merge
        var vadCommit = -1L
        while (vadCommit < 0) {
            if (g.endpoint()) vadCommit = g.lastCommitMs
            assertTrue("runaway", g.t - c2 < 6_000)
        }
        assertTrue("fast: ${vadCommit - c2}", vadCommit - c2 >= FAST && vadCommit - c2 < SLOW)
        assertEquals(EndpointCutKind.VAD, g.ep.lastCut()!!.kind)
        assertEquals(listOf(SLOW_LINE, FAST_LINE), g.bp())
    }

    // R5 -----------------------------------------------------------------------------------

    @Test fun r5_a_cap_cut_under_slow_mode_keeps_the_mode_and_a_depth_drop_paces_fast_from_the_cap_anchor() {
        val g = Rig(); g.firstCut(); g.ep.onQueueDepth(2)
        assertFalse(g.endpoint()); assertEquals(listOf(SLOW_LINE), g.bp())
        val anchor = g.t - FRAME; g.ep.reset()             // the 15 s cap cut, say
        g.ep.onQueueDepth(1)                               // the older job drained mid-interval
        assertFalse(g.endpoint())                          // +736: steps FAST, merges
        assertEquals(listOf(SLOW_LINE, FAST_LINE), g.bp())
        assertFalse(g.endpoint())                          // +1 472
        assertTrue(g.endpoint())                           // +2 208: fast, from the cap anchor
        assertEquals(anchor + FAST_K * IV, g.lastCommitMs)
        // (b) depth 2 published, NO endpoint since, then a cap cut: the cap does not step it, the
        //     first endpoint after the cap does, and the slow floor is measured from the cap.
        val h = Rig(); h.firstCut(); h.ep.onQueueDepth(2)
        val a2 = h.t - FRAME; h.ep.reset()
        assertEquals(emptyList<String>(), h.bp())
        repeat(SLOW_K - 1) { assertFalse(h.endpoint()) }
        assertEquals(listOf(SLOW_LINE), h.bp())
        assertTrue(h.endpoint()); assertEquals(a2 + SLOW_K * IV, h.lastCommitMs)
    }

    // R6 -----------------------------------------------------------------------------------

    @Test fun r6_a_stale_slow_bit_after_a_long_silence_is_re_stepped_at_the_next_endpoint_nothing_is_sticky() {
        val g = Rig(); g.firstCut(); g.ep.onQueueDepth(2)
        assertFalse(g.endpoint()); assertEquals(listOf(SLOW_LINE), g.bp())
        g.ep.onQueueDepth(0)                               // the queue drained during a long pause
        assertFalse(g.run(P_SILENCE, 300))                 // ~9.6 s: no endpoint, the bit stays true
        assertTrue(g.endpoint())                           // past both floors: commits, and re-steps
        assertEquals(listOf(SLOW_LINE, "backpressure: depth=0 -> fast floor 2000"), g.bp())
        val c2 = g.lastCommitMs
        repeat(FAST_K - 1) { assertFalse(g.endpoint()) }
        assertTrue(g.endpoint()); assertEquals("fast pacing resumed", c2 + FAST_K * IV, g.lastCommitMs)
        assertEquals(2, g.bp().size)
    }

    // R7 -----------------------------------------------------------------------------------

    @Test fun r7_a_mode_stepped_silently_in_an_unarmed_session_is_not_inherited_by_an_armed_one() {
        val g = Rig(slow = null); g.firstCut(); g.ep.onQueueDepth(2)
        assertFalse(g.endpoint())                          // steps SLOW silently (unarmed)
        assertEquals(emptyList<String>(), g.bp())
        g.t += 5_000
        g.ep.onSessionStart(nowMs = g.t, minCommitIntervalMs = FAST, slowCommitIntervalMs = SLOW)
        val c1 = g.firstCut()
        repeat(FAST_K - 1) { assertFalse(g.endpoint()) }
        assertTrue(g.endpoint()); assertEquals(c1 + FAST_K * IV, g.lastCommitMs)
        assertEquals("neither the depth nor the mode crossed the session boundary", emptyList<String>(), g.bp())
    }

    // R8 -----------------------------------------------------------------------------------

    /** One server, a FIFO, resolutions at the top of the frame — `DecoderQueueSim` on the JVM. */
    private class Decoder(private val serviceMs: Long, private val q: SegmentQueueDepth) {
        private val jobs = ArrayDeque<Pair<Long, Long>>()   // (seq, finishMs)
        private var busyUntil = 0L
        private var nextSeq = 0L
        var maxDepth = 0
        fun resolveBy(t: Long) {
            while (jobs.isNotEmpty() && jobs.first().second <= t) q.onResolved(jobs.removeFirst().first)
        }
        fun commitAt(t: Long) {
            val start = maxOf(t, busyUntil)
            busyUntil = start + serviceMs
            val seq = nextSeq++
            jobs.addLast(seq to busyUntil)
            maxDepth = maxOf(maxDepth, q.onCommitted(seq))
        }
    }

    private data class Run(val commits: List<Long>, val maxDepth: Int, val lines: List<String>) {
        val gaps: List<Long> get() = commits.zipWithNext { a, b -> b - a }
    }

    private fun drive(serviceMs: Long, slow: Long?, utterances: Int = 120): Run {
        val g = Rig(slow = slow)
        val q = SegmentQueueDepth(onDepth = g.ep::onQueueDepth)
        val d = Decoder(serviceMs, q)
        g.beforeFrame = { t -> d.resolveBy(t) }
        g.onCommit = { t -> d.commitAt(t) }
        g.firstCut()
        repeat(utterances) { g.endpoint() }
        return Run(g.commitTimes.toList(), d.maxDepth, g.bp())
    }

    @Test fun r8_a_single_server_decoder_fed_through_the_real_SegmentQueueDepth_bounds_the_queue_at_ENTER() {
        // keeps up (2 050 < 2 208): the governor never engages, the governed run IS the ungoverned run
        val cool = drive(2_050, SLOW); val coolOff = drive(2_050, null)
        assertEquals(coolOff.commits, cool.commits)
        assertEquals(1, cool.maxDepth); assertEquals(emptyList<String>(), cool.lines)
        assertEquals(setOf(FAST_K * IV), cool.gaps.toSet())
        // a hot phone (2 500): ungoverned grows; governed never exceeds ENTER, fewer commits,
        // intervals on the grid at 2 208 and 2 944 (the depth-based release clears the backlog
        // within one endpoint, exactly as the simulator test says)
        val hot = drive(2_500, SLOW); val hotOff = drive(2_500, null)
        assertTrue("ungoverned: ${hotOff.maxDepth}", hotOff.maxDepth >= 4)
        assertEquals(CommitCadencePolicy.BACKPRESSURE_ENTER_DEPTH, hot.maxDepth)
        assertEquals(setOf(FAST_K * IV, (FAST_K + 1) * IV), hot.gaps.toSet())
        assertTrue(hot.commits.size < hotOff.commits.size)
        assertTrue(hot.lines.size >= 4)
        assertEquals(SLOW_LINE, hot.lines.first())
        assertTrue("strict alternation", hot.lines.zipWithNext().all { (a, b) -> a.startsWith("backpressure: depth=2") != b.startsWith("backpressure: depth=2") })
        // a decoder at the slow floor itself (3 200): the 3 680 interval appears, still bounded at 2
        val slow = drive(3_200, SLOW)
        assertEquals(CommitCadencePolicy.BACKPRESSURE_ENTER_DEPTH, slow.maxDepth)
        assertTrue(SLOW_K * IV in slow.gaps)
        // beyond it (4 000): the bound is gone, the growth is slower than ungoverned
        val beyond = drive(4_000, SLOW); val beyondOff = drive(4_000, null)
        assertTrue(beyond.maxDepth > CommitCadencePolicy.BACKPRESSURE_ENTER_DEPTH)
        assertTrue("${beyond.maxDepth} vs ${beyondOff.maxDepth}", beyond.maxDepth < beyondOff.maxDepth / 3)
    }
}
