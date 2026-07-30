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
 * The Dictation card's chip. Identical to [transcriptionEngineChip] except that when C4 live
 * word-for-word is active — only possible with OpenAI selected (see [liveModeRowVisible]) — it
 * appends " · word-for-word". Batch never streams, so the Transcribe-file card uses
 * [transcriptionEngineChip] directly. "word-for-word" is a MODE name, never "faster": measured
 * on-device is a tie at best, so a speed claim would be a lie. The `engineDisplayName != null`
 * guard means an (impossible) on-device liveMode never leaves a dangling suffix.
 */
internal fun dictationChip(engineDisplayName: String?, localModelLabel: String?, liveMode: Boolean): String {
    val base = transcriptionEngineChip(engineDisplayName, localModelLabel)
    return if (liveMode && engineDisplayName != null) "$base · word-for-word" else base
}

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
 * MainActivity's start destination. First run (no model AND onboarding never completed) → the
 * two-path chooser; otherwise Home. Existing users all have a model, so [hasModel] short-circuits
 * them to Home — they NEVER see the chooser. A user who took the key path or SKIPPED has
 * onboardingCompleted set, so they land on Home (with the setup banner) and are never forced back
 * into the chooser despite having no model. This is the ONLY consumer of the formerly-vestigial
 * onboardingCompleted flag (recon §5), repurposed as the honest skip flag.
 */
internal fun firstRunStartDestination(hasModel: Boolean, onboardingCompleted: Boolean): String =
    if (hasModel || onboardingCompleted) ROUTE_HOME else ROUTE_FIRST_RUN
