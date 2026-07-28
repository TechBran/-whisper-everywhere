package com.whispereverywhere.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.whispereverywhere.WhisperEverywhereApp
import com.whispereverywhere.data.local.SecureStoreException
import com.whispereverywhere.net.OkHttpTransport
import com.whispereverywhere.provider.KeyStatus
import com.whispereverywhere.provider.KeyValidator
import com.whispereverywhere.provider.Provider
import com.whispereverywhere.provider.ProviderAccounts
import com.whispereverywhere.provider.ProviderCatalog
import com.whispereverywhere.provider.ProviderId
import com.whispereverywhere.ui.theme.Primary
import com.whispereverywhere.ui.theme.Success
import com.whispereverywhere.ui.theme.Warning
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
internal fun statusMessage(status: KeyStatus, providerDisplayName: String): String = when (status) {
    KeyStatus.Valid -> "Key verified ✓"
    KeyStatus.Invalid -> "That key was rejected. Check you copied all of it."
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

    // Local mirror of the persisted flag: it only ever flips false->true here, via the accept
    // button. Reading the persisted value fresh on every entry means "Not now" (which never
    // writes it) always re-shows the disclosure the next time this screen opens.
    var disclosureAccepted by remember { mutableStateOf(app.preferencesManager.cloudDisclosureAccepted) }

    // Bumped after every save/remove so provider rows re-read their stored key from SecureStore.
    var refreshKey by remember { mutableStateOf(0) }

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

            ProviderCatalog.all.forEach { provider ->
                val storedKey = remember(refreshKey, provider.id) { accounts.key(provider.id) }
                ProviderCard(
                    provider = provider,
                    accounts = accounts,
                    storedKey = storedKey,
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
                "Cloud transcription sends your audio off this device.",
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
                    "Whisper Everywhere works entirely on your device by default. If you add " +
                        "a provider key below, the audio you dictate — and text you " +
                        "select for read-aloud — is sent to that company's servers to be " +
                        "processed.",
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
                    "This is off until you add a key, and you can remove a key at any time.",
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

@Composable
private fun ProviderCard(
    provider: Provider,
    accounts: ProviderAccounts,
    storedKey: String?,
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
    fun persist(rawKey: String): Boolean = try {
        accounts.setKey(provider.id, rawKey)
        saveFailure = null
        true
    } catch (e: SecureStoreException) {
        saveFailure = "Couldn't save securely — your key was not stored."
        false
    }

    fun verifyAndSave() {
        val candidate = fieldValue
        verifying = true
        lastStatus = null
        saveFailure = null
        scope.launch {
            val result = withContext(Dispatchers.IO) { validator.validate(provider.id, candidate) }
            lastStatus = result
            if (shouldPersistKey(result) && persist(candidate)) {
                fieldValue = ""
                onChanged()
            }
            verifying = false
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
                text = storedKey?.let { "Key saved: ${maskedKeyPlaceholder(it)}" } ?: "No key saved",
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
                label = { Text(if (storedKey != null) "Replace key" else "API key") },
                placeholder = {
                    Text(storedKey?.let { maskedKeyPlaceholder(it) } ?: "Paste your key")
                },
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
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

                if (storedKey != null) {
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(
                        onClick = {
                            accounts.clear(provider.id)
                            onChanged()
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
                        text = statusMessage(status, provider.displayName),
                        style = MaterialTheme.typography.bodySmall,
                        color = when (status) {
                            is KeyStatus.Valid, is KeyStatus.NoCredit -> Success
                            is KeyStatus.Invalid -> MaterialTheme.colorScheme.error
                            else -> Warning
                        },
                    )
                    if (status is KeyStatus.Unknown) {
                        TextButton(
                            onClick = {
                                if (persist(fieldValue)) {
                                    fieldValue = ""
                                    lastStatus = null
                                    onChanged()
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
