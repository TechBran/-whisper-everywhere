package com.whispereverywhere.transcription.live

import com.whispereverywhere.transcription.cloud.FatalKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * ElevenLabs Scribe v2 Realtime behind the [RealtimeProtocol] seam, driven through a fake
 * [SessionControl] + a recording [RealtimeTransport.Listener] and a manual [ReconnectScheduler], with
 * VERBATIM doc JSON fed to the real [ElevenLabsEvents] parser. The shared bootstrap/dispatch/fatal
 * contract lives in [RealtimeProtocolContractTest]; this suite pins what is ElevenLabs-specific: the
 * commit-on-last-chunk fold, the single-in-flight serialization, timeout -> Lost, and the length-only
 * error mapping.
 */
class ElevenLabsRealtimeProtocolTest {

    /** Records every frame the protocol sends; [accept] models backpressure/socket-down (false). */
    private class RecordingControl(private val accept: Boolean = true) : SessionControl {
        val frames = mutableListOf<Frame>()
        var rotates = 0
        override fun send(frame: Frame): Boolean { frames += frame; return accept }
        override fun rotate() { rotates++ }
        val texts get() = frames.map { (it as Frame.Text).json }
    }

    private class RecordingListener : RealtimeTransport.Listener {
        val deltas = mutableListOf<Pair<String, String>>()
        val completed = mutableListOf<Pair<String, String>>()
        val committed = mutableListOf<String>()
        val failed = mutableListOf<String>()
        val errors = mutableListOf<Pair<String?, Int>>()
        val fatals = mutableListOf<Pair<FatalKind, Int>>()
        override fun onConnected() {}
        override fun onDelta(itemId: String, text: String) { deltas += itemId to text }
        override fun onCompleted(itemId: String, transcript: String) { completed += itemId to transcript }
        override fun onCommitted(itemId: String) { committed += itemId }
        override fun onTranscriptionFailed(itemId: String) { failed += itemId }
        override fun onErrorEvent(code: String?, messageLength: Int) { errors += code to messageLength }
        override fun onDisconnected() {}
        override fun onFatal(kind: FatalKind, code: Int) { fatals += kind to code }
    }

    /** Captures scheduled timeout tasks; [fireAll] runs the ones armed so far (snapshot). */
    private class ManualScheduler : ReconnectScheduler {
        val delays = mutableListOf<Long>()
        private val tasks = mutableListOf<() -> Unit>()
        override fun schedule(delayMs: Long, task: () -> Unit) { delays += delayMs; tasks += task }
        fun fireAll() { val snapshot = tasks.toList(); tasks.clear(); snapshot.forEach { it() } }
    }

    private val control = RecordingControl()
    private val sink = RecordingListener()
    private val scheduler = ManualScheduler()

    private fun protocol(ctrl: SessionControl = control): ElevenLabsRealtimeProtocol =
        ElevenLabsRealtimeProtocol(scheduler).apply { bind(ctrl, sink) }

    // ---- codec shape (verbatim wire) ------------------------------------------------------------

    @Test fun audio_chunk_json_shape_is_exact() {
        assertEquals(
            """{"message_type":"input_audio_chunk","audio_base_64":"YWJj","commit":true,"sample_rate":16000}""",
            ElevenLabsEvents.audioChunk("YWJj", commit = true),
        )
    }

    // ---- wire facts -----------------------------------------------------------------------------

    @Test fun endpoint_is_the_pinned_scribe_v2_realtime_url() {
        assertEquals(
            "wss://api.elevenlabs.io/v1/speech-to-text/realtime?model_id=scribe_v2_realtime",
            protocol().endpoint,
        )
    }

    @Test fun upgrade_header_is_the_bare_xi_api_key() {
        // NOT a Bearer scheme — the value is the raw key (ProviderCatalog: ElevenLabs sends it bare).
        assertEquals(listOf("xi-api-key" to "el-secret"), protocol().upgradeHeaders("el-secret"))
    }

    @Test fun elevenlabs_does_not_use_the_tolerant_beta_retry() {
        assertFalse(protocol().tolerant4xxRetry)
    }

    @Test fun bootstrap_sends_no_config_frame() {
        assertTrue(protocol().bootstrap("el-secret", "en").isEmpty())
    }

    // ---- deltas are preview only ----------------------------------------------------------------

    @Test fun partial_transcript_dispatches_onDelta_and_never_binds_or_completes() {
        val p = protocol()
        p.onText("""{"message_type":"partial_transcript","text":"hello wor"}""")
        assertEquals(listOf("" to "hello wor"), sink.deltas)
        assertTrue("a partial never binds a turn", sink.committed.isEmpty())
        assertTrue("a partial never completes a turn", sink.completed.isEmpty())
    }

    // ---- the commit-on-last-chunk fold ----------------------------------------------------------

    @Test fun first_append_is_held_and_only_flushes_once_the_next_append_folds_it() {
        val p = protocol()
        // Fold slot: the newest append is held back by one, so the FIRST append sends nothing.
        assertTrue(p.onAppend(pcm(shortArrayOf(1, 2))))
        assertEquals("first append is held, not sent", 0, control.frames.size)

        // The second append flushes the first commit:false and holds itself.
        assertTrue(p.onAppend(pcm(shortArrayOf(3, 4))))
        assertEquals(1, control.frames.size)
        assertEquals(ElevenLabsEvents.audioChunk(b64(pcm(shortArrayOf(1, 2))), commit = false), control.texts.single())
    }

    @Test fun three_appends_then_commit_send_two_false_chunks_then_one_true_and_bind_synthetically() {
        val p = protocol()
        val a = pcm(shortArrayOf(1, 2)); val b = pcm(shortArrayOf(3, 4)); val c = pcm(shortArrayOf(5, 6))
        // Fold-by-one: append a (held), append b (flush a false), append c (flush b false).
        p.onAppend(a); p.onAppend(b); p.onAppend(c)
        assertEquals(
            listOf(
                ElevenLabsEvents.audioChunk(b64(a), commit = false),
                ElevenLabsEvents.audioChunk(b64(b), commit = false),
            ),
            control.texts,
        )
        // No commit yet -> no synthetic bind fired.
        assertTrue(sink.committed.isEmpty())

        // onCommit flushes the held last chunk commit:true AND fires the synthetic onCommitted NOW,
        // before any committed_transcript — that is the deterministic FIFO bind the engine expects.
        assertTrue(p.onCommit())
        assertEquals(3, control.texts.size)
        assertEquals(ElevenLabsEvents.audioChunk(b64(c), commit = true), control.texts.last())
        assertEquals("exactly one synthetic bind, at commit time", 1, sink.committed.size)
        assertTrue("the bind precedes any transcript", sink.completed.isEmpty())
        assertEquals("a commit arms one timeout", listOf(8_000L), scheduler.delays)
    }

    @Test fun commit_with_no_held_audio_flushes_an_empty_commit_true_chunk() {
        val p = protocol()
        assertTrue(p.onCommit())
        assertEquals(ElevenLabsEvents.audioChunk("", commit = true), control.texts.single())
        assertEquals(1, sink.committed.size)
    }

    // ---- committed_transcript resolves the bound turn exactly once ------------------------------

    @Test fun committed_transcript_completes_the_synthetic_id_exactly_once() {
        val p = protocol()
        p.onAppend(pcm(shortArrayOf(1, 2)))
        p.onCommit()
        val id = sink.committed.single()

        p.onText("""{"message_type":"committed_transcript","text":"hello world"}""")
        assertEquals(listOf(id to "hello world"), sink.completed)

        // A second committed_transcript with no turn in flight resolves nothing (no double-fire).
        p.onText("""{"message_type":"committed_transcript","text":"stray"}""")
        assertEquals(1, sink.completed.size)
    }

    // ---- single-in-flight serialization ---------------------------------------------------------

    @Test fun a_commit_while_one_is_in_flight_is_deferred_until_the_prior_transcript_lands() {
        val p = protocol()
        // Turn 1: append + commit -> one commit:true chunk sent, bound as id1.
        p.onAppend(pcm(shortArrayOf(1, 2)))
        assertTrue(p.onCommit())
        val framesAfterFirstCommit = control.frames.size
        val id1 = sink.committed.single()

        // Turn 2's commit arrives while turn 1 is still in flight: it must send NOTHING yet and it
        // must NOT bind a second synthetic id — the serialization holds the commit back.
        assertTrue("a deferred commit still succeeds (never blocks the sender)", p.onCommit())
        assertEquals("no frame sent while a commit is in flight", framesAfterFirstCommit, control.frames.size)
        assertEquals("no second bind while a commit is in flight", 1, sink.committed.size)

        // Turn 1 resolves -> the deferred commit flushes now (empty commit:true) and binds id2.
        p.onText("""{"message_type":"committed_transcript","text":"one"}""")
        assertEquals(listOf(id1 to "one"), sink.completed)
        assertEquals("the deferred commit now flushes", framesAfterFirstCommit + 1, control.frames.size)
        assertEquals(ElevenLabsEvents.audioChunk("", commit = true), control.texts.last())
        assertEquals("the deferred commit binds a fresh id", 2, sink.committed.size)
        val id2 = sink.committed.last()
        assertTrue("each turn gets a distinct synthetic id", id1 != id2)

        // Turn 2's transcript then resolves id2.
        p.onText("""{"message_type":"committed_transcript","text":"two"}""")
        assertEquals(listOf(id1 to "one", id2 to "two"), sink.completed)
    }

    // ---- timeout -> Lost, and the slot frees for the next commit --------------------------------

    @Test fun a_missing_committed_transcript_times_out_to_Lost_and_frees_the_slot() {
        val p = protocol()
        p.onAppend(pcm(shortArrayOf(1, 2)))
        p.onCommit()
        val id1 = sink.committed.single()
        assertEquals(listOf(8_000L), scheduler.delays)

        // No committed_transcript ever arrives; the armed timeout fires.
        scheduler.fireAll()
        assertEquals("the held turn resolves via the engine's Lost path", listOf(id1), sink.failed)
        assertTrue("timeout does not fabricate a transcript", sink.completed.isEmpty())

        // The in-flight slot is free again: the next commit sends immediately (not deferred).
        p.onAppend(pcm(shortArrayOf(9, 9)))
        val framesBefore = control.frames.size
        assertTrue(p.onCommit())
        assertEquals("the next commit is sent, not held", framesBefore + 1, control.frames.size)
        assertEquals(2, sink.committed.size)
        assertTrue("the fresh turn's id differs from the lost one", sink.committed.last() != id1)
    }

    @Test fun a_committed_transcript_after_a_timeout_resolves_nothing() {
        val p = protocol()
        p.onAppend(pcm(shortArrayOf(1, 2)))
        p.onCommit()
        val id1 = sink.committed.single()

        scheduler.fireAll() // the turn is already Lost via timeout
        assertEquals(listOf(id1), sink.failed)

        // A very-late transcript for the timed-out turn must not complete anything (slot is empty).
        p.onText("""{"message_type":"committed_transcript","text":"late"}""")
        assertTrue(sink.completed.isEmpty())
    }

    // ---- per-open reset on reconnect (bootstrap clears held-commit state) ------------------------

    @Test fun bootstrap_on_reconnect_clears_held_commit_state_so_the_next_commit_is_not_deferred() {
        val p = protocol()
        // Turn A committed and in flight; turn B deferred behind it (single-in-flight serialization).
        p.onAppend(pcm(shortArrayOf(1, 2)))
        p.onCommit()
        val idA = sink.committed.single()
        p.onCommit() // B deferred while A is in flight
        assertEquals("B is deferred, not bound, while A is in flight", 1, sink.committed.size)

        // A transient WS drop then reopen: the transport reconnects WITHOUT calling reset() (that fires
        // only on a deliberate close), so bootstrap must itself clear the stale held-commit state.
        p.bootstrap("el-secret", null)

        // A fresh commit after the reopen must send IMMEDIATELY, not sit deferred behind stale A.
        val framesBefore = control.frames.size
        p.onAppend(pcm(shortArrayOf(3, 4)))
        assertTrue(p.onCommit())
        assertEquals("post-reconnect commit flushes at once", framesBefore + 1, control.frames.size)
        assertEquals("the fresh commit binds a new id (not deferred)", 2, sink.committed.size)
        val idFresh = sink.committed.last()
        assertTrue("the fresh turn's id differs from the pre-drop one", idFresh != idA)

        // The fresh turn resolves normally.
        p.onText("""{"message_type":"committed_transcript","text":"fresh"}""")
        assertEquals(listOf(idFresh to "fresh"), sink.completed)

        // The still-armed pre-drop timeout (for A) and the fresh turn's timeout both fire now: neither
        // resolves anything — bootstrap neutralized the stale slot and the fresh turn is already done.
        scheduler.fireAll()
        assertTrue("the stale pre-drop timeout binds/resolves nothing", sink.failed.isEmpty())
        assertEquals("no extra completion from a stale timer", 1, sink.completed.size)
    }

    // ---- errors carry length only ---------------------------------------------------------------

    @Test fun input_error_dispatches_code_and_length_only_never_content() {
        val p = protocol()
        val secret = "your key lacks the scope for realtime speech-to-text"
        p.onText("""{"message_type":"input_error","message":"$secret"}""")
        assertEquals(listOf<Pair<String?, Int>>("input_error" to secret.length), sink.errors)
        // The message content never crosses the seam — only its length.
        assertTrue(sink.errors.none { (_, len) -> len != secret.length })
        assertTrue("no delta/complete/bind from an error", sink.deltas.isEmpty() && sink.completed.isEmpty())
    }

    @Test fun unknown_and_malformed_frames_dispatch_nothing() {
        val p = protocol()
        p.onText("""{"message_type":"session_started"}""") // forward-compat unknown
        p.onText("not json at all")                         // malformed
        assertTrue(
            sink.deltas.isEmpty() && sink.completed.isEmpty() && sink.committed.isEmpty() &&
                sink.failed.isEmpty() && sink.errors.isEmpty(),
        )
    }

    // ---- handshake status -> fatal --------------------------------------------------------------

    @Test fun classifyFatal_maps_handshake_status_to_kind_or_transient() {
        val p = protocol()
        assertEquals(FatalKind.INVALID_KEY, p.classifyFatal(401))
        assertEquals(FatalKind.INVALID_KEY, p.classifyFatal(403))
        assertEquals(FatalKind.OUT_OF_CREDIT, p.classifyFatal(429))
        assertNull("5xx / network is transient -> reconnect, not fatal", p.classifyFatal(500))
    }

    // ---- backpressure -----------------------------------------------------------------------------

    @Test fun a_flush_that_control_refuses_sheds_the_turn() {
        val refusing = RecordingControl(accept = false)
        val p = protocol(refusing)
        p.onAppend(pcm(shortArrayOf(1, 2))) // held, not sent
        // The second append flushes the first, but control refuses (backpressure) -> false to shed.
        assertFalse(p.onAppend(pcm(shortArrayOf(3, 4))))
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private fun pcm(samples: ShortArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val v = samples[i].toInt()
            out[i * 2] = (v and 0xFF).toByte()
            out[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
}
