# T0 — Gemini Live transcription protocol probes P1–P9 (PC only, observed behaviour)

Run 2026-09-10 05:54–06:40 local (UTC−4) from Windows 11, Python 3.13.7, `websockets` 16.1.1,
`google-genai` 2.22.0 as the oracle client. Model `models/gemini-3.5-transcribe-live` (confirmed in
`GET /v1beta/models`: `bidiGenerateContent` only). Endpoint
`wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent`.
No device, no adb, no gradle, no edit to the repo (read-only reads of the seam files only).

**Key discipline.** Every script reads `C:\Users\bastr\.androidbuild\gemini.key` at runtime into a local;
every byte written to stdout or a dump passes through a redactor that replaces the key with `[KEY]`;
the console pipeline additionally `sed`-redacted. §9 states the grep result.

**Artefacts.** Scripts + raw redacted JSONL dumps: `C:\Users\bastr\.androidbuild\gemini-probes\`
(`probe_common.py`, `p1_auth.py` … `p8_silence.py`, `p3b`–`p3f` follow-ups, `p6_p9_cap_stall.py`,
`p10_batch_bonus.py`, `make_spanish.py`, `list_models.py`; dumps under `dumps\*.jsonl`, one line per
timestamped event, `t` = seconds since the script's start). Test audio: the app's `jfk.wav`
(`app/src/androidTest/assets`, 16 kHz mono, 11.00 s) and `canary_digits.wav` (`app/src/main/assets`,
2.56 s), synthesized digital silence, and a Spanish clip synthesized through Gemini TTS
(`gemini-2.5-flash-preview-tts`, "Hola, buenos días. Me llamo Carlos y vivo en Madrid…", 9.17 s,
resampled 24 k→16 k; `spanish_16k.wav`). All audio streamed as 100 ms PCM16 chunks at real-time pace.

Vocabulary used below: **VA** = the server's `voiceActivity` message; **final** =
`serverContent.inputTranscription`; **interim** = `serverContent.interimInputTranscription`;
**auto VAD** = `automaticActivityDetection.disabled:false`; **manual VAD** = `disabled:true` with
client `activityStart`/`activityEnd`.

---

## 0. The eight findings that change the design

1. **Every inbound frame is a BINARY WebSocket frame** (1,542 of 1,542 across all dumps; the SDK
   reads with `recv(decode=False)` for the same reason). `RealtimeTransport.kt:300`
   (`onMessage(webSocket, bytes: ByteString) = Unit`, "No provider sends inbound BINARY — decline it")
   would drop **every** Gemini message. This is a transport/seam change, not a protocol-local one.
2. **Automatic VAD silently drops speech after the first utterance, deterministically.** On jfk
   (11 s, two sentences) it emitted the first segment and then nothing for the remaining ~4.3 s of
   speech in 12/12 sessions; over a 590 s loop it produced 47 finals of which **44 were the two words
   "Ask not."** (~90 % of each loop's speech lost, every loop, for ten minutes). It is not a stall:
   events kept arriving on schedule. Manual VAD transcribed the same audio completely, every time
   (11/11 activities across P3c/P3d/P3e, incl. a 33 s activity and two identical 11 s activities on
   one socket). Hybrid `audioStreamEnd` cannot rescue audio the server VAD never opened. **The adapter
   must run manual VAD from the app's own endpointer** — the research doc's "automatic first, hybrid
   phase 2" is inverted.
3. **A bad key closes 1007, not 1008**, after a 101 upgrade: `API key not valid. Please pass a valid
   API key.` (header and query, well-formed and malformed keys alike). No probe ever produced a non-101
   handshake status, so `classifyFatal(httpCode)` will never fire for Gemini auth; everything is a
   server-initiated close frame → `onClosing`/`onClosed`, the zero-reconnect path the doc's §3.3.9
   describes. `classifyClose` must key on the **reason text**, not the code (map in §5.3).
4. **The 10-minute cap is a GoAway at 540 s with `timeLeft:"50s"`, then a 1008 close at 590 s** (n=2, identical under auto and manual VAD):
   `Connection aborted because the client failed to close the connection after receiving a GoAway
   signal once the session durat[ion …]` (reason truncated at the 123-byte close-frame limit). The
   sibling model's "no GoAway, 523 s" regression did **not** reproduce here. An immediate reconnect
   after the cap, and three reconnects after an abrupt TCP abort, all completed setup in 0.24–0.30 s
   — no 409.
5. **Interims are cumulative whole-utterance strings** (replace mode), on a ~0.49 s cadence, with
   exact-duplicate consecutive interims; **exactly one final per activity/segment**; the final can be
   normalized differently from the last interim ("one two three four five" vs "1 2 3 4 5"). Each
   final is delivered as three back-to-back frames: `inputTranscription` → `generationComplete:true`
   → `voiceActivity ACTIVITY_END`. A turn with no speech emits **no final at all**, only the
   `ACTIVITY_END` ack — so `ACTIVITY_END` is the turn terminator and the adapter must resolve an
   open turn empty on it.
6. **Session resumption is dead on this model**: `sessionResumption:{}` is accepted, but no
   `sessionResumptionUpdate` ever arrives (10 s and 590 s runs); a bogus handle closes 1008
   `BidiGenerateContent session not found`; `transparent` is an unknown field (1007).
   `contextWindowCompression` is accepted and does nothing observable. Rotation = fresh `setup`.
7. **Language codes are accepted whatever you send** (`en`, `en-US`, `es`, `es-ES`, `de`, `xx-XX`,
   `klingon`, `["en-US","es-ES"]`, `[]`, omitted) and have **no observable effect**: `["en-US"]` and
   `["en"]` on the Spanish clip still return perfect Spanish. **`languageCode` is never populated** on
   any interim or final (0 of 1,103). The doc's "per-utterance language is free" is not true today.
8. **No pricing counters**: `usageMetadata` appeared once in the whole campaign, as an empty `{}`
   on the empty-activity edge case. Billing must be read from AI Studio (A10 stands).

Undocumented on the reference page but real on the wire and in the SDK types: a top-level
`voiceActivity {type: ACTIVITY_START|ACTIVITY_END, audioOffset: "<seconds>s"}` message that rides
with an **empty** `serverContent: {}` (reference page: `speechState` is "DEPRECATED: Use VoiceActivity
instead", nothing more; https://ai.google.dev/api/live, fetched 2026-09-10). `audioOffset` counts
audio *sent*, including audio outside activities.

---

## 1. Results table P1–P9 (+ follow-ups) — observed → design consequence

| # | Probe | Observed (timestamps are `t` in the named dump) | Design consequence |
|---|---|---|---|
| **P1** | header vs `?key=` vs none; SDK oracle | `x-goog-api-key` upgrade header: **101**, `setupComplete` 165 ms after `setup` (`p1_auth` t=0.161–0.327). `?key=`: **101**, 147 ms. No auth: 101 then close **1008** `Method doesn't allow unregistered callers (callers without established identity). Please use API Key or other form of API c` (t=15.924). SDK 2.22.0 upgrade request (spied at `google.genai.live.ws_connect`): URI `wss://generativelanguage.googleapis.com//ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent` (note the double slash — accepted), headers `Content-Type: application/json`, `x-goog-api-key: [KEY]`, `user-agent`/`x-goog-api-client: google-genai-sdk/2.22.0 gl-python/3.13.7`, **no query string**; it awaits one `recv()` before yielding the session. | Header auth is real → `upgradeHeaders(apiKey) = listOf("x-goog-api-key" to apiKey)`, `endpoint` stays a key-less constant, **zero seam change for auth**; the `endpointFor` fallback is not needed. Missing header = 1008 after 101, never an HTTP 401. |
| **P2** | bad-key surface | Well-formed bogus key (`AIzaSy`+33 chars) and malformed (`not-a-key`), header and query: all **101 then close 1007** `API key not valid. Please pass a valid API key.` 80–90 ms after `setup` (`p2_badkey` t=0.239/0.464/0.684/0.902). Malformed `setup` (unknown field): **1007** `Invalid JSON payload received. Unknown name "bogusField" at 'setup': Cannot find field.` Unknown model: **1008** `models/gemini-nope-live is not found for API version v1beta, or is not supported for bidiGenerateContent. Call ModelService`. Audio before `setup`: **1007** `Request contains an invalid argument.` Socket open with nothing sent: no close within 12 s. Every close was server-initiated (`rcvd_then_sent: true`). | The doc's `1008+"api key" → INVALID_KEY` misses that the key error is **1007**. Match on reason text (§5.3). All fatal surfaces arrive on `onClosing/onClosed`, so the §3.3.9 seam fix (`classifyClose`, reconnect on transient close) is mandatory, and `onFatal` for INVALID_KEY must come from the close path. Pre-validating the key over REST remains cheap insurance but is no longer needed to disambiguate a 400. |
| **P3** | interim shape, jfk × 5 | 5/5 identical: 12 interims, all cumulative (11/11 prefix-extensions, 11/11 non-shrinking), 6 exact duplicates of the previous interim; **1 final** `And so, my fellow Americans, ask not what your country` at `ACTIVITY_END audioOffset 6.680s`; the remaining 4.3 s of speech (`can do for you, ask what you can do for your country`) produced **no** event in any run despite 3 s of trailing silence audio + 6 s wait. Latency: first interim 0.97 s p50 / 1.01 s p95 after speech onset; interim cadence 0.49 s p50 (0.38–0.60); final 0.19 s p50 / 0.30 s p95 after the chunk at the `ACTIVITY_END` offset was sent. `languageCode` absent everywhere. No final ever arrives if audio simply stops (P1 canary: interims only) — the server VAD needs the silence *as audio*. | Replace-mode preview (`onDelta("", text)`), dedupe identical interims, one `onCommitted+onCompleted` per final — as the doc mapped. But the dropped second sentence is the P3b–P3e story: **the mapping only works under manual VAD**. |
| **P3b** | why the second utterance vanishes (9 variants, `p3b_second_utterance`) | jfk+3 s+jfk: finals `…what your country` and **`Ask not.`** (jfk#2's opening and ending lost). Explicit `silenceDurationMs:500,prefixPaddingMs:100`: `…Americans,` / `Ask not.` then **nothing for jfk#2 at all**. Three canary clips with 2 s gaps: 3/3 correct. `clientContent.turnComplete` after the final: no help. SDK-shaped setup: same as V1. 15 s wait: no late final. 4 s stream gap (no audio): no help. jfk+1 s+canary: canary fine. jfk[7.5s:] alone on a fresh socket: `Ask what you can do for your country.` (fine). | Deterministic (identical `audioOffset`s every run), content-dependent, not time/level-dependent, not fixable from the client under auto VAD. |
| **P3c** | manual vs auto (`p3c_vad_modes`) | **Manual** `activityStart` + jfk + `activityEnd`, twice on one socket: both finals the full sentence `And so, my fellow Americans, ask not what your country can do for you, ask what you can do for your country.`, final 0.31 s / 0.25 s after `activityEnd`, 21 cumulative interims. Manual split at 6.5 s: `…what your country` / `can do for you, ask what you can do for your country.` Auto with −1 dBFS-normalized jfk, `START_SENSITIVITY_HIGH`+`END_SENSITIVITY_LOW`, and −50 dBFS noise in the gaps: all identical to V1 (`Ask not.`). **Hybrid** (auto VAD + `audioStreamEnd` at the 6.4 s and 8.35 s pauses): first final `…ask not what your`, then **nothing** for either later segment. | Manual VAD is the only mode that transcribes everything. Hybrid is not a fallback. |
| **P3d** | manual-mode rules (`p3d_manual_robustness`) | Audio outside an activity: silently ignored, never transcribed, but `audioOffset` advances (consumed). One 33 s activity (jfk×3): one correct final for all three sentences. **Empty activity** (`activityStart` immediately followed by `activityEnd`, no audio): server acks both, sends `{"serverContent":{"turnComplete":true},"usageMetadata":{}}`, then closes **1007 `Precondition check failed.`** Nested `activityStart`: ignored, both clips merged into one final. Five activities with 0 ms gaps: only 3 finals, two with bleed (`5 1 2 3 4 5`). | Never send audio outside an activity (cost, and it is useless). Never send an empty activity (fatal close). Never nest. See P3e for the gap. |
| **P3e** | minimum `activityEnd`→next `activityStart` gap (`p3e_activity_gap`) | 0 ms: 2/4 clean; 100 ms: 1 merged final; 250 ms: 1 merged + 1 clean; **500 ms: 4/4**; 1000 ms: 4/4; **ack-gated (wait for `ACTIVITY_END` VA): 4/4**, ack never timed out. | Gate the next `activityStart` on the server's `ACTIVITY_END` (arrives 0.26 s p50 / 0.42 s p95 after `activityEnd`), fallback 500 ms; buffer the new turn's audio until the start is sent. |
| **P3f** | speech-less activity (`p3f_silent_activity`) | 2 s silence, 0.3 s silence, 2 s −50 dBFS noise inside an activity: `ACTIVITY_START` + `ACTIVITY_END` acks only — **no interim, no final**, socket stays healthy, next canary transcribes. | On `ACTIVITY_END` with no final since `activityEnd`: `onCommitted(id)` + `onCompleted(id, "")` → `EmptyExpected`, or the engine's seq strands. |
| **P4** | `audioStreamEnd` | After the full jfk: `audioStreamEnd` flushed nothing new (the second sentence was already lost). Then canary on the same socket: `ACTIVITY_START audioOffset 11.600s`, interims, final `1 2 3 4 5` — **audio resumes after `audioStreamEnd`**. Canary + immediate `audioStreamEnd`: final in **0.25 s**. `audioStreamEnd` at 5 s mid-utterance: final `…ask not` in 0.41 s, `ACTIVITY_END audioOffset "5s"`; the remaining 6 s + 2.5 s silence: nothing. | Yes, the socket survives `audioStreamEnd` and it flushes a final fast (0.25–0.42 s, n=3) — but it belongs to the auto/hybrid design, which is rejected. Not used. |
| **P5** | session resumption | `"sessionResumption":{}` → `setupComplete {}`; **zero `sessionResumptionUpdate`** over a full turn (and zero over 590 s in the baseline long run). Bogus handle → **1008 `BidiGenerateContent session not found`** at setup. `{"transparent":true}` → **1007 `Unknown name "transparent" at 'setup.session_resumption'`**. `contextWindowCompression:{slidingWindow:{}}` → accepted, no effect. `setupComplete` is always `{}` (no `sessionId`). | Drop the handle machinery entirely (not even phase 2). Every open sends a full `setup`; the ledger is ours — the doc's rotation design already assumed this. A handle field in `reset()` is dead code. |
| **P6** | the ~10-minute close (`p6_p9_cap_stall_baseline`) | Opened t=0.185 (setup RTT 0.169 s). **`{"goAway":{"timeLeft":"50s"}}` at 540.16 s** after open. Server close at **590.22 s**: **1008** `Connection aborted because the client failed to close the connection after receiving a GoAway signal once the session durat` (truncated). 48 loops, 590 s of audio. Immediate reconnect: `setupComplete` in 0.238 s. Abrupt TCP abort (no close frame) then reconnect ×3: 0.289 / 0.296 / 0.261 s — **no 409**. | Reactive rotation on GoAway has a **50 s** window; rotate at the next activity boundary (`ACTIVITY_END` received) and no later than ~`timeLeft − 10 s`. Keep a proactive deadline as belt-and-braces (the sibling model regressed to no-GoAway on the forum), e.g. rotate at the first boundary after **570 s** if no GoAway has arrived — the doc's ≤500 s is 40 s of margin against data that did not reproduce; 570 s still leaves 20 s. Close 1008 with "GoAway" in the reason → transient (reconnect), never a latch. The 409 orphan story did not reproduce; `409 → null` stays harmless. |
| **P7** | language codes | Setup accepted for **every** value tried: `[]`, omitted, `["es"]`, `["es-ES"]`, `["en"]`, `["en-US"]`, `["de"]`, `["en-US","es-ES"]`, `["xx-XX"]`, `["klingon"]`. Spanish clip → identical, correct Spanish final (`Hola, buenos días. Me llamo Carlos y vivo en Madrid. Hoy hace mucho calor y por la tarde voy a la playa con mis amigos.`) under auto, `es`, `es-ES`, **`en-US`, `en`** and the pair. jfk under `de` → English. `languageCode` never present (0/1,103 transcription events). | Send the bare ISO code (`["de"]`) — no region table, no exceptions map, no server-side validation to worry about (invalid codes are swallowed). Treat it as a hint with no proven effect; do not promise per-utterance language detection in copy; the "route by detected language" feature has **no data source** on this model today. (Dump stores proper UTF-8: `días` verified.) |
| **P8** | 60 s digital silence then speech | 600 chunks of zeros: **zero events**, no VA, no close, socket open (websockets pinged every 20 s). Then jfk: `ACTIVITY_START audioOffset 60.440s` 0.48 s after the first speech chunk, normal interims, final `…what your country` (and, as always under auto VAD, the second sentence lost). | No hallucination on silence; no idle timeout at 60 s; a 20 s ping keepalive is enough (the app's OkHttp client already pings at 20 s). Under manual VAD the app will not send silence at all. |
| **P9** | silent stall over 10 min (same run as P6) | 291 VA/interim/final events over 590 s; **every loop produced its final** (47/47 completed loops), zero zero-interim loops; inter-event gap p50 0.50 s, max **11.09 s** — which is the loop period (11 s jfk + 1.5 s) minus the one short surviving segment, i.e. auto-VAD deafness, not a stall. Last event 9.4 s before the close (the loop's silent tail). Manual-mode long run: §7 — identical cap timing, 47/47 complete finals, max gap 1.58 s. | No silent stall reproduced in 590 s. Keep a watchdog but make it crisp under manual VAD: `activityStart` is acked by `ACTIVITY_START` within ~40 ms and `activityEnd` by a final/`ACTIVITY_END` within 0.44 s max (n=28) — **"no `ACTIVITY_END` within 3 s of `activityEnd`" → rotate**; the doc's "8 s of speech with no inbound" rule is unnecessary and would have false-fired constantly under auto VAD. |
| **P10** (bonus, the map's parked §3 question) | batch `gemini-3.5-transcribe` over REST | `:generateContent` + `inlineData` WAV: **HTTP 200, no candidates text**, usage 275 audio tokens in / 0 out. `/v1beta/interactions` with inline audio: **HTTP 200, `output_text` null**, 275 audio tokens, `total_output_tokens: 0` (a hidden 411-token text prompt is visible in `raw_prompt_token`). | The forum's empty-output report reproduces today. Spec §3's batch swap stays parked; `GeminiStt` stays on `gemini-3.6-flash`. |

---

## 2. Corrections to the research doc §3.2 / §3.3 (what the doc got wrong or must change)

1. **`onText` is not enough — inbound is BINARY** (§3.2 `onText`; `RealtimeTransport.kt:300`). Add a
   default-body member `fun onBinary(bytes: ByteString) = Unit` to `RealtimeProtocol` and have the
   transport call it from `onMessage(webSocket, bytes)`; Gemini overrides it with
   `onText(bytes.utf8())`. (Or decode in the transport for all protocols — simpler, but it retires the
   "decline binary" invariant the three shipped providers rely on as a tripwire.) Without this the
   adapter receives nothing, including `setupComplete`, and the 2 s ring overflows on every open.
2. **§3.3.2 "VAD: automatic first, hybrid as phase 2" is inverted.** Automatic VAD drops most of the
   speech after the first segment on real recorded audio; hybrid cannot recover it. The first cut is
   **manual VAD** driven by the app's endpointer: `LiveTurnPolicy.runClientVad` must return `true` for
   the Gemini live session (a per-provider predicate — the cost the doc priced as "phase 2"), the
   engine is built with `serverDriven = false` (the client-VAD ledger the engine keeps and tests,
   `LiveTranscriptionEngine.kt:75-86`), and the protocol:
   - `onAppend(pcm)`: if no activity is open and the post-`activityEnd` gate is clear, send
     `{"realtimeInput":{"activityStart":{}}}` first (lazy open on the first frame of a turn, so the
     app's pre-roll lands *inside* the activity); then the audio frame. If the gate is not clear (the
     previous `ACTIVITY_END` ack has not arrived and < 500 ms have passed), buffer the frames (a few
     hundred ms) and flush them after the deferred `activityStart`. Never send audio outside an activity.
   - `onCommit()`: send `{"realtimeInput":{"activityEnd":{}}}`, arm the ack gate, return `true`.
   - The engine's `minCommitBytes` (100 ms) gate means a too-short turn sends no `onCommit` → the
     activity stays open and folds into the next turn; ≤ 100 ms of audio may then be both locally
     rescued and included in the next final — sub-word, acceptable, but pin it in a test.
   - `onCommit` is called from the sender loop after all the turn's appends (`LiveTranscriptionEngine.kt:290-320`),
     so ordering `activityStart → audio → activityEnd` is guaranteed by the existing queue.
3. **§3.2 inbound dispatch — the boundary is `ACTIVITY_END`, not the final alone.** Keep the ElevenLabs
   shape for a final (`onCommitted(id)` then `onCompleted(id, text)`; with `serverDriven=false`,
   `onCommitted` binds the oldest unbound seq — the one `commit()` registered — and `onCompleted`
   resolves it, `LiveTranscriptionEngine.kt:343-364`). Add: on `voiceActivity ACTIVITY_END` with no
   final since the last `activityEnd` → `onCommitted(id)` + `onCompleted(id, "")` (→ `EmptyExpected`,
   `:418-432`). Ignore `ACTIVITY_START` except to clear the ack gate and stamp liveness. Ignore
   `generationComplete`, `turnComplete`, `modelTurn` (never seen; keep the rule).
4. **§3.2 `classifyClose` map is wrong on codes.** Observed: bad key = **1007**; no auth = 1008;
   unknown model = 1008; malformed setup / audio-before-setup / empty activity = 1007; bogus resumption
   handle = 1008; cap = 1008 with "GoAway" in the reason. Reasons are truncated at 123 bytes. Map on
   case-insensitive reason prefixes (§5.3), with the code as a tie-breaker only.
5. **§3.2 `classifyFatal(code)` is effectively dead for Gemini** — no probe produced a non-101
   handshake. Keep the table for completeness; do not build behaviour on it.
6. **§3.3.10 rotation numbers.** GoAway is real and arrives at 540 s with 50 s of grace; the close is
   at 590 s. Proactive deadline can move from ≤500 s to ~570 s (or stay at 500 s if the owner prefers
   the sibling-model margin; both are safe, the difference is one extra rotation per ~1.6 h). Rotate at
   an activity boundary (after `ACTIVITY_END`), never mid-activity — a mid-activity rotation loses the
   open turn to the local fallback (acceptable, but avoidable with the 50 s window).
7. **§3.3.10/§3.2 resumption handle: delete.** No handle is ever issued. `reset()` has nothing to clear.
8. **§3.2 `languageCode` "free and homeless" → not free: never sent.** The `SegmentOutcome` side-channel
   discussion is moot until the model populates it. Language hints have no proven effect; keep the bare
   code wiring for the day they do.
9. **§3.3.11 watchdog**: retarget from "8 s of speech-energy frames with no inbound" to the manual-VAD
   acks: `activityStart` → `ACTIVITY_START` within ~1 s, `activityEnd` → final/`ACTIVITY_END` within
   3 s; either miss → `rotate()`. Silence never triggers it because silence is never sent.
10. **§3.3.5 ready gate**: setup RTT is 0.13 s p50 / 0.17 s p95 / 0.22 s max (n=58), so the 2 s ring is
    ample; under manual VAD the ring only ever holds the first turn's opening frames.
11. **§3.4 cost shape**: under manual VAD the app sends **only speech** (audio outside activities is
    ignored anyway), so the "open mic bills wall-clock" paragraph and the "do not gate audio" warning
    no longer apply to Gemini — the row becomes the cheapest-shaped one, not the priciest. A10 must
    still be read from AI Studio: no `usageMetadata` is emitted.
12. **§3.2 `bootstrap` pinned setup shape** must carry `"realtimeInputConfig":{"automaticActivityDetection":{"disabled":true}}`
    (§5.1). `silenceDurationMs` is irrelevant under manual VAD.
13. **§3.1 chunking**: 100 ms chunks worked throughout; pairing 32 ms frames to 64 ms was not tested and
    is not needed for correctness — keep it only for envelope overhead.

---

## 3. Latency and counters (all from the dumps; wall-clock, PC on residential fibre)

| Metric | Value |
|---|---|
| `setup` → `setupComplete` | p50 0.132 s, p95 0.170 s, max 0.216 s (n=58) |
| Upgrade (TCP+TLS+101) | ~0.11–0.16 s |
| Speech onset → first interim (auto VAD, jfk) | p50 0.97 s, p95 1.01 s (n=5) |
| Interim cadence | p50 0.49 s (0.38–0.60 s, n=55) |
| Final after the `ACTIVITY_END`-offset chunk was sent (auto VAD) | p50 0.19 s, p95 0.30 s (n=5) |
| `activityEnd` → final (manual VAD) | **p50 0.26 s, p95 0.42 s, max 0.44 s (n=28)** |
| `activityStart` → `ACTIVITY_START` ack | ~35–45 ms |
| `audioStreamEnd` → final | 0.25 / 0.42 / 0.35 s (n=3) |
| Reconnect after cap / after TCP abort (upgrade + setup) | 0.24–0.30 s |
| `usageMetadata` | never populated (one empty `{}` on an error path) |
| Free-tier concurrency | up to 2 concurrent sessions used without incident; 3 not tried |

---

## 4. Exact frames (redacted) an implementer needs

### 4.1 Outbound

Upgrade request (what worked, and what the SDK sends):
```
GET /ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent HTTP/1.1
Host: generativelanguage.googleapis.com
x-goog-api-key: [KEY]
(Upgrade/Connection/Sec-WebSocket-* as usual; no query string)
```
Query form also works: `…BidiGenerateContent?key=[KEY]` — but the header is the one to use.

Setup, **manual VAD** (the recommended pinned shape; `languageCodes` omitted for auto, `["de"]` for a
selection):
```json
{"setup":{"model":"models/gemini-3.5-transcribe-live","generationConfig":{"responseModalities":["TEXT"]},"inputAudioTranscription":{"mode":"VERBATIM"},"realtimeInputConfig":{"automaticActivityDetection":{"disabled":true}}}}
```
Setup as sent by Google's SDK (for reference; auto VAD by omission):
```json
{"setup":{"model":"models/gemini-3.5-transcribe-live","generationConfig":{"responseModalities":["TEXT"]},"inputAudioTranscription":{}}}
```
Activity boundaries and audio (100 ms = 3,200 B PCM16 → 4,268 base64 chars):
```json
{"realtimeInput":{"activityStart":{}}}
{"realtimeInput":{"audio":{"data":"<base64 pcm16le 16k mono>","mimeType":"audio/pcm;rate=16000"}}}
{"realtimeInput":{"activityEnd":{}}}
```
Not used by the design but verified: `{"realtimeInput":{"audioStreamEnd":true}}`;
`{"clientContent":{"turnComplete":true}}` is accepted and does nothing useful.

### 4.2 Inbound (each one a BINARY frame carrying UTF-8 JSON)

```json
{"setupComplete":{}}
{"serverContent":{},"voiceActivity":{"type":"ACTIVITY_START","audioOffset":"0.440s"}}
{"serverContent":{"interimInputTranscription":{"text":"And so"}}}
{"serverContent":{"interimInputTranscription":{"text":"And so, my fellow Americans, ask not what your"}}}
{"serverContent":{"inputTranscription":{"text":"And so, my fellow Americans, ask not what your country"}}}
{"serverContent":{"generationComplete":true}}
{"serverContent":{},"voiceActivity":{"type":"ACTIVITY_END","audioOffset":"6.680s"}}
{"goAway":{"timeLeft":"50s"}}
```
Edge frames: `{"serverContent":{"turnComplete":true},"usageMetadata":{}}` (empty activity, precedes
the 1007 close). `audioOffset` strings look like `"0s"`, `"5s"`, `"11.600s"`, `"555.480s"` — a
protobuf Duration, integer seconds carry no decimals.

### 4.3 Close frames (all server-initiated after a 101; reason ≤ 123 bytes)

| Trigger | Code | Reason (verbatim, as truncated on the wire) |
|---|---|---|
| wrong key (any form, header or query) | **1007** | `API key not valid. Please pass a valid API key.` |
| no key at all | 1008 | `Method doesn't allow unregistered callers (callers without established identity). Please use API Key or other form of API c` |
| unknown model id | 1008 | `models/gemini-nope-live is not found for API version v1beta, or is not supported for bidiGenerateContent. Call ModelService` |
| unknown field in `setup` | 1007 | `Invalid JSON payload received. Unknown name "bogusField" at 'setup': Cannot find field.` |
| audio before `setup` | 1007 | `Request contains an invalid argument.` |
| empty activity (`activityStart` then `activityEnd`, no audio) | 1007 | `Precondition check failed.` |
| bogus resumption handle | 1008 | `BidiGenerateContent session not found` |
| unknown field in `sessionResumption` | 1007 | `Invalid JSON payload received. Unknown name "transparent" at 'setup.session_resumption': Cannot find field.` |
| the 10-minute cap (50 s after GoAway) | **1008** | `Connection aborted because the client failed to close the connection after receiving a GoAway signal once the session durat` |
| client `close(1000)` | — | server completes the handshake, no reason |

---

## 5. Recommended `RealtimeProtocol` mapping for `GeminiRealtimeProtocol` (supersedes the doc's §3.2 table)

### 5.1 Members

| Member | Gemini |
|---|---|
| `endpoint` | the constant BidiGenerateContent URL, no key |
| `upgradeHeaders(apiKey)` | `listOf("x-goog-api-key" to apiKey)` (verified) |
| `tolerant4xxRetry` | `false` |
| `bootstrap(apiKey, language)` | one `Frame.Text`: the §4.1 manual-VAD setup; `"languageCodes":["<bare>"]` when a language is selected, omitted for auto; resets: ready gate, pre-setup ring, `activityOpen=false`, ack gate, `lastFinal=null`, `openedAtNanos` |
| `onAppend(pcm16k)` | if `!ready` → ring (2 s cap) and `true`; else if `!activityOpen` → (if the ack gate is armed and < 500 ms since `activityEnd` and no `ACTIVITY_END` yet → buffer, `true`) else send `activityStart`, `activityOpen=true`; then send the audio frame (pair to 64 ms if desired). Stamp `lastAppendNanos`. Proactive rotation check here (§5.4). |
| `onCommit()` | if `activityOpen`: send `activityEnd`, `activityOpen=false`, arm the ack gate + the 3 s watchdog; return the send result. If no activity is open (too-short turn never appended? — cannot happen: `commit()` only enqueues after `turnHasAudio`), return `true`. |
| `onBinary(bytes)` **(new default-body member)** | `onText(bytes.utf8())` |
| `onText(text)` | §5.2 |
| `classifyFatal(code)` | keep the doc's table; it will not fire in practice |
| `classifyClose(code, reason)` **(new default-body member)** | §5.3 |
| `reset()` | clear ring, buffers, gates, `lastFinal`, preview; nothing else to hold |

### 5.2 Inbound dispatch

| Inbound | Action | Listener |
|---|---|---|
| `setupComplete` | `ready=true`; flush the ring outside the lock (an `activityStart` is emitted by the first flushed append) | — |
| `voiceActivity ACTIVITY_START` | clear the start watchdog | — |
| `serverContent.interimInputTranscription.text` | preview = text (replace); skip if identical to the previous | `onDelta("", text)` |
| `serverContent.inputTranscription.text` | `lastFinal = text`; `id = ids.incrementAndGet()` | `onCommitted(id)` then `onCompleted(id, text)` |
| `voiceActivity ACTIVITY_END` | clear the ack gate + watchdog; if no final arrived since `activityEnd` → `id` fresh | `onCommitted(id)`, `onCompleted(id, "")` (→ `EmptyExpected`) only in the no-final case; else nothing |
| `serverContent.generationComplete` / `turnComplete` / `modelTurn` / `interrupted` / `waitingForInput` | ignore | — |
| `goAway.timeLeft` | mark `rotateAtNextBoundary=true`; if no activity is open, `control.rotate()` now; else rotate right after the next `ACTIVITY_END` (and unconditionally at `timeLeft − 10 s`) | — |
| `sessionResumptionUpdate` / `usageMetadata` | ignore (never/empty) | — |
| anything else / malformed | ignore | — |

### 5.3 `classifyClose(code, reason)` (reason lower-cased, prefix/contains match; code secondary)

| Reason contains | → |
|---|---|
| `api key not valid` | `INVALID_KEY` (code 1007) |
| `unregistered callers` | `INVALID_KEY` (the header was not sent — our bug in practice; 1008) |
| `is not found for api version` / `not supported for bidigeneratecontent` | `MODEL_UNAVAILABLE` (1008) |
| `invalid json payload` / `invalid argument` / `precondition check failed` | `MODEL_UNAVAILABLE` (our payload; 1007) — latch visibly, cannot self-heal |
| `quota` / `resource_exhausted` / `rate limit` | `OUT_OF_CREDIT` (not observed; keep) |
| `goaway` / `session durat` / `aborted` | `null` → reconnect (the cap; 1008) |
| 1000 / 1001 / 1006 / 1011–1013, anything else | `null` → reconnect |

The reason text never crosses the seam or a log line (Soniox rule); the rules are pinned by tests.

### 5.4 Timers (no threads; checked in `onAppend`/`onText`)

- Rotate on GoAway at the next `ACTIVITY_END`, hard stop at `timeLeft − 10 s`.
- Proactive rotation at ~570 s of connection age at the next boundary (owner may prefer 500 s).
- Watchdog: `activityEnd` sent and no final/`ACTIVITY_END` within 3 s → `rotate()`; `activityStart`
  sent and no `ACTIVITY_START` within 3 s → `rotate()`. Cancel the old socket rather than close it
  (the §3.3.11 clobber hazard stands).

### 5.5 Service / engine wiring the doc did not price

- `LiveTurnPolicy.runClientVad(sessionIsLive)` → per-provider: `true` for Gemini live.
- Build `LiveTranscriptionEngine(serverDriven = false)` for Gemini; the CLOUD_LIVE arm currently
  passes the server-driven flag for all three providers.
- The endpointer's hangover (350 ms) decides the activity cut; Gemini adds 0.26–0.42 s to the final.
- `RealtimeTransport`: `onMessage(bytes)` → `protocol.onBinary(bytes)`; `onClosing/onClosed` →
  `classifyClose` (fatal → `onFatal`, transient → `onDisconnected()` + `scheduleReconnect()`).

---

## 6. Limits of this campaign (say so before code)

- Every drop was reproduced on **jfk** (an old, noisy recording) and on nothing clean: the canary and
  the TTS Spanish clip were never dropped under auto VAD. A phone mic in a room is closer to jfk than to
  TTS. Manual VAD sidesteps the question entirely, which is the point.
- The cap (P6) is n=2 (one run per VAD mode), both on the same day and key; the timing may still drift with Google's fleet.
- Free-tier key behaviour; a paid-tier key may differ in caps/limits (the docs say the cap is per model).
- Latencies are from a PC on fibre; the phone adds radio RTT.
- `voiceActivity` is undocumented on the reference page; treat its shape as observed-2026-09-10 and
  parse it leniently.

---

## 7. Manual-VAD long run (P6/P9 under the recommended mode) — `p6_p9_cap_stall_manual`

Same loop as the baseline but as the adapter will run it: `activityStart` + jfk (11 s) + `activityEnd`,
gated on the `ACTIVITY_END` ack, then 1.5 s with **no audio sent**, repeated.

| Metric | Manual VAD | Automatic VAD (baseline) |
|---|---|---|
| GoAway | **540.12 s**, `timeLeft:"50s"` | 540.16 s, `timeLeft:"50s"` |
| Server close | **590.17 s**, 1008, same "GoAway" reason | 590.22 s, 1008 |
| Loops / finals | 48 started, **47 finals, 47/47 = the complete sentence verbatim** | 48 / 47, 44 of them "Ask not." |
| `ACTIVITY_END` acks | 47/47, **0 ack timeouts** | — |
| Interims | 990 (≈21 per loop, cumulative) | 150 |
| Inter-event gap | p50 0.49 s, p95 1.04 s, p99 1.55 s, **max 1.58 s** (the deliberate 1.5 s idle) | max 11.09 s (deafness) |
| Last event before close | 0.38 s | 9.36 s |
| Reconnect after the cap | `setupComplete` in 0.253 s | 0.238 s |
| Reconnect after abrupt TCP abort ×3 | 0.241 / 0.279 / 0.253 s | 0.289 / 0.296 / 0.261 s |
| `sessionResumptionUpdate` / `usageMetadata` | none / none | none / none |

**P6 verdict (n=2, both modes):** GoAway at 540 s ± 0.05 s, close at 590 s ± 0.05 s, code 1008, reason
prefixed `Connection aborted because the client failed to close the connection after receiving a GoAway
signal`. **P9 verdict:** no silent stall in 2 × 590 s; under manual VAD every activity was acknowledged
and transcribed completely for the whole connection lifetime. Audio billed under manual mode is speech
only (528 s of the 590 s here, because the loop idles 1.5 s per 12.5 s; a dictation session idles far more).

---

## 8. Sources fetched today (2026-09-10)

- https://ai.google.dev/gemini-api/docs/live-api/live-transcribe (page dated 2026-08-26): manual VAD =
  "Set to `true` to disable automatic voice activity detection and manually send `activityStart` and
  `activityEnd` signals"; "Live transcription sessions support continuous streaming for up to 10
  minutes"; "Send audio in chunks of 100ms (1,024 to 2,048 frames)". No mention of `voiceActivity`.
- https://ai.google.dev/api/live: `BidiGenerateContentServerMessage` lists `usageMetadata, setupComplete,
  serverContent, toolCall, toolCallCancellation, goAway, sessionResumptionUpdate`; `speechState` is
  "DEPRECATED: Use VoiceActivity instead" with no definition; frame type (binary/text) unspecified.
- Installed SDK source `google/genai/live.py` (2.22.0): URI built with no query string,
  `additional_headers` carrying `x-goog-api-key`, one `recv()` awaited after `setup`; `types.py` defines
  `VoiceActivity{voice_activity_type, audio_offset}` and `LiveServerMessage.voice_activity`.

## 9. Key hygiene

The key was read from `C:\Users\bastr\.androidbuild\gemini.key` at runtime by each script and never
printed, logged, or stored anywhere else; every dump line and console line was redacted before being
written (`[KEY]` appears exactly where the query URL and the SDK's upgrade header were recorded).
Final grep (`grep -r -F -c`) over `C:\Users\bastr\.androidbuild\gemini-probes\` (scripts, stdout captures,
`dumps\*.jsonl`) and this report, after the last dump was closed and again after this section was
written: matching lines for the key's first 8 characters = **0**, first 12 characters = **0**,
the full 39-character key = **0**. (The only `AIza…` strings in any dump are the deliberately
bogus `AIzaSyAAAA…` key from P2, which does not share the real key's 8-character prefix.)
