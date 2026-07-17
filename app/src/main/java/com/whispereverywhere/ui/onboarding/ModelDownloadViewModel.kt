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
        /** Network finished; the move + sha256 verification of a large file is running. */
        data object Verifying : DownloadState
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
        if (_state.value is DownloadState.Downloading || _state.value is DownloadState.Verifying) return
        _state.value = DownloadState.Downloading(pct = 0, soFar = 0L, total = model.approxBytes)
        viewModelScope.launch {
            try {
                manager.download(
                    model,
                    onProgress = { soFar, total ->
                        val safeTotal = if (total > 0L) total else model.approxBytes
                        val pct = if (safeTotal > 0L) {
                            ((soFar.toDouble() / safeTotal.toDouble()) * 100.0)
                                .toInt().coerceIn(0, 100)
                        } else 0
                        _state.value = DownloadState.Downloading(pct, soFar, safeTotal)
                    },
                    onVerifying = { _state.value = DownloadState.Verifying },
                )
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
        if (_state.value !is DownloadState.Downloading && _state.value !is DownloadState.Verifying) {
            _state.value = DownloadState.Idle
        }
    }
}
