package com.whispereverywhere.transcription.live

import com.whispereverywhere.transcription.SegmentOutcome
import com.whispereverywhere.transcription.TranscriptionEngine
import com.whispereverywhere.transcription.cloud.FallbackPolicy
import com.whispereverywhere.transcription.cloud.FatalKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The live engine over a FAKE [LiveTranscriptionEngine.Transport], so the whole item_id↔seq
 * correlation and exactly-once machine runs with no network. The tests play the role the socket
 * plays in production: they fire onDelta/onCompleted/onDisconnected/onError/onFatal on the transport
 * listener the engine handed to the factory, and assert what the engine resolved and sent.
 *
 * The invariants pinned here are the same ones [com.whispereverywhere.transcription.cloud.CloudTranscriptionEngine]
 * pins over a POST, plus the two only a socket can break: an out-of-order completion (the documented
 * `item_id` case) and a capture thread blocked behind a saturated sender.
 */
class LiveTranscriptionEngineTest {

    private val scopes = Collections.synchronizedList(mutableListOf<CoroutineScope>())

    @After fun cancelScopes() {
        // Release any parked sender before tearing the scope down so no pool thread is left blocked.
        harnesses.forEach { it.transport.releaseGate() }
        scopes.forEach { it.cancel() }
    }

    private fun scope(): CoroutineScope =
        CoroutineScope(Dispatchers.Default + SupervisorJob()).also { scopes += it }

    // ---------------------------------------------------------------- fakes

    /**
     * Records outbound sends and, optionally, STALLS every [sendAppend] on a latch so the sender
     * coroutine cannot drain — the saturated-socket condition that must never reach the capture
     * thread.
     */
    private class FakeTransport(val listener: RealtimeTransport.Listener) :
        LiveTranscriptionEngine.Transport {
        val appends = Collections.synchronizedList(mutableListOf<ByteArray>())
        /** Every sendAppend CALL, including refused ones — lets a test await the sender draining. */
        val appendCalls = AtomicInteger(0)
        val commits = AtomicInteger(0)
        var connects = 0
        var closes = 0
        /** Models the real transport: a closed/absent socket sends nothing and returns false. */
        @Volatile var open = true
        /** When set, sendAppend returns false (network backpressure / socket-down) without recording. */
        @Volatile var refuseAppends = false
        @Volatile private var gate: CountDownLatch? = null

        fun stall() { gate = CountDownLatch(1) }
        fun releaseGate() { gate?.countDown() }

        override fun connect(apiKey: String, language: String?) { connects++; open = true }
        override fun sendAppend(pcm: ByteArray): Boolean {
            gate?.await()
            appendCalls.incrementAndGet()
            if (!open || refuseAppends) return false
            appends += pcm
            return true
        }
        override fun sendCommit(): Boolean {
            if (!open) return false
            commits.incrementAndGet(); return true
        }
        override fun close() { closes++; open = false }
    }

    private class Rec : TranscriptionEngine.Listener {
        val opens = AtomicInteger(0)
        val closes = AtomicInteger(0)
        val deltas = Collections.synchronizedList(mutableListOf<String>())
        val all = Collections.synchronizedList(mutableListOf<Pair<Long, SegmentOutcome>>())
        private val queue = LinkedBlockingQueue<Pair<Long, SegmentOutcome>>()

        override fun onOpen() { opens.incrementAndGet() }
        override fun onDelta(text: String) { deltas += text }
        override fun onSegmentResolved(seq: Long, outcome: SegmentOutcome) {
            all += seq to outcome
            queue.put(seq to outcome)
        }
        override fun onError(message: String) = Unit
        override fun onClosed() { closes.incrementAndGet() }

        /** Blocks for the next resolution in ARRIVAL order (not seq order). */
        fun next(timeoutMs: Long = 5_000): Pair<Long, SegmentOutcome> {
            val e = queue.poll(timeoutMs, TimeUnit.MILLISECONDS)
            assertNotNull("expected a resolution within ${timeoutMs}ms", e)
            return e!!
        }
    }

    private inner class Harness(maxBacklog: Int, minCommitBytes: Int) {
        lateinit var transport: FakeTransport
        val l = Rec()
        val engine = LiveTranscriptionEngine("sk-test", scope(), maxBacklog, minCommitBytes) { listener ->
            FakeTransport(listener).also { transport = it }
        }
        init { harnesses += this }
    }

    private val harnesses = Collections.synchronizedList(mutableListOf<Harness>())

    // minCommitBytes defaults to 0 here so the correlation tests can drive turns with tiny fixtures;
    // the sub-minimum test passes the real 3200 B threshold explicitly.
    private fun connected(maxBacklog: Int = 128, minCommitBytes: Int = 0): Harness =
        Harness(maxBacklog, minCommitBytes).also { it.engine.connect(null, it.l) }

    // ---------------------------------------------------------------- the capture thread

    @Test fun sendAudio_never_blocks_capture_thread() {
        // The sender is wedged on a stalled socket. sendAudio runs on the AudioRecord thread every
        // ~32 ms and also draws the waveform; if it ever waited on the drain the UI would freeze.
        val h = connected()
        h.transport.stall()
        val chunk = ByteArray(1024)
        val startNs = System.nanoTime()
        repeat(500) { h.engine.sendAudio(chunk) }
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000
        assertTrue("sendAudio blocked for ${elapsedMs}ms with the sender saturated", elapsedMs < 500)
        h.transport.releaseGate()
    }

    // ---------------------------------------------------------------- seq allocation

    @Test fun commit_allocates_monotonic_seq_and_cuts_buffer_synchronously() {
        val h = connected()
        h.engine.sendAudio(byteArrayOf(1, 2, 3, 4)); assertEquals(0L, h.engine.commit())
        h.engine.sendAudio(byteArrayOf(5, 6)); assertEquals(1L, h.engine.commit())
        // The turn was cut synchronously inside commit(): a follow-up commit with no new audio owes
        // nothing. This is the property FallbackTranscriptionEngine's mirror↔seq pairing depends on.
        assertEquals(-1L, h.engine.commit())
    }

    @Test fun empty_buffer_commit_returns_NO_SEGMENT() {
        val h = connected()
        assertEquals(-1L, h.engine.commit())
        assertEquals("no seq was allocated, so nothing is owed a resolution", 0, h.l.all.size)
    }

    // ---------------------------------------------------------------- correlation + exactly-once

    @Test fun completed_resolves_mapped_seq_as_Text_exactly_once() {
        val h = connected()
        h.engine.sendAudio(byteArrayOf(1)); assertEquals(0L, h.engine.commit())

        h.transport.listener.onCommitted("it_1") // commit ack binds it_1 -> seq 0
        h.transport.listener.onCompleted("it_1", "  hello world \n")
        assertEquals(1, h.l.all.size)
        assertEquals(0L to SegmentOutcome.Text("hello world"), h.l.all.single())

        // A duplicate completed for the same item_id must NOT resolve seq 0 a second time — a second
        // injection is a full-field read-modify-write that would corrupt the field.
        h.transport.listener.onCompleted("it_1", "hello world")
        assertEquals(1, h.l.all.size)
    }

    @Test fun an_empty_server_completion_is_silence_not_a_loss_marker() {
        // Owner-reported artifact, 2026-07-31: "[…]" appearing in live transcripts now and then.
        // Under SERVER-driven turns the provider's VAD cuts turns, and a server VAD fires on a
        // cough, a door, a breath — an empty completion is that non-speech, not a lost sentence.
        // (The old EmptyUnexpected classification was written when OUR client VAD cut turns, where
        // an empty transcript really did mean confirmed speech went missing; the 2026-07-31
        // inversion changed the premise and the line was not revisited.) Silence contributes
        // nothing, exactly as on every other path.
        val h = connected()
        h.engine.sendAudio(byteArrayOf(1)); assertEquals(0L, h.engine.commit())

        h.transport.listener.onCommitted("it_1")
        h.transport.listener.onCompleted("it_1", "   \n ")
        assertEquals(0L to SegmentOutcome.EmptyExpected, h.l.all.single())
    }

    @Test fun a_completion_that_is_only_model_chrome_is_also_silence() {
        // TranscriptText.clean strips markdown fences and bracketed non-speech markers; a
        // completion made ENTIRELY of chrome cleans to empty and takes the same silence path
        // rather than stamping a marker.
        val h = connected()
        h.engine.sendAudio(byteArrayOf(1)); assertEquals(0L, h.engine.commit())

        h.transport.listener.onCommitted("it_1")
        h.transport.listener.onCompleted("it_1", "```\n[BLANK_AUDIO]\n```")
        assertEquals(0L to SegmentOutcome.EmptyExpected, h.l.all.single())
    }

    @Test fun out_of_order_completions_resolve_correct_seqs() {
        // The documented case: "Ordering between completion events from different speech turns isn't
        // guaranteed. Use item_id." The commit ACKS arrive in commit order (A before B) and are what
        // bind item_id<->seq; the completions may then arrive in any order and still land correctly.
        val h = connected()
        h.engine.sendAudio(byteArrayOf(1)); assertEquals(0L, h.engine.commit()) // turn A
        h.engine.sendAudio(byteArrayOf(2)); assertEquals(1L, h.engine.commit()) // turn B

        h.transport.listener.onCommitted("it_A") // ack order is commit order: it_A -> seq 0
        h.transport.listener.onCommitted("it_B") // it_B -> seq 1

        h.transport.listener.onDelta("it_A", "al")   // preview only; binding already fixed by the acks
        h.transport.listener.onDelta("it_B", "br")
        assertEquals(listOf("al", "br"), h.l.deltas)  // deltas forwarded, never resolve

        h.transport.listener.onCompleted("it_B", "bravo")  // later turn finishes first
        h.transport.listener.onCompleted("it_A", "alpha")

        val bySeq = h.l.all.toMap()
        assertEquals(SegmentOutcome.Text("bravo"), bySeq[1L])
        assertEquals(SegmentOutcome.Text("alpha"), bySeq[0L])
    }

    @Test fun deltaless_turns_bind_by_committed_ack_not_by_completion_race() {
        // Two short turns that each emit a single `completed` with NO prior delta. First-mention
        // binding would swap them when B completes before A; the committed ACKS (in commit order)
        // fix the binding first, so the swap is impossible.
        val h = connected()
        h.engine.sendAudio(byteArrayOf(1)); assertEquals(0L, h.engine.commit()) // turn A
        h.engine.sendAudio(byteArrayOf(2)); assertEquals(1L, h.engine.commit()) // turn B

        h.transport.listener.onCommitted("it_A")
        h.transport.listener.onCommitted("it_B")

        h.transport.listener.onCompleted("it_B", "bravo") // B's completion races ahead of A's
        h.transport.listener.onCompleted("it_A", "alpha")

        val bySeq = h.l.all.toMap()
        assertEquals("B's transcript stays on B's seq", SegmentOutcome.Text("bravo"), bySeq[1L])
        assertEquals("A's transcript stays on A's seq", SegmentOutcome.Text("alpha"), bySeq[0L])
    }

    @Test fun a_transcription_failed_resolves_its_seq_Lost_and_keeps_correlation_aligned() {
        // A per-item failure must resolve THAT seq Lost (fallback rescues) and never strand it to
        // poison the next turn's binding.
        val h = connected()
        h.engine.sendAudio(byteArrayOf(1)); assertEquals(0L, h.engine.commit()) // turn A
        h.engine.sendAudio(byteArrayOf(2)); assertEquals(1L, h.engine.commit()) // turn B

        h.transport.listener.onCommitted("it_A")
        h.transport.listener.onCommitted("it_B")

        h.transport.listener.onTranscriptionFailed("it_A") // A cannot be transcribed
        h.transport.listener.onCompleted("it_B", "bravo")  // B still lands on its own seq

        val bySeq = h.l.all.toMap()
        assertTrue("the failed turn resolves Lost", bySeq[0L] is SegmentOutcome.Lost)
        assertTrue("and routes to the local retry", FallbackPolicy.shouldFallBack(bySeq[0L]!!))
        assertEquals("the next turn is unaffected", SegmentOutcome.Text("bravo"), bySeq[1L])
    }

    @Test fun a_subminimum_turn_resolves_Lost_without_committing_and_never_poisons_the_next() {
        // A cough / VAD false-trigger under the ~100 ms server minimum. Committing it would draw an
        // `input_audio_buffer_commit_empty` with NO item raised, stranding this seq and binding the
        // next real turn's item onto it (permanent off-by-one). Refuse the commit; resolve Lost.
        val h = connected(minCommitBytes = 3_200)
        h.engine.sendAudio(ByteArray(64)) // ~2 ms — far under the minimum
        val a = h.engine.commit()
        assertTrue("a too-short turn still allocates a real seq", a >= 0)
        val (ra, oa) = h.l.next()
        assertEquals(a, ra)
        assertTrue("too-short -> Lost, not a server-rejected phantom", oa is SegmentOutcome.Lost)
        assertTrue("and routes to the local retry", FallbackPolicy.shouldFallBack(oa))

        // Give the sender time; NO commit may be sent for the sub-minimum turn.
        Thread.sleep(100)
        assertEquals("no commit is sent for a sub-minimum turn", 0, h.transport.commits.get())

        // A real utterance follows. Its committed ack binds to B, NOT the vanished A.
        h.engine.sendAudio(ByteArray(4_000)) // >= 3200 B
        val b = h.engine.commit()
        assertTrue(b > a)
        h.transport.listener.onCommitted("it_B")
        h.transport.listener.onCompleted("it_B", "hello")
        assertEquals(SegmentOutcome.Text("hello"), h.l.all.toMap()[b])
    }

    @Test fun ws_drop_resolves_outstanding_turns_Lost() {
        val h = connected()
        h.engine.sendAudio(byteArrayOf(1)); assertEquals(0L, h.engine.commit())
        h.engine.sendAudio(byteArrayOf(2)); assertEquals(1L, h.engine.commit())

        // The server buffer is gone; the transport will reconnect, but these turns can never
        // complete, so each resolves Lost for the untouched fallback to rescue from the mirror.
        h.transport.listener.onDisconnected()
        assertEquals(setOf(0L, 1L), h.l.all.map { it.first }.toSet())
        assertTrue("every dropped turn is Lost", h.l.all.all { it.second is SegmentOutcome.Lost })

        // The correlation map was cleared: a straggler completed for a dropped turn does nothing.
        h.transport.listener.onCompleted("it_late", "ignored")
        assertEquals(2, h.l.all.size)
    }

    @Test fun ws_drop_clears_a_queued_commit_so_it_never_crosses_to_the_reconnected_socket() {
        // A pre-drop turn's Commit must not survive in the queue to flush onto the fresh socket,
        // where it would commit that socket's partial buffer into a phantom item (mis-correlation).
        val h = connected()
        h.transport.stall() // wedge the sender inside the first append BEFORE any op is enqueued
        h.engine.sendAudio(byteArrayOf(1))
        assertEquals(0L, h.engine.commit()) // Commit enqueued; the gated sender cannot reach it

        h.transport.listener.onDisconnected() // resolves seq 0 Lost AND clears the send buffer
        val (seq, outcome) = h.l.next()
        assertEquals(0L, seq)
        assertTrue("the dropped turn resolves Lost", outcome is SegmentOutcome.Lost)

        h.transport.releaseGate() // sender resumes to a cleared queue — no commit may flush
        Thread.sleep(100)
        assertEquals("a dropped turn's Commit must not cross onto the socket", 0, h.transport.commits.get())
    }

    @Test fun commit_while_the_socket_is_down_resolves_Lost_not_dangling() {
        // The pre-onOpen handshake window, or a reconnect backoff gap after the queue was cleared:
        // both the appends and the commit are dropped, so the server never raises an item. The seq
        // must NOT sit bindable forever (off-by-one poisoning + tail-stall); it resolves Lost locally.
        val h = connected()
        h.transport.open = false
        h.engine.sendAudio(byteArrayOf(1, 2))
        val seq = h.engine.commit()
        assertTrue(seq >= 0)

        val (rSeq, outcome) = h.l.next()
        assertEquals(seq, rSeq)
        assertTrue("a fully-dropped turn resolves Lost, rescued locally — never dangles", outcome is SegmentOutcome.Lost)

        // The orphan is gone from `pending`: no tail-stall, no awaitIdle timeout, nothing to mis-bind.
        assertTrue("no seq dangles after a socket-down commit", h.engine.awaitIdle(2_000))
        h.transport.listener.onCompleted("it_orphan", "ignored") // cannot resolve or bind the vanished turn
        assertEquals(1, h.l.all.size)
    }

    // ---------------------------------------------------------------- backpressure

    @Test fun backpressure_sheds_turn_as_Lost_never_blocks() {
        val h = connected(maxBacklog = 3)
        h.transport.stall() // the sender cannot drain past the first append
        // Pump well past the cap. Every call returns immediately (asserted by test 1); here we only
        // need the queue to overflow so the turn is marked shed.
        repeat(40) { h.engine.sendAudio(ByteArray(64)) }

        val seq = h.engine.commit()
        assertTrue("a shed turn still allocates a real seq", seq >= 0)
        val (rSeq, outcome) = h.l.next()
        assertEquals(seq, rSeq)
        assertTrue("shed -> Lost, not a silent drop", outcome is SegmentOutcome.Lost)
        assertTrue(
            "a shed turn must route to the local retry",
            FallbackPolicy.shouldFallBack(outcome),
        )
        h.transport.releaseGate()
    }

    @Test fun transport_backpressure_on_a_live_socket_sheds_the_turn_as_Lost() {
        // The primary stall mode the engine's own maxBacklog cannot see: the socket is alive and our
        // queue drains fine, but the transport reports its outbound buffer is over the cap and refuses
        // the append. A false append must shed the turn to the local fallback, not vanish.
        val h = connected()
        h.transport.refuseAppends = true // socket alive, but OkHttp's buffer is over MAX_OUTBOUND_BYTES
        repeat(5) { h.engine.sendAudio(ByteArray(64)) }

        // Wait until the sender has processed all five appends (each refused -> turn shed) so commit
        // observes the shed deterministically, with no sleep-and-hope.
        val deadline = System.currentTimeMillis() + 2_000
        while (h.transport.appendCalls.get() < 5 && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertEquals(5, h.transport.appendCalls.get())
        assertTrue("a refused append never reaches the socket", h.transport.appends.isEmpty())

        val seq = h.engine.commit()
        assertTrue("a shed turn still allocates a real seq", seq >= 0)
        val (rSeq, outcome) = h.l.next()
        assertEquals(seq, rSeq)
        assertTrue("transport backpressure -> Lost", outcome is SegmentOutcome.Lost)
        assertTrue("and routes to the local retry", FallbackPolicy.shouldFallBack(outcome))
    }

    // ---------------------------------------------------------------- the fatal latch

    @Test fun fatal_error_event_latches_once() {
        val h = connected()
        assertNull(h.engine.lastFatal())

        h.transport.listener.onErrorEvent("invalid_api_key", 42) // message LENGTH only, never content
        assertEquals(FatalKind.INVALID_KEY, h.engine.lastFatal())

        // A commit after the latch must fail fast to Lost rather than spend another charge.
        h.engine.sendAudio(byteArrayOf(1))
        val seq = h.engine.commit()
        assertTrue(seq >= 0)
        val (rSeq, outcome) = h.l.next()
        assertEquals(seq, rSeq)
        assertTrue(outcome is SegmentOutcome.Lost)

        // Latched: a second error event does not change or re-arm the latch.
        h.transport.listener.onErrorEvent("invalid_api_key", 9)
        assertEquals(FatalKind.INVALID_KEY, h.engine.lastFatal())
    }

    @Test fun fatal_latch_closes_the_transport_and_stops_further_appends() {
        // A latched fatal means every further append is wasted work on a doomed, paid session, and a
        // live socket that never gives up. The latch must close the socket and drop queued audio.
        val h = connected()
        h.transport.listener.onErrorEvent("invalid_api_key", 10)
        assertEquals(FatalKind.INVALID_KEY, h.engine.lastFatal())
        assertEquals("the latched session closes its socket", 1, h.transport.closes)

        h.engine.sendAudio(byteArrayOf(1, 2))
        Thread.sleep(100)
        assertTrue("no audio may leave after a fatal latch", h.transport.appends.isEmpty())
    }

    @Test fun handshake_fatal_latches_and_abandons_outstanding() {
        val h = connected()
        h.engine.sendAudio(byteArrayOf(1)); assertEquals(0L, h.engine.commit())

        h.transport.listener.onFatal(FatalKind.OUT_OF_CREDIT, 429)
        assertEquals(FatalKind.OUT_OF_CREDIT, h.engine.lastFatal())
        assertEquals(1, h.l.all.size)
        assertTrue(h.l.all.single().second is SegmentOutcome.Lost)
    }

    // ---------------------------------------------------------------- teardown + sessions

    @Test fun close_resolves_all_outstanding_and_is_idempotent() {
        val h = connected()
        h.engine.sendAudio(byteArrayOf(1)); assertEquals(0L, h.engine.commit())
        h.engine.sendAudio(byteArrayOf(2)); assertEquals(1L, h.engine.commit())

        h.engine.close()
        assertEquals(setOf(0L, 1L), h.l.all.map { it.first }.toSet())
        assertTrue(h.l.all.all { it.second is SegmentOutcome.Lost })
        assertEquals(1, h.l.closes.get())

        h.engine.close() // second close no-ops: no new resolutions, onClosed not fired again
        assertEquals(2, h.l.all.size)
        assertEquals(1, h.l.closes.get())
    }

    @Test fun connect_fires_onOpen_and_restarts_seq_numbering() {
        val h = connected()
        assertEquals(1, h.l.opens.get())
        h.engine.sendAudio(byteArrayOf(1)); assertEquals(0L, h.engine.commit())
        h.transport.listener.onCompleted("it_1", "one")

        val second = Rec()
        h.engine.connect(null, second)
        assertEquals(1, second.opens.get())
        h.engine.sendAudio(byteArrayOf(2))
        assertEquals("a fresh SegmentOrderer starts at head 0, so seq restarts with it", 0L, h.engine.commit())
    }

    // The audio-pipeline encode pin (sender_upsamples_16k_to_24k_and_base64_encodes_the_append)
    // relocated behind the seam to OpenAiRealtimeProtocolTest.append_upsamples_16k_to_24k_and_base64_encodes
    // when the engine stopped encoding and began handing raw 16 kHz PCM to the transport's protocol.
    // Bytes preserved verbatim — the single reviewed relocation (plan 2026-07-31 Task 1, Step 3).
}
