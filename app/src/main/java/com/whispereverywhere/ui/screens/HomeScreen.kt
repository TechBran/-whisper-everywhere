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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.whispereverywhere.ui.onboarding.OnboardingSetupViewModel
import com.whispereverywhere.ui.onboarding.OnboardingSetupViewModel.EngineState
import com.whispereverywhere.WhisperEverywhereApp
import com.whispereverywhere.data.local.PreferencesManager
import com.whispereverywhere.provider.ProviderCatalog
import com.whispereverywhere.service.FloatingBubbleService
import com.whispereverywhere.service.WhisperAccessibilityService
import com.whispereverywhere.service.resolveSttProvider
import com.whispereverywhere.tts.TtsModelManager
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
    // The read-aloud voice's installed state is purely on-disk (marker file + model.onnx) — same
    // cheap existence checks as installedModel(), refreshed on the same ON_RESUME tick.
    val ttsModelManager = remember { TtsModelManager(context) }
    var hasTtsVoice by remember { mutableStateOf(ttsModelManager.isInstalled()) }

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
        hasTtsVoice = ttsModelManager.isInstalled()
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
    // Configured engines, live: the installed on-device model (0/1) + every provider with a
    // stored key. Same off-main keystore/disk snapshot cadence as hasAnyKey below.
    @Suppress("ProduceStateDoesNotAssignValue")
    val configuredEngineCount by produceState(0, resumeTick) {
        value = withContext(Dispatchers.IO) {
            val keyed = ProviderCatalog.all.count { app.preferencesManager.providerAccounts.key(it.id) != null }
            keyed + (if (app.whisperModelManager.installedModel() != null) 1 else 0)
        }
    }

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
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.primary,
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

            // Permission chip: visible ONLY while a bubble-blocking permission is missing (owner
            // report 2026-08-01 — granted permissions were reported in Settings but a missing one
            // was named nowhere; the disabled button's generic "Grant permissions in Settings" was
            // the only clue). Tap goes straight to Settings' Permissions rows. When everything is
            // granted this renders nothing, keeping the clean dashboard the refresh established.
            com.whispereverywhere.ui.onboarding.OnboardingLogic.homePermissionChipText(
                com.whispereverywhere.ui.onboarding.OnboardingLogic.missingBubblePermissions(
                    mic = hasMicrophonePermission,
                    overlay = hasOverlayPermission,
                    accessibility = hasAccessibilityEnabled,
                )
            )?.let { chip ->
                Text(
                    text = chip,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onNavigateToSettings() }
                        .padding(vertical = 8.dp),
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Missing-engine status rows (owner request 2026-08-01: "a status and download
            // shortcuts right there, in case someone has maybe deleted them"). Each appears ONLY
            // while its engine is absent from disk and downloads IN PLACE via the same
            // activity-scoped OnboardingSetupViewModel onboarding uses — progress started on
            // either surface shows on both. Rows vanish the moment the engine lands (the Ready
            // observer below re-reads the disk state).
            val setupVm: OnboardingSetupViewModel =
                viewModel(viewModelStoreOwner = context as androidx.activity.ComponentActivity)
            val speechSetup by setupVm.speechState.collectAsState()
            val voiceSetup by setupVm.voiceState.collectAsState()
            LaunchedEffect(speechSetup, voiceSetup) {
                if (speechSetup is EngineState.Ready || voiceSetup is EngineState.Ready) refreshPermissions()
            }
            if (!hasSpeechModel) {
                MissingEngineRow(
                    title = "Speech model not installed",
                    detail = "Dictation and file transcription need it — about 60 MB",
                    state = speechSetup,
                    onDownload = { setupVm.ensureSpeech() },
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
            if (!hasTtsVoice) {
                MissingEngineRow(
                    title = "Read-aloud voice not installed",
                    detail = "Reading text aloud needs it — about 365 MB",
                    state = voiceSetup,
                    onDownload = { setupVm.ensureVoice() },
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Stats panel: Today (live time incl. seconds) / Engines (live configured count) /
            // Transcriptions. "Total Time" was dropped as redundant with Today (owner 2026-08-01);
            // the count re-reads on every resume via the remember key, since the service process
            // writes it outside this composition.
            val totalTranscriptions = remember(resumeTick) { app.usageTracker.getTotalTranscriptionCount() }
            val monthCostCents = remember(resumeTick) { app.cloudCostTracker.estimatedMonthCents() }
            UsageStatsCard(
                usedSeconds = usedSecondsToday,
                configuredEngines = configuredEngineCount,
                totalTranscriptions = totalTranscriptions,
                monthCostCents = monthCostCents,
                sttEngineName = sttEngineName,
            )

            Spacer(modifier = Modifier.height(16.dp))

            // The how-to guide (owner 2026-08-01): comprehensive, DIRECTLY below the stats, with
            // read-aloud pinned to it — the app reading its own manual is the fastest demo of
            // read-aloud there is. Collapsed by default so the dashboard stays a dashboard.
            HowToGuideCard()

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

            // Cloud-key note (3.5.0, Workstream B): a dismissible nudge that better accuracy and
            // wider language coverage exist behind the user's own API key. Visibility is the pure
            // CloudKeyNote.shouldShow truth table: any configured provider key OR a selected cloud
            // STT engine hides it permanently, independent of the persisted X. The local mirror of
            // cloudNoteDismissed follows house convention for plain-var prefs read in composition
            // (see EnginesAndVoicesScreen's sttProviderId remember).
            var cloudNoteDismissed by remember {
                mutableStateOf(app.preferencesManager.cloudNoteDismissed)
            }
            if (com.whispereverywhere.ui.CloudKeyNote.shouldShow(
                    cloudProviderConfigured = hasAnyKey || sttProviderId != null,
                    dismissed = cloudNoteDismissed,
                )
            ) {
                CloudKeyNoteCard(
                    onOpenEnginesVoices = onNavigateToEnginesVoices,
                    onDismiss = {
                        app.preferencesManager.cloudNoteDismissed = true
                        cloudNoteDismissed = true
                    },
                )
                Spacer(modifier = Modifier.height(16.dp))
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
private fun HowToGuideCard() {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }

    // Warm path (3.6.0, Workstream E2): expanding the guide is the think-time signal that a
    // read-aloud tap may follow — start the ~2 s Kokoro load then, not on the tap. Deliberately
    // NOT on plain Home composition: that would allocate the TTS context on every app open for
    // users who never read. No-op when the voice isn't installed or is already loaded, and the
    // engine's idle-unload reclaims the context if the tap never comes.
    LaunchedEffect(expanded) {
        if (expanded) com.whispereverywhere.tts.TtsController.preload(context)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.HelpOutline, contentDescription = null, tint = Primary)
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "How to use Whisper Everywhere",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                // Read-aloud, pinned to the guide (owner: "more interactive") — tap to hear the
                // whole guide through whichever voice is configured; tapping again restarts, and
                // the bubble's stop/scrubber controls work on it like any read.
                // preload first (3.6.0 E2): covers the collapsed-header tap the expansion
                // preload above never saw; the load overlaps speakFromTrigger's main-thread
                // prefs/Keystore resolution. No-op when already loaded.
                IconButton(onClick = {
                    com.whispereverywhere.tts.TtsController.preload(context)
                    com.whispereverywhere.tts.TtsController.stop()
                    com.whispereverywhere.tts.TtsController.speakFromTrigger(
                        context, com.whispereverywhere.ui.HowToGuide.plainText()
                    )
                }) {
                    Icon(
                        Icons.Filled.VolumeUp,
                        contentDescription = "Read this guide aloud",
                        tint = Primary
                    )
                }
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (expanded) {
                com.whispereverywhere.ui.HowToGuide.sections.forEach { section ->
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = section.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = section.body,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * One absent on-device engine: what is missing, why it matters, and a Download button that fixes
 * it in place. While the shared setup ViewModel reports Working the button yields to a progress
 * bar; Failed shows the reason and turns the button into Retry (ensure* re-runs from Failed).
 */
@Composable
private fun MissingEngineRow(
    title: String,
    detail: String,
    state: EngineState,
    onDownload: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                when (state) {
                    is EngineState.Working -> Unit // the bar below carries the status
                    is EngineState.Failed -> OutlinedButton(onClick = onDownload) { Text("Retry") }
                    else -> OutlinedButton(onClick = onDownload) { Text("Download") }
                }
            }
            when (state) {
                is EngineState.Working -> {
                    Spacer(modifier = Modifier.height(10.dp))
                    if (state.pct >= 0) {
                        LinearProgressIndicator(
                            progress = { state.pct / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "${state.label} — ${state.pct}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "${state.label}\u2026",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                is EngineState.Failed -> {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                else -> Unit
            }
        }
    }
}

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

/**
 * The dismissible cloud-key note. All copy comes verbatim from [com.whispereverywhere.ui.CloudKeyNote]
 * (the JVM-pinned discipline surface); this shell only lays it out. The X persists dismissal via
 * [onDismiss]; the button rides Home's existing Engines & voices route. Untested UI by house
 * convention — the visibility logic lives in CloudKeyNote.shouldShow, which is.
 */
@Composable
private fun CloudKeyNoteCard(
    onOpenEnginesVoices: () -> Unit,
    onDismiss: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = Primary.copy(alpha = 0.08f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = com.whispereverywhere.ui.CloudKeyNote.HEADLINE,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Dismiss",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = com.whispereverywhere.ui.CloudKeyNote.BODY,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                onClick = onOpenEnginesVoices,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(com.whispereverywhere.ui.CloudKeyNote.BUTTON)
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
        targetValue = if (isEnabled) RecordingActive else RecordingIdle,
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
    configuredEngines: Int,
    totalTranscriptions: Int,
    monthCostCents: Double,
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
                    icon = Icons.Filled.Tune,
                    value = configuredEngines.toString(),
                    label = "Engines"
                )
                StatItem(
                    icon = Icons.Filled.TextFields,
                    value = totalTranscriptions.toString(),
                    label = "Transcriptions"
                )
            }

            // The month's estimated cloud spend — absent entirely for an all-on-device month.
            com.whispereverywhere.data.local.CloudCostMath.monthCostFooter(monthCostCents)?.let { line ->
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
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
                    // Borderless (owner 2026-08-01, with the card-outline removals): the chevron
                    // and the surfaceVariant fill carry the "tap me" signal; the box outline is
                    // retired like every other outline on the black ground.
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
                        unfocusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
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
