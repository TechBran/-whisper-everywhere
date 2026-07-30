package com.whispereverywhere.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.whispereverywhere.MainActivity
import com.whispereverywhere.R
import com.whispereverywhere.WhisperEverywhereApp
import com.whispereverywhere.net.ConnectivityMonitor
import com.whispereverywhere.net.OkHttpTransport
import com.whispereverywhere.recording.AudioDecoder
import com.whispereverywhere.recording.BatchStatus
import com.whispereverywhere.recording.PcmSink
import com.whispereverywhere.recording.RecordingMeta
import com.whispereverywhere.recording.RecordingStore
import com.whispereverywhere.recording.StorageGuard
import com.whispereverywhere.transcription.TranscriptStore
import com.whispereverywhere.transcription.WhisperNativeBackend
import com.whispereverywhere.transcription.batch.BatchCloudGate
import com.whispereverywhere.transcription.batch.BatchCostEstimator
import com.whispereverywhere.transcription.batch.BatchProgress
import com.whispereverywhere.transcription.batch.BatchTranscriber
import com.whispereverywhere.transcription.cloud.OpenAiStt
import com.whispereverywhere.transcription.cloud.SttProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

/**
 * Process-scoped bridge from the [BatchTranscriptionService] to the [com.whispereverywhere.ui.BatchJobViewModel].
 *
 * The service owns the job; the UI only OBSERVES. Kept intentionally tiny — a StateFlow the
 * ViewModel collects and a cancel passthrough — so there is no binder, no service connection, and
 * no lifecycle coupling between the FGS and whatever screen happens to be in front. The service is
 * the sole writer of [progress]; the UI never writes it.
 */
object BatchJobController {
    /** null before any job; the latest [BatchProgress] once one starts. Written ONLY by the service. */
    val progress = MutableStateFlow<BatchProgress?>(null)

    /** The in-flight transcriber, so a Cancel tap can reach it without a binder. */
    @Volatile
    internal var active: BatchTranscriber? = null

    /** Cooperative cancel: the transcriber stops between chunks and keeps its partial checkpoint. */
    fun cancelActive() { active?.cancel() }
}

/**
 * The foreground host for one whole-file batch job: DECODE phase, then TRANSCRIBE phase, to
 * completion off the main thread.
 *
 * Why a foreground service and not a plain coroutine: an hour-long file decodes and transcribes for
 * many minutes, and a backgrounded plain coroutine is killed by the OS mid-job. Foreground keeps it
 * alive; the per-chunk manifest checkpoint (owned by [BatchTranscriber]) is the second belt for the
 * case where even a foreground service dies.
 *
 * The cloud/local decision is made HERE, once per start, and only ever DEGRADES to local — never
 * the reverse. Cloud is passed to the transcriber only when ALL of these hold:
 *   1. [BatchCloudGate]'s triad (a selected+resolvable provider, a stored key, accepted disclosure),
 *   2. a validated network,
 *   3. notifications are enabled (the FGS spend indicator must be visible while the user is charged),
 *   4. the job is below the §6.5 cost threshold OR the cost confirm rode the intent.
 * Any miss means `cloud = null` and the job runs fully on-device. The transcriber is a local-only
 * runner when handed a null provider.
 *
 * Privacy: the picked content Uri's read grant rides the intent (FLAG_GRANT_READ_URI_PERMISSION set
 * by the UI). The Uri is NEVER logged — it can embed a filename. Only reasons/counts/lengths are.
 */
class BatchTranscriptionService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val app by lazy { WhisperEverywhereApp.getInstance() }

    // ONLY ever cacheDir/batch — the transient workspace. Never a bare File in production.
    private val store by lazy { RecordingStore.forApp(this) }

    // One OkHttp client for the service's life (built lazily, only if a cloud job actually runs).
    private var httpTransport: OkHttpTransport? = null

    @Volatile private var activeTranscriber: BatchTranscriber? = null
    @Volatile private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val uri: Uri? = intent?.data
        val retryId: String? = intent?.getStringExtra(EXTRA_RECORDING_ID)

        // Neither a new job nor a retry — nothing to do.
        if (uri == null && retryId == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Foreground FIRST (satisfies the startForegroundService 5 s contract), guarded fail-soft.
        startForegroundSafely(PREPARING_TEXT)

        // One job per service instance. A concurrent start (rare) is ignored rather than loading a
        // second native context alongside the running one.
        if (running) {
            Log.w(TAG, "batch: a job is already running — ignoring the new start")
            return START_NOT_STICKY
        }
        running = true

        val isRetry = uri == null
        val displayName = intent.getStringExtra(EXTRA_DISPLAY_NAME) ?: DEFAULT_DISPLAY_NAME
        val durationMs = intent.getLongExtra(EXTRA_DURATION_MS, 0L)
        val costConfirmed = intent.getBooleanExtra(EXTRA_COST_CONFIRMED, false)
        val reset = intent.getBooleanExtra(EXTRA_RESET, false)

        serviceScope.launch(Dispatchers.Default) {
            runJob(isRetry, uri, retryId, displayName, durationMs, costConfirmed, reset)
        }
        return START_NOT_STICKY
    }

    /**
     * The whole job: resolve/produce the decoded workspace, build the transcriber with the fully
     * gated engine choice, transcribe to completion, deliver, and always tear the service down.
     */
    private suspend fun runJob(
        isRetry: Boolean,
        uri: Uri?,
        retryId: String?,
        displayName: String,
        durationMs: Long,
        costConfirmed: Boolean,
        reset: Boolean,
    ) {
        var jobId: String? = null
        var mirror: Job? = null
        try {
            // ---- resolve the workspace id (decode phase for a new job) ----
            val id: String = if (isRetry) {
                retryId ?: run {
                    Log.w(TAG, "batch: retry with no recording id")
                    return
                }
            } else {
                val newId = UUID.randomUUID().toString()
                jobId = newId
                publishProgress(newId, BatchStatus.Transcribing) // "Preparing audio…"

                store.sweepStale()

                // Free-space gate on the retriever-duration estimate. mkdirs first so the StatFs
                // target exists — availableBytesAt fails OPEN (Long.MAX_VALUE) on a missing path,
                // which would make the guard vacuous. (Minimal deviation from the plan's literal
                // call; reported.)
                store.dir(newId).mkdirs()
                val required = BatchCostEstimator.bytesForDuration(durationMs)
                if (!StorageGuard.enoughSpace(StorageGuard.availableBytesAt(store.dir(newId)), required)) {
                    Log.w(TAG, "batch: not enough storage (need ~${required / (1024 * 1024)} MB)")
                    publishProgress(newId, BatchStatus.Failed)
                    store.delete(newId)
                    return
                }

                if (uri == null) { // defensive: isRetry was false, so uri must be non-null
                    Log.w(TAG, "batch: new job with no source uri")
                    store.delete(newId)
                    return
                }

                val sink = PcmSink(store.audioFile(newId))
                val result = try {
                    AudioDecoder().decodeTo(this@BatchTranscriptionService, uri, sink) { /* progress: notification stays "Preparing audio…" */ }
                } finally {
                    sink.close() // flush the buffered tail before anything reads audio.pcm
                }

                when (result) {
                    is AudioDecoder.DecodeResult.Unsupported -> {
                        // Generic message only; never the Uri. reason is a class name, not content.
                        Log.w(TAG, "batch: couldn't read the audio file (${result.reason})")
                        publishProgress(newId, BatchStatus.Failed)
                        store.delete(newId)
                        return
                    }
                    is AudioDecoder.DecodeResult.Ok -> {
                        store.save(
                            RecordingMeta(
                                id = newId,
                                createdAtMs = System.currentTimeMillis(),
                                durationMs = result.durationMs,
                                displayName = displayName,
                                byteLength = result.byteLength.toInt(),
                            ),
                        )
                    }
                }
                newId
            }
            jobId = id
            publishProgress(id, BatchStatus.Transcribing) // "Preparing audio…" for the retry path too

            val meta = store.read(id) ?: run {
                Log.w(TAG, "batch: no manifest for the job — cannot transcribe")
                return
            }

            // ---- the ONE cloud decision, fully gated; degrades to local, never fails the job ----
            val cloud = resolveCloud(meta.byteLength.toLong(), costConfirmed)

            val transcriber = BatchTranscriber(
                store = store,
                cloud = cloud,
                backend = WhisperNativeBackend,
                modelPathProvider = app.whisperModelManager,
            )
            activeTranscriber = transcriber
            BatchJobController.active = transcriber

            // Mirror the transcriber's progress to the UI singleton and the notification. Skip the
            // initial null so the "Preparing audio…" progress set above survives until the first
            // real chunk lands.
            mirror = serviceScope.launch {
                transcriber.progress.collect { p ->
                    if (p != null) {
                        BatchJobController.progress.value = p
                        updateNotification(notificationText(p))
                    }
                }
            }

            transcriber.transcribe(id, reset)
            // Belt: guarantee the terminal status reaches the UI even if the mirror is torn down.
            BatchJobController.progress.value = transcriber.progress.value
            deliver(id)
        } catch (t: Throwable) {
            Log.w(TAG, "batch: job error (${t.javaClass.simpleName})")
            jobId?.let { BatchJobController.progress.value = BatchProgress(it, 0, 0, BatchStatus.Failed) }
        } finally {
            mirror?.cancel()
            activeTranscriber = null
            BatchJobController.active = null
            running = false
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /**
     * Cloud is passed to the transcriber ONLY when the whole gate holds. Each failed condition
     * degrades to on-device (returns null); none fails the job. Never re-derives policy — it calls
     * the pinned [BatchCloudGate] predicate and the pinned [BatchCostEstimator] threshold.
     */
    private fun resolveCloud(byteLength: Long, costConfirmed: Boolean): SttProvider? {
        val prefs = app.preferencesManager
        val providerId = resolveSttProvider(prefs.sttProviderId)           // top-level, this package
        val key = providerId?.let { prefs.providerAccounts.key(it) }
        val disclosureAccepted = prefs.cloudDisclosureAccepted

        // (triad) key + provider + accepted disclosure, plus a validated network.
        if (!BatchCloudGate.cloudEligible(providerId?.name, key, disclosureAccepted)) return null
        if (!ConnectivityMonitor(this).hasValidatedNetwork()) return null

        // (notifications) §6.5: no visible spend indicator -> do not charge silently -> local.
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) return null

        // (cost confirm) §6.5: a job past the threshold needs the explicit confirm that rode the
        // intent, so no stale/future caller can start a large cloud job unconfirmed.
        if (BatchCostEstimator.needsConfirmation(byteLength) && !costConfirmed) return null

        return OpenAiStt(transport(), key!!)
    }

    /**
     * Final hand-off. On Done: save the assembled TEXT into the existing [TranscriptStore] (its
     * text-only contract is untouched — the transcript IS text), then DELETE the workspace so no
     * audio outlives its job. On Failed/PartiallyDone the workspace is KEPT so Retry can resume; it
     * is removed when the user dismisses the job or by sweepStale after 24 h.
     */
    private fun deliver(id: String) {
        val meta = store.read(id) ?: return
        if (meta.status == BatchStatus.Done) {
            val text = store.assembledText(meta)
            // Save the transcript, then apply the SAME rolling 14-day/10 MB retention every other
            // writer honors. Batch was the one writer that never swept: a user who only ever batches
            // (never live-dictates) would otherwise grow transcripts/ without bound — and batch
            // produces the app's largest transcripts (hour-long files). deliver() already runs off
            // the main thread (runJob is on Dispatchers.Default), so the sweep is safe to run inline.
            TranscriptStore(File(filesDir, "transcripts")).apply {
                save(meta.createdAtMs, text)
                sweep()
            }
            store.delete(id)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Cancel cooperatively (keeps the partial checkpoint), then close the transcriber's native
        // dispatcher, then cancel the host scope.
        activeTranscriber?.cancel()
        activeTranscriber?.shutdown()
        activeTranscriber = null
        BatchJobController.active = null
        serviceScope.cancel()
        httpTransport = null
    }

    // ---- infra ----

    private fun transport(): OkHttpTransport = httpTransport ?: OkHttpTransport().also { httpTransport = it }

    private fun publishProgress(id: String, status: BatchStatus) {
        val p = BatchProgress(id, 0, 0, status)
        BatchJobController.progress.value = p
        updateNotification(notificationText(p))
    }

    private fun notificationText(p: BatchProgress): String = when (p.status) {
        BatchStatus.Done -> "Finished"
        BatchStatus.Failed, BatchStatus.PartiallyDone -> "Stopped"
        // chunkCount 0 = the decode/plan phase; otherwise report the running chunk. No speed claims.
        else -> if (p.chunkCount <= 0) PREPARING_TEXT
        else "Chunk ${(p.chunkIndex + 1).coerceAtMost(p.chunkCount)} of ${p.chunkCount}"
    }

    private fun startForegroundSafely(text: String) {
        val notification = buildNotification(text)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this, NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(NOTIF_ID, notification)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "batch: startForeground rejected (${t.javaClass.simpleName}) — stopping")
            stopSelf()
        }
    }

    private fun updateNotification(text: String) {
        if (!running) return
        runCatching { NotificationManagerCompat.from(this).notify(NOTIF_ID, buildNotification(text)) }
    }

    private fun buildNotification(text: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, WhisperEverywhereApp.NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Transcribing audio file")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val TAG = "WE-BATCH"

        /** Distinct from the bubble's NOTIFICATION_ID (1001) and BootReceiver's (1002). */
        const val NOTIF_ID = 1003

        const val EXTRA_DISPLAY_NAME = "extra_display_name"
        const val EXTRA_DURATION_MS = "extra_duration_ms"
        const val EXTRA_COST_CONFIRMED = "extra_cost_confirmed"
        const val EXTRA_RECORDING_ID = "extra_recording_id"
        const val EXTRA_RESET = "extra_reset"

        private const val PREPARING_TEXT = "Preparing audio…"
        private const val DEFAULT_DISPLAY_NAME = "audio"
    }
}
