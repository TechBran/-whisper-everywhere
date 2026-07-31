package com.whispereverywhere.transcription.live

import com.whispereverywhere.provider.ProviderCatalog
import com.whispereverywhere.provider.ProviderId
import com.whispereverywhere.recording.Resampler
import com.whispereverywhere.transcription.cloud.FatalKind
import com.whispereverywhere.tts.cloud.PcmBytes
import java.util.Base64

/**
 * OpenAI gpt-live-transcribe behind the [RealtimeProtocol] seam. Every byte this emits is what the
 * pre-seam engine+transport emitted: 24 kHz upsample + base64 append, session.update bootstrap,
 * item_id-ordered binding via the committed ack. This class exists so the seam has three
 * implementations, not so OpenAI's wire changes — it does not.
 */
class OpenAiRealtimeProtocol : RealtimeProtocol {
    override val endpoint = RealtimeTransport.ENDPOINT
    override val tolerant4xxRetry = true

    private lateinit var control: SessionControl
    private lateinit var sink: RealtimeTransport.Listener

    /**
     * item_id -> the running transcript accumulated from that turn's deltas. OpenAI streams
     * `conversation.item.input_audio_transcription.delta` as INCREMENTAL fragments (the field is
     * literally `delta`, per the Realtime convention — each event is the next fragment, NOT the whole
     * line so far). The preview strip renders deltas replace-only (`strip.text = text`), which matches
     * ElevenLabs (`partial_transcript`) and Soniox (`finals + non-finals`) because those are already
     * cumulative — but OpenAI is not, so without accumulating here the strip would flicker one fragment
     * at a time and never show the sentence grow, failing the "word-for-word AS SPOKEN" headline for
     * the flagship provider. So this adapter folds the fragments into the running line and emits THAT.
     * Cleared when the turn ends (committed/completed/failed) and on [reset]. Single-threaded: every
     * mutation is on the socket callback ([onText]).
     */
    private val deltaByItem = HashMap<String, StringBuilder>()

    override fun upgradeHeaders(apiKey: String): List<Pair<String, String>> {
        val p = ProviderCatalog.byId(ProviderId.OPENAI)
        return listOf(p.authHeaderName to p.authHeaderValue(apiKey))
    }

    override fun bind(control: SessionControl, sink: RealtimeTransport.Listener) {
        this.control = control
        this.sink = sink
    }

    // turn_detection:server_vad — the SERVER cuts turns and auto-commits, so `input_audio_buffer.committed`
    // now arrives at each server-detected boundary and drives the engine's server-turn allocation.
    override fun bootstrap(apiKey: String, language: String?): List<Frame> =
        listOf(Frame.Text(RealtimeEvents.sessionUpdate()))

    override fun onAppend(pcm16k: ByteArray): Boolean {
        // The SAME 16k→24k upsample + LE + base64 the engine's encodeAppend used to do, byte-for-byte.
        val out24k = Resampler.upsample16kTo24k(PcmBytes.toShortArrayLE(pcm16k))
        val b64 = Base64.getEncoder().encodeToString(shortsToBytesLE(out24k))
        return control.send(Frame.Text(RealtimeEvents.append(b64)))
    }

    // Unreachable on the live path: the server auto-commits under server_vad and the engine never
    // enqueues a client commit in server-driven mode. This is a benign no-op, NOT a retained
    // client-VAD path — the real commit-frame send (`input_audio_buffer.commit`) was DELETED with the
    // inversion. The engine's serverDriven=false ledger still exists and is tested, but re-enabling a
    // client-VAD turn HERE would mean restoring that removed send, so the fallback is documented, not
    // built. All three providers segment server-side, so it stays unbuilt.
    override fun onCommit(): Boolean = true

    override fun onText(text: String) {
        when (val e = RealtimeEventParser.parse(text)) {
            // Fold the incremental fragment into the turn's running line and emit the accumulated
            // total, so the strip shows the sentence grow word-for-word (not a one-fragment flicker).
            is Inbound.Delta -> {
                val running = deltaByItem.getOrPut(e.itemId) { StringBuilder() }.append(e.text)
                sink.onDelta(e.itemId, running.toString())
            }
            is Inbound.Completed -> { deltaByItem.remove(e.itemId); sink.onCompleted(e.itemId, e.transcript) }
            is Inbound.Committed -> { deltaByItem.remove(e.itemId); sink.onCommitted(e.itemId) }
            is Inbound.Failed -> { deltaByItem.remove(e.itemId); sink.onTranscriptionFailed(e.itemId) }
            is Inbound.Error -> sink.onErrorEvent(e.code, e.message.length)
            is Inbound.Ack, null -> Unit
        }
    }

    // Lifted VERBATIM from RealtimeTransport.classifyFatal (:214-223). The body distinguishes
    // rate-limit from exhausted credit, but the body is OFF-LIMITS on a handshake (credential
    // safety), so a 429 on the upgrade is treated as the WALLET being the problem, not rate-limit:
    // fail terminal, latch, let the local fallback rescue — never hammer an empty account.
    override fun classifyFatal(code: Int): FatalKind? = when (code) {
        401 -> FatalKind.INVALID_KEY
        403 -> FatalKind.FORBIDDEN
        429 -> FatalKind.OUT_OF_CREDIT
        else -> null // 5xx / network → transient → reconnect
    }

    override fun reset() { deltaByItem.clear() }

    /** PCM16 samples → headerless little-endian bytes (inverse of [PcmBytes.toShortArrayLE]). */
    private fun shortsToBytesLE(samples: ShortArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val v = samples[i].toInt()
            out[i * 2] = (v and 0xFF).toByte()
            out[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }
}
