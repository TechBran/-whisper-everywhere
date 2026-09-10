# 4.4.0 — The on-device word-for-word previewer: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship 4.4.0 (versionCode 89): with English resolved and a 73 MB pack installed, a sherpa-onnx streaming Zipformer paints lowercase words on the bubble strip ~0.4–0.5 s behind the voice, on every device, on the CPU, while whisper (CPU or NPU) keeps every commit and types exactly what it types today.

**Architecture:** A **tee**. `PreviewTeeEngine` decorates the local `TranscriptionEngine`: `sendAudio` goes to `local` first and then to a bounded queue drained by `StreamingPreviewEngine` on its own single-thread executor (2 ORT threads, 32 ms feed, decode-while-ready, cumulative partial); every commit forwards to `local` and then pads 500 ms / drains / freezes the preview text under the seq `local` returned / releases and re-creates the stream; `PreviewComposer` emits `frozen[unresolved seqs] + partial` as the strip's `onDelta`; `local`'s resolutions pass through untouched and drop their frozen prefix. The strip-ownership rules gain a second input (`sessionHasLocalPreview`) in a behaviour-neutral prep commit on `main`. A load-time canary (`canary_digits.wav` → `GpuCanaryPolicy.canaryPasses`) is the only guard against the FEAT_SME miscompute; failure disables the previewer for the process. The pack is four pinned files at an immutable HF commit, sha256-verified, installed atomically under `filesDir/zf-stream/`.

**Tech Stack:** Kotlin 2.0.21 / AGP 8.7.3 / Gradle 8.14.4 / JUnit 4 (JVM only — no instrumented tests, no Robolectric; `unitTests.isReturnDefaultValues = true`, `app/build.gradle.kts:227`); sherpa-onnx 1.13.7 AAR (`app/libs/sherpa-onnx-1.13.7.aar`, ORT 1.27.1 — already pinned at `app/build.gradle.kts:596-598`, `:831`); Android `DownloadManager`; Compose for the two rows.

**Spec:** `docs/superpowers/specs/2026-09-10-streaming-previewer-design.md` (this commit). The plan argues from the spec; read both. The measurements it rests on: `docs/measurements/2026-09-10-tab-sherpa-rung3.md`, `docs/measurements/2026-09-10-zipformer-en-pc-rung1.md`.

## Global Constraints

- **Branches.** Task 1 (P0) is a behaviour-neutral prep commit **on `main`** (spec §11; research §5.5 item 10 — the strip change must not ride an engine branch). Tasks 2–8 land on `feat/4.4.0-streaming-previewer`, created off `main` AFTER Task 1's commit. Never commit Tasks 2–8 to `main`.
- **Release identity is the CONTROLLER's.** No task in this plan edits `versionCode` / `versionName` (`app/build.gradle.kts:53-54`) or `ReleaseIdentityTest`. The suite stays at 88 / 4.3.4 until the controller's identity commit (spec R5: 4.4.0 / 89).
- **Rulings.** The spec assumes R1–R6 (spec §0). If the owner rules otherwise before a task starts, the task's "Ruling hooks" line says what to change; the code is written so each flip is one function.
- **Build env (PowerShell 5.1):** every shell that runs Gradle must first set `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio1\jbr"` (the literal `1` is real). Always `.\gradlew.bat ... --no-daemon`. Build outputs live OUTSIDE the repo at `C:\Users\bastr\.androidbuild\WhisperEverywhere\`.
- **NEVER run `:app:installDebug` or `:app:connectedDebugAndroidTest`** — both uninstall the app and wipe the owner's downloaded models. **No adb in this plan.** The only device step is the owner's session (Task 8's sheet) on the internal-track build.
- **JVM suite command:** `.\gradlew.bat :app:testDebugUnitTest --no-daemon` (add `--tests "<fqcn>"` for one class). **Before every run purge the XML** so a stale result can never be read: `Remove-Item -Recurse -Force "C:\Users\bastr\.androidbuild\WhisperEverywhere\app\test-results\testDebugUnitTest" -ErrorAction SilentlyContinue`. Results are read from the raw XML, never from the console: see "Counting the suite" below. Gradle printing `UP-TO-DATE` for the test task means it did NOT run — purge and re-run.
- **`assembleDebug` after every task that touches production Kotlin** (Tasks 1–7): `.\gradlew.bat :app:assembleDebug --no-daemon` → `BUILD SUCCESSFUL`. The JVM suite compiles `app` too, but Task 7's `SherpaPreviewRecognizer` is the one file whose imports resolve only against the AAR's `classes.jar` at compile time and whose Kotlin parameter names (`FeatureConfig(sampleRate =, featureDim =, dither =)`, `VersionInfo.onnxruntimeVersion`) are pinned by the AAR, so a compile is the proof.
- **No test may import or reference `com.k2fsa`.** The AAR's classes load `libsherpa-onnx-jni.so` in a static initialiser; on the JVM that is an `UnsatisfiedLinkError`. Everything sherpa-shaped goes behind `PreviewRecognizer` (Task 3) and is faked. `TtsEngineSeamTest` is the precedent ("no sherpa").
- **Source-reading tests** (`InFlightStripWiringPinTest`, the new `LocalPreviewWiringPinTest`) read repo files at test time. Every file such a test reads MUST be in `sourcePinnedInputs` in `app/build.gradle.kts` (the block ending at `:508`). `FloatingBubbleService.kt` (`:312-313`) and `LocalWhisperEngine.kt` (`:396`) are already listed; no new file is read by any pin in this plan — if a task adds one, add the entry.
- **Pins bite on LIVE lines only.** Anchors are symbol-scoped strings inside a declaration's body (the `InFlightStripWiringPinTest.body()` idiom, LF-normalised); never line numbers, never whole-file `contains`.
- **Commit messages** are written to a UTF-8 **no-BOM** file and applied with `git commit -F <file>` (an em dash in a `-m` argument arrives mangled). Every commit ends with exactly two trailer lines: `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>` and the `Claude-Session:` line naming the implementing session's own URL (the harness hands each session its URL; use that one, never this plan's). Verify with `git log -1 --format=%B | Select-Object -Last 2`. Porcelain must be clean after every commit.
- **Diagnostics never carry transcript content.** Numbers, codes, stage names, language codes, file names only (`SegmentTiming.kt:1-45`, `NpuDiag` KDoc). `outLen`, never the text.
- **No new dependencies.** The AAR is already a dependency; `DownloadManager`, `MessageDigest`, `LinkedBlockingQueue` are platform.
- **Do not edit** `LocalWhisperEngine.kt`, `NpuWhisperBackend.kt`, the cloud engines, `SileroEndpointer.kt`, `WhisperModel.kt`, `WhisperModelManager.kt`, `CommitCadencePolicy.kt`, `FinalDeliveryPolicy.kt`, `TranscriptSink.kt` (spec §10). `TranscriptionEngine.kt` gains no member.

### Counting the suite (paste into PowerShell after a run)

```powershell
$dir = "C:\Users\bastr\.androidbuild\WhisperEverywhere\app\test-results\testDebugUnitTest"
$s = Get-ChildItem "$dir\*.xml" | ForEach-Object { ([xml](Get-Content $_.FullName)).testsuite }
"suites=$($s.Count) tests=$(($s | Measure-Object tests -Sum).Sum) failures=$(($s | Measure-Object failures -Sum).Sum) errors=$(($s | Measure-Object errors -Sum).Sum)"
```
Baseline at `5efc2be` (the last certified run is the 88 identity commit `1581a0d`; the docs commits since it touch no test): **177 suites / 2,150 tests / 0 failures / 0 errors.** Task 1 re-measures the whole suite FIRST and records the true number in the ledger; every later task records its delta.

### Committing (paste into PowerShell; edit the message)

```powershell
$msg = @'
<type>(<scope>): <subject>

<body>

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: <this session's URL>
'@
[System.IO.File]::WriteAllText("C:\Users\bastr\.androidbuild\commit-msg.txt", $msg, (New-Object System.Text.UTF8Encoding $false))
git add <files>
git commit -F "C:\Users\bastr\.androidbuild\commit-msg.txt"
git log -1 --format=%B | Select-Object -Last 2
git status --porcelain
```

### Execution protocol (the SDD loop this project uses)

One fresh implementer per task. The implementer ends by writing `.superpowers/sdd/2026-09-10-440-streaming-previewer/task-<N>-report.md` (what changed, the suite count before/after from XML, the battery rows below executed with their red/green evidence). The controller then saves `git diff <parent>..<head>` beside it as `review-<parent>..<head>.diff` and dispatches a reviewer against the spec + report; findings go through one fix round and a scoped re-review before the next task starts. The ledger `progress.md` in that directory records every ruling.

**Battery rows** (per task, listed under "Battery"): each row temporarily applies one mutation to the implementation, runs the named test class, and must observe RED; then the mutation is reverted (`git checkout -- <file>`) and the class must be GREEN again. Evidence = the XML `failures` count for that class in both states. A pin that stays green under its mutation is a hole and the task is not done.

---

## File map

| # | File | Responsibility | Task |
|---|---|---|---|
| 1 | `app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt` (`:324` rule, `:335-339` label, `:375-380` visibility, `:395-396` clears; field `:498`; reset `:2672`; `onDelta` `:3039`, `:3071`; render `:3529-3530`; delivery `:3561`) | the strip rules gain `sessionHasLocalPreview` | 1 |
| 2 | `app/src/test/java/com/whispereverywhere/service/InFlightStripTest.kt` | re-specced value pins | 1 |
| 3 | `app/src/test/java/com/whispereverywhere/service/InFlightStripWiringPinTest.kt` | re-specced needles + the reset and blank-branch pins | 1 |
| 4 | `.superpowers/sdd/2026-09-10-440-streaming-previewer/progress.md` (create) | the ledger | 1 |
| 5 | `app/src/main/java/com/whispereverywhere/transcription/stream/StreamingPackCatalog.kt` (create) | the four pinned files, URL, badge, marker text | 2 |
| 6 | `app/src/main/java/com/whispereverywhere/transcription/stream/StreamingPackInstall.kt` (create) | pure install/verify/delete over `File` | 2 |
| 7 | `app/src/main/java/com/whispereverywhere/transcription/stream/StreamingPackManager.kt` (create) | `DownloadManager` download, free-space gate, stale rows | 2 |
| 8 | `app/src/main/java/com/whispereverywhere/WhisperEverywhereApp.kt` (`:35-37`) | `streamingPackManager by lazy` | 2 |
| 9 | `app/src/test/java/com/whispereverywhere/transcription/stream/StreamingPackCatalogTest.kt`, `StreamingPackInstallTest.kt` (create) | pins 5, 6 | 2 |
| 10 | `app/src/main/java/com/whispereverywhere/transcription/stream/StreamingPreviewTuning.kt` (create) | the measured constants | 3 |
| 11 | `app/src/main/java/com/whispereverywhere/transcription/stream/PreviewRecognizer.kt` (create) | the seam: `PreviewRecognizer`, `PreviewStream`, `PreviewResult`, `PreviewRecognizerFactory` | 3 |
| 12 | `app/src/main/java/com/whispereverywhere/transcription/stream/PreviewCanary.kt` (create) | the load-time canary | 3 |
| 13 | `app/src/test/java/com/whispereverywhere/transcription/stream/ScriptedRecognizer.kt`, `StreamingPreviewTuningTest.kt`, `PreviewCanaryTest.kt` (create) | the shared fake; pins 10, 12 | 3 |
| 14 | `app/src/main/java/com/whispereverywhere/transcription/stream/PreviewText.kt`, `PcmRing.kt`, `StreamDiag.kt`, `StreamingPreviewEngine.kt` (create; `LocalPreview` interface lives in the engine file) | the previewer | 4 |
| 15 | `app/src/test/java/com/whispereverywhere/transcription/stream/PreviewTextTest.kt`, `PcmRingTest.kt`, `StreamDiagTest.kt`, `StreamingPreviewEngineTest.kt` (create) | pins 14 | 4 |
| 16 | `app/src/main/java/com/whispereverywhere/transcription/stream/PreviewComposer.kt`, `PreviewTeeEngine.kt` (create) | the composition rule; the tee | 5 |
| 17 | `app/src/test/java/com/whispereverywhere/transcription/stream/PreviewComposerTest.kt`, `PreviewTeeEngineTest.kt` (create) | pins 16 | 5 |
| 18 | `FloatingBubbleService.kt` (beside `sessionLanguageFor`, `:203-209`) | `localPreviewArms` | 6 |
| 19 | `app/src/main/java/com/whispereverywhere/data/local/PreferencesManager.kt` (`:400-409` pattern; keys `:452-461`) | `localPreviewEnabled` | 6 |
| 20 | `app/src/main/java/com/whispereverywhere/transcription/stream/StreamingPackCopy.kt` (create) | every user-facing string | 6 |
| 21 | `app/src/main/java/com/whispereverywhere/ui/screens/SettingsScreen.kt` (`:502-525`, `:886`), `ui/screens/OnboardingFlowScreen.kt` (`:593`, `:602`) | the row, the switch, the sentence, the chip | 6 |
| 22 | `app/src/test/java/com/whispereverywhere/service/LocalPreviewGateTest.kt`, `app/src/test/java/com/whispereverywhere/transcription/stream/StreamingPackCopyTest.kt` (create) | pins 18, 20 | 6 |
| 23 | `app/src/main/java/com/whispereverywhere/transcription/stream/SherpaPreviewRecognizer.kt` (create) | the production adapter over `com.k2fsa.sherpa.onnx.OnlineRecognizer` | 7 |
| 24 | `FloatingBubbleService.kt` (`:443` field; `:760-762` arbiter; `:783` prewarm; `:1140` destroy; `:2896` base engine; `:2937-2940` wrap site; `:3398-3407` trim) | the wiring | 7 |
| 25 | `app/proguard-rules.pro` (`:72-76`), `app/src/main/java/com/whispereverywhere/transcription/NativeComputeGate.kt` (KDoc) | the two guard sentences | 7 |
| 26 | `app/src/test/java/com/whispereverywhere/service/LocalPreviewWiringPinTest.kt` (create) | source pins on 24 | 7 |
| 27 | `docs/superpowers/sdd/2026-09-10-440-streaming-previewer/acceptance.md` (create) | the owner's device sheet Z1–Z11 | 8 |

---

### Task 1 (P0): The strip prep — `sessionHasLocalPreview` joins the rules, behaviour-neutral, on `main`

**Size:** 1.5 days. **Branch:** `main` (this task only). **Ruling hooks:** R2 lives in `inFlightStripLabel`'s first row.

**Files:**
- Modify: `app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt`
- Modify: `app/src/test/java/com/whispereverywhere/service/InFlightStripTest.kt`
- Modify: `app/src/test/java/com/whispereverywhere/service/InFlightStripWiringPinTest.kt`
- Create: `.superpowers/sdd/2026-09-10-440-streaming-previewer/progress.md`

**Interfaces (Task 7 consumes):**
- `internal fun deltaOwnsPreviewStrip(sessionIsLive: Boolean, sessionHasLocalPreview: Boolean): Boolean`
- `internal fun inFlightStripLabel(depth: Int, sessionHasLocalPreview: Boolean): String?`
- `internal fun deltaBlankVisibility(sessionHasLocalPreview: Boolean, currentlyHidden: Boolean): StripVisibility`
- `internal fun resolvedTextClearsStrip(sessionIsLive: Boolean, sessionHasLocalPreview: Boolean, isFinalizing: Boolean): Boolean`
- The service field `@Volatile private var sessionHasLocalPreview = false`, reset beside `sessionIsLive = false`; **never set true in this task**.

- [ ] **Step 0: Baseline.** Purge the XML, run the whole suite, count. Record the exact number as the baseline in the ledger (expected 177 / 2,150 / 0 / 0).

- [ ] **Step 1: Re-spec the value pins** — replace the body of `InFlightStripTest.kt` from the first `// ---` section marker to the end of the class with:

```kotlin
    // ------------------------------------------------------------- who owns the strip

    @Test fun a_server_driven_live_session_keeps_its_deltas_on_the_strip() {
        // CLOUD_LIVE partials stream AS SPOKEN and are the whole point of the strip there.
        assertTrue(deltaOwnsPreviewStrip(sessionIsLive = true, sessionHasLocalPreview = false))
    }

    @Test fun a_local_session_without_a_preview_still_hands_the_strip_to_the_in_flight_line() {
        // whisper.cpp fires new_segment AFTER the window's decode, so at utterance cadence the
        // whole burst — and LocalWhisperEngine's terminal onDelta("") — lands inside one
        // Choreographer frame. The commit/resolve in-flight line replaces it. Unchanged from 3.7 G.
        assertFalse(deltaOwnsPreviewStrip(sessionIsLive = false, sessionHasLocalPreview = false))
    }

    @Test fun a_local_preview_owns_the_strip_exactly_as_a_live_session_does() {
        // 4.4.0 P0: the second input. A tee whose partials arrive every 320 ms is the live
        // session's shape, so it takes the live session's render path — words, not the label.
        assertTrue(deltaOwnsPreviewStrip(sessionIsLive = false, sessionHasLocalPreview = true))
        // Unreachable by construction (the tee is built only for local sessions) but pinned, so
        // the rule has no hole where its fourth row should be.
        assertTrue(deltaOwnsPreviewStrip(sessionIsLive = true, sessionHasLocalPreview = true))
    }

    // ------------------------------------------------------------- what the line says

    @Test fun an_empty_queue_has_no_line() {
        assertNull(inFlightStripLabel(0, sessionHasLocalPreview = false))
    }

    @Test fun a_negative_depth_is_treated_as_empty() {
        assertNull(inFlightStripLabel(-1, sessionHasLocalPreview = false))
    }

    @Test fun one_utterance_in_flight_says_only_that() {
        assertEquals("Transcribing…", inFlightStripLabel(1, sessionHasLocalPreview = false))
    }

    @Test fun a_backlog_names_its_depth() {
        assertEquals("Transcribing… (2 in queue)", inFlightStripLabel(2, sessionHasLocalPreview = false))
        assertEquals("Transcribing… (7 in queue)", inFlightStripLabel(7, sessionHasLocalPreview = false))
    }

    @Test fun a_local_preview_displaces_the_label_at_every_depth() {
        // RULING ASSUMED (R2): DISPLACED. The strip carries the words; `queue:` keeps the depth.
        // A flip to "shared" or "moved" is a change to THIS function and this test, nowhere else.
        listOf(-1, 0, 1, 2, 7, 9).forEach { d ->
            assertNull("depth $d under a local preview", inFlightStripLabel(d, sessionHasLocalPreview = true))
        }
    }

    @Test fun the_line_makes_no_speed_claim_and_names_no_provider() {
        // Depth 9 is probed ONLY by this scan (G2 review m1/m7): do not trim it.
        listOf(0, 1, 2, 9).forEach { d ->
            val text = (inFlightStripLabel(d, sessionHasLocalPreview = false) ?: "").lowercase()
            listOf("faster", "fastest", "quicker", "quickest", "instant", "real-time")
                .forEach { banned ->
                    assertFalse("in-flight line contains banned word '$banned'", text.contains(banned))
                }
        }
    }

    // ------------------------------------------------------------- the commit funnel

    @Test fun a_commit_that_cut_nothing_does_not_advance_the_queue() {
        assertFalse(commitAdvancesQueueDepth(-1L))
    }

    @Test fun the_very_first_segment_of_a_session_advances_the_queue() {
        assertTrue(commitAdvancesQueueDepth(0L))
    }

    @Test fun any_real_seq_advances_the_queue() {
        assertTrue(commitAdvancesQueueDepth(1L))
        assertTrue(commitAdvancesQueueDepth(4_096L))
    }

    // ------------------------------------------------------------- the anti-churn rule

    @Test fun the_first_line_of_a_session_reveals_the_strip() {
        assertEquals(StripVisibility.SHOWING, inFlightStripVisibility(label = "Transcribing…", currentlyHidden = true))
    }

    @Test fun an_empty_queue_before_the_first_commit_leaves_the_strip_hidden() {
        assertEquals(StripVisibility.HIDDEN, inFlightStripVisibility(label = null, currentlyHidden = true))
    }

    @Test fun an_emptied_queue_keeps_the_strip_occupying_its_space() {
        assertEquals(StripVisibility.OCCUPYING_BLANK, inFlightStripVisibility(label = null, currentlyHidden = false))
    }

    @Test fun a_deepening_queue_repaints_without_a_geometry_change() {
        assertEquals(StripVisibility.SHOWING, inFlightStripVisibility(label = "Transcribing… (3 in queue)", currentlyHidden = false))
    }

    // ------------------------------------------------------------- a blank delta

    @Test fun a_blank_delta_still_hides_a_live_sessions_strip() {
        // CLOUD_LIVE keeps 3.6.0's GONE byte for byte — the three live providers are out of scope.
        assertEquals(StripVisibility.HIDDEN, deltaBlankVisibility(sessionHasLocalPreview = false, currentlyHidden = true))
        assertEquals(StripVisibility.HIDDEN, deltaBlankVisibility(sessionHasLocalPreview = false, currentlyHidden = false))
    }

    @Test fun a_blank_delta_parks_a_local_previews_revealed_strip_instead_of_hiding_it() {
        // THE anti-churn rule, applied to the delta render: a local preview blanks between
        // utterances (frozen text resolved, no partial yet), and VISIBLE<->GONE there would post a
        // reclampNow() per utterance — the exact cost 3.7 G removed from the label render.
        assertEquals(StripVisibility.OCCUPYING_BLANK, deltaBlankVisibility(sessionHasLocalPreview = true, currentlyHidden = false))
    }

    @Test fun a_blank_delta_never_reveals_a_still_hidden_strip() {
        // GONE -> INVISIBLE would grow the window for a line with nothing in it.
        assertEquals(StripVisibility.HIDDEN, deltaBlankVisibility(sessionHasLocalPreview = true, currentlyHidden = true))
    }

    // ------------------------------------------------------------- resolution vs the strip

    @Test fun a_live_resolution_still_clears_the_words_it_was_streaming() {
        assertTrue(resolvedTextClearsStrip(sessionIsLive = true, sessionHasLocalPreview = false, isFinalizing = false))
    }

    @Test fun a_local_resolution_repaints_the_in_flight_line_instead_of_clearing() {
        assertFalse(resolvedTextClearsStrip(sessionIsLive = false, sessionHasLocalPreview = false, isFinalizing = false))
    }

    @Test fun a_local_previews_resolution_never_clears_the_strip() {
        // The tee recomposes after every resolution (frozen prefix dropped, partial kept) and
        // emits that as the next delta. Clearing here would GONE the strip under it and pay the
        // reveal — and its reclamp — on every utterance.
        assertFalse(resolvedTextClearsStrip(sessionIsLive = false, sessionHasLocalPreview = true, isFinalizing = false))
    }

    @Test fun finalizing_owns_the_strip_in_every_session_kind() {
        assertFalse(resolvedTextClearsStrip(sessionIsLive = true, sessionHasLocalPreview = false, isFinalizing = true))
        assertFalse(resolvedTextClearsStrip(sessionIsLive = false, sessionHasLocalPreview = false, isFinalizing = true))
        assertFalse(resolvedTextClearsStrip(sessionIsLive = false, sessionHasLocalPreview = true, isFinalizing = true))
    }
}
```

- [ ] **Step 2: Run to see it fail** — `--tests "com.whispereverywhere.service.InFlightStripTest"`. Expected: compilation FAILS (`No value passed for parameter 'sessionHasLocalPreview'`, `Unresolved reference: deltaBlankVisibility`).

- [ ] **Step 3: Implement the rules** in `FloatingBubbleService.kt`.

(a) Replace `internal fun deltaOwnsPreviewStrip(sessionIsLive: Boolean): Boolean = sessionIsLive` (`:324`) with — and append this paragraph to the end of its KDoc, before the closing `*/`:

```kotlin
 *
 * **4.4.0 P0 — the second input.** A LOCAL PREVIEW (`PreviewTeeEngine`, spec
 * 2026-09-10-streaming-previewer-design.md §4.2) emits partials every 320 ms — the live
 * session's shape — so it takes the live session's render path. `sessionHasLocalPreview` is a
 * second session flag beside `sessionIsLive`, set only at the one wrap site; `sessionIsLive`
 * keeps its three other jobs untouched. Until a producer sets it, every reader answers as
 * before: this commit is behaviour-neutral by the truth table InFlightStripTest pins.
 */
internal fun deltaOwnsPreviewStrip(sessionIsLive: Boolean, sessionHasLocalPreview: Boolean): Boolean =
    sessionIsLive || sessionHasLocalPreview
```

(b) Replace `inFlightStripLabel` (`:335-339`) with:

```kotlin
internal fun inFlightStripLabel(depth: Int, sessionHasLocalPreview: Boolean): String? = when {
    // RULING ASSUMED (R2): while a local preview paints the words, the label is DISPLACED —
    // the strip IS the pending text, and the `queue:` diag line keeps the depth. A flip to
    // "shared" (label after the words) or "moved" (into the window) changes THIS row only.
    sessionHasLocalPreview -> null
    depth <= 0 -> null
    depth == 1 -> "Transcribing…"
    else -> "Transcribing… ($depth in queue)"
}
```

(c) After `inFlightStripVisibility` (`:375-380`) add:

```kotlin
/**
 * What a BLANK delta does to the strip (4.4.0 P0). A server-driven live session keeps 3.6.0's
 * behaviour — GONE, byte for byte (the three live providers are out of this release's scope).
 * A local preview blanks between utterances by design (its frozen text resolved, no partial
 * yet), so it composes with [inFlightStripVisibility]'s anti-churn rule: parked INVISIBLE once
 * revealed, never revealed for nothing. SHOWING is unreachable here — a blank never shows.
 */
internal fun deltaBlankVisibility(sessionHasLocalPreview: Boolean, currentlyHidden: Boolean): StripVisibility =
    if (sessionHasLocalPreview) inFlightStripVisibility(label = null, currentlyHidden = currentlyHidden)
    else StripVisibility.HIDDEN
```

(d) Replace `resolvedTextClearsStrip` (`:395-396`) with — appending this paragraph to its KDoc:

```kotlin
 *
 * **4.4.0 P0:** a LOCAL PREVIEW never clears here. Its tee recomposes the strip after every
 * resolution (the resolved seq's frozen prefix dropped, the live partial kept) and emits that
 * as the next delta; a clear in between would GONE the strip and pay the reveal — and its
 * reclampNow() — once per utterance, which is exactly the churn this rule's other half removes.
 */
internal fun resolvedTextClearsStrip(sessionIsLive: Boolean, sessionHasLocalPreview: Boolean, isFinalizing: Boolean): Boolean =
    !isFinalizing && deltaOwnsPreviewStrip(sessionIsLive, sessionHasLocalPreview) && !sessionHasLocalPreview
```

(e) After the `sessionIsLive` field (`:498`) add:

```kotlin
    // 4.4.0 P0: frozen per session at the one wrap site in startRecording (Task 7 of the
    // streaming-previewer plan sets it; this commit only declares and resets it). True only when a
    // PreviewTeeEngine is this session's engine. Read by onDelta, the render and delivery through
    // the four pure rules above — never directly.
    @Volatile private var sessionHasLocalPreview = false
```

(f) In `resolveTranscriptionEngine`, directly under `sessionIsLive = false` (`:2672`), add `sessionHasLocalPreview = false`.

(g) In `onDelta` (`:3039`) replace the gate line with:

```kotlin
                if (!deltaOwnsPreviewStrip(sessionIsLive = sessionIsLive, sessionHasLocalPreview = sessionHasLocalPreview)) return
```

(h) In `onDelta`'s blank branch (`:3070-3072`) replace `transcriptionDeltaText.visibility = View.GONE` with:

```kotlin
                        // 4.4.0 P0: a local preview parks, a live session hides (deltaBlankVisibility).
                        when (deltaBlankVisibility(
                            sessionHasLocalPreview = sessionHasLocalPreview,
                            currentlyHidden = transcriptionDeltaText.visibility == View.GONE,
                        )) {
                            StripVisibility.HIDDEN -> transcriptionDeltaText.visibility = View.GONE
                            StripVisibility.OCCUPYING_BLANK -> {
                                transcriptionDeltaText.text = ""
                                transcriptionDeltaText.visibility = View.INVISIBLE
                            }
                            StripVisibility.SHOWING -> Unit
                        }
```

(i) In `renderInFlightStrip` (`:3529-3530`) replace the two lines with:

```kotlin
        if (deltaOwnsPreviewStrip(sessionIsLive = sessionIsLive, sessionHasLocalPreview = sessionHasLocalPreview)) return
        val label = inFlightStripLabel(depth = segmentQueueDepth.depth(), sessionHasLocalPreview = sessionHasLocalPreview)
```

(j) In `deliverReleasedText` (`:3561`) replace the condition with:

```kotlin
        if (resolvedTextClearsStrip(sessionIsLive = sessionIsLive, sessionHasLocalPreview = sessionHasLocalPreview, isFinalizing = finalizing)) {
```

- [ ] **Step 4: Run `InFlightStripTest` to see it pass**, then run `InFlightStripWiringPinTest` — expected: RED on `theRenderAsksTheSessionKindAndNeverTheWrapper`, `localDeltasAreTurnedAwayAtTheTopOfOnDelta` and `deliveryRepaintsTheStripInsteadOfHidingIt` (the needles are the one-argument strings).

- [ ] **Step 5: Re-spec the wiring pins.** In `InFlightStripWiringPinTest.kt`:

Replace the needle in `theRenderAsksTheSessionKindAndNeverTheWrapper` with `"deltaOwnsPreviewStrip(sessionIsLive = sessionIsLive, sessionHasLocalPreview = sessionHasLocalPreview)"` and add, after the `cloudWrapper` assertion:

```kotlin
        assertEquals(
            "the render asks the label with the second input too (R2 lives in that function)",
            1,
            count(render, "inFlightStripLabel(depth = segmentQueueDepth.depth(), sessionHasLocalPreview = sessionHasLocalPreview)"),
        )
```

In `localDeltasAreTurnedAwayAtTheTopOfOnDelta` replace `val gate = …` with:

```kotlin
        val gate = "                if (!deltaOwnsPreviewStrip(sessionIsLive = sessionIsLive, sessionHasLocalPreview = sessionHasLocalPreview)) return\n"
```

In `deliveryRepaintsTheStripInsteadOfHidingIt` replace the `indexOfOrFail(delivery, …)` needle with:

```kotlin
        indexOfOrFail(
            delivery,
            "        if (resolvedTextClearsStrip(sessionIsLive = sessionIsLive, " +
                "sessionHasLocalPreview = sessionHasLocalPreview, isFinalizing = finalizing)) {\n",
        )
```

Add two tests before the closing brace of the class:

```kotlin
    @Test
    fun theSecondInputIsResetBesideTheFirstAndNeverSetTrueByThisCommit() {
        // 4.4.0 P0 is behaviour-neutral: the flag is declared, reset per session on the line under
        // `sessionIsLive = false`, and set true NOWHERE until the wrap site lands (Task 7). A
        // `= true` appearing here would mean a producer rode the prep commit.
        val resolve = body("    private fun resolveTranscriptionEngine(): TranscriptionEngine {", "\n    }\n")
        assertEquals(1, count(resolve, "        sessionIsLive = false\n        sessionHasLocalPreview = false\n"))
        assertEquals(1, count(text, "@Volatile private var sessionHasLocalPreview = false"))
        // The literal assignment to true is forbidden in this file for good: Task 7 assigns the
        // GATE's answer (`sessionHasLocalPreview = previewArmed`), never a constant.
        assertEquals(0, count(text, "sessionHasLocalPreview = true"))
    }

    @Test
    fun theBlankDeltaBranchAsksTheRuleAndParksOnlyALocalPreview() {
        // The delta render's blank branch is the second place the anti-churn rule must hold. The
        // 3.6.0 body was a bare `visibility = View.GONE`; under a local preview that is a reveal
        // per utterance. The branch now asks `deltaBlankVisibility`, and its HIDDEN row is the
        // only GONE write left in onDelta.
        assertEquals(1, count(onDelta, "deltaBlankVisibility("))
        assertEquals(1, count(onDelta, "StripVisibility.HIDDEN -> transcriptionDeltaText.visibility = View.GONE"))
        assertEquals(1, count(onDelta, "transcriptionDeltaText.visibility = View.GONE"))
        assertEquals(1, count(onDelta, "transcriptionDeltaText.visibility = View.INVISIBLE"))
    }
```

- [ ] **Step 6: Run both classes to see them pass.** Then `assembleDebug` → `BUILD SUCCESSFUL`.

- [ ] **Step 7: Create the ledger** `.superpowers/sdd/2026-09-10-440-streaming-previewer/progress.md`:

```markdown
# 4.4.0 — the on-device word-for-word previewer — ledger

Task 1 (P0) on main; Tasks 2-8 on feat/4.4.0-streaming-previewer. Spec docs/superpowers/specs/2026-09-10-streaming-previewer-design.md. Plan docs/superpowers/plans/2026-09-10-streaming-previewer.md.
Rulings ASSUMED (spec §0): R1 canary-only + release note; R2 displaced; R3 default-on; R4 Auto=whisper on multilingual; R5 4.4.0/89 (controller's commit); R6 streaming first.
Baseline suite at 5efc2be: <suites> / <tests> / 0 / 0 (from XML, Step 0).

=== Task 1: P0 strip prep (main) ===
(commit, suite count from XML, notes)
```

- [ ] **Step 8: Whole suite, then commit on `main`.** Expected: baseline + 8 tests (InFlightStripTest 17 → 23 = +6; the wiring pin +2), `failures=0`.

```
feat(strip): the strip rules gain sessionHasLocalPreview — behaviour-neutral prep for the 4.4.0 previewer

deltaOwnsPreviewStrip, inFlightStripLabel (R2 assumed: displaced), deltaBlankVisibility (a
local preview parks INVISIBLE, live keeps GONE) and resolvedTextClearsStrip take the second
session flag; the flag is declared and reset, never set. InFlightStripTest and the wiring pin
re-specced; the reset and the blank branch pinned. Ledger opened.
```
Files: `FloatingBubbleService.kt`, `InFlightStripTest.kt`, `InFlightStripWiringPinTest.kt`, `progress.md`.

**Battery:** (1) change `deltaOwnsPreviewStrip` to `sessionIsLive` only → `a_local_preview_owns_the_strip…` RED; revert. (2) make `inFlightStripLabel` ignore `sessionHasLocalPreview` → `a_local_preview_displaces_the_label…` RED; revert. (3) make `deltaBlankVisibility` return `HIDDEN` unconditionally → `a_blank_delta_parks…` RED; revert. (4) in `onDelta`'s blank branch restore the bare `View.GONE` → `theBlankDeltaBranchAsksTheRule…` RED; revert. (5) delete the `sessionHasLocalPreview = false` reset → `theSecondInputIsReset…` RED; revert.

---

### Task 2 (P1): `StreamingPackCatalog` + `StreamingPackInstall` + `StreamingPackManager`

**Size:** 1.5 days. **Branch:** create `feat/4.4.0-streaming-previewer` off `main` at Task 1's commit; everything from here lands there.

**Files:**
- Create: `app/src/main/java/com/whispereverywhere/transcription/stream/StreamingPackCatalog.kt`
- Create: `app/src/main/java/com/whispereverywhere/transcription/stream/StreamingPackInstall.kt`
- Create: `app/src/main/java/com/whispereverywhere/transcription/stream/StreamingPackManager.kt`
- Modify: `app/src/main/java/com/whispereverywhere/WhisperEverywhereApp.kt` (beside `whisperModelManager`, `:35-37`)
- Test: `app/src/test/java/com/whispereverywhere/transcription/stream/StreamingPackCatalogTest.kt`, `StreamingPackInstallTest.kt` (create)

**Interfaces (Tasks 3, 6, 7 consume):**
- `data class PackFile(name, bytes, sha256)`; `data class StreamingPack(language, dirName, baseUrl, encoder, decoder, joiner, tokens) { files; totalBytes; urlOf(f) }`
- `object StreamingPackCatalog { ROOT_DIR = "zf-stream"; MARKER = ".installed"; TMP_SUFFIX = ".tmp"; val EN; val packs; forLanguage(code); sizeBadge(bytes); markerText(pack) }`
- `object StreamingPackInstall { installDir(root, pack); tmpDir; marker(dir); isInstalled(dir, pack); verify(staged, pack): PackVerdict; install(staged, root, pack); delete(root, pack); markCorrupt(root, pack); sha256Hex(f) }`
- `class StreamingPackManager(context) { root(); installDir(pack); isInstalled(pack); installedDir(pack); markCorrupt(pack); delete(pack); suspend download(pack, onProgress) }`
- `WhisperEverywhereApp.streamingPackManager: StreamingPackManager`

- [ ] **Step 1: Write the failing tests.**

`StreamingPackCatalogTest.kt`:

```kotlin
package com.whispereverywhere.transcription.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The four pinned files of `streaming-zipformer-en-2023-06-26` at HF commit 672fbf1b…, sizes and
 * sha256s as re-hashed on the PC (rung 1 §1.2) and on the Tab (rung 3 §1.2, §7). Literals here,
 * literals in the catalog: a drift in either is a red test, never a quiet re-pin. The encoder's
 * hash was "oid only" in the research doc and is VERIFIED since rung 1.
 */
class StreamingPackCatalogTest {

    @Test fun theEnglishPackIsTheFourFilesAtTheImmutableCommit() {
        val p = StreamingPackCatalog.EN
        assertEquals("en", p.language)
        assertEquals("en-2023-06-26", p.dirName)
        assertEquals(
            "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26/resolve/672fbf1b30579d6585301139bb363f42a0ad4a24/",
            p.baseUrl,
        )
        assertEquals(PackFile("encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx", 71_083_163L, "563fde436d16cf7607cf408cd6b30909819d03162652ef389c2450ced3f45ac1"), p.encoder)
        assertEquals(PackFile("decoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx", 1_307_236L, "98da299f471e38bb4e1a8df579b8cc9122d6039576a77e357b3c60f17dd83b02"), p.decoder)
        assertEquals(PackFile("joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx", 259_335L, "d944208d660d67c8d72cd2acaeac971fa5ceb8c80e76c1968148846fedd6e297"), p.joiner)
        assertEquals(PackFile("tokens.txt", 5_048L, "49e3c2646595fd907228b3c6787069658f67b17377c60aeb8619c4551b2316fb"), p.tokens)
        assertEquals(listOf(p.encoder, p.decoder, p.joiner, p.tokens), p.files)
        assertEquals(72_654_782L, p.totalBytes)
    }

    @Test fun theUrlIsTheCommitPinnedBasePlusTheFileName() {
        val p = StreamingPackCatalog.EN
        assertEquals(p.baseUrl + "tokens.txt", p.urlOf(p.tokens))
        // resolve/main is a MUTABLE ref (WhisperCatalog's own rule): the base must carry the sha.
        assertTrue(p.baseUrl.contains("/resolve/672fbf1b30579d6585301139bb363f42a0ad4a24/"))
        p.files.forEach { assertTrue(it.sha256.matches(Regex("[0-9a-f]{64}"))) }
    }

    @Test fun theBadgeFollowsTheHouseDecimalConvention() {
        // ModelTierCopy says "190 MB" for 190,085,487 B; 72,654,782 B is "73 MB", never "70 MB".
        assertEquals("73 MB", StreamingPackCatalog.sizeBadge(72_654_782L))
        assertEquals("190 MB", StreamingPackCatalog.sizeBadge(190_085_487L))
    }

    @Test fun theMarkerTextIsTheFourHashLines() {
        val p = StreamingPackCatalog.EN
        val expected = p.files.joinToString("") { "${it.sha256}  ${it.name}\n" }
        assertEquals(expected, StreamingPackCatalog.markerText(p))
        assertEquals(4, StreamingPackCatalog.markerText(p).lines().count { it.isNotBlank() })
    }

    @Test fun onlyEnglishHasAPackAndAutoHasNone() {
        assertSame(StreamingPackCatalog.EN, StreamingPackCatalog.forLanguage("en"))
        assertNull(StreamingPackCatalog.forLanguage("es"))
        assertNull("auto (null) never resolves to a pack — the gate reads the RESOLVED language", StreamingPackCatalog.forLanguage(null))
        assertEquals(listOf(StreamingPackCatalog.EN), StreamingPackCatalog.packs)
    }

    @Test fun theLayoutConstantsAreFixed() {
        assertEquals("zf-stream", StreamingPackCatalog.ROOT_DIR)
        assertEquals(".installed", StreamingPackCatalog.MARKER)
        assertEquals(".tmp", StreamingPackCatalog.TMP_SUFFIX)
    }
}
```

`StreamingPackInstallTest.kt`:

```kotlin
package com.whispereverywhere.transcription.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class StreamingPackInstallTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun sha(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** A tiny pack with real sizes and hashes for four synthetic files. */
    private val enc = "ENCODER".toByteArray()
    private val dec = "DECODER-BYTES".toByteArray()
    private val joi = "J".toByteArray()
    private val tok = "<blk>\n<sos/eos>\n<unk>\n".toByteArray()
    private val pack = StreamingPack(
        language = "en", dirName = "en-test", baseUrl = "https://example.invalid/resolve/abc/",
        encoder = PackFile("encoder.onnx", enc.size.toLong(), sha(enc)),
        decoder = PackFile("decoder.onnx", dec.size.toLong(), sha(dec)),
        joiner = PackFile("joiner.onnx", joi.size.toLong(), sha(joi)),
        tokens = PackFile("tokens.txt", tok.size.toLong(), sha(tok)),
    )

    private fun stage(vararg overrides: Pair<String, ByteArray>): File {
        val staged = tmp.newFolder("staged-" + System.nanoTime())
        val contents = mutableMapOf("encoder.onnx" to enc, "decoder.onnx" to dec, "joiner.onnx" to joi, "tokens.txt" to tok)
        overrides.forEach { (n, b) -> contents[n] = b }
        contents.forEach { (n, b) -> File(staged, n).writeBytes(b) }
        return staged
    }

    @Test fun verifyPassesTheExactFiles() {
        assertEquals(PackVerdict.Ok, StreamingPackInstall.verify(stage(), pack))
    }

    @Test fun verifyRefusesAShortFileBeforeHashingAnything() {
        // The size gate is EXACT: these are pinned files at an immutable commit, so a ±5 % band
        // would only admit a wrong file. A mismatch names the file (a filename is not content).
        val v = StreamingPackInstall.verify(stage("decoder.onnx" to "DECODER".toByteArray()), pack)
        assertEquals(PackVerdict.SizeMismatch("decoder.onnx", 7L, dec.size.toLong()), v)
    }

    @Test fun verifyRefusesASameSizeFileWhoseHashDiffers() {
        val v = StreamingPackInstall.verify(stage("encoder.onnx" to "ENCODEX".toByteArray()), pack)
        assertEquals(PackVerdict.HashMismatch("encoder.onnx"), v)
    }

    @Test fun verifyNamesAMissingFile() {
        val staged = stage()
        File(staged, "tokens.txt").delete()
        assertEquals(PackVerdict.Missing("tokens.txt"), StreamingPackInstall.verify(staged, pack))
    }

    @Test fun installMovesTheFilesWritesTheMarkerAndIsInstalledAfterwards() {
        val root = tmp.newFolder("root")
        val staged = stage()
        StreamingPackInstall.install(staged, root, pack)
        val dir = StreamingPackInstall.installDir(root, pack)
        assertTrue(StreamingPackInstall.isInstalled(dir, pack))
        assertEquals(StreamingPackCatalog.markerText(pack), StreamingPackInstall.marker(dir).readText())
        assertEquals("ENCODER", File(dir, "encoder.onnx").readText())
        assertFalse("the staged copies are moved, not duplicated", File(staged, "encoder.onnx").exists())
        assertFalse("no .tmp dir survives a completed install", StreamingPackInstall.tmpDir(root, pack).exists())
    }

    @Test fun installReplacesAPreviousInstallAtomically() {
        val root = tmp.newFolder("root")
        StreamingPackInstall.install(stage(), root, pack)
        val dir = StreamingPackInstall.installDir(root, pack)
        File(dir, "stale.bin").writeText("old")
        StreamingPackInstall.install(stage(), root, pack)
        assertTrue(StreamingPackInstall.isInstalled(dir, pack))
        assertFalse("the old directory is REPLACED, not merged", File(dir, "stale.bin").exists())
    }

    @Test fun isInstalledIsAMarkerPlusFourExactLengths_neverAHash() {
        val root = tmp.newFolder("root")
        StreamingPackInstall.install(stage(), root, pack)
        val dir = StreamingPackInstall.installDir(root, pack)
        // Same length, different bytes: isInstalled still says yes — it is the session-start
        // read and must be a length read (a 71 MB hash on every session start is not free).
        // The load-time canary and the recognizer's own refusal cover the rest.
        File(dir, "encoder.onnx").writeBytes("ENCODEX".toByteArray())
        assertTrue(StreamingPackInstall.isInstalled(dir, pack))
        // A short file, or a missing marker, says no.
        File(dir, "encoder.onnx").writeBytes("ENC".toByteArray())
        assertFalse(StreamingPackInstall.isInstalled(dir, pack))
        File(dir, "encoder.onnx").writeBytes(enc)
        assertTrue(StreamingPackInstall.isInstalled(dir, pack))
        StreamingPackInstall.marker(dir).delete()
        assertFalse(StreamingPackInstall.isInstalled(dir, pack))
    }

    @Test fun markCorruptRemovesOnlyTheMarker() {
        val root = tmp.newFolder("root")
        StreamingPackInstall.install(stage(), root, pack)
        val dir = StreamingPackInstall.installDir(root, pack)
        StreamingPackInstall.markCorrupt(root, pack)
        assertFalse(StreamingPackInstall.isInstalled(dir, pack))
        assertTrue("the bytes stay; only the verdict is withdrawn", File(dir, "encoder.onnx").exists())
    }

    @Test fun deleteRemovesTheInstallAndAnyTmp() {
        val root = tmp.newFolder("root")
        StreamingPackInstall.install(stage(), root, pack)
        StreamingPackInstall.tmpDir(root, pack).mkdirs()
        StreamingPackInstall.delete(root, pack)
        assertFalse(StreamingPackInstall.installDir(root, pack).exists())
        assertFalse(StreamingPackInstall.tmpDir(root, pack).exists())
    }

    @Test fun sha256HexMatchesTheJdk() {
        val f = tmp.newFile("x")
        f.writeBytes(enc)
        assertEquals(sha(enc), StreamingPackInstall.sha256Hex(f))
    }
}
```

- [ ] **Step 2: Run to see them fail** — `--tests "com.whispereverywhere.transcription.stream.*"`. Expected: compilation FAILS (unresolved package).

- [ ] **Step 3: Implement.**

`StreamingPackCatalog.kt`:

```kotlin
package com.whispereverywhere.transcription.stream

/** One pinned file of a streaming pack: its name at the commit, its EXACT byte count, its sha256. */
data class PackFile(val name: String, val bytes: Long, val sha256: String)

/**
 * A streaming-previewer model pack: four raw files at ONE immutable Hugging Face commit
 * (spec §6). Never the release tarball (310 MB of fp32 + int8 + wavs under a 73 MB badge), never
 * a `WhisperModel` row, never a `pairedArtifact`, never a Play asset pack — the pack is a sibling
 * of the TTS voice, keyed by LANGUAGE, with no tier identity (spec §6, "what a non-whisper pack
 * must NOT touch").
 */
data class StreamingPack(
    val language: String,
    val dirName: String,
    val baseUrl: String,
    val encoder: PackFile,
    val decoder: PackFile,
    val joiner: PackFile,
    val tokens: PackFile,
) {
    val files: List<PackFile> get() = listOf(encoder, decoder, joiner, tokens)
    val totalBytes: Long get() = files.sumOf { it.bytes }
    fun urlOf(file: PackFile): String = baseUrl + file.name
}

/**
 * The packs the previewer can run. One row today: `streaming-zipformer-en-2023-06-26` (66 M,
 * int8, Apache-2.0, LibriSpeech), the four files re-hashed on the PC (rung 1 §1.2) and on the
 * Tab (rung 3 §1.2, §7). A second language is a second row whose licence cell is green first
 * (research §2.5) — no per-language cards, no chooser change.
 */
object StreamingPackCatalog {
    const val ROOT_DIR = "zf-stream"
    const val MARKER = ".installed"
    const val TMP_SUFFIX = ".tmp"

    val EN = StreamingPack(
        language = "en",
        dirName = "en-2023-06-26",
        // resolve/<commit sha>/, the catalog's own rule (WhisperModel.kt:103-108): resolve/main is
        // a MUTABLE ref and a replaced upstream file would brick every download until an update.
        baseUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26/resolve/672fbf1b30579d6585301139bb363f42a0ad4a24/",
        encoder = PackFile("encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx", 71_083_163L, "563fde436d16cf7607cf408cd6b30909819d03162652ef389c2450ced3f45ac1"),
        decoder = PackFile("decoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx", 1_307_236L, "98da299f471e38bb4e1a8df579b8cc9122d6039576a77e357b3c60f17dd83b02"),
        joiner = PackFile("joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx", 259_335L, "d944208d660d67c8d72cd2acaeac971fa5ceb8c80e76c1968148846fedd6e297"),
        tokens = PackFile("tokens.txt", 5_048L, "49e3c2646595fd907228b3c6787069658f67b17377c60aeb8619c4551b2316fb"),
    )

    val packs: List<StreamingPack> = listOf(EN)

    /** The pack for a RESOLVED session language; null for auto (null) and for every language without a row. */
    fun forLanguage(code: String?): StreamingPack? = packs.firstOrNull { it.language == code }

    /** "73 MB" for 72,654,782 B — the house decimal convention (ModelTierCopy: "190 MB" for 190,085,487 B). */
    fun sizeBadge(bytes: Long): String = "${(bytes + 500_000L) / 1_000_000L} MB"

    /** The `.installed` marker's content: the four `sha256␠␠name` lines, written LAST by the installer. */
    fun markerText(pack: StreamingPack): String = pack.files.joinToString("") { "${it.sha256}  ${it.name}\n" }
}
```

`StreamingPackInstall.kt`:

```kotlin
package com.whispereverywhere.transcription.stream

import java.io.File
import java.security.MessageDigest

/** `verify`'s answer. A mismatch names the FILE — a filename is not transcript content. */
sealed class PackVerdict {
    object Ok : PackVerdict()
    data class Missing(val name: String) : PackVerdict()
    data class SizeMismatch(val name: String, val actual: Long, val expected: Long) : PackVerdict()
    data class HashMismatch(val name: String) : PackVerdict()
}

class StreamingPackException(message: String) : Exception(message)

/**
 * The pure half of pack installation — `java.io.File` only, JVM-tested with a TemporaryFolder.
 * The Android half (`StreamingPackManager`) downloads into a staging dir and hands it here.
 *
 * `isInstalled` is a LENGTH read (marker + four exact byte counts), never a hash: it runs on the
 * session-start path. `verify` is the hash, and it runs once, after a download. The marker is
 * written LAST and the temp dir is renamed over the previous install, so a kill at any instant
 * leaves the old install intact or nothing — never a half pack (the TtsModelManager precedent).
 */
object StreamingPackInstall {

    fun installDir(root: File, pack: StreamingPack): File = File(root, pack.dirName)
    fun tmpDir(root: File, pack: StreamingPack): File = File(root, pack.dirName + StreamingPackCatalog.TMP_SUFFIX)
    fun marker(dir: File): File = File(dir, StreamingPackCatalog.MARKER)

    fun isInstalled(dir: File, pack: StreamingPack): Boolean =
        marker(dir).isFile && pack.files.all { f -> File(dir, f.name).let { it.isFile && it.length() == f.bytes } }

    /** Every file's length must EQUAL its pin before anything is hashed (a cheap refusal); then sha256 per file. */
    fun verify(staged: File, pack: StreamingPack): PackVerdict {
        for (f in pack.files) {
            val file = File(staged, f.name)
            if (!file.isFile) return PackVerdict.Missing(f.name)
            if (file.length() != f.bytes) return PackVerdict.SizeMismatch(f.name, file.length(), f.bytes)
        }
        for (f in pack.files) {
            if (!sha256Hex(File(staged, f.name)).equals(f.sha256, ignoreCase = true)) return PackVerdict.HashMismatch(f.name)
        }
        return PackVerdict.Ok
    }

    /** Moves the VERIFIED files from [staged] into place atomically; the marker is written LAST. */
    fun install(staged: File, root: File, pack: StreamingPack) {
        val tmp = tmpDir(root, pack)
        if (tmp.exists()) tmp.deleteRecursively()
        tmp.mkdirs()
        for (f in pack.files) {
            val src = File(staged, f.name)
            val dst = File(tmp, f.name)
            if (!src.renameTo(dst)) {
                src.copyTo(dst, overwrite = true)
                src.delete()
            }
        }
        marker(tmp).writeText(StreamingPackCatalog.markerText(pack))
        val final = installDir(root, pack)
        if (final.exists()) final.deleteRecursively()
        if (!tmp.renameTo(final)) {
            tmp.deleteRecursively()
            throw StreamingPackException("Could not finalize the preview model install")
        }
    }

    fun delete(root: File, pack: StreamingPack) {
        installDir(root, pack).deleteRecursively()
        tmpDir(root, pack).deleteRecursively()
    }

    /** Withdraws the verdict only: `isInstalled` answers false, the Settings row reads Repair, the bytes stay. */
    fun markCorrupt(root: File, pack: StreamingPack) {
        marker(installDir(root, pack)).delete()
    }

    fun sha256Hex(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
```

`StreamingPackManager.kt`:

```kotlin
package com.whispereverywhere.transcription.stream

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Downloads and installs a streaming-previewer pack (spec §6) — the `TtsModelManager` shape
 * (tts/TtsModelManager.kt:52-136): free-space gate before the network, stale DownloadManager rows
 * removed, DownloadManager for the transport (it already follows HF `resolve/<sha>/` redirects
 * for the 190 MB whisper files), then the pure `StreamingPackInstall` for verify + atomic install.
 * Four sequential requests, one per pinned file, into an external staging dir; progress is the
 * cumulative byte count over the pack's total.
 */
class StreamingPackManager(private val context: Context) {

    /** context.filesDir/zf-stream, created if missing. */
    fun root(): File {
        val dir = File(context.filesDir, StreamingPackCatalog.ROOT_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun installDir(pack: StreamingPack): File = StreamingPackInstall.installDir(root(), pack)
    fun isInstalled(pack: StreamingPack): Boolean = StreamingPackInstall.isInstalled(installDir(pack), pack)
    fun installedDir(pack: StreamingPack): File? = if (isInstalled(pack)) installDir(pack) else null
    fun markCorrupt(pack: StreamingPack) = StreamingPackInstall.markCorrupt(root(), pack)

    fun delete(pack: StreamingPack) {
        StreamingPackInstall.delete(root(), pack)
        stagingDir(pack).deleteRecursively()
        removeStaleDownloads(downloadManager(), pack)
    }

    /** Download + verify + atomically install. Main-safe (Dispatchers.IO). Throws [StreamingPackException]. */
    suspend fun download(pack: StreamingPack, onProgress: (soFar: Long, total: Long) -> Unit): Unit =
        withContext(Dispatchers.IO) {
            val dm = downloadManager()
            val staging = stagingDir(pack).apply { mkdirs() }
            val required = (pack.totalBytes * 1.1).toLong()
            val extFree = runCatching { StatFs(staging.absolutePath).availableBytes }.getOrDefault(Long.MAX_VALUE)
            val intFree = runCatching { StatFs(root().absolutePath).availableBytes }.getOrDefault(Long.MAX_VALUE)
            if (extFree < required || intFree < required) {
                throw StreamingPackException(
                    "Not enough free storage: the preview model needs about ${(2 * required) / 1_000_000} MB free during install."
                )
            }
            removeStaleDownloads(dm, pack)
            var doneBytes = 0L
            for (f in pack.files) {
                val dest = File(staging, f.name)
                if (dest.exists()) dest.delete()
                fetchOne(dm, pack, f, dest) { soFar -> onProgress(doneBytes + soFar, pack.totalBytes) }
                doneBytes += f.bytes
                onProgress(doneBytes, pack.totalBytes)
            }
            when (val v = StreamingPackInstall.verify(staging, pack)) {
                PackVerdict.Ok -> Unit
                is PackVerdict.Missing -> fail(staging, "Preview model file missing after download: ${v.name}")
                is PackVerdict.SizeMismatch ->
                    fail(staging, "Preview model file size mismatch: ${v.name} (${v.actual} of ${v.expected} bytes)")
                is PackVerdict.HashMismatch -> fail(staging, "Preview model file failed integrity verification: ${v.name}")
            }
            StreamingPackInstall.install(staging, root(), pack)
            staging.deleteRecursively()
        }

    private fun fail(staging: File, message: String): Nothing {
        staging.deleteRecursively()
        throw StreamingPackException(message)
    }

    private suspend fun fetchOne(dm: DownloadManager, pack: StreamingPack, f: PackFile, dest: File, onSoFar: (Long) -> Unit) {
        val request = DownloadManager.Request(pack.urlOf(f).toUri())
            .setTitle("Live words preview model")
            .setDescription("Downloading ${f.name}")
            .setDestinationInExternalFilesDir(
                context, Environment.DIRECTORY_DOWNLOADS, "${StreamingPackCatalog.ROOT_DIR}/${pack.dirName}/${f.name}",
            )
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
        val id = dm.enqueue(request)
        var keepRow = false
        try {
            while (true) {
                val status = dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
                    if (!c.moveToFirst()) throw StreamingPackException("Preview model download entry disappeared")
                    onSoFar(c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)))
                    val s = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    when (s) {
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            val localUri = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
                            val src = localUri?.let { File(Uri.parse(it).path ?: "") }
                            if (src == null || !src.exists()) {
                                throw StreamingPackException("Cannot resolve downloaded file for ${f.name}")
                            }
                            if (src.absolutePath != dest.absolutePath) {
                                if (dest.exists()) dest.delete()
                                if (!src.renameTo(dest)) {
                                    src.copyTo(dest, overwrite = true)
                                    src.delete()
                                }
                            }
                        }
                        DownloadManager.STATUS_FAILED -> {
                            val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                            throw StreamingPackException("Preview model download failed (reason=$reason) on ${f.name}")
                        }
                        else -> Unit // PENDING / RUNNING / PAUSED
                    }
                    s
                }
                if (status == DownloadManager.STATUS_SUCCESSFUL) return
                delay(POLL_INTERVAL_MS)
            }
        } catch (ce: kotlinx.coroutines.CancellationException) {
            keepRow = true
            throw ce
        } finally {
            if (!keepRow) dm.remove(id)
        }
    }

    private fun stagingDir(pack: StreamingPack): File =
        File(File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), StreamingPackCatalog.ROOT_DIR), pack.dirName)

    private fun downloadManager(): DownloadManager =
        context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    private fun removeStaleDownloads(dm: DownloadManager, pack: StreamingPack) {
        val urls = pack.files.map { pack.urlOf(it) }.toSet()
        try {
            dm.query(DownloadManager.Query()).use { c ->
                val idIdx = c.getColumnIndex(DownloadManager.COLUMN_ID)
                val uriIdx = c.getColumnIndex(DownloadManager.COLUMN_URI)
                if (idIdx < 0 || uriIdx < 0) return
                while (c.moveToNext()) {
                    if (c.getString(uriIdx) in urls) dm.remove(c.getLong(idIdx))
                }
            }
        } catch (t: Throwable) {
            android.util.Log.w("WE-DIAG", "stream-pack: removeStaleDownloads failed", t)
        }
    }

    private companion object {
        const val POLL_INTERVAL_MS = 300L
    }
}
```

In `WhisperEverywhereApp.kt`, directly after the `whisperModelManager` block (`:35-37`):

```kotlin
    /** 4.4.0: the streaming-previewer pack (spec §6) — a sibling of the TTS voice, keyed by language. */
    val streamingPackManager: com.whispereverywhere.transcription.stream.StreamingPackManager by lazy {
        com.whispereverywhere.transcription.stream.StreamingPackManager(this)
    }
```

- [ ] **Step 4: Run to see them pass** (`--tests "com.whispereverywhere.transcription.stream.*"`). Expected: 16 tests, `failures=0`. Then `assembleDebug`.

- [ ] **Step 5: Whole suite, then commit.** Expected: Task 1's count + 16 (2 new suites).

```
feat(stream): the streaming pack — four pinned files at an immutable commit, verified and installed atomically

StreamingPackCatalog carries the en-2023-06-26 files (sizes, sha256s, the resolve/<sha>/ base)
as literals; StreamingPackInstall is the pure verify (exact size, then hash) and the
marker-last atomic install; StreamingPackManager is the TtsModelManager download shape over
DownloadManager. Exposed on the App beside whisperModelManager.
```

**Battery:** (1) change the encoder's byte count in the catalog by one → `theEnglishPackIsTheFourFiles…` RED; revert. (2) make `verify` skip the length loop → `verifyRefusesAShortFileBeforeHashing…` RED (it becomes a HashMismatch); revert. (3) make `isInstalled` ignore the marker → `isInstalledIsAMarkerPlusFourExactLengths…` RED; revert. (4) make `sizeBadge` truncate (drop the `+ 500_000L`) → `theBadgeFollowsTheHouseDecimalConvention` RED; revert.

---

### Task 3 (P2): The recognizer seam, the measured constants, and the load-time canary

**Size:** 0.5 day. **Ruling hooks:** R1 — the canary is the only SME guard; nothing here persists a verdict.

**Files:**
- Create: `app/src/main/java/com/whispereverywhere/transcription/stream/StreamingPreviewTuning.kt`
- Create: `app/src/main/java/com/whispereverywhere/transcription/stream/PreviewRecognizer.kt`
- Create: `app/src/main/java/com/whispereverywhere/transcription/stream/PreviewCanary.kt`
- Test: `app/src/test/java/com/whispereverywhere/transcription/stream/ScriptedRecognizer.kt` (the shared fake, no `@Test`), `StreamingPreviewTuningTest.kt`, `PreviewCanaryTest.kt` (create)

**Interfaces (Tasks 4, 7 consume):**
- `object StreamingPreviewTuning { NUM_THREADS = 2; PAD_MS = 500L; SAMPLE_RATE = 16_000; CHUNK_SAMPLES = 512; QUEUE_CAPACITY = 128; RETAIN_RING_MS = 3_000L; MAX_CONSECUTIVE_FAILURES = 3; BYTES_PER_MS = 32; padSamples(); ringBytes() }`
- `interface PreviewStream { acceptWaveform(FloatArray); inputFinished(); release() }`
- `class PreviewResult(text, tokens: List<String>, timestamps: FloatArray) { companion EMPTY }`
- `interface PreviewRecognizer { createStream(); isReady(s); decode(s); result(s); release() }`
- `interface PreviewRecognizerFactory { load(dir, pack, numThreads): PreviewRecognizer; sherpaVersion(); ortVersion() }`
- `sealed class CanaryVerdict { Pass(outLen, decodes); Fail(outLen, decodes); NoClip } .code`
- `object PreviewCanary { run(recognizer, clip): CanaryVerdict; passes(text) }`

- [ ] **Step 1: Write the shared fake** `ScriptedRecognizer.kt` (test sources; Task 4 reuses it):

```kotlin
package com.whispereverywhere.transcription.stream

/**
 * A scripted stand-in for the sherpa `OnlineRecognizer`, shaped by the measurements: the FIRST
 * decode needs `T = 45` frames (7,200 samples, encoder metadata `T=45`, rung 3 §7) and every
 * later one 320 ms (5,120 samples, `decode_chunk_len=32`) — so 9 of every 10 32 ms feeds leave
 * `isReady` false, exactly as measured (rung 1 §3.1). [texts] is the cumulative text after the
 * k-th decode (1-based; the last entry repeats); tokens carry the AAR's LEADING SPACE, never
 * U+2581 (rung 3 §2.2), and [timestampsOf] supplies the per-token seconds for the trim tests.
 * NEVER references com.k2fsa: the AAR's static init loads a native library.
 */
class ScriptedRecognizer(
    private val texts: List<String>,
    private val timestampsOf: (String) -> FloatArray = { t -> FloatArray(tokensOf(t).size) { i -> 0.32f * (i + 1) } },
    private val failDecodesFrom: Int = Int.MAX_VALUE,   // every decode whose 1-based global index >= this throws
    private val canaryText: String? = null,             // when set, the FIRST stream ever created answers this text (the canary's)
) : PreviewRecognizer {

    class Stream : PreviewStream {
        var samples = 0L
        var consumed = 0L
        var decodes = 0
        var finished = false
        var released = false
        val fed = mutableListOf<Int>()               // per-call sample counts, in order
        override fun acceptWaveform(samples: FloatArray) { this.samples += samples.size; fed += samples.size }
        override fun inputFinished() { finished = true }
        override fun release() { released = true }
    }

    val streams = mutableListOf<Stream>()
    var totalDecodes = 0
    var released = false

    override fun createStream(): PreviewStream = Stream().also { streams += it }

    override fun isReady(stream: PreviewStream): Boolean {
        val s = stream as Stream
        if (s.released) throw IllegalStateException("released stream")
        val need = if (s.decodes == 0) FIRST_DECODE_SAMPLES else CHUNK_SHIFT_SAMPLES
        return s.samples - s.consumed >= need
    }

    override fun decode(stream: PreviewStream) {
        val s = stream as Stream
        val need = if (s.decodes == 0) FIRST_DECODE_SAMPLES else CHUNK_SHIFT_SAMPLES
        s.consumed += need
        s.decodes++
        totalDecodes++
        if (totalDecodes >= failDecodesFrom) throw IllegalStateException("scripted decode failure")
    }

    override fun result(stream: PreviewStream): PreviewResult {
        val s = stream as Stream
        if (s.decodes == 0) return PreviewResult.EMPTY
        if (canaryText != null && s === streams.firstOrNull()) {
            return PreviewResult(canaryText, tokensOf(canaryText), timestampsOf(canaryText))
        }
        if (texts.isEmpty()) return PreviewResult.EMPTY
        val text = texts[minOf(s.decodes, texts.size) - 1]
        return PreviewResult(text, tokensOf(text), timestampsOf(text))
    }

    override fun release() { released = true }

    companion object {
        const val FIRST_DECODE_SAMPLES = 7_200L
        const val CHUNK_SHIFT_SAMPLES = 5_120L
        /** The AAR's shape: each word piece arrives with a LEADING SPACE. */
        fun tokensOf(text: String): List<String> = text.split(' ').filter { it.isNotEmpty() }.map { " $it" }
    }
}

/** A factory over one scripted recognizer, or one that throws at load. */
class ScriptedFactory(private val recognizer: PreviewRecognizer?, private val throwAtLoad: Boolean = false) : PreviewRecognizerFactory {
    var loads = 0
    var lastThreads = -1
    override fun load(dir: java.io.File, pack: StreamingPack, numThreads: Int): PreviewRecognizer {
        loads++
        lastThreads = numThreads
        if (throwAtLoad) throw IllegalStateException("scripted load failure")
        return requireNotNull(recognizer)
    }
    override fun sherpaVersion(): String = "1.13.7"
    override fun ortVersion(): String = "1.27.1"
}
```

- [ ] **Step 2: Write the failing tests.**

`StreamingPreviewTuningTest.kt`:

```kotlin
package com.whispereverywhere.transcription.stream

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The measured constants (spec §2), as literals with their provenance. Written as literals
 * here, not derived, so a re-tune in either direction is a red test and not a quiet drift.
 */
class StreamingPreviewTuningTest {

    @Test fun twoThreadsIsTheNumber() {
        // rung 3 §5.4: max-pace RTF 0.054 @ 2, 0.072 @ 4 (SLOWER — ORT hand-offs on 16 ms bursts),
        // 0.051 @ 1. Two.
        assertEquals(2, StreamingPreviewTuning.NUM_THREADS)
    }

    @Test fun thePadIsFiveHundredMilliseconds() {
        // rung 1 §4: the canary loses `VE` at 450 ms and keeps `FIVE` at 500, on the PC and on the
        // Tab (rung 3 §5.3); 800 buys nothing. 8,000 zero samples at 16 kHz.
        assertEquals(500L, StreamingPreviewTuning.PAD_MS)
        assertEquals(8_000, StreamingPreviewTuning.padSamples())
    }

    @Test fun theFeedIsTheAppsOwnChunking() {
        // util/StreamingAudioRecorder.kt:80 — 1,024-byte reads = 512 samples = 32 ms.
        assertEquals(16_000, StreamingPreviewTuning.SAMPLE_RATE)
        assertEquals(512, StreamingPreviewTuning.CHUNK_SAMPLES)
        assertEquals(32, StreamingPreviewTuning.BYTES_PER_MS)
    }

    @Test fun theQueueAndTheRingAreBounded() {
        // 128 chunks ≈ 4.1 s (LiveTranscriptionEngine.DEFAULT_MAX_BACKLOG); the ring holds
        // CommitCadencePolicy.CAP_CUT_MAX_RETAIN_MS of PCM16 = 96,000 bytes.
        assertEquals(128, StreamingPreviewTuning.QUEUE_CAPACITY)
        assertEquals(3_000L, StreamingPreviewTuning.RETAIN_RING_MS)
        assertEquals(96_000, StreamingPreviewTuning.ringBytes())
        assertEquals(3, StreamingPreviewTuning.MAX_CONSECUTIVE_FAILURES)
    }
}
```

`PreviewCanaryTest.kt`:

```kotlin
package com.whispereverywhere.transcription.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The load-time canary — RULING ASSUMED (R1): the ONLY guard against the FEAT_SME silent
 * miscompute (sherpa-onnx #3845: EMPTY text for the whole stream on SM8850 + ORT 1.27.0;
 * #3791: `"MY WOMAN"` for a five-word clip on an M4). The verdict rule is
 * `GpuCanaryPolicy.canaryPasses`, unchanged: it already fails empty, fails garbage (fewer than
 * 4 of 5 positions), fails a runaway, and passes the measured `ONE TWO THREE FOUR FIVE`
 * (rung 3 §4: exact in 8 of 8 runs).
 */
class PreviewCanaryTest {

    /** 2.560 s of "audio" — the canary clip's length (40,960 frames, rung 1 §1.6). Values are irrelevant to the fake. */
    private val clip = FloatArray(40_960)

    @Test fun theMeasuredTextPasses() {
        val rec = ScriptedRecognizer(listOf("ONE", "ONE TWO THREE", "ONE TWO THREE FOUR", "ONE TWO THREE FOUR FIVE"))
        val v = PreviewCanary.run(rec, clip)
        assertTrue(v is CanaryVerdict.Pass)
        assertEquals("pass", v.code)
        assertEquals(23, (v as CanaryVerdict.Pass).outLen)
    }

    @Test fun emptyTextIsTheSmeSignatureAndFails() {
        val rec = ScriptedRecognizer(listOf(""))
        val v = PreviewCanary.run(rec, clip)
        assertTrue(v is CanaryVerdict.Fail)
        assertEquals("fail", v.code)
        assertEquals(0, (v as CanaryVerdict.Fail).outLen)
    }

    @Test fun garbageFails() {
        assertTrue(PreviewCanary.run(ScriptedRecognizer(listOf("MY WOMAN")), clip) is CanaryVerdict.Fail)
    }

    @Test fun oneDroppedDigitIsOrdinaryAndPasses() {
        // GpuCanaryPolicy.MIN_MATCHES = 4 — the tolerance the GPU canary was written with.
        assertTrue(PreviewCanary.run(ScriptedRecognizer(listOf("ONE TWO THREE FOUR")), clip) is CanaryVerdict.Pass)
    }

    @Test fun theClipIsFedInAppSizedChunksThenPaddedThenFinishedThenDrained() {
        val rec = ScriptedRecognizer(listOf("ONE TWO THREE FOUR FIVE"))
        PreviewCanary.run(rec, clip)
        val s = rec.streams.single()
        // 40,960 samples = 80 chunks of 512, then ONE pad of 8,000 zeros.
        assertEquals(81, s.fed.size)
        assertTrue(s.fed.dropLast(1).all { it == 512 })
        assertEquals(8_000, s.fed.last())
        assertTrue("inputFinished after the pad", s.finished)
        // 48,960 samples: the first decode at 7,200, then every 5,120 → 1 + (48,960 − 7,200) / 5,120 = 9 decodes.
        assertEquals(9, s.decodes)
        assertTrue("the throwaway stream is released", s.released)
        assertFalse("the recognizer itself is NOT released by the canary", rec.released)
    }

    @Test fun aMissingClipIsNoVerdict() {
        val rec = ScriptedRecognizer(listOf("ONE TWO THREE FOUR FIVE"))
        assertEquals(CanaryVerdict.NoClip, PreviewCanary.run(rec, null))
        assertEquals(CanaryVerdict.NoClip, PreviewCanary.run(rec, FloatArray(0)))
        assertEquals("none", CanaryVerdict.NoClip.code)
        assertTrue("no stream is opened for no clip", rec.streams.isEmpty())
    }

    @Test fun theStreamIsReleasedEvenWhenDecodeThrows() {
        val rec = ScriptedRecognizer(listOf("ONE"), failDecodesFrom = 2)
        val thrown = runCatching { PreviewCanary.run(rec, clip) }.exceptionOrNull()
        assertTrue(thrown is IllegalStateException)
        assertTrue(rec.streams.single().released)
    }

    @Test fun theVerdictRuleIsTheGpuCanarys() {
        assertTrue(PreviewCanary.passes("ONE TWO THREE FOUR FIVE"))
        assertTrue(PreviewCanary.passes("one two three four five"))
        assertFalse(PreviewCanary.passes(""))
        assertFalse(PreviewCanary.passes("   "))
        assertFalse(PreviewCanary.passes("MY WOMAN"))
    }
}
```

- [ ] **Step 3: Run to see them fail** (`--tests "com.whispereverywhere.transcription.stream.*"`). Expected: compilation FAILS.

- [ ] **Step 4: Implement.**

`StreamingPreviewTuning.kt`:

```kotlin
package com.whispereverywhere.transcription.stream

/**
 * The previewer's constants — every one a MEASUREMENT with its provenance (spec §2), pinned by
 * StreamingPreviewTuningTest. Nothing here is inferred.
 */
object StreamingPreviewTuning {
    /** rung 3 §5.4: RTF 0.054 @ 2 threads, 0.072 @ 4 (slower), 0.051 @ 1. Two. */
    const val NUM_THREADS = 2

    /** rung 1 §4 / rung 3 §5.3: the canary's `VE` is lost at 450 ms and kept at 500; 800 buys nothing. */
    const val PAD_MS = 500L

    /** The capture pipeline's rate (SegmentTiming.SAMPLE_RATE_HZ). */
    const val SAMPLE_RATE = 16_000

    /** StreamingAudioRecorder reads 1,024 bytes = 512 samples = 32 ms. */
    const val CHUNK_SAMPLES = 512

    /** PCM16 mono @ 16 kHz: 32 bytes per millisecond (LocalWhisperEngine.BYTES_PER_MS). */
    const val BYTES_PER_MS = 32

    /** 128 chunks ≈ 4.1 s — LiveTranscriptionEngine.DEFAULT_MAX_BACKLOG. Overflow sheds, never blocks. */
    const val QUEUE_CAPACITY = 128

    /** The retained-tail ring: CommitCadencePolicy.CAP_CUT_MAX_RETAIN_MS of PCM. */
    const val RETAIN_RING_MS = 3_000L

    /** Consecutive decode failures inside one segment before the previewer disables itself for the process. */
    const val MAX_CONSECUTIVE_FAILURES = 3

    fun padSamples(): Int = (PAD_MS * SAMPLE_RATE / 1000L).toInt()
    fun ringBytes(): Int = (RETAIN_RING_MS * BYTES_PER_MS).toInt()
}
```

`PreviewRecognizer.kt`:

```kotlin
package com.whispereverywhere.transcription.stream

import java.io.File

/**
 * THE SEAM over sherpa-onnx's `OnlineRecognizer` / `OnlineStream` — the five calls the previewer
 * makes, and nothing else. Exists so the engine's loop, the canary and the tee are JVM-testable
 * with `ScriptedRecognizer`: the AAR's classes load `libsherpa-onnx-jni.so` in a static
 * initialiser, so no test may reference `com.k2fsa` (TtsEngineSeamTest's rule). The production
 * adapter is `SherpaPreviewRecognizer` (Task 7), the only file in the app that imports the AAR's
 * streaming classes.
 */
interface PreviewStream {
    /** Float32 samples in [-1, 1] at 16 kHz (AudioMath.pcm16ToFloat's output). */
    fun acceptWaveform(samples: FloatArray)
    fun inputFinished()
    fun release()
}

/**
 * `getResult()` — the CUMULATIVE text of the open stream, its tokens (each with the AAR's
 * LEADING SPACE, rung 3 §2.2) and per-token timestamps in seconds from the stream's start.
 */
class PreviewResult(val text: String, val tokens: List<String>, val timestamps: FloatArray) {
    companion object {
        val EMPTY = PreviewResult("", emptyList(), FloatArray(0))
    }
}

interface PreviewRecognizer {
    fun createStream(): PreviewStream
    fun isReady(stream: PreviewStream): Boolean
    fun decode(stream: PreviewStream)
    fun result(stream: PreviewStream): PreviewResult
    fun release()
}

interface PreviewRecognizerFactory {
    /** Loads the recognizer over the four files in [dir]; throws when the files are refused. */
    fun load(dir: File, pack: StreamingPack, numThreads: Int): PreviewRecognizer
    fun sherpaVersion(): String
    fun ortVersion(): String
}
```

`PreviewCanary.kt`:

```kotlin
package com.whispereverywhere.transcription.stream

import com.whispereverywhere.transcription.GpuCanaryPolicy

/** The canary's answer; `code` is what the `stream-open:` line prints. */
sealed class CanaryVerdict {
    abstract val code: String
    data class Pass(val outLen: Int, val decodes: Int) : CanaryVerdict() { override val code: String get() = "pass" }
    data class Fail(val outLen: Int, val decodes: Int) : CanaryVerdict() { override val code: String get() = "fail" }
    object NoClip : CanaryVerdict() { override val code: String get() = "none" }
}

/**
 * The load-time canary (spec §7.2) — RULING ASSUMED (R1): the ONLY guard against the FEAT_SME
 * silent-miscompute class. sherpa-onnx #3845 (SM8850, ORT 1.27.0): EMPTY text for the whole
 * stream, no crash, no NaN; #3791 (M4): `"MY WOMAN"` for a five-word clip. Neither test device
 * has SME (rung 3 §1.1), so on the devices we own this always passes — the rule exists for the
 * `8elite5_galaxy` census family (NpuFleetCensus.kt:142-145) we cannot test.
 *
 * Feeds the bundled `canary_digits.wav` (CanaryAudio.samples(), the GPU canary's own reader:
 * 2.560 s of "one two three four five") in the app's 512-sample chunks, pads PAD_MS, finishes,
 * drains, and scores with `GpuCanaryPolicy.canaryPasses` — unchanged, because it already
 * answers the three signatures (empty / garbage / runaway) and passes the measured
 * `ONE TWO THREE FOUR FIVE`. A null clip is NO VERDICT, not a failure — and, unlike the GPU
 * canary, nothing permanent hangs on it: the engine treats "none" as off for this process.
 *
 * The verdict is never persisted (no preference, no per-(versionCode, pack) latch): the tee is
 * additive, so a false negative costs one process of blank strips and nothing typed.
 */
object PreviewCanary {

    fun run(recognizer: PreviewRecognizer, clip: FloatArray?): CanaryVerdict {
        if (clip == null || clip.isEmpty()) return CanaryVerdict.NoClip
        val stream = recognizer.createStream()
        var decodes = 0
        try {
            var i = 0
            while (i < clip.size) {
                val n = minOf(StreamingPreviewTuning.CHUNK_SAMPLES, clip.size - i)
                stream.acceptWaveform(clip.copyOfRange(i, i + n))
                while (recognizer.isReady(stream)) {
                    recognizer.decode(stream)
                    decodes++
                }
                i += n
            }
            stream.acceptWaveform(FloatArray(StreamingPreviewTuning.padSamples()))
            stream.inputFinished()
            while (recognizer.isReady(stream)) {
                recognizer.decode(stream)
                decodes++
            }
            val text = recognizer.result(stream).text
            return if (passes(text)) CanaryVerdict.Pass(text.length, decodes) else CanaryVerdict.Fail(text.length, decodes)
        } finally {
            stream.release()
        }
    }

    /** The GPU canary's rule, verbatim (GpuCanaryPolicy.canaryPasses): its normaliser lowercases, so ALL CAPS passes. */
    fun passes(text: String): Boolean = GpuCanaryPolicy.canaryPasses(text)
}
```

- [ ] **Step 5: Run to see them pass** (`--tests "com.whispereverywhere.transcription.stream.*"`). Expected: 16 + 4 + 8 = 28 tests, `failures=0`. `assembleDebug`.

- [ ] **Step 6: Whole suite, then commit.** Expected: Task 2's count + 12 (2 new suites).

```
feat(stream): the recognizer seam, the measured constants, and the load-time canary

PreviewRecognizer/PreviewStream/PreviewResult are the five sherpa calls the previewer makes,
faked by ScriptedRecognizer (T=45 first decode, 320 ms shift, leading-space tokens);
StreamingPreviewTuning pins 2 threads / 500 ms pad / 512-sample chunks / 128-chunk queue / 3 s
ring; PreviewCanary feeds canary_digits.wav, pads, drains and scores with
GpuCanaryPolicy.canaryPasses — empty text is the #3845 signature.
```

**Battery:** (1) change `PAD_MS` to `450L` → `thePadIsFiveHundredMilliseconds` RED; revert. (2) remove the pad feed from `PreviewCanary.run` → `theClipIsFedInAppSizedChunks…` RED (80 feeds, not 81); revert. (3) make `run` return `Pass` for empty text → `emptyTextIsTheSmeSignatureAndFails` RED; revert. (4) drop the `finally` release → `theStreamIsReleasedEvenWhenDecodeThrows` RED; revert.

---

### Task 4 (P3): `StreamingPreviewEngine` — the feed, the partials, the commit hook

**Size:** 2 days. **Ruling hooks:** none (the engine is ruling-neutral).

**Files:**
- Create: `app/src/main/java/com/whispereverywhere/transcription/stream/PreviewText.kt`
- Create: `app/src/main/java/com/whispereverywhere/transcription/stream/PcmRing.kt`
- Create: `app/src/main/java/com/whispereverywhere/transcription/stream/StreamDiag.kt`
- Create: `app/src/main/java/com/whispereverywhere/transcription/stream/StreamingPreviewEngine.kt` (holds the `LocalPreview` interface too)
- Test: `app/src/test/java/com/whispereverywhere/transcription/stream/PreviewTextTest.kt`, `PcmRingTest.kt`, `StreamDiagTest.kt`, `StreamingPreviewEngineTest.kt` (create)

**Interfaces (Tasks 5, 7 consume):**
- `interface LocalPreview { open(onPartial: (String) -> Unit); sendAudio(pcm); commit(seq, retainMs, onFrozen: (Long, String) -> Unit); close() }`
- `class StreamingPreviewEngine(factory, canaryClip, onLoadFailure = {}, executor, clock, nanoClock, queueCapacity, log, enterExecutorThread) : LocalPreview { val disabled: Boolean; fun isWarm(); fun warm(packDir, pack); fun release() }`
- `object StreamDiag { openLine(...); timingLine(...); gateLine(...); rtf(decodeMs, audioMs); percentileUs(samples, p) }`
- `object PreviewText { normalize(raw); before(result, cutS) }`; `class PcmRing(capacityBytes) { write(pcm); last(bytes); clear() }`

- [ ] **Step 1: Write the failing tests.**

`PreviewTextTest.kt`:

```kotlin
package com.whispereverywhere.transcription.stream

import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewTextTest {

    @Test fun normalizeTrimsAndLowercases() {
        // The model emits ALL CAPS (rung 1 §1.3: 497 uppercase pieces, no lowercase, no digits);
        // the strip must not shout. sherpa's text may carry a leading space from the first token.
        assertEquals("one two three four five", PreviewText.normalize(" ONE TWO THREE FOUR FIVE"))
        assertEquals("", PreviewText.normalize("   "))
    }

    @Test fun beforeKeepsTheTokensThatEndBeforeTheCutAndJoinsByConcatenation() {
        // The AAR's tokens carry a LEADING SPACE (" F", "OUR" — rung 3 §2.2): a word is the run of
        // pieces from one leading-space piece to the next, so the join is a bare concatenation.
        val r = PreviewResult(
            "ONE TWO THREE FOUR FIVE",
            listOf(" ONE", " TWO", " THREE", " F", "OUR", " FI", "VE"),
            floatArrayOf(0.96f, 1.28f, 1.48f, 2.04f, 2.20f, 2.40f, 2.68f),   // rung 3 §5.3's word ends
        )
        assertEquals("one two three four", PreviewText.before(r, 2.30f))
        assertEquals("one two three f", PreviewText.before(r, 2.10f))
        assertEquals("", PreviewText.before(r, 0.5f))
        assertEquals("one two three four five", PreviewText.before(r, 9f))
    }

    @Test fun beforeFallsBackToTheWholeTextWhenTimestampsAreShort() {
        // A result whose timestamps do not cover its tokens cannot be trimmed honestly; the whole
        // (normalized) text is better than a silently truncated one.
        val r = PreviewResult("A B", listOf(" A", " B"), floatArrayOf(0.5f))
        assertEquals("a b", PreviewText.before(r, 0.1f))
    }
}
```

`PcmRingTest.kt`:

```kotlin
package com.whispereverywhere.transcription.stream

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PcmRingTest {

    private fun bytes(from: Int, n: Int) = ByteArray(n) { (from + it).toByte() }

    @Test fun lastReturnsTheNewestBytesAcrossTheWrap() {
        val ring = PcmRing(8)
        ring.write(bytes(0, 6))          // 0..5
        ring.write(bytes(6, 6))          // 6..11 — wraps
        assertArrayEquals(bytes(4, 8), ring.last(8))
        assertArrayEquals(bytes(8, 4), ring.last(4))
    }

    @Test fun lastNeverReturnsMoreThanWasWrittenAndIsEvenSized() {
        val ring = PcmRing(8)
        ring.write(bytes(0, 3))
        assertArrayEquals(bytes(1, 2), ring.last(100))   // 3 written; the NEWEST even count (a PCM16 sample is never split)
        assertEquals(0, PcmRing(8).last(4).size)
    }

    @Test fun aWriteLargerThanTheRingKeepsItsTail() {
        val ring = PcmRing(4)
        ring.write(bytes(0, 10))
        assertArrayEquals(bytes(6, 4), ring.last(4))
    }

    @Test fun clearForgetsEverything() {
        val ring = PcmRing(8)
        ring.write(bytes(0, 8))
        ring.clear()
        assertEquals(0, ring.last(8).size)
    }
}
```

`StreamDiagTest.kt`:

```kotlin
package com.whispereverywhere.transcription.stream

import org.junit.Assert.assertEquals
import org.junit.Test

/** The three greppable lines, byte-exact (SegmentTimingTest's discipline). Numbers and codes only — never text. */
class StreamDiagTest {

    @Test fun openLineMatchesTheGreppableFormatExactly() {
        assertEquals(
            "stream-open: sherpa=1.13.7 ort=1.27.1 threads=2 provider=cpu loadMs=811 canary=pass canaryMs=212 outLen=23 load=ok",
            StreamDiag.openLine("1.13.7", "1.27.1", 2, 811L, "pass", 212L, 23, "ok"),
        )
    }

    @Test fun timingLineMatchesTheGreppableFormatExactly() {
        assertEquals(
            "stream-timing: seq=4 audio=2560 decodes=9 decodeMs=310 p50us=34000 p99us=46000 rtf=0.121 partials=4 firstPartialMs=480 padMs=500 shed=0 retract=0",
            StreamDiag.timingLine(4L, 2560L, 9, 310L, 34_000L, 46_000L, 0.1211, 4, 480L, 500L, false, 0),
        )
        assertEquals(
            "stream-timing: seq=0 audio=0 decodes=0 decodeMs=0 p50us=0 p99us=0 rtf=0.000 partials=0 firstPartialMs=-1 padMs=500 shed=1 retract=2",
            StreamDiag.timingLine(0L, 0L, 0, 0L, 0L, 0L, 0.0, 0, -1L, 500L, true, 2),
        )
    }

    @Test fun gateLineMatchesTheGreppableFormatExactly() {
        assertEquals(
            "stream-gate: lang=en pack=1 cloud=0 batch=0 enabled=1 ready=1 -> preview=1",
            StreamDiag.gateLine("en", true, false, false, true, true, true),
        )
        assertEquals(
            "stream-gate: lang=auto pack=1 cloud=0 batch=0 enabled=1 ready=1 -> preview=0",
            StreamDiag.gateLine(null, true, false, false, true, true, false),
        )
    }

    @Test fun rtfIsZeroSafe() {
        assertEquals(0.0, StreamDiag.rtf(100L, 0L), 0.0)
        assertEquals(0.125, StreamDiag.rtf(320L, 2560L), 1e-9)
    }

    @Test fun percentileIsNearestRankOnASortedCopy() {
        val s = listOf(50L, 10L, 40L, 20L, 30L)
        assertEquals(30L, StreamDiag.percentileUs(s, 0.50))
        assertEquals(50L, StreamDiag.percentileUs(s, 0.99))
        assertEquals(10L, StreamDiag.percentileUs(s, 0.0))
        assertEquals(0L, StreamDiag.percentileUs(emptyList(), 0.5))
    }
}
```

`StreamingPreviewEngineTest.kt`:

```kotlin
package com.whispereverywhere.transcription.stream

import com.whispereverywhere.transcription.SameThreadExecutorService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/**
 * The previewer's loop over the scripted recognizer, with the measurements as fixtures: the
 * canary's four partials (rung 3 §5.3 — `ONE`, `ONE TWO THREE`, `… FOUR`, `… FIVE`, the last
 * from the pad), the T=45 / 320 ms decode cadence, the leading-space tokens, the 500 ms pad.
 * Executor: SameThreadExecutorService (LocalWhisperEngineTest's), so every posted task runs
 * inline and the assertions read a settled engine.
 */
class StreamingPreviewEngineTest {

    private class ManualExecutor : AbstractExecutorService() {
        val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks.addLast(command) }
        fun runAll() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
        override fun shutdown() = Unit
        override fun shutdownNow(): MutableList<Runnable> = mutableListOf()
        override fun isShutdown() = false
        override fun isTerminated() = false
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = true
    }

    private companion object {
        const val CANARY = "ONE TWO THREE FOUR FIVE"
        val CANARY_PARTIALS = listOf("ONE", "ONE TWO THREE", "ONE TWO THREE FOUR", "ONE TWO THREE FOUR FIVE")
    }

    private var now = 0L
    private val logs = mutableListOf<String>()
    private val emitted = mutableListOf<String>()
    private val dir = File("unused")
    private val pack = StreamingPackCatalog.EN

    private fun engine(
        rec: PreviewRecognizer?,
        executor: ExecutorService = SameThreadExecutorService(),
        clip: FloatArray? = FloatArray(40_960),
        factory: PreviewRecognizerFactory = ScriptedFactory(rec),
        capacity: Int = StreamingPreviewTuning.QUEUE_CAPACITY,
        onLoadFailure: () -> Unit = {},
    ) = StreamingPreviewEngine(
        factory = factory, canaryClip = { clip }, onLoadFailure = onLoadFailure, executor = executor,
        clock = { now }, nanoClock = { 0L }, queueCapacity = capacity, log = { logs += it }, enterExecutorThread = {},
    )

    private fun warmOpen(rec: PreviewRecognizer, executor: ExecutorService = SameThreadExecutorService(), capacity: Int = StreamingPreviewTuning.QUEUE_CAPACITY): StreamingPreviewEngine {
        val e = engine(rec, executor, capacity = capacity)
        e.warm(dir, pack)
        e.open { emitted += it }
        return e
    }

    /** 32 ms of PCM per chunk, the clock advanced BEFORE the send (the frame's own instant). */
    private fun feedMs(e: StreamingPreviewEngine, ms: Int) {
        repeat(ms / 32) { now += 32; e.sendAudio(ByteArray(1024)) }
    }

    // ------------------------------------------------------------- warm + canary

    @Test fun warmLoadsWithTwoThreadsRunsTheCanaryAndLogsTheOpenLine() {
        val rec = ScriptedRecognizer(listOf("HELLO"), canaryText = CANARY)
        val factory = ScriptedFactory(rec)
        val e = engine(rec, factory = factory)
        e.warm(dir, pack)
        assertEquals(2, factory.lastThreads)
        assertTrue(e.isWarm())
        assertFalse(e.disabled)
        assertEquals(
            "stream-open: sherpa=1.13.7 ort=1.27.1 threads=2 provider=cpu loadMs=0 canary=pass canaryMs=0 outLen=23 load=ok",
            logs.single(),
        )
        assertTrue("the canary's throwaway stream is released", rec.streams.single().released)
        assertFalse(rec.released)
        e.warm(dir, pack)
        assertEquals("warm is idempotent", 1, factory.loads)
    }

    @Test fun aFailedCanaryDisablesForTheProcessAndReleasesTheRecognizer() {
        val rec = ScriptedRecognizer(listOf("HELLO"), canaryText = "")
        val factory = ScriptedFactory(rec)
        val e = engine(rec, factory = factory)
        e.warm(dir, pack)
        assertTrue(e.disabled)
        assertFalse(e.isWarm())
        assertTrue(rec.released)
        assertTrue(logs.single().contains(" canary=fail canaryMs=0 outLen=0 load=ok"))
        e.warm(dir, pack)
        assertEquals("a disabled previewer never reloads in this process", 1, factory.loads)
    }

    @Test fun aMissingClipIsNoVerdictAndTheProcessStaysOff() {
        val rec = ScriptedRecognizer(listOf("HELLO"), canaryText = CANARY)
        val e = engine(rec, clip = null)
        e.warm(dir, pack)
        assertTrue(e.disabled)
        assertTrue(logs.single().contains(" canary=none canaryMs=0 outLen=0 load=ok"))
    }

    @Test fun aLoadFailureDisablesAndReportsTheCorruption() {
        var corrupt = 0
        val e = engine(null, factory = ScriptedFactory(null, throwAtLoad = true), onLoadFailure = { corrupt++ })
        e.warm(dir, pack)
        assertTrue(e.disabled)
        assertEquals(1, corrupt)
        assertTrue(logs.single().endsWith(" canary=skipped canaryMs=0 outLen=0 load=fail"))
    }

    // ------------------------------------------------------------- the feed and the partials

    @Test fun partialsArriveLowercasedDedupedAndPrefixGrowingAtTheDecodeCadence() {
        val rec = ScriptedRecognizer(CANARY_PARTIALS, canaryText = CANARY)
        val e = warmOpen(rec)
        feedMs(e, 2_560)
        // First decode at 7,200 samples (chunk 15), then every 5,120 (chunks 25, 35, 45, …): the
        // text changes on the first four decodes and repeats on the rest — repeats are not partials.
        assertEquals(listOf("one", "one two three", "one two three four", "one two three four five"), emitted)
        val s = rec.streams[1]
        assertEquals("one accept per 32 ms chunk", 80, s.fed.size)
        assertEquals("decode only while ready: 1 + (40,960 − 7,200) / 5,120", 7, s.decodes)
    }

    @Test fun commitPadsFinishesDrainsFreezesTheFinalTextAndRecreatesTheStream() {
        val rec = ScriptedRecognizer(CANARY_PARTIALS, canaryText = CANARY)
        val e = warmOpen(rec)
        feedMs(e, 2_560)
        var frozen: Pair<Long, String>? = null
        e.commit(seq = 0L, retainMs = 0L) { seq, text -> frozen = seq to text }
        assertEquals(0L to "one two three four five", frozen)
        val s1 = rec.streams[1]
        assertEquals("the pad is exactly PAD_MS of zeros", 8_000, s1.fed.last())
        assertTrue(s1.finished)
        assertTrue("the padded stream is released, never reset", s1.released)
        assertEquals("canary + session + the fresh stream", 3, rec.streams.size)
        assertFalse(rec.streams[2].released)
        assertEquals("the pad's decodes: 1 + (48,960 − 7,200) / 5,120", 9, s1.decodes)
        assertEquals(
            "stream-timing: seq=0 audio=2560 decodes=9 decodeMs=0 p50us=0 p99us=0 rtf=0.000 partials=4 firstPartialMs=480 padMs=500 shed=0 retract=0",
            logs.last(),
        )
        assertEquals("the freeze itself emits no partial — the composer speaks for it", 4, emitted.size)
    }

    @Test fun aRetainedTailIsTrimmedFromTheFrozenTextAndRefedToTheFreshStream() {
        val rec = ScriptedRecognizer(
            listOf("HELLO THERE FRIEND"),
            timestampsOf = { floatArrayOf(0.5f, 1.0f, 1.5f) },
            canaryText = CANARY,
        )
        val e = warmOpen(rec)
        feedMs(e, 2_048)
        var frozen = ""
        e.commit(seq = 3L, retainMs = 800L) { _, text -> frozen = text }
        // cut = (2,048 − 800) / 1000 = 1.248 s: HELLO (0.5) and THERE (1.0) stay, FRIEND (1.5) goes.
        assertEquals("hello there", frozen)
        val fresh = rec.streams[2]
        assertEquals("the ring's last 800 ms (12,800 samples) re-fed in one accept", listOf(12_800), fresh.fed)
        assertEquals("hello there friend", emitted.last())
    }

    @Test fun queueOverflowShedsTheChunkAndSaysSoOnTheTimingLine() {
        val rec = ScriptedRecognizer(listOf("A"), canaryText = CANARY)
        val exec = ManualExecutor()
        val e = warmOpen(rec, executor = exec, capacity = 2)
        exec.runAll()
        repeat(3) { e.sendAudio(ByteArray(1024)) }   // the executor is starved: the third chunk has nowhere to go
        exec.runAll()
        assertEquals(2 * 512L, rec.streams[1].samples)
        e.commit(0L, 0L) { _, _ -> }
        exec.runAll()
        assertTrue(logs.last().contains(" shed=1 "))
    }

    @Test fun aRetractionIsCountedNeverCorrected() {
        val rec = ScriptedRecognizer(listOf("ONE TWO", "ONE THREE"), canaryText = CANARY)
        val e = warmOpen(rec)
        feedMs(e, 1_024)
        assertEquals("the strip shows what the model now says", listOf("one two", "one three"), emitted)
        e.commit(0L, 0L) { _, _ -> }
        assertTrue(logs.last().endsWith(" retract=1"))
    }

    @Test fun theThrottleNeverBitesAtTheDecodeCadenceButThinsABurst() {
        // 320 ms between partials is over the 150 ms floor, so steady state emits every change.
        // Two changes inside one burst (the clock does not move) emit once and the second lands
        // on the next drain — nothing is lost, only thinned.
        val rec = ScriptedRecognizer(listOf("A", "A B", "A B C"), canaryText = CANARY)
        val e = warmOpen(rec)
        repeat(25) { e.sendAudio(ByteArray(1024)) }           // clock frozen: decodes 1 and 2 inside "one instant"
        assertEquals(listOf("a"), emitted)
        now += 200
        e.sendAudio(ByteArray(1024))
        assertEquals(listOf("a", "a b"), emitted)
    }

    // ------------------------------------------------------------- failure and lifecycle

    @Test fun threeConsecutiveDecodeFailuresDisableThePreviewerAndBlankTheStrip() {
        val rec = ScriptedRecognizer(listOf("A"), failDecodesFrom = 10, canaryText = CANARY)   // the canary's 9 decodes pass
        val e = warmOpen(rec)
        feedMs(e, 1_600)   // three fresh streams × 15 chunks to reach their first (throwing) decode
        assertTrue(e.disabled)
        assertFalse(e.isWarm())
        assertTrue(rec.released)
        assertEquals("", emitted.last())
        assertEquals(3, logs.count { it.startsWith("stream-preview: decode threw") })
    }

    @Test fun closeReleasesTheStreamButNotTheRecognizer_releaseReleasesBoth() {
        val rec = ScriptedRecognizer(listOf("A"), canaryText = CANARY)
        val e = warmOpen(rec)
        feedMs(e, 64)
        e.close()
        assertTrue(rec.streams[1].released)
        assertFalse(rec.released)
        assertTrue("still warm across sessions", e.isWarm())
        e.sendAudio(ByteArray(1024))
        assertEquals("audio after close is dropped", 2, rec.streams.size)
        e.release()
        assertTrue(rec.released)
        assertFalse(e.isWarm())
        assertFalse("release is not a verdict", e.disabled)
    }

    @Test fun aCommitBeforeWarmFreezesBlank() {
        val e = engine(ScriptedRecognizer(listOf("A")))
        var frozen: String? = null
        e.commit(7L, 0L) { _, text -> frozen = text }
        assertEquals("", frozen)
        assertNull(logs.lastOrNull())
    }
}
```

- [ ] **Step 2: Run to see them fail** (`--tests "com.whispereverywhere.transcription.stream.*"`). Expected: compilation FAILS.

- [ ] **Step 3: Implement.**

`PreviewText.kt`:

```kotlin
package com.whispereverywhere.transcription.stream

import java.util.Locale

/** The strip's text rules — pure, pinned by PreviewTextTest. */
object PreviewText {

    /** The model emits ALL CAPS (rung 1 §1.3); the strip shows lowercase. Locale.US: never a Turkish dotless i. */
    fun normalize(raw: String): String = raw.trim().lowercase(Locale.US)

    /**
     * The tokens that END before [cutS] seconds, concatenated — the AAR's tokens carry a LEADING
     * SPACE (rung 3 §2.2: `" F", "OUR"`), so a bare concatenation reproduces the words and their
     * boundaries. Used at a cap cut with a retained tail: the tail's words re-appear from the
     * fresh stream, so they must not also stay frozen. Timestamps shorter than the tokens cannot
     * be trusted to trim; the whole text is returned instead of a silent truncation.
     */
    fun before(result: PreviewResult, cutS: Float): String {
        if (result.timestamps.size < result.tokens.size) return normalize(result.text)
        val sb = StringBuilder()
        for (i in result.tokens.indices) {
            if (result.timestamps[i] < cutS) sb.append(result.tokens[i])
        }
        return normalize(sb.toString())
    }
}
```

`PcmRing.kt`:

```kotlin
package com.whispereverywhere.transcription.stream

/**
 * The last [capacityBytes] of PCM the previewer was fed — the retained-tail source for a cap cut
 * (spec §4.2). Written on the capture thread, read on the preview executor; both under the
 * monitor. `last` returns an EVEN byte count so a PCM16 sample is never split.
 */
class PcmRing(capacityBytes: Int) {
    private val buf = ByteArray(capacityBytes)
    private var total = 0L   // bytes ever written; the write cursor is total % size

    @Synchronized
    fun write(pcm: ByteArray) {
        val size = buf.size
        if (pcm.size >= size) {
            System.arraycopy(pcm, pcm.size - size, buf, 0, size)
            total = ((total + pcm.size) / size) * size   // the newest byte sits at index size − 1
            return
        }
        val start = (total % size).toInt()
        val first = minOf(pcm.size, size - start)
        System.arraycopy(pcm, 0, buf, start, first)
        if (first < pcm.size) System.arraycopy(pcm, first, buf, 0, pcm.size - first)
        total += pcm.size
    }

    @Synchronized
    fun last(bytes: Int): ByteArray {
        val size = buf.size
        val n = minOf(bytes.toLong(), total, size.toLong()).toInt() and 1.inv()
        if (n <= 0) return ByteArray(0)
        val out = ByteArray(n)
        val end = (total % size).toInt()
        val start = ((end - n) % size + size) % size
        val first = minOf(n, size - start)
        System.arraycopy(buf, start, out, 0, first)
        if (first < n) System.arraycopy(buf, 0, out, first, n - first)
        return out
    }

    @Synchronized
    fun clear() { total = 0L }
}
```

`StreamDiag.kt`:

```kotlin
package com.whispereverywhere.transcription.stream

import java.util.Locale

/**
 * The previewer's three greppable lines (spec §8), pure so the exact format is JVM-pinned
 * (StreamDiagTest — the SegmentTiming discipline). Numbers, codes, a language code, never text:
 * `outLen` is a length. `stream-timing:` joins to the funnel's `endpoint:` / `queue:` /
 * `perceived:` lines on `seq`.
 */
object StreamDiag {

    fun openLine(sherpa: String, ort: String, threads: Int, loadMs: Long, canary: String, canaryMs: Long, outLen: Int, load: String): String =
        "stream-open: sherpa=$sherpa ort=$ort threads=$threads provider=cpu loadMs=$loadMs canary=$canary canaryMs=$canaryMs outLen=$outLen load=$load"

    fun timingLine(
        seq: Long, audioMs: Long, decodes: Int, decodeMs: Long, p50Us: Long, p99Us: Long, rtf: Double,
        partials: Int, firstPartialMs: Long, padMs: Long, shed: Boolean, retractions: Int,
    ): String =
        "stream-timing: seq=$seq audio=$audioMs decodes=$decodes decodeMs=$decodeMs p50us=$p50Us p99us=$p99Us rtf=" +
            String.format(Locale.US, "%.3f", rtf) +
            " partials=$partials firstPartialMs=$firstPartialMs padMs=$padMs shed=${bit(shed)} retract=$retractions"

    fun gateLine(
        lang: String?, packInstalled: Boolean, isCloudSession: Boolean, batchJobActive: Boolean,
        userEnabled: Boolean, previewReady: Boolean, armed: Boolean,
    ): String =
        "stream-gate: lang=${lang ?: "auto"} pack=${bit(packInstalled)} cloud=${bit(isCloudSession)} batch=${bit(batchJobActive)} " +
            "enabled=${bit(userEnabled)} ready=${bit(previewReady)} -> preview=${bit(armed)}"

    /** Σ decode / audio; a zero audio length (a degenerate commit) reports 0.0 rather than dividing by zero. */
    fun rtf(decodeMs: Long, audioMs: Long): Double = if (audioMs <= 0L) 0.0 else decodeMs.toDouble() / audioMs.toDouble()

    /** Nearest-rank percentile over a sorted copy; 0 for no samples. */
    fun percentileUs(samples: List<Long>, p: Double): Long {
        if (samples.isEmpty()) return 0L
        val sorted = samples.sorted()
        val rank = Math.ceil(p * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }

    private fun bit(v: Boolean): Int = if (v) 1 else 0
}
```

`StreamingPreviewEngine.kt`:

```kotlin
package com.whispereverywhere.transcription.stream

import com.whispereverywhere.transcription.DeltaThrottle
import com.whispereverywhere.util.AudioMath
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue

/**
 * The four calls `PreviewTeeEngine` makes on a local previewer — a seam so the tee is testable
 * with a fake. [commit]'s [onFrozen] is invoked on the previewer's executor, once, with the
 * padded stream's final text ("" when nothing could be frozen).
 */
interface LocalPreview {
    fun open(onPartial: (String) -> Unit)
    fun sendAudio(pcm: ByteArray)
    fun commit(seq: Long, retainMs: Long, onFrozen: (seq: Long, text: String) -> Unit)
    fun close()
}

/**
 * The on-device word-for-word previewer (spec §3, §4): one sherpa `OnlineRecognizer` (behind
 * [PreviewRecognizer]) and one open [PreviewStream], driven on this engine's OWN single-thread
 * executor — the `TtsEngine` ownership pattern: one thread touches the native object, explicit
 * release, `@Volatile` flags for the readers on other threads.
 *
 * **The feed** (rung 1 §1.5, the probe's loop verbatim): [sendAudio] on the capture thread
 * writes the chunk into the retained-tail ring and `offer`s it to a bounded queue; a successful
 * offer schedules one [drain] on the executor; an overflow DROPS the chunk and marks the segment
 * `shed` — the capture thread never blocks and never decodes (it already carries the inline
 * Silero probe). [drain] polls the queue empty, converts, `acceptWaveform`s, decodes while
 * `isReady` (T = 45 frames for the first decode, then every 320 ms — rung 3 §7), reads the
 * cumulative text, lowercases it, and emits it when it CHANGED and the [DeltaThrottle] allows
 * (at 320 ms the 150 ms throttle never bites; it thins only the commit-time burst, and the
 * freeze bypasses it).
 *
 * **The commit hook** (spec §4.3): pad [StreamingPreviewTuning.PAD_MS] of zeros (the measured
 * floor — 450 loses the canary's `VE`), `inputFinished`, drain, take the result, trim it at the
 * cut when a tail is retained, hand it to [onFrozen], log `stream-timing:`, then RELEASE the
 * stream and `createStream()` — never `reset`, which cannot drop encoder state through this
 * AAR and would decode the pad into the next utterance (research §3.3). The retained tail is
 * re-fed to the fresh stream from the ring.
 *
 * **The canary** (spec §7.2) runs inside [warm], once per load; a failure or a missing clip
 * DISABLES the previewer for the life of the process — nothing is persisted.
 *
 * **Never inside `NativeComputeGate`**: that is a whole-call whisper lock; wrapping this loop in
 * it would stop it being streaming.
 */
class StreamingPreviewEngine(
    private val factory: PreviewRecognizerFactory,
    private val canaryClip: () -> FloatArray?,
    private val onLoadFailure: () -> Unit = {},
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "stream-preview").apply { isDaemon = true }
    },
    private val clock: () -> Long = System::currentTimeMillis,
    private val nanoClock: () -> Long = System::nanoTime,
    private val queueCapacity: Int = StreamingPreviewTuning.QUEUE_CAPACITY,
    private val log: (String) -> Unit = { android.util.Log.i("WE-DIAG", it) },
    private val enterExecutorThread: () -> Unit = {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
    },
) : LocalPreview {

    /** True once a load failed, the canary failed, no clip existed, or decodes kept throwing. Process-scoped. */
    @Volatile var disabled: Boolean = false
        private set

    @Volatile private var warm = false

    // Executor-confined from here down.
    private var recognizer: PreviewRecognizer? = null
    private var stream: PreviewStream? = null
    private val queue = LinkedBlockingQueue<ByteArray>(queueCapacity)
    private val ring = PcmRing(StreamingPreviewTuning.ringBytes())
    @Volatile private var onPartial: ((String) -> Unit)? = null
    private val throttle = DeltaThrottle(now = clock)
    private var lastEmitted = ""
    private var pendingEmit: String? = null
    private var segment = SegmentStats(0L)
    @Volatile private var shedThisSegment = false
    private var consecutiveFailures = 0
    private var priorityApplied = false

    fun isWarm(): Boolean = warm && !disabled

    /** Load + canary on the executor. Idempotent; a no-op once disabled. */
    fun warm(packDir: File, pack: StreamingPack) {
        if (disabled) return
        executor.execute {
            applyPriorityOnce()
            if (recognizer != null || disabled) return@execute
            val t0 = nanoClock()
            val rec = try {
                factory.load(packDir, pack, StreamingPreviewTuning.NUM_THREADS)
            } catch (t: Throwable) {
                disabled = true
                log(StreamDiag.openLine(factory.sherpaVersion(), factory.ortVersion(), StreamingPreviewTuning.NUM_THREADS, msSince(t0), "skipped", 0L, 0, "fail"))
                onLoadFailure()
                return@execute
            }
            val loadMs = msSince(t0)
            val t1 = nanoClock()
            val verdict = try {
                PreviewCanary.run(rec, canaryClip())
            } catch (t: Throwable) {
                CanaryVerdict.Fail(0, 0)
            }
            val outLen = when (verdict) {
                is CanaryVerdict.Pass -> verdict.outLen
                is CanaryVerdict.Fail -> verdict.outLen
                CanaryVerdict.NoClip -> 0
            }
            log(StreamDiag.openLine(factory.sherpaVersion(), factory.ortVersion(), StreamingPreviewTuning.NUM_THREADS, loadMs, verdict.code, msSince(t1), outLen, "ok"))
            if (verdict is CanaryVerdict.Pass) {
                recognizer = rec
                warm = true
            } else {
                runCatching { rec.release() }
                disabled = true
            }
        }
    }

    override fun open(onPartial: (String) -> Unit) {
        this.onPartial = onPartial
        executor.execute {
            val rec = recognizer ?: return@execute
            stream?.let { runCatching { it.release() } }
            stream = rec.createStream()
            queue.clear()
            ring.clear()
            resetSegment()
        }
    }

    /** CAPTURE THREAD, every 32 ms: a ring write and an offer, then return. Never blocks, never decodes. */
    override fun sendAudio(pcm: ByteArray) {
        if (disabled || onPartial == null) return
        ring.write(pcm)
        if (queue.offer(pcm)) executor.execute(::drain) else shedThisSegment = true
    }

    override fun commit(seq: Long, retainMs: Long, onFrozen: (seq: Long, text: String) -> Unit) {
        executor.execute {
            val rec = recognizer
            if (rec == null || stream == null) {
                onFrozen(seq, "")
                return@execute
            }
            drain()   // everything that arrived before the cut is fed first
            val open = stream
            val frozen = if (open == null || disabled) "" else freeze(rec, open, retainMs)
            val audioMs = segment.audioBytes / StreamingPreviewTuning.BYTES_PER_MS
            log(
                StreamDiag.timingLine(
                    seq, audioMs, segment.decodes, segment.decodeUs / 1000L, segment.p50Us(), segment.p99Us(),
                    StreamDiag.rtf(segment.decodeUs / 1000L, audioMs), segment.partials, segment.firstPartialMs,
                    StreamingPreviewTuning.PAD_MS, shedThisSegment, segment.retractions,
                ),
            )
            onFrozen(seq, frozen)
            open?.let { runCatching { it.release() } }
            stream = if (disabled) null else rec.createStream()
            resetSegment()
            val fresh = stream ?: return@execute
            if (retainMs > 0L) {
                val tail = ring.last((retainMs * StreamingPreviewTuning.BYTES_PER_MS).toInt())
                if (tail.isNotEmpty()) {
                    segment.audioBytes += tail.size
                    if (feedAndDecode(rec, fresh, AudioMath.pcm16ToFloat(tail))) emitIfChanged(rec, fresh)
                }
            }
        }
    }

    /** Ends the session's stream; the recognizer stays resident for the next session. */
    override fun close() {
        onPartial = null
        executor.execute {
            stream?.let { runCatching { it.release() } }
            stream = null
            queue.clear()
            ring.clear()
            resetSegment()
        }
    }

    /** Frees the recognizer (onTrimMemory / onDestroy). Not a verdict: a later [warm] reloads. */
    fun release() {
        onPartial = null
        executor.execute {
            stream?.let { runCatching { it.release() } }
            stream = null
            recognizer?.let { runCatching { it.release() } }
            recognizer = null
            warm = false
            queue.clear()
            ring.clear()
            resetSegment()
        }
    }

    // ------------------------------------------------------------------ executor-side internals

    private fun drain() {
        val rec = recognizer
        val s = stream
        if (rec == null || s == null) {
            queue.clear()
            return
        }
        var total = 0
        val chunks = ArrayList<ByteArray>(4)
        while (true) {
            val c = queue.poll() ?: break
            chunks += c
            total += c.size
        }
        if (total == 0) return
        val pcm = if (chunks.size == 1) chunks[0] else ByteArray(total).also { out ->
            var o = 0
            for (c in chunks) {
                System.arraycopy(c, 0, out, o, c.size)
                o += c.size
            }
        }
        segment.audioBytes += pcm.size
        if (!feedAndDecode(rec, s, AudioMath.pcm16ToFloat(pcm))) return
        emitIfChanged(rec, s)
    }

    /** Feed + decode-while-ready, every decode timed. False when the stream was lost to a failure. */
    private fun feedAndDecode(rec: PreviewRecognizer, s: PreviewStream, samples: FloatArray): Boolean = try {
        s.acceptWaveform(samples)
        while (rec.isReady(s)) timedDecode(rec, s)
        consecutiveFailures = 0
        true
    } catch (t: Throwable) {
        onDecodeFailure(rec, t)
        false
    }

    private fun timedDecode(rec: PreviewRecognizer, s: PreviewStream) {
        val t = nanoClock()
        rec.decode(s)
        segment.recordDecode((nanoClock() - t) / 1_000L)
    }

    private fun emitIfChanged(rec: PreviewRecognizer, s: PreviewStream) {
        val text = try {
            PreviewText.normalize(rec.result(s).text)
        } catch (t: Throwable) {
            onDecodeFailure(rec, t)
            return
        }
        if (text != lastEmitted && text != pendingEmit) {
            if (lastEmitted.isNotEmpty() && !text.startsWith(lastEmitted)) segment.retractions++
            pendingEmit = text
        }
        val pending = pendingEmit ?: return
        if (!throttle.shouldEmit()) return
        pendingEmit = null
        emit(pending)
    }

    private fun emit(text: String) {
        lastEmitted = text
        segment.partials++
        if (segment.firstPartialMs < 0L) segment.firstPartialMs = clock() - segment.startMs
        onPartial?.invoke(text)
    }

    private fun freeze(rec: PreviewRecognizer, s: PreviewStream, retainMs: Long): String = try {
        s.acceptWaveform(FloatArray(StreamingPreviewTuning.padSamples()))
        s.inputFinished()
        while (rec.isReady(s)) timedDecode(rec, s)
        val r = rec.result(s)
        if (retainMs > 0L) PreviewText.before(r, cutSeconds(retainMs)) else PreviewText.normalize(r.text)
    } catch (t: Throwable) {
        noteFailure(rec, t)
        ""
    }

    private fun cutSeconds(retainMs: Long): Float =
        (segment.audioBytes / StreamingPreviewTuning.BYTES_PER_MS - retainMs) / 1000f

    private fun onDecodeFailure(rec: PreviewRecognizer, t: Throwable) {
        stream?.let { runCatching { it.release() } }
        stream = null
        noteFailure(rec, t)
        if (!disabled) {
            stream = rec.createStream()
            lastEmitted = ""
            pendingEmit = null
        }
    }

    private fun noteFailure(rec: PreviewRecognizer, t: Throwable) {
        consecutiveFailures++
        log("stream-preview: decode threw (${t.javaClass.simpleName}) failures=$consecutiveFailures")
        if (consecutiveFailures >= StreamingPreviewTuning.MAX_CONSECUTIVE_FAILURES) {
            disabled = true
            warm = false
            stream?.let { runCatching { it.release() } }
            stream = null
            runCatching { rec.release() }
            recognizer = null
            queue.clear()
            emit("")
        }
    }

    private fun resetSegment() {
        segment = SegmentStats(clock())
        lastEmitted = ""
        pendingEmit = null
        shedThisSegment = false
        throttle.reset()
    }

    private fun applyPriorityOnce() {
        if (priorityApplied) return
        priorityApplied = true
        runCatching { enterExecutorThread() }
    }

    private fun msSince(t0: Long): Long = (nanoClock() - t0) / 1_000_000L

    private class SegmentStats(val startMs: Long) {
        var audioBytes = 0L
        var decodes = 0
        var decodeUs = 0L
        val samplesUs = ArrayList<Long>(64)
        var partials = 0
        var firstPartialMs = -1L
        var retractions = 0
        fun recordDecode(us: Long) {
            decodes++
            decodeUs += us
            samplesUs += us
        }
        fun p50Us(): Long = StreamDiag.percentileUs(samplesUs, 0.50)
        fun p99Us(): Long = StreamDiag.percentileUs(samplesUs, 0.99)
    }
}
```

- [ ] **Step 4: Run to see them pass** (`--tests "com.whispereverywhere.transcription.stream.*"`). Expected: 28 + 3 + 4 + 5 + 13 = 53 tests, `failures=0`. `assembleDebug`. If `theThrottleNeverBites…` disagrees on the exact emitted lists, the FIXTURE is wrong, not the throttle — recount the decode points (7,200 then +5,120) before touching `emitIfChanged`.

- [ ] **Step 5: Whole suite, then commit.** Expected: Task 3's count + 25 (4 new suites).

```
feat(stream): StreamingPreviewEngine — the 32 ms feed, decode-while-ready, the cumulative partial, and the pad/drain/freeze/recreate commit hook

Two ORT threads, a bounded 128-chunk queue that sheds rather than blocks, PreviewText's
lowercase + leading-space join, PcmRing for the retained tail, the 500 ms pad at every commit
followed by release + createStream (never reset), the canary inside warm, three greppable
StreamDiag lines. Tests use the measured partial sequences as fixtures.
```

**Battery:** (1) change `padSamples()`'s use in `freeze` to `FloatArray(7_200)` → `commitPads…` RED (fed.last() ≠ 8,000; decodes ≠ 9); revert. (2) replace `createStream()` after the freeze with re-use of the released stream → `commitPads…` RED (streams.size ≠ 3); revert. (3) make `sendAudio` `put` instead of `offer` → `queueOverflowSheds…` hangs/RED; revert. (4) make `emitIfChanged` skip the retraction increment → `aRetractionIsCounted…` RED; revert. (5) make `noteFailure` never disable → `threeConsecutiveDecodeFailures…` RED; revert. (6) uppercase the emitted text (drop `normalize`) → `partialsArriveLowercased…` RED; revert.

---

### Task 5 (P4): `PreviewComposer` + `PreviewTeeEngine` — the tee beside whisper

**Size:** 1.5 days. **Ruling hooks:** none.

**Files:**
- Create: `app/src/main/java/com/whispereverywhere/transcription/stream/PreviewComposer.kt`
- Create: `app/src/main/java/com/whispereverywhere/transcription/stream/PreviewTeeEngine.kt`
- Test: `app/src/test/java/com/whispereverywhere/transcription/stream/PreviewComposerTest.kt`, `PreviewTeeEngineTest.kt` (create)

**Interfaces (Task 7 consumes):**
- `class PreviewComposer { onPartial(text): String; freeze(seq, text): String; resolve(seq): String; compose(); pending(); reset() }`
- `class PreviewTeeEngine(preview: LocalPreview, local: TranscriptionEngine, composer = PreviewComposer()) : TranscriptionEngine`

- [ ] **Step 1: Write the failing tests.**

`PreviewComposerTest.kt`:

```kotlin
package com.whispereverywhere.transcription.stream

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The strip's composition rule (spec §4.2): `<frozen text of every committed-but-unresolved seq,
 * oldest first> + " " + <current partial>` — "what the window does not have yet". Whisper's
 * final for seq N lands 2-6 s after N's cut, and the strip is replace-only, so without the frozen
 * prefix N's words would vanish from the strip the moment N+1's partials start and show nowhere
 * until the window caught up.
 */
class PreviewComposerTest {

    @Test fun aPartialAloneIsTheStrip() {
        val c = PreviewComposer()
        assertEquals("", c.compose())
        assertEquals("hello", c.onPartial("hello"))
        assertEquals("hello there", c.onPartial("hello there"))
    }

    @Test fun aFreezeMovesTheTextBehindTheNextPartial() {
        val c = PreviewComposer()
        c.onPartial("hello there")
        assertEquals("hello there friend", c.freeze(0L, "hello there friend"))
        assertEquals("the partial resets at a freeze", "hello there friend", c.onPartial(""))
        assertEquals("hello there friend how", c.onPartial("how"))
        assertEquals(1, c.pending())
    }

    @Test fun aResolutionDropsItsSeqAndEverythingOlder() {
        val c = PreviewComposer()
        c.freeze(0L, "one")
        c.freeze(1L, "two")
        c.freeze(2L, "three")
        c.onPartial("four")
        assertEquals("one two three four", c.compose())
        assertEquals("three four", c.resolve(1L))
        assertEquals("four", c.resolve(2L))
        assertEquals(0, c.pending())
    }

    @Test fun aResolutionBelowEveryFrozenSeqDropsNothing_aboveDropsAll() {
        val c = PreviewComposer()
        c.freeze(3L, "x")
        assertEquals("x", c.resolve(1L))
        // Local resolves in commit order, so a seq the composer never froze (the 30 s buffer
        // backstop's own commit, unreachable under the 15 s cap) can only be OLDER than what is
        // frozen — and "drop everything at or below" answers that correctly too.
        assertEquals("", c.resolve(99L))
        assertEquals(0, c.pending())
    }

    @Test fun aBlankFreezeKeepsNothingButStillClearsThePartial() {
        val c = PreviewComposer()
        c.onPartial("hmm")
        assertEquals("", c.freeze(0L, ""))
        assertEquals(0, c.pending())
        assertEquals("", c.resolve(0L))
    }

    @Test fun resetForgetsEverything() {
        val c = PreviewComposer()
        c.freeze(0L, "a")
        c.onPartial("b")
        c.reset()
        assertEquals("", c.compose())
        assertEquals(0, c.pending())
    }
}
```

`PreviewTeeEngineTest.kt`:

```kotlin
package com.whispereverywhere.transcription.stream

import com.whispereverywhere.transcription.SegmentOutcome
import com.whispereverywhere.transcription.SpeechEvidence
import com.whispereverywhere.transcription.TranscriptionEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tee (spec §3, §4.1): `local` keeps every commit, every seq and every resolution exactly
 * as today; the previewer only paints. Pinned here: the sendAudio order (local FIRST — the
 * CapSeamPinTest order), the freeze under LOCAL's seq, the swallowed local deltas, the
 * pass-through of resolutions BEFORE the recomposed delta, and the lifecycle delegation.
 */
class PreviewTeeEngineTest {

    private val order = mutableListOf<String>()

    private inner class FakeLocal : TranscriptionEngine {
        var listener: TranscriptionEngine.Listener? = null
        var language: String? = "unset"
        val audio = mutableListOf<ByteArray>()
        val commits = mutableListOf<Pair<Long, SpeechEvidence>>()
        val retains = mutableListOf<Long>()
        var nextSeq = 0L
        var refuse = false
        var closed = false
        var shut = false
        var prewarmed = false
        var idleCalls = 0
        var releasedCtx = false
        override fun connect(language: String?, listener: TranscriptionEngine.Listener) {
            this.language = language
            this.listener = listener
            order += "local.connect"
            listener.onOpen()
        }
        override fun sendAudio(pcm: ByteArray) { audio += pcm; order += "local" }
        override fun commit(): Long = commit(SpeechEvidence.UNKNOWN)
        override fun commit(evidence: SpeechEvidence): Long {
            if (refuse) return -1L
            val seq = nextSeq++
            commits += seq to evidence
            return seq
        }
        override fun commitRetainingTailMs(retainMs: Long): Long = commitRetainingTailMs(retainMs, SpeechEvidence.UNKNOWN)
        override fun commitRetainingTailMs(retainMs: Long, evidence: SpeechEvidence): Long {
            retains += retainMs
            return commit(evidence)
        }
        override fun close() { closed = true; order += "local.close"; listener?.onClosed(); listener = null }
        override fun prewarm() { prewarmed = true }
        override fun shutdown() { shut = true }
        override fun awaitIdle(timeoutMs: Long): Boolean { idleCalls++; return true }
        override fun releaseContext() { releasedCtx = true }
        fun resolve(seq: Long, outcome: SegmentOutcome) = listener!!.onSegmentResolved(seq, outcome)
        fun delta(text: String) = listener!!.onDelta(text)
    }

    private inner class FakePreview : LocalPreview {
        var onPartial: ((String) -> Unit)? = null
        val audio = mutableListOf<ByteArray>()
        val commits = mutableListOf<Pair<Long, Long>>()
        var frozenText = "frozen"
        var closed = false
        override fun open(onPartial: (String) -> Unit) { this.onPartial = onPartial; order += "preview.open" }
        override fun sendAudio(pcm: ByteArray) { audio += pcm; order += "preview" }
        override fun commit(seq: Long, retainMs: Long, onFrozen: (Long, String) -> Unit) {
            commits += seq to retainMs
            onFrozen(seq, frozenText)
        }
        override fun close() { closed = true; order += "preview.close" }
        fun partial(text: String) = onPartial!!.invoke(text)
    }

    private class Owner : TranscriptionEngine.Listener {
        val events = mutableListOf<String>()
        val deltas = mutableListOf<String>()
        val resolved = mutableListOf<Pair<Long, SegmentOutcome>>()
        var opened = 0
        var closedCalls = 0
        override fun onOpen() { opened++ }
        override fun onDelta(text: String) { deltas += text; events += "delta:$text" }
        override fun onSegmentResolved(seq: Long, outcome: SegmentOutcome) { resolved += seq to outcome; events += "resolved:$seq" }
        override fun onError(message: String) { events += "error" }
        override fun onClosed() { closedCalls++ }
    }

    private val local = FakeLocal()
    private val preview = FakePreview()
    private val owner = Owner()
    private val tee = PreviewTeeEngine(preview, local)
    private val pcm = byteArrayOf(1, 0, 2, 0)

    private fun connected(): PreviewTeeEngine { tee.connect("en", owner); return tee }

    @Test fun connectOpensThePreviewThenLocalWithTheSameLanguageAndTheOwnerHearsOnOpen() {
        connected()
        assertEquals(listOf("preview.open", "local.connect"), order)
        assertEquals("en", local.language)
        assertEquals(1, owner.opened)
    }

    @Test fun audioGoesToLocalFirstThenThePreview() {
        connected()
        tee.sendAudio(pcm)
        tee.sendAudio(pcm)
        assertEquals(listOf("preview.open", "local.connect", "local", "preview", "local", "preview"), order)
        assertEquals(2, local.audio.size)
        assertEquals(2, preview.audio.size)
    }

    @Test fun aCommitForwardsTheEvidenceAndFreezesThePreviewUnderLocalsSeq() {
        connected()
        val ev = SpeechEvidence.of(1_000L)
        assertEquals(0L, tee.commit(ev))
        assertEquals(1L, tee.commit())
        assertEquals(listOf(0L to ev, 1L to SpeechEvidence.UNKNOWN), local.commits)
        assertEquals(listOf(0L to 0L, 1L to 0L), preview.commits)
    }

    @Test fun aRetainingCommitHandsTheRetainToBoth() {
        connected()
        val ev = SpeechEvidence.of(2_000L)
        assertEquals(0L, tee.commitRetainingTailMs(800L, ev))
        assertEquals(1L, tee.commitRetainingTailMs(300L))
        assertEquals(listOf(800L, 300L), local.retains)
        assertEquals(listOf(0L to ev, 1L to SpeechEvidence.UNKNOWN), local.commits)
        assertEquals(listOf(0L to 800L, 1L to 300L), preview.commits)
    }

    @Test fun aCommitThatCutNothingNeverFreezes() {
        connected()
        local.refuse = true
        assertEquals(-1L, tee.commit())
        assertEquals(-1L, tee.commitRetainingTailMs(500L))
        assertTrue(preview.commits.isEmpty())
        assertTrue("no delta for a cut that was not one", owner.deltas.isEmpty())
    }

    @Test fun localsOwnDeltasAreSwallowed() {
        // LocalWhisperEngine's native burst and its terminal onDelta("") (LocalWhisperEngine.kt:600)
        // would blank the strip under the previewer's words; only the composer speaks here.
        connected()
        local.delta(" Hello")
        local.delta("")
        assertTrue(owner.deltas.isEmpty())
    }

    @Test fun theStripIsFrozenPrefixPlusPartialAndAResolutionDropsItsPrefixAfterPassingThrough() {
        connected()
        preview.partial("hello")
        assertEquals(listOf("hello"), owner.deltas)
        preview.frozenText = "hello there"
        tee.commit()
        assertEquals("hello there", owner.deltas.last())
        preview.partial("how")
        assertEquals("hello there how", owner.deltas.last())
        local.resolve(0L, SegmentOutcome.Text("Hello there."))
        // The resolution reaches the owner FIRST (delivery timing byte-identical), THEN the strip shrinks.
        assertEquals(listOf("resolved:0", "delta:how"), owner.events.takeLast(2))
        assertEquals(listOf(0L to SegmentOutcome.Text("Hello there.")), owner.resolved)
        preview.frozenText = "how are you"
        tee.commit()
        assertEquals("how are you", owner.deltas.last())
        local.resolve(1L, SegmentOutcome.EmptyExpected)
        assertEquals("an EmptyExpected drops its frozen prefix too — whisper's verdict is the truth", "", owner.deltas.last())
    }

    @Test fun everySeqReachesTheOwnerExactlyOnceInOrder() {
        connected()
        tee.commit(); tee.commit(); tee.commit()
        local.resolve(0L, SegmentOutcome.Text("a"))
        local.resolve(1L, SegmentOutcome.EmptyExpected)
        local.resolve(2L, SegmentOutcome.Text("c"))
        assertEquals(listOf(0L, 1L, 2L), owner.resolved.map { it.first })
    }

    @Test fun closeClosesThePreviewThenLocalAndTheOwnerHearsOnClosedOnce() {
        connected()
        tee.close()
        assertEquals(listOf("preview.close", "local.close"), order.takeLast(2))
        assertTrue(preview.closed)
        assertTrue(local.closed)
        assertEquals(1, owner.closedCalls)
        preview.partial("late")
        assertTrue("a partial after close reaches nobody", owner.deltas.isEmpty())
    }

    @Test fun lifecycleDelegatesToLocalAndNeverTouchesThePreviewsRecognizer() {
        // The service OWNS the resident previewer (release on trim/destroy, Task 7); the tee only
        // borrows it. prewarm/shutdown/awaitIdle/releaseContext are local's alone.
        tee.prewarm()
        assertTrue(local.prewarmed)
        assertTrue(tee.awaitIdle(1_000L))
        assertEquals(1, local.idleCalls)
        tee.releaseContext()
        assertTrue(local.releasedCtx)
        tee.shutdown()
        assertTrue(local.shut)
        assertFalse(preview.closed)
    }
}
```

- [ ] **Step 2: Run to see them fail** (`--tests "com.whispereverywhere.transcription.stream.*"`). Expected: compilation FAILS.

- [ ] **Step 3: Implement.**

`PreviewComposer.kt`:

```kotlin
package com.whispereverywhere.transcription.stream

import java.util.TreeMap

/**
 * The strip's composition rule (spec §4.2): `frozen[every committed-but-unresolved seq, oldest
 * first] + " " + partial` — "what the window does not have yet". Whisper's final for seq N lands
 * 2-6 s after N's cut (F ≈ 2.3 s Fold6 CPU, ≤ 6 s Tab, ≈ 1.9 s turbo) and the strip is
 * replace-only, so N's words stay on the strip until N resolves, and N+1's partial grows behind
 * them. A blank composition is the strip's "nothing pending" (parked INVISIBLE by the service).
 *
 * Not thread-safe by itself: `PreviewTeeEngine` serialises the three writers (the preview
 * executor's partial/freeze, the local executor's resolve) under one lock.
 */
class PreviewComposer {
    private val frozen = TreeMap<Long, String>()
    private var partial = ""

    fun onPartial(text: String): String {
        partial = text
        return compose()
    }

    /** A commit for [seq]: its padded text is frozen; the partial (which it supersedes) resets. */
    fun freeze(seq: Long, text: String): String {
        if (text.isNotBlank()) frozen[seq] = text
        partial = ""
        return compose()
    }

    /** `local` resolved [seq] (Text, EmptyExpected, Lost alike): its prefix — and anything older — leaves the strip. */
    fun resolve(seq: Long): String {
        frozen.headMap(seq, true).clear()
        return compose()
    }

    fun compose(): String {
        val sb = StringBuilder()
        for (t in frozen.values) {
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(t)
        }
        if (partial.isNotBlank()) {
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(partial)
        }
        return sb.toString()
    }

    fun pending(): Int = frozen.size

    fun reset() {
        frozen.clear()
        partial = ""
    }
}
```

`PreviewTeeEngine.kt`:

```kotlin
package com.whispereverywhere.transcription.stream

import com.whispereverywhere.transcription.SegmentOutcome
import com.whispereverywhere.transcription.SpeechEvidence
import com.whispereverywhere.transcription.TranscriptionEngine

/**
 * THE TEE (spec §3, §4.1) — the `FallbackTranscriptionEngine` decorator shape
 * (transcription/cloud/FallbackTranscriptionEngine.kt:195-210) with the previewer in the mirror's
 * seat. [local] is the session's whisper engine (CPU or NPU) and keeps EVERYTHING it has today:
 * every seq it allocates, its EmptyExpected floor, its buffer, its resolutions — the typed
 * transcript cannot depend on the previewer.
 *
 *  - [sendAudio]: `local` FIRST (the CapSeamPinTest order), then the preview's non-blocking
 *    offer. The recorder hands a fresh copy per chunk (StreamingAudioRecorder.kt:96
 *    `buffer.copyOf(read)`), so holding the reference in the preview's queue is safe.
 *  - every commit form forwards to `local` and, ONLY for a real seq (`>= 0`), freezes the preview
 *    under that seq — the previewer never allocates a seq and never resolves one.
 *  - [Relay] forwards `onOpen` / `onSegmentResolved` / `onError` / `onClosed` untouched and
 *    SWALLOWS `local`'s `onDelta` (its native burst and the terminal blank at
 *    LocalWhisperEngine.kt:600 would blank the strip under the previewer's words). After a
 *    resolution is forwarded, the composer drops that seq's frozen prefix and the shrunken strip
 *    is emitted — resolution first, so delivery timing is byte-identical to today.
 *  - the composer is the ONE `onDelta` source; its three writers (preview executor: partial,
 *    freeze; local executor: resolve) are serialised under [composerLock].
 *  - lifecycle (`prewarm` / `shutdown` / `awaitIdle` / `releaseContext`) is `local`'s alone: the
 *    service OWNS the resident previewer and releases it on trim/destroy; the tee borrows it.
 */
class PreviewTeeEngine(
    private val preview: LocalPreview,
    private val local: TranscriptionEngine,
    private val composer: PreviewComposer = PreviewComposer(),
) : TranscriptionEngine {

    @Volatile private var listener: TranscriptionEngine.Listener? = null
    private val composerLock = Any()

    override fun connect(language: String?, listener: TranscriptionEngine.Listener) {
        this.listener = listener
        synchronized(composerLock) { composer.reset() }
        preview.open { partial -> emit { composer.onPartial(partial) } }
        local.connect(language, Relay(listener))
    }

    /** AUDIO CAPTURE THREAD, every ~32 ms: two appends and return. */
    override fun sendAudio(pcm: ByteArray) {
        local.sendAudio(pcm)
        preview.sendAudio(pcm)
    }

    override fun commit(): Long = commit(SpeechEvidence.UNKNOWN)

    override fun commit(evidence: SpeechEvidence): Long = freezeAfter(local.commit(evidence), retainMs = 0L)

    override fun commitRetainingTailMs(retainMs: Long): Long = commitRetainingTailMs(retainMs, SpeechEvidence.UNKNOWN)

    override fun commitRetainingTailMs(retainMs: Long, evidence: SpeechEvidence): Long =
        freezeAfter(local.commitRetainingTailMs(retainMs, evidence), retainMs)

    private fun freezeAfter(seq: Long, retainMs: Long): Long {
        if (seq >= 0L) preview.commit(seq, retainMs) { s, text -> emit { composer.freeze(s, text) } }
        return seq
    }

    override fun close() {
        preview.close()
        local.close()
        listener = null
    }

    override fun prewarm() = local.prewarm()
    override fun shutdown() = local.shutdown()
    override fun awaitIdle(timeoutMs: Long): Boolean = local.awaitIdle(timeoutMs)
    override fun releaseContext() = local.releaseContext()

    private fun emit(compose: () -> String) {
        val l = listener ?: return
        val text = synchronized(composerLock) { compose() }
        l.onDelta(text)
    }

    private inner class Relay(private val owner: TranscriptionEngine.Listener) : TranscriptionEngine.Listener {
        override fun onOpen() = owner.onOpen()
        override fun onDelta(text: String) = Unit   // swallowed: the composer is the one delta source
        override fun onSegmentResolved(seq: Long, outcome: SegmentOutcome) {
            owner.onSegmentResolved(seq, outcome)
            emit { composer.resolve(seq) }
        }
        override fun onError(message: String) = owner.onError(message)
        override fun onClosed() = owner.onClosed()
    }
}
```

- [ ] **Step 4: Run to see them pass** (`--tests "com.whispereverywhere.transcription.stream.*"`). Expected: 53 + 6 + 10 = 69 tests, `failures=0`. `assembleDebug`.

- [ ] **Step 5: Whole suite, then commit.** Expected: Task 4's count + 16 (2 new suites).

```
feat(stream): PreviewTeeEngine — PCM mirrored to the previewer beside whisper; the previewer never commits

sendAudio local-first then the preview; every commit form forwards to local and freezes the
preview under local's seq (never for -1); local's deltas are swallowed, the composer is the
one onDelta source (frozen unresolved prefixes + the live partial); a resolution passes
through first and then drops its prefix. Lifecycle is local's.
```

**Battery:** (1) swap the two lines in `sendAudio` → `audioGoesToLocalFirst…` RED; revert. (2) drop the `seq >= 0L` guard → `aCommitThatCutNothingNeverFreezes` RED; revert. (3) forward `onDelta` in `Relay` → `localsOwnDeltasAreSwallowed` RED; revert. (4) emit before forwarding in `onSegmentResolved` → `theStripIsFrozenPrefix…` RED (order); revert. (5) make `resolve` drop only the exact seq (`remove(seq)`) → `aResolutionDropsItsSeqAndEverythingOlder` RED; revert.

---

### Task 6 (P5): The language gate, the preference, the copy, the two surfaces

**Size:** 1 day. **Ruling hooks:** R3 = the preference's default (`true`); R4 = the gate's `sessionLanguage == "en"` row (a flip accepts `null` too). Both are one literal each.

**Files:**
- Modify: `app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt` (add `localPreviewArms` directly under `sessionLanguageFor`, `:203-209`)
- Modify: `app/src/main/java/com/whispereverywhere/data/local/PreferencesManager.kt` (`:400-409` pattern; key block `:452-461`)
- Create: `app/src/main/java/com/whispereverywhere/transcription/stream/StreamingPackCopy.kt`
- Modify: `app/src/main/java/com/whispereverywhere/ui/screens/SettingsScreen.kt` (after the read-aloud voice `when`, `:502-530`; composable before `fun SettingsItem(`, `:948`)
- Modify: `app/src/main/java/com/whispereverywhere/ui/screens/OnboardingFlowScreen.kt` (`LanguageStep`, `:587-608`; its call site `:228`)
- Test: `app/src/test/java/com/whispereverywhere/service/LocalPreviewGateTest.kt`, `app/src/test/java/com/whispereverywhere/transcription/stream/StreamingPackCopyTest.kt` (create)

**Interfaces (Task 7 consumes):**
- `internal fun localPreviewArms(sessionLanguage: String?, packInstalled: Boolean, isCloudSession: Boolean, batchJobActive: Boolean, userEnabled: Boolean, previewReady: Boolean): Boolean`
- `PreferencesManager.localPreviewEnabled: Boolean` (default true) + `localPreviewEnabledFlow: StateFlow<Boolean>`
- `object StreamingPackCopy { SETTINGS_TITLE, SETTINGS_INSTALL, SETTINGS_INSTALLED, SETTINGS_REPAIR, SETTINGS_DISABLED_ON_DEVICE, SWITCH_TITLE, LANGUAGE_STEP_SENTENCE, LANGUAGE_CHIP, DELETE_TITLE }`

- [ ] **Step 1: Write the failing tests.**

`LocalPreviewGateTest.kt`:

```kotlin
package com.whispereverywhere.service

import com.whispereverywhere.model.ModelScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The previewer's gate (spec §5), as a truth table — and its composition with
 * [sessionLanguageFor], because RULING ASSUMED (R4) is a statement about the RESOLVED language:
 * Auto = whisper only on multilingual tiers; Auto on `pro` = English, which `sessionLanguageFor`
 * already answers (FloatingBubbleService.kt:203-209). A flip to "Auto + pack ⇒ English partials
 * regardless" is one accepted value (`null`) in the gate and one row here.
 */
class LocalPreviewGateTest {

    private fun arms(
        lang: String?,
        pack: Boolean = true,
        cloud: Boolean = false,
        batch: Boolean = false,
        enabled: Boolean = true,
        ready: Boolean = true,
    ) = localPreviewArms(
        sessionLanguage = lang, packInstalled = pack, isCloudSession = cloud,
        batchJobActive = batch, userEnabled = enabled, previewReady = ready,
    )

    @Test fun fixedEnglishWithThePackArms() {
        assertTrue(arms("en"))
    }

    @Test fun autoOnAnEnglishOnlyTierResolvesToEnglishAndArms() {
        // R4's second half: `pro` (small.en) already forces "en" for the local engine.
        val lang = sessionLanguageFor(ModelScope.ENGLISH, null, TranscribingEngine.LOCAL)
        assertEquals("en", lang)
        assertTrue(arms(lang))
    }

    @Test fun autoOnAMultilingualTierIsWhisperOnly() {
        // R4's first half: English partials over Spanish speech would be garbage. Byte-identical
        // to 4.3.4 for every Auto + multi / npu / npu-turbo session.
        val lang = sessionLanguageFor(ModelScope.MULTILINGUAL, null, TranscribingEngine.LOCAL)
        assertNull(lang)
        assertFalse(arms(lang))
    }

    @Test fun aNonEnglishPickNeverArms() {
        assertFalse(arms("es"))
        assertFalse(arms(sessionLanguageFor(ModelScope.MULTILINGUAL, "es", TranscribingEngine.LOCAL)))
        assertFalse(arms(sessionLanguageFor(null, "fr", TranscribingEngine.LOCAL)))
    }

    @Test fun aSpanishPickOnAnEnglishOnlyTierArms_becauseWhisperTypesEnglishThere() {
        // The scope override wins for the local engine; the preview matches the typed language.
        assertTrue(arms(sessionLanguageFor(ModelScope.ENGLISH, "es", TranscribingEngine.LOCAL)))
    }

    @Test fun everyOtherInputIsAVeto() {
        assertFalse("no pack", arms("en", pack = false))
        assertFalse("a cloud session (batch or live) keeps today's strip", arms("en", cloud = true))
        assertFalse("a batch file job is running", arms("en", batch = true))
        assertFalse("the switch is off (R3 makes it default-on; off is still off)", arms("en", enabled = false))
        assertFalse("the canary failed, or the recognizer is not warm yet", arms("en", ready = false))
    }
}
```

`StreamingPackCopyTest.kt`:

```kotlin
package com.whispereverywhere.transcription.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every user-facing string of the feature (spec §9), pinned verbatim and scanned for the banned words. */
class StreamingPackCopyTest {

    private val all = listOf(
        StreamingPackCopy.SETTINGS_TITLE,
        StreamingPackCopy.SETTINGS_INSTALL,
        StreamingPackCopy.SETTINGS_INSTALLED,
        StreamingPackCopy.SETTINGS_REPAIR,
        StreamingPackCopy.SETTINGS_DISABLED_ON_DEVICE,
        StreamingPackCopy.SWITCH_TITLE,
        StreamingPackCopy.LANGUAGE_STEP_SENTENCE,
        StreamingPackCopy.LANGUAGE_CHIP,
        StreamingPackCopy.DELETE_TITLE,
    )

    @Test fun theStringsArePinnedVerbatim() {
        assertEquals("Live words while you speak (English)", StreamingPackCopy.SETTINGS_TITLE)
        assertEquals(
            "Download a 73 MB English preview model. Words appear on the bubble as you talk; the typed transcript is still the speech model's.",
            StreamingPackCopy.SETTINGS_INSTALL,
        )
        assertEquals(
            "Installed (73 MB). Words appear on the bubble as you speak English; the typed transcript is unchanged.",
            StreamingPackCopy.SETTINGS_INSTALLED,
        )
        assertEquals("The preview model is damaged. Download it again to restore live words.", StreamingPackCopy.SETTINGS_REPAIR)
        assertEquals(
            "Live words are off on this device: the preview model did not pass its start-up check. Your transcripts are unaffected.",
            StreamingPackCopy.SETTINGS_DISABLED_ON_DEVICE,
        )
        assertEquals("Show live words", StreamingPackCopy.SWITCH_TITLE)
        assertEquals(
            "Live words on the bubble are English-only for now; other languages show a progress line while each sentence is transcribed.",
            StreamingPackCopy.LANGUAGE_STEP_SENTENCE,
        )
        assertEquals("Live words on the bubble while you speak — preview model installed.", StreamingPackCopy.LANGUAGE_CHIP)
        assertEquals("Delete the preview model", StreamingPackCopy.DELETE_TITLE)
    }

    @Test fun theBadgeIsTheCatalogsNotARetypedNumber() {
        val badge = StreamingPackCatalog.sizeBadge(StreamingPackCatalog.EN.totalBytes)
        assertEquals("73 MB", badge)
        assertTrue(StreamingPackCopy.SETTINGS_INSTALL.contains(badge))
        assertTrue(StreamingPackCopy.SETTINGS_INSTALLED.contains(badge))
        all.forEach { assertFalse("never the tarball's size under the small badge", it.contains("310")) }
    }

    @Test fun noStringClaimsASpeedOrNamesALatency() {
        // The union of the app's banned lists: HowToGuideTest, InFlightStripTest, ModelTierCopyTest,
        // CloudProvidersScreenLogicTest. "live" is the app's own word for the surface (CLOUD_LIVE).
        val banned = listOf("faster", "fastest", "quicker", "quickest", "instant", "real-time", "blazing", "lightning", "speed")
        all.forEach { s ->
            val lower = s.lowercase()
            banned.forEach { b -> assertFalse("'$b' in <<$s>>", lower.contains(b)) }
            assertFalse("no millisecond claim in copy", Regex("\\d+\\s?ms").containsMatchIn(lower))
        }
    }

    @Test fun everyInstallStringSaysTheTypedTranscriptIsUntouched() {
        // The one promise that matters: the previewer is additive (spec §10).
        assertTrue(StreamingPackCopy.SETTINGS_INSTALL.contains("typed transcript"))
        assertTrue(StreamingPackCopy.SETTINGS_INSTALLED.contains("typed transcript"))
        assertTrue(StreamingPackCopy.SETTINGS_DISABLED_ON_DEVICE.contains("transcripts are unaffected"))
    }
}
```

- [ ] **Step 2: Run to see them fail** (`--tests "com.whispereverywhere.service.LocalPreviewGateTest"`, `--tests "com.whispereverywhere.transcription.stream.StreamingPackCopyTest"`). Expected: compilation FAILS.

- [ ] **Step 3: Implement.**

(a) `FloatingBubbleService.kt`, directly after `sessionLanguageFor` (`:203-209`):

```kotlin
/**
 * Does this session get the on-device word-for-word previewer (4.4.0, spec §5)? Pure, pinned as
 * a truth table by LocalPreviewGateTest; the ONE caller is the wrap site in startRecording, which
 * logs every input on the `stream-gate:` line.
 *
 * RULING ASSUMED (R4): [sessionLanguage] is the RESOLVED local language —
 * `sessionLanguageFor(installedScope, selection, LOCAL)` — so Auto on an ENGLISH-scope tier is
 * "en" (arms) and Auto on a multilingual tier is null (whisper only; English partials over
 * Spanish speech would be garbage). A flip to "Auto + pack ⇒ English regardless" accepts null
 * here and nowhere else. RULING ASSUMED (R3): [userEnabled] defaults true in PreferencesManager.
 *
 * Cloud sessions (batch or live) keep today's strip; a running batch file job vetoes (two CPU
 * consumers beside whisper's bursts is the research's §3.9 refusal); [previewReady] is the
 * resident recognizer's `isWarm()` — false while it loads and forever after a failed canary.
 */
internal fun localPreviewArms(
    sessionLanguage: String?,
    packInstalled: Boolean,
    isCloudSession: Boolean,
    batchJobActive: Boolean,
    userEnabled: Boolean,
    previewReady: Boolean,
): Boolean =
    sessionLanguage == "en" && packInstalled && !isCloudSession && !batchJobActive && userEnabled && previewReady
```

(b) `PreferencesManager.kt` — after the `sttLiveModeGemini` block (`:400-409`):

```kotlin
    /**
     * 4.4.0 — RULING ASSUMED (R3): the on-device word-for-word previewer is DEFAULT-ON and
     * additive for a fixed-English user with the pack installed; the whisper step stays the
     * mandatory one. The switch only matters once the pack exists; the gate
     * (`localPreviewArms`) reads it per session. Reactive mirror for the Settings row.
     */
    private val _localPreviewEnabled = MutableStateFlow(prefs.getBoolean(KEY_LOCAL_PREVIEW_ENABLED, true))
    val localPreviewEnabledFlow: StateFlow<Boolean> = _localPreviewEnabled.asStateFlow()

    var localPreviewEnabled: Boolean
        get() = _localPreviewEnabled.value
        set(value) {
            prefs.edit().putBoolean(KEY_LOCAL_PREVIEW_ENABLED, value).apply()
            _localPreviewEnabled.value = value
        }
```

and in the key block (`:452-461`): `private const val KEY_LOCAL_PREVIEW_ENABLED = "local_preview_enabled"`.

(c) `StreamingPackCopy.kt`:

```kotlin
package com.whispereverywhere.transcription.stream

/**
 * Every user-facing string of the previewer (spec §9) — pure, Compose-free, pinned verbatim by
 * StreamingPackCopyTest and scanned for the app's banned speed words. "Live" is the app's own word
 * for the surface (CLOUD_LIVE); no sentence promises a latency. The size badge is the catalog's
 * (`sizeBadge` → "73 MB"), never a retyped number.
 */
object StreamingPackCopy {
    private val BADGE = StreamingPackCatalog.sizeBadge(StreamingPackCatalog.EN.totalBytes)

    const val SETTINGS_TITLE = "Live words while you speak (English)"
    val SETTINGS_INSTALL =
        "Download a $BADGE English preview model. Words appear on the bubble as you talk; the typed transcript is still the speech model's."
    val SETTINGS_INSTALLED =
        "Installed ($BADGE). Words appear on the bubble as you speak English; the typed transcript is unchanged."
    const val SETTINGS_REPAIR = "The preview model is damaged. Download it again to restore live words."
    /** RULING ASSUMED (R1): the canary is the only SME guard; this is what the row says after it fails. */
    const val SETTINGS_DISABLED_ON_DEVICE =
        "Live words are off on this device: the preview model did not pass its start-up check. Your transcripts are unaffected."
    const val SWITCH_TITLE = "Show live words"
    const val LANGUAGE_STEP_SENTENCE =
        "Live words on the bubble are English-only for now; other languages show a progress line while each sentence is transcribed."
    /** Rendered in the English row's subtitle slot on the language step when the pack is installed. */
    const val LANGUAGE_CHIP = "Live words on the bubble while you speak — preview model installed."
    const val DELETE_TITLE = "Delete the preview model"
}
```

(d) `SettingsScreen.kt` — directly after the `when` that renders the read-aloud voice rows (its `else ->` branch is the `SettingsItem(icon = Icons.Filled.CloudDownload, title = "Download the read-aloud voice", …)` at `:502-530`; find the `when`'s closing brace and add the call after it):

```kotlin
                LivePreviewRows(context)
```

and, before `fun SettingsItem(` (`:948`), the composable:

```kotlin
/**
 * 4.4.0: the streaming-previewer pack (spec §6, §9) — download / installed + switch + delete /
 * repair. Mirrors the read-aloud voice rows above it: the same SettingsItem / SettingsSwitchItem
 * composables, the same DownloadManager-backed manager with (soFar, total) progress. The
 * "start-up check failed" sentence is shown only while the service's previewer reports it, which
 * this screen cannot read (the previewer lives in the service process's memory) — so it is the
 * row's subtitle when the pack is installed and the switch is on but the LAST `stream-gate:` line
 * the owner sees says `ready=0`; the sheet's Z11 row reads it from logcat.
 */
@Composable
private fun LivePreviewRows(context: android.content.Context) {
    val app = context.applicationContext as com.whispereverywhere.WhisperEverywhereApp
    val manager = app.streamingPackManager
    val pack = com.whispereverywhere.transcription.stream.StreamingPackCatalog.EN
    var refresh by remember { mutableStateOf(0) }
    val installed = remember(refresh) { manager.isInstalled(pack) }
    val dirPresent = remember(refresh) { manager.installDir(pack).exists() }
    var status by remember { mutableStateOf<String?>(null) }
    val enabled by app.preferencesManager.localPreviewEnabledFlow.collectAsState()
    val scope = rememberCoroutineScope()
    when {
        installed -> {
            SettingsItem(
                icon = Icons.Filled.Subtitles,
                title = com.whispereverywhere.transcription.stream.StreamingPackCopy.SETTINGS_TITLE,
                subtitle = com.whispereverywhere.transcription.stream.StreamingPackCopy.SETTINGS_INSTALLED,
            )
            SettingsSwitchItem(
                icon = Icons.Filled.Subtitles,
                title = com.whispereverywhere.transcription.stream.StreamingPackCopy.SWITCH_TITLE,
                checked = enabled,
                onCheckedChange = { app.preferencesManager.localPreviewEnabled = it },
            )
            SettingsItem(
                icon = Icons.Filled.Delete,
                title = com.whispereverywhere.transcription.stream.StreamingPackCopy.DELETE_TITLE,
                onClick = {
                    manager.delete(pack)
                    refresh++
                },
            )
        }
        status != null -> SettingsItem(
            icon = Icons.Filled.CloudDownload,
            title = com.whispereverywhere.transcription.stream.StreamingPackCopy.SETTINGS_TITLE,
            subtitle = status,
        )
        else -> SettingsItem(
            icon = Icons.Filled.CloudDownload,
            title = com.whispereverywhere.transcription.stream.StreamingPackCopy.SETTINGS_TITLE,
            subtitle = if (dirPresent) com.whispereverywhere.transcription.stream.StreamingPackCopy.SETTINGS_REPAIR
            else com.whispereverywhere.transcription.stream.StreamingPackCopy.SETTINGS_INSTALL,
            onClick = {
                status = "Starting…"
                scope.launch {
                    runCatching {
                        manager.download(pack) { soFar, total ->
                            status = "${soFar / 1_000_000} / ${total / 1_000_000} MB"
                        }
                    }.onFailure {
                        android.widget.Toast.makeText(
                            context,
                            it.message ?: "Preview model download failed",
                            android.widget.Toast.LENGTH_LONG,
                        ).show()
                    }
                    status = null
                    refresh++
                }
            },
        )
    }
}
```

(e) `OnboardingFlowScreen.kt` — `LanguageStep` (`:587-608`) gains a fourth parameter and one sentence, and the English row's subtitle:

```kotlin
@Composable
private fun LanguageStep(
    languageTag: String,
    picked: String?,
    onPick: (String) -> Unit,
    livePackInstalled: Boolean,
) {
    Text(
        OnboardingLogic.LANGUAGE_HINT,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    // 4.4.0: the previewer is English-only (spec §5, §9) — said here, where the language is picked.
    Text(
        com.whispereverywhere.transcription.stream.StreamingPackCopy.LANGUAGE_STEP_SENTENCE,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(16.dp))
    val deviceCode = OnboardingLogic.deviceLanguageCode(languageTag)
    OnboardingLogic.languageRows(languageTag).forEach { (code, displayName) ->
        LanguageRow(
            title = displayName,
            subtitle = when {
                code == "auto" -> OnboardingLogic.AUTO_LANGUAGE_SUBTITLE
                code == "en" && livePackInstalled -> com.whispereverywhere.transcription.stream.StreamingPackCopy.LANGUAGE_CHIP
                else -> null
            },
            badged = code == deviceCode,
            selected = picked == code,
            onClick = { onPick(code) },
        )
    }
}
```

At the call site (`Step.LANGUAGE -> LanguageStep(` at `:228`) pass `livePackInstalled = livePackInstalled`, where, in the enclosing composable:

```kotlin
    val livePackInstalled = remember {
        (context.applicationContext as com.whispereverywhere.WhisperEverywhereApp).streamingPackManager
            .isInstalled(com.whispereverywhere.transcription.stream.StreamingPackCatalog.EN)
    }
```
(`context` is the screen's existing `LocalContext.current`; if the enclosing composable has no `context` in scope, add `val context = LocalContext.current` beside its other `remember`s.)

- [ ] **Step 4: Run both classes to see them pass**, then `assembleDebug` (the Compose edits compile only there).

- [ ] **Step 5: Whole suite, then commit.** Expected: Task 5's count + 10 (2 new suites).

```
feat(stream): the language gate, the default-on switch, the copy, the two surfaces

localPreviewArms is the truth table (R4: the RESOLVED local language; Auto on pro arms, Auto on
multi does not); PreferencesManager.localPreviewEnabled defaults true (R3); StreamingPackCopy
pins every string and the banned-word scan; the Settings rows mirror the read-aloud voice's,
the language step says English-only and marks the English row when the pack is installed.
```

**Battery:** (1) make `localPreviewArms` accept `sessionLanguage == null` → `autoOnAMultilingualTierIsWhisperOnly` RED; revert. (2) drop the `!batchJobActive` term → `everyOtherInputIsAVeto` RED; revert. (3) change `SETTINGS_INSTALL` to say "instant" → `noStringClaimsASpeed…` RED; revert. (4) retype the badge as `"70 MB"` in `SETTINGS_INSTALLED` → `theBadgeIsTheCatalogs…` RED; revert.

---

### Task 7 (P6): The production adapter and the service wiring — the tee is constructed at ONE site

**Size:** 1.5 days. **Ruling hooks:** none new (R3/R4 are read through the gate; R2 through P0's rules).

**Files:**
- Create: `app/src/main/java/com/whispereverywhere/transcription/stream/SherpaPreviewRecognizer.kt` (the ONLY app file that imports the AAR's streaming classes)
- Modify: `app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt` — field beside `localEngine` (`:443-444`); the arbiter registration (`:760-762`); the prewarm coroutine (`:783`); `onDestroy` (`:1140-1141`); a `warmStreamingPreview()` member after `warmLocalEngine` (`:2497-2545`); the wrap site (`:2896` and `:2932-2940`); `onTrimMemory` (`:3398-3407`)
- Modify: `app/src/main/java/com/whispereverywhere/audio/AudioArbiter.kt` (`:16` KDoc)
- Modify: `app/proguard-rules.pro` (`:72-76`, comment only), `app/src/main/java/com/whispereverywhere/transcription/NativeComputeGate.kt` (KDoc only)
- Test: `app/src/test/java/com/whispereverywhere/service/LocalPreviewWiringPinTest.kt` (create)

**Interfaces:**
- `class SherpaPreviewRecognizerFactory : PreviewRecognizerFactory` (production)
- The service's `private var streamingPreview: StreamingPreviewEngine?` and `private fun warmStreamingPreview(): StreamingPreviewEngine?`

- [ ] **Step 1: Write the failing source pins** `LocalPreviewWiringPinTest.kt`:

```kotlin
package com.whispereverywhere.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * THE PREVIEWER'S WIRING, pinned structurally (4.4.0 Task 7) — the InFlightStripWiringPinTest
 * idiom: `FloatingBubbleService` cannot be instantiated on the JVM, so the CALLS are pinned as
 * LF-normalised, symbol-scoped needles inside their declaring bodies; never line numbers.
 *
 * What this class holds: the tee is constructed at ONE site, AFTER the session language resolves
 * and BEFORE `connect`, and `transcriptionEngine` is re-pointed at it (every later reader — the
 * capture callback, the commit funnel, the stop path — reads that field); the session flag is
 * assigned the GATE's answer and never a constant; the resident previewer is released on trim and
 * on destroy and nowhere else; it is warmed beside the local prewarm; the arbiter counts
 * CONNECTING as capturing; and the service never imports the AAR — `SherpaPreviewRecognizer` is
 * the one adapter.
 */
class LocalPreviewWiringPinTest {

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
        source("src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt")
            .readText()
            .replace("\r\n", "\n")
    }

    private fun count(haystack: String, needle: String) = haystack.split(needle).size - 1

    private fun indexOfOrFail(haystack: String, needle: String): Int {
        val i = haystack.indexOf(needle)
        assertTrue("missing from FloatingBubbleService.kt: <<$needle>>", i >= 0)
        return i
    }

    private fun body(declaration: String, closer: String): String {
        val start = indexOfOrFail(text, declaration)
        val close = text.indexOf(closer, start)
        assertTrue("the closing brace of <<$declaration>> moved", close > start)
        return text.substring(start, close + closer.length)
    }

    private val startRecording: String by lazy { body("    private fun startRecording() {", "\n    }\n") }
    private val onTrim: String by lazy { body("    override fun onTrimMemory(level: Int) {", "\n    }\n") }
    private val onDestroy: String by lazy { body("    override fun onDestroy() {", "\n    }\n") }

    @Test
    fun theTeeIsBuiltAtOneSiteAfterTheLanguageResolvesAndBeforeConnect() {
        val lang = indexOfOrFail(startRecording, "        val lang = sessionLanguageFor(\n")
        val gate = indexOfOrFail(startRecording, "        val previewArmed = localPreviewArms(\n")
        val flag = indexOfOrFail(startRecording, "        sessionHasLocalPreview = previewArmed\n")
        val wrap = indexOfOrFail(startRecording, "PreviewTeeEngine(requireNotNull(preview), baseEngine)")
        val repoint = indexOfOrFail(startRecording, ".also { transcriptionEngine = it }")
        val connect = indexOfOrFail(startRecording, "        engine.connect(lang, object : TranscriptionEngine.Listener {")
        assertTrue("the gate reads the RESOLVED language", lang < gate)
        assertTrue("the flag is set from the gate", gate < flag)
        assertTrue("the tee is built after the flag", flag < wrap)
        assertTrue("and transcriptionEngine is re-pointed at it, so the capture callback, the funnel and the stop path all see the tee", wrap < repoint)
        assertTrue("connect runs on the wrapped engine", repoint < connect)
        assertEquals("ONE wrap site", 1, count(text, "PreviewTeeEngine(requireNotNull(preview), baseEngine)"))
        assertEquals("ONE gate call", 1, count(text, "= localPreviewArms(\n"))
        assertEquals("the base engine is resolved exactly as before, under a new name", 1, count(text, "val baseEngine: TranscriptionEngine = resolveTranscriptionEngine()"))
    }

    @Test
    fun theGateReadsTheSessionKindFromTheWrapperAndTheBatchControllerAndLogsItsInputs() {
        // `cloudWrapper != null` IS the right predicate HERE (unlike the strip render): a cloud
        // batch or live session must keep today's strip, and cloudWrapper is non-null for exactly
        // those. The gate line is logged AFTER the gate and BEFORE the flag, with the same inputs.
        assertEquals(1, count(startRecording, "            isCloudSession = cloudWrapper != null,\n"))
        assertEquals(1, count(startRecording, "            batchJobActive = BatchJobController.active != null,\n"))
        val gate = indexOfOrFail(startRecording, "        val previewArmed = localPreviewArms(\n")
        val line = indexOfOrFail(startRecording, "StreamDiag.gateLine(")
        val flag = indexOfOrFail(startRecording, "        sessionHasLocalPreview = previewArmed\n")
        assertTrue(gate < line && line < flag)
        assertEquals(1, count(text, "StreamDiag.gateLine("))
    }

    @Test
    fun theFlagIsAssignedTheGatesAnswerAndNeverAConstant() {
        assertEquals(1, count(text, "sessionHasLocalPreview = previewArmed"))
        assertEquals("never a literal true", 0, count(text, "sessionHasLocalPreview = true"))
        assertEquals("the one reset (Task 1's), 8-space indented — the declaration's `= false` is not this", 1, count(text, "        sessionHasLocalPreview = false\n"))
    }

    @Test
    fun theResidentPreviewerIsReleasedOnTrimAndOnDestroyAndNowhereElse() {
        // The service OWNS the previewer (spec §4.1 step 10, §7.4): the tee borrows it per session.
        val trimLocal = indexOfOrFail(onTrim, "localEngine?.releaseContext()")
        val trimPreview = indexOfOrFail(onTrim, "streamingPreview?.release()")
        assertTrue("released under the same three-state guard, after the local context", trimLocal < trimPreview)
        indexOfOrFail(onDestroy, "        streamingPreview?.release()\n        streamingPreview = null\n")
        assertEquals("two release sites: trim and destroy", 2, count(text, "streamingPreview?.release()"))
    }

    @Test
    fun thePreviewerIsWarmedBesideTheLocalPrewarm() {
        // Off the session's critical path: the ~0.8 s load + the canary run in the same delayed
        // coroutine as the whisper prewarm, gated on the switch (R3) — the pack check is inside.
        val prewarm = indexOfOrFail(text, "            warmLocalEngine().prewarm()\n")
        val ours = indexOfOrFail(text, "            if (app.preferencesManager.localPreviewEnabled) warmStreamingPreview()\n")
        assertTrue("directly beside the local prewarm", ours > prewarm && ours - prewarm < 400)
        assertEquals(1, count(text, "    private fun warmStreamingPreview(): "))
    }

    @Test
    fun theArbiterCountsConnectingAsCapturing() {
        // Research §3.9 / spec §7.1: `requestCapture()` runs while CONNECTING, but `isCapturing` used
        // to answer RECORDING || FINALIZING only, so a read-aloud requested during CONNECTING started
        // Kokoro at 4 threads beside the session. With a second CPU consumer that overlap is worse.
        val start = indexOfOrFail(text, "com.whispereverywhere.audio.AudioArbiter.isCapturing = {\n")
        val end = text.indexOf("\n        }\n", start)
        assertTrue(end > start)
        val lambda = text.substring(start, end)
        listOf("BubbleState.RECORDING", "BubbleState.FINALIZING", "BubbleState.CONNECTING").forEach {
            assertTrue("isCapturing names $it", lambda.contains("currentState == $it"))
        }
    }

    @Test
    fun theServiceNeverImportsTheAarDirectly() {
        // SherpaPreviewRecognizer is the ONE adapter; everything else sees the seam.
        assertEquals(0, count(text, "com.k2fsa"))
        assertEquals(1, count(text, "SherpaPreviewRecognizerFactory()"))
    }
}
```

- [ ] **Step 2: Run to see it fail** (`--tests "com.whispereverywhere.service.LocalPreviewWiringPinTest"`). Expected: RED on every row (needles missing).

- [ ] **Step 3: The adapter** `SherpaPreviewRecognizer.kt` — the probe's construction verbatim (`tools/probes/litertlm-probe/app/src/main/java/com/whispereverywhere/probe/SherpaProbe.kt:110-127`, the config that produced every number in rung 3):

```kotlin
package com.whispereverywhere.transcription.stream

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.VersionInfo
import java.io.File

/**
 * THE ONE ADAPTER over the AAR's streaming classes (sherpa-onnx 1.13.7, ORT 1.27.1 —
 * app/build.gradle.kts:596-598). The config is the probe's verbatim (rung 3 §2): 16 kHz / 80-bin
 * features with dither 0, `modelType = "zipformer2"`, `provider = "cpu"` (NNAPI is compiled out
 * of this AAR — rung 3 §5.6), `enableEndpoint = false` (the app's Silero cuts, never sherpa's),
 * `decodingMethod = "greedy_search"` (beam costs +40 % and retracts — rung 1 §7),
 * `assetManager = null` (absolute paths, as TtsEngine.buildTts does). Never referenced from a
 * test: the AAR's static init loads a native library.
 *
 * R8: `-keep class com.k2fsa.sherpa.onnx.** { *; }` (proguard-rules.pro) covers these classes;
 * a narrowed keep is a GetFieldID SIGABRT in release builds (the rule's comment says so).
 */
class SherpaPreviewRecognizerFactory : PreviewRecognizerFactory {

    override fun sherpaVersion(): String = runCatching { VersionInfo.version }.getOrDefault("?")

    // 1.13.5+ carries the getter (rung 3 §1.2: javap on classes.jar); the probe read it by reflection
    // only to survive the 1.13.4 arm. If the Kotlin property name differs on this AAR, use the
    // probe's `VersionInfo.Companion.javaClass.getMethod("getOnnxruntimeVersion")` form.
    override fun ortVersion(): String = runCatching { VersionInfo.onnxruntimeVersion }.getOrDefault("?")

    override fun load(dir: File, pack: StreamingPack, numThreads: Int): PreviewRecognizer {
        val config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = StreamingPreviewTuning.SAMPLE_RATE, featureDim = 80, dither = 0f),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = File(dir, pack.encoder.name).absolutePath,
                    decoder = File(dir, pack.decoder.name).absolutePath,
                    joiner = File(dir, pack.joiner.name).absolutePath,
                ),
                tokens = File(dir, pack.tokens.name).absolutePath,
                numThreads = numThreads,
                debug = false,
                provider = "cpu",
                modelType = "zipformer2",
            ),
            enableEndpoint = false,
            decodingMethod = "greedy_search",
        )
        return SherpaPreviewRecognizer(OnlineRecognizer(null, config))
    }
}

private class SherpaStream(val inner: OnlineStream) : PreviewStream {
    override fun acceptWaveform(samples: FloatArray) = inner.acceptWaveform(samples, StreamingPreviewTuning.SAMPLE_RATE)
    override fun inputFinished() = inner.inputFinished()
    override fun release() = inner.release()
}

class SherpaPreviewRecognizer(private val inner: OnlineRecognizer) : PreviewRecognizer {
    override fun createStream(): PreviewStream = SherpaStream(inner.createStream(""))
    override fun isReady(stream: PreviewStream): Boolean = inner.isReady((stream as SherpaStream).inner)
    override fun decode(stream: PreviewStream) = inner.decode((stream as SherpaStream).inner)
    override fun result(stream: PreviewStream): PreviewResult {
        val r = inner.getResult((stream as SherpaStream).inner)
        return PreviewResult(r.text, r.tokens.toList(), r.timestamps)
    }
    override fun release() = inner.release()
}
```

- [ ] **Step 4: The service wiring** in `FloatingBubbleService.kt`:

(a) After `private var localEngine: LocalWhisperEngine? = null` (`:443`):

```kotlin
    // 4.4.0: THE RESIDENT PREVIEWER (spec §4.1 step 1, §7.4) — the sherpa recognizer and its own
    // executor, Main-confined like localEngine. Built lazily by warmStreamingPreview() when the
    // English pack is installed and the switch is on; released on onTrimMemory (outside a session)
    // and onDestroy; BORROWED per session by PreviewTeeEngine, never owned by it.
    private var streamingPreview: com.whispereverywhere.transcription.stream.StreamingPreviewEngine? = null
```

(b) The arbiter registration (`:760-762`) becomes:

```kotlin
        com.whispereverywhere.audio.AudioArbiter.isCapturing = {
            currentState == BubbleState.RECORDING || currentState == BubbleState.FINALIZING ||
                currentState == BubbleState.CONNECTING
        }
```
and in `AudioArbiter.kt` the KDoc at `:16` becomes `/** CONNECTING, RECORDING or FINALIZING — a session is being set up, or a transcript is still being produced (4.4.0: CONNECTING joined, spec §7.1). */`.

(c) In the prewarm coroutine, directly after `            warmLocalEngine().prewarm()` (`:783`):

```kotlin
            // 4.4.0: the previewer's ~0.8 s load and its canary (spec §7.2), off the session's
            // critical path; the pack check is inside. RULING ASSUMED (R3): the switch defaults on.
            if (app.preferencesManager.localPreviewEnabled) warmStreamingPreview()
```

(d) In `onDestroy`, directly after `        localEngine = null` (`:1141`):

```kotlin
        streamingPreview?.release()
        streamingPreview = null
```

(e) After `warmLocalEngine` (`:2497-2545`) add:

```kotlin
    /**
     * 4.4.0: build (once) and warm the resident previewer — load + canary on its own executor —
     * when the English pack is installed; null when it is not (the gate then says pack=0).
     * Idempotent: a warm engine's warm() is a no-op and a disabled one never reloads in this
     * process (spec §7.2). Called from the prewarm coroutine at service start and lazily at the
     * wrap site; the latter arms NEXT session, not this one, because warm() is asynchronous and
     * the gate reads isWarm() now — a session started under a second after the service came up
     * is exactly today's session, by design.
     */
    private fun warmStreamingPreview(): com.whispereverywhere.transcription.stream.StreamingPreviewEngine? {
        val pack = com.whispereverywhere.transcription.stream.StreamingPackCatalog.EN
        val dir = app.streamingPackManager.installedDir(pack) ?: return null
        val engine = streamingPreview ?: com.whispereverywhere.transcription.stream.StreamingPreviewEngine(
            factory = com.whispereverywhere.transcription.stream.SherpaPreviewRecognizerFactory(),
            canaryClip = { com.whispereverywhere.transcription.CanaryAudio.samples() },
            onLoadFailure = { app.streamingPackManager.markCorrupt(pack) },
        ).also { streamingPreview = it }
        engine.warm(dir, pack)
        return engine
    }
```

(f) The wrap site. Rename `        val engine: TranscriptionEngine = resolveTranscriptionEngine()` (`:2896`) to `        val baseEngine: TranscriptionEngine = resolveTranscriptionEngine()`; then, directly after the `connect lang resolved=` log line (`:2938`) and before `engine.connect(lang, …)` (`:2940`), insert:

```kotlin
        // 4.4.0: THE ONE WRAP SITE (spec §4.1 step 2, §5). After the language resolved, before
        // connect: the gate reads the RESOLVED local language (R4), the pack, the session kind
        // (cloudWrapper != null IS the right predicate here — cloud batch and live keep today's
        // strip), a running batch job, the switch (R3) and the resident previewer's readiness
        // (false while loading; false forever after a failed canary — R1). When it arms, the
        // session engine becomes the tee and transcriptionEngine is RE-POINTED at it, so the
        // capture callback, the commit funnel and the stop path all drive the tee. The strip
        // rules read sessionHasLocalPreview (Task 1's second input) — assigned the gate's answer,
        // never a constant. Every input is logged, so a "why no words?" is one grep.
        val previewPack = com.whispereverywhere.transcription.stream.StreamingPackCatalog.EN
        val packInstalled = app.streamingPackManager.isInstalled(previewPack)
        val userEnabled = app.preferencesManager.localPreviewEnabled
        val preview = if (packInstalled && userEnabled) (streamingPreview ?: warmStreamingPreview()) else streamingPreview
        val previewReady = preview?.isWarm() == true
        val previewArmed = localPreviewArms(
            sessionLanguage = lang,
            packInstalled = packInstalled,
            isCloudSession = cloudWrapper != null,
            batchJobActive = BatchJobController.active != null,
            userEnabled = userEnabled,
            previewReady = previewReady,
        )
        android.util.Log.i(
            "WE-DIAG",
            com.whispereverywhere.transcription.stream.StreamDiag.gateLine(
                lang, packInstalled, cloudWrapper != null, BatchJobController.active != null,
                userEnabled, previewReady, previewArmed,
            ),
        )
        sessionHasLocalPreview = previewArmed
        val engine: TranscriptionEngine = if (previewArmed) {
            com.whispereverywhere.transcription.stream.PreviewTeeEngine(requireNotNull(preview), baseEngine)
                .also { transcriptionEngine = it }
        } else {
            baseEngine
        }

```

(The `connectingStatusLabel` block between `:2898` and `:2925` reads `localEngine` and `cloudWrapper`, not `engine`; it is untouched.)

(g) In `onTrimMemory` (`:3398-3407`), directly after `            localEngine?.releaseContext()`:

```kotlin
            streamingPreview?.release()
```

- [ ] **Step 5: The two guard sentences.**

`app/proguard-rules.pro`, after the line `# proven on-device, release-only, 2026-07-18). Keep the whole API surface.` (`:75`):

```
# 4.4.0: the streaming previewer (OnlineRecognizer / OnlineStream / VersionInfo, reached only
# through transcription/stream/SherpaPreviewRecognizer.kt) rides this SAME rule. Never narrow it
# to the TTS classes: a narrowed keep is a GetFieldID SIGABRT at OnlineRecognizer.newFromFile in
# release builds, invisible on debug (research §5.5 item 8).
```

`NativeComputeGate.kt`, appended to the object's KDoc before the closing `*/`:

```
 *
 * **The 4.4.0 streaming previewer runs sherpa-onnx OUTSIDE this gate, deliberately**
 * (`transcription/stream/StreamingPreviewEngine`). The gate is a whole-call whisper lock; wrapping
 * a 32 ms decode loop in it would serialise the previewer behind every whisper burst and stop it
 * being streaming. sherpa's ORT sessions share nothing with whisper's contexts. Do not "fix" this.
```

- [ ] **Step 6: Run the pin class to see it pass**; then `assembleDebug` → `BUILD SUCCESSFUL` (this is the proof that `FeatureConfig(sampleRate =, featureDim =, dither =)`, `OnlineRecognizer(null, config)`, `createStream("")`, `acceptWaveform(samples, 16000)` and `VersionInfo.onnxruntimeVersion` resolve against the 1.13.7 `classes.jar`; if only the version getter fails, switch to the reflection form named in the adapter's comment). Then run `InFlightStripWiringPinTest`, `CommitFunnelPinTest`, `CapSeamPinTest`, `EndpointerLifecyclePinTest`, `DeviceAudioLatchPinTest`, `BubbleHideWiringPinTest`, `ConsentBudgetWiringPinTest` — every existing source pin on the service must stay green (the wrap inserts lines; it moves no anchor).

- [ ] **Step 7: Whole suite, then commit.** Expected: Task 6's count + 7 (1 new suite).

```
feat(stream): the previewer is wired — one wrap site after the language resolves, the resident recognizer, the gate line

SherpaPreviewRecognizerFactory is the one adapter over the AAR (the probe's config verbatim);
the service builds PreviewTeeEngine at one site between sessionLanguageFor and connect and
re-points transcriptionEngine at it; sessionHasLocalPreview takes the gate's answer; the
recognizer is warmed beside the local prewarm and released on trim/destroy; the arbiter counts
CONNECTING as capturing; the R8 rule and NativeComputeGate carry their guard sentences.
```

**Battery:** (1) move the wrap block ABOVE `val lang = sessionLanguageFor(` → `theTeeIsBuiltAtOneSite…` RED (order); revert. (2) drop `.also { transcriptionEngine = it }` → RED; revert. (3) replace `isCloudSession = cloudWrapper != null` with `isCloudSession = sessionIsLive` → `theGateReadsTheSessionKind…` RED; revert. (4) delete `streamingPreview?.release()` from `onTrimMemory` → `theResidentPreviewerIsReleased…` RED; revert. (5) remove `|| currentState == BubbleState.CONNECTING` → `theArbiterCountsConnecting…` RED; revert. (6) write `sessionHasLocalPreview = true` at the wrap → `theFlagIsAssignedTheGatesAnswer…` RED (and Task 1's pin too); revert.

---

### Task 8: Certification, the acceptance sheet Z1–Z11, merge readiness

**Size:** 0.5 day. **Not in this task:** the identity bump (4.4.0 / 89) — the controller's commit (spec R5).

**Files:**
- Create: `docs/superpowers/sdd/2026-09-10-440-streaming-previewer/acceptance.md`
- Modify: `.superpowers/sdd/2026-09-10-440-streaming-previewer/progress.md`

- [ ] **Step 1: Full boundary run.** Clean porcelain; purge XML; whole suite EXECUTED; count from XML. Expected: Task 1's baseline + 8 (T1) + 16 (T2) + 12 (T3) + 25 (T4) + 16 (T5) + 10 (T6) + 7 (T7) = **+94 tests over 13 new suites** (StreamingPackCatalogTest, StreamingPackInstallTest, StreamingPreviewTuningTest, PreviewCanaryTest, PreviewTextTest, PcmRingTest, StreamDiagTest, StreamingPreviewEngineTest, PreviewComposerTest, PreviewTeeEngineTest, LocalPreviewGateTest, StreamingPackCopyTest, LocalPreviewWiringPinTest; T1 adds tests to two existing suites — recount from the XML and record the true figure), `failures=0`, `errors=0`. `assembleDebug` green. Also the release bundle to prove `lintVitalRelease` and R8 still pass with the new `com.k2fsa` consumer: `.\gradlew.bat :app:bundleRelease --no-daemon` → `BUILD SUCCESSFUL` (the signing config exists; do not touch keystore files).

- [ ] **Step 2: Re-run every battery row** listed in Tasks 1–7 from a clean tree, in one session, recording per row: class, mutation, RED failures count, GREEN after revert. Any survivor = fix the pin in a micro-round before proceeding.

- [ ] **Step 3: Write the acceptance sheet** `docs/superpowers/sdd/2026-09-10-440-streaming-previewer/acceptance.md`:

```markdown
# 4.4.0 — device acceptance (owner session): the on-device word-for-word previewer

Install: the INTERNAL TRACK build (never adb installDebug) on the Fold6 and the Tab. Capture: on the PC,
`C:\Users\bastr\AppData\Local\Android\Sdk\platform-tools\adb.exe logcat -s WE-DIAG *>> C:\Users\bastr\.androidbuild\capture-440.txt`
(append, never clear; leave it running for the whole session). Before Z1: Settings → "Live words while you
speak (English)" → Download (73 MB) on both devices; "Show live words" ON.

RULINGS ASSUMED by the build (spec §0): R1 canary-only + release note · R2 label displaced · R3 default-on ·
R4 Auto = whisper only on multilingual tiers · R5 4.4.0/89 · R6 streaming first. Mark any you rule otherwise.

## Z1 — words appear while speaking (Fold6 on pro; Fold6 on npu-turbo with English picked; Tab on multi with English picked)
Dictate five ordinary sentences. Expect lowercase unpunctuated words on the strip ~0.5 s behind each word
(the first word ~1.2-1.5 s after you start). Grep: `stream-gate: … -> preview=1` at the session start;
one `stream-timing: seq=N … padMs=500 shed=0 retract=0` per sentence. FAIL: no words; any `retract>0`
on clean speech; any `shed=1`; `preview=0` with `pack=1 enabled=1 ready=1 lang=en`.

## Z2 — the typed result is 4.3.4's
Read the five sentences as typed. Expect cased, punctuated, numerals — exactly what 4.3.4 typed for the same
audio (record 4.3.4's text for two of them beforehand). FAIL: any difference in the typed text.

## Z3 — a five-minute read (Tab on multi, English picked)
Read a printed page for five minutes. Expect the strip never more than a tick behind (no growing backlog),
`rtf=` under 0.25 on every `stream-timing:` line, `shed=0` throughout. `dumpsys battery` before and after:
temperature moves < 3.0 °C; `dumpsys power | grep -i thermal` status stays 0. This is the streamer-beside-
whisper thermal read rung 3 did not run. FAIL: a lag that grows; `shed=1`; a thermal step.

## Z4 — Auto on multi / npu-turbo is byte-identical to 4.3.4
Language Auto, dictate two sentences. Expect the "Transcribing…" line, no words, the same typed text as 4.3.4.
Grep: `stream-gate: lang=auto … -> preview=0`. FAIL: any words on the strip.

## Z5 — Spanish picked on multi
Dictate two Spanish sentences. Expect no live words; the onboarding language step's sentence explains why
(open it once and read it). `stream-gate: lang=es … -> preview=0`. FAIL: English words over Spanish.

## Z6 — a device-audio (YouTube) session, edited video, English
Expect words streaming; the flatline cut still fires (`endpoint: … cut=flat`); the typed text is whisper's
(a boundary word the strip got wrong is expected — rung 1 §6 — and the typed text must not carry it).
FAIL: a lost cut; a sentence typed twice.

## Z7 — source switch mid-utterance
Speak, switch mic → device audio mid-sentence, speak on. Expect `endpoint: … cut=switch` followed by a
`stream-timing:` for that seq and words continuing on the fresh stream; no word typed twice or dropped.

## Z8 — read-aloud during CONNECTING and during RECORDING
Copy a paragraph, then tap the mic and tap the speaker lobe at once (CONNECTING), and again mid-session.
Expect both refused (no Kokoro playback), no stutter on the strip. FAIL: audio overlap.

## Z9 — a batch file job while dictating
Start a file transcription, then tap the mic. Expect `stream-gate: … batch=1 -> preview=0`, a 4.3.4 session,
and the batch job completing. FAIL: contention, a crash, words on the strip.

## Z10 — stop mid-sentence
Stop while a word is on the strip. Expect the tail in the typed text, the strip showing "Finishing
transcript…" and then coming down with the session; nothing parked afterwards. FAIL: a lost tail; a strip
left INVISIBLE-occupying after the session ends.

## Z11 — the canary line, both devices, at service start
Grep: `stream-open: sherpa=1.13.7 ort=1.27.1 threads=2 provider=cpu loadMs=<n> canary=pass canaryMs=<n>
outLen=<n> load=ok` once per service start on BOTH devices. FAIL: `canary=fail`, `canary=none`, or
`load=fail` on either — report the whole line.

Promote when Z1-Z11 pass. R1's release-notes sentence ships with the promotion: "On some 2026 flagships the
live-words preview may stay blank; the typed transcript is unaffected."
```

- [ ] **Step 4: Ledger close-out** — append to `progress.md`: the per-task commit list, the final suite count, the battery summary (rows / killed / survivors), the acceptance sheet path, the identity note ("4.4.0 / 89 is the controller's commit; ReleaseIdentityTest still pins 88 / 4.3.4 on this branch"), and the merge instruction: fast-forward onto `main` only after the controller's identity commit and the owner marking Z1, Z2, Z4 and Z11 PASS on both devices.

- [ ] **Step 5: Commit**

```
docs(4.4.0): acceptance sheet Z1-Z11 + ledger close-out — certified <suites>/<tests>/0

Battery re-run from a clean tree (rows/killed/survivors recorded). Merge is gated on the
controller's identity commit and the owner's device session: Z1, Z2, Z4, Z11 on both devices.
```

---

## Self-review

**Spec coverage.** §0 rulings: R1 → the canary as the only guard (T3, T4 `warm`, T7's `previewReady`), the release-notes sentence (T8); R2 → `inFlightStripLabel`'s first row (T1); R3 → the preference default (T6); R4 → the gate's `== "en"` over the RESOLVED language (T6); R5 → out of the plan by design (T8 notes it); R6 → a calendar statement. §2 constants → `StreamingPreviewTuning` + its test (T3), the leading-space join (T4 `PreviewText`), the throttle interplay (T4 `theThrottleNeverBites…`), the RSS/thermal budgets (T8 Z3). §3 components → T2 (pack), T3 (seam, canary), T4 (engine, text, ring, diag), T5 (composer, tee), T6 (gate, pref, copy), T7 (adapter, wiring). §4.1 data flow → T7 (steps 1, 2, 10), T5 (3, 4, 6, 7, 8), T4 (5, 6, 9). §4.2 strip contract → T1 (rules), T5 (composition), T4 (freeze/trim). §4.3 → T4. §5 → T6. §6 → T2 (files, layout, marker, size gate, atomic install, corrupt/missing, delete), T6 (surfaces). §7 → T4 (failures, threading), T7 (arbiter, trim/destroy). §8 → T4 `StreamDiag`. §9 → T6. §10 → the Global Constraints' do-not-edit list and T5's pass-through pins. §12 → every test. §13 → T8.

**Placeholder scan.** Every code step carries the code. The two places that say "if X does not compile, use Y" (T7's `VersionInfo.onnxruntimeVersion` → the probe's reflection form; T6's `context` in the onboarding screen) name the exact alternative. The suite counts are stated as deltas over a baseline Task 1 measures, because the last certified figure (177 / 2,150) predates three docs commits — that is an instruction to measure, not a placeholder. The `Claude-Session:` trailer is the implementing session's own URL by rule.

**Type consistency.** `PreviewResult(text, tokens: List<String>, timestamps: FloatArray)` is used identically in T3 (seam, fake), T4 (`PreviewText.before`, engine) and T7 (adapter). `LocalPreview`'s four members match between T4 (engine implements), T5 (tee calls, `FakePreview`). `PreviewRecognizerFactory.load(dir, pack, numThreads)` + `sherpaVersion()` / `ortVersion()` match T3 (interface, `ScriptedFactory`), T4 (engine) and T7 (adapter). `localPreviewArms`'s six parameters match T6 (declaration, test) and T7 (call, pin needles). `StreamDiag.gateLine`'s seven arguments match T4 (definition, test) and T7 (call). The strip rules' signatures match T1 (declarations, tests, pin needles) and T7 reads none of them directly. `StreamingPackCatalog.EN` / `installedDir` / `isInstalled` / `markCorrupt` match T2 and T7. `CanaryVerdict.code` values (`pass` / `fail` / `none`) plus the engine's `skipped` match T3, T4 and the `stream-open:` format in T4's `StreamDiagTest`.

**Fixture arithmetic (re-derived).** First decode at 7,200 samples (chunk 15 → clock 480 ms), then every 5,120 (chunks 25, 35, 45, 55, 65, 75): 7 decodes over 80 chunks; the 500 ms pad adds 8,000 → 48,960 → 9 decodes; the canary clip (40,960) + pad → 9 decodes; the retained-tail test's cut `(2,048 − 800) / 1000 = 1.248 s` keeps two of three tokens; the ring test's newest even count is `[1, 2]`.

**Scope.** No task edits `LocalWhisperEngine`, the NPU backends, the cloud engines, the endpointer, the catalog, the delivery path or `TranscriptionEngine`. T1 is alone on `main`. The identity bump is not here. The one change outside the tee (the arbiter's CONNECTING) is carried from research §3.9 with its own pin.
