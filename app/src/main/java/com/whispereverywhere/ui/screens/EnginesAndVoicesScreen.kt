package com.whispereverywhere.ui.screens

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.whispereverywhere.WhisperEverywhereApp
import com.whispereverywhere.net.OkHttpTransport
import com.whispereverywhere.provider.KeyValidator
import com.whispereverywhere.provider.ProviderAccounts
import com.whispereverywhere.provider.ProviderCatalog
import com.whispereverywhere.provider.ProviderId
import com.whispereverywhere.tts.cloud.CloudVoice
import com.whispereverywhere.tts.cloud.ElevenLabsTts
import com.whispereverywhere.tts.cloud.GeminiTtsVoices
import com.whispereverywhere.tts.cloud.OpenAiTtsVoices
import com.whispereverywhere.tts.resolveTtsProvider
import com.whispereverywhere.ui.theme.Primary
import com.whispereverywhere.ui.theme.Warning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------------------------------------
// Engines & voices hub. A container that re-parents three existing surfaces — the transcription
// engine selector, the read-aloud voice picker, and the API-keys cards — under one screen with
// three sections. This is FURNITURE only: every gate keeps its EXACT pure function and its EXACT
// behavior. The transcription selector + disclosure dialog are lifted verbatim from
// CloudProvidersScreen; the voice picker verbatim from SettingsScreen; the ProviderCard cards
// (the one gate-bearing composable) stay put in CloudProvidersScreen.kt and are called here.
// All pure logic (sttSelectableProviders, ttsSelectableProviders, sttSelectionCaption,
// liveModeRowVisible, liveModeLabel/Caption, selectionAfterKeyRemoval, cloudDisclosure*, the
// ttsProviderPriceNote/ttsNoSpeedControlNote/ttsPreviewCaption/cloudVoiceDisplayName copy) stays
// in its original file, same package, same signatures — so every pinned test passes unchanged.
// ---------------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnginesAndVoicesScreen(
    onNavigateBack: () -> Unit,
    onNavigateToPrivacyPolicy: () -> Unit = {},
) {
    val app = WhisperEverywhereApp.getInstance()
    val accounts = app.preferencesManager.providerAccounts
    val validator = remember { KeyValidator(OkHttpTransport()) }
    val context = LocalContext.current

    // This screen can render a credential (behind the eye-icon reveal in the API-keys section),
    // and a screenshot here would land in the gallery and, for most users, auto-sync to Google
    // Photos — exactly the exposure backup_rules.xml exists to prevent; the recents thumbnail
    // captures it passively too. FLAG_SECURE covers the screen's ENTIRE lifetime — it is
    // deliberately not gated on the reveal toggle, which would race the recents snapshot taken
    // when the app backgrounds.
    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    // Local mirror of the persisted flag: it only ever flips false->true here, via the accept
    // button. Reading the persisted value fresh on every entry means "Not now" (which never
    // writes it) always re-shows the disclosure the next time this screen opens.
    var disclosureAccepted by remember { mutableStateOf(app.preferencesManager.cloudDisclosureAccepted) }

    // Whether the cloud disclosure is on screen right now. It auto-shows on first entry when not yet
    // accepted, but declining it ("Not now") only DISMISSES it — it no longer ejects the whole hub.
    // Section 2's on-device Kokoro voice picker is a zero-egress feature and must stay reachable
    // without cloud consent, exactly as it was in Settings before the merge. The cloud sections stay
    // fully locked (sttSelectableProviders / ttsSelectableProviders filter out cloud rows, and
    // ProviderCard.editable stays false) until the user re-opens this via the "Enable cloud" affordance
    // and accepts — the v3 gate's semantics are unchanged; only its scope shrinks back off Section 2.
    var showDisclosure by remember { mutableStateOf(!app.preferencesManager.cloudDisclosureAccepted) }

    // Bumped after every save/remove so provider rows re-read their stored key from SecureStore.
    var refreshKey by remember { mutableStateOf(0) }

    // Local mirror of the persisted engine selection. null = on-device (the default).
    var sttProviderId by remember { mutableStateOf(app.preferencesManager.sttProviderId) }

    // Local mirror of the batch-vs-live axis (C4). false = one-shot batch POST (the default);
    // consulted only while OpenAI is the selected engine — see decideEngineChoice.
    var sttLiveMode by remember { mutableStateOf(app.preferencesManager.sttLiveMode) }

    // Read-aloud voice-picker state (lifted verbatim from SettingsScreen). null engine = on-device
    // Kokoro (the default and the one-way fallback); a ProviderId NAME routes read-aloud through a
    // cloud voice. The cloud-voice state mirrors the selected engine's stored voice so the picker's
    // radio and the Voice row subtitle stay in sync as the user switches engine tabs.
    var ttsVoiceIdState by remember { mutableStateOf(app.preferencesManager.ttsVoiceId) }
    var ttsProviderIdState by remember { mutableStateOf(app.preferencesManager.ttsProviderId) }
    var ttsCloudVoiceIdState by remember {
        mutableStateOf(
            resolveTtsProvider(app.preferencesManager.ttsProviderId)
                ?.let { app.preferencesManager.ttsCloudVoiceId(it) },
        )
    }
    // ElevenLabs' catalog is dynamic (GET /v1/voices), so it is fetched lazily the first time its
    // tab is viewed and held for this screen's lifetime. An empty fetch (network/key failure) shows
    // an honest error row, not a silent blank list.
    val ttsTransport = remember { OkHttpTransport() }
    var elVoices by remember { mutableStateOf<List<CloudVoice>?>(null) }
    var elVoicesLoading by remember { mutableStateOf(false) }
    var elVoicesError by remember { mutableStateOf(false) }
    var showVoicePickerDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Engines & voices") },
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
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = "Use your own account with a cloud provider for transcription and " +
                    "read-aloud. This stays off until you add a key below, and you can " +
                    "remove one at any time.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp)
            )

            // Re-entry point for the cloud disclosure once it has been declined this session: the
            // cloud sections stay locked until it is accepted, so a user who dismissed it needs a way
            // back to it without leaving and re-entering. Hidden once accepted.
            if (!disclosureAccepted) {
                TextButton(
                    onClick = { showDisclosure = true },
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) {
                    Text("Enable cloud engines & voices")
                }
            }

            // Section 1 — Transcription engine (moved verbatim from CloudProvidersScreen).
            HubSectionHeader("Transcription engine")
            SttEngineSelector(
                accounts = accounts,
                refreshKey = refreshKey,
                selectedProviderId = sttProviderId,
                disclosureAccepted = disclosureAccepted,
                onSelect = { providerId ->
                    app.preferencesManager.sttProviderId = providerId
                    sttProviderId = providerId
                },
                liveMode = sttLiveMode,
                onLiveModeChange = { enabled ->
                    app.preferencesManager.sttLiveMode = enabled
                    sttLiveMode = enabled
                },
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Section 2 — Read-aloud voice. The Voice row opens the picker dialog moved verbatim
            // from SettingsScreen (engine radios + Kokoro list + per-provider cloud voice lists +
            // Preview). Previews still spend the key via TtsController.speakFromTrigger; Select is
            // still free; prices / no-speed notes still appear exactly here.
            HubSectionHeader("Read-aloud voice")
            SettingsItem(
                icon = Icons.Filled.RecordVoiceOver,
                title = "Voice",
                subtitle = run {
                    val cloudId = resolveTtsProvider(ttsProviderIdState)
                    if (cloudId == null) {
                        com.whispereverywhere.tts.TtsVoices
                            .byId(ttsVoiceIdState).displayName + " — tap to change"
                    } else {
                        val name = cloudVoiceDisplayName(cloudId, ttsCloudVoiceIdState, elVoices)
                        "${ProviderCatalog.byId(cloudId).displayName}: " +
                            "${name ?: "choose a voice"} — tap to change"
                    }
                },
                onClick = { showVoicePickerDialog = true },
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Section 3 — API keys (moved verbatim from CloudProvidersScreen; ProviderCard itself
            // stays in CloudProvidersScreen.kt as the home of the gate-bearing key composables).
            HubSectionHeader("API keys")
            ProviderCatalog.all.forEach { provider ->
                // Off Main: SecureStore.get() runs a Keystore cipher, and a raw key must never
                // sit in Compose state anyway — only the masked stand-in crosses into the UI.
                val storedKeyDisplay by produceState<String?>(null, refreshKey, provider.id) {
                    value = withContext(Dispatchers.IO) { accounts.maskedKey(provider.id) }
                }
                ProviderCard(
                    provider = provider,
                    accounts = accounts,
                    storedKeyDisplay = storedKeyDisplay,
                    validator = validator,
                    editable = disclosureAccepted,
                    onChanged = { refreshKey++ },
                    onKeyRemoved = {
                        // A selection must not outlive its own credential — see
                        // selectionAfterKeyRemoval for why re-adding a key must not be enough on
                        // its own to resume cloud transcription.
                        val next = selectionAfterKeyRemoval(sttProviderId, provider.id)
                        if (next != sttProviderId) {
                            app.preferencesManager.sttProviderId = next
                            sttProviderId = next
                        }
                        // The SAME rule for read-aloud (a distinct data class: selected TEXT, not
                        // audio). Without this, removing a provider's key cleared only the STT
                        // selection while ttsProviderId / its stored voice survived — so re-adding
                        // the key silently resumed text egress in ONE action, defeating the
                        // two-deliberate-actions gate the app enforces for STT and advertises in the
                        // privacy policy. Clear the read-aloud engine if it was this provider, and
                        // always drop this provider's stored voice (setTtsCloudVoiceId's own doc:
                        // "Clearing keeps the store tidy on key removal").
                        val ttsNext = selectionAfterKeyRemoval(
                            app.preferencesManager.ttsProviderId, provider.id,
                        )
                        if (ttsNext != app.preferencesManager.ttsProviderId) {
                            app.preferencesManager.ttsProviderId = ttsNext
                        }
                        app.preferencesManager.setTtsCloudVoiceId(provider.id, null)
                        // Re-sync the read-aloud LOCAL mirrors from prefs. Since the merge, Section 3
                        // (removal) and the Section 2 voice picker share this remember-state on ONE
                        // screen — unlike the STT branch above, the TTS branch never updated its
                        // mirrors, so they went stale. A stale mirror let the picker keep rendering the
                        // just-deselected cloud engine's voice list with live Select/Preview, which
                        // re-persist ttsProviderId and resume cloud read-aloud in a SINGLE action —
                        // defeating the two-deliberate-actions key-removal gate. Recompute exactly as
                        // the initial `remember` does.
                        ttsProviderIdState = app.preferencesManager.ttsProviderId
                        ttsCloudVoiceIdState = resolveTtsProvider(app.preferencesManager.ttsProviderId)
                            ?.let { app.preferencesManager.ttsCloudVoiceId(it) }
                    },
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    // Play requires this BEFORE any key field becomes editable — see ProviderCard's `editable`
    // param above, which stays false until this dialog is accepted.
    if (showDisclosure && !disclosureAccepted) {
        CloudDisclosureDialog(
            onAccept = {
                app.preferencesManager.cloudDisclosureAccepted = true
                disclosureAccepted = true
                showDisclosure = false
            },
            // "Not now" DISMISSES the disclosure and leaves the user on the hub — it no longer ejects
            // them, so on-device Kokoro (Section 2) stays usable. It still never writes acceptance:
            // cloud remains locked until "I understand". Re-openable via "Enable cloud" above.
            onNotNow = { showDisclosure = false },
            onOpenPrivacyPolicy = onNavigateToPrivacyPolicy,
        )
    }

    // Voice picker (Track F + Task 6): pick the read-aloud ENGINE (on-device Kokoro — the default
    // and fallback — or a configured cloud provider gated on disclosure v3), then a voice within it.
    // On-device voices preview for free on tap. A cloud voice is SELECTED on tap (free) and previewed
    // only via the explicit Preview button, because a cloud preview spends the user's own key.
    if (showVoicePickerDialog) {
        // Off Main: configured() runs a Keystore lookup per provider. (Suppression: the producer DOES
        // assign value — the compose-runtime checker just can't see an assignment after a suspend call.)
        @Suppress("ProduceStateDoesNotAssignValue")
        val configuredForTts by produceState<Set<ProviderId>>(emptySet()) {
            value = withContext(Dispatchers.IO) { app.preferencesManager.providerAccounts.configured() }
        }
        val selectableTts = ttsSelectableProviders(
            configured = configuredForTts,
            disclosureAccepted = app.preferencesManager.cloudDisclosureAccepted,
        )
        // Which engine's voice list is on screen. null = on-device Kokoro.
        val viewedProviderId = resolveTtsProvider(ttsProviderIdState)

        // ElevenLabs' catalog is dynamic — fetch it the first time its tab is viewed, once.
        if (viewedProviderId == ProviderId.ELEVENLABS) {
            LaunchedEffect(Unit) {
                if (elVoices == null && !elVoicesLoading) {
                    elVoicesLoading = true
                    elVoicesError = false
                    val fetched = withContext(Dispatchers.IO) {
                        val key = app.preferencesManager.providerAccounts.key(ProviderId.ELEVENLABS)
                        if (key.isNullOrBlank()) emptyList()
                        else runCatching {
                            ElevenLabsTts(ttsTransport, key, context.applicationContext).voices()
                        }.getOrDefault(emptyList())
                    }
                    elVoicesLoading = false
                    // ElevenLabs always returns default voices on success, so an empty list means the
                    // fetch itself failed (bad key / offline) — show an honest error, not a blank list.
                    if (fetched.isEmpty()) elVoicesError = true else elVoices = fetched
                }
            }
        }

        // Select a cloud voice (free) — persist so a subsequent read (or Preview) resolves it.
        fun selectCloudVoice(providerId: ProviderId, voiceId: String) {
            app.preferencesManager.setTtsCloudVoiceId(providerId, voiceId)
            ttsCloudVoiceIdState = voiceId
        }
        // Preview a cloud voice (costs the user's key) — ensure engine + voice are persisted, then
        // synthesize the fixed sample through the REAL provider path (falls back to Kokoro on failure).
        fun previewCloudVoice(providerId: ProviderId, voiceId: String) {
            app.preferencesManager.ttsProviderId = providerId.name
            ttsProviderIdState = providerId.name
            selectCloudVoice(providerId, voiceId)
            com.whispereverywhere.tts.TtsController.speakFromTrigger(context, TTS_PREVIEW_SAMPLE)
        }

        AlertDialog(
            onDismissRequest = { showVoicePickerDialog = false },
            icon = { Icon(Icons.Filled.RecordVoiceOver, contentDescription = null) },
            title = { Text("Read-aloud voice", fontWeight = FontWeight.Bold) },
            text = {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 460.dp)
                ) {
                    // 1. Engine selector — on-device (always) plus any cloud provider offered by
                    //    ttsSelectableProviders (v3 accepted AND a stored key).
                    item {
                        Text(
                            "Voice engine",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(4.dp))
                        TtsEngineRow(
                            label = "On-device (Kokoro)",
                            selected = ttsProviderIdState == null,
                            onClick = {
                                app.preferencesManager.ttsProviderId = null
                                ttsProviderIdState = null
                            },
                        )
                        selectableTts.forEach { provider ->
                            TtsEngineRow(
                                label = provider.displayName,
                                selected = ttsProviderIdState == provider.id.name,
                                onClick = {
                                    app.preferencesManager.ttsProviderId = provider.id.name
                                    ttsProviderIdState = provider.id.name
                                    ttsCloudVoiceIdState =
                                        app.preferencesManager.ttsCloudVoiceId(provider.id)
                                },
                            )
                        }
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    }

                    if (viewedProviderId == null) {
                        // 2a. On-device Kokoro voices — tap selects AND previews (free, on-device).
                        items(com.whispereverywhere.tts.TtsVoices.ALL.size) { i ->
                            val voice = com.whispereverywhere.tts.TtsVoices.ALL[i]
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        ttsVoiceIdState = voice.id
                                        app.preferencesManager.ttsVoiceId = voice.id
                                        com.whispereverywhere.tts.TtsController.speakFromTrigger(
                                            context, "Hi, I'm ${voice.displayName.substringBefore(" —")}. " +
                                                "This is how I read your text."
                                        )
                                    }
                                    .padding(vertical = 10.dp, horizontal = 4.dp),
                            ) {
                                RadioButton(
                                    selected = voice.id == ttsVoiceIdState,
                                    onClick = null,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(voice.displayName, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    } else {
                        // 2b. Cloud provider — honest price note, the no-speed-control note where it
                        //     applies, and the money caption. Then the provider's voice catalog.
                        item {
                            ttsNoSpeedControlNote(viewedProviderId)?.let { note ->
                                Text(
                                    note,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(4.dp))
                            }
                            Text(
                                ttsProviderPriceNote(viewedProviderId),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                ttsPreviewCaption(),
                                style = MaterialTheme.typography.bodySmall,
                                color = Warning,
                                fontWeight = FontWeight.Medium,
                            )
                            Spacer(Modifier.height(8.dp))
                        }

                        val staticVoices: List<CloudVoice>? = when (viewedProviderId) {
                            ProviderId.OPENAI -> OpenAiTtsVoices.ALL
                            ProviderId.GEMINI -> GeminiTtsVoices.ALL
                            ProviderId.ELEVENLABS -> null // dynamic — handled below
                            ProviderId.SONIOX -> null // STT-only; never enters the TTS voice list
                        }

                        if (staticVoices != null) {
                            items(staticVoices.size) { i ->
                                val voice = staticVoices[i]
                                TtsCloudVoiceRow(
                                    displayName = voice.displayName,
                                    recommended = voice.recommended,
                                    selected = voice.voiceId == ttsCloudVoiceIdState,
                                    onSelect = { selectCloudVoice(viewedProviderId, voice.voiceId) },
                                    onPreview = { previewCloudVoice(viewedProviderId, voice.voiceId) },
                                )
                            }
                        } else {
                            // ElevenLabs dynamic catalog: loading spinner, honest error row, or the list.
                            when {
                                elVoicesLoading -> item {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(vertical = 10.dp, horizontal = 4.dp),
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            strokeWidth = 2.dp,
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            "Loading ElevenLabs voices…",
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                    }
                                }
                                elVoicesError -> item {
                                    Text(
                                        "Couldn't load ElevenLabs voices. Check your connection and " +
                                            "that the saved key has voice access.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
                                    )
                                    TextButton(onClick = { elVoices = null; elVoicesError = false }) {
                                        Text("Try again")
                                    }
                                }
                                else -> {
                                    val list = elVoices ?: emptyList()
                                    items(list.size) { i ->
                                        val voice = list[i]
                                        TtsCloudVoiceRow(
                                            displayName = voice.displayName,
                                            recommended = voice.recommended,
                                            selected = voice.voiceId == ttsCloudVoiceIdState,
                                            onSelect = { selectCloudVoice(viewedProviderId, voice.voiceId) },
                                            onPreview = { previewCloudVoice(viewedProviderId, voice.voiceId) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    com.whispereverywhere.tts.TtsController.stop()
                    showVoicePickerDialog = false
                }) { Text("Done") }
            },
        )
    }
}

/** A section title in the hub, matching the app's SettingsSection header styling. */
@Composable
private fun HubSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = Primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
private fun CloudDisclosureDialog(
    onAccept: () -> Unit,
    onNotNow: () -> Unit,
    onOpenPrivacyPolicy: () -> Unit,
) {
    AlertDialog(
        // Dismissal must never equal consent — both back-press and tap-outside are disabled
        // below, and this is intentionally a no-op rather than a silent accept.
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
        icon = { Icon(Icons.Filled.CloudUpload, contentDescription = null) },
        title = {
            Text(
                "Adding a cloud key sends data to that provider.",
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    cloudDisclosureMainText(),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "You are using your own account, so they bill you directly and their " +
                        "terms apply to your data.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    cloudDisclosureOffUntilText(),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    "What each provider does with your data:",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(4.dp))
                ProviderCatalog.all.forEach { provider ->
                    Text(
                        "• ${provider.displayName}: ${providerTrainingDisclosure(provider)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Read the full privacy policy",
                    style = MaterialTheme.typography.bodySmall,
                    color = Primary,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable(onClick = onOpenPrivacyPolicy),
                )
            }
        },
        confirmButton = {
            Button(onClick = onAccept) { Text("I understand") }
        },
        dismissButton = {
            TextButton(onClick = onNotNow) { Text("Not now") }
        },
    )
}

/**
 * "Transcribe with" selector: on-device (always offered, the default) plus one row per provider
 * that both has a stored key AND is [STT_CAPABLE_PROVIDERS] — i.e. only providers
 * [com.whispereverywhere.service.FloatingBubbleService] can genuinely route audio to. Selecting a
 * provider writes [com.whispereverywhere.data.local.PreferencesManager.sttProviderId]; selecting
 * on-device clears it.
 */
@Composable
private fun SttEngineSelector(
    accounts: ProviderAccounts,
    refreshKey: Int,
    selectedProviderId: String?,
    disclosureAccepted: Boolean,
    onSelect: (String?) -> Unit,
    liveMode: Boolean,
    onLiveModeChange: (Boolean) -> Unit,
) {
    // Off Main: configured() runs a Keystore lookup per provider, same as the masked-key reads
    // below — must not run on the Compose/Main thread.
    val configured by produceState<Set<ProviderId>>(emptySet(), refreshKey) {
        value = withContext(Dispatchers.IO) { accounts.configured() }
    }
    val selectable = sttSelectableProviders(configured, disclosureAccepted)

    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Text(
            text = "Transcribe with",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(4.dp))

        SttEngineRow(
            label = "On-device (free, private)",
            selected = selectedProviderId == null,
            onClick = { onSelect(null) },
        )
        selectable.forEach { provider ->
            SttEngineRow(
                label = provider.displayName,
                selected = selectedProviderId == provider.id.name,
                onClick = { onSelect(provider.id.name) },
            )
        }

        val selectedProvider = selectable.firstOrNull { it.id.name == selectedProviderId }
        if (selectedProvider != null) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = sttSelectionCaption(selectedProvider.displayName),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 40.dp),
            )
        }

        // C4: the word-for-word live toggle, offered only when OpenAI is the selected engine (the
        // one provider that streams). Same v3 disclosure gate as selection — no new consent surface.
        if (liveModeRowVisible(selectedProviderId, configured, disclosureAccepted)) {
            Spacer(modifier = Modifier.height(8.dp))
            LiveModeRow(enabled = liveMode, onToggle = onLiveModeChange)
        }
    }
}

/**
 * The live-mode toggle row. Names the mode and its PRICE (the one new cost surface this mode adds)
 * with a "word-for-word" sub-line and NO speed claim — see [liveModeLabel] / [liveModeCaption].
 */
@Composable
private fun LiveModeRow(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle(!enabled) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = liveModeLabel(), style = MaterialTheme.typography.bodyMedium)
            Text(
                text = liveModeCaption(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Switch(checked = enabled, onCheckedChange = onToggle)
    }
}

@Composable
private fun SttEngineRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(modifier = Modifier.width(4.dp))
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
    }
}

/** One engine radio row in the read-aloud voice picker (on-device or a cloud provider). */
@Composable
private fun TtsEngineRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(4.dp))
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * One cloud voice row: a radio to SELECT (free), an optional provider "Recommended" chip, and a
 * Preview button. Preview is a deliberate, separate action because it spends the user's own key.
 */
@Composable
private fun TtsCloudVoiceRow(
    displayName: String,
    recommended: Boolean,
    selected: Boolean,
    onSelect: () -> Unit,
    onPreview: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 6.dp, horizontal = 4.dp),
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Spacer(Modifier.width(8.dp))
        Text(
            text = displayName,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        if (recommended) {
            RecommendedChip()
            Spacer(Modifier.width(4.dp))
        }
        TextButton(onClick = onPreview) {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(2.dp))
            Text("Preview")
        }
    }
}

/** Provider-suggested-voice chip (e.g. OpenAI's marin/cedar). Never a speed or quality claim. */
@Composable
private fun RecommendedChip() {
    Surface(
        color = Primary.copy(alpha = 0.12f),
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            text = "Recommended",
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = Primary,
            fontWeight = FontWeight.Bold,
        )
    }
}
