package com.whispereverywhere.transcription.live

import com.whispereverywhere.transcription.cloud.FatalKind
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Soniox stt-rt-v5 behind the [RealtimeProtocol] seam, driven through a fake [SessionControl] + a
 * recording [RealtimeTransport.Listener] + a manual [ReconnectScheduler], with VERBATIM doc JSON fed
 * to the real [SonioxEvents] parser. The shared bootstrap/dispatch/fatal contract lives in
 * [RealtimeProtocolContractTest]; this suite pins what is Soniox-specific: the key-bearing config
 * shape, raw-binary audio, client-assembled turns with a grace window (late finals captured,
 * zero-final turns resolved empty-not-lost, `finished` early flush), the numeric error map, and the
 * finalize/rotate session cycling. The no-log discipline is pinned separately in
 * [SonioxNoLogDisciplineTest].
 */
class SonioxRealtimeProtocolTest {

    /** Records every frame; [accept] models backpressure/socket-down (false). */
    private class RecordingControl(private val accept: Boolean = true) : SessionControl {
        val frames = mutableListOf<Frame>()
        var rotates = 0
        override fun send(frame: Frame): Boolean { frames += frame; return accept }
        override fun rotate() { rotates++ }
        val texts get() = frames.filterIsInstance<Frame.Text>().map { it.json }
        val binaries get() = frames.filterIsInstance<Frame.Binary>().map { it.bytes.toByteArray() }
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

    /** Captures scheduled tasks (grace timers); [fireAll] runs the ones armed so far, in order. */
    private class ManualScheduler : ReconnectScheduler {
        val delays = mutableListOf<Long>()
        private val tasks = mutableListOf<() -> Unit>()
        override fun schedule(delayMs: Long, task: () -> Unit) { delays += delayMs; tasks += task }
        fun fireAll() { val snapshot = tasks.toList(); tasks.clear(); snapshot.forEach { it() } }
    }

    private val control = RecordingControl()
    private val sink = RecordingListener()
    private val scheduler = ManualScheduler()

    private fun protocol(ctrl: SessionControl = control): SonioxRealtimeProtocol =
        SonioxRealtimeProtocol(scheduler).apply { bind(ctrl, sink) }

    // ---- wire facts -----------------------------------------------------------------------------

    @Test fun endpoint_is_the_pinned_stt_rt_v5_url() {
        assertEquals("wss://stt-rt.soniox.com/transcribe-websocket", protocol().endpoint)
    }

    @Test fun the_key_rides_config_so_there_is_no_upgrade_header() {
        assertTrue(protocol().upgradeHeaders("sx-secret").isEmpty())
    }

    @Test fun soniox_does_not_use_the_tolerant_beta_retry() {
        assertFalse(protocol().tolerant4xxRetry)
    }

    // ---- config bootstrap shape (verbatim wire; language_hints present only when given) ----------

    @Test fun bootstrap_config_shape_is_exact_without_language() {
        val frame = protocol().bootstrap("sx-secret", null).single() as Frame.Text
        assertEquals(
            """{"api_key":"sx-secret","model":"stt-rt-v5","audio_format":"s16le","sample_rate":16000,"num_channels":1}""",
            frame.json,
        )
    }

    @Test fun bootstrap_config_shape_adds_language_hints_only_when_a_language_is_given() {
        val frame = protocol().bootstrap("sx-secret", "en").single() as Frame.Text
        assertEquals(
            """{"api_key":"sx-secret","model":"stt-rt-v5","audio_format":"s16le","sample_rate":16000,"num_channels":1,"language_hints":["en"]}""",
            frame.json,
        )
    }

    // ---- audio is RAW s16le BINARY (no base64, no resample) --------------------------------------

    @Test fun onAppend_sends_a_binary_frame_whose_bytes_equal_the_input_pcm() {
        val p = protocol()
        val pcm = pcm(shortArrayOf(0, 100, -200, 32000))
        assertTrue(p.onAppend(pcm))
        assertTrue("no text frame for audio — Soniox streams binary", control.texts.isEmpty())
        assertArrayEquals("bytes on the wire are the raw PCM, unchanged", pcm, control.binaries.single())
    }

    @Test fun onAppend_propagates_backpressure_from_control() {
        val p = protocol(RecordingControl(accept = false))
        assertFalse(p.onAppend(pcm(shortArrayOf(1, 2))))
    }

    // ---- preview: accumulated finals + current non-finals; finals persist, non-finals do not -----

    @Test fun mixed_tokens_preview_is_accumulated_finals_plus_current_non_finals() {
        val p = protocol()
        p.onText("""{"tokens":[{"text":"hello ","is_final":true},{"text":"wor","is_final":false}]}""")
        assertEquals(listOf("" to "hello wor"), sink.deltas)

        // A later message's non-finals REPLACE the previous ones (not accumulate); finals persist.
        p.onText("""{"tokens":[{"text":"world","is_final":false}]}""")
        assertEquals("" to "hello world", sink.deltas.last())

        // Finalizing "world" keeps it; the preview is now all-final.
        p.onText("""{"tokens":[{"text":"world","is_final":true}]}""")
        assertEquals("" to "hello world", sink.deltas.last())
        assertTrue("preview never binds or completes a turn", sink.completed.isEmpty() && sink.committed.isEmpty())
    }

    // ---- client-assembled turn at VAD close, with the grace window ------------------------------

    @Test fun commit_then_grace_assembles_the_accumulated_finals_and_clears_for_the_next_turn() {
        val p = protocol()
        p.onText("""{"tokens":[{"text":"first turn","is_final":true}]}""")

        // VAD close arms the grace window (one timer); nothing resolves yet.
        assertTrue(p.onCommit())
        assertEquals("a commit arms exactly one grace timer", listOf(SonioxRealtimeProtocol.DEFAULT_GRACE_MS), scheduler.delays)
        assertTrue("the turn does not resolve at commit time", sink.completed.isEmpty())

        scheduler.fireAll()
        assertEquals(listOf("1" to "first turn"), sink.completed)
        assertTrue("assembling a turn is never a failure", sink.failed.isEmpty())

        // The accumulator is clear: a second turn starts empty and gets a fresh id.
        p.onText("""{"tokens":[{"text":"second turn","is_final":true}]}""")
        p.onCommit()
        scheduler.fireAll()
        assertEquals(listOf("1" to "first turn", "2" to "second turn"), sink.completed)
    }

    @Test fun the_grace_window_captures_finals_that_arrive_after_the_vad_close() {
        val p = protocol()
        // At VAD close the tail is still non-final: only "hello " has finalized so far.
        p.onText("""{"tokens":[{"text":"hello ","is_final":true},{"text":"there","is_final":false}]}""")
        p.onCommit()

        // "there" finalizes DURING the grace window — it belongs to the just-cut turn, not the next.
        p.onText("""{"tokens":[{"text":"there","is_final":true}]}""")
        scheduler.fireAll()
        assertEquals("the late final is captured", listOf("1" to "hello there"), sink.completed)
    }

    @Test fun a_turn_with_zero_finals_resolves_empty_not_lost() {
        val p = protocol()
        // VAD cut a turn but Soniox produced no final tokens before the grace window closed.
        p.onCommit()
        scheduler.fireAll()
        // Resolved as an empty completion (the engine's empty-outcome path), NOT onTranscriptionFailed.
        assertEquals(listOf("1" to ""), sink.completed)
        assertTrue("zero finals is not a hard transcription failure", sink.failed.isEmpty())
    }

    @Test fun finished_true_flushes_the_in_grace_turn_before_the_timer_fires() {
        val p = protocol()
        p.onText("""{"tokens":[{"text":"done","is_final":true}]}""")
        p.onCommit()

        // End-of-stream flush resolves the turn immediately; the later grace timer is then a no-op.
        p.onText("""{"finished":true}""")
        assertEquals(listOf("1" to "done"), sink.completed)
        scheduler.fireAll()
        assertEquals("the superseded grace timer resolves nothing", 1, sink.completed.size)
    }

    @Test fun a_second_commit_inside_the_grace_window_finalizes_the_prior_turn_first_in_order() {
        val p = protocol()
        p.onText("""{"tokens":[{"text":"one","is_final":true}]}""")
        p.onCommit() // turn 1, grace armed

        // A second VAD close lands before turn 1's grace fires. Turn 1 finalizes with what it has.
        p.onText("""{"tokens":[{"text":"two","is_final":true}]}""")
        p.onCommit() // turn 2 opened; turn 1's finalize was scheduled off-thread (delay 0)

        scheduler.fireAll()
        // Both seqs resolve, in commit order, each exactly once (turn 1 = "onetwo" it had accumulated,
        // turn 2 = empty — its words, if any, would arrive after and be rescued locally if empty).
        assertEquals(2, sink.completed.size)
        assertEquals("1", sink.completed[0].first)
        assertEquals("2", sink.completed[1].first)
        assertTrue(sink.failed.isEmpty())
    }

    // ---- numeric error map (code + length only; session cycling) --------------------------------

    @Test fun error_401_is_invalid_key_fatal() {
        protocol().onText("""{"error_code":401,"error_message":"bad key"}""")
        assertEquals(listOf<Pair<String?, Int>>("401" to "bad key".length), sink.errors)
        assertEquals(listOf(FatalKind.INVALID_KEY to 401), sink.fatals)
    }

    @Test fun error_402_is_out_of_credit_fatal() {
        protocol().onText("""{"error_code":402,"error_message":"no funds"}""")
        assertEquals(listOf(FatalKind.OUT_OF_CREDIT to 402), sink.fatals)
    }

    @Test fun error_403_rotates_once_then_latches_forbidden() {
        val p = protocol()
        p.onText("""{"error_code":403,"error_message":"session expired"}""")
        assertEquals("first 403 rotates the session", 1, control.rotates)
        assertTrue("first 403 does not latch", sink.fatals.isEmpty())

        p.onText("""{"error_code":403,"error_message":"session expired"}""")
        assertEquals("second 403 does not rotate again", 1, control.rotates)
        assertEquals("second 403 latches forbidden", listOf(FatalKind.FORBIDDEN to 403), sink.fatals)
    }

    @Test fun bootstrap_does_not_reset_the_403_counter_so_rotation_stays_bounded() {
        val p = protocol()
        p.onText("""{"error_code":403,"error_message":"x"}""")
        assertEquals(1, control.rotates)
        // A reopen (bootstrap) clears TURN state but NOT the 403 counter — otherwise inband 403s would
        // rotate forever (each successful reopen resets the transport's own reconnect ceiling).
        p.bootstrap("sx-secret", null)
        p.onText("""{"error_code":403,"error_message":"x"}""")
        assertEquals("no second rotation after a reopen", 1, control.rotates)
        assertEquals(listOf(FatalKind.FORBIDDEN to 403), sink.fatals)
    }

    @Test fun reset_clears_the_403_counter_for_the_next_session() {
        val p = protocol()
        p.onText("""{"error_code":403,"error_message":"x"}""")
        p.onText("""{"error_code":403,"error_message":"x"}""") // latched forbidden this session
        p.reset()
        p.onText("""{"error_code":403,"error_message":"x"}""")
        assertEquals("a fresh session gets its own single rotation", 2, control.rotates)
    }

    @Test fun error_413_sends_the_empty_frame_finalize_then_rotates() {
        val p = protocol()
        p.onText("""{"error_code":413,"error_message":"max duration"}""")
        assertEquals("the finalize frame is an empty binary frame", 0, control.binaries.single().size)
        assertEquals(1, control.rotates)
        assertTrue("413 is a session cycle, not a fatal", sink.fatals.isEmpty())
    }

    @Test fun transient_429_and_5xx_neither_latch_nor_rotate() {
        val p = protocol()
        p.onText("""{"error_code":429,"error_message":"slow down"}""")
        p.onText("""{"error_code":503,"error_message":"unavailable"}""")
        assertTrue(sink.fatals.isEmpty())
        assertEquals(0, control.rotates)
        assertEquals("errors still cross as code + length only", listOf("429", "503"), sink.errors.map { it.first })
    }

    @Test fun error_message_content_never_crosses_only_its_length() {
        val secret = "your account key sk-live-DEADBEEF lacks realtime scope"
        protocol().onText("""{"error_code":401,"error_message":"$secret"}""")
        assertEquals(listOf<Pair<String?, Int>>("401" to secret.length), sink.errors)
        assertTrue("no error path emits a delta/completion", sink.deltas.isEmpty() && sink.completed.isEmpty())
    }

    // ---- handshake status -> fatal (Soniox authenticates inband, so this is the rare path) -------

    @Test fun classifyFatal_maps_handshake_status_to_kind_or_transient() {
        val p = protocol()
        assertEquals(FatalKind.INVALID_KEY, p.classifyFatal(401))
        assertEquals(FatalKind.FORBIDDEN, p.classifyFatal(403))
        assertEquals(FatalKind.OUT_OF_CREDIT, p.classifyFatal(429))
        assertNull("5xx / network is transient -> reconnect, not fatal", p.classifyFatal(500))
    }

    // ---- forward-compatibility ------------------------------------------------------------------

    @Test fun unknown_and_malformed_frames_dispatch_nothing() {
        val p = protocol()
        p.onText("""{"keepalive":true}""") // no tokens / finished / error -> ignored
        p.onText("not json at all")
        assertTrue(
            sink.deltas.isEmpty() && sink.completed.isEmpty() && sink.committed.isEmpty() &&
                sink.failed.isEmpty() && sink.errors.isEmpty() && sink.fatals.isEmpty(),
        )
    }

    // ---- helpers --------------------------------------------------------------------------------

    private fun pcm(samples: ShortArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val v = samples[i].toInt()
            out[i * 2] = (v and 0xFF).toByte()
            out[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }
}
