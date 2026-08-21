package com.whispereverywhere.audio

import com.whispereverywhere.util.SpeechSegmenter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 3.7 fallback contract, pinned: with no VAD model the service constructs an
 * [AmplitudeEndpointer], and its verdict stream must be INDISTINGUISHABLE from the
 * [SpeechSegmenter] the service called directly through 3.6.0. "Model missing" must be
 * byte-identical shipped behaviour, never a new code path.
 */
class AmplitudeEndpointerTest {

    /** (amplitude, wall-clock ms): speech, a mid-word dip, a real pause, a second utterance. */
    private val trace: List<Pair<Int, Long>> = listOf(
        0 to 0L, 120 to 32L, 5000 to 64L, 6000 to 96L, 300 to 128L, 4800 to 160L,
        200 to 192L, 100 to 500L, 80 to 900L, 60 to 1_000L,           // > 800 ms quiet -> commit
        40 to 1_032L, 5_200 to 2_000L, 4_900 to 2_032L, 260 to 2_064L,
        240 to 2_500L, 220 to 2_900L,                                  // second pause -> commit
        0 to 3_000L, 0 to 20_000L,                                     // long silence, no speech
    )

    private val chunk = ByteArray(1024) { 0x07 }

    @Test
    fun verdictStreamIsIdenticalToTheSpeechSegmenterItReplaces() {
        val segmenter = SpeechSegmenter()
        val endpointer = AmplitudeEndpointer()
        val expected = trace.map { (amp, t) -> segmenter.onAmplitude(amp, t) }
        val actual = trace.map { (amp, t) -> endpointer.onFrame(chunk, amp, t) }
        assertEquals(expected, actual)
        assertTrue("the trace must exercise at least one commit", expected.any { it })
    }

    @Test
    fun hasPendingSpeechTracksTheSegmenterStepForStep() {
        val segmenter = SpeechSegmenter()
        val endpointer = AmplitudeEndpointer()
        for ((amp, t) in trace) {
            segmenter.onAmplitude(amp, t)
            endpointer.onFrame(chunk, amp, t)
            assertEquals("at t=$t amp=$amp", segmenter.hasPendingSpeech(), endpointer.hasPendingSpeech())
        }
    }

    @Test
    fun resetClearsPendingSpeechExactlyAsTheSegmenterDoes() {
        val endpointer = AmplitudeEndpointer()
        endpointer.onFrame(chunk, 5_000, 0L)
        assertTrue(endpointer.hasPendingSpeech())
        endpointer.reset()
        assertFalse(endpointer.hasPendingSpeech())
        // and a reset segment does not commit on the next quiet frame
        assertFalse(endpointer.onFrame(chunk, 100, 5_000L))
    }

    @Test
    fun theAudioChunkIsIgnored_soAShortOrEmptyReadChangesNothing() {
        val withChunk = AmplitudeEndpointer()
        val withoutChunk = AmplitudeEndpointer()
        val empty = ByteArray(0)
        val a = trace.map { (amp, t) -> withChunk.onFrame(chunk, amp, t) }
        val b = trace.map { (amp, t) -> withoutChunk.onFrame(empty, amp, t) }
        assertEquals(a, b)
    }

    @Test
    fun theDefaultedExtensionPointsAreInertForTheAmplitudePath() {
        val plain = AmplitudeEndpointer()
        val poked = AmplitudeEndpointer()
        poked.onSessionStart(nowMs = 0L, minCommitIntervalMs = 6_000L)
        val a = trace.map { (amp, t) -> plain.onFrame(chunk, amp, t) }
        val b = trace.map { (amp, t) -> poked.onFrame(chunk, amp, t) }
        assertEquals("a cadence floor must not reach the amplitude path", a, b)
        // ABSOLUTE, and it must come first: every cut-point assertion below is DIFFERENTIAL
        // against the constant, so re-defining the sentinel moves both sides and they all still
        // pass. The wall-cap branch (D6) tests `!= NO_CUT_POINT`, and an implementation that
        // initialises its own cut-point field to a literal 0L — as SileroEndpointer will — is
        // only correct while the sentinel IS 0L.
        assertEquals("the NO_CUT_POINT sentinel is 0L", 0L, Endpointer.NO_CUT_POINT)
        // No micro-pause memory exists here, so the cap cut can never be offered a cut point —
        // which is what makes the 15 s backstop byte-identical to 3.6.0 on this path.
        assertEquals(Endpointer.NO_CUT_POINT, poked.pendingCutPointMs())
        poked.onFrame(chunk, 5_000, 30_000L)
        assertEquals(Endpointer.NO_CUT_POINT, poked.pendingCutPointMs())
        poked.onSessionEnd()
        assertEquals(Endpointer.NO_CUT_POINT, poked.pendingCutPointMs())

        // "Inert" is a claim about live state, not only about the verdict stream: poking the
        // extension points on an OPEN segment must not disturb it. Poking `poked` above could
        // not catch a resetting override, because it happened before any frame — where a reset
        // is indistinguishable from a no-op.
        val open = AmplitudeEndpointer().also { it.onFrame(chunk, 5_000, 0L) }
        assertTrue(open.hasPendingSpeech())
        open.onSessionStart(nowMs = 0L, minCommitIntervalMs = 6_000L)
        assertTrue("onSessionStart must not touch endpoint state", open.hasPendingSpeech())
        open.onSessionEnd()
        assertTrue("onSessionEnd must not touch endpoint state", open.hasPendingSpeech())
    }

    @Test
    fun productionDefaultsArePinned_notMerelyMirrored() {
        // ABSOLUTE, never differential against SpeechSegmenter: a differential assertion inherits
        // any mutation to the defaults and passes. FBS constructs the endpointer with all defaults,
        // so 500/250/800 are the values that actually run. (D1 inheritance: M2/M3/M4/M5/M8.)
        val chunk = ByteArray(1024)
        assertFalse("499 is below the voice floor",
            AmplitudeEndpointer().let { it.onFrame(chunk, 499, 0L); it.hasPendingSpeech() })
        assertTrue("500 is at the voice floor",
            AmplitudeEndpointer().let { it.onFrame(chunk, 500, 0L); it.hasPendingSpeech() })
        val midFloor = AmplitudeEndpointer().also { it.onFrame(chunk, 5_000, 0L) }
        assertFalse("251 is above the silence floor — a mid-floor room opens but never closes",
            midFloor.onFrame(chunk, 251, 10_000L))
        val atFloor = AmplitudeEndpointer().also { it.onFrame(chunk, 5_000, 0L) }
        assertTrue("250 is at the silence floor", atFloor.onFrame(chunk, 250, 800L))
        val pause = AmplitudeEndpointer().also { it.onFrame(chunk, 5_000, 0L) }
        assertFalse("799 ms is not yet a pause", pause.onFrame(chunk, 100, 799L))
        assertTrue("800 ms exactly IS a pause (>= not >)", pause.onFrame(chunk, 100, 800L))
    }
}
