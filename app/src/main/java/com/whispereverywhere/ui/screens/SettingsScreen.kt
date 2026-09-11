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
import com.whispereverywhere.data.local.PreferencesManager
import com.whispereverywhere.model.ModelMigration
import com.whispereverywhere.model.ModelScope
import com.whispereverywhere.model.WhisperCatalog
import com.whispereverywhere.provider.ProviderId
import com.whispereverywhere.service.WhisperAccessibilityService
import com.whispereverywhere.transcription.stream.PreviewAutoFetchController
import com.whispereverywhere.transcription.stream.PreviewDeleteCase
import com.whispereverywhere.transcription.stream.PreviewPhase
import com.whispereverywhere.transcription.stream.PreviewTrigger
import com.whispereverywhere.transcription.stream.PreviewWorkboard
import com.whispereverywhere.transcription.stream.StreamingPackCatalog
import com.whispereverywhere.transcription.stream.StreamingPackController
import com.whispereverywhere.transcription.stream.StreamingPackCopy
import com.whispereverywhere.tts.VoiceInstallRoute
import com.whispereverywhere.tts.cloud.CloudVoice
import com.whispereverywhere.tts.cloud.GeminiTtsVoices
import com.whispereverywhere.tts.cloud.OpenAiTtsVoices
import com.whispereverywhere.tts.cloud.SonioxTtsVoices
import com.whispereverywhere.ui.onboarding.AccessibilityAvailabilityProbe
import com.whispereverywhere.ui.onboarding.OnboardingLogic
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
    // (4.4.0, Task 2b) The APPLICATION's manager, not a per-composition one: its Play-refusal
    // latch has to outlive this screen, and TtsPackController reads the same instance.
    val ttsManager = app.ttsModelManager
    val ttsInstalled = remember(ttsRefreshKey) { ttsManager.isInstalled() }
    var ttsDownloadStatus by remember { mutableStateOf<String?>(null) }
    // The voice's Play fetch: state() answers from the delivered pack first, and the row's ONE
    // action routes on it (TtsModelManager.installRoute). The fetch's own progress/refusal line
    // is the shell's StateFlow, so a fetch that outlives this screen is still narrated on return.
    val voiceFetch by com.whispereverywhere.tts.TtsPackController.state.collectAsState()
    val voiceFetchLine = com.whispereverywhere.tts.TtsModelManager.fetchLine(voiceFetch)
    // (fix round 1, review nit 2) Keyed on the fetch's STATUS WORD, not on the state itself: a
    // Downloading tick arrives several times a second for the whole 350 MB, and state() does a
    // Play getPackLocation plus two File reads ON THE COMPOSITION THREAD — while its answer
    // cannot change until the status does. Same key for the effect below, which otherwise
    // re-launched per tick to test one type.
    val voiceStatusWord = com.whispereverywhere.npu.NpuPackFetch.statusWord(voiceFetch)
    val voiceRoute = remember(ttsRefreshKey, voiceStatusWord) {
        com.whispereverywhere.tts.TtsModelManager.installRoute(ttsManager.state())
    }
    // A landed pack install has to re-read isInstalled(): the row is keyed on ttsRefreshKey.
    LaunchedEffect(voiceStatusWord) {
        if (voiceFetch is com.whispereverywhere.npu.NpuPackFetch.FetchState.Installed) {
            ttsRefreshKey++
        }
    }
    // THE ROW'S ONE ACTION, spelled once and shared by the offer row and the in-flight row's
    // retry. It has to be shared: after a Play refusal the fallback latch has already moved
    // voiceRoute to Download, so a "Retry" that always re-asked Play would keep failing under a
    // sentence promising the direct download instead. The SOURCE decision is installRoute's
    // (pure, total over StreamingPackState); these are only the four actuators.
    val startVoiceInstall: () -> Unit = start@{
        // (fix round 1, B1) Refused while the fetch SHELL is working — read at TAP time, not at
        // composition time, so no row can be left permanently dead by a state change that
        // scheduled no recomposition. voiceRoute is still FromPack while the shell extracts the
        // delivered pack, so without this a tap on the offer row (reachable after cancel(),
        // whose Cancelled publishes at once while the extract runs on) would start a second
        // install of the same archive. The manager serializes them regardless; doing nothing is
        // the honest answer to a tap the row cannot serve.
        if (com.whispereverywhere.tts.TtsPackController.isBusy()) return@start
        when (voiceRoute) {
            VoiceInstallRoute.None -> Unit
            VoiceInstallRoute.Fetch -> {
                com.whispereverywhere.tts.TtsPackController.start(context)
            }
            VoiceInstallRoute.FromPack -> {
                ttsDownloadStatus = "Verifying and unpacking…"
                ttsScope.launch {
                    runCatching {
                        ttsManager.installFromPack(
                            onProgress = { _, _ -> },
                            onExtracting = { ttsDownloadStatus = "Verifying and unpacking…" },
                        )
                    }.onFailure {
                        android.widget.Toast.makeText(
                            context,
                            it.message ?: "Voice install failed",
                            android.widget.Toast.LENGTH_LONG,
                        ).show()
                    }
                    ttsDownloadStatus = null
                    ttsRefreshKey++
                }
            }
            VoiceInstallRoute.Download -> {
                ttsDownloadStatus = "Starting…"
                ttsScope.launch {
                    runCatching {
                        ttsManager.download(
                            onProgress = { soFar, total ->
                                ttsDownloadStatus =
                                    "${soFar / 1_000_000} / ${total / 1_000_000} MB"
                            },
                            onExtracting = { ttsDownloadStatus = "Verifying and unpacking…" },
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
            }
        }
    }
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
                    // 4.1 m2: no enumeration. `pickable` is the device-INDEPENDENT lineup, and
                    // the screen this row opens can show up to two more tiers on a gate-passing
                    // device — a constant list here was already wrong for one of them.
                    subtitle = "Pick a speech-model tier",
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
                            // (Task 6) The FromPack route verifies and extracts bytes Play
                            // already delivered, with no network at any point — "Downloading
                            // voice…" over it is a small lie on the one route Task 2b exists to
                            // add. The choice is the route table's now, not this screen's: an
                            // `if` in a Compose tree is a decision no JVM test can reach.
                            title = com.whispereverywhere.tts.TtsModelManager
                                .installingRowTitle(voiceRoute),
                            subtitle = ttsDownloadStatus ?: "",
                        )
                    }
                    // (4.4.0, Task 2b) A Play fetch in flight — or the refusal it ended in, in
                    // this feature's own words. Tapping retries THROUGH THE ROUTE, so a refusal
                    // that promised the direct download delivers it; a NeedsConfirmation tap
                    // re-shows PLAY'S own dialog, never a re-ask of ours.
                    voiceFetchLine != null -> {
                        // (fix round 1, B1) …and it is TAPPABLE only where a tap does something:
                        // the terminal Failed the retry is for, and the NeedsConfirmation that
                        // answers Play. This branch is entered for every in-flight state too, and
                        // SettingsItem wraps itself in Modifier.clickable whenever it is handed an
                        // onClick — so one tap during the ~30 s extract used to start a SECOND
                        // installFromPack into the same temp dir. The decision is pure and tested
                        // (TtsModelManager.fetchLineTappable); null here means not clickable.
                        val voiceTappable = com.whispereverywhere.tts.TtsModelManager
                            .fetchLineTappable(voiceFetch)
                        val voiceRowTap: () -> Unit = {
                            val activity = context as? android.app.Activity
                            if (voiceFetch is
                                    com.whispereverywhere.npu.NpuPackFetch.FetchState.NeedsConfirmation &&
                                activity != null
                            ) {
                                com.whispereverywhere.tts.TtsPackController.confirm(activity)
                            } else {
                                startVoiceInstall()
                            }
                        }
                        SettingsItem(
                            icon = Icons.Filled.CloudDownload,
                            title = "Read-aloud voice",
                            subtitle = voiceFetchLine,
                            onClick = if (voiceTappable) voiceRowTap else null,
                        )
                    }
                    else -> {
                        SettingsItem(
                            icon = Icons.Filled.CloudDownload,
                            title = com.whispereverywhere.tts.TtsModelManager
                                .installRowTitle(voiceRoute),
                            subtitle = com.whispereverywhere.tts.TtsModelManager
                                .installRowSubtitle(voiceRoute),
                            onClick = { startVoiceInstall() },
                        )
                    }
                }
            }

            // Live words (4.4.0): the on-device streaming previewer's pack. Its own section
            // rather than a fourth row under "Read aloud" — the two features share a delivery
            // mechanism and nothing else, and a switch for the bubble's text under a heading
            // about speaking text aloud is a heading that misleads.
            SettingsSection(title = "Live words") {
                LivePreviewRows(app = app, context = context)
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
                // 4.3.3 (accessibility-optional-spec §5): the row calls the service RECOMMENDED,
                // never required, and reads the device through the one adapter so a headset
                // whose policy forbids it gets the blocked sentence here too. Settings has no
                // Enable-then-resume signal of its own, so it can never read RESTRICTED.
                // Keyed like the flow's (OnboardingFlowScreen's `remember(accessibility, ...)`):
                // the probe is three device reads — SystemProperties by reflection,
                // hasSystemFeature and a getInstallSourceInfo binder call — and its answer moves
                // only when the service state does, so it must not run on every recomposition.
                val accessibilityAvailability = remember(hasAccessibility) {
                    AccessibilityAvailabilityProbe.classify(
                        context,
                        returnedFromSettings = false,
                        serviceEnabled = hasAccessibility,
                    )
                }

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
                    subtitle = if (hasAccessibility) "Enabled" else OnboardingLogic.accessibilitySettingsSubtitle(accessibilityAvailability),
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

/**
 * 4.4.0: the streaming previewer's pack (spec §6, §9 as amended on 2026-09-10) — the offer, the
 * Play fetch in flight, our own install, and then installed + switch + delete.
 *
 * It MIRRORS the read-aloud voice rows above it deliberately: the same four-branch `when` in the
 * same order (installed first, so a stale terminal fetch state can never hide a working model),
 * the same application-scoped manager (a per-composition one carries a private Play-refusal
 * latch the fetch shell could never see), the same tap guard, and the same division of labour —
 * every DECISION is pure and lives elsewhere, so this composable only wires them:
 *
 *  - which source this install has: `StreamingPackManager.state` → `StreamingPackInstall.resolve`
 *  - what is RUNNING, and on which route, and whether it can be stopped: `PreviewWorkboard` —
 *    the one observable (4.5.0 Task 1). This row reads nothing else about work in flight, and
 *    it no longer owns an actuator: the tap goes to `PreviewAutoFetchController.start`, the same
 *    object Home's card uses, whose guard spans all three starters (review r3's H3-B2)
 *  - every word, including the amendment's "included with the app" — on the DELIVERED route
 *    only, since an undelivered on-demand pack still costs the user 73 MB: `StreamingPackCopy`
 *  - whether a tap does anything at all: `StreamingPackCopy.workLineTappable`
 *  - which of the four facts the delete row is looking at, and therefore which of the four true
 *    sentences it renders: `PreviewDeleteCase.of` → `StreamingPackCopy.deleteSubtitle`
 *  - whether this user may be offered the pack at all: `StreamingPackCatalog.forLanguage` of
 *    their SELECTION (4.4.1 pass 3, ITEM 1) — no language is offered a model that has already
 *    been decided cannot arm for it, and the one it can never arm for is told so instead
 *
 * The one string the previewer has that this row does NOT render is
 * `StreamingPackCopy.SETTINGS_DISABLED_ON_DEVICE`: the canary's verdict lives on the previewer
 * instance the service builds (`StreamingPreviewEngine.isDisabled(pack)`, per-LANGUAGE since
 * 4.5.0 T2 defect 4) and nothing reads it — so a pack that failed its start-up check still reads
 * as installed and armable here. `LivePreviewRowsPinTest` pins the rest as source.
 */
@Composable
private fun LivePreviewRows(app: WhisperEverywhereApp, context: Context) {
    // NO `rememberCoroutineScope()` here as of 4.5.0 (Task 1): this row owned an install that ran
    // on a scope cancelled by leaving the screen, and a 73 MB transfer must outlive the Compose
    // tree that started it. `PreviewAutoFetchController`'s process scope is the one that does.
    val previewManager = app.streamingPackManager
    val previewPack = StreamingPackCatalog.EN
    // (4.4.1 acquisition amendment) The previewer's copy is parameterised by language, and this
    // section is still the ONE pack's — the per-language list is the multilingual build's. The
    // name comes from the picker's one table, so this row and Home's card cannot name the same
    // language differently; the fallback is the code, unreachable for a catalogue row.
    val previewLanguage = PreferencesManager.languageDisplayName(previewPack.language)
        ?: previewPack.language
    // ...and WHICH language the user picked, because every row below is about a pack and a pack
    // is per language (owner ruling 1, 2026-09-11: on Auto the feature is silent by design, which
    // has to be SAID here or it reads as broken).
    val selectedLanguage by app.preferencesManager.selectedLanguage.collectAsState()
    // (4.4.1 pass 3, ITEM 1) THE HONEST PREDICATE IS THE CATALOGUE'S, not `== "auto"`. A user who
    // picked French has no pack either, and until this line they were offered "Get the English
    // preview model", spent 73 MB, and were then told "Installed. Words appear on the bubble as
    // you speak English" — which owner ruling 1 had already decided can never happen for them on
    // ANY tier, because the gate arms for the language they PICKED. No language may be offered a
    // model that has already been decided cannot arm for it.
    val selectedPack = StreamingPackCatalog.forLanguage(selectedLanguage)
    // The picked language's own word, or null on Auto — the same mapping `getLanguageForApi()`
    // performs on this very preference (Auto is not a language), applied to the collected value so
    // the row stays reactive. The copy answers both cases from this one input; no rule lives here.
    val pickedLanguage = selectedLanguage.takeIf { it != "auto" }
        ?.let { PreferencesManager.languageDisplayName(it) ?: it }
    var previewRefreshKey by remember { mutableStateOf(0) }
    // (4.5.0 Task 1) THE ONE OBSERVABLE, and the ONLY thing this row reads about work in flight.
    // It replaces the TWO values three review rounds proved cannot answer "is work running?":
    // this composable's own `previewInstallStatus`, which Home could not see, and
    // `StreamingPackController.state`, which knew nothing about our two routes. Every blocker of
    // those rounds was a consequence of that gap — a second 73 MB started over work already
    // running (H3-B2), a delete row drawn over a live write (H3-B1), and a fetch begun on Home
    // that this row could not mention at all.
    val previewWorkboard by PreviewWorkboard.work.collectAsState()
    val previewWork = previewWorkboard[previewPack.language]
    // ONE answer to "does this row have a tap right now", computed here and consumed twice: by
    // the sentence (so AWAITING_ANSWER cannot say "tap to answer" where there is no tap — review
    // r3 H3-B3) and by the row's own onClick below. Hoisted rather than duplicated, because two
    // spellings of this condition is exactly how the sentence and the gesture came to disagree.
    val previewTappable = StreamingPackCopy.workLineTappable(previewWork) &&
        selectedPack == previewPack
    val previewWorkLine = previewWork?.let {
        StreamingPackCopy.workLine(it, tappable = previewTappable)
    }
    // Keyed on the PHASE, not the record: a DOWNLOADING tick arrives several times a second for
    // the whole 73 MB, and state() does a Play getPackLocation plus five File reads ON THE
    // COMPOSITION THREAD while its answer cannot change until the phase does (the voice row's
    // review nit 2, which this row would otherwise repeat).
    val previewPhase = previewWork?.phase
    val previewState = remember(previewRefreshKey, previewPhase) {
        previewManager.state(previewPack)
    }
    val previewEnabled by app.preferencesManager.localPreviewEnabledFlow.collectAsState()
    // Any TERMINAL phase has to re-read the state: the rows are keyed on previewRefreshKey, and
    // an install that landed, a download that failed halfway and a cancel all change what is on
    // disk. 4.4.1 re-read on Installed alone, so a failed install left the row describing the
    // state from before it.
    LaunchedEffect(previewPhase) {
        if (previewPhase != null && !previewPhase.inFlight) {
            previewRefreshKey++
        }
    }
    // THE ROW'S ONE ACTION, and as of 4.5.0 it is the FEATURE'S one actuator (Task 1, review r3's
    // H3-B2). This row used to hold a route `when` of its own, run it in `rememberCoroutineScope()`
    // and guard it on `StreamingPackController.isBusy()` — which could not see
    // `PreviewAutoFetchController`'s two routes at all, so the row offered and STARTED a second
    // 73 MB over work the controller was already doing. Its scope also died with the screen, so
    // leaving Settings mid-download cancelled the transfer while the DownloadManager row kept
    // going. One actuator answers both: the guard is `busy()` for all three starters, the route
    // reduction happens once inside it, and the work is on a process-scoped scope whoever began it.
    //
    // `PreviewTrigger.TAP` because a tap is a PICK, not an unasked top-up: it is exempt from the
    // once-per-launch latch (a tap is consent and may be repeated) and it is recorded on the board
    // as the user's own, which is what lets the copy state the deal honestly. It is also the one
    // cause that spends the user's connection whatever the connection reads — this row has never
    // been gated on metering, and Task 3a's silence is the UNASKED path's alone.
    val startPreviewInstall: () -> Unit = {
        PreviewAutoFetchController.start(app, previewPack, previewState, PreviewTrigger.TAP)
    }
    // WHAT A SELECTION WITH NO PACK COSTS, first in the section and above every offer (owner
    // ruling 1, 2026-09-11: *"if they leave it in auto, then you get no live streaming at all.
    // And that will seem to be a very fair trade-off."*). FIRST for the language step's own
    // reason — a caveat read after the offer is a caveat that changed nothing — and only where
    // there is no pack for the selection, because with a pack the rows below name its language.
    //
    // (4.4.1 pass 3, ITEM 1) The copy answers the two cases apart: Auto is a CHOICE, unmade in
    // the picker; a language the catalogue has no row for is a GAP in the app, and no pick closes
    // it today. Telling a French user to pick a language would be no help at all.
    if (selectedPack == null) {
        SettingsItem(
            icon = Icons.Filled.Subtitles,
            title = StreamingPackCopy.noLiveWordsTitle(pickedLanguage),
            subtitle = StreamingPackCopy.noLiveWordsSubtitle(pickedLanguage),
        )
    }
    // THE ROWS THAT DESCRIBE OR OFFER THIS PACK, and only for the user whose selection it serves.
    // `selectedPack == previewPack` rather than `!= null` so that the day a second catalogue row
    // lands this section is SILENT for it rather than describing the English model under another
    // language's name — the per-language LIST is the multilingual build's, parked by the brief.
    //
    // (fix round 1, H-B3) WORK IN FLIGHT IS NOT ONE OF THESE ROWS and has left this gate: it
    // describes neither the pack nor an offer but a transfer THE USER STARTED. It renders below,
    // ungated — see there for why.
    if (selectedPack == previewPack) {
        when {
            previewState.isInstalled -> {
                SettingsItem(
                    icon = Icons.Filled.Subtitles,
                    title = StreamingPackCopy.settingsTitle(previewState, previewLanguage),
                    subtitle = StreamingPackCopy.settingsSubtitle(previewState, previewLanguage),
                )
                SettingsSwitchItem(
                    icon = Icons.Filled.Subtitles,
                    title = StreamingPackCopy.SWITCH_TITLE,
                    checked = previewEnabled,
                    onCheckedChange = { app.preferencesManager.localPreviewEnabled = it },
                )
            }
            // The in-flight row renders BELOW instead, for every selection — and not here, so
            // the offer can never appear over work that is already running. ONE condition now
            // that there is one observable: 4.4.1 needed two, and Settings collected only one of
            // the two things that could be running.
            //
            // (fix round 2, review r2's B1a) AND A CANCEL PLAY HAS NOT ANSWERED IS ONE OF THE
            // THINGS THAT ARE RUNNING. Fix round 1 published a terminal CANCELLED here and held
            // the abandonment in a private field of the fetch shell, so this row saw no line,
            // fell into the `else ->` OFFER below, and drew a live 73 MB tap that
            // `PreviewAutoFetchController.start` then refused on `busy()` in complete silence.
            // `PreviewPhase.ABANDONED` is that fact on the board, so this arm withdraws the
            // offer for exactly as long as the actuator would refuse it — no second read, and no
            // guard of this row's own.
            previewWorkLine != null -> Unit
            else -> SettingsItem(
                icon = Icons.Filled.CloudDownload,
                title = StreamingPackCopy.settingsTitle(previewState, previewLanguage),
                subtitle = StreamingPackCopy.settingsSubtitle(previewState, previewLanguage),
                onClick = startPreviewInstall,
            )
        }
    }
    // WORK IN FLIGHT KEEPS ITS SURFACE WHEREVER THE SELECTION GOES (fix round 1, H-B3). This row
    // describes a transfer the user THEMSELVES started, so a selection that moves off this pack's
    // language mid-transfer must not hide it: the board is process-scoped, so the 73 MB keeps
    // going either way — and Home renders nothing at all for a selection with no pack
    // (`hasPackForSelection` false ⇒ Card.NONE). Gated on the selection, a running 73 MB was
    // therefore invisible everywhere in the app, with no system notification on either Play
    // route — it is the VISIBILITY, and only the visibility, that this row is out here for;
    // fix round 2 (H2-B1) withdrew the tap. That is the same hiding of the user's own action D17
    // refused on the card ("hiding its progress card would hide their own action from them and
    // leave the X as the only thing to press"). An un-tappable progress row naming the model they
    // asked for is a receipt, not a sale.
    //
    // (4.5.0 Task 1) ONE ROW WHERE THERE WERE TWO, because there is one observable. The pair
    // existed only because Play's fetch and our install narrated themselves through different
    // values; a user could not tell which of them was running, and Settings could not see a fetch
    // Home had begun at all.
    //
    // WHAT SURVIVES IS THE SENTENCE; NO TAP DOES (fix round 2, H2-B1). `workLineTappable` is true
    // for exactly two phases and BOTH of them spend the 73 MB rather than watch it:
    //   - `FAILED` — the terminal retry is a fresh 73 MB for a language the gate has already
    //     refused, which is exactly the offer the section above stopped making;
    //   - `AWAITING_ANSWER` — which fix round 1 read as "the transfer already in flight" and
    //     is not. It is Play's state BEFORE Play has moved a single byte, in BOTH sub-cases:
    //     `NpuPackFetch.kt:183-184` maps `STATUS_WAITING_FOR_WIFI` and
    //     `STATUS_REQUIRES_USER_CONFIRMATION` onto it, and this file's own copy says so twice
    //     (`SETTINGS_INSTALL_FETCH`: *"Play raises its own metered/size dialog BEFORE a transfer
    //     that size"*; `CARD_ANSWER_PLAY`: *"a cellular or size confirmation, or a wait for
    //     wifi"*). `PreviewPhase.AWAITING_ANSWER.inFlight` being true is a SINGLE-FLIGHT answer,
    //     not a bytes-have-moved one. So the tap that answers it is the tap that AUTHORISES the
    //     73 MB — over cellular in the wifi-wait case.
    // Off-selection that is 73 MB of data and 73 MB of storage for a recognizer
    // `localPreviewArms` refuses on its first conjunct: the very spend this pass exists to stop,
    // one state over from where fix round 1 drew the line. Nothing is lost by closing it — an
    // unanswered Play fetch parks harmlessly, the row keeps SAYING what is happening (which is
    // the receipt this whole block exists to provide), and picking the language back makes the
    // tap live again.
    if (!previewState.isInstalled && previewWorkLine != null) {
        // `previewTappable` is decided ONCE, up beside previewWorkLine, and says: a tap does
        // something only on the terminal retry and on the AWAITING_ANSWER that answers PLAY'S OWN
        // dialog, AND only while the selection is still the language this pack serves, because
        // both of those taps spend the 73 MB (see above). Every other phase is work in flight.
        // SettingsItem makes itself clickable the moment it is handed an onClick, so the flag
        // gates the onClick and the sentence together.
        val previewRowTap: () -> Unit = {
            val activity = context as? android.app.Activity
            if (previewPhase == PreviewPhase.AWAITING_ANSWER && activity != null) {
                StreamingPackController.confirm(activity)
            } else {
                startPreviewInstall()
            }
        }
        SettingsItem(
            icon = Icons.Filled.CloudDownload,
            title = StreamingPackCopy.featureTitle(previewLanguage),
            subtitle = previewWorkLine,
            onClick = if (previewTappable) previewRowTap else null,
        )
    }
    // THE DELETE FOLLOWS THE BYTES, not the selection (4.4.1 pass 3, ITEM 1) — so it lives
    // OUTSIDE the gate above. 73 MB installed for English must stay reclaimable after the user
    // picks French: a delete row that only appeared for the pack's own language would leave them
    // with no way to get the storage back but to re-pick a language they do not want. It is also
    // the one row a selection with no pack still needs.
    //
    // (fix round 1, H-B2) AND `Repair` IS BYTES. `isInstalled` is `this is Installed` only
    // (`StreamingPackInstall.kt:29`), and `Repair` is exactly the state where up to 73 MB is
    // sitting under `filesDir` with the verdict withdrawn: `markCorrupt` deletes only the marker
    // ("the bytes stay", its own KDoc) and the previewer's `onLoadFailure` calls it on any device
    // where the sherpa load throws. With the repair row now inside the selection gate above, a
    // user whose load failed once and who then picks French or Auto had NO row anywhere in the
    // app that reclaims those bytes. `delete` clears the install dir either way.
    //
    // (fix round 2, H2-B2; 4.5.0 Task 1, review r3's H3-B1) AND THE BYTES HAVE TO BE SETTLED.
    // `previewState` is `remember(previewRefreshKey, previewPhase)` and OUR OWN install changes
    // neither key while it runs, so through a repair install the state stays `Repair` — which,
    // with the line above, rendered this row BESIDE our running copy. That combination cannot
    // keep the row's promise: the `onClick` has no busy guard, `delete` clears the install dir
    // under the copy, and `installFromPack` is NOT cancellation-cooperative (ITEM 4's finding —
    // no suspension point between `withContext(Dispatchers.IO)`'s entry and its return), so the
    // copy finishes, `install` re-creates the directory and the marker lands. The user would
    // press *"Frees 73 MB. Live words stop"* and get *"Installed (73 MB)"* — with the declined
    // flag written, so Home never mentions it again. (On the `Downloadable` sibling `delete`'s
    // `removeStaleDownloads` kills the live DownloadManager row instead, failing the install the
    // user actually wanted.)
    //
    // 4.4.1 withdrew the row on `previewInstallStatus == null` — this composable's own `var`,
    // which knew nothing about the two routes `PreviewAutoFetchController` runs or about a fetch
    // Home had started, so the race it closed was one third of the race. THE CASE IS NOW DERIVED
    // FROM THE ONE OBSERVABLE, which sees all three starters, and the row renders in all five
    // cases with a sentence that is true in each — including the write, where the tap is withdrawn
    // rather than the row. An un-tappable row saying what is happening is this feature's own
    // answer everywhere else (the progress row above is exactly that); a row that vanishes for
    // the seconds a copy runs leaves the reader wondering where their reclaim went.
    //
    // (fix round 1, review r1's B2) AND THE SWITCH IS ONE OF THE FACTS. With "Show live words"
    // switched OFF — the switch this same section draws one row above, inside the selection gate
    // — the case used to be LIVE and the row read "Frees 73 MB. Live words stop", one tap under
    // a switch that had already stopped them. It was the one sentence in the feature that did not
    // ask: `localPreviewArms` conjoins `userEnabled`, `PreviewAutoFetch.decide` returns NONE on
    // it and `PreviewAutoFetch.card` returns Card.NONE on it because "every sentence this card
    // can spell is false while the switch is off". `previewEnabled` is the same value the switch
    // above is drawn from, so the two rows cannot disagree.
    PreviewDeleteCase.of(
        state = previewState,
        selectedForThisPack = selectedPack == previewPack,
        showLiveWords = previewEnabled,
        work = previewWork,
    )?.let { deleteCase ->
        SettingsItem(
            icon = Icons.Filled.Delete,
            title = StreamingPackCopy.DELETE_TITLE,
            subtitle = StreamingPackCopy.deleteSubtitle(
                deleteCase,
                previewLanguage,
                previewPack.totalBytes,
            ),
            // THE WRITE IS THE ONE CASE WITH NO TAP. Everything else this row can be looking at
            // is at rest by construction, and `delete` on a settled install cannot race anything.
            onClick = if (deleteCase == PreviewDeleteCase.WORKING) {
                null
            } else {
                {
                    // (4.4.1) A DELETE IS A DECISION, and it is recorded BEFORE the bytes go: the
                    // 4.4.1 auto-fetch would otherwise put this model back on the next app open,
                    // which is the one thing the owner's discovery ruling must not do. Written
                    // first so a removal that failed partway still leaves the decision recorded.
                    // (4.4.1 acquisition amendment) And it is recorded FOR THIS PACK'S LANGUAGE,
                    // not globally: deleting one language's model says nothing about another's,
                    // and the store is a set (owner ruling 2026-09-11, consequence 5).
                    app.preferencesManager.setLivePreviewDeclined(previewPack.language, true)
                    previewManager.delete(previewPack)
                    previewRefreshKey++
                }
            },
        )
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
