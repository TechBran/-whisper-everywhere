# Speaker Labels Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Workflow agents run on Opus (owner ruling 2026-09-18); the controller (Fable) adjudicates.

**Goal:** Committed text is split into paragraphs at speaker changes, labelled `Speaker N:` in the transcript panel once a second voice is confirmed, with labels in copied/saved text only behind a setting.

**Architecture:** The native layer exports, per chunk, the VAD segment bounds (original and trimmed timelines) and whisper's per-segment text ranges. The local engine attaches those as `spans` to the existing `SegmentOutcome.Text`. On the service's resolution path a `SpeakerEmbedder` (sherpa-onnx CAM++, own single thread) fingerprints each VAD segment, a pure `SpeakerTracker` assigns speaker ids, and a run-aware `TranscriptSink` renders the panel, the delivered file and history from `(speakerId, text)` runs per the spec's table. The live preview path (`PreviewTeeEngine`) is untouched.

**Tech Stack:** Kotlin, whisper.cpp JNI (C++17), sherpa-onnx 1.13.7 (`SpeakerEmbeddingExtractor`), JUnit4 unit tests + the repo's source-pin tests.

**Spec:** `docs/superpowers/specs/2026-09-18-speaker-labels-design.md` (approved 2026-09-18; bundling ruled).

## Global Constraints

- NEVER run `:app:installDebug` or `:app:connectedDebugAndroidTest`; install only with `adb install -r`.
- Never read, print, log or commit `C:/Users/bastr/.androidbuild/gemini.key`. No transcript content in any log line — diag lines carry numbers only.
- The live preview strip and cloud sessions are untouched: no file under `transcription/stream/` changes except a KDoc; no file under `transcription/cloud/` changes.
- The commit floors (`CommitCadencePolicy`) and the whisper thread's work are untouched: embedding runs on its own executor and never blocks `onSegmentResolved`'s delivery of text.
- The panel shows no label until a second speaker is CONFIRMED (a segment ≥ `MIN_NEW_SPEAKER_SECONDS`); one speaker all session = today's output byte for byte.
- Text typed into a field: paragraph breaks at speaker changes, never labels. Clipboard and saved transcripts: labels only when `speakerLabelsInExport` is on (default off). `detectSpeakers` defaults on.
- Speaker numbering is per session; a returning speaker keeps its number; at most 8 speakers.
- The model is bundled in `app/src/main/assets/` (owner ruling); no download flow. Licence clearance is a production gate, not a build gate.
- Every source-pinned file touched must already be in `sourcePinnedInputs` in `app/build.gradle.kts` (whisper_jni.cpp :369, SettingsScreen.kt :476, FloatingBubbleService.kt :498, PreferencesManager.kt :532 are); add new pinned files there.
- Commit messages in the repo's style, with the trailers `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>` and `Claude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT`. Do not push.

## Facts the plan rests on (from the 2026-09-18 code mapping; verify before relying)

- `WhisperNative.transcribeRaw(...)`: `ByteArray` of concatenated segment text (`whisper_jni.cpp:961-977`); `lastSegmentStats(): IntArray` is the precedent for a process-global, read-after-call diagnostic export (`whisper_jni.cpp:501-515`, `WhisperNative.kt:230-250`).
- `we_vad_filter` (`whisper_jni.cpp:156-238`) slices `[t0*160, t1*160)` per VAD segment, stitches with `kGapSamples = 1600` zeros, swaps into `pcm`; `whisper_full` runs on the trimmed pcm (:950); short chunks are zero-padded to `kMinSamples = 17600` (:834-837).
- `TranscriptionEngine.Listener.onSegmentResolved(seq, outcome)`; `SegmentOutcome.Text(val text: String)` (`SegmentOutcome.kt:12-20`); the one emission site is `LocalWhisperEngine.resolve` (:594-603); cleaning is `TranscriptText.clean` (:517) which collapses all whitespace.
- Service: `onSegmentResolved` (:4922) → `segmentOrderer.onResolved` (`Release(text, lostSegments)`) → `deliverReleasedText` (:5566) → `handleTranscriptionResult` (:5603): `sessionTranscript.append(...)` + `transcriptSink?.append(text)`. Panel = `TranscriptSink.preview` collected in `showSessionPreview` (:4279-4311). Final delivery = ONE write at stop, `deliverFinalTranscript(full)` (:5626), from the sink's file. History = `transcriptStore.save(sessionStamp, sessionTranscript.toString())` (:5227), plain `<startedAtMs>.txt`.
- `PreviewTeeEngine.Relay.onSegmentResolved` forwards the outcome untouched (:197-200).
- Sherpa adapters pass `assetManager = null` + absolute paths (`SherpaPreviewRecognizer.kt:72`, `TtsEngine.buildTts`); `NpuAssetStage` stages an asset into `filesDir` (`WhisperNative.kt:274-279`). `SpeakerEmbeddingExtractor(AssetManager?, SpeakerEmbeddingExtractorConfig(model, numThreads, debug, provider))`, `createStream()`, `isReady(stream)`, `compute(stream): FloatArray`, `dim()`, `release()` (verified in the shipped 1.13.7 jar).
- Settings pattern: `preferDeviceAudio` (`PreferencesManager.kt:185-193`, `SettingsScreen.kt:732-740`); pins in `PreferencesBubbleAlwaysOnTest` style.
- Model file: `C:/Users/bastr/.androidbuild/WhisperEverywhere/speaker/3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx`, 29,596,978 B, sha256 `357a834f702b80161e5b981182c038e18553c1f2ca752ed6cec2052365d4129b`.

---

### Task 0: Export the segment geometry from native code

**Files:**
- Modify: `app/src/main/cpp/whisper_jni.cpp` (the VAD filter ~156-238, the globals ~149-151, `transcribeRaw` ~775-990, a new export beside `lastSegmentStats` ~501)
- Modify: `app/src/main/java/com/whispereverywhere/whisper/WhisperNative.kt` (two new externs beside `lastSegmentStats`)
- Test: `app/src/test/java/com/whispereverywhere/whisper/SegmentGeometryPinTest.kt` (source pin: the globals are reset at the top of `transcribeRaw`, written before `pcm.swap`, and read only after the call)

**Interfaces:**
- Produces: `WhisperNative.lastVadSegments(): IntArray` — per VAD segment `[origStartSample, origEndSample, trimmedStartSample, trimmedEndSample]`, in chunk order; empty when the VAD did not run. `WhisperNative.lastWhisperSegments(): IntArray` — per whisper segment `[t0cs, t1cs, byteStart, byteEnd]` where `byteStart..byteEnd` index the `ByteArray` `transcribeRaw` just returned; empty when no segments. Both are process-global like `lastSegmentStats` and valid only on the thread that called `transcribeRaw`, until the next call.

- [ ] **Step 1: Write the failing pin test**

```kotlin
package com.whispereverywhere.whisper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Source pin: the segment-geometry exports follow lastSegmentStats' contract exactly. */
class SegmentGeometryPinTest {
    private val cpp = File("src/main/cpp/whisper_jni.cpp").takeIf { it.exists() }
        ?: File("app/src/main/cpp/whisper_jni.cpp")
    private val kt = File("src/main/java/com/whispereverywhere/whisper/WhisperNative.kt").takeIf { it.exists() }
        ?: File("app/src/main/java/com/whispereverywhere/whisper/WhisperNative.kt")
    private val cppSrc by lazy { cpp.readText() }
    private val ktSrc by lazy { kt.readText() }

    @Test fun bothExportsAreDeclaredInKotlinAndCpp() {
        assertTrue(ktSrc.contains("external fun lastVadSegments(): IntArray"))
        assertTrue(ktSrc.contains("external fun lastWhisperSegments(): IntArray"))
        assertTrue(cppSrc.contains("Java_com_whispereverywhere_whisper_WhisperNative_lastVadSegments"))
        assertTrue(cppSrc.contains("Java_com_whispereverywhere_whisper_WhisperNative_lastWhisperSegments"))
    }

    @Test fun theGeometryIsClearedAtTheTopOfTranscribeRawAndWrittenBeforeTheSwap() {
        val top = cppSrc.indexOf("Java_com_whispereverywhere_whisper_WhisperNative_transcribeRaw")
        val clear = cppSrc.indexOf("g_last_vad_segments.clear()", top)
        val swap = cppSrc.indexOf("pcm.swap(filtered);")
        val write = cppSrc.indexOf("g_last_vad_segments.push_back", 0)
        assertTrue("cleared inside transcribeRaw", clear > top)
        assertTrue("written before the swap so trimmed offsets are final", write in 1 until swap)
        assertEquals("one writer of the whisper geometry", 1, Regex("g_last_whisper_segments\\.push_back").findAll(cppSrc).count())
    }
}
```

- [ ] **Step 2: Run it, expect failure** — `.\gradlew.bat :app:testDebugUnitTest --tests "*SegmentGeometryPinTest*" --no-daemon` → FAIL (symbols absent).

- [ ] **Step 3: Native export.** In `whisper_jni.cpp`:

```cpp
// beside g_last_ctx_frames (~:149)
static std::vector<jint> g_last_vad_segments;      // [origS0, origS1, trimS0, trimS1] * n
static std::vector<jint> g_last_whisper_segments;  // [t0cs, t1cs, byteStart, byteEnd] * n
```
In `we_vad_filter`'s stitch loop (~:213-224), before `filtered.insert(... pcm.begin()+s0 ...)`:
```cpp
        const auto trimS0 = static_cast<jint>(filtered.size());
        filtered.insert(filtered.end(), pcm.begin() + s0, pcm.begin() + s1);
        g_last_vad_segments.push_back(static_cast<jint>(s0));
        g_last_vad_segments.push_back(static_cast<jint>(s1));
        g_last_vad_segments.push_back(trimS0);
        g_last_vad_segments.push_back(static_cast<jint>(filtered.size()));
```
At the top of `transcribeRaw` beside the three `store(0)` resets (~:787-789): `g_last_vad_segments.clear(); g_last_whisper_segments.clear();`.
In the result loop (~:961-977), record per segment before appending:
```cpp
        const jint byteStart = static_cast<jint>(result.size());
        result += seg;
        g_last_whisper_segments.push_back(static_cast<jint>(whisper_full_get_segment_t0(ctx, i)));
        g_last_whisper_segments.push_back(static_cast<jint>(whisper_full_get_segment_t1(ctx, i)));
        g_last_whisper_segments.push_back(byteStart);
        g_last_whisper_segments.push_back(static_cast<jint>(result.size()));
```
Two exports beside `lastSegmentStats`:
```cpp
static jintArray we_int_vector(JNIEnv *env, const std::vector<jint> &v) {
    jintArray out = env->NewIntArray(static_cast<jsize>(v.size()));
    if (out != nullptr && !v.empty()) env->SetIntArrayRegion(out, 0, static_cast<jsize>(v.size()), v.data());
    return out;
}
extern "C" JNIEXPORT jintArray JNICALL
Java_com_whispereverywhere_whisper_WhisperNative_lastVadSegments(JNIEnv *env, jobject) { return we_int_vector(env, g_last_vad_segments); }
extern "C" JNIEXPORT jintArray JNICALL
Java_com_whispereverywhere_whisper_WhisperNative_lastWhisperSegments(JNIEnv *env, jobject) { return we_int_vector(env, g_last_whisper_segments); }
```
Kotlin, beside `lastSegmentStats` with a KDoc in its style (process-global, same-thread, diagnostics-grade until Task 3 makes it load-bearing):
```kotlin
    /** Per VAD segment of the LAST transcribeRaw on this thread: [origStart, origEnd, trimmedStart, trimmedEnd] samples. Empty when the VAD did not run. */
    external fun lastVadSegments(): IntArray
    /** Per whisper segment of the LAST transcribeRaw: [t0cs, t1cs, byteStart, byteEnd] into the returned ByteArray. */
    external fun lastWhisperSegments(): IntArray
```

- [ ] **Step 4: Run the pin and the native build** — `.\gradlew.bat :app:testDebugUnitTest --tests "*SegmentGeometryPinTest*" --no-daemon` → PASS; `.\gradlew.bat :app:buildCMakeRelWithDebInfo[arm64-v8a] --no-daemon` → BUILD SUCCESSFUL.

- [ ] **Step 5: Commit** — `git add app/src/main/cpp/whisper_jni.cpp app/src/main/java/com/whispereverywhere/whisper/WhisperNative.kt app/src/test/java/com/whispereverywhere/whisper/SegmentGeometryPinTest.kt && git commit -m "feat(speakers): the native layer exports the segment geometry a speaker label needs"`

---

### Task 1: `SpeakerSpan` on the outcome, built by the local engine

**Files:**
- Modify: `app/src/main/java/com/whispereverywhere/transcription/SegmentOutcome.kt`
- Create: `app/src/main/java/com/whispereverywhere/transcription/speakers/SpeakerSpans.kt` (pure)
- Modify: `app/src/main/java/com/whispereverywhere/transcription/LocalWhisperEngine.kt` (~461-476 the transcribe call; ~517-570 clean + outcome)
- Test: `app/src/test/java/com/whispereverywhere/transcription/speakers/SpeakerSpansTest.kt`

**Interfaces:**
- Produces: `data class VadSeg(val origStart: Int, val origEnd: Int, val trimmedStart: Int, val trimmedEnd: Int)`; `data class SpeakerSpan(val vadIndex: Int, val text: String)`; `SegmentOutcome.Text(val text: String, val spans: List<SpeakerSpan>? = null, val vad: List<VadSeg>? = null)` — `text` unchanged (the cleaned whole), `spans` in text order, `vad` in chunk order; both null when the geometry is unavailable (no VAD, cloud, NPU).
- `object SpeakerSpans { fun vadSegments(raw: IntArray): List<VadSeg>; fun spans(raw: IntArray, bytes: ByteArray, vad: List<VadSeg>): List<SpeakerSpan> }` — a whisper segment maps to the VAD segment its `[t0cs*160, t1cs*160)` overlaps most on the TRIMMED timeline; no overlap (a zero-padded tail) → the last VAD segment; adjacent spans with the same `vadIndex` merge; each span's text is `TranscriptText.clean` of its byte slice.

- [ ] **Step 1: Failing tests** (`SpeakerSpansTest`): (a) two VAD segments, two whisper segments each inside one → two spans; (b) a whisper segment straddling the 100 ms gap → the side with more overlap; (c) a whisper segment entirely in the zero-padded tail → last VAD; (d) three whisper segments in one VAD segment merge into one span whose text is the cleaned concatenation joined by `TextJoin` rules; (e) empty raw arrays → empty lists; (f) byte slices decode UTF-8 4-byte characters intact.
- [ ] **Step 2: Run → FAIL.**
- [ ] **Step 3: Implement** `SpeakerSpans` (pure; UTF-8 decode per slice; overlap in samples: `t0cs*160`); extend `SegmentOutcome.Text` with the two nullable fields (defaults keep every existing constructor call compiling); in `LocalWhisperEngine` right after `backend.transcribeStreaming` returns on the engine thread, read `WhisperNative.lastVadSegments()` / `lastWhisperSegments()` through the backend seam (add `fun lastGeometry(): Pair<IntArray, IntArray>` to the backend interface in `TranscriptionEngine.kt` beside `lastSegmentStats`, with the NPU/cloud backends returning empty arrays), build `vad` and `spans`, and construct `SegmentOutcome.Text(cleaned, spans, vad)`; spans are dropped (null) when `cleaned.isBlank()`.
- [ ] **Step 4: Run** `SpeakerSpansTest` + the engine tests (`LocalWhisperEngine*Test`) → PASS; the whole suite → PASS (every `SegmentOutcome.Text(...)` positional construction still compiles).
- [ ] **Step 5: Commit** — `feat(speakers): the local engine hands the service speaker spans beside the text`.

---

### Task 2: `SpeakerTracker` (pure) and `SpeakerEmbedder` (the sherpa adapter)

**Files:**
- Create: `app/src/main/java/com/whispereverywhere/transcription/speakers/SpeakerTracker.kt`
- Create: `app/src/main/java/com/whispereverywhere/transcription/speakers/SpeakerEmbedder.kt` (the ONE file importing `com.k2fsa.sherpa.onnx.SpeakerEmbedding*`; never referenced from a test)
- Create: `app/src/main/assets/speaker_campplus_en_16k.onnx` (copied from the cached download; sha256 recorded in the commit message and in `SpeakerEmbedder`'s KDoc)
- Test: `app/src/test/java/com/whispereverywhere/transcription/speakers/SpeakerTrackerTest.kt`; `SpeakerEmbedderPinTest.kt` (source pin: the adapter is the only importer; assets path constant; provider cpu; numThreads 1)

**Interfaces:**
- `class SpeakerTracker(val tSame: Float = 0.55f, val tNew: Float = 0.45f, val maxSpeakers: Int = 8, val ema: Float = 0.2f)` with `fun assign(embedding: FloatArray, durationSec: Float): Int` (1-based speaker id; `durationSec < MIN_NEW_SPEAKER_SECONDS` can never open a speaker), `val speakerCount: Int`, `val secondSpeakerConfirmed: Boolean` (true once two speakers each have ≥1 assignment of a segment ≥ `MIN_NEW_SPEAKER_SECONDS`), `fun currentSpeaker(): Int`, `fun reset()`. Constants `MIN_EMBED_SECONDS = 1.0f`, `MIN_NEW_SPEAKER_SECONDS = 1.5f`.
- `class SpeakerEmbedder(app: Application)` with `fun embed(pcm: FloatArray, sampleRate: Int = 16000): FloatArray?` (null on any failure; the model loads lazily on first call, on the caller's thread — the caller is the executor in Task 3), `fun release()`. Loads via `SpeakerEmbeddingExtractor(app.assets, SpeakerEmbeddingExtractorConfig(model = "speaker_campplus_en_16k.onnx", numThreads = 1, debug = false, provider = "cpu"))`; if the AssetManager constructor throws on device (a Task 4 finding), fall back to staging the asset into `filesDir` and passing an absolute path, the `NpuAssetStage` way.

- [ ] **Step 1: Failing `SpeakerTrackerTest`** with synthetic unit vectors: same vector twice → 1,1; orthogonal vectors → 1,2; the return of speaker 1 (v1, v2, v1) → 1,2,1; a 0.5-cosine vector with the band [0.45,0.55) → current speaker, no new id; a short (0.8 s) segment far from everyone → current speaker, `speakerCount` unchanged; the 9th distinct voice → the closest existing id; EMA: after ten assignments of slightly varying v1 the centroid tracks (a later v1' at 0.6 similarity to the ORIGINAL but 0.9 to the drifted centroid → speaker 1); `secondSpeakerConfirmed` false after a 1.2 s second-voice segment, true after a 1.6 s one; `reset()` clears everything.
- [ ] **Step 2: Run → FAIL.**
- [ ] **Step 3: Implement** the tracker (L2-normalise on entry; cosine = dot; centroids as normalised running means; the three-band rule from spec §3.2) and the embedder (stream = `createStream()`, `acceptWaveform(pcm, sampleRate)`, `inputFinished()`, `if (!isReady(stream)) return null`, `compute(stream)`, release the stream). Copy the model into assets; add the asset to `app/build.gradle.kts` `aaptOptions`/`androidResources { noCompress += "onnx" }` if not already (check how `ggml-silero-v5.1.2.bin` is handled).
- [ ] **Step 4: Run** the tracker test + the pin → PASS; `:app:compileReleaseKotlin` → PASS.
- [ ] **Step 5: Commit** — `feat(speakers): a pure tracker with a hysteresis band, and the one adapter over sherpa's embedding extractor`.

---

### Task 3: The spike wiring — embed, assign, and say so in a diag line (no user-visible change)

**Files:**
- Modify: `app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt` (`onSegmentResolved` ~4922; session start ~4330-4370; teardown ~5169/5280; `onDestroy`)
- Modify: `app/src/main/cpp/whisper_jni.cpp` + `WhisperNative.kt`: `external fun diag(line: String)` → `LOGDIAG("%s", ...)` so the spike's line survives R8 in the release build the owner tests on (the Kotlin `Log` calls are stripped; memory of 2026-09-02)
- Modify: `app/src/main/java/com/whispereverywhere/data/local/PreferencesManager.kt` + `SettingsScreen.kt`: the two switches `detectSpeakers` (default true) and `speakerLabelsInExport` (default false), rows under Preferences (copy: "Detect speakers — Splits the transcript into paragraphs when a different person speaks; shows Speaker labels in the transcript window once a second voice is heard" / "Speaker labels in copied and saved text — Off: paragraphs only. On: each paragraph starts with Speaker 1, Speaker 2 …")
- Test: `SpeakerWiringPinTest.kt` (source pin: the embedder runs on its own executor named `speaker-embed`; `onSegmentResolved` never awaits it; the tracker is reset at session start and the embedder released at teardown; `PreviewTeeEngine.kt` and `transcription/stream/` contain no reference to `SpeakerTracker`/`SpeakerEmbedder`), `PreferencesSpeakerSettingsTest.kt` (defaults + round trip), `app/build.gradle.kts` `sourcePinnedInputs` gains the new pinned files.

**Interfaces:**
- Consumes Task 1's `spans`/`vad`, Task 2's tracker/embedder.
- Produces: per resolved `Text` outcome with `vad != null`, a job on the `speaker-embed` executor that, for each VAD segment ≥ `MIN_EMBED_SECONDS`, slices the ORIGINAL chunk pcm `[origStart, origEnd)` (the engine must keep the chunk's samples reachable by seq: add `SegmentOutcome.Text.pcmRef: FloatArray?` set by the engine for `Text` outcomes when `detectSpeakers` is on — or, simpler, have the embedder job run INSIDE the engine right after transcribe on a separate executor and attach `speakerIds: List<Int>?` per VAD segment to the outcome; **choose the engine-side placement**: the pcm is in scope there, nothing new crosses the seam except ids), assigns via the tracker, and emits ONE diag line per chunk through `WhisperNative.diag`: `speaker: seq=<n> segs=<n> embedMs=<total> ids=[1,1,2] best=[0.91,0.88,0.31] dur=[3.2,1.1,2.4] confirmed=<0|1>`. Nothing rendered yet.

- [ ] **Step 1: Failing pins** (`SpeakerWiringPinTest`, `PreferencesSpeakerSettingsTest`).
- [ ] **Step 2: Run → FAIL.**
- [ ] **Step 3: Implement.** Engine side: a `SpeakerAssigner` (owns the executor, the embedder, the tracker; `fun assign(seq, samples, vad, onDone: (List<Int>, confirmed: Boolean, stats) -> Unit)`), constructed by the service per session when `detectSpeakers` is on and handed to the engine (`LocalWhisperEngine.speakerAssigner: SpeakerAssigner?`); the engine calls it after `resolve` with the chunk's samples and `vad`; the callback lands on the service's Main thread and (for now) only logs the diag line via `WhisperNative.diag`. Reset at session start, release at teardown. Settings rows and preferences.
- [ ] **Step 4: Run** pins + suite → PASS; `:app:compileReleaseKotlin` → PASS.
- [ ] **Step 5: Commit** — `feat(speakers): the spike — every chunk's voices are fingerprinted and assigned, and one diag line says what was decided`.

---

### Task 4: The device session (owner + controller; no code)

- [ ] **Step 1:** Controller builds the release APK (`:app:testDebugUnitTest --rerun :app:assembleRelease` in one Gradle run), verifies 4.9.2/100 with aapt, installs on the Tab with `adb install -r`, starts `adb logcat -v time -s WE-DIAG` to a file. Then the Fold6 the same way if the owner connects it (it runs the Play copy — a local build cannot install over it; the Fold6 session waits for the internal track).
- [ ] **Step 2:** Owner runs, on turbo or medium, device audio: (a) the TEDx talk, 3 min — expect `confirmed=0` throughout; (b) a two-person YouTube interview, 3 min; (c) a three-person podcast clip, 3 min; (d) the owner dictating alone 1 min, then a second person in the room, then the owner again.
- [ ] **Step 3:** Controller reads the log: per-segment `embedMs` (budget: median < 100 ms, worst < 300 ms, no chunk's total over 1 s); on (a) zero false speakers; on (b)-(d) the id sequence against what the owner heard (he reports "the second voice came in at about 0:40, the host came back at 1:30"). From `best=` distributions the controller sets `T_SAME`/`T_NEW` (and, if CAM++ splits one voice repeatedly, tries WeSpeaker ResNet34 by swapping the asset — same adapter). Record the numbers in `docs/measurements/2026-09-1x-speaker-spike.md`.
- [ ] **Step 4:** The thresholds become the tracker's defaults (one commit, `chore(speakers): thresholds from the device session`).

---

### Task 5: Runs everywhere — the run-aware sink, the panel labels, the delivered file, history

**Files:**
- Modify: `app/src/main/java/com/whispereverywhere/transcription/TranscriptSink.kt` (run-aware: `append(text: String, speakerId: Int?)`, `setLabels(on: Boolean)`; preview rendered from runs; the file rendered at `close()`)
- Create: `app/src/main/java/com/whispereverywhere/transcription/speakers/SpeakerLabels.kt` (pure formatter: `render(runs: List<Run>, mode: Mode): String` with `Mode.PANEL(labels: Boolean)`, `Mode.FIELD`, `Mode.EXPORT(labels: Boolean)` — the spec §2 table; paragraphs joined by `"\n\n"`; label form `Speaker N: `; one-speaker sessions render exactly `TextJoin`-joined text with no breaks)
- Modify: `FloatingBubbleService.kt` (`handleTranscriptionResult` takes the outcome's spans + the assigner's ids → `sink.append(run)` per run; `sessionTranscript` becomes a run list; the latch flip calls `sink.setLabels(true)` which re-renders the preview from the session start; `deliverFinalTranscript` receives `SpeakerLabels.render(runs, FIELD)` for injection and `render(runs, EXPORT(prefs.speakerLabelsInExport))` for the clipboard; history saves `render(runs, EXPORT(false))` as today's text file PLUS a sidecar `<startedAtMs>.speakers.json` of run boundaries)
- Modify: `TranscriptStore.kt` (`save(startedAtMs, text, runs: List<Run>?)`, `readRuns(entry): List<Run>?`), `TranscriptsScreen.kt` (copy/share render with `EXPORT(prefs.speakerLabelsInExport)` when runs exist)
- Tests: `SpeakerLabelsTest` (every cell of §2; the no-break one-speaker case byte-equal to `TextJoin` joining), `TranscriptSinkTest` additions (runs, relabel re-render, file = FIELD mode), `TranscriptStoreTest` additions (sidecar round trip, missing sidecar → null), the service pins updated (`AccessibilityOptionalDeliveryPinTest` counts the same three clipboard writes and two injections; `PerceivedStampPinTest`'s blank gate unchanged).

**Interfaces:**
- `data class Run(val speakerId: Int, val text: String)`; the assigner's ids arrive asynchronously → the sink appends the run with `speakerId = null` immediately (text is never delayed) and `sink.relabel(seq, ids)` patches the run's speaker when the assignment lands; the panel re-renders on both events.

- [ ] Steps 1-5 as in the tasks above (tests first; run; implement; run the whole suite; commit `feat(speakers): committed text is paragraphs by speaker — labels in the window once a second voice is heard, paragraphs only in a field, labels in exports behind the switch`).

---

### Task 6: Release identity, notes, acceptance rows

- `app/build.gradle.kts` → `4.10.0` / `100` (a MINOR: a new capability); `ReleaseIdentityTest`; the acceptance sheet gains §AO (speaker labels: the four device scenarios with the spike's numbers); release notes draft in the scratchpad; the licence row for the CAM++ model in `docs/LANGUAGE-CLEARANCE.md`'s sibling sheet (production gate).

---

## Self-review (controller, 2026-09-18)

- Spec coverage: §2 table → Task 5 (`SpeakerLabels` modes) + settings in Task 3; §3.2 steps 1-5 → Tasks 0, 1, 2, 3, 5; §3.3 (own thread, floors untouched) → Task 3's executor + pin; §3.4 bundling → Task 2; §3.5 files → Tasks 0-5; §6 spike → Tasks 3-4; §7 open items: 0 ruled (bundle), 1 (detect default on) and 2 (`Speaker N:`) taken as proposed, 3 (no marker before confirmation) taken as proposed.
- Placeholders: Task 5 and 6 steps are compressed ("Steps 1-5 as above") — the implementer of Task 5 expands them in its brief; every interface is named.
- Type consistency: `VadSeg`, `SpeakerSpan`, `Run`, `SpeakerAssigner`, `SpeakerLabels.Mode` used with the same names across tasks; `SegmentOutcome.Text`'s two new fields default to null everywhere.
