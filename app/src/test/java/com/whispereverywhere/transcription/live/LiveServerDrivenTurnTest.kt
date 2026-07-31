package com.whispereverywhere.transcription.live

import com.whispereverywhere.transcription.SegmentOutcome
import com.whispereverywhere.transcription.TranscriptionEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * The SERVER-DRIVEN inversion (plan 2026-07-31 Task 1). In the open-mic live modes the SERVER cuts
 * turns: a server turn event ([onCommitted] / a drop) rotates the fallback mirror AND allocates the
 * seq as one operation, and NO client `input_audio_buffer.commit` is ever sent. This suite drives a
 * fake [LiveTranscriptionEngine.Transport] and stands in for the fallback mirror rotation with a
 * callback that re-enters `engine.commit()` (exactly what the service wires to `fallback.commit()`),
 * asserting the seq pairing, delta-before-completion, exactly-once, drop-mid-turn rescue, and the
 * no-segment bind guard.
 *
 * The client-mode ledger (`serverDriven = false`) is unchanged and pinned by
 * [LiveTranscriptionEngineTest]; this suite only exercises the server-driven branch.
 */
class LiveServerDrivenTurnTest {

    private val scopes = Collections.synchronizedList(mutableListOf<CoroutineScope>())

    @After fun cancelScopes() { scopes.forEach { it.cancel() } }

    private fun scope(): CoroutineScope =
        CoroutineScope(Dispatchers.Default + SupervisorJob()).also { scopes += it }

    /** Records outbound sends. In server mode the engine must NEVER call [sendCommit]. */
    private class FakeTransport(val listener: RealtimeTransport.Listener) :
        LiveTranscriptionEngine.Transport {
        val appends = Collections.synchronizedList(mutableListOf<ByteArray>())
        val commits = AtomicInteger(0)
        override fun connect(apiKey: String, language: String?) {}
        override fun sendAppend(pcm: ByteArray): Boolean { appends += pcm; return true }
        override fun sendCommit(): Boolean { commits.incrementAndGet(); return true }
        override fun close() {}
    }

    /** Records deltas and resolutions in ONE ordered log so "delta strictly before completion" is provable. */
    private class Rec : TranscriptionEngine.Listener {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val resolved = Collections.synchronizedList(mutableListOf<Pair<Long, SegmentOutcome>>())
        override fun onOpen() {}
        override fun onDelta(text: String) { events += "delta:$text" }
        override fun onSegmentResolved(seq: Long, outcome: SegmentOutcome) {
            events += "resolved:$seq:${outcome.javaClass.simpleName}"
            resolved += seq to outcome
        }
        override fun onError(message: String) {}
        override fun onClosed() {}
    }

    private inner class Harness {
        lateinit var transport: FakeTransport
        val l = Rec()
        val rotations = AtomicInteger(0)
        val engine = LiveTranscriptionEngine(
            apiKey = "sk-test",
            scope = scope(),
            minCommitBytes = 0,
            serverDriven = true,
        ) { listener -> FakeTransport(listener).also { transport = it } }

        init {
            // Stand in for FallbackTranscriptionEngine.commit(): rotate the mirror (a no-op in this
            // fake) and allocate the paired seq by re-entering the engine's own commit(), returning it.
            engine.attachServerTurnRotation { rotations.incrementAndGet(); engine.commit() }
            engine.connect(null, l)
        }
    }

    private fun connected(): Harness = Harness()

    // ---- deltas stream before the turn completes, and the seq is server-allocated ----------------

    @Test fun a_server_turn_streams_deltas_then_allocates_a_seq_and_completes_it() {
        val h = connected()
        h.engine.sendAudio(byteArrayOf(1, 2, 3, 4))

        // Deltas arrive AS SPOKEN, before any turn boundary.
        h.transport.listener.onDelta("it_1", "hel")
        h.transport.listener.onDelta("it_1", "hello wor")

        // The server cuts the turn: the rotation fires (allocating seq 0) and the item binds to it.
        h.transport.listener.onCommitted("it_1")
        assertEquals("the rotation fired exactly once for the boundary", 1, h.rotations.get())

        // Then the completion resolves the just-allocated seq.
        h.transport.listener.onCompleted("it_1", "hello world")

        assertEquals(
            "deltas are recorded strictly BEFORE the resolution",
            listOf("delta:hel", "delta:hello wor", "resolved:0:Text"),
            h.l.events,
        )
        assertEquals(0L to SegmentOutcome.Text("hello world"), h.l.resolved.single())
    }

    // ---- no client commit is ever sent in server-driven mode -------------------------------------

    @Test fun a_full_server_driven_turn_sends_zero_client_commits() {
        val h = connected()
        h.engine.sendAudio(byteArrayOf(1, 2, 3, 4))
        h.transport.listener.onDelta("it_1", "hi")
        h.transport.listener.onCommitted("it_1")
        h.transport.listener.onCompleted("it_1", "hi there")
        // Give the sender a moment; it drains appends but must enqueue no commit.
        Thread.sleep(50)
        assertEquals("the SERVER commits — the engine sends no client commit", 0, h.transport.commits.get())
    }

    // ---- exactly-once: a duplicate completion resolves the seq only once -------------------------

    @Test fun a_duplicate_completion_resolves_the_server_turn_exactly_once() {
        val h = connected()
        h.engine.sendAudio(byteArrayOf(1))
        h.transport.listener.onCommitted("it_1")
        h.transport.listener.onCompleted("it_1", "once")
        h.transport.listener.onCompleted("it_1", "twice") // duplicate
        assertEquals(1, h.l.resolved.size)
        assertEquals(0L to SegmentOutcome.Text("once"), h.l.resolved.single())
    }

    // ---- drop mid-turn: the tail is rotated under a fresh seq then resolved Lost, once ------------

    @Test fun a_drop_mid_turn_rotates_the_tail_and_resolves_it_Lost_exactly_once() {
        val h = connected()
        h.engine.sendAudio(byteArrayOf(1, 2, 3, 4))
        h.transport.listener.onDelta("it_1", "half a sen")

        // No committed arrives — the socket drops. The engine rotates the in-progress tail under a
        // fresh seq (rescued by the fallback), then resolves it Lost.
        h.transport.listener.onDisconnected()
        assertEquals("the drop rotated the tail exactly once", 1, h.rotations.get())
        assertEquals(1, h.l.resolved.size)
        val (seq, outcome) = h.l.resolved.single()
        assertEquals(0L, seq)
        assertTrue("the dropped tail is Lost (fallback rescues it)", outcome is SegmentOutcome.Lost)

        // A later stray completion for the vanished turn is a no-op.
        h.transport.listener.onCompleted("it_late", "ignored")
        assertEquals(1, h.l.resolved.size)
    }

    // ---- NO_SEGMENT bind guard: a boundary with no new audio binds and resolves nothing ----------

    @Test fun a_committed_with_no_audio_since_the_last_boundary_binds_nothing() {
        val h = connected()
        // First real turn.
        h.engine.sendAudio(byteArrayOf(1))
        h.transport.listener.onCommitted("it_1")
        h.transport.listener.onCompleted("it_1", "first")
        assertEquals(1, h.l.resolved.size)

        // A second committed with NO audio buffered since: the rotation returns NO_SEGMENT (-1), so
        // the item binds nothing — it must not attach to a stale earlier seq.
        val rotationsBefore = h.rotations.get()
        h.transport.listener.onCommitted("it_2")
        assertEquals("the rotation was consulted", rotationsBefore + 1, h.rotations.get())
        // A completion for the unbound item resolves nothing (no seq to resolve).
        h.transport.listener.onCompleted("it_2", "stray")
        assertEquals("no second resolution from a no-audio boundary", 1, h.l.resolved.size)
    }
}
