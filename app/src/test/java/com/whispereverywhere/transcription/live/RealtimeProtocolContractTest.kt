package com.whispereverywhere.transcription.live

import com.whispereverywhere.transcription.cloud.FatalKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The shared [RealtimeProtocol] contract, driven against the reference implementation
 * ([OpenAiRealtimeProtocol]) through a fake [SessionControl] + a recording [RealtimeTransport.Listener].
 * Every provider's own suite SPECIALIZES this: [RealtimeProtocol.bootstrap] emits the per-open frames,
 * inbound [RealtimeProtocol.onText] dispatches to the typed sink, and handshake status maps to a
 * [FatalKind] (or null = transient -> the transport reconnects with backoff). Task 1 wires only
 * OpenAI; Tasks 2–3 add the ElevenLabs/Soniox suites against the SAME seam.
 */
class RealtimeProtocolContractTest {

    private class FakeControl : SessionControl {
        val frames = mutableListOf<Frame>()
        override fun send(frame: Frame): Boolean { frames += frame; return true }
        override fun rotate() {}
    }

    private class RecordingListener : RealtimeTransport.Listener {
        val deltas = mutableListOf<Pair<String, String>>()
        val completed = mutableListOf<Pair<String, String>>()
        val committed = mutableListOf<String>()
        val failed = mutableListOf<String>()
        val errors = mutableListOf<Pair<String?, Int>>()
        val fatals = mutableListOf<Pair<FatalKind, Int>>()
        var connects = 0
        var disconnects = 0
        override fun onConnected() { connects++ }
        override fun onDelta(itemId: String, text: String) { deltas += itemId to text }
        override fun onCompleted(itemId: String, transcript: String) { completed += itemId to transcript }
        override fun onCommitted(itemId: String) { committed += itemId }
        override fun onTranscriptionFailed(itemId: String) { failed += itemId }
        override fun onErrorEvent(code: String?, messageLength: Int) { errors += code to messageLength }
        override fun onDisconnected() { disconnects++ }
        override fun onFatal(kind: FatalKind, code: Int) { fatals += kind to code }
        /** Total sink dispatches, so an "ignored" frame can be proven to touch nothing. */
        val dispatchCount get() =
            deltas.size + completed.size + committed.size + failed.size + errors.size + fatals.size
    }

    private val sink = RecordingListener()
    private val control = FakeControl()
    private val protocol = OpenAiRealtimeProtocol().apply { bind(control, sink) }

    @Test fun bootstrap_emits_exactly_one_session_update() {
        val frames = protocol.bootstrap("sk-x", null).map { (it as Frame.Text).json }
        assertEquals(listOf(RealtimeEvents.sessionUpdate()), frames)
    }

    @Test fun onText_delta_dispatches_onDelta() {
        protocol.onText(
            """{"type":"conversation.item.input_audio_transcription.delta","item_id":"i1","delta":"he"}""",
        )
        assertEquals(listOf("i1" to "he"), sink.deltas)
    }

    @Test fun onText_completed_dispatches_onCompleted() {
        protocol.onText(
            """{"type":"conversation.item.input_audio_transcription.completed","item_id":"i2","transcript":"hello"}""",
        )
        assertEquals(listOf("i2" to "hello"), sink.completed)
    }

    @Test fun onText_committed_dispatches_onCommitted() {
        protocol.onText("""{"type":"input_audio_buffer.committed","item_id":"i3"}""")
        assertEquals(listOf("i3"), sink.committed)
    }

    @Test fun onText_failed_dispatches_onTranscriptionFailed() {
        protocol.onText(
            """{"type":"conversation.item.input_audio_transcription.failed","item_id":"i4"}""",
        )
        assertEquals(listOf("i4"), sink.failed)
    }

    @Test fun onText_error_dispatches_length_only_never_content() {
        protocol.onText("""{"type":"error","error":{"code":"bad","message":"secret detail"}}""")
        // Only the code + message LENGTH cross the seam — never the content.
        assertEquals(listOf<Pair<String?, Int>>("bad" to "secret detail".length), sink.errors)
    }

    @Test fun onText_ack_and_unknown_and_malformed_dispatch_nothing() {
        protocol.onText("""{"type":"session.updated"}""") // benign ack
        protocol.onText("""{"type":"response.done"}""")    // unknown/forward-compat type
        protocol.onText("not json at all")                 // malformed
        assertEquals(0, sink.dispatchCount)
    }

    @Test fun classifyFatal_maps_handshake_status_to_kind_or_transient() {
        assertEquals(FatalKind.INVALID_KEY, protocol.classifyFatal(401))
        assertEquals(FatalKind.FORBIDDEN, protocol.classifyFatal(403))
        assertEquals(FatalKind.OUT_OF_CREDIT, protocol.classifyFatal(429))
        assertNull("5xx / network is transient -> reconnect, not fatal", protocol.classifyFatal(500))
    }
}
