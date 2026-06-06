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
}
