# 3.4.0 Implementation Plan — Resizable Transcript Window, Final-Only Commit, Speaker-Morph Removal

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship Whisper Everywhere 3.4.0: the main bubble is always a mic (selection-morph deleted), dictation commits to the target field exactly once at stop while the bubble window accumulates live, and the transcript window is user-resizable with persisted size and size-aware on-screen clamping.

**Architecture:** Three sequential workstreams over the two overlay services. A (delete the selection→speaker morph + TTS own-voice authority), B (unify the session preview pipeline, suppress mid-session injection, single policy-driven delivery at stop), C (view-level resize via a corner handle + one parameterized position clamp fed real window dimensions). New logic lands as pure JVM-testable classes (`FinalDeliveryPolicy`, `ResizeMath`) wired thinly into the services, per house convention. D closes out user-facing copy and the version bump.

**Tech Stack:** Kotlin, AGP 8.7.3 / Gradle 8.14.4, JDK 21, JUnit4 JVM tests. No new dependencies.

**Spec (authoritative on any ambiguity):** `docs/superpowers/specs/2026-08-08-bubble-resize-final-commit-speaker-fix-design.md`

## Global Constraints

- **Order:** Workstream A (Tasks A1…) → B → C → D. Within a workstream, tasks run in order. Line-number hints were verified at HEAD `ae86024` and shift as tasks land — anchor by the named symbol/function, not the number.
- **Build/test (PowerShell, from repo root; set JAVA_HOME every invocation):**
  - Full JVM suite: `$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon`
  - One class: append `--tests "com.whispereverywhere.<pkg>.<Class>"`
  - Compile: `$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:assembleDebug --no-daemon`
  - Build output is relocated outside the repo: APK at `C:\Users\bastr\.androidbuild\WhisperEverywhere\app\outputs\apk\debug\app-debug.apk`, test report at `C:\Users\bastr\.androidbuild\WhisperEverywhere\app\reports\tests\testDebugUnitTest\index.html`.
- **NEVER run `:app:installDebug` or `:app:connectedDebugAndroidTest`** — both uninstall first and destroy the owner's 500+ MB on-device models. Device installs are owner-run: `adb.exe install -r <apk>` (adb at `C:\Users\bastr\AppData\Local\Android\Sdk\platform-tools\adb.exe`). There is no instrumented-test workflow; verification is JVM tests + owner-run on-device checklists.
- **Tests:** JVM only, under `app/src/test/java/com/whispereverywhere/...`. `FloatingBubbleService` / `WhisperAccessibilityService` have no direct tests by convention — extract pure logic instead. Concurrency-adjacent tests use a real background executor (`Executors.newSingleThreadExecutor()`), never a same-thread executor.
- **Do not touch:** `EmptyExpected`/`FallbackPolicy.reconcile`, `SegmentOrderer` semantics, `decideEngineChoice`/`sttLiveMode`, disclosure texts (no v3→v4 bump), Play declarations.
- **Every commit message** ends with exactly these two trailer lines:

  ```
  Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT
  ```

  The per-task commit commands in this plan quote only the headline `-m` for brevity. ALWAYS run them with a second `-m` appending the trailers verbatim (PowerShell — the backtick-n is a literal newline):

  ```powershell
  git commit -m "<headline from the task>" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`nClaude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT"
  ```

---

---

### Task A1: Retire the accessibility-side selection watcher (the morph's only producer)

**Files:**
- Modify: `app/src/main/java/com/whispereverywhere/service/WhisperAccessibilityService.kt`
  - `selectionDebounceJob` field + `handleSelectionChanged` function (lines ~133–184 at HEAD ae86024, between `fireCopyDetected` and `onServiceConnected`)
  - the call site inside `onAccessibilityEvent`, in the `TYPE_VIEW_TEXT_CHANGED / TYPE_VIEW_TEXT_SELECTION_CHANGED` when-branch (~:267–272)
  - companion constants `SELECTION_DEBOUNCE_MS` / `CLEAR_DEBOUNCE_MS` / `MAX_SELECTION_CHARS` (~:1336–1340, inside `companion object`)

**Interfaces:**
- Consumes: nothing from earlier tasks (this workstream runs first, on a clean tree at HEAD ae86024).
- Produces: `WhisperAccessibilityService` still compiles with the (now inert) `OnTextSelectionListener` interface, `selectionListener` field, and `setSelectionListener(listener: OnTextSelectionListener?)` — Task A2 deletes those together with their last remaining references in `FloatingBubbleService`. After this task, NO accessibility event can reach any selection listener: the mic→speaker morph bug is dead at the source.

Context for the engineer: `TYPE_VIEW_TEXT_SELECTION_CHANGED` events feed two consumers in this file. The one being deleted is `handleSelectionChanged` (read-aloud speaker morph). The one being **preserved untouched** is the caret-tracking catch-all further down the same when-branch (the `else if` at ~:290–307 that checks `event.fromIndex >= 0 && event.fromIndex == event.toIndex && source.isFocused` — commented "CARET tracking, the third catch-all layer"). Do not touch that branch, `fireCopyDetected`, or anything clipboard-related.

- [ ] **Step 1: Delete the `selectionDebounceJob` field and the whole `handleSelectionChanged` function.** In `WhisperAccessibilityService.kt`, find this exact block (it sits immediately after `fireCopyDetected`'s closing brace and immediately before `override fun onServiceConnected()`), and delete everything shown except the final `override fun onServiceConnected() {` line, which is the anchor and stays:

```kotlin
    private var selectionDebounceJob: kotlinx.coroutines.Job? = null

    /**
     * Selection watching is OPPORTUNISTIC (Chromium page text, old-Compose apps and PDF viewers
     * never emit these events — the PROCESS_TEXT toolbar entry covers those). Debounced 400 ms
     * because events fire on every selection-handle drag.
     */
    private fun handleSelectionChanged(event: AccessibilityEvent) {
        val source = event.source
        val pkg = (event.packageName ?: source?.packageName)?.toString()
        // Never react to our own overlay/app windows.
        if (pkg == packageName) return
        // The EVENT carries the selection for TYPE_VIEW_TEXT_SELECTION_CHANGED — the source
        // node's textSelectionStart/End is often -1/stale (proven on-device 2026-07-18:
        // Messages delivered -1/-1 on the node while fromIndex/toIndex were correct).
        var start = event.fromIndex
        var end = event.toIndex
        var text = event.text?.firstOrNull()?.toString() ?: source?.text?.toString()
        if ((start < 0 || end < 0) && source != null) {
            start = source.textSelectionStart
            end = source.textSelectionEnd
            text = source.text?.toString() ?: text
        }
        if (start > end) {
            val t = start; start = end; end = t // dragging the start handle inverts the range
        }
        val valid = text != null && start in 0 until end && end <= text.length
        android.util.Log.i(
            "WE-TTS",
            "selection event: pkg=$pkg start=$start end=$end " +
                "textLen=${text?.length ?: -1} valid=$valid listener=${selectionListener != null}",
        )
        selectionDebounceJob?.cancel()
        if (valid) {
            val selected = text!!.substring(start, end).take(MAX_SELECTION_CHARS)
            if (selected.isBlank()) return
            selectionDebounceJob = serviceScope.launch {
                delay(SELECTION_DEBOUNCE_MS)
                android.util.Log.i("WE-TTS", "selection notify: len=${selected.length}")
                selectionListener?.onTextSelected(selected)
            }
        } else {
            // Debounce the CLEAR too: fields fire start==end selection events for cursor
            // blinks/IME updates while text is still visually selected — an instant clear made
            // the speaker icon flicker away (user-reported). A valid selection arriving within
            // the grace window cancels the pending clear above.
            selectionDebounceJob = serviceScope.launch {
                delay(CLEAR_DEBOUNCE_MS)
                selectionListener?.onSelectionCleared()
            }
        }
    }

    override fun onServiceConnected() {
```

  After the edit the file reads: `fireCopyDetected`'s closing brace, one blank line, then `override fun onServiceConnected() {`.

- [ ] **Step 2: Delete the dispatch call site, preserving the rest of the when-branch.** In `onAccessibilityEvent` (same file, ~:267), replace:

```kotlin
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                // Read-aloud: selection watching rides the same event (Track F).
                if (event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) {
                    handleSelectionChanged(event)
                }
                // Text activity - this confirms we have an active input
```

  with:

```kotlin
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                // Text activity - this confirms we have an active input
```

  Everything below that comment in the branch — including the caret-tracking `else if` (`event.fromIndex >= 0 && event.fromIndex == event.toIndex && source.isFocused`) — stays byte-identical. One wording fix inside the preserved caret comment: that comment ends with "the read-aloud selection watcher above already consumed those." — the watcher above is now gone, so replace that one sentence-line:

```kotlin
                        // press select/copy, which produces RANGES) from false-firing the bubble;
                        // the read-aloud selection watcher above already consumed those.
```

  with:

```kotlin
                        // press select/copy, which produces RANGES) from false-firing the bubble.
```

- [ ] **Step 3: Delete the debounce/cap constants from the companion object.** At ~:1336 replace:

```kotlin
        private const val SELECTION_DEBOUNCE_MS = 400L
        private const val CLEAR_DEBOUNCE_MS = 800L
        // Whole-page reads are chunked sentence-by-sentence inside the engine, so a large cap
        // streams instead of overloading anything (user decision 2026-07-18).
        private const val MAX_SELECTION_CHARS = 100_000

        fun setSelectionListener(listener: OnTextSelectionListener?) {
```

  with:

```kotlin
        fun setSelectionListener(listener: OnTextSelectionListener?) {
```

  (`MAX_SELECTION_CHARS` had exactly one consumer — the function deleted in Step 1; verified at HEAD. `SpeakTextActivity` has its own independent `MAX_CHARS`.) Leave `selectionListener`, `setSelectionListener`, and the `OnTextSelectionListener` interface in place — `FloatingBubbleService` still implements/calls them until Task A2; they are unreachable dead wiring, not behavior.

- [ ] **Step 4: Compile.**

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:assembleDebug --no-daemon
```

  Expect: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Run the full JVM suite.** (Spec-verified: zero test files reference `speakMode|onTextSelected|selectionChanged`, so no test edits are needed.)

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon
```

  Expect: `BUILD SUCCESSFUL`, no failed tests.

- [ ] **Step 6: Commit.**

```powershell
git add app/src/main/java/com/whispereverywhere/service/WhisperAccessibilityService.kt
git commit -m "fix(bubble): the selection watcher is dead — nothing feeds the speaker morph now"
```

---

### Task A2: Delete the bubble-side morph state, render arm, and the listener plumbing end to end

**Files:**
- Modify: `app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt`
  - class declaration (~:148), field block (~:152–157), `MORPH_REVERT_MS` (~:264–265), `registerFocusListener` (~:440), the Track-F section (`onTextSelected`/`onSelectionCleared`/`scheduleMorphRevert`, ~:521–563), `exitSpeakingVisuals` comment (~:613), `startSpeaking` (~:618, ~:656), `onDestroy` (~:691), `handleBubbleClick` (~:1366), `startRecording` (~:1802–1805), `updateBubbleState` IDLE branch (~:2291–2297) — all line numbers are HEAD ae86024; Task A1 removed ~60 lines from a *different* file, so these are unshifted.
- Modify: `app/src/main/java/com/whispereverywhere/service/WhisperAccessibilityService.kt`
  - `OnTextSelectionListener` interface (~:99–103) and companion `selectionListener`/`setSelectionListener` (post-A1, directly above `clipboardListener`)

**Interfaces:**
- Consumes: the post-A1 state of `WhisperAccessibilityService` (producer gone; interface + setter still present, deleted here with their last references).
- Produces: `FloatingBubbleService` keeps `@Volatile private var isSpeakingNow` as the only read-aloud state flag — Task A3 replaces the media-detector consult of it; `updateBubbleState`'s IDLE icon is now strictly `isSpeakingNow ? ic_stop_speech : ic_mic`.

Caution: do **not** delete `R.drawable.ic_speaker` — it is still used by the paused-pill icon in `handleBubbleClick` (`if (nowPaused) bubbleIcon.setImageResource(R.drawable.ic_speaker)`) and by `floating_bubble.xml:222` (the speaker lobe's `speakClipIcon`). Do not touch `readClipboardAndSpeak`, `pulseSpeakerLobe`, `onClipboardChanged`, `enterSpeakingVisuals`, or `SpeakTextActivity` — the clipboard/lobe/PROCESS_TEXT read-aloud paths all survive.

- [ ] **Step 1: Drop the interface from the class declaration.** In `FloatingBubbleService.kt` replace:

```kotlin
class FloatingBubbleService : Service(),
    WhisperAccessibilityService.OnTextFieldFocusListener,
    WhisperAccessibilityService.OnTextSelectionListener,
    WhisperAccessibilityService.OnClipboardChangedListener,
    MediaSessionDetector.MediaPlaybackListener {
```

  with:

```kotlin
class FloatingBubbleService : Service(),
    WhisperAccessibilityService.OnTextFieldFocusListener,
    WhisperAccessibilityService.OnClipboardChangedListener,
    MediaSessionDetector.MediaPlaybackListener {
```

- [ ] **Step 2: Delete `speakModeText` and `morphRevertJob`; keep `isSpeakingNow`.** Replace (~:152–158):

```kotlin
    // Read-aloud (Track F): the text captured by the live selection watcher. Non-null = the
    // idle bubble shows a speaker and a tap SPEAKS instead of recording. Cleared when the
    // selection collapses; never set while a session is active.
    @Volatile private var speakModeText: String? = null
    @Volatile private var isSpeakingNow = false
    private var morphRevertJob: Job? = null
    private lateinit var speechStopIcon: ImageView
```

  with:

```kotlin
    // Read-aloud (Track F): true while OUR read-aloud audio is playing (speaker lobe, clipboard
    // copy, or PROCESS_TEXT toolbar). The idle bubble is ALWAYS a mic (owner rule 2026-08-08);
    // speaker behavior never arrives via text selection.
    @Volatile private var isSpeakingNow = false
    private lateinit var speechStopIcon: ImageView
```

- [ ] **Step 3: Delete `MORPH_REVERT_MS`.** Replace (~:262–267):

```kotlin
    private val FINALIZE_TIMEOUT_MS = 300_000L

    // Untapped speaker morph reverts to the mic after this window (Track F).
    private val MORPH_REVERT_MS = 20_000L

    // Wall-clock cap per uncommitted stretch. Continuous loud audio (media playback, music) never
```

  with:

```kotlin
    private val FINALIZE_TIMEOUT_MS = 300_000L

    // Wall-clock cap per uncommitted stretch. Continuous loud audio (media playback, music) never
```

- [ ] **Step 4: Deregister the selection listener in `registerFocusListener`.** Replace (~:439–441):

```kotlin
        WhisperAccessibilityService.setFocusListener(this)
        WhisperAccessibilityService.setSelectionListener(this)
        WhisperAccessibilityService.setClipboardListener(this)
```

  with:

```kotlin
        WhisperAccessibilityService.setFocusListener(this)
        WhisperAccessibilityService.setClipboardListener(this)
```

- [ ] **Step 5: Delete `onTextSelected`, `onSelectionCleared`, `scheduleMorphRevert`, and retitle the section.** Replace the block from the section banner (~:521) through `scheduleMorphRevert`'s closing brace (~:563) — it ends immediately before the `readClipboardAndSpeak` kdoc, which is the anchor and stays:

```kotlin
    // ========== Read-aloud (Track F): selection -> speaker morph -> speak/stop ==========

    override fun onTextSelected(text: String) {
        serviceScope.launch(Dispatchers.Main) {
            // Never morph mid-session; selection during capture follows the session rules.
            val installed = com.whispereverywhere.tts.TtsController.isVoiceInstalled(this@FloatingBubbleService)
            android.util.Log.i(
                "WE-TTS",
                "morph check: state=$currentState speaking=$isSpeakingNow installed=$installed",
            )
            if (currentState != BubbleState.IDLE || isSpeakingNow) return@launch
            if (!installed) return@launch
            speakModeText = text
            bubbleIcon.setImageResource(R.drawable.ic_speaker)
            scheduleMorphRevert()
            // Hide the ~2 s model load inside the user's think-time between select and tap.
            com.whispereverywhere.tts.TtsController.preload(this@FloatingBubbleService)
        }
    }

    override fun onSelectionCleared() {
        serviceScope.launch(Dispatchers.Main) {
            if (isSpeakingNow) return@launch // keep the speaking pill while audio plays
            morphRevertJob?.cancel()
            speakModeText = null
            if (currentState == BubbleState.IDLE) bubbleIcon.setImageResource(R.drawable.ic_mic)
        }
    }

    /**
     * The morph EXPIRES (user decision 2026-07-18): an untapped speaker reverts to the mic
     * after a grace window, so the bubble never sticks in speak mode from an old selection.
     */
    private fun scheduleMorphRevert() {
        morphRevertJob?.cancel()
        morphRevertJob = serviceScope.launch(Dispatchers.Main) {
            delay(MORPH_REVERT_MS)
            if (!isSpeakingNow && currentState == BubbleState.IDLE) {
                speakModeText = null
                bubbleIcon.setImageResource(R.drawable.ic_mic)
            }
        }
    }

    /**
     * Read whatever is on the clipboard aloud. Android 10+ blocks background clipboard reads,
```

  with:

```kotlin
    // ========== Read-aloud (Track F): speaker lobe / clipboard copy -> speak/stop ==========

    /**
     * Read whatever is on the clipboard aloud. Android 10+ blocks background clipboard reads,
```

  (Note: the `TtsController.preload` call dies here with `onTextSelected` — Task A4 re-homes it in `onClipboardChanged`, per spec.)

- [ ] **Step 6: Fix the stale comment in `exitSpeakingVisuals`.** Replace:

```kotlin
        // The IDLE branch restores icon/width/blob for the current morph state.
```

  with:

```kotlin
        // The IDLE branch restores icon/width/blob for the current speaking state.
```

- [ ] **Step 7: Remove the two morph touches in `startSpeaking`.** Replace the function head:

```kotlin
    private fun startSpeaking(text: String) {
        morphRevertJob?.cancel()
        isSpeakingNow = true
```

  with:

```kotlin
    private fun startSpeaking(text: String) {
        isSpeakingNow = true
```

  and, in the same function's `speakFromTrigger` onDone lambda, replace:

```kotlin
            exitSpeakingVisuals()
            if (speakModeText != null) scheduleMorphRevert()
        }
    }
```

  with:

```kotlin
            exitSpeakingVisuals()
        }
    }
```

- [ ] **Step 8: Deregister in `onDestroy`.** Replace (~:690–692):

```kotlin
        WhisperAccessibilityService.setFocusListener(null)
        WhisperAccessibilityService.setSelectionListener(null)
        WhisperAccessibilityService.setClipboardListener(null)
```

  with:

```kotlin
        WhisperAccessibilityService.setFocusListener(null)
        WhisperAccessibilityService.setClipboardListener(null)
```

- [ ] **Step 9: Delete the tap case in `handleBubbleClick`.** Replace (~:1366–1367):

```kotlin
                speakModeText != null -> startSpeaking(speakModeText!!)
                else -> startRecording()
```

  with:

```kotlin
                else -> startRecording()
```

- [ ] **Step 10: Delete the writer in `startRecording` and trim its comment.** Replace (~:1802–1806):

```kotlin
        // Capture wins instantly over read-aloud (Track F exclusivity rule); any selection
        // made before dictation is stale by definition once a session starts (review fix I2).
        com.whispereverywhere.audio.AudioArbiter.requestCapture()
        speakModeText = null
        isSpeakingNow = false
```

  with:

```kotlin
        // Capture wins instantly over read-aloud (Track F exclusivity rule).
        com.whispereverywhere.audio.AudioArbiter.requestCapture()
        isSpeakingNow = false
```

- [ ] **Step 11: Collapse the IDLE icon render in `updateBubbleState`.** Replace (~:2291–2297, inside `BubbleState.IDLE ->`):

```kotlin
                    bubbleIcon.setImageResource(
                        when {
                            isSpeakingNow -> R.drawable.ic_stop_speech
                            speakModeText != null -> R.drawable.ic_speaker
                            else -> R.drawable.ic_mic
                        },
                    )
```

  with:

```kotlin
                    bubbleIcon.setImageResource(
                        if (isSpeakingNow) R.drawable.ic_stop_speech else R.drawable.ic_mic,
                    )
```

- [ ] **Step 12: Delete the interface in `WhisperAccessibilityService.kt`.** Replace (~:99–106):

```kotlin
    /** Read-aloud (Track F): cross-app text-selection events for the speaker-bubble morph. */
    interface OnTextSelectionListener {
        fun onTextSelected(text: String)
        fun onSelectionCleared()
    }

    /** Fires when ANY app puts something on the clipboard (best-effort; OEM-dependent). */
```

  with:

```kotlin
    /** Fires when ANY app puts something on the clipboard (best-effort; OEM-dependent). */
```

- [ ] **Step 13: Delete the companion plumbing.** In the same file's `companion object` (post-A1 the setter sits directly between `selectionListener` and `clipboardListener`), replace:

```kotlin
        @Volatile private var selectionListener: OnTextSelectionListener? = null

        fun setSelectionListener(listener: OnTextSelectionListener?) {
            selectionListener = listener
        }

        @Volatile private var clipboardListener: OnClipboardChangedListener? = null
```

  with:

```kotlin
        @Volatile private var clipboardListener: OnClipboardChangedListener? = null
```

- [ ] **Step 14: Compile.**

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:assembleDebug --no-daemon
```

  Expect: `BUILD SUCCESSFUL`. (If it fails with an unresolved `speakModeText`/`scheduleMorphRevert`/`OnTextSelectionListener` reference, a deletion site was missed — the complete site list is exactly Steps 1–13; re-check against them.)

- [ ] **Step 15: Run the full JVM suite.**

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon
```

  Expect: `BUILD SUCCESSFUL`, no failed tests.

- [ ] **Step 16: Commit.**

```powershell
git add app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt app/src/main/java/com/whispereverywhere/service/WhisperAccessibilityService.kt
git commit -m "fix(bubble): the main bubble is always a mic — speaker morph deleted end to end"
```

---

### Task A3: One own-voice authority — TtsController-backed self-audio for media detection

**Files:**
- Create: `app/src/test/java/com/whispereverywhere/service/MediaPollPolicyTest.kt`
- Create: `app/src/test/java/com/whispereverywhere/tts/TtsControllerSpeechActiveTest.kt`
- Modify: `app/src/main/java/com/whispereverywhere/service/MediaSessionDetector.kt` (decision line inside `checkAudioPlaybackState`, ~:146 at HEAD; new top-level pure function after the `MediaSessionDetector` class's closing brace, before the `MediaNotificationListener` kdoc)
- Modify: `app/src/main/java/com/whispereverywhere/tts/TtsController.kt` (new `isSpeechActive()` directly after `preload`, ~:51–53)
- Modify: `app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt` (`onCreate`, the `mediaDetector.selfAudioActive` assignment — ~:351–352 at HEAD; A2's deletions above this point total only ~7 lines, so expect it near ~:345 — find it by the quoted text, not the number)

**Interfaces:**
- Consumes: `TtsEngine.isSpeaking(): Boolean` and `TtsController`'s private `@Volatile private var engine: TtsEngine?` (both existing).
- Produces:
  - `fun isPolledAudioMedia(isAudioActive: Boolean, selfAudioActive: Boolean, alreadyPlaying: Boolean): Boolean` — top-level in package `com.whispereverywhere.service` (file `MediaSessionDetector.kt`).
  - `TtsController.isSpeechActive(): Boolean` — the process-wide "our TTS is audible" authority.

Why this shape (spec: "design the seam so it is JVM-testable if the decision logic permits"): the detector's self-audio check **is** a service-injected lambda (`selfAudioActive: () -> Boolean`), but the classification that consumes it lives inline in `checkAudioPlaybackState`, which needs `AudioManager` + a main-looper `Handler` — not JVM-runnable. So the decision is extracted as a pure top-level function (the house pattern: `planUnitOutcome`/`shouldSoftLatchCloud` at file scope in `TtsEngine.kt`, pinned by `TtsEngineSeamTest`) and TDD'd. The remaining wiring — the one-line lambda in `FloatingBubbleService.onCreate` and the one-line call-site swap in the detector — is Android-service glue, untestable by house convention. `isSpeechActive()`'s true-path is also not JVM-reachable (constructing `TtsEngine` requires `Handler(Looper.getMainLooper())`), so only its null-engine contract is pinned; its set/clear correctness is inherited from `TtsEngine.speaking`, which is already generation-guarded (see Step 6's kdoc) and is owner-verified on-device. No concurrency seam exists in these tests, so the real-background-executor house rule does not apply here.

- [ ] **Step 1: Write the failing decision-table test.** Create `app/src/test/java/com/whispereverywhere/service/MediaPollPolicyTest.kt`:

```kotlin
package com.whispereverywhere.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The audio-polling branch's media classification, tested PURE. The detector's polling loop
 * cannot run on the JVM (AudioManager + main-looper Handler), but the decision it applies can
 * and must: OUR OWN read-aloud voice is never transcribable media. Pre-fix, only service-driven
 * reads were covered, so a PROCESS_TEXT toolbar read got classified as media — bubble summoned
 * with a "Tap bubble to transcribe audio" toast over its own voice, and a tap mid-read recorded
 * our own TTS.
 */
class MediaPollPolicyTest {

    @Test fun controller_level_tts_active_is_self_audio_not_media() {
        // THE spec case: audio is audible (isMusicActive counts our AudioTrack) but it is ours.
        assertFalse(isPolledAudioMedia(isAudioActive = true, selfAudioActive = true, alreadyPlaying = false))
    }

    @Test fun real_audio_with_no_self_voice_is_media() {
        assertTrue(isPolledAudioMedia(isAudioActive = true, selfAudioActive = false, alreadyPlaying = false))
    }

    @Test fun silence_is_never_media() {
        assertFalse(isPolledAudioMedia(isAudioActive = false, selfAudioActive = false, alreadyPlaying = false))
        assertFalse(isPolledAudioMedia(isAudioActive = false, selfAudioActive = true, alreadyPlaying = false))
    }

    @Test fun an_already_flagged_episode_is_not_reannounced() {
        // The poll only STARTS a media episode; a running one must not re-fire
        // onMediaPlaybackStarted (duplicate-notification guard, preserved from the inline check).
        assertFalse(isPolledAudioMedia(isAudioActive = true, selfAudioActive = false, alreadyPlaying = true))
    }
}
```

- [ ] **Step 2: Run it, expect compile failure.**

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon --tests "com.whispereverywhere.service.MediaPollPolicyTest"
```

  Expect: `BUILD FAILED` — Kotlin compilation error `Unresolved reference 'isPolledAudioMedia'` in `MediaPollPolicyTest.kt`.

- [ ] **Step 3: Implement the pure function.** In `MediaSessionDetector.kt`, insert AFTER the `MediaSessionDetector` **class's** closing brace at ~:350 and BEFORE the `MediaNotificationListener` kdoc at ~:352 (the `/**` beginning `NotificationListenerService required to access MediaSessionManager`). Careful: the `companion object` (~:279–349) sits between `fun isCurrentlyPlaying()` and the class brace — the new function must land OUTSIDE the class (top-level), not inside it:

```kotlin
/**
 * The audio-polling branch's media classification, extracted PURE for JVM tests (the detector
 * itself needs AudioManager + a main-looper Handler — see MediaPollPolicyTest). Polled device
 * audio counts as transcribable media only when something is audible, it is NOT our own
 * read-aloud voice (selfAudioActive — TtsController.isSpeechActive via the service-injected
 * [MediaSessionDetector.selfAudioActive] lambda), and a media episode is not already running
 * (the duplicate-notification guard).
 */
fun isPolledAudioMedia(
    isAudioActive: Boolean,
    selfAudioActive: Boolean,
    alreadyPlaying: Boolean,
): Boolean = isAudioActive && !selfAudioActive && !alreadyPlaying
```

- [ ] **Step 4: Re-run the test class, expect pass.** Same command as Step 2. Expect: `BUILD SUCCESSFUL`, 4 tests passed, 0 failed.

- [ ] **Step 5: Write the failing null-engine contract test.** Create `app/src/test/java/com/whispereverywhere/tts/TtsControllerSpeechActiveTest.kt`:

```kotlin
package com.whispereverywhere.tts

import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * TtsController.isSpeechActive is the ONE "our TTS is audible" authority. Its true-path cannot
 * run on the JVM — TtsEngine's constructor builds a Handler(Looper.getMainLooper()), so no unit
 * test can ever construct the engine — but the null-engine contract must be pinned: a process
 * where no read has ever started reports NOT speaking (false, never a crash). The set/clear
 * behavior delegates to TtsEngine.speaking (set synchronously in speak(), cleared in its
 * executor task's finally on completion AND on error, cleared instantly by stop(), and
 * generation-guarded against a superseded read clearing a newer one) — owner-checked on-device.
 */
class TtsControllerSpeechActiveTest {

    @Test fun no_engine_means_not_speaking() {
        // No JVM test can create the engine (see class kdoc), so the singleton's engine field
        // is necessarily null here regardless of test ordering.
        assertFalse(TtsController.isSpeechActive())
    }
}
```

- [ ] **Step 6: Run it, expect compile failure; then implement.**

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon --tests "com.whispereverywhere.tts.TtsControllerSpeechActiveTest"
```

  Expect: `BUILD FAILED` — `Unresolved reference 'isSpeechActive'`. Then in `TtsController.kt`, insert directly after the `preload` function (`fun preload(context: Context) { engine(context).preload() }`) and before the `speakFromTrigger` kdoc:

```kotlin
    /**
     * The single "our TTS is audible" authority: true while OUR read-aloud is playing (or
     * synthesizing toward playback), whatever the trigger — bubble tap, speaker lobe, or the
     * PROCESS_TEXT toolbar ([SpeakTextActivity]). Media detection consults this so a read is
     * never classified as transcribable media; pre-fix only service-driven reads were covered
     * and a toolbar read summoned the bubble with a transcribe prompt over its own voice.
     *
     * Delegates to the engine's generation-guarded speaking flag, which EVERY entry point
     * funnels through: [speakFromTrigger] → [TtsEngine.speak] sets it synchronously before
     * returning; it clears on completion and on error (the speak task's finally), and instantly
     * on [stop] — so media detection can never be permanently suppressed. A null engine (no
     * read ever started in this process) is simply "not speaking".
     */
    fun isSpeechActive(): Boolean = engine?.isSpeaking() == true
```

- [ ] **Step 7: Re-run the test class, expect pass.** Same command as Step 6. Expect: `BUILD SUCCESSFUL`, 1 test passed, 0 failed.

- [ ] **Step 8: Swap the detector's decision line onto the pure function.** In `MediaSessionDetector.checkAudioPlaybackState`, replace:

```kotlin
            // Only use audio polling if MediaSession hasn't detected anything
            // This prevents duplicate notifications
            if (isAudioActive && !selfAudioActive() && !isMediaPlaying) {
```

  with:

```kotlin
            // Only use audio polling if MediaSession hasn't detected anything
            // This prevents duplicate notifications
            if (isPolledAudioMedia(isAudioActive, selfAudioActive(), isMediaPlaying)) {
```

- [ ] **Step 9: Point the service's self-audio lambda at the controller.** In `FloatingBubbleService.onCreate`, replace:

```kotlin
        // Our read-aloud plays on the music stream; the detector must never count it as media.
        mediaDetector.selfAudioActive = { isSpeakingNow }
```

  with:

```kotlin
        // Our read-aloud plays on the music stream; the detector must never count it as media.
        // TtsController is the ONE authority: it covers every trigger, including
        // SpeakTextActivity's toolbar reads, which the service-local isSpeakingNow never saw —
        // those were classified as transcribable media over our own voice.
        mediaDetector.selfAudioActive = { com.whispereverywhere.tts.TtsController.isSpeechActive() }
```

  (`isSpeakingNow` itself stays — it still drives the pill visuals, `handleBubbleClick`'s pause/resume, `onClipboardChanged`'s gating, and the IDLE icon.)

- [ ] **Step 10: Compile, then run the full JVM suite.**

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:assembleDebug --no-daemon
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon
```

  Expect: `BUILD SUCCESSFUL` twice; no failed tests.

- [ ] **Step 11: Commit.**

```powershell
git add app/src/main/java/com/whispereverywhere/service/MediaSessionDetector.kt app/src/main/java/com/whispereverywhere/tts/TtsController.kt app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt app/src/test/java/com/whispereverywhere/service/MediaPollPolicyTest.kt app/src/test/java/com/whispereverywhere/tts/TtsControllerSpeechActiveTest.kt
git commit -m "fix(tts): TtsController owns 'our voice is audible' — toolbar reads are never media"
```

---

### Task A4: Re-home the TTS preload on clipboard copy + final sweep

**Files:**
- Modify: `app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt` (`onClipboardChanged` — ~:451–481 at HEAD; post-A2/A3 expect it near ~:444; find the function by name)

**Interfaces:**
- Consumes: `TtsController.preload(context: Context)` and `TtsController.isVoiceInstalled(context: Context): Boolean` (both existing, unchanged).
- Produces: nothing — this closes Workstream 1.

- [ ] **Step 1: Add the preload to `onClipboardChanged`.** The preload that used to ride `onTextSelected` (deleted in Task A2 Step 5) warms Kokoro's ~0.8 GB context so the first lobe tap doesn't pay the ~2 s load. A detected copy is the new warm signal — a copy is exactly what makes a speaker-lobe tap likely next (the same event already pulses the lobe). In `onClipboardChanged`, replace:

```kotlin
            if (currentState != BubbleState.IDLE || isSpeakingNow) return@launch
            if (!com.whispereverywhere.tts.TtsController.isVoiceInstalled(this@FloatingBubbleService)) return@launch
```

  with:

```kotlin
            if (currentState != BubbleState.IDLE || isSpeakingNow) return@launch
            if (!com.whispereverywhere.tts.TtsController.isVoiceInstalled(this@FloatingBubbleService)) return@launch
            // Warm the ~2 s voice-model load inside the copy -> lobe-tap think-time (this
            // preload used to ride the deleted selection morph; the copy that pulses the
            // speaker lobe below is the new warm signal). No-op if already loaded.
            com.whispereverywhere.tts.TtsController.preload(this@FloatingBubbleService)
```

  Placement matters: after the voice-installed gate (never preload a voice that isn't there — `preload()` no-ops on a missing dir, but the gate keeps intent obvious) and before the summon/pulse block, so always-on-mode copies (where `summoned` is false) still warm the model.

- [ ] **Step 2: Sweep for resurrection.** Confirm every deleted symbol is gone from production and test code (docs/ still mentions them by design):

```powershell
git grep -nE "speakModeText|onTextSelected|onSelectionCleared|setSelectionListener|scheduleMorphRevert|SELECTION_DEBOUNCE|CLEAR_DEBOUNCE|MORPH_REVERT|OnTextSelectionListener|MAX_SELECTION_CHARS|selectionDebounceJob|morphRevertJob" -- app/src
```

  Expect: no output (git grep exits 1 on zero matches — that is the pass condition). Any hit is a missed deletion site; fix it in the file it names and re-run.

- [ ] **Step 3: Confirm the preserved paths survived.**

```powershell
git grep -n "CARET tracking" -- app/src/main/java/com/whispereverywhere/service/WhisperAccessibilityService.kt
git grep -nE "readClipboardAndSpeak|speakFromTrigger|pulseSpeakerLobe" -- app/src/main/java/com/whispereverywhere
```

  Expect: the first prints exactly one hit (the caret catch-all's comment in `onAccessibilityEvent`); the second prints hits in `FloatingBubbleService.kt` (definition + lobe wiring) and `TtsController.kt`/`SpeakTextActivity.kt` for `speakFromTrigger`. Zero hits on either = a preserved path was damaged; stop and restore it before committing.

- [ ] **Step 4: Compile, then run the full JVM suite.**

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:assembleDebug --no-daemon
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon
```

  Expect: `BUILD SUCCESSFUL` twice; no failed tests.

- [ ] **Step 5: Commit.**

```powershell
git add app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt
git commit -m "feat(tts): a detected copy now warms the voice model, as the dead morph once did"
```

---

**Owner on-device checklist (Workstream 1) — owner-run, after A4 is merged.** Build with `:app:assembleDebug` (above), install ONLY via `adb.exe install -r C:\Users\bastr\.androidbuild\WhisperEverywhere\app\outputs\apk\debug\app-debug.apk` — NEVER `installDebug`/`connectedAndroidTest` (they wipe the on-device models).

1. Focus a browser URL bar (auto-select-all) → bubble stays a mic.
2. Type into a field with inline autocomplete (search box suggesting a suffix) → stays a mic through every keystroke.
3. Long-press-select text anywhere → bubble never morphs; the caret/typing paths still summon the bubble (caret catch-all intact).
4. Copy text → speaker lobe pulses; first lobe tap starts reading with no noticeable model-load stall (preload relocation).
5. Select text → toolbar → "Speak" (SpeakTextActivity) → NO "Tap bubble to transcribe audio" toast, bubble is not summoned to the media spot, and tapping the bubble mid-read pauses the read instead of starting a recording of our own voice.
6. After a read finishes (and after force-stopping one mid-read), play real media (YouTube/Spotify) → media detection still summons the bubble (the audible flag reset correctly).

---

### Task B1: `FinalDeliveryPolicy` — the pure delivery-decision table (TDD)

**Files:**
- Create: `app/src/main/java/com/whispereverywhere/transcription/FinalDeliveryPolicy.kt`
- Test (create): `app/src/test/java/com/whispereverywhere/transcription/FinalDeliveryPolicyTest.kt`

**Interfaces:**
- Consumes: nothing (pure, Android-free).
- Produces (Task B5, B6 and Workstream 3 rely on these EXACT names — do not rename):
  - `enum class InjectTarget { SESSION_BOUND, FINALIZE_FOCUS }` (top-level, package `com.whispereverywhere.transcription`)
  - `data class FinalDeliveryPlan(val inject: InjectTarget?, val copyWholeToClipboard: Boolean)`
  - `object FinalDeliveryPolicy { fun decide(isTextFieldSession: Boolean, degradedToClipboard: Boolean, hasLiveInputTarget: Boolean, transcriptBlank: Boolean): FinalDeliveryPlan }`

- [ ] **Step 1: Write the failing test — the full 16-row table.** Create `app/src/test/java/com/whispereverywhere/transcription/FinalDeliveryPolicyTest.kt` with exactly:

```kotlin
package com.whispereverywhere.transcription

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The W2 final-only-commit decision table. Four booleans in, one plan out; every one of the
 * 16 combinations is pinned here. The semantics rows (spec 2026-08-08, Workstream 2):
 *   transcriptBlank            -> (null, false)                       — nothing to deliver
 *   field && !degraded         -> (SESSION_BOUND, false)              — the one injection
 *   field && degraded          -> (null, true)                        — one consolidated copy
 *   !field                     -> (FINALIZE_FOCUS if live target else null, true)
 */
class FinalDeliveryPolicyTest {

    // ---------------------------------------------- row 1: blank transcript wins over everything

    @Test fun a_blank_transcript_delivers_nothing_no_matter_what() {
        for (field in listOf(true, false))
            for (degraded in listOf(true, false))
                for (live in listOf(true, false)) {
                    assertEquals(
                        "field=$field degraded=$degraded live=$live",
                        FinalDeliveryPlan(inject = null, copyWholeToClipboard = false),
                        FinalDeliveryPolicy.decide(
                            isTextFieldSession = field,
                            degradedToClipboard = degraded,
                            hasLiveInputTarget = live,
                            transcriptBlank = true,
                        ),
                    )
                }
    }

    // ---------------------------------------------- row 2: healthy field session -> session-bound

    @Test fun a_healthy_field_session_injects_into_the_session_bound_target() {
        // hasLiveInputTarget is deliberately IRRELEVANT here: the session-bound write resolves
        // dead nodes itself (resolveInjectionTarget's focused-field fallback) — the policy must
        // not second-guess it, or a dead node would silently demote a field session to clipboard.
        for (live in listOf(true, false)) {
            assertEquals(
                "hasLiveInputTarget=$live",
                FinalDeliveryPlan(inject = InjectTarget.SESSION_BOUND, copyWholeToClipboard = false),
                FinalDeliveryPolicy.decide(
                    isTextFieldSession = true,
                    degradedToClipboard = false,
                    hasLiveInputTarget = live,
                    transcriptBlank = false,
                ),
            )
        }
    }

    // ---------------------------------------------- row 3: degraded field session -> one copy

    @Test fun a_degraded_field_session_gets_one_consolidated_clipboard_copy() {
        for (live in listOf(true, false)) {
            assertEquals(
                "hasLiveInputTarget=$live",
                FinalDeliveryPlan(inject = null, copyWholeToClipboard = true),
                FinalDeliveryPolicy.decide(
                    isTextFieldSession = true,
                    degradedToClipboard = true,
                    hasLiveInputTarget = live,
                    transcriptBlank = false,
                ),
            )
        }
    }

    // ---------------------------------------------- row 4: preview session, live target at stop

    @Test fun a_preview_session_with_a_live_target_copies_and_injects_at_the_focus() {
        // Targeting the finalize-time focus is BY DESIGN for non-field sessions (the
        // capture-video-then-tap-into-prompt flow); degraded is a field-session concept only.
        for (degraded in listOf(true, false)) {
            assertEquals(
                "degraded=$degraded",
                FinalDeliveryPlan(inject = InjectTarget.FINALIZE_FOCUS, copyWholeToClipboard = true),
                FinalDeliveryPolicy.decide(
                    isTextFieldSession = false,
                    degradedToClipboard = degraded,
                    hasLiveInputTarget = true,
                    transcriptBlank = false,
                ),
            )
        }
    }

    // ---------------------------------------------- row 5: preview session, no target -> copy only

    @Test fun a_preview_session_with_no_target_copies_to_clipboard_only() {
        for (degraded in listOf(true, false)) {
            assertEquals(
                "degraded=$degraded",
                FinalDeliveryPlan(inject = null, copyWholeToClipboard = true),
                FinalDeliveryPolicy.decide(
                    isTextFieldSession = false,
                    degradedToClipboard = degraded,
                    hasLiveInputTarget = false,
                    transcriptBlank = false,
                ),
            )
        }
    }
}
```

- [ ] **Step 2: Run the test class, expect FAIL (compilation error).**
  `$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon --tests "com.whispereverywhere.transcription.FinalDeliveryPolicyTest"`
  Expected outcome: `BUILD FAILED` — `:app:compileDebugUnitTestKotlin` fails with `unresolved reference 'FinalDeliveryPolicy'` (and `FinalDeliveryPlan` / `InjectTarget`).

- [ ] **Step 3: Minimal implementation.** Create `app/src/main/java/com/whispereverywhere/transcription/FinalDeliveryPolicy.kt` with exactly:

```kotlin
package com.whispereverywhere.transcription

/** Where the ONE stop-time write goes (W2 final-only commit). */
enum class InjectTarget {
    /** The field captured at beginInjectionSession — a TEXT_FIELD session's target. */
    SESSION_BOUND,

    /** Whatever field is focused AT STOP — the non-field session's opportunistic inject. */
    FINALIZE_FOCUS,
}

/** The whole delivery, decided once: at most one injection, at most one clipboard write. */
data class FinalDeliveryPlan(val inject: InjectTarget?, val copyWholeToClipboard: Boolean)

/**
 * The single decision point for what happens to a finished transcript. Pure and Android-free
 * so the whole table is a JVM test (FinalDeliveryPolicyTest pins all 16 combinations).
 *
 * Mid-session, NOTHING leaves the app (segments only accumulate); this table runs exactly once,
 * at stopRecording (and best-effort at onDestroy), on the full accumulated transcript.
 */
object FinalDeliveryPolicy {
    fun decide(
        isTextFieldSession: Boolean,
        degradedToClipboard: Boolean,
        hasLiveInputTarget: Boolean,
        transcriptBlank: Boolean,
    ): FinalDeliveryPlan = when {
        // Nothing was said: no write of any kind (the "No speech detected" toast covers UX).
        transcriptBlank -> FinalDeliveryPlan(inject = null, copyWholeToClipboard = false)

        // Field session, delivery healthy: the ONE injection, into the session-bound target.
        // Dead-node fallback lives INSIDE the write (resolveInjectionTarget), not here.
        isTextFieldSession && !degradedToClipboard ->
            FinalDeliveryPlan(inject = InjectTarget.SESSION_BOUND, copyWholeToClipboard = false)

        // Field session that degraded to clipboard: one consolidated copy, no injection.
        isTextFieldSession -> FinalDeliveryPlan(inject = null, copyWholeToClipboard = true)

        // Preview session: clipboard once, plus the finalize-time-focus inject when a real
        // target exists at stop (that targeting is BY DESIGN for non-field sessions).
        else -> FinalDeliveryPlan(
            inject = if (hasLiveInputTarget) InjectTarget.FINALIZE_FOCUS else null,
            copyWholeToClipboard = true,
        )
    }
}
```

- [ ] **Step 4: Run the test class, expect PASS.**
  `$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon --tests "com.whispereverywhere.transcription.FinalDeliveryPolicyTest"`
  Expected outcome: `BUILD SUCCESSFUL`, 5 tests passed, 0 failed.

- [ ] **Step 5: Run the full JVM suite.**
  `$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon`
  Expected outcome: `BUILD SUCCESSFUL`, no failures.

- [ ] **Step 6: Commit.**
  `git add app/src/main/java/com/whispereverywhere/transcription/FinalDeliveryPolicy.kt app/src/test/java/com/whispereverywhere/transcription/FinalDeliveryPolicyTest.kt`
  `git commit -m "feat(transcription): FinalDeliveryPolicy — one table decides where a finished transcript goes"`

---

### Task B2: TextJoin accumulation-equivalence + the sink joins under TextJoin (TDD)

**Files:**
- Modify: `app/src/main/java/com/whispereverywhere/transcription/TranscriptSink.kt` — function `append` (lines 26–38)
- Test (modify): `app/src/test/java/com/whispereverywhere/text/TextJoinTest.kt` (append after the last test, line ~149)
- Test (modify): `app/src/test/java/com/whispereverywhere/TranscriptSinkTest.kt` (append after the last test, line ~42)

**Interfaces:**
- Consumes: `TextJoin.normalize/needsSpace/join/assemble` (existing, `com.whispereverywhere.text.TextJoin`).
- Produces: `TranscriptSink.append(segment: String)` now TextJoin-governed — its file content equals `TextJoin.assemble(segments)` with NO trailing space. Task B5 relies on this file being the delivered transcript ("the final string must read identically to what sequential injection produced" — spec Spacing clause).

- [ ] **Step 1: Add the accumulation-equivalence tests to `TextJoinTest`.** These are equivalence PROOFS of existing behavior (expected to pass immediately — they pin that switching from per-segment injection to accumulate-then-deliver cannot change the text). Append inside the class, after `melt_invariant_holds_across_generated_pairs`:

```kotlin
    // --- accumulation equivalence (W2 final-only commit) ------------------------
    // The accumulating window joins segments ONE AT A TIME as they resolve; the final delivery
    // reads the whole thing at once. These pin that the two shapes produce the same string —
    // N segments folded through join() == assemble() of the whole list — so final-only commit
    // delivers character-for-character what per-segment injection used to type.

    private fun incrementalJoin(segments: List<String>): String =
        segments.fold("") { acc, seg ->
            val n = TextJoin.normalize(seg)
            if (n.isEmpty()) acc else TextJoin.join(acc, n)
        }

    @Test fun incremental_join_equals_assemble_at_once() {
        val segments = listOf("Hello world", "this is a test", ".", "Right", "?", "OK then")
        assertEquals(TextJoin.assemble(segments), incrementalJoin(segments))
    }

    @Test fun incremental_join_equals_assemble_with_blanks_and_cjk() {
        val segments = listOf("你好", "世界", "  ", "hello", "", "world", "!")
        assertEquals(TextJoin.assemble(segments), incrementalJoin(segments))
    }

    @Test fun accumulation_equivalence_holds_across_generated_segment_lists() {
        val shapes = listOf("word", "two words", ".", ",", ")", "(", "你好", " padded ", "a")
        for (a in shapes) for (b in shapes) for (c in shapes) {
            val segs = listOf(a, b, c)
            assertEquals("segments=$segs", TextJoin.assemble(segs), incrementalJoin(segs))
        }
    }
```

- [ ] **Step 2: Run TextJoinTest, expect PASS.**
  `$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon --tests "com.whispereverywhere.text.TextJoinTest"`
  Expected outcome: `BUILD SUCCESSFUL`, all tests (existing 23 + new 3 = 26) pass.

- [ ] **Step 3: Write the failing sink tests.** In `app/src/test/java/com/whispereverywhere/TranscriptSinkTest.kt`, add two imports at the top (below `import com.whispereverywhere.transcription.TranscriptSink`):

```kotlin
import com.whispereverywhere.text.TextJoin
import java.util.concurrent.Executors
```

and append inside the class, after `blank_segments_are_ignored`:

```kotlin
    // --- TextJoin-governed joins (W2 final-only commit) -------------------------
    // The sink file IS the transcript the final delivery ships, so its joins must follow the
    // same melt policy sequential injection followed: punctuation attaches, CJK grows no stray
    // space, and there is no trailing separator.

    @Test fun sink_joins_under_the_textjoin_policy_not_blind_spaces() {
        val f = tmp()
        val sink = TranscriptSink(f)
        sink.append("Hello")
        sink.append(".")      // closing punctuation attaches: 'Hello.', never 'Hello . '
        sink.append("你好")
        sink.append("世界")   // CJK boundary: no stray space
        sink.close()
        assertEquals("Hello. 你好世界", f.readText())
    }

    @Test fun sink_file_equals_textjoin_assemble_of_the_segments() {
        val f = tmp()
        val sink = TranscriptSink(f)
        val segs = listOf("Hello world", "this is a test", ".", "  ", "OK then", "?")
        segs.forEach { sink.append(it) }
        sink.close()
        assertEquals(TextJoin.assemble(segs), f.readText())
    }

    @Test fun appends_from_a_background_executor_land_complete_and_joined() {
        // House rule: concurrency-adjacent tests run on a REAL background executor. The bubble
        // appends from engine threads; the file must still equal the assemble of the segments.
        val f = tmp()
        val sink = TranscriptSink(f)
        val exec = Executors.newSingleThreadExecutor()
        try {
            val segs = (1..50).map { "segment$it" }
            segs.forEach { s -> exec.submit { sink.append(s) } }
            exec.submit { }.get() // fence: every queued append has completed
            sink.close()
            assertEquals(TextJoin.assemble(segs), f.readText())
        } finally {
            exec.shutdown()
        }
    }
```

- [ ] **Step 4: Run TranscriptSinkTest, expect 3 FAILURES.**
  `$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon --tests "com.whispereverywhere.TranscriptSinkTest"`
  Expected outcome: `BUILD FAILED` — 6 tests completed, 3 failed (`sink_joins_under_the_textjoin_policy_not_blind_spaces`, `sink_file_equals_textjoin_assemble_of_the_segments`, `appends_from_a_background_executor_land_complete_and_joined`), each a `ComparisonFailure` showing the current unconditional-trailing-space output (e.g. expected `Hello. 你好世界` but was `Hello . 你好 世界 `).

- [ ] **Step 5: Minimal implementation — TextJoin-governed `append`.** In `app/src/main/java/com/whispereverywhere/transcription/TranscriptSink.kt`, replace the whole `append` function (lines 26–38):

```kotlin
    @Synchronized
    fun append(segment: String) {
        val s = TextJoin.normalize(segment)
        if (s.isEmpty()) return
        // TextJoin governs the join (W2 final-only commit): this file IS the transcript the
        // final delivery ships, so 'Hello'+'.' must read 'Hello.' — exactly what sequential
        // per-segment injection used to produce — and a CJK boundary must not grow a stray
        // space. [tail]'s last char is always the last char written to the file (truncation
        // only eats the FRONT), so it is the left side of every boundary decision.
        if (tail.isNotEmpty() && TextJoin.needsSpace(tail, s)) {
            writer.write(" ")
            tail.append(' ')
        }
        writer.write(s)
        writer.flush()
        tail.append(s)
        if (tail.length > previewCapChars) {
            tail.delete(0, tail.length - previewCapChars)
        }
        _preview.value = tail.toString()
    }
```

- [ ] **Step 6: Run TranscriptSinkTest, expect PASS.**
  Same command as Step 4. Expected outcome: `BUILD SUCCESSFUL`, 6 tests passed, 0 failed (the 3 pre-existing tests use `contains`/blank assertions and survive the join change).

- [ ] **Step 7: Run the full JVM suite.**
  `$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon`
  Expected outcome: `BUILD SUCCESSFUL`, no failures.

- [ ] **Step 8: Commit.**
  `git add app/src/main/java/com/whispereverywhere/transcription/TranscriptSink.kt app/src/test/java/com/whispereverywhere/TranscriptSinkTest.kt app/src/test/java/com/whispereverywhere/text/TextJoinTest.kt`
  `git commit -m "feat(transcription): the sink joins under TextJoin — its file IS the transcript now"`

---

### Task B3: `showSessionPreview` — one preview pipeline for every session context

Android-service wiring (untestable by house convention): change → compile → full suite → commit.

**Files:**
- Modify: `app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt` — three regions, all anchored by function name (line numbers are HEAD `ae86024`; Workstream 1's removals shift everything after ~line 520 up by ~55 lines — anchor on the quoted code, not the numbers):
  1. new function inserted directly above `private fun startRecording()` (≈1795)
  2. the three-way preview branch inside `startRecording`'s `onOpen` (≈1866–1906)
  3. the delta gate in `onDelta` (≈1919–1944) and the FINALIZING-status gate in `stopRecording` (≈2003–2006)

**Interfaces:**
- Consumes: `TranscriptSink(sessionFile)` (existing; B2 made its joins TextJoin-governed).
- Produces: `private fun showSessionPreview(live: Boolean)` — Workstream 3's `applyPreviewSize()` call lands at the marked comment on its first line. Task B4/B5 rely on: every session (all contexts) now has `transcriptSink != null` and a visible accumulating window.

- [ ] **Step 1: Add `showSessionPreview`.** Insert immediately above `private fun startRecording() {`:

```kotlin
    /**
     * The ONE preview pipeline (W2 unified preview): EVERY session — TEXT_FIELD, MEDIA_PLAYBACK,
     * NONE, live or batch — shows the accumulating transcript window and gets a bounded-memory
     * TranscriptSink. Mid-session text goes here and NOWHERE else; the single external write
     * happens at stop (deliverFinalTranscript). For a TEXT_FIELD session this window is the
     * user's only live feedback now, since segments no longer inject as they resolve.
     * [live] sessions additionally stream the in-flight utterance onto the delta strip via
     * onDelta; here the strip is only reset so the last session's text never flashes back.
     */
    private fun showSessionPreview(live: Boolean) {
        // W3: applyPreviewSize() call lands here
        android.util.Log.i("WE-DIAG", "showSessionPreview: live=$live context=$sessionContext")
        transcriptionEditText.visibility = View.VISIBLE
        transcriptionEditText.text = ""
        transcriptionDeltaText.text = ""
        transcriptionDeltaText.visibility = View.GONE
        transcriptionPreviewContainer.visibility = View.VISIBLE

        // Bounded-memory sink for the session; the file on disk is the full transcript.
        val sessionFile = java.io.File(filesDir, "transcript_session.txt").apply { if (exists()) delete() }
        val sink = com.whispereverywhere.transcription.TranscriptSink(sessionFile)
        transcriptSink = sink
        previewJob?.cancel()
        previewJob = serviceScope.launch(Dispatchers.Main) {
            sink.preview.collectLatest { text ->
                transcriptionEditText.text = text
                // TextView has no setSelection; scroll to reveal the newest text.
                transcriptionEditText.post {
                    val lc = transcriptionEditText.lineCount
                    val layout = transcriptionEditText.layout
                    if (lc > 0 && layout != null) {
                        val dy = layout.getLineBottom(lc - 1) - transcriptionEditText.height
                        transcriptionEditText.scrollTo(0, dy.coerceAtLeast(0))
                    }
                }
            }
        }
    }
```

- [ ] **Step 2: Replace the three-way branch in `onOpen`.** Inside `startRecording` → `engine.connect(..., object : TranscriptionEngine.Listener { override fun onOpen() { ... } })`, replace this entire block (starts with the comment `// Show preview text bubble if we are not injecting into a text field`, ends at the closing brace before `updateBubbleState(BubbleState.RECORDING)`):

```kotlin
                    // Show preview text bubble if we are not injecting into a text field
                    if (sessionContext != BubbleContext.TEXT_FIELD) {
                        // Restore the full-transcript view a prior live session may have hidden.
                        transcriptionEditText.visibility = View.VISIBLE
                        transcriptionEditText.text = ""
                        transcriptionDeltaText.text = ""
                        transcriptionDeltaText.visibility = View.GONE
                        transcriptionPreviewContainer.visibility = View.VISIBLE

                        // Create a bounded-memory sink for this session (Task 7)
                        val sessionFile = java.io.File(filesDir, "transcript_session.txt").apply { if (exists()) delete() }
                        val sink = com.whispereverywhere.transcription.TranscriptSink(sessionFile)
                        transcriptSink = sink
                        previewJob?.cancel()
                        previewJob = serviceScope.launch(Dispatchers.Main) {
                            sink.preview.collectLatest { text ->
                                transcriptionEditText.text = text
                                // TextView has no setSelection; scroll to reveal the newest text.
                                transcriptionEditText.post {
                                    val lc = transcriptionEditText.lineCount
                                    val layout = transcriptionEditText.layout
                                    if (lc > 0 && layout != null) {
                                        val dy = layout.getLineBottom(lc - 1) - transcriptionEditText.height
                                        transcriptionEditText.scrollTo(0, dy.coerceAtLeast(0))
                                    }
                                }
                            }
                        }
                    } else if (sessionIsLive) {
                        // A live TEXT_FIELD session injects each finished turn into the real field,
                        // but the field has no affordance for the word-for-word partials — so lift
                        // the preview STRIP (the delta line only) for live mode. The full-transcript
                        // editText stays hidden: deltas render on this strip and NEVER inject.
                        // Completions still flow through the unchanged orderer -> full-field RMW path.
                        transcriptionEditText.visibility = View.GONE
                        transcriptionDeltaText.text = ""
                        transcriptionDeltaText.visibility = View.GONE
                        transcriptionPreviewContainer.visibility = View.VISIBLE
                    } else {
                        transcriptionPreviewContainer.visibility = View.GONE
                    }
```

with:

```kotlin
                    // One preview pipeline for every session context (W2): the accumulating
                    // window + sink, always. Live sessions additionally stream onto the strip.
                    showSessionPreview(live = sessionIsLive)
```

- [ ] **Step 3: Drop `onDelta`'s now-vacuous context gate.** In the same listener, replace the whole `onDelta` override (its outer `if (sessionContext != BubbleContext.TEXT_FIELD || sessionIsLive)` became always-true: only the live engine emits deltas, and the container is now up for every context):

```kotlin
            override fun onDelta(text: String) {
                // On-device and batch engines emit no intra-segment deltas; only the live engine
                // does. The strip already renders for NONE/MEDIA_PLAYBACK preview sessions; the
                // `|| sessionIsLive` clause lifts it into a live TEXT_FIELD session too, where the
                // onOpen branch above made the container (delta line only) visible for exactly this.
                if (sessionContext != BubbleContext.TEXT_FIELD || sessionIsLive) {
                    serviceScope.launch(Dispatchers.Main) {
                        if (text.isNotBlank()) {
                            transcriptionDeltaText.visibility = View.VISIBLE
                            transcriptionDeltaText.text = text
                            // Keep the newest words in view. The panel grows to maxLines then
                            // scrolls; without this it would hold the TOP of a long utterance and
                            // the live words would stream out of sight — the opposite of the point.
                            // Posted so the scroll runs after layout has measured the new text.
                            transcriptionDeltaText.post {
                                val overflow = transcriptionDeltaText.layout?.let { l ->
                                    l.getLineBottom(l.lineCount - 1) - transcriptionDeltaText.height
                                } ?: 0
                                transcriptionDeltaText.scrollTo(0, overflow.coerceAtLeast(0))
                            }
                        } else {
                            transcriptionDeltaText.visibility = View.GONE
                        }
                    }
                }
            }
```

with:

```kotlin
            override fun onDelta(text: String) {
                // Only the live engine emits intra-segment deltas. The unified preview (W2)
                // keeps the container up for EVERY session context, so the strip renders
                // wherever deltas exist — no context gate. Resolved turns accumulate into the
                // window below it.
                serviceScope.launch(Dispatchers.Main) {
                    if (text.isNotBlank()) {
                        transcriptionDeltaText.visibility = View.VISIBLE
                        transcriptionDeltaText.text = text
                        // Keep the newest words in view. The panel grows to maxLines then
                        // scrolls; without this it would hold the TOP of a long utterance and
                        // the live words would stream out of sight — the opposite of the point.
                        // Posted so the scroll runs after layout has measured the new text.
                        transcriptionDeltaText.post {
                            val overflow = transcriptionDeltaText.layout?.let { l ->
                                l.getLineBottom(l.lineCount - 1) - transcriptionDeltaText.height
                            } ?: 0
                            transcriptionDeltaText.scrollTo(0, overflow.coerceAtLeast(0))
                        }
                    } else {
                        transcriptionDeltaText.visibility = View.GONE
                    }
                }
            }
```

- [ ] **Step 4: Un-gate the FINALIZING status line.** In `stopRecording`, directly under `updateBubbleState(BubbleState.FINALIZING)` and its preceding comment block, replace:

```kotlin
        if (sessionContext != BubbleContext.TEXT_FIELD) {
            transcriptionDeltaText.text = "Finishing transcript — last segments coming in…"
            transcriptionDeltaText.visibility = View.VISIBLE
        }
```

with:

```kotlin
        // Every session shows the closing status now (W2 unified preview) — the accumulating
        // window is up for TEXT_FIELD sessions too, and this line is its "still working" signal.
        transcriptionDeltaText.text = "Finishing transcript — last segments coming in…"
        transcriptionDeltaText.visibility = View.VISIBLE
```

- [ ] **Step 5: Compile.**
  `$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:assembleDebug --no-daemon`
  Expected outcome: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Run the full JVM suite.**
  `$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon`
  Expected outcome: `BUILD SUCCESSFUL`, no failures.

- [ ] **Step 7: Commit.**
  `git add app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt`
  `git commit -m "feat(bubble): one preview pipeline — every session gets the accumulating window"`

Note for the engineer: after this commit (and until B5 lands), a TEXT_FIELD session shows the window but still injects per segment while the window stays empty — an intended intermediate state on the feature branch, completed by B4+B5.

---

### Task B4: Accumulate-only mid-session — no external write until stop

Android-service wiring: change → compile → full suite → commit.

**Files:**
- Modify: `app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt` — functions `deliverReleasedText` (≈2189–2212 at `ae86024`) and `handleTranscriptionResult` (≈2214–2259); anchor by name.

**Interfaces:**
- Consumes: `transcriptSink` created for every session by B3's `showSessionPreview`.
- Produces: `handleTranscriptionResult(text)` = pure accumulation (`sessionTranscript` + sink), never injects, never touches the clipboard. B5's final delivery is the only external writer left.

- [ ] **Step 1: Replace `deliverReleasedText` wholesale.** Keep its doc comment but fix the last line — it still promises injection: change `(preview sink + history + injection)` to `(preview sink + history)`. Then replace the body (only the context branching dies):

Replace:

```kotlin
    private fun deliverReleasedText(text: String) {
        if (text.isBlank()) return
        sessionProducedText = true
        if (sessionContext != BubbleContext.TEXT_FIELD) {
            // During FINALIZING the delta line carries the "finishing…" status — keep it up
            // between drain segments instead of blanking it.
            if (currentState != BubbleState.FINALIZING) {
                transcriptionDeltaText.visibility = View.GONE
            }
            // Route through the bounded-memory sink; the preview StateFlow drives
            // transcriptionEditText via collectLatest in onOpen (Task 7).
            transcriptSink?.append(text)
        } else if (sessionIsLive && currentState != BubbleState.FINALIZING) {
            // Live TEXT_FIELD: this turn has just been injected into the real field, so the panel's
            // job for it is done — clear it or the finished words linger UNDER the next utterance
            // as it streams in, reading as duplicated text. (Held during FINALIZING, where the
            // panel carries the closing status instead.) Scroll reset too, so the next turn starts
            // at the top of the panel rather than wherever the last one left it parked.
            transcriptionDeltaText.text = ""
            transcriptionDeltaText.scrollTo(0, 0)
            transcriptionDeltaText.visibility = View.GONE
        }
        handleTranscriptionResult(text)
    }
```

with:

```kotlin
    private fun deliverReleasedText(text: String) {
        if (text.isBlank()) return
        sessionProducedText = true
        // The strip carried this utterance while it was in flight (live deltas); its resolved
        // text moves into the accumulating window via the sink below, so reset the strip for
        // the next one — or the finished words linger UNDER the next utterance as it streams
        // in, reading as duplicated text. Held during FINALIZING, where the strip carries the
        // "finishing transcript" status instead. Scroll reset too, so the next turn starts at
        // the top of the panel rather than wherever the last one left it parked.
        if (currentState != BubbleState.FINALIZING) {
            transcriptionDeltaText.text = ""
            transcriptionDeltaText.scrollTo(0, 0)
            transcriptionDeltaText.visibility = View.GONE
        }
        handleTranscriptionResult(text)
    }
```

- [ ] **Step 2: Replace `handleTranscriptionResult` wholesale** (doc comment included — the old one says "based on current context", which stops being true):

Replace the entire function (from its `/** ... */` doc comment through the closing brace of the `when (sessionContext)` block and the function):

```kotlin
    /**
     * Mid-session accumulation — and ONLY accumulation (W2 final-only commit). Every resolved
     * segment lands in exactly two places, for EVERY session context: the bounded-memory sink
     * (whose file is the transcript the final delivery reads) and sessionTranscript (history's
     * source, persisted at finalize). NOTHING leaves the app until stopRecording's
     * deliverFinalTranscript — no injection, no clipboard write, no caret pinning. FINALIZING
     * counts as in-session: drain-released segments and orderer flushes land here too.
     */
    private fun handleTranscriptionResult(text: String) {
        android.util.Log.i("WE-DIAG", "handleResult: session=$sessionContext live=$currentContext len=${text.length}")
        val historyTok = TextJoin.normalize(text)
        if (historyTok.isEmpty()) return
        if (sessionTranscript.isNotEmpty() && TextJoin.needsSpace(sessionTranscript, historyTok)) {
            sessionTranscript.append(' ')
        }
        sessionTranscript.append(historyTok)
        transcriptSink?.append(text)
    }
```

(This deletes the `when (sessionContext)` block entirely: the `injectTextWithResult` call, the per-segment `InjectionResult` handling, the two `sessionClipboardFallback = true` writers, and the "Can't type here — full transcript will be copied when you stop" toasts. The `sessionClipboardFallback` field STAYS — B5 gives it its new writer. The sink append moves here from `deliverReleasedText` so flush-released text still reaches the file through the one funnel.)

- [ ] **Step 3: Compile.**
  `$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:assembleDebug --no-daemon`
  Expected outcome: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Run the full JVM suite.**
  `$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon`
  Expected outcome: `BUILD SUCCESSFUL`, no failures.

- [ ] **Step 5: Commit.**
  `git add app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt`
  `git commit -m "feat(bubble): mid-session writes are gone — segments accumulate, nothing injects"`

---

### Task B5: The single delivery at stop — inject while the session binding is alive

Android-service wiring: change → compile → full suite → commit.

**Files:**
- Modify: `app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt` — four regions, anchor by name: field block near `sessionClipboardFallback` (≈319–321 at `ae86024`), `startRecording`'s session-reset lines (≈1828), the finalize coroutine inside `stopRecording` (≈2032–2142), and a new function `deliverFinalTranscript` inserted directly after `handleTranscriptionResult`.

**Interfaces:**
- Consumes: `FinalDeliveryPolicy.decide(...)`, `InjectTarget`, `FinalDeliveryPlan` (Task B1); TextJoin-governed sink file (Task B2); per-session sink for all contexts (Task B3); accumulate-only mid-session (Task B4); existing `WhisperAccessibilityService.injectTextWithResult(text): InjectionResult`, `hasLiveInputTarget()`, `endInjectionSession()`.
- Produces: `private fun deliverFinalTranscript(full: String)` — idempotent per session via `finalDelivered`; Task B6 calls it from `onDestroy`.

- [ ] **Step 1: Add the once-per-session guard field and retire the old comment on `sessionClipboardFallback`.** Replace:

```kotlin
    // TEXT_FIELD session whose delivery degraded to clipboard (document apps, dead targets):
    // per-segment toasts are suppressed and the FULL transcript is copied once at finalize.
    @Volatile private var sessionClipboardFallback = false
```

with:

```kotlin
    // TEXT_FIELD session whose FINAL write degraded to clipboard (document apps, dead targets):
    // set by deliverFinalTranscript when the one stop-time write can't type. Records the
    // OUTCOME (for the degraded toast wording); the decide() degraded row it could feed is
    // future-proofing only — finalDelivered makes a second decide() impossible in production.
    @Volatile private var sessionClipboardFallback = false

    // The final write fires EXACTLY once per session — stopRecording's finalize normally, or
    // onDestroy's best-effort if the service dies mid-session. This flag closes the race
    // between those two paths (destroy can cancel the finalize coroutine at any point).
    @Volatile private var finalDelivered = false
```

- [ ] **Step 2: Reset the guard at session start.** In `startRecording`, replace:

```kotlin
        sessionClipboardFallback = false
        WhisperAccessibilityService.beginInjectionSession()
```

with:

```kotlin
        sessionClipboardFallback = false
        finalDelivered = false
        WhisperAccessibilityService.beginInjectionSession()
```

- [ ] **Step 3: Add `deliverFinalTranscript`.** Insert directly after the closing brace of `handleTranscriptionResult`:

```kotlin
    /**
     * The ONE external write of the session (W2 final-only commit). Called from stopRecording's
     * finalize block BEFORE teardownRealtime — teardown ends the injection-session binding, and
     * the SESSION_BOUND write must resolve the field captured at beginInjectionSession — or
     * best-effort from onDestroy. [finalDelivered] makes once-only hold even if destroy races
     * the finalize coroutine. Main thread.
     */
    private fun deliverFinalTranscript(full: String) {
        if (finalDelivered) return
        finalDelivered = true
        val plan = com.whispereverywhere.transcription.FinalDeliveryPolicy.decide(
            isTextFieldSession = sessionContext == BubbleContext.TEXT_FIELD,
            degradedToClipboard = sessionClipboardFallback,
            hasLiveInputTarget = WhisperAccessibilityService.hasLiveInputTarget(),
            transcriptBlank = full.isEmpty(),
        )
        android.util.Log.i(
            "WE-DIAG",
            "finalDelivery: inject=${plan.inject} copy=${plan.copyWholeToClipboard} len=${full.length}",
        )
        when (plan.inject) {
            com.whispereverywhere.transcription.InjectTarget.SESSION_BOUND -> {
                // Field session: one write through the session-bound target. Document/social
                // apps run their paste strategy exactly once; a dead node falls back to the
                // focused field INSIDE injectTextWithResult. Result handling mirrors the old
                // per-segment handler, adapted to stop-time copy.
                when (WhisperAccessibilityService.injectTextWithResult(full)) {
                    WhisperAccessibilityService.InjectionResult.SUCCESS -> {
                        // Typed where the user aimed it — no toast needed.
                    }
                    WhisperAccessibilityService.InjectionResult.CLIPBOARD_ONLY -> {
                        // The strategy already left the FULL transcript on the clipboard.
                        sessionClipboardFallback = true
                        showToast("Can't type here — full transcript copied to clipboard")
                    }
                    WhisperAccessibilityService.InjectionResult.FAILED -> {
                        sessionClipboardFallback = true
                        runCatching {
                            val clip = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            clip.setPrimaryClip(android.content.ClipData.newPlainText("Transcript", full))
                        }
                        showToast("Can't type here — full transcript copied to clipboard")
                    }
                }
            }
            com.whispereverywhere.transcription.InjectTarget.FINALIZE_FOCUS -> {
                // Preview session that ends with a live field focused: clipboard once, plus the
                // opportunistic finalize-time inject (targeting the CURRENT focus is BY DESIGN
                // here — covers capture-video-then-tap-into-prompt end to end; clipboard stays
                // set either way). Delivery now runs BEFORE teardown, so the record-start
                // session binding is still alive and would win inside resolveInjectionTarget —
                // end it first (idempotent; teardown's later call no-ops) so this write
                // resolves the field focused NOW, not the one focused when recording began.
                WhisperAccessibilityService.endInjectionSession()
                val clip = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clip.setPrimaryClip(android.content.ClipData.newPlainText("Transcript", full))
                val injected = WhisperAccessibilityService.injectTextWithResult(full) ==
                    WhisperAccessibilityService.InjectionResult.SUCCESS
                showToast(
                    if (injected) "Transcription inserted — also on your clipboard"
                    else "Transcription copied to clipboard",
                )
            }
            null -> if (plan.copyWholeToClipboard) {
                // Target-less preview session: ONE consolidated copy — genuinely the only
                // clipboard write of the session now. (The TEXT_FIELD wording below is
                // future-proofing: the degraded decide() row can't fire before delivery today,
                // because sessionClipboardFallback is only ever set BY deliverFinalTranscript.)
                val clip = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clip.setPrimaryClip(android.content.ClipData.newPlainText("Transcript", full))
                showToast(
                    if (sessionContext == BubbleContext.TEXT_FIELD) "Full transcription copied to clipboard"
                    else "Transcription copied to clipboard",
                )
            }
        }
    }
```

- [ ] **Step 4: Reorder the finalize coroutine — deliver BEFORE teardown.** In `stopRecording`'s `serviceScope.launch(Dispatchers.Main)` block, replace:

```kotlin
            // Release anything the orderer is still holding, BEFORE teardown closes the sink —
            // held text's only exit is flush(), and the pile is largest exactly here, at the end
            // of a session. (Provably empty for the on-device engine, which resolves in order.)
            deliverReleasedText(segmentOrderer.flush().text)
            // Capture the sink before teardown nulls it, so the full transcript can still be read.
            val finalizingSink = transcriptSink
            teardownRealtime()
            android.util.Log.i("WE-DIAG", "finalize: state=$currentState producedText=$sessionProducedText")
```

with:

```kotlin
            // Release anything the orderer is still holding, BEFORE the final delivery reads
            // the sink — held text's only exit is flush(), and the pile is largest exactly
            // here, at the end of a session. (Provably empty for the on-device engine, which
            // resolves in order.)
            deliverReleasedText(segmentOrderer.flush().text)

            // ---- W2 single delivery: the ONE external write of the session. Runs BEFORE
            // teardownRealtime, because teardown ends the injection-session binding captured
            // at beginInjectionSession and the SESSION_BOUND write must resolve against it.
            // The sink is closed first (full flush; teardown's later close is a swallowed
            // no-op), then its file is read back as the one transcript source every session
            // kind shares. Preview hide / sink close / endInjectionSession all FOLLOW delivery.
            transcriptSink?.close()
            val fullTranscript = transcriptSink?.let { sink ->
                withContext(Dispatchers.IO) { sink.fullTextFile().readText().trim() }
            } ?: ""
            if (currentState == BubbleState.FINALIZING) {
                deliverFinalTranscript(fullTranscript)
            }
            teardownRealtime()
            android.util.Log.i("WE-DIAG", "finalize: state=$currentState producedText=$sessionProducedText")
```

- [ ] **Step 5: Delete the old end-of-session delivery block.** Later in the same coroutine (after the history-persist `if (sessionTranscript.isNotBlank()) { ... }` block), replace:

```kotlin
            if (currentState == BubbleState.FINALIZING) {
                // If we were transcribing without injecting, read the full text from the sink's
                // session file (bounded memory; Task 7) and copy it to the clipboard.
                if (sessionContext != BubbleContext.TEXT_FIELD) {
                    previewJob?.cancel(); previewJob = null
                    finalizingSink?.let { sink ->
                        // teardownRealtime already closed the sink; just read its flushed file.
                        val full = withContext(Dispatchers.IO) { sink.fullTextFile().readText().trim() }
                        if (full.isNotEmpty()) {
                            val clip = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            clip.setPrimaryClip(android.content.ClipData.newPlainText("Transcript", full))
                            // User decision 2026-07-18: a preview session whose user has moved
                            // into a text field by the END delivers there too — the whole
                            // transcript in one block (clipboard stays set either way). Covers
                            // the capture-video-then-tap-into-prompt flow end to end.
                            val injected = WhisperAccessibilityService.hasLiveInputTarget() &&
                                WhisperAccessibilityService.injectTextWithResult(full) ==
                                WhisperAccessibilityService.InjectionResult.SUCCESS
                            showToast(
                                if (injected) "Transcription inserted — also on your clipboard"
                                else "Transcription copied to clipboard",
                            )
                        }
                    }
                } else if (sessionClipboardFallback && sessionTranscript.isNotBlank()) {
                    // Field session that degraded to clipboard delivery: replace the per-segment
                    // scraps with ONE copy of the whole transcript (user decision 2026-07-18).
                    val clip = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clip.setPrimaryClip(
                        android.content.ClipData.newPlainText("Transcript", sessionTranscript.toString()),
                    )
                    showToast("Full transcription copied to clipboard")
                }

                if (!sessionProducedText) {
                    showToast("No speech detected — try again a bit louder or closer to the mic.")
                }
                vibrateSuccess()
                updateBubbleState(BubbleState.IDLE)
            }
```

with:

```kotlin
            if (currentState == BubbleState.FINALIZING) {
                // Delivery already happened above, pre-teardown, through FinalDeliveryPolicy.
                if (!sessionProducedText) {
                    showToast("No speech detected — try again a bit louder or closer to the mic.")
                }
                vibrateSuccess()
                updateBubbleState(BubbleState.IDLE)
            }
```

- [ ] **Step 6: Compile.**
  `$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:assembleDebug --no-daemon`
  Expected outcome: `BUILD SUCCESSFUL` (in particular, no remaining reference to the deleted `finalizingSink`).

- [ ] **Step 7: Run the full JVM suite.**
  `$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon`
  Expected outcome: `BUILD SUCCESSFUL`, no failures.

- [ ] **Step 8: Commit.**
  `git add app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt`
  `git commit -m "feat(bubble): the single delivery at stop, while the session binding is still alive"`

---

### Task B6: onDestroy best-effort — a killed service still delivers what it accumulated

Android-service wiring: change → compile → full suite → commit.

**Files:**
- Modify: `app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt` — function `onDestroy` (≈685–739 at `ae86024`; W1 removes the `setSelectionListener(null)` line inside it — anchor on the `MediaProjectionGate.clear()` / `teardownRealtime()` pair, which W1 does not touch), and the engine `Listener.onError` fatal branch (≈1954–1965 at `ae86024`; anchor on the quoted block in Step 2).

**Interfaces:**
- Consumes: `deliverFinalTranscript(full: String)` and its `finalDelivered` guard (Task B5); `deliverReleasedText` funnel (Task B4); `TranscriptSink.close()/fullTextFile()`.
- Produces: nothing new.

- [ ] **Step 1: Insert the best-effort delivery block.** In `onDestroy`, replace:

```kotlin
        com.whispereverywhere.audio.MediaProjectionGate.listener = null
        com.whispereverywhere.audio.MediaProjectionGate.clear()
        teardownRealtime()
```

with:

```kotlin
        com.whispereverywhere.audio.MediaProjectionGate.listener = null
        com.whispereverywhere.audio.MediaProjectionGate.clear()
        // W2 best-effort: a service destroyed mid-session (system kill, mode toggle) still
        // delivers what it accumulated, through the SAME single-delivery policy as a normal
        // stop — and it must run BEFORE teardownRealtime, which ends the injection-session
        // binding the SESSION_BOUND write needs. Deliberately synchronous (serviceScope is
        // already cancelled, so there is nothing to post to; toasts inside no-op harmlessly)
        // and failure-swallowed: destroy must never block or throw. finalDelivered (set by a
        // finalize that already delivered, then IDLE) keeps the write once-only.
        if (currentState == BubbleState.RECORDING || currentState == BubbleState.FINALIZING) {
            runCatching {
                deliverReleasedText(segmentOrderer.flush().text)
                transcriptSink?.close()
                val full = transcriptSink?.fullTextFile()?.readText()?.trim() ?: ""
                deliverFinalTranscript(full)
            }
        }
        teardownRealtime()
```

- [ ] **Step 2: A fatal engine error during FINALIZING delivers too.** Pre-change, per-segment injection meant most text had already landed by the time a fatal error hit; post-B4 nothing lands until delivery runs — so the fatal `onError` path (which tears down and nulls the sink) would lose the whole transcript. In the engine listener's `onError` (same file, ≈:1954–1965 at `ae86024`), replace:

```kotlin
                // connect-time / fatal (e.g. no model installed)
                serviceScope.launch(Dispatchers.Main) {
                    updateBubbleState(BubbleState.ERROR)
                    teardownRealtime()
                }
```

with:

```kotlin
                // connect-time / fatal (e.g. no model installed)
                serviceScope.launch(Dispatchers.Main) {
                    updateBubbleState(BubbleState.ERROR)
                    // W2: deliver best-effort BEFORE teardown nulls the sink and ends the
                    // session binding — a fatal error mid-FINALIZING must not eat the
                    // transcript. Connect-time fatals have an empty/absent sink, so the
                    // isNotEmpty gate keeps them delivery-free. finalDelivered keeps this
                    // once-only against the finalize coroutine, which skips its own delivery
                    // when it wakes in ERROR state (its FINALIZING guard fails).
                    runCatching {
                        deliverReleasedText(segmentOrderer.flush().text)
                        transcriptSink?.close()
                        val full = transcriptSink?.fullTextFile()?.readText()?.trim() ?: ""
                        if (full.isNotEmpty()) deliverFinalTranscript(full)
                    }
                    teardownRealtime()
                }
```

- [ ] **Step 3: Compile.**
  `$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:assembleDebug --no-daemon`
  Expected outcome: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Run the full JVM suite.**
  `$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon`
  Expected outcome: `BUILD SUCCESSFUL`, no failures.

- [ ] **Step 5: Commit.**
  `git add app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt`
  `git commit -m "fix(bubble): a destroyed service or fatal finalize error still delivers what it accumulated"`

---

### Task B7: Dead-code deletion sweep — fewer ways to write means one write stays one

Android-service wiring: verify-by-grep → delete → compile → full suite → commit. The OTHER paste strategies (`injectForSocialMediaWithResult`, `injectViaClipboard`, `tryPasteOnAnyNode`, `pasteAtEnd`, `pinSelectionToEnd`, the document-app branch inside `injectTextWithResultInternal`) STAY — the final write needs them.

**Files:**
- Modify: `app/src/main/java/com/whispereverywhere/service/WhisperAccessibilityService.kt` — delete `injectViaClipboardPreservingContent` (≈875–934 at `ae86024`), `injectViaClipboardForDocumentApp` (≈1032–1086), companion `injectText` (≈1431–1433), `injectTextToFocusedField` (≈814–820, incl. its doc comment); anchor by name.
- Modify: `app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt` — delete `copyToClipboard` (≈2261–2269) and the two imports it alone uses (lines 7–8).

**Interfaces:**
- Consumes: nothing.
- Produces: nothing — pure removal; all remaining callers verified untouched.

- [ ] **Step 1: Verify zero callers of `injectViaClipboardPreservingContent`.** (Every grep in this task is scoped `-- app/src` — the tracked docs/ spec and plan files mention these symbols by design and must not trip the checks.)
  `git grep -n "injectViaClipboardPreservingContent" -- app/src`
  Expected outcome: exactly 1 hit — the `private fun` definition in `WhisperAccessibilityService.kt`. (If more hits appear inside `app/src`, STOP — do not delete; report.)

- [ ] **Step 2: Delete `injectViaClipboardPreservingContent`** — the entire function from `private fun injectViaClipboardPreservingContent(text: String): Boolean {` through its closing `}` (ends with the `// Paste failed but text is in clipboard` / `return true` lines). NOTE: nothing sits directly above the function itself — the line above it is `pasteAtEnd`'s closing brace. Separately, delete the ONE stray orphaned kdoc at ≈:822–825 (`/** Inject text for social media apps, preserving existing @mentions. ... */`) — it sits ~50 lines earlier, stacked directly above `pinSelectionToEnd`'s own kdoc (≈:826–839), and that second kdoc STAYS: it documents the live `pinSelectionToEnd`. Re-run the grep from Step 1 (scoped `-- app/src`); expected outcome: 0 hits.

- [ ] **Step 3: Verify zero callers of `injectViaClipboardForDocumentApp`, then delete it.**
  `git grep -n "injectViaClipboardForDocumentApp" -- app/src`
  Expected outcome: exactly 1 hit (the definition). Delete the entire function including its doc comment (`/** Special injection for document apps - uses clipboard and gesture-based paste */`). Re-run grep (scoped); expected outcome: 0 hits.

- [ ] **Step 4: Verify and delete companion `injectText`.**
  `git grep -n "injectText(" -- app/src`
  Expected outcome: exactly 1 hit — the companion `fun injectText(text: String): Boolean` definition (note: `injectTextWithResult(` and `injectTextToFocusedField(` do NOT match this pattern). Delete:

```kotlin
        fun injectText(text: String): Boolean {
            return instance?.injectTextToFocusedField(text) ?: false
        }
```

- [ ] **Step 5: Verify `injectTextToFocusedField` is now unreferenced, then delete it.**
  `git grep -n "injectTextToFocusedField" -- app/src`
  Expected outcome: exactly 1 hit — the definition (its only caller died in Step 4). Delete the function and its doc comment:

```kotlin
    /**
     * Inject text into the currently focused text field. Delegates to the unified result path so
     * there is exactly ONE SET_TEXT implementation (the anchor path); maps the result to Boolean.
     */
    fun injectTextToFocusedField(text: String): Boolean {
        return injectTextWithResultInternal(text) != InjectionResult.FAILED
    }
```

  Re-run grep (scoped); expected outcome: 0 hits.

- [ ] **Step 6: Verify and delete `FloatingBubbleService.copyToClipboard`.**
  `git grep -n "copyToClipboard" -- app/src`
  Expected outcome: exactly 1 hit — the definition in `FloatingBubbleService.kt` (the pattern does not substring-match B5's `copyWholeToClipboard` — "Whole" separates the words). Delete:

```kotlin
    private fun copyToClipboard(text: String) {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Whisper Transcription", text)
            clipboard.setPrimaryClip(clip)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
```

  Then delete its now-orphaned imports at the top of `FloatingBubbleService.kt` (every other clipboard use in the file is `android.content.`-qualified — verify with `git grep -n "ClipboardManager" -- app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt`, expecting only `android.content.ClipboardManager`-qualified hits afterwards):

```kotlin
import android.content.ClipData
import android.content.ClipboardManager
```

- [ ] **Step 7: Compile.**
  `$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:assembleDebug --no-daemon`
  Expected outcome: `BUILD SUCCESSFUL`.

- [ ] **Step 8: Run the full JVM suite.**
  `$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon`
  Expected outcome: `BUILD SUCCESSFUL`, no failures.

- [ ] **Step 9: Commit.**
  `git add app/src/main/java/com/whispereverywhere/service/WhisperAccessibilityService.kt app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt`
  `git commit -m "chore(inject): dead per-segment delivery paths deleted — one write, fewer ways to write"`

---

#### Owner on-device checklist — Workstream 2 (after B7; owner runs these)

Install (NEVER `installDebug`/`connectedAndroidTest` — they wipe the on-device models):
`$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:assembleDebug --no-daemon` then `adb.exe install -r C:\Users\bastr\.androidbuild\WhisperEverywhere\app\outputs\apk\debug\app-debug.apk`

- [ ] WhatsApp (SET_TEXT path): dictate 3+ pauses — NOTHING appears in the field mid-session; the bubble's window accumulates; on stop the full text lands in the field in one write, spacing identical to reading the window.
- [ ] Google Docs (document paste path): same session shape — exactly one paste at stop; check the clipboard is touched at most ONCE the whole session.
- [ ] A social app (Facebook comment with an @mention): mention survives; one paste at stop.
- [ ] URL bar / search box: window accumulates, one delivery at stop.
- [ ] Non-field session (home screen): window accumulates; stop → "Transcription copied to clipboard"; tap into a field BEFORE stop completes → "Transcription inserted — also on your clipboard".
- [ ] Both engines: on-device mode AND cloud live mode (delta strip streams above the accumulating window; resolved turns move down into it, no duplicated text).
- [ ] Mid-session mic↔device-audio switch: one transcript, one delivery.
- [ ] Kill the service mid-dictation (Settings toggle): accumulated text still delivered (field or clipboard).
- [ ] "Finishing transcript — last segments coming in…" shows during FINALIZING in a TEXT_FIELD session too.

---

### Task C1: ResizeMath — the pure resize/clamp/y-compensation table (TDD)

**Files:**
- Test: `app/src/test/java/com/whispereverywhere/service/ResizeMathTest.kt` (create)
- Create: `app/src/main/java/com/whispereverywhere/service/ResizeMath.kt`

**Interfaces:**
- Consumes: nothing (pure, dependency-free Kotlin object).
- Produces (later tasks call every one of these, signatures are load-bearing):
  - `object ResizeMath` in package `com.whispereverywhere.service`
  - `const val MIN_WIDTH_DP = 200f`, `MAX_WIDTH_DP_CAP = 560f`, `MIN_HEIGHT_DP = 80f`, `DEFAULT_WIDTH_DP = 280f`, `DEFAULT_HEIGHT_DP = 120f`
  - `data class Result(val widthDp: Float, val heightDp: Float, val windowDyPx: Int)`
  - `fun maxWidthDp(screenWidthPx: Int, density: Float): Float`
  - `fun maxHeightDp(screenHeightPx: Int, density: Float): Float`
  - `fun resize(startWidthDp: Float, startHeightDp: Float, dragDxPx: Float, dragDyPx: Float, density: Float, screenWidthPx: Int, screenHeightPx: Int): Result`

Semantics being pinned: the handle sits at the preview's TOP-RIGHT and the overlay window is TOP-LEFT anchored (`Gravity.TOP or Gravity.START`, window grows downward). Width follows the finger horizontally. Height grows when dragging UP (negative dy). `windowDyPx = -round((newHeightDp - startHeightDp) * density)` so `params.y` moves up exactly as much as the window grows — the top edge follows the finger and the mic pill below never moves.

**Recorded deviation from the spec's testing row (accepted):** the spec asks ResizeMath's JVM tests to also cover "the default-position 0.9 off-screen scenario resolving on-screen with real dims" and "rotation re-clamp". Position clamping deliberately stays in the service's existing `clampToBounds(x, y, viewW, viewH)` — already the single parameterized primitive — rather than being duplicated into ResizeMath; those two scenarios are exercised by C4's wiring (real dims at every clamp site) and pinned by C5's owner checklist items (default-position fully on-screen; rotation mid-session). ResizeMath owns size + y-compensation only; the "persist values" of the spec are exactly its returned clamped dp sizes.

- [ ] **Step 1: Write the failing test file.** Create `app/src/test/java/com/whispereverywhere/service/ResizeMathTest.kt` with exactly:

```kotlin
package com.whispereverywhere.service

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The pure geometry behind the transcript-window resize handle (W3). The handle sits at the
 * preview's TOP-RIGHT and the overlay window is TOP-LEFT anchored: width follows the finger
 * horizontally; height grows when dragging UP; windowDyPx moves params.y up by exactly the
 * window's pixel growth, so the TOP edge follows the finger and the mic pill below stays put.
 */
class ResizeMathTest {

    // Realistic device: 1080x2400 @ density 2.625 (Pixel-class).
    private val density = 2.625f
    private val screenW = 1080
    private val screenH = 2400

    private fun resize(dxPx: Float, dyPx: Float, startW: Float = 280f, startH: Float = 120f) =
        ResizeMath.resize(
            startWidthDp = startW,
            startHeightDp = startH,
            dragDxPx = dxPx,
            dragDyPx = dyPx,
            density = density,
            screenWidthPx = screenW,
            screenHeightPx = screenH,
        )

    @Test fun max_width_is_95_percent_of_screen_or_the_560dp_cap_whichever_is_smaller() {
        // 0.95 * 1080 / 2.625 = 390.857dp — the screen term wins over the 560dp cap here.
        assertEquals(390.857f, ResizeMath.maxWidthDp(screenW, density), 0.01f)
        // 1600px-wide density-2.0 tablet: 0.95 * 1600 / 2.0 = 760dp -> capped at 560.
        assertEquals(560f, ResizeMath.maxWidthDp(1600, 2.0f), 0f)
    }

    @Test fun max_height_is_60_percent_of_screen() {
        assertEquals(548.571f, ResizeMath.maxHeightDp(screenH, density), 0.01f)
    }

    @Test fun zero_drag_is_identity() {
        val r = resize(0f, 0f)
        assertEquals(280f, r.widthDp, 0f)
        assertEquals(120f, r.heightDp, 0f)
        assertEquals(0, r.windowDyPx)
    }

    @Test fun width_follows_the_finger_right() {
        // +105px at 2.625 density = +40dp.
        assertEquals(320f, resize(105f, 0f).widthDp, 0.001f)
    }

    @Test fun width_clamps_at_min_when_dragged_far_left() {
        assertEquals(ResizeMath.MIN_WIDTH_DP, resize(-1000f, 0f).widthDp, 0f)
    }

    @Test fun width_clamps_at_the_screen_derived_max_when_dragged_far_right() {
        assertEquals(ResizeMath.maxWidthDp(screenW, density), resize(5000f, 0f).widthDp, 0.001f)
    }

    @Test fun height_grows_when_dragging_UP() {
        // Finger moves up = negative dy. -105px = +40dp of height.
        assertEquals(160f, resize(0f, -105f).heightDp, 0.001f)
    }

    @Test fun height_clamps_at_min_when_dragged_far_down() {
        assertEquals(ResizeMath.MIN_HEIGHT_DP, resize(0f, 2000f).heightDp, 0f)
    }

    @Test fun height_clamps_at_the_screen_derived_max_when_dragged_far_up() {
        assertEquals(ResizeMath.maxHeightDp(screenH, density), resize(0f, -5000f).heightDp, 0.001f)
    }

    @Test fun growing_taller_moves_the_window_UP_by_exactly_the_pixel_growth() {
        // +40dp height at 2.625 = +105px of window growth -> y compensates by -105, the same
        // distance the finger travelled up: the top edge tracks the finger.
        assertEquals(-105, resize(0f, -105f).windowDyPx)
    }

    @Test fun shrinking_moves_the_window_DOWN_and_compensation_tracks_the_CLAMPED_height() {
        // Drag down 2000px: raw height would be far below zero but clamps at 80dp — a -40dp
        // change. Compensation must follow the CLAMPED delta: -(-40 * 2.625) = +105.
        val r = resize(0f, 2000f)
        assertEquals(ResizeMath.MIN_HEIGHT_DP, r.heightDp, 0f)
        assertEquals(105, r.windowDyPx)
    }

    @Test fun diagonal_drag_resizes_both_axes_independently() {
        val r = resize(105f, -105f)
        assertEquals(320f, r.widthDp, 0.001f)
        assertEquals(160f, r.heightDp, 0.001f)
        assertEquals(-105, r.windowDyPx)
    }

    @Test fun a_tiny_screen_whose_max_is_below_MIN_never_throws_and_pins_to_MIN() {
        // 0.95 * 400 / 2.625 = 144.8dp < MIN_WIDTH_DP (200): Kotlin's coerceIn THROWS when
        // max < min, so the implementation must floor the screen-derived max at MIN first.
        val r = ResizeMath.resize(280f, 120f, 0f, 0f, 2.625f, 400, 2400)
        assertEquals(ResizeMath.MIN_WIDTH_DP, r.widthDp, 0f)
    }

    @Test fun defaults_are_the_shipped_280_by_120() {
        assertEquals(280f, ResizeMath.DEFAULT_WIDTH_DP, 0f)
        assertEquals(120f, ResizeMath.DEFAULT_HEIGHT_DP, 0f)
    }
}
```

- [ ] **Step 2: Run the test class, expect FAIL.** From the repo root in PowerShell:
  `$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon --tests "com.whispereverywhere.service.ResizeMathTest"`
  Expected outcome: `BUILD FAILED` — `:app:compileDebugUnitTestKotlin` fails with `e: ... ResizeMathTest.kt ... unresolved reference 'ResizeMath'` (the class does not exist yet; a compile failure is the expected red state for a brand-new class).

- [ ] **Step 3: Write the minimal implementation.** Create `app/src/main/java/com/whispereverywhere/service/ResizeMath.kt` with exactly:

```kotlin
package com.whispereverywhere.service

import kotlin.math.roundToInt

/**
 * Pure geometry for the transcript-window resize handle (W3). The handle sits at the preview's
 * TOP-RIGHT; the overlay window is TOP-LEFT anchored (Gravity.TOP or Gravity.START) and grows
 * downward, so:
 *  - width follows the finger horizontally (dragDxPx > 0 = wider);
 *  - height grows when dragging UP (dragDyPx < 0 = taller);
 *  - [Result.windowDyPx] moves params.y by exactly the height change in px, so the TOP edge
 *    follows the finger while the mic pill below stays put.
 *
 * Bounds are re-derived from the LIVE screen metrics on every call (rotation-safe), and each
 * screen-derived max is floored at its min so a pathological screen can never make coerceIn
 * throw (coerceIn(min, max) throws IllegalArgumentException when max < min).
 */
object ResizeMath {
    const val MIN_WIDTH_DP = 200f
    const val MAX_WIDTH_DP_CAP = 560f
    const val MIN_HEIGHT_DP = 80f
    const val DEFAULT_WIDTH_DP = 280f
    const val DEFAULT_HEIGHT_DP = 120f

    data class Result(val widthDp: Float, val heightDp: Float, val windowDyPx: Int)

    fun maxWidthDp(screenWidthPx: Int, density: Float): Float =
        minOf(0.95f * screenWidthPx / density, MAX_WIDTH_DP_CAP)

    fun maxHeightDp(screenHeightPx: Int, density: Float): Float =
        0.60f * screenHeightPx / density

    fun resize(
        startWidthDp: Float,
        startHeightDp: Float,
        dragDxPx: Float,
        dragDyPx: Float,
        density: Float,
        screenWidthPx: Int,
        screenHeightPx: Int,
    ): Result {
        val widthDp = (startWidthDp + dragDxPx / density).coerceIn(
            MIN_WIDTH_DP,
            maxWidthDp(screenWidthPx, density).coerceAtLeast(MIN_WIDTH_DP),
        )
        val heightDp = (startHeightDp - dragDyPx / density).coerceIn(
            MIN_HEIGHT_DP,
            maxHeightDp(screenHeightPx, density).coerceAtLeast(MIN_HEIGHT_DP),
        )
        val windowDyPx = -((heightDp - startHeightDp) * density).roundToInt()
        return Result(widthDp, heightDp, windowDyPx)
    }
}
```

- [ ] **Step 4: Run the test class, expect PASS.**
  `$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon --tests "com.whispereverywhere.service.ResizeMathTest"`
  Expected outcome: `BUILD SUCCESSFUL` — all 14 `ResizeMathTest` cases pass.

- [ ] **Step 5: Run the full JVM suite.**
  `$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon`
  Expected outcome: `BUILD SUCCESSFUL`, no failures.

- [ ] **Step 6: Commit.**
  `git add app/src/main/java/com/whispereverywhere/service/ResizeMath.kt app/src/test/java/com/whispereverywhere/service/ResizeMathTest.kt`
  `git commit -m "feat(bubble): ResizeMath — the resize handle's pure clamp + y-compensation table"`

---

### Task C2: Persisted preview size — prefs + applyPreviewSize + runtime-owned maxHeight

**Files:**
- Modify: `app/src/main/java/com/whispereverywhere/data/local/PreferencesManager.kt` — imports (≈:5), after `bubblePositionY` (≈:185-189), companion keys after `KEY_BUBBLE_Y` (≈:336)
- Modify: `app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt` — `createBubbleView()` (≈:1102-1177 at HEAD; W1/W2 shift lines — anchor by function) and `showSessionPreview()` (created by Workstream 2, at the old onOpen preview block, spec ≈:1867-1906)
- Modify: `app/src/main/res/layout/floating_bubble.xml` — `transcription_edit_text` (:26-37, `android:maxHeight` at :30)

**Interfaces:**
- Consumes (from C1): `ResizeMath.DEFAULT_WIDTH_DP`, `ResizeMath.DEFAULT_HEIGHT_DP`, `ResizeMath.MIN_WIDTH_DP`, `ResizeMath.MIN_HEIGHT_DP`, `ResizeMath.maxWidthDp(Int, Float): Float`, `ResizeMath.maxHeightDp(Int, Float): Float`. From Workstream 2: `private fun showSessionPreview(live: Boolean)` containing the anchor comment `// W3: applyPreviewSize() call lands here`.
- Produces:
  - `PreferencesManager.bubbleTextWidthDp: Float` (var, key `"bubble_text_width_dp"`, default `ResizeMath.DEFAULT_WIDTH_DP`)
  - `PreferencesManager.bubbleTextHeightDp: Float` (var, key `"bubble_text_height_dp"`, default `ResizeMath.DEFAULT_HEIGHT_DP`)
  - `FloatingBubbleService.applyPreviewSize(widthDp: Float = ..., heightDp: Float = ...)` — C3's live-resize path calls the two-arg form; C3/C4 call the zero-arg form.

The XML `android:maxHeight` removal and the `applyPreviewSize` wiring MUST land in the same commit: without the runtime `setMaxHeight`, removing the XML cap would let the transcript grow unbounded.

- [ ] **Step 1: Add the ResizeMath import to PreferencesManager.** In `app/src/main/java/com/whispereverywhere/data/local/PreferencesManager.kt`, replace:

```kotlin
import com.whispereverywhere.provider.ProviderId
import com.whispereverywhere.tts.ttsCloudVoiceKey
```

with:

```kotlin
import com.whispereverywhere.provider.ProviderId
import com.whispereverywhere.service.ResizeMath
import com.whispereverywhere.tts.ttsCloudVoiceKey
```

- [ ] **Step 2: Add the two size vars.** In the same file, directly after the `bubblePositionY` var — i.e. replace:

```kotlin
    var bubblePositionY: Float
        get() = prefs.getFloat(KEY_BUBBLE_Y, 0.5f)
        set(value) {
            prefs.edit().putFloat(KEY_BUBBLE_Y, value).apply()
        }
```

with:

```kotlin
    var bubblePositionY: Float
        get() = prefs.getFloat(KEY_BUBBLE_Y, 0.5f)
        set(value) {
            prefs.edit().putFloat(KEY_BUBBLE_Y, value).apply()
        }

    // Transcript preview panel size in dp (W3 resize handle). Written only by resize drag-end
    // and long-press reset; every read is applied through FloatingBubbleService.applyPreviewSize,
    // which re-clamps against the LIVE screen — a stale/corrupt value can't wedge the panel.
    var bubbleTextWidthDp: Float
        get() = prefs.getFloat(KEY_BUBBLE_TEXT_WIDTH_DP, ResizeMath.DEFAULT_WIDTH_DP)
        set(value) {
            prefs.edit().putFloat(KEY_BUBBLE_TEXT_WIDTH_DP, value).apply()
        }

    var bubbleTextHeightDp: Float
        get() = prefs.getFloat(KEY_BUBBLE_TEXT_HEIGHT_DP, ResizeMath.DEFAULT_HEIGHT_DP)
        set(value) {
            prefs.edit().putFloat(KEY_BUBBLE_TEXT_HEIGHT_DP, value).apply()
        }
```

- [ ] **Step 3: Add the key constants.** In the same file's `companion object`, replace:

```kotlin
        private const val KEY_BUBBLE_X = "bubble_x"
        private const val KEY_BUBBLE_Y = "bubble_y"
```

with:

```kotlin
        private const val KEY_BUBBLE_X = "bubble_x"
        private const val KEY_BUBBLE_Y = "bubble_y"
        private const val KEY_BUBBLE_TEXT_WIDTH_DP = "bubble_text_width_dp"
        private const val KEY_BUBBLE_TEXT_HEIGHT_DP = "bubble_text_height_dp"
```

- [ ] **Step 4: Add `applyPreviewSize` to FloatingBubbleService.** In `app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt`, insert directly ABOVE the line `    // ========== Pin / Lock ==========` (which follows `createBubbleView()`'s closing brace):

```kotlin
    // ========== Transcript preview sizing (W3) ==========

    /**
     * Apply the persisted (or, mid-resize, the in-flight) preview panel size to both transcript
     * views. dp -> px against the CURRENT displayMetrics and re-clamped to the CURRENT screen on
     * every call, so a stale/corrupt pref or a rotation can never produce an off-screen or
     * zero-size panel. Runtime owns the panel's width and max height; the XML 280dp width is only
     * the pre-first-apply default and android:maxHeight was removed from the layout entirely.
     */
    private fun applyPreviewSize(
        widthDp: Float = app.preferencesManager.bubbleTextWidthDp,
        heightDp: Float = app.preferencesManager.bubbleTextHeightDp,
    ) {
        val dm = resources.displayMetrics
        val maxW = ResizeMath.maxWidthDp(dm.widthPixels, dm.density)
            .coerceAtLeast(ResizeMath.MIN_WIDTH_DP)
        val maxH = ResizeMath.maxHeightDp(dm.heightPixels, dm.density)
            .coerceAtLeast(ResizeMath.MIN_HEIGHT_DP)
        val widthPx = (widthDp.coerceIn(ResizeMath.MIN_WIDTH_DP, maxW) * dm.density).toInt()
        val heightPx = (heightDp.coerceIn(ResizeMath.MIN_HEIGHT_DP, maxH) * dm.density).toInt()
        transcriptionEditText.layoutParams = transcriptionEditText.layoutParams.apply { width = widthPx }
        transcriptionEditText.maxHeight = heightPx
        transcriptionDeltaText.layoutParams = transcriptionDeltaText.layoutParams.apply { width = widthPx }
    }
```

(`ResizeMath` is in the same package — no import needed.)

- [ ] **Step 5: Call it at view creation.** Inside `createBubbleView()`, replace:

```kotlin
        transcriptionEditText.movementMethod = android.text.method.ScrollingMovementMethod()
```

with:

```kotlin
        transcriptionEditText.movementMethod = android.text.method.ScrollingMovementMethod()
        applyPreviewSize()
```

- [ ] **Step 6: Call it at every preview show.** Inside `showSessionPreview(live: Boolean)` (the W2 function), replace the anchor comment line:

```kotlin
        // W3: applyPreviewSize() call lands here
```

with:

```kotlin
        applyPreviewSize()
```

- [ ] **Step 7: Remove the XML maxHeight.** In `app/src/main/res/layout/floating_bubble.xml`, replace:

```xml
        <TextView
            android:id="@+id/transcription_edit_text"
            android:layout_width="280dp"
            android:layout_height="wrap_content"
            android:maxHeight="120dp"
            android:scrollbars="vertical"
```

with:

```xml
        <TextView
            android:id="@+id/transcription_edit_text"
            android:layout_width="280dp"
            android:layout_height="wrap_content"
            android:scrollbars="vertical"
```

(Height stays `wrap_content` — that is what makes the runtime `setMaxHeight` bind. The 280dp width remains as the pre-first-apply default; `applyPreviewSize` overwrites it via `layoutParams.width`. The delta strip at `transcription_delta_text` is untouched here: it keeps `maxLines="5"` and its width follows the pref via `applyPreviewSize`.)

- [ ] **Step 8: Compile.**
  `$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:assembleDebug --no-daemon`
  Expected outcome: `BUILD SUCCESSFUL`.

- [ ] **Step 9: Run the full JVM suite.**
  `$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon`
  Expected outcome: `BUILD SUCCESSFUL`, no failures.

- [ ] **Step 10: Commit.**
  `git add app/src/main/java/com/whispereverywhere/data/local/PreferencesManager.kt app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt app/src/main/res/layout/floating_bubble.xml`
  `git commit -m "feat(bubble): persisted preview size — runtime owns the panel width and max height"`

---

### Task C3: The resize handle — drawable, view, and the live-resize gesture

**Files:**
- Create: `app/src/main/res/drawable/ic_resize_handle.xml`
- Modify: `app/src/main/res/layout/floating_bubble.xml` — `transcription_preview_container` (:11-66)
- Modify: `app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt` — fields after `LONG_PRESS_MS` (≈:257), `createBubbleView()` view lookups (≈:1112-1114), new functions after `applyPreviewSize` (added by C2)

**Interfaces:**
- Consumes (C1): `ResizeMath.resize(...)`, `ResizeMath.Result`, `ResizeMath.DEFAULT_WIDTH_DP/HEIGHT_DP`. (C2): `applyPreviewSize(widthDp, heightDp)`, `applyPreviewSize()`, `PreferencesManager.bubbleTextWidthDp/bubbleTextHeightDp`. Existing: `clampToBounds(x, y, viewW, viewH): Pair<Int, Int>` (≈:1231), `LONG_PRESS_MS = 500L` (≈:257), `serviceScope` (Main-dispatcher), `params`, `windowManager`, `showToast(String)`.
- Produces (Task C4 relies on these exact signatures):
  - `private fun estimatedWindowSize(widthDp: Float, heightDp: Float): Pair<Int, Int>`
  - `private fun currentWindowSize(): Pair<Int, Int>`
  - `private fun reclampNow()`
  - `private fun handleResizeTouch(event: MotionEvent): Boolean`, `private fun resetPreviewSize()`
  - `R.id.resize_handle`, `R.drawable.ic_resize_handle`

- [ ] **Step 1: Create the 45° double-arrow drawable.** Create `app/src/main/res/drawable/ic_resize_handle.xml` (Material "open in full" glyph — a double-headed arrow on the ↗/↙ diagonal, matching the house single-path vector style of `ic_keyboard.xml`):

```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="#FFFFFF"
        android:pathData="M21,11L21,3l-8,0l3.29,3.29l-10,10L3,13l0,8l8,0l-3.29,-3.29l10,-10L21,11z"/>
</vector>
```

- [ ] **Step 2: Put the handle at the panel's top-right.** The container is a vertical `LinearLayout`, so a `FrameLayout` wrapper around the transcript TextView carries the overlay (the wrapper's top-right IS the panel's top-right — the transcript view defines the panel width). In `app/src/main/res/layout/floating_bubble.xml`, replace (this is the post-C2 text, without `android:maxHeight`):

```xml
        <!-- Read-only text for finalized transcription chunks -->
        <TextView
            android:id="@+id/transcription_edit_text"
            android:layout_width="280dp"
            android:layout_height="wrap_content"
            android:scrollbars="vertical"
            android:textColor="#FFFFFF"
            android:textSize="14sp"
            android:minHeight="20dp"
            android:hint="Listening..."
            android:textColorHint="#99FFFFFF"
            android:gravity="top|start" />
```

with:

```xml
        <!-- Wrapper so the resize handle can OVERLAY the transcript's top-right corner: the
             container is a vertical LinearLayout, which can't stack children. The transcript
             view defines the panel width, so the wrapper's top-right is the panel's top-right. -->
        <FrameLayout
            android:layout_width="wrap_content"
            android:layout_height="wrap_content">

            <!-- Read-only text for finalized transcription chunks. Width + max height are
                 RUNTIME-OWNED (applyPreviewSize); 280dp is only the pre-first-apply default. -->
            <TextView
                android:id="@+id/transcription_edit_text"
                android:layout_width="280dp"
                android:layout_height="wrap_content"
                android:scrollbars="vertical"
                android:textColor="#FFFFFF"
                android:textSize="14sp"
                android:minHeight="20dp"
                android:hint="Listening..."
                android:textColorHint="#99FFFFFF"
                android:gravity="top|start" />

            <!-- W3 resize handle: 28dp touch target, 45-degree double-arrow, visible whenever
                 the container is (resizing mid-dictation is the intended flow). Drag = resize,
                 long-press = reset to 280x120dp. It consumes its own gesture — child-first
                 dispatch beats the root drag listener and its long-press-to-pin arming. -->
            <ImageView
                android:id="@+id/resize_handle"
                android:layout_width="28dp"
                android:layout_height="28dp"
                android:layout_gravity="top|end"
                android:padding="6dp"
                android:alpha="0.7"
                android:src="@drawable/ic_resize_handle"
                android:contentDescription="Resize transcript window" />
        </FrameLayout>
```

The delta `TextView` and everything else in the container stay exactly as they are.

- [ ] **Step 3: Add the gesture-state fields.** In `FloatingBubbleService.kt`, replace:

```kotlin
    // Long-press detection for pin toggle (500 ms threshold)
    private var longPressJob: kotlinx.coroutines.Job? = null
    private val LONG_PRESS_MS = 500L
```

with:

```kotlin
    // Long-press detection for pin toggle (500 ms threshold)
    private var longPressJob: kotlinx.coroutines.Job? = null
    private val LONG_PRESS_MS = 500L

    // Resize-handle gesture state (W3). Deliberately separate from the root-drag fields
    // (initialX/initialTouchX/...): the handle consumes its own gesture stream so the two never
    // interleave, but sharing fields would make that invariant load-bearing and invisible.
    private lateinit var resizeHandle: ImageView
    private var resizeStartWidthDp = 0f
    private var resizeStartHeightDp = 0f
    private var resizeStartTouchX = 0f
    private var resizeStartTouchY = 0f
    private var resizeStartWindowY = 0
    private var isResizing = false
    private var lastResizeResult: ResizeMath.Result? = null
    private var resizeLongPressJob: kotlinx.coroutines.Job? = null
```

- [ ] **Step 4: Wire the handle in createBubbleView.** Replace:

```kotlin
        transcriptionEditText = bubbleView.findViewById(R.id.transcription_edit_text)
        transcriptionDeltaText = bubbleView.findViewById(R.id.transcription_delta_text)
```

with:

```kotlin
        transcriptionEditText = bubbleView.findViewById(R.id.transcription_edit_text)
        transcriptionDeltaText = bubbleView.findViewById(R.id.transcription_delta_text)
        resizeHandle = bubbleView.findViewById(R.id.resize_handle)
        resizeHandle.setOnTouchListener { _, event -> handleResizeTouch(event) }
```

- [ ] **Step 5: Add the geometry helpers.** Directly after the `applyPreviewSize` function (added in C2), insert:

```kotlin
    /**
     * Estimated full-window dims (px) for a GIVEN panel size — used when the view isn't laid out
     * yet or mid-resize when the measured size lags a frame. Base: the 64dp pill estimate the old
     * clamps used. When the preview is visible: width = panel + 48dp chrome (16dp container
     * padding per side + 8dp root padding per side); height stacks panel + 48dp chrome (12dp
     * container padding top/bottom + 8dp bottom margin + 8dp root padding top/bottom) on the
     * pill; the live-mode delta strip, when visible, gets its own ~100dp allowance (it is not
     * part of heightDp). A slight OVER-estimate by design: clamping with a too-big window only
     * keeps it further from the screen edge — the safe direction.
     */
    private fun estimatedWindowSize(widthDp: Float, heightDp: Float): Pair<Int, Int> {
        val density = resources.displayMetrics.density
        val pillEstimate = (64 * density).toInt()
        if (transcriptionPreviewContainer.visibility != View.VISIBLE) {
            return Pair(pillEstimate, pillEstimate)
        }
        val panelW = ((widthDp + 48f) * density).toInt()
        // Live sessions stack the delta strip (maxLines=5, italic) below the main panel —
        // without this allowance the estimate UNDER-shoots in live mode and a mid-resize
        // clamp could leave the window bottom off-screen.
        val stripAllowance =
            if (transcriptionDeltaText.visibility == View.VISIBLE) (100 * density).toInt() else 0
        val panelH = ((heightDp + 48f) * density).toInt() + stripAllowance
        return Pair(maxOf(pillEstimate, panelW), pillEstimate + panelH)
    }

    /** The window's REAL current dims for clamping: measured when laid out, estimated otherwise. */
    private fun currentWindowSize(): Pair<Int, Int> {
        if (bubbleView.width > 0 && bubbleView.height > 0) {
            return Pair(bubbleView.width, bubbleView.height)
        }
        return estimatedWindowSize(
            app.preferencesManager.bubbleTextWidthDp,
            app.preferencesManager.bubbleTextHeightDp,
        )
    }

    /** Re-clamp params to bounds for the window's CURRENT size (preview shown/hidden, resize,
     *  reset). Post from a geometry-changing site so the measure pass has run first. */
    private fun reclampNow() {
        val (winW, winH) = currentWindowSize()
        val clamped = clampToBounds(params.x, params.y, winW, winH)
        if (clamped.first == params.x && clamped.second == params.y) return
        params.x = clamped.first
        params.y = clamped.second
        try {
            windowManager.updateViewLayout(bubbleView, params)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
```

- [ ] **Step 6: Add the gesture itself.** Directly after `reclampNow()`, insert:

```kotlin
    /**
     * The resize-handle gesture (W3). The handle CONSUMES its stream (child-first dispatch), so
     * the root drag / long-press-to-pin listener never sees these events. Width follows the
     * finger; height grows when dragging UP, and params.y compensates by the clamped growth so
     * the TOP edge follows the finger while the mic pill below stays put. Live-apply per move;
     * persist on ACTION_UP (size AND the moved y, exactly like the root drag-end persists
     * position); long-press without a drag resets to the 280x120dp defaults. Pin locks POSITION,
     * not size — resizing while pinned is allowed, and its y-compensation is part of resizing.
     */
    private fun handleResizeTouch(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                resizeStartWidthDp = app.preferencesManager.bubbleTextWidthDp
                resizeStartHeightDp = app.preferencesManager.bubbleTextHeightDp
                resizeStartTouchX = event.rawX
                resizeStartTouchY = event.rawY
                resizeStartWindowY = params.y
                isResizing = false
                lastResizeResult = null
                // Same 500 ms threshold as the pin gesture; cancelled the moment a drag starts.
                resizeLongPressJob?.cancel()
                resizeLongPressJob = serviceScope.launch {
                    delay(LONG_PRESS_MS)
                    if (!isResizing) resetPreviewSize()
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - resizeStartTouchX
                val dy = event.rawY - resizeStartTouchY
                if (kotlin.math.abs(dx) > 10 || kotlin.math.abs(dy) > 10) {
                    isResizing = true
                    resizeLongPressJob?.cancel()
                }
                if (!isResizing) return true
                val dm = resources.displayMetrics
                val result = ResizeMath.resize(
                    startWidthDp = resizeStartWidthDp,
                    startHeightDp = resizeStartHeightDp,
                    dragDxPx = dx,
                    dragDyPx = dy,
                    density = dm.density,
                    screenWidthPx = dm.widthPixels,
                    screenHeightPx = dm.heightPixels,
                )
                // Live-apply through the SAME code path the persisted size uses, then move the
                // window's top edge with the finger and re-clamp against the NEW estimated dims.
                applyPreviewSize(result.widthDp, result.heightDp)
                val est = estimatedWindowSize(result.widthDp, result.heightDp)
                val clamped = clampToBounds(
                    params.x, resizeStartWindowY + result.windowDyPx, est.first, est.second,
                )
                params.x = clamped.first
                params.y = clamped.second
                try {
                    windowManager.updateViewLayout(bubbleView, params)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                lastResizeResult = result
                return true
            }
            MotionEvent.ACTION_UP -> {
                resizeLongPressJob?.cancel(); resizeLongPressJob = null
                val result = lastResizeResult
                if (isResizing && result != null) {
                    app.preferencesManager.bubbleTextWidthDp = result.widthDp
                    app.preferencesManager.bubbleTextHeightDp = result.heightDp
                    // Persist the compensated spot exactly like the root drag-end does: the
                    // window's y moved with the resize, and pop-up restore reads these fractions.
                    val dm = resources.displayMetrics
                    if (dm.widthPixels > 0) {
                        app.preferencesManager.bubblePositionX = params.x.toFloat() / dm.widthPixels
                    }
                    if (dm.heightPixels > 0) {
                        app.preferencesManager.bubblePositionY = params.y.toFloat() / dm.heightPixels
                    }
                }
                isResizing = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                resizeLongPressJob?.cancel(); resizeLongPressJob = null
                isResizing = false
                return true
            }
        }
        return false
    }

    /** Long-press on the handle: back to the stock 280x120dp panel, persisted immediately —
     *  insurance against wedging the window tiny. */
    private fun resetPreviewSize() {
        app.preferencesManager.bubbleTextWidthDp = ResizeMath.DEFAULT_WIDTH_DP
        app.preferencesManager.bubbleTextHeightDp = ResizeMath.DEFAULT_HEIGHT_DP
        applyPreviewSize()
        bubbleView.post { reclampNow() }
        showToast("Preview size reset")
    }
```

(`serviceScope` runs on `Dispatchers.Main`, so `resetPreviewSize`'s view work inside the launch is main-thread — the same pattern as the pin long-press at `handleTouch` ACTION_DOWN.)

- [ ] **Step 7: Compile.**
  `$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:assembleDebug --no-daemon`
  Expected outcome: `BUILD SUCCESSFUL`.

- [ ] **Step 8: Run the full JVM suite.**
  `$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon`
  Expected outcome: `BUILD SUCCESSFUL`, no failures.

- [ ] **Step 9: Commit.**
  `git add app/src/main/res/drawable/ic_resize_handle.xml app/src/main/res/layout/floating_bubble.xml app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt`
  `git commit -m "feat(bubble): the resize handle — drag sizes the transcript live, long-press resets"`

---

### Task C4: One size-aware clamp — real dims at every site, rotation stops rewriting the spot

**Files:**
- Modify: `app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt` — `clampToBounds` (≈:1231), `savedPinnedPosition` (≈:1239), `reclampAfterConfigChange` (≈:1247), `showBubbleForMedia` (≈:875), `showBubbleNearTextField` (≈:937), `showBubbleAtRest` (≈:1003), `createBubbleView` restore block (≈:1148-1156), `handleTouch` ACTION_MOVE (≈:1299-1303), `showSessionPreview` (W2 function), `teardownRealtime` (≈:2145-2156). All line numbers are HEAD `ae86024` pre-W1/W2 — anchor by function name.

**Interfaces:**
- Consumes (C3): `currentWindowSize(): Pair<Int, Int>`, `reclampNow()`. (C2): `applyPreviewSize()` call site inside `showSessionPreview`. Existing: `getNavigationBarHeight(): Int` (≈:1093).
- Produces: `clampToBounds(x, y, viewW, viewH)` now including the navbar term (same signature); `savedPinnedPosition(): Pair<Int, Int>` (parameterless — the old `savedPinnedPosition(size: Int)` is gone).

Background for whoever implements this: today every clamp assumes a 56–64dp pill while the real window can be 280dp+ wide with the preview up (`bubblePositionX` defaults to 0.9, so the default-position window hangs up to ~272dp off-screen right), and the two show-site y-clamps disagree on the navbar term (`showBubbleForMedia` subtracts `getNavigationBarHeight()`, `showBubbleNearTextField` doesn't). This task makes `clampToBounds` the ONLY clamp, feeds it real dimensions everywhere, and stops `reclampAfterConfigChange` from persisting position — persisted fractions must be written only by user drag-end, resize-end, and pin; restores clamp anyway, so rotation can no longer silently rewrite the saved spot using transient preview-inflated dimensions.

- [ ] **Step 1: Move the navbar term into the shared clamp.** Replace:

```kotlin
    /**
     * Clamp (x, y) so the bubble view (viewW x viewH) stays fully on screen.
     * Falls back gracefully when screen size is not yet determined.
     */
    private fun clampToBounds(x: Int, y: Int, viewW: Int, viewH: Int): Pair<Int, Int> {
        val dm = resources.displayMetrics
        val maxX = (dm.widthPixels - viewW).coerceAtLeast(0)
        val maxY = (dm.heightPixels - viewH).coerceAtLeast(0)
        return Pair(x.coerceIn(0, maxX), y.coerceIn(0, maxY))
    }
```

with:

```kotlin
    /**
     * THE clamp: (x, y) coerced so a viewW x viewH window stays fully on the USABLE screen.
     * The navbar term lives HERE now, so every caller agrees on the bottom edge — the two
     * show-site y-clamps used to disagree on it. Falls back gracefully (0..0) when the screen
     * size is not yet determined.
     */
    private fun clampToBounds(x: Int, y: Int, viewW: Int, viewH: Int): Pair<Int, Int> {
        val dm = resources.displayMetrics
        val maxX = (dm.widthPixels - viewW).coerceAtLeast(0)
        val maxY = (dm.heightPixels - viewH - getNavigationBarHeight()).coerceAtLeast(0)
        return Pair(x.coerceIn(0, maxX), y.coerceIn(0, maxY))
    }
```

- [ ] **Step 2: Make savedPinnedPosition size-aware and parameterless.** Replace:

```kotlin
    /** The user's pinned bubble position from prefs (stored as screen fractions), in px, clamped. */
    private fun savedPinnedPosition(size: Int): Pair<Int, Int> {
        val dm = resources.displayMetrics
        val x = (app.preferencesManager.bubblePositionX * dm.widthPixels).toInt()
        val y = (app.preferencesManager.bubblePositionY * dm.heightPixels).toInt()
        return clampToBounds(x, y, size, size)
    }
```

with:

```kotlin
    /** The user's pinned bubble position from prefs (stored as screen fractions), in px, clamped
     *  against the window's REAL current size — not the old 56dp pill guess. */
    private fun savedPinnedPosition(): Pair<Int, Int> {
        val dm = resources.displayMetrics
        val x = (app.preferencesManager.bubblePositionX * dm.widthPixels).toInt()
        val y = (app.preferencesManager.bubblePositionY * dm.heightPixels).toInt()
        val (winW, winH) = currentWindowSize()
        return clampToBounds(x, y, winW, winH)
    }
```

- [ ] **Step 3: Rewrite showBubbleForMedia's placement block.** Inside `showBubbleForMedia()`, replace:

```kotlin
        val bubbleSize = (56 * displayMetrics.density).toInt()
        val padding = (16 * displayMetrics.density).toInt()

        // The REMEMBERED spot, exactly like the text-field pop-up (owner 2026-08-01: the bubble
        // appears "right where I placed it last" — the old bottom-right media default was the
        // final teleporter left after the drag snap was removed). Pinned still wins.
        var targetX = (app.preferencesManager.bubblePositionX * screenWidth).toInt()
            .coerceIn(0, screenWidth - bubbleSize)
        var targetY = (app.preferencesManager.bubblePositionY * screenHeight).toInt()
            .coerceIn(padding, screenHeight - bubbleSize - padding - getNavigationBarHeight())
        if (isOverlayPinned) {
            val pinned = savedPinnedPosition(bubbleSize)
```

with:

```kotlin
        // The REMEMBERED spot, exactly like the text-field pop-up (owner 2026-08-01: the bubble
        // appears "right where I placed it last" — the old bottom-right media default was the
        // final teleporter left after the drag snap was removed). Pinned still wins. Clamped
        // through THE clamp against the window's REAL size (W3), not the old 56dp pill guess.
        val (winW, winH) = currentWindowSize()
        val restored = clampToBounds(
            (app.preferencesManager.bubblePositionX * screenWidth).toInt(),
            (app.preferencesManager.bubblePositionY * screenHeight).toInt(),
            winW,
            winH,
        )
        var targetX = restored.first
        var targetY = restored.second
        if (isOverlayPinned) {
            val pinned = savedPinnedPosition()
```

- [ ] **Step 4: Rewrite showBubbleNearTextField's placement block.** Inside `showBubbleNearTextField(rect: Rect)`, replace:

```kotlin
        val bubbleSize = (56 * displayMetrics.density).toInt()
        val padding = (16 * displayMetrics.density).toInt()

        // The bubble pops up WHERE IT WAS LAST — the user's dragged/remembered spot — never at a
        // field-derived position (owner decision 2026-08-01: "it should just pop up where it
        // popped up last time, or where the user moved the bubble to... a new location can be
        // annoying — users won't know where their bubble's gonna be"). The rect parameter is now
        // context-only (what focused, not where to go); bubblePositionX/Y is already written by
        // every drag, so the spot tracks the user for free. Pinned keeps its own stronger spot.
        var targetX = (app.preferencesManager.bubblePositionX * screenWidth).toInt()
            .coerceIn(0, screenWidth - bubbleSize)
        var targetY = (app.preferencesManager.bubblePositionY * screenHeight).toInt()
            .coerceIn(padding, screenHeight - bubbleSize - padding)
        if (isOverlayPinned) {
            val pinned = savedPinnedPosition(bubbleSize)
```

with:

```kotlin
        // The bubble pops up WHERE IT WAS LAST — the user's dragged/remembered spot — never at a
        // field-derived position (owner decision 2026-08-01: "it should just pop up where it
        // popped up last time, or where the user moved the bubble to... a new location can be
        // annoying — users won't know where their bubble's gonna be"). The rect parameter is now
        // context-only (what focused, not where to go); bubblePositionX/Y is already written by
        // every drag, so the spot tracks the user for free. Pinned keeps its own stronger spot.
        // Clamped through THE clamp against the window's REAL size (W3) — this site used to skip
        // the navbar term the media site subtracted; the shared clamp ends that disagreement.
        val (winW, winH) = currentWindowSize()
        val restored = clampToBounds(
            (app.preferencesManager.bubblePositionX * screenWidth).toInt(),
            (app.preferencesManager.bubblePositionY * screenHeight).toInt(),
            winW,
            winH,
        )
        var targetX = restored.first
        var targetY = restored.second
        if (isOverlayPinned) {
            val pinned = savedPinnedPosition()
```

- [ ] **Step 5: Fix showBubbleAtRest.** Inside `showBubbleAtRest()`, replace:

```kotlin
        val bubbleSize = (56 * resources.displayMetrics.density).toInt()
        val pos = savedPinnedPosition(bubbleSize)
```

with:

```kotlin
        val pos = savedPinnedPosition()
```

- [ ] **Step 6: Fix the restore-at-create clamp.** Inside `createBubbleView()`, replace:

```kotlin
        // Use a reasonable bubble size estimate for clamping before the view is measured.
        // The actual measured size is used in onConfigurationChanged after layout.
        val estimatedSize = (64 * displayMetrics.density).toInt()
        val clamped = clampToBounds(rawX, rawY, estimatedSize, estimatedSize)
```

with:

```kotlin
        // Size-aware estimate for clamping before the view is measured (the preview is GONE at
        // inflate, so this resolves to the same 64dp pill estimate as before); the measured size
        // takes over at the next reclamp (config change / preview show).
        val (estW, estH) = currentWindowSize()
        val clamped = clampToBounds(rawX, rawY, estW, estH)
```

- [ ] **Step 7: Clamp the root drag live.** Inside `handleTouch()`'s `MotionEvent.ACTION_MOVE` branch, replace:

```kotlin
                // When pinned, suppress all drag movement; only taps (and long-press) register
                if (!isOverlayPinned && isDragging) {
                    params.x = (initialX + dx).toInt()
                    params.y = (initialY + dy).toInt()
                    windowManager.updateViewLayout(bubbleView, params)
                }
```

with:

```kotlin
                // When pinned, suppress all drag movement; only taps (and long-press) register.
                // Clamped per step against the REAL window size, so a drag can no longer park
                // the preview-widened window partly off-screen (drag-end persists params as-is).
                if (!isOverlayPinned && isDragging) {
                    val (winW, winH) = currentWindowSize()
                    val clamped = clampToBounds((initialX + dx).toInt(), (initialY + dy).toInt(), winW, winH)
                    params.x = clamped.first
                    params.y = clamped.second
                    windowManager.updateViewLayout(bubbleView, params)
                }
```

- [ ] **Step 8: Stop the config-change position persist.** Replace the whole function:

```kotlin
    /** Re-clamp and persist after a configuration change (rotation / fold). */
    private fun reclampAfterConfigChange() {
        val viewW = if (bubbleView.width > 0) bubbleView.width else (64 * resources.displayMetrics.density).toInt()
        val viewH = if (bubbleView.height > 0) bubbleView.height else viewW
        val clamped = clampToBounds(params.x, params.y, viewW, viewH)
        params.x = clamped.first
        params.y = clamped.second
        try {
            windowManager.updateViewLayout(bubbleView, params)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        val dm = resources.displayMetrics
        if (dm.widthPixels > 0) {
            app.preferencesManager.bubblePositionX = params.x.toFloat() / dm.widthPixels
        }
        if (dm.heightPixels > 0) {
            app.preferencesManager.bubblePositionY = params.y.toFloat() / dm.heightPixels
        }
    }
```

with:

```kotlin
    /**
     * Re-clamp after a configuration change (rotation / fold). Deliberately does NOT persist:
     * the saved fractions are written only by user drag-end, resize-end, and pin — a rotation
     * mid-session must never rewrite the user's spot using transient preview-inflated
     * dimensions. Restores clamp anyway, so nothing is lost by not writing here.
     */
    private fun reclampAfterConfigChange() {
        val (viewW, viewH) = currentWindowSize()
        val clamped = clampToBounds(params.x, params.y, viewW, viewH)
        params.x = clamped.first
        params.y = clamped.second
        try {
            windowManager.updateViewLayout(bubbleView, params)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
```

- [ ] **Step 9: Re-clamp on preview SHOW.** Inside `showSessionPreview(live: Boolean)` ONLY (the call in `createBubbleView` and `resetPreviewSize` must not be touched — locate the one inside `fun showSessionPreview`), replace the `applyPreviewSize()` line Task C2 placed at the W3 anchor:

```kotlin
        applyPreviewSize()
```

with:

```kotlin
        applyPreviewSize()
        // The preview appearing is a geometry change: the window just grew upward/rightward.
        // Posted so the measure pass has run and currentWindowSize() sees the REAL new dims.
        bubbleView.post { reclampNow() }
```

- [ ] **Step 10: Re-clamp on preview HIDE.** Inside `teardownRealtime()`, replace:

```kotlin
        transcriptionDeltaText.visibility = View.GONE
        transcriptionPreviewContainer.visibility = View.GONE
```

with:

```kotlin
        transcriptionDeltaText.visibility = View.GONE
        transcriptionPreviewContainer.visibility = View.GONE
        // Geometry change in the other direction (window shrinks back to the pill) — posted so
        // the re-measure has run. Harmless when nothing moved: reclampNow() no-ops on equality.
        bubbleView.post { reclampNow() }
```

- [ ] **Step 11: Verify no stale callers.** Run: `Grep pattern "savedPinnedPosition\(|estimatedSize" path app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt` (or `Select-String` equivalent). Expected outcome: `savedPinnedPosition(` appears only as the parameterless definition and three `savedPinnedPosition()` call sites; `estimatedSize` has zero hits.

- [ ] **Step 12: Compile.**
  `$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:assembleDebug --no-daemon`
  Expected outcome: `BUILD SUCCESSFUL`.

- [ ] **Step 13: Run the full JVM suite.**
  `$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon`
  Expected outcome: `BUILD SUCCESSFUL`, no failures.

- [ ] **Step 14: Commit.**
  `git add app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt`
  `git commit -m "fix(bubble): one size-aware clamp — real window dims everywhere, rotation stops rewriting the spot"`

---

### Task C5: Owner on-device checklist (install via adb only — NEVER installDebug)

**Files:**
- Test: none (owner-driven on-device verification; there is no instrumented-test workflow on this device by house rule)

**Interfaces:**
- Consumes: the C1–C4 build. APK lands at `C:\Users\bastr\.androidbuild\WhisperEverywhere\app\outputs\apk\debug\app-debug.apk` (build output is relocated outside the repo/OneDrive).

HARD RULE: never run `:app:installDebug` or any `connectedAndroidTest` — both uninstall first and wipe the owner's 500+ MB on-device models. `adb install -r` preserves app data.

- [ ] **Step 1: Build the APK.**
  `$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:assembleDebug --no-daemon`
  Expected outcome: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Install preserving data.**
  `& 'C:\Users\bastr\AppData\Local\Android\Sdk\platform-tools\adb.exe' install -r 'C:\Users\bastr\.androidbuild\WhisperEverywhere\app\outputs\apk\debug\app-debug.apk'`
  Expected outcome: `Success`.

- [ ] **Step 3: Owner checks — hand the device to the owner with this list; every item must pass:**
  1. **Resize during dictation in WhatsApp:** start dictation in a WhatsApp chat field; the preview panel shows with the double-arrow handle at its top-right. Drag the handle right → panel widens; drag UP → panel grows taller, its TOP edge follows the finger, and the mic pill does NOT move. The delta strip's width follows the panel's.
  2. **Handle vs. drag:** dragging the handle never moves the bubble window itself and never triggers the long-press pin toast.
  3. **Rotate mid-session:** rotate the phone while recording — the whole window (panel + pill) remains fully on-screen; rotating back restores roughly the original spot (rotation no longer rewrites the saved position).
  4. **Persistence:** stop the session, toggle the bubble service off and on (or reboot) — the next session's preview comes back at the resized dimensions.
  5. **Long-press reset:** press and hold the handle ~half a second without moving — "Preview size reset" toast, panel snaps back to 280×120dp, and the reset size survives a service restart.
  6. **Default-position clip is gone:** with position prefs at default (fresh spot: drag the bubble to the far right edge, mid-height), start a session — the preview window is clamped fully on-screen instead of hanging ~272dp off the right edge, and its bottom clears the navigation bar.
  7. **Extremes:** drag the handle far beyond every edge — width stops at 200dp/~95% of screen width, height stops at 80dp/60% of screen height, nothing crashes, and the window never ends up partially off-screen.

---

### Task D1: The how-to guide learns the new behavior

**Files:**
- Modify: `app/src/main/java/com/whispereverywhere/ui/HowToGuide.kt` (the "The bubble" and "Dictating into any app" section bodies, lines ~17–31)
- Test: `app/src/test/java/com/whispereverywhere/ui/HowToGuideTest.kt`

**Interfaces:**
- Consumes: the shipped Workstream B behavior (final-only commit) and Workstream C behavior (resize handle, long-press reset) — this task only describes them.
- Produces: nothing downstream; D2 is independent.

Context: `HowToGuide` is the single source of the in-app guide (rendered as a card and read aloud). Its test pins *invariants* (no speed claims, honest money talk, scoped privacy promise), not verbatim copy. The guide's "Read-aloud" section ("Select text in any app and choose Read aloud, or copy text…") stays true after Workstream A — the selection-**toolbar** entry survives; only the silent bubble morph died — so it is not touched.

- [ ] **Step 1: Write the failing test.** Add to `HowToGuideTest.kt`, after `the_privacy_promise_is_stated_and_scoped`:

```kotlin
    @Test fun the_dictation_flow_is_final_only_and_the_window_resizable() {
        // 3.4.0: text lands once, at stop — and the transcript window has a resize handle.
        val text = HowToGuide.plainText().lowercase()
        assertTrue(text.contains("when you tap the bubble again to stop"))
        assertTrue(text.contains("typed at the cursor in one go"))
        assertTrue(text.contains("double arrow"))
    }
```

- [ ] **Step 2: Run it, expect FAIL.**

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon --tests "com.whispereverywhere.ui.HowToGuideTest"
```

Expected: FAILED — `the_dictation_flow_is_final_only_and_the_window_resizable` (assertion on "when you tap the bubble again to stop").

- [ ] **Step 3: Update the two section bodies.** In `HowToGuide.kt`, replace the "The bubble" body string with:

```kotlin
            "The floating bubble is the heart of the app. Tap it to start dictating and tap " +
                "again to stop. Drag it anywhere — it remembers your spot. Press and hold to " +
                "lock it in place (a lock flashes to confirm), and press and hold again to " +
                "unlock. While you dictate, your words collect in a text window above the " +
                "bubble — drag the double arrow at its top right corner to make it any size " +
                "you like, and it stays that size; press and hold the arrow to go back to the " +
                "standard size. In Settings you can keep it always on screen, or let it pop " +
                "up on its own whenever a text field or keyboard appears and hide when idle.",
```

and the "Dictating into any app" body string with:

```kotlin
            "Tap into any text field, then tap the bubble and speak. Your words collect in " +
                "the bubble's text window as you talk, and when you tap the bubble again to " +
                "stop, the whole transcription is typed at the cursor in one go — no " +
                "half-finished chunks landing while you think. Dictation works in over 90 " +
                "languages. Everything runs on your phone by default — audio never leaves " +
                "your device unless you set up a cloud provider yourself.",
```

(Both preserve the pinned invariants: "typed at the cursor", "on your phone by default", "unless you set up a cloud provider", no speed words.)

- [ ] **Step 4: Run the class, expect PASS; then the full suite.**

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon --tests "com.whispereverywhere.ui.HowToGuideTest"
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon
```

Expected: `BUILD SUCCESSFUL` both times, all tests green.

- [ ] **Step 5: Commit.**

```powershell
git add app/src/main/java/com/whispereverywhere/ui/HowToGuide.kt app/src/test/java/com/whispereverywhere/ui/HowToGuideTest.kt
git commit -m "docs(guide): the guide catches up — text lands at stop, and the window resizes"
```

---

### Task D2: 3.4.0 / versionCode 74

**Files:**
- Modify: `app/build.gradle.kts:40-41`

**Interfaces:**
- Consumes: all prior workstreams complete and green.
- Produces: the release build identity for the 3.4.0 AAB (owner builds/signs/submits via the established Play flow — out of plan scope).

Explicitly out of plan scope, tracked for ship time (spec ship-notes): the Play release-notes copy should credit the review that asked for the resize + final-only commit, and the owner replies to that review after rollout. Both are drafted with the owner during the release session, not by an executing engineer here.

- [ ] **Step 1: Bump the version.** In `app/build.gradle.kts`, replace:

```kotlin
        versionCode = 73
        versionName = "3.3.0"  // multi-provider cloud release: optional user-key STT (OpenAI/Gemini/ElevenLabs/Soniox), cloud read-aloud, batch file transcription, OpenAI live word-for-word
```

with:

```kotlin
        versionCode = 74
        versionName = "3.4.0"  // the Play-review release: resizable transcript window, final-only commit into the field, the bubble is always a mic
```

- [ ] **Step 2: Compile and run the full suite.**

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:assembleDebug --no-daemon
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon
```

Expected: `BUILD SUCCESSFUL` both times.

- [ ] **Step 3: Commit.**

```powershell
git add app/build.gradle.kts
git commit -m "chore(release): 3.4.0 / versionCode 74"
```
