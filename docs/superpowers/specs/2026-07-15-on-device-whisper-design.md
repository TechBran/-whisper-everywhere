# On-Device Whisper — Design Spec

**Date:** 2026-07-15
**Status:** Draft (design), pending review → implementation plan
**Supersedes:** the "cloud-only / on-device is a non-goal" decision in
`specs/2026-06-06-whisper-everywhere-v2-design.md`. Streaming STT, the reactive waveform, and the
floating bubble from that release are **kept**; only the *transcription backend* changes.

## Summary

Move Whisper Everywhere from cloud transcription (OpenAI realtime WebSocket) to a **100% on-device**
whisper.cpp engine. The existing streaming UX is preserved unchanged — mic → `StreamingAudioRecorder`
→ `SpeechSegmenter` (client VAD) → per-utterance commit → transcript → accessibility injection. Only
the component behind the commit changes: instead of flushing a segment to a WebSocket, we run
`whisper_full()` locally on that segment. We also add **on-demand model download onboarding**
(English tiers + a multilingual option) and a **floating-UI overhaul** (pin/lock + drift hardening).

Result: $0 marginal cost, works offline/airplane-mode, no audio leaves the device, no API key needed
for transcription.

## Goals

- Replace the cloud STT backend with a drop-in **on-device** engine, leaving the recorder, VAD,
  waveform, bubble state machine, and text-injection code unchanged.
- Preserve the "streaming feels live" UX: transcripts appear per utterance as the user pauses.
- Ship tiered, on-demand model downloads (small APK; models fetched at onboarding).
- English tiers (default) **and** a multilingual option.
- Add an overlay **pin/lock** toggle and harden bubble position against drift.
- Support **indefinite-length** sessions — transcribe a whole book, movie, or hours-long video — at
  bounded memory (finish each segment, clear as you go).

## Non-goals

- Real "token-by-token" intra-utterance partials (`onDelta`). On-device whisper transcribes a whole
  committed segment as one fast batch → we emit `onCompleted` per segment, no intra-segment deltas.
  (Injection already fires on `onCompleted`, so behavior in text fields is identical.)
- Keeping the cloud path as a fallback (explicitly removed — fully sovereign, per decision).
- On-device **TTS** (separate future effort; the `2026-06-06-tts-read-aloud.md` plan is untouched).
- Play Asset Delivery. Models are downloaded at runtime to `filesDir`, keeping the APK < 150 MB.

## Decisions (locked)

1. **Fully on-device** — remove the cloud STT path.
2. **English tiers + a multilingual option** — English default; multilingual selectable.
3. **Scope includes the floating-UI overhaul** (pin/lock + drift hardening).

---

## Architecture

### The swap point

Today `FloatingBubbleService.startRecording()` news up a `RealtimeTranscriptionClient(apiKey)` and
drives it via `connect / sendAudio / commit / close` + a `Listener`. We generalize that contract into
a backend-neutral interface and provide an on-device implementation:

```kotlin
interface TranscriptionEngine {
    fun connect(language: String?, listener: Listener)   // prepare/load; call onOpen when ready
    fun sendAudio(pcm: ByteArray)                         // buffer PCM16 mono (see capture rate)
    fun commit()                                          // transcribe buffered segment now
    fun close()                                           // release the session

    interface Listener {
        fun onOpen()
        fun onDelta(text: String)        // unused on-device (kept for interface compatibility)
        fun onCompleted(text: String)    // final transcript for one committed segment
        fun onError(message: String)
        fun onClosed()
    }
}

class LocalWhisperEngine(private val app: Context) : TranscriptionEngine { ... }
```

`FloatingBubbleService` references `TranscriptionEngine` and instantiates `LocalWhisperEngine`.
**No changes** to `StreamingAudioRecorder`, `SpeechSegmenter`, `BarWaveformView`,
`WhisperAccessibilityService`, `MediaSessionDetector`, `BootReceiver`, or the bubble state machine.

### Capture at 16 kHz (removes all resampling)

The recorder captures 24 kHz today only because OpenAI's realtime API wanted it. whisper's native
rate is **16 kHz**, so we set `StreamingAudioRecorder.SAMPLE_RATE = 16000`. PCM16@16k → float32 feeds
`whisper_full()` directly — no resampler. Segmenter thresholds (amplitude RMS) and timings (ms) are
sample-rate-independent, so they're unaffected. (16 kHz is a universally supported `AudioRecord` rate.)

### Native layer (whisper.cpp via NDK/JNI)

Based on the official `examples/whisper.android` (Kotlin) approach:

- Vendor whisper.cpp + ggml under `app/src/main/cpp/` with a `CMakeLists.txt`.
- JNI `whisper_jni.cpp` exposing exactly three functions. The Kotlin `WhisperNative` object names them
  `init` / `transcribe` / `free`; the exported C symbols are `Java_..._WhisperNative_init` etc.:
  - `init(modelPath: String): Long`  → returns a `whisper_context*` handle (0 on failure)
  - `transcribe(ctxPtr: Long, samples: FloatArray, lang: String?, translate: Boolean): String`
  - `free(ctxPtr: Long)`
- Kotlin `WhisperNative` object: `System.loadLibrary("whisper_jni")` + `external` decls.
- `app/build.gradle.kts`: `externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } }`,
  `ndk { abiFilters += listOf("arm64-v8a") }` (optionally `armeabi-v7a`), pin `ndkVersion` (r26+).
- Build flags: `-O3`, C++17, NEON on; leave GPU/OpenCL off for v1 (CPU is the fast batch path).

Handles are held in `LocalWhisperEngine`; the loaded context is cached in a process-level singleton so
subsequent recording sessions are instant (load only on first use / after a model change).

### `LocalWhisperEngine` behavior

- `connect(language, listener)`: resolve installed model path via `WhisperModelManager`. If none →
  `onError("No speech model installed")`. Else load the native context on a background thread (cached),
  then `onOpen()`.
- `sendAudio(pcm)`: append bytes to a thread-safe buffer. (Called rapidly from the recorder thread.)
- `commit()`: atomically snapshot+clear the buffer; on a **single-thread executor** (segments
  serialize, mirroring the old one-at-a-time model): PCM16→float32, `nativeTranscribe(...)`, then
  `onCompleted(text.trim())`. Errors → `onError`.
- `close()`: cancel pending work, keep the cached context (freed on model change / `onTrimMemory`).
- Language: `"auto"` or null → let whisper auto-detect (multilingual models) or force `en`
  (English models). `translate=false`.

### Long-form / indefinite transcription

Sessions must run **unbounded** (a whole book, movie, or multi-hour video) without growing memory:

- **Audio stays bounded.** `commit()` snapshots and **clears** the PCM buffer every segment, and the
  VAD's `maxSegmentMs` (15 s) force-commits during continuous speech — so buffered audio never exceeds
  one segment (~0.5 MB), regardless of session length.
- **The model is reused, not accumulated.** The whisper context is loaded **once** and each segment is
  an independent `whisper_full()` batch — no cross-segment KV/prompt growth. Per-segment float buffers
  are released after each call.
- **Transcript is streamed to a sink, not held in RAM.** In text-field mode each segment is injected
  immediately (nothing accumulates). In dictation/preview/media mode, finalized segments are appended
  to a **session file** in `filesDir`, keeping only a **capped in-memory tail** (~4 000 chars) for the
  on-screen preview. The full transcript is the file (copy/share/export at stop) — bounded memory
  whether the session is 10 seconds or 10 hours.
- **A failed segment is skipped, never fatal.** After retries, a bad segment is logged and dropped and
  recording continues — one glitch can't kill a two-hour transcription.
- **Longevity.** The foreground (microphone) service + wake lock keep a long session alive; the
  notification shows elapsed time. (Hours of CPU inference has a battery/thermal cost — noted.)

### Model manager + download

`WhisperModelManager` (no UI):
- **Catalog** of tiers, each: `id`, `displayName`, `fileName`, `url`, `approxBytes`, `sha256`,
  `scope` (english | multilingual). Hosted on the Hugging Face CDN:
  `https://huggingface.co/ggerganov/whisper.cpp/resolve/main/<fileName>`.
- Download via Android **`DownloadManager`** → `context.filesDir/models/<fileName>` (private,
  sandboxed). Expose progress (poll `DownloadManager.Query` → bytes/total). Verify **sha256** on
  completion; delete + fail on mismatch.
- `isInstalled(tier)`, `installedModelPath()`, `download(tier, onProgress)`, `delete(tier)`.
- Selected tier + resolved path persisted in prefs.

**Tier catalog (q5-quantized; exact sizes/sha pinned at implementation):**

| Tier | Model file | Scope | ~Download | Recommended device |
|---|---|---|---|---|
| **Eco** | `ggml-base.en-q5_1.bin` | English | ~57 MB | any |
| **Pro** (default) | `ggml-small.en-q5_1.bin` | English | ~190 MB | mid-range+ |
| **Extreme** | `ggml-medium.en-q5_0.bin` | English | ~539 MB | 6 GB+ RAM |
| **Multilingual** | `ggml-small-q5_1.bin` | 99 langs | ~190 MB | mid-range+ |
| **Ultra** | `ggml-large-v3-turbo-q5_0.bin` | 99 langs | ~574 MB | **high-end, 8 GB+ RAM** |

Quantized (not the F16 sizes in the original pasted spec) — smaller download, negligible accuracy loss.

**Ultra tier (large-v3-turbo):** the same model family running on the desktop box; needs ~1.5–2 GB RAM
at inference. **Gate it behind a device-RAM check** (`ActivityManager.MemoryInfo.totalMem`): surface it
prominently only on ≥ 8 GB devices (e.g. Z Fold6 @ 12 GB) with a clear "high-end devices only" note,
but let the user pick it anyway if they choose. q5_0 keeps the download ~574 MB at near-full quality.

### Onboarding

First-run gate: if no model is installed, show a **model wizard** — welcome → pick tier (Eco / Pro /
Extreme, with a "Other languages" toggle that swaps in the multilingual model) → download with a
progress bar → ready. Integrate with the existing `onboarding_completed` flag and add a **"Speech
model"** row to the HomeScreen `SetupChecklist`. Settings gets a "Speech model" section to
switch/redownload tiers and see disk usage.

### Floating-UI overhaul (drift + pin)

- **Pin/lock:** new `KEY_OVERLAY_PINNED` bool pref + a small pin icon in the expanded panel (and/or
  long-press the bubble to toggle). When pinned, `handleTouch` blocks drag math — only taps register
  (recording still works). Persist; show a pin indicator.
- **Drift hardening:** position already persists (`bubble_x/bubble_y`, normalized). Add:
  `onConfigurationChanged` → re-clamp x/y to current screen bounds (rotation/fold), re-assert
  `LayoutParams` after keyboard show/hide, and guard against off-screen coordinates on restore.

### Permissions / manifest

- `INTERNET` + `ACCESS_NETWORK_STATE` **stay** (model download only; not used during transcription).
- No new runtime permissions. Transcription now works fully offline once a model is installed.
- API key UI: STT no longer uses it. Keep it dormant for a future TTS feature, or hide it — TBD in the
  plan (leaning: hide from the primary flow, keep the storage).

### What gets removed

- `data/api/RealtimeTranscriptionClient.kt`, `data/api/RealtimeEvents.kt`,
  `test/.../RealtimeEventsTest.kt` (cloud realtime protocol).
- OkHttp remains only if needed elsewhere; otherwise drop the dependency.
- The `Listener` contract moves onto `TranscriptionEngine`.

---

## Testing strategy

- **Unit (JVM):** `WhisperModelManager` catalog/URL/sha logic (no network); `AudioMath` PCM16→float;
  `LocalWhisperEngine` buffering/commit/threading against a **fake `WhisperNative`**; existing
  `SpeechSegmenter`/`AudioMath` tests keep passing after the 16 kHz change.
- **Instrumented / manual:** on-device smoke test — download Pro, record, verify per-utterance
  injection into a text field; airplane-mode transcription; pin toggle blocks drag; rotation keeps
  position; low-storage + sha-mismatch error paths.
- Build gates: `./gradlew.bat testDebugUnitTest` and `./gradlew.bat assembleDebug`.

## Risks / edge cases

- **Device performance:** medium tier is heavy on low-end devices → recommend Pro by default; surface
  a note. **Memory:** medium inference can use ~1–2 GB RAM → guard with `onTrimMemory`, free context
  under pressure. **First-load latency:** model load ~1–3 s → reuse the CONNECTING state, cache the
  context. **Download failures / low disk:** resumable via `DownloadManager`, sha-verify, clear errors.
- **APK/store:** models on-demand keep the APK < 150 MB (no PAD). Bump to **v2.0.0** (versionCode 11);
  update the Play listing/privacy to "on-device, offline."
- **Licensing:** whisper.cpp + ggml models are MIT — fine for a proprietary app with attribution
  (add an OSS-licenses/attribution entry).

## Resolved decisions (review 2026-07-15)

1. **API key:** hide from the primary flow, keep the encrypted storage dormant (for a future TTS).
2. **Launch models:** ship the full catalog at once (Eco / Pro / Extreme / Multilingual / **Ultra**).
3. **Default tier:** **Pro (`small.en`)**; Eco offered for low-end, Ultra for high-end (RAM-gated).
4. **Add the Ultra tier** (`large-v3-turbo`), RAM-gated to high-end devices — user-selectable.

---

## Resilience & automatic retry (production requirement)

Failures must self-heal without the user re-speaking. A shared, bounded backoff policy
(`RetryPolicy`: max attempts, base delay, exponential + jitter, cap) is applied at every fallible
step; the user sees an error **only after retries are exhausted**, and every attempt is logged.

- **STT transcription:** if `nativeTranscribe` throws, times out, or a segment that contained speech
  returns empty, re-run it on the executor up to **2 retries** before surfacing `onError`. The audio
  segment is retained until it either succeeds or exhausts retries — never dropped silently.
- **Model load:** if `nativeInit` fails, retry once (transient FS/mmap), then a clear actionable error
  ("model may be corrupt — re-download") that offers a one-tap re-download.
- **Model download:** `DownloadManager` handles pause/resume/network-drop; on failure, auto-retry with
  backoff; on **sha256 mismatch**, delete + re-download once before failing.
- **Text injection:** already multi-strategy (SET_TEXT → paste → gesture); add a node-not-ready retry
  (`refresh()` + re-attempt) before falling back to clipboard. Never lose a transcript — clipboard is
  the guaranteed floor.
- **General text generation** (covers the future TTS synth path): same `RetryPolicy` wrapper, so any
  generation/synthesis step auto-retries before erroring.

`RetryPolicy` is a pure, unit-tested helper (deterministic with an injected clock/RNG).

## Production readiness (Play Store)

This ships to Play Store review — the plan treats these as gating requirements, not polish:

- **16 KB page alignment (Android 15+):** native `.so`s **must** be 16 KB-aligned or Play rejects the
  upload. Build whisper.cpp/ggml with the NDK's 16 KB support (`-Wl,-z,max-page-size=16384`, NDK r27+);
  add an assemble-time check.
- **R8/ProGuard keep rules:** keep JNI (`-keepclasseswithmembernames class * { native <methods>; }`),
  the `WhisperNative` class + method signatures, the accessibility service, and any
  reflection/serialization-touched model classes. Verify a **release** build transcribes (not just debug).
- **Crash-free / never kill the overlay:** wrap all native calls in guarded try/catch incl. `OutOfMemoryError`;
  a transcription failure must degrade gracefully, never crash `FloatingBubbleService`.
- **No ANRs:** all model load + inference off the main thread; the UI thread only touches views.
- **Memory:** `onTrimMemory`/`onLowMemory` frees the cached context; Ultra/Extreme guarded by the RAM check.
- **Play policies:** the **Accessibility Service** use requires a prominent in-app disclosure + a Play
  Console declaration matching the actual purpose (text injection) — include the disclosure screen.
  Update the **Data safety** form + **privacy policy** to "on-device, no audio or transcripts leave the
  device" (a strong differentiator now that it's true).
- **Versioning:** bump to **v2.0.0 / versionCode 11** (done in **Plan 4** release hardening, not Plan 1); release signing already configured.
- **Licensing:** bundle whisper.cpp/ggml (MIT) attribution in an in-app OSS-licenses screen.
- **Testing gates:** `testDebugUnitTest` green; a release `assembleRelease` that runs on a physical
  device (incl. the Z Fold6) transcribing offline; retry paths exercised.
