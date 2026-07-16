# Plan 2: Model Onboarding & Settings UI (Implementation)

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** In-app tiered model download onboarding + a Settings speech-model section, making the on-device app end-to-end runnable (download a model, then transcribe).

**Architecture:** A `ModelDownloadViewModel` drives `WhisperModelManager.download`; `OnboardingModelScreen` (Compose) picks a tier with device-RAM gating; navigation gates first-run when no model is installed; Settings gains a speech-model section; the OpenAI API-key UI is hidden (on-device only).

**Tech Stack:** Kotlin, Jetpack Compose + Material3, `WhisperModelManager` (Plan 1), Android DownloadManager.

## Global Constraints
- Package `com.whispereverywhere`; Compose + Material3; minSdk 26, targetSdk 35.
- Consume Plan 1's `WhisperModelManager` / `WhisperCatalog` / `WhisperModel` - do not reimplement.
- Build/verify: `.\gradlew.bat assembleDebug` with a FULL JDK 17 as JAVA_HOME (the AS JBR lacks jmods; jdk at `D:\gemma-inference\jdk17\jdk-17.0.19+10`).
- No API key is required for transcription (on-device only); keep the encrypted storage, hide the UI.
- RAM-gate Ultra/Extreme via `WhisperModelManager.isRecommendedForDevice`.
- PreferencesManager already exposes `var selectedModelId: String?` and `var onboardingCompleted: Boolean`.

---

### Task 1: Onboarding model-download screen + ModelDownloadViewModel

**Files:**
- Create: `app/src/main/java/com/whispereverywhere/util/ByteFormat.kt`
- Create: `app/src/main/java/com/whispereverywhere/ui/onboarding/ModelDownloadViewModel.kt`
- Create: `app/src/main/java/com/whispereverywhere/ui/screens/OnboardingModelScreen.kt`
- Test: `app/src/test/java/com/whispereverywhere/util/ByteFormatTest.kt`

**Interfaces:**
- Consumes (Plan 1, already in repo — do NOT recreate):
  - `com.whispereverywhere.model.WhisperModel(id, displayName, fileName, url, approxBytes: Long, sha256, scope: ModelScope, minRamBytes: Long)`, `enum ModelScope { ENGLISH, MULTILINGUAL }`
  - `com.whispereverywhere.model.WhisperCatalog` — `entries: List<WhisperModel>`, `const DEFAULT_MODEL_ID = "pro"`, `byId(id)`, `isRecommendedForDevice(model, totalRamBytes)`
  - `com.whispereverywhere.model.WhisperModelManager` — `catalog: List<WhisperModel>`, `isRecommendedForDevice(model): Boolean`, `suspend download(model, onProgress: (soFar: Long, total: Long) -> Unit)`, nested `class ModelDownloadException(message: String) : Exception`
  - `com.whispereverywhere.data.local.PreferencesManager` — `var selectedModelId: String?`, `var onboardingCompleted: Boolean`
  - `com.whispereverywhere.WhisperEverywhereApp.getInstance()` — `whisperModelManager`, `preferencesManager`
  - Theme colors from `ui.theme`: `Primary`, `Success`, `Warning`, `Error`, `OnPrimary`
- Produces:
  - `com.whispereverywhere.util.formatBytes(bytes: Long): String` — pure, unit-tested
  - `ModelDownloadViewModel` exposing `state: StateFlow<DownloadState>` (`Idle` / `Downloading(pct, soFar, total)` / `Done(modelId)` / `Error(msg)`) and `download(model)` / `reset()`
  - `@Composable OnboardingModelScreen(onModelReady: () -> Unit, viewModel: ModelDownloadViewModel = viewModel())`

- [ ] **Step 1: Add the pure `formatBytes` helper.**
  Create `app/src/main/java/com/whispereverywhere/util/ByteFormat.kt`. Pure (no Android imports) so it is JVM-unit-testable. Base-1000 (matches how download sizes are advertised in the catalog/spec, e.g. `190_000_000L` → "190 MB"):
  ```kotlin
  package com.whispereverywhere.util

  import java.util.Locale
  import kotlin.math.abs

  /**
   * Human-readable, base-1000 (SI) size string, e.g. 57_000_000 -> "57 MB",
   * 190_000_000 -> "190 MB", 574_000_000 -> "574 MB", 1_500_000_000 -> "1.5 GB".
   * Bytes/KB render as whole numbers; MB/GB/TB keep one decimal only when it is
   * non-zero. Negative inputs are clamped to 0 B. Pure — safe for JVM unit tests.
   */
  fun formatBytes(bytes: Long): String {
      if (bytes <= 0L) return "0 B"
      val units = arrayOf("B", "KB", "MB", "GB", "TB")
      var value = bytes.toDouble()
      var unitIndex = 0
      while (value >= 1000.0 && unitIndex < units.size - 1) {
          value /= 1000.0
          unitIndex++
      }
      // B and KB: no decimals. MB and up: one decimal, trimmed when it's .0
      val text = if (unitIndex <= 1) {
          String.format(Locale.US, "%.0f", value)
      } else {
          val oneDp = String.format(Locale.US, "%.1f", value)
          if (oneDp.endsWith(".0")) oneDp.dropLast(2) else oneDp
      }
      return "$text ${units[unitIndex]}"
  }
  ```
  Note: `abs` import is unused — omit it; final import block is only `java.util.Locale`.

- [ ] **Step 2: Add the JUnit4 test for `formatBytes`.**
  Create `app/src/test/java/com/whispereverywhere/util/ByteFormatTest.kt`:
  ```kotlin
  package com.whispereverywhere.util

  import org.junit.Assert.assertEquals
  import org.junit.Test

  class ByteFormatTest {

      @Test
      fun zeroAndNegativeClampToZeroBytes() {
          assertEquals("0 B", formatBytes(0L))
          assertEquals("0 B", formatBytes(-1L))
          assertEquals("0 B", formatBytes(-1_000_000L))
      }

      @Test
      fun bytesAndKilobytesHaveNoDecimals() {
          assertEquals("512 B", formatBytes(512L))
          assertEquals("999 B", formatBytes(999L))
          assertEquals("1 KB", formatBytes(1_000L))
          assertEquals("57 KB", formatBytes(57_000L))
      }

      @Test
      fun catalogSizesRenderAsWholeMegabytes() {
          assertEquals("57 MB", formatBytes(57_000_000L))
          assertEquals("190 MB", formatBytes(190_000_000L))
          assertEquals("539 MB", formatBytes(539_000_000L))
          assertEquals("574 MB", formatBytes(574_000_000L))
      }

      @Test
      fun megabytesKeepOneDecimalOnlyWhenNonZero() {
          assertEquals("1.5 MB", formatBytes(1_500_000L))
          assertEquals("2.3 MB", formatBytes(2_300_000L))
          assertEquals("1 MB", formatBytes(1_000_000L))
      }

      @Test
      fun gigabytesRollOverPastAThousandMegabytes() {
          assertEquals("1.5 GB", formatBytes(1_500_000_000L))
          assertEquals("2 GB", formatBytes(2_000_000_000L))
      }

      @Test
      fun terabytesAreTheLargestUnit() {
          assertEquals("1 TB", formatBytes(1_000_000_000_000L))
          assertEquals("1.2 TB", formatBytes(1_200_000_000_000L))
      }
  }
  ```

- [ ] **Step 3: Create `ModelDownloadViewModel`.**
  Create `app/src/main/java/com/whispereverywhere/ui/onboarding/ModelDownloadViewModel.kt`. `AndroidViewModel` so it can reach `WhisperEverywhereApp` for the manager + prefs. On success it sets `prefs.selectedModelId = model.id` and marks onboarding complete, catching `WhisperModelManager.ModelDownloadException` (and any other throwable, so an unexpected error never leaks uncaught) into `Error`:
  ```kotlin
  package com.whispereverywhere.ui.onboarding

  import android.app.Application
  import androidx.lifecycle.AndroidViewModel
  import androidx.lifecycle.viewModelScope
  import com.whispereverywhere.WhisperEverywhereApp
  import com.whispereverywhere.model.WhisperModel
  import com.whispereverywhere.model.WhisperModelManager
  import kotlinx.coroutines.flow.MutableStateFlow
  import kotlinx.coroutines.flow.StateFlow
  import kotlinx.coroutines.flow.asStateFlow
  import kotlinx.coroutines.launch

  /**
   * Drives a single model download for the onboarding wizard.
   *
   * State machine: Idle -> Downloading(pct,...) -> Done(modelId) | Error(msg).
   * On success it persists the chosen tier (prefs.selectedModelId) and marks
   * onboarding complete, so the first-run gate won't reappear.
   */
  class ModelDownloadViewModel(app: Application) : AndroidViewModel(app) {

      sealed interface DownloadState {
          data object Idle : DownloadState
          data class Downloading(val pct: Int, val soFar: Long, val total: Long) : DownloadState
          data class Done(val modelId: String) : DownloadState
          data class Error(val message: String) : DownloadState
      }

      private val appInstance: WhisperEverywhereApp = getApplication()
      private val manager: WhisperModelManager get() = appInstance.whisperModelManager
      private val prefs get() = appInstance.preferencesManager

      private val _state = MutableStateFlow<DownloadState>(DownloadState.Idle)
      val state: StateFlow<DownloadState> = _state.asStateFlow()

      /** Kick off (or retry) the download of [model]. Ignored if one is already running. */
      fun download(model: WhisperModel) {
          if (_state.value is DownloadState.Downloading) return
          _state.value = DownloadState.Downloading(pct = 0, soFar = 0L, total = model.approxBytes)
          viewModelScope.launch {
              try {
                  manager.download(model) { soFar, total ->
                      val safeTotal = if (total > 0L) total else model.approxBytes
                      val pct = if (safeTotal > 0L) {
                          ((soFar.toDouble() / safeTotal.toDouble()) * 100.0)
                              .toInt().coerceIn(0, 100)
                      } else 0
                      _state.value = DownloadState.Downloading(pct, soFar, safeTotal)
                  }
                  // Success: persist the choice and clear the first-run gate.
                  prefs.selectedModelId = model.id
                  prefs.onboardingCompleted = true
                  _state.value = DownloadState.Done(model.id)
              } catch (e: WhisperModelManager.ModelDownloadException) {
                  _state.value = DownloadState.Error(e.message ?: "Download failed")
              } catch (e: Exception) {
                  _state.value = DownloadState.Error(e.message ?: "Unexpected error")
              }
          }
      }

      /** Reset back to Idle (e.g. after dismissing an error before retry). */
      fun reset() {
          if (_state.value !is DownloadState.Downloading) {
              _state.value = DownloadState.Idle
          }
      }
  }
  ```

- [ ] **Step 4: Create `OnboardingModelScreen`.**
  Create `app/src/main/java/com/whispereverywhere/ui/screens/OnboardingModelScreen.kt`. Scrollable list of tier cards from `app.whisperModelManager.catalog`; each shows `displayName`, `formatBytes(model.approxBytes)`, scope, a "Recommended for your device" badge when `manager.isRecommendedForDevice(model)`, and a "High-end devices only" note for RAM-gated tiers (`minRamBytes > 0`) when NOT recommended. Tap a card -> `viewModel.download(model)`. While `Downloading`, show a `LinearProgressIndicator` + percent (bound to the tapped tier). On `Error`, an error row with a Retry button. On `Done`, fire `onModelReady()` via `LaunchedEffect`. Default-highlight `WhisperCatalog.DEFAULT_MODEL_ID` ("pro"). Match HomeScreen's Material3 card style (`Card` + `CardDefaults`, theme `Primary`/`Success`/`Warning`/`Error`, `RoundedCornerShape`, `Surface` pill badges):
  ```kotlin
  package com.whispereverywhere.ui.screens

  import androidx.compose.foundation.BorderStroke
  import androidx.compose.foundation.background
  import androidx.compose.foundation.clickable
  import androidx.compose.foundation.layout.*
  import androidx.compose.foundation.rememberScrollState
  import androidx.compose.foundation.shape.RoundedCornerShape
  import androidx.compose.foundation.verticalScroll
  import androidx.compose.material.icons.Icons
  import androidx.compose.material.icons.filled.CheckCircle
  import androidx.compose.material.icons.filled.CloudDownload
  import androidx.compose.material.icons.filled.ErrorOutline
  import androidx.compose.material.icons.filled.Language
  import androidx.compose.material.icons.filled.Verified
  import androidx.compose.material3.*
  import androidx.compose.runtime.*
  import androidx.compose.ui.Alignment
  import androidx.compose.ui.Modifier
  import androidx.compose.ui.draw.clip
  import androidx.compose.ui.text.font.FontWeight
  import androidx.compose.ui.text.style.TextAlign
  import androidx.compose.ui.unit.dp
  import androidx.lifecycle.viewmodel.compose.viewModel
  import com.whispereverywhere.WhisperEverywhereApp
  import com.whispereverywhere.model.ModelScope
  import com.whispereverywhere.model.WhisperCatalog
  import com.whispereverywhere.model.WhisperModel
  import com.whispereverywhere.ui.onboarding.ModelDownloadViewModel
  import com.whispereverywhere.ui.onboarding.ModelDownloadViewModel.DownloadState
  import com.whispereverywhere.ui.theme.OnPrimary
  import com.whispereverywhere.ui.theme.Primary
  import com.whispereverywhere.ui.theme.Success
  import com.whispereverywhere.ui.theme.Warning
  import com.whispereverywhere.util.formatBytes

  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  fun OnboardingModelScreen(
      onModelReady: () -> Unit,
      viewModel: ModelDownloadViewModel = viewModel()
  ) {
      val app = WhisperEverywhereApp.getInstance()
      val manager = app.whisperModelManager
      val models = manager.catalog

      val state by viewModel.state.collectAsState()

      // The tier the user tapped (drives which card shows progress / error).
      var activeModelId by remember { mutableStateOf<String?>(null) }

      // Fire the ready callback exactly once when the download completes.
      LaunchedEffect(state) {
          val s = state
          if (s is DownloadState.Done) {
              onModelReady()
          }
      }

      val scrollState = rememberScrollState()

      Scaffold(
          topBar = {
              TopAppBar(
                  title = { Text("Choose a Speech Model", fontWeight = FontWeight.Bold) },
                  colors = TopAppBarDefaults.topAppBarColors(
                      containerColor = MaterialTheme.colorScheme.primary,
                      titleContentColor = MaterialTheme.colorScheme.onPrimary
                  )
              )
          }
      ) { paddingValues ->
          Column(
              modifier = Modifier
                  .fillMaxSize()
                  .padding(paddingValues)
                  .verticalScroll(scrollState)
                  .padding(16.dp)
          ) {
              Text(
                  text = "Speech runs 100% on your device. Pick a model to download once " +
                      "— it works offline afterward, and no audio ever leaves your phone.",
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant
              )

              Spacer(modifier = Modifier.height(16.dp))

              models.forEach { model ->
                  val recommended = manager.isRecommendedForDevice(model)
                  val isDefault = model.id == WhisperCatalog.DEFAULT_MODEL_ID
                  val isActive = activeModelId == model.id

                  ModelTierCard(
                      model = model,
                      recommended = recommended,
                      isDefault = isDefault,
                      state = if (isActive) state else DownloadState.Idle,
                      onSelect = {
                          activeModelId = model.id
                          viewModel.download(model)
                      },
                      onRetry = {
                          activeModelId = model.id
                          viewModel.download(model)
                      }
                  )

                  Spacer(modifier = Modifier.height(12.dp))
              }

              Spacer(modifier = Modifier.height(8.dp))
          }
      }
  }

  @Composable
  private fun ModelTierCard(
      model: WhisperModel,
      recommended: Boolean,
      isDefault: Boolean,
      state: DownloadState,
      onSelect: () -> Unit,
      onRetry: () -> Unit
  ) {
      val downloading = state as? DownloadState.Downloading
      val error = state as? DownloadState.Error
      val done = state is DownloadState.Done
      val isBusy = downloading != null

      val ramGated = model.minRamBytes > 0L

      val borderColor = when {
          done -> Success
          isDefault -> Primary
          else -> MaterialTheme.colorScheme.outline
      }

      Card(
          modifier = Modifier
              .fillMaxWidth()
              .then(
                  if (!isBusy) Modifier.clickable(onClick = onSelect) else Modifier
              ),
          colors = CardDefaults.cardColors(
              containerColor = if (isDefault)
                  Primary.copy(alpha = 0.05f)
              else
                  MaterialTheme.colorScheme.surface
          ),
          border = BorderStroke(if (isDefault || done) 2.dp else 1.dp, borderColor),
          elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
      ) {
          Column(modifier = Modifier.padding(16.dp)) {

              // Title row: name + size
              Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = Alignment.CenterVertically
              ) {
                  Text(
                      text = model.displayName,
                      style = MaterialTheme.typography.titleMedium,
                      fontWeight = FontWeight.SemiBold,
                      modifier = Modifier.weight(1f)
                  )
                  Text(
                      text = formatBytes(model.approxBytes),
                      style = MaterialTheme.typography.bodyMedium,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                      fontWeight = FontWeight.Medium
                  )
              }

              Spacer(modifier = Modifier.height(6.dp))

              // Scope row
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

              // Badges
              Spacer(modifier = Modifier.height(8.dp))
              Row(
                  horizontalArrangement = Arrangement.spacedBy(8.dp),
                  verticalAlignment = Alignment.CenterVertically
              ) {
                  if (isDefault) {
                      TierBadge(text = "Default", color = Primary)
                  }
                  if (recommended) {
                      TierBadge(text = "Recommended for your device", color = Success)
                  }
              }

              // High-end-only note for RAM-gated tiers the device can't recommend.
              if (ramGated && !recommended) {
                  Spacer(modifier = Modifier.height(8.dp))
                  Surface(
                      color = Warning.copy(alpha = 0.12f),
                      shape = RoundedCornerShape(8.dp)
                  ) {
                      Text(
                          text = "High-end devices only — this tier needs more RAM than " +
                              "this device reports. You can still pick it, but performance " +
                              "may suffer.",
                          modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                          style = MaterialTheme.typography.bodySmall,
                          color = Warning
                      )
                  }
              }

              // Progress / error / action area
              when {
                  downloading != null -> {
                      Spacer(modifier = Modifier.height(12.dp))
                      LinearProgressIndicator(
                          progress = downloading.pct / 100f,
                          modifier = Modifier
                              .fillMaxWidth()
                              .clip(RoundedCornerShape(4.dp)),
                          color = Primary
                      )
                      Spacer(modifier = Modifier.height(6.dp))
                      Text(
                          text = "Downloading… ${downloading.pct}%  " +
                              "(${formatBytes(downloading.soFar)} / ${formatBytes(downloading.total)})",
                          style = MaterialTheme.typography.bodySmall,
                          color = MaterialTheme.colorScheme.onSurfaceVariant
                      )
                  }

                  error != null -> {
                      Spacer(modifier = Modifier.height(12.dp))
                      Row(
                          modifier = Modifier.fillMaxWidth(),
                          verticalAlignment = Alignment.CenterVertically
                      ) {
                          Icon(
                              Icons.Filled.ErrorOutline,
                              contentDescription = null,
                              tint = MaterialTheme.colorScheme.error,
                              modifier = Modifier.size(20.dp)
                          )
                          Spacer(modifier = Modifier.width(8.dp))
                          Text(
                              text = error.message,
                              style = MaterialTheme.typography.bodySmall,
                              color = MaterialTheme.colorScheme.error,
                              modifier = Modifier.weight(1f)
                          )
                          TextButton(onClick = onRetry) {
                              Text("Retry")
                          }
                      }
                  }

                  done -> {
                      Spacer(modifier = Modifier.height(12.dp))
                      Row(verticalAlignment = Alignment.CenterVertically) {
                          Icon(
                              Icons.Filled.CheckCircle,
                              contentDescription = null,
                              tint = Success,
                              modifier = Modifier.size(20.dp)
                          )
                          Spacer(modifier = Modifier.width(8.dp))
                          Text(
                              text = "Ready to use",
                              style = MaterialTheme.typography.bodyMedium,
                              color = Success,
                              fontWeight = FontWeight.Medium
                          )
                      }
                  }

                  else -> {
                      Spacer(modifier = Modifier.height(12.dp))
                      Button(
                          onClick = onSelect,
                          modifier = Modifier.fillMaxWidth()
                      ) {
                          Icon(
                              Icons.Filled.CloudDownload,
                              contentDescription = null,
                              modifier = Modifier.size(18.dp)
                          )
                          Spacer(modifier = Modifier.width(8.dp))
                          Text("Download")
                      }
                  }
              }
          }
      }
  }

  @Composable
  private fun TierBadge(text: String, color: androidx.compose.ui.graphics.Color) {
      Surface(
          color = color.copy(alpha = 0.12f),
          shape = RoundedCornerShape(8.dp)
      ) {
          Row(
              modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
              verticalAlignment = Alignment.CenterVertically
          ) {
              Icon(
                  Icons.Filled.Verified,
                  contentDescription = null,
                  tint = color,
                  modifier = Modifier.size(14.dp)
              )
              Spacer(modifier = Modifier.width(4.dp))
              Text(
                  text = text,
                  style = MaterialTheme.typography.labelSmall,
                  color = color,
                  fontWeight = FontWeight.Bold
              )
          }
      }
  }
  ```
  Note: `background`, `OnPrimary`, and `TextAlign` are imported for parity with HomeScreen but if the compiler flags them as unused warnings that is acceptable (warnings don't fail the build); drop them only if a strict lint gate is configured. The load-bearing detail: `LinearProgressIndicator(progress = downloading.pct / 100f, ...)` uses the Material3 2023.10.01 BOM signature (a `Float`, not a lambda).

- [ ] **Step 5: BUILD check + unit test.**
  From the project root run `.\gradlew.bat testDebugUnitTest` (must compile + pass `ByteFormatTest`) and `.\gradlew.bat assembleDebug` (must succeed). Both must be green before this task is considered done. If `LinearProgressIndicator`'s `progress` parameter is reported as deprecated/ambiguous for the pinned Compose BOM, keep the `Float` overload (the lambda overload only exists in newer BOMs); the `@Suppress("DEPRECATION")` annotation on the composable is acceptable if a deprecation-as-error flag is set.

---

### Task 2: Onboarding navigation gating + HomeScreen checklist row

**Files:**
- Modify: `C:\Users\bastr\OneDrive\Desktop\whisper Everywhere\app\src\main\java\com\whispereverywhere\MainActivity.kt`
- Modify: `C:\Users\bastr\OneDrive\Desktop\whisper Everywhere\app\src\main\java\com\whispereverywhere\ui\screens\HomeScreen.kt`

**Interfaces:**
- Consumes (from Task 1): `OnboardingModelScreen(onModelReady: () -> Unit)` in package `com.whispereverywhere.ui.screens`, and `ModelDownloadViewModel` (constructed inside `OnboardingModelScreen`, no wiring needed here).
- Consumes (from Plan 1): `WhisperEverywhereApp.getInstance().whisperModelManager.installedModel(): WhisperModel?`.
- Produces: NavHost route `"onboarding_model"`; launch-time start-destination gate; a "Speech model" `SetupChecklist` row driven by a new `hasSpeechModel: Boolean` parameter.

---

- [ ] **Step 1: Add the `onboarding_model` route + launch-time start-destination gate in `MainActivity.kt`.**

  In `MainActivity.kt`, add the import for the onboarding screen (grouped with the existing screen imports, alphabetically after `LegalDocumentScreen`):

  ```kotlin
  import com.whispereverywhere.ui.screens.OnboardingModelScreen
  ```

  Replace the entire `WhisperEverywhereNavigation()` composable (lines 80-130) with the version below. The start destination is computed **once at launch** from `installedModel()`; `onModelReady` navigates to `"home"` and pops `"onboarding_model"` off the back stack so the user cannot swipe back into onboarding. All four existing routes (`home`, `settings`, `privacy_policy`, `terms_of_service`) are kept byte-for-byte identical.

  ```kotlin
  @Composable
  fun WhisperEverywhereNavigation() {
      val navController = rememberNavController()

      // Compute the start destination once, at launch: if there is no installed
      // speech model, gate the app behind the model-download onboarding wizard.
      val startDestination = remember {
          val app = WhisperEverywhereApp.getInstance()
          if (app.whisperModelManager.installedModel() == null) {
              "onboarding_model"
          } else {
              "home"
          }
      }

      NavHost(
          navController = navController,
          startDestination = startDestination
      ) {
          composable("onboarding_model") {
              OnboardingModelScreen(
                  onModelReady = {
                      navController.navigate("home") {
                          popUpTo("onboarding_model") { inclusive = true }
                      }
                  }
              )
          }

          composable("home") {
              HomeScreen(
                  onNavigateToSettings = {
                      navController.navigate("settings")
                  },
                  onNavigateToOnboardingModel = {
                      navController.navigate("onboarding_model")
                  }
              )
          }

          composable("settings") {
              SettingsScreen(
                  onNavigateBack = {
                      navController.popBackStack()
                  },
                  onNavigateToPrivacyPolicy = {
                      navController.navigate("privacy_policy")
                  },
                  onNavigateToTerms = {
                      navController.navigate("terms_of_service")
                  }
              )
          }

          composable("privacy_policy") {
              LegalDocumentScreen(
                  title = "Privacy Policy",
                  assetFileName = "privacy_policy.html",
                  onNavigateBack = {
                      navController.popBackStack()
                  }
              )
          }

          composable("terms_of_service") {
              LegalDocumentScreen(
                  title = "Terms of Service",
                  assetFileName = "terms_of_service.html",
                  onNavigateBack = {
                      navController.popBackStack()
                  }
              )
          }
      }
  }
  ```

  Add the `WhisperEverywhereApp` import (grouped with the existing `com.whispereverywhere.ui.screens.*` imports, at the top-level package):

  ```kotlin
  import com.whispereverywhere.WhisperEverywhereApp
  ```

- [ ] **Step 2: Add the `onNavigateToOnboardingModel` parameter + speech-model state to `HomeScreen`.**

  In `HomeScreen.kt`, change the `HomeScreen` signature (lines 42-44) to accept the new navigation callback:

  ```kotlin
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  fun HomeScreen(
      onNavigateToSettings: () -> Unit,
      onNavigateToOnboardingModel: () -> Unit
  ) {
  ```

  After the existing `hasNotificationListener` state declaration (line 63), add speech-model state driven by `installedModel()`:

  ```kotlin
      var hasSpeechModel by remember { mutableStateOf(app.whisperModelManager.installedModel() != null) }
  ```

- [ ] **Step 3: Refresh the speech-model state on resume and in the poll loop.**

  Inside `fun refreshPermissions()` (after line 77, `hasNotificationListener = MediaNotificationListener.isEnabled()`), add:

  ```kotlin
          hasSpeechModel = app.whisperModelManager.installedModel() != null
  ```

  Inside the periodic `LaunchedEffect(Unit)` poll loop, after the `hasNotificationListener` update block (after line 121, the closing `}` of the `if (newNotificationListener != hasNotificationListener)` block), add:

  ```kotlin
              val newSpeechModel = app.whisperModelManager.installedModel() != null
              if (newSpeechModel != hasSpeechModel) {
                  hasSpeechModel = newSpeechModel
              }
  ```

- [ ] **Step 4: Pass the speech-model state into `SetupChecklist` at the call site.**

  In the `SetupChecklist(...)` call (lines 189-216), add the `hasSpeechModel` argument and an `onRequestSpeechModel` callback that navigates to onboarding. Update the call to:

  ```kotlin
              SetupChecklist(
                  hasApiKey = hasApiKey,
                  hasMicrophonePermission = hasMicrophonePermission,
                  hasOverlayPermission = hasOverlayPermission,
                  hasAccessibilityEnabled = hasAccessibilityEnabled,
                  hasNotificationListener = hasNotificationListener,
                  hasSpeechModel = hasSpeechModel,
                  onSetApiKey = onNavigateToSettings,
                  onRequestMicrophone = {
                      val intent = Intent(
                          Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                          Uri.parse("package:${context.packageName}")
                      )
                      context.startActivity(intent)
                  },
                  onRequestOverlay = {
                      val intent = Intent(
                          Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                          Uri.parse("package:${context.packageName}")
                      )
                      context.startActivity(intent)
                  },
                  onRequestAccessibility = {
                      showAccessibilityDialog = true
                  },
                  onRequestNotificationListener = {
                      showNotificationListenerDialog = true
                  },
                  onRequestSpeechModel = onNavigateToOnboardingModel
              )
  ```

- [ ] **Step 5: Add the `hasSpeechModel` / `onRequestSpeechModel` params to `SetupChecklist` and gate `coreComplete` on the model.**

  In the `SetupChecklist` composable signature (lines 684-696), add the two new parameters. The speech model is a **core** requirement (transcription cannot work without it), so include it in `coreComplete`:

  ```kotlin
  @Composable
  fun SetupChecklist(
      hasApiKey: Boolean,
      hasMicrophonePermission: Boolean,
      hasOverlayPermission: Boolean,
      hasAccessibilityEnabled: Boolean,
      hasNotificationListener: Boolean = false,
      hasSpeechModel: Boolean = false,
      onSetApiKey: () -> Unit,
      onRequestMicrophone: () -> Unit,
      onRequestOverlay: () -> Unit,
      onRequestAccessibility: () -> Unit,
      onRequestNotificationListener: () -> Unit = {},
      onRequestSpeechModel: () -> Unit = {}
  ) {
      // Core requirements for basic functionality
      val coreComplete = hasSpeechModel && hasApiKey && hasMicrophonePermission && hasOverlayPermission && hasAccessibilityEnabled
      val allComplete = coreComplete && hasNotificationListener
  ```

- [ ] **Step 6: Render the "Speech model" checklist row (first, above the API key row).**

  In the "Setup Required" branch of `SetupChecklist`, add a `SetupItem` for the speech model as the **first** row (immediately after `Spacer(modifier = Modifier.height(12.dp))` on line 822, before the `SetupItem` for "OpenAI API Key"):

  ```kotlin
              SetupItem(
                  title = "Speech Model",
                  description = if (!hasSpeechModel) "Required to transcribe on-device" else null,
                  isComplete = hasSpeechModel,
                  onClick = onRequestSpeechModel
              )
  ```

  This reuses the existing `SetupItem` composable unchanged (green `CheckCircle` when installed, outlined circle + "Setup" button that taps into `onboarding_model` when not).

- [ ] **Step 7: BUILD check.** From `C:\Users\bastr\OneDrive\Desktop\whisper Everywhere`, run `.\gradlew.bat assembleDebug` and confirm it completes with `BUILD SUCCESSFUL`. (No unit test in this task: all changes are Compose UI wiring and navigation — the pure model logic they consume, `installedModel()`, is covered by the `WhisperModelManager` tests from Plan 1. The `startDestination` gate and checklist row have no pure-logic helper to isolate.)

---

### Task 3: Settings "Speech model" section + hide API key

**Files:**
- Modify: `C:\Users\bastr\OneDrive\Desktop\whisper Everywhere\app\src\main\java\com\whispereverywhere\ui\screens\SettingsScreen.kt`
- Modify: `C:\Users\bastr\OneDrive\Desktop\whisper Everywhere\app\src\main\java\com\whispereverywhere\MainActivity.kt`
- Create: `C:\Users\bastr\OneDrive\Desktop\whisper Everywhere\app\src\main\java\com\whispereverywhere\ui\util\ByteFormat.kt`
- Test: `C:\Users\bastr\OneDrive\Desktop\whisper Everywhere\app\src\test\java\com\whispereverywhere\ui\util\ByteFormatTest.kt`

**Interfaces:**
- Consumes (Plan 1): `WhisperEverywhereApp.getInstance().whisperModelManager` → `installedModel(): WhisperModel?`, `modelsDir(): File`, `delete(model)`, `isRecommendedForDevice(model)`; `WhisperModel.displayName`, `WhisperModel.approxBytes`, `WhisperModel.scope`; `PreferencesManager.selectedModelId` (settable), `apiKey` accessor (kept, not surfaced). Consumes (sibling Plan-2 Task 1/2): the `onboarding_model` nav route + `ModelDownloadViewModel` behind it — reached here only via a new `onNavigateToModelOnboarding: () -> Unit` callback; this task does NOT re-implement downloading.
- Produces: a new "Speech model" `SettingsSection`; a public pure helper `ui.util.formatBytes(Long): String`; SettingsScreen no longer renders any OpenAI API-key row or dialog.

- [ ] **Step 1: Add the pure `formatBytes` helper.** Create `ByteFormat.kt` — deterministic (no Android/Locale dependence) so it is JVM-unit-testable:

```kotlin
package com.whispereverywhere.ui.util

import kotlin.math.abs

/**
 * Formats a byte count as a short human string ("None", "57.0 MB", "1.9 GB").
 * Pure/JVM-testable: no Android, no Locale — always '.' decimal, 1 fractional digit for KB+.
 */
fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unitIndex = 0
    while (abs(value) >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex++
    }
    return if (unitIndex == 0) {
        "$bytes B"
    } else {
        val rounded = (value * 10.0).let { Math.round(it) } / 10.0
        val whole = rounded.toLong()
        val frac = (Math.round(rounded * 10.0) % 10).let { if (it < 0) -it else it }
        "$whole.$frac ${units[unitIndex]}"
    }
}
```

- [ ] **Step 2: JUnit4 test for `formatBytes`.** Create `ByteFormatTest.kt`:

```kotlin
package com.whispereverywhere.ui.util

import org.junit.Assert.assertEquals
import org.junit.Test

class ByteFormatTest {
    @Test fun zeroAndNegativeAreZeroBytes() {
        assertEquals("0 B", formatBytes(0L))
        assertEquals("0 B", formatBytes(-5L))
    }

    @Test fun smallBytesStayInBytes() {
        assertEquals("512 B", formatBytes(512L))
        assertEquals("1023 B", formatBytes(1023L))
    }

    @Test fun kilobytesGetOneDecimal() {
        assertEquals("1.0 KB", formatBytes(1024L))
        assertEquals("1.5 KB", formatBytes(1536L))
    }

    @Test fun megabytesForModelSizedFiles() {
        // Eco tier ~57 MB (57_000_000 bytes) -> 54.4 MB (base-1024).
        assertEquals("54.4 MB", formatBytes(57_000_000L))
    }

    @Test fun gigabytesRollOver() {
        assertEquals("1.9 GB", formatBytes(2_000_000_000L))
    }
}
```

- [ ] **Step 3: Add the `onNavigateToModelOnboarding` param to `SettingsScreen`.** In `SettingsScreen.kt` change the signature to add the callback (default `{}` so existing call sites still compile), and add the model-manager handle + reactive refresh key near the top of the composable:

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToPrivacyPolicy: () -> Unit = {},
    onNavigateToTerms: () -> Unit = {},
    onNavigateToModelOnboarding: () -> Unit = {}
) {
    val context = LocalContext.current
    val app = WhisperEverywhereApp.getInstance()
    val modelManager = app.whisperModelManager

    val vibrationEnabled by app.preferencesManager.vibrationEnabled.collectAsState()

    // Bump to force a re-read of installed-model / disk-usage after a delete or when
    // returning from the model-onboarding flow. `installedModel()` and `modelsDir()`
    // are cheap synchronous disk checks.
    var modelRefreshKey by remember { mutableStateOf(0) }
    var showDeleteModelDialog by remember { mutableStateOf(false) }

    val installedModel = remember(modelRefreshKey) { modelManager.installedModel() }
    val modelsDirUsageBytes = remember(modelRefreshKey) {
        modelManager.modelsDir().walkTopDown()
            .filter { it.isFile }
            .map { it.length() }
            .sum()
    }

    val scrollState = rememberScrollState()
```

Note: also DELETE the now-unused API-key state (`apiKey`, `showApiKey`, `showApiKeyDialog`) declarations from this block — they are removed in Step 5.

- [ ] **Step 4: Insert the "Speech model" section as the first section** in the scrolling `Column` (replacing the old "API Configuration" section position). Uses the existing `SettingsItem` and shows installed model (displayName + on-disk size or "None"), total `modelsDir()` usage, a Change/Download action (navigates to onboarding), and Delete (guarded by a confirm dialog):

```kotlin
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(scrollState)
        ) {
            // Speech model (on-device whisper) — replaces the old cloud API-key section.
            SettingsSection(title = "Speech model") {
                if (installedModel != null) {
                    val onDiskBytes = remember(modelRefreshKey, installedModel.id) {
                        val f = java.io.File(modelManager.modelsDir(), installedModel.fileName)
                        if (f.exists()) f.length() else installedModel.approxBytes
                    }
                    val scopeLabel = when (installedModel.scope) {
                        com.whispereverywhere.model.ModelScope.ENGLISH -> "English"
                        com.whispereverywhere.model.ModelScope.MULTILINGUAL -> "Multilingual"
                    }
                    SettingsItem(
                        icon = Icons.Filled.GraphicEq,
                        title = installedModel.displayName,
                        subtitle = "$scopeLabel · ${formatBytes(onDiskBytes)} on disk",
                        trailing = {
                            Icon(
                                Icons.Filled.CheckCircle,
                                contentDescription = null,
                                tint = Success,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    )
                } else {
                    SettingsItem(
                        icon = Icons.Filled.GraphicEq,
                        title = "Speech model",
                        subtitle = "None installed — tap to download",
                        onClick = onNavigateToModelOnboarding,
                        trailing = {
                            Surface(
                                color = Warning.copy(alpha = 0.1f),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = "REQUIRED",
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Warning,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    )
                }

                SettingsItem(
                    icon = Icons.Filled.Storage,
                    title = "Model storage",
                    subtitle = "${formatBytes(modelsDirUsageBytes)} used on this device"
                )

                SettingsItem(
                    icon = Icons.Filled.CloudDownload,
                    title = if (installedModel != null) "Change or add a model" else "Download a model",
                    subtitle = "Pick a speech-model tier (Eco / Pro / Extreme / Multilingual / Ultra)",
                    onClick = onNavigateToModelOnboarding
                )

                if (installedModel != null) {
                    SettingsItem(
                        icon = Icons.Filled.Delete,
                        title = "Delete current model",
                        subtitle = "Frees ${formatBytes(
                            java.io.File(modelManager.modelsDir(), installedModel.fileName)
                                .let { if (it.exists()) it.length() else installedModel.approxBytes }
                        )} — you'll need to re-download to transcribe",
                        onClick = { showDeleteModelDialog = true },
                        trailing = {
                            Icon(
                                Icons.Filled.DeleteOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    )
                }
            }

            // Preferences Section
            SettingsSection(title = "Preferences") {
                // ... (unchanged existing vibration switch)
```

- [ ] **Step 5: Remove all API-key UI.** Delete the entire old `SettingsSection(title = "API Configuration") { ... }` block (old lines ~72–106), delete the whole `if (showApiKeyDialog) { AlertDialog(...) }` block (old lines ~261–416), and delete the `ApiKeySetupStep` composable (old lines ~419–443). Also drop the now-unused imports that only the API-key UI used: `KeyboardOptions`, `KeyboardType`, `PasswordVisualTransformation`, `VisualTransformation`. Keep `PreferencesManager.apiKey`/`hasApiKey()` untouched (storage stays for future TTS). Keep the "Preferences", "Permissions", and "About" sections exactly as-is.

- [ ] **Step 6: Add the delete-confirmation dialog** (matches the app's `AlertDialog` style) after the section `Column`, before the closing of the composable. On confirm it deletes the file, clears the selection, and bumps the refresh key so the section re-reads:

```kotlin
    if (showDeleteModelDialog && installedModel != null) {
        AlertDialog(
            onDismissRequest = { showDeleteModelDialog = false },
            icon = { Icon(Icons.Filled.DeleteOutline, contentDescription = null) },
            title = { Text("Delete ${installedModel.displayName}?", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "This removes the model file from your device. On-device transcription " +
                        "will stop working until you download a model again.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        modelManager.delete(installedModel)
                        app.preferencesManager.selectedModelId = null
                        modelRefreshKey++
                        showDeleteModelDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteModelDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
```

- [ ] **Step 7: Add required imports to `SettingsScreen.kt`.** Add `import com.whispereverywhere.ui.util.formatBytes`. The Material icons `GraphicEq`, `Storage`, `CloudDownload`, `Delete`, `DeleteOutline`, and `CheckCircle` are already covered by the existing wildcard `import androidx.compose.material.icons.filled.*`; `ButtonDefaults` is covered by `androidx.compose.material3.*`. `Success` and `Warning` colors come from the existing `import com.whispereverywhere.ui.theme.*`. Confirm no other new imports are needed after the Step-5 deletions.

- [ ] **Step 8: Wire the new callback in `MainActivity.kt`.** In the `composable("settings")` block, pass `onNavigateToModelOnboarding = { navController.navigate("onboarding_model") }`. (The `onboarding_model` destination is registered by the sibling Plan-2 onboarding task; if that route is not yet present when this task lands, the plan's earlier onboarding task adds it — do not define it here.) Updated block:

```kotlin
        composable("settings") {
            SettingsScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToPrivacyPolicy = { navController.navigate("privacy_policy") },
                onNavigateToTerms = { navController.navigate("terms_of_service") },
                onNavigateToModelOnboarding = { navController.navigate("onboarding_model") }
            )
        }
```

- [ ] **Step 9: Unit-test gate.** Run `.\gradlew.bat testDebugUnitTest --tests "com.whispereverywhere.ui.util.ByteFormatTest"` — must pass (verifies the pure `formatBytes` helper).

- [ ] **Step 10: BUILD check.** Run `.\gradlew.bat assembleDebug` from the repo root — must succeed with no unresolved references (confirms the removed API-key imports/composables leave no dangling references and the new section/callbacks compile). If the sibling `onboarding_model` route is not yet merged, temporarily point `onNavigateToModelOnboarding` at `navController.popBackStack()` to keep the build green, then restore the `onboarding_model` navigation once that task lands.
