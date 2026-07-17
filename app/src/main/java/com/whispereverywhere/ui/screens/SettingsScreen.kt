package com.whispereverywhere.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.whispereverywhere.BuildConfig
import com.whispereverywhere.WhisperEverywhereApp
import com.whispereverywhere.model.ModelScope
import com.whispereverywhere.service.WhisperAccessibilityService
import com.whispereverywhere.ui.theme.*
import com.whispereverywhere.util.formatBytes
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToPrivacyPolicy: () -> Unit = {},
    onNavigateToTerms: () -> Unit = {},
    onNavigateToModelOnboarding: () -> Unit = {},
    onNavigateToLicenses: () -> Unit = {}
) {
    val context = LocalContext.current
    val app = WhisperEverywhereApp.getInstance()
    val modelManager = app.whisperModelManager

    val vibrationEnabled by app.preferencesManager.vibrationEnabled.collectAsState()
    val bubbleAlwaysOn by app.preferencesManager.bubbleAlwaysOn.collectAsState()

    // Bump to force a re-read of installed-model / disk-usage after a delete or when
    // returning from the model-onboarding flow.
    var modelRefreshKey by remember { mutableStateOf(0) }
    var showDeleteModelDialog by remember { mutableStateOf(false) }

    val installedModel = remember(modelRefreshKey) { modelManager.installedModel() }

    // Compute models-dir total disk usage off the main thread.
    val modelsDirUsageBytes by produceState(initialValue = 0L, key1 = modelRefreshKey) {
        value = withContext(Dispatchers.IO) {
            modelManager.modelsDir().walkTopDown().filter { it.isFile }.map { it.length() }.sum()
        }
    }

    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { paddingValues ->
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
                        val f = File(modelManager.modelsDir(), installedModel.fileName)
                        if (f.exists()) f.length() else installedModel.approxBytes
                    }
                    val scopeLabel = when (installedModel.scope) {
                        ModelScope.ENGLISH -> "English"
                        ModelScope.MULTILINGUAL -> "Multilingual"
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
                            File(modelManager.modelsDir(), installedModel.fileName)
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
                SettingsSwitchItem(
                    icon = Icons.Filled.PushPin,
                    title = "Keep bubble always on screen",
                    subtitle = "Bubble stays where you place it. Off: pops up only near " +
                        "text fields and playing media, hides when idle",
                    checked = bubbleAlwaysOn,
                    onCheckedChange = { enabled ->
                        app.preferencesManager.setBubbleAlwaysOn(enabled)
                        // Apply immediately: restart the running service in the new mode.
                        // (onDestroy no longer clobbers bubbleEnabled, so this is a clean cycle.)
                        if (app.preferencesManager.isBubbleEnabled()) {
                            com.whispereverywhere.service.FloatingBubbleService.stop(context)
                            com.whispereverywhere.service.FloatingBubbleService.start(context)
                        }
                    }
                )
                SettingsSwitchItem(
                    icon = Icons.Filled.Vibration,
                    title = "Vibration Feedback",
                    subtitle = "Vibrate on recording start/stop",
                    checked = vibrationEnabled,
                    onCheckedChange = { app.preferencesManager.setVibrationEnabled(it) }
                )
            }

            // Permissions Section
            SettingsSection(title = "Permissions") {
                val hasMicrophone = androidx.core.content.ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.RECORD_AUDIO
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                val hasOverlay = Settings.canDrawOverlays(context)
                val hasAccessibility = WhisperAccessibilityService.isEnabled()
                val hasNotificationListener = com.whispereverywhere.service.MediaNotificationListener.isEnabled()

                SettingsItem(
                    icon = Icons.Filled.Mic,
                    title = "Microphone Permission",
                    subtitle = if (hasMicrophone) "Granted" else "Required to record audio",
                    trailing = {
                        if (hasMicrophone) {
                            Icon(
                                Icons.Filled.CheckCircle,
                                contentDescription = null,
                                tint = Success,
                                modifier = Modifier.size(20.dp)
                            )
                        } else {
                            TextButton(onClick = {
                                val intent = Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:${context.packageName}")
                                )
                                context.startActivity(intent)
                            }) {
                                Text("Grant")
                            }
                        }
                    }
                )

                SettingsItem(
                    icon = Icons.Filled.Layers,
                    title = "Overlay Permission",
                    subtitle = if (hasOverlay) "Granted" else "Required for floating bubble",
                    trailing = {
                        if (hasOverlay) {
                            Icon(
                                Icons.Filled.CheckCircle,
                                contentDescription = null,
                                tint = Success,
                                modifier = Modifier.size(20.dp)
                            )
                        } else {
                            TextButton(onClick = {
                                val intent = Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                )
                                context.startActivity(intent)
                            }) {
                                Text("Grant")
                            }
                        }
                    }
                )

                SettingsItem(
                    icon = Icons.Filled.Accessibility,
                    title = "Accessibility Service",
                    subtitle = if (hasAccessibility) "Enabled" else "Required for text injection",
                    trailing = {
                        if (hasAccessibility) {
                            Icon(
                                Icons.Filled.CheckCircle,
                                contentDescription = null,
                                tint = Success,
                                modifier = Modifier.size(20.dp)
                            )
                        } else {
                            TextButton(onClick = {
                                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                context.startActivity(intent)
                            }) {
                                Text("Enable")
                            }
                        }
                    }
                )

                SettingsItem(
                    icon = Icons.Filled.MusicNote,
                    title = "Notification Access",
                    subtitle = if (hasNotificationListener) "Granted" else "Optional: For media transcription",
                    trailing = {
                        if (hasNotificationListener) {
                            Icon(
                                Icons.Filled.CheckCircle,
                                contentDescription = null,
                                tint = Success,
                                modifier = Modifier.size(20.dp)
                            )
                        } else {
                            TextButton(onClick = {
                                val intent = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
                                context.startActivity(intent)
                            }) {
                                Text("Grant")
                            }
                        }
                    }
                )
            }

            // About Section
            SettingsSection(title = "About") {
                SettingsItem(
                    icon = Icons.Filled.Info,
                    title = "Version",
                    subtitle = BuildConfig.VERSION_NAME
                )

                SettingsItem(
                    icon = Icons.Filled.Policy,
                    title = "Privacy Policy",
                    onClick = onNavigateToPrivacyPolicy
                )

                SettingsItem(
                    icon = Icons.Filled.Description,
                    title = "Terms of Service",
                    onClick = onNavigateToTerms
                )

                SettingsItem(
                    icon = Icons.Filled.Description,
                    title = "Open-Source Licenses",
                    onClick = onNavigateToLicenses
                )

                SettingsItem(
                    icon = Icons.Filled.Help,
                    title = "Help & Support",
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://whispereverywhere.com/support"))
                        context.startActivity(intent)
                    }
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    // Delete-model confirmation dialog
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
}

@Composable
fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = Primary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        )

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                content()
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
fun SettingsItem(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null)
                    Modifier.clickable(onClick = onClick)
                else
                    Modifier
            )
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp)
        )

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (trailing != null) {
            trailing()
        } else if (onClick != null) {
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun SettingsSwitchItem(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp)
        )

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}
