# 3.7.0 Implementation Plan — Real VAD endpointing: utterance cadence on pro, paced cadence on multi

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace amplitude-threshold segmentation with the vendored streaming Silero VAD so segments cut when the user actually stops talking. Pro runs true per-utterance cadence (speech-end → visible text ≈ 1.3–1.8 s, constant); multi runs the same endpointing at a paced commit interval its measured cost supports. Stops on an idle queue become near-instant, the wall caps survive unchanged as backstops so every pathological case degrades to exactly today's behaviour, and the release ships the diagnostics that make all of it measurable.

**Architecture:** A dedicated native Silero context is driven one 512-sample frame at a time from the audio capture thread, outside `NativeComputeGate` — the only whisper calls in the process that bypass it, with the safety argument recorded at the surface itself. The commit decision moves behind a pure-Kotlin `Endpointer` interface plugged into the existing decision site (`FloatingBubbleService.onAudioChunk`), where the wall caps stay the `else if` they already are, so caps remain structural backstops rather than the normal path. Per-tier commit cadence comes from the measured fixed per-commit cost F (`CommitCadencePolicy`), and one greppable WE-DIAG family (`endpoint:` / `segment-timing: seq=` / `queue:` / `perceived:` / `probe:`) joins on `seq` so the mandate can be verified rather than asserted.

**Tech Stack:** Kotlin 2.0.21, AGP 8.13.2, JDK 21 (Android Studio JBR), JUnit4 JVM tests, whisper.cpp via CMake/NDK (`libwhisper_jni.so`, submodule `TechBran/whisper.cpp` @ `we/v1.9.1-android`), Jetpack Compose + Android Views (bubble), Kotlin coroutines/Flow. No new dependencies.

**Spec (authoritative on any ambiguity):** `docs/superpowers/specs/2026-08-20-vad-endpointing-design.md`

**Branch / baseline:** `feat/3.7-vad-endpointing`, branched from **current `main`** — 3.5.0 and 3.6.0 are already merged, so main is the vc77 tree with G4's `audio_ctx` floor at **512** (not 768; the 768 arithmetic in older documents is historical). Every line anchor in this plan was verified against that tree and shifts as tasks land — **anchor on the quoted text and the named symbol, never on the line number.**

---

## Global Constraints

Every task's requirements implicitly include this section.

### Execution order (binding)

**N1 → N2 → N3 → N4 → N5 → N6 → E1 → E2 → F1 → F2 → F3 → F4 → F5 → F6 → D1 → D2 → C1 → C2 → C3 → C4 → C5 → C6 → C7 → C8 → C9 → C10 → D3 → D4 → D5 → D6 → D7 → D8 → D9 → D10 → F7 → F8 → F9 → G1 → G2 → G3 → G4 → G5 → H1 → H2 → H3 → H4 → H5 → S1 → S2 → S3 → S4 → S5** (52 tasks).

Task IDs are letter-blocked by workstream, and the ID order IS the execution order. Four orderings are load-bearing and are not preferences:

- **E1/E2 land early, before D wires the probe in.** `StreamingAudioRecorder.stop()` joins before it stops the record; `vadProbeFree` blocks on the probe mutex and is reachable from Main. Size the window by the WIDEST holder, not the typical one: a frame is sub-millisecond, but `vadProbeInit` (N4) holds `g_probe_mutex` across `whisper_vad_init_from_file_with_params` — file I/O plus tensor allocation — so a stop landing during session startup blocks Main for a model load, not for a frame. Until the reorder lands, wiring the probe in creates an ANR vector.
- **Free-after-init is a binding constraint on the N6/D integration wiring, not a style choice.** The probe surface is idempotent but not order-free: if a stop wins the race against a still-running `vadProbeInit`, `vadProbeFree` takes the mutex first, finds `g_probe_ctx == nullptr`, returns having freed nothing, and the init that was blocked behind it then publishes a live context that nothing will ever release — leaked until the next `vadProbeInit` frees it, which may be never. The wiring must guarantee `vadProbeFree` runs *after* any in-flight `vadProbeInit` has published (E1/E2's join gives exactly this), or carry an explicit "init was cancelled" flag checked under the same mutex.
- **F1–F6 land before C**, so C can consume `ProbeStats` (C10) and so the `segment-timing: seq=` prepend is in place before anything joins on `seq`.
- **D1 and D2 land before C1.** `SileroEndpointer` declares `: Endpointer` from birth, so the interface has to exist first; D1 (the `SpeechSegmenter` collapse) precedes D2 so `AmplitudeEndpointer` wraps the final three-parameter shape and its parity test is meaningful. The rest of D (D3–D10) runs after C, because D8 constructs `SileroEndpointer` and D9 makes it the verdict.
- **F7–F9 land after D9/D10.** They edit the same five commit sites and the same two source-shape pin tests D just wrote; running them second means one re-anchor instead of two.

### Build / test commands (PowerShell, repo root — set JAVA_HOME on every invocation)

- Full JVM suite: `$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon`
- One class: append `--tests "com.whispereverywhere.<pkg>.<Class>"` (repeat `--tests` for several)
- Compile + native: `$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:assembleDebug --no-daemon`
- Instrumented compile (never run): `$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon`

### Output paths (outside the repo — root `build.gradle.kts:17-22` relocates `buildDirectory` to `<localBuildRoot>/<project.name>`)

- Debug APK: `C:\Users\bastr\.androidbuild\WhisperEverywhere\app\outputs\apk\debug\app-debug.apk`
- androidTest APK: `C:\Users\bastr\.androidbuild\WhisperEverywhere\app\outputs\apk\androidTest\debug\app-debug-androidTest.apk`
- JVM test results (XML): `C:\Users\bastr\.androidbuild\WhisperEverywhere\app\test-results\testDebugUnitTest`
- JVM test report: `C:\Users\bastr\.androidbuild\WhisperEverywhere\app\reports\tests\testDebugUnitTest\index.html`

### NEVER install via Gradle

**NEVER run `:app:installDebug` or `:app:connectedDebugAndroidTest`.** Both uninstall first and destroy the owner's 500+ MB of downloaded on-device models. Every device install is owner-run and data-preserving:

```
& "C:\Users\bastr\AppData\Local\Android\Sdk\platform-tools\adb.exe" install -r <apk>
```

adb is not on PATH; always call it by that absolute path.

### Untouchable contracts (binding, from the spec's Constraints section)

- Wall caps in the `else if` (byte-identical with a never-firing endpointer, pinned by test); cloud 4 s-cap suppression (`cap=4000ms` in cloud = regression); unconditional stop flush; unconditional-and-first `sendAudio`; `no_context = true` final-only commit; live bypass; `EmptyExpected`/reconcile semantics; `SegmentOrderer` release rules; disclosure texts; the 3.5.0 skip + delivery fence; drain reserve economics (the reserve is a bound, not a cost — do not "optimise it away" on the strength of the empty-tail win); `NativeComputeGate` wraps every whisper call (the probe alone bypasses, with the safety argument recorded in Workstream A).
- The batch `we_vad_filter` keeps its own 0.40/150 ms tuning — probe decides WHEN to cut, the filter decides WHAT reaches the encoder. Independent knobs.
- `segment-timing:` keeps its prefix and its `audio=… transcribe=… rtf=…` substring byte-identical: `seq=` is PREPENDED and the native counters are APPENDED.
- Never log transcript content. WE-DIAG lines carry lengths, language codes and numbers only.

### JVM test rules (TDD throughout)

- Write the failing test, run it, watch the named failure, then implement. Every task below states its expected red.
- Tests live under `app/src/test/java/com/whispereverywhere/...`. Compose screens and the two Services have no direct tests by house convention — extract the logic as a pure object/top-level function and pin THAT.
- **Concurrency-adjacent tests use REAL background executors** (`Executors.newSingleThreadExecutor()` / `newFixedThreadPool`), shut down in a `finally`. `SameThreadExecutorService` is for deterministic sequencing only and may not stand in for a thread the test exists to separate.
- Shared fakes (`SameThreadExecutorService`, `QueueingExecutorService`, `FakeWhisperBackend`, `RecordingListener`, `FakeModelPathProvider`) are top-level classes in `app/src/test/java/com/whispereverywhere/transcription/LocalWhisperEngineTest.kt` — reuse them from the same package, never redeclare.
- New `WhisperBackend` members must carry defaults so the existing fakes keep compiling untouched.

### Test evidence is XML aggregation (binding)

Evidence is the JUnit XML under `C:\Users\bastr\.androidbuild\WhisperEverywhere\app\test-results\testDebugUnitTest` — **never a Gradle task summary and never a green console line.**

```powershell
$dir = 'C:\Users\bastr\.androidbuild\WhisperEverywhere\app\test-results\testDebugUnitTest'
$files = @(Get-ChildItem $dir -Filter 'TEST-*.xml')
$t=0;$f=0;$e=0;$s=0
foreach ($x in $files) { $d = [xml][System.IO.File]::ReadAllText($x.FullName); $t += [int]$d.testsuite.tests; $f += [int]$d.testsuite.failures; $e += [int]$d.testsuite.errors; $s += [int]$d.testsuite.skipped }
"suites=$($files.Count) tests=$t failures=$f errors=$e skipped=$s"
```

**No task states an absolute suite total.** Per-task evidence is `failures=0 errors=0` plus that task's own class counts and its `+N` delta; the branch's absolute totals are computed exactly once, in S5, after a forced-fresh run against a purged results directory. (`[System.IO.File]::ReadAllText` rather than `Get-Content -Raw`: PS 5.1 reads BOM-less UTF-8 as ANSI and would mangle these files.)

### Native / instrumented verification rule

The JVM suite cannot exercise JNI (`WhisperNative` loads the native library) or `androidTest`. For any task marked **NATIVE** or **INSTRUMENTED**, verification is exactly:

1. **Compile:** `:app:assembleDebug` (builds `libwhisper_jni.so` via the `whisper_jni` CMake target) — and `:app:assembleDebugAndroidTest` for instrumented tests.
2. **The full JVM suite still green** (proves nothing else regressed).
3. **A named owner on-device check**, recorded in the S3 acceptance sheet — never claimed as done by the implementer.

Native and Android-only wiring still gets a red first: a source-contract JUnit test over the `.cpp`/`.kt` that pins the construct, or a genuinely new pure symbol the wiring needs, written test-first.

### Commit trailer (exact, every commit)

Every commit message ends with exactly these two lines:

```
Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT
```

Two forms are used below and both are correct: a PowerShell here-string (`git commit -m @'…'@`, the closing `'@` at column 0), or a second `-m` with a backtick-n newline (`-m "Co-Authored-By: …\`nClaude-Session: …"`).

### Claim rules (copy discipline, binding)

Our-own-before/after only, on the same device, and the copy says so. No absolutes, no cloud speed claims, no comparison to any other app. New user-facing strings are pinned by test in the task that introduces them.

### Shared contracts (load-bearing exact names — identical at every mention)

| Contract | Exact signature | Owner | Consumers |
|---|---|---|---|
| Native probe | `WhisperNative.vadProbeInit(modelPath: String): Boolean` · `vadProbeFrame(pcm: java.nio.ByteBuffer, nBytes: Int): Float` · `vadProbeReset()` · `vadProbeFree()`; `nBytes` exactly 1024, `-1.0f` = "no verdict", never "silence" | N6 | D4, S2 |
| Endpointer seam | `interface Endpointer` (package `com.whispereverywhere.audio`): `onFrame(chunk: ByteArray, amp: Int, nowMs: Long): Boolean`, `hasPendingSpeech(): Boolean`, `reset()`, `onSessionStart(nowMs: Long, minCommitIntervalMs: Long) {}`, `onSessionEnd() {}`, `pendingCutPointMs(): Long = NO_CUT_POINT`; companion `NO_CUT_POINT = 0L` | D2 | C2, D8, D9, D10, F8 |
| Endpointer implementations | `class AmplitudeEndpointer(segmenter: SpeechSegmenter = SpeechSegmenter()) : Endpointer` · `class SileroEndpointer(probe: (ByteArray) -> Float, probeReset: () -> Unit = {}, nanoClock: () -> Long = { System.nanoTime() }, probeStats: ProbeStats = …, probeArm: () -> Unit = {}, probeTeardown: () -> Unit = {}) : Endpointer` — both `com.whispereverywhere.audio` | D2, C2 | D8 |
| Endpointer tuning | `object EndpointerTuning` (`com.whispereverywhere.audio`): frame geometry, `ONSET_THRESHOLD`, `RELEASE_THRESHOLD`, `HANGOVER_MS`, `MIN_SPEECH_MS`, `MICRO_PAUSE_MS`, `PROBE_BUDGET_MS`, `PROBE_CUTOUT_FRAMES`, `NO_VERDICT`. **No commit-interval constants live here** | C1 | C2–C10, S2 |
| Cadence policy | `object CommitCadencePolicy` (`com.whispereverywhere.service`): `MIN_COMMIT_INTERVAL_FAST_MS = 1_200L`, `MULTI_MS = 6_000L`, `LARGE_MS = 8_000L`, `CLOUD_MS = 3_000L`, `CAP_CUT_MAX_RETAIN_MS = 3_000L`, `minCommitIntervalMs(tierId: String?, isCloudBatch: Boolean): Long`, `capCutRetainMs(nowMs: Long, cutPointMs: Long): Long` — the ONLY owner of commit intervals | D3 | D9, D10 |
| Cut record | `data class EndpointCut(val speechMs: Long, val trailMs: Long, val prob: Float)` (`com.whispereverywhere.audio`), produced by `SileroEndpointer.lastCut()` | C8 | F8, F9 |
| Diagnostic formats | `object EndpointDiag` (`com.whispereverywhere.service`): `endpointLine(seq: Long, cut: String, ec: EndpointCut?)`, `queueLine(depth: Int)`, `perceivedLine(seq: Long, speechEndToVisibleMs: Long)`, `capCommitLine(capMs: Long)`; companion `VAD`/`CAP`/`STOP`/`SWITCH` | F7, F8, F9 | S3, S5 |
| Queue depth | `class SegmentQueueDepth` (`com.whispereverywhere.service`): `onCommitted(seq: Long): Int`, `onResolved(seq: Long): Int`, `depth(): Int`, `reset()` — seq-SET based, ONE instance on the service | F7 | G1, G3, G4, G5 |
| Commit funnel | `private fun commitSegment(engine: TranscriptionEngine, cut: String, retainMs: Long = 0L, nowMs: Long = System.currentTimeMillis()): Long` — a private MEMBER function of `FloatingBubbleService` (NOT a top-level extension: it reads the service's private `segmentQueueDepth` / `perceivedLatency` / `endpointer` / `serviceScope`), the ONE funnel all five commit sites route through | F7 | F8, F9, G3 |
| Probe cost | `class ProbeStats(budgetUs: Long, emitIntervalMs: Long = EMIT_INTERVAL_MS)` (`com.whispereverywhere.util`): `record(elapsedUs: Long, nowMs: Long): Boolean`, `frames()`, `overruns()`, `percentileUs(q)`, `line()`, `reset()` — every one of them `@Synchronized` on the instance: `record()` is written from the capture thread while `reset()`/`line()` are called from Main | F1 | C10 |
| Timing line | `SegmentTiming.line(seq: Long, audioMs: Long, transcribeMs: Long, stats: NativeSegmentStats? = null): String` | F2, F5 | F6, S5 |

**Line prefixes are contiguous single string literals in source** (`endpoint: seq=`, `queue: depth=`, `perceived: `, `probe: frames=`, `segment-timing: seq=`), because S3 and S5 grep the source for them with `-SimpleMatch`. Never build one of these prefixes by concatenation. The `probe:` line's unit is `µs` (written as the `\u00B5` escape); no grep in this plan contains a `µ`.

## Workstream A/B — Native probe surface + fork hygiene (N)

---

### Task N1: B-prime — pin the existing batch VAD context to one thread

**Files:**
- Modify `C:/Users/bastr/OneDrive/Desktop/whisper Everywhere/app/src/main/cpp/whisper_jni.cpp` — inside `we_vad_filter`, between `:140` (`whisper_vad_context_params vcp = whisper_vad_default_context_params();`) and `:141` (`g_vad_ctx = whisper_vad_init_from_file_with_params(vadPath.c_str(), vcp);`)
- Create `C:/Users/bastr/OneDrive/Desktop/whisper Everywhere/app/src/test/java/com/whispereverywhere/whisper/NativeVadSourceContractTest.kt`

**Interfaces:**
- Consumes: `whisper_vad_default_context_params(void) -> whisper_vad_context_params` (whisper.h:688); `struct whisper_vad_context_params { int n_threads; bool use_gpu; int gpu_device; }` (whisper.h:682-686) — **the field is `n_threads`**; the initializer comment at `whisper.cpp:4445` reads `.n_thread` and does not compile. `whisper_vad_init_from_file_with_params(const char*, whisper_vad_context_params) -> whisper_vad_context*` (whisper.h:690).
- Produces: `NativeVadSourceContractTest` (JVM test class, package `com.whispereverywhere.whisper`) with private helpers `repoFile(relative: String): java.io.File`, `jni: String`, `weVadFilterBody(): String` — Tasks N2-N5 add `@Test` methods and helpers to THIS class.

**Why this is not a JVM-TDD task in the usual sense (explicit):** `whisper_jni.cpp` has no JVM-side behavior — there is no `libwhisper_jni.so` on the unit-test classpath, and `:app:assembleDebug` (CMake) is the only thing that compiles it. So the failing test here is a **source-contract test**: it reads the C++ file and pins the construct. That is a real, runnable, fails-first JUnit test, and it is the only mechanism that makes this constraint visible to the suite instead of free to regress silently. Assertions are anchored to **content, never line numbers** — the spec's own anchors had already drifted (see Task N2).

- [ ] **Step 1: Write the failing test.** Create `app/src/test/java/com/whispereverywhere/whisper/NativeVadSourceContractTest.kt`:

```kotlin
package com.whispereverywhere.whisper

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-level contract guards for the native VAD surface (whisper_jni.cpp and the vendored
 * whisper.cpp fork).
 *
 * WHY A TEST THAT READS C++: neither file has any JVM-side behavior — there is no
 * libwhisper_jni.so on the unit-test classpath, and `:app:assembleDebug` is the only gate that
 * touches them. That leaves several binding 3.7 constraints (n_threads = 1 on every VAD context,
 * the -1.0f short-frame sentinel, the probe's dedicated context, the recorded
 * NativeComputeGate-bypass argument) invisible to the test suite and free to regress silently.
 * These assertions pin the constructs themselves, so a refactor that drops one fails here in
 * seconds instead of on-device in a month.
 *
 * Every assertion is anchored to CONTENT, never to a line number: the 3.7 spec's own line anchors
 * had already drifted by the time it was written.
 */
class NativeVadSourceContractTest {

    private fun repoFile(relative: String): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            File(dir, relative).let { if (it.isFile) return it }
            File(dir, "app/$relative").let { if (it.isFile) return it }
            dir = dir.parentFile
        }
        throw AssertionError(
            "could not locate $relative from ${System.getProperty("user.dir")} " +
                "(if this is the vendored fork, run: git submodule update --init --recursive)"
        )
    }

    private val jni: String by lazy { repoFile("src/main/cpp/whisper_jni.cpp").readText() }

    /** we_vad_filter's body: the only column-0 `}` in that function is its closing brace. */
    private fun weVadFilterBody(): String =
        jni.substringAfter("static bool we_vad_filter(").substringBefore("\n}\n")

    @Test
    fun batchVadContext_pinsOneThread_soTheShippedFilterStopsForkingPthreadsPerChunk() {
        val body = weVadFilterBody()
        assertTrue(
            "we_vad_filter must set vcp.n_threads = 1 before creating the batch VAD context. " +
                "ggml_backend_cpu_set_threadpool is never called for a VAD context, so " +
                "ggml_graph_compute takes the disposable-threadpool path and spawns + joins " +
                "n_threads-1 real pthreads PER GRAPH COMPUTE (ggml-cpu.c:3320-3325, joined at " +
                ":3379) — and that " +
                "compute sits inside the per-window frame loop (whisper.cpp:5170). At the default " +
                "4 that is 375 create/join cycles per 4 s chunk and 1,407 per 15 s chunk, today, " +
                "on shipped behavior, for a ~74-node graph with a barrier between every node.",
            body.contains("vcp.n_threads = 1;")
        )
        assertTrue(
            "vcp.n_threads = 1 must be set BEFORE the init call it parameterises",
            body.indexOf("vcp.n_threads = 1;") <
                body.indexOf("whisper_vad_init_from_file_with_params")
        )
        assertTrue(
            "the field is n_threads (whisper.h:683). `.n_thread` is the initializer COMMENT at " +
                "whisper.cpp:4445 and will not compile — this guard exists because that comment " +
                "misled an earlier investigation layer.",
            !Regex("""vcp\.n_thread\b""").containsMatchIn(body)
        )
    }
}
```

- [ ] **Step 2: Run it, expected failure.** From the repo root:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests "com.whispereverywhere.whisper.NativeVadSourceContractTest" --no-daemon
```

Expected:
```
com.whispereverywhere.whisper.NativeVadSourceContractTest > batchVadContext_pinsOneThread_soTheShippedFilterStopsForkingPthreadsPerChunk FAILED
    java.lang.AssertionError: we_vad_filter must set vcp.n_threads = 1 before creating the batch VAD context. ...
```

- [ ] **Step 3: Minimal implementation.** In `app/src/main/cpp/whisper_jni.cpp`, replace the single line at `:140`

```cpp
        whisper_vad_context_params vcp = whisper_vad_default_context_params();
```

with:

```cpp
        whisper_vad_context_params vcp = whisper_vad_default_context_params();
        // n_threads = 1 is a straight latency win, not a tuning preference.
        // ggml_backend_cpu_set_threadpool is never called for a VAD context, so cpu_ctx->threadpool
        // is NULL and ggml_graph_compute takes the disposable path: it spawns + joins n_threads-1
        // real pthreads on EVERY graph compute — the disposable decision is ggml-cpu.c:3320-3325,
        // the spawn is the `for (j = 1; j < n_threads; j++) ggml_thread_create` loop at :3283-3287
        // inside ggml_threadpool_new_impl, and the join is :3379 — and that compute is inside the
        // per-window frame loop (whisper.cpp:5170), once per 512 samples. At the default 4 this
        // costs 375 create/join cycles per 4 s commit and 1,407 per 15 s commit, for a ~74-node /
        // ~1.36 MFLOP graph with a ggml_barrier between every node, which cannot benefit from 4-way
        // splitting anyway. whisper_jni.cpp already encodes the softer version of this lesson for
        // whisper itself below ("extra efficiency-core threads a NET LOSS"); VAD needs the hard one.
        // FIELD NAME: n_threads (whisper.h:683). The initializer comment at whisper.cpp:4445 says
        // ".n_thread" — that is the comment, not the field, and it does not compile.
        vcp.n_threads = 1;
```

- [ ] **Step 4: Run tests green + compile gate.** Both, from the repo root:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests "com.whispereverywhere.whisper.NativeVadSourceContractTest" --no-daemon
```
```powershell
([xml](Get-Content 'C:\Users\bastr\.androidbuild\WhisperEverywhere\app\test-results\testDebugUnitTest\TEST-com.whispereverywhere.whisper.NativeVadSourceContractTest.xml')).testsuite | Select-Object tests,failures,errors,skipped
```
Expect `tests=1 failures=0 errors=0 skipped=0` from the XML (never from Gradle's task summary). Then the native compile gate — the only thing that proves the C++ edit builds:
```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:assembleDebug --no-daemon
```
Expect `BUILD SUCCESSFUL`. (If this reports `vcp.n_thread` unknown-member, the wrong field name was used — that is exactly what Step 1's third assertion prevents.)

- [ ] **Step 5: Commit.**

```powershell
if ((git rev-parse --abbrev-ref HEAD) -eq 'main') { git checkout -b feat/3.7-vad-endpointing }
git add app/src/main/cpp/whisper_jni.cpp app/src/test/java/com/whispereverywhere/whisper/NativeVadSourceContractTest.kt
git commit -m @'
perf(vad): pin the batch VAD context to one thread (B-prime free win)

we_vad_filter created its cached whisper_vad_context from
whisper_vad_default_context_params() unmodified, i.e. n_threads = 4. A VAD
context never gets a ggml threadpool installed, so every graph compute takes
the disposable-pthread path and spawns + joins three real threads — and that
compute runs once per 512-sample window inside the frame loop. Shipped
behaviour today pays 375 create/join cycles per 4 s commit and 1,407 per 15 s
commit for a ~74-node graph with a barrier between every node, which cannot
use the extra threads at all.

Independent of the 3.7 endpointer: this is a win on 3.6.0 behaviour as it
stands. Also lands NativeVadSourceContractTest, a source-contract guard for
the native VAD constructs that assembleDebug is otherwise the only witness to.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT
'@
```

---

### Task N2: B — demote the five per-call streaming-VAD logs in the fork

**Files:**
- Modify `C:/Users/bastr/OneDrive/Desktop/whisper Everywhere/app/src/main/cpp/whisper.cpp/src/whisper.cpp` (git **submodule** `TechBran/whisper.cpp` @ branch `we/v1.9.1-android`) — five lines inside `whisper_vad_detect_speech_no_reset`, currently `:5119`, `:5120`, `:5123`, `:5149`, `:5182`
- Modify `C:/Users/bastr/OneDrive/Desktop/whisper Everywhere/app/src/test/java/com/whispereverywhere/whisper/NativeVadSourceContractTest.kt` — add the `fork` helper and one `@Test`

**Interfaces:**
- Consumes: `NativeVadSourceContractTest.repoFile(String): File` (Task N1). `WHISPER_LOG_DEBUG(...)` — verified at `whisper.cpp:125-132`: `WHISPER_DEBUG` is commented out at `:126`, so `WHISPER_LOG_DEBUG(...)` expands to **nothing**. This is a true compile-out, not a re-level: it must be, because `we_native_log` (`whisper_jni.cpp:22-27`) maps every non-WARN/ERROR level to `ANDROID_LOG_INFO`, so a level change alone would have fixed nothing.
- Produces: `NativeVadSourceContractTest.fork: String` (lazy read of the vendored `whisper.cpp`), consumed by no later task in this section.

**Line-anchor resolution (binding):** the spec and the investigation report cite `whisper.cpp:5104,5105,5108,5117,5176`. Re-verified in the actual submodule at `714224a3`: the four aligned-path lines are now **5119, 5120, 5123, 5182** and the short-frame line is **5149** (the spec's `5117` was the short-frame one; today `5123` is `props size`). These were re-rebased +6 after task N1 bumped the fork: the six-line fork note now sitting at `:5104-5109` shifted everything below it. Anchor on the format strings, listed below, not the numbers.

**Not JVM-TDD-able (explicit):** same as Task N1 — the failing test is a source-contract assertion over the vendored `.cpp`; `:app:assembleDebug` is the compile gate. This one is a **submodule** edit, so it takes two commits (fork commit + parent pointer bump).

- [ ] **Step 1: Write the failing test.** In `NativeVadSourceContractTest.kt`, add the `fork` helper immediately after the `jni` property, and the test method at the end of the class:

```kotlin
    private val fork: String by lazy { repoFile("src/main/cpp/whisper.cpp/src/whisper.cpp").readText() }

    /** The streaming VAD entry point's body: bounded by the resetting wrapper that follows it. */
    private fun streamingVadEntryPoint(): String =
        fork.substringAfter("bool whisper_vad_detect_speech_no_reset(")
            .substringBefore("bool whisper_vad_detect_speech(")
```

```kotlin
    @Test
    fun streamingVadEntryPoint_logsNothingAtInfo_soFrameRateProbingCannotFloodLogd() {
        val fn = streamingVadEntryPoint()
        listOf(
            "detecting speech in %d samples",
            "n_chunks: %d",
            "props size: %u",
            "chunk_len: %d < n_window: %d",
            "vad time = %.2f ms processing %d samples",
        ).forEach { message ->
            val line = fn.lineSequence().firstOrNull { it.contains(message) }
                ?: throw AssertionError("\"$message\" vanished from whisper_vad_detect_speech_no_reset")
            assertTrue(
                "\"$message\" must log at WHISPER_LOG_DEBUG, which is compiled out entirely " +
                    "(WHISPER_DEBUG is undefined at whisper.cpp:126). It fires per VAD call, and " +
                    "3.7 drives that call 31.25x/second from the capture thread — every INFO here " +
                    "is ~31 __android_log_write/second to logd, plausibly costing more than the " +
                    "1.36 MFLOP inference itself, and it evicts the WE-DIAG lines the owner's " +
                    "acceptance greps depend on. Demoting (not deleting) keeps the fork " +
                    "upstream-mergeable and keeps -DWHISPER_DEBUG useful. Found: $line",
                line.contains("WHISPER_LOG_DEBUG(")
            )
        }
        assertTrue(
            "no WHISPER_LOG_INFO may survive anywhere in whisper_vad_detect_speech_no_reset — " +
                "it is a per-call function and 3.7 makes it a per-frame one",
            !fn.contains("WHISPER_LOG_INFO")
        )
    }
```

- [ ] **Step 2: Run it, expected failure.**

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests "com.whispereverywhere.whisper.NativeVadSourceContractTest" --no-daemon
```

Expected:
```
com.whispereverywhere.whisper.NativeVadSourceContractTest > streamingVadEntryPoint_logsNothingAtInfo_soFrameRateProbingCannotFloodLogd FAILED
    java.lang.AssertionError: "detecting speech in %d samples" must log at WHISPER_LOG_DEBUG, which is compiled out entirely ... Found:     WHISPER_LOG_INFO("%s: detecting speech in %d samples\n", __func__, n_samples);
```

- [ ] **Step 3: Minimal implementation.** In `app/src/main/cpp/whisper.cpp/src/whisper.cpp`, five one-token edits inside `whisper_vad_detect_speech_no_reset`. Each replaces `WHISPER_LOG_INFO` with `WHISPER_LOG_DEBUG`, nothing else on the line changes:

```cpp
    WHISPER_LOG_DEBUG("%s: detecting speech in %d samples\n", __func__, n_samples);
    WHISPER_LOG_DEBUG("%s: n_chunks: %d\n", __func__, n_chunks);
```
```cpp
    WHISPER_LOG_DEBUG("%s: props size: %u\n", __func__, n_chunks);
```
```cpp
            WHISPER_LOG_DEBUG("%s: chunk_len: %d < n_window: %d\n", __func__, chunk_len, vctx->n_window);
```
```cpp
    WHISPER_LOG_DEBUG("%s: vad time = %.2f ms processing %d samples\n", __func__, 1e-3f * vctx->t_vad_us, n_samples);
```

Then, directly above the function signature `bool whisper_vad_detect_speech_no_reset(`, add the fork-hygiene note so the next upstream rebase knows why:

```cpp
// whisper Everywhere fork note: the per-call logs in this function are DEBUG, not INFO.
// Android streaming VAD calls this once per 512-sample window (31.25x/second) on the audio
// capture thread; at INFO each line is an __android_log_write to logd, ~125 socket writes per
// second for the lifetime of a dictation session. WHISPER_DEBUG is undefined by default
// (see :126) so these compile out to nothing in a normal build, and -DWHISPER_DEBUG restores
// the full narration. Do not re-promote on an upstream merge.
```

**Do not touch** the two `WHISPER_LOG_ERROR` lines in the same function (sched-alloc failure, graph-compute failure) — those are once-per-failure and must stay loud.

- [ ] **Step 4: Run tests green + compile gate.**

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests "com.whispereverywhere.whisper.NativeVadSourceContractTest" --no-daemon
```
```powershell
([xml](Get-Content 'C:\Users\bastr\.androidbuild\WhisperEverywhere\app\test-results\testDebugUnitTest\TEST-com.whispereverywhere.whisper.NativeVadSourceContractTest.xml')).testsuite | Select-Object tests,failures,errors,skipped
```
Expect `tests=2 failures=0 errors=0 skipped=0`. Then the compile gate — this one recompiles the vendored `whisper.cpp` translation unit, so budget a few minutes:
```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:assembleDebug --no-daemon
```
Expect `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit** — submodule first, then the parent pointer bump (a bump to an unpushed fork commit is broken by construction, so the push is part of this step):

```powershell
cd "C:\Users\bastr\OneDrive\Desktop\whisper Everywhere\app\src\main\cpp\whisper.cpp"
git add src/whisper.cpp
git commit -m @'
vad: demote the five per-call streaming-VAD logs to WHISPER_LOG_DEBUG

whisper_vad_detect_speech_no_reset emits four INFO lines per call plus one on
a short frame. Driven at 512-sample granularity for streaming endpointing that
is 31.25 calls/second from the audio capture thread — on Android roughly 125
__android_log_write calls per second to logd, for the whole life of a session,
plausibly costing more than the ~1.36 MFLOP inference the lines describe, and
enough to evict other diagnostics from the ring buffer.

WHISPER_DEBUG is undefined by default (whisper.cpp:126), so WHISPER_LOG_DEBUG
expands to nothing: zero cost in a normal build, full narration under
-DWHISPER_DEBUG. Batch VAD gets the same benefit. WHISPER_LOG_ERROR in the
same function is untouched.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT
'@
git push techbran HEAD:we/v1.9.1-android
cd "C:\Users\bastr\OneDrive\Desktop\whisper Everywhere"
git add app/src/main/cpp/whisper.cpp app/src/test/java/com/whispereverywhere/whisper/NativeVadSourceContractTest.kt
git commit -m @'
chore(fork): bump whisper.cpp to the VAD log-demotion commit

we/v1.9.1-android gains the streaming-VAD log demotion: the five per-call
WHISPER_LOG_INFO lines in whisper_vad_detect_speech_no_reset become
WHISPER_LOG_DEBUG, which is compiled out by default. At the 31.25 Hz cadence
the 3.7 endpointer probe runs, those were ~125 logd writes per second on the
capture thread, and they evicted the WE-DIAG lines owner acceptance greps for.

Pinned by NativeVadSourceContractTest so an upstream merge that re-promotes
them fails the JVM suite instead of the owner's logcat.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT
'@
```

---

### Task N3: A — the probe surface header: dedicated context, and the NativeComputeGate bypass argument

**Files:**
- Modify `C:/Users/bastr/OneDrive/Desktop/whisper Everywhere/app/src/main/cpp/whisper_jni.cpp` — insert a new block between `:182` (the closing `}` of `we_vad_filter`) and `:184` (the `// ---` header of the new-segment delivery block)
- Modify `C:/Users/bastr/OneDrive/Desktop/whisper Everywhere/app/src/test/java/com/whispereverywhere/whisper/NativeVadSourceContractTest.kt` — add two `@Test` methods

**Interfaces:**
- Consumes: `whisper_vad_free(whisper_vad_context*)` (whisper.h:729). `LOGI` (whisper_jni.cpp:16). Existing statics `g_vad_mutex` / `g_vad_ctx` (`:126-127`) — referenced only to be kept at arm's length.
- Produces (C++ file scope, consumed by Tasks N4 and N5): `static std::mutex g_probe_mutex;` · `static whisper_vad_context * g_probe_ctx = nullptr;`
- Produces (JNI export, consumed by Task N6): `Java_com_whispereverywhere_whisper_WhisperNative_vadProbeFree(JNIEnv*, jobject) -> void`
- Produces (test helpers, consumed by Tasks N4 and N5): `NativeVadSourceContractTest.jniFunctionBody(name: String): String` · `NativeVadSourceContractTest.containsLiveLine(scope: String, needle: String): Boolean` (a presence check that a commented-out line cannot satisfy)

**This is the A.4 task.** Its primary deliverable is the recorded safety argument for the one whisper call in the process that is **not** wrapped by `NativeComputeGate`; the state declarations and `vadProbeFree` land with it so the intermediate tree compiles with every declared symbol used (no `-Wunused-variable`; `app/src/main/cpp/CMakeLists.txt` sets no `-Werror`, but leaving a clean tree at every task boundary is cheaper than arguing about it).

**Not JVM-TDD-able (explicit):** `vadProbeFree`'s behavior is native. The failing test is a source-contract assertion — and here that is not a consolation prize: the constraint being pinned *is* a documentation constraint ("the safety argument recorded in Workstream A"), so a test over the text is the correct instrument, not a substitute for a better one.

- [ ] **Step 1: Write the failing test.** In `NativeVadSourceContractTest.kt`, add the helper after `weVadFilterBody()` and the two tests at the end of the class:

```kotlin
    /** One JNI export's body: every JNI function in whisper_jni.cpp closes at column 0. */
    private fun jniFunctionBody(name: String): String {
        val marker = "Java_com_whispereverywhere_whisper_WhisperNative_$name("
        val start = jni.indexOf(marker)
        assertTrue(
            "JNI export $name is not declared in whisper_jni.cpp. indexOf() returns -1 when the " +
                "marker is absent, so substring(start) would silently rebase the scope to the top " +
                "of the file, and every claim about $name's body would then be answered by " +
                "unrelated code hundreds of lines away instead of failing here.",
            start >= 0
        )
        val body = jni.substring(start)
        assertTrue(
            "no column-0 \"\\n}\\n\" follows \"$marker\". substringBefore() returns its RECEIVER " +
                "when the delimiter is absent, so a mangled or re-indented closing brace silently " +
                "widens the scope past $name into the FOLLOWING function (and onward until some " +
                "later brace does sit at column 0). Presence checks then pass on a neighbour's " +
                "code, and \"must not touch g_vad_ctx\" fails for a reason unrelated to $name.",
            body.contains("\n}\n")
        )
        return body.substringBefore("\n}\n")
    }

    /**
     * True when [needle] appears on a line of LIVE code inside [scope]; a commented-out line does
     * not count. Same lesson as the log-demotion guard: `// g_probe_ctx = nullptr;` left behind by
     * a refactor would keep a plain contains() green while the dangling pointer it describes is
     * real. Available to Tasks N4 and N5.
     */
    private fun containsLiveLine(scope: String, needle: String): Boolean =
        scope.lineSequence().any { line ->
            val trimmed = line.trimStart()
            line.contains(needle) &&
                !trimmed.startsWith("//") &&
                !trimmed.startsWith("/*") &&
                !trimmed.startsWith("*")
        }
```

```kotlin
    @Test
    fun probeSurface_recordsTheComputeGateBypassArgument_whereTheBypassActuallyLives() {
        val banner = "3.7 Workstream A"
        val terminator = "g_probe_mutex"
        assertTrue(
            "the section banner \"$banner\" is missing from whisper_jni.cpp. substringAfter() " +
                "returns its RECEIVER when the delimiter is absent, so the scope below would " +
                "silently become everything from the top of the file up to the first " +
                "\"$terminator\" — and the whole point of this test is that the argument lives AT " +
                "the probe surface, not merely somewhere in the translation unit.",
            jni.contains(banner)
        )
        val afterBanner = jni.substringAfter(banner)
        assertTrue(
            "\"$terminator\" does not follow \"$banner\". substringBefore() returns its receiver " +
                "when the delimiter is absent, so the scope would silently widen to end-of-file " +
                "and the claims below could be satisfied by prose written anywhere at all.",
            afterBanner.contains(terminator)
        )
        val header = afterBanner.substringBefore(terminator)
        listOf(
            "OUTSIDE NativeComputeGate",
            "whisper.cpp:4671-4674",
            "own CPU backend",
            "FAIR ReentrantLock",
            "2.6 MB",
            // "2.6 MB" alone is satisfied by the RSS sentence higher up in the banner, so deleting
            // the memory REASON from the bypass argument leaves the figure — and the test — intact.
            // This pins the argument the figure is doing work in, not just the figure.
            "is not an OOM risk",
        ).forEach { claim ->
            assertTrue(
                "NativeComputeGate wraps EVERY whisper call in this process; the probe alone " +
                    "bypasses it, so the argument for why that is safe must live at the surface " +
                    "itself, where anyone moving this code will read it. It must state \"$claim\". " +
                    "(If the whisper.cpp citation has drifted, re-verify the forced-CPU line " +
                    "rather than deleting the claim.)",
                header.contains(claim)
            )
        }
    }

    @Test
    fun probeContext_isDedicated_becauseSharingTheBatchContextCorruptsItThreeWays() {
        assertTrue(
            "the probe needs its OWN whisper_vad_context. we_vad_filter's path reaches " +
                "whisper_vad_detect_speech, which unconditionally resets the LSTM on entry " +
                "(whisper.cpp:5193) — wiping the recurrence the probe is riding — and resizes " +
                "probs to hundreds of entries from index 0, which is the slot the probe reads.",
            Regex("""static\s+whisper_vad_context\s*\*\s*g_probe_ctx\s*=\s*nullptr;""")
                .containsMatchIn(jni)
        )
        assertTrue(
            "the probe needs its OWN mutex. ggml_backend_sched is not thread-safe and both " +
                "callers would write the same \"frame\" input tensor; g_vad_mutex cannot fix the " +
                "state wipe or the probs clobber anyway.",
            Regex("""static\s+std::mutex\s+g_probe_mutex;""").containsMatchIn(jni)
        )
        val free = jniFunctionBody("vadProbeFree")
        assertTrue("vadProbeFree must take g_probe_mutex", containsLiveLine(free, "g_probe_mutex"))
        assertTrue("vadProbeFree must not touch g_vad_ctx", !free.contains("g_vad_ctx"))
        assertTrue(
            "vadProbeFree must be null-safe and idempotent",
            containsLiveLine(free, "g_probe_ctx = nullptr;")
        )
    }
```

- [ ] **Step 2: Run it, expected failure.**

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests "com.whispereverywhere.whisper.NativeVadSourceContractTest" --no-daemon
```

Expected (two failures):
```
com.whispereverywhere.whisper.NativeVadSourceContractTest > probeSurface_recordsTheComputeGateBypassArgument_whereTheBypassActuallyLives FAILED
    java.lang.AssertionError: NativeComputeGate wraps EVERY whisper call in this process; ... It must state "OUTSIDE NativeComputeGate".
com.whispereverywhere.whisper.NativeVadSourceContractTest > probeContext_isDedicated_becauseSharingTheBatchContextCorruptsItThreeWays FAILED
    java.lang.AssertionError: the probe needs its OWN whisper_vad_context. ...
```

- [ ] **Step 3: Minimal implementation.** In `app/src/main/cpp/whisper_jni.cpp`, insert between `we_vad_filter`'s closing `}` and the `// ---` header of the new-segment block:

```cpp
// ---------------------------------------------------------------------------------------------
// 3.7 Workstream A - streaming VAD probe surface (vadProbeInit / vadProbeFrame / vadProbeReset /
// vadProbeFree).
//
// A dedicated Silero context driven ONE 512-sample frame at a time from the audio capture thread,
// so the Kotlin endpointer can cut segments where the user actually stopped talking instead of
// where a wall clock ran out. It is deliberately SEPARATE from g_vad_ctx above, and that is a
// correctness requirement rather than an optimisation: we_vad_filter reaches
// whisper_vad_detect_speech, which resets the LSTM state on entry (whisper.cpp:5193) and resizes
// probs to hundreds of entries from index 0. Sharing one context corrupts the probe three
// independent ways - state wipe, probs clobber, and a ggml_backend_sched data race (sched is not
// thread-safe and both callers write the same "frame" input tensor). A mutex fixes only the
// third. Two contexts cost ~2.6 MB RSS; the process already carries one for the batch filter.
//
// Division of labour with that batch filter, which still runs on every commit: the PROBE decides
// WHEN to cut, the FILTER decides WHAT audio inside the cut reaches the encoder. Independent
// jobs, independent knobs - the filter keeps its own 0.40 / 150 ms onset tuning untouched.
//
// PROBE SAFETY: these four functions run OUTSIDE NativeComputeGate. Every other whisper call in
// this process is wrapped by it; the probe alone is not. The argument:
//   1. The gate exists for exactly two named reasons (NativeComputeGate.kt:15-21): concurrent
//      submits on the shared Adreno OpenCL command queue racing GpuPolicy's crash sentinel, and
//      two full contexts doubling the KV/compute buffers inside a foreground service.
//   2. Reason one is unreachable. whisper_vad_init_context hard-forces use_gpu = false at
//      whisper.cpp:4671-4674 ("GPU VAD is forced disabled until the performance is improved"),
//      REGARDLESS of what the params ask for; belt and braces,
//      whisper_vad_default_context_params() already defaults it false. A VAD context cannot
//      touch OpenCL, so it cannot race the sentinel.
//   3. Reason two is unreachable. The VAD context builds its own CPU backend with its own work
//      buffers, sched and galloc - no mutable state shared with any whisper_context - and 2.6 MB
//      is not an OOM risk against a 190-574 MB model tier the user already loaded.
//   4. Taking the gate would be actively harmful. It is a FAIR ReentrantLock
//      (NativeComputeGate.kt:34), so each 32 ms frame would queue behind whatever holds it: a
//      4-15 s whisper_full, or one of BatchTranscriber's ~54 s per-chunk holds. That is precisely
//      the stall 3.7 exists to remove, recreated inside the mechanism meant to remove it.
// Nothing else may follow the probe through this hole: NativeComputeGate still wraps every
// whisper_full, load and release.
//
// Thread-safety here is g_probe_mutex, which guards ONLY these four functions and the probe
// context. It is never taken while g_vad_mutex is held and never the reverse, so the two VAD
// paths cannot deadlock against each other.
// ---------------------------------------------------------------------------------------------

static std::mutex             g_probe_mutex;
static whisper_vad_context  * g_probe_ctx = nullptr;

// Releases the probe context (~2.6 MB). Idempotent, and safe after a failed vadProbeInit.
// Blocks briefly if a frame is in flight, which is why the caller runs it on the capture-thread
// teardown path rather than on Main.
extern "C" JNIEXPORT void JNICALL
Java_com_whispereverywhere_whisper_WhisperNative_vadProbeFree(
        JNIEnv * /*env*/, jobject /* this */) {
    std::lock_guard<std::mutex> lock(g_probe_mutex);
    if (g_probe_ctx != nullptr) {
        whisper_vad_free(g_probe_ctx);
        g_probe_ctx = nullptr;
        LOGI("vad probe: context freed");
    }
}
```

- [ ] **Step 4: Run tests green + compile gate.**

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests "com.whispereverywhere.whisper.NativeVadSourceContractTest" --no-daemon
```
```powershell
([xml](Get-Content 'C:\Users\bastr\.androidbuild\WhisperEverywhere\app\test-results\testDebugUnitTest\TEST-com.whispereverywhere.whisper.NativeVadSourceContractTest.xml')).testsuite | Select-Object tests,failures,errors,skipped
```
Expect `tests=4 failures=0 errors=0 skipped=0`.
```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:assembleDebug --no-daemon
```
Expect `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit.**

```powershell
git add app/src/main/cpp/whisper_jni.cpp app/src/test/java/com/whispereverywhere/whisper/NativeVadSourceContractTest.kt
git commit -m @'
feat(vad): probe surface header — dedicated context + the compute-gate bypass argument

Opens the 3.7 streaming-VAD probe surface with its own whisper_vad_context and
its own mutex, never the batch filter's. Sharing g_vad_ctx would corrupt the
probe three independent ways: whisper_vad_detect_speech resets the LSTM on
entry, resize()s probs from index 0, and ggml_backend_sched is not thread-safe
with both callers writing the same "frame" tensor. A mutex fixes only the last.

Records, at the surface itself, why these four functions bypass
NativeComputeGate — the only whisper calls in the process that do. VAD is
forced CPU-only regardless of params (whisper.cpp:4671-4674) so the gate's GPU
reason cannot apply; the context owns its backend and buffers so the memory
reason cannot apply; and the gate is FAIR, so routing 32 ms frames through it
would queue them behind 4-15 s whisper_full calls and ~54 s batch holds —
recreating the stall 3.7 exists to remove.

vadProbeFree lands with it so every declared symbol has a user.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT
'@
```

---

### Task N4: A — `vadProbeInit`

**Files:**
- Modify `C:/Users/bastr/OneDrive/Desktop/whisper Everywhere/app/src/main/cpp/whisper_jni.cpp` — insert directly above `Java_com_whispereverywhere_whisper_WhisperNative_vadProbeFree` (added in Task N3)
- Modify `C:/Users/bastr/OneDrive/Desktop/whisper Everywhere/app/src/test/java/com/whispereverywhere/whisper/NativeVadSourceContractTest.kt` — add two `@Test` methods

**Interfaces:**
- Consumes: `g_probe_mutex`, `g_probe_ctx` (Task N3). `whisper_vad_default_context_params(void)` and `whisper_vad_init_from_file_with_params(const char*, whisper_vad_context_params)` (whisper.h:688, :690). `we_install_native_logging()` (whisper_jni.cpp:29). `LOGI`/`LOGE` (`:16-17`).
- Produces (JNI export, consumed by Task N6): `Java_com_whispereverywhere_whisper_WhisperNative_vadProbeInit(JNIEnv*, jobject, jstring modelPath) -> jboolean`
- Contract produced for Workstreams C/D: **false is a normal outcome, not an error** — model missing / extraction failed / bad file. The caller falls back to `AmplitudeEndpointer`, which is byte-identical to 3.6.0. Idempotent: a second init frees the first context.

**Not JVM-TDD-able (explicit):** the model load is native. Failing test = source contract; `:app:assembleDebug` = compile gate. The *runtime* exit criterion ("a probe frame returns a plausible prob, and `g_vad_ctx` is provably untouched by a concurrent `we_vad_filter`") is on-device owner acceptance, and it is **not** reachable from this plan: `:app:installDebug` and `:app:connectedDebugAndroidTest` are forbidden here (they wipe on-device models).

- [ ] **Step 1: Write the failing test.** Add to `NativeVadSourceContractTest.kt`:

```kotlin
    @Test
    fun probeContext_pinsOneThread_soFrameRateProbingDoesNotForkPthreadsThirtyTimesASecond() {
        val init = jniFunctionBody("vadProbeInit")
        val pin = Regex("""(?m)^[ \t]*vcp\.n_threads = 1;""").find(init)
        assertTrue(
            "vadProbeInit must set vcp.n_threads = 1. This is the highest-leverage single line " +
                "in Workstream A: at 31.25 frames/second the default 4 means 93.75 pthread " +
                "create/join cycles per second, continuously, for a ~74-node / ~1.36 MFLOP graph " +
                "with a ggml_barrier between every node that cannot be split 4 ways at all.",
            pin != null
        )
        assertTrue(
            "vcp.n_threads = 1 must be set BEFORE the init call it parameterises. The index comes " +
                "from the line-anchored regex match, not indexOf(\"vcp.n_threads = 1;\"): a literal " +
                "search would happily measure the position of a COMMENTED-OUT pin and report the " +
                "ordering of a line that never executes.",
            pin!!.range.first < init.indexOf("whisper_vad_init_from_file_with_params")
        )
        assertTrue(
            "the field is n_threads (whisper.h:683), not the .n_thread of the initializer comment",
            !Regex("""vcp\.n_thread\b""").containsMatchIn(init)
        )
    }

    @Test
    fun probeInit_isIdempotent_soARestartedSessionCannotLeakTheEarlierContext() {
        val init = jniFunctionBody("vadProbeInit")
        val free = Regex("""(?m)^[ \t]*whisper_vad_free\(g_probe_ctx\);""").find(init)
        assertTrue(
            "vadProbeInit must call whisper_vad_free(g_probe_ctx) on a LIVE line: a commented-out " +
                "free satisfies indexOf() while leaking the context it claims to release.",
            free != null
        )
        val freeAt = free!!.range.first
        val createAt = init.indexOf("whisper_vad_init_from_file_with_params")
        assertTrue(
            "vadProbeInit must free any existing probe context BEFORE creating a new one — it is " +
                "called once per recording session and a session restart or model swap would " +
                "otherwise leak ~2.6 MB each time",
            freeAt in 0 until createAt
        )
        assertTrue(
            "vadProbeInit must not touch the batch filter's context",
            !init.contains("g_vad_ctx")
        )
    }
```

- [ ] **Step 2: Run it, expected failure.**

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests "com.whispereverywhere.whisper.NativeVadSourceContractTest" --no-daemon
```

Expected (both new tests error out of the helper, since the export does not exist yet):
```
com.whispereverywhere.whisper.NativeVadSourceContractTest > probeContext_pinsOneThread_soFrameRateProbingDoesNotForkPthreadsThirtyTimesASecond FAILED
    java.lang.AssertionError: JNI export vadProbeInit is not declared in whisper_jni.cpp
com.whispereverywhere.whisper.NativeVadSourceContractTest > probeInit_isIdempotent_soARestartedSessionCannotLeakTheEarlierContext FAILED
    java.lang.AssertionError: JNI export vadProbeInit is not declared in whisper_jni.cpp
```

- [ ] **Step 3: Minimal implementation.** Insert in `app/src/main/cpp/whisper_jni.cpp`, directly above the `vadProbeFree` export:

```cpp
// Creates (or recreates) the probe context. Called once per recording session, on the capture
// thread, before the first frame. Returns false for a missing/unloadable model - a NORMAL
// outcome, not an error: the caller then runs the amplitude endpointer, which is byte-identical
// to 3.6.0 behaviour (VadModel.path() already returns null and logs "running without VAD").
extern "C" JNIEXPORT jboolean JNICALL
Java_com_whispereverywhere_whisper_WhisperNative_vadProbeInit(
        JNIEnv *env, jobject /* this */, jstring modelPath) {
    we_install_native_logging();
    if (modelPath == nullptr) {
        return JNI_FALSE;
    }
    std::string pathStr;
    {
        const char *raw = env->GetStringUTFChars(modelPath, nullptr);
        if (raw == nullptr) {
            return JNI_FALSE;
        }
        pathStr = raw;
        env->ReleaseStringUTFChars(modelPath, raw);
    }

    std::lock_guard<std::mutex> lock(g_probe_mutex);
    // Idempotent. A session restart or a model swap must not leak the previous ~2.6 MB context.
    if (g_probe_ctx != nullptr) {
        whisper_vad_free(g_probe_ctx);
        g_probe_ctx = nullptr;
    }

    whisper_vad_context_params vcp = whisper_vad_default_context_params();
    // MANDATORY, not a tuning preference - see the same note on the batch context above. No
    // ggml threadpool is installed for a VAD context, so ggml_graph_compute spawns + joins
    // n_threads-1 real pthreads on every compute: the disposable decision at ggml-cpu.c:3320-3325,
    // the `for (j = 1; j < n_threads; j++) ggml_thread_create` loop at :3283-3287 inside
    // ggml_threadpool_new_impl that does the spawning, and the join at :3379. That compute runs
    // once per 512-sample window (whisper.cpp:5170). At 31.25 frames/second the default 4 means
    // 93.75 create/join cycles per second, continuously, on the audio capture thread.
    // FIELD NAME: n_threads (whisper.h:683); ".n_thread" is the initializer comment at
    // whisper.cpp:4445 and does not compile.
    vcp.n_threads = 1;
    // use_gpu is already false by default and whisper_vad_init_context forces it false anyway
    // (whisper.cpp:4671-4674) - that forcing is load-bearing for the PROBE SAFETY argument above,
    // so do not "helpfully" enable it here if a future whisper.cpp makes GPU VAD viable without
    // first moving this surface inside NativeComputeGate.

    g_probe_ctx = whisper_vad_init_from_file_with_params(pathStr.c_str(), vcp);
    if (g_probe_ctx == nullptr) {
        LOGE("vad probe: init failed for %s - endpointing falls back to amplitude",
             pathStr.c_str());
        return JNI_FALSE;
    }
    LOGI("vad probe: context ready (n_threads=1, %s)", pathStr.c_str());
    return JNI_TRUE;
}
```

- [ ] **Step 4: Run tests green + compile gate.**

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests "com.whispereverywhere.whisper.NativeVadSourceContractTest" --no-daemon
```
```powershell
([xml](Get-Content 'C:\Users\bastr\.androidbuild\WhisperEverywhere\app\test-results\testDebugUnitTest\TEST-com.whispereverywhere.whisper.NativeVadSourceContractTest.xml')).testsuite | Select-Object tests,failures,errors,skipped
```
Expect `tests=6 failures=0 errors=0 skipped=0`.
```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:assembleDebug --no-daemon
```
Expect `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit.**

```powershell
git add app/src/main/cpp/whisper_jni.cpp app/src/test/java/com/whispereverywhere/whisper/NativeVadSourceContractTest.kt
git commit -m @'
feat(vad): vadProbeInit — the probe context, pinned to one thread

Loads the bundled Silero model into the dedicated probe context created in the
previous commit, with n_threads = 1. That single line is the highest-leverage
one in the workstream: a VAD context never gets a ggml threadpool, so every
graph compute spawns and joins n_threads-1 pthreads, and at 31.25 frames per
second the default 4 would mean 93.75 create/join cycles every second on the
audio capture thread — for a graph with a barrier between every node that
cannot use them.

Idempotent, so a session restart or model swap frees the previous context
rather than leaking 2.6 MB per session. Returning false is a normal outcome,
not an error: the caller falls back to the amplitude endpointer, which is
byte-identical to 3.6.0.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT
'@
```

---

### Task N5: A — `vadProbeFrame` (direct ByteBuffer, `-1.0f` sentinel) and `vadProbeReset`

**Files:**
- Modify `C:/Users/bastr/OneDrive/Desktop/whisper Everywhere/app/src/main/cpp/whisper_jni.cpp` — add `#include <cstdint>` to the include block at `:2-9` (after `<cmath>`); insert the frame constants + `vadProbeFrame` + `vadProbeReset` between `vadProbeInit` (Task N4) and `vadProbeFree` (Task N3)
- Modify `C:/Users/bastr/OneDrive/Desktop/whisper Everywhere/app/src/test/java/com/whispereverywhere/whisper/NativeVadSourceContractTest.kt` — add two `@Test` methods

**Interfaces:**
- Consumes: `g_probe_mutex`, `g_probe_ctx` (Task N3). `whisper_vad_detect_speech_no_reset(whisper_vad_context*, const float*, int) -> bool` (whisper.h:700-703 — carries LSTM state across calls via graph-level `ggml_cpy` write-back). `whisper_vad_n_probs(whisper_vad_context*) -> int` (whisper.h:708). `whisper_vad_probs(whisper_vad_context*) -> float*` (whisper.h:709). `whisper_vad_reset_state(whisper_vad_context*) -> void` (whisper.h:706 — clears the state buffer only; model weights live in a different buffer). `JNIEnv::GetDirectBufferAddress`, `JNIEnv::GetDirectBufferCapacity`.
- Produces (JNI exports, consumed by Task N6): `Java_com_whispereverywhere_whisper_WhisperNative_vadProbeFrame(JNIEnv*, jobject, jobject pcm, jint nBytes) -> jfloat` · `Java_com_whispereverywhere_whisper_WhisperNative_vadProbeReset(JNIEnv*, jobject) -> void`
- Produces (C++ file scope): `static constexpr int kProbeFrameSamples = 512;` · `static constexpr int kProbeFrameBytes = kProbeFrameSamples * 2;` · `static float g_probe_frame[kProbeFrameSamples];`
- **Frame contract produced for Workstream C/D (binding):** `pcm` is a **direct** `ByteBuffer` in native byte order holding little-endian 16-bit mono PCM at 16 kHz; bytes `[0, nBytes)` are read from its base address and position/limit/mark are ignored (one buffer per session, refilled forever). `nBytes != 1024` → `-1.0f`. Not initialised, not direct, capacity short, or native failure → `-1.0f`. Otherwise a raw probability in `[0,1]`. **`-1.0f` means "no verdict", never "silence"** — it must never open or close the speech gate.

**Not JVM-TDD-able (explicit):** the inference is native. Failing test = source contract; `:app:assembleDebug` = compile gate. On-device probability plausibility is Workstream I owner acceptance (frame-by-frame probs over the owner's 8 s clip) and is reached by a normal install from Android Studio, never by `:app:installDebug` or `:app:connectedDebugAndroidTest`.

- [ ] **Step 1: Write the failing test.** Add to `NativeVadSourceContractTest.kt`:

```kotlin
    @Test
    fun probeFrame_refusesAnythingButOneExactSileroWindow_withTheNoVerdictSentinel() {
        assertTrue(
            "the window is 512 samples (model header n_window=512) = 32 ms at 16 kHz",
            Regex("""kProbeFrameSamples\s*=\s*512""").containsMatchIn(jni)
        )
        assertTrue(
            "1024 bytes = 512 samples of 16-bit PCM = exactly one mic callback",
            Regex("""kProbeFrameBytes\s*=\s*kProbeFrameSamples \* 2""").containsMatchIn(jni)
        )
        val frame = jniFunctionBody("vadProbeFrame")
        assertTrue(
            "a misaligned frame must be REFUSED with -1.0f, never zero-padded and never reported " +
                "as silence: whisper_vad_detect_speech_no_reset zero-pads a short frame " +
                "(whisper.cpp:5148-5159) and STILL advances the LSTM one step, poisoning the " +
                "recurrence for every frame after it. AudioRecord.read returns UP TO the buffer " +
                "size and the 48 kHz decimator emits \"~1024\" bytes, so one chunk = one frame is " +
                "the common case and never the contract.",
            frame.contains("nBytes != kProbeFrameBytes") && frame.contains("return -1.0f;")
        )
        assertTrue(
            "0.0f must never be returned as a failure value — it is a legitimate probability",
            !Regex("""return\s+0\.0f;""").containsMatchIn(frame)
        )
        assertTrue(
            "an uninitialised probe returns the sentinel too",
            frame.contains("g_probe_ctx == nullptr")
        )
        assertTrue(
            "the frame must reach native memory via GetDirectBufferAddress — no per-frame " +
                "FloatArray (2 KB x 31.25/s), no JNI array copy, no callback trampoline",
            frame.contains("GetDirectBufferAddress")
        )
        assertTrue(
            "streaming MUST use the no_reset entry point; the resetting variant would wipe the " +
                "LSTM on every single frame and the recurrence would never accumulate",
            frame.contains("whisper_vad_detect_speech_no_reset(")
        )
        assertTrue(
            "vadProbeFrame must never call the resetting variant",
            !Regex("""[^_]whisper_vad_detect_speech\(""").containsMatchIn(frame)
        )
    }

    @Test
    fun probeSurface_isFourFunctions_eachIsolatedFromTheBatchFilter() {
        listOf("vadProbeInit", "vadProbeFrame", "vadProbeReset", "vadProbeFree").forEach { fn ->
            val body = jniFunctionBody(fn)
            assertTrue("$fn must take g_probe_mutex", body.contains("g_probe_mutex"))
            assertTrue(
                "$fn must not touch g_vad_ctx: the batch filter resets that context's LSTM on " +
                    "entry and clobbers probs[0], which is the slot the probe reads",
                !body.contains("g_vad_ctx")
            )
            assertTrue("$fn must not take g_vad_mutex", !body.contains("g_vad_mutex"))
        }
        assertTrue(
            "vadProbeReset must clear the LSTM state — it is the 'new utterance starts here' " +
                "signal, wired into all five reset sites by Workstream D",
            jniFunctionBody("vadProbeReset").contains("whisper_vad_reset_state(g_probe_ctx);")
        )
    }
```

- [ ] **Step 2: Run it, expected failure.**

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests "com.whispereverywhere.whisper.NativeVadSourceContractTest" --no-daemon
```

Expected:
```
com.whispereverywhere.whisper.NativeVadSourceContractTest > probeFrame_refusesAnythingButOneExactSileroWindow_withTheNoVerdictSentinel FAILED
    java.lang.AssertionError: the window is 512 samples (model header n_window=512) = 32 ms at 16 kHz
com.whispereverywhere.whisper.NativeVadSourceContractTest > probeSurface_isFourFunctions_eachIsolatedFromTheBatchFilter FAILED
    java.lang.AssertionError: JNI export vadProbeFrame is not declared in whisper_jni.cpp
```

- [ ] **Step 3: Minimal implementation.** First, in the include block at the top of `app/src/main/cpp/whisper_jni.cpp`, add `<cstdint>` after `<cmath>` so `int16_t` is guaranteed rather than inherited from `jni.h`:

```cpp
#include <cmath>
#include <cstdint>
#include <cstdlib>
```

Then insert between the `vadProbeInit` and `vadProbeFree` exports:

```cpp
// One Silero window. The bundled model header declares n_window = 512, which at 16 kHz is exactly
// the 32 ms the mic callback delivers, and 1024 bytes of 16-bit PCM.
static constexpr int kProbeFrameSamples = 512;
static constexpr int kProbeFrameBytes   = kProbeFrameSamples * 2;

// Reused for every frame: no per-frame allocation on the audio capture thread. Guarded by
// g_probe_mutex along with g_probe_ctx.
static float g_probe_frame[kProbeFrameSamples];

// Speech probability in [0,1] for EXACTLY ONE 512-sample window, or -1.0f meaning "no verdict".
//
// -1.0f is NEVER "silence". A short frame is zero-padded by whisper_vad_detect_speech_no_reset
// (whisper.cpp:5148-5159) and STILL advances the LSTM one step, which poisons the recurrence for
// every frame after it - a silent, gradual accuracy loss with no symptom at the call site. So a
// misaligned frame is refused outright and the Kotlin caller accumulates to exact 512-sample
// boundaries. record.read() returns UP TO the buffer size and the 48 kHz decimator output is
// documented as "~1024" bytes: one chunk = one frame is the common case, never the contract.
// The endpointer treats -1.0f as "keep the previous state" - it neither opens nor closes the gate.
//
// [pcm] must be a DIRECT ByteBuffer in native byte order. Bytes [0, nBytes) are read straight
// from its base address; position/limit/mark are ignored, so one buffer is allocated per session
// and refilled forever. Returning a RAW float rather than a bool is deliberate: threshold,
// hysteresis, hangover and min-speech policy live in Kotlin where they are JVM-pinnable, the same
// split SegmentCapPolicy and SpeechSegmenter already use.
extern "C" JNIEXPORT jfloat JNICALL
Java_com_whispereverywhere_whisper_WhisperNative_vadProbeFrame(
        JNIEnv *env, jobject /* this */, jobject pcm, jint nBytes) {
    if (pcm == nullptr || nBytes != kProbeFrameBytes) {
        return -1.0f;
    }
    void *base = env->GetDirectBufferAddress(pcm);
    if (base == nullptr) {
        return -1.0f;   // not a direct buffer, or JNI cannot address it
    }
    if (env->GetDirectBufferCapacity(pcm) < static_cast<jlong>(nBytes)) {
        return -1.0f;
    }

    std::lock_guard<std::mutex> lock(g_probe_mutex);
    if (g_probe_ctx == nullptr) {
        return -1.0f;   // probe unavailable: the caller is on the amplitude fallback
    }

    // PCM16 -> float natively: 512 samples, no JNI array copy, no Kotlin-side FloatArray.
    // memcpy per sample rather than a reinterpret_cast because a direct ByteBuffer carries no
    // int16 alignment guarantee; clang folds this to a halfword load.
    const auto *bytes = static_cast<const unsigned char *>(base);
    for (int i = 0; i < kProbeFrameSamples; ++i) {
        int16_t s;
        std::memcpy(&s, bytes + 2 * i, sizeof(s));
        g_probe_frame[i] = static_cast<float>(s) / 32768.0f;
    }

    // no_reset: the LSTM hidden/cell state carries across calls at the graph level
    // (whisper.cpp:4617/:4621 write back through ggml_cpy), which is the whole streaming premise.
    if (!whisper_vad_detect_speech_no_reset(g_probe_ctx, g_probe_frame, kProbeFrameSamples)) {
        return -1.0f;
    }
    if (whisper_vad_n_probs(g_probe_ctx) <= 0) {
        return -1.0f;
    }
    return whisper_vad_probs(g_probe_ctx)[0];
}

// Zeroes the probe's LSTM hidden/cell state - the "a new utterance starts here" signal.
// whisper_vad_reset_state clears the state buffer only (whisper.cpp:5100-5102); model weights
// live in a different buffer, so this is one backend buffer clear and safe to call often.
// Must run after EVERY commit and at every acoustic-source change: carrying recurrence across a
// mic <-> device-audio switch is a correctness bug, not merely suboptimal.
extern "C" JNIEXPORT void JNICALL
Java_com_whispereverywhere_whisper_WhisperNative_vadProbeReset(
        JNIEnv * /*env*/, jobject /* this */) {
    std::lock_guard<std::mutex> lock(g_probe_mutex);
    if (g_probe_ctx != nullptr) {
        whisper_vad_reset_state(g_probe_ctx);
    }
}
```

- [ ] **Step 4: Run tests green + compile gate.**

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests "com.whispereverywhere.whisper.NativeVadSourceContractTest" --no-daemon
```
```powershell
([xml](Get-Content 'C:\Users\bastr\.androidbuild\WhisperEverywhere\app\test-results\testDebugUnitTest\TEST-com.whispereverywhere.whisper.NativeVadSourceContractTest.xml')).testsuite | Select-Object tests,failures,errors,skipped
```
Expect `tests=8 failures=0 errors=0 skipped=0`.
```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:assembleDebug --no-daemon
```
Expect `BUILD SUCCESSFUL` — this is the gate that proves the whole four-function native surface links.

- [ ] **Step 5: Commit.**

```powershell
git add app/src/main/cpp/whisper_jni.cpp app/src/test/java/com/whispereverywhere/whisper/NativeVadSourceContractTest.kt
git commit -m @'
feat(vad): vadProbeFrame + vadProbeReset — the streaming frame contract

vadProbeFrame reads one 512-sample window straight out of a reusable direct
ByteBuffer (GetDirectBufferAddress, PCM16 -> float natively) and returns the
raw Silero probability. No per-frame FloatArray, no JNI array copy, no callback
trampoline: at 31.25 Hz those would add ~64 KB/s of allocation to the audio
capture thread for nothing.

Anything that is not exactly 1024 bytes returns -1.0f, and -1.0f means "no
verdict", never "silence". The native frame loop zero-pads a short frame and
still advances the LSTM one step, poisoning the recurrence for every frame
after it with no symptom at the call site — so a misaligned frame must be
refused and the caller must accumulate to exact 512-sample boundaries.
0.0f is never returned as a failure value; it is a legitimate probability.

Returning a raw float rather than a verdict keeps threshold, hysteresis and
hangover policy in Kotlin, where it is JVM-pinnable.

vadProbeReset zeroes the LSTM state for the next utterance — a state-buffer
clear only, so it is cheap enough for the five reset sites that will call it.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT
'@
```

---

### Task N6: A — the four `WhisperNative.kt` externs

**Files:**
- Modify `C:/Users/bastr/OneDrive/Desktop/whisper Everywhere/app/src/main/java/com/whispereverywhere/whisper/WhisperNative.kt` — add `import java.nio.ByteBuffer` after `:1` (`package`); extend the object KDoc list at `:6-11`; insert the four externs between `:103` (`external fun detectedLanguage`) and `:105` (the `BENCH-ONLY` KDoc)
- Create `C:/Users/bastr/OneDrive/Desktop/whisper Everywhere/app/src/test/java/com/whispereverywhere/whisper/WhisperNativeVadProbeShapeTest.kt`

**Interfaces:**
- Consumes: the four JNI exports from Tasks N3-N5 (`vadProbeInit(JNIEnv*, jobject, jstring) -> jboolean`, `vadProbeFrame(JNIEnv*, jobject, jobject, jint) -> jfloat`, `vadProbeReset(JNIEnv*, jobject) -> void`, `vadProbeFree(JNIEnv*, jobject) -> void`). Kotlin `object` members compile to *instance* methods, which is why every export takes `jobject` — matching the existing `setAudioCtxFloor`.
- **Produces (the section's public surface — Workstreams C, D and E consume exactly these):**
  - `WhisperNative.vadProbeInit(modelPath: String): Boolean`
  - `WhisperNative.vadProbeFrame(pcm: java.nio.ByteBuffer, nBytes: Int): Float`
  - `WhisperNative.vadProbeReset()`
  - `WhisperNative.vadProbeFree()`
  - plus the frame contract pinned in Task N5's Interfaces block.
- Produces (test class): `WhisperNativeVadProbeShapeTest`.

**This IS the JVM-testable wrapper the brief asks for.** `WhisperNative`'s `init` block calls `System.loadLibrary("whisper_jni")`, so *touching* the object on a JVM throws `UnsatisfiedLinkError`. The test therefore loads the class with `Class.forName(name, initialize = false, loader)` — which skips `<clinit>` entirely — and inspects declared methods by reflection. That catches every failure this seam can have from the Kotlin side: a renamed method (JNI resolves BY NAME, so a rename is an `UnsatisfiedLinkError` on the capture thread at the first frame, in release only if R8 is involved), a non-`external` declaration, or a parameter/return type that does not match the exported signature.

- [ ] **Step 1: Write the failing test.** Create `app/src/test/java/com/whispereverywhere/whisper/WhisperNativeVadProbeShapeTest.kt`:

```kotlin
package com.whispereverywhere.whisper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier
import java.nio.ByteBuffer

/**
 * Pins the SHAPE of the four 3.7 VAD-probe externs.
 *
 * WhisperNative's init block calls System.loadLibrary("whisper_jni"), which throws
 * UnsatisfiedLinkError on a plain JVM — so this loads the class WITHOUT running its static
 * initializer (Class.forName(..., initialize = false)) and inspects declared methods only. That is
 * enough to catch every failure this seam can have on the Kotlin side: JNI resolves BY NAME, so a
 * rename or a changed parameter type is not a compile error anywhere — it is an
 * UnsatisfiedLinkError on the audio capture thread at the first frame of the next dictation.
 */
class WhisperNativeVadProbeShapeTest {

    private val clazz: Class<*> =
        Class.forName("com.whispereverywhere.whisper.WhisperNative", false, javaClass.classLoader)

    private fun method(name: String, vararg params: Class<*>) = clazz.getDeclaredMethod(name, *params)

    @Test
    fun vadProbeInit_takesAModelPath_andReportsReadinessAsBoolean() {
        val m = method("vadProbeInit", String::class.java)
        assertTrue("vadProbeInit must be declared `external`", Modifier.isNative(m.modifiers))
        assertEquals(
            "false is a normal outcome (model missing / unloadable), which is why this is a " +
                "Boolean and not an exception — the caller falls back to AmplitudeEndpointer",
            java.lang.Boolean.TYPE, m.returnType
        )
    }

    @Test
    fun vadProbeFrame_takesADirectBufferAndAByteCount_andReturnsARawProbability() {
        val m = method("vadProbeFrame", ByteBuffer::class.java, Integer.TYPE)
        assertTrue("vadProbeFrame must be declared `external`", Modifier.isNative(m.modifiers))
        assertEquals(
            "a ByteBuffer, not a ByteArray: the buffer is direct and reused for the whole " +
                "session, so no per-frame allocation or JNI array copy happens at 31.25 Hz",
            ByteBuffer::class.java, m.parameterTypes[0]
        )
        assertEquals(
            "returns a RAW probability — threshold/hysteresis/hangover policy stays in Kotlin " +
                "where it is JVM-pinnable (SileroEndpointer), not behind the JNI boundary. Float " +
                "also carries the -1.0f no-verdict sentinel, which a Boolean could not.",
            java.lang.Float.TYPE, m.returnType
        )
    }

    @Test
    fun vadProbeResetAndFree_takeNoArguments_andReturnNothing() {
        listOf("vadProbeReset", "vadProbeFree").forEach { name ->
            val m = method(name)
            assertTrue("$name must be declared `external`", Modifier.isNative(m.modifiers))
            assertEquals("$name returns nothing", java.lang.Void.TYPE, m.returnType)
            assertEquals("$name takes no arguments", 0, m.parameterTypes.size)
        }
    }

    @Test
    fun theProbeSurfaceIsExactlyFourMethods_soNoPolicyLeaksAcrossTheJniBoundary() {
        val probe = clazz.declaredMethods.map { it.name }.filter { it.startsWith("vadProbe") }.sorted()
        assertEquals(
            "four and only four: init, frame, reset, free. Anything else here would be endpointing " +
                "policy that belongs in Kotlin.",
            listOf("vadProbeFrame", "vadProbeFree", "vadProbeInit", "vadProbeReset"), probe
        )
    }
}
```

- [ ] **Step 2: Run it, expected failure.**

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests "com.whispereverywhere.whisper.WhisperNativeVadProbeShapeTest" --no-daemon
```

Expected:
```
com.whispereverywhere.whisper.WhisperNativeVadProbeShapeTest > vadProbeInit_takesAModelPath_andReportsReadinessAsBoolean FAILED
    java.lang.NoSuchMethodException: com.whispereverywhere.whisper.WhisperNative.vadProbeInit(java.lang.String)
com.whispereverywhere.whisper.WhisperNativeVadProbeShapeTest > vadProbeFrame_takesADirectBufferAndAByteCount_andReturnsARawProbability FAILED
    java.lang.NoSuchMethodException: com.whispereverywhere.whisper.WhisperNative.vadProbeFrame(java.nio.ByteBuffer, int)
com.whispereverywhere.whisper.WhisperNativeVadProbeShapeTest > vadProbeResetAndFree_takeNoArguments_andReturnNothing FAILED
    java.lang.NoSuchMethodException: com.whispereverywhere.whisper.WhisperNative.vadProbeReset()
com.whispereverywhere.whisper.WhisperNativeVadProbeShapeTest > theProbeSurfaceIsExactlyFourMethods_soNoPolicyLeaksAcrossTheJniBoundary FAILED
    java.lang.AssertionError: four and only four: init, frame, reset, free. ... expected:<[vadProbeFrame, vadProbeFree, vadProbeInit, vadProbeReset]> but was:<[]>
```

- [ ] **Step 3: Minimal implementation.** In `app/src/main/java/com/whispereverywhere/whisper/WhisperNative.kt`:

(a) after the `package` line, add the import:
```kotlin
import java.nio.ByteBuffer
```

(b) in the object KDoc, after the `- free()` line, extend the 1:1 map:
```
 *   - vadProbe*()        -> a dedicated streaming Silero VAD context (3.7 endpointing; see below)
```

(c) insert between `external fun detectedLanguage(ctxPtr: Long): String?` and the `BENCH-ONLY` KDoc:

```kotlin
    // ---------------------------------------------------------------------------------------
    // 3.7 Workstream A — streaming VAD probe.
    //
    // Four externs over a DEDICATED native Silero context, entirely separate from the batch VAD
    // filter [transcribeRaw] runs on every commit. Different jobs: the probe decides WHEN to cut
    // a segment, the filter decides WHAT audio inside that cut reaches the encoder. Independent
    // contexts, independent tuning — the filter keeps its own 0.40 / 150 ms onset knobs.
    //
    // These four are the ONLY whisper calls in this process not wrapped by NativeComputeGate.
    // The safety argument is recorded at the surface itself, in the "PROBE SAFETY" comment block
    // in whisper_jni.cpp — read it before moving any of this.
    // ---------------------------------------------------------------------------------------

    /**
     * Loads the bundled Silero model into a dedicated native VAD context pinned to ONE thread,
     * and reports whether it is ready. Call once per recording session, on the capture thread,
     * before the first [vadProbeFrame]; pass the path from `VadModel.path()`.
     *
     * false is a NORMAL, expected outcome — model missing, extraction failed, unloadable file —
     * not an error: the caller then runs the amplitude endpointer, whose behavior is
     * byte-identical to 3.6.0. Idempotent: a second call frees the previous context first, so a
     * session restart or model swap cannot leak ~2.6 MB.
     */
    external fun vadProbeInit(modelPath: String): Boolean

    /**
     * Speech probability in `[0,1]` for EXACTLY ONE 512-sample Silero window — or **-1.0f,
     * meaning "no verdict"**.
     *
     * [pcm] must be a DIRECT buffer (`ByteBuffer.allocateDirect`) in `ByteOrder.nativeOrder()`,
     * holding little-endian 16-bit mono PCM at 16 kHz. Bytes `[0, nBytes)` are read straight from
     * its base address — position, limit and mark are ignored — so ONE buffer is allocated per
     * session and refilled forever: no per-frame FloatArray, no JNI array copy, no callback.
     *
     * [nBytes] must be exactly **1024** (512 samples × 2 bytes = one 32 ms mic callback at
     * 16 kHz). Anything else returns -1.0f. That is a hard refusal, not a convenience: the native
     * frame loop zero-pads a short frame and STILL advances the LSTM one step, poisoning the
     * recurrence for every frame after it — silent, gradual accuracy loss with no symptom at the
     * call site. `AudioRecord.read` returns *up to* the buffer size and the 48 kHz decimator emits
     * "~1024" bytes, so one chunk = one frame is the common case and never the contract: the
     * caller MUST accumulate to exact 512-sample boundaries.
     *
     * **-1.0f is never "silence".** Treat it as "keep the previous state" — it must neither open
     * nor close the speech gate. It is also what an uninitialised or failed probe returns, so the
     * fallback path needs no separate signal.
     *
     * Returns a RAW probability on purpose: threshold, hysteresis, hangover and min-speech policy
     * live in Kotlin where they are JVM-pinnable, the same split `SegmentCapPolicy` already uses.
     *
     * Runs INLINE on the audio capture thread, ~31.25×/second, holding a native mutex for the
     * duration (0.2–1.5 ms expected against a 32 ms budget). Never call it from Main.
     */
    external fun vadProbeFrame(pcm: ByteBuffer, nBytes: Int): Float

    /**
     * Zeroes the probe's LSTM hidden/cell state — the "a new utterance starts here" signal. Model
     * weights live in a different buffer, so this is one backend buffer clear: cheap enough to
     * call on every commit.
     *
     * Must run after EVERY commit and at every acoustic-source change. Carrying recurrence across
     * a mic ↔ device-audio switch is a correctness bug, not merely suboptimal. No-op when the
     * probe was never initialised.
     */
    external fun vadProbeReset()

    /**
     * Frees the probe context (~2.6 MB RSS). Call at record stop. Safe to call twice, and safe
     * after [vadProbeInit] returned false. Blocks briefly if a frame is in flight, which is why
     * it belongs on the capture-thread teardown path and not on Main.
     */
    external fun vadProbeFree()
```

- [ ] **Step 4: Run tests green.** The new class, then the whole suite (this section's last task, so the section evidence is the aggregate — from the XML, never from Gradle's task summary):

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests "com.whispereverywhere.whisper.*" --no-daemon
```
```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon
```
```powershell
$s = Get-ChildItem 'C:\Users\bastr\.androidbuild\WhisperEverywhere\app\test-results\testDebugUnitTest\*.xml' | ForEach-Object { ([xml](Get-Content $_.FullName)).testsuite }
"tests=$((($s | ForEach-Object { [int]$_.tests }) | Measure-Object -Sum).Sum) failures=$((($s | ForEach-Object { [int]$_.failures }) | Measure-Object -Sum).Sum) errors=$((($s | ForEach-Object { [int]$_.errors }) | Measure-Object -Sum).Sum)"
```
Expect `failures=0 errors=0` and the **+12 delta for this section** (8 in `NativeVadSourceContractTest`, 4 in `WhisperNativeVadProbeShapeTest`). No absolute suite total is asserted here or anywhere else in this plan — Task S5 recomputes it once, from a purged results directory and a forced-fresh run. Then the final compile gate:
```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:assembleDebug --no-daemon
```
Expect `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit.**

```powershell
git add app/src/main/java/com/whispereverywhere/whisper/WhisperNative.kt app/src/test/java/com/whispereverywhere/whisper/WhisperNativeVadProbeShapeTest.kt
git commit -m @'
feat(vad): the four vadProbe externs — Workstream A's Kotlin surface

WhisperNative gains vadProbeInit/Frame/Reset/Free alongside its existing six,
completing the streaming VAD probe seam that Workstreams C and D build the
SileroEndpointer on. The KDoc carries the parts of the contract no signature
can: the buffer must be direct and native-ordered, nBytes must be exactly 1024,
-1.0f means "no verdict" and never "silence", the caller must accumulate to
512-sample boundaries, and the probability comes back raw because the policy
belongs in Kotlin.

Pinned by WhisperNativeVadProbeShapeTest, which loads WhisperNative WITHOUT
running its static initializer (System.loadLibrary would throw on a JVM) and
checks the four declared methods by reflection. JNI resolves by name, so a
rename or a changed parameter type is a compile error nowhere — it is an
UnsatisfiedLinkError on the audio capture thread at the first frame.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT
'@
```

## Workstream E — Capture-thread hardening (E)

---

### Task E1: CaptureThreadPolicy — the pure capture-thread contract

**Files:**
- Create `app/src/main/java/com/whispereverywhere/util/CaptureThreadPolicy.kt`
- Create `app/src/test/java/com/whispereverywhere/util/CaptureThreadPolicyTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `object CaptureThreadPolicy` in `com.whispereverywhere.util` with
  `const val CAPTURE_THREAD_PRIORITY: Int` (= `android.os.Process.THREAD_PRIORITY_URGENT_AUDIO`, −19),
  `fun enterCaptureThread()`,
  `fun stopThenJoin(joinMs: Long, stopRecord: () -> Unit, joinThread: (Long) -> Unit)`.

- [ ] **Step 1: Write the failing test** — `app/src/test/java/com/whispereverywhere/util/CaptureThreadPolicyTest.kt`:

```kotlin
package com.whispereverywhere.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch

/**
 * Pins the two capture-thread contracts 3.7 Workstream E makes load-bearing: the priority every
 * PCM capture thread runs at, and stop-BEFORE-join teardown ordering.
 *
 * The ordering test uses a REAL background thread, never a same-thread stub: the bug it pins is
 * a scheduling bug, and a same-thread fake cannot express "the reader is blocked right now".
 */
class CaptureThreadPolicyTest {

    @Test
    fun captureThreadsRunAtUrgentAudioPriority_not_plainAudio() {
        // THREAD_PRIORITY_URGENT_AUDIO == -19; THREAD_PRIORITY_AUDIO == -16. The distinction is
        // the whole point (TtsEngine.kt:302 is the in-repo precedent), so pin the VALUE, which a
        // future edit to the softer constant would silently change.
        assertEquals(-19, CaptureThreadPolicy.CAPTURE_THREAD_PRIORITY)
    }

    @Test
    fun enterCaptureThread_isSafeToCallOnARealBackgroundThread() {
        val thrown = arrayOfNulls<Throwable>(1)
        val t = Thread {
            try {
                CaptureThreadPolicy.enterCaptureThread()
            } catch (e: Throwable) {
                thrown[0] = e
            }
        }
        t.start()
        t.join(2_000)
        assertFalse(t.isAlive)
        assertEquals(null, thrown[0])
    }

    @Test
    fun stopThenJoin_stopsTheRecordFirst_soTheJoinNeverWaitsOutItsTimeout() {
        // Models AudioRecord: read() blocks until a buffer fills, and only stop() unblocks it.
        // Join-before-stop (the pre-3.7 order in StreamingAudioRecorder.stop) therefore waits the
        // FULL join timeout on the MAIN thread — the ANR vector this ordering closes.
        val recordStopped = CountDownLatch(1)
        val events = java.util.Collections.synchronizedList(mutableListOf<String>())
        val captureThread = Thread {
            recordStopped.await()
            events += "capture-exit"
        }
        captureThread.start()

        val startNs = System.nanoTime()
        CaptureThreadPolicy.stopThenJoin(
            joinMs = 2_000L,
            stopRecord = { events += "stop"; recordStopped.countDown() },
            joinThread = { ms -> captureThread.join(ms) },
        )
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000

        assertEquals(listOf("stop", "capture-exit"), events)
        assertFalse("stopThenJoin must have JOINED, not merely returned", captureThread.isAlive)
        assertTrue("join waited out its timeout (elapsed=${elapsedMs}ms)", elapsedMs < 1_000L)
    }

    @Test
    fun stopThenJoin_stillJoins_whenStoppingTheRecordThrows() {
        // AudioRecord.stop() throws IllegalStateException on an uninitialized record. Swallowing
        // that must not skip the join, or the capture thread outlives release().
        val done = CountDownLatch(1)
        val t = Thread { done.await() }
        t.start()
        var joined = false

        CaptureThreadPolicy.stopThenJoin(
            joinMs = 2_000L,
            stopRecord = { done.countDown(); throw IllegalStateException("uninitialized") },
            joinThread = { ms -> t.join(ms); joined = true },
        )

        assertTrue(joined)
        assertFalse(t.isAlive)
    }
}
```

- [ ] **Step 2: Run it, expected failure** — from repo root:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests "com.whispereverywhere.util.CaptureThreadPolicyTest" --no-daemon
```

Expected: Kotlin compilation of the test source set fails with
`e: ...CaptureThreadPolicyTest.kt: Unresolved reference: CaptureThreadPolicy` (four occurrences).
No XML is produced under `C:/Users/bastr/.androidbuild/WhisperEverywhere/app/test-results` for
this class — that absence IS the red.

- [ ] **Step 3: Minimal implementation** — create
`app/src/main/java/com/whispereverywhere/util/CaptureThreadPolicy.kt`:

```kotlin
package com.whispereverywhere.util

/**
 * The two contracts every PCM capture thread in the app obeys (3.7 Workstream E).
 *
 * Both were latent until 3.7: at ~1% callback duty a mis-prioritised capture thread only cost
 * waveform smoothness, and a backwards teardown only cost one read period. The inline Silero
 * probe puts real native work in the capture callback, which promotes both to load-bearing.
 *
 * Pure and framework-light on purpose (only the two `android.os.Process` touches, which the unit
 * test's returnDefaultValues stubs make no-ops) so the ordering rule is JVM-pinned —
 * CaptureThreadPolicyTest.
 */
object CaptureThreadPolicy {

    /**
     * Capture threads must outrank ordinary background threads: a busy device otherwise
     * deschedules exactly the thread draining the AudioRecord ring, and a missed read is
     * unrecoverable audio. THREAD_PRIORITY_URGENT_AUDIO (-19), not THREAD_PRIORITY_AUDIO (-16) —
     * TtsEngine.kt:302 sets the same value on the render thread for the same reason.
     */
    const val CAPTURE_THREAD_PRIORITY: Int = android.os.Process.THREAD_PRIORITY_URGENT_AUDIO

    /**
     * How long teardown waits for a capture thread to exit. Pre-3.7 this was a bare `2000`
     * literal inside StreamingAudioRecorder.stop(); naming it here is what lets the ordering
     * rule and its bound be read in one place.
     */
    const val CAPTURE_JOIN_MS: Long = 2_000L

    /** FIRST statement of every capture thread body. */
    fun enterCaptureThread() {
        android.os.Process.setThreadPriority(CAPTURE_THREAD_PRIORITY)
    }

    /**
     * Teardown in the ONLY safe order: halt the recorder, THEN join its thread.
     *
     * `AudioRecord.read()` blocks until its buffer fills, and stopping the record is what
     * unblocks it immediately — joining first waits a full read period at best, and with native
     * work in the callback it can wait far longer, on Main. PlaybackAudioCapturer.kt:92-95 has
     * always done it this way and says why; this is that comment made reusable and testable.
     *
     * Both callbacks are individually guarded: a throwing [stopRecord] (AudioRecord.stop() on an
     * uninitialized record) must never skip the join, or the capture thread outlives release().
     */
    fun stopThenJoin(joinMs: Long, stopRecord: () -> Unit, joinThread: (Long) -> Unit) {
        runCatching { stopRecord() }
        runCatching { joinThread(joinMs) }
    }
}
```

- [ ] **Step 4: Run tests green** —

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests "com.whispereverywhere.util.CaptureThreadPolicyTest" --no-daemon
```

Evidence: `C:/Users/bastr/.androidbuild/WhisperEverywhere/app/test-results/testDebugUnitTest/TEST-com.whispereverywhere.util.CaptureThreadPolicyTest.xml`
shows `tests="4" failures="0" errors="0" skipped="0"`.

- [ ] **Step 5: Commit** —

```powershell
git add app/src/main/java/com/whispereverywhere/util/CaptureThreadPolicy.kt app/src/test/java/com/whispereverywhere/util/CaptureThreadPolicyTest.kt; git commit -m @'
feat(capture): CaptureThreadPolicy — urgent-audio priority + stop-then-join, JVM-pinned

E1/E2 groundwork. The ordering test drives a REAL background thread blocked exactly the
way AudioRecord.read() blocks, so join-before-stop fails it by waiting out the timeout.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT
'@
```

---

### Task E2: Wire both capture threads — priority + stop-then-join

**Files:**
- Modify `app/src/test/java/com/whispereverywhere/util/CaptureThreadPolicyTest.kt` (append two tests
  + the source locator helper)
- Modify `app/src/main/java/com/whispereverywhere/util/CaptureThreadPolicy.kt` (already carries
  `CAPTURE_JOIN_MS` from Task E1 — no edit if Step 3 of Task E1 landed verbatim)
- Modify `app/src/main/java/com/whispereverywhere/util/StreamingAudioRecorder.kt` — thread body
  opens at `:70` (`thread = Thread {`); `stop()` is `:102-116`, with the backwards
  `thread?.join(2000)` / `audioRecord?.stop()` pair at `:107-108`
- Modify `app/src/main/java/com/whispereverywhere/audio/PlaybackAudioCapturer.kt` — thread body
  opens at `:64` (`thread = Thread {`). **`stop()` at `:90-101` is deliberately NOT touched** —
  it is already the correct order and is the precedent Task E1 documents.

**Interfaces:**
- Consumes: `CaptureThreadPolicy.enterCaptureThread()`, `CaptureThreadPolicy.stopThenJoin(joinMs, stopRecord, joinThread)`,
  `CaptureThreadPolicy.CAPTURE_JOIN_MS` (Task E1).
- Produces: no new symbols. Behaviour: both capture threads run at −19; `StreamingAudioRecorder.stop()`
  halts the record before joining.

- [ ] **Step 1: Write the failing test** — append to
`app/src/test/java/com/whispereverywhere/util/CaptureThreadPolicyTest.kt`, inside the class:

```kotlin
    @Test
    fun captureJoinBoundIsNamedOnce_andMatchesThePre37Literal() {
        // StreamingAudioRecorder.stop() used a bare `2000` literal. Naming it is what lets the
        // ordering rule and its bound be read in one place; pin the value so the wiring cannot
        // silently lengthen a Main-thread wait.
        assertEquals(2_000L, CaptureThreadPolicy.CAPTURE_JOIN_MS)
    }

    @Test
    fun bothCaptureThreadsEnterThePolicy_andTheRecorderStopsBeforeItJoins() {
        // THE load-bearing red for this task. Neither wiring can be reached from a JVM test
        // (AudioRecord and AudioPlaybackCaptureConfiguration are Android-only), so the contract is
        // pinned STRUCTURALLY on the source — the CapSeamPinTest pattern this plan uses at every
        // service seam. Without it the reorder has no regression protection at all: `assembleDebug`
        // compiles the un-wired recorder perfectly happily, so it cannot express this contract.
        val recorder = source("src/main/java/com/whispereverywhere/util/StreamingAudioRecorder.kt")
        val playback = source("src/main/java/com/whispereverywhere/audio/PlaybackAudioCapturer.kt")

        assertTrue(
            "StreamingAudioRecorder.stop() must go through CaptureThreadPolicy.stopThenJoin",
            recorder.contains("CaptureThreadPolicy.stopThenJoin("),
        )
        assertEquals(
            "no bare join survives: the record must be stopped first, and only the policy orders that",
            0,
            recorder.split("thread?.join(2000)").size - 1,
        )
        assertTrue(
            "the mic capture thread must raise its priority as its FIRST statement",
            recorder.contains("CaptureThreadPolicy.enterCaptureThread()"),
        )
        assertTrue(
            "the device-audio capture thread must raise its priority too",
            playback.contains("CaptureThreadPolicy.enterCaptureThread()"),
        )
    }
```

with the source locator helper (same one `CapSeamPinTest` uses), added once to the class:

```kotlin
    private fun source(relative: String): String {
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir")!!).absoluteFile
        while (dir != null) {
            for (candidate in listOf(java.io.File(dir, relative), java.io.File(dir, "app/$relative"))) {
                if (candidate.isFile) return candidate.readText()
            }
            dir = dir.parentFile
        }
        throw AssertionError("cannot locate $relative from ${System.getProperty("user.dir")}")
    }
```

and the import `import org.junit.Assert.assertTrue` if the file does not already carry it.

- [ ] **Step 2: Run it, expected failure** —

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests "com.whispereverywhere.util.CaptureThreadPolicyTest" --no-daemon
```

Expected:
`bothCaptureThreadsEnterThePolicy_andTheRecorderStopsBeforeItJoins FAILED — java.lang.AssertionError: StreamingAudioRecorder.stop() must go through CaptureThreadPolicy.stopThenJoin`.
That is this task's red, and it is a genuine one: it fails against today's `thread?.join(2000)` /
`audioRecord?.stop()` pair at `StreamingAudioRecorder.kt:107-108` and pins the reorder against a
future revert. `captureJoinBoundIsNamedOnce_andMatchesThePre37Literal` is red only if Task E1's
Step 3 was landed WITHOUT `CAPTURE_JOIN_MS`
(`e: ...CaptureThreadPolicyTest.kt: Unresolved reference: CAPTURE_JOIN_MS`); if E1 landed the
constant verbatim it is green immediately — record that as an already-green guard and proceed.

- [ ] **Step 3: Minimal implementation** —

(a) `StreamingAudioRecorder.kt` — add the import beside the existing ones (the file is in the same
package `com.whispereverywhere.util`, so **no import is needed**; `CaptureThreadPolicy` resolves
directly).

(b) `StreamingAudioRecorder.kt:70` — insert the priority call as the first statement of the thread
body. Replace:

```kotlin
        thread = Thread {
            // Read in 32ms slices (512 samples @16kHz = 1024 bytes), NOT the full buffer:
```

with:

```kotlin
        thread = Thread {
            // FIRST statement: this thread drains the AudioRecord ring and (from 3.7) runs the
            // Silero probe inline in the callback. At default priority a busy device deschedules
            // exactly it, and a missed read is unrecoverable audio.
            CaptureThreadPolicy.enterCaptureThread()
            // Read in 32ms slices (512 samples @16kHz = 1024 bytes), NOT the full buffer:
```

(c) `StreamingAudioRecorder.kt:102-116` — replace the whole `stop()`:

```kotlin
    fun stop() {
        if (!recording) return
        recording = false
        _amplitude.value = 0
        try {
            // STOP THE RECORD, THEN JOIN — the same order PlaybackAudioCapturer.stop() has always
            // used (PlaybackAudioCapturer.kt:92-95, which explains why). read() blocks until its
            // buffer fills and only stop() unblocks it immediately, so the pre-3.7 join-then-stop
            // waited a full read period at best. This runs on MAIN from three sites
            // (FloatingBubbleService.kt:916, :1822, :2344), and 3.7 puts a native probe in the
            // capture callback, so "at best" stopped being the interesting case: the join could
            // wait out its whole 2 s bound, twice per session — ANR territory. The timeout path was
            // worse than slow: it fell through to release() while the capture thread could still be
            // inside onAudioChunk -> sendAudio, landing a chunk AFTER the unconditional stop flush
            // (FloatingBubbleService.kt:2388) — a lost tail or an orphan segment.
            CaptureThreadPolicy.stopThenJoin(
                joinMs = CaptureThreadPolicy.CAPTURE_JOIN_MS,
                stopRecord = { audioRecord?.stop() },
                joinThread = { ms -> thread?.join(ms) },
            )
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            audioRecord?.release()
            audioRecord = null
            thread = null
        }
    }
```

(d) `PlaybackAudioCapturer.kt:64` — insert the priority call as the first statement of its thread
body. Replace:

```kotlin
        thread = Thread {
            val buffer = ByteArray(readSize)
```

with:

```kotlin
        thread = Thread {
            // Same contract as the mic path — see CaptureThreadPolicy. stop() below already
            // stops-then-joins and is the precedent that policy is named after; it stays as is.
            com.whispereverywhere.util.CaptureThreadPolicy.enterCaptureThread()
            val buffer = ByteArray(readSize)
```

- [ ] **Step 4: Run tests green** — unit tests, then the compile gate that is the real
verification for the two Android-only files:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests "com.whispereverywhere.util.CaptureThreadPolicyTest" --no-daemon; if ($?) { .\gradlew.bat :app:assembleDebug --no-daemon }
```

Evidence: `TEST-com.whispereverywhere.util.CaptureThreadPolicyTest.xml` shows `tests="6" failures="0"`
— including `bothCaptureThreadsEnterThePolicy_andTheRecorderStopsBeforeItJoins`, which was red in
Step 2 and is now green against the reordered `stop()` — and `assembleDebug` reports
`BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit** —

```powershell
git add app/src/main/java/com/whispereverywhere/util/StreamingAudioRecorder.kt app/src/main/java/com/whispereverywhere/audio/PlaybackAudioCapturer.kt app/src/test/java/com/whispereverywhere/util/CaptureThreadPolicyTest.kt; git commit -m @'
fix(capture): stop the AudioRecord before joining, and run both capture threads urgent-audio

StreamingAudioRecorder.stop() joined before stopping, so on Main it could wait a full read
period - and, with 3.7 native work in the callback, its whole 2 s bound. A timed-out join
also released the record while the thread might still be inside sendAudio, landing a chunk
after the unconditional stop flush. PlaybackAudioCapturer.stop() is unchanged: it was
already right, and is the precedent CaptureThreadPolicy is named after.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT
'@
```

## Workstream F (pure) — Diagnostics with no service dependency (F1–F6)

---

### Task F1: ProbeStats — overrun counter + the `probe:` line

**Files:**
- Create `app/src/main/java/com/whispereverywhere/util/ProbeStats.kt`
- Create `app/src/test/java/com/whispereverywhere/util/ProbeStatsTest.kt`

**Interfaces:**
- Consumes: `EndpointerTuning.PROBE_BUDGET_MS` (Workstream C, value **8** ms per the spec's tuning
  table) — consumed only at the WIRING site, not here: this class takes `budgetUs: Long` as a
  constructor parameter so it compiles and tests independently of Workstream C.
- Produces: `class ProbeStats(budgetUs: Long, emitIntervalMs: Long = EMIT_INTERVAL_MS)` in
  `com.whispereverywhere.util` with
  `fun record(elapsedUs: Long, nowMs: Long): Boolean`,
  `fun frames(): Long`, `fun overruns(): Long`, `fun percentileUs(q: Double): Long`,
  `fun line(): String`, `fun reset()`;
  companion `BUCKET_WIDTH_US = 16L`, `BUCKETS = 1024`, `EMIT_INTERVAL_MS = 10_000L`.
  Line shape: `probe: frames=N p50=…µs p99=…µs overruns=N`.

**Ownership — Task C10:** the single instance lives in `SileroEndpointer` (Workstream C), which
takes it as a constructor parameter, calls `record(elapsedUs, nowMs)` around its probe call, logs
`line()` under tag `WE-DIAG` whenever `record` returns true, resets it in `onSessionStart` and emits
one final `line()` in `onSessionEnd`. That file does not exist yet at this point in the plan, so the
wiring is its own task: **Task C10**, immediately after C's state machine is complete. Nothing here
depends on it — `budgetUs` is a constructor parameter precisely so this class lands and tests alone.

- [ ] **Step 1: Write the failing test** — `app/src/test/java/com/whispereverywhere/util/ProbeStatsTest.kt`:

```kotlin
package com.whispereverywhere.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 3.7 probe cost/overrun accounting (Workstream E3, surfaced by Workstream F's `probe:` line).
 * Everything here is pure: the class is fed from the capture thread at 31.25 Hz, so it must be
 * allocation-free per frame and must not need a clock of its own.
 */
class ProbeStatsTest {

    /** 8 ms, the spec's PROBE_BUDGET_MS, expressed in the microseconds record() takes. */
    private val budgetUs = 8_000L

    @Test
    fun countsEveryFrame_andOnlyBudgetOverrunsAsOverruns() {
        val s = ProbeStats(budgetUs = budgetUs)
        s.record(800L, 0L)
        s.record(8_000L, 32L)     // exactly at budget is NOT an overrun
        s.record(8_001L, 64L)     // one microsecond over IS
        s.record(9_600L, 96L)

        assertEquals(4L, s.frames())
        assertEquals(2L, s.overruns())
    }

    @Test
    fun p50AndP99AreTakenOverTheWholeSession_andP99CatchesTheTail() {
        val s = ProbeStats(budgetUs = budgetUs)
        // 98 fast frames (800 us -> bucket 50) and 2 slow ones (9600 us -> bucket 600).
        var t = 0L
        repeat(98) { s.record(800L, t); t += 32L }
        repeat(2) { s.record(9_600L, t); t += 32L }

        assertEquals(800L, s.percentileUs(0.50))
        // rank(0.99) = 99 > 98 fast frames, so p99 must land in the slow bucket.
        assertEquals(9_600L, s.percentileUs(0.99))
        assertEquals(2L, s.overruns())
    }

    @Test
    fun percentilesOfAnEmptySessionAreZero_neverNaNOrCrash() {
        val s = ProbeStats(budgetUs = budgetUs)
        assertEquals(0L, s.percentileUs(0.50))
        assertEquals(0L, s.percentileUs(0.99))
        assertEquals("probe: frames=0 p50=0\u00B5s p99=0\u00B5s overruns=0", s.line())
    }

    @Test
    fun lineMatchesTheGreppableFormatExactly() {
        val s = ProbeStats(budgetUs = budgetUs)
        repeat(10) { s.record(1_600L, it * 32L) }
        // \u00B5 is MICRO SIGN: written as an escape so the source stays ASCII and the emitted
        // byte is identical regardless of how the file is encoded on disk.
        assertEquals("probe: frames=10 p50=1600\u00B5s p99=1600\u00B5s overruns=0", s.line())
    }

    @Test
    fun recordSignalsALineIsDue_atMostOncePerEmitInterval() {
        val s = ProbeStats(budgetUs = budgetUs, emitIntervalMs = 10_000L)
        assertFalse("the very first frame only arms the clock", s.record(800L, 1_000L))
        assertFalse(s.record(800L, 5_000L))
        assertFalse(s.record(800L, 10_999L))
        assertTrue(s.record(800L, 11_000L))      // 10 s after the arming frame
        assertFalse(s.record(800L, 11_032L))     // window restarts, nothing due yet
        assertTrue(s.record(800L, 21_000L))
    }

    @Test
    fun overBucketRangeFramesAreClampedIntoTheOverflowBucket_notLost() {
        val s = ProbeStats(budgetUs = budgetUs)
        s.record(5_000_000L, 0L)      // 5 s: a pathological stall, far past the histogram
        assertEquals(1L, s.frames())
        assertEquals(1L, s.overruns())
        // Overflow reports the histogram ceiling, which is unambiguous ("at least this bad").
        assertEquals(ProbeStats.BUCKETS * ProbeStats.BUCKET_WIDTH_US, s.percentileUs(0.50))
    }

    @Test
    fun resetClearsCountersAndHistogram_forTheNextSession() {
        val s = ProbeStats(budgetUs = budgetUs)
        repeat(5) { s.record(9_600L, it * 32L) }
        s.reset()
        assertEquals(0L, s.frames())
        assertEquals(0L, s.overruns())
        assertEquals(0L, s.percentileUs(0.99))
    }

    @Test
    fun aRealCaptureThreadRecordingWhileMainReadsAndResetsNeverTearsTheCounters() {
        // The production shape, and the reason every method is @Synchronized: record() runs on the
        // AudioRecord capture thread while reset() (onSessionStart) and line() (onSessionEnd) are
        // called from Main — and E1's join is TIMED, so a timed-out teardown genuinely leaves the
        // capture thread inside record() while Main is inside line(). REAL executor per the Global
        // Constraints' concurrency rule; never a same-thread stub.
        val capture = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val s = ProbeStats(budgetUs = budgetUs)
            val n = 4_000
            val done = java.util.concurrent.CountDownLatch(1)
            capture.execute {
                for (i in 0 until n) s.record(if (i % 4 == 0) 9_600L else 800L, i * 32L)
                done.countDown()
            }
            // Main hammers the readers throughout — a torn read would surface as an exception
            // (IndexOutOfBounds from a half-cleared histogram) or as an impossible invariant.
            while (done.count > 0L) {
                s.line()
                assertTrue("overruns can never exceed frames", s.overruns() <= s.frames())
            }
            assertTrue("workers did not finish", done.await(20, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(n.toLong(), s.frames())
            assertEquals((n / 4).toLong(), s.overruns())

            // And a reset from Main leaves the instance usable, not half-cleared.
            s.reset()
            assertEquals(0L, s.frames())
            assertEquals(0L, s.overruns())
        } finally {
            capture.shutdownNow()
        }
    }
}
```

- [ ] **Step 2: Run it, expected failure** —

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests "com.whispereverywhere.util.ProbeStatsTest" --no-daemon
```

Expected: `e: ...ProbeStatsTest.kt: Unresolved reference: ProbeStats`.

- [ ] **Step 3: Minimal implementation** — create
`app/src/main/java/com/whispereverywhere/util/ProbeStats.kt`:

```kotlin
package com.whispereverywhere.util

/**
 * Per-frame cost and overrun accounting for the inline Silero probe (3.7 Workstream E3; surfaced
 * by Workstream F's `probe:` line).
 *
 * The probe runs INLINE on the capture thread, 31.25 times a second, against a 32 ms budget.
 * The decision to keep it there instead of handing frames to a dedicated thread was explicitly
 * conditional on measuring whether it ever misses — this is that measurement, and
 * `overruns` is the number the promotion decision reads. Owner acceptance: overruns = 0 on the
 * Fold6.
 *
 * ALLOCATION-FREE PER FRAME: one pre-sized histogram, no lists, no boxing. Percentiles are
 * session-cumulative (not windowed) because the acceptance question is "did this session ever
 * miss", not "is it missing right now" — the overrun counter answers the latter.
 *
 * THREADING: [record] runs on the CAPTURE thread (the probe records, and that thread also emits the
 * line when [record] says one is due); [reset] and [line] are called from MAIN, at session start and
 * session end respectively (Task C10 wires both). Every method is therefore `@Synchronized` on the
 * instance. This is not defensive: E1's teardown join is TIMED, so a timed-out join leaves the
 * capture thread free to mutate `frameCount` / `overrunCount` / `hist` while Main is reading them —
 * and an under-reported `overruns` is exactly the number the S3 acceptance sheet gates on. The lock
 * is re-entrant, so [line] calling [percentileUs] is safe, and it is a handful of int ops taken
 * ~31 times a second, uncontended.
 */
class ProbeStats(
    /** [PROBE_BUDGET_MS] from the tuning object, in microseconds. A frame strictly ABOVE it overruns. */
    private val budgetUs: Long,
    private val emitIntervalMs: Long = EMIT_INTERVAL_MS,
) {
    // Index i counts frames in [i*16, (i+1)*16) us; index BUCKETS is the overflow bucket.
    private val hist = IntArray(BUCKETS + 1)
    private var frameCount = 0L
    private var overrunCount = 0L
    private var lastEmitMs = 0L
    private var armed = false

    /**
     * Records one probe call and returns true when a `probe:` line is due (at most one per
     * [emitIntervalMs]). The first frame only ARMS the clock — a line on frame one would report
     * a one-sample distribution.
     */
    @Synchronized
    fun record(elapsedUs: Long, nowMs: Long): Boolean {
        frameCount++
        if (elapsedUs > budgetUs) overrunCount++
        val us = if (elapsedUs < 0L) 0L else elapsedUs
        val bucket = (us / BUCKET_WIDTH_US)
        hist[if (bucket >= BUCKETS) BUCKETS else bucket.toInt()]++
        if (!armed) {
            armed = true
            lastEmitMs = nowMs
            return false
        }
        if (nowMs - lastEmitMs < emitIntervalMs) return false
        lastEmitMs = nowMs
        return true
    }

    @Synchronized
    fun frames(): Long = frameCount

    @Synchronized
    fun overruns(): Long = overrunCount

    /**
     * The lower edge of the bucket holding the [q]-quantile, in microseconds; 0 when no frame was
     * ever recorded. Quantisation is 16 us against an expected 200-1500 us cost — three orders of
     * magnitude below the 8 ms budget the number is read against.
     */
    @Synchronized
    fun percentileUs(q: Double): Long {
        if (frameCount == 0L) return 0L
        var rank = Math.ceil(q * frameCount).toLong()
        if (rank < 1L) rank = 1L
        var cumulative = 0L
        for (i in hist.indices) {
            cumulative += hist[i].toLong()
            if (cumulative >= rank) return i * BUCKET_WIDTH_US
        }
        return BUCKETS * BUCKET_WIDTH_US
    }

    /**
     * The greppable line. `\u00B5` is MICRO SIGN written as an escape so this source file stays
     * pure ASCII and the emitted byte cannot drift with the file's on-disk encoding.
     */
    @Synchronized
    fun line(): String =
        "probe: frames=$frameCount p50=${percentileUs(0.50)}\u00B5s " +
            "p99=${percentileUs(0.99)}\u00B5s overruns=$overrunCount"

    /** Per-session reset; the endpointer calls this from its own session reset, on Main. */
    @Synchronized
    fun reset() {
        java.util.Arrays.fill(hist, 0)
        frameCount = 0L
        overrunCount = 0L
        lastEmitMs = 0L
        armed = false
    }

    companion object {
        const val BUCKET_WIDTH_US = 16L
        /** 1024 buckets = 0..16383 us, twice the 8 ms budget; index BUCKETS is the overflow. */
        const val BUCKETS = 1024
        const val EMIT_INTERVAL_MS = 10_000L
    }
}
```

- [ ] **Step 4: Run tests green** —

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests "com.whispereverywhere.util.ProbeStatsTest" --no-daemon
```

Evidence: `TEST-com.whispereverywhere.util.ProbeStatsTest.xml` shows `tests="8" failures="0" errors="0"`
(the **+8 delta** for this task — seven pure cases plus the real-executor concurrency case).

- [ ] **Step 5: Commit** —

```powershell
git add app/src/main/java/com/whispereverywhere/util/ProbeStats.kt app/src/test/java/com/whispereverywhere/util/ProbeStatsTest.kt; git commit -m @'
feat(diag): ProbeStats - allocation-free probe cost histogram + overrun counter

E3 plus the `probe: frames= p50= p99= overruns=` line from the F family. Session-cumulative
percentiles because the acceptance question is "did this session ever miss the 32 ms budget",
and overruns=0 on the Fold6 is the gate for keeping the probe inline on the capture thread.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT
'@
```

---

### Task F2: PREPEND `seq=` to `segment-timing` (and the 6 assertions, same task)

**Files:**
- Modify `app/src/test/java/com/whispereverywhere/transcription/SegmentTimingTest.kt` — the six
  assertions carrying the `segment-timing:` literal are at `:11`, `:19`, `:27`, `:36`, `:53`, `:64`.
  The seventh test, `audioMsConvertsSampleCountAt16kHz` (`:41-46`), carries no literal and is NOT
  touched.
- Modify `app/src/main/java/com/whispereverywhere/transcription/SegmentTiming.kt` — `line()` at
  `:41-45`, KDoc sample at `:11`
- Modify `app/src/main/java/com/whispereverywhere/transcription/LocalWhisperEngine.kt` — the only
  caller, `:326-332`

**Interfaces:**
- Consumes: `LocalWhisperEngine.runSegment`'s `seq: Long` parameter (`:269`) — already in scope at
  the call site.
- Produces: `SegmentTiming.line(seq: Long, audioMs: Long, transcribeMs: Long): String` →
  `"segment-timing: seq=<n> audio=<ms> transcribe=<ms> rtf=<x.xx>"`.

**Contract:** `seq=` is PREPENDED to the existing shape. The substring
`audio=<ms> transcribe=<ms> rtf=<x.xx>` and the `segment-timing: ` prefix stay byte-identical, so
every `findstr segment-timing` grep and every existing field-order parser keeps working.

- [ ] **Step 1: Write the failing test** — replace the six `segment-timing:` literals in
`SegmentTimingTest.kt` and add `seq` to each `line(...)` call. The file becomes:

```kotlin
package com.whispereverywhere.transcription

import org.junit.Assert.assertEquals
import org.junit.Test

class SegmentTimingTest {

    @Test
    fun lineMatchesTheGreppableFormatExactly() {
        assertEquals(
            "segment-timing: seq=0 audio=4000 transcribe=6000 rtf=1.50",
            SegmentTiming.line(seq = 0L, audioMs = 4_000L, transcribeMs = 6_000L),
        )
    }

    @Test
    fun rtfBelowOneMeansFasterThanRealTime() {
        assertEquals(
            "segment-timing: seq=3 audio=15000 transcribe=3000 rtf=0.20",
            SegmentTiming.line(seq = 3L, audioMs = 15_000L, transcribeMs = 3_000L),
        )
    }

    @Test
    fun rtfRoundsToTwoDecimals() {
        assertEquals(
            "segment-timing: seq=1 audio=3000 transcribe=1000 rtf=0.33",
            SegmentTiming.line(seq = 1L, audioMs = 3_000L, transcribeMs = 1_000L),
        )
    }

    @Test
    fun zeroAudioNeverDividesByZero() {
        // A degenerate commit (near-zero samples) must report a parseable line, not NaN/crash.
        assertEquals(
            "segment-timing: seq=7 audio=0 transcribe=500 rtf=0.00",
            SegmentTiming.line(seq = 7L, audioMs = 0L, transcribeMs = 500L),
        )
    }

    @Test
    fun audioMsConvertsSampleCountAt16kHz() {
        assertEquals(4_000L, SegmentTiming.audioMs(sampleCount = 64_000))
        assertEquals(1_000L, SegmentTiming.audioMs(sampleCount = 16_000))
        assertEquals(0L, SegmentTiming.audioMs(sampleCount = 0))
    }

    @Test fun formatIsLocaleIndependent() {
        val prior = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY) // comma decimal separator
            assertEquals(
                "segment-timing: seq=0 audio=4000 transcribe=6000 rtf=1.50",
                SegmentTiming.line(seq = 0L, audioMs = 4_000L, transcribeMs = 6_000L),
            )
        } finally {
            java.util.Locale.setDefault(prior)
        }
    }

    @Test fun rtfRoundsHalfUpNotTruncated() {
        // 2000/3000 = 0.666... -> 0.67 under HALF_UP; 0.66 under truncation.
        assertEquals(
            "segment-timing: seq=2 audio=3000 transcribe=2000 rtf=0.67",
            SegmentTiming.line(seq = 2L, audioMs = 3_000L, transcribeMs = 2_000L),
        )
    }

    @Test
    fun seqIsPrependedSoTheOldFieldOrderSurvivesVerbatim() {
        // The whole point of PREPENDING: the pre-3.7 substring is still present, unmodified, so
        // `findstr segment-timing` and every field-order parser written against 3.6.0 keep working.
        val line = SegmentTiming.line(seq = 12L, audioMs = 2_400L, transcribeMs = 1_100L)
        assertEquals(true, line.startsWith("segment-timing: seq=12 "))
        assertEquals(true, line.contains("audio=2400 transcribe=1100 rtf=0.46"))
    }
}
```

- [ ] **Step 2: Run it, expected failure** —

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests "com.whispereverywhere.transcription.SegmentTimingTest" --no-daemon
```

Expected: `e: ...SegmentTimingTest.kt: Cannot find a parameter with this name: seq` (seven
occurrences — the six updated assertions plus the new pinning test).

- [ ] **Step 3: Minimal implementation** —

(a) `SegmentTiming.kt:11` — update the KDoc sample line. Replace:

```
 *     segment-timing: audio=<ms> transcribe=<ms> rtf=<x.xx>
```

with:

```
 *     segment-timing: seq=<n> audio=<ms> transcribe=<ms> rtf=<x.xx>
```

(b) `SegmentTiming.kt:37-45` — replace the `line` function and its KDoc:

```kotlin
    /**
     * The line itself. A zero/negative [audioMs] (degenerate commit) reports rtf=0.00 instead of
     * dividing by zero. Locale.US so the decimal separator is always a point, never a comma.
     *
     * [seq] is PREPENDED, not appended and not emitted as a sibling line (3.7 Workstream F): the
     * pre-3.7 substring `audio=… transcribe=… rtf=…` survives byte-identically, so every
     * `findstr segment-timing` grep and every parser written against 3.6.0 keeps working, while a
     * capture can now be joined against `endpoint:` / `perceived:` on the shared seq — which is
     * what makes "why was this segment cut and how long did the user wait" answerable from one log.
     */
    fun line(seq: Long, audioMs: Long, transcribeMs: Long): String {
        val rtf = if (audioMs > 0) transcribeMs.toDouble() / audioMs.toDouble() else 0.0
        return "segment-timing: seq=$seq audio=$audioMs transcribe=$transcribeMs rtf=" +
            String.format(Locale.US, "%.2f", rtf)
    }
```

(c) `LocalWhisperEngine.kt:326-332` — the sole caller. Replace:

```kotlin
                android.util.Log.i(
                    "WE-DIAG",
                    SegmentTiming.line(
                        audioMs = SegmentTiming.audioMs(samples.size),
                        transcribeMs = (System.nanoTime() - transcribeStartNs) / 1_000_000,
                    ),
                )
```

with:

```kotlin
                android.util.Log.i(
                    "WE-DIAG",
                    SegmentTiming.line(
                        seq = seq,
                        audioMs = SegmentTiming.audioMs(samples.size),
                        transcribeMs = (System.nanoTime() - transcribeStartNs) / 1_000_000,
                    ),
                )
```

- [ ] **Step 4: Run tests green** — the whole transcription package, because the main source set
compiles here and any other caller would surface now:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests "com.whispereverywhere.transcription.*" --no-daemon
```

Evidence: `TEST-com.whispereverywhere.transcription.SegmentTimingTest.xml` shows `tests="8" failures="0"`,
and no other XML in that directory reports a new failure.

- [ ] **Step 5: Commit** —

```powershell
git add app/src/main/java/com/whispereverywhere/transcription/SegmentTiming.kt app/src/main/java/com/whispereverywhere/transcription/LocalWhisperEngine.kt app/src/test/java/com/whispereverywhere/transcription/SegmentTimingTest.kt; git commit -m @'
feat(diag): PREPEND seq= to segment-timing so the F family joins on one key

seq is prepended, never appended and never a sibling line: `audio= transcribe= rtf=` survives
byte-identically, so 3.6.0 greps and parsers are untouched, while endpoint:/perceived:/queue:
now join to segment-timing on seq. All 6 SegmentTimingTest assertions updated in this commit,
plus one new test pinning that the old substring is still present verbatim.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT
'@
```

**Note (not a step):** `docs/superpowers/plans/2026-08-19-local-speed.md:111` records the old
`line(audioMs, transcribeMs)` signature in its shared-contracts table. It is a closed 3.6.0 plan,
so it is deliberately left as the historical record; the 3.7 plan's own contracts table is the
live one.

---

### Task F3: Native — expose ctxFrames/vadIn/vadOut, and move the vad-filter line to WE-DIAG

**Files:**
- Modify `app/src/main/cpp/whisper_jni.cpp` — macros at `:15-17`; VAD statics at `:126-128`;
  `we_vad_filter` body ends `:179-181`; `transcribeRaw` PCM extraction at `:274-278`; the
  `audio_ctx` block at `:370-383`
- Modify `app/src/main/java/com/whispereverywhere/whisper/WhisperNative.kt` — add one extern
  beside `setAudioCtxFloor` (`:105-113`)

**Every `whisper_jni.cpp` line number above is PRE-Workstream-N.** The binding execution order is
N1→N6 first, and N3–N5 insert the whole probe surface (~180 lines) immediately after
`we_vad_filter`'s closing brace — so by the time this task runs, everything below `:182` has moved
down by that block and the `// ---` banner at the old `:184` is now N3's `3.7 Workstream A` header.
**Anchor on the quoted TEXT, never on these numbers**, and place `lastSegmentStats` immediately
AFTER the `vadProbeFree` export (the last member of N3–N5's block) rather than "before the `// ---`
banner".

**Interfaces:**
- Consumes: nothing.
- Produces: `external fun WhisperNative.lastSegmentStats(): IntArray` — a 3-element array
  `[ctxFrames, vadInSamples, vadOutSamples]` describing the LAST `transcribeRaw` in this process.
  Native symbol `Java_com_whispereverywhere_whisper_WhisperNative_lastSegmentStats`.

**RED discipline for native:** there is no JVM test that can reach JNI (`System.loadLibrary`
throws `UnsatisfiedLinkError` off-device), so the gate is the C++ compiler, driven the same way:
write the consumer first, watch it fail to compile, then declare what it needed.

- [ ] **Step 1: Write the failing test** — the failing artifact is the new JNI entry point,
which reads three counters that do not exist yet. Insert it immediately AFTER the `vadProbeFree`
export — the last member of the probe-surface block N3–N5 landed between `we_vad_filter`'s closing
brace and the new-segment banner — and before that banner:

```cpp

// ---------------------------------------------------------------------------------------------
// 3.7 Workstream F: the two cost drivers Kotlin could not see. `ctxFrames` is the encoder audio
// context actually used (the §4 cost driver: a 2.4 s utterance still pays the 512-frame floor),
// and `vadIn`/`vadOut` are we_vad_filter's before/after sample counts — `vadOut=0` with a
// `cut=vad` endpoint is the exact probe-vs-batch-filter disagreement signature.
//
// Process-global, written inside whisper_full's own JNI frame, which NativeComputeGate has
// serialized process-wide. The Kotlin side snapshots them INSIDE that same gate hold and tags the
// snapshot with its ctx, so a batch chunk interleaving afterwards cannot be misread as a bubble
// segment's numbers. Diagnostics only: never read for a decision.
// ---------------------------------------------------------------------------------------------
extern "C" JNIEXPORT jintArray JNICALL
Java_com_whispereverywhere_whisper_WhisperNative_lastSegmentStats(
        JNIEnv *env, jobject /* this */) {
    jint values[3] = {
        static_cast<jint>(g_last_ctx_frames.load(std::memory_order_relaxed)),
        static_cast<jint>(g_last_vad_in.load(std::memory_order_relaxed)),
        static_cast<jint>(g_last_vad_out.load(std::memory_order_relaxed)),
    };
    jintArray out = env->NewIntArray(3);
    if (out == nullptr) {
        return nullptr;
    }
    env->SetIntArrayRegion(out, 0, 3, values);
    return out;
}
```

- [ ] **Step 2: Run it, expected failure** — the CMake target builds inside `assembleDebug`:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:assembleDebug --no-daemon
```

Expected: `BUILD FAILED`, with clang reporting
`whisper_jni.cpp:...: error: use of undeclared identifier 'g_last_ctx_frames'` (and the same for
`g_last_vad_in`, `g_last_vad_out`).

- [ ] **Step 3: Minimal implementation** —

(a) `whisper_jni.cpp:15-17` — add a WE-DIAG print macro beside the existing two. Replace:

```cpp
#define LOG_TAG "whisper_jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
```

with:

```cpp
#define LOG_TAG "whisper_jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
// The owner's acceptance greps are `adb logcat -s WE-DIAG`. A native line that belongs to that
// story must carry that tag, or it is invisible to the capture that is supposed to answer for it.
#define LOGDIAG(...) __android_log_print(ANDROID_LOG_INFO, "WE-DIAG", __VA_ARGS__)
```

(b) `whisper_jni.cpp:126-128` — declare the three counters beside the VAD statics. Replace:

```cpp
static std::mutex             g_vad_mutex;
static whisper_vad_context  * g_vad_ctx = nullptr;
static std::string            g_vad_path;
```

with:

```cpp
static std::mutex             g_vad_mutex;
static whisper_vad_context  * g_vad_ctx = nullptr;
static std::string            g_vad_path;

// 3.7 Workstream F cost counters for the LAST transcribeRaw; see lastSegmentStats below.
static std::atomic<int>       g_last_ctx_frames{0};
static std::atomic<int>       g_last_vad_in{0};
static std::atomic<int>       g_last_vad_out{0};
```

(c) `whisper_jni.cpp:179` — record the filter's before/after and move the line to WE-DIAG. The
format string is deliberately UNCHANGED, so any existing grep for `VAD: ` still matches; only the
tag moves. Replace:

```cpp
    LOGI("VAD: %zu -> %zu samples (%d segments)", pcm.size(), filtered.size(), nseg);
```

with:

```cpp
    g_last_vad_in.store(static_cast<int>(pcm.size()), std::memory_order_relaxed);
    g_last_vad_out.store(static_cast<int>(filtered.size()), std::memory_order_relaxed);
    // Tag moved whisper_jni -> WE-DIAG (3.7 F); the TEXT is byte-identical so existing greps hold.
    LOGDIAG("VAD: %zu -> %zu samples (%d segments)", pcm.size(), filtered.size(), nseg);
```

(d) `whisper_jni.cpp:273` — reset all three at the top of every real transcribe, so a stale
value can never be reported. Insert immediately before `const jsize n = env->GetArrayLength(samples);`
(`:274`):

```cpp
    // Reset the F counters for THIS call: the early returns below (VAD found zero speech, energy
    // gate) never reach the audio_ctx block, and reporting a previous segment's ctxFrames there
    // would make an encoder-free commit look like it paid for one. ctxFrames=0 means "whisper_full
    // never ran"; vadIn=0 vadOut=0 means "no VAD ran at all".
    g_last_ctx_frames.store(0, std::memory_order_relaxed);
    g_last_vad_in.store(0, std::memory_order_relaxed);
    g_last_vad_out.store(0, std::memory_order_relaxed);

```

(e) `whisper_jni.cpp:382` — publish the computed context. Replace:

```cpp
        params.audio_ctx = neededFrames;
```

with:

```cpp
        params.audio_ctx = neededFrames;
        g_last_ctx_frames.store(neededFrames, std::memory_order_relaxed);
```

(f) `WhisperNative.kt` — add the extern immediately after `setAudioCtxFloor` (`:113`), before
`free` (`:115-116`):

```kotlin
    /**
     * Cost counters for the LAST [transcribeRaw] in this process, as
     * `[ctxFrames, vadInSamples, vadOutSamples]` (3.7 Workstream F).
     *
     * - `ctxFrames` — the encoder audio context actually used. 0 means whisper_full never ran
     *   (the VAD found zero speech, or the energy gate fired). This is the §4 cost driver and was
     *   entirely invisible from Kotlin before 3.7.
     * - `vadInSamples` / `vadOutSamples` — we_vad_filter's before/after sample counts. Both 0
     *   means no VAD ran; `vadIn > 0` with `vadOut == 0` is the probe-vs-batch-filter
     *   disagreement, which is the one thing `cut=vad` cannot tell you on its own.
     *
     * PROCESS-GLOBAL, like [detectedLanguage]: only meaningful when read on the same thread that
     * just ran the transcribe, while that thread still holds NativeComputeGate. WhisperNativeBackend
     * snapshots it inside the gate and tags the snapshot with its ctx; nothing else may call it.
     * Diagnostics only — never read for a decision.
     */
    external fun lastSegmentStats(): IntArray
```

- [ ] **Step 4: Run tests green** —

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:assembleDebug --no-daemon; if ($?) { .\gradlew.bat :app:testDebugUnitTest --no-daemon }
```

Evidence: `assembleDebug` reports `BUILD SUCCESSFUL` (the C++ error is gone — the native gate for
this task), and the full unit-test XML directory
`C:/Users/bastr/.androidbuild/WhisperEverywhere/app/test-results/testDebugUnitTest` reports zero
failures across all classes.

- [ ] **Step 5: Commit** —

```powershell
git add app/src/main/cpp/whisper_jni.cpp app/src/main/java/com/whispereverywhere/whisper/WhisperNative.kt; git commit -m @'
feat(native): expose ctxFrames/vadIn/vadOut, and tag the vad-filter line WE-DIAG

ctxFrames is the encoder cost driver 3.7's cadence arithmetic turns on and was invisible from
Kotlin. vadIn/vadOut make the probe-vs-batch-filter disagreement (cut=vad with vadOut=0)
observable instead of a silent EmptyExpected. The VAD filter line keeps its text byte for byte;
only its tag moves to WE-DIAG so the owner acceptance grep can see it.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT
'@
```

---

### Task F4: `NativeSegmentStats` + the backend seam

**Files:**
- Modify `app/src/test/java/com/whispereverywhere/transcription/WhisperBackendSeamTest.kt`
  (`:12-33`)
- Modify `app/src/main/java/com/whispereverywhere/transcription/TranscriptionEngine.kt` —
  `interface WhisperBackend` at `:85-128`; `WhisperNativeBackend.transcribeInternal` at `:283-308`

**Interfaces:**
- Consumes: `WhisperNative.lastSegmentStats(): IntArray` (Task F3).
- Produces:
  - `data class NativeSegmentStats(val ctxFrames: Int, val vadInSamples: Int, val vadOutSamples: Int)`
    in `com.whispereverywhere.transcription`
  - `WhisperBackend.lastSegmentStats(ctx: Long): NativeSegmentStats? = null` (defaulted, so every
    existing fake compiles and behaves exactly as before)

- [ ] **Step 1: Write the failing test** — append to `WhisperBackendSeamTest.kt`, inside the class:

```kotlin
    @Test
    fun lastSegmentStats_defaultsToNull_soTheTimingLineDegradesInsteadOfLying() {
        // A backend with no native counters must report NOTHING, not zeros: `ctxFrames=0` is a
        // real and meaningful reading (whisper_full never ran), so a fake must not forge it.
        assertNull(MinimalBackend().lastSegmentStats(1L))
    }

    @Test
    fun nativeSegmentStats_carriesTheThreeNativeCounters() {
        val s = NativeSegmentStats(ctxFrames = 512, vadInSamples = 48_000, vadOutSamples = 32_000)
        assertEquals(512, s.ctxFrames)
        assertEquals(48_000, s.vadInSamples)
        assertEquals(32_000, s.vadOutSamples)
    }
```

- [ ] **Step 2: Run it, expected failure** —

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests "com.whispereverywhere.transcription.WhisperBackendSeamTest" --no-daemon
```

Expected: `e: ...WhisperBackendSeamTest.kt: Unresolved reference: lastSegmentStats` and
`e: ...WhisperBackendSeamTest.kt: Unresolved reference: NativeSegmentStats`.

- [ ] **Step 3: Minimal implementation** —

(a) `TranscriptionEngine.kt:83` — insert the data class immediately before
`/** Thin seam over the native layer ... */` (`:84`):

```kotlin
/**
 * The native cost counters for one completed transcribe (3.7 Workstream F), read back through the
 * backend seam so [SegmentTiming] can name them without Kotlin guessing.
 *
 * `ctxFrames == 0` is a real reading, not a missing one: it means whisper_full never ran (the VAD
 * found zero speech, or the energy gate fired). "Unknown" is expressed by a null
 * [WhisperBackend.lastSegmentStats], never by zeros.
 */
data class NativeSegmentStats(
    val ctxFrames: Int,
    val vadInSamples: Int,
    val vadOutSamples: Int,
)

```

(b) `TranscriptionEngine.kt:127` — add the defaulted member to `WhisperBackend`, immediately
before `fun release(ctx: Long)`:

```kotlin
    /**
     * Cost counters for the LAST transcribe THIS backend ran on [ctx], or null when the backend
     * has none (every fake, and any future non-native backend). Default null so existing fakes
     * keep 3.6.0 behaviour byte for byte — the timing line simply omits the fields.
     *
     * Called by [LocalWhisperEngine.runSegment] immediately after its transcribe returns, on the
     * same single native-executor thread. Diagnostics only.
     */
    fun lastSegmentStats(ctx: Long): NativeSegmentStats? = null

```

(c) `TranscriptionEngine.kt:283-308` — `WhisperNativeBackend.transcribeInternal`, plus two fields
and the override. Replace the whole block from the `// The one place the gate...` comment through
the closing brace of `transcribeInternal`:

```kotlin
    // 3.7 Workstream F: the native counters are process-global, so they are snapshotted INSIDE the
    // gate hold that produced them and tagged with the ctx that ran. A batch chunk interleaving
    // after we release the gate therefore cannot be misread as a bubble segment's numbers.
    // Written in this order — stats first, tag last — so a reader that sees a matching tag is
    // guaranteed to see the stats that go with it.
    @Volatile private var lastStats: NativeSegmentStats? = null
    @Volatile private var lastStatsCtx: Long = 0L

    private fun captureStats(ctx: Long) {
        // runCatching: a diagnostic must never be able to fail a transcribe that already
        // succeeded (UnsatisfiedLinkError on a stale .so, OOM on the 3-int array).
        val v = runCatching { WhisperNative.lastSegmentStats() }.getOrNull() ?: return
        if (v.size < 3) return
        lastStats = NativeSegmentStats(ctxFrames = v[0], vadInSamples = v[1], vadOutSamples = v[2])
        lastStatsCtx = ctx
    }

    override fun lastSegmentStats(ctx: Long): NativeSegmentStats? =
        if (ctx != 0L && ctx == lastStatsCtx) lastStats else null

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
            val text = WhisperNative.transcribe(
                ctx, samples, lang, translate = false, vadModelPath = vad, onNewSegment = onNewSegment
            )
            captureStats(ctx)
            return@serialized text
        }
        GpuPolicy.onGpuComputeStarting()
        var ok = false
        try {
            val text = WhisperNative.transcribe(
                ctx, samples, lang, translate = false, vadModelPath = vad, onNewSegment = onNewSegment
            )
            ok = true
            captureStats(ctx)   // AFTER ok = true: the sentinel's verdict is about the transcribe
            text
        } finally {
            GpuPolicy.onGpuComputeFinished(ok)
        }
    }
```

- [ ] **Step 4: Run tests green** —

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests "com.whispereverywhere.transcription.*" --no-daemon
```

Evidence: `TEST-com.whispereverywhere.transcription.WhisperBackendSeamTest.xml` shows
`tests="4" failures="0"`, and no other class in that directory regressed.

- [ ] **Step 5: Commit** —

```powershell
git add app/src/main/java/com/whispereverywhere/transcription/TranscriptionEngine.kt app/src/test/java/com/whispereverywhere/transcription/WhisperBackendSeamTest.kt; git commit -m @'
feat(diag): NativeSegmentStats through the backend seam, snapshotted inside the gate

The native counters are process-global, so the snapshot is taken inside the same
NativeComputeGate hold that produced them and tagged with the ctx that ran - an interleaved
batch chunk can never be misread as a bubble segment. Default null on the interface: every
existing fake keeps 3.6.0 behaviour, and the timing line omits the fields rather than forging
zeros (ctxFrames=0 is a real reading).

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT
'@
```

---

### Task F5: `segment-timing` trailing fields — `vadIn= vadOut= ctxFrames=`

**Files:**
- Modify `app/src/test/java/com/whispereverywhere/transcription/SegmentTimingTest.kt` (append tests)
- Modify `app/src/main/java/com/whispereverywhere/transcription/SegmentTiming.kt` — `line()` and
  the KDoc sample at `:11` (post-F2 shape)

**Interfaces:**
- Consumes: `NativeSegmentStats` (Task F4).
- Produces: `SegmentTiming.line(seq: Long, audioMs: Long, transcribeMs: Long, stats: NativeSegmentStats? = null): String`
  → `"segment-timing: seq=<n> audio=<ms> transcribe=<ms> rtf=<x.xx>[ vadIn=<n> vadOut=<n> ctxFrames=<n>]"`.

**Contract:** the new fields are APPENDED and OPTIONAL. With `stats = null` the line is
byte-identical to Task F2's, which is what keeps the six pinned assertions green unchanged.

- [ ] **Step 1: Write the failing test** — append to `SegmentTimingTest.kt`, inside the class:

```kotlin
    @Test
    fun nativeStatsAreAppendedAfterRtf_neverInterleaved() {
        assertEquals(
            "segment-timing: seq=4 audio=2400 transcribe=1100 rtf=0.46 " +
                "vadIn=38400 vadOut=32000 ctxFrames=512",
            SegmentTiming.line(
                seq = 4L,
                audioMs = 2_400L,
                transcribeMs = 1_100L,
                stats = NativeSegmentStats(ctxFrames = 512, vadInSamples = 38_400, vadOutSamples = 32_000),
            ),
        )
    }

    @Test
    fun withoutNativeStatsTheLineIsByteIdenticalToTheSeqOnlyForm() {
        // The 6 pinned assertions above pass `stats` implicitly as null. This states WHY that is
        // safe: a backend with no counters must produce the exact same bytes as before.
        assertEquals(
            SegmentTiming.line(seq = 4L, audioMs = 2_400L, transcribeMs = 1_100L),
            SegmentTiming.line(seq = 4L, audioMs = 2_400L, transcribeMs = 1_100L, stats = null),
        )
    }

    @Test
    fun aVadThatFoundNoSpeechReportsVadOutZeroAndCtxFramesZero() {
        // The probe-vs-batch-filter disagreement signature: the endpointer said "utterance ended,
        // commit" and we_vad_filter found nothing, so whisper_full never ran.
        assertEquals(
            "segment-timing: seq=9 audio=1000 transcribe=40 rtf=0.04 " +
                "vadIn=16000 vadOut=0 ctxFrames=0",
            SegmentTiming.line(
                seq = 9L,
                audioMs = 1_000L,
                transcribeMs = 40L,
                stats = NativeSegmentStats(ctxFrames = 0, vadInSamples = 16_000, vadOutSamples = 0),
            ),
        )
    }
```

- [ ] **Step 2: Run it, expected failure** —

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests "com.whispereverywhere.transcription.SegmentTimingTest" --no-daemon
```

Expected: `e: ...SegmentTimingTest.kt: Cannot find a parameter with this name: stats`
(three occurrences).

- [ ] **Step 3: Minimal implementation** —

(a) `SegmentTiming.kt:11` — update the KDoc sample. Replace:

```
 *     segment-timing: seq=<n> audio=<ms> transcribe=<ms> rtf=<x.xx>
```

with:

```
 *     segment-timing: seq=<n> audio=<ms> transcribe=<ms> rtf=<x.xx> vadIn=<n> vadOut=<n> ctxFrames=<n>
```

(b) `SegmentTiming.kt` — replace the `line` function written in Task F2:

```kotlin
    /**
     * The line itself. A zero/negative [audioMs] (degenerate commit) reports rtf=0.00 instead of
     * dividing by zero. Locale.US so the decimal separator is always a point, never a comma.
     *
     * [seq] is PREPENDED, not appended and not emitted as a sibling line (3.7 Workstream F): the
     * pre-3.7 substring `audio=… transcribe=… rtf=…` survives byte-identically, so every
     * `findstr segment-timing` grep and every parser written against 3.6.0 keeps working, while a
     * capture can now be joined against `endpoint:` / `perceived:` on the shared seq — which is
     * what makes "why was this segment cut and how long did the user wait" answerable from one log.
     *
     * [stats] is APPENDED and OPTIONAL, for the same compatibility reason from the other end: a
     * backend with no native counters (every fake, any future non-native backend) emits exactly
     * the seq-only form. `ctxFrames` is the encoder cost driver 3.7's cadence arithmetic turns on;
     * `vadIn`/`vadOut` instrument the rare probe-vs-batch-filter disagreement (`cut=vad` with
     * `vadOut=0`), which otherwise surfaces only as a silent EmptyExpected.
     */
    fun line(
        seq: Long,
        audioMs: Long,
        transcribeMs: Long,
        stats: NativeSegmentStats? = null,
    ): String {
        val rtf = if (audioMs > 0) transcribeMs.toDouble() / audioMs.toDouble() else 0.0
        val head = "segment-timing: seq=$seq audio=$audioMs transcribe=$transcribeMs rtf=" +
            String.format(Locale.US, "%.2f", rtf)
        return if (stats == null) {
            head
        } else {
            head + " vadIn=${stats.vadInSamples} vadOut=${stats.vadOutSamples}" +
                " ctxFrames=${stats.ctxFrames}"
        }
    }
```

- [ ] **Step 4: Run tests green** —

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests "com.whispereverywhere.transcription.SegmentTimingTest" --no-daemon
```

Evidence: `TEST-com.whispereverywhere.transcription.SegmentTimingTest.xml` shows
`tests="11" failures="0"` — the six pinned assertions from Task F2 still green, unchanged.

- [ ] **Step 5: Commit** —

```powershell
git add app/src/main/java/com/whispereverywhere/transcription/SegmentTiming.kt app/src/test/java/com/whispereverywhere/transcription/SegmentTimingTest.kt; git commit -m @'
feat(diag): append vadIn/vadOut/ctxFrames to segment-timing, optional and last

Appended, never interleaved, and omitted entirely when the backend has no counters - so the
6 assertions pinned in the seq commit stay green unchanged and 3.6.0 parsers reading the first
three fields are untouched. ctxFrames is the encoder cost driver; vadOut=0 under a cut=vad
endpoint is the probe-vs-batch-filter disagreement made visible.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT
'@
```

---

### Task F6: LocalWhisperEngine reads the stats and emits the full line

**Files:**
- Create `app/src/test/java/com/whispereverywhere/transcription/LocalWhisperEngineStatsTest.kt`
- Modify `app/src/main/java/com/whispereverywhere/transcription/LocalWhisperEngine.kt` — the
  timing emit at `:321-332` (post-F2 shape)

**Interfaces:**
- Consumes: `WhisperBackend.lastSegmentStats(ctx: Long): NativeSegmentStats?` (Task F4),
  `SegmentTiming.line(seq, audioMs, transcribeMs, stats)` (Task F5).
- Produces: no new symbols. Behaviour: exactly one `lastSegmentStats(ctx)` query per segment,
  after the transcribe that produced it.

**Executor discipline:** this test uses a REAL `Executors.newSingleThreadExecutor()`, not the
package's `SameThreadExecutorService` — the property under test is ordering across the engine's
background thread, and a same-thread stub cannot express it.

- [ ] **Step 1: Write the failing test** — create
`app/src/test/java/com/whispereverywhere/transcription/LocalWhisperEngineStatsTest.kt`:

```kotlin
package com.whispereverywhere.transcription

import com.whispereverywhere.util.RetryPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 3.7 Workstream F: the engine must read the native cost counters for the segment it just ran,
 * exactly once, AFTER the transcribe that produced them.
 *
 * REAL single-thread executor throughout — the ordering property lives on the engine's background
 * thread, and a same-thread stub would prove nothing about it.
 */
class LocalWhisperEngineStatsTest {

    private val pcm = byteArrayOf(0x10, 0x00, 0x20, 0x00)

    private fun fastRetry() = RetryPolicy(maxAttempts = 1, baseDelayMs = 0, maxDelayMs = 0, rng = { 0.0 })

    private class PathProvider(private val path: String?) : ModelPathProvider {
        override fun installedModelPath(): String? = path
    }

    /** Records the ORDER of native touches so "stats after transcribe" is provable, not assumed. */
    private class OrderRecordingBackend : WhisperBackend {
        val events: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
        override fun load(modelPath: String): Long = 42L
        override fun transcribe(ctx: Long, samples: FloatArray, lang: String?, useVad: Boolean): String {
            events += "transcribe(ctx=$ctx)"
            return " hello"
        }
        override fun lastSegmentStats(ctx: Long): NativeSegmentStats? {
            events += "stats(ctx=$ctx)"
            return NativeSegmentStats(ctxFrames = 512, vadInSamples = 38_400, vadOutSamples = 32_000)
        }
        override fun release(ctx: Long) = Unit
    }

    private class LatchListener(resolutions: Int) : TranscriptionEngine.Listener {
        val done = CountDownLatch(resolutions)
        val opened = CountDownLatch(1)
        val resolved: MutableList<Long> = java.util.Collections.synchronizedList(mutableListOf())
        override fun onOpen() { opened.countDown() }
        override fun onDelta(text: String) = Unit
        override fun onSegmentResolved(seq: Long, outcome: SegmentOutcome) {
            resolved += seq
            done.countDown()
        }
        override fun onError(message: String) = Unit
        override fun onClosed() = Unit
    }

    @Test
    fun statsAreQueriedOncePerSegment_afterTheTranscribeThatProducedThem() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val backend = OrderRecordingBackend()
            val engine = LocalWhisperEngine(
                modelPathProvider = PathProvider("/models/pro.bin"),
                retry = fastRetry(),
                backend = backend,
                executor = executor,
            )
            val listener = LatchListener(resolutions = 1)
            engine.connect(language = "en", listener = listener)
            assertTrue("engine never opened", listener.opened.await(5, TimeUnit.SECONDS))

            engine.sendAudio(pcm)
            engine.commit()
            assertTrue("segment never resolved", listener.done.await(5, TimeUnit.SECONDS))

            // The ORDER is the contract: the counters describe the call that just finished, so a
            // read before it would report the PREVIOUS segment's encoder cost.
            assertEquals(listOf("transcribe(ctx=42)", "stats(ctx=42)"), backend.events)
            assertEquals(listOf(0L), listener.resolved)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun everySegmentGetsItsOwnStatsQuery_neverOneForTheWholeSession() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val backend = OrderRecordingBackend()
            val engine = LocalWhisperEngine(
                modelPathProvider = PathProvider("/models/pro.bin"),
                retry = fastRetry(),
                backend = backend,
                executor = executor,
            )
            val listener = LatchListener(resolutions = 3)
            engine.connect(language = "en", listener = listener)
            assertTrue(listener.opened.await(5, TimeUnit.SECONDS))

            repeat(3) { engine.sendAudio(pcm); engine.commit() }
            assertTrue("segments never drained", listener.done.await(5, TimeUnit.SECONDS))

            assertEquals(
                listOf(
                    "transcribe(ctx=42)", "stats(ctx=42)",
                    "transcribe(ctx=42)", "stats(ctx=42)",
                    "transcribe(ctx=42)", "stats(ctx=42)",
                ),
                backend.events,
            )
            assertEquals(listOf(0L, 1L, 2L), listener.resolved)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun aBackendWithNoCountersStillResolvesNormally() {
        // The default lastSegmentStats returns null; the engine must log a stats-free line and
        // resolve exactly as it did in 3.6.0.
        val executor = Executors.newSingleThreadExecutor()
        try {
            val backend = object : WhisperBackend {
                override fun load(modelPath: String): Long = 7L
                override fun transcribe(ctx: Long, samples: FloatArray, lang: String?, useVad: Boolean) = " plain"
                override fun release(ctx: Long) = Unit
            }
            val engine = LocalWhisperEngine(
                modelPathProvider = PathProvider("/models/pro.bin"),
                retry = fastRetry(),
                backend = backend,
                executor = executor,
            )
            val listener = LatchListener(resolutions = 1)
            engine.connect(language = "en", listener = listener)
            assertTrue(listener.opened.await(5, TimeUnit.SECONDS))

            engine.sendAudio(pcm)
            engine.commit()
            assertTrue(listener.done.await(5, TimeUnit.SECONDS))
            assertEquals(listOf(0L), listener.resolved)
        } finally {
            executor.shutdownNow()
        }
    }
}
```

- [ ] **Step 2: Run it, expected failure** —

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests "com.whispereverywhere.transcription.LocalWhisperEngineStatsTest" --no-daemon
```

Expected: the first two tests fail with
`java.lang.AssertionError: expected:<[transcribe(ctx=42), stats(ctx=42)]> but was:<[transcribe(ctx=42)]>`
— the engine does not query the counters yet. The third test passes already (it pins the
unchanged path).

- [ ] **Step 3: Minimal implementation** — `LocalWhisperEngine.kt:321-332`, replace the timing
emit written in Task F2:

```kotlin
                // Permanent per-segment RTF instrumentation (3.6.0, Workstream A3; extended by
                // 3.7 Workstream F with seq and the native cost counters): the number the
                // tier-consolidation, GPU and cadence decision gates read, measured on the owner's
                // device instead of estimated. Includes retry time deliberately — it is the wall
                // cost the user actually paid for this segment. Numbers only, never transcript
                // content. Grep "segment-timing:".
                //
                // transcribeMs is taken BEFORE the counters are read, so the diagnostic query can
                // never inflate the number it is annotating. lastSegmentStats describes the call
                // that just returned and is null for any backend without native counters, in
                // which case the line degrades to the seq-only form rather than forging zeros.
                val transcribeMs = (System.nanoTime() - transcribeStartNs) / 1_000_000
                val nativeStats = backend.lastSegmentStats(ctx)
                android.util.Log.i(
                    "WE-DIAG",
                    SegmentTiming.line(
                        seq = seq,
                        audioMs = SegmentTiming.audioMs(samples.size),
                        transcribeMs = transcribeMs,
                        stats = nativeStats,
                    ),
                )
```

- [ ] **Step 4: Run tests green** —

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests "com.whispereverywhere.transcription.*" --no-daemon
```

Evidence: `TEST-com.whispereverywhere.transcription.LocalWhisperEngineStatsTest.xml` shows
`tests="3" failures="0"`, and `LocalWhisperEngineTest` / `LocalWhisperEngineStreamingTest` /
`LocalWhisperEnginePinTest` XMLs are unchanged and green.

- [ ] **Step 5: Commit** —

```powershell
git add app/src/main/java/com/whispereverywhere/transcription/LocalWhisperEngine.kt app/src/test/java/com/whispereverywhere/transcription/LocalWhisperEngineStatsTest.kt; git commit -m @'
feat(diag): engine emits seq + native cost counters on every segment-timing line

transcribeMs is taken before the counters are queried so the diagnostic cannot inflate the
number it annotates. The test drives a REAL single-thread executor and pins the ORDER
(transcribe then stats) - a read before the call would report the previous segment's encoder
cost, which is exactly the confusion ctxFrames exists to end.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT
'@
```

## Workstream D (seam prerequisites) — SpeechSegmenter collapse + the Endpointer interface

---

### Task D1: Collapse `SpeechSegmenter`'s dead 15 s clock

**Files:**
- Modify `app/src/main/java/com/whispereverywhere/util/SpeechSegmenter.kt` (KDoc :14, ctor param :29, branch :48–49)
- Modify `app/src/test/java/com/whispereverywhere/SpeechSegmenterTest.kt` (helper :10–15, test :39–48)

**Interfaces:**
- Consumes: `SegmentCapPolicy.MAX_SEGMENT_WALL_MS = 15_000L` (`service/SegmentCapPolicy.kt:57`) — the one surviving wall clock.
- Produces: `class SpeechSegmenter(voiceThreshold: Int = 500, silenceThreshold: Int = 250, pauseMs: Long = 800)` — a THREE-parameter constructor. `onAmplitude(amplitude: Int, nowMs: Long): Boolean`, `hasPendingSpeech(): Boolean`, `reset()` unchanged.

- [ ] **Step 1: Write the failing test.** Replace the whole of `app/src/test/java/com/whispereverywhere/SpeechSegmenterTest.kt` with:
```kotlin
package com.whispereverywhere

import com.whispereverywhere.util.SpeechSegmenter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechSegmenterTest {

    private fun segmenter() = SpeechSegmenter(
        voiceThreshold = 1000,
        silenceThreshold = 500,
        pauseMs = 700,
    )

    @Test
    fun silence_without_prior_speech_never_commits() {
        val s = segmenter()
        assertFalse(s.onAmplitude(0, 0))
        assertFalse(s.onAmplitude(100, 500))
        assertFalse(s.onAmplitude(0, 5000))
        assertFalse(s.hasPendingSpeech())
    }

    @Test
    fun speech_then_long_pause_commits_once() {
        val s = segmenter()
        assertFalse(s.onAmplitude(5000, 0))      // speaking
        assertFalse(s.onAmplitude(5000, 200))    // still speaking
        assertFalse(s.onAmplitude(100, 400))     // brief quiet, not long enough
        // quiet long enough after last voice (200 .. 200+700 = 900)
        assertTrue(s.onAmplitude(100, 950))      // pause ends segment -> commit
        // after commit, state resets; further silence does nothing
        assertFalse(s.onAmplitude(100, 1100))
        assertFalse(s.hasPendingSpeech())
    }

    @Test
    fun continuousSpeech_neverSelfCommits_theWallCapOwnsThatClock() {
        // 3.7: the segmenter's own maxSegmentMs was a DEAD, differently-anchored duplicate of
        // SegmentCapPolicy.MAX_SEGMENT_WALL_MS (segment-start anchor vs last-commit anchor). Two
        // disagreeing wall clocks is how the next diagnosis gets confusing, so there is now
        // exactly one — and it lives at the call site's `else if`, not in here.
        val s = segmenter()
        var t = 0L
        while (t <= 20_000) { assertFalse(s.onAmplitude(5000, t)); t += 500 }
        // A quiet sample far past the OLD 15 s duplicate, but only 100 ms after the last voice:
        // the pause condition is unmet, so this segmenter must stay silent and let the cap fire.
        assertFalse(
            "SpeechSegmenter must no longer own a wall clock — SegmentCapPolicy does",
            s.onAmplitude(100, 20_100),
        )
        assertTrue("the segment is still open", s.hasPendingSpeech())
    }

    @Test
    fun short_quiet_dip_does_not_commit() {
        val s = segmenter()
        s.onAmplitude(5000, 0)
        // a single quiet sample 100ms after voice — far below pauseMs
        assertFalse(s.onAmplitude(100, 100))
        assertTrue(s.hasPendingSpeech())
    }
}
```

- [ ] **Step 2: Run it, expect the wall-clock assertion to fire.**
```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests "com.whispereverywhere.SpeechSegmenterTest" --no-daemon
```
Expected: `continuousSpeech_neverSelfCommits_theWallCapOwnsThatClock FAILED` with
`java.lang.AssertionError: SpeechSegmenter must no longer own a wall clock — SegmentCapPolicy does`
(at t=20 100 the old `segmentTooLong` branch returns true).

- [ ] **Step 3: Minimal implementation.** In `app/src/main/java/com/whispereverywhere/util/SpeechSegmenter.kt`, delete the `maxSegmentMs` parameter (line 29) and its `@param` KDoc line (line 14), and delete the `segmentTooLong` term. The KDoc paragraph at :3–9 and the branch at :47–52 become:
```kotlin
/**
 * Decides when to commit the realtime audio buffer for gpt-realtime-whisper,
 * which has no server-side turn detection. Pure logic (no Android deps) so it
 * is unit-testable: feed it amplitude samples via [onAmplitude] and it returns
 * true when a commit should fire — after a natural pause following speech.
 *
 * ONE wall clock, and it is not here (3.7, Workstream D6): this class used to carry its own
 * `maxSegmentMs = 15000` anchored at first-voice-sample, a dead duplicate of
 * [com.whispereverywhere.service.SegmentCapPolicy.MAX_SEGMENT_WALL_MS] anchored at last-commit.
 * It was provably unreachable in both real cases (loud audio returns early above; mid-floor audio
 * always trips the cap-policy clock first) and is now removed, so the cap `else if` at the call
 * site is the only wall-clock backstop in the system.
 *
 * @param voiceThreshold amplitude (0..32767) at/above which we consider speech present
 * @param silenceThreshold amplitude at/below which we consider it quiet
 * @param pauseMs quiet duration after speech that ends a segment
 */
```
and
```kotlin
        val pausedLongEnough = amplitude <= silenceThreshold && (nowMs - lastVoiceMs) >= pauseMs
        if (pausedLongEnough) {
            reset()
            return true
        }
        return false
```
Keep `segmentStartMs` deleted too — the field, its write at :40, and its clear in `reset()`:
```kotlin
    private var hasSpoken = false
    private var lastVoiceMs = 0L

    /** @return true when the caller should commit the buffer now. */
    fun onAmplitude(amplitude: Int, nowMs: Long): Boolean {
        if (amplitude >= voiceThreshold) {
            hasSpoken = true
            lastVoiceMs = nowMs
            return false
        }
        if (!hasSpoken) return false
        ...
    }

    /** True if speech has been detected since the last commit/reset. */
    fun hasPendingSpeech(): Boolean = hasSpoken

    fun reset() {
        hasSpoken = false
        lastVoiceMs = 0L
    }
```
(The `if (!hasSpoken) { hasSpoken = true; segmentStartMs = nowMs }` guard collapses to a plain
`hasSpoken = true` once `segmentStartMs` is gone — same observable behaviour.)

- [ ] **Step 4: Run tests green.**
```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon
```
Then the aggregation command from Global Constraints. Expected: `failures=0 errors=0` and a **+0 delta** for this task — one existing test is replaced by its inverse, none is added.

- [ ] **Step 5: Commit.**
```powershell
git add app/src/main/java/com/whispereverywhere/util/SpeechSegmenter.kt app/src/test/java/com/whispereverywhere/SpeechSegmenterTest.kt; git commit -m @'
refactor(vad): one wall clock — SpeechSegmenter's dead 15s duplicate is gone

maxSegmentMs anchored at first-voice-sample duplicated SegmentCapPolicy's
MAX_SEGMENT_WALL_MS anchored at last-commit, and was unreachable in both real
cases: loud audio returns early above the branch, mid-floor audio always trips
the cap-policy clock first. The cap `else if` at the call site is now the only
wall-clock backstop in the system.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT
'@
```

---

### Task D2: The `Endpointer` interface + `AmplitudeEndpointer`

**Files:**
- Create `app/src/main/java/com/whispereverywhere/audio/Endpointer.kt`
- Create `app/src/main/java/com/whispereverywhere/audio/AmplitudeEndpointer.kt`
- Create `app/src/test/java/com/whispereverywhere/audio/AmplitudeEndpointerTest.kt`

**Interfaces:**
- Consumes: `com.whispereverywhere.util.SpeechSegmenter(voiceThreshold, silenceThreshold, pauseMs)` (Task D1).
- Produces:
  - `interface Endpointer` with THREE abstract members — `onFrame(chunk: ByteArray, amp: Int, nowMs: Long): Boolean`, `hasPendingSpeech(): Boolean`, `reset()` — plus three DEFAULTED extension points: `onSessionStart(nowMs: Long, minCommitIntervalMs: Long)`, `onSessionEnd()`, `pendingCutPointMs(): Long`.
  - `Endpointer.NO_CUT_POINT: Long = 0L`
  - `class AmplitudeEndpointer(segmenter: SpeechSegmenter = SpeechSegmenter()) : Endpointer`

**Why this task runs before Workstream C.** `SileroEndpointer` declares `: Endpointer` from birth
(Task C2), so the interface has to exist first — it is one 5-line file with no dependency on
anything C produces. The alternative (write the class bare, add the supertype in a later integration
task) was rejected: it leaves a whole workstream's worth of code whose conformance nothing checks
until the very end. Note also what is NOT on this interface: `lastCut()` and `isProbeCutout()` stay
concrete-class accessors on `SileroEndpointer` (Task C7/C8) — the diagnostic funnel reaches them with
an `as?`, and putting them here would oblige every implementor to fake a probe it does not have.

- [ ] **Step 1: Write the failing test.** Create `app/src/test/java/com/whispereverywhere/audio/AmplitudeEndpointerTest.kt`:
```kotlin
package com.whispereverywhere.audio

import com.whispereverywhere.util.SpeechSegmenter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 3.7 fallback contract, pinned: with no VAD model the service constructs an
 * [AmplitudeEndpointer], and its verdict stream must be INDISTINGUISHABLE from the
 * [SpeechSegmenter] the service called directly through 3.6.0. "Model missing" must be
 * byte-identical shipped behaviour, never a new code path.
 */
class AmplitudeEndpointerTest {

    /** (amplitude, wall-clock ms): speech, a mid-word dip, a real pause, a second utterance. */
    private val trace: List<Pair<Int, Long>> = listOf(
        0 to 0L, 120 to 32L, 5000 to 64L, 6000 to 96L, 300 to 128L, 4800 to 160L,
        200 to 192L, 100 to 500L, 80 to 900L, 60 to 1_000L,           // > 800 ms quiet -> commit
        40 to 1_032L, 5_200 to 2_000L, 4_900 to 2_032L, 260 to 2_064L,
        240 to 2_500L, 220 to 2_900L,                                  // second pause -> commit
        0 to 3_000L, 0 to 20_000L,                                     // long silence, no speech
    )

    private val chunk = ByteArray(1024) { 0x07 }

    @Test
    fun verdictStreamIsIdenticalToTheSpeechSegmenterItReplaces() {
        val segmenter = SpeechSegmenter()
        val endpointer = AmplitudeEndpointer()
        val expected = trace.map { (amp, t) -> segmenter.onAmplitude(amp, t) }
        val actual = trace.map { (amp, t) -> endpointer.onFrame(chunk, amp, t) }
        assertEquals(expected, actual)
        assertTrue("the trace must exercise at least one commit", expected.any { it })
    }

    @Test
    fun hasPendingSpeechTracksTheSegmenterStepForStep() {
        val segmenter = SpeechSegmenter()
        val endpointer = AmplitudeEndpointer()
        for ((amp, t) in trace) {
            segmenter.onAmplitude(amp, t)
            endpointer.onFrame(chunk, amp, t)
            assertEquals("at t=$t amp=$amp", segmenter.hasPendingSpeech(), endpointer.hasPendingSpeech())
        }
    }

    @Test
    fun resetClearsPendingSpeechExactlyAsTheSegmenterDoes() {
        val endpointer = AmplitudeEndpointer()
        endpointer.onFrame(chunk, 5_000, 0L)
        assertTrue(endpointer.hasPendingSpeech())
        endpointer.reset()
        assertFalse(endpointer.hasPendingSpeech())
        // and a reset segment does not commit on the next quiet frame
        assertFalse(endpointer.onFrame(chunk, 100, 5_000L))
    }

    @Test
    fun theAudioChunkIsIgnored_soAShortOrEmptyReadChangesNothing() {
        val withChunk = AmplitudeEndpointer()
        val withoutChunk = AmplitudeEndpointer()
        val empty = ByteArray(0)
        val a = trace.map { (amp, t) -> withChunk.onFrame(chunk, amp, t) }
        val b = trace.map { (amp, t) -> withoutChunk.onFrame(empty, amp, t) }
        assertEquals(a, b)
    }

    @Test
    fun theDefaultedExtensionPointsAreInertForTheAmplitudePath() {
        val plain = AmplitudeEndpointer()
        val poked = AmplitudeEndpointer()
        poked.onSessionStart(nowMs = 0L, minCommitIntervalMs = 6_000L)
        val a = trace.map { (amp, t) -> plain.onFrame(chunk, amp, t) }
        val b = trace.map { (amp, t) -> poked.onFrame(chunk, amp, t) }
        assertEquals("a cadence floor must not reach the amplitude path", a, b)
        // No micro-pause memory exists here, so the cap cut can never be offered a cut point —
        // which is what makes the 15 s backstop byte-identical to 3.6.0 on this path.
        assertEquals(Endpointer.NO_CUT_POINT, poked.pendingCutPointMs())
        poked.onFrame(chunk, 5_000, 30_000L)
        assertEquals(Endpointer.NO_CUT_POINT, poked.pendingCutPointMs())
        poked.onSessionEnd()
        assertEquals(Endpointer.NO_CUT_POINT, poked.pendingCutPointMs())
    }
}
```

- [ ] **Step 2: Run it, expect a compile failure.**
```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests "com.whispereverywhere.audio.AmplitudeEndpointerTest" --no-daemon
```
Expected: `> Task :app:compileDebugUnitTestKotlin FAILED` with
`e: ...AmplitudeEndpointerTest.kt:...: Unresolved reference: AmplitudeEndpointer` and
`Unresolved reference: Endpointer`.

- [ ] **Step 3: Minimal implementation.** Create `app/src/main/java/com/whispereverywhere/audio/Endpointer.kt`:
```kotlin
package com.whispereverywhere.audio

/**
 * The ONE commit-decision surface the capture path asks "should I cut the segment now?"
 * (3.7, Workstream D1).
 *
 * The seam it plugs into (FloatingBubbleService.onAudioChunk) is deliberately NOT restructured:
 * the wall-cap check stays the `else if` it already was, so a never-firing endpointer leaves cap
 * behaviour byte-identical to 3.6.0. Only the verdict inside the `if` changes hands.
 *
 * THREE abstract members — everything else is a defaulted extension point that
 * [AmplitudeEndpointer] deliberately does not override, which is exactly what makes the
 * model-missing fallback shipped behaviour rather than a new code path.
 *
 * Threading: [onFrame], [hasPendingSpeech], [pendingCutPointMs] and [reset] are all called from
 * the CAPTURE thread (StreamingAudioRecorder / PlaybackAudioCapturer), ~31.25 Hz, with one
 * exception — [reset] is additionally called from Main at switchSource / onOpen / stopRecording.
 * [onSessionStart] and [onSessionEnd] are Main-only. Implementations must be allocation-free and
 * lock-light on the [onFrame] path: it runs inline on the audio thread against a 32 ms budget.
 */
interface Endpointer {

    /**
     * One captured PCM16 chunk (mono, 16 kHz — nominally 512 samples / 1024 bytes, but `read()`
     * returns *up to* the buffer size, so a short chunk is legal and implementations must
     * accumulate rather than assume). [amp] is its RMS (0..32767), already computed by the
     * capture thread. [nowMs] is `System.currentTimeMillis()` at the call site.
     *
     * @return true when the caller should commit NOW. Returning true MUST leave this endpointer
     * in the same state [reset] would — including any native probe state — so the caller never
     * has to reset after a positive verdict.
     */
    fun onFrame(chunk: ByteArray, amp: Int, nowMs: Long): Boolean

    /**
     * True when speech has been detected since the last commit/reset. Its ONE consumer is the
     * LOCAL-silence re-arm in the wall-cap branch: a cap cut on genuinely silent audio re-arms
     * the 4 s first-cap window instead of consuming it.
     */
    fun hasPendingSpeech(): Boolean

    /** Drop all in-flight endpoint state, including any native recurrence state. */
    fun reset()

    /**
     * Session open (Main). [nowMs] is the session's wall-clock anchor (`sessionOpenMs` at the call
     * site) and [minCommitIntervalMs] is the MEASURED cost governor from
     * [com.whispereverywhere.service.CommitCadencePolicy] — the endpointer keeps cutting at real
     * pauses but merges utterances until the interval has elapsed. Default: no-op, so the
     * amplitude path is untouched by cadence.
     *
     * Cadence arrives HERE and not in a constructor because it is per SESSION, not per service: it
     * depends on the installed tier AND on whether every commit becomes a provider request, and
     * both can change between two sessions without the service being rebuilt. An implementation
     * that has not been given one yet must assume the expensive end
     * ([com.whispereverywhere.service.CommitCadencePolicy.MIN_COMMIT_INTERVAL_LARGE_MS]).
     *
     * An implementation that owns a native probe ARMS it here and initialises it lazily on the
     * first [onFrame] — i.e. on the capture thread, never on Main.
     */
    fun onSessionStart(nowMs: Long, minCommitIntervalMs: Long) {}

    /**
     * Session end (Main), called from stopRecording AFTER both capture sources have stopped and
     * JOINED their threads and after the unconditional stop flush. An implementation that owns a
     * native probe frees it here. Default: no-op.
     */
    fun onSessionEnd() {}

    /**
     * The endpointer's remembered micro-pause: the wall-clock ms of the most recent silence dip
     * inside the currently open stretch, or [NO_CUT_POINT] when none was observed.
     *
     * Read ONLY by the wall-cap branch. Silero's own answer to "speech forever"
     * (`max_speech_duration_s`) does not cut blind — it cuts at the last observed micro-pause —
     * and with `no_context = true` making a mid-word boundary permanently unrepairable, that is a
     * strictly better cut for the same latency bound. Default [NO_CUT_POINT]: no offer, so the
     * cap commits the whole buffer exactly as it does today.
     */
    fun pendingCutPointMs(): Long = NO_CUT_POINT

    companion object {
        /** "No micro-pause was observed in this stretch." */
        const val NO_CUT_POINT = 0L
    }
}
```
Create `app/src/main/java/com/whispereverywhere/audio/AmplitudeEndpointer.kt`:
```kotlin
package com.whispereverywhere.audio

import com.whispereverywhere.util.SpeechSegmenter

/**
 * The 3.6.0 amplitude segmenter, wearing the 3.7 [Endpointer] interface and nothing more
 * (Workstream D1/D7 tier 1).
 *
 * This is the fallback the service constructs whenever `VadModel.path()` returns null — the
 * existing "running without VAD" path, which already logs and already degrades gracefully. It
 * ignores [onFrame]'s `chunk`, overrides none of the interface's defaulted extension points, and
 * therefore offers no micro-pause cut point: a full session on this endpointer is byte-identical
 * to 3.6.0, which is the property the regression suite pins by running unchanged.
 */
class AmplitudeEndpointer(
    private val segmenter: SpeechSegmenter = SpeechSegmenter(),
) : Endpointer {

    override fun onFrame(chunk: ByteArray, amp: Int, nowMs: Long): Boolean =
        segmenter.onAmplitude(amp, nowMs)

    override fun hasPendingSpeech(): Boolean = segmenter.hasPendingSpeech()

    override fun reset() = segmenter.reset()
}
```

- [ ] **Step 4: Run tests green.**
```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests "com.whispereverywhere.audio.AmplitudeEndpointerTest" --no-daemon
```
Then the full suite + the aggregation command from Global Constraints. Expected: `failures=0 errors=0` and the **+5 delta** for this task (`AmplitudeEndpointerTest`).

- [ ] **Step 5: Commit.**
```powershell
git add app/src/main/java/com/whispereverywhere/audio/Endpointer.kt app/src/main/java/com/whispereverywhere/audio/AmplitudeEndpointer.kt app/src/test/java/com/whispereverywhere/audio/AmplitudeEndpointerTest.kt; git commit -m @'
feat(vad): the Endpointer seam + AmplitudeEndpointer, byte-identical to 3.6.0

Three abstract members (onFrame/hasPendingSpeech/reset) plus three defaulted
extension points (onSessionStart cadence, onSessionEnd probe teardown,
pendingCutPointMs micro-pause offer). AmplitudeEndpointer overrides none of the
defaults, so the model-missing fallback is the shipped path wearing an
interface — pinned verdict-for-verdict against SpeechSegmenter.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT
'@
```

## Workstream C — SileroEndpointer (C)

---

### Task C1: EndpointerTuning — every 3.7 endpointing constant in one JVM-pinned object

**Files:**
- Create: `app/src/main/java/com/whispereverywhere/audio/EndpointerTuning.kt`
- Test (create): `app/src/test/java/com/whispereverywhere/audio/EndpointerTuningTest.kt`

**Interfaces:**
- Consumes: nothing. Tier id strings are the verified literals in
  `app/src/main/java/com/whispereverywhere/model/WhisperModel.kt:65,77,88,98,111,121`
  (`"eco"`, `"base"`, `"pro"`, `"extreme"`, `"multi"`, `"ultra"`).
- Produces: `object com.whispereverywhere.audio.EndpointerTuning` with
  `const val FRAME_SAMPLES: Int = 512`, `FRAME_BYTES: Int = 1024`, `FRAME_MS: Long = 32L`,
  `NO_VERDICT: Float = -1.0f`, `ONSET_THRESHOLD: Float = 0.50f`, `RELEASE_THRESHOLD: Float = 0.35f`,
  `HANGOVER_MS: Long = 500L`, `MIN_SPEECH_MS: Long = 300L`, `MICRO_PAUSE_MS: Long = 98L`,
  `PROBE_BUDGET_MS: Long = 8L`, `PROBE_CUTOUT_FRAMES: Int = 16`.

**What is deliberately NOT here: the commit intervals.** The per-tier cost governor
(1200 / 6000 / 8000 / 3000 cloud) and `minCommitIntervalMs(tierId, isCloudBatch)` live in
`com.whispereverywhere.service.CommitCadencePolicy` (Task D3) and NOWHERE else. Two objects both
answering "how often may this tier commit" is how the log and the code start disagreeing, and the
cadence is not an acoustic knob: it is a per-session function of the installed tier and of whether
every commit becomes a provider request, handed to the endpointer at `onSessionStart`. This object
holds only what the state machine itself decides with — thresholds, durations, frame geometry, the
probe budget.

- [ ] **Step 1: Write the failing test.** Create
  `app/src/test/java/com/whispereverywhere/audio/EndpointerTuningTest.kt`:

```kotlin
package com.whispereverywhere.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The 3.7 tuning table (spec "Tuning constants") pinned verbatim. Every value here is a decision
 * with a written derivation; a silent edit is a behaviour change, so it fails this test first.
 */
class EndpointerTuningTest {

    @Test fun the_shipped_tuning_table_is_pinned_verbatim() {
        assertEquals(0.50f, EndpointerTuning.ONSET_THRESHOLD, 0.0f)
        assertEquals(0.35f, EndpointerTuning.RELEASE_THRESHOLD, 0.0f)
        assertEquals(500L, EndpointerTuning.HANGOVER_MS)
        assertEquals(300L, EndpointerTuning.MIN_SPEECH_MS)
        assertEquals(98L, EndpointerTuning.MICRO_PAUSE_MS)
        assertEquals(8L, EndpointerTuning.PROBE_BUDGET_MS)
        assertEquals(16, EndpointerTuning.PROBE_CUTOUT_FRAMES)
        assertEquals(-1.0f, EndpointerTuning.NO_VERDICT, 0.0f)
    }

    @Test fun the_frame_geometry_is_the_silero_window() {
        // whisper.cpp model header: n_window = 512 @ 16 kHz mono PCM16.
        assertEquals(512, EndpointerTuning.FRAME_SAMPLES)
        assertEquals(EndpointerTuning.FRAME_SAMPLES * 2, EndpointerTuning.FRAME_BYTES)
        assertEquals(1_000L * EndpointerTuning.FRAME_SAMPLES / 16_000L, EndpointerTuning.FRAME_MS)
    }

    @Test fun the_release_threshold_is_the_native_schmitt_hysteresis() {
        // whisper.cpp:5258 -> neg_threshold = threshold - 0.15f
        assertEquals(0.15f, EndpointerTuning.ONSET_THRESHOLD - EndpointerTuning.RELEASE_THRESHOLD, 1e-6f)
    }

    @Test fun the_endpointer_onset_is_NOT_the_batch_filters_0_40() {
        // whisper_jni.cpp:149 keeps 0.40/150 ms for we_vad_filter: the probe decides WHEN to cut,
        // the batch filter decides WHAT reaches the encoder. Independent knobs, by design.
        assertNotEquals(0.40f, EndpointerTuning.ONSET_THRESHOLD)
    }

}
```

The per-tier commit intervals are NOT pinned here: `CommitCadencePolicyTest` (Task D3) owns every
one of those assertions, including "cloud batch wins outright" and "an unknown tier takes the
conservative value". One object, one test class, one place to change a number.

- [ ] **Step 2: Run it, expect a compile failure** (the object does not exist yet).

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests "com.whispereverywhere.audio.EndpointerTuningTest" --no-daemon
```

Expected: `> Task :app:compileDebugUnitTestKotlin FAILED` with
`e: ...\EndpointerTuningTest.kt:12:23 Unresolved reference: EndpointerTuning` (one such line per use).

- [ ] **Step 3: Minimal implementation.** Create
  `app/src/main/java/com/whispereverywhere/audio/EndpointerTuning.kt`:

```kotlin
package com.whispereverywhere.audio

/**
 * Every knob the 3.7 Silero endpointer turns, in ONE object, JVM-pinned by EndpointerTuningTest.
 *
 * Deliberately SEPARATE from the batch VAD filter's tuning (`whisper_jni.cpp:149`, threshold 0.40 /
 * speech_pad 150 ms), which is untouched: the streaming probe decides WHEN to cut an utterance, the
 * batch filter decides WHAT audio inside that commit reaches the encoder. Independent knobs on
 * independent jobs — the batch filter's 0.40 buys onset headroom that `suppress_nst` absorbs at the
 * token layer, and endpointing has no token layer.
 *
 * There is deliberately NO smoothing/EMA constant. The reference implementation does not smooth
 * (`whisper.cpp:5217-5352`): the Schmitt trigger, the minimum speech duration and the hangover
 * already low-pass the sequence. An EMA would add lag and a second thing to tune.
 */
object EndpointerTuning {

    /** Silero's window: 512 samples of 16 kHz mono is exactly one probe frame (model n_window). */
    const val FRAME_SAMPLES = 512

    /**
     * 512 samples x 2 bytes (PCM16). `vadProbeFrame` returns [NO_VERDICT] for any other size.
     *
     * SINGLE OWNER: this object owns the native frame contract. `VadProbe.FRAME_BYTES` (Task D4)
     * is an alias of this constant, not a second literal — `EndpointerFactory` sizes its direct
     * buffer from one and fills it from the other, so a divergence would be a
     * `BufferOverflowException` on the capture thread rather than a doc inconsistency.
     */
    const val FRAME_BYTES = 1024

    /** 512 / 16 000 s. One mic callback delivers exactly this much audio. */
    const val FRAME_MS = 32L

    /**
     * "No verdict" from the native probe — NEVER "silence". A short frame zero-padded into the
     * model still advances the LSTM and poisons the recurrence, so the native side refuses and the
     * client keeps the previous state.
     *
     * SINGLE OWNER, as for [FRAME_BYTES]: `VadProbe.NO_VERDICT` (Task D4) aliases this. Two
     * independent `-1.0f` literals in one package would let a future edit turn the native sentinel
     * into a legitimate probability on one side of the seam only.
     */
    const val NO_VERDICT = -1.0f

    /** Native default (`whisper.cpp:4454`). A frame at or above this opens/holds the gate. */
    const val ONSET_THRESHOLD = 0.50f

    /**
     * Schmitt hysteresis, native `neg_threshold = threshold - 0.15f` (`whisper.cpp:5258`). This is
     * the exact mechanism whose absence causes today's 251-499 RMS dead band
     * (`SpeechSegmenter.kt:18-26`). Widen to 0.30 if mid-word splits appear in A/B.
     */
    const val RELEASE_THRESHOLD = 0.35f

    /**
     * Trailing silence that ends an utterance. NOT the native 100 ms, which is a file-segmentation
     * value with a 200 ms merge pass behind it. Inter-clause pauses run 200-500 ms; the cost of
     * cutting too early is one extra full encoder pass PLUS a mid-clause boundary that
     * `no_context = true` makes unrepairable. Also feeds the batch filter's `speech_pad_ms = 150`,
     * which needs trailing audio to expand into. Owner A/B range 350-800.
     */
    const val HANGOVER_MS = 500L

    /**
     * Shortest run of speech that may be committed. The native filter already drops <250 ms before
     * `whisper_full`; 300 keeps client and native agreeing instead of fighting.
     */
    const val MIN_SPEECH_MS = 300L

    /**
     * A dip below [RELEASE_THRESHOLD] lasting longer than this is remembered as a cut point for the
     * wall-cap path (native `min_silence_samples_at_max_speech`, `whisper.cpp:5255`). At the 32 ms
     * frame cadence the first qualifying frame is the 4th of the dip (128 ms > 98 ms).
     */
    const val MICRO_PAUSE_MS = 98L

    /** A probe frame slower than this is an overrun against the 32 ms budget. */
    const val PROBE_BUDGET_MS = 8L

    /** Consecutive overruns that latch the probe off for the rest of the session. */
    const val PROBE_CUTOUT_FRAMES = 16

    // NO COMMIT-INTERVAL CONSTANTS LIVE HERE. The measured per-tier cost governor
    // (1200 pro / 6000 multi / 8000 extreme+ultra / 3000 cloud batch) is owned solely by
    // com.whispereverywhere.service.CommitCadencePolicy, and reaches the endpointer per SESSION via
    // Endpointer.onSessionStart(nowMs, minCommitIntervalMs) — it depends on the installed tier AND
    // on whether every commit becomes a provider request, neither of which is an acoustic knob.
}
```

- [ ] **Step 4: Run tests green** (and read the evidence out of the XML, not the task line):

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests "com.whispereverywhere.audio.EndpointerTuningTest" --no-daemon; [xml]$x = Get-Content -Raw 'C:\Users\bastr\.androidbuild\WhisperEverywhere\app\test-results\testDebugUnitTest\TEST-com.whispereverywhere.audio.EndpointerTuningTest.xml'; "$($x.testsuite.tests) tests / $($x.testsuite.failures) failures / $($x.testsuite.errors) errors"
```

Expected: `4 tests / 0 failures / 0 errors`.

- [ ] **Step 5: Commit.**

```powershell
git add app/src/main/java/com/whispereverywhere/audio/EndpointerTuning.kt app/src/test/java/com/whispereverywhere/audio/EndpointerTuningTest.kt; git commit -m @'
feat(vad): EndpointerTuning — the 3.7 tuning table in one JVM-pinned object

Every ACOUSTIC endpointing constant from the approved spec, verbatim, with its
derivation in KDoc: 0.50/0.35 Schmitt pair, 500 ms hangover, 300 ms min speech,
the 98 ms micro-pause floor and the 8 ms / 16-frame probe cutout trigger. The
per-tier commit cadence is deliberately NOT here — CommitCadencePolicy owns it,
because it is a per-session function of tier and engine, not a knob on the
state machine.

Separate from the batch we_vad_filter's 0.40/150 ms, which stays untouched: the
probe decides WHEN to cut, the filter decides WHAT reaches the encoder. Pinned by
EndpointerTuningTest so a silent edit fails the build.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT
'@
```

---

### Task C2: SileroEndpointer — the exact-512-sample accumulator and the −1.0f sentinel

**Files:**
- Create: `app/src/main/java/com/whispereverywhere/audio/SileroEndpointer.kt`
- Test (create): `app/src/test/java/com/whispereverywhere/audio/SileroEndpointerTest.kt`

**Interfaces:**
- Consumes: `EndpointerTuning.FRAME_BYTES`, `EndpointerTuning.NO_VERDICT` (Task C1);
  `com.whispereverywhere.audio.Endpointer` (Task D2 — same package, landed before this workstream).
- Produces: `class com.whispereverywhere.audio.SileroEndpointer(probe: (ByteArray) -> Float, probeReset: () -> Unit = {}) : Endpointer`
  with `override fun onFrame(chunk: ByteArray, amp: Int, nowMs: Long): Boolean`,
  `override fun hasPendingSpeech(): Boolean`, `override fun reset()`. It declares the supertype from
  birth: the interface is five lines and already exists, so there is no window in which a whole
  workstream's code has nothing checking its conformance. The cadence floor is NOT a constructor
  parameter — it arrives per session at `onSessionStart` (Task C6), because tier and engine vary
  between two sessions of the same service.
  Test-only helpers produced here and reused by every later task in this section:
  `private class FakeProbe(var next: Float)`, `private class Pump(ep, probe, var t: Long)`,
  `private const val B` (= 1024), `private const val BASE` (= 1_000_000L).

- [ ] **Step 1: Write the failing test.** Create
  `app/src/test/java/com/whispereverywhere/audio/SileroEndpointerTest.kt`:

```kotlin
package com.whispereverywhere.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val B = EndpointerTuning.FRAME_BYTES

/** Non-zero: 0L is the endpointer's "no micro-pause remembered" sentinel. */
private const val BASE = 1_000_000L

/**
 * The injected probe. Records a COPY of every frame it is handed — the endpointer REUSES one
 * 1024-byte array, so retaining the reference would alias every "frame" onto the latest one.
 */
private class FakeProbe(var next: Float = 0f) : (ByteArray) -> Float {
    val frames = mutableListOf<ByteArray>()
    override fun invoke(frame: ByteArray): Float {
        frames += frame.copyOf()
        return next
    }
}

/** Drives one endpointer at the real 32 ms frame cadence, holding the clock between stretches. */
private class Pump(
    val ep: SileroEndpointer,
    val probe: FakeProbe,
    var t: Long = BASE,
) {
    var commits = 0
    var lastCommitMs = -1L

    /** Feeds [frames] complete frames of probability [p]. @return true if any of them committed. */
    fun run(p: Float, frames: Int): Boolean {
        probe.next = p
        var fired = false
        repeat(frames) {
            if (ep.onFrame(ByteArray(B), 0, t)) {
                fired = true
                commits++
                lastCommitMs = t
            }
            t += EndpointerTuning.FRAME_MS
        }
        return fired
    }
}

class SileroEndpointerTest {

    @Test fun an_exact_frame_reaches_the_probe_untouched() {
        val probe = FakeProbe()
        val ep = SileroEndpointer(probe = probe)
        assertFalse(ep.onFrame(ByteArray(B) { 3 }, 0, BASE))
        assertEquals(1, probe.frames.size)
        assertEquals(B, probe.frames[0].size)
        assertTrue(probe.frames[0].all { it == 3.toByte() })
    }

    @Test fun short_reads_are_reassembled_into_exact_512_sample_frames() {
        // AudioRecord.read() returns UP TO the buffer size and StreamingAudioRecorder forwards
        // buffer.copyOf(read); the 48 kHz decimator documents "~1024". One chunk = one frame is
        // the common case, never the contract — and a zero-padded short frame would poison the LSTM.
        val probe = FakeProbe()
        val ep = SileroEndpointer(probe = probe)
        assertFalse(ep.onFrame(ByteArray(700) { 1 }, 0, BASE))
        assertEquals("a partial frame must never reach the probe", 0, probe.frames.size)
        assertFalse(ep.onFrame(ByteArray(324) { 2 }, 0, BASE + 32))
        assertEquals(1, probe.frames.size)
        val f = probe.frames[0]
        assertEquals(B, f.size)
        assertTrue("bytes 0..699 come from chunk 1", (0 until 700).all { f[it] == 1.toByte() })
        assertTrue("bytes 700..1023 come from chunk 2", (700 until B).all { f[it] == 2.toByte() })
    }

    @Test fun one_chunk_carrying_several_frames_probes_each_of_them() {
        val probe = FakeProbe()
        val ep = SileroEndpointer(probe = probe)
        assertFalse(ep.onFrame(ByteArray(2_500), 0, BASE))
        assertEquals("2048 of 2500 bytes are two whole frames; 452 are retained", 2, probe.frames.size)
        assertFalse(ep.onFrame(ByteArray(572), 0, BASE + 32))
        assertEquals(3, probe.frames.size)
        assertTrue(probe.frames.all { it.size == B })
    }

    @Test fun the_minus_one_sentinel_never_opens_the_gate() {
        val probe = FakeProbe()
        val ep = SileroEndpointer(probe = probe)
        val pump = Pump(ep, probe)
        assertFalse(pump.run(EndpointerTuning.NO_VERDICT, 200))
        assertFalse("a no-verdict frame is never silence and never speech", ep.hasPendingSpeech())
        assertEquals("the frames are still consumed and probed", 200, probe.frames.size)
    }

    @Test fun reset_drops_the_partial_frame_and_resets_the_native_probe() {
        // reset() is one of the five vadProbeReset sites (cap cut :1722, switchSource :1819,
        // onOpen :2224, stopRecording :2393, and this internal one). The partial frame goes with
        // it: the next probe frame must start on a boundary aligned with the fresh LSTM.
        var resets = 0
        val probe = FakeProbe()
        val ep = SileroEndpointer(
            probe = probe,
            probeReset = { resets++ },
        )
        assertFalse(ep.onFrame(ByteArray(900) { 5 }, 0, BASE))
        assertEquals(0, probe.frames.size)
        ep.reset()
        assertEquals(1, resets)
        assertFalse(ep.onFrame(ByteArray(B) { 6 }, 0, BASE + 32))
        assertEquals(1, probe.frames.size)
        assertTrue(
            "the 900 dropped bytes must not survive into the next frame",
            probe.frames[0].all { it == 6.toByte() },
        )
    }

    @Test fun an_empty_chunk_is_a_no_op() {
        val probe = FakeProbe()
        val ep = SileroEndpointer(probe = probe)
        assertFalse(ep.onFrame(ByteArray(0), 0, BASE))
        assertEquals(0, probe.frames.size)
    }
}
```

- [ ] **Step 2: Run it, expect a compile failure** (the class does not exist yet).

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests "com.whispereverywhere.audio.SileroEndpointerTest" --no-daemon
```

Expected: `> Task :app:compileDebugUnitTestKotlin FAILED` with
`e: ...\SileroEndpointerTest.kt:29:15 Unresolved reference: SileroEndpointer`.

- [ ] **Step 3: Minimal implementation.** Create
  `app/src/main/java/com/whispereverywhere/audio/SileroEndpointer.kt`:

```kotlin
package com.whispereverywhere.audio

/**
 * The 3.7 real-VAD endpointer: streaming Silero probabilities in, "commit now" out.
 *
 * It replaces the amplitude DECISION only. Everything structural around it is unchanged: the wall
 * caps stay in the `else if` at `FloatingBubbleService.kt:1695` as backstops, `sendAudio` stays
 * unconditional and first, and the stop flush stays unconditional. With this endpointer stubbed to
 * never fire, session behaviour is byte-identical to 3.6.0 — that is the whole de-risking argument.
 *
 * ## The state machine
 * Ported from `whisper.cpp:5276-5346` (the vendored Silero post-processor), including two details
 * that are easy to lose:
 *  - **The dead band.** A frame with `RELEASE <= p < ONSET` neither clears the pending end nor
 *    counts as silence. Only a frame at or above `ONSET` resets the hangover clock. That is what
 *    makes the hangover a HARD TIMER rather than a decaying one.
 *  - **The micro-pause memory.** The most recent dip below `RELEASE` that outlived
 *    [EndpointerTuning.MICRO_PAUSE_MS] is remembered, so the 15 s wall cap can cut at a real
 *    boundary instead of an arbitrary millisecond. `no_context = true` makes mid-word cuts
 *    unrepairable, so a better boundary is free quality at the same latency bound.
 *
 * ## Clock
 * ONE clock: the caller's `nowMs`, stamped on the chunk the frames came from. The native reference
 * counts sample indices; wall clock is equivalent here because capture is real time, and it is what
 * [com.whispereverywhere.service.SegmentCapPolicy] and the log lines already use. A burst
 * delivery (the AudioRecord ring holds
 * >=128 ms) makes the hangover fire slightly LATE, never early — the conservative direction.
 *
 * ## Threading
 * [onFrame] runs on the capture thread. [reset] is also called from Main (switchSource, onOpen,
 * stopRecording). Fields are @Volatile for the same reason and with the same tolerance
 * [com.whispereverywhere.service.SegmentCapPolicy] documents: a torn observation costs at most
 * one 32 ms chunk of slack.
 *
 * @param probe hands a frame of exactly [EndpointerTuning.FRAME_BYTES] PCM16 bytes to the native
 *        Silero probe and returns its probability, or [EndpointerTuning.NO_VERDICT]. **The array is
 *        REUSED between calls — the probe must copy anything it retains.** The real binding to
 *        `WhisperNative.vadProbeFrame` is made by [EndpointerFactory] (Workstream D).
 * @param probeReset resets the native probe's LSTM state; fired on every commit and every [reset].
 *
 * The per-tier cost governor is NOT a constructor parameter: it is handed over per session through
 * [Endpointer.onSessionStart] (Task C6), because it depends on the installed tier AND on whether
 * every commit becomes a provider request — both of which change between sessions of one service.
 */
class SileroEndpointer(
    private val probe: (ByteArray) -> Float,
    private val probeReset: () -> Unit = {},
) : Endpointer {
    /** The accumulator. One array for the life of the endpointer: no per-frame allocation. */
    private val frame = ByteArray(EndpointerTuning.FRAME_BYTES)

    private var fill = 0
    private var lastFrameMs = 0L

    /**
     * @param chunk PCM16 mono 16 kHz, ANY length (short reads are normal).
     * @param amp the chunk's RMS, ignored here — it exists for the amplitude fallback that shares
     *        this call shape.
     * @param nowMs the capture wall clock for this chunk.
     * @return true when the caller should commit the buffer NOW.
     */
    override fun onFrame(chunk: ByteArray, amp: Int, nowMs: Long): Boolean {
        lastFrameMs = nowMs
        var src = 0
        while (src < chunk.size) {
            val n = minOf(EndpointerTuning.FRAME_BYTES - fill, chunk.size - src)
            System.arraycopy(chunk, src, frame, fill, n)
            fill += n
            src += n
            if (fill < EndpointerTuning.FRAME_BYTES) break
            fill = 0
            if (onProb(probe(frame), nowMs)) return true
        }
        return false
    }

    /** True when speech has been seen since the last commit/reset. */
    override fun hasPendingSpeech(): Boolean = false

    /** A commit happened elsewhere (cap cut, source switch, session open, stop). */
    override fun reset() {
        clearForNextSegment()
    }

    /**
     * One frame's verdict.
     *
     * [EndpointerTuning.NO_VERDICT] (any negative) means "no verdict": the previous state is kept
     * exactly — it can neither open nor close the gate. It does not stall the hangover either,
     * because that clock is wall time from the pending end, so the next real verdict sees the full
     * elapsed silence.
     */
    private fun onProb(p: Float, nowMs: Long): Boolean {
        if (p < 0f) return false
        return false
    }

    private fun clearForNextSegment() {
        fill = 0
        probeReset()
    }
}
```

Note: Kotlin warns `Variable lastFrameMs is never used` until Task C6 wires the governor. Expected;
the build does not use `allWarningsAsErrors`. The interface's three defaulted extension points
(`onSessionStart`, `onSessionEnd`, `pendingCutPointMs`) are inherited as no-ops for now and are
overridden in Tasks C5, C6 and C10 as the state they report comes into existence.

- [ ] **Step 4: Run tests green.**

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests "com.whispereverywhere.audio.SileroEndpointerTest" --no-daemon; [xml]$x = Get-Content -Raw 'C:\Users\bastr\.androidbuild\WhisperEverywhere\app\test-results\testDebugUnitTest\TEST-com.whispereverywhere.audio.SileroEndpointerTest.xml'; "$($x.testsuite.tests) tests / $($x.testsuite.failures) failures / $($x.testsuite.errors) errors"
```

Expected: `6 tests / 0 failures / 0 errors`.

- [ ] **Step 5: Commit.**

```powershell
git add app/src/main/java/com/whispereverywhere/audio/SileroEndpointer.kt app/src/test/java/com/whispereverywhere/audio/SileroEndpointerTest.kt; git commit -m @'
feat(vad): SileroEndpointer accumulator — exact 512-sample frames, -1.0f sentinel

The mic delivers exactly one Silero frame per 32 ms callback in the common case,
but AudioRecord.read() returns UP TO the buffer size and the 48 kHz decimator
documents "~1024" — a short frame zero-padded into the model still advances the
LSTM and poisons the recurrence. So the client accumulates to exact 1024-byte
boundaries and only then probes, reusing one array (no per-frame allocation).

-1.0f is "no verdict", never "silence": it keeps the previous state untouched.
reset() drops the partial frame with the LSTM reset so the next frame starts on a
boundary aligned with the fresh state.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT
'@
```

---

### Task C3: The Schmitt trigger — gate open, the inert dead band, honest hasPendingSpeech()

**Files:**
- Modify: `app/src/main/java/com/whispereverywhere/audio/SileroEndpointer.kt` (fields block after
  `private var lastFrameMs = 0L`; the `hasPendingSpeech()` body; the `onProb` body created in Task C2;
  the `clearForNextSegment()` body)
- Test (modify): `app/src/test/java/com/whispereverywhere/audio/SileroEndpointerTest.kt` (append
  the new `@Test` methods before the class's closing brace)

**Interfaces:**
- Consumes: `EndpointerTuning.ONSET_THRESHOLD`, `RELEASE_THRESHOLD`, `MIN_SPEECH_MS` (Task C1);
  `Pump`, `FakeProbe`, `BASE` (Task C2).
- Produces: honest `SileroEndpointer.hasPendingSpeech(): Boolean` — ">= MIN_SPEECH_MS of >= ONSET
  frames since the last commit/reset", the predicate the LOCAL-silence re-arm at
  `FloatingBubbleService.kt:1716` reads.

- [ ] **Step 1: Write the failing test.** Append to `SileroEndpointerTest.kt`:

```kotlin
    @Test fun a_frame_below_ONSET_never_opens_the_gate() {
        val probe = FakeProbe()
        val ep = SileroEndpointer(probe = probe)
        val pump = Pump(ep, probe)
        assertFalse(pump.run(0.49f, 100))
        assertFalse("0.49 is under ONSET — 3.2 s of it is still not speech", ep.hasPendingSpeech())
    }

    @Test fun ONSET_opens_the_gate_and_MIN_SPEECH_MS_latches_pending_speech() {
        val probe = FakeProbe()
        val ep = SileroEndpointer(probe = probe)
        val pump = Pump(ep, probe)
        // Frames land at BASE + 32k. After 10 frames the newest is at +288 ms: under 300.
        assertFalse(pump.run(EndpointerTuning.ONSET_THRESHOLD, 10))
        assertFalse("288 ms of speech is under MIN_SPEECH_MS", ep.hasPendingSpeech())
        assertFalse(pump.run(EndpointerTuning.ONSET_THRESHOLD, 1))
        assertTrue("the 11th frame is 320 ms in — the latch must set", ep.hasPendingSpeech())
    }

    @Test fun the_dead_band_alone_never_opens_the_gate() {
        val probe = FakeProbe()
        val ep = SileroEndpointer(probe = probe)
        val pump = Pump(ep, probe)
        assertFalse(pump.run(0.42f, 100))
        assertFalse(ep.hasPendingSpeech())
    }

    @Test fun dead_band_frames_do_not_close_an_open_gate_or_move_the_speech_start() {
        val probe = FakeProbe()
        val ep = SileroEndpointer(probe = probe)
        val pump = Pump(ep, probe)
        pump.run(0.9f, 5)                     // gate opens at BASE; 128 ms in — no latch yet
        assertFalse(ep.hasPendingSpeech())
        assertFalse(pump.run(0.42f, 20))      // 640 ms of dead band: inert, gate stays open
        assertFalse("a dead-band frame never runs the latch itself", ep.hasPendingSpeech())
        assertFalse(pump.run(0.9f, 1))        // the frame at BASE+800: 800 ms since speechStart
        assertTrue("speechStart survived the dead band untouched", ep.hasPendingSpeech())
    }

    @Test fun no_verdict_frames_do_not_close_an_open_gate() {
        val probe = FakeProbe()
        val ep = SileroEndpointer(probe = probe)
        val pump = Pump(ep, probe)
        pump.run(0.9f, 11)
        assertTrue(ep.hasPendingSpeech())
        assertFalse(pump.run(EndpointerTuning.NO_VERDICT, 100))
        assertTrue("a no-verdict frame keeps the previous state exactly", ep.hasPendingSpeech())
    }

    @Test fun reset_clears_pending_speech() {
        val probe = FakeProbe()
        val ep = SileroEndpointer(probe = probe)
        val pump = Pump(ep, probe)
        pump.run(0.9f, 11)
        assertTrue(ep.hasPendingSpeech())
        ep.reset()
        assertFalse(ep.hasPendingSpeech())
    }
```

- [ ] **Step 2: Run it, expect assertion failures.**

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests "com.whispereverywhere.audio.SileroEndpointerTest" --no-daemon
```

Expected: four failures, all because `hasPendingSpeech()` is hard-coded false —
`SileroEndpointerTest > ONSET_opens_the_gate_and_MIN_SPEECH_MS_latches_pending_speech FAILED`
`java.lang.AssertionError: the 11th frame is 320 ms in — the latch must set`, plus
`...dead_band_frames_do_not_close_an_open_gate_or_move_the_speech_start FAILED java.lang.AssertionError: speechStart survived the dead band untouched`,
`...no_verdict_frames_do_not_close_an_open_gate FAILED java.lang.AssertionError`, and
`...reset_clears_pending_speech FAILED java.lang.AssertionError` (its first assertion).
The two "never opens the gate" tests pass already — they are the regressions that must stay green.

- [ ] **Step 3: Minimal implementation.** In `SileroEndpointer.kt`, add three fields under
  `private var lastFrameMs = 0L`:

```kotlin
    private var speaking = false
    private var speechStartMs = 0L
    private var pendingSpeech = false
```

replace the `hasPendingSpeech()` body:

```kotlin
    /**
     * True when speech has been seen since the last commit/reset — ">= MIN_SPEECH_MS of frames at
     * or above ONSET". It describes the UNCOMMITTED BUFFER, not the gate: a merged or discarded
     * utterance leaves it true, because that audio really is still sitting there.
     *
     * This is the semantics upgrade the LOCAL-silence re-arm at `FloatingBubbleService.kt:1716`
     * has been waiting for: the soft talker in a noisy room, whose RMS never clears 500, flips from
     * permanently-false to true. The branch above it is unchanged — only the predicate gets honest.
     */
    override fun hasPendingSpeech(): Boolean = pendingSpeech
```

replace the `onProb` body (keeping its KDoc):

```kotlin
    private fun onProb(p: Float, nowMs: Long): Boolean {
        if (p < 0f) return false

        // whisper.cpp:5283-5296. A frame at or above ONSET opens the gate if it is closed. (The
        // pending-end clear this branch also performs lands with the hangover.)
        if (p >= EndpointerTuning.ONSET_THRESHOLD) {
            if (!speaking) {
                speaking = true
                speechStartMs = nowMs
            }
            if (nowMs - speechStartMs >= EndpointerTuning.MIN_SPEECH_MS) pendingSpeech = true
            return false
        }

        // THE DEAD BAND (RELEASE <= p < ONSET) is deliberately inert: it is neither an onset nor a
        // silence. The native guards at :5283 and :5322 use DIFFERENT thresholds and the gap
        // between them falls through both — that is the Schmitt hysteresis, and its absence is
        // exactly what strands today's amplitude segmenter in the 251-499 RMS band.
        if (p >= EndpointerTuning.RELEASE_THRESHOLD) return false

        return false
    }
```

and extend `clearForNextSegment()`:

```kotlin
    private fun clearForNextSegment() {
        speaking = false
        speechStartMs = 0L
        pendingSpeech = false
        fill = 0
        probeReset()
    }
```

- [ ] **Step 4: Run tests green.**

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests "com.whispereverywhere.audio.SileroEndpointerTest" --no-daemon; [xml]$x = Get-Content -Raw 'C:\Users\bastr\.androidbuild\WhisperEverywhere\app\test-results\testDebugUnitTest\TEST-com.whispereverywhere.audio.SileroEndpointerTest.xml'; "$($x.testsuite.tests) tests / $($x.testsuite.failures) failures / $($x.testsuite.errors) errors"
```

Expected: `12 tests / 0 failures / 0 errors`.

- [ ] **Step 5: Commit.**

```powershell
git add app/src/main/java/com/whispereverywhere/audio/SileroEndpointer.kt app/src/test/java/com/whispereverywhere/audio/SileroEndpointerTest.kt; git commit -m @'
feat(vad): Schmitt trigger + the inert dead band + an honest hasPendingSpeech()

0.50 opens the gate, 0.35 is the release, and the band between them is deliberately
inert — neither onset nor silence, exactly as whisper.cpp:5283/5322 guard it with
two different thresholds. That hysteresis is the mechanism whose absence strands
today's segmenter in the 251-499 RMS dead band.

hasPendingSpeech() now means ">= 300 ms of >= ONSET frames since the last commit",
describing the uncommitted buffer rather than the gate. The soft talker in a noisy
room flips from permanently-false to true; the branch that reads it is untouched.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT
'@
```

---

### Task C4: The hangover hard timer, min-speech, and the commit

**Files:**
- Modify: `app/src/main/java/com/whispereverywhere/audio/SileroEndpointer.kt` (fields block; the
  `onProb` body from Task C3; new `closeGate()` and `commitAt()` helpers; `clearForNextSegment()`)
- Test (modify): `app/src/test/java/com/whispereverywhere/audio/SileroEndpointerTest.kt` (append)

**Interfaces:**
- Consumes: `EndpointerTuning.HANGOVER_MS`, `MIN_SPEECH_MS` (Task C1); `Pump`, `FakeProbe`, `B`,
  `BASE` (Task C2).
- Produces: `SileroEndpointer.onFrame(...)` returning **true** on a real endpoint — the verdict
  Workstream D drops into the `if` at `FloatingBubbleService.kt:1691`, leaving the wall caps in the
  `else if` at `:1695` structurally untouched.

- [ ] **Step 1: Write the failing test.** Append to `SileroEndpointerTest.kt`:

```kotlin
    @Test fun the_hangover_cuts_at_exactly_500ms_of_trailing_silence() {
        val probe = FakeProbe()
        val ep = SileroEndpointer(probe = probe)
        val pump = Pump(ep, probe)
        pump.run(0.9f, 20)                       // speech BASE..BASE+608, gate open at BASE
        assertFalse("16 silent frames is 480 ms — under the hangover", pump.run(0.1f, 16))
        assertTrue("the 17th silent frame is 512 ms in", pump.run(0.1f, 1))
        assertEquals(1, pump.commits)
        assertEquals(BASE + 640 + 512, pump.lastCommitMs)
    }

    @Test fun dead_band_frames_do_not_stall_the_hangover_hard_timer() {
        // THE point of the dead-band port: the clock runs on wall time from the pending end, so a
        // long mumble in the 0.35-0.49 band cannot hold the gate open. Only a frame at or above
        // ONSET resets it.
        val probe = FakeProbe()
        val ep = SileroEndpointer(probe = probe)
        val pump = Pump(ep, probe)
        pump.run(0.9f, 20)                       // gate opens at BASE, speech ends at BASE+640
        assertFalse(pump.run(0.1f, 1))           // pending end stamped at BASE+640
        assertFalse("dead-band frames never commit themselves", pump.run(0.42f, 10))
        assertFalse(pump.run(0.1f, 5))           // BASE+992..1120 -> 352..480 ms elapsed
        assertTrue("the timer counted the dead band", pump.run(0.1f, 1))
        assertEquals(BASE + 640 + 512, pump.lastCommitMs)
    }

    @Test fun a_frame_back_above_ONSET_resets_the_hangover_clock() {
        val probe = FakeProbe()
        val ep = SileroEndpointer(probe = probe)
        val pump = Pump(ep, probe)
        pump.run(0.9f, 20)                       // gate opens at BASE
        assertFalse(pump.run(0.1f, 10))          // pending end at BASE+640, 288 ms elapsed
        assertFalse(pump.run(0.9f, 1))           // BASE+960: back to speech, clock cleared
        assertFalse("the clock restarts from BASE+992, not BASE+640", pump.run(0.1f, 16))
        assertTrue(pump.run(0.1f, 1))
        assertEquals(BASE + 992 + 512, pump.lastCommitMs)
    }

    @Test fun no_verdict_frames_do_not_short_circuit_the_hangover() {
        val probe = FakeProbe()
        val ep = SileroEndpointer(probe = probe)
        val pump = Pump(ep, probe)
        pump.run(0.9f, 20)
        assertFalse(pump.run(EndpointerTuning.NO_VERDICT, 30))   // 960 ms of nothing at all
        assertFalse("the pending end is stamped by the first REAL silence", pump.run(0.1f, 16))
        assertTrue(pump.run(0.1f, 1))
        assertEquals(BASE + 1600 + 512, pump.lastCommitMs)
    }

    @Test fun a_burst_under_MIN_SPEECH_MS_is_discarded_without_a_commit() {
        // whisper.cpp:5337 — too short to be an utterance: drop it and re-arm, no segment emitted.
        // The native filter would drop it before whisper_full anyway; agreeing here saves the call.
        val probe = FakeProbe()
        val ep = SileroEndpointer(probe = probe)
        val pump = Pump(ep, probe)
        pump.run(0.9f, 8)                        // 256 ms of speech
        assertFalse("256 ms is under MIN_SPEECH_MS", pump.run(0.1f, 40))
        assertEquals(0, pump.commits)
        assertFalse(ep.hasPendingSpeech())
    }

    @Test fun exactly_MIN_SPEECH_MS_does_not_cut_but_does_count_as_pending_speech() {
        // The native gate is strict (`>`), the pending-speech latch is inclusive (`>=`), and they
        // differ only at exactly 300 ms. The asymmetry is deliberate and errs toward "there IS
        // speech in the buffer", which is the safe direction for the cap-policy branch above it.
        var resets = 0
        val probe = FakeProbe()
        val ep = SileroEndpointer(
            probe = probe,
            probeReset = { resets++ },
        )
        probe.next = 0.9f
        assertFalse(ep.onFrame(ByteArray(B), 0, BASE))
        assertFalse(ep.onFrame(ByteArray(B), 0, BASE + 300))
        assertTrue(ep.hasPendingSpeech())
        probe.next = 0.1f
        assertFalse(ep.onFrame(ByteArray(B), 0, BASE + 300))    // pending end == speechStart + 300
        assertFalse(ep.onFrame(ByteArray(B), 0, BASE + 900))    // 600 ms of silence: hangover done
        assertEquals("no commit at exactly MIN_SPEECH_MS", 0, resets)
        assertTrue("but the buffer is not empty", ep.hasPendingSpeech())
    }

    @Test fun a_commit_resets_the_probe_and_clears_the_accumulator() {
        var resets = 0
        val probe = FakeProbe()
        val ep = SileroEndpointer(
            probe = probe,
            probeReset = { resets++ },
        )
        val pump = Pump(ep, probe)
        pump.run(0.9f, 20)
        assertFalse(pump.run(0.1f, 16))
        // 452 bytes of the NEXT frame are already in the accumulator when the cut fires.
        probe.next = 0.1f
        assertFalse(ep.onFrame(ByteArray(452) { 7 }, 0, pump.t))
        val before = probe.frames.size
        assertTrue(ep.onFrame(ByteArray(B) { 8 }, 0, pump.t))
        assertEquals("the rest of the committing chunk is dropped, not probed", before + 1, probe.frames.size)
        assertEquals(1, resets)
        assertFalse(ep.hasPendingSpeech())
        assertFalse(ep.onFrame(ByteArray(B) { 9 }, 0, pump.t + 32))
        assertTrue(
            "the accumulator restarts on a boundary aligned with the fresh LSTM",
            probe.frames.last().all { it == 9.toByte() },
        )
    }
```

- [ ] **Step 2: Run it, expect assertion failures** (nothing ever commits yet).

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests "com.whispereverywhere.audio.SileroEndpointerTest" --no-daemon
```

Expected: `SileroEndpointerTest > the_hangover_cuts_at_exactly_500ms_of_trailing_silence FAILED`
`java.lang.AssertionError: the 17th silent frame is 512 ms in`, and the same
`AssertionError` shape for `dead_band_frames_do_not_stall_the_hangover_hard_timer`,
`a_frame_back_above_ONSET_resets_the_hangover_clock`,
`no_verdict_frames_do_not_short_circuit_the_hangover`, and
`a_commit_resets_the_probe_and_clears_the_accumulator`.

- [ ] **Step 3: Minimal implementation.** In `SileroEndpointer.kt` add one field under
  `private var pendingSpeech = false`:

```kotlin
    private var tempEndMs = 0L
```

replace the `onProb` body (keeping its KDoc):

```kotlin
    private fun onProb(p: Float, nowMs: Long): Boolean {
        if (p < 0f) return false

        // whisper.cpp:5283-5296. A frame at or above ONSET clears the pending end — the HARD reset
        // that makes the hangover a timer rather than a decay — and opens the gate if it is closed.
        if (p >= EndpointerTuning.ONSET_THRESHOLD) {
            tempEndMs = 0L
            if (!speaking) {
                speaking = true
                speechStartMs = nowMs
            }
            if (nowMs - speechStartMs >= EndpointerTuning.MIN_SPEECH_MS) pendingSpeech = true
            return false
        }

        // THE DEAD BAND (RELEASE <= p < ONSET) is deliberately inert: it is neither an onset nor a
        // silence. The native guards at :5283 and :5322 use DIFFERENT thresholds and the gap
        // between them falls through both — that is the Schmitt hysteresis, and its absence is
        // exactly what strands today's amplitude segmenter in the 251-499 RMS band. Because it does
        // not clear the pending end, the hangover keeps counting straight through a mumble.
        if (p >= EndpointerTuning.RELEASE_THRESHOLD) return false

        // whisper.cpp:5322-5345 — silence after speech.
        if (!speaking) return false
        if (tempEndMs == 0L) tempEndMs = nowMs
        if (nowMs - tempEndMs < EndpointerTuning.HANGOVER_MS) return false

        val speechMs = tempEndMs - speechStartMs
        if (speechMs <= EndpointerTuning.MIN_SPEECH_MS) {
            // whisper.cpp:5337 — too short to be an utterance. Drop it and re-arm; the native VAD
            // filter would drop it before whisper_full anyway, so committing would buy an
            // EmptyExpected round trip and nothing else. pendingSpeech is NOT cleared: the audio is
            // still in the engine's buffer, and the cap-policy branch needs to know that.
            closeGate()
            return false
        }
        commitAt(nowMs)
        return true
    }
```

and add the two helpers plus the extended clear (replace `clearForNextSegment()`):

```kotlin
    /** The utterance gate only — the pending buffer's bookkeeping survives. */
    private fun closeGate() {
        speaking = false
        speechStartMs = 0L
        tempEndMs = 0L
    }

    private fun commitAt(nowMs: Long) {
        clearForNextSegment()
    }

    private fun clearForNextSegment() {
        closeGate()
        pendingSpeech = false
        fill = 0
        probeReset()
    }
```

- [ ] **Step 4: Run tests green.**

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests "com.whispereverywhere.audio.SileroEndpointerTest" --no-daemon; [xml]$x = Get-Content -Raw 'C:\Users\bastr\.androidbuild\WhisperEverywhere\app\test-results\testDebugUnitTest\TEST-com.whispereverywhere.audio.SileroEndpointerTest.xml'; "$($x.testsuite.tests) tests / $($x.testsuite.failures) failures / $($x.testsuite.errors) errors"
```

Expected: `19 tests / 0 failures / 0 errors`.

- [ ] **Step 5: Commit.**

```powershell
git add app/src/main/java/com/whispereverywhere/audio/SileroEndpointer.kt app/src/test/java/com/whispereverywhere/audio/SileroEndpointerTest.kt; git commit -m @'
feat(vad): the hangover hard timer, min-speech, and the endpoint commit

500 ms of trailing silence below RELEASE ends an utterance — measured from the
FIRST sub-RELEASE frame and reset only by a frame back at or above ONSET, so a
mumble in the dead band cannot stall it. That is the hard-timer behaviour ported
verbatim from whisper.cpp:5322-5345, and it is why the dead band had to be inert.

A burst under 300 ms is discarded rather than committed: the native filter drops
<250 ms before whisper_full, so committing would buy an empty round trip. A commit
fires the probe reset and clears the accumulator, and the rest of the committing
chunk is dropped — that audio is already inside the commit.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT
'@
```

---

### Task C5: Micro-pause memory — the better cut point the 15 s wall cap can use

**Files:**
- Modify: `app/src/main/java/com/whispereverywhere/audio/SileroEndpointer.kt` (fields block; the
  silence branch of `onProb`; `clearForNextSegment()`; new public `pendingCutPointMs()`)
- Test (modify): `app/src/test/java/com/whispereverywhere/audio/SileroEndpointerTest.kt` (append)

**Interfaces:**
- Consumes: `EndpointerTuning.MICRO_PAUSE_MS` (Task C1).
- Produces: `override fun SileroEndpointer.pendingCutPointMs(): Long` — the wall-clock ms at which
  the most recent qualifying dip BEGAN, or [Endpointer.NO_CUT_POINT] (0L). Workstream D reads it in the cap-cut branch
  (`FloatingBubbleService.kt:1695-1723`) and Workstream F logs it.

- [ ] **Step 1: Write the failing test.** Append to `SileroEndpointerTest.kt`:

```kotlin
    @Test fun no_micro_pause_is_offered_until_a_dip_outlives_98ms() {
        val probe = FakeProbe()
        val ep = SileroEndpointer(probe = probe)
        val pump = Pump(ep, probe)
        pump.run(0.9f, 20)
        assertEquals("continuous speech has no remembered pause", Endpointer.NO_CUT_POINT, ep.pendingCutPointMs())
        pump.run(0.1f, 1)                    // dip starts at BASE+640, 0 ms old
        assertEquals(Endpointer.NO_CUT_POINT, ep.pendingCutPointMs())
        pump.run(0.1f, 3)                    // 32, 64, 96 ms old — 96 is not > 98
        assertEquals(
            "the 98 ms floor is exclusive, as in whisper.cpp:5328",
            Endpointer.NO_CUT_POINT,
            ep.pendingCutPointMs(),
        )
        pump.run(0.1f, 1)                    // 128 ms old
        assertEquals(BASE + 640, ep.pendingCutPointMs())
    }

    @Test fun the_micro_pause_survives_a_re_onset_within_the_same_stretch() {
        // This is the whole point: during a 15 s continuous stretch the endpointer keeps the most
        // recent real boundary, so a cap cut lands there instead of mid-word. no_context = true
        // makes a mid-word cut permanently unrepairable.
        val probe = FakeProbe()
        val ep = SileroEndpointer(probe = probe)
        val pump = Pump(ep, probe)
        pump.run(0.9f, 20)
        pump.run(0.1f, 5)                    // dip at BASE+640 remembered
        assertEquals(BASE + 640, ep.pendingCutPointMs())
        pump.run(0.9f, 10)                   // speech resumes: the pending end clears, the memory does not
        assertEquals(BASE + 640, ep.pendingCutPointMs())
        pump.run(0.1f, 5)                    // a NEWER dip at BASE+1120 replaces it
        assertEquals(BASE + 1120, ep.pendingCutPointMs())
    }

    @Test fun a_commit_clears_the_micro_pause_memory() {
        val probe = FakeProbe()
        val ep = SileroEndpointer(probe = probe)
        val pump = Pump(ep, probe)
        pump.run(0.9f, 20)
        assertTrue(pump.run(0.1f, 17))
        assertEquals("the remembered pause was consumed by the cut", Endpointer.NO_CUT_POINT, ep.pendingCutPointMs())
    }

    @Test fun reset_clears_the_micro_pause_memory() {
        val probe = FakeProbe()
        val ep = SileroEndpointer(probe = probe)
        val pump = Pump(ep, probe)
        pump.run(0.9f, 20)
        pump.run(0.1f, 5)
        assertEquals(BASE + 640, ep.pendingCutPointMs())
        ep.reset()
        assertEquals(Endpointer.NO_CUT_POINT, ep.pendingCutPointMs())
    }

    @Test fun a_discarded_short_burst_keeps_the_remembered_pause() {
        // Deliberate deviation from whisper.cpp:5341, which clears prev_end here as bookkeeping for
        // its own max-speech split. We keep it: this field exists ONLY to give the wall cap a real
        // cut point, and a 200 ms cough after a good pause must not erase it.
        val probe = FakeProbe()
        val ep = SileroEndpointer(probe = probe)
        val pump = Pump(ep, probe)
        pump.run(0.9f, 20)
        assertTrue(pump.run(0.1f, 17))       // commit at BASE+1152; t is now BASE+1184
        pump.run(0.9f, 5)                    // 160 ms burst — under MIN_SPEECH_MS
        assertFalse(pump.run(0.1f, 17))      // discarded, no commit
        assertEquals(1, pump.commits)
        assertEquals(BASE + 1344, ep.pendingCutPointMs())
    }
```

No new import is needed: the offer is a `Long` with `Endpointer.NO_CUT_POINT` (0L) as its "none"
value rather than a nullable, so these are `assertEquals` assertions like every other one in the
file. (`org.junit.Assert.assertNull` arrives later, with Task C8's `lastCut()` tests.)

- [ ] **Step 2: Run it, expect a compile failure** (`pendingCutPointMs` does not exist).

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests "com.whispereverywhere.audio.SileroEndpointerTest" --no-daemon
```

Expected: `> Task :app:compileDebugUnitTestKotlin FAILED` with
`e: ...\SileroEndpointerTest.kt:<n>:63 Unresolved reference: pendingCutPointMs`.

- [ ] **Step 3: Minimal implementation.** In `SileroEndpointer.kt` add one field under
  `private var tempEndMs = 0L`:

```kotlin
    private var prevEndMs = 0L
```

insert the promotion line into the silence branch of `onProb`, between the pending-end stamp and the
hangover check, so that block reads:

```kotlin
        if (!speaking) return false
        if (tempEndMs == 0L) tempEndMs = nowMs
        // MICRO-PAUSE MEMORY (whisper.cpp:5328-5330). Once this dip has outlived the native 98 ms
        // floor, remember where it STARTED. Silero's own answer to "speech forever" never cuts
        // blind — it cuts at the last such point — and with no_context = true that is a strictly
        // better boundary than an arbitrary millisecond, at the same latency bound. Kept across a
        // re-onset on purpose: during a continuous stretch this is the only real boundary we have.
        if (nowMs - tempEndMs > EndpointerTuning.MICRO_PAUSE_MS) prevEndMs = tempEndMs
        if (nowMs - tempEndMs < EndpointerTuning.HANGOVER_MS) return false
```

add the public accessor next to `hasPendingSpeech()`:

```kotlin
    /**
     * The wall-clock ms at which the most recent qualifying micro-pause BEGAN, or
     * [Endpointer.NO_CUT_POINT] when none has been seen since the last commit/reset. Offered to the
     * wall-cap cut path: when the 15 s cap fires with the gate open, this is a real speech boundary
     * to cut at instead of the arbitrary millisecond the cap happens to land on.
     *
     * 0L doubles as "no offer" rather than a nullable Long because the ONE consumer,
     * [com.whispereverywhere.service.CommitCadencePolicy.capCutRetainMs], already treats every
     * non-positive value as "commit everything, exactly as 3.6.0 did".
     */
    override fun pendingCutPointMs(): Long = prevEndMs
```

and extend `clearForNextSegment()` with one line (after `closeGate()`):

```kotlin
        prevEndMs = 0L
```

- [ ] **Step 4: Run tests green.**

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests "com.whispereverywhere.audio.SileroEndpointerTest" --no-daemon; [xml]$x = Get-Content -Raw 'C:\Users\bastr\.androidbuild\WhisperEverywhere\app\test-results\testDebugUnitTest\TEST-com.whispereverywhere.audio.SileroEndpointerTest.xml'; "$($x.testsuite.tests) tests / $($x.testsuite.failures) failures / $($x.testsuite.errors) errors"
```

Expected: `24 tests / 0 failures / 0 errors`.

- [ ] **Step 5: Commit.**

```powershell
git add app/src/main/java/com/whispereverywhere/audio/SileroEndpointer.kt app/src/test/java/com/whispereverywhere/audio/SileroEndpointerTest.kt; git commit -m @'
feat(vad): micro-pause memory — a real cut point for the wall cap

Silero's own max-speech handling never cuts blind: it cuts at prev_end, the most
recent silence outliving 98 ms. Ported here so that when the 15 s wall cap fires
with the gate open, the endpointer can offer a real speech boundary instead of the
arbitrary millisecond the cap landed on. With no_context = true a mid-word cut is
permanently unrepairable, so this is free quality at the same latency bound.

Kept across a re-onset (the only boundary a continuous stretch has) and across a
discarded short burst — a deliberate deviation from whisper.cpp:5341, which clears
it there as bookkeeping for its own split path. Cleared by a commit or a reset.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT
'@
```

---

### Task C6: The per-tier commit governor — merge utterances until the interval elapses

**Files:**
- Modify: `app/src/main/java/com/whispereverywhere/audio/SileroEndpointer.kt` (fields block; the
  commit tail of `onProb`; `reset()`; `commitAt()`; new public `onSessionStart()`)
- Test (modify): `app/src/test/java/com/whispereverywhere/audio/SileroEndpointerTest.kt` (append)

**Interfaces:**
- Consumes: nothing new from Task C1 — **the commit-interval constants do NOT live in
  `EndpointerTuning`** (see C1's closing `// NO COMMIT-INTERVAL CONSTANTS LIVE HERE`), and the
  cadence floor is **not** a constructor parameter. It arrives per session through
  `Endpointer.onSessionStart(nowMs, minCommitIntervalMs)` (Task D2). Also `Pump`, `FakeProbe`, `BASE`
  (Task C2). The interval VALUES are quoted here as bare literals (`1_200L` / `6_000L` / `8_000L`)
  with `CommitCadencePolicy` (Task D3) named in a comment, because Workstream C must compile without
  the `service` package.
- Produces: `override fun SileroEndpointer.onSessionStart(nowMs: Long, minCommitIntervalMs: Long)` —
  the session anchor AND this session's cadence floor, wired by Workstream D at
  `FloatingBubbleService.kt:2224` (onOpen) alongside `SegmentCapPolicy.onSessionStart(now)`.
  `reset()` gains its documented governor semantics: "a commit happened, at approximately the last
  frame's timestamp".

**Cadence is per session, not per construction.** The floor depends on the installed tier AND on
whether every commit becomes a provider request; both can differ between two sessions of the same
service, so the value arrives with the session and is stored. Until the first `onSessionStart` the
endpointer assumes the expensive end — 8000 ms, the same "unmeasured means conservative" reasoning
that gives extreme/ultra their row — which in practice only affects a frame that arrives before
onOpen has run.

- [ ] **Step 1: Write the failing test.** Append to `SileroEndpointerTest.kt`:

```kotlin
    @Test fun the_sessions_first_endpoint_is_never_merged() {
        // First text fast, on every tier. The governor bounds the STEADY state; one early commit is
        // exactly what FIRST_SEGMENT_WALL_MS exists to buy, and this beats it to the punch.
        val probe = FakeProbe()
        val ep = SileroEndpointer(probe = probe)
        val pump = Pump(ep, probe)
        // 8000L is CommitCadencePolicy.MIN_COMMIT_INTERVAL_LARGE_MS, quoted rather than imported:
        // Workstream C compiles without the service package (Task D3 owns that object).
        ep.onSessionStart(BASE, 8_000L)
        pump.run(0.9f, 20)
        assertTrue(pump.run(0.1f, 17))
        assertEquals(1, pump.commits)
    }

    @Test fun pro_merges_an_utterance_that_endpoints_inside_1200ms() {
        val probe = FakeProbe()
        val ep = SileroEndpointer(probe = probe)
        val pump = Pump(ep, probe)
        // 1200L is CommitCadencePolicy.MIN_COMMIT_INTERVAL_FAST_MS (pro), quoted not imported.
        ep.onSessionStart(BASE, 1_200L)
        pump.run(0.9f, 20)
        assertTrue(pump.run(0.1f, 17))                 // commit 1 at BASE+1152
        pump.run(0.9f, 11)                             // utterance 2: BASE+1184..1504
        assertFalse("endpoint at +896 ms is inside the 1200 ms interval", pump.run(0.1f, 17))
        assertEquals(1, pump.commits)
        assertTrue("the merged audio is still uncommitted", ep.hasPendingSpeech())
        assertEquals("the merged endpoint becomes the best known cut point", BASE + 1536, ep.pendingCutPointMs())
        pump.run(0.9f, 11)                             // utterance 3: BASE+2080..2400
        assertTrue("endpoint at +1792 ms clears the interval", pump.run(0.1f, 17))
        assertEquals(2, pump.commits)
        assertEquals(BASE + 2944, pump.lastCommitMs)
    }

    @Test fun multi_paces_three_utterances_into_one_commit() {
        val probe = FakeProbe()
        val ep = SileroEndpointer(probe = probe)
        val pump = Pump(ep, probe)
        // 6000L is CommitCadencePolicy.MIN_COMMIT_INTERVAL_MULTI_MS, quoted not imported.
        ep.onSessionStart(BASE, 6_000L)
        pump.run(0.9f, 20)
        assertTrue(pump.run(0.1f, 17))                 // commit 1 at BASE+1152
        pump.run(0.9f, 11); assertFalse(pump.run(0.1f, 17))
        pump.run(0.9f, 11); assertFalse(pump.run(0.1f, 17))
        assertEquals("6 s has not elapsed: the endpointer still cuts, it just merges", 1, pump.commits)
        assertTrue(ep.hasPendingSpeech())
    }

    @Test fun reset_anchors_the_governor_on_the_last_frame_seen() {
        // The Endpointer interface carries no clock into reset(), so an EXTERNAL commit (cap cut,
        // switchSource, stop) re-anchors on the most recent frame timestamp — within one 32 ms
        // chunk of the real instant, the same tolerance SegmentCapPolicy documents for its own
        // cross-thread writes.
        val probe = FakeProbe()
        val ep = SileroEndpointer(probe = probe)
        val pump = Pump(ep, probe)
        ep.onSessionStart(BASE, 6_000L)                // multi's paced floor
        pump.run(0.9f, 20)                             // last frame at BASE+608
        ep.reset()                                     // the wall-cap cut at FBS.kt:1722
        assertFalse(ep.hasPendingSpeech())
        pump.run(0.9f, 11)
        assertFalse("896 ms after the cap cut is inside multi's 6 s", pump.run(0.1f, 17))
        assertEquals(0, pump.commits)
    }

    @Test fun onSessionStart_re_arms_the_first_free_cut() {
        val probe = FakeProbe()
        val ep = SileroEndpointer(probe = probe)
        val pump = Pump(ep, probe)
        ep.onSessionStart(BASE, 6_000L)                // multi's paced floor
        pump.run(0.9f, 20)
        assertTrue(pump.run(0.1f, 17))                 // commit 1
        pump.t = BASE + 2_000
        ep.onSessionStart(BASE + 2_000, 6_000L)
        pump.run(0.9f, 11)
        assertTrue("a new session's first cut is free again", pump.run(0.1f, 17))
        assertEquals(2, pump.commits)
    }
```

- [ ] **Step 2: Run it, expect a compile failure and assertion failures.**

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests "com.whispereverywhere.audio.SileroEndpointerTest" --no-daemon
```

Expected: this one COMPILES — `onSessionStart(nowMs, minCommitIntervalMs)` is the interface's
defaulted no-op until this task overrides it — so the red is four assertion failures, which is the
stronger red anyway (a no-op governor is exactly the bug):
`pro_merges_an_utterance_that_endpoints_inside_1200ms FAILED java.lang.AssertionError: endpoint at +896 ms is inside the 1200 ms interval`,
`multi_paces_three_utterances_into_one_commit FAILED java.lang.AssertionError: 6 s has not elapsed: the endpointer still cuts, it just merges expected:<1> but was:<3>`,
`reset_anchors_the_governor_on_the_last_frame_seen FAILED expected:<0> but was:<1>`, and
`onSessionStart_re_arms_the_first_free_cut FAILED java.lang.AssertionError: a new session's first cut is free again`.

- [ ] **Step 3: Minimal implementation.** In `SileroEndpointer.kt` add three fields under
  `private var prevEndMs = 0L`:

```kotlin
    private var lastCommitMs = 0L
    private var hasCommitted = false

    /**
     * This session's cadence floor, handed over at [onSessionStart]. Before the first session start
     * it is the conservative 8000 — the same "UNMEASURED means assume the expensive end" rule that
     * gives extreme/ultra their row in
     * [com.whispereverywhere.service.CommitCadencePolicy] — so a frame arriving before onOpen has
     * run can never commit at a rate the tier cannot pay for.
     */
    private var minCommitIntervalMs = 8_000L
```

insert the governor between the min-speech check and `commitAt(nowMs)` in `onProb`, so the tail of
that function reads:

```kotlin
        val speechMs = tempEndMs - speechStartMs
        if (speechMs <= EndpointerTuning.MIN_SPEECH_MS) {
            closeGate()
            return false
        }
        if (hasCommitted && nowMs - lastCommitMs < minCommitIntervalMs) {
            // THE COST GOVERNOR. A real endpoint, but committing it now would outrun the tier's
            // measured per-commit cost (F*N + m*S <= 0.70*60 s). MERGE: close the gate so the next
            // pause is judged afresh, keep pendingSpeech (that audio really is still uncommitted),
            // and promote this endpoint to the micro-pause memory — it is the best cut point known.
            // The session's FIRST cut is never merged: first text fast on every tier.
            prevEndMs = tempEndMs
            closeGate()
            return false
        }
        commitAt(nowMs)
        return true
```

replace `commitAt()` and `reset()`, and add `onSessionStart()`:

```kotlin
    private fun commitAt(nowMs: Long) {
        lastCommitMs = nowMs
        hasCommitted = true
        clearForNextSegment()
    }

    /**
     * A commit happened elsewhere — the wall-cap cut (`FloatingBubbleService.kt:1722`),
     * `switchSource` (`:1819`), `stopRecording` (`:2393`) — or the session is being re-armed.
     * Fires the native probe reset, which `switchSource` in particular MUST have: carrying LSTM
     * state across a mic <-> device-audio swap is a correctness bug, not merely suboptimal.
     *
     * [reset] carries no clock (the interface's three abstract members are the capture path's, and
     * widening them for this would push a timestamp through four call sites that do not have one),
     * so the governor re-anchors on the last frame seen: within one 32 ms chunk of the true commit
     * instant.
     */
    override fun reset() {
        lastCommitMs = lastFrameMs
        hasCommitted = true
        clearForNextSegment()
    }

    /**
     * A new RECORDING session (`FloatingBubbleService.kt:2224`, beside
     * `SegmentCapPolicy.onSessionStart`). Everything [reset] clears, plus the governor's
     * first-cut-is-free arming and THIS session's cadence floor.
     *
     * [minCommitIntervalMs] comes from
     * [com.whispereverywhere.service.CommitCadencePolicy.minCommitIntervalMs] at the call site,
     * which is the only place that knows both the installed tier and whether this session posts
     * every commit to a provider.
     */
    override fun onSessionStart(nowMs: Long, minCommitIntervalMs: Long) {
        this.minCommitIntervalMs = minCommitIntervalMs
        lastFrameMs = nowMs
        lastCommitMs = 0L
        hasCommitted = false
        clearForNextSegment()
    }
```

- [ ] **Step 4: Run tests green.**

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests "com.whispereverywhere.audio.SileroEndpointerTest" --no-daemon; [xml]$x = Get-Content -Raw 'C:\Users\bastr\.androidbuild\WhisperEverywhere\app\test-results\testDebugUnitTest\TEST-com.whispereverywhere.audio.SileroEndpointerTest.xml'; "$($x.testsuite.tests) tests / $($x.testsuite.failures) failures / $($x.testsuite.errors) errors"
```

Expected: `29 tests / 0 failures / 0 errors`.

- [ ] **Step 5: Commit.**

```powershell
git add app/src/main/java/com/whispereverywhere/audio/SileroEndpointer.kt app/src/test/java/com/whispereverywhere/audio/SileroEndpointerTest.kt; git commit -m @'
feat(vad): the per-tier commit governor — merge, do not commit, inside the interval

The measured cost governor from the 2026-08-20 session: pro commits at true
utterance cadence (1200 ms floor, below which a commit is zero-padded to the same
encoder cost anyway), multi paces at 6000 ms (F = 2.3 s, m ~ 0.45 -> <= 10.7
commits/min at 0.70 duty), extreme/ultra 8000 conservative, cloud batch 3000.

Inside the interval the endpointer still cuts at real pauses — it just merges the
utterance into the pending buffer and remembers the boundary. The session's first
endpoint is always free, so first text stays fast on every tier. reset() re-anchors
the governor on the last frame seen, which is what makes the external commit sites
(cap cut, switchSource, stop) line up without widening the interface.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT
'@
```

---

### Task C7: The latched slow-probe cutout

**Files:**
- Modify: `app/src/main/java/com/whispereverywhere/audio/SileroEndpointer.kt` (constructor; fields
  block; `onFrame`; `onSessionStart`; new `timedProbe()` and three accessors)
- Test (modify): `app/src/test/java/com/whispereverywhere/audio/SileroEndpointerTest.kt` (replace
  the `FakeProbe` helper, add `FakeClock`, append the new `@Test` methods)

**Interfaces:**
- Consumes: `EndpointerTuning.PROBE_BUDGET_MS`, `PROBE_CUTOUT_FRAMES` (Task C1).
- Produces: constructor gains `nanoClock: () -> Long = { System.nanoTime() }` (3rd parameter,
  defaulted — no existing call site changes) and `SileroEndpointer.isProbeCutout(): Boolean`.

**One budget, two questions — only one of them lives here.** The LATCH needs "is the device failing
to keep up right now", which is a run of CONSECUTIVE overruns and is what this task counts. The
session totals behind Workstream F's `probe: frames=… p50=…µs p99=…µs overruns=…` line are a
different question ("did this session ever miss the budget"), they need a histogram, and they already
have an owner: `com.whispereverywhere.util.ProbeStats` (Task F1), wired into this class in Task C10.
This task therefore does NOT mint `probeFrames()` / `probeOverruns()` accessors — a second set of
counters is exactly how the log and the latch would start disagreeing.

- [ ] **Step 1: Write the failing test.** In `SileroEndpointerTest.kt`, replace the `FakeProbe`
  helper with the version below and add `FakeClock` beside it:

```kotlin
/**
 * The injected probe. Records a COPY of every frame it is handed — the endpointer REUSES one
 * 1024-byte array, so retaining the reference would alias every "frame" onto the latest one.
 * Optionally charges [costMs] of synthetic wall time against [clock] for the cutout tests.
 */
private class FakeProbe(var next: Float = 0f) : (ByteArray) -> Float {
    val frames = mutableListOf<ByteArray>()
    var clock: FakeClock? = null
    var costMs: Long = 0L
    override fun invoke(frame: ByteArray): Float {
        frames += frame.copyOf()
        clock?.let { it.nowNs += costMs * 1_000_000L }
        return next
    }
}

/** A hand-cranked nanoTime, advanced only by [FakeProbe.costMs]. */
private class FakeClock(var nowNs: Long = 0L) : () -> Long {
    override fun invoke(): Long = nowNs
}
```

and append:

```kotlin
    @Test fun a_frame_exactly_at_the_budget_is_not_an_overrun() {
        val clock = FakeClock()
        val probe = FakeProbe()
        probe.clock = clock
        probe.costMs = EndpointerTuning.PROBE_BUDGET_MS
        val ep = SileroEndpointer(
            probe = probe,
            nanoClock = clock,
        )
        Pump(ep, probe).run(0.1f, 40)
        assertFalse(ep.isProbeCutout())
        assertEquals("every frame still reached the probe", 40, probe.frames.size)
    }

    @Test fun the_cutout_latches_only_on_16_CONSECUTIVE_overruns() {
        val clock = FakeClock()
        val probe = FakeProbe()
        probe.clock = clock
        val ep = SileroEndpointer(
            probe = probe,
            nanoClock = clock,
        )
        val pump = Pump(ep, probe)

        probe.costMs = 9
        pump.run(0.1f, 15)
        assertFalse("15 is not 16", ep.isProbeCutout())
        probe.costMs = 0
        pump.run(0.1f, 1)
        assertFalse(ep.isProbeCutout())
        probe.costMs = 9
        pump.run(0.1f, 15)
        assertFalse("the fast frame broke the run", ep.isProbeCutout())
        pump.run(0.1f, 1)
        assertTrue("the 16th consecutive overrun latches", ep.isProbeCutout())
        assertEquals("32 frames were probed before the latch fired", 32, probe.frames.size)
    }

    @Test fun a_latched_cutout_stops_probing_for_the_rest_of_the_session() {
        val clock = FakeClock()
        val probe = FakeProbe()
        probe.clock = clock
        probe.costMs = 9
        val ep = SileroEndpointer(
            probe = probe,
            nanoClock = clock,
        )
        val pump = Pump(ep, probe)
        pump.run(0.1f, 16)
        assertTrue(ep.isProbeCutout())
        val probed = probe.frames.size
        probe.costMs = 0
        assertFalse("the amplitude fallback owns the session now", pump.run(0.9f, 200))
        assertEquals("not one more native call", probed, probe.frames.size)
        assertEquals("the latch fired on the 16th frame", 16, probed)
    }

    @Test fun the_latch_survives_reset_and_is_re_armed_only_by_a_new_session() {
        // The we_on_new_segment latch discipline: a probe that blew its budget 16 frames running
        // will almost certainly blow it again, so it is never retried per frame — or per commit.
        val clock = FakeClock()
        val probe = FakeProbe()
        probe.clock = clock
        probe.costMs = 9
        val ep = SileroEndpointer(
            probe = probe,
            nanoClock = clock,
        )
        val pump = Pump(ep, probe)
        pump.run(0.1f, 16)
        assertTrue(ep.isProbeCutout())
        ep.reset()
        assertTrue("a commit must NOT re-arm the probe", ep.isProbeCutout())
        probe.costMs = 0
        pump.run(0.9f, 10)
        assertEquals(16, probe.frames.size)

        ep.onSessionStart(pump.t, 1_200L)
        assertFalse("a fresh session re-arms it", ep.isProbeCutout())
        pump.run(0.9f, 5)
        assertEquals(21, probe.frames.size)
    }
```

- [ ] **Step 2: Run it, expect a compile failure** (no `nanoClock` parameter, no accessors).

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests "com.whispereverywhere.audio.SileroEndpointerTest" --no-daemon
```

Expected: `> Task :app:compileDebugUnitTestKotlin FAILED` with
`e: ...\SileroEndpointerTest.kt:<n>:13 Cannot find a parameter with this name: nanoClock` and
`e: ...\SileroEndpointerTest.kt:<n>:23 Unresolved reference: isProbeCutout`.

- [ ] **Step 3: Minimal implementation.** In `SileroEndpointer.kt` add the third constructor
  parameter (with its KDoc line under the existing `@param probeReset`):

```kotlin
    private val probeReset: () -> Unit = {},
    private val nanoClock: () -> Long = { System.nanoTime() },
) : Endpointer {
```

```
 * @param nanoClock monotonic ns source for the probe budget; injected only so the cutout is
 *        testable on the JVM.
```

add two fields under `private var hasCommitted = false`:

```kotlin
    private var slowRun = 0
    private var probeCutout = false
```

replace the `onFrame` body:

```kotlin
    override fun onFrame(chunk: ByteArray, amp: Int, nowMs: Long): Boolean {
        if (probeCutout) return false
        lastFrameMs = nowMs
        var src = 0
        while (src < chunk.size) {
            val n = minOf(EndpointerTuning.FRAME_BYTES - fill, chunk.size - src)
            System.arraycopy(chunk, src, frame, fill, n)
            fill += n
            src += n
            if (fill < EndpointerTuning.FRAME_BYTES) break
            fill = 0
            val p = timedProbe()
            // The frame that TRIPPED the latch has its verdict discarded: from here the amplitude
            // fallback owns the session.
            if (probeCutout) return false
            if (onProb(p, nowMs)) return true
        }
        return false
    }
```

add `timedProbe()` beside `onProb`:

```kotlin
    /**
     * One native probe call, timed. After [EndpointerTuning.PROBE_CUTOUT_FRAMES] CONSECUTIVE frames
     * over [EndpointerTuning.PROBE_BUDGET_MS] the probe is latched off for the rest of the session
     * and the caller falls back to amplitude. Latched, never retried per frame: the same discipline
     * the new-segment callback uses for a throwing callback — something that failed 16 frames
     * running will fail on the next one too, and retrying costs the audio thread every time.
     */
    private fun timedProbe(): Float {
        val t0 = nanoClock()
        val p = probe(frame)
        val elapsedMs = (nanoClock() - t0) / 1_000_000L
        if (elapsedMs > EndpointerTuning.PROBE_BUDGET_MS) {
            slowRun++
            if (slowRun >= EndpointerTuning.PROBE_CUTOUT_FRAMES) probeCutout = true
        } else {
            slowRun = 0
        }
        return p
    }
```

add the accessor next to `pendingCutPointMs()` (it stays on the concrete class, NOT on the
`Endpointer` interface — an amplitude endpointer has no probe to latch, and the one caller that
cares reaches it with an `as?`):

```kotlin
    /** True once the probe has been latched off for this session (amplitude fallback in force). */
    fun isProbeCutout(): Boolean = probeCutout
```

and extend `onSessionStart()` with the re-arm (before `clearForNextSegment()`):

```kotlin
        slowRun = 0
        probeCutout = false
```

- [ ] **Step 4: Run tests green.**

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests "com.whispereverywhere.audio.SileroEndpointerTest" --no-daemon; [xml]$x = Get-Content -Raw 'C:\Users\bastr\.androidbuild\WhisperEverywhere\app\test-results\testDebugUnitTest\TEST-com.whispereverywhere.audio.SileroEndpointerTest.xml'; "$($x.testsuite.tests) tests / $($x.testsuite.failures) failures / $($x.testsuite.errors) errors"
```

Expected: `33 tests / 0 failures / 0 errors`.

- [ ] **Step 5: Commit.**

```powershell
git add app/src/main/java/com/whispereverywhere/audio/SileroEndpointer.kt app/src/test/java/com/whispereverywhere/audio/SileroEndpointerTest.kt; git commit -m @'
feat(vad): latched slow-probe cutout — 16 consecutive frames over 8 ms

Third fallback tier, under "model missing -> amplitude" and over "wall caps
always". The budget is generous against the 0.2-1.5 ms the probe is expected to
cost, so 16 consecutive overruns means the device is not keeping up, not that one
frame was unlucky.

Latched for the session and deliberately NOT re-armed by reset(): re-arming per
commit would retry a probe that has already failed 16 frames running, on the audio
thread, forever. Only a new session clears it. The latch counts CONSECUTIVE
overruns and nothing else; the session's frame/overrun totals and the probe: line
belong to ProbeStats, wired in the next commit.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT
'@
```

---

### Task C8: EndpointCut — the three numbers only the endpointer knows

**Files:**
- Modify: `app/src/main/java/com/whispereverywhere/audio/SileroEndpointer.kt` (new top-level
  `data class EndpointCut` above the class; fields block; the commit tail of `onProb`;
  `onSessionStart`; new `lastCut()` accessor)
- Test (modify): `app/src/test/java/com/whispereverywhere/audio/SileroEndpointerTest.kt` (append)

**Interfaces:**
- Consumes: nothing new.
- Produces: `data class com.whispereverywhere.audio.EndpointCut(val speechMs: Long, val trailMs: Long, val prob: Float)`
  and `SileroEndpointer.lastCut(): EndpointCut?`. The commit funnel (Task F8) reads it immediately
  after `onFrame` returned true — via `(endpointer as? SileroEndpointer)?.lastCut()`, the one site
  in the service that touches endpointer state — and `EndpointDiag.endpointLine` formats
  `endpoint: seq=N cut=vad speechMs=… trailMs=… p=…` from it. Nothing downstream may re-derive
  these numbers: the state machine re-arms as it returns, so it is the only holder.

- [ ] **Step 1: Write the failing test.** Add the import
`import org.junit.Assert.assertNull` at the top of `SileroEndpointerTest.kt` (first use), then
append:

```kotlin
    @Test fun a_vad_cut_records_what_it_cut() {
        val probe = FakeProbe()
        val ep = SileroEndpointer(probe = probe)
        val pump = Pump(ep, probe)
        assertNull("nothing has been cut yet", ep.lastCut())
        pump.run(0.9f, 20)
        assertTrue(pump.run(0.1f, 17))
        assertEquals(EndpointCut(speechMs = 640L, trailMs = 512L, prob = 0.1f), ep.lastCut())
    }

    @Test fun a_merged_endpoint_is_not_a_cut() {
        val probe = FakeProbe()
        val ep = SileroEndpointer(probe = probe)
        val pump = Pump(ep, probe)
        ep.onSessionStart(BASE, 1_200L)                // pro's utterance cadence
        pump.run(0.9f, 20)
        assertTrue(pump.run(0.1f, 17))
        pump.run(0.9f, 11)
        assertFalse(pump.run(0.1f, 17))
        assertEquals(
            "the merge changed nothing about the last CUT",
            EndpointCut(speechMs = 640L, trailMs = 512L, prob = 0.1f),
            ep.lastCut(),
        )
    }

    @Test fun onSessionStart_clears_the_cut_record() {
        val probe = FakeProbe()
        val ep = SileroEndpointer(probe = probe)
        val pump = Pump(ep, probe)
        pump.run(0.9f, 20)
        assertTrue(pump.run(0.1f, 17))
        ep.onSessionStart(pump.t, 1_200L)
        assertNull(ep.lastCut())
    }
```

- [ ] **Step 2: Run it, expect a compile failure.**

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests "com.whispereverywhere.audio.SileroEndpointerTest" --no-daemon
```

Expected: `> Task :app:compileDebugUnitTestKotlin FAILED` with
`e: ...\SileroEndpointerTest.kt:<n>:44 Unresolved reference: lastCut` and
`e: ...\SileroEndpointerTest.kt:<n>:22 Unresolved reference: EndpointCut`.

- [ ] **Step 3: Minimal implementation.** In `SileroEndpointer.kt` add the data class above the
  class declaration (below the `package` line):

```kotlin
/**
 * What a VAD-decided commit actually cut, for the `endpoint:` diagnostic line. These three numbers
 * exist nowhere else: by the time the service sees the verdict, the state machine has already
 * re-armed. Read immediately after [SileroEndpointer.onFrame] returns true.
 *
 * @param speechMs speech from the gate opening to the frame that began the trailing silence
 * @param trailMs trailing silence at the moment of the cut (always >= HANGOVER_MS)
 * @param prob the Silero probability of the frame that fired the cut
 */
data class EndpointCut(val speechMs: Long, val trailMs: Long, val prob: Float)
```

add one field under `private var probeCutout = false` (the last field Task C7 added, so the fields
block order matches Task C9's pinned list exactly):

```kotlin
    private var lastCutRecord: EndpointCut? = null
```

record it in `onProb` — replace the two lines `commitAt(nowMs)` / `return true` with:

```kotlin
        lastCutRecord = EndpointCut(speechMs = speechMs, trailMs = nowMs - tempEndMs, prob = p)
        commitAt(nowMs)
        return true
```

add the accessor next to `pendingCutPointMs()`:

```kotlin
    /** The most recent VAD cut of this session, or null. A MERGED endpoint is not a cut. */
    fun lastCut(): EndpointCut? = lastCutRecord
```

and one line in `onSessionStart()` beside the other counter resets:

```kotlin
        lastCutRecord = null
```

- [ ] **Step 4: Run tests green.**

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests "com.whispereverywhere.audio.SileroEndpointerTest" --no-daemon; [xml]$x = Get-Content -Raw 'C:\Users\bastr\.androidbuild\WhisperEverywhere\app\test-results\testDebugUnitTest\TEST-com.whispereverywhere.audio.SileroEndpointerTest.xml'; "$($x.testsuite.tests) tests / $($x.testsuite.failures) failures / $($x.testsuite.errors) errors"
```

Expected: `36 tests / 0 failures / 0 errors`.

- [ ] **Step 5: Commit.**

```powershell
git add app/src/main/java/com/whispereverywhere/audio/SileroEndpointer.kt app/src/test/java/com/whispereverywhere/audio/SileroEndpointerTest.kt; git commit -m @'
feat(vad): EndpointCut — speechMs/trailMs/p for the endpoint: diagnostic

The mandate is unmeasurable without knowing WHY a segment was cut. These three
numbers exist only inside the state machine: by the time the service sees the
verdict it has already re-armed, so the cut has to be recorded as it happens. The
service reads them straight after onFrame() returns true and formats the line.

A merged endpoint deliberately leaves the record alone — a merge is not a cut.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT
'@
```

---

### Task C9: Cross-thread safety — @Volatile fields, pinned, with a real capture-thread storm

**Files:**
- Modify: `app/src/main/java/com/whispereverywhere/audio/SileroEndpointer.kt` (every mutable field
  in the fields block)
- Test (create): `app/src/test/java/com/whispereverywhere/audio/SileroEndpointerConcurrencyTest.kt`

**Interfaces:**
- Consumes: everything produced by Tasks C2–C8.
- Produces: no new API. The pin is the contract: the thirteen mutable fields stay `@Volatile`
  because `onFrame` runs on the capture thread while `reset()` is called from Main at
  `FloatingBubbleService.kt:1819` (switchSource), `:2224` (onOpen) and `:2393` (stopRecording).

- [ ] **Step 1: Write the failing test.** Create
  `app/src/test/java/com/whispereverywhere/audio/SileroEndpointerConcurrencyTest.kt`:

```kotlin
package com.whispereverywhere.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * [SileroEndpointer.onFrame] runs on the capture thread; [SileroEndpointer.reset] is called from
 * Main (switchSource, onOpen, stopRecording). Same split, same tolerance and same fix as
 * [com.whispereverywhere.service.SegmentCapPolicy], whose fields are @Volatile for exactly this
 * reason.
 *
 * Real threads throughout — a same-thread stub would prove nothing here.
 */
class SileroEndpointerConcurrencyTest {

    @Test fun every_cross_thread_field_stays_volatile() {
        val required = listOf(
            "fill", "lastFrameMs", "speaking", "speechStartMs", "pendingSpeech", "tempEndMs",
            "prevEndMs", "lastCommitMs", "hasCommitted", "minCommitIntervalMs", "slowRun",
            "probeCutout", "lastCutRecord",
        )
        val declared = SileroEndpointer::class.java.declaredFields.associateBy { it.name }
        for (name in required) {
            val field = declared[name]
            assertTrue("field $name has been renamed or removed — update this pin", field != null)
            assertTrue(
                "$name is written on the capture thread and read/written from Main " +
                    "(reset at FloatingBubbleService :1819/:2224/:2393): it must stay @Volatile",
                Modifier.isVolatile(field!!.modifiers),
            )
        }
    }

    @Test fun main_thread_resets_never_corrupt_the_capture_thread_pump() {
        val badFrameSize = AtomicInteger(0)
        val probed = AtomicInteger(0)
        val ep = SileroEndpointer(
            probe = { frame ->
                if (frame.size != EndpointerTuning.FRAME_BYTES) badFrameSize.incrementAndGet()
                // Alternating speech/silence so the state machine is doing real work throughout.
                if ((probed.incrementAndGet() / 20) % 2 == 0) 0.9f else 0.05f
            },
        )
        ep.onSessionStart(1_000_000L, 1_200L)

        val capture = Executors.newSingleThreadExecutor()
        val stop = AtomicBoolean(false)
        val done = CountDownLatch(1)
        val thrown = AtomicReference<Throwable?>(null)

        capture.execute {
            try {
                var t = 1_000_000L
                var i = 0
                // Short reads interleaved with whole frames, as AudioRecord really delivers them.
                while (!stop.get() && i < 200_000) {
                    val size = if (i % 3 == 0) 640 else EndpointerTuning.FRAME_BYTES
                    ep.onFrame(ByteArray(size), 0, t)
                    t += EndpointerTuning.FRAME_MS
                    i++
                }
            } catch (th: Throwable) {
                thrown.set(th)
            } finally {
                done.countDown()
            }
        }

        repeat(5_000) { ep.reset() }        // Main hammering the four external reset sites
        stop.set(true)
        assertTrue("the capture pump did not finish", done.await(30, TimeUnit.SECONDS))
        capture.shutdownNow()

        assertNull("capture thread threw: ${thrown.get()}", thrown.get())
        assertEquals("the probe must only ever see whole frames", 0, badFrameSize.get())
        assertTrue("the pump did no work", probed.get() > 0)
    }
}
```

- [ ] **Step 2: Run it, expect the volatile pin to fail.**

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests "com.whispereverywhere.audio.SileroEndpointerConcurrencyTest" --no-daemon
```

Expected: `SileroEndpointerConcurrencyTest > every_cross_thread_field_stays_volatile FAILED`
`java.lang.AssertionError: fill is written on the capture thread and read/written from Main (reset at FloatingBubbleService :1819/:2224/:2393): it must stay @Volatile`.
(`main_thread_resets_never_corrupt_the_capture_thread_pump` passes already — it is the smoke test
that the storm produces no exception and no partial frame, not the substantive pin.)

- [ ] **Step 3: Minimal implementation.** In `SileroEndpointer.kt` mark every mutable field
  `@Volatile` — the fields block becomes exactly:

```kotlin
    /** The accumulator. One array for the life of the endpointer: no per-frame allocation. */
    private val frame = ByteArray(EndpointerTuning.FRAME_BYTES)

    // Written on the capture thread, cleared from Main. @Volatile for visibility, not atomicity:
    // the writes are not atomic TOGETHER, and a torn observation costs at most one 32 ms chunk of
    // slack — the exact tolerance SegmentCapPolicy documents for the same two callers.
    @Volatile private var fill = 0
    @Volatile private var lastFrameMs = 0L
    @Volatile private var speaking = false
    @Volatile private var speechStartMs = 0L
    @Volatile private var pendingSpeech = false
    @Volatile private var tempEndMs = 0L
    @Volatile private var prevEndMs = 0L
    @Volatile private var lastCommitMs = 0L
    @Volatile private var hasCommitted = false
    @Volatile private var minCommitIntervalMs = 8_000L
    @Volatile private var slowRun = 0
    @Volatile private var probeCutout = false
    @Volatile private var lastCutRecord: EndpointCut? = null
```

(`minCommitIntervalMs` is in the list for the same reason as the rest, and is the one field written
from MAIN and read from the capture thread rather than the other way round: `onSessionStart` runs on
Main at onOpen while the capture thread is about to start reading it every 32 ms.)

- [ ] **Step 4: Run tests green** — this section's two classes, then the whole suite, since Workstream
  C is complete here:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon; $x = Get-ChildItem 'C:\Users\bastr\.androidbuild\WhisperEverywhere\app\test-results\testDebugUnitTest\*.xml' | ForEach-Object { [xml](Get-Content -Raw $_.FullName) }; "{0} tests / {1} failures / {2} errors" -f (($x | ForEach-Object { [int]$_.testsuite.tests } | Measure-Object -Sum).Sum), (($x | ForEach-Object { [int]$_.testsuite.failures } | Measure-Object -Sum).Sum), (($x | ForEach-Object { [int]$_.testsuite.errors } | Measure-Object -Sum).Sum)
```

Expected: `failures=0 errors=0`. This task's own delta is +2 (`SileroEndpointerConcurrencyTest`);
the section's running delta after Task C10 is +45. No absolute total is asserted here — Task S5
computes the branch's totals once, from a purged results directory.

- [ ] **Step 5: Commit.**

```powershell
git add app/src/main/java/com/whispereverywhere/audio/SileroEndpointer.kt app/src/test/java/com/whispereverywhere/audio/SileroEndpointerConcurrencyTest.kt; git commit -m @'
feat(vad): @Volatile the endpointer state, pinned, with a real capture-thread storm

onFrame() runs on the capture thread while reset() is called from Main at three
sites (switchSource, onOpen, stopRecording) — the same split SegmentCapPolicy
already documents and solves the same way. Visibility, not atomicity: a torn
observation costs at most one 32 ms chunk of slack.

The pin is reflective on purpose, so dropping a @Volatile in a future refactor
fails the build instead of producing a stale read on a device. Alongside it, a real
single-thread executor pumps short reads and whole frames while Main hammers 5 000
resets: no exception, and the probe never sees a partial frame.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT
'@
```

---

### Task C10: `ProbeStats` wiring + the session probe seam (arm / teardown)

**Files:**
- Modify: `app/src/main/java/com/whispereverywhere/audio/SileroEndpointer.kt` (constructor; `timedProbe()`;
  `onSessionStart()`; new `onSessionEnd()` override)
- Test (modify): `app/src/test/java/com/whispereverywhere/audio/SileroEndpointerTest.kt` (append)

**Interfaces:**
- Consumes: `com.whispereverywhere.util.ProbeStats(budgetUs, emitIntervalMs)` with
  `record(elapsedUs, nowMs): Boolean` / `line()` / `reset()` (Task F1); `EndpointerTuning.PROBE_BUDGET_MS`
  (Task C1); `Endpointer.onSessionEnd()` (Task D2).
- Produces: three new DEFAULTED constructor parameters on `SileroEndpointer` —
  `probeStats: ProbeStats = ProbeStats(budgetUs = EndpointerTuning.PROBE_BUDGET_MS * 1_000L)`,
  `probeArm: () -> Unit = {}`, `probeTeardown: () -> Unit = {}` — and
  `override fun onSessionEnd()`. The endpointer owns the ONE `ProbeStats` instance: it records every
  probe call, logs `probe: frames=…` when the interval is due, resets it per session and emits one
  final line at session end.

**Why this is its own task.** Task F1 landed `ProbeStats` as a pure, dependency-free class and named
its wiring an explicit handoff, because the file that owns it did not exist yet. This is that handoff,
landed against the finished state machine — and it is also where the endpointer gains the two session
hooks `EndpointerFactory` (Task D8) needs to bind the native probe's lifecycle:
`probeArm` (arm the `VadProbeLifecycle` at session open; the one-time `init` still happens lazily on
the capture thread at the first frame) and `probeTeardown` (free the native context at session end,
after both capture threads have joined). Both are injected lambdas, so this class stays JVM-testable
with no JNI on the classpath.

The cutout latch keeps its OWN consecutive-overrun counter (`slowRun`, 16 frames over budget). That
is a different question from `ProbeStats`' session totals — "is the device failing to keep up right
now" versus "did this session ever miss the budget" — and conflating them would either latch on a
scattered handful of slow frames or never latch at all.

- [ ] **Step 1: Write the failing test.** Append to `SileroEndpointerTest.kt`:

```kotlin
    @Test fun every_probe_call_is_recorded_against_the_budget() {
        val clock = FakeClock()
        val probe = FakeProbe()
        probe.clock = clock
        probe.costMs = 1
        val stats = ProbeStats(budgetUs = EndpointerTuning.PROBE_BUDGET_MS * 1_000L)
        val ep = SileroEndpointer(probe = probe, nanoClock = clock, probeStats = stats)
        Pump(ep, probe).run(0.1f, 40)
        assertEquals(40L, stats.frames())
        assertEquals(0L, stats.overruns())
    }

    @Test fun overrunning_frames_reach_the_stats_and_the_latch_stops_the_count() {
        // ONE budget, two consumers: the latch (16 CONSECUTIVE) and the session totals. Once the
        // latch fires the probe is not called again, so the totals stop where the amplitude
        // fallback took over — which is exactly what the `probe:` line should report.
        val clock = FakeClock()
        val probe = FakeProbe()
        probe.clock = clock
        probe.costMs = 9
        val stats = ProbeStats(budgetUs = EndpointerTuning.PROBE_BUDGET_MS * 1_000L)
        val ep = SileroEndpointer(probe = probe, nanoClock = clock, probeStats = stats)
        Pump(ep, probe).run(0.1f, 40)
        assertTrue(ep.isProbeCutout())
        assertEquals(16L, stats.frames())
        assertEquals(16L, stats.overruns())
    }

    @Test fun a_new_session_resets_the_stats_arms_the_probe_and_session_end_tears_it_down() {
        val clock = FakeClock()
        val probe = FakeProbe()
        probe.clock = clock
        val stats = ProbeStats(budgetUs = EndpointerTuning.PROBE_BUDGET_MS * 1_000L)
        var arms = 0
        var teardowns = 0
        val ep = SileroEndpointer(
            probe = probe,
            nanoClock = clock,
            probeStats = stats,
            probeArm = { arms++ },
            probeTeardown = { teardowns++ },
        )
        val pump = Pump(ep, probe)
        pump.run(0.1f, 10)
        assertEquals(10L, stats.frames())

        ep.onSessionStart(pump.t, 1_200L)
        assertEquals("a session's probe accounting starts from zero", 0L, stats.frames())
        assertEquals("the native probe is armed once per session, on Main", 1, arms)
        assertEquals("arming must not free anything", 0, teardowns)

        ep.onSessionEnd()
        assertEquals("the native context is freed exactly once, at session end", 1, teardowns)
    }
```

Add the import at the top of the file: `import com.whispereverywhere.util.ProbeStats`.

- [ ] **Step 2: Run it, expect a compile failure.**

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests "com.whispereverywhere.audio.SileroEndpointerTest" --no-daemon
```

Expected: `> Task :app:compileDebugUnitTestKotlin FAILED` with
`e: ...\SileroEndpointerTest.kt:<n>:13 Cannot find a parameter with this name: probeStats`
(and the same for `probeArm` / `probeTeardown`). That compile failure IS the red for this task, and
it is the whole red: with `probeStats` unresolved the test source set does not compile, so no test
runs and no assertion failure is produced.

**What the red must NOT contain:** `Unresolved reference: onSessionEnd`. That member is inherited
from `Endpointer` as a no-op default (Task D2), so it resolves fine — seeing it unresolved means
D2's default body was not landed.

- [ ] **Step 3: Minimal implementation.** In `SileroEndpointer.kt`:

(a) add the three parameters to the constructor, after `nanoClock`:

```kotlin
    private val nanoClock: () -> Long = { System.nanoTime() },
    private val probeStats: ProbeStats =
        ProbeStats(budgetUs = EndpointerTuning.PROBE_BUDGET_MS * 1_000L),
    private val probeArm: () -> Unit = {},
    private val probeTeardown: () -> Unit = {},
) : Endpointer {
```

with the KDoc lines under `@param nanoClock`:

```
 * @param probeStats the session's probe cost/overrun accounting. ONE instance per endpointer: it is
 *        recorded on every probe call, emitted as the `probe:` line when its interval is due, reset
 *        at session start and emitted once more at session end. Defaulted so the state-machine tests
 *        need not supply one; [com.whispereverywhere.audio.EndpointerFactory] passes the real one.
 *        Two threads reach it: `record()` from the CAPTURE thread, `reset()` (onSessionStart) and
 *        `line()` (onSessionEnd) from MAIN — which is why every method on it is `@Synchronized`.
 * @param probeArm arms the native probe's lifecycle for a new session (Main). The context itself is
 *        still created lazily on the capture thread, at the first frame.
 * @param probeTeardown frees the native probe context at session end, after the capture threads have
 *        joined. Injected rather than called directly so this class needs no JNI on the classpath.
```

and add the import beside the file's other imports:

```kotlin
import com.whispereverywhere.util.ProbeStats
```

(b) add one field beside the other cost fields (it is a plain `val`, so no `@Volatile`; the budget is
a constant for the endpointer's life):

```kotlin
    private val budgetUs = EndpointerTuning.PROBE_BUDGET_MS * 1_000L
```

(c) replace `timedProbe()` — it now takes the frame's wall clock, because `ProbeStats` needs it to
decide when a line is due, and it measures in MICROSECONDS, the unit the `probe:` line reports:

```kotlin
    /**
     * One native probe call, timed. Two independent consumers of the same measurement:
     *  - [probeStats] accumulates the session's cost distribution and overrun total, and says when a
     *    `probe: frames=… p50=…µs p99=…µs overruns=…` line is due (at most one per 10 s);
     *  - [slowRun] is the LATCH's consecutive-overrun counter. After
     *    [EndpointerTuning.PROBE_CUTOUT_FRAMES] consecutive frames over
     *    [EndpointerTuning.PROBE_BUDGET_MS] the probe is latched off for the rest of the session and
     *    the caller falls back to amplitude. Latched, never retried per frame: the same discipline
     *    the new-segment callback uses for a throwing callback — something that failed 16 frames
     *    running will fail on the next one too, and retrying costs the audio thread every time.
     */
    private fun timedProbe(nowMs: Long): Float {
        val t0 = nanoClock()
        val p = probe(frame)
        val elapsedUs = (nanoClock() - t0) / 1_000L
        if (probeStats.record(elapsedUs, nowMs)) {
            android.util.Log.i("WE-DIAG", probeStats.line())
        }
        if (elapsedUs > budgetUs) {
            slowRun++
            if (slowRun >= EndpointerTuning.PROBE_CUTOUT_FRAMES) probeCutout = true
        } else {
            slowRun = 0
        }
        return p
    }
```

and update its one call site in `onFrame`:

```kotlin
            val p = timedProbe(nowMs)
```

(d) extend `onSessionStart()` — the stats reset and the arm go beside the cutout re-arm. Both run on
MAIN (Workstream D wires `onSessionStart` from `onOpen`), which is the other half of why
`ProbeStats` is instance-synchronized:

```kotlin
        probeStats.reset()
        probeArm()
```

(e) add the session-end override next to `onSessionStart()`:

```kotlin
    /**
     * Session end, **on MAIN**, from stopRecording AFTER both capture sources have stopped and
     * JOINED their threads and after the unconditional stop flush. [probeStats] is read from this
     * thread while the capture thread may still be writing it (E1's join is timed), which is what
     * its instance synchronisation is for. Emits the session's final `probe:` line
     * — unconditionally, because a session that never reached the 10 s interval would otherwise
     * report nothing at all, and "overruns=0 over 40 frames" is exactly the acceptance evidence —
     * then frees the native context.
     */
    override fun onSessionEnd() {
        android.util.Log.i("WE-DIAG", probeStats.line())
        probeTeardown()
    }
```

- [ ] **Step 4: Run tests green.**

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests "com.whispereverywhere.audio.*" --no-daemon; [xml]$x = Get-Content -Raw 'C:\Users\bastr\.androidbuild\WhisperEverywhere\app\test-results\testDebugUnitTest\TEST-com.whispereverywhere.audio.SileroEndpointerTest.xml'; "$($x.testsuite.tests) tests / $($x.testsuite.failures) failures / $($x.testsuite.errors) errors"
```

Expected: `39 tests / 0 failures / 0 errors`. Then the whole suite — Workstream C is complete here, so
this is the section's evidence: `failures=0 errors=0` and the **+45 delta** this section adds
(`EndpointerTuningTest` 4, `SileroEndpointerTest` 39, `SileroEndpointerConcurrencyTest` 2). The
absolute total is computed once, in Task S5.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon
```

- [ ] **Step 5: Commit.**

```powershell
git add app/src/main/java/com/whispereverywhere/audio/SileroEndpointer.kt app/src/test/java/com/whispereverywhere/audio/SileroEndpointerTest.kt; git commit -m @'
feat(vad): the endpointer owns ProbeStats, and the session probe seam

ProbeStats (Workstream F) had no owner while the endpointer did not exist. It has
one now: every probe call is recorded in microseconds, the `probe:` line is emitted
when its 10 s interval comes due, the accounting resets per session and a final
line is emitted at session end. The cutout latch keeps its own consecutive-overrun
counter — "is the device failing right now" is a different question from "did this
session ever miss the budget", and conflating them would either latch on a
scattered handful of slow frames or never latch at all.

onSessionStart/onSessionEnd also gain the two injected hooks the factory binds the
native probe's lifecycle to: arm on Main at session open (the context is still
created lazily on the capture thread), free at session end after the capture
threads have joined. Injected as lambdas, so this class still needs no JNI to test.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT
'@
```

## Workstream D — Integration seam, fallback, cadence policy (D3–D10)

---

### Task D3: `CommitCadencePolicy` — the measured per-tier cost governor

**Files:**
- Create `app/src/main/java/com/whispereverywhere/service/CommitCadencePolicy.kt`
- Create `app/src/test/java/com/whispereverywhere/service/CommitCadencePolicyTest.kt`

**Interfaces:**
- Consumes: `WhisperModel.id` values from `com.whispereverywhere.model.WhisperCatalog.entries` — `"eco"`, `"base"`, `"pro"`, `"multi"`, `"extreme"`, `"ultra"` (verified `model/WhisperModel.kt:63–132`).
- Produces: `object CommitCadencePolicy` with
  - `MIN_COMMIT_INTERVAL_FAST_MS = 1_200L`, `MIN_COMMIT_INTERVAL_MULTI_MS = 6_000L`, `MIN_COMMIT_INTERVAL_LARGE_MS = 8_000L`, `MIN_COMMIT_INTERVAL_CLOUD_MS = 3_000L`, `CAP_CUT_MAX_RETAIN_MS = 3_000L`
  - `fun minCommitIntervalMs(tierId: String?, isCloudBatch: Boolean): Long`
  - `fun capCutRetainMs(nowMs: Long, cutPointMs: Long): Long`

- [ ] **Step 1: Write the failing test.** Create `app/src/test/java/com/whispereverywhere/service/CommitCadencePolicyTest.kt`:
```kotlin
package com.whispereverywhere.service

import com.whispereverywhere.audio.Endpointer
import com.whispereverywhere.model.WhisperCatalog
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The 3.7 cost governor, JVM-pinned. Every number here is MEASURED (Fold6, vc77, floor 512,
 * production backends) or derived from a measurement — changing one is a decision, not an edit.
 */
class CommitCadencePolicyTest {

    @Test
    fun theShippedIntervalsAreTheMeasuredOnes() {
        assertEquals(1_200L, CommitCadencePolicy.MIN_COMMIT_INTERVAL_FAST_MS)
        assertEquals(6_000L, CommitCadencePolicy.MIN_COMMIT_INTERVAL_MULTI_MS)
        assertEquals(8_000L, CommitCadencePolicy.MIN_COMMIT_INTERVAL_LARGE_MS)
        assertEquals(3_000L, CommitCadencePolicy.MIN_COMMIT_INTERVAL_CLOUD_MS)
        assertEquals(3_000L, CommitCadencePolicy.CAP_CUT_MAX_RETAIN_MS)
    }

    @Test
    fun proRunsTrueUtteranceCadence() {
        // F = 0.77-1.0 s measured on GPU: below ~1.1 s a commit is zero-padded to the same
        // encoder cost anyway, so merging beats committing.
        assertEquals(1_200L, CommitCadencePolicy.minCommitIntervalMs("pro", isCloudBatch = false))
    }

    @Test
    fun theSixtyMbTiersRideTheSameFastCadenceAsPro() {
        assertEquals(1_200L, CommitCadencePolicy.minCommitIntervalMs("eco", isCloudBatch = false))
        assertEquals(1_200L, CommitCadencePolicy.minCommitIntervalMs("base", isCloudBatch = false))
    }

    @Test
    fun multiIsPacedByItsMeasuredFixedCost() {
        // F=2.3 s, m~0.45, S=38.4 s/min: F*N + m*S <= 0.70*60 -> N <= ~10.7 commits/min.
        assertEquals(6_000L, CommitCadencePolicy.minCommitIntervalMs("multi", isCloudBatch = false))
    }

    @Test
    fun theUnmeasuredLargeTiersGetTheConservativeInterval() {
        assertEquals(8_000L, CommitCadencePolicy.minCommitIntervalMs("extreme", isCloudBatch = false))
        assertEquals(8_000L, CommitCadencePolicy.minCommitIntervalMs("ultra", isCloudBatch = false))
    }

    @Test
    fun anUnknownOrAbsentTierAssumesTheExpensiveEnd() {
        assertEquals(8_000L, CommitCadencePolicy.minCommitIntervalMs(null, isCloudBatch = false))
        assertEquals(8_000L, CommitCadencePolicy.minCommitIntervalMs("smallish", isCloudBatch = false))
    }

    @Test
    fun everyCatalogTierIsNamedExplicitly() {
        // A tier added to the catalog without a cadence decision silently inherits the 8 s
        // conservative default and nobody notices. This is the alarm for that.
        val expected = mapOf(
            "eco" to 1_200L, "base" to 1_200L, "pro" to 1_200L,
            "multi" to 6_000L, "extreme" to 8_000L, "ultra" to 8_000L,
        )
        assertEquals(
            "a catalog tier gained or lost an entry — decide its cadence",
            expected.keys,
            WhisperCatalog.entries.map { it.id }.toSet(),
        )
        for ((id, interval) in expected) {
            assertEquals(id, interval, CommitCadencePolicy.minCommitIntervalMs(id, isCloudBatch = false))
        }
    }

    @Test
    fun cloudBatchRaisesAFastTierToTheRequestFloor() {
        // Every batch commit is one HTTP POST: Semaphore(3) in flight, shed at 24.
        assertEquals(3_000L, CommitCadencePolicy.minCommitIntervalMs("pro", isCloudBatch = true))
        assertEquals(3_000L, CommitCadencePolicy.minCommitIntervalMs("eco", isCloudBatch = true))
    }

    @Test
    fun cloudBatchIsAFlatFloorForEveryTier() {
        // The spec's tuning table lists cloud batch as ONE row, not as a per-tier maximum, and
        // isCloudBatch wins outright. In CLOUD_WITH_FALLBACK the cloud engine is primary and the
        // local mirror only transcribes on a rescue, so pacing every cloud session at the slower
        // local tier's floor would slow the engine that is actually doing the work; the
        // failure-path drain is bounded by the reserve mechanics, not by this number.
        assertEquals(3_000L, CommitCadencePolicy.minCommitIntervalMs("multi", isCloudBatch = true))
        assertEquals(3_000L, CommitCadencePolicy.minCommitIntervalMs("ultra", isCloudBatch = true))
        assertEquals(3_000L, CommitCadencePolicy.minCommitIntervalMs(null, isCloudBatch = true))
    }

    @Test
    fun noOfferMeansNoSplit() {
        assertEquals(0L, CommitCadencePolicy.capCutRetainMs(nowMs = 50_000L, cutPointMs = Endpointer.NO_CUT_POINT))
        assertEquals(0L, CommitCadencePolicy.capCutRetainMs(nowMs = 50_000L, cutPointMs = -1L))
    }

    @Test
    fun aRecentMicroPauseIsTheRetainedTail() {
        assertEquals(900L, CommitCadencePolicy.capCutRetainMs(nowMs = 50_000L, cutPointMs = 49_100L))
        assertEquals(3_000L, CommitCadencePolicy.capCutRetainMs(nowMs = 50_000L, cutPointMs = 47_000L))
    }

    @Test
    fun aStaleOfferIsRefusedRatherThanDeferringHalfTheWindow() {
        // A pause 13 s back is not "the boundary near where the cap fired": taking it would defer
        // 13 s of audio into the next cap window and push the effective wall bound to ~28 s.
        assertEquals(0L, CommitCadencePolicy.capCutRetainMs(nowMs = 50_000L, cutPointMs = 37_000L))
        assertEquals(0L, CommitCadencePolicy.capCutRetainMs(nowMs = 50_000L, cutPointMs = 46_999L))
    }

    @Test
    fun aFutureOrEqualCutPointIsRefused() {
        assertEquals(0L, CommitCadencePolicy.capCutRetainMs(nowMs = 50_000L, cutPointMs = 50_000L))
        assertEquals(0L, CommitCadencePolicy.capCutRetainMs(nowMs = 50_000L, cutPointMs = 50_500L))
    }
}
```

- [ ] **Step 2: Run it, expect a compile failure.**
```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests "com.whispereverywhere.service.CommitCadencePolicyTest" --no-daemon
```
Expected: `> Task :app:compileDebugUnitTestKotlin FAILED` with
`e: ...CommitCadencePolicyTest.kt:...: Unresolved reference: CommitCadencePolicy`.

- [ ] **Step 3: Minimal implementation.** Create `app/src/main/java/com/whispereverywhere/service/CommitCadencePolicy.kt`:
```kotlin
package com.whispereverywhere.service

import com.whispereverywhere.audio.Endpointer

/**
 * The 3.7 commit-cadence governor (Workstream D3) — ONE policy object, all values MEASURED on the
 * Fold6 at vc77 with the 512 audio_ctx floor and production backends.
 *
 * A real endpointer cuts at real pauses; what it must NOT do is cut faster than the tier can pay
 * for. The arithmetic is `F*N + m*S <= 0.70 * 60 s` per minute of session, where F is the fixed
 * per-commit cost, m the steady rtf and S the speech seconds (conserved — same speech, same
 * tokens, only the number of encoder passes changes).
 *
 * - pro: F = 0.77-1.0 s (GPU) -> true utterance cadence. Below ~1.1 s a commit is zero-padded to
 *   the same encoder cost as a 2.4 s one, so merging strictly beats committing.
 * - multi: F = 2.3 s (CPU) -> N <= ~10.7 commits/min -> a 6 s floor. Predictable ~2.8 s
 *   speech-end-to-text at the paced boundary, and no 15 s walls.
 * - extreme/ultra (539-574 MB): UNMEASURED. 8 s is the conservative placeholder; H2 may revise it.
 * - cloud batch: every commit is one HTTP POST (Semaphore(3) in flight, shed at 24). Same
 *   reasoning that made the 4 s first cap LOCAL-only.
 *
 * Pure and Context-free so every number is JVM-pinned (CommitCadencePolicyTest).
 */
object CommitCadencePolicy {

    /** pro / eco / base — any tier whose measured F is at or under ~1.2 s. */
    const val MIN_COMMIT_INTERVAL_FAST_MS = 1_200L

    /** multi: derived from F = 2.3 s at a 0.70 duty ceiling. */
    const val MIN_COMMIT_INTERVAL_MULTI_MS = 6_000L

    /**
     * extreme / ultra, and any tier this build does not recognise: assume the expensive end.
     *
     * This row stays live configuration after Workstream H retires those tiers from the CHOOSER:
     * retirement hides a tier from fresh installs and changes nothing for the users who already
     * have one, and those are exactly the users this number paces.
     */
    const val MIN_COMMIT_INTERVAL_LARGE_MS = 8_000L

    /** cloud batch: the provider-request floor, orthogonal to the local tier. */
    const val MIN_COMMIT_INTERVAL_CLOUD_MS = 3_000L

    /**
     * The oldest micro-pause the wall cap will still cut at. An offer older than this is not the
     * boundary near where the cap fired — taking it would defer most of the window into the next
     * one and push the effective wall bound from 15 s to ~28 s. Owner-tunable knob.
     */
    const val CAP_CUT_MAX_RETAIN_MS = 3_000L

    /**
     * The minimum interval between endpoint-driven commits for this session.
     *
     * **Cloud batch wins outright — a FLAT 3 000 for every tier**, exactly as the spec's tuning
     * table lists it. In a cloud-batch session the cloud engine is the primary transcriber and the
     * local mirror only runs on a rescue, so pacing the whole session at the local tier's floor
     * would slow the engine doing the work in order to protect one that usually does none; the
     * cost of the failure path is bounded by the drain reserve, not by this interval. (Owner
     * acceptance watches the other side of that trade: the multi-tier cloud sessions'
     * `finalize-timing: local-drain` in the Task S3 sheet is the evidence that would reopen it.)
     *
     * [tierId] is `WhisperModel.id`; null/unrecognised assumes the expensive end. The app cannot
     * reach a recording session without an installed model, so that branch is defensive only.
     */
    fun minCommitIntervalMs(tierId: String?, isCloudBatch: Boolean): Long {
        if (isCloudBatch) return MIN_COMMIT_INTERVAL_CLOUD_MS
        return when (tierId) {
            "eco", "base", "pro" -> MIN_COMMIT_INTERVAL_FAST_MS
            "multi" -> MIN_COMMIT_INTERVAL_MULTI_MS
            "extreme", "ultra" -> MIN_COMMIT_INTERVAL_LARGE_MS
            else -> MIN_COMMIT_INTERVAL_LARGE_MS
        }
    }

    /**
     * How many trailing milliseconds the wall-cap commit should RETAIN, given the endpointer's
     * remembered micro-pause [cutPointMs] (wall clock) at cap time [nowMs].
     *
     * Returns 0 — i.e. "commit everything, exactly as 3.6.0 did" — for no offer, a
     * future/equal offer, and a stale offer. That zero is what makes the cap path byte-identical
     * whenever the endpointer never fired, which is the untouchable this function must not break.
     */
    fun capCutRetainMs(nowMs: Long, cutPointMs: Long): Long {
        if (cutPointMs <= Endpointer.NO_CUT_POINT) return 0L
        val retain = nowMs - cutPointMs
        if (retain <= 0L) return 0L
        return if (retain > CAP_CUT_MAX_RETAIN_MS) 0L else retain
    }
}
```

- [ ] **Step 4: Run tests green.**
```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests "com.whispereverywhere.service.CommitCadencePolicyTest" --no-daemon
```
Then the full suite + the aggregation command from Global Constraints. Expected: `failures=0 errors=0` and the **+13 delta** for this task (`CommitCadencePolicyTest`).

- [ ] **Step 5: Commit.**
```powershell
git add app/src/main/java/com/whispereverywhere/service/CommitCadencePolicy.kt app/src/test/java/com/whispereverywhere/service/CommitCadencePolicyTest.kt; git commit -m @'
feat(vad): CommitCadencePolicy — the measured per-tier cost governor

1200 pro/eco/base, 6000 multi (F=2.3s at a 0.70 duty ceiling), 8000
extreme/ultra (unmeasured, conservative), and a FLAT 3000 for cloud batch on
every tier: in a cloud session the cloud engine is primary and the local mirror
only runs on a rescue, so the local tier's floor must not pace it. Also carries
the cap-cut micro-pause window (CAP_CUT_MAX_RETAIN_MS=3000): a stale offer
returns 0, which is what keeps the wall cap byte-identical when nothing was
offered.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT
'@
```

---

### Task D4: The `VadProbe` seam + `VadProbeLifecycle`

**Files:**
- Create `app/src/main/java/com/whispereverywhere/audio/VadProbe.kt` (interface + `NativeVadProbe`)
- Create `app/src/main/java/com/whispereverywhere/audio/VadProbeLifecycle.kt`
- Create `app/src/test/java/com/whispereverywhere/audio/VadProbeLifecycleTest.kt`

**Interfaces:**
- Consumes (Workstream A — **this task lands after A's externs task**): `WhisperNative.vadProbeInit(modelPath: String): Boolean`, `WhisperNative.vadProbeFrame(pcm16: java.nio.ByteBuffer, nBytes: Int): Float`, `WhisperNative.vadProbeReset()`, `WhisperNative.vadProbeFree()`. Also `EndpointerTuning.FRAME_BYTES` / `EndpointerTuning.NO_VERDICT` (Task C1, same package, lands first in the binding order) — the SINGLE OWNER of the native frame contract.
- Produces:
  - `interface VadProbe { fun init(modelPath: String): Boolean; fun frame(pcm16: java.nio.ByteBuffer, nBytes: Int): Float; fun reset(); fun free() }` with `VadProbe.FRAME_BYTES` and `VadProbe.NO_VERDICT` declared as ALIASES of the `EndpointerTuning` constants (`= EndpointerTuning.FRAME_BYTES` / `= EndpointerTuning.NO_VERDICT`), never as second literals
  - `object NativeVadProbe : VadProbe`
  - `class VadProbeLifecycle(probe: VadProbe)` with `enum class State { IDLE, ARMED, READY, UNAVAILABLE }`, `state(): State`, `arm(modelPath: String?)`, `ensureReady(): Boolean`, `reset()`, `release()`

- [ ] **Step 1: Write the failing test.** Create `app/src/test/java/com/whispereverywhere/audio/VadProbeLifecycleTest.kt`:
```kotlin
package com.whispereverywhere.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/** Scriptable probe: counts every native call and can fail or throw on init. */
class FakeVadProbe(
    private val initReturns: Boolean = true,
    private val initThrows: Boolean = false,
) : VadProbe {
    val calls = mutableListOf<String>()
    var initCalls = 0
    var resetCalls = 0
    var freeCalls = 0
    var lastPath: String? = null

    override fun init(modelPath: String): Boolean {
        initCalls++; lastPath = modelPath; calls += "init"
        if (initThrows) throw RuntimeException("probe init blew up")
        return initReturns
    }
    override fun frame(pcm16: ByteBuffer, nBytes: Int): Float = 0.9f
    override fun reset() { resetCalls++; calls += "reset" }
    override fun free() { freeCalls++; calls += "free" }
}

/**
 * The probe's init/free lifecycle on the CAPTURE path (3.7, Workstream D). The rules this pins:
 * init exactly once, lazily, off Main; a failed init latches for the whole session and is never
 * retried per frame; free only ever follows a successful init; a fresh arm() re-initialises.
 */
class VadProbeLifecycleTest {

    @Test
    fun aNullModelPathIsUnavailableAndNeverTouchesTheProbe() {
        val probe = FakeVadProbe()
        val life = VadProbeLifecycle(probe)
        life.arm(null)
        assertEquals(VadProbeLifecycle.State.UNAVAILABLE, life.state())
        assertFalse(life.ensureReady())
        assertEquals(0, probe.initCalls)
    }

    @Test
    fun initHappensOnTheFirstFrame_exactlyOnce() {
        val probe = FakeVadProbe()
        val life = VadProbeLifecycle(probe)
        life.arm("/data/vad/ggml-silero-v5.1.2.bin")
        // Arming alone must NOT initialise: arm() runs on Main, init must run on the capture thread.
        assertEquals(VadProbeLifecycle.State.ARMED, life.state())
        assertEquals(0, probe.initCalls)

        assertTrue(life.ensureReady())
        assertEquals(VadProbeLifecycle.State.READY, life.state())
        assertEquals(1, probe.initCalls)
        assertEquals("/data/vad/ggml-silero-v5.1.2.bin", probe.lastPath)

        // 31.25 calls/second for the rest of the session — all of them free.
        repeat(100) { assertTrue(life.ensureReady()) }
        assertEquals(1, probe.initCalls)
    }

    @Test
    fun aFailedInitLatchesForTheSessionAndIsNeverRetried() {
        val probe = FakeVadProbe(initReturns = false)
        val life = VadProbeLifecycle(probe)
        life.arm("/data/vad/model.bin")
        assertFalse(life.ensureReady())
        assertEquals(VadProbeLifecycle.State.UNAVAILABLE, life.state())
        repeat(500) { assertFalse(life.ensureReady()) }
        assertEquals("a failed init must never be retried per frame", 1, probe.initCalls)
    }

    @Test
    fun aThrowingInitLatchesAndDoesNotEscapeToTheCaptureThread() {
        val probe = FakeVadProbe(initThrows = true)
        val life = VadProbeLifecycle(probe)
        life.arm("/data/vad/model.bin")
        assertFalse(life.ensureReady())      // must not throw: the audio thread must not die
        assertEquals(VadProbeLifecycle.State.UNAVAILABLE, life.state())
        repeat(10) { assertFalse(life.ensureReady()) }
        assertEquals(1, probe.initCalls)
    }

    @Test
    fun resetOnlyReachesTheProbeOnceItIsReady() {
        val probe = FakeVadProbe()
        val life = VadProbeLifecycle(probe)
        life.reset()                          // IDLE
        life.arm("/data/vad/model.bin")
        life.reset()                          // ARMED but not initialised
        assertEquals(0, probe.resetCalls)
        life.ensureReady()
        life.reset(); life.reset()
        assertEquals(2, probe.resetCalls)
    }

    @Test
    fun freeOnlyEverFollowsASuccessfulInit() {
        val never = FakeVadProbe()
        val neverLife = VadProbeLifecycle(never)
        neverLife.arm("/data/vad/model.bin")
        neverLife.release()
        assertEquals("never initialised, so nothing to free", 0, never.freeCalls)
        assertEquals(VadProbeLifecycle.State.IDLE, neverLife.state())

        val probe = FakeVadProbe()
        val life = VadProbeLifecycle(probe)
        life.arm("/data/vad/model.bin")
        life.ensureReady()
        life.release()
        assertEquals(listOf("init", "free"), probe.calls)
        life.release()
        assertEquals("release is idempotent", 1, probe.freeCalls)
    }

    @Test
    fun aFreshSessionReArmsAndReInitialises() {
        val probe = FakeVadProbe()
        val life = VadProbeLifecycle(probe)
        life.arm("/data/vad/model.bin"); life.ensureReady(); life.release()
        life.arm("/data/vad/model.bin"); assertTrue(life.ensureReady()); life.release()
        assertEquals(listOf("init", "free", "init", "free"), probe.calls)
    }

    @Test
    fun theFrameContractConstantsAreTheNativeOnes() {
        // 512 samples PCM16 mono @16 kHz = 1024 bytes = exactly one Silero window; anything else
        // returns "no verdict", never "silence" — a zero-padded short frame still advances the
        // LSTM and poisons the recurrence.
        assertEquals(1024, VadProbe.FRAME_BYTES)
        assertEquals(-1.0f, VadProbe.NO_VERDICT, 0.0f)
    }
}
```

- [ ] **Step 2: Run it, expect a compile failure.**
```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests "com.whispereverywhere.audio.VadProbeLifecycleTest" --no-daemon
```
Expected: `> Task :app:compileDebugUnitTestKotlin FAILED` with
`e: ...VadProbeLifecycleTest.kt:...: Unresolved reference: VadProbe` and `Unresolved reference: VadProbeLifecycle`.

- [ ] **Step 3: Minimal implementation.** Create `app/src/main/java/com/whispereverywhere/audio/VadProbe.kt`:
```kotlin
package com.whispereverywhere.audio

import com.whispereverywhere.whisper.WhisperNative
import java.nio.ByteBuffer

/**
 * Thin seam over the four streaming-VAD externs (3.7, Workstream A) so the Kotlin endpointer is
 * JVM-testable without JNI — the same discipline `WhisperBackend` applies to `whisper_full`.
 *
 * The probe deliberately does NOT go through `NativeComputeGate`: its context is forced CPU-only
 * natively regardless of params, owns its own backend, work buffers and sched, and costs ~2.6 MB.
 * Routing 32 ms frames through a FAIR process-global lock would queue them behind 4-15 s
 * whisper_full calls (or Batch's ~54 s gate holds) and recreate the exact stall 3.7 fixes. That
 * argument is recorded here because this seam is the only Kotlin-side place it is visible.
 */
interface VadProbe {
    /** Load the Silero model into the dedicated probe context. False = unavailable this session. */
    fun init(modelPath: String): Boolean

    /**
     * One 512-sample window. [pcm16] MUST be a DIRECT buffer and [nBytes] MUST be
     * [FRAME_BYTES] — anything else returns [NO_VERDICT], never a silence verdict.
     */
    fun frame(pcm16: ByteBuffer, nBytes: Int): Float

    /** Zero the LSTM recurrence (h/c state only; model weights are in a different buffer). */
    fun reset()

    /** Free the probe context. */
    fun free()

    companion object {
        // ALIASES, not literals. [EndpointerTuning] (Task C1, same package) is the SINGLE OWNER of
        // the native frame contract; these exist so a caller holding a VadProbe need not import the
        // tuning object. EndpointerFactory sizes its direct buffer from one of these pairs and
        // fills it from the other, so two independent literals would be a BufferOverflowException
        // on the capture thread — or a native sentinel silently readable as a probability.

        /** 512 samples of PCM16 mono @16 kHz — exactly one Silero window. */
        const val FRAME_BYTES = EndpointerTuning.FRAME_BYTES

        /** "No verdict." NEVER to be read as silence: a short frame poisons the recurrence. */
        const val NO_VERDICT = EndpointerTuning.NO_VERDICT
    }
}

/** Production probe: delegates to the four `WhisperNative` externs. */
object NativeVadProbe : VadProbe {
    override fun init(modelPath: String): Boolean = WhisperNative.vadProbeInit(modelPath)
    override fun frame(pcm16: ByteBuffer, nBytes: Int): Float = WhisperNative.vadProbeFrame(pcm16, nBytes)
    override fun reset() = WhisperNative.vadProbeReset()
    override fun free() = WhisperNative.vadProbeFree()
}
```
Create `app/src/main/java/com/whispereverywhere/audio/VadProbeLifecycle.kt`:
```kotlin
package com.whispereverywhere.audio

/**
 * Owns WHEN the native probe context exists (3.7, Workstream D8) — never WHAT it decides.
 *
 * The rules, all of which have in-repo precedent:
 *  - [arm] runs on MAIN at session open and only records the path. Initialising there would put
 *    a model load on the UI thread.
 *  - [ensureReady] runs on the CAPTURE thread from the first [Endpointer.onFrame] and performs
 *    the one-time init, so the probe context is created on the thread that will use it.
 *  - A failed or throwing init LATCHES `UNAVAILABLE` for the whole session and is never retried
 *    per frame — the same discipline `we_on_new_segment` uses for a throwing callback ("a
 *    callback that threw once will almost certainly throw on every remaining segment").
 *  - [release] runs on MAIN from stopRecording, AFTER both capture sources have stopped and
 *    joined, and frees only a context that was actually created.
 */
class VadProbeLifecycle(private val probe: VadProbe) {

    enum class State { IDLE, ARMED, READY, UNAVAILABLE }

    @Volatile private var currentState = State.IDLE
    @Volatile private var modelPath: String? = null

    fun state(): State = currentState

    /** Session open (Main). A null path means "no VAD model" — unavailable, probe untouched. */
    fun arm(modelPath: String?) {
        this.modelPath = modelPath
        currentState = if (modelPath == null) State.UNAVAILABLE else State.ARMED
    }

    /** Capture thread, per frame. Cheap after the first call. @return true when the probe is usable. */
    fun ensureReady(): Boolean {
        val snapshot = currentState
        if (snapshot == State.READY) return true
        if (snapshot != State.ARMED) return false
        val path = modelPath
        if (path == null) {
            currentState = State.UNAVAILABLE
            return false
        }
        val ok = try {
            probe.init(path)
        } catch (t: Throwable) {
            // Must never escape: this runs inline on the audio thread.
            android.util.Log.w("WE-DIAG", "probe: init threw — amplitude fallback for this session", t)
            false
        }
        currentState = if (ok) State.READY else State.UNAVAILABLE
        if (!ok) android.util.Log.w("WE-DIAG", "probe: init failed — amplitude fallback for this session")
        return ok
    }

    /** One of the five reset sites, or the endpointer's own post-commit reset. */
    fun reset() {
        if (currentState == State.READY) runCatching { probe.reset() }
    }

    /** Session end (Main), after the capture threads have joined. Idempotent. */
    fun release() {
        if (currentState == State.READY) runCatching { probe.free() }
        currentState = State.IDLE
        modelPath = null
    }
}
```

- [ ] **Step 4: Run tests green.**
```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests "com.whispereverywhere.audio.VadProbeLifecycleTest" --no-daemon
```
Then the full suite + the aggregation command from Global Constraints. Expected: `failures=0 errors=0` and the **+8 delta** for this task (`VadProbeLifecycleTest`).

- [ ] **Step 5: Commit.**
```powershell
git add app/src/main/java/com/whispereverywhere/audio/VadProbe.kt app/src/main/java/com/whispereverywhere/audio/VadProbeLifecycle.kt app/src/test/java/com/whispereverywhere/audio/VadProbeLifecycleTest.kt; git commit -m @'
feat(vad): VadProbe seam + the probe's capture-path init/free lifecycle

VadProbe mirrors what WhisperBackend does for whisper_full: the four streaming
externs behind a JVM-testable interface, with the outside-NativeComputeGate
safety argument recorded where Kotlin can see it. VadProbeLifecycle pins the
rules — arm on Main, init lazily on the capture thread, latch a failed init for
the whole session, free only what was initialised, re-arm per session.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT
'@
```

---

### Task D5: `VadProbeLifecycle` under real concurrency — no double init, no free-before-init

**Files:**
- Create `app/src/test/java/com/whispereverywhere/audio/VadProbeLifecycleConcurrencyTest.kt`
- Modify `app/src/main/java/com/whispereverywhere/audio/VadProbeLifecycle.kt` (add the init/release monitor)

**Interfaces:**
- Consumes: `VadProbeLifecycle(probe: VadProbe)`, `VadProbe` (Task D4).
- Produces: no new names — the same API, now with `ensureReady`/`release` mutually exclusive.

- [ ] **Step 1: Write the failing test.** Create `app/src/test/java/com/whispereverywhere/audio/VadProbeLifecycleConcurrencyTest.kt`:
```kotlin
package com.whispereverywhere.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * REAL background executors, never a same-thread stub: the two threads this class exists to
 * separate are genuinely different threads in production — the capture thread calling
 * ensureReady() 31.25 times a second, and Main calling release() from stopRecording. A
 * same-thread stub cannot express either race.
 */
class VadProbeLifecycleConcurrencyTest {

    /** init/free take real time, so an unsynchronised lifecycle loses the race deterministically. */
    private class SlowProbe(private val initMs: Long = 150L) : VadProbe {
        val order = CopyOnWriteArrayList<String>()
        val initCalls = AtomicInteger(0)
        val freeCalls = AtomicInteger(0)
        override fun init(modelPath: String): Boolean {
            initCalls.incrementAndGet()
            order += "init-enter"
            Thread.sleep(initMs)
            order += "init-exit"
            return true
        }
        override fun frame(pcm16: ByteBuffer, nBytes: Int): Float = 0.9f
        override fun reset() { order += "reset" }
        override fun free() { freeCalls.incrementAndGet(); order += "free" }
    }

    @Test
    fun twoCaptureThreadsRacingTheFirstFrameInitialiseExactlyOnce() {
        val probe = SlowProbe()
        val life = VadProbeLifecycle(probe)
        life.arm("/data/vad/model.bin")

        val pool = Executors.newFixedThreadPool(2)
        try {
            val go = CountDownLatch(1)
            val done = CountDownLatch(2)
            val results = CopyOnWriteArrayList<Boolean>()
            repeat(2) {
                pool.execute {
                    go.await(5, TimeUnit.SECONDS)
                    results += life.ensureReady()
                    done.countDown()
                }
            }
            go.countDown()
            assertTrue("both workers must finish", done.await(10, TimeUnit.SECONDS))
            assertEquals(listOf(true, true), results.toList())
            assertEquals("the native probe context must be created exactly once", 1, probe.initCalls.get())
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun releaseNeverFreesAContextThatIsStillBeingCreated() {
        val probe = SlowProbe(initMs = 300L)
        val life = VadProbeLifecycle(probe)
        life.arm("/data/vad/model.bin")

        val pool = Executors.newFixedThreadPool(2)
        try {
            val initStarted = CountDownLatch(1)
            val done = CountDownLatch(2)
            pool.execute {
                initStarted.countDown()
                life.ensureReady()
                done.countDown()
            }
            assertTrue(initStarted.await(5, TimeUnit.SECONDS))
            Thread.sleep(50)                     // land inside the in-flight init
            pool.execute { life.release(); done.countDown() }
            assertTrue(done.await(10, TimeUnit.SECONDS))

            assertEquals(
                "free must never interleave with an in-flight init",
                listOf("init-enter", "init-exit", "free"),
                probe.order.toList(),
            )
            assertEquals(1, probe.freeCalls.get())
            assertEquals(VadProbeLifecycle.State.IDLE, life.state())
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun aFrameArrivingAfterReleaseDoesNotResurrectTheProbe() {
        val probe = SlowProbe(initMs = 0L)
        val life = VadProbeLifecycle(probe)
        life.arm("/data/vad/model.bin")
        life.ensureReady()
        life.release()

        val pool = Executors.newSingleThreadExecutor()
        try {
            val done = CountDownLatch(1)
            val late = CopyOnWriteArrayList<Boolean>()
            pool.execute { late += life.ensureReady(); done.countDown() }
            assertTrue(done.await(5, TimeUnit.SECONDS))
            assertEquals(listOf(false), late.toList())
            assertEquals("a late frame must not re-init a released probe", 1, probe.initCalls.get())
        } finally {
            pool.shutdownNow()
        }
    }
}
```

- [ ] **Step 2: Run it, expect the double init.**
```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests "com.whispereverywhere.audio.VadProbeLifecycleConcurrencyTest" --no-daemon
```
Expected:
`twoCaptureThreadsRacingTheFirstFrameInitialiseExactlyOnce FAILED` —
`java.lang.AssertionError: the native probe context must be created exactly once expected:<1> but was:<2>`,
and `releaseNeverFreesAContextThatIsStillBeingCreated FAILED` —
`expected:<[init-enter, init-exit, free]> but was:<[init-enter, init-exit]>`, accompanied by
`freeCalls expected:<1> but was:<0>` and `expected:<IDLE> but was:<READY>`.

**Read that second red correctly — the pre-fix bug is a LEAKED context, not an interleaved free.**
Task D4's `release()` is `if (currentState == State.READY) runCatching { probe.free() }`, and
`ensureReady()` only assigns `READY` AFTER `probe.init(path)` returns. So during an in-flight init
the state is still `ARMED`: the un-synchronised release sees it, SKIPS the free entirely, sets
`IDLE` — and the finishing init then overwrites that with `READY`. Nothing is ever freed and the
lifecycle claims it is ready, which is the second half of what the monitor fixes.

- [ ] **Step 3: Minimal implementation.** In `app/src/main/java/com/whispereverywhere/audio/VadProbeLifecycle.kt`, add a monitor and take it around the init and the free. The volatile fast path stays, so the 31.25 Hz steady-state cost is one volatile read:
```kotlin
    /**
     * Guards the two operations that touch the native context's EXISTENCE. The steady-state
     * per-frame path never takes it: [ensureReady] returns on the volatile READY read above it.
     * [release] runs on Main only after the capture threads have joined, so in production this
     * lock is uncontended — it exists so a mis-ordered teardown degrades to a wait instead of a
     * free-under-init.
     */
    private val contextLock = Any()

    fun ensureReady(): Boolean {
        if (currentState == State.READY) return true          // hot path: one volatile read
        synchronized(contextLock) {
            val snapshot = currentState
            if (snapshot == State.READY) return true
            if (snapshot != State.ARMED) return false
            val path = modelPath
            if (path == null) {
                currentState = State.UNAVAILABLE
                return false
            }
            val ok = try {
                probe.init(path)
            } catch (t: Throwable) {
                android.util.Log.w("WE-DIAG", "probe: init threw — amplitude fallback for this session", t)
                false
            }
            currentState = if (ok) State.READY else State.UNAVAILABLE
            if (!ok) android.util.Log.w("WE-DIAG", "probe: init failed — amplitude fallback for this session")
            return ok
        }
    }

    fun release() {
        synchronized(contextLock) {
            if (currentState == State.READY) runCatching { probe.free() }
            currentState = State.IDLE
            modelPath = null
        }
    }
```
(`arm` and `reset` keep their volatile-only form: `arm` is Main-before-capture-start, and `reset`
only ever calls into a context whose existence is already established.)

- [ ] **Step 4: Run tests green.**
```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests "com.whispereverywhere.audio.VadProbeLifecycle*" --no-daemon
```
Then the full suite + the aggregation command from Global Constraints. Expected: `failures=0 errors=0` and the **+3 delta** for this task (`VadProbeLifecycleConcurrencyTest`).

- [ ] **Step 5: Commit.**
```powershell
git add app/src/main/java/com/whispereverywhere/audio/VadProbeLifecycle.kt app/src/test/java/com/whispereverywhere/audio/VadProbeLifecycleConcurrencyTest.kt; git commit -m @'
fix(vad): the probe context is created once and never freed under an in-flight init

Two capture threads racing the first frame used to build two native contexts;
a stopRecording release landing inside an in-flight init used to free one that
did not exist yet. A monitor around exactly those two operations fixes both;
the 31.25 Hz steady-state path still returns on a single volatile read. Pinned
with real background executors, not same-thread stubs.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT
'@
```

---

### Task D6: `commitRetainingTailMs` — the cap cut's buffer split

**Files:**
- Modify `app/src/main/java/com/whispereverywhere/transcription/TranscriptionEngine.kt` (add a defaulted member after `commit()` at :23, before `close()` at :26)
- Modify `app/src/main/java/com/whispereverywhere/transcription/LocalWhisperEngine.kt` (add the override after `commit()` ends at :240; add `BYTES_PER_MS` to the private companion at :62–72)
- Create `app/src/test/java/com/whispereverywhere/transcription/LocalWhisperEngineCapSplitTest.kt`

**Interfaces:**
- Consumes: `LocalWhisperEngine(modelPathProvider, retry, backend, executor, deltaClock)`, `FakeModelPathProvider`, `RecordingListener` (existing test fakes in `LocalWhisperEngineTest.kt`, same package), `AudioMath.pcm16ToFloat` (samples = bytes/2, no padding — verified `LocalWhisperEngine.kt:288`).
- Produces: `TranscriptionEngine.commitRetainingTailMs(retainMs: Long): Long` (default `= commit()`), overridden in `LocalWhisperEngine`. `LocalWhisperEngine.BYTES_PER_MS = 32`.

- [ ] **Step 1: Write the failing test.** Create `app/src/test/java/com/whispereverywhere/transcription/LocalWhisperEngineCapSplitTest.kt`:
```kotlin
package com.whispereverywhere.transcription

import com.whispereverywhere.util.RetryPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 3.7 Workstream D: when the 15 s wall cap fires with the gate open, the commit may keep the
 * trailing audio since the endpointer's remembered micro-pause, so the boundary lands in a pause
 * instead of mid-word (`no_context = true` makes a mid-word cap boundary unrepairable).
 *
 * REAL background executor — LocalWhisperEngine's own single-thread default. The split happens
 * under bufferLock while the capture thread is still calling sendAudio, so a same-thread stub
 * would prove nothing about the contract this test exists to pin.
 */
class LocalWhisperEngineCapSplitTest {

    private class SizeRecordingBackend(private val done: CountDownLatch) : WhisperBackend {
        val sampleCounts = CopyOnWriteArrayList<Int>()
        override fun load(modelPath: String): Long = 42L
        override fun transcribe(ctx: Long, samples: FloatArray, lang: String?, useVad: Boolean): String {
            sampleCounts.add(samples.size)
            done.countDown()
            return "seg"
        }
        override fun release(ctx: Long) = Unit
    }

    private fun engine(backend: WhisperBackend) = LocalWhisperEngine(
        modelPathProvider = FakeModelPathProvider("/models/pro.bin"),
        retry = RetryPolicy(maxAttempts = 1, baseDelayMs = 0, maxDelayMs = 0, rng = { 0.0 }),
        backend = backend,
        // executor deliberately left at its default: a REAL single-thread background executor.
    )

    /** 32 bytes of PCM16 mono @16 kHz is exactly 1 ms. */
    private fun pcm(ms: Int) = ByteArray(ms * 32) { 0x11 }

    @Test
    fun retainZeroIsExactlyCommit() {
        val doneA = CountDownLatch(1)
        val doneB = CountDownLatch(1)
        val a = SizeRecordingBackend(doneA)
        val b = SizeRecordingBackend(doneB)
        val ea = engine(a); val eb = engine(b)
        ea.connect(null, RecordingListener()); eb.connect(null, RecordingListener())

        ea.sendAudio(pcm(500)); eb.sendAudio(pcm(500))
        val seqA = ea.commit()
        val seqB = eb.commitRetainingTailMs(0L)

        assertTrue(doneA.await(10, TimeUnit.SECONDS) && doneB.await(10, TimeUnit.SECONDS))
        assertEquals(seqA, seqB)
        assertEquals(a.sampleCounts.toList(), b.sampleCounts.toList())
        assertEquals(listOf(8_000), b.sampleCounts.toList())     // 500 ms * 16 samples/ms
        ea.close(); eb.close()
    }

    @Test
    fun theRetainedTailStaysBufferedAndRidesTheNextSegment() {
        val done = CountDownLatch(2)
        val backend = SizeRecordingBackend(done)
        val e = engine(backend)
        e.connect(null, RecordingListener())

        e.sendAudio(pcm(5_000))
        assertEquals(0L, e.commitRetainingTailMs(800L))          // cut at the micro-pause
        e.sendAudio(pcm(200))
        assertEquals(1L, e.commit())

        assertTrue(done.await(10, TimeUnit.SECONDS))
        // 4 200 ms committed, then the retained 800 ms + the new 200 ms.
        assertEquals(listOf(67_200, 16_000), backend.sampleCounts.toList())
        e.close()
    }

    @Test
    fun aRetainLongerThanTheBufferCommitsEverythingRatherThanDeferringIt() {
        val done = CountDownLatch(1)
        val backend = SizeRecordingBackend(done)
        val e = engine(backend)
        e.connect(null, RecordingListener())

        e.sendAudio(pcm(100))
        assertEquals(0L, e.commitRetainingTailMs(3_000L))
        assertTrue(done.await(10, TimeUnit.SECONDS))
        assertEquals(listOf(1_600), backend.sampleCounts.toList())
        // and the buffer really is empty: a cap that already fired never defers its whole window.
        assertEquals(-1L, e.commit())
        e.close()
    }

    @Test
    fun anEmptyBufferStillReturnsNoSegment() {
        val backend = SizeRecordingBackend(CountDownLatch(1))
        val e = engine(backend)
        e.connect(null, RecordingListener())
        assertEquals(-1L, e.commitRetainingTailMs(800L))
        assertEquals(0, backend.sampleCounts.size)
        e.close()
    }

    @Test
    fun everyOtherEngineKeepsPlainCommitBehaviour() {
        // The default on the interface is a plain commit(), so cloud / live / fallback engines are
        // byte-unchanged: retaining PCM behind a wrapper's back would desynchronise its mirror.
        val calls = mutableListOf<String>()
        val plain = object : TranscriptionEngine {
            override fun connect(language: String?, listener: TranscriptionEngine.Listener) = Unit
            override fun sendAudio(pcm: ByteArray) = Unit
            override fun commit(): Long { calls += "commit"; return 7L }
            override fun close() = Unit
        }
        assertEquals(7L, plain.commitRetainingTailMs(1_234L))
        assertEquals(listOf("commit"), calls)
    }
}
```

- [ ] **Step 2: Run it, expect a compile failure.**
```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests "com.whispereverywhere.transcription.LocalWhisperEngineCapSplitTest" --no-daemon
```
Expected: `> Task :app:compileDebugUnitTestKotlin FAILED` with
`e: ...LocalWhisperEngineCapSplitTest.kt:...: Unresolved reference: commitRetainingTailMs`.

- [ ] **Step 3: Minimal implementation.** In `TranscriptionEngine.kt`, insert between `commit()` (ends :23) and `close()` (:26):
```kotlin
    /**
     * Commit everything buffered EXCEPT the last [retainMs] milliseconds, which stay buffered and
     * open the next segment (3.7, Workstream D).
     *
     * Its ONE caller is the wall-cap branch, and it is handed the endpointer's remembered
     * micro-pause via `CommitCadencePolicy.capCutRetainMs`, which returns 0 whenever there is no
     * usable offer. `commitRetainingTailMs(0)` is therefore required to be exactly [commit] —
     * that identity is what keeps the wall cap byte-identical to 3.6.0 under an endpointer that
     * never fires, and it is pinned by test.
     *
     * Default: a plain [commit]. Retaining audio is a LOCAL-engine affordance — a cloud engine's
     * commit is an HTTP POST and the fallback wrapper mirrors PCM per committed seq, so keeping
     * bytes back behind either one's back would desynchronise a mirror rather than improve a
     * boundary. Cloud/live/batch sessions keep 3.6.0 behaviour by inheriting this.
     */
    fun commitRetainingTailMs(retainMs: Long): Long = commit()
```
In `LocalWhisperEngine.kt`, add to the private companion (after `TRANSCRIBE_FAILED` at :71):
```kotlin
        /** PCM16 mono @16 kHz: 16 000 samples/s * 2 bytes = 32 bytes per millisecond. */
        const val BYTES_PER_MS = 32
```
and add the override immediately after `commit()`'s closing brace (:240):
```kotlin
    /**
     * [commit], minus a trailing tail (3.7, Workstream D). See the interface KDoc for why
     * `retainMs <= 0` must be indistinguishable from [commit] — the first line here is that
     * guarantee, not an optimisation.
     *
     * The split is computed INSIDE bufferLock together with the seq, for the same reason [commit]
     * allocates its seq there: the capture thread is still calling sendAudio, and a snapshot taken
     * outside the lock would let a chunk land between the read and the rewrite.
     */
    override fun commitRetainingTailMs(retainMs: Long): Long {
        if (retainMs <= 0L) return commit()

        val myListener = this.listener
        if (myListener == null) {
            android.util.Log.i("WE-DIAG", "commit: no listener (session ended), skipped")
            return NO_SEGMENT
        }
        val lang = this.language

        val (seq, pcm, retainedBytes) = synchronized(bufferLock) {
            val snapshot = buffer.toByteArray()
            if (snapshot.isEmpty()) {
                android.util.Log.i("WE-DIAG", "commit: pcmBytes=0 -> nothing to cut")
                return NO_SEGMENT
            }
            // Aligned DOWN to a whole PCM16 frame so a split can never land mid-sample.
            val retain = (retainMs * BYTES_PER_MS)
                .coerceAtMost(snapshot.size.toLong())
                .toInt() and 1.inv()
            val cut = snapshot.size - retain
            if (cut <= 0) {
                // The offer covers the whole window. A cap that has already fired must never
                // defer its entire buffer, so this degrades to a plain full commit.
                buffer.reset()
                Triple(nextSeq++, snapshot, 0)
            } else {
                buffer.reset()
                buffer.write(snapshot, cut, retain)
                Triple(nextSeq++, snapshot.copyOfRange(0, cut), retain)
            }
        }
        android.util.Log.i("WE-DIAG", "commit: seq=$seq pcmBytes=${pcm.size} samples=${pcm.size / 2}")
        android.util.Log.i(
            "WE-DIAG",
            "cap-cut split: seq=$seq retainedTailBytes=$retainedBytes retainedMs=${retainedBytes / BYTES_PER_MS}",
        )
        executor.execute { runSegment(seq, pcm, lang, myListener) }
        return seq
    }
```

- [ ] **Step 4: Run tests green.**
```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests "com.whispereverywhere.transcription.*" --no-daemon
```
Then the full suite + the aggregation command from Global Constraints. Expected: `failures=0 errors=0` and the **+5 delta** for this task (`LocalWhisperEngineCapSplitTest`).

- [ ] **Step 5: Commit.**
```powershell
git add app/src/main/java/com/whispereverywhere/transcription/TranscriptionEngine.kt app/src/main/java/com/whispereverywhere/transcription/LocalWhisperEngine.kt app/src/test/java/com/whispereverywhere/transcription/LocalWhisperEngineCapSplitTest.kt; git commit -m @'
feat(vad): commitRetainingTailMs — the wall cap can cut at a micro-pause

The 15s backstop can now keep the trailing audio since the endpointer's last
observed silence dip, so its boundary lands in a pause instead of mid-word,
which no_context=true makes unrepairable. retainMs<=0 is EXACTLY commit(), by
first line and by test — that identity is what keeps the cap byte-identical
under an endpointer that never fires. Default on the interface is a plain
commit(), so cloud/live/fallback engines are byte-unchanged.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT
'@
```

---

### Task D7: `capCutConsumesWindow` — the cap-cut bookkeeping predicate, JVM-pinned

**Files:**
- Modify `app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt` (add a top-level `internal fun` after `processingTimerRunsIn` ends at :167, before `class FloatingBubbleService` at :169; use it at :1716)
- Create `app/src/test/java/com/whispereverywhere/service/CapCutBookkeepingTest.kt`

**Interfaces:**
- Consumes: nothing new.
- Produces: `internal fun capCutConsumesWindow(hasPendingSpeech: Boolean, isCloudSession: Boolean): Boolean` in package `com.whispereverywhere.service`.

*Why this is not a restructuring:* the `else if` block keeps its exact shape; one boolean
expression becomes a named call, exactly as `connectingStatusLabel` (:154) and
`processingTimerRunsIn` (:165) already do for two other in-service branches. It STRENGTHENS the
untouchable — the three-way cap-cut rule gains a JVM pin it has never had.

- [ ] **Step 1: Write the failing test.** Create `app/src/test/java/com/whispereverywhere/service/CapCutBookkeepingTest.kt`:
```kotlin
package com.whispereverywhere.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wall-cap cut's policy bookkeeping, extracted from FloatingBubbleService's `else if` and
 * pinned (3.7, Workstream D). The branch itself is untouched — only the predicate under it is now
 * a named, tested unit, which matters because 3.7 makes hasPendingSpeech() HONEST for the soft
 * talker who was permanently false under the amplitude segmenter.
 *
 *  - real speech, any session  -> consume the first-cap window and restart the clock
 *  - CLOUD silence             -> also consume: the 4 s window must NEVER re-open on cloud
 *                                 (`cap=4000ms` in a cloud session is the bug signature)
 *  - LOCAL silence             -> re-arm, so a user who pauses to think still gets the 4 s first
 *                                 cut on their first real speech (3.5.0 parity guarantee)
 */
class CapCutBookkeepingTest {

    @Test
    fun realSpeechConsumesTheWindowInEverySession() {
        assertTrue(capCutConsumesWindow(hasPendingSpeech = true, isCloudSession = false))
        assertTrue(capCutConsumesWindow(hasPendingSpeech = true, isCloudSession = true))
    }

    @Test
    fun cloudSilenceStillConsumesTheWindow() {
        // Re-opening the 4 s window on cloud costs an extra billable provider request.
        assertTrue(capCutConsumesWindow(hasPendingSpeech = false, isCloudSession = true))
    }

    @Test
    fun localSilenceReArmsTheFirstCapWindow() {
        assertFalse(capCutConsumesWindow(hasPendingSpeech = false, isCloudSession = false))
    }

    @Test
    fun theRuleIsExhaustiveOverBothInputs() {
        val truthTable = listOf(
            Triple(true, true, true),
            Triple(true, false, true),
            Triple(false, true, true),
            Triple(false, false, false),
        )
        for ((speech, cloud, expected) in truthTable) {
            org.junit.Assert.assertEquals(
                "speech=$speech cloud=$cloud",
                expected,
                capCutConsumesWindow(speech, cloud),
            )
        }
    }
}
```

- [ ] **Step 2: Run it, expect a compile failure.**
```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests "com.whispereverywhere.service.CapCutBookkeepingTest" --no-daemon
```
Expected: `> Task :app:compileDebugUnitTestKotlin FAILED` with
`e: ...CapCutBookkeepingTest.kt:...: Unresolved reference: capCutConsumesWindow`.

- [ ] **Step 3: Minimal implementation.** In `FloatingBubbleService.kt`, insert after line 167 (the end of `processingTimerRunsIn`) and before line 169 (`class FloatingBubbleService`):
```kotlin

/**
 * Whether a WALL-CAP cut consumes the session's first-cap window (3.7, Workstream D — the
 * predicate only; the `else if` branch it sits under is unchanged).
 *
 * A cap cut on silence-only audio still commits the buffer — that part is unconditional. What is
 * conditional is the bookkeeping:
 *  - real speech (any session): consume the window and restart the clock;
 *  - CLOUD silence: also consume — the 4 s window must NEVER re-open on cloud, because a 4 s
 *    cloud cut is an extra billable provider request and `cap=4000ms` in a cloud session is the
 *    documented regression signature;
 *  - LOCAL silence: re-arm, so a user who pauses to think still gets the 4 s first cut on their
 *    first real speech (3.5.0 parity guarantee).
 *
 * Pure and JVM-pinned (CapCutBookkeepingTest). This matters more under 3.7 than it did under
 * 3.6.0: `hasPendingSpeech()` becomes HONEST — the soft talker in a noisy room, permanently false
 * under the amplitude segmenter, now reports true — so this branch changes behaviour for exactly
 * the users it was mis-serving, with no edit to the branch itself.
 */
internal fun capCutConsumesWindow(hasPendingSpeech: Boolean, isCloudSession: Boolean): Boolean =
    hasPendingSpeech || isCloudSession
```
Then at :1716 replace the inline expression, leaving every surrounding line byte-identical:
```kotlin
                if (capCutConsumesWindow(speechSegmenter.hasPendingSpeech(), cloudWrapper != null)) {
```

- [ ] **Step 4: Run tests green.**
```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests "com.whispereverywhere.service.*" --no-daemon
```
Then the full suite + the aggregation command from Global Constraints. Expected: `failures=0 errors=0` and the **+4 delta** for this task (`CapCutBookkeepingTest`).

- [ ] **Step 5: Commit.**
```powershell
git add app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt app/src/test/java/com/whispereverywhere/service/CapCutBookkeepingTest.kt; git commit -m @'
refactor(vad): name and pin the cap-cut bookkeeping predicate

The three-way rule (real speech consumes; cloud silence consumes; local silence
re-arms) becomes capCutConsumesWindow, following connectingStatusLabel and
processingTimerRunsIn. The else-if branch is untouched — only the boolean under
it is now tested. It matters more in 3.7 because hasPendingSpeech() becomes
honest for the soft talker it was permanently mis-answering.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT
'@
```

---

### Task D8: `EndpointerFactory` — the ONE selection site, with the fallback pinned

**Files:**
- Create `app/src/main/java/com/whispereverywhere/audio/EndpointerFactory.kt`
- Create `app/src/test/java/com/whispereverywhere/audio/EndpointerFactoryTest.kt`

**Interfaces:**
- Consumes (Workstream C — **this task lands after C's tasks**):
  `class SileroEndpointer(probe: (ByteArray) -> Float, probeReset: () -> Unit = {}, nanoClock: () -> Long = …, probeStats: ProbeStats = …, probeArm: () -> Unit = {}, probeTeardown: () -> Unit = {}) : Endpointer`
  (Tasks C2, C7, C10). C's endpointer is deliberately JNI-free: it takes a `(ByteArray) -> Float`
  lambda, not a `VadProbe`, so the whole state machine is JVM-testable. **This factory is where the
  two halves are bound** — it owns the `VadProbeLifecycle`, the ONE direct `ByteBuffer` the native
  contract requires, and the four lambdas (`probe`, `probeReset`, `probeArm`, `probeTeardown`).
- Consumes: `VadProbe` / `NativeVadProbe` / `VadProbeLifecycle` (Tasks D4, D5), `AmplitudeEndpointer`
  (Task D2), `com.whispereverywhere.util.ProbeStats` (Task F1), `EndpointerTuning.PROBE_BUDGET_MS`
  (Task C1), `VadModel.path(): String?` (`transcription/VadModel.kt:20`, read by the caller).
- Produces: `internal object EndpointerFactory { fun create(vadModelPath: String?, probe: VadProbe = NativeVadProbe): Endpointer }`.

- [ ] **Step 1: Write the failing test.** Create `app/src/test/java/com/whispereverywhere/audio/EndpointerFactoryTest.kt`:
```kotlin
package com.whispereverywhere.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fallback tier 1 (3.7, Workstream D7), pinned: model missing -> AmplitudeEndpointer -> a full
 * session byte-identical to 3.6.0. This is not a new failure mode — `VadModel.path()` already
 * returns null and already logs "running without VAD".
 */
class EndpointerFactoryTest {

    @Test
    fun aNullModelPathSelectsTheAmplitudeFallback() {
        val probe = FakeVadProbe()
        val endpointer = EndpointerFactory.create(vadModelPath = null, probe = probe)
        assertTrue(
            "a missing VAD model must yield the 3.6.0 path, not a degraded Silero one",
            endpointer is AmplitudeEndpointer,
        )
        assertEquals("the probe must not be touched at all", 0, probe.initCalls)
        assertEquals(0, probe.freeCalls)
    }

    @Test
    fun theAmplitudeFallbackOffersNoCutPointSoTheCapStaysByteIdentical() {
        val endpointer = EndpointerFactory.create(vadModelPath = null, probe = FakeVadProbe())
        endpointer.onSessionStart(nowMs = 0L, minCommitIntervalMs = 6_000L)
        val chunk = ByteArray(1024)
        repeat(600) { i -> endpointer.onFrame(chunk, 5_000, i * 32L) }   // 19.2 s of loud audio
        assertEquals(Endpointer.NO_CUT_POINT, endpointer.pendingCutPointMs())
        endpointer.onSessionEnd()
    }

    @Test
    fun aResolvedModelPathSelectsTheSileroEndpointer() {
        val probe = FakeVadProbe()
        val endpointer = EndpointerFactory.create(vadModelPath = "/data/vad/model.bin", probe = probe)
        assertTrue(endpointer is SileroEndpointer)
    }

    @Test
    fun constructionNeverInitialisesTheProbe() {
        // Construction runs on MAIN at service construction; init belongs on the capture thread,
        // at the first frame (VadProbeLifecycle's contract).
        val probe = FakeVadProbe()
        EndpointerFactory.create(vadModelPath = "/data/vad/model.bin", probe = probe)
        assertEquals(0, probe.initCalls)
    }

    @Test
    fun theSileroPathArmsAtSessionStartAndFreesAtSessionEnd() {
        // The binding this factory exists for: C's endpointer knows nothing about JNI, so the
        // native context's arm/init/free lifecycle is wired here, through lambdas. arm() only
        // records the path — the one-time init still happens on the CAPTURE thread at the first
        // frame — and free() must follow a session, not a construction.
        val probe = FakeVadProbe()
        val endpointer = EndpointerFactory.create(vadModelPath = "/data/vad/model.bin", probe = probe)
        endpointer.onSessionStart(nowMs = 1_000L, minCommitIntervalMs = 1_200L)
        assertEquals("arming must not load the model", 0, probe.initCalls)

        // One whole frame on the capture path: NOW the context is created, exactly once.
        endpointer.onFrame(ByteArray(VadProbe.FRAME_BYTES), 0, 1_032L)
        assertEquals(1, probe.initCalls)
        assertEquals("the probe is fed the reused DIRECT buffer, one frame at a time", 0, probe.freeCalls)

        endpointer.onSessionEnd()
        assertEquals(1, probe.freeCalls)

        // A second session re-arms and re-initialises rather than staying dead.
        endpointer.onSessionStart(nowMs = 5_000L, minCommitIntervalMs = 1_200L)
        endpointer.onFrame(ByteArray(VadProbe.FRAME_BYTES), 0, 5_032L)
        assertEquals(2, probe.initCalls)
    }
}
```
(`FakeVadProbe` is the fake introduced in Task D4's `VadProbeLifecycleTest.kt`, same package.)

- [ ] **Step 2: Run it, expect a compile failure.**
```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests "com.whispereverywhere.audio.EndpointerFactoryTest" --no-daemon
```
Expected: `> Task :app:compileDebugUnitTestKotlin FAILED` with
`e: ...EndpointerFactoryTest.kt:...: Unresolved reference: EndpointerFactory`.

- [ ] **Step 3: Minimal implementation.** Create `app/src/main/java/com/whispereverywhere/audio/EndpointerFactory.kt`:
```kotlin
package com.whispereverywhere.audio

import com.whispereverywhere.util.ProbeStats
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The ONE place 3.7 decides which endpointer the session runs, and the ONE place the pure state
 * machine is bound to the native probe (Workstream D1/D7).
 *
 * Chosen once, at FloatingBubbleService construction, on nothing more than whether the bundled
 * Silero model resolved on disk. `VadModel.path()` already returns null and already logs "running
 * without VAD" when extraction fails, so the null branch is the shipped 3.6.0 path — the fallback
 * costs no new failure mode and no new code path.
 *
 * THE BINDING. [SileroEndpointer] takes a `(ByteArray) -> Float` lambda rather than a [VadProbe],
 * so the whole state machine is JVM-testable with no JNI on the classpath. Everything JNI-shaped
 * therefore lives here: the [VadProbeLifecycle] that owns WHEN the native context exists, the ONE
 * `ByteBuffer.allocateDirect(FRAME_BYTES)` in `nativeOrder()` the frame contract requires (the
 * native side reads it through `GetDirectBufferAddress`, so a heap buffer would not work at all,
 * and one buffer refilled forever is what keeps the capture thread allocation-free at 31.25 Hz),
 * and the four lambdas the endpointer calls.
 *
 * Note what is NOT decided here: cadence (per session, per tier — see
 * [com.whispereverywhere.service.CommitCadencePolicy]) and probe initialisation (per session, on
 * the capture thread — [VadProbeLifecycle.ensureReady] runs at the first frame). Construction must
 * stay cheap and Main-safe: nothing below touches the probe.
 */
internal object EndpointerFactory {

    fun create(vadModelPath: String?, probe: VadProbe = NativeVadProbe): Endpointer {
        if (vadModelPath == null) {
            android.util.Log.i("WE-DIAG", "endpointer: amplitude (no VAD model — 3.6.0 behaviour)")
            return AmplitudeEndpointer()
        }
        android.util.Log.i("WE-DIAG", "endpointer: silero (streaming probe)")
        val lifecycle = VadProbeLifecycle(probe)
        // ONE direct buffer for the endpointer's life. Position/limit/mark are ignored by the
        // native side (it reads [0, nBytes) from the base address), so it is refilled forever.
        val buffer = ByteBuffer.allocateDirect(VadProbe.FRAME_BYTES).order(ByteOrder.nativeOrder())
        return SileroEndpointer(
            probe = { frame ->
                // ensureReady() is the ONE-TIME init, and it happens HERE: on the capture thread,
                // at the first frame. A failed or throwing init latches UNAVAILABLE for the whole
                // session, and NO_VERDICT is never read as silence.
                if (!lifecycle.ensureReady()) {
                    VadProbe.NO_VERDICT
                } else {
                    buffer.clear()
                    buffer.put(frame, 0, VadProbe.FRAME_BYTES)
                    probe.frame(buffer, VadProbe.FRAME_BYTES)
                }
            },
            probeReset = { lifecycle.reset() },
            probeStats = ProbeStats(budgetUs = EndpointerTuning.PROBE_BUDGET_MS * 1_000L),
            probeArm = { lifecycle.arm(vadModelPath) },
            probeTeardown = { lifecycle.release() },
        )
    }
}
```

- [ ] **Step 4: Run tests green.**
```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests "com.whispereverywhere.audio.*" --no-daemon
```
Then the full suite + the aggregation command from Global Constraints. Expected: `failures=0 errors=0`
and the **+5 delta** for this task (`EndpointerFactoryTest`).

- [ ] **Step 5: Commit.**
```powershell
git add app/src/main/java/com/whispereverywhere/audio/EndpointerFactory.kt app/src/test/java/com/whispereverywhere/audio/EndpointerFactoryTest.kt; git commit -m @'
feat(vad): EndpointerFactory — one selection site, one probe binding, fallback pinned

Silero when VadModel.path() resolves, AmplitudeEndpointer when it does not.
The null branch is the shipped 3.6.0 path wearing the interface, so a missing
model is not a new failure mode; pinned by asserting the fallback never offers
a cap cut point and never touches the probe.

This is also the seam between the pure state machine and JNI: SileroEndpointer
takes a (ByteArray) -> Float lambda so it stays JVM-testable, and everything
JNI-shaped — the VadProbeLifecycle, the one reused direct ByteBuffer the frame
contract requires, and the arm/reset/free lambdas — is assembled here.
Construction stays Main-safe: cadence and probe init are both per-session, and
the one-time init happens on the capture thread at the first frame.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT
'@
```

---

### Task D9: Wire the seam at `FloatingBubbleService.kt:1689–1724` — verdict swap + cap-cut split

**Files:**
- Modify `app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt`
  (import :47; field :250; seam :1691, :1716, :1721–1722; the three surviving
  `speechSegmenter.reset()` call sites at :1819, :2224, :2393 — **rename only**)
- Create `app/src/test/java/com/whispereverywhere/service/CapSeamPinTest.kt`

**Interfaces:**
- Consumes: `EndpointerFactory.create(vadModelPath, probe)` (Task D8), `Endpointer` (Task D2), `CommitCadencePolicy.capCutRetainMs(nowMs, cutPointMs)` (Task D3), `TranscriptionEngine.commitRetainingTailMs(retainMs)` (Task D6), `capCutConsumesWindow(...)` (Task D7), `VadModel.path()`.
- Produces: `FloatingBubbleService.endpointer: Endpointer` (private field, the ONE instance for the service's life). The field `speechSegmenter` is **removed**.

- [ ] **Step 1: Write the failing test.** Create `app/src/test/java/com/whispereverywhere/service/CapSeamPinTest.kt`:
```kotlin
package com.whispereverywhere.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * UNTOUCHABLE #1, pinned structurally (3.7): the wall caps live in the `else if`, OUTSIDE the
 * verdict, so an endpointer that never fires leaves cap behaviour byte-identical to 3.6.0.
 *
 * FloatingBubbleService is an Android Service and cannot be instantiated in a JVM test, so the
 * behavioural half of this contract is pinned by the pure units the seam calls
 * (CapCutBookkeepingTest, CommitCadencePolicyTest, LocalWhisperEngineCapSplitTest,
 * AmplitudeEndpointerTest) and the STRUCTURAL half is pinned here, on the source itself. A
 * refactor that nests the cap check inside the verdict, or that gates `sendAudio`, fails this
 * test loudly instead of shipping.
 */
class CapSeamPinTest {

    private fun source(relative: String): File {
        var dir: File? = File(System.getProperty("user.dir")!!).absoluteFile
        while (dir != null) {
            for (candidate in listOf(File(dir, relative), File(dir, "app/$relative"))) {
                if (candidate.isFile) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError("cannot locate $relative from ${System.getProperty("user.dir")}")
    }

    private val text: String by lazy {
        source("src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt").readText()
    }

    private fun indexOfOrFail(needle: String): Int {
        val i = text.indexOf(needle)
        assertTrue("missing from FloatingBubbleService.kt: <<$needle>>", i >= 0)
        return i
    }

    @Test
    fun sendAudioIsUnconditionalAndFirst() {
        val send = indexOfOrFail("        engine.sendAudio(chunk)\n")
        val gate = indexOfOrFail("LiveTurnPolicy.runClientVad(sessionIsLive)")
        assertTrue("sendAudio must precede the client-VAD gate", send < gate)
        assertEquals("sendAudio must appear exactly once in onAudioChunk", 1, text.split("engine.sendAudio(chunk)").size - 1)
    }

    @Test
    fun theEndpointerIsTheVerdictInsideTheIf() {
        indexOfOrFail("            if (endpointer.onFrame(chunk, amp, now)) {")
    }

    @Test
    fun theWallCapIsTheElseIfAndThereIsExactlyOneOfThem() {
        indexOfOrFail("            } else if (segmentCapPolicy.capExceeded(now)) {")
        assertEquals(
            "the cap check must exist exactly once, and as the else-if",
            1,
            text.split("segmentCapPolicy.capExceeded(").size - 1,
        )
    }

    @Test
    fun theCapBranchKeepsItsBookkeepingAndItsUnconditionalCommit() {
        val cap = indexOfOrFail("            } else if (segmentCapPolicy.capExceeded(now)) {")
        val bookkeeping = indexOfOrFail(
            "                if (capCutConsumesWindow(endpointer.hasPendingSpeech(), cloudWrapper != null)) {"
        )
        val commit = indexOfOrFail("                engine.commitRetainingTailMs(retainMs)")
        val reset = text.indexOf("                endpointer.reset()", commit)
        assertTrue("bookkeeping stays inside the cap branch", bookkeeping > cap)
        assertTrue("the commit follows the bookkeeping", commit > bookkeeping)
        assertTrue("the endpointer is reset after the cap commit", reset > commit)
    }

    @Test
    fun theCapCutAsksTheCadencePolicyForItsRetainWindow() {
        indexOfOrFail(
            "                val retainMs = CommitCadencePolicy.capCutRetainMs(now, endpointer.pendingCutPointMs())"
        )
    }

    @Test
    fun theAmplitudeSegmenterIsNoLongerCalledDirectly() {
        assertEquals(
            "the service must reach the segmenter only through AmplitudeEndpointer",
            0,
            text.split("speechSegmenter").size - 1,
        )
    }

    @Test
    fun theEndpointerIsChosenExactlyOnce() {
        assertEquals(1, text.split("EndpointerFactory.create(").size - 1)
    }
}
```

- [ ] **Step 2: Run it, expect the structural assertions to fire.**
```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests "com.whispereverywhere.service.CapSeamPinTest" --no-daemon
```
Expected: 5 of 7 fail, headed by
`theEndpointerIsTheVerdictInsideTheIf FAILED — java.lang.AssertionError: missing from FloatingBubbleService.kt: <<            if (endpointer.onFrame(chunk, amp, now)) {>>`
and `theAmplitudeSegmenterIsNoLongerCalledDirectly FAILED — expected:<0> but was:<8>`.

- [ ] **Step 3: Minimal implementation.** Four edits to `FloatingBubbleService.kt`.

(a) Line 47: replace `import com.whispereverywhere.util.SpeechSegmenter` with
```kotlin
import com.whispereverywhere.audio.Endpointer
import com.whispereverywhere.audio.EndpointerFactory
```
(keep alphabetical order: these sort above `com.whispereverywhere.net.ConnectivityMonitor` at :35 —
place them immediately after `import com.whispereverywhere.WhisperEverywhereApp` at :34).

(b) Line 250: replace `private val speechSegmenter = SpeechSegmenter()` with
```kotlin
    /**
     * The ONE commit-decision surface for this service's life (3.7, Workstream D1). Chosen HERE,
     * at construction, on nothing but whether the bundled Silero model resolved: a null path
     * yields AmplitudeEndpointer, which wraps the very SpeechSegmenter this field used to hold —
     * so "VAD model missing" is byte-identical shipped behaviour rather than a new path.
     *
     * Deliberately not per-session: the model path is process-constant. What IS per-session is the
     * cadence floor and the probe's native context, both handed over in onOpen via
     * [Endpointer.onSessionStart] and torn down in stopRecording via [Endpointer.onSessionEnd].
     *
     * Cost note: `VadModel.path()` may copy the 885 KB asset on the FIRST service construction
     * after an install; every later call returns its cached @Volatile path.
     */
    private val endpointer: Endpointer =
        EndpointerFactory.create(com.whispereverywhere.transcription.VadModel.path())
```

(c) The seam. Line 1691 becomes the endpointer call; lines 1716 and 1721–1722 take the cap-cut
split. Every other line in :1689–1724 — including the entire comment block at :1696–1720 and the
`else if` itself — stays byte-identical:
```kotlin
            if (endpointer.onFrame(chunk, amp, now)) {
```
```kotlin
                if (capCutConsumesWindow(endpointer.hasPendingSpeech(), cloudWrapper != null)) {
                    segmentCapPolicy.onCommit(now)
                } else {
                    segmentCapPolicy.onSessionStart(now)
                }
                // 3.7 (Workstream D): when the endpointer remembers a micro-pause inside this
                // window, cut THERE and keep the tail — a strictly better boundary for the same
                // latency bound, because `no_context = true` makes a mid-word cap cut permanently
                // unrepairable. capCutRetainMs returns 0 for no offer, a stale offer, and for the
                // amplitude endpointer always; commitRetainingTailMs(0) IS engine.commit(). So an
                // endpointer that never fires leaves this branch byte-identical to 3.6.0.
                val retainMs = CommitCadencePolicy.capCutRetainMs(now, endpointer.pendingCutPointMs())
                engine.commitRetainingTailMs(retainMs)
                endpointer.reset()
```
Also update the comment at :1705 to name the new call:
`// hasPendingSpeech() is read BEFORE endpointer.reset(), which clears the flag.`

(d) **The three remaining `speechSegmenter.reset()` call sites — rename, nothing more.** Deleting the
field in (b) without these leaves `switchSource` (:1819), `onOpen` (:2224) and `stopRecording`
(:2393) referencing a field that no longer exists, so `compileDebugKotlin` fails with three
`Unresolved reference: speechSegmenter` and this task's own
`theAmplitudeSegmenterIsNoLongerCalledDirectly` cannot reach 0. At each of the three, replace:

```kotlin
        speechSegmenter.reset()
```

with:

```kotlin
        endpointer.reset()
```

(matching each site's existing indentation — 8 spaces at :1819 and :2393, 20 inside `onOpen`'s
coroutine at :2224). **Rename only.** The comment extension at `switchSource`, the per-session
cadence handover in `onOpen` and the `onSessionEnd()` teardown in `stopRecording` are Task D10's, and
none of them is landed here.

- [ ] **Step 4: Run tests green.**
```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon; .\gradlew.bat :app:assembleDebug --no-daemon
```
Then the aggregation command from Global Constraints. Expected: `failures=0 errors=0` and the
**+7 delta** for this task (`CapSeamPinTest`), and `BUILD SUCCESSFUL` for `assembleDebug` (the seam
now references four new units and the native probe externs — this is the first task where the whole
graph must link).

- [ ] **Step 5: Commit.**
```powershell
git add app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt app/src/test/java/com/whispereverywhere/service/CapSeamPinTest.kt; git commit -m @'
feat(vad): the endpointer is the verdict; the wall caps stay the else-if

One identifier changes inside the `if` and the cap branch gains a retain
window; the else-if, its comment block and its unconditional commit are
untouched. sendAudio stays unconditional and first. CapSeamPinTest pins the
structure on the source itself, so a future refactor that nests the cap inside
the verdict or gates sendAudio fails loudly instead of shipping.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT
'@
```

---

### Task D10: The five reset sites + the per-session cadence and probe lifecycle

**Files:**
- Modify `app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt`
  (:1819 switchSource, :2224 + :2238 onOpen, :2393 stopRecording)
- Create `app/src/test/java/com/whispereverywhere/service/EndpointerLifecyclePinTest.kt`

**Interfaces:**
- Consumes: `Endpointer.reset()`, `Endpointer.onSessionStart(nowMs, minCommitIntervalMs)`, `Endpointer.onSessionEnd()` (Task D2); `CommitCadencePolicy.minCommitIntervalMs(tierId, isCloudBatch)` (Task D3); the existing local `val installedModel = app.whisperModelManager.installedModel()` at :2211, which is in scope inside the `object : TranscriptionEngine.Listener` at :2219.
- Produces: nothing new — this task closes the lifecycle.

The five reset sites, all reached through `endpointer.reset()` so `vadProbeReset()` follows:
`:1722` cap cut (Task D9) · `:1819` switchSource · `:2224` onOpen · `:2393` stopRecording ·
the endpointer's own post-commit reset (Workstream C, required by `Endpointer.onFrame`'s contract).
`:1819` is the one that is a **correctness** bug if missed: `switchSource` swaps mic ↔ device
audio mid-session, and carrying LSTM recurrence across an acoustic-source change is wrong.

- [ ] **Step 1: Write the failing test.** Create `app/src/test/java/com/whispereverywhere/service/EndpointerLifecyclePinTest.kt`:
```kotlin
package com.whispereverywhere.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * All five reset sites, the per-session cadence handover and the probe teardown, pinned on the
 * source (see CapSeamPinTest for why the pin is structural). Site 5 — the endpointer's own
 * post-commit reset — is a contract on Endpointer.onFrame and is pinned in Workstream C.
 */
class EndpointerLifecyclePinTest {

    private fun source(relative: String): File {
        var dir: File? = File(System.getProperty("user.dir")!!).absoluteFile
        while (dir != null) {
            for (candidate in listOf(File(dir, relative), File(dir, "app/$relative"))) {
                if (candidate.isFile) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError("cannot locate $relative from ${System.getProperty("user.dir")}")
    }

    private val text: String by lazy {
        source("src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt").readText()
    }

    private fun count(needle: String) = text.split(needle).size - 1

    private fun indexOfOrFail(needle: String): Int {
        val i = text.indexOf(needle)
        assertTrue("missing from FloatingBubbleService.kt: <<$needle>>", i >= 0)
        return i
    }

    @Test
    fun thereAreExactlyFourServiceSideResetSites() {
        // cap cut, switchSource, onOpen, stopRecording. The fifth lives inside the endpointer.
        assertEquals(4, count("endpointer.reset()"))
    }

    @Test
    fun switchSourceResetsBeforeSwappingTheAcousticSource() {
        val commit = indexOfOrFail("        transcriptionEngine?.commit()\n        endpointer.reset()")
        val stopOld = text.indexOf("            com.whispereverywhere.audio.ActiveSource.MIC -> audioRecorder.stop()", commit)
        assertTrue("the reset must precede the source swap", stopOld > commit)
    }

    @Test
    fun onOpenResetsAndHandsOverThisSessionsCadence() {
        val reset = indexOfOrFail("                    endpointer.reset()")
        val cadence = indexOfOrFail("                    endpointer.onSessionStart(")
        val anchor = indexOfOrFail("                        nowMs = sessionOpenMs,")
        val tier = indexOfOrFail("                            tierId = installedModel?.id,")
        val cloud = indexOfOrFail("                            isCloudBatch = cloudWrapper != null,")
        val startInput = text.indexOf("                    val started = startAudioInput()", cadence)
        assertTrue(reset < cadence)
        assertTrue(cadence < anchor && anchor < tier && tier < cloud)
        assertTrue("the endpointer must be armed BEFORE the first frame can arrive", cloud < startInput)
        assertEquals(1, count("endpointer.onSessionStart("))
        assertEquals(1, count("CommitCadencePolicy.minCommitIntervalMs("))
    }

    @Test
    fun theCloudFirstCapSuppressionIsUntouchedAndStillPrecedesTheCadence() {
        val suppression = indexOfOrFail("                    if (cloudWrapper != null) segmentCapPolicy.onCommit(sessionOpenMs)")
        val cadence = indexOfOrFail("                    endpointer.onSessionStart(")
        assertTrue("the 4 s cloud suppression must stay where it is", suppression < cadence)
        assertEquals(1, count("if (cloudWrapper != null) segmentCapPolicy.onCommit(sessionOpenMs)"))
    }

    @Test
    fun stopRecordingFlushesUnconditionallyThenResetsThenFreesTheProbe() {
        val flush = indexOfOrFail("        transcriptionEngine?.commit()\n        android.util.Log.i(")
        val reset = text.indexOf("        endpointer.reset()", flush)
        val end = text.indexOf("        endpointer.onSessionEnd()", reset)
        assertTrue("the flush stays unconditional and first", reset > flush)
        assertTrue("the probe is freed after the reset", end > reset)
        assertEquals(1, count("endpointer.onSessionEnd()"))
    }

    @Test
    fun theProbeIsFreedOnlyAfterBothCaptureSourcesHaveJoined() {
        // SCOPED TO stopRecording, deliberately. Both capture-stop anchors also occur, at the same
        // 8-space indentation, inside onDestroy far above (FloatingBubbleService.kt:764-765). An
        // unscoped indexOf() would therefore compare onDestroy's offsets against stopRecording's
        // teardown and pass unconditionally — pinning nothing, while the hazard it claims to pin
        // (vadProbeFree running with a frame still inside vadProbeFrame) stayed wide open.
        val stopFn = indexOfOrFail("    private fun stopRecording() {")
        val recorderStop = text.indexOf("        audioRecorder.stop()", stopFn)
        val playbackStop = text.indexOf("        stopPlaybackCapturer()", stopFn)
        val end = text.indexOf("        endpointer.onSessionEnd()", stopFn)
        assertTrue("stopRecording must stop the mic recorder", recorderStop >= 0)
        assertTrue("stopRecording must stop the playback capturer", playbackStop >= 0)
        assertTrue("stopRecording must end the endpointer session", end >= 0)
        assertTrue(recorderStop < end)
        assertTrue(playbackStop < end)
    }

    @Test
    fun theOldSegmenterFieldIsGoneEverywhere() {
        assertEquals(0, count("speechSegmenter"))
        assertEquals(0, count("import com.whispereverywhere.util.SpeechSegmenter"))
    }
}
```

- [ ] **Step 2: Run it, expect the lifecycle assertions to fire.**
```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests "com.whispereverywhere.service.EndpointerLifecyclePinTest" --no-daemon
```
Expected: **4 of the 7 fail, and three are already green.** Task D9 renamed all four service-side
reset sites, so the three tests that only assert the rename pass immediately — record them as
already-green guards (the same shape as the guards in Tasks H5 and G1), not as a miss:

- already green: `thereAreExactlyFourServiceSideResetSites`,
  `switchSourceResetsBeforeSwappingTheAcousticSource`, `theOldSegmenterFieldIsGoneEverywhere`.
- still red, all four for the same missing pair (`endpointer.onSessionStart(` /
  `endpointer.onSessionEnd()`, which this task lands):
  - `onOpenResetsAndHandsOverThisSessionsCadence FAILED — java.lang.AssertionError: missing from FloatingBubbleService.kt: <<                    endpointer.onSessionStart(>>`
  - `theCloudFirstCapSuppressionIsUntouchedAndStillPrecedesTheCadence FAILED — java.lang.AssertionError: missing from FloatingBubbleService.kt: <<                    endpointer.onSessionStart(>>`
  - `stopRecordingFlushesUnconditionallyThenResetsThenFreesTheProbe FAILED — java.lang.AssertionError: the probe is freed after the reset` (the `onSessionEnd()` search returns -1)
  - `theProbeIsFreedOnlyAfterBothCaptureSourcesHaveJoined FAILED — java.lang.AssertionError: stopRecording must end the endpointer session`

- [ ] **Step 3: Minimal implementation.** Three edits to `FloatingBubbleService.kt`. **None of them
renames anything** — Task D9 already swapped all four `speechSegmenter.reset()` calls to
`endpointer.reset()`; what is missing is the lifecycle around them.

(a) `switchSource`, line 1819 — the reset already reads `endpointer.reset()`:
```kotlin
        transcriptionEngine?.commit()
        endpointer.reset()
```
This task only extends the comment above it (:1815–1817) with one sentence:
```kotlin
        // ...the same mechanism stopRecording's tail commit uses. The endpointer reset is a
        // CORRECTNESS requirement, not hygiene: this line swaps mic <-> device audio, and the
        // streaming VAD's LSTM recurrence must never carry across an acoustic-source change.
```

(b) `onOpen`, line 2224 — the reset already reads `endpointer.reset()` (Task D9). Insert the cadence
handover immediately after the cloud-suppression line at :2238 and before
`val started = startAudioInput()` at :2239:
```kotlin
                    if (cloudWrapper != null) segmentCapPolicy.onCommit(sessionOpenMs)
                    // 3.7 (Workstream D3): the endpointer's paced-commit floor is the MEASURED
                    // cost governor, and it is per-session because it depends on BOTH the
                    // installed tier and whether every commit becomes a provider request.
                    // cloudWrapper is already resolved here — see the note above — and
                    // installedModel was resolved just before connect(). Armed BEFORE
                    // startAudioInput() so the first captured frame already sees this session's
                    // cadence; the native probe itself initialises lazily on that first frame,
                    // i.e. on the capture thread, never here on Main.
                    endpointer.onSessionStart(
                        nowMs = sessionOpenMs,
                        minCommitIntervalMs = CommitCadencePolicy.minCommitIntervalMs(
                            tierId = installedModel?.id,
                            isCloudBatch = cloudWrapper != null,
                        ),
                    )
                    val started = startAudioInput()
```

(c) `stopRecording`, line 2393 — the reset already reads `endpointer.reset()` (Task D9). Add the
teardown immediately after it:
```kotlin
        endpointer.reset()
        // 3.7 (Workstream D5/D8): the probe's native context is freed HERE, and only here.
        // audioRecorder.stop() and stopPlaybackCapturer() above have already JOINED their capture
        // threads, so no frame can be inside vadProbeFrame while vadProbeFree runs — and the
        // unconditional flush above still belongs to this session, so freeing any earlier would
        // race the audio it is flushing.
        endpointer.onSessionEnd()
```

- [ ] **Step 4: Run tests green.**
```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon; .\gradlew.bat :app:assembleDebug --no-daemon
```
Then the aggregation command from Global Constraints. Expected: `failures=0 errors=0`, the **+7
delta** for this task (`EndpointerLifecyclePinTest`), zero pre-existing tests modified since Task D1,
and `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit.**
```powershell
git add app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt app/src/test/java/com/whispereverywhere/service/EndpointerLifecyclePinTest.kt; git commit -m @'
feat(vad): all five reset sites, per-session cadence, probe teardown

switchSource, onOpen, stopRecording and the cap cut all reset through the
endpointer, so vadProbeReset() follows each — switchSource is the correctness
one, since LSTM recurrence must not carry across a mic<->device-audio swap.
onOpen hands over this session's measured cadence floor (tier + cloud) before
startAudioInput, so the first frame already sees it. stopRecording frees the
probe only after both capture threads have joined and after the unconditional
flush. The cloud 4s suppression is untouched and still precedes the handover.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT
'@
```

---

## Workstream F (service) — The commit funnel and the endpoint diagnostics (F7–F9)

---

### Task F7: The commit funnel — capture the five discarded `commit()` seqs + `queue: depth=`

**Files:**
- Create `app/src/main/java/com/whispereverywhere/service/EndpointDiag.kt`
- Create `app/src/main/java/com/whispereverywhere/service/SegmentQueueDepth.kt`
- Create `app/src/test/java/com/whispereverywhere/service/SegmentQueueDepthTest.kt`
- Modify `app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt` at the five
  sites that today discard `commit()`'s return: `:915` (projection-consent flush), `:1694`
  (endpoint cut), `:1721` (wall-cap cut), `:1818` (`switchSource`), `:2388` (stop flush); plus
  `onSegmentResolved` at `:2298-2306`, a field beside `segmentCapPolicy` at `:307`, and the
  per-session reset at `:2152` (beside `segmentOrderer = …SegmentOrderer()`).
- Modify `app/src/test/java/com/whispereverywhere/service/CapSeamPinTest.kt` (Task D9) and
  `app/src/test/java/com/whispereverywhere/service/EndpointerLifecyclePinTest.kt` (Task D10) — three
  quoted source literals move with the call sites. **The assertions are updated, never weakened:**
  each keeps asserting the same structural fact about the same branch.

  *Anchors after Workstream D:* `:1694` is inside the `if (endpointer.onFrame(chunk, amp, now))`
  branch D9 landed, and `:1721` is the `engine.commitRetainingTailMs(retainMs)` inside the
  `else if (segmentCapPolicy.capExceeded(now))` block. Anchor on those two statements, not on the
  line numbers. **Neither `if` is restructured by this task** — only the commit expression inside
  each branch changes.

**Interfaces:**
- Consumes: `TranscriptionEngine.commit(): Long` (already returns seq; `-1` = nothing cut) and
  `TranscriptionEngine.commitRetainingTailMs(retainMs: Long): Long` (Task D6).
- Produces:
  - `class SegmentQueueDepth` in `com.whispereverywhere.service` with
    `fun onCommitted(seq: Long): Int`, `fun onResolved(seq: Long): Int`, `fun depth(): Int`, `fun reset()`
  - `object EndpointDiag` in `com.whispereverywhere.service` with `fun queueLine(depth: Int): String`
    → `"queue: depth=<n>"`, **and the four cut-kind constants `VAD` / `CAP` / `STOP` / `SWITCH`**.
    They are born here, not in Task F8, because this task's five rewritten call sites already
    reference them — `assembleDebug` could not succeed at this boundary otherwise. Task F8 adds only
    the two formatters that need `EndpointCut`.
  - **`private fun commitSegment(engine: TranscriptionEngine, cut: String, retainMs: Long = 0L, nowMs: Long = System.currentTimeMillis()): Long`
    — THE single commit funnel, a private MEMBER of `FloatingBubbleService`** (never a top-level
    extension: it reads the service's private `segmentQueueDepth`, `endpointer` and `serviceScope`).
    All five commit sites route through it exactly once. It calls `commit()` (or
    `commitRetainingTailMs` when the cap offered a retain window), returns that seq unchanged, and
    owns the diagnostics that hang off a commit. `nowMs` is the FRAME's clock where the caller has
    one — the two capture-thread sites pass their `now`, so Task F9's speech-end stamp is measured
    against the same instant `trailMs` was; the three Main-side sites take the default. Tasks F8 and
    F9 each extend this ONE function — `endpoint:`/`perceived:` — and Task G3 hangs the in-flight
    strip repaint off it. There is never a second funnel.

- [ ] **Step 1: Write the failing test** — create
`app/src/test/java/com/whispereverywhere/service/SegmentQueueDepthTest.kt`:

```kotlin
package com.whispereverywhere.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The committed-but-unresolved backlog (3.7 Workstream F). This is the only surface that makes a
 * growing multi-tier backlog visible WHILE it happens rather than at stop.
 *
 * Concurrency is not incidental: commits arrive from the capture thread AND from Main
 * (switchSource, projection consent, stopRecording) while resolutions arrive on Main. The
 * concurrent test therefore uses REAL executors, never a same-thread stub.
 */
class SegmentQueueDepthTest {

    @Test
    fun depthRisesPerCommitAndFallsPerResolution() {
        val q = SegmentQueueDepth()
        assertEquals(1, q.onCommitted(0L))
        assertEquals(2, q.onCommitted(1L))
        assertEquals(3, q.onCommitted(2L))
        assertEquals(2, q.onResolved(0L))
        assertEquals(1, q.onResolved(1L))
        assertEquals(0, q.onResolved(2L))
        assertEquals(0, q.depth())
    }

    @Test
    fun aCommitThatCutNothingDoesNotCount() {
        // TranscriptionEngine.commit() returns -1 when there was nothing to cut, and that seq will
        // never reach onSegmentResolved — counting it would strand the depth above zero forever.
        val q = SegmentQueueDepth()
        assertEquals(0, q.onCommitted(-1L))
        assertEquals(0, q.depth())
    }

    @Test
    fun anUnknownOrDuplicateResolutionNeverDrivesDepthNegative() {
        val q = SegmentQueueDepth()
        q.onCommitted(0L)
        assertEquals(0, q.onResolved(0L))
        assertEquals(0, q.onResolved(0L))   // duplicate
        assertEquals(0, q.onResolved(99L))  // never committed
        assertEquals(0, q.depth())
    }

    @Test
    fun aRepeatedCommitOfTheSameSeqCountsOnce() {
        val q = SegmentQueueDepth()
        assertEquals(1, q.onCommitted(5L))
        assertEquals(1, q.onCommitted(5L))
    }

    @Test
    fun resetClearsTheBacklogForTheNextSession() {
        val q = SegmentQueueDepth()
        q.onCommitted(0L); q.onCommitted(1L)
        q.reset()
        assertEquals(0, q.depth())
        assertEquals(1, q.onCommitted(0L))   // seq numbering restarts per session
    }

    @Test
    fun commitsAndResolutionsFromRealBackgroundThreadsSettleAtZero() {
        val commits = Executors.newSingleThreadExecutor()
        val resolutions = Executors.newSingleThreadExecutor()
        try {
            val q = SegmentQueueDepth()
            val n = 500
            val committed = CountDownLatch(n)
            val resolved = CountDownLatch(n)

            for (seq in 0 until n) {
                commits.execute {
                    q.onCommitted(seq.toLong())
                    committed.countDown()
                    // Enqueued from INSIDE the commit task, so a seq is never resolved before it
                    // was committed — that is the real production ordering (a seq only exists
                    // once commit() returned it). The two executors still overlap freely: commit
                    // N+1 runs on one thread while resolve N runs on the other, which is the
                    // interleaving the synchronization has to survive.
                    resolutions.execute { q.onResolved(seq.toLong()); resolved.countDown() }
                }
            }
            assertTrue(committed.await(10, TimeUnit.SECONDS))
            assertTrue(resolved.await(10, TimeUnit.SECONDS))

            // Every committed seq was resolved exactly once, so the backlog must be empty — and
            // must never have gone negative on the way.
            assertEquals(0, q.depth())
        } finally {
            commits.shutdownNow()
            resolutions.shutdownNow()
        }
    }

    @Test
    fun queueLineMatchesTheGreppableFormatExactly() {
        assertEquals("queue: depth=0", EndpointDiag.queueLine(0))
        assertEquals("queue: depth=7", EndpointDiag.queueLine(7))
    }
}
```

- [ ] **Step 2: Run it, expected failure** —

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests "com.whispereverywhere.service.SegmentQueueDepthTest" --no-daemon
```

Expected: `e: ...SegmentQueueDepthTest.kt: Unresolved reference: SegmentQueueDepth` and
`e: ...SegmentQueueDepthTest.kt: Unresolved reference: EndpointDiag`.

- [ ] **Step 3: Minimal implementation** —

(a) Create `app/src/main/java/com/whispereverywhere/service/SegmentQueueDepth.kt`:

```kotlin
package com.whispereverywhere.service

/**
 * The committed-but-unresolved segment backlog (3.7 Workstream F).
 *
 * Under 3.7's utterance cadence the backlog is the difference between "the model is slower than
 * you speak" and "the model is fine and the last utterance is just in flight" — and before this
 * counter that difference was only visible at stop, in `finalize-timing: local-drain`. This makes
 * it visible while it grows.
 *
 * Tracks the SET of in-flight seqs rather than a bare int, so a duplicate resolution, an unknown
 * seq, or a re-committed seq can never drive the depth negative or strand it above zero — the
 * failure mode a counter would have, and the one that would make the diagnostic lie exactly when
 * it matters.
 *
 * THREADING: commits arrive from the capture thread AND from Main (switchSource, projection
 * consent, stopRecording); resolutions arrive on Main. Every method is synchronized on the
 * instance — the whole class is a few set operations per segment, ~16 times a minute.
 */
class SegmentQueueDepth {

    private val inFlight = HashSet<Long>()

    /**
     * Records a commit and returns the new depth. A [seq] below zero is [TranscriptionEngine]'s
     * "nothing was cut" answer: it will never resolve, so counting it would strand the depth.
     */
    @Synchronized
    fun onCommitted(seq: Long): Int {
        if (seq >= 0L) inFlight.add(seq)
        return inFlight.size
    }

    /** Records a resolution and returns the new depth. Unknown/duplicate seqs are no-ops. */
    @Synchronized
    fun onResolved(seq: Long): Int {
        inFlight.remove(seq)
        return inFlight.size
    }

    @Synchronized
    fun depth(): Int = inFlight.size

    /** Per-session reset: seq numbering restarts at 0 on every engine connect(). */
    @Synchronized
    fun reset() {
        inFlight.clear()
    }
}
```

(b) Create `app/src/main/java/com/whispereverywhere/service/EndpointDiag.kt`:

```kotlin
package com.whispereverywhere.service

/**
 * The 3.7 endpoint diagnostic family (Workstream F). One greppable set of lines, all under the
 * WE-DIAG tag and all joinable on `seq=` with `segment-timing:`, so a single logcat capture
 * answers "why was this segment cut, how long did the user wait, and was the queue growing".
 *
 *     endpoint:       seq=N cut=vad|cap|stop|switch speechMs=… trailMs=… p=…
 *     segment-timing: seq=N audio=… transcribe=… rtf=… vadIn=… vadOut=… ctxFrames=…
 *     queue:          depth=N
 *     perceived:      seq=N speechEndToVisible=…ms
 *     probe:          frames=N p50=…µs p99=…µs overruns=N
 *
 * Pure so every format is JVM-pinned: the owner's acceptance sheet greps these exact strings, and
 * a silent format drift would break every report that parses them. Content discipline is the same
 * as SegmentTiming's — numbers and fixed vocabulary only, NEVER transcript text.
 */
object EndpointDiag {

    /** The endpointer found a real pause: the cadence 3.7 exists to produce. */
    const val VAD = "vad"

    /** The wall-clock backstop fired. Under 3.7 this is a VAD-FAILURE signature, not the norm. */
    const val CAP = "cap"

    /** The unconditional stop flush. */
    const val STOP = "stop"

    /** A mic <-> device-audio source swap cut the segment at the boundary. */
    const val SWITCH = "switch"

    /** The committed-but-unresolved backlog, from [SegmentQueueDepth]. */
    fun queueLine(depth: Int): String = "queue: depth=$depth"
}
```

The four constants land HERE rather than in Task F8 because edits (d)–(h) below reference them: the
main source set would not compile at this task's boundary otherwise, and Step 4's `assembleDebug`
gate is exactly what would catch that. They are pure string constants with no dependency on
`EndpointCut`, so nothing about F8 is pulled forward with them.

(c) `FloatingBubbleService.kt:307` — add the field beside `segmentCapPolicy`. After:

```kotlin
    private val segmentCapPolicy = SegmentCapPolicy()
```

insert:

```kotlin
    /**
     * 3.7 Workstream F: the committed-but-unresolved backlog. Fed by every commit site and by
     * onSegmentResolved; the only surface that shows a growing multi-tier backlog while it grows.
     */
    private val segmentQueueDepth = SegmentQueueDepth()
```

(d) `FloatingBubbleService.kt:915` — the projection-consent flush, a source handover. Replace:

```kotlin
                    transcriptionEngine?.commit()
```

with:

```kotlin
                    transcriptionEngine?.let { commitSegment(it, EndpointDiag.SWITCH) }
```

(e) `FloatingBubbleService.kt:1691-1694` — the endpoint cut, inside the `if` Task D9 landed.
Replace:

```kotlin
            if (endpointer.onFrame(chunk, amp, now)) {
                android.util.Log.i("WE-DIAG", "VAD -> commit (rms=$amp)")
                segmentCapPolicy.onCommit(now)
                engine.commit()
```

with:

```kotlin
            if (endpointer.onFrame(chunk, amp, now)) {
                android.util.Log.i("WE-DIAG", "VAD -> commit (rms=$amp)")
                segmentCapPolicy.onCommit(now)
                commitSegment(engine, EndpointDiag.VAD, nowMs = now)
```

`nowMs = now` is the frame's clock — the same `now` `onFrame` was given, and the one the endpointer's
`trailMs` was measured against. Task F9 derives the speech-end instant from that pair, so taking a
fresh `System.currentTimeMillis()` inside the funnel instead would bias the headline metric low by
the whole `engine.commit()` buffer snapshot. Named argument because `retainMs` is skipped here.

(f) `FloatingBubbleService.kt:1721` — the wall-cap cut, inside the untouched `else if`. The retain
window Task D9 computes is passed straight through, so the funnel makes the same call the branch
made before it (`commitRetainingTailMs(0)` IS `commit()`, by first line and by test). Replace:

```kotlin
                engine.commitRetainingTailMs(retainMs)
                endpointer.reset()
```

with:

```kotlin
                commitSegment(engine, EndpointDiag.CAP, retainMs, now)
                endpointer.reset()
```

(g) `FloatingBubbleService.kt:1818` — `switchSource`. Replace:

```kotlin
        transcriptionEngine?.commit()
        endpointer.reset()
```

with:

```kotlin
        transcriptionEngine?.let { commitSegment(it, EndpointDiag.SWITCH) }
        endpointer.reset()
```

(h) `FloatingBubbleService.kt:2388` — the UNCONDITIONAL stop flush. The call stays unconditional
and in place; only its discarded return is captured. Replace:

```kotlin
        transcriptionEngine?.commit()
```

with:

```kotlin
        transcriptionEngine?.let { commitSegment(it, EndpointDiag.STOP) }
```

(i) `FloatingBubbleService.kt:2298-2306` — feed resolutions into the depth. Replace:

```kotlin
            override fun onSegmentResolved(seq: Long, outcome: SegmentOutcome) {
                android.util.Log.i("WE-DIAG", "onSegmentResolved: seq=$seq outcome=${outcome.javaClass.simpleName}")
                // Hop to Main FIRST: the orderer is main-thread confined, and the engine calls
                // this from its executor thread. The hop is the same one the old onCompleted did,
                // so delivery timing is unchanged.
                serviceScope.launch(Dispatchers.Main) {
                    deliverReleasedText(segmentOrderer.onResolved(seq, outcome).text)
                }
            }
```

with:

```kotlin
            override fun onSegmentResolved(seq: Long, outcome: SegmentOutcome) {
                android.util.Log.i("WE-DIAG", "onSegmentResolved: seq=$seq outcome=${outcome.javaClass.simpleName}")
                // Hop to Main FIRST: the orderer is main-thread confined, and the engine calls
                // this from its executor thread. The hop is the same one the old onCompleted did,
                // so delivery timing is unchanged.
                serviceScope.launch(Dispatchers.Main) {
                    deliverReleasedText(segmentOrderer.onResolved(seq, outcome).text)
                    android.util.Log.i(
                        "WE-DIAG",
                        EndpointDiag.queueLine(segmentQueueDepth.onResolved(seq)),
                    )
                }
            }
```

(j) `FloatingBubbleService.kt` — add THE FUNNEL immediately before `deliverReleasedText` (`:2576`,
the KDoc block starts there):

```kotlin
    /**
     * THE ONE COMMIT FUNNEL (3.7 Workstream F). Every commit site in this service goes through
     * here, so the seq [TranscriptionEngine.commit] has always returned is captured exactly once
     * and in one place — which is what lets `queue:` (and, from Tasks F8/F9, `endpoint:` and
     * `perceived:`) join to `segment-timing:` on one key. No plumbing was required; the number was
     * always there and every call site threw it away.
     *
     * It adds NOTHING to the commit DECISION: the wall caps, the cloud 4 s suppression and the
     * unconditional stop flush all decide whether to call it exactly as before, and
     * `commitRetainingTailMs(0)` is `commit()` by first line and by test — so with an endpointer
     * that never fires, this is byte-identical to 3.6.0.
     *
     * [cut] is one of [EndpointDiag]'s four cut kinds and names WHY this commit happened.
     * [retainMs] is non-zero only at the wall-cap site, where the endpointer offered a micro-pause
     * to cut at. [nowMs] is the FRAME's clock at the two capture-thread sites and defaults to the
     * wall clock at the three Main-side ones; it exists so Task F9's speech-end stamp is measured
     * against the same instant the endpointer's `trailMs` was, not against a clock re-read after a
     * ~960 KB buffer snapshot. Returns exactly what the engine returned — the seq, or the -1
     * "nothing was cut" answer documented on [TranscriptionEngine.commit], which contributes
     * nothing to the backlog because it will never resolve.
     *
     * Callable from the CAPTURE thread (the endpoint and cap cuts) and from Main (switchSource,
     * stopRecording, the projection-consent flush) — [SegmentQueueDepth] is synchronized.
     */
    private fun commitSegment(
        engine: TranscriptionEngine,
        cut: String,
        retainMs: Long = 0L,
        nowMs: Long = System.currentTimeMillis(),
    ): Long {
        val seq = if (retainMs > 0L) engine.commitRetainingTailMs(retainMs) else engine.commit()
        android.util.Log.i("WE-DIAG", EndpointDiag.queueLine(segmentQueueDepth.onCommitted(seq)))
        return seq
    }
```

(k) `FloatingBubbleService.kt:2152` — reset the backlog where the session's seq numbering restarts.
**Session START, not stop.** The engine restarts seq numbering at 0 in `connect()`, and the orderer
is recreated on the same line for exactly that reason; resetting at stop instead would zero the depth
BEFORE the drain, so `queue: depth=` would read 0 for every segment still in flight at stop — the
part of the session the counter exists to show. After:

```kotlin
        segmentOrderer = com.whispereverywhere.transcription.SegmentOrderer()
```

insert:

```kotlin
        // 3.7 Workstream F: same reason the orderer is recreated. A depth carried over from a
        // torn-down session would render a phantom backlog on the next one's first commit, and a
        // reset at stop would blank the diagnostic for the whole drain.
        segmentQueueDepth.reset()
```

(l) The two source-shape pin tests from Workstream D quote commit expressions that just moved.
Update the three literals — same assertions, same branches, new expression. In
`CapSeamPinTest.theCapBranchKeepsItsBookkeepingAndItsUnconditionalCommit`:

```kotlin
        val commit = indexOfOrFail("                commitSegment(engine, EndpointDiag.CAP, retainMs, now)")
```

and in `EndpointerLifecyclePinTest`, `switchSourceResetsBeforeSwappingTheAcousticSource`:

```kotlin
        val commit = indexOfOrFail(
            "        transcriptionEngine?.let { commitSegment(it, EndpointDiag.SWITCH) }\n        endpointer.reset()"
        )
```

and `stopRecordingFlushesUnconditionallyThenResetsThenFreesTheProbe`:

```kotlin
        val flush = indexOfOrFail(
            "        transcriptionEngine?.let { commitSegment(it, EndpointDiag.STOP) }\n        android.util.Log.i("
        )
```

Nothing else in either pin test changes: `sendAudio` is still unconditional and first, the cap check
is still the `else if`, the bookkeeping still precedes the commit, and the reset still follows it.

- [ ] **Step 4: Run tests green** —

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests "com.whispereverywhere.service.*" --no-daemon; if ($?) { .\gradlew.bat :app:assembleDebug --no-daemon }
```

Evidence: `TEST-com.whispereverywhere.service.SegmentQueueDepthTest.xml` shows
`tests="7" failures="0" errors="0"`, `CapSeamPinTest` and `EndpointerLifecyclePinTest` are green with
their three updated literals, and `assembleDebug` reports `BUILD SUCCESSFUL` — the gate for the
`FloatingBubbleService` edits.

- [ ] **Step 5: Commit** —

```powershell
git add app/src/main/java/com/whispereverywhere/service/EndpointDiag.kt app/src/main/java/com/whispereverywhere/service/SegmentQueueDepth.kt app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt app/src/test/java/com/whispereverywhere/service/SegmentQueueDepthTest.kt app/src/test/java/com/whispereverywhere/service/CapSeamPinTest.kt app/src/test/java/com/whispereverywhere/service/EndpointerLifecyclePinTest.kt; git commit -m @'
feat(diag): one commit funnel — capture the five discarded seqs and emit queue: depth

commit() has always returned the seq and all five service call sites threw it away, so no log
line could be joined to another. commitSegment() is now the single funnel they all route through:
it makes the same call the branch made before it, returns the seq unchanged, and owns the
diagnostics that hang off a commit. Capturing the seq needs no plumbing and gives the F family
one key. SegmentQueueDepth tracks the in-flight SET, not a counter, so a duplicate or unknown
resolution cannot strand or invert the depth. The stop flush stays unconditional and in place;
the wall-cap check stays in its else-if untouched, and the two D pin tests are updated to quote
the new expression while asserting the same structure.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT
'@
```

---

### Task F8: `endpoint:` lines at all five cut sites + the wall-cap reword

**Files:**
- Modify `app/src/main/java/com/whispereverywhere/service/EndpointDiag.kt`
- Create `app/src/test/java/com/whispereverywhere/service/EndpointDiagTest.kt`
- Modify `app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt` — the cap log
  line at `:1699-1702` and `commitSegment` (the funnel, added in Task F7, immediately before
  `:2576`). **The five call sites already pass their cut kind** (Task F7), so they are untouched here.

**Interfaces:**
- Consumes: `com.whispereverywhere.audio.EndpointCut(speechMs, trailMs, prob)` and
  `SileroEndpointer.lastCut(): EndpointCut?` (Task C8) — the three numbers exist only inside the
  state machine, and by the time the service sees a verdict it has already re-armed, so the cut is
  RECORDED as it happens and read straight after. There is exactly ONE site that has any of them:
  the `cut=vad` commit, where the funnel reads `(endpointer as? SileroEndpointer)?.lastCut()`. The
  amplitude endpointer and the other three cut kinds pass `null` and render the unknown shape. No
  member is added to the `Endpointer` interface for this: an accessor every implementor would have
  to fake is a worse contract than one `as?` at one site.
- Produces:
  - `EndpointDiag.endpointLine(seq: Long, cut: String, ec: EndpointCut?): String`
  - `EndpointDiag.capCommitLine(capMs: Long): String`
  - the `import com.whispereverywhere.audio.EndpointCut` those two formatters need

  **The four cut-kind constants `VAD` / `CAP` / `STOP` / `SWITCH` are NOT produced here** — Task F7
  landed them, because F7's five rewritten call sites already reference them. They survive this
  task's whole-file replacement unchanged.

**Binding constraint on the reword:** `cap=<n>ms` must survive VERBATIM. `cap=4000ms` appearing in
a cloud session is the documented 3.6.0 regression signature the owner's acceptance greps for
(`h2-owner-acceptance.md:46-49`), and `wall-clock cap -> commit` is the existing grep. The reword
therefore APPENDS the VAD-failure marker to a byte-identical prefix.

- [ ] **Step 1: Write the failing test** — create
`app/src/test/java/com/whispereverywhere/service/EndpointDiagTest.kt`:

```kotlin
package com.whispereverywhere.service

import com.whispereverywhere.audio.EndpointCut
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The exact bytes of the 3.7 endpoint diagnostic family (Workstream F). The owner's acceptance
 * sheet greps these strings; a silent drift breaks every report that parses them.
 */
class EndpointDiagTest {

    @Test
    fun endpointLineMatchesTheGreppableFormatExactly() {
        assertEquals(
            "endpoint: seq=4 cut=vad speechMs=2400 trailMs=500 p=0.42",
            EndpointDiag.endpointLine(
                seq = 4L,
                cut = EndpointDiag.VAD,
                ec = EndpointCut(speechMs = 2_400L, trailMs = 500L, prob = 0.42f),
            ),
        )
    }

    @Test
    fun theCutVocabularyIsExactlyTheFourSpeccedValues() {
        assertEquals("vad", EndpointDiag.VAD)
        assertEquals("cap", EndpointDiag.CAP)
        assertEquals("stop", EndpointDiag.STOP)
        assertEquals("switch", EndpointDiag.SWITCH)
    }

    @Test
    fun aCutWithNoEndpointerStateBehindItReportsMinusOneProb_neverAFabricatedZero() {
        // p=0.00 would read as "the probe was certain there was no speech". The sentinel matches
        // the native frame contract: -1 is "no verdict", and it is never "silence". A null ec is
        // how cap/stop/switch cuts and the whole amplitude path arrive here.
        assertEquals(
            "endpoint: seq=0 cut=stop speechMs=0 trailMs=0 p=-1.00",
            EndpointDiag.endpointLine(seq = 0L, cut = EndpointDiag.STOP, ec = null),
        )
    }

    @Test
    fun probIsFormattedLocaleIndependently() {
        val prior = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY) // comma decimal separator
            assertEquals(
                "endpoint: seq=1 cut=vad speechMs=300 trailMs=520 p=0.51",
                EndpointDiag.endpointLine(
                    seq = 1L,
                    cut = EndpointDiag.VAD,
                    ec = EndpointCut(speechMs = 300L, trailMs = 520L, prob = 0.51f),
                ),
            )
        } finally {
            java.util.Locale.setDefault(prior)
        }
    }

    @Test
    fun theCapLineKeepsItsPre37PrefixAndItsCapField_verbatim() {
        val line = EndpointDiag.capCommitLine(4_000L)
        // `wall-clock cap -> commit` is the existing grep; `cap=4000ms` in a CLOUD session is the
        // documented 3.6.0 regression signature. Both must survive the reword byte for byte.
        assertTrue(line.startsWith("wall-clock cap -> commit (cap=4000ms)"))
        assertTrue(line.contains("cap=4000ms"))
    }

    @Test
    fun theCapLineNamesItselfAsAVadFailureSignature() {
        assertEquals(
            "wall-clock cap -> commit (cap=15000ms) VAD-MISS: no endpoint in this window",
            EndpointDiag.capCommitLine(15_000L),
        )
    }
}
```

- [ ] **Step 2: Run it, expected failure** —

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests "com.whispereverywhere.service.EndpointDiagTest" --no-daemon
```

Expected: `e: ...EndpointDiagTest.kt: Unresolved reference: endpointLine` and
`e: ...EndpointDiagTest.kt: Unresolved reference: capCommitLine`.

**Not** `Unresolved reference: VAD` — Task F7 landed the four cut-kind constants, so
`theCutVocabularyIsExactlyTheFourSpeccedValues` is an ALREADY-GREEN guard over work that exists (the
same shape as the already-green guards in Tasks H5 and G1). It is kept because the vocabulary is
what the acceptance sheet greps, and nothing else pins it.

- [ ] **Step 3: Minimal implementation** — `EndpointDiag.kt`, replace the whole file. The four
constants are carried through byte-identically from Task F7; only the import, the two formatters and
the class KDoc are new:

```kotlin
package com.whispereverywhere.service

import com.whispereverywhere.audio.EndpointCut
import java.util.Locale

/**
 * The 3.7 endpoint diagnostic family (Workstream F). One greppable set of lines, all under the
 * WE-DIAG tag and all joinable on `seq=` with `segment-timing:`, so a single logcat capture
 * answers "why was this segment cut, how long did the user wait, and was the queue growing".
 *
 *     endpoint:       seq=N cut=vad|cap|stop|switch speechMs=… trailMs=… p=…
 *     segment-timing: seq=N audio=… transcribe=… rtf=… vadIn=… vadOut=… ctxFrames=…
 *     queue:          depth=N
 *     perceived:      seq=N speechEndToVisible=…ms
 *     probe:          frames=N p50=…µs p99=…µs overruns=N
 *
 * Pure so every format is JVM-pinned: the owner's acceptance sheet greps these exact strings, and
 * a silent format drift would break every report that parses them. Content discipline is the same
 * as SegmentTiming's — numbers and fixed vocabulary only, NEVER transcript text.
 */
object EndpointDiag {

    /** The endpointer found a real pause: the cadence 3.7 exists to produce. */
    const val VAD = "vad"

    /** The wall-clock backstop fired. Under 3.7 this is a VAD-FAILURE signature, not the norm. */
    const val CAP = "cap"

    /** The unconditional stop flush. */
    const val STOP = "stop"

    /** A mic <-> device-audio source swap cut the segment at the boundary. */
    const val SWITCH = "switch"

    /**
     * Why this seq was cut, and on what evidence. Locale.US: the point is always a point.
     *
     * [ec] is the endpointer's own record of the cut ([com.whispereverywhere.audio.SileroEndpointer.lastCut]),
     * and it is null for every cut that had no probe behind it — the cap, stop and switch sites, and
     * the whole amplitude fallback. A null renders `speechMs=0 trailMs=0 p=-1.00`: the UNKNOWN
     * shape, matching the native frame contract where -1 is "no verdict" and never "silence".
     * `p=0.00` is never emitted for an unknown cut, because it would read as "the probe was certain
     * there was no speech" — a different and much stronger claim.
     */
    fun endpointLine(seq: Long, cut: String, ec: EndpointCut?): String =
        "endpoint: seq=$seq cut=$cut speechMs=${ec?.speechMs ?: 0L} trailMs=${ec?.trailMs ?: 0L} p=" +
            String.format(Locale.US, "%.2f", ec?.prob ?: -1.0f)

    /** The committed-but-unresolved backlog, from [SegmentQueueDepth]. */
    fun queueLine(depth: Int): String = "queue: depth=$depth"

    /**
     * The wall-clock backstop line, REWORDED for 3.7 as the failure signature it becomes.
     *
     * Before 3.7 this was the normal path (the amplitude segmenter's dead band meant most cuts
     * were cap cuts). With a real endpointer it means the endpointer did not fire for a whole cap
     * window — worth investigating every time.
     *
     * Two substrings are load-bearing and are preserved BYTE FOR BYTE, which is why the marker is
     * appended rather than the line rewritten: `wall-clock cap -> commit` is the existing grep,
     * and `cap=<n>ms` is the documented regression signature — `cap=4000ms` appearing in a CLOUD
     * session means the LOCAL-only first-cap suppression at FloatingBubbleService.kt:2238 broke.
     */
    fun capCommitLine(capMs: Long): String =
        "wall-clock cap -> commit (cap=${capMs}ms) VAD-MISS: no endpoint in this window"
}
```

- [ ] **Step 4: Run tests green, then wire the service** —

First confirm the pure formats:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests "com.whispereverywhere.service.EndpointDiagTest" --no-daemon
```

Evidence: `TEST-com.whispereverywhere.service.EndpointDiagTest.xml` shows `tests="6" failures="0"`.

Then wire `FloatingBubbleService.kt`:

(a) Add the import beside the file's other `com.whispereverywhere.audio` imports (Task D9 added
`Endpointer` and `EndpointerFactory` there):

```kotlin
import com.whispereverywhere.audio.SileroEndpointer
```

(b) Extend the funnel `commitSegment` (added in Task F7) with the `endpoint:` line. This is the ONE
edit to the funnel in this task, and the ONLY site in the service that reads endpointer state.
Replace the body — the KDoc above it gains one paragraph, quoted after the code:

```kotlin
    private fun commitSegment(
        engine: TranscriptionEngine,
        cut: String,
        retainMs: Long = 0L,
        nowMs: Long = System.currentTimeMillis(),
    ): Long {
        val seq = if (retainMs > 0L) engine.commitRetainingTailMs(retainMs) else engine.commit()
        // Only a VAD cut has a probe behind it. lastCut() is read IMMEDIATELY after the verdict,
        // because the state machine re-arms as it returns true; an amplitude endpointer is not a
        // SileroEndpointer and yields null, which renders the unknown shape (p=-1.00).
        val ec = if (cut == EndpointDiag.VAD) (endpointer as? SileroEndpointer)?.lastCut() else null
        android.util.Log.i("WE-DIAG", EndpointDiag.endpointLine(seq, cut, ec))
        android.util.Log.i("WE-DIAG", EndpointDiag.queueLine(segmentQueueDepth.onCommitted(seq)))
        return seq
    }
```

(the signature is Task F7's, unchanged — `nowMs` is quoted here only so the replacement block is
complete; this task adds the two `ec` lines and nothing else)

added to its KDoc:

```kotlin
     * The `endpoint:` line is emitted even for a `-1` seq — "the endpointer fired and there was
     * nothing buffered" is exactly the kind of thing this family exists to make visible — while the
     * backlog deliberately ignores it, because that seq will never resolve.
```

(c) `:1699-1702` — the cap log line. Replace:

```kotlin
                android.util.Log.i(
                    "WE-DIAG",
                    "wall-clock cap -> commit (cap=${segmentCapPolicy.currentCapMs()}ms)",
                )
```

with:

```kotlin
                android.util.Log.i("WE-DIAG", EndpointDiag.capCommitLine(segmentCapPolicy.currentCapMs()))
```

(d) **The five call sites need no edit in this task.** Task F7 already routed them through
`commitSegment(engine, EndpointDiag.<KIND>)` with their cut kind, precisely so the funnel could grow
the `endpoint:` line here without touching a single branch again:

```kotlin
                    transcriptionEngine?.let { commitSegment(it, EndpointDiag.SWITCH) }   // :915
                commitSegment(engine, EndpointDiag.VAD, nowMs = now)                      // :1694
                commitSegment(engine, EndpointDiag.CAP, retainMs, now)                    // :1721
        transcriptionEngine?.let { commitSegment(it, EndpointDiag.SWITCH) }               // :1818
        transcriptionEngine?.let { commitSegment(it, EndpointDiag.STOP) }                 // :2388
```

Then the compile gate:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:assembleDebug --no-daemon; if ($?) { .\gradlew.bat :app:testDebugUnitTest --no-daemon }
```

Evidence: `BUILD SUCCESSFUL`, and the full `testDebugUnitTest` XML directory reports zero failures.

- [ ] **Step 5: Commit** —

```powershell
git add app/src/main/java/com/whispereverywhere/service/EndpointDiag.kt app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt app/src/test/java/com/whispereverywhere/service/EndpointDiagTest.kt; git commit -m @'
feat(diag): endpoint: lines at all five cut sites, and the cap line as a VAD-failure signature

cut=vad|cap|stop|switch names WHY every segment was cut, joinable to segment-timing on seq.
p=-1.00 is the no-verdict sentinel, matching the native frame contract - never 0.00, which
would read as "the probe was certain there was no speech".

The wall-clock cap line is reworded by APPENDING: `wall-clock cap -> commit (cap=Nms)` stays
byte for byte, because that prefix is the existing grep and cap=4000ms in a cloud session is
the documented regression signature for the LOCAL-only first-cap suppression.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT
'@
```

---

### Task F9: `perceived: speechEndToVisible` — the headline metric

**Files:**
- Create `app/src/main/java/com/whispereverywhere/service/PerceivedLatency.kt`
- Create `app/src/test/java/com/whispereverywhere/service/PerceivedLatencyTest.kt`
- Modify `app/src/main/java/com/whispereverywhere/service/EndpointDiag.kt` (append one formatter)
- Modify `app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt` — a field at
  `:307` (beside `segmentQueueDepth` from Task F7), the funnel `commitSegment` (before `:2576`),
  `onSegmentResolved` at `:2298-2306` (post-Task-F7 shape), and the per-session reset at `:2152`
  (beside the `segmentQueueDepth.reset()` Task F7 landed there)

**Interfaces:**
- Consumes: `com.whispereverywhere.audio.EndpointCut.trailMs` (Task C8), which the funnel already
  reads for the `endpoint:` line, and the funnel's own `nowMs` parameter (Task F7) — the FRAME's
  clock, which the two capture-thread commit sites pass in. `speechEnd = nowMs - trailMs`, so the
  perceived metric needs no accessor of its own on the endpointer and no second read of its state.
  **The stamp must not re-read the clock inside the funnel:** `trailMs` was computed from the capture
  chunk's `nowMs`, taken before `endpointer.onFrame`, and the funnel then runs `engine.commit()` (a
  full buffer snapshot under `bufferLock`, up to ~960 KB) plus two `Log.i` calls before it would get
  there — every reported `speechEndToVisible` would be smaller than the truth by that delta, on the
  exact metric S3 Check 2 and S4's release-notes contingency read.

**Where the stamp is taken, and where it is NOT.** The metric means WORDS VISIBLE, so it is read at
`deliverReleasedText`'s return, on the same Main tick, where the view write has already happened.
Workstream G's in-flight strip renders earlier (at the commit, and again at the resolution) and is
explicitly NOT a perceived-latency stamp: it acknowledges that an utterance is in flight, which is a
different claim from "you can read your sentence". Task G5 moves the queue decrement ahead of
delivery and changes nothing about this stamp.
- Produces:
  - `class PerceivedLatency(maxTracked: Int = MAX_TRACKED)` with
    `fun onCommitted(seq: Long, speechEndMs: Long)`, `fun onVisible(seq: Long, nowMs: Long): Long?`,
    `fun reset()`; companion `MAX_TRACKED = 64`
  - `EndpointDiag.perceivedLine(seq: Long, speechEndToVisibleMs: Long): String`
    → `"perceived: seq=<n> speechEndToVisible=<n>ms"`

- [ ] **Step 1: Write the failing test** — create
`app/src/test/java/com/whispereverywhere/service/PerceivedLatencyTest.kt`:

```kotlin
package com.whispereverywhere.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * speech-end -> text-visible, the quantity the 3.7 mandate is actually about (owner acceptance:
 * pro ~1.3-1.8 s constant, multi ~2.8 s at the paced boundary; the headline is the VARIANCE, so
 * the stamp has to be per-segment, not an average).
 *
 * Deliberately keyed by seq rather than a FIFO pop: segments that resolve to silence release no
 * text at all, so a positional queue would drift one entry per silent segment and start
 * attributing one utterance's wait to the next one.
 */
class PerceivedLatencyTest {

    @Test
    fun reportsTheWaitFromSpeechEndToTheMomentTextBecomesVisible() {
        val p = PerceivedLatency()
        p.onCommitted(seq = 0L, speechEndMs = 1_000L)
        assertEquals(1_500L, p.onVisible(seq = 0L, nowMs = 2_500L))
    }

    @Test
    fun aSeqWithNoSpeechEndStampReportsNothing() {
        // Cap/stop/switch cuts have no speech-end instant, so there is no honest number to report.
        val p = PerceivedLatency()
        assertNull(p.onVisible(seq = 0L, nowMs = 2_500L))
    }

    @Test
    fun aStampIsConsumedExactlyOnce() {
        val p = PerceivedLatency()
        p.onCommitted(seq = 0L, speechEndMs = 1_000L)
        assertEquals(1_500L, p.onVisible(seq = 0L, nowMs = 2_500L))
        assertNull(p.onVisible(seq = 0L, nowMs = 3_000L))
    }

    @Test
    fun aSilentSegmentDoesNotShiftTheNextUtterancesNumber() {
        // seq 0 resolves to silence and is never delivered; seq 1 is real. Under a positional
        // queue seq 1 would be handed seq 0's stamp and report a wait that never happened.
        val p = PerceivedLatency()
        p.onCommitted(seq = 0L, speechEndMs = 1_000L)
        p.onCommitted(seq = 1L, speechEndMs = 5_000L)
        assertEquals(1_400L, p.onVisible(seq = 1L, nowMs = 6_400L))
    }

    @Test
    fun visibilityPrunesEveryEarlierStamp_soSilentSegmentsCannotAccumulate() {
        val p = PerceivedLatency()
        p.onCommitted(seq = 0L, speechEndMs = 1_000L)
        p.onCommitted(seq = 1L, speechEndMs = 2_000L)
        p.onCommitted(seq = 2L, speechEndMs = 3_000L)
        assertEquals(1_000L, p.onVisible(seq = 2L, nowMs = 4_000L))
        // Delivery is strictly in seq order, so 0 and 1 can never become visible after 2.
        assertNull(p.onVisible(seq = 0L, nowMs = 5_000L))
        assertNull(p.onVisible(seq = 1L, nowMs = 5_000L))
    }

    @Test
    fun trackingIsBounded_theOldestStampIsDroppedFirst() {
        val p = PerceivedLatency(maxTracked = 2)
        p.onCommitted(seq = 0L, speechEndMs = 1_000L)
        p.onCommitted(seq = 1L, speechEndMs = 2_000L)
        p.onCommitted(seq = 2L, speechEndMs = 3_000L)   // evicts seq 0
        assertNull(p.onVisible(seq = 0L, nowMs = 4_000L))
        assertEquals(2_000L, p.onVisible(seq = 1L, nowMs = 4_000L))
    }

    @Test
    fun negativeSeqIsNeverStamped() {
        // commit() returned -1: nothing was cut, and nothing will ever resolve.
        val p = PerceivedLatency()
        p.onCommitted(seq = -1L, speechEndMs = 1_000L)
        assertNull(p.onVisible(seq = -1L, nowMs = 2_000L))
    }

    @Test
    fun resetClearsEveryStampForTheNextSession() {
        val p = PerceivedLatency()
        p.onCommitted(seq = 0L, speechEndMs = 1_000L)
        p.reset()
        assertNull(p.onVisible(seq = 0L, nowMs = 2_000L))
    }

    @Test
    fun perceivedLineMatchesTheGreppableFormatExactly() {
        assertEquals(
            "perceived: seq=4 speechEndToVisible=1500ms",
            EndpointDiag.perceivedLine(seq = 4L, speechEndToVisibleMs = 1_500L),
        )
    }
}
```

- [ ] **Step 2: Run it, expected failure** —

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests "com.whispereverywhere.service.PerceivedLatencyTest" --no-daemon
```

Expected: `e: ...PerceivedLatencyTest.kt: Unresolved reference: PerceivedLatency` and
`e: ...PerceivedLatencyTest.kt: Unresolved reference: perceivedLine`.

- [ ] **Step 3: Minimal implementation** —

(a) Create `app/src/main/java/com/whispereverywhere/service/PerceivedLatency.kt`:

```kotlin
package com.whispereverywhere.service

/**
 * speech-end -> text-visible, per segment (3.7 Workstream F).
 *
 * This is the number the mandate is about. Today later-segment latency is uniformly distributed
 * over 0-15 s depending on where in the cap window the user stopped talking; under 3.7 it should
 * be a constant `HANGOVER + C(u)`. The headline is therefore the VARIANCE, which means the stamp
 * must be per segment — a running average would hide exactly the property being claimed.
 *
 * KEYED BY SEQ, not a positional queue. Segments that resolve to silence release no text, so a
 * FIFO pop would drift one entry per silent segment and start attributing one utterance's wait to
 * the next one — a diagnostic that lies in precisely the sessions worth diagnosing. [onVisible]
 * also prunes every EARLIER seq, which is sound because delivery is strictly in seq order
 * (SegmentOrderer), and is what keeps the map bounded through a session of quiet commits.
 *
 * Only endpoint (`cut=vad`) commits are stamped: a cap/stop/switch cut has no speech-end instant,
 * so there is no honest number to report and [onVisible] returns null rather than inventing one.
 *
 * THREADING: stamps are written from the capture thread (the endpoint cut) and read on Main
 * (delivery). Synchronized on the instance; a handful of map operations per segment.
 */
class PerceivedLatency(private val maxTracked: Int = MAX_TRACKED) {

    /** seq -> wall-clock ms of the frame that ended speech for that segment. */
    private val stamps = java.util.TreeMap<Long, Long>()

    /** Records an endpoint cut. Negative [seq] ("nothing was cut") is ignored. */
    @Synchronized
    fun onCommitted(seq: Long, speechEndMs: Long) {
        if (seq < 0L) return
        stamps[seq] = speechEndMs
        while (stamps.size > maxTracked) stamps.remove(stamps.firstKey())
    }

    /**
     * [seq]'s text just became visible. Returns the wait in ms, or null when this seq carried no
     * speech-end stamp. Consumes the stamp and prunes every earlier one.
     */
    @Synchronized
    fun onVisible(seq: Long, nowMs: Long): Long? {
        val stamp = stamps.remove(seq)
        while (stamps.isNotEmpty() && stamps.firstKey() < seq) stamps.remove(stamps.firstKey())
        return if (stamp == null) null else nowMs - stamp
    }

    /** Per-session reset: seq numbering restarts at 0 on every engine connect(). */
    @Synchronized
    fun reset() {
        stamps.clear()
    }

    companion object {
        /**
         * ~4 minutes of utterance-cadence commits. A bound, not a budget: [onVisible]'s pruning is
         * what actually keeps the map small, and this only backstops a pathological session where
         * nothing ever resolves.
         */
        const val MAX_TRACKED = 64
    }
}
```

(b) `EndpointDiag.kt` — append inside the object, after `capCommitLine`:

```kotlin

    /**
     * The headline metric: how long the user waited between finishing a sentence and seeing it.
     * Emitted only for endpoint cuts — see [PerceivedLatency] for why cap/stop/switch cuts have
     * no honest number here.
     */
    fun perceivedLine(seq: Long, speechEndToVisibleMs: Long): String =
        "perceived: seq=$seq speechEndToVisible=${speechEndToVisibleMs}ms"
```

- [ ] **Step 4: Run tests green, then wire the service** —

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests "com.whispereverywhere.service.PerceivedLatencyTest" --no-daemon
```

Evidence: `TEST-com.whispereverywhere.service.PerceivedLatencyTest.xml` shows `tests="9" failures="0"`.

Then wire `FloatingBubbleService.kt`:

(a) After the `segmentQueueDepth` field (added in Task F7 at `:307`), insert:

```kotlin
    /**
     * 3.7 Workstream F: speech-end -> text-visible per segment, the headline acceptance metric.
     * Stamped at the endpoint cut, read where the text actually renders.
     */
    private val perceivedLatency = PerceivedLatency()
```

(b) Extend the funnel `commitSegment` (Task F7, grown in Task F8) with the speech-end stamp. Its
KDoc gains one paragraph and the body one `if`; nothing else about it changes, and this is the last
task in Workstream F that touches it:

```kotlin
     * The speech-end instant is DERIVED from the same `trailMs` the `endpoint:` line reports and
     * from the FRAME clock the caller handed in (`speechEnd = nowMs - trailMs`), so the perceived
     * metric needs no accessor of its own on the endpointer and no second read of its state — and
     * no second read of the clock, which would land after the commit's buffer snapshot and bias
     * every number low. Only `cut=vad` is stamped: the other three cut kinds have no speech-end
     * instant, and a stamp with no honest instant behind it would be a number the acceptance sheet
     * could not use.
```

```kotlin
    private fun commitSegment(
        engine: TranscriptionEngine,
        cut: String,
        retainMs: Long = 0L,
        nowMs: Long = System.currentTimeMillis(),
    ): Long {
        val seq = if (retainMs > 0L) engine.commitRetainingTailMs(retainMs) else engine.commit()
        val ec = if (cut == EndpointDiag.VAD) (endpointer as? SileroEndpointer)?.lastCut() else null
        android.util.Log.i("WE-DIAG", EndpointDiag.endpointLine(seq, cut, ec))
        if (cut == EndpointDiag.VAD && ec != null) {
            perceivedLatency.onCommitted(seq, nowMs - ec.trailMs)
        }
        android.util.Log.i("WE-DIAG", EndpointDiag.queueLine(segmentQueueDepth.onCommitted(seq)))
        return seq
    }
```

(c) `onSegmentResolved` at `:2298-2306` (post-Task-F7 shape) — replace the coroutine body:

```kotlin
                serviceScope.launch(Dispatchers.Main) {
                    // The Release is captured rather than inlined so the perceived stamp can be
                    // read at the moment the text ACTUALLY rendered: deliverReleasedText writes
                    // the view synchronously on Main and early-returns on blank, so "returned
                    // having delivered non-blank text" is exactly the visible instant. The
                    // SegmentOrderer's release rules are untouched — this only names its result.
                    val release = segmentOrderer.onResolved(seq, outcome)
                    deliverReleasedText(release.text)
                    val waited = perceivedLatency.onVisible(seq, System.currentTimeMillis())
                    // Always consume the stamp (it prunes), but only REPORT when text rendered:
                    // a segment that resolved to silence made nothing visible to time.
                    if (waited != null && release.text.isNotBlank()) {
                        android.util.Log.i("WE-DIAG", EndpointDiag.perceivedLine(seq, waited))
                    }
                    android.util.Log.i(
                        "WE-DIAG",
                        EndpointDiag.queueLine(segmentQueueDepth.onResolved(seq)),
                    )
                }
```

(d) At `:2152`, beside the `segmentQueueDepth.reset()` Task F7 added after
`segmentOrderer = com.whispereverywhere.transcription.SegmentOrderer()`, insert:

```kotlin
        perceivedLatency.reset()
```

**Session START, not stop — for the same reason and a sharper one.** Seq numbering restarts at 0 in
`connect()`, so the session boundary is here. Resetting at stop would drop every stamp for the
segments still in flight when the user taps stop, and those are systematically the SLOWEST samples:
at pro's utterance cadence the last utterance is always in flight, and on multi (6 s pacing, F=2.3 s)
several are. `onVisible` would return null for all of them, no `perceived:` line would be emitted,
and S3 Check 2's p50/p95 grid — the contingency gate for S4's release-notes latency claim — would be
biased low by exactly the tail it is supposed to measure.

Then the compile gate and the full suite:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:assembleDebug --no-daemon; if ($?) { .\gradlew.bat :app:testDebugUnitTest --no-daemon }
```

Evidence: `BUILD SUCCESSFUL`, and every XML under
`C:/Users/bastr/.androidbuild/WhisperEverywhere/app/test-results/testDebugUnitTest` reports zero
failures and zero errors.

- [ ] **Step 5: Commit** —

```powershell
git add app/src/main/java/com/whispereverywhere/service/PerceivedLatency.kt app/src/main/java/com/whispereverywhere/service/EndpointDiag.kt app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt app/src/test/java/com/whispereverywhere/service/PerceivedLatencyTest.kt; git commit -m @'
feat(diag): perceived: speechEndToVisible, stamped where the text actually renders

The headline acceptance metric, per segment because the claim is about VARIANCE - today later
segments are uniform over 0-15 s, and an average would hide exactly the property 3.7 asserts.

Keyed by seq, not a FIFO pop: silent segments release no text, so a positional queue would
drift one entry per silent segment and attribute one utterance's wait to the next. The speech
end is derived from the same trailMs the endpoint: line already reports, so the endpointer
needs no extra accessor. SegmentOrderer release rules untouched - the Release is captured
rather than inlined, nothing more.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT
'@
```

## Workstream G — UX: the in-flight state

---

### Task G1: One queue counter — the duplicate is not created, and the survivor gains G's pins

**Files:**
- Modify `app/src/test/java/com/whispereverywhere/service/SegmentQueueDepthTest.kt` (append two tests)

**Interfaces:**
- Consumes: `class SegmentQueueDepth` — **the seq-SET-based counter landed in Task F7**, with
  `onCommitted(seq: Long): Int`, `onResolved(seq: Long): Int`, `depth(): Int`, `reset()`.
- Produces: no production code. Two additional pins on the survivor.

**Why this task lands nothing new.** Workstream G's own draft of the in-flight strip carried a
second queue counter — an `AtomicInteger` with `onCommitted()` / `onResolved()` taking no arguments.
It is **deliberately not created**: two counters would let the on-screen line and the `queue: depth=`
diagnostic disagree, which is precisely the failure the diagnostic exists to catch, and the
seq-keyed survivor is strictly stronger (a duplicate resolution, an unknown seq or a re-committed
seq can neither strand the depth above zero nor invert it, and an `AtomicInteger` cannot express
that at all). The strip therefore reads `segmentQueueDepth.depth()` — the same number the log line
reports, by construction.

What G's version DID have that the survivor's suite does not is an unordered multi-writer storm
(commits and resolutions racing from four threads with no happens-before between a seq's commit and
its resolution) and an explicit "a fresh counter is empty" pin. Both are kept, rewritten against the
survivor's seq-keyed API. **This task's red already happened, in Task F7** — that is what a
reconciliation task looks like when the winning implementation landed first, and it is recorded here
rather than silently dropped so the coverage is not lost with the class.

- [ ] **Step 1: Write the tests** — append to
  `app/src/test/java/com/whispereverywhere/service/SegmentQueueDepthTest.kt`, inside the class:

```kotlin
    @Test
    fun aFreshCounterIsEmpty() {
        // The strip reads depth() before anything has been committed, on every session's first
        // render pass; it must not have to defend against a garbage initial value.
        assertEquals(0, SegmentQueueDepth().depth())
    }

    @Test
    fun unorderedCommitsAndResolutionsFromFourRealThreadsStayInBounds() {
        // The ORDERED case is pinned above (each resolution enqueued from inside its own commit).
        // This is the adversarial one: two committer threads and two resolver threads with no
        // happens-before between a seq's commit and its resolution, which is what a torn-down
        // session, an error-path flush and the unconditional stop flush can genuinely produce.
        // REAL pools, never a same-thread stub: in production onCommitted() is called from the
        // AudioRecord capture thread while onResolved() runs on Main.
        val q = SegmentQueueDepth()
        val committers = Executors.newFixedThreadPool(2)
        val resolvers = Executors.newFixedThreadPool(2)
        try {
            val start = CountDownLatch(1)
            val done = CountDownLatch(4)
            repeat(2) { worker ->
                committers.execute {
                    start.await()
                    for (i in 0 until 500) q.onCommitted((worker * 500 + i).toLong())
                    done.countDown()
                }
            }
            repeat(2) { worker ->
                resolvers.execute {
                    start.await()
                    for (i in 0 until 500) q.onResolved((worker * 500 + i).toLong())
                    done.countDown()
                }
            }
            start.countDown()
            assertTrue("workers did not finish", done.await(20, TimeUnit.SECONDS))

            // 1000 distinct seqs committed and the same 1000 resolved, in any interleaving: every
            // resolution that arrived early is a no-op on an absent key, so a residue of real keys
            // is expected here. Draining all of them DETERMINISTICALLY, from this thread, is what
            // makes the assertion bite — an unsynchronized HashSet loses or duplicates entries
            // under a four-thread storm and leaves a non-zero residue that no drain can clear.
            // (A bare `depth() in 0..1000` would pass by construction: a set fed 1000 distinct keys
            // cannot exceed 1000 whether it is synchronized or not.)
            for (seq in 0 until 1000) q.onResolved(seq.toLong())
            assertEquals("the storm left entries the drain could not clear", 0, q.depth())

            // And the counter is still usable afterwards — no torn state.
            q.reset()
            assertEquals(0, q.depth())
            assertEquals(1, q.onCommitted(0L))
        } finally {
            committers.shutdownNow()
            resolvers.shutdownNow()
        }
    }
```

- [ ] **Step 2: Run them** —

`$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests "com.whispereverywhere.service.SegmentQueueDepthTest" --no-daemon`

Expected: **green**, and that is the point — these two pins are a guard over an implementation that
already exists, exactly like the two already-green guard assertions in Task H5. If the storm test
goes red, the survivor's `@Synchronized` boundary is wrong and Task F7 is the task to fix, not this
one. `TEST-com.whispereverywhere.service.SegmentQueueDepthTest.xml` shows
`tests="9" failures="0" errors="0"`.

- [ ] **Step 3: Confirm no second counter exists** — one production grep, because the whole point of
  this task is a class that must NOT be there:

```powershell
Get-ChildItem app\src\main\java\com\whispereverywhere -Recurse -Filter *.kt | Select-String -SimpleMatch -Pattern 'class SegmentQueueDepth' | Select-Object Path
Select-String -Path app\src\main\java\com\whispereverywhere\service\FloatingBubbleService.kt -SimpleMatch -Pattern 'SegmentQueueDepth()' | Measure-Object | Select-Object -ExpandProperty Count
```

(`Select-String` has no `-Recurse`; the enumeration is `Get-ChildItem`'s job — the same idiom Task S3
Step 2 uses. The second command targets a single file and is already valid.)

Expected: exactly ONE file declares the class
(`app\src\main\java\com\whispereverywhere\service\SegmentQueueDepth.kt`), and the service constructs
exactly ONE instance. Two of either means the deleted duplicate came back.

- [ ] **Step 4: Run the neighbouring suites** —

`$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests "com.whispereverywhere.service.*" --no-daemon`

Expected: `BUILD SUCCESSFUL`; `failures=0 errors=0` and the **+2 delta** for this task.

- [ ] **Step 5: Commit** —

```
git add app/src/test/java/com/whispereverywhere/service/SegmentQueueDepthTest.kt
git commit -m "test(service): G — one queue counter, with G's unordered storm pinned on it

Workstream G's own AtomicInteger queue counter is deliberately never created:
the seq-SET counter from Workstream F is the survivor, so the in-flight strip
and the queue: depth= line read the same number and cannot disagree. G's two
missing pins — a fresh counter reads zero, and four real threads committing and
resolving with no ordering between them keep the depth in bounds — are rewritten
against the seq-keyed API and kept.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT"
```

---

### Task G2: Strip ownership + the in-flight label (pure rules)

**Files:**
- Modify `app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt` — insert after `processingTimerRunsIn` (currently ends at line 167), before `class FloatingBubbleService` at line 169.
- Create `app/src/test/java/com/whispereverywhere/service/InFlightStripTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `internal fun deltaOwnsPreviewStrip(sessionIsLive: Boolean): Boolean` and `internal fun inFlightStripLabel(depth: Int): String?` — top-level in the `com.whispereverywhere.service` package, the same shape as the existing `connectingStatusLabel` / `processingTimerRunsIn` phase-ownership rules. Tasks G3–G5 consume both.

- [ ] **Step 1: Write the failing test** — create `app/src/test/java/com/whispereverywhere/service/InFlightStripTest.kt`:

```kotlin
package com.whispereverywhere.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InFlightStripTest {

    // ------------------------------------------------------------- who owns the strip

    @Test fun a_server_driven_live_session_keeps_its_deltas_on_the_strip() {
        // CLOUD_LIVE partials stream AS SPOKEN and are the whole point of the strip there.
        // 3.7 changes nothing for them.
        assertTrue(deltaOwnsPreviewStrip(sessionIsLive = true))
    }

    @Test fun a_local_session_no_longer_lets_native_deltas_drive_the_strip() {
        // whisper.cpp fires new_segment AFTER the window's decode, so at utterance cadence the
        // whole burst — and LocalWhisperEngine's terminal onDelta("") — lands inside one
        // Choreographer frame: set and cleared before anything renders. The commit/resolve
        // in-flight line replaces it. D4's plumbing (DeltaThrottle, the JNI callback,
        // transcribeStreaming) is untouched — only this render is gated.
        assertFalse(deltaOwnsPreviewStrip(sessionIsLive = false))
    }

    // ------------------------------------------------------------- what the line says

    @Test fun an_empty_queue_has_no_line() {
        assertNull(inFlightStripLabel(0))
    }

    @Test fun a_negative_depth_is_treated_as_empty() {
        // SegmentQueueDepth floors at zero, but the label must not be the only thing standing
        // between a miscount and a "-1 in queue" on a user's screen.
        assertNull(inFlightStripLabel(-1))
    }

    @Test fun one_utterance_in_flight_says_only_that() {
        assertEquals("Transcribing…", inFlightStripLabel(1))
    }

    @Test fun a_backlog_names_its_depth() {
        // The ONLY surface that makes a growing multi backlog visible WHILE it grows.
        assertEquals("Transcribing… (2 in queue)", inFlightStripLabel(2))
        assertEquals("Transcribing… (7 in queue)", inFlightStripLabel(7))
    }

    @Test fun the_line_makes_no_speed_claim_and_names_no_provider() {
        // Same copy discipline as HowToGuide and every other user-facing string.
        listOf(0, 1, 2, 9).forEach { d ->
            val text = (inFlightStripLabel(d) ?: "").lowercase()
            listOf("faster", "fastest", "quicker", "quickest", "instant", "real-time")
                .forEach { banned ->
                    assertFalse("in-flight line contains banned word '$banned'", text.contains(banned))
                }
        }
    }
}
```

- [ ] **Step 2: Run it, expected failure** —

`$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests "com.whispereverywhere.service.InFlightStripTest" --no-daemon`

Expected: `> Task :app:compileDebugUnitTestKotlin FAILED` with
`e: ...InFlightStripTest.kt:14:20 Unresolved reference: deltaOwnsPreviewStrip` and
`e: ...InFlightStripTest.kt:30:21 Unresolved reference: inFlightStripLabel`

- [ ] **Step 3: Minimal implementation** — in `FloatingBubbleService.kt`, insert between the end of `processingTimerRunsIn` (line 167, `        state == FloatingBubbleService.BubbleState.FINALIZING`) and the blank line before `class FloatingBubbleService` (line 169):

```kotlin

/**
 * Who owns the preview strip's TextView (3.7, Workstream G). Only a SERVER-DRIVEN LIVE session
 * still streams deltas onto it: its partials arrive as the words are spoken, which is the whole
 * point of the surface. A LOCAL session's native deltas all arrive in one burst at ~100 % of the
 * transcribe's wall time — whisper.cpp fires `new_segment_callback` after the window's decode —
 * and `LocalWhisperEngine` follows them with a terminal `onDelta("")`, so at utterance cadence
 * the strip was set and hidden inside a single Choreographer frame. That is the "flicker" H2
 * filed as accepted cosmetic; it is the render being pointless, not slow.
 *
 * D4's plumbing stays exactly where it is — `transcribeStreaming`, the JNI new-segment callback
 * and [com.whispereverywhere.transcription.DeltaThrottle] are untouched and still feed CLOUD_LIVE;
 * only this render decision moved. Cloud BATCH is unaffected either way: it emits no deltas
 * (its `CloudRelay` forwards a callback its engine never fires, and the fallback's `LocalRelay`
 * swallows the rescue engine's).
 *
 * Pure so the rule is a pinned contract rather than a buried `if` ([InFlightStripTest]), the same
 * discipline as [connectingStatusLabel], [processingTimerRunsIn] and
 * [com.whispereverywhere.transcription.live.LiveTurnPolicy].
 */
internal fun deltaOwnsPreviewStrip(sessionIsLive: Boolean): Boolean = sessionIsLive

/**
 * The LOCAL in-flight line for [depth] committed-but-unresolved segments, or null when the queue
 * is empty (3.7, Workstream G).
 *
 * Under VAD endpointing there is a genuinely new repeating state that did not exist before:
 * "the endpoint fired, this sentence is in flight", lasting ~1.3–4.3 s and recurring ~16×/minute,
 * during which nothing on screen changes. The depth suffix appears only past one, and it is the
 * only surface that makes a growing backlog visible WHILE it grows rather than at stop.
 */
internal fun inFlightStripLabel(depth: Int): String? = when {
    depth <= 0 -> null
    depth == 1 -> "Transcribing…"
    else -> "Transcribing… ($depth in queue)"
}
```

- [ ] **Step 4: Run tests green** —

`$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests "com.whispereverywhere.service.InFlightStripTest" --tests "com.whispereverywhere.service.ConnectingLabelTest" --tests "com.whispereverywhere.service.ProcessingTimerPolicyTest" --no-daemon`

Expected: `BUILD SUCCESSFUL`; 7 tests / 0 failures in
`...\test-results\testDebugUnitTest\TEST-com.whispereverywhere.service.InFlightStripTest.xml`, and the two neighbouring phase-rule suites still green.

- [ ] **Step 5: Commit** —

```
git add app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt app/src/test/java/com/whispereverywhere/service/InFlightStripTest.kt
git commit -m "feat(bubble): G — strip ownership + the in-flight line, as pure rules

deltaOwnsPreviewStrip() and inFlightStripLabel() join connectingStatusLabel and
processingTimerRunsIn as pinned phase rules on the same TextView. Local native
deltas stop driving the strip; CLOUD_LIVE partials are untouched and D4's
plumbing (DeltaThrottle, the JNI callback, transcribeStreaming) stays intact.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT"
```

---

### Task G3: Hang the in-flight strip off the commit funnel

**Files:**
- Modify `app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt` — add `commitAdvancesQueueDepth` beside the Task G2 rules (after `inFlightStripLabel`); ONE edit to the funnel `commitSegment` (landed in Task F7, grown in F8/F9); the `renderInFlightStrip()` stub after it. **No reset is added:** Task F7 already established the per-session `segmentQueueDepth.reset()` at line 2152.
- Modify `app/src/test/java/com/whispereverywhere/service/InFlightStripTest.kt` — add the funnel rule's assertions.

**Interfaces:**
- Consumes: `SegmentQueueDepth` and the funnel `commitSegment(engine, cut, retainMs, nowMs)` (Task F7); `LocalWhisperEngine`'s **-1 "nothing was cut" return**, documented on `TranscriptionEngine.commit()`. `NO_SEGMENT` itself is a `private companion object` const (`LocalWhisperEngine.kt:62-67`) and is UNREACHABLE from the `service` package — never reference it by name; compare against `0L` directly (`seq >= 0L`).
- Produces: `internal fun commitAdvancesQueueDepth(seq: Long): Boolean` and a `renderInFlightStrip()` hook inside the existing funnel.

**What this task does NOT do.** It does not create a funnel, a counter, a field **or a reset**:
Workstream F landed all four, the five call sites already route through `commitSegment`, every commit
already updates `segmentQueueDepth` and emits `queue: depth=`, and the per-session
`segmentQueueDepth.reset()` already sits at line 2152 beside the orderer's. What is missing is the
SCREEN — the number is in the log and nowhere else. So this task adds exactly one line to the funnel
(a Main-hop repaint) plus the guard that decides when a repaint is worth posting. One task, one edit
to the funnel, no second writer.

- [ ] **Step 1: Write the failing test** — append to `app/src/test/java/com/whispereverywhere/service/InFlightStripTest.kt`, immediately before the final closing `}`:

```kotlin

    // ------------------------------------------------------------- the commit funnel

    @Test fun a_commit_that_cut_nothing_does_not_advance_the_queue() {
        // -1L is TranscriptionEngine.commit()'s documented "there was nothing to cut" answer — the
        // silent no-op the stop flush and switchSource hit on an already-empty buffer. Counting it
        // would strand the strip on "Transcribing…" for the rest of the session, because no
        // resolution can ever arrive to take it back down. (The engine's own NO_SEGMENT constant is
        // private to LocalWhisperEngine and deliberately not referenced from this package.)
        assertFalse(commitAdvancesQueueDepth(-1L))
    }

    @Test fun the_very_first_segment_of_a_session_advances_the_queue() {
        // seq numbering restarts at 0 in connect(), so zero is a REAL segment, not a sentinel.
        assertTrue(commitAdvancesQueueDepth(0L))
    }

    @Test fun any_real_seq_advances_the_queue() {
        assertTrue(commitAdvancesQueueDepth(1L))
        assertTrue(commitAdvancesQueueDepth(4_096L))
    }
}
```

(delete the now-duplicated trailing `}` so the file still ends with exactly one class-closing brace)

- [ ] **Step 2: Run it, expected failure** —

`$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests "com.whispereverywhere.service.InFlightStripTest" --no-daemon`

Expected: `> Task :app:compileDebugUnitTestKotlin FAILED` with
`e: ...InFlightStripTest.kt:78:21 Unresolved reference: commitAdvancesQueueDepth`

- [ ] **Step 3: Minimal implementation** — two edits in `FloatingBubbleService.kt`.

(a) after `inFlightStripLabel` (the Task G2 block), add the rule:

```kotlin

/**
 * Does this `commit()` return value represent a segment the queue should count (3.7, G)?
 *
 * [TranscriptionEngine.commit] returns `-1L` for "nothing to cut" — the ordinary outcome of the
 * unconditional stop flush and of `switchSource` on an already-drained buffer. (The engine names
 * that value `NO_SEGMENT` in a private companion; it is not visible here, so the contract is the
 * documented `-1`, compared directly.) Counting one would leave the in-flight line up for the rest
 * of the session with no resolution able to take it down. seq 0 is a REAL segment: connect()
 * restarts numbering at zero every session.
 *
 * [SegmentQueueDepth] applies the same rule to its own set; this names it for the SCREEN, which
 * must not post a repaint for a commit that changed nothing.
 */
internal fun commitAdvancesQueueDepth(seq: Long): Boolean = seq >= 0L
```

(b) add the repaint to the funnel `commitSegment` — ONE line plus the stub. The funnel's existing
body (the commit, the `endpoint:` line, the perceived stamp, the `queue:` line) is untouched:

```kotlin
        android.util.Log.i("WE-DIAG", EndpointDiag.queueLine(segmentQueueDepth.onCommitted(seq)))
        // 3.7 G: the depth is now in the log AND on screen from one place. The repaint hops to
        // Main because this funnel is also called from the capture thread; it is skipped entirely
        // for a commit that cut nothing, which cannot have changed the depth.
        if (commitAdvancesQueueDepth(seq)) serviceScope.launch(Dispatchers.Main) { renderInFlightStrip() }
        return seq
    }
```

and, immediately after the funnel, the stub that Task G4 replaces with the real painter:

```kotlin
    /** Painted in Task G4; the funnel calls it from the first commit onward. */
    private fun renderInFlightStrip() = Unit
```

**No third edit: the per-session reset already exists.** Task F7 landed
`segmentQueueDepth.reset()` at line 2152, immediately after
`segmentOrderer = com.whispereverywhere.transcription.SegmentOrderer()`, for the same reason the
orderer is recreated there — a depth carried over from a torn-down session would render a phantom
backlog on the next one's first commit, and the strip reads `depth()` on every session's first
render pass. Task F9 put `perceivedLatency.reset()` beside it. **Verify both lines are present and
add neither**; a second reset call would be a second writer to the state this task's whole argument
is that there is only one of.

- [ ] **Step 4: Run tests green** —

`$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests "com.whispereverywhere.service.*" --no-daemon`

Expected: `BUILD SUCCESSFUL`; `TEST-com.whispereverywhere.service.InFlightStripTest.xml` shows 10 tests / 0 failures, and `SegmentCapPolicyTest` (the wall-cap contract), `CapSeamPinTest` and `EndpointerLifecyclePinTest` are unchanged and green — this task edits no branch and no call site.

- [ ] **Step 5: Commit** —

```
git add app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt app/src/test/java/com/whispereverywhere/service/InFlightStripTest.kt
git commit -m "feat(bubble): G — the in-flight strip repaints from the one commit funnel

The five commit sites already route through commitSegment() and already update
the queue depth; this hangs the screen off the same place, so the log line and
the strip can never disagree. A commit that cut nothing (seq -1) posts no
repaint, because it cannot have changed the depth. The wall-cap else-if, the
cloud 4s suppression and the unconditional stop flush are untouched.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT"
```

---

### Task G4: Paint the in-flight line, and kill the per-utterance reclamp churn

**Files:**
- Modify `app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt` — add `StripVisibility` + `inFlightStripVisibility` beside the Task G2 / G3 rules (after `commitAdvancesQueueDepth`, which Task G3 adds); replace the `renderInFlightStrip()` stub from Task G3; fix `estimatedWindowSize` line **1321**.
- Modify `app/src/test/java/com/whispereverywhere/service/InFlightStripTest.kt`

**Interfaces:**
- Consumes: `inFlightStripLabel` / `deltaOwnsPreviewStrip` (Task G2), `segmentQueueDepth` (Task F7),
  and the `renderInFlightStrip()` stub the funnel already calls (Task G3).
- Produces: `internal enum class StripVisibility { HIDDEN, OCCUPYING_BLANK, SHOWING }`, `internal fun inFlightStripVisibility(label: String?, currentlyHidden: Boolean): StripVisibility`, and a real `private fun renderInFlightStrip()`.

- [ ] **Step 1: Write the failing test** — append to `InFlightStripTest.kt` before the final `}`:

```kotlin

    // ------------------------------------------------------------- the anti-churn rule

    @Test fun the_first_line_of_a_session_reveals_the_strip() {
        assertEquals(
            StripVisibility.SHOWING,
            inFlightStripVisibility(label = "Transcribing…", currentlyHidden = true),
        )
    }

    @Test fun an_empty_queue_before_the_first_commit_leaves_the_strip_hidden() {
        // showSessionPreview() starts it GONE. Going GONE -> INVISIBLE would grow the window
        // for a line with nothing in it.
        assertEquals(
            StripVisibility.HIDDEN,
            inFlightStripVisibility(label = null, currentlyHidden = true),
        )
    }

    @Test fun an_emptied_queue_keeps_the_strip_occupying_its_space() {
        // THE anti-churn rule. Once the strip has been revealed it never returns to GONE for the
        // rest of the session: at utterance cadence the queue empties and refills every ~2.4 s,
        // and a VISIBLE<->GONE flap would re-measure the window and post a reclamp every single
        // utterance — the exact churn G exists to remove (previously one reclamp per 15 s cap).
        assertEquals(
            StripVisibility.OCCUPYING_BLANK,
            inFlightStripVisibility(label = null, currentlyHidden = false),
        )
    }

    @Test fun a_deepening_queue_repaints_without_a_geometry_change() {
        assertEquals(
            StripVisibility.SHOWING,
            inFlightStripVisibility(label = "Transcribing… (3 in queue)", currentlyHidden = false),
        )
    }
}
```

(again, drop the duplicated trailing brace)

- [ ] **Step 2: Run it, expected failure** —

`$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests "com.whispereverywhere.service.InFlightStripTest" --no-daemon`

Expected: `> Task :app:compileDebugUnitTestKotlin FAILED` with
`e: ...InFlightStripTest.kt:95:13 Unresolved reference: StripVisibility` and
`e: ...InFlightStripTest.kt:96:13 Unresolved reference: inFlightStripVisibility`

- [ ] **Step 3: Minimal implementation** — three edits in `FloatingBubbleService.kt`.

(a) after `commitAdvancesQueueDepth`, add:

```kotlin

/** What the preview strip should be doing for a given in-flight line (3.7, Workstream G). */
internal enum class StripVisibility { HIDDEN, OCCUPYING_BLANK, SHOWING }

/**
 * The anti-churn rule. Once the in-flight line has revealed the strip, an empty queue leaves it
 * OCCUPYING_BLANK (View.INVISIBLE) rather than hidden: at utterance cadence the queue empties and
 * refills roughly every 2.4 s, so a VISIBLE↔GONE flap would change the window's height — and
 * therefore post `reclampNow()` — on every single utterance. Under the 15 s wall cap that
 * happened once per cap window; the delta strip's own show/hide did it per segment boundary.
 * Revealing once per session and then only swapping text is what removes it.
 */
internal fun inFlightStripVisibility(label: String?, currentlyHidden: Boolean): StripVisibility =
    when {
        label != null -> StripVisibility.SHOWING
        currentlyHidden -> StripVisibility.HIDDEN
        else -> StripVisibility.OCCUPYING_BLANK
    }
```

(b) replace the Task G3 stub

```kotlin
    /** Painted in Task G4; the funnel calls it from the first commit onward. */
    private fun renderInFlightStrip() = Unit
```

with:

```kotlin
    /**
     * Paint the LOCAL in-flight line onto the preview strip (3.7, Workstream G). Main thread only.
     *
     * Phase ownership, exactly as CONNECTING and FINALIZING already practise it on this same
     * TextView: this paints only while RECORDING, so `connectingStatusLabel`'s "Loading speech
     * model…" and stopRecording's "Finishing transcript…" keep the strip in their own phases. A
     * live session never reaches the body — its deltas own the strip.
     *
     * The reveal is the session's ONE geometry change; from then on the line swaps text or goes
     * INVISIBLE, so nothing re-measures and no reclamp is posted per utterance.
     */
    private fun renderInFlightStrip() {
        if (currentState != BubbleState.RECORDING) return
        if (deltaOwnsPreviewStrip(sessionIsLive)) return
        val label = inFlightStripLabel(segmentQueueDepth.depth())
        val wasHidden = transcriptionDeltaText.visibility == View.GONE
        when (inFlightStripVisibility(label, wasHidden)) {
            StripVisibility.HIDDEN -> return
            StripVisibility.OCCUPYING_BLANK -> {
                transcriptionDeltaText.text = ""
                transcriptionDeltaText.visibility = View.INVISIBLE
            }
            StripVisibility.SHOWING -> {
                transcriptionDeltaText.text = label
                transcriptionDeltaText.visibility = View.VISIBLE
                // Posted ONLY on the reveal — the one time the window actually grew.
                if (wasHidden) bubbleView.post { reclampNow() }
            }
        }
    }
```

(c) `estimatedWindowSize`, line **1321** — an INVISIBLE strip still occupies its height, so the estimate must allow for it. Replace:

```kotlin
            if (transcriptionDeltaText.visibility == View.VISIBLE) (100 * density).toInt() else 0
```

with:

```kotlin
            // != GONE, not == VISIBLE: 3.7's in-flight line parks the strip at INVISIBLE between
            // utterances, which still occupies its height in the layout. Reading that as "no
            // strip" would UNDER-shoot the estimate — the unsafe direction for a clamp.
            if (transcriptionDeltaText.visibility != View.GONE) (100 * density).toInt() else 0
```

- [ ] **Step 4: Run tests green** —

`$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests "com.whispereverywhere.service.*" --no-daemon`

Expected: `BUILD SUCCESSFUL`; `TEST-com.whispereverywhere.service.InFlightStripTest.xml` shows 14 tests / 0 failures; `ResizeMathTest` still green.

- [ ] **Step 5: Commit** —

```
git add app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt app/src/test/java/com/whispereverywhere/service/InFlightStripTest.kt
git commit -m "feat(bubble): G — paint the in-flight line, reveal the strip once per session

inFlightStripVisibility pins the anti-churn rule: the strip is revealed once and
then only swaps text or parks at INVISIBLE, so utterance cadence no longer posts
a reclamp per commit. estimatedWindowSize now reads != GONE, since an INVISIBLE
strip still occupies its height.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT"
```

---

### Task G5: Re-route — local deltas off the strip, resolutions onto it

**Files:**
- Modify `app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt` — `resolvedTextClearsStrip` beside the other rules; `onDelta` at lines **2262–2297**; `onSegmentResolved` at lines **2298–2306**; `deliverReleasedText` at lines **2591–2595**.
- Modify `app/src/test/java/com/whispereverywhere/service/InFlightStripTest.kt`

**Interfaces:**
- Consumes: `deltaOwnsPreviewStrip` (Task G2), `segmentQueueDepth` (Task F7), `renderInFlightStrip`
  (Task G4), and the `onSegmentResolved` body as Tasks F7/F9 left it.
- Produces: `internal fun resolvedTextClearsStrip(sessionIsLive: Boolean, isFinalizing: Boolean): Boolean`.

- [ ] **Step 1: Write the failing test** — append to `InFlightStripTest.kt` before the final `}`:

```kotlin

    // ------------------------------------------------------------- resolution vs the strip

    @Test fun a_live_resolution_still_clears_the_words_it_was_streaming() {
        // Unchanged 3.6.0 behaviour: the resolved turn moves into the accumulating window, so the
        // strip must reset or the finished words linger UNDER the next utterance as it streams.
        assertTrue(resolvedTextClearsStrip(sessionIsLive = true, isFinalizing = false))
    }

    @Test fun a_local_resolution_repaints_the_in_flight_line_instead_of_clearing() {
        // The strip is not carrying this utterance's words any more — it is carrying the queue.
        // Clearing it here would blank the backlog signal on every single resolution.
        assertFalse(resolvedTextClearsStrip(sessionIsLive = false, isFinalizing = false))
    }

    @Test fun finalizing_owns_the_strip_in_every_session_kind() {
        // The stop tap writes "Finishing transcript…" / "Finishing… (waiting on provider)" and
        // THEN flushes the tail; nothing released during the drain may overwrite that line.
        assertFalse(resolvedTextClearsStrip(sessionIsLive = true, isFinalizing = true))
        assertFalse(resolvedTextClearsStrip(sessionIsLive = false, isFinalizing = true))
    }
}
```

- [ ] **Step 2: Run it, expected failure** —

`$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests "com.whispereverywhere.service.InFlightStripTest" --no-daemon`

Expected: `> Task :app:compileDebugUnitTestKotlin FAILED` with
`e: ...InFlightStripTest.kt:126:20 Unresolved reference: resolvedTextClearsStrip`

- [ ] **Step 3: Minimal implementation** — four edits in `FloatingBubbleService.kt`.

(a) after `inFlightStripVisibility`, add:

```kotlin

/**
 * Should a released segment's text CLEAR the preview strip (3.7, Workstream G)?
 *
 * Only when deltas own it: there the strip was carrying this very utterance's words and leaving
 * them would read as duplicated text under the next one. When the in-flight line owns it, the
 * caller repaints from the queue depth instead — clearing would blank the backlog signal on every
 * resolution. FINALIZING never clears in either case: the stop tap's status line owns the strip
 * for the whole drain (the pre-existing 3.6.0 D guard, preserved verbatim).
 */
internal fun resolvedTextClearsStrip(sessionIsLive: Boolean, isFinalizing: Boolean): Boolean =
    !isFinalizing && deltaOwnsPreviewStrip(sessionIsLive)
```

(b) `onDelta`, line **2262** — insert the gate as the first statement of the method, leaving the whole existing body byte-identical below it. Replace:

```kotlin
            override fun onDelta(text: String) {
                // Local partial streaming (3.6.0 D) joined cloud-live here. The unified preview
```

with:

```kotlin
            override fun onDelta(text: String) {
                // 3.7 G: LOCAL deltas no longer drive the strip — the commit/resolve in-flight
                // line does. The callback itself, DeltaThrottle and transcribeStreaming's JNI
                // plumbing are deliberately left running: CLOUD_LIVE still renders from here, and
                // the local stream stays available for the next surface that wants it.
                if (!deltaOwnsPreviewStrip(sessionIsLive)) return
                // Local partial streaming (3.6.0 D) joined cloud-live here. The unified preview
```

(c) `onSegmentResolved` — the coroutine body, in the shape Task F9 left it. The queue decrement and
its `queue:` line move AHEAD of delivery and gain the repaint; the perceived stamp stays exactly
where F9 put it, at `deliverReleasedText`'s return, because that is the instant the words are
visible. Replace:

```kotlin
                serviceScope.launch(Dispatchers.Main) {
                    // The Release is captured rather than inlined so the perceived stamp can be
                    // read at the moment the text ACTUALLY rendered: deliverReleasedText writes
                    // the view synchronously on Main and early-returns on blank, so "returned
                    // having delivered non-blank text" is exactly the visible instant. The
                    // SegmentOrderer's release rules are untouched — this only names its result.
                    val release = segmentOrderer.onResolved(seq, outcome)
                    deliverReleasedText(release.text)
                    val waited = perceivedLatency.onVisible(seq, System.currentTimeMillis())
                    // Always consume the stamp (it prunes), but only REPORT when text rendered:
                    // a segment that resolved to silence made nothing visible to time.
                    if (waited != null && release.text.isNotBlank()) {
                        android.util.Log.i("WE-DIAG", EndpointDiag.perceivedLine(seq, waited))
                    }
                    android.util.Log.i(
                        "WE-DIAG",
                        EndpointDiag.queueLine(segmentQueueDepth.onResolved(seq)),
                    )
                }
```

with:

```kotlin
                serviceScope.launch(Dispatchers.Main) {
                    // The Release is captured rather than inlined so the perceived stamp can be
                    // read at the moment the text ACTUALLY rendered: deliverReleasedText writes
                    // the view synchronously on Main and early-returns on blank, so "returned
                    // having delivered non-blank text" is exactly the visible instant. The
                    // SegmentOrderer's release rules are untouched — this only names its result.
                    val release = segmentOrderer.onResolved(seq, outcome)
                    // 3.7 G: the queue drops BEFORE delivery and independently of it — an
                    // EmptyExpected or a Lost segment resolves without releasing any text, and
                    // the backlog must still count down or the strip sticks at a phantom depth.
                    android.util.Log.i(
                        "WE-DIAG",
                        EndpointDiag.queueLine(segmentQueueDepth.onResolved(seq)),
                    )
                    renderInFlightStrip()
                    deliverReleasedText(release.text)
                    val waited = perceivedLatency.onVisible(seq, System.currentTimeMillis())
                    // Always consume the stamp (it prunes), but only REPORT when text rendered:
                    // a segment that resolved to silence made nothing visible to time.
                    if (waited != null && release.text.isNotBlank()) {
                        android.util.Log.i("WE-DIAG", EndpointDiag.perceivedLine(seq, waited))
                    }
                }
```

(d) `deliverReleasedText`, lines **2591–2595** — replace:

```kotlin
        if (currentState != BubbleState.FINALIZING) {
            transcriptionDeltaText.text = ""
            transcriptionDeltaText.scrollTo(0, 0)
            transcriptionDeltaText.visibility = View.GONE
        }
```

with:

```kotlin
        if (resolvedTextClearsStrip(sessionIsLive, currentState == BubbleState.FINALIZING)) {
            transcriptionDeltaText.text = ""
            transcriptionDeltaText.scrollTo(0, 0)
            transcriptionDeltaText.visibility = View.GONE
        } else if (currentState != BubbleState.FINALIZING) {
            // 3.7 G: the strip is carrying the in-flight line, whose truth is the queue depth —
            // repaint it, never hide it. This is also where the per-utterance scrollTo(0,0) goes
            // away: the line is one short string, so there is nothing to scroll.
            renderInFlightStrip()
        }
```

- [ ] **Step 4: Run tests green** —

`$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon`

Expected: `BUILD SUCCESSFUL`, full suite. Confirm from the XML directory that `InFlightStripTest` is 17/0 and that the live suites (`LiveServerDrivenTurnTest`, `LiveStopTailRescueTest`, `LiveTranscriptionEngineTest`), `LocalWhisperEngineStreamingTest` and `DeltaThrottleTest` are unchanged and green — D4's plumbing must still behave exactly as before.

- [ ] **Step 5: Commit** —

```
git add app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt app/src/test/java/com/whispereverywhere/service/InFlightStripTest.kt
git commit -m "feat(bubble): G — retire native deltas as the LOCAL strip driver

onDelta returns early for non-live sessions; resolutions decrement the queue and
repaint the in-flight line instead of hiding the strip. FINALIZING still owns the
strip through the drain. Cloud-live deltas, DeltaThrottle and the JNI new-segment
callback are untouched — only the local render moved to commit/resolve.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT"
```

## Workstream H — Tier retirement and the GPU row

---

### Task H1: Retire eco + base — split "not offered" from "not supported"

**Files:**
- Modify `app/src/main/java/com/whispereverywhere/model/WhisperModel.kt` — the `retired` KDoc + new `unsupported` field (lines 25–31); `entries` (lines 63–132); `DEFAULT_MODEL_ID` (line 142).
- Modify `app/src/main/java/com/whispereverywhere/model/ModelMigration.kt` — line 35 (the target constant) and line 44 (the gate). **Two surgical edits, no block replacement.**
- Modify `app/src/test/java/com/whispereverywhere/model/WhisperCatalogHelpersTest.kt` — lines 106–124.
- Modify `app/src/test/java/com/whispereverywhere/model/ModelMigrationTest.kt` — lines 15–18, 41–56, 63–77.

**Why the migration gate lands HERE and not in Task H2.** Retiring eco/base and flipping
`DEFAULT_MODEL_ID` to "pro" while `ModelMigration.decide` still reads `if (!selected.retired)` puts
two guaranteed failures on this task's commit: `decide("eco")` starts returning `OfferDownload`
instead of `None`, and `SwapAndDelete("extreme", "eco")` becomes `SwapAndDelete("extreme", "pro")`.
The gate flip and the target constant are therefore part of the same change as the retirement, not a
follow-up — Task H2 keeps the rename, the Settings wiring and the `targetIdFor` documentation.

**Interfaces:**
- Consumes: nothing.
- Produces: `WhisperModel.unsupported: Boolean` (default `false`); `WhisperCatalog.pickable == [pro, multi]`; `WhisperCatalog.DEFAULT_MODEL_ID == "pro"`; `ModelMigration.decide` gated on `unsupported`; `ModelMigration.MULTILINGUAL_TARGET_ID == "multi"` (private).

- [ ] **Step 1: Write the failing tests** — two files. First `WhisperCatalogHelpersTest.kt`, replace lines 106–124 (`retired_tiers_are_not_pickable` through `default_is_pickable`) with:

```kotlin
    @Test fun retired_tiers_are_not_pickable() {
        val ids = WhisperCatalog.pickable.map { it.id }
        assertFalse(ids.contains("extreme"))
        assertFalse(ids.contains("ultra"))
        // 3.7 Workstream H (owner decision 2026-08-20): the 60 MB tiers join them. "Pretty much
        // useless at this point… because of the accuracy."
        assertFalse(ids.contains("eco"))
        assertFalse(ids.contains("base"))
    }

    @Test fun pickable_is_exactly_pro_and_multi() {
        // The post-3.7 lineup: pro = the English flagship, multi = the international tier.
        assertEquals(listOf("pro", "multi"), WhisperCatalog.pickable.map { it.id })
    }

    @Test fun the_sixty_megabyte_tiers_stay_resolvable_after_retirement() {
        // Same rule that protects extreme/ultra: byId() must keep answering or every installed
        // eco/base user's installedModel() goes null and the app-wide gate force-marches them
        // into onboarding with their model file orphaned on disk.
        assertNotNull(WhisperCatalog.byId("eco"))
        assertNotNull(WhisperCatalog.byId("base"))
    }

    @Test fun retiring_a_tier_does_not_by_itself_declare_it_unsupported() {
        // THE 3.7 split. `retired` hides a tier from the chooser (fresh installs only);
        // `unsupported` is what drives Settings' migration card. eco/base are retired but
        // perfectly usable, so their installed users must see nothing at all — the spec's
        // "existing users unaffected; no re-download forced". extreme/ultra keep both flags.
        assertFalse(WhisperCatalog.byId("eco")!!.unsupported)
        assertFalse(WhisperCatalog.byId("base")!!.unsupported)
        assertTrue(WhisperCatalog.byId("extreme")!!.unsupported)
        assertTrue(WhisperCatalog.byId("ultra")!!.unsupported)
    }

    @Test fun every_unsupported_tier_is_also_retired() {
        // Offering a tier the app wants to migrate people OFF of would be incoherent.
        WhisperCatalog.entries.filter { it.unsupported }.forEach {
            assertTrue("'${it.id}' is unsupported but still offered", it.retired)
        }
    }

    @Test fun default_is_pro() {
        assertEquals("pro", WhisperCatalog.DEFAULT_MODEL_ID)
        assertNotNull(WhisperCatalog.byId(WhisperCatalog.DEFAULT_MODEL_ID))
    }

    @Test fun default_is_pickable() {
        // A retired default would be unreachable from the picker — an unshippable state.
        assertTrue(WhisperCatalog.pickable.any { it.id == WhisperCatalog.DEFAULT_MODEL_ID })
    }
```

Then `ModelMigrationTest.kt` — three replacements. **The line numbers are the file's ORIGINAL ones,
so apply them bottom-up** (63–77 first, then 41–56, then 15–18).

replace lines 15–18 (`a_current_tier_needs_no_migration`) with:

```kotlin
    @Test fun a_current_tier_needs_no_migration() {
        assertEquals(ModelMigration.Action.None, decide("pro"))
        assertEquals(ModelMigration.Action.None, decide("multi"))
    }

    @Test fun a_retired_but_supported_tier_is_left_completely_alone() {
        // 3.7 Workstream H: eco and base are retired (hidden from the chooser) but still work.
        // Raising the migration card for them would ask a user with a working 60 MB model to
        // download 190 MB they never asked for — and the card's own copy ("much faster") would
        // be false, since pro is slower than eco. This is the test that forces decide() to gate
        // on `unsupported` rather than `retired`, in the same task that retires them.
        assertEquals(ModelMigration.Action.None, decide("eco"))
        assertEquals(ModelMigration.Action.None, decide("base"))
        assertEquals(ModelMigration.Action.None, decide("eco", online = false))
        assertEquals(ModelMigration.Action.None, decide("base", targetInstalled = true))
    }
```

replace lines 41–56 (`swap_only_happens_once_the_target_is_actually_on_disk` and
`swap_happens_offline_too_once_the_target_is_installed`) with:

```kotlin
    @Test fun swap_only_happens_once_the_target_is_actually_on_disk() {
        // "ultra" is MULTILINGUAL, so its target is "multi", not the ENGLISH default "pro" —
        // see the MF3 tests below pinning the scope-aware mapping.
        assertEquals(
            ModelMigration.Action.SwapAndDelete("ultra", "multi"),
            decide("ultra", targetInstalled = true),
        )
    }

    @Test fun swap_happens_offline_too_once_the_target_is_installed() {
        // No network needed to swap a file that is already downloaded.
        assertEquals(
            ModelMigration.Action.SwapAndDelete("extreme", "pro"),
            decide("extreme", targetInstalled = true, online = false),
        )
    }
```

replace lines 63–77 (the MF3 comment and its two tests) with:

```kotlin
    // MF3: the target must match the retired model's language scope. "ultra" is MULTILINGUAL
    // (large-v3-turbo) — routing it to the ENGLISH-only default silently breaks dictation in every
    // other language. "extreme" is ENGLISH, so the English default is correct for it. Since 3.7 the
    // lineup is two tiers, so those targets are "multi" and "pro".
    // Replaces the old `migration_target_is_the_catalog_default`, which assumed every retired
    // tier maps to WhisperCatalog.DEFAULT_MODEL_ID regardless of scope — that assumption is the
    // bug MF3 fixes.
    @Test fun a_multilingual_unsupported_tier_migrates_to_multi_not_the_english_default() {
        val a = decide("ultra", targetInstalled = true) as ModelMigration.Action.SwapAndDelete
        assertEquals("multi", a.toId)
    }

    @Test fun an_english_unsupported_tier_migrates_to_pro() {
        val a = decide("extreme", targetInstalled = true) as ModelMigration.Action.SwapAndDelete
        assertEquals(WhisperCatalog.DEFAULT_MODEL_ID, a.toId)
        assertEquals("pro", a.toId)
    }
```

- [ ] **Step 2: Run it, expected failure** —

`$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests "com.whispereverywhere.model.*" --no-daemon`

Expected: `> Task :app:compileDebugUnitTestKotlin FAILED` with
`e: ...WhisperCatalogHelpersTest.kt:124:38 Unresolved reference: unsupported`

That compile failure is the whole red for Step 2 — the test source set does not build, so none of the
migration assertions run yet. They are the SECOND red, and they only become visible once Step 3(a)
has landed the `unsupported` field: at that point, with `retired` still on the gate and
`MULTILINGUAL_TARGET_ID` still `"base"`, three tests fail:
- `a_retired_but_supported_tier_is_left_completely_alone` → `expected:<None> but was:<OfferDownload>`
- `swap_only_happens_once_the_target_is_actually_on_disk` → `expected:<SwapAndDelete(fromId=ultra, toId=multi)> but was:<SwapAndDelete(fromId=ultra, toId=base)>`
- `a_multilingual_unsupported_tier_migrates_to_multi_not_the_english_default` → `expected:<multi> but was:<base>`

`swap_happens_offline_too_once_the_target_is_installed` and `an_english_unsupported_tier_migrates_to_pro`
are already green at this checkpoint — Step 3(d) flipped the ENGLISH target to `"pro"`. Step 3(e)'s
gate edit clears the first failure; 3(e)'s constant edit clears the other two. If you land 3(a)–(d)
and stop, that three-red set is exactly what you will see.

- [ ] **Step 3: Minimal implementation** — in `WhisperModel.kt`:

(a) replace the `retired` doc + field (lines 25–31) with:

```kotlin
    /**
     * A tier that is no longer OFFERED but must remain RESOLVABLE. Removing an entry outright
     * makes [WhisperCatalog.byId] return null for anyone who selected it, which makes
     * `installedModel()` return null, which trips the app-wide gate and force-marches that user
     * into onboarding — with their model file orphaned on disk. Retire; never delete.
     *
     * Retirement alone says nothing about the tier still working. It hides the card from fresh
     * installs and nothing more; see [unsupported] for the stronger claim.
     */
    val retired: Boolean = false,
    /**
     * A retired tier the app also wants users OFF of — the only thing that raises Settings'
     * "This model is no longer supported" migration card ([ModelMigration]). 3.7 splits this out
     * of [retired]: the 60 MB tiers are retired for accuracy (owner decision 2026-08-20) but keep
     * working perfectly for everyone who has one, so prompting them to swap a working 60 MB model
     * for a 190 MB download would be both unrequested and — since pro is SLOWER than eco — a
     * false claim in the card's own copy. extreme/ultra keep both flags: their targets really are
     * faster, so that card stays true.
     */
    val unsupported: Boolean = false,
```

(b) in `entries`, add `retired = true,` to the `eco` block (after `minRamBytes = 0L,`, line 74) and to the `base` block (after `minRamBytes = 0L,`, line 85), each preceded by the note:

```kotlin
            // 3.7 Workstream H: retired, NOT unsupported — installed users keep it, untouched.
            retired = true,
```

(c) in the `extreme` block, change line 108 `            retired = true,` to:

```kotlin
            retired = true,
            unsupported = true,
```

and identically in the `ultra` block (line 130).

(d) replace `DEFAULT_MODEL_ID` (lines 137–142) with:

```kotlin
    /**
     * Default tier on first run. **pro (small.en) since 2026-08-20 (3.7 Workstream H):** eco and
     * base are retired for accuracy, leaving pro as the English flagship and multi as the
     * international tier. The chooser steers by locale ([ModelTierCopy.steerIdForLanguageTag]);
     * this constant is the fallback for every path with no locale in hand — the auto-setup
     * re-entry in OnboardingSetupViewModel and ModelMigration's ENGLISH target.
     */
    const val DEFAULT_MODEL_ID = "pro"
```

(e) `ModelMigration.kt` — two surgical edits, the ones that keep this task's own suite green.

Line 35 — the multilingual target follows the new lineup:

```kotlin
    private const val MULTILINGUAL_TARGET_ID = "multi"
```

Line 44 — the gate reads the flag this task just split out. Replace
`        if (!selected.retired) return Action.None` with:

```kotlin
        // `unsupported`, not `retired` (3.7 Workstream H): a merely retired tier is hidden from
        // the chooser and otherwise left completely alone — its installed users are not prompted,
        // not migrated, and never asked to re-download.
        if (!selected.unsupported) return Action.None
```

*(the `targetIdFor` KDoc above still describes the old "base"/"eco" lineup and is rewritten in Task
H2, which owns the documentation and the Settings wiring. The behaviour is correct as of this task.)*

- [ ] **Step 4: Run tests green** —

`$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests "com.whispereverywhere.model.*" --no-daemon`

Expected: `BUILD SUCCESSFUL`; `TEST-com.whispereverywhere.model.WhisperCatalogHelpersTest.xml` shows 0 failures; `TEST-com.whispereverywhere.model.ModelMigrationTest.xml` shows **11 tests / 0 failures** (the ten it had plus `a_retired_but_supported_tier_is_left_completely_alone`); and `ModelTierCopyTest` is untouched and still green — it iterates `WhisperCatalog.pickable`, which merely got shorter, and eco/base still have copy until Task H3 removes it.

- [ ] **Step 5: Commit** —

```
git add app/src/main/java/com/whispereverywhere/model/WhisperModel.kt app/src/main/java/com/whispereverywhere/model/ModelMigration.kt app/src/test/java/com/whispereverywhere/model/WhisperCatalogHelpersTest.kt app/src/test/java/com/whispereverywhere/model/ModelMigrationTest.kt
git commit -m "feat(model): H — retire eco + base; split unsupported out of retired

Owner decision 2026-08-20. The 60 MB tiers leave the chooser and stay fully
resolvable; a new `unsupported` flag (extreme/ultra only) is what raises the
migration card, so installed eco/base users see nothing and are never asked to
re-download. Default becomes pro.

decide() moves onto the new flag in the same commit, because retiring a tier
while the gate still reads `retired` would prompt every installed eco/base user
to re-download - and the scope-aware targets move with the lineup (ENGLISH ->
pro, MULTILINGUAL -> multi).

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT"
```

---

### Task H2: Migration follows the split — the rename, the card wiring and the documentation

**Files:**
- Modify `app/src/main/java/com/whispereverywhere/model/ModelMigration.kt` — **lines 26–31 only** (the `targetIdFor` KDoc block; the constant on line 35 and the gate on line 44 landed in Task H1). The block replaced is the KDoc alone, so no brace moves and none is duplicated.
- Modify `app/src/main/java/com/whispereverywhere/model/WhisperModelManager.kt` — lines 53–60.
- Modify `app/src/main/java/com/whispereverywhere/ui/screens/SettingsScreen.kt` — lines 166–168, 211–215.
- Modify `app/src/test/java/com/whispereverywhere/model/ModelMigrationTest.kt` — append one test.

**What Task H1 already did.** The gate (`if (!selected.unsupported)`), the target constant
(`MULTILINGUAL_TARGET_ID = "multi"`) and every affected assertion in `ModelMigrationTest` moved into
H1, because retiring eco/base without them would have put two real failures on H1's commit. This task
is what is LEFT: the manager rename, the Settings card that reads it, the documentation that still
describes the old lineup, and one new pin.

**Interfaces:**
- Consumes: `WhisperModel.unsupported`, `WhisperCatalog.DEFAULT_MODEL_ID == "pro"`, `ModelMigration.decide` gated on `unsupported` (all Task H1).
- Produces: `WhisperModelManager.unsupportedInstalledModel(): WhisperModel?` (renamed from `retiredInstalledModel`). `ModelMigration.targetIdFor(ModelScope): String` already returns `"pro"` / `"multi"`; this task only documents it.

- [ ] **Step 1: Write the test** — in `ModelMigrationTest.kt`, append inside the class:

```kotlin
    @Test fun every_migration_target_is_a_tier_the_user_can_actually_pick() {
        // A target that is itself retired would move users from one dead end to another.
        listOf(ModelScope.ENGLISH, ModelScope.MULTILINGUAL).forEach { scope ->
            val target = ModelMigration.targetIdFor(scope)
            assertTrue(
                "target '$target' for $scope is not pickable",
                WhisperCatalog.pickable.any { it.id == target },
            )
            assertEquals(scope, WhisperCatalog.byId(target)!!.scope)
        }
    }
```

and add the imports `import org.junit.Assert.assertTrue` at the top of the file (after `assertEquals`).

- [ ] **Step 2: Run it** —

`$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests "com.whispereverywhere.model.ModelMigrationTest" --no-daemon`

Expected: **green**, and that is correct. `every_migration_target_is_a_tier_the_user_can_actually_pick`
is an ALREADY-GREEN guard over behaviour Task H1 landed (`targetIdFor(ENGLISH) == "pro"`,
`targetIdFor(MULTILINGUAL) == "multi"`, both pickable and both scope-matched) — the same shape as the
already-green guards in Tasks H5, G1 and F8. **This task's red already happened, in Task H1:** the
migration semantics were driven there by
`a_retired_but_supported_tier_is_left_completely_alone` and the two swap assertions, because retiring
eco/base and flipping the gate cannot be split across two commits without leaving one of them on a
failing suite. The pin is recorded here rather than dropped, so the coverage does not disappear with
the task that owns the rename.

The load-bearing verification for THIS task is therefore Step 4's `assembleDebug`: renaming
`retiredInstalledModel` without updating the `SettingsScreen` call site does not fail any unit test —
it fails the compile, and nothing else would catch it.

- [ ] **Step 3: Minimal implementation** —

(a) `ModelMigration.kt`, replace **lines 26–31** — the `targetIdFor` KDoc block, and only that. It
still describes the pre-3.7 lineup ("base" as the multilingual counterpart to the ENGLISH default
"eco") while the code beneath it, since Task H1, returns "multi" and "pro". The block starts at the
`/**` on line 26 and ends at the `*/` on line 31; the function signature on line 32 and everything
below it — including `MULTILINGUAL_TARGET_ID` and the whole of `decide` — is untouched, so no brace
is moved or duplicated:

```kotlin
    /**
     * The pickable tier an unsupported model's users should land on. MUST match the retired
     * model's [ModelScope] — moving a MULTILINGUAL user to the ENGLISH-only default silently
     * breaks dictation in every other language with no warning (that was the MF3 bug). Since 3.7
     * the lineup is two tiers: "pro" is the ENGLISH default and "multi" is its multilingual
     * counterpart.
     */
```

(b) `WhisperModelManager.kt`, replace lines 53–60 with:

```kotlin
    /**
     * The selected tier when it is UNSUPPORTED — still resolvable and possibly still installed,
     * but one the app wants users off of. Drives the migration prompt. Merely RETIRED tiers
     * (eco, base since 3.7) deliberately return null here: they are hidden from the chooser and
     * otherwise untouched. Returns null in the normal case.
     */
    fun unsupportedInstalledModel(): WhisperModel? {
        val model = WhisperCatalog.byId(prefs.selectedModelId) ?: return null
        return if (model.unsupported) model else null
    }
```

(c) `SettingsScreen.kt`, line **166–168** — replace:

```kotlin
    // Retired-tier migration (model catalog trim): non-null when the selected tier is retired.
```
…and line 168's `val retiredModel = remember(modelRefreshKey) { modelManager.retiredInstalledModel() }` with:

```kotlin
    // Unsupported-tier migration: non-null only for extreme/ultra. Merely retired tiers
    // (eco, base) never raise this card — see WhisperModel.unsupported.
    val retiredModel = remember(modelRefreshKey) { modelManager.unsupportedInstalledModel() }
```

(d) `SettingsScreen.kt`, lines **212–214** — replace the MF3 comment with:

```kotlin
                    // MF3: the target must match the retired model's scope — a multilingual
                    // user must land on "multi" (multilingual), not silently on the ENGLISH-only
                    // default. See ModelMigration.targetIdFor.
```

*(the card's body copy — "…is much faster and works well for everyday dictation" — is left byte-identical and stays TRUE: extreme→pro and ultra→multi are both genuinely faster. Zero copy change, so no pinned copy test moves.)*

- [ ] **Step 4: Run tests green** —

`$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests "com.whispereverywhere.model.*" --no-daemon`

then a compile check of the Compose call site:

`$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:assembleDebug --no-daemon`

Expected: `BUILD SUCCESSFUL` for both — the `assembleDebug` half is the one that matters, because it
is the only thing that proves the `SettingsScreen` call site followed the rename.
`TEST-com.whispereverywhere.model.ModelMigrationTest.xml` shows 12 tests / 0 failures (H1's 11 plus
this task's guard).

- [ ] **Step 5: Commit** —

```
git add app/src/main/java/com/whispereverywhere/model/ModelMigration.kt app/src/main/java/com/whispereverywhere/model/WhisperModelManager.kt app/src/main/java/com/whispereverywhere/ui/screens/SettingsScreen.kt app/src/test/java/com/whispereverywhere/model/ModelMigrationTest.kt
git commit -m "feat(model): H — the migration card reads `unsupported`, and the docs follow

decide() moved onto the unsupported flag in H1, with the retirement it belongs
to. This is the surface around it: retiredInstalledModel becomes
unsupportedInstalledModel, the Settings card reads the new name, targetIdFor's
doc stops describing the retired lineup, and a new test pins that every target
is itself pickable and scope-matched. The card's copy is unchanged and still
true for extreme/ultra.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT"
```

---

### Task H3: Tier copy — drop the retired cards, steer by locale

**Files:**
- Modify `app/src/main/java/com/whispereverywhere/model/ModelTierCopy.kt` — the class doc, `copyById` (lines 21–43), and new members after `forId`.
- Modify `app/src/test/java/com/whispereverywhere/model/ModelTierCopyTest.kt` — lines 73–**86** plus additions.

**Interfaces:**
- Consumes: `WhisperCatalog.pickable`, `WhisperModel.retired` (Task H1).
- Produces: `ModelTierCopy.steerIdForLanguageTag(languageTag: String): String` and `const val ModelTierCopy.STEER_BADGE: String = "Best match for your language"`.

- [ ] **Step 1: Write the failing test** — in `ModelTierCopyTest.kt`, replace lines **73–86** (`the_owner_approved_headlines_are_pinned_exactly` and `retired_and_unknown_tiers_have_no_copy`, **through the class-closing `}` on line 86**) with the block below. The replacement ends with its own class-closing `}`, so the range must include the old one — replacing 73–85 would leave a duplicate brace and a syntax error:

```kotlin
    @Test fun the_owner_approved_headlines_are_pinned_exactly() {
        assertEquals("Best English accuracy", ModelTierCopy.forId("pro")!!.headline)
        assertEquals("Best multilingual accuracy", ModelTierCopy.forId("multi")!!.headline)
    }

    @Test fun retired_and_unknown_tiers_have_no_copy() {
        // Retired tiers stay resolvable in WhisperCatalog but are not offered — no copy required.
        assertNull(ModelTierCopy.forId("extreme"))
        assertNull(ModelTierCopy.forId("ultra"))
        // 3.7 Workstream H: the 60 MB tiers joined them.
        assertNull(ModelTierCopy.forId("eco"))
        assertNull(ModelTierCopy.forId("base"))
        assertNull(ModelTierCopy.forId("nope"))
    }

    @Test fun no_offered_tier_names_a_retired_one() {
        // "Noticeably slower than Eco" was true and is now a dangling reference to a card the
        // user can no longer see. Copy may not describe a tier by comparison to a dead one.
        val retiredIds = WhisperCatalog.entries.filter { it.retired }.map { it.id.lowercase() }
        WhisperCatalog.pickable.forEach { model ->
            val copy = ModelTierCopy.forId(model.id)!!
            val all = (copy.headline + " " + copy.body + " " + copy.badges.joinToString(" ")).lowercase()
            retiredIds.forEach { r ->
                assertFalse("tier '${model.id}' copy names retired tier '$r'", all.contains(r))
            }
        }
    }

    @Test fun english_locales_are_steered_to_pro() {
        assertEquals("pro", ModelTierCopy.steerIdForLanguageTag("en"))
        assertEquals("pro", ModelTierCopy.steerIdForLanguageTag("en-US"))
        assertEquals("pro", ModelTierCopy.steerIdForLanguageTag("en_GB"))
        assertEquals("pro", ModelTierCopy.steerIdForLanguageTag("EN-au"))
    }

    @Test fun every_other_locale_is_steered_to_multi() {
        // The Bengali review is the reason this rule exists at all: an English-only tier must
        // never be the thing a non-English speaker lands on by default.
        assertEquals("multi", ModelTierCopy.steerIdForLanguageTag("bn"))
        assertEquals("multi", ModelTierCopy.steerIdForLanguageTag("bn-BD"))
        assertEquals("multi", ModelTierCopy.steerIdForLanguageTag("fr-CA"))
        assertEquals("multi", ModelTierCopy.steerIdForLanguageTag("zh-Hans-CN"))
        assertEquals("multi", ModelTierCopy.steerIdForLanguageTag(""))
    }

    @Test fun the_steer_always_lands_on_a_pickable_tier_of_the_right_scope() {
        val pickableIds = WhisperCatalog.pickable.map { it.id }
        listOf("en-US", "bn-BD", "de", "").forEach { tag ->
            val id = ModelTierCopy.steerIdForLanguageTag(tag)
            assertTrue("steer '$id' for '$tag' is not pickable", pickableIds.contains(id))
        }
        assertEquals(ModelScope.ENGLISH, WhisperCatalog.byId(ModelTierCopy.steerIdForLanguageTag("en"))!!.scope)
        assertEquals(ModelScope.MULTILINGUAL, WhisperCatalog.byId(ModelTierCopy.steerIdForLanguageTag("bn"))!!.scope)
    }

    @Test fun the_steer_badge_is_pinned_exactly_and_claims_nothing_about_speed() {
        assertEquals("Best match for your language", ModelTierCopy.STEER_BADGE)
        listOf("faster", "fastest", "quicker", "instant").forEach {
            assertFalse(ModelTierCopy.STEER_BADGE.lowercase().contains(it))
        }
    }
}
```

and add `import org.junit.Assert.assertFalse` to the imports.

- [ ] **Step 2: Run it, expected failure** —

`$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests "com.whispereverywhere.model.ModelTierCopyTest" --no-daemon`

Expected: `> Task :app:compileDebugUnitTestKotlin FAILED` with
`e: ...ModelTierCopyTest.kt:106:37 Unresolved reference: steerIdForLanguageTag` and
`e: ...ModelTierCopyTest.kt:137:37 Unresolved reference: STEER_BADGE`

- [ ] **Step 3: Minimal implementation** — in `ModelTierCopy.kt`:

(a) replace `copyById` (lines 21–43) with:

```kotlin
    private val copyById: Map<String, TierCopy> = mapOf(
        "pro" to TierCopy(
            headline = "Best English accuracy",
            badges = listOf("English only", "190 MB"),
            // 3.7 Workstream H: the old body read "Noticeably slower than Eco, noticeably
            // sharper" — a comparison to a tier the user can no longer see. pro is now the
            // English flagship, so the copy positions it directly.
            body = "The sharpest on-device English dictation this app ships.",
        ),
        "multi" to TierCopy(
            headline = "Best multilingual accuracy",
            badges = listOf("90+ languages", "190 MB"),
            body = "The pick for non-English dictation.",
        ),
    )
```

(b) after `forId` (line 46), add:

```kotlin

    /**
     * The tier a fresh install is steered to, from the device's primary language tag (3.7,
     * Workstream H). English-locale users get "pro" — the English flagship; everyone else gets
     * "multi", the international tier. It is a STEER, never a lock: both cards stay tappable and
     * [com.whispereverywhere.ui.onboarding.OnboardingLogic.TIER_SWITCH_HINT] still promises the
     * switch. Accepts either separator ("en-US", "en_GB") and any case, because callers pass
     * whatever `Locale.toLanguageTag()` / `Locale.getLanguage()` handed them.
     */
    fun steerIdForLanguageTag(languageTag: String): String =
        if (languageTag.substringBefore('-').substringBefore('_').lowercase() == "en") "pro"
        else "multi"

    /**
     * The chip marking the steered card. Names the REASON — "Default" alone never explained why
     * this card and not the other one, and for a non-English user the catalog default and the
     * right answer are different tiers.
     */
    const val STEER_BADGE = "Best match for your language"
```

(c) update the class doc's second paragraph: replace `Relative speed words BETWEEN on-device tiers ("Fastest", "slower") are factual and allowed;` with

```
 * Since 3.7 the lineup is two tiers, so the copy positions each one directly instead of by
 * comparison to a retired card ([ModelTierCopyTest.no_offered_tier_names_a_retired_one]);
```

- [ ] **Step 4: Run tests green** —

`$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests "com.whispereverywhere.model.*" --tests "com.whispereverywhere.ui.HowToGuideTest" --no-daemon`

Expected: `BUILD SUCCESSFUL`; `TEST-com.whispereverywhere.model.ModelTierCopyTest.xml` shows 12 tests / 0 failures. `HowToGuideTest` is included deliberately: it bans "fastest" app-wide and the retired eco headline was the app's only use of it — the guide's own copy is untouched by this task and must still pass unchanged.

- [ ] **Step 5: Commit** —

```
git add app/src/main/java/com/whispereverywhere/model/ModelTierCopy.kt app/src/test/java/com/whispereverywhere/model/ModelTierCopyTest.kt
git commit -m "feat(model): H — two-tier copy + the locale steer

eco/base cards go with their tiers; pro's body stops comparing itself to a card
nobody can see any more. steerIdForLanguageTag sends English locales to pro and
everything else to multi, pinned alongside a new rule that no offered tier's
copy may name a retired one.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT"
```

---

### Task H4: Both choosers render the steer

**Files:**
- Modify `app/src/main/java/com/whispereverywhere/model/ModelTierCopy.kt` — add `orderedForLanguageTag` after `steerIdForLanguageTag`.
- Modify `app/src/main/java/com/whispereverywhere/ui/screens/OnboardingFlowScreen.kt` — lines 399–407, 443–448, 482–485.
- Modify `app/src/main/java/com/whispereverywhere/ui/screens/OnboardingModelScreen.kt` — lines 43–45, 89–92, 227–229.
- Modify `app/src/test/java/com/whispereverywhere/model/ModelTierCopyTest.kt`

**Interfaces:**
- Consumes: `ModelTierCopy.steerIdForLanguageTag`, `ModelTierCopy.STEER_BADGE` (Task H3).
- Produces: `ModelTierCopy.orderedForLanguageTag(languageTag: String): List<String>`.

- [ ] **Step 1: Write the failing test** — append to `ModelTierCopyTest.kt`, inside the class,
immediately before the final `}`:

```kotlin

    @Test fun the_steered_tier_is_offered_first() {
        assertEquals(listOf("pro", "multi"), ModelTierCopy.orderedForLanguageTag("en-US"))
        assertEquals(listOf("multi", "pro"), ModelTierCopy.orderedForLanguageTag("bn-BD"))
    }

    @Test fun the_order_is_always_a_permutation_of_the_pickable_catalog() {
        // A future tier that nobody remembered to mention here must still reach the chooser —
        // dropping one silently would make it undownloadable.
        val pickable = WhisperCatalog.pickable.map { it.id }
        listOf("en", "bn", "de-AT", "").forEach { tag ->
            val ordered = ModelTierCopy.orderedForLanguageTag(tag)
            assertEquals("'$tag' lost or duplicated a tier", pickable.size, ordered.size)
            assertEquals("'$tag' is not a permutation", pickable.toSet(), ordered.toSet())
        }
    }

    @Test fun every_ordered_id_resolves_and_has_copy() {
        ModelTierCopy.orderedForLanguageTag("en").forEach {
            assertNotNull(WhisperCatalog.byId(it))
            assertNotNull(ModelTierCopy.forId(it))
        }
    }
```

(the appended block carries **no** trailing `}` — the file's existing class-closing brace is the one
that closes it, exactly as the G-section tasks spell out. Appending a `}` here would duplicate it.)

- [ ] **Step 2: Run it, expected failure** —

`$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests "com.whispereverywhere.model.ModelTierCopyTest" --no-daemon`

Expected: `> Task :app:compileDebugUnitTestKotlin FAILED` with
`e: ...ModelTierCopyTest.kt:150:44 Unresolved reference: orderedForLanguageTag`

- [ ] **Step 3: Minimal implementation** —

(a) `ModelTierCopy.kt`, after `steerIdForLanguageTag`, add:

```kotlin

    /**
     * Every offered tier id with the [steerIdForLanguageTag] one FIRST (3.7, Workstream H). Both
     * chooser surfaces render this list, so the steer is one rule rather than two. It is a
     * permutation of [WhisperCatalog.pickable] by construction — a tier this object has never
     * heard of still reaches the user, just not at the top.
     */
    fun orderedForLanguageTag(languageTag: String): List<String> {
        val steer = steerIdForLanguageTag(languageTag)
        val ids = WhisperCatalog.pickable.map { it.id }
        return ids.filter { it == steer } + ids.filter { it != steer }
    }
```

(b) `OnboardingFlowScreen.kt`, replace lines 399–407 with:

```kotlin
        // 3.7 Workstream H: the steered tier first — English locale -> pro, everything else ->
        // multi. A steer, not a lock: both cards stay tappable and TIER_SWITCH_HINT below still
        // promises the switch.
        val languageTag = java.util.Locale.getDefault().toLanguageTag()
        val steerId = ModelTierCopy.steerIdForLanguageTag(languageTag)
        ModelTierCopy.orderedForLanguageTag(languageTag).mapNotNull { WhisperCatalog.byId(it) }
            .forEach { model ->
                TierChoiceCard(
                    model = model,
                    copy = ModelTierCopy.forId(model.id),
                    steered = model.id == steerId,
                    selected = pickedTierId == model.id,
                    onClick = { onPick(model.id) },
                )
                Spacer(Modifier.height(12.dp))
            }
```

(c) `OnboardingFlowScreen.kt`, replace the `TierChoiceCard` signature (lines 441–448) with:

```kotlin
/** One selectable tier card rendering [ModelTierCopy] — the same copy Settings' picker shows. */
@Composable
private fun TierChoiceCard(
    model: WhisperModel,
    copy: ModelTierCopy.TierCopy?,
    steered: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
) {
```

(d) `OnboardingFlowScreen.kt`, replace lines 482–485 with:

```kotlin
            copy?.let { c ->
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val chips = if (steered) listOf(ModelTierCopy.STEER_BADGE) + c.badges else c.badges
                    chips.forEach { badge ->
```

(e) `OnboardingModelScreen.kt`, replace lines 43–45 with:

```kotlin
    // Only tiers still offered — retired tiers stay resolvable via WhisperCatalog.byId for
    // existing users but must not be selectable by anyone new. 3.7 Workstream H orders them by
    // locale: the steered tier is first and carries the badge.
    val languageTag = java.util.Locale.getDefault().toLanguageTag()
    val steerId = ModelTierCopy.steerIdForLanguageTag(languageTag)
    val models = ModelTierCopy.orderedForLanguageTag(languageTag)
        .mapNotNull { WhisperCatalog.byId(it) }
```

(f) `OnboardingModelScreen.kt`, replace line 91 (`                val isDefault = model.id == WhisperCatalog.DEFAULT_MODEL_ID`) with:

```kotlin
                // The highlighted card is the STEERED one, not the catalog default: for a
                // non-English user those are different tiers, and highlighting the English-only
                // default is exactly the mistake the Bengali review reported.
                val isDefault = model.id == steerId
```

(g) `OnboardingModelScreen.kt`, replace lines 227–229 with:

```kotlin
                if (isDefault) {
                    TierBadge(text = ModelTierCopy.STEER_BADGE, color = Primary)
                }
```

- [ ] **Step 4: Run tests green** —

`$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests "com.whispereverywhere.model.*" --tests "com.whispereverywhere.ui.onboarding.OnboardingLogicTest" --no-daemon`

then, since both edits are Compose-only:

`$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:assembleDebug --no-daemon`

Expected: `BUILD SUCCESSFUL` for both; `TEST-com.whispereverywhere.model.ModelTierCopyTest.xml` shows 15 tests / 0 failures.

- [ ] **Step 5: Commit** —

```
git add app/src/main/java/com/whispereverywhere/model/ModelTierCopy.kt app/src/main/java/com/whispereverywhere/ui/screens/OnboardingFlowScreen.kt app/src/main/java/com/whispereverywhere/ui/screens/OnboardingModelScreen.kt app/src/test/java/com/whispereverywhere/model/ModelTierCopyTest.kt
git commit -m "feat(onboarding): H — both choosers lead with the locale-steered tier

orderedForLanguageTag drives the guided flow and the Settings picker from one
rule, and the highlighted card follows the steer rather than the catalog default
— for a non-English user those are different tiers. 'Default' becomes 'Best match
for your language', which says why.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT"
```

---

### Task H5: Remove the GPU-experiment Settings row, keep the machinery inert

**Files:**
- Modify `app/src/main/java/com/whispereverywhere/ui/screens/SettingsScreen.kt` — line **147**, lines **592–604**.
- Create `app/src/test/java/com/whispereverywhere/ui/screens/GpuExperimentRowRetiredTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: nothing new. **Explicitly preserved:** `PreferencesManager.isGpuMultilingualExperimentEnabled(): Boolean`, `PreferencesManager.setGpuMultilingualExperiment(Boolean)`, `PreferencesManager.gpuMultilingualExperiment: StateFlow<Boolean>`, and `GpuPolicy.decideUseGpuForLoad`'s read of it at `GpuPolicy.kt:195-198` — all left in place, returning the stored value.

- [ ] **Step 1: Write the failing test** — create `app/src/test/java/com/whispereverywhere/ui/screens/GpuExperimentRowRetiredTest.kt`:

```kotlin
package com.whispereverywhere.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 3.7 Decision Gate 2 (owner call 2026-08-20): the "Try GPU for multilingual (experimental)"
 * Settings row is retired. The 2026-08-19 A/B closed the question — the multilingual GPU arm is
 * correct but ~9x slower, and the canary is corruption-only by design, so a PASS routes a
 * toggle-ON user straight to the slow backend.
 *
 * The deliverable is a Compose row that no longer exists, which has no runtime surface to assert
 * against — so this pins it at the source level, in both directions. The second half matters more
 * than the first: the row is gone, but the PREFERENCE must stay readable, or an existing `true`
 * on a shipped device becomes unreadable state and GpuPolicy's multilingual branch changes
 * meaning rather than merely losing its switch.
 */
class GpuExperimentRowRetiredTest {

    private fun source(relative: String): File {
        var dir = File(".").absoluteFile
        while (dir.parentFile != null &&
            !File(dir, "src/main/java/com/whispereverywhere").isDirectory
        ) {
            dir = dir.parentFile
        }
        val f = File(dir, "src/main/java/$relative")
        assertTrue("cannot locate $relative from ${File(".").absolutePath}", f.isFile)
        return f
    }

    @Test fun settings_no_longer_offers_the_multilingual_gpu_toggle() {
        val text = source("com/whispereverywhere/ui/screens/SettingsScreen.kt").readText()
        assertFalse(
            "the GPU experiment row is back in SettingsScreen",
            text.contains("Try GPU for multilingual"),
        )
        assertFalse(
            "SettingsScreen still writes the GPU experiment preference",
            text.contains("setGpuMultilingualExperiment"),
        )
        assertFalse(
            "SettingsScreen still observes the GPU experiment preference",
            text.contains("gpuMultilingualExperiment"),
        )
    }

    @Test fun the_preference_stays_readable_so_existing_true_values_still_mean_something() {
        val prefs = source("com/whispereverywhere/data/local/PreferencesManager.kt").readText()
        assertTrue(
            "the GPU experiment getter was deleted — an existing `true` is now unreadable",
            prefs.contains("fun isGpuMultilingualExperimentEnabled()"),
        )
        assertTrue(
            "the GPU experiment preference KEY was deleted",
            prefs.contains("KEY_GPU_MULTI_EXPERIMENT"),
        )
    }

    @Test fun gpu_policy_still_consults_the_stored_value() {
        val policy = source("com/whispereverywhere/transcription/GpuPolicy.kt").readText()
        assertTrue(
            "GpuPolicy no longer reads the experiment preference — the machinery is not inert, " +
                "it is changed",
            policy.contains("isGpuMultilingualExperimentEnabled()"),
        )
        assertTrue(
            "the canary gate that a stored `true` opens was removed",
            policy.contains("canaryVerdict("),
        )
    }
}
```

- [ ] **Step 2: Run it, expected failure** —

`$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests "com.whispereverywhere.ui.screens.GpuExperimentRowRetiredTest" --no-daemon`

Expected: `BUILD SUCCESSFUL` compile, test task FAILS. In
`TEST-com.whispereverywhere.ui.screens.GpuExperimentRowRetiredTest.xml`:
`settings_no_longer_offers_the_multilingual_gpu_toggle` →
`java.lang.AssertionError: the GPU experiment row is back in SettingsScreen`.
The other two tests pass already — they are the guard, not the change.

- [ ] **Step 3: Minimal implementation** — two deletions in `SettingsScreen.kt`, and nothing else anywhere.

(a) delete line **147**:

```kotlin
    val gpuMultiExperiment by app.preferencesManager.gpuMultilingualExperiment.collectAsState()
```

(b) delete lines **592–604** entirely (the comment block and the whole `SettingsSwitchItem`), so the `SettingsSection` closes straight after the Vibration Feedback row:

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

`PreferencesManager`, `GpuPolicy`, `GpuCanaryPolicy`, the canary asset and every latch are left **byte-identical**: the toggle can no longer be turned on, an already-stored `true` is still returned by `isGpuMultilingualExperimentEnabled()` and still consulted at `GpuPolicy.kt:195-198`, and the canary still gates it. If `Icons.Filled.Memory` becomes an unused import after the deletion, remove that import line too.

- [ ] **Step 4: Run tests green** —

`$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests "com.whispereverywhere.ui.screens.GpuExperimentRowRetiredTest" --tests "com.whispereverywhere.transcription.GpuCanaryPolicyTest" --tests "com.whispereverywhere.transcription.CanaryAudioTest" --no-daemon`

then:

`$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:assembleDebug --no-daemon`

Expected: `BUILD SUCCESSFUL` for both; `TEST-...GpuExperimentRowRetiredTest.xml` shows 3 tests / 0 failures and the two canary suites are unchanged and green.

- [ ] **Step 5: Commit** —

```
git add app/src/main/java/com/whispereverywhere/ui/screens/SettingsScreen.kt app/src/test/java/com/whispereverywhere/ui/screens/GpuExperimentRowRetiredTest.kt
git commit -m "feat(settings): H — retire the multilingual GPU experiment row

Decision Gate 2, owner call: the A/B closed the question (correct but ~9x slower,
and a corruption-only canary passes it straight through). The row goes; GpuPolicy,
the canary and every latch stay byte-identical and inert, and the preference key
stays readable so a stored `true` still means what it meant. Pinned in both
directions — the row must not come back, the preference must not be deleted.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT"
```

---

---

## Contract check — Workstreams G and H

| Untouchable | Where this section comes near it | Status |
|---|---|---|
| Wall caps in the `else if` at `FBS.kt:1695` | Task G3 adds a Main-hopped repaint inside the funnel Task F7 already routed that branch through | Branch structure, predicate and ordering unchanged; the funnel makes the same call the branch made before it, and `commitRetainingTailMs(0)` IS `commit()`. Byte-identical with a never-firing endpointer. |
| Cloud 4 s-cap suppression at `:2238` | not touched | unchanged |
| Unconditional stop flush at `:2388` | not touched by G (Task F7 routed it through the funnel) | still unconditional, still first in the flush block |
| `sendAudio` at `:1668` unconditional and first | not touched | unchanged |
| `no_context = true` final-only commit | not touched | unchanged |
| Live bypass (`RealtimeTurnPolicy`) | Task G5 reads `sessionIsLive` for RENDER ownership only | the VAD/commit bypass predicate is untouched |
| `EmptyExpected` / `FallbackPolicy.reconcile` | Task G5 decrements the queue on every resolution, including `EmptyExpected` and `Lost` | outcome semantics unread and unchanged; only the counter moves |
| `SegmentOrderer` release rules | Task G5 decrements after `onResolved(...)` returns its Release and before delivery; release rules and delivery order untouched | unchanged |
| Disclosure texts | not touched | unchanged |
| 3.5.0 `awaitIdle` skip + LocalRelay delivery fence | not touched | unchanged |
| `segment-timing` PREPEND-seq-only | not touched (Workstream F) | n/a |
| Batch `we_vad_filter` 0.40/150 ms | not touched | unchanged |
| `NativeComputeGate` wraps every whisper call | not touched | unchanged |
| Real background executors in concurrency tests | Task G1 | two real `Executors.newFixedThreadPool(2)` pools against the surviving seq-keyed counter, no same-thread stub |

## Workstream I — Ship mechanics + owner acceptance (S)

---

### Task S1: Release identity — versionCode 78 / versionName "3.7.0", pinned by a JVM test

**Files:**
- Create: `app/src/test/java/com/whispereverywhere/ReleaseIdentityTest.kt`
- Modify: `app/build.gradle.kts` (`:40-41`, the `versionCode`/`versionName` pair in `defaultConfig`)

**Interfaces:**
- Consumes: `com.whispereverywhere.BuildConfig.VERSION_CODE: Int` / `.VERSION_NAME: String` —
  generated for the app module (verified present in the debug variant's generated
  `BuildConfig.java`, currently `77` / `"3.6.0"`). `buildConfig = true` is already set
  (`app/build.gradle.kts:142`).
- Produces: **`versionCode = 78`, `versionName = "3.7.0"`** — the release identity Task S4's notes
  and Task S3's sheet both name, and the value `GpuPolicy` keys its canary latches on.

- [ ] **Step 1: Write the failing test.** Create
  `app/src/test/java/com/whispereverywhere/ReleaseIdentityTest.kt` (package `com.whispereverywhere`,
  matching the sibling `AudioMathTest.kt` in that directory):

```kotlin
package com.whispereverywhere

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The release identity, pinned. This is the one piece of ship mechanics that fails SILENTLY when
 * it is forgotten: a 3.7.0 build still carrying versionCode 77 is rejected only AFTER the upload,
 * and a 3.7.0 build still NAMED "3.6.0" ships release notes that the in-app About screen
 * contradicts. Both have a one-line fix and no other detector.
 *
 * It is also load-bearing beyond cosmetics. GpuPolicy keys its PERMANENT canary latches on
 * BuildConfig.VERSION_CODE (GpuPolicy.kt:101, 188, 261, 275, 284, 295), so moving 77 -> 78 clears
 * every recorded GPU verdict on every device — including the 3.6.0 "GPU-VERDICT: BAN
 * reason=slower" latch for multi. With the experimental multilingual-GPU toggle OFF (the shipped
 * default) nothing re-runs; with it ON, the canary runs once more on the first cold multi load.
 * The acceptance sheet says so where it matters.
 */
class ReleaseIdentityTest {

    @Test
    fun release_identity_is_3_7_0_at_version_code_78() {
        assertEquals(
            "versionName must be 3.7.0 for this release (app/build.gradle.kts defaultConfig)",
            "3.7.0",
            BuildConfig.VERSION_NAME,
        )
        assertEquals(
            "versionCode must be 78 for this release (app/build.gradle.kts defaultConfig)",
            78,
            BuildConfig.VERSION_CODE,
        )
    }
}
```

- [ ] **Step 2: Run it, watch it fail.**

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests "com.whispereverywhere.ReleaseIdentityTest" --no-daemon
```

Expected failure (the versionName assertion fires first):

```
com.whispereverywhere.ReleaseIdentityTest > release_identity_is_3_7_0_at_version_code_78 FAILED
    java.lang.AssertionError: versionName must be 3.7.0 for this release (app/build.gradle.kts defaultConfig) expected:<3.[7].0> but was:<3.[6].0>
```

- [ ] **Step 3: Minimal implementation — bump the version pair.** In `app/build.gradle.kts`,
  replace OLD (exact current lines `:40-41`):

```kotlin
        versionCode = 77
        versionName = "3.6.0"  // local speed: streaming deltas, first-segment cap, session language pin, drain floor
```

with NEW:

```kotlin
        versionCode = 78
        versionName = "3.7.0"  // VAD endpointing: Silero cuts at real pauses, per-tier commit cadence, eco/base retired
```

- [ ] **Step 4: Run tests green.**

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests "com.whispereverywhere.ReleaseIdentityTest" --no-daemon
```

Expected: `BUILD SUCCESSFUL`, no failed tests. (The version pair feeds `BuildConfig`, so the whole
unit variant recompiles — that is expected, not a signal.)

- [ ] **Step 5: Commit.**

```powershell
git add app/build.gradle.kts app/src/test/java/com/whispereverywhere/ReleaseIdentityTest.kt
git commit -m "chore(release): 3.7.0 / versionCode 78 - VAD endpointing release identity, pinned by test (I1)" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`nClaude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT"
```

---

### Task S2: `VadProbeBenchTest` — frame-by-frame Silero probs over the owner's clip — **INSTRUMENTED**

**Instrumented caveat (deliberate, precedented deviation from the red-green step form).** This test
cannot run on the JVM: it calls `libwhisper_jni.so`. The in-repo precedent is 3.6.0's G3/C8
(`docs/superpowers/plans/2026-08-19-local-speed.md`, Task G3: *"this test cannot run on the JVM.
Verification = both compile targets green + the owner on-device run recorded in the decision
file"*). The red state here is real and lives in Task S3's sheet: Check 1 reads
`RESULT: PENDING` until the owner's device run fills it. Nothing in this task fabricates a
JVM-green that would not mean anything.

**Files:**
- Create: `app/src/androidTest/java/com/whispereverywhere/whisper/VadProbeBenchTest.kt`

**Interfaces:**
- Consumes (Workstream A, exact signatures this file compiles against — **A must land first**):
  `WhisperNative.vadProbeInit(modelPath: String): Boolean` ·
  `WhisperNative.vadProbeFrame(pcm16: java.nio.ByteBuffer, nBytes: Int): Float` ·
  `WhisperNative.vadProbeReset()` · `WhisperNative.vadProbeFree()`
- Consumes (existing, verified in-tree): `VadModel.path(): String?`
  (`transcription/VadModel.kt:20`) · `CanaryAudio.dataChunk(bytes: ByteArray): ByteArray` and
  `CanaryAudio.formatIsValid(bytes: ByteArray): Boolean` (`transcription/CanaryAudio.kt:56,77` —
  both public, pure, JVM-pinned in `CanaryAudioTest`) · `AudioMath.amplitude(buffer, offset,
  length): Int` (`util/AudioMath.kt`).
- Produces: the greppable `WE-BENCH` line family Task S3's Check 1 reads —
  `BENCH vadprobe frame=` · `BENCH vadprobe summary` · `BENCH vadprobe crosstab` ·
  `BENCH vadprobe threshold=` · `BENCH vadprobe cost`.

- [ ] **Step 1: Write the test.** Create
  `app/src/androidTest/java/com/whispereverywhere/whisper/VadProbeBenchTest.kt`:

```kotlin
package com.whispereverywhere.whisper

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.whispereverywhere.transcription.CanaryAudio
import com.whispereverywhere.transcription.VadModel
import com.whispereverywhere.util.AudioMath
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/**
 * Workstream I acceptance instrument: replay ONE clip through the streaming Silero probe, one
 * 512-sample frame at a time, and print every prob beside the RMS the SHIPPED amplitude gate would
 * have seen for the same frame.
 *
 * Why it exists. Two premises 3.7 rests on are ESTIMATE, not MEASURED (investigation report §6
 * risk 8): that Silero is categorically more noise-robust than an RMS gate, and that the soft
 * talker in a noisy room — the owner's field report, the 251-499 RMS dead band documented at
 * SpeechSegmenter.kt:18-26 — is fixed by it. The vendored tree contains ZERO robustness evidence:
 * one README sentence and functional tests. One pass over the owner's own 8 s "zero dips below
 * -22 dB" clip settles both, and the crosstab line below is the settlement: every frame the
 * amplitude gate calls silence while Silero calls speech is a cut 3.6.0 could never make.
 *
 * It also carries the threshold A/B (spec Workstream I bullet 5) WITHOUT a state machine, and
 * deliberately so: replaying the recorded probs through SileroEndpointer would be validating the
 * tuning with the code the tuning is for. `framesAbove` answers "does this threshold see the
 * speech at all"; `longestGapMs` answers "would a hangover of X ms have cut inside the utterance"
 * — any longestGapMs >= a HANGOVER_MS candidate is a mid-utterance cut at that pair, and
 * no_context = true (whisper_jni.cpp params.no_context) makes such a cut unrepairable.
 *
 * RUN (owner device; NEVER :app:installDebug / :app:connectedDebugAndroidTest — both uninstall
 * first and wipe the downloaded models; adb is not on PATH):
 *
 *   $adb = "C:\Users\bastr\AppData\Local\Android\Sdk\platform-tools\adb.exe"
 *   $env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon
 *   & $adb install -r C:\Users\bastr\.androidbuild\WhisperEverywhere\app\outputs\apk\debug\app-debug.apk
 *   & $adb install -r C:\Users\bastr\.androidbuild\WhisperEverywhere\app\outputs\apk\androidTest\debug\app-debug-androidTest.apk
 *   & $adb push <your-clip.wav> /sdcard/Android/data/com.whispereverywhere/files/owner-vad-clip.wav
 *   & $adb logcat -c
 *   & $adb shell am instrument -w -e class com.whispereverywhere.whisper.VadProbeBenchTest com.whispereverywhere.test/androidx.test.runner.AndroidJUnitRunner
 *   & $adb logcat -d -s WE-BENCH | findstr "BENCH vadprobe"
 *
 * The clip is 16 kHz mono PCM16 (any other format is REJECTED, not silently resampled) and is
 * PUSHED, never bundled: it is personal audio, and this repo has a public remote.
 * getExternalFilesDir is the one path `adb push` can write and the app can read with no storage
 * permission on API 30+.
 *
 * Results go to logcat tag WE-BENCH. The owner records them in
 * docs/superpowers/specs/2026-08-20-i-owner-acceptance.md, Check 1.
 */
@RunWith(AndroidJUnit4::class)
class VadProbeBenchTest {

    private companion object {
        const val TAG = "WE-BENCH"

        /** One Silero window. The mic delivers exactly this per 32 ms AudioRecord callback. */
        const val FRAME_SAMPLES = 512

        /**
         * PCM16 mono: the ONLY nBytes vadProbeFrame accepts. Anything else returns the -1.0f
         * "no verdict" sentinel, because a short frame zero-padded still advances the LSTM by one
         * step and poisons the recurrence. This bench walks whole frames only and drops the
         * remainder, so noVerdict SHOULD read 0 — a nonzero count is itself a finding.
         */
        const val FRAME_BYTES = FRAME_SAMPLES * 2

        const val FRAME_MS = 32

        const val CLIP = "owner-vad-clip.wav"

        /**
         * Spec tuning table (docs/superpowers/specs/2026-08-20-vad-endpointing-design.md):
         * ONSET 0.50, RELEASE 0.35, plus the documented "widen to 0.30 if mid-word splits appear"
         * and the batch filter's own 0.40 for contrast. Copied verbatim, and deliberately NOT
         * imported from the production tuning object — this bench is what validates that object.
         */
        val THRESHOLD_CANDIDATES = listOf(0.50f, 0.40f, 0.35f, 0.30f)

        /**
         * `com.whispereverywhere.audio.EndpointerTuning.PROBE_BUDGET_MS` = 8 ms — the production
         * constant this number mirrors — expressed in the microseconds this bench measures in.
         * Copied, not imported, for the same reason as THRESHOLD_CANDIDATES above: this bench is
         * what validates that tuning object, so importing it would make the check circular. If the
         * production budget ever moves, this line moves with it and the sheet records both.
         */
        const val PROBE_BUDGET_US = 8000L

        /** SpeechSegmenter's shipped defaults (SpeechSegmenter.kt:17,27) — the gate 3.7 replaces. */
        const val AMP_VOICE = 500
        const val AMP_SILENCE = 250
    }

    @Test
    fun bench_vad_probe_frames() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext

        val clip = File(context.getExternalFilesDir(null), CLIP)
        // SKIP, not fail, when the clip is absent: it is an owner-supplied binary exactly like
        // canary_digits.wav was, and "the owner has not pushed it yet" must not read as a red
        // bench. Same assumeTrue idiom WhisperBenchTest uses for a missing model.
        assumeTrue(
            "push the clip first:  adb push <your.wav> " +
                "/sdcard/Android/data/com.whispereverywhere/files/$CLIP",
            clip.exists(),
        )
        val bytes = clip.readBytes()
        // The same format gate the production canary uses. A 44.1 kHz or stereo export would still
        // decode to "samples" and would still produce probs — plausible-looking numbers that would
        // then be recorded as the noise-robustness verdict. Reject instead.
        assertTrue(
            "$CLIP is not 16 kHz mono PCM16 — re-export it at 16 kHz mono before re-running",
            CanaryAudio.formatIsValid(bytes),
        )
        val pcm = CanaryAudio.dataChunk(bytes)
        assertTrue("$CLIP decoded to no PCM samples", pcm.isNotEmpty())

        val vadPath = VadModel.path()
        assertTrue("VadModel.path() is null — the bundled Silero model did not extract", vadPath != null)
        assertTrue("vadProbeInit failed for $vadPath", WhisperNative.vadProbeInit(vadPath!!))
        try {
            WhisperNative.vadProbeReset()
            // Reused DIRECT buffer, exactly as the production probe uses one: the native side reads
            // it through GetDirectBufferAddress, so a heap ByteBuffer would not work at all.
            // LITTLE_ENDIAN is documentation — put(ByteArray) is byte-order-independent, and the
            // WAV bytes are already the little-endian PCM16 the native int16 read expects.
            val buf = ByteBuffer.allocateDirect(FRAME_BYTES).order(ByteOrder.LITTLE_ENDIAN)

            val probs = ArrayList<Float>()
            val costsUs = ArrayList<Long>()
            var noVerdict = 0
            var ampSilentSileroSpeech = 0
            var ampVoiceSileroSilent = 0
            var ampDeadBand = 0
            var frame = 0
            var offset = 0
            while (offset + FRAME_BYTES <= pcm.size) {
                buf.clear()
                buf.put(pcm, offset, FRAME_BYTES)
                // NOT wrapped in NativeComputeGate, on purpose: the probe is outside the gate in
                // production (VAD is forced CPU-only at whisper.cpp:4671-4674, own backend, own
                // buffers), and wrapping it here would measure the lock, not the probe.
                val t0 = System.nanoTime()
                val p = WhisperNative.vadProbeFrame(buf, FRAME_BYTES)
                val us = (System.nanoTime() - t0) / 1000L
                val rms = AudioMath.amplitude(pcm, offset, FRAME_BYTES)
                // Locale.US on every number: these lines are read back off logcat and pasted into
                // the acceptance sheet, and a comma-decimal device locale would emit p=0,5123.
                android.util.Log.i(
                    TAG,
                    String.format(
                        Locale.US,
                        "BENCH vadprobe frame=%d ms=%d rms=%d p=%.4f us=%d",
                        frame, frame * FRAME_MS, rms, p, us,
                    ),
                )
                costsUs += us
                if (p < 0f) {
                    noVerdict++
                } else {
                    probs += p
                    if (rms <= AMP_SILENCE && p >= 0.50f) ampSilentSileroSpeech++
                    if (rms >= AMP_VOICE && p < 0.35f) ampVoiceSileroSilent++
                }
                if (rms > AMP_SILENCE && rms < AMP_VOICE) ampDeadBand++
                frame++
                offset += FRAME_BYTES
            }
            assertTrue("no whole 512-sample frames in $CLIP", probs.isNotEmpty())

            val sortedProbs = probs.map { it.toDouble() }.sorted()
            android.util.Log.i(
                TAG,
                String.format(
                    Locale.US,
                    "BENCH vadprobe summary frames=%d noVerdict=%d durationMs=%d " +
                        "pMin=%.4f p50=%.4f p95=%.4f pMax=%.4f",
                    frame, noVerdict, frame.toLong() * FRAME_MS,
                    sortedProbs.first(), percentile(sortedProbs, 0.50),
                    percentile(sortedProbs, 0.95), sortedProbs.last(),
                ),
            )

            // THE SETTLEMENT LINE. ampSilentSileroSpeech = frames the shipped amplitude gate calls
            // silence (<= 250 RMS) while Silero calls speech (>= 0.50): the soft-talker fix,
            // counted. ampVoiceSileroSilent is the opposite direction and should be near zero.
            // ampDeadBand counts frames in the 251-499 band that can open a segment but can never
            // close one — the documented mechanism behind "it just waits for the 15 s cap".
            android.util.Log.i(
                TAG,
                String.format(
                    Locale.US,
                    "BENCH vadprobe crosstab ampSilentSileroSpeech=%d ampVoiceSileroSilent=%d " +
                        "ampDeadBand=%d ampDeadBandPct=%.1f",
                    ampSilentSileroSpeech, ampVoiceSileroSilent, ampDeadBand,
                    100.0 * ampDeadBand / frame,
                ),
            )

            for (t in THRESHOLD_CANDIDATES) {
                var above = 0
                var gap = 0
                var longestGap = 0
                for (p in probs) {
                    if (p >= t) {
                        above++
                        gap = 0
                    } else {
                        gap++
                        if (gap > longestGap) longestGap = gap
                    }
                }
                android.util.Log.i(
                    TAG,
                    String.format(
                        Locale.US,
                        "BENCH vadprobe threshold=%.2f framesAbove=%d speechPct=%.1f longestGapMs=%d",
                        t, above, 100.0 * above / probs.size, longestGap.toLong() * FRAME_MS,
                    ),
                )
            }

            val sortedCosts = costsUs.map { it.toDouble() }.sorted()
            android.util.Log.i(
                TAG,
                String.format(
                    Locale.US,
                    "BENCH vadprobe cost frames=%d p50us=%.0f p99us=%.0f maxus=%.0f " +
                        "overBudget=%d budgetUs=%d",
                    sortedCosts.size, percentile(sortedCosts, 0.50), percentile(sortedCosts, 0.99),
                    sortedCosts.last(), costsUs.count { it > PROBE_BUDGET_US }, PROBE_BUDGET_US,
                ),
            )
        } finally {
            // The probe context is process-global and this instrument shares the app process with
            // the bubble service. Never leak it.
            WhisperNative.vadProbeFree()
        }
    }

    /** Linear-interpolated percentile over an already-sorted list. Mirrors WhisperBenchTest. */
    private fun percentile(sorted: List<Double>, p: Double): Double {
        if (sorted.isEmpty()) return 0.0
        if (sorted.size == 1) return sorted[0]
        val idx = p * (sorted.size - 1)
        val lo = idx.toInt()
        val hi = minOf(lo + 1, sorted.size - 1)
        return sorted[lo] + (sorted[hi] - sorted[lo]) * (idx - lo)
    }
}
```

- [ ] **Step 2: Compile both targets — the only green available here.**

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon
```

Expected: `BUILD SUCCESSFUL`. **If it fails with `Unresolved reference: vadProbeInit` (or
`vadProbeFrame` / `vadProbeReset` / `vadProbeFree`), Workstream A has not landed or its externs are
named differently — STOP and reconcile against A's `WhisperNative.kt` signatures rather than
editing this file to match a guess.**

- [ ] **Step 3: Confirm the JVM suite is untouched.** This file lives in `androidTest` and adds no
  production code, so the unit-test count must be exactly what it was before this task:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon
```

Expected: `BUILD SUCCESSFUL`, no failed tests.

- [ ] **Step 4: Commit.**

```powershell
git add app/src/androidTest/java/com/whispereverywhere/whisper/VadProbeBenchTest.kt
git commit -m "feat(bench): frame-by-frame Silero probs vs the amplitude gate over one pushed clip (I2)" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`nClaude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT"
```

---

### Task S3: The owner acceptance sheet

**Files:**
- Create: `docs/superpowers/specs/2026-08-20-i-owner-acceptance.md`

**Interfaces:**
- Consumes (Workstream F diagnostic tokens — this sheet greps them, so **a rename in F breaks this
  sheet and Step 2 is what catches it**): `endpoint: seq=` · `segment-timing: seq=` ·
  `queue: depth=` · `speechEndToVisible=` · `probe: frames=` + `overruns=` · the pre-existing
  `wall-clock cap -> commit (cap=…ms)` and `finalize-timing: local-drain`.
- Consumes (Task S2): the `BENCH vadprobe` line family.
- Produces: the sheet the controller hands the owner at merge, with `RESULT: PENDING` markers that
  only a device run fills, and the Certification block Task S5 writes into.

**Doc-task note:** a markdown deliverable has no unit test. Step 2 is nevertheless a real
executable gate — it proves every diagnostic token this sheet tells the owner to grep is actually
emitted by a source file on this branch, which is the classic way an acceptance sheet rots (the
owner greps for a line nobody implemented and reports a false failure).

- [ ] **Step 1: Write the sheet.** Create
  `docs/superpowers/specs/2026-08-20-i-owner-acceptance.md`:

```markdown
# 3.7.0 Workstream I — Owner on-device acceptance sheet

Owner-run. **NEVER `:app:installDebug` or `:app:connectedDebugAndroidTest`** — both uninstall
first and wipe the downloaded models. adb is not on PATH:
`$adb = "C:\Users\bastr\AppData\Local\Android\Sdk\platform-tools\adb.exe"`.

**Builds.** BEFORE = the installed **versionCode 77** (3.6.0 with G4's floor 512 — the build the
2026-08-20 measurement session characterised: pro-GPU F 0.77-1.0 s, multi-CPU F 2.3 s).
AFTER = the 3.7.0 branch build, **versionCode 78**. Archive the before-build first so it stays
reinstallable:

    $adb = "C:\Users\bastr\AppData\Local\Android\Sdk\platform-tools\adb.exe"
    & $adb shell pm path com.whispereverywhere     # note the /data/app/.../base.apk path
    & $adb pull <that path> C:\Users\bastr\.androidbuild\WhisperEverywhere\whisper-3.6.0-BEFORE-370.apk

Run the whole BEFORE column on the installed 77 FIRST. Then build and install the after-build
(data-preserving — debug over debug updates in place):

    $env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon
    & $adb install -r C:\Users\bastr\.androidbuild\WhisperEverywhere\app\outputs\apk\debug\app-debug.apk

**Read this before anything else: versionCode 78 CLEARS every GPU canary latch.** `GpuPolicy` keys
its permanent verdicts on `BuildConfig.VERSION_CODE`, so the 3.6.0 `GPU-VERDICT: BAN
reason=slower` latch for multi does not carry into 78. With the experimental multilingual-GPU
toggle OFF — the shipped default, and the state the owner left the device in on 2026-08-20 —
nothing re-runs and multi stays on CPU. If the toggle is ON, the canary runs once more on the
first cold multi load and can re-latch ALLOW (it is a corruption screen, not a speed test, and
"correct but 9x slower" passes it). **Leave the toggle off for this grid**, or every multi number
below measures a backend the release does not ship.

Hygiene, unchanged from the 3.6.0 sheet: `& $adb logcat -c` between sessions; no batch
file-transcription job running (the compute gate is shared and inflates transcribe-ms); cloud OFF
for the local rows (rescue segments stream into the same log unlabelled).

The 3.7 diagnostic family — one grep gets the whole story of a session:

    & $adb logcat -d -s WE-DIAG | findstr /R "endpoint: segment-timing: queue: perceived: probe:"

---

## Check 1 — Frame-by-frame Silero probs over the owner's clip (the ESTIMATE-to-MEASURED pass)

RESULT: PENDING

This is the one check that converts a premise rather than measuring a latency. Two things ride on
it: that Silero is categorically more noise-robust than an RMS gate (the vendored tree contains
zero robustness evidence — one README sentence), and that the soft talker in a noisy room is
fixed. Use the owner's own 8 s "zero dips below -22 dB" clip, 16 kHz mono PCM16.

    & $adb push <clip.wav> /sdcard/Android/data/com.whispereverywhere/files/owner-vad-clip.wav
    & $adb install -r C:\Users\bastr\.androidbuild\WhisperEverywhere\app\outputs\apk\androidTest\debug\app-debug-androidTest.apk
    & $adb logcat -c
    & $adb shell am instrument -w -e class com.whispereverywhere.whisper.VadProbeBenchTest com.whispereverywhere.test/androidx.test.runner.AndroidJUnitRunner
    & $adb logcat -d -s WE-BENCH | findstr "BENCH vadprobe"

Record the four summary lines verbatim (the ~250 `frame=` lines go in the paste block below them):

- [ ] `BENCH vadprobe summary` — `noVerdict` MUST be 0. Nonzero means frames reached the probe at
      a size other than 1024 bytes, which cannot happen from this bench and would mean the frame
      contract itself is wrong.
- [ ] `BENCH vadprobe crosstab` — **the settlement.** `ampSilentSileroSpeech` is the count of
      frames the shipped amplitude gate calls silence (<= 250 RMS) while Silero calls speech
      (>= 0.50). A materially nonzero count on a clip that never dips below -22 dB is the
      soft-talker fix, MEASURED. `ampDeadBandPct` is the fraction of the clip sitting in the
      251-499 band that can open a segment but never close one — the mechanism behind "it just
      waits for the wall cap". `ampVoiceSileroSilent` should be near zero; a large value is a
      finding worth reporting, not a pass.
- [ ] `BENCH vadprobe threshold=` (four lines) — `speechPct` at 0.50 should track how much of the
      clip is actually speech. **`longestGapMs` is the hangover check:** any value >= a
      `HANGOVER_MS` candidate (350 / 500 / 800) means that threshold+hangover pair would have cut
      INSIDE the utterance, and `no_context = true` makes such a cut unrepairable. If 0.50 shows a
      long gap that 0.35 does not, that is the spec's documented "widen RELEASE to 0.30" signal.
- [ ] `BENCH vadprobe cost` — `overBudget` MUST be 0 against the 8000 us budget. `p50us`/`p99us`
      are the first measured probe cost in this repo (the estimate was 200-1500 us).

If the clip is unavailable the run SKIPS rather than fails (`assumeTrue`) — a skip is not a pass.

    (paste the BENCH vadprobe lines here)

## Check 2 — Per-tier perceived latency: `speechEndToVisible` p50/p95

RESULT: PENDING

The headline. **Variance is the story, not the mean:** on 77 the later-segment wait is uniformly
distributed over 0-15 s depending on where in the cap window you stopped talking; on 78 it should
be near-constant. A tight p95 is the result even if p50 barely moves.

Grid: 2 tiers (pro, multi) x local mode x BEFORE/AFTER. Per cell, dictate **6-8 short sentences
with real pauses between them** (this is the opposite of the 3.6.0 sheet's continuous-speech
protocol — that one exercised the wall cap; this one exercises the endpointer). Then:

    $v = @(& $adb logcat -d -s WE-DIAG | Select-String 'speechEndToVisible=(\d+)ms' | ForEach-Object { [int]$_.Matches[0].Groups[1].Value } | Sort-Object)
    "n=$($v.Count) p50=$($v[[int][math]::Floor(0.50*($v.Count-1))]) p95=$($v[[int][math]::Floor(0.95*($v.Count-1))]) max=$($v[-1])"

- [ ] **pro, AFTER:** target p50 ~1.3-1.8 s and a p95 close to it (hangover 500 ms + ~0.8-1.3 s
      inference at the measured GPU F). A p95 far above p50 means cuts are still landing on the
      cap, not the endpointer — cross-check `endpoint: cut=` below.
- [ ] **multi, AFTER:** target ~2.8 s at the paced boundary (`MIN_COMMIT_INTERVAL_MS = 6000`), and
      **no 15 s outliers at all**. Multi is paced by arithmetic, not preference: F=2.3 s, m~0.45,
      `F*N + m*S <= 0.70*60 s` gives <= ~10.7 commits/min.
- [ ] **BEFORE column, both tiers:** record the real numbers. The release notes' "text lands
      sooner, and at a steady pace" survives only if this column backs it (Task S4 contingency).
- [ ] **`endpoint:` cut mix.** `& $adb logcat -d -s WE-DIAG | findstr "endpoint:"` — on 78 the
      overwhelming majority must read `cut=vad`. **`cut=cap` is now a VAD-FAILURE SIGNATURE, not
      the normal path** — a handful is fine (humming, continuous media); a majority means the
      endpointer is not firing and the session degraded to 3.6.0 behaviour. Report the mix.
- [ ] **`ctxFrames=` on `segment-timing:`.** At utterance cadence most commits should show a small
      encoder context, not the floor on every call. This is the cost driver that decides whether
      multi's pacing can ever be relaxed.

## Check 3 — Stop on an idle queue is near-instant

RESULT: PENDING

Under 3.7 the last utterance was already cut at its own endpoint, so the stop flush hands whisper
**pure trailing silence**: `we_vad_filter` finds no speech and the native side returns BEFORE
`whisper_full`. Cost should be tens of ms, against ~1.3 s (base) to ~4.5 s (multi) on 77.

- [ ] Local session, **stop during a real pause** (not mid-word), queue visibly idle: the
      `finalize-timing` total is ~tens of ms and `queue: depth=0` immediately before the stop.
- [ ] Local session, **stop mid-utterance**: the tail is real audio and costs one inference. This
      is expected and is NOT a regression — it is the same work 77 did, just less of it.
- [ ] **Under load, `finalize-timing: local-drain` is the honest number now.** On 77 it was
      near-zero on the batch happy path; at 6x the commit rate a real backlog can exist at stop,
      and the drain reserve is a BOUND, not a cost. A nonzero local-drain next to a nonzero
      `queue: depth=` before the stop is correct behaviour, not a timeout.

## Check 4 — Cloud batch: request count is bounded, and `cap=4000ms` is still absent

RESULT: PENDING

Every cloud-batch commit is one HTTP POST. `MIN_COMMIT_INTERVAL_MS = 3000` bounds it at <= 20
requests/minute of session; `Semaphore(3)` in flight sheds at 24.

- [ ] One ~2-minute cloud-batch session of normal paced speech. Count the commits:
      `& $adb logcat -d -s WE-DIAG | findstr "endpoint:" | measure` — the count divided by session
      minutes must be **<= 20/min**. Report the actual rate.
- [ ] **`cap=4000ms` must NEVER appear in a cloud session** (3.6.0 A2 regression signature, still
      valid). Run one cloud-batch session with a deliberately SILENT opening stretch (~20 s) and
      confirm the first `wall-clock cap -> commit` line reads `(cap=15000ms)`:
      `& $adb logcat -d -s WE-DIAG | findstr "wall-clock cap"`.
- [ ] **Cloud LIVE is untouched.** One cloud-live session: the server cuts turns, the endpointer
      never runs, and `endpoint:` lines must be ABSENT for the whole session.

## Check 5 — Threshold / hangover A/B

RESULT: PENDING

The vendored Silero reflect-pads instead of using `n_context`, so published thresholds do not
transfer verbatim — budget for tuning. Check 1's `longestGapMs` table does the offline half over
the owner's clip; this is the on-device half, and it is only needed if Check 1 or Check 2 shows a
problem.

- [ ] **Only if mid-clause splits are audible in the transcripts:** widen `RELEASE_THRESHOLD`
      0.35 -> 0.30 in the tuning object, rebuild, re-run Check 2's pro cell, compare p50/p95.
- [ ] **Only if the wait feels long on pro:** try `HANGOVER_MS` 500 -> 350; re-run Check 2 AND
      re-read the transcripts. The trade is lopsided on purpose — 350 saves ~150 ms on a ~1.3 s
      inference and risks an unrepairable mid-clause boundary.
- [ ] Record every value tried and its p50/p95. A changed default lands as its own commit with
      this sheet's numbers quoted in the message.

## Check 6 — Probe overruns = 0 on the Fold6

RESULT: PENDING

- [ ] `& $adb logcat -d -s WE-DIAG | findstr "probe:"` after each session in Check 2. Every line
      must read `overruns=0`. The ring gives >= 128 ms of slack (>= 4 frames) against an expected
      0.2-1.5 ms probe, so a nonzero count means something else is blocking the capture thread.
- [ ] Compare `p50=`/`p99=` here against Check 1's `BENCH vadprobe cost` numbers. A large gap
      between the bench (idle device) and production (mic open, inference running) is the real
      contention signal and is what would justify promoting the probe to its own thread — a
      measured decision, explicitly not a default.
- [ ] **The latched cutout must not have fired.** If a session silently reverted to amplitude
      behaviour, `endpoint: cut=` goes all-`cap` and the cutout logs once. Report it if seen.

## Check 7 — Fallback is byte-identical to 3.6.0

RESULT: PENDING

- [ ] With the probe forced unavailable (see the fallback tier in Workstream D — model missing or
      init failure), a full local session must behave exactly as 77 did: `cap=4000ms` first cut,
      `cap=15000ms` later cuts, `endpoint: cut=cap`, transcripts delivered once at stop. This is
      the structural guarantee that every pathological case degrades to today's behaviour.

## Regression spot-checks

- [ ] Dictation into WhatsApp delivers ONCE at stop (final-only commit intact); Transcriptions
      history records the session; the resize handle works; the how-to guide reads unchanged.
- [ ] **Tier retirement (Workstream H):** eco/base are gone from the chooser, an already-installed
      eco/base still resolves and transcribes, and nothing forces a re-download.
- [ ] Device-audio (`switchSource`) mid-session: swap mic <-> playback and confirm the next
      `endpoint:` line reads sensibly. Carrying LSTM state across an acoustic-source change is a
      correctness bug; this is the check for it.

## Certification (filled by the branch-certification task, not by the owner)

    suites=      tests=      failures=      errors=      skipped=
    assembleDebug:             PENDING
    assembleDebugAndroidTest:  PENDING
    untouchable contracts:     PENDING

## Report

Report every `RESULT:` line filled, both columns of Check 2's grid, and the Check 1 paste. **A
missed target is a decision-gate finding, not a silent pass** — specifically, if Check 2's AFTER
numbers do not beat the BEFORE column, Task S4's release-notes contingency fires BEFORE Play
submission.
```

- [ ] **Step 2: Run the token-existence gate — every diagnostic this sheet greps must be emitted
  by a source file on this branch.**

```powershell
$src = 'app\src\main\java\com\whispereverywhere'
@('endpoint: seq=','segment-timing: seq=','queue: depth=','speechEndToVisible=','probe: frames=','overruns=','wall-clock cap -> commit','finalize-timing: local-drain') | ForEach-Object {
  $c = @(Get-ChildItem $src -Recurse -Filter *.kt | Select-String -SimpleMatch -Pattern $_).Count
  "{0,-28} {1}" -f $_, $c
}
```

Expected: **every count >= 1.** A `0` means Workstream F renamed or dropped that token — fix the
SHEET to match F (F's format is the contract), never the other way round, and re-run. `probe:
frames=` and `overruns=` may legitimately live on the same emitting line.

The tokens carry their `seq=` / `frames=` suffix on purpose: the bare prefixes match unrelated
source text and would pass vacuously. Verified while drafting — bare `endpoint:` already returns 1
hit today from `RealtimeProtocol.kt:40` (`val endpoint: String`), which is a cloud URL field and
has nothing to do with Workstream F. A gate that cannot fail is not a gate.

- [ ] **Step 3: Confirm the sheet's own build commands are the current ones.**

```powershell
Select-String -Path docs\superpowers\specs\2026-08-20-i-owner-acceptance.md -SimpleMatch -Pattern 'installDebug','connectedDebugAndroidTest','install -r','Android Studio1'
```

Expected: the `installDebug` / `connectedDebugAndroidTest` hits appear ONLY inside the "NEVER"
sentence at the top; `install -r` and `Android Studio1` each appear in the build block. If
`install -r` is missing, the sheet would send the owner to a models-wiping command.

- [ ] **Step 4: Verify nothing else changed.** This task adds one doc and no code:

```powershell
git status --porcelain
```

Expected exactly: `?? docs/superpowers/specs/2026-08-20-i-owner-acceptance.md`

- [ ] **Step 5: Commit.**

```powershell
git add docs/superpowers/specs/2026-08-20-i-owner-acceptance.md
git commit -m "docs(acceptance): 3.7.0 owner sheet - probs-over-the-owner-clip, per-tier perceived p50/p95, cloud request bound (I3)" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`nClaude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT"
```

---

### Task S4: Release notes 3.7.0 + the Play listing delta

**Files:**
- Modify: `docs/PLAY-LISTING.md` (append two sections at the end of the file; the FINAL 3.3.0 block
  at `:112-162` is left byte-identical — it is the record of what is live in the Console today)

**Interfaces:**
- Consumes: Task S1's `versionName = "3.7.0"` / `versionCode = 78`; Task S3's Check 2 grid (the
  contingency that decides whether the latency sentence survives).
- Produces: the 3.7.0 release-notes text (483 chars) and the exact OLD->NEW bullet swap the owner
  applies in the Console **in the SAME release as the 78 AAB** — this file and the Console are two
  copies of the same copy, which is the rule the file itself states at `:184-187`.

**Claim rules (binding, from the spec's Constraints):** our-own-before/after only, no absolutes, no
cloud speed claims. Every comparative below is against our own previous version on the same phone,
and the notes say so outright in the closing sentence.

- [ ] **Step 1: Append the release notes.** In `docs/PLAY-LISTING.md`, anchor on OLD (the current
  final two lines of the file):

```markdown
> Our biggest on-device speed release. Words now appear in the bubble while the model is still transcribing — no more silent wait. The first line of a session lands sooner, multilingual mode no longer re-detects your language every segment, and switching models warms the new one in the background. Stopping now counts up while it finishes. Every claim is against our own previous version, measured on the same phone.

(415 chars)
```

NEW (the OLD block, then):

```markdown
> Our biggest on-device speed release. Words now appear in the bubble while the model is still transcribing — no more silent wait. The first line of a session lands sooner, multilingual mode no longer re-detects your language every segment, and switching models warms the new one in the background. Stopping now counts up while it finishes. Every claim is against our own previous version, measured on the same phone.

(415 chars)

## Release notes — 3.7.0 (within 500 chars)

> Dictation now cuts where you stop talking. A real voice-activity model listens frame by frame, so each sentence goes to be transcribed the moment you finish it instead of waiting on a timer — text lands sooner, and at a steady pace. Stopping is quicker when nothing is still queued. Multilingual paces itself to what your phone keeps up with. The two lightest model tiers are retired; models you already installed are untouched. Measured against our own previous version, same phone.

(**483 chars** — re-verify in Step 2. Claim discipline, unchanged: no cloud speed claims, no
absolutes, every comparative our-own-before/after and said outright in the closing sentence.

**What each sentence is allowed to claim.** "Cuts where you stop talking" is the feature, not a
speed claim. "Text lands sooner, and at a steady pace" is the Workstream I Check 2 result — and
STEADY is the honest headline: on 3.6.0 the later-segment wait was uniformly spread over 0-15 s
depending on where in the cap window you stopped, and on 3.7.0 it is near-constant. "Stopping is
quicker when nothing is still queued" carries its own condition on purpose: the win is that the
tail is pure silence and returns before the encoder runs, and it does NOT apply when a real
backlog exists. "Multilingual paces itself to what your phone keeps up with" describes the
per-tier commit interval honestly and promises no number — multilingual is measurably the slower
tier and the notes must not imply otherwise.

**Deliberately absent:** the GPU experiment (ships off; the 3.6.0 measurement banned it for
multilingual), any request-count or billing statement about cloud, and any comparison to any other
app.

CONTINGENCY — resolve BEFORE Play submission, against
docs/superpowers/specs/2026-08-20-i-owner-acceptance.md Check 2:
- If the AFTER p50/p95 do not beat the BEFORE column, delete ", and at a steady pace" and
  "text lands sooner, " — keeping only the mechanism sentence — and re-verify the count.
- If Check 3 shows no idle-stop win, delete "Stopping is quicker when nothing is still queued. "
- If Workstream H (tier retirement) is cut, delete "The two lightest model tiers are retired;
  models you already installed are untouched. "
Any edit re-runs Step 2's count.)

## Listing delta — 3.7.0 (owner applies in the Console, SAME release as the 78 AAB)

The FINAL 3.3.0 full description above is what is LIVE and stays as the record of it. Two bullets
in the `⚡ Built for speed` block go stale in 3.7.0 — the tier list (eco/base retired, Workstream H)
and the VAD bullet (amplitude gate replaced by real endpointing). Replace exactly these two lines,
nothing else:

OLD:
• Model tiers from light-and-quick to maximum accuracy, including multilingual
• Voice activity detection: silence costs nothing

NEW:
• Model tiers from fast-and-English to 90+ languages to maximum accuracy
• Real voice-activity detection: your sentence is transcribed when you stop talking, and silence costs nothing

Arithmetic: 127 chars out, 182 in, **+55 -> 3,962 of the 4,000-char limit** (38 to spare — any
further listing edit this release must re-count first).

Untouched on purpose: the GPU bullet ("GPU-accelerated Whisper on Snapdragon (Adreno)") stays
true — the 2026-08-20 bench VALIDATED the GPU default for the English tier while banning it for
multilingual, and the bullet claims neither tier. The short description, the privacy block and
every disclosure text are unchanged: 3.7.0 adds no permission, no data flow and no cloud
behaviour — the VAD probe consumes the same mic stream the session already records.
```

- [ ] **Step 2: Verify both character budgets.**

```powershell
$n = "Dictation now cuts where you stop talking. A real voice-activity model listens frame by frame, so each sentence goes to be transcribed the moment you finish it instead of waiting on a timer — text lands sooner, and at a steady pace. Stopping is quicker when nothing is still queued. Multilingual paces itself to what your phone keeps up with. The two lightest model tiers are retired; models you already installed are untouched. Measured against our own previous version, same phone."
$o1 = "• Model tiers from light-and-quick to maximum accuracy, including multilingual"
$o2 = "• Voice activity detection: silence costs nothing"
$n1 = "• Model tiers from fast-and-English to 90+ languages to maximum accuracy"
$n2 = "• Real voice-activity detection: your sentence is transcribed when you stop talking, and silence costs nothing"
"notes=$($n.Length) (limit 500)"
"listing old=$($o1.Length + $o2.Length) new=$($n1.Length + $n2.Length) delta=$(($n1.Length + $n2.Length) - ($o1.Length + $o2.Length)) projected=$(3907 + ($n1.Length + $n2.Length) - ($o1.Length + $o2.Length)) (limit 4000)"
```

Expected, exactly:

```
notes=483 (limit 500)
listing old=127 new=182 delta=55 projected=3962 (limit 4000)
```

Anything else means the pasted text drifted from the draft (most likely an em dash flattened to a
hyphen, or a `•` lost) — fix the appended markdown, not this command.

- [ ] **Step 3: Verify the OLD bullets are still unique in the file** — the delta names two lines
  the owner will find by search, and the archived 3.2.0 block contains near-identical wording
  ("Five model tiers…", "Instant start…"):

```powershell
Select-String -Path docs\PLAY-LISTING.md -SimpleMatch -Pattern '• Model tiers from light-and-quick to maximum accuracy, including multilingual' | Measure-Object | Select-Object -ExpandProperty Count
Select-String -Path docs\PLAY-LISTING.md -SimpleMatch -Pattern '• Voice activity detection: silence costs nothing' | Measure-Object | Select-Object -ExpandProperty Count
```

Expected: `2` and `3` respectively — one hit each inside the new delta section (the OLD block),
plus the live FINAL 3.3.0 occurrence, plus (for the VAD bullet only) the archived 3.2.0
occurrence. If the first count is not 2, the FINAL block was edited by mistake: revert it, the
live copy must stay byte-identical.

- [ ] **Step 4: Confirm no code changed.**

```powershell
git status --porcelain
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon
```

Expected: `M docs/PLAY-LISTING.md` and nothing else; `BUILD SUCCESSFUL`, no failed tests.

- [ ] **Step 5: Commit.**

```powershell
git add docs/PLAY-LISTING.md
git commit -m "docs(release): 3.7.0 notes + the two-bullet Play listing delta (I4)" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`nClaude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT"
```

---

### Task S5: Whole-branch certification — untouchables, forced-fresh XML aggregation, both APKs

**Runs LAST, after every other task in every section.** This is the branch's ship gate. **Test
evidence is the XML aggregation below — never a Gradle task count and never a green console line.**

**Files:**
- Modify: `docs/superpowers/specs/2026-08-20-i-owner-acceptance.md` (the Certification block only)

**Interfaces:**
- Consumes: the whole branch. The `segment-timing: seq=` probe consumes Workstream F's format
  change; the other six consume the untouchable contracts as they stand today.
- Produces: the certified `suites= tests= failures= errors= skipped=` tuple recorded in the sheet.
  **Baseline to beat: the 3.6.0 branch certified 94 suites / 1,041 tests / 0 failures / 0 errors
  (re-verified against the current results dir while drafting). 3.7 must be strictly greater on
  both counts and exactly 0 on failures and errors.**

- [ ] **Step 1: Untouchable contracts — the cheapest gate, so it runs first.** Seven invariants the
  spec's Constraints section makes non-negotiable, each anchored on content rather than a line
  number (every one of these files moves during 3.7):

```powershell
$fbs = 'app\src\main\java\com\whispereverywhere\service\FloatingBubbleService.kt'
$jni = 'app\src\main\cpp\whisper_jni.cpp'
$st  = 'app\src\main\java\com\whispereverywhere\transcription\SegmentTiming.kt'
@(
  @{n='sendAudio-first';        f=$fbs; p='engine.sendAudio(chunk)'},
  @{n='caps-in-else-if';        f=$fbs; p='} else if (segmentCapPolicy.capExceeded(now)) {'},
  @{n='cloud-4s-suppression';   f=$fbs; p='if (cloudWrapper != null) segmentCapPolicy.onCommit(sessionOpenMs)'},
  @{n='stop-flush-uncond';      f=$fbs; p='Flush whatever is buffered, UNCONDITIONALLY.'},
  @{n='final-only-commit';      f=$jni; p='params.no_context'},
  @{n='segment-timing-prepend'; f=$st;  p='segment-timing: seq='}
) | ForEach-Object { $c = @(Select-String -Path $_.f -SimpleMatch -Pattern $_.p).Count; "{0,-24} {1}" -f $_.n, $c }

# 7th: no declaration and no permission moved on this branch.
$decl = @(git diff --name-only main...HEAD -- docs/PLAY-DECLARATIONS.md app/src/main/AndroidManifest.xml)
"{0,-24} {1}" -f 'play-declarations', $(if ($decl.Count -eq 0) { 'unchanged' } else { 'CHANGED - STOP' })
```

Expected, exactly:

```
sendAudio-first          1
caps-in-else-if          1
cloud-4s-suppression     1
stop-flush-uncond        1
final-only-commit        1
segment-timing-prepend   2
play-declarations        unchanged
```

**`segment-timing-prepend` reads `2`, not `1`, and that is the pass condition.** `Select-String`
returns one match per matching LINE, and after Tasks F2/F5 `SegmentTiming.kt` carries the prefix on
two lines: the KDoc sample at `:11` and the literal in `line()`. Both are required — the sample
documents the shape the sheet greps, the literal emits it.

Reading a failure: `caps-in-else-if` at 0 means the wall caps left the `else if` and are now inside
the endpointer's verdict — the single structural fact the whole de-risking rests on; STOP.
`segment-timing-prepend` at 0 means Workstream F emitted a sibling line instead of prepending
`seq=`, breaking every existing `findstr segment-timing` grep; at 1, one of the two sites drifted —
check which. `stop-flush-uncond` at 0 means the flush was gated — the change that silently discarded
whole sessions for soft talkers. `play-declarations` at anything but `unchanged` means 3.7 touched a
declaration or a permission: the release notes and the Console submission both assume it did not, and
the spec's Workstream I makes "no new permissions, no FGS/Data-Safety/disclosure changes" binding.

- [ ] **Step 2: Purge stale results, then force a fresh full unit run.** The purge is not
  optional: XML from a suite that was renamed or deleted during the branch survives in this
  directory forever and would inflate the certified count.

```powershell
$dir = 'C:\Users\bastr\.androidbuild\WhisperEverywhere\app\test-results\testDebugUnitTest'
if (Test-Path $dir) { Remove-Item -Recurse -Force $dir }
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon --rerun-tasks
```

Expected: `BUILD SUCCESSFUL`. (`--rerun-tasks` is what regenerates the XML — without it Gradle
reports the task UP-TO-DATE and writes nothing into the directory just deleted.)

- [ ] **Step 3: Aggregate the XML — this is the evidence.**

```powershell
$dir = 'C:\Users\bastr\.androidbuild\WhisperEverywhere\app\test-results\testDebugUnitTest'
$files = @(Get-ChildItem $dir -Filter 'TEST-*.xml')
$t=0;$f=0;$e=0;$s=0
foreach ($x in $files) { $d = [xml][System.IO.File]::ReadAllText($x.FullName); $t += [int]$d.testsuite.tests; $f += [int]$d.testsuite.failures; $e += [int]$d.testsuite.errors; $s += [int]$d.testsuite.skipped }
"suites=$($files.Count) tests=$t failures=$f errors=$e skipped=$s"
```

Expected shape: `suites=<N> tests=<M> failures=0 errors=0 skipped=0`, with **N > 94 and M > 1041**.
`failures=0 errors=0` is the gate; a lower suite or test count than the 3.6.0 baseline means tests
were deleted rather than added and must be explained before this branch ships.
(`[System.IO.File]::ReadAllText` rather than `Get-Content -Raw`: PS 5.1 reads BOM-less UTF-8 as
ANSI and would mangle these files.)

- [ ] **Step 4: Build both APKs — the native probe only compiles here.** `assembleDebug` runs
  CMake, so Workstream A's and B's C++ is not verified by anything above.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon
```

Expected: `BUILD SUCCESSFUL`. (Never `:app:installDebug` / `:app:connectedDebugAndroidTest`.) The
androidTest APK is what carries `VadProbeBenchTest` to the device for Check 1.

- [ ] **Step 5: Record the certification in the acceptance sheet.** In
  `docs/superpowers/specs/2026-08-20-i-owner-acceptance.md`, replace OLD:

```markdown
    suites=      tests=      failures=      errors=      skipped=
    assembleDebug:             PENDING
    assembleDebugAndroidTest:  PENDING
    untouchable contracts:     PENDING
```

with NEW (substitute the real numbers from Steps 2-4; `<date>` is the run date):

```markdown
    suites=<N> tests=<M> failures=0 errors=0 skipped=0   (forced fresh run, <date>)
    assembleDebug:             BUILD SUCCESSFUL
    assembleDebugAndroidTest:  BUILD SUCCESSFUL
    untouchable contracts:     7/7 (sendAudio-first, caps-in-else-if, cloud-4s-suppression,
                               stop-flush-uncond, final-only-commit, segment-timing-prepend,
                               play-declarations-unchanged)
    baseline for comparison:   3.6.0 = 94 suites / 1041 tests / 0 failures
```

- [ ] **Step 6: Commit.**

```powershell
git add docs/superpowers/specs/2026-08-20-i-owner-acceptance.md
git commit -m "chore(release): certify the 3.7.0 branch - <M> tests/0 failures, both APKs, 7/7 untouchables (I5)" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`nClaude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT"
```
