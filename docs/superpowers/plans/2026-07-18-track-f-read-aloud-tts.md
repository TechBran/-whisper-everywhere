# Track F — Read-Aloud TTS Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (adapted: controller implements inline, read-only reviewer subagents gate) to implement this
> plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Highlight text anywhere on the phone → bubble morphs into a speaker (or use the
system-toolbar "Speak" entry) → tap → Kokoro-82M speaks it aloud, fully on-device, first words
within ~1–2.5 s.

**Architecture:** sherpa-onnx (vendored AAR) runs Kokoro-82M fp32 on CPU. A `TtsEngine`
wrapper owns OfflineTts + AudioTrack streaming + audio focus. A single `AudioArbiter` enforces
{IDLE, CAPTURING, SPEAKING} exclusivity with FloatingBubbleService. Triggers: (1)
ACTION_PROCESS_TEXT trampoline (universal), (2) bubble morph on a11y selection events
(opportunistic). Model files download once via a `TtsModelManager` mirroring the hardened
whisper download path.

**Tech Stack:** sherpa-onnx 1.13.4 AAR, Kokoro multi-lang v1_1 (fp32 primary; int8 measured
then likely rejected), Apache commons-compress (tar.bz2 extraction), AudioTrack, existing
WhisperAccessibilityService events.

## Global Constraints

- CPU only; `provider = "cpu"`; NO GPU/NNAPI/QNN work (research verdict 2026-07-18).
- fp32 is the default model UNLESS the Phase-0 benchmark shows int8 RTF ≤ fp32 RTF (published
  data says it won't).
- TTS and STT NEVER run simultaneously — all starts route through `AudioArbiter`.
- Speaking stops instantly on: bubble tap while speaking, new recording start, audio-focus
  loss, selection cleared.
- No text spoken is ever logged or stored (same privacy bar as transcripts).
- Commit messages end with the session trailer (Co-Authored-By + Claude-Session).
- Play posture unchanged: isAccessibilityTool="false"; disclosure text update ships WITH the
  a11y-triggered layer.
- Connected tests always pass `-Pandroid.injected.androidTest.leaveApksInstalledAfterTest=true`
  and reinstall the release APK afterward.

---

### Task 0: Feasibility spike — on-device RTF benchmark (GATE)

**Files:**
- Create: `app/libs/sherpa-onnx-1.13.4.aar` (vendored, gitignored if >100MB — it is ~49MB, commit it)
- Modify: `app/build.gradle.kts` (add `implementation(files("libs/sherpa-onnx-1.13.4.aar"))`)
- Create: `app/src/androidTest/java/com/whispereverywhere/tts/KokoroBenchTest.kt`

**Interfaces:**
- Produces: measured fp32 + int8 RTF and first-sentence latency on the Fold 6; speaker-id →
  voice-name mapping; GO/NO-GO for fp32.

- [ ] **Step 1:** Download the AAR from
  `https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.4/sherpa-onnx-1.13.4.aar`
  into `app/libs/`; add the gradle dependency; verify the app still assembles (abiFilters
  already restrict to arm64-v8a).
- [ ] **Step 2:** Download + extract `kokoro-multi-lang-v1_1.tar.bz2` (fp32) and
  `kokoro-int8-multi-lang-v1_1.tar.bz2` from the sherpa-onnx `tts-models` release tag on the
  dev machine; `adb push` the extracted directories to `/data/local/tmp/`.
- [ ] **Step 3:** Write `KokoroBenchTest`: instrumentation test taking `-e ttsModelDir`;
  builds `OfflineTts` (model.onnx|model.int8.onnx, voices.bin, tokens.txt,
  dataDir=espeak-ng-data, lexicon=lexicon-us-en.txt, numThreads=4, provider="cpu"); calls
  `generate()` on a fixed ~12 s paragraph; asserts non-empty samples; logs
  (loadMs, synthMs, audioSec, RTF=synthMs/1000/audioSec, firstSentenceMs via
  generateWithCallback timing of the first callback). Also logs `tts.numSpeakers()`.
- [ ] **Step 4:** Run for fp32 and int8; record numbers in this plan under "Bench results".
- [ ] **Step 5:** GATE: fp32 RTF < 0.7 → proceed, model=fp32. 0.7–1.0 → proceed but flag
  long-selection stutter risk. > 1.0 → STOP, escalate (Piper tier becomes primary).
- [ ] **Step 6:** Commit (`feat(tts): vendored sherpa-onnx + Kokoro bench`).

### Task 1: TtsModelManager — download/install the voice model

**Files:**
- Create: `app/src/main/java/com/whispereverywhere/tts/TtsModelManager.kt`
- Modify: `app/build.gradle.kts` (add `implementation("org.apache.commons:commons-compress:1.27.1")`)
- Test: `app/src/test/java/com/whispereverywhere/tts/TtsModelManagerTest.kt`

**Interfaces:**
- Produces: `fun isInstalled(): Boolean`, `fun installedDir(): File?`,
  `suspend fun download(onProgress: (Long, Long) -> Unit, onExtracting: () -> Unit)`,
  `fun delete()`.
- Consumes: DownloadManager pattern from `WhisperModelManager` (mirror its resume/verify/
  free-space hardening; single tar.bz2 asset, sha256-pinned, extracted to
  `filesDir/tts/kokoro-v1_1/`, extraction is atomic via temp-dir rename).

- [ ] Steps: failing unit tests for install-state/dir-layout/atomic-rename logic (pure-JVM
  parts) → implement → tests pass → commit. Exact URL + sha256 recorded in code from the
  Task-0 download (pin the tts-models release asset).

### Task 2: TtsEngine — synthesis + streaming playback + focus

**Files:**
- Create: `app/src/main/java/com/whispereverywhere/tts/TtsEngine.kt`
- Test: `app/src/androidTest/java/com/whispereverywhere/tts/TtsEngineSmokeTest.kt`

**Interfaces:**
- Produces: `fun preload()`, `fun unload()`, `fun isReady(): Boolean`,
  `fun speak(text: String, onDone: () -> Unit): Boolean`, `fun stop()`,
  `@Volatile var speakerId: Int`, `var speed: Float`.
- Contract: `speak` runs generateWithCallback on a single-thread executor; each sentence
  FloatArray → 16-bit PCM → AudioTrack(MODE_STREAM, 24 kHz mono, CONTENT_TYPE_SPEECH,
  low-latency); first write starts playback. `stop()` sets a cancel flag (callback returns 0),
  AudioTrack.pause()+flush()+release, abandons focus. Audio focus:
  AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK requested before play, abandoned on end/stop; focus LOSS
  → stop(). Idle-unload timer (5 min) frees the ~0.8 GB RSS.

- [ ] Steps: smoke instrumentation test (speak short text on-device, assert playback state +
  completion) → implement → run on Fold 6 → commit.

### Task 3: AudioArbiter — STT/TTS mutual exclusion

**Files:**
- Create: `app/src/main/java/com/whispereverywhere/audio/AudioArbiter.kt`
- Modify: `FloatingBubbleService.kt` (recording start/stop route through arbiter)
- Test: `app/src/test/java/com/whispereverywhere/audio/AudioArbiterTest.kt`

**Interfaces:**
- Produces: `object AudioArbiter { enum State {IDLE, CAPTURING, SPEAKING};
  fun requestCapture(onGranted: () -> Unit)`, `fun requestSpeak(onGranted: () -> Unit)`,
  `fun released(state)` — requestSpeak while CAPTURING stops the recording session first
  (graceful finalize), requestCapture while SPEAKING calls TtsEngine.stop() first.

- [ ] Steps: unit tests for the transition table (failing first) → implement → wire
  startRecording/stopRecording + TtsEngine.speak through it → tests pass → commit.

### Task 4: Trigger layer 1 — ACTION_PROCESS_TEXT "Speak"

**Files:**
- Create: `app/src/main/java/com/whispereverywhere/tts/SpeakTextActivity.kt`
- Modify: `AndroidManifest.xml`

**Manifest block:**
```xml
<activity
    android:name=".tts.SpeakTextActivity"
    android:exported="true"
    android:label="@string/speak_action_label"
    android:theme="@android:style/Theme.NoDisplay"
    android:excludeFromRecents="true"
    android:noHistory="true"
    android:taskAffinity="">
    <intent-filter>
        <action android:name="android.intent.action.PROCESS_TEXT" />
        <category android:name="android.intent.category.DEFAULT" />
        <data android:mimeType="text/plain" />
    </intent-filter>
</activity>
```

**Activity contract (verbatim skeleton):**
```kotlin
class SpeakTextActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
        if (!text.isNullOrBlank()) TtsController.speakFromTrigger(this, text)
        finish() // MUST finish inside onCreate: Theme.NoDisplay throws otherwise (API 23+)
    }
}
```
`TtsController` = small mediator: ensures model installed (else routes to MainActivity's TTS
download card with a toast), routes through AudioArbiter, calls TtsEngine.

- [ ] Steps: implement → on-device test in Chrome + a text field (toolbar shows "Speak",
  audio plays, selection toolbar unaffected) → commit.

### Task 5: Trigger layer 2 — bubble morphs to speaker on selection

**Files:**
- Modify: `WhisperAccessibilityService.kt` (selection watcher), `FloatingBubbleService.kt`
  (speaker bubble state + tap routing)

**Contract:**
- On TYPE_VIEW_TEXT_SELECTION_CHANGED with start<end and source text resolvable: debounce
  400 ms, cache `selectedText` (substring, bounds-checked, cap 5 000 chars), notify the bubble
  → bubble shows speaker icon state (new BubbleState.SPEAK_READY visual on the existing blob).
- Selection cleared (start==end / window change / click elsewhere) → revert to mic state.
- Tap while SPEAK_READY → AudioArbiter.requestSpeak → TtsEngine.speak(selectedText);
  while SPEAKING the bubble shows a stop glyph; tap → stop → revert.
- Never morph while a recording session is active (RECORDING/FINALIZING) — selection during
  capture is already handled by Track-session rules.
- `TtsEngine.preload()` fires on the morph (hides model load inside think-time).

- [ ] Steps: implement watcher + morph + tap routing → on-device test (select in a note app
  → bubble morphs → tap → speech; Chrome static text → no morph, toolbar path still works) →
  reviewer subagent on the diff → fix findings → commit.

### Task 6: Settings + disclosure + docs

**Files:**
- Modify: `SettingsScreen.kt` (voice picker from tts.numSpeakers() names map, speed slider,
  "Read aloud" section with model download/delete), `HomeScreen.kt` disclosure dialog text
  (add read-aloud sentence), `docs/PLAY-DECLARATIONS.md` (a11y declaration + new capability),
  `docs/PLAN.md` (Track F status).

- [ ] Steps: implement → build → on-device sanity → commit.

### Task 7: Final review + release

- [ ] Whole-track read-only review subagent on the full diff; fix Critical/Important.
- [ ] Full gate: unit suites + lint + assembleRelease; bump version (3.0.0 — the generational
  marker: STT + TTS); install; on-device pass of both triggers + exclusivity (speak during
  capture attempt, capture during speech attempt).
- [ ] Commit, push; PLAN.md Track F → SHIPPED.

## Bench results (Fold 6 / SD 8 Gen 3, 4 threads, 2026-07-18 — GATE: PASS, fp32 selected)

| Variant | Load (ms) | Synth (ms) | Audio (s) | RTF | First-sentence (ms) |
|---------|-----------|------------|-----------|-----|---------------------|
| fp32    | 2045      | 8106       | 14.05     | **0.577** | **1882**       |
| int8    | 2169      | 11940      | 13.92     | 0.857 | 2815               |

fp32 < 0.7 gate → GO. int8 confirmed ~1.5x slower on-device (research prediction held) —
rejected. 103 speakers, 24 kHz. ALSO LEARNED (cost one native crash): Kotlin 2.0's default
invokedynamic lambdas lack the specialized `invoke([F)Ljava/lang/Integer` bridge sherpa's JNI
reflectively calls — every sherpa callback MUST be an explicit `object : Function1<FloatArray,
Int>`. TtsEngine encapsulates this so no caller can regress it.
