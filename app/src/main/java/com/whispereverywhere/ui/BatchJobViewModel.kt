package com.whispereverywhere.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.whispereverywhere.recording.RecordingStore
import com.whispereverywhere.service.BatchJobController
import com.whispereverywhere.service.BatchTranscriptionService
import com.whispereverywhere.transcription.batch.BatchProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The screen's window onto one batch job. It OBSERVES [BatchJobController.progress] (the service is
 * the sole writer) and fires intents at [BatchTranscriptionService]; it never runs the job itself.
 *
 * Nothing here re-derives the cloud/local decision — that lives entirely in the service
 * (BatchCloudGate triad + notifications + cost confirm). The ViewModel only carries the user's
 * per-job cost consent through [BatchTranscriptionService.EXTRA_COST_CONFIRMED]; the picked Uri's
 * read grant rides the intent via FLAG_GRANT_READ_URI_PERMISSION. The Uri is never logged.
 */
class BatchJobViewModel(app: Application) : AndroidViewModel(app) {

    /** The one source of truth for job state — written by the service, only read here. */
    val progress: StateFlow<BatchProgress?> = BatchJobController.progress

    /** Start a brand-new job from a freshly picked file. The service decodes, plans, transcribes. */
    fun startNew(uri: Uri, displayName: String, durationMs: Long, costConfirmed: Boolean) {
        val app = getApplication<Application>()
        val intent = Intent(app, BatchTranscriptionService::class.java).apply {
            data = uri
            // The read grant must ride the intent to the service.
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra(BatchTranscriptionService.EXTRA_DISPLAY_NAME, displayName)
            putExtra(BatchTranscriptionService.EXTRA_DURATION_MS, durationMs)
            putExtra(BatchTranscriptionService.EXTRA_COST_CONFIRMED, costConfirmed)
        }
        ContextCompat.startForegroundService(app, intent)
    }

    /**
     * Resume (or, with [reset], restart) an existing workspace. A Done chunk is never re-run, so
     * Retry is safe on a paid API. The cost confirm is re-gated by the caller on the FULL byteLength.
     */
    fun retry(id: String, reset: Boolean, costConfirmed: Boolean) {
        val app = getApplication<Application>()
        val intent = Intent(app, BatchTranscriptionService::class.java).apply {
            putExtra(BatchTranscriptionService.EXTRA_RECORDING_ID, id)
            putExtra(BatchTranscriptionService.EXTRA_RESET, reset)
            putExtra(BatchTranscriptionService.EXTRA_COST_CONFIRMED, costConfirmed)
        }
        ContextCompat.startForegroundService(app, intent)
    }

    /** Cooperative cancel: the service stops between chunks and keeps its partial checkpoint. */
    fun cancel() {
        BatchJobController.cancelActive()
    }

    /** Abandon a finished-with-failure job: delete its transient workspace off the main thread. */
    fun dismiss(id: String) {
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            RecordingStore.forApp(app).delete(id)
        }
    }
}
