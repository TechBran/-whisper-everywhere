# Speaker Timing Layer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Workflow agents run on Opus (owner ruling 2026-09-18); the controller (Fable) adjudicates.

**Goal:** Every tier reports *when* it said each piece of text, so speaker windows can be cut at a word (CPU) or a sentence (NPU) instead of at a pause — which is what stops a 15-second pause-free chunk from collapsing to one speaker.

**Architecture:** Two independent timing sources feed one existing consumer. The CPU tiers turn on whisper.cpp's per-token timestamps and export them beside the geometry they already export. The NPU tier stops suppressing whisper's timestamp tokens, which it already has in its vocabulary, and parses them into sentence bounds. `SpeakerSpans` then cuts windows at those times; everything downstream (`SpeakerTracker`, `SpeakerReclusterer`, `SpeakerRuns`, `SpeakerLabels`, `TranscriptSink`) is untouched because it all keys on window indices.

**Tech Stack:** Kotlin, whisper.cpp JNI (C++17), the QNN decoder (`qnn_asr.cpp` + `NpuDecodePolicy`), JUnit4 plus the repo's source-pin idiom.

**Spec:** `docs/superpowers/specs/2026-09-19-speaker-boundaries-design.md` (layer 1 only; layer 2 is a later plan, after the spike). Evidence for why: `docs/measurements/2026-09-18-speaker-spike.md` §Session 7.

## Global Constraints

- NEVER run `:app:installDebug` or `:app:connectedDebugAndroidTest`. Install only with `adb install -r`. Two devices are attached: address them with `adb -s` (tablet `R52XC00LL9K`, Fold6 `RFCX60XC85K`).
- Never read, print, log or commit `C:/Users/bastr/.androidbuild/gemini.key`. **No transcript content in any log line** — diag lines carry numbers only.
- **`dtw_token_timestamps` must never be set.** whisper.cpp gates the new-segment callback on `!dtw_token_timestamps` (`whisper_jni.cpp:1193`), so enabling it silently kills live words. A pin asserts it is absent.
- The transcript's TEXT must not change on any tier. Timing is additive; a test asserts byte-identical text with timing on and off.
- Everything speaker-related stays on the `speaker-embed` executor. The whisper thread, the audio thread, `CommitCadencePolicy`'s floors and the live preview strip are untouched.
- Cloud sessions are untouched (`speakerAssigner` is null there by construction).
- Commit in the repo's style, with these trailers on every commit:
  `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`
  `Claude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT`
- Do not push. Do not build an APK or AAB; the controller builds.

## File structure

| file | responsibility after this plan |
|---|---|
| `app/src/main/cpp/whisper_jni.cpp` | adds `lastTokenTimes` export (per-token times), sets `params.token_timestamps` |
| `app/src/main/java/com/whispereverywhere/whisper/WhisperNative.kt` | declares `lastTokenTimes` |
| `app/src/main/java/com/whispereverywhere/transcription/TranscriptionEngine.kt` | `SegmentGeometry` gains `tokenTimes`; the backend seam unchanged in shape |
| `app/src/main/java/com/whispereverywhere/transcription/speakers/SpeakerSpans.kt` | windows may be cut at token times; sentence-driven windows for the NPU route |
| `app/src/main/java/com/whispereverywhere/npu/NpuDecodePolicy.kt` | prompt without `<\|notimestamps\|>`, suppression without the timestamp range |
| `app/src/main/java/com/whispereverywhere/npu/NpuSentences.kt` (new) | pure: token array → sentence bounds `[t0cs, t1cs, byteStart, byteEnd]` |
| `app/src/main/java/com/whispereverywhere/transcription/NpuWhisperBackend.kt` | carries the decoded sentence bounds off the decode call |
| `app/src/main/java/com/whispereverywhere/transcription/speakers/SpeakerAssigner.kt` | the VAD route subdivides by sentences when it has them |

---

### Task 1: whisper.cpp reports per-token times

**Files:**
- Modify: `app/src/main/cpp/whisper_jni.cpp` (params block ~1115-1121; the result loop ~1077-1095; a new export beside `lastWhisperSegments`)
- Modify: `app/src/main/java/com/whispereverywhere/whisper/WhisperNative.kt`
- Test: `app/src/test/java/com/whispereverywhere/whisper/SegmentGeometryPinTest.kt` (extend)

**Interfaces:**
- Produces: `WhisperNative.lastTokenTimes(): IntArray` — `[t0cs, t1cs, byteStart, byteEnd]` per **token** of the last `transcribeRaw` on this thread, byte offsets into the returned `ByteArray`, empty when timing was unavailable. Same process-global, same-thread, cleared-at-entry contract as `lastVadSegments` / `lastWhisperSegments`, under the same `g_geom_mutex`.

- [ ] **Step 1: extend the pin test (fails first)**

```kotlin
@Test fun tokenTimesAreExportedUnderTheSameContractAsTheOtherGeometry() {
    assertTrue(ktSrc.contains("external fun lastTokenTimes(): IntArray"))
    assertTrue(cppSrc.contains("Java_com_whispereverywhere_whisper_WhisperNative_lastTokenTimes"))
    assertTrue("token timing must be asked for", cppSrc.contains("params.token_timestamps = true"))
    assertFalse(
        "DTW silently kills the streaming callback (whisper.cpp:7678) — never set it",
        cppSrc.contains("dtw_token_timestamps"),
    )
    val clear = cppSrc.indexOf("g_last_token_times.clear()")
    val swap = cppSrc.indexOf("pcm.swap(filtered);")
    assertTrue("cleared at entry, like its siblings", clear in 1 until swap || clear > 0)
}
```

- [ ] **Step 2: run it, expect failure** — `.\gradlew.bat :app:testDebugUnitTest --tests "*SegmentGeometryPinTest*" --no-daemon`

- [ ] **Step 3: implement.** Global beside the other two: `static std::vector<jint> g_last_token_times;`, cleared with them. In the params block: `params.token_timestamps = true;` (and nothing else — no `max_len`, no `split_on_word`, which would change segmentation and therefore text). In the result loop, for each segment `i`, walk its tokens and record those that carry text:

```cpp
        const int nTok = whisper_full_n_tokens(ctx, i);
        for (int j = 0; j < nTok; ++j) {
            const whisper_token id = whisper_full_get_token_id(ctx, i, j);
            if (id >= whisper_token_eot(ctx)) continue;      // specials carry no text
            const char *tt = whisper_full_get_token_text(ctx, i, j);
            if (tt == nullptr || *tt == '\0') continue;
            const auto data = whisper_full_get_token_data(ctx, i, j);
            const jint b0 = static_cast<jint>(result.size());
            result += tt;
            const jint vals[4] = {static_cast<jint>(data.t0), static_cast<jint>(data.t1), b0,
                                  static_cast<jint>(result.size())};
            for (const jint v : vals) g_last_token_times.push_back(v);
        }
```

**Note:** the loop above must produce byte-for-byte the same `result` string the current segment-text loop produces. Concatenating token texts is whisper's own segment text; verify by keeping both paths and asserting equality in a scratch build, then delete the old accumulation. If they ever differ, keep the segment text as authoritative and emit token times without rebuilding `result`.

Export with the existing `we_int_vector` helper; declare in Kotlin beside `lastWhisperSegments` with a KDoc naming the contract.

- [ ] **Step 4: run** the pin test, `:app:buildCMakeRelWithDebInfo[arm64-v8a]`, and the full suite. All green.

- [ ] **Step 5: commit** — `feat(speakers): whisper says when it said each word`

---

### Task 2: windows cut at a word on the CPU tiers

**Files:**
- Modify: `app/src/main/java/com/whispereverywhere/transcription/TranscriptionEngine.kt` (`SegmentGeometry`)
- Modify: `app/src/main/java/com/whispereverywhere/transcription/LocalWhisperEngine.kt` (geometry capture)
- Modify: `app/src/main/java/com/whispereverywhere/transcription/speakers/SpeakerSpans.kt`
- Test: `app/src/test/java/com/whispereverywhere/transcription/speakers/SpeakerSpansTest.kt`

**Interfaces:**
- Consumes: `WhisperNative.lastTokenTimes()` from Task 1.
- Produces: `SegmentGeometry(vadSegments, whisperSegments, tokenTimes)` — third array defaulted to `IntArray(0)` so every existing construction compiles. `SpeakerSpans.windows(raw, vad, tokenTimes)` — when `tokenTimes` is non-empty a window boundary may fall at any token edge inside a sentence; when empty the behaviour is today's, unchanged.

- [ ] **Step 1: failing tests.** A VAD segment holding one 6 s sentence whose tokens span two halves: with `tokenTimes` supplied, a requested split near the middle lands on the nearest token edge, never inside a token's byte range; with `tokenTimes` empty, the same input yields today's single window. Token edges outside the segment are ignored. Byte ranges of adjacent windows are contiguous and cover the text exactly.
- [ ] **Step 2: run, expect failure.**
- [ ] **Step 3: implement.** Keep `windows(raw, vad)` as an overload delegating with an empty array, so no call site outside this task changes. The cut rule: given a candidate time inside a VAD segment, choose the token boundary minimising |tokenEdge - candidate|, subject to both resulting windows being ≥ `MIN_WINDOW_SECONDS`.
- [ ] **Step 4: run** the suite; the CPU route's existing tests must pass unchanged.
- [ ] **Step 5: commit** — `feat(speakers): a window may end at a word, not only at a sentence`

---

### Task 3: the NPU tier emits timestamps

**Files:**
- Modify: `app/src/main/java/com/whispereverywhere/npu/NpuDecodePolicy.kt` (`promptTokens`, `suppressList`)
- Create: `app/src/main/java/com/whispereverywhere/npu/NpuSentences.kt` (pure)
- Modify: `app/src/main/java/com/whispereverywhere/transcription/NpuWhisperBackend.kt` (keep the decoded sentence bounds)
- Test: `app/src/test/java/com/whispereverywhere/npu/NpuSentencesTest.kt`, plus the existing `NpuDecodePolicy` tests

**Interfaces:**
- Produces: `NpuSentences.of(tokens: IntArray, family: WhisperTokenFamily, decode: (IntArray) -> String): IntArray` — `[t0cs, t1cs, byteStart, byteEnd]` per sentence, byte offsets into the text the same call would produce, empty when the stream carries no timestamp pair. `NpuWhisperBackend.lastSentences(ctx: Long): IntArray` for the engine to read, same one-slot ctx-tagged discipline as `lastSegmentStats`.

- [ ] **Step 1: failing tests** over synthetic token arrays: `<|0.00|> Hello there <|2.40|> <|2.40|> And you <|4.00|>` yields two sentences with the right centisecond bounds and byte ranges that slice the decoded text exactly; an unterminated final sentence takes the last emitted timestamp, or the chunk end when there is none; a stream with no timestamp tokens yields an empty array (the old behaviour's shape); timestamps never appear in the decoded text.
- [ ] **Step 2: run, expect failure.**
- [ ] **Step 3: implement.** `promptTokens` drops `family.noTimestamps`; `suppressList` drops the `timestampBegin until family.vocab` range (keep every other suppression and the ascending-unique precondition). Time for id `t` is `(t - family.timestampBegin) * 2` centiseconds. The BPE decoder must be fed only text tokens.
- [ ] **Step 4: run** the suite and `:app:compileReleaseKotlin`.
- [ ] **Step 5: commit** — `feat(npu): the decoder is allowed to say when, and the tier parses it`

---

### Task 4: the NPU route makes a window per sentence

**Files:**
- Modify: `app/src/main/java/com/whispereverywhere/transcription/speakers/SpeakerAssigner.kt` (`assignWholeChunk` → sentence-aware)
- Modify: `app/src/main/java/com/whispereverywhere/transcription/speakers/SpeakerSpans.kt` (VAD spans × sentences)
- Modify: `app/src/main/java/com/whispereverywhere/transcription/LocalWhisperEngine.kt` (pass the sentences; build per-sentence spans for the outcome)
- Test: `SpeakerAssignerTest`, `SpeakerSpansTest`, `LocalWhisperEngineSpeakerRouteTest`

**Interfaces:**
- Consumes: Task 3's `lastSentences`, Task 1's window machinery.
- Produces: `SpeakerAssigner.assignVadRoute(seq, samples, vadModelPath, sentences: IntArray)` — when `sentences` is empty it behaves exactly as today's `assignWholeChunk` (one id for the chunk, `pick` set); when non-empty it subdivides the VAD spans at sentence bounds, fingerprints each window, and publishes **per-window ids with per-sentence spans**, so the chunk becomes one run per sentence rather than one run.

- [ ] **Step 1: failing tests.** A 15 s VAD segment carrying four sentences yields four windows and four runs; ids differ where the voices differ. With no sentences the whole-chunk behaviour is byte-identical to today (the existing tests must still pass untouched). A sentence shorter than `MIN_WINDOW_SECONDS` coalesces with its neighbour but keeps its own span, so the text still splits where the sentence does.
- [ ] **Step 2: run, expect failure.**
- [ ] **Step 3: implement.**
- [ ] **Step 4: run** the suite plus `:app:compileReleaseKotlin`.
- [ ] **Step 5: commit** — `feat(npu): one speaker per sentence, not one per chunk`

---

### Task 5: identity, acceptance and the numbers to read

**Files:** `app/build.gradle.kts` (versionCode 102, versionName 4.11.0 — 101 is on the internal track and spent), `ReleaseIdentityTest`, `docs/superpowers/sdd/2026-09-02-431-guards-tts/acceptance.md` (§AO rows), `docs/measurements/2026-09-18-speaker-spike.md` (a Session 8 stub).

- [ ] Version and its paragraph, in the file's established style.
- [ ] §AO gains: on the NPU tier a pause-free two-voice clip keeps alternating labels past two minutes (the Session 7 failure); on both tiers the transcript text is unchanged with timing on; the live words strip still works (the DTW landmine's user-visible proof).
- [ ] A Session 8 stub naming what the controller reads from the device: `windows=` per chunk on the NPU tier (expect > 1 where Session 7 had 1), `embedMs=` against the 3,000 ms fence, and whether labels still alternate at the three-minute mark.
- [ ] Commit — `chore(release): 4.11.0 at versionCode 102 — the timing layer`

---

## Self-review

**Spec coverage.** Layer 1's CPU half → Tasks 1-2; its NPU half → Tasks 3-4; the DTW prohibition → Global Constraints and Task 1's pin; "no text change" → Global Constraints, asserted in Tasks 1 and 3; the acceptance rows and the measurement → Task 5. Layer 2 is out of scope by design, as the spec says.

**Placeholders.** None: every task names its files, its interface signatures and what its tests assert. Task 1 carries the only real unknown — whether concatenated token text reproduces segment text byte-for-byte — and it is written as a verify-then-choose step with a stated fallback rather than an assumption.

**Type consistency.** `SegmentGeometry(vadSegments, whisperSegments, tokenTimes)`, `SpeakerWindow`, `SpeakerSpan(windowIndex, text)`, `assignVadRoute(seq, samples, vadModelPath, sentences)` and `NpuSentences.of(...)` are used with these exact names in every task that mentions them. `assignWholeChunk` is renamed once, in Task 4, with its no-sentences behaviour preserved.
