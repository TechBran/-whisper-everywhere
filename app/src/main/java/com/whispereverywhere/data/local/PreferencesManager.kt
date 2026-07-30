package com.whispereverywhere.data.local

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class PreferencesManager(private val context: Context) {

    private val secureStore = SecureStore(context)

    /** Per-provider cloud credentials (Release C1). Backed by the same SecureStore. */
    val providerAccounts: com.whispereverywhere.provider.ProviderAccounts =
        com.whispereverywhere.provider.ProviderAccounts(secureStore)

    /** True when the Keystore is usable. False means credentials cannot be stored at all. */
    fun secureStorageAvailable(): Boolean = secureStore.isAvailable()

    // Regular preferences for non-sensitive settings
    private val prefs: SharedPreferences = context.getSharedPreferences(
        "whisper_everywhere_prefs",
        Context.MODE_PRIVATE
    )

    init {
        purgeLegacyCredentialStores()
    }

    /**
     * One-time cleanup of the pre-3.3 credential stores.
     *
     * "encrypted_api_key_fallback" is the dangerous one: it was a MODE_PRIVATE PLAINTEXT file
     * written whenever EncryptedSharedPreferences failed to initialise twice, and both backup
     * rule files excluded only "encrypted_api_key.xml" — so a raw key in it was eligible for
     * Google Drive backup and device-to-device transfer. Users who ran the 2.x cloud-era build
     * may still have a real key sitting there. Delete both files unconditionally; the migration
     * is one-way and a lost key is re-enterable, whereas a leaked one is not retractable.
     *
     * CC3: the completion flag is only written once BOTH stores are confirmed gone. Writing it
     * unconditionally (the old behavior) would permanently mark the purge done even when a
     * transient failure left a plaintext credential on disk forever. Never setting the flag on a
     * genuine failure just costs a cheap retry next launch — the safe side to err on.
     */
    private fun purgeLegacyCredentialStores() {
        if (prefs.getBoolean(KEY_LEGACY_PURGED, false)) return
        val primaryGone = purgeLegacyStore("encrypted_api_key")
        val fallbackGone = purgeLegacyStore("encrypted_api_key_fallback")
        if (primaryGone && fallbackGone) {
            prefs.edit().putBoolean(KEY_LEGACY_PURGED, true).apply()
        }
    }

    /**
     * True once [name]'s SharedPreferences file is confirmed absent — either it never existed,
     * or this call deleted it. False means it may still be sitting on disk.
     *
     * `Context.deleteSharedPreferences` returns false both when deletion fails AND when the file
     * never existed in the first place, so a bare `false` can't be trusted as "still there" on
     * its own; confirm with the file itself. `Context.getSharedPreferencesPath` isn't in the
     * public SDK, so this uses the public `getDataDir()` (API 24+) plus the documented
     * `shared_prefs/<name>.xml` layout every SharedPreferences file lives at.
     */
    private fun purgeLegacyStore(name: String): Boolean {
        val deleted = runCatching { context.deleteSharedPreferences(name) }.getOrDefault(false)
        if (deleted) return true
        return runCatching {
            // The absence check below is only trustworthy if our path model is right. If
            // shared_prefs/ is not where we think it is, File.exists() returns false for a file
            // that is actually sitting on disk somewhere else — and we would then mark the purge
            // complete and leave a plaintext credential forever. That is the one direction this
            // must never fail in, so confirm the directory first and treat "cannot confirm" as
            // "may still be there". The cost of being wrong this way is one cheap retry next
            // launch; the cost of being wrong the other way is permanent.
            val sharedPrefsDir = File(context.dataDir, "shared_prefs")
            if (!sharedPrefsDir.isDirectory) return@runCatching false
            !File(sharedPrefsDir, "$name.xml").exists()
        }.getOrDefault(false)
    }

    // State flows for reactive updates
    private val _vibrationEnabled = MutableStateFlow(prefs.getBoolean(KEY_VIBRATION_ENABLED, true))
    val vibrationEnabled: StateFlow<Boolean> = _vibrationEnabled.asStateFlow()

    private val _bubbleEnabled = MutableStateFlow(prefs.getBoolean(KEY_BUBBLE_ENABLED, false))
    val bubbleEnabled: StateFlow<Boolean> = _bubbleEnabled.asStateFlow()

    private val _selectedLanguage = MutableStateFlow(prefs.getString(KEY_SELECTED_LANGUAGE, "auto") ?: "auto")
    val selectedLanguage: StateFlow<String> = _selectedLanguage.asStateFlow()

    /**
     * The user's own provider credential. Backed by [SecureStore] (Keystore AES-256-GCM).
     *
     * The setter THROWS [SecureStoreException] when secure storage is unavailable. That is
     * deliberate: the previous implementation swallowed the failure and wrote plaintext. Callers
     * must surface the error to the user rather than pretending the key was saved.
     */
    var apiKey: String
        get() = secureStore.get(KEY_API_KEY) ?: ""
        set(value) {
            if (value.isEmpty()) secureStore.remove(KEY_API_KEY) else secureStore.put(KEY_API_KEY, value)
        }

    fun hasApiKey(): Boolean = apiKey.isNotBlank()

    // Vibration feedback
    fun setVibrationEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_VIBRATION_ENABLED, enabled).apply()
        _vibrationEnabled.value = enabled
    }

    fun isVibrationEnabled(): Boolean = _vibrationEnabled.value

    // Bubble enabled state
    fun setBubbleEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BUBBLE_ENABLED, enabled).apply()
        _bubbleEnabled.value = enabled
    }

    fun isBubbleEnabled(): Boolean = _bubbleEnabled.value

    // Bubble display mode: true = always on screen at the user's chosen spot (default);
    // false = auto pop-up near focused text fields / during media, hidden otherwise.
    private val _bubbleAlwaysOn = MutableStateFlow(prefs.getBoolean(KEY_BUBBLE_ALWAYS_ON, true))
    val bubbleAlwaysOn: StateFlow<Boolean> = _bubbleAlwaysOn.asStateFlow()

    fun setBubbleAlwaysOn(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BUBBLE_ALWAYS_ON, enabled).apply()
        _bubbleAlwaysOn.value = enabled
    }

    fun isBubbleAlwaysOn(): Boolean = _bubbleAlwaysOn.value

    // Media transcription source: true (default) = capture the DEVICE's audio stream while
    // media is playing (mic fully off — no room noise / feedback); false = always microphone.
    private val _preferDeviceAudio = MutableStateFlow(prefs.getBoolean(KEY_PREFER_DEVICE_AUDIO, true))
    val preferDeviceAudio: StateFlow<Boolean> = _preferDeviceAudio.asStateFlow()

    fun setPreferDeviceAudio(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PREFER_DEVICE_AUDIO, enabled).apply()
        _preferDeviceAudio.value = enabled
    }

    fun isPreferDeviceAudio(): Boolean = _preferDeviceAudio.value

    // Language selection for transcription
    fun setSelectedLanguage(languageCode: String) {
        prefs.edit().putString(KEY_SELECTED_LANGUAGE, languageCode).apply()
        _selectedLanguage.value = languageCode
    }

    fun getSelectedLanguage(): String = _selectedLanguage.value

    // Returns null for "auto" to let Whisper auto-detect, otherwise returns the language code
    fun getLanguageForApi(): String? {
        val lang = _selectedLanguage.value
        return if (lang == "auto") null else lang
    }

    // Bubble position (x, y as percentage of screen)
    var bubblePositionX: Float
        get() = prefs.getFloat(KEY_BUBBLE_X, 0.9f)
        set(value) {
            prefs.edit().putFloat(KEY_BUBBLE_X, value).apply()
        }

    var bubblePositionY: Float
        get() = prefs.getFloat(KEY_BUBBLE_Y, 0.5f)
        set(value) {
            prefs.edit().putFloat(KEY_BUBBLE_Y, value).apply()
        }

    // Onboarding completed
    var onboardingCompleted: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING_COMPLETED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_ONBOARDING_COMPLETED, value).apply()
        }

    // Overlay pin/lock: when true the bubble cannot be accidentally dragged
    var overlayPinned: Boolean
        get() = prefs.getBoolean(KEY_OVERLAY_PINNED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_OVERLAY_PINNED, value).apply()
        }

    // Selected on-device whisper model tier id (see WhisperCatalog); null = none chosen yet
    var selectedModelId: String?
        get() = prefs.getString(KEY_SELECTED_MODEL_ID, null)
        set(value) {
            prefs.edit().putString(KEY_SELECTED_MODEL_ID, value).apply()
        }

    // Read-aloud speech rate (Track F); 1.0 = the voice's natural pace.
    var ttsSpeed: Float
        get() = prefs.getFloat(KEY_TTS_SPEED, 1.0f)
        set(value) {
            prefs.edit().putFloat(KEY_TTS_SPEED, value).apply()
        }

    // Read-aloud voice (Track F): kokoro-multi-lang-v1_0 speaker id (see TtsVoices).
    var ttsVoiceId: Int
        get() = prefs.getInt(KEY_TTS_VOICE_ID, com.whispereverywhere.tts.TtsVoices.DEFAULT_VOICE_ID)
        set(value) {
            prefs.edit().putInt(KEY_TTS_VOICE_ID, value).apply()
        }

    /**
     * True once the user has seen the cloud disclosure and affirmatively accepted it.
     *
     * Play requires prominent in-app disclosure BEFORE any personal data is sent off-device, shown
     * during normal usage rather than buried in a menu, with affirmative action. Back-press or
     * tap-away must NOT count as acceptance — hence a persisted flag set only by the accept
     * button, never by dismissal.
     */
    var cloudDisclosureAccepted: Boolean
        get() = prefs.getBoolean(KEY_CLOUD_DISCLOSURE_ACCEPTED, false)
        set(value) { prefs.edit().putBoolean(KEY_CLOUD_DISCLOSURE_ACCEPTED, value).apply() }

    /**
     * Which engine transcribes. null = on-device (the default and the shipped behaviour).
     * A ProviderId NAME selects cloud with local as fallback.
     */
    var sttProviderId: String?
        get() = prefs.getString(KEY_STT_PROVIDER, null)
        set(value) {
            if (value == null) prefs.edit().remove(KEY_STT_PROVIDER).apply()
            else prefs.edit().putString(KEY_STT_PROVIDER, value).apply()
        }

    companion object {
        private const val KEY_API_KEY = "openai_api_key"
        private const val KEY_LEGACY_PURGED = "legacy_credential_stores_purged_v1"
        private const val KEY_VIBRATION_ENABLED = "vibration_enabled"
        private const val KEY_BUBBLE_ENABLED = "bubble_enabled"
        private const val KEY_BUBBLE_ALWAYS_ON = "bubble_always_on"
        private const val KEY_PREFER_DEVICE_AUDIO = "prefer_device_audio"
        private const val KEY_BUBBLE_X = "bubble_x"
        private const val KEY_BUBBLE_Y = "bubble_y"
        private const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"
        private const val KEY_SELECTED_MODEL_ID = "selected_model_id"
        private const val KEY_SELECTED_LANGUAGE = "selected_language"
        private const val KEY_OVERLAY_PINNED = "overlay_pinned"
        private const val KEY_TTS_SPEED = "tts_speed"
        private const val KEY_TTS_VOICE_ID = "tts_voice_id"
        /**
         * VERSIONED, and the version must be bumped whenever the disclosure's MEANING changes —
         * not merely its wording.
         *
         * Bumped v1 -> v2 for Release C2a. C1 shipped this dialog in the FUTURE tense ("a future
         * update will send audio"); C2a is that update and rewrote it in the present tense. Every
         * user who could reach a cloud selection on day one already held the v1 flag, because a key
         * can only be stored after accepting and only providers with a stored key are selectable —
         * so without a bump, 100% of them would have had their consent to "we will do this later"
         * silently treated as consent to doing it now.
         *
         * v1 is deliberately left in place rather than migrated: an unset v2 simply re-prompts.
         */
        private const val KEY_CLOUD_DISCLOSURE_ACCEPTED = "cloud_disclosure_accepted_v2"
        private const val KEY_STT_PROVIDER = "stt_provider_id"

        // Whisper API supported languages with display names
        // See: https://platform.openai.com/docs/guides/speech-to-text/supported-languages
        val SUPPORTED_LANGUAGES = listOf(
            "auto" to "Auto-detect",
            "en" to "English",
            "es" to "Spanish",
            "fr" to "French",
            "de" to "German",
            "it" to "Italian",
            "pt" to "Portuguese",
            "nl" to "Dutch",
            "pl" to "Polish",
            "ru" to "Russian",
            "zh" to "Chinese",
            "ja" to "Japanese",
            "ko" to "Korean",
            "ar" to "Arabic",
            "hi" to "Hindi",
            "tr" to "Turkish",
            "vi" to "Vietnamese",
            "th" to "Thai",
            "id" to "Indonesian",
            "ms" to "Malay",
            "tl" to "Tagalog",
            "uk" to "Ukrainian",
            "cs" to "Czech",
            "ro" to "Romanian",
            "hu" to "Hungarian",
            "el" to "Greek",
            "he" to "Hebrew",
            "sv" to "Swedish",
            "da" to "Danish",
            "fi" to "Finnish",
            "no" to "Norwegian",
            "sk" to "Slovak",
            "hr" to "Croatian",
            "bg" to "Bulgarian",
            "sr" to "Serbian",
            "sl" to "Slovenian",
            "et" to "Estonian",
            "lv" to "Latvian",
            "lt" to "Lithuanian",
            "fa" to "Persian",
            "ur" to "Urdu",
            "bn" to "Bengali",
            "ta" to "Tamil",
            "te" to "Telugu",
            "mr" to "Marathi",
            "gu" to "Gujarati",
            "kn" to "Kannada",
            "ml" to "Malayalam",
            "pa" to "Punjabi",
            "sw" to "Swahili",
            "af" to "Afrikaans",
            "cy" to "Welsh",
            "gl" to "Galician",
            "ca" to "Catalan",
            "eu" to "Basque"
        )
    }
}
