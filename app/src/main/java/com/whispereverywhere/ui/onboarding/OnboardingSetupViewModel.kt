package com.whispereverywhere.ui.onboarding

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.whispereverywhere.WhisperEverywhereApp
import com.whispereverywhere.model.WhisperCatalog
import com.whispereverywhere.model.WhisperModel
import com.whispereverywhere.model.WhisperModelManager
import com.whispereverywhere.npu.NpuPackController
import com.whispereverywhere.tts.TtsModelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Drives the guided-onboarding engine setup: the USER-PICKED speech tier and the on-device
 * read-aloud voice, downloaded side by side. Contract since 3.5.0: ONE PICK, THEN NO BUTTONS —
 * the engines step shows the four tier cards with no preselection, the pick writes
 * prefs.selectedModelId, and [beginAutoSetup] then drives both downloads to completion with
 * nothing further to press (evolving the 2026-08-01 "no button presses" owner decision: the
 * no-buttons promise now starts one informed tap later). Distinct from [ModelDownloadViewModel],
 * which stays the manual per-tier picker behind Home's setup banner.
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
 * Idempotence: [beginAutoSetup] is called from the engines step's single confirm action — never
 * before the pick has been persisted — and does nothing when already running or already
 * installed; re-entering the step (back/forward, granting permissions in Settings and
 * returning) never restarts a download.
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

    /** Start both engine downloads — the engines step's confirm, after the pick is persisted. Per-engine idempotent. */
    fun beginAutoSetup() {
        ensureSpeech()
        ensureVoice()
    }

    /**
     * Make the on-device speech model exist, if it is not already installed: the CURRENTLY
     * SELECTED tier — prefs.selectedModelId, which the onboarding pick persists BEFORE calling
     * [beginAutoSetup] — falling back to [WhisperCatalog.DEFAULT_MODEL_ID] when nothing was ever
     * selected. Already-installed reports Ready without a network touch. Idempotent while
     * running; callable again from Failed, which is what Retry is.
     *
     * Since 4.2 F6 a GATED tier routes through [ensureGatedSpeech] — the Play pack flow — and
     * the download path below stays byte-for-byte the non-gated tiers' route.
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
        if (model.gated) {
            // 4.2 F6: a gated tier's files arrive from Play's asset pack, never from
            // DownloadManager — download() would delete the encoder it cannot re-fetch
            // (WhisperCatalog.isInstallableByDownload's own warning) and size-gate the wreck
            // against the pair sum. The pack flow below; the download path underneath stays
            // byte-for-byte the non-gated tiers' route.
            ensureGatedSpeech(model)
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

    /**
     * [ensureSpeech]'s gated route (4.2 F6): hand the tier to [NpuPackController] — Play fetch,
     * census verify, the shared parking transaction — and mirror its state onto the engine card
     * through the ONE pure mapping ([OnboardingLogic.engineStateForFetch]) until a terminal
     * state lands. Retry is ensureSpeech again: `start` is single-flight (a double tap is
     * refused; a relaunch re-attaches to Play's surviving download), and a failed VERIFY leaves
     * the delivered pack on disk, so the retry costs nothing. On Ready, the same persistence as
     * the download path: the selection re-asserted and the first-run gate cleared the moment
     * dictation is actually possible — `Installed` is only ever published after the pair is
     * census-verified, renamed into place and announced, so Ready here is as true as the
     * download path's.
     *
     * Play's own consent dialog is the FLOW SCREEN's job: it observes the controller directly
     * and calls `confirm(activity)` once per NeedsConfirmation entry. This ViewModel never
     * talks to an Activity, and there is no custom re-ask anywhere — the consent is Play's.
     */
    private fun ensureGatedSpeech(model: WhisperModel) {
        // Published before start() so the Working guard above holds from this instant — the
        // same double-tap discipline as the download path's first Working write.
        _speechState.value = EngineState.Working(INDETERMINATE, OnboardingLogic.FETCH_PREPARING)
        NpuPackController.start(appInstance, model.id)
        viewModelScope.launch {
            NpuPackController.state
                .map(OnboardingLogic::engineStateForFetch)
                .onEach { mapped ->
                    if (mapped is EngineState.Ready) {
                        // Same order as the download path: persistence first, then Ready.
                        prefs.selectedModelId = model.id
                        prefs.onboardingCompleted = true
                    }
                    _speechState.value = mapped
                }
                // Collect to the FIRST terminal state, then stop: a collector left running
                // would keep mirroring later fetches (F7's chooser can start one for another
                // tier) onto this card. first() cancels the upstream collection itself.
                .first { it is EngineState.Ready || it is EngineState.Failed }
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
        const val INDETERMINATE = -1
        const val DOWNLOADING = "Downloading"
        const val VERIFYING = "Verifying"
        const val EXTRACTING = "Unpacking"
    }
}
