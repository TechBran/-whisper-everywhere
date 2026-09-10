package com.whispereverywhere.transcription.live

import com.whispereverywhere.transcription.cloud.FatalKind
import okio.ByteString

/**
 * One outbound WebSocket frame. Text carries JSON (OpenAI/ElevenLabs); Binary carries raw PCM
 * (Soniox streams s16le audio as binary frames, not base64 text). The transport sends each via the
 * matching okhttp3.WebSocket.send overload — no provider knowledge lives in the transport.
 */
sealed interface Frame {
    @JvmInline value class Text(val json: String) : Frame
    class Binary(val bytes: ByteString) : Frame
}

/**
 * The narrow slice of the live socket a protocol may drive: send a frame (subject to the same
 * queueSize backpressure the OpenAI path already enforced) and — for Soniox only — rotate the
 * session (finalize + reopen under the existing reconnect ceiling) when the server signals a max
 * duration. rotate() is a no-op-if-you-do-not-call-it capability; OpenAI/ElevenLabs never touch it.
 */
interface SessionControl {
    /** False if there is no live socket OR the outbound buffer is over threshold (backpressure). */
    fun send(frame: Frame): Boolean
    /** Finalize-and-reopen the socket, counted against maxReconnects (Soniox 413 rotation). */
    fun rotate()
}

/**
 * Everything that varies per realtime provider, and NOTHING that does not. The transport owns the
 * socket lifecycle + reconnect/backoff; the engine owns the turn ledger + exactly-once. A protocol
 * owns exactly six things: endpoint, upgrade headers, per-open bootstrap, outbound frame building,
 * inbound wire→the typed [RealtimeTransport.Listener] vocabulary, and status→FatalKind — the last
 * in two forms since 4.3.4: the handshake status ([classifyFatal]) and the server's CLOSE frame
 * ([classifyClose]), because a provider (Gemini) can answer every error, the bad key included,
 * with a 101 upgrade followed by a close frame.
 *
 * Lifecycle: [bind] once per connect() (hands the protocol its socket control + inbound sink for the
 * session), then [bootstrap] on EVERY open (including each reconnect — this is why the key is passed
 * in, never stored by the protocol), then onAppend/onCommit/onText/onBinary per event, then [reset]
 * on close. Every default-body member below is the pre-4.3.4 behaviour spelled out, so the three
 * shipped providers inherit it byte-identically.
 */
interface RealtimeProtocol {
    val endpoint: String

    /** Header pairs for the upgrade request. The key belongs HERE for header-auth providers; Soniox
     *  returns an empty list (its key rides [bootstrap], not a header). Never logged by the transport. */
    fun upgradeHeaders(apiKey: String): List<Pair<String, String>>

    /** OpenAI's tolerant connector: retry the FIRST 4xx once with the beta header before any fatal.
     *  Only OpenAI needs it; others return false so a 4xx classifies immediately. */
    val tolerant4xxRetry: Boolean

    /** Bind to this session's socket control + inbound sink for the protocol's lifetime. */
    fun bind(control: SessionControl, sink: RealtimeTransport.Listener)

    /** Frames to send once per open. OpenAI: sessionUpdate. ElevenLabs: none. Soniox: the key-bearing
     *  config — built here from [apiKey], sent by the transport, and NEVER retained by the protocol. */
    fun bootstrap(apiKey: String, language: String?): List<Frame>

    /** One 16 kHz PCM16 append. The protocol resamples/base64s/binaries/folds and sends via control.
     *  Returns false = backpressure or socket-down, so the engine sheds the turn (existing pattern). */
    fun onAppend(pcm16k: ByteArray): Boolean

    /** VAD close. OpenAI: send commit event. ElevenLabs: flush the held chunk with commit:true.
     *  Soniox: assemble the accumulated final tokens and emit onCompleted for the just-cut seq.
     *  Returns false = the finalize could not be sent (engine resolves the turn Lost, existing path). */
    fun onCommit(): Boolean

    /** One inbound TEXT frame → parse and dispatch to the bound sink (or ignore). */
    fun onText(text: String)

    /**
     * One inbound BINARY frame. Default: DECLINED — none of OpenAI/ElevenLabs/Soniox sends inbound
     * binary, and the silent no-op stays a tripwire for them. Gemini overrides it: its server sends
     * EVERY message as a binary frame carrying UTF-8 JSON (T0 2026-09-10, 1,542 of 1,542 frames),
     * so it decodes and hands the text to [onText].
     */
    fun onBinary(bytes: ByteString) = Unit

    /** Handshake status → fatal, or null = transient (the transport reconnects with backoff). */
    fun classifyFatal(code: Int): FatalKind?

    /**
     * A SERVER-INITIATED close frame ([code] + [reason]) → fatal, or null = transient (the transport
     * surfaces a disconnect AND reconnects with backoff). Default null: the pre-4.3.4 providers
     * never classified a close (and, until 4.3.4, the transport never reconnected on one). Gemini
     * keys on the REASON TEXT with the code as a tie-breaker (T0 §5.3: bad key = 1007 "API key not
     * valid", the 10-minute cap = 1008 with "GoAway" in the reason). The reason text must never
     * cross the seam, a log line, or a callback — the classification is the only thing that leaves.
     */
    fun classifyClose(code: Int, reason: String): FatalKind? = null

    /** Session teardown: drop any held state. NEVER logs the key or any turn content. */
    fun reset()
}
