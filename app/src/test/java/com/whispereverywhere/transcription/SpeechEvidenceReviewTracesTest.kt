package com.whispereverywhere.transcription

import com.whispereverywhere.audio.Endpointer
import com.whispereverywhere.audio.EndpointerGrid
import com.whispereverywhere.audio.EndpointerTuning
import com.whispereverywhere.audio.SileroEndpointer
import com.whispereverywhere.service.SegmentQueueDepth
import com.whispereverywhere.util.RetryPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val B = EndpointerTuning.FRAME_BYTES
private const val FRAME_MS = EndpointerTuning.FRAME_MS
private const val BASE = 1_000_000L
private const val P_SPEECH = 0.9f
private const val P_DEAD_BAND = 0.45f
private const val P_SILENCE = 0.1f
private val HANGOVER_FRAMES = EndpointerGrid.HANGOVER_FRAMES
private val MICRO_PAUSE_FRAMES = EndpointerGrid.MICRO_PAUSE_FRAMES
private val FLOOR = EndpointerTuning.MIN_SPEECH_EVIDENCE_MS

/**
 * The floor as a FRAME COUNT — six at 192 ms. T2 and T4 sit exactly one frame under and exactly
 * on the floor, so both derive from this rather than from a literal (nit N1 moved it 256 -> 192).
 */
private val FLOOR_FRAMES = (FLOOR / FRAME_MS).toInt()

/**
 * THE SPEECH EVIDENCE (4.3.2) — the adversarial reviewer's traces (round 1). Each one drives the
 * REAL `SileroEndpointer` and the REAL `LocalWhisperEngine` through the funnel's exact three
 * lines (`speechEvidenceMs()` -> `commit(evidence)` -> `onBufferCommitted(tailRetained)`), so
 * the seam is exercised end to end rather than each half from the other's word.
 *
 * Two of the five (T1, T2) pin an HONEST LIMIT rather than a virtue: the count is a total of
 * frames at or above ONSET (0.50), so an utterance Silero parks in the dead band
 * (RELEASE 0.35 <= p < 0.50) carries no evidence and IS skipped — including one the CPU tier's
 * own `we_vad_filter` (threshold 0.40) would have transcribed. They exist so that the number on
 * the device sheet has a fixture behind it; if the sheet shows a real word skipped, the fix is
 * the floor or the counting rule, and these two tests are the ones that must then change.
 */
class SpeechEvidenceReviewTracesTest {

    // ------------------------------------------------------------------ a tiny funnel

    private class Probe(var next: Float = 0f) : (ByteArray) -> Float {
        override fun invoke(frame: ByteArray): Float = next
    }

    /** Frames through the endpointer AND the engine, as the capture thread feeds both. */
    private class Rig(val ep: SileroEndpointer, val probe: Probe, val engine: LocalWhisperEngine) {
        var t = BASE
        val cuts = mutableListOf<Long>()

        fun run(p: Float, frames: Int): Boolean {
            probe.next = p
            var fired = false
            repeat(frames) {
                val chunk = ByteArray(B)
                engine.sendAudio(chunk)                       // sendAudio FIRST, unconditional
                if (ep.onFrame(chunk, 3_000, t)) { fired = true; cuts += t }
                t += FRAME_MS
            }
            return fired
        }

        /** `FloatingBubbleService.commitSegment`'s three evidence lines, verbatim in order. */
        fun funnel(retainMs: Long = 0L): Long {
            val evidence = SpeechEvidence.of(ep.speechEvidenceMs())
            val seq = if (retainMs > 0L) engine.commitRetainingTailMs(retainMs, evidence) else engine.commit(evidence)
            ep.onBufferCommitted(tailRetained = retainMs > 0L)
            return seq
        }
    }

    private fun rig(backend: WhisperBackend = SampleCountBackend(), listener: TranscriptionEngine.Listener): Rig {
        val probe = Probe()
        val ep = SileroEndpointer(probe = probe)
        ep.onSessionStart(nowMs = BASE, minCommitIntervalMs = 0L)
        val engine = LocalWhisperEngine(
            modelPathProvider = FakeModelPathProvider("/models/pro.bin"),
            retry = RetryPolicy(maxAttempts = 3, baseDelayMs = 0, maxDelayMs = 0, rng = { 0.0 }),
            backend = backend,
            executor = SameThreadExecutorService(),
        )
        engine.connect(language = "en", listener = listener)
        return Rig(ep, probe, engine)
    }

    // ------------------------------------------------------------------ T1

    @Test
    fun T1_an_utterance_silero_parks_entirely_in_the_dead_band_carries_zero_evidence_and_is_skipped_at_the_cap() {
        // 2 s of p = 0.45: above native's 0.40 filter threshold (the CPU tier would have run
        // whisper on it), below ONSET (the gate never opens, no cut, no pending speech). A
        // 4 s first cap commits it with KNOWN evidence 0 -> SKIPPED. The documented limit.
        val listener = RecordingListener()
        val r = rig(listener = listener)
        assertFalse(r.run(P_DEAD_BAND, 62))
        assertFalse(r.ep.hasPendingSpeech())
        assertEquals(0L, r.ep.speechEvidenceMs())
        assertTrue(P_DEAD_BAND >= EndpointerTuning.RELEASE_THRESHOLD && P_DEAD_BAND < EndpointerTuning.ONSET_THRESHOLD)
        assertTrue("native's batch filter would have kept this audio", P_DEAD_BAND >= 0.40f)

        assertEquals(0L, r.funnel())
        assertEquals(listOf(0L to SegmentOutcome.EmptyExpected), listener.resolved)
    }

    // ------------------------------------------------------------------ T2

    @Test
    fun T2_a_VAD_cut_whose_utterance_is_mostly_dead_band_passes_the_span_floor_and_is_still_skipped() {
        // Ten frames S D S D S D S D S D: the span is 320 ms > MIN_SPEECH_MS (the hangover CUTS
        // it), but only five are at or above ONSET: 160 ms < 192. A real, mumbled word that the
        // endpointer chose to commit is then not encoded. "In the normal case" is the KDoc's
        // phrase; this is the abnormal one, pinned.
        //
        // BUILT FROM THE CONSTANTS. N1 moved the floor 256 -> 192 and this fixture had to move
        // with it — seven onset frames were under 256 and are over 192 — so it is now spelled as
        // "one frame short of FLOOR_FRAMES, interleaved with the dead band over just enough
        // frames to clear MIN_SPEECH_MS". The interleaving is what buys the span: a dead-band
        // frame holds the gate open and the hangover clock still, while counting as no evidence.
        val listener = RecordingListener()
        val r = rig(listener = listener)
        val onsetFrames = FLOOR_FRAMES - 1
        val spanFrames = (EndpointerTuning.MIN_SPEECH_MS / FRAME_MS).toInt() + 1
        assertTrue("the dead band must have room for the span", spanFrames >= 2 * onsetFrames)
        for (i in 0 until spanFrames) {
            assertFalse(r.run(if (i % 2 == 0 && i / 2 < onsetFrames) P_SPEECH else P_DEAD_BAND, 1))
        }
        assertTrue("the hangover cuts: the span clears MIN_SPEECH_MS", r.run(P_SILENCE, HANGOVER_FRAMES))
        assertEquals(spanFrames * FRAME_MS, r.ep.lastCut()?.speechMs)
        assertTrue(spanFrames * FRAME_MS > EndpointerTuning.MIN_SPEECH_MS)
        assertEquals(onsetFrames * FRAME_MS, r.ep.speechEvidenceMs())
        assertTrue(r.ep.speechEvidenceMs() < FLOOR)

        assertEquals(0L, r.funnel())
        assertEquals("a VAD cut CAN be skipped on evidence", listOf(0L to SegmentOutcome.EmptyExpected), listener.resolved)
        assertEquals("re-based UNKNOWN after a plain commit", Endpointer.UNKNOWN_SPEECH_EVIDENCE_MS, r.ep.speechEvidenceMs())
    }

    // ------------------------------------------------------------------ T3

    @Test
    fun T3_commit_A_real_B_skipped_C_real_the_depth_returns_to_zero_and_the_orderer_releases_A_then_C() {
        // The brief's integration test verbatim, wired as the service wires it: the funnel's
        // `segmentQueueDepth.onCommitted(seq)`, the listener's `onResolved(seq)`, the orderer's
        // `onResolved(seq, outcome)`. In commit order first (the local executor's shape).
        val published = mutableListOf<Int>()
        val depth = SegmentQueueDepth(onDepth = { published += it })
        val orderer = SegmentOrderer()
        val executor = QueueingExecutorService()
        val engine = LocalWhisperEngine(
            modelPathProvider = FakeModelPathProvider("/models/pro.bin"),
            retry = RetryPolicy(maxAttempts = 3, baseDelayMs = 0, maxDelayMs = 0, rng = { 0.0 }),
            backend = ScriptedBackend(listOf({ "A" }, { "C" })),
            executor = executor,
        )
        var released = ""
        engine.connect(language = "en", listener = object : TranscriptionEngine.Listener {
            override fun onOpen() = Unit
            override fun onDelta(text: String) = Unit
            override fun onSegmentResolved(seq: Long, outcome: SegmentOutcome) {
                released += orderer.onResolved(seq, outcome).text
                depth.onResolved(seq)
            }
            override fun onError(message: String) = Unit
            override fun onClosed() = Unit
        })
        executor.tasks.forEach { it.run() }; executor.tasks.clear()     // the model load
        val audio = ByteArray(2_000 * 32)

        engine.sendAudio(audio); depth.onCommitted(engine.commit(SpeechEvidence.of(640L)))   // A
        engine.sendAudio(audio); depth.onCommitted(engine.commit(SpeechEvidence.of(0L)))     // B: skipped
        engine.sendAudio(audio); depth.onCommitted(engine.commit(SpeechEvidence.of(640L)))   // C
        assertEquals(3, depth.depth())
        assertEquals(3, executor.tasks.size)
        executor.tasks.forEach { it.run() }
        assertEquals(0, depth.depth())
        assertEquals(listOf(1, 2, 3, 2, 1, 0), published)
        // Three separate drains, one release each: the orderer leaves word-to-word spacing
        // ACROSS calls to the caller (SegmentOrderer.hasEmittedText KDoc), so "A" then "C".
        assertEquals("AC", released)
        assertEquals(0, orderer.pendingCount())
    }

    @Test
    fun T3b_the_same_three_resolving_out_of_order_C_then_B_then_A() {
        val published = mutableListOf<Int>()
        val depth = SegmentQueueDepth(onDepth = { published += it })
        val orderer = SegmentOrderer()
        val executor = QueueingExecutorService()
        val engine = LocalWhisperEngine(
            modelPathProvider = FakeModelPathProvider("/models/pro.bin"),
            retry = RetryPolicy(maxAttempts = 3, baseDelayMs = 0, maxDelayMs = 0, rng = { 0.0 }),
            backend = ScriptedBackend(listOf({ "C" }, { "A" })),     // call order = C first
            executor = executor,
        )
        var released = ""
        engine.connect(language = "en", listener = object : TranscriptionEngine.Listener {
            override fun onOpen() = Unit
            override fun onDelta(text: String) = Unit
            override fun onSegmentResolved(seq: Long, outcome: SegmentOutcome) {
                released += orderer.onResolved(seq, outcome).text
                depth.onResolved(seq)
            }
            override fun onError(message: String) = Unit
            override fun onClosed() = Unit
        })
        executor.tasks.forEach { it.run() }; executor.tasks.clear()
        val audio = ByteArray(2_000 * 32)
        engine.sendAudio(audio); depth.onCommitted(engine.commit(SpeechEvidence.of(640L)))
        engine.sendAudio(audio); depth.onCommitted(engine.commit(SpeechEvidence.of(0L)))
        engine.sendAudio(audio); depth.onCommitted(engine.commit(SpeechEvidence.of(640L)))

        executor.tasks[2].run()
        assertEquals("", released); assertEquals(2, depth.depth())
        executor.tasks[1].run()
        assertEquals("", released); assertEquals(1, depth.depth())
        executor.tasks[0].run()
        assertEquals("A C", released); assertEquals(0, depth.depth())
        assertEquals(listOf(1, 2, 3, 2, 1, 0), published)
    }

    // ------------------------------------------------------------------ T4

    @Test
    fun T4_the_last_word_split_across_a_retaining_cap_and_the_stop_flush_is_encoded_only_because_of_the_carry() {
        // 118 speech frames, a 5-frame dip (offered on its fifth frame), THREE more speech
        // frames, then the cap fires and retains the tail from the offer. The committed part is
        // credited with all 121 (encoded). The endpointer carries the tail's THREE. Then
        // FLOOR_FRAMES - 3 more speech frames and six of silence (no cut), then the stop flush:
        // the carry plus the new frames is exactly FLOOR_FRAMES -> ENCODED at the floor. Without
        // the carry it would read the new frames alone, under the floor -> the speaker's last
        // word skipped. The split derives from the constant: at 256 it was 3 + 5, at 192 it is
        // 3 + 3, and the point of the fixture — the carry is what saves the word — is the same.
        val listener = RecordingListener()
        val r = rig(listener = listener)
        val carried = 3
        val newFrames = FLOOR_FRAMES - carried
        assertTrue("the fixture is only a fixture while the carry is decisive", newFrames in 1 until FLOOR_FRAMES)
        assertFalse(r.run(P_SPEECH, 118))
        assertFalse(r.run(P_SILENCE, MICRO_PAUSE_FRAMES))
        val offer = r.ep.pendingCutPointMs()
        assertTrue(offer > Endpointer.NO_CUT_POINT)
        assertFalse(r.run(P_SPEECH, carried))
        assertEquals((118 + carried) * FRAME_MS, r.ep.speechEvidenceMs())

        // The cap site: retain = now - offer (CommitCadencePolicy.capCutRetainMs, <= 3000 ms).
        val retainMs = r.t - offer
        assertEquals((MICRO_PAUSE_FRAMES + carried) * FRAME_MS, retainMs)
        assertEquals(0L, r.funnel(retainMs = retainMs))
        r.ep.reset()                                                   // the cap site's reset
        assertEquals("the part ran", 1, listener.resolved.size)
        assertTrue(listener.resolved[0].second is SegmentOutcome.Text)
        assertEquals("the carry: the onset frames after the offer", carried * FRAME_MS, r.ep.speechEvidenceMs())

        assertFalse(r.run(P_SPEECH, newFrames))
        assertFalse("six silent frames: under the hangover, no cut", r.run(P_SILENCE, 6))
        assertEquals(FLOOR, r.ep.speechEvidenceMs())
        assertEquals(1L, r.funnel())                                   // the STOP flush
        assertTrue("encoded at exactly the floor", listener.resolved[1].second is SegmentOutcome.Text)
        // The tail the flush encoded is exactly the retained frames + every new one.
        assertEquals(
            SegmentOutcome.Text("n${(MICRO_PAUSE_FRAMES + carried + newFrames + 6) * (B / 2)}"),
            listener.resolved[1].second,
        )
        // The counter-factual the carry exists for:
        assertTrue(SpeechEvidence.of(newFrames * FRAME_MS).isUnder(FLOOR))
    }

    // ------------------------------------------------------------------ T5

    @Test
    fun T5_an_offer_that_survives_the_consent_flush_then_a_cap_the_engine_clamps_to_a_full_commit_can_only_over_count() {
        // The consent flush commits through the funnel with NO reset, so `prevEndMs` outlives
        // the re-base. The next cap computes retain = now - offer, which is LONGER than the
        // audio the engine holds since the flush -> `cut <= 0` -> a plain full commit (nothing
        // retained) while the endpointer is told tailRetained = true. The carry must then be
        // the whole post-flush count (the offer's snapshot was zeroed at the flush): an
        // over-count of frames whose audio was in fact committed — never an under-count, so
        // never a wrong skip. And the silent case carries 0, KNOWN, so the following silence
        // is still skipped.
        val listener = RecordingListener()
        val r = rig(listener = listener)
        assertFalse(r.run(P_SPEECH, 20))
        assertFalse(r.run(P_SILENCE, MICRO_PAUSE_FRAMES))
        val offer = r.ep.pendingCutPointMs()
        assertTrue(offer > Endpointer.NO_CUT_POINT)
        assertEquals(0L, r.funnel())                                   // the consent flush, no reset
        assertTrue(listener.resolved[0].second is SegmentOutcome.Text)
        assertEquals(offer, r.ep.pendingCutPointMs())                  // the offer survived

        // Post-flush: three onset frames and a dip too short to be promoted (the fifth dip
        // frame promotes; three never do), so the LIVE offer is still the pre-flush one.
        assertFalse(r.run(P_SPEECH, 3))
        assertFalse(r.run(P_SILENCE, 3))
        assertEquals(offer, r.ep.pendingCutPointMs())
        assertEquals(3 * FRAME_MS, r.ep.speechEvidenceMs())
        // The cap site: retain = now - offer = 11 frames, LONGER than the 6 frames the engine
        // holds since the flush -> commitRetainingTailMs clamps (`cut <= 0`) to a full commit.
        val retainMs = r.t - r.ep.pendingCutPointMs()
        assertEquals(11 * FRAME_MS, retainMs)
        assertTrue(retainMs <= 3_000L)
        assertEquals(1L, r.funnel(retainMs = retainMs))                // the engine clamps: full commit
        r.ep.reset()
        assertEquals("96 ms of evidence: skipped (native would drop a 96 ms blip too)", SegmentOutcome.EmptyExpected, listener.resolved[1].second)
        assertEquals("the carry is the WHOLE post-flush count, an over-count of audio already committed",
            3 * FRAME_MS, r.ep.speechEvidenceMs())

        // The buffer is EMPTY (nothing was retained): a flush now cuts nothing and owes nothing
        // (NO_SEGMENT) — and the funnel STILL re-bases, so the over-counted carry dies here.
        // Harmless in the only direction that matters: the count describes audio already in
        // the engine (sendAudio is first), so an empty buffer has nothing left to vouch for.
        assertEquals(-1L, r.funnel())
        assertEquals(2, listener.resolved.size)
        assertEquals(Endpointer.UNKNOWN_SPEECH_EVIDENCE_MS, r.ep.speechEvidenceMs())
        // The owner's bed after all of that: room tone with four flickers -> 128 < 192, skipped.
        assertFalse(r.run(P_SILENCE, 100))
        repeat(4) { r.run(P_SPEECH, 1); r.run(P_SILENCE, 100) }
        assertEquals(4 * FRAME_MS, r.ep.speechEvidenceMs())
        assertEquals(2L, r.funnel())
        assertEquals(SegmentOutcome.EmptyExpected, listener.resolved[2].second)
    }
}
