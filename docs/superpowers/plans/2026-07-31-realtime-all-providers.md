# Realtime word-for-word for ALL streaming-capable providers (OpenAI + ElevenLabs + Soniox)

> **For agentic workers:** REQUIRED SUB-SKILL: use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax. Assert the fresh green suite count at the gate — do not hardcode it here.

**Goal:** the mic button's word-for-word live mode currently streams **only** OpenAI. Widen it to every provider with a native BYOK realtime WebSocket — **OpenAI, ElevenLabs, Soniox** — while leaving OpenAI's live behavior byte-for-byte identical. **Gemini has no client-usable realtime path** (Live API wants ephemeral backend-minted tokens); its selector row offers segment mode only, with **no apology copy** — a provider limitation, not a defect.

**Architecture — the seam:** the recon proved ~80% of the live stack is provider-agnostic (the `LiveTranscriptionEngine` turn ledger, seq exactly-once, send-queue non-block, backpressure shed, fatal latch, and the `RealtimeTransport` reconnect/backoff shell). What varies per provider is exactly six things: **endpoint + upgrade headers, the per-open bootstrap, outbound frame building (resample? base64? binary? commit-as-frame vs commit-as-flag vs client-assembled), inbound wire→our typed vocabulary, and status→FatalKind.** Extract those behind a per-provider **`RealtimeProtocol`** injected into `RealtimeTransport`. The transport stays the shared socket+reconnect shell; the engine's ledger is untouched. OpenAI becomes `OpenAiRealtimeProtocol` and produces identical frames — the regression contract holds.

**One unavoidable, behavior-preserving signature change:** the engine currently upsamples 16 k→24 k + base64-encodes *before* calling `transport.sendAppend(base64)`. EL needs 16 k base64 in a different JSON; Soniox needs raw 16 k **binary**. So the encode moves behind the seam: the engine hands **raw 16 kHz PCM** down (`sendAppend(pcm: ByteArray)`), and each protocol frames it. OpenAI's protocol performs today's 24 k+base64 inside the seam → **identical bytes on the wire**.

**Tech Stack:** Kotlin 2.0.21, OkHttp 4.12.0 (**pinned** — binary `WebSocket.send(ByteString)` is already available), kotlinx-serialization-json 1.7.3, JUnit 4. **No new dependencies.**

## Global constraints (carried, still binding)
- **`java` NOT on PATH.** PowerShell: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio1\jbr"`; `.\gradlew.bat --no-daemon`. `assembleRelease`/`bundleRelease` (R8) green both sides.
- **NEVER `connectedAndroidTest` or `installDebug`.** Instrumented = compile-check only.
- `unitTests.isReturnDefaultValues = true`; **`org.json` BANNED**, **`android.util.Base64` BANNED** — kotlinx.serialization / `java.util.Base64` only. The pure protocol codecs must stay Android-free.
- **Commit ONLY named files, never `git add -A`. Retry once on `index.lock`. Branch `main`.**
- **Baseline HEAD `8af421a`, 708 tests / 0 failures, 3.3.0/73** (re-cut at the end of THIS wave). Assert the green count FRESH at the gate; it grows by the new suites, never regresses.
- **No credential/content logging** (no key, header, body, token text, or transcript). **No speed claims** in user copy — **including Soniox: never "fastest"**, say what it does. Every price says "about".

## The regression contract (non-negotiable)
- **OpenAI's live behavior is IDENTICAL after extraction.** Every existing `transcription.live` test keeps passing with **imports/renames only**. Two tests assert the *encode*, which physically relocates behind the seam; they move to `OpenAiRealtimeProtocolTest` with their **expected bytes preserved verbatim** — this is the single reviewed relocation, called out in Task 1, and it is a byte-for-byte no-op on the wire. Any *other* assertion change is a stop-and-report.
- **Deltas are NEVER injected** — preview only, all providers. Completions/turns feed the existing seq/orderer path exactly-once. Mic-only rides `SourceRoutedTranscriptionEngine`; `Fallback(live, local)` wrapping is preserved byte-identically so a dead socket's turns are rescued locally.
- **Credential rules per provider:** OpenAI/ElevenLabs — key in the **upgrade header only**. **Soniox — key rides the FIRST CONFIG MESSAGE** (its documented mechanism, over TLS): built inside the no-log discipline — no log line, no exception message, no `toString` ever carries any part of it. The audit attacks this specifically; Task 3 pins it.
- The capture thread never blocks; backpressure sheds turns to local (existing pattern). Reconnect/backoff/give-up is reused per provider. Soniox 413 max-duration → graceful finalize-and-reopen **under the existing reconnect ceiling**.

---

## Pinned realtime pricing (fetched live 2026-07-31)

Both new providers publish a realtime rate, so **no UNKNOWN policy is triggered** this wave. Every figure the UI shows says **"about"**; no speed word for any provider.

| Provider (pinned realtime model) | Published rate | ~$/min shown | Source |
|---|---|---|---|
| **OpenAI** `gpt-live-transcribe` | $0.017/min (carried from C4) | **about $0.017/min** | (existing live pin) |
| **ElevenLabs** `scribe_v2_realtime` | **$0.39/hr** | **about $0.007/min** | elevenlabs.io/realtime-speech-to-text ($0.39/hr ≈ $0.0065/min) |
| **Soniox** `stt-rt-v5` | **$0.12/hr real-time (streaming)** | **about $0.002/min** | soniox.com/pricing ("$0.12/hour for real-time (streaming)") |

Soniox is the cheapest but its copy says only *what it does* ("Transcribes word-for-word as you speak"), never "fastest"/"cheapest". Rounding is conservative-up ($0.0065 → "about $0.007").

---

## File Structure

| File | Change |
|---|---|
| `transcription/live/RealtimeProtocol.kt` | **Create.** The seam: `RealtimeProtocol` interface, `Frame` (Text/Binary), `SessionControl` (send + rotate). Android-free. |
| `transcription/live/OpenAiRealtimeProtocol.kt` | **Create.** OpenAI behind the seam — wraps the untouched `RealtimeEvents`/`RealtimeEventParser`; 24 k upsample + base64 (moved from the engine); item_id binding; tolerant beta-retry; classifyFatal. **Identical frames.** |
| `transcription/live/ElevenLabsRealtimeProtocol.kt` | **Create.** Scribe v2 Realtime: `xi-api-key` header, 16 k base64 chunks, commit:true folded onto the last chunk, single-in-flight serialization with timeout→Lost, `input_error` mapping, synthetic id correlation. |
| `transcription/live/SonioxRealtimeProtocol.kt` | **Create.** stt-rt-v5: config-first key under the no-log discipline, RAW s16le **binary** frames, is_final token accumulation → client-assembled turn at VAD close, numeric error map, empty-frame finalize + session rotation under the reconnect ceiling. |
| `transcription/live/RealtimeTransport.kt` | **Modify.** Add `protocol: RealtimeProtocol = OpenAiRealtimeProtocol()` (default keeps the 3-arg ctor). Endpoint/headers/bootstrap/dispatch/classifyFatal/binary-send delegate to the protocol; `sendAppend(pcm: ByteArray)`. Reconnect/backoff/give-up shell unchanged. |
| `transcription/live/RealtimeEvents.kt` | **Untouched** (re-asserted). Still the OpenAI codec; `RealtimeEventParserTest` stays green with zero edits. |
| `transcription/live/LiveTranscriptionEngine.kt` | **Modify.** `Transport.sendAppend(pcm: ByteArray)`; delete `encodeAppend`/`shortsToBytesLE`/`Resampler`/`Base64` imports (moved to `OpenAiRealtimeProtocol`); `senderLoop` sends raw PCM. Ledger/correlation/exactly-once **byte-identical**. |
| `service/FloatingBubbleService.kt` | **Modify.** `decideEngineChoice` gates CLOUD_LIVE on `isRealtimeStt(provider)` (streaming-capable set), not `== OPENAI`; add `REALTIME_STT_PROVIDERS`/`isRealtimeStt`; CLOUD_LIVE branch selects the per-provider protocol; flip the OpenAI-only KDoc. `lastLiveEngine`/`sessionIsLive`/Fallback+SourceRouted wrapping unchanged. |
| `provider/ProviderCatalog.kt` | **Modify.** SONIOX `supportsStreaming = false → true`; update its KDoc (realtime ships this wave). |
| `ui/screens/CloudProvidersScreen.kt` | **Modify.** `liveModeRowVisible` → any streaming-capable selected+configured provider; `liveModeLabel(id)` per-provider label+price; Gemini shows no live row and no apology copy. |
| `ui/screens/EnginesAndVoicesScreen.kt` | **Modify.** Thread the selected `ProviderId` through `LiveModeRow`/`liveModeLabel`; flip the OpenAI-only comment. |
| `ui/screens/ModeDashboard.kt` | **Modify.** `dictationLiveActive` widens to `isRealtimeStt` in lockstep with `decideEngineChoice`; flip KDoc. Chip is already provider-generic. |
| `docs/PLAY-DECLARATIONS.md` | **Modify.** Flip the "live is OpenAI-only" line; add the 3.x realtime-all-providers ledger entry; confirm the §5/§6 recipient narrative already names all four. |
| **Tests** | New: `RealtimeProtocolContractTest`, `OpenAiRealtimeProtocolTest` (absorbs the two relocated encode pins), `ElevenLabsRealtimeProtocolTest`, `SonioxRealtimeProtocolTest`, `SonioxNoLogDisciplineTest`. Modified (flagged): `RealtimeTransportTest` (`append_and_commit_forwarded…` byte-identical rewrite; two param-renames), `LiveTranscriptionEngineTest` (fake `Transport` param `String`→`ByteArray`; `sender_upsamples…` relocates), `EngineSelectionTest`, `ModeDashboardLogicTest`, `CloudProvidersScreenLogicTest`. |

Untouched (re-asserted, not edited): the batch/TTS/segment-STT engines, `SttProvider`/adapters, `RealtimeEvents`/`RealtimeEventParser`, the four batch adapters, `FallbackTranscriptionEngine`, `SourceRoutedTranscriptionEngine`, the send-queue/backpressure/fatal-latch machinery.

---

## Task 1: Extract the `RealtimeProtocol` seam — OpenAI behind it, ZERO behavior change

### Step 1 — the seam types (`RealtimeProtocol.kt`, new; Android-free)

```kotlin
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
 * inbound wire→the typed [RealtimeTransport.Listener] vocabulary, and status→FatalKind.
 *
 * Lifecycle: [bind] once per connect() (hands the protocol its socket control + inbound sink for the
 * session), then [bootstrap] on EVERY open (including each reconnect — this is why the key is passed
 * in, never stored by the protocol), then onAppend/onCommit/onText per event, then [reset] on close.
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

    /** Handshake/close status → fatal, or null = transient (the transport reconnects with backoff). */
    fun classifyFatal(code: Int): FatalKind?

    /** Session teardown: drop any held state. NEVER logs the key or any turn content. */
    fun reset()
}
```

### Step 2 — `OpenAiRealtimeProtocol.kt` (new): today's behavior, verbatim, relocated

The encode (`Resampler.upsample16kTo24k` + `java.util.Base64`) and the `item_id`↔bind dispatch move here from the engine/transport. `RealtimeEvents`/`RealtimeEventParser` are reused **unchanged**, so their exact-shape tests stay green untouched.

```kotlin
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

    private lateinit var sink: RealtimeTransport.Listener

    override fun upgradeHeaders(apiKey: String): List<Pair<String, String>> {
        val p = ProviderCatalog.byId(ProviderId.OPENAI)
        return listOf(p.authHeaderName to p.authHeaderValue(apiKey))
    }

    override fun bind(control: SessionControl, sink: RealtimeTransport.Listener) { this.sink = sink }

    // turn_detection:null — WE own turn commits. Identical to the pre-seam onOpen bootstrap.
    override fun bootstrap(apiKey: String, language: String?): List<Frame> =
        listOf(Frame.Text(RealtimeEvents.sessionUpdate()))

    override fun onAppend(pcm16k: ByteArray): Boolean {
        // The SAME 16k→24k upsample + LE + base64 the engine's encodeAppend used to do, byte-for-byte.
        val out24k = Resampler.upsample16kTo24k(PcmBytes.toShortArrayLE(pcm16k))
        val b64 = Base64.getEncoder().encodeToString(shortsToBytesLE(out24k))
        return control.send(Frame.Text(RealtimeEvents.append(b64)))
    }

    override fun onCommit(): Boolean = control.send(Frame.Text(RealtimeEvents.commit()))

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

    override fun classifyFatal(code: Int): FatalKind? = when (code) {
        401 -> FatalKind.INVALID_KEY
        403 -> FatalKind.FORBIDDEN
        429 -> FatalKind.OUT_OF_CREDIT
        else -> null // 5xx / network → transient → reconnect
    }

    override fun reset() {}

    private lateinit var control: SessionControl
    // bind() also captures control:
    private fun captureControl(c: SessionControl) { control = c }

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
```

> Implementation note: fold `captureControl` into `bind` (`override fun bind(control, sink) { this.control = control; this.sink = sink }`) — shown split above only to keep the field near its user. `classifyFatal`'s 401/403/429 mapping is lifted verbatim from `RealtimeTransport.classifyFatal` (`:214-223`), including the "a 429 on the upgrade is the wallet, not rate-limit" rationale — keep that KDoc.

### Step 3 — `RealtimeTransport.kt` becomes the shared shell (delegates the six items)

Add the protocol param with an OpenAI default so the existing `RealtimeTransport(factory, scheduler, listener)` call sites and `RealtimeTransportTest` compile unchanged:

```kotlin
class RealtimeTransport(
    private val factory: WebSocketFactory,
    private val scheduler: ReconnectScheduler,
    private val listener: Listener,
    private val protocol: RealtimeProtocol = OpenAiRealtimeProtocol(), // default = today's OpenAI path
    private val backoff: Backoff = Backoff.DEFAULT,
    private val maxReconnects: Int = DEFAULT_MAX_RECONNECTS,
) {
```

`connect` binds the protocol once, then opens (bootstrap fires per open):

```kotlin
fun connect(apiKey: String, language: String?) {
    synchronized(lock) {
        this.apiKey = apiKey
        this.language = language
        closed = false
        reconnectAttempts = 0
        protocol.bind(control, listener)   // control = the SessionControl below
        openSocket()
    }
}
```

`sendAppend`/`sendCommit` become raw-PCM thin wrappers over the protocol (backpressure now lives in `SessionControl.send`, so it is enforced once, for every provider):

```kotlin
/** Enqueue one append as raw 16 kHz PCM16; the protocol frames it. False = no socket or backpressure. */
fun sendAppend(pcm: ByteArray): Boolean = synchronized(lock) {
    if (webSocket == null) return false
    protocol.onAppend(pcm)
}

/** Finalize the current turn per the protocol (commit event / commit-flag / client assembly). */
fun sendCommit(): Boolean = synchronized(lock) {
    if (webSocket == null && protocol !is SonioxRealtimeProtocol) return false
    protocol.onCommit()
}
```

The `SessionControl` the protocol drives — the ONE place `okhttp3.WebSocket.send` is called, now handling both frame types and the queueSize backpressure that used to sit in `sendAppend`:

```kotlin
private val control = object : SessionControl {
    override fun send(frame: Frame): Boolean = synchronized(lock) {
        val ws = webSocket ?: return false
        if (ws.queueSize() > MAX_OUTBOUND_BYTES) return false
        when (frame) {
            is Frame.Text -> ws.send(frame.json)
            is Frame.Binary -> ws.send(frame.bytes)   // OkHttp 4.12.0 binary send — Soniox audio
        }
    }
    override fun rotate() = synchronized(lock) {
        if (closed) return@synchronized
        // Empty-frame finalize is the protocol's job before it calls rotate(); here we just cycle the
        // socket under the SAME reconnect ceiling, so a pathological rotation loop still gives up.
        webSocket?.close(NORMAL_CLOSURE, null)
        webSocket = null
        scheduleReconnect()
    }
}
```

`openSocket` uses the protocol's endpoint + headers (the key still touches only the header list the protocol returns — empty for Soniox):

```kotlin
private fun openSocket() {
    val builder = Request.Builder().url(protocol.endpoint)
    protocol.upgradeHeaders(apiKey).forEach { (n, v) -> builder.header(n, v) }
    if (useBetaHeader && protocol.tolerant4xxRetry) builder.header(BETA_HEADER, BETA_VALUE)
    webSocket = factory.newWebSocket(builder.build(), InternalListener())
}
```

`InternalListener` bootstraps via the protocol and dispatches inbound via the protocol (behavior identical for OpenAI):

```kotlin
override fun onOpen(webSocket: WebSocket, response: Response) {
    synchronized(lock) {
        if (closed) return
        reconnectAttempts = 0
        this@RealtimeTransport.webSocket = webSocket
    }
    protocol.bootstrap(apiKey, language).forEach { control.send(it) } // OpenAI: sessionUpdate, once
    listener.onConnected()
}

override fun onMessage(webSocket: WebSocket, text: String) = protocol.onText(text)
override fun onMessage(webSocket: WebSocket, bytes: ByteString) = Unit // no provider sends inbound binary
```

`onFailure` uses `protocol.tolerant4xxRetry` (was the hardcoded OpenAI beta flow) and `protocol.classifyFatal`; the rest of `onFailure`/`scheduleReconnect`/`onClosing`/`onClosed`/`close` is **unchanged**. Replace the two references (`classifyFatal(it)` → `protocol.classifyFatal(it)`; the `!useBetaHeader` guard gains `&& protocol.tolerant4xxRetry`). Add `override fun close()` → `protocol.reset()` after the socket closes.

> **The one reviewed relocation.** Moving the encode behind the seam means two tests that asserted the encode now assert it against `OpenAiRealtimeProtocol` instead of the engine/transport — **expected bytes preserved verbatim**:
> - `LiveTranscriptionEngineTest.sender_upsamples_16k_to_24k_and_base64_encodes_the_append` → `OpenAiRealtimeProtocolTest.append_upsamples_16k_to_24k_and_base64_encodes` (drive raw PCM through `OpenAiRealtimeProtocol.onAppend` with a recording `SessionControl`; assert the SAME `RealtimeEvents.append(<24k-base64>)` JSON).
> - `RealtimeTransportTest.append_and_commit_forwarded_as_correct_events` → rewrite to call `transport.sendAppend(<raw pcm>)`/`sendCommit()` and assert the fake socket received the SAME append+commit JSON (default OpenAI protocol). Meaning intact: "append/commit forwarded correctly."
>
> `RealtimeEventParserTest` (all 10, incl. `outbound_session_update_shape_is_exact`, `append_base64_roundtrips`, `commit_event_shape_is_exact`) is **untouched** — `RealtimeEvents` did not move. The other `RealtimeTransportTest` cases (`sendAppend_refuses_when_over_threshold`, `send_without_a_live_socket_returns_false`) are **param renames** (base64 `String`→`pcm: ByteArray`); their assertions (false over threshold / false with no socket) are unchanged.

### Step 4 — `LiveTranscriptionEngine.kt`: hand raw PCM down

`Transport.sendAppend(base64: String)` → `sendAppend(pcm: ByteArray)`; delete `encodeAppend`, `shortsToBytesLE`, and the `Resampler`/`Base64`/`PcmBytes` imports (now in `OpenAiRealtimeProtocol`). `senderLoop`'s append arm becomes `if (!transport.sendAppend(op.pcm)) markTurnShed()`. `realTransport`'s adapter: `override fun sendAppend(pcm: ByteArray) = rt.sendAppend(pcm)`. **Everything else in the engine — the ledger, `resolveOnce`, `bindItem`, `commit`, backpressure, fatal latch — is byte-identical.** The `LiveTranscriptionEngineTest` fakes change their `sendAppend` param type `String`→`ByteArray` (rename); every correlation/shed/exactly-once assertion is unchanged.

### Step 5 — `RealtimeProtocolContractTest.kt` (new)

Drive `OpenAiRealtimeProtocol` through the seam with a fake `SessionControl` + a recording `RealtimeTransport.Listener`: bootstrap emits exactly one `session.update`; `onText` of each documented OpenAI event dispatches the matching sink call; `classifyFatal(401/403/429/500)` = INVALID_KEY/FORBIDDEN/OUT_OF_CREDIT/null. This is the shared contract every protocol's own suite specializes.

---

## Task 2: `ElevenLabsRealtimeProtocol` — Scribe v2 Realtime (the cleanest mapping)

Verified wire (recon + docs 2026-07-31): `wss://api.elevenlabs.io/v1/speech-to-text/realtime?model_id=scribe_v2_realtime`; `xi-api-key` **header** (bare, from `ProviderCatalog`); PCM16 mono **16 kHz native (no resample)** base64; send `{"message_type":"input_audio_chunk","audio_base_64":…,"commit":<bool>,"sample_rate":16000}`; recv `partial_transcript {text}` → delta, `committed_transcript {text}` → the turn's completion; errors as `input_error`. No item ids → **single in-flight committed turn**, so we synthesize correlation and serialize commits.

### Step 1 — pure codec (Android-free; kotlinx-serialization only)

```kotlin
package com.whispereverywhere.transcription.live

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Pure ElevenLabs Scribe-v2-Realtime codec. Verbatim-doc JSON drives ElevenLabsRealtimeProtocolTest. */
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
```

### Step 2 — the protocol (fold commit onto the last chunk; single-in-flight serialization; timeout→Lost)

```kotlin
package com.whispereverywhere.transcription.live

import com.whispereverywhere.provider.ProviderCatalog
import com.whispereverywhere.provider.ProviderId
import com.whispereverywhere.transcription.cloud.FatalKind
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong

/**
 * ElevenLabs Scribe v2 Realtime. No item ids and one committed turn at a time, so this adapter
 * SYNTHESIZES the correlation the engine's FIFO bind expects:
 *  - it emits onCommitted(syntheticId) at the moment it sends the commit-flagged chunk (binds the
 *    engine's oldest unbound seq, exactly as the OpenAI commit ack does), and
 *  - onCompleted(syntheticId, text) when committed_transcript lands.
 * commit:true rides the LAST audio chunk (not a separate frame), so the adapter holds the most recent
 * chunk back by one: a following append flushes it commit:false; onCommit flushes it commit:true.
 * Because only one commit may be outstanding, a commit that arrives while one is in flight is DEFERRED
 * until the prior committed_transcript (or its timeout) resolves — the serialization lives HERE, not
 * in the engine. A timeout resolves the held turn via onTranscriptionFailed → the engine's Lost path,
 * so a dropped commit is rescued locally, never stranded.
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

    // All held state guarded by `gate`.
    private val gate = Any()
    private var heldChunk: ByteArray? = null      // the not-yet-flushed most-recent append (fold slot)
    private var inFlightId: String? = null        // the synthetic id of the commit awaiting a transcript
    private var commitDeferred = false            // a commit requested while one was in flight
    private var timeoutToken = 0L                 // invalidates a stale timeout after resolution

    override fun upgradeHeaders(apiKey: String): List<Pair<String, String>> {
        val p = ProviderCatalog.byId(ProviderId.ELEVENLABS) // xi-api-key, bare value
        return listOf(p.authHeaderName to p.authHeaderValue(apiKey))
    }

    override fun bind(control: SessionControl, sink: RealtimeTransport.Listener) {
        this.control = control; this.sink = sink
    }

    override fun bootstrap(apiKey: String, language: String?): List<Frame> = emptyList() // no config frame

    override fun onAppend(pcm16k: ByteArray): Boolean = synchronized(gate) {
        val toFlush = heldChunk
        heldChunk = pcm16k                        // hold the newest; flush the previous (fold slot)
        toFlush?.let { flush(it, commit = false) } ?: true
    }

    override fun onCommit(): Boolean = synchronized(gate) {
        if (inFlightId != null) { commitDeferred = true; return true } // serialize: wait for the prior
        sendCommitNow()
    }

    private fun sendCommitNow(): Boolean {           // caller holds gate
        val id = ids.incrementAndGet().toString()
        inFlightId = id
        sink.onCommitted(id)                         // bind the engine's oldest unbound seq NOW
        val last = heldChunk; heldChunk = null
        val ok = if (last != null) flush(last, commit = true)
                 else control.send(Frame.Text(ElevenLabsEvents.audioChunk("", commit = true)))
        armTimeout(id)
        return ok
    }

    private fun flush(pcm: ByteArray, commit: Boolean): Boolean {
        val b64 = Base64.getEncoder().encodeToString(pcm)   // 16 k native — NO resample
        return control.send(Frame.Text(ElevenLabsEvents.audioChunk(b64, commit)))
    }

    private fun armTimeout(id: String) {             // caller holds gate
        val token = ++timeoutToken
        scheduler.schedule(commitTimeoutMs) {
            val fire = synchronized(gate) { token == timeoutToken && inFlightId == id }
            if (fire) { sink.onTranscriptionFailed(id); resolveInFlight() } // held turn → Lost
        }
    }

    private fun resolveInFlight() = synchronized(gate) {
        inFlightId = null
        timeoutToken++                               // invalidate any pending timeout
        if (commitDeferred) { commitDeferred = false; sendCommitNow() }
    }

    override fun onText(text: String) {
        when (val e = ElevenLabsEvents.parse(text)) {
            is ElevenLabsEvents.In.Partial -> sink.onDelta("", e.text) // preview only; id irrelevant
            is ElevenLabsEvents.In.Committed -> {
                val id = synchronized(gate) { inFlightId } ?: return
                sink.onCompleted(id, e.text)         // resolve the bound seq exactly-once (engine path)
                resolveInFlight()
            }
            is ElevenLabsEvents.In.Error ->
                sink.onErrorEvent("input_error", e.message.length) // length only — never content
            null -> Unit
        }
    }

    override fun classifyFatal(code: Int): FatalKind? = when (code) {
        401, 403 -> FatalKind.INVALID_KEY
        429 -> FatalKind.OUT_OF_CREDIT
        else -> null
    }

    override fun reset() = synchronized(gate) {
        heldChunk = null; inFlightId = null; commitDeferred = false; timeoutToken++
    }

    companion object {
        const val ENDPOINT =
            "wss://api.elevenlabs.io/v1/speech-to-text/realtime?model_id=scribe_v2_realtime"
        const val DEFAULT_COMMIT_TIMEOUT_MS = 8_000L // committed_transcript lands well within this
    }
}
```

### Step 3 — tests (`ElevenLabsRealtimeProtocolTest.kt`, new) — VERBATIM doc JSON through the real parser

- `partial_transcript` JSON → `sink.onDelta` with the exact text, and **never** binds/completes.
- append→append→commit: the fake `SessionControl` records exactly two `commit:false` chunks then one `commit:true` chunk; a synthetic `onCommitted` fires the instant the commit chunk is sent (before the transcript).
- `committed_transcript` JSON → `onCompleted(sameId, text)`, resolving exactly once.
- **serialization:** a second `onCommit` while one is in flight sends **no** frame until the first `committed_transcript` arrives, then flushes the deferred commit.
- **timeout→Lost:** with a synchronous fake `ReconnectScheduler`, a missing `committed_transcript` fires `onTranscriptionFailed(id)` and frees the in-flight slot for the next commit.
- `input_error` JSON → `onErrorEvent("input_error", len)`; assert the message text is **absent** from the callback (length only).
- `classifyFatal` 401/403/429 mapping.

---

## Task 3: `SonioxRealtimeProtocol` — stt-rt-v5, config-first key under the no-log discipline

Verified wire: `wss://stt-rt.soniox.com/transcribe-websocket`, model **stt-rt-v5**; **api_key in the FIRST CONFIG MESSAGE** (documented, over TLS); config `{"api_key":…,"model":"stt-rt-v5","audio_format":"s16le","sample_rate":16000,"num_channels":1[,"language_hints":[…]]}`; **RAW s16le binary** frames @16 k (no resample, no base64); recv `{"tokens":[{"text","is_final",…}], "finished":bool}` — non-final → delta preview, final → accumulate → VAD close assembles the turn; empty frame → `finished:true` then close; numeric errors 400/401/402/403/408/413/429/5xx.

### Step 1 — pure codec + the key-bearing config as a PURE FUNCTION that retains nothing

```kotlin
package com.whispereverywhere.transcription.live

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Pure Soniox stt-rt-v5 codec. [config] is a PURE FUNCTION of (apiKey, language): its String output
 * is the ONLY object that ever holds the key — it is handed straight to the transport and never
 * stored, logged, toString'd, or placed in an exception. Verbatim-doc JSON drives the tests.
 */
object SonioxEvents {
    private val OUT = Json { encodeDefaults = true; explicitNulls = false }
    private val IN = Json { ignoreUnknownKeys = true }

    fun config(apiKey: String, language: String?): String =
        OUT.encodeToString(Config(apiKey = apiKey, languageHints = language?.let { listOf(it) }))

    data class Token(val text: String, val isFinal: Boolean)
    data class Result(val tokens: List<Token>, val finished: Boolean, val errorCode: Int?, val errorLen: Int)

    fun parse(json: String): Result? {
        val o = try { IN.parseToJsonElement(json) as? JsonObject } catch (_: Throwable) { null } ?: return null
        val errCode = (o["error_code"] as? JsonPrimitive)?.intOrNull
        val errLen = ((o["error_message"] as? JsonPrimitive)?.contentOrNull).orEmpty().length
        val toks = (o["tokens"] as? JsonArray).orEmptyTokens()
        val finished = (o["finished"] as? JsonPrimitive)?.booleanOrNull ?: false
        if (toks.isEmpty() && !finished && errCode == null) return null
        return Result(toks, finished, errCode, errLen)
    }

    private fun JsonArray?.orEmptyTokens(): List<Token> = this.orEmpty().mapNotNull {
        val t = it as? JsonObject ?: return@mapNotNull null
        val text = (t["text"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
        Token(text, (t["is_final"] as? JsonPrimitive)?.booleanOrNull ?: false)
    }

    @Serializable
    private data class Config(
        @SerialName("api_key") val apiKey: String,
        val model: String = "stt-rt-v5",
        @SerialName("audio_format") val audioFormat: String = "s16le",
        @SerialName("sample_rate") val sampleRate: Int = 16000,
        @SerialName("num_channels") val numChannels: Int = 1,
        @SerialName("language_hints") val languageHints: List<String>? = null,
    )
}
```

### Step 2 — the protocol (binary audio; client-assembled turn; error map; rotation; NO stored key)

```kotlin
package com.whispereverywhere.transcription.live

import com.whispereverywhere.transcription.cloud.FatalKind
import okio.ByteString.Companion.toByteString
import java.util.concurrent.atomic.AtomicLong

/**
 * Soniox stt-rt-v5. The key never becomes a field of this object: it arrives per open via
 * [bootstrap] and is consumed by SonioxEvents.config, so [reset]/toString/any exception path have
 * nothing to leak (SonioxNoLogDisciplineTest attacks exactly this). Audio is RAW s16le BINARY at
 * 16 kHz — no resample, no base64. There is no server turn: non-final tokens are the live preview,
 * final tokens accumulate, and at VAD close the adapter assembles the turn transcript from the
 * finals and emits onCompleted for the just-cut seq (the engine's oldest-unbound fallback bind).
 *
 * ASYNC GRACE WINDOW (deviation from the earlier synchronous sketch — see the self-review). At the
 * instant VAD cuts a turn the last spoken words are still NON-FINAL (Soniox finalizes them a beat
 * later as the stream confirms them). Assembling the finals synchronously in [onCommit] would drop
 * those trailing words AND misattribute them to the NEXT turn once they finalize. So [onCommit]
 * arms a short grace timer ([DEFAULT_GRACE_MS]) on the injected scheduler; finals landing during the
 * window keep accumulating for the just-cut turn, and the turn resolves when the timer fires — or
 * early, when an inbound `finished:true` flushes it. This mirrors ElevenLabs' async deferred-commit
 * on the same scheduler. Edge cases the engine's exactly-once ledger relies on: a ZERO-FINAL cut
 * resolves via onCompleted(id, "") (the engine's empty-completion outcome, NOT onTranscriptionFailed);
 * a SECOND commit inside the window finalizes the prior turn FIRST, in commit order, off-thread.
 */
class SonioxRealtimeProtocol(
    private val scheduler: ReconnectScheduler,
    private val graceMs: Long = DEFAULT_GRACE_MS, // how long to keep accumulating trailing finals
) : RealtimeProtocol {

    override val endpoint = ENDPOINT
    override val tolerant4xxRetry = false

    private lateinit var control: SessionControl
    private lateinit var sink: RealtimeTransport.Listener
    private val ids = AtomicLong(0)

    private val gate = Any()
    private val finals = StringBuilder()   // accumulated final-token text for the CURRENT turn
    private var lastPreview = ""           // last non-final preview (for onDelta)
    private var pending: Pending? = null   // a VAD-cut turn inside its grace window
    private var graceCounter = 0L          // one unique token per armed window (staleness guard)
    private var sessionExpiredRetries = 0  // 403 → reconnect once, then fatal

    private class Pending(val id: String, val token: Long)
    private class Completion(val id: String, val text: String)

    override fun upgradeHeaders(apiKey: String) = emptyList<Pair<String, String>>() // key rides config

    override fun bind(control: SessionControl, sink: RealtimeTransport.Listener) {
        this.control = control; this.sink = sink
    }

    /** The ONLY carrier of the key. Built, returned, sent — never retained. No log line here, ever.
     *  Also the per-open reset of TURN state (finals/preview/pending) — a still-armed grace timer is
     *  superseded via its now-stale token — but NOT sessionExpiredRetries (see the note below). */
    override fun bootstrap(apiKey: String, language: String?): List<Frame> {
        synchronized(gate) { finals.setLength(0); lastPreview = ""; pending = null }
        return listOf(Frame.Text(SonioxEvents.config(apiKey, language)))
    }

    override fun onAppend(pcm16k: ByteArray): Boolean =
        control.send(Frame.Binary(pcm16k.toByteString())) // raw s16le binary frame

    /** VAD close: arm the grace window for the just-cut turn. If a prior turn is still in its window
     *  (two cuts within [graceMs]) it is finalized FIRST, off-thread (delay 0), in commit order, so no
     *  sink callback runs under the transport's send lock. Nothing is sent; the turn resolves async. */
    override fun onCommit(): Boolean {
        val prior: Completion?; val id: String; val token: Long
        synchronized(gate) {
            prior = closePendingLocked() // finalize any in-grace turn before opening the next
            id = ids.incrementAndGet().toString(); token = ++graceCounter
            pending = Pending(id, token)
        }
        prior?.let { c -> scheduler.schedule(0L) { fire(c) } } // in commit order, before the new grace
        scheduler.schedule(graceMs) { onGrace(id, token) }
        return true
    }

    override fun onText(text: String) {
        val r = SonioxEvents.parse(text) ?: return
        r.errorCode?.let { return handleError(it, r.errorLen) }
        var preview = ""; var flushed: Completion? = null
        synchronized(gate) {
            val nonFinal = StringBuilder()
            for (t in r.tokens) if (t.isFinal) finals.append(t.text) else nonFinal.append(t.text)
            lastPreview = finals.toString() + nonFinal.toString(); preview = lastPreview
            if (r.finished) flushed = closePendingLocked() // end-of-stream: flush the in-grace turn early
        }
        sink.onDelta("", preview) // preview strip only — never injected
        flushed?.let { fire(it) }
    }

    /** Grace timer: assemble the accumulated finals for [id] and resolve its seq, unless superseded. */
    private fun onGrace(id: String, token: Long) {
        val completion = synchronized(gate) {
            val p = pending
            if (p == null || p.token != token) return // torn down / superseded by a later commit
            closePendingLocked()
        } ?: return
        fire(completion)
    }

    /** Caller holds [gate]: snapshot the in-grace turn's finals, clear the accumulator, return it. A
     *  zero-final turn returns Completion(id, "") — resolved empty, never a hard transcription failure. */
    private fun closePendingLocked(): Completion? {
        val p = pending ?: return null
        pending = null
        val text = finals.toString(); finals.setLength(0); lastPreview = ""
        return Completion(p.id, text)
    }

    /** Resolve the just-cut seq exactly-once via the engine's oldest-unbound fallback bind. Never gated. */
    private fun fire(c: Completion) = sink.onCompleted(c.id, c.text)

    /** Numeric error map. Content never crosses — length only. Session-scoped 403/413 use the shell. */
    private fun handleError(code: Int, len: Int) {
        sink.onErrorEvent(code.toString(), len)
        when (code) {
            401 -> sink.onFatal(FatalKind.INVALID_KEY, code)
            402 -> sink.onFatal(FatalKind.OUT_OF_CREDIT, code)
            403 -> if (sessionExpiredRetries++ == 0) control.rotate() // reconnect once…
                    else sink.onFatal(FatalKind.FORBIDDEN, code)       // …then fatal
            413 -> { control.send(Frame.Binary(ByteArray(0).toByteString())); control.rotate() } // finalize + rotate
            429, in 500..599 -> Unit // transient: the socket drop/close drives reconnect (existing path)
            else -> Unit             // 400/408 and unknowns: benign, forward-compatible
        }
    }

    override fun reset() = synchronized(gate) {
        finals.setLength(0); lastPreview = ""; pending = null; sessionExpiredRetries = 0
    }

    companion object {
        const val ENDPOINT = "wss://stt-rt.soniox.com/transcribe-websocket"
        /** Trailing finals almost always land inside this after a VAD cut on a continuous stream. */
        const val DEFAULT_GRACE_MS = 600L
    }
}
```

> `sessionExpiredRetries` is cleared ONLY in `reset()` (on session close), deliberately NOT in `bootstrap`: a successful reopen resets the transport's OWN reconnect counter, so clearing the 403 counter per open would let inband 403s rotate forever. `bootstrap` DOES clear turn state (finals/preview/pending) so a reconnect or 413/403 rotation starts clean and no pre-rotation turn bleeds across; a still-armed grace timer is superseded by the now-stale `pending` token. 413 rotation and 403 retry both ride `scheduleReconnect` and therefore respect `maxReconnects` — a stuck session gives up instead of rotating forever.

### Step 3 — `SonioxRealtimeProtocolTest.kt` + `SonioxNoLogDisciplineTest.kt` (new)

Protocol suite (verbatim doc JSON):
- config bootstrap shape is exact (model/audio_format/sample_rate/num_channels; `language_hints` present only when a language is given, absent otherwise).
- `onAppend` sends a **binary** frame whose bytes equal the input PCM (no base64, no resample).
- a `tokens` message with mixed final/non-final → `onDelta` preview = accumulated finals + current non-finals; finals persist, non-finals do not.
- `onCommit` arms ONE grace timer (`DEFAULT_GRACE_MS`) and resolves nothing yet; when the timer fires (via the manual scheduler) it emits `onCompleted(id, <assembled finals>)` and clears the accumulator, so a second turn starts empty with a fresh id.
- grace window captures a LATE final: a token still non-final at the cut that finalizes DURING the window belongs to the just-cut turn, not the next.
- a ZERO-FINAL cut resolves `onCompleted(id, "")` (empty completion), NOT `onTranscriptionFailed` — never a hard loss.
- `finished:true` flushes the in-grace turn immediately; the later grace timer is then a no-op (superseded token).
- a SECOND commit inside the window finalizes the prior turn first, in commit order (both seqs resolve exactly once).
- error map: 401→INVALID_KEY fatal, 402→OUT_OF_CREDIT fatal, 403 first→`control.rotate()` then second→FORBIDDEN fatal, 413→empty binary finalize + rotate, 429/5xx→no fatal; `onErrorEvent` carries the code + **length only**. `bootstrap` does NOT reset the 403 counter (rotation stays bounded); `reset` does (next session gets its own single rotation).

No-log discipline suite (the audit target):
- `SonioxEvents.config(KEY, null)` **contains** KEY (it must, to send it) — but the sent `Frame.Text` is the ONLY object that does.
- `protocol.toString()` contains no part of KEY; after `bootstrap(KEY, …)` and `reset()`, reflection over the instance's declared fields finds **no** field holding KEY (the class has no `apiKey` field by construction).
- driving a full session (bootstrap → tokens → error) through a `SessionControl`/`Listener` that record every string they receive proves KEY appears **only** in the config frame and never in any `onErrorEvent`/`onDelta`/`onCompleted`/`onFatal` argument.

---

## Task 4: Selection widening — per-provider rows, chip, service construction, prefs

### Step 1 — `ProviderCatalog.kt`: Soniox streams now

Flip SONIOX `supportsStreaming = false → true` and update its KDoc ("v1 async is the segment path; **realtime stt-rt-v5 ships this wave** behind `SonioxRealtimeProtocol`"). OpenAI/ElevenLabs are already `true`; Gemini stays `false` (no client-usable realtime path).

### Step 2 — `FloatingBubbleService.kt`: the realtime set drives CLOUD_LIVE

```kotlin
/** STT providers with a shipped BYOK realtime adapter — the CLOUD_LIVE gate. Gemini is absent (its
 *  Live API needs backend-minted ephemeral tokens this app has no server for). Kept in lockstep with
 *  ModeDashboard.dictationLiveActive and the CLOUD_LIVE construction below. */
internal val REALTIME_STT_PROVIDERS: Set<ProviderId> =
    ProviderCatalog.all.filter { it.supportsStreaming }.map { it.id }.toSet()

internal fun isRealtimeStt(sttProviderIdName: String?): Boolean =
    resolveSttProvider(sttProviderIdName)?.let { it in REALTIME_STT_PROVIDERS } == true
```

`decideEngineChoice` line 96 widens from the OpenAI literal to the set:

```kotlin
liveMode && isRealtimeStt(sttProviderId) -> EngineChoice.CLOUD_LIVE
```

Flip the decision KDoc (`:80-85`): "Live is realtime-capable-provider-only (OpenAI, ElevenLabs, Soniox); Gemini has no client realtime path so its flag is inert — the batch path is byte-unchanged for every non-realtime provider."

CLOUD_LIVE construction (`:1641-1671`) selects the protocol; the `key` source, `lastLiveEngine`, `sessionIsLive`, and the `FallbackTranscriptionEngine(cloud, local)` + `SourceRoutedTranscriptionEngine(mic, device=local)` wrapping stay **byte-identical**:

```kotlin
EngineChoice.CLOUD_LIVE -> {
    val liveProviderId = requireNotNull(resolveSttProvider(prefs.sttProviderId))
    val protocol = when (liveProviderId) {
        ProviderId.OPENAI     -> OpenAiRealtimeProtocol()
        ProviderId.ELEVENLABS -> ElevenLabsRealtimeProtocol(sharedLiveReconnectScheduler())
        ProviderId.SONIOX     -> SonioxRealtimeProtocol(sharedLiveReconnectScheduler())
        else -> error("non-realtime provider reached CLOUD_LIVE") // decideEngineChoice forbids it
    }
    val cloud = LiveTranscriptionEngine(
        apiKey = requireNotNull(key),   // OpenAI/EL: header; Soniox: consumed by config, never stored
        scope = serviceScope,
        makeTransport = { l ->
            LiveTranscriptionEngine.realTransport(
                RealtimeTransport(sharedLiveWsFactory(), sharedLiveReconnectScheduler(), l, protocol)
            )
        },
    )
    lastLiveEngine = cloud
    sessionIsLive = true
    SourceRoutedTranscriptionEngine(
        micEngine = FallbackTranscriptionEngine(cloud, local, serviceScope),
        deviceEngine = local,
    ).also { sourceRouter = it }
}
```

Flip the CLOUD_LIVE comment: the mic audio / v3 disclosure / SourceRouted-device-unreachable guarantees are unchanged; only the transport protocol and cost tier vary by provider.

### Step 3 — `CloudProvidersScreen.kt`: per-provider live row + price

```kotlin
internal fun liveModeRowVisible(
    selectedProviderId: String?, configured: Set<ProviderId>, disclosureAccepted: Boolean,
): Boolean {
    val id = selectedProviderId?.let { runCatching { ProviderId.valueOf(it) }.getOrNull() } ?: return false
    return disclosureAccepted && id in configured && ProviderCatalog.byId(id).supportsStreaming
}

/** Per-provider live label: the mode name + that provider's "about" price. NO speed word for ANY
 *  provider (Soniox included — it is the cheapest, never "fastest"). Prices pinned 2026-07-31. */
internal fun liveModeLabel(providerId: ProviderId): String {
    val price = when (providerId) {
        ProviderId.OPENAI     -> "about \$0.017/min"
        ProviderId.ELEVENLABS -> "about \$0.007/min"
        ProviderId.SONIOX     -> "about \$0.002/min"
        ProviderId.GEMINI     -> error("Gemini has no live row") // never streaming-capable
    }
    return "Cloud word-for-word (${ProviderCatalog.byId(providerId).displayName}) · $price"
}
```

`liveModeCaption()` stays generic ("Transcribes word-for-word as you speak."). Update `liveModeRowVisible`/`liveModeLabel` KDoc: OpenAI is no longer "the only provider that streams"; Gemini's row shows **segment mode only, with no apology copy** (its absence from `supportsStreaming` is the whole mechanism — do not add "Gemini doesn't support word-for-word" text).

### Step 4 — `EnginesAndVoicesScreen.kt`: thread the provider through

`LiveModeRow` takes the selected `ProviderId`; its `Text(liveModeLabel(providerId))`. The call site resolves the selected provider (already `selectedProvider`) and passes `selectedProvider.id`. Flip the `:657` comment to "offered for any realtime-capable selected provider (OpenAI, ElevenLabs, Soniox)".

### Step 5 — `ModeDashboard.kt`: chip valve in lockstep

`dictationLiveActive` widens to mirror `decideEngineChoice` exactly:

```kotlin
internal fun dictationLiveActive(sttProviderIdName: String?, sttLiveMode: Boolean): Boolean =
    sttLiveMode && com.whispereverywhere.service.isRealtimeStt(sttProviderIdName)
```

`dictationChip` is **already** provider-generic (`"$base · word-for-word"` off `engineDisplayName`), so "ElevenLabs · word-for-word" / "Soniox · word-for-word" render correctly with no edit. Flip the KDoc (both funcs): word-for-word is realtime-capable-provider-only, not OpenAI-only; a stale `sttLiveMode` after a switch to **Gemini** (non-realtime) correctly shows no suffix because `isRealtimeStt(Gemini) == false` — the exact case `dictation_chip_no_word_for_word_on_stale_live_after_switch_to_gemini` pins.

### Step 6 — prefs: no new key

`EngineChoice` needs **no new value** (CLOUD_LIVE stays; provider varies). `sttLiveMode` is unchanged — it is inert on non-realtime providers via `isRealtimeStt`, exactly as it was inert on non-OpenAI before. No migration.

### Step 7 — widen the pinned-OpenAI tests (flagged assertion changes, per recon)

- `EngineSelectionTest`: `live_flag_with_openai_and_key_and_net_gives_CLOUD_LIVE` keeps; **add** ElevenLabs + Soniox → CLOUD_LIVE with key+net+live; the "non-OpenAI never CLOUD_LIVE" loop **narrows** to "non-**realtime** (Gemini) never CLOUD_LIVE".
- `CloudProvidersScreenLogicTest.live_mode_copy_says_word_for_word_and_never_claims_speed`: assert per-provider for all three that the label has no speed word and carries the right "about $" price; `liveModeRowVisible` lights for each streaming-capable selected+configured provider and stays dark for Gemini.
- `ModeDashboardLogicTest`: `dictation_word_for_word_appends_only_for_cloud` extends to EL/Soniox; the stale-live-after-switch-to-Gemini test still passes (Gemini not realtime).

These are the recon-anticipated widenings — flag each as an intentional assertion change, not a silent one.

---

## Task 5: Docs — ledger + flip the "live is OpenAI-only" inventory

Grep inventory to flip (run `grep -rin "openai" app/src/main docs | grep -i "live\|word-for-word\|stream"` and clear each live-mode OpenAI-only claim):
- `RealtimeTransport.kt` class KDoc ("the OpenAI Realtime transcription session") → "the realtime transcription session; the per-provider wire lives in `RealtimeProtocol`".
- `FloatingBubbleService` decision + CLOUD_LIVE KDoc (Task 4 Step 2).
- `CloudProvidersScreen`/`EnginesAndVoicesScreen`/`ModeDashboard` KDoc (Task 4 Steps 3–5).
- `docs/PLAY-DECLARATIONS.md`: flip the "live word-for-word is OpenAI-only" line to "OpenAI, ElevenLabs, Soniox". **Confirm** (recon ✓) the §5/§6 recipient narrative already names all four providers as audio recipients for dictation, so no recipient-list change is needed — live now uses the same recipients the batch/segment paths already declare. Add the ledger entry:

```
**Release ledger — Realtime all-providers (2026-07-31):** live word-for-word widened from OpenAI-only
to every streaming-capable BYOK provider (OpenAI, ElevenLabs, Soniox) via a per-provider
RealtimeProtocol seam; OpenAI's wire is byte-identical (regression contract held). ElevenLabs =
xi-api-key header + 16 kHz base64 + commit-on-last-chunk with single-in-flight serialization; Soniox =
config-message key under the no-log discipline + raw s16le binary + client-assembled turns +
finalize/rotate under the reconnect ceiling. Gemini stays segment-only (no client realtime path), no
apology copy. Deltas never inject; mic-only via SourceRouted; Fallback(live, local) preserved. No new
recipient, no disclosure-version change (same audio-to-a-provider meaning under v3). Per-provider
"about" prices: OpenAI $0.017/min, ElevenLabs $0.007/min, Soniox $0.002/min. No speed claims.
```

Historical plan docs (`2026-07-30-c4-live-transcribe.md`, `2026-07-30-soniox-provider.md`) carry dated "live is OpenAI-only"/"realtime is a follow-up" caveats that were TRUE when written — do **not** rewrite history; append a one-line pointer at each: `> Superseded 2026-07-31 by docs/superpowers/plans/2026-07-31-realtime-all-providers.md (realtime now all streaming-capable providers).`

---

## Self-review (inline)

- **Regression contract held.** `RealtimeEvents`/`RealtimeEventParser` are untouched → all 10 parser tests green with zero edits, incl. `outbound_session_update_shape_is_exact`. `OpenAiRealtimeProtocol` reuses them and reproduces the 24 k+base64 append byte-for-byte. The ONLY relocations are the two encode-asserting tests (bytes preserved verbatim); every other live test is imports/param-renames with meaning intact. The default `protocol = OpenAiRealtimeProtocol()` keeps the 3-arg `RealtimeTransport` ctor and every existing call site compiling.
- **Deltas never inject; exactly-once intact.** All three protocols emit `onDelta` for preview and route completions through the engine's unchanged `onCommitted`/`onCompleted`/`onTranscriptionFailed` → `resolveOnce`. EL synthesizes the committed ack; Soniox assembles the turn through an ASYNC GRACE WINDOW (`DEFAULT_GRACE_MS`) on the injected scheduler — trailing finals that land just after a VAD cut are captured for the just-cut turn rather than dropped or misattributed to the next; the timer fires (or `finished:true` flushes early) and resolves via the engine's oldest-unbound fallback bind, in commit order, with a zero-final cut resolving empty (not failed). This is a correctness deviation from the earlier synchronous sketch, pinned by the grace/late-final/zero-final/finished-flush/second-commit tests. The engine ledger is not edited.
- **Soniox no-log discipline.** The key is never a field — it flows through `bootstrap(apiKey,…)` per open and is consumed by the pure `SonioxEvents.config`. `reset`/`toString`/exceptions have nothing to leak; `SonioxNoLogDisciplineTest` proves the config frame is the sole carrier and reflection finds no key-holding field. `onErrorEvent` carries code + length only, never content — same discipline as OpenAI's `Inbound.Error`.
- **Capture thread never blocks; backpressure once.** `sendAppend` still returns fast; framing happens in the protocol on the sender coroutine; `SessionControl.send` enforces the single `queueSize` threshold for text AND binary. A false return sheds the turn to local via the existing path. EL's fold holds at most one chunk; Soniox streams binary with no buffering.
- **Reconnect/rotation under the ceiling.** 403-once and 413-rotate both ride `scheduleReconnect`, so `maxReconnects` still bounds a pathological session; the empty-frame finalize precedes rotation. EL's commit timeout uses the injected scheduler and resolves the held turn Lost, never stranding a seq.
- **Copy + pricing.** Per-provider "about" prices, no speed word anywhere (Soniox explicitly). Gemini shows segment-only with no apology copy — its absence from `supportsStreaming` is the entire mechanism.
- **Untouched:** batch/TTS/segment-STT engines, the four batch adapters, Fallback/SourceRouted wrapping, prefs schema (`EngineChoice` unchanged, no migration).
- **No new deps; OkHttp pinned** (binary send already in 4.12.0); `assembleRelease` gated both sides (R8 sees a new sealed `Frame` + three protocol classes — keep them non-obfuscation-sensitive; they are plain Kotlin).
- **Commit hygiene:** only the named files; never `git add -A`; retry once on `index.lock`.

## Verification gate (assert fresh — evidence before claims)

- [ ] `$env:JAVA_HOME=…; .\gradlew.bat --no-daemon testDebugUnitTest` green; record the fresh count (must exceed 708 by the new suites; the 51 live tests still pass, the two encode pins relocated).
- [ ] `.\gradlew.bat --no-daemon assembleDebug assembleRelease` both green (R8).
- [ ] `grep -rin "openai-only\|only.*openai" app/src/main docs` shows no LIVE constraint (only superseded-pointers / genuinely OpenAI-specific header/beta code).
- [ ] Soniox no-log suite green; confirm no `Log.*` call in any `bootstrap`/config path carries the key.
- [ ] Commit ONLY the files named in File Structure. Message: realtime all-providers (OpenAI unchanged + ElevenLabs + Soniox), the RealtimeProtocol seam, per-provider prices; no speed claims. Re-cut 3.x at wave end.
