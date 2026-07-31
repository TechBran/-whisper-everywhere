package com.whispereverywhere.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.whispereverywhere.WhisperEverywhereApp
import com.whispereverywhere.recording.BatchStatus
import com.whispereverywhere.recording.ChunkStatus
import com.whispereverywhere.recording.RecordingMeta
import com.whispereverywhere.recording.RecordingStore
import com.whispereverywhere.service.resolveBatchSttProvider
import com.whispereverywhere.transcription.TranscriptStore
import com.whispereverywhere.transcription.batch.BatchCloudGate
import com.whispereverywhere.transcription.batch.BatchCostEstimator
import com.whispereverywhere.ui.BatchJobViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/**
 * ONE screen for a whole-file batch transcription job. No recordings library, no record screen, no
 * playback, no export — the rescope cut all of those. It carries a single picked file through four
 * states: Ready (engine choice) -> Working (progress + Cancel) -> Done (transcript + Copy/Share) or
 * Failed/PartiallyDone (reason + Retry-resume + Discard).
 *
 * The cloud engine row is ABSENT — not merely disabled — unless [BatchCloudGate]'s consent triad
 * holds (a resolvable provider, a stored key, accepted disclosure). Its per-minute price and the
 * cost-confirm threshold both come from [BatchCostEstimator]; copy says "about", never a promise,
 * and never a speed claim. The service is the sole owner of the job and the sole writer of progress;
 * this screen only observes and fires intents through [BatchJobViewModel].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchTranscribeScreen(
    uri: Uri,
    displayName: String,
    durationMs: Long,
    onNavigateBack: () -> Unit,
    viewModel: BatchJobViewModel = viewModel(),
) {
    val context = LocalContext.current
    val progress by viewModel.progress.collectAsState()

    // The per-job engine choice. Cloud is offered ONLY when the triad holds; it then defaults to the
    // global selection (cloudEligible already encodes "cloud is both selected and usable").
    val cloudEligible = remember {
        val prefs = WhisperEverywhereApp.getInstance().preferencesManager
        // Use the OpenAI-only batch clamp, NOT resolveSttProvider: the batch SERVICE resolves cloud
        // through resolveBatchSttProvider (BatchTranscriptionService.resolveCloud), so a Gemini /
        // ElevenLabs / Soniox selection that the wider live-mode resolver would accept must NOT make
        // the cloud row appear here — the service would clamp it to on-device, and the row's
        // hardcoded "OpenAI" title + OpenAI price would be for a key the user may not even hold.
        // Matching the predicates keeps the screen and the service in agreement.
        val providerId = resolveBatchSttProvider(prefs.sttProviderId)
        val key = providerId?.let { prefs.providerAccounts.key(it) }
        BatchCloudGate.cloudEligible(providerId?.name, key, prefs.cloudDisclosureAccepted)
    }
    var useCloud by remember { mutableStateOf(cloudEligible) }

    // Screen-state bookkeeping. `jobStarted` gates Ready vs the live states; `sawActive` guards
    // against briefly rendering a PREVIOUS job's terminal progress (which lingers in the global
    // singleton) before the service publishes this job's first "Transcribing".
    var jobStarted by remember { mutableStateOf(false) }
    var sawActive by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<PendingConfirm?>(null) }

    LaunchedEffect(progress?.status, jobStarted) {
        val s = progress?.status
        if (jobStarted && (s == BatchStatus.Transcribing || s == BatchStatus.Recorded)) {
            sawActive = true
        }
    }

    // Show the cost dialog only for a cloud job past the §6.5 threshold; otherwise start directly.
    fun beginJob(costBytes: Long, confirmedStart: () -> Unit, directStart: () -> Unit) {
        if (useCloud && BatchCostEstimator.needsConfirmation(costBytes)) {
            pending = PendingConfirm(
                minutes = BatchCostEstimator.minutes(costBytes),
                cents = BatchCostEstimator.estimatedCents(costBytes),
                onCloud = { pending = null; confirmedStart() },
                onLocal = { pending = null; directStart() },
            )
        } else {
            directStart()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Transcribe audio file") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
        ) {
            val p = progress
            when {
                // ---- READY ---------------------------------------------------------------
                !jobStarted -> ReadyContent(
                    displayName = displayName,
                    durationMs = durationMs,
                    cloudEligible = cloudEligible,
                    useCloud = useCloud,
                    onUseCloud = { useCloud = it },
                    onTranscribe = {
                        beginJob(
                            costBytes = BatchCostEstimator.bytesForDuration(durationMs),
                            confirmedStart = {
                                sawActive = false; jobStarted = true
                                viewModel.startNew(uri, displayName, durationMs, costConfirmed = true, useCloud = useCloud)
                            },
                            directStart = {
                                sawActive = false; jobStarted = true
                                viewModel.startNew(uri, displayName, durationMs, costConfirmed = false, useCloud = useCloud)
                            },
                        )
                    },
                )

                // ---- WORKING (also the "waiting for the service to take over" window) ----
                !sawActive || p == null || p.status == BatchStatus.Transcribing || p.status == BatchStatus.Recorded ->
                    WorkingContent(
                        label = when {
                            !sawActive || p == null || p.chunkCount <= 0 -> "Preparing audio…"
                            else -> "Chunk ${(p.chunkIndex + 1).coerceAtMost(p.chunkCount)} of ${p.chunkCount}"
                        },
                        fraction = if (sawActive && p != null && p.chunkCount > 0)
                            (p.chunkIndex.toFloat() / p.chunkCount).coerceIn(0f, 1f) else null,
                        onCancel = { viewModel.cancel() },
                    )

                // ---- DONE ----------------------------------------------------------------
                p.status == BatchStatus.Done -> DoneContent(recordingId = p.recordingId)

                // ---- FAILED / PARTIALLY DONE --------------------------------------------
                else -> {
                    val recordingId = p.recordingId
                    val meta by produceState<RecordingMeta?>(initialValue = null, key1 = recordingId) {
                        value = withContext(Dispatchers.IO) { RecordingStore.forApp(context).read(recordingId) }
                    }
                    val m = meta
                    val total = m?.chunkPlan?.size ?: 0
                    val done = m?.chunkPlan?.count { it.status == ChunkStatus.Done } ?: 0
                    // No workspace / no plan on a Failed job means decode never produced audio.
                    val decodeFailed = p.status == BatchStatus.Failed && total == 0
                    ErrorContent(
                        reason = if (decodeFailed) "Couldn't read this audio file"
                        else "Transcription stopped — $done of $total parts done",
                        onRetry = {
                            if (decodeFailed) {
                                // Nothing to resume — re-attempt the whole job from the picked file.
                                beginJob(
                                    costBytes = BatchCostEstimator.bytesForDuration(durationMs),
                                    confirmedStart = {
                                        sawActive = false
                                        viewModel.startNew(uri, displayName, durationMs, costConfirmed = true, useCloud = useCloud)
                                    },
                                    directStart = {
                                        sawActive = false
                                        viewModel.startNew(uri, displayName, durationMs, costConfirmed = false, useCloud = useCloud)
                                    },
                                )
                            } else {
                                // Resume: the cost gate re-estimates on the FULL byteLength (an
                                // over-estimate once chunks are Done — it errs toward asking).
                                beginJob(
                                    costBytes = (m?.byteLength ?: 0).toLong(),
                                    confirmedStart = {
                                        sawActive = false
                                        viewModel.retry(recordingId, reset = false, costConfirmed = true, useCloud = useCloud)
                                    },
                                    directStart = {
                                        sawActive = false
                                        viewModel.retry(recordingId, reset = false, costConfirmed = false, useCloud = useCloud)
                                    },
                                )
                            }
                        },
                        onDiscard = {
                            viewModel.dismiss(recordingId)
                            onNavigateBack()
                        },
                    )
                }
            }
        }
    }

    pending?.let { pc ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Use the cloud?") },
            text = {
                Text(
                    "Transcribe about ${formatMinutes(pc.minutes)} in the cloud for about " +
                        "${formatCents(pc.cents)} with your OpenAI key?"
                )
            },
            confirmButton = { Button(onClick = pc.onCloud) { Text("Use cloud") } },
            dismissButton = { TextButton(onClick = pc.onLocal) { Text("Use on-device") } },
        )
    }
}

/** Captured estimate + the two follow-through actions for the cost-confirm dialog. */
private class PendingConfirm(
    val minutes: Double,
    val cents: Double,
    val onCloud: () -> Unit,
    val onLocal: () -> Unit,
)

@Composable
private fun ReadyContent(
    displayName: String,
    durationMs: Long,
    cloudEligible: Boolean,
    useCloud: Boolean,
    onUseCloud: (Boolean) -> Unit,
    onTranscribe: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(displayName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(
            formatDuration(durationMs),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(24.dp))
        Text("Transcribe with", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))

        EngineRow(
            selected = !useCloud,
            title = "On-device",
            subtitle = "Free and private — no network needed",
            onClick = { onUseCloud(false) },
        )
        // ABSENT (not disabled) unless the BatchCloudGate triad holds.
        if (cloudEligible) {
            Spacer(Modifier.height(8.dp))
            EngineRow(
                selected = useCloud,
                title = "OpenAI",
                subtitle = "about ¢${BatchCostEstimator.CENTS_PER_MINUTE}/min",
                onClick = { onUseCloud(true) },
            )
        }

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onTranscribe,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Transcribe")
        }
    }
}

@Composable
private fun EngineRow(
    selected: Boolean,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (selected) 3.dp else 1.dp),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = onClick)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun WorkingContent(
    label: String,
    fraction: Float?,
    onCancel: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(top = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(16.dp))
        if (fraction == null) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(24.dp))
        OutlinedButton(onClick = onCancel) { Text("Cancel") }
    }
}

@Composable
private fun DoneContent(recordingId: String) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    // The service saved the transcript to TranscriptStore and deleted the workspace on Done. Prefer
    // the workspace text while it briefly survives; otherwise the newest TranscriptStore entry IS
    // this job's (deliver() saves BEFORE it deletes, so workspace-gone implies saved-and-newest).
    val transcript by produceState(initialValue = "", key1 = recordingId) {
        value = loadFinishedTranscript(context, recordingId)
    }

    Column(Modifier.fillMaxSize()) {
        Text("Saved to Transcriptions", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(8.dp))
        Text(
            transcript.ifBlank { "(no speech found)" },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        )
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { clipboard.setText(AnnotatedString(transcript)) },
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Copy")
            }
            Button(
                onClick = {
                    context.startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, transcript)
                            },
                            "Share transcription",
                        )
                    )
                },
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Share")
            }
        }
    }
}

@Composable
private fun ErrorContent(
    reason: String,
    onRetry: () -> Unit,
    onDiscard: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(top = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            reason,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("Retry") }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onDiscard) { Text("Discard") }
    }
}

/** Best-effort read of the finished transcript across the Done -> save -> delete window. */
private suspend fun loadFinishedTranscript(context: Context, recordingId: String): String =
    withContext(Dispatchers.IO) {
        val store = RecordingStore.forApp(context)
        val transcripts = TranscriptStore(File(context.filesDir, "transcripts"))
        repeat(20) {
            store.read(recordingId)?.let { meta ->
                val t = store.assembledText(meta)
                if (t.isNotBlank()) return@withContext t
            }
            transcripts.list().firstOrNull()?.let { entry ->
                val t = runCatching { transcripts.read(entry) }.getOrDefault("")
                if (t.isNotBlank()) return@withContext t
            }
            delay(100)
        }
        ""
    }

private fun formatDuration(ms: Long): String {
    if (ms <= 0L) return "Length unknown"
    val totalSec = ms / 1000
    val m = totalSec / 60
    val s = totalSec % 60
    return if (m > 0) "$m min $s sec" else "$s sec"
}

private fun formatMinutes(minutes: Double): String = "${minutes.roundToInt().coerceAtLeast(1)} min"

private fun formatCents(cents: Double): String = "¢${"%.1f".format(cents)}"
