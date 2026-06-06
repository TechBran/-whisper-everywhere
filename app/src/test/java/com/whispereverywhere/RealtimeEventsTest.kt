package com.whispereverywhere

import com.whispereverywhere.data.api.RealtimeEventFactory
import com.whispereverywhere.data.api.RealtimeEventParser
import com.whispereverywhere.data.api.ServerEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeEventsTest {

    @Test
    fun sessionUpdate_includes_model_and_pcm() {
        val json = RealtimeEventFactory.sessionUpdate(
            model = "gpt-realtime-whisper",
            language = "en"
        )
        assertTrue(json.contains("\"type\":\"session.update\""))
        assertTrue(json.contains("\"transcription\""))
        assertTrue(json.contains("gpt-realtime-whisper"))
        assertTrue(json.contains("\"server_vad\""))
        assertTrue(json.contains("\"en\""))
    }

    @Test
    fun sessionUpdate_omits_language_when_null() {
        val json = RealtimeEventFactory.sessionUpdate(model = "gpt-realtime-whisper", language = null)
        assertTrue(!json.contains("\"language\""))
    }

    @Test
    fun appendAudio_wraps_base64() {
        val json = RealtimeEventFactory.appendAudio("QUJD")
        assertTrue(json.contains("\"type\":\"input_audio_buffer.append\""))
        assertTrue(json.contains("\"audio\":\"QUJD\""))
    }

    @Test
    fun parse_delta_event() {
        val json = """{"type":"conversation.item.input_audio_transcription.delta","delta":"hello"}"""
        val event = RealtimeEventParser.parse(json)
        assertTrue(event is ServerEvent.Delta)
        assertEquals("hello", (event as ServerEvent.Delta).text)
    }

    @Test
    fun parse_completed_event() {
        val json = """{"type":"conversation.item.input_audio_transcription.completed","transcript":"Hello world."}"""
        val event = RealtimeEventParser.parse(json)
        assertTrue(event is ServerEvent.Completed)
        assertEquals("Hello world.", (event as ServerEvent.Completed).text)
    }

    @Test
    fun parse_error_event() {
        val json = """{"type":"error","error":{"message":"bad key"}}"""
        val event = RealtimeEventParser.parse(json)
        assertTrue(event is ServerEvent.Error)
        assertEquals("bad key", (event as ServerEvent.Error).message)
    }

    @Test
    fun parse_unknown_event_is_other() {
        val json = """{"type":"session.updated"}"""
        assertTrue(RealtimeEventParser.parse(json) is ServerEvent.Other)
    }
}
