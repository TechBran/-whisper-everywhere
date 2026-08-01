package com.whispereverywhere.transcription.live

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

/**
 * Pure JVM — no framework, no android.*. Pins the OpenAI Realtime codec against the live-doc
 * shapes verified 2026-07-30.
 */
class RealtimeEventParserTest {

    @Test fun parses_delta_itemId_and_text() {
        val json = """
            {"type":"conversation.item.input_audio_transcription.delta","item_id":"it_7","delta":"hel"}
        """.trimIndent()
        assertEquals(Inbound.Delta("it_7", "hel"), RealtimeEventParser.parse(json))
    }

    @Test fun parses_completed_itemId_and_transcript() {
        val json = """
            {"type":"conversation.item.input_audio_transcription.completed","item_id":"it_7","transcript":"hello there"}
        """.trimIndent()
        assertEquals(Inbound.Completed("it_7", "hello there"), RealtimeEventParser.parse(json))
    }

    @Test fun parses_error_event_to_fatal_with_code() {
        // The engine maps `code` -> FatalKind; only the message LENGTH is ever logged, not content.
        val json = """
            {"type":"error","error":{"type":"invalid_request_error","code":"invalid_api_key","message":"Incorrect API key provided"}}
        """.trimIndent()
        val e = RealtimeEventParser.parse(json) as Inbound.Error
        assertEquals("invalid_api_key", e.code)
        assertEquals("Incorrect API key provided", e.message)
    }

    @Test fun ack_events_are_recognized_not_dispatched_as_content() {
        assertEquals(Inbound.Ack("session.updated"), RealtimeEventParser.parse("""{"type":"session.updated"}"""))
    }

    @Test fun committed_ack_carries_the_item_id_for_binding() {
        // The commit ack is the ordered signal the engine binds item_id<->seq on.
        assertEquals(
            Inbound.Committed("it_1"),
            RealtimeEventParser.parse("""{"type":"input_audio_buffer.committed","item_id":"it_1"}"""),
        )
        // Without an item_id there is nothing to bind: it degrades to a benign ack, never crashes.
        assertEquals(
            Inbound.Ack("input_audio_buffer.committed"),
            RealtimeEventParser.parse("""{"type":"input_audio_buffer.committed"}"""),
        )
    }

    @Test fun transcription_failed_parses_to_a_per_item_failure() {
        val json =
            """{"type":"conversation.item.input_audio_transcription.failed","item_id":"it_9","error":{"message":"x"}}"""
        assertEquals(Inbound.Failed("it_9"), RealtimeEventParser.parse(json))
    }

    @Test fun unknown_event_type_is_ignored_not_thrown() {
        // Forward-compat: the stream carries many events we do not model. None may crash the loop.
        assertNull(RealtimeEventParser.parse("""{"type":"response.output_audio.delta","x":1}"""))
        assertNull(RealtimeEventParser.parse("not json at all"))
        assertNull(RealtimeEventParser.parse("""{"no_type_field":true}"""))
    }

    @Test fun outbound_session_update_shape_is_exact() {
        // Pinned VERBATIM: pcm @ 24000, turn_detection:server_vad (the 2026-07-31 inversion), and
        // model **gpt-transcribe** — the turn-based realtime model. The live server proved
        // (on-device 2026-07-31) that gpt-live-transcribe is the continuous/turn-free model and
        // REJECTS server_vad with an in-band `invalid_value`, silently degrading every session to
        // the local fallback. gpt-transcribe accepts server_vad, creates items mid-speech, and
        // streams deltas per item — and is ~4x cheaper ($0.0045 vs $0.017/min).
        val expected =
            """{"type":"session.update","session":{"type":"transcription","audio":{"input":""" +
                """{"format":{"type":"audio/pcm","rate":24000},"transcription":""" +
                """{"model":"gpt-transcribe"},"turn_detection":""" +
                """{"type":"server_vad","threshold":0.5,"prefix_padding_ms":300,"silence_duration_ms":500}}}}}"""
        assertEquals(expected, RealtimeEvents.sessionUpdate())
    }

    @Test fun append_base64_roundtrips() {
        // java.util.Base64, NOT android.util.Base64 (which returns null under isReturnDefaultValues).
        val ramp = ByteArray(300) { (it % 256).toByte() }
        val b64 = Base64.getEncoder().encodeToString(ramp)
        val json = RealtimeEvents.append(b64)

        val audioField = Json.parseToJsonElement(json).jsonObject["audio"]!!.jsonPrimitive.content
        assertArrayEquals(ramp, Base64.getDecoder().decode(audioField))
    }

    @Test fun commit_event_shape_is_exact() {
        assertEquals("""{"type":"input_audio_buffer.commit"}""", RealtimeEvents.commit())
    }
}
