# Startup cut-off — root-cause investigation

**Report (owner, 2026-09-10, verbatim):** "every once in a while, if I tap the transcribe button and
start speaking right away, sometimes the first chunks, like, first maybe couple seconds of what I
say gets cut off. So the listener should be listening when everything is fully ready to start
transcribing."

**Scope:** read-only. Repo `C:/Users/bastr/OneDrive/Desktop/whisper Everywhere`, branch `main` at
`f18549b`. No edits, no gradle, no adb, no git state change. `.androidbuild/wt-44` untouched.

---

## 1. The mechanism, in one line

**On the local tiers the microphone is not opened until the native model context has finished
loading.** `startAudioInput()` — the call that constructs `AudioRecord` and spawns the capture
thread — lives *inside* the engine's `onOpen()` callback, at `FloatingBubbleService.kt:3010`. The
haptic that tells the user "go" fires at the tap, `FloatingBubbleService.kt:2847`, **before**
`engine.connect()` is called at all (`:2942`). `LocalWhisperEngine.connect()` defers `onOpen()` until
the context is loaded (`LocalWhisperEngine.kt:165-190`) — the only tier that does. So every
millisecond of that load is a window in which the app has buzzed "listening" and no audio device is
open. **The audio is not dropped by a guard — it is never captured.**

The cloud tiers fail differently and less badly; §4 separates the two shapes.

---

## 2. The numbered trace, tap → first audio frame that can reach a decoder

Line numbers are `app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt` unless
stated.

| # | Site | What happens | Cost |
|---|---|---|---|
| 1 | `:1979-1983` `ACTION_UP` → `handleBubbleClick()` | Fires on finger-lift. No tap/long-press disambiguation wait — `longPressJob` is cancelled, not awaited. | ~0 |
| 2 | `:2038` `IDLE -> startRecording()` | | ~0 |
| 3 | `:2846` `updateBubbleState(BubbleState.CONNECTING)` | `currentState` set synchronously; visuals posted to Main. | ~0 |
| 4 | **`:2847` `vibrateStart()`** | **THE READY CUE — a 50 ms buzz, unconditionally, here.** Nothing about the engine has been consulted. | fires at T0 |
| 5 | `:2848` `AudioArbiter.requestCapture()` | Stops read-aloud TTS (Track F exclusivity). If TTS was speaking, an output stream tears down; the input HAL open at #16 can queue behind it. | 0–? |
| 6 | `:2851-2896` | `consentBudget.reset()`, fresh `SegmentOrderer`, `segmentQueueDepth.reset()`, `perceivedLatency.reset()`, **`sessionStartMs = System.currentTimeMillis()`** (`:2872`), `sessionContext` freeze, `beginInjectionSession()`. Cheap Main field writes. | ~1 ms |
| 7 | `:2898` `resolveTranscriptionEngine()` | On Main. Calls `warmLocalEngine(allowRebuild = true)` (`:2682` → `:2498`) — **the one caller permitted to rebuild**: on a tier change it `shutdown()`s the cached engine and constructs a fresh `LocalWhisperEngine`, making `isWarm()` false so #10 pays a full cold load. Also **assigns `transcriptionEngine = engine` at `:2795`** — before `connect()`. | ~1–20 ms |
| 8 | `:2908-2911` `connectingStatusLabel(...)` | Shows "Loading speech model…" iff `!isCloudSession && !localEngineWarm` (`:179-181`). **Cloud sessions are excluded by construction** — bare spinner, no words. | ~0 |
| 9 | `:2937-2940` `sessionLanguageFor(...)` | `app.whisperModelManager.installedModel()` — filesystem lookup. | ~1 ms |
| 10 | **`:2942` `engine.connect(lang, listener)`** | **Where the cost lives — and only on the local tiers.** See §3. | **local: 0.1 s – 4.1 s; cloud: ~0** |
| 11 | `:2946` `onOpen()` | Logs `onOpen handler: state=…`, then hops to Main (`serviceScope.launch(Dispatchers.Main)`). | 0–16 ms |
| 12 | `:2948` `sessionOpenMs = System.currentTimeMillis()` | **The session clock is anchored at engine-ready, not at the tap.** Everything downstream is stamped from here. | — |
| 13 | `:2952` `segmentCapPolicy.onSessionStart(sessionOpenMs)` | Arms the 4 s first-segment cap window. | ~0 |
| 14 | `:2960` `if (cloudWrapper != null) segmentCapPolicy.onCommit(sessionOpenMs)` | Closes the 4 s window immediately for cloud (3.6.0 A2 — an extra first segment is an extra billable request). | ~0 |
| 15 | `:2985-3009` `endpointer.onSessionStart(nowMs = sessionOpenMs, …)` | `SileroEndpointer.kt:646-665`: clears the gate, zeroes backpressure depth, `probeCutout = false`, **`flatlineArmed = false`**, `probeStats.reset()`, and calls **`probeArm()`** — which opens the VAD probe's session epoch. Does *not* init the native probe. | ~0 |
| 16 | **`:3010` `val started = startAudioInput()`** | **THE RECORDER STARTS HERE AND NOWHERE EARLIER.** `:2203` decides the source via `AudioSourcePolicy.decide(...)`, then: <br> • `UseMic` → `:2258` `startMicSource()` → `setActiveSource(MIC)` → `audioRecorder.start(::onAudioChunk)` <br> • `UsePlayback` → `:2262` `startPlaybackSource()` → `setActiveSource(PLAYBACK)` → `PlaybackAudioCapturer.start(...)` (may construct `AudioRecord` **twice** — 16 kHz, then 48 kHz + decimator, `PlaybackAudioCapturer.kt:47-55`) <br> • `RequestConsent` → **returns `Result.success(Unit)` with NO source open at all** (`:2218-2230`) | see #17 |
| 17 | `StreamingAudioRecorder.kt:53-68` | `AudioRecord(MediaRecorder.AudioSource.MIC, 16000, MONO, PCM16, bufferSize)` **constructed fresh every session** — binder round-trip to AudioFlinger, record-track creation, input HAL power-up and effect attach for the `MIC` source — then `record.startRecording()`. | **UNMEASURED — see §5** |
| 18 | `StreamingAudioRecorder.kt:70-101` | `Thread{}.start()`, `CaptureThreadPolicy.enterCaptureThread()`, first `record.read(buffer, 0, 1024)` blocks until 1024 B (32 ms) exist. Ring = `max(getMinBufferSize, 4096)` B ≈ ≥128 ms (`:27-28`). | ≥32 ms |
| 19 | `:2061` `onAudioChunk` → `val engine = transcriptionEngine ?: return` | **A silent-drop guard, but NOT the bug** — `transcriptionEngine` was assigned at `:2795`, before `connect()`. It only fires when no session exists. | ~0 |
| 20 | `:2062` `engine.sendAudio(chunk)` | **Unconditional and first.** `LocalWhisperEngine.sendAudio` (`:198-224`) appends under `bufferLock` with only a 30 s accumulation backstop (`MAX_BUFFER_BYTES = 30*16000*2 = 960,000 B`). No connect gate, no `listener == null` guard. **The engine would have accepted this audio at step 4.** | ~µs |
| 21 | `:2087` `endpointer.onFrame(chunk, amp, now)` | `SileroEndpointer.kt:428`. On the **first** frame reaches `VadProbeLifecycle.ensureReady()` (`:206-235`) → `whisper_jni.cpp:344-374` `whisper_vad_init_from_file_with_params`, inline on the capture thread. | **2–9 ms measured** (§5) |
| 22 | `:3022` `updateBubbleState(BubbleState.RECORDING)` | Bubble colour + waveform switch to RECORDING. **This one is already at true readiness** — only the haptic is early. | — |

### Three facts that fell out of the trace

1. **`transcriptionEngine` is live before `connect()`** (`:2795` inside `:2898`). The `?: return` at
   `:2061` is *not* what loses the audio, and nothing downstream needs to change to accept early audio.
2. **`sendAudio` has no readiness gate on any local tier.** The repo already asserts this as an
   invariant — `docs/superpowers/plans/2026-09-02-vad-hangover-retune.md:564`: *"**Nothing is
   clipped** — `sendAudio` is unconditional and first."* That is true **mid-session** and false **at
   session start**, for the single reason that at session start there is no capture thread to call it.
3. **`AudioSourcePolicy.decide(...)` depends on nothing from `connect()`** —
   `AudioSourcePolicy.kt:29-40` takes only `mediaPlaying`, `hasProjection`, `sdkInt`,
   `preferDeviceAudio`, `consentAvailable`. The whole `startAudioInput()` call can legally move above
   `connect()`.

---

## 3. What `connect()` costs, per tier

### 3a. NPU turbo (`npu-turbo`, Fold6) — **4,107 ms cold, measured on the shipped app**

`NpuWhisperBackend.load()` (`NpuWhisperBackend.kt:271-465`) runs seven stages under one
`NativeComputeGate.serialized` hold. Its own comment at `:418` estimates stage 6 at "342 MiB and
~525 ms", and `docs/superpowers/research/2026-08-28-npu-spike-g1-results.md:19` gives
`Cold context load (127 MB) | 488 ms | 498 ms | 525 ms` — **but both describe a 127 MB encoder-only
spike harness, not the shipped pair.** The shipped number was a written-down open question
(`docs/superpowers/research/2026-08-27-npu-whisper-turbo-research.md:151`;
`docs/superpowers/sdd/2026-08-29-fleet-onboarding/acceptance.md:173` specifies the log line with its
value left blank).

**It is in the captures.** `C:/Users/bastr/.androidbuild/capture-yt-84-flatline-0903-1936.txt`,
Fold6, `whisper_large_v3_turbo_quantized_*` graphs, `HTP_QTI_AISW backendId=6`:

```
09-03 19:29:48.854  W WE-DIAG : libQnnSystem.so: absolute path failed …; loaded by SONAME instead
09-03 19:29:48.856  I WE-DIAG : probe OK (libDir=…)
09-03 19:29:50.448  I WE-DIAG : initMelOnly: mel-only context ready (128 bands, no weights)
09-03 19:29:50.481  I WE-DIAG : nativeInit spec: melBins=128 decLayers=4 heads=20 vocab=51866 …
09-03 19:29:50.483  I WE-DIAG : backendCreate OK (1 ms)
09-03 19:29:50.654  I WE-DIAG : deviceCreate OK (172 ms)
09-03 19:29:51.329  I WE-DIAG : encoder: 775831552 bytes read in 674 ms
09-03 19:29:52.565  I WE-DIAG : encoder: contextCreateFromBinary OK - cold load 1234 ms
09-03 19:29:52.850  I WE-DIAG : decoder: 295854080 bytes read in 234 ms
09-03 19:29:52.921  I WE-DIAG : decoder: contextCreateFromBinary OK - cold load 71 ms
09-03 19:29:52.961  I WE-DIAG : nativeInit OK - encoder graph 'whisper_large_v3_turbo_quantized_encoder' …
09-03 19:29:52.961  I WE-DIAG : nativeInit: session armed with epoch 1
```

| Stage | Cost |
|---|---|
| `dlopen` libQnnSystem/libQnnHtp + `probe OK` | 2 ms |
| → `initMelOnly: mel-only context ready` (mel ctx + 563 KB vocab JSON + asset staging) | **1,592 ms** |
| → `nativeInit spec` | 33 ms |
| `backendCreate` | 1 ms |
| `deviceCreate` | 172 ms |
| encoder blob read (775,831,552 B @ ~1,151 MB/s) | 674 ms |
| encoder `contextCreateFromBinary` | **1,234 ms** |
| decoder blob read (295,854,080 B @ ~1,264 MB/s) | 234 ms |
| decoder `contextCreateFromBinary` | 71 ms |
| bind / quant / alias-guard / epoch | ~93 ms |
| **TOTAL, dlopen → `session armed`** | **4,107 ms** |

The spike harness's own second bar was *"cold load ≤ 8 s"* (`.androidbuild/npu-spike/RUNME.md`,
`cold 3521.4`), so 4.1 s is in family — and the "~525 ms" comment at `NpuWhisperBackend.kt:418`
**understates the shipped tier by roughly 8x.**

In this capture the 4.1 s init was the **prewarm** (first `encode:` is 27 s later), which is the design
intent: `:770-785` prewarms 1500 ms after service start. A warm turbo `connect()` takes the fast path
at `LocalWhisperEngine.kt:154-160` — one `controlExecutor` hop, ~0 ms.

### 3b. CPU small (`ggml-small-q5_1`) — 237 ms cold on a CPU-only device, 110 ms warm, far worse on the GPU arm

`C:/Users/bastr/.androidbuild/capture-tab-s10-load-0904-2138.txt` (Tab S10+, Mali rejected → CPU):

```
09-04 21:37:16.116  I ggml    : load_backend: loaded CPU backend from libggml-cpu.so
09-04 21:37:16.125  W ggml    : ggml_opencl: unsupported GPU 'Mali-G720-Immortalis MC12 r0p0'.
09-04 21:37:16.126  I whisper_jni: init: use_gpu=0 flash_attn=0
09-04 21:37:16.126  I ggml    : whisper_init_from_file_with_params_no_state: loading model from '…/ggml-small-q5_1.bin'
09-04 21:37:16.336  I ggml    : whisper_model_load: model size    =  189.49 MB
09-04 21:37:16.353  I ggml    : whisper_init_state: compute buffer (decode) =   97.28 MB
```

| Interval | Cost |
|---|---|
| `load_backend` → `loading model` (backend registration + OpenCL reject) | 10 ms |
| `loading model` → `model size = 189.49 MB` | 210 ms |
| → last `whisper_init_state` buffer | 227 ms |
| **TOTAL small/CPU engine init** | **237 ms** |

Fold6, where the Adreno OpenCL arm *is* taken:

- `docs/measurements/2026-07-28-whisper-stt-bench-fold6.log:2` — `load tier=eco loadMs=11672`
  (first load in the process); `:8` — `load tier=base loadMs=110` warm.
- `docs/superpowers/specs/2026-08-19-gpu-ab-bench.md:130-173` — four cold runs, 5-min idle:
  CPU arm `loadMs` **181–302**, GPU arm `loadMs` **1,515–1,933**.
- The "~7 s" figure at `:770`, `LocalWhisperEngine.kt:623/648`, `FallbackTranscriptionEngine.kt:422`
  is mmap + Adreno kernel compile; `docs/PLAN.md:137` puts the kernel compile alone at "~7–16 s".

### 3c. Cloud — **`connect()` costs nothing; `onOpen()` is synchronous**

This is the correction that reshapes the report. `LiveTranscriptionEngine.connect`
(`LiveTranscriptionEngine.kt:199-217`) ends:

```kotlin
senderJob = scope.launch { senderLoop() }
transport.connect(apiKey, language)
// Delivered synchronously (batch-compatible): the recorder may start buffering audio at once.
// The socket handshake completes in the background; audio captured before it is live is
// dropped by sendAppend and the turn, coming back short, is rescued by the fallback.
listener.onOpen()                                          // :216
```

`transport.connect()` → `RealtimeTransport.openSocket()` ends at
`webSocket = factory.newWebSocket(...)`, documented at `RealtimeTransport.kt:180-197` as returning
**before** the HTTP upgrade. `CloudTranscriptionEngine.connect` is the same shape —
`CloudTranscriptionEngine.kt:119` `listener.onOpen()`, with `:115-118` "there is nothing to load
here". `FallbackTranscriptionEngine` fires no `onOpen` of its own; it forwards the inner cloud
engine's through `CloudRelay.onOpen` (`:239`), and its local mirror's `onOpen` is **swallowed** by
`LocalRelay` (`:273`) — so a cold mirror never blocks a cloud session.

**So on cloud the mic opens promptly. The loss moves one layer down and changes shape.** Audio handed
to the transport before the handshake completes is discarded at `RealtimeTransport.kt:268-274`:

```kotlin
fun sendAppend(pcm: ByteArray): Boolean {
    val sent = synchronized(lock) {
        // [bootstrapped], not just a non-null socket: newWebSocket() hands back a socket before the
        // handshake, so "non-null" is not "ready to receive audio". See the [bootstrapped] KDoc.
        if (webSocket == null || !bootstrapped) return false
```

`false` → `senderLoop` → `markTurnShed()` → `commit()` resolves the turn **`Lost(BACKLOG)`**
("network too far behind") → `FallbackTranscriptionEngine.localRetry` re-transcribes **the whole first
turn** from its local mirror. Granularity matters: it is not "the first two seconds are missing from
the cloud text", it is "the entire first turn is shed and redone by whisper" — so the words normally
**survive**, in whisper's quality rather than the provider's, delivered late. They are lost only if
the mirror cannot run (no model installed, mirror failure), and the mirror's own context may be cold.

Handshake magnitudes, from `docs/measurements/2026-09-10-gemini-live-probes-t0.md` (PC fibre; `:7`
"No device, no adb"):

- `:171` — `| Upgrade (TCP+TLS+101) | ~0.11–0.16 s |`
- `:170` — `| setup → setupComplete | p50 0.132 s, p95 0.170 s, max 0.216 s (n=58) |`
- `:178` — `| Reconnect after cap / after TCP abort | 0.24–0.30 s |`
- `RealtimeTransport.kt:487` — "handshake + setup ≈ 0.17 s, T0 P1"

**No on-device cloud measurement exists in the repo, and no cloud tier appears in any of the nine
captures.** On a phone with a cold radio, DNS + TLS is routinely 0.5–2 s.

The Gemini protocol's own 2 s ring sits **behind** this gate and therefore cannot help here:
`GeminiRealtimeProtocol.kt:611-612` `MAX_QUEUED_BYTES = 64_000` (= exactly 2,000 ms at 32 kB/s), filled
at `:499-505` `enqueue`, flushed in one burst by `pump` at `:533-551` on `setupComplete`. But
`onAppend` is only ever reached *past* the `bootstrapped` check, so the ring covers **`setup` →
`setupComplete` (0.13 s p50) and the ≤500 ms inter-turn ack gap** — not the upgrade, and not the tap.
`GeminiRealtimeProtocolTest.kt:204-210` pins the cap; `:213-215` names its purpose ("the first turn's
audio and its commit arrive inside the 130 ms setup round trip").

The three non-Gemini live protocols (`OpenAiRealtimeProtocol`, `ElevenLabsRealtimeProtocol`,
`SonioxRealtimeProtocol`) have **no queue, no `ready` flag, no cap** — pre-handshake audio is shed
outright. And `RealtimeTransport.kt:186-195` records that Soniox **hard-400s** any pre-config frame,
which is why the `bootstrapped` gate exists: **any pre-connect replay scheme must keep that gate
intact.**

### 3d. The device-audio consent path — 1–3 s, already an accepted gap

`:2224-2228`:

> `// KNOWN GAP (accepted): until the user answers (typically 1-3s), the session`
> `// shows RECORDING with no live source; grant starts capture, deny/back falls`
> `// back to mic.`

The same bug in the source-selection layer, already known. It stacks on the connect window.

### 3e. Summary

| Tier / source | Warm | Cold | Label? | Fate of the missing audio |
|---|---|---|---|---|
| **NPU turbo, mic** | ~0 ms | **4,107 ms** measured | "Loading speech model…" | **never captured — irrecoverable** |
| **CPU small, mic** | 110 ms | 237 ms (CPU-only) / 1,515–1,933 ms (Adreno) / 11,672 ms (first-in-process) | "Loading speech model…" | **never captured — irrecoverable** |
| **Cloud live, mic** | `connect` ~0; handshake 0.11–0.16 s fibre, unmeasured on radio | no warm path | **none — bare spinner** | captured, then whole first turn shed `Lost(BACKLOG)` → re-transcribed by the local mirror (survives *if* the mirror runs) |
| **Cloud batch, mic** | `connect` ~0 (`CloudTranscriptionEngine.kt:119`) | — | none | nothing lost — the POST is per-commit |
| **any tier, device audio** | + up to two `AudioRecord` constructions | + **1–3 s consent with no source open** | none | never captured |
| **all tiers, both sources** | **+ `AudioRecord` construct + `startRecording()` + first 32 ms read — UNMEASURED, paid every session** | | | never captured |

**When is a local tier cold?** The first ~5.6 s after service start (1500 ms delay + the load); after
any `onTrimMemory(level >= TRIM_MEMORY_RUNNING_LOW)` while idle (`:3400-3415`) — and **nothing
re-prewarms after a trim**: the only prewarm triggers are service start (`:779-785`) and a
model-switch/install (`:817-828`); after a tier switch, because `warmLocalEngine(allowRebuild = true)`
rebuilds; and on the first session of a new process. A background overlay service holding 342 MiB
(`npu`) to 1.02 GiB (`npu-turbo`) makes trims routine.

---

## 4. Which specific drop explains "a couple of seconds"

**The local-tier one, and only that one.** Cloud loses audio too, but the wrapper re-transcribes the
whole shed turn locally, so the words normally arrive — late and in whisper's quality, not absent. A
user would report that as "wrong words" or "slow", not as "cut off".

On a local tier there is no rescue: the capture thread does not exist, so `sendAudio` is never called,
so there is nothing to re-transcribe. **4,107 ms of measured cold turbo load is "a couple of seconds"
and then some**, and it is *silent* — no partial text, no gap marker, nothing in the transcript to say
words were spoken.

**"Every once in a while" is the cold window, not a second bug.** Warm, the same path costs one
`controlExecutor` hop + one Main hop + the `AudioRecord` open — likely 150–500 ms, unpleasant but not
"a couple of seconds". The intermittency is exactly which of the cold windows above the tap lands in,
with post-trim the most frequent by construction.

---

## 5. Ruled out, with evidence

- **The Silero VAD probe init is cheap.** `capture-tab-s10-baseline-0904-2132.txt`, three cycles:
  `whisper_vad_init_from_file` → `vad probe: context ready` = **9 ms, 2 ms, 2 ms**;
  `capture-tab-s10-load` gives **3 ms**. It runs inline on the capture thread
  (`VadProbeLifecycle.kt:206`) against a ≥128 ms `AudioRecord` ring — cannot overrun it.
- **The per-frame probe is cheap.** `docs/PLAY-LISTING.md:316` — "p50 2.3-2.4 ms / p99 5.8-6.1 ms with
  0.2-0.3% of frames overrunning", against an 8 ms budget.
- **The `?: return` at `:2061` is not the drop** — `transcriptionEngine` is set at `:2795`.
- **`sendAudio` never refuses audio on a local tier** — `LocalWhisperEngine.kt:198-224`.
- **No tap-detection delay** — `handleBubbleClick()` is called directly from `ACTION_UP` (`:1983`).
- **The 885 KB Silero asset copy on Main** (`:522-524`, unmeasured per
  `docs/superpowers/specs/2026-08-20-i-owner-acceptance.md:615-623`) is at *service construction*,
  once per install — not per session.
- **Cloud `connect()` is not the wait** — §3c.

### The one measurement the repo cannot currently make

The nine captures in `C:/Users/bastr/.androidbuild/` are **tag-filtered** (effectively
`logcat -s WE-DIAG ggml whisper_jni`). Grep for
`startRecording|onSessionStart|startAudioInput|setActiveSource|AudioRecord|recorder start` returns
**0 hits across all nine files** — the Kotlin service side was filtered out at capture time. So the
number that would close this investigation, **tap → first `record.read()` return**, exists in no
artefact.

The lines that give it are **already in the source**; they need only an unfiltered capture
(`logcat --pid=$(pidof com.whispereverywhere) -v threadtime`):

| Line | Marks |
|---|---|
| `:2894` `startRecording: sessionContext=…` | T0 (the tap) |
| `:2941` `connect lang resolved=…` | connect entry |
| `LocalWhisperEngine.kt:156` `onOpen (ctx already loaded)` / `:189` `onOpen (ctx loaded)` | which branch, warm or cold |
| `:2947` `onOpen handler: state=…` | engine ready |
| `:2210` `startAudioInput: decision=…` | source chosen |
| `StreamingAudioRecorder.kt:68` `AudioRecord recording bufferSize=… rate=…` | mic open |
| `:3011` `recorder start success=…` | recorder up |
| `StreamingAudioRecorder.kt:93` `audio: chunks=1 read=… peakRms=…` | **first captured frame** |

**Zero code changes; one device session.** This should be step 0 of the fix, because the
`AudioRecord` open is the residual that no design here can remove.

---

## 6. The fix

### Option A — the startup ring

**Shape.** Move `endpointer.onSessionStart(...)` **and** `startAudioInput()` out of `onOpen()` and up
into `startRecording()`, *above* `engine.connect(...)`, keeping their relative order. Add one
`@Volatile engineReady` flag and one bounded PCM ring. `onAudioChunk` gains a two-phase head:

```
// capture thread
if (!engineReady) { ring.append(chunk, amp, now); paintVisuals(chunk, amp); return }
if (ring.isNotEmpty()) ring.drainTo(::processChunk)   // in order, ORIGINAL timestamps
processChunk(chunk, amp, now)                          // the existing body, byte for byte
```

`onOpen()` keeps everything else and adds one statement: `engineReady = true`.

**Why this layer.** The repo has already built this ring **one layer too low**. Gemini's 2 s
pre-setup ring (`GeminiRealtimeProtocol.kt:611-612`, `:499-505`, `:533-551`) is this exact mechanism,
owner-reviewed, sized against its own measured RTT — but behind the `bootstrapped` gate, so it covers
0.13 s of one provider's setup and nothing of the tap window. Option A is the same ring at the
**capture seam**, where it covers all three tiers and both sources.

**RAM.** 16 kHz mono PCM16 = 32,000 B/s. 2 s = 64 KB, **5 s = 160 KB**, 8 s = 256 KB; the measured
4.1 s turbo worst case = **131 KB**. `LocalWhisperEngine.MAX_BUFFER_BYTES` is already 960,000 B, so
even a 30 s ring is inside a cap the engine tolerates. **Recommended cap: 5 s / 160 KB**, and on
overflow **stop buffering and log** rather than drop-oldest — dropping the oldest bytes discards
precisely the first words the feature exists to save; dropping the newest leaves a contiguous, honest
prefix.

One live-session ceiling to respect: `LiveTranscriptionEngine.sendAudio` sheds once
`queuedAppends >= maxBacklog` (`:233-236`), and `DEFAULT_MAX_BACKLOG = 128` (`:626`) ≈ **4 s at
32 ms/chunk**. A 5 s replay plus live capture can trip that — in which case the turn sheds and the
mirror rescues it, **exactly as 100% of that window does today**. So it is not a regression, but the
ring cap can also be chosen per session kind (`cloudWrapper` / `sessionIsLive` are both resolved
before `connect()`): 2 s for a live session, 5 s for local.

**The endpointer clock.** `SileroEndpointer.kt:39-44` rules:

> "**ONE clock:** the caller's `nowMs`, stamped on the chunk the frames came from. … A burst delivery
> (the AudioRecord ring holds >=128 ms) makes the hangover fire slightly LATE, never early — the
> conservative direction."

That tolerance was written for a 128 ms burst. A 2–5 s burst needs the **anchors moved**, not the
tolerance stretched: replay with the chunks' **original** capture timestamps, and anchor the session
clocks at the **tap** (`sessionStartMs`, `:2872`) instead of at engine-ready (`sessionOpenMs`,
`:2948`). Then, term by term:

- **Hangover** (`nowMs - tempEndMs >= hangoverMs`): replayed chunks are 32 ms apart in their own
  stamps, exactly as live. Behaviour **identical** — neither late nor early.
- **Cadence governor** (`nowMs - lastCommitMs >= floorMs`, `:244`): with the tap anchor the deltas are
  positive and correct. It is consulted only inside the `hasCommitted &&` guard, so the session's
  first cut is free regardless — the seam cannot manufacture a commit.
- **Wall caps** (`SegmentCapPolicy.capExceeded`): the 4 s first-cap window now starts when the user
  started talking, which is what it always meant to measure. **This is a behaviour change** — on a
  slow cold connect the cap can fire *during* the replay — and it is the right one: it is the cut the
  user would have got had the engine been warm. **But the cloud suppression at `:2960` must move with
  the anchor**, or cloud sessions regain the 4 s cut that 3.6.0 A2 removed to avoid an extra billable
  request.
- **Flatline trigger** (`:351-365`): deliberately *a count, not a wall-clock age* — "the device stamps
  one bursty `currentTimeMillis()` per chunk". A burst is the case it was written to survive.
  Unaffected.
- **Speech-evidence gate** (`speechEvidenceMs()`, `:514`): `evidenceFrames * FRAME_MS`, a pure frame
  count. Unaffected.
- **Probe budget / cutout latch** (`timedProbe`, `:770-786`): uses the injected monotonic `nanoClock`,
  not `nowMs`, and counts *consecutive* overruns. Unaffected in kind — **but** a 5 s drain is ~156
  probe calls at ~2.3 ms ≈ **360 ms of solid capture-thread work**, during which `record.read()` is
  not called and the ≥128 ms `AudioRecord` ring **will** overrun. **This is Option A's principal
  technical risk**: it would lose audio in the *middle* of the session opening — a stutter rather than
  a truncation, and strictly worse than the bug being fixed. It needs engineering (bound the per-drain
  work and interleave reads, or drain into a second buffer while live audio keeps being read), not a
  hand-wave.

**The Gemini activity machine.** A replay arriving as one burst would open **one** activity carrying
the whole buffer, and that is **correct and already designed**:
`docs/measurements/2026-09-10-gemini-live-probes-t0.md:117-119` — "lazy open on the first frame of a
turn, so the app's pre-roll lands *inside* the activity". The turn is then cut by our endpointer's
`activityEnd` as usual. **Both rings should stay**: `onOpen` is socket-open, which still precedes
`setupComplete` by ~0.13 s, so the protocol ring keeps earning its keep for that last stretch. One
interaction to note: the FOLLOW-UP at `GeminiRealtimeProtocol.kt:526-531` proposes opening
`activityStart` on the endpointer's *first speech frame* for billing reasons — a replay design pushes
the other way, and the two want reconciling before both land.

**The device-audio latch and consent.** The ring must be armed **only for the source that will
actually be used**. Because `AudioSourcePolicy.decide(...)` depends on nothing from `connect()` (§2,
fact 3), moving the whole of `startAudioInput()` above `connect()` is sound — and sampling
`mediaDetector.isCurrentlyPlaying()` *at the tap* is arguably more faithful to intent than sampling it
after a 4 s load. **Opening the mic "just in case" during a device-audio session is forbidden** —
`:2221-2222`: "The mic is NOT opened meanwhile — media capture must never mix room audio." So the
`RequestConsent` branch gets **no ring**; its 1–3 s gap is a separate fix (pre-request the projection
token while IDLE, or persist it across sessions) and is **out of scope here**.

**Two ordering invariants that must not break. Both fail silently.**

1. **`onSessionStart` must stay ABOVE `setActiveSource`.** `onSessionStart` writes
   `flatlineArmed = false` (`SileroEndpointer.kt:660`); `setActiveSource` writes
   `armFlatline(source == PLAYBACK)` (`:2250-2256`). Reversing them leaves the 4.4 flatline cut
   **permanently disarmed** for every device-audio session. Today the nesting gets this right by
   accident; moving both statements *together* preserves it, moving one does not.
2. **`probeArm()` must run before the capture thread starts.** `VadProbeLifecycle.kt:102-105` states
   the precondition:
   > "(2) `probeArm` runs before the session's first frame. `SileroEndpointer` fires it from
   > `onSessionStart`, and the ordering that actually carries this is a **THREAD START**, not the
   > lambda's reachability: `onOpen`'s Main body … runs `onSessionStart` and only then
   > `startAudioInput()`, which spawns the capture thread."

   The capture thread snapshots its epoch via `ThreadLocal.withInitial { armed.get() }` at its **first
   probe call**. Start the thread before `arm()` and it snapshots `NO_SESSION`, `ensureReady(session)`
   returns `false` forever, and **the Silero VAD is silently off for the whole session** — amplitude
   fallback, i.e. the pre-3.7 machine, with no error and no log beyond an absent `probe:` line.
   Option A satisfies this **only if** `onSessionStart` moves up together with `startAudioInput()` and
   stays above it, **and** the replay is executed **by the capture thread itself** — a second thread
   calling the probe would write `VadProbeLifecycle`'s one shared direct buffer concurrently with the
   real capture thread: T8 torn frames / T9 cross-session LSTM contamination, the exact intra-session
   hazard `switchSource` already carries as undischarged residue (`:2317-2324`,
   `VadProbeLifecycle.kt:60-70`).

**Seams.**

| Seam | File:line | Change |
|---|---|---|
| new `StartupRing` | `app/src/main/java/com/whispereverywhere/audio/StartupRing.kt` | bounded `(chunk, amp, nowMs)` queue, preallocated; `append`/`drainTo`/`clear`/`byteSize`. Pure ⇒ JVM-pinnable. |
| move `onSessionStart` + `startAudioInput` | `FloatingBubbleService.kt:2985-3016` → above `:2942` | keep relative order; re-anchor `nowMs` to `sessionStartMs` (`:2872`) |
| move the cloud first-cap suppression | `FloatingBubbleService.kt:2960` | must follow the anchor |
| `engineReady` + drain | `FloatingBubbleService.kt:2061` (`onAudioChunk` head) | two-phase head above the existing body |
| set the flag | `FloatingBubbleService.kt:2947` (`onOpen` Main body) | one statement |
| clear ring + flag | `:3154` `stopRecording`, `:3378` `teardownRealtime`, `:3126` connect-fatal `onError` | a failed connect must discard, not replay into a dead engine |
| move the haptic (Option B) | `:2847` → `onOpen` Main body near `:3022` | one statement |
| **unchanged** | `LocalWhisperEngine.sendAudio`, `commitSegment`, `SegmentOrderer`, `RealtimeTransport`'s `bootstrapped` gate, every engine | **no engine change at all** |

**Tests.** Five pin tests bear on this; three are affected, and two of those are **hard failures**
because they anchor on the literal `"                    val started = startAudioInput()"` inside
`onOpen` and `indexOf` returns −1 once it moves:

| Test | File:line | Effect |
|---|---|---|
| `EndpointerLifecyclePinTest.onOpenHandsOverThisSessionsCadenceBeforeTheFirstFrame` | `:103-113` | **HARD FAIL** — asserts `cloud < startInput` on that literal (20-space indent hard-coded). Must be rewritten deliberately to pin the *new* ordering: `onSessionStart` before `startAudioInput`, both before `connect`. |
| `BackpressureWiringPinTest.theSlowRowRidesTheSameOnSessionStartCallAsTheFastRow` | `:77-89` | **HARD FAIL** — same anchor, same −1. |
| `CapSeamPinTest.sendAudioIsUnconditionalAndFirst` | `:68-75` | **Conditional** — censuses `engine.sendAudio(chunk)` at *exactly once*. A replay loop using that same literal makes it 2. Per this file's own discipline (`:18-34`) the pin should be **updated intentionally**, not dodged by renaming the variable. The `send < gate` ordering half is unaffected. |
| `EndpointerLifecyclePinTest.thereAreExactlyThreeServiceSideResetSites` | `:83-91` | Breaks only if the new path adds an `endpointer.reset()`. It should not. |
| `CommitFunnelPinTest` (11 tests) | `:89-289` | Green as long as the replay only calls `sendAudio` and never `engine.commit`/`commitRetainingTailMs` (`:100` pins **exactly 6** `commitSegment(`). |
| `DeviceAudioLatchPinTest` (3 tests) | `:66`, `:86`, `:104` | Green — all three are scoped to other member bodies. **But the real hazard here has no pin at all:** opening the mic before `AudioSourcePolicy.decide` runs is precisely the "microphone enters a device-audio session" leak this class exists to forbid (`:8-24`). **Add one.** |

Both hard-failing pins encode the *same real invariant* — the endpointer must be armed before the
first captured frame — and Option A honours it; only their textual anchor moves. Rewriting them is
part of the change, not a workaround.

### Option B — move the ready cue to true readiness

Delete `vibrateStart()` from `:2847`, fire it from the `onOpen()` Main body beside
`updateBubbleState(BubbleState.RECORDING)` (`:3022`). Note what is **already** right: the bubble's
colour and waveform switch at true readiness. **Only the haptic is early**, so B is genuinely one
statement.

It stops the app lying, and on the cold turbo path (4.1 s) it converts a silent data loss into a
visible wait — strictly better. It **recovers no audio**, makes the app feel slower by exactly the
connect time, and makes the *variance* user-visible: a buzz 0.15 s after the tap on one session and
4.1 s later on the next is harder to dictate against than a predictable one. And it cannot cover the
always-paid `AudioRecord` open unless the cue is deferred to the **first captured frame** — the honest
version of B, and slightly more than one line.

### Which does the owner's sentence ask for?

*"the listener should be listening when everything is fully ready to start transcribing"* reads
literally as **A**: at the moment everything is ready, the listener should *already be* listening. It
can be strained into B — "only tell the listener to start once ready" — but that reading contradicts
the complaint, which is that words were **lost**, not that the app lied. **B alone answers a report
the owner did not file.**

### Recommendation: **C — A as the substance, B as one line inside it.**

A is the fix. B is a one-statement honesty patch that makes A's irreducible residual (the
`AudioRecord` open, which A can only stop *adding* `connect()` to, never remove) invisible rather than
merely smaller. A without B leaves a 100–500 ms lie standing; B without A leaves the audio lost and
the app slower. Together the user's mental model becomes true: **buzz = the first sample is in the
ring.**

**Size: 3–4 days.**

| | |
|---|---|
| **0.5 d** | **Step 0, and it gates the rest:** one unfiltered-logcat device session measuring tap → first `record.read()` per tier and per source. No code (§5 lists the eight existing log lines). Without it A's residual is unknown and B's threshold is a guess. |
| 1.0 d | `StartupRing` + JVM tests: cap, ordering, overflow-stops-buffering, drain-preserves-timestamps. |
| 1.0 d | The service reorder: hoist `onSessionStart` + `startAudioInput` above `connect`, re-anchor both session clocks and the cloud suppression to `sessionStartMs`, two-phase `onAudioChunk`, ring teardown on all three exit paths, `vibrateStart` relocation. |
| 0.5 d | The capture-thread drain hazard: bound per-drain probe work so the `AudioRecord` ring cannot overrun during replay. |
| 1.0 d | Pins + regression: rewrite the two hard-failing pins, update the `CapSeamPinTest` census, add the missing device-audio pin, run the full JVM suite, then a device sheet across {turbo cold, turbo warm, small cold, small warm, live cold-radio, live warm} × {mic, device audio}, each with a scripted opening word spoken on the buzz. |

### What could regress — ordered by how quietly it fails

1. **The Silero VAD goes silently off for the whole session** if the capture thread starts before
   `probeArm()`, or if anything but the capture thread drains the ring (`VadProbeLifecycle.kt:102-116`,
   `:60-70`). Symptom: no `probe: frames=… p50=…` line, amplitude endpointing, worse cuts, **no error
   anywhere.** Highest-risk item in the change.
2. **The flatline cut goes permanently disarmed for device audio** if `setActiveSource` ends up above
   `onSessionStart` (`SileroEndpointer.kt:660` vs `:2250-2256`). Silent; only visible on device-audio
   capture of edited media.
3. **`AudioRecord` ring overrun during the replay** — ~360 ms of probe work for a 5 s drain against a
   ≥128 ms ring. Would lose audio *mid-opening*: a stutter, strictly worse than the truncation being
   fixed.
4. **The mic opens before `AudioSourcePolicy.decide` in a device-audio session** — the leak
   `DeviceAudioLatchPinTest` exists to forbid, with no pin currently covering it.
5. **The 4 s first-cap window now starts at the tap**, so on a slow cold connect it can fire during the
   replay and produce an extra first segment. On **cloud batch** an extra segment is an extra
   **billable** request — the suppression at `:2960` must move with the anchor or 3.6.0 A2 regresses.
6. **RECORDING now precedes engine-ready**, so `stopRecording` can be tapped with a full ring and no
   engine open. The connect-fatal `onError` path (`:3126`) must discard the ring; `deliverReleasedText`
   / `transcriptSink` must not see a session that never opened.
7. **The mic is held during a failed connect** (no model installed, dead key) where today it never
   opens. Privacy story unchanged — same session, same consent — but the mic-in-use indicator now
   appears for a session that produces nothing. **Worth an explicit owner ruling.**
8. **`mediaDetector.isCurrentlyPlaying()` is sampled up to 4 s earlier**, so the source decision can
   differ when media starts or stops during connect.
9. **The `bootstrapped` gate must survive untouched.** Soniox hard-400s any pre-config frame
   (`RealtimeTransport.kt:186-195`). The startup ring must sit **above** the transport, never bypass it.
10. **`AudioArbiter` exclusivity.** `requestCapture()` at `:2848` already precedes everything, so TTS
    stops before the mic opens either way — but the mic now opens ~4 s earlier relative to that
    teardown. Verify no output/input HAL race on the Fold6.

### Two findings worth fixing regardless of which option is taken

- **`NpuWhisperBackend.kt:418` is wrong by ~8x.** "342 MiB and ~525 ms" describes a 127 MB
  encoder-only spike; the shipped pair measures **4,107 ms**. The comment is load-bearing — it is the
  stated reason the stage ordering puts `nativeInit` last — and the number now exists.
- **Nothing re-prewarms after `onTrimMemory`.** `:3400-3415` frees the context while idle; the only
  prewarm triggers are service start and a model switch (`:779-785`, `:817-828`). Re-arming the
  prewarm on `onTrimMemory`'s complement (e.g. the next IDLE settle after a trim) would remove the
  most frequent cold window outright, and it is a much smaller change than Option A.

---

## 7. Answers to the three questions asked

**Mechanism, three sentences.** The bubble's ready haptic fires at the tap
(`FloatingBubbleService.kt:2847`) but `startAudioInput()` — the call that constructs `AudioRecord` and
spawns the capture thread — is nested inside the engine's `onOpen()` callback
(`FloatingBubbleService.kt:3010`), and `LocalWhisperEngine` is the one engine whose `onOpen()` genuinely
waits, until the native model context has loaded (`LocalWhisperEngine.kt:165-190`). So on the local
tiers the app buzzes "listening" and then keeps the microphone shut for the whole load: nothing drops
the audio, it is never captured, even though `sendAudio` is unconditional and `transcriptionEngine` is
already assigned at `:2795` before `connect()` runs. Cold that load measures **4,107 ms** on the
shipped npu-turbo tier and 237 ms–11,672 ms on CPU small, and it recurs after every `onTrimMemory`
because nothing re-prewarms — which is exactly "every once in a while".

**Measured startup cost per tier.**

| Tier | Cold | Warm | Evidence |
|---|---|---|---|
| **NPU turbo** | **4,107 ms** (Fold6, shipped app: 1,592 ms mel+vocab, 172 ms `deviceCreate`, 674 ms + 234 ms reads of 775 MB + 296 MB, 1,234 ms + 71 ms `contextCreateFromBinary`) | ~0 ms | `capture-yt-84-flatline-0903-1936.txt` 19:29:48.854 → 52.961. **The repo listed this as an open question; the in-source estimate at `NpuWhisperBackend.kt:418` understates it ~8x** |
| **CPU small** | **237 ms** (Tab S10+, CPU-only) / **1,515–1,933 ms** (Fold6 Adreno arm) / **11,672 ms** (first-in-process) | **110 ms** | `capture-tab-s10-load-0904-2138.txt`; `gpu-ab-bench.md:130-173`; `whisper-stt-bench-fold6.log:2,8` |
| **Cloud live** | **`connect()` ≈ 0 — `onOpen()` is synchronous** (`LiveTranscriptionEngine.kt:216`). The exposure is the handshake behind it: 0.11–0.16 s upgrade + 0.13 s p50 / 0.22 s max `setupComplete`, during which frames are shed at `RealtimeTransport.kt:272` and the whole first turn is rescued by the local mirror | no warm path | `gemini-live-probes-t0.md:170,171,178`; `RealtimeTransport.kt:487`. **PC fibre only — no on-device measurement exists** |
| Silero probe init | **2–9 ms** | — | `capture-tab-s10-baseline-0904-2132.txt` ×3; `capture-tab-s10-load` |
| **`AudioRecord` open** | **UNMEASURED — paid every session, every tier, both sources** | | the captures are tag-filtered; zero service-side lines in all nine |

**Recommended fix and size.** **Option C — the startup ring (A) with the haptic moved onto true
readiness (B) as one statement inside it. 3–4 days**, the first half-day a no-code unfiltered-logcat
session to measure the `AudioRecord` open, because that is the residual A cannot remove and the
threshold B must be set against. The design has an owner-reviewed precedent in tree: Gemini's 2 s
pre-setup ring (`GeminiRealtimeProtocol.kt:611-612`, `:499-505`, `:533-551`) is this exact mechanism
built one layer too low — behind the `bootstrapped` gate, so it covers 0.13 s of one provider's setup
and nothing of the tap window. Option A lifts it to the capture seam, covering all three tiers and
both sources. RAM is negligible (160 KB for 5 s; 131 KB covers the measured 4.1 s worst case; the
engine already tolerates a 960 KB buffer). Two invariants must not break, and both fail silently:
`probeArm()` before the capture thread starts, and `onSessionStart` above `setActiveSource`. Two pin
tests hard-fail by design and must be rewritten
(`EndpointerLifecyclePinTest.onOpenHandsOverThisSessionsCadenceBeforeTheFirstFrame:103`,
`BackpressureWiringPinTest.theSlowRowRidesTheSameOnSessionStartCallAsTheFastRow:77`); one census pin
needs updating (`CapSeamPinTest.sendAudioIsUnconditionalAndFirst:75`); and the device-audio mic leak
that Option A newly makes reachable has **no pin today** and needs one.

**Cheaper interim, if 3–4 days is not available now:** re-arm the prewarm after `onTrimMemory`
(`:3400-3415` has no counterpart). That removes the most frequent cold window without touching the
capture seam, the endpointer clock, or a single pin — and it is worth doing anyway.
