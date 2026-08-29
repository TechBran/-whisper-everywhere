package com.whispereverywhere.ui.screens

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
import com.whispereverywhere.model.ModelMigration
import com.whispereverywhere.model.ModelScope
import com.whispereverywhere.model.WhisperCatalog
import com.whispereverywhere.provider.ProviderId
import com.whispereverywhere.service.WhisperAccessibilityService
import com.whispereverywhere.tts.cloud.CloudVoice
import com.whispereverywhere.tts.cloud.GeminiTtsVoices
import com.whispereverywhere.tts.cloud.OpenAiTtsVoices
import com.whispereverywhere.tts.cloud.SonioxTtsVoices
import com.whispereverywhere.ui.theme.*
import com.whispereverywhere.util.formatBytes
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------------------------------------
// Read-aloud voice-picker copy — pure, Compose-free so it is JVM-unit-testable (Task 6). Every
// user-facing string here is HONEST: a cloud preview spends the user's own key and real money, and
// two of the three providers expose no speed parameter, so the copy says so plainly. NO speed
// claim ever appears — a synthesized voice is not sold on being fast.
// ---------------------------------------------------------------------------------------------

/** The fixed, short sentence a Preview taps synthesizes — a pangram, so every phoneme is heard. */
internal const val TTS_PREVIEW_SAMPLE = "The quick brown fox jumps over the lazy dog."

/**
 * Caption under a cloud voice list. A preview runs the REAL provider request against the user's own
 * key, so it costs them real money — never dressed up, never a speed claim.
 */
internal fun ttsPreviewCaption(): String =
    "Previews use your key and cost real money."

/**
 * Per-provider price note from the numbers re-verified against live docs 2026-07-30. Honest about
 * uncertainty (Gemini's TTS models are preview, with no committed GA price) and never a speed claim.
 * Exhaustive over [ProviderId] so a fourth provider must get its own reviewed line, not inherit one.
 */
internal fun ttsProviderPriceNote(providerId: ProviderId): String = when (providerId) {
    ProviderId.OPENAI ->
        "About \$0.015 per minute of audio, billed to your OpenAI key."
    ProviderId.ELEVENLABS ->
        "About half a credit per character, billed to your ElevenLabs plan."
    ProviderId.GEMINI ->
        "Preview model — Google's pricing is not final; billed to your Gemini key."
    ProviderId.SONIOX ->
        "About \$0.70 per hour of audio (about 1.2¢ per minute), billed to your Soniox key."
}

/**
 * The honest no-speed-control note for the two providers whose synthesis API has no request-time
 * speed parameter (ElevenLabs, Gemini): the speed setting governs the on-device voice — and the
 * on-device fallback — only. OpenAI DOES take a speed field, so it shows no such note (null).
 * This is a disclosure, not a speed claim: it tells the user the control does nothing here.
 */
internal fun ttsNoSpeedControlNote(providerId: ProviderId): String? = when (providerId) {
    ProviderId.ELEVENLABS, ProviderId.GEMINI ->
        "This provider has no speed control; the speed setting applies to on-device voices only."
    ProviderId.OPENAI -> null
    // Soniox DOES take a request-time speed field (0.7–1.3), so it honors the speed setting like
    // OpenAI — stays null on purpose; do NOT move it into the ELEVENLABS/GEMINI no-speed branch.
    ProviderId.SONIOX -> null
}

/**
 * Friendly name for a stored cloud [voiceId], resolved from the provider's catalog (static for
 * OpenAI/Gemini, [dynamicVoices] for ElevenLabs). Falls back to the raw id if the catalog does not
 * carry it (an ElevenLabs id whose dynamic catalog has not been fetched yet), and null when nothing
 * is chosen — the caller shows "choose a voice". No claim, just a label.
 */
internal fun cloudVoiceDisplayName(
    providerId: ProviderId,
    voiceId: String?,
    dynamicVoices: List<CloudVoice>?,
): String? {
    if (voiceId.isNullOrBlank()) return null
    val catalog = when (providerId) {
        ProviderId.OPENAI -> OpenAiTtsVoices.ALL
        ProviderId.GEMINI -> GeminiTtsVoices.ALL
        ProviderId.ELEVENLABS -> dynamicVoices ?: emptyList()
        ProviderId.SONIOX -> SonioxTtsVoices.ALL
    }
    return catalog.firstOrNull { it.voiceId == voiceId }?.displayName ?: voiceId
}

/**
 * The privacy line at the foot of the in-app "How read-aloud works" guide.
 *
 * It used to assert, in bold, "Everything is generated on your phone — nothing you read or hear
 * ever leaves the device." That became FALSE the moment cloud read-aloud shipped (Task 5/6): a user
 * who selects a cloud voice sends the selected text to that provider. The unqualified claim
 * contradicted the flipped privacy §6, the v3 disclosure, the Play declaration, and the code itself.
 *
 * Qualified to the two-state truth — on-device by default, cloud only if the user chooses a cloud
 * voice — kept consistent with [com.whispereverywhere.ui.screens.cloudDisclosureMainText] and the
 * privacy pair. Pure so the honesty is JVM-pinned (the guide Text is Compose-untestable).
 */
internal fun readAloudGuidePrivacyLine(): String =
    "By default everything is generated on your phone, and nothing you read or hear leaves the " +
        "device. If you choose a cloud read-aloud voice, the text you select is sent to that " +
        "provider to be spoken."

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToPrivacyPolicy: () -> Unit = {},
    onNavigateToTerms: () -> Unit = {},
    onNavigateToModelOnboarding: () -> Unit = {},
    onNavigateToLicenses: () -> Unit = {},
    onNavigateToCloudProviders: () -> Unit = {}
) {
    val context = LocalContext.current
    val app = WhisperEverywhereApp.getInstance()
    val modelManager = app.whisperModelManager

    val vibrationEnabled by app.preferencesManager.vibrationEnabled.collectAsState()
    val bubbleAlwaysOn by app.preferencesManager.bubbleAlwaysOn.collectAsState()
    val dictationFirstKeyboard by app.preferencesManager.dictationFirstKeyboard.collectAsState()
    val preferDeviceAudio by app.preferencesManager.preferDeviceAudio.collectAsState()

    // Bump to force a re-read of installed-model / disk-usage after a delete or when
    // returning from the model-onboarding flow.
    var modelRefreshKey by remember { mutableStateOf(0) }
    var showDeleteModelDialog by remember { mutableStateOf(false) }

    // Read aloud (Track F)
    val ttsScope = rememberCoroutineScope()
    var ttsRefreshKey by remember { mutableStateOf(0) }
    val ttsManager = remember { com.whispereverywhere.tts.TtsModelManager(context) }
    val ttsInstalled = remember(ttsRefreshKey) { ttsManager.isInstalled() }
    var ttsDownloadStatus by remember { mutableStateOf<String?>(null) }
    var ttsSpeedState by remember { mutableStateOf(app.preferencesManager.ttsSpeed) }
    var showDeleteVoiceDialog by remember { mutableStateOf(false) }
    var showReadAloudGuide by remember { mutableStateOf(false) }

    val installedModel = remember(modelRefreshKey) { modelManager.installedModel() }

    // Unsupported-tier migration: non-null only for extreme/ultra. Merely retired tiers
    // (eco, base) never raise this card — see WhisperModel.unsupported.
    val retiredModel = remember(modelRefreshKey) { modelManager.unsupportedInstalledModel() }
    val migrationScope = rememberCoroutineScope()
    var migrationBusy by remember { mutableStateOf(false) }
    var migrationStatus by remember { mutableStateOf<String?>(null) }

    // Compute models-dir total disk usage off the main thread.
    // Suppression: the producer DOES assign `value` (directly below); the compose-runtime
    // checker just can't see assignments that follow a suspend call in this lint version.
    @Suppress("ProduceStateDoesNotAssignValue")
    val modelsDirUsageBytes by produceState(initialValue = 0L, key1 = modelRefreshKey) {
        val computed = withContext(Dispatchers.IO) {
            modelManager.modelsDir().walkTopDown().filter { it.isFile }.map { it.length() }.sum()
        }
        value = computed
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
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.primary,
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
                if (retiredModel != null) {
                    // MF3: the target must match the retired model's scope — a multilingual
                    // user must land on "multi" (multilingual), not silently on the ENGLISH-only
                    // default. See ModelMigration.targetIdFor.
                    val target = WhisperCatalog.byId(ModelMigration.targetIdFor(retiredModel.scope))!!
                    // Re-derived every recomposition (same idiom as the permission checks
                    // below) so the card reacts to connectivity and to a completed download.
                    val migrationAction = ModelMigration.decide(
                        selectedId = app.preferencesManager.selectedModelId,
                        selectedInstalled = modelManager.isInstalled(retiredModel),
                        targetInstalled = modelManager.isInstalled(target),
                        online = isNetworkAvailable(context),
                    )
                    Surface(
                        color = Warning.copy(alpha = 0.1f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Filled.ErrorOutline,
                                    contentDescription = null,
                                    tint = Warning,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "This model is no longer supported",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "${target.displayName} is much faster and works well " +
                                    "for everyday dictation. We'll download it " +
                                    "(${formatBytes(target.approxBytes)}), then free up the " +
                                    "space your old model is using.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            when {
                                migrationBusy -> {
                                    Text(
                                        text = migrationStatus ?: "Switching…",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                                migrationAction is ModelMigration.Action.WaitForNetwork -> {
                                    Text(
                                        text = "Connect to the internet to switch.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                                else -> {
                                    Button(
                                        onClick = {
                                            // MF2: the busy flag must be set here, synchronously,
                                            // at tap time — not inside the launched coroutine.
                                            // rememberCoroutineScope dispatches on
                                            // AndroidUiDispatcher.Main, which always dispatches
                                            // (never runs inline), so a second tap inside that
                                            // dispatch+recomposition window would otherwise start
                                            // a second coroutine and race the first: coroutine A
                                            // could finish, delete the retired file, then
                                            // coroutine B's pre-existing dest.delete() unlinks the
                                            // freshly-verified replacement, leaving the user with
                                            // no model and no way back (onboarding has no back
                                            // navigation).
                                            if (!migrationBusy) {
                                                migrationBusy = true
                                                migrationScope.launch {
                                                    migrationStatus = "Starting…"
                                                    try {
                                                        when (migrationAction) {
                                                            is ModelMigration.Action.OfferDownload -> {
                                                                modelManager.download(
                                                                    target,
                                                                    onProgress = { soFar, total ->
                                                                        val safeTotal = if (total > 0L) total
                                                                            else target.approxBytes
                                                                        migrationStatus = "${formatBytes(soFar)} / " +
                                                                            formatBytes(safeTotal)
                                                                    },
                                                                    onVerifying = { migrationStatus = "Verifying…" },
                                                                )
                                                                // Re-evaluate rather than sequencing by hand:
                                                                // download() only returns once the target is
                                                                // verified on disk, so this now resolves to
                                                                // SwapAndDelete.
                                                                val after = ModelMigration.decide(
                                                                    selectedId = app.preferencesManager.selectedModelId,
                                                                    selectedInstalled =
                                                                        modelManager.isInstalled(retiredModel),
                                                                    targetInstalled = modelManager.isInstalled(target),
                                                                    online = isNetworkAvailable(context),
                                                                )
                                                                if (after is ModelMigration.Action.SwapAndDelete) {
                                                                    app.preferencesManager.selectedModelId = after.toId
                                                                    modelManager.deleteModelFile(
                                                                        WhisperCatalog.byId(after.fromId)!!
                                                                    )
                                                                }
                                                            }
                                                            is ModelMigration.Action.SwapAndDelete -> {
                                                                app.preferencesManager.selectedModelId =
                                                                    migrationAction.toId
                                                                modelManager.deleteModelFile(
                                                                    WhisperCatalog.byId(migrationAction.fromId)!!
                                                                )
                                                            }
                                                            else -> Unit // WaitForNetwork/None: no button shown
                                                        }
                                                    } catch (c: kotlinx.coroutines.CancellationException) {
                                                        // CC1: leaving Settings or rotating cancels
                                                        // migrationScope. Rethrow so structured
                                                        // concurrency completes the cancellation
                                                        // instead of it being caught below and
                                                        // toasted as a raw framework string on
                                                        // whatever screen the user landed on.
                                                        throw c
                                                    } catch (t: Throwable) {
                                                        android.widget.Toast.makeText(
                                                            context,
                                                            t.message ?: "Couldn't switch to ${target.displayName}",
                                                            android.widget.Toast.LENGTH_LONG,
                                                        ).show()
                                                    } finally {
                                                        migrationBusy = false
                                                        migrationStatus = null
                                                        modelRefreshKey++
                                                    }
                                                }
                                            }
                                        },
                                        enabled = !migrationBusy,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("Switch to ${target.displayName}")
                                    }
                                }
                            }
                        }
                    }
                }

                if (installedModel != null) {
                    // Every file the tier occupies, not just its primary (final review F1): for
                    // `npu` a single-file read omits the 225,316,864 B decoder and disagrees with
                    // the "Model storage" directory walk on this same screen.
                    val onDiskBytes = remember(modelRefreshKey, installedModel.id) {
                        modelManager.installedBytes(installedModel)
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
                    subtitle = "Pick a speech-model tier " +
                        "(${WhisperCatalog.pickable.joinToString(" / ") { it.displayName }})",
                    onClick = onNavigateToModelOnboarding
                )

                if (installedModel != null) {
                    SettingsItem(
                        icon = Icons.Filled.Delete,
                        title = "Delete current model",
                        // The SAME figure the storage row above uses and the same set of files
                        // delete() removes (final review F1). Promising "Frees 127 MB" while
                        // removing 342 MiB — or, before F1, promising 127 MB and stranding 215 MB —
                        // is the shape of untruth this branch has already paid for four times.
                        subtitle = "Frees ${formatBytes(modelManager.installedBytes(installedModel))}" +
                            " — you'll need to re-download to transcribe",
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

            // Read aloud (Track F): on-device Kokoro TTS
            SettingsSection(title = "Read aloud") {
                when {
                    ttsInstalled -> {
                        SettingsItem(
                            icon = Icons.Filled.GraphicEq,
                            title = "Kokoro voice — installed",
                            subtitle = "Speaks highlighted or copied text aloud, fully " +
                                "on-device. Tap for the full how-to.",
                            onClick = { showReadAloudGuide = true },
                        )
                        SettingsItem(
                            icon = Icons.Outlined.Info,
                            title = "How to use read-aloud",
                            subtitle = "The three ways to speak text, and the playback controls",
                            onClick = { showReadAloudGuide = true },
                        )
                        SettingsItem(
                            icon = Icons.Filled.RecordVoiceOver,
                            title = "Voice",
                            subtitle = "Choose the read-aloud engine and voice in Engines & voices",
                            onClick = onNavigateToCloudProviders,
                        )
                        SettingsItem(
                            icon = Icons.Filled.Speed,
                            title = "Speech rate",
                            subtitle = "${ttsSpeedState}x — tap to change",
                            onClick = {
                                val speeds = listOf(0.8f, 1.0f, 1.25f, 1.5f)
                                val next = speeds[(speeds.indexOf(ttsSpeedState) + 1)
                                    .mod(speeds.size)]
                                ttsSpeedState = next
                                app.preferencesManager.ttsSpeed = next
                            },
                        )
                        SettingsItem(
                            icon = Icons.Filled.Delete,
                            title = "Delete the voice",
                            subtitle = "Frees ~410 MB — re-download any time",
                            onClick = { showDeleteVoiceDialog = true },
                        )
                    }
                    ttsDownloadStatus != null -> {
                        SettingsItem(
                            icon = Icons.Filled.CloudDownload,
                            title = "Downloading voice…",
                            subtitle = ttsDownloadStatus ?: "",
                        )
                    }
                    else -> {
                        SettingsItem(
                            icon = Icons.Filled.CloudDownload,
                            title = "Download the read-aloud voice",
                            subtitle = "Kokoro (365 MB download): speaks highlighted text " +
                                "aloud, entirely on-device",
                            onClick = {
                                ttsDownloadStatus = "Starting…"
                                ttsScope.launch {
                                    runCatching {
                                        ttsManager.download(
                                            onProgress = { soFar, total ->
                                                ttsDownloadStatus =
                                                    "${soFar / 1_000_000} / ${total / 1_000_000} MB"
                                            },
                                            onExtracting = {
                                                ttsDownloadStatus = "Verifying and unpacking…"
                                            },
                                        )
                                    }.onFailure {
                                        android.widget.Toast.makeText(
                                            context,
                                            it.message ?: "Voice download failed",
                                            android.widget.Toast.LENGTH_LONG,
                                        ).show()
                                    }
                                    ttsDownloadStatus = null
                                    ttsRefreshKey++
                                }
                            },
                        )
                    }
                }
            }

            // Cloud providers (Release C1): bring-your-own-key credential management. No audio
            // is sent anywhere yet — that lands in C2, gated behind the same key.
            SettingsSection(title = "Cloud providers") {
                val configured = app.preferencesManager.providerAccounts.configured()
                SettingsItem(
                    icon = Icons.Filled.Cloud,
                    title = "Cloud providers",
                    subtitle = if (configured.isEmpty())
                        "Use your own OpenAI, Gemini or ElevenLabs key"
                    else
                        "${configured.size} configured",
                    onClick = onNavigateToCloudProviders
                )
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
                    icon = Icons.Filled.KeyboardHide,
                    title = "Dictation-first keyboard",
                    subtitle = "Hide the on-screen keyboard when a text field focuses — dictate " +
                        "with the bubble instead, and tap its keyboard button whenever you want " +
                        "the keyboard back. Needs the accessibility service; some keyboards may " +
                        "ignore it",
                    checked = dictationFirstKeyboard,
                    onCheckedChange = { enabled ->
                        app.preferencesManager.setDictationFirstKeyboard(enabled)
                        // Applies live: the service re-reads the pref and flips the show mode now.
                        com.whispereverywhere.service.WhisperAccessibilityService.applyKeyboardPreference()
                    }
                )
                SettingsSwitchItem(
                    icon = Icons.Filled.MusicNote,
                    title = "Capture device audio for media",
                    subtitle = "While a video or podcast plays, transcribe its audio stream " +
                        "directly (mic off — no room noise). Asks for screen-capture " +
                        "permission the first time",
                    checked = preferDeviceAudio,
                    onCheckedChange = { app.preferencesManager.setPreferDeviceAudio(it) }
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

    // Read-aloud how-to guide (user request 2026-07-18: people need to know exactly how).
    if (showReadAloudGuide) {
        AlertDialog(
            onDismissRequest = { showReadAloudGuide = false },
            icon = { Icon(Icons.Filled.GraphicEq, contentDescription = null) },
            title = { Text("How read-aloud works", fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    Modifier
                        .heightIn(max = 440.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        "Three ways to have text spoken aloud:",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "1.  Highlight it.  Select text in a note, message, or text box — " +
                            "the bubble's microphone turns into a speaker. Tap it to listen. " +
                            "If you don't tap, it quietly turns back into the mic after about " +
                            "20 seconds.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "2.  Copy it.  Some apps — like web pages in Chrome and PDFs — don't " +
                            "let the bubble see what you highlight. There, copy the text " +
                            "instead (for a whole page: Select all, then Copy) and tap the " +
                            "small speaker chip on the bubble's lower-left corner. Whatever " +
                            "is on your clipboard is read aloud.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "3.  Use the Speak menu.  After selecting text in most apps, the " +
                            "popup toolbar has a \"Speak\" entry (sometimes behind ⋮). " +
                            "Tapping it reads the selection immediately.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "While it's speaking:",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "The bubble becomes a pill with a live waveform of the voice. " +
                            "Tap the pill to pause and resume. Tap the small square to stop. " +
                            "Starting a recording also stops speech instantly — the two never " +
                            "run at once. Long text is spoken sentence by sentence, so even a " +
                            "whole article streams smoothly.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        readAloudGuidePrivacyLine(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showReadAloudGuide = false }) { Text("Got it") }
            },
        )
    }

    // Delete-voice confirmation dialog (Track F)
    if (showDeleteVoiceDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteVoiceDialog = false },
            icon = { Icon(Icons.Filled.DeleteOutline, contentDescription = null) },
            title = { Text("Delete the read-aloud voice?", fontWeight = FontWeight.Bold) },
            text = { Text("Frees ~410 MB. Highlighted text can't be spoken until you download it again.") },
            confirmButton = {
                Button(
                    onClick = {
                        ttsManager.delete()
                        ttsRefreshKey++
                        showDeleteVoiceDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteVoiceDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

/** True when the device currently reports an internet-capable network. */
private fun isNetworkAvailable(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ?: return false
    val network = cm.activeNetwork ?: return false
    val capabilities = cm.getNetworkCapabilities(network) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
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
