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
    fun continuousSpeech_neverSelfCommits_theWallCapOwnsThatClock() {
        // 3.7: the segmenter's own maxSegmentMs was a DEAD, differently-anchored duplicate of
        // SegmentCapPolicy.MAX_SEGMENT_WALL_MS (segment-start anchor vs last-commit anchor). Two
        // disagreeing wall clocks is how the next diagnosis gets confusing, so there is now
        // exactly one — and it lives at the call site's `else if`, not in here.
        val s = segmenter()
        var t = 0L
        while (t <= 20_000) { assertFalse(s.onAmplitude(5000, t)); t += 500 }
        // A quiet sample far past the OLD 15 s duplicate, but only 100 ms after the last voice:
        // the pause condition is unmet, so this segmenter must stay silent and let the cap fire.
        assertFalse(
            "SpeechSegmenter must no longer own a wall clock — SegmentCapPolicy does",
            s.onAmplitude(100, 20_100),
        )
        assertTrue("the segment is still open", s.hasPendingSpeech())
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
