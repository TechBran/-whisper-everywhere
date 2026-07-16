package com.whispereverywhere.ui.screens

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.whispereverywhere.WhisperEverywhereApp
import com.whispereverywhere.model.ModelScope
import com.whispereverywhere.model.WhisperCatalog
import com.whispereverywhere.model.WhisperModel
import com.whispereverywhere.ui.onboarding.ModelDownloadViewModel
import com.whispereverywhere.ui.onboarding.ModelDownloadViewModel.DownloadState
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
                        progress = { downloading.pct / 100f },
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
