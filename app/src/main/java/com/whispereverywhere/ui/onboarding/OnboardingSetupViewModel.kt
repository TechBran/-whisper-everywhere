package com.whispereverywhere.ui.onboarding

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.whispereverywhere.WhisperEverywhereApp
import com.whispereverywhere.model.WhisperCatalog
import com.whispereverywhere.model.WhisperModelManager
import com.whispereverywhere.tts.TtsModelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Drives the guided-onboarding AUTOMATIC engine setup: the Base multilingual speech model and the
 * on-device read-aloud voice, downloaded side by side with no button presses (owner decision
 * 2026-08-01 — "the user just doesn't have to press the buttons and they just automatically
 * happen"). Distinct from [ModelDownloadViewModel], which stays the manual per-tier picker behind
 * Home's setup banner.
 *
 * MUST be scoped to the ACTIVITY (`viewModel(viewModelStoreOwner = activity)`), not the onboarding
 * destination: the voice bundle is ~365 MB and the user is explicitly allowed to continue to the
 * cloud-keys step — or finish onboarding entirely — while it is still downloading or extracting. A
 * destination-scoped ViewModel dies with its back-stack entry and would cancel the extract
 * mid-archive; activity scope survives every in-app navigation. (If the user force-leaves the app
 * mid-extract, Settings' manual "Download the read-aloud voice" remains the retry path — the
 * on-disk `.installed` marker only appears after a COMPLETE extract, so a torn one re-downloads
 * rather than half-working.)
 *
 * Idempotence: [beginAutoSetup] is called from a LaunchedEffect on the engines step and does
 * nothing when already running or already installed — re-entering the step (back/forward, process
 * of granting permissions in Settings and returning) never restarts a download.
 */
class OnboardingSetupViewModel(app: Application) : AndroidViewModel(app) {

    /** One engine's setup progress. pct is 0-100; INDETERMINATE (-1) while extracting/verifying. */
    sealed interface EngineState {
        data object Pending : EngineState
        data class Working(val pct: Int, val label: String) : EngineState
        data object Ready : EngineState
        data class Failed(val message: String) : EngineState
    }

    private val appInstance: WhisperEverywhereApp = getApplication()
    private val whisperManager: WhisperModelManager get() = appInstance.whisperModelManager
    private val ttsManager by lazy { TtsModelManager(appInstance) }
    private val prefs get() = appInstance.preferencesManager

    private val _speechState = MutableStateFlow<EngineState>(EngineState.Pending)
    val speechState: StateFlow<EngineState> = _speechState.asStateFlow()

    private val _voiceState = MutableStateFlow<EngineState>(EngineState.Pending)
    val voiceState: StateFlow<EngineState> = _voiceState.asStateFlow()

    /** Start both engine downloads (the onboarding engines step). Per-engine idempotent. */
    fun beginAutoSetup() {
        ensureSpeech()
        ensureVoice()
    }

    /**
     * Make the on-device speech model exist, if it is not already installed: the
     * ONBOARDING_MODEL_ID tier — "Base multilingual" (~60 MB), the owner's chosen default
     * (multilingual on any device, no RAM floor). Already-installed reports Ready without a
     * network touch — which also self-heals the "selected tier's file was deleted but base is
     * still on disk" state by re-selecting base. Idempotent while running; callable again from
     * Failed, which is what Retry is.
     *
     * Serves BOTH onboarding's automatic step and Home's missing-engine status row (owner request
     * 2026-08-01: "a status and download shortcuts right there, in case someone has deleted them")
     * — one activity-scoped instance, so progress started on either surface shows on both.
     */
    fun ensureSpeech() {
        // Only Working blocks re-entry. A Ready deliberately does NOT: Ready can go stale — the
        // activity-scoped VM outlives a trip to Settings where the user deletes the model file —
        // and the disk check below is the truth. A still-installed engine just re-reports Ready.
        if (_speechState.value is EngineState.Working) return
        val model = WhisperCatalog.byId(ONBOARDING_MODEL_ID)
        if (model == null || whisperManager.isInstalled(model)) {
            // Also Ready when the catalog somehow lacks the id (defensive): this path must never
            // wedge onboarding — the manual tier picker remains the fallback.
            if (model != null) prefs.selectedModelId = model.id
            _speechState.value = EngineState.Ready
            return
        }
        _speechState.value = EngineState.Working(0, DOWNLOADING)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                whisperManager.download(
                    model,
                    onProgress = { soFar, total ->
                        val safeTotal = if (total > 0L) total else model.approxBytes
                        val pct = ((soFar * 100.0) / safeTotal).toInt().coerceIn(0, 100)
                        _speechState.value = EngineState.Working(pct, DOWNLOADING)
                    },
                    onVerifying = { _speechState.value = EngineState.Working(INDETERMINATE, VERIFYING) },
                )
                // Same persistence as the manual picker: the tier is selected and the first-run
                // gate cleared the moment dictation is actually possible.
                prefs.selectedModelId = model.id
                prefs.onboardingCompleted = true
                _speechState.value = EngineState.Ready
            } catch (e: Exception) {
                _speechState.value = EngineState.Failed(e.message ?: "Download failed")
            }
        }
    }

    /** Make the on-device read-aloud voice exist, if it is not already. Same contract as [ensureSpeech]. */
    fun ensureVoice() {
        if (_voiceState.value is EngineState.Working) return // Ready can be stale; disk decides
        if (ttsManager.isInstalled()) {
            _voiceState.value = EngineState.Ready
            return
        }
        _voiceState.value = EngineState.Working(0, DOWNLOADING)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                ttsManager.download(
                    onProgress = { soFar, total ->
                        val safeTotal = if (total > 0L) total else TtsModelManager.TAR_BYTES
                        val pct = ((soFar * 100.0) / safeTotal).toInt().coerceIn(0, 100)
                        _voiceState.value = EngineState.Working(pct, DOWNLOADING)
                    },
                    onExtracting = { _voiceState.value = EngineState.Working(INDETERMINATE, EXTRACTING) },
                )
                _voiceState.value = EngineState.Ready
            } catch (e: Exception) {
                _voiceState.value = EngineState.Failed(e.message ?: "Download failed")
            }
        }
    }

    companion object {
        /** The auto-setup speech tier: multilingual on every device, ~60 MB, no RAM floor. */
        const val ONBOARDING_MODEL_ID = "base"

        const val INDETERMINATE = -1
        const val DOWNLOADING = "Downloading"
        const val VERIFYING = "Verifying"
        const val EXTRACTING = "Unpacking"
    }
}
