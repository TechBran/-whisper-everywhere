# UI refresh — mode dashboard, Engines & voices hub, two-path first run

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restructure the app's *furniture* so Home is a **mode dashboard** — one card per mode (Dictation, Transcribe-file, Read-aloud), each showing its LIVE configuration as a status chip and tapping into its own settings — plus a **two-path first-run chooser** (Free & private → model download; Bring your own key → the hub) that is skippable and NEVER blocks. `CloudProvidersScreen` becomes the **Engines & voices hub** (three sections: Transcription engine / Read-aloud voice / API keys). The owner APPROVED this exact design (`ui-refresh-brief.md`).

**Architecture — this wave moves FURNITURE, never LOGIC.** Every gate (disclosure v3, consent triads, key-removal deselection, cost confirms, engine selection) keeps its EXACT behavior and its EXACT pure functions. Composables may move files/screens; pure logic may not change. The BUBBLE and its service (`FloatingBubbleService`) are untouched. The only NEW pure logic is a set of Compose-free **dashboard formatters** (Task 1) in a new file, with their own new test file — so no pinned test changes except an `import`. If a pinned test needs more than an import change, **logic moved — stop and report** (the wave law).

**Tech Stack:** Kotlin 2.0.21, Compose Material3, existing theme/typography (`ui/theme/Color.kt`: `Primary`=#3B82F6, `Success`, `Warning`). **No new dependencies. OkHttp 4.12.0 pinned.** `unitTests.isReturnDefaultValues = true`; `org.json` BANNED. This is a restructure, not a re-skin.

## Global Constraints (binding)

- **Furniture only.** Pure gate functions in `com.whispereverywhere.ui.screens` keep their package, exact names, exact signatures, and exact returned strings. Moved composables keep their pinned copy tests — update imports, never assertions.
- **First run NEVER blocks.** Skippable; a skipper lands on Home with the setup banner. Existing users (who all have a model) NEVER see the chooser. **No speed claims anywhere.** Prices keep appearing ONLY where they appear today (the hub's live-mode row / voice list) — the dashboard chips add no new price surface.
- **All chips read reactively** — StateFlow / `ON_RESUME` snapshot state from `PreferencesManager`, **no polling** (the existing 1000 ms Home poll is removed). Keystore-backed reads (`providerAccounts.configured()`) stay off-main (`produceState` + `Dispatchers.IO`).
- **`java` NOT on PATH.** PowerShell: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio1\jbr"`; `.\gradlew.bat --no-daemon`. `assembleRelease` must stay green (R8). **NEVER `connectedAndroidTest`/`installDebug`** — instrumented = compile-check only.
- **Branch `main`. Commit ONLY named files, never `git add -A`. Retry once on `index.lock`.**
- **Baseline: 610 tests / 0 failures, HEAD `208174a`**, `assembleDebug` + `assembleRelease` both green.

---

## File Structure

| File | Change |
|---|---|
| `ui/screens/ModeDashboard.kt` | **NEW** — pure dashboard formatters + first-run routing (Task 1) |
| `test/.../ui/screens/ModeDashboardLogicTest.kt` | **NEW** — JVM tests for Task 1 |
| `ui/screens/EnginesAndVoicesScreen.kt` | **NEW** — the hub composable; three sections (Task 2). Thin wrapper hosting MOVED composables |
| `ui/screens/CloudProvidersScreen.kt` | Pure fns STAY (unchanged). Screen composables MOVE into the hub; keep file as home of the `internal` pure logic + the API-keys section composables |
| `ui/screens/SettingsScreen.kt` | Read-aloud voice-picker composables MOVE into the hub's section 2; pure fns STAY unchanged |
| `ui/screens/HomeScreen.kt` | Home becomes the mode dashboard: mode cards + chips + setup banner (Task 3) |
| `ui/screens/FirstRunChooserScreen.kt` | **NEW** — two-path chooser (Task 4) |
| `MainActivity.kt` | start destination via `firstRunStartDestination`; `first_run` + `engines_voices` routes; `cloud_providers` kept as back-compat alias (Task 2/4) |
| `data/local/PreferencesManager.kt` | **additive** StateFlows for `sttProviderId`/`sttLiveMode`/`ttsProviderId`; `setOnboardingCompleted` setter (repurpose vestigial flag) |
| tests (1 new file) | see Task 1; all 610 pinned green UNCHANGED |

---

## Task 1 — Pure dashboard logic (`ui/screens/ModeDashboard.kt`) + tests

These are **formatters over already-resolved primitives** (a provider display name, a model-tier label, booleans) — never over raw prefs ids or a keystore handle. The Composable resolves the raw prefs (off-main for keystore reads) and hands primitives in, exactly like `sttSelectionCaption(providerDisplayName)` does today. That keeps this file Compose-free and JVM-testable, and keeps it clear of every pinned gate.

### 1a. TDD — `ModeDashboardLogicTest` (new file, package `com.whispereverywhere.ui.screens`)

```kotlin
package com.whispereverywhere.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModeDashboardLogicTest {

    // --- setup banner ---
    @Test fun no_model_no_key_shows_two_path() =
        assertEquals(SetupBanner.TWO_PATH, setupBannerState(hasModel = false, hasAnyKey = false))
    @Test fun model_only_is_partial() =
        assertEquals(SetupBanner.PARTIAL_LINE, setupBannerState(hasModel = true, hasAnyKey = false))
    @Test fun key_only_is_partial() =
        assertEquals(SetupBanner.PARTIAL_LINE, setupBannerState(hasModel = false, hasAnyKey = true))
    @Test fun both_configured_shows_nothing() =
        assertEquals(SetupBanner.NONE, setupBannerState(hasModel = true, hasAnyKey = true))

    @Test fun partial_line_model_path_mentions_key_no_speed_claim() {
        val s = partialSetupLine(hasModel = true)
        assertTrue(s, s.contains("key"))
        assertFalse(s, s.contains("fast"))
    }
    @Test fun partial_line_key_path_mentions_model() =
        assertTrue(partialSetupLine(hasModel = false).contains("model"))

    // --- transcription / dictation chips ---
    @Test fun on_device_chip_with_tier() =
        assertEquals("On-device · Eco", transcriptionEngineChip(engineDisplayName = null, localModelLabel = "Eco"))
    @Test fun on_device_chip_without_tier() =
        assertEquals("On-device", transcriptionEngineChip(engineDisplayName = null, localModelLabel = null))
    @Test fun cloud_engine_chip_is_display_name() =
        assertEquals("OpenAI", transcriptionEngineChip(engineDisplayName = "OpenAI", localModelLabel = "Eco"))

    @Test fun dictation_word_for_word_appends_only_for_cloud() {
        assertEquals("OpenAI · word-for-word", dictationChip("OpenAI", null, liveMode = true))
        // liveMode can never be true on-device (liveModeRowVisible gates it to OpenAI); the guard
        // proves it never leaves a dangling suffix on the on-device chip.
        assertEquals("On-device · Eco", dictationChip(null, "Eco", liveMode = true))
    }
    @Test fun dictation_non_live_matches_engine_chip() =
        assertEquals("OpenAI", dictationChip("OpenAI", null, liveMode = false))
    @Test fun dictation_chip_makes_no_speed_claim() =
        assertFalse(dictationChip("OpenAI", null, liveMode = true).contains("fast"))

    // --- read-aloud chip ---
    @Test fun kokoro_chip_with_voice() =
        assertEquals("Kokoro · af_heart", readAloudChip(engineDisplayName = null, voiceDisplayName = "af_heart"))
    @Test fun kokoro_chip_without_voice() =
        assertEquals("Kokoro", readAloudChip(engineDisplayName = null, voiceDisplayName = null))
    @Test fun cloud_voice_chip_names_voice_and_provider() =
        assertEquals("marin (OpenAI)", readAloudChip(engineDisplayName = "OpenAI", voiceDisplayName = "marin"))
    @Test fun cloud_engine_without_voice_prompts_choice() =
        assertEquals("OpenAI · choose a voice", readAloudChip(engineDisplayName = "OpenAI", voiceDisplayName = null))

    // --- first-run routing ---
    @Test fun fresh_install_goes_to_chooser() =
        assertEquals(ROUTE_FIRST_RUN, firstRunStartDestination(hasModel = false, onboardingCompleted = false))
    @Test fun existing_user_with_model_goes_home() =
        assertEquals(ROUTE_HOME, firstRunStartDestination(hasModel = true, onboardingCompleted = false))
    @Test fun skipper_or_key_user_goes_home() =
        assertEquals(ROUTE_HOME, firstRunStartDestination(hasModel = false, onboardingCompleted = true))
}
```

### 1b. Implement — `ModeDashboard.kt`

```kotlin
package com.whispereverywhere.ui.screens

// ---------------------------------------------------------------------------------------------
// Pure dashboard logic — Compose-free so it is JVM-unit-testable without Robolectric. Every
// function is a FORMATTER over already-resolved primitives (a provider display name, a model-tier
// label, booleans), NEVER over raw prefs ids or a keystore handle: the Composable resolves the raw
// prefs (keystore off-main) and hands primitives in, mirroring sttSelectionCaption(displayName).
// NO speed claim ever appears; NO price appears here — cloud price stays in the hub's live-mode row
// (liveModeLabel), the one surface it appears on today.
// ---------------------------------------------------------------------------------------------

internal const val ROUTE_HOME = "home"
internal const val ROUTE_FIRST_RUN = "first_run"

/** The three setup-guidance states Home can be in, derived from what the user has configured. */
enum class SetupBanner { TWO_PATH, PARTIAL_LINE, NONE }

/**
 * Which setup guidance Home shows. Neither a model nor any key → the prominent two-path banner
 * (Free & private / Bring your own key). Exactly one present → a thin honest status line. Both →
 * nothing. A single working path is still surfaced (PARTIAL_LINE) because the missing half unlocks
 * real capability: cloud engines/voices need a key, on-device dictation needs a model.
 */
internal fun setupBannerState(hasModel: Boolean, hasAnyKey: Boolean): SetupBanner = when {
    !hasModel && !hasAnyKey -> SetupBanner.TWO_PATH
    hasModel && hasAnyKey -> SetupBanner.NONE
    else -> SetupBanner.PARTIAL_LINE
}

/**
 * The single honest line for PARTIAL_LINE. Names the capability the missing half unlocks — nothing
 * more, no price, no speed claim. Never called for the other two states.
 */
internal fun partialSetupLine(hasModel: Boolean): String =
    if (hasModel)
        "On-device transcription is ready. Add a provider key to use a cloud engine or voice."
    else
        "A provider key is saved. Download a model to transcribe on-device."

/**
 * The transcription-engine chip shared by the Dictation and Transcribe-file cards.
 * [engineDisplayName] is the resolved cloud provider name (ProviderCatalog.byId(sttProviderId)
 * .displayName) or null for on-device; [localModelLabel] is the installed model's tier label
 * (e.g. "Eco") or null when it is absent/unknown.
 */
internal fun transcriptionEngineChip(engineDisplayName: String?, localModelLabel: String?): String =
    if (engineDisplayName == null)
        "On-device" + (localModelLabel?.let { " · $it" } ?: "")
    else
        engineDisplayName

/**
 * The Dictation card's chip. Identical to [transcriptionEngineChip] except that when C4 live
 * word-for-word is active — only possible with OpenAI selected (see [liveModeRowVisible]) — it
 * appends " · word-for-word". Batch never streams, so the Transcribe-file card uses
 * [transcriptionEngineChip] directly. "word-for-word" is a MODE name, never "faster": measured
 * on-device is a tie at best, so a speed claim would be a lie. The `engineDisplayName != null`
 * guard means an (impossible) on-device liveMode never leaves a dangling suffix.
 */
internal fun dictationChip(engineDisplayName: String?, localModelLabel: String?, liveMode: Boolean): String {
    val base = transcriptionEngineChip(engineDisplayName, localModelLabel)
    return if (liveMode && engineDisplayName != null) "$base · word-for-word" else base
}

/**
 * The Read-aloud card's chip. On-device Kokoro → "Kokoro" + optional " · <voice>" (the Kokoro
 * speaker name, e.g. "af_heart"); a cloud engine → "<voice> (<provider>)" (e.g. "marin (OpenAI)"),
 * or "<provider> · choose a voice" when no cloud voice is picked yet. [voiceDisplayName] is already
 * resolved by the caller (cloudVoiceDisplayName / TtsVoices.byId). No price, no speed claim.
 */
internal fun readAloudChip(engineDisplayName: String?, voiceDisplayName: String?): String =
    if (engineDisplayName == null)
        "Kokoro" + (voiceDisplayName?.let { " · $it" } ?: "")
    else
        voiceDisplayName?.let { "$it ($engineDisplayName)" } ?: "$engineDisplayName · choose a voice"

/**
 * MainActivity's start destination. First run (no model AND onboarding never completed) → the
 * two-path chooser; otherwise Home. Existing users all have a model, so [hasModel] short-circuits
 * them to Home — they NEVER see the chooser. A user who took the key path or SKIPPED has
 * onboardingCompleted set, so they land on Home (with the setup banner) and are never forced back
 * into the chooser despite having no model. This is the ONLY consumer of the formerly-vestigial
 * onboardingCompleted flag (recon §5), repurposed as the honest skip flag.
 */
internal fun firstRunStartDestination(hasModel: Boolean, onboardingCompleted: Boolean): String =
    if (hasModel || onboardingCompleted) ROUTE_HOME else ROUTE_FIRST_RUN
```

> **Self-review:** Nothing here imports a pinned gate; nothing here is imported by a pinned test → the 610 baseline is untouched by this file. The chips carry no price (cloud price stays in `liveModeLabel`, honoring "prices keep appearing where they appear today" — no NEW price surface) and no speed word (`dictation_chip_makes_no_speed_claim` pins it). **Owner-copy note for review:** the brief's mode-dashboard example writes the live chip as `"OpenAI word-for-word · ~$0.017/min"`; this plan keeps the chip price-free and leaves the ~$0.017/min in the hub's `liveModeLabel()` (unchanged) to avoid a second price surface and stay inside the wave law. Flagging as a copy decision for the owner — logic is unaffected either way.

---

## Task 2 — Engines & voices hub (MOVE composables, ZERO logic edits)

**Behavior spec — this is a Compose restructure; no pure logic changes.**

- [ ] Create `EnginesAndVoicesScreen(onNavigateBack, onNavigateToPrivacyPolicy = {})` — a single `Scaffold` + scrolling `Column` with the existing `TopAppBar` idiom (title "Engines & voices"), the FLAG_SECURE `DisposableEffect`, and the `CloudDisclosureDialog` shown when `!disclosureAccepted` — all LIFTED verbatim from `CloudProvidersScreen` (behavior byte-identical: `onNotNow = onNavigateBack`, `onAccept` sets pref+mirror, `dismissOnBackPress/ClickOutside = false`). The hub renders three `SettingsSection`s (reuse the existing `SettingsSection` scaffold, recon §7):
  1. **Transcription engine** — MOVE `SttEngineSelector` (the "Transcribe with" title, on-device row, `sttSelectableProviders(...)` rows, `sttSelectionCaption`, and the `LiveModeRow` gated by `liveModeRowVisible`) here **unchanged**. All four pure fns (`sttSelectableProviders`, `sttSelectionCaption`, `liveModeRowVisible`, `liveModeLabel`/`liveModeCaption`) stay in `CloudProvidersScreen.kt`, same package, same signatures.
  2. **Read-aloud voice** — MOVE the voice-picker surface from `SettingsScreen` (`TtsEngineRow`, `TtsCloudVoiceRow`, `RecommendedChip`, the engine radio list, the Kokoro list, the per-provider cloud voice lists + Preview). It keeps calling `ttsSelectableProviders`/`ttsPreviewCaption`/`ttsProviderPriceNote`/`ttsNoSpeedControlNote`/`cloudVoiceDisplayName` — **unchanged**, still `internal` in their current files/package. Previews still spend the key via `TtsController.speakFromTrigger`; Select is still free. Prices/no-speed notes still appear exactly here.
  3. **API keys** — MOVE the `ProviderCard` loop over `ProviderCatalog.all` here **unchanged**: `verifyAndSave` → `validator.validate` → `shouldPersistKey` → `persist`; "Remove" still runs `selectionAfterKeyRemoval` for BOTH `sttProviderId` and `ttsProviderId` and clears `setTtsCloudVoiceId(id, null)`; "Save anyway" still gated by `looksLikeInvalidKey`. Local mirrors (`disclosureAccepted`, `sttProviderId`, `sttLiveMode`, `refreshKey`) preserved.
- [ ] **Route rename with back-compat.** Add route `engines_voices` → `EnginesAndVoicesScreen`. **KEEP `cloud_providers` as an alias** that resolves to the SAME composable (a second `composable("cloud_providers"){ EnginesAndVoicesScreen(...) }`), so any retained deep link / back-stack entry still resolves. `SettingsScreen`'s entry (recon §4, L557-567) now navigates to `engines_voices`. The old "Read aloud" voice-row entry in `SettingsScreen` becomes a link into the hub's section 2 (or is removed if the hub is the sole home of voice settings — implementer's call, no logic either way).
- [ ] **Pinned-test guard.** After the move, run `CloudProvidersScreenLogicTest`, `TtsVoicePickerLogicTest`, `PreferencesTtsCloudTest`. They import `com.whispereverywhere.ui.screens.*` and `com.whispereverywhere.tts.*` by function name. If any needs more than an unchanged run (i.e. an assertion edit), **STOP — logic moved.** An import-only change is acceptable ONLY if a moved pure fn changed file within the same package (it should not; keep the pure fns where they are).

> **Self-review:** every gate names a pure fn that stays put; the hub is a container that re-parents existing composables. Disclosure v3, the triad, and key-removal deselection are invoked by the same functions with the same arguments. Back-compat alias means no route is orphaned. Pin: all §8 pinned suites green UNCHANGED.

---

## Task 3 — Home = mode dashboard (Compose; nav threading)

**Behavior spec.**

- [ ] Keep at the top of `HomeScreen`'s `Column`, unchanged: the **MainControlButton** (bubble toggle; `canEnable` logic untouched), the **UsageStatsCard**, and the **Transcriptions** card. The bubble is untouched.
- [ ] **Setup banner** directly under the status area, driven by `setupBannerState(hasModel, hasAnyKey)`:
  - `TWO_PATH` → a prominent card cloning the nav-card idiom (recon §7) with two actions: **"Free & private — download a model"** → `onNavigateToOnboardingModel` (existing model flow); **"Bring your own key"** → `onNavigateToEnginesVoices` (the hub, which shows the disclosure). No speed claim.
  - `PARTIAL_LINE` → a single `bodySmall`/`onSurfaceVariant` line = `partialSetupLine(hasModel)`, tappable into the hub.
  - `NONE` → nothing.
- [ ] **Mode cards** (each = the nav/status-card idiom: `Icon(tint=Primary)` + title + a **status chip** using the `Surface(alpha 0.1f, RoundedCornerShape(8.dp))` chip idiom + `KeyboardArrowRight`):
  - 🎙️ **Dictation** — chip = `dictationChip(engineDisplayName, localModelLabel, liveMode)`; tap → `onNavigateToEnginesVoices` (section 1).
  - 📁 **Transcribe audio file** — chip = `transcriptionEngineChip(engineDisplayName, localModelLabel)`; tap → `onPickAudioFile` (existing batch chain, unchanged).
  - 🔊 **Read-aloud** — chip = `readAloudChip(ttsEngineDisplayName, voiceDisplayName)`; tap → `onNavigateToEnginesVoices` (section 2).
  - Word-for-word is folded into the Dictation chip (there is no separate C4 card) — matches the brief's "only if a distinct selector state exists; else folded in."
- [ ] **Reactive resolution, no polling.** In `HomeScreen`, resolve chip primitives from `PreferencesManager` StateFlows via `collectAsStateWithLifecycle` for `sttProviderId`, `sttLiveMode`, `ttsProviderId`; resolve `engineDisplayName` through `ProviderCatalog.byId(...)?.displayName`, `voiceDisplayName` through `cloudVoiceDisplayName(...)` / `TtsVoices.byId(...)`, and `localModelLabel` from the installed model's tier. **Remove the existing 1000 ms poll** (recon §2, L99-127); take `hasModel` + `hasAnyKey` (keystore) as an `ON_RESUME` snapshot via `produceState` + `Dispatchers.IO`. The brief's "chips read reactively — no polling" is thereby honored.
- [ ] **Nav threading.** `MainActivity`'s `home` composable gains `onNavigateToEnginesVoices = { navController.navigate("engines_voices") }`, alongside the existing `onNavigateToSettings`, `onNavigateToOnboardingModel`, `onNavigateToTranscripts`, `onPickAudioFile`.

> **Self-review:** the batch entry chain (card → `onPickAudioFile` → `audioPickerLauncher` → `queryPickedAudio` → `batch_transcribe`) is reused verbatim — no logic touched. Chips are pure-fn outputs over StateFlow/snapshot state; the poll is gone. Removed HowToUseCard/BYOKInfoCard/LanguageSelectionCard/SetupChecklist are relocated or dropped as pure furniture (no pure fn lives in them).

---

## Task 4 — Two-path first-run chooser (skippable; never blocks)

**Behavior spec.**

- [ ] `PreferencesManager` (additive): expose `setOnboardingCompleted(value: Boolean)` writing the EXISTING `KEY_ONBOARDING_COMPLETED` (recon §5, vestigial today) and a plain reader `onboardingCompleted`. No pure-logic change — a new persisted setter only.
- [ ] `MainActivity` start destination = `firstRunStartDestination(whisperModelManager.installedModel() != null, preferencesManager.onboardingCompleted)` (replaces the L136-143 `installedModel() == null` check). Existing users (model present) → `home`, unchanged.
- [ ] New `FirstRunChooserScreen(onFreeAndPrivate, onBringYourOwnKey, onSkip)`:
  - Two large cards (nav-card idiom): **Free & private** (subtitle: download an on-device model; no speed claim) → sets `onboardingCompleted = true`, then `onFreeAndPrivate` → navigate `onboarding_model` (the EXISTING `OnboardingModelScreen`, unchanged — it still records completion implicitly by writing the model file).
  - **Bring your own key** (subtitle: use your own provider account) → sets `onboardingCompleted = true`, then `onBringYourOwnKey` → navigate `engines_voices` (the hub shows the v3 disclosure before anything is sent).
  - A **Skip** affordance (text button) → sets `onboardingCompleted = true`, `onSkip` → navigate `home`. Lands on Home with the `TWO_PATH` banner. Back press = skip (never blocks).
  - Route `first_run`; `navigate(...)` uses `popUpTo("first_run"){ inclusive = true }` so the chooser leaves no back-stack trap (mirrors the existing onboarding→home pop).
- [ ] `OnboardingModelScreen` is UNCHANGED — it is now the destination of the Free path. Its `onModelReady` still pops to `home`.

> **Self-review:** every path sets `onboardingCompleted = true` up front, so the chooser is shown at most once regardless of which path (or skip) the user takes — a key-only user with no model is never dragged back (the `firstRunStartDestination` `onboardingCompleted` short-circuit, pinned by `skipper_or_key_user_goes_home`). Existing users pinned out by `existing_user_with_model_goes_home`. Nothing blocks: skip is always present and back = skip.

---

## Task 5 — Verify sweep

- [ ] `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio1\jbr"; .\gradlew.bat --no-daemon testDebugUnitTest assembleDebug assembleRelease`
- [ ] **Every pinned suite green UNCHANGED** (import-only edits at most): `CloudProvidersScreenLogicTest`, `TtsVoicePickerLogicTest`, `PreferencesTtsCloudTest`, `EngineSelectionTest`, `BatchCloudGateTest`. If any assertion needed editing → logic moved → **STOP and report** (wave law).
- [ ] New `ModeDashboardLogicTest` green; total = 610 + new tests, 0 failures.
- [ ] `assembleRelease` R8-clean (screenshot-free Compose sanity — the composables compile under R8). Instrumented = compile-check only; **NEVER `connectedAndroidTest`/`installDebug`.**
- [ ] Manual trace (no device): fresh install → `first_run`; pick Free → `onboarding_model`; pick Key → `engines_voices` (disclosure shows); Skip → `home` + TWO_PATH banner; existing user → straight to `home`.

## Constraint → Task → Pin traceability

| Constraint | Task | Pin |
|---|---|---|
| Chip formatters pure, no price/speed | 1 | `dictation_chip_makes_no_speed_claim`; chips carry no `$` |
| Setup banner: noModel && noKey → two-path | 1, 3 | `no_model_no_key_shows_two_path` |
| Hub gathers STT + TTS + keys, gates unchanged | 2 | all §8 pinned suites green UNCHANGED |
| Route rename + back-compat | 2 | `cloud_providers` alias still resolves |
| First run skippable, never blocks, once only | 4 | `skipper_or_key_user_goes_home` |
| Existing users never see chooser | 1, 4 | `existing_user_with_model_goes_home` |
| Chips reactive, no polling | 3 | StateFlow + `ON_RESUME` snapshot; 1000 ms poll removed |
| Bubble untouched | all | `FloatingBubbleService` not in File Structure |

## Commit (named files only)

```
git add "docs/superpowers/plans/2026-07-30-ui-refresh-mode-dashboard.md"
git commit -m "plan: UI refresh — mode dashboard, engines-and-voices hub, two-path first run"
```
