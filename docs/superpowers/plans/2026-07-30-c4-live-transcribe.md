# C4 — word-for-word live transcription over the Realtime WebSocket

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a THIRD mic-only STT mode — **"Cloud word-for-word (OpenAI) · about $0.017/min"** — that streams capture over an OpenAI Realtime WebSocket (`gpt-live-transcribe`) so the user sees partial text render as they speak. Partials (`delta`) surface ONLY on a live preview strip; each finished turn (`completed`) resolves through the EXISTING `TranscriptionEngine` seq/orderer contract and injects via the SAME full-field read-modify-write path as batch. On-device stays default; batch-POST cloud stays untouched; TTS untouched. The live engine slots in as the `cloud` inside the existing `FallbackTranscriptionEngine(cloud, local, serviceScope)` — a dropped socket resolves its turn `Lost` and the untouched fallback rescues it locally from the mirrored PCM.

**Architecture:** One new `TranscriptionEngine` implementation (`LiveTranscriptionEngine`) fronted by one thin OkHttp WebSocket wrapper (`RealtimeTransport`). `sendAudio` appends to a buffer on the hot capture thread and returns — a sender coroutine drains, upsamples 16k→24k, base64-encodes, and sends `input_audio_buffer.append`. `commit()` cuts the buffer synchronously under the caller's lock (as batch does — the fallback's mirror↔seq pairing depends on it), sends `input_audio_buffer.commit`, allocates the monotonic seq, and records the pending turn. `completed`/`delta` events arrive keyed by `item_id`; the engine maps `item_id→seq` (ordering between turns is NOT guaranteed — pinned by tests) and resolves each seq **exactly once**. No routing change: it becomes the `micEngine`'s `cloud` at `FloatingBubbleService.kt:1561`.

**Tech Stack:** Kotlin 2.0.21, OkHttp 4.12.0 (**pinned** — its WebSocket API is the transport), kotlinx-serialization-json 1.7.3, `java.util.Base64`, JUnit 4. **No new dependencies.**

## Global Constraints (binding — carried from C1/C2a/C2b/C3 + the live-doc verification 2026-07-30)

- **Deltas go ONLY to the preview surface — NEVER partial injection (Spec §5.6a is law).** Injection is a full-field RMW; a partial injected then revised corrupts the field. Only `completed` turns, resolved once through the orderer, ever inject.
- **Live mode is MIC-ONLY by construction.** It plugs in as the `micEngine`; the router keeps the mic engine CLOSED and buffer-free while source is PLAYBACK — device audio is physically unreachable from it.
- **Same consent triad + disclosure v3** as cloud STT — same mic audio, same provider, so **NO new data class and NO version bump**. Live adds only a new transport + a new COST tier ($0.017/min ≈ 4× batch). The mode row shows the price; **no speed claims — "word-for-word as you speak", never "faster".**
- **Every seq resolves exactly once** — on delta, completed, WS drop, reconnect, close, AND the out-of-order-completion case OpenAI documents (`item_id`↔seq). A dropped WS mid-turn resolves that turn `Lost` so the existing fallback rescues it locally. Verify the wrapping works; do NOT reimplement rescue.
- **The capture thread never blocks.** WS send is off-thread (buffer + draining sender). Backpressure: if the socket can't drain, degrade to `Lost`-per-turn (fallback rescues) — never block capture.
- **No credential, transcript, or audio in logcat.** Handshake failures log status code ONLY. The API key goes in the `Authorization: Bearer` header of the upgrade `Request` ONLY.
- **24 kHz pcm16.** Upsample the 16 kHz capture with the existing `ElevenLabsTts.upsample16kTo24k` math (lift to `Resampler` — it is `ShortArray`→`ShortArray`; capture is `ByteArray`, convert via `PcmBytes.toShortArrayLE`).
- **`org.json` BANNED / `android.util.Base64` BANNED** (`unitTests.isReturnDefaultValues = true`). Use `kotlinx.serialization` + `java.util.Base64`. Framework classes (`WebSocket` impl, `android.util.Log`) untestable in JVM — keep them out of pure-logic units; inject a `WebSocket` factory fake.
- **`java` NOT on PATH.** PowerShell: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio1\jbr"`; `.\gradlew.bat --no-daemon`. `assembleRelease` must stay green (R8). **NEVER `connectedAndroidTest`/`installDebug`** — instrumented = compile-check only.
- **Branch `main`. Commit ONLY named files, never `git add -A`. Retry once on `index.lock`.**
- **Baseline: 548 tests / 0 failures, HEAD `2aad53e`**, `assembleDebug` + `assembleRelease` both green.

---

## Pinned protocol facts (verified against live docs 2026-07-30 — see `c4-realtime-protocol-notes.md`)

- **BYOK streaming is officially supported for native clients.** A phone holding the user's own key is the "server-side" case: `Authorization: Bearer <key>` on the WS upgrade. Ephemeral client secrets are BROWSER-only. **Gemini stays blocked** (its Live API wants ephemeral tokens); this mode is **OpenAI-only**.
> Superseded 2026-07-31 by docs/superpowers/plans/2026-07-31-realtime-all-providers.md (realtime now all streaming-capable providers).
- **Connect:** `wss://api.openai.com/v1/realtime?intent=transcription`, then send `session.update`. Omit `OpenAI-Beta: realtime=v1`; add it only if the handshake 4xx's (tolerant connector). No documented duration limit — plan reconnect-on-drop anyway.
- **Session config:** `{ "type":"session.update", "session":{ "type":"transcription", "audio":{ "input":{ "format":{ "type":"audio/pcm","rate":24000 }, "transcription":{ "model":"gpt-live-transcribe" }, "turn_detection": null } } } }`. `turn_detection:null` = WE commit turns → maps 1:1 onto the existing client VAD; `commit()` sites unchanged (no double-VAD).
- **Messages:** append `{ "type":"input_audio_buffer.append","audio":"<base64 pcm16>" }`; delta `conversation.item.input_audio_transcription.delta` (`item_id`,`delta`); completed `conversation.item.input_audio_transcription.completed` (`item_id`,`transcript`); error events carry the fatal (401/quota). **"Ordering between completion events from different speech turns isn't guaranteed. Use item_id."**
- **Cost:** `gpt-live-transcribe` **$0.017/min** (≈4× batch). Surface where selected; no speed claims.

---

## File Structure

| File | Change |
|---|---|
| `recording/SampleMath.kt` | **+** `Resampler.upsample16kTo24k(ShortArray): ShortArray` (lift from `ElevenLabsTts`; leave a delegating alias there) |
| `transcription/live/RealtimeTransport.kt` | **NEW** — OkHttp WS wrapper: connect, `session.update`, append/commit, typed event parse, reconnect+backoff, `WebSocket` factory injected |
| `transcription/live/RealtimeEvents.kt` | **NEW** — `@Serializable` outbound/inbound event models + `RealtimeEventParser` (pure) |
| `transcription/live/LiveTranscriptionEngine.kt` | **NEW** — `TranscriptionEngine` impl: off-thread sender, `item_id→seq` map, exactly-once, `lastFatal()` |
| `transcription/FloatingBubbleService.kt` | `EngineChoice.CLOUD_LIVE` branch; build `Fallback(Live, local)` as `micEngine`; lift the `sessionContext != TEXT_FIELD` delta gate for live only |
| `data/local/PreferencesManager.kt` | **+** `sttLiveMode: Boolean` (`KEY_STT_LIVE_MODE`) — the new batch-vs-live axis |
| `ui/screens/CloudProvidersScreen.kt` | live-mode selector row (OpenAI-only) with the $0.017/min price note + "word-for-word as you speak" copy; SAME triad + v3 gate |
| `res/layout/floating_bubble.xml` | none — reuse `transcription_delta_text` / `transcription_preview_container` |
| `docs/PLAY-DECLARATIONS.md` | RECORD the no-new-data-class / no-version-bump determination + price-surface ledger entry |
| tests (5 new files) | see each task |

---

## Task 1 — `Resampler.upsample16kTo24k`: lift the 16k→24k math

**The gap (recon §7):** the 24k upsampler is `internal` inside `tts/cloud/ElevenLabsTts.kt:221`; the live engine needs it in the STT path. Move it to `Resampler` (`recording/SampleMath.kt`), leave a delegating alias in `ElevenLabsTts` so the TTS wave and its JVM tests stay green.

### 1a. TDD — extend `SampleMathTest`
- `upsample16kTo24k_lengthIsThreeHalves` — N samples → `N*3/2` (exact 2:3).
- `upsample16kTo24k_endpointsPreserved` — first/last sample identical (linear interp anchors).
- `upsample16kTo24k_matchesLegacyElevenLabsOutput` — byte-identical to the old private impl on a fixed ramp (proves the lift is behavior-preserving).

### 1b. Implement
Copy the linear-interp body verbatim into `object Resampler`; make `ElevenLabsTts.upsample16kTo24k` delegate. No `ByteArray` overload here — the engine converts via `PcmBytes.toShortArrayLE` (recon §7) at the seam.

> **Self-review:** `matchesLegacyElevenLabsOutput` is the guard that the lift changed nothing. `ElevenLabsTts`'s own tests still pass through the alias → no TTS regression.

---

## Task 2 — `RealtimeEvents` — typed event models + pure parser

Kotlinx-serialization models for the four inbound types we consume (`delta`, `completed`, `error`, plus `session.updated`/`input_audio_buffer.committed` acks we log-and-ignore) and the three outbound (`session.update`, `append`, `commit`). Unknown types tolerated (`ignoreUnknownKeys = true`) — the Realtime stream carries many events we don't need.

### 2a. TDD — `RealtimeEventParserTest` (pure JVM, no framework)
- `parses_delta_itemId_and_text`.
- `parses_completed_itemId_and_transcript`.
- `parses_error_event_to_fatal_with_code` — an `error` event → `Inbound.Error(code, message)` (message length only ever logged, not content).
- `unknown_event_type_is_ignored_not_thrown` — forward-compat.
- `outbound_session_update_shape_is_exact` — serialized JSON equals the pinned §config verbatim (rate 24000, model `gpt-live-transcribe`, `turn_detection:null`).
- `append_base64_roundtrips` — a known PCM ramp → base64 field decodes back identical (`java.util.Base64`, NOT `android.util.Base64`).

### 2b. Implement
`sealed interface Inbound { Delta(itemId, text); Completed(itemId, transcript); Error(code, message); Ack(type) }`. `RealtimeEventParser.parse(json: String): Inbound?` returns null for ignored types. Outbound builders return `String`. **No `android.*` in this file** — it must run under `isReturnDefaultValues`.

> **Self-review:** parser is total over the documented set and null-safe for the rest. The `outbound_session_update_shape_is_exact` test is the contract pin against the live docs.

---

## Task 3 — `RealtimeTransport` — the OkHttp WebSocket wrapper

Owns connect, session bootstrap, send, typed dispatch, clean close, and **reconnect-with-backoff policy** (nowhere else). Injectable `WebSocketFactory` so JVM tests drive a fake socket; real path uses the shared `OkHttpClient` (recon §8) via `newBuilder().readTimeout(0).callTimeout(0)` for the long-lived socket.

### 3a. TDD — `RealtimeTransportTest` (fake `WebSocket` + fake factory)
- `onOpen_sends_session_update_once` — bootstrap fires exactly once per open.
- `handshake_failure_logs_status_code_only_and_reports_fatal` — `onFailure(Response 401)` → `Fatal(INVALID_KEY)`; assert NO body/text captured (status code only).
- `append_and_commit_forwarded_as_correct_events`.
- `inbound_delta_and_completed_dispatched_to_listener`.
- `drop_triggers_backoff_reconnect_then_bootstrap_again` — `onFailure` (non-fatal) → schedules reconnect (backoff sequence pinned, capped), re-sends `session.update` on the new open.
- `close_is_clean_and_idempotent` — `close(1000)`; a second `close()` no-ops.

### 3b. Implement
`class RealtimeTransport(factory, clock, listener)`. `connect(apiKey, language)`: build the upgrade `Request` (URL `…?intent=transcription`, `Authorization` header ONLY), `factory.newWebSocket(req, wsListener)`. `onFailure` classifies: 401→`INVALID_KEY`, 429/quota→`OUT_OF_CREDIT`, 403→`FORBIDDEN` (mirror `OpenAiStt.classify`, log `"openai realtime http $code"` — code only), else transient→reconnect with capped exponential backoff. `sendAppend(base64)`, `sendCommit()`, `close()`. **No `android.util.Log` string interpolation of any body.**

> **Self-review:** backoff/reconnect live ONLY here — the engine never re-implements it. `handshake_failure_logs_status_code_only` is the credential-safety guard. Fatal is surfaced up, not swallowed.

---

## Task 4 — `LiveTranscriptionEngine` implements `TranscriptionEngine`

The heart. Mirrors `CloudTranscriptionEngine`'s exactly-once discipline (recon §2) over a socket instead of a POST.

### 4a. TDD — `LiveTranscriptionEngineTest` (fake `RealtimeTransport`)
- `sendAudio_never_blocks_capture_thread` — `sendAudio` returns immediately while the fake sender is stalled (append happens under buffer-lock only; drain is off-thread).
- `commit_allocates_monotonic_seq_and_cuts_buffer_synchronously` — seq increments; buffer emptied inside the `commit()` call (fallback mirror pairing).
- `completed_resolves_mapped_seq_as_Text_exactly_once` — `item_id→seq` map; a duplicate `completed` for the same `item_id` does NOT double-resolve.
- `out_of_order_completions_resolve_correct_seqs` — commit A then B; `completed` for B arrives before A → each resolves ITS seq (the documented case).
- `ws_drop_resolves_outstanding_turns_Lost` — mid-turn `onFailure` → every unresolved seq → `Lost(WS_DROP)` (fallback rescues); the correlation map is cleared.
- `empty_buffer_commit_returns_NO_SEGMENT` — `-1`, no seq allocated (matches batch).
- `backpressure_sheds_turn_as_Lost_never_blocks` — sender can't drain past cap → that turn `Lost(BACKLOG)`, capture unaffected.
- `fatal_error_event_latches_once` — `error`(401) → `lastFatal()` set once; subsequent commits fail fast to `Lost`.
- `close_resolves_all_outstanding_and_is_idempotent`.

### 4b. Implement
- **Buffer + sender:** `sendAudio(pcm)` appends the `ByteArray` under `bufferLock` and returns (hot path). A `senderJob` coroutine drains: `toShortArrayLE` → `Resampler.upsample16kTo24k` → `PcmBytes.toBytesLE` → `Base64` → `transport.sendAppend`. If the drain backlog exceeds `maxBacklog`, mark the current turn for `Lost(BACKLOG)` and drop — never block.
- **commit():** under `bufferLock`, if buffer empty return `NO_SEGMENT=-1`; else snapshot+clear, send `input_audio_buffer.commit`, allocate `seq = nextSeq++`, push a `PendingTurn(seq)` onto an ordered pending queue keyed for correlation. **Synchronous cut under the lock** — the fallback's mirror↔seq pairing (recon §3) depends on it.
- **item_id→seq correlation:** the first `delta`/`completed` naming an unmapped `item_id` binds it to the OLDEST unbound pending seq (turns commit in order; item_ids surface in order of first mention). `completed` → `resolveOnce(seq, Text|EmptyUnexpected)`; empty transcript on a voiced turn → `EmptyUnexpected` (visible loss), silence → `EmptyExpected`.
- **Exactly-once:** `resolveOnce(seq,outcome)` + per-`PendingTurn` `claimed:AtomicBoolean` (mirror batch L94/L246). `abandonOutstanding(reason)` on drop/close/connect resolves all in-flight `Lost`.
- **deltas:** forward `delta` text to `listener.onDelta(text)` — the contract's existing channel (recon §1). NEVER touches resolution.
- **fatal:** `@Volatile fatal` latched from `error` events / handshake fatal; exposed via `lastFatal()` for the latch-toast (recon §10). `onError` (session-fatal) only for unrecoverable transport loss.
- **lifecycle:** `connect` resets seq to 0 and bootstraps transport; `close` abandons outstanding + closes transport; `awaitIdle` = join sender + pending drain on the deadline; `sendAudio`/`commit` are the only hot-path methods.

> **Self-review:** every seq path — delta, completed, dup-completed, out-of-order, drop, backlog, fatal, close — resolves once (nine tests pin it). `commit()` stays synchronous under the lock so `FallbackTranscriptionEngine`'s mirrorLock race fix (recon §3) still covers instant resolutions. Capture thread only ever touches `bufferLock`.

---

## Task 5 — Mode selection + UI + service wiring + delta surface

### 5a. Pref — the batch-vs-live axis (recon §9)
`PreferencesManager`: `sttLiveMode: Boolean` (`KEY_STT_LIVE_MODE = "stt_live_mode"`), default false. This is the missing THIRD-mode axis; provider stays OpenAI.

### 5b. `EngineChoice.CLOUD_LIVE` + `decideEngineChoice` (TDD in `EngineSelectionTest`)
Add `CLOUD_LIVE` to the enum. `decideEngineChoice` gains a param `liveMode: Boolean`: after the existing one-way valve reaches `CLOUD_WITH_FALLBACK`, if `liveMode && providerId == OPENAI` → `CLOUD_LIVE`, else `CLOUD_WITH_FALLBACK`. Tests:
- `live_flag_with_openai_and_key_and_net_gives_CLOUD_LIVE`.
- `live_flag_without_key_still_falls_to_LOCAL_NO_KEY` (valve order preserved — live never bypasses the local guards).
- `live_flag_with_non_openai_provider_stays_CLOUD_WITH_FALLBACK` (Gemini/ElevenLabs can't stream).
- `live_flag_offline_gives_LOCAL_OFFLINE`.

### 5c. `resolveTranscriptionEngine()` build (recon §4/§9)
In the `CLOUD_LIVE` branch, build `cloud = LiveTranscriptionEngine(RealtimeTransport(realFactory, …), serviceScope, …)` and wire it EXACTLY as batch: `micEngine = FallbackTranscriptionEngine(cloud, local, serviceScope)` at L1561. Set `lastCloudEngine` to it so the latch-toast (recon §10) fires on WS auth/credit fatal via `lastFatal()`. `serviceScope` is NOT `Dispatchers.Unconfined` (recon §3 caller note) — unchanged. Router, `commit()` sites, wall-cap/VAD, `onSourceChanged` carry-over: ALL unchanged.

### 5d. Delta surface (recon §5 — smallest honest thing)
For a **TEXT_FIELD** live session there is today no delta affordance (the container is GONE, `onDelta` a no-op). Lift the `sessionContext != TEXT_FIELD` gate **for live mode only**: make `transcription_preview_container` VISIBLE with `transcription_edit_text` hidden and render `onDelta` into `transcription_delta_text` (italic strip). Completions still inject via the unchanged orderer→`handleTranscriptionResult` full-field RMW path — **deltas never reach injection**. NONE/MEDIA_PLAYBACK already render deltas as-is — no change.

### 5e. Selector row (recon §9)
`CloudProvidersScreen.kt`: below the OpenAI provider row, when OpenAI is the selected STT provider + key stored + `disclosureAccepted`, show a live-mode toggle **"Cloud word-for-word (OpenAI) · about $0.017/min"** with sub-copy **"Transcribes word-for-word as you speak."** — mirror `StreamingChip`. **No speed claim.** Toggling sets `sttLiveMode`. Gated on the SAME triad + `cloud_disclosure_accepted_v3` — **no new consent surface.** TDD in `CloudProvidersScreenLogicTest`: row visible ONLY for OpenAI+key+disclosure; hidden for Gemini/ElevenLabs; price string present; NO "faster"/"speed" substring.

> **Self-review:** live is additive — every existing `EngineChoice` path is byte-unchanged; the valve still reaches local first. The delta gate is lifted for `liveMode` ONLY, so batch/NONE behavior is untouched. The copy test bans speed words.

---

## Task 6 — Compliance touch: the no-bump determination + ledger

**Determination (record in `docs/PLAY-DECLARATIONS.md`):** live mode sends the **same mic audio to the same provider (OpenAI)** already covered by disclosure **v3** (dictated-audio-to-provider). It adds NO new data class — only a new *transport* (WebSocket vs POST) and a new *price tier*. Therefore **NO disclosure version bump, NO re-prompt, NO Data Safety change.** The new user-facing surface is the mode row's **$0.017/min price note** — the honest cost disclosure. Ledger entry: date 2026-07-30, "C4 live transcribe: transport+price only, v3 unchanged, price surfaced on selector."

> **Self-review:** the only thing that changed for the user is transport + cost; data-out is identical to what v3 already authorizes. Bumping v3 would be dishonest churn (re-prompting for no new data). The price note is the required new-cost surface and it carries no speed claim.

---

## Final verification (run before commit)
- [ ] `$env:JAVA_HOME=…jbr; .\gradlew.bat --no-daemon testDebugUnitTest` → **≥ 548 + new tests, 0 failures**.
- [ ] `.\gradlew.bat --no-daemon assembleDebug assembleRelease` both green (R8 keeps the WS/serialization models).
- [ ] Grep the new files: no `android.util.Base64`, no `org.json`, no body/transcript/key in any `Log` call.
- [ ] Confirm on-device default, batch-POST, and TTS paths are byte-unchanged (git diff touches only the named files).

Commit: `git add "docs/superpowers/plans/2026-07-30-c4-live-transcribe.md" && git commit -m "plan: C4 — word-for-word live transcription over the Realtime WebSocket"`
