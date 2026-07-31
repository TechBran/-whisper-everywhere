package com.whispereverywhere.ui.screens

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
import com.whispereverywhere.provider.ProviderCatalog
import com.whispereverywhere.service.FloatingBubbleService
import com.whispereverywhere.service.WhisperAccessibilityService
import com.whispereverywhere.service.resolveSttProvider
import com.whispereverywhere.tts.TtsVoices
import com.whispereverywhere.tts.resolveTtsProvider
import com.whispereverywhere.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------------------------------------
// Home = mode dashboard. The bubble status control stays at the top, then one card per mode
// (Dictation / Transcribe-file / Read-aloud), each showing its LIVE configuration as a status chip
// and tapping into its own settings. Directly under the status area sits the setup guidance derived
// from setupBannerState(hasModel, hasAnyKey) — a two-path banner when nothing is configured, an
// honest one-liner when half is, nothing once both are. All chips read reactively from
// PreferencesManager StateFlows plus an ON_RESUME snapshot for the keystore/disk reads: no polling.
// Every chip string is a pure formatter from ModeDashboard.kt — no price, no speed claim. The
// bubble and its service are untouched.
// ---------------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToSettings: () -> Unit,
    onNavigateToOnboardingModel: () -> Unit = {},
    onNavigateToTranscripts: () -> Unit = {},
    onNavigateToEnginesVoices: () -> Unit = {},
    onPickAudioFile: () -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val app = WhisperEverywhereApp.getInstance()

    val bubbleEnabled by app.preferencesManager.bubbleEnabled.collectAsState()
    val usedSecondsToday by app.usageTracker.usedSecondsToday.collectAsState()

    // Reactive engine/voice selections — the chips update the instant these change (e.g. on return
    // from the hub), with no polling. StateFlows, not a 1000 ms loop.
    val sttProviderId by app.preferencesManager.sttProviderIdFlow.collectAsState()
    val sttLiveMode by app.preferencesManager.sttLiveModeFlow.collectAsState()
    val ttsProviderId by app.preferencesManager.ttsProviderIdFlow.collectAsState()

    // Permission states — refreshed on ON_RESUME. hasSpeechModel doubles as the banner's hasModel.
    var hasMicrophonePermission by remember {
        mutableStateOf(
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.RECORD_AUDIO
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        )
    }
    var hasOverlayPermission by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var hasAccessibilityEnabled by remember { mutableStateOf(WhisperAccessibilityService.isEnabled()) }
    var hasSpeechModel by remember { mutableStateOf(app.whisperModelManager.installedModel() != null) }

    // Bumped on ON_RESUME so the off-main keystore/disk snapshots below re-read when the user comes
    // back from the hub (where they may have added a key or downloaded a model).
    var resumeTick by remember { mutableStateOf(0) }

    fun refreshPermissions() {
        hasMicrophonePermission = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        hasOverlayPermission = Settings.canDrawOverlays(context)
        hasAccessibilityEnabled = WhisperAccessibilityService.isEnabled()
        hasSpeechModel = app.whisperModelManager.installedModel() != null
    }

    // Refresh when the app resumes. (The old 1000 ms poll is gone — the chips are reactive and the
    // permission/keystore reads only need to be fresh on resume.)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshPermissions()
                resumeTick++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // hasAnyKey — a Keystore lookup per provider, so OFF the main thread; re-read on each resume.
    // Suppression: the producer DOES assign value (below) — the compose-runtime checker just can't
    // see an assignment after a suspend call.
    @Suppress("ProduceStateDoesNotAssignValue")
    val hasAnyKey by produceState(false, resumeTick) {
        value = withContext(Dispatchers.IO) {
            app.preferencesManager.providerAccounts.configured().isNotEmpty()
        }
    }

    // The installed model's tier label (e.g. "Eco") for the transcription chips — a small disk read,
    // taken off-main and refreshed on resume. null when no model is installed.
    @Suppress("ProduceStateDoesNotAssignValue")
    val localModelLabel by produceState<String?>(null, resumeTick) {
        value = withContext(Dispatchers.IO) {
            app.whisperModelManager.installedModel()?.displayName?.substringBefore(" (")?.trim()
        }
    }

    // Resolve the reactive prefs ids into the primitives the pure chip formatters expect.
    val sttEngineName = remember(sttProviderId) {
        resolveSttProvider(sttProviderId)?.let { ProviderCatalog.byId(it).displayName }
    }
    val readAloudEngineName = remember(ttsProviderId) {
        resolveTtsProvider(ttsProviderId)?.let { ProviderCatalog.byId(it).displayName }
    }
    // The read-aloud voice label: the Kokoro speaker key on-device (e.g. "af_heart"), else the
    // resolved cloud voice name. Cheap prefs reads, re-taken on resume. ElevenLabs' dynamic catalog
    // is not fetched here (that lives in the hub), so cloudVoiceDisplayName falls back to the raw id.
    val readAloudVoiceLabel = remember(ttsProviderId, resumeTick) {
        val cloudId = resolveTtsProvider(ttsProviderId)
        if (cloudId == null) {
            TtsVoices.byId(app.preferencesManager.ttsVoiceId).key
        } else {
            cloudVoiceDisplayName(cloudId, app.preferencesManager.ttsCloudVoiceId(cloudId), null)
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
            // Main Control Button (bubble toggle) — canEnable logic unchanged.
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
                totalTranscriptions = app.usageTracker.getTotalTranscriptionCount(),
                sttEngineName = sttEngineName,
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Setup guidance, directly under the status area. Two-path banner when nothing is
            // configured; an honest one-liner when exactly one half is; nothing once both are.
            when (setupBannerState(hasModel = hasSpeechModel, hasAnyKey = hasAnyKey)) {
                SetupBanner.TWO_PATH -> {
                    SetupBannerTwoPath(
                        onDownloadModel = onNavigateToOnboardingModel,
                        onBringYourOwnKey = onNavigateToEnginesVoices,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }
                SetupBanner.PARTIAL_LINE -> {
                    Text(
                        text = partialSetupLine(hasModel = hasSpeechModel),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onNavigateToEnginesVoices() }
                            .padding(vertical = 8.dp),
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }
                SetupBanner.NONE -> Unit
            }

            // Mode cards — each shows its live configuration as a status chip and taps into settings.
            ModeCard(
                icon = Icons.Filled.Mic,
                title = "Dictation",
                chip = dictationChip(
                    sttEngineName,
                    localModelLabel,
                    dictationLiveActive(sttProviderId, sttLiveMode),
                ),
                onClick = onNavigateToEnginesVoices,
            )

            Spacer(modifier = Modifier.height(16.dp))

            ModeCard(
                icon = Icons.Filled.GraphicEq,
                title = "Transcribe audio file",
                chip = transcriptionEngineChip(sttEngineName, localModelLabel),
                onClick = onPickAudioFile,
            )

            Spacer(modifier = Modifier.height(16.dp))

            ModeCard(
                icon = Icons.Filled.RecordVoiceOver,
                title = "Read aloud",
                chip = readAloudChip(readAloudEngineName, readAloudVoiceLabel),
                onClick = onNavigateToEnginesVoices,
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

            // Transcription language — the one place this is chosen; stays on the dashboard.
            LanguageSelectionCard()
        }
    }
}

/**
 * One mode card in the dashboard: an accent icon, the mode name, a live status chip built by the
 * pure formatters in ModeDashboard.kt, and a chevron. Taps into that mode's settings.
 */
@Composable
private fun ModeCard(
    icon: ImageVector,
    title: String,
    chip: String,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
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
                imageVector = icon,
                contentDescription = null,
                tint = Primary
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Surface(
                    color = Primary.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = chip,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = Primary,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            Icon(
                Icons.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * The prominent two-path setup banner shown when neither a model nor any key is configured. Free &
 * private goes to the on-device model download; Bring your own key goes to the hub (which shows the
 * disclosure). No speed claim — a synthesized choice is not sold on being fast.
 */
@Composable
private fun SetupBannerTwoPath(
    onDownloadModel: () -> Unit,
    onBringYourOwnKey: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = Primary.copy(alpha = 0.08f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Finish setting up",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Pick how you'd like to transcribe. You can change this any time.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = onDownloadModel,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Free & private — download a model")
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = onBringYourOwnKey,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Bring your own key")
            }
        }
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

        val labels = mainControlLabels(isEnabled = isEnabled, canEnable = canEnable)
        Text(
            text = labels.title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground
        )

        Text(
            text = labels.subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Footer line under the usage-stats card. The "No usage limits - transcription runs entirely
 * on-device" promise is true ONLY when transcription actually resolves on-device, i.e. no cloud STT
 * provider is selected ([sttEngineName] == null; see resolveSttProvider). For a cloud selection both
 * clauses are false — transcription does NOT run on-device and there IS a per-minute cost / provider
 * quota — so the honest thing is to show nothing rather than the on-device over-promise. Pure and
 * top-level so it is unit-testable without composing the card.
 */
internal fun usageStatsFooterLabel(sttEngineName: String?): String? =
    if (sttEngineName == null) "No usage limits - transcription runs entirely on-device" else null

@Composable
fun UsageStatsCard(
    usedSeconds: Int,
    totalUsage: Long,
    totalTranscriptions: Int,
    sttEngineName: String?,
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

            usageStatsFooterLabel(sttEngineName)?.let { footer ->
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = footer,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
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
