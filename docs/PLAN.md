# Whisper Everywhere — Living Plan

> Maintained in place by the AI pair (Claude). Statuses move as work ships; design details are
> edited here first, then implemented. Last updated: 2026-07-17.

## Roadmap at a glance

| # | Track | Status |
|---|-------|--------|
| A | **Device-audio capture** (media transcription without the mic) + transcript history | **SHIPPED 2026-07-17** (user-validated on device: clean YouTube/voice separation) |
| B | Runs-on-every-phone (dynamic GGML backends; non-OpenCL devices) | **SHIPPED 2026-07-17** (2.8.1; DT_NEEDED chain verified clean; SONAME module loading; user-confirmed) |
| C | Production leftovers (injection binding, Play declarations, full log strip, toolchain, CI) | **SHIPPED 2026-07-17** |
| D | GPU polish (multilingual model on OpenCL retest; kernel-cache the first-load pause) | **SHIPPED 2026-07-17** (multilingual→CPU gate; prewarm kills the first-load pause) |
| E | Housekeeping (fork whisper.cpp under TechBran for a cloneable submodule; optional upstream PR of the ggml-opencl lazy-init fix) | **DONE 2026-07-17** (fork live; upstream PR awaits user go-ahead) |
| F | Read-aloud TTS (Kokoro-82M on-device via sherpa-onnx; highlight → speaker bubble) | researched 2026-07-18 — awaiting go/spec |

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

**User decisions (2026-07-18, from the complete test):**
3. **A session's routing is frozen at the tap** (`sessionContext` + injection session): the
   whole transcript goes where the session was aimed when it started; mid-session clicks can
   never split it across destinations (2.9.2).
4. **Media capture and field dictation are separate sessions**: focusing a text field while
   the PLAYBACK source records auto-FINISHES the media session (one body → clipboard +
   history); dictation into the field starts fresh on the next bubble tap (2.9.3).

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

## Track C — Production leftovers (SHIPPED 2026-07-17)

- Injection binding: recording start now captures the focused node as the session target
  (`beginInjectionSession`); every segment injects THERE while the node is alive, with
  strategy classification (document/social clipboard paths) following the target's app.
  Dead node → whole session cleared (node + package) → pre-session fallback behavior.
- Play declarations: `docs/PLAY-DECLARATIONS.md` (a11y `isAccessibilityTool="false"` +
  existing prominent-disclosure dialog verified compliant; FGS mic/mediaProjection texts;
  data-safety: nothing collected; notification-listener justification).
- Notification-listener review: KEEP — `MediaSessionManager.getActiveSessions()` legally
  requires an enabled listener component; ours reads no notification content.
- Full release log strip: R8 `-assumenosideeffects` now covers v/d/i/w/e (diagnosis moves
  to debug builds; native `__android_log_print` unaffected).
- Edge-to-edge: `enableEdgeToEdge()` in MainActivity (screens already Scaffold-inset).
- Toolchain: AGP 8.2.0→8.7.3, Kotlin 1.9.20→2.0.21 (+ compose-compiler plugin replaces
  composeOptions), Compose BOM 2024.10.01, navigation 2.8.4, accompanist 0.36.0,
  kotlinx-serialization 1.7.3.
- CI: `.github/workflows/ci.yml` — Kotlin compile + unit tests per push/PR (native assemble
  deferred until the Track E fork makes the submodule cloneable); build-dir relocation made
  dev-machine-conditional; gradlew exec bit set.

## Track D — GPU polish (SHIPPED 2026-07-17)

**Multilingual retest (instrumented A/B on the Fold 6, smoke test + `-e useGpu`):** the #3708
risk is REAL but silent — no assert fires. `ggml-small-q5_1` (vocab 51865) decodes to garbage
tokens on GPU; `ggml-large-v3-turbo-q5_0` (vocab 51866) returns empty; CPU controls transcribe
the same jfk.wav correctly. Multilingual tiny passes on GPU (dims under the Adreno-kernel
threshold) but no catalog tier is that small. **Fix: GpuPolicy allows GPU only for `.en`
models** (vocab 51864, %4==0) — a static gate, because crash sentinels cannot catch silent
corruption. Both multilingual tiers now take the dotprod/i8mm CPU path.

**First-load pause:** v1.9.1's ggml-opencl has no kernel-binary cache (would be heavy vendored
surgery), so instead `LocalWhisperEngine.prewarm()` loads the context at bubble-service start
(+1.5 s, on the engine's own executor) — the first recording no longer pays the model load +
~7-16 s kernel compile inside CONNECTING. Revisit real binary caching only if upstream grows it.

Also: debug builds sign with the release key when `keystore.properties` exists, so dev-machine
debug/test APKs install over the release app without wiping the downloaded model.

## Track F — Read-aloud TTS (RESEARCHED 2026-07-18; awaiting go/spec)

**Vision (user):** highlight text anywhere → the bubble morphs into a speaker → tap → the text
is spoken aloud, fast, fully on-device. TTS and STT are mutually exclusive activities.

**Engine — the same one as the home box:** Speaches on the server runs
`speaches-ai/Kokoro-82M-v1.0-ONNX`; the on-device path for that exact model is **sherpa-onnx**
(k2-fsa, Apache-2.0, v1.13.4): first-class Kotlin `OfflineTts` API, per-sentence callback
streaming into AudioTrack (return 0 = instant cancel), prebuilt AAR (~11-13 MB APK delta,
arm64-only), production Android apps exist. No Maven coords — vendor the AAR.

**Compute — CPU, deliberately (research verdict, 3-lens sweep wf_cc384795):**
- Kokoro-82M is non-autoregressive (StyleTTS2-derived) and already beats real time on phone
  CPUs: est. RTF 0.6-0.9 on the 8 Gen 3 (NimbleEdge measured ~0.8 on "recent smartphones").
- Every Android GPU/NPU path is a dead end for this graph today: NNAPI deprecated at our
  targetSdk 35; QNN-HTP needs static shapes + has no LSTM; QNN-GPU is a Windows preview;
  ORT WebGPU/Vulkan experimental — and Vulkan-on-Adreno is already proven dead here. GPU would
  buy ~1-2 s at a cost of weeks. XNNPACK EP flag = one free afternoon A/B.
- **fp32, NOT int8**: int8 Kokoro is ~2x SLOWER than fp32 on ARM (iPhone 15 + Galaxy S10 +
  sherpa bench all agree) — the quantization intuition is inverted. fp32 ≈ 330-430 MB download
  (prunable; community fp16 169 MB worth testing); ~0.8 GB RAM while speaking → preload when
  the bubble arms, idle-unload, never co-resident with a whisper session (already exclusive).
- Latency budget: preload hides the 1-3 s model load; sentence-chunked synthesis → first
  sentence plays (~1-2.5 s after tap) while the rest synthesizes.

**Trigger UX — three layers (coverage is the hard part):**
1. `ACTION_PROCESS_TEXT` "Speak" entry in the system selection toolbar — universal, zero
   permissions, works in Chrome/WebView/Gmail/WhatsApp-select-text; `Theme.NoDisplay`
   trampoline must finish() inside onCreate().
2. Bubble-morph via a11y `TYPE_VIEW_TEXT_SELECTION_CHANGED` (we already subscribe): debounce
   ~400 ms, read textSelectionStart/End. Opportunistic ONLY — Chromium never exposes static
   web-text selection, old-Compose and PDF viewers emit nothing (Google's own Select to Speak
   gave up on selection detection). When events don't arrive, the toolbar entry still works.
3. Optional later: tap-bubble-then-tap-text mode for the dead zone.

**Audio:** one AudioTrack (MODE_STREAM, 24 kHz mono, CONTENT_TYPE_SPEECH, low-latency),
AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK (music ducks, podcasts pause), pause()+flush() for instant
stop, abandon focus at end. One arbiter owns {IDLE, CAPTURING, SPEAKING} — speaker tap stops
capture first, mic tap stops speech first.

**Landmine:** current sherpa-onnx statically links espeak-ng (**GPLv3**) into the JNI .so —
fine sideloaded, NOT fine for a closed-source Play release. sherpa-onnx 2.0.0 removes it
(announced 2026-07-08, no date; lexicon-based G2P replaces it — lexicons already ship for
Kokoro). Options: wait for 2.0.0 / source-build lexicon-only today / open-source the app.
Decide before store distribution, not before prototyping.

**Play posture:** update the a11y declaration + in-app disclosure to name read-aloud
("reads text you select aloud, entirely on-device"); Jan 28 2026 a11y policy bans autonomous
actions — user-tapped read-aloud is passive, allowed; keep isAccessibilityTool=false.

**Next step:** brainstorm → spec → plan cycle; week-1 task is the on-device RTF benchmark
(fp32 vs fp16 vs int8 on the Fold 6) before any UX latency promises.

## Track E — Housekeeping (DONE 2026-07-17)

Fork live: **TechBran/whisper.cpp**, branch `we/v1.9.1-android` (v1.9.1 + ggml-opencl
lazy-init fix) pushed; `.gitmodules` points at the fork (+ branch), so the repo is cloneable
end-to-end. CI's first run is green. Remaining (user decisions): upstream PR of the lazy-init
fix (public act — needs go-ahead); moving the repo out of OneDrive.
