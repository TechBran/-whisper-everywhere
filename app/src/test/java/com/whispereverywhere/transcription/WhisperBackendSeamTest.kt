package com.whispereverywhere.transcription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the WhisperBackend interface DEFAULTS every existing fake and any future backend
 * inherit. These defaults are what keep the 3.6.0 additions (language pinning, partial
 * streaming) opt-in: a backend that doesn't implement them behaves byte-for-byte like 3.5.0.
 */
class WhisperBackendSeamTest {

    /** Minimal backend: overrides ONLY the 3.5.0 surface. */
    internal class MinimalBackend : WhisperBackend {
        override fun load(modelPath: String): Long = 1L
        override fun transcribe(ctx: Long, samples: FloatArray, lang: String?, useVad: Boolean): String = "text"
        override fun release(ctx: Long) = Unit
    }

    @Test
    fun detectedLanguage_defaultsToNull_soNothingPinsAccidentally() {
        assertNull(MinimalBackend().detectedLanguage(1L))
    }

    @Test
    fun transcribeStreaming_defaultsToPlainTranscribe_withNoDeltas() {
        var callbacks = 0
        val text = MinimalBackend().transcribeStreaming(1L, FloatArray(4), lang = null) { callbacks++ }
        assertEquals("text", text)
        assertEquals(0, callbacks)   // the default streams nothing: byte-for-byte 3.5.0 behavior
    }

    @Test
    fun lastSegmentStats_defaultsToNull_soTheTimingLineDegradesInsteadOfLying() {
        // A backend with no native counters must report NOTHING, not zeros: `ctxFrames=0` is a
        // real and meaningful reading (whisper_full never ran), so a fake must not forge it.
        assertNull(MinimalBackend().lastSegmentStats(1L))
    }

    @Test
    fun nativeSegmentStats_carriesTheThreeNativeCounters() {
        val s = NativeSegmentStats(ctxFrames = 512, vadInSamples = 48_000, vadOutSamples = 32_000)
        assertEquals(512, s.ctxFrames)
        assertEquals(48_000, s.vadInSamples)
        assertEquals(32_000, s.vadOutSamples)
    }
}
