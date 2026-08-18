# 3.5.0 Implementation Plan — Onboarding Model Choice, Cloud Education, Home Key Note, Finalize Latency

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship Whisper Everywhere 3.5.0: new users pick their speech model from honestly-described tiers (and learn about model switching and cloud keys during onboarding), existing users see a dismissible Home card about cloud keys, and ending a session stops holding transcriptions open longer than the unavoidable tail work.

**Architecture:** Four workstreams. A reworks the guided onboarding's engines step around a no-preselection tier chooser backed by a new pure `ModelTierCopy` object (single source of tier descriptions, shared with the Settings picker) and reworks the cloud-keys step copy. B adds a pure-predicate-driven dismissible card to Home. C instruments the stop path with WE-DIAG phase timings, adds an honest FINALIZING status line, and fixes the statically-convicted unnecessary wait in the engine drain — without touching the load-bearing reconcile/orderer contracts. D is release mechanics.

**Tech Stack:** Kotlin, AGP 8.7.3 / Gradle 8.14.4, JDK 21, JUnit4 JVM tests, Jetpack Compose UI. No new dependencies.

**Spec (authoritative on any ambiguity):** `docs/superpowers/specs/2026-08-18-onboarding-choice-cloud-education-finalize-latency-design.md`

## Global Constraints

- **Order:** Workstream A (Tasks A1…) → B → C → D. Within a workstream, tasks run in order. Line hints were verified at HEAD `4cf7cdf` and shift as tasks land — anchor by the named symbol/function.
- **Build/test (PowerShell, repo root; set JAVA_HOME every invocation):**
  - Full JVM suite: `$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon`
  - One class: append `--tests "com.whispereverywhere.<pkg>.<Class>"`
  - Compile: `$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:assembleDebug --no-daemon`
  - Outputs land outside the repo: APK `C:\Users\bastr\.androidbuild\WhisperEverywhere\app\outputs\apk\debug\app-debug.apk`, test report `C:\Users\bastr\.androidbuild\WhisperEverywhere\app\reports\tests\testDebugUnitTest\index.html`.
- **NEVER run `:app:installDebug` or `:app:connectedDebugAndroidTest`** — both uninstall first and destroy the owner's 500+ MB on-device models. Device installs are owner-run: `adb.exe install -r <apk>` (adb at `C:\Users\bastr\AppData\Local\Android\Sdk\platform-tools\adb.exe`). Verification is JVM tests + owner-run on-device checks.
- **Tests:** JVM only, under `app/src/test/java/com/whispereverywhere/...`. UI composables and the two services have no direct tests by convention — extract pure logic instead. Concurrency-adjacent tests use a real background executor (`Executors.newSingleThreadExecutor()`), never a same-thread executor.
- **Do not touch:** `EmptyExpected`/`FallbackPolicy.reconcile` semantics, `SegmentOrderer` release rules, disclosure texts (no v3→v4 bump), Play declarations, the how-to guide (`HowToGuide.kt` — its pinned tests must pass unmodified).
- **Copy discipline:** no cloud speed claims anywhere; relative speed words between on-device tiers inside `ModelTierCopy` are factual and allowed.
- **Shared contracts (load-bearing exact names):** `ModelTierCopy.forId(id: String): TierCopy?` with `TierCopy(headline, badges, body)`; `PreferencesManager.cloudNoteDismissed: Boolean` (key `"cloud_note_dismissed"`); `OnboardingSetupViewModel.ensureSpeech()` downloads `WhisperCatalog.byId(prefs.selectedModelId) ?: byId(DEFAULT_MODEL_ID)!!`; `CloudKeyNote.shouldShow(cloudProviderConfigured: Boolean, dismissed: Boolean): Boolean`.
- **Every commit message** ends with exactly these two trailer lines, appended via a second `-m` (PowerShell backtick-n newline):

  ```
  Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT
  ```

  `git commit -m "<headline>" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`nClaude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT"`

---

---

### Task A1: ModelTierCopy — the single source of tier descriptions (TDD)

**Files:**
- Create: `app/src/main/java/com/whispereverywhere/model/ModelTierCopy.kt`
- Test: `app/src/test/java/com/whispereverywhere/model/ModelTierCopyTest.kt`

**Interfaces:**
- Consumes: `WhisperCatalog.pickable: List<WhisperModel>`, `WhisperModel.scope: ModelScope`, `WhisperModel.approxBytes: Long` (all existing, `app/src/main/java/com/whispereverywhere/model/WhisperModel.kt`).
- Produces: `object ModelTierCopy { data class TierCopy(val headline: String, val badges: List<String>, val body: String); fun forId(id: String): TierCopy? }` — consumed by Task A4 (onboarding cards) and Task A6 (Settings picker). This is a dictated shared contract; other sections rely on these exact names.

- [ ] **Step 1: Write the failing test file.** Create `app/src/test/java/com/whispereverywhere/model/ModelTierCopyTest.kt` with exactly:

```kotlin
package com.whispereverywhere.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelTierCopyTest {

    // The discipline that would have prevented the Bengali review: nobody can add or reword an
    // offered tier without stating a size, a speed-vs-accuracy position, and language coverage.

    @Test fun every_pickable_tier_has_copy() {
        WhisperCatalog.pickable.forEach { model ->
            assertNotNull("no copy for offered tier '${model.id}'", ModelTierCopy.forId(model.id))
        }
    }

    @Test fun every_tier_states_its_size_as_a_badge() {
        WhisperCatalog.pickable.forEach { model ->
            val copy = ModelTierCopy.forId(model.id)!!
            assertTrue(
                "tier '${model.id}' has no size badge",
                copy.badges.any { it.endsWith(" MB") },
            )
        }
    }

    @Test fun the_size_badge_tells_the_truth_about_the_download() {
        // 60 MB tiers say 60, 190 MB tiers say 190 — the badge must track approxBytes.
        WhisperCatalog.pickable.forEach { model ->
            val copy = ModelTierCopy.forId(model.id)!!
            val expectedMb = (model.approxBytes / 1_000_000L).toInt()
            val statedMb = copy.badges.first { it.endsWith(" MB") }.removeSuffix(" MB").toInt()
            assertTrue(
                "tier '${model.id}' badge says $statedMb MB but the download is ~$expectedMb MB",
                kotlin.math.abs(statedMb - expectedMb) <= 5,
            )
        }
    }

    @Test fun every_tier_takes_a_speed_vs_accuracy_position() {
        val positionWords = listOf("fastest", "fast", "slower", "accuracy")
        WhisperCatalog.pickable.forEach { model ->
            val copy = ModelTierCopy.forId(model.id)!!
            val all = (copy.headline + " " + copy.body).lowercase()
            assertTrue(
                "tier '${model.id}' copy takes no speed-vs-accuracy position",
                positionWords.any { all.contains(it) },
            )
        }
    }

    @Test fun language_coverage_is_a_badge_matching_the_catalog_scope() {
        // Coverage renders as a badge — visually impossible to miss. "English only" on every
        // ENGLISH tier; "90+ languages" on every MULTILINGUAL tier.
        WhisperCatalog.pickable.forEach { model ->
            val copy = ModelTierCopy.forId(model.id)!!
            when (model.scope) {
                ModelScope.ENGLISH -> assertTrue(
                    "ENGLISH tier '${model.id}' lacks the 'English only' badge",
                    copy.badges.contains("English only"),
                )
                ModelScope.MULTILINGUAL -> assertTrue(
                    "MULTILINGUAL tier '${model.id}' lacks the '90+ languages' badge",
                    copy.badges.contains("90+ languages"),
                )
            }
        }
    }

    @Test fun the_owner_approved_headlines_are_pinned_exactly() {
        assertEquals("Fastest", ModelTierCopy.forId("eco")!!.headline)
        assertEquals("Fast", ModelTierCopy.forId("base")!!.headline)
        assertEquals("Best English accuracy", ModelTierCopy.forId("pro")!!.headline)
        assertEquals("Best multilingual accuracy", ModelTierCopy.forId("multi")!!.headline)
    }

    @Test fun retired_and_unknown_tiers_have_no_copy() {
        // Retired tiers stay resolvable in WhisperCatalog but are not offered — no copy required.
        assertNull(ModelTierCopy.forId("extreme"))
        assertNull(ModelTierCopy.forId("ultra"))
        assertNull(ModelTierCopy.forId("nope"))
    }
}
```

- [ ] **Step 2: Run the test class — expect FAIL.** `$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon --tests "com.whispereverywhere.model.ModelTierCopyTest"` — expect `BUILD FAILED` on `:app:compileDebugUnitTestKotlin` with `Unresolved reference 'ModelTierCopy'` (the object does not exist yet — this proves the test is exercising the new symbol, not passing vacuously).
- [ ] **Step 3: Write the implementation.** Create `app/src/main/java/com/whispereverywhere/model/ModelTierCopy.kt` with exactly:

```kotlin
package com.whispereverywhere.model

/**
 * The single source of truth for tier descriptions — consumed by BOTH the guided onboarding's
 * model-choice cards and the Settings manual picker (OnboardingModelScreen), so the two surfaces
 * can never drift apart. Pattern: HowToGuide — pure, Compose- and Android-free, every string a
 * JVM test subject.
 *
 * Copy discipline, pinned by [ModelTierCopyTest] (the test that would have prevented the Bengali
 * review): every offered tier states a size, a speed-vs-accuracy position, and its language
 * coverage as a badge — "English only" on every ENGLISH-scope tier, "90+ languages" on every
 * MULTILINGUAL tier. Relative speed words BETWEEN on-device tiers ("Fastest", "slower") are
 * factual and allowed; the app-wide no-speed-claims rule constrains CLOUD claims, which this
 * copy never makes.
 */
object ModelTierCopy {

    /** One tier's card copy: a positioning headline, badge chips, and one honest sentence. */
    data class TierCopy(val headline: String, val badges: List<String>, val body: String)

    private val copyById: Map<String, TierCopy> = mapOf(
        "eco" to TierCopy(
            headline = "Fastest",
            badges = listOf("English only", "60 MB"),
            body = "Real-time dictation on any phone; the lightest download.",
        ),
        "base" to TierCopy(
            headline = "Fast",
            badges = listOf("90+ languages", "60 MB"),
            body = "Quick everyday dictation in most languages; lighter accuracy than the big " +
                "multilingual tier.",
        ),
        "pro" to TierCopy(
            headline = "Best English accuracy",
            badges = listOf("English only", "190 MB"),
            body = "Noticeably slower than Eco, noticeably sharper.",
        ),
        "multi" to TierCopy(
            headline = "Best multilingual accuracy",
            badges = listOf("90+ languages", "190 MB"),
            body = "The pick for non-English dictation.",
        ),
    )

    /** Copy for an offered tier id; null for retired or unknown ids (callers fall back). */
    fun forId(id: String): TierCopy? = copyById[id]
}
```

- [ ] **Step 4: Run the test class — expect PASS.** `$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon --tests "com.whispereverywhere.model.ModelTierCopyTest"` — expect `BUILD SUCCESSFUL` with all 7 tests passing.
- [ ] **Step 5: Run the full JVM suite.** `$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon` — expect `BUILD SUCCESSFUL`, `HowToGuideTest` untouched and green.
- [ ] **Step 6: Commit.** `git add app/src/main/java/com/whispereverywhere/model/ModelTierCopy.kt app/src/test/java/com/whispereverywhere/model/ModelTierCopyTest.kt` then `git commit -m "feat(model): ModelTierCopy — one source of truth for tier descriptions, discipline pinned by tests" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`nClaude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT"`

---

### Task A2: OnboardingLogic — the engines step's one-pick primary action (TDD)

**Files:**
- Modify: `app/src/main/java/com/whispereverywhere/ui/onboarding/OnboardingLogic.kt` (inside `object OnboardingLogic`, after `enginesContinueHint`)
- Test: `app/src/test/java/com/whispereverywhere/ui/onboarding/OnboardingLogicTest.kt` (append inside `class OnboardingLogicTest`)

**Interfaces:**
- Consumes: existing `OnboardingLogic.enginesContinueEnabled(speechReady: Boolean, speechFailed: Boolean): Boolean`.
- Produces (Task A4 relies on these exact names):
  - `data class EnginesAction(val label: String, val enabled: Boolean, val startsDownloads: Boolean)` (nested in `OnboardingLogic`)
  - `fun enginesPrimaryAction(downloadsBegun: Boolean, tierPicked: Boolean, speechReady: Boolean, speechFailed: Boolean): EnginesAction`
  - `const val TIER_SWITCH_HINT = "Not sure? Pick one — you can switch models anytime in Settings."`

- [ ] **Step 1: Append the failing tests.** In `OnboardingLogicTest.kt`, insert before the final closing brace of the class (after `the_chip_text_counts_honestly`):

```kotlin

    // ---------------------------------------------------------------- engines chooser (3.5.0)

    @Test fun no_preselection_means_the_download_action_starts_disabled() {
        // Owner decision: the user must make an informed pick — the disabled Download button is
        // what forces it.
        val action = OnboardingLogic.enginesPrimaryAction(
            downloadsBegun = false, tierPicked = false, speechReady = false, speechFailed = false,
        )
        assertEquals("Download", action.label)
        assertFalse(action.enabled)
        assertTrue(action.startsDownloads)
    }

    @Test fun picking_a_tier_is_all_it_takes_to_unlock_download() {
        val action = OnboardingLogic.enginesPrimaryAction(
            downloadsBegun = false, tierPicked = true, speechReady = false, speechFailed = false,
        )
        assertTrue(action.enabled)
        assertTrue(action.startsDownloads)
    }

    @Test fun once_downloads_begin_the_action_is_continue_with_the_unchanged_gating() {
        // One pick, then no buttons: after the confirm the footer is the OLD Continue — gated
        // while speech works, unlocked by Ready or Failed (never wedge), never by the voice.
        val working = OnboardingLogic.enginesPrimaryAction(
            downloadsBegun = true, tierPicked = true, speechReady = false, speechFailed = false,
        )
        assertEquals("Continue", working.label)
        assertFalse(working.enabled)
        assertFalse(working.startsDownloads)
        assertTrue(
            OnboardingLogic.enginesPrimaryAction(
                downloadsBegun = true, tierPicked = true, speechReady = true, speechFailed = false,
            ).enabled
        )
        assertTrue(
            OnboardingLogic.enginesPrimaryAction(
                downloadsBegun = true, tierPicked = true, speechReady = false, speechFailed = true,
            ).enabled
        )
    }

    @Test fun the_switch_anytime_hint_is_pinned_exactly() {
        // Spec A3: plants the switching habit and lowers the stakes of the forced choice.
        assertEquals(
            "Not sure? Pick one — you can switch models anytime in Settings.",
            OnboardingLogic.TIER_SWITCH_HINT,
        )
    }
```

- [ ] **Step 2: Run the test class — expect FAIL.** `$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon --tests "com.whispereverywhere.ui.onboarding.OnboardingLogicTest"` — expect `BUILD FAILED` on `:app:compileDebugUnitTestKotlin` with `Unresolved reference 'enginesPrimaryAction'` (and `'TIER_SWITCH_HINT'`).
- [ ] **Step 3: Implement.** In `OnboardingLogic.kt`, insert after the closing brace of `enginesContinueHint` (before the `missingBubblePermissions` kdoc):

```kotlin

    /** The chooser hint under the model cards (spec A3) — plants the switching habit. */
    const val TIER_SWITCH_HINT = "Not sure? Pick one — you can switch models anytime in Settings."

    /** The engines step's single primary action: Download until downloads begin, then Continue. */
    data class EnginesAction(val label: String, val enabled: Boolean, val startsDownloads: Boolean)

    /**
     * One pick, then no buttons (3.5.0 evolution of the 2026-08-01 owner decision): before any
     * download exists the primary action is "Download", enabled ONLY once a tier card is picked —
     * there is deliberately no preselection, so the disabled button is what forces the informed
     * choice. From the moment downloads begin the action is the old "Continue" with its
     * unchanged never-wedge gating ([enginesContinueEnabled]): speech Ready or Failed unlocks
     * it; the background voice never blocks.
     */
    fun enginesPrimaryAction(
        downloadsBegun: Boolean,
        tierPicked: Boolean,
        speechReady: Boolean,
        speechFailed: Boolean,
    ): EnginesAction =
        if (!downloadsBegun) {
            EnginesAction(label = "Download", enabled = tierPicked, startsDownloads = true)
        } else {
            EnginesAction(
                label = "Continue",
                enabled = enginesContinueEnabled(speechReady, speechFailed),
                startsDownloads = false,
            )
        }
```

- [ ] **Step 4: Run the test class — expect PASS.** `$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon --tests "com.whispereverywhere.ui.onboarding.OnboardingLogicTest"` — expect `BUILD SUCCESSFUL`, all 12 tests (8 existing + 4 new) passing.
- [ ] **Step 5: Run the full JVM suite.** `$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon` — expect `BUILD SUCCESSFUL`.
- [ ] **Step 6: Commit.** `git add app/src/main/java/com/whispereverywhere/ui/onboarding/OnboardingLogic.kt app/src/test/java/com/whispereverywhere/ui/onboarding/OnboardingLogicTest.kt` then `git commit -m "feat(onboarding): engines-step primary action — a pick gates Download, then the old Continue gating" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`nClaude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT"`

---

### Task A3: ensureSpeech downloads the selected tier; ONBOARDING_MODEL_ID deleted

**Files:**
- Modify: `app/src/main/java/com/whispereverywhere/ui/onboarding/OnboardingSetupViewModel.kt` (class kdoc; `beginAutoSetup` kdoc; `ensureSpeech` kdoc + resolution block; `companion object`)

**Interfaces:**
- Consumes: `WhisperCatalog.byId(id: String?): WhisperModel?`, `WhisperCatalog.DEFAULT_MODEL_ID` ( = `"eco"`, never retired), `prefs.selectedModelId: String?` (PreferencesManager), `whisperManager.isInstalled(model)` — all existing.
- Produces (dictated shared contract; Task A4 and Home's missing-engine row rely on it): `ensureSpeech()` resolves `WhisperCatalog.byId(prefs.selectedModelId) ?: WhisperCatalog.byId(WhisperCatalog.DEFAULT_MODEL_ID)!!`; the onboarding pick writes `prefs.selectedModelId` BEFORE `beginAutoSetup()`. `ONBOARDING_MODEL_ID` no longer exists (grep-verified: its only references are inside this file). All idempotence/retry/disk-truth semantics unchanged.

This is Android ViewModel wiring — untestable by house convention: exact diff → compile → full JVM suite → commit. Interim behavior note: until Task A4 lands, the engines step's `LaunchedEffect` still auto-starts `beginAutoSetup()`, which now downloads the default (`eco`) for a fresh profile — compiles and stays green; A4 replaces the auto-start with the chooser.

- [ ] **Step 1: Rewrite the class kdoc's first paragraph.** Replace (lines 17–21):

```kotlin
 * Drives the guided-onboarding AUTOMATIC engine setup: the Base multilingual speech model and the
 * on-device read-aloud voice, downloaded side by side with no button presses (owner decision
 * 2026-08-01 — "the user just doesn't have to press the buttons and they just automatically
 * happen"). Distinct from [ModelDownloadViewModel], which stays the manual per-tier picker behind
 * Home's setup banner.
```

with:

```kotlin
 * Drives the guided-onboarding engine setup: the USER-PICKED speech tier and the on-device
 * read-aloud voice, downloaded side by side. Contract since 3.5.0: ONE PICK, THEN NO BUTTONS —
 * the engines step shows the four tier cards with no preselection, the pick writes
 * prefs.selectedModelId, and [beginAutoSetup] then drives both downloads to completion with
 * nothing further to press (evolving the 2026-08-01 "no button presses" owner decision: the
 * no-buttons promise now starts one informed tap later). Distinct from [ModelDownloadViewModel],
 * which stays the manual per-tier picker behind Home's setup banner.
```

- [ ] **Step 2: Rewrite the idempotence paragraph.** Replace (lines 32–34):

```kotlin
 * Idempotence: [beginAutoSetup] is called from a LaunchedEffect on the engines step and does
 * nothing when already running or already installed — re-entering the step (back/forward, process
 * of granting permissions in Settings and returning) never restarts a download.
```

with:

```kotlin
 * Idempotence: [beginAutoSetup] is called from the engines step's single confirm action — never
 * before the pick has been persisted — and does nothing when already running or already
 * installed; re-entering the step (back/forward, granting permissions in Settings and
 * returning) never restarts a download.
```

- [ ] **Step 3: Update `beginAutoSetup`'s kdoc.** Replace `/** Start both engine downloads (the onboarding engines step). Per-engine idempotent. */` with `/** Start both engine downloads — the engines step's confirm, after the pick is persisted. Per-engine idempotent. */`
- [ ] **Step 4: Rewrite `ensureSpeech`'s kdoc and resolution block.** Replace the kdoc (lines 63–74) and the function's opening through the installed short-circuit (the code from `fun ensureSpeech() {` down to the `return` before `_speechState.value = EngineState.Working(0, DOWNLOADING)`):

```kotlin
    /**
     * Make the on-device speech model exist, if it is not already installed: the CURRENTLY
     * SELECTED tier — prefs.selectedModelId, which the onboarding pick persists BEFORE calling
     * [beginAutoSetup] — falling back to [WhisperCatalog.DEFAULT_MODEL_ID] when nothing was ever
     * selected. Already-installed reports Ready without a network touch. Idempotent while
     * running; callable again from Failed, which is what Retry is.
     *
     * Serves BOTH onboarding's engines step and Home's missing-engine status row (owner request
     * 2026-08-01: "a status and download shortcuts right there, in case someone has deleted
     * them") — one activity-scoped instance, so progress started on either surface shows on
     * both, and the Home row's re-download automatically uses the user's OWN tier.
     */
    fun ensureSpeech() {
        // Only Working blocks re-entry. A Ready deliberately does NOT: Ready can go stale — the
        // activity-scoped VM outlives a trip to Settings where the user deletes the model file —
        // and the disk check below is the truth. A still-installed engine just re-reports Ready.
        if (_speechState.value is EngineState.Working) return
        // ONE source of truth: the selected tier (written by the onboarding pick before
        // beginAutoSetup, or by any later Settings switch). The fallback covers Home's
        // missing-engine row tapped on a profile with no selection on record; DEFAULT_MODEL_ID
        // is a catalog invariant (never retired), so the !! cannot fire.
        val model = WhisperCatalog.byId(prefs.selectedModelId)
            ?: WhisperCatalog.byId(WhisperCatalog.DEFAULT_MODEL_ID)!!
        if (whisperManager.isInstalled(model)) {
            // Re-assert the selection so a dangling prefs state self-heals to the installed tier.
            prefs.selectedModelId = model.id
            _speechState.value = EngineState.Ready
            return
        }
```

(the `_speechState.value = EngineState.Working(0, DOWNLOADING)` line and the whole download `launch` block, including `prefs.selectedModelId = model.id; prefs.onboardingCompleted = true` on success, stay byte-for-byte unchanged).
- [ ] **Step 5: Delete the constant.** In `companion object`, remove exactly these lines (leaving `INDETERMINATE`, `DOWNLOADING`, `VERIFYING`, `EXTRACTING`):

```kotlin
        /** The auto-setup speech tier: multilingual on every device, ~60 MB, no RAM floor. */
        const val ONBOARDING_MODEL_ID = "base"

```

- [ ] **Step 6: Compile.** `$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:assembleDebug --no-daemon` — expect `BUILD SUCCESSFUL` (no remaining `ONBOARDING_MODEL_ID` references exist anywhere — verified by grep at plan time).
- [ ] **Step 7: Run the full JVM suite.** `$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon` — expect `BUILD SUCCESSFUL`.
- [ ] **Step 8: Commit.** `git add app/src/main/java/com/whispereverywhere/ui/onboarding/OnboardingSetupViewModel.kt` then `git commit -m "feat(onboarding): ensureSpeech downloads the selected tier — ONBOARDING_MODEL_ID deleted" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`nClaude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT"`

---

### Task A4: The four-card chooser on the engines step

**Files:**
- Modify: `app/src/main/java/com/whispereverywhere/ui/screens/OnboardingFlowScreen.kt` (imports; file-header comment; `OnboardingFlowScreen` — pick state, `EnginesStep` call site, pinned footer; `EnginesStep`; new `TierChoiceCard`)

**Interfaces:**
- Consumes: `ModelTierCopy.forId(id): TierCopy?` (Task A1); `OnboardingLogic.enginesPrimaryAction(...): EnginesAction`, `OnboardingLogic.TIER_SWITCH_HINT` (Task A2); `ensureSpeech`/`beginAutoSetup` selected-tier contract (Task A3); existing `WhisperCatalog.pickable`, `WhisperEverywhereApp.getInstance().preferencesManager.selectedModelId`, `formatBytes(Long): String`, `EngineRow(title, subtitle, state, onRetry)` (unchanged private composable in this file).
- Produces: the engines step UI: chooser phase (speechState is `Pending`) → download phase (anything else). No public API.

- [ ] **Step 1: Add imports.** In the import block, after `import com.whispereverywhere.service.WhisperAccessibilityService` (line 31) region, add:

```kotlin
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import com.whispereverywhere.WhisperEverywhereApp
import com.whispereverywhere.model.ModelTierCopy
import com.whispereverywhere.model.WhisperCatalog
import com.whispereverywhere.model.WhisperModel
import com.whispereverywhere.util.formatBytes
```

(place the two `androidx.compose.foundation.*` lines with the existing foundation imports after `import androidx.compose.foundation.clickable`, and the `com.whispereverywhere.*` lines alphabetically before `import com.whispereverywhere.service.MediaNotificationListener`).
- [ ] **Step 2: Update the file-header comment.** Replace (lines 39–43):

```kotlin
// Guided first-run onboarding (owner decision 2026-08-01): everything the app needs, configured
// in one pass on first startup. Three steps — permissions (all four, granted in place), engines
// (the Base multilingual model + the read-aloud voice, downloaded AUTOMATICALLY with no button
// presses), and optional cloud provider keys. Replaces the two-path chooser: the chooser made
// setup a fork; this makes it a walk, and the cloud fork is simply the last step.
```

with:

```kotlin
// Guided first-run onboarding (owner decision 2026-08-01): everything the app needs, configured
// in one pass on first startup. Three steps — permissions (all four, granted in place), engines
// (3.5.0: the user PICKS one of four speech tiers from honest cards — no preselection — and that
// single confirmed pick starts BOTH downloads, chosen tier + read-aloud voice, with no further
// button presses), and the cloud-keys teaching step. Replaces the two-path chooser: the chooser
// made setup a fork; this makes it a walk, and the cloud fork is simply the last step.
```

- [ ] **Step 3: Hoist the pick state.** In `OnboardingFlowScreen`, immediately after `var step by remember { mutableStateOf(Step.PERMISSIONS) }`, add:

```kotlin
    // The chooser's transient pick (3.5.0). Deliberately NOT persisted until the confirm tap:
    // prefs.selectedModelId is written the moment Download is pressed, never before.
    var pickedTierId by remember { mutableStateOf<String?>(null) }
```

- [ ] **Step 4: Rewire the step call site.** In the `when (step)` block inside the scrolling column, replace `Step.ENGINES -> EnginesStep(setupVm)` with:

```kotlin
                    Step.ENGINES -> EnginesStep(
                        vm = setupVm,
                        pickedTierId = pickedTierId,
                        onPick = { pickedTierId = it },
                    )
```

- [ ] **Step 5: Rewrite the pinned footer.** Replace the whole `if (step != Step.CLOUD) { ... }` block — from the `if (step != Step.CLOUD) {` line itself (line 114) through the `Button(...) { Text("Continue") }` closing brace (line 144). The replacement below re-emits the `if` wrapper, so the `if` line is part of what is replaced (do NOT keep the old `if` and paste this inside it):

```kotlin
            if (step != Step.CLOUD) {
                val speech by setupVm.speechState.collectAsState()
                val voice by setupVm.voiceState.collectAsState()
                if (step == Step.PERMISSIONS) {
                    Button(
                        onClick = { OnboardingLogic.next(step)?.let { step = it } },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Continue")
                    }
                } else {
                    // ENGINES: one primary action — "Download" (gated on a pick) until downloads
                    // begin, then the old "Continue" with its unchanged never-wedge gating.
                    val action = OnboardingLogic.enginesPrimaryAction(
                        downloadsBegun = speech !is EngineState.Pending,
                        tierPicked = pickedTierId != null,
                        speechReady = speech is EngineState.Ready,
                        speechFailed = speech is EngineState.Failed,
                    )
                    if (!action.startsDownloads) {
                        OnboardingLogic.enginesContinueHint(
                            speechReady = speech is EngineState.Ready,
                            voiceReady = voice is EngineState.Ready,
                        )?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                    Button(
                        onClick = {
                            if (action.startsDownloads) {
                                pickedTierId?.let { picked ->
                                    // Contract: the pick is persisted BEFORE beginAutoSetup so
                                    // ensureSpeech resolves it as the one source of truth.
                                    WhisperEverywhereApp.getInstance()
                                        .preferencesManager.selectedModelId = picked
                                    setupVm.beginAutoSetup()
                                }
                            } else {
                                OnboardingLogic.next(step)?.let { step = it }
                            }
                        },
                        enabled = action.enabled,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(action.label)
                    }
                }
            }
```

- [ ] **Step 6: Rewrite `EnginesStep` and add `TierChoiceCard`.** Replace the `EnginesStep` kdoc and composable (from the `/**` above `private fun EnginesStep` through its closing brace — the `LaunchedEffect(Unit) { vm.beginAutoSetup() }` auto-start dies here) with:

```kotlin
/**
 * The model-choice step (3.5.0): four tier cards from [ModelTierCopy], no preselection, one pick.
 * Until downloads begin it renders the chooser; from the first beginAutoSetup() the SAME step
 * renders the two progress rows and nothing further needs pressing. Re-entering the step after
 * the confirm shows progress, never the chooser again — the activity-scoped VM's speechState
 * (Pending = not yet begun) is the phase truth.
 */
@Composable
private fun EnginesStep(
    vm: OnboardingSetupViewModel,
    pickedTierId: String?,
    onPick: (String) -> Unit,
) {
    val speech by vm.speechState.collectAsState()
    val voice by vm.voiceState.collectAsState()

    if (speech is EngineState.Pending) {
        // ---- choose phase: nothing downloads until the user has made an informed pick.
        Text(
            "Pick your speech model — dictation runs on your phone, and audio never has to " +
                "leave it. The read-aloud voice (about 365 MB) downloads alongside whichever " +
                "model you choose.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        WhisperCatalog.pickable.forEach { model ->
            TierChoiceCard(
                model = model,
                copy = ModelTierCopy.forId(model.id),
                selected = pickedTierId == model.id,
                onClick = { onPick(model.id) },
            )
            Spacer(Modifier.height(12.dp))
        }
        Text(
            OnboardingLogic.TIER_SWITCH_HINT,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        // ---- download phase: entered by the one confirmed pick; nothing further to press.
        val chosen = WhisperCatalog.byId(
            WhisperEverywhereApp.getInstance().preferencesManager.selectedModelId
        ) ?: WhisperCatalog.byId(WhisperCatalog.DEFAULT_MODEL_ID)!!
        Text(
            "Downloading your engines — nothing to press. Both stay on your phone; audio " +
                "never has to leave it.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        EngineRow(
            title = "Speech model — ${chosen.displayName}",
            subtitle = "Transcribes your dictation on-device (${formatBytes(chosen.approxBytes)})",
            state = speech,
            onRetry = { vm.ensureSpeech() },
        )
        Spacer(Modifier.height(12.dp))
        EngineRow(
            title = "Read-aloud voice",
            subtitle = "Speaks text aloud on-device (about 365 MB)",
            state = voice,
            onRetry = { vm.ensureVoice() },
        )
    }
}

/** One selectable tier card rendering [ModelTierCopy] — the same copy Settings' picker shows. */
@Composable
private fun TierChoiceCard(
    model: WhisperModel,
    copy: ModelTierCopy.TierCopy?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = if (selected) Primary.copy(alpha = 0.08f)
            else MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) Primary else MaterialTheme.colorScheme.outline,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (selected) 2.dp else 1.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        copy?.headline ?: model.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        model.displayName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                if (selected) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = "Selected", tint = Primary)
                }
            }
            copy?.let { c ->
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    c.badges.forEach { badge ->
                        Surface(
                            color = Primary.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Text(
                                badge,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = Primary,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    c.body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
```

- [ ] **Step 7: Compile.** `$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:assembleDebug --no-daemon` — expect `BUILD SUCCESSFUL`.
- [ ] **Step 8: Run the full JVM suite.** `$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon` — expect `BUILD SUCCESSFUL`.
- [ ] **Step 9: Commit.** `git add app/src/main/java/com/whispereverywhere/ui/screens/OnboardingFlowScreen.kt` then `git commit -m "feat(onboarding): four-card model chooser — no preselection, one pick starts both downloads" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`nClaude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT"`

---

### Task A5: The cloud-keys step becomes a teaching card

**Files:**
- Modify: `app/src/main/java/com/whispereverywhere/ui/screens/OnboardingFlowScreen.kt` (`CloudStep` composable only — same navigation destination, copy reworked in place per spec A3)

**Interfaces:**
- Consumes: existing private `ChoiceCard(icon, title, subtitle, onClick)` in the same file; existing `onCloudSetup`/`onFinish` lambdas (no navigation changes).
- Produces: nothing consumed by later tasks.

Copy contract (spec A3, checked in substance): own API key · the four providers by name · top accuracy + widest language coverage · billed to the user's own provider account · entirely optional · on-device always works and remains the default · NO speed claims (the old copy's "real-time streaming" line is retired with it).

- [ ] **Step 1: Rewrite `CloudStep`.** Replace the whole `CloudStep` composable and its kdoc (from `/** The final fork, as two full cards — the same idiom as the old chooser, in its rightful place. */` through the composable's closing brace) with:

```kotlin
/**
 * The teaching card (3.5.0, spec A3): before this step existed a user could finish onboarding
 * never learning cloud keys exist. Copy contract — own API key, the four providers by name, top
 * accuracy + widest language coverage, billed to the USER's provider account, entirely optional,
 * on-device always works and remains the default. NO speed claims: the old copy's "real-time
 * streaming" hook was retired with it.
 */
@Composable
private fun CloudStep(onCloudSetup: () -> Unit, onFinish: () -> Unit) {
    Text(
        "One more thing worth knowing: you can plug in your own API key from OpenAI, " +
            "Google Gemini, ElevenLabs, or Soniox. The big cloud models offer top accuracy " +
            "and the widest language coverage, and usage is billed to your own provider " +
            "account at the provider's rates. It's entirely optional — the on-device model " +
            "always works and remains the default.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(20.dp))

    ChoiceCard(
        icon = Icons.Filled.CloudQueue,
        title = "Set up cloud providers",
        subtitle = "Bring your own keys — top accuracy and the widest language coverage, " +
            "billed to your own accounts.",
        onClick = onCloudSetup,
    )
    Spacer(Modifier.height(16.dp))
    ChoiceCard(
        icon = Icons.Filled.PhoneAndroid,
        title = "Finish — on-device only",
        subtitle = "Free and private. Everything runs on your phone; add keys anytime in " +
            "Engines & voices.",
        onClick = onFinish,
    )
}
```

- [ ] **Step 2: Compile.** `$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:assembleDebug --no-daemon` — expect `BUILD SUCCESSFUL`.
- [ ] **Step 3: Run the full JVM suite.** `$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon` — expect `BUILD SUCCESSFUL` (`HowToGuideTest` untouched and green — this change touches no marketing surface).
- [ ] **Step 4: Commit.** `git add app/src/main/java/com/whispereverywhere/ui/screens/OnboardingFlowScreen.kt` then `git commit -m "feat(onboarding): cloud-keys step is now a teaching card — own key, four providers, no speed claims" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`nClaude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT"`

---

### Task A6: Settings manual picker renders ModelTierCopy

**Files:**
- Modify: `app/src/main/java/com/whispereverywhere/ui/screens/OnboardingModelScreen.kt` (imports; `ModelTierCard` — the scope row is replaced by ModelTierCopy rendering; new `CopyBadge` composable)

**Interfaces:**
- Consumes: `ModelTierCopy.forId(id): TierCopy?` (Task A1); everything else already in the file (`WhisperCatalog.pickable`, `TierBadge`, `formatBytes`).
- Produces: nothing consumed by later tasks — this closes Workstream A (both surfaces now read one source of truth).

- [ ] **Step 1: Add the import.** After `import com.whispereverywhere.model.ModelScope` add:

```kotlin
import com.whispereverywhere.model.ModelTierCopy
```

- [ ] **Step 2: Render the copy in `ModelTierCard`.** In `ModelTierCard`, first add the lookup at the top of the function body, directly after the parameter list's opening brace (before `val downloading = state as? DownloadState.Downloading`):

```kotlin
    // 3.5.0: same source of truth as the onboarding chooser (ModelTierCopy) — the headline takes
    // the speed-vs-accuracy position, the badges make language coverage impossible to miss, the
    // body is the honest one-liner. Null only for a tier without copy (ModelTierCopyTest pins
    // that every pickable tier has some), which falls back to the old catalog-scope row.
    val copy = ModelTierCopy.forId(model.id)
```

Then replace the scope row — the block from `// Scope row` through the closing brace of its `Row(verticalAlignment = Alignment.CenterVertically) { ... }` (the one containing `Icons.Filled.Language` and the `when (model.scope)` text) — with:

```kotlin
            if (copy != null) {
                Text(
                    text = copy.headline,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Primary,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    copy.badges.forEach { badge -> CopyBadge(text = badge) }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = copy.body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Language,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = when (model.scope) {
                            ModelScope.ENGLISH -> "English only"
                            ModelScope.MULTILINGUAL -> "Multilingual (99 languages)"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
```

- [ ] **Step 3: Add the badge composable.** After the closing brace of the existing `TierBadge` composable at the end of the file, add:

```kotlin

/** A plain copy chip (no icon) for ModelTierCopy badges — language coverage and size. */
@Composable
private fun CopyBadge(text: String) {
    Surface(
        color = Primary.copy(alpha = 0.12f),
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = Primary,
            fontWeight = FontWeight.Bold
        )
    }
}
```

- [ ] **Step 4: Compile.** `$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:assembleDebug --no-daemon` — expect `BUILD SUCCESSFUL`.
- [ ] **Step 5: Run the full JVM suite.** `$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon` — expect `BUILD SUCCESSFUL`.
- [ ] **Step 6: Commit.** `git add app/src/main/java/com/whispereverywhere/ui/screens/OnboardingModelScreen.kt` then `git commit -m "feat(settings): manual model picker renders ModelTierCopy — same copy as onboarding" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`nClaude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT"`

---

### Task B1: CloudKeyNote — pure copy + visibility predicate (TDD)
**Files:**
- Create: `app/src/main/java/com/whispereverywhere/ui/CloudKeyNote.kt`
- Test: `app/src/test/java/com/whispereverywhere/ui/CloudKeyNoteTest.kt`

**Interfaces:**
- Consumes: nothing (pure, Android-free; pattern: `HowToGuide` in the same package).
- Produces: `object CloudKeyNote { const val HEADLINE: String; const val BODY: String; const val BUTTON: String; fun shouldShow(cloudProviderConfigured: Boolean, dismissed: Boolean): Boolean }` — consumed by Task B3's Home wiring.

- [ ] **Step 1: Write the failing test** — create `app/src/test/java/com/whispereverywhere/ui/CloudKeyNoteTest.kt`:
```kotlin
package com.whispereverywhere.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudKeyNoteTest {

    // ------------------------------------------------------------------ visibility truth table
    // The full configured × dismissed table — the note shows in exactly ONE of the four cells.

    @Test fun shows_only_while_unconfigured_and_undismissed() {
        assertTrue(CloudKeyNote.shouldShow(cloudProviderConfigured = false, dismissed = false))
    }

    @Test fun dismissal_hides_it() {
        assertFalse(CloudKeyNote.shouldShow(cloudProviderConfigured = false, dismissed = true))
    }

    @Test fun a_configured_cloud_provider_hides_it_even_when_never_dismissed() {
        // "Configuring a cloud key hides it permanently regardless of dismissal" (spec B).
        assertFalse(CloudKeyNote.shouldShow(cloudProviderConfigured = true, dismissed = false))
    }

    @Test fun configured_and_dismissed_hides_it() {
        assertFalse(CloudKeyNote.shouldShow(cloudProviderConfigured = true, dismissed = true))
    }

    // ------------------------------------------------------------------ copy discipline
    // The discipline surface for this card: an accuracy/language pitch is allowed, a speed
    // claim is not (owner decision, same rule that pins HowToGuide and the listing copy).

    @Test fun the_card_copy_makes_no_speed_claims() {
        val text = (CloudKeyNote.HEADLINE + " " + CloudKeyNote.BODY + " " + CloudKeyNote.BUTTON)
            .lowercase()
        listOf("faster", "fastest", "quicker", "instant", "speed").forEach { banned ->
            assertFalse("cloud-key note contains banned speed word: $banned", text.contains(banned))
        }
    }

    @Test fun the_copy_is_the_owner_approved_text_verbatim() {
        assertEquals("Want top accuracy or more languages?", CloudKeyNote.HEADLINE)
        assertEquals(
            "Add your own API key — large cloud models from OpenAI, Gemini, ElevenLabs, " +
                "or Soniox, billed to your own account.",
            CloudKeyNote.BODY
        )
        assertEquals("Open Engines & voices", CloudKeyNote.BUTTON)
    }

    @Test fun the_billing_truth_is_present() {
        // Cloud is always the user's own account, never ours — same invariant HowToGuideTest pins.
        assertTrue(CloudKeyNote.BODY.lowercase().contains("billed to your own account"))
    }
}
```
- [ ] **Step 2: Run the test class — expect FAIL** (the test source cannot compile: `CloudKeyNote` does not exist yet, so `:app:compileDebugUnitTestKotlin` fails with `Unresolved reference: CloudKeyNote`):
```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon --tests "com.whispereverywhere.ui.CloudKeyNoteTest"
```
Expected outcome line: `BUILD FAILED` (compileDebugUnitTestKotlin: `Unresolved reference: CloudKeyNote`).
- [ ] **Step 3: Write the implementation** — create `app/src/main/java/com/whispereverywhere/ui/CloudKeyNote.kt`:
```kotlin
package com.whispereverywhere.ui

/**
 * Home's dismissible cloud-key note (3.5.0, Workstream B) — ONE source of truth for both the
 * card copy and the visibility rule, kept Compose- and Android-free (pattern: [HowToGuide]) so
 * [CloudKeyNoteTest] can pin the copy discipline on the JVM: the accuracy/language pitch is
 * allowed, a speed claim is not (owner decision), and the visibility truth table is exhaustive.
 *
 * Visibility: shown only while NO cloud provider is configured/selected AND the user has not
 * dismissed it. Configuring a key hides it permanently regardless of dismissal. Dismissal is the
 * card's X, persisted as `PreferencesManager.cloudNoteDismissed` and never unset.
 */
object CloudKeyNote {

    const val HEADLINE = "Want top accuracy or more languages?"

    const val BODY =
        "Add your own API key — large cloud models from OpenAI, Gemini, ElevenLabs, " +
            "or Soniox, billed to your own account."

    const val BUTTON = "Open Engines & voices"

    /** The whole visibility rule: both gates must be open. */
    fun shouldShow(cloudProviderConfigured: Boolean, dismissed: Boolean): Boolean =
        !cloudProviderConfigured && !dismissed
}
```
- [ ] **Step 4: Run the test class — expect PASS**:
```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon --tests "com.whispereverywhere.ui.CloudKeyNoteTest"
```
Expected outcome line: `BUILD SUCCESSFUL` (7 tests, 0 failures).
- [ ] **Step 5: Commit**:
```powershell
git add app/src/main/java/com/whispereverywhere/ui/CloudKeyNote.kt app/src/test/java/com/whispereverywhere/ui/CloudKeyNoteTest.kt; git commit -m "feat(home): CloudKeyNote copy + visibility predicate, pure and JVM-pinned" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`nClaude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT"
```

### Task B2: `cloudNoteDismissed` pref (dictated shared contract)
**Files:**
- Modify: `app/src/main/java/com/whispereverywhere/data/local/PreferencesManager.kt` — plain-var block (anchor: between the `overlayPinned` var and the `selectedModelId` var) and the companion key-constant block (anchor: after `KEY_OVERLAY_PINNED`).

**Interfaces:**
- Consumes: nothing new.
- Produces: `var cloudNoteDismissed: Boolean` on `PreferencesManager` (get/set, key `"cloud_note_dismissed"`, default `false`) — consumed by Task B3's Home wiring; name and key are the dictated cross-section contract.

- [ ] **Step 1: Add the plain var** — in `PreferencesManager.kt`, directly after the `overlayPinned` property (`// Overlay pin/lock: when true the bubble cannot be accidentally dragged` block) and before `// Selected on-device whisper model tier id`, insert:
```kotlin
    // Home's cloud-key note (Workstream B): set true by the card's X, never unset in-app. The
    // note ALSO hides permanently once any provider key is configured — that gate lives in the
    // visibility predicate (CloudKeyNote.shouldShow), independent of this flag.
    var cloudNoteDismissed: Boolean
        get() = prefs.getBoolean(KEY_CLOUD_NOTE_DISMISSED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_CLOUD_NOTE_DISMISSED, value).apply()
        }
```
- [ ] **Step 2: Add the key constant** — in the same file's `companion object`, directly after `private const val KEY_OVERLAY_PINNED = "overlay_pinned"`, insert:
```kotlin
        private const val KEY_CLOUD_NOTE_DISMISSED = "cloud_note_dismissed"
```
- [ ] **Step 3: Compile**:
```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:assembleDebug --no-daemon
```
Expected outcome line: `BUILD SUCCESSFUL`.
- [ ] **Step 4: Full JVM suite** (no PreferencesManager JVM test exists — Context-backed prefs are untestable by house convention; the suite guards everything else):
```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon
```
Expected outcome line: `BUILD SUCCESSFUL`.
- [ ] **Step 5: Commit**:
```powershell
git add app/src/main/java/com/whispereverywhere/data/local/PreferencesManager.kt; git commit -m "feat(prefs): cloudNoteDismissed flag for Home's cloud-key note" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`nClaude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT"
```

### Task B3: The dismissible card on Home
**Files:**
- Modify: `app/src/main/java/com/whispereverywhere/ui/screens/HomeScreen.kt` — inside `fun HomeScreen(...)` (anchor: between the end of the `when (setupBannerState(...))` block and the `// Mode cards` comment), plus a new private composable `CloudKeyNoteCard` (anchor: inserted directly after the `SetupBannerTwoPath` composable, before `fun MainControlButton`).

**Interfaces:**
- Consumes: `CloudKeyNote.shouldShow(cloudProviderConfigured: Boolean, dismissed: Boolean): Boolean`, `CloudKeyNote.HEADLINE/BODY/BUTTON` (Task B1); `PreferencesManager.cloudNoteDismissed: Boolean` (Task B2); HomeScreen's existing `hasAnyKey: Boolean` produceState, `sttProviderId: String?` flow value, and the existing `onNavigateToEnginesVoices: () -> Unit` route param (the same route the setup banner's "Bring your own key" already uses).
- Produces: nothing consumed by later tasks (terminal UI wiring).

- [ ] **Step 1: Wire the card into `HomeScreen`** — in `HomeScreen.kt`, find the end of the setup-guidance block inside `fun HomeScreen` and the mode-cards comment:
```kotlin
                SetupBanner.NONE -> Unit
            }

            // Mode cards — each shows its live configuration as a status chip and taps into settings.
```
and insert between them:
```kotlin
                SetupBanner.NONE -> Unit
            }

            // Cloud-key note (3.5.0, Workstream B): a dismissible nudge that better accuracy and
            // wider language coverage exist behind the user's own API key. Visibility is the pure
            // CloudKeyNote.shouldShow truth table: any configured provider key OR a selected cloud
            // STT engine hides it permanently, independent of the persisted X. The local mirror of
            // cloudNoteDismissed follows house convention for plain-var prefs read in composition
            // (see EnginesAndVoicesScreen's sttProviderId remember).
            var cloudNoteDismissed by remember {
                mutableStateOf(app.preferencesManager.cloudNoteDismissed)
            }
            if (com.whispereverywhere.ui.CloudKeyNote.shouldShow(
                    cloudProviderConfigured = hasAnyKey || sttProviderId != null,
                    dismissed = cloudNoteDismissed,
                )
            ) {
                CloudKeyNoteCard(
                    onOpenEnginesVoices = onNavigateToEnginesVoices,
                    onDismiss = {
                        app.preferencesManager.cloudNoteDismissed = true
                        cloudNoteDismissed = true
                    },
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Mode cards — each shows its live configuration as a status chip and taps into settings.
```
- [ ] **Step 2: Add the card composable** — in the same file, directly after the closing brace of `private fun SetupBannerTwoPath(...)` (before `fun MainControlButton`), insert:
```kotlin
/**
 * The dismissible cloud-key note. All copy comes verbatim from [com.whispereverywhere.ui.CloudKeyNote]
 * (the JVM-pinned discipline surface); this shell only lays it out. The X persists dismissal via
 * [onDismiss]; the button rides Home's existing Engines & voices route. Untested UI by house
 * convention — the visibility logic lives in CloudKeyNote.shouldShow, which is.
 */
@Composable
private fun CloudKeyNoteCard(
    onOpenEnginesVoices: () -> Unit,
    onDismiss: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = Primary.copy(alpha = 0.08f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = com.whispereverywhere.ui.CloudKeyNote.HEADLINE,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Dismiss",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = com.whispereverywhere.ui.CloudKeyNote.BODY,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                onClick = onOpenEnginesVoices,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(com.whispereverywhere.ui.CloudKeyNote.BUTTON)
            }
        }
    }
}
```
(No new imports needed: `Icons.Filled.Close` arrives via the existing `androidx.compose.material.icons.filled.*` wildcard; `Primary` via `com.whispereverywhere.ui.theme.*`.)
- [ ] **Step 3: Compile**:
```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:assembleDebug --no-daemon
```
Expected outcome line: `BUILD SUCCESSFUL`.
- [ ] **Step 4: Full JVM suite**:
```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon
```
Expected outcome line: `BUILD SUCCESSFUL`.
- [ ] **Step 5: Commit**:
```powershell
git add app/src/main/java/com/whispereverywhere/ui/screens/HomeScreen.kt; git commit -m "feat(home): dismissible cloud-key note card wired to Engines & voices" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`nClaude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT"
```
- [ ] **Step 6: Record the owner on-device check (owner-run — NEVER `:app:installDebug`)**: owner installs via `adb.exe install -r C:\Users\bastr\.androidbuild\WhisperEverywhere\app\outputs\apk\debug\app-debug.apk` (build outputs are redirected OUTSIDE the repo — see Global Constraints) and verifies, per the spec-D checklist: (a) with no key configured the note shows on Home; (b) its button opens Engines & voices; (c) the X hides it immediately AND it stays hidden after force-stop + relaunch; (d) on a profile with a provider key configured (or after adding one) the note never shows even when it was never dismissed. No executor action beyond confirming this item is on the owner checklist for 3.5.0.

---

### Task C1: WE-DIAG finalize-timing instrumentation across the stop path

**Files:**
- Modify: `app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt` — inside `stopRecording()` (the stop-tap prologue, the tail `transcriptionEngine?.commit()`, and the finalize coroutine's flush/delivery block)
- Modify: `app/src/main/java/com/whispereverywhere/transcription/cloud/CloudTranscriptionEngine.kt` — `awaitIdle`
- Modify: `app/src/main/java/com/whispereverywhere/transcription/live/LiveTranscriptionEngine.kt` — `awaitIdle`
- Modify: `app/src/main/java/com/whispereverywhere/transcription/LocalWhisperEngine.kt` — `awaitIdle`
- Test: none new (log-only; existing JVM suite guards the untouched semantics; `unitTests.isReturnDefaultValues = true` makes `android.util.Log` a no-op under test)

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: the permanent log-line contract `"finalize-timing: <phase>=<elapsed>ms"` on tag `WE-DIAG`, phases `commit-dispatch`, `cloud-drain`, `local-drain`, `orderer-flush`, `delivery`, `total` — `total` is logged immediately before the IDLE transition, so it also covers the teardown/stats/history-persist span after delivery. Task C3's skip line reuses the exact `local-drain` phase name. Verified by grep: `awaitIdle` is called in production ONLY from `stopRecording`'s finalize coroutine (`FloatingBubbleService.kt:2243`), so each phase line fires exactly once per stop. Placement rationale: the cloud/local split lives INSIDE the engines' own `awaitIdle` (the service sees only the combined `FallbackTranscriptionEngine.awaitIdle`), and because each engine self-logs, a local-only session emits `local-drain`, a batch/live session emits `cloud-drain` then `local-drain`, with no duplicate lines — `FallbackTranscriptionEngine` itself logs nothing in this task.

- [ ] **Step 1: Capture the stop-tap timestamp and log `commit-dispatch` in `stopRecording()`.** Two edits. First, at the function's opening:
  ```kotlin
  // OLD (FloatingBubbleService.kt, stopRecording, first lines)
      private fun stopRecording() {
          android.util.Log.i("WE-DIAG", "stopRecording: state=$currentState")
  ```
  ```kotlin
  // NEW
      private fun stopRecording() {
          // C1 finalize-timing: every phase of the stop path is measured from this instant.
          // Permanent diagnosis capability (spec 2026-08-18 C1) — grep "finalize-timing:".
          val stopTapNs = System.nanoTime()
          android.util.Log.i("WE-DIAG", "stopRecording: state=$currentState")
  ```
  Second, at the tail commit (same function). The OLD block includes the flush comment on purpose: the bare `commit()`/`reset()` pair also appears in `switchSource`, and this comment is unique to `stopRecording` — anchor on the full block, not the pair:
  ```kotlin
  // OLD
          // Flush whatever is buffered, UNCONDITIONALLY. The amplitude segmenter misses quiet
          // speech below its fixed thresholds — gating this flush on hasPendingSpeech() silently
          // discarded whole sessions for soft talkers ("No speech detected" despite real speech).
          // The native Silero VAD inside whisper_full now makes the unconditional flush safe: a
          // silence-only tail is trimmed to nothing and returns empty, fast, with no junk tokens.
          transcriptionEngine?.commit()
          speechSegmenter.reset()
  ```
  ```kotlin
  // NEW
          // Flush whatever is buffered, UNCONDITIONALLY. The amplitude segmenter misses quiet
          // speech below its fixed thresholds — gating this flush on hasPendingSpeech() silently
          // discarded whole sessions for soft talkers ("No speech detected" despite real speech).
          // The native Silero VAD inside whisper_full now makes the unconditional flush safe: a
          // silence-only tail is trimmed to nothing and returns empty, fast, with no junk tokens.
          transcriptionEngine?.commit()
          android.util.Log.i(
              "WE-DIAG",
              "finalize-timing: commit-dispatch=${(System.nanoTime() - stopTapNs) / 1_000_000}ms",
          )
          speechSegmenter.reset()
  ```
- [ ] **Step 2: Log `orderer-flush` around the post-drain flush in the finalize coroutine.** Inside the `serviceScope.launch(Dispatchers.Main)` block of `stopRecording()`:
  ```kotlin
  // OLD
              // Release anything the orderer is still holding, BEFORE the final delivery reads
              // the sink — held text's only exit is flush(), and the pile is largest exactly
              // here, at the end of a session. (Provably empty for the on-device engine, which
              // resolves in order.)
              deliverReleasedText(segmentOrderer.flush().text)
  ```
  ```kotlin
  // NEW
              // Release anything the orderer is still holding, BEFORE the final delivery reads
              // the sink — held text's only exit is flush(), and the pile is largest exactly
              // here, at the end of a session. (Provably empty for the on-device engine, which
              // resolves in order.)
              val flushStartNs = System.nanoTime()
              deliverReleasedText(segmentOrderer.flush().text)
              android.util.Log.i(
                  "WE-DIAG",
                  "finalize-timing: orderer-flush=${(System.nanoTime() - flushStartNs) / 1_000_000}ms",
              )
  ```
- [ ] **Step 3: Log `delivery` and `total` around the single external write.** Same coroutine, two edits. Start the delivery clock where the sink is detached:
  ```kotlin
  // OLD
              val finishedSink = transcriptSink
              transcriptSink = null
  ```
  ```kotlin
  // NEW
              val deliveryStartNs = System.nanoTime()
              val finishedSink = transcriptSink
              transcriptSink = null
  ```
  close the delivery clock right before teardown:
  ```kotlin
  // OLD
              teardownRealtime()
              android.util.Log.i("WE-DIAG", "finalize: state=$currentState producedText=$sessionProducedText")
  ```
  ```kotlin
  // NEW
              android.util.Log.i(
                  "WE-DIAG",
                  "finalize-timing: delivery=${(System.nanoTime() - deliveryStartNs) / 1_000_000}ms",
              )
              teardownRealtime()
              android.util.Log.i("WE-DIAG", "finalize: state=$currentState producedText=$sessionProducedText")
  ```
  and log `total` at the very end of the coroutine, immediately before the IDLE transition, so the span after delivery (teardown, the stats block, the history persist's `Dispatchers.IO` write) is never invisible to the timings — the spec's phase list runs stop-tap → IDLE:
  ```kotlin
  // OLD
              if (currentState == BubbleState.FINALIZING) {
                  // Delivery already happened above, pre-teardown, through FinalDeliveryPolicy.
                  if (!sessionProducedText) {
  ```
  ```kotlin
  // NEW
              // C1 finalize-timing: total spans stop-tap → teardown/stats/history done. Logged
              // OUTSIDE the FINALIZING guard so every exit of the finalize coroutine reports it;
              // total minus (commit-dispatch + drains + orderer-flush + delivery) exposes the
              // teardown/stats/history span without needing its own phase name.
              android.util.Log.i(
                  "WE-DIAG",
                  "finalize-timing: total=${(System.nanoTime() - stopTapNs) / 1_000_000}ms",
              )
              if (currentState == BubbleState.FINALIZING) {
                  // Delivery already happened above, pre-teardown, through FinalDeliveryPolicy.
                  if (!sessionProducedText) {
  ```
- [ ] **Step 4: Log `cloud-drain` in `CloudTranscriptionEngine.awaitIdle`.** Replace the body:
  ```kotlin
  // OLD (CloudTranscriptionEngine.kt, awaitIdle)
      override fun awaitIdle(timeoutMs: Long): Boolean = runBlocking {
          withTimeoutOrNull(timeoutMs) {
              while (pending.isNotEmpty()) {
                  pendingSeqs().forEach { pending[it]?.job?.join() }
                  // A job whose seq is resolved by the completion handler rather than by its own
                  // body can be complete a moment before [pending] loses the entry; yield instead of
                  // spinning on it. Bounded by the enclosing timeout either way.
                  if (pending.isNotEmpty()) yield()
              }
              true
          } ?: false
      }
  ```
  ```kotlin
  // NEW
      override fun awaitIdle(timeoutMs: Long): Boolean = runBlocking {
          val startNs = System.nanoTime()
          val drained = withTimeoutOrNull(timeoutMs) {
              while (pending.isNotEmpty()) {
                  pendingSeqs().forEach { pending[it]?.job?.join() }
                  // A job whose seq is resolved by the completion handler rather than by its own
                  // body can be complete a moment before [pending] loses the entry; yield instead of
                  // spinning on it. Bounded by the enclosing timeout either way.
                  if (pending.isNotEmpty()) yield()
              }
              true
          } ?: false
          // C1 finalize-timing: awaitIdle is only called from the service's finalize path, so this
          // is the stop path's cloud-drain phase (the tail segment's provider round-trip lives here).
          android.util.Log.i(TAG, "finalize-timing: cloud-drain=${(System.nanoTime() - startNs) / 1_000_000}ms")
          drained
      }
  ```
- [ ] **Step 5: Log `cloud-drain` in `LiveTranscriptionEngine.awaitIdle`** (the live engine IS the session's cloud side; same phase name):
  ```kotlin
  // OLD (LiveTranscriptionEngine.kt, awaitIdle)
      override fun awaitIdle(timeoutMs: Long): Boolean = runBlocking {
          withTimeoutOrNull(timeoutMs) {
              while (synchronized(bufferLock) { sendQueue.isNotEmpty() }) yield()
              while (synchronized(correlationLock) { pending.isNotEmpty() }) yield()
              true
          } ?: false
      }
  ```
  ```kotlin
  // NEW
      override fun awaitIdle(timeoutMs: Long): Boolean = runBlocking {
          val startNs = System.nanoTime()
          val drained = withTimeoutOrNull(timeoutMs) {
              while (synchronized(bufferLock) { sendQueue.isNotEmpty() }) yield()
              while (synchronized(correlationLock) { pending.isNotEmpty() }) yield()
              true
          } ?: false
          // C1 finalize-timing: after finishServerTurns this should be near-zero — a large value
          // here convicts the live drain (spec C2 "live path" candidate).
          android.util.Log.i(TAG, "finalize-timing: cloud-drain=${(System.nanoTime() - startNs) / 1_000_000}ms")
          drained
      }
  ```
- [ ] **Step 6: Log `local-drain` in `LocalWhisperEngine.awaitIdle`.** Restructure so both the drained and timed-out exits log (the early `RejectedExecutionException` return stays silent — engine already shut down, nothing was waited on):
  ```kotlin
  // OLD (LocalWhisperEngine.kt, awaitIdle)
      override fun awaitIdle(timeoutMs: Long): Boolean {
          val latch = java.util.concurrent.CountDownLatch(1)
          try {
              executor.execute { latch.countDown() }
          } catch (t: java.util.concurrent.RejectedExecutionException) {
              return true  // executor already shut down — nothing is in flight
          }
          return try {
              latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
          } catch (t: InterruptedException) {
              Thread.currentThread().interrupt()
              false
          }
      }
  ```
  ```kotlin
  // NEW
      override fun awaitIdle(timeoutMs: Long): Boolean {
          val startNs = System.nanoTime()
          val latch = java.util.concurrent.CountDownLatch(1)
          try {
              executor.execute { latch.countDown() }
          } catch (t: java.util.concurrent.RejectedExecutionException) {
              return true  // executor already shut down — nothing is in flight
          }
          val drained = try {
              latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
          } catch (t: InterruptedException) {
              Thread.currentThread().interrupt()
              false
          }
          // C1 finalize-timing: everything queued on the native executor ahead of the fence —
          // retries, or (first session) the safety-net model load — is paid for inside this number.
          android.util.Log.i(
              "WE-DIAG",
              "finalize-timing: local-drain=${(System.nanoTime() - startNs) / 1_000_000}ms",
          )
          return drained
      }
  ```
- [ ] **Step 7: Compile.** Run: `$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:assembleDebug --no-daemon` — expect `BUILD SUCCESSFUL` (log-only edits, no signature changes).
- [ ] **Step 8: Full JVM suite.** Run: `$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon` — expect `BUILD SUCCESSFUL` (Log is stubbed to no-op under `isReturnDefaultValues`; no assertion touches timing lines).
- [ ] **Step 9: Commit.** `git add app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt app/src/main/java/com/whispereverywhere/transcription/cloud/CloudTranscriptionEngine.kt app/src/main/java/com/whispereverywhere/transcription/live/LiveTranscriptionEngine.kt app/src/main/java/com/whispereverywhere/transcription/LocalWhisperEngine.kt` then `git commit -m "feat(diag): finalize-timing WE-DIAG phase logs across the stop path" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`nClaude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT"`

---

### Task C2: FINALIZING status line names what is being waited on

**Files:**
- Modify: `app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt` — inside `stopRecording()`, the `transcriptionDeltaText` assignment right after `updateBubbleState(BubbleState.FINALIZING)`
- Test: none (Android UI wiring — untestable by house convention)

**Interfaces:**
- Consumes: the existing field `private var cloudWrapper: FallbackTranscriptionEngine?` — non-null at stop time exactly for `CLOUD_WITH_FALLBACK` / `CLOUD_LIVE` sessions (set in `resolveTranscriptionEngine`, retired only at next-session start / onDestroy).
- Produces: the two exact user-facing strings `"Finishing… (waiting on provider)"` and `"Finishing transcript…"` (spec C2 UX layer; no speed or duration claims). Accepted approximation vs the spec's phase-scoped wording: the string is chosen ONCE at stop from `cloudWrapper != null` (session-scoped), so a cloud session whose local retry drain runs after the provider finished still reads "waiting on provider" for those extra moments. Deliberate simplification — a per-phase swap would need a callback out of the engine drain for a line that is on screen for seconds; do not "fix" this without a spec change.

- [ ] **Step 1: Swap the fixed status string for the engine-aware pair.** In `stopRecording()`:
  ```kotlin
  // OLD
          // Every session shows the closing status now (W2 unified preview) — the accumulating
          // window is up for TEXT_FIELD sessions too, and this line is its "still working" signal.
          transcriptionDeltaText.text = "Finishing transcript — last segments coming in…"
          transcriptionDeltaText.visibility = View.VISIBLE
  ```
  ```kotlin
  // NEW
          // Every session shows the closing status now (W2 unified preview) — the accumulating
          // window is up for TEXT_FIELD sessions too, and this line is its "still working" signal.
          // Cloud/live sessions name the actual wait (the tail segment's provider round-trip) so an
          // honest two-second drain never reads as a hang; cloudWrapper is non-null exactly for
          // CLOUD_WITH_FALLBACK / CLOUD_LIVE sessions and is not retired until the next session.
          transcriptionDeltaText.text = if (cloudWrapper != null) {
              "Finishing… (waiting on provider)"
          } else {
              "Finishing transcript…"
          }
          transcriptionDeltaText.visibility = View.VISIBLE
  ```
- [ ] **Step 2: Compile.** Run: `$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:assembleDebug --no-daemon` — expect `BUILD SUCCESSFUL`.
- [ ] **Step 3: Full JVM suite.** Run: `$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon` — expect `BUILD SUCCESSFUL` (no test reads this string).
- [ ] **Step 4: Commit.** `git add app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt` then `git commit -m "feat(bubble): FINALIZING status line names the provider wait for cloud sessions" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`nClaude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT"`
- [ ] **Step 5: Archive the BEFORE build — the C3 evidence gate's "before" artifact.** This commit is the last with C1's instrumentation but WITHOUT C3's fix: exactly the "before" build the spec's before/after protocol needs. C1 and C3 land on the same branch with no owner pause between them, so the "before" state would otherwise cease to exist as an installable artifact — archive it now, outside the repo, where later commits cannot touch it:
  ```powershell
  $env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:assembleDebug --no-daemon
  Copy-Item 'C:\Users\bastr\.androidbuild\WhisperEverywhere\app\outputs\apk\debug\app-debug.apk' 'C:\Users\bastr\.androidbuild\WhisperEverywhere\whisper-3.5.0-BEFORE-C3-instrumented.apk'
  ```
  Expected: `BUILD SUCCESSFUL`, then the copy exists (verify with `Test-Path 'C:\Users\bastr\.androidbuild\WhisperEverywhere\whisper-3.5.0-BEFORE-C3-instrumented.apk'` → `True`). The owner's D2 timing checklist installs THIS apk for the "before" sessions and the final branch build for the "after" sessions — the spec's evidence gate stays executable after C3 lands, with no mid-branch owner checkpoint.

---

### Task C3: THE FIX — stop fencing local when it owes the session nothing

**Static conviction (from the full read of `FallbackTranscriptionEngine` + `LocalWhisperEngine`).** On the happy path — every cloud segment resolved `Text`/`EmptyExpected` — **local's queue contains NO per-segment work at stop.** `local.sendAudio`/`local.commit` are reachable ONLY from `localRetry`, which is called ONLY from `CloudRelay.onSegmentResolved` when `FallbackPolicy.shouldFallBack` is true (`Lost`/`EmptyUnexpected`). There is no shadow transcribe of successful segments — so there is no per-segment local work to cancel, and the spec's "cancel local's queue for cloud-resolved segments" branch has an empty target. What local's single-thread native executor CAN still be running at stop is the **safety-net model (re)load** that `FallbackTranscriptionEngine.connect() → local.connect()` (or `prewarm()`, `FloatingBubbleService.kt:446`) enqueued: ~7 s on Adreno OpenCL per the `prewarm` kdoc, re-triggered after every `releaseContext()` trim and every model switch. `FallbackTranscriptionEngine.awaitIdle` (`:379`) unconditionally fences local (`local.awaitIdle(remainingMs)` queues behind that load), so a short cloud session stopped before the load finishes pays the load's remainder inside FINALIZING — a wait on work whose result nothing pending needs. **Fix:** after a full cloud drain, skip the local fence iff the retry ledger is empty. This is sound because of the class's own documented invariant: retries are submitted to local synchronously inside cloud's resolution callback, and `CloudTranscriptionEngine.resolveOnce` removes a `pending` entry only AFTER the callback returns — so `cloud.awaitIdle == true` guarantees every retry this session will ever start is already in the ledger. **One more property is REQUIRED before "empty ledger" may mean "safe to skip": the ledger must be a *delivery* fence, not a scheduling fence — and at HEAD it is not.** `LocalRelay.onSegmentResolved` removes the entry (`retries.remove(seq)` under `retryLock`) BEFORE calling `retry.resolve(outcome)` outside the lock, so there is a window in which the last retry has left the ledger while its resolution (orderer release → service append) is still running on local's executor thread. A skip firing inside that window lets the finalize coroutine flush the orderer and detach the sink while the rescued text is mid-delivery — silent segment loss, exactly the class of bug the drain contract exists to prevent (compare the 2026-07-29 latch race in this file's history). `CloudTranscriptionEngine.resolveOnce` already has the needed discipline — its kdoc says `pending` is removed only after the callback returns precisely so awaitIdle means "delivered rather than merely scheduled". **So this task ALSO reorders `LocalRelay.onSegmentResolved` to remove AFTER delivery** (lookup under the lock without removing → resolve outside the lock → remove under the lock; the `claimed` CAS already makes any double delivery a no-op, so the abandon/straggler paths are unaffected). With that ordering: empty ledger ⇒ every seq the session handed out has already resolved AND been delivered ⇒ skipping cannot lose a segment. Nothing is cancelled (the load keeps running; the net stays warm), `FallbackPolicy.reconcile` and `SegmentOrderer` are untouched.

**Owner-run C1 evidence gate (before/after, one batch-cloud + one local + one live session on-device via `adb.exe install -r`; "before" = the archived C2 Step 5 APK `whisper-3.5.0-BEFORE-C3-instrumented.apk`, "after" = the final branch build — both installable at D2 time, no mid-branch pause):** the conviction is CONFIRMED if the *before* batch-cloud timings show `local-drain` materially > 0 on a clean session (largest on the first session after service start, a trim, or a model switch) while `cloud-drain` ≈ one provider round-trip; *after*, the batch-cloud happy path must log `finalize-timing: local-drain=0ms (skipped: no outstanding retries)` and `total` must shrink by the old `local-drain`. LIVE sessions are NOT expected to log the skip line even when fixed: every live stop rescues its tail turn on-device by design (`finishServerTurns` resolves pending turns `Lost` → `FallbackPolicy.shouldFallBack` arms the rescue — the 2026-07-31 tail-turn rescue), so a correct after-build live session logs a small nonzero `local-drain` covering that rescue; treat its absence of the skip line as expected, not as a failed gate. The conviction is FALSIFIED if *before* shows `local-drain` ≈ 0 with `cloud-drain` dominating — then the wait is the irreducible tail round-trip, the fix (still correct) is not the ship's headline, and the C2 status line is the user-facing remedy; the next candidate to instrument would be serialized waits ahead of `commit-dispatch` (a non-trivial `commit-dispatch` number convicts `audioRecorder.stop()`/projection release stacking ahead of the tail commit).

**Files:**
- Modify: `app/src/main/java/com/whispereverywhere/transcription/cloud/FallbackTranscriptionEngine.kt` — `awaitIdle` + new private `retriesOutstanding()` + `LocalRelay.onSegmentResolved` reordered to remove-after-delivery
- Test: `app/src/test/java/com/whispereverywhere/transcription/cloud/FallbackTranscriptionEngineTest.kt` — three new tests in the "draining and teardown" section

**Interfaces:**
- Consumes: C1's `"finalize-timing: local-drain=..."` phase name (the skip logs the same phase so the owner's log always shows all six phases); the test file's existing `FakeEngine` (records `awaitIdleCalls: MutableList<Long>`), `Rec`, `FakeStt`, `scope()`, `engine(cloud, local)` helpers exactly as defined at `FallbackTranscriptionEngineTest.kt:59-175`.
- Produces: `private fun retriesOutstanding(): Boolean` on `FallbackTranscriptionEngine`; the ledger-as-DELIVERY-fence ordering in `LocalRelay.onSegmentResolved` (an entry is removed only after its `resolve()` returns); the skip semantics Task C4 guards.

- [ ] **Step 1: Write failing test — the fence must be skipped when local owes nothing.** Append inside the `// ---- draining and teardown` section of `FallbackTranscriptionEngineTest.kt`, after `awaitIdle_drains_cloud_before_local_so_retries_are_covered`:
  ```kotlin
      @Test fun awaitIdle_does_not_wait_on_local_when_no_retry_is_outstanding() {
          // THE FINALIZE HOLD (owner report, spec 2026-08-18 C2): on the happy path every cloud
          // segment resolved Text/EmptyExpected, so local's queue holds NO retry — only the
          // safety-net model load connect() enqueued. Fencing behind that load is waiting on
          // work whose result nothing pending needs.
          val provider = FakeStt(respond = { SttResult.Text("all good") })
          val local = FakeEngine()
          val e = engine(CloudTranscriptionEngine(provider, scope()), local)
          val l = Rec()
          e.connect(null, l)
          e.sendAudio(byteArrayOf(2)); e.commit()

          assertTrue(e.awaitIdle(5_000))
          assertEquals(0L to SegmentOutcome.Text("all good"), l.next())
          assertEquals("local owes nothing — the fence must be skipped", 0, local.awaitIdleCalls.size)
      }
  ```
- [ ] **Step 2: Write failing test — the stop path must not pay for the model load (real background executor).** Append directly below Step 1's test:
  ```kotlin
      @Test fun a_happy_path_stop_does_not_pay_for_the_safety_nets_model_load() {
          // The convicted hold, reproduced: a REAL LocalWhisperEngine on a REAL single-thread
          // executor whose model load is still running at stop — exactly the first cloud session
          // after service start / a memory trim / a model switch. The load must keep running
          // (the net stays warm) but the stop must not wait for it.
          val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
          val releaseLoad = CountDownLatch(1)
          try {
              val loadStarted = CountDownLatch(1)
              val backend = object : WhisperBackend {
                  override fun load(modelPath: String): Long {
                      loadStarted.countDown()
                      releaseLoad.await(10, TimeUnit.SECONDS) // the ~7 s Adreno load, held by the test
                      return 42L
                  }
                  override fun transcribe(ctx: Long, samples: FloatArray, lang: String?, useVad: Boolean) = ""
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
              val provider = FakeStt(respond = { SttResult.Text("all good") })
              val e = engine(CloudTranscriptionEngine(provider, scope()), local)
              val l = Rec()
              e.connect(null, l)
              assertTrue(loadStarted.await(5_000, TimeUnit.MILLISECONDS))
              e.sendAudio(ByteArray(3200) { 1 })
              assertEquals(0L, e.commit())

              val startNs = System.nanoTime()
              assertTrue(e.awaitIdle(20_000))
              val elapsedMs = (System.nanoTime() - startNs) / 1_000_000
              assertEquals(0L to SegmentOutcome.Text("all good"), l.next())
              assertTrue(
                  "stop waited ${elapsedMs}ms on a model load no pending segment needs",
                  elapsedMs < 2_000,
              )
          } finally {
              releaseLoad.countDown()
              executor.shutdownNow()
          }
      }
  ```
- [ ] **Step 3: Run the class — expect BOTH new tests to FAIL.** Run: `$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon --tests "com.whispereverywhere.transcription.cloud.FallbackTranscriptionEngineTest"` — expect `BUILD FAILED` with `awaitIdle_does_not_wait_on_local_when_no_retry_is_outstanding FAILED` (current `awaitIdle` unconditionally calls `local.awaitIdle`, so `awaitIdleCalls.size == 1`) and `a_happy_path_stop_does_not_pay_for_the_safety_nets_model_load FAILED` (the fence queues behind the held load; `elapsedMs` ≈ 10 000 from the latch timeout, ≥ 2 000). All 28 pre-existing tests still pass.
- [ ] **Step 4: Implement the skip in `FallbackTranscriptionEngine`.** Replace `awaitIdle` and its kdoc (lines 369-386) and add the helper below it:
  ```kotlin
      /**
       * Drains cloud FIRST, then local, and that order is load-bearing rather than arbitrary: a retry
       * is submitted to local synchronously inside cloud's own resolution callback, so by the time
       * cloud reports drained, every retry this session will ever start is already queued on local.
       * Draining local first would race the work it is supposed to wait for.
       *
       * That same property makes the happy-path skip sound: cloud drained means the retry ledger can
       * only shrink from here. If it is EMPTY, local owes this session nothing — every seq the
       * session handed out has already resolved AND been delivered ([LocalRelay.onSegmentResolved]
       * removes a ledger entry only after its resolve() returns: the same delivery-fence discipline
       * as CloudTranscriptionEngine.resolveOnce, and load-bearing for this skip) — and the only work
       * its executor can still be running is the safety-net model (re)load connect()/prewarm() enqueued. Fencing behind that
       * load made a short cloud session's stop pay up to the whole load (~7 s on Adreno OpenCL) for
       * a result nothing pending needed (owner-reported finalize lag, spec 2026-08-18 C2). Nothing
       * is cancelled — the load keeps running and the net stays warm — we just stop waiting for it.
       * FallbackPolicy.reconcile and the SegmentOrderer contracts are untouched: no outcome changes,
       * only a wait on an executor that owes no resolutions is dropped.
       *
       * The two drains share ONE budget by deadline rather than splitting it in half, so a fast cloud
       * drain leaves the local retry almost all of the time. If cloud consumes the whole budget the
       * result is false regardless of what local reports.
       */
      override fun awaitIdle(timeoutMs: Long): Boolean {
          val budget = timeoutMs.coerceIn(0L, MAX_DRAIN_MS)
          val deadlineNs = System.nanoTime() + budget * NANOS_PER_MS
          val cloudDrained = cloud.awaitIdle(budget)
          if (cloudDrained && !retriesOutstanding()) {
              // Same phase name as LocalWhisperEngine.awaitIdle's C1 line, so the owner's
              // finalize-timing log always shows all six phases.
              android.util.Log.i(TAG, "finalize-timing: local-drain=0ms (skipped: no outstanding retries)")
              return true
          }
          val remainingMs = ((deadlineNs - System.nanoTime()) / NANOS_PER_MS).coerceAtLeast(0L)
          val localDrained = local.awaitIdle(remainingMs)
          return cloudDrained && localDrained
      }

      /**
       * True while local still owes this session a resolution: an armed retry not yet answered, or a
       * [localRetry] whose local.commit() has not returned. Meaningful as a skip guard ONLY after a
       * full cloud drain — before that, cloud callbacks can still arm new retries.
       */
      private fun retriesOutstanding(): Boolean =
          synchronized(retryLock) { retries.isNotEmpty() || submitting != null }
  ```
- [ ] **Step 5: Run the class — expect PASS.** Run: `$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon --tests "com.whispereverywhere.transcription.cloud.FallbackTranscriptionEngineTest"` — expect `BUILD SUCCESSFUL`, 30 tests green: the two new tests pass, and the pre-existing retry-path tests (`awaitIdle_drains_cloud_before_local_so_retries_are_covered`, `the_decorator_keeps_its_own_pcm…`, `a_rescue_longer_than_the_local_buffer_cap…`) still pass because an outstanding retry keeps `retriesOutstanding()` true and the local fence runs exactly as before.
- [ ] **Step 6: Write failing test — the skip must NOT fire while a rescue's delivery is still in flight.** This pins the delivery-fence property the header argument requires (and which HEAD's remove-before-resolve ordering lacks). Deterministic, no sleeps racing anything: the listener blocks INSIDE the delivery callback, holding the window open while the test probes `awaitIdle`. Append below `a_happy_path_stop_does_not_pay_for_the_safety_nets_model_load` (reference the listener interface the same way the file's `Rec` helper declares it; `CountDownLatch`/`TimeUnit` are already imported for the Step 2 test):
  ```kotlin
      @Test fun the_skip_cannot_fire_while_a_rescues_delivery_is_still_in_flight() {
          // The remove-before-resolve window: LocalRelay removed the ledger entry BEFORE delivering
          // the resolution (resolve runs outside the lock). A stop landing in that window saw an
          // empty ledger, skipped the fence, and the finalize path then flushed the orderer and
          // detached the sink while the rescued text was still mid-delivery — silent segment loss.
          // Held open deterministically here: the listener blocks INSIDE the delivery callback,
          // which runs on local's real executor thread.
          val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
          val deliveryEntered = CountDownLatch(1)
          val releaseDelivery = CountDownLatch(1)
          try {
              val backend = object : WhisperBackend {
                  override fun load(modelPath: String) = 42L
                  override fun transcribe(ctx: Long, samples: FloatArray, lang: String?, useVad: Boolean) = "rescued locally"
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
              // The one segment fails on cloud, so it is rescued locally — the session's only
              // delivery goes through LocalRelay.
              val provider = FakeStt(respond = { SttResult.Failed(SttError.Offline) })
              val e = engine(CloudTranscriptionEngine(provider, scope()), local)
              val delivered = java.util.concurrent.CopyOnWriteArrayList<Pair<Long, SegmentOutcome>>()
              val l = object : TranscriptionEngine.Listener {
                  override fun onOpen() = Unit
                  override fun onDelta(text: String) = Unit
                  override fun onError(message: String) = Unit
                  override fun onClosed() = Unit
                  override fun onSegmentResolved(seq: Long, outcome: SegmentOutcome) {
                      deliveryEntered.countDown()
                      releaseDelivery.await(10, TimeUnit.SECONDS)
                      delivered.add(seq to outcome)
                  }
              }
              e.connect(null, l)
              e.sendAudio(ByteArray(3200) { 1 })
              assertEquals(0L, e.commit())
              // The rescue has entered delivery and is now HELD there, mid-flight.
              assertTrue(deliveryEntered.await(5, TimeUnit.SECONDS))

              val done = java.util.concurrent.atomic.AtomicBoolean(false)
              val drained = java.util.concurrent.atomic.AtomicBoolean(false)
              val waiter = Thread {
                  drained.set(e.awaitIdle(20_000))
                  done.set(true)
              }
              waiter.start()
              waiter.join(1_500)
              assertTrue(
                  "awaitIdle returned while a rescue's delivery was still in flight",
                  !done.get(),
              )

              releaseDelivery.countDown()
              waiter.join(20_000)
              assertTrue(done.get())
              assertTrue(drained.get())
              assertEquals(listOf(0L to SegmentOutcome.Text("rescued locally")), delivered.toList())
          } finally {
              releaseDelivery.countDown()
              executor.shutdownNow()
          }
      }
  ```
- [ ] **Step 7: Run the class — expect exactly this test to FAIL.** Run: `$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon --tests "com.whispereverywhere.transcription.cloud.FallbackTranscriptionEngineTest"` — expect `BUILD FAILED` with `the_skip_cannot_fire_while_a_rescues_delivery_is_still_in_flight FAILED` on the mid-flight assertion: with Step 4's skip in place but `LocalRelay` unchanged, the executor thread removes the retry from the ledger BEFORE delivering, so while the listener holds the delivery open the ledger is empty, `retriesOutstanding()` is false, the skip fires, and `awaitIdle` returns inside the 1.5 s probe (`done.get()` is already true at the join). The other 30 tests pass.
- [ ] **Step 8: Make the ledger a delivery fence — reorder `LocalRelay.onSegmentResolved` to remove AFTER resolve.** In `FallbackTranscriptionEngine.kt`, replace the method (its kdoc'd comment block is kept verbatim — only the lookup line and the tail change):
  ```kotlin
  // OLD
          override fun onSegmentResolved(seq: Long, outcome: SegmentOutcome) {
              val retry = synchronized(retryLock) {
                  // Two arrival orders, both real:
                  //
                  //  * asynchronous — local.commit() has already returned and registered the mapping.
                  //    The lock is what guarantees "already": [localRetry] holds it across commit()
                  //    AND the registration, so an engine thread arriving here waits for both.
                  //
                  //  * synchronous — the local engine resolved INSIDE commit(), before there was a
                  //    seq to map it against. That is not contrived: LocalWhisperEngine resolves on
                  //    its executor, and a same-thread executor lands here RE-ENTRANTLY on the
                  //    submitting thread, which still holds this lock. [submitting] is that retry.
                  //    Missing it would leave the ORIGINAL seq unresolved and stall the orderer head
                  //    forever, holding every later segment with it.
                  retries.remove(seq) ?: submitting
              }
              // Not ours: a straggler from a session that has already been abandoned, and one that
              // was resolved at abandon time. `claimed` makes a double delivery a no-op regardless.
              retry?.resolve(outcome)
          }
  ```
  ```kotlin
  // NEW
          override fun onSegmentResolved(seq: Long, outcome: SegmentOutcome) {
              val retry = synchronized(retryLock) {
                  // Two arrival orders, both real:
                  //
                  //  * asynchronous — local.commit() has already returned and registered the mapping.
                  //    The lock is what guarantees "already": [localRetry] holds it across commit()
                  //    AND the registration, so an engine thread arriving here waits for both.
                  //
                  //  * synchronous — the local engine resolved INSIDE commit(), before there was a
                  //    seq to map it against. That is not contrived: LocalWhisperEngine resolves on
                  //    its executor, and a same-thread executor lands here RE-ENTRANTLY on the
                  //    submitting thread, which still holds this lock. [submitting] is that retry.
                  //    Missing it would leave the ORIGINAL seq unresolved and stall the orderer head
                  //    forever, holding every later segment with it.
                  retries[seq] ?: submitting
              }
              // Not ours: a straggler from a session that has already been abandoned, and one that
              // was resolved at abandon time. `claimed` makes a double delivery a no-op regardless.
              retry?.resolve(outcome)
              // Removed only AFTER resolve() returns: the ledger is a DELIVERY fence, not a
              // scheduling fence — the same discipline as CloudTranscriptionEngine.resolveOnce, for
              // the same reason. awaitIdle's happy-path skip consults retriesOutstanding(), and
              // "left the ledger" must imply "delivered", or a stop landing in the gap flushes the
              // orderer and detaches the sink while the rescued text is mid-delivery. Removing a
              // key that was never registered (the synchronous [submitting] arrival) is a no-op,
              // and a concurrent abandon's double resolve is already absorbed by `claimed`.
              synchronized(retryLock) { retries.remove(seq) }
          }
  ```
- [ ] **Step 9: Run the class — expect PASS.** Run: `$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon --tests "com.whispereverywhere.transcription.cloud.FallbackTranscriptionEngineTest"` — expect `BUILD SUCCESSFUL`, 31 tests green. Step 6's test now passes because the entry stays ledgered until its delivery returns: `retriesOutstanding()` is true while the listener holds the callback open, so `awaitIdle` takes the fence path and queues behind the blocked executor task. The 28 pre-existing tests are unaffected — the reorder changes no observable outcome, only when the private ledger forgets a seq (both arrival orders in the comment behave identically; the re-entrant `submitting` arrival's trailing `remove` is a no-op on a key that was never registered).
- [ ] **Step 10: Full JVM suite.** Run: `$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon` — expect `BUILD SUCCESSFUL`.
- [ ] **Step 11: Commit.** `git add app/src/main/java/com/whispereverywhere/transcription/cloud/FallbackTranscriptionEngine.kt app/src/test/java/com/whispereverywhere/transcription/cloud/FallbackTranscriptionEngineTest.kt` then `git commit -m "fix(cloud): stop skips the local drain when no retry is outstanding" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`nClaude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT"`

---

### Task C4: real-executor proof that the retained retry path loses no segment

**Files:**
- Test: `app/src/test/java/com/whispereverywhere/transcription/cloud/FallbackTranscriptionEngineTest.kt` — one new test after Task C3's two

**Interfaces:**
- Consumes: C3's skip semantics (`retriesOutstanding()` guard) and its `LocalRelay` delivery-fence ordering (entries leave the ledger only after `resolve()` returns); the file's `FakeStt(respond: (ByteArray) -> SttResult)`, `engine()`, `Rec` helpers; real `LocalWhisperEngine(modelPathProvider, retry, backend, executor)` construction as at `FallbackTranscriptionEngineTest.kt:656-663`.
- Produces: the spec-mandated no-segment-loss guard for the retry path that remains (spec C2: "a real-background-executor JVM test must prove no segment loss").

- [ ] **Step 1: Write the guard test — mixed workload, real background executor, everything delivered BEFORE `awaitIdle` returns.** Append below `the_skip_cannot_fire_while_a_rescues_delivery_is_still_in_flight` (Task C3's last test):
  ```kotlin
      @Test fun awaitIdle_still_drains_every_outstanding_retry_before_returning() {
          // The C3 skip's boundary: it must NEVER fire while a retry is outstanding. Real
          // background executor on purpose (house rule): the retries resolve on local's executor
          // thread a beat after cloud's callbacks armed them — the interleaving a same-thread
          // executor hides. Even seqs succeed on cloud; odd seqs are lost and must be rescued
          // locally, with every resolution delivered by the time awaitIdle returns true.
          val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
          try {
              val backend = object : WhisperBackend {
                  override fun load(modelPath: String) = 42L
                  override fun transcribe(ctx: Long, samples: FloatArray, lang: String?, useVad: Boolean): String {
                      Thread.sleep(100) // a real transcribe takes time; the drain must cover it
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
              val provider = FakeStt(respond = { pcm ->
                  if (pcm[0].toInt() % 2 == 0) SttResult.Text("cloud ${pcm[0]}")
                  else SttResult.Failed(SttError.Offline)
              })
              val e = engine(CloudTranscriptionEngine(provider, scope()), local)
              val l = Rec()
              e.connect(null, l)
              val total = 8
              repeat(total) { i ->
                  e.sendAudio(ByteArray(3200) { i.toByte() })
                  assertEquals(i.toLong(), e.commit())
              }

              assertTrue(e.awaitIdle(20_000))
              // Asserted IMMEDIATELY, no polling: the drain contract is that everything committed
              // has been DELIVERED, not merely scheduled, when awaitIdle returns.
              assertEquals("every seq resolved before awaitIdle returned", total, l.all.size)
              val bySeq = l.all.sortedBy { it.first }
              assertEquals("no seq lost, none duplicated", (0L until total.toLong()).toList(), bySeq.map { it.first })
              bySeq.forEach { (seq, outcome) ->
                  if (seq % 2 == 0L) assertEquals(SegmentOutcome.Text("cloud $seq"), outcome)
                  else assertEquals("seq $seq must be rescued, not lost", SegmentOutcome.Text("rescued locally"), outcome)
              }
          } finally {
              executor.shutdownNow()
          }
      }
  ```
- [ ] **Step 2: Run the class — expect PASS on the first run.** Run: `$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon --tests "com.whispereverywhere.transcription.cloud.FallbackTranscriptionEngineTest"` — expect `BUILD SUCCESSFUL`, 32 tests green. This test guards behavior C3 deliberately preserved (it is a regression fence, not a red-first TDD step — the failure it defends against is a FUTURE loosening of the `retriesOutstanding()` guard; deleting the guard's `retries.isNotEmpty()` clause makes this test fail with `l.all.size < 8`).
- [ ] **Step 3: Full JVM suite.** Run: `$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon` — expect `BUILD SUCCESSFUL`.
- [ ] **Step 4: Commit.** `git add app/src/test/java/com/whispereverywhere/transcription/cloud/FallbackTranscriptionEngineTest.kt` then `git commit -m "test(cloud): real-executor proof the retained retry path still drains at stop" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`nClaude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT"`
- [ ] **Step 5: Record the owner acceptance gate (no code).** Note for the release checklist (Workstream D owns the checklist doc; this step only states the gate): owner sideloads via `adb.exe install -r` (NEVER `installDebug`) — "before" = the archived C2 Step 5 APK `C:\Users\bastr\.androidbuild\WhisperEverywhere\whisper-3.5.0-BEFORE-C3-instrumented.apk`, "after" = the final branch build — runs one batch-cloud, one local, one live session on each, and captures `adb.exe logcat -s WE-DIAG | findstr finalize-timing`. Accept when, on the AFTER build: the batch-cloud happy path logs `local-drain=0ms (skipped: no outstanding retries)` and `total` ≈ `commit-dispatch` + `cloud-drain` (one tail round-trip) + low-ms flush/delivery; the LIVE session logs a small nonzero `local-drain` INSTEAD of the skip line — expected, not a failure: every live stop rescues its tail turn on-device by design (`finishServerTurns` resolves pending turns `Lost` → `FallbackPolicy.shouldFallBack` arms the rescue), so live's gate is "`local-drain` covers only the tail rescue and every segment is delivered"; and a session with forced losses (airplane mode mid-session) still delivers every segment via the rescue with `local-drain` > 0. If the *before* logs already show `local-drain` ≈ 0 everywhere, the conviction is falsified per Task C3's evidence gate, the follow-up target is whatever phase the timings convict instead, and D1's "finishes faster" release-note sentence must be trimmed before Play submission (exact replacement recorded in Task D1).

---

### Task D1: Release notes + version bump 3.5.0 / versionCode 76

**Files:**
- Modify: `docs/PLAY-LISTING.md` (append a new release-notes section after the 3.4.1 one)
- Modify: `app/build.gradle.kts` (the `versionCode`/`versionName` pair inside `defaultConfig`)

**Interfaces:**
- Consumes: all prior workstreams complete and green.
- Produces: the release identity for the 3.5.0 AAB (owner builds/signs/submits via the established Play flow — out of plan scope, as is the post-rollout reply to the Bengali reviewer, which the owner sends once 76 is live).

- [ ] **Step 1: Append the 3.5.0 release notes to `docs/PLAY-LISTING.md`.** After the `## Release notes — 3.4.1` block, add:

```markdown
## Release notes — 3.5.0 (within 500 chars)

> You're in charge of your speech model now. Setup lets you pick your tier with plain-language descriptions — fastest, most accurate, English-only or 90+ languages — and you can switch anytime in Settings. Prefer the big cloud models? Add your own API key (OpenAI, Gemini, ElevenLabs, Soniox), billed to your account. Also: ending a session now finishes faster, and the app says what it's waiting on while it wraps up.

(Verify ≤500 chars in Step 2. Claim rules: no cloud speed claims — "finishes faster" refers to our
own fix vs our own previous version, which is factual and allowed. CONTINGENCY: that clause is
backed by the D2 before/after timings, which run AFTER this commit. If the before-build logs
falsify the C3 conviction — `local-drain` ≈ 0 everywhere — edit the notes BEFORE Play submission:
replace "Also: ending a session now finishes faster, and the app says what it's waiting on while
it wraps up." with "Also: the app now says what it's waiting on while a session wraps up." and
re-verify the count.)
```

- [ ] **Step 2: Verify the character count.**

```powershell
$s = "You're in charge of your speech model now. Setup lets you pick your tier with plain-language descriptions — fastest, most accurate, English-only or 90+ languages — and you can switch anytime in Settings. Prefer the big cloud models? Add your own API key (OpenAI, Gemini, ElevenLabs, Soniox), billed to your account. Also: ending a session now finishes faster, and the app says what it's waiting on while it wraps up."
$s.Length
```

Expected: a number ≤ 500 (the draft is ~410). If over, trim the final sentence first.

- [ ] **Step 3: Bump the version.** In `app/build.gradle.kts`, replace:

```kotlin
        versionCode = 75
        versionName = "3.4.1"  // hotfix: transcription history no longer self-deletes at finalize (stats block zeroed the save stamp)
```

with:

```kotlin
        versionCode = 76
        versionName = "3.5.0"  // pick-your-model onboarding + cloud-key education; finalize no longer over-waits at stop
```

- [ ] **Step 4: Compile and run the full suite.**

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:assembleDebug --no-daemon
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon
```

Expected: `BUILD SUCCESSFUL` twice, no failed tests.

- [ ] **Step 5: Commit.**

```powershell
git add docs/PLAY-LISTING.md app/build.gradle.kts
git commit -m "chore(release): 3.5.0 / versionCode 76 — pick-your-model onboarding, finalize fix" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`nClaude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT"
```

---

### Task D2: Owner on-device checklist (owner-run — NEVER installDebug)

No implementer dispatch; the controller surfaces this to the owner when the branch merges.

Install: build `:app:assembleDebug`, then `adb.exe install -r C:\Users\bastr\.androidbuild\WhisperEverywhere\app\outputs\apk\debug\app-debug.apk` (data-preserving; NEVER `installDebug`/`connectedAndroidTest` — they wipe the on-device models).

- [ ] **Onboarding walkthrough** (fresh state needed: use a spare profile, or on the daily device expect to re-download models/keys afterward — owner's call; clearing app data wipes models AND keys): four tier cards visible, none preselected, continue gated until a pick; picking starts model + voice downloads together with no further taps; the "switch models anytime in Settings" line renders; the cloud-keys step shows the teaching card (four providers, own-account billing, optional).
- [ ] **Settings picker** shows the same tier descriptions as onboarding — and actually SWITCH to a different tier, dictate one short session on it (exercises A3's selected-tier reload path), then switch back if desired.
- [ ] **Home note:** visible while no cloud key is configured; X dismisses and it stays gone across app restarts; with a key configured it never shows; its button lands on Engines & voices.
- [ ] **Finalize timing (the C acceptance gate — BEFORE/AFTER):** FIRST install the archived before-build (`adb.exe install -r C:\Users\bastr\.androidbuild\WhisperEverywhere\whisper-3.5.0-BEFORE-C3-instrumented.apk`, from C2 Step 5 — instrumented but unfixed) and run one on-device session per mode — local, cloud batch, cloud live — dictating ~3 sentences, then stop; pull and SAVE the timings: `adb.exe logcat -d | Select-String "finalize-timing"`. THEN install the final branch build and repeat the same three sessions. Report both sets of phase lines to the controller. Acceptance on the after-build: batch-cloud stop logs `local-drain=0ms (skipped: no outstanding retries)` and ≈ one tail round-trip; LIVE logs a small nonzero `local-drain` instead of the skip line (the tail-turn rescue — expected by design, not a failure); no phase shows a wait attributable to already-resolved work; the FINALIZING line reads "Finishing… (waiting on provider)" during cloud drains. If the BEFORE logs already show `local-drain` ≈ 0 everywhere, the C3 conviction is falsified — report it, and trim D1's "finishes faster" sentence before Play submission (exact replacement in Task D1).
- [ ] **Regression spot-checks:** dictation into WhatsApp still delivers once at stop; Transcriptions history still records sessions (the 3.4.1 fix); resize handle still works.
