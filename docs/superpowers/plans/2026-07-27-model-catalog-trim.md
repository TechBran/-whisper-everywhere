# Model Catalog Trim Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Cut the whisper catalog to four fast tiers, default to Eco, and migrate existing Extreme/Ultra users off the slow models without ever leaving them unable to dictate.

**Architecture:** The catalog gains a `base` multilingual tier and loses `extreme`/`ultra` from the *pickable* set, but those two remain **resolvable** by id so `installedModel()` never returns null for an existing user. A one-time migration downloads Eco, and only after it verifies does it switch the selection and delete the orphaned file.

**Tech Stack:** Kotlin 2.0.21, JUnit 4, pure-JVM `WhisperCatalog` (no Android), `WhisperModelManager` (Android shell), `DownloadManager`.

## Global Constraints

- **Unit tests run with `unitTests.isReturnDefaultValues = true`** (`app/build.gradle.kts:166`). Keep JVM-unit-tested code free of `android.*`; `WhisperCatalog` already is and must stay that way.
- **`java` is NOT on PATH.** PowerShell: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio1\jbr"`. Use `.\gradlew.bat --no-daemon`.
- **Baseline: 15 suites / 92 tests / 0 failures.**
- **Do NOT run `connectedAndroidTest` or `installDebug`** without the signature preflight in `2026-07-27-tts-diagnostics-release-0.md`. `connectedAndroidTest` uninstalls the app and destroys the user's downloaded models.
- **Never leave a user with no usable model.** Every step below is ordered so that the currently-working model keeps working until its replacement is verified on disk.
- **Do not touch `TtsEngine.kt`, `TtsDiag.kt`, `TtsDiagMath.kt`** (Release 0), or the credential files (Release B Tasks 2-5).

---

## Why

Play Store reviews report transcription being too slow. On-device testing (Fold 6, 2026-07-27) found the **Eco (base.en)** tier fast and accurate enough for real-time use, while the larger tiers are the source of the latency complaints. The catalog currently offers two tiers — `extreme` (medium.en) and `ultra` (large-v3-turbo) — whose size and inference cost actively work against the product's core promise.

**Decisions (owner, 2026-07-27):**
- **D-M1:** Force-migrate existing `extreme`/`ultra` users to Eco on next launch.
- **D-M2:** Default tier changes from `pro` (small.en) to `eco` (base.en).

---

## The hazard this plan is shaped around

`WhisperModelManager.kt:48-51`:

```kotlin
fun installedModel(): WhisperModel? {
    val model = WhisperCatalog.byId(prefs.selectedModelId) ?: return null
    return if (isInstalled(model)) model else null      // isInstalled = FILE EXISTS on disk
}
```

The whole app is gated on `installedModel() != null` (`MainActivity:106`, `HomeScreen:66/80/121/163/884`, `SettingsScreen:67`), and `OnboardingModelScreen` has **no back navigation**.

Two failure modes follow, and both must be designed out:

1. **Deleting a catalog entry** makes `byId` return null for anyone who selected it → gate fails → force-marched into onboarding with no escape, 1.5 GB orphaned on disk.
2. **Switching `selectedModelId` to `eco` before Eco is downloaded** makes `isInstalled` return false → same gate failure. **An offline user would be completely bricked.**

Therefore: entries stay resolvable, and migration is **download-then-swap-then-delete**, in that order, with every stage safe to interrupt.

---

## Target catalog

| id | Display | File | Scope | Status |
|---|---|---|---|---|
| `eco` | Eco (base.en) | `ggml-base.en-q5_1.bin` | ENGLISH | **new default**, pickable |
| `base` | Base multilingual | `ggml-base-q5_1.bin` | MULTILINGUAL | **NEW**, pickable |
| `pro` | Pro (small.en) | `ggml-small.en-q5_1.bin` | ENGLISH | pickable |
| `multi` | Multilingual (small) | `ggml-small-q5_1.bin` | MULTILINGUAL | pickable |
| `extreme` | Extreme (medium.en) | `ggml-medium.en-q5_0.bin` | ENGLISH | **retired** — resolvable, not pickable |
| `ultra` | Ultra (large-v3-turbo) | `ggml-large-v3-turbo-q5_0.bin` | MULTILINGUAL | **retired** — resolvable, not pickable |

### Verified values for the new `base` tier

Fetched from the LFS pointer at the catalog's pinned commit
`5359861c739e955e79d9a303bcbc70fb988958b1` on 2026-07-27:

```
sha256 = 422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898
size   = 59707625
```

**Validated method:** the same fetch against `ggml-base.en-q5_1.bin` returned
`4baf70dd…fdd2f` / `59721011`, matching the existing `SHA256_ECO` and `approxBytes = 59_721_011`
in `WhisperModel.kt:47,63` **exactly**. Do not round `approxBytes` — the in-code comment at
`WhisperModel.kt:61-62` records that rounded values once left a correct download only ~129 KB
inside the ±5% gate.

---

## File Structure

| File | Responsibility |
|---|---|
| `app/src/main/java/com/whispereverywhere/model/WhisperModel.kt` | **Modify.** Add `retired` flag + `base` tier; add `pickable`; change `DEFAULT_MODEL_ID`. |
| `app/src/test/java/com/whispereverywhere/model/WhisperCatalogHelpersTest.kt` | **Modify.** Extend for the new tier, retirement, and default. |
| `app/src/main/java/com/whispereverywhere/model/WhisperModelManager.kt` | **Modify.** Add `retiredInstalledModel()` and `deleteModelFile()`. |
| `app/src/main/java/com/whispereverywhere/model/ModelMigration.kt` | **Create.** Pure migration state machine. No Android. |
| `app/src/test/java/com/whispereverywhere/model/ModelMigrationTest.kt` | **Create.** JVM tests for every migration state. |
| `app/src/main/java/com/whispereverywhere/ui/screens/OnboardingModelScreen.kt` | **Modify.** Show only pickable tiers. |
| `app/src/main/java/com/whispereverywhere/ui/screens/SettingsScreen.kt` | **Modify.** Same, plus the migration card. |

---

## Task 1: Catalog — add `base`, retire `extreme`/`ultra`, default to `eco`

**Files:**
- Modify: `app/src/main/java/com/whispereverywhere/model/WhisperModel.kt`
- Test: `app/src/test/java/com/whispereverywhere/model/WhisperCatalogHelpersTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces, used by Tasks 2-3:
  - `WhisperModel.retired: Boolean` (new field, defaults `false`)
  - `WhisperCatalog.pickable: List<WhisperModel>` — entries where `!retired`
  - `WhisperCatalog.entries` — unchanged meaning: **ALL** entries including retired, so `byId` still resolves
  - `WhisperCatalog.DEFAULT_MODEL_ID` == `"eco"`

- [ ] **Step 1: Write the failing test**

Append to `app/src/test/java/com/whispereverywhere/model/WhisperCatalogHelpersTest.kt` (keep the existing tests; read the file first and match its idiom):

```kotlin
    @Test fun retired_tiers_remain_resolvable_by_id() {
        // The app-wide gate is installedModel() != null, which starts with byId(). If byId
        // returns null for a retired tier, every user on it is force-marched into onboarding
        // with no back navigation and their model file is orphaned. Resolvable forever.
        assertNotNull(WhisperCatalog.byId("extreme"))
        assertNotNull(WhisperCatalog.byId("ultra"))
    }

    @Test fun retired_tiers_are_not_pickable() {
        val ids = WhisperCatalog.pickable.map { it.id }
        assertFalse(ids.contains("extreme"))
        assertFalse(ids.contains("ultra"))
    }

    @Test fun pickable_is_exactly_the_four_fast_tiers() {
        assertEquals(listOf("eco", "base", "pro", "multi"), WhisperCatalog.pickable.map { it.id })
    }

    @Test fun default_is_eco() {
        assertEquals("eco", WhisperCatalog.DEFAULT_MODEL_ID)
        assertNotNull(WhisperCatalog.byId(WhisperCatalog.DEFAULT_MODEL_ID))
    }

    @Test fun default_is_pickable() {
        // A retired default would be unreachable from the picker — an unshippable state.
        assertTrue(WhisperCatalog.pickable.any { it.id == WhisperCatalog.DEFAULT_MODEL_ID })
    }

    @Test fun base_multilingual_tier_has_its_pinned_lfs_values() {
        // Exact LFS byte size and digest, fetched at the catalog's pinned commit. Rounding
        // approxBytes has previously left a correct download barely inside the +/-5% gate.
        val m = WhisperCatalog.byId("base")!!
        assertEquals("ggml-base-q5_1.bin", m.fileName)
        assertEquals(59_707_625L, m.approxBytes)
        assertEquals("422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898", m.sha256)
        assertEquals(ModelScope.MULTILINGUAL, m.scope)
    }

    @Test fun every_entry_has_a_distinct_id_and_filename() {
        assertEquals(WhisperCatalog.entries.size, WhisperCatalog.entries.map { it.id }.toSet().size)
        assertEquals(WhisperCatalog.entries.size, WhisperCatalog.entries.map { it.fileName }.toSet().size)
    }

    @Test fun every_sha256_is_lowercase_hex_of_the_right_length() {
        WhisperCatalog.entries.forEach {
            assertEquals("${it.id} sha256 length", 64, it.sha256.length)
            assertTrue("${it.id} sha256 must be lowercase hex", it.sha256.matches(Regex("[0-9a-f]{64}")))
        }
    }
```

Add whatever imports the file lacks (`assertNotNull`, `assertFalse`, `assertTrue`, `assertEquals`).

- [ ] **Step 2: Run the test to verify it fails**

```bash
.\gradlew.bat :app:testDebugUnitTest --no-daemon --tests "com.whispereverywhere.model.WhisperCatalogHelpersTest"
```
Expected: FAIL — `Unresolved reference: pickable`, and no `base` entry.

- [ ] **Step 3: Add the `retired` field**

In `WhisperModel.kt`, add to the `WhisperModel` data class (after `minRamBytes`), with KDoc:

```kotlin
    /**
     * A tier that is no longer offered but must remain RESOLVABLE. Removing an entry outright
     * makes [WhisperCatalog.byId] return null for anyone who selected it, which makes
     * `installedModel()` return null, which trips the app-wide gate and force-marches that user
     * into onboarding — with their model file orphaned on disk. Retire; never delete.
     */
    val retired: Boolean = false,
```

- [ ] **Step 4: Add the `base` tier and retire the slow ones**

Add the sha256 constant beside the others:

```kotlin
    private const val SHA256_BASE = "422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898"
```

Insert the new entry immediately after the `eco` entry so catalog order matches picker order:

```kotlin
        WhisperModel(
            id = "base",
            displayName = "Base multilingual",
            fileName = "ggml-base-q5_1.bin",
            url = urlFor("ggml-base-q5_1.bin"),
            // Exact LFS byte size at the pinned commit — do NOT round (see the eco note above).
            approxBytes = 59_707_625L,
            sha256 = SHA256_BASE,
            scope = ModelScope.MULTILINGUAL,
            minRamBytes = 0L,
        ),
```

Then add `retired = true,` as the last argument of the existing `extreme` and `ultra` entries. Change nothing else about them — their `sha256`, `approxBytes`, `url` and `fileName` must stay exactly as they are, because an existing installation is verified against those values.

- [ ] **Step 5: Add `pickable` and change the default**

Beside `entries`:

```kotlin
    /** Tiers offered to users. Retired tiers stay in [entries] so byId() keeps resolving them. */
    val pickable: List<WhisperModel> = entries.filter { !it.retired }
```

Then change the default (currently `pro`, `WhisperModel.kt:113`):

The existing declaration is `WhisperModel.kt:113-114`:

```kotlin
    /** Default tier selected on first run (Pro / small.en). */
    const val DEFAULT_MODEL_ID = "pro"
```

Replace with — note **no explicit type annotation**, matching the existing style:

```kotlin
    /**
     * Default tier on first run. Eco (base.en) since 2026-07-27: on-device testing found it fast
     * enough for real-time dictation, and Play reviews cite latency on the larger tiers. It is
     * also a 60 MB first-run download instead of 190 MB.
     */
    const val DEFAULT_MODEL_ID = "eco"
```

- [ ] **Step 6: Run the test to verify it passes**

```bash
.\gradlew.bat :app:testDebugUnitTest --no-daemon --tests "com.whispereverywhere.model.WhisperCatalogHelpersTest"
```
Expected: PASS.

Then the full suite:
```bash
.\gradlew.bat :app:testDebugUnitTest --no-daemon
```
Expected: 92 + 8 new = **100 tests**, 0 failures.

**Two EXISTING assertions will fail, and here is exactly how to fix them.** These are correct
failures — the catalog genuinely changed — but do not improvise the new values.

`app/src/test/java/com/whispereverywhere/model/WhisperCatalogHelpersTest.kt:14-15` currently reads:

```kotlin
        assertEquals(5, WhisperCatalog.entries.size)
        assertEquals(listOf("eco", "pro", "extreme", "multi", "ultra"), ids)
```

Change to (the new entry is inserted after `eco`, per Step 4, and retired tiers REMAIN in
`entries` — that is the whole point of retiring rather than deleting):

```kotlin
        assertEquals(6, WhisperCatalog.entries.size)
        assertEquals(listOf("eco", "base", "pro", "extreme", "multi", "ultra"), ids)
```

Every other existing assertion in that file — the `extreme`/`ultra` scopes, `minRamBytes`,
`approxBytes`, and the `isRecommendedForDevice` cases at `:65-72` — must keep passing **unchanged**.
If any of those break, you have altered a retired entry's data, which you must not do: an existing
installation is verified against exactly those values. Revert and report.

State in your report which existing assertions you changed and why.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/whispereverywhere/model/WhisperModel.kt \
        app/src/test/java/com/whispereverywhere/model/WhisperCatalogHelpersTest.kt
git commit -m "feat(models): add base multilingual, retire medium/large, default to Eco

Play reviews cite transcription latency; on-device testing found Eco
(base.en) fast and accurate enough for real-time dictation while the
medium and large tiers are the source of the complaints.

extreme and ultra are RETIRED, not deleted: byId() must keep resolving
them or installedModel() returns null for anyone using them, which trips
the app-wide gate and force-marches them into onboarding with no back
navigation and a 1.5 GB orphaned file.

base multilingual values are the exact LFS size and digest at the
catalog's pinned commit; the same fetch reproduced eco's existing pinned
values byte-for-byte, validating the method."
```

---

## Task 2: `ModelMigration` — the pure decision logic

**Files:**
- Create: `app/src/main/java/com/whispereverywhere/model/ModelMigration.kt`
- Test: `app/src/test/java/com/whispereverywhere/model/ModelMigrationTest.kt`

**Interfaces:**
- Consumes: `WhisperCatalog` (Task 1).
- Produces, used by Task 3:
  - `ModelMigration.decide(selectedId: String?, selectedInstalled: Boolean, targetInstalled: Boolean, online: Boolean): Action`
  - `sealed interface Action { data object None; data object OfferDownload; data object WaitForNetwork; data class SwapAndDelete(val fromId: String, val toId: String) }`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/whispereverywhere/model/ModelMigrationTest.kt`:

```kotlin
package com.whispereverywhere.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelMigrationTest {

    private fun decide(
        selectedId: String?,
        selectedInstalled: Boolean = true,
        targetInstalled: Boolean = false,
        online: Boolean = true,
    ) = ModelMigration.decide(selectedId, selectedInstalled, targetInstalled, online)

    @Test fun a_current_tier_needs_no_migration() {
        assertEquals(ModelMigration.Action.None, decide("pro"))
        assertEquals(ModelMigration.Action.None, decide("eco"))
    }

    @Test fun no_selection_needs_no_migration() {
        // First run. Onboarding handles this; migration must not interfere.
        assertEquals(ModelMigration.Action.None, decide(null))
    }

    @Test fun an_unknown_id_needs_no_migration() {
        // Downgrade from a future version. Onboarding will handle it; do not delete anything.
        assertEquals(ModelMigration.Action.None, decide("some-future-tier"))
    }

    @Test fun a_retired_tier_online_without_the_target_offers_the_download() {
        assertEquals(ModelMigration.Action.OfferDownload, decide("ultra"))
        assertEquals(ModelMigration.Action.OfferDownload, decide("extreme"))
    }

    @Test fun a_retired_tier_offline_waits_and_keeps_the_old_model() {
        // THE load-bearing case. Deleting or switching here would leave an offline user with no
        // usable model and no way to get one — the app gate would dump them into onboarding.
        assertEquals(ModelMigration.Action.WaitForNetwork, decide("ultra", online = false))
    }

    @Test fun swap_only_happens_once_the_target_is_actually_on_disk() {
        assertEquals(
            ModelMigration.Action.SwapAndDelete("ultra", "eco"),
            decide("ultra", targetInstalled = true),
        )
    }

    @Test fun swap_happens_offline_too_once_the_target_is_installed() {
        // No network needed to swap a file that is already downloaded.
        assertEquals(
            ModelMigration.Action.SwapAndDelete("extreme", "eco"),
            decide("extreme", targetInstalled = true, online = false),
        )
    }

    @Test fun a_retired_tier_whose_file_is_already_gone_still_offers_the_download() {
        // User cleared storage. Nothing to delete, but they still need a working model.
        assertEquals(ModelMigration.Action.OfferDownload, decide("ultra", selectedInstalled = false))
    }

    @Test fun migration_target_is_the_catalog_default() {
        val a = decide("ultra", targetInstalled = true) as ModelMigration.Action.SwapAndDelete
        assertEquals(WhisperCatalog.DEFAULT_MODEL_ID, a.toId)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
.\gradlew.bat :app:testDebugUnitTest --no-daemon --tests "com.whispereverywhere.model.ModelMigrationTest"
```
Expected: FAIL — `Unresolved reference: ModelMigration`.

- [ ] **Step 3: Write the implementation**

Create `app/src/main/java/com/whispereverywhere/model/ModelMigration.kt`:

```kotlin
package com.whispereverywhere.model

/**
 * Decides how to move a user off a retired model tier. Pure and Android-free so every state is
 * unit-testable — this logic decides whether a shipped user can still dictate, so it must not be
 * reachable only through a device.
 *
 * ORDERING IS THE WHOLE POINT: download the replacement, verify it on disk, THEN switch the
 * selection, THEN delete the old file. Any other order can leave a user with no usable model,
 * because `installedModel()` requires the selected tier's file to exist and the entire app is
 * gated on that being non-null — with onboarding offering no way back.
 */
object ModelMigration {

    sealed interface Action {
        /** Nothing to do: current tier, no selection, or an id this build does not know. */
        data object None : Action
        /** On a retired tier, online, replacement not yet downloaded. */
        data object OfferDownload : Action
        /** On a retired tier but offline. KEEP the old model working and retry later. */
        data object WaitForNetwork : Action
        /** Replacement verified on disk. Safe to switch and reclaim the old file. */
        data class SwapAndDelete(val fromId: String, val toId: String) : Action
    }

    fun decide(
        selectedId: String?,
        selectedInstalled: Boolean,
        targetInstalled: Boolean,
        online: Boolean,
    ): Action {
        val selected = selectedId?.let { WhisperCatalog.byId(it) } ?: return Action.None
        if (!selected.retired) return Action.None
        val target = WhisperCatalog.DEFAULT_MODEL_ID
        // Target on disk wins regardless of connectivity — nothing left to download.
        if (targetInstalled) return Action.SwapAndDelete(selected.id, target)
        return if (online) Action.OfferDownload else Action.WaitForNetwork
    }
}
```

Note `selectedInstalled` is accepted but not branched on: whether the old file is present changes only what there is to delete, never the decision. Keep the parameter — Task 3 uses it to skip a no-op delete, and the test pins the behaviour.

- [ ] **Step 4: Run to verify it passes**

```bash
.\gradlew.bat :app:testDebugUnitTest --no-daemon --tests "com.whispereverywhere.model.ModelMigrationTest"
```
Expected: PASS, 9 tests. Then the full suite: **109 tests**, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/whispereverywhere/model/ModelMigration.kt \
        app/src/test/java/com/whispereverywhere/model/ModelMigrationTest.kt
git commit -m "feat(models): ModelMigration — pure retired-tier migration logic

Ordering is the whole point: download, verify on disk, switch selection,
then delete. Any other order can leave a user unable to dictate, because
installedModel() requires the selected tier's file to exist and the whole
app is gated on it with no way back out of onboarding.

The offline case is explicit: keep the old model working and retry, never
switch or delete."
```

---

## Task 3: Wire the migration and hide retired tiers from the UI

**Files:**
- Modify: `app/src/main/java/com/whispereverywhere/model/WhisperModelManager.kt`
- Modify: `app/src/main/java/com/whispereverywhere/ui/screens/OnboardingModelScreen.kt`
- Modify: `app/src/main/java/com/whispereverywhere/ui/screens/SettingsScreen.kt`

**Interfaces:**
- Consumes: `WhisperCatalog.pickable`, `ModelMigration.decide` (Tasks 1-2).
- Produces: end of this plan.

- [ ] **Step 1: Add the manager helpers**

In `WhisperModelManager.kt`, after `installedModel()`:

```kotlin
    /**
     * The selected tier when it is RETIRED — i.e. still resolvable and possibly still installed,
     * but no longer offered. Drives the migration prompt. Returns null in the normal case.
     */
    fun retiredInstalledModel(): WhisperModel? {
        val model = WhisperCatalog.byId(prefs.selectedModelId) ?: return null
        return if (model.retired) model else null
    }

    /** Delete a tier's file from disk. Returns true if a file was actually removed. */
    fun deleteModelFile(model: WhisperModel): Boolean {
        val f = fileFor(model)
        return f.exists() && f.delete()
    }
```

- [ ] **Step 2: Show only pickable tiers**

In `OnboardingModelScreen.kt` and `SettingsScreen.kt`, find where the model list is rendered — it currently iterates `WhisperCatalog.entries` or `manager.catalog`. Change those iterations to `WhisperCatalog.pickable`.

**Read both files first and change only the list source.** Do not restyle, reorder, or otherwise touch the screens; they are shipped UI.

Grep to confirm you found every site:
```bash
grep -rn "WhisperCatalog.entries\|manager.catalog\|\.catalog" app/src/main/java/com/whispereverywhere/ui/
```
Every hit that feeds a user-facing picker must use `pickable`. `entries` may legitimately remain where the code needs *all* tiers (e.g. resolving an id) — judge each site and list your reasoning in the report.

- [ ] **Step 3: Add the migration card to Settings**

In the speech-model section of `SettingsScreen.kt`, above the existing model picker, add a card shown only when `retiredInstalledModel() != null`. Match the surrounding Compose idiom — read the file and reuse whatever card/section composable the screen already uses rather than introducing a new style.

Copy, verbatim:

- Title: **"This model is no longer supported"**
- Body: **"Eco is much faster and works well for everyday dictation. We'll download it (60 MB), then free up the space your old model is using."**
- Primary action: **"Switch to Eco"**
- When offline: replace the action with the text **"Connect to the internet to switch."**

On tap, run the flow in Step 4.

- [ ] **Step 4: Implement the flow**

```
action = ModelMigration.decide(
    selectedId       = prefs.selectedModelId,
    selectedInstalled= retiredModel?.let { isInstalled(it) } ?: false,
    targetInstalled  = isInstalled(WhisperCatalog.byId(WhisperCatalog.DEFAULT_MODEL_ID)!!),
    online           = <connectivity check>,
)
```

- `None` → render nothing.
- `WaitForNetwork` → show the card with the offline text. **Change nothing on disk.**
- `OfferDownload` → on tap, download Eco through the existing `WhisperModelManager` download path (same size-gate + sha256 verification as any other tier — do not bypass it). Leave `selectedModelId` pointing at the old tier for the whole download, so the app keeps working if it fails or is interrupted.
- `SwapAndDelete(from, to)` → set `prefs.selectedModelId = to` **first**, then `deleteModelFile(byId(from)!!)`. In that order: if the process dies between them the user has a working Eco and a stale file (recoverable), not a dangling selection (bricked).

Re-evaluate `decide(...)` after the download completes so the flow lands on `SwapAndDelete` naturally rather than being sequenced by hand.

- [ ] **Step 5: Verify**

```bash
.\gradlew.bat :app:assembleDebug --no-daemon
.\gradlew.bat :app:testDebugUnitTest --no-daemon
.\gradlew.bat :app:assembleRelease --no-daemon
```
Expected: all green; 109 tests, 0 failures.

Then prove no picker still offers a retired tier:
```bash
grep -rn "WhisperCatalog.entries" app/src/main/java/com/whispereverywhere/ui/ || echo "OK: no UI reads the full entry list"
```

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/whispereverywhere/model/WhisperModelManager.kt \
        app/src/main/java/com/whispereverywhere/ui/screens/OnboardingModelScreen.kt \
        app/src/main/java/com/whispereverywhere/ui/screens/SettingsScreen.kt
git commit -m "feat(models): migrate retired tiers to Eco; hide them from the pickers

Pickers now read WhisperCatalog.pickable, so nobody new can choose a
retired tier, while byId() still resolves them so existing users keep
working.

The migration downloads Eco first and only switches the selection once
the file verifies on disk, then deletes the old model. Offline users keep
their existing model and are told to reconnect — switching or deleting
first would leave them with no usable model and no route out of
onboarding."
```

---

## Task 4: On-device verification

**Files:** none.

This plan changes what a shipped user sees on launch. The migration cannot be proven by unit tests alone — the decision logic is covered, but the download-verify-swap-delete sequence touches real files.

- [ ] **Step 1: Signature preflight, then install**

Run the preflight from `2026-07-27-tts-diagnostics-release-0.md`. Then `adb install -r` the debug APK — **never** `connectedAndroidTest`, which uninstalls and destroys the user's models.

- [ ] **Step 2: Verify the normal path is untouched**

The test device has `ggml-small-q5_1.bin` (`multi`) installed. `multi` is **not** retired, so:
- No migration card appears.
- Dictation works exactly as before.

If a card appears here, the `retired` flag is set on the wrong entries — stop and fix.

- [ ] **Step 3: Verify the migration path**

Simulate a retired selection without downloading 1.5 GB:
```bash
adb shell run-as com.whispereverywhere \
  sh -c "sed -i 's/\"multi\"/\"ultra\"/' shared_prefs/whisper_everywhere_prefs.xml"
```
(Confirm the actual pref file name and key first by reading it: `adb shell run-as com.whispereverywhere cat shared_prefs/whisper_everywhere_prefs.xml`.)

Then relaunch and confirm:
- The migration card appears with the specified copy.
- **The app still works** — the gate did not trip, because `byId("ultra")` still resolves.
- Airplane mode → the card shows the offline text and nothing is deleted.
- Back online → "Switch to Eco" downloads 60 MB, then the selection flips and the card disappears.

- [ ] **Step 4: Restore the device**

Set the selection back to the tier the user actually wants and confirm dictation works. Report what was left installed.
