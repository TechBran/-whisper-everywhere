package com.whispereverywhere.audio

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AudioArbiterTest {

    private var captureStops = 0
    private var speechStops = 0

    @Before
    fun setUp() {
        AudioArbiter.reset()
        captureStops = 0
        speechStops = 0
        AudioArbiter.stopCaptureGracefully = { captureStops++ }
        AudioArbiter.stopSpeech = { speechStops++ }
    }

    @After
    fun tearDown() = AudioArbiter.reset()

    @Test
    fun speak_whenIdle_granted_noSideEffects() {
        assertTrue(AudioArbiter.requestSpeak())
        assertEquals(0, captureStops)
        assertEquals(0, speechStops)
    }

    @Test
    fun speak_whileCapturing_denied_andCaptureAskedToFinish() {
        AudioArbiter.isCapturing = { true }
        assertFalse(AudioArbiter.requestSpeak())
        assertEquals(1, captureStops)
        assertEquals("speech must not be touched", 0, speechStops)
    }

    @Test
    fun speak_whileAlreadySpeaking_granted_afterStoppingOldSpeech() {
        AudioArbiter.isSpeaking = { true }
        assertTrue(AudioArbiter.requestSpeak())
        assertEquals(1, speechStops)
    }

    @Test
    fun capture_whenIdle_granted_noSideEffects() {
        assertTrue(AudioArbiter.requestCapture())
        assertEquals(0, captureStops)
        assertEquals(0, speechStops)
    }

    @Test
    fun capture_whileSpeaking_granted_speechStoppedFirst() {
        AudioArbiter.isSpeaking = { true }
        assertTrue(AudioArbiter.requestCapture())
        assertEquals(1, speechStops)
        assertEquals(0, captureStops)
    }
}
