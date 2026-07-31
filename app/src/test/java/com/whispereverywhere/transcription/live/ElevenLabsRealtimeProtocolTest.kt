package com.whispereverywhere.transcription.live

import com.whispereverywhere.transcription.cloud.FatalKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * ElevenLabs Scribe v2 Realtime behind the [RealtimeProtocol] seam, driven through a fake
 * [SessionControl] + a recording [RealtimeTransport.Listener], with VERBATIM doc JSON fed to the real
 * [ElevenLabsEvents] parser. Server-driven (`commit_strategy=vad`): every chunk streams immediately
 * commit:false, partials stream as `onDelta`, and a `committed_transcript` mints a synthetic id,
 * binds it (`onCommitted`), then resolves it (`onCompleted`) in one event — no fold, no client commit,
 * no timeout. The shared bootstrap/dispatch/fatal contract lives in [RealtimeProtocolContractTest].
 */
class ElevenLabsRealtimeProtocolTest {

    /** Records every frame the protocol sends; [accept] models backpressure/socket-down (false). */
    private class RecordingControl(private val accept: Boolean = true) : SessionControl {
        val frames = mutableListOf<Frame>()
        var rotates = 0
        override fun send(frame: Frame): Boolean { frames += frame; return accept }
        override fun rotate() { rotates++ }
        val texts get() = frames.map { (it as Frame.Text).json }
    }

    /** Records deltas/completions/binds in a single ordered log so ordering claims are provable. */
    private class RecordingListener : RealtimeTransport.Listener {
        val deltas = mutableListOf<Pair<String, String>>()
        val completed = mutableListOf<Pair<String, String>>()
        val committed = mutableListOf<String>()
        val failed = mutableListOf<String>()
        val errors = mutableListOf<Pair<String?, Int>>()
        val fatals = mutableListOf<Pair<FatalKind, Int>>()
        val order = mutableListOf<String>()
        override fun onConnected() {}
        override fun onDelta(itemId: String, text: String) { deltas += itemId to text; order += "delta:$text" }
        override fun onCompleted(itemId: String, transcript: String) { completed += itemId to transcript; order += "completed:$itemId" }
        override fun onCommitted(itemId: String) { committed += itemId; order += "committed:$itemId" }
        override fun onTranscriptionFailed(itemId: String) { failed += itemId }
        override fun onErrorEvent(code: String?, messageLength: Int) { errors += code to messageLength }
        override fun onDisconnected() {}
        override fun onFatal(kind: FatalKind, code: Int) { fatals += kind to code }
    }

    private val control = RecordingControl()
    private val sink = RecordingListener()

    private fun protocol(ctrl: SessionControl = control): ElevenLabsRealtimeProtocol =
        ElevenLabsRealtimeProtocol().apply { bind(ctrl, sink) }

    // ---- codec shape (verbatim wire) ------------------------------------------------------------

    @Test fun audio_chunk_json_shape_is_exact() {
        assertEquals(
            """{"message_type":"input_audio_chunk","audio_base_64":"YWJj","commit":true,"sample_rate":16000}""",
            ElevenLabsEvents.audioChunk("YWJj", commit = true),
        )
    }

    // ---- wire facts -----------------------------------------------------------------------------

    @Test fun endpoint_is_the_pinned_scribe_v2_realtime_url_with_server_vad_commit_strategy() {
        assertEquals(
            "wss://api.elevenlabs.io/v1/speech-to-text/realtime?model_id=scribe_v2_realtime&commit_strategy=vad",
            protocol().endpoint,
        )
    }

    @Test fun upgrade_header_is_the_bare_xi_api_key() {
        // NOT a Bearer scheme — the value is the raw key (ProviderCatalog: ElevenLabs sends it bare).
        assertEquals(listOf("xi-api-key" to "el-secret"), protocol().upgradeHeaders("el-secret"))
    }

    @Test fun elevenlabs_does_not_use_the_tolerant_beta_retry() {
        assertFalse(protocol().tolerant4xxRetry)
    }

    @Test fun bootstrap_sends_no_config_frame() {
        assertTrue(protocol().bootstrap("el-secret", "en").isEmpty())
    }

    // ---- deltas are preview only, streaming as spoken -------------------------------------------

    @Test fun partial_transcript_dispatches_onDelta_and_never_binds_or_completes() {
        val p = protocol()
        p.onText("""{"message_type":"partial_transcript","text":"hello wor"}""")
        assertEquals(listOf("" to "hello wor"), sink.deltas)
        assertTrue("a partial never binds a turn", sink.committed.isEmpty())
        assertTrue("a partial never completes a turn", sink.completed.isEmpty())
    }

    // ---- every chunk streams immediately, commit:false (no fold, no client commit) --------------

    @Test fun every_append_streams_immediately_commit_false() {
        val p = protocol()
        val a = pcm(shortArrayOf(1, 2)); val b = pcm(shortArrayOf(3, 4))
        assertTrue(p.onAppend(a))
        assertTrue(p.onAppend(b))
        assertEquals(
            listOf(
                ElevenLabsEvents.audioChunk(b64(a), commit = false),
                ElevenLabsEvents.audioChunk(b64(b), commit = false),
            ),
            control.texts,
        )
        assertTrue("no synthetic bind from appends alone", sink.committed.isEmpty())
    }

    @Test fun onCommit_is_a_server_driven_no_op_that_sends_no_client_commit() {
        val p = protocol()
        p.onAppend(pcm(shortArrayOf(1, 2)))
        val framesBefore = control.frames.size
        assertTrue("onCommit succeeds (never blocks the sender)", p.onCommit())
        assertEquals("onCommit sends nothing — the server commits under VAD", framesBefore, control.frames.size)
        assertTrue("no client commit:true frame ever leaves", control.texts.none { it.contains("\"commit\":true") })
        assertTrue("onCommit does not bind a turn", sink.committed.isEmpty())
    }

    // ---- committed_transcript = boundary + completion in one event, resolving exactly once -------

    @Test fun committed_transcript_binds_then_completes_a_synthetic_id_in_order() {
        val p = protocol()
        p.onText("""{"message_type":"committed_transcript","text":"hello world"}""")
        val id = sink.committed.single()
        assertEquals("onCommitted strictly precedes onCompleted for the same id", listOf("committed:$id", "completed:$id"), sink.order)
        assertEquals(listOf(id to "hello world"), sink.completed)
    }

    @Test fun a_delta_then_a_committed_transcript_orders_the_delta_before_the_completion() {
        val p = protocol()
        p.onText("""{"message_type":"partial_transcript","text":"hello wor"}""")
        p.onText("""{"message_type":"committed_transcript","text":"hello world"}""")
        val id = sink.committed.single()
        assertEquals(
            "the delta precedes the boundary and the completion",
            listOf("delta:hello wor", "committed:$id", "completed:$id"),
            sink.order,
        )
    }

    @Test fun each_committed_transcript_gets_a_fresh_distinct_id() {
        val p = protocol()
        p.onText("""{"message_type":"committed_transcript","text":"one"}""")
        p.onText("""{"message_type":"committed_transcript","text":"two"}""")
        assertEquals(2, sink.committed.size)
        val (id1, id2) = sink.committed[0] to sink.committed[1]
        assertTrue("each turn gets a distinct synthetic id", id1 != id2)
        assertEquals(listOf(id1 to "one", id2 to "two"), sink.completed)
    }

    // ---- errors carry length only ---------------------------------------------------------------

    @Test fun input_error_dispatches_code_and_length_only_never_content() {
        val p = protocol()
        val secret = "your key lacks the scope for realtime speech-to-text"
        p.onText("""{"message_type":"input_error","message":"$secret"}""")
        assertEquals(listOf<Pair<String?, Int>>("input_error" to secret.length), sink.errors)
        // The message content never crosses the seam — only its length.
        assertTrue(sink.errors.none { (_, len) -> len != secret.length })
        assertTrue("no delta/complete/bind from an error", sink.deltas.isEmpty() && sink.completed.isEmpty())
    }

    @Test fun unknown_and_malformed_frames_dispatch_nothing() {
        val p = protocol()
        p.onText("""{"message_type":"session_started"}""") // forward-compat unknown
        p.onText("not json at all")                         // malformed
        assertTrue(
            sink.deltas.isEmpty() && sink.completed.isEmpty() && sink.committed.isEmpty() &&
                sink.failed.isEmpty() && sink.errors.isEmpty(),
        )
    }

    // ---- handshake status -> fatal --------------------------------------------------------------

    @Test fun classifyFatal_maps_handshake_status_to_kind_or_transient() {
        val p = protocol()
        assertEquals(FatalKind.INVALID_KEY, p.classifyFatal(401))
        assertEquals(FatalKind.INVALID_KEY, p.classifyFatal(403))
        assertEquals(FatalKind.OUT_OF_CREDIT, p.classifyFatal(429))
        assertNull("5xx / network is transient -> reconnect, not fatal", p.classifyFatal(500))
    }

    // ---- backpressure -----------------------------------------------------------------------------

    @Test fun an_append_that_control_refuses_sheds_the_turn() {
        val refusing = RecordingControl(accept = false)
        val p = protocol(refusing)
        assertFalse("a refused append propagates false to shed the turn", p.onAppend(pcm(shortArrayOf(1, 2))))
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private fun pcm(samples: ShortArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val v = samples[i].toInt()
            out[i * 2] = (v and 0xFF).toByte()
            out[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
}
