# 4.3.1 — Decode guards, bubble survives a read, projected-complete playback: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship 4.3.1 (versionCode 83): the NPU decode loop gains whisper.cpp's three quality gates, the floating bubble can no longer be hidden mid-read, and read-aloud playback starts only when the projected remainder is covered.

**Architecture:** Three independent workstreams on one branch. (A) Policy constants live in `NpuDecodePolicy` and cross JNI as arguments; the native loop applies the temperature ladder and reports six stats; Kotlin makes the no-speech call. (B) A pure `BubbleHidePolicy` decides Ignore/Defer/Hide at the single sink `hideBubble(reason)`; a parked hide replays when the read ends. (C) `TtsBufferPolicy.startDecision` gates the FIRST start on `banked ≥ 1.5 × rtf × remaining`; the engine publishes remaining chars and an estimated total; the scrubber draws the generating region.

**Tech Stack:** Kotlin 2.0.21 / AGP 8.7.3 / Gradle 8.14.4 / JUnit 4 (JVM only — no instrumented tests exist); C++17 JNI in `app/src/main/cpp/qnn_asr.cpp` against the raw QNN C API; sherpa-onnx TTS; Android `AudioTrack`.

**Spec:** `docs/superpowers/specs/2026-09-02-431-decode-guards-bubble-tts-design.md` (commit `865cdf6`). The plan argues from the spec; read both.

## Global Constraints

- **Branch:** `feat/4.3.1-guards-and-tts` (already exists, spec at `865cdf6`, off `main` `3f982b9`). Never commit to `main`.
- **Release identity:** `versionCode = 83`, `versionName = "4.3.1"` — 82 is spent in production.
- **Build env (PowerShell 5.1):** every shell that runs Gradle must first set `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio1\jbr"` (the literal `1` is real). Always `.\gradlew.bat ... --no-daemon`. Build outputs live OUTSIDE the repo at `C:\Users\bastr\.androidbuild\WhisperEverywhere\`.
- **NEVER run `:app:installDebug` or `:app:connectedDebugAndroidTest`** — both uninstall the app and wipe the owner's 1 GB of downloaded models. There is no device step in this plan except the owner's own session at the end.
- **JVM suite command:** `.\gradlew.bat :app:testDebugUnitTest --no-daemon` (add `--tests "<fqcn>"` for one class). **Before every run purge the XML** so a stale result can never be read: `Remove-Item -Recurse -Force "C:\Users\bastr\.androidbuild\WhisperEverywhere\app\test-results\testDebugUnitTest" -ErrorAction SilentlyContinue`. Results are read from the raw XML, never from the console: see "Counting the suite" below. Gradle printing `UP-TO-DATE` for the test task means it did NOT run — purge and re-run.
- **Native compiles only under `:app:assembleDebug`.** The JVM suite never compiles `qnn_asr.cpp`; after any C++ edit run `.\gradlew.bat :app:assembleDebug --no-daemon` and require `BUILD SUCCESSFUL`.
- **Source-reading tests** (`NpuNativeContractTest`, `NpuBackendWiringTest`, the new `BubbleHideWiringPinTest`) read repo files at test time. Every file such a test reads MUST be listed in `sourcePinnedInputs` in `app/build.gradle.kts` (the block ending at `:506`), or Gradle marks the test UP-TO-DATE when only that file changes and the pin passes against stale evidence. `FloatingBubbleService.kt` and `qnn_asr.cpp` are already listed; add any NEW file a new test reads.
- **Pins bite on LIVE lines only.** Count and order with the `liveLines` / `liveOffsets` helpers (they skip `//`, `/*`, `*` lines); never with `indexOf`/`contains` on a whole file — an implementer's own comment can answer a whole-file count.
- **Commit messages** are written to a UTF-8 **no-BOM** file and applied with `git commit -F <file>` (an em dash in a `-m` argument arrives mangled). Every commit ends with exactly these two trailer lines:
  ```
  Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01NTcK744kxdCw2Vgq16c3Ye
  ```
  Verify with `git log -1 --format=%B | tail -2`. Porcelain must be clean after every commit (`git status --porcelain` prints nothing).
- **Diagnostics never carry transcript content.** Numbers, codes, stage names only (`NpuDiag` KDoc, `diagToken` in native).
- **No new dependencies.**

### Counting the suite (paste into PowerShell after a run)

```powershell
$dir = "C:\Users\bastr\.androidbuild\WhisperEverywhere\app\test-results\testDebugUnitTest"
$s = Get-ChildItem "$dir\*.xml" | ForEach-Object { ([xml](Get-Content $_.FullName)).testsuite }
"suites=$($s.Count) tests=$(($s | Measure-Object tests -Sum).Sum) failures=$(($s | Measure-Object failures -Sum).Sum) errors=$(($s | Measure-Object errors -Sum).Sum)"
```
Baseline at `865cdf6`: **150 suites / 1,836 tests / 0 failures / 0 errors.** Every task's final step re-measures the whole suite and records the delta in its task report.

### Committing (paste into PowerShell; edit the message)

```powershell
$msg = @'
<type>(<scope>): <subject>

<body>

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01NTcK744kxdCw2Vgq16c3Ye
'@
[System.IO.File]::WriteAllText("C:\Users\bastr\.androidbuild\commit-msg.txt", $msg, (New-Object System.Text.UTF8Encoding $false))
git add <files>
git commit -F "C:\Users\bastr\.androidbuild\commit-msg.txt"
git log -1 --format=%B | Select-Object -Last 2
git status --porcelain
```

### Execution protocol (the SDD loop this project uses)

One fresh implementer per task. The implementer ends by writing `.superpowers/sdd/2026-09-02-431-guards-tts/task-<N>-report.md` (what changed, the suite count before/after from XML, the battery rows below executed with their red/green evidence). The controller then saves `git diff <parent>..<head>` beside it as `review-<parent>..<head>.diff` and dispatches a reviewer against the spec + report; findings go through one fix round and a scoped re-review before the next task starts. The ledger `progress.md` in that directory records every ruling.

**Battery rows** (per task, listed under "Battery"): each row temporarily applies one mutation to the implementation, runs the named test class, and must observe RED; then the mutation is reverted (`git checkout -- <file>`) and the class must be GREEN again. Evidence = the XML `failures` count for that class in both states. A pin that stays green under its mutation is a hole and the task is not done.

---

## File map

| # | File | Responsibility | Task |
|---|---|---|---|
| 1 | `app/build.gradle.kts` (`:70-71` region — `versionCode`/`versionName`) | release identity | 1 |
| 2 | `app/src/test/java/com/whispereverywhere/ReleaseIdentityTest.kt` | pins the identity | 1 |
| 3 | `.superpowers/sdd/2026-09-02-431-guards-tts/progress.md` (create) | the ledger | 1 |
| 4 | `app/src/main/java/com/whispereverywhere/npu/NpuDecodePolicy.kt` | guard thresholds, ladder, `isNoSpeech` | 2 |
| 5 | `app/src/main/java/com/whispereverywhere/npu/NpuDecodeStats.kt` (create) | stats slot names + terminator codes | 2 |
| 6 | `app/src/test/java/com/whispereverywhere/npu/NpuDecodePolicyTest.kt` | pins 4 and 5 | 2 |
| 7 | `app/src/main/cpp/qnn_asr.cpp` (`:2875-3115` nativeDecodeSegment; `:1885-1922` mask+argmax; `:36-50` includes) | the guarded loop | 3 |
| 8 | `app/src/main/java/com/whispereverywhere/npu/QnnAsrNative.kt` (`:195-219`) | the JNI declaration | 3 |
| 9 | `app/src/test/java/com/whispereverywhere/npu/NpuNativeContractTest.kt` | source pins on 7 ↔ 5 | 3 |
| 10 | `app/src/main/java/com/whispereverywhere/transcription/NpuWhisperBackend.kt` (`:624-663`) | pass policy in, read stats, blank on no-speech | 4 |
| 11 | `app/src/main/java/com/whispereverywhere/npu/NpuDiag.kt` (`:44-45`) | the grown `npu:` line | 4 |
| 12 | `app/src/test/java/com/whispereverywhere/npu/NpuDiagTest.kt`, `NpuBackendWiringTest.kt` | pins 10 and 11 | 4 |
| 13 | `app/src/main/java/com/whispereverywhere/service/BubbleHidePolicy.kt` (create) | pure hide decision | 5 |
| 14 | `app/src/test/java/com/whispereverywhere/service/BubbleHidePolicyTest.kt` (create) | pins 13 | 5 |
| 15 | `app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt` (`:1413` hideBubble; callers `:845,:1144,:1196,:3483`; `:944-983` startSpeaking; IDLE branch `:3453-3486`) | the sink, the replay, the trigger reset | 6 |
| 16 | `app/src/main/java/com/whispereverywhere/tts/TtsController.kt` (`:74-108`) | `speakFromTrigger` returns Boolean | 6 |
| 17 | `app/src/test/java/com/whispereverywhere/service/BubbleHideWiringPinTest.kt` (create) | source pins on 15 | 6 |
| 18 | `app/src/main/java/com/whispereverywhere/tts/TtsBufferPolicy.kt` | `startDecision` / `shouldStart` | 7 |
| 19 | `app/src/main/java/com/whispereverywhere/tts/TtsRemainingEstimate.kt` (create) | chars → ms/samples | 7 |
| 20 | `app/src/test/java/com/whispereverywhere/tts/TtsBufferPolicyTest.kt`, `TtsRemainingEstimateTest.kt` (create) | pins 18, 19 | 7 |
| 21 | `app/src/main/java/com/whispereverywhere/tts/TtsDiag.kt`, `TtsDiagTest.kt` | `TTSDIAG start` line | 8 |
| 22 | `app/src/main/java/com/whispereverywhere/tts/TtsEngine.kt` (`:116-120` onProgress; `:294-297` policy; `:352-393` gate; `:476-493` progress; `:505-538` callback; `:566-661` producer) | wiring | 8 |
| 23 | `app/src/main/java/com/whispereverywhere/ui/components/TtsScrubberView.kt` (`:28-30`, `:59-83`, `:105-109`) | estimated total, generating region, seek re-basing | 9 |
| 24 | `FloatingBubbleService.kt` (`:970-972` onProgress adapter) | 4-arg adapter | 9 |
| 25 | `docs/superpowers/sdd/2026-09-02-431-guards-tts/acceptance.md` (create) | the owner's device sheet | 10 |

---

### Task 1: Release identity 4.3.1 / 83 and the ledger

**Files:**
- Modify: `app/build.gradle.kts` (the `defaultConfig` lines currently `versionCode = 82` / `versionName = "4.3.0"`)
- Modify: `app/src/test/java/com/whispereverywhere/ReleaseIdentityTest.kt`
- Create: `.superpowers/sdd/2026-09-02-431-guards-tts/progress.md`

**Interfaces:**
- Produces: `BuildConfig.VERSION_CODE == 83`, `BuildConfig.VERSION_NAME == "4.3.1"`.

- [ ] **Step 1: Write the failing test** — replace the test method in `ReleaseIdentityTest.kt` and add one KDoc paragraph in the existing voice:

```kotlin
    /*
     * **versionCode 83 — the plain successor to 82.** 82 went to PRODUCTION on 2026-08-30 as 4.3.0,
     * so it is spent twice over: Play refuses a second upload at the same code, and every installed
     * phone already carries it. 4.3.1 is a patch (three field reports, no new surface), so the NAME
     * moves by one in the last place and the code by one integer; 83 > 82 is what lets the track
     * install replace the production build on the owner's own phone.
     */
    @Test
    fun release_identity_is_4_3_1_at_version_code_83() {
        assertEquals(
            "versionName must be 4.3.1 for this release (app/build.gradle.kts defaultConfig)",
            "4.3.1",
            BuildConfig.VERSION_NAME,
        )
        assertEquals(
            "versionCode must be 83 for this release (app/build.gradle.kts defaultConfig)",
            83,
            BuildConfig.VERSION_CODE,
        )
    }
```
Delete the old `release_identity_is_4_3_0_at_version_code_82` method. Place the new KDoc paragraph inside the class KDoc where the "versionCode 82" paragraph sits (keep that paragraph; it is history).

- [ ] **Step 2: Run it to see it fail**

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio1\jbr"
Remove-Item -Recurse -Force "C:\Users\bastr\.androidbuild\WhisperEverywhere\app\test-results\testDebugUnitTest" -ErrorAction SilentlyContinue
.\gradlew.bat :app:testDebugUnitTest --no-daemon --tests "com.whispereverywhere.ReleaseIdentityTest"
```
Expected: FAIL — `versionName must be 4.3.1 ... expected:<4.3.1> but was:<4.3.0>`.

- [ ] **Step 3: Bump the identity** in `app/build.gradle.kts`: `versionCode = 83`, `versionName = "4.3.1"`.

- [ ] **Step 4: Run it to see it pass** (same command). Expected: PASS, XML `failures=0` for the class.

- [ ] **Step 5: Create the ledger** `.superpowers/sdd/2026-09-02-431-guards-tts/progress.md`:

```markdown
# 4.3.1 — decode guards / bubble survives a read / projected-complete playback — ledger

Branch feat/4.3.1-guards-and-tts off main 3f982b9. Spec 865cdf6. Plan docs/superpowers/plans/2026-09-02-431-decode-guards-bubble-tts.md.
Baseline suite at 865cdf6: 150 suites / 1,836 tests / 0 failures / 0 errors.

=== Task 1: release identity 83 / 4.3.1 ===
(commit, suite count from XML, notes)
```

- [ ] **Step 6: Whole suite, then commit**

Purge, run the full suite (no `--tests`), count from XML. Expected `tests=1836 failures=0 errors=0` (the class swapped one test for one).

```
chore(release): 4.3.1 at versionCode 83 — 82 is in production

The three-report patch takes the next integer; the name moves in the last place.
ReleaseIdentityTest re-pinned. Ledger opened.
```
Files: `app/build.gradle.kts`, `app/src/test/java/com/whispereverywhere/ReleaseIdentityTest.kt`, `.superpowers/sdd/2026-09-02-431-guards-tts/progress.md`.

**Battery:** none (the pin IS the test).

---

### Task 2: The guard policy as data — `NpuDecodePolicy` + `NpuDecodeStats`

**Files:**
- Modify: `app/src/main/java/com/whispereverywhere/npu/NpuDecodePolicy.kt` (add a section after `maxTokensFor`, before `// --- the language policy (NEW-C2)`)
- Create: `app/src/main/java/com/whispereverywhere/npu/NpuDecodeStats.kt`
- Test: `app/src/test/java/com/whispereverywhere/npu/NpuDecodePolicyTest.kt` (append tests)

**Interfaces:**
- Produces (Task 3, 4 consume):
  - `NpuDecodePolicy.ENTROPY_THOLD: Float = 2.4f`, `LOGPROB_THOLD: Float = -1.0f`, `NO_SPEECH_THOLD: Float = 0.6f`, `ENTROPY_WINDOW: Int = 32`, `TEMPERATURES: FloatArray = [0.0, 0.2, 0.4, 0.6, 0.8, 1.0]`
  - `NpuDecodePolicy.isNoSpeech(noSpeechProb: Float, avgLogprob: Float): Boolean`
  - `object NpuDecodeStats { const val NO_SPEECH_PROB = 0; AVG_LOGPROB = 1; ENTROPY = 2; RUNG = 3; TERMINATOR = 4; STEPS = 5; SIZE = 6; TERM_EOT = 0f; TERM_BUDGET = 1f; TERM_CAP = 2f; TERM_CUT = 3f; fun terminatorName(code: Float): String; fun newArray(): FloatArray }`

- [ ] **Step 1: Write the failing tests** — append to `NpuDecodePolicyTest.kt` (inside the class):

```kotlin
    // ---------------------------------------------------------------- the decode guards (4.3.1 A)

    /**
     * whisper.cpp's defaults, verbatim: `whisper_full_default_params` in the fork at
     * app/src/main/cpp/whisper.cpp/src/whisper.cpp:6235-6238 — `temperature_inc 0.2f`,
     * `entropy_thold 2.4f`, `logprob_thold -1.0f`, `no_speech_thold 0.6f`. The CPU tier runs with
     * exactly these (whisper_jni.cpp:854 sets only temperature_inc, leaving the rest at default),
     * so the two tiers judge a segment by the same numbers. Written as literals here, not derived,
     * so a drift in either direction is a red test and not a quiet re-tune.
     */
    @Test
    fun guardThresholdsAreWhisperCppsDefaults() {
        assertEquals(2.4f, NpuDecodePolicy.ENTROPY_THOLD, 0f)
        assertEquals(-1.0f, NpuDecodePolicy.LOGPROB_THOLD, 0f)
        assertEquals(0.6f, NpuDecodePolicy.NO_SPEECH_THOLD, 0f)
        assertEquals(32, NpuDecodePolicy.ENTROPY_WINDOW)
    }

    /** `[t0, t0+0.2, ..., 1.0]` from `temperature = 0`, as whisper.cpp:7134-7141 builds it. */
    @Test
    fun theTemperatureLadderStartsGreedyAndClimbsByPointTwoToOne() {
        val t = NpuDecodePolicy.TEMPERATURES
        assertEquals(6, t.size)
        assertEquals(0.0f, t[0], 0f)
        for (i in 1 until t.size) {
            assertEquals("rung $i", 0.2f * i, t[i], 1e-6f)
        }
        assertEquals(1.0f, t.last(), 1e-6f)
    }

    /**
     * whisper.cpp:7865 — `is_no_speech = no_speech_prob > no_speech_thold && avg_logprobs <
     * logprob_thold`. BOTH strict. A confident transcript of a segment the model also thinks is
     * silent is kept (the model contradicted itself; the words win), and an unconfident transcript
     * of a segment the model thinks has speech is kept too (that is a hard segment, not silence).
     */
    @Test
    fun noSpeechNeedsBothAHighNoSpeechProbabilityAndALowAverageLogprob() {
        assertTrue(NpuDecodePolicy.isNoSpeech(noSpeechProb = 0.61f, avgLogprob = -1.01f))
        assertFalse("at the threshold is not over it", NpuDecodePolicy.isNoSpeech(0.6f, -1.01f))
        assertFalse("at the threshold is not under it", NpuDecodePolicy.isNoSpeech(0.61f, -1.0f))
        assertFalse("confident words beat a silence vote", NpuDecodePolicy.isNoSpeech(0.99f, -0.2f))
        assertFalse("unconfident words in a speech segment are kept", NpuDecodePolicy.isNoSpeech(0.1f, -3f))
        assertFalse("NaN (no scale readable) never blanks a segment", NpuDecodePolicy.isNoSpeech(Float.NaN, Float.NaN))
        assertFalse("the native 'unreadable' sentinel never blanks a segment", NpuDecodePolicy.isNoSpeech(-1f, -5f))
    }

    /**
     * The stats array's slot names and terminator codes. Native mirrors them as `kStat*` /
     * `kTerm*` literals and NpuNativeContractTest holds the two copies equal by source text; this
     * test pins the Kotlin side's own values so that comparison has a fixed point.
     */
    @Test
    fun decodeStatsSlotsAndTerminatorCodesAreFixed() {
        assertEquals(0, NpuDecodeStats.NO_SPEECH_PROB)
        assertEquals(1, NpuDecodeStats.AVG_LOGPROB)
        assertEquals(2, NpuDecodeStats.ENTROPY)
        assertEquals(3, NpuDecodeStats.RUNG)
        assertEquals(4, NpuDecodeStats.TERMINATOR)
        assertEquals(5, NpuDecodeStats.STEPS)
        assertEquals(6, NpuDecodeStats.SIZE)
        assertEquals(6, NpuDecodeStats.newArray().size)
        assertTrue("a fresh array reads as 'nothing measured'", NpuDecodeStats.newArray().all { it.isNaN() })
        assertEquals("eot", NpuDecodeStats.terminatorName(NpuDecodeStats.TERM_EOT))
        assertEquals("budget", NpuDecodeStats.terminatorName(NpuDecodeStats.TERM_BUDGET))
        assertEquals("cap", NpuDecodeStats.terminatorName(NpuDecodeStats.TERM_CAP))
        assertEquals("cut", NpuDecodeStats.terminatorName(NpuDecodeStats.TERM_CUT))
        assertEquals("unknown", NpuDecodeStats.terminatorName(9f))
        assertEquals("unknown", NpuDecodeStats.terminatorName(Float.NaN))
    }
```
`assertFalse` is already imported? The file imports `assertTrue`, `assertEquals` …; add `import org.junit.Assert.assertFalse` if missing.

- [ ] **Step 2: Run to see them fail**

```powershell
.\gradlew.bat :app:testDebugUnitTest --no-daemon --tests "com.whispereverywhere.npu.NpuDecodePolicyTest"
```
Expected: compilation FAILS (`Unresolved reference: ENTROPY_THOLD`, `NpuDecodeStats`).

- [ ] **Step 3: Implement** — in `NpuDecodePolicy.kt`, after `maxTokensFor`:

```kotlin
    // ---------------------------------------------------------------- the decode guards (4.3.1 A)

    /**
     * whisper.cpp's three quality gates, as DATA handed to `nativeDecodeSegment` beside the
     * suppress lists — the same shape, for the same reason: native applies them because only the
     * loop can act on them (a rung must be re-run; a runaway must be stopped mid-loop), and Kotlin
     * owns the numbers so they are pinned where a JVM test can read them and there is exactly one
     * copy. Values are `whisper_full_default_params`' (whisper.cpp:6235-6238) — the CPU tier's.
     *
     * Why the NPU tier needs them at all: its loop was a bare greedy argmax with two terminators,
     * EOT or the 196-token budget. A greedy decode that enters a cycle cannot leave it — the argmax
     * is deterministic — so it ran to the budget ("one word × 70-80", owner 2026-09-01), and
     * `<|nospeech|>` was masked but never READ, so dead-time segments typed "Thank you."
     */
    /** Entropy of the last [ENTROPY_WINDOW] token ids below this is a repetition loop. */
    const val ENTROPY_THOLD = 2.4f
    /** Mean per-token log-probability below this is a low-confidence rung. */
    const val LOGPROB_THOLD = -1.0f
    /** `p(<|nospeech|>)` at the SOT step above this says the segment is silence. */
    const val NO_SPEECH_THOLD = 0.6f
    /** `whisper_sequence_score`'s n: the entropy is over the last 32 ids (whisper.cpp:6885). */
    const val ENTROPY_WINDOW = 32
    /** The fallback ladder, `temperature = 0` then `+= temperature_inc` (whisper.cpp:7134). */
    val TEMPERATURES: FloatArray = floatArrayOf(0.0f, 0.2f, 0.4f, 0.6f, 0.8f, 1.0f)

    /**
     * whisper.cpp:7865, both comparisons strict. NaN or a negative sentinel (native could not read
     * the logits' scale, so no probability was computed) answers false: a guard that cannot
     * measure must not blank a segment.
     */
    fun isNoSpeech(noSpeechProb: Float, avgLogprob: Float): Boolean =
        noSpeechProb > NO_SPEECH_THOLD && avgLogprob < LOGPROB_THOLD
```
(`NaN > x` and `NaN < x` are both false in Kotlin, which is what the test relies on.)

Create `NpuDecodeStats.kt`:

```kotlin
package com.whispereverywhere.npu

/**
 * The six numbers `nativeDecodeSegment` reports about the segment it just decoded, by slot.
 *
 * Native fills a `FloatArray(SIZE)` the caller hands in — an OUT array, like `out` for the ids —
 * and writes these slots by the `kStat*` literals that mirror the constants below.
 * `NpuNativeContractTest` holds the two copies equal by reading `qnn_asr.cpp` as text, because a
 * slot that moves on one side and not the other is not a crash: the diag line prints the entropy
 * where the no-speech probability should be and the no-speech gate reads a rung index.
 *
 * Never transcript content: probabilities, an entropy, a rung index, a code and a step count.
 */
object NpuDecodeStats {
    /** `p(<|nospeech|>)` from the raw logits at the SOT step; `-1` when the scale was unreadable. */
    const val NO_SPEECH_PROB = 0
    /** Mean log-probability of the emitted ids under the masked distribution; NaN if unreadable. */
    const val AVG_LOGPROB = 1
    /** Histogram entropy of the last [NpuDecodePolicy.ENTROPY_WINDOW] ids; NaN if fewer were emitted. */
    const val ENTROPY = 2
    /** Index into [NpuDecodePolicy.TEMPERATURES] of the rung whose output was returned. */
    const val RUNG = 3
    /** One of the `TERM_*` codes. */
    const val TERMINATOR = 4
    /** Decoder steps executed across every rung — the segment's real decode cost. */
    const val STEPS = 5
    const val SIZE = 6

    const val TERM_EOT = 0f
    const val TERM_BUDGET = 1f
    const val TERM_CAP = 2f
    const val TERM_CUT = 3f

    /** A fresh OUT array: every slot NaN, so "native never wrote this" is readable as such. */
    fun newArray(): FloatArray = FloatArray(SIZE) { Float.NaN }

    fun terminatorName(code: Float): String = when (code) {
        TERM_EOT -> "eot"
        TERM_BUDGET -> "budget"
        TERM_CAP -> "cap"
        TERM_CUT -> "cut"
        else -> "unknown"
    }
}
```

- [ ] **Step 4: Run to see them pass** (same command). Expected: PASS, class XML `failures=0`.

- [ ] **Step 5: Whole suite, then commit**

Expected count: 1,836 + 4 = **1,840**, `failures=0`.

```
feat(npu): the decode guards as policy data — whisper.cpp's thresholds, the ladder, the stats slots

NpuDecodePolicy carries ENTROPY/LOGPROB/NO_SPEECH thresholds, the 32-id window and the
0.0..1.0 temperature ladder as literals pinned to whisper_full_default_params; isNoSpeech is
whisper.cpp:7865 with NaN answering false. NpuDecodeStats names the six OUT slots and the four
terminator codes native will mirror.
```

**Battery:** (1) change `NO_SPEECH_THOLD` to `0.5f` → `guardThresholdsAreWhisperCppsDefaults` RED; revert. (2) make `isNoSpeech` use `>=` → `noSpeechNeedsBoth…` RED ("at the threshold"); revert.

---

### Task 3: The guarded native loop

**Files:**
- Modify: `app/src/main/cpp/qnn_asr.cpp` — includes `:36-50`; constants after `kLogitFloor` (`:535`); a new sampling helper after `suppressThenArgmax` (`:1899-1922`); `nativeDecodeSegment` (`:2875-3115`)
- Modify: `app/src/main/java/com/whispereverywhere/npu/QnnAsrNative.kt` (`:195-219`)
- Test: `app/src/test/java/com/whispereverywhere/npu/NpuNativeContractTest.kt` (append)

**Interfaces:**
- Consumes: `NpuDecodeStats` slot values (Task 2) as the `kStat*` literals.
- Produces (Task 4 consumes): the new JNI signature
  ```kotlin
  external fun nativeDecodeSegment(
      prompt: IntArray, suppress: IntArray, beginSuppress: IntArray, maxTokens: Int, out: IntArray,
      temperatures: FloatArray, entropyThold: Float, logprobThold: Float, noSpeechThold: Float,
      noSpeechToken: Int, stats: FloatArray,
  ): Int
  ```
  Return unchanged: ids written (`>= 0`) or `< 0` with `nativeLastError()`. `stats` is fully written on every `>= 0` return.

- [ ] **Step 1: Write the failing source pins** — append to `NpuNativeContractTest.kt` inside the class (the helpers `cpp`, `seam`, `functionBody`, `liveLines`, `liveOffsets` already exist there):

```kotlin
    // ---------------------------------------------------------------- 4.3.1 A: the decode guards

    /**
     * The six stats slots and four terminator codes are literals on BOTH sides of the seam.
     * Kotlin's are pinned in NpuDecodePolicyTest; this holds native's copy equal to them, by text,
     * because a slot that moves on one side only is not a crash — the no-speech gate would read a
     * rung index and blank segments by their temperature.
     */
    @Test
    fun theStatsSlotsAndTerminatorCodesMirrorNpuDecodeStatsExactly() {
        val expect = listOf(
            "constexpr int kStatNoSpeechProb = ${NpuDecodeStats.NO_SPEECH_PROB};",
            "constexpr int kStatAvgLogprob = ${NpuDecodeStats.AVG_LOGPROB};",
            "constexpr int kStatEntropy = ${NpuDecodeStats.ENTROPY};",
            "constexpr int kStatRung = ${NpuDecodeStats.RUNG};",
            "constexpr int kStatTerminator = ${NpuDecodeStats.TERMINATOR};",
            "constexpr int kStatSteps = ${NpuDecodeStats.STEPS};",
            "constexpr int kStatSize = ${NpuDecodeStats.SIZE};",
            "constexpr float kTermEot = ${NpuDecodeStats.TERM_EOT.toInt()}.0f;",
            "constexpr float kTermBudget = ${NpuDecodeStats.TERM_BUDGET.toInt()}.0f;",
            "constexpr float kTermCap = ${NpuDecodeStats.TERM_CAP.toInt()}.0f;",
            "constexpr float kTermCut = ${NpuDecodeStats.TERM_CUT.toInt()}.0f;",
            "constexpr int32_t kEntropyWindow = ${NpuDecodePolicy.ENTROPY_WINDOW};",
        )
        for (line in expect) {
            assertTrue("qnn_asr.cpp must declare exactly: $line", liveLines(cpp, line).size == 1)
        }
    }

    /**
     * The guards live INSIDE the loop, in this order, and the ladder wraps it. Presence is not
     * enough (the lesson every branch here has paid for): an entropy check that sits after the
     * loop instead of inside it is whisper.cpp's shape, which lets a runaway reach the budget
     * before it is judged.
     */
    @Test
    fun theDecodeLoopReadsNoSpeechAtSotThenGuardsEntropyPerStepInsideTheLadder() {
        val body = functionBody(cpp, "Java_com_whispereverywhere_npu_QnnAsrNative_nativeDecodeSegment(")
        val ladder = liveOffsets(body, "for (size_t rung = 0; rung < temperatures.size(); ++rung) {")
        val positions = liveOffsets(body, "for (uint32_t position = 0; position <= lastPosition; ++position) {")
        val nsp = liveOffsets(body, "noSpeechProb = noSpeechProbabilityLocked(")
        val entropy = liveOffsets(body, "if (count > kEntropyWindow) {")
        val cut = liveOffsets(body, "count = failedAt > kEntropyWindow ? failedAt - kEntropyWindow : 0;")
        assertTrue("one ladder loop; found ${ladder.size}", ladder.size == 1)
        assertTrue("one position loop; found ${positions.size}", positions.size == 1)
        assertTrue("one no-speech read; found ${nsp.size}", nsp.size == 1)
        assertTrue("one in-loop entropy check; found ${entropy.size}", entropy.size == 1)
        assertTrue("one last-rung cut; found ${cut.size}", cut.size == 1)
        assertTrue("the position loop is inside the ladder", ladder.first() < positions.first())
        assertTrue("no-speech is read inside the position loop (SOT step)", positions.first() < nsp.first())
        assertTrue("the entropy check is inside the position loop", positions.first() < entropy.first())
        assertTrue("the cut comes after the ladder has run", entropy.first() < cut.first())
    }

    /** Sampling never selects a suppressed id: mask first, then draw — the same shape as the argmax. */
    @Test
    fun temperatureSamplingMasksBeforeItDraws() {
        val body = functionBody(cpp, "int32_t suppressThenSample(")
        val mask = liveOffsets(body, "logits[id] = kLogitFloor;")
        val draw = liveOffsets(body, "std::uniform_real_distribution<double>")
        assertTrue("the mask writes come first; mask=$mask draw=$draw", mask.isNotEmpty() && draw.size == 1 && mask.last() < draw.first())
        assertTrue("floor entries carry no mass", liveLines(body, "if (logits[i] == kLogitFloor) continue;").size >= 1)
    }

    /** The Kotlin declaration names every new argument, in native's order. */
    @Test
    fun theDecodeDeclarationCarriesTheGuardArgumentsInOrder() {
        val decl = seam.substring(seam.indexOf("external fun nativeDecodeSegment("))
            .substringBefore("): Int")
        val order = listOf("prompt: IntArray", "suppress: IntArray", "beginSuppress: IntArray",
            "maxTokens: Int", "out: IntArray", "temperatures: FloatArray", "entropyThold: Float",
            "logprobThold: Float", "noSpeechThold: Float", "noSpeechToken: Int", "stats: FloatArray")
        var last = -1
        for (p in order) {
            val at = decl.indexOf(p)
            assertTrue("missing or out of order: $p", at > last)
            last = at
        }
    }
```
Add `import com.whispereverywhere.npu.NpuDecodeStats` / `NpuDecodePolicy` if the test file is in another package (it is in `com.whispereverywhere.npu`, so no import needed).

- [ ] **Step 2: Run to see them fail**

```powershell
.\gradlew.bat :app:testDebugUnitTest --no-daemon --tests "com.whispereverywhere.npu.NpuNativeContractTest"
```
Expected: the four new tests FAIL (`qnn_asr.cpp must declare exactly: …`, anchor missing, etc.); every pre-existing test in the class still passes.

- [ ] **Step 3: Native — includes and constants.** In `qnn_asr.cpp` add after `#include <map>` (`:47`):

```cpp
#include <random>
```
After `constexpr uint16_t kLogitFloor = 0;` (`:535`) add:

```cpp
// ---------------------------------------------------------------- 4.3.1 A: the decode guards
//
// The six OUT slots nativeDecodeSegment fills and the four terminator codes, as literals that
// MIRROR NpuDecodeStats.kt. NpuNativeContractTest holds the two copies equal by source text; a
// slot that moves here and not there is not a crash, it is the no-speech gate reading a rung index.
constexpr int kStatNoSpeechProb = 0;
constexpr int kStatAvgLogprob = 1;
constexpr int kStatEntropy = 2;
constexpr int kStatRung = 3;
constexpr int kStatTerminator = 4;
constexpr int kStatSteps = 5;
constexpr int kStatSize = 6;
constexpr float kTermEot = 0.0f;
constexpr float kTermBudget = 1.0f;
constexpr float kTermCap = 2.0f;
constexpr float kTermCut = 3.0f;
/// whisper_sequence_score's n: the repetition entropy is over the last 32 emitted ids.
constexpr int32_t kEntropyWindow = 32;
/// The stats value for "no scale was readable, so no probability was computed".
constexpr float kStatUnreadable = -1.0f;
```

- [ ] **Step 4: Native — the helpers.** After `argmaxInRange` (`:1929-1938`) add:

```cpp
// ---------------------------------------------------------------- 4.3.1 A: probabilities and sampling

/// The logits tensor's per-tensor scale, or 0 when it cannot be read. Under a per-tensor affine
/// `v = scale * (q + offset)` the offset cancels in every softmax, so the scale alone turns raw
/// ufixed16 codes into log-odds. 0 means "no probability gate this session" — the entropy guard
/// needs no scale and runs regardless; the probability-based gates report kStatUnreadable.
float logitsScaleLocked() {
    const Qnn_Tensor_t &t = g.dec.outputs[g.decLogitsIdx];
    if (tensorDataType(t) != QNN_DATATYPE_UFIXED_POINT_16) return 0.0f;
    const Qnn_QuantizeParams_t *q = tensorQuantParams(t);
    if (!q || q->encodingDefinition != QNN_DEFINITION_DEFINED ||
        q->quantizationEncoding != QNN_QUANTIZATION_ENCODING_SCALE_OFFSET) return 0.0f;
    const float s = q->scaleOffsetEncoding.scale;
    return (s > 0.0f && std::isfinite(s)) ? s : 0.0f;
}

/// log(sum(exp(v_i - max))) + max over the vocabulary, in `v = scale * q` units. `excludeFloor`
/// drops entries at kLogitFloor - the mask's -infinity, exactly whisper.cpp's filtered distribution
/// - and is false for the RAW read at the SOT step, which whisper.cpp takes "before any logit
/// filtering" (whisper.cpp:7432). Returns false when nothing is live.
bool logSumExpLocked(const uint16_t *logits, uint32_t vocab, float scale, bool excludeFloor,
                     double *outLogZ) {
    float mx = 0.0f;
    bool any = false;
    for (uint32_t i = 0; i < vocab; ++i) {
        if (excludeFloor && logits[i] == kLogitFloor) continue;
        const float v = scale * static_cast<float>(logits[i]);
        if (!any || v > mx) { mx = v; any = true; }
    }
    if (!any) return false;
    double sum = 0.0;
    for (uint32_t i = 0; i < vocab; ++i) {
        if (excludeFloor && logits[i] == kLogitFloor) continue;
        sum += std::exp(static_cast<double>(scale * static_cast<float>(logits[i]) - mx));
    }
    *outLogZ = static_cast<double>(mx) + std::log(sum);
    return true;
}

/// p(<|nospeech|>) from the RAW logits of the step that predicts the token after SOT.
float noSpeechProbabilityLocked(const uint16_t *logits, uint32_t vocab, float scale,
                                int32_t noSpeechToken) {
    double logZ = 0.0;
    if (!logSumExpLocked(logits, vocab, scale, /*excludeFloor=*/false, &logZ)) return kStatUnreadable;
    const double v = static_cast<double>(scale * static_cast<float>(logits[noSpeechToken]));
    return static_cast<float>(std::exp(v - logZ));
}

/// log p(id) under the MASKED distribution (floor entries excluded), for avg_logprob.
double maskedLogprobLocked(const uint16_t *logits, uint32_t vocab, float scale, int32_t id) {
    double logZ = 0.0;
    if (!logSumExpLocked(logits, vocab, scale, /*excludeFloor=*/true, &logZ)) return 0.0;
    return static_cast<double>(scale * static_cast<float>(logits[id])) - logZ;
}

/// MASK, THEN DRAW - the sampling twin of suppressThenArgmax, for the ladder's T > 0 rungs. The
/// mask writes are repeated here rather than factored out so that both selectors keep the
/// "mask first, in one function" shape the C2 comment above insists on. Floor entries carry no
/// mass, so a suppressed id can never be drawn. Returns -1 when nothing is live.
int32_t suppressThenSample(uint16_t *logits, uint32_t vocab,
                           const std::vector<int32_t> &suppress,
                           const std::vector<int32_t> &beginSuppress,
                           bool applyBegin, float scale, float temperature, std::mt19937 &rng) {
    for (int32_t id : suppress) {
        logits[id] = kLogitFloor;
    }
    if (applyBegin) {
        for (int32_t id : beginSuppress) {
            logits[id] = kLogitFloor;
        }
    }
    float mx = 0.0f;
    bool any = false;
    for (uint32_t i = 0; i < vocab; ++i) {
        if (logits[i] == kLogitFloor) continue;
        const float v = scale * static_cast<float>(logits[i]);
        if (!any || v > mx) { mx = v; any = true; }
    }
    if (!any) return -1;
    double sum = 0.0;
    for (uint32_t i = 0; i < vocab; ++i) {
        if (logits[i] == kLogitFloor) continue;
        sum += std::exp(static_cast<double>((scale * static_cast<float>(logits[i]) - mx) / temperature));
    }
    std::uniform_real_distribution<double> uni(0.0, 1.0);
    double target = uni(rng) * sum;
    int32_t last = -1;
    for (uint32_t i = 0; i < vocab; ++i) {
        if (logits[i] == kLogitFloor) continue;
        target -= std::exp(static_cast<double>((scale * static_cast<float>(logits[i]) - mx) / temperature));
        last = static_cast<int32_t>(i);
        if (target <= 0.0) return last;
    }
    return last;  // rounding fell off the end: the last live id
}

/// whisper_sequence_score's entropy (whisper.cpp:6885-6905): the histogram of the last
/// kEntropyWindow ids, -sum(p ln p). Id-based - no probabilities involved.
double trailingEntropy(const std::vector<int32_t> &ids, int32_t count) {
    const int32_t n = count < kEntropyWindow ? count : kEntropyWindow;
    if (n <= 0) return 0.0;
    std::map<int32_t, int> hist;
    for (int32_t i = count - n; i < count; ++i) hist[ids[static_cast<size_t>(i)]]++;
    double h = 0.0;
    for (const auto &kv : hist) {
        const double p = kv.second / static_cast<double>(n);
        h -= p * std::log(p);
    }
    return h;
}
```

- [ ] **Step 5: Native — the JNI entry.** Replace the signature and the body of `Java_com_whispereverywhere_npu_QnnAsrNative_nativeDecodeSegment` from its `extern "C"` line (`:2874`) to the closing `}` before `/// ONE decode step at position 0` (`:3117`) with the version below. The prologue (session/encoded/prompt/suppress checks, `lastPosition`, `maxPromptLen`, `out` bounds check) is kept verbatim; only the marked regions are new. Keep every existing `LOGDIAG` in the loop body exactly as it is (they are pinned elsewhere) — they move inside the ladder unchanged.

```cpp
extern "C" JNIEXPORT jint JNICALL
Java_com_whispereverywhere_npu_QnnAsrNative_nativeDecodeSegment(
        JNIEnv *env, jobject /* this */, jintArray jPrompt, jintArray jSuppress,
        jintArray jBeginSuppress, jint maxTokens, jintArray jOut,
        jfloatArray jTemperatures, jfloat entropyThold, jfloat logprobThold,
        jfloat noSpeechThold, jint noSpeechToken, jfloatArray jStats) {
    std::lock_guard<std::mutex> lock(g.mu);
    // ... [KEEP the existing checks verbatim: initialised/decBound, null prompt/out, g.encoded,
    //      prompt/suppress/beginSuppress vectors, lastPosition/maxPromptLen, checkTokenIdsLocked
    //      x3, maxTokens > 0, out bounds] ...

    // 4.3.1 A: THE GUARD ARGUMENTS, validated once, like the id lists above them.
    std::vector<float> temperatures;
    if (jTemperatures) {
        const jsize n = env->GetArrayLength(jTemperatures);
        if (n > 0) {
            temperatures.resize(static_cast<size_t>(n));
            env->GetFloatArrayRegion(jTemperatures, 0, n, temperatures.data());
        }
    }
    if (temperatures.empty() || temperatures[0] != 0.0f) {
        failure("decode: temperatures must start with 0 (the greedy rung); got " +
                std::to_string(temperatures.size()) + " entries");
        return -1;
    }
    for (size_t i = 1; i < temperatures.size(); ++i) {
        if (!(temperatures[i] > temperatures[i - 1]) || !std::isfinite(temperatures[i])) {
            failure("decode: temperatures must be finite and ascending at index " + std::to_string(i));
            return -1;
        }
    }
    if (!std::isfinite(entropyThold) || !std::isfinite(logprobThold) || !std::isfinite(noSpeechThold)) {
        failure("decode: a guard threshold is not finite");
        return -1;
    }
    if (noSpeechToken < 0 || noSpeechToken >= static_cast<int32_t>(g.vocab)) {
        failure("decode: noSpeechToken " + std::to_string(noSpeechToken) + " is outside the vocabulary");
        return -1;
    }
    if (!jStats || env->GetArrayLength(jStats) < kStatSize) {
        failure("decode: stats must have room for " + std::to_string(kStatSize) + " values");
        return -1;
    }
    const float scale = logitsScaleLocked();   // 0 => no probability gates this segment

    uint16_t *logits = static_cast<uint16_t *>(g.dec.outBufs[g.decLogitsIdx].p);
    const uint32_t promptLen = static_cast<uint32_t>(prompt.size());
    std::vector<int32_t> out(static_cast<size_t>(maxTokens), 0);
    int32_t count = 0;
    bool hitEot = false;
    const auto t0 = Clock::now();

    // THE PROMPT ECHO. [KEEP the existing LOGDIAG("npu-debug: prompt ids=...") block verbatim.]

    int32_t firstGenerated = -1;
    uint32_t lastPositionRun = 0;
    uint32_t stepsRun = 0;           // across every rung: the segment's real decode cost

    // 4.3.1 A: THE LADDER. Each rung is a full re-decode against the SAME encode - self-KV zeroed,
    // prompt re-fed, cross-KV untouched - at temperatures[rung]. Rung 0 is the greedy loop this
    // function has always been; it is byte-identical in what it emits when no gate trips.
    float noSpeechProb = kStatUnreadable;
    double avgLogprob = 0.0;
    double entropyLast = 0.0;
    size_t rungUsed = 0;
    float terminator = kTermEot;
    for (size_t rung = 0; rung < temperatures.size(); ++rung) {
        const float temperature = temperatures[rung];
        std::mt19937 rng(0x5EEDu ^ static_cast<uint32_t>(rung));   // deterministic per rung
        zeroSelfKvLocked();
        count = 0;
        hitEot = false;
        double sumLogprob = 0.0;
        bool failedEntropy = false;
        int32_t failedAt = 0;
        int32_t next = prompt[0];
        entropyLast = 0.0;
        rungUsed = rung;

        for (uint32_t position = 0; position <= lastPosition; ++position) {
            const int32_t tokenIn = (position < promptLen) ? prompt[position] : next;
            const int inSetForStep = g.selfInSet;
            err = decodeStepLocked(tokenIn, position);
            if (!err.empty()) {
                failure("decode: " + err);
                return -2;
            }
            lastPositionRun = position;
            ++stepsRun;

            // [KEEP the existing Q10a-D2/D3 selfkv LOGDIAG block verbatim here.]

            // 4.3.1 A: p(<|nospeech|>) is read ONCE, at the SOT step of the first rung, from the RAW
            // logits - before this step's argmax is discarded with the rest of the prompt walk.
            if (rung == 0 && position == 0 && scale > 0.0f) {
                noSpeechProb = noSpeechProbabilityLocked(logits, g.vocab, scale, noSpeechToken);
            }

            const bool trace = g.diag && position <= promptLen;
            LogitsHealth h;
            if (trace) h = scanLogitsRaw(logits, g.vocab);
            char inName[24], rawName[24], maskedName[24];

            if (position + 1 < promptLen) {
                // [KEEP the existing prefill trace LOGDIAG verbatim.]
                bindSelfKvLocked(1 - g.selfInSet);
                continue;
            }

            const bool applyBegin = (position == promptLen - 1);
            const int32_t tok = (temperature == 0.0f)
                    ? suppressThenArgmax(logits, g.vocab, suppress, beginSuppress, applyBegin)
                    : suppressThenSample(logits, g.vocab, suppress, beginSuppress, applyBegin,
                                         scale, temperature, rng);
            // [KEEP the existing per-step trace LOGDIAG verbatim; it prints `tok` as before.]
            if (tok < 0) {
                failure("decode: every logit is at the bottom rail at position " +
                        std::to_string(position) + "; the graph produced no token");
                return -3;
            }
            if (scale > 0.0f) sumLogprob += maskedLogprobLocked(logits, g.vocab, scale, tok);
            if (firstGenerated < 0) firstGenerated = tok;
            if (tok == kEotToken) {
                hitEot = true;
                break;
            }
            out[static_cast<size_t>(count)] = tok;
            ++count;
            // 4.3.1 A: THE REPETITION GUARD, IN-LOOP. whisper.cpp scores the finished sequence
            // (:7807) because its decoders run batched; this loop is step-wise, so the same
            // 32-id histogram entropy is checked at every step past the window and a runaway dies
            // at ~33-40 tokens instead of at the 196-token budget.
            if (count > kEntropyWindow) {
                entropyLast = trailingEntropy(out, count);
                if (entropyLast < entropyThold) {
                    failedEntropy = true;
                    failedAt = count;
                    break;
                }
            }
            if (count >= maxTokens) break;
            next = tok;
            bindSelfKvLocked(1 - g.selfInSet);
        }

        avgLogprob = (scale > 0.0f && count > 0) ? sumLogprob / count : 0.0;
        // whisper.cpp:7835 - a low-confidence rung falls back only when the model does NOT think
        // the segment is silent; a silent segment is judged by Kotlin, not re-decoded hotter.
        const bool lowConfidence = scale > 0.0f && count > 0 &&
                                   avgLogprob < static_cast<double>(logprobThold) &&
                                   noSpeechProb < noSpeechThold;
        const bool lastRung = (rung + 1 == temperatures.size());
        if (!failedEntropy && !lowConfidence) break;          // this rung's output stands
        if (lastRung) {
            if (failedEntropy) {
                // THE ONE DEVIATION FROM THE REFERENCE (spec A, step 3): the reference emits the
                // last rung whatever it is, and that is exactly report 1. Keep the prefix before
                // the window that tripped; drop the loop.
                count = failedAt > kEntropyWindow ? failedAt - kEntropyWindow : 0;
                terminator = kTermCut;
            }
            break;                                            // a low-confidence last rung stands
        }
        // otherwise: next rung
    }

    if (terminator != kTermCut) {
        terminator = hitEot ? kTermEot : (count >= maxTokens ? kTermBudget : kTermCap);
    }
    if (count > 0) env->SetIntArrayRegion(jOut, 0, count, reinterpret_cast<const jint *>(out.data()));

    float stats[kStatSize];
    stats[kStatNoSpeechProb] = noSpeechProb;
    stats[kStatAvgLogprob] = (scale > 0.0f && count > 0) ? static_cast<float>(avgLogprob) : NAN;
    stats[kStatEntropy] = (count > kEntropyWindow) ? static_cast<float>(entropyLast) : NAN;
    stats[kStatRung] = static_cast<float>(rungUsed);
    stats[kStatTerminator] = terminator;
    stats[kStatSteps] = static_cast<float>(stepsRun);
    env->SetFloatArrayRegion(jStats, 0, kStatSize, stats);

    const double ms = msSince(t0);
    const char *termName = terminator == kTermCut ? "cut" : (hitEot ? "eot" : (count >= maxTokens ? "count" : "cap"));
    LOGI("decode: %d tokens in %.1f ms (%.2f ms/token), terminated by %s (vote: %s) nsp=%.2f lp=%.2f ent=%.2f rung=%zu steps=%u",
         count, ms, count > 0 ? ms / count : 0.0,
         terminator == kTermCut ? "the repetition cut" :
         (hitEot ? "EOT" : (count >= maxTokens ? "the token budget" : "the position cap")),
         g.voteNote.c_str(), stats[kStatNoSpeechProb], stats[kStatAvgLogprob], stats[kStatEntropy],
         rungUsed, stepsRun);
    if (g.diag) {
        char firstName[24];
        LOGDIAG("npu-debug: result count=%d first=%s terminator=%s steps=%u posFirst=0 posLast=%u",
                count, diagToken(firstGenerated, firstName, sizeof(firstName)), termName,
                stepsRun, lastPositionRun);
    }
    g.lastError.clear();
    return count;
}
```
Notes for the implementer: `err` is the `std::string` already declared by the prologue's `checkTokenIdsLocked` calls — reuse it. `trailingEntropy` takes the `out` vector and the live `count` (it reads `out[count-32, count)`). `NAN` comes from `<cmath>`, already included. Do not touch `suppressThenArgmax`.

- [ ] **Step 6: Kotlin declaration.** In `QnnAsrNative.kt` replace the `external fun nativeDecodeSegment(...)` block (`:210-219`) with:

```kotlin
    external fun nativeDecodeSegment(
        prompt: IntArray,
        suppress: IntArray,
        beginSuppress: IntArray,
        maxTokens: Int,
        out: IntArray,
        temperatures: FloatArray,
        entropyThold: Float,
        logprobThold: Float,
        noSpeechThold: Float,
        noSpeechToken: Int,
        stats: FloatArray,
    ): Int
```
and extend its KDoc (`:195-209`) with:

```kotlin
     * @param temperatures `NpuDecodePolicy.TEMPERATURES` — the fallback ladder; `[0]` must be 0.
     * @param entropyThold `NpuDecodePolicy.ENTROPY_THOLD`: a rung whose last 32 ids have less
     *        histogram entropy than this is a repetition loop and is abandoned at that step.
     * @param logprobThold `NpuDecodePolicy.LOGPROB_THOLD`: a rung whose mean log-prob is below
     *        this (and whose no-speech probability is below [noSpeechThold]) is re-run hotter.
     * @param noSpeechThold `NpuDecodePolicy.NO_SPEECH_THOLD`.
     * @param noSpeechToken `spec.tokens.noSpeech` — this family's `<|nospeech|>` id; native reads
     *        its raw probability at the SOT step and never emits it.
     * @param stats OUT, `NpuDecodeStats.newArray()`; fully written on every `>= 0` return, by the
     *        [NpuDecodeStats] slots. `-1` in `NO_SPEECH_PROB` means the logits' scale was unreadable
     *        and no probability gate ran (the entropy guard still did).
```

- [ ] **Step 7: Compile native**

```powershell
.\gradlew.bat :app:assembleDebug --no-daemon
```
Expected: `BUILD SUCCESSFUL`. A `jfloatArray`/`GetFloatArrayRegion` or `std::mt19937` error means an include or a type slipped; fix and re-run until green. (Task 4 changes the Kotlin caller; until then `NpuWhisperBackend` will not compile against the new signature — **so in this task also apply the minimal caller change**: in `NpuWhisperBackend.kt:626-632` pass `NpuDecodePolicy.TEMPERATURES, NpuDecodePolicy.ENTROPY_THOLD, NpuDecodePolicy.LOGPROB_THOLD, NpuDecodePolicy.NO_SPEECH_THOLD, spec.tokens.noSpeech, NpuDecodeStats.newArray()` as the six trailing arguments and nothing else. Task 4 does the real wiring.)

- [ ] **Step 8: Run the contract test** (same command as Step 2). Expected: all PASS.

- [ ] **Step 9: Whole suite, then commit**

Expected: 1,840 + 4 = **1,844**, `failures=0`; `assembleDebug` green.

```
feat(npu): the guarded decode loop — no-speech at SOT, per-step entropy, the temperature ladder

nativeDecodeSegment takes whisper.cpp's thresholds and ladder as arguments and reports six
stats. Rung 0 is the old greedy loop byte-for-byte; a rung whose last-32-id entropy drops
below 2.4 dies at that step and the next temperature re-decodes against the same encode; a
last-rung loop is cut back to the prefix. p(<|nospeech|>) is read raw at the SOT step and
per-token log-probs come from the masked distribution. Scale unreadable => entropy guard only.
```

**Battery:** (1) delete the `if (count > kEntropyWindow) {` block → `theDecodeLoopReads…` RED; revert. (2) move the `noSpeechProb = …` read above the position loop → RED ("no-speech is read inside the position loop"); revert. (3) change `kStatEntropy` to `3` → `theStatsSlots…` RED; revert. (4) swap the mask writes below the draw in `suppressThenSample` → `temperatureSamplingMasksBeforeItDraws` RED; revert. Each row: `assembleDebug` is NOT needed (source pins), only the test class.

---

### Task 4: Kotlin wiring — pass the policy, read the stats, blank on no-speech, the grown diag line

**Files:**
- Modify: `app/src/main/java/com/whispereverywhere/transcription/NpuWhisperBackend.kt` (`:624-663`)
- Modify: `app/src/main/java/com/whispereverywhere/npu/NpuDiag.kt` (`:27-45`)
- Test: `app/src/test/java/com/whispereverywhere/npu/NpuDiagTest.kt` (`:38-48`, `:60-80`), `app/src/test/java/com/whispereverywhere/transcription/NpuBackendWiringTest.kt` (append)

**Interfaces:**
- Consumes: Task 2 constants, Task 3 signature.
- Produces: `NpuDiag.line(encodeMs: Long, decodeMs: Long, tokens: Int, langNote: String, noSpeechProb: Float, avgLogprob: Float, entropy: Float, rung: Int, terminator: String): String` → `"npu: encode=405 decode=168 tokens=37 lang=en nsp=0.02 lp=-0.31 ent=3.10 rung=0 term=eot"`.

- [ ] **Step 1: Write the failing tests.** In `NpuDiagTest.kt` replace `lineMatchesTheGreppableFormatExactly` (`:38-49`) with:

```kotlin
    @Test
    fun lineMatchesTheGreppableFormatExactly() {
        assertEquals(
            "npu: encode=405 decode=168 tokens=37 lang=en nsp=0.02 lp=-0.31 ent=3.10 rung=0 term=eot",
            NpuDiag.line(
                encodeMs = 405L, decodeMs = 168L, tokens = 37, langNote = "en",
                noSpeechProb = 0.02f, avgLogprob = -0.31f, entropy = 3.1f, rung = 0, terminator = "eot",
            ),
        )
        assertEquals(
            "zero tokens is a real reading — EOT first, i.e. silence — and must render as a " +
                "normal line rather than being suppressed as \"nothing happened\"",
            "npu: encode=402 decode=5 tokens=0 lang=en nsp=0.81 lp=NaN ent=NaN rung=0 term=eot",
            NpuDiag.line(402L, 5L, 0, "en", 0.81f, Float.NaN, Float.NaN, 0, "eot"),
        )
        assertEquals(
            "the unreadable-scale sentinel prints as -1.00, never as a probability",
            "npu: encode=405 decode=168 tokens=37 lang=en nsp=-1.00 lp=NaN ent=3.10 rung=2 term=cut",
            NpuDiag.line(405L, 168L, 37, "en", -1f, Float.NaN, 3.1f, 2, "cut"),
        )
    }
```
and update the four language-note assertions (`:60-80`) to the nine-argument form, each expecting the suffix ` nsp=0.02 lp=-0.31 ent=3.10 rung=0 term=eot`, e.g.:

```kotlin
        assertEquals(
            "npu: encode=405 decode=168 tokens=37 lang=auto->fr(detected) nsp=0.02 lp=-0.31 ent=3.10 rung=0 term=eot",
            NpuDiag.line(405L, 168L, 37, "auto->fr(detected)", 0.02f, -0.31f, 3.1f, 0, "eot"),
        )
```
Append to `NpuBackendWiringTest.kt` (it already has `read`, `count`, `liveOffsets`, `memberBody`; the backend source is read as `read("src/main/java/com/whispereverywhere/transcription/NpuWhisperBackend.kt")` — follow the file's existing `backend`/`service` lazy vals; if none names the backend, add `private val backend: String by lazy { read("src/main/java/com/whispereverywhere/transcription/NpuWhisperBackend.kt") }`):

```kotlin
    /**
     * 4.3.1 A: the no-speech decision is Kotlin's, taken from the stats native returned, and a
     * blank is what it produces — which is the engine's existing EmptyExpected path. The order
     * matters: the decision is read BEFORE the ids are detokenised, and the diag line carries the
     * numbers whatever the decision was.
     */
    @Test
    fun theNpuTierBlanksANoSpeechSegmentFromTheReturnedStatsBeforeDetokenising() {
        val body = memberBody(backend, "    override fun transcribe(ctx: Long, samples: FloatArray, lang: String?, useVad: Boolean): String {")
        val stats = liveOffsets(body, "val stats = NpuDecodeStats.newArray()")
        val call = liveOffsets(body, "QnnAsrNative.nativeDecodeSegment(")
        val gate = liveOffsets(body, "NpuDecodePolicy.isNoSpeech(stats[NpuDecodeStats.NO_SPEECH_PROB], stats[NpuDecodeStats.AVG_LOGPROB])")
        val decode = liveOffsets(body, "bpe.decode(out.copyOf(written))")
        val line = liveOffsets(body, "NpuDiag.line(")
        assertEquals("one stats array", 1, stats.size)
        assertEquals("one decode call", 1, call.size)
        assertEquals("one no-speech gate", 1, gate.size)
        assertEquals("one detokenise", 1, decode.size)
        assertEquals("one diag line", 1, line.size)
        assertTrue("stats is allocated before the call", stats.first() < call.first())
        assertTrue("the gate reads the stats after the call", call.first() < gate.first())
        assertTrue("the gate decides before the ids are detokenised", gate.first() < decode.first())
        assertTrue("the ladder arguments are the policy's, not literals",
            body.contains("NpuDecodePolicy.TEMPERATURES,") && body.contains("NpuDecodePolicy.ENTROPY_THOLD,") &&
                body.contains("NpuDecodePolicy.LOGPROB_THOLD,") && body.contains("NpuDecodePolicy.NO_SPEECH_THOLD,") &&
                body.contains("spec.tokens.noSpeech,"))
        assertTrue("the diag line carries the five stats fields",
            body.contains("NpuDecodeStats.terminatorName(stats[NpuDecodeStats.TERMINATOR])"))
    }
```

- [ ] **Step 2: Run to see them fail**

```powershell
.\gradlew.bat :app:testDebugUnitTest --no-daemon --tests "com.whispereverywhere.npu.NpuDiagTest" --tests "com.whispereverywhere.transcription.NpuBackendWiringTest"
```
Expected: compile failure on `NpuDiag.line` arity, and the wiring test RED.

- [ ] **Step 3: Implement `NpuDiag.line`** (replace `:44-45`; extend the KDoc's `@param` list):

```kotlin
    fun line(
        encodeMs: Long,
        decodeMs: Long,
        tokens: Int,
        langNote: String,
        noSpeechProb: Float,
        avgLogprob: Float,
        entropy: Float,
        rung: Int,
        terminator: String,
    ): String =
        "npu: encode=$encodeMs decode=$decodeMs tokens=$tokens lang=$langNote " +
            "nsp=${f2(noSpeechProb)} lp=${f2(avgLogprob)} ent=${f2(entropy)} rung=$rung term=$terminator"

    /** Two decimals, US locale, `NaN` printed as the literal `NaN` — never a locale comma. */
    private fun f2(v: Float): String =
        if (v.isNaN()) "NaN" else String.format(java.util.Locale.US, "%.2f", v)
```
KDoc additions: `@param noSpeechProb` (raw `p(<|nospeech|>)` at SOT, `-1` unreadable), `@param avgLogprob` (masked mean, NaN unreadable/empty), `@param entropy` (last-32 histogram entropy, NaN below the window), `@param rung` (ladder index used), `@param terminator` (`eot|budget|cap|cut`). Note in the KDoc that the `npu: encode=` prefix is still one contiguous literal.

- [ ] **Step 4: Implement the backend wiring.** In `NpuWhisperBackend.kt` replace `:624-632` (prompt/out/call) and the text/diag lines (`:648`, `:661-663`) so the block reads:

```kotlin
            val prompt = NpuDecodePolicy.promptTokens(spec.tokens, resolution.token)
            val out = IntArray(NpuDecodePolicy.maxTokensFor(spec.tokens, prompt.size))
            // 4.3.1 A: the guards travel as data, like the suppress lists; the six stats come back
            // in this OUT array and the no-speech decision is taken HERE, from them.
            val stats = NpuDecodeStats.newArray()
            val written = QnnAsrNative.nativeDecodeSegment(
                prompt,
                NpuDecodePolicy.suppressList(spec.tokens),
                NpuDecodePolicy.beginSuppressList(spec.tokens),
                out.size,
                out,
                NpuDecodePolicy.TEMPERATURES,
                NpuDecodePolicy.ENTROPY_THOLD,
                NpuDecodePolicy.LOGPROB_THOLD,
                NpuDecodePolicy.NO_SPEECH_THOLD,
                spec.tokens.noSpeech,
                stats,
            )
            if (written < 0) {
                return@serialized fallBackAndRun("decode", QnnAsrNative.nativeLastError(), samples, lang, useVad)
            }
            val decodeMs = SystemClock.elapsedRealtime() - decodeStart

            // whisper.cpp:7865 — the model said "silence" AND was unsure of its words: type nothing.
            // A blank reaches LocalWhisperEngine's existing blank branch and resolves EmptyExpected,
            // the same outcome the CPU tier's VAD-empty takes. Decided BEFORE detokenising so a
            // hallucinated "Thank you." never exists as a String at all.
            val noSpeech = NpuDecodePolicy.isNoSpeech(stats[NpuDecodeStats.NO_SPEECH_PROB], stats[NpuDecodeStats.AVG_LOGPROB])

            // [KEEP the existing comment block about the slice and IllegalArgumentException.]
            val text = if (noSpeech) "" else bpe.decode(out.copyOf(written))

            // [KEEP the existing `.reportable` comment and assignment.]
            lastReportedLanguage = resolution.reportable

            // ONE line per segment. `tokens` is native's count even when the gate blanked the text,
            // so the line still says what the decoder produced; `nsp`/`lp` say why it was dropped.
            android.util.Log.i(
                NpuDiag.TAG,
                NpuDiag.line(
                    encodeMs, decodeMs, written, resolution.note,
                    stats[NpuDecodeStats.NO_SPEECH_PROB], stats[NpuDecodeStats.AVG_LOGPROB],
                    stats[NpuDecodeStats.ENTROPY], stats[NpuDecodeStats.RUNG].toInt(),
                    NpuDecodeStats.terminatorName(stats[NpuDecodeStats.TERMINATOR]),
                ),
            )
            text
```
Add `import com.whispereverywhere.npu.NpuDecodeStats` if the file imports `npu.*` symbols individually.

- [ ] **Step 5: Run to see them pass** (Step 2 command). Expected: PASS. Then `grep -rn "npu: encode=" app/src/test` — any other pin on the literal prefix must still pass (the prefix is unchanged).

- [ ] **Step 6: Compile native + Kotlin** — `.\gradlew.bat :app:assembleDebug --no-daemon` → `BUILD SUCCESSFUL`.

- [ ] **Step 7: Whole suite, then commit**

Expected: 1,844 + 1 = **1,845**, `failures=0`.

```
feat(npu): Kotlin decides no-speech from the returned stats; the npu: line carries the five gate fields

The backend hands the policy in, reads the six stats back, blanks a segment that whisper.cpp:7865
would blank (before detokenising), and logs nsp/lp/ent/rung/term beside encode/decode/tokens.
```

**Battery:** (1) move the `isNoSpeech` line below `bpe.decode` → wiring pin RED ("decides before detokenised"); revert. (2) replace `NpuDecodePolicy.ENTROPY_THOLD,` with `2.4f,` in the call → RED ("not literals"); revert. (3) drop `term=` from `NpuDiag.line` → `lineMatchesTheGreppableFormatExactly` RED; revert.

---

### Task 5: `BubbleHidePolicy` — the pure hide decision

**Files:**
- Create: `app/src/main/java/com/whispereverywhere/service/BubbleHidePolicy.kt`
- Create: `app/src/test/java/com/whispereverywhere/service/BubbleHidePolicyTest.kt`

**Interfaces:**
- Produces (Task 6 consumes):
  ```kotlin
  object BubbleHidePolicy {
      enum class Decision { IGNORE, DEFER, HIDE }
      fun decide(speaking: Boolean, alwaysOn: Boolean, visible: Boolean): Decision
      fun replay(contextIsTextField: Boolean, alwaysOn: Boolean): Boolean
  }
  ```

- [ ] **Step 1: Write the failing test** — `BubbleHidePolicyTest.kt`:

```kotlin
package com.whispereverywhere.service

import com.whispereverywhere.service.BubbleHidePolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one rule every hide of the floating bubble goes through (4.3.1 B). Pure, so the order of
 * the three guards is a truth table here and not a reading of a 3,500-line service.
 */
class BubbleHidePolicyTest {

    @Test fun always_on_is_never_hidden_whatever_else_is_true() {
        for (speaking in listOf(true, false)) for (visible in listOf(true, false)) {
            assertEquals(Decision.IGNORE, BubbleHidePolicy.decide(speaking, alwaysOn = true, visible = visible))
        }
    }

    @Test fun a_hidden_bubble_has_nothing_to_hide_and_nothing_to_park() {
        // Not DEFER: parking a reason while nothing is visible would replay a hide the user never saw.
        assertEquals(Decision.IGNORE, BubbleHidePolicy.decide(speaking = true, alwaysOn = false, visible = false))
        assertEquals(Decision.IGNORE, BubbleHidePolicy.decide(speaking = false, alwaysOn = false, visible = false))
    }

    @Test fun a_read_in_progress_defers_the_hide_instead_of_taking_the_bubble_away() {
        // The owner's bug: any window event mid-read hid the pill and the audio kept playing.
        assertEquals(Decision.DEFER, BubbleHidePolicy.decide(speaking = true, alwaysOn = false, visible = true))
    }

    @Test fun otherwise_the_hide_proceeds() {
        assertEquals(Decision.HIDE, BubbleHidePolicy.decide(speaking = false, alwaysOn = false, visible = true))
    }

    @Test fun a_parked_hide_replays_only_where_the_bubble_would_have_hidden_anyway() {
        // A clipboard-summoned bubble (context NONE) still leaves after the read, as the summon
        // comment promises; a bubble on a focused field stays; always-on never hides.
        assertTrue(BubbleHidePolicy.replay(contextIsTextField = false, alwaysOn = false))
        assertFalse(BubbleHidePolicy.replay(contextIsTextField = true, alwaysOn = false))
        assertFalse(BubbleHidePolicy.replay(contextIsTextField = false, alwaysOn = true))
        assertFalse(BubbleHidePolicy.replay(contextIsTextField = true, alwaysOn = true))
    }
}
```

- [ ] **Step 2: Run to see it fail** — `--tests "com.whispereverywhere.service.BubbleHidePolicyTest"`. Expected: compile failure (`Unresolved reference: BubbleHidePolicy`).

- [ ] **Step 3: Implement** — `BubbleHidePolicy.kt`:

```kotlin
package com.whispereverywhere.service

/**
 * Whether a request to hide the floating bubble may proceed right now (4.3.1 B).
 *
 * `FloatingBubbleService.hideBubble(reason)` is the single sink every hide goes through, and this
 * is the single decision it takes. The rule exists because a read-aloud leaves `currentState` at
 * IDLE — only `isSpeakingNow` marks the read — and two callers (a text-field unfocus driven by any
 * foreign window event, and a media-stopped) hid the pill mid-read while the audio played on
 * (owner 2026-09-01). Pure so the guard order is a truth table in `BubbleHidePolicyTest`.
 *
 * Order: always-on never hides; a bubble that is not visible has nothing to hide and nothing to
 * park; a read in progress DEFERS (the reason is parked and replayed when the read ends); else hide.
 */
object BubbleHidePolicy {

    enum class Decision { IGNORE, DEFER, HIDE }

    fun decide(speaking: Boolean, alwaysOn: Boolean, visible: Boolean): Decision = when {
        alwaysOn -> Decision.IGNORE
        !visible -> Decision.IGNORE
        speaking -> Decision.DEFER
        else -> Decision.HIDE
    }

    /**
     * When the read ends, a parked hide replays only where the bubble would have hidden anyway:
     * never in always-on, never off a focused text field (that bubble is the user's). A
     * clipboard-summoned bubble — context NONE — therefore still leaves after the read, which is
     * what the summon promises ("for long enough to tap the pulsing speaker lobe, then leave").
     */
    fun replay(contextIsTextField: Boolean, alwaysOn: Boolean): Boolean =
        !alwaysOn && !contextIsTextField
}
```

- [ ] **Step 4: Run to see it pass.** Expected: PASS (5 tests).

- [ ] **Step 5: Whole suite, then commit** — expected **1,850** (`+5`), `failures=0`.

```
feat(bubble): BubbleHidePolicy — the one hide decision, pure

IGNORE in always-on or when hidden; DEFER while a read is in progress; HIDE otherwise. replay()
says where a parked hide may land when the read ends.
```

**Battery:** swap the `speaking` and `!visible` arms → `a_hidden_bubble_has_nothing…` RED; revert.

---

### Task 6: The sink, the replay, the trigger reset — `FloatingBubbleService` + `TtsController`

**Files:**
- Modify: `app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt` — `hideBubble` (`:1413-1437`), its four callers (`:845`, `:1144`, `:1196`, `:3483`), `startSpeaking` (`:944-983`), the IDLE render branch after the `shouldHideOnIdle` block (`:3478-3485`), a new field beside `isSpeakingNow` (`:358`)
- Modify: `app/src/main/java/com/whispereverywhere/tts/TtsController.kt` (`:74-108`)
- Create: `app/src/test/java/com/whispereverywhere/service/BubbleHideWiringPinTest.kt`

**Interfaces:**
- Consumes: `BubbleHidePolicy` (Task 5).
- Produces: `TtsController.speakFromTrigger(context, text, onDone): Boolean` — `true` iff `TtsEngine.speak` was invoked and returned `true`. (`SpeakTextActivity.kt:29` may ignore the value.)

- [ ] **Step 1: Write the failing source pins** — `BubbleHideWiringPinTest.kt`:

```kotlin
package com.whispereverywhere.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source pins on FloatingBubbleService for 4.3.1 B. The service is a 3,500-line Android class no
 * JVM test can construct, so the WIRING is held by text: every hide carries a reason and goes
 * through the one sink; the sink asks BubbleHidePolicy; a parked hide replays in the IDLE branch;
 * a bailed trigger resets the speaking flag. Live lines only — a comment cannot satisfy a pin.
 */
class BubbleHideWiringPinTest {

    private fun source(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            for (candidate in listOf(File(dir, relative), File(dir, "app/$relative"))) {
                if (candidate.isFile) return candidate.readText().replace("\r\n", "\n")
            }
            dir = dir.parentFile
        }
        throw AssertionError("cannot locate $relative from ${System.getProperty("user.dir")}")
    }

    private fun liveLines(scope: String, needle: String): List<String> =
        scope.split("\n").map { it.trimStart() }.filter { line ->
            !(line.startsWith("//") || line.startsWith("/*") || line.startsWith("*")) && line.contains(needle)
        }

    private fun liveOffsets(scope: String, needle: String): List<Int> {
        val out = mutableListOf<Int>()
        var at = 0
        for (line in scope.split("\n")) {
            val t = line.trimStart()
            val commented = t.startsWith("//") || t.startsWith("/*") || t.startsWith("*")
            if (!commented && line.contains(needle)) out += at
            at += line.length + 1
        }
        return out
    }

    /** A member's body: from the anchor line to the first non-blank line at or left of its indent. */
    private fun memberBody(kt: String, anchor: String): String {
        val start = kt.indexOf(anchor)
        assertTrue("anchor missing: $anchor", start >= 0)
        val lineStart = kt.lastIndexOf('\n', start - 1) + 1
        val indent = kt.substring(lineStart, start).takeWhile { it == ' ' }.length
        val lines = kt.substring(start).split("\n")
        val body = StringBuilder(lines.first())
        var closed = false
        for (line in lines.drop(1)) {
            if (line.isNotBlank() && line.takeWhile { it == ' ' }.length <= indent) { closed = true; break }
            body.append("\n").append(line)
        }
        assertTrue("member never closes: $anchor", closed)
        return body.toString()
    }

    private val service: String by lazy { source("src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt") }
    private val controller: String by lazy { source("src/main/java/com/whispereverywhere/tts/TtsController.kt") }

    @Test
    fun every_hide_carries_a_reason_and_there_is_exactly_one_sink() {
        assertEquals("no reasonless hide may remain", emptyList<String>(), liveLines(service, "hideBubble()"))
        assertEquals("one sink", 1, liveLines(service, "private fun hideBubble(reason: String) {").size)
        // four original callers + the deferred replay = five call sites, each with a reason literal
        val calls = liveLines(service, "hideBubble(\"")
        assertEquals("five reasoned calls, found: $calls", 5, calls.size)
        for (r in listOf("clipboard-autohide", "field-unfocused", "media-stopped", "idle-after-session", "deferred:")) {
            assertTrue("reason $r is used", calls.any { it.contains("hideBubble(\"$r") })
        }
    }

    @Test
    fun the_sink_asks_the_policy_logs_and_parks_before_it_animates() {
        val body = memberBody(service, "    private fun hideBubble(reason: String) {")
        val decide = liveOffsets(body, "BubbleHidePolicy.decide(")
        val log = liveOffsets(body, "\"bubble hide: reason=")
        val park = liveOffsets(body, "deferredHideReason = reason")
        val anim = liveOffsets(body, "hideAnimator = ValueAnimator.ofFloat(1f, 0f)")
        assertTrue("decide once", decide.size == 1)
        assertTrue("log once", log.size == 1)
        assertTrue("park once", park.size == 1)
        assertTrue("animate once", anim.size == 1)
        assertTrue("decide -> log -> park -> animate", decide.first() < log.first() && log.first() < park.first() && park.first() < anim.first())
        assertTrue("the old alwaysOn/visible early returns are gone — the policy owns them",
            liveLines(body, "if (alwaysOnMode()) return").isEmpty() && liveLines(body, "if (!isBubbleVisible) return").isEmpty())
    }

    @Test
    fun the_idle_branch_replays_a_parked_hide_through_the_policy_after_the_read() {
        val body = memberBody(service, "    private fun updateBubbleState(newState: BubbleState) {")
        val idle = liveOffsets(body, "BubbleState.IDLE -> {")
        val replay = liveOffsets(body, "BubbleHidePolicy.replay(")
        val guard = liveOffsets(body, "deferredHideReason?.let { parked ->")
        assertTrue("one replay in the IDLE arm", idle.size == 1 && replay.size == 1 && guard.size == 1)
        assertTrue("guard then replay, inside IDLE", idle.first() < guard.first() && guard.first() < replay.first())
        assertTrue("a replay never runs while still speaking", body.contains("if (!isSpeakingNow) {"))
    }

    @Test
    fun a_bailed_trigger_resets_the_speaking_flag_and_the_visuals() {
        val body = memberBody(service, "    private fun startSpeaking(text: String) {")
        assertTrue(liveLines(body, "val started = com.whispereverywhere.tts.TtsController.speakFromTrigger(this, text) {").size == 1)
        val bail = memberBody(body, "        if (!started) {")
        assertTrue(bail.contains("isSpeakingNow = false") && bail.contains("exitSpeakingVisuals()") &&
            bail.contains("engine.onPcmChunk = null") && bail.contains("engine.onBuffering = null") && bail.contains("engine.onProgress = null"))
        assertTrue("the controller reports whether speak() ran",
            liveLines(controller, "fun speakFromTrigger(context: Context, text: String, onDone: () -> Unit = {}): Boolean {").size == 1)
        assertTrue(liveLines(controller, "return e.speak(text, onDone)").size == 1)
    }
}
```

- [ ] **Step 2: Add `TtsController.kt` to `sourcePinnedInputs`** in `app/build.gradle.kts` — inside the list (the block ending at `:506`), after the `FloatingBubbleService.kt` entry (`:440`):

```kotlin
        // (4.3.1 B) BubbleHideWiringPinTest reads the controller for speakFromTrigger's Boolean.
        "src/main/java/com/whispereverywhere/tts/TtsController.kt",
```

- [ ] **Step 3: Run to see them fail** — `--tests "com.whispereverywhere.service.BubbleHideWiringPinTest"`. Expected: all four RED.

- [ ] **Step 4: Implement.**

(a) Field, beside `:358`:
```kotlin
    /** 4.3.1 B: a hide refused because a read was in progress; replayed by the IDLE branch. */
    @Volatile private var deferredHideReason: String? = null
```

(b) Replace `hideBubble` (`:1413-1437`):
```kotlin
    /**
     * THE ONE SINK every hide goes through (4.3.1 B). The decision is [BubbleHidePolicy]'s: always-on
     * and an already-hidden bubble are ignored; a read in progress PARKS the reason instead of
     * taking the pill away mid-playback (the owner's bug — a foreign window event during a read hid
     * the bubble while the audio played on); otherwise the hide animates. One WE-DIAG line per call
     * names the caller, so the next field report is one grep.
     */
    private fun hideBubble(reason: String) {
        val decision = BubbleHidePolicy.decide(
            speaking = isSpeakingNow, alwaysOn = alwaysOnMode(), visible = isBubbleVisible,
        )
        android.util.Log.i(
            "WE-DIAG",
            "bubble hide: reason=$reason decision=$decision state=$currentState context=$currentContext speaking=$isSpeakingNow",
        )
        when (decision) {
            BubbleHidePolicy.Decision.IGNORE -> return
            BubbleHidePolicy.Decision.DEFER -> { deferredHideReason = reason; return }
            BubbleHidePolicy.Decision.HIDE -> Unit
        }

        showAnimator?.cancel()

        hideAnimator = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 150
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animation ->
                val value = animation.animatedValue as Float
                bubbleView.alpha = value
                bubbleView.scaleX = 0.5f + (0.5f * value)
                bubbleView.scaleY = 0.5f + (0.5f * value)
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    bubbleView.visibility = View.GONE
                    isBubbleVisible = false
                }
            })
            start()
        }
    }
```

(c) Callers: `:845` → `hideBubble("clipboard-autohide")`; `:1144` → `hideBubble("field-unfocused")`; `:1196` → `hideBubble("media-stopped")`; `:3483` → `currentContext = BubbleContext.NONE; hideBubble("idle-after-session")`.

(d) IDLE branch — immediately after the `if (shouldHideOnIdle) { ... }` block (`:3478-3485`), still inside `BubbleState.IDLE -> {`:
```kotlin
                    // 4.3.1 B: a hide that arrived mid-read replays now — only where the bubble
                    // would have hidden anyway (never off a focused field, never in always-on).
                    deferredHideReason?.let { parked ->
                        if (!isSpeakingNow) {
                            deferredHideReason = null
                            if (BubbleHidePolicy.replay(
                                    contextIsTextField = currentContext == BubbleContext.TEXT_FIELD,
                                    alwaysOn = alwaysOnMode(),
                                )
                            ) {
                                currentContext = BubbleContext.NONE
                                hideBubble("deferred:$parked")
                            }
                        }
                    }
```

(e) `startSpeaking` (`:976-982`): replace the trailing `TtsController.speakFromTrigger(this, text) { ... }` call with:
```kotlin
        val started = com.whispereverywhere.tts.TtsController.speakFromTrigger(this, text) {
            // onDone (main thread): tear down the pill; selection may still be live.
            isSpeakingNow = false
            engine.onPcmChunk = null
            engine.onBuffering = null
            engine.onProgress = null
            exitSpeakingVisuals()
        }
        if (!started) {
            // The trigger bailed (voice not installed, arbiter busy): onDone will never fire, so
            // undo the speaking state set above or the pill stays "speaking" with nothing playing.
            isSpeakingNow = false
            engine.onPcmChunk = null
            engine.onBuffering = null
            engine.onProgress = null
            exitSpeakingVisuals()
        }
```

(f) `TtsController.speakFromTrigger` (`:74-108`): change the signature to `fun speakFromTrigger(context: Context, text: String, onDone: () -> Unit = {}): Boolean {`, make both early `return`s `return false`, and end with `return e.speak(text, onDone)`. Update its KDoc: "Returns true iff the engine's `speak` ran and accepted the text; false on every bail, so the caller can undo any speaking state it set optimistically."

- [ ] **Step 5: Run the pin test** (Step 3 command). Expected: PASS. Then compile: `.\gradlew.bat :app:assembleDebug --no-daemon` → green (the deferred-replay `hideBubble("deferred:$parked")` is a string template — the pin needle `hideBubble("deferred:` matches its live line).

- [ ] **Step 6: Whole suite, then commit** — expected **1,854** (`+4`), `failures=0`.

```
fix(bubble): a read in progress can no longer lose its bubble — one sink, one policy, one diag line

hideBubble(reason) asks BubbleHidePolicy, logs every call with its caller, parks a mid-read hide
and replays it in the IDLE branch only where the bubble would have hidden anyway. A bailed
speakFromTrigger (now Boolean) resets the speaking flag instead of stranding the pill.
```

**Battery:** (1) restore one caller to `hideBubble()` → `every_hide_carries_a_reason…` RED; revert. (2) move the `Log.i` above `decide` → `the_sink_asks…` RED; revert. (3) delete the `if (!started)` block → `a_bailed_trigger…` RED; revert.

---

### Task 7: `TtsBufferPolicy.startDecision` + `TtsRemainingEstimate`

**Files:**
- Modify: `app/src/main/java/com/whispereverywhere/tts/TtsBufferPolicy.kt`
- Create: `app/src/main/java/com/whispereverywhere/tts/TtsRemainingEstimate.kt`
- Test: `app/src/test/java/com/whispereverywhere/tts/TtsBufferPolicyTest.kt` (append); create `TtsRemainingEstimateTest.kt`

**Interfaces:**
- Produces (Task 8, 9 consume):
  ```kotlin
  enum class StartRule { DONE, CAP, PROJECTED }
  fun TtsBufferPolicy.startDecision(bufferedMs: Int, remainingMs: Int, totalMs: Int, noGrowthMs: Long, done: Boolean): StartRule?
  fun TtsBufferPolicy.shouldStart(...same...): Boolean   // == startDecision(...) != null
  fun TtsBufferPolicy.rtf(): Double
  TtsBufferPolicy.SHORT_READ_MS = 20_000; PROJECTED_SAFETY = 1.5; START_CAP_MS = 12_000L
  object TtsRemainingEstimate { fun ms(chars: Int): Long; fun samples(chars: Int, sampleRate: Int): Long }
  ```

- [ ] **Step 1: Write the failing tests** — append to `TtsBufferPolicyTest.kt`:

```kotlin
    // ---------------------------------------------------------------- the start gate (4.3.1 C)

    // A 2-minute read: 120 000 ms of audio estimated. Local RTF 0.58 after 20 samples.
    private fun measuredLocal(): TtsBufferPolicy = localPolicy().also { p ->
        repeat(20) { p.recordRtf(synthMs = 580, audMs = 1_000) }
    }

    @Test fun an_empty_bank_never_starts_whatever_the_rule() {
        val p = measuredLocal()
        assertNull(p.startDecision(bufferedMs = 0, remainingMs = 0, totalMs = 120_000, noGrowthMs = 60_000, done = true))
    }

    @Test fun a_finished_producer_starts_at_once() {
        assertEquals(StartRule.DONE, measuredLocal().startDecision(bufferedMs = 300, remainingMs = 0, totalMs = 5_000, noGrowthMs = 0, done = true))
    }

    @Test fun projected_complete_starts_at_47_percent_for_the_measured_local_voice() {
        // banked >= 1.5 * 0.58 * remaining  <=>  banked/total >= 0.87/1.87 = 46.5 %
        val p = measuredLocal()
        val total = 120_000
        assertNull("46 % is short", p.startDecision(bufferedMs = 55_000, remainingMs = 65_000, totalMs = total, noGrowthMs = 0, done = false))
        assertEquals(StartRule.PROJECTED, p.startDecision(bufferedMs = 57_000, remainingMs = 63_000, totalMs = total, noGrowthMs = 0, done = false))
    }

    @Test fun a_slow_producer_needs_more_but_never_everything() {
        val p = localPolicy()
        repeat(20) { p.recordRtf(synthMs = 2_000, audMs = 1_000) }   // rtf 2.0
        val total = 120_000
        // 1.5 * 2.0 / (1 + 3.0) = 75 %
        assertNull(p.startDecision(bufferedMs = 89_000, remainingMs = 31_000, totalMs = total, noGrowthMs = 0, done = false))
        assertEquals(StartRule.PROJECTED, p.startDecision(bufferedMs = 91_000, remainingMs = 29_000, totalMs = total, noGrowthMs = 0, done = false))
    }

    @Test fun rtf_one_starts_at_60_percent() {
        val p = localPolicy()
        repeat(20) { p.recordRtf(synthMs = 1_000, audMs = 1_000) }
        // 1.5 * 1.0 / (1 + 1.5) = 60 % of 120 000 = 72 000 banked / 48 000 remaining.
        assertNull(p.startDecision(bufferedMs = 71_000, remainingMs = 49_000, totalMs = 120_000, noGrowthMs = 0, done = false))
        assertEquals(StartRule.PROJECTED, p.startDecision(bufferedMs = 73_000, remainingMs = 47_000, totalMs = 120_000, noGrowthMs = 0, done = false))
    }

    @Test fun a_short_read_completes_first_even_when_the_projection_would_pass() {
        val p = measuredLocal()
        // 19 s total, 18 s banked, 1 s remaining: projection passes, floor says wait for done.
        assertNull(p.startDecision(bufferedMs = 18_000, remainingMs = 1_000, totalMs = 19_000, noGrowthMs = 0, done = false))
        assertEquals(StartRule.DONE, p.startDecision(bufferedMs = 19_000, remainingMs = 0, totalMs = 19_000, noGrowthMs = 0, done = true))
        // 21 s total is not short: the projection applies.
        assertEquals(StartRule.PROJECTED, p.startDecision(bufferedMs = 18_000, remainingMs = 3_000, totalMs = 21_000, noGrowthMs = 0, done = false))
    }

    @Test fun the_start_cap_fires_on_no_growth_time_only_and_needs_audio() {
        val p = measuredLocal()
        assertEquals(StartRule.CAP, p.startDecision(bufferedMs = 800, remainingMs = 100_000, totalMs = 120_000, noGrowthMs = TtsBufferPolicy.START_CAP_MS, done = false))
        assertNull(p.startDecision(bufferedMs = 800, remainingMs = 100_000, totalMs = 120_000, noGrowthMs = TtsBufferPolicy.START_CAP_MS - 1, done = false))
        assertNull(p.startDecision(bufferedMs = 0, remainingMs = 100_000, totalMs = 120_000, noGrowthMs = 60_000, done = false))
        // The cap also frees a SHORT read whose producer stopped growing.
        assertEquals(StartRule.CAP, p.startDecision(bufferedMs = 800, remainingMs = 5_000, totalMs = 10_000, noGrowthMs = TtsBufferPolicy.START_CAP_MS, done = false))
    }

    @Test fun should_start_is_the_decision_made_boolean_and_the_constants_are_the_specs() {
        val p = measuredLocal()
        assertTrue(p.shouldStart(bufferedMs = 300, remainingMs = 0, totalMs = 5_000, noGrowthMs = 0, done = true))
        assertFalse(p.shouldStart(bufferedMs = 0, remainingMs = 0, totalMs = 5_000, noGrowthMs = 0, done = true))
        assertEquals(20_000, TtsBufferPolicy.SHORT_READ_MS)
        assertEquals(1.5, TtsBufferPolicy.PROJECTED_SAFETY, 0.0)
        assertEquals(12_000L, TtsBufferPolicy.START_CAP_MS)
        assertEquals(0.58, p.rtf(), 0.02)
    }

    @Test fun the_resume_rule_is_untouched() {
        // shouldProceed is the stall-resume gate and keeps its watermark semantics byte-for-byte.
        val p = localPolicy()
        assertEquals(3_375, p.targetMs())
        assertFalse(p.shouldProceed(bufferedMs = 1_000, waitedMs = 0, done = false))
        assertTrue(p.shouldProceed(bufferedMs = 3_400, waitedMs = 0, done = false))
    }
```
Add `import org.junit.Assert.assertNull` at the top of the file.

Create `TtsRemainingEstimateTest.kt`:
```kotlin
package com.whispereverywhere.tts

import org.junit.Assert.assertEquals
import org.junit.Test

class TtsRemainingEstimateTest {
    @Test fun chars_to_ms_uses_the_splitters_constant() {
        assertEquals(45L * 100, TtsRemainingEstimate.ms(100))
        assertEquals(ClauseSplitter.MS_PER_CHAR, TtsRemainingEstimate.ms(1))
    }
    @Test fun negative_chars_clamp_to_zero() {
        assertEquals(0L, TtsRemainingEstimate.ms(-5))
        assertEquals(0L, TtsRemainingEstimate.samples(-5, 24_000))
    }
    @Test fun samples_follow_the_track_rate() {
        // 100 chars = 4 500 ms = 108 000 samples at 24 kHz
        assertEquals(108_000L, TtsRemainingEstimate.samples(100, 24_000))
    }
}
```

- [ ] **Step 2: Run to see them fail** — `--tests "com.whispereverywhere.tts.TtsBufferPolicyTest" --tests "com.whispereverywhere.tts.TtsRemainingEstimateTest"`. Expected: compile failure (`startDecision`, `StartRule`, `TtsRemainingEstimate`).

- [ ] **Step 3: Implement.** In `TtsBufferPolicy.kt` add before the class:

```kotlin
/** Which rule let playback start (4.3.1 C) — logged on the `TTSDIAG start` line. */
enum class StartRule { DONE, CAP, PROJECTED }
```
Inside the class, after `shouldProceed`:

```kotlin
    /** The measured synthesis speed the projection uses (EWMA; DEFAULT_RTF until fed). */
    fun rtf(): Double = rtfEwma

    /**
     * THE START GATE (4.3.1 C, owner decision 2026-09-02 "projected-complete"): playback may
     * begin only when what is banked outlasts the projected remainder of the read.
     *
     *     start ⟸ bufferedMs > 0 && (
     *         done                                                         -> DONE
     *         || noGrowthMs >= START_CAP_MS                                -> CAP
     *         || (totalMs > SHORT_READ_MS
     *             && bufferedMs >= PROJECTED_SAFETY * rtf * remainingMs)   -> PROJECTED )
     *
     * Derivation: with lead L and remaining audio R the producer needs rtf·R wall-seconds and
     * playback consumes the lead in L seconds, so L ≥ 1.5·rtf·R finishes synthesis before playback
     * reaches the frontier with a 50 % margin, independent of unit granularity. As a fraction of
     * the read that is 1.5rtf/(1+1.5rtf): 47 % at the local voice's 0.58, 60 % at rtf 1, 75 % at
     * rtf 2 — "mostly all", never degenerating to everything-first. A short read (≤ SHORT_READ_MS)
     * completes first: the wait is small and certainty is free. The cap escapes a producer that
     * has stopped growing the bank with audio already banked (a cloud fetch on its 45 s timeout)
     * and counts NO-GROWTH time only. [shouldProceed] is the stall-RESUME rule and is untouched.
     */
    fun startDecision(bufferedMs: Int, remainingMs: Int, totalMs: Int, noGrowthMs: Long, done: Boolean): StartRule? {
        if (bufferedMs <= 0) return null
        if (done) return StartRule.DONE
        if (noGrowthMs >= START_CAP_MS) return StartRule.CAP
        if (totalMs <= SHORT_READ_MS) return null
        val need = PROJECTED_SAFETY * rtfEwma * remainingMs.coerceAtLeast(0)
        return if (bufferedMs >= need) StartRule.PROJECTED else null
    }

    fun shouldStart(bufferedMs: Int, remainingMs: Int, totalMs: Int, noGrowthMs: Long, done: Boolean): Boolean =
        startDecision(bufferedMs, remainingMs, totalMs, noGrowthMs, done) != null
```
And in the companion:
```kotlin
        /** A read this short is generated fully before it starts (the wait is small). */
        const val SHORT_READ_MS = 20_000
        /** Margin on the projected remainder: 1.5× the measured synthesis time. */
        const val PROJECTED_SAFETY = 1.5
        /** The start gate's stuck-producer escape, on no-growth time only. */
        const val START_CAP_MS = 12_000L
```
Create `TtsRemainingEstimate.kt`:
```kotlin
package com.whispereverywhere.tts

/**
 * Chars still to synthesise → estimated audio, by the same 45 ms/char the clause splitter's caps
 * were derived with (`ClauseSplitter.MS_PER_CHAR`). One home for the conversion so the start gate
 * and the scrubber's estimated total cannot disagree.
 */
object TtsRemainingEstimate {
    fun ms(chars: Int): Long = chars.coerceAtLeast(0) * ClauseSplitter.MS_PER_CHAR
    fun samples(chars: Int, sampleRate: Int): Long = ms(chars) * sampleRate / 1000
}
```

- [ ] **Step 4: Run to see them pass** (Step 2 command). Expected: PASS. Check the 47 % edge by hand: `1.5 × 0.58 × 63 000 = 54 810 ≤ 57 000` passes; `1.5 × 0.58 × 65 000 = 56 550 > 55 000` fails. (The EWMA after 20 samples from 0.75 → within 0.01 of 0.58: `0.75 × 0.7^20 + 0.58 × (1 − 0.7^20) ≈ 0.5801`.)

- [ ] **Step 5: Whole suite, then commit** — expected **1,866** (`+12`), `failures=0`.

```
feat(tts): the start gate — projected-complete, short reads complete first, a no-growth cap

startDecision names the rule (DONE / CAP / PROJECTED); shouldProceed keeps the stall-resume
watermark untouched. TtsRemainingEstimate is the one chars→ms home.
```

**Battery:** (1) change `PROJECTED_SAFETY` to `1.0` → `projected_complete_starts_at_47…` RED; revert. (2) drop the `totalMs <= SHORT_READ_MS` line → `a_short_read_completes_first…` RED; revert.

---

### Task 8: Engine wiring — remaining chars, cloud RTF, the start gate, progress with a total, `TTSDIAG start`

**Files:**
- Modify: `app/src/main/java/com/whispereverywhere/tts/TtsDiag.kt` (add `start`), `app/src/test/java/com/whispereverywhere/tts/TtsDiagTest.kt`
- Modify: `app/src/main/java/com/whispereverywhere/tts/TtsEngine.kt` — `onProgress` (`:116-120`), fields near `:47-49`, the `bufferPolicy` block (`:291-297`), the gate (`:352-393`), the two progress emits (`:476-483`, `:492-493`), the cloud unit (`:611-627` region), the local/cloud unit completion (`:614-661`)
- Modify: `app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt` (`:970-972`) — the `onProgress` adapter must compile against the new arity (temporary 4-arg pass-through; Task 9 gives the scrubber the total)

**Interfaces:**
- Consumes: Task 7.
- Produces: `TtsEngine.onProgress: ((played: Long, available: Long, estimatedTotal: Long, done: Boolean) -> Unit)?`; `TtsDiag.start(gen: Long, bankedMs: Long, remainingMs: Long, totalMs: Long, rtf: Double, rule: String): String`.

- [ ] **Step 1: Write the failing test** — append to `TtsDiagTest.kt`:

```kotlin
    @Test fun start_line_names_the_rule_and_the_numbers_it_was_decided_on() {
        val line = TtsDiag.start(gen = 7, bankedMs = 57_000, remainingMs = 63_000, totalMs = 120_000, rtf = 0.58, rule = "projected")
        assertTrue(line, line.startsWith("TTSDIAG start "))
        assertTrue(line, line.contains("gen=7"))
        assertTrue(line, line.contains("bankedMs=57000"))
        assertTrue(line, line.contains("remainingMs=63000"))
        assertTrue(line, line.contains("totalMs=120000"))
        assertTrue(line, line.contains("rtf=0.58"))
        assertTrue(line, line.contains("rule=projected"))
        assertTrue("no comma", !line.contains(","))
    }
```

- [ ] **Step 2: Run to see it fail** — `--tests "com.whispereverywhere.tts.TtsDiagTest"`. Expected: compile failure.

- [ ] **Step 3: Implement `TtsDiag.start`** (after `play`):

```kotlin
    /** Playback START decision (4.3.1 C): the numbers the gate saw and the rule that let it go. */
    fun start(gen: Long, bankedMs: Long, remainingMs: Long, totalMs: Long, rtf: Double, rule: String): String =
        "TTSDIAG start gen=$gen bankedMs=$bankedMs remainingMs=$remainingMs totalMs=$totalMs rtf=${d2(rtf)} rule=$rule"
```
Update the object KDoc's kinds list: `open, sent, start, play, under, end`.

- [ ] **Step 4: Run to see it pass.** Expected: PASS.

- [ ] **Step 5: Engine — the progress signature.** Replace `:116-120`:

```kotlin
    /**
     * Scrubber feed (~10 Hz from the playback thread, and during the start gate): samples played,
     * samples synthesized so far, the ESTIMATED total (chars × 45 ms until synthesis finishes,
     * then == available), and whether synthesis has finished (= the bar's right edge is final).
     */
    @Volatile var onProgress: ((played: Long, available: Long, estimatedTotal: Long, done: Boolean) -> Unit)? = null
```
Fields, after `@Volatile private var speaking = false` (`:48`):
```kotlin
    // 4.3.1 C: what the start gate projects from. Written by the producer, read by playback.
    @Volatile private var plannedChars = 0
    @Volatile private var remainingChars = 0
```
Add a private helper inside the class (near `seekToFraction`):
```kotlin
    /** The bar's estimated end: the chars→samples projection until done, then the bank itself. */
    private fun estimatedTotalSamples(sampleRate: Int, done: Boolean): Long =
        if (done) availableSamples
        else maxOf(availableSamples, TtsRemainingEstimate.samples(plannedChars, sampleRate))
```

- [ ] **Step 6: Engine — the producer publishes.** In the producer (`:614`), replace `for (unit in ClauseSplitter.plan(clean, unitCap)) {` with:

```kotlin
                    val units = ClauseSplitter.plan(clean, unitCap)
                    plannedChars = units.sumOf { it.length }
                    remainingChars = plannedChars
                    for (unit in units) {
                        if (cancelled()) break
                        // ... [existing body of the loop, unchanged] ...
```
and at the END of that loop body (after the `when (planUnitOutcome(...)) { ... }` closes, still inside `for`), add:
```kotlin
                        // This unit's audio — cloud or local fallback — is in the bank: shrink the
                        // projected remainder. Once per top-level unit; localResplit's sub-units
                        // are part of it.
                        remainingChars -= unit.length
```
Reset both fields at the top of `speak()`'s executor task, right after `playedSamples = 0L` / `availableSamples = 0L` (`:238-239`): `plannedChars = 0; remainingChars = 0`.

- [ ] **Step 7: Engine — the cloud path feeds the RTF.** In the `UnitAction.Cloud` branch, wrap the fetch: before `val res = runCatching { runBlocking { ... } }` add `val unitStartMs = System.currentTimeMillis(); val bankBefore = availableSamples; val unitSeq = cloudUnitSeq++` (declare `var cloudUnitSeq = 0` beside `var consecutiveSoft = 0`), and in the `else -> consecutiveSoft = 0 // Cloud delivered` arm of the post-synth `when`, record:
```kotlin
                                    else -> {
                                        consecutiveSoft = 0 // Cloud delivered: reset the streak.
                                        // 4.3.1 C: the projection needs THIS provider's speed, not
                                        // DEFAULT_RTF. Wall time of the fetch vs audio it banked,
                                        // skipping the first unit (connection setup) as the local
                                        // callback skips seq 0.
                                        val audMs = TtsDiagMath.audioMs((availableSamples - bankBefore).toInt().coerceAtLeast(0), engine.sampleRate())
                                        if (unitSeq > 0) bufferPolicy.recordRtf(System.currentTimeMillis() - unitStartMs, audMs.toInt())
                                    }
```

- [ ] **Step 8: Engine — the start gate.** Replace the `if (!bufferPolicy.shouldProceed(bufferedMs.toInt(), waitedMs, done = false)) {` line and its block (`:374-388`) with:

```kotlin
                                val rate = localTrack.sampleRate
                                val remainingMs = TtsRemainingEstimate.ms(remainingChars).toInt()
                                val totalMs = TtsRemainingEstimate.ms(plannedChars).toInt()
                                // 4.3.1 C: the FIRST start is projected-complete; a RESUME after a
                                // stall keeps the watermark rule (shouldProceed) untouched.
                                val rule: StartRule? = if (!started) {
                                    bufferPolicy.startDecision(bufferedMs.toInt(), remainingMs, totalMs, waitedMs, done = false)
                                } else if (bufferPolicy.shouldProceed(bufferedMs.toInt(), waitedMs, done = false)) {
                                    StartRule.PROJECTED
                                } else null
                                if (rule == null) {
                                    // The start hold is BUFFERING and must say so — without this
                                    // the speaking pill sits silent with a motionless aurora and
                                    // reads as the bubble vanishing (owner report 2026-08-01).
                                    if (!started && !gateRingShown) {
                                        gateRingShown = true
                                        onBuffering?.invoke(true)
                                    }
                                    // The scrubber must move while we wait: the gray region grows
                                    // toward the estimated total (4.3.1 C).
                                    val now = System.currentTimeMillis()
                                    if (now - lastProgressMs >= 100) {
                                        lastProgressMs = now
                                        onProgress?.invoke(cursor, availableSamples, estimatedTotalSamples(rate, false), false)
                                    }
                                    try { Thread.sleep(20) } catch (_: InterruptedException) {}
                                    continue@loop
                                }
                                if (!started) {
                                    android.util.Log.i(
                                        TtsDiag.TAG,
                                        TtsDiag.start(myGen, bufferedMs, remainingMs.toLong(), totalMs.toLong(), bufferPolicy.rtf(), rule.name.lowercase()),
                                    )
                                }
```
Keep the `if (gateRingShown) { gateRingShown = false; onBuffering?.invoke(false) }` that follows.

- [ ] **Step 9: Engine — the two existing progress emits.** `:479` → `onProgress?.invoke(cursor, availableSamples, estimatedTotalSamples(localTrack.sampleRate, doneFlag.get()), doneFlag.get())`; `:493` → `onProgress?.invoke(playedSamples, availableSamples, availableSamples, true)`.

- [ ] **Step 10: Service adapter** (`FloatingBubbleService.kt:970-972`): `engine.onProgress = { played, available, total, done -> ttsScrubber.setProgress(played, available, done) }` — a pass-through that ignores `total` until Task 9. (Comment: `// 4.3.1 C: total reaches the scrubber in Task 9.`)

- [ ] **Step 11: Compile + suite** — `assembleDebug` green; whole suite expected **1,867** (`+1`), `failures=0`.

- [ ] **Step 12: Commit**

```
feat(tts): the engine starts on the projected remainder and shows the wait

The producer publishes planned/remaining chars; cloud units feed the RTF estimate; the start
gate calls startDecision (resume keeps shouldProceed); progress carries an estimated total and
ticks during the hold; TTSDIAG start names the rule.
```

**Battery:** this task's behaviour is engine code with no JVM oracle; the pins are Task 7's and the `TtsDiag` test. Row: change `TtsDiag.start`'s prefix to `TTSDIAG begin` → `start_line_names…` RED; revert. Record in the report that the gate wiring is verified by the owner's device session (Task 10 sheet §C).

---

### Task 9: The scrubber shows the generating region; seeks stay in synthesized audio

**Files:**
- Modify: `app/src/main/java/com/whispereverywhere/ui/components/TtsScrubberView.kt` (`:28-30`, `:59-83`, `:105-109`)
- Modify: `app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt` (`:970-972`)
- Create: `app/src/main/java/com/whispereverywhere/ui/components/ScrubberMath.kt` and `app/src/test/java/com/whispereverywhere/ui/components/ScrubberMathTest.kt`

**Interfaces:**
- Consumes: `onProgress` 4-arg (Task 8).
- Produces: `TtsScrubberView.setProgress(played: Long, available: Long, estimatedTotal: Long, done: Boolean)`; `object ScrubberMath { fun span(available: Long, estimatedTotal: Long): Long; fun frac(part: Long, span: Long): Float; fun seekFracOfSynthesized(barFrac: Float, available: Long, span: Long): Float }`.

- [ ] **Step 1: Write the failing test** — `ScrubberMathTest.kt`:

```kotlin
package com.whispereverywhere.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/** The scrubber's geometry, pure (4.3.1 C): the bar spans the ESTIMATED read, seeks stay in the bank. */
class ScrubberMathTest {
    @Test fun the_bar_spans_the_larger_of_estimate_and_bank() {
        assertEquals(1_000L, ScrubberMath.span(available = 400, estimatedTotal = 1_000))
        assertEquals(1_200L, ScrubberMath.span(available = 1_200, estimatedTotal = 1_000)) // estimate ran short
        assertEquals(0L, ScrubberMath.span(available = 0, estimatedTotal = 0))
    }
    @Test fun fractions_are_clamped_and_zero_span_is_zero() {
        assertEquals(0.4f, ScrubberMath.frac(400, 1_000), 1e-6f)
        assertEquals(1f, ScrubberMath.frac(1_500, 1_000), 1e-6f)
        assertEquals(0f, ScrubberMath.frac(400, 0), 1e-6f)
    }
    @Test fun a_seek_on_the_bar_is_rebased_to_the_synthesized_audio() {
        // Bar spans 1000, bank holds 400: the bar's 0.2 is the bank's 0.5; past the bank clamps to 1.
        assertEquals(0.5f, ScrubberMath.seekFracOfSynthesized(barFrac = 0.2f, available = 400, span = 1_000), 1e-6f)
        assertEquals(1f, ScrubberMath.seekFracOfSynthesized(barFrac = 0.9f, available = 400, span = 1_000), 1e-6f)
        assertEquals(0f, ScrubberMath.seekFracOfSynthesized(barFrac = 0.5f, available = 0, span = 1_000), 1e-6f)
        // When the bank IS the bar (done), the mapping is identity.
        assertEquals(0.7f, ScrubberMath.seekFracOfSynthesized(barFrac = 0.7f, available = 1_000, span = 1_000), 1e-6f)
    }
}
```

- [ ] **Step 2: Run to see it fail** — `--tests "com.whispereverywhere.ui.components.ScrubberMathTest"`. Expected: compile failure.

- [ ] **Step 3: Implement** — `ScrubberMath.kt`:

```kotlin
package com.whispereverywhere.ui.components

/** Pure geometry for [TtsScrubberView] (4.3.1 C), so the bar's model is testable without a View. */
object ScrubberMath {
    /** The bar represents the whole read: the estimate, or the bank if the estimate ran short. */
    fun span(available: Long, estimatedTotal: Long): Long = maxOf(available, estimatedTotal, 0L)

    fun frac(part: Long, span: Long): Float =
        if (span <= 0L) 0f else (part.toDouble() / span).toFloat().coerceIn(0f, 1f)

    /**
     * A drag lands on the BAR; the engine seeks within SYNTHESIZED audio (`seekToFraction`'s
     * contract). Re-base, clamping to the bank's end: you can scrub into what exists, not into
     * what is still being generated.
     */
    fun seekFracOfSynthesized(barFrac: Float, available: Long, span: Long): Float {
        if (available <= 0L || span <= 0L) return 0f
        return (barFrac.toDouble() * span / available).toFloat().coerceIn(0f, 1f)
    }
}
```
In `TtsScrubberView.kt`: replace the state (`:28-30`) with
```kotlin
    @Volatile private var playedFrac = 0f      // of the bar
    @Volatile private var readyFrac = 1f       // of the bar: synthesized so far
    @Volatile private var synthesisDone = true
    @Volatile private var lastAvailable = 0L
    @Volatile private var lastSpan = 0L
```
replace `setProgress` (`:59-63`) with
```kotlin
    /**
     * [played]/[available] in samples as before; [estimatedTotal] is the projected end of the read
     * (== [available] once [done]). The bar spans the read; gray is what is ready ahead of you,
     * white is what is still being generated (4.3.1 C — the wait is visible, not silent).
     */
    fun setProgress(played: Long, available: Long, estimatedTotal: Long, done: Boolean) {
        val span = ScrubberMath.span(available, estimatedTotal)
        playedFrac = ScrubberMath.frac(played, span)
        readyFrac = ScrubberMath.frac(available, span)
        synthesisDone = done
        lastAvailable = available
        lastSpan = span
        if (!dragging) postInvalidate()
    }
```
replace the "ahead of you" drawing (`:73-76`) with
```kotlin
        // Ahead of you: gray where audio is ready; white from the synthesized frontier to the
        // estimated end while generation continues (falls back to the old 10 dp tail when the
        // estimate has no room left).
        val readyX = left + (right - left) * readyFrac.coerceIn(0f, 1f)
        if (readyX > x) canvas.drawLine(x, y, readyX, y, readyPaint)
        if (!synthesisDone) {
            val tailStart = if (readyX < right - dp(10f)) readyX else right - dp(10f)
            canvas.drawLine(tailStart, y, right, y, growPaint)
        }
```
and the `ACTION_UP` seek (`:105-109`) with
```kotlin
            MotionEvent.ACTION_UP -> {
                dragging = false
                onSeek?.invoke(ScrubberMath.seekFracOfSynthesized(frac, lastAvailable, lastSpan))
                invalidate()
                return true
            }
```
Update the KDoc on `onSeek` (`:26`): "Fraction (0..1) of SYNTHESIZED audio to jump to — already re-based from the bar by [ScrubberMath]." In `FloatingBubbleService.kt:970-972`: `engine.onProgress = { played, available, total, done -> ttsScrubber.setProgress(played, available, total, done) }`, and `enterSpeakingVisuals` (`:930`) `ttsScrubber.setProgress(0, 0, 0, false)`.

- [ ] **Step 4: Run to see it pass**; `assembleDebug` green.

- [ ] **Step 5: Whole suite, then commit** — expected **1,870** (`+3`), `failures=0`.

```
feat(tts): the scrubber spans the estimated read — gray is ready, white is still generating

ScrubberMath is the bar's geometry, pure; seeks are re-based to synthesized audio so the
engine's seekToFraction contract is untouched.
```

**Battery:** make `seekFracOfSynthesized` return `barFrac` → `a_seek_on_the_bar…` RED; revert.

---

### Task 10: Certification, the acceptance sheet, merge readiness

**Files:**
- Create: `docs/superpowers/sdd/2026-09-02-431-guards-tts/acceptance.md`
- Modify: `.superpowers/sdd/2026-09-02-431-guards-tts/progress.md`

- [ ] **Step 1: Full boundary run.** Clean porcelain; purge XML; whole suite EXECUTED; count from XML. Expected **150 + 3 suites (BubbleHidePolicyTest, BubbleHideWiringPinTest, TtsRemainingEstimateTest, ScrubberMathTest = +4 suites → 154) / 1,870 tests / 0 / 0.** Record exact numbers. `assembleDebug` green. Also build the release bundle to prove `lintVitalRelease` still passes: `.\gradlew.bat :app:bundleRelease --no-daemon` → `BUILD SUCCESSFUL` (the signing config already exists; do not touch keystore files).

- [ ] **Step 2: Re-run every battery row** listed in Tasks 2–9 from a clean tree, in one session, recording per row: class, mutation, RED failures count, GREEN after revert. Any survivor = fix the pin in a micro-round before proceeding.

- [ ] **Step 3: Write the acceptance sheet** `docs/superpowers/sdd/2026-09-02-431-guards-tts/acceptance.md` — the owner's device session, one page:

```markdown
# 4.3.1 — device acceptance (owner session)

Install: the INTERNAL TRACK build (never adb installDebug). Capture: on the PC,
`C:\Users\bastr\AppData\Local\Android\Sdk\platform-tools\adb.exe logcat -s WE-DIAG WE-TTS *>> C:\Users\bastr\.androidbuild\capture-431.txt`
(append, never clear; leave it running for the whole session).

## A — decode guards (NPU turbo tier)
A1. Dictate five ordinary sentences. Expect text unchanged from 4.3.0 and, per segment, a line
    `npu: encode=… tokens=N lang=… nsp=0.0x lp=-0.x ent=… rung=0 term=eot`. FAIL if any `rung>0`
    on clean speech, or any transcript differs from what 4.3.0 typed for the same sentence.
A2. Repeat the utterances that used to run away (the "70-80 repeats" ones). Expect NO repeated
    block; the line shows `rung>=1` or `term=cut`, tokens well under 196. Grep:
    `Select-String "terminated by the token budget" capture-431.txt` → expected: no hits.
A3. Open the mic, breathe/"um"/think for 2-3 s without words, close. Expect nothing typed and a
    line with `nsp>0.60` and `lp<-1.00`. FAIL if "Thank you" (or any text) appears.
A4. If ANY line shows `nsp=-1.00`: the logits scale was unreadable on this asset — report it;
    the entropy guard still ran (A2 must still pass).

## B — the bubble survives a read
B1. Auto pop-up mode, bubble hidden. Copy a paragraph in another app; tap the pulsing speaker
    lobe within 2 s. Expect the pill (aurora + scrubber + ✕) to stay for the whole read, then
    leave. Grep `bubble hide:` → a line with `decision=DEFER` and its `reason=`; WRITE THE
    REASON DOWN (it names the trigger for the follow-up).
B2. Same, tapping after 6 s. Expect identical behaviour.
B3. Always-on mode: same copy→tap; the bubble never leaves.
B4. With a text field focused, copy from it and tap: the bubble stays after the read.
B5. Uninstall the voice (Settings) and tap the lobe: toast, bubble returns to idle, no stuck pill.

## C — projected-complete playback
C1. Local voice, a ~2-minute article. Expect the ring + a scrubber whose gray region grows and
    white shrinks, first word at roughly half generated (`TTSDIAG start … rule=projected`), then
    `TTSDIAG end … underN=0`. FAIL if `underN>0` or if playback starts before `start` is logged.
C2. Same on a cloud voice: `underN=0`; the `start` line's `rtf=` is the cloud's, not 0.75.
C3. A one-sentence read: starts within ~2 s (`rule=done`).
C4. Stop (✕) during the wait: instant, no audio afterwards. Scrub back mid-read: works; scrubbing
    past the gray edge lands at the frontier.
```

- [ ] **Step 4: Ledger close-out** — append to `progress.md`: the per-task commit list, the final suite count, the battery summary (rows/killed/survivors), the acceptance sheet path, and the merge instruction: fast-forward onto `main` only after the owner marks A2, A3, B1 and C1 PASS.

- [ ] **Step 5: Commit**

```
docs(4.3.1): acceptance sheet + ledger close-out — certified 154/1,870/0

Battery re-run from a clean tree (rows/killed/survivors recorded). Merge is gated on the
owner's device session: A2, A3, B1, C1.
```

---

### Task 11: The screen-capture consent asks at most twice per session (Workstream D, added 2026-09-02)

**Files:**
- Create: `app/src/main/java/com/whispereverywhere/audio/ProjectionConsentBudget.kt`
- Modify: `app/src/main/java/com/whispereverywhere/audio/AudioSourcePolicy.kt` (`decide` gains `consentAvailable`)
- Modify: `app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt` — the handover site in `onMediaPlaybackStarted` (grep `Allow screen capture to transcribe device audio`, first hit), `startAudioInput()` (grep `SourceDecision.RequestConsent ->`), `startRecording()` (grep `sessionProducedText = false`), a new field beside `activeSource`
- Test: create `app/src/test/java/com/whispereverywhere/audio/ProjectionConsentBudgetTest.kt`; modify `app/src/test/java/com/whispereverywhere/audio/AudioSourcePolicyTest.kt`; create `app/src/test/java/com/whispereverywhere/service/ConsentBudgetWiringPinTest.kt`
- Docs: append §D to `docs/superpowers/sdd/2026-09-02-431-guards-tts/acceptance.md`

**Interfaces:**
- Produces: `ProjectionConsentBudget(maxAsks: Int = MAX_ASKS_PER_SESSION)` with `asked: Int`, `mayAsk(): Boolean`, `noteAsked()`, `reset()`, `MAX_ASKS_PER_SESSION = 2`; `AudioSourcePolicy.decide(mediaPlaying, hasProjection, sdkInt, preferDeviceAudio, consentAvailable: Boolean): SourceDecision`.

- [ ] **Step 1: Write the failing tests.** Create `ProjectionConsentBudgetTest.kt`:

```kotlin
package com.whispereverywhere.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-session cap on the screen-capture consent dialog (4.3.1 D). The owner's report: cancel
 * the dialog and the app raised it again at once — every cancel resumed the video, every resume
 * asked — with no bound. Two asks per session: the second is the "I cancelled by mistake"
 * recovery; after that the session is the microphone's.
 */
class ProjectionConsentBudgetTest {

    @Test fun two_asks_then_the_session_is_the_microphones() {
        val b = ProjectionConsentBudget()
        assertTrue(b.mayAsk()); b.noteAsked()
        assertEquals(1, b.asked)
        assertTrue(b.mayAsk()); b.noteAsked()
        assertEquals(2, b.asked)
        assertFalse("the third ask is the trap", b.mayAsk())
        b.noteAsked() // a caller that ignores mayAsk() still cannot make it true
        assertFalse(b.mayAsk())
    }

    @Test fun a_new_session_starts_a_fresh_budget() {
        val b = ProjectionConsentBudget()
        b.noteAsked(); b.noteAsked()
        assertFalse(b.mayAsk())
        b.reset()
        assertEquals(0, b.asked)
        assertTrue(b.mayAsk())
    }

    @Test fun the_cap_is_two_and_honoured_when_overridden() {
        assertEquals(2, ProjectionConsentBudget.MAX_ASKS_PER_SESSION)
        val once = ProjectionConsentBudget(maxAsks = 1)
        assertTrue(once.mayAsk()); once.noteAsked()
        assertFalse(once.mayAsk())
    }
}
```
Append to `AudioSourcePolicyTest.kt` (and add `consentAvailable = true` to every existing call so they keep their meaning):

```kotlin
    @Test fun `media playing without projection but the consent budget is spent - mic`() =
        assertEquals(SourceDecision.UseMic,
            AudioSourcePolicy.decide(mediaPlaying = true, hasProjection = false, sdkInt = 34, preferDeviceAudio = true, consentAvailable = false))

    @Test fun `a stored projection is used whatever the budget says`() =
        assertEquals(SourceDecision.UsePlayback,
            AudioSourcePolicy.decide(mediaPlaying = true, hasProjection = true, sdkInt = 34, preferDeviceAudio = true, consentAvailable = false))
```
Create `ConsentBudgetWiringPinTest.kt` — copy the `source` / `liveLines` / `liveOffsets` / `memberBody` helpers from `BubbleHideWiringPinTest.kt` verbatim (the corrected `memberBody`, whose indent is the anchor line's own leading spaces), then:

```kotlin
    private val service: String by lazy { source("src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt") }

    @Test
    fun both_consent_requests_are_budgeted_and_counted_and_the_budget_resets_per_session() {
        val asks = liveLines(service, "MediaProjectionGate.requestConsent(")
        assertEquals("exactly two ask sites, found: $asks", 2, asks.size)
        val notes = liveOffsets(service, "consentBudget.noteAsked()")
        val askOffsets = liveOffsets(service, "MediaProjectionGate.requestConsent(")
        assertEquals("every ask is counted once", 2, notes.size)
        for (i in 0..1) assertTrue("noteAsked precedes ask $i", notes[i] < askOffsets[i])
        val handover = memberBody(service, "    override fun onMediaPlaybackStarted(packageName: String, title: String?) {")
        assertEquals("the handover asks under the budget", 1, liveLines(handover, "consentBudget.mayAsk()").size)
        val input = memberBody(service, "    private fun startAudioInput(): Result<Unit> {")
        assertEquals("startAudioInput hands the budget to the policy", 1,
            liveLines(input, "consentAvailable = consentBudget.mayAsk(),").size)
        val start = memberBody(service, "    private fun startRecording() {")
        assertEquals("a session starts with a fresh budget", 1, liveLines(start, "consentBudget.reset()").size)
        assertEquals("one field", 1, liveLines(service, "private val consentBudget = com.whispereverywhere.audio.ProjectionConsentBudget()").size)
    }

    @Test
    fun a_spent_budget_toasts_once_per_session_not_once_per_media_event() {
        val handover = memberBody(service, "    override fun onMediaPlaybackStarted(packageName: String, title: String?) {")
        assertEquals(1, liveLines(handover, "if (!consentExhaustedToastShown) {").size)
        assertEquals(1, liveLines(handover, "consentExhaustedToastShown = true").size)
        val start = memberBody(service, "    private fun startRecording() {")
        assertEquals(1, liveLines(start, "consentExhaustedToastShown = false").size)
    }
```
Check the anchors by grep before relying on them: `override fun onMediaPlaybackStarted(packageName: String, title: String?) {` (4-space indent), `private fun startAudioInput(): Result<Unit> {`, `private fun startRecording() {`. If a signature differs, use the file's exact line as the anchor and say so in the report.

- [ ] **Step 2: Run to see them fail** — `--tests "com.whispereverywhere.audio.ProjectionConsentBudgetTest" --tests "com.whispereverywhere.audio.AudioSourcePolicyTest" --tests "com.whispereverywhere.service.ConsentBudgetWiringPinTest"`. Expected: compile failure (`ProjectionConsentBudget`, the new parameter).

- [ ] **Step 3: Implement.** Create `ProjectionConsentBudget.kt`:

```kotlin
package com.whispereverywhere.audio

/**
 * How many times ONE recording session may raise the screen-capture consent dialog (4.3.1 D).
 *
 * Why it exists: the dialog's own appearance pauses the video and its dismissal resumes it, so a
 * cancel makes the media detector fire "playback started" again, and the handover asked again —
 * every cancel, forever, unless the user stopped the session first (owner report 2026-09-02).
 * Two asks: the second is the "cancelled by mistake" recovery; the third would be the trap.
 * Counts ASKS launched, not answers, so a dialog the system dismissed still spent one.
 */
class ProjectionConsentBudget(private val maxAsks: Int = MAX_ASKS_PER_SESSION) {
    var asked: Int = 0
        private set

    fun mayAsk(): Boolean = asked < maxAsks
    fun noteAsked() { asked++ }
    fun reset() { asked = 0 }

    companion object {
        const val MAX_ASKS_PER_SESSION = 2
    }
}
```
`AudioSourcePolicy.decide` gains the parameter and one row (keep the KDoc table and add the row):
```kotlin
    fun decide(
        mediaPlaying: Boolean,
        hasProjection: Boolean,
        sdkInt: Int,
        preferDeviceAudio: Boolean,
        consentAvailable: Boolean,
    ): SourceDecision = when {
        !mediaPlaying || !preferDeviceAudio || sdkInt < 29 -> SourceDecision.UseMic
        hasProjection -> SourceDecision.UsePlayback
        //  - media, no token, budget spent -> mic (4.3.1 D: at most two dialogs per session)
        !consentAvailable -> SourceDecision.UseMic
        else -> SourceDecision.RequestConsent
    }
```
Service — fields beside `activeSource`:
```kotlin
    /** 4.3.1 D: the screen-capture dialog may be raised at most twice per session. */
    private val consentBudget = com.whispereverywhere.audio.ProjectionConsentBudget()
    /** One "microphone for this session" toast per session, not one per media event. */
    private var consentExhaustedToastShown = false
```
`startRecording()` — beside `sessionProducedText = false`: `consentBudget.reset()` and `consentExhaustedToastShown = false`.
`startAudioInput()` — pass `consentAvailable = consentBudget.mayAsk(),` to `decide`; in the `RequestConsent ->` arm, immediately before `MediaProjectionGate.requestConsent(this)`: `consentBudget.noteAsked()` and `android.util.Log.i("WE-DIAG", "projection consent: asked=${consentBudget.asked}/${com.whispereverywhere.audio.ProjectionConsentBudget.MAX_ASKS_PER_SESSION}")`.
Handover in `onMediaPlaybackStarted` — replace the `else {` arm that asks with:
```kotlin
                } else if (consentBudget.mayAsk()) {
                    // Flush + stop the mic NOW (never mix room audio into a media session),
                    // then ask; capture starts when consent lands.
                    transcriptionEngine?.let { commitSegment(it, EndpointDiag.SWITCH) }
                    audioRecorder.stop()
                    consentBudget.noteAsked()
                    android.util.Log.i("WE-DIAG", "projection consent: asked=${consentBudget.asked}/${com.whispereverywhere.audio.ProjectionConsentBudget.MAX_ASKS_PER_SESSION}")
                    com.whispereverywhere.audio.MediaProjectionGate.listener = projectionListener
                    com.whispereverywhere.audio.MediaProjectionGate.requestConsent(this@FloatingBubbleService)
                    showToast("Allow screen capture to transcribe device audio")
                } else {
                    // 4.3.1 D: the budget is spent — the session is the microphone's. Say so ONCE;
                    // the video resuming after every cancel would otherwise toast on every resume.
                    if (!consentExhaustedToastShown) {
                        consentExhaustedToastShown = true
                        android.util.Log.i("WE-DIAG", "projection consent: budget spent -> microphone for this session")
                        showToast("Using the microphone for this session — screen capture was declined")
                    }
                }
```
(The mic keeps running in that arm: nothing is stopped.)

- [ ] **Step 4: Run to see them pass**; `assembleDebug` → BUILD SUCCESSFUL.

- [ ] **Step 5: Acceptance sheet §D** — append to `docs/superpowers/sdd/2026-09-02-431-guards-tts/acceptance.md`, in the sheet's own row style with the `[ ] PASS  [ ] FAIL` checkbox on each row:

```markdown
## D — the screen-capture dialog asks at most twice
D1. Device-audio preference ON. Play a YouTube video, tap the bubble to transcribe. The share
    dialog appears: CANCEL. It appears once more (the video resumed): CANCEL again. Expect: no
    third dialog; the toast "Using the microphone for this session — screen capture was
    declined"; transcription continues from the microphone. Grep:
    `Select-String "projection consent:" C:\Users\bastr\.androidbuild\capture-431.txt`
    → `asked=1/2`, `asked=2/2`, `budget spent -> microphone for this session`. FAIL if a third
    dialog appears or the toast repeats.
D2. Same start; CANCEL the first dialog, GRANT the second. Expect device audio captured
    ("Capturing device audio" toast) and the video's words transcribed.
D3. After D1, stop the session (tap the bubble) and tap again with the video still playing.
    Expect the dialog to return (a new session, a fresh budget).
```
Add D1 to the merge-gate footer's row list.

- [ ] **Step 6: Whole suite, then commit** — expected **156 suites / 1,877 tests / 0 failures** from the 154 / 1,870 baseline: +2 suites (`ProjectionConsentBudgetTest`, `ConsentBudgetWiringPinTest`) and +7 tests (3 budget + 2 wiring + 2 policy). Record the exact numbers.

```
fix(capture): the screen-capture consent asks at most twice per session, then the microphone

Every cancel resumed the video and every resume asked again (the handover site had no memory).
ProjectionConsentBudget is reset per session and consulted at both ask sites; AudioSourcePolicy
turns RequestConsent into UseMic when the budget is spent; one toast per session; two WE-DIAG
lines; sheet §D.
```

**Battery:** (1) remove `consentBudget.noteAsked()` from the handover → the wiring pin RED; revert. (2) change `MAX_ASKS_PER_SESSION` to `3` → `the_cap_is_two…` RED; revert. (3) delete the `!consentAvailable -> UseMic` row → `…budget is spent - mic` RED; revert.

---

## Self-review

**Spec coverage.** A: thresholds/ladder as data (T2), stats contract (T2/T3), no-speech at SOT from raw logits (T3), masked per-token log-prob (T3), in-loop entropy (T3), ladder with last-rung cut (T3), scale-unreadable degradation (T3, T2's NaN rule), Kotlin decision + blank → EmptyExpected (T4), grown diag lines (T3 LOGI, T4 `npu:`), pins in the four named test classes (T2–T4), device evidence (T10 §A). B: `hideBubble(reason)` sink + WE-DIAG line + defer (T6), replay rule (T5/T6), `speakFromTrigger` Boolean + reset (T6), pure policy test (T5), a11y classification out of scope (unchanged), device check (T10 §B). C: `shouldStart`/`startDecision` with the three constants (T7), planned/remaining chars (T8), cloud RTF (T8), start-gate-only change with resume untouched (T7 test, T8), `onProgress` total + ticks during the gate (T8), scrubber generating region + seek re-basing (T9), `TTSDIAG start` (T8), device evidence (T10 §C). Release: identity (T1), ledger (T1/T10), bundle build (T10).

**Placeholder scan.** Every code step carries the code; the two "[KEEP … verbatim]" markers in T3 refer to blocks that already exist at the stated lines and must not be retyped (retyping a pinned LOGDIAG is how a diag pin goes red) — that is an instruction, not a placeholder.

**Type consistency.** `NpuDecodeStats` slot names are used identically in T2/T3/T4; `nativeDecodeSegment`'s eleven parameters match between T3's Kotlin declaration, T3's JNI signature and T4's call; `startDecision`/`StartRule`/`rtf()` match between T7 and T8; `onProgress`'s four-argument shape matches T8 (engine), T8 (service pass-through) and T9 (scrubber); `BubbleHidePolicy.decide/replay` match T5 and T6; `TtsRemainingEstimate.ms/samples` match T7, T8 and T9's `ScrubberMath` inputs.
