package com.whispereverywhere.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
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
import com.whispereverywhere.data.local.SecureStoreException
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
 * Providers this release can actually transcribe through. Tracks the STT adapters that ship in the
 * app, which as of C2b are OpenAI, Gemini, and ElevenLabs — the full catalog. It stays a distinct
 * set rather than collapsing to [Provider.supportsStt] on purpose: [Provider.supportsStt] describes
 * general provider capability, while this is the honest "has a built adapter" gate. Offering a
 * provider here that [com.whispereverywhere.service.FloatingBubbleService] has no adapter for would
 * fall back to on-device with a misleading "no key" message even when a key IS stored; keeping the
 * set adapter-driven is the honest fix, and it must stay in lockstep with [SttProviderFactory].
 */
internal val STT_CAPABLE_PROVIDERS: Set<ProviderId> =
    setOf(ProviderId.OPENAI, ProviderId.GEMINI, ProviderId.ELEVENLABS)

/**
 * The STT selection that should survive removing [removedProvider]'s key: the same selection,
 * unless it was that very provider, in which case the app returns to on-device.
 *
 * A selection must not outlive its own credential. If it did, re-adding a key would silently
 * resume cloud transcription in ONE action, while the gate this app advertises — and describes in
 * its privacy policy — is TWO: add a key, then choose it. It would also leave the selector
 * pointing at a provider that cannot transcribe anything.
 */
internal fun selectionAfterKeyRemoval(selected: String?, removedProvider: ProviderId): String? =
    if (selected == removedProvider.name) null else selected

/**
 * Which providers may be OFFERED as the transcription engine.
 *
 * Gated on [disclosureAccepted] as well as on having a key, because SELECTING a provider — not
 * saving its key — is the action that starts sending audio. Gating only the key field would let a
 * user who has never seen the present-tense disclosure (someone who accepted C1's future-tense
 * version, which is everyone who can reach this screen on upgrade) turn transmission on.
 *
 * An empty result is not an error state: it means on-device, which is the default and always
 * available.
 */
internal fun sttSelectableProviders(
    configured: Set<ProviderId>,
    disclosureAccepted: Boolean,
): List<Provider> =
    if (!disclosureAccepted) emptyList()
    else ProviderCatalog.all.filter { it.id in STT_CAPABLE_PROVIDERS && it.id in configured }

/**
 * Which providers may be OFFERED as the read-aloud voice engine — the TTS analogue of
 * [sttSelectableProviders], and gated identically: disclosure accepted AND a stored key.
 *
 * Selecting a cloud VOICE is the action that starts sending selected read-aloud TEXT off-device (a
 * new data class), so consent must gate SELECTION, not merely storing the key. [disclosureAccepted]
 * is the v3 flag ([com.whispereverywhere.data.local.PreferencesManager.cloudDisclosureAccepted],
 * whose key bumped v2 -> v3 when read-aloud text joined the audio already covered by v2): a user who
 * accepted only the audio-only v2 has an unset v3 and is re-prompted, so this returns empty for them.
 *
 * Membership is the honest "has a built cloud TTS adapter" gate, [ProviderCatalog.TTS_CAPABLE_PROVIDERS],
 * kept in lockstep with [com.whispereverywhere.tts.cloud.TtsProviderFactory]. An empty result is not
 * an error: it means on-device Kokoro, which is the default, the fallback, and always available.
 */
internal fun ttsSelectableProviders(
    configured: Set<ProviderId>,
    disclosureAccepted: Boolean,
): List<Provider> =
    if (!disclosureAccepted) emptyList()
    else ProviderCatalog.all.filter { it.id in ProviderCatalog.TTS_CAPABLE_PROVIDERS && it.id in configured }

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
 * Whether the C4 live-mode toggle should be offered. It is a sub-option of transcribing THROUGH
 * OpenAI, so all three must hold: OpenAI is the SELECTED engine (not merely configured), its key is
 * stored, and the v3 disclosure is accepted. OpenAI is the only provider whose BYOK Realtime
 * WebSocket can stream, so no other provider ever shows it.
 *
 * Gated on the SAME [disclosureAccepted] v3 flag as selection itself — live adds a cost tier, not a
 * new data class, so it needs no new consent surface. Requiring the stored key too means the toggle
 * can never outlive the credential it would stream with.
 */
internal fun liveModeRowVisible(
    selectedProviderId: String?,
    configured: Set<ProviderId>,
    disclosureAccepted: Boolean,
): Boolean =
    disclosureAccepted &&
        selectedProviderId == ProviderId.OPENAI.name &&
        ProviderId.OPENAI in configured

/**
 * The live-mode row's label. Surfaces the price where the mode is chosen — the ONLY new user-facing
 * cost disclosure this mode adds (~4x batch). Deliberately makes NO speed claim: "word-for-word",
 * never "faster". Measured on-device transcription is a tie at best, so a speed claim would be a lie.
 */
internal fun liveModeLabel(): String = "Cloud word-for-word (OpenAI) · about \$0.017/min"

/** Sub-copy under the live-mode toggle. Says WHAT it does, never that it is fast. */
internal fun liveModeCaption(): String = "Transcribes word-for-word as you speak."

/**
 * Body of the one-time cloud disclosure dialog (Release C2a Task 7; extended Task 7 of the cloud
 * TTS plan for v3).
 *
 * PRESENT tense, deliberately: C1 wrote this in future tense because no audio moved yet — C2a is
 * the release where it does, so the declaration and this in-app copy must flip together. Two
 * gating steps are named explicitly (add a key; then separately select the provider as the
 * transcription engine) because a stored key alone sends nothing per-utterance — only a one-time
 * verification call. Mentions the on-device fallback so the failure behavior is disclosed, not
 * just the happy path.
 *
 * The final sentence is the v3 addition: selected read-aloud TEXT is a NEW data class that now
 * leaves the device (TtsController gained a real cloud path in Task 5/6 of the cloud TTS plan),
 * so the disclosure's MEANING changed and the flag bumped `cloud_disclosure_accepted_v2` ->
 * `_v3` (the MF3 rule — bump only on meaning change; v2 stays in the store; an unset v3
 * re-prompts everyone, including users who already accepted the audio-only v2 text). Present
 * tense, no speed claim, matching the rest of this copy.
 */
internal fun cloudDisclosureMainText(): String =
    "Whisper Everywhere works entirely on your device by default. Adding a provider key below " +
        "sends that key to the provider once, to verify it works. Selecting that provider as " +
        "your transcription engine is what turns cloud on: from then on, the audio you dictate " +
        "is sent to that company's servers to be transcribed, with the on-device model taking " +
        "over automatically if that provider fails. When you choose a cloud voice for " +
        "read-aloud, the text you select to be read aloud is also sent to that same provider " +
        "to be spoken."

/** Reiterates both gating steps from [cloudDisclosureMainText] so "off until" isn't read as "off until a key is added" alone. */
internal fun cloudDisclosureOffUntilText(): String =
    "This stays off until you add a key and select that provider as your engine, and you can " +
        "switch back to on-device or remove the key at any time."

@Composable
internal fun ProviderCard(
    provider: Provider,
    accounts: ProviderAccounts,
    storedKeyDisplay: String?,
    validator: KeyValidator,
    editable: Boolean,
    onChanged: () -> Unit,
    onKeyRemoved: () -> Unit,
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
                                // Deselect BEFORE onChanged(): onChanged bumps the refresh key and
                                // recomposes the selector, which must not paint a selected row for
                                // a provider whose key has just gone.
                                onKeyRemoved()
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
