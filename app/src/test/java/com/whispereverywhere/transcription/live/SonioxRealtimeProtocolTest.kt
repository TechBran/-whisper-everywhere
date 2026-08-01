package com.whispereverywhere.transcription.live

import com.whispereverywhere.transcription.cloud.FatalKind
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Soniox stt-rt-v5 behind the [RealtimeProtocol] seam, driven through a fake [SessionControl] + a
 * recording [RealtimeTransport.Listener], with VERBATIM doc JSON fed to the real [SonioxEvents]
 * parser. Server-driven (`enable_endpoint_detection:true`): non-final tokens stream as preview, final
 * tokens accumulate, and the `<end>` token is the turn boundary — it snapshots the finals into a fresh
 * synthetic id, binds it (`onCommitted`) then resolves it (`onCompleted`). This suite pins the
 * key-bearing config shape (now incl. endpoint detection), raw-binary audio, the `<end>`-driven turn,
 * zero-final empty completion, the numeric error map, and the finalize/rotate session cycling. The
 * no-log discipline is pinned separately in [SonioxNoLogDisciplineTest].
 */
class SonioxRealtimeProtocolTest {

    /** Records every frame; [accept] models backpressure/socket-down (false). */
    private class RecordingControl(private val accept: Boolean = true) : SessionControl {
        val frames = mutableListOf<Frame>()
        var rotates = 0
        override fun send(frame: Frame): Boolean { frames += frame; return accept }
        override fun rotate() { rotates++ }
        val texts get() = frames.filterIsInstance<Frame.Text>().map { it.json }
        val binaries get() = frames.filterIsInstance<Frame.Binary>().map { it.bytes.toByteArray() }
    }

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

    private fun protocol(ctrl: SessionControl = control): SonioxRealtimeProtocol =
        SonioxRealtimeProtocol().apply { bind(ctrl, sink) }

    // ---- wire facts -----------------------------------------------------------------------------

    @Test fun endpoint_is_the_pinned_stt_rt_v5_url() {
        assertEquals("wss://stt-rt.soniox.com/transcribe-websocket", protocol().endpoint)
    }

    @Test fun the_key_rides_config_so_there_is_no_upgrade_header() {
        assertTrue(protocol().upgradeHeaders("sx-secret").isEmpty())
    }

    @Test fun soniox_does_not_use_the_tolerant_beta_retry() {
        assertFalse(protocol().tolerant4xxRetry)
    }

    // ---- the tolerant-CONFIG cascade (live server 400'd the documented full config, 2026-07-31) ----

    @Test fun a_pre_token_400_cascades_once_to_the_reduced_config_then_latches_visibly() {
        val p = protocol()
        p.bootstrap("sx-secret", null)

        // The live server rejects the FULL config before any tokens: one SILENT rotate, no fatal.
        p.onText("""{"error_code":400,"error_message":"bad config"}""")
        assertEquals("one silent rotation", 1, control.rotates)
        assertTrue("no fatal on the first config rejection", sink.fatals.isEmpty())

        // The reopened session's config OMITS enable_endpoint_detection entirely.
        val reduced = (p.bootstrap("sx-secret", null).single() as Frame.Text).json
        assertEquals(
            """{"api_key":"sx-secret","model":"stt-rt-v5","audio_format":"s16le","sample_rate":16000,"num_channels":1}""",
            reduced,
        )

        // A second pre-token 400 is believed: VISIBLE latch, never silent degradation.
        p.onText("""{"error_code":400,"error_message":"still bad"}""")
        assertEquals(listOf(FatalKind.MODEL_UNAVAILABLE to 400), sink.fatals)
        assertEquals("no further rotation", 1, control.rotates)
    }

    @Test fun a_mid_session_400_after_tokens_stays_benign() {
        // A working stream that hiccups a 400 must not be killed or cascaded — only PRE-token
        // 400s are config rejections.
        val p = protocol()
        p.bootstrap("sx-secret", null)
        p.onText("""{"tokens":[{"text":"hello","is_final":false}]}""")
        p.onText("""{"error_code":400,"error_message":"odd"}""")
        assertEquals(0, control.rotates)
        assertTrue(sink.fatals.isEmpty())
    }

    @Test fun reduced_config_sticks_across_reopens() {
        val p = protocol()
        p.bootstrap("sx-secret", null)
        p.onText("""{"error_code":400,"error_message":"bad config"}""")
        // Every later open (reconnects, rotations) keeps the reduced shape — a server that
        // rejected the field once will reject it again.
        repeat(2) {
            val json = (p.bootstrap("sx-secret", "en").single() as Frame.Text).json
            assertFalse("endpoint field must stay omitted", json.contains("enable_endpoint_detection"))
        }
    }

    @Test fun no_reachable_toString_can_render_the_key() {
        // The Config holder is deliberately NOT a data class: a synthesized toString() would
        // render api_key in cleartext, one debug interpolation away from a leak. The wire frame
        // is the ONE legal carrier; every object's toString must be key-free. Walk the protocol
        // object graph reflectively and assert none of it stringifies the secret.
        val key = "sx-SECRET-9137"
        val p = protocol()
        val frames = p.bootstrap(key, "en")
        val seen = mutableSetOf<Any>()
        fun sweep(obj: Any?, depth: Int) {
            if (obj == null || depth > 4 || !seen.add(obj)) return
            if (obj !is Frame.Text) {  // the wire frame is the one legal carrier
                assertFalse(
                    "toString of ${obj.javaClass.name} leaks the key",
                    obj.toString().contains(key),
                )
            }
            obj.javaClass.declaredFields.forEach { f ->
                if (f.type.isPrimitive) return@forEach
                f.isAccessible = true
                runCatching { sweep(f.get(obj), depth + 1) }
            }
        }
        sweep(p, 0)
        frames.forEach { sweep(it, 0) }
    }

    // ---- config bootstrap shape (verbatim wire; endpoint detection on; language_hints only when given) --

    @Test fun bootstrap_config_shape_is_exact_without_language() {
        val frame = protocol().bootstrap("sx-secret", null).single() as Frame.Text
        assertEquals(
            """{"api_key":"sx-secret","model":"stt-rt-v5","audio_format":"s16le","sample_rate":16000,"num_channels":1,"enable_endpoint_detection":true}""",
            frame.json,
        )
    }

    @Test fun bootstrap_config_shape_adds_language_hints_only_when_a_language_is_given() {
        val frame = protocol().bootstrap("sx-secret", "en").single() as Frame.Text
        assertEquals(
            """{"api_key":"sx-secret","model":"stt-rt-v5","audio_format":"s16le","sample_rate":16000,"num_channels":1,"enable_endpoint_detection":true,"language_hints":["en"]}""",
            frame.json,
        )
    }

    // ---- audio is RAW s16le BINARY (no base64, no resample) --------------------------------------

    @Test fun onAppend_sends_a_binary_frame_whose_bytes_equal_the_input_pcm() {
        val p = protocol()
        val pcm = pcm(shortArrayOf(0, 100, -200, 32000))
        assertTrue(p.onAppend(pcm))
        assertTrue("no text frame for audio — Soniox streams binary", control.texts.isEmpty())
        assertArrayEquals("bytes on the wire are the raw PCM, unchanged", pcm, control.binaries.single())
    }

    @Test fun onAppend_propagates_backpressure_from_control() {
        val p = protocol(RecordingControl(accept = false))
        assertFalse(p.onAppend(pcm(shortArrayOf(1, 2))))
    }

    @Test fun onCommit_is_a_server_driven_no_op() {
        // No client commit exists — the server marks turns via <end>. onCommit sends nothing, binds
        // nothing, and never blocks the sender.
        val p = protocol()
        assertTrue(p.onCommit())
        assertTrue(control.frames.isEmpty())
        assertTrue(sink.committed.isEmpty() && sink.completed.isEmpty())
    }

    // ---- preview: accumulated finals + current non-finals; finals persist, non-finals do not -----

    @Test fun mixed_tokens_preview_is_accumulated_finals_plus_current_non_finals() {
        val p = protocol()
        p.onText("""{"tokens":[{"text":"hello ","is_final":true},{"text":"wor","is_final":false}]}""")
        assertEquals(listOf("" to "hello wor"), sink.deltas)

        // A later message's non-finals REPLACE the previous ones (not accumulate); finals persist.
        p.onText("""{"tokens":[{"text":"world","is_final":false}]}""")
        assertEquals("" to "hello world", sink.deltas.last())

        // Finalizing "world" keeps it; the preview is now all-final.
        p.onText("""{"tokens":[{"text":"world","is_final":true}]}""")
        assertEquals("" to "hello world", sink.deltas.last())
        assertTrue("preview never binds or completes a turn", sink.completed.isEmpty() && sink.committed.isEmpty())
    }

    @Test fun bare_final_tokens_do_not_melt_in_the_assembled_turn() {
        val p = protocol()
        // Two BARE final tokens (no baked spacing) accumulate across messages, then <end> cuts the turn.
        // The assembled turn is what gets injected, so it must read "Hello world", not "Helloworld".
        p.onText("""{"tokens":[{"text":"Hello","is_final":true}]}""")
        p.onText("""{"tokens":[{"text":"world","is_final":true}]}""")
        p.onText("""{"tokens":[{"text":"<end>","is_final":true}]}""")
        assertEquals(listOf("1" to "Hello world"), sink.completed)
    }

    // ---- the <end> token IS the turn boundary ----------------------------------------------------

    @Test fun an_end_token_binds_then_completes_the_assembled_finals_and_clears_for_the_next_turn() {
        val p = protocol()
        p.onText("""{"tokens":[{"text":"first turn","is_final":true}]}""")

        // <end> cuts the turn: bind (allocate seq) strictly before completing it with the finals.
        p.onText("""{"tokens":[{"text":"<end>","is_final":true}]}""")
        val id1 = sink.committed.single()
        assertEquals(listOf("committed:$id1", "completed:$id1"), sink.order.filter { it.startsWith("committed") || it.startsWith("completed") })
        assertEquals(listOf(id1 to "first turn"), sink.completed)
        assertTrue("assembling a turn is never a failure", sink.failed.isEmpty())

        // The accumulator is clear: a second turn starts empty and gets a fresh distinct id.
        p.onText("""{"tokens":[{"text":"second turn","is_final":true}]}""")
        p.onText("""{"tokens":[{"text":"<end>","is_final":true}]}""")
        val id2 = sink.committed.last()
        assertTrue("each segment gets a fresh id", id1 != id2)
        assertEquals(listOf(id1 to "first turn", id2 to "second turn"), sink.completed)
    }

    @Test fun a_delta_message_then_an_end_message_orders_deltas_before_the_completion() {
        val p = protocol()
        p.onText("""{"tokens":[{"text":"hello ","is_final":true},{"text":"there","is_final":false}]}""")
        // "there" finalizes, then <end> cuts the turn — all captured server-side, no grace window.
        p.onText("""{"tokens":[{"text":"there","is_final":true},{"text":"<end>","is_final":true}]}""")
        val id = sink.committed.single()
        // The <end> clears the finals accumulator, so the post-boundary preview goes blank (the strip
        // resets for the next turn) — but every delta still precedes the boundary and the completion.
        assertEquals(
            "deltas precede the boundary and completion",
            listOf("delta:hello there", "delta:", "committed:$id", "completed:$id"),
            sink.order,
        )
        assertEquals(listOf(id to "hello there"), sink.completed)
    }

    @Test fun a_zero_final_end_resolves_empty_not_lost() {
        val p = protocol()
        // An <end> with no accumulated finals before it.
        p.onText("""{"tokens":[{"text":"<end>","is_final":true}]}""")
        assertEquals(listOf("1" to ""), sink.completed)
        assertTrue("zero finals is not a hard transcription failure", sink.failed.isEmpty())
    }

    @Test fun the_end_token_never_leaks_into_the_transcript() {
        val p = protocol()
        p.onText("""{"tokens":[{"text":"word","is_final":true},{"text":"<end>","is_final":true}]}""")
        assertEquals(listOf("1" to "word"), sink.completed)
        assertTrue("the <end> marker never appears in any delta", sink.deltas.none { it.second.contains("<end>") })
    }

    // ---- numeric error map (code + length only; session cycling) --------------------------------

    @Test fun error_401_is_invalid_key_fatal() {
        protocol().onText("""{"error_code":401,"error_message":"bad key"}""")
        assertEquals(listOf<Pair<String?, Int>>("401" to "bad key".length), sink.errors)
        assertEquals(listOf(FatalKind.INVALID_KEY to 401), sink.fatals)
    }

    @Test fun error_402_is_out_of_credit_fatal() {
        protocol().onText("""{"error_code":402,"error_message":"no funds"}""")
        assertEquals(listOf(FatalKind.OUT_OF_CREDIT to 402), sink.fatals)
    }

    @Test fun error_403_rotates_once_then_latches_forbidden() {
        val p = protocol()
        p.onText("""{"error_code":403,"error_message":"session expired"}""")
        assertEquals("first 403 rotates the session", 1, control.rotates)
        assertTrue("first 403 does not latch", sink.fatals.isEmpty())

        p.onText("""{"error_code":403,"error_message":"session expired"}""")
        assertEquals("second 403 does not rotate again", 1, control.rotates)
        assertEquals("second 403 latches forbidden", listOf(FatalKind.FORBIDDEN to 403), sink.fatals)
    }

    @Test fun bootstrap_does_not_reset_the_403_counter_so_rotation_stays_bounded() {
        val p = protocol()
        p.onText("""{"error_code":403,"error_message":"x"}""")
        assertEquals(1, control.rotates)
        // A reopen (bootstrap) clears TURN state but NOT the 403 counter — otherwise inband 403s would
        // rotate forever (each successful reopen resets the transport's own reconnect ceiling).
        p.bootstrap("sx-secret", null)
        p.onText("""{"error_code":403,"error_message":"x"}""")
        assertEquals("no second rotation after a reopen", 1, control.rotates)
        assertEquals(listOf(FatalKind.FORBIDDEN to 403), sink.fatals)
    }

    @Test fun reset_clears_the_403_counter_for_the_next_session() {
        val p = protocol()
        p.onText("""{"error_code":403,"error_message":"x"}""")
        p.onText("""{"error_code":403,"error_message":"x"}""") // latched forbidden this session
        p.reset()
        p.onText("""{"error_code":403,"error_message":"x"}""")
        assertEquals("a fresh session gets its own single rotation", 2, control.rotates)
    }

    @Test fun error_413_sends_the_empty_frame_finalize_then_rotates() {
        val p = protocol()
        p.onText("""{"error_code":413,"error_message":"max duration"}""")
        assertEquals("the finalize frame is an empty binary frame", 0, control.binaries.single().size)
        assertEquals(1, control.rotates)
        assertTrue("413 is a session cycle, not a fatal", sink.fatals.isEmpty())
    }

    @Test fun transient_429_and_5xx_neither_latch_nor_rotate() {
        val p = protocol()
        p.onText("""{"error_code":429,"error_message":"slow down"}""")
        p.onText("""{"error_code":503,"error_message":"unavailable"}""")
        assertTrue(sink.fatals.isEmpty())
        assertEquals(0, control.rotates)
        assertEquals("errors still cross as code + length only", listOf("429", "503"), sink.errors.map { it.first })
    }

    @Test fun error_message_content_never_crosses_only_its_length() {
        val secret = "your account key sk-live-DEADBEEF lacks realtime scope"
        protocol().onText("""{"error_code":401,"error_message":"$secret"}""")
        assertEquals(listOf<Pair<String?, Int>>("401" to secret.length), sink.errors)
        assertTrue("no error path emits a delta/completion", sink.deltas.isEmpty() && sink.completed.isEmpty())
    }

    // ---- handshake status -> fatal (Soniox authenticates inband, so this is the rare path) -------

    @Test fun classifyFatal_maps_handshake_status_to_kind_or_transient() {
        val p = protocol()
        assertEquals(FatalKind.INVALID_KEY, p.classifyFatal(401))
        assertEquals(FatalKind.FORBIDDEN, p.classifyFatal(403))
        assertEquals(FatalKind.OUT_OF_CREDIT, p.classifyFatal(429))
        assertNull("5xx / network is transient -> reconnect, not fatal", p.classifyFatal(500))
    }

    // ---- forward-compatibility ------------------------------------------------------------------

    @Test fun unknown_and_malformed_frames_dispatch_nothing() {
        val p = protocol()
        p.onText("""{"keepalive":true}""") // no tokens / finished / error -> ignored
        p.onText("not json at all")
        assertTrue(
            sink.deltas.isEmpty() && sink.completed.isEmpty() && sink.committed.isEmpty() &&
                sink.failed.isEmpty() && sink.errors.isEmpty() && sink.fatals.isEmpty(),
        )
    }

    // ---- helpers --------------------------------------------------------------------------------

    private fun pcm(samples: ShortArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val v = samples[i].toInt()
            out[i * 2] = (v and 0xFF).toByte()
            out[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }
}
