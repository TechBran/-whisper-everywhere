package com.whispereverywhere.ui.screens

// ---------------------------------------------------------------------------------------------
// Pure dashboard logic — Compose-free so it is JVM-unit-testable without Robolectric. Every
// function is a FORMATTER over already-resolved primitives (a provider display name, a model-tier
// label, booleans), NEVER over raw prefs ids or a keystore handle: the Composable resolves the raw
// prefs (keystore off-main) and hands primitives in, mirroring sttSelectionCaption(displayName).
// NO speed claim ever appears; NO price appears here — cloud price stays in the hub's live-mode row
// (liveModeLabel), the one surface it appears on today.
// ---------------------------------------------------------------------------------------------

internal const val ROUTE_HOME = "home"
internal const val ROUTE_FIRST_RUN = "first_run"

/** The three setup-guidance states Home can be in, derived from what the user has configured. */
enum class SetupBanner { TWO_PATH, PARTIAL_LINE, NONE }

/**
 * Which setup guidance Home shows. Neither a model nor any key → the prominent two-path banner
 * (Free & private / Bring your own key). Exactly one present → a thin honest status line. Both →
 * nothing. A single working path is still surfaced (PARTIAL_LINE) because the missing half unlocks
 * real capability: cloud engines/voices need a key, on-device dictation needs a model.
 */
internal fun setupBannerState(hasModel: Boolean, hasAnyKey: Boolean): SetupBanner = when {
    !hasModel && !hasAnyKey -> SetupBanner.TWO_PATH
    hasModel && hasAnyKey -> SetupBanner.NONE
    else -> SetupBanner.PARTIAL_LINE
}

/**
 * The single honest line for PARTIAL_LINE. Names the capability the missing half unlocks — nothing
 * more, no price, no speed claim. Never called for the other two states.
 */
internal fun partialSetupLine(hasModel: Boolean): String =
    if (hasModel)
        "On-device transcription is ready. Add a provider key to use a cloud engine or voice."
    else
        "A provider key is saved. Download a model to transcribe on-device."

/**
 * The transcription-engine chip shared by the Dictation and Transcribe-file cards.
 * [engineDisplayName] is the resolved cloud provider name (ProviderCatalog.byId(sttProviderId)
 * .displayName) or null for on-device; [localModelLabel] is the installed model's tier label
 * (e.g. "Eco") or null when it is absent/unknown.
 */
internal fun transcriptionEngineChip(engineDisplayName: String?, localModelLabel: String?): String =
    if (engineDisplayName == null)
        "On-device" + (localModelLabel?.let { " · $it" } ?: "")
    else
        engineDisplayName

/**
 * The Dictation card's chip. Identical to [transcriptionEngineChip] except that when live
 * word-for-word is active — only possible with a realtime-capable provider selected (see
 * [liveModeRowVisible]) — it appends " · real-time streaming". Batch never streams, so the
 * Transcribe-file card uses [transcriptionEngineChip] directly. "real-time streaming" is a MODE name (owner rename 2026-08-01, from "word-for-word"),
 * never "faster": measured on-device is a tie at best, so a speed claim would be a lie. The
 * `engineDisplayName != null` guard means an (impossible) on-device liveMode never leaves a
 * dangling suffix. Already provider-generic (off `engineDisplayName`), so "ElevenLabs ·
 * word-for-word" / "Soniox · word-for-word" render correctly with no per-provider edit here.
 *
 * [liveMode] is the RESOLVED live state, not the raw persisted flag: the caller must pass
 * [dictationLiveActive] so the suffix appears only for a genuinely live session on a
 * realtime-capable provider. word-for-word live streaming is realtime-capable-provider-only
 * (OpenAI, ElevenLabs, Soniox), and `sttLiveMode` is deliberately left set after an engine switch,
 * so passing the raw flag would advertise "Gemini · word-for-word" over a Gemini BATCH session.
 */
internal fun dictationChip(engineDisplayName: String?, localModelLabel: String?, liveMode: Boolean): String {
    val base = transcriptionEngineChip(engineDisplayName, localModelLabel)
    return if (liveMode && engineDisplayName != null) "$base · real-time streaming" else base
}

/**
 * Whether word-for-word live streaming is actually active — realtime-capable-provider-only,
 * mirroring [com.whispereverywhere.service.decideEngineChoice], which upgrades a session to
 * CLOUD_LIVE only when `liveMode && isRealtimeStt(sttProviderId)`. The persisted `sttLiveMode` flag
 * is deliberately NOT reset when the engine switches to a non-realtime provider (it is inert on
 * batch-only engines — see PreferencesManager), so the Dictation chip MUST re-apply this rule
 * itself; otherwise a stale flag surfaces "word-for-word" over a Gemini BATCH session (the one
 * provider with no client-usable realtime path). [sttProviderIdName] is the raw persisted id
 * (ProviderId.name) or null for on-device.
 */
internal fun dictationLiveActive(sttProviderIdName: String?, sttLiveMode: Boolean): Boolean =
    sttLiveMode && com.whispereverywhere.service.isRealtimeStt(sttProviderIdName)

/**
 * The Read-aloud card's chip. On-device Kokoro → "Kokoro" + optional " · <voice>" (the Kokoro
 * speaker name, e.g. "af_heart"); a cloud engine → "<voice> (<provider>)" (e.g. "marin (OpenAI)"),
 * or "<provider> · choose a voice" when no cloud voice is picked yet. [voiceDisplayName] is already
 * resolved by the caller (cloudVoiceDisplayName / TtsVoices.byId). No price, no speed claim.
 */
internal fun readAloudChip(engineDisplayName: String?, voiceDisplayName: String?): String =
    if (engineDisplayName == null)
        "Kokoro" + (voiceDisplayName?.let { " · $it" } ?: "")
    else
        voiceDisplayName?.let { "$it ($engineDisplayName)" } ?: "$engineDisplayName · choose a voice"

/**
 * MainActivity's start destination. Onboarding is mandatory (owner decisions 2026-08-18/19): a
 * user with no installed model always lands on the two-path chooser, full stop. [hasModel] is the
 * ONLY signal consulted, because it is the only honest proof that setup actually happened — Google
 * Auto Backup restores app preferences (including `onboardingCompleted`) on a fresh install but
 * NEVER restores the model file, so a flag-only check would strand a modelless restored user on a
 * Home screen that can't transcribe: exactly the dead-end mandatory onboarding exists to prevent.
 * `onboardingCompleted` is still written elsewhere (e.g. on ensureSpeech success) for other
 * consumers, but routing no longer reads it.
 */
internal fun firstRunStartDestination(hasModel: Boolean): String =
    if (hasModel) ROUTE_HOME else ROUTE_FIRST_RUN

/** The main control button's two lines. */
data class MainControlLabels(val title: String, val subtitle: String)

/**
 * The bubble control button's title + sub-line. When the bubble can't be enabled yet (a runtime
 * permission or the model is missing) the sub-line routes the user to Settings. The refresh removed
 * Home's per-permission SetupChecklist — the mic/overlay/accessibility grants now live behind the
 * top-right Settings gear, and the setup BANNER only covers model+key — so the old "Setup required
 * below" copy pointed at furniture that no longer renders. No speed claim ever appears here.
 */
internal fun mainControlLabels(isEnabled: Boolean, canEnable: Boolean): MainControlLabels = when {
    isEnabled -> MainControlLabels("Tap to Disable", "Bubble is active")
    canEnable -> MainControlLabels("Tap to Enable", "Bubble is inactive")
    else -> MainControlLabels("Complete setup first", "Grant permissions in Settings")
}
