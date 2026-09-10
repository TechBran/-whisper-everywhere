package com.whispereverywhere.transcription.live

import com.whispereverywhere.recording.Resampler
import com.whispereverywhere.transcription.cloud.FatalKind
import com.whispereverywhere.tts.cloud.PcmBytes
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.Base64

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
        val requests = mutableListOf<Request>()
        override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
            val ws = FakeWebSocket()
            sockets += ws
            listeners += listener
            requests += request
            return ws
        }
        val lastSocket get() = sockets.last()
        val lastListener get() = listeners.last()
        val lastRequest get() = requests.last()
    }

    private class FakeScheduler : ReconnectScheduler {
        val delays = mutableListOf<Long>()
        private val tasks = mutableListOf<() -> Unit>()
        override fun schedule(delayMs: Long, task: () -> Unit) { delays += delayMs; tasks += task }
        fun runNext() { tasks.removeAt(0).invoke() }
        /** Runs a scheduled reconnect if there is one; false once the ceiling stops scheduling them. */
        fun runNextIfAny(): Boolean {
            if (tasks.isEmpty()) return false
            tasks.removeAt(0).invoke()
            return true
        }
        val lastDelay get() = delays.last()
    }

    /** A fake monotonic clock, in ms, so socket LIFETIME (the healthy-open rule) is driveable. */
    private class FakeClock { var ms = 1_000L; fun nanos(): Long = ms * 1_000_000L; fun advance(dMs: Long) { ms += dMs } }

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
        val clock = FakeClock()
        val transport = RealtimeTransport(factory, scheduler, listener, nowNanos = clock::nanos)
    }

    // --- tests -----------------------------------------------------------------------------------

    @Test fun onOpen_sends_session_update_once() {
        val r = Rig()
        r.transport.connect("sk-x", null)
        r.factory.lastListener.onOpen(r.factory.lastSocket, httpResponse(101))

        assertEquals(listOf(RealtimeEvents.sessionUpdate()), r.factory.lastSocket.sent)
        assertEquals(1, r.listener.connects)
    }

    // --- the tolerant connector: one silent beta-header retry before any 4xx is believed --------

    @Test fun the_first_4xx_retries_once_with_the_beta_header_before_any_fatal() {
        // Current docs omit `OpenAI-Beta: realtime=v1`; older deployments require it. A
        // header-caused 401 must NOT be reported as "key rejected" — the transport retries once
        // with the header, silently: no fatal, no disconnect, no reconnect-attempt consumed.
        val r = Rig()
        r.transport.connect("sk-x", null)
        assertNull("first attempt omits the beta header", r.factory.lastRequest.header("OpenAI-Beta"))

        r.factory.lastListener.onFailure(r.factory.lastSocket, IOException("upgrade rejected"), httpResponse(401))

        assertEquals("a second socket was opened immediately", 2, r.factory.sockets.size)
        assertEquals("the retry carries the beta header", "realtime=v1", r.factory.lastRequest.header("OpenAI-Beta"))
        assertTrue("no fatal yet — the retry is silent", r.listener.fatals.isEmpty())
        assertEquals("no disconnect surfaced for the silent retry", 0, r.listener.disconnects)
        assertEquals("no scheduled reconnect consumed", 0, r.scheduler.delays.size)

        // The retry succeeds -> a normal session; the header sticks for later reconnects.
        r.factory.lastListener.onOpen(r.factory.lastSocket, httpResponse(101))
        assertEquals(1, r.listener.connects)
    }

    @Test fun handshake_failure_logs_status_code_only_and_reports_fatal_after_the_retry() {
        val r = Rig()
        r.transport.connect("sk-x", null)
        // A body is attached on purpose: the transport must NOT consume it.
        val resp = httpResponse(401, "SENSITIVE-BODY-should-never-be-read")
        r.factory.lastListener.onFailure(r.factory.lastSocket, IOException("upgrade rejected"), resp)
        // First 401 spent the silent beta retry; the SECOND is believed and classified.
        r.factory.lastListener.onFailure(r.factory.lastSocket, IOException("upgrade rejected"), httpResponse(401))

        assertEquals(listOf(FatalKind.INVALID_KEY to 401), r.listener.fatals)
        assertEquals("fatal must not reconnect", 0, r.scheduler.delays.size)
        assertEquals("fatal is not a transient disconnect", 0, r.listener.disconnects)
        // Body still fully readable => the transport never touched it (status code only).
        assertEquals("SENSITIVE-BODY-should-never-be-read", resp.body?.string())
    }

    @Test fun a_403_is_forbidden_and_a_429_is_out_of_credit_after_the_retry() {
        val r1 = Rig().also { it.transport.connect("sk", null) }
        r1.factory.lastListener.onFailure(r1.factory.lastSocket, IOException(), httpResponse(403))
        r1.factory.lastListener.onFailure(r1.factory.lastSocket, IOException(), httpResponse(403))
        assertEquals(listOf(FatalKind.FORBIDDEN to 403), r1.listener.fatals)

        val r2 = Rig().also { it.transport.connect("sk", null) }
        r2.factory.lastListener.onFailure(r2.factory.lastSocket, IOException(), httpResponse(429))
        r2.factory.lastListener.onFailure(r2.factory.lastSocket, IOException(), httpResponse(429))
        assertEquals(listOf(FatalKind.OUT_OF_CREDIT to 429), r2.listener.fatals)
        assertEquals(0, r2.scheduler.delays.size)
    }

    @Test fun a_network_drop_never_triggers_the_beta_retry() {
        // The retry answers exactly one question — "was the header missing?" — which only a 4xx
        // can pose. A plain network drop reconnects with backoff, headerless, as before.
        val r = Rig()
        r.transport.connect("sk-x", null)
        r.factory.lastListener.onFailure(r.factory.lastSocket, IOException("reset"), null)
        assertEquals("no immediate second socket", 1, r.factory.sockets.size)
        assertEquals("normal backoff path", 1, r.scheduler.delays.size)
        r.scheduler.runNext()
        assertNull("reconnect stays headerless", r.factory.lastRequest.header("OpenAI-Beta"))
    }

    @Test fun append_forwarded_as_an_encoded_event_and_commit_is_a_server_driven_no_op() {
        val r = Rig()
        r.transport.connect("sk-x", null)
        r.factory.lastListener.onOpen(r.factory.lastSocket, httpResponse(101))

        // The engine now hands raw 16 kHz PCM down; the default OpenAI protocol frames it (24 k
        // upsample + base64), so the SAME append JSON reaches the wire — meaning intact. Under
        // server_vad the SERVER auto-commits, so sendCommit is a benign no-op that sends no frame.
        val pcm = pcm16LE(shortArrayOf(0, 100, 200, 300))
        assertTrue(r.transport.sendAppend(pcm))
        assertTrue("commit succeeds but sends nothing under server_vad", r.transport.sendCommit())

        val expectedB64 = Base64.getEncoder()
            .encodeToString(pcm16LE(Resampler.upsample16kTo24k(PcmBytes.toShortArrayLE(pcm))))
        assertEquals(
            listOf(RealtimeEvents.sessionUpdate(), RealtimeEvents.append(expectedB64)),
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
        assertTrue("over-threshold append is refused", !r.transport.sendAppend(byteArrayOf(1, 2, 3, 4)))

        // Once the buffer drains below the threshold it sends normally again.
        r.factory.lastSocket.queued = 0
        assertTrue(r.transport.sendAppend(byteArrayOf(1, 2, 3, 4)))
    }

    @Test fun send_without_a_live_socket_returns_false_and_does_not_throw() {
        val r = Rig() // never connected
        assertTrue(!r.transport.sendAppend(byteArrayOf(1, 2, 3, 4)))
        assertTrue(!r.transport.sendCommit())
    }

    // ---- THE CONFIG-FIRST GUARANTEE (the Soniox live bug, 2026-07-31) ---------------------------

    @Test fun audio_is_refused_until_the_bootstrap_frames_are_on_the_wire() {
        // newWebSocket() returns a socket BEFORE the handshake, so connect() leaves a non-null
        // socket the audio pump can see while onOpen (and the config) is still pending. Soniox
        // 400s any pre-config frame ("Start request must be a text message." — proven against
        // their live server); OpenAI/ElevenLabs tolerate it, which is why the ordering bug hid on
        // two of three providers. The gate makes config-first structural for ALL providers.
        val r = Rig()
        r.transport.connect("sk-x", null)

        // Socket exists, handshake has NOT completed: every send must refuse.
        assertTrue("append before onOpen must be refused", !r.transport.sendAppend(byteArrayOf(1, 2)))
        assertTrue("commit before onOpen must be refused", !r.transport.sendCommit())
        assertTrue("nothing may reach the wire before the config", r.factory.lastSocket.sent.isEmpty())

        // Handshake completes -> bootstrap goes out FIRST, then the gate opens.
        r.factory.lastListener.onOpen(r.factory.lastSocket, httpResponse(101))
        assertEquals(
            "the config is the first frame on the wire",
            listOf(RealtimeEvents.sessionUpdate()),
            r.factory.lastSocket.sent,
        )
        assertTrue("audio flows once bootstrapped", r.transport.sendAppend(byteArrayOf(1, 2)))
    }

    @Test fun a_drop_shuts_the_gate_until_the_reconnected_socket_re_bootstraps() {
        // The reconnect gap is the same hazard: a new socket is created (non-null) long before its
        // onOpen. Audio must not cross into it ahead of the fresh config.
        val r = Rig()
        r.transport.connect("sk-x", null)
        r.factory.lastListener.onOpen(r.factory.lastSocket, httpResponse(101))
        assertTrue(r.transport.sendAppend(byteArrayOf(1, 2)))

        r.factory.lastListener.onFailure(r.factory.lastSocket, IOException("reset"), null)
        assertTrue("gate shut on drop", !r.transport.sendAppend(byteArrayOf(1, 2)))

        r.scheduler.runNext() // reconnect creates a new pre-handshake socket
        assertTrue("still shut on the fresh socket", !r.transport.sendAppend(byteArrayOf(1, 2)))

        r.factory.lastListener.onOpen(r.factory.lastSocket, httpResponse(101))
        assertEquals(
            "the reconnected socket is re-bootstrapped before any audio",
            listOf(RealtimeEvents.sessionUpdate()),
            r.factory.lastSocket.sent,
        )
        assertTrue(r.transport.sendAppend(byteArrayOf(1, 2)))
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

    @Test fun inbound_committed_and_failed_dispatched_to_listener() {
        val r = Rig()
        r.transport.connect("sk-x", null)
        r.factory.lastListener.onOpen(r.factory.lastSocket, httpResponse(101))

        r.factory.lastListener.onMessage(
            r.factory.lastSocket,
            """{"type":"input_audio_buffer.committed","item_id":"it_5"}""",
        )
        r.factory.lastListener.onMessage(
            r.factory.lastSocket,
            """{"type":"conversation.item.input_audio_transcription.failed","item_id":"it_5"}""",
        )

        assertEquals(listOf("it_5"), r.listener.committed)
        assertEquals(listOf("it_5"), r.listener.failed)
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

    @Test fun a_healthy_connection_resets_the_reconnect_ceiling() {
        val r = Rig()
        r.transport.connect("sk-x", null)
        repeat(RealtimeTransport.DEFAULT_MAX_RECONNECTS) {
            r.factory.lastListener.onFailure(r.factory.lastSocket, IOException(), null)
            r.scheduler.runNext()
        }
        // A connection that STAYED UP clears the consecutive-failure count, so the socket is willing
        // again. The open alone does not (r1 nit 1) — see the open/close-loop test below.
        r.factory.lastListener.onOpen(r.factory.lastSocket, httpResponse(101))
        r.clock.advance(RealtimeTransport.MIN_HEALTHY_OPEN_MS)
        val before = r.scheduler.delays.size
        r.factory.lastListener.onFailure(r.factory.lastSocket, IOException(), null)
        assertEquals("a healthy connection re-armed reconnect", before + 1, r.scheduler.delays.size)
        assertEquals("and from the base delay", 500L, r.scheduler.lastDelay)
    }

    @Test fun a_healthy_connection_resets_the_backoff() {
        val r = Rig()
        r.transport.connect("sk-x", null)
        r.factory.lastListener.onFailure(r.factory.lastSocket, IOException(), null) // -> 500
        r.scheduler.runNext()
        r.factory.lastListener.onOpen(r.factory.lastSocket, httpResponse(101))
        r.clock.advance(RealtimeTransport.MIN_HEALTHY_OPEN_MS)                       // reset
        r.factory.lastListener.onFailure(r.factory.lastSocket, IOException(), null) // back to 500
        assertEquals(500L, r.scheduler.lastDelay)
    }

    @Test fun a_short_lived_open_does_not_forgive_the_backoff() {
        val r = Rig()
        r.transport.connect("sk-x", null)
        r.factory.lastListener.onFailure(r.factory.lastSocket, IOException(), null) // -> 500
        r.scheduler.runNext()
        r.factory.lastListener.onOpen(r.factory.lastSocket, httpResponse(101))
        r.clock.advance(750) // the loop's cadence: open, then gone again
        r.factory.lastListener.onFailure(r.factory.lastSocket, IOException(), null)
        assertEquals("the counter kept climbing", 1000L, r.scheduler.lastDelay)
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

    // ---- SERVER-INITIATED CLOSE FRAMES (4.3.4 / T4, from the Gemini T0 probes 2026-09-10) -------
    //
    // Until this change no test drove onClosing/onClosed, and the transport's onClosed scheduled
    // NOTHING: any post-upgrade close frame ended the cloud half of the session with zero
    // reconnects and no toast. Gemini answers EVERY error — the bad key included — as a 101 upgrade
    // followed by a close frame, so the close path had to grow the classification + reconnect the
    // onFailure path always had, plus the socket-identity guard that keeps a retired socket's late
    // callbacks away from its replacement.

    /**
     * A minimal protocol for the close/rotate/binary probes: classifies one close reason as fatal,
     * rotates on request (from an inbound message, or from inside onAppend — the watchdog shape),
     * and records what reaches it. Its bootstrap frame is distinctive so re-bootstrap is provable.
     */
    private class ProbeProtocol : RealtimeProtocol {
        override val endpoint = "wss://probe.invalid/live"
        override val tolerant4xxRetry = false
        lateinit var control: SessionControl
        val texts = mutableListOf<String>()
        val binaries = mutableListOf<ByteString>()
        var rotateOnNextAppend = false
        var bootstraps = 0
        override fun upgradeHeaders(apiKey: String): List<Pair<String, String>> = emptyList()
        override fun bind(control: SessionControl, sink: RealtimeTransport.Listener) { this.control = control }
        override fun bootstrap(apiKey: String, language: String?): List<Frame> {
            bootstraps++
            return listOf(Frame.Text(PROBE_SETUP))
        }
        override fun onAppend(pcm16k: ByteArray): Boolean {
            if (rotateOnNextAppend) {
                rotateOnNextAppend = false
                control.rotate() // the stall watchdog's shape: rotate from INSIDE the send path
                return false
            }
            return control.send(Frame.Text("audio"))
        }
        override fun onCommit(): Boolean = true
        override fun onText(text: String) {
            texts += text
            if (text == "rotate") control.rotate() // the GoAway / max-duration shape
        }
        override fun onBinary(bytes: ByteString) { binaries += bytes }
        override fun classifyFatal(code: Int): FatalKind? = null
        override fun classifyClose(code: Int, reason: String): FatalKind? =
            if (reason.contains("api key not valid", ignoreCase = true)) FatalKind.INVALID_KEY else null
        override fun reset() {}
    }

    private companion object {
        const val PROBE_SETUP = """{"setup":"probe"}"""
        const val GEMINI_CAP_REASON =
            "Connection aborted because the client failed to close the connection after receiving a GoAway signal once the session durat"
        const val GEMINI_BAD_KEY_REASON = "API key not valid. Please pass a valid API key."
    }

    private class ProbeRig {
        val factory = FakeFactory()
        val scheduler = FakeScheduler()
        val listener = RecordingListener()
        val protocol = ProbeProtocol()
        val clock = FakeClock()
        val transport = RealtimeTransport(factory, scheduler, listener, protocol, nowNanos = clock::nanos)
    }

    @Test fun a_transient_server_close_surfaces_one_disconnect_and_reconnects_with_backoff() {
        // The default protocol classifies no close (classifyClose = null): the 10-minute-cap close
        // shape is TRANSIENT — the engine resolves outstanding turns Lost (local rescue) and the
        // transport re-establishes the session under the ceiling, exactly as a network drop does.
        val r = Rig()
        r.transport.connect("sk-x", null)
        val ws1 = r.factory.lastSocket
        r.factory.lastListener.onOpen(ws1, httpResponse(101))

        r.factory.lastListener.onClosing(ws1, 1008, GEMINI_CAP_REASON)
        assertEquals("the close handshake is completed", 1000, ws1.closeCode)
        r.factory.lastListener.onClosed(ws1, 1008, GEMINI_CAP_REASON)

        assertEquals("exactly one disconnect for the pair of close callbacks", 1, r.listener.disconnects)
        assertEquals("a transient close is not a fatal", 0, r.listener.fatals.size)
        assertEquals("one reconnect scheduled, from the base delay", listOf(500L), r.scheduler.delays)
        assertTrue("the gate is shut while down", !r.transport.sendAppend(byteArrayOf(1, 2)))

        r.scheduler.runNext()
        assertEquals("a replacement socket was opened", 2, r.factory.sockets.size)
        val ws2 = r.factory.lastSocket
        r.factory.lastListener.onOpen(ws2, httpResponse(101))
        assertEquals("the replacement is re-bootstrapped", listOf(RealtimeEvents.sessionUpdate()), ws2.sent)
        assertTrue("audio flows on the replacement", r.transport.sendAppend(byteArrayOf(1, 2)))
        assertEquals(2, r.listener.connects)
    }

    @Test fun an_open_close_loop_within_seconds_still_hits_the_reconnect_ceiling() {
        // r1 nit 1: onOpen used to reset the ceiling, so it only ever counted consecutive FAILED
        // opens. A server that answers every open with a close this protocol reads as transient
        // (101 -> setupComplete -> close, an unmapped reason — the shape the 4.3.4 close path made
        // reconnectable) looped open/close every ~0.75 s for the whole mic session, no toast, no
        // bound. The counter now needs MIN_HEALTHY_OPEN_MS of socket life to be forgiven, so the
        // loop consumes the ceiling exactly like a failed open and the transport gives up.
        val r = ProbeRig()
        r.transport.connect("k", null)
        var opens = 0
        repeat(RealtimeTransport.DEFAULT_MAX_RECONNECTS + 1) {
            val ws = r.factory.lastSocket
            r.factory.lastListener.onOpen(ws, httpResponse(101))
            opens++
            r.clock.advance(750) // the server hangs up well inside MIN_HEALTHY_OPEN_MS
            r.factory.lastListener.onClosing(ws, 1011, "internal error")
            r.factory.lastListener.onClosed(ws, 1011, "internal error")
            r.scheduler.runNextIfAny()
        }
        assertEquals("every open was answered by a transient close", opens, r.listener.disconnects)
        assertEquals(
            "the ceiling bit: no reconnect past it, whatever the socket did in between",
            RealtimeTransport.DEFAULT_MAX_RECONNECTS,
            r.scheduler.delays.size,
        )
        assertEquals(
            "so the loop is bounded at ceiling + the original socket",
            RealtimeTransport.DEFAULT_MAX_RECONNECTS + 1,
            r.factory.sockets.size,
        )
        assertTrue("all of it inside seconds", r.clock.ms < RealtimeTransport.MIN_HEALTHY_OPEN_MS)
        assertTrue("the socket stays down; the engine rides the local fallback", !r.transport.sendAppend(byteArrayOf(1)))
    }

    @Test fun a_fatal_server_close_latches_without_reconnect_and_only_the_code_crosses() {
        // Gemini's bad key: 101 then close 1007 "API key not valid…". classifyClose maps it, the
        // transport surfaces onFatal(kind, CLOSE CODE) and schedules nothing — no disconnect, no
        // reconnect loop against a dead key. The reason text reaches the classifier and nothing else.
        val r = ProbeRig()
        r.transport.connect("k", null)
        val ws1 = r.factory.lastSocket
        r.factory.lastListener.onOpen(ws1, httpResponse(101))

        r.factory.lastListener.onClosing(ws1, 1007, GEMINI_BAD_KEY_REASON)
        r.factory.lastListener.onClosed(ws1, 1007, GEMINI_BAD_KEY_REASON)

        assertEquals(listOf(FatalKind.INVALID_KEY to 1007), r.listener.fatals)
        assertEquals("a fatal close never reconnects", 0, r.scheduler.delays.size)
        assertEquals("a fatal close is not a transient disconnect", 0, r.listener.disconnects)
        assertTrue("the socket stays down", !r.transport.sendAppend(byteArrayOf(1, 2)))
    }

    @Test fun our_own_close_keeps_the_close_handshake_silent() {
        // close() then the server's half of the handshake: neither a disconnect nor a reconnect.
        val r = Rig()
        r.transport.connect("sk-x", null)
        val ws1 = r.factory.lastSocket
        r.factory.lastListener.onOpen(ws1, httpResponse(101))
        r.transport.close()
        r.factory.lastListener.onClosing(ws1, 1000, "")
        r.factory.lastListener.onClosed(ws1, 1000, "")
        assertEquals(0, r.listener.disconnects)
        assertEquals(0, r.scheduler.delays.size)
        assertEquals("closed exactly once, by us", 1, ws1.closeCount)
    }

    @Test fun a_late_callback_from_a_superseded_socket_cannot_null_the_replacement() {
        // The clobber hazard: OkHttp cancels an unanswered close only after 60 s, so a retired
        // socket's onFailure/onClosed can land long after the replacement is live. Before the
        // identity guard it nulled the replacement, fired a spurious disconnect (a whisper sentence
        // + shed audio) and scheduled yet another open.
        val r = Rig()
        r.transport.connect("sk-x", null)
        val ws1 = r.factory.lastSocket
        r.factory.lastListener.onOpen(ws1, httpResponse(101))
        r.factory.lastListener.onFailure(ws1, IOException("reset"), null)
        r.scheduler.runNext()
        val ws2 = r.factory.lastSocket
        r.factory.lastListener.onOpen(ws2, httpResponse(101))
        assertTrue(r.transport.sendAppend(byteArrayOf(1, 2)))
        val disconnectsBefore = r.listener.disconnects
        val delaysBefore = r.scheduler.delays.size

        // The OLD socket speaks again, every way it can.
        r.factory.lastListener.onClosing(ws1, 1006, "")
        r.factory.lastListener.onClosed(ws1, 1006, "")
        r.factory.lastListener.onFailure(ws1, IOException("late cancel"), null)
        r.factory.lastListener.onMessage(ws1, deltaJson("stale", "ghost"))

        assertTrue("the replacement is still live", r.transport.sendAppend(byteArrayOf(3, 4)))
        assertEquals("no spurious disconnect", disconnectsBefore, r.listener.disconnects)
        assertEquals("no extra reconnect", delaysBefore, r.scheduler.delays.size)
        assertTrue("a retired socket's message is never dispatched", r.listener.deltas.none { it.first == "stale" })
        assertEquals("no extra socket", 2, r.factory.sockets.size)
    }

    @Test fun rotate_retires_the_socket_and_surfaces_exactly_one_disconnect_independent_of_its_close_frames() {
        // A protocol rotation (GoAway / max duration): the socket is closed with 1000, ONE
        // disconnect is surfaced at once (outstanding turns -> local rescue), one reconnect is
        // scheduled — and the retired socket's own close handshake and late messages add nothing.
        val r = ProbeRig()
        r.transport.connect("k", null)
        val ws1 = r.factory.lastSocket
        r.factory.lastListener.onOpen(ws1, httpResponse(101))
        assertEquals(1, r.protocol.bootstraps)

        r.factory.lastListener.onMessage(ws1, "rotate")
        assertEquals("rotate closes normally", 1000, ws1.closeCode)
        assertEquals("the one disconnect a rotation owes", 1, r.listener.disconnects)
        assertEquals(listOf(500L), r.scheduler.delays)
        assertEquals(0, r.listener.fatals.size)

        // The retired socket finishes its handshake and even squeezes out a late message.
        r.factory.lastListener.onClosing(ws1, 1000, "")
        r.factory.lastListener.onClosed(ws1, 1000, "")
        r.factory.lastListener.onMessage(ws1, "late")
        assertEquals("still exactly one disconnect", 1, r.listener.disconnects)
        assertEquals("still exactly one reconnect", 1, r.scheduler.delays.size)
        assertEquals("the late message never reached the protocol", listOf("rotate"), r.protocol.texts)

        r.scheduler.runNext()
        val ws2 = r.factory.lastSocket
        r.factory.lastListener.onOpen(ws2, httpResponse(101))
        assertEquals("the replacement re-bootstraps", listOf(PROBE_SETUP), ws2.sent)
        assertEquals(2, r.protocol.bootstraps)
        assertTrue(r.transport.sendAppend(byteArrayOf(1, 2)))
    }

    @Test fun rotate_from_inside_the_send_path_surfaces_the_disconnect_after_the_lock_is_released() {
        // The stall watchdog rotates from INSIDE onAppend, i.e. under the transport lock held by
        // sendAppend. The disconnect must still reach the listener — after the lock is released,
        // on the same call — not depend on the retired socket ever answering the close.
        val r = ProbeRig()
        r.transport.connect("k", null)
        val ws1 = r.factory.lastSocket
        r.factory.lastListener.onOpen(ws1, httpResponse(101))
        r.protocol.rotateOnNextAppend = true

        assertTrue("the append that rotated is reported unsent (the turn is shed)", !r.transport.sendAppend(byteArrayOf(1, 2)))
        assertEquals(1000, ws1.closeCode)
        assertEquals("the disconnect surfaced on the rotating call itself", 1, r.listener.disconnects)
        assertEquals(listOf(500L), r.scheduler.delays)
        assertTrue("the gate is shut until the replacement bootstraps", !r.transport.sendAppend(byteArrayOf(1, 2)))
    }

    @Test fun inbound_binary_reaches_the_protocol_and_the_default_declines_it() {
        // Gemini sends every message as a BINARY frame carrying UTF-8 JSON. The transport forwards
        // binary to RealtimeProtocol.onBinary; the default body declines it (the three shipped
        // providers never receive binary and must keep their tripwire).
        val probe = ProbeRig()
        probe.transport.connect("k", null)
        probe.factory.lastListener.onOpen(probe.factory.lastSocket, httpResponse(101))
        val payload = """{"setupComplete":{}}""".encodeUtf8()
        probe.factory.lastListener.onMessage(probe.factory.lastSocket, payload)
        assertEquals(listOf(payload), probe.protocol.binaries)

        val openai = Rig()
        openai.transport.connect("sk-x", null)
        openai.factory.lastListener.onOpen(openai.factory.lastSocket, httpResponse(101))
        openai.factory.lastListener.onMessage(openai.factory.lastSocket, deltaJson("it_1", "hel").encodeUtf8())
        assertTrue("the default protocol declines binary — a delta in a binary frame dispatches nothing", openai.listener.deltas.isEmpty())
    }

    @Test fun a_socket_that_opens_after_being_retired_is_cancelled_not_adopted() {
        // rotate() during a handshake: the retired socket's onOpen must not become the live socket
        // beside the replacement the reconnect is about to open.
        val r = ProbeRig()
        r.transport.connect("k", null)
        val ws1 = r.factory.lastSocket
        r.factory.lastListener.onOpen(ws1, httpResponse(101))
        r.factory.lastListener.onMessage(ws1, "rotate")
        r.scheduler.runNext()
        val ws2 = r.factory.lastSocket
        r.factory.lastListener.onOpen(ws1, httpResponse(101)) // the retired socket "opens" late
        assertEquals("no second bootstrap onto the retired socket", listOf(PROBE_SETUP), ws1.sent)
        assertEquals(1, r.protocol.bootstraps)
        assertTrue("the gate stays shut until the REPLACEMENT opens", !r.transport.sendAppend(byteArrayOf(9)))
        r.factory.lastListener.onOpen(ws2, httpResponse(101))
        assertEquals(listOf(PROBE_SETUP), ws2.sent)
        assertEquals(2, r.protocol.bootstraps)
        assertTrue(r.transport.sendAppend(byteArrayOf(1)))
        assertEquals("audio went to the replacement only", listOf(PROBE_SETUP, "audio"), ws2.sent)
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
