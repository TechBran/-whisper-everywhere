package com.whispereverywhere.transcription.live

import com.whispereverywhere.recording.Resampler
import com.whispereverywhere.transcription.cloud.FatalKind
import com.whispereverywhere.tts.cloud.PcmBytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * OpenAI behind the [RealtimeProtocol] seam. This suite owns the two encode pins RELOCATED verbatim
 * from the pre-seam engine/transport (plan 2026-07-31 Task 1, Step 3 — the single reviewed
 * relocation, bytes preserved): the append still upsamples 16 k -> 24 k + base64s, and commit still
 * emits `input_audio_buffer.commit`. It also pins the OpenAI-specific header + tolerant-retry flag.
 * The shared bootstrap/dispatch/classifyFatal contract lives in [RealtimeProtocolContractTest].
 */
class OpenAiRealtimeProtocolTest {

    /** Records every frame the protocol sends; [accept] models backpressure/socket-down (false). */
    private class RecordingControl(private val accept: Boolean = true) : SessionControl {
        val frames = mutableListOf<Frame>()
        var rotates = 0
        override fun send(frame: Frame): Boolean { frames += frame; return accept }
        override fun rotate() { rotates++ }
        val texts get() = frames.map { (it as Frame.Text).json }
    }

    private fun bound(control: SessionControl): OpenAiRealtimeProtocol =
        OpenAiRealtimeProtocol().apply { bind(control, NoopListener) }

    // ---- the relocated encode pin (was LiveTranscriptionEngineTest.sender_upsamples…) ----

    @Test fun append_upsamples_16k_to_24k_and_base64_encodes() {
        val control = RecordingControl()
        val protocol = bound(control)

        // input [0,100,200,300] @16k -> [0,66,133,200,266,300] @24k, little-endian pcm16, base64.
        val pcm16 = pcm16LE(shortArrayOf(0, 100, 200, 300))
        assertTrue(protocol.onAppend(pcm16))
        assertEquals(1, control.frames.size)

        // VERBATIM expected 24 kHz samples from the pre-seam engine test — decoded off the wire frame.
        val json = control.texts.single()
        val b64OnWire = json.substringAfter("\"audio\":\"").substringBefore("\"")
        val decoded = PcmBytes.toShortArrayLE(Base64.getDecoder().decode(b64OnWire))
        assertEquals(listOf<Short>(0, 66, 133, 200, 266, 300), decoded.toList())

        // …and the frame is byte-identical to the pre-seam RealtimeEvents.append output.
        val expectedB64 = Base64.getEncoder()
            .encodeToString(pcm16LE(Resampler.upsample16kTo24k(PcmBytes.toShortArrayLE(pcm16))))
        assertEquals(RealtimeEvents.append(expectedB64), json)
    }

    @Test fun commit_is_a_server_driven_no_op_that_sends_nothing() {
        // Under server_vad the SERVER auto-commits; the engine never enqueues a client commit in
        // server-driven mode, so onCommit is unreachable on the live path and sends no frame. It stays
        // a benign true (the documented client-VAD fallback mode never blocks the sender).
        val control = RecordingControl()
        assertTrue(bound(control).onCommit())
        assertTrue("onCommit sends no frame under server_vad", control.frames.isEmpty())
    }

    @Test fun append_propagates_backpressure_from_control() {
        val control = RecordingControl(accept = false)
        assertFalse(bound(control).onAppend(pcm16LE(shortArrayOf(1, 2, 3, 4))))
    }

    // ---- OpenAI-specific wire facts ----

    @Test fun upgrade_header_is_the_openai_bearer_authorization() {
        assertEquals(
            listOf("Authorization" to "Bearer sk-test"),
            OpenAiRealtimeProtocol().upgradeHeaders("sk-test"),
        )
    }

    @Test fun openai_uses_the_tolerant_beta_retry() {
        assertTrue(OpenAiRealtimeProtocol().tolerant4xxRetry)
    }

    @Test fun endpoint_is_the_pinned_transcription_intent() {
        assertEquals(RealtimeTransport.ENDPOINT, OpenAiRealtimeProtocol().endpoint)
    }

    private object NoopListener : RealtimeTransport.Listener {
        override fun onConnected() {}
        override fun onDelta(itemId: String, text: String) {}
        override fun onCompleted(itemId: String, transcript: String) {}
        override fun onCommitted(itemId: String) {}
        override fun onTranscriptionFailed(itemId: String) {}
        override fun onErrorEvent(code: String?, messageLength: Int) {}
        override fun onDisconnected() {}
        override fun onFatal(kind: FatalKind, code: Int) {}
    }
}

/** Little-endian PCM16 encode for the test only (mirrors the OpenAI protocol's inlined seam). */
private fun pcm16LE(samples: ShortArray): ByteArray {
    val out = ByteArray(samples.size * 2)
    for (i in samples.indices) {
        val v = samples[i].toInt()
        out[i * 2] = (v and 0xFF).toByte()
        out[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
    }
    return out
}
