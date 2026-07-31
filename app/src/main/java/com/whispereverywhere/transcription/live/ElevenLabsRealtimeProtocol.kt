package com.whispereverywhere.transcription.live

import com.whispereverywhere.provider.ProviderCatalog
import com.whispereverywhere.provider.ProviderId
import com.whispereverywhere.transcription.cloud.FatalKind
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong

/**
 * Pure ElevenLabs Scribe-v2-Realtime codec — Android-free (kotlinx-serialization only, no
 * `org.json`, no `android.util.Base64`), so it runs under `unitTests.isReturnDefaultValues = true`.
 * Verbatim-doc JSON drives [ElevenLabsRealtimeProtocolTest].
 *
 * Wire (recon + docs 2026-07-31): outbound a single frame type carries base64 PCM16 with a commit
 * flag; inbound `partial_transcript` is the live preview and `committed_transcript` is the finished
 * turn; errors arrive as `input_error`. Unknown types return null (forward-compatible).
 */
object ElevenLabsEvents {
    private val OUT = Json { encodeDefaults = true }
    private val IN = Json { ignoreUnknownKeys = true }

    /** [audioB64] is base64 of NATIVE 16 kHz PCM16 — ElevenLabs takes 16 k directly (no 24 k upsample). */
    fun audioChunk(audioB64: String, commit: Boolean): String =
        OUT.encodeToString(Chunk(audioBase64 = audioB64, commit = commit))

    sealed interface In {
        data class Partial(val text: String) : In
        data class Committed(val text: String) : In
        data class Error(val message: String) : In
    }

    fun parse(json: String): In? {
        val o = try { IN.parseToJsonElement(json) as? JsonObject } catch (_: Throwable) { null } ?: return null
        return when (o.str("message_type")) {
            "partial_transcript" -> In.Partial(o.str("text").orEmpty())
            "committed_transcript" -> In.Committed(o.str("text").orEmpty())
            "input_error" -> In.Error(o.str("message").orEmpty())
            else -> null // forward-compatible
        }
    }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull

    @Serializable
    private data class Chunk(
        @SerialName("message_type") val messageType: String = "input_audio_chunk",
        @SerialName("audio_base_64") val audioBase64: String,
        val commit: Boolean,
        @SerialName("sample_rate") val sampleRate: Int = 16000,
    )
}

/**
 * ElevenLabs Scribe v2 Realtime behind the [RealtimeProtocol] seam. ElevenLabs emits NO item ids and
 * accepts one committed turn at a time, so this adapter SYNTHESIZES the correlation the engine's FIFO
 * bind expects:
 *  - it emits `onCommitted(syntheticId)` at the moment it sends the commit-flagged chunk (binding the
 *    engine's oldest unbound seq, exactly as the OpenAI commit ack does), and
 *  - `onCompleted(syntheticId, text)` when `committed_transcript` lands.
 *
 * commit:true rides the LAST audio chunk (not a separate frame), so the adapter holds the most recent
 * append back by one (the fold slot): a following append flushes the held chunk commit:false; onCommit
 * flushes it commit:true. Because only ONE commit may be outstanding, a commit requested while one is
 * in flight is DEFERRED until the prior `committed_transcript` (or its timeout) resolves — the
 * single-in-flight serialization lives HERE, not in the engine. A timeout resolves the held turn via
 * `onTranscriptionFailed` -> the engine's Lost path, so a dropped/slow commit is rescued locally and
 * never stranded, and the deferred commit is then sent.
 *
 * Credential rule: the key rides the `xi-api-key` UPGRADE HEADER only (never a config frame, never a
 * field of this object). Content never crosses to a callback — deltas/completions carry text to the
 * engine's preview/ledger; errors carry code + length only.
 *
 * Two deliberate, reported deviations from the plan sketch, both behavior-preserving for every
 * documented event and every test:
 *  1. `control.send`/`sink.*` are called OUTSIDE the [gate] lock. The transport holds its own socket
 *     lock across `sendAppend`/`sendCommit` -> `onAppend`/`onCommit` (lock -> gate), while an inbound
 *     `committed_transcript` that flushes a deferred commit would run gate -> `control.send` (gate ->
 *     lock). Holding gate across the send inverts those orders and can deadlock; deciding under gate
 *     and acting outside it keeps the single-in-flight state atomic without the inversion.
 *  2. The in-flight slot is left via one atomic [claimInFlight]. Whichever of the timeout or the
 *     `committed_transcript` reaches it first resolves the turn; the loser sees the slot already
 *     empty and no-ops — so the turn resolves EXACTLY once even if a slow transcript races its
 *     timeout, instead of both firing a sink callback and mis-binding the next turn.
 */
class ElevenLabsRealtimeProtocol(
    private val scheduler: ReconnectScheduler,
    private val commitTimeoutMs: Long = DEFAULT_COMMIT_TIMEOUT_MS,
) : RealtimeProtocol {

    override val endpoint = ENDPOINT
    override val tolerant4xxRetry = false

    private lateinit var control: SessionControl
    private lateinit var sink: RealtimeTransport.Listener
    private val ids = AtomicLong(0)

    // All held state guarded by `gate`; the send/callback that acts on a decision runs OUTSIDE it.
    private val gate = Any()
    private var heldChunk: ByteArray? = null // the not-yet-flushed most-recent append (fold slot)
    private var inFlightId: String? = null   // the synthetic id of the commit awaiting a transcript
    private var commitDeferred = false       // a commit requested while one was in flight

    override fun upgradeHeaders(apiKey: String): List<Pair<String, String>> {
        val p = ProviderCatalog.byId(ProviderId.ELEVENLABS) // xi-api-key, bare value
        return listOf(p.authHeaderName to p.authHeaderValue(apiKey))
    }

    override fun bind(control: SessionControl, sink: RealtimeTransport.Listener) {
        this.control = control
        this.sink = sink
    }

    /**
     * No config frame — but this IS the per-open reset point for held state (parity with
     * [SonioxRealtimeProtocol.bootstrap]). [reset] fires only on a deliberate [RealtimeTransport.close];
     * a transient WS drop reconnects WITHOUT it, so without clearing here the pre-drop held-commit state
     * (heldChunk / the in-flight slot / a deferred commit) would survive onto the fresh socket:
     * post-reconnect commits would DEFER forever behind a stale inFlightId, and the still-armed stale
     * timeout could bind a later, unrelated turn. Clearing inFlightId here also neutralizes that stale
     * timeout — [onTimeout]'s [claimInFlight] then finds a mismatch (or null) and no-ops.
     */
    override fun bootstrap(apiKey: String, language: String?): List<Frame> {
        synchronized(gate) {
            heldChunk = null
            inFlightId = null
            commitDeferred = false
        }
        return emptyList()
    }

    override fun onAppend(pcm16k: ByteArray): Boolean {
        val toFlush = synchronized(gate) {
            val prev = heldChunk
            heldChunk = pcm16k // hold the newest; flush the previous (fold slot)
            prev
        }
        return if (toFlush != null) flush(toFlush, commit = false) else true
    }

    override fun onCommit(): Boolean {
        val prepared = synchronized(gate) {
            if (inFlightId != null) {
                commitDeferred = true // serialize: the prior commit must resolve before this one sends
                null
            } else {
                prepareCommitLocked()
            }
        } ?: return true // deferred: nothing is sent now, but the held commit "succeeds" (never blocks)
        return executeCommit(prepared)
    }

    /** Caller holds [gate]: claim the single in-flight slot and take the fold chunk for this commit. */
    private fun prepareCommitLocked(): PreparedCommit {
        val id = ids.incrementAndGet().toString()
        inFlightId = id
        val last = heldChunk
        heldChunk = null
        return PreparedCommit(id, last)
    }

    /** Outside [gate]: bind the engine's oldest unbound seq, flush the commit chunk, arm the timeout. */
    private fun executeCommit(c: PreparedCommit): Boolean {
        sink.onCommitted(c.id) // bind the engine's oldest unbound seq NOW (as the OpenAI commit ack does)
        val ok = if (c.last != null) {
            flush(c.last, commit = true)
        } else {
            control.send(Frame.Text(ElevenLabsEvents.audioChunk("", commit = true)))
        }
        scheduler.schedule(commitTimeoutMs) { onTimeout(c.id) }
        return ok
    }

    private fun flush(pcm: ByteArray, commit: Boolean): Boolean {
        val b64 = Base64.getEncoder().encodeToString(pcm) // 16 k native — NO resample
        return control.send(Frame.Text(ElevenLabsEvents.audioChunk(b64, commit)))
    }

    private fun onTimeout(id: String) {
        val claim = claimInFlight(expected = id) ?: return // already resolved / a later commit is in flight
        sink.onTranscriptionFailed(claim.id) // held turn -> the engine's Lost path (rescued locally)
        claim.next?.let { executeCommit(it) }
    }

    override fun onText(text: String) {
        when (val e = ElevenLabsEvents.parse(text)) {
            is ElevenLabsEvents.In.Partial -> sink.onDelta("", e.text) // preview only; id irrelevant
            is ElevenLabsEvents.In.Committed -> {
                val claim = claimInFlight(expected = null) ?: return // no turn in flight -> late/ignore
                sink.onCompleted(claim.id, e.text) // resolve the bound seq exactly-once (engine path)
                claim.next?.let { executeCommit(it) }
            }
            is ElevenLabsEvents.In.Error ->
                sink.onErrorEvent("input_error", e.message.length) // length only — never content
            null -> Unit
        }
    }

    /**
     * Atomically leave the in-flight state and, if a commit was deferred, prepare the next one — all
     * under [gate] so the timeout and the `committed_transcript` cannot both resolve the same turn.
     * [expected] == null claims whatever is in flight (a `committed_transcript` carries no id);
     * a non-null [expected] claims only if it still matches (a stale timeout finds a mismatch -> null).
     */
    private fun claimInFlight(expected: String?): Claim? = synchronized(gate) {
        val cur = inFlightId ?: return null
        if (expected != null && expected != cur) return null
        inFlightId = null
        val next = if (commitDeferred) { commitDeferred = false; prepareCommitLocked() } else null
        Claim(cur, next)
    }

    override fun classifyFatal(code: Int): FatalKind? = when (code) {
        401, 403 -> FatalKind.INVALID_KEY
        429 -> FatalKind.OUT_OF_CREDIT
        else -> null // 5xx / network -> transient -> reconnect
    }

    override fun reset() = synchronized(gate) {
        heldChunk = null
        inFlightId = null
        commitDeferred = false
    }

    private class PreparedCommit(val id: String, val last: ByteArray?)
    private class Claim(val id: String, val next: PreparedCommit?)

    companion object {
        const val ENDPOINT =
            "wss://api.elevenlabs.io/v1/speech-to-text/realtime?model_id=scribe_v2_realtime"

        /** `committed_transcript` lands well within this; a miss resolves the held turn Lost. */
        const val DEFAULT_COMMIT_TIMEOUT_MS = 8_000L
    }
}
