package com.whispereverywhere.transcription.cloud

import com.whispereverywhere.provider.ProviderId
import com.whispereverywhere.transcription.SegmentOutcome
import com.whispereverywhere.transcription.TranscriptionEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * The engine's contract is the same one [com.whispereverywhere.transcription.LocalWhisperEngine]
 * satisfies, so these tests assert the same invariants Release A depends on — plus the three that
 * only a networked engine can break: unbounded concurrency, a blocked audio thread, and a fatal
 * error charged forty times over.
 */
class CloudTranscriptionEngineTest {

    private val scopes = Collections.synchronizedList(mutableListOf<CoroutineScope>())

    @After fun cancelScopes() {
        scopes.forEach { it.cancel() }
    }

    private fun scope(): CoroutineScope =
        CoroutineScope(Dispatchers.Default + SupervisorJob()).also { scopes += it }

    // ---------------------------------------------------------------- fakes

    private class FakeStt(
        private val gate: (suspend (Int, ByteArray) -> Unit)? = null,
        private val respond: (Int, ByteArray) -> SttResult = { _, _ -> SttResult.Text("ok") },
    ) : SttProvider {
        override val id = ProviderId.OPENAI
        override val maxRequestBytes = 25L * 1024 * 1024

        val calls = AtomicInteger(0)
        val concurrent = AtomicInteger(0)
        val peakConcurrent = AtomicInteger(0)
        val payloads = Collections.synchronizedList(mutableListOf<ByteArray>())
        val languages = Collections.synchronizedList(mutableListOf<String?>())

        override suspend fun transcribe(pcm: ByteArray, language: String?): SttResult {
            val n = calls.getAndIncrement()
            payloads += pcm
            languages += language
            val now = concurrent.incrementAndGet()
            peakConcurrent.getAndUpdate { seen -> maxOf(seen, now) }
            try {
                gate?.invoke(n, pcm)
                return respond(n, pcm)
            } finally {
                concurrent.decrementAndGet()
            }
        }
    }

    private class Rec : TranscriptionEngine.Listener {
        val opens = AtomicInteger(0)
        val closes = AtomicInteger(0)
        val errors = Collections.synchronizedList(mutableListOf<String>())
        val all = Collections.synchronizedList(mutableListOf<Pair<Long, SegmentOutcome>>())
        private val queue = LinkedBlockingQueue<Pair<Long, SegmentOutcome>>()

        override fun onOpen() { opens.incrementAndGet() }
        override fun onDelta(text: String) = Unit
        override fun onSegmentResolved(seq: Long, outcome: SegmentOutcome) {
            all += seq to outcome
            queue.put(seq to outcome)
        }
        override fun onError(message: String) { errors += message }
        override fun onClosed() { closes.incrementAndGet() }

        /** Blocks for the next resolution in ARRIVAL order (not seq order). */
        fun next(timeoutMs: Long = 5_000): Pair<Long, SegmentOutcome> {
            val e = queue.poll(timeoutMs, TimeUnit.MILLISECONDS)
            assertNotNull("expected a resolution within ${timeoutMs}ms", e)
            return e!!
        }
    }

    private fun fatal(kind: FatalKind = FatalKind.INVALID_KEY, msg: String = "Key rejected") =
        SttResult.Failed(SttError.Fatal(kind, msg))

    // ---------------------------------------------------------------- lifecycle

    @Test fun connect_opens_and_close_closes() {
        val e = CloudTranscriptionEngine(FakeStt(), scope())
        val l = Rec()
        e.connect(null, l)
        assertEquals(1, l.opens.get())
        e.close()
        assertEquals(1, l.closes.get())
    }

    @Test fun commit_without_a_listener_allocates_no_seq() {
        val p = FakeStt()
        val e = CloudTranscriptionEngine(p, scope())
        e.sendAudio(byteArrayOf(1, 2))
        assertEquals("no session, so nothing is owed a resolution", -1L, e.commit())

        val l = Rec()
        e.connect(null, l)
        e.close()
        e.sendAudio(byteArrayOf(3, 4))
        assertEquals(-1L, e.commit())
        assertEquals(0, p.calls.get())
    }

    @Test fun commit_with_an_empty_buffer_allocates_no_seq() {
        val p = FakeStt()
        val e = CloudTranscriptionEngine(p, scope())
        e.connect(null, Rec())
        assertEquals(-1L, e.commit())
        assertEquals(0, p.calls.get())
    }

    // ---------------------------------------------------------------- the audio thread

    @Test fun sendAudio_alone_issues_no_request() {
        val p = FakeStt()
        val e = CloudTranscriptionEngine(p, scope())
        e.connect(null, Rec())
        repeat(100) { e.sendAudio(ByteArray(640)) }
        assertEquals("audio is buffered, not streamed per chunk", 0, p.calls.get())
        assertTrue(e.awaitIdle(1_000))
    }

    @Test fun sendAudio_does_not_block_while_every_request_slot_is_saturated() {
        // sendAudio runs on the AudioRecord thread every ~32 ms and also drives the waveform.
        // If it ever waited on the network the waveform would freeze and audio would be dropped.
        val release = CompletableDeferred<Unit>()
        val inFlight = CountDownLatch(3)
        val p = FakeStt(gate = { _, _ -> inFlight.countDown(); release.await() })
        val e = CloudTranscriptionEngine(p, scope())
        e.connect(null, Rec())
        repeat(3) { e.sendAudio(ByteArray(1024)); e.commit() }
        assertTrue("3 requests should be stuck on the network", inFlight.await(5_000, TimeUnit.MILLISECONDS))

        val chunk = ByteArray(1024)
        val startNs = System.nanoTime()
        repeat(500) { e.sendAudio(chunk) }
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000
        assertTrue("sendAudio blocked for ${elapsedMs}ms with the network saturated", elapsedMs < 500)
        release.complete(Unit)
    }

    // ---------------------------------------------------------------- the request

    @Test fun commit_uploads_exactly_the_buffered_pcm_and_clears_the_buffer() {
        val p = FakeStt()
        val e = CloudTranscriptionEngine(p, scope())
        val l = Rec()
        e.connect(null, l)
        e.sendAudio(byteArrayOf(1, 2))
        e.sendAudio(byteArrayOf(3, 4))
        assertEquals(0L, e.commit())
        assertTrue(e.awaitIdle(5_000))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), p.payloads.single())
        assertEquals("the buffer was consumed by the first commit", -1L, e.commit())
        assertEquals(1, p.calls.get())
    }

    @Test fun the_language_hint_is_forwarded_to_the_provider() {
        val p = FakeStt()
        val e = CloudTranscriptionEngine(p, scope())
        e.connect("de", Rec())
        e.sendAudio(byteArrayOf(1))
        e.commit()
        assertTrue(e.awaitIdle(5_000))
        assertEquals("de", p.languages.single())
    }

    @Test fun a_transcript_resolves_as_trimmed_text() {
        val p = FakeStt(respond = { _, _ -> SttResult.Text("  hello world \n") })
        val e = CloudTranscriptionEngine(p, scope())
        val l = Rec()
        e.connect(null, l)
        e.sendAudio(byteArrayOf(1)); e.commit()
        assertTrue(e.awaitIdle(5_000))
        assertEquals(SegmentOutcome.Text("hello world"), l.all.single().second)
    }

    @Test fun an_empty_transcript_is_empty_expected_not_a_loss() {
        // The user said nothing. Marking it Lost would stamp a "[…]" on ordinary silence and,
        // worse, arm a fallback that re-runs the same silence for money.
        val p = FakeStt(respond = { _, _ -> SttResult.Text("") })
        val e = CloudTranscriptionEngine(p, scope())
        val l = Rec()
        e.connect(null, l)
        e.sendAudio(byteArrayOf(1)); e.commit()
        assertTrue(e.awaitIdle(5_000))
        assertEquals(SegmentOutcome.EmptyExpected, l.all.single().second)
    }

    @Test fun a_whitespace_only_transcript_is_empty_expected() {
        val p = FakeStt(respond = { _, _ -> SttResult.Text("   \n  ") })
        val e = CloudTranscriptionEngine(p, scope())
        val l = Rec()
        e.connect(null, l)
        e.sendAudio(byteArrayOf(1)); e.commit()
        assertTrue(e.awaitIdle(5_000))
        assertEquals(SegmentOutcome.EmptyExpected, l.all.single().second)
    }

    // ---------------------------------------------------------------- identity and ordering

    @Test fun seq_starts_at_zero_and_increments_once_per_committed_segment() {
        val e = CloudTranscriptionEngine(FakeStt(), scope())
        e.connect(null, Rec())
        val seqs = (0 until 3).map { i -> e.sendAudio(byteArrayOf(i.toByte())); e.commit() }
        assertEquals(listOf(0L, 1L, 2L), seqs)
        assertTrue(e.awaitIdle(5_000))
    }

    @Test fun out_of_order_completion_keeps_each_transcript_on_its_own_seq() {
        // Three requests run concurrently, so completion order is NOT commit order. seq is
        // allocated with the PCM snapshot under the buffer lock, so identity follows AUDIO order
        // and SegmentOrderer can still release strictly in sequence.
        val holdFirst = CompletableDeferred<Unit>()
        val p = FakeStt(
            gate = { _, pcm -> if (pcm[0] == 1.toByte()) holdFirst.await() },
            respond = { _, pcm -> SttResult.Text(if (pcm[0] == 1.toByte()) "first" else "second") },
        )
        val e = CloudTranscriptionEngine(p, scope())
        val l = Rec()
        e.connect(null, l)
        e.sendAudio(byteArrayOf(1)); assertEquals(0L, e.commit())
        e.sendAudio(byteArrayOf(2)); assertEquals(1L, e.commit())

        val early = l.next()
        assertEquals("the later segment finished first", 1L, early.first)
        assertEquals(SegmentOutcome.Text("second"), early.second)

        holdFirst.complete(Unit)
        val late = l.next()
        assertEquals(0L, late.first)
        assertEquals(SegmentOutcome.Text("first"), late.second)
    }

    @Test fun every_committed_seq_resolves_exactly_once() {
        val p = FakeStt(respond = { n, _ ->
            when (n % 4) {
                0 -> SttResult.Text("t$n")
                1 -> SttResult.Text("")
                2 -> SttResult.Failed(SttError.Transient(null))
                else -> SttResult.Failed(SttError.Offline)
            }
        })
        val e = CloudTranscriptionEngine(p, scope())
        val l = Rec()
        e.connect(null, l)
        repeat(50) { i -> e.sendAudio(byteArrayOf(i.toByte())); e.commit() }
        assertTrue(e.awaitIdle(10_000))
        val seqs = l.all.map { it.first }
        assertEquals("no seq resolved twice", seqs.size, seqs.toSet().size)
        assertEquals((0L until 50L).toList(), seqs.sorted())
    }

    @Test fun concurrency_is_capped_at_three_in_flight() {
        // 3 is only safe because Release A landed strict in-order release. A fourth concurrent
        // upload is a fourth simultaneous TLS connection on a phone radio.
        val release = CompletableDeferred<Unit>()
        val three = CountDownLatch(3)
        val four = CountDownLatch(4)
        val p = FakeStt(gate = { _, _ -> three.countDown(); four.countDown(); release.await() })
        val e = CloudTranscriptionEngine(p, scope())
        val l = Rec()
        e.connect(null, l)
        repeat(10) { i -> e.sendAudio(byteArrayOf(i.toByte())); e.commit() }

        assertTrue("3 should start immediately", three.await(5_000, TimeUnit.MILLISECONDS))
        assertFalse("a 4th must wait for a permit", four.await(300, TimeUnit.MILLISECONDS))
        assertEquals(3, p.calls.get())

        release.complete(Unit)
        assertTrue(e.awaitIdle(10_000))
        assertEquals(10, p.calls.get())
        assertTrue("peak concurrency was ${p.peakConcurrent.get()}", p.peakConcurrent.get() <= 3)
        assertEquals(10, l.all.size)
    }

    // ---------------------------------------------------------------- the fatal latch

    @Test fun a_fatal_error_latches_so_one_bad_key_costs_one_request_not_twenty() {
        val p = FakeStt(respond = { _, _ -> fatal() })
        val e = CloudTranscriptionEngine(p, scope(), maxInFlight = 1)
        val l = Rec()
        e.connect(null, l)
        repeat(20) { i -> e.sendAudio(byteArrayOf(i.toByte())); e.commit() }
        assertTrue(e.awaitIdle(10_000))
        assertEquals("the rejected key must be paid for once", 1, p.calls.get())
    }

    @Test fun a_latched_fatal_still_resolves_every_later_seq() {
        // Skipping the request must NOT skip the resolution — an unresolved seq stalls the
        // orderer head forever and holds every later segment with it.
        val p = FakeStt(respond = { _, _ -> fatal(FatalKind.OUT_OF_CREDIT, "Account has no remaining credit") })
        val e = CloudTranscriptionEngine(p, scope(), maxInFlight = 1)
        val l = Rec()
        e.connect(null, l)
        repeat(20) { i -> e.sendAudio(byteArrayOf(i.toByte())); e.commit() }
        assertTrue(e.awaitIdle(10_000))
        assertEquals((0L until 20L).toList(), l.all.map { it.first }.sorted())
        assertTrue(l.all.all { it.second is SegmentOutcome.Lost })
        assertEquals(
            "Account has no remaining credit",
            (l.all.last().second as SegmentOutcome.Lost).reason,
        )
    }

    @Test fun lastFatal_exposes_the_latched_error_for_the_fallback_valve() {
        val p = FakeStt(respond = { _, _ -> fatal(FatalKind.OUT_OF_CREDIT, "Account has no remaining credit") })
        val e = CloudTranscriptionEngine(p, scope(), maxInFlight = 1)
        assertNull(e.lastFatal())
        e.connect(null, Rec())
        e.sendAudio(byteArrayOf(1)); e.commit()
        assertTrue(e.awaitIdle(5_000))
        assertEquals(FatalKind.OUT_OF_CREDIT, e.lastFatal()?.kind)
    }

    @Test fun a_transient_error_does_not_latch() {
        val p = FakeStt(respond = { _, _ -> SttResult.Failed(SttError.Transient(null)) })
        val e = CloudTranscriptionEngine(p, scope(), maxInFlight = 1)
        val l = Rec()
        e.connect(null, l)
        repeat(5) { i -> e.sendAudio(byteArrayOf(i.toByte())); e.commit() }
        assertTrue(e.awaitIdle(10_000))
        assertEquals("a rate limit is not a dead key", 5, p.calls.get())
        assertNull(e.lastFatal())
        assertEquals(5, l.all.size)
    }

    @Test fun an_offline_error_does_not_latch() {
        val p = FakeStt(respond = { _, _ -> SttResult.Failed(SttError.Offline) })
        val e = CloudTranscriptionEngine(p, scope(), maxInFlight = 1)
        val l = Rec()
        e.connect(null, l)
        repeat(5) { i -> e.sendAudio(byteArrayOf(i.toByte())); e.commit() }
        assertTrue(e.awaitIdle(10_000))
        assertEquals("a tunnel is not a dead key", 5, p.calls.get())
        assertNull(e.lastFatal())
        assertEquals(SegmentOutcome.Lost("offline"), l.all.first().second)
    }

    @Test fun a_bad_segment_does_not_latch() {
        val p = FakeStt(respond = { _, _ -> SttResult.Failed(SttError.BadSegment) })
        val e = CloudTranscriptionEngine(p, scope(), maxInFlight = 1)
        val l = Rec()
        e.connect(null, l)
        repeat(5) { i -> e.sendAudio(byteArrayOf(i.toByte())); e.commit() }
        assertTrue(e.awaitIdle(10_000))
        assertEquals("one oversized segment must not disable the provider", 5, p.calls.get())
        assertNull(e.lastFatal())
        assertEquals(SegmentOutcome.Lost("segment rejected"), l.all.first().second)
    }

    @Test fun a_provider_that_throws_resolves_lost_rather_than_stalling_the_orderer() {
        val p = FakeStt(gate = { _, _ -> throw IllegalStateException("boom") })
        val e = CloudTranscriptionEngine(p, scope())
        val l = Rec()
        e.connect(null, l)
        e.sendAudio(byteArrayOf(1)); e.commit()
        assertTrue(e.awaitIdle(5_000))
        assertEquals(SegmentOutcome.Lost("boom"), l.all.single().second)
    }

    // ---------------------------------------------------------------- draining and teardown

    @Test fun awaitIdle_is_false_while_a_request_is_outstanding_and_true_once_it_drains() {
        val release = CompletableDeferred<Unit>()
        val started = CountDownLatch(1)
        val p = FakeStt(
            gate = { _, _ -> started.countDown(); release.await() },
            respond = { _, _ -> SttResult.Text("done") },
        )
        val e = CloudTranscriptionEngine(p, scope())
        val l = Rec()
        e.connect(null, l)
        e.sendAudio(byteArrayOf(1)); e.commit()
        assertTrue(started.await(5_000, TimeUnit.MILLISECONDS))

        assertFalse("must not claim idle with a request in flight", e.awaitIdle(200))
        assertEquals(0, l.all.size)

        release.complete(Unit)
        assertTrue(e.awaitIdle(5_000))
        assertEquals(SegmentOutcome.Text("done"), l.all.single().second)
    }

    @Test fun close_resolves_every_outstanding_seq_exactly_once() {
        val release = CompletableDeferred<Unit>()
        val started = CountDownLatch(1)
        val p = FakeStt(gate = { _, _ -> started.countDown(); release.await() })
        val e = CloudTranscriptionEngine(p, scope())
        val l = Rec()
        e.connect(null, l)
        e.sendAudio(byteArrayOf(1)); assertEquals(0L, e.commit())
        assertTrue(started.await(5_000, TimeUnit.MILLISECONDS))

        e.close()
        assertEquals("cancelling must not leave the seq dangling", 1, l.all.size)
        assertEquals(0L, l.all.single().first)
        assertTrue(l.all.single().second is SegmentOutcome.Lost)

        release.complete(Unit)
        Thread.sleep(200)
        assertEquals("and it must not resolve a second time", 1, l.all.size)
    }

    @Test fun shutdown_resolves_outstanding_work_and_closes_the_session() {
        val release = CompletableDeferred<Unit>()
        val started = CountDownLatch(1)
        val p = FakeStt(gate = { _, _ -> started.countDown(); release.await() })
        val e = CloudTranscriptionEngine(p, scope())
        val l = Rec()
        e.connect(null, l)
        e.sendAudio(byteArrayOf(1)); e.commit()
        assertTrue(started.await(5_000, TimeUnit.MILLISECONDS))

        e.shutdown()
        assertEquals(1, l.all.size)
        assertEquals(1, l.closes.get())
        release.complete(Unit)
    }

    @Test fun a_dead_scope_still_resolves_the_seq_it_handed_out() {
        // commit() returned a seq, so the orderer is waiting on it. If the scope is already gone
        // the coroutine body never runs at all — the resolution has to come from somewhere else.
        val s = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val p = FakeStt()
        val e = CloudTranscriptionEngine(p, s)
        val l = Rec()
        e.connect(null, l)
        s.cancel()
        e.sendAudio(byteArrayOf(1))
        assertEquals(0L, e.commit())
        val (seq, outcome) = l.next()
        assertEquals(0L, seq)
        assertTrue(outcome is SegmentOutcome.Lost)
        assertEquals(0, p.calls.get())
    }

    // ---------------------------------------------------------------- session boundaries

    @Test fun connect_restarts_seq_numbering_and_clears_the_latched_fatal() {
        // A fresh SegmentOrderer starts at head 0, so seq must restart with it; and the user may
        // have fixed the key between sessions, so the latch must not outlive the session.
        val p = FakeStt(respond = { n, _ -> if (n == 0) fatal() else SttResult.Text("back") })
        val e = CloudTranscriptionEngine(p, scope(), maxInFlight = 1)
        val first = Rec()
        e.connect(null, first)
        e.sendAudio(byteArrayOf(1)); e.commit()
        assertTrue(e.awaitIdle(5_000))
        assertNotNull(e.lastFatal())
        e.close()

        val second = Rec()
        e.connect(null, second)
        assertNull("a new session must not inherit the old session's latch", e.lastFatal())
        e.sendAudio(byteArrayOf(2))
        assertEquals("seq restarts at 0 with the new orderer", 0L, e.commit())
        assertTrue(e.awaitIdle(5_000))
        assertEquals(SegmentOutcome.Text("back"), second.all.single().second)
    }

    @Test fun a_straggler_resolves_to_the_session_that_committed_it_not_the_next_one() {
        // seq restarts at 0 per session, so delivering an old seq to the NEW listener would look
        // like the new session's first segment and corrupt its transcript.
        val release = CompletableDeferred<Unit>()
        val started = CountDownLatch(1)
        val p = FakeStt(gate = { _, _ -> started.countDown(); release.await() })
        val e = CloudTranscriptionEngine(p, scope())
        val first = Rec()
        e.connect(null, first)
        e.sendAudio(byteArrayOf(1)); e.commit()
        assertTrue(started.await(5_000, TimeUnit.MILLISECONDS))

        val second = Rec()
        e.connect(null, second)
        release.complete(Unit)
        Thread.sleep(200)

        assertEquals("the new session never sees the old seq", 0, second.all.size)
        assertEquals(1, first.all.size)
        assertEquals(0L, first.all.single().first)
    }
}
