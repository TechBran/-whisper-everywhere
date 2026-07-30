package com.whispereverywhere.transcription.live

import com.whispereverywhere.transcription.cloud.FatalKind
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * The WebSocket lifecycle driven entirely off injected fakes — NO real network. The fake factory
 * hands back the transport's own [WebSocketListener], so the test plays the role of OkHttp: it
 * fires onOpen/onMessage/onFailure and asserts what the transport sent and surfaced.
 */
class RealtimeTransportTest {

    // --- fakes -----------------------------------------------------------------------------------

    private class FakeWebSocket : WebSocket {
        val sent = mutableListOf<String>()
        var closeCode: Int? = null
        var closeCount = 0
        var queued = 0L
        override fun request(): Request = Request.Builder().url("https://api.openai.com/").build()
        override fun queueSize(): Long = queued
        override fun send(text: String): Boolean { sent += text; return true }
        override fun send(bytes: ByteString): Boolean = true
        override fun close(code: Int, reason: String?): Boolean { closeCode = code; closeCount++; return true }
        override fun cancel() {}
    }

    private class FakeFactory : WebSocketFactory {
        val sockets = mutableListOf<FakeWebSocket>()
        val listeners = mutableListOf<WebSocketListener>()
        override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
            val ws = FakeWebSocket()
            sockets += ws
            listeners += listener
            return ws
        }
        val lastSocket get() = sockets.last()
        val lastListener get() = listeners.last()
    }

    private class FakeScheduler : ReconnectScheduler {
        val delays = mutableListOf<Long>()
        private val tasks = mutableListOf<() -> Unit>()
        override fun schedule(delayMs: Long, task: () -> Unit) { delays += delayMs; tasks += task }
        fun runNext() { tasks.removeAt(0).invoke() }
        val lastDelay get() = delays.last()
    }

    private class RecordingListener : RealtimeTransport.Listener {
        val deltas = mutableListOf<Pair<String, String>>()
        val completed = mutableListOf<Pair<String, String>>()
        val errors = mutableListOf<Pair<String?, Int>>()
        val fatals = mutableListOf<Pair<FatalKind, Int>>()
        var connects = 0
        var disconnects = 0
        override fun onConnected() { connects++ }
        override fun onDelta(itemId: String, text: String) { deltas += itemId to text }
        override fun onCompleted(itemId: String, transcript: String) { completed += itemId to transcript }
        override fun onErrorEvent(code: String?, messageLength: Int) { errors += code to messageLength }
        override fun onDisconnected() { disconnects++ }
        override fun onFatal(kind: FatalKind, code: Int) { fatals += kind to code }
    }

    private fun httpResponse(code: Int, bodyText: String? = null): Response {
        val b = Response.Builder()
            .request(Request.Builder().url("https://api.openai.com/").build())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("status $code")
        if (bodyText != null) b.body(bodyText.toResponseBody("text/plain".toMediaType()))
        return b.build()
    }

    private fun deltaJson(itemId: String, text: String) =
        """{"type":"conversation.item.input_audio_transcription.delta","item_id":"$itemId","delta":"$text"}"""

    private fun completedJson(itemId: String, transcript: String) =
        """{"type":"conversation.item.input_audio_transcription.completed","item_id":"$itemId","transcript":"$transcript"}"""

    private class Rig {
        val factory = FakeFactory()
        val scheduler = FakeScheduler()
        val listener = RecordingListener()
        val transport = RealtimeTransport(factory, scheduler, listener)
    }

    // --- tests -----------------------------------------------------------------------------------

    @Test fun onOpen_sends_session_update_once() {
        val r = Rig()
        r.transport.connect("sk-x", null)
        r.factory.lastListener.onOpen(r.factory.lastSocket, httpResponse(101))

        assertEquals(listOf(RealtimeEvents.sessionUpdate()), r.factory.lastSocket.sent)
        assertEquals(1, r.listener.connects)
    }

    @Test fun handshake_failure_logs_status_code_only_and_reports_fatal() {
        val r = Rig()
        r.transport.connect("sk-x", null)
        // A body is attached on purpose: the transport must NOT consume it.
        val resp = httpResponse(401, "SENSITIVE-BODY-should-never-be-read")
        r.factory.lastListener.onFailure(r.factory.lastSocket, IOException("upgrade rejected"), resp)

        assertEquals(listOf(FatalKind.INVALID_KEY to 401), r.listener.fatals)
        assertEquals("fatal must not reconnect", 0, r.scheduler.delays.size)
        assertEquals("fatal is not a transient disconnect", 0, r.listener.disconnects)
        // Body still fully readable => the transport never touched it (status code only).
        assertEquals("SENSITIVE-BODY-should-never-be-read", resp.body?.string())
    }

    @Test fun a_403_is_forbidden_and_a_429_is_out_of_credit() {
        val r1 = Rig().also { it.transport.connect("sk", null) }
        r1.factory.lastListener.onFailure(r1.factory.lastSocket, IOException(), httpResponse(403))
        assertEquals(listOf(FatalKind.FORBIDDEN to 403), r1.listener.fatals)

        val r2 = Rig().also { it.transport.connect("sk", null) }
        r2.factory.lastListener.onFailure(r2.factory.lastSocket, IOException(), httpResponse(429))
        assertEquals(listOf(FatalKind.OUT_OF_CREDIT to 429), r2.listener.fatals)
        assertEquals(0, r2.scheduler.delays.size)
    }

    @Test fun append_and_commit_forwarded_as_correct_events() {
        val r = Rig()
        r.transport.connect("sk-x", null)
        r.factory.lastListener.onOpen(r.factory.lastSocket, httpResponse(101))

        assertTrue(r.transport.sendAppend("QUJD"))
        assertTrue(r.transport.sendCommit())

        assertEquals(
            listOf(RealtimeEvents.sessionUpdate(), RealtimeEvents.append("QUJD"), RealtimeEvents.commit()),
            r.factory.lastSocket.sent,
        )
    }

    @Test fun sendAppend_refuses_when_the_outbound_buffer_is_over_threshold() {
        val r = Rig()
        r.transport.connect("sk-x", null)
        r.factory.lastListener.onOpen(r.factory.lastSocket, httpResponse(101))

        // A live socket whose OkHttp outbound buffer is past the cap must reject the append as
        // backpressure — not silently pile bytes toward OkHttp's 16 MiB hard-cancel.
        r.factory.lastSocket.queued = RealtimeTransport.MAX_OUTBOUND_BYTES + 1
        assertTrue("over-threshold append is refused", !r.transport.sendAppend("QUJD"))

        // Once the buffer drains below the threshold it sends normally again.
        r.factory.lastSocket.queued = 0
        assertTrue(r.transport.sendAppend("QUJD"))
    }

    @Test fun send_without_a_live_socket_returns_false_and_does_not_throw() {
        val r = Rig() // never connected
        assertTrue(!r.transport.sendAppend("QUJD"))
        assertTrue(!r.transport.sendCommit())
    }

    @Test fun inbound_delta_and_completed_dispatched_to_listener() {
        val r = Rig()
        r.transport.connect("sk-x", null)
        r.factory.lastListener.onOpen(r.factory.lastSocket, httpResponse(101))

        r.factory.lastListener.onMessage(r.factory.lastSocket, deltaJson("it_1", "hel"))
        r.factory.lastListener.onMessage(r.factory.lastSocket, completedJson("it_1", "hello"))

        assertEquals(listOf("it_1" to "hel"), r.listener.deltas)
        assertEquals(listOf("it_1" to "hello"), r.listener.completed)
    }

    @Test fun inbound_error_event_surfaces_length_only_never_content() {
        val r = Rig()
        r.transport.connect("sk-x", null)
        r.factory.lastListener.onOpen(r.factory.lastSocket, httpResponse(101))
        val msg = "Incorrect API key provided: sk-secret"
        r.factory.lastListener.onMessage(
            r.factory.lastSocket,
            """{"type":"error","error":{"code":"invalid_api_key","message":"$msg"}}""",
        )
        assertEquals(listOf<Pair<String?, Int>>("invalid_api_key" to msg.length), r.listener.errors)
    }

    @Test fun drop_triggers_backoff_reconnect_then_bootstrap_again() {
        val r = Rig()
        r.transport.connect("sk-x", null)
        val ws1 = r.factory.lastSocket
        r.factory.lastListener.onOpen(ws1, httpResponse(101))
        assertEquals(listOf(RealtimeEvents.sessionUpdate()), ws1.sent)

        r.factory.lastListener.onFailure(ws1, IOException("socket dropped"), null)
        assertEquals(1, r.listener.disconnects)
        assertEquals(500L, r.scheduler.lastDelay)
        assertEquals("reconnect is scheduled, not immediate", 1, r.factory.sockets.size)

        r.scheduler.runNext()
        assertEquals("a new socket was opened", 2, r.factory.sockets.size)
        val ws2 = r.factory.lastSocket
        r.factory.lastListener.onOpen(ws2, httpResponse(101))
        assertEquals("session bootstrap fires again on the new open", listOf(RealtimeEvents.sessionUpdate()), ws2.sent)
    }

    @Test fun repeated_drops_escalate_backoff_capped_exponential() {
        val r = Rig()
        r.transport.connect("sk-x", null)

        r.factory.lastListener.onFailure(r.factory.lastSocket, IOException(), null) // attempt 0
        assertEquals(500L, r.scheduler.lastDelay)
        r.scheduler.runNext()
        r.factory.lastListener.onFailure(r.factory.lastSocket, IOException(), null) // attempt 1
        assertEquals(1000L, r.scheduler.lastDelay)
        r.scheduler.runNext()
        r.factory.lastListener.onFailure(r.factory.lastSocket, IOException(), null) // attempt 2
        assertEquals(2000L, r.scheduler.lastDelay)
    }

    @Test fun reconnect_gives_up_after_the_ceiling_instead_of_looping_forever() {
        val r = Rig()
        r.transport.connect("sk-x", null)
        // Each failure schedules one reconnect, up to the ceiling; onOpen never fires, so these are
        // consecutive failures with no reset.
        repeat(RealtimeTransport.DEFAULT_MAX_RECONNECTS) {
            r.factory.lastListener.onFailure(r.factory.lastSocket, IOException(), null)
            r.scheduler.runNext()
        }
        val scheduled = r.scheduler.delays.size
        assertEquals(RealtimeTransport.DEFAULT_MAX_RECONNECTS, scheduled)

        // One more failure past the ceiling must NOT schedule another reconnect (battery-safe latch),
        // but it still surfaces the disconnect so the engine resolves its turns Lost -> local.
        r.factory.lastListener.onFailure(r.factory.lastSocket, IOException(), null)
        assertEquals("no reconnect past the ceiling", scheduled, r.scheduler.delays.size)
        assertEquals(RealtimeTransport.DEFAULT_MAX_RECONNECTS + 1, r.listener.disconnects)
    }

    @Test fun a_successful_open_resets_the_reconnect_ceiling() {
        val r = Rig()
        r.transport.connect("sk-x", null)
        repeat(RealtimeTransport.DEFAULT_MAX_RECONNECTS) {
            r.factory.lastListener.onFailure(r.factory.lastSocket, IOException(), null)
            r.scheduler.runNext()
        }
        // A successful open clears the consecutive-failure count, so the socket is willing again.
        r.factory.lastListener.onOpen(r.factory.lastSocket, httpResponse(101))
        val before = r.scheduler.delays.size
        r.factory.lastListener.onFailure(r.factory.lastSocket, IOException(), null)
        assertEquals("open re-armed reconnect", before + 1, r.scheduler.delays.size)
        assertEquals("and from the base delay", 500L, r.scheduler.lastDelay)
    }

    @Test fun a_successful_open_resets_the_backoff() {
        val r = Rig()
        r.transport.connect("sk-x", null)
        r.factory.lastListener.onFailure(r.factory.lastSocket, IOException(), null) // -> 500
        r.scheduler.runNext()
        r.factory.lastListener.onOpen(r.factory.lastSocket, httpResponse(101))      // reset
        r.factory.lastListener.onFailure(r.factory.lastSocket, IOException(), null) // back to 500
        assertEquals(500L, r.scheduler.lastDelay)
    }

    @Test fun backoff_schedule_is_pinned_and_capped() {
        val b = RealtimeTransport.Backoff.DEFAULT
        assertEquals(
            listOf(500L, 1000L, 2000L, 4000L, 8000L, 8000L, 8000L),
            (0..6).map { b.delayFor(it) },
        )
        assertEquals("never overflows negative at absurd attempts", 8000L, b.delayFor(1000))
    }

    @Test fun close_is_clean_and_idempotent() {
        val r = Rig()
        r.transport.connect("sk-x", null)
        val ws = r.factory.lastSocket
        r.factory.lastListener.onOpen(ws, httpResponse(101))

        r.transport.close()
        assertEquals(1000, ws.closeCode)
        assertEquals(1, ws.closeCount)

        r.transport.close() // second close no-ops
        assertEquals(1, ws.closeCount)
    }

    @Test fun a_reconnect_scheduled_before_close_does_not_reopen_after_close() {
        val r = Rig()
        r.transport.connect("sk-x", null)
        r.factory.lastListener.onFailure(r.factory.lastSocket, IOException(), null) // schedules reconnect
        r.transport.close()
        r.scheduler.runNext() // fires after close -> must NOT open a new socket
        assertEquals(1, r.factory.sockets.size)
    }

    @Test fun unknown_inbound_is_ignored() {
        val r = Rig()
        r.transport.connect("sk-x", null)
        r.factory.lastListener.onOpen(r.factory.lastSocket, httpResponse(101))
        r.factory.lastListener.onMessage(r.factory.lastSocket, """{"type":"response.done"}""")
        assertTrue(r.listener.deltas.isEmpty())
        assertTrue(r.listener.completed.isEmpty())
        assertNull(RealtimeEventParser.parse("""{"type":"response.done"}"""))
    }
}
