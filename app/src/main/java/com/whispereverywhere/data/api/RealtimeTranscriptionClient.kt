package com.whispereverywhere.data.api

import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * Streams microphone PCM16 to OpenAI's realtime transcription endpoint over a
 * WebSocket and reports transcription events via [Listener].
 */
class RealtimeTranscriptionClient(
    private val apiKey: String,
    private val model: String = "gpt-realtime-whisper",
) {
    interface Listener {
        fun onOpen()
        fun onDelta(text: String)
        fun onCompleted(text: String)
        fun onError(message: String)
        fun onClosed()
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS) // keep the socket alive while listening
        .readTimeout(0, TimeUnit.MILLISECONDS) // no read timeout on a streaming socket
        .build()

    private var webSocket: WebSocket? = null
    @Volatile private var listener: Listener? = null

    fun connect(language: String?, listener: Listener) {
        this.listener = listener
        val request = Request.Builder()
            // GA realtime transcription interface. The old beta header
            // (OpenAI-Beta: realtime=v1) is no longer supported and is rejected.
            .url("wss://api.openai.com/v1/realtime?intent=transcription")
            .addHeader("Authorization", "Bearer $apiKey")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                ws.send(RealtimeEventFactory.sessionUpdate(model, language))
                listener.onOpen()
            }

            override fun onMessage(ws: WebSocket, text: String) {
                when (val event = RealtimeEventParser.parse(text)) {
                    is ServerEvent.Delta -> listener.onDelta(event.text)
                    is ServerEvent.Completed -> listener.onCompleted(event.text)
                    is ServerEvent.Error -> listener.onError(event.message)
                    is ServerEvent.Other -> { /* session.updated, etc. — ignore */ }
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                listener.onError(t.message ?: "Connection failed")
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                listener.onClosed()
            }
        })
    }

    /** Send one PCM16 chunk. Safe to call rapidly from the recorder thread. */
    fun sendAudio(pcm: ByteArray) {
        val b64 = Base64.encodeToString(pcm, Base64.NO_WRAP)
        webSocket?.send(RealtimeEventFactory.appendAudio(b64))
    }

    /** Commit the buffer (call on stop) so the server emits the final completed event. */
    fun commit() {
        webSocket?.send(RealtimeEventFactory.commit())
    }

    fun close() {
        try {
            webSocket?.close(1000, "client closing")
        } catch (e: Exception) {
            e.printStackTrace()
        }
        webSocket = null
        listener = null
    }
}
