# Multi-provider cloud STT + TTS, with audio retention and retry

**Date:** 2026-07-27
**Status:** Design approved, ready for implementation planning
**Target release train:** 3.3.0 (Releases A–D below)

---

## 1. What this is

Whisper Everywhere went 100% on-device in the 3.x line and took criticism for removing
API-key support. This spec restores cloud transcription and adds cloud read-aloud, as
**selectable options layered on top of** the existing local stack — not as a replacement.

Three providers, each with the user's own API key:

| Provider | STT | TTS |
|---|---|---|
| OpenAI | `gpt-4o-mini-transcribe` (pinned snapshot) | `gpt-4o-mini-tts` (pinned), `tts-1` fallback |
| Google **Gemini API** | `gemini-3.6-flash` / lite tiers | `gemini-2.5-flash-preview-tts` |
| ElevenLabs | `scribe_v2` | `eleven_flash_v2_5` |

Plus two new capabilities the cloud work makes worth building:

- **VAD-close-driven dispatch** with segment identity and strict in-order release.
- **On-device session audio archive** with a **Retry transcribe** action.

Everything local stays. A user who wants free and offline keeps exactly what they have.

### Explicitly out of scope for v1

- WebSocket / realtime streaming STT (seam kept clean; see §5.6).
- Google Cloud Speech-to-Text and Google Cloud Text-to-Speech — **structurally impossible**, see §3.1.
- Encryption at rest for the audio archive — see §7.3 for the reasoning.
- Retro-injection of retried text into the original field — **physically impossible**, see §6.5.

---

## 2. Decisions

Locked during design. Do not relitigate without a new decision record.

| # | Decision | Choice |
|---|---|---|
| D1 | Providers | OpenAI, Google **Gemini**, ElevenLabs |
| D2 | Transport | ~~Batch only~~ **REVISED 2026-07-27** — WebSocket streaming for OpenAI and ElevenLabs; **Gemini stays batch** (no BYOK streaming path exists). See §5.6 |
| D24 | Live partials | Rendered in the **bubble's own preview only**. The text field still receives committed text on segment close — never word-by-word. See §5.6a |
| D25 | IME | **Deferred, not rejected.** True word-for-word into third-party fields requires an `InputMethodService`; accessibility `ACTION_SET_TEXT` structurally cannot do it. See §5.6b |
| D3 | Data boundary | Mic audio + accessibility-**selected** text may go cloud. MediaProjection device audio **never** |
| D4 | Local model | Strong nudge — cloud-only permitted; onboarding defaults to the smallest tier as "offline backup", skippable |
| D5 | Fallback | Local is terminal fallback for every cloud failure class |
| D6 | Fallback disclosure | Graduated: badge → toast → sticky + auto-demotion |
| D7 | Fallback direction | **One-way valve.** Never escalate local → cloud |
| D8 | Gemini | Ships with its own hard warning naming human review explicitly |
| D9 | Credentials | One `ProviderAccount` per provider; **independent** STT and TTS engine selection |
| D10 | Spend | Ambient estimate + user-set monthly cap that auto-demotes to local |
| D11 | Setup placement | Settings section + one-time dismissible nudge on Home after first success |
| D12 | VAD hangover | Ship 800 ms; make injectable; flip default to 500 ms later on measured evidence |
| D13 | Stall UX | Bubble badge + "N waiting" only. No text panel over the user's app |
| D14 | Lost segment | Inline `[…]`, consecutive losses collapsed to one marker |
| D15 | Stop with requests in flight | Drain, bounded 30 s, with "Finish without them" escape |
| D16 | Audio archive scope | **Mic sessions only.** Device-audio sessions retain transcript only |
| D17 | Archive default | **OFF.** No hidden scratch buffer. Contextual prompt at first failure |
| D18 | Retention sweeper | Add `androidx.work`; 12 h periodic + boot sweep |
| D19 | Transcript window | Default **7 days**, user-selectable 7/14/30/forever |
| D20 | TTS gap fix order | **Diagnostics first**, measure on-device, then tune constants |
| D21 | TTS prebuffer | ~~Always prebuffer (~2 s)~~ **DEFERRED 2026-07-27** — the measured first burst was 3,262 ms, so a 2 s watermark would not have prevented the observed gap. See §6A.1b |
| D22 | Clause splitting | Split sentences over ~300 chars. **Promoted to THE fix** by the 2026-07-27 capture, and required cloud infrastructure regardless (provider input caps). The size bound is also a memory fix |
| D23 | Truncation | Surface a distinct `Truncated` outcome + speak-from-offset; never silent |

---

## 3. Hard constraints

Facts that remove choices. Each was verified against source or live vendor documentation.

### 3.1 Google Cloud STT/TTS are unreachable with a pasted key

`speech.googleapis.com` and `texttospeech.googleapis.com` authenticate via OAuth2 / ADC /
service accounts **only**. No API-key method exists in their auth docs; the canonical sample is
`Authorization: Bearer $(gcloud auth print-access-token)`. The STT endpoint embeds a
`PROJECT_ID` in the path. Structural reason, from Google's own API-keys documentation: a
standard API key does not authenticate a principal, so IAM cannot authorize the call.

Supporting it would mean walking a consumer through creating a GCP project, enabling billing,
minting a service-account JSON, and pasting a **private key** into a GPLv3 open-source app.
**Ruled out. Google means the Gemini API.**

Related: **"Google Scribe" does not exist** as a Google STT product. Scribe is ElevenLabs' ASR
brand. Google's Live Transcribe accessibility app is internally codenamed scribe and has no
public API.

### 3.2 The Play listing and Accessibility declaration are currently false-on-ship

`docs/PLAY-LISTING.md` claims "100% on-device", "No audio ever uploaded", "Zero audio or text
leaves your phone — verifiable". `docs/PLAY-DECLARATIONS.md` §1 promises "audio and text never
leave the device"; §3 says mediaProjection transcription is "entirely on-device".

Play's applicable rule is **purpose limitation** — "Limit data collection and use strictly to
the disclosed and declared purposes." There is no quotable 2026 rule banning off-device
transmission of accessibility-derived data; the exposure is purpose limitation plus reviewer
discretion. Listing copy, both Console declarations, Data Safety, `privacy_policy.html` and
`terms_of_service.html` must change **in lockstep with the code**.

### 3.3 OkHttp forces a toolchain bump

`okhttp-android-5.4.0.aar` declares `minCompileSdk=36`; AGP ≤8.9 caps at API 35. No fallback
exists — OkHttp 5.0.0–5.3.2 all stamp Kotlin metadata 2.2.0, unreadable by Kotlin 2.0.21.

Required: **AGP 8.7.3 → 8.13.2, compileSdk/targetSdk 35 → 36.** Gradle wrapper 8.14.4 and
Kotlin 2.0.21 unchanged. **Play mandates targetSdk 36 for updates from 31 Aug 2026** — this is
pull-forward, not new work.

### 3.4 Kotlin 2.0.21 sets a hard dependency ceiling

A 2.0.x compiler reads metadata at most one minor ahead (2.1.0).

- **Unusable:** Ktor 3.5.1, kotlinx-serialization-json ≥1.9.0, kotlinx-coroutines 1.11.0, and
  therefore `okhttp-coroutines` 5.4.0 (which pulls coroutines 1.11.0).
- **Safe:** serialization-json 1.8.1, coroutines 1.10.2, OkHttp 5.4.0, Retrofit 3.0.0.
- Hand-roll the ~20-line `suspendCancellableCoroutine` `Call.await()` bridge.

### 3.5 The credential layer is a live data leak — fix before anything else

`PreferencesManager.kt:41` falls back to a **plaintext** `MODE_PRIVATE` file named
`encrypted_api_key_fallback` when `EncryptedSharedPreferences` init fails twice.
`backup_rules.xml` and `data_extraction_rules.xml` both use `<include domain="sharedpref" path="."/>`
and exclude only `encrypted_api_key.xml`.

**A raw API key is therefore eligible for Google Drive cloud backup and device-to-device transfer.**
Verified in all three files. Fix regardless of this feature:

1. Delete the fallback path — never silently degrade to plaintext. Fail loudly.
2. Flip both XML files to an explicit allowlist plus explicit `<exclude domain="file" path="."/>`.
3. Delete the fallback file on migration; it may already be in users' backups.

Also: `androidx.security:security-crypto` is **terminally deprecated** (final release 1.1.0,
2025-07-30; "There won't be any subsequent releases"). The project pins a pre-deprecation
alpha. There is no Jetpack replacement — move to direct Android Keystore AES-256-GCM.

### 3.6 Four lifecycle methods escape the `TranscriptionEngine` interface

`prewarm()`, `shutdown()`, `awaitIdle()`, `releaseContext()` are reached via unchecked
downcasts at `FloatingBubbleService.kt:259`, `:510`, `:1528`, `:1607`. For any second
implementation they **silently no-op**. Line `:1528` is the dangerous one — `awaitIdle` is the
fence that drains in-flight transcribes before `close()` detaches the listener; skipping it
drops every pending segment via the listener-identity guard, reproducing the
"No speech detected despite valid audio" bug the in-code comment documents as already fixed once.

**Lift all four onto the interface with no-op defaults before writing a second implementation.**

### 3.7 `sendAudio()` runs on the capture thread every ~32 ms and must never block

Any network I/O, TLS handshake, or backpressure wait inside it stalls `AudioRecord` itself and
freezes the waveform, which is driven by the same thread. A cloud engine must hand off to its
own queue immediately.

### 3.8 Injection is a full-field read-modify-write

`WhisperAccessibilityService.kt:1072` reads the whole field → `:1088-1100` splices →
`:1106` `ACTION_SET_TEXT` with the entire rebuilt string.

Two overlapping injections do not merely scramble word order — **the second reads pre-first text
and writes it back, silently deleting the first segment.** Retroactive correction is impossible:
nothing records where a segment landed, `ACTION_SET_SELECTION` appears **zero times** in the
codebase, and two of three delivery paths (document apps `:993-1030`, social apps `:782-847`)
are clipboard + `ACTION_PASTE` with no position control at all.

**This forces strict in-order release. No speculate-then-correct, ever.**

### 3.9 Format and API constraints, per provider

- **OpenAI rejects raw PCM.** Containers only: mp3, mp4, mpeg, mpga, m4a, wav, webm. A 44-byte
  WAV header is mandatory, and OpenAI infers format from the multipart **filename** — the part
  must be named `audio.wav`. 25 MB cap ≈ 13 min of raw PCM16.
- **OpenAI: timestamps and streaming are mutually exclusive.** Timestamps need
  `response_format=verbose_json`, which is whisper-1 only; `stream=true` is unsupported on whisper-1.
- **Gemini's MIME allowlist is narrow:** wav, mp3, aiff, aac, ogg, flac only. No mp4/m4a, no webm,
  no bare Opus. 20 MB is a **total request** cap and base64 inflates ~33%.
- **Gemini free tier trains on user data with human review** (paid tier excluded). `store`
  defaults to **true** on Interactions — set false.
- **All Gemini TTS models are preview.** No SLA. No SSML support at all.
- **ElevenLabs `tag_audio_events` defaults to true** — injects literal `(laughter)` tokens into
  dictated text. Set false.
- **ElevenLabs default `model_id` is `eleven_multilingual_v2`** — the slow, 2× expensive one.
  Always send `model_id` explicitly.
- **ElevenLabs `pcm_24000` is available on all tiers**; `pcm_44100` is Pro-only and would
  hard-fail most users.
- **ElevenLabs keyterms > 100 triggers a 20-second minimum billable duration per request** —
  a contact-list glossary would bill every 3 s utterance as 20 s (6.7×) with no error and no log.
  Enforce with a `require()` citing the doc.

### 3.10 TTS converges on one format — this is the lucky break

All three providers can emit **24 kHz, 16-bit, mono, little-endian, headerless PCM** — byte-identical
to what Kokoro already produces and what the existing AudioTrack already plays.
**No decoder, no resampler, no MediaCodec.**

Caveat: `TtsEngine` hardcodes `24_000` in `AHEAD_CAP_SAMPLES` and `RETAIN_CAP_SAMPLES` while the
AudioTrack is built from `engine.sampleRate()`. A 44.1 kHz voice would silently turn the
30-minute retention cap into ~16 minutes and **158 MB of heap in a foreground service.**
Derive both from the actual rate.

### 3.11 GPLv3 permits REST but not vendor SDKs

Per the FSF, sockets are normally separate-program communication and GPLv3 has no AGPL-style
network clause. Posting audio to a documented HTTPS endpoint creates no combined work. Linking
a **proprietary vendor Android SDK** into the GPLv3 binary would raise a genuine combined-work
problem. **Use plain HTTPS only.** Never embed a developer-owned key in a public-source app.

---

## 4. What is already true (and mostly right)

Verified by reading the source:

- **`TranscriptionEngine` is already backend-neutral.** Its KDoc says so, and `onDelta` was
  explicitly preserved "for interface compatibility" with a future streaming backend.
- **Dispatch already follows VAD close.** `SpeechSegmenter.onAmplitude()` returning true calls
  `engine.commit()` synchronously on the audio thread (`FloatingBubbleService.kt:1174-1177`).
  The owner's requirement is met **in shape**; §5 fixes what undercuts it.
- **The audio contract is uniform:** PCM16 LE, mono, 16 kHz, 1024-byte / 32 ms chunks from both
  the mic and the (decimated) playback path.
- **Downstream is provider-agnostic:** `TranscriptSink`, `TranscriptStore`, `TranscriptText.clean()`,
  `injectTextWithResult` need no changes.
- **The TTS pipeline below the sherpa callback is reusable verbatim:** retained store, read cursor,
  seek, pause, `AtomicLong` generation cancellation, audio focus — and `onBuffering`, which is
  genuinely *better* suited to a network producer than a local one.

### 4.1 Two VADs, and the one driving dispatch is not the neural one

1. **Dispatch VAD** — `SpeechSegmenter`, pure Kotlin, fixed RMS energy thresholds. This is the
   real-time driver.
2. **Content VAD** — Silero v5.1.2, run manually in `whisper_jni.cpp:108-157` **after** the
   segment is already committed, purely to trim silence before `whisper_full`. Zero influence
   on *when* transcription starts.

---

## 5. Dispatch pipeline

### 5.1 Bugs in shipped code, fixed first

These affect users **today** and ship in Release A with no cloud code.

| Bug | Location | Effect |
|---|---|---|
| **251–499 RMS dead zone** | `SpeechSegmenter.kt:38` | Opening needs RMS ≥500, closing needs ≤250. In any room with a noise floor between them, the close condition **can never be satisfied** — dispatch silently degrades to 15 s wall-clock chunks, with no log distinguishing it |
| **Enqueue race** | `LocalWhisperEngine.kt:151-159` | `commit()` snapshots under `bufferLock` but calls `executor.execute` **outside** it. `switchSource` (`:1260`) commits *before* stopping the source, so it can enqueue a later audio slice ahead of an earlier one |
| **`maxSegmentMs` is dead code** | `SpeechSegmenter.kt:28-35` | Unreachable during continuous loud speech — the `amplitude >= voiceThreshold` branch returns before `segmentTooLong` is evaluated. `SpeechSegmenterTest.kt:40-48` encodes the workaround |
| **Retention gated behind text** | `FloatingBubbleService.kt:1540-1545` | `sweep()` sits inside `if (sessionTranscript.isNotBlank())` — a session producing no text neither persists nor garbage-collects |
| **No repetition gate** | — | `TranscriptText.clean()` strips only bracketed groups and a fixed keyword list. "Thank you for watching." passes, and sets `sessionProducedText = true`, **disarming the app's only safety net** at `:1580` |

**Fixes:**

- **Adaptive silence floor.** EMA of non-voiced chunk RMS over ~2 s:
  `effSilence = min(max(silenceThreshold, floorEma * 1.6), voiceThreshold - 1)`.
  The `max()` makes regression impossible by construction — in a quiet room
  `floorEma * 1.6 < 250`, so behaviour is byte-identical to today. **Adapt the silence threshold
  only; leave `voiceThreshold` at 500.** Raising the open threshold would hurt soft talkers.
- **Allocate `seq` inside `bufferLock`** with the PCM snapshot. Ordering becomes a function of
  audio order, not enqueue order. This alone closes the race.
- **Delete `SpeechSegmenter.maxSegmentMs`**; keep `MAX_SEGMENT_WALL_MS` as the only cap, split
  into SOFT (12 s cloud / 15 s local, arms "cut at next lull") and HARD (20 s, forced).
  On a hard cut, cut at `argmin RMS` over the last 750 ms using a 24-entry ring of the per-chunk
  RMS values `StreamingAudioRecorder.kt:83` already computes — not at "now", which slices mid-word.
- **Move `sweep()` out** of the `isNotBlank` guard.
- **`SegmentQuality` repetition gate:** port whisper's own compression-ratio heuristic —
  `text.toByteArray().size.toFloat() / deflate(text).size > 2.4` ⇒ REJECT, via `java.util.zip.Deflater`.
  This is literally the gate whisper.cpp trips on (`whisper_jni.cpp:222`), pre-calibrated on the
  exact failure that hit this app on the 2026-07-18 YouTube capture. Feed that sample in as a fixture.

### 5.2 Segment identity

```kotlin
enum class CutReason { VAD_CLOSE, SOFT_CAP, HARD_CAP, SOURCE_SWITCH, BUFFER_CAP, STOP_FLUSH }

class AudioSegment(
    val seqLo: Long,
    val seqHi: Long = seqLo,        // a RANGE, so N-way coalescing works
    val generation: Long,
    val pcm: ByteArray,             // PCM16 mono 16 kHz
    val language: String?,
    val voicedMs: Int,              // from the segmenter, NOT wall time
    val capturedAtMs: Long,
    val reason: CutReason,
) {
    val durationMs: Long get() = pcm.size / 32L    // 32 B per ms
}

sealed interface SegmentOutcome {
    data class Text(val text: String) : SegmentOutcome
    data object EmptyExpected : SegmentOutcome      // VAD proved nothing to transcribe
    data object EmptyUnexpected : SegmentOutcome    // real voiced audio, empty result
    data class Lost(val reason: String) : SegmentOutcome
}
```

`seqLo..seqHi` as a range (not a scalar) is load-bearing: pairwise-only merging cannot reach the
request-reduction that Gemini free-tier rate limiting depends on.

**Prerequisite that does not exist:** `AudioMath.peak(pcm: ByteArray): Float`. `AudioMath.kt` has
only `amplitude` and `pcm16ToFloat`.

### 5.3 Ordering — strict in-order release

Forced by §3.8, not chosen for tidiness.

`SegmentOrderer` is pure Kotlin, main-thread confined, testable like `SpeechSegmenterTest`.
It holds completed-but-blocked outcomes in a map keyed by seq and releases only a contiguous run
from the head. **A release burst is joined into ONE string and delivered as ONE injection** —
strictly better than today's one-write-per-segment, because it means fewer full-field rewrites
and fewer cursor jumps.

**Liveness is bounded by resolution, not hold time.** Every dispatched seq resolves exactly
once, so the head can never block indefinitely. **The terminal-callback contract is the single
highest-risk part of this change**: `LocalWhisperEngine.kt:182-184` currently drops a blank
result with *no callback at all*, which under a reorder buffer is a permanent head-of-line
stall. Add a debug assertion that every seq returned by `commit()` reaches exactly one terminal state.

**`flush()` must be called on every exit path** — `stopRecording`, generation bump, `onDestroy`,
and the drain-timeout branch. Held text is uniquely fragile: unlike today, where every completed
segment injects immediately, the buffer deliberately accumulates finished work in RAM whose only
exit is one function — and the pile is largest exactly when the user is most likely to tap the
bubble. **Also persist each outcome the moment it resolves** (release still gates *injection*),
so a process kill costs a clipboard paste, not the words.

**Deltas must be suppressed unless `seq == head`**, or a streaming provider leaks later words
into the preview before earlier ones.

### 5.4 Dispatch and concurrency

```
inbox (UNLIMITED Channel)
  → pump coroutine (Dispatchers.IO)
      → coalesce()          [N-way, seq-range aware, RATE-aware not depth-aware]
      → admission: Semaphore(limit) AND RateLimitGate AND TokenBucket
      → runSegment()        [wire deadline starts HERE]
  → SegmentOrderer          [main-thread confined]
  → applyRelease()          [one injection per release burst]
```

**The single most important structural rule: the timeout clock starts when the request goes on
the wire, never before.** Admission wait is bounded separately by
`ADMISSION_DEADLINE_MS = 60_000`, and on expiry the segment is **routed to local, not failed**.

Rationale: on Gemini's free tier (~10 RPM ≈ one token per 6.7 s), a naive
`withTimeout` outside the permit means segments 2–4 time out *while sitting in a local token
bucket, having never touched the network* — the user's chat field receives `yes no […] […] […]`
for three words captured perfectly. **A segment must never be destroyed for a reason local to
this device.**

**Backpressure ladder** (audio is never dropped): L0 dispatch immediately → L1 coalesce adjacent
up to 20 s → L2 queue unbounded but surface `backlogSeconds` → L3 spill PCM to disk above 8 MB
→ L4 demote to local for the rest of the session.

**L4 triggers:** first `Fatal`; two consecutive terminal failures; quality breaker; sustained
60 s backlog. A backlog-only trigger is structurally unreachable when the network is *broken*
rather than slow — offline failures resolve in milliseconds, so the backlog never grows.

**Concurrency:** local `maxInFlight = 1` **permanently** (one `whisper_context`; a second is a
multi-hundred-MB OOM path in a foreground service that already wires `releaseContext()` to
`onTrimMemory`; `n_threads = min(cores,4)` already saturates the cores). Cloud: OpenAI 3,
Gemini 2 + mandatory RPM token bucket, ElevenLabs 3.

At N=1 local, results always arrive with `seq == head`, so **the orderer is a provable
pass-through and local delivery timing is unchanged.**

### 5.5 Failure ladder

1. **Pre-dispatch.** `AudioGuard` rejects <150 ms. **Silero `vadTrim` runs before the network
   call** — expose `we_vad_filter` as a standalone JNI entry (~30 lines; it already owns its own
   cached context under `g_vad_mutex`). Zero speech ⇒ `EmptyExpected`, no request.
   This is a **correctness** guard: `FloatingBubbleService.kt:1514-1517` states the unconditional
   stop-flush is safe *because* whisper's internal VAD trims silence to nothing. A cloud engine
   uploading raw PCM voids that stated precondition and ships unguarded silence to a model —
   precisely the input that produces "Thank you for watching."
2. **Transient** (429 with reset hint, 5xx, IOException with validated network):
   `RetryPolicy(maxAttempts = 2)`, honouring `Retry-After` via a new `delayOverrideMs` hook.
   `RetryPolicy` currently has **no `Retry-After` path at all** — a server saying "wait 8 seconds"
   is unrepresentable, so the client waits ~0.2 s then ~0.4 s, burning attempts into a closed window.
   A **session-scoped** `RateLimitGate` closes on any 429; 429 is a session signal, not a per-segment one.
3. **Fatal** (401/403, 429 with `insufficient_quota` / `RESOURCE_EXHAUSTED` / `quota_exceeded`,
   or offline per `ConnectivityManager`): trips a circuit breaker on the **first** occurrence.
   One fatal error costs one request, not forty.
4. **BadSegment** (400/413): this segment only; does not trip the breaker.
5. **Quality reject** (HTTP 200, garbage): never retried; always counted. Two rejects and ≥50%
   of completions ⇒ breaker.
6. **Admission starvation:** route to local, never fail.
7. **Terminal cloud failure ⇒ local, always.** Retain `segment.pcm` until **release**, not until
   dispatch. This is the mechanism that converts "3 minutes of dictation destroyed" into
   "the transcript arrived late", and it is why every other failure class can be handled calmly.
8. **Local unavailable:** spill PCM, resolve `Lost`, offer retry at finalize.
9. **Only then** does the user see `[…]`, consecutive losses collapsed to one.

**Connectivity is first-class.** `connect()` refuses with a real error before any audio is
captured (one `NET_CAPABILITY_VALIDATED` lookup, no round trip). There is currently **zero**
connectivity awareness in the app even though `ACCESS_NETWORK_STATE` is already declared.
Register a `NetworkCallback`: `onLost` cancels in-flight calls (failure in ~0 s instead of ~35 s
of timeouts); `onAvailable` resumes **staggered** — one segment, confirm a 200, then reopen the gate.

**Drain must track dispatched-but-unresolved seqs explicitly.** Never use "the held-results map
is empty" as a fence — in a total outage nothing has completed, so that check returns
immediately and discards every in-flight segment, reintroducing exactly the regression
documented at `FloatingBubbleService.kt:1520-1525`.

**`FloatingBubbleService.kt:1580` must branch on `sessionFatalMessage`.** Telling a user their
mic was too quiet when their card declined is the worst output in the entire failure space, and
it is a three-line fix.

### 5.6 REVISED 2026-07-27 — streaming where a BYOK client can actually get it

D2 originally chose batch-only. That is **partially reversed**: the owner's goal is the lowest
possible perceived latency, and two of the three providers can deliver real streaming to a client
holding nothing but the user's own API key.

| Provider | Transport | Why |
|---|---|---|
| **OpenAI** | **WebSocket** — Realtime API, live partials | Works with a pasted key. (Ephemeral tokens exist but are only needed for developer-owned keys.) |
| **ElevenLabs** | **WebSocket** — `scribe_v2_realtime`, `commit_strategy=manual` | `manual` commit maps almost exactly onto this app's caller-driven `commit()`, so the app's own VAD stays the segment authority *inside* a persistent stream |
| **Google Gemini** | **Batch. No streaming.** | Not a preference — a hard blocker. The Live API is preview, session-capped at 15 minutes, and explicitly recommends ephemeral tokens minted by a backend this app does not have. **There is no usable BYOK streaming path on Google.** |

So "streaming on all cloud providers" is not achievable. Gemini remains batch, and the UI must not
imply otherwise — the provider row should state which providers stream.

**Costs accepted with this reversal**, all previously documented as reasons *not* to do it:
1.8–2.8× the per-minute price (OpenAI realtime ~$0.017/min vs ~$0.006 batch; ElevenLabs $0.39/hr
vs $0.22/hr), hand-rolled WebSocket clients for two providers (ElevenLabs ships **no** official
Android SDK and has 13+ realtime error message types, two with unpublished thresholds), and
reconnect/keepalive state machines. The batch path remains as each streaming provider's own
fallback, so it is not thrown away.

Note `sendAudio()` runs on the capture thread every ~32 ms and must never block (§3.7) — a
WebSocket write with backpressure needs its own queue, or it stalls `AudioRecord` and freezes the
waveform.

### 5.6a Where live partials go — and where they must not

**Decision D24: partials render in the bubble's own preview surface only. The user's text field
receives committed text on segment close, exactly as today.**

This is forced by §3.8. Injection is a full-field read-modify-write: read the whole field, splice,
`ACTION_SET_TEXT` the entire rebuilt string. Emitting partials word-by-word into a live field
would mean rewriting the complete field on every word — clobbering concurrent user typing, jumping
the cursor, fighting autocorrect, and growing more expensive as the text grows. Two of the three
delivery paths (document apps `:993-1030`, social apps `:782-847`) are clipboard + `ACTION_PASTE`
with **no position control at all**, so ordering cannot even be enforced there.

The plumbing already exists and is 90% done: `onDelta` reaches `transcriptionDeltaText` today. It
is gated off for the primary path at `FloatingBubbleService.kt:1442`
(`if (sessionContext != BubbleContext.TEXT_FIELD)`). **Un-gate it for the preview only.**

Two prerequisites before doing so:
- **Separate the delta TextView from the FINALIZING status TextView.** They are currently the same
  view, so late deltas would clobber the "Finishing transcript…" message.
- **Suppress `onDelta` unless `seq == head`** (§5.3), or a streaming provider leaks later words
  into the preview ahead of earlier ones.

### 5.6b Why true word-for-word needs an IME — deferred, not rejected

**Decision D25.** The best-possible UX the owner described — word-for-word text appearing live in
any app — is how Gboard voice typing works, and it works because Gboard **is an input method**.
`InputConnection.commitText()` appends incrementally without rewriting the field.

This app has **no `InputMethodService`** (verified) and never calls `ACTION_SET_SELECTION`
(verified: zero occurrences). An accessibility service doing full-field `ACTION_SET_TEXT`
structurally cannot deliver incremental word-level insertion.

Shipping an IME would additionally **remove the accessibility-permission dependency**, which is
this app's single largest Play-review liability (§3.2, §8.2, §8.3). That makes it strategically
interesting beyond the UX win.

Deferred because it is a substantial new subsystem — keyboard UI, IME lifecycle, per-app input
quirks — and because users must explicitly enable and switch to it, which is a real adoption cost.
**Revisit if word-for-word becomes a priority, or if Play pressure on the accessibility
declaration increases.**

### 5.6c Local streaming — the honest ceiling

whisper.cpp is not a streaming architecture; it processes fixed windows, and this app's JNI layer
returns one string per `whisper_full` call. Word-level local output would mean re-running inference
over a growing buffer — multiplying compute on precisely the axis that already draws latency
complaints, and which motivated retiring the medium and large tiers.

**On-device stays segment-level.** The realistic local win is faster, better-bounded segments
(clause splitting, §6A.1b), not live words. Do not promise local word-for-word in UI copy.

### 5.6d Superseded — the original batch-only argument

- 1.8–2.8× the running cost, paid by the user (OpenAI realtime $0.017/min vs ~$0.006 batch;
  ElevenLabs $0.39/hr vs $0.22/hr).
- Hand-rolled WebSocket clients for two providers; ElevenLabs has **no official Android SDK** and
  13+ realtime error types, two with unpublished thresholds.
- **Impossible on Google with a pasted key** — the Live API is preview, 15-min session cap, and
  explicitly recommends ephemeral tokens requiring a backend this app does not have.
- **The payoff is currently invisible:** `onDelta` is gated off when `sessionContext` is
  `TEXT_FIELD` — the primary dictation path — and late deltas would clobber the "Finishing
  transcript…" status in the same TextView.

Keep the seam clean, and separate the delta preview TextView from the FINALIZING status TextView
so a future streaming engine does not have to untangle them.

**One hedge to check before committing:** ElevenLabs shipped `scribe_v2_realtime_turbo` and
`scribe_v2_realtime_lite` in July 2026 with unpublished pricing. If a lite realtime tier prices
near batch, this calculus changes.

### 5.7 Latency, honestly

The perceived floor is `pauseMs + ≤32 ms chunk + admission + inference + ~40 ms injection`.
At 800 ms hangover that is **816 ms before any engine does anything** — 54% of a ~1,500 ms warm
cloud budget, larger than TLS + upload + injection combined. **The hangover and the dead zone
are the two highest-leverage changes in this document, and neither is a cloud feature.**

Where cloud wins and loses: whisper's `audio_ctx = clamp(pcm.size()/320 + 64, 768, 1500)`
(`whisper_jni.cpp:270-277`) means a 3 s utterance still runs the encoder at **51.2% of a full
30 s encode** — local cost does not scale down below ~15 s of audio. Modelled: on a flagship,
local ≈1.8–2.7 s vs warm cloud ≈1.5 s — **roughly a wash; do not sell cloud as a speedup there.**
On the mid-range devices this app targets, local ≈3.5–6.0 s and cloud is 2–4× faster.

**These numbers are modelled, not measured.** See §11.

---

## 6. Audio archive and retry

### 6.1 Format: raw headerless PCM16LE

No WAV on disk, no FLAC, no Opus.

**Why not FLAC:** the design addresses audio by **byte offsets** (`byteStart`/`byteEnd` for
per-segment retry). FLAC frames are variable-length, so byte offsets into a FLAC file are
meaningless — a gap-fill retry would seek to byte 320,000, read whatever frame data is there,
and attribute the result to segment 4: **a wrong transcript of the right session, presented as a fix.**

**Why not Opus:** the MediaCodec Opus *encoder* is API 29; `minSdk` is **26**. Gemini's allowlist
has no Opus entry.

**Why raw over WAV:** no length field to patch (no fsync-ordering hazard, no zero-length-"complete"
file on power loss); **any truncated prefix is still valid audio**, making crash recovery
arithmetic rather than repair; it is exactly what `AudioMath.pcm16ToFloat` and whisper.cpp
consume. A WAV header is a pure function of length, synthesized on demand via
`SequenceInputStream(headerBytes, RandomAccessFile slice)` — **peak retry memory is one segment
(≤960 kB) regardless of session length.**

### 6.2 Location and layout

```
context.noBackupFilesDir/sessions/<uuid>/
    audio.pcm          (audio.pcm.part while in flight)
    audio.tainted      zero-byte marker, written the instant provenance flips
    transcript.txt
    meta.json          SessionManifest, .part → fsync → rename
```

**`noBackupFilesDir`, not `filesDir`:** `/data/data/<pkg>/no_backup` is a *sibling* of `/files`,
unreachable through the `file` backup domain. Today `filesDir` escapes Auto Backup only **by
omission** — one later `<include domain="file" path="."/>` would silently start uploading raw
voice to Google Drive. Protection by construction beats protection by accident. Add explicit
`<exclude domain="file" path="."/>` and `<exclude domain="device_file" path="."/>` to both rule
files anyway, so the intent is stated.

**Not `cacheDir`** — disqualifying, not merely suboptimal: Android may delete cache to recover
space, most likely under the very storage pressure a long session created. A Retry button for
audio the OS silently vaporized is a promise you cannot keep.

**Session identity becomes a UUID.** Wall-clock milliseconds are non-monotonic — an NTP
correction collides two sessions, and a duplicate key crashes `LazyColumn` at
`TranscriptsScreen.kt:75`. `startedAtMs` is demoted to metadata.

### 6.3 The writer

Tap point: `FloatingBubbleService.onAudioChunk` — the single funnel above `switchSource()`.
The capture thread does exactly one thing:

```kotlin
private val q = ArrayBlockingQueue<ByteArray>(256)   // ~8 s of backlog
fun offer(chunk: ByteArray) { if (!q.offer(chunk)) droppedChunks.incrementAndGet() }
```

`offer()`, never `put()` — see §3.7. Arrays are already fresh per-chunk copies owned by nobody
downstream, so ownership transfers for free.

**Gate: only enqueue when `activeSource == MIC`** (D16). Race-free because `switchSource()`
commits and joins the old capture thread before the new source sets `activeSource`.

Writer thread maintains `bytesWritten` and a rolling CRC32 as bytes pass (zero extra reads), and
**asserts even chunk length** — a single odd `AudioRecord.read` would byte-swap the entire
remainder of the session into full-scale noise. Carry the stray byte forward.

Free space: poll `StorageManager.getAllocatableBytes()` at most once per 30 s, wrapped in
`runCatching{}.getOrDefault(Long.MAX_VALUE)` — fail open, matching `WhisperModelManager.kt:108-111`.
There is no low-storage broadcast to register for; `ACTION_DEVICE_STORAGE_LOW` is not delivered
to manifest receivers on O+.

### 6.4 Retention

| Setting | Value | Rationale |
|---|---|---|
| `keepSessionAudio` | **OFF** by default | The listing was revamped around "audio is never stored" |
| Opt-in moment | Contextual prompt at first failure + Settings switch | Converts the cohort that needs it, when they want it |
| `AUDIO_MAX_AGE_MS` | 48 h (selectable 1 h / 24 h / 48 h / 7 d) | Retry is recovery, not archive. Applied **retroactively** the instant the setting changes |
| `AUDIO_MAX_TOTAL_BYTES` | 512 MB (selectable 250 MB / 512 MB / 1 GB / 2 GB) | Runaway guard; the age pass binds first for any realistic user |
| `AUDIO_MAX_SESSIONS` | 50 | Bounds `list()` cost, crash-recovery scan, and the LazyColumn |
| `AUDIO_MAX_SESSION_BYTES` | 128 MB (~66 min) | Per-session cap |
| Transcript window | **7 days**, selectable 7/14/30/forever | See D19 |

**No hidden scratch buffer.** An always-on writer beneath a switch labelled "Keep the audio of
each session" is an undisclosed writer that survives an explicit opt-out — an enforcement fact
pattern, not a bug report. It would also falsify the policy sentence "off by default and must be
turned on by you". The cost is stated plainly: **the first failure a user ever hits is
unrecoverable**, mitigated contextually rather than silently.

**Sweeper (D18):** `androidx.work`, 12 h `PeriodicWorkRequest`, no constraints, enqueued `KEEP`
from `Application.onCreate`, plus a `BootReceiver` `goAsync` sweep. Every other trigger is
event-driven and stops firing the moment the user stops dictating — without this, "deleted
automatically after 48 hours" is an **affirmatively false statement in a published privacy
policy**, not merely a bug.

**UI copy must state the asymmetry** — "transcripts kept 7 days, audio 48 hours" — or Retry
silently stops working after two days and reads as a bug.

### 6.5 Retry

**Granularity: both modes, one code path.** Whole-session redo is *not* "feed the file to the
engine" — `sendAudio` self-commits every 960,000 bytes, so replaying an hour fragments into ~120
segments cut mid-word, strictly worse than what exists. Both modes replay **stored segment
boundaries** and differ only in `seqs`.

| Session state | Primary button | Overflow |
|---|---|---|
| Zero `Text` segments | **Transcribe** (all seqs) | Pick engine |
| Some text, ≥1 missing | **Retry 3 missing parts** | Redo whole session |
| All text, user dislikes it | **Redo whole session** | — |

**Engine choice:** a sheet, defaulted **away** from what just ran — reusing the engine that just
failed is the worst possible default. But **never pre-select a cloud provider for a session that
succeeded locally**; a bigger local model is the correct default upgrade, and cloud must be a
deliberate reach. Rows carry explicit disabled reasons: "No API key" / "Monthly cap reached" /
"Dictated into a password field — not eligible". Retry **never** writes the global engine
preference.

**What is retryable at all.** Retry requires retained audio, and per D16 only mic audio is
retained. Three cases, and the UI must distinguish them rather than showing a dead button:

| Session `sourceMix` | Audio on disk | Retry offered |
|---|---|---|
| `MIC` | Yes | Full retry, local or cloud |
| `DEVICE` | **None** | **No retry.** Row reads "Transcript only — device audio isn't saved." The media is still on the phone; the honest recovery is to replay and recapture |
| `MIXED` | Mic portions only | **v1: no retry** (fail-closed, see below) |

`MIXED` arises when `switchSource()` runs mid-session. Because `switchSource()` commits and then
joins the old capture thread, **every segment is single-source by construction** — so mic segments
have a byte range and playback segments have none. Per-segment retry is therefore *technically*
well-defined, but v1 takes the session-level fail-closed path
(`cloudEligible` requires `!everUsedPlayback`) because it is one predicate instead of a
per-segment eligibility matrix across playback, password, and integrity state, and because a
partially-retryable session is hard to explain in a row subtitle. Per-segment eligibility for
`MIXED` sessions is a deliberate v1.1 refinement, not an oversight.

**Cloud gating is a positive allowlist**, fail-closed:
`cloudEligible = !everUsedPlayback && !sawPasswordField && audioState in {PRESENT, TRUNCATED}`,
computed at session start and persisted. `sawPasswordField` is sticky and requires new detection —
`isPassword` appears **nowhere** in the repo today.

**Versioning is non-destructive, and the invariant lives in the store.** `SessionStore.appendVersion()`
is the only write path; there is no update-in-place API, enforced by a unit test.
**Do not auto-promote** — keep showing the previous version with an inline bar:
*"New transcription ready — [Compare] [Use it] [Discard]"*. This one rule neutralizes
hallucinated-version-shown-by-default, silent-sentence-deletion, and
cancelled-partial-becomes-canonical simultaneously, and makes Cancel behave like Cancel.

Delta chip is **regression-aware**: whisper hallucination is characteristically *additive*, so a
bare char-delta systematically rewards the failure mode. Compute `charsAdded`, `charsRemoved`,
`segmentsThatLostText`; if the last is >0 the chip is warning-coloured and v1 stays displayed.

**Durability — append-only journal.** On a 5–10 minute background job, process death is a *more
likely* terminator than user cancellation. A batch-end-only write means 30 minutes of audio
uploaded, real money spent, **zero text persisted, no evidence it ran.** Open the version at
batch **start** with `status = InProgress`; after **each part** merge results, settle
`spentMicroCents`, `.part` → fsync → rename. On app start, any `InProgress` demotes to `Partial`
with **Finish transcribing** that re-uploads only the unpaid-for audio.

**Destination: record, clipboard, share. Nothing else.** Retro-injection is impossible (§3.8) and
attempting it is the most dangerous thing this feature could do — injecting into whatever field
is focused at retry time pastes an unrelated old session into whatever the user is currently
typing. Verbatim UI copy: *"Retried text is saved here — copy or share it. It can't be put back
into the app you originally dictated into."*

Note `setPrimaryClip` is a **silent no-op from the background since API 29**, so a completion
notification's [Copy] must be a `PendingIntent` into `MainActivity`. Set
`ClipDescription.EXTRA_IS_SENSITIVE` on all clipboard writes.

**Loop-breakers:** `MIN_RETRY_INTERVAL_MS = 60_000` per (session, engine) on **any** completed
retry, not only failures — otherwise nothing rate-limits "retry roulette" across three providers,
each attempt individually under the confirmation threshold. After 2 attempts an engine stops
being the default; when all have failed the button demotes to secondary with the recording still
playable and exportable. **Never delete audio on retry failure** — pin it for the batch.

**Auto-offer:** total failure with a local model installed ⇒ auto-retry once on device without
asking (free, already the terminal fallback). Partial ⇒ no interruption, just a status line and a
badge. **Absolute rule: never auto-run a cloud retry.** It spends the user's money.

**Cost confirmation above 10¢ or 10 minutes** — not 25¢, because a 40-minute whisper-1 retry is
$0.24, one cent under a 25¢ guard. Estimate from **retained** bytes, not segment durations: if
`audioState == TRUNCATED`, quoting 40 minutes when 5 were saved is a lie.

**Runs in a `dataSync` foreground service** — *not* `microphone`, which would light the privacy
indicator with no mic open. On Android 13+ with `POST_NOTIFICATIONS` denied the FGS notification
is not shown, so the only spend indicator disappears — require the permission before offering
cloud retry.

---

## 6A. TTS playback smoothness

Read-aloud has audible gaps every few seconds during live synthesis. Root-caused 2026-07-27;
this is a **prerequisite** for cloud TTS, because a network producer has worse jitter than local
synthesis and would ride the same broken pipeline.

### 6A.1 Root cause

**Proven from source (sherpa-onnx v1.13.4):** `batch_size = 1` is hard-coded for Kokoro and
`max_num_sentences` is explicitly ignored. Only `.`/`?`/`!` terminate a unit. **No sub-sentence
streaming exists**, so audio genuinely arrives in whole-sentence bursts and the fix must be
entirely consumer-side.

The operative buffer is the in-memory `store`, **not** the AudioTrack. The underrun condition is:

```
UNDERRUN  ⟺  banked_audio < RTF × duration(next_sentence)
```

At RTF 0.577 that means any sentence **more than 1.73× longer than everything banked before it**
starves the pipeline — an ordinary ratio in prose.

Ranked contributors:

1. **No resume hysteresis** (dominant; explains recurrence). After a stall the loop resumes on
   the first 100 ms available (`TtsEngine.kt:203-220`), so no lead is ever rebuilt and it
   re-stalls at the next long sentence. Every heading or one-word sentence re-arms it.
2. **Uncontrolled start prebuffer** — largest single gap; bites when sentence 1 is short.
3. **Unbounded `D_max`** — gap scales with the *next* sentence; no buffer bounds it.
4. **Unverified RTF headroom under production load** (60 fps render loop, thermal soak).
5. Playback thread at nice 0 with no `THREAD_PRIORITY_URGENT_AUDIO`.
6. Sherpa's discarded whole-utterance `float[]` — GC hitch; OOM at the cap.
7. `stalled`/`doneFlag` exit race — clips the last ~160 ms.

Worked example: `"Chapter Three."` (1.0 s) followed by a 45-word sentence (15 s) = **7.66 s of
silence**. Each stall additionally costs 161–403 ms because AudioFlinger forces a full track
re-prebuffer (`FS_FILLING`) after every underrun — which is why they read as discrete *pauses*.

**Explicitly refuted — do not spend effort:** `readAt()` O(n) under the lock (wrong by 4-5 orders
of magnitude; chunks are sentences, not slices); AudioTrack buffer depth (`write()` is
`WRITE_BLOCKING`, so track contents are a *subset* of synthesized audio — enlarging it moves the
gap by **0 ms**); the `onPcmChunk` tap (0.06-0.2% duty).

### 6A.1a MEASURED — Fold 6 baseline, 2026-07-27

Captured with the Release 0 instrumentation on SM-F956U / Android 16.
Raw log: `docs/measurements/2026-07-27-tts-baseline-fold6.log`.
**These numbers supersede the modelled ones above wherever they conflict.**

```
open  bufFrames=9640 bufMs=401 perfMode=0 rate=24000 chars=2198
sent  seq=0  audMs=3262   synthMs=1954   rtf=0.60
sent  seq=1  audMs=23603  synthMs=12347  rtf=0.52
under seq=2  wallMs=9413  renderMs=382   audibleMs=9031  hwUnderD=1
end   ttfwMs=1997 underN=1 underMs=9031 maxGapMs=9031 rtfP50=0.62 rtfP95=0.73 hwUnder=1
```

**Verdict: starvation confirmed** (`hwUnder > 0` AND `audibleMs > 0`). The §6A.1 gap formula
predicted `r·A₂ − A₁` = 12,347 − 3,262 = **9,085 ms**; measured **9,031 ms** — within 54 ms.

**One stall, not chronic starvation.** Eleven sentences, a single gap, at the first boundary.
After the 23.6 s block landed the bank never depleted again — exactly as the model predicts for
uniform prose. The reported "pauses every few seconds" was this one 9-second gap.

**The cause is structural, not slow synthesis.** RTF 0.62 means synthesis ran at ~1.6× realtime.
The device kept up. What broke was a single **23,603 ms** unit (sherpa splits only on `.`/`?`/`!`)
with only 3,262 ms banked against it — a 7.2× ratio against the 1.73× starvation threshold.

**Four corrections to the assumptions above:**

1. **`perfMode=0` — the `PERFORMANCE_MODE_LOW_LATENCY` request was DENIED**; the framework
   granted `NONE` with a 401 ms buffer. §6A.2's keep-LOW_LATENCY argument is **moot on this
   device** — the app is already running in `NONE`. Do not spend effort defending a mode that
   isn't being granted; re-check `performanceMode` on any device before reasoning about it.
2. **Real RTF matches the bench almost exactly — 0.583 vs 0.577, a 1.1% difference.**

   > **Correction, 2026-07-27.** An earlier revision of this section claimed "~7% worse than the
   > bench" by quoting `rtfP50=0.62`. That was wrong: `rtfP50` is an **unweighted** median over
   > callbacks whose durations span 2.5 s to 23.6 s, and the bench's 0.577 is a **duration-weighted
   > aggregate**. Comparing them is apples to oranges. Recomputed from the capture,
   > `Σ synthMs / Σ audMs` = 56,206 / 96,357 = **0.5833** (0.5828 excluding the phonemization-laden
   > first callback). Cross-check: the summed `audMs` lands within 5 ms of the record's
   > `audioMs=96362`.
   >
   > **The device is performing as benched.** There is no meaningful RTF degradation under real
   > load, and the "7% worse" figure must not be cited. The summary record is being amended to
   > emit `rtfAgg=` so this comparison cannot be got wrong again.

   Either way, far below 1.0 — so the §6A.4 "buffering cannot help" regime is **not** in play.
3. **`dutyPct` is broken, not merely unreliable — it printed `100` on the same line that
   reported a 9-second gap.** `audioMs` (96.4 s) exceeded `wallMs` (65.4 s) — a 147% ratio that
   `.coerceIn(0, 100)` silently rendered as "gapless". The final `play` record shows
   `leadMs=45767`: the session ended with 46 s of synthesized audio never played, because
   `audioMs` counts *synthesized* rather than *played* audio.
   A spec caveat is not sufficient for this — the log line itself lies, and the next reader will
   not have this document open. Being fixed in code: drop the upper clamp so >100 self-identifies,
   correct the KDoc, and emit `playedMs=` alongside.
4. **`ttfwMs=1997` matches the advertised ~1.9 s** — and is only trustworthy because the
   `diagT0` cold-load defect was fixed before capture. Unfixed it would have read ~4 s.

### 6A.1b The fix, re-ranked against evidence

**A fixed start prebuffer would NOT have prevented the measured gap.** The first burst was
already 3,262 ms — any watermark at or below that is satisfied instantly and playback begins at
the same moment. Decision D21 ("always prebuffer ~2 s") is therefore **not the fix for the
observed failure** and is deferred.

**Clause splitting is the fix.** Capping a submission at `SPLIT_MAX_CHARS = 300` (~3.5 s of
audio) turns the 23.6 s unit into ~7 chunks needing ~2.1 s each — comfortably under the 3.26 s
already playing. The gap disappears.

It is also **required cloud infrastructure, not local polish**: every provider caps input
(OpenAI ~4,096 chars, ElevenLabs 5k–40k by model, Gemini degrades past "a few minutes") against
this app's 100,000-char selection limit, and Gemini 2.5 TTS returns one complete blob with no
streaming — so an unsplit run-on reproduces this exact failure with a network round-trip added.

**Deferred as unjustified by the data:** the adaptive watermark, rebuffer hysteresis, and the
`isBelowRealtime()` degraded mode (§6A.3, §6A.4). Revisit only if a capture shows chronic
multi-stall starvation or RTF ≥ 0.95.

### 6A.2 Keep `PERFORMANCE_MODE_LOW_LATENCY`

> **Superseded in practice — see §6A.1a.** The measured `perfMode=0` shows the request is denied
> and `NONE` granted on the Fold 6. Retained below as the reasoning for *why not to actively
> switch* to `NONE`, which remains valid on devices where `LOW_LATENCY` IS granted.

Switching to `PERFORMANCE_MODE_NONE` auto-enables `FLAG_DEEP_BUFFER` for this attribute set, and
`flush()` **cannot recall HAL-resident audio** — audible stop goes ~150 ms → ~300-400 ms, breaking
the documented instant-stop guarantee. Worst case: speech over an incoming call on
`AUDIOFOCUS_LOSS_TRANSIENT`. `LOW_LATENCY` is floor-only and does not cap `setBufferSizeInBytes`.

Do size the track buffer in **milliseconds** (`TRACK_BUFFER_MS = 400`) rather than `minBuf * 4`,
which silently ranged 161-403 ms across devices. It absorbs 0 ms of producer stalls by design —
it is writer-deschedule insurance only.

### 6A.3 The fix

A single pure-Kotlin `TtsBufferPolicy` (no `android.*`, JUnit-testable) is the only gate:

```
shouldStart / shouldResume  ⟸  bufferedMs > 0 && (done || bufferedMs >= targetMs() || waitedMs >= capMs)
targetMs() = SAFETY * rtfEwma * dMaxMs + stallBumpMs,  clamped [MIN_WM_MS, MAX_WM_MS]
```

The `bufferedMs > 0` guard on **resume** is a real shipping bug fix: without it, a long drought
lets the cap fire on an empty store and the loop flaps play/pause/`onBuffering` at ~0.4 Hz,
strobing the ring.

**RTF measurement must exclude two contaminants:** skip the first burst of every segment from the
EWMA (it carries whole-text espeak phonemization, which scales with *selection* length, not
sentence length), and measure callback-**exit** to callback-**entry** so the `AHEAD_CAP`
backpressure hold never reads as slow synthesis.

**Decision (owner, 2026-07-27): always prebuffer.** Start and resume both gate on the watermark.
This is the smoothness-over-latency choice; it is made affordable by clause splitting (§6A.5),
which bounds the first chunk to ~2.2 s of audio and therefore satisfies the watermark in ~1.3 s —
better than today's ~1.9 s time-to-first-word.

Playback loop also gets: `THREAD_PRIORITY_URGENT_AUDIO` (in its **own commit**, after the
baseline — see §6A.6); `awaitDrain(...)` with a `cancelled() || seek || paused` abort predicate
(mandatory, or seek/pause latency regresses to 500-1200 ms) replacing the blind `Thread.sleep(150)`;
`store.awaitMoreThan(total, WAIT_TICK_MS = 20)` replacing `Thread.sleep(50)` (data-driven, and
mandatory for cloud packet cadences); pause wall-time excluded from the gate clock; and reporting
the **rendered** position (`cursor - (writtenFrames - playbackHeadPosition)`) so the scrubber
stops leading the audio.

### 6A.4 When RTF ≥ 1, buffering cannot help — say so

At RTF > 1 the speech fraction of wall clock is capped at `1/RTF` by arithmetic. The only free
parameter is **how the missing time is distributed**, and a short resume cap picks the worst
distribution (maximum interruptions).

Above `REALTIME_RTF = 0.95` (over ≥3 samples), switch the resume gate to a bank target
`min((rtf-1) * remainingAudioEstimate, MAX_BANK_MS = 8000)` — at RTF 1.3 that is ~10 s of
preparation then ~35 s continuous, versus a 2.5 s / 8.3 s sawtooth. Emit a one-shot **Degraded**
notice with an estimated prepare time. Above ~2× realtime, detect and refuse, pointing at cloud
TTS. **Never an unbounded spinner** — `LOCAL_STALL_DEADLINE_MS = 60_000`, then drain what is
banked and stop with a real error.

Today the app shows a spinner and no explanation. That is the part users experience as breakage.

### 6A.5 Clause splitting and truncation

**Split sentences over `SPLIT_MAX_CHARS = 300`** (~3.5 s of audio) at clause boundaries, target
`SPLIT_TARGET_CHARS = 190`. Normal prose keeps its prosody. This bounds `D_max`, improves
time-to-first-word, and — **not optional** — bounds sherpa's discarded whole-utterance array from
**~173 MB at the retention cap to ~100 KB**. That array is an OOM-class defect today on a
no-`largeHeap` app. A/B the split aggressiveness on comma-spliced prose, where the seam is most audible.

**Surface a distinct `Truncated` outcome.** The 100,000-char selection limit (~96 min) and the
30-min `RETAIN_CAP` disagree by 3.2×, so "Select all → Speak" silently drops ~69% of the content —
and after the completion-guard fix it would report a pristine 100% done. Add a "continue from here"
speak-from-offset entry point, plus an up-front duration estimate.

### 6A.6 Ship order — diagnostics before fix

**Decision (owner, 2026-07-27): measure first.** Every watermark constant above is arithmetic on
one cold bench run on a Fold 6. Land them in three bisectable commits:

1. **Diagnostics only.** `WE-TTS` `TTSDIAG` single-line key=value records: `open` (granted
   `bufferSizeInFrames`, performance mode), `sent` (seq, samples, audMs, synthMs, per-sentence
   RTF), `play` (lead ms at each boundary crossing), `under`, `end` (ttfwMs, underN, underMs,
   maxGapMs, dutyPct, rtf p50/p95/max). Sample `getUnderrunCount()` (API 24; minSdk 26, so
   unconditional) at track creation, stall entry, stall exit, and end — paired with
   `playbackHeadPosition`, re-baselined after every `flush()`.
   **`audibleMs = wallStalled − renderedDuringStall` is the only honest silence measurement** —
   `underrunCount` alone says we fed late, never how much silence the user heard.
2. **Policy + watermarks + correctness fixes** (including `doneFlag` in a `finally`, and the
   truncation race).
3. **Store / tap / segmentation**, and `THREAD_PRIORITY_URGENT_AUDIO` separately so it does not
   contaminate the baseline.

**Triangulation that can refute this whole diagnosis:**

| `hwUnderrunΔ` | audible silence | Verdict |
|---|---|---|
| > 0 | > 0 | Starvation confirmed |
| > 0 | ~0 | Thread descheduling — different fix |
| ~0 | ~0 | **Diagnosis wrong.** Next: sherpa `silence_scale=0.2` inter-sentence padding, truncation race |

Known blind spot: `audibleMs` cannot resolve gaps shorter than one AudioTrack buffer
(~160-400 ms). If the real complaint is rapid sub-buffer chops rather than multi-second pauses,
the instrumentation reports a clean pass and the symptom persists.

### 6A.7 Bugs to fix regardless of measurement

- **Stranded playback thread.** A permanently-failed chunk throws out of the drain loop, skipping
  `doneFlag.set(true)`. The playback thread never exits, leaks the AudioTrack, and `onDone` tears
  down the stop button and scrubber while banked audio keeps playing **unstoppably, with audio
  focus already abandoned**. Fix: `doneFlag` in a `finally`; record the failure and return
  normally so banked audio drains. The OOM path strands the thread today.
- **Single-appender invariant.** Today the store is a local `ArrayList<ShortArray>` inside
  `speak()`, guarded by `synchronized(store)`, with exactly one appender (the sherpa callback on
  the executor thread) — so it is currently safe. There is no `PcmStore` class yet. The invariant
  becomes load-bearing when the extracted store meets the cloud design's worker pool: add a
  debug-build single-appender assertion **as part of that extraction**, not before. The failure
  mode would be silent index corruption, not a deadlock.

---

## 7. Credentials and security

### 7.1 Storage

Replace `EncryptedSharedPreferences` with **direct Android Keystore AES-256-GCM** wrapping values
in DataStore under `noBackupFilesDir`. Delete the plaintext fallback path entirely (§3.5).

```kotlin
data class ProviderAccount(
    val providerId: ProviderId,   // enum NAME is the key, never the ordinal
    val key: String,
    val endpointOverride: String? = null,   // ElevenLabs data-residency hosts
)
```

**Key by enum name, never ordinal** — reordering would silently repoint every user's saved
provider and credential mapping. Back-fill the existing `openai_api_key` into
`ProviderAccount(OPENAI, key)` on first run, keeping the old key readable for one version.

Model selection is a **(provider, model) pair**, not provider alone: OpenAI alone exposes
whisper-1, gpt-4o-transcribe, gpt-4o-mini-transcribe and gpt-4o-transcribe-diarize with
genuinely different capabilities.

**Store the selection as a `StateFlow`, not a plain var** — the long-lived `FloatingBubbleService`
caches its engine for the process lifetime and only nulls it in `onDestroy`, so a non-reactive
pref would keep serving the old provider until the service restarts.

### 7.2 Logging

**Add an explicit logcat redaction rule before the first cloud request ships.** `WE-DIAG` logs
liberally and any request dump would put the user's key in logcat.

### 7.3 Encryption at rest for the archive: no, in v1

`androidx.security:security-crypto` shipped stable and fully deprecated simultaneously, with no
migration guide. The threat-model delta on an FBE-mandatory device is close to zero: a
permissionless app cannot open the sandbox; adb/cloud backup is already excluded by location;
BFU extraction is already covered by FBE. The only case with a real answer is AFU forensics, and
the only config that helps is `setUserAuthenticationRequired(true)` — **which makes the key
unobtainable while the screen is locked and therefore kills background dictation, the core use case.**

Worse, **key invalidation would silently destroy the archive**: Keystore keys are dropped when
the user removes their screen lock and on new biometric enrollment. Every retry button breaks at
once, with no user-diagnosable cause.

Spend the budget instead on: `noBackupFilesDir`, default-off, a 48 h window, `FLAG_SECURE` plus
an optional `BiometricPrompt` gate on the Transcriptions screen (**honestly labelled a UI lock**),
an obvious delete-all, and visible disk usage. Revisit only if audio ever leaves the sandbox.

Also ship `android:manageSpaceActivity` deep-linked to the archive. Today the only OS-level
control is "Clear storage", which destroys API keys, the whisper model, the Kokoro voice and
every transcript in one undifferentiated tap. A bubble-only user never opens the app UI, so
Android Settings is their *first* contact with the storage number.

---

## 8. Compliance

**Not legal advice. Have counsel review the Play submission and the drafted clauses.**

### 8.1 Data Safety

On-device-only retention is **not** "collection" — Play's definition is transmission-based.
**But the cloud STT work changes the form regardless:** `Audio files → Voice or sound recordings`
becomes **Collected = Yes, Shared = Yes**, purpose "App functionality", marked **optional**
because it is opt-in. Plus the accessibility-selected text.

The ephemeral-processing exemption is **not available** — it requires retention "no longer than
necessary to service the specific request in real-time", which is factually untrue for Gemini's
unpaid tier and ElevenLabs' default.

### 8.2 Prominent disclosure

Must be **in-app, modal, anchored to the cloud toggle**, describe the data and how it is
used/shared, not be bundled with unrelated disclosures, and require affirmative action.
Back-press / tap-away / auto-dismiss must not count as consent.

Complication: `RECORD_AUDIO` is already granted for on-device use, so **there is no runtime
permission prompt to anchor to** — the cloud toggle itself must be the trigger, before the key
field is usable.

**Three different provider disclosures, not one.** "Your data goes to a third party" is
materially inaccurate for OpenAI (retains nothing, trains on nothing) in a way it is not for
Gemini free tier (trains, human review) or ElevenLabs (trains by default, account-level opt-out).

### 8.3 The Notification Access bullet is a fifth falsity nobody listed

Media detection runs on `MediaSessionManager.getActiveSessions` authorized by the **Notification
Listener**, plus an `AudioManager` polling fallback — *not* the accessibility API. So its in-app
justification ("used only to detect when audio/video is playing") becomes false once that
permission is the trigger for capturing third-party audio. **Rewrite it too.**

### 8.4 Call recording and `USAGE_UNKNOWN`

`AudioPlaybackCapture` cannot reach `USAGE_VOICE_COMMUNICATION`. But this app opts into
`USAGE_UNKNOWN` (`PlaybackAudioCapturer.kt:43`), which is where players that never set
`AudioAttributes` live — sloppy VoIP, telehealth, WebRTC-in-a-WebView.

Matching `USAGE_UNKNOWN` is the difference between "we cannot record calls" and "we can record
the calls made by badly-written apps", and that distinction **is** the defence in the twelve US
all-party-consent states. **Nobody can honestly publish "Android does not permit us to capture
call audio" while matching `USAGE_UNKNOWN`.** Drop it, add an `AudioManager.getMode()` interlock,
and do not archive device audio at all (D16).

### 8.5 The privacy policy is already inaccurate today

`privacy_policy.html:80-84` enumerates locally-stored data as model files and usage statistics and
**omits transcripts entirely**, while `:127` admits they exist. There is currently an
**undisclosed 14-day plaintext store of dictated passwords and medical history.** Correct this as
a present-tense fix, not buried inside a forward-looking change note.

Then rewrite `:64` ("We do not collect your audio recordings") and `:100` ("Audio is processed
locally and is never stored permanently or sent anywhere"), the Notification Access bullet, and add:

- **(A)** audio stored on your device — concrete window, cap, delete paths
- **(B)** **what a recording may contain** — "if you dictated a password, a PIN, a card number, a
  health detail, that audio is in the stored file until it expires or you delete it"
- **(C)** recordings may contain other people; user responsibility for local recording law
- **(D)** a headed **Data retention and deletion** section stating both windows
- **(F)** a dated change note: *"Earlier versions of this policy stated that audio was never
  stored. From version 3.3.0, Whisper Everywhere can optionally keep session audio on your
  device. This is off by default and must be turned on by you."*

That sentence is only true if there is no hidden scratch buffer — which is why there isn't one.

**Code strings changing in the same commit:** `TranscriptStore.kt:6`, `TranscriptsScreen.kt:30`,
`TranscriptsScreen.kt:61`, `StreamingAudioRecorder.kt:16-17`, `HomeScreen.kt:213`.
**Whatever the master toggle actually does must be the identical sentence in all nine places.**

### 8.6 GDPR

Voice recordings are personal data but **not automatically Art 9 biometric data** — Art 4(14)
requires processing "which allow or confirm the unique identification"; STT is not speaker
recognition. The *content* is routinely Art 9 regardless, which is the real reason for short windows.

Local-only handling has a genuine non-controller argument, but you are shipping cloud STT in the
same release where you are unambiguously a controller. **Arguing you are not a controller for the
on-device slice buys nothing and costs credibility. Build to controller standards and say so.**

Art 17 erasure = per-session delete + delete-all (and because you hold nothing, uninstall +
in-app delete is *complete* erasure — state that). Art 5(1)(e) = the periodic worker, without
which the published window is fiction. Art 32 = sandbox + explicit backup exclusion +
`FLAG_SECURE`, with the encryption decision documented as **a reasoned trade rather than an omission**.

A DPIA is likely not required, but write a two-paragraph "DPIA screening — not required, because"
note and keep it. It costs an hour and it is the first thing a regulator asks for.

---

## 9. Release sequencing

> **Planning note.** This spec deliberately spans six releases and is **too large for a single
> implementation plan**. Each release gets its own plan → implementation → review cycle.
> **Start with Release 0** (TTS diagnostics — smallest, and it unblocks a bug the user is hitting
> today), then **Release A**. A is independently shippable, ships no cloud code, and produces the
> benchmark data that several Release C decisions depend on. C and D should not be planned until
> A's bench numbers exist.

**Release 0 — TTS diagnostics + the two unconditional bug fixes.**
`TTSDIAG` instrumentation (§6A.6 commit 1), plus the stranded-playback-thread fix and the
`PcmStore` single-appender assertion (§6A.7), which are correct regardless of what the
measurement shows. Owner runs one read-aloud and returns logcat. **No tuning until that lands** —
every watermark constant is currently arithmetic on one cold bench.

**Release 0.1 — the TTS buffer policy**, once the measurement confirms (or refutes) the diagnosis.
§6A.6 commits 2 and 3.

**Release A — local only, no cloud code, zero user-visible regression.**
Segment identity + `SegmentOrderer` (pass-through at N=1) + enqueue-race fix + terminal-callback
contract + adaptive silence floor + `SegmentQuality` repetition gate + argmin-RMS hard cut +
`AudioMath.peak` + `RetryPolicy.delayOverrideMs` + lift the four lifecycle methods onto the
interface + move `sweep()` out of the `isNotBlank` guard + harden the session write path
(`CoroutineExceptionHandler` on `serviceScope`, `runCatching` in `TranscriptSink.append` and
`TranscriptStore.save`). Make `pauseMs` injectable, **default stays 800**.
**Write `WhisperBenchTest`** (§11).

**Release B — credentials + toolchain.**
Delete the plaintext fallback, Keystore rewrite, backup-rule allowlist, logcat redaction,
AGP 8.13.2 / compileSdk 36, OkHttp 5.4.0 + R8 rules.

**Release C — cloud STT + TTS, batch.**
Provider catalog, policies, `CloudTranscriptionEngine`, `FallbackTranscriptionEngine`,
`TtsSynthesizer`, connectivity, fatal classification, rate gate, spill, target-binding hardening,
graduated degradation UX, Settings + nudge, prominent disclosure, all document changes.
**`pauseMs` stays 800 for both local and cloud** — per D12 the flip happens in its own release,
after `WhisperBenchTest` and real-device feedback exist.

**Release E — the hangover flip.** Change the default to 500 ms once measured, with 800 ms
available as "Relaxed". One-line default change plus a changelog entry; all prerequisite work
(injectable `pauseMs`, the `PreferencesManager` hook, N-way merge) landed in A and C.

**Release D — audio archive + retry.**
Writer, store, sweeper (`androidx.work`), Transcriptions screen rework, retry runner, versioning.

**Do not ship the hangover reduction and cloud dispatch in the same release** — hence Release E
being separate. At 800 ms, five rapid monosyllables coalesce into one segment and the
short-utterance pathology is dormant; at 350 ms it becomes live. 500 ms is the floor that is safe
without the N-way merge fix (four 400 ms inter-word gaps do not split at 500 ms). Shipping a
300 ms latency win and a fragmentation regression in the same release as cloud would make the two
indistinguishable in bug reports.

---

## 10. Testing

**Pure JVM, no Android, no mocks** (matching `AudioSourcePolicyTest` / `SpeechSegmenterTest`):
`SegmentOrderer`, `SegmentQuality`, `AudioGuard`, the coalescer, `SttProviderPolicy`,
`FallbackPolicy`, `ProviderCatalog`, `SessionManifest` serialization, retention sweep arithmetic.

**Via `FakeHttpTransport`:** every provider adapter — request shape, error classification,
`Retry-After` handling, quality gate on canned garbage responses.

**Instrumented (budget for these; nothing above covers them):**
target-binding failures — bound field destroyed mid-drain, bound field backgrounded, session 2
starting during session 1's drain. Assert **zero `performAction` on any node outside
`sessionTargetWindowId`**, assert clipboard + notification on a backgrounded target, assert
session 1 text never reaches session 2's target.

**Fixtures:** the 2026-07-18 YouTube repetition sample for the quality gate; `jfk.wav` (already in
`androidTest/assets`, currently unused) for `WhisperBenchTest`.

**Debug assertions:** every seq returned by `commit()` reaches exactly one terminal state;
`orderer.pendingCount() == 0` after teardown; `commit()` completes in <1 ms.

---

## 11. Must verify before or during implementation

Ranked by how much a wrong answer would cost.

1. **No measured local whisper latency exists in this repo.** The only bench harness is
   `KokoroBenchTest` (TTS). Every local-path number in §5.7 is modelled. This is the single most
   decision-relevant number in the document and it is currently a guess.
   **Write `WhisperBenchTest` in Release A** — measure per-utterance latency at 1/3/8/15 s, CPU and
   GPU, per catalog tier.
2. **Per-request billing granularity.** OpenAI's minimum duration / rounding increment is not
   officially documented; only third-party aggregators claim per-second with no minimum. Confirm
   with a billing test (20 × 3 s requests, read the dashboard) — if a minimum exists, VAD chunking
   is dramatically more expensive than modelled.
3. **Provider inference latency for short audio is unverified** for OpenAI, Gemini, and ElevenLabs
   REST. One afternoon: 50 reps per provider from a tethered phone, report p50/p95.
4. **Channel count is undocumented** on OpenAI TTS; bit depth and endianness are undocumented on
   ElevenLabs TTS and Gemini TTS. All three are near-certain (signed 16-bit LE mono) but a wrong
   assumption produces loud static or half-speed audio. **Validate empirically on day one**
   (48,000 bytes per second of 24 kHz audio ⇒ mono).
5. **OpenAI's SSE event-schema reference 404s today** — the delta event name (`speech.audio.delta`)
   could not be verified. Capture a live response before coding against it.
6. **GPT-4o mini TTS status is ambiguous** — the models catalog renders a "Deprecated" badge while
   its own model page reports GA, with no shutdown date and no successor. Pin the dated snapshot,
   keep `tts-1` wired as a fallback, and remap voices on fallback (13 voices vs 9) or it 400s.
7. **ElevenLabs realtime session limits are unpublished** (`session_time_limit_exceeded`,
   `chunk_size_exceeded` thresholds). Only matters if streaming is revisited.
8. **Silero `n_window` is unverified** — read from the model header, absent from this repo's source.
   Only matters if Silero is ever moved onto the 32 ms capture path.
9. **whisper.cpp `initial_prompt` truncation at 224 tokens** is inferred, not verified in the
   vendored source. Verify before finalizing the rolling-context budget. Note that exposing
   `initial_prompt` locally is a JNI signature change plus an `.so` rebuild.
10. **The premise that short independent utterances measurably hurt accuracy is unmeasured in this
    app.** Before spending the JNI rebuild, A/B ~20 recorded dictations with `initial_prompt` on/off.

**Known disagreement, recorded rather than resolved:** one adversarial reviewer recommended
forcing `maxInFlight = 1` whenever the injection target is a clipboard-path app (documents,
social), since sequencing cannot be enforced through clipboard + `ACTION_PASTE`. The reasoning is
sound but N=1 makes those apps strictly slower than local for back-to-back speech, and the actual
placement problem is the target app's cursor, which N=1 does not fix either. Concurrency is left
at 3 for all targets with the reorder buffer guaranteeing PASTE calls are *issued* in order.
**Revisit if field reports show scrambled text specifically in document/social apps.**

---

## 12. What this costs

- **New dependencies:** OkHttp 5.4.0, `androidx.work`. Plus the AGP/compileSdk bump (owed to Play
  by 31 Aug 2026 regardless).
- **New code surface:** ~15 new classes, most of them pure and unit-testable.
- **Disk:** 0 by default (archive is opt-in); 512 MB ceiling when enabled.
- **Review risk:** real and concentrated in one place — an app with accessibility permissions
  adding cloud transmission is the combination reviewers scrutinize hardest. Mitigated by keeping
  MediaProjection audio on-device, keeping every declaration truthful, and shipping the disclosure
  correctly.
- **Money, to the user:** ~$0.60/month for heavy dictation on OpenAI batch; under $0.10 on Gemini
  lite tiers. Cost is not the barrier — the *fear* of cost is, which is what the hard cap addresses.
