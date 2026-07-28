package com.whispereverywhere.ui.screens

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.view.WindowManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.whispereverywhere.WhisperEverywhereApp
import com.whispereverywhere.data.local.SecureStoreException
import com.whispereverywhere.net.OkHttpTransport
import com.whispereverywhere.provider.INVALID_KEY_MARKERS
import com.whispereverywhere.provider.KeyStatus
import com.whispereverywhere.provider.KeyValidator
import com.whispereverywhere.provider.Provider
import com.whispereverywhere.provider.ProviderAccounts
import com.whispereverywhere.provider.ProviderCatalog
import com.whispereverywhere.provider.ProviderId
import com.whispereverywhere.ui.theme.Primary
import com.whispereverywhere.ui.theme.Success
import com.whispereverywhere.ui.theme.Warning
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------------------------------------
// Pure logic — deliberately Compose-free so it is JVM-unit-testable without Robolectric.
// ---------------------------------------------------------------------------------------------

/**
 * A display stand-in for a stored key. NEVER the raw value: this is derived from only the last
 * 4 characters, so even the masked form can't be used to reconstruct the credential.
 */
internal fun maskedKeyPlaceholder(key: String): String =
    if (key.length <= 4) "•".repeat(key.length) else "••••${key.takeLast(4)}"

/**
 * The user-facing line for a [KeyStatus]. [KeyStatus.Offline] deliberately never says "rejected"
 * — telling a user their key is wrong when the real cause was a dead radio sends them off to
 * regenerate a perfectly good key.
 */
internal fun statusMessage(
    status: KeyStatus,
    providerDisplayName: String,
    providerId: ProviderId? = null,
): String = when (status) {
    KeyStatus.Valid -> "Key verified ✓"
    // ElevenLabs is the ONLY one of the three with per-endpoint API-key restrictions, so a
    // rejection there is at least as likely to be a scoping problem as a bad paste. Blaming the
    // user's typing sends someone with a perfectly good — and correctly locked-down — key off to
    // regenerate it. Observed in the field 2026-07-28.
    KeyStatus.Invalid -> if (providerId == ProviderId.ELEVENLABS) {
        "That key was rejected. Check you copied all of it — or, if the key is restricted, " +
            "give it voice read access in your ElevenLabs dashboard."
    } else {
        "That key was rejected. Check you copied all of it."
    }
    KeyStatus.NoCredit -> "The key works, but the account has no credit."
    KeyStatus.RateLimited -> "Rate limited — try again in a moment."
    KeyStatus.Offline -> "Couldn't reach $providerDisplayName. Check your connection."
    is KeyStatus.Unknown -> "Couldn't verify (${status.detail}). Save anyway?"
}

/**
 * True for exactly the outcomes that mean the key is genuinely usable and should be persisted.
 *
 * [KeyStatus.NoCredit] STILL saves — the key itself is valid, the account is just empty.
 * [KeyStatus.Offline] never saves and is never silently discarded either; the caller keeps
 * whatever the user typed so they don't have to retype it once back online.
 */
internal fun shouldPersistKey(status: KeyStatus): Boolean =
    status is KeyStatus.Valid || status is KeyStatus.NoCredit

/**
 * True when a [KeyStatus.Unknown] detail embeds a provider's own "the key itself is wrong"
 * marker, even under a status code (Gemini's 400 API_KEY_INVALID) that isn't the clean 401. When
 * true, the screen must not offer "Save anyway" — that would persist a key already known to be
 * bad rather than merely unverifiable.
 */
internal fun looksLikeInvalidKey(detail: String): Boolean =
    INVALID_KEY_MARKERS.any { detail.contains(it, ignoreCase = true) }

/**
 * Per-provider training-on-data disclosure line. One generic sentence would be materially
 * inaccurate: OpenAI does not train on API data, Gemini's free tier does with human review,
 * ElevenLabs trains by default with an account-level opt-out. Exhaustive over [ProviderId] on
 * purpose — a fourth provider must get its own reviewed line, not silently inherit one of these.
 */
internal fun providerTrainingDisclosure(provider: Provider): String = when (provider.id) {
    ProviderId.OPENAI ->
        "OpenAI does not train on data sent through the API."
    ProviderId.GEMINI ->
        "Google's free tier uses what you send to improve its products, and human " +
            "reviewers may read it. Paid tiers do not."
    ProviderId.ELEVENLABS ->
        "ElevenLabs trains on API data by default; you can opt out in your " +
            "ElevenLabs account settings."
}

/**
 * Providers this release can actually transcribe through. Deliberately narrower than
 * [ProviderCatalog.all] / [Provider.supportsStt] — those describe general provider capability,
 * but Release C2a ships only the OpenAI STT adapter (Gemini/ElevenLabs are C2b, not yet built).
 * Offering a provider here that [com.whispereverywhere.service.FloatingBubbleService] has no
 * adapter for would fall back to on-device with a misleading "no key" message even when a key IS
 * stored; excluding it here is the honest fix, not a downstream one.
 */
internal val STT_CAPABLE_PROVIDERS: Set<ProviderId> = setOf(ProviderId.OPENAI)

/**
 * Caption shown under a selected cloud STT provider.
 *
 * Deliberately makes no speed claim — measured on-device: a typical 3 s utterance transcribes
 * locally in 1.1-1.3 s, so cloud is roughly a tie at best on a good connection. Cloud's case here
 * is accuracy and language coverage, not speed. States what happens (audio goes to the provider,
 * local takes over on failure), nothing more.
 */
internal fun sttSelectionCaption(providerDisplayName: String): String =
    "Audio is sent to $providerDisplayName. If it fails, the on-device model takes over."

/**
 * Body of the one-time cloud disclosure dialog (Release C2a Task 7).
 *
 * PRESENT tense, deliberately: C1 wrote this in future tense because no audio moved yet — C2a is
 * the release where it does, so the declaration and this in-app copy must flip together. Two
 * gating steps are named explicitly (add a key; then separately select the provider as the
 * transcription engine) because a stored key alone sends nothing per-utterance — only a one-time
 * verification call. Mentions the on-device fallback so the failure behavior is disclosed, not
 * just the happy path. Makes no claim about cloud read-aloud: there is no adapter for it in this
 * release (TtsController is untouched), so claiming it is "sent" would overclaim.
 */
internal fun cloudDisclosureMainText(): String =
    "Whisper Everywhere works entirely on your device by default. Adding a provider key below " +
        "sends that key to the provider once, to verify it works. Selecting that provider as " +
        "your transcription engine is what turns cloud on: from then on, the audio you dictate " +
        "is sent to that company's servers to be transcribed, with the on-device model taking " +
        "over automatically if that provider fails."

/** Reiterates both gating steps from [cloudDisclosureMainText] so "off until" isn't read as "off until a key is added" alone. */
internal fun cloudDisclosureOffUntilText(): String =
    "This stays off until you add a key and select that provider as your engine, and you can " +
        "switch back to on-device or remove the key at any time."

// ---------------------------------------------------------------------------------------------
// UI
// ---------------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudProvidersScreen(
    onNavigateBack: () -> Unit,
    onNavigateToPrivacyPolicy: () -> Unit = {},
) {
    val app = WhisperEverywhereApp.getInstance()
    val accounts = app.preferencesManager.providerAccounts
    val validator = remember { KeyValidator(OkHttpTransport()) }
    val context = LocalContext.current

    // This is the only screen that can render a credential (behind the eye-icon reveal), and a
    // screenshot here would land in the gallery and, for most users, auto-sync to Google Photos —
    // exactly the exposure backup_rules.xml exists to prevent; the recents thumbnail captures it
    // passively too. FLAG_SECURE covers the screen's ENTIRE lifetime — it is deliberately not
    // gated on the reveal toggle, which would race the recents snapshot taken when the app
    // backgrounds.
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

    // Bumped after every save/remove so provider rows re-read their stored key from SecureStore.
    var refreshKey by remember { mutableStateOf(0) }

    // Local mirror of the persisted engine selection. null = on-device (the default).
    var sttProviderId by remember { mutableStateOf(app.preferencesManager.sttProviderId) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cloud providers") },
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

            SttEngineSelector(
                accounts = accounts,
                refreshKey = refreshKey,
                selectedProviderId = sttProviderId,
                onSelect = { providerId ->
                    app.preferencesManager.sttProviderId = providerId
                    sttProviderId = providerId
                },
            )

            Spacer(modifier = Modifier.height(16.dp))

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
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    // Play requires this BEFORE any key field becomes editable — see ProviderCard's `editable`
    // param above, which stays false until this dialog is accepted.
    if (!disclosureAccepted) {
        CloudDisclosureDialog(
            onAccept = {
                app.preferencesManager.cloudDisclosureAccepted = true
                disclosureAccepted = true
            },
            onNotNow = onNavigateBack,
            onOpenPrivacyPolicy = onNavigateToPrivacyPolicy,
        )
    }
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
    onSelect: (String?) -> Unit,
) {
    // Off Main: configured() runs a Keystore lookup per provider, same as the masked-key reads
    // below — must not run on the Compose/Main thread.
    val configured by produceState<Set<ProviderId>>(emptySet(), refreshKey) {
        value = withContext(Dispatchers.IO) { accounts.configured() }
    }
    val selectable = ProviderCatalog.all.filter { it.id in STT_CAPABLE_PROVIDERS && it.id in configured }

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

@Composable
private fun ProviderCard(
    provider: Provider,
    accounts: ProviderAccounts,
    storedKeyDisplay: String?,
    validator: KeyValidator,
    editable: Boolean,
    onChanged: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var fieldValue by remember(provider.id) { mutableStateOf("") }
    var showKey by remember(provider.id) { mutableStateOf(false) }
    var verifying by remember(provider.id) { mutableStateOf(false) }
    var lastStatus by remember(provider.id) { mutableStateOf<KeyStatus?>(null) }
    var saveFailure by remember(provider.id) { mutableStateOf<String?>(null) }

    // Wrapped per the brief: a failed secure write must be surfaced, never silently swallowed.
    // Runs on Dispatchers.IO: Cipher init/doFinal, and on the first-ever save a 256-bit
    // KeyGenerator.generateKey() in the TEE (commonly 100-500 ms), must never run on Main.
    suspend fun persist(rawKey: String): Boolean = withContext(Dispatchers.IO) {
        try {
            accounts.setKey(provider.id, rawKey)
            saveFailure = null
            true
        } catch (e: SecureStoreException) {
            saveFailure = "Couldn't save securely — your key was not stored."
            false
        }
    }

    fun verifyAndSave() {
        val candidate = fieldValue
        verifying = true
        lastStatus = null
        saveFailure = null
        scope.launch {
            // Without this handler+finally, any throw here (including one from a malformed
            // header — see KeyValidator's ASCII guard and OkHttpTransport's widened catch) would
            // leave `verifying` stuck true forever: the spinner never stops and the button never
            // re-enables.
            try {
                val result = withContext(Dispatchers.IO) { validator.validate(provider.id, candidate) }
                lastStatus = result
                if (shouldPersistKey(result) && persist(candidate)) {
                    fieldValue = ""
                    onChanged()
                }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                lastStatus = null
                saveFailure = "Something went wrong verifying that key. Try again."
            } finally {
                verifying = false
            }
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = provider.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (provider.supportsStreaming) {
                    StreamingChip()
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = storedKeyDisplay?.let { "Key saved: $it" } ?: "No key saved",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = fieldValue,
                onValueChange = {
                    fieldValue = it
                    lastStatus = null
                    saveFailure = null
                },
                enabled = editable && !verifying,
                singleLine = true,
                label = { Text(if (storedKeyDisplay != null) "Replace key" else "API key") },
                placeholder = {
                    Text(storedKeyDisplay ?: "Paste your key")
                },
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                // Masking is visual only (PasswordVisualTransformation just changes what Compose
                // paints); without this the IME still sees KeyboardType.Text with autocorrect on,
                // so the raw key would appear in the keyboard's suggestion strip above the very
                // field the app masked, and could be committed to the personal dictionary.
                // Applies identically whether showKey is true or false — the IME contract must
                // not depend on the visual reveal state.
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    autoCorrect = false,
                    imeAction = ImeAction.Done,
                ),
                trailingIcon = {
                    IconButton(onClick = { showKey = !showKey }, enabled = editable) {
                        Icon(
                            if (showKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (showKey) "Hide key" else "Show key",
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { verifyAndSave() },
                    enabled = editable && !verifying && fieldValue.isNotBlank(),
                ) {
                    if (verifying) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text("Save & verify")
                }

                if (storedKeyDisplay != null) {
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(
                        onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) { accounts.clear(provider.id) }
                                onChanged()
                            }
                        },
                        enabled = editable,
                    ) {
                        Text("Remove")
                    }
                }
            }

            val status = lastStatus
            when {
                saveFailure != null -> {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = saveFailure ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                status != null -> {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = statusMessage(status, provider.displayName, provider.id),
                        style = MaterialTheme.typography.bodySmall,
                        color = when (status) {
                            is KeyStatus.Valid, is KeyStatus.NoCredit -> Success
                            is KeyStatus.Invalid -> MaterialTheme.colorScheme.error
                            else -> Warning
                        },
                    )
                    // Suppressed when the provider's own body recognizably says the key itself
                    // is wrong (e.g. Gemini's 400 API_KEY_INVALID, routed to Unknown because it
                    // isn't a clean 401) — offering to save a key already known to be bad is
                    // worse than offering nothing.
                    if (status is KeyStatus.Unknown && !looksLikeInvalidKey(status.detail)) {
                        TextButton(
                            onClick = {
                                scope.launch {
                                    if (persist(fieldValue)) {
                                        fieldValue = ""
                                        lastStatus = null
                                        onChanged()
                                    }
                                }
                            },
                            enabled = editable,
                        ) {
                            Text("Save anyway")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Where do I get a key?",
                style = MaterialTheme.typography.bodySmall,
                color = Primary,
                modifier = Modifier.clickable {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(provider.keyHelpUrl)))
                },
            )
        }
    }
}

/** Marks providers with live streaming transcription, so nobody picks Gemini expecting it. */
@Composable
private fun StreamingChip() {
    Surface(
        color = Primary.copy(alpha = 0.12f),
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Podcasts,
                contentDescription = null,
                tint = Primary,
                modifier = Modifier.size(14.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "Streaming",
                style = MaterialTheme.typography.labelSmall,
                color = Primary,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
