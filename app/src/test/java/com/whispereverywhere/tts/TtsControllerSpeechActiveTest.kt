package com.whispereverywhere.tts

import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * TtsController.isSpeechActive is the ONE "our TTS is audible" authority. Its true-path cannot
 * run on the JVM — TtsEngine's constructor builds a Handler(Looper.getMainLooper()), so no unit
 * test can ever construct the engine — but the null-engine contract must be pinned: a process
 * where no read has ever started reports NOT speaking (false, never a crash). The set/clear
 * behavior delegates to TtsEngine.speaking (set synchronously in speak(), cleared in its
 * executor task's finally on completion AND on error, cleared instantly by stop(), and
 * generation-guarded against a superseded read clearing a newer one) — owner-checked on-device.
 */
class TtsControllerSpeechActiveTest {

    @Test fun no_engine_means_not_speaking() {
        // No JVM test can create the engine (see class kdoc), so the singleton's engine field
        // is necessarily null here regardless of test ordering.
        assertFalse(TtsController.isSpeechActive())
    }
}
