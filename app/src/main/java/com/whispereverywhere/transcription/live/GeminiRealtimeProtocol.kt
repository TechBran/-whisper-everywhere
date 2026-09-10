package com.whispereverywhere.transcription.live

import com.whispereverywhere.provider.ProviderCatalog
import com.whispereverywhere.provider.ProviderId
import com.whispereverywhere.transcription.cloud.FatalKind
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okio.ByteString
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong

/**
 * Pure Gemini Live (`gemini-3.5-transcribe-live`, BidiGenerateContent) codec — Android-free
 * (kotlinx-serialization only, `java.util.Base64`, nothing from `android.*`), so it runs under
 * `unitTests.isReturnDefaultValues = true`. Every shape here is the one RECORDED on the wire by the
 * T0 probes (docs/measurements/2026-09-10-gemini-live-probes-t0.md §4), not the reference page's.
 *
 * Outbound: one `setup` per open (MANUAL VAD — `automaticActivityDetection.disabled:true`, T0 finding
 * 2: automatic VAD drops most speech after the first segment), then `activityStart` / base64 PCM16
 * audio / `activityEnd` inside `realtimeInput`. Inbound: `setupComplete`, cumulative
 * `interimInputTranscription`, one `inputTranscription` per activity, the undocumented top-level
 * `voiceActivity {type, audioOffset}` that rides with an EMPTY `serverContent`, and `goAway`.
 * Everything else — `generationComplete`, `turnComplete`, `modelTurn`, `usageMetadata`,
 * `sessionResumptionUpdate`, malformed frames — parses to nothing (forward-compatible).
 */
object GeminiLiveEvents {
    const val MODEL = "models/gemini-3.5-transcribe-live"
    const val AUDIO_MIME = "audio/pcm;rate=16000"

    private val OUT = Json { encodeDefaults = true; explicitNulls = false }
    private val IN = Json { ignoreUnknownKeys = true }

    /**
     * The per-open setup frame, pinned byte-exact (T0 §4.1). [language] is the user's bare ISO code
     * (`"de"`) → `"languageCodes":["de"]`; null (auto) OMITS the field — T0 P7: every value tried was
     * accepted and none had an observable effect, so it is sent as a hint, no region table.
     */
    fun setup(language: String?): String =
        OUT.encodeToString(
            SetupMessage(Setup(inputAudioTranscription = Transcription(languageCodes = language?.let { listOf(it) }))),
        )

    fun activityStart(): String = OUT.encodeToString(RealtimeInputMessage(RealtimeInput(activityStart = Empty())))

    fun activityEnd(): String = OUT.encodeToString(RealtimeInputMessage(RealtimeInput(activityEnd = Empty())))

    /** [base64] is base64 of NATIVE 16 kHz PCM16 mono little-endian — Gemini's own input format, no resample. */
    fun audio(base64: String): String =
        OUT.encodeToString(RealtimeInputMessage(RealtimeInput(audio = Audio(data = base64))))

    sealed interface In {
        object SetupComplete : In
        /** A cumulative whole-utterance hypothesis (replace mode; exact duplicates are common). */
        data class Interim(val text: String) : In
        /** The one final for the activity just ended. */
        data class Final(val text: String) : In
        object ActivityStart : In
        object ActivityEnd : In
        /** [timeLeftMs] parsed from the protobuf Duration string (`"50s"`); null if absent/unparsable. */
        data class GoAway(val timeLeftMs: Long?) : In
    }

    /**
     * One inbound frame → the events it carries, in order. A list, not a single event, because one
     * `BidiGenerateContentServerMessage` can carry several members (`voiceActivity` beside a
     * `serverContent`, `usageMetadata` beside anything) and none may short-circuit another.
     */
    fun parse(json: String): List<In> {
        val o = try { IN.parseToJsonElement(json) as? JsonObject } catch (_: Throwable) { null } ?: return emptyList()
        val out = ArrayList<In>(2)
        if (o["setupComplete"] is JsonObject) out += In.SetupComplete
        (o["serverContent"] as? JsonObject)?.let { sc ->
            (sc["interimInputTranscription"] as? JsonObject)?.str("text")?.let { out += In.Interim(it) }
            (sc["inputTranscription"] as? JsonObject)?.str("text")?.let { out += In.Final(it) }
            // modelTurn / turnComplete / generationComplete / interrupted / waitingForInput: ignored,
            // always — a model-id change can never type a chatbot reply into the user's field.
        }
        when ((o["voiceActivity"] as? JsonObject)?.str("type")) {
            "ACTIVITY_START" -> out += In.ActivityStart
            "ACTIVITY_END" -> out += In.ActivityEnd
        }
        (o["goAway"] as? JsonObject)?.let { out += In.GoAway(durationMs(it.str("timeLeft"))) }
        return out
    }

    /** `"50s"` / `"6.680s"` / `"0s"` → milliseconds; anything else → null. Lenient on purpose (undocumented shape). */
    internal fun durationMs(s: String?): Long? {
        val body = s?.trim()?.removeSuffix("s")?.takeIf { it.isNotEmpty() } ?: return null
        val seconds = body.toDoubleOrNull() ?: return null
        if (seconds.isNaN() || seconds < 0) return null
        return (seconds * 1000.0).toLong()
    }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull

    // Plain classes, not data classes: nothing here ever holds the key, but the Soniox rule stands —
    // no synthesized toString on anything that touches the wire.
    @Serializable private class SetupMessage(val setup: Setup)

    @Serializable private class Setup(
        val model: String = MODEL,
        val generationConfig: GenerationConfig = GenerationConfig(),
        val inputAudioTranscription: Transcription,
        val realtimeInputConfig: RealtimeInputConfig = RealtimeInputConfig(),
    )

    @Serializable private class GenerationConfig(val responseModalities: List<String> = listOf("TEXT"))

    @Serializable private class Transcription(
        val languageCodes: List<String>? = null,
        val mode: String = "VERBATIM",
    )

    @Serializable private class RealtimeInputConfig(val automaticActivityDetection: Aad = Aad())

    @Serializable private class Aad(val disabled: Boolean = true)

    @Serializable private class RealtimeInputMessage(val realtimeInput: RealtimeInput)

    @Serializable private class RealtimeInput(
        val activityStart: Empty? = null,
        val audio: Audio? = null,
        val activityEnd: Empty? = null,
    )

    @Serializable private class Audio(val data: String, val mimeType: String = AUDIO_MIME)

    @Serializable private class Empty
}

/**
 * Gemini Live transcription behind the [RealtimeProtocol] seam, in CLIENT-VAD mode — the one
 * shipped provider whose turns the APP cuts. T0 (2026-09-10) made every decision here:
 *
 * **Manual VAD, driven by the app's endpointer** (finding 2). Automatic VAD transcribed only the
 * first sentence of a recording (44/47 finals over 590 s were "Ask not."); manual VAD transcribed
 * everything, every time. So the engine runs `serverDriven = false`, `LiveTurnPolicy` keeps the
 * client VAD for a Gemini live session, and this adapter maps the engine's turn to an ACTIVITY:
 * `activityStart` LAZILY on the first audio frame of a turn (so the endpointer's pre-roll lands
 * inside it), audio frames, `activityEnd` on [onCommit]. Three rules from P3d/P3e/P3f:
 *  - never send an EMPTY activity (start then end with no audio): the server closes 1007
 *    "Precondition check failed" — structurally impossible here, an activity opens only on a frame;
 *  - the NEXT `activityStart` only after the server's `ACTIVITY_END` ack (or [ACK_GAP_MS]); frames
 *    arriving inside that gap are held and flushed after the deferred start. The fallback means a
 *    second activity can open — and close — before the first is answered, so closes are kept as
 *    a FIFO ([pending]) and each final/ack is attributed to its own activity, never to a flag;
 *  - a speech-less activity returns NO final, only the ack → that turn resolves EMPTY on the ack
 *    (`onCommitted` + `onCompleted(id, "")` → the engine's EmptyExpected) or the engine's seq strands.
 *
 * **One thread sends.** [onAppend] / [onCommit] / [onDiscard] run on the engine's sender thread
 * (under the transport lock); [onText] runs on the socket thread and only MUTATES STATE — the ring
 * flush on `setupComplete`, the held-frame flush on the ack, every `activityStart`/`activityEnd`
 * are sent by the next sender-thread call. That is what keeps `activityStart → audio → activityEnd`
 * ordered without a cross-thread send race; audio is continuous (~32 ms frames) while recording,
 * so "the next sender-thread call" is at most one frame away. `control.*`/`sink.*` are never called
 * under [gate] (the Soniox deadlock discipline).
 *
 * **Inbound** (finding 5): interims are cumulative whole-utterance strings (replace-mode preview,
 * exact duplicates deduped), exactly one final per activity → `onCommitted(id)` then
 * `onCompleted(id, text)` (the ElevenLabs shape; in client mode the engine binds the oldest unbound
 * seq — the one its `commit()` registered). `ACTIVITY_END` is the turn terminator.
 *
 * **Errors are always 101-then-close** (findings 3): [classifyFatal] never fires in practice;
 * [classifyClose] keys on the REASON TEXT (bad key = 1007 "API key not valid…"), and the reason
 * never crosses the seam or a log line.
 *
 * **Rotation** (findings 4, 6): GoAway at 540 s with `timeLeft "50s"`, close 1008 at 590 s; no
 * session resumption exists on this model, so every open sends a full `setup` and there is no
 * handle to carry. Rotate at the next turn boundary after GoAway, hard-stop at `timeLeft − 10 s`,
 * proactively at [PROACTIVE_ROTATE_MS] of connection age if no GoAway came. Watchdog: `activityEnd`
 * sent and no `ACTIVITY_END` ack within [WATCHDOG_MS] → rotate. The ACK, not the final, is what
 * pops the close and frees the boundary, so it is the ack the watchdog times: a final whose ack
 * never arrives would otherwise wedge the FIFO head for the rest of the open (`atBoundary` false →
 * GoAway degrades to the hard stop, the age rotation never fires). P9: acks arrive ≤ 0.44 s, always
 * right behind their own final, so timing the ack cannot false-fire inside [WATCHDOG_MS].
 *
 * **The key never becomes a field**: it arrives per open through [upgradeHeaders] as the
 * `x-goog-api-key` header pair (what Google's own SDK sends) and is consumed by the transport.
 */
class GeminiRealtimeProtocol(private val nowNanos: () -> Long = System::nanoTime) : RealtimeProtocol {

    override val endpoint = ENDPOINT
    override val tolerant4xxRetry = false

    private lateinit var control: SessionControl
    private lateinit var sink: RealtimeTransport.Listener
    private val ids = AtomicLong(0)

    /** A turn whose frames could not go straight to the wire (pre-setup, or inside the ack gap). */
    private class QueuedTurn {
        val frames = ArrayDeque<ByteArray>()
        var closed = false
        var discard = false
    }

    /**
     * An `activityEnd` on the wire whose `ACTIVITY_END` ack has not arrived. [discard]: the engine
     * resolved the turn locally, its final is swallowed; otherwise the engine expects exactly one
     * completion. [finalSeen] flips on its `inputTranscription`; [turn] and [sentNanos] feed the
     * diag line and the end watchdog.
     */
    private class PendingClose(val turn: Long, val sentNanos: Long, val discard: Boolean) {
        var finalSeen = false
    }

    // All state guarded by [gate]; control.* / sink.* are never called while holding it.
    private val gate = Any()
    private var ready = false
    /** Turns not yet on the wire, oldest first; only the LAST may still be open to new frames. */
    private val queue = ArrayDeque<QueuedTurn>()
    private var queuedBytes = 0
    /** An activity is open on the wire. Invariant: `open` implies [queue] is empty. */
    private var open = false
    /**
     * Activities closed on the wire and not yet acked, oldest first (B1). The [ACK_GAP_MS]
     * fallback deliberately opens the NEXT activity before the previous ack, so TWO closes can be
     * outstanding; the server answers per activity, in activity order (T0 finding 5:
     * `inputTranscription → generationComplete → ACTIVITY_END`), so a final belongs to the oldest
     * entry without one and an ack pops the head. One boolean per outcome misattributed them.
     */
    private val pending = ArrayDeque<PendingClose>()
    /** The OPEN activity's `activityStart` is unanswered; a close hands liveness to the end watchdog. */
    private var startAckPending = false
    private var activityStartSentNanos = 0L
    private var lastPreview = ""
    private var openedAtNanos = 0L
    private var setupSentNanos = 0L
    private var rotateAtBoundary = false
    private var hardStopNanos = Long.MAX_VALUE
    private var rotated = false
    private var turns = 0L

    override fun upgradeHeaders(apiKey: String): List<Pair<String, String>> {
        val p = ProviderCatalog.byId(ProviderId.GEMINI) // x-goog-api-key, bare value (T0 P1: verified)
        return listOf(p.authHeaderName to p.authHeaderValue(apiKey))
    }

    override fun bind(control: SessionControl, sink: RealtimeTransport.Listener) {
        this.control = control
        this.sink = sink
    }

    /** One `setup` per open — a reconnect or rotation starts a fresh session; there is no handle to resume. */
    override fun bootstrap(apiKey: String, language: String?): List<Frame> {
        synchronized(gate) {
            clearPerOpenState()
            val now = nowNanos()
            openedAtNanos = now
            setupSentNanos = now
        }
        return listOf(Frame.Text(GeminiLiveEvents.setup(language)))
    }

    override fun onAppend(pcm16k: ByteArray): Boolean {
        val out = ArrayList<String>(4)
        var accepted = true
        var rotate: String? = null
        synchronized(gate) {
            val now = nowNanos()
            rotate = rotationDue(now)
            if (rotate == null) {
                if (ready && open) {
                    out += GeminiLiveEvents.audio(encode(pcm16k)) // direct: the activity is open on the wire
                } else {
                    accepted = enqueue(pcm16k)
                    pump(now, out)
                }
            }
        }
        if (rotate != null) {
            rotateNow(rotate)
            return false // this frame never reached the server: the engine sheds the turn -> local rescue
        }
        var ok = true
        for (f in out) ok = control.send(Frame.Text(f)) && ok
        return accepted && ok
    }

    /** The app's endpointer cut the turn: close the activity (or mark the queued turn closed). */
    override fun onCommit(): Boolean {
        val out = ArrayList<String>(4)
        synchronized(gate) {
            val now = nowNanos()
            if (ready && open) closeWireActivity(now, out, discard = false)
            else queue.lastOrNull()?.takeIf { !it.closed }?.closed = true // nothing queued: nothing to end (never an empty activity)
            pump(now, out)
        }
        var ok = true
        for (f in out) ok = control.send(Frame.Text(f)) && ok
        return ok
    }

    /**
     * The engine resolved the turn LOCALLY without committing it (shed by a reconnect gap or
     * backpressure, or under the 100 ms minimum). Its audio must not fold into the next final: a
     * queued turn is dropped unsent, an open activity is closed and its final swallowed — and its
     * INTERIMS never reach the preview strip either (the mirror already typed those words). Without
     * this every rotation would duplicate the shed turn's tail into the next Gemini sentence.
     */
    override fun onDiscard(): Boolean {
        val out = ArrayList<String>(2)
        synchronized(gate) {
            val now = nowNanos()
            if (ready && open) {
                closeWireActivity(now, out, discard = true)
            } else {
                queue.lastOrNull()?.takeIf { !it.closed }?.let { it.closed = true; it.discard = true }
            }
            pump(now, out)
        }
        var ok = true
        for (f in out) ok = control.send(Frame.Text(f)) && ok
        return ok
    }

    override fun onBinary(bytes: ByteString) = onText(bytes.utf8()) // every inbound frame is binary (T0 finding 1)

    override fun onText(text: String) {
        val events = GeminiLiveEvents.parse(text)
        if (events.isEmpty()) return
        var delta: String? = null
        val resolves = ArrayList<Completion>(2) // built under gate, fired outside it
        var rotate: String? = null
        synchronized(gate) {
            val now = nowNanos()
            for (e in events) when (e) {
                is GeminiLiveEvents.In.SetupComplete -> {
                    ready = true
                    android.util.Log.i(TAG, "gemini setup complete rtt=${(now - setupSentNanos) / 1_000_000}ms")
                    // The queued frames flush on the next sender-thread call — never from here.
                }
                is GeminiLiveEvents.In.Interim -> {
                    // Transcription in flight proves an activity was taken — the OPEN one only when no
                    // closed activity is still owed its final (the server answers in activity order).
                    val owed = pending.firstOrNull { !it.finalSeen }
                    if (owed == null) startAckPending = false
                    // These words belong to the activity whose final is still owed. If the engine
                    // DISCARDED that activity it already rescued the turn from the mirror and typed
                    // it, so previewing the server's version of the same words would flicker text the
                    // bubble already owns until the next activity's interim replaced it. Skip them —
                    // and leave [lastPreview] alone, so the next activity's first interim still shows.
                    if (owed?.discard != true && e.text != lastPreview) { lastPreview = e.text; delta = e.text }
                }
                is GeminiLiveEvents.In.Final -> {
                    lastPreview = ""
                    val owner = pending.firstOrNull { !it.finalSeen }
                    when {
                        owner == null -> Unit // a final for no closed activity (never observed): ignore, never bind
                        owner.discard -> owner.finalSeen = true // the engine already rescued this turn locally
                        else -> { owner.finalSeen = true; resolves += Completion(ids.incrementAndGet().toString(), e.text) }
                    }
                }
                is GeminiLiveEvents.In.ActivityStart -> startAckPending = false
                is GeminiLiveEvents.In.ActivityEnd -> {
                    lastPreview = "" // a speech-less activity's unconfirmed interims must not dedupe the next turn's first
                    val done = pending.removeFirstOrNull() // a stray ack (never observed) pops nothing
                    if (done != null) {
                        // A speech-less activity: no final ever comes (P3f) -> resolve EMPTY on its ack.
                        if (!done.finalSeen && !done.discard) resolves += Completion(ids.incrementAndGet().toString(), "")
                        android.util.Log.i(
                            TAG,
                            "gemini turn ${done.turn} end ack=${(now - done.sentNanos) / 1_000_000}ms final=${done.finalSeen} queued=${queue.size}",
                        )
                    }
                }
                is GeminiLiveEvents.In.GoAway -> {
                    rotateAtBoundary = true
                    val left = e.timeLeftMs ?: 0L
                    hardStopNanos = minOf(hardStopNanos, now + maxOf(left - HARD_STOP_MARGIN_MS, 0L) * 1_000_000L)
                    android.util.Log.i(TAG, "gemini goAway timeLeft=${e.timeLeftMs ?: -1}ms age=${ageSeconds(now)}s")
                }
            }
            rotate = rotationDue(now)
        }
        delta?.let { sink.onDelta("", it) } // preview only, replace mode — never binds
        for (c in resolves) { sink.onCommitted(c.id); sink.onCompleted(c.id, c.text) } // bind, then resolve exactly-once
        rotate?.let { rotateNow(it) }
    }

    /** Handshake status → kind. Kept for completeness: no probe ever produced a non-101 handshake (T0 §2.5). */
    override fun classifyFatal(code: Int): FatalKind? = when (code) {
        400 -> FatalKind.MODEL_UNAVAILABLE // our setup/URL, or a bad key the pre-validator would already have caught
        401 -> FatalKind.INVALID_KEY
        403 -> FatalKind.FORBIDDEN
        429 -> FatalKind.OUT_OF_CREDIT
        else -> null // 409 / 5xx / network -> transient -> reconnect
    }

    /**
     * The close-frame map (T0 §4.3 / §5.3): reason text first, code as a tie-breaker only. The
     * reason is matched here and goes nowhere else.
     */
    override fun classifyClose(code: Int, reason: String): FatalKind? {
        val r = reason.lowercase()
        return when {
            r.contains("api key not valid") -> FatalKind.INVALID_KEY // 1007 — the bad key
            r.contains("unregistered callers") -> FatalKind.INVALID_KEY // 1008 — no key reached the server
            r.contains("is not found for api version") || r.contains("not supported for bidigeneratecontent") ->
                FatalKind.MODEL_UNAVAILABLE // 1008 — the model id
            r.contains("invalid json payload") || r.contains("invalid argument") || r.contains("precondition check failed") ->
                FatalKind.MODEL_UNAVAILABLE // 1007 — OUR payload: latch visibly, it cannot self-heal
            r.contains("quota") || r.contains("resource_exhausted") || r.contains("rate limit") ->
                FatalKind.OUT_OF_CREDIT // not observed; kept
            else -> null // the cap ("GoAway" / "session durat" / "aborted"), 1000/1001/1006/1011-1013, unknowns -> reconnect
        }
    }

    override fun reset() = synchronized(gate) { clearPerOpenState() }

    // ---- turn machine (all callers hold [gate]) --------------------------------------------------

    /** The ONLY place a [QueuedTurn] is created, and always with a frame: a queued turn is never empty. */
    private fun enqueue(pcm: ByteArray): Boolean {
        if (queuedBytes + pcm.size > MAX_QUEUED_BYTES) return false // ~2 s buffered: shed, the mirror rescues
        val turn = queue.lastOrNull()?.takeIf { !it.closed } ?: QueuedTurn().also { queue.addLast(it) }
        turn.frames.addLast(pcm)
        queuedBytes += pcm.size
        return true
    }

    /**
     * Move queued turns onto the wire while the server can take a new activity: after
     * `setupComplete`, with no activity open, and past the ack gate. A discarded turn is dropped
     * unsent; a closed turn goes out as start + audio + end; the live turn opens and stays open.
     */
    private fun pump(now: Long, out: MutableList<String>) {
        while (ready && !open && queue.isNotEmpty()) {
            // P3e: the next start waits for the LATEST close's ack (acks pop in order, so any entry
            // outstanding means the latest is), else 500 ms since that close.
            val latest = pending.lastOrNull()
            if (latest != null && now - latest.sentNanos < ACK_GAP_MS * 1_000_000L) return
            val t = queue.removeFirst()
            var bytes = 0
            for (f in t.frames) bytes += f.size
            queuedBytes -= bytes
            if (t.discard) continue // the engine rescued it locally; the server never needs it
            out += GeminiLiveEvents.activityStart()
            open = true
            startAckPending = true
            activityStartSentNanos = now
            for (f in t.frames) out += GeminiLiveEvents.audio(encode(f))
            if (t.closed) closeWireActivity(now, out, discard = false)
        }
    }

    private fun closeWireActivity(now: Long, out: MutableList<String>, discard: Boolean) {
        out += GeminiLiveEvents.activityEnd()
        open = false
        startAckPending = false // from here the end watchdog times this activity, not the start one
        turns++
        pending.addLast(PendingClose(turn = turns, sentNanos = now, discard = discard))
    }

    /** The rotation reason due NOW, or null. Once per open; the transport's ceiling bounds the rest. */
    private fun rotationDue(now: Long): String? {
        if (rotated) return null
        val atBoundary = !open && pending.isEmpty()
        // The oldest close still owed its ACK. Not "still owed its final": the ack is what pops the
        // entry, so a close whose final arrived and whose `ACTIVITY_END` never did must still time
        // out — otherwise it wedges the head and [atBoundary] stays false for the rest of the open.
        val stalled = pending.firstOrNull()
        val reason = when {
            now >= hardStopNanos -> "goaway-hardstop"
            stalled != null && now - stalled.sentNanos > WATCHDOG_MS * 1_000_000L -> "watchdog-end"
            startAckPending && now - activityStartSentNanos > WATCHDOG_MS * 1_000_000L -> "watchdog-start"
            atBoundary && rotateAtBoundary -> "goaway"
            atBoundary && ready && now - openedAtNanos >= PROACTIVE_ROTATE_MS * 1_000_000L -> "age"
            else -> null
        } ?: return null
        rotated = true
        return reason
    }

    private fun rotateNow(reason: String) {
        android.util.Log.i(TAG, "gemini rotate reason=$reason age=${ageSeconds(nowNanos())}s turns=$turns")
        control.rotate() // the transport surfaces the one disconnect this owes; bootstrap() resets us on the reopen
    }

    private fun ageSeconds(now: Long): Long = (now - openedAtNanos) / 1_000_000_000L

    private fun clearPerOpenState() {
        ready = false
        queue.clear()
        queuedBytes = 0
        open = false
        pending.clear()
        startAckPending = false
        lastPreview = ""
        rotateAtBoundary = false
        hardStopNanos = Long.MAX_VALUE
        rotated = false
        turns = 0L
    }

    private fun encode(pcm: ByteArray): String = Base64.getEncoder().encodeToString(pcm)

    private class Completion(val id: String, val text: String)

    companion object {
        private const val TAG = "WE-DIAG"
        const val ENDPOINT =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"

        /** Audio held before `setupComplete` / inside the ack gap before the turn is shed: ~2 s of 16 kHz PCM16. */
        const val MAX_QUEUED_BYTES = 64_000

        /** The next `activityStart` waits this long for the `ACTIVITY_END` ack before going anyway (P3e: 500 ms = 4/4 clean). */
        const val ACK_GAP_MS = 500L

        /** No final / `ACTIVITY_END` (or `ACTIVITY_START`) within this of our signal → rotate (P9: acks ≤ 0.44 s, n = 28). */
        const val WATCHDOG_MS = 3_000L

        /** Rotate at the next boundary from this connection age if no GoAway came (P6: GoAway 540 s, close 590 s). */
        const val PROACTIVE_ROTATE_MS = 570_000L

        /** After GoAway, rotate no later than `timeLeft` minus this, mid-activity if it must (the 50 s window). */
        const val HARD_STOP_MARGIN_MS = 10_000L
    }
}
