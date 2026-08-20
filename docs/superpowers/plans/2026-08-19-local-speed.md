# 3.6.0 Implementation Plan — Local Speed: Real-Time Feel for the 190 MB Tiers

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the 190 MB on-device tiers (pro / multi) fast enough that cloud keys feel optional — first visible text within ~5 s under continuous speech, words appearing DURING inference, stops that cost only the true tail work — and capture the on-device numbers (per-segment RTF, GPU/CPU A-B, audio_ctx floor accuracy) that decide the deferred one-download-no-choices UX.

**Architecture:** Eight workstreams over one branch. **A** makes the wall-clock segment cap first-commit-aware (4 s first stretch, 15 s after) and adds the permanent per-segment `segment-timing` RTF line that every later decision reads. **E** removes cold-start tax: a re-prewarm on model switch driven by one prefs-flow mirror, TTS preload at the two cold call sites, an honest "Loading speech model…" CONNECTING label, and the previously-dead FINALIZING ticker. **B** stops multilingual whisper re-detecting the language on every segment by exposing `whisper_full_lang_id` through JNI and pinning the detection for the session. **D** wires whisper.cpp's new-segment callback through JNI so words render on the bubble's delta strip mid-inference — preview-only, with the final-only commit contract untouched. **C** adds canary-validated GPU enablement for multilingual models behind an off-by-default developer toggle. **F** gives the fallback engine's local drain a reserved share of the finalize budget so a near-timeout cloud tail can no longer starve a rescued segment into a silent drop. **G** ships the measurement (pure WER scoring + a bench-only settable `audio_ctx` floor + a per-tier A-B) and lowers the production floor ONLY on a recorded pass. **H** is release identity and the owner's before/after acceptance grid.

**Tech Stack:** Kotlin 2.0.21, AGP 8.13.2, JDK 21 (Android Studio JBR), JUnit4 JVM tests, whisper.cpp via CMake/NDK (`libwhisper_jni.so`), Jetpack Compose + Android Views (bubble), Kotlin coroutines/Flow. No new dependencies.

**Spec (authoritative on any ambiguity):** `docs/superpowers/specs/2026-08-19-local-speed-design.md`

**Branch / baseline:** `3.6.0-local-speed`, HEAD `97ec697`. Every OLD block below was read from the tree at that commit.

---

## Global Constraints

### Execution order (binding)

**A1 → A2 → A3 → A4 → E1 → E2 → E3 → E4 → E5 → E6 → B1 → B2 → B3 → B4 → D1 → D2 → D3 → D4 → D5 → C1 → C2 → C3 → C4 → C5 → C6 → C7 → F1 → F2 → F3 → F4 → G1 → G2 → G3 → C8 → G4 → H1 → H2** (37 tasks).

Task IDs are the original authors' and are NOT renumbered — the ID order is not the execution order. Two deliberate departures from strict alphabetical grouping:

- **C8 runs after G3**, not with the rest of C: both edit `app/src/androidTest/java/com/whispereverywhere/whisper/WhisperBenchTest.kt`, C8 consumes `WerMath` (G1) and reuses the `WerMath` import that G3 adds, and C8's canary assertion needs C4's asset.
- **G4 is gated on owner data** and is expected to be a no-op at plan-execution time. That is by design, not a failure.

**Why A first:** A3/A4's `segment-timing` RTF line is the measurement every later decision gate reads (tier consolidation, GPU default, floor lowering). Landing it first means every subsequent owner build emits it.

Line numbers in OLD-block hints were verified at HEAD `97ec697` and shift as tasks land — **anchor on the quoted text and the named symbol, never on the line number.** Where a task's OLD block depends on an earlier task's edit, the task says so explicitly under **Builds on**.

### Build / test commands (PowerShell, repo root — set JAVA_HOME on every invocation)

- Full JVM suite: `$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon`
- One class: append `--tests "com.whispereverywhere.<pkg>.<Class>"` (repeat `--tests` for several)
- Compile + native: `$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:assembleDebug --no-daemon`
- Instrumented compile (never run): `$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon`

### Output paths (outside the repo — root `build.gradle.kts:17-22` relocates `buildDirectory` to `<localBuildRoot>/<project.name>`)

- Debug APK: `C:\Users\bastr\.androidbuild\WhisperEverywhere\app\outputs\apk\debug\app-debug.apk`
- androidTest APK: `C:\Users\bastr\.androidbuild\WhisperEverywhere\app\outputs\apk\androidTest\debug\app-debug-androidTest.apk`
- JVM test report: `C:\Users\bastr\.androidbuild\WhisperEverywhere\app\reports\tests\testDebugUnitTest\index.html`
- CMake/ninja staging: `C:\Users\bastr\.androidbuild\WhisperEverywhere\cxx-staging` (`app/build.gradle.kts:32`)

**Note the `app\` path segment.** Several source parts wrote `…\WhisperEverywhere\outputs\apk\…`; that path does not exist and is corrected throughout this plan.

### NEVER install via Gradle

**NEVER run `:app:installDebug` or `:app:connectedDebugAndroidTest`.** Both uninstall first and destroy the owner's 500+ MB of downloaded on-device models. Every device install is owner-run and data-preserving:

```
& "C:\Users\bastr\AppData\Local\Android\Sdk\platform-tools\adb.exe" install -r <apk>
```

adb is not on PATH; always call it by that absolute path.

### Untouchables (binding, unchanged from 3.5.0)

- `SegmentOutcome.EmptyExpected` semantics and `FallbackPolicy.reconcile` (both in `app/src/main/java/com/whispereverywhere/transcription/cloud/FallbackTranscriptionEngine.kt`).
- `SegmentOrderer` release rules (`app/src/main/java/com/whispereverywhere/transcription/SegmentOrderer.kt`).
- The `NO_SEGMENT` contract and seq allocation in `LocalWhisperEngine`.
- **The final-only commit contract:** deltas are PREVIEW-ONLY. No delta may reach the external field, the transcript sink, the orderer, or history. Committed text comes exclusively from `onSegmentResolved`.
- The 800 ms pause cut in `SpeechSegmenter` — untouched; `SpeechSegmenterTest` must stay green unmodified.
- Disclosure texts and `CLOUD_DISCLOSURE_KEY`/`CLOUD_DISCLOSURE_VERSION` (no v3→v4 bump; no new permissions, no FGS/Data-Safety change this release).
- The how-to guide's pinned invariants (`HowToGuide.kt` copy + its tests) — E3 touches only the guide card's warm-up, never the copy.
- `best_of=1` is REJECTED; `params.temperature_inc = 0.2f` anti-repetition fallback in `whisper_jni.cpp` stays on.
- Never log transcript content. WE-DIAG lines carry lengths, language codes and numbers only.

### JVM test rules

- TDD throughout: write the failing test, run it, watch the named failure, then implement.
- Tests live under `app/src/test/java/com/whispereverywhere/...`. Compose screens and the two Services have no direct tests by house convention — extract the logic as a pure object/top-level function and pin THAT.
- Concurrency-adjacent tests use a REAL background executor (`Executors.newSingleThreadExecutor()`), shut down in a `finally`. `SameThreadExecutorService` is for deterministic sequencing only.
- Shared fakes (`SameThreadExecutorService`, `QueueingExecutorService`, `FakeWhisperBackend`, `RecordingListener`, `FakeModelPathProvider`) are top-level classes in `app/src/test/java/com/whispereverywhere/transcription/LocalWhisperEngineTest.kt` — reuse them from the same package, never redeclare.
- `LocalWhisperEngineTest` currently holds **18** `@Test` methods; every task that runs it expects all 18 green.
- New `WhisperBackend` members must carry defaults so the 14 existing fakes keep compiling untouched.

### Native / instrumented verification rule

The JVM suite cannot exercise JNI (`WhisperNative` loads the native library) or `androidTest`. For any task marked **NATIVE** or **INSTRUMENTED**, verification is exactly:

1. **Compile:** `:app:assembleDebug` (builds `libwhisper_jni.so` via the `whisper_jni` CMake target, `app/src/main/cpp/CMakeLists.txt:23`) — and `:app:assembleDebugAndroidTest` for instrumented tests.
2. **The full JVM suite still green** (proves nothing else regressed).
3. **A named owner on-device check**, recorded in the H2 acceptance sheet — never claimed as done by the implementer.

A JNI symbol-name typo compiles fine and fails only at runtime, so every new `Java_com_whispereverywhere_whisper_WhisperNative_*` symbol below was copied from the existing pattern character-for-character.

### Commit trailer (exact, every commit)

Every commit message ends with exactly these two lines, appended via a second `-m` (PowerShell backtick-n = newline):

```
Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT
```

```powershell
git commit -m "<headline>" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`nClaude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT"
```

### Copy discipline

No cloud speed claims. No absolute latency numbers in user-facing copy. Every comparative claim is our-own-before/after on the same device, and says so.

### Shared contracts (load-bearing exact names — identical at every mention)

| Contract | Exact signature | Owner | Consumers |
|---|---|---|---|
| Segment cap policy | `class SegmentCapPolicy(firstSegmentCapMs: Long = FIRST_SEGMENT_WALL_MS, laterSegmentCapMs: Long = MAX_SEGMENT_WALL_MS)` with `onSessionStart(nowMs: Long)`, `onCommit(nowMs: Long)`, `currentCapMs(): Long`, `capExceeded(nowMs: Long): Boolean`; companion `FIRST_SEGMENT_WALL_MS = 4_000L`, `MAX_SEGMENT_WALL_MS = 15_000L`. Package `com.whispereverywhere.service`. | A1 | A2 |
| RTF line | `object SegmentTiming` with `SAMPLE_RATE_HZ = 16_000`, `audioMs(sampleCount: Int, sampleRateHz: Int = SAMPLE_RATE_HZ): Long`, `line(audioMs: Long, transcribeMs: Long): String` → `"segment-timing: audio=<ms> transcribe=<ms> rtf=<x.xx>"`. Package `com.whispereverywhere.transcription`. | A3 | A4, H2 |
| Re-prewarm | `LocalWhisperEngine.prewarmModelSwitch()` — **this exact name at every mention** (not `prewarmOnModelSwitch`) | E1 | E2 |
| Warm flag | `LocalWhisperEngine.isWarm(): Boolean` | E4 | E5 |
| CONNECTING label | `internal fun connectingStatusLabel(isCloudSession: Boolean, localEngineWarm: Boolean): String?` (top-level in `FloatingBubbleService.kt`) | E4 | E5 |
| Ticker states | `internal fun processingTimerRunsIn(state: FloatingBubbleService.BubbleState): Boolean` (top-level in `FloatingBubbleService.kt`) | E6 | E6 |
| Prefs mirror | `PreferencesManager.selectedModelIdFlow: StateFlow<String?>` backing the unchanged `var selectedModelId: String?` | E2 | E2 |
| Install signal | `PreferencesManager.modelInstalled: SharedFlow<Unit>` (backed by `MutableSharedFlow<Unit>(extraBufferCapacity = 1)`) + `PreferencesManager.notifyModelInstalled()`, called from `WhisperModelManager.verifyDest`'s success tail — the non-conflating companion to the prefs mirror | E2 | E2 |
| Language detection (JNI) | `WhisperNative.detectedLanguage(ctxPtr: Long): String?` | B1 | B2 |
| Language detection (seam) | `WhisperBackend.detectedLanguage(ctx: Long): String? = null` | B2 | B4 |
| Language pin | `class LanguagePin` with `languageFor(sessionLanguage: String?): String?`, `onDetected(sessionLanguage: String?, detected: String?)`, `reset()`. Package `com.whispereverywhere.transcription`. Engine field name `languagePin`. | B3 | B4 |
| Streaming callback (JNI) | `fun interface WhisperNative.NewSegmentCallback { fun onRunningText(textUtf8: ByteArray) }`; `WhisperNative.transcribeRaw(..., callback: NewSegmentCallback?)`; `WhisperNative.transcribe(..., onNewSegment: ((String) -> Unit)? = null)` | D1 | D2, C8 |
| Streaming (seam) | `WhisperBackend.transcribeStreaming(ctx: Long, samples: FloatArray, lang: String?, useVad: Boolean = true, onNewSegment: (String) -> Unit): String` — default delegates to `transcribe` | D2 | D4 |
| Delta throttle | `class DeltaThrottle(minIntervalMs: Long = 150L, now: () -> Long = System::currentTimeMillis)` with `shouldEmit(): Boolean`, `reset()`. Engine field `deltaThrottle`, constructor param `deltaClock: () -> Long`. | D3 | D4 |
| GPU canary (pure) | `object GpuCanaryPolicy` with `EXPECTED_TOKENS: List<Set<String>>` (one alias set per spoken position — word AND numeral form), `MIN_MATCHES: Int`, `canaryPasses(text: String): Boolean`, `normalize(text: String): List<String>`. Package `com.whispereverywhere.transcription`. | C1 | C5, C8 |
| GPU canary latch | `GpuPolicy.needsCanary(): Boolean` / `GpuPolicy.onCanaryResult(passed: Boolean)` — the verdict is persisted per (versionCode, model) in the existing `gpu_policy` prefs file; reading it back is a PRIVATE helper (`canaryVerdict(p, vc, m)`), not part of this contract | C3 | C5 |
| Experiment toggle | `PreferencesManager.gpuMultilingualExperiment: StateFlow<Boolean>` + `setGpuMultilingualExperiment(enabled: Boolean)` + `isGpuMultilingualExperimentEnabled(): Boolean` (key `"gpu_multilingual_experiment"`, default **false**) — the `vibrationEnabled` pattern | C2 | C3, C6 |
| Drain reserve | `internal fun localDrainReserveMs(budgetMs: Long): Long` = `min(60_000, budget/5)`, top-level in `FallbackTranscriptionEngine.kt` | F1 | F2, F3, F4 |
| WER scoring | `object WerMath` with `FLOOR_WER_GATE = 0.10`, `tokens(text: String): List<String>`, `wer(reference: String, hypothesis: String): Double`, `floorQualifies(wers: List<Double>): Boolean`. Package `com.whispereverywhere.util`. | G1 | G3, C8 |
| Bench floor knob | `WhisperNative.setAudioCtxFloor(floor: Int)` (bench-only) + native `g_audio_ctx_floor` (default 768) | G2 | G3, G4 |

### Shared-file edit order (seams)

Ten files are edited by several workstreams. Each task states which prior task's edits its OLD block assumes.

| File | Edited by (in execution order) |
|---|---|
| `app/src/main/cpp/whisper_jni.cpp` | B1 → D1 → G2 → (G4, gated) |
| `app/src/main/java/com/whispereverywhere/whisper/WhisperNative.kt` | B1 → D1 → G2 |
| `app/src/main/java/com/whispereverywhere/transcription/LocalWhisperEngine.kt` | A4 → E1 → E4 → B4 → D4 |
| `app/src/main/java/com/whispereverywhere/transcription/TranscriptionEngine.kt` | B2 → D2 → D4 → C5 |
| `app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt` | A2 → E2 → E4 → E5 → E6 → D4 → F4 |
| `app/src/main/java/com/whispereverywhere/data/local/PreferencesManager.kt` | E2 → C2 |
| `app/src/main/java/com/whispereverywhere/transcription/cloud/FallbackTranscriptionEngine.kt` | F1 → F2 → F3 |
| `app/src/test/java/com/whispereverywhere/transcription/cloud/FallbackTranscriptionEngineTest.kt` | F1 → F2 → F3 |
| `app/src/test/java/com/whispereverywhere/transcription/WhisperBackendSeamTest.kt` | B2 → D2 |
| `app/src/androidTest/java/com/whispereverywhere/whisper/WhisperBenchTest.kt` | G3 → C8 → (G4, gated) |

---

## Workstream A — Segmentation latency (the 15-second wall)

### Task A1: `SegmentCapPolicy` — the first-commit-aware wall cap as a pure JVM object

**Files:**
- Create: `app/src/main/java/com/whispereverywhere/service/SegmentCapPolicy.kt`
- Create: `app/src/test/java/com/whispereverywhere/service/SegmentCapPolicyTest.kt`

Spec anchor: Workstream A1/A4 — the session's FIRST uncommitted stretch cuts at 4 000 ms, every later stretch keeps the existing 15 000 ms cap, state resets per session. The 800 ms pause cut (`SpeechSegmenter`) is untouched and still wins when a real pause happens; this object only answers "has the CURRENT uncommitted stretch outlived its cap?".

**Step 1 — Write the failing test**

- [ ] Write `app/src/test/java/com/whispereverywhere/service/SegmentCapPolicyTest.kt` exactly:

```kotlin
package com.whispereverywhere.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentCapPolicyTest {

    @Test
    fun shippedCapsAre4And15Seconds() {
        assertEquals(4_000L, SegmentCapPolicy.FIRST_SEGMENT_WALL_MS)
        assertEquals(15_000L, SegmentCapPolicy.MAX_SEGMENT_WALL_MS)
        val policy = SegmentCapPolicy()
        policy.onSessionStart(nowMs = 0L)
        assertEquals(4_000L, policy.currentCapMs())
    }

    @Test
    fun firstUncommittedStretchCapsAt4000ms() {
        val policy = SegmentCapPolicy()
        policy.onSessionStart(nowMs = 10_000L)
        assertFalse("one ms under the first cap must not cut", policy.capExceeded(nowMs = 13_999L))
        assertTrue("the first cap fires at exactly 4 000 ms", policy.capExceeded(nowMs = 14_000L))
    }

    @Test
    fun laterStretchesKeepThe15000msCap() {
        val policy = SegmentCapPolicy()
        policy.onSessionStart(nowMs = 0L)
        policy.onCommit(nowMs = 4_000L)          // the first wall-cap commit
        assertEquals(15_000L, policy.currentCapMs())
        assertFalse(policy.capExceeded(nowMs = 18_999L))
        assertTrue(policy.capExceeded(nowMs = 19_000L))
    }

    @Test
    fun aPauseCommitAlsoEndsTheFirstCapWindow() {
        // The 800 ms pause cut still wins when a real pause happens (untouched semantics);
        // once ANY commit has cut the first segment, the later cap governs the next stretch.
        val policy = SegmentCapPolicy()
        policy.onSessionStart(nowMs = 0L)
        policy.onCommit(nowMs = 1_200L)          // VAD pause commit, well before 4 s
        assertEquals(15_000L, policy.currentCapMs())
        assertFalse("the 4 s cap must NOT fire on the second stretch", policy.capExceeded(nowMs = 5_000L))
        assertTrue(policy.capExceeded(nowMs = 16_200L))
    }

    @Test
    fun everyCommitRestartsTheClock() {
        val policy = SegmentCapPolicy()
        policy.onSessionStart(nowMs = 0L)
        policy.onCommit(nowMs = 4_000L)
        policy.onCommit(nowMs = 19_000L)
        assertFalse(policy.capExceeded(nowMs = 33_999L))
        assertTrue(policy.capExceeded(nowMs = 34_000L))
    }

    @Test
    fun aNewSessionResetsToTheFirstCap() {
        // Per-session reset (the RECORDING anchor at FloatingBubbleService onOpen): session 2's
        // first segment gets the 4 s cap again, measured from ITS start.
        val policy = SegmentCapPolicy()
        policy.onSessionStart(nowMs = 0L)
        policy.onCommit(nowMs = 4_000L)
        policy.onCommit(nowMs = 19_000L)
        policy.onSessionStart(nowMs = 60_000L)
        assertEquals(4_000L, policy.currentCapMs())
        assertFalse(policy.capExceeded(nowMs = 63_999L))
        assertTrue(policy.capExceeded(nowMs = 64_000L))
    }

    @Test
    fun capsAreInjectableForTests() {
        val policy = SegmentCapPolicy(firstSegmentCapMs = 100L, laterSegmentCapMs = 200L)
        policy.onSessionStart(nowMs = 0L)
        assertTrue(policy.capExceeded(nowMs = 100L))
        policy.onCommit(nowMs = 100L)
        assertFalse(policy.capExceeded(nowMs = 299L))
        assertTrue(policy.capExceeded(nowMs = 300L))
    }
}
```

**Step 2 — Run it, watch it fail**

- [ ] Run:
```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon --tests "com.whispereverywhere.service.SegmentCapPolicyTest"
```
Expected failure: `> Task :app:compileDebugUnitTestKotlin FAILED` with `Unresolved reference 'SegmentCapPolicy'` (the class does not exist yet).

**Step 3 — Implement**

- [ ] Write `app/src/main/java/com/whispereverywhere/service/SegmentCapPolicy.kt` exactly:

```kotlin
package com.whispereverywhere.service

/**
 * The wall-clock commit cap for one recording session (3.6.0, Workstream A).
 *
 * Continuous loud audio (media playback, music, uninterrupted speech) never dips below the
 * segmenter's silence floor, so the 800 ms pause cut never fires — the wall cap is what bounds an
 * uncommitted stretch. Before 3.6.0 that bound was a flat 15 s, which meant ~15 s + a full-segment
 * inference before the FIRST visible text (owner report: ~17 s on multi). The fix: the session's
 * FIRST stretch cuts at [FIRST_SEGMENT_WALL_MS] (4 s) so first text lands fast; every LATER
 * stretch keeps the pre-existing [MAX_SEGMENT_WALL_MS] (15 s). The pause cut is untouched and
 * still wins whenever a real pause happens — any commit, whatever cut it, ends the first-cap
 * window and restarts the clock.
 *
 * Pure and Compose/Context-free so the first-vs-later rule and the per-session reset are
 * JVM-pinned (SegmentCapPolicyTest). Threading: [onSessionStart]/[onCommit] are called from Main
 * (session open, switchSource) AND the audio-capture thread (per-chunk VAD path), same as the
 * plain `lastCommitWallMs` long this replaces. Fields are @Volatile; the two writes in [onCommit]
 * are not atomic together, but a torn observation costs at most one ~32 ms audio chunk of cap
 * slack — the exact tolerance the old field had.
 */
class SegmentCapPolicy(
    private val firstSegmentCapMs: Long = FIRST_SEGMENT_WALL_MS,
    private val laterSegmentCapMs: Long = MAX_SEGMENT_WALL_MS,
) {
    @Volatile private var anchorMs = 0L
    @Volatile private var firstCommitDone = false

    /** RECORDING start: the cap clock restarts and the FIRST-segment cap applies again. */
    fun onSessionStart(nowMs: Long) {
        anchorMs = nowMs
        firstCommitDone = false
    }

    /** Any commit — pause cut, wall cap, source switch — restarts the clock; later caps apply. */
    fun onCommit(nowMs: Long) {
        anchorMs = nowMs
        firstCommitDone = true
    }

    /** The cap currently in force (first vs later), for the WE-DIAG line. */
    fun currentCapMs(): Long = if (firstCommitDone) laterSegmentCapMs else firstSegmentCapMs

    /** True when the current uncommitted stretch has outlived its cap. */
    fun capExceeded(nowMs: Long): Boolean = nowMs - anchorMs >= currentCapMs()

    companion object {
        /**
         * The session's first commit: 4 s, so first visible text under continuous speech is
         * ~4 s + one short segment's inference instead of 15 s + a long one (spec A1). Free
         * consequence (spec A2): a short first cap also shrinks the stop-tap tail for short
         * sessions — the buffer holds at most this much never-transcribed audio until then.
         */
        const val FIRST_SEGMENT_WALL_MS = 4_000L

        /** Every later uncommitted stretch keeps the pre-3.6.0 cap, byte-identical semantics. */
        const val MAX_SEGMENT_WALL_MS = 15_000L
    }
}
```

**Step 4 — Run green**

- [ ] Run:
```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon --tests "com.whispereverywhere.service.SegmentCapPolicyTest"
```
Expected: `BUILD SUCCESSFUL`, all 7 `SegmentCapPolicyTest` tests pass.

**Step 5 — Commit**

- [ ] Run:
```powershell
git add app/src/main/java/com/whispereverywhere/service/SegmentCapPolicy.kt app/src/test/java/com/whispereverywhere/service/SegmentCapPolicyTest.kt
git commit -m "feat(bubble): SegmentCapPolicy - first commit caps at 4s, later at 15s, reset per session (A1)" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`nClaude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT"
```

---

### Task A2: Wire `SegmentCapPolicy` into `FloatingBubbleService` (replace `MAX_SEGMENT_WALL_MS`/`lastCommitWallMs` at all four sites)

**Files:**
- Edit: `app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt`

**Builds on:** A1. First edit to this file — every OLD block below matches HEAD `97ec697` verbatim.

`lastCommitWallMs` has exactly four live sites (declaration ~:272-276, the per-chunk VAD/cap block ~:1603-1615, `switchSource` ~:1706-1711, the RECORDING anchor in `onOpen` ~:2086-2090) — all replaced here; nothing else references it (verified by grep). The policy logic itself is already pinned by A1's tests; this task is Android-Service wiring, which the JVM suite cannot execute directly — verification is the untouched full suite (including `SpeechSegmenterTest`, which pins the pause semantics the spec declares untouchable) plus a clean `assembleDebug`, feeding the owner's on-device WE-DIAG check.

**The 4 s first cap is LOCAL-ONLY, and the gate is at the CALL SITE.** The cap block runs whenever `LiveTurnPolicy.runClientVad(sessionIsLive)` is true, which includes `CLOUD_WITH_FALLBACK` (only `CLOUD_LIVE` sets `sessionIsLive = true`) — so an ungated 4 s first cap would also cut the first *cloud batch* segment at 4 s instead of 15 s: one extra provider round-trip, one extra `FallbackTranscriptionEngine` mirror, and one extra **billable** request on every short cloud session, for no visible gain (a cloud session's first-text wait is the network, not inference). Cloud sessions therefore keep the pre-existing 15 s cap for EVERY segment. `SegmentCapPolicy` stays pure and unchanged (no `firstCapEligible` parameter, no new constructor argument, A1's seven tests untouched) — the gate is one line at the session-open call site in Step 4: a cloud session closes the first-cap window immediately after `onSessionStart`, which is exactly the "any commit ends the first-cap window" rule A1 already pins.

**Step 1 — Replace the field pair**

- [ ] In `FloatingBubbleService.kt`, replace this OLD block (~:272):

```kotlin
    // Wall-clock cap per uncommitted stretch. Continuous loud audio (media playback, music) never
    // dips below the segmenter's silence floor, so its pause-based commit never fires — without
    // this cap the engine buffer grows unbounded and produces one giant end-of-session segment.
    private val MAX_SEGMENT_WALL_MS = 15_000L
    private var lastCommitWallMs = 0L
```

with:

```kotlin
    // Wall-clock cap per uncommitted stretch. Continuous loud audio (media playback, music) never
    // dips below the segmenter's silence floor, so its pause-based commit never fires — without
    // this cap the engine buffer grows unbounded and produces one giant end-of-session segment.
    // 3.6.0 (Workstream A): the cap is first-commit-aware — the session's FIRST stretch cuts at
    // 4 s so first visible text lands fast under continuous speech; every later stretch keeps the
    // old 15 s. The 800 ms pause cut is untouched and still wins when a real pause happens.
    // First-vs-later rule and per-session reset are JVM-pinned in SegmentCapPolicyTest.
    private val segmentCapPolicy = SegmentCapPolicy()
```

**Step 2 — Replace the per-chunk VAD/cap block**

- [ ] Replace this OLD block (inside `onAudioChunk`, ~:1603):

```kotlin
        if (com.whispereverywhere.transcription.live.LiveTurnPolicy.runClientVad(sessionIsLive)) {
            val now = System.currentTimeMillis()
            if (speechSegmenter.onAmplitude(amp, now)) {
                android.util.Log.i("WE-DIAG", "VAD -> commit (rms=$amp)")
                lastCommitWallMs = now
                engine.commit()
            } else if (now - lastCommitWallMs >= MAX_SEGMENT_WALL_MS) {
                android.util.Log.i("WE-DIAG", "wall-clock cap -> commit")
                lastCommitWallMs = now
                engine.commit()
                speechSegmenter.reset()
            }
        }
```

with:

```kotlin
        if (com.whispereverywhere.transcription.live.LiveTurnPolicy.runClientVad(sessionIsLive)) {
            val now = System.currentTimeMillis()
            if (speechSegmenter.onAmplitude(amp, now)) {
                android.util.Log.i("WE-DIAG", "VAD -> commit (rms=$amp)")
                segmentCapPolicy.onCommit(now)
                engine.commit()
            } else if (segmentCapPolicy.capExceeded(now)) {
                // currentCapMs() is read BEFORE onCommit flips first->later, so the line names
                // the cap that actually fired (4000ms for the session's first LOCAL stretch;
                // cloud sessions closed the first-cap window at onOpen and always read 15000ms).
                android.util.Log.i(
                    "WE-DIAG",
                    "wall-clock cap -> commit (cap=${segmentCapPolicy.currentCapMs()}ms)",
                )
                segmentCapPolicy.onCommit(now)
                engine.commit()
                speechSegmenter.reset()
            }
        }
```

**Step 3 — Replace the switchSource anchor** (disambiguated from the onOpen pair by the `transcriptionEngine?.commit()` line above it)

- [ ] Replace this OLD block (inside `switchSource`, ~:1706):

```kotlin
        // One engine serves both sources, so this commit is what keeps mic and device audio in
        // SEPARATE segments at the boundary. In a live (server-driven) session it cuts a client
        // turn mid-stream — the same mechanism stopRecording's tail commit uses.
        transcriptionEngine?.commit()
        speechSegmenter.reset()
        lastCommitWallMs = System.currentTimeMillis()
```

with:

```kotlin
        // One engine serves both sources, so this commit is what keeps mic and device audio in
        // SEPARATE segments at the boundary. In a live (server-driven) session it cuts a client
        // turn mid-stream — the same mechanism stopRecording's tail commit uses.
        transcriptionEngine?.commit()
        speechSegmenter.reset()
        segmentCapPolicy.onCommit(System.currentTimeMillis())
```

**Step 4 — Replace the RECORDING anchor** (disambiguated from the switchSource pair by the CONNECTING guard above it) — this is also where the LOCAL-ONLY gate lives

- [ ] Replace this OLD block (inside `onOpen`, ~:2086):

```kotlin
                serviceScope.launch(Dispatchers.Main) {
                    if (currentState != BubbleState.CONNECTING) return@launch
                    speechSegmenter.reset()
                    lastCommitWallMs = System.currentTimeMillis()
                    val started = startAudioInput()
```

with:

```kotlin
                serviceScope.launch(Dispatchers.Main) {
                    if (currentState != BubbleState.CONNECTING) return@launch
                    speechSegmenter.reset()
                    // Per-session reset: the FIRST-segment 4 s cap applies again from here.
                    val sessionStartMs = System.currentTimeMillis()
                    segmentCapPolicy.onSessionStart(sessionStartMs)
                    // 3.6.0 (Workstream A) — the 4 s first cap is LOCAL-ONLY. This VAD/cap path
                    // also runs for CLOUD_WITH_FALLBACK (runClientVad is true for it; only
                    // CLOUD_LIVE sets sessionIsLive), and there an extra first segment means an
                    // extra provider round-trip, an extra fallback mirror and an extra BILLABLE
                    // request — while the user's first-text wait is the network, not inference.
                    // Closing the first-cap window immediately (the same "any commit ends it"
                    // rule SegmentCapPolicyTest pins) leaves cloud sessions on the pre-existing
                    // 15 s cap for every segment: byte-identical to 3.5.0. cloudWrapper is
                    // already resolved here — resolveTranscriptionEngine() ran in startRecording,
                    // and it is the same cloud predicate stopRecording uses.
                    if (cloudWrapper != null) segmentCapPolicy.onCommit(sessionStartMs)
                    val started = startAudioInput()
```

**Step 5 — Verify**

- [ ] Run the full suite (pins `SpeechSegmenterTest` pause semantics unchanged, `SegmentCapPolicyTest` green):
```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon
```
Expected: `BUILD SUCCESSFUL`, zero failures.
- [ ] Build:
```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:assembleDebug --no-daemon
```
Expected: `BUILD SUCCESSFUL` (APK lands at `C:\Users\bastr\.androidbuild\WhisperEverywhere\app\outputs\apk\debug\app-debug.apk`). NEVER `:app:installDebug` / `:app:connectedDebugAndroidTest`.
- [ ] Owner on-device check (record in the H2 acceptance sheet, not automated): in a LOCAL session, speak continuously — logcat shows `wall-clock cap -> commit (cap=4000ms)` ~4 s in, then `(cap=15000ms)` for later cuts; a session with an early real pause shows `VAD -> commit` first and no 4 s cut afterward. In a CLOUD BATCH session the same continuous speech must show `(cap=15000ms)` on the FIRST cut too — that is the local-only gate proving itself (a `cap=4000ms` line in a cloud session is a bug: it costs an extra billable request).

**Step 6 — Commit**

- [ ] Run:
```powershell
git add app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt
git commit -m "feat(bubble): first segment cuts at 4s - wall cap is now SegmentCapPolicy at all four sites (A2)" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`nClaude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT"
```

---

### Task A3: `SegmentTiming` — the permanent per-segment RTF line, format JVM-pinned

**Files:**
- Create: `app/src/main/java/com/whispereverywhere/transcription/SegmentTiming.kt`
- Create: `app/src/test/java/com/whispereverywhere/transcription/SegmentTimingTest.kt`

Spec anchor: Workstream A3 — one WE-DIAG line per segment, `"segment-timing: audio=<ms> transcribe=<ms> rtf=<x.xx>"`, converting multi's ESTIMATED RTF into MEASURED owner-device data. The exact greppable format is what future reports and the decision gates depend on, so it is pinned pure.

**Step 1 — Write the failing test**

- [ ] Write `app/src/test/java/com/whispereverywhere/transcription/SegmentTimingTest.kt` exactly:

```kotlin
package com.whispereverywhere.transcription

import org.junit.Assert.assertEquals
import org.junit.Test

class SegmentTimingTest {

    @Test
    fun lineMatchesTheGreppableFormatExactly() {
        assertEquals(
            "segment-timing: audio=4000 transcribe=6000 rtf=1.50",
            SegmentTiming.line(audioMs = 4_000L, transcribeMs = 6_000L),
        )
    }

    @Test
    fun rtfBelowOneMeansFasterThanRealTime() {
        assertEquals(
            "segment-timing: audio=15000 transcribe=3000 rtf=0.20",
            SegmentTiming.line(audioMs = 15_000L, transcribeMs = 3_000L),
        )
    }

    @Test
    fun rtfRoundsToTwoDecimals() {
        assertEquals(
            "segment-timing: audio=3000 transcribe=1000 rtf=0.33",
            SegmentTiming.line(audioMs = 3_000L, transcribeMs = 1_000L),
        )
    }

    @Test
    fun zeroAudioNeverDividesByZero() {
        // A degenerate commit (near-zero samples) must report a parseable line, not NaN/crash.
        assertEquals(
            "segment-timing: audio=0 transcribe=500 rtf=0.00",
            SegmentTiming.line(audioMs = 0L, transcribeMs = 500L),
        )
    }

    @Test
    fun audioMsConvertsSampleCountAt16kHz() {
        assertEquals(4_000L, SegmentTiming.audioMs(sampleCount = 64_000))
        assertEquals(1_000L, SegmentTiming.audioMs(sampleCount = 16_000))
        assertEquals(0L, SegmentTiming.audioMs(sampleCount = 0))
    }
}
```

**Step 2 — Run it, watch it fail**

- [ ] Run:
```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon --tests "com.whispereverywhere.transcription.SegmentTimingTest"
```
Expected failure: `> Task :app:compileDebugUnitTestKotlin FAILED` with `Unresolved reference 'SegmentTiming'`.

**Step 3 — Implement**

- [ ] Write `app/src/main/java/com/whispereverywhere/transcription/SegmentTiming.kt` exactly:

```kotlin
package com.whispereverywhere.transcription

import java.util.Locale

/**
 * The permanent per-segment RTF diagnostic (3.6.0, Workstream A3).
 *
 * One line per resolved local segment, emitted by [LocalWhisperEngine.runSegment] around
 * `backend.transcribe`:
 *
 *     segment-timing: audio=<ms> transcribe=<ms> rtf=<x.xx>
 *
 * rtf = transcribe/audio — RTF < 1 means faster than real time. This converts the deep-analysis
 * report's last big ESTIMATE (the multi tier's real RTF on the owner's device) into MEASURED
 * data, and is what the tier-consolidation and GPU-default decision gates read (spec Decision
 * Gates 1-2). Pure so the exact greppable format is JVM-pinned (SegmentTimingTest) — a format
 * drift would silently break every future report that parses it.
 *
 * READ THE DENOMINATOR LITERALLY: [audioMs] is the PRE-VAD wall-clock duration of the committed
 * buffer (every sample the user's segment held, silence included), while the numerator is the
 * whole transcribe call — native VAD trimming, encode, decode AND any retry attempts. So this is
 * a speech-in/compute-out ratio for one committed segment, NOT a pure model RTF: it is
 * deliberately the wall cost the user actually paid, which is the number the decision gates want.
 *
 * Content discipline: numbers only, NEVER transcript text — logcat is readable by adb/other
 * tooling and the product promise is that transcriptions stay on-device.
 */
object SegmentTiming {

    /** PCM16 mono sample rate the entire capture pipeline runs at. */
    const val SAMPLE_RATE_HZ = 16_000

    /** Audio duration in ms for [sampleCount] float samples at [sampleRateHz]. */
    fun audioMs(sampleCount: Int, sampleRateHz: Int = SAMPLE_RATE_HZ): Long =
        sampleCount * 1000L / sampleRateHz

    /**
     * The line itself. A zero/negative [audioMs] (degenerate commit) reports rtf=0.00 instead of
     * dividing by zero. Locale.US so the decimal separator is always a point, never a comma.
     */
    fun line(audioMs: Long, transcribeMs: Long): String {
        val rtf = if (audioMs > 0) transcribeMs.toDouble() / audioMs.toDouble() else 0.0
        return "segment-timing: audio=$audioMs transcribe=$transcribeMs rtf=" +
            String.format(Locale.US, "%.2f", rtf)
    }
}
```

**Step 4 — Run green**

- [ ] Run:
```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon --tests "com.whispereverywhere.transcription.SegmentTimingTest"
```
Expected: `BUILD SUCCESSFUL`, all 5 `SegmentTimingTest` tests pass.

**Step 5 — Commit**

- [ ] Run:
```powershell
git add app/src/main/java/com/whispereverywhere/transcription/SegmentTiming.kt app/src/test/java/com/whispereverywhere/transcription/SegmentTimingTest.kt
git commit -m "feat(diag): SegmentTiming - the pinned segment-timing RTF line format (A3)" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`nClaude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT"
```

---

### Task A4: Emit the RTF line in `LocalWhisperEngine.runSegment` around `backend.transcribe`

**Files:**
- Edit: `app/src/main/java/com/whispereverywhere/transcription/LocalWhisperEngine.kt`

**Builds on:** A3. First edit to this file — the OLD block matches HEAD `97ec697` verbatim (`LocalWhisperEngine.kt:259-263`).

The line's content is pinned by A3; this wires the timing capture around the one `backend.transcribe` call. The wiring itself cannot be asserted from the JVM suite (log output goes through the `android.util.Log` stub), so verification is `LocalWhisperEngineTest` staying green (proves no behavioral change to segment resolution) + `assembleDebug` + the owner's on-device grep.

**Step 1 — Wire the timing**

- [ ] In `LocalWhisperEngine.kt`, replace this OLD block (inside `runSegment`, ~:259):

```kotlin
                val samples = AudioMath.pcm16ToFloat(pcm)
                android.util.Log.i("WE-DIAG", "transcribe START seq=$seq samples=${samples.size} lang=$lang")
                val text = runBlocking {
                    retry.retry { backend.transcribe(ctx, samples, lang) }
                }
```

with:

```kotlin
                val samples = AudioMath.pcm16ToFloat(pcm)
                android.util.Log.i("WE-DIAG", "transcribe START seq=$seq samples=${samples.size} lang=$lang")
                val transcribeStartNs = System.nanoTime()
                val text = runBlocking {
                    retry.retry { backend.transcribe(ctx, samples, lang) }
                }
                // Permanent per-segment RTF instrumentation (3.6.0, Workstream A3): the number
                // the tier-consolidation and GPU decision gates read, measured on the owner's
                // device instead of estimated. Includes retry time deliberately — it is the wall
                // cost the user actually paid for this segment. Numbers only, never transcript
                // content. Grep "segment-timing:".
                android.util.Log.i(
                    "WE-DIAG",
                    SegmentTiming.line(
                        audioMs = SegmentTiming.audioMs(samples.size),
                        transcribeMs = (System.nanoTime() - transcribeStartNs) / 1_000_000,
                    ),
                )
```

**Step 2 — Verify**

- [ ] Run:
```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon --tests "com.whispereverywhere.transcription.LocalWhisperEngineTest"
```
Expected: `BUILD SUCCESSFUL`, all 18 `LocalWhisperEngineTest` tests still pass (byte-identical outcomes; the new line is log-only).
- [ ] Build:
```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:assembleDebug --no-daemon
```
Expected: `BUILD SUCCESSFUL`.
- [ ] Owner on-device check (feeds H2 acceptance: RTF captured per tier): `adb logcat -s WE-DIAG | findstr segment-timing` shows one line per segment on both pro and multi, e.g. `segment-timing: audio=4000 transcribe=5200 rtf=1.30`.

**Step 3 — Commit**

- [ ] Run:
```powershell
git add app/src/main/java/com/whispereverywhere/transcription/LocalWhisperEngine.kt
git commit -m "feat(diag): per-segment segment-timing RTF line around backend.transcribe (A4)" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`nClaude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT"
```

---

## Workstream E — Warm paths & honest waits

### Task E1: `LocalWhisperEngine.prewarmModelSwitch()` — a prewarm that also handles a full slot

**Files:**
- Edit: `app/src/main/java/com/whispereverywhere/transcription/LocalWhisperEngine.kt`
- Create: `app/src/test/java/com/whispereverywhere/transcription/LocalWhisperEngineWarmPathTest.kt`

**Builds on:** A4 (same file). A4 edited only the transcribe block INSIDE `runSegment`; the `prewarm()` function quoted below is untouched by it and still matches HEAD verbatim.

Spec anchor: Workstream E1 — `prewarm()` deliberately only fills an EMPTY slot (`LocalWhisperEngine.kt:333`), so after a tier switch the stale context sits loaded and the next session pays the ~7 s release+load inline in CONNECTING. This adds the engine half of the fix; E2 adds the one shared trigger. Reuses the shared test fakes (`FakeWhisperBackend`, `SameThreadExecutorService`, `RecordingListener` — all top-level in `LocalWhisperEngineTest.kt`, same package). The real-executor test uses `Executors.newSingleThreadExecutor()` shut down in `finally`, per house concurrency rules.

**Step 1 — Write the failing test**

- [ ] Write `app/src/test/java/com/whispereverywhere/transcription/LocalWhisperEngineWarmPathTest.kt` exactly:

```kotlin
package com.whispereverywhere.transcription

import com.whispereverywhere.util.RetryPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors

/** installedModelPath() the test can repoint mid-test — the on-disk model switches in production. */
class SwitchableModelPathProvider(var path: String?) : ModelPathProvider {
    override fun installedModelPath(): String? = path
}

class LocalWhisperEngineWarmPathTest {

    private fun fastRetry() = RetryPolicy(maxAttempts = 3, baseDelayMs = 0, maxDelayMs = 0, rng = { 0.0 })

    private fun engineWith(provider: ModelPathProvider, backend: WhisperBackend) =
        LocalWhisperEngine(
            modelPathProvider = provider,
            retry = fastRetry(),
            backend = backend,
            executor = SameThreadExecutorService(),
        )

    // ===== prewarmModelSwitch(): the re-prewarm a tier switch needs (Workstream E1) =====

    @Test
    fun prewarmModelSwitch_afterATierSwitch_releasesTheOldContextAndLoadsTheNew() {
        val provider = SwitchableModelPathProvider("/models/pro.bin")
        val backend = FakeWhisperBackend()
        val engine = engineWith(provider, backend)
        engine.prewarm()
        assertEquals(listOf("/models/pro.bin"), backend.loadCalls)

        provider.path = "/models/multi.bin"     // the user switched tiers
        engine.prewarmModelSwitch()

        assertEquals("the stale context must be freed exactly once", 1, backend.releaseCalls)
        assertEquals(listOf("/models/pro.bin", "/models/multi.bin"), backend.loadCalls)
    }

    @Test
    fun prewarmModelSwitch_whenTheLoadedModelIsCurrent_doesNothing() {
        // e.g. OnboardingSetupViewModel's self-heal rewrite of the SAME id: no release, no reload.
        val provider = SwitchableModelPathProvider("/models/pro.bin")
        val backend = FakeWhisperBackend()
        val engine = engineWith(provider, backend)
        engine.prewarm()
        engine.prewarmModelSwitch()

        assertEquals("no reload for the same model", 1, backend.loadCalls.size)
        assertEquals(0, backend.releaseCalls)
    }

    @Test
    fun prewarmModelSwitch_withAnEmptySlot_behavesLikePrewarm() {
        val provider = SwitchableModelPathProvider("/models/pro.bin")
        val backend = FakeWhisperBackend()
        val engine = engineWith(provider, backend)
        engine.prewarmModelSwitch()
        assertEquals(listOf("/models/pro.bin"), backend.loadCalls)
        assertEquals(0, backend.releaseCalls)
    }

    @Test
    fun prewarmModelSwitch_withNoInstalledModel_doesNothing() {
        // The delete-model writer pushes null: the stale context stays loaded (same as today) and
        // nothing new loads — connect() reports the no-model error if a session actually starts.
        val provider = SwitchableModelPathProvider("/models/pro.bin")
        val backend = FakeWhisperBackend()
        val engine = engineWith(provider, backend)
        engine.prewarm()
        provider.path = null
        engine.prewarmModelSwitch()
        assertEquals(1, backend.loadCalls.size)
        assertEquals(0, backend.releaseCalls)
    }

    @Test
    fun prewarmModelSwitch_onARealBackgroundExecutor_leavesTheNextConnectWarm() {
        // The point of the whole workstream: after a switch + re-prewarm, connect() takes its
        // fast path (no load inside CONNECTING). Real single-thread executor, per house rules.
        val provider = SwitchableModelPathProvider("/models/pro.bin")
        val backend = FakeWhisperBackend()
        val executor = Executors.newSingleThreadExecutor()
        val engine = LocalWhisperEngine(
            modelPathProvider = provider,
            retry = fastRetry(),
            backend = backend,
            executor = executor,
        )
        try {
            engine.prewarm()
            assertTrue(engine.awaitIdle(5_000L))
            provider.path = "/models/multi.bin"
            engine.prewarmModelSwitch()
            assertTrue(engine.awaitIdle(5_000L))
            assertEquals(listOf("/models/pro.bin", "/models/multi.bin"), backend.loadCalls)
            assertEquals(1, backend.releaseCalls)

            val listener = RecordingListener()
            engine.connect(language = "en", listener = listener)
            // The fast path signals onOpen on the control executor; poll with a deadline.
            val deadline = System.currentTimeMillis() + 5_000L
            while (!listener.opened && System.currentTimeMillis() < deadline) Thread.sleep(10)
            assertTrue("connect() must open without a reload", listener.opened)
            assertTrue(engine.awaitIdle(5_000L))
            assertEquals("connect() must NOT have loaded again", 2, backend.loadCalls.size)
        } finally {
            engine.shutdown()
            executor.shutdownNow()
        }
    }
}
```

**Step 2 — Run it, watch it fail**

- [ ] Run:
```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon --tests "com.whispereverywhere.transcription.LocalWhisperEngineWarmPathTest"
```
Expected failure: `> Task :app:compileDebugUnitTestKotlin FAILED` with `Unresolved reference 'prewarmModelSwitch'`.

**Step 3 — Implement**

- [ ] In `LocalWhisperEngine.kt`, replace this OLD block (the whole `prewarm()` function, ~:331-347 — its KDoc above stays untouched):

```kotlin
    override fun prewarm() {
        val modelPath = modelPathProvider.installedModelPath() ?: return
        if (ctxPtr != 0L) return
        executor.execute {
            if (ctxPtr != 0L) return@execute
            try {
                val loaded = runBlocking { loadRetry.retry { backend.load(modelPath) } }
                if (loaded != 0L) {
                    ctxPtr = loaded
                    loadedModelPath = modelPath
                    android.util.Log.i("WE-DIAG", "prewarm: ctx loaded")
                }
            } catch (t: Throwable) {
                Log.w("LocalWhisperEngine", "prewarm load failed (connect() will retry)", t)
            }
        }
    }
```

with:

```kotlin
    override fun prewarm() {
        val modelPath = modelPathProvider.installedModelPath() ?: return
        if (ctxPtr != 0L) return
        executor.execute {
            if (ctxPtr != 0L) return@execute
            try {
                val loaded = runBlocking { loadRetry.retry { backend.load(modelPath) } }
                if (loaded != 0L) {
                    ctxPtr = loaded
                    loadedModelPath = modelPath
                    android.util.Log.i("WE-DIAG", "prewarm: ctx loaded")
                }
            } catch (t: Throwable) {
                Log.w("LocalWhisperEngine", "prewarm load failed (connect() will retry)", t)
            }
        }
    }

    /**
     * Prewarm that also handles a MODEL SWITCH (3.6.0, Workstream E1). [prewarm] deliberately
     * fills only an EMPTY slot, so after the user switched tiers the STALE context sat loaded and
     * the next session paid the release+load (~7 s on the GPU path) inline in CONNECTING. This
     * runs the same release-then-load sequence connect() would — ahead of time, on the native
     * executor, so it serializes with any queued work and the next connect() takes its fast path.
     *
     * Same-model and empty-slot calls converge on the right thing (no-op / plain load); a null
     * installed path (model deleted) no-ops entirely, exactly like [prewarm]. Silent on failure,
     * also like [prewarm]: connect() retries the load with full error reporting.
     *
     * Callers must NOT invoke this while a session is live: releasing the context mid-session
     * would resolve every later segment Lost. The bubble's debounced collector gates on IDLE.
     */
    fun prewarmModelSwitch() {
        val modelPath = modelPathProvider.installedModelPath() ?: return
        executor.execute {
            if (ctxPtr != 0L && modelPath == loadedModelPath) return@execute
            if (ctxPtr != 0L) {
                android.util.Log.i(
                    "WE-DIAG",
                    "prewarmModelSwitch: releasing stale ctx ($loadedModelPath -> $modelPath)",
                )
                try {
                    backend.release(ctxPtr)
                } catch (t: Throwable) {
                    Log.w("LocalWhisperEngine", "prewarmModelSwitch release failed", t)
                }
                ctxPtr = 0L
                loadedModelPath = null
            }
            try {
                val loaded = runBlocking { loadRetry.retry { backend.load(modelPath) } }
                if (loaded != 0L) {
                    ctxPtr = loaded
                    loadedModelPath = modelPath
                    android.util.Log.i("WE-DIAG", "prewarmModelSwitch: ctx loaded")
                }
            } catch (t: Throwable) {
                Log.w("LocalWhisperEngine", "prewarmModelSwitch load failed (connect() will retry)", t)
            }
        }
    }
```

**Step 4 — Run green**

- [ ] Run:
```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon --tests "com.whispereverywhere.transcription.LocalWhisperEngineWarmPathTest" --tests "com.whispereverywhere.transcription.LocalWhisperEngineTest"
```
Expected: `BUILD SUCCESSFUL` — all 5 new warm-path tests pass and all 18 pre-existing `LocalWhisperEngineTest` tests stay green.

**Step 5 — Commit**

- [ ] Run:
```powershell
git add app/src/main/java/com/whispereverywhere/transcription/LocalWhisperEngine.kt app/src/test/java/com/whispereverywhere/transcription/LocalWhisperEngineWarmPathTest.kt
git commit -m "feat(engine): prewarmModelSwitch - release-then-load ahead of time so a tier switch opens warm (E1)" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`nClaude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT"
```

---

### Task E2: TWO shared re-prewarm triggers — `selectedModelIdFlow` mirror + `modelInstalled` signal + one merged collector

**Files:**
- Edit: `app/src/main/java/com/whispereverywhere/data/local/PreferencesManager.kt`
- Edit: `app/src/main/java/com/whispereverywhere/model/WhisperModelManager.kt`
- Edit: `app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt`

**Builds on:** E1 (`prewarmModelSwitch`), A2 (same service file — A2 replaced the `MAX_SEGMENT_WALL_MS`/`lastCommitWallMs` field pair at ~:272 and three call sites; none of them is inside the import block or the `onCreate` tail quoted here, so both OLD blocks below still match). `WhisperModelManager.kt` is untouched by every prior task.

Design decision (spec E1 "one shared mechanism rather than six copy-pastes"): instead of wiring six writer call sites (`ModelDownloadViewModel.kt:58`, `OnboardingSetupViewModel.kt:91,109`, `SettingsScreen.kt:316,323,759` — grep also found a SEVENTH, `OnboardingFlowScreen.kt:216`), back `selectedModelId` with a `MutableStateFlow` mirror — the exact `sttProviderId` pattern already in this file (`PreferencesManager.kt:275-284`), where every write in the app already goes through the setter so the flow can never drift from prefs. The service (the only owner of `localEngine`, the only object holding the native context) collects it with `collectLatest` + `delay` as the debounce. Zero call-site changes; all seven writers — and any future one — reach the collector automatically.

**Why the id flow alone is NOT enough (the onboarding / first-download gap).** A `MutableStateFlow` mirrors *which id is selected*, not *whether that model is on disk*, and it conflates equal values. Both properties bite on exactly the path where the context is guaranteed stale:

- `OnboardingFlowScreen.kt:216` writes the picked id **before** `beginAutoSetup()` starts the download (its own contract comment at `:210-213`: "written the moment Download is pressed, never before"). The flow emits, the 750 ms debounce fires, and `prewarmModelSwitch` → `installedModelPath()` → `WhisperModelManager.installedModel()` returns **null**, because the file is not on disk yet. The re-prewarm no-ops.
- `OnboardingSetupViewModel.kt:109` then writes the **same id** once the download succeeds — and `MutableStateFlow` conflates an equal value into **no emission at all**. Same for the `:91` self-heal.

So the one moment that most needs a re-prewarm — a model that just finished downloading, with a stale-or-empty context — gets nothing, and the next session pays the ~7 s inline. (`ModelDownloadViewModel.kt:58` and `SettingsScreen.kt:316/323` DO write post-download and are genuinely covered by the id flow.)

The fix is a second, **non-conflating** trigger: `PreferencesManager.modelInstalled`, a `MutableSharedFlow<Unit>` emitted once from `WhisperModelManager`'s download-success tail (after the size + sha256 gates pass, i.e. the file is verifiably on disk). The service merges it with the debounced id flow — both arms call the same `prewarmModelSwitch()`. The id flow keeps its 750 ms debounce (rapid successive writes must collapse); the install signal needs none, because there is exactly one emission per completed download.

The engine half (`prewarmModelSwitch`) is already JVM-pinned by E1; `PreferencesManager`/`WhisperModelManager` are Context-bound and the collector lives in an Android `Service`, so this wiring task is verified by the full suite + `assembleDebug` + the owner check.

**Step 1 — The prefs mirror + the install signal**

- [ ] In `PreferencesManager.kt`, replace this OLD import block (~:9-11):

```kotlin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
```

with:

```kotlin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
```

- [ ] In `PreferencesManager.kt`, replace this OLD block (~:230):

```kotlin
    // Selected on-device whisper model tier id (see WhisperCatalog); null = none chosen yet
    var selectedModelId: String?
        get() = prefs.getString(KEY_SELECTED_MODEL_ID, null)
        set(value) {
            prefs.edit().putString(KEY_SELECTED_MODEL_ID, value).apply()
        }
```

with:

```kotlin
    // Selected on-device whisper model tier id (see WhisperCatalog); null = none chosen yet.
    //
    // Backed by a MutableStateFlow (3.6.0, Workstream E1) so the bubble service can re-prewarm
    // the native context the moment the user switches tiers — prewarm() fills only an EMPTY slot,
    // so before this the first session after a switch paid the ~7 s release+load inline in
    // CONNECTING. The `var` API is unchanged, and EVERY writer in the app already goes through
    // this setter (ModelDownloadViewModel, OnboardingSetupViewModel x2, OnboardingFlowScreen,
    // SettingsScreen x3), so the flow can never drift from prefs — the same one-mirror pattern
    // as sttProviderId below. One mechanism instead of seven per-writer hooks.
    private val _selectedModelId = MutableStateFlow(prefs.getString(KEY_SELECTED_MODEL_ID, null))
    val selectedModelIdFlow: StateFlow<String?> = _selectedModelId.asStateFlow()

    var selectedModelId: String?
        get() = _selectedModelId.value
        set(value) {
            prefs.edit().putString(KEY_SELECTED_MODEL_ID, value).apply()
            _selectedModelId.value = value
        }

    /**
     * "A model finished downloading and passed verification" (3.6.0, Workstream E1). The
     * companion trigger to [selectedModelIdFlow], and NOT redundant with it: the onboarding flow
     * writes the picked id BEFORE the download starts (nothing is on disk yet, so a re-prewarm
     * then no-ops) and rewrites the SAME id afterwards, which a StateFlow conflates into no
     * emission at all. That is precisely the case whose context is stale, so it needs a signal
     * that carries "on disk now" instead of "selected now".
     *
     * SharedFlow, not StateFlow: consecutive installs must each emit, and there is no meaningful
     * "current value". extraBufferCapacity = 1 with the default suspend-free [tryEmit] path so
     * the emitter (a Dispatchers.IO download coroutine) never blocks and never needs a scope; a
     * dropped duplicate while the collector is mid-prewarm is harmless — the prewarm it is
     * already running loads the same file.
     */
    private val _modelInstalled = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val modelInstalled: SharedFlow<Unit> = _modelInstalled.asSharedFlow()

    /** Called by WhisperModelManager once a downloaded model is verified on disk. */
    fun notifyModelInstalled() {
        _modelInstalled.tryEmit(Unit)
    }
```

**Step 2 — Emit the install signal from the download's success tail**

- [ ] In `WhisperModelManager.kt`, replace this OLD block (the tail of `verifyDest`, ~:255-261 — the single point BOTH download paths funnel through: the normal poll loop calls it directly, and the "reuse a completed file" fast path reaches it via `moveVerified`):

```kotlin
        } catch (e: ModelDownloadException) {
            throw e                  // already deleted dest above; just rethrow
        } catch (e: Exception) {
            dest.delete()            // unexpected IO or other error — clean up partial file
            throw e
        }
    }
```

with:

```kotlin
        } catch (e: ModelDownloadException) {
            throw e                  // already deleted dest above; just rethrow
        } catch (e: Exception) {
            dest.delete()            // unexpected IO or other error — clean up partial file
            throw e
        }
        // 3.6.0 (Workstream E1): the model is now verifiably ON DISK, which is the moment the
        // bubble's native context became stale. Emitted here — the one point both download paths
        // funnel through (the poll loop's STATUS_SUCCESSFUL branch calls verifyDest directly, the
        // reuse-a-completed-file fast path reaches it through moveVerified) — and AFTER the size
        // and sha256 gates, so a signal is never sent for a file that gets deleted a line later.
        // The selectedModelId flow cannot cover this: onboarding writes the id BEFORE the file
        // exists and rewrites the SAME id after, which a StateFlow conflates away entirely.
        prefs.notifyModelInstalled()
    }
```

**Step 3 — The merged collector**

- [ ] In `FloatingBubbleService.kt`, replace this OLD import pair (~:55):

```kotlin
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
```

with:

```kotlin
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.isActive
```

- [ ] In `FloatingBubbleService.kt`, replace this OLD block (the tail of `onCreate`, ~:436-448):

```kotlin
        // Pre-warm the whisper context (model mmap + Adreno OpenCL kernel compile, ~7 s on the
        // GPU path) so the FIRST recording connects instantly instead of paying it inside
        // CONNECTING. Slightly delayed to keep service startup/view inflation snappy; queued on
        // the engine's own single-thread executor, so it never races a session.
        // Deliberately the LOCAL engine, not the session engine: the native context is the only
        // expensive thing to warm, and resolving the session engine here would (a) decide the
        // cloud/local question ~1.5 s after boot and then cache that answer for the service's
        // whole life, and (b) toast about a degraded mode before the user has asked for anything.
        serviceScope.launch {
            delay(1500)
            warmLocalEngine().prewarm()
        }
    }
```

with:

```kotlin
        // Pre-warm the whisper context (model mmap + Adreno OpenCL kernel compile, ~7 s on the
        // GPU path) so the FIRST recording connects instantly instead of paying it inside
        // CONNECTING. Slightly delayed to keep service startup/view inflation snappy; queued on
        // the engine's own single-thread executor, so it never races a session.
        // Deliberately the LOCAL engine, not the session engine: the native context is the only
        // expensive thing to warm, and resolving the session engine here would (a) decide the
        // cloud/local question ~1.5 s after boot and then cache that answer for the service's
        // whole life, and (b) toast about a degraded mode before the user has asked for anything.
        serviceScope.launch {
            delay(1500)
            warmLocalEngine().prewarm()
        }

        // Re-prewarm on model switch OR first install (3.6.0, Workstream E1). TWO triggers, ONE
        // collector, both ending in the same prewarmModelSwitch():
        //
        //  (a) every selectedModelId write — onboarding pick, Home's missing-engine row,
        //      Settings' migration/switch/delete — through the ONE prefs mirror, zero per-writer
        //      wiring. Debounced 750 ms: rapid successive writes (the migration's swap,
        //      onboarding's double write) restart the wait and only the final selection loads;
        //      the StateFlow additionally conflates same-value rewrites into no emission at all.
        //      drop(1) skips the replay of the current value at collect time — the prewarm above
        //      already covers service start.
        //  (b) modelInstalled — emitted once per VERIFIED download. This is the case (a) cannot
        //      see: onboarding writes the id before the file exists (a re-prewarm then finds no
        //      installed path and no-ops) and rewrites the same id after, which the StateFlow
        //      conflates away. Undebounced: one emission per completed download, and the file is
        //      already on disk when it arrives.
        //
        // collectLatest over the merged flow means an install signal cancels a still-pending id
        // debounce — correct, because both arms do the identical thing. Mid-session triggers are
        // skipped, never deferred: releasing a context a live session is using would Lost every
        // later segment, and connect() at the next session start reloads exactly as it always
        // has. A null id (model deleted) no-ops inside prewarmModelSwitch. Main-dispatched, so
        // the currentState read is main-confined.
        serviceScope.launch {
            merge(
                app.preferencesManager.selectedModelIdFlow.drop(1)
                    .map { id -> 750L to "selectedModelId -> $id" },
                app.preferencesManager.modelInstalled.map { 0L to "model installed" },
            ).collectLatest { (debounceMs, reason) ->
                delay(debounceMs)
                if (currentState != BubbleState.IDLE && currentState != BubbleState.ERROR) {
                    android.util.Log.i("WE-DIAG", "$reason mid-session — connect() will reload")
                    return@collectLatest
                }
                android.util.Log.i("WE-DIAG", "$reason: re-prewarming engine")
                warmLocalEngine().prewarmModelSwitch()
            }
        }
    }
```

**Step 4 — Verify**

- [ ] Run the full suite:
```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon
```
Expected: `BUILD SUCCESSFUL`, zero failures.
- [ ] Build:
```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:assembleDebug --no-daemon
```
Expected: `BUILD SUCCESSFUL`. NEVER `:app:installDebug`.
- [ ] Owner on-device check, BOTH triggers:
  - **switch:** with the bubble running, switch tiers in Settings → logcat shows `selectedModelId -> <id>: re-prewarming engine` then `prewarmModelSwitch: ctx loaded` within ~1 s of the write finishing; the NEXT session's CONNECTING is instant (`onOpen (ctx already loaded)`), where before it paid the ~7 s load.
  - **install:** download a tier that is not on disk (Settings, or a fresh onboarding run) → on verification completing, logcat shows `model installed: re-prewarming engine` then `prewarmModelSwitch: ctx loaded` — the path that logged nothing at all before this task.

**Step 5 — Commit**

- [ ] Run:
```powershell
git add app/src/main/java/com/whispereverywhere/data/local/PreferencesManager.kt app/src/main/java/com/whispereverywhere/model/WhisperModelManager.kt app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt
git commit -m "feat(bubble): re-prewarm on every model switch AND every completed download - one merged collector (E2)" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`nClaude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT"
```

---

### Task E3: TTS preload at the two cold call sites (`SpeakTextActivity`, Home guide read)

**Files:**
- Edit: `app/src/main/java/com/whispereverywhere/tts/SpeakTextActivity.kt`
- Edit: `app/src/main/java/com/whispereverywhere/ui/screens/HomeScreen.kt`

**Builds on:** nothing — both files are untouched by every prior task.

Spec anchor: Workstream E2 — today only the clipboard path warms (`FloatingBubbleService.onClipboardChanged` → `TtsController.preload`, the only production caller per grep); the PROCESS_TEXT toolbar read and the Home guide read pay the ~2 s Kokoro load cold every time. `TtsController.preload(context)` is a no-op when the voice isn't installed or is already loaded (`TtsEngine.preload`, `TtsEngine.kt:149-158`), and the engine's idle-unload reclaims the context, so preloading on an engagement signal is memory-safe. Pure UI/Activity wiring — no JVM-testable logic (house convention: Compose shells untested; the preload semantics live in `TtsEngine`, already shipped) — so verification is compile + full suite + owner check. **The guide's COPY is not edited here** (untouchable), only the button's warm-up.

**Step 1 — SpeakTextActivity**

- [ ] In `SpeakTextActivity.kt`, replace this OLD block (~:20):

```kotlin
        val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
        if (!text.isNullOrBlank()) {
            // Cap pathological selections; spans are dropped by toString above.
            TtsController.speakFromTrigger(this, text.take(MAX_CHARS))
        }
        finish()
```

with:

```kotlin
        val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
        if (!text.isNullOrBlank()) {
            // Warm path (3.6.0, Workstream E2): start the ~2 s Kokoro model load on the synthesis
            // thread NOW, so it overlaps speakFromTrigger's main-thread resolution (voice-installed
            // disk check, arbiter, prefs + Keystore reads in applyCloudVoice) instead of starting
            // after them — the toolbar read was a guaranteed-cold call site; only the clipboard
            // path preloaded. No-op when the voice isn't installed or is already loaded.
            TtsController.preload(this)
            // Cap pathological selections; spans are dropped by toString above.
            TtsController.speakFromTrigger(this, text.take(MAX_CHARS))
        }
        finish()
```

**Step 2 — Home guide read**

- [ ] In `HomeScreen.kt`, replace this OLD block (the head of `HowToGuideCard`, ~:436):

```kotlin
private fun HowToGuideCard() {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
```

with:

```kotlin
private fun HowToGuideCard() {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }

    // Warm path (3.6.0, Workstream E2): expanding the guide is the think-time signal that a
    // read-aloud tap may follow — start the ~2 s Kokoro load then, not on the tap. Deliberately
    // NOT on plain Home composition: that would allocate the TTS context on every app open for
    // users who never read. No-op when the voice isn't installed or is already loaded, and the
    // engine's idle-unload reclaims the context if the tap never comes.
    LaunchedEffect(expanded) {
        if (expanded) com.whispereverywhere.tts.TtsController.preload(context)
    }
```

(`LaunchedEffect` is already in scope via `import androidx.compose.runtime.*` at `HomeScreen.kt:16`.)

- [ ] In `HomeScreen.kt`, replace this OLD block (the guide's speaker button, ~:461):

```kotlin
                // Read-aloud, pinned to the guide (owner: "more interactive") — tap to hear the
                // whole guide through whichever voice is configured; tapping again restarts, and
                // the bubble's stop/scrubber controls work on it like any read.
                IconButton(onClick = {
                    com.whispereverywhere.tts.TtsController.stop()
                    com.whispereverywhere.tts.TtsController.speakFromTrigger(
                        context, com.whispereverywhere.ui.HowToGuide.plainText()
                    )
                }) {
```

with:

```kotlin
                // Read-aloud, pinned to the guide (owner: "more interactive") — tap to hear the
                // whole guide through whichever voice is configured; tapping again restarts, and
                // the bubble's stop/scrubber controls work on it like any read.
                // preload first (3.6.0 E2): covers the collapsed-header tap the expansion
                // preload above never saw; the load overlaps speakFromTrigger's main-thread
                // prefs/Keystore resolution. No-op when already loaded.
                IconButton(onClick = {
                    com.whispereverywhere.tts.TtsController.preload(context)
                    com.whispereverywhere.tts.TtsController.stop()
                    com.whispereverywhere.tts.TtsController.speakFromTrigger(
                        context, com.whispereverywhere.ui.HowToGuide.plainText()
                    )
                }) {
```

**Step 3 — Verify**

- [ ] Run the full suite (pins the how-to guide's tested invariants untouched):
```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon
```
Expected: `BUILD SUCCESSFUL`, zero failures.
- [ ] Build:
```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:assembleDebug --no-daemon
```
Expected: `BUILD SUCCESSFUL`.
- [ ] Owner on-device check: expand the guide, wait ~2 s, tap the speaker — speech starts near-instantly (was ~2 s); a toolbar "Speak" in Chrome starts noticeably faster on the second-plus read of a session.

**Step 4 — Commit**

- [ ] Run:
```powershell
git add app/src/main/java/com/whispereverywhere/tts/SpeakTextActivity.kt app/src/main/java/com/whispereverywhere/ui/screens/HomeScreen.kt
git commit -m "feat(tts): preload the voice at the two cold call sites - toolbar read and Home guide (E3)" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`nClaude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT"
```

---

### Task E4: The cold-connect flag (`LocalWhisperEngine.isWarm()`) + the pure CONNECTING label rule

**Files:**
- Edit: `app/src/main/java/com/whispereverywhere/transcription/LocalWhisperEngine.kt`
- Edit: `app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt` (one top-level pure function only — no service-body wiring yet; that is E5)
- Edit: `app/src/test/java/com/whispereverywhere/transcription/LocalWhisperEngineWarmPathTest.kt` (created in E1)
- Create: `app/src/test/java/com/whispereverywhere/service/ConnectingLabelTest.kt`

**Builds on:** E1 (the warm-path test file, and the `prewarmModelSwitch` function E1 appended immediately above the `releaseContext` KDoc anchor below — the anchor text itself is unchanged), A2/E2 (service file; neither touched the top-level `isRealtimeStt` function).

Spec anchor: Workstream E3 — cold loads show "Loading speech model…"; the engine already KNOWS which branch its `connect()` takes (`LocalWhisperEngine.kt:107` warm vs `:118` cold) but only logs it. Surface it as a flag — never parse logs. The flag mirrors `connect()`'s own fast-path condition (`ctxPtr != 0L && modelPath == loadedModelPath`); a race with an in-flight load can only UNDER-promise (label shows, connect turns out warm) — the safe direction.

**Step 1 — Write the failing tests**

- [ ] In `LocalWhisperEngineWarmPathTest.kt`, replace this OLD tail (the end of the real-executor test plus the class's closing brace):

```kotlin
        } finally {
            engine.shutdown()
            executor.shutdownNow()
        }
    }
}
```

with:

```kotlin
        } finally {
            engine.shutdown()
            executor.shutdownNow()
        }
    }

    // ===== isWarm(): the CONNECTING-label flag (Workstream E3) =====

    @Test
    fun isWarm_isFalseBeforeAnyLoad() {
        val engine = engineWith(SwitchableModelPathProvider("/models/pro.bin"), FakeWhisperBackend())
        org.junit.Assert.assertFalse(engine.isWarm())
    }

    @Test
    fun isWarm_isTrueOnceTheInstalledModelIsLoaded() {
        val engine = engineWith(SwitchableModelPathProvider("/models/pro.bin"), FakeWhisperBackend())
        engine.prewarm()
        assertTrue(engine.isWarm())
    }

    @Test
    fun isWarm_isFalseAfterReleaseContext() {
        // onTrimMemory freed the context: the next connect() is cold and the label must say so.
        val engine = engineWith(SwitchableModelPathProvider("/models/pro.bin"), FakeWhisperBackend())
        engine.prewarm()
        engine.releaseContext()
        org.junit.Assert.assertFalse(engine.isWarm())
    }

    @Test
    fun isWarm_isFalseWhenTheInstalledModelChangedSinceTheLoad() {
        // The same condition connect() checks: a loaded-but-stale context is a COLD start.
        val provider = SwitchableModelPathProvider("/models/pro.bin")
        val engine = engineWith(provider, FakeWhisperBackend())
        engine.prewarm()
        provider.path = "/models/multi.bin"
        org.junit.Assert.assertFalse(engine.isWarm())
    }

    @Test
    fun isWarm_isFalseWithNoInstalledModel() {
        val engine = engineWith(SwitchableModelPathProvider(null), FakeWhisperBackend())
        org.junit.Assert.assertFalse(engine.isWarm())
    }
}
```

- [ ] Write `app/src/test/java/com/whispereverywhere/service/ConnectingLabelTest.kt` exactly:

```kotlin
package com.whispereverywhere.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConnectingLabelTest {

    @Test
    fun aColdLocalSessionNamesTheModelLoad() {
        assertEquals(
            "Loading speech model…",
            connectingStatusLabel(isCloudSession = false, localEngineWarm = false),
        )
    }

    @Test
    fun aWarmLocalSessionKeepsTheBareSpinner() {
        assertNull(connectingStatusLabel(isCloudSession = false, localEngineWarm = true))
    }

    @Test
    fun cloudSessionsNeverClaimAModelLoad() {
        // Their CONNECTING wait is the socket/handshake — "loading speech model" would be a lie.
        assertNull(connectingStatusLabel(isCloudSession = true, localEngineWarm = false))
        assertNull(connectingStatusLabel(isCloudSession = true, localEngineWarm = true))
    }
}
```

**Step 2 — Run them, watch them fail**

- [ ] Run:
```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon --tests "com.whispereverywhere.transcription.LocalWhisperEngineWarmPathTest" --tests "com.whispereverywhere.service.ConnectingLabelTest"
```
Expected failure: `> Task :app:compileDebugUnitTestKotlin FAILED` with `Unresolved reference 'isWarm'` and `Unresolved reference 'connectingStatusLabel'`.

**Step 3 — Implement**

- [ ] In `LocalWhisperEngine.kt`, replace this OLD block (the `releaseContext` KDoc + signature, ~:349):

```kotlin
    /**
     * Frees the cached native context (e.g. from onTrimMemory under memory pressure).
     * The context reloads lazily on the next connect(). Runs on the executor so it never
     * races an in-flight transcription.
     */
    override fun releaseContext() {
```

with:

```kotlin
    /**
     * True when the NEXT connect() will take its fast path: a context is loaded AND it is for the
     * currently installed model — the exact condition connect() checks before skipping the load.
     * Surfaced as a flag (3.6.0, Workstream E3) so the bubble can show the honest
     * "Loading speech model…" CONNECTING label on cold starts instead of parsing which branch
     * the engine logged. Cheap volatile reads plus one path lookup; callable from any thread.
     * A race with an in-flight load only UNDER-promises (label shows, connect lands warm) —
     * the safe direction.
     */
    fun isWarm(): Boolean {
        val modelPath = modelPathProvider.installedModelPath() ?: return false
        return ctxPtr != 0L && modelPath == loadedModelPath
    }

    /**
     * Frees the cached native context (e.g. from onTrimMemory under memory pressure).
     * The context reloads lazily on the next connect(). Runs on the executor so it never
     * races an in-flight transcription.
     */
    override fun releaseContext() {
```

- [ ] In `FloatingBubbleService.kt`, replace this OLD block (top-level, ~:140):

```kotlin
/** True when [sttProviderIdName] both resolves to a shipped adapter AND streams in real time. */
internal fun isRealtimeStt(sttProviderIdName: String?): Boolean =
    resolveSttProvider(sttProviderIdName)?.let { it in REALTIME_STT_PROVIDERS } == true
```

with:

```kotlin
/** True when [sttProviderIdName] both resolves to a shipped adapter AND streams in real time. */
internal fun isRealtimeStt(sttProviderIdName: String?): Boolean =
    resolveSttProvider(sttProviderIdName)?.let { it in REALTIME_STT_PROVIDERS } == true

/**
 * The CONNECTING status label (3.6.0, Workstream E3). A LOCAL session whose engine still has to
 * load the model gets the honest "Loading speech model…" — naming the ~7 s cold wait — instead
 * of a bare spinner. A warm local engine, or ANY cloud session (whose CONNECTING wait is the
 * socket/handshake, not a model load), gets null: spinner only, exactly as before. Pure so the
 * branch is JVM-pinned (ConnectingLabelTest); the warm flag comes from LocalWhisperEngine.isWarm.
 */
internal fun connectingStatusLabel(isCloudSession: Boolean, localEngineWarm: Boolean): String? =
    if (!isCloudSession && !localEngineWarm) "Loading speech model…" else null
```

**Step 4 — Run green**

- [ ] Run:
```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon --tests "com.whispereverywhere.transcription.LocalWhisperEngineWarmPathTest" --tests "com.whispereverywhere.service.ConnectingLabelTest"
```
Expected: `BUILD SUCCESSFUL` — 10 warm-path tests (5 from E1 + 5 new) and 3 `ConnectingLabelTest` tests pass.

**Step 5 — Commit**

- [ ] Run:
```powershell
git add app/src/main/java/com/whispereverywhere/transcription/LocalWhisperEngine.kt app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt app/src/test/java/com/whispereverywhere/transcription/LocalWhisperEngineWarmPathTest.kt app/src/test/java/com/whispereverywhere/service/ConnectingLabelTest.kt
git commit -m "feat(engine): isWarm flag + pure connectingStatusLabel rule - cold loads get named (E4)" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`nClaude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT"
```

---

### Task E5: Render the cold-load label during CONNECTING

**Files:**
- Edit: `app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt`

**Builds on:** E4 (`connectingStatusLabel`, `LocalWhisperEngine.isWarm`). A2 edited the `onOpen` body ~20 lines BELOW this anchor and the field pair far above it; the OLD block quoted here is unchanged.

Wires E4's pinned rule into `startRecording`, right after the session engine resolves (that is the earliest moment both inputs exist: `cloudWrapper` is nulled then set by `resolveTranscriptionEngine`, and `localEngine` by `warmLocalEngine` inside it). The label rides the existing delta strip — the same surface the FINALIZING status line uses — and every exit already cleans it up: `onOpen` → `showSessionPreview()` resets strip + window text (`:2003-2004`); recorder-start failure and connect-time fatal both run `teardownRealtime()`, which hides the container (`:2389-2390`). Service-body wiring, so verification is suite + build + owner check.

**Step 1 — Wire the label**

- [ ] In `FloatingBubbleService.kt`, replace this OLD block (inside `startRecording`, ~:2064):

```kotlin
        // On-device engine. connect() resolves the installed model and loads the
        // native context off-thread; CONNECTING covers that model-load wait and
        // onOpen() fires only once the context is ready.
        // Reuse a single engine across sessions so the native model context is loaded once and
        // reused (spec: "loaded once and reused"); it is released only on memory pressure
        // (onTrimMemory) or on service destroy (onDestroy), not at the end of each recording.
        val engine: TranscriptionEngine = resolveTranscriptionEngine()
```

with:

```kotlin
        // On-device engine. connect() resolves the installed model and loads the
        // native context off-thread; CONNECTING covers that model-load wait and
        // onOpen() fires only once the context is ready.
        // Reuse a single engine across sessions so the native model context is loaded once and
        // reused (spec: "loaded once and reused"); it is released only on memory pressure
        // (onTrimMemory) or on service destroy (onDestroy), not at the end of each recording.
        val engine: TranscriptionEngine = resolveTranscriptionEngine()

        // Honest CONNECTING (3.6.0, Workstream E3): a cold local engine is about to pay the ~7 s
        // model load inside CONNECTING — name the wait. The engine itself reports which branch
        // its connect() will take (isWarm(), the same check connect() runs — a surfaced flag,
        // never log parsing); cloud sessions (cloudWrapper != null) are excluded because their
        // CONNECTING wait is the socket/handshake. The strip is the label surface, exactly like
        // the FINALIZING status line: onOpen's showSessionPreview() resets it, and every failure
        // exit (recorder start failure, connect-time fatal) runs teardownRealtime(), which
        // brings the container down.
        connectingStatusLabel(
            isCloudSession = cloudWrapper != null,
            localEngineWarm = localEngine?.isWarm() == true,
        )?.let { label ->
            // Size BEFORE showing, exactly like showSessionPreview() does (applyPreviewSize is
            // its first call): the container's width/height come from bubbleTextWidthDp/HeightDp
            // clamped against the live screen, and without this the first session after a service
            // start renders the panel at whatever geometry was left over, then jumps when
            // showSessionPreview runs at onOpen.
            applyPreviewSize()
            transcriptionEditText.visibility = View.GONE
            transcriptionDeltaText.text = label
            transcriptionDeltaText.scrollTo(0, 0)
            transcriptionDeltaText.visibility = View.VISIBLE
            transcriptionPreviewContainer.visibility = View.VISIBLE
            // The strip appearing is a geometry change — posted so the measure pass ran first.
            bubbleView.post { reclampNow() }
        }
```

**Step 2 — Verify**

- [ ] Run the full suite:
```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon
```
Expected: `BUILD SUCCESSFUL`, zero failures.
- [ ] Build:
```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:assembleDebug --no-daemon
```
Expected: `BUILD SUCCESSFUL`.
- [ ] Owner on-device check: force a cold start (toggle the bubble off/on, tap record within the 1.5 s pre-warm delay — or switch tiers with the bubble stopped, then start it and record immediately): the bubble shows "Loading speech model…" until recording begins. A warm tap and every cloud session show the spinner exactly as before.

**Step 3 — Commit**

- [ ] Run:
```powershell
git add app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt
git commit -m "feat(bubble): CONNECTING names the cold model load - 'Loading speech model...' on the strip (E5)" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`nClaude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT"
```

---

### Task E6: Wire the dead `startProcessingTimer()` into FINALIZING

**Files:**
- Edit: `app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt`
- Create: `app/src/test/java/com/whispereverywhere/service/ProcessingTimerPolicyTest.kt`

**Builds on:** E4 — the first OLD block below is the `connectingStatusLabel` function E4 appended after `isRealtimeStt`; it is quoted here exactly as E4 left it. The other two anchors (`startProcessingTimer`'s head, the `FINALIZING` branch of `updateBubbleState`) are untouched by every prior task.

Spec anchor: Workstream E4 — `startProcessingTimer()` (`FloatingBubbleService.kt:2713-2725`) is dead code: its only caller is the PROCESSING branch, and nothing in the service ever enters `BubbleState.PROCESSING` (grep: the enum's only other appearances are the click-ignore row at `:1561` and the branch itself). Wire it into FINALIZING so long drains count up visibly alongside the 3.5.0 "Finishing…" status line. The which-states-tick rule is extracted pure and TDD'd; IDLE (`:2587-2588`) and ERROR (`:2671-2672`) — the only FINALIZING exits — already stop the timer and hide the text, so cleanup is inherited.

**Step 1 — Write the failing test**

- [ ] Write `app/src/test/java/com/whispereverywhere/service/ProcessingTimerPolicyTest.kt` exactly:

```kotlin
package com.whispereverywhere.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessingTimerPolicyTest {

    @Test
    fun tickerRunsInFinalizingAndProcessing() {
        // FINALIZING added in 3.6.0 (Workstream E4): the stop-tap drain counts up visibly
        // alongside the "Finishing…" status line. PROCESSING keeps the legacy branch.
        assertTrue(processingTimerRunsIn(FloatingBubbleService.BubbleState.FINALIZING))
        assertTrue(processingTimerRunsIn(FloatingBubbleService.BubbleState.PROCESSING))
    }

    @Test
    fun tickerStopsEverywhereElse() {
        // Both FINALIZING exits (IDLE, ERROR) must terminate the while-loop — and the ticker
        // must never run over recording or connecting chrome.
        assertFalse(processingTimerRunsIn(FloatingBubbleService.BubbleState.IDLE))
        assertFalse(processingTimerRunsIn(FloatingBubbleService.BubbleState.CONNECTING))
        assertFalse(processingTimerRunsIn(FloatingBubbleService.BubbleState.RECORDING))
        assertFalse(processingTimerRunsIn(FloatingBubbleService.BubbleState.ERROR))
    }
}
```

**Step 2 — Run it, watch it fail**

- [ ] Run:
```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon --tests "com.whispereverywhere.service.ProcessingTimerPolicyTest"
```
Expected failure: `> Task :app:compileDebugUnitTestKotlin FAILED` with `Unresolved reference 'processingTimerRunsIn'`.

**Step 3 — Implement**

- [ ] In `FloatingBubbleService.kt`, replace this OLD block (top-level; the function E4 added):

```kotlin
/**
 * The CONNECTING status label (3.6.0, Workstream E3). A LOCAL session whose engine still has to
 * load the model gets the honest "Loading speech model…" — naming the ~7 s cold wait — instead
 * of a bare spinner. A warm local engine, or ANY cloud session (whose CONNECTING wait is the
 * socket/handshake, not a model load), gets null: spinner only, exactly as before. Pure so the
 * branch is JVM-pinned (ConnectingLabelTest); the warm flag comes from LocalWhisperEngine.isWarm.
 */
internal fun connectingStatusLabel(isCloudSession: Boolean, localEngineWarm: Boolean): String? =
    if (!isCloudSession && !localEngineWarm) "Loading speech model…" else null
```

with:

```kotlin
/**
 * The CONNECTING status label (3.6.0, Workstream E3). A LOCAL session whose engine still has to
 * load the model gets the honest "Loading speech model…" — naming the ~7 s cold wait — instead
 * of a bare spinner. A warm local engine, or ANY cloud session (whose CONNECTING wait is the
 * socket/handshake, not a model load), gets null: spinner only, exactly as before. Pure so the
 * branch is JVM-pinned (ConnectingLabelTest); the warm flag comes from LocalWhisperEngine.isWarm.
 */
internal fun connectingStatusLabel(isCloudSession: Boolean, localEngineWarm: Boolean): String? =
    if (!isCloudSession && !localEngineWarm) "Loading speech model…" else null

/**
 * The states whose elapsed ticker runs (3.6.0, Workstream E4). PROCESSING kept for the legacy
 * branch that has always owned the ticker UI; FINALIZING added so the stop-tap drain counts up
 * visibly alongside the "Finishing…" status line instead of an unchanging spinner. The ticker's
 * while-loop re-reads the live state through this each tick, so BOTH FINALIZING exits (IDLE,
 * ERROR — each of which also hides the text and cancels the job) terminate it. Pure and
 * JVM-pinned (ProcessingTimerPolicyTest).
 */
internal fun processingTimerRunsIn(state: FloatingBubbleService.BubbleState): Boolean =
    state == FloatingBubbleService.BubbleState.PROCESSING ||
        state == FloatingBubbleService.BubbleState.FINALIZING
```

- [ ] In `FloatingBubbleService.kt`, replace this OLD block (the ticker loop head, ~:2713):

```kotlin
    private fun startProcessingTimer() {
        processingStartTime = System.currentTimeMillis()
        processingTimeText.text = "0s"

        processingTimerJob?.cancel()
        processingTimerJob = serviceScope.launch {
            while (isActive && currentState == BubbleState.PROCESSING) {
```

with:

```kotlin
    private fun startProcessingTimer() {
        processingStartTime = System.currentTimeMillis()
        processingTimeText.text = "0s"

        processingTimerJob?.cancel()
        processingTimerJob = serviceScope.launch {
            while (isActive && processingTimerRunsIn(currentState)) {
```

- [ ] In `FloatingBubbleService.kt`, replace this OLD block (the FINALIZING branch of `updateBubbleState`, ~:2641):

```kotlin
                BubbleState.FINALIZING -> {
                    pulseAnimator?.cancel()
                    waveformView.stop()
                    waveformView.visibility = View.GONE
                    setBubbleWidth(56)
                    processingRing.visibility = View.VISIBLE
                    blobView.fillColor = androidx.core.content.ContextCompat.getColor(this@FloatingBubbleService, R.color.bubble_processing)
                    blobView.setMode(com.whispereverywhere.ui.components.BlobView.Mode.PROCESSING)
                    startRotationAnimation()
                }
```

with:

```kotlin
                BubbleState.FINALIZING -> {
                    pulseAnimator?.cancel()
                    waveformView.stop()
                    waveformView.visibility = View.GONE
                    setBubbleWidth(56)
                    processingRing.visibility = View.VISIBLE
                    blobView.fillColor = androidx.core.content.ContextCompat.getColor(this@FloatingBubbleService, R.color.bubble_processing)
                    blobView.setMode(com.whispereverywhere.ui.components.BlobView.Mode.PROCESSING)
                    startRotationAnimation()
                    // 3.6.0 (Workstream E4): the previously-dead elapsed ticker now counts the
                    // drain up next to the "Finishing…" status line — a long drain visibly makes
                    // progress. Both exits (IDLE, ERROR) hide the text and cancel the job.
                    // The mic glyph goes with it, mirroring the PROCESSING branch: the pill is
                    // 56 dp wide here, so the elapsed text would otherwise render over the icon.
                    bubbleIcon.visibility = View.GONE
                    processingTimeText.visibility = View.VISIBLE
                    startProcessingTimer()
                }
```

**Step 4 — Run green**

- [ ] Run:
```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon --tests "com.whispereverywhere.service.ProcessingTimerPolicyTest"
```
Expected: `BUILD SUCCESSFUL`, both `ProcessingTimerPolicyTest` tests pass.
- [ ] Run the full suite + build:
```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:assembleDebug --no-daemon
```
Expected: both `BUILD SUCCESSFUL`, zero test failures.
- [ ] Owner on-device check (feeds H2 "honest waits"): stop a long capture — the pill shows the spinner, the "Finishing… (waiting on provider)" / "Finishing transcript…" line, AND a counting `1s 2s 3s…` that stops the moment the bubble returns to idle.

**Step 5 — Commit**

- [ ] Run:
```powershell
git add app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt app/src/test/java/com/whispereverywhere/service/ProcessingTimerPolicyTest.kt
git commit -m "feat(bubble): FINALIZING counts up - the dead processing ticker now runs through the drain (E6)" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`nClaude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT"
```

---

## Workstream B — Multilingual double-encode (language pinning)

### Task B1: JNI — expose `whisper_full_lang_id` as `WhisperNative.detectedLanguage(ctx)` — **NATIVE**

**Files:**
- Edit: `app/src/main/cpp/whisper_jni.cpp`
- Edit: `app/src/main/java/com/whispereverywhere/whisper/WhisperNative.kt`

**Builds on:** nothing — first edit to both files; both OLD blocks match HEAD `97ec697` verbatim.

**Why:** Workstream B needs the language whisper auto-detected during a completed `whisper_full` (set in the auto-detect branch, read by `whisper_full_lang_id` — `whisper.h:634` — and mapped to an ISO code by `whisper_lang_str`, `whisper.h:367`, which returns `nullptr` for an unknown id). The id **persists on the ctx across calls and across sessions**, and the early-return paths in `transcribeRaw` (VAD-found-zero-speech at `whisper_jni.cpp:189-193`, the no-VAD energy gate at `:194-202`, null/empty input at `:166-171`) never reach `whisper_full` — so the value is only trustworthy immediately after a transcribe that demonstrably ran. Kotlin (Task B4) enforces that by querying only after a non-blank result.

**Verification contract (native):** (a) `:app:assembleDebug` builds `libwhisper_jni.so`; (b) the full JVM suite stays green; (c) the owner on-device check it feeds — with a multilingual tier and language = auto, logcat shows B4's `WE-DIAG language-pin: detected=<code>` line on the session's first speech segment, and later segments' `transcribe START` lines show `effective=<code>`. NEVER `:app:installDebug` / `:app:connectedDebugAndroidTest`.

- [ ] **Step 1 — add the JNI function.** In `app/src/main/cpp/whisper_jni.cpp`, anchor on the `free` entry point (unique — the only `_free` symbol in the file):

OLD:
```cpp
extern "C" JNIEXPORT void JNICALL
Java_com_whispereverywhere_whisper_WhisperNative_free(
        JNIEnv *env, jobject /* this */, jlong ctxPtr) {
```

NEW:
```cpp
extern "C" JNIEXPORT jstring JNICALL
Java_com_whispereverywhere_whisper_WhisperNative_detectedLanguage(
        JNIEnv *env, jobject /* this */, jlong ctxPtr) {
    auto *ctx = reinterpret_cast<whisper_context *>(ctxPtr);
    if (ctx == nullptr) {
        return nullptr;
    }
    // state->lang_id from the LAST completed whisper_full on this ctx. It PERSISTS across calls —
    // even across sessions — so the Kotlin side must only trust it right after a transcribe that
    // demonstrably ran: the early-return paths in transcribeRaw above (VAD found zero speech,
    // the no-VAD energy gate, empty input) never touch whisper_full and would leave a stale id.
    // Threading: a plain field read, but it reads the ctx — call it only on the single thread
    // that runs transcribe for this ctx (LocalWhisperEngine's native executor).
    const int langId = whisper_full_lang_id(ctx);
    const char *code = whisper_lang_str(langId);   // nullptr for an unknown id (whisper logs it)
    if (code == nullptr) {
        return nullptr;
    }
    // ISO 639-1 codes are plain ASCII — safe for NewStringUTF (no 4-byte UTF-8 here).
    return env->NewStringUTF(code);
}

extern "C" JNIEXPORT void JNICALL
Java_com_whispereverywhere_whisper_WhisperNative_free(
        JNIEnv *env, jobject /* this */, jlong ctxPtr) {
```

- [ ] **Step 2 — declare the Kotlin external.** In `WhisperNative.kt`, two edits.

Edit 2a — the header KDoc no longer lists exactly three functions. OLD (file top, lines 3-13):
```kotlin
/**
 * JNI bridge to the native whisper.cpp engine (libwhisper_jni.so).
 *
 * All three functions map 1:1 to whisper_jni.cpp:
 *   - init()       -> whisper_init_from_file_with_params(); returns whisper_context* as Long (0 = failure)
 *   - transcribe() -> whisper_full() with WHISPER_SAMPLING_GREEDY; returns concatenated segment text
 *   - free()       -> whisper_free()
 *
 * The returned Long is an opaque native pointer handle owned by the caller
 * (LocalWhisperEngine caches it). Never dereference it in Kotlin.
 */
```
NEW:
```kotlin
/**
 * JNI bridge to the native whisper.cpp engine (libwhisper_jni.so).
 *
 * The functions map 1:1 to whisper_jni.cpp:
 *   - init()             -> whisper_init_from_file_with_params(); returns whisper_context* as Long (0 = failure)
 *   - transcribe()       -> whisper_full() with WHISPER_SAMPLING_GREEDY; returns concatenated segment text
 *   - detectedLanguage() -> whisper_lang_str(whisper_full_lang_id()): the LAST completed transcribe's detection
 *   - free()             -> whisper_free()
 *
 * The returned Long is an opaque native pointer handle owned by the caller
 * (LocalWhisperEngine caches it). Never dereference it in Kotlin.
 */
```

Edit 2b — the external, inserted before `free`. OLD (unique):
```kotlin
    /** Frees the native whisper_context. Safe to call once per non-zero handle. */
    external fun free(ctxPtr: Long)
```
NEW:
```kotlin
    /**
     * ISO code (e.g. "de") whisper auto-detected during the LAST completed whisper_full on
     * [ctxPtr], or null when unavailable. Only meaningful IMMEDIATELY after a transcribe that
     * actually ran whisper on an auto-language call: the native early-return paths (VAD found
     * zero speech, the energy gate, empty input) never reach whisper_full, and the underlying
     * state->lang_id then still holds a PREVIOUS call's detection — possibly a previous
     * session's. Callers guard this by querying only after a non-blank transcribe (see
     * LocalWhisperEngine.runSegment). Call on the same single thread that runs transcribe.
     */
    external fun detectedLanguage(ctxPtr: Long): String?

    /** Frees the native whisper_context. Safe to call once per non-zero handle. */
    external fun free(ctxPtr: Long)
```

- [ ] **Step 3 — compile verification (the .so and the Kotlin external):**
```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:assembleDebug --no-daemon
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon
```
Expect `BUILD SUCCESSFUL` twice, zero test failures.

- [ ] **Step 4 — commit:**
```powershell
git add app/src/main/cpp/whisper_jni.cpp app/src/main/java/com/whispereverywhere/whisper/WhisperNative.kt
git commit -m "feat(jni): expose whisper_full_lang_id - detectedLanguage(ctx) after a completed transcribe (B1)" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`nClaude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT"
```

---

### Task B2: `WhisperBackend.detectedLanguage` seam — null default, gate-held native override

**Files:**
- Edit: `app/src/main/java/com/whispereverywhere/transcription/TranscriptionEngine.kt`
- Create: `app/src/test/java/com/whispereverywhere/transcription/WhisperBackendSeamTest.kt`

**Builds on:** B1 (`WhisperNative.detectedLanguage`). First edit to `TranscriptionEngine.kt` — both OLD blocks match HEAD verbatim.

**Why:** `LocalWhisperEngine` talks to the native layer only through the `WhisperBackend` seam (`TranscriptionEngine.kt:80-91`) so it stays JVM-testable. The new method gets a **default of `null`** so all 14 existing test fakes (`LocalWhisperEngineTest.kt`, `BatchTranscriberTest.kt`, `VadPlumbingTest.kt`, `FallbackTranscriptionEngineTest.kt`) keep compiling unchanged, and a backend that never detects can never pin. The production override follows the house rule at `TranscriptionEngine.kt:118-121`: every native entry point in `WhisperNativeBackend` holds the process-global `NativeComputeGate` (a fair, reentrant `ReentrantLock`).

- [ ] **Step 1 — write the failing test.** Create `app/src/test/java/com/whispereverywhere/transcription/WhisperBackendSeamTest.kt`:
```kotlin
package com.whispereverywhere.transcription

import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the WhisperBackend interface DEFAULTS every existing fake and any future backend
 * inherit. These defaults are what keep the 3.6.0 additions (language pinning, partial
 * streaming) opt-in: a backend that doesn't implement them behaves byte-for-byte like 3.5.0.
 */
class WhisperBackendSeamTest {

    /** Minimal backend: overrides ONLY the 3.5.0 surface. */
    internal class MinimalBackend : WhisperBackend {
        override fun load(modelPath: String): Long = 1L
        override fun transcribe(ctx: Long, samples: FloatArray, lang: String?, useVad: Boolean): String = "text"
        override fun release(ctx: Long) = Unit
    }

    @Test
    fun detectedLanguage_defaultsToNull_soNothingPinsAccidentally() {
        assertNull(MinimalBackend().detectedLanguage(1L))
    }
}
```

- [ ] **Step 2 — run it, expect the red:**
```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon --tests "com.whispereverywhere.transcription.WhisperBackendSeamTest"
```
Expect **compile failure**: `e: ... Unresolved reference 'detectedLanguage'` (BUILD FAILED).

- [ ] **Step 3 — implement.** In `TranscriptionEngine.kt`, two edits.

Edit 3a — the interface. OLD (unique — the `WhisperBackend` declaration; these two lines have no `override`):
```kotlin
    fun transcribe(ctx: Long, samples: FloatArray, lang: String?, useVad: Boolean = true): String
    fun release(ctx: Long)
```
NEW:
```kotlin
    fun transcribe(ctx: Long, samples: FloatArray, lang: String?, useVad: Boolean = true): String

    /**
     * ISO code whisper auto-detected during the LAST completed transcribe on [ctx], or null
     * when unavailable (never ran / unknown id / a backend with no detection). Meaningful
     * ONLY right after a transcribe(...) that returned non-blank text on an auto-language
     * call — the native early-return paths (VAD-empty, energy gate) never reach whisper_full
     * and would leave a stale id behind (see WhisperNative.detectedLanguage). Default null:
     * every existing fake keeps compiling, and a detection-less backend can never pin.
     */
    fun detectedLanguage(ctx: Long): String? = null

    fun release(ctx: Long)
```

Edit 3b — the production override. OLD (unique — the object's release override):
```kotlin
    override fun release(ctx: Long) = NativeComputeGate.serialized { WhisperNative.free(ctx) }
```
NEW:
```kotlin
    // A single native field read, but still a native-ctx touch: it follows the house rule that
    // EVERY native entry point in this backend holds the process-global gate (see the comment
    // above load()). The engine calls it on its single native-executor thread, right after the
    // transcribe whose detection it reads — the gate wait is at most one interleaved batch call,
    // the same wait the next transcribe would pay anyway.
    override fun detectedLanguage(ctx: Long): String? =
        NativeComputeGate.serialized { WhisperNative.detectedLanguage(ctx) }

    override fun release(ctx: Long) = NativeComputeGate.serialized { WhisperNative.free(ctx) }
```

- [ ] **Step 4 — run green:** the Step 2 command. Expect `BUILD SUCCESSFUL`, 1 test, 0 failures. Then the full suite (proves the 14 untouched fakes still compile):
```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon
```
Expect `BUILD SUCCESSFUL`, zero failures.

- [ ] **Step 5 — commit:**
```powershell
git add app/src/main/java/com/whispereverywhere/transcription/TranscriptionEngine.kt app/src/test/java/com/whispereverywhere/transcription/WhisperBackendSeamTest.kt
git commit -m "feat(engine): WhisperBackend.detectedLanguage seam - gate-held native read, null default (B2)" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`nClaude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT"
```

---

### Task B3: `LanguagePin` — the pure session-pin state machine (TDD)

**Files:**
- Create: `app/src/main/java/com/whispereverywhere/transcription/LanguagePin.kt`
- Create: `app/src/test/java/com/whispereverywhere/transcription/LanguagePinTest.kt`

**Builds on:** nothing — two new files.

**Why:** the pin logic must be a pure JVM-testable object, per the spec ("Kotlin keeps the pin state in the engine") and the house rule that Kotlin-side logic stays extracted and JVM-tested. Semantics (spec Workstream B): auto → detect → pinned → reset per session; an explicit language passes through and never pins. `PreferencesManager.getLanguageForApi()` (`PreferencesManager.kt:174-177`) already maps `"auto"` → `null`, and `FloatingBubbleService.kt:2076-2080` forces `"en"` for ENGLISH-scope tiers before `connect()` — so `sessionLanguage == null` is exactly "auto session" and nothing upstream needs changing.

- [ ] **Step 1 — write the failing tests.** Create `app/src/test/java/com/whispereverywhere/transcription/LanguagePinTest.kt`:
```kotlin
package com.whispereverywhere.transcription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Workstream B (3.6.0): the session-scoped language pin, pure. auto -> detect -> pinned ->
 * reset; explicit languages bypass entirely and never pin.
 */
class LanguagePinTest {

    @Test
    fun autoSession_passesNullUntilADetectionArrives() {
        val pin = LanguagePin()
        assertNull(pin.languageFor(null))       // first segment: native auto-detect
        assertNull(pin.languageFor(null))       // still auto until something is detected
    }

    @Test
    fun detection_pinsForTheRestOfTheSession() {
        val pin = LanguagePin()
        pin.onDetected(sessionLanguage = null, detected = "de")
        assertEquals("de", pin.languageFor(null))
        assertEquals("de", pin.languageFor(null))
    }

    @Test
    fun firstDetectionWins() {
        val pin = LanguagePin()
        pin.onDetected(sessionLanguage = null, detected = "de")
        pin.onDetected(sessionLanguage = null, detected = "fr")   // a later, different detection
        assertEquals("de", pin.languageFor(null))
    }

    @Test
    fun reset_clearsThePin_soTheNextSessionReDetects() {
        val pin = LanguagePin()
        pin.onDetected(sessionLanguage = null, detected = "de")
        pin.reset()
        assertNull(pin.languageFor(null))
    }

    @Test
    fun explicitLanguage_passesThroughAndNeverPins() {
        val pin = LanguagePin()
        assertEquals("en", pin.languageFor("en"))
        pin.onDetected(sessionLanguage = "en", detected = "de")   // explicit sessions never pin
        assertNull(pin.languageFor(null))
        assertEquals("en", pin.languageFor("en"))
    }

    @Test
    fun unusableDetections_neverPin() {
        val pin = LanguagePin()
        pin.onDetected(sessionLanguage = null, detected = null)
        pin.onDetected(sessionLanguage = null, detected = "")
        pin.onDetected(sessionLanguage = null, detected = "auto")
        assertNull(pin.languageFor(null))
    }
}
```

- [ ] **Step 2 — run it, expect the red:**
```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon --tests "com.whispereverywhere.transcription.LanguagePinTest"
```
Expect **compile failure**: `e: ... Unresolved reference 'LanguagePin'` (BUILD FAILED).

- [ ] **Step 3 — implement.** Create `app/src/main/java/com/whispereverywhere/transcription/LanguagePin.kt`:
```kotlin
package com.whispereverywhere.transcription

/**
 * Session-scoped language pin for auto-language local sessions (3.6.0 Workstream B).
 *
 * Multilingual whisper pays a throwaway language-detect encoder pass on EVERY segment when
 * language = auto — roughly half of multi's steady-state native cost. This object remembers the
 * language whisper detected on the session's first segment that actually produced speech, so
 * every later segment passes the concrete code and skips the detect pass entirely.
 *
 * Rules (spec 2026-08-19, Workstream B):
 *  - Only auto sessions participate: an explicit session language passes through unchanged
 *    and NEVER pins. PreferencesManager.getLanguageForApi() already maps "auto" -> null, and
 *    FloatingBubbleService forces "en" for ENGLISH-scope tiers before connect(), so
 *    sessionLanguage == null here is exactly "the user chose auto on a multilingual tier".
 *  - The pin is per-session: [reset] runs from LocalWhisperEngine.connect(). A user switching
 *    spoken language MID-session keeps the first detection until the next session — accepted
 *    trade, recorded in the spec.
 *  - First detection wins; unusable detections (null / blank / "auto") never pin.
 *
 * Threading: [onDetected] runs on the engine's single native-executor thread; [reset] on the
 * connect() caller thread; [languageFor] on the executor thread. A single @Volatile reference
 * suffices — there is no compound invariant across fields, and the engine only calls
 * [onDetected] behind its stale-listener guard, so a dead session's late segment never writes.
 */
class LanguagePin {
    @Volatile private var pinned: String? = null

    /**
     * The language to pass to the next transcribe. An explicit [sessionLanguage] always wins;
     * an auto session gets the pinned code once detected — null (= native auto-detect) before.
     */
    fun languageFor(sessionLanguage: String?): String? = sessionLanguage ?: pinned

    /**
     * Records a detection. No-ops unless this is an auto session ([sessionLanguage] == null),
     * nothing is pinned yet, and [detected] is a usable code.
     */
    fun onDetected(sessionLanguage: String?, detected: String?) {
        if (sessionLanguage != null) return
        if (pinned != null) return
        if (detected.isNullOrBlank() || detected == "auto") return
        pinned = detected
    }

    /** Clears the pin. Called at session start (LocalWhisperEngine.connect). */
    fun reset() {
        pinned = null
    }
}
```

- [ ] **Step 4 — run green:** the Step 2 command. Expect `BUILD SUCCESSFUL`, 6 tests, 0 failures.

- [ ] **Step 5 — commit:**
```powershell
git add app/src/main/java/com/whispereverywhere/transcription/LanguagePin.kt app/src/test/java/com/whispereverywhere/transcription/LanguagePinTest.kt
git commit -m "feat(engine): LanguagePin - session-scoped auto-language pin state machine (B3)" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`nClaude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT"
```

---

### Task B4: wire the pin into `LocalWhisperEngine` (TDD)

**Files:**
- Edit: `app/src/main/java/com/whispereverywhere/transcription/LocalWhisperEngine.kt`
- Create: `app/src/test/java/com/whispereverywhere/transcription/LocalWhisperEnginePinTest.kt`

**Builds on:** B1, B2, B3 — **and A4**, which already wrapped the `backend.transcribe` call in `runSegment` with the `segment-timing` instrumentation. **Edit 3c's OLD block below is quoted AS THE TREE STANDS AFTER A4** (it includes A4's `val transcribeStartNs = System.nanoTime()` line). E1/E4 also edited this file, but only `prewarm()`/`releaseContext()`, which are far from every anchor here.

**Design, stated for review:**
- The pin is queried only for segments that **paid** the native detect pass: `lang == null` (auto session) **and** `effectiveLang == null` (nothing pinned yet) — once pinned, zero extra JNI calls per segment.
- Only after `cleaned.isNotBlank()` — a blank proves nothing ran (native VAD-empty / energy-gate early returns) or whisper produced only stripped markers; in both cases `whisper_full_lang_id` may be stale, so we re-detect next segment rather than trust it.
- The pin write sits behind the **exact stale-session guard** used for resolutions — `listener === myListener` — so a previous session's late-finishing transcribe can never pin the new session (connect() has already swapped `listener`).
- No transcript content is logged, ever — the WE-DIAG line logs the language code only.
- UNTOUCHED: `EmptyExpected` semantics, the `NO_SEGMENT` contract, seq allocation, orderer rules, final-only commit, A4's timing line.

- [ ] **Step 1 — write the failing tests.** Create `app/src/test/java/com/whispereverywhere/transcription/LocalWhisperEnginePinTest.kt` (reuses `SameThreadExecutorService`, `QueueingExecutorService`, `RecordingListener`, `FakeModelPathProvider` — top-level classes in `LocalWhisperEngineTest.kt`, same package):
```kotlin
package com.whispereverywhere.transcription

import com.whispereverywhere.util.RetryPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Workstream B (3.6.0): session-scoped language pinning through LocalWhisperEngine.
 * Auto sessions (connect(null)) detect on the first speech segment and pass the pinned code
 * to every later transcribe; explicit sessions pass through and never pin; the pin dies with
 * the session. Reuses the shared fakes from LocalWhisperEngineTest.kt (same package).
 */
class LocalWhisperEnginePinTest {

    private fun fastRetry() = RetryPolicy(maxAttempts = 1, baseDelayMs = 0, maxDelayMs = 0, rng = { 0.0 })

    private val pcm = byteArrayOf(0x10, 0x00, 0x20, 0x00)

    /** Records the lang of every transcribe; scripted texts; scripted detection. */
    private class PinProbeBackend(
        private val script: List<String>,
        private val detected: String? = "de",
    ) : WhisperBackend {
        val langs = mutableListOf<String?>()
        var detectQueries = 0
        private var i = 0
        override fun load(modelPath: String): Long = 42L
        override fun transcribe(ctx: Long, samples: FloatArray, lang: String?, useVad: Boolean): String {
            langs += lang
            val text = script[i.coerceAtMost(script.size - 1)]
            i++
            return text
        }
        override fun detectedLanguage(ctx: Long): String? { detectQueries++; return detected }
        override fun release(ctx: Long) = Unit
    }

    private fun engineWith(
        backend: WhisperBackend,
        executor: java.util.concurrent.ExecutorService = SameThreadExecutorService(),
    ) = LocalWhisperEngine(
        modelPathProvider = FakeModelPathProvider("/models/multi.bin"),
        retry = fastRetry(),
        backend = backend,
        executor = executor,
    )

    @Test
    fun autoSession_firstSpeechSegmentDetects_laterSegmentsPassThePin() {
        val backend = PinProbeBackend(script = listOf("hallo", "welt", "drei"))
        val engine = engineWith(backend)
        val listener = RecordingListener()
        engine.connect(language = null, listener = listener)

        repeat(3) { engine.sendAudio(pcm); engine.commit() }

        // Segment 0 paid the native detect pass (lang=null); 1 and 2 ride the pin.
        assertEquals(listOf(null, "de", "de"), backend.langs)
        // Queried exactly once — once pinned, no more per-segment JNI reads.
        assertEquals(1, backend.detectQueries)
        assertEquals(listOf("hallo", "welt", "drei"), listener.completed)
    }

    @Test
    fun explicitLanguage_neverPins_andNeverQueriesDetection() {
        val backend = PinProbeBackend(script = listOf("hello", "world"))
        val engine = engineWith(backend)
        engine.connect(language = "en", listener = RecordingListener())

        repeat(2) { engine.sendAudio(pcm); engine.commit() }

        assertEquals(listOf("en", "en"), backend.langs)
        assertEquals(0, backend.detectQueries)
    }

    @Test
    fun connect_clearsThePin_theNextSessionReDetects() {
        val backend = PinProbeBackend(script = listOf("eins", "zwei", "drei"))
        val engine = engineWith(backend)
        engine.connect(language = null, listener = RecordingListener())
        engine.sendAudio(pcm); engine.commit()          // pins "de"
        engine.close()

        engine.connect(language = null, listener = RecordingListener())
        engine.sendAudio(pcm); engine.commit()          // must re-detect: lang=null again
        engine.sendAudio(pcm); engine.commit()          // then ride the fresh pin

        assertEquals(listOf(null, null, "de"), backend.langs)
        assertEquals(2, backend.detectQueries)          // one detection per session
    }

    @Test
    fun blankFirstSegment_doesNotPin_theNextSpeechSegmentDetects() {
        // Native early-returns (VAD-empty, energy gate) surface as blanks, and
        // whisper_full_lang_id would be STALE for them — the engine must not read a verdict
        // whisper never produced on this audio.
        val backend = PinProbeBackend(script = listOf("   ", "hallo", "welt"))
        val engine = engineWith(backend)
        engine.connect(language = null, listener = RecordingListener())

        repeat(3) { engine.sendAudio(pcm); engine.commit() }

        assertEquals(listOf(null, null, "de"), backend.langs)
        assertEquals(1, backend.detectQueries)          // never queried for the blank
    }

    @Test
    fun unavailableDetection_neverPins_soAutoKeepsDetectingNatively() {
        val backend = PinProbeBackend(script = listOf("uno", "dos"), detected = null)
        val engine = engineWith(backend)
        engine.connect(language = null, listener = RecordingListener())

        repeat(2) { engine.sendAudio(pcm); engine.commit() }

        assertEquals(listOf(null, null), backend.langs)
        assertEquals(2, backend.detectQueries)          // re-queried until something usable lands
    }

    @Test
    fun aStaleSessionsLateSegment_cannotPinTheNewSession() {
        val executor = QueueingExecutorService()
        val backend = PinProbeBackend(script = listOf("alt", "neu", "neuer"))
        val engine = engineWith(backend, executor = executor)
        val first = RecordingListener()
        engine.connect(language = null, listener = first)
        executor.tasks[0].run()                          // the model-load task
        assertTrue(first.opened)

        engine.sendAudio(pcm)
        engine.commit()                                  // queued as tasks[1], NOT yet run
        engine.close()                                   // detaches the first listener

        val second = RecordingListener()
        engine.connect(language = null, listener = second)   // ctx loaded: onOpen via controlExecutor

        executor.tasks[1].run()                          // stale segment finishes AFTER the new connect

        // The stale segment transcribed (wasted work, result dropped by the existing guard)
        // but must NOT have pinned — its detection belongs to the dead session:
        assertEquals(0, backend.detectQueries)
        assertTrue(first.resolved.isEmpty())             // pre-existing stale-guard behavior

        engine.sendAudio(pcm); engine.commit()
        executor.tasks[2].run()
        // The new session's first segment still ran native auto-detect (lang=null), then pinned.
        assertEquals(listOf(null, null), backend.langs)
        assertEquals(1, backend.detectQueries)
        engine.sendAudio(pcm); engine.commit()
        executor.tasks[3].run()
        assertEquals(listOf(null, null, "de"), backend.langs)
    }
}
```

- [ ] **Step 2 — run it, expect the red:**
```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon --tests "com.whispereverywhere.transcription.LocalWhisperEnginePinTest"
```
Expect BUILD FAILED with **5 of 6 tests failing** on assertions (e.g. `autoSession_firstSpeechSegmentDetects_laterSegmentsPassThePin` fails `expected:<[null, de, de]> but was:<[null, null, null]>`). `explicitLanguage_neverPins_andNeverQueriesDetection` passes by construction — it pins the pre-existing pass-through.

- [ ] **Step 3 — implement.** Four edits in `LocalWhisperEngine.kt`.

Edit 3a — the pin field. OLD (unique, `:75-76`):
```kotlin
    @Volatile private var listener: TranscriptionEngine.Listener? = null
    @Volatile private var language: String? = null
```
NEW:
```kotlin
    @Volatile private var listener: TranscriptionEngine.Listener? = null
    @Volatile private var language: String? = null

    /**
     * Session-scoped language pin (3.6.0 Workstream B). Auto-language sessions only: once the
     * first speech-producing segment's detection lands, later segments pass the concrete code
     * and skip multilingual whisper's per-segment detect-encode pass. Reset in [connect];
     * written only behind the stale-listener guard in [runSegment], so a previous session's
     * late segment can never pin the new session.
     */
    private val languagePin = LanguagePin()
```

Edit 3b — reset on connect. OLD (unique, `:88-91`):
```kotlin
        // Per-session state: a fresh SegmentOrderer starts at head 0, so seq numbering must
        // restart with it — otherwise the new session's first segment looks like a late duplicate
        // of the old session's and is dropped. Under bufferLock because commit() reads it there.
        synchronized(bufferLock) { nextSeq = 0L }
```
NEW:
```kotlin
        // Per-session state: a fresh SegmentOrderer starts at head 0, so seq numbering must
        // restart with it — otherwise the new session's first segment looks like a late duplicate
        // of the old session's and is dropped. Under bufferLock because commit() reads it there.
        synchronized(bufferLock) { nextSeq = 0L }
        // Per-session language detection (spec Workstream B): the pin never outlives a session,
        // so a user switching languages BETWEEN sessions always re-detects.
        languagePin.reset()
```

Edit 3c — pass the effective language. **OLD is post-A4** (unique — inside `runSegment`; note A4's `transcribeStartNs` line):
```kotlin
                val samples = AudioMath.pcm16ToFloat(pcm)
                android.util.Log.i("WE-DIAG", "transcribe START seq=$seq samples=${samples.size} lang=$lang")
                val transcribeStartNs = System.nanoTime()
                val text = runBlocking {
                    retry.retry { backend.transcribe(ctx, samples, lang) }
                }
```
NEW:
```kotlin
                val samples = AudioMath.pcm16ToFloat(pcm)
                // B (3.6.0): an explicit language passes through untouched; an auto session
                // (lang == null) rides the session pin once the first speech segment detected it.
                val effectiveLang = languagePin.languageFor(lang)
                android.util.Log.i(
                    "WE-DIAG",
                    "transcribe START seq=$seq samples=${samples.size} lang=$lang effective=$effectiveLang",
                )
                val transcribeStartNs = System.nanoTime()
                val text = runBlocking {
                    retry.retry { backend.transcribe(ctx, samples, effectiveLang) }
                }
```
(A4's `segment-timing` block sits immediately below this and is NOT touched — it still closes over `transcribeStartNs` and `samples`.)

Edit 3d — pin after a proven-speech segment. OLD (unique — the DONE log plus the blank check, `:269-273`):
```kotlin
                android.util.Log.i(
                    "WE-DIAG",
                    "transcribe DONE seq=$seq rawLen=${text.length} cleanLen=${cleaned.length}",
                )
                if (cleaned.isBlank()) {
```
NEW:
```kotlin
                android.util.Log.i(
                    "WE-DIAG",
                    "transcribe DONE seq=$seq rawLen=${text.length} cleanLen=${cleaned.length}",
                )
                // B (3.6.0 language pinning): query the detection only for segments that PAID the
                // native detect pass (auto session, nothing pinned yet) and only when whisper
                // demonstrably ran on THIS audio — a non-blank result. Every native early return
                // (VAD-empty, energy gate) yields a blank, and whisper_full_lang_id would then be
                // STALE (it persists on the ctx across calls, even across sessions). The stale-
                // listener guard — the exact `listener === myListener` identity check resolutions
                // use — keeps a dead session's late segment from pinning the new session.
                if (lang == null && effectiveLang == null && cleaned.isNotBlank() && listener === myListener) {
                    val detected = backend.detectedLanguage(ctx)
                    languagePin.onDetected(sessionLanguage = lang, detected = detected)
                    // Language code only — never transcript content.
                    android.util.Log.i("WE-DIAG", "language-pin: detected=$detected")
                }
                if (cleaned.isBlank()) {
```

- [ ] **Step 4 — run green:** the Step 2 command — expect `BUILD SUCCESSFUL`, 6 tests, 0 failures. Then the full suite:
```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon
```
Expect `BUILD SUCCESSFUL`, zero failures (all pre-existing `LocalWhisperEngineTest` tests connect with `language = "en"`, so `effectiveLang == lang` for every one of them).

- [ ] **Step 5 — verify the service plumb needs no change (read-only check):** confirm `FloatingBubbleService.kt:2072-2081` still resolves `lang` as `"en"` for ENGLISH-scope tiers and `app.preferencesManager.getLanguageForApi()` otherwise (null = auto) — that is the entire upstream contract the pin relies on. No service edit in this task.

- [ ] **Step 6 — commit:**
```powershell
git add app/src/main/java/com/whispereverywhere/transcription/LocalWhisperEngine.kt app/src/test/java/com/whispereverywhere/transcription/LocalWhisperEnginePinTest.kt
git commit -m "feat(engine): pin the detected language per session - auto multi stops paying the per-segment detect pass (B4)" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`nClaude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT"
```

---

## Workstream D — Native partial streaming (the real-time feel)

### Task D1: JNI — whisper.cpp new-segment callback → Kotlin (global ref + `CallVoidMethod`) — **NATIVE**

**Files:**
- Edit: `app/src/main/cpp/whisper_jni.cpp`
- Edit: `app/src/main/java/com/whispereverywhere/whisper/WhisperNative.kt`
- Edit: `app/proguard-rules.pro`

**Builds on:** B1 (both shared files). B1 inserted `detectedLanguage` immediately ABOVE the `_free` entry point in the .cpp — below both anchors used here — and inserted its Kotlin external above `free`, below the `transcribeRaw`/`transcribe` pair anchored here. Every OLD block below is unchanged by B1.

**Why:** during one `whisper_full` call, whisper.cpp fires `params.new_segment_callback` (declared `whisper.h:463`, wired at `whisper.h:562-563`) after each newly decoded segment; the temperature-fallback re-decodes null it internally, so only accepted segments surface. Forwarding the running text to Kotlin mid-inference is the raw material for `onDelta` (Task D4).

**Threading and safety design, stated for review:**
- `whisper_full` is synchronous and invokes the callback **on the thread that called it** — the engine's single native-executor thread, i.e. the same thread that entered `transcribeRaw`. That thread is by definition JVM-attached, so `GetEnv` succeeds; `AttachCurrentThread` is kept as a defensive fallback (with matching detach) in case a future whisper.cpp moves the callback to a worker thread — it must degrade, not crash.
- The callback object is held as a **`NewGlobalRef`** (not the frame-local ref) and invoked via **`CallVoidMethod`**; the global ref is deleted immediately after `whisper_full` returns, on every path — no callbacks can fire after it returns, and the stack-allocated `we_segment_cb_ctx` never outlives the call.
- **No native lock is held while calling back into the JVM:** `g_vad_mutex` is acquired and released entirely inside `we_vad_filter`, before `whisper_full` starts; this file holds no other lock. (Kotlin-side: the callback runs while `WhisperNativeBackend` holds `NativeComputeGate` — a fair *reentrant* lock — so the Kotlin closure must stay lock-free and never re-enter the backend; Task D4's closure is throttle-check + listener forward only.)
- Text crosses as **raw UTF-8 bytes** for the same reason `transcribeRaw`'s result does (`whisper_jni.cpp:293-296`): `NewStringUTF` aborts the process on 4-byte UTF-8.
- A pending Java exception from the callback is cleared — a preview must never abort the transcribe.

**Verification contract (native):** (a) `:app:assembleDebug` builds `libwhisper_jni.so`; (b) the full JVM suite stays green; (c) the owner on-device check it feeds (H2): "Streaming deltas visibly render during local inference" — words appear on the bubble's delta strip while a 4-15 s segment is still transcribing. NEVER `:app:installDebug` / `:app:connectedDebugAndroidTest`.

- [ ] **Step 1 — native: callback context + trampoline + new parameter.** In `whisper_jni.cpp`, two edits.

Edit 1a. OLD (unique — the transcribeRaw opener, `:159-163`):
```cpp
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_whispereverywhere_whisper_WhisperNative_transcribeRaw(
        JNIEnv *env, jobject /* this */,
        jlong ctxPtr, jfloatArray samples, jstring lang, jboolean translate,
        jstring vadModelPath) {
```
NEW:
```cpp
// ---------------------------------------------------------------------------------------------
// 3.6.0 Workstream D: incremental new-segment delivery. whisper_full invokes
// new_segment_callback ON THE CALLING THREAD (the engine's single native-executor thread) after
// each accepted segment; we forward the FULL running text of THIS call to Kotlin — the same
// "replace the whole preview" shape the cloud-live protocols use — as raw UTF-8 bytes
// (NewStringUTF aborts on 4-byte UTF-8; multilingual segments contain it). Global ref +
// CallVoidMethod; no native lock is held here (g_vad_mutex lives entirely inside we_vad_filter,
// which finished before whisper_full started).
// ---------------------------------------------------------------------------------------------

struct we_segment_cb_ctx {
    JavaVM   *vm       = nullptr;
    jobject   callback = nullptr;   // global ref to the Kotlin NewSegmentCallback
    jmethodID method   = nullptr;   // onRunningText([B)V
};

static void we_on_new_segment(struct whisper_context * /*ctx*/, struct whisper_state *state,
                              int /*n_new*/, void *user_data) {
    auto *cb = static_cast<we_segment_cb_ctx *>(user_data);
    if (cb == nullptr || cb->callback == nullptr || state == nullptr) {
        return;
    }
    JNIEnv *env = nullptr;
    bool attachedHere = false;
    if (cb->vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        // Defensive only: whisper_full calls this on the thread that entered transcribeRaw,
        // which is already attached — GetEnv succeeds there. This fallback keeps a future
        // whisper.cpp worker-thread callback from crashing instead of degrading.
        if (cb->vm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            return;
        }
        attachedHere = true;
    }
    std::string running;
    const int nSeg = whisper_full_n_segments_from_state(state);
    for (int i = 0; i < nSeg; ++i) {
        const char *seg = whisper_full_get_segment_text_from_state(state, i);
        if (seg != nullptr) {
            running += seg;
        }
    }
    const jsize len = static_cast<jsize>(running.size());
    jbyteArray arr = env->NewByteArray(len);
    if (arr != nullptr) {
        if (len > 0) {
            env->SetByteArrayRegion(arr, 0, len, reinterpret_cast<const jbyte *>(running.data()));
        }
        env->CallVoidMethod(cb->callback, cb->method, arr);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();   // a preview callback must never abort the transcribe
        }
        env->DeleteLocalRef(arr);
    }
    if (attachedHere) {
        cb->vm->DetachCurrentThread();
    }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_whispereverywhere_whisper_WhisperNative_transcribeRaw(
        JNIEnv *env, jobject /* this */,
        jlong ctxPtr, jfloatArray samples, jstring lang, jboolean translate,
        jstring vadModelPath, jobject segmentCallback) {
```

Edit 1b. OLD (unique — the whisper_full call, `:280-283`):
```cpp
    if (whisper_full(ctx, params, pcm.data(), static_cast<int>(pcm.size())) != 0) {
        LOGE("whisper_full failed");
        return emptyResult();
    }
```
NEW:
```cpp
    // D (3.6.0): arm the new-segment trampoline. cbCtx is stack-local — whisper_full is
    // synchronous and no callback can fire after it returns, which is also why the global ref
    // is deleted immediately after, on every path.
    we_segment_cb_ctx cbCtx;
    if (segmentCallback != nullptr) {
        env->GetJavaVM(&cbCtx.vm);
        jclass cbClass = env->GetObjectClass(segmentCallback);
        cbCtx.method = env->GetMethodID(cbClass, "onRunningText", "([B)V");
        env->DeleteLocalRef(cbClass);
        if (cbCtx.vm != nullptr && cbCtx.method != nullptr) {
            cbCtx.callback = env->NewGlobalRef(segmentCallback);
            params.new_segment_callback           = we_on_new_segment;
            params.new_segment_callback_user_data = &cbCtx;
        } else {
            LOGE("new-segment callback wiring failed (no vm/method) — transcribing without preview");
            // GetMethodID raised (NoSuchMethodError) — degrade, never abort. Leaving it pending
            // makes the NewByteArray/SetByteArrayRegion below run with an exception in flight,
            // which CheckJNI turns into a process abort: the wrong failure direction for a
            // preview-only feature, and exactly the release-only R8 slip the proguard keeps
            // in Step 3 exist to prevent.
            if (env->ExceptionCheck()) env->ExceptionClear();
        }
    }

    const int fullRc = whisper_full(ctx, params, pcm.data(), static_cast<int>(pcm.size()));
    if (cbCtx.callback != nullptr) {
        env->DeleteGlobalRef(cbCtx.callback);
    }
    if (fullRc != 0) {
        LOGE("whisper_full failed");
        return emptyResult();
    }
```

- [ ] **Step 2 — Kotlin external + wrapper.** In `WhisperNative.kt`. OLD (unique — the whole transcribeRaw + transcribe pair, `:35-61`):
```kotlin
    /**
     * Runs whisper_full on float32 PCM (mono, 16 kHz, [-1,1]). Returns raw UTF-8 bytes:
     * NewStringUTF in JNI aborts on 4-byte UTF-8 (emoji / rare CJK from multilingual models),
     * so the native side hands bytes across and [transcribe] decodes them safely here.
     */
    external fun transcribeRaw(
        ctxPtr: Long,
        samples: FloatArray,
        lang: String?,
        translate: Boolean,
        vadModelPath: String?,
    ): ByteArray

    /**
     * @param ctxPtr handle from [init]
     * @param lang   ISO code (e.g. "en"), or null/"auto" for auto-detect
     * @param translate true to translate to English; false for transcribe-in-language
     * @param vadModelPath path to a ggml Silero VAD model, or null to run without VAD.
     *        With VAD, silence/non-speech is trimmed natively before the encoder runs.
     */
    fun transcribe(
        ctxPtr: Long,
        samples: FloatArray,
        lang: String?,
        translate: Boolean,
        vadModelPath: String?,
    ): String = String(transcribeRaw(ctxPtr, samples, lang, translate, vadModelPath), Charsets.UTF_8)
```
NEW:
```kotlin
    /**
     * Receives the in-flight transcribe's FULL running text after each newly decoded native
     * segment. Invoked by whisper_jni's new-segment trampoline (CallVoidMethod on a global
     * ref) ON THE THREAD THAT CALLED [transcribeRaw], while whisper_full is still executing.
     * Raw UTF-8 bytes for the same reason [transcribeRaw] returns them: NewStringUTF aborts
     * on 4-byte UTF-8. Implementations must be fast and lock-free (they run inside the native
     * decode loop, and the process-global NativeComputeGate is held by this thread) and must
     * never call back into [WhisperNative].
     *
     * RELEASE builds: onRunningText is resolved via JNI GetMethodID BY NAME — the matching
     * keep rules in app/proguard-rules.pro must stay, or R8 renames it and deltas silently
     * vanish in release only.
     */
    fun interface NewSegmentCallback {
        fun onRunningText(textUtf8: ByteArray)
    }

    /**
     * Runs whisper_full on float32 PCM (mono, 16 kHz, [-1,1]). Returns raw UTF-8 bytes:
     * NewStringUTF in JNI aborts on 4-byte UTF-8 (emoji / rare CJK from multilingual models),
     * so the native side hands bytes across and [transcribe] decodes them safely here.
     * [callback] (nullable) streams incremental running text — see [NewSegmentCallback].
     */
    external fun transcribeRaw(
        ctxPtr: Long,
        samples: FloatArray,
        lang: String?,
        translate: Boolean,
        vadModelPath: String?,
        callback: NewSegmentCallback?,
    ): ByteArray

    /**
     * @param ctxPtr handle from [init]
     * @param lang   ISO code (e.g. "en"), or null/"auto" for auto-detect
     * @param translate true to translate to English; false for transcribe-in-language
     * @param vadModelPath path to a ggml Silero VAD model, or null to run without VAD.
     *        With VAD, silence/non-speech is trimmed natively before the encoder runs.
     * @param onNewSegment optional preview stream: the full text decoded so far in THIS call,
     *        delivered on the calling thread mid-inference (see [NewSegmentCallback]); null = off.
     */
    fun transcribe(
        ctxPtr: Long,
        samples: FloatArray,
        lang: String?,
        translate: Boolean,
        vadModelPath: String?,
        onNewSegment: ((String) -> Unit)? = null,
    ): String {
        val callback = onNewSegment?.let { emit ->
            NewSegmentCallback { bytes -> emit(String(bytes, Charsets.UTF_8)) }
        }
        return String(transcribeRaw(ctxPtr, samples, lang, translate, vadModelPath, callback), Charsets.UTF_8)
    }
```
(The existing `WhisperNativeBackend` call sites pass no `onNewSegment` and keep compiling via the default. C8 later calls this 5-arg form too — the default keeps that valid.)

- [ ] **Step 3 — proguard keep rules** (release minifies, `app/build.gradle.kts:112`; the existing `-keep class ...WhisperNative { *; }` does NOT cover the nested interface or lambda implementors). In `app/proguard-rules.pro`, OLD (unique, `:60-62`):
```
# --- Native whisper.cpp JNI bridge (Task 1) ---
-keepclasseswithmembernames class * { native <methods>; }
-keep class com.whispereverywhere.whisper.WhisperNative { *; }
```
NEW:
```
# --- Native whisper.cpp JNI bridge (Task 1) ---
-keepclasseswithmembernames class * { native <methods>; }
-keep class com.whispereverywhere.whisper.WhisperNative { *; }
# 3.6.0 partial streaming: whisper_jni resolves onRunningText via GetMethodID BY NAME on
# whatever class implements NewSegmentCallback (usually a Kotlin lambda class). R8 renaming or
# stripping it makes preview deltas silently vanish in RELEASE builds only — the class of
# failure this project has been bitten by twice (see the sherpa-onnx and OkHttp sections).
-keep interface com.whispereverywhere.whisper.WhisperNative$NewSegmentCallback { *; }
-keepclassmembers class * implements com.whispereverywhere.whisper.WhisperNative$NewSegmentCallback {
    public void onRunningText(byte[]);
}
```

- [ ] **Step 4 — compile verification:**
```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:assembleDebug --no-daemon
```
Expect `BUILD SUCCESSFUL` (the JNI symbol name `Java_..._transcribeRaw` is unchanged — parameters are not name-mangled for non-overloaded natives — so the Kotlin external binds to the new signature). Then the JVM suite:
```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon
```
Expect `BUILD SUCCESSFUL`, zero failures.

- [ ] **Step 5 — commit:**
```powershell
git add app/src/main/cpp/whisper_jni.cpp app/src/main/java/com/whispereverywhere/whisper/WhisperNative.kt app/proguard-rules.pro
git commit -m "feat(jni): whisper.cpp new-segment callback -> Kotlin - running text streams mid-inference (D1)" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`nClaude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT"
```

---

### Task D2: `WhisperBackend.transcribeStreaming` seam — default delegates, native impl streams

**Files:**
- Edit: `app/src/main/java/com/whispereverywhere/transcription/TranscriptionEngine.kt`
- Edit: `app/src/test/java/com/whispereverywhere/transcription/WhisperBackendSeamTest.kt`

**Builds on:** B2 (its interface edit landed first — Edit 3a's OLD anchor below is B2's added `detectedLanguage` default plus the `release` line), D1 (`WhisperNative.transcribe`'s new `onNewSegment` parameter). Edit 3b's anchor (the `transcribe` override) is untouched by B2.

- [ ] **Step 1 — write the failing test.** In `WhisperBackendSeamTest.kt`, OLD (unique — the existing test method):
```kotlin
    @Test
    fun detectedLanguage_defaultsToNull_soNothingPinsAccidentally() {
        assertNull(MinimalBackend().detectedLanguage(1L))
    }
```
NEW:
```kotlin
    @Test
    fun detectedLanguage_defaultsToNull_soNothingPinsAccidentally() {
        assertNull(MinimalBackend().detectedLanguage(1L))
    }

    @Test
    fun transcribeStreaming_defaultsToPlainTranscribe_withNoDeltas() {
        var callbacks = 0
        val text = MinimalBackend().transcribeStreaming(1L, FloatArray(4), lang = null) { callbacks++ }
        assertEquals("text", text)
        assertEquals(0, callbacks)   // the default streams nothing: byte-for-byte 3.5.0 behavior
    }
```
And extend the imports. OLD:
```kotlin
import org.junit.Assert.assertNull
import org.junit.Test
```
NEW:
```kotlin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
```

- [ ] **Step 2 — run it, expect the red:**
```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon --tests "com.whispereverywhere.transcription.WhisperBackendSeamTest"
```
Expect **compile failure**: `e: ... Unresolved reference 'transcribeStreaming'` (BUILD FAILED).

- [ ] **Step 3 — implement.** Two edits in `TranscriptionEngine.kt`.

Edit 3a — the interface (**OLD is post-B2**). OLD (unique):
```kotlin
    fun detectedLanguage(ctx: Long): String? = null

    fun release(ctx: Long)
```
NEW:
```kotlin
    fun detectedLanguage(ctx: Long): String? = null

    /**
     * Like [transcribe], additionally delivering the in-flight call's running text after each
     * newly decoded native segment (3.6.0 Workstream D). [onNewSegment] receives the FULL text
     * decoded so far in THIS call, on the SAME thread that invoked transcribeStreaming, while
     * the native call is still executing — and, for the production backend, while this thread
     * holds the process-global [NativeComputeGate]. Callers must therefore keep the closure
     * lock-free and must never re-enter the backend from inside it. PREVIEW-ONLY: the returned
     * String remains the only authoritative result. Default: plain [transcribe], zero deltas —
     * every existing fake keeps 3.5.0 behavior untouched.
     */
    fun transcribeStreaming(
        ctx: Long,
        samples: FloatArray,
        lang: String?,
        useVad: Boolean = true,
        onNewSegment: (String) -> Unit,
    ): String = transcribe(ctx, samples, lang, useVad)

    fun release(ctx: Long)
```

Edit 3b — the production backend: one shared private impl so the GpuPolicy sentinel logic exists exactly once. OLD (unique — the whole current transcribe override, `:135-155`):
```kotlin
    override fun transcribe(ctx: Long, samples: FloatArray, lang: String?, useVad: Boolean): String =
        NativeComputeGate.serialized {
            val vad = if (useVad) VadModel.path() else null   // batch passes false -> no native VAD
            val validating = GpuPolicy.needsComputeValidation()
            if (!validating) {
                return@serialized WhisperNative.transcribe(
                    ctx, samples, lang, translate = false, vadModelPath = vad
                )
            }
            GpuPolicy.onGpuComputeStarting()
            var ok = false
            try {
                val text = WhisperNative.transcribe(
                    ctx, samples, lang, translate = false, vadModelPath = vad
                )
                ok = true
                text
            } finally {
                GpuPolicy.onGpuComputeFinished(ok)
            }
        }
```
NEW:
```kotlin
    override fun transcribe(ctx: Long, samples: FloatArray, lang: String?, useVad: Boolean): String =
        transcribeInternal(ctx, samples, lang, useVad, onNewSegment = null)

    override fun transcribeStreaming(
        ctx: Long,
        samples: FloatArray,
        lang: String?,
        useVad: Boolean,
        onNewSegment: (String) -> Unit,
    ): String = transcribeInternal(ctx, samples, lang, useVad, onNewSegment)

    // The one place the gate + GpuPolicy sentinel wrap a native whisper_full. [onNewSegment]
    // (nullable) is invoked by the JNI trampoline on THIS thread while the gate is held —
    // see WhisperBackend.transcribeStreaming's contract for why the closure must stay lock-free.
    private fun transcribeInternal(
        ctx: Long,
        samples: FloatArray,
        lang: String?,
        useVad: Boolean,
        onNewSegment: ((String) -> Unit)?,
    ): String = NativeComputeGate.serialized {
        val vad = if (useVad) VadModel.path() else null   // batch passes false -> no native VAD
        val validating = GpuPolicy.needsComputeValidation()
        if (!validating) {
            return@serialized WhisperNative.transcribe(
                ctx, samples, lang, translate = false, vadModelPath = vad, onNewSegment = onNewSegment
            )
        }
        GpuPolicy.onGpuComputeStarting()
        var ok = false
        try {
            val text = WhisperNative.transcribe(
                ctx, samples, lang, translate = false, vadModelPath = vad, onNewSegment = onNewSegment
            )
            ok = true
            text
        } finally {
            GpuPolicy.onGpuComputeFinished(ok)
        }
    }
```

- [ ] **Step 4 — run green:** the Step 2 command — expect `BUILD SUCCESSFUL`, 2 tests, 0 failures. Then the full suite:
```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon
```
Expect `BUILD SUCCESSFUL`, zero failures.

- [ ] **Step 5 — commit:**
```powershell
git add app/src/main/java/com/whispereverywhere/transcription/TranscriptionEngine.kt app/src/test/java/com/whispereverywhere/transcription/WhisperBackendSeamTest.kt
git commit -m "feat(engine): WhisperBackend.transcribeStreaming seam - default delegates, native impl streams (D2)" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`nClaude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT"
```

---

### Task D3: `DeltaThrottle` — the ~150 ms preview rate limiter (TDD)

**Files:**
- Create: `app/src/main/java/com/whispereverywhere/transcription/DeltaThrottle.kt`
- Create: `app/src/test/java/com/whispereverywhere/transcription/DeltaThrottleTest.kt`

**Builds on:** nothing — two new files.

**Why:** spec D3 — "throttle callback→UI delivery (post at most every ~150 ms) so JNI churn never floods Main." Pure and clock-injected so the JVM tests are deterministic. Dropping intermediates loses nothing: every delta carries the FULL running text, so the next emit (or the terminal resolution) supersedes it.

- [ ] **Step 1 — write the failing tests.** Create `app/src/test/java/com/whispereverywhere/transcription/DeltaThrottleTest.kt`:
```kotlin
package com.whispereverywhere.transcription

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeltaThrottleTest {

    @Test
    fun firstEmitAlwaysPasses() {
        val throttle = DeltaThrottle(minIntervalMs = 150, now = { 0L })
        assertTrue(throttle.shouldEmit())
    }

    @Test
    fun emitsInsideTheWindowAreSuppressed() {
        var t = 0L
        val throttle = DeltaThrottle(minIntervalMs = 150, now = { t })
        assertTrue(throttle.shouldEmit())
        t = 100
        assertFalse(throttle.shouldEmit())
        t = 149
        assertFalse(throttle.shouldEmit())
    }

    @Test
    fun anEmitAfterTheWindowPasses_andReopensTheWindow() {
        var t = 0L
        val throttle = DeltaThrottle(minIntervalMs = 150, now = { t })
        assertTrue(throttle.shouldEmit())
        t = 150
        assertTrue(throttle.shouldEmit())
        t = 200                                  // only 50 ms after the second emit
        assertFalse(throttle.shouldEmit())
    }

    @Test
    fun reset_allowsAnImmediateEmit() {
        val throttle = DeltaThrottle(minIntervalMs = 150, now = { 0L })
        assertTrue(throttle.shouldEmit())
        throttle.reset()                          // new segment
        assertTrue(throttle.shouldEmit())         // same clock instant, still passes
    }
}
```

- [ ] **Step 2 — run it, expect the red:**
```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon --tests "com.whispereverywhere.transcription.DeltaThrottleTest"
```
Expect **compile failure**: `e: ... Unresolved reference 'DeltaThrottle'` (BUILD FAILED).

- [ ] **Step 3 — implement.** Create `app/src/main/java/com/whispereverywhere/transcription/DeltaThrottle.kt`:
```kotlin
package com.whispereverywhere.transcription

/**
 * Rate limiter for preview-delta delivery (3.6.0 Workstream D). whisper.cpp's new-segment
 * callback can fire in bursts (several segments decoded back-to-back); forwarding every one
 * would post JNI-churned text onto Main faster than it can usefully render. At most one emit
 * per [minIntervalMs] keeps the preview fluid without flooding.
 *
 * Dropping intermediates loses nothing: deltas are preview-only and each carries the FULL
 * running text, so the next emit — or the segment's terminal resolution — supersedes them.
 *
 * Threading: confined to LocalWhisperEngine's single native-executor thread (reset at segment
 * start, checked inside the native callback, which whisper_full invokes on that same thread) —
 * no synchronization needed. [now] is injectable for deterministic JVM tests; milliseconds,
 * only differences are used.
 */
class DeltaThrottle(
    private val minIntervalMs: Long = 150L,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var hasEmitted = false
    private var lastEmitMs = 0L

    /** True if the caller may emit now; records the emit when it says yes. */
    fun shouldEmit(): Boolean {
        val t = now()
        if (hasEmitted && t - lastEmitMs < minIntervalMs) return false
        hasEmitted = true
        lastEmitMs = t
        return true
    }

    /** New segment: its first delta should render immediately. */
    fun reset() {
        hasEmitted = false
    }
}
```

- [ ] **Step 4 — run green:** the Step 2 command — expect `BUILD SUCCESSFUL`, 4 tests, 0 failures.

- [ ] **Step 5 — commit:**
```powershell
git add app/src/main/java/com/whispereverywhere/transcription/DeltaThrottle.kt app/src/test/java/com/whispereverywhere/transcription/DeltaThrottleTest.kt
git commit -m "feat(engine): DeltaThrottle - 150ms preview-delta rate limiter (D3)" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`nClaude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT"
```

---

### Task D4: `LocalWhisperEngine` emits throttled preview deltas — final-only commit untouched (TDD)

**Files:**
- Edit: `app/src/main/java/com/whispereverywhere/transcription/LocalWhisperEngine.kt`
- Edit: `app/src/main/java/com/whispereverywhere/transcription/TranscriptionEngine.kt` (one KDoc)
- Edit: `app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt` (one guard line inside the `onDelta` handler)
- Edit: `app/src/test/java/com/whispereverywhere/transcription/LocalWhisperEngineTest.kt` (one comment)
- Create: `app/src/test/java/com/whispereverywhere/transcription/LocalWhisperEngineStreamingTest.kt`

**Builds on:** D2, D3, **B4** (its `languagePin` field is Edit 3b's anchor and its `effectiveLang` is inside Edit 3d's anchor) and **A4** (Edit 3d's OLD block includes A4's `transcribeStartNs` line, and A4's `segment-timing` block immediately below is deliberately left in place so the RTF measurement still wraps the streaming call). Also **A2/E2/E4/E5/E6** for the service file — all five edited other regions (the field pair, the imports and `onCreate` tail, two top-level functions, `startRecording`, the ticker head and the FINALIZING branch); the `onDelta` handler quoted in Edit 3i is untouched by every one of them and still matches HEAD verbatim.

**Design, stated for review:**
- The engine calls `backend.transcribeStreaming(...)`; the closure runs **on the native executor thread, mid-inference, while `NativeComputeGate` is held by that thread** — it is strictly lock-free: throttle check + `myListener.onDelta(running)`. It never touches `bufferLock`, never re-enters the backend, never logs delta text (deltas ARE user speech).
- **Stale-session guard, exact:** `listener === myListener` — the same identity check `runSegment` already uses for `onSegmentResolved` and `onError`. `close()` nulls `listener`, `connect()` replaces it; either way callbacks from a transcribe that outlives its session are dropped.
- **Final-only commit untouched (UNTOUCHABLE):** deltas go ONLY to `onDelta`. The committed text still comes exclusively from the returned `String` → `TranscriptText.clean` → `SegmentOutcome` → `onSegmentResolved`. No delta ever reaches the orderer, the external field, or history — the service's `onDelta` handler (`FloatingBubbleService.kt:2113-2141`) writes only `transcriptionDeltaText`, verified in D5.
- After a segment that streamed at least one delta reaches its terminal outcome, the engine emits `onDelta("")` — the service's existing blank-delta branch hides the strip (`:2137-2139`), so committed text isn't shown twice (strip + accumulating window). Emitted only when something streamed, so non-streaming backends (every existing fake, and batch) keep the byte-for-byte 3.5.0 callback sequence. Both hops to Main are `serviceScope.launch(Dispatchers.Main)` from the same thread, so FIFO order guarantees clear-then-append.
- ~150 ms throttle via D3's `DeltaThrottle`, reset per segment so each segment's first words render immediately; the clock is an injected constructor parameter (trailing, defaulted — every existing construction site keeps compiling).
- **Service plumbing needs almost no change — with ONE required exception (Edit 3i).** What is already fine: `showSessionPreview` runs for EVERY session and resets the strip (`:2003-2004`), the `onDelta` handler has no live/context gate and already does the grow-reclamp + scroll-to-newest dance (`:2118-2140`), and the delta-strip reset/hide paths (`deliverReleasedText` at `:2432-2438`, `teardownRealtime` at `:2389`) cover session end. What is NOT: the stop tap sets `transcriptionDeltaText` to the 3.5.0 FINALIZING status line ("Finishing… (waiting on provider)" / "Finishing transcript…") and then flushes the tail segment (`:2220-2231`) — and that tail segment now STREAMS. Its deltas would overwrite the status line mid-drain, and the terminal `onDelta("")` would then hide the strip for the rest of it, killing exactly the line E6's new ticker counts up beside and H2 asks the owner to verify. The codebase already knows this hazard: `deliverReleasedText` guards its identical reset with `if (currentState != BubbleState.FINALIZING)` (`:2432-2438`). Edit 3i adds the same guard to the handler — gate the SINK, not the engine, so the engine's contract stays "always stream, always clear" and every other session state is untouched.

- [ ] **Step 1 — write the failing tests.** Create `app/src/test/java/com/whispereverywhere/transcription/LocalWhisperEngineStreamingTest.kt` (reuses the shared fakes from `LocalWhisperEngineTest.kt`, same package):
```kotlin
package com.whispereverywhere.transcription

import com.whispereverywhere.util.RetryPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Workstream D (3.6.0): native partial streaming through LocalWhisperEngine. Deltas are
 * PREVIEW-ONLY — the final-only commit contract is pinned here: committed text comes
 * exclusively from segment resolution regardless of what streamed. Reuses the shared fakes
 * from LocalWhisperEngineTest.kt (same package).
 */
class LocalWhisperEngineStreamingTest {

    private fun fastRetry() = RetryPolicy(maxAttempts = 1, baseDelayMs = 0, maxDelayMs = 0, rng = { 0.0 })

    private val pcm = byteArrayOf(0x10, 0x00, 0x20, 0x00)

    /** Streams [deltas] through onNewSegment (running [advance] between them), then returns [finalText]. */
    private class StreamingScriptBackend(
        private val deltas: List<String>,
        private val finalText: String,
        private val advance: () -> Unit = {},
    ) : WhisperBackend {
        override fun load(modelPath: String): Long = 42L
        override fun transcribe(ctx: Long, samples: FloatArray, lang: String?, useVad: Boolean): String = finalText
        override fun transcribeStreaming(
            ctx: Long,
            samples: FloatArray,
            lang: String?,
            useVad: Boolean,
            onNewSegment: (String) -> Unit,
        ): String {
            for (d in deltas) { onNewSegment(d); advance() }
            return finalText
        }
        override fun release(ctx: Long) = Unit
    }

    private fun engineWith(
        backend: WhisperBackend,
        clock: () -> Long,
        executor: java.util.concurrent.ExecutorService = SameThreadExecutorService(),
    ) = LocalWhisperEngine(
        modelPathProvider = FakeModelPathProvider("/models/pro.bin"),
        retry = fastRetry(),
        backend = backend,
        executor = executor,
        deltaClock = clock,
    )

    @Test
    fun streamingDeltas_renderDuringInference_andStayPreviewOnly() {
        var now = 0L
        val backend = StreamingScriptBackend(
            deltas = listOf(" Hello", " Hello world"),
            finalText = " Hello world.",
            advance = { now += 200 },              // clear of the 150 ms throttle window
        )
        val engine = engineWith(backend, clock = { now })
        val listener = RecordingListener()
        engine.connect(language = "en", listener = listener)
        engine.sendAudio(pcm)
        engine.commit()

        // Both deltas forwarded UNTRIMMED (preview), then the terminal blank clears the strip.
        assertEquals(listOf(" Hello", " Hello world", ""), listener.deltas)
        // FINAL-ONLY COMMIT, PINNED: committed text comes exclusively from the returned String
        // via segment resolution — exactly one Text outcome, cleaned; no delta was committed.
        assertEquals(listOf(0L to SegmentOutcome.Text("Hello world.")), listener.resolved)
    }

    @Test
    fun deltasInsideTheThrottleWindow_areSuppressed() {
        var now = 0L
        val backend = StreamingScriptBackend(
            deltas = listOf(" a", " ab", " abc"),
            finalText = " abc",
            advance = { now += 50 },               // 50 ms apart: only the first passes
        )
        val engine = engineWith(backend, clock = { now })
        val listener = RecordingListener()
        engine.connect(language = "en", listener = listener)
        engine.sendAudio(pcm)
        engine.commit()

        assertEquals(listOf(" a", ""), listener.deltas)
        assertEquals(listOf("abc"), listener.completed)   // nothing lost: the final supersedes
    }

    @Test
    fun theThrottleResetsPerSegment_firstDeltaOfEachSegmentIsImmediate() {
        val backend = StreamingScriptBackend(
            deltas = listOf(" x"),
            finalText = " x",
            advance = {},                           // the clock NEVER advances
        )
        val engine = engineWith(backend, clock = { 0L })
        val listener = RecordingListener()
        engine.connect(language = "en", listener = listener)

        engine.sendAudio(pcm); engine.commit()
        engine.sendAudio(pcm); engine.commit()

        // Without the per-segment reset the second segment's only delta would be swallowed.
        assertEquals(listOf(" x", "", " x", ""), listener.deltas)
    }

    @Test
    fun staleSessionDeltas_areDropped_byTheListenerIdentityGuard() {
        var now = 0L
        val executor = QueueingExecutorService()
        val backend = StreamingScriptBackend(
            deltas = listOf(" too late"),
            finalText = " too late",
            advance = { now += 200 },
        )
        val engine = engineWith(backend, clock = { now }, executor = executor)
        val listener = RecordingListener()
        engine.connect(language = "en", listener = listener)
        executor.tasks[0].run()                     // the model-load task
        assertTrue(listener.opened)

        engine.sendAudio(pcm)
        engine.commit()                             // queued as tasks[1], NOT yet run
        engine.close()                              // session over: listener detached

        executor.tasks[1].run()                     // the transcribe outlives its session

        // The exact guard: `listener === myListener` in runSegment — deltas, the terminal
        // clear, and the resolution are ALL dropped for a detached session.
        assertTrue(listener.deltas.isEmpty())
        assertTrue(listener.resolved.isEmpty())
    }

    @Test
    fun aNonStreamingBackend_emitsNoDeltasAndNoClear() {
        // Default transcribeStreaming delegates to transcribe: byte-for-byte 3.5.0 callback
        // sequence — in particular no spurious "" clear for backends/fakes that never stream.
        val engine = engineWith(FakeWhisperBackend(text = "plain"), clock = { 0L })
        val listener = RecordingListener()
        engine.connect(language = "en", listener = listener)
        engine.sendAudio(pcm)
        engine.commit()

        assertTrue(listener.deltas.isEmpty())
        assertEquals(listOf("plain"), listener.completed)
    }
}
```

- [ ] **Step 2 — run it, expect the red:**
```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon --tests "com.whispereverywhere.transcription.LocalWhisperEngineStreamingTest"
```
Expect **compile failure**: `e: ... Cannot find a parameter with this name: deltaClock` (BUILD FAILED).

- [ ] **Step 3 — implement.** Six edits in `LocalWhisperEngine.kt` (3a-3f), one KDoc in `TranscriptionEngine.kt` (3g), one comment in `LocalWhisperEngineTest.kt` (3h), one guard line in `FloatingBubbleService.kt` (3i).

Edit 3a — constructor parameter. OLD (unique, `:34-35`):
```kotlin
    private val executor: ExecutorService = Executors.newSingleThreadExecutor(),
) : TranscriptionEngine {
```
NEW:
```kotlin
    private val executor: ExecutorService = Executors.newSingleThreadExecutor(),
    /**
     * Clock feeding the preview-delta throttle (3.6.0 Workstream D). Injectable so JVM tests
     * drive throttling deterministically. Milliseconds; only differences are used.
     */
    private val deltaClock: () -> Long = System::currentTimeMillis,
) : TranscriptionEngine {
```

Edit 3b — throttle field (**anchor is B4's pin field**). OLD (unique):
```kotlin
    private val languagePin = LanguagePin()
```
NEW:
```kotlin
    private val languagePin = LanguagePin()

    /**
     * Preview-delta rate limiter (3.6.0 Workstream D). Touched only on the native executor
     * thread: reset at segment start, checked inside the native new-segment callback, which
     * whisper_full invokes on that same thread.
     */
    private val deltaThrottle = DeltaThrottle(now = deltaClock)
```

Edit 3c — the streamed-preview flag. OLD (unique — runSegment's opener, `:242-248`):
```kotlin
    private fun runSegment(
        seq: Long,
        pcm: ByteArray,
        lang: String?,
        myListener: TranscriptionEngine.Listener,
    ) {
        val outcome: SegmentOutcome = try {
```
NEW:
```kotlin
    private fun runSegment(
        seq: Long,
        pcm: ByteArray,
        lang: String?,
        myListener: TranscriptionEngine.Listener,
    ) {
        // D (3.6.0): true once at least one preview delta was forwarded for THIS segment, so
        // the strip is cleared exactly when something was put on it — and never otherwise.
        var streamedPreview = false
        val outcome: SegmentOutcome = try {
```

Edit 3d — stream the transcribe (**OLD is post-A4 + post-B4**: it carries A4's `transcribeStartNs` and B4's `effectiveLang`). OLD (unique):
```kotlin
                val transcribeStartNs = System.nanoTime()
                val text = runBlocking {
                    retry.retry { backend.transcribe(ctx, samples, effectiveLang) }
                }
```
NEW:
```kotlin
                // D (3.6.0 partial streaming): whisper.cpp's new-segment callback arrives HERE,
                // on this same executor thread, WHILE backend.transcribeStreaming is still
                // executing — and while WhisperNativeBackend holds the process-global
                // NativeComputeGate — so this closure stays strictly lock-free: throttle check
                // + listener forward, nothing else. Never bufferLock, never a backend re-entry,
                // never logging (delta text IS user speech). Deltas are PREVIEW-ONLY: committed
                // text comes exclusively from the returned String via segment resolution below
                // (the final-only commit contract, untouched). Stale sessions are dropped by
                // the exact guard resolutions use: `listener === myListener`.
                val transcribeStartNs = System.nanoTime()
                val text = runBlocking {
                    retry.retry {
                        // INSIDE the retry lambda, not above it: a retried attempt re-decodes
                        // this segment from scratch, so its first delta must render immediately
                        // too. Resetting once per segment would leave the retry's opening words
                        // inside the previous attempt's throttle window and swallow them.
                        deltaThrottle.reset()
                        backend.transcribeStreaming(ctx, samples, effectiveLang) { running ->
                            if (listener === myListener && deltaThrottle.shouldEmit()) {
                                streamedPreview = true
                                myListener.onDelta(running)
                            }
                        }
                    }
                }
```
(A4's `segment-timing` block still follows immediately and is unchanged — the RTF number now covers the streaming call, which is the same wall cost.)

Edit 3e — the terminal clear. OLD (unique, `:305-306`):
```kotlin
        // Guard: only fire if the listener hasn't been replaced/nulled since commit().
        if (listener === myListener) myListener.onSegmentResolved(seq, outcome)
```
NEW:
```kotlin
        // D (3.6.0): the segment reached a terminal outcome, so the in-flight preview is stale —
        // a blank delta clears the strip (the service's onDelta hides it on blank) before the
        // resolution lands in the accumulating window; both hop to Main via the same FIFO, so
        // the clear always renders first. Emitted only when this segment actually streamed, so
        // non-streaming backends keep the exact 3.5.0 callback sequence.
        if (streamedPreview && listener === myListener) myListener.onDelta("")
        // Guard: only fire if the listener hasn't been replaced/nulled since commit().
        if (listener === myListener) myListener.onSegmentResolved(seq, outcome)
```

Edit 3f — the class KDoc stops claiming "no deltas". OLD (unique, file top `:12-14`):
```kotlin
 * On-device whisper.cpp engine. Buffers PCM16 audio, and on commit runs one batch
 * transcription of the buffered segment on a single-thread executor (segments serialize).
 * No intra-segment deltas are emitted — exactly one onSegmentResolved per committed segment.
```
NEW:
```kotlin
 * On-device whisper.cpp engine. Buffers PCM16 audio, and on commit runs one batch
 * transcription of the buffered segment on a single-thread executor (segments serialize).
 * Intra-segment deltas (3.6.0) are PREVIEW-ONLY: the native new-segment callback streams the
 * in-flight text to onDelta, throttled (~150 ms), but committed text still comes exclusively
 * from the exactly-one onSegmentResolved per committed segment — the final-only commit contract.
```

Edit 3g — the Listener KDoc stops claiming "unused on-device". In `TranscriptionEngine.kt`, OLD (unique, `:64`):
```kotlin
        fun onDelta(text: String)     // unused on-device; kept for interface compatibility
```
NEW:
```kotlin
        /**
         * PREVIEW-ONLY running text of the in-flight segment/turn (cloud-live partials; local
         * partial streaming since 3.6.0). Blank means "clear the preview". Never committed —
         * committed text arrives exclusively via [onSegmentResolved].
         */
        fun onDelta(text: String)
```

Edit 3h — one stale comment in the pre-existing test (assertion unchanged; it remains true for a non-streaming backend). In `LocalWhisperEngineTest.kt`, OLD (unique, `:209`):
```kotlin
        assertTrue(listener.deltas.isEmpty())                     // never onDelta
```
NEW:
```kotlin
        assertTrue(listener.deltas.isEmpty())   // a non-streaming backend emits no deltas (streaming pinned in LocalWhisperEngineStreamingTest)
```

Edit 3i — **the FINALIZING gate** (the one required service change; see the design note above). In `FloatingBubbleService.kt`, OLD (unique — the head of the `onDelta` handler, `:2113-2119`, untouched by A2/E2/E4/E5/E6):
```kotlin
            override fun onDelta(text: String) {
                // Only the live engine emits intra-segment deltas. The unified preview (W2)
                // keeps the container up for EVERY session context, so the strip renders
                // wherever deltas exist — no context gate. Resolved turns accumulate into the
                // window below it.
                serviceScope.launch(Dispatchers.Main) {
                    if (text.isNotBlank()) {
```
NEW:
```kotlin
            override fun onDelta(text: String) {
                // Local partial streaming (3.6.0 D) joined cloud-live here. The unified preview
                // (W2) keeps the container up for EVERY session context, so the strip renders
                // wherever deltas exist — no context gate. Resolved turns accumulate into the
                // window below it.
                serviceScope.launch(Dispatchers.Main) {
                    // 3.6.0 D: the strip carries the FINALIZING status line; local tail deltas must not clobber it.
                    // The stop tap writes "Finishing… (waiting on provider)" / "Finishing
                    // transcript…" here and THEN flushes the tail segment, which now streams —
                    // ungated, its deltas would replace that line and its terminal blank would
                    // hide the strip for the whole drain, next to E6's counting-up ticker. Same
                    // guard deliverReleasedText already uses for its sibling reset.
                    if (currentState == BubbleState.FINALIZING) return@launch
                    if (text.isNotBlank()) {
```

- [ ] **Step 4 — run green:** the Step 2 command — expect `BUILD SUCCESSFUL`, 5 tests, 0 failures. Then the full suite (proves the untouched 3.5.0 behavior for every non-streaming fake, the pin tests, and the fallback/batch suites):
```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon
```
Expect `BUILD SUCCESSFUL`, zero failures.

- [ ] **Step 5 — commit:**
```powershell
git add app/src/main/java/com/whispereverywhere/transcription/LocalWhisperEngine.kt app/src/main/java/com/whispereverywhere/transcription/TranscriptionEngine.kt app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt app/src/test/java/com/whispereverywhere/transcription/LocalWhisperEngineTest.kt app/src/test/java/com/whispereverywhere/transcription/LocalWhisperEngineStreamingTest.kt
git commit -m "feat(engine): local partial streaming - throttled onDelta previews during inference, final-only commit untouched (D4)" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`nClaude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT"
```

---

### Task D5: verification gate — service plumbing, contracts, build (no code changes, no commit)

**Why:** spec D5 — "callbacks from a transcribe that outlives its session are dropped by the existing stale-listener guard semantics; verify explicitly in review" — plus the plan-level proof that the service side needs no edit and no UNTOUCHABLE moved. Every check is read-only; if any fails, STOP and fix the responsible task rather than patching here.

- [ ] **Check 1 — deltas reach the preview strip and NOTHING else, and never the FINALIZING line.** Run both:
```powershell
Select-String -Path "app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt" -Pattern "override fun onDelta"
Select-String -Path "app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt" -Pattern "3.6.0 D: the strip carries the FINALIZING status line"
```
Expect exactly one hit each (the handler ~line 2113; the gate comment on the first line inside its Main-dispatched block). Read that handler and confirm: (a) it writes only `transcriptionDeltaText` (visibility, text, scroll, reclamp) — no call to `deliverReleasedText`, `handleTranscriptionResult`, `transcriptSink`, or `segmentOrderer`; blank text hides the strip (`transcriptionDeltaText.visibility = View.GONE`); (b) the FIRST statement inside `serviceScope.launch(Dispatchers.Main) { … }` is `if (currentState == BubbleState.FINALIZING) return@launch` (D4 Edit 3i), so the stop-tap status line survives the streamed tail segment untouched. (a) is what makes the engine's deltas preview-only END TO END; (b) is what keeps the 3.5.0 "Finishing…" line — the one E6's ticker counts up beside — from being clobbered by them.

- [ ] **Check 2 — the strip renders for local sessions without any service change.** Confirm in `showSessionPreview` (~`:1995-2026`) that the preview container shows for EVERY session and the strip is reset (`transcriptionDeltaText.text = ""` / `visibility = View.GONE` at `:2003-2004`), and that the `onDelta` handler carries the comment "no context gate" — the strip renders wherever deltas exist, which now includes local sessions.

- [ ] **Check 3 — stale-guard statement.** Confirm `LocalWhisperEngine.runSegment` guards ALL THREE listener touchpoints with the identity check `listener === myListener`: the delta forward (inside the `transcribeStreaming` closure), the terminal `onDelta("")` clear, and `onSegmentResolved` — and that `close()` sets `this.listener = null` while `connect()` replaces it, which is the entire drop mechanism for callbacks from a transcribe that outlives its session. Pinned by `staleSessionDeltas_areDropped_byTheListenerIdentityGuard` (D4) and `aStaleSessionsLateSegment_cannotPinTheNewSession` (B4).

- [ ] **Check 4 — UNTOUCHABLES untouched.** Run (note: `FallbackPolicy` is an object INSIDE `FallbackTranscriptionEngine.kt`; there is no `FallbackPolicy.kt` — passing a nonexistent path would make this check pass vacuously):
```powershell
git diff 97ec697 --stat -- app/src/main/java/com/whispereverywhere/transcription/SegmentOrderer.kt app/src/main/java/com/whispereverywhere/transcription/cloud/FallbackTranscriptionEngine.kt app/src/main/java/com/whispereverywhere/util/SpeechSegmenter.kt
```
Expect **empty output** — A, E, B and D changed none of them. (Workstream F edits `FallbackTranscriptionEngine.kt` LATER, by design and inside `awaitIdle` only; this check runs before F.)

- [ ] **Check 5 — full suite + native build, final:**
```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:assembleDebug --no-daemon
```
Both `BUILD SUCCESSFUL`. NEVER `:app:installDebug` / `:app:connectedDebugAndroidTest`.

- [ ] **Check 6 — record the owner acceptance items these workstreams feed** (H2; owner sideloads the debug APK from `C:\Users\bastr\.androidbuild\WhisperEverywhere\app\outputs\apk\debug\`):
  - **B:** multilingual tier + language auto → logcat shows `WE-DIAG language-pin: detected=<code>` once per session on the first speech segment; later `transcribe START` lines show `effective=<code>`; per-segment native time drops versus the 3.5.0 build (the detect-encode pass is gone — cross-checked with A4's `segment-timing` RTF lines).
  - **D:** "Streaming deltas visibly render during local inference" — words appear on the delta strip while a long segment is still transcribing, the strip clears when the segment's text lands in the accumulating window, and the committed/external text is unchanged from a 3.5.0 run of the same speech (final-only, preview never injected).

---

## Workstream C — GPU re-evaluation for multi (validation-gated, corruption-safe)

> **⚠ Assembly note — read before executing C1-C7.** The source part that carried Workstream C
> arrived TRUNCATED: it began mid-sentence inside G1's implementation, so **C1 through C7 were
> missing entirely** and only C8 (which names their outputs) was present. C1-C7 below were
> authored by the plan assembler from the spec (Workstream C, items 1-5), the repo at HEAD
> `97ec697`, and the exact contracts C8 depends on (`GpuCanaryPolicy.canaryPasses`, the
> `canary_digits.wav` asset). They are complete and executable, but they are NOT the original
> author's design — have the C author review them before execution. One consequence is recorded
> in the flags at the end of this plan: the assembly brief stated that **C also touches
> `whisper_jni.cpp`**, and no native C edit could be reconstructed; if the original C1-C7 do
> touch it, their anchors must be re-cross-checked against B1's, D1's and G2's edits to that file.

The current ban is EMPIRICAL (garbage-token corruption on multilingual models via OpenCL, `the empirical-corruption docblock above GpuPolicy.isGpuSafeModel`) — it is not lifted blind. Design: **canary-validated enablement**, shipped OFF behind a developer toggle.

### Task C1: `GpuCanaryPolicy` — the pure canary match rule (TDD)

**Files:**
- Create: `app/src/main/java/com/whispereverywhere/transcription/GpuCanaryPolicy.kt`
- Create: `app/src/test/java/com/whispereverywhere/transcription/GpuCanaryPolicyTest.kt`

**Builds on:** nothing — two new files.

**Why:** spec C1-C2 — a tiny known-audio sample plus "its expected token set", compared after a GPU transcribe. The comparison rule is what decides whether a device+model is allowed on the GPU forever, so it is pure and JVM-pinned. The two documented failure signatures are *garbage tokens* and *empty output* — both must fail, and a repetition runaway (the other observed corruption shape) must fail too.

- [ ] **Step 1 — write the failing test.** Create `app/src/test/java/com/whispereverywhere/transcription/GpuCanaryPolicyTest.kt`:
```kotlin
package com.whispereverywhere.transcription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Workstream C (3.6.0): the canary match rule that decides whether a multilingual model may
 * use the Adreno OpenCL backend on THIS device. Both documented corruption signatures —
 * garbage tokens and empty output — must FAIL, and so must a repetition runaway.
 */
class GpuCanaryPolicyTest {

    @Test
    fun theExpectedSetIsTheFiveSpokenDigits() {
        assertEquals(
            listOf(
                setOf("one", "1"), setOf("two", "2"), setOf("three", "3"),
                setOf("four", "4"), setOf("five", "5"),
            ),
            GpuCanaryPolicy.EXPECTED_TOKENS,
        )
        assertEquals(4, GpuCanaryPolicy.MIN_MATCHES)
    }

    @Test
    fun aCleanTranscriptionPasses() {
        assertTrue(GpuCanaryPolicy.canaryPasses(" One two three four five."))
    }

    @Test
    fun numeralRenderingsPassToo() {
        // whisper renders spoken digits as NUMERALS constantly (the JNI decodes with
        // no_context=true and suppress_nst=true on a ~1 s clip), and normalize deliberately keeps
        // digits. "1, 2, 3, 4, 5." is a PERFECT transcription of the canary clip — failing it
        // would latch a correct GPU to CPU for the whole app version on a formatting coin-flip.
        assertTrue(GpuCanaryPolicy.canaryPasses("1, 2, 3, 4, 5."))
        assertTrue(GpuCanaryPolicy.canaryPasses("one 2 three 4 five"))   // mixed rendering
    }

    @Test
    fun punctuationAndCasingDoNotMatter() {
        assertTrue(GpuCanaryPolicy.canaryPasses("ONE, TWO, THREE, FOUR, FIVE!"))
    }

    @Test
    fun oneMissedDigitStillPasses_butTwoDoNot() {
        // Whisper legitimately drops a leading digit on a 1 s clip; two misses is a red flag.
        assertTrue(GpuCanaryPolicy.canaryPasses("two three four five"))
        assertFalse(GpuCanaryPolicy.canaryPasses("three four five"))
    }

    @Test
    fun emptyOutputFails() {
        // The ggml-large-v3-turbo-q5_0 GPU signature (the empirical-corruption docblock above GpuPolicy.isGpuSafeModel): empty transcriptions.
        assertFalse(GpuCanaryPolicy.canaryPasses(""))
        assertFalse(GpuCanaryPolicy.canaryPasses("   "))
    }

    @Test
    fun garbageTokensFail() {
        // The ggml-small-q5_1 GPU signature: decodes to garbage.
        assertFalse(GpuCanaryPolicy.canaryPasses("шшш ののの ¿¿¿ qwx zzz"))
    }

    @Test
    fun aRepetitionRunawayFails_evenWithEveryDigitPresent() {
        // Corruption can also surface as a degenerate loop that happens to contain the digits.
        val runaway = "one two three four five " + "five ".repeat(40)
        assertFalse(GpuCanaryPolicy.canaryPasses(runaway))
    }

    @Test
    fun normalizeStripsPunctuationAndLowercases() {
        assertEquals(listOf("one", "two"), GpuCanaryPolicy.normalize("  One, TWO! "))
    }
}
```

- [ ] **Step 2 — run it, expect the red:**
```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon --tests "com.whispereverywhere.transcription.GpuCanaryPolicyTest"
```
Expect **compile failure**: `e: ... Unresolved reference 'GpuCanaryPolicy'`.

- [ ] **Step 3 — implement.** Create `app/src/main/java/com/whispereverywhere/transcription/GpuCanaryPolicy.kt`:
```kotlin
package com.whispereverywhere.transcription

/**
 * The GPU canary's match rule (3.6.0 Workstream C).
 *
 * The multilingual GPU ban is EMPIRICAL, not theoretical: on the Fold 6 the Adreno OpenCL
 * backend decoded ggml-small-q5_1 to garbage tokens and ggml-large-v3-turbo-q5_0 to empty
 * transcriptions, with no crash to catch (the empirical-corruption docblock above GpuPolicy.isGpuSafeModel). Crash sentinels cannot see
 * silent corruption — so before a non-".en" model is allowed on the GPU, it transcribes a
 * bundled ~1 s clip of spoken digits and its output is checked HERE.
 *
 * The rule is deliberately tolerant of ordinary ASR slack (casing, punctuation, one dropped
 * digit on a short clip) and intolerant of every observed corruption shape:
 *  - empty / whitespace-only  -> FAIL (the large-turbo signature)
 *  - fewer than [MIN_MATCHES] expected POSITIONS -> FAIL (the garbage-token signature)
 *  - a runaway token count    -> FAIL (degenerate repetition, which can contain the digits)
 *
 * Pure and JVM-pinned (GpuCanaryPolicyTest): this verdict is persisted as a permanent per
 * (app version, model, device) latch, so a wrong rule is expensive to undo.
 */
object GpuCanaryPolicy {

    /**
     * One ALIAS SET per spoken position in `assets/canary_digits.wav`, in order. The clip
     * contains the WORDS one-through-five (that is the C4 asset contract and it does not change),
     * but whisper renders spoken digits as numerals routinely — the JNI decodes with
     * no_context=true and suppress_nst=true, and on a ~1 s clip "1, 2, 3, 4, 5." is a common and
     * entirely CORRECT output. [normalize] keeps digits, so matching word forms only would score
     * that perfect transcription zero and latch a working GPU to CPU for the whole app version
     * on a formatting coin-flip. The aliases are about whisper's RENDERING, never about what the
     * owner records.
     */
    val EXPECTED_TOKENS: List<Set<String>> = listOf(
        setOf("one", "1"),
        setOf("two", "2"),
        setOf("three", "3"),
        setOf("four", "4"),
        setOf("five", "5"),
    )

    /** How many POSITIONS must appear (in any alias form). One dropped digit is ordinary; two is not. */
    const val MIN_MATCHES = 4

    /** Beyond this many tokens the output is a repetition runaway, not a transcription. */
    private const val MAX_TOKENS = 20

    private val NON_WORD = Regex("[^\\p{L}\\p{Nd}]+")

    /** Lowercased word tokens, punctuation stripped. */
    fun normalize(text: String): List<String> =
        text.lowercase().split(NON_WORD).filter { it.isNotEmpty() }

    /** True when [text] is a believable transcription of the canary clip. */
    fun canaryPasses(text: String): Boolean {
        val tokens = normalize(text)
        if (tokens.isEmpty()) return false
        if (tokens.size > MAX_TOKENS) return false
        val seen = tokens.toSet()
        // A position counts as matched when ANY of its aliases is present.
        return EXPECTED_TOKENS.count { aliases -> aliases.any { it in seen } } >= MIN_MATCHES
    }
}
```

- [ ] **Step 4 — run green:** the Step 2 command. Expect `BUILD SUCCESSFUL`, 9 tests, 0 failures.

- [ ] **Step 5 — commit:**
```powershell
git add app/src/main/java/com/whispereverywhere/transcription/GpuCanaryPolicy.kt app/src/test/java/com/whispereverywhere/transcription/GpuCanaryPolicyTest.kt
git commit -m "feat(gpu): GpuCanaryPolicy - the pure canary match rule for multilingual GPU validation (C1)" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`nClaude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT"
```

---

### Task C2: The developer toggle preference — off by default

**Files:**
- Edit: `app/src/main/java/com/whispereverywhere/data/local/PreferencesManager.kt`

**Builds on:** E2 (same file). E2 replaced the `selectedModelId` block immediately ABOVE the `ttsSpeed` anchor used here; both OLD blocks below are unchanged by it.

**Why:** spec C4 — "the code path ships OFF by default behind a Settings developer toggle ('Try GPU for multilingual (experimental)') until the owner's device validates it — flipping the default is a data-driven follow-up, not part of this release's default behavior." Modelled byte-for-byte on the `vibrationEnabled` StateFlow pattern already in this file (`:86-116`) so the Settings row is reactive.

- [ ] **Step 1 — the preference.** In `PreferencesManager.kt`, replace this OLD block (~:237):
```kotlin
    // Read-aloud speech rate (Track F); 1.0 = the voice's natural pace.
    var ttsSpeed: Float
        get() = prefs.getFloat(KEY_TTS_SPEED, 1.0f)
        set(value) {
            prefs.edit().putFloat(KEY_TTS_SPEED, value).apply()
        }
```
with:
```kotlin
    /**
     * Developer toggle: allow the canary-validated GPU path for MULTILINGUAL whisper models
     * (3.6.0 Workstream C). **Defaults to false and ships false.** The multilingual GPU ban is
     * empirical (silent garbage-token / empty-output corruption on Adreno OpenCL,
     * the empirical-corruption docblock above GpuPolicy.isGpuSafeModel); this release adds the canary MECHANISM and the owner's measurement,
     * not a default change. Flipping the default is a separate, data-driven decision (spec
     * Decision Gate 2). Even when ON, a model only reaches the GPU after passing the bundled
     * canary once on this device — and a failure latches that model to CPU permanently.
     */
    private val _gpuMultilingualExperiment =
        MutableStateFlow(prefs.getBoolean(KEY_GPU_MULTI_EXPERIMENT, false))
    val gpuMultilingualExperiment: StateFlow<Boolean> = _gpuMultilingualExperiment.asStateFlow()

    fun setGpuMultilingualExperiment(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_GPU_MULTI_EXPERIMENT, enabled).apply()
        _gpuMultilingualExperiment.value = enabled
    }

    fun isGpuMultilingualExperimentEnabled(): Boolean = _gpuMultilingualExperiment.value

    // Read-aloud speech rate (Track F); 1.0 = the voice's natural pace.
    var ttsSpeed: Float
        get() = prefs.getFloat(KEY_TTS_SPEED, 1.0f)
        set(value) {
            prefs.edit().putFloat(KEY_TTS_SPEED, value).apply()
        }
```

- [ ] **Step 2 — the key.** Replace this OLD block in the companion (~:369):
```kotlin
        private const val KEY_TTS_SPEED = "tts_speed"
        private const val KEY_TTS_VOICE_ID = "tts_voice_id"
```
with:
```kotlin
        private const val KEY_TTS_SPEED = "tts_speed"
        private const val KEY_TTS_VOICE_ID = "tts_voice_id"
        private const val KEY_GPU_MULTI_EXPERIMENT = "gpu_multilingual_experiment"
```

- [ ] **Step 3 — verify:**
```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:assembleDebug --no-daemon
```
Expected: both `BUILD SUCCESSFUL`, zero failures. (`PreferencesManager` is Context-bound — house convention leaves it untested directly; the default-false invariant is re-asserted end-to-end by C7's grep check.)

- [ ] **Step 4 — commit:**
```powershell
git add app/src/main/java/com/whispereverywhere/data/local/PreferencesManager.kt
git commit -m "feat(gpu): gpuMultilingualExperiment developer toggle, default false (C2)" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`nClaude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT"
```

---

### Task C3: `GpuPolicy` — the persisted canary latch and the experiment gate

**Files:**
- Edit: `app/src/main/java/com/whispereverywhere/transcription/GpuPolicy.kt`

**Builds on:** C1 (nothing referenced yet), C2 (`isGpuMultilingualExperimentEnabled`). First edit to `GpuPolicy.kt`.

**Why:** spec C2 — "Pass → GPU allowed for that model+device (persisted latch). Fail → permanent CPU latch (persisted) + WE-DIAG line; the session proceeds on CPU exactly as today." The latch reuses the existing per-(versionCode, model) key scheme in the same `gpu_policy` prefs file, so a new app version re-trials once — the same generosity the crash sentinels already grant. The crash-sentinel state machine, the renderer allowlist and the `stateLock` discipline are untouched.

- [ ] **Step 1 — the latch keys and accessors.** In `GpuPolicy.kt`, replace this OLD block (~:42-48):
```kotlin
    private fun keyBlocked(vc: Int, m: String) = "gpu_blocked_v${vc}_$m"
    private fun keyValidated(vc: Int, m: String) = "gpu_validated_v${vc}_$m"
    private fun keyInFlight(vc: Int, m: String, phase: String) = "gpu_inflight_${phase}_v${vc}_$m"

    /** Stable per-model key from the model path (file name, sanitized for prefs keys). */
    private fun modelKey(modelPath: String): String =
        modelPath.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_")
```
with:
```kotlin
    private fun keyBlocked(vc: Int, m: String) = "gpu_blocked_v${vc}_$m"
    private fun keyValidated(vc: Int, m: String) = "gpu_validated_v${vc}_$m"
    private fun keyInFlight(vc: Int, m: String, phase: String) = "gpu_inflight_${phase}_v${vc}_$m"

    /**
     * The CANARY verdict for a multilingual model on this device (3.6.0 Workstream C). Absent =
     * never run. Keyed by app version AND model like every other flag here, so a new app version
     * (possibly a new driver/backend) re-trials once instead of inheriting a stale verdict.
     */
    private fun keyCanary(vc: Int, m: String) = "gpu_canary_v${vc}_$m"

    /** Stable per-model key from the model path (file name, sanitized for prefs keys). */
    private fun modelKey(modelPath: String): String =
        modelPath.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_")

    /** true = canary passed, false = permanent CPU latch, null = never run for this version+model. */
    private fun canaryVerdict(p: SharedPreferences, vc: Int, m: String): Boolean? =
        if (!p.contains(keyCanary(vc, m))) null else p.getBoolean(keyCanary(vc, m), false)

    /**
     * Set while a GPU load was allowed for a model whose canary has NOT run yet — the signal
     * WhisperNativeBackend.load reads to run the canary immediately after the load returns.
     * Written under [stateLock] together with [activeModelKey]. Cleared by [onCanaryResult] when
     * a verdict is recorded, and re-decided from scratch at the top of every
     * [decideUseGpuForLoad] — so an exit that records no verdict (C5's `ctx == 0L` and
     * "canary asset unavailable" paths) can never leave a stale `true` behind.
     */
    @Volatile private var canaryPending = false

    /** True when the load just decided needs its canary run before any user audio touches it. */
    fun needsCanary(): Boolean = canaryPending

    /**
     * Records the canary verdict for the model of the load that set [needsCanary]. A FAIL is a
     * permanent per-(version, model) CPU latch: the corruption is silent and reproducible, so
     * one demonstration is enough. commit() (not apply()) — the verdict must survive a process
     * death that a later GPU call might cause.
     *
     * A FAIL also CLEARS `gpu_validated` for that (version, model), in the same edit. The canary
     * runs through the same compute path a user segment does, so its non-throwing transcribe has
     * already set `keyValidated(vc, m) = true` — that flag means "a GPU compute completed without
     * killing us", and leaving it set while latching the model to CPU would disarm the crash
     * sentinel for a model whose GPU output is known garbage. Two prefs flags in direct
     * contradiction is worse than either. Never called when the canary could not be RUN (see
     * CanaryAudio.samples): an absent asset must leave the verdict unrecorded, not latch.
     */
    fun onCanaryResult(passed: Boolean) = runCatching {
      synchronized(stateLock) {
        canaryPending = false
        val p = prefs() ?: return@runCatching
        val vc = BuildConfig.VERSION_CODE
        val m = activeModelKey ?: return@runCatching
        if (passed) {
            p.edit().putBoolean(keyCanary(vc, m), true).commit()
            Log.i(TAG, "GpuPolicy: canary PASSED for v$vc/$m — GPU allowed for this model+device")
        } else {
            p.edit()
                .putBoolean(keyCanary(vc, m), false)
                .putBoolean(keyValidated(vc, m), false)
                .commit()
            Log.w(TAG, "GpuPolicy: canary FAILED for v$vc/$m — permanent CPU latch, session continues on CPU")
            gpuActiveInProcess = false
        }
      }
    }.let { }
```

- [ ] **Step 2 — clear the stale flag at the top, then the experiment gate.** Replace this OLD block (the head of `decideUseGpuForLoad` through the multilingual branch, ~:86-96):
```kotlin
    fun decideUseGpuForLoad(modelPath: String): Boolean = runCatching {
      synchronized(stateLock) {
        val p = prefs() ?: return false
        val vc = BuildConfig.VERSION_CODE
        val m = modelKey(modelPath)

        if (!isGpuSafeModel(m)) {
            Log.i(TAG, "GpuPolicy: $m is multilingual — silent GPU corruption on Adreno -> CPU")
            return false
        }
```
with:
```kotlin
    fun decideUseGpuForLoad(modelPath: String): Boolean = runCatching {
      synchronized(stateLock) {
        // 3.6.0 C, stale-flag hygiene: this function is the ONLY place canaryPending is armed,
        // so it decides the flag afresh on every call — cleared here, set at the tail (Step 3)
        // only when a multilingual model still needs validating. Without this, an exit that
        // never reaches the tail (any early return here, or either of C5's no-verdict exits —
        // `ctx == 0L` and "canary asset unavailable", both of which deliberately record
        // nothing) could leave a stale `true` paired with a freshly written activeModelKey.
        canaryPending = false
        val p = prefs() ?: return false
        val vc = BuildConfig.VERSION_CODE
        val m = modelKey(modelPath)

        if (!isGpuSafeModel(m)) {
            // 3.6.0 Workstream C: multilingual models are no longer banned outright — they are
            // CANARY-GATED, and only when the owner has enabled the experimental developer
            // toggle. Default (toggle off) behavior is byte-identical to 3.5.0.
            val experiment = runCatching {
                com.whispereverywhere.WhisperEverywhereApp.getInstance()
                    .preferencesManager.isGpuMultilingualExperimentEnabled()
            }.getOrDefault(false)
            if (!experiment) {
                Log.i(TAG, "GpuPolicy: $m is multilingual — silent GPU corruption on Adreno -> CPU")
                return false
            }
            when (canaryVerdict(p, vc, m)) {
                false -> {
                    Log.i(TAG, "GpuPolicy: $m failed the canary on this device (latched) -> CPU")
                    return false
                }
                true -> Log.i(TAG, "GpuPolicy: $m passed the canary on this device -> GPU allowed")
                null -> Log.i(TAG, "GpuPolicy: $m canary not yet run -> GPU load armed for validation")
            }
        }
```

- [ ] **Step 3 — arm `canaryPending` when the verdict is unknown.** Replace this OLD block (the tail of `decideUseGpuForLoad`, ~:122-131):
```kotlin
        if (!p.getBoolean(keyValidated(vc, m), false)) {
            // First GPU attempt for this (version, model): arm the load sentinel BEFORE the
            // native call. commit() (not apply()) — must be on disk if the load kills us.
            p.edit().putBoolean(keyInFlight(vc, m, "load"), true).commit()
            Log.i(TAG, "GpuPolicy: renderer '$renderer' allowlisted; GPU trial armed (v$vc, $m)")
        }
        activeModelKey = m
        gpuActiveInProcess = true
        true
      }
```
with:
```kotlin
        if (!p.getBoolean(keyValidated(vc, m), false)) {
            // First GPU attempt for this (version, model): arm the load sentinel BEFORE the
            // native call. commit() (not apply()) — must be on disk if the load kills us.
            p.edit().putBoolean(keyInFlight(vc, m, "load"), true).commit()
            Log.i(TAG, "GpuPolicy: renderer '$renderer' allowlisted; GPU trial armed (v$vc, $m)")
        }
        activeModelKey = m
        gpuActiveInProcess = true
        // 3.6.0 C: a multilingual model with no recorded verdict must transcribe the canary
        // before any user audio reaches it. Written with activeModelKey under stateLock so
        // onCanaryResult always latches the model this decision was about.
        canaryPending = !isGpuSafeModel(m) && canaryVerdict(p, vc, m) == null
        true
      }
```

- [ ] **Step 4 — verify:**
```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:assembleDebug --no-daemon
```
Expected: both `BUILD SUCCESSFUL`, zero failures. (`GpuPolicy` reads `BuildConfig`/`Application`/EGL and fails safe to CPU in the JVM — house convention leaves it untested directly; the match rule it consumes is pinned by C1 and the end-to-end verdict by C8's on-device assertion.)

- [ ] **Step 5 — commit:**
```powershell
git add app/src/main/java/com/whispereverywhere/transcription/GpuPolicy.kt
git commit -m "feat(gpu): persisted canary latch + experiment gate for multilingual models (C3)" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`nClaude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT"
```

---

### Task C4: The bundled canary clip + its loader — **OWNER-SUPPLIED ASSET**

**Files:**
- Add (owner-supplied binary): `app/src/main/assets/canary_digits.wav`
- Create: `app/src/main/java/com/whispereverywhere/transcription/CanaryAudio.kt`
- Create: `app/src/test/java/com/whispereverywhere/transcription/CanaryAudioTest.kt`

**Builds on:** C1 (`EXPECTED_TOKENS` defines what must be spoken — its per-position alias sets carry the numeral forms, so the CLIP is still plain spoken words; the aliases exist for whisper's rendering, not for the recording).

**Why:** spec C1 — "Ship a tiny bundled known-audio sample (~1 s, spoken digits) + its expected token set." The token set is C1; this is the audio and the reader. `app/src/main/assets/` currently holds `ggml-silero-v5.1.2.bin`, `oss_licenses.html`, `privacy_policy.html`, `terms_of_service.html` — the canary joins them (main assets, NOT androidTest: production code reads it).

**The clip is a binary an agent cannot synthesize.** The owner records or obtains it; this task's automated half is the loader plus the format check.

**HARD GATE — the asset splits this task in two.** The loader (`CanaryAudio`) and its tests are pure and land unconditionally. The ASSET does not. If `app/src/main/assets/canary_digits.wav` is not present when Step 2 runs, the executor completes Steps 3-7 (loader + tests + their commit) and then **STOPS**: Step 8's `git add` of the asset does not run, and **C5, C7 and C8 do not execute** — they are blocked on owner delivery, recorded in the assembly flags. Do NOT substitute a synthesized or placeholder WAV: the canary's verdict is a permanent per-(versionCode, model) CPU latch, and a wrong clip poisons it for the whole app version.

- [ ] **Step 1 — the owner supplies the clip.** Requirements, non-negotiable because the whole latch rests on them:
  - Contents: the WORDS **"one two three four five"**, clearly spoken, in English, no music/noise. (Spoken words, not a recording of numerals being read differently — C1's numeral aliases cover how whisper may TRANSCRIBE them, and change nothing about what is recorded.)
  - Format: **RIFF/WAVE, PCM16, mono, 16 000 Hz** (whisper's native rate — no resampling path exists here).
  - Length: **1.0-2.0 s** (~32 000-64 000 data bytes). Longer wastes a cold load; shorter risks whisper's sub-1.1 s zero-pad path.
  - Save as `app/src/main/assets/canary_digits.wav`.

- [ ] **Step 2 — is the asset there, and is it the right shape?** (Run before writing any code against it. The existence check comes first: `ReadAllBytes` on a missing path throws a raw `FileNotFoundException`, which is not a decision.)
```powershell
if (-not (Test-Path "app/src/main/assets/canary_digits.wav")) {
    'STOP: canary_digits.wav not supplied by owner — C5/C7/C8 blocked. Land Steps 3-7 (loader + tests) and stop; do NOT run Step 8.'
} else {
    $b = [System.IO.File]::ReadAllBytes((Resolve-Path "app/src/main/assets/canary_digits.wav"))
    "riff=$([System.Text.Encoding]::ASCII.GetString($b,0,4)) wave=$([System.Text.Encoding]::ASCII.GetString($b,8,4)) channels=$([BitConverter]::ToUInt16($b,22)) rate=$([BitConverter]::ToUInt32($b,24)) bits=$([BitConverter]::ToUInt16($b,34)) totalBytes=$($b.Length)"
}
```
Two outcomes, both defined:
  - **`STOP: …` printed** → the asset is absent. Continue with Steps 3-7 (they do not touch the asset), then STOP. Record in the run log that C5/C7/C8 are blocked on the owner's clip.
  - **A field line printed** → expect `riff=RIFF wave=WAVE channels=1 rate=16000 bits=16 totalBytes=` a number between roughly 32 100 and 64 200. If any field differs, STOP and re-export the clip — do not adapt the code to a different format, and do not commit the bad file.

- [ ] **Step 3 — write the failing test.** Create `app/src/test/java/com/whispereverywhere/transcription/CanaryAudioTest.kt`:
```kotlin
package com.whispereverywhere.transcription

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The WAV data-chunk walk is the only testable half of the canary loader (the asset read needs
 * a Context). Pinned because a wrong offset would feed whisper header bytes as audio and fail
 * the canary for a reason that has nothing to do with the GPU.
 */
class CanaryAudioTest {

    /** Minimal RIFF/WAVE with a LIST chunk before data, so a fixed 44-byte offset would break. */
    private fun wav(payload: ByteArray): ByteArray {
        val list = ByteArray(4) { 0x7F }
        val out = java.io.ByteArrayOutputStream()
        fun le(v: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
        out.write("RIFF".toByteArray()); out.write(le(0)); out.write("WAVE".toByteArray())
        out.write("LIST".toByteArray()); out.write(le(list.size)); out.write(list)
        out.write("data".toByteArray()); out.write(le(payload.size)); out.write(payload)
        return out.toByteArray()
    }

    @Test
    fun dataChunkIsFoundAfterAnIntermediateChunk() {
        val payload = byteArrayOf(1, 2, 3, 4, 5, 6)
        assertArrayEquals(payload, CanaryAudio.dataChunk(wav(payload)))
    }

    @Test
    fun aFileWithNoDataChunkYieldsNothing() {
        assertEquals(0, CanaryAudio.dataChunk("RIFF____WAVE".toByteArray()).size)
    }
}
```

- [ ] **Step 4 — run it, expect the red:**
```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon --tests "com.whispereverywhere.transcription.CanaryAudioTest"
```
Expect **compile failure**: `e: ... Unresolved reference 'CanaryAudio'`.

- [ ] **Step 5 — implement.** Create `app/src/main/java/com/whispereverywhere/transcription/CanaryAudio.kt`:
```kotlin
package com.whispereverywhere.transcription

import android.util.Log
import com.whispereverywhere.WhisperEverywhereApp
import com.whispereverywhere.util.AudioMath
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The bundled GPU-canary clip (3.6.0 Workstream C): ~1 s of spoken digits, PCM16 mono 16 kHz,
 * shipped in main assets so PRODUCTION code can transcribe it during a cold GPU load.
 *
 * Kept in memory only for the duration of one canary run — the samples are re-read each time
 * (once per device+model, ever) rather than cached, because the whole point is that this path
 * runs at most once per app version and must cost nothing afterwards.
 */
object CanaryAudio {
    private const val TAG = "WE-DIAG"
    const val ASSET = "canary_digits.wav"

    /**
     * Float samples for the canary clip, or null when the asset is missing/unreadable.
     *
     * null means **"no verdict is possible"**, NOT "the canary failed". The caller must fall back
     * to CPU for this load and record NOTHING: a canary FAILURE is a permanent per-(versionCode,
     * model) CPU latch, and letting a packaging slip (asset omitted, wrong format, asset shrink)
     * write that latch would ban a perfectly good GPU for the entire app version with no in-app
     * recovery. Leaving the verdict unrecorded means the next launch simply retries.
     */
    fun samples(): FloatArray? = runCatching {
        val bytes = WhisperEverywhereApp.getInstance().assets.open(ASSET).use { it.readBytes() }
        val pcm = dataChunk(bytes)
        if (pcm.isEmpty()) null else AudioMath.pcm16ToFloat(pcm)
    }.onFailure {
        Log.w(TAG, "CanaryAudio: $ASSET unreadable — no canary verdict can be recorded", it)
    }.getOrNull()

    /**
     * Raw bytes of the WAV "data" chunk, walking the chunk list rather than assuming a 44-byte
     * header (real encoders emit LIST/fact chunks first). Mirrors WhisperBenchTest.wavDataChunk.
     * Pure — JVM-pinned in CanaryAudioTest.
     */
    fun dataChunk(bytes: ByteArray): ByteArray {
        var i = 12 // skip "RIFF"<size>"WAVE"
        while (i + 8 <= bytes.size) {
            val id = String(bytes, i, 4, Charsets.US_ASCII)
            val chunkSize = ByteBuffer.wrap(bytes, i + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (id == "data") {
                val end = minOf(i + 8 + chunkSize, bytes.size)
                return bytes.copyOfRange(i + 8, end)
            }
            i += 8 + chunkSize + (chunkSize and 1)
        }
        return ByteArray(0)
    }
}
```

- [ ] **Step 6 — run green + build:**
```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:assembleDebug --no-daemon
```
Expected: both `BUILD SUCCESSFUL`, zero failures.

- [ ] **Step 7 — commit the loader + tests (UNCONDITIONAL — runs whether or not the asset exists):**
```powershell
git add app/src/main/java/com/whispereverywhere/transcription/CanaryAudio.kt app/src/test/java/com/whispereverywhere/transcription/CanaryAudioTest.kt
git commit -m "feat(gpu): canary clip loader + WAV data-chunk walk (C4)" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`nClaude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT"
```

- [ ] **Step 8 — commit the asset (CONDITIONAL — only if Step 2 printed the field line):**

If Step 2 printed `STOP: …`, **this step does not run and C4 ends here**; C5, C7 and C8 stay blocked until the owner delivers the clip, and that is recorded in the run log, not worked around. Otherwise:
```powershell
git add app/src/main/assets/canary_digits.wav
git commit -m "feat(gpu): bundle the owner-supplied canary digits clip (C4)" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`nClaude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT"
```

---

### Task C5: Run the canary on the native executor during the cold GPU load

**Files:**
- Edit: `app/src/main/java/com/whispereverywhere/transcription/TranscriptionEngine.kt` (`WhisperNativeBackend.load` only)

**Builds on:** C1, C3, C4 — **and D2**, whose `transcribeInternal` this reuses so the canary transcribe goes through the SAME gate + GpuPolicy crash-sentinel wrapper a user segment does. B2/D2/D4 edited other parts of this file; the `load` override quoted below is unchanged by all three.

**Why:** spec C3 — "The canary runs on the native executor during prewarm/connect (adds one ~1 s inference to one cold load, once per device+model), never during a user's live session audio." `WhisperNativeBackend.load` IS that point. It is reached from **two** native executors, not one: `LocalWhisperEngine`'s single native-executor thread (prewarm / prewarmModelSwitch / connect) **and** `BatchTranscriber.loadCtx` (`BatchTranscriber.kt:263-267`, on its own `nativeDispatcher`). The property that matters holds for both: neither call site is inside a transcribe of user audio — each is a model LOAD that precedes the work — and `NativeComputeGate` (fair, reentrant) serializes them, so the ~1 s canary can never interleave with a live session's segments. It already holds that gate for the whole call. On failure the GPU context is freed and the model reloads on CPU inside the same call, so the caller receives a working context either way and the session proceeds exactly as today. On an ABSENT asset nothing is recorded at all (see the block below) — a packaging slip must never write the permanent latch.

- [ ] **Step 1 — wire the canary.** In `TranscriptionEngine.kt`, replace this OLD block (~:122-133):
```kotlin
    override fun load(modelPath: String): Long = NativeComputeGate.serialized {
        ensureBackendsLoaded()
        val useGpu = GpuPolicy.decideUseGpuForLoad(modelPath)
        if (!useGpu) return@serialized WhisperNative.init(modelPath, false)
        // finally (not sequential code): a survivable Java exception between arm and disarm must
        // still disarm — only true process death may leave the sentinel behind.
        try {
            WhisperNative.init(modelPath, true)
        } finally {
            GpuPolicy.onGpuLoadReturned()
        }
    }
```
with:
```kotlin
    override fun load(modelPath: String): Long = NativeComputeGate.serialized {
        ensureBackendsLoaded()
        val useGpu = GpuPolicy.decideUseGpuForLoad(modelPath)
        if (!useGpu) return@serialized WhisperNative.init(modelPath, false)
        // finally (not sequential code): a survivable Java exception between arm and disarm must
        // still disarm — only true process death may leave the sentinel behind.
        val ctx = try {
            WhisperNative.init(modelPath, true)
        } finally {
            GpuPolicy.onGpuLoadReturned()
        }
        if (ctx == 0L || !GpuPolicy.needsCanary()) return@serialized ctx

        // GPU CANARY (3.6.0 Workstream C). A multilingual model reached the GPU for the first
        // time on this device: prove it decodes correctly BEFORE any user audio touches it. The
        // multilingual GPU failure mode is silent corruption, which no crash sentinel can catch
        // (the empirical-corruption docblock above GpuPolicy.isGpuSafeModel) — so one bundled ~1 s clip of spoken digits is transcribed and
        // matched by the pure rule in GpuCanaryPolicy.
        //
        // WHERE THIS RUNS: load() is reached from BOTH native executors — LocalWhisperEngine's
        // (prewarm / prewarmModelSwitch / connect) AND BatchTranscriber.loadCtx — but in NEITHER
        // case from inside a transcribe of user audio, and NativeComputeGate serializes the two,
        // so the ~1 s cost can never land mid-session. At most ONCE per device+model per app
        // version.
        //
        // Routed through transcribeInternal so the canary pays the same GpuPolicy compute
        // sentinel a real segment does; useVad=false because the clip is already speech-only.
        // Sentinel interaction, handled in GpuPolicy.onCanaryResult: that wrapper writes
        // gpu_validated=true for any transcribe that merely did not THROW, which a garbage-
        // decoding GPU does — so a failing canary would otherwise leave "validated" set on the
        // very model it latches to CPU, disarming the crash sentinel. onCanaryResult(false)
        // clears keyValidated in the same edit.
        val samples = CanaryAudio.samples()
        if (samples == null) {
            // NO VERDICT — not a failure. A missing or unreadable asset (omitted from the
            // package, wrong format, asset shrink) is evidence about the BUILD, not about this
            // GPU, and onCanaryResult(false) would write a permanent per-(versionCode, model)
            // CPU latch with no in-app recovery. Fall back to CPU for this load and record
            // nothing: canaryPending is re-decided at the top of the next decideUseGpuForLoad,
            // so the next launch simply retries once the asset is really there.
            android.util.Log.w("WE-DIAG", "gpu-canary: asset unavailable — no verdict recorded")
            runCatching { WhisperNative.free(ctx) }
            return@serialized WhisperNative.init(modelPath, false)
        }
        val text = runCatching {
            transcribeInternal(ctx, samples, "en", useVad = false, onNewSegment = null)
        }.getOrDefault("")
        val passed = GpuCanaryPolicy.canaryPasses(text)
        // Length only — the canary text is known audio, but the no-transcript-logging rule is
        // absolute so the habit never erodes.
        android.util.Log.i(
            "WE-DIAG",
            "gpu-canary: passed=$passed outLen=${text.length}",
        )
        GpuPolicy.onCanaryResult(passed)
        if (passed) return@serialized ctx

        // Failed: drop the GPU context and reload on CPU inside this same call, so the caller
        // gets a working context and the session proceeds exactly as it does today. The latch
        // GpuPolicy just wrote means no later load for this model even attempts the GPU.
        runCatching { WhisperNative.free(ctx) }
        WhisperNative.init(modelPath, false)
    }
```

- [ ] **Step 2 — verify:**
```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:assembleDebug --no-daemon
```
Expected: both `BUILD SUCCESSFUL`, zero failures. With the C2 toggle off (the shipped default) `GpuPolicy.needsCanary()` is never true for a multilingual model and `decideUseGpuForLoad` returns false exactly as in 3.5.0 — this whole block is unreachable by default.

- [ ] **Step 3 — commit:**
```powershell
git add app/src/main/java/com/whispereverywhere/transcription/TranscriptionEngine.kt
git commit -m "feat(gpu): run the canary on the cold GPU load, latch the verdict, fall back to CPU on failure (C5)" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`nClaude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT"
```

---

### Task C6: The Settings developer toggle

**Files:**
- Edit: `app/src/main/java/com/whispereverywhere/ui/screens/SettingsScreen.kt`

**Builds on:** C2 (`gpuMultilingualExperiment` flow + setter). First edit to this file in this plan.

**Why:** spec C4 — the path ships behind a Settings developer toggle named **"Try GPU for multilingual (experimental)"**. Placed at the end of the existing "Preferences" section, after the Vibration row, using the same `SettingsSwitchItem` component and the same `collectAsState()` state pattern as its neighbours. Compose shell — no JVM test by house convention.

- [ ] **Step 1 — the state.** Replace this OLD block (~:143-146):
```kotlin
    val vibrationEnabled by app.preferencesManager.vibrationEnabled.collectAsState()
    val bubbleAlwaysOn by app.preferencesManager.bubbleAlwaysOn.collectAsState()
    val dictationFirstKeyboard by app.preferencesManager.dictationFirstKeyboard.collectAsState()
    val preferDeviceAudio by app.preferencesManager.preferDeviceAudio.collectAsState()
```
with:
```kotlin
    val vibrationEnabled by app.preferencesManager.vibrationEnabled.collectAsState()
    val bubbleAlwaysOn by app.preferencesManager.bubbleAlwaysOn.collectAsState()
    val dictationFirstKeyboard by app.preferencesManager.dictationFirstKeyboard.collectAsState()
    val preferDeviceAudio by app.preferencesManager.preferDeviceAudio.collectAsState()
    val gpuMultiExperiment by app.preferencesManager.gpuMultilingualExperiment.collectAsState()
```

- [ ] **Step 2 — the row.** Replace this OLD block (the Vibration row and its section close, ~:584-591):
```kotlin
                SettingsSwitchItem(
                    icon = Icons.Filled.Vibration,
                    title = "Vibration Feedback",
                    subtitle = "Vibrate on recording start/stop",
                    checked = vibrationEnabled,
                    onCheckedChange = { app.preferencesManager.setVibrationEnabled(it) }
                )
            }
```
with:
```kotlin
                SettingsSwitchItem(
                    icon = Icons.Filled.Vibration,
                    title = "Vibration Feedback",
                    subtitle = "Vibrate on recording start/stop",
                    checked = vibrationEnabled,
                    onCheckedChange = { app.preferencesManager.setVibrationEnabled(it) }
                )
                // 3.6.0 Workstream C — developer toggle, OFF by default and shipping off. The
                // multilingual GPU path is gated behind a bundled-clip canary that must pass on
                // THIS device before any real audio uses it; a failure latches the model to CPU
                // permanently. Naming it "experimental" is the honest description, not a hedge.
                SettingsSwitchItem(
                    icon = Icons.Filled.Memory,
                    title = "Try GPU for multilingual (experimental)",
                    subtitle = "Multilingual models normally run on the CPU because some phones " +
                        "decode them incorrectly on the GPU. With this on, the app tests the GPU " +
                        "once with a built-in clip and only uses it if the result is correct.",
                    checked = gpuMultiExperiment,
                    onCheckedChange = { app.preferencesManager.setGpuMultilingualExperiment(it) }
                )
            }
```
(`Icons.Filled.Memory` resolves through the existing `import androidx.compose.material.icons.filled.*` at `SettingsScreen.kt:15`. If the build reports it unresolved, substitute `Icons.Filled.Speed` — do not add a new icon dependency.)

- [ ] **Step 3 — verify:**
```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:assembleDebug --no-daemon
```
Expected: both `BUILD SUCCESSFUL`, zero failures.

- [ ] **Step 4 — commit:**
```powershell
git add app/src/main/java/com/whispereverywhere/ui/screens/SettingsScreen.kt
git commit -m "feat(settings): 'Try GPU for multilingual (experimental)' developer toggle (C6)" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`nClaude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT"
```

---

### Task C7: verification gate — default-off proven, no live-session canary (no code changes, no commit)

**Why:** the whole workstream's safety claim is "shipped OFF; default behavior byte-identical to 3.5.0". That is a property of the code, so it is checked, not asserted. Read-only; if any check fails, STOP and fix the responsible task.

- [ ] **Check 1 — the preference default is false.** Run:
```powershell
Select-String -Path "app/src/main/java/com/whispereverywhere/data/local/PreferencesManager.kt" -Pattern "KEY_GPU_MULTI_EXPERIMENT"
```
Expect THREE hits: the `MutableStateFlow(prefs.getBoolean(KEY_GPU_MULTI_EXPERIMENT, false))` construction, the setter's `putBoolean`, and the key constant. (Corrected from "two" by C2's review — the mandated code yields three; a two-hit expectation would false-STOP this gate.) The default literal must be `false`.

- [ ] **Check 2 — nothing enables the toggle in code.** Run (`Select-String` has no `-Recurse`, and `-Path <directory>` matches no files — the file list must come from `Get-ChildItem`, or this check errors out / reads as a vacuous pass):
```powershell
Get-ChildItem -Recurse -Filter *.kt app/src/main/java/com/whispereverywhere | Select-String -Pattern "setGpuMultilingualExperiment"
```
Expect exactly ONE hit outside `PreferencesManager.kt`: the `onCheckedChange` in `SettingsScreen.kt`. Any other caller would be a code path that flips the default behind the user's back.

- [ ] **Check 3 — the canary never runs during a session's audio.** Confirm `GpuCanaryPolicy`/`CanaryAudio` are referenced ONLY from `WhisperNativeBackend.load` in production code:
```powershell
Get-ChildItem -Recurse -Filter *.kt app/src/main/java/com/whispereverywhere | Select-String -Pattern "CanaryAudio|GpuCanaryPolicy"
```
PASS criterion: hits only in `GpuCanaryPolicy.kt`, `CanaryAudio.kt`, and `TranscriptionEngine.kt` (the `load` override). `load` itself is reached from **two** call sites — `LocalWhisperEngine`'s prewarm / prewarmModelSwitch / connect, and `BatchTranscriber.loadCtx` — and the safety property is that NEITHER is inside a transcribe of user audio (both are model loads that precede the work), with `NativeComputeGate` serializing them. Do not certify the narrower "only LocalWhisperEngine" claim: it is false, and the true one is what the canary's cost model rests on.

- [ ] **Check 4 — the .en path is untouched.** Confirm `isGpuSafeModel(m)` still short-circuits to the pre-existing behaviour for `.en` models: the C3 gate only runs INSIDE the `if (!isGpuSafeModel(m))` branch, so an English tier's GPU decision, sentinels and allowlist are byte-identical to 3.5.0.

- [ ] **Check 5 — full suite + build:**
```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:assembleDebug --no-daemon
```
Both `BUILD SUCCESSFUL`.

- [ ] **Check 6 — record the owner acceptance item this feeds** (H2): enable the toggle, force a cold multilingual load, grep `WE-DIAG` for `gpu-canary: passed=` and the following `GpuPolicy: canary PASSED|FAILED` line. Both verdicts are results; the default flip is a follow-up decision, not this release.

---

## Workstream F — Drain budget floor (no silent starvation)

### Task F1: The reserve arithmetic — `localDrainReserveMs` (pure, testable without an engine)

**Files:**
- Edit: `app/src/main/java/com/whispereverywhere/transcription/cloud/FallbackTranscriptionEngine.kt`
- Edit: `app/src/test/java/com/whispereverywhere/transcription/cloud/FallbackTranscriptionEngineTest.kt`

**Builds on:** nothing — first edit to both files. (D5's Check 4 verified `FallbackTranscriptionEngine.kt` was still untouched at the end of D; F is where it changes, inside `awaitIdle` only.)

**Interfaces:**
- Consumes: nothing — pure function, top-level in the engine's file (same package as the test).
- Produces: `internal fun localDrainReserveMs(budgetMs: Long): Long` = `min(60_000, budgetMs / 5)`, consumed by F2/F3's `awaitIdle` rewrite.

**Why:** `FallbackTranscriptionEngine.awaitIdle` shares ONE deadline between cloud and local; a near-timeout cloud tail starves rescued local segments into silent drops (spec Workstream F, analysis §3 batch worst case). The floor is `min(60 s, budget/5)` — with the service's `FINALIZE_TIMEOUT_MS = 300_000` (`FloatingBubbleService.kt:270`) that is exactly 60 000 ms. The arithmetic ships first as a pure function so it is pinned independently of the drain plumbing.

- [ ] **Step 1: Write the failing test.** In `FallbackTranscriptionEngineTest.kt`, the current end of the file is the fatal-latch test. Edit with OLD:
```kotlin
        assertEquals("one fatal costs ONE request — the latch held", 1, provider.payloads.size)
    }
}
```
NEW:
```kotlin
        assertEquals("one fatal costs ONE request — the latch held", 1, provider.payloads.size)
    }

    // ---------------------------------------------------------------- the drain budget floor

    @Test fun the_local_reserve_is_a_fifth_of_the_budget_capped_at_sixty_seconds() {
        // WORKSTREAM F (spec 2026-08-19): the share of one shared awaitIdle budget reserved for
        // the LOCAL drain — min(60 s, budget/5). FINALIZE_TIMEOUT_MS (300 s) hits the cap exactly.
        assertEquals(60_000L, localDrainReserveMs(300_000L))
        assertEquals(2_000L, localDrainReserveMs(10_000L))
        assertEquals(1_600L, localDrainReserveMs(8_000L))
        assertEquals("beyond 300 s the cap holds", 60_000L, localDrainReserveMs(3_600_000L))
        assertEquals("a zero budget reserves nothing", 0L, localDrainReserveMs(0L))
    }
}
```

- [ ] **Step 2: Run it — expect a compile failure.**
```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon --tests "com.whispereverywhere.transcription.cloud.FallbackTranscriptionEngineTest"
```
Expected: `BUILD FAILED` at `compileDebugUnitTestKotlin` with `Unresolved reference 'localDrainReserveMs'`.

- [ ] **Step 3: Implement.** In `FallbackTranscriptionEngine.kt`, edit with OLD (~:54-56, the class KDoc opener — unique):
```kotlin
/**
 * Runs [cloud] first and retries failed segments on [local], preserving seq so the orderer still
 * releases in order.
```
NEW:
```kotlin
/**
 * The minimum share of one [FallbackTranscriptionEngine.awaitIdle] budget reserved for the LOCAL
 * drain: min(60 s, budget / 5). Rescued segments run on the local executor, and a cloud tail that
 * consumed the whole shared deadline used to leave them a zero-width fence — this floor is what
 * they keep (spec 2026-08-19 Workstream F). Top-level and pure so the arithmetic is testable
 * without an engine.
 */
internal fun localDrainReserveMs(budgetMs: Long): Long = minOf(60_000L, budgetMs / 5L)

/**
 * Runs [cloud] first and retries failed segments on [local], preserving seq so the orderer still
 * releases in order.
```

- [ ] **Step 4: Run green.**
```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon --tests "com.whispereverywhere.transcription.cloud.FallbackTranscriptionEngineTest"
```
Expected: `BUILD SUCCESSFUL` — the class now runs 33 tests, 0 failed (report at `C:\Users\bastr\.androidbuild\WhisperEverywhere\app\reports\tests\testDebugUnitTest\index.html`).

- [ ] **Step 5: Commit.**
```powershell
git add app/src/main/java/com/whispereverywhere/transcription/cloud/FallbackTranscriptionEngine.kt app/src/test/java/com/whispereverywhere/transcription/cloud/FallbackTranscriptionEngineTest.kt
git commit -m "feat(cloud): local drain reserve arithmetic - min(60 s, budget/5) (F1)" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`nClaude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT"
```

---

### Task F2: The drain floor — a rescue armed near the deadline still delivers

**Files:**
- Edit: `app/src/main/java/com/whispereverywhere/transcription/cloud/FallbackTranscriptionEngine.kt` (`awaitIdle` body only)
- Edit: `app/src/test/java/com/whispereverywhere/transcription/cloud/FallbackTranscriptionEngineTest.kt`

**Builds on:** F1 (`localDrainReserveMs`; the test's OLD anchor is the tail F1 created). The inline `WhisperBackend` object in the new test overrides only `load`/`transcribe`/`release` — it compiles against B2's and D2's additions because both carry defaults.

**Interfaces:**
- Produces: `local.awaitIdle` never receives less than the reserve. The 3.5.0 happy-path skip block and the `LocalRelay` delivery-fence discipline are NOT touched — byte-identical.

**The arithmetic, thought through against the current implementation:** today local's window is `deadline − now`, i.e. `budget − T_cloud`. When cloud's drain completes at `T_cloud ≈ budget` (the batch worst case: the tail segment resolves as a loss just before the deadline), the rescue that resolution arms synchronously inherits a near-zero window; `awaitIdle` returns while whisper is mid-transcribe, the finalize path flushes the orderer and detaches the sink, and the rescued text is silently dropped. The floor `maxOf(remaining, reserve)` fixes exactly this case: since `LocalWhisperEngine.awaitIdle` submits its fence task at call time, every rescue armed *before* cloud's fence returned is queued ahead of it and is therefore covered. When local owes nothing, the fence task on an idle executor completes immediately — the floor costs zero wall time on the happy path. Worst-case overrun is `budget + reserve` (bounded; F3 caps the case where the debt is already known, F4 documents the bound at the call site).

- [ ] **Step 1: Write the failing test.** In `FallbackTranscriptionEngineTest.kt`, edit with OLD (the tail F1 created):
```kotlin
        assertEquals("beyond 300 s the cap holds", 60_000L, localDrainReserveMs(3_600_000L))
        assertEquals("a zero budget reserves nothing", 0L, localDrainReserveMs(0L))
    }
}
```
NEW:
```kotlin
        assertEquals("beyond 300 s the cap holds", 60_000L, localDrainReserveMs(3_600_000L))
        assertEquals("a zero budget reserves nothing", 0L, localDrainReserveMs(0L))
    }

    @Test fun a_rescue_armed_near_the_deadline_still_gets_the_local_reserve() {
        // WORKSTREAM F: the starvation itself. Cloud's tail segment resolves as a loss ~500 ms
        // before the 8 s shared deadline; the rescue it arms (synchronously, inside cloud's own
        // resolution callback) needs ~1 s of transcribe. Under the old deadline arithmetic local
        // inherited the ~500 ms sliver, awaitIdle returned mid-transcribe, and the finalize flush
        // dropped the rescued text silently. With the floor, local gets
        // localDrainReserveMs(8_000) = 1_600 ms and the delivery is fenced.
        //
        // Real executor + real wall time on purpose: the bug IS the deadline arithmetic.
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val releaseCloud = CompletableDeferred<Unit>()
        try {
            val provider = FakeStt(
                gate = { releaseCloud.await() },
                respond = { SttResult.Failed(SttError.Offline) },
            )
            val backend = object : WhisperBackend {
                override fun load(modelPath: String) = 42L
                override fun transcribe(ctx: Long, samples: FloatArray, lang: String?, useVad: Boolean): String {
                    Thread.sleep(1_000) // the tail transcribe the old sliver could never cover
                    return "rescued locally"
                }
                override fun release(ctx: Long) = Unit
            }
            val local = LocalWhisperEngine(
                modelPathProvider = object : ModelPathProvider {
                    override fun installedModelPath() = "/models/tiny.bin"
                },
                retry = RetryPolicy(maxAttempts = 1, baseDelayMs = 0, maxDelayMs = 0, rng = { 0.0 }),
                backend = backend,
                executor = executor,
            )
            val e = engine(CloudTranscriptionEngine(provider, scope()), local)
            val l = Rec()
            e.connect(null, l)
            e.sendAudio(ByteArray(3200) { 1 })
            assertEquals(0L, e.commit())

            val drained = java.util.concurrent.atomic.AtomicBoolean(false)
            val waiter = Thread { drained.set(e.awaitIdle(8_000)) }
            waiter.start()
            // Let cloud burn most of the shared deadline, then hand it the loss.
            Thread.sleep(7_500)
            releaseCloud.complete(Unit)
            waiter.join(15_000)
            assertTrue("awaitIdle never returned", !waiter.isAlive)

            // Asserted IMMEDIATELY at return, no polling: the drain contract is DELIVERED, not
            // merely scheduled. Under the old arithmetic the delivery lands ~500 ms after this
            // assertion runs — exactly the window the finalize flush used to strike in.
            assertEquals(
                "the rescued text must be DELIVERED before awaitIdle returns (the reserve floor)",
                1, l.all.size,
            )
            assertEquals(0L to SegmentOutcome.Text("rescued locally"), l.all.single())
            assertTrue("the drain must report success once every owed rescue delivered", drained.get())
        } finally {
            releaseCloud.complete(Unit)
            executor.shutdownNow()
        }
    }
}
```

- [ ] **Step 2: Run it — expect the starvation.**
```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon --tests "com.whispereverywhere.transcription.cloud.FallbackTranscriptionEngineTest"
```
Expected: `BUILD FAILED`, `a_rescue_armed_near_the_deadline_still_gets_the_local_reserve FAILED` with `java.lang.AssertionError: the rescued text must be DELIVERED before awaitIdle returns (the reserve floor) expected:<1> but was:<0>` (takes ~8 s wall — cloud drains at ~7.5 s, local gets the ~500 ms sliver, the 1 s transcribe overruns it).

- [ ] **Step 3: Implement the floor.** In `FallbackTranscriptionEngine.kt`, two edits.

Edit A, OLD (~:408-410):
```kotlin
    override fun awaitIdle(timeoutMs: Long): Boolean {
        val budget = timeoutMs.coerceIn(0L, MAX_DRAIN_MS)
        val deadlineNs = System.nanoTime() + budget * NANOS_PER_MS
```
NEW:
```kotlin
    override fun awaitIdle(timeoutMs: Long): Boolean {
        val budget = timeoutMs.coerceIn(0L, MAX_DRAIN_MS)
        val reserve = localDrainReserveMs(budget)
        val deadlineNs = System.nanoTime() + budget * NANOS_PER_MS
```

Edit B, OLD (~:418-420):
```kotlin
        val remainingMs = ((deadlineNs - System.nanoTime()) / NANOS_PER_MS).coerceAtLeast(0L)
        val localDrained = local.awaitIdle(remainingMs)
        return cloudDrained && localDrained
```
NEW:
```kotlin
        val remainingMs = ((deadlineNs - System.nanoTime()) / NANOS_PER_MS).coerceAtLeast(0L)
        // The drain floor (3.6.0 Workstream F): a rescue armed near the END of cloud's drain used
        // to inherit whatever sliver of the shared deadline was left — near zero in the batch
        // worst case — and its delivery was then cut off by the finalize flush. Local now gets at
        // least [reserve] WHENEVER IT IS OWED SOMETHING. Every rescue armed before cloud's fence
        // returned is queued ahead of local's fence task, so it is covered.
        //
        // The floor is CONDITIONAL on retriesOutstanding() deliberately. The 3.5.0 skip above
        // requires cloudDrained, so on a cloud TIMEOUT it never fires and control reaches here
        // with remainingMs ~= 0. An unconditional floor would then make a timed-out cloud
        // session wait up to [reserve] on a local executor that owes nothing — and
        // LocalWhisperEngine.awaitIdle fences behind its WHOLE queue, which can still hold the
        // safety-net model load: exactly the multi-second dead wait the 3.5.0 skip was written
        // to delete. Guarded, "cloud timed out and local owes nothing" keeps 3.5.0's ~0 ms
        // behavior byte for byte, and the owed case still gets its full reserve.
        val localDrained =
            local.awaitIdle(if (retriesOutstanding()) maxOf(remainingMs, reserve) else remainingMs)
        return cloudDrained && localDrained
```

The skip block between the two edits (`if (cloudDrained && !retriesOutstanding()) { ... return true }` with its `finalize-timing: local-drain=0ms (skipped: no outstanding retries)` log) is NOT touched.

**The `retriesOutstanding()` guard does not weaken Step 1's test** — check this before running it. The test's rescue is registered in `retries` for the WHOLE local transcribe, not just while it is queued: `FallbackTranscriptionEngine` puts it in the map when cloud's loss arms it (`:288, :314`) and the identity-checked removal happens only after `resolve()` has DELIVERED (`:288`'s delivery fence, `:428`). The 1 s transcribe is therefore still in flight — and still counted — at the moment this line executes, so `retriesOutstanding()` is true, the floor applies, and the assertion "the rescued text must be DELIVERED before awaitIdle returns" holds exactly as written. The guard only removes the case the test does not exercise: no rescue owed at all.

- [ ] **Step 4: Run green.** Same command as Step 2. Expected: `BUILD SUCCESSFUL` — 34 tests, 0 failed. The three 3.5.0-skip pins (`awaitIdle_does_not_wait_on_local_when_no_retry_is_outstanding`, `a_happy_path_stop_does_not_pay_for_the_safety_nets_model_load`, `the_skip_cannot_fire_while_a_rescues_delivery_is_still_in_flight`) must all still pass.

- [ ] **Step 5: Commit.**
```powershell
git add app/src/main/java/com/whispereverywhere/transcription/cloud/FallbackTranscriptionEngine.kt app/src/test/java/com/whispereverywhere/transcription/cloud/FallbackTranscriptionEngineTest.kt
git commit -m "feat(cloud): drain budget floor - rescues armed near the deadline keep the local reserve (F2)" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`nClaude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT"
```

---

### Task F3: Cap cloud's share when local is already owed work — floor + cap fit the budget

**Files:**
- Edit: `app/src/main/java/com/whispereverywhere/transcription/cloud/FallbackTranscriptionEngine.kt` (`awaitIdle` body + its kdoc final paragraph)
- Edit: `app/src/test/java/com/whispereverywhere/transcription/cloud/FallbackTranscriptionEngineTest.kt`

**Builds on:** F2 (the floor and the `reserve` local it introduced; the test's OLD anchor is F2's tail).

**Interfaces:**
- Produces: when retries are outstanding *at call time*, cloud's wait is `budget − reserve`, so floor + cap total exactly `budget`. When no retries are outstanding at call time cloud keeps the full budget (the happy path is bit-identical to 3.5.0, so the skip fires exactly as before).

**Why the cap keys off call-time `retriesOutstanding()`:** before a full cloud drain the predicate can only prove "work exists", never "no work will come" (the existing comment on `retriesOutstanding` says exactly this). "Work exists" is precisely the direction a cap needs — it is the *skip* that needs the other direction, and the skip still runs only after a full cloud drain, unchanged.

- [ ] **Step 1: Write the failing test.** In `FallbackTranscriptionEngineTest.kt`, edit with OLD (the tail F2 created):
```kotlin
            assertTrue("the drain must report success once every owed rescue delivered", drained.get())
        } finally {
            releaseCloud.complete(Unit)
            executor.shutdownNow()
        }
    }
}
```
NEW:
```kotlin
            assertTrue("the drain must report success once every owed rescue delivered", drained.get())
        } finally {
            releaseCloud.complete(Unit)
            executor.shutdownNow()
        }
    }

    @Test fun a_stuck_cloud_is_capped_so_an_owed_rescue_keeps_its_reserve_inside_the_budget() {
        // WORKSTREAM F, the cap half: when local is ALREADY owed a rescue at stop time, cloud's
        // wait is capped at budget - reserve so floor + cap still fit the shared deadline. With
        // an 8 s budget the cap is 6_400 ms: a cloud segment that never resolves must stop being
        // waited on there, not at 8_000 ms — the reserve is spent fencing local's owed delivery,
        // not burned watching a dead upload.
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val neverResolves = CompletableDeferred<Unit>()
        try {
            val transcribeStarted = CountDownLatch(1)
            val provider = FakeStt(
                gate = { pcm -> if (pcm[0].toInt() == 9) neverResolves.await() },
                respond = { SttResult.Failed(SttError.Offline) },
            )
            val backend = object : WhisperBackend {
                override fun load(modelPath: String) = 42L
                override fun transcribe(ctx: Long, samples: FloatArray, lang: String?, useVad: Boolean): String {
                    transcribeStarted.countDown()
                    Thread.sleep(700)
                    return "rescued locally"
                }
                override fun release(ctx: Long) = Unit
            }
            val local = LocalWhisperEngine(
                modelPathProvider = object : ModelPathProvider {
                    override fun installedModelPath() = "/models/tiny.bin"
                },
                retry = RetryPolicy(maxAttempts = 1, baseDelayMs = 0, maxDelayMs = 0, rng = { 0.0 }),
                backend = backend,
                executor = executor,
            )
            val e = engine(CloudTranscriptionEngine(provider, scope()), local)
            val l = Rec()
            e.connect(null, l)
            // Segment 0 fails on cloud immediately; its rescue is mid-transcribe (700 ms) when
            // awaitIdle is called below — local is provably owed work at call time.
            e.sendAudio(ByteArray(3200) { 1 })
            assertEquals(0L, e.commit())
            assertTrue(transcribeStarted.await(5, TimeUnit.SECONDS))
            // Segment 1's upload never resolves: cloud can never drain.
            e.sendAudio(ByteArray(3200) { 9 })
            assertEquals(1L, e.commit())

            val startNs = System.nanoTime()
            val drained = e.awaitIdle(8_000)
            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000

            assertTrue("cloud never drained, so the composite result must be false", !drained)
            assertEquals(
                "the owed rescue delivered inside the shared budget",
                0L to SegmentOutcome.Text("rescued locally"), l.all.single(),
            )
            assertTrue(
                "cloud's wait must stop at the 6_400 ms cap, not burn the full 8_000 ms (took ${elapsedMs}ms)",
                elapsedMs < 7_200,
            )
        } finally {
            neverResolves.complete(Unit)
            executor.shutdownNow()
        }
    }
}
```

- [ ] **Step 2: Run it — expect the cap missing.** Same test command as F2 Step 2. Expected: `BUILD FAILED`, `a_stuck_cloud_is_capped_so_an_owed_rescue_keeps_its_reserve_inside_the_budget FAILED` with `java.lang.AssertionError: cloud's wait must stop at the 6_400 ms cap, not burn the full 8_000 ms (took ~8000ms)`.

- [ ] **Step 3: Implement the cap + rewrite the kdoc's final paragraph.** In `FallbackTranscriptionEngine.kt`, two edits.

Edit A, OLD (unique, ~:411):
```kotlin
        val cloudDrained = cloud.awaitIdle(budget)
```
NEW:
```kotlin
        // The cap (3.6.0 Workstream F): retries outstanding at stop time mean local is ALREADY
        // owed work, so cloud's wait is capped at budget - reserve and the floor below fits
        // inside the shared deadline. Before a full cloud drain retriesOutstanding() can only
        // prove "work exists", never "no work will come" — exactly the direction a cap needs,
        // and the opposite of what the happy-path skip needs (that one still runs only AFTER a
        // full cloud drain, unchanged below).
        val cloudBudget = if (retriesOutstanding()) budget - reserve else budget
        // Makes a CAPPED stop distinguishable from a TIMED-OUT one in the logs the owner saves
        // (H2). With retries outstanding, a cloud drain that would have finished inside the last
        // `reserve` ms of the budget now returns false, so FloatingBubbleService logs
        // "finalize: drain timed out after ${FINALIZE_TIMEOUT_MS}ms" on a session where every
        // segment actually delivered. A cloud-budget line SMALLER than the full budget beside
        // that warning means the cap fired — not that anything was lost.
        android.util.Log.i(TAG, "finalize-timing: cloud-budget=${cloudBudget}ms (reserve=${reserve}ms)")
        val cloudDrained = cloud.awaitIdle(cloudBudget)
```

Edit B (the method's kdoc final paragraph, ~:404-407), OLD:
```kotlin
     * The two drains share ONE budget by deadline rather than splitting it in half, so a fast cloud
     * drain leaves the local retry almost all of the time. If cloud consumes the whole budget the
     * result is false regardless of what local reports.
     */
```
NEW:
```kotlin
     * The two drains share ONE budget by deadline rather than splitting it in half, so a fast
     * cloud drain still leaves the local retry almost all of the time. Since 3.6.0 (spec
     * 2026-08-19 Workstream F) local is additionally guaranteed a floor of [localDrainReserveMs]
     * — min(60 s, budget/5) — because a cloud tail that resolved near the end of the deadline
     * used to arm a rescue and bequeath it a zero-width fence: awaitIdle returned while whisper
     * was mid-transcribe and the finalize flush dropped the rescued text silently (batch-mode
     * worst case). Two consequences, both deliberate:
     *
     *  - retries outstanding at CALL time cap cloud's wait at budget - reserve, so floor + cap
     *    total exactly the shared budget;
     *  - a rescue armed only DURING cloud's drain may push the floor past the caller's deadline
     *    by up to the reserve (worst case budget + 60 s — still bounded, and an idle local queue
     *    completes its fence task immediately, so the floor costs nothing when local owes
     *    nothing).
     *
     * If cloud consumes its whole share the result is false regardless of what local reports.
     */
```

- [ ] **Step 4: Run green.** Same test command. Expected: `BUILD SUCCESSFUL` — 35 tests, 0 failed. In particular the skip pins and `awaitIdle_still_drains_every_outstanding_retry_before_returning` (whose cloud drains in milliseconds either way, capped or not) stay green.

- [ ] **Step 5: Full JVM suite.**
```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon
```
Expected: `BUILD SUCCESSFUL`, no failed tests.

- [ ] **Step 6: Commit.**
```powershell
git add app/src/main/java/com/whispereverywhere/transcription/cloud/FallbackTranscriptionEngine.kt app/src/test/java/com/whispereverywhere/transcription/cloud/FallbackTranscriptionEngineTest.kt
git commit -m "feat(cloud): cap cloud's drain share when local is owed work - floor + cap fit the shared budget (F3)" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`nClaude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT"
```

---

### Task F4: Document the widened finalize bound at the call site (comment-only)

**Files:**
- Edit: `app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt` (the `FINALIZE_TIMEOUT_MS` comment, ~:267)

**Builds on:** F2/F3 (the bound being documented). A2 replaced the field pair immediately BELOW this comment; E2/E4/E5/E6 edited other regions. The OLD block is unchanged by all of them.

**Interfaces:** none — no behavior change; the comment above the constant now states the true worst-case bound.

- [ ] **Step 1: Edit the comment.** OLD:
```kotlin
    // Max time finalize waits for the transcription backlog to drain before force-ending the
    // session. Generous because a slow model (e.g. the large tier) can lag several segments behind
    // real time; bounded so a pathological run can't hang the bubble in FINALIZING forever.
    private val FINALIZE_TIMEOUT_MS = 300_000L
```
NEW:
```kotlin
    // Max time finalize waits for the transcription backlog to drain before force-ending the
    // session. Generous because a slow model (e.g. the large tier) can lag several segments behind
    // real time; bounded so a pathological run can't hang the bubble in FINALIZING forever.
    // Since 3.6.0 the fallback engine may run up to 60 s past this when a rescue was armed only
    // during the cloud drain — its local reserve floor (FallbackTranscriptionEngine.awaitIdle,
    // localDrainReserveMs). Still bounded: worst case is this value + 60 s.
    private val FINALIZE_TIMEOUT_MS = 300_000L
```

- [ ] **Step 2: Verify it still compiles.**
```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit.**
```powershell
git add app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt
git commit -m "docs(service): FINALIZE_TIMEOUT_MS comment - the fallback drain floor can add up to 60 s (F4)" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`nClaude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT"
```

---

## Workstream G — Tail inference floor (validation-gated; may ship nothing)

> **⚠ Assembly note:** the source part carrying Workstream G began mid-file inside G1's
> implementation — G1's test, its command steps and the head of `WerMath` were missing. G1 below
> is reconstructed around the ONE fragment that did survive verbatim (the `floorQualifies` KDoc
> tail and body), with the surviving text preserved exactly. G2, G3 and G4 are the original
> author's, with output paths corrected and seam notes added.

### Task G1: `WerMath` — pure token-WER scoring + the executable floor gate (TDD)

**Files:**
- Create: `app/src/main/java/com/whispereverywhere/util/WerMath.kt`
- Create: `app/src/test/java/com/whispereverywhere/util/WerMathTest.kt`

**Builds on:** nothing — two new files. (Neither exists at HEAD: `app/src/main/java/com/whispereverywhere/util/` holds only `AudioBands.kt`, `AudioMath.kt`, `ByteFormat.kt`, `RetryPolicy.kt`, `SpeechSegmenter.kt`, `StreamingAudioRecorder.kt`.)

**Why:** Workstream G ships a MEASUREMENT, and the measurement's verdict must be executable, not eyeballed — G4 branches on it literally. Both consumers are instrumented tests that cannot run on the JVM (G3's floor sweep, C8's GPU/CPU A-B), so the scoring itself lives here, pure and pinned.

- [ ] **Write the failing test.** Create `app/src/test/java/com/whispereverywhere/util/WerMathTest.kt`:
```kotlin
package com.whispereverywhere.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Workstream G (3.6.0): token-level WER, and the executable gate Task G4 branches on. Pure
 * because both consumers (the audio_ctx floor sweep, the GPU/CPU A-B) are instrumented tests
 * that the JVM suite cannot run — the arithmetic has to be pinned here or nowhere.
 */
class WerMathTest {

    private val eps = 1e-9

    @Test fun identicalTextScoresZero() {
        assertEquals(0.0, WerMath.wer("one two three", "one two three"), eps)
    }

    @Test fun casingAndPunctuationAreIgnored() {
        // Two decodes of the same audio differ in punctuation constantly; that is not an error.
        assertEquals(0.0, WerMath.wer("Ask not what your country can do.", "ask not what your country can do"), eps)
    }

    @Test fun oneSubstitutionInFiveWordsIsPointTwo() {
        assertEquals(0.2, WerMath.wer("one two three four five", "one two THREEE four five"), eps)
    }

    @Test fun oneDeletionAndOneInsertionEachCountAsOneError() {
        assertEquals(0.25, WerMath.wer("one two three four", "one three four"), eps)
        assertEquals(0.25, WerMath.wer("one two three four", "one two extra three four"), eps)
    }

    @Test fun aCompletelyDifferentHypothesisScoresOne() {
        assertEquals(1.0, WerMath.wer("one two three", "alpha beta gamma"), eps)
    }

    @Test fun anEmptyHypothesisLosesEveryReferenceWord() {
        assertEquals(1.0, WerMath.wer("one two three", "   "), eps)
    }

    @Test fun anEmptyReferenceScoresZeroOnlyWhenTheHypothesisIsEmptyToo() {
        assertEquals(0.0, WerMath.wer("", ""), eps)
        assertEquals(1.0, WerMath.wer("", "unexpected words"), eps)
    }

    @Test fun theGateIsTenPercent() {
        assertEquals(0.10, WerMath.FLOOR_WER_GATE, eps)
    }

    @Test fun floorQualifiesOnlyWhenEveryRecordedWerIsAtOrUnderTheGate() {
        assertTrue(WerMath.floorQualifies(listOf(0.0, 0.05, 0.10)))
        assertFalse("one bad slice disqualifies the floor", WerMath.floorQualifies(listOf(0.0, 0.11)))
        assertFalse("no measurements is not a pass", WerMath.floorQualifies(emptyList()))
    }
}
```

- [ ] **Run it, expect the red:**
```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon --tests "com.whispereverywhere.util.WerMathTest"
```
Expect **compile failure**: `e: ... Unresolved reference 'WerMath'` (BUILD FAILED).

- [ ] **Implement.** Create `app/src/main/java/com/whispereverywhere/util/WerMath.kt`:
```kotlin
package com.whispereverywhere.util

/**
 * Token-level Word Error Rate and the audio_ctx floor's accuracy gate (3.6.0 Workstream G).
 *
 * The stop-tail fragment pays a 768-frame minimum audio_ctx for ~119 frames of audio — a 6.45x
 * overshoot on the finalize critical path. The floor was raised 256 -> 768 for a REAL, documented
 * accuracy regression (stock whisper models garble short phrases under aggressive audio_ctx
 * reduction), so it is lowered ONLY if a per-tier bench proves accuracy holds. That proof is
 * arithmetic, and arithmetic belongs in a pure, tested object rather than in an instrumented
 * test nobody can run on CI.
 *
 * WER = edit distance between token sequences / reference token count. Tokens are lowercased and
 * stripped of punctuation: two decodes of the same audio differ in casing and punctuation
 * constantly, and that is not an error worth failing a floor over.
 */
object WerMath {

    /**
     * The accuracy gate a candidate audio_ctx floor must clear on EVERY benched slice of EVERY
     * benched tier: 10 % word error against the same tier's transcription at the production
     * floor. Chosen to be tight enough that the documented garbling regression cannot slip
     * through, loose enough that ordinary decode jitter on a short slice does not fail a good
     * floor.
     */
    const val FLOOR_WER_GATE = 0.10

    private val NON_WORD = Regex("[^\\p{L}\\p{Nd}']+")

    /** Lowercased word tokens, punctuation stripped. */
    fun tokens(text: String): List<String> =
        text.lowercase().split(NON_WORD).filter { it.isNotEmpty() }

    /**
     * Word Error Rate of [hypothesis] against [reference], in [0, ∞) — values above 1 are
     * possible when the hypothesis inserts more words than the reference contains. An empty
     * reference scores 0.0 against an empty hypothesis and 1.0 against any non-empty one
     * (a degenerate case the bench asserts away, but it must never divide by zero).
     */
    fun wer(reference: String, hypothesis: String): Double {
        val ref = tokens(reference)
        val hyp = tokens(hypothesis)
        if (ref.isEmpty()) return if (hyp.isEmpty()) 0.0 else 1.0
        return editDistance(ref, hyp).toDouble() / ref.size.toDouble()
    }

    /** Levenshtein distance over token lists; two rolling rows, so memory is O(hyp). */
    private fun editDistance(ref: List<String>, hyp: List<String>): Int {
        var prev = IntArray(hyp.size + 1) { it }
        var curr = IntArray(hyp.size + 1)
        for (i in 1..ref.size) {
            curr[0] = i
            for (j in 1..hyp.size) {
                val substitution = prev[j - 1] + if (ref[i - 1] == hyp[j - 1]) 0 else 1
                val deletion = prev[j] + 1
                val insertion = curr[j - 1] + 1
                curr[j] = minOf(substitution, deletion, insertion)
            }
            val swap = prev; prev = curr; curr = swap
        }
        return prev[hyp.size]
    }

    /**
     * The floor-candidate verdict: a candidate audio_ctx floor QUALIFIES only when at least one
     * measurement exists and EVERY recorded WER is at or under [FLOOR_WER_GATE]. Task G4 is gated on
     * this function's verdict as logged by the bench and recorded by the owner — never on
     * eyeballing.
     */
    fun floorQualifies(wers: List<Double>): Boolean =
        wers.isNotEmpty() && wers.all { it <= FLOOR_WER_GATE }
}
```

- [ ] **Run green:** same command. Expected: `BUILD SUCCESSFUL`, all 9 tests pass.

- [ ] **Commit:**
```powershell
git add app/src/main/java/com/whispereverywhere/util/WerMath.kt app/src/test/java/com/whispereverywhere/util/WerMathTest.kt
git commit -m "feat(util): pure token-WER scoring + the executable audio_ctx floor gate (G1)" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`nClaude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT"
```

---

### Task G2: Native bench knob — settable `audio_ctx` floor, production default unchanged at 768 — **NATIVE**

**Why:** the floor is a hard-coded literal inside `transcribeRaw` (`whisper_jni.cpp:269-278`). To A-B lower floors on-device WITHOUT changing production behavior, the floor becomes a process-global atomic defaulting to 768 (byte-identical shipped behavior) with a JNI setter the bench calls. Production code never calls the setter.

**Builds on:** B1 and D1 (both shared files).
- `.cpp`: B1 inserted `detectedLanguage` just above `_free` (BELOW every anchor here); D1 inserted its trampoline just above `transcribeRaw` (BELOW the `we_install_native_logging` anchor, ABOVE nothing this task edits) and replaced the `whisper_full` call at `:280-283` (BELOW the audio_ctx block at `:269-278`). All three OLD blocks below are therefore unchanged.
- `WhisperNative.kt`: B1 inserted `detectedLanguage` immediately above the `free` pair anchored here; the two-line anchor itself is untouched and still the file's only occurrence.

**Native caveat:** the JVM suite cannot exercise this (`WhisperNative` loads the native lib). Verification is `:app:assembleDebug` compiling the `.so`, plus the owner on-device check it feeds — G3's bench run, whose floor-sweep log lines are impossible unless the setter resolved and worked. The JNI symbol name below is copied from the existing pattern character-for-character.

**Files:**
- Edit: `app/src/main/cpp/whisper_jni.cpp`
- Edit: `app/src/main/java/com/whispereverywhere/whisper/WhisperNative.kt`

**Steps:**

- [ ] **Edit 1 (include)** — in `app/src/main/cpp/whisper_jni.cpp`, anchor on the unique OLD text (`:1-2`):
```cpp
#include <jni.h>
#include <cmath>
```
Replace with:
```cpp
#include <jni.h>
#include <atomic>
#include <cmath>
```

- [ ] **Edit 2 (the atomic + setter)** — anchor on the unique OLD text (`:28-34`):
```cpp
static void we_install_native_logging() {
    static bool done = false;
    if (done) return;
    done = true;
    ggml_log_set(we_native_log, nullptr);
    whisper_log_set(we_native_log, nullptr);
}
```
Replace with:
```cpp
static void we_install_native_logging() {
    static bool done = false;
    if (done) return;
    done = true;
    ggml_log_set(we_native_log, nullptr);
    whisper_log_set(we_native_log, nullptr);
}

// ---------------------------------------------------------------------------------------------
// Encoder audio_ctx floor (3.6.0 Workstream G). 768 is the SHIPPED production value — raised
// 256 -> 768 for a documented accuracy regression (see the audio_ctx block in transcribeRaw).
// The setter exists ONLY for the WhisperBenchTest A-B harness: it lets an instrumented bench
// measure lower floors against 768 on the SAME device/model WITHOUT changing production
// behavior. Production code never calls it. Lowering the DEFAULT is gated on that bench's
// recorded per-tier accuracy verdict (WerMath.floorQualifies) — never done blind.
// ---------------------------------------------------------------------------------------------
static std::atomic<int> g_audio_ctx_floor{768};

extern "C" JNIEXPORT void JNICALL
Java_com_whispereverywhere_whisper_WhisperNative_setAudioCtxFloor(
        JNIEnv * /*env*/, jobject /* this */, jint floor) {
    int f = static_cast<int>(floor);
    if (f < 64)   f = 64;    // below the +64 headroom would be self-defeating
    if (f > 1500) f = 1500;  // the model maximum
    g_audio_ctx_floor.store(f, std::memory_order_relaxed);
    LOGI("audio_ctx floor set to %d (bench override; production default 768)", f);
}
```

- [ ] **Edit 3 (consume the atomic)** — anchor on the unique OLD text (`:270-277`):
```cpp
        int neededFrames = static_cast<int>(pcm.size() / 320) + 64;
        // Floor raised 256 -> 768: STOCK whisper models lose accuracy under aggressive audio_ctx
        // reduction (positional-embedding mismatch; FUTO's ACFT models are fine-tuned to tolerate
        // it, ours are not) — user-visible as garbled short phrases. 768 halves the encoder cost
        // vs full context while keeping a wide safety margin; the GPU makes the rest cheap.
        if (neededFrames < 768)  neededFrames = 768;
        if (neededFrames > 1500) neededFrames = 1500;
        params.audio_ctx = neededFrames;
```
Replace with:
```cpp
        int neededFrames = static_cast<int>(pcm.size() / 320) + 64;
        // Floor raised 256 -> 768: STOCK whisper models lose accuracy under aggressive audio_ctx
        // reduction (positional-embedding mismatch; FUTO's ACFT models are fine-tuned to tolerate
        // it, ours are not) — user-visible as garbled short phrases. 768 halves the encoder cost
        // vs full context while keeping a wide safety margin; the GPU makes the rest cheap.
        // The literal now lives in g_audio_ctx_floor (same default, 768) so the bench harness
        // can A-B lower floors — see setAudioCtxFloor above. Production behavior: unchanged.
        const int floorFrames = g_audio_ctx_floor.load(std::memory_order_relaxed);
        if (neededFrames < floorFrames) neededFrames = floorFrames;
        if (neededFrames > 1500) neededFrames = 1500;
        params.audio_ctx = neededFrames;
```

- [ ] **Edit 4 (Kotlin external)** — in `app/src/main/java/com/whispereverywhere/whisper/WhisperNative.kt`, anchor on the unique OLD text (still the file's only `free` declaration after B1/D1):
```kotlin
    /** Frees the native whisper_context. Safe to call once per non-zero handle. */
    external fun free(ctxPtr: Long)
```
Replace with:
```kotlin
    /**
     * BENCH-ONLY override of the encoder audio_ctx floor (whisper_jni.cpp; the production
     * default is 768). Deliberately absent from this object's 1:1 KDoc list above — it maps to
     * no production behavior. Clamped natively to 64..1500 and process-global — it affects EVERY
     * subsequent transcribe in this process, which is why production code must never call it.
     * WhisperBenchTest's floor A-B is the sole caller and restores the production value in a
     * finally block.
     */
    external fun setAudioCtxFloor(floor: Int)

    /** Frees the native whisper_context. Safe to call once per non-zero handle. */
    external fun free(ctxPtr: Long)
```

- [ ] **Verify — suite (unaffected) then native compile:**
```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:assembleDebug --no-daemon
```
Expected: `BUILD SUCCESSFUL` twice (the second builds `libwhisper_jni.so` — any C++ error fails here).

- [ ] **Commit:**
```powershell
git add app/src/main/cpp/whisper_jni.cpp app/src/main/java/com/whispereverywhere/whisper/WhisperNative.kt
git commit -m "feat(jni): bench-only settable audio_ctx floor, production default stays 768 (G2)" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`nClaude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT"
```

---

### Task G3: Floor A-B bench in `WhisperBenchTest` + the decision record the owner fills — **INSTRUMENTED**

**Prerequisites:** G1 (`WerMath`), G2 (`setAudioCtxFloor`).

**Builds on:** nothing in this file yet — G3 is the FIRST edit to `WhisperBenchTest.kt`; C8 edits it afterwards and reuses the `WerMath` import added here.

**Why:** Workstream G ships the measurement, never a blind floor change. This extends the existing owner-run bench (run via the documented `adb install -r` + `am instrument` protocol, NEVER `:app:connectedDebugAndroidTest` / `:app:installDebug`, which wipe the owner's models) with a per-tier floor sweep scored by `WerMath` against the 768 reference, and creates the record file G4 is gated on.

**Instrumented caveat:** this test cannot run on the JVM. Verification = both compile targets green + the owner on-device run recorded in the decision file.

**Files:**
- Edit: `app/src/androidTest/java/com/whispereverywhere/whisper/WhisperBenchTest.kt`
- Create: `docs/superpowers/specs/2026-08-19-audio-ctx-floor-bench.md`

**Steps:**

- [ ] **Edit 1 (import)** — anchor on the unique OLD text (`:6-7`):
```kotlin
import com.whispereverywhere.transcription.WhisperNativeBackend
import com.whispereverywhere.util.AudioMath
```
Replace with:
```kotlin
import com.whispereverywhere.transcription.WhisperNativeBackend
import com.whispereverywhere.util.AudioMath
import com.whispereverywhere.util.WerMath
```

- [ ] **Edit 2 (companion constants)** — anchor on the unique OLD text (`:52-53`):
```kotlin
        val SLICE_SECONDS = listOf(1, 3, 8, 15)
    }
```
Replace with:
```kotlin
        val SLICE_SECONDS = listOf(1, 3, 8, 15)

        /**
         * The production audio_ctx floor (whisper_jni.cpp g_audio_ctx_floor default). If Task G4
         * ever lowers the native default, bump THIS in the same commit — the floor sweep uses it
         * as the A-B reference arm AND restores it in its finally.
         */
        const val PRODUCTION_FLOOR = 768

        /** Candidate floors below production; each is A-B'd against [PRODUCTION_FLOOR]. */
        val FLOOR_CANDIDATES = listOf(512, 384, 256)

        /**
         * Tail-fragment-sized slices: every one keeps neededFrames = samples/320 + 64 UNDER 768
         * (8 s ~= 464 frames), so the floor is the binding term being measured. 15 s would escape
         * the floor entirely and measure nothing.
         */
        val FLOOR_SLICE_SECONDS = listOf(1, 2, 3, 8)
    }
```

- [ ] **Edit 3 (the sweep)** — anchor on the unique OLD text (`:79-82`, the end of the existing test method):
```kotlin
        for (model in installed) {
            benchTier(model.id, File(modelsDir, model.fileName).absolutePath, pcm)
        }
    }
```
Replace with:
```kotlin
        for (model in installed) {
            benchTier(model.id, File(modelsDir, model.fileName).absolutePath, pcm)
        }
    }

    /**
     * Workstream G (3.6.0): A-B the production audio_ctx floor against lower candidates, per
     * installed tier, with accuracy scoring. The stop-tail fragment pays a 768-frame minimum for
     * ~119 frames of audio; the floor was raised 256 -> 768 for a REAL accuracy regression, so
     * "no candidate qualifies" is a fully expected, shippable answer.
     *
     * MEASUREMENT, not a gate: the only assertions are that loads succeed and the reference arm
     * transcribes non-blank. Accuracy = WerMath.wer(same tier, same slice, reference floor vs
     * candidate floor); the per-floor verdict line applies the SAME WerMath.floorQualifies rule
     * Task G4 is gated on. The owner records the lines in
     * docs/superpowers/specs/2026-08-19-audio-ctx-floor-bench.md.
     */
    @Test
    fun bench_audio_ctx_floor_ab() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        val modelsDir = File(context.filesDir, "models")
        val installed = WhisperCatalog.entries.filter { model ->
            val f = File(modelsDir, model.fileName)
            f.exists() && WhisperCatalog.sizeWithinTolerance(f.length(), model.approxBytes)
        }
        assumeTrue(
            "No installed whisper model found under ${modelsDir.absolutePath}; " +
                "download one in-app first, then rerun this test",
            installed.isNotEmpty(),
        )
        val wavBytes = inst.context.assets.open("jfk.wav").use { it.readBytes() }
        val pcm = wavDataChunk(wavBytes)
        assertTrue("jfk.wav decoded to no PCM samples", pcm.isNotEmpty())

        for (model in installed) {
            benchFloorsForTier(model.id, File(modelsDir, model.fileName).absolutePath, pcm)
        }
    }

    private fun benchFloorsForTier(tier: String, modelPath: String, pcm: ByteArray) {
        val ctx = WhisperNativeBackend.load(modelPath)
        assertNotEquals("model load returned 0 (failed) for tier=$tier at $modelPath", 0L, ctx)
        try {
            // Reference arm FIRST, at the production floor: its text is the "A" of every A-B.
            val refTexts = HashMap<Int, String>()
            com.whispereverywhere.whisper.WhisperNative.setAudioCtxFloor(PRODUCTION_FLOOR)
            for (seconds in FLOOR_SLICE_SECONDS) {
                val samples = AudioMath.pcm16ToFloat(tileToDuration(pcm, seconds, SAMPLE_RATE))
                val w0 = System.currentTimeMillis()
                val text = WhisperNativeBackend.transcribe(ctx, samples, "en")
                val wallMs = System.currentTimeMillis() - w0
                assertTrue(
                    "tier=$tier floor=$PRODUCTION_FLOOR slice=${seconds}s produced a blank " +
                        "reference — cannot score candidates against nothing",
                    text.isNotBlank(),
                )
                refTexts[seconds] = text
                android.util.Log.i(
                    TAG,
                    "BENCH audioctx tier=$tier floor=$PRODUCTION_FLOOR slice=${seconds}s " +
                        "wallMs=$wallMs wer=0.000 (reference)",
                )
            }
            for (floor in FLOOR_CANDIDATES) {
                com.whispereverywhere.whisper.WhisperNative.setAudioCtxFloor(floor)
                val wers = mutableListOf<Double>()
                for (seconds in FLOOR_SLICE_SECONDS) {
                    val samples = AudioMath.pcm16ToFloat(tileToDuration(pcm, seconds, SAMPLE_RATE))
                    val w0 = System.currentTimeMillis()
                    val text = WhisperNativeBackend.transcribe(ctx, samples, "en")
                    val wallMs = System.currentTimeMillis() - w0
                    val wer = WerMath.wer(refTexts.getValue(seconds), text)
                    wers += wer
                    // Locale.US: G4's gate and the decision record PARSE these numbers, and a
                    // comma-decimal device locale would emit wer=0,123 and break both.
                    android.util.Log.i(
                        TAG,
                        String.format(
                            java.util.Locale.US,
                            "BENCH audioctx tier=$tier floor=$floor slice=${seconds}s " +
                                "wallMs=$wallMs wer=%.3f",
                            wer,
                        ),
                    )
                }
                val qualifies = WerMath.floorQualifies(wers)
                android.util.Log.i(
                    TAG,
                    String.format(
                        java.util.Locale.US,
                        "BENCH audioctx tier=$tier floor=$floor " +
                            "verdict=${if (qualifies) "PASS" else "FAIL"} " +
                            "maxWer=%.3f gate=%.2f",
                        wers.maxOrNull() ?: 0.0,
                        WerMath.FLOOR_WER_GATE,
                    ),
                )
            }
        } finally {
            // NEVER leak a lowered floor: the override is process-global and the bubble service
            // lives in this same process.
            com.whispereverywhere.whisper.WhisperNative.setAudioCtxFloor(PRODUCTION_FLOOR)
            WhisperNativeBackend.release(ctx)
        }
    }
```

- [ ] **Create the decision record** — `docs/superpowers/specs/2026-08-19-audio-ctx-floor-bench.md` with exactly:

```markdown
# audio_ctx floor bench — recorded verdicts (3.6.0 Workstream G)

**Rule (executable: `WerMath.floorQualifies`, gate = 0.10):** a candidate floor QUALIFIES only if
EVERY `BENCH audioctx ... wer=` line for that floor, on EVERY benched tier, is <= 0.100 — the
bench prints a per-floor `verdict=PASS|FAIL` line applying exactly this rule.
The production floor (`whisper_jni.cpp` `g_audio_ctx_floor` default, 768) changes ONLY if a
candidate qualifies on BOTH 190 MB tiers (pro AND multi), and then to the LOWEST qualifying
candidate (Task G4). If nothing qualifies, 768 stands and this record is the ship artifact.

## How to run (owner device)

Build both APKs (never `:app:installDebug` / `:app:connectedDebugAndroidTest` — gradle install
tasks wipe app data and the downloaded models; `adb install -r` preserves them):

    $env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon
    adb install -r C:\Users\bastr\.androidbuild\WhisperEverywhere\app\outputs\apk\debug\app-debug.apk
    adb install -r C:\Users\bastr\.androidbuild\WhisperEverywhere\app\outputs\apk\androidTest\debug\app-debug-androidTest.apk
    adb shell am instrument -w -e class com.whispereverywhere.whisper.WhisperBenchTest#bench_audio_ctx_floor_ab com.whispereverywhere.test/androidx.test.runner.AndroidJUnitRunner
    adb logcat -d -s WE-BENCH | findstr "BENCH audioctx"

(adb is not on PATH: use `& "C:\Users\bastr\AppData\Local\Android\Sdk\platform-tools\adb.exe"`.)

Bench each 190 MB tier by having it installed in-app (the test benches whatever is on disk).
Do NOT dictate or run batch jobs while the bench runs: the floor override is process-global.

## Results

RESULT: PENDING

(paste every `BENCH audioctx` logcat line here, then fill the table)

| tier  | floor | maxWer | verdict (from the bench's own verdict line) |
|-------|-------|--------|---------------------------------------------|
| pro   | 512   |        |                                              |
| pro   | 384   |        |                                              |
| pro   | 256   |        |                                              |
| multi | 512   |        |                                              |
| multi | 384   |        |                                              |
| multi | 256   |        |                                              |

When filled, replace `RESULT: PENDING` with exactly one of:
- `RESULT: PASS floor=<512|384|256>` — the lowest floor with verdict=PASS on EVERY benched tier
- `RESULT: FAIL` — no candidate passed on every tier; 768 stands

## Decision

DECISION: PENDING (filled by Task G4)
```

- [ ] **Verify — compile both targets:**
```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon
```
Expected: `BUILD SUCCESSFUL` (this compiles the new instrumented test; the JVM suite does not cover androidTest).

- [ ] **Commit:**
```powershell
git add app/src/androidTest/java/com/whispereverywhere/whisper/WhisperBenchTest.kt docs/superpowers/specs/2026-08-19-audio-ctx-floor-bench.md
git commit -m "feat(bench): per-tier audio_ctx floor A-B sweep with WER scoring + decision record (G3)" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`nClaude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT"
```

---

### Task C8: GPU/CPU A-B in `WhisperBenchTest` (spec item C5's acceptance harness) — **INSTRUMENTED**

**Prerequisites — this is why C8 executes here, out of C's block:** it uses `GpuCanaryPolicy` (C1), the committed canary asset (C4), `WerMath` (G1), and **G3's edits to this same file** — the `WerMath` import G3 adds is reused here, so do NOT re-add it.

**Builds on:** G3 (same file). G3 inserted `benchFloorsForTier` immediately after the first test method, well ABOVE the `wavDataChunk` KDoc anchored here; that anchor is unchanged and still unique. Also builds on D1: `WhisperNative.transcribe`'s new `onNewSegment` parameter is defaulted, so the five-argument calls below still compile.

**Why:** spec Workstream C item 5: the bench harness extended to run per-tier GPU/CPU A-B so the owner's Fold 6 numbers (RTF + accuracy) decide the future GPU default. Both arms are FORCED via direct `WhisperNative.init(path, useGpu)` — this measures the hardware, not the gate — with every native call wrapped in `NativeComputeGate.serialized` because the instrument shares the app process with the bubble service. The CPU arm's canary transcription is asserted to pass `GpuCanaryPolicy.canaryPasses` — the end-to-end on-device proof that the C4 clip + C1 match rule are sound before they gate anything.

**Files:**
- Edit: `app/src/androidTest/java/com/whispereverywhere/whisper/WhisperBenchTest.kt`

**Steps:**

- [ ] **Edit 1 (imports)** — anchor on the unique OLD text (`:5`):
```kotlin
import com.whispereverywhere.model.WhisperCatalog
```
Replace with:
```kotlin
import com.whispereverywhere.model.WhisperCatalog
import com.whispereverywhere.transcription.GpuCanaryPolicy
import com.whispereverywhere.transcription.NativeComputeGate
```

- [ ] **Edit 2 (the A-B test)** — anchor on the unique OLD text (the KDoc opening above `wavDataChunk`, `:133-135`; insert BEFORE it — this anchor is untouched by G3's insertions). **Note the third line runs on past "header." — the full line must be matched:**
```kotlin
    /**
     * Locates the "data" chunk of a standard WAV file and returns its raw bytes, without
     * assuming a fixed 44-byte header. Mirrors WhisperNativeSmokeTest.wavToFloat's chunk walk
```
Replace with:
```kotlin
    /**
     * Workstream C (3.6.0): per-tier GPU/CPU A-B for the owner's decision gate ("GPU default for
     * multi flips only on canary + bench + owner-device evidence"). Bypasses GpuPolicy
     * DELIBERATELY — both arms are forced via WhisperNative.init(useGpu) — because this measures
     * the hardware, not the gate; no GpuPolicy sentinel is armed, and a GPU-arm crash kills only
     * this instrument run. Every native call holds NativeComputeGate: the instrument shares the
     * app process with the bubble/batch services.
     *
     * NOTE: on devices without OpenCL, ggml silently falls back to CPU inside the "GPU" arm —
     * the two arms then measure the same backend (visible in the log via matching loadMs/rtf).
     *
     * One assertion beyond load success: the CPU arm must pass GpuCanaryPolicy.canaryPasses on
     * the bundled clip — CPU is the trusted ground truth, so a failure there means the clip or
     * the match rule is broken, and the production canary gate would be meaningless.
     */
    @Test
    fun bench_gpu_vs_cpu_ab() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        val modelsDir = File(context.filesDir, "models")
        val installed = WhisperCatalog.entries.filter { model ->
            val f = File(modelsDir, model.fileName)
            f.exists() && WhisperCatalog.sizeWithinTolerance(f.length(), model.approxBytes)
        }
        assumeTrue(
            "No installed whisper model found under ${modelsDir.absolutePath}; " +
                "download one in-app first, then rerun this test",
            installed.isNotEmpty(),
        )

        val jfk = wavDataChunk(inst.context.assets.open("jfk.wav").use { it.readBytes() })
        assertTrue("jfk.wav decoded to no PCM samples", jfk.isNotEmpty())
        // The PRODUCTION canary clip (main assets, Workstream C) — targetContext, not inst.context.
        // SKIP rather than error when it is absent: the clip is an owner-supplied binary (C4) and
        // assets.open would throw a bare FileNotFoundException outside any assumption, turning
        // "the owner has not delivered the clip yet" into a red bench. Same assumeTrue idiom the
        // installed-model check above uses.
        val canaryBytes = runCatching {
            context.assets.open("canary_digits.wav").use { it.readBytes() }
        }.getOrNull()
        assumeTrue("canary_digits.wav not bundled", canaryBytes != null)
        val canaryPcm = wavDataChunk(canaryBytes!!)
        assertTrue("canary_digits.wav decoded to no PCM samples", canaryPcm.isNotEmpty())

        // One production-seam load/release first so ggml backends are registered exactly the way
        // production registers them (ensureBackendsLoaded); the A-B arms then force init() flags.
        run {
            val warm = WhisperNativeBackend.load(
                File(modelsDir, installed.first().fileName).absolutePath,
            )
            assertNotEquals("backend warm load failed", 0L, warm)
            WhisperNativeBackend.release(warm)
        }

        for (model in installed) {
            val path = File(modelsDir, model.fileName).absolutePath
            val cpu = benchArm(model.id, path, useGpu = false, jfk = jfk, canaryPcm = canaryPcm)
            val gpu = benchArm(model.id, path, useGpu = true, jfk = jfk, canaryPcm = canaryPcm)
            val wer = WerMath.wer(cpu.sliceText, gpu.sliceText)
            // Locale.US: these numbers are read back off logcat and compared, and a
            // comma-decimal device locale would emit gpuVsCpuWer=0,123.
            android.util.Log.i(
                TAG,
                String.format(
                    java.util.Locale.US,
                    "BENCH gpu-ab tier=${model.id} gpuVsCpuWer=%.3f canaryCpu=${cpu.canaryPassed} " +
                        "canaryGpu=${gpu.canaryPassed} cpuRtf8s=%.3f gpuRtf8s=%.3f",
                    wer, cpu.rtf8s, gpu.rtf8s,
                ),
            )
            // The CPU arm's canary is a GATE, not a measurement, and that is deliberate: CPU is
            // the trusted ground truth (the empirical ban is about the GPU only), and with the
            // alias-tolerant match rule and the verified bundled clip in place, a CPU-arm canary
            // failure means the HARNESS is broken — wrong clip, wrong asset, wrong rule. Every
            // number this bench prints would then be meaningless, and the production canary gate
            // it validates would be meaningless too. Failing loudly here is the correct outcome.
            assertTrue(
                "tier=${model.id}: CPU arm failed the canary — clip or match rule is broken",
                cpu.canaryPassed,
            )
            // The GPU arm is a MEASUREMENT: pass and fail are both valid, recordable results.
        }
    }

    private class ArmResult(val sliceText: String, val canaryPassed: Boolean, val rtf8s: Double)

    private fun benchArm(
        tier: String,
        modelPath: String,
        useGpu: Boolean,
        jfk: ByteArray,
        canaryPcm: ByteArray,
    ): ArmResult {
        val arm = if (useGpu) "gpu" else "cpu"
        val t0 = System.currentTimeMillis()
        val ctx = NativeComputeGate.serialized {
            com.whispereverywhere.whisper.WhisperNative.init(modelPath, useGpu)
        }
        val loadMs = System.currentTimeMillis() - t0
        assertNotEquals("init(useGpu=$useGpu) failed for tier=$tier", 0L, ctx)
        android.util.Log.i(TAG, "BENCH gpu-ab tier=$tier arm=$arm loadMs=$loadMs")
        try {
            var rtf8s = 0.0
            var text8s = ""
            for (seconds in listOf(3, 8)) {
                val samples = AudioMath.pcm16ToFloat(tileToDuration(jfk, seconds, SAMPLE_RATE))
                val audioMs = samples.size.toLong() * 1000L / SAMPLE_RATE
                val w0 = System.currentTimeMillis()
                val text = NativeComputeGate.serialized {
                    com.whispereverywhere.whisper.WhisperNative.transcribe(
                        ctx, samples, "en", translate = false, vadModelPath = null,
                    )
                }
                val wallMs = System.currentTimeMillis() - w0
                val rtf = wallMs.toDouble() / audioMs
                android.util.Log.i(
                    TAG,
                    String.format(
                        java.util.Locale.US,
                        "BENCH gpu-ab tier=$tier arm=$arm slice=${seconds}s wallMs=$wallMs rtf=%.3f",
                        rtf,
                    ),
                )
                if (seconds == 8) { rtf8s = rtf; text8s = text }
            }
            val canaryText = NativeComputeGate.serialized {
                com.whispereverywhere.whisper.WhisperNative.transcribe(
                    ctx, AudioMath.pcm16ToFloat(canaryPcm), "en",
                    translate = false, vadModelPath = null,
                )
            }
            return ArmResult(text8s, GpuCanaryPolicy.canaryPasses(canaryText), rtf8s)
        } finally {
            NativeComputeGate.serialized { com.whispereverywhere.whisper.WhisperNative.free(ctx) }
        }
    }

    /**
     * Locates the "data" chunk of a standard WAV file and returns its raw bytes, without
     * assuming a fixed 44-byte header. Mirrors WhisperNativeSmokeTest.wavToFloat's chunk walk
```

- [ ] **Edit 3 (clamp the data-chunk read)** — `benchArm` now feeds an owner-supplied WAV through `wavDataChunk`, whose copy is unclamped: a clip whose data-chunk header OVERSTATES its payload (some encoders do, and C4 Step 2 checks the format header but not the data size) throws `IndexOutOfBoundsException` and takes the whole bench down. `CanaryAudio.dataChunk` (C4) already clamps with `minOf`; mirror it. Anchor on the unique OLD text (inside `wavDataChunk`, `:144-146`):
```kotlin
            if (id == "data") {
                return bytes.copyOfRange(i + 8, i + 8 + chunkSize)
            }
```
Replace with:
```kotlin
            if (id == "data") {
                // Clamped like CanaryAudio.dataChunk: an over-declared data size (real encoders
                // emit them) must truncate, not throw — jfk.wav is ours, but the canary clip is
                // an owner-supplied binary.
                return bytes.copyOfRange(i + 8, minOf(i + 8 + chunkSize, bytes.size))
            }
```

- [ ] **Verify — compile both targets:**
```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Record the owner run protocol** (H2 acceptance; NOT run now, and never via gradle install/connected tasks):
```
adb install -r C:\Users\bastr\.androidbuild\WhisperEverywhere\app\outputs\apk\debug\app-debug.apk
adb install -r C:\Users\bastr\.androidbuild\WhisperEverywhere\app\outputs\apk\androidTest\debug\app-debug-androidTest.apk
adb shell am instrument -w -e class com.whispereverywhere.whisper.WhisperBenchTest#bench_gpu_vs_cpu_ab com.whispereverywhere.test/androidx.test.runner.AndroidJUnitRunner
adb logcat -d -s WE-BENCH | findstr "gpu-ab"
```
Read-out: `canaryGpu=true` with `gpuVsCpuWer` near 0 and a lower `gpuRtf8s` is the evidence the GPU-default decision gate (spec Decision Gate 2) wants; `canaryGpu=false` reproduces the documented corruption and closes the question for that model+driver — both are results.

- [ ] **Commit:**
```powershell
git add app/src/androidTest/java/com/whispereverywhere/whisper/WhisperBenchTest.kt
git commit -m "feat(bench): forced GPU/CPU A-B per tier with canary + WER scoring for the GPU decision gate (C8)" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`nClaude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT"
```

---

### Task G4: GATED production floor change — executes only on a recorded PASS

**Prerequisites:** G3 shipped AND the owner has run the bench on-device AND filled `docs/superpowers/specs/2026-08-19-audio-ctx-floor-bench.md`. **This task is expected to be a no-op at plan-execution time** — that is by design, not a failure.

**Builds on:** G2 (`g_audio_ctx_floor`), G3 (`PRODUCTION_FLOOR`, the record file). Both anchors are single lines that no other task touches.

**The gate, executable — follow it literally:**

- [ ] **Step 1 — read the verdict.** Run:
```powershell
Select-String -Path "docs/superpowers/specs/2026-08-19-audio-ctx-floor-bench.md" -Pattern "^RESULT:"
```
- If the file is missing or the line is `RESULT: PENDING` → **STOP. This task ships NOTHING.** Do not edit any file, do not commit. Record in the plan margin that G4 is awaiting owner data.
- If `RESULT: FAIL` → go to Step 2 only.
- If `RESULT: PASS floor=<F>` where `<F>` is one of 512/384/256 → verify the table shows `PASS` for `<F>` on BOTH `pro` and `multi` rows (if either row is blank or FAIL, treat as `RESULT: FAIL` — the recorded RESULT line was filled wrong, note that in the Decision section). Then go to Step 3.

- [ ] **Step 2 (FAIL path) — record and stop.** In the record file, replace the line `DECISION: PENDING (filled by Task G4)` with `DECISION: 768 stands — no candidate floor held wer <= 0.10 on every tier (bench evidence above).` Commit:
```powershell
git add docs/superpowers/specs/2026-08-19-audio-ctx-floor-bench.md
git commit -m "docs: audio_ctx floor bench verdict recorded - 768 stands, no floor change (G4)" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`nClaude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT"
```
**Task ends here on the FAIL path.**

- [ ] **Step 3 (PASS path) — apply the floor, `<F>` taken verbatim from the RESULT line** (the ONLY data-dependent token in this task; every edit below is otherwise exact). Three edits:

1. `app/src/main/cpp/whisper_jni.cpp` — OLD (unique, added by G2):
```cpp
static std::atomic<int> g_audio_ctx_floor{768};
```
NEW (substitute `<F>`):
```cpp
// Lowered 768 -> <F> per the recorded per-tier bench verdict (wer <= 0.10 on pro AND multi,
// docs/superpowers/specs/2026-08-19-audio-ctx-floor-bench.md). Raise back to 768 on ANY
// owner-reported garbled-short-phrase regression — accuracy outranks the latency win.
static std::atomic<int> g_audio_ctx_floor{<F>};
```

2. `app/src/androidTest/java/com/whispereverywhere/whisper/WhisperBenchTest.kt` — OLD (unique, added by G3):
```kotlin
        const val PRODUCTION_FLOOR = 768
```
NEW (substitute `<F>`):
```kotlin
        const val PRODUCTION_FLOOR = <F>
```

3. Record file — replace `DECISION: PENDING (filled by Task G4)` with `DECISION: production floor lowered 768 -> <F> per the PASS verdict above (whisper_jni.cpp g_audio_ctx_floor + bench PRODUCTION_FLOOR updated in the same commit).`

- [ ] **Step 4 (PASS path) — verify:**
```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon
```
Expected: `BUILD SUCCESSFUL` for all. Then the owner re-runs the G3 bench once more at the new default (the reference arm now IS the new production floor) as the post-change spot check, plus one real dictation session with a short stop-tail listening for garbling.

- [ ] **Step 5 (PASS path) — commit:**
```powershell
git add app/src/main/cpp/whisper_jni.cpp app/src/androidTest/java/com/whispereverywhere/whisper/WhisperBenchTest.kt docs/superpowers/specs/2026-08-19-audio-ctx-floor-bench.md
git commit -m "perf(jni): lower audio_ctx floor 768 -> <F> per recorded per-tier bench PASS (G4)" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`nClaude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT"
```

---

## Workstream H — Ship mechanics + acceptance

### Task H1: Release identity — 3.6.0 notes + versionCode 77

**Files:**
- Modify: `docs/PLAY-LISTING.md` (append a new release-notes section after the 3.5.0 one)
- Modify: `app/build.gradle.kts` (the `versionCode`/`versionName` pair inside `defaultConfig`, `:40-41`)

**Builds on:** every prior workstream complete and green on the branch. Neither file is touched by any other task.

**Interfaces:**
- Produces: the release identity for the 3.6.0 AAB (owner builds/signs/submits via the established Play flow — out of plan scope). This file and the Play Console are TWO copies of the same copy: the Console gets this text in the SAME release as the 77 AAB.

- [ ] **Step 1: Append the 3.6.0 release notes to `docs/PLAY-LISTING.md`.** Edit with OLD (the current end of the file):
```markdown
(Verify ≤500 chars in Step 2. Claim rules: no cloud speed claims — "finishes faster" refers to our
own fix vs our own previous version, which is factual and allowed. CONTINGENCY: that clause is
backed by the D2 before/after timings, which run AFTER this commit. If the before-build logs
falsify the C3 conviction — `local-drain` ≈ 0 everywhere — edit the notes BEFORE Play submission:
replace "Also: ending a session now finishes faster, and the app says what it's waiting on while
it wraps up." with "Also: the app now says what it's waiting on while a session wraps up." and
re-verify the count.)
```
NEW (the OLD block, then):
```markdown
## Release notes — 3.6.0 (within 500 chars)

> Our biggest on-device speed release. Words now appear in the bubble while the model is still transcribing — no more silent wait. The first line of a session lands sooner, multilingual mode no longer re-detects your language every segment, and switching models warms the new one in the background. Stopping now counts up while it finishes. Every claim is against our own previous version, measured on the same phone.

(**415 chars** — re-verify in Step 2. Claim rules per the 3.6.0 spec: NO cloud speed claims and no
absolute numbers — every comparative is our-own-before/after, said outright in the closing
sentence. "Words now appear while the model is still transcribing" is the factual streaming
mention (Workstream D, preview-only; it does NOT claim faster completion). The GPU canary ships
OFF by default and is deliberately absent.

**The stopping sentence claims the TICKER, not speed, and carries no contingency.** 3.6.0 makes no
stop faster: A2's shorter first cap only shrinks the never-transcribed tail on SHORT local
sessions, and Workstream F deliberately WIDENS the finalize bound (F4: worst case
`FINALIZE_TIMEOUT_MS` + 60 s). "Ending a session now finishes faster" was also already shipped in
the 3.5.0 notes, so repeating it here would re-sell a previous release's win. What is new and
unconditionally true is E6: the FINALIZING pill counts up while the drain runs. That is all this
sentence says, and it needs no before/after data to back it.

CONTINGENCY: if Workstream D (streaming deltas) is cut before ship, delete the second sentence; if
Workstream A (first-segment cap) is cut, delete "The first line of a session lands sooner, " and
capitalize "multilingual"; re-verify the count either way BEFORE Play submission.)
```

- [ ] **Step 2: Verify the character count.**
```powershell
$s = "Our biggest on-device speed release. Words now appear in the bubble while the model is still transcribing — no more silent wait. The first line of a session lands sooner, multilingual mode no longer re-detects your language every segment, and switching models warms the new one in the background. Stopping now counts up while it finishes. Every claim is against our own previous version, measured on the same phone."
$s.Length
```
Expected: **415** (must be ≤ 500). If it reads anything else, the pasted text drifted from the draft above — fix the notes, not this command.

- [ ] **Step 3: Bump the version.** In `app/build.gradle.kts`, replace OLD (exact current lines):
```kotlin
        versionCode = 76
        versionName = "3.5.0"  // pick-your-model onboarding + cloud-key education; finalize no longer over-waits at stop
```
with NEW:
```kotlin
        versionCode = 77
        versionName = "3.6.0"  // local speed: streaming deltas, first-segment cap, session language pin, drain floor
```

- [ ] **Step 4: Compile and run the full suite.**
```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:assembleDebug --no-daemon
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon
```
Expected: `BUILD SUCCESSFUL` twice, no failed tests. (Never `:app:installDebug` / `:app:connectedDebugAndroidTest`.)

- [ ] **Step 5: Commit.**
```powershell
git add docs/PLAY-LISTING.md app/build.gradle.kts
git commit -m "chore(release): 3.6.0 / versionCode 77 - local speed release identity (H1)" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`nClaude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT"
```

---

### Task H2: Owner on-device acceptance (owner-run — NEVER installDebug)

No implementer dispatch; the controller surfaces this to the owner when the branch merges. All adb commands use `& "C:\Users\bastr\AppData\Local\Android\Sdk\platform-tools\adb.exe"` (adb is not on PATH). NEVER `:app:installDebug` or `:app:connectedDebugAndroidTest` — both uninstall first and wipe the downloaded models.

**Builds on:** every task above, merged and green.

**Builds.** BEFORE = the installed 3.5.0 (versionCode 76). AFTER = the 3.6.0 branch build. Archive the before-build first so it stays reinstallable:

```powershell
$adb = "C:\Users\bastr\AppData\Local\Android\Sdk\platform-tools\adb.exe"
& $adb shell pm path com.whispereverywhere   # note the /data/app/.../base.apk path it prints
& $adb pull <that path> C:\Users\bastr\.androidbuild\WhisperEverywhere\whisper-3.5.0-BEFORE-360.apk
```

Run the ENTIRE before grid below on the installed 3.5.0 FIRST. Then build and install the after-build (data-preserving):

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:assembleDebug --no-daemon
& $adb install -r C:\Users\bastr\.androidbuild\WhisperEverywhere\app\outputs\apk\debug\app-debug.apk
```

**The grid — 2 tiers × 3 modes × before/after = 12 sessions.** Per tier (pro, then multi — switch in Settings) and per mode (local, cloud batch, cloud live): dictate the same ~3 sentences of CONTINUOUS speech (read a paragraph aloud without pausing — the wall-cap path is the one under test), then stop. Between sessions: `& $adb logcat -c`. After each session, capture and SAVE both grep outputs labeled tier/mode/build:

```powershell
& $adb logcat -d | Select-String "WE-DIAG" | Select-String "finalize-timing"
& $adb logcat -d | Select-String "WE-DIAG" | Select-String "segment-timing"
```

- [ ] **First visible text under continuous speech (the headline gate) — LOCAL MODE:** on the AFTER build, multi shows its first words in the bubble preview ≤ ~6 s from record start (BEFORE ≈ 17 s — record the actual before number too). Pro should be comfortably under that. Measure by screen recording or stopwatch from the tap to the first words rendered. The target is stated for LOCAL sessions only: the 4 s first cap is gated on `cloudWrapper == null` (A2), so cloud batch/live sessions keep 3.5.0's 15 s cap for every segment on purpose — no extra billable round-trip. In the cloud rows of the grid, check the opposite thing: the first `wall-clock cap -> commit` line must read `(cap=15000ms)`.
- [ ] **`segment-timing` RTF captured (the decision-gate data):** every local segment on the AFTER build logs `segment-timing: audio=<ms> transcribe=<ms> rtf=<x.xx>`. Save the rtf values per tier (CPU). These are the repo's first measured 190 MB-tier RTFs — report them to the controller verbatim; they feed the tier-consolidation and GPU-default decision gates.
- [ ] **Streaming deltas visual check:** during a long local segment (multi, continuous speech), words appear progressively in the bubble preview WHILE the model is transcribing. The external field still receives the text ONCE, complete, at stop — nothing typed mid-session, history unchanged (final-only commit intact). **Through the stop, the FINALIZING status line must stay visible and un-clobbered:** tap stop mid-utterance and confirm the strip keeps reading "Finishing transcript…" / "Finishing… (waiting on provider)" for the whole drain — never replaced by preview words and never blanked — which is what D4's Edit 3i gate buys and what E6's ticker counts up beside. Known and accepted cosmetic: during continuous dictation the strip hides and re-shows once at each segment boundary (the terminal blank delta), a one-frame flicker plus one reclamp per segment — report it only if it is worse than that.
- [ ] **Language pin:** multi + language auto → `WE-DIAG language-pin: detected=<code>` appears once per session on the first speech segment; later `transcribe START` lines carry `effective=<code>`. Compare the per-segment `segment-timing` transcribe numbers against the BEFORE build for the same tier.
- [ ] **Warm switch:** with the bubble running, switch tiers in Settings → `selectedModelId -> <id>: re-prewarming engine` then `prewarmModelSwitch: ctx loaded`; the next session's CONNECTING is instant.
- [ ] **Cold-load label:** force a cold start → the bubble reads "Loading speech model…" until recording begins; warm and cloud sessions show the bare spinner.
- [ ] **Stop cost:** warm local stop's `finalize-timing` total ≈ the tail inference only (no ~7 s load, no dead wait); the FINALIZING pill counts up during long drains. LIVE stop logs a small nonzero `local-drain` instead of the skip line — the tail-turn rescue, expected by design, not a failure. Batch-cloud happy path still logs `local-drain=0ms (skipped: no outstanding retries)`. **Reading a `drain timed out` warning correctly (F3):** every stop now also logs `finalize-timing: cloud-budget=<n>ms (reserve=<r>ms)`. A `finalize: drain timed out` warning paired with a cloud-budget SMALLER than the full budget is a CAPPED stop — cloud's share was shortened so an owed local rescue kept its reserve, and every segment still delivered; only a timed-out warning next to the FULL budget is a real cloud timeout. Check the delivered text before reporting either as a failure.
- [ ] **Drain floor spot check (Workstream F):** one batch-cloud session on the AFTER build, toggling airplane mode ON mid-session, then stop. Every spoken segment still delivers (rescued on-device, no "[…]" marker for real speech), `finalize-timing` shows a nonzero `local-drain`, and the stop completes within FINALIZE_TIMEOUT_MS + 60 s.
- [ ] **GPU canary (Workstream C — ships OFF by default):** Settings → enable "Try GPU for multilingual (experimental)" → force a cold load (switch away from multi and back, or restart the service) → grep WE-DIAG for `gpu-canary: passed=` and the following `GpuPolicy: canary PASSED|FAILED` line. PASS: run two multi sessions and compare their `segment-timing` rtf against the CPU runs from the grid; report both. FAIL: confirm the session proceeded on CPU, the permanent CPU latch line logged, and transcripts are clean (no garbage tokens). Report the verdict either way — the default flip is a data-driven follow-up, not this release. Disable the toggle afterward unless keeping it.
- [ ] **Bench runs (optional but they are the G/C decision data):** run `bench_audio_ctx_floor_ab` (G3) and `bench_gpu_vs_cpu_ab` (C8) via the `am instrument` protocol in each task, and fill `docs/superpowers/specs/2026-08-19-audio-ctx-floor-bench.md`. Filling it is what unblocks G4.
- [ ] **Regression spot-checks:** dictation into WhatsApp delivers once at stop; Transcriptions history records sessions; resize handle works; the how-to guide reads unchanged (its pinned copy was not touched this release).
- [ ] **Report** both builds' saved grep outputs and the before/after first-text numbers to the controller. If the AFTER multi first-text misses ~6 s, that is a finding for the decision gates, not a silent pass — the release notes' "lands sooner" sentence survives only if the before/after numbers actually back it (H1 contingency).

---

## Assembly flags — things a reviewer must resolve

These are recorded rather than silently dropped. Everything else in this plan was verified line-by-line against the tree at HEAD `97ec697`.

1. **Part 3 of 4 arrived truncated.** The source part carrying Workstreams C and G began mid-sentence inside `WerMath`'s `floorQualifies` KDoc. Consequences:
   - **C1-C7 were missing entirely** and are assembler-authored (see the note above Workstream C). C8, the only C task that survived, drove their contracts (`GpuCanaryPolicy.canaryPasses`, `canary_digits.wav`, `WerMath`).
   - **G1's test and the head of `WerMath`** were missing and are reconstructed around the surviving `floorQualifies` fragment (preserved verbatim) and the surviving "all 9 tests pass" expectation.
2. **The assembly brief states that Workstream C also touches `app/src/main/cpp/whisper_jni.cpp`.** No native C edit could be reconstructed from the surviving material, and the C design here needs none. If the original C1-C7 do include a native edit, it must be re-cross-checked against B1's (`detectedLanguage` above `_free`), D1's (trampoline above `transcribeRaw`; the `whisper_full` call) and G2's (`<atomic>` include; the block after `we_install_native_logging`; the `audio_ctx` floor block) edits, in that execution order.
3. **`canary_digits.wav` is an owner-supplied binary** (C4 Step 1). No agent can synthesize spoken-digit audio; C4 ships the loader, the format check and the tests, and blocks on the file. C5, C7 and C8 all depend on it. The block is now EXECUTABLE rather than implied: C4 Step 2 tests for the file first and prints a `STOP:` line when it is missing, C4's commit is split (loader+tests unconditionally in Step 7, the asset conditionally in Step 8), C5 records NO verdict when the asset is unreadable (a missing asset must never write the permanent CPU latch), and C8 skips via `assumeTrue` instead of erroring.
4. **Corrected before merging (would have failed as written):**
   - Part B-D's code fences were uniformly indented +2 spaces relative to the real files (markdown list nesting). Every OLD block is de-indented here to match the tree exactly.
   - **B4's Edit 3c** quoted the pre-A4 `backend.transcribe` block; A4 runs first and inserts `val transcribeStartNs = System.nanoTime()` into it. Rewritten against the post-A4 tree. **D4's Edit 3d** likewise now quotes the post-A4 + post-B4 text.
   - **C8's Edit 2** anchored on a truncated KDoc line (`* assuming a fixed 44-byte header.`); the real line continues `... Mirrors WhisperNativeSmokeTest.wavToFloat's chunk walk`. Anchor extended.
   - **D5's Check 4** listed `app/src/main/java/com/whispereverywhere/transcription/cloud/FallbackPolicy.kt`, which does not exist (`FallbackPolicy` is an object inside `FallbackTranscriptionEngine.kt`). `git diff --stat` on a nonexistent path returns empty, so the check would have passed vacuously. Replaced with real paths.
   - **APK paths:** G3's decision record and C8's owner protocol used `…\.androidbuild\WhisperEverywhere\outputs\apk\…`. The real path has an `app\` segment (root `build.gradle.kts:17-22` sets `buildDirectory` to `<localBuildRoot>/<project.name>`). Corrected everywhere.
   - **Test count:** A4 and E1 expected "17 pre-existing `LocalWhisperEngineTest` tests"; the file has **18**. Corrected.
   - B1 cited `whisper.cpp` internal line numbers from the vendored submodule; replaced with the public `whisper.h` declarations actually verified (`:367`, `:463`, `:562-563`, `:631`, `:634`, `:652`).
5. **Not independently verifiable from the tree, carried forward as the authors wrote them:**
   - `TtsEngine.preload`'s no-op-when-loaded semantics (E3 cites `TtsEngine.kt:149-158`) — the claim is plausible and E3 is comment-and-one-call wiring, but it was not re-read.
   - The claim that 14 existing `WhisperBackend` fakes exist across four test files (B2). The default-carrying design makes the exact count immaterial: the full-suite run in B2 Step 4 is the real check.
   - Every owner on-device expectation (first-text ≤ 6 s, RTF values, canary verdicts, bench numbers) — by definition unmeasurable here; that is what H2 exists for.
6. **Workstream C ships no default behavior change.** With `gpuMultilingualExperiment` false — the shipped default, re-checked in C7 — `decideUseGpuForLoad` returns false for multilingual models exactly as in 3.5.0 and the entire canary path is unreachable. Release notes (H1) deliberately do not mention it.

7. **Reviewed and deliberately KEPT** (adjudicated on the 2026-08-19 two-checker review; each was challenged, each stands, none is an oversight):
   - **C8's `assertTrue(cpu.canaryPassed)` stays a hard gate, not a measurement.** With the alias-tolerant match rule (C1) and the no-verdict-on-missing-asset rule (C5) in place, a CPU-arm failure means the harness itself is broken, which makes every number the bench prints meaningless — failing loudly is correct. Rationale recorded in C8's code comment.
   - **D4's terminal `onDelta("")` stays.** The strip must clear when a segment's running text is superseded by its committed text; the once-per-segment hide/show flicker is accepted cosmetic and is now named in H2's visual check. The FINALIZING clobber it used to cause is fixed at the SINK (D4 Edit 3i), not by removing the clear.
   - **E5's `transcriptionEditText.visibility = View.GONE` stays**, as does G2's settable-floor 64..1500 clamp — both are elaborations beyond the letter of the spec, both are correctly walled off (the label is a CONNECTING-only surface; the floor knob is bench-only and never called from production).
   - **E5's reliance on the next `showSessionPreview()` to restore `transcriptionEditText` stays.** It is self-healing on every path that matters (`onOpen` restores it, `teardownRealtime` brings the whole container down); a dedicated restore path would be dead code.



