# 4.4.0 — The on-device word-for-word previewer

**Date:** 2026-09-10 · **Target release:** 4.4.0 (versionCode 89) · **Status:** design on the RECOMMENDED rulings — R1–R6 are ASSUMED, each marked below, none yet ruled
**Origin:** the owner's ruling "my goal is word for word" (2026-09-09) and three authorities, in order: (1) the measurements — `docs/measurements/2026-09-10-tab-sherpa-rung3.md` (the Tab) and `docs/measurements/2026-09-10-zipformer-en-pc-rung1.md` (the PC); (2) the research design `docs/superpowers/research/2026-09-09-streaming-local-tier-research.md` §1–§6; (3) the code. Every `file:line` below was read on `main` at `5efc2be` (versionCode 88 / 4.3.4 — the sherpa-onnx AAR is already 1.13.7 and `sessionLanguageFor` already exists, so the research doc's B0 and T1 prerequisites are DONE and its line numbers are superseded by the ones here).

The previewer is a **tee**: PCM is mirrored to a sherpa-onnx streaming Zipformer whose cumulative text paints the bubble strip while the user speaks; `LocalWhisperEngine` (CPU) or the NPU tier keeps every commit, every resolution and the typed transcript exactly as today. It adds one 72.7 MB download, one strip-ownership rule input, one language gate and zero new native bytes.

---

## 0. The rulings this spec assumes

The owner has not ruled R1–R6. The spec is written on the research doc's recommendation for each; every dependent sentence below cites its ruling. If a ruling flips, the block says what moves.

> **RULING ASSUMED (R1):** ship with the load-time canary as the ONLY guard against the FEAT_SME silent-miscompute class (sherpa-onnx #3845 on SM8850 — `NpuFleetCensus.kt:142-145` names `SM8850-AD` as a census family; no S26-class device is reachable for a pre-promote check), plus one release-notes sentence naming the gap: "On some 2026 flagships the live-words preview may stay blank; the typed transcript is unaffected." — flips to **a device check before promote** if the owner obtains an SM8850 device: the canary stays, the sentence goes, and Z11 runs on that device too.

> **RULING ASSUMED (R2):** while a local preview is active the "(N in queue)" in-flight label is **DISPLACED** — the strip carries the words instead, and `queue:` in logcat keeps the depth. — flips to **shared** (label appended after the words) or **moved** (label into the window) if the owner rules otherwise; the flip is one pure function, `inFlightStripLabel(depth, sessionHasLocalPreview)` (§4.2), and its render row.

> **RULING ASSUMED (R3):** **default-on, additive** for a fixed-English user with the pack installed: a new `localPreviewEnabled` preference defaults `true`; the whisper step of onboarding stays the mandatory one and the pack is offered as an extra. — flips to **opt-in** by changing the default to `false` and the Settings row's copy; nothing else moves.

> **RULING ASSUMED (R4):** **Auto = whisper only on multilingual tiers; Auto on `pro` = English.** The gate reads the language `sessionLanguageFor(installedScope, selection, LOCAL)` resolves (`FloatingBubbleService.kt:203-209`), which already turns Auto into `"en"` on an ENGLISH-scope tier and leaves it `null` on a multilingual one. — flips to **Auto + pack ⇒ English partials regardless** by accepting `null` in `localPreviewArms` (§5); the truth-table test is the diff.

> **RULING ASSUMED (R5):** the streaming tier is **4.4.0 at versionCode 89** (88 = 4.3.4 is Gemini live, `app/build.gradle.kts:53-54`). — flips to **4.3.5 / 89** if the owner keeps the minor for something else; the identity commit is the controller's either way (§11).

> **RULING ASSUMED (R6):** the streaming week runs **before** anything else on the engine calendar (owner's 2026-08-27 rule: local sovereignty outranks cloud features). — flips to **after** with no change to this document; only the calendar moves.

---

## 1. What the user gets, and does not get

**Gets.** With English resolved for the session and the 73 MB pack installed, lowercase unpunctuated words appear on the bubble strip while they speak — measured on the Tab S10+ at **p50 0.40 s / p95 0.52 s** behind the word's last frame (n = 100 words, `rung3 §5.1`), on every device the app ships to, with nothing leaving the phone. The strip is replace-only and never un-types a word (0 retractions in 134 runs, `rung3 §4`). The transcript typed at stop is **unchanged**: whisper's, cased, punctuated, with numerals, in 100 languages on `multi` / turbo.

**Does not get.** Words in any language but English (the only licence-clean small streaming model is English-only, research §2.3); casing, punctuation or digits on the strip (the vocabulary has no digit piece — 497 uppercase BPE pieces, `rung1 §1.3`); a change to what is typed; per-token language switching; anything on the NPU (the previewer runs on two CPU threads beside whichever tier commits).

**The honest comparison (measured, `rung1 §5`):** on `jfk.wav` the previewer's final reads `and saw my fellow americans ask not what's your country can do for you as but you can do for your country` — WER 0.182, four unstressed monosyllables wrong, every content word right, while whisper-small scores 0.000 on the same clip. On flatline-gated audio (edited video) the previewer loses a content word (`country → cutter`, WER 0.318) where whisper loses none — which is exactly why the previewer paints and whisper types.

---

## 2. Measured constants — the numbers this design is built on

Every value below is a MEASUREMENT with its provenance, carried into code as a named constant. None is inferred.

| Constant | Value | Where it lives | Provenance |
|---|---|---|---|
| `NUM_THREADS` | **2** | `StreamingPreviewEngine` | max-pace RTF 0.054 @ 2 vs 0.072 @ 4 (four threads are SLOWER — ORT hand-offs on 16 ms bursts) and 0.051 @ 1; two is the number; `rung3 §5.4`, §10.3 |
| `PAD_MS` | **500** | `StreamingPreviewEngine` | the canary loses `VE` at 450 ms and keeps `FIVE` at 500 on both the PC and the Tab; 800 buys nothing; `rung1 §4`, `rung3 §5.3` |
| token join | **concatenate; a LEADING SPACE opens a word** | `PreviewText` | the AAR's JNI returns `" ONE", " TWO", " F", "OUR"` — U+2581 is already an ASCII space by the time Kotlin sees it, on 1.13.7 and 1.13.4 both; `rung3 §2.2`, §10.10 |
| decode cadence | **320 ms** (encoder metadata `T=45`, `decode_chunk_len=32`) | design fact, pinned in the fake | one `decode()` per 320 ms of audio; 9 of every 10 32 ms feeds leave `isReady` false; `rung3 §7`, `rung1 §3.1` |
| partial lag | **p50 0.401 / p95 0.523 / max 0.526 s** (wall) | acceptance Z1, Z3 | `rung3 §5.1`; audio-clock 0.360 / 0.480 = the PC's structural floor; the device adds ~40 ms per burst |
| `DeltaThrottle` | **150 ms, kept** | `transcription/DeltaThrottle.kt:18` | partials arrive every 320 ms, so at steady state the throttle never suppresses a real partial; it only thins the commit-time drain burst, and a freeze bypasses it (§4.3) |
| RTF, compute | **0.054** max pace; **0.116** at real-time pace (cold cores); **0.099** beside a 4-thread busy loop | budget §7 | `rung3 §5.4-5.5`: real-time pace costs 2× max pace on this SoC because the core down-clocks between 32 ms wake-ups; a busy load makes it CHEAPER |
| RSS budget | **+170 MB** resident while loaded | budget §7 | before load 180 → end of a 10-minute run 349 MB (+169; peak snapshot +191 with the probe's own accumulation); after `release()` the ORT libraries stay mapped (+140); `rung3 §6` |
| thermal | **+1.2 °C / 10 min, 0 status steps** (streamer alone, on charger) | budget §7, Z3 | `rung3 §6`; the streamer-beside-whisper 10-minute run was NOT executed — Z3 is where it is read |
| retractions | **0** in 134 runs (greedy) | strip contract §4.3 | `rung3 §4`; `modified_beam_search` costs +40 % RTF and introduces 4 retractions — **greedy, never beam** (`rung1 §7`) |
| canary text | `ONE TWO THREE FOUR FIVE` — exact, 8 of 8 runs on 1.13.7, exact on 1.13.4 | `PreviewCanary` | `rung3 §4`; the clip is `app/src/main/assets/canary_digits.wav`, 81,998 B, 2.560 s, sha256 `a3079109f735d4acea2756ce5398c67119ab36fa832571f5a5b45b616a7a5cd4` |
| `provider` | **`"cpu"` only** | `SherpaPreviewRecognizer` | `provider=nnapi` logs `Android NNAPI requires API level >= 27. Current API level 21 Fallback to cpu!` — the AAR is built against API 21 and the NNAPI EP is compiled out; `nospin` is slower (0.076); `rung3 §5.6`, §5.4 |
| load / warm-up | 802–860 ms / 20–28 ms first decode | prewarm §3.1 | `rung3 §5` |
| first partial | canary `ONE` at audio 1.44 s; `jfk` `AND` at 1.12 s | Z1's expectation | `rung1 §3.1` — `T = 45` frames must exist before the first decode, then the first word must complete |
| strip case | **lowercase** | `PreviewText` | the model emits ALL CAPS (`rung1 §1.3`); the strip must not shout |

**AAR and model, pinned.** `app/libs/sherpa-onnx-1.13.7.aar`, 49,113,869 B, sha256 `c4ef49e309f24fcee5c106b8a279481aaecaabb078cd37b2cd6e9a62cc8a73c8` (`app/build.gradle.kts:596-598`, dependency `:831`), carrying ONNX Runtime **1.27.1** (read out of the binary and out of `VersionInfo` at run time, `rung3 §1.2`, §7). The model is `streaming-zipformer-en-2023-06-26` at the immutable HF commit `672fbf1b30579d6585301139bb363f42a0ad4a24` — four files, §6.

---

## 3. Components

All new code is Kotlin under `app/src/main/java/com/whispereverywhere/transcription/stream/` unless stated. One-line responsibilities:

| Component | Responsibility |
|---|---|
| `StreamingPackCatalog` (pure) | The four pinned files (name, bytes, sha256), the base URL at the commit sha, the directory name, the marker text, the "73 MB" badge |
| `StreamingPackInstall` (pure, `java.io.File`) | `isInstalled` (marker + four files at exact byte counts), `verify` (size gate then sha256 per file), atomic `install` (temp dir → rename, marker written LAST), `delete`, `markCorrupt` |
| `StreamingPackManager` (Android) | The `TtsModelManager` shape (`tts/TtsModelManager.kt:33-45`, `:52-136`): free-space gate, stale `DownloadManager` rows, four sequential downloads to the external files dir, then `StreamingPackInstall` |
| `PreviewRecognizer` / `PreviewStream` / `PreviewRecognizerFactory` (seam) | The five calls the engine makes — `createStream / acceptWaveform / isReady / decode / result / release` — so the loop is JVM-testable with a fake; **no test may reference `com.k2fsa`** (the AAR's static init loads a native library) |
| `SherpaPreviewRecognizer` (production adapter) | Wraps `com.k2fsa.sherpa.onnx.OnlineRecognizer` with the probe's exact config (`tools/probes/litertlm-probe/.../SherpaProbe.kt:110-127`): `FeatureConfig(16000, 80, dither 0)`, `modelType = "zipformer2"`, `provider = "cpu"`, `numThreads = 2`, `enableEndpoint = false`, `decodingMethod = "greedy_search"`, `assetManager = null` |
| `PreviewCanary` (pure over the seam) | Feeds `canary_digits.wav` in 512-sample chunks + `PAD_MS` zeros, drains, and asks `GpuCanaryPolicy.canaryPasses` (`transcription/GpuCanaryPolicy.kt:57-71`) — empty text is the #3845 signature, garbage the #3791 one |
| `StreamingPreviewEngine` | Owns one recognizer + one open stream on its own single-thread executor; the 32 ms feed, decode-while-ready, the cumulative partial, the commit hook (pad / drain / freeze / release + `createStream`), the retained-tail ring, dedupe, the retraction counter, the `stream-timing:` line |
| `PreviewText` (pure) | Lowercase + trim of a partial; the timestamp trim of a frozen text at a cut point (tokens joined by concatenation — the leading space is the word boundary) |
| `PreviewComposer` (pure) | `frozen[seq…] + partial` → the strip text; `freeze(seq, text)`, `resolve(seq)`, `onPartial(text)` |
| `PreviewTeeEngine : TranscriptionEngine` | The `FallbackTranscriptionEngine` decorator shape (`transcription/cloud/FallbackTranscriptionEngine.kt:195-210`): `sendAudio` to `local` first then the preview; commits forward to `local` and freeze the preview under the seq `local` returned; `onSegmentResolved` from `local` only; `onDelta` from the composer only — `LocalWhisperEngine`'s own deltas and its terminal blank (`LocalWhisperEngine.kt:600`) are swallowed |
| `LocalPreview` (interface) | The four calls the tee makes on the engine — `open / sendAudio / commit / close` — so the tee is testable with a fake |
| `localPreviewArms(...)` (pure, `FloatingBubbleService.kt` beside `sessionLanguageFor`) | The language + pack + session-kind + batch + preference + canary gate (§5) |
| `deltaOwnsPreviewStrip(sessionIsLive, sessionHasLocalPreview)`, `inFlightStripLabel(depth, sessionHasLocalPreview)`, `deltaBlankVisibility(...)`, `resolvedTextClearsStrip(...)` (pure, `FloatingBubbleService.kt:324-396` today) | The strip-ownership rules with the second input (§4.2) — the P0 prep commit, behaviour-neutral until a producer exists |
| `StreamDiag` (pure) | The three greppable lines — `stream-open:`, `stream-timing:`, `stream-gate:` — numbers and codes only, never text (§8) |
| `StreamingPackCopy` (pure) | Every user-facing string of the feature (§9), banned-word-tested |
| `PreferencesManager.localPreviewEnabled` | The R3 switch, default `true`, with a `StateFlow` mirror for the Settings row (the `sttLiveModeGemini` pattern, `data/local/PreferencesManager.kt:400-409`) |
| `WhisperEverywhereApp.streamingPackManager` | `by lazy`, beside `whisperModelManager` (`WhisperEverywhereApp.kt:35-37`) |

What is NOT a component: a `WhisperModel` row, a `pairedArtifact`, a Play asset pack, a `NativeComputeGate` hold, sherpa's endpointer, a punctuation model, a second language.

---

## 4. Data flow

### 4.1 Session lifecycle

1. **Service start.** Beside `warmLocalEngine().prewarm()` (`FloatingBubbleService.kt:783`), if the pack is installed and `localPreviewEnabled`, `warmStreamingPreview()` builds the resident `StreamingPreviewEngine` (Main-confined field beside `localEngine`, `:443`) and posts `warm(packDir)` to its executor: load the recognizer (≈ 0.8 s), run the canary (≈ 0.2 s of compute over 3.06 s of audio), log `stream-open:`. A failed or absent canary sets `disabled` for the life of the process (§7.2) and releases the recognizer.
2. **`startRecording`** (`:2839`). `resolveTranscriptionEngine()` (`:2640-2799`) is untouched: it still returns `local` for the three `LOCAL_*` arms and resets `sessionIsLive = false` (`:2672`) — P0 adds `sessionHasLocalPreview = false` on the next line. After the language resolves (`:2932-2937`) and before `engine.connect` (`:2940`), the ONE wrap site runs `localPreviewArms(...)`; when it answers true the session engine becomes `PreviewTeeEngine(streamingPreview, local)`, `transcriptionEngine` is re-pointed at the tee, `sessionHasLocalPreview = true`, and `stream-gate:` is logged either way.
3. **`connect`.** The tee opens the preview (`createStream`, composer reset) then `local.connect(lang, relay)`; `onOpen` is the local engine's, forwarded — `showSessionPreview(live = sessionIsLive)` (`:3018`) still starts the strip GONE.
4. **Every 32 ms** the capture thread (`util/StreamingAudioRecorder.kt:80`, 1,024-byte reads, URGENT_AUDIO) calls `engine.sendAudio(chunk)` (`:2061`): the tee forwards to `local` (its buffer append, `LocalWhisperEngine.kt:198-208`) and then `offer`s the chunk to the preview's bounded queue (capacity 128 chunks ≈ 4.1 s, the `LiveTranscriptionEngine.kt:626` backlog figure). A successful offer schedules one `drain()` on the preview executor; an overflow drops the chunk, marks the segment `shed`, logs once. **`sendAudio` never blocks and never decodes** — the capture thread already carries the inline Silero probe.
5. **`drain()`** (preview executor): poll the queue empty, write the PCM into the 3 s ring, `pcm16ToFloat` (`util/AudioMath.kt:44-56`), `acceptWaveform`, `while (isReady) decode()` (each decode timed), `result().text` → `PreviewText.normalize` (trim, lowercase) → if changed since the last emit and the throttle allows → `composer.onPartial(text)` → `listener.onDelta(composed)`. The service's `onDelta` (`:3031-3073`) hops to Main and paints — the same path CLOUD_LIVE uses today.
6. **Every commit** — VAD (`:2089`), CAP (`:2179`), SWITCH (`:2324`), STOP (`:3204`) — goes through the one funnel `commitSegment` (`:3464-3481`) into `engine.commit(evidence)` / `commitRetainingTailMs(retainMs, evidence)`. The tee forwards to `local` FIRST (its seq, its `EmptyExpected` floor, its buffer cut — all unchanged) and then, only for `seq >= 0`, posts `preview.commit(seq, retainMs)`: drain what is queued, feed `PAD_MS` of zeros, `inputFinished`, decode while ready, take `result()`, trim it at the cut point when `retainMs > 0` (§4.3), hand `(seq, frozenText)` to the composer, log `stream-timing:`, **release the stream and `createStream()`** (never `reset` — `reset` cannot drop encoder state through this AAR and would decode the pad into the next utterance, research §3.3), re-feed the ring's last `retainMs` of PCM to the fresh stream.
7. **Resolution.** `local` resolves seq N on its own executor → the tee's relay forwards `onSegmentResolved(N, outcome)` to the service **first** (delivery timing byte-identical: Main hop → orderer → `deliverReleasedText` `:3553` → the window) and then emits `composer.resolve(N)` as a delta. For a local-preview session `deliverReleasedText` neither clears the strip nor paints the label (§4.2); the delta that follows shrinks the strip to what the window still lacks.
8. **Stop.** `stopRecording` (`:3152`) writes the FINALIZING line onto the strip, commits STOP through the funnel (freeze + fresh stream), `awaitIdle` drains `local` (`:3255`), every resolution drops its frozen prefix, and the resulting blank delta is turned away by the existing FINALIZING guard (`:3049`). `close()` releases the preview's stream (not the recognizer) and then `local.close()`.
9. **Switch** (`switchSource`, `:2302-2325`): the SWITCH commit freezes and re-creates the stream; `endpointer.reset()` follows as today. A fresh stream is what makes a mic↔device switch acoustically clean with no cut-kind plumbing.
10. **`onTrimMemory`** (`:3398-3407`): `streamingPreview?.release()` beside `localEngine?.releaseContext()` under the same three-state guard; **`onDestroy`** (`:1140`): release and null the field beside `localEngine?.shutdown()`. The next eligible session re-warms (0.8 s + canary).

### 4.2 The strip contract — who owns it, and what it shows

**Ownership (P0, behaviour-neutral prep on `main`).** Today `deltaOwnsPreviewStrip(sessionIsLive) = sessionIsLive` (`:324`) with three readers — `resolvedTextClearsStrip`'s body (`:396`), `onDelta`'s first statement (`:3039`) and the render's early return (`:3529`) — pinned by `InFlightStripTest` (values) and `InFlightStripWiringPinTest` (the exact one-argument call string). The change:

```kotlin
internal fun deltaOwnsPreviewStrip(sessionIsLive: Boolean, sessionHasLocalPreview: Boolean): Boolean =
    sessionIsLive || sessionHasLocalPreview

/** R2: while a local preview paints the words, the in-flight label is DISPLACED. */
internal fun inFlightStripLabel(depth: Int, sessionHasLocalPreview: Boolean): String? = when {
    sessionHasLocalPreview -> null
    depth <= 0 -> null
    depth == 1 -> "Transcribing…"
    else -> "Transcribing… ($depth in queue)"
}

/** A blank delta parks a local preview's strip INVISIBLE (the anti-churn rule); CLOUD_LIVE keeps today's GONE. */
internal fun deltaBlankVisibility(sessionHasLocalPreview: Boolean, currentlyHidden: Boolean): StripVisibility =
    if (sessionHasLocalPreview) inFlightStripVisibility(label = null, currentlyHidden = currentlyHidden)
    else StripVisibility.HIDDEN

/** Only a SERVER-driven live resolution clears the strip; a local preview recomposes its own. */
internal fun resolvedTextClearsStrip(sessionIsLive: Boolean, sessionHasLocalPreview: Boolean, isFinalizing: Boolean): Boolean =
    !isFinalizing && deltaOwnsPreviewStrip(sessionIsLive, sessionHasLocalPreview) && !sessionHasLocalPreview
```

`sessionHasLocalPreview` is a second `@Volatile` session flag beside `sessionIsLive` (`:498`), reset at `:2672`, set only at the wrap site. `sessionIsLive` keeps its three existing jobs untouched. The render (`:3527-3545`) keeps its early return, now on the two-input rule, so the in-flight line is never painted under a local preview; `onDelta`'s blank branch (`:3071`, today `View.GONE`) becomes the `deltaBlankVisibility` `when`, so a local preview's strip goes INVISIBLE-not-GONE once revealed — without this, every utterance's blank-then-words would pay `reclampNow()` (`:3053-3057`), the per-utterance geometry churn the 3.7 G anti-churn rule (`:359-380`) exists to remove. Until a producer sets the flag, every row of every rule answers exactly as today: **P0 is behaviour-neutral, and the pin tests are re-specced to say so.**

**What the strip shows.** The composer emits **`<frozen text of every committed-but-unresolved seq, oldest first> + " " + <current partial>`** — "what the window does not have yet":

| Event | Strip text after it | Visibility |
|---|---|---|
| **partial** — the open stream's cumulative text since the last commit, lowercased, trimmed; replace-only; strictly prefix-growing under greedy (a retraction is COUNTED in `stream-timing:`, never corrected — the strip shows whatever the model now says) | `frozen… + partial` | VISIBLE (the first non-blank of the session pays the one reveal + reclamp) |
| **freeze** — at a commit for seq N: the stream is padded 500 ms and drained; its final text becomes `frozen[N]`; the partial resets to "" | `frozen[…N]` | VISIBLE |
| **final** — `local` resolves seq N (`Text`, `EmptyExpected` or `Lost` alike): `frozen[≤ N]` is dropped; whisper's cased, punctuated text lands in the window via the unchanged path | `frozen[> N] + partial` | VISIBLE, or **INVISIBLE (parked)** when nothing remains — never GONE mid-session |
| **FINALIZING** | the status line ("Finishing transcript…") | the existing guard turns every delta away |
| **retained tail** (cap cut with a micro-pause, `retainMs > 0`, ≤ `CAP_CUT_MAX_RETAIN_MS = 3_000`, `CommitCadencePolicy.kt:183`) | `frozen[N]` is TRIMMED at the cut point using the result's per-token `timestamps` (40 ms resolution): tokens whose timestamp ≥ `(segmentAudioMs − retainMs) / 1000` are dropped; the tail's words re-appear from the fresh stream, not from the frozen text | as above |

Strip and window therefore diverge on purpose — lowercase words above, cased sentences below — and every word is visible somewhere at every moment: on the strip until whisper has it, in the window after.

**Under the governor's merge branch** (`audio/SileroEndpointer.kt`, the 6 s `multi` floor, `CommitCadencePolicy.kt:134`) no commit fires, the stream is not touched, and the partial simply spans the merged pauses — consistent by construction. **The 30 s buffer backstop** inside `LocalWhisperEngine.sendAudio` (`:198-208`, `MAX_BUFFER_BYTES` `:79`) commits without the tee's knowledge, but it is unreachable in the bubble: `MAX_SEGMENT_WALL_MS = 15_000` (`service/SegmentCapPolicy.kt:57`) cuts first.

### 4.3 The commit hook — pad, drain, freeze, release + createStream

Three primary-source facts the rule rests on (research §3.3): `IsReady ⇔ processed + ChunkSize() < ready`; `ChunkSize = T = 45` frames and `ChunkShift = decode_chunk_len = 32` are read from the shipped encoder's metadata (`rung3 §7`); `OnlineStream::Reset()` keeps unprocessed frames as the NEXT segment's head. So at the app's 350 ms hangover (`audio/EndpointerTuning.kt:87`) the chunk holding the last syllable may not have decoded, and a bare `reset` would decode it into the next utterance's partial. Rule: feed **500 ms of zeros** (8,000 samples — the measured floor; `VE` is lost at 450), `inputFinished`, drain, take the result, release the stream, `createStream()`. Cost per commit: ~2 decodes ≈ 40–90 ms on the preview executor, off every other thread.

**Dedupe and the throttle.** `result().text` is read after every burst and 9 of 10 bursts return the same text; only a CHANGED normalized text is a partial. `DeltaThrottle` (150 ms) sits between a changed partial and `onDelta`; at the 320 ms cadence it never bites. Inside the commit-time drain several decodes complete within milliseconds and the throttle thins them — harmless, because the freeze emits unconditionally through the composer.

---

## 5. Language gating

```kotlin
internal fun localPreviewArms(
    sessionLanguage: String?,      // sessionLanguageFor(installedScope, selection, LOCAL) — null = auto
    packInstalled: Boolean,        // StreamingPackManager.isInstalled(StreamingPackCatalog.EN)
    isCloudSession: Boolean,       // cloudWrapper != null — batch and live cloud sessions keep today's strip
    batchJobActive: Boolean,       // BatchJobController.active != null (service/BatchTranscriptionService.kt:53-59)
    userEnabled: Boolean,          // PreferencesManager.localPreviewEnabled (R3: default true)
    previewReady: Boolean,         // streamingPreview != null && isWarm() && !disabled (the canary, §7.2)
): Boolean =
    sessionLanguage == "en" && packInstalled && !isCloudSession && !batchJobActive && userEnabled && previewReady
```

The truth table, each row pinned (`LocalPreviewGateTest`):

| Tier | Selection | `sessionLanguage` | Arms? | Why |
|---|---|---|---|---|
| `pro` (ENGLISH scope) | Auto | `"en"` (`:208`) | **yes** | R4: `pro` already resolves Auto to English |
| `pro` | `"en"` | `"en"` | yes | |
| `pro` | `"es"` | `"en"` (the scope override) | yes | whisper types English anyway; the preview matches the typed language |
| `multi` / `npu` / `npu-turbo` | Auto | `null` | **no** — byte-identical to 4.3.4 | R4: English partials over Spanish speech would be garbage |
| `multi` / turbo | `"en"` | `"en"` | yes | |
| `multi` / turbo | `"es"` | `"es"` | no — the language-step sentence explains | no es pack exists (research §2.5) |
| any | any | any | no | cloud session (batch or live), a batch file job running, the switch off, the pack absent, the canary failed or the preview not yet warm |

Nothing in `LanguagePin`, `detectsPerUtterance` or the language whisper receives changes: `engine.connect(lang, …)` carries exactly the `lang` it carries today.

---

## 6. Pack delivery

**Source — four raw files at an immutable commit**, the catalog's own rule (`model/WhisperModel.kt:103-108`: "resolve/main is a MUTABLE ref … A commit sha can never change out from under us"). The release tarball (310,414,022 B, fp32 + int8 + wavs) is never used.

Base URL: `https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26/resolve/672fbf1b30579d6585301139bb363f42a0ad4a24/`

| File | Bytes | sha256 |
|---|---|---|
| `encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx` | 71,083,163 | `563fde436d16cf7607cf408cd6b30909819d03162652ef389c2450ced3f45ac1` |
| `decoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx` | 1,307,236 | `98da299f471e38bb4e1a8df579b8cc9122d6039576a77e357b3c60f17dd83b02` |
| `joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx` | 259,335 | `d944208d660d67c8d72cd2acaeac971fa5ceb8c80e76c1968148846fedd6e297` |
| `tokens.txt` | 5,048 | `49e3c2646595fd907228b3c6787069658f67b17377c60aeb8619c4551b2316fb` |
| **total** | **72,654,782** | all four re-hashed on the PC and on the Tab (`rung1 §1.2`, `rung3 §1.2`, §7) — the encoder's hash, which the research doc carried as "oid only", is now VERIFIED |

**Layout.** `filesDir/zf-stream/en-2023-06-26/{the four files}` + `.installed`. The marker is written LAST and holds the four `sha256␠␠name` lines (the `TtsModelManager` marker precedent, `tts/TtsModelManager.kt:42-45`, `:151-152`). `isInstalled` = marker present AND every file present at its EXACT byte count (a length read, never a hash, on the session-start path).

**Download.** `StreamingPackManager.download(pack, onProgress)`: free-space gate first (external ≥ 1.1 × total, internal ≥ 1.1 × total — the `TtsModelManager.kt:66-76` shape); stale `DownloadManager` rows for any of the four URLs removed; four sequential `DownloadManager` requests into `getExternalFilesDir(DOWNLOADS)/zf-stream/en-2023-06-26/<name>` (the app's `DownloadManager` already follows HF `resolve/<sha>/` redirects for 190 MB whisper files — the transport is proven); progress is cumulative bytes over 72,654,782; then `StreamingPackInstall.verify` and `install`.

**Size gate, then hash.** Every file's length must EQUAL its pinned byte count before it is hashed (a cheap refusal; these are pinned files at an immutable commit, so ±5 % would only admit a wrong file); then sha256 must equal the pin. A mismatch names the file in the exception (a filename is not content) and deletes the staged copies.

**Atomic install.** Verified files move into `zf-stream/en-2023-06-26.tmp`, the marker is written there last, and the temp dir is renamed over any previous install. A kill at any point leaves either the old install intact or nothing — never a half pack.

**Corrupt or missing file at load.** `warm()` finds `isInstalled` false (a file gone or short) → no load, `stream-gate:` says `pack=0`, the Settings row reads "Repair" (re-download). `isInstalled` true but the recognizer refuses the files (construction throws / returns no handle) → `stream-open: load=fail`, `markCorrupt` removes the marker so the next session does not retry a broken pack, `disabled` for the process, the row reads "Repair". In both cases the session is exactly today's — the previewer fails safe by construction.

**Delete.** Removes the install dir, the `.tmp` dir, the external staged copies and any `DownloadManager` rows (`TtsModelManager.kt:168-174`). A running session's resident recognizer is released at the next `onTrimMemory` / `onDestroy`; the next session sees `pack=0`.

**What a non-whisper pack must NOT touch.** `WhisperCatalog.entries`, `pairedArtifact` (`WhisperModel.kt:94` — simultaneously the download refusal `:435`, the batch substitution predicate and half the mel-donor exclusion `:395-402`), `WhisperModelManager.installedModel()` / `installedModelPath()` / `selectedTierId()` (`:82`, `:149`, `:209`), `ModelMigration`, `ModelTierCopy`'s tier cards, `OnboardingModelScreen`'s chooser, `device_targeting_config.xml`, `verifyNpuPacks`. The pack is a sibling of the TTS voice, keyed by language, with no tier identity.

**Surfaces.** A Settings row beside the read-aloud voice row (`ui/screens/SettingsScreen.kt:502-525` is the download precedent; `:886` the delete): "Download" / "Installed (73 MB) · Delete" / "Repair"; the R3 switch "Show live words" under it, visible only when installed. One sentence on the onboarding LANGUAGE step under `LANGUAGE_HINT` (`ui/screens/OnboardingFlowScreen.kt:593`); a "Live words" chip on the English row when the pack is installed. Every string lives in `StreamingPackCopy` (§9).

---

## 7. Error handling, threading, RAM, battery

### 7.1 Failure table

| Failure | Detected where | Effect on the session | Effect on the typed text |
|---|---|---|---|
| Pack absent / file short | `isInstalled` at warm | no previewer; `stream-gate: pack=0` | none |
| Recognizer load throws / no handle | `warm()` on the preview executor | `disabled` for the process; `markCorrupt`; `stream-open: load=fail` | none |
| Canary FAILS (empty text = #3845; garbage = #3791; runaway) | `PreviewCanary` at warm | recognizer released; `disabled` for the process; **one** line `stream-open: … canary=fail outLen=<n>`; nothing persisted — a service restart retries once | none |
| Canary asset unreadable (`CanaryAudio.samples() == null`, `transcription/CanaryAudio.kt:35-52`) | at warm | `disabled`; `canary=none` (no verdict — and unlike the GPU canary nothing permanent is at stake, so "off for this process" is the safe reading) | none |
| `decode()` throws mid-session | `drain()` | the exception is caught per burst, counted, the stream released and re-created, the partial reset; three consecutive throws in one SESSION `disable` the previewer for the process and blank the strip. Session, not segment: only a decode that RETURNED clears the count (at the measured cadence 9 of 10 bursts decode nothing, so a per-burst or per-segment clear makes `MAX_CONSECUTIVE_FAILURES` dead code and a model throwing once per segment never disables), and `open()` clears it, so three throws spread over three sessions do NOT switch the previewer off | none |
| Queue overflow (executor starved) | `sendAudio` | the chunk is dropped, `shed=1` on the segment's line, logged once per segment; the partial may miss words until the next freeze; whisper still has every byte | none |
| A retraction (non-prefix partial) | `drain()` | counted (`retract=n`), painted as-is | none |
| `onTrimMemory ≥ RUNNING_LOW` between sessions | `:3398` | recognizer released; re-warmed at the next eligible session | none |
| Batch job running | the gate | no previewer this session (`batch=1`) | none |
| A read-aloud requested during CONNECTING | `AudioArbiter.isCapturing` (`:760-762`) today excludes CONNECTING, so Kokoro at 4 threads could start beside the session (research §3.9) | the registration gains `CONNECTING`; `requestSpeak` refuses from the first frame of the session | none |

Every row's last column is the same word, and that is the design: **a previewer fails safe where a committer would type nothing.**

### 7.2 The load-time canary — the only SME guard (R1)

At every recognizer load: `CanaryAudio.samples()` (the bundled clip, the same reader and the same `null = no verdict` rule the GPU canary uses) is fed to a throwaway stream in 512-sample chunks, padded `PAD_MS`, drained, and the result's text goes to `GpuCanaryPolicy.canaryPasses` — which already answers false for empty text (the #3845 signature: "empty text for the whole stream", encoder output sum −14.069 on ORT 1.27.0 + FEAT_SME), for fewer than 4 of the 5 digit positions (the #3791 `"MY WOMAN"` class), and for a token runaway; and true for the measured `ONE TWO THREE FOUR FIVE` (its normaliser lowercases). The verdict is process-scoped: `disabled = true` until the service restarts; no preference, no per-(versionCode, pack) latch. Rationale: the tee is additive, so a false negative costs one process of blank strips and nothing typed; a persisted latch is the GPU canary's shape because there the wrong verdict banned a working GPU for a whole app version, which has no analogue here.

### 7.3 Threading

| Thread | Does | Never does |
|---|---|---|
| capture (URGENT_AUDIO, `util/CaptureThreadPolicy.kt`) | `tee.sendAudio`: two appends (local's buffer under its lock; the preview's `offer` + ring write under the preview's lock) | decode; block; allocate beyond the chunk copy |
| preview executor (single thread, `THREAD_PRIORITY_AUDIO` — below capture, above default) | load, canary, `drain`, `commit`, `close`, `release` — every native call on the recognizer, FIFO by construction | touch `NativeComputeGate` (the gate is a whole-call whisper lock, `transcription/NativeComputeGate.kt:33-38`; wrapping the loop in it would stop it being streaming — one sentence goes into the gate's KDoc so the next reader does not "fix" it) |
| local executor | exactly today's | |
| Main | render | |

`numThreads = 2` is ORT's intra-op pool inside the recognizer; the app adds one Java thread. The Tab has 4×X4 + 4×A720; whisper `multi` runs 4 threads in bursts; TTS asks for 4 and is excluded from the session by the arbiter (row above).

### 7.4 RAM

Resident while loaded: **+170 MB** (measured +160 after load, +169 at the end of ten minutes, `rung3 §6`), beside whisper's context. Released on `onTrimMemory(RUNNING_LOW)` outside a session and on `onDestroy`; re-warmed lazily. The kill line was +400 MB; the measurement clears it 2.4×. No idle-unload timer in v1 (whisper's context follows the same policy).

### 7.5 Battery

At real-time pace the streamer costs **RTF 0.116 of one core** (~12 %) while fed, 0.099 beside a busy load (the cores stay clocked up), against `multi`'s bursts (F ≈ 2.3 s per commit on the Fold6, RTF ≈ 0.34 on the Tab). ORT spinning stays default-on (turning it off measured SLOWER, 0.076 vs 0.054). Ten minutes alone moved the Tab's battery +1.2 °C with zero thermal-status steps, on the charger; **the combined streamer + whisper ten-minute run was not executed** and is Z3's job on the device sheet. The feed runs for the whole session (not only under Silero) in v1: a feed-under-VAD lever exists if Z3 asks for it.

---

## 8. Diagnostics — three lines, no content

Debug and release alike (the lines are app diag under `WE-DIAG`; numbers, codes and stage names only, the `SegmentTiming.kt:1-45` discipline). Pure `StreamDiag`, format pinned by `StreamDiagTest`:

```
stream-open: sherpa=1.13.7 ort=1.27.1 threads=2 provider=cpu loadMs=811 canary=pass|fail|none|skipped canaryMs=212 outLen=23 load=ok|fail
stream-timing: seq=N audio=<ms> decodes=<n> decodeMs=<n> p50us=<n> p99us=<n> rtf=<x.xxx> partials=<n> firstPartialMs=<ms> padMs=500 shed=0|1 retract=<n>
stream-gate: lang=en|auto|<code> pack=1|0 cloud=0|1 batch=0|1 enabled=1|0 ready=1|0 -> preview=1|0
```

`outLen` is a length, never the text (the GPU canary's own rule, `TranscriptionEngine.kt:370-374`). The funnel's `endpoint:` / `queue:` / `perceived:` lines (`service/EndpointDiag.kt`) are untouched; `queue:` keeps the depth the strip no longer paints (R2).

---

## 9. Copy

All strings in `StreamingPackCopy`, pinned verbatim and scanned for the banned words (`faster`, `fastest`, `quicker`, `quickest`, `instant`, `real-time`, `blazing`, `lightning`, `speed` — the union of `HowToGuideTest:13`, `InFlightStripTest`, `ModelTierCopyTest:149`, `CloudProvidersScreenLogicTest:215`):

| Key | Text |
|---|---|
| `SETTINGS_TITLE` | Live words while you speak (English) |
| `SETTINGS_INSTALL` | Download a 73 MB English preview model. Words appear on the bubble as you talk; the typed transcript is still the speech model's. |
| `SETTINGS_INSTALLED` | Installed (73 MB). Words appear on the bubble as you speak English; the typed transcript is unchanged. |
| `SETTINGS_REPAIR` | The preview model is damaged. Download it again to restore live words. |
| `SETTINGS_DISABLED_ON_DEVICE` | Live words are off on this device: the preview model did not pass its start-up check. Your transcripts are unaffected. |
| `SWITCH_TITLE` | Show live words |
| `LANGUAGE_STEP_SENTENCE` | Live words on the bubble are English-only for now; other languages show a progress line while each sentence is transcribed. |
| `LANGUAGE_CHIP` | Live words on the bubble while you speak — preview model installed. (rendered in the English row's existing subtitle slot on the language step; no new visual element) |
| `DELETE_TITLE` | Delete the preview model |

"73 MB" follows the house decimal convention (`ModelTierCopy.kt:25` says "190 MB" for 190,085,487 B). "Live" is the app's own word for the surface (CLOUD_LIVE); no sentence promises a latency.

---

## 10. What must NOT change

- **The mic latch** (`DeviceAudioLatchPinTest`): the tee is built from the same `resolveTranscriptionEngine` result and the capture callback is untouched but for nothing — `engine.sendAudio(chunk)` at `:2061` is the same call on a different object.
- **Final-only delivery** (`:3592-3610`, `:3613`): the previewer never writes to the sink, the history or the field; `onDelta` is preview-only by contract (`TranscriptionEngine.kt:119-124`).
- **The whisper commit path**: `commitSegment` (`:3464-3481`) is byte-identical; `LocalWhisperEngine` is not edited; every seq `local` returns still reaches `onSegmentResolved` exactly once, in order, through the relay; `EmptyExpected` under the 192 ms evidence floor (`EndpointerTuning.kt:197`) still skips the encode.
- **The NPU tier**: `NpuWhisperBackend`, `NpuBackendSelector`, the decode guards, the cadence rows — untouched; the tee wraps whatever `warmLocalEngine` built.
- **The three cloud live providers and cloud batch**: `cloudWrapper != null` sessions never arm the previewer; `sessionIsLive`'s three jobs, `LiveTurnPolicy`, the Gemini client-VAD path — untouched. `onDelta`'s live branch keeps its `View.GONE` blank.
- **The endpointer**: Silero, the hangover, the flatline cut, the caps, the governor — untouched; sherpa's endpointer stays `enableEndpoint = false`.
- **`NativeComputeGate`**: never held by the previewer.
- **`-keep class com.k2fsa.sherpa.onnx.** { *; }`** (`app/proguard-rules.pro:76`): never narrowed — the landing adds one sentence to its comment.
- **Batch**: `BatchTranscriber` and its whisper-only wiring — untouched; the gate refuses the tee while a job runs.

---

## 11. Release and sequencing

- **P0** (the strip prep, §4.2) lands as its own behaviour-neutral commit on `main` first (research §5.5 item 10: it must not ride an engine branch).
- Everything else lands on `feat/4.4.0-streaming-previewer` off that `main`.
- **Identity 4.4.0 / 89** (R5): the controller's commit, not the plan's.
- **R6:** the streaming week precedes any other engine work.
- The internal-track AAB has 88's pack shape: no asset pack, no `device_targeting_config.xml` change, no `verifyNpuPacks` impact.

---

## 12. Testing

JVM only (no instrumented tests exist; no Robolectric). Pure rules are pinned by value; the engine and the tee by fakes over the seams; the service by source pins on live lines (`InFlightStripWiringPinTest`'s idiom: LF-normalised, symbol-scoped, never a line number). **No test may import or reference `com.k2fsa`** — `TtsEngineSeamTest` sets the precedent ("no sherpa"). Recorded partial sequences from the measurements are the engine's fixtures: the canary's four partials (`ONE` → `ONE TWO THREE` → `… FOUR` → `… FIVE`, the last from the pad, `rung3 §5.3`) and `jfk`'s 22 (`rung1 §3.2`), plus the token/timestamp shape with the leading space (`" F", "OUR"`). `FloatingBubbleService.kt` and `LocalWhisperEngine.kt` are already in `sourcePinnedInputs` (`app/build.gradle.kts:312-313`, `:388-396`).

---

## 13. Acceptance — the owner's device session (Z1–Z11)

Internal-track build on the Fold6 (`pro` / `multi` / `npu-turbo`) and the Tab (`multi`). Logcat `-s WE-DIAG` captured for the whole session. The sheet itself is the plan's last task; the rows:

| # | Row | Expect | Fail if |
|---|---|---|---|
| Z1 | Fixed English (or Auto on `pro`), pack installed: dictate five sentences | lowercase words appear on the strip within ~0.5 s of each word (first word ~1.2–1.5 s after onset); `stream-gate: … -> preview=1`; one `stream-timing:` per commit with `retract=0`, `shed=0`, `padMs=500` | no words; any `retract>0` on clean speech; `shed=1` |
| Z2 | Same five sentences, read the typed result | byte-identical to what 4.3.4 types for the same audio (cased, punctuated, numerals) | any difference in the typed text |
| Z3 | A five-minute read (Tab, `multi`) | the strip never falls more than one tick behind (no growing backlog); `rtf` on every line < 0.25; `shed=0`; the Tab's battery `dumpsys` moves < 3 °C; `thermalStatus` 0 | a visible lag that grows; `shed=1`; a status step |
| Z4 | Auto on `multi` / turbo | byte-identical to 4.3.4: the "Transcribing…" line, no words; `stream-gate: lang=auto … -> preview=0` | words appear |
| Z5 | Fixed Spanish on `multi` | no live words; the language step's sentence reads as the reason; `preview=0` | English words over Spanish |
| Z6 | A device-audio (YouTube) session, edited video | words stream; the flatline cut still fires (`endpoint: … cut=flat`); the typed text is whisper's | a lost cut; a duplicated sentence in the window |
| Z7 | Source switch mid-utterance (mic ↔ device audio) | no duplicated and no lost words across the switch; `endpoint: … cut=switch` followed by a fresh stream (`stream-timing:` for that seq) | a word typed twice or dropped |
| Z8 | Read-aloud requested during CONNECTING, then during RECORDING | refused both times (`requestSpeak` false); no Kokoro thread beside the session; no stutter on the strip | audio overlap |
| Z9 | Start a batch file job, then dictate | `stream-gate: … batch=1 -> preview=0`; the session is 4.3.4's; the batch job completes | contention or a crash |
| Z10 | Stop mid-sentence | the tail lands in the typed text; the strip shows "Finishing transcript…" then clears; nothing remains parked after the final | a lost tail; a stranded strip |
| Z11 | The canary line on BOTH devices at service start | `stream-open: sherpa=1.13.7 ort=1.27.1 threads=2 provider=cpu … canary=pass outLen=<n> load=ok` | `canary=fail` / `none` / `load=fail` on either device |

Promote when Z1–Z11 pass; R1's release-notes sentence ships with the promotion.

---

## 14. Out of scope

Shape C (the transducer as committer); a second language pack; the 7.1 MB punctuation model (licence unreadable); `OnlinePunctuation`; hotwords; NNAPI (not a path in this AAR); ORT spinning off (slower); chunk batching (64 ms feeds); a feed-under-Silero lever (kept in reserve for Z3); the finalizer/journal; an idle-unload timer; the S23/XR.

---

## Self-review

- **Placeholders.** None: every constant carries a value and a provenance; every file path exists on `main` at `5efc2be` or is named as new under `transcription/stream/`; every copy string is written out. The only unknowns are the six rulings, and each is marked with its flip.
- **Contradictions.** (a) The research doc's `padMs=450` vs the measured 500 — resolved for 500 everywhere, including the diag line. (b) The research's `provider ∈ {cpu, cpu:<cfg>, nnapi}` probe row vs the measurement — `cpu` only; the adapter has no provider arm. (c) The research's "measure threads 1/2/4" — measured; 2 is the constant. (d) The research's "on `onTrimMemory` releases the preview" against a session-scoped engine — resolved: the recognizer is resident and released on trim/destroy, streams are per-segment. (e) "disable for the session" (the task) vs "for this build" (research §3.9) for a failed canary — resolved: for the PROCESS, nothing persisted, §7.2 states why. (f) The research's B0 / T1 prerequisites — already on `main` (88), so the sequencing table collapses to P0 → the feature branch.
- **Ambiguity.** "Final" on the strip has one meaning (§4.2: the local engine's resolution drops the frozen prefix); "freeze" one (the padded drain at a commit); "partial" one (the open stream's cumulative text). The composer's join is a single space; the trim's cut point is `(segmentAudioMs − retainMs) / 1000` in seconds against sherpa's per-token timestamps; `isInstalled` is a length read, `verify` a hash.
- **Scope.** Nothing here edits `LocalWhisperEngine`, the NPU backends, the cloud engines, the endpointer, the catalog or the delivery path (§10); the two Compose surfaces are additive rows; P0 is separated so the strip change cannot ride the engine branch. The arbiter's CONNECTING row is the one change outside the tee, carried from research §3.9 because a second CPU consumer makes the Kokoro overlap worse, and it is one line plus a pin.
- **Measurements folded in.** Threads, pad, the leading-space join, the p95 and the throttle interplay, the RSS budget, the canary text, `T=45 / decode_chunk_len=32`, `provider=nnapi` is a no-op, greedy-not-beam, zero retractions, ALL-CAPS → lowercase, the gated-audio weakness as Z6 — each appears in §2 with its provenance and again where it is used.
