package com.whispereverywhere.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.whispereverywhere.WhisperEverywhereApp
import com.whispereverywhere.data.local.PreferencesManager
import com.whispereverywhere.service.FloatingBubbleService
import com.whispereverywhere.service.MediaNotificationListener
import com.whispereverywhere.service.WhisperAccessibilityService
import com.whispereverywhere.ui.theme.*
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToSettings: () -> Unit,
    onNavigateToOnboardingModel: () -> Unit = {},
    onNavigateToTranscripts: () -> Unit = {},
    onPickAudioFile: () -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val app = WhisperEverywhereApp.getInstance()

    val bubbleEnabled by app.preferencesManager.bubbleEnabled.collectAsState()
    val usedSecondsToday by app.usageTracker.usedSecondsToday.collectAsState()

    // Permission states - will be updated on lifecycle events
    var hasMicrophonePermission by remember { 
        mutableStateOf(
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.RECORD_AUDIO
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) 
    }
    var hasOverlayPermission by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var hasAccessibilityEnabled by remember { mutableStateOf(WhisperAccessibilityService.isEnabled()) }
    var hasNotificationListener by remember { mutableStateOf(MediaNotificationListener.isEnabled()) }
    var hasSpeechModel by remember { mutableStateOf(app.whisperModelManager.installedModel() != null) }

    // Dialog states
    var showAccessibilityDialog by remember { mutableStateOf(false) }
    var showNotificationListenerDialog by remember { mutableStateOf(false) }

    // Function to refresh all permission states
    fun refreshPermissions() {
        hasMicrophonePermission = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        hasOverlayPermission = Settings.canDrawOverlays(context)
        hasAccessibilityEnabled = WhisperAccessibilityService.isEnabled()
        hasNotificationListener = MediaNotificationListener.isEnabled()
        hasSpeechModel = app.whisperModelManager.installedModel() != null
    }

    // Listen to lifecycle events - refresh when app resumes
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshPermissions()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Also poll periodically while on screen to catch accessibility service changes
    // (since accessibility service connection doesn't trigger lifecycle events)
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000) // Check every second
            val newAccessibility = WhisperAccessibilityService.isEnabled()
            val newOverlay = Settings.canDrawOverlays(context)
            val newMicrophone = androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.RECORD_AUDIO
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED

            // Only update if changed (to avoid unnecessary recompositions)
            if (newMicrophone != hasMicrophonePermission) {
                hasMicrophonePermission = newMicrophone
            }
            if (newAccessibility != hasAccessibilityEnabled) {
                hasAccessibilityEnabled = newAccessibility
            }
            if (newOverlay != hasOverlayPermission) {
                hasOverlayPermission = newOverlay
            }
            val newNotificationListener = MediaNotificationListener.isEnabled()
            if (newNotificationListener != hasNotificationListener) {
                hasNotificationListener = newNotificationListener
            }
            val newSpeechModel = app.whisperModelManager.installedModel() != null
            if (newSpeechModel != hasSpeechModel) {
                hasSpeechModel = newSpeechModel
            }
        }
    }

    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Whisper Everywhere",
                        fontWeight = FontWeight.Bold
                    )
                },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(scrollState)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Main Control Button
            MainControlButton(
                isEnabled = bubbleEnabled,
                canEnable = hasSpeechModel && hasMicrophonePermission && hasOverlayPermission && hasAccessibilityEnabled,
                onToggle = {
                    if (bubbleEnabled) {
                        FloatingBubbleService.stop(context)
                        app.preferencesManager.setBubbleEnabled(false)
                    } else {
                        FloatingBubbleService.start(context)
                        app.preferencesManager.setBubbleEnabled(true)
                    }
                }
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Usage Stats Card (shows today's usage - no limits)
            UsageStatsCard(
                usedSeconds = usedSecondsToday,
                totalUsage = app.usageTracker.getTotalUsageAllTime(),
                totalTranscriptions = app.usageTracker.getTotalTranscriptionCount()
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Transcription history entry (rolling 14-day, text-only sessions)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onNavigateToTranscripts() },
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.ReceiptLong,
                        contentDescription = null,
                        tint = Primary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Transcriptions",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Your saved sessions — kept 14 days",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        Icons.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Batch transcription entry — pick an audio file and transcribe it all at once.
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPickAudioFile() },
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.GraphicEq,
                        contentDescription = null,
                        tint = Primary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Transcribe audio file",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Pick a recording and turn it into text",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        Icons.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Language Selection Card
            LanguageSelectionCard()

            Spacer(modifier = Modifier.height(16.dp))

            // Setup Checklist
            SetupChecklist(
                hasMicrophonePermission = hasMicrophonePermission,
                hasOverlayPermission = hasOverlayPermission,
                hasAccessibilityEnabled = hasAccessibilityEnabled,
                hasNotificationListener = hasNotificationListener,
                hasSpeechModel = hasSpeechModel,
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

            Spacer(modifier = Modifier.height(16.dp))

            // How to Use Card
            HowToUseCard()

            Spacer(modifier = Modifier.height(16.dp))

            // BYOK Info Card
            BYOKInfoCard()
        }
    }

    // Accessibility Service Instructions Dialog
    if (showAccessibilityDialog) {
        AlertDialog(
            onDismissRequest = { showAccessibilityDialog = false },
            icon = {
                Icon(
                    Icons.Filled.Accessibility,
                    contentDescription = null,
                    tint = Primary,
                    modifier = Modifier.size(48.dp)
                )
            },
            title = {
                Text(
                    "Enable Accessibility Service",
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            },
            text = {
                Column {
                    Text(
                        "Whisper Everywhere uses Android's Accessibility Service to insert your " +
                            "transcribed speech into the text field you are typing in, and to read " +
                            "text you highlight aloud when you tap the speaker bubble. It reads only " +
                            "the focused input field and your highlighted selection — it does not " +
                            "collect, store, or share the contents of your screen, and no data ever " +
                            "leaves your device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        "Follow these steps in Settings:",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    InstructionStep(1, "Find \"Installed apps\" or \"Downloaded apps\"")
                    InstructionStep(2, "Tap \"Whisper Everywhere Voice Input\"")
                    InstructionStep(3, "Toggle the switch to ON")
                    InstructionStep(4, "Tap \"Allow\" on the confirmation dialog")

                    Spacer(modifier = Modifier.height(12.dp))

                    Surface(
                        color = Primary.copy(alpha = 0.1f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Filled.Info,
                                contentDescription = null,
                                tint = Primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Your speech is transcribed entirely on your device. This permission is used only to place that text into the field you choose.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showAccessibilityDialog = false
                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        context.startActivity(intent)
                    }
                ) {
                    Text("Open Settings")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAccessibilityDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Notification Listener Instructions Dialog
    if (showNotificationListenerDialog) {
        AlertDialog(
            onDismissRequest = { showNotificationListenerDialog = false },
            icon = {
                Icon(
                    Icons.Filled.Notifications,
                    contentDescription = null,
                    tint = Primary,
                    modifier = Modifier.size(48.dp)
                )
            },
            title = {
                Text(
                    "Enable Media Detection",
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            },
            text = {
                Column {
                    Text(
                        "Follow these steps in Settings:",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    InstructionStep(1, "Find \"Whisper Everywhere\" in the list")
                    InstructionStep(2, "Toggle the switch to ON")
                    InstructionStep(3, "Tap \"Allow\" on the confirmation dialog")

                    Spacer(modifier = Modifier.height(12.dp))

                    Surface(
                        color = Primary.copy(alpha = 0.1f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Filled.Info,
                                contentDescription = null,
                                tint = Primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "This allows the app to detect when audio/video is playing so you can transcribe it.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showNotificationListenerDialog = false
                        val intent = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
                        context.startActivity(intent)
                    }
                ) {
                    Text("Open Settings")
                }
            },
            dismissButton = {
                TextButton(onClick = { showNotificationListenerDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun InstructionStep(number: Int, text: String) {
    Row(
        modifier = Modifier.padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Surface(
            color = Primary,
            shape = CircleShape,
            modifier = Modifier.size(24.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = number.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = OnPrimary,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

@Composable
fun MainControlButton(
    isEnabled: Boolean,
    canEnable: Boolean,
    onToggle: () -> Unit
) {
    val scale by animateFloatAsState(
        targetValue = if (isEnabled) 1.1f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "scale"
    )

    val buttonColor by animateColorAsState(
        targetValue = if (isEnabled) RecordingActive else Primary,
        animationSpec = tween(300),
        label = "color"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(140.dp)
                .scale(scale)
                .clip(CircleShape)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            buttonColor,
                            buttonColor.copy(alpha = 0.8f)
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            IconButton(
                onClick = {
                    if (canEnable) onToggle()
                },
                modifier = Modifier.size(140.dp),
                enabled = canEnable
            ) {
                Icon(
                    imageVector = if (isEnabled) Icons.Filled.MicOff else Icons.Filled.Mic,
                    contentDescription = if (isEnabled) "Disable" else "Enable",
                    modifier = Modifier.size(64.dp),
                    tint = OnPrimary
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = if (isEnabled) "Tap to Disable" else if (canEnable) "Tap to Enable" else "Complete Setup First",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground
        )

        Text(
            text = if (isEnabled) "Bubble is active" else if (canEnable) "Bubble is inactive" else "Setup required below",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun UsageStatsCard(
    usedSeconds: Int,
    totalUsage: Long,
    totalTranscriptions: Int
) {
    val usageTracker = WhisperEverywhereApp.getInstance().usageTracker

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Your Stats",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )

                Surface(
                    color = Success.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = "UNLIMITED",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = Success,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatItem(
                    icon = Icons.Filled.Today,
                    value = usageTracker.formatTime(usedSeconds),
                    label = "Today"
                )
                StatItem(
                    icon = Icons.Filled.Timer,
                    value = usageTracker.formatTimeVerbose(totalUsage.toInt()),
                    label = "Total Time"
                )
                StatItem(
                    icon = Icons.Filled.TextFields,
                    value = totalTranscriptions.toString(),
                    label = "Transcriptions"
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "No usage limits - transcription runs entirely on-device",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguageSelectionCard() {
    val app = WhisperEverywhereApp.getInstance()
    val selectedLanguage by app.preferencesManager.selectedLanguage.collectAsState()
    var expanded by remember { mutableStateOf(false) }

    // Find the display name for the current selection
    val selectedDisplayName = PreferencesManager.SUPPORTED_LANGUAGES
        .find { it.first == selectedLanguage }?.second ?: "Auto-detect"

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.Language,
                        contentDescription = null,
                        tint = Primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Transcription Language",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Select the language you'll be speaking",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Dropdown menu
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = !expanded }
            ) {
                OutlinedTextField(
                    value = selectedDisplayName,
                    onValueChange = {},
                    readOnly = true,
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline
                    )
                )

                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    PreferencesManager.SUPPORTED_LANGUAGES.forEach { (code, displayName) ->
                        DropdownMenuItem(
                            text = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(displayName)
                                    if (code == selectedLanguage) {
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Icon(
                                            Icons.Filled.Check,
                                            contentDescription = null,
                                            tint = Primary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            },
                            onClick = {
                                app.preferencesManager.setSelectedLanguage(code)
                                expanded = false
                            }
                        )
                    }
                }
            }

            if (selectedLanguage == "auto") {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Whisper will automatically detect the spoken language",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun SetupChecklist(
    hasMicrophonePermission: Boolean,
    hasOverlayPermission: Boolean,
    hasAccessibilityEnabled: Boolean,
    hasNotificationListener: Boolean = false,
    hasSpeechModel: Boolean = false,
    onRequestMicrophone: () -> Unit,
    onRequestOverlay: () -> Unit,
    onRequestAccessibility: () -> Unit,
    onRequestNotificationListener: () -> Unit = {},
    onRequestSpeechModel: () -> Unit = {}
) {
    // Core requirements for basic functionality
    val coreComplete = hasSpeechModel && hasMicrophonePermission && hasOverlayPermission && hasAccessibilityEnabled
    val allComplete = coreComplete && hasNotificationListener

    // Show success message when core setup is complete
    if (coreComplete) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = Success.copy(alpha = 0.1f)
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = Success
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "All set! You're ready to use Whisper Everywhere.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Success
                    )
                }

                // Optional: Media transcription setup
                if (!hasNotificationListener) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Divider(color = Success.copy(alpha = 0.3f))
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Filled.MusicNote,
                            contentDescription = null,
                            tint = Primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Optional: Media Transcription",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Enable notification access to transcribe audio/video playing in other apps.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = onRequestNotificationListener,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            Icons.Filled.Notifications,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Enable Media Detection")
                    }
                } else {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Filled.MusicNote,
                            contentDescription = null,
                            tint = Success,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Media transcription enabled",
                            style = MaterialTheme.typography.bodySmall,
                            color = Success
                        )
                    }
                }
            }
        }
        return
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = Warning.copy(alpha = 0.1f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.Checklist,
                    contentDescription = null,
                    tint = Warning
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Setup Required",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            SetupItem(
                title = "Speech Model",
                description = if (!hasSpeechModel) "Required to transcribe on-device" else null,
                isComplete = hasSpeechModel,
                onClick = onRequestSpeechModel
            )

            SetupItem(
                title = "Microphone Permission",
                description = if (!hasMicrophonePermission) "Required to record audio" else null,
                isComplete = hasMicrophonePermission,
                onClick = onRequestMicrophone
            )

            SetupItem(
                title = "Overlay Permission",
                description = if (!hasOverlayPermission) "Required for floating bubble" else null,
                isComplete = hasOverlayPermission,
                onClick = onRequestOverlay
            )

            SetupItem(
                title = "Accessibility Service",
                description = if (!hasAccessibilityEnabled) "Required for text injection" else null,
                isComplete = hasAccessibilityEnabled,
                onClick = onRequestAccessibility
            )
        }
    }
}

@Composable
fun SetupItem(
    title: String,
    description: String? = null,
    isComplete: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                imageVector = if (isComplete) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                contentDescription = null,
                tint = if (isComplete) Success else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isComplete)
                        MaterialTheme.colorScheme.onSurfaceVariant
                    else
                        MaterialTheme.colorScheme.onSurface
                )
                if (description != null && !isComplete) {
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (!isComplete) {
            TextButton(onClick = onClick) {
                Text("Setup")
            }
        }
    }
}

@Composable
fun HowToUseCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "How to Use",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.height(12.dp))

            HowToStep(
                number = 1,
                text = "Enable the floating bubble above"
            )
            HowToStep(
                number = 2,
                text = "Open any app with a text field"
            )
            HowToStep(
                number = 3,
                text = "Tap the bubble to start recording"
            )
            HowToStep(
                number = 4,
                text = "Speak clearly, then tap again to stop"
            )
            HowToStep(
                number = 5,
                text = "Your text will appear automatically!"
            )
        }
    }
}

@Composable
fun HowToStep(number: Int, text: String) {
    Row(
        modifier = Modifier.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            color = Primary.copy(alpha = 0.1f),
            shape = CircleShape,
            modifier = Modifier.size(24.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = number.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = Primary,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
fun BYOKInfoCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = Primary.copy(alpha = 0.05f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.PhoneAndroid,
                    contentDescription = null,
                    tint = Primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "100% On-Device",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Transcription runs entirely on your device. No API key or internet connection required — your audio never leaves your phone.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun StatItem(
    icon: ImageVector,
    value: String,
    label: String
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Primary,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
