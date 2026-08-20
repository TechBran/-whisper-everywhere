package com.whispereverywhere.transcription

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
}
