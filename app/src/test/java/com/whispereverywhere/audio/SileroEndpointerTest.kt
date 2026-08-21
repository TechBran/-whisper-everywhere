package com.whispereverywhere.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

private const val B = EndpointerTuning.FRAME_BYTES

/** Non-zero: 0L is the endpointer's "no micro-pause remembered" sentinel. */
private const val BASE = 1_000_000L

/**
 * The smallest cost [FakeProbe] can charge that this endpointer counts as an OVERRUN.
 *
 * Spelled as "the budget plus one whole millisecond" rather than as `9`, because that is exactly
 * what the endpointer's comparison means: it truncates the elapsed nanoseconds to milliseconds and
 * tests STRICTLY above [EndpointerTuning.PROBE_BUDGET_MS], so anything under the next whole
 * millisecond — 8.5 ms included — is not an overrun.
 * `the_budget_is_compared_in_TRUNCATED_MILLISECONDS_not_microseconds` is where that lives as a
 * property; this constant only keeps the other tests from re-deciding it in a literal.
 */
private val OVERRUN_MS = EndpointerTuning.PROBE_BUDGET_MS + 1

/**
 * The injected probe. Records a COPY of every frame it is handed — the endpointer REUSES one
 * 1024-byte array, so retaining the reference would alias every "frame" onto the latest one.
 * `the_probe_is_handed_ONE_reused_array_which_is_why_the_fake_copies` proves that is a real hazard
 * and not a defensive habit. Optionally charges [costMs] of synthetic wall time against [clock] for
 * the cutout tests.
 */
private class FakeProbe(var next: Float = 0f) : (ByteArray) -> Float {
    val frames = mutableListOf<ByteArray>()
    var clock: FakeClock? = null
    var costMs: Long = 0L
    override fun invoke(frame: ByteArray): Float {
        frames += frame.copyOf()
        clock?.let { it.nowNs += costMs * 1_000_000L }
        return next
    }
}

/**
 * A hand-cranked `System.nanoTime`, advanced ONLY by the probe that charges it — [FakeProbe.costMs]
 * in whole milliseconds, or a bespoke probe lambda where a sub-millisecond cost is the point.
 *
 * Nothing else moves it, and that is the whole design: the budget measures time spent INSIDE one
 * probe call, so a clock that advanced with the audio stream would measure the wrong thing.
 */
private class FakeClock(var nowNs: Long = 0L) : () -> Long {
    override fun invoke(): Long = nowNs
}

/**
 * Drives one endpointer at the real 32 ms frame cadence, holding the clock between stretches.
 *
 * `t` IS this section's WALL clock, and for now the only one: the endpointer reads no wall clock of
 * its own — every wall time it knows comes in as `onFrame`'s `nowMs`, which is the whole point of
 * the "ONE clock" ruling in [SileroEndpointer]'s KDoc. Advancing `t` here is therefore the only way
 * wall time passes anywhere in this class.
 *
 * Task C7 added the SECOND clock, and it is not a contradiction: the `nanoClock` third constructor
 * parameter driving the probe's cost budget, with its own [FakeClock] helper beside this one. A
 * different clock in a different unit measuring a different thing — monotonic nanoseconds spent
 * inside one probe call, never wall time along the audio stream. Neither may be used for the
 * other's job, and this pump never touches the nanoClock: only a probe that charges it does. That
 * is why the budget's boundary is reachable through [Pump] as well as off it — the 32 ms
 * quantisation the four CONSTANT boundary tests below have to escape applies to `nowMs` alone.
 */
private class Pump(
    val ep: SileroEndpointer,
    val probe: FakeProbe,
    var t: Long = BASE,
) {
    var commits = 0
    var lastCommitMs = -1L

    /** Feeds [frames] complete frames of probability [p]. @return true if any of them committed. */
    fun run(p: Float, frames: Int): Boolean {
        probe.next = p
        var fired = false
        repeat(frames) {
            if (ep.onFrame(ByteArray(B), 0, t)) {
                fired = true
                commits++
                lastCommitMs = t
            }
            t += EndpointerTuning.FRAME_MS
        }
        return fired
    }
}

class SileroEndpointerTest {

    @Test fun an_exact_frame_reaches_the_probe_untouched() {
        val probe = FakeProbe()
        val ep = SileroEndpointer(probe = probe)
        assertFalse(ep.onFrame(ByteArray(B) { 3 }, 0, BASE))
        assertEquals(1, probe.frames.size)
        assertEquals(B, probe.frames[0].size)
        assertTrue(probe.frames[0].all { it == 3.toByte() })
    }

    @Test fun short_reads_are_reassembled_into_exact_512_sample_frames() {
        // AudioRecord.read() returns UP TO the buffer size and StreamingAudioRecorder forwards
        // buffer.copyOf(read) (StreamingAudioRecorder.kt:80,97); the 48 kHz decimator documents
        // "~1024" (PlaybackAudioCapturer.kt:62). One chunk = one frame is the common case, never
        // the contract — and a zero-padded short frame would poison the LSTM.
        //
        // The second chunk is sized RELATIVE to B so the pair always sums to exactly one frame:
        // 700 + 324 today, and still one whole frame if the Silero window ever changes. The
        // frame size itself is pinned absolutely, once, by EndpointerTuningTest.
        val probe = FakeProbe()
        val ep = SileroEndpointer(probe = probe)
        assertFalse(ep.onFrame(ByteArray(700) { 1 }, 0, BASE))
        assertEquals("a partial frame must never reach the probe", 0, probe.frames.size)
        assertFalse(ep.onFrame(ByteArray(B - 700) { 2 }, 0, BASE + EndpointerTuning.FRAME_MS))
        assertEquals(1, probe.frames.size)
        val f = probe.frames[0]
        assertEquals(B, f.size)
        assertTrue("bytes 0..699 come from chunk 1", (0 until 700).all { f[it] == 1.toByte() })
        assertTrue("bytes 700..1023 come from chunk 2", (700 until B).all { f[it] == 2.toByte() })
    }

    @Test fun one_chunk_carrying_several_frames_probes_each_of_them() {
        val probe = FakeProbe()
        val ep = SileroEndpointer(probe = probe)
        assertFalse(ep.onFrame(ByteArray(2 * B + 452), 0, BASE))
        assertEquals("2048 of 2500 bytes are two whole frames; 452 are retained", 2, probe.frames.size)
        assertFalse(ep.onFrame(ByteArray(B - 452), 0, BASE + EndpointerTuning.FRAME_MS))
        assertEquals("the leftover 452 carried, so 572 more bytes complete a third frame", 3, probe.frames.size)
        assertTrue(probe.frames.all { it.size == B })
    }

    @Test fun the_minus_one_sentinel_never_opens_the_gate() {
        // The guard in onProb is `p < 0f`, not `p == NO_VERDICT`: a probability is by definition
        // in [0, 1], so ANY negative is "no verdict", and an exact float comparison against a
        // value that crossed a JNI boundary is the wrong instrument. This assertion is what makes
        // the two equivalent — it fails if the sentinel is ever redefined to a non-negative value,
        // which would leave the guard silently unreachable.
        assertTrue(
            "the `p < 0f` guard in SileroEndpointer.onProb only covers NO_VERDICT while the " +
                "sentinel is negative",
            EndpointerTuning.NO_VERDICT < 0f,
        )
        val probe = FakeProbe()
        val ep = SileroEndpointer(probe = probe)
        val pump = Pump(ep, probe)
        assertFalse(pump.run(EndpointerTuning.NO_VERDICT, 200))
        assertFalse("a no-verdict frame is never silence and never speech", ep.hasPendingSpeech())
        assertEquals("the frames are still consumed and probed", 200, probe.frames.size)
    }

    @Test fun reset_drops_the_partial_frame_and_resets_the_native_probe() {
        // reset() is one of the five vadProbeReset sites (cap cut FloatingBubbleService.kt:1722,
        // switchSource :1819, onOpen :2224, stopRecording :2393 — all four verified against the
        // tree today — and this internal one). The partial frame goes with it: the next probe
        // frame must start on a boundary aligned with the fresh LSTM.
        var resets = 0
        val probe = FakeProbe()
        val ep = SileroEndpointer(
            probe = probe,
            probeReset = { resets++ },
        )
        assertFalse(ep.onFrame(ByteArray(900) { 5 }, 0, BASE))
        assertEquals(0, probe.frames.size)
        ep.reset()
        assertEquals(1, resets)
        assertFalse(ep.onFrame(ByteArray(B) { 6 }, 0, BASE + EndpointerTuning.FRAME_MS))
        assertEquals(1, probe.frames.size)
        assertTrue(
            "the 900 dropped bytes must not survive into the next frame",
            probe.frames[0].all { it == 6.toByte() },
        )
    }

    @Test fun an_empty_chunk_is_a_no_op() {
        val probe = FakeProbe()
        val ep = SileroEndpointer(probe = probe)
        assertFalse(ep.onFrame(ByteArray(0), 0, BASE))
        assertEquals(0, probe.frames.size)
    }

    /**
     * The supertype is declared FROM BIRTH, and this is the only test that checks it: every other
     * test in this class binds the concrete type and would survive `: Endpointer` being dropped
     * without noticing. The seam only ever sees the interface — `FloatingBubbleService` holds an
     * [Endpointer] and `EndpointerFactory` (Task D8) returns one — so a class that conforms only
     * at the end of a ten-task workstream has nine tasks' worth of code checked by nothing.
     */
    @Test fun it_wears_the_Endpointer_supertype_from_birth() {
        val probe = FakeProbe()
        val ep: Endpointer = SileroEndpointer(probe = probe)
        assertFalse("onFrame is reachable through the interface", ep.onFrame(ByteArray(B), 0, BASE))
        assertFalse("hasPendingSpeech is reachable through the interface", ep.hasPendingSpeech())
        ep.reset()
        assertEquals("and the frame really went through the accumulator", 1, probe.frames.size)
    }

    /**
     * The endpointer hands the SAME array to the probe every time.
     *
     * That is not an implementation detail a test may ignore, it is the reason [FakeProbe] copies:
     * a fake that retained the reference would alias every recorded "frame" onto the newest one,
     * and every content assertion in this class would silently become an assertion about the last
     * frame only. It is also the contract [Endpointer] states for the real probe — allocation-free
     * on the `onFrame` path, and "the probe must copy anything it retains".
     */
    @Test fun the_probe_is_handed_ONE_reused_array_which_is_why_the_fake_copies() {
        val kept = mutableListOf<ByteArray>() // references, deliberately NOT copies
        val aliasing = SileroEndpointer(probe = { f -> kept += f; 0f })
        aliasing.onFrame(ByteArray(B) { 1 }, 0, BASE)
        aliasing.onFrame(ByteArray(B) { 2 }, 0, BASE + EndpointerTuning.FRAME_MS)
        assertEquals(2, kept.size)
        assertSame(
            "one array for the life of the endpointer: no per-frame allocation on the audio thread",
            kept[0],
            kept[1],
        )
        assertTrue(
            "the reference kept from the FIRST frame now reads as the SECOND frame's bytes — " +
                "this is the aliasing FakeProbe.copyOf() exists to defeat",
            kept[0].all { it == 2.toByte() },
        )

        val probe = FakeProbe()
        val copying = SileroEndpointer(probe = probe)
        copying.onFrame(ByteArray(B) { 1 }, 0, BASE)
        copying.onFrame(ByteArray(B) { 2 }, 0, BASE + EndpointerTuning.FRAME_MS)
        assertTrue("frame 1 survives in the copying fake", probe.frames[0].all { it == 1.toByte() })
        assertTrue("frame 2 is its own array", probe.frames[1].all { it == 2.toByte() })
    }

    // ---------------------------------------------------------------------------------------
    // The Schmitt trigger (Task C3): the onset branch, the inert dead band, and the
    // uncommitted-buffer predicate. The silence branch, the pending end and the hangover are
    // Task C4's — every test below is written to keep stating the SAME property once they land.
    // ---------------------------------------------------------------------------------------

    @Test fun a_frame_below_ONSET_never_opens_the_gate() {
        val probe = FakeProbe()
        val ep = SileroEndpointer(probe = probe)
        val pump = Pump(ep, probe)
        assertFalse(pump.run(0.49f, 100))
        assertFalse("0.49 is under ONSET — 3.2 s of it is still not speech", ep.hasPendingSpeech())
    }

    @Test fun ONSET_opens_the_gate_and_MIN_SPEECH_MS_latches_pending_speech() {
        val probe = FakeProbe()
        val ep = SileroEndpointer(probe = probe)
        val pump = Pump(ep, probe)
        // Frames land at BASE + 32k. After 10 frames the newest is at +288 ms: under 300.
        assertFalse(pump.run(EndpointerTuning.ONSET_THRESHOLD, 10))
        assertFalse("288 ms of speech is under MIN_SPEECH_MS", ep.hasPendingSpeech())
        assertFalse(pump.run(EndpointerTuning.ONSET_THRESHOLD, 1))
        assertTrue("the 11th frame is 320 ms in — the latch must set", ep.hasPendingSpeech())
    }

    /**
     * The 32 ms grid steps 288 → 320, so it can never land ON [EndpointerTuning.MIN_SPEECH_MS] and
     * `>=` versus `>` is invisible to every [Pump]-driven test in this class. The boundary is
     * nonetheless REACHABLE in production: `nowMs` is the wall clock stamped on the CHUNK, and a
     * chunk is whatever `AudioRecord.read` returned — bursts and short reads put frames at
     * arbitrary milliseconds, which is the same premise the class KDoc's burst-delivery paragraph
     * rests on. So this one test drives `onFrame` directly rather than through the pump.
     *
     * The native comparison at `whisper.cpp:5337` is STRICT (`>`) and Task C4 ports it strictly —
     * but it governs a DIFFERENT decision: whether a FINISHED segment is emitted. This is a running
     * latch on an open gate. The asymmetry (strict commit gate, inclusive buffer latch) is
     * deliberate and errs toward "there IS speech in the buffer".
     */
    @Test fun the_latch_fires_at_exactly_MIN_SPEECH_MS() {
        val probe = FakeProbe()
        val ep = SileroEndpointer(probe = probe)
        probe.next = EndpointerTuning.ONSET_THRESHOLD
        assertFalse(ep.onFrame(ByteArray(B), 0, BASE))
        assertFalse("the frame that opens the gate is 0 ms in", ep.hasPendingSpeech())
        assertFalse(ep.onFrame(ByteArray(B), 0, BASE + EndpointerTuning.MIN_SPEECH_MS - 1))
        assertFalse("299 ms is not yet MIN_SPEECH_MS", ep.hasPendingSpeech())
        assertFalse(ep.onFrame(ByteArray(B), 0, BASE + EndpointerTuning.MIN_SPEECH_MS))
        assertTrue("the boundary is inclusive: 300 ms IS MIN_SPEECH_MS", ep.hasPendingSpeech())
    }

    @Test fun the_dead_band_alone_never_opens_the_gate() {
        val probe = FakeProbe()
        val ep = SileroEndpointer(probe = probe)
        val pump = Pump(ep, probe)
        assertFalse(pump.run(0.42f, 100))
        assertFalse(ep.hasPendingSpeech())
    }

    @Test fun dead_band_frames_do_not_close_an_open_gate_or_move_the_speech_start() {
        val probe = FakeProbe()
        val ep = SileroEndpointer(probe = probe)
        val pump = Pump(ep, probe)
        pump.run(0.9f, 5)                     // gate opens at BASE; 128 ms in — no latch yet
        assertFalse(ep.hasPendingSpeech())
        assertFalse(pump.run(0.42f, 20))      // 640 ms of dead band: inert, gate stays open
        assertFalse("a dead-band frame never runs the latch itself", ep.hasPendingSpeech())
        assertFalse(pump.run(0.9f, 1))        // the frame at BASE+800: 800 ms since speechStart
        assertTrue("speechStart survived the dead band untouched", ep.hasPendingSpeech())
    }

    @Test fun no_verdict_frames_do_not_close_an_open_gate() {
        val probe = FakeProbe()
        val ep = SileroEndpointer(probe = probe)
        val pump = Pump(ep, probe)
        pump.run(0.9f, 11)
        assertTrue(ep.hasPendingSpeech())
        assertFalse(pump.run(EndpointerTuning.NO_VERDICT, 100))
        assertTrue("a no-verdict frame keeps the previous state exactly", ep.hasPendingSpeech())
    }

    /**
     * `hasPendingSpeech()` describes the UNCOMMITTED BUFFER, not the gate — the semantics the
     * LOCAL-silence re-arm at `FloatingBubbleService.kt:1716` reads, and the one thing Tasks C4
     * and C5 must not quietly narrow back to "speech in the current frame". Speech that has been
     * heard and not yet committed stays pending THROUGH silence, because that audio really is
     * still sitting in the caller's buffer waiting to be sent.
     *
     * The silent stretch is deliberately SHORTER than [EndpointerTuning.HANGOVER_MS] (320 ms of
     * the 500), so this test states exactly one property today and the SAME one property after
     * C4 gives a long silence the power to commit. A 6 s stretch here would start failing the
     * moment the hangover exists, and would then be "fixed" by weakening the assertion.
     */
    @Test fun silence_below_RELEASE_does_not_clear_the_uncommitted_buffer() {
        val probe = FakeProbe()
        val ep = SileroEndpointer(probe = probe)
        val pump = Pump(ep, probe)
        pump.run(0.9f, 11)
        assertTrue(ep.hasPendingSpeech())
        assertFalse(pump.run(0.0f, 10))
        assertTrue(
            "a silent frame is not a commit — the speech already heard is still uncommitted",
            ep.hasPendingSpeech(),
        )
    }

    @Test fun reset_clears_pending_speech() {
        val probe = FakeProbe()
        val ep = SileroEndpointer(probe = probe)
        val pump = Pump(ep, probe)
        pump.run(0.9f, 11)
        assertTrue(ep.hasPendingSpeech())
        ep.reset()
        assertFalse(ep.hasPendingSpeech())
        // The GATE has to close with it, not just the latch: if `speaking` survived a reset, the
        // stale speechStartMs would make the very next onset frame latch instantly — a whole
        // segment's worth of pending speech conjured from one frame.
        assertFalse(pump.run(0.9f, 1))
        assertFalse(
            "reset closes the gate too, so the next onset frame starts a fresh clock",
            ep.hasPendingSpeech(),
        )
    }

    // ---------------------------------------------------------------------------------------
    // The hangover hard timer, min-speech and the commit (Task C4): the silence branch stamps
    // the pending end ONCE, the hangover elapses on WALL TIME from it, and only a frame at or
    // above ONSET clears it. This is where `onFrame` first returns true.
    //
    // The rule the block above states in the other direction, kept: a test asserting a
    // NON-COMMIT property uses audio SHORTER than HANGOVER_MS, because a longer clip starts
    // failing once commits exist and the tempting fix is to weaken the assertion. The tests
    // below deliberately run PAST the hangover — the commit decision is exactly what they are
    // about.
    // ---------------------------------------------------------------------------------------

    @Test fun the_hangover_cuts_at_exactly_500ms_of_trailing_silence() {
        val probe = FakeProbe()
        val ep = SileroEndpointer(probe = probe)
        val pump = Pump(ep, probe)
        pump.run(0.9f, 20)                       // speech BASE..BASE+608, gate open at BASE
        assertFalse("16 silent frames is 480 ms — under the hangover", pump.run(0.1f, 16))
        assertTrue("the 17th silent frame is 512 ms in", pump.run(0.1f, 1))
        assertEquals(1, pump.commits)
        assertEquals(BASE + 640 + 512, pump.lastCommitMs)
    }

    /**
     * The same off-grid problem as [the_latch_fires_at_exactly_MIN_SPEECH_MS], one threshold later.
     * [Pump] steps the elapsed silence 480 -> 512 ms, so it can never land ON
     * [EndpointerTuning.HANGOVER_MS] and `<` versus `<=` is invisible to every pump-driven test in
     * this class — a mutation battery proved it, by surviving one. The boundary is REACHABLE in
     * production for the reason the class KDoc gives: `nowMs` is stamped on the CHUNK, and bursts
     * and short reads put frames at arbitrary milliseconds. So this test drives `onFrame` directly
     * too.
     *
     * Unlike the MIN_SPEECH_MS latch, this boundary is NOT a JVM-side choice. Native continues
     * while `(curr_sample - temp_end) < min_silence_samples` (`whisper.cpp:5333`) and cuts
     * otherwise, so exactly HANGOVER_MS CUTS. Inclusive here means "ported", not "decided".
     */
    @Test fun the_hangover_fires_at_exactly_HANGOVER_MS() {
        val probe = FakeProbe()
        val ep = SileroEndpointer(probe = probe)
        // Comfortably clear of the min-speech gate, so the only decision under test is the
        // hangover's own boundary and not the commit gate sitting behind it.
        val speech = EndpointerTuning.MIN_SPEECH_MS + 100
        probe.next = 0.9f
        assertFalse(ep.onFrame(ByteArray(B), 0, BASE))
        assertFalse(ep.onFrame(ByteArray(B), 0, BASE + speech))
        probe.next = 0.1f
        assertFalse(ep.onFrame(ByteArray(B), 0, BASE + speech))   // pending end == BASE + speech
        assertFalse(
            "one millisecond under the hangover is not the hangover",
            ep.onFrame(ByteArray(B), 0, BASE + speech + EndpointerTuning.HANGOVER_MS - 1),
        )
        assertTrue(
            "the boundary is inclusive: HANGOVER_MS of silence IS the hangover",
            ep.onFrame(ByteArray(B), 0, BASE + speech + EndpointerTuning.HANGOVER_MS),
        )
    }

    @Test fun dead_band_frames_do_not_stall_the_hangover_hard_timer() {
        // THE point of the dead-band port: the clock runs on wall time from the pending end, so a
        // long mumble in the 0.35-0.49 band cannot hold the gate open. Only a frame at or above
        // ONSET resets it.
        val probe = FakeProbe()
        val ep = SileroEndpointer(probe = probe)
        val pump = Pump(ep, probe)
        pump.run(0.9f, 20)                       // gate opens at BASE, speech ends at BASE+640
        assertFalse(pump.run(0.1f, 1))           // pending end stamped at BASE+640
        assertFalse("dead-band frames never commit themselves", pump.run(0.42f, 10))
        assertFalse(pump.run(0.1f, 5))           // BASE+992..1120 -> 352..480 ms elapsed
        assertTrue("the timer counted the dead band", pump.run(0.1f, 1))
        assertEquals(BASE + 640 + 512, pump.lastCommitMs)
    }

    @Test fun a_frame_back_above_ONSET_resets_the_hangover_clock() {
        val probe = FakeProbe()
        val ep = SileroEndpointer(probe = probe)
        val pump = Pump(ep, probe)
        pump.run(0.9f, 20)                       // gate opens at BASE
        assertFalse(pump.run(0.1f, 10))          // pending end at BASE+640, 288 ms elapsed
        assertFalse(pump.run(0.9f, 1))           // BASE+960: back to speech, clock cleared
        assertFalse("the clock restarts from BASE+992, not BASE+640", pump.run(0.1f, 16))
        assertTrue(pump.run(0.1f, 1))
        assertEquals(BASE + 992 + 512, pump.lastCommitMs)
    }

    @Test fun no_verdict_frames_do_not_short_circuit_the_hangover() {
        val probe = FakeProbe()
        val ep = SileroEndpointer(probe = probe)
        val pump = Pump(ep, probe)
        pump.run(0.9f, 20)
        assertFalse(pump.run(EndpointerTuning.NO_VERDICT, 30))   // 960 ms of nothing at all
        assertFalse("the pending end is stamped by the first REAL silence", pump.run(0.1f, 16))
        assertTrue(pump.run(0.1f, 1))
        assertEquals(BASE + 1600 + 512, pump.lastCommitMs)
    }

    @Test fun a_burst_under_MIN_SPEECH_MS_is_discarded_without_a_commit() {
        // whisper.cpp:5337 — too short to be an utterance: drop it and re-arm, no segment emitted.
        // The native filter would drop it before whisper_full anyway; agreeing here saves the call.
        val probe = FakeProbe()
        val ep = SileroEndpointer(probe = probe)
        val pump = Pump(ep, probe)
        pump.run(0.9f, 8)                        // 256 ms of speech
        assertFalse("256 ms is under MIN_SPEECH_MS", pump.run(0.1f, 40))
        assertEquals(0, pump.commits)
        assertFalse(ep.hasPendingSpeech())
    }

    @Test fun exactly_MIN_SPEECH_MS_does_not_cut_but_does_count_as_pending_speech() {
        // The native gate is strict (`>`), the pending-speech latch is inclusive (`>=`), and they
        // differ only at exactly 300 ms. The asymmetry is deliberate and errs toward "there IS
        // speech in the buffer", which is the safe direction for the cap-policy branch above it.
        var resets = 0
        val probe = FakeProbe()
        val ep = SileroEndpointer(
            probe = probe,
            probeReset = { resets++ },
        )
        val minSpeech = EndpointerTuning.MIN_SPEECH_MS
        probe.next = 0.9f
        assertFalse(ep.onFrame(ByteArray(B), 0, BASE))
        assertFalse(ep.onFrame(ByteArray(B), 0, BASE + minSpeech))
        assertTrue(ep.hasPendingSpeech())
        probe.next = 0.1f
        // The pending end lands exactly MIN_SPEECH_MS after the speech start.
        assertFalse(ep.onFrame(ByteArray(B), 0, BASE + minSpeech))
        // Comfortably past the hangover, so the ONLY reason this frame does not cut is the strict
        // commit gate. Spelled from the constants: a retune must not leave this test asserting a
        // boundary its own failure message no longer describes.
        assertFalse(
            ep.onFrame(ByteArray(B), 0, BASE + minSpeech + EndpointerTuning.HANGOVER_MS + 100),
        )
        assertEquals("no commit at exactly MIN_SPEECH_MS", 0, resets)
        assertTrue("but the buffer is not empty", ep.hasPendingSpeech())
    }

    /**
     * The second utterance is measured from ITS OWN start.
     *
     * This property could not be stated before C4: nothing in this class ever committed, so there
     * was no "next" utterance for a stale `speechStartMs` to poison. C4 creates that path, so the
     * assertion lands with it. The burst here is 256 ms — under [EndpointerTuning.MIN_SPEECH_MS]
     * on its own clock, and therefore correctly discarded — but 1440 ms measured from the FIRST
     * utterance's start, which would commit. A commit that forgot to re-anchor the clock is not a
     * subtle mistake in the length it reports; it is an extra cut in the wrong place.
     */
    @Test fun a_second_utterance_is_measured_from_its_OWN_start() {
        val probe = FakeProbe()
        val ep = SileroEndpointer(probe = probe)
        val pump = Pump(ep, probe)
        pump.run(0.9f, 20)
        assertFalse(pump.run(0.1f, 16))
        assertTrue("the first utterance commits", pump.run(0.1f, 1))
        pump.run(0.9f, 8)                        // 256 ms on the new clock, 1440 on the old one
        assertFalse(
            "a stale speech start would measure this burst from the FIRST utterance and cut",
            pump.run(0.1f, 40),
        )
        assertEquals("the short second burst is discarded, not committed", 1, pump.commits)
    }

    @Test fun a_commit_resets_the_probe_and_clears_the_accumulator() {
        var resets = 0
        val probe = FakeProbe()
        val ep = SileroEndpointer(
            probe = probe,
            probeReset = { resets++ },
        )
        val pump = Pump(ep, probe)
        pump.run(0.9f, 20)
        assertFalse(pump.run(0.1f, 16))
        // 452 bytes of the NEXT frame are already in the accumulator when the cut fires.
        probe.next = 0.1f
        assertFalse(ep.onFrame(ByteArray(452) { 7 }, 0, pump.t))
        val before = probe.frames.size
        assertTrue(ep.onFrame(ByteArray(B) { 8 }, 0, pump.t))
        assertEquals(
            "the rest of the committing chunk is dropped, not probed",
            before + 1,
            probe.frames.size,
        )
        assertEquals(1, resets)
        assertFalse(ep.hasPendingSpeech())
        assertFalse(ep.onFrame(ByteArray(B) { 9 }, 0, pump.t + 32))
        assertTrue(
            "the accumulator restarts on a boundary aligned with the fresh LSTM",
            probe.frames.last().all { it == 9.toByte() },
        )
    }

    // ---------------------------------------------------------------------------------------
    // The micro-pause memory (Task C5): the most recent dip below RELEASE that OUTLIVED
    // MICRO_PAUSE_MS is remembered as a real cut point for the 15 s wall cap
    // (native prev_end, whisper.cpp:5328-5330).
    //
    // It is BUFFER knowledge, not gate state, and the tests below pin that distinction from both
    // sides: it survives a re-onset and a discarded short burst, and only a commit or a reset
    // takes it away. The discard case is a deliberate divergence from whisper.cpp:5341 and the
    // test that states it is the one a "tidy-up" refactor into closeGate() would break.
    // ---------------------------------------------------------------------------------------

    @Test fun no_micro_pause_is_offered_until_a_dip_outlives_98ms() {
        val probe = FakeProbe()
        val ep = SileroEndpointer(probe = probe)
        val pump = Pump(ep, probe)
        pump.run(0.9f, 20)
        assertEquals(
            "continuous speech has no remembered pause",
            Endpointer.NO_CUT_POINT,
            ep.pendingCutPointMs(),
        )
        pump.run(0.1f, 1)                    // dip starts at BASE+640, 0 ms old
        assertEquals(Endpointer.NO_CUT_POINT, ep.pendingCutPointMs())
        pump.run(0.1f, 3)                    // 32, 64, 96 ms old — 96 is not > 98
        assertEquals(
            "the 98 ms floor is exclusive, as in whisper.cpp:5328",
            Endpointer.NO_CUT_POINT,
            ep.pendingCutPointMs(),
        )
        pump.run(0.1f, 1)                    // 128 ms old
        assertEquals(BASE + 640, ep.pendingCutPointMs())
    }

    /**
     * The fourth instance of this class's off-grid pattern, and the first one PREDICTED rather
     * than discovered by a surviving mutation: [Pump] steps a dip's age 96 -> 128 ms, so it can
     * never land ON [EndpointerTuning.MICRO_PAUSE_MS] and `>` versus `>=` is invisible to every
     * pump-driven test in this class — including all five the plan wrote for this task. The
     * boundary is REACHABLE in production for the reason the class KDoc gives: `nowMs` is stamped
     * on the CHUNK, and bursts and short reads put frames at arbitrary milliseconds. So this test
     * drives `onFrame` directly, as [the_latch_fires_at_exactly_MIN_SPEECH_MS] and
     * [the_hangover_fires_at_exactly_HANGOVER_MS] do.
     *
     * Like the hangover's and unlike the buffer latch's, this boundary is NOT a JVM-side choice.
     * Native compares `(curr_sample - temp_end) > min_silence_samples_at_max_speech` STRICTLY
     * (`whisper.cpp:5328`), so exactly MICRO_PAUSE_MS does NOT promote. Exclusive here means
     * "ported", not "decided" — and it is the same strictness [EndpointerTuning.MICRO_PAUSE_MS]'s
     * own KDoc reasons from to reach its 5th-frame truth.
     */
    @Test fun the_micro_pause_floor_is_exclusive_at_exactly_MICRO_PAUSE_MS() {
        val probe = FakeProbe()
        val ep = SileroEndpointer(probe = probe)
        // The dip opens with the gate up and a long enough utterance behind it to be committable,
        // and ends far inside HANGOVER_MS: the only decision under test is the micro-pause floor.
        val dipStart = BASE + EndpointerTuning.MIN_SPEECH_MS + 100
        probe.next = 0.9f
        assertFalse(ep.onFrame(ByteArray(B), 0, BASE))
        probe.next = 0.1f
        assertFalse(ep.onFrame(ByteArray(B), 0, dipStart))         // pending end == dipStart
        assertEquals(
            "the frame that starts a dip is 0 ms old, and 0 is not more than 98",
            Endpointer.NO_CUT_POINT,
            ep.pendingCutPointMs(),
        )
        assertFalse(ep.onFrame(ByteArray(B), 0, dipStart + EndpointerTuning.MICRO_PAUSE_MS))
        assertEquals(
            "the floor is exclusive: exactly MICRO_PAUSE_MS is not MORE than MICRO_PAUSE_MS",
            Endpointer.NO_CUT_POINT,
            ep.pendingCutPointMs(),
        )
        assertFalse(ep.onFrame(ByteArray(B), 0, dipStart + EndpointerTuning.MICRO_PAUSE_MS + 1))
        assertEquals(
            "one millisecond past the floor promotes the dip's START, not the current frame",
            dipStart,
            ep.pendingCutPointMs(),
        )
    }

    @Test fun the_micro_pause_survives_a_re_onset_within_the_same_stretch() {
        // This is the whole point: during a 15 s continuous stretch the endpointer keeps the most
        // recent real boundary, so a cap cut lands there instead of mid-word. no_context = true
        // makes a mid-word cut permanently unrepairable.
        val probe = FakeProbe()
        val ep = SileroEndpointer(probe = probe)
        val pump = Pump(ep, probe)
        pump.run(0.9f, 20)
        pump.run(0.1f, 5)                    // dip at BASE+640 remembered
        assertEquals(BASE + 640, ep.pendingCutPointMs())
        pump.run(0.9f, 10)                   // speech resumes: pending end clears, memory does not
        assertEquals(BASE + 640, ep.pendingCutPointMs())
        // The offer only ever moves FORWARD, never back to "none". A newer dip that has not yet
        // outlived the floor is not a replacement for the older one, and must not be allowed to
        // erase it either: a cap cut landing in these four frames still needs the best boundary
        // known. Stated because it is invisible from the far side of the dip — where both a
        // correct implementation and one that clears on every dip's first frame read BASE+1120.
        pump.run(0.1f, 4)                    // the new dip is 0/32/64/96 ms old — too young
        assertEquals(
            "a dip too young to qualify must not erase the older boundary it cannot yet replace",
            BASE + 640,
            ep.pendingCutPointMs(),
        )
        pump.run(0.1f, 1)                    // 128 ms: the NEWER dip at BASE+1120 replaces it
        assertEquals(BASE + 1120, ep.pendingCutPointMs())
    }

    @Test fun a_commit_clears_the_micro_pause_memory() {
        val probe = FakeProbe()
        val ep = SileroEndpointer(probe = probe)
        val pump = Pump(ep, probe)
        pump.run(0.9f, 20)
        // The offer is shown to EXIST before the commit is asked to consume it. Without this the
        // test asserts an absence that a never-offering implementation satisfies for free — it
        // passed vacuously against the interface default at RED, which is exactly the shape of
        // test that reports success while the feature is missing.
        pump.run(0.1f, 5)
        assertEquals(BASE + 640, ep.pendingCutPointMs())
        assertTrue(pump.run(0.1f, 12))       // the 17th silent frame overall: the hangover cuts
        assertEquals(
            "the remembered pause was consumed by the cut",
            Endpointer.NO_CUT_POINT,
            ep.pendingCutPointMs(),
        )
    }

    @Test fun reset_clears_the_micro_pause_memory() {
        val probe = FakeProbe()
        val ep = SileroEndpointer(probe = probe)
        val pump = Pump(ep, probe)
        pump.run(0.9f, 20)
        pump.run(0.1f, 5)
        assertEquals(BASE + 640, ep.pendingCutPointMs())
        ep.reset()
        assertEquals(Endpointer.NO_CUT_POINT, ep.pendingCutPointMs())
    }

    @Test fun a_discarded_short_burst_keeps_the_remembered_pause() {
        // Deliberate deviation from whisper.cpp:5341, which clears prev_end here as bookkeeping for
        // its own max-speech split. We keep it: this field exists ONLY to give the wall cap a real
        // cut point, and a 200 ms cough after a good pause must not erase it.
        val probe = FakeProbe()
        val ep = SileroEndpointer(probe = probe)
        val pump = Pump(ep, probe)
        pump.run(0.9f, 20)
        assertTrue(pump.run(0.1f, 17))       // commit at BASE+1152; t is now BASE+1184
        pump.run(0.9f, 5)                    // 160 ms burst — under MIN_SPEECH_MS
        assertFalse(pump.run(0.1f, 17))      // discarded, no commit
        assertEquals(1, pump.commits)
        assertEquals(BASE + 1344, ep.pendingCutPointMs())
        // And it must survive the silence AFTER the discard, which is the half that actually
        // happens in production: the cough ends, the gate shuts, and the speaker stays quiet until
        // the 15 s cap fires. Every frame in that stretch takes the `!speaking` early return, so
        // an implementation that cleared the offer there would pass the assertion above and still
        // hand the cap nothing at the only moment the cap ever asks.
        pump.run(0.1f, 20)
        assertEquals(
            "silence with the gate SHUT must not erase the offer either",
            BASE + 1344,
            ep.pendingCutPointMs(),
        )
    }

    // ---------------------------------------------------------------------------------------
    // The per-tier commit governor (Task C6): an endpoint that lands INSIDE the session's cadence
    // floor is MERGED rather than committed — the gate closes, the audio stays pending, and the
    // next real pause is judged afresh. The session's FIRST endpoint is never merged, so first
    // text stays fast on every tier and the governor bounds only the steady state.
    //
    // The floor arrives per SESSION through onSessionStart(nowMs, minCommitIntervalMs), and every
    // call below NAMES both arguments. They are two same-typed Longs whose order is pinned by
    // nothing — Task D2's M22 mutation swapped them and survived its whole suite — so a positional
    // call here would be one silent transposition away from anchoring a session on 6000 and pacing
    // it at a wall-clock instant.
    // ---------------------------------------------------------------------------------------

    @Test fun the_sessions_first_endpoint_is_never_merged() {
        // First text fast, on every tier. The governor bounds the STEADY state; one early commit is
        // exactly what FIRST_SEGMENT_WALL_MS exists to buy, and this beats it to the punch.
        val probe = FakeProbe()
        val ep = SileroEndpointer(probe = probe)
        val pump = Pump(ep, probe)
        // 8000L is CommitCadencePolicy.MIN_COMMIT_INTERVAL_LARGE_MS, quoted rather than imported:
        // Workstream C compiles without the service package (Task D3 owns that object).
        ep.onSessionStart(nowMs = BASE, minCommitIntervalMs = 8_000L)
        pump.run(0.9f, 20)
        assertTrue(pump.run(0.1f, 17))
        assertEquals(1, pump.commits)
    }

    @Test fun pro_merges_an_utterance_that_endpoints_inside_1200ms() {
        val probe = FakeProbe()
        val ep = SileroEndpointer(probe = probe)
        val pump = Pump(ep, probe)
        // 1200L is CommitCadencePolicy.MIN_COMMIT_INTERVAL_FAST_MS (pro), quoted not imported.
        ep.onSessionStart(nowMs = BASE, minCommitIntervalMs = 1_200L)
        pump.run(0.9f, 20)
        assertTrue(pump.run(0.1f, 17))                 // commit 1 at BASE+1152
        pump.run(0.9f, 11)                             // utterance 2: BASE+1184..1504
        assertFalse("endpoint at +896 ms is inside the 1200 ms interval", pump.run(0.1f, 17))
        assertEquals(1, pump.commits)
        assertTrue("the merged audio is still uncommitted", ep.hasPendingSpeech())
        assertEquals(
            "the merged endpoint becomes the best known cut point",
            BASE + 1536,
            ep.pendingCutPointMs(),
        )
        pump.run(0.9f, 11)                             // utterance 3: BASE+2080..2400
        assertTrue("endpoint at +1792 ms clears the interval", pump.run(0.1f, 17))
        assertEquals(2, pump.commits)
        assertEquals(BASE + 2944, pump.lastCommitMs)
    }

    @Test fun multi_paces_three_utterances_into_one_commit() {
        val probe = FakeProbe()
        val ep = SileroEndpointer(probe = probe)
        val pump = Pump(ep, probe)
        // 6000L is CommitCadencePolicy.MIN_COMMIT_INTERVAL_MULTI_MS, quoted not imported.
        ep.onSessionStart(nowMs = BASE, minCommitIntervalMs = 6_000L)
        pump.run(0.9f, 20)
        assertTrue(pump.run(0.1f, 17))                 // commit 1 at BASE+1152
        pump.run(0.9f, 11); assertFalse(pump.run(0.1f, 17))
        pump.run(0.9f, 11); assertFalse(pump.run(0.1f, 17))
        assertEquals(
            "6 s has not elapsed: the endpointer still cuts, it just merges",
            1,
            pump.commits,
        )
        assertTrue(ep.hasPendingSpeech())
        // And the merge really CLOSES the gate. The governor is a floor on commits, NOT a timer
        // that fires when it expires: six seconds after the commit the interval has elapsed, but
        // with no new speech there is no new endpoint to take. A merge that returned false without
        // closing the gate would leave the pending end and the old speech start standing, and the
        // first frame past the floor would cut HERE — mid-silence, on a boundary already judged.
        assertFalse("an elapsed interval is not itself a cut", pump.run(0.1f, 200))
        assertEquals(1, pump.commits)
    }

    /**
     * The interval boundary — and, unlike this class's four other boundary tests, it is REACHABLE
     * through [Pump].
     *
     * The difference is structural rather than a change of heart. [EndpointerTuning.MIN_SPEECH_MS],
     * [EndpointerTuning.HANGOVER_MS] and [EndpointerTuning.MICRO_PAUSE_MS] are CONSTANTS, and none
     * of 300 / 500 / 98 is a multiple of the 32 ms frame, so no pump-driven test can land on them;
     * each has its own direct-`onFrame` test and those four stay exactly where they are. The
     * cadence floor is a PARAMETER the caller chooses per session, so a test may simply choose one
     * the grid hits. 896 ms is where this fixture's second endpoint falls: commit 1 at BASE+1152,
     * endpoint 2 at BASE+2048.
     *
     * The guard is `nowMs - lastCommitMs < minCommitIntervalMs`, so exactly the interval has
     * ELAPSED and commits, and one millisecond more of floor merges the same endpoint. Both sides
     * are stated because `<` versus `<=` is invisible from either one alone.
     */
    @Test fun exactly_the_interval_commits_and_one_millisecond_more_merges() {
        fun secondEndpointUnder(intervalMs: Long): Pump {
            val probe = FakeProbe()
            val ep = SileroEndpointer(probe = probe)
            val pump = Pump(ep, probe)
            ep.onSessionStart(nowMs = BASE, minCommitIntervalMs = intervalMs)
            pump.run(0.9f, 20)
            assertTrue("commit 1 lands at BASE+1152", pump.run(0.1f, 17))
            pump.run(0.9f, 11)
            pump.run(0.1f, 17)                         // endpoint 2 at BASE+2048: exactly +896 ms
            return pump
        }
        val elapsed = secondEndpointUnder(896L)
        assertEquals(
            "exactly the interval has ELAPSED — the endpoint is not INSIDE the window",
            2,
            elapsed.commits,
        )
        assertEquals(BASE + 2048, elapsed.lastCommitMs)
        val inside = secondEndpointUnder(897L)
        assertEquals(
            "one millisecond more of floor and the same endpoint merges",
            1,
            inside.commits,
        )
    }

    @Test fun reset_anchors_the_governor_on_the_last_frame_seen() {
        // The Endpointer interface carries no clock into reset(), so an EXTERNAL commit (cap cut,
        // switchSource, stop) re-anchors on the most recent frame timestamp — within one 32 ms
        // chunk of the real instant, the same tolerance SegmentCapPolicy documents for its own
        // cross-thread writes.
        val probe = FakeProbe()
        val ep = SileroEndpointer(probe = probe)
        val pump = Pump(ep, probe)
        // PRO's 1200 ms floor, deliberately, and not multi's 6000: the endpoint below lands 896 ms
        // after the cap cut but 1504 ms after the session opened, so only a floor BETWEEN those
        // two can tell the two anchors apart. Under 6000 both anchors merge and the test would
        // pass against an implementation that re-anchored on nothing at all.
        ep.onSessionStart(nowMs = BASE, minCommitIntervalMs = 1_200L)
        pump.run(0.9f, 20)                             // last frame at BASE+608
        ep.reset()                                     // the wall-cap cut at FBS.kt:1722
        assertFalse(ep.hasPendingSpeech())
        pump.run(0.9f, 11)
        assertFalse(
            "896 ms after the cap cut is inside pro's 1200 — measured from the SESSION it would " +
                "be 1504 and would cut",
            pump.run(0.1f, 17),
        )
        assertEquals(0, pump.commits)
        // And a reset arriving BEFORE this session's first frame anchors on the SESSION, not on
        // whatever frame the previous session left behind: [onSessionStart] stamps `lastFrameMs`
        // itself. Same session hygiene as the micro-pause memory it clears through
        // clearForNextSegment — a minute-old timestamp surviving a session boundary would clear
        // any floor instantly and hand the new session a second free cut it was never promised.
        pump.t = BASE + 60_000
        ep.onSessionStart(nowMs = BASE + 60_000, minCommitIntervalMs = 1_200L)
        ep.reset()                                     // switchSource, before a single frame
        pump.run(0.9f, 11)
        assertFalse(
            "the anchor is this session's open, not the minute-old frame the last one left",
            pump.run(0.1f, 17),
        )
        assertEquals(0, pump.commits)
    }

    @Test fun onSessionStart_re_arms_the_first_free_cut() {
        val probe = FakeProbe()
        val ep = SileroEndpointer(probe = probe)
        val pump = Pump(ep, probe)
        ep.onSessionStart(nowMs = BASE, minCommitIntervalMs = 6_000L)   // multi's paced floor
        pump.run(0.9f, 20)
        assertTrue(pump.run(0.1f, 17))                 // commit 1 at BASE+1152
        // The governor is shown ENGAGED before the re-arm is asked to lift it. Without this the
        // test asserts a commit that a never-merging endpointer delivers for free — it passed
        // vacuously at RED against the interface's defaulted no-op, which is the exact shape of
        // test that reports success while the feature is missing.
        pump.run(0.9f, 11)
        assertFalse("still inside multi's 6 s", pump.run(0.1f, 17))
        assertTrue(ep.hasPendingSpeech())
        assertEquals(BASE + 1536, ep.pendingCutPointMs())
        pump.t = BASE + 3_000
        ep.onSessionStart(nowMs = BASE + 3_000, minCommitIntervalMs = 6_000L)
        // The session boundary is also the last place the previous session's audio is spoken for.
        // onSessionStart clears the buffer bookkeeping through the same clearForNextSegment a
        // commit uses, so the merged audio the last session left does not read as pending here,
        // and the wall cap cannot be offered a cut point measured on it. Asserted after showing
        // both were SET two lines above: an absence nothing ever created is not a clear.
        assertFalse("a new session starts with an empty buffer", ep.hasPendingSpeech())
        assertEquals(
            "and with no cut point measured on the last session's audio",
            Endpointer.NO_CUT_POINT,
            ep.pendingCutPointMs(),
        )
        pump.run(0.9f, 11)
        assertTrue("a new session's first cut is free again", pump.run(0.1f, 17))
        assertEquals(2, pump.commits)
    }

    /**
     * The floor BEFORE any session start is the conservative LARGE one, not a tier's own.
     *
     * "Unmeasured means assume the expensive end" — the same rule that gives extreme and ultra
     * their row in `CommitCadencePolicy`. The only way to reach this state is a frame arriving
     * before `onOpen` has run, and the cost of guessing low there is a commit rate the installed
     * tier cannot pay for; the cost of guessing high is one late cut on audio nobody has asked for
     * yet. The two assertions BRACKET the value rather than reading the field: 6976 ms still
     * merges (so the default is above multi's 6000, above the cloud batch's 3000 and far above
     * pro's 1200) and 8128 ms commits (so it is not something merely enormous).
     */
    @Test fun before_any_session_start_the_floor_is_the_conservative_8000() {
        val probe = FakeProbe()
        val ep = SileroEndpointer(probe = probe)
        val pump = Pump(ep, probe)
        pump.run(0.9f, 20)
        assertTrue("the first cut is free here too", pump.run(0.1f, 17))   // commit 1, BASE+1152
        pump.run(0.1f, 190)                            // quiet: the gate is shut, nothing happens
        pump.run(0.9f, 11)
        assertFalse(
            "6976 ms after the commit is still inside the LARGE floor",
            pump.run(0.1f, 17),
        )
        pump.run(0.1f, 8)
        pump.run(0.9f, 11)
        assertTrue("8128 ms clears it", pump.run(0.1f, 17))
        assertEquals(2, pump.commits)
    }

    // ---------------------------------------------------------------------------------------
    // The latched slow-probe cutout (Task C7): the THIRD fallback tier, under "no model ->
    // AmplitudeEndpointer" and over "the wall caps always". PROBE_CUTOUT_FRAMES CONSECUTIVE frames
    // over PROBE_BUDGET_MS and the probe is latched off for the rest of the SESSION — never retried
    // per frame, and not re-armed by a commit either: a probe that has blown its budget 16 frames
    // running will blow it on the 17th too, and every retry costs the audio thread again.
    //
    // Four properties, none of which any other one implies, and each pinned below on its own:
    //  - the run must be CONSECUTIVE — one fast frame resets it;
    //  - the comparison is TRUNCATED MILLISECONDS, STRICTLY above the budget, so its effective
    //    threshold is 9 ms and an 8.5 ms probe is not an overrun. Task C10 re-measures the same
    //    call in MICROSECONDS, where 8.5 ms IS one: different numbers, and the test below says
    //    which of them this is;
    //  - the frame that TRIPS the latch has its verdict DISCARDED, commit and all;
    //  - the latch is permanent within a session and lifted ONLY by onSessionStart.
    // ---------------------------------------------------------------------------------------

    @Test fun a_frame_exactly_at_the_budget_is_not_an_overrun() {
        val clock = FakeClock()
        val probe = FakeProbe()
        probe.clock = clock
        probe.costMs = EndpointerTuning.PROBE_BUDGET_MS
        val ep = SileroEndpointer(
            probe = probe,
            nanoClock = clock,
        )
        Pump(ep, probe).run(0.1f, 40)
        assertFalse(ep.isProbeCutout())
        assertEquals("every frame still reached the probe", 40, probe.frames.size)
    }

    /**
     * THE UNITS PIN, and the one test on this branch written against a task that has not run yet.
     *
     * [EndpointerTuning.PROBE_BUDGET_MS] is 8 MILLISECONDS and its two consumers do not compare it
     * the same way. This latch truncates the elapsed nanoseconds to whole milliseconds and tests
     * strictly above the budget, so its real threshold is 9 ms. Task C10's `ProbeStats` compares
     * MICROSECONDS against the same constant converted, where the threshold is 8.000 ms exactly.
     * **A probe costing 8.5 ms is not an overrun here and is one there** — so if C10 "unifies" the
     * two by moving this comparison to microseconds, it retunes the cutout latch inside a wiring
     * commit: a behaviour change wearing a plumbing commit's message.
     *
     * Both sides are stated, because neither is visible from the other: half a millisecond past the
     * budget must NOT latch after four times the cutout run, and one whole millisecond past it must
     * latch on exactly the 16th frame. Spelled from [EndpointerTuning.PROBE_BUDGET_MS] throughout,
     * so a retune of the budget moves the boundary this test describes instead of stranding it.
     */
    @Test fun the_budget_is_compared_in_TRUNCATED_MILLISECONDS_not_microseconds() {
        val clock = FakeClock()
        val budgetNs = EndpointerTuning.PROBE_BUDGET_MS * 1_000_000L
        var costNs = budgetNs + 500_000L
        var probed = 0
        val ep = SileroEndpointer(
            probe = { clock.nowNs += costNs; probed++; 0.1f },
            nanoClock = clock,
        )
        var t = BASE
        fun frames(n: Int) = repeat(n) {
            ep.onFrame(ByteArray(B), 0, t)
            t += EndpointerTuning.FRAME_MS
        }

        frames(4 * EndpointerTuning.PROBE_CUTOUT_FRAMES)
        assertFalse(
            "half a millisecond past the budget truncates back TO the budget, and the budget " +
                "itself is not an overrun — four times the cutout run of it must not latch",
            ep.isProbeCutout(),
        )
        costNs = budgetNs + 999_999L
        frames(EndpointerTuning.PROBE_CUTOUT_FRAMES)
        assertFalse(
            "one nanosecond under the next whole millisecond still truncates to the budget",
            ep.isProbeCutout(),
        )
        costNs = budgetNs + 1_000_000L
        frames(EndpointerTuning.PROBE_CUTOUT_FRAMES - 1)
        assertFalse("the run is one frame short", ep.isProbeCutout())
        frames(1)
        assertTrue(
            "one whole millisecond past the budget IS an overrun — the effective threshold is 9 " +
                "ms, not 8, and not 8.000 as a microsecond compare would make it",
            ep.isProbeCutout(),
        )
        assertEquals(
            "every frame up to the latch was probed, and none after it",
            6 * EndpointerTuning.PROBE_CUTOUT_FRAMES,
            probed,
        )
    }

    @Test fun the_cutout_latches_only_on_16_CONSECUTIVE_overruns() {
        val clock = FakeClock()
        val probe = FakeProbe()
        probe.clock = clock
        val ep = SileroEndpointer(
            probe = probe,
            nanoClock = clock,
        )
        val pump = Pump(ep, probe)

        probe.costMs = OVERRUN_MS
        pump.run(0.1f, 15)
        assertFalse("15 is not 16", ep.isProbeCutout())
        probe.costMs = 0
        pump.run(0.1f, 1)
        assertFalse(ep.isProbeCutout())
        probe.costMs = OVERRUN_MS
        pump.run(0.1f, 15)
        assertFalse("the fast frame broke the run", ep.isProbeCutout())
        pump.run(0.1f, 1)
        assertTrue("the 16th consecutive overrun latches", ep.isProbeCutout())
        assertEquals("32 frames were probed before the latch fired", 32, probe.frames.size)
    }

    @Test fun a_latched_cutout_stops_probing_for_the_rest_of_the_session() {
        val clock = FakeClock()
        val probe = FakeProbe()
        probe.clock = clock
        probe.costMs = OVERRUN_MS
        val ep = SileroEndpointer(
            probe = probe,
            nanoClock = clock,
        )
        val pump = Pump(ep, probe)
        pump.run(0.1f, 16)
        assertTrue(ep.isProbeCutout())
        val probed = probe.frames.size
        probe.costMs = 0
        assertFalse("the amplitude fallback owns the session now", pump.run(0.9f, 200))
        assertEquals("not one more native call", probed, probe.frames.size)
        assertEquals("the latch fired on the 16th frame", 16, probed)
    }

    /**
     * The frame that TRIPS the latch has its verdict DISCARDED — and here it was a commit.
     *
     * The check sits between [SileroEndpointer.onFrame]'s probe call and its dispatch into
     * `onProb`, not after it, so the last measurement a failing probe ever produces is thrown away
     * rather than acted on. That is the conservative direction and the only defensible one: the
     * reason the latch fires at all is that this probe's output has stopped being trustworthy, and
     * a cut is the one verdict the caller cannot take back — `no_context = true` makes a mid-word
     * cut unrepairable.
     *
     * The fixture lines two constants up on purpose. At the 32 ms cadence the hangover cuts on the
     * dip's 17TH frame and [EndpointerTuning.PROBE_CUTOUT_FRAMES] is 16, so making every frame from
     * the dip's second onward slow puts the 16th consecutive overrun exactly on the committing
     * frame. A retune of either constant breaks this arithmetic LOUDLY — the assertions below stop
     * describing the frame they name — rather than quietly leaving the property untested.
     */
    @Test fun the_frame_that_trips_the_latch_has_its_verdict_discarded() {
        val clock = FakeClock()
        val probe = FakeProbe()
        probe.clock = clock
        val ep = SileroEndpointer(
            probe = probe,
            nanoClock = clock,
        )
        val pump = Pump(ep, probe)
        pump.run(0.9f, 20)                  // fast: gate opens at BASE, speech runs to BASE+608
        assertTrue("640 ms of speech is comfortably committable", ep.hasPendingSpeech())
        pump.run(0.1f, 1)                   // the dip's first frame stamps the end at BASE+640
        probe.costMs = OVERRUN_MS
        assertFalse(
            "the dip's frames 2..16 are slow, but 480 ms is under the hangover",
            pump.run(0.1f, EndpointerTuning.PROBE_CUTOUT_FRAMES - 1),
        )
        assertFalse("15 consecutive overruns is not the latch either", ep.isProbeCutout())
        assertFalse(
            "the dip's 17th frame is 512 ms in and WOULD cut — but it is also the 16th " +
                "consecutive overrun, and the latch discards the verdict it just paid for",
            pump.run(0.1f, 1),
        )
        assertTrue(ep.isProbeCutout())
        assertEquals(0, pump.commits)
        assertTrue(
            "onProb never ran on that frame, so nothing cleared the buffer either",
            ep.hasPendingSpeech(),
        )
    }

    @Test fun the_latch_survives_reset_and_is_re_armed_only_by_a_new_session() {
        // The we_on_new_segment latch discipline: a probe that blew its budget 16 frames running
        // will almost certainly blow it again, so it is never retried per frame — or per commit.
        val clock = FakeClock()
        val probe = FakeProbe()
        probe.clock = clock
        probe.costMs = OVERRUN_MS
        val ep = SileroEndpointer(
            probe = probe,
            nanoClock = clock,
        )
        val pump = Pump(ep, probe)
        pump.run(0.1f, 16)
        assertTrue(ep.isProbeCutout())
        ep.reset()
        assertTrue("a commit must NOT re-arm the probe", ep.isProbeCutout())
        probe.costMs = 0
        pump.run(0.9f, 10)
        assertEquals(16, probe.frames.size)

        ep.onSessionStart(nowMs = pump.t, minCommitIntervalMs = 1_200L)
        assertFalse("a fresh session re-arms it", ep.isProbeCutout())

        // And the RUN is re-armed with the flag, which only a SLOW first frame can show. A session
        // start that cleared `probeCutout` and left the counter sitting at its cutout value would
        // re-latch on this very frame — one overrun, not sixteen — the per-frame retry in its most
        // expensive disguise: the probe gets exactly one frame's chance and the whole recording is
        // decided by it. Any fast frame in between hides that, because a fast frame zeroes the run
        // itself; the first draft of this test ran five and the mutation survived.
        probe.costMs = OVERRUN_MS
        pump.run(0.9f, 1)
        assertEquals("the probe is live again", 17, probe.frames.size)
        assertFalse("one slow frame into a new session is one, not sixteen", ep.isProbeCutout())
        pump.run(0.9f, EndpointerTuning.PROBE_CUTOUT_FRAMES - 2)
        assertFalse("15 overruns is still not the latch", ep.isProbeCutout())
        pump.run(0.9f, 1)
        assertTrue("the 16th is, on the new session's own count", ep.isProbeCutout())
        assertEquals(32, probe.frames.size)
    }

    /**
     * The obligations this file places on LATER tasks and on other files are pinned as WHOLE
     * SENTENCES, each scoped to the member that states it.
     *
     * Same instrument and same reason as `EndpointerTuningTest`: a stub whose semantics live only
     * in a comment is a stub whose semantics can be deleted by the task that fills it in. Whole
     * sentences rather than distinctive words, per the N6 K10 lesson.
     */
    @Test fun the_binding_obligations_are_pinned_in_the_source() {
        val pins = listOf(
            // The two pins that stood here were C2's obligations ON C3 — "Task C3 replaces this
            // stub and MUST implement…" and what a constant `false` cost until it did. C3
            // discharged both and deleted them with the stub's KDoc, which is what a discharged
            // obligation is supposed to look like. The two below are C3's own, and they are
            // obligations on C4 and C5: they say what the predicate MEANS now that it is real.
            Pin(
                "hasPendingSpeech describes the uncommitted BUFFER, not the gate (C4/C5)",
                kdocFor("override fun hasPendingSpeech()"),
                "It describes the UNCOMMITTED BUFFER, not the gate: a merged or discarded " +
                    "utterance leaves it true, because that audio really is still sitting there.",
            ),
            Pin(
                "and therefore what may clear it — a closing gate is not a commit",
                kdocFor("override fun hasPendingSpeech()"),
                "Only a commit or a [reset] clears it — a closing gate does not, and neither " +
                    "does a silent frame.",
            ),
            Pin(
                "the probe must copy anything it retains — the array is reused",
                classKdoc(),
                "The array is REUSED between calls — the probe must copy anything it retains.",
            ),
            Pin(
                "-1.0f keeps the previous state; it can neither open nor close the gate (T10)",
                kdocFor("private fun onProb("),
                "the previous state is kept exactly — it can neither open nor close the gate",
            ),
            Pin(
                "the accumulator is the CONTRACT, not an optimisation (T6)",
                kdocFor("override fun onFrame("),
                "accumulating to exact frame boundaries is the CONTRACT, not an optimisation",
            ),
            Pin(
                "the cadence floor is not a constructor parameter, and why",
                classKdoc(),
                "The per-tier cost governor is NOT a constructor parameter",
            ),
            // C7's own two, and both are obligations on C10 — the task that wires ProbeStats and
            // the native arm/teardown into this same method and this same timed call.
            Pin(
                "the probe budget's UNITS and strictness — C10 measures the same call differently",
                kdocFor("private fun timedProbe("),
                "The comparison is TRUNCATED MILLISECONDS, strictly above the budget, so the " +
                    "effective threshold is 9 ms: an 8.5 ms probe truncates to 8 and is NOT an " +
                    "overrun.",
            ),
            Pin(
                "only a new SESSION lifts the slow-probe latch — a commit must not (C10)",
                kdocFor("override fun onSessionStart("),
                "The slow-probe latch is RE-ARMED here and nowhere else.",
            ),
        )

        pins.forEach { (item, scope, sentence) ->
            assertTrue(
                "SileroEndpointer.kt no longer states: \"$sentence\"\n" +
                    "That sentence is the written obligation for: $item.\n" +
                    "This class is built across ten tasks that each replace a stub someone else " +
                    "wrote. The sentence beside a stub is the only thing that tells the next " +
                    "implementer what the stub was standing in FOR — delete it and the stub " +
                    "becomes indistinguishable from finished code. Restore the sentence, or, if " +
                    "the obligation really changed, change the code, the sentence and this pin " +
                    "together.\n" +
                    "A pin may legitimately be DELETED, but only by the task that DISCHARGES its " +
                    "obligation, in the same commit, and only when the sentence describes a stub " +
                    "that no longer exists. Task C3 deleted two on exactly those terms (see the " +
                    "comment above this list). Deleting one to get green is the failure this " +
                    "test exists to catch.",
                prose(scope).contains(sentence),
            )
        }
    }

    /**
     * The class KDoc promises "@Volatile", and `reset()` really is called from Main while the
     * capture thread is inside `onFrame` (`Endpointer`'s own threading note; the four service
     * sites at FloatingBubbleService.kt:1722/:1819/:2224/:2393, of which :2224 becomes
     * `onSessionStart` — Main-only, and a WRITER of three more fields). Without the annotation
     * there is no happens-before edge at all between the Main-thread clear and the capture thread,
     * so the cleared state may never become visible — a promise no prose can keep on its own.
     *
     * CARRY, deliberately enforced on later tasks: Tasks C3-C10 add fields to this class. It has
     * already paid for itself once — the plan's C3 snippet declared its three (`speaking`,
     * `speechStartMs`, `pendingSpeech`) without the annotation, and this test is what told that
     * implementer to add it, in the same breath as the reason. C4-C10 add more fields on the same
     * terms. `val` fields are exempt — final-field semantics already publish them safely.
     */
    @Test fun every_mutable_field_is_volatile_because_reset_arrives_from_Main() {
        val lines = src.lines()
        // Leading annotations are part of the match, not a reason to miss the declaration: a
        // pattern that only saw BARE `private var` would see no fields at all once they are
        // annotated, and would then pass this test for the wrong reason forever.
        val decl = Regex(
            """^ {4}(?:@\w+\s+)*(?:(?:private|internal|protected|lateinit)\s+)*var\s+(\w+)""",
        )
        val offenders = lines.withIndex().mapNotNull { (i, line) ->
            val name = decl.find(line)?.groupValues?.get(1) ?: return@mapNotNull null
            val annotatedHere = line.contains("@Volatile")
            val annotatedAbove = i > 0 && lines[i - 1].trim() == "@Volatile"
            if (annotatedHere || annotatedAbove) null else name
        }
        assertEquals(
            "SileroEndpointer declares a mutable field without @Volatile: $offenders. onFrame " +
                "runs on the capture thread and reset() is called from Main at switchSource, " +
                "onOpen and stopRecording, so every var in this class is read on one thread and " +
                "written on another. @Volatile is the tolerance this class documents (and the one " +
                "SegmentCapPolicy already ships): the writes are not atomic together, but a torn " +
                "observation costs at most one 32 ms chunk of slack — whereas an unannotated " +
                "field may never publish the Main-thread clear at all.",
            emptyList<String>(),
            offenders,
        )
        assertTrue(
            "SileroEndpointer declares no mutable fields at all — this guard is reading the " +
                "wrong file or the field block moved out of the class body",
            lines.any { decl.containsMatchIn(it) },
        )
    }

    /**
     * The frame contract is IMPORTED, never re-literalised.
     *
     * `EndpointerTuning` states, in a sentence that suite already pins, that it is the SINGLE OWNER
     * of the JVM side of the native frame contract — `FRAME_BYTES` and `NO_VERDICT`. A fresh
     * `1024` or `-1.0f` written into this file is not a style question: it is a second owner, and
     * the next edit to the real one would move the probe's frame size on one side of the seam only.
     *
     * CODE only, deliberately. The KDoc above `onFrame` cites the two OTHER 1024s in the capture
     * path (`StreamingAudioRecorder.kt:80`, the 48 kHz decimator's "~1024") precisely to say they
     * are AudioRecord read sizes and not the frame contract — a scan that could not tell prose from
     * code would forbid the sentence that prevents the confusion.
     */
    @Test fun the_frame_contract_is_imported_never_re_literalised() {
        val forbidden = mapOf(
            Regex("""(?<![\w.])1024(?![\w.])""") to "EndpointerTuning.FRAME_BYTES",
            Regex("""(?<![\w.])-1\.0f""") to "EndpointerTuning.NO_VERDICT",
        )
        forbidden.forEach { (literal, constant) ->
            val hits = code().filter { literal.containsMatchIn(it) }
            assertEquals(
                "SileroEndpointer.kt writes the frame contract as a literal instead of importing " +
                    "$constant: $hits. EndpointerTuning owns the JVM side of that contract in one " +
                    "place, and says so in a sentence EndpointerTuningTest enforces; a second " +
                    "literal here would let a future edit move the frame size or turn the native " +
                    "sentinel into a legitimate probability on one side of the seam only.",
                emptyList<String>(),
                hits,
            )
        }
    }

    /**
     * The micro-pause offer's "none" value is NAMED, never re-literalised.
     *
     * `prevEndMs` carries WALL-CLOCK milliseconds and its zero LEAVES the class, through
     * [SileroEndpointer.pendingCutPointMs], as [Endpointer.NO_CUT_POINT] — a sentinel whose own
     * KDoc warns that 0 is a legal reading of that clock, merely unreachable in practice, and that
     * a switch to a monotonic or session-relative clock must change the sentinel FIRST. A bare
     * `0L` written here is correct today and silently wrong the day the clock changes: the
     * endpointer would report "no micro-pause observed" for its first frame, the wall cap would
     * fall back to cutting blind, and nothing at the call site would look wrong. Prose cannot hold
     * that line across the seven tasks still to touch this class, so this does.
     *
     * Scoped to `prevEndMs` ALONE, deliberately, in both directions. [SileroEndpointer]'s
     * `tempEndMs` keeps its bare `0L`: that zero is private "not in a dip" bookkeeping published
     * to nobody, and dragging it under the interface's clock ruling would be a false claim about
     * what the sentinel governs. The scan permits an ASSIGNMENT of a wall-clock instant — it
     * forbids only a zero — which is why C5 wrote it this way for a `prevEndMs = tempEndMs` the
     * plan expected Task C6 to add on the merge path. C6 dropped that line instead (it was dead:
     * the promotion above the hangover check has always already written the same value, and
     * moving it past `closeGate()` would have written 0 with the promotion covering), so the
     * allowance stands unused. The scoping is still what it was written for: a future merge-site
     * boundary write must not have to fight this scan to say a legitimate instant.
     */
    @Test fun the_wall_clock_sentinel_is_never_re_literalised_as_a_bare_zero() {
        val bareZero = Regex("""(?<![\w.])0L?(?![\w.])""")
        val hits = code().filter { it.contains("prevEndMs") && bareZero.containsMatchIn(it) }
        assertEquals(
            "SileroEndpointer.kt writes a bare zero on a prevEndMs line instead of naming " +
                "Endpointer.NO_CUT_POINT: $hits. That field's zero is the sentinel the interface " +
                "OWNS and documents, not an arithmetic zero this class may spell for itself: the " +
                "companion's KDoc says to change the sentinel before changing the clock, and a " +
                "literal here is exactly the site that would not be changed. tempEndMs's bare 0L " +
                "is deliberately none of this test's business.",
            emptyList<String>(),
            hits,
        )
        assertTrue(
            "SileroEndpointer.kt no longer initialises prevEndMs to Endpointer.NO_CUT_POINT — so " +
                "the scan above is passing because the field it guards has been renamed or " +
                "deleted, not because the discipline held.",
            code().any { it.contains("var prevEndMs = Endpointer.NO_CUT_POINT") },
        )
    }

    // ---------------------------------------------------------------------------------------
    // Source-reading helpers. Same shape as EndpointerTuningTest's, same reasons.
    // ---------------------------------------------------------------------------------------

    private data class Pin(val item: String, val scope: String, val sentence: String)

    private fun repoFile(relative: String): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            File(dir, relative).let { if (it.isFile) return it }
            File(dir, "app/$relative").let { if (it.isFile) return it }
            dir = dir.parentFile
        }
        throw AssertionError("could not locate $relative from ${System.getProperty("user.dir")}")
    }

    /**
     * Line endings are normalized to LF at the single read site, so no anchor below can be
     * defeated by a CRLF checkout (the N1/N2 lesson: `readText()` does not normalize).
     */
    private val src: String by lazy {
        repoFile("src/main/java/com/whispereverywhere/audio/SileroEndpointer.kt")
            .readText().replace("\r\n", "\n")
    }

    /**
     * The file's CODE lines: KDoc bodies, block openers/closers and whole-line `//` comments
     * dropped, trailing `// …` cut. Approximate by design — it exists so a literal scan cannot be
     * tripped by a comment that DISCUSSES a literal, which is the only prose this file contains
     * about numbers.
     */
    private fun code(): List<String> =
        src.lines()
            .map { it.trim() }
            .filterNot {
                it.startsWith("*") || it.startsWith("/**") || it.startsWith("//") ||
                    it.startsWith("*/")
            }
            .map { it.substringBefore("//").trim() }
            .filter { it.isNotEmpty() }

    /**
     * A DECLARATION's first line, in any form this class uses or is planned to use.
     *
     * Written as modifiers-then-keyword rather than as the list of exact prefixes it replaces,
     * because the list is what went stale. C2 wrote `override fun `, `private fun `, `class `,
     * `private val ` and `@Volatile`; C7's own `fun isProbeCutout()` carries no modifier at all and
     * matches none of them, and neither would C8's `data class EndpointCut` or any `internal fun`.
     * A scope guard that has quietly stopped seeing the members it guards against is worse than no
     * guard: it reports green while a pin is satisfied by a KDoc written about something else.
     *
     * Local `val`/`var` lines inside a body match too, deliberately — any CODE between a KDoc block
     * and the declaration it is claimed for means the block is not that declaration's.
     */
    private val memberStart = Regex(
        "^(?:@\\w+(?:\\([^)]*\\))?\\s+)*" +
            "(?:(?:private|internal|protected|public|override|open|abstract|final|data|value|" +
            "lateinit|const|companion|suspend|operator|inline|external)\\s+)*" +
            "(?:fun|val|var|class|object|constructor)\\b",
    )

    /** …or a line carrying nothing but annotations, which is how a field's may be written. */
    private val annotationOnly = Regex("^(?:@\\w+(?:\\([^)]*\\))?\\s*)+$")

    private fun spansAMember(block: String): Boolean =
        block.lineSequence().drop(1).any {
            val t = it.trimStart()
            memberStart.containsMatchIn(t) || annotationOnly.matches(t)
        }

    /** The class's own KDoc: the block that ends at the `class SileroEndpointer` declaration. */
    private fun classKdoc(): String {
        val at = src.indexOf("class SileroEndpointer(")
        assertTrue("SileroEndpointer.kt no longer declares `class SileroEndpointer(`", at >= 0)
        val head = src.substring(0, at)
        val open = head.lastIndexOf("/**")
        assertTrue(
            "no KDoc block opens above `class SileroEndpointer(`. lastIndexOf returns -1 when the " +
                "block is gone, so this assert names the real cause — the class's state-machine, " +
                "clock, threading and constructor-scope rulings were deleted — instead of failing " +
                "the sentence pins for what reads like drift.",
            open >= 0,
        )
        val block = head.substring(open)
        assertTrue(
            "the class KDoc scope widened past a top-level declaration: the two class-scoped " +
                "pins would then be satisfied by a helper's KDoc, not by the class's own.",
            !spansAMember(block),
        )
        return block
    }

    /**
     * The KDoc block immediately above one declaration — from its opening marker down to the
     * declaration. (Spelling that marker out here is not possible: Kotlin NESTS block comments, so
     * it would open one that never closes.)
     */
    private fun kdocFor(declaration: String): String {
        val at = src.indexOf(declaration)
        assertTrue("SileroEndpointer.kt no longer declares `$declaration`", at >= 0)
        val head = src.substring(0, at)
        val open = head.lastIndexOf("/**")
        assertTrue("no KDoc block opens above `$declaration`", open >= 0)
        val block = head.substring(open)
        assertTrue(
            "the KDoc scope for `$declaration` widened past a previous member: lastIndexOf finds " +
                "the NEAREST block above the declaration, so deleting THIS member's KDoc outright " +
                "silently borrows the previous member's (or the class's) — and an obligation pin " +
                "could then be satisfied by a sentence written about something else.",
            !spansAMember(block),
        )
        return block
    }

    /**
     * KDoc/comment prose as a single normalized line: leading decoration stripped and runs of
     * whitespace collapsed, so a pin can anchor on a WHOLE SENTENCE without being defeated by
     * wherever the 100-column limit happened to wrap it.
     */
    private fun prose(scope: String): String =
        scope.lineSequence()
            .map { line ->
                var t = line.trim()
                if (t.startsWith("/**")) t = t.removePrefix("/**")
                if (t.startsWith("//")) t = t.removePrefix("//")
                if (t.endsWith("*/")) t = t.removeSuffix("*/")
                if (t.startsWith("*")) t = t.removePrefix("*")
                t.trim()
            }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
            .replace(Regex("\\s+"), " ")
}
