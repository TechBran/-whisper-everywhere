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

    // The server auto-commits under server_vad; the engine never enqueues a client commit in
    // server-driven mode, so this is unreachable on the live path. Kept as a no-op only for the
    // documented client-VAD fallback mode (a hypothetical non-segmenting provider).
    override fun onCommit(): Boolean = true

    override fun onText(text: String) {
        when (val e = RealtimeEventParser.parse(text)) {
            is Inbound.Delta -> sink.onDelta(e.itemId, e.text)
            is Inbound.Completed -> sink.onCompleted(e.itemId, e.transcript)
            is Inbound.Committed -> sink.onCommitted(e.itemId)
            is Inbound.Failed -> sink.onTranscriptionFailed(e.itemId)
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

    override fun reset() {}

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
