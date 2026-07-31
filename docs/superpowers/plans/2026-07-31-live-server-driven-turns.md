# Live word-for-word AS SPOKEN — server-driven turns for the open-mic modes

> **For agentic workers:** REQUIRED SUB-SKILL: use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax. Assert the fresh green suite count at the gate — do not hardcode it here.

**Goal:** the mic button's live modes (OpenAI / ElevenLabs / Soniox) do NOT stream word-for-word during speech in the field. Deltas appear only when the speaker pauses. The root cause is architectural: **live sessions run the SAME client-side VAD that batch runs, and cut turns with a client `commit()`.** For an open WebSocket that is the wrong contract — the socket is already open and the provider can segment on its own server VAD, streaming partials as the words are spoken. This wave **inverts the live turn model to server-driven**: the provider's own segmentation creates turns, a SERVER event allocates the seq and rotates the fallback mirror, client VAD commits and the wall-clock cap are DISABLED for live sessions, and deltas visibly stream on the existing preview strip before any turn completes. **Local + Gemini + batch keep client VAD EXACTLY as today, byte-identical, pinned.**

**The owner's directive (verbatim intent):** *"For the WebSocket connections, those are open connections, and we can just have the mic open for those"* — deltas must visibly stream word-for-word AS SPOKEN. *"For non-WebSocket or RTC models, we have to stick with the batch approach using the VAD"* — local, Gemini segment mode, and batch keep client VAD exactly as today.

## Verified server-segmentation facts (fetched live 2026-07-31 — pinned in the recon)

| | Server segments live? | Delta surface | Server turn-end signal | Client commit in live mode |
|---|---|---|---|---|
| **OpenAI** | Yes — `turn_detection: server_vad` (must switch OFF `null`) | transcription `.delta` | `input_audio_buffer.committed` (carries `item_id`) → `.completed` | **REMOVE** — `.append` only |
| **ElevenLabs** | Yes — `commit_strategy=vad` | `partial_transcript` | `committed_transcript` (carries final text) | omit `commit:true` |
| **Soniox** | Yes — `enable_endpoint_detection:true` | `is_final:false` tokens | `<end>` token (always final, once per segment) | none exists |

All three verified providers segment server-side, so **none needs the client-VAD live fallback** — see the "per-provider fallback" note under Absolute constraints. The structural blocker was **OpenAI's `turn_detection:null`** (the server creates no item, so it can emit no delta, until our commit); ElevenLabs/Soniox already reach `onDelta` (recon §2) but were cut by client VAD, so partials only clustered around pauses. Switching each to its server segmentation makes partials stream throughout AND moves the turn boundary to the server.

## Global constraints (carried, still binding)
- **`java` NOT on PATH.** PowerShell: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio1\jbr"`; `.\gradlew.bat --no-daemon`. `assembleRelease`/`bundleRelease` (R8) green both sides.
- **NEVER `connectedAndroidTest` or `installDebug`.** Instrumented = compile-check only. No new dependencies; OkHttp pinned.
- `unitTests.isReturnDefaultValues = true`; **`org.json` BANNED**, **`android.util.Base64` BANNED** — kotlinx.serialization / `java.util.Base64` only. The pure protocol codecs stay Android-free.
- **Commit ONLY named files, never `git add -A`. Retry once on `index.lock`. Branch `main`.**
- **Baseline HEAD `8d1481d`, 822 tests / 0 failures, 3.3.0/73.** Assert the green count FRESH at the gate; it grows by the new suites, and the re-baselined live suites move but never shrink.
- **No credential/content logging.** Soniox config no-log discipline intact. **No speed claims** in user copy — including Soniox: never "fastest". Every price says "about".

## Absolute constraints (the acceptance contract)
- **Server-driven turns in live mode.** The provider's own segmentation creates turns; the engine allocates seqs from SERVER turn events; client VAD commits and the wall-clock cap are DISABLED for live sessions (the stop button / session end remains the outer bound). **Local + Gemini + batch paths: UNTOUCHED** — their VAD/wall-cap behavior byte-identical, pinned by `LiveTurnPolicyTest`.
- **Exactly-once survives the inversion.** Every server-created turn resolves exactly once through the existing ledger/orderer; a WS drop mid-turn still rescues locally via the fallback mirror — and **the mirror's cut points now follow SERVER turn boundaries** (the committed / `committed_transcript` / `<end>` event, and a drop, rotate the mirror). This is the highest-risk change; Task 1 re-derives it and `LiveServerDrivenTurnTest` pins the drop-mid-turn rescue.
- **Deltas visibly stream** on the existing strip during live TEXT_FIELD sessions, pre-turn-end, for all three providers — pinned by tests driving verbatim server events through the real parsers and asserting the listener sees deltas BEFORE any completion.
- **Deltas still NEVER injected** — the strip only; completions inject via the orderer, unchanged.
- **Cost honesty.** Continuous streaming bills the whole open-mic time including silence — the live rows' copy says **"billed per minute while the mic is open"** (still no speed claim).
- **Per-provider fallback (documented, not built).** If a provider genuinely lacked server segmentation, THAT provider would keep client-VAD commits (deltas still streaming) via `serverDriven=false` on its protocol + engine — the machinery this wave removes would be retained for it. All three verified providers segment server-side, so implementing an unused client path would be dead code; the `serverDriven` seam is kept so the fallback is a construction flag, not a rewrite.

---

## Architecture — the inversion, and why the mirror is the crux

The recon proved the delta surface is already wired end-to-end (`onDelta` bypasses the correlation ledger entirely — `LiveTranscriptionEngine.kt:285`, `FallbackTranscriptionEngine.kt:205`, `SourceRoutedTranscriptionEngine.kt:136`, `FloatingBubbleService.kt:1841`). What is wrong is **who cuts the turn**. Today:

```
service client VAD ──► engine.commit()  [= FallbackTranscriptionEngine.commit()]
                         └─ under mirrorLock: snapshot mirror + cloud.commit() (allocate seq) + retain PCM[seq]
```

The load-bearing invariant is **"the seq names exactly the bytes in the snapshot"** — `FallbackTranscriptionEngine.commit()` (`:187`) snapshots the mirror and calls `cloud.commit()` (which allocates the seq under `bufferLock`) as ONE operation under `mirrorLock`. The exactly-once ledger and the retain-per-seq rescue both key off that single `commit()` call.

To invert without breaking that pairing, **the server turn event must trigger the exact same `commit()`, from inside the engine instead of from the service.** The server event arrives on the transport callback thread, inside `LiveTranscriptionEngine`, which is *wrapped by* `FallbackTranscriptionEngine`. So the engine calls "up" through an injected callback:

```
server turn event (transport thread) ──► TransportListener.onCommitted(itemId)
   └─ serverDriven? serverTurnRotation()   [= fallback.commit()]  ← rotates the mirror + allocates the seq
       └─ under mirrorLock: snapshot mirror + cloud.commit() (allocate seq) + retain PCM[seq]   (UNCHANGED body)
   └─ bindItem(itemId)                       ← binds the server item to the freshly-allocated seq
```

`FallbackTranscriptionEngine` is **byte-identical** — its `commit()`/`sendAudio`/mirror/retain/CloudRelay machinery does not change. The service wires `live.attachServerTurnRotation { fallback.commit() }` in the CLOUD_LIVE branch; the callback returns the `Long` seq so `onCommitted` only binds when audio was actually cut. Lock order is preserved: `mirrorLock → bufferLock` on both the audio thread (`sendAudio`) and the server-turn thread (`onCommitted → fallback.commit → cloud.commit`), so they serialize on `mirrorLock` and never invert.

**Drop-mid-turn rescue.** In client mode the audio since the last commit is un-snapshotted until the next commit or `stopRecording`. In server mode there is no client commit, so on `onDisconnected` the engine first fires `serverTurnRotation()` to snapshot the in-progress tail under a fresh seq, THEN `abandonOutstanding(WS_DROP)` resolves that seq (and all pending) Lost — the fallback rescues the tail from `retained[seq]`. `stopRecording`'s unconditional `commit()` (`:1909`) remains the session-end outer bound for the final tail.

**Continuous append is already correct** — `onAudioChunk` calls `engine.sendAudio(chunk)` unconditionally (`:1328`) before any VAD; the mic is effectively already open. The only service change is to STOP running the client VAD/wall-cap in live sessions.

---

## File Structure

| File | Change |
|---|---|
| `transcription/live/LiveTranscriptionEngine.kt` | **Modify.** Add `serverDriven: Boolean = false` + `attachServerTurnRotation(cb: () -> Long)`. In server mode: `commit()` skips `SendOp.Commit` and the too-short gate (the server owns turn validity; backpressure shed still resolves Lost); `TransportListener.onCommitted` rotates the mirror via the callback then binds; `onDisconnected` rotates the tail before abandoning. Ledger / `resolveOnce` / `bindItem` / exactly-once **unchanged**; client-mode default path **byte-identical** (existing `LiveTranscriptionEngineTest` green unchanged). |
| `transcription/live/RealtimeEvents.kt` | **Modify.** `sessionUpdate()` emits `turn_detection: server_vad {threshold, prefix_padding_ms, silence_duration_ms}` instead of `null`. The one reviewed OpenAI wire change. |
| `transcription/live/OpenAiRealtimeProtocol.kt` | **Modify (minimal).** `onCommit()` becomes an unused no-op (the server auto-commits; the engine never enqueues a client commit in server mode). `onText` dispatch unchanged — `input_audio_buffer.committed` now arrives mid-session under server VAD and drives the engine's server-turn allocation. |
| `transcription/live/ElevenLabsRealtimeProtocol.kt` | **Modify.** Endpoint `&commit_strategy=vad`; `onAppend` sends each chunk immediately `commit:false` (drop the fold slot); `onCommit` no-op; `committed_transcript` → synth id → `onCommitted(id)` then `onCompleted(id, text)`. **Remove** the fold-slot / single-in-flight / burned-count / timeout machinery and the `scheduler` param (dead under server VAD). `partial_transcript` → `onDelta` unchanged. |
| `transcription/live/SonioxRealtimeProtocol.kt` | **Modify.** Config `enable_endpoint_detection:true`; `onCommit` no-op; `onText` accumulates finals, streams non-finals as preview, and on the `<end>` token synth id → `onCommitted(id)` then `onCompleted(id, <assembled finals>)`. **Remove** the grace-window machinery and the `scheduler` param. Key no-log discipline + `TextJoin` finals-join **retained**. |
| `transcription/live/RealtimeTurnPolicy.kt` | **Create.** `object LiveTurnPolicy { fun runClientVad(sessionIsLive: Boolean) = !sessionIsLive }` — the one pure predicate the service's VAD gate reads, so the "chunk-based paths untouched" contract is a pinned unit, not a buried `if`. |
| `service/FloatingBubbleService.kt` | **Modify.** `onAudioChunk` wraps the client-VAD commit + wall-cap block in `if (LiveTurnPolicy.runClientVad(sessionIsLive))` (append stays unconditional); CLOUD_LIVE branch passes `serverDriven = true` and wires `live.attachServerTurnRotation { fallback.commit() }`; drop the now-unused `scheduler` args to the EL/Soniox protocol ctors. Stop-flush `commit()` (`:1909`) unchanged. |
| `ui/screens/CloudProvidersScreen.kt` | **Modify.** `liveModeCaption()` → adds "billed per minute while the mic is open". |
| **Tests** | New: `LiveServerDrivenTurnTest`, `LiveTurnPolicyTest`, `OpenAiServerVadShapeTest` (or fold into `RealtimeEventParserTest`). Re-baselined (flagged per task): `RealtimeEventParserTest.outbound_session_update_shape_is_exact`, `OpenAiRealtimeProtocolTest`/`RealtimeProtocolContractTest`, `ElevenLabsRealtimeProtocolTest`, `SonioxRealtimeProtocolTest`, `SonioxNoLogDisciplineTest`, `RealtimeTransportTest` (protocol-ctor arg drops), `CloudProvidersScreenLogicTest` (caption). Untouched: `LiveTranscriptionEngineTest` (client-mode ledger still valid), all batch/TTS/segment suites. |

Untouched (re-asserted): `FallbackTranscriptionEngine`, `SourceRoutedTranscriptionEngine`, `RealtimeTransport` (the socket/reconnect shell), `RealtimeProtocol`/`SessionControl`/`Frame` seam, the batch adapters, `SpeechSegmenter`, the orderer.

---

## Task 1: Invert the engine to server-driven turns (the crux)

TDD. Write `LiveServerDrivenTurnTest` FIRST; it drives a fake `Transport` and a recording listener, and a fake "outer" that stands in for the fallback mirror rotation.

### Step 1 — `LiveTranscriptionEngine.kt`: the server-driven seam

Add the constructor flag and the rotation callback (near the other fields):

```kotlin
class LiveTranscriptionEngine(
    private val apiKey: String,
    private val scope: CoroutineScope,
    private val maxBacklog: Int = DEFAULT_MAX_BACKLOG,
    private val minCommitBytes: Int = DEFAULT_MIN_COMMIT_BYTES,
    /** true for the open-mic live modes: the SERVER cuts turns. false keeps the client-VAD ledger
     *  (still a valid mode of this class — its unit tests exercise it, and it is the documented
     *  per-provider fallback for a hypothetical non-segmenting provider). */
    private val serverDriven: Boolean = false,
    makeTransport: (RealtimeTransport.Listener) -> Transport,
) : TranscriptionEngine {

    /**
     * In server-driven mode a SERVER turn event must rotate the fallback mirror AND allocate the seq
     * as ONE operation — the same pairing the client [commit] gives batch. The engine is WRAPPED by
     * [com.whispereverywhere.transcription.cloud.FallbackTranscriptionEngine], so it calls "up"
     * through this callback (wired by the service to `fallback.commit()`), which snapshots the mirror
     * and calls back into [commit], returning the allocated seq (or a negative NO_SEGMENT). Null in
     * client mode. */
    @Volatile private var serverTurnRotation: (() -> Long)? = null
    fun attachServerTurnRotation(cb: () -> Long) { serverTurnRotation = cb }
```

`commit()` — two guarded changes; everything else byte-identical:

```kotlin
    synchronized(bufferLock) {
        if (!turnHasAudio) return NO_SEGMENT
        seq = nextSeq++
        shed = turnShed
        // Client mode: the server rejects a sub-100ms commit with no item, so a too-short turn is
        // resolved Lost locally. Server mode: the SERVER already decided this turn is real (it cut
        // it), and we send no client commit anyway — so the too-short gate does not apply.
        tooShort = !serverDriven && turnAudioBytes < minCommitBytes
        turnHasAudio = false
        turnShed = false
        turnAudioBytes = 0
        // Server mode never sends a client input_audio_buffer.commit — the server auto-commits.
        if (latched == null && !shed && !tooShort && !serverDriven) sendQueue.addLast(SendOp.Commit(seq))
    }
```

`TransportListener.onCommitted` — the allocation+bind point in server mode:

```kotlin
    override fun onCommitted(itemId: String) {
        if (serverDriven) {
            // Rotate the fallback mirror + allocate the seq for the just-cut turn, THEN bind the
            // server item to it. A NO_SEGMENT (no audio since the last boundary) binds nothing —
            // otherwise bindItem would attach this item to a stale earlier seq.
            val seq = serverTurnRotation?.invoke() ?: NO_SEGMENT
            if (seq < 0) return
        }
        bindItem(itemId)
    }
```

`onDisconnected` — rescue the in-progress tail before abandoning:

```kotlin
    override fun onDisconnected() {
        // Server mode: no client commit has cut the in-progress audio, so snapshot it under a fresh
        // seq FIRST (rotating the mirror). abandonOutstanding then resolves that seq — and all
        // pending — Lost, and the fallback rescues the tail from the retained PCM. Client mode is
        // unchanged (the service's next commit / stop snapshots the tail).
        if (serverDriven) serverTurnRotation?.invoke()
        clearSendBuffer()
        abandonOutstanding(WS_DROP)
    }
```

`realTransport`/`Transport`/`sendAudio`/`resolveOnce`/`bindItem`/`abandonOutstanding`/`close` are **unchanged**.

### Step 2 — `FloatingBubbleService.kt` wiring (CLOUD_LIVE branch)

`fallback.commit()` returns the paired seq, so the callback is a one-liner. Construct the live engine `serverDriven = true`, keep the `FallbackTranscriptionEngine` wrap identical, then attach:

```kotlin
val cloud = com.whispereverywhere.transcription.live.LiveTranscriptionEngine(
    apiKey = requireNotNull(key),
    scope = serviceScope,
    serverDriven = true,
    makeTransport = { transportListener -> /* … unchanged … */ },
)
lastLiveEngine = cloud
sessionIsLive = true
val fallback = FallbackTranscriptionEngine(cloud, local, serviceScope)
// Server turns rotate the SAME fallback that mirrors this engine's PCM — the seq the callback
// returns is the paired seq the mirror just retained. Wired here, where both objects exist, so
// FallbackTranscriptionEngine stays provider-agnostic and byte-identical.
cloud.attachServerTurnRotation { fallback.commit() }
com.whispereverywhere.transcription.SourceRoutedTranscriptionEngine(
    micEngine = fallback,
    deviceEngine = local,
).also { sourceRouter = it }
```

### Step 3 — `LiveServerDrivenTurnTest.kt` (new)

A fake `Transport` records appends/commits; a `RecordingListener` records `onDelta`/`onSegmentResolved` in order; a fake rotation `{ engine.commit() }` stands in for the fallback (asserting the seq pairing). Assert:
- **deltas before completion:** feed `onDelta` for an item, then `onCommitted(item)` → the rotation fires and allocates a seq → then `onCompleted(item, text)` resolves it; the recorded order shows the delta strictly BEFORE the resolution.
- **no client commit sent:** the fake `Transport` receives ZERO `sendCommit()` across a full server-driven turn (only appends).
- **exactly-once:** a duplicate `onCompleted` for the same item resolves the seq once; `onSegmentResolved(seq)` fires exactly once.
- **drop-mid-turn rescue:** append audio, `onDelta`, then `onDisconnected()` (no `committed`) → a seq is allocated for the tail (rotation fired) and resolved `Lost(WS_DROP)` exactly once; a later stray `onCompleted` is a no-op.
- **NO_SEGMENT bind guard:** `onCommitted` with no audio since the last boundary (rotation returns −1) binds nothing and resolves nothing.

**Self-review (Task 1):** The rotation callback re-enters the engine (`onCommitted → fallback.commit → cloud.commit`) on the transport thread; verified no lock inversion (`mirrorLock → bufferLock` both paths) and no recursion (`commit()` never calls the rotation). `@Volatile` on the callback covers the connect-thread write vs transport-thread read. Client mode (`serverDriven=false`, callback null) is provably identical — the two `commit()` predicates collapse to today's, and `onCommitted`/`onDisconnected` take their original branch.

---

## Task 2: OpenAI — `turn_detection: server_vad`

TDD. Re-baseline `RealtimeEventParserTest.outbound_session_update_shape_is_exact` FIRST to the server_vad JSON, then make it pass.

### Step 1 — `RealtimeEvents.kt`: the VAD config

Replace the always-null `turn_detection` with the verified server_vad object (defaults from the realtime-vad guide; `create_response`/`interrupt_response` are inert in transcription sessions, so omitted):

```kotlin
@Serializable
private data class Input(
    val format: Format = Format(),
    val transcription: Transcription = Transcription(),
    // server_vad — the SERVER detects speech boundaries and auto-commits, creating the item
    // mid-speech so transcription deltas stream AS SPOKEN. (Was null, which created no item until
    // our client commit — the field bug this wave fixes.)
    @SerialName("turn_detection") val turnDetection: TurnDetection = TurnDetection(),
)

@Serializable
private data class TurnDetection(
    val type: String = "server_vad",
    val threshold: Double = 0.5,
    @SerialName("prefix_padding_ms") val prefixPaddingMs: Int = 300,
    @SerialName("silence_duration_ms") val silenceDurationMs: Int = 500,
)
```

Update the `sessionUpdate` KDoc: the shape is now `server_vad`, WE no longer commit turns. `append(base64)` / `commit()` builders are untouched (`commit()` is now unused on the live path but retained for the client-VAD fallback mode).

### Step 2 — `OpenAiRealtimeProtocol.kt`

`onCommit()` → `override fun onCommit(): Boolean = true` with a KDoc: *the server auto-commits under server_vad; the engine never enqueues a client commit in server mode, so this is unreachable on the live path and kept only for the fallback.* `onText` dispatch is **unchanged** — `input_audio_buffer.committed` (already parsed, carries `item_id`) now arrives at each server-detected boundary and drives Task 1's `onCommitted`. `classifyFatal` unchanged.

### Step 3 — tests

- `RealtimeEventParserTest.outbound_session_update_shape_is_exact` (re-baselined): assert the EXACT server_vad JSON, key-ordered by declaration.
- `RealtimeProtocolContractTest` / `OpenAiRealtimeProtocolTest`: bootstrap emits exactly one `session.update` carrying `server_vad`; feeding the verbatim `input_audio_buffer.committed` → `onCommitted(itemId)`; a `.delta` before a `.completed` reaches `onDelta` first; `classifyFatal(401/403/429/500)` unchanged.

**Self-review (Task 2):** UNKNOWN carried from the fact sheet — whether `input_audio_buffer.committed` is emitted under server_vad or implicit. The parser already degrades a committed without `item_id` to a benign `Ack` (`RealtimeEvents.kt:147`); if the server omits `.committed` entirely, the defensive follow-up is to allocate on `input_audio_buffer.speech_stopped` and bind on first delta — added ONLY if device testing shows no `.committed`. Not speculatively coded (YAGNI); flagged for the acceptance run.

---

## Task 3: ElevenLabs — `commit_strategy=vad`

TDD. Re-baseline `ElevenLabsRealtimeProtocolTest` FIRST.

### Step 1 — endpoint + protocol

```kotlin
const val ENDPOINT =
    "wss://api.elevenlabs.io/v1/speech-to-text/realtime?model_id=scribe_v2_realtime&commit_strategy=vad"
```

Drop the `scheduler` ctor param (and the whole fold-slot / `inFlightId` / `commitDeferred` / `burnedCount` / timeout apparatus — all dead under server VAD). The body collapses to:

```kotlin
class ElevenLabsRealtimeProtocol : RealtimeProtocol {
    override val endpoint = ENDPOINT
    override val tolerant4xxRetry = false
    private lateinit var control: SessionControl
    private lateinit var sink: RealtimeTransport.Listener
    private val ids = AtomicLong(0)

    override fun upgradeHeaders(apiKey: String) =
        listOf(ProviderCatalog.byId(ProviderId.ELEVENLABS).let { it.authHeaderName to it.authHeaderValue(apiKey) })
    override fun bind(control: SessionControl, sink: RealtimeTransport.Listener) { this.control = control; this.sink = sink }
    override fun bootstrap(apiKey: String, language: String?): List<Frame> = emptyList() // commit_strategy rides the query

    /** Stream every chunk immediately, commit:false — the SERVER VAD commits segments. 16 k native, no resample. */
    override fun onAppend(pcm16k: ByteArray): Boolean {
        val b64 = Base64.getEncoder().encodeToString(pcm16k)
        return control.send(Frame.Text(ElevenLabsEvents.audioChunk(b64, commit = false)))
    }

    /** Unreachable on the live path — the server commits. Kept for the fallback mode. */
    override fun onCommit(): Boolean = true

    override fun onText(text: String) {
        when (val e = ElevenLabsEvents.parse(text)) {
            is ElevenLabsEvents.In.Partial -> sink.onDelta("", e.text) // preview only, streams as spoken
            is ElevenLabsEvents.In.Committed -> {
                // Server turn boundary + final text in one event: allocate+bind the seq, then resolve it.
                val id = ids.incrementAndGet().toString()
                sink.onCommitted(id)          // Task 1: rotate mirror + allocate seq
                sink.onCompleted(id, e.text)  // resolve it exactly-once
            }
            is ElevenLabsEvents.In.Error -> sink.onErrorEvent("input_error", e.message.length) // length only
            null -> Unit
        }
    }
    override fun classifyFatal(code: Int): FatalKind? = when (code) {
        401, 403 -> FatalKind.INVALID_KEY; 429 -> FatalKind.OUT_OF_CREDIT; else -> null
    }
    override fun reset() {}
    companion object { /* ENDPOINT as above */ }
}
```

`ElevenLabsEvents` (the pure codec) is unchanged.

### Step 2 — tests (re-baselined)

- `partial_transcript` JSON → `onDelta` with exact text, never binds/completes.
- append → exactly one `commit:false` chunk on the fake `SessionControl` (no fold, no `commit:true` ever).
- `committed_transcript` JSON → `onCommitted(id)` STRICTLY before `onCompleted(id, text)` (same id), resolving once.
- a delta then a committed_transcript: the recorded order proves the delta precedes the completion.
- `input_error` → `onErrorEvent("input_error", len)`; message text absent (length only).

**Self-review (Task 3):** Removing the serialization/timeout machinery is safe because it existed only to correlate CLIENT commits, which no longer exist. `committed_transcript` carries the final text, so the boundary and completion are one event — no in-flight window to protect. The `xi-api-key` header rule is unchanged.

---

## Task 4: Soniox — `enable_endpoint_detection:true` + `<end>` turns

TDD. Re-baseline `SonioxRealtimeProtocolTest` + `SonioxNoLogDisciplineTest` FIRST.

### Step 1 — config

Add the endpoint-detection flag to the key-bearing `Config` (no-log discipline unchanged — `Config` stays a non-`data` class with the redacting `toString`):

```kotlin
@Serializable
private class Config(
    @SerialName("api_key") val apiKey: String,
    val model: String = "stt-rt-v5",
    @SerialName("audio_format") val audioFormat: String = "s16le",
    @SerialName("sample_rate") val sampleRate: Int = 16000,
    @SerialName("num_channels") val numChannels: Int = 1,
    @SerialName("enable_endpoint_detection") val enableEndpointDetection: Boolean = true,
    @SerialName("language_hints") val languageHints: List<String>? = null,
) { override fun toString(): String = "Config(api_key=<redacted>, model=$model)" }
```

### Step 2 — protocol (drop the grace window; the `<end>` token IS the boundary)

Drop the `scheduler` ctor param and the `Pending`/`Armed`/`graceCounter`/`onGrace`/`closePendingLocked`/`fire`/`onCommit` grace machinery. Keep `finals`, `lastPreview`, `sessionExpiredRetries`, the `handleError` map, and `appendJoined`/`TextJoin`. New `onText`:

```kotlin
override fun onCommit(): Boolean = true // no client commit exists; the server marks turns via <end>

override fun onText(text: String) {
    val r = SonioxEvents.parse(text) ?: return
    r.errorCode?.let { handleError(it, r.errorLen); return }
    var preview = ""
    val boundaries = ArrayList<Completion>() // built under gate, fired outside it (deadlock discipline)
    synchronized(gate) {
        val nonFinal = StringBuilder()
        for (t in r.tokens) when {
            t.isFinal && t.text == END_TOKEN -> {
                // Server segment boundary: snapshot the finals for this turn and open the next empty.
                boundaries.add(Completion(ids.incrementAndGet().toString(), finals.toString()))
                finals.setLength(0)
            }
            t.isFinal -> appendJoined(finals, t.text) // real finalized word — melt-proof join
            else -> nonFinal.append(t.text)           // preview only, replaced each message
        }
        lastPreview = TextJoin.join(finals.toString(), nonFinal.toString())
        preview = lastPreview
    }
    sink.onDelta("", preview) // streams non-final tokens AS SPOKEN — preview strip only, never injected
    for (c in boundaries) { sink.onCommitted(c.id); fire(c) } // allocate+bind then resolve, in order
}

private fun fire(c: Completion) = sink.onCompleted(c.id, c.text) // zero-final → onCompleted(id, "")
```

`END_TOKEN = "<end>"` in the companion. `handleError`'s 413 finalize + `control.rotate()` and 403 single rotation are unchanged (they use `control`, not the removed scheduler). `bootstrap` keeps clearing `finals`/`lastPreview` per open; `reset` clears those + `sessionExpiredRetries`.

### Step 3 — tests (re-baselined)

- config bootstrap shape exact, now incl. `enable_endpoint_detection:true`.
- `onAppend` sends a binary frame equal to the input PCM (no base64/resample) — unchanged.
- mixed final/non-final tokens → `onDelta` preview = joined finals + current non-finals; finals persist, non-finals do not.
- an `<end>` token → `onCommitted(id)` strictly before `onCompleted(id, <assembled finals>)`; finals then reset so the next segment starts empty with a fresh id.
- a delta message then an `<end>` message: recorded order proves deltas precede the completion.
- a ZERO-final `<end>` → `onCompleted(id, "")` (empty completion → engine `EmptyUnexpected` → local rescue), never `onTranscriptionFailed`.
- error map: 401→INVALID_KEY, 402→OUT_OF_CREDIT, 403 first→`rotate()` then second→FORBIDDEN, 413→empty-binary finalize + rotate, 429/5xx→no fatal; `onErrorEvent` code + length only.
- No-log suite: after `bootstrap(KEY,…)`+`reset()`, reflection finds NO field holding KEY; a full session (bootstrap→tokens→`<end>`→error) proves KEY appears ONLY in the config frame, never in any `onDelta`/`onCommitted`/`onCompleted`/`onErrorEvent`/`onFatal` argument.

**Self-review (Task 4):** The `<end>`-driven boundary replaces the client-VAD grace window that only existed because the CLIENT cut turns before the server had finalized the trailing words — server endpoint detection finalizes them itself, so the race the grace window guarded is gone. Firing boundaries outside `gate` preserves the no-deadlock discipline. The `<end>` token is never appended to `finals`, so it never leaks into a transcript.

---

## Task 5: Service VAD gate + cost copy

### Step 1 — `RealtimeTurnPolicy.kt` (new) + `onAudioChunk` gate

```kotlin
package com.whispereverywhere.transcription.live

/** The ONE predicate deciding whether the service runs its client-side VAD/commit for a session.
 *  Live (server-driven) sessions bypass it — the SERVER cuts turns. Local + Gemini + batch keep it,
 *  byte-identical. A named unit so "chunk-based paths untouched" is pinned, not buried in an if. */
object LiveTurnPolicy { fun runClientVad(sessionIsLive: Boolean): Boolean = !sessionIsLive }
```

`onAudioChunk` — `engine.sendAudio(chunk)` stays UNCONDITIONAL (the mic is always open); wrap only the VAD/commit block:

```kotlin
engine.sendAudio(chunk)
// … waveform bands …
if (LiveTurnPolicy.runClientVad(sessionIsLive)) {
    val now = System.currentTimeMillis()
    if (speechSegmenter.onAmplitude(amp, now)) { lastCommitWallMs = now; engine.commit() }
    else if (now - lastCommitWallMs >= MAX_SEGMENT_WALL_MS) { lastCommitWallMs = now; engine.commit(); speechSegmenter.reset() }
}
```

Live sessions therefore never run `speechSegmenter` or the wall-cap; batch/local (`sessionIsLive=false`) are byte-identical. `stopRecording`'s `transcriptionEngine?.commit()` (`:1909`) stays — the session-end outer bound.

### Step 2 — cost copy

```kotlin
/** Sub-copy under the live-mode toggle. Says WHAT it does + the honest billing model. No speed claim. */
internal fun liveModeCaption(): String =
    "Transcribes word-for-word as you speak — billed per minute while the mic is open."
```

`liveModeLabel` (the per-provider "about $X/min" prices) is unchanged.

### Step 3 — tests

- `LiveTurnPolicyTest`: `runClientVad(false)` == true (batch/local/Gemini keep VAD); `runClientVad(true)` == false (live bypasses). This is the "chunk-based paths byte-identical" pin.
- `CloudProvidersScreenLogicTest`: re-baseline `liveModeCaption()` to the new string; assert it contains "billed per minute while the mic is open" and NO speed word.

**Self-review (Task 5):** `sendAudio` staying unconditional is what keeps the socket fed; only the *turn cut* moves to the server. `sessionIsLive` is frozen per session (`:1615`) and true only for the CLOUD_LIVE branch, so the gate can never accidentally disable batch VAD. The EL/Soniox protocol ctor calls in the CLOUD_LIVE branch drop their `sharedLiveReconnectScheduler()` argument (Tasks 3–4 removed the param); verify those two call sites compile.

---

## Task 6: Gate

- [ ] `.\gradlew.bat --no-daemon testDebugUnitTest` — assert the FRESH green count (baseline 822 + new suites, re-baselined suites moved not shrunk), 0 failures.
- [ ] `.\gradlew.bat --no-daemon assembleRelease bundleRelease` — R8 green.
- [ ] Acceptance (owner, on device, all three live modes): deltas visibly stream word-for-word during continuous speech WITHOUT pausing; turns finalize and inject on the server boundary; a mid-session network blip rescues the tail locally (no missing sentence); the live row reads "billed per minute while the mic is open".

## Whole-plan self-review

- **Directive satisfied:** WebSocket modes now keep the mic open and stream deltas as spoken (server VAD / server VAD / endpoint detection); non-WebSocket paths (local, Gemini segment, batch) keep client VAD exactly — pinned by `LiveTurnPolicyTest` + the untouched `LiveTranscriptionEngineTest`/batch suites.
- **Highest-risk item (mirror inversion) contained:** the mirror rotates on server boundaries and on drop; `FallbackTranscriptionEngine` is byte-identical; exactly-once and drop rescue are pinned by `LiveServerDrivenTurnTest`.
- **Honesty:** cost copy states continuous per-minute billing; no speed claim; no credential/content logging; Soniox no-log discipline re-pinned.
- **Scope discipline:** dead client-VAD machinery removed from the three protocols (unreachable under server segmentation); the `serverDriven`/`serverVad=false` fallback seam retained but not fleshed out (no verified provider needs it — building it would be dead code).
