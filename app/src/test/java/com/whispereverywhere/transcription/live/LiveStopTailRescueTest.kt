package com.whispereverywhere.transcription.live

import com.whispereverywhere.transcription.SegmentOutcome
import com.whispereverywhere.transcription.TranscriptionEngine
import com.whispereverywhere.transcription.cloud.FallbackTranscriptionEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Collections

/**
 * The graceful-STOP tail rescue (Critical fix, 2026-07-31). In server-driven live mode the final open
 * utterance is never endpointed by the server — the mic is closed at stop, so no silence frame /
 * `<end>` / `committed_transcript` will ever arrive to resolve the tail seq the stop-commit allocated.
 * Before the fix that stranded the tail in `pending`: the service's finalize `awaitIdle` looped the
 * whole 300 s budget and then close() cleared the retained PCM before the tail resolved, dropping the
 * WHOLE last utterance as a bare marker with no on-device rescue.
 *
 * Unlike [LiveServerDrivenTurnTest] (which stubs the mirror rotation with a bare `engine.commit()`),
 * this suite wires the REAL [FallbackTranscriptionEngine] exactly as the service does —
 * `attachServerTurnRotation { fallback.commit() }` — so the retained-PCM rescue path is exercised end
 * to end, and asserts a stop with an open server turn both DRAINS FAST and RESCUES the tail locally.
 */
class LiveStopTailRescueTest {

    private val scopes = Collections.synchronizedList(mutableListOf<CoroutineScope>())

    @After fun cancelScopes() { scopes.forEach { it.cancel() } }

    // Not Unconfined: FallbackTranscriptionEngine calls cloud.commit() inside its mirror lock and an
    // unconfined dispatcher would run the resolver inline on the wrong thread (see its class KDoc).
    private fun scope(): CoroutineScope =
        CoroutineScope(Dispatchers.Default + SupervisorJob()).also { scopes += it }

    /** Records outbound sends; in server mode the engine must NEVER call [sendCommit]. */
    private class FakeTransport(val listener: RealtimeTransport.Listener) :
        LiveTranscriptionEngine.Transport {
        override fun connect(apiKey: String, language: String?) {}
        override fun sendAppend(pcm: ByteArray): Boolean = true
        override fun sendCommit(): Boolean = true
        override fun close() {}
    }

    /**
     * The on-device safety net. Buffers audio, hands out its OWN seqs, and — by default — resolves
     * each cut SYNCHRONOUSLY as `Text("local")`, modelling a same-thread executor (the re-entrant
     * resolve path [FallbackTranscriptionEngine] must handle). Its lifecycle callbacks are swallowed
     * upstream by the fallback's LocalRelay, so onOpen/onClosed here are harmless.
     */
    private class FakeLocal : TranscriptionEngine {
        @Volatile private var owner: TranscriptionEngine.Listener? = null
        private val buffer = ByteArrayOutputStream()
        private var nextSeq = 0L
        val commits = Collections.synchronizedList(mutableListOf<ByteArray>())

        override fun connect(language: String?, listener: TranscriptionEngine.Listener) {
            owner = listener; buffer.reset(); nextSeq = 0L; listener.onOpen()
        }
        override fun sendAudio(pcm: ByteArray) { synchronized(buffer) { buffer.write(pcm) } }
        override fun commit(): Long {
            val l = owner ?: return -1L
            val pcm = synchronized(buffer) {
                val s = buffer.toByteArray(); if (s.isEmpty()) return -1L; buffer.reset(); s
            }
            val seq = nextSeq++
            commits += pcm
            l.onSegmentResolved(seq, SegmentOutcome.Text("local")) // re-entrant, before commit returns
            return seq
        }
        override fun close() { owner?.onClosed(); owner = null }
        override fun shutdown() = close()
        override fun prewarm() {}
        override fun releaseContext() {}
        override fun awaitIdle(timeoutMs: Long): Boolean = true
    }

    private class Rec : TranscriptionEngine.Listener {
        val deltas = Collections.synchronizedList(mutableListOf<String>())
        val all = Collections.synchronizedList(mutableListOf<Pair<Long, SegmentOutcome>>())
        override fun onOpen() {}
        override fun onDelta(text: String) { deltas += text }
        override fun onSegmentResolved(seq: Long, outcome: SegmentOutcome) { all += seq to outcome }
        override fun onError(message: String) {}
        override fun onClosed() {}
    }

    private inner class Rig {
        lateinit var transport: FakeTransport
        val local = FakeLocal()
        val cloud = LiveTranscriptionEngine(
            apiKey = "sk-test",
            scope = scope(),
            minCommitBytes = 0,
            serverDriven = true,
        ) { listener -> FakeTransport(listener).also { transport = it } }
        val fallback = FallbackTranscriptionEngine(cloud, local, scope())
        val rec = Rec()

        init {
            cloud.attachServerTurnRotation { fallback.commit() } // exactly how the service wires it
            fallback.connect(null, rec)
        }

        /** What the service's stopRecording() does for a live session: stop-commit, then finalize. */
        fun stop() { fallback.commit(); cloud.finishServerTurns() }
    }

    // ---- the flagship case: stop mid-utterance, nothing committed during the session -------------

    @Test fun a_stop_with_an_open_server_turn_drains_fast_and_rescues_the_tail_on_device() {
        val r = Rig()

        // Open turn: audio streams and partials preview, but the SERVER never cuts a boundary — the
        // user stops mid-utterance, so nothing is committed during the whole session.
        r.fallback.sendAudio(byteArrayOf(1, 2, 3, 4))
        r.transport.listener.onDelta("it_1", "hello wor")
        assertEquals(listOf("hello wor"), r.rec.deltas.toList())
        assertEquals("no turn resolved during the open session", 0, r.rec.all.size)

        r.stop()

        // Drains FAST: the tail was resolved at stop, so pending is empty and awaitIdle does not loop
        // the budget. Before the fix this returned false after spinning the entire timeout.
        assertTrue("the finalize drain completes promptly", r.fallback.awaitIdle(2_000))

        // Rescued ON-DEVICE: local got exactly the tail's PCM and its transcript reaches the listener.
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), r.local.commits.single())
        assertEquals(1, r.rec.all.size)
        assertEquals(0L to SegmentOutcome.Text("local"), r.rec.all.single())

        // Exactly-once holds: a stray late completion for the vanished tail is a no-op.
        r.transport.listener.onCompleted("it_late", "ignored")
        assertEquals(1, r.rec.all.size)
    }

    // ---- mixed: a completed cloud turn survives, only the open tail falls back at stop -----------

    @Test fun a_completed_cloud_turn_survives_and_only_the_open_tail_is_rescued_at_stop() {
        val r = Rig()

        // First turn: audio, a server boundary, then a completion — resolved via the CLOUD, no rescue.
        r.fallback.sendAudio(byteArrayOf(1, 1))
        r.transport.listener.onCommitted("it_1") // rotate the mirror + allocate seq 0
        r.transport.listener.onCompleted("it_1", "first turn")
        assertEquals(0L to SegmentOutcome.Text("first turn"), r.rec.all.single())
        assertEquals("a completed cloud turn does NOT fall back to local", 0, r.local.commits.size)

        // Second (open) turn: audio + partials, but the user stops before the server cuts it.
        r.fallback.sendAudio(byteArrayOf(2, 2, 2))
        r.transport.listener.onDelta("it_2", "second")

        r.stop()
        assertTrue(r.fallback.awaitIdle(2_000))

        // Only the tail (seq 1) falls back, with exactly its own PCM; the first turn is untouched.
        assertArrayEquals(byteArrayOf(2, 2, 2), r.local.commits.single())
        assertEquals(2, r.rec.all.size)
        assertEquals(1L to SegmentOutcome.Text("local"), r.rec.all[1])
    }
}
