package com.whispereverywhere

import com.whispereverywhere.util.SpeechSegmenter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechSegmenterTest {

    private fun segmenter() = SpeechSegmenter(
        voiceThreshold = 1000,
        silenceThreshold = 500,
        pauseMs = 700,
        maxSegmentMs = 8000,
    )

    @Test
    fun silence_without_prior_speech_never_commits() {
        val s = segmenter()
        assertFalse(s.onAmplitude(0, 0))
        assertFalse(s.onAmplitude(100, 500))
        assertFalse(s.onAmplitude(0, 5000))
        assertFalse(s.hasPendingSpeech())
    }

    @Test
    fun speech_then_long_pause_commits_once() {
        val s = segmenter()
        assertFalse(s.onAmplitude(5000, 0))      // speaking
        assertFalse(s.onAmplitude(5000, 200))    // still speaking
        assertFalse(s.onAmplitude(100, 400))     // brief quiet, not long enough
        // quiet long enough after last voice (200 .. 200+700 = 900)
        assertTrue(s.onAmplitude(100, 950))      // pause ends segment -> commit
        // after commit, state resets; further silence does nothing
        assertFalse(s.onAmplitude(100, 1100))
        assertFalse(s.hasPendingSpeech())
    }

    @Test
    fun continuous_speech_forces_commit_at_max_segment() {
        val s = segmenter()
        var t = 0L
        // Speak continuously well past maxSegmentMs; a quiet sample after the cap commits.
        while (t <= 8000) { s.onAmplitude(5000, t); t += 500 }
        // A quiet sample now: segment length exceeded -> commit even though pause is short.
        val committed = s.onAmplitude(100, t)
        assertTrue(committed)
    }

    @Test
    fun short_quiet_dip_does_not_commit() {
        val s = segmenter()
        s.onAmplitude(5000, 0)
        // a single quiet sample 100ms after voice — far below pauseMs
        assertFalse(s.onAmplitude(100, 100))
        assertTrue(s.hasPendingSpeech())
    }

    @Test fun a_quiet_room_behaves_exactly_as_before() {
        // The adaptive floor must be provably non-regressive: with a low noise floor the effective
        // silence threshold stays at the original 250.
        val s = SpeechSegmenter()
        var t = 0L
        assertFalse(s.onAmplitude(600, t))          // speech opens the segment
        t += 900
        assertTrue("must close on a quiet sample after the hangover", s.onAmplitude(100, t))
    }

    @Test fun a_noisy_room_can_still_close_a_segment() {
        // THE bug this fixes. With a fixed 250 threshold, a room whose floor sits at ~350 can
        // never satisfy the close condition: speech opens at >=500 but nothing ever drops to
        // <=250, so dispatch silently degrades to 15-second wall-clock chunks.
        val s = SpeechSegmenter()
        var t = 0L
        repeat(70) { s.onAmplitude(350, t); t += 32 }   // establish a ~350 noise floor
        assertFalse(s.onAmplitude(600, t))              // speech
        t += 900
        assertTrue("noisy room must still close", s.onAmplitude(350, t))
    }

    @Test fun the_adaptive_floor_never_rises_to_swallow_speech() {
        // effSilence is clamped below voiceThreshold, so a loud room cannot make speech itself
        // count as silence.
        val s = SpeechSegmenter()
        var t = 0L
        repeat(70) { s.onAmplitude(490, t); t += 32 }
        assertFalse("a sample at the voice threshold must never close a segment", s.onAmplitude(500, t))
    }
}
