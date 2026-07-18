# Whisper Everywhere — Living Plan

> Maintained in place by the AI pair (Claude). Statuses move as work ships; design details are
> edited here first, then implemented. Last updated: 2026-07-17.

## Roadmap at a glance

| # | Track | Status |
|---|-------|--------|
| A | **Device-audio capture** (media transcription without the mic) + transcript history | **SHIPPED 2026-07-17** (user-validated on device: clean YouTube/voice separation) |
| B | Runs-on-every-phone (dynamic GGML backends; non-OpenCL devices) | **SHIPPED 2026-07-17** (2.8.1; DT_NEEDED chain verified clean; SONAME module loading; user-confirmed) |
| C | Production leftovers (injection binding, Play declarations, full log strip, toolchain, CI) | queued |
| D | GPU polish (multilingual model on OpenCL retest; kernel-cache the first-load pause) | queued |
| E | Housekeeping (fork whisper.cpp under TechBran for a cloneable submodule; optional upstream PR of the ggml-opencl lazy-init fix) | queued |

## Shipped (2026-07-17, branch `feature/on-device-whisper`)

- GPU inference: whisper.cpp v1.9.1 + Qualcomm OpenCL backend on Adreno (crash-sentinel gated,
  per-model validation, EGL allowlist). Vulkan closed on Adreno (proven on-device).
- Native Silero VAD in JNI (silence costs ~nothing), sub-1.1s padding, decode tunings,
  audio_ctx floor 768 (short-phrase accuracy), 32ms audio frames.
- Security: keystore relocated + rotated, creds externalized; downloads main-safe with
  verify state + free-space + resume; Android 15-safe boot path; crash symbols.
- Bubble: always-on mode (default, free placement) vs auto pop-up; aurora recording pill
  (design ref `docs/design/bubble2-ribbon-reference.png`): deep black pill, 4 band-driven
  aurora sheets (red→navy gradient), Goertzel 4-band AGC, Xbox-orb traveling-lobe blob rim
  with spatial band mapping, live rec timer.

---

## Track A — Device-audio capture (ACTIVE)

**Goal:** When transcribing media (YouTube, podcasts, any playback), capture the DEVICE's audio
stream directly instead of the open microphone: perfect signal, zero room noise, zero background
speech, zero mic/speaker feedback. Mic remains the source for normal dictation.

**User decisions (2026-07-17):**
1. During playback capture the microphone is **fully cut** — media sessions transcribe the
   stream only. This mode is *just for transcription* of the media.
2. **Graceful mid-session switch is required**: a natural flow is mic-button FIRST, then
   pressing play on the video. Recording must hand over from mic → stream seamlessly.

**Mechanism (research: workflow wf_8025d094):**
- `AudioPlaybackCapture` (Android 10+): `AudioRecord` built from
  `AudioPlaybackCaptureConfiguration.Builder(mediaProjection).addMatchingUsage(USAGE_MEDIA)`.
- Requires one-time-per-grant **MediaProjection consent** (system dialog via Activity result)
  and manifest FGS type `mediaProjection` alongside `microphone`.
- Capturable: most apps incl. YouTube; DRM apps (Netflix etc.) opt out → stream arrives silent.
- Request 16 kHz mono PCM16 directly from the capture AudioRecord; verify on-device — if the
  mixer refuses, capture 44.1/48 kHz and decimate to 16 kHz in a small resampler.

**Source state machine:**
```
DICTATION (mic)  ──media starts (MediaSessionDetector)──▶  MEDIA_CAPTURE (stream, mic OFF)
     ▲                                                            │
     └──────────────media stops / user taps stop──────────────────┘
```
- Tap mic while media already playing → go straight to MEDIA_CAPTURE (consent if not yet held).
- Media starts mid-recording → commit the current mic segment, stop the mic, start stream
  capture, subtle toast "Capturing device audio". VAD/engine pipeline identical downstream.
- Media stops mid-capture → commit stream segment, return to mic seamlessly.
- Consent lifecycle: projection requested lazily on first need via a transparent trampoline
  activity (notification-safe); token kept for the service's lifetime; re-request on restart.
- Fallbacks: Android 8/9 → mic path unchanged. Silent capture (DRM opt-out) detected after
  ~3 s of zero energy → toast + fall back to mic.

**Task checklist (implementation complete 2026-07-17, commits aaba31d..11f8d71; subagent-driven
with per-task reviews, all Important findings fixed; final whole-branch review in flight):**
- [x] Manifest: `FOREGROUND_SERVICE_MEDIA_PROJECTION` permission + service type; consent
      trampoline activity (transparent, exported=false)
- [x] `MediaProjectionGate`: holds the projection token; lazy consent request + result plumbing
- [x] `PlaybackAudioCapturer`: AudioRecord w/ playback-capture config, 16 kHz path + fallback
      decimator, same `(chunk, amp)` callback contract as `StreamingAudioRecorder`
- [x] `FloatingBubbleService`: source state machine (mic↔stream) wired to
      `MediaSessionDetector` events mid-session; segment commit at every source switch
- [x] Visuals: aurora/blob driven identically from stream audio (AudioBands is source-agnostic)
- [x] DRM/silent-capture detection + graceful mic fallback (3s watchdog)
- [x] On-device validation: USER CONFIRMED 2026-07-17 — "works well, clearly and cleanly
      separates the YouTube video from my audio"; consent modal + grant flow as designed
- [x] Settings: "Capture device audio for media" toggle (default ON)
- [x] BONUS (user request mid-plan): transcription history — `TranscriptStore` (text-only,
      14-day/10MB rolling, oldest-first eviction, 5/5 unit tests), session capture funnel,
      home-screen Transcriptions card + list/detail screen (copy/share/delete)

## Track B — Runs-on-every-phone (SHIPPED 2026-07-17, commit b6bc689)

Done: `GGML_BACKEND_DL=ON`; backends are dlopen modules loaded by BARE SONAME through the
app's linker namespace (`ggml_backend_load("libggml-cpu.so")` etc.). CPU always loads;
OpenCL is best-effort — absent vendor `libOpenCL.so` (Tensor Pixels, Mali Samsungs) is
skipped instead of killing `System.loadLibrary`. Verified: `llvm-readelf` shows zero OpenCL
references on the hard chain (whisper_jni→whisper→ggml→base); GPU still engages on the Fold 6.
LESSON (cost one brief crash loop): with `extractNativeLibs=false` the nativeLibraryDir holds
no real files — `ggml_backend_load_all_from_path()` scans find NOTHING; always load by SONAME
on Android. Remaining (final-test item): behavior on an actual non-OpenCL device is simulated
only (dlopen-failure path); a Pixel/Mali test device would fully close it.

## Track C — Production leftovers (queued)

Text-injection binds to the field captured at record start (not whatever is focused at
delivery); Play Console declarations (accessibility `isAccessibilityTool` + disclosure,
FGS-mic video, data safety); full release Log strip (extend proguard v/d → i/w/e);
edge-to-edge targetSdk 35; AGP/Kotlin/Compose toolchain bump; CI (build + unit tests);
notification-listener necessity review.

## Track D — GPU polish (queued)

Multilingual model (odd 51865 vocab) on Adreno OpenCL — upstream #3708 M%4 assert risk:
test, then keep GPU or gate that model to CPU. Kernel-binary caching to cut the ~7 s
first-load compile. Optional: whisper-bench numbers per model tier for the README.

## Track E — Housekeeping (queued)

Fork ggerganov/whisper.cpp → TechBran/whisper.cpp, push `we/v1.9.1-android` (makes the
public branch cloneable; currently the submodule pointer references a local-only commit).
Optional upstream PR: ggml-opencl lazy-init fix (needs user go-ahead; public act).
OneDrive: consider moving the repo out of the synced folder entirely.
