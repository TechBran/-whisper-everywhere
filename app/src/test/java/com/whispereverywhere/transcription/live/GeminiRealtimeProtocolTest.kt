package com.whispereverywhere.transcription.live

import com.whispereverywhere.transcription.cloud.FatalKind
import okio.ByteString.Companion.encodeUtf8
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Gemini Live (`gemini-3.5-transcribe-live`) behind the [RealtimeProtocol] seam, driven through a
 * fake [SessionControl] + a recording [RealtimeTransport.Listener] + an injected clock, with the
 * frames RECORDED by the T0 probes (docs/measurements/2026-09-10-gemini-live-probes-t0.md §4) fed
 * to the real [GeminiLiveEvents] codec. Client-VAD: the app's endpointer cuts turns and this
 * adapter maps each to a manual-VAD activity. This suite pins the byte-exact setup and activity
 * frames, the pre-setup ring, the lazy `activityStart`, the ack-gated next start, the never-empty
 * activity, the one-final-per-activity → `onCommitted`/`onCompleted` shape, the speech-less
 * activity → EMPTY, the discard of a locally-rescued turn, GoAway / age / watchdog rotation, the
 * close-reason map, and the no-log discipline.
 */
class GeminiRealtimeProtocolTest {

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
        val order = mutableListOf<String>()
        override fun onConnected() {}
        override fun onDelta(itemId: String, text: String) { deltas += itemId to text; order += "delta:$text" }
        override fun onCompleted(itemId: String, transcript: String) { completed += itemId to transcript; order += "completed:$itemId" }
        override fun onCommitted(itemId: String) { committed += itemId; order += "committed:$itemId" }
        override fun onTranscriptionFailed(itemId: String) { failed += itemId }
        override fun onErrorEvent(code: String?, messageLength: Int) { errors += code to messageLength }
        override fun onDisconnected() {}
        override fun onFatal(kind: FatalKind, code: Int) { fatals += kind to code }
        val dispatchCount get() = deltas.size + completed.size + committed.size + failed.size + errors.size + fatals.size
    }

    /** A fake monotonic clock, in ms, so the timers are driven without waiting. */
    private class Clock { var ms = 1_000L; fun nanos(): Long = ms * 1_000_000L; fun advance(dMs: Long) { ms += dMs } }

    private val control = RecordingControl()
    private val sink = RecordingListener()
    private val clock = Clock()

    private fun protocol(ctrl: SessionControl = control): GeminiRealtimeProtocol =
        GeminiRealtimeProtocol(nowNanos = clock::nanos).apply { bind(ctrl, sink) }

    /** bootstrap + setupComplete: the state a live session is in when the first audio frame arrives. */
    private fun ready(p: GeminiRealtimeProtocol, language: String? = null): GeminiRealtimeProtocol {
        p.bootstrap("AIza-test-key", language)
        p.onText(SETUP_COMPLETE)
        return p
    }

    // ---- recorded frames (T0 §4, redacted; they contain no key) ---------------------------------

    private companion object {
        const val SETUP_AUTO =
            """{"setup":{"model":"models/gemini-3.5-transcribe-live","generationConfig":{"responseModalities":["TEXT"]},"inputAudioTranscription":{"mode":"VERBATIM"},"realtimeInputConfig":{"automaticActivityDetection":{"disabled":true}}}}"""
        const val SETUP_DE =
            """{"setup":{"model":"models/gemini-3.5-transcribe-live","generationConfig":{"responseModalities":["TEXT"]},"inputAudioTranscription":{"languageCodes":["de"],"mode":"VERBATIM"},"realtimeInputConfig":{"automaticActivityDetection":{"disabled":true}}}}"""
        const val ACTIVITY_START = """{"realtimeInput":{"activityStart":{}}}"""
        const val ACTIVITY_END = """{"realtimeInput":{"activityEnd":{}}}"""

        const val SETUP_COMPLETE = """{"setupComplete":{}}"""
        const val VA_START = """{"serverContent":{},"voiceActivity":{"type":"ACTIVITY_START","audioOffset":"0.440s"}}"""
        const val INTERIM_1 = """{"serverContent":{"interimInputTranscription":{"text":"And so"}}}"""
        const val INTERIM_2 = """{"serverContent":{"interimInputTranscription":{"text":"And so, my fellow Americans, ask not what your"}}}"""
        const val FINAL_1 = """{"serverContent":{"inputTranscription":{"text":"And so, my fellow Americans, ask not what your country"}}}"""
        const val GENERATION_COMPLETE = """{"serverContent":{"generationComplete":true}}"""
        const val VA_END = """{"serverContent":{},"voiceActivity":{"type":"ACTIVITY_END","audioOffset":"6.680s"}}"""
        const val GO_AWAY = """{"goAway":{"timeLeft":"50s"}}"""
        const val EMPTY_ACTIVITY_EDGE = """{"serverContent":{"turnComplete":true},"usageMetadata":{}}"""

        const val FINAL_TEXT = "And so, my fellow Americans, ask not what your country"
    }

    private fun audioFrame(pcm: ByteArray): String =
        """{"realtimeInput":{"audio":{"data":"${Base64.getEncoder().encodeToString(pcm)}","mimeType":"audio/pcm;rate=16000"}}}"""

    // ---- wire facts (T0 P1) ---------------------------------------------------------------------

    @Test fun endpoint_is_the_key_less_bidi_generate_content_url() {
        assertEquals(
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent",
            protocol().endpoint,
        )
        assertFalse("the key never rides the URL", protocol().endpoint.contains("key="))
    }

    @Test fun the_key_rides_the_x_goog_api_key_upgrade_header_bare() {
        // What Google's own SDK sends (T0 P1: 101, setupComplete in 165 ms). Not Bearer.
        assertEquals(listOf("x-goog-api-key" to "AIza-secret"), protocol().upgradeHeaders("AIza-secret"))
    }

    @Test fun gemini_does_not_use_the_tolerant_beta_retry() {
        assertFalse(protocol().tolerant4xxRetry)
    }

    // ---- outbound shapes, byte-exact (T0 §4.1) --------------------------------------------------

    @Test fun setup_shape_is_exact_manual_vad_no_language_field_for_auto() {
        val frame = protocol().bootstrap("AIza-secret", null).single() as Frame.Text
        assertEquals(SETUP_AUTO, frame.json)
        assertFalse("the key never enters the setup frame", frame.json.contains("AIza-secret"))
    }

    @Test fun setup_shape_carries_the_bare_language_code_when_selected() {
        // T0 P7: bare codes are accepted; no region table.
        val frame = protocol().bootstrap("AIza-secret", "de").single() as Frame.Text
        assertEquals(SETUP_DE, frame.json)
    }

    @Test fun activity_and_audio_frame_shapes_are_exact() {
        assertEquals(ACTIVITY_START, GeminiLiveEvents.activityStart())
        assertEquals(ACTIVITY_END, GeminiLiveEvents.activityEnd())
        assertEquals(
            """{"realtimeInput":{"audio":{"data":"YWJj","mimeType":"audio/pcm;rate=16000"}}}""",
            GeminiLiveEvents.audio("YWJj"),
        )
    }

    // ---- inbound codec (T0 §4.2) ----------------------------------------------------------------

    @Test fun inbound_parse_covers_every_recorded_shape_and_ignores_the_rest() {
        assertEquals(listOf(GeminiLiveEvents.In.SetupComplete), GeminiLiveEvents.parse(SETUP_COMPLETE))
        assertEquals(listOf(GeminiLiveEvents.In.ActivityStart), GeminiLiveEvents.parse(VA_START))
        assertEquals(listOf(GeminiLiveEvents.In.Interim("And so")), GeminiLiveEvents.parse(INTERIM_1))
        assertEquals(listOf(GeminiLiveEvents.In.Final(FINAL_TEXT)), GeminiLiveEvents.parse(FINAL_1))
        assertEquals(listOf(GeminiLiveEvents.In.ActivityEnd), GeminiLiveEvents.parse(VA_END))
        assertEquals(listOf(GeminiLiveEvents.In.GoAway(50_000L)), GeminiLiveEvents.parse(GO_AWAY))
        assertTrue(GeminiLiveEvents.parse(GENERATION_COMPLETE).isEmpty())
        assertTrue("usageMetadata + turnComplete carry nothing actionable", GeminiLiveEvents.parse(EMPTY_ACTIVITY_EDGE).isEmpty())
        assertTrue(GeminiLiveEvents.parse("""{"serverContent":{"modelTurn":{"parts":[{"text":"Hello! How can I help?"}]}}}""").isEmpty())
        assertTrue(GeminiLiveEvents.parse("""{"sessionResumptionUpdate":{"newHandle":"h","resumable":true}}""").isEmpty())
        assertTrue(GeminiLiveEvents.parse("not json at all").isEmpty())
        assertTrue(GeminiLiveEvents.parse("[1,2,3]").isEmpty())
    }

    @Test fun a_frame_carrying_several_members_yields_every_event_in_order() {
        // usageMetadata beside a final must never short-circuit the transcription.
        val events = GeminiLiveEvents.parse(
            """{"serverContent":{"inputTranscription":{"text":"one two"}},"voiceActivity":{"type":"ACTIVITY_END","audioOffset":"5s"},"usageMetadata":{}}""",
        )
        assertEquals(listOf(GeminiLiveEvents.In.Final("one two"), GeminiLiveEvents.In.ActivityEnd), events)
    }

    @Test fun duration_strings_parse_leniently() {
        assertEquals(50_000L, GeminiLiveEvents.durationMs("50s"))
        assertEquals(6_680L, GeminiLiveEvents.durationMs("6.680s"))
        assertEquals(0L, GeminiLiveEvents.durationMs("0s"))
        assertNull(GeminiLiveEvents.durationMs(null))
        assertNull(GeminiLiveEvents.durationMs("soon"))
        assertNull(GeminiLiveEvents.durationMs("-5s"))
    }

    @Test fun binary_frames_decode_to_the_same_dispatch_as_text() {
        // T0 finding 1: every inbound frame is BINARY carrying UTF-8 JSON.
        val p = ready(protocol())
        p.onAppend(ByteArray(64))
        p.onCommit()
        p.onBinary(FINAL_1.encodeUtf8())
        assertEquals(listOf("1" to FINAL_TEXT), sink.completed)
    }

    // ---- the ready gate: nothing but setup before setupComplete, then the ring flushes ----------

    @Test fun audio_before_setup_complete_is_held_and_flushed_inside_an_activity_on_the_next_append() {
        val p = protocol()
        p.bootstrap("k", null)
        val a = ByteArray(64) { 1 }; val b = ByteArray(64) { 2 }; val c = ByteArray(64) { 3 }
        assertTrue(p.onAppend(a))
        assertTrue(p.onAppend(b))
        assertTrue("nothing goes to the wire before setupComplete", control.frames.isEmpty())

        p.onText(SETUP_COMPLETE)
        assertTrue("setupComplete itself sends nothing — the sender thread flushes", control.frames.isEmpty())

        assertTrue(p.onAppend(c))
        assertEquals(
            "activityStart, then the ring in order, then the new frame",
            listOf(ACTIVITY_START, audioFrame(a), audioFrame(b), audioFrame(c)),
            control.texts,
        )
    }

    @Test fun the_pre_setup_ring_overflows_to_a_shed_after_two_seconds_of_audio() {
        val p = protocol()
        p.bootstrap("k", null)
        val frame = ByteArray(3_200) // 100 ms
        repeat(20) { assertTrue(p.onAppend(frame)) } // exactly 2 s fits
        assertFalse("the 21st frame is shed — the mirror rescues the turn", p.onAppend(frame))
    }

    @Test fun a_commit_before_setup_complete_closes_the_flushed_activity_and_gates_the_next_turn() {
        // Reconnect-gap shape: the first turn's audio and its commit arrive inside the 130 ms setup
        // round trip. The flush must send start + audio + END for that turn, then hold the next
        // turn's frame behind the ack gate — never fold the two into one activity.
        val p = protocol()
        p.bootstrap("k", null)
        val a = ByteArray(64) { 1 }; val b = ByteArray(64) { 2 }
        p.onAppend(a)
        assertTrue(p.onCommit())
        p.onText(SETUP_COMPLETE)
        assertTrue(p.onAppend(b))
        assertEquals(listOf(ACTIVITY_START, audioFrame(a), ACTIVITY_END), control.texts)

        // The first turn's final binds the first seq; the held frame goes out after the ack.
        p.onText(FINAL_1)
        p.onText(VA_END)
        assertEquals(listOf("1" to FINAL_TEXT), sink.completed)
        p.onAppend(ByteArray(64) { 3 })
        assertEquals(ACTIVITY_START, control.texts[3])
        assertEquals(audioFrame(b), control.texts[4])
    }

    // ---- manual VAD: lazy start, audio inside, end on commit --------------------------------------

    @Test fun the_first_frame_of_a_turn_opens_the_activity_and_later_frames_ride_inside_it() {
        val p = ready(protocol())
        val a = ByteArray(64) { 1 }; val b = ByteArray(64) { 2 }
        assertTrue(p.onAppend(a))
        assertTrue(p.onAppend(b))
        assertEquals(listOf(ACTIVITY_START, audioFrame(a), audioFrame(b)), control.texts)
    }

    @Test fun commit_sends_activity_end_and_the_final_binds_then_completes_a_fresh_id() {
        val p = ready(protocol())
        p.onAppend(ByteArray(64))
        assertTrue(p.onCommit())
        assertEquals(ACTIVITY_END, control.texts.last())

        p.onText(INTERIM_1)
        p.onText(INTERIM_2)
        p.onText(FINAL_1)
        p.onText(GENERATION_COMPLETE)
        p.onText(VA_END)

        val id = sink.committed.single()
        assertEquals(
            "cumulative previews, then bind, then resolve — in that order",
            listOf("delta:And so", "delta:And so, my fellow Americans, ask not what your", "committed:$id", "completed:$id"),
            sink.order,
        )
        assertEquals(listOf(id to FINAL_TEXT), sink.completed)
        assertTrue("assembling a turn is never a failure", sink.failed.isEmpty())
    }

    @Test fun exact_duplicate_interims_are_deduped_and_previews_never_bind() {
        val p = ready(protocol())
        p.onAppend(ByteArray(64))
        p.onText(INTERIM_1)
        p.onText(INTERIM_1) // T0 P3: 6 of 12 interims were exact duplicates of the previous one
        p.onText(INTERIM_2)
        assertEquals(listOf("" to "And so", "" to "And so, my fellow Americans, ask not what your"), sink.deltas)
        assertTrue(sink.committed.isEmpty() && sink.completed.isEmpty())
    }

    @Test fun commit_with_no_audio_since_the_last_end_sends_nothing_so_an_activity_is_never_empty() {
        // P3d: start-then-end with no audio closes the socket 1007. An activity opens only on a
        // frame, so a stray commit can never produce one.
        val p = ready(protocol())
        assertTrue(p.onCommit())
        assertTrue(control.frames.isEmpty())
        p.onAppend(ByteArray(64)); p.onCommit()
        val n = control.frames.size
        assertTrue(p.onCommit()) // a second commit for the same turn
        assertEquals("no second activityEnd", n, control.frames.size)
    }

    @Test fun each_final_gets_a_fresh_distinct_id_per_activity() {
        val p = ready(protocol())
        p.onAppend(ByteArray(64)); p.onCommit()
        p.onText("""{"serverContent":{"inputTranscription":{"text":"one"}}}"""); p.onText(VA_END)
        p.onAppend(ByteArray(64)); p.onCommit()
        p.onText("""{"serverContent":{"inputTranscription":{"text":"two"}}}"""); p.onText(VA_END)
        val (id1, id2) = sink.committed[0] to sink.committed[1]
        assertTrue(id1 != id2)
        assertEquals(listOf(id1 to "one", id2 to "two"), sink.completed)
    }

    // ---- the speech-less activity: no final, only the ack -> EMPTY (T0 P3f) ---------------------

    @Test fun an_activity_end_ack_with_no_final_resolves_the_turn_empty_not_lost() {
        val p = ready(protocol())
        p.onAppend(ByteArray(64)); p.onCommit()
        p.onText(VA_END) // the server heard no speech: ack only
        val id = sink.committed.single()
        assertEquals(listOf("committed:$id", "completed:$id"), sink.order)
        assertEquals(listOf(id to ""), sink.completed)
        assertTrue(sink.failed.isEmpty())
    }

    @Test fun an_activity_end_ack_after_a_final_resolves_nothing_more() {
        val p = ready(protocol())
        p.onAppend(ByteArray(64)); p.onCommit()
        p.onText(FINAL_1); p.onText(GENERATION_COMPLETE); p.onText(VA_END)
        assertEquals("one turn, one resolution", 1, sink.completed.size)
    }

    @Test fun a_stray_activity_end_with_nothing_awaited_dispatches_nothing() {
        val p = ready(protocol())
        p.onText(VA_START)
        p.onText(VA_END)
        assertEquals(0, sink.dispatchCount)
    }

    // ---- the ack gate: the next activityStart waits for ACTIVITY_END, or 500 ms (T0 P3e) ---------

    @Test fun frames_inside_the_ack_gap_are_held_then_flushed_after_the_deferred_start_on_the_ack() {
        val p = ready(protocol())
        p.onAppend(ByteArray(64) { 1 }); p.onCommit()
        val before = control.frames.size
        val h1 = ByteArray(64) { 7 }; val h2 = ByteArray(64) { 8 }
        clock.advance(100)
        assertTrue(p.onAppend(h1))
        assertTrue(p.onAppend(h2))
        assertEquals("held: nothing on the wire inside the gap", before, control.frames.size)

        p.onText(FINAL_1)
        p.onText(VA_END) // the ack clears the gate; the next sender call flushes
        assertEquals(before, control.frames.size)
        val n = ByteArray(64) { 9 }
        assertTrue(p.onAppend(n))
        assertEquals(
            listOf(ACTIVITY_START, audioFrame(h1), audioFrame(h2), audioFrame(n)),
            control.texts.drop(before),
        )
    }

    @Test fun the_ack_gate_opens_by_itself_after_500ms_without_the_ack() {
        val p = ready(protocol())
        p.onAppend(ByteArray(64) { 1 }); p.onCommit()
        val before = control.frames.size
        val h = ByteArray(64) { 7 }
        clock.advance(200)
        p.onAppend(h)
        assertEquals(before, control.frames.size)
        clock.advance(400) // 600 ms since activityEnd
        val n = ByteArray(64) { 9 }
        p.onAppend(n)
        assertEquals(listOf(ACTIVITY_START, audioFrame(h), audioFrame(n)), control.texts.drop(before))
    }

    @Test fun held_frames_overflow_to_a_shed_rather_than_grow_without_bound() {
        val p = ready(protocol())
        p.onAppend(ByteArray(64)); p.onCommit()
        val frame = ByteArray(3_200)
        repeat(20) { assertTrue(p.onAppend(frame)) }
        assertFalse(p.onAppend(frame))
    }

    // ---- discard: a turn the engine rescued locally must not fold into the next final -------------

    @Test fun discarding_an_open_activity_closes_it_and_swallows_its_final() {
        // The rotation shape: the engine shed the turn in the reconnect gap and rescued it from
        // the mirror; the part of it that DID reach the server must not be transcribed again.
        val p = ready(protocol())
        p.onAppend(ByteArray(64))
        assertTrue(p.onDiscard())
        assertEquals(ACTIVITY_END, control.texts.last())
        p.onText(FINAL_1)
        p.onText(VA_END)
        assertEquals("the discarded turn's final never reaches the engine", 0, sink.dispatchCount)

        // The next turn is a fresh activity with its own final.
        p.onAppend(ByteArray(64) { 5 }); p.onCommit()
        p.onText("""{"serverContent":{"inputTranscription":{"text":"next"}}}"""); p.onText(VA_END)
        assertEquals(listOf("1" to "next"), sink.completed)
    }

    @Test fun discarding_a_queued_turn_drops_it_unsent() {
        val p = ready(protocol())
        p.onAppend(ByteArray(64)); p.onCommit()
        val before = control.frames.size
        p.onAppend(ByteArray(64) { 7 }) // held inside the ack gap
        assertTrue(p.onDiscard())
        p.onText(FINAL_1); p.onText(VA_END) // the FIRST turn's final + ack, as recorded
        assertEquals("one final resolves the first committed turn", listOf("1" to FINAL_TEXT), sink.completed)
        p.onAppend(ByteArray(64) { 9 })
        assertEquals(
            "the discarded held frame never went out; the new turn starts clean",
            listOf(ACTIVITY_START, audioFrame(ByteArray(64) { 9 })),
            control.texts.drop(before),
        )
        assertEquals("nothing resolved the discarded turn", 1, sink.completed.size)
    }

    @Test fun discard_with_nothing_open_sends_nothing() {
        val p = ready(protocol())
        assertTrue(p.onDiscard())
        assertTrue(control.frames.isEmpty())
    }

    // ---- rotation: GoAway at a boundary / at the next boundary / hard stop; proactive age -----------

    @Test fun go_away_at_a_turn_boundary_rotates_at_once() {
        val p = ready(protocol())
        p.onAppend(ByteArray(64)); p.onCommit()
        p.onText(FINAL_1); p.onText(VA_END)
        p.onText(GO_AWAY)
        assertEquals(1, control.rotates)
    }

    @Test fun go_away_mid_activity_rotates_at_the_next_activity_end() {
        val p = ready(protocol())
        p.onAppend(ByteArray(64))
        p.onText(GO_AWAY)
        assertEquals("never mid-activity while the window allows", 0, control.rotates)
        p.onAppend(ByteArray(64))
        assertEquals(0, control.rotates)
        p.onCommit()
        p.onText(FINAL_1)
        assertEquals("the final alone is not the boundary", 0, control.rotates)
        p.onText(VA_END)
        assertEquals("rotated on the ack that closes the turn", 1, control.rotates)
        assertEquals("the turn's final still reached the engine first", listOf("1" to FINAL_TEXT), sink.completed)
    }

    @Test fun go_away_hard_stops_ten_seconds_before_time_left_even_mid_activity() {
        val p = ready(protocol())
        p.onAppend(ByteArray(64))
        p.onText(VA_START) // the start ack (~40 ms in practice) — keeps the start watchdog out of this test
        p.onText(GO_AWAY) // timeLeft 50 s -> hard stop at +40 s
        clock.advance(39_000)
        assertTrue(p.onAppend(ByteArray(64)))
        assertEquals(0, control.rotates)
        clock.advance(1_500)
        assertFalse("the frame that triggered the rotation is reported unsent", p.onAppend(ByteArray(64)))
        assertEquals(1, control.rotates)
    }

    @Test fun rotation_happens_once_per_open_and_bootstrap_re_arms_it() {
        val p = ready(protocol())
        p.onText(GO_AWAY)
        p.onText(GO_AWAY)
        assertEquals(1, control.rotates)
        ready(p) // the reopen
        p.onText(GO_AWAY)
        assertEquals(2, control.rotates)
    }

    @Test fun proactive_rotation_fires_at_a_boundary_after_570s_of_connection_age_and_not_before() {
        val p = ready(protocol())
        clock.advance(569_000)
        p.onAppend(ByteArray(64)); p.onCommit()
        p.onText(FINAL_1); p.onText(VA_END)
        assertEquals("569 s: no rotation", 0, control.rotates)
        clock.advance(2_000)
        assertFalse(p.onAppend(ByteArray(64))) // 571 s, at a boundary: rotate instead of opening
        assertEquals(1, control.rotates)
    }

    @Test fun proactive_rotation_waits_for_the_boundary_while_an_activity_is_open() {
        val p = ready(protocol())
        p.onAppend(ByteArray(64))
        p.onText(VA_START)
        clock.advance(575_000)
        assertTrue("mid-activity: keep streaming", p.onAppend(ByteArray(64)))
        assertEquals(0, control.rotates)
        p.onCommit(); p.onText(FINAL_1); p.onText(VA_END)
        assertEquals(1, control.rotates)
    }

    // ---- the watchdog: our signal unanswered for 3 s -> rotate (T0 P9) ----------------------------

    @Test fun no_activity_end_ack_within_3s_of_activity_end_rotates() {
        val p = ready(protocol())
        p.onAppend(ByteArray(64)); p.onText(VA_START); p.onCommit()
        clock.advance(2_900)
        p.onAppend(ByteArray(64))
        assertEquals(0, control.rotates)
        clock.advance(200)
        p.onAppend(ByteArray(64))
        assertEquals(1, control.rotates)
    }

    @Test fun a_final_or_the_ack_disarms_the_end_watchdog() {
        val p = ready(protocol())
        p.onAppend(ByteArray(64)); p.onText(VA_START); p.onCommit()
        p.onText(FINAL_1)
        clock.advance(5_000)
        p.onAppend(ByteArray(64))
        assertEquals("the final proved the server alive", 0, control.rotates)
    }

    @Test fun no_activity_start_ack_within_3s_of_activity_start_rotates() {
        val p = ready(protocol())
        p.onAppend(ByteArray(64))
        clock.advance(3_100)
        p.onAppend(ByteArray(64))
        assertEquals(1, control.rotates)

        // And with the ack, it never fires.
        val quiet = RecordingControl()
        val q = ready(protocol(quiet))
        q.onAppend(ByteArray(64))
        q.onText(VA_START)
        clock.advance(10_000)
        q.onAppend(ByteArray(64))
        assertEquals("an acked start never times out", 0, quiet.rotates)
    }

    @Test fun silence_never_triggers_the_watchdog_because_nothing_is_sent() {
        // Under manual VAD the app never sends outside a turn; between turns the clock may run
        // for minutes with no outbound signal pending, and the watchdog has nothing to time.
        val p = ready(protocol())
        p.onAppend(ByteArray(64)); p.onCommit(); p.onText(FINAL_1); p.onText(VA_END)
        clock.advance(120_000)
        p.onText(VA_START) // any inbound
        assertEquals(0, control.rotates)
    }

    // ---- close frames (T0 §4.3, verbatim as truncated on the wire) --------------------------------

    @Test fun classifyClose_maps_the_recorded_reasons_by_text_with_the_code_as_a_tie_breaker() {
        val p = protocol()
        assertEquals(FatalKind.INVALID_KEY, p.classifyClose(1007, "API key not valid. Please pass a valid API key."))
        assertEquals(
            FatalKind.INVALID_KEY,
            p.classifyClose(1008, "Method doesn't allow unregistered callers (callers without established identity). Please use API Key or other form of API c"),
        )
        assertEquals(
            FatalKind.MODEL_UNAVAILABLE,
            p.classifyClose(1008, "models/gemini-nope-live is not found for API version v1beta, or is not supported for bidiGenerateContent. Call ModelService"),
        )
        assertEquals(FatalKind.MODEL_UNAVAILABLE, p.classifyClose(1007, """Invalid JSON payload received. Unknown name "bogusField" at 'setup': Cannot find field."""))
        assertEquals(FatalKind.MODEL_UNAVAILABLE, p.classifyClose(1007, "Request contains an invalid argument."))
        assertEquals(FatalKind.MODEL_UNAVAILABLE, p.classifyClose(1007, "Precondition check failed."))
        assertEquals(FatalKind.OUT_OF_CREDIT, p.classifyClose(1008, "Quota exceeded for quota metric"))
        assertEquals(FatalKind.OUT_OF_CREDIT, p.classifyClose(1011, "RESOURCE_EXHAUSTED"))
    }

    @Test fun the_ten_minute_cap_and_plain_closes_are_transient() {
        val p = protocol()
        assertNull(
            "the cap: reconnect, never a latch",
            p.classifyClose(1008, "Connection aborted because the client failed to close the connection after receiving a GoAway signal once the session durat"),
        )
        assertNull(p.classifyClose(1000, ""))
        assertNull(p.classifyClose(1001, "going away"))
        assertNull(p.classifyClose(1006, ""))
        assertNull(p.classifyClose(1011, "internal error"))
        assertNull("an unknown 1008 reconnects under the ceiling rather than latching on a guess", p.classifyClose(1008, "something new"))
        assertNull(p.classifyClose(1008, "BidiGenerateContent session not found"))
    }

    @Test fun classifyFatal_keeps_the_handshake_table_though_no_probe_ever_reached_it() {
        val p = protocol()
        assertEquals(FatalKind.MODEL_UNAVAILABLE, p.classifyFatal(400))
        assertEquals(FatalKind.INVALID_KEY, p.classifyFatal(401))
        assertEquals(FatalKind.FORBIDDEN, p.classifyFatal(403))
        assertEquals(FatalKind.OUT_OF_CREDIT, p.classifyFatal(429))
        assertNull("409 (an orphaned session) retries, never latches", p.classifyFatal(409))
        assertNull(p.classifyFatal(500))
    }

    // ---- forward compatibility ---------------------------------------------------------------------

    @Test fun ignored_frames_dispatch_nothing() {
        val p = ready(protocol())
        p.onText(GENERATION_COMPLETE)
        p.onText(EMPTY_ACTIVITY_EDGE)
        p.onText("""{"serverContent":{"modelTurn":{"parts":[{"text":"I am a chatbot"}]}}}""")
        p.onText("""{"sessionResumptionUpdate":{"newHandle":"h","resumable":true}}""")
        p.onText("not json at all")
        assertEquals(0, sink.dispatchCount)
        assertEquals(0, control.rotates)
    }

    @Test fun backpressure_from_control_propagates_as_false() {
        val p = ready(protocol(RecordingControl(accept = false)))
        assertFalse(p.onAppend(ByteArray(64)))
    }

    @Test fun reset_clears_the_session_so_the_next_open_starts_before_setup() {
        val p = ready(protocol())
        p.onAppend(ByteArray(64))
        p.reset()
        p.bootstrap("k", null)
        val n = control.frames.size
        p.onAppend(ByteArray(64))
        assertEquals("not ready again until setupComplete", n, control.frames.size)
    }

    // ---- the no-log discipline: the key is a header pair and nothing else ------------------------

    @Test fun no_declared_field_holds_the_key_and_no_callback_or_frame_carries_it() {
        val key = "AIza-PRIVATE-c0ffee-D34DB33F-tail"
        val p = protocol()
        val headers = p.upgradeHeaders(key)
        val boot = p.bootstrap(key, "en")
        boot.forEach { control.send(it) }
        p.onText(SETUP_COMPLETE)
        p.onAppend(byteArrayOf(9, 9, 9, 9)); p.onCommit()
        p.onText(INTERIM_1); p.onText(FINAL_1); p.onText(VA_END)
        p.classifyClose(1007, "API key not valid. Please pass a valid API key.")
        p.reset()

        assertEquals("the header pair is the ONE legal carrier", listOf("x-goog-api-key" to key), headers)
        for (f in p.javaClass.declaredFields) {
            f.isAccessible = true
            assertFalse("field '${f.name}' leaked the key", f.get(p)?.toString().orEmpty().contains("c0ffee"))
        }
        assertFalse(p.toString().contains("c0ffee"))
        assertTrue("no sent frame carries the key", control.texts.none { it.contains("c0ffee") })
        val crossed = sink.deltas.flatMap { listOf(it.first, it.second) } + sink.completed.flatMap { listOf(it.first, it.second) } + sink.committed
        assertTrue("no callback argument carries the key", crossed.none { it.contains("c0ffee") })
    }

    @Test fun the_close_reason_never_crosses_the_seam() {
        // classifyClose returns a kind; the reason is matched and dropped. A transport test pins
        // that only the kind + code reach the listener; here: no field retains it either.
        val p = protocol()
        p.classifyClose(1007, "API key not valid. SECRET-ECHO")
        for (f in p.javaClass.declaredFields) {
            f.isAccessible = true
            assertFalse("field '${f.name}' retained the close reason", f.get(p)?.toString().orEmpty().contains("SECRET-ECHO"))
        }
    }
}
