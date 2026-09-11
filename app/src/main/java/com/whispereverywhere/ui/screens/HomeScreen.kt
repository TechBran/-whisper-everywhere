package com.whispereverywhere.ui.screens

import android.content.Context
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
import com.whispereverywhere.audio.AudioArbiter
import com.whispereverywhere.data.local.PreferencesManager
import com.whispereverywhere.net.ConnectivityMonitor
import com.whispereverywhere.npu.NpuPackFetch
import com.whispereverywhere.provider.ProviderCatalog
import com.whispereverywhere.service.BatchJobController
import com.whispereverywhere.service.FloatingBubbleService
import com.whispereverywhere.service.WhisperAccessibilityService
import com.whispereverywhere.service.resolveSttProvider
import com.whispereverywhere.transcription.stream.PreviewAutoFetch
import com.whispereverywhere.transcription.stream.PreviewAutoFetchController
import com.whispereverywhere.transcription.stream.PreviewPhase
import com.whispereverywhere.transcription.stream.PreviewPicks
import com.whispereverywhere.transcription.stream.PreviewTrigger
import com.whispereverywhere.transcription.stream.PreviewWorkboard
import com.whispereverywhere.transcription.stream.StreamingPackCatalog
import com.whispereverywhere.transcription.stream.StreamingPackController
import com.whispereverywhere.transcription.stream.StreamingPackCopy
import com.whispereverywhere.transcription.stream.StreamingPackState
import com.whispereverywhere.ui.components.LivePreviewSelectorStrip
import com.whispereverywhere.tts.TtsModelManager
import com.whispereverywhere.tts.TtsPackController
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
    val sttLiveModeGemini by app.preferencesManager.sttLiveModeGeminiFlow.collectAsState()
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
    // (4.4.0, Task 2b fix round 1, B2) The APPLICATION's manager, not a per-composition one: the
    // Play-refusal latch that decides whether this row's install asks Play or downloads directly
    // lives on that instance, and TtsPackController flips it there.
    val ttsModelManager = app.ttsModelManager
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
            // Main Control Button (bubble toggle). 4.3.3: the gate is HomeGate.canEnable — model,
            // mic, overlay — and no longer the accessibility service (accessibility-optional-
            // spec §3); the status line under it says what its absence costs.
            MainControlButton(
                isEnabled = bubbleEnabled,
                canEnable = HomeGate.canEnable(
                    hasSpeechModel = hasSpeechModel,
                    hasMicrophonePermission = hasMicrophonePermission,
                    hasOverlayPermission = hasOverlayPermission,
                ),
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

            // 4.3.3: the accessibility service is a STATUS here, not a blocker — one line under
            // the control naming the trade (typing off, transcripts copied), tapping through to
            // Settings' accessibility row, which carries the Enable path and the platform-aware
            // guidance. Renders nothing while the service is on: the clean dashboard stays clean.
            HomeGate.typingStatusLine(hasAccessibilityEnabled)?.let { status ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onNavigateToSettings() }
                        .padding(vertical = 8.dp),
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Permission chip: visible ONLY while a bubble-blocking permission is missing (owner
            // report 2026-08-01 — granted permissions were reported in Settings but a missing one
            // was named nowhere; the disabled button's generic "Grant permissions in Settings" was
            // the only clue). Tap goes straight to Settings' Permissions rows. When everything is
            // granted this renders nothing, keeping the clean dashboard the refresh established.
            // 4.3.3: the count is mic + overlay — the accessibility service no longer blocks the
            // bubble, so it is not "still needed"; its own status line sits under the control.
            com.whispereverywhere.ui.onboarding.OnboardingLogic.homePermissionChipText(
                com.whispereverywhere.ui.onboarding.OnboardingLogic.missingBubblePermissions(
                    mic = hasMicrophonePermission,
                    overlay = hasOverlayPermission,
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
                // (4.4.0, Task 2b fix round 1, B2) The row names the SOURCE this device installs
                // from — "350 MB fetched from Google Play" on a Play build, where the archive
                // comes from our own on-demand tts_kokoro pack and not from a third party. It
                // does not say "included with the app" on that route, because the pack is
                // on-demand and the fetch really does spend the user's data (Task 6, B1). It
                // said "about 365 MB" on every build before, which was wrong about the size
                // and, on the flow most users take, about the source. Re-read on the same
                // resume tick as hasTtsVoice.
                val voiceClause = remember(resumeTick) {
                    TtsModelManager.voiceSourceClause(
                        TtsModelManager.installRoute(ttsModelManager.state())
                    )
                }
                // Play's own consent dialog, once per ENTRY into NeedsConfirmation — the flow
                // screen's own rule, and needed here for the same reason: this row can start the
                // 350 MB Play fetch, and Play raises its dialog for a download that size.
                val voiceFetch by TtsPackController.state.collectAsState()
                LaunchedEffect(voiceFetch) {
                    if (voiceFetch is NpuPackFetch.FetchState.NeedsConfirmation) {
                        (context as? android.app.Activity)?.let { TtsPackController.confirm(it) }
                    }
                }
                MissingEngineRow(
                    title = "Read-aloud voice not installed",
                    detail = "Reading text aloud needs it — $voiceClause",
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

            // Live words (4.4.1): the previewer's pack arrives on its own, and this is the card
            // that says so — the owner's discovery ruling of 2026-09-11. ABOVE the cloud-key note
            // deliberately: both are dismissible nudges, and this one is the on-device feature
            // the user already paid for in storage, needing no key and no account.
            LiveWordsCard(
                app = app,
                context = context,
                resumeTick = resumeTick,
                localTierInstalled = hasSpeechModel,
            )

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
                    dictationLiveActive(
                        sttProviderId,
                        com.whispereverywhere.service.liveModeFor(sttProviderId, sttLiveMode, sttLiveModeGemini),
                    ),
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

/**
 * 4.4.1 — HOME'S LIVE-WORDS CARD, and the foreground hook that makes the previewer's 73 MB pack
 * arrive without being asked for. The owner's ruling of 2026-09-11: the previewer and its English
 * gate were confirmed working on device, and the gap was DISCOVERY — *"That way the users don't
 * have to discover the setting at all. But the setting works very well."*
 *
 * Every DECISION here is pure and lives elsewhere, so this composable only wires them:
 *
 *  - whether the pack may fetch itself right now: [PreviewAutoFetch.decide], called ONCE
 *  - what the card therefore shows: [PreviewAutoFetch.card], over that same answer
 *  - which source this install has: `StreamingPackManager.state` → `StreamingPackInstall.resolve`
 *  - which route the fetch takes: [PreviewAutoFetchController], through the one pure reduction
 *  - what is RUNNING, on which route, and whether the X can stop it: [PreviewWorkboard] — the one
 *    observable (4.5.0 Task 1), which this card reads instead of the pair it used to
 *  - every word, including the delivered-vs-fetch distinction: [StreamingPackCopy]
 *
 * It mirrors the Settings row (`LivePreviewRows`) where they overlap — the Application's manager,
 * the status-word key on the `state()` read, Play's own confirmation dialog — and shares no
 * actuator with it, because a composable's install lambda cannot outlive the screen and an
 * auto-fetch has to. `LiveWordsCardPinTest` pins all of that as source.
 *
 * The three inputs it reads live rather than through a flow are each read from the one owner the
 * app already has for that question — the session from `AudioArbiter` (whose `isCapturing` is the
 * house's single reading of "a session is in flight"), the batch job from `BatchJobController`
 * (the same input `localPreviewArms` reads), the connection from `ConnectivityMonitor`, once per
 * foreground. `localPreviewArms` itself is untouched: the owner tests on Auto deliberately, and
 * the "pick English" sentence belongs on the installed card, not in the gate.
 */
@Composable
private fun LiveWordsCard(
    app: WhisperEverywhereApp,
    context: Context,
    resumeTick: Int,
    localTierInstalled: Boolean,
) {
    // THE ONE COLLECTOR (owner rulings 2026-09-11). The language selection funnels through one
    // writer — PreferencesManager.setSelectedLanguage, called from the dropdown below and from
    // onboarding's Continue and nowhere else — so ONE collector on its StateFlow serves all three
    // moments the rulings name:
    //
    //  - the picker's change, seen the instant it is written (ruling 2);
    //  - onboarding's Continue, seen when this screen first composes after it (ruling 2);
    //  - and the value ALREADY in place when the app comes to the foreground, because a StateFlow
    //    replays its current value to a new collector — which is the top-up for every user whose
    //    language was set before this update and who will therefore never re-select it.
    //
    // It cannot re-fire on an unchanged value: a StateFlow conflates equal values, so a
    // recomposition or a return to this screen re-emits nothing, and the decision's own
    // already-tried-this-launch and in-flight guards answer anything that slips past that.
    // Nothing is collected in the data layer and nothing is fetched at either selection site: a
    // SharedPreferences writer called from a click handler has no business owning a download.
    val selectedLanguage by app.preferencesManager.selectedLanguage.collectAsState()
    // ...and the pack is the CATALOGUE's answer for that language — null on Auto and on every
    // language with no row, which is how "no selected language, no live words" costs no predicate
    // of its own: with no pack there is no state to read, no card to show and nothing to fetch.
    val pack = StreamingPackCatalog.forLanguage(selectedLanguage)
    // The language's own NAME, from the picker's one table, because every sentence on this card
    // names the language it is about (CONTROLLER RULING 2026-09-11). The fallback is the code
    // itself, which is unreachable — a pack's language is always a picker code
    // (StreamingPackCopyTest holds that) — and is a readable word rather than a crash if a later
    // catalogue row arrives before its picker entry.
    val languageName = PreferencesManager.languageDisplayName(selectedLanguage) ?: selectedLanguage
    // (4.5.0 Task 1) THE ONE OBSERVABLE, and the only thing this card reads about work in flight.
    // It replaces the pair — `StreamingPackController.state` for Play's own fetch and
    // `PreviewAutoFetchController.line` for ours — that made "is work running?" two questions,
    // only one of which the Settings row collected.
    val previewWorkboard by PreviewWorkboard.work.collectAsState()
    val previewWork = pack?.let { previewWorkboard[it.language] }
    // (4.5.0 Task 3b) WHY a transfer would be starting, which is the one input the connection
    // rule branches on: *"an unasked background transfer waits for wifi; a transfer the user just
    // caused by picking a language happens at once, because the pick IS the consent."* The
    // selection flow cannot answer it — it replays its current value to every new collector, so a
    // language chosen ten seconds ago and one chosen before the last update arrive identically —
    // and `PreviewPicks` is the process-scoped record of the gesture itself, written by the one
    // writer of the selection. Collected rather than read, so a pick re-asks ON THE SPOT.
    val pickedThisProcess by PreviewPicks.picked.collectAsState()
    val trigger = if (selectedLanguage in pickedThisProcess) {
        PreviewTrigger.SELECTION
    } else {
        PreviewTrigger.TOP_UP
    }
    val showLiveWords by app.preferencesManager.localPreviewEnabledFlow.collectAsState()
    // Read into a local mirror — the house convention for plain-var prefs read in composition,
    // and the cloud-key note's own shape for the same gesture — and re-read on each foreground,
    // so a delete made in Settings is seen here without depending on the NavHost having disposed
    // this screen (review r1, nit 4). A stale read here errs toward re-fetching, which is the
    // direction AF3 and AF4 are about.
    // ...and it is read FOR THE SELECTED LANGUAGE (4.4.1 acquisition amendment): a user who
    // deleted the Spanish model has declined Spanish, not live words. The selected code is the
    // same code the pack above was resolved from, so the flag the X writes, the flag this reads
    // and the pack the decision is about cannot name three different languages.
    var saidNo by remember(resumeTick, selectedLanguage) {
        mutableStateOf(app.preferencesManager.livePreviewDeclined(selectedLanguage))
    }
    // (CONTROLLER RULING 2026-09-11, CHANGE 4) Has the user already SEEN live words? Written by
    // the gate's own call site the first time the previewer arms, so it can become true while
    // this screen is in the background — hence the resume key, the same one `saidNo` uses. It
    // retires the announcement and nothing else.
    val hasArmed = remember(resumeTick) { app.preferencesManager.livePreviewArmedOnce }
    // ONE phase and ONE predicate where 4.4.1 had a status word plus a non-null line: the record
    // covers Play's fetch and our own install alike, so neither can be running unseen here.
    val previewPhase = previewWork?.phase
    val working = previewWork?.inFlight == true
    // BOTH SYSTEM READS OFF THE COMPOSITION THREAD, on the app's start destination (review r1,
    // B4): state() is nine File stats plus a Play getPackLocation through PlayPacks.assetsPath,
    // and isUnmetered() is a getSystemService plus a getNetworkCapabilities. The Settings row's
    // own comment says that read is too expensive for a recomposition; here it would also be
    // paid on the first frame and on every resume by every user — one who deleted the model, one
    // who dismissed the card, one with no local tier — for an answer that is then discarded.
    // HomeScreen's own pattern for exactly this shape of read is produceState + Dispatchers.IO
    // (the keystore and installedModel snapshots above). Keyed as the remembers were: the resume
    // tick, and the PHASE — never the bytes, which tick several times a second for the whole
    // 73 MB, and never the record itself for the same reason; plus the SELECTED LANGUAGE, because
    // a different language is a different pack and therefore a different state to read. One key
    // where 4.4.1 needed two (a status word and "is our own line non-null"), because one
    // observable covers both.
    @Suppress("ProduceStateDoesNotAssignValue")
    val packStateSnapshot by produceState<StreamingPackState?>(
        null, resumeTick, selectedLanguage, previewPhase,
    ) {
        // CLEARED FIRST (CONTROLLER RULING 2026-09-11, CHANGE 3 — review r2's nit 2). produceState
        // keeps its PREVIOUS value across a key change, and our own install's terminal phase
        // re-keys this producer. In that window the old snapshot still read
        // PackDelivered/Downloadable with busy() false and the launch's attempt spent, so
        // `decide` answered OFFER and the card rendered "Install the English preview model" over
        // a model that had just finished installing. Clearing it sends that frame down the
        // not-yet-known branch below — NONE, i.e. nothing said — until the new state lands, which
        // is the first of the two fixes the nit named.
        value = null
        // No pack for this language (Auto, or a language with no row) is no state at all, so the
        // not-yet-known branch is also the AUTO branch: nothing decided, nothing said.
        value = pack?.let { withContext(Dispatchers.IO) { app.streamingPackManager.state(it) } }
    }
    @Suppress("ProduceStateDoesNotAssignValue")
    val unmeteredSnapshot by produceState<Boolean?>(null, resumeTick) {
        value = withContext(Dispatchers.IO) { ConnectivityMonitor(context).isUnmetered() }
    }
    // Plain locals, so the "not yet known" check below reads as one null test (a delegated
    // property cannot be smart-cast) and so the decision and the card see the same snapshot.
    val packState = packStateSnapshot
    val unmetered = unmeteredSnapshot
    // Until both snapshots have landed there is nothing to decide and nothing true to say, so
    // the answer is NONE for that one frame — the same flicker the cloud-key note's own resume
    // snapshot has. The other default would be a 73 MB transfer decided on inputs not yet read.
    val decision = if (packState == null || unmetered == null) {
        PreviewAutoFetch.Decision.NONE
    } else {
        PreviewAutoFetch.decide(
            selectedLanguage = selectedLanguage,
            packLanguage = pack?.language,
            state = packState,
            userSaidNo = saidNo,
            showLiveWords = showLiveWords,
            localTierInstalled = localTierInstalled,
            // (4.5.0 Task 3b) The cause, mapped to the starter by the enum rather than by a
            // literal here: this is the ONE input the metered rule and the back-off branch on.
            starter = trigger.starter,
            unmetered = unmetered,
            sessionActive = AudioArbiter.isCapturing(),
            batchJobActive = BatchJobController.active != null,
            packWorkInFlight = PreviewAutoFetchController.busy(),
            // Per language: the latch stops a loop on ONE pack, and a user who picks a second
            // language in the same launch has made a new decision, not repeated an old one.
            attemptedThisLaunch = pack != null &&
                PreviewAutoFetchController.attemptedThisLaunch(pack),
            backedOff = PreviewAutoFetch.backedOff(
                lastFailureAtMs = app.preferencesManager.livePreviewAutoFetchFailedAt,
                nowMs = System.currentTimeMillis(),
            ),
        )
    }
    // THE COLLECTOR'S ONE ACTUATION, and the whole of it: it performs the decision above and
    // tests nothing of its own. Keyed on the resume tick, so every return to the foreground asks
    // again; on the SELECTED LANGUAGE, so picking one asks on the spot (ruling 2); and on the
    // answer, so the effect cannot fire under a stale one. Both `?.let`s are the snapshots'
    // null-unwraps, not second conditions: a FETCH is only ever answered over a pack that matches
    // the selected language and a state that has been read.
    LaunchedEffect(resumeTick, selectedLanguage, decision) {
        if (decision == PreviewAutoFetch.Decision.FETCH) {
            pack?.let { p ->
                packState?.let { PreviewAutoFetchController.start(app, p, it, trigger) }
            }
        }
    }
    // Play's own consent dialog, once per ENTRY into AWAITING_ANSWER — the missing-voice row's
    // rule above, needed here for the same reason: this card can start a 73 MB Play fetch, and
    // Play raises its own dialog for a transfer that size. Never a re-ask of ours.
    val playAwaitsAnAnswer = previewPhase == PreviewPhase.AWAITING_ANSWER
    // ...and the SAME gesture, offered on the card. Raising it once per entry is right (a dialog
    // re-raised on every recomposition is unusable), but it left a user who back-pressed out of
    // Play's dialog on a note reading "tap to answer" with nothing to tap but the permanent-no X
    // — the metered path's own state. The Settings row's workLineTappable + previewRowTap
    // lesson, inherited rather than re-learned (review r1, B3).
    val answerPlay: () -> Unit = {
        (context as? android.app.Activity)?.let { StreamingPackController.confirm(it) }
    }
    LaunchedEffect(previewPhase) {
        if (playAwaitsAnAnswer) answerPlay()
    }
    // The X is the PERMANENT no, so it records the decision FIRST — the delete row's own rule,
    // for the same reason: a cancellation that threw must not leave a device that re-fetches what
    // the user just refused. Then it abandons the arrival it was pressed on, because a "no" that
    // lets 73 MB finish landing is not a no (CONTROLLER RULING 2026-09-11, CHANGE 2).
    //
    // (4.5.0 Task 1) FOR THIS LANGUAGE, and only if that language's work can actually be stopped.
    // The cancel is keyed by the same code the flag above is written for, so the X on one
    // language's card can no longer abandon another language's transfer; and the route table it
    // consults (`PreviewRoute.stopsBeforeTheCopy`, plus the phase term) is what makes "cancel"
    // mean one thing. On the DELIVERED route it means nothing can be stopped — Play has already
    // put those bytes on the device and the local copy is not cancellation-cooperative — so that
    // install lands, costing no data, while the flag written above still silences the card and
    // the auto-fetch for good. The X stays a DISMISS on every state, which is what it is labelled.
    val dismiss: () -> Unit = {
        app.preferencesManager.setLivePreviewDeclined(selectedLanguage, true)
        saidNo = true
        PreviewAutoFetchController.cancel(selectedLanguage)
    }
    // ...AND IT IS OFFERED ONLY WHERE THE RECORD SAYS IT MAY BE PRESSED (4.5.0 Task 1, fix round
    // 2 — review r2's B1c). The X is one gesture with two halves and only the second is
    // refusable: `cancel` returns on `!work.cancellable`, but the permanent no above it is
    // written unconditionally. Pressed during the INSTALLING phase of a Play fetch — a streamed
    // sha256 of 72,654,782 B plus a copy, so a window of many seconds — it wrote the declined
    // flag, `card`'s `userSaidNo` outranked `workInFlight` so this card vanished as if the no had
    // taken effect, the install landed anyway, and `localPreviewArms` has no declined term: live
    // words then appeared for the user who had pressed the only control on screen to refuse them.
    // That is the *installed AND declined* outcome this feature's own comments name twice as
    // unacceptable, surviving at exactly the two phases fix round 1 moved OUT of `cancellable`.
    //
    // So the brief's second half — "or the UI must not offer a cancel on the route where it
    // cannot" — is answered where it belongs, in the UI: no X over work that cannot be stopped.
    // The card is then a receipt (the Settings row's own answer, one row per screen), and the
    // announcement that follows the install carries an X that really does mean no. A gated WRITE
    // was the alternative and is worse: a control that visibly does nothing is the same defect as
    // the offer row that started this round.
    val dismissWhereItWouldMeanSomething: (() -> Unit)? =
        if (previewWork?.dismissable == false) null else dismiss
    when (
        PreviewAutoFetch.card(
            // A card is about ONE language's pack. On Auto (and on any language the catalogue
            // has no row for) there is none, and `workInFlight` spans the Settings row's own
            // fetch — so without this the card would narrate an English transfer under the
            // selected language's name.
            hasPackForSelection = pack != null,
            // Not-yet-read is not installed: the card says nothing for that one frame.
            installed = packState?.isInstalled == true,
            // The announcement is one-time without being a refusal: once live words have armed
            // for a real session the user has watched them appear, and the X stays free to mean
            // "no" rather than "I have read this" (CONTROLLER RULING 2026-09-11, CHANGE 4).
            previewHasArmed = hasArmed,
            // (4.4.1 pass 3, ITEM 3) ...and the other way the announcement can be false: with no
            // on-device tier every session is a cloud session, the gate refuses on
            // `!isCloudSession`, and "Live words are on" is permanently untrue — which
            // `previewHasArmed` could never retire, because nothing would ever write it. The same
            // input the decision reads, so the card and the fetch agree about who this is for.
            localTierInstalled = localTierInstalled,
            userSaidNo = saidNo,
            // The switch silences the card as well as the fetch: with it off there is no true
            // sentence left for this card to spell, least of all "Live words are on" over an
            // install that landed before it was turned off (review r1, B2).
            showLiveWords = showLiveWords,
            // ONE predicate over the ONE observable: it already spans Play's fetch and our own
            // install, so the disjunction 4.4.1 needed here is gone — and with it the chance of
            // one half being read and the other forgotten.
            workInFlight = working,
            decision = decision,
        )
    ) {
        PreviewAutoFetch.Card.NONE -> Unit
        PreviewAutoFetch.Card.WORKING -> LiveWordsNote(
            title = StreamingPackCopy.CARD_TITLE,
            body = StreamingPackCopy.cardWorking(languageName),
            // ONE line, from the one observable, whichever route is carrying the bytes — and
            // between the decision and the starter's first board write, the same dead-time line
            // the feature has always used for that gap.
            note = previewWork?.let { StreamingPackCopy.workLine(it) }
                ?: StreamingPackCopy.PROGRESS_STARTING,
            // The one in-flight state whose note asks for a gesture gets the gesture; every
            // other one is work with nothing to ask, and a button on those would re-enter the
            // fetch mid-transfer (the row's own B1 lesson).
            action = if (playAwaitsAnAnswer) StreamingPackCopy.CARD_ANSWER_PLAY else null,
            onAction = answerPlay,
            onDismiss = dismissWhereItWouldMeanSomething,
        )
        // The OFFER is only ever answered over a matched pack and a read state, so these two
        // `?.let`s unwrap the snapshots rather than deciding anything — and the words, the route
        // and the tap all come from that one pair, which is what keeps them from naming different
        // sources or different languages.
        PreviewAutoFetch.Card.OFFER -> pack?.let { p ->
            packState?.let { offered ->
                LiveWordsNote(
                    title = StreamingPackCopy.CARD_TITLE,
                    // The SAME per-source table the Settings row reads, so this card cannot
                    // promise a route the tap will not take.
                    body = StreamingPackCopy.cardOffer(offered, languageName),
                    // What the pick buys and what Auto costs, on the state where the note slot
                    // is free — the language step's sentence is for someone still choosing.
                    note = StreamingPackCopy.cardLanguageNote(languageName),
                    action = StreamingPackCopy.cardAction(offered, languageName),
                    onAction = { PreviewAutoFetchController.start(app, p, offered, PreviewTrigger.TAP) },
                    onDismiss = dismissWhereItWouldMeanSomething,
                )
            }
        }
        PreviewAutoFetch.Card.INSTALLED -> LiveWordsNote(
            title = StreamingPackCopy.CARD_INSTALLED_TITLE,
            body = StreamingPackCopy.cardInstalled(languageName),
            note = StreamingPackCopy.cardLanguageNote(languageName),
            action = null,
            onAction = {},
            onDismiss = dismissWhereItWouldMeanSomething,
        )
    }
}

/**
 * The live-words card's layout — [CloudKeyNoteCard]'s own, deliberately: the house already has a
 * dismissible nudge on this screen (headline row with an X, body, an action button) and a second
 * visual language for the same job would read as a second kind of thing. All copy arrives as
 * parameters from [StreamingPackCopy]; this shell spells no sentence and holds no rule. Untested
 * UI by house convention — the visibility and the words are both pinned elsewhere.
 *
 * @param onDismiss the X, or NULL to draw no X at all (4.5.0 Task 1, fix round 2 — review r2's
 *        B1c). The caller decides that from the one observable's own `dismissable`: this card's
 *        X writes a permanent no as well as abandoning the arrival, so over work that cannot be
 *        stopped it would fire only the half that cannot be taken back. A card with no X is the
 *        receipt this feature already renders everywhere else for work in flight.
 */
@Composable
private fun LiveWordsNote(
    title: String,
    body: String,
    note: String?,
    action: String?,
    onAction: () -> Unit,
    onDismiss: (() -> Unit)?,
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
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                if (onDismiss != null) {
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = StreamingPackCopy.CARD_DISMISS,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (note != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (action != null) {
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = onAction,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(action)
                }
            }
        }
    }
    Spacer(modifier = Modifier.height(16.dp))
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

    // Find the display name for the current selection — through the one owner of code-to-word
    // (4.4.1), so this field and the live-words card cannot name the same language differently.
    val selectedDisplayName =
        PreferencesManager.languageDisplayName(selectedLanguage) ?: "Auto-detect"

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

            // (4.5.0 Task 3c) THE PROGRESS LIVES ABOVE THE SELECTOR — owner ruling, 2026-09-11:
            // *"you can incorporate the status for that model being downloaded right there above
            // the language selector. That way users can see the progress right away and know that
            // their language is ready for selection."* It is the honest half of ruling 3b: a pick
            // now spends the user's data on any connection, so the place they picked has to show
            // it happening. The strip reads the ONE observable and this card reads nothing: no
            // decision, no board, no actuator here — the selection still writes one preference.
            LivePreviewSelectorStrip()

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
                // ...and what that costs (owner ruling 1, 2026-09-11): *"if they leave it in
                // auto, then you get no live streaming at all. And that will seem to be a very
                // fair trade-off."* A trade the user is never told about is not a trade, and this
                // is the one surface where they are standing on the Auto side of it — there is no
                // card on Auto, by design. The sentence is the previewer's own, from the file
                // that owns every word of it.
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = StreamingPackCopy.AUTO_NO_LIVE_WORDS,
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
