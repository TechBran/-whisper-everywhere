package com.whispereverywhere.data.local

import android.content.Context
import android.content.SharedPreferences
import com.whispereverywhere.model.ModelInstallSignal
import com.whispereverywhere.npu.NpuApuVerdict
import com.whispereverywhere.npu.NpuRedownload
import com.whispereverywhere.provider.ProviderId
import com.whispereverywhere.service.BubbleColours
import com.whispereverywhere.service.ResizeMath
import com.whispereverywhere.transcription.speakers.SpeakerSpikeStore
import com.whispereverywhere.tts.ttsCloudVoiceKey
import java.io.File
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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
        backfillBubbleAlwaysOn()
    }

    /**
     * ONE-TIME BACKFILL for the "Keep bubble always on screen" default flip (4.8.0, owner ruling
     * 2026-09-17: *"We have to change the default keep bubble always on screen. That's defaulted
     * as on. We want it to not be on the screen all the time as the default."*).
     *
     * The default is a promise to NEW users: a fresh install gets the pop-up bubble. It must not
     * be a change forced on EXISTING ones: a user who never touched the toggle has been living in
     * always-on since they installed, and finding the bubble gone after an update — replaced by
     * one that only appears near a text field — is a behaviour change nobody asked for, on the
     * one control the whole app hangs off. So an install with NO stored value has the value it
     * is to live under written down once, before the flow below is created — `true` for an
     * existing install, which then reads exactly what it read before; `false` for a fresh one —
     * and every later construction finds a stored value and does nothing. The FRESH write is
     * not decoration: this class is built once per process, a fresh install finishes onboarding
     * inside that process, and nothing on the onboarding path writes this key, so a fresh
     * install left unwritten would be indistinguishable from an existing one at its NEXT
     * process start (onboarding completed, no stored value) and would be flipped to always-on —
     * the owner's ruling honoured for exactly one process lifetime (4.8.0 review, findings 6-8).
     * The decision is [bubbleAlwaysOnBackfill], pure and tested; this is only the read and the
     * write. Runs before `_bubbleAlwaysOn`'s initialiser by declaration order (Kotlin runs `init`
     * blocks and property initialisers top to bottom), which is what makes the write visible to
     * the flow's first read.
     */
    private fun backfillBubbleAlwaysOn() {
        val write = bubbleAlwaysOnBackfill(
            hasStoredValue = prefs.contains(KEY_BUBBLE_ALWAYS_ON),
            onboardingCompleted = prefs.getBoolean(KEY_ONBOARDING_COMPLETED, false),
        ) ?: return
        prefs.edit().putBoolean(KEY_BUBBLE_ALWAYS_ON, write).apply()
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

    // Bubble display mode: true = always on screen at the user's chosen spot; false = auto
    // pop-up near focused text fields / during media, hidden otherwise. OFF by default since
    // 4.8.0 (owner ruling 2026-09-17) for NEW installs only — `backfillBubbleAlwaysOn` in `init`
    // has already written the answer for an install with no stored value (`true` for an existing
    // install that never touched the toggle, `false` for a fresh one), so this read never changes
    // anyone's mode on upgrade; the constant here is the fresh write's own value, so the read's
    // fallback agrees with the store whichever of the two a construction reaches first. Without
    // the accessibility service the bubble is always-on regardless of this value
    // (FloatingBubbleService.alwaysOnMode, 4.3.3 N1).
    private val _bubbleAlwaysOn =
        MutableStateFlow(prefs.getBoolean(KEY_BUBBLE_ALWAYS_ON, BUBBLE_ALWAYS_ON_DEFAULT))
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
    /**
     * THE ONE WRITER of the selection — the in-app dropdown and onboarding's Continue, and
     * nowhere else (`LiveWordsCardPinTest` holds both sites to one write each).
     *
     * (4.5.0 Task 3b) It also RECORDS THE PICK, because it is the one place that can see a pick
     * happen: `selectedLanguage` answers *"which language is selected"* and can never answer
     * *"did the user just choose it"*, and the UNASKED path's two cautions divide on exactly
     * that — a gesture this process watched skips the wait for a network that WORKS and the 24 h
     * back-off after a failure, and a standing selection left over from an earlier launch does
     * not. That, and `PreviewWork.starter` for Task 1's observable, is the whole of what the
     * register buys; `PreviewPicks`' own KDoc is its home.
     *
     * **It is NOT a question about what the bytes COST** (4.5.0 pass 2, Fix 1). This paragraph
     * used to hand the split to the owner by name and by date and state it as a wifi asymmetry,
     * and that was the middle of three rulings rather than the settled one. The owner settled it
     * the other way, asked directly and answered directly: *"Yes. I wanted to silently download
     * on cellular and Wi Fi."* Both starters download on any connection, there is no metering
     * test left anywhere in this feature, and `PreviewAutoFetch`'s KDoc is that ruling's home.
     *
     * Recording a FACT about a gesture is not owning a download: the 4.4.1 amendment's rule that
     * *"a SharedPreferences writer called from Compose click handlers has no business owning a
     * download"* still holds, and the decision that turns a pick into an arrival is where it
     * always was (`PreviewAutoFetch.decide`, from Home's card).
     *
     * The pick is noted BEFORE the flow is written, and the order is DETERMINISM rather than
     * correctness (review r1's nit 1 — the first version of this comment claimed it was
     * load-bearing, and the claim was false in both halves). For a re-pick of the language
     * already selected the selection flow does not emit AT ALL (`MutableStateFlow` conflates
     * equal values) and `PreviewPicks` is the only emitter; for a new language both writes happen
     * on the same thread before any recomposition runs, so a collector cannot see one without
     * eventually seeing the other. `LiveWordsCardPinTest` pins the order anyway, because one
     * fixed order is one fewer thing to reason about — but the reason is not that a note placed
     * after the flow write would be read as a top-up.
     */
    fun setSelectedLanguage(languageCode: String) {
        prefs.edit().putString(KEY_SELECTED_LANGUAGE, languageCode).apply()
        com.whispereverywhere.transcription.stream.PreviewPicks.note(languageCode)
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

    // Transcript preview panel size in dp (W3 resize handle). Written only by resize drag-end
    // and long-press reset; every read is applied through FloatingBubbleService.applyPreviewSize,
    // which re-clamps against the LIVE screen — a stale/corrupt value can't wedge the panel.
    var bubbleTextWidthDp: Float
        get() = prefs.getFloat(KEY_BUBBLE_TEXT_WIDTH_DP, ResizeMath.DEFAULT_WIDTH_DP)
        set(value) {
            prefs.edit().putFloat(KEY_BUBBLE_TEXT_WIDTH_DP, value).apply()
        }

    var bubbleTextHeightDp: Float
        get() = prefs.getFloat(KEY_BUBBLE_TEXT_HEIGHT_DP, ResizeMath.DEFAULT_HEIGHT_DP)
        set(value) {
            prefs.edit().putFloat(KEY_BUBBLE_TEXT_HEIGHT_DP, value).apply()
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

    // Home's cloud-key note (Workstream B): set true by the card's X, never unset in-app. The
    // note ALSO hides permanently once any provider key is configured — that gate lives in the
    // visibility predicate (CloudKeyNote.shouldShow), independent of this flag.
    var cloudNoteDismissed: Boolean
        get() = prefs.getBoolean(KEY_CLOUD_NOTE_DISMISSED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_CLOUD_NOTE_DISMISSED, value).apply()
        }

    // Selected on-device whisper model tier id (see WhisperCatalog); null = none chosen yet.
    //
    // Backed by a MutableStateFlow (3.6.0, Workstream E1) so the bubble service can re-prewarm
    // the native context the moment the user switches tiers — prewarm() fills only an EMPTY slot,
    // so before this the first session after a switch paid the ~7 s release+load inline in
    // CONNECTING. The `var` API is unchanged, and EVERY writer in the app already goes through
    // this setter (ModelDownloadViewModel, OnboardingSetupViewModel x2, OnboardingFlowScreen,
    // SettingsScreen x3), so the flow can never drift from prefs — the same one-mirror pattern
    // as sttProviderId below. One mechanism instead of seven per-writer hooks.
    private val _selectedModelId = MutableStateFlow(prefs.getString(KEY_SELECTED_MODEL_ID, null))
    val selectedModelIdFlow: StateFlow<String?> = _selectedModelId.asStateFlow()

    var selectedModelId: String?
        get() = _selectedModelId.value
        set(value) {
            prefs.edit().putString(KEY_SELECTED_MODEL_ID, value).apply()
            _selectedModelId.value = value
        }

    /**
     * "A model finished downloading and passed verification" (3.6.0, Workstream E1). The
     * companion trigger to [selectedModelIdFlow], and NOT redundant with it: the onboarding flow
     * writes the picked id BEFORE the download starts (nothing is on disk yet, so a re-prewarm
     * then no-ops) and rewrites the SAME id afterwards, which a StateFlow conflates into no
     * emission at all. That is precisely the case whose context is stale, so it needs a signal
     * that carries "on disk now" instead of "selected now".
     *
     * SharedFlow, not StateFlow: consecutive installs must each emit, and there is no meaningful
     * "current value". extraBufferCapacity = 1 with the default suspend-free [tryEmit] path so
     * the emitter (a Dispatchers.IO download coroutine) never blocks and never needs a scope; a
     * dropped duplicate while the collector is mid-prewarm is harmless — the prewarm it is
     * already running loads the same file.
     */
    private val _modelInstalled = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val modelInstalled: SharedFlow<Unit> = _modelInstalled.asSharedFlow()

    /**
     * Called by WhisperModelManager once a downloaded model is verified on disk — **and by Q8's
     * SAF importer once the npu pair is verified**, which is the same event by a different route.
     *
     * Two signals, because they have two different consumers and two different shapes.
     * [modelInstalled] is a `SharedFlow<Unit>` the bubble collects to schedule a prewarm;
     * [ModelInstallSignal.generation] is a monotonic counter the Compose choosers use as a
     * re-read KEY, which `Unit` cannot serve as (4.0, Q7b fix round, I1). One function so a caller
     * cannot deliver half the news.
     */
    fun notifyModelInstalled() {
        _modelInstalled.tryEmit(Unit)
        ModelInstallSignal.bump()
    }

    // ============================================ 4.15 — THE AI-CHIP RE-DOWNLOAD RECORD

    /**
     * DEVICE-LOCAL STATE: facts about THIS phone's disk, in a store of their own (4.15).
     *
     * `whisper_everywhere_prefs` is on the backup allowlist (`backup_rules.xml`,
     * `data_extraction_rules.xml`), so it travels to a new phone by Auto Backup and by
     * device-to-device transfer. The re-download record must not: it says "the pair THIS phone
     * held was removed", and restored onto a phone that never held one it would tell a fresh
     * install that its AI-chip model "has a faster version — download it again", about a model it
     * never had, possibly on silicon with no AI-chip tier at all. Both rule files are allowlists,
     * so a file they do not name is excluded by omission — which is how `whisper_usage_prefs`,
     * `whisper_cost_prefs` and `gpu_policy` already stay home. Nothing may add this file to them.
     */
    private val deviceLocal: SharedPreferences = context.getSharedPreferences(
        DEVICE_LOCAL_PREFS,
        Context.MODE_PRIVATE
    )

    private val _npuRedownload =
        MutableStateFlow(readNpuRedownload { key, default -> deviceLocal.getString(key, default) })

    /**
     * "The selected AI-chip tier needs a re-download" (4.15, owner ruling 2026-09-24) — written by
     * the launch stale-pair sweep when it removed the SELECTED tier's pair for failing this build's
     * census, cleared by the shared finalise when a pair for that tier lands. A StateFlow, the
     * [selectedModelIdFlow] one-mirror pattern, so the onboarding flow's sentence disappears the
     * moment the fetch completes without anyone re-reading the store.
     */
    val npuRedownloadFlow: StateFlow<NpuRedownload?> = _npuRedownload.asStateFlow()

    val npuRedownload: NpuRedownload?
        get() = _npuRedownload.value

    /** Record a stale event — see [npuRedownloadFlow]. One edit, so the two halves land together. */
    fun recordNpuRedownload(record: NpuRedownload) {
        deviceLocal.edit()
            .putString(KEY_NPU_REDOWNLOAD_TIER, record.tierId)
            .putString(KEY_NPU_REDOWNLOAD_CENSUS, record.censusKey)
            .apply()
        _npuRedownload.value = record
    }

    /**
     * A pair for [landedTierId] landed (the shared finalise's committed branch): the record for
     * that tier is resolved, and so is the note that the update notice already went out for it.
     * A landing for any other tier leaves the record alone — [NpuRedownload.clearedBy] is the rule.
     *
     * Answers whether it cleared (112): the finalise takes the shade notice down on a `true`, and
     * a landing that left the record standing leaves the notice standing with it.
     */
    fun clearNpuRedownload(landedTierId: String): Boolean {
        if (!NpuRedownload.clearedBy(_npuRedownload.value, landedTierId)) return false
        deviceLocal.edit()
            .remove(KEY_NPU_REDOWNLOAD_TIER)
            .remove(KEY_NPU_REDOWNLOAD_CENSUS)
            .remove(KEY_NPU_REFRESH_NOTIFIED_CENSUS)
            .apply()
        _npuRedownload.value = null
        return true
    }

    /**
     * THE MEDIATEK DRIVER CHECK'S STORED VERDICT (P2; design §2.3 item 3), or null when none is
     * stored or the stored one is unreadable — both of which mean "probe again". Device-local by
     * this store's rule: it is a fact about THIS device's driver, and restored onto another it
     * would answer for a ROM it was never taken on (its fingerprint would refuse it anyway — two
     * walls, not one). Read once per process by `WhisperEverywhereApp.settleApuDriverVerdict`,
     * which decides whether it still answers (`NpuApuDriverCheck.reusableOrNull`).
     */
    val npuApuVerdict: NpuApuVerdict?
        get() = readNpuApuVerdict { key, default -> deviceLocal.getString(key, default) }

    /** Store the driver check's verdict — one key, one JSON document, so it cannot tear. */
    fun recordNpuApuVerdict(verdict: NpuApuVerdict) {
        deviceLocal.edit().putString(KEY_NPU_APU_VERDICT, verdict.encode()).apply()
    }

    /**
     * The census key the app-update notice was last posted for — what makes that notice fire ONCE
     * per stale event on `MY_PACKAGE_REPLACED` (`NpuRefreshNotice.decide`). Device-local with the
     * record, and cleared with it.
     */
    var npuRefreshNotifiedCensusKey: String?
        get() = deviceLocal.getString(KEY_NPU_REFRESH_NOTIFIED_CENSUS, null)
        set(value) {
            if (value == null) deviceLocal.edit().remove(KEY_NPU_REFRESH_NOTIFIED_CENSUS).apply()
            else deviceLocal.edit().putString(KEY_NPU_REFRESH_NOTIFIED_CENSUS, value).apply()
        }

    /**
     * Developer toggle: allow the canary-validated GPU path for MULTILINGUAL whisper models
     * (3.6.0 Workstream C). **Defaults to false and ships false.** The multilingual GPU ban is
     * empirical (silent garbage-token / empty-output corruption on Adreno OpenCL,
     * the empirical-corruption docblock above GpuPolicy.isGpuSafeModel); this release adds the canary MECHANISM and the owner's measurement,
     * not a default change. Flipping the default is a separate, data-driven decision (spec
     * Decision Gate 2). Even when ON, a model only reaches the GPU after passing the bundled
     * canary once on this device — and a failure latches that model to CPU permanently.
     */
    private val _gpuMultilingualExperiment =
        MutableStateFlow(prefs.getBoolean(KEY_GPU_MULTI_EXPERIMENT, false))
    val gpuMultilingualExperiment: StateFlow<Boolean> = _gpuMultilingualExperiment.asStateFlow()

    fun setGpuMultilingualExperiment(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_GPU_MULTI_EXPERIMENT, enabled).apply()
        _gpuMultilingualExperiment.value = enabled
    }

    fun isGpuMultilingualExperimentEnabled(): Boolean = _gpuMultilingualExperiment.value

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
        // Routes through the pure, version-scoped seam so the read path a unit test can pin (default
        // false; a prior-version acceptance does NOT satisfy the current getter) IS the production
        // read path — see readCloudDisclosureAccepted / CLOUD_DISCLOSURE_KEY.
        get() = readCloudDisclosureAccepted(prefs::getBoolean)
        set(value) { prefs.edit().putBoolean(CLOUD_DISCLOSURE_KEY, value).apply() }

    /**
     * Which engine transcribes. null = on-device (the default and the shipped behaviour).
     * A ProviderId NAME selects cloud with local as fallback.
     *
     * Backed by a [MutableStateFlow] so Home's mode-dashboard chips read it reactively (no polling)
     * via [sttProviderIdFlow]. The `var` API is unchanged: the getter still returns the current
     * value and the setter still writes prefs — it now also pushes the mirror, and every write in
     * the app already goes through this setter, so the flow can never drift from prefs.
     */
    private val _sttProviderId = MutableStateFlow(prefs.getString(KEY_STT_PROVIDER, null))
    val sttProviderIdFlow: StateFlow<String?> = _sttProviderId.asStateFlow()

    var sttProviderId: String?
        get() = _sttProviderId.value
        set(value) {
            if (value == null) prefs.edit().remove(KEY_STT_PROVIDER).apply()
            else prefs.edit().putString(KEY_STT_PROVIDER, value).apply()
            _sttProviderId.value = value
        }

    /**
     * The batch-vs-live axis for cloud STT (C4; widened from OpenAI-only by the realtime
     * all-providers wave). true = word-for-word live streaming over the selected provider's own
     * Realtime WebSocket (OpenAI, ElevenLabs, or Soniox — each behind its own RealtimeProtocol);
     * false = the one-shot batch POST.
     *
     * **Defaults to true** (owner decision 2026-07-31): a streaming provider should stream the
     * moment its key is in, with no second switch to find. Word-for-word is the reason to pick one
     * of these three over on-device at all, and the previous false default meant every user paid
     * for a cloud provider and silently got the batch transport until they went looking for a
     * toggle. Safe to flip rather than migrate: this preference has never shipped — 3.2.0
     * (versionCode 72, the released build) has no cloud STT at all, and cloud arrives with 3.3.0.
     * Anyone who DOES set it keeps their choice, since a stored value always beats this default.
     *
     * Orthogonal to [sttProviderId], which still names the provider: this only flips HOW the audio
     * is sent. It is consulted only when the selected provider is realtime-capable AND is not
     * Gemini — Gemini's live mode (4.3.4) has its own flag, [sttLiveModeGemini], which carries the
     * same default-on rule, so this one never reaches it. It is equally inert with no provider
     * selected, no key, or no network: the live leaf sits AFTER those local guards, so an on-device
     * user is untouched.
     * Same mic audio, same provider, same v3 disclosure as batch; the ONLY user-visible change is
     * a new transport and a per-provider cost tier (about $0.0045/min OpenAI gpt-transcribe,
     * $0.007/min ElevenLabs, $0.002/min Soniox; Gemini's own row: about $0.009/min on a paid key,
     * $0 on the free tier where Google may train on the audio), surfaced on the selector row
     * itself — which is why the row stays visible and switchable.
     */
    private val _sttLiveMode = MutableStateFlow(prefs.getBoolean(KEY_STT_LIVE_MODE, true))
    /** Reactive mirror of [sttLiveMode] for the Dictation card's word-for-word chip. Additive. */
    val sttLiveModeFlow: StateFlow<Boolean> = _sttLiveMode.asStateFlow()

    var sttLiveMode: Boolean
        get() = _sttLiveMode.value
        set(value) {
            prefs.edit().putBoolean(KEY_STT_LIVE_MODE, value).apply()
            _sttLiveMode.value = value
        }

    /**
     * Gemini's OWN batch-vs-live axis (4.3.4), **default true** — like the other three, a Gemini key
     * streams the moment it is in.
     *
     * Owner ruling 2026-09-10: *"Gemini Live must be the DEFAULT whenever a Gemini key is present —
     * we shouldn't even have to select."* This supersedes the controller's holding ruling of the
     * same morning (made in the owner's absence), which defaulted it false so that users who had
     * chosen Gemini as a BATCH engine stayed on batch until they opted in. Only the ABSENT-pref
     * default flips: a stored value always beats it, so anyone who has already set the switch —
     * either way — keeps their choice untouched.
     *
     * Still a SEPARATE preference from [sttLiveMode] rather than a seed into it, for the reason
     * that outlives the default: a Gemini-only user could never have expressed a choice through
     * the shared flag (Gemini's live row did not render before 4.3.4), so Gemini's own explicit
     * on/off has to be recordable apart from the other three's. `liveModeFor` resolves which flag
     * applies.
     *
     * The row's switch is therefore an OPT-OUT, not an opt-in: turned off, Gemini sends each
     * finished phrase as one batch request instead of streaming (live is about $0.009/min on a paid
     * key and $0 on the free tier, where Google may use the audio to improve its products — the
     * cost and training copy lives on the row itself, `liveModeLabel`/`liveModeCaption`).
     */
    private val _sttLiveModeGemini = MutableStateFlow(prefs.getBoolean(KEY_STT_LIVE_MODE_GEMINI, true))
    /** Reactive mirror of [sttLiveModeGemini] for the Dictation card's chip. Additive. */
    val sttLiveModeGeminiFlow: StateFlow<Boolean> = _sttLiveModeGemini.asStateFlow()

    var sttLiveModeGemini: Boolean
        get() = _sttLiveModeGemini.value
        set(value) {
            prefs.edit().putBoolean(KEY_STT_LIVE_MODE_GEMINI, value).apply()
            _sttLiveModeGemini.value = value
        }

    /**
     * 4.4.0 — RULING ASSUMED (R3): the on-device word-for-word previewer is DEFAULT-ON and
     * additive for a fixed-English user with the pack installed; the whisper step stays the
     * mandatory one. The switch only matters once the pack exists — the PACK is the opt-in, and
     * a second switch to find would leave the feature invisible to the users it was built for —
     * and the gate (`localPreviewArms`) reads it per session, so turning it off restores 4.3.4's
     * strip exactly. Reactive mirror for the Settings row.
     */
    private val _localPreviewEnabled = MutableStateFlow(prefs.getBoolean(KEY_LOCAL_PREVIEW_ENABLED, true))
    val localPreviewEnabledFlow: StateFlow<Boolean> = _localPreviewEnabled.asStateFlow()

    var localPreviewEnabled: Boolean
        get() = _localPreviewEnabled.value
        set(value) {
            prefs.edit().putBoolean(KEY_LOCAL_PREVIEW_ENABLED, value).apply()
            _localPreviewEnabled.value = value
        }

    /**
     * 4.10 — SPEAKER DETECTION, DEFAULT ON (spec §7 item 1, taken as proposed).
     *
     * The owner's complaint is the shape of the output, not a missing option: *"right now the text
     * just comes out as a big blob"*. Paragraph breaks at a speaker change are the fix, and a fix
     * behind a switch nobody finds is not delivered. ON is safe as a default because a
     * ONE-SPEAKER session is byte-for-byte today's output on every surface — the panel shows no
     * label until a second voice is CONFIRMED (`SpeakerTracker.CONFIRM_N` segments of at least
     * `SpeakerTracker.MIN_OPEN_SECONDS`), and a session that never confirms never renders a
     * break either.
     *
     * Read PER SESSION at the tap (`FloatingBubbleService.startRecording`), so turning it off
     * restores 4.9's output exactly from the next recording, and the ~40-60 MB the embedder holds
     * resident is never loaded at all for a user who turned it off. Cloud sessions ignore it
     * entirely (spec §2, §4: they behave exactly as today).
     */
    private val _detectSpeakers = MutableStateFlow(prefs.getBoolean(KEY_DETECT_SPEAKERS, DETECT_SPEAKERS_DEFAULT))
    val detectSpeakersFlow: StateFlow<Boolean> = _detectSpeakers.asStateFlow()

    var detectSpeakers: Boolean
        get() = _detectSpeakers.value
        set(value) {
            prefs.edit().putBoolean(KEY_DETECT_SPEAKERS, value).apply()
            _detectSpeakers.value = value
            // (4.10 spike session 2) Turning detection OFF takes the fingerprint dump with it,
            // every file of it, immediately — not at the next session, which a user who just
            // turned the feature off may never start. Nothing in the app ever reads a dump back,
            // so there is nothing to lose and no reason to keep speech-derived data one tap
            // longer than the feature that produced it. Off the Main thread
            // (`SpeakerSpikeStore.purgeAsync`) because this setter runs under a finger and a
            // session's WAVs can be hundreds of files.
            //
            // UNCONDITIONAL since 4.10.0, and that is the point of the line. It used to be
            // ANDed with the spike's compile-time switch, which was right while the spike was
            // armed and is exactly wrong now that it is not: with that switch off no session can
            // write a dump AND the 24 h sweep inside the dump's own open() is compiled away with
            // it, so a phone upgrading from a spike build to this one would keep that build's
            // speech audio forever. A deletion must not be gated on the switch that produced the
            // thing being deleted. `SpeakerSpike.purge` carries the argument; the other caller is
            // `WhisperEverywhereApp.onCreate`, which is what reaches a user who never taps here.
            if (!value) SpeakerSpikeStore.purgeAsync(context)
        }

    /**
     * 4.10 — `Speaker N:` LABELS IN COPIED AND SAVED TEXT, DEFAULT OFF (spec §2, owner's ruling:
     * *"when we copy it out, no need for that unless we see fit — maybe a setting where speaker
     * labels go to the copied text or the saved text … if someone flips the toggle on, then they
     * get the speaker labels"*).
     *
     * OFF is the default because the clipboard and the saved transcript are what LEAVES the app,
     * and their shape must not change under a user who did not ask for it. Paragraph breaks at a
     * speaker change appear there regardless — they are the feature; the labels are the opinion.
     *
     * It never affects text typed into another app's field: a text field is not a transcript
     * (spec §2, the owner's ruling), so that surface gets paragraphs and never labels whatever
     * this reads. And it is applied at EXPORT time rather than at save time, which is why the
     * saved transcript keeps its speaker boundaries beside it: a user who flips this on wants it
     * to be true of the transcripts they already have.
     */
    private val _speakerLabelsInExport =
        MutableStateFlow(prefs.getBoolean(KEY_SPEAKER_LABELS_IN_EXPORT, SPEAKER_LABELS_IN_EXPORT_DEFAULT))
    val speakerLabelsInExportFlow: StateFlow<Boolean> = _speakerLabelsInExport.asStateFlow()

    var speakerLabelsInExport: Boolean
        get() = _speakerLabelsInExport.value
        set(value) {
            prefs.edit().putBoolean(KEY_SPEAKER_LABELS_IN_EXPORT, value).apply()
            _speakerLabelsInExport.value = value
        }

    /**
     * 4.5.1 — THE BUBBLE'S THREE USER-OWNED PRESENTATION FACTS (owner ruling 2026-09-12:
     * *"users can change the colour of their live words and of their transcribed committed words
     * as well … and maybe even the clarity of the black bubble background"*).
     *
     * [com.whispereverywhere.service.BubbleColours] owns every rule about them — the palette, the
     * opacity ladder, the contrast floor and the reasoning for each. These three properties are
     * storage only, and they take the shape [localPreviewEnabled] takes because the Settings
     * sample has to redraw on the tap that changed a colour: a `StateFlow` mirror written by the
     * one setter that writes the file.
     *
     * **Every READ goes through the guard, and that is the load-bearing half.** The palette and
     * the ladder can be edited in a later build *under* a value a user already stored, and `0` is
     * what an Int preference reads as if the file is ever cleared — so a raw read could hand the
     * bubble black-on-black, the one combination the owner ruled out. The re-clamp on read is the
     * discipline `applyPreviewSize` already applies to the panel's geometry for the same reason.
     * The write guard is hygiene on top: it keeps an out-of-range value from reaching the file.
     */
    private val _bubbleLiveColour = MutableStateFlow(
        BubbleColours.textColour(
            prefs.getInt(KEY_BUBBLE_LIVE_COLOUR, BubbleColours.LIVE_DEFAULT),
            BubbleColours.LIVE_DEFAULT,
        )
    )
    val bubbleLiveColourFlow: StateFlow<Int> = _bubbleLiveColour.asStateFlow()

    var bubbleLiveColour: Int
        get() = _bubbleLiveColour.value
        set(value) {
            prefs.edit().putInt(KEY_BUBBLE_LIVE_COLOUR, BubbleColours.textColour(value, BubbleColours.LIVE_DEFAULT)).apply()
            _bubbleLiveColour.value = BubbleColours.textColour(value, BubbleColours.LIVE_DEFAULT)
        }

    private val _bubbleCommittedColour = MutableStateFlow(
        BubbleColours.textColour(
            prefs.getInt(KEY_BUBBLE_COMMITTED_COLOUR, BubbleColours.COMMITTED_DEFAULT),
            BubbleColours.COMMITTED_DEFAULT,
        )
    )
    val bubbleCommittedColourFlow: StateFlow<Int> = _bubbleCommittedColour.asStateFlow()

    var bubbleCommittedColour: Int
        get() = _bubbleCommittedColour.value
        set(value) {
            prefs.edit().putInt(KEY_BUBBLE_COMMITTED_COLOUR, BubbleColours.textColour(value, BubbleColours.COMMITTED_DEFAULT)).apply()
            _bubbleCommittedColour.value = BubbleColours.textColour(value, BubbleColours.COMMITTED_DEFAULT)
        }

    private val _bubbleOpacityPercent = MutableStateFlow(
        BubbleColours.opacityPercent(
            prefs.getInt(KEY_BUBBLE_OPACITY_PERCENT, BubbleColours.OPACITY_DEFAULT_PERCENT),
        )
    )
    val bubbleOpacityPercentFlow: StateFlow<Int> = _bubbleOpacityPercent.asStateFlow()

    var bubbleOpacityPercent: Int
        get() = _bubbleOpacityPercent.value
        set(value) {
            prefs.edit().putInt(KEY_BUBBLE_OPACITY_PERCENT, BubbleColours.opacityPercent(value)).apply()
            _bubbleOpacityPercent.value = BubbleColours.opacityPercent(value)
        }

    /**
     * 4.4.1 — THE USER SAID NO, **for one language** (owner rulings 2026-09-11, consequence 5).
     * Set by Home's live-words card X and by the Settings row's DELETE, never unset in-app; read
     * by [com.whispereverywhere.transcription.stream.PreviewAutoFetch], which treats it as
     * absolute for the language it was recorded in.
     *
     * The owner's discovery ruling makes the previewer's pack arrive unasked, and the one thing
     * that must never do is undo a decision the user already made. A DELETE is such a decision —
     * the user freed the space on purpose — so it writes this flag, and the auto-fetch is over
     * for good for that language. The MANUAL path is untouched: the Settings row still installs
     * on a tap, which is the only way back and the only way that asks.
     *
     * ### Why the key carries the language, and why now
     *
     * *"So a user can have multiple languages loaded onto their app."* The store is a SET, so a
     * "no" has to be one too: deleting the Spanish pack must not stop English arriving, and must
     * not let the foreground top-up re-download Spanish on the next launch. A GLOBAL flag
     * persisted by 4.4.1 and re-keyed in a later release would need a migration for every user
     * who set it — and getting that migration wrong means a 73 MB fetch of a model somebody
     * deleted on purpose. Nothing has shipped with the global key, so this is a re-key, not a
     * migration.
     *
     * ONE flag for BOTH gestures, still, and per language for both: dismissing the card and
     * deleting the model are the same sentence from the user ("not this one"). The third thing
     * that silences the card — the announcement retiring itself — is [livePreviewArmedOnce],
     * deliberately separate and deliberately not per language.
     *
     * NOT a StateFlow: the card reads it once per foreground into a local mirror, the house
     * convention for plain-var prefs read in composition ([cloudNoteDismissed] is the precedent
     * and the same card shape).
     */
    fun livePreviewDeclined(languageCode: String): Boolean =
        readLivePreviewDeclined(languageCode, prefs::getBoolean)

    fun setLivePreviewDeclined(languageCode: String, declined: Boolean) {
        prefs.edit().putBoolean(livePreviewDeclinedKey(languageCode), declined).apply()
    }

    /**
     * 4.4.1 — THE USER HAS SEEN LIVE WORDS. Read only by Home's card, which stops announcing the
     * feature after it (CONTROLLER RULING 2026-09-11, CHANGE 4).
     *
     * **THIS KDOC IS THE ONE HOME FOR WHEN IT IS WRITTEN, AND THE WRITE IS `onOpen`'s** (4.5.0 T4
     * fix round 2, review r2's B2: it went on naming *"the previewer gate's own call site"* for a
     * round after that call site stopped writing it — and a reader who trusted it would have
     * restored the defect by moving the write back). Written once, from inside
     * `FloatingBubbleService`'s `onOpen`, guarded by the previewer gate's own answer for that
     * session: **the gate's answer is NECESSARY and it is NOT SUFFICIENT.** `localPreviewArms` has
     * no tier term, so on a device with no speech model and no configured provider it ARMS — and
     * that session then dies at connect with no word ever rendered
     * ([com.whispereverywhere.transcription.stream.PreviewUnreachable]'s KDoc is the one home for
     * why). Writing this flag where the gate answered marked such a user as having watched live
     * words appear; the flag is global and permanent, so the day they installed a speech model
     * *"Live words are on"* was suppressed forever, for exactly the reader the announcement exists
     * for. `onOpen` is the first instant a word can have appeared: the bubble reaches `RECORDING`
     * and the capture thread's startup ring begins draining into the tee.
     *
     * The brief asked for a ONE-TIME announcement — *"then it stops appearing"* — and that was
     * true only via the card's X, which is also [livePreviewDeclined], the permanent no. So a
     * 4.4.0 user who already had the pack had to choose between being told about live words on
     * every single open and declining the feature for good. Once a session has OPENED with the
     * previewer armed the user has watched the words appear, and announcing them is noise.
     *
     * DELIBERATELY NOT the declined flag and deliberately NOT per language: "I have seen this" is
     * not "I do not want this", and having seen live words once in any language is a fact about
     * the user, not about a pack. It suppresses the ANNOUNCEMENT only — never the offer, never
     * the working card — which `PreviewAutoFetchTest` holds by reading it under `installed` alone.
     */
    var livePreviewArmedOnce: Boolean
        get() = prefs.getBoolean(KEY_LIVE_PREVIEW_ARMED_ONCE, false)
        set(value) {
            prefs.edit().putBoolean(KEY_LIVE_PREVIEW_ARMED_ONCE, value).apply()
        }

    /**
     * 4.4.1 — WHEN THE LAST AUTO-FETCH FAILED, as `System.currentTimeMillis()`; 0 = never, which
     * is what an absent pref reads as and what
     * [com.whispereverywhere.transcription.stream.PreviewAutoFetch.backedOff] treats as "no
     * back-off".
     *
     * The once-per-launch latch is in memory and dies with the process, so without this a user
     * reopening the app on a bad connection would pay for a failing 73 MB transfer once per
     * process start, all afternoon. Persisted rather than in-memory for exactly that reason.
     */
    var livePreviewAutoFetchFailedAt: Long
        get() = prefs.getLong(KEY_LIVE_PREVIEW_AUTOFETCH_FAILED_AT, 0L)
        set(value) {
            prefs.edit().putLong(KEY_LIVE_PREVIEW_AUTOFETCH_FAILED_AT, value).apply()
        }

    /**
     * Which engine READS ALOUD. null = on-device Kokoro (the default and the shipped behaviour, the
     * regression contract). A [ProviderId] NAME selects a cloud voice with local Kokoro as the
     * one-way fallback — parallel to [sttProviderId]. Distinct from [ttsVoiceId], which stays the
     * Kokoro speaker id used for the on-device voice AND the fallback.
     */
    private val _ttsProviderId = MutableStateFlow(prefs.getString(KEY_TTS_PROVIDER_ID, null))
    /** Reactive mirror of [ttsProviderId] for the Read-aloud card's voice chip. Additive. */
    val ttsProviderIdFlow: StateFlow<String?> = _ttsProviderId.asStateFlow()

    var ttsProviderId: String?
        get() = _ttsProviderId.value
        set(value) {
            if (value == null) prefs.edit().remove(KEY_TTS_PROVIDER_ID).apply()
            else prefs.edit().putString(KEY_TTS_PROVIDER_ID, value).apply()
            _ttsProviderId.value = value
        }

    /**
     * The chosen cloud voice for [id], namespaced per provider ([ttsCloudVoiceKey]) so OpenAI's
     * selection can never be read as Gemini's — a single shared key (or the Kokoro [ttsVoiceId]:Int)
     * cannot carry a provider voice string. null = none chosen for that provider yet.
     */
    fun ttsCloudVoiceId(id: ProviderId): String? = prefs.getString(ttsCloudVoiceKey(id), null)

    /** Set (or, with null/blank, clear) [id]'s cloud voice. Clearing keeps the store tidy on key removal. */
    fun setTtsCloudVoiceId(id: ProviderId, voiceId: String?) {
        if (voiceId.isNullOrBlank()) prefs.edit().remove(ttsCloudVoiceKey(id)).apply()
        else prefs.edit().putString(ttsCloudVoiceKey(id), voiceId).apply()
    }

    companion object {
        /**
         * "Keep bubble always on screen" for a FRESH install: off (4.8.0, owner ruling
         * 2026-09-17). Was `true` from the setting's birth through 4.7.0. The flip reaches only
         * installs that have never stored the key AND never finished onboarding — see
         * [bubbleAlwaysOnBackfill] for why an upgrade keeps what it had, and why a fresh install
         * has this value WRITTEN rather than merely read.
         */
        const val BUBBLE_ALWAYS_ON_DEFAULT: Boolean = false

        /**
         * "Detect speakers": ON for everyone (4.10, spec §7 item 1). Named rather than written as
         * a literal inside the `getBoolean` call so the ruling has one home a test can assert
         * against — see [detectSpeakers] for why ON is safe.
         *
         * No backfill, and it needs none: unlike [BUBBLE_ALWAYS_ON_DEFAULT] this setting has never
         * shipped, so there is no install anywhere with a different expectation of it.
         */
        const val DETECT_SPEAKERS_DEFAULT: Boolean = true

        /**
         * "Speaker labels in copied and saved text": OFF (4.10, spec §2). The clipboard and the
         * saved file are what leaves the app; see [speakerLabelsInExport].
         */
        const val SPEAKER_LABELS_IN_EXPORT_DEFAULT: Boolean = false

        /**
         * THE DEFAULT-FLIP RULE, pure: what to write under the always-on key ONCE, at construction,
         * before the flow reads it — or `null` to write nothing.
         *
         *  - A stored value: nothing to do; the user's (or a previous backfill's) choice stands.
         *  - No stored value and onboarding COMPLETED: an existing install that never touched
         *    the toggle. It has been living in always-on (the pre-4.8 default), so `true` is
         *    persisted — the default is a promise to new users, and an existing user must not
         *    find the bubble gone after an update.
         *  - No stored value and onboarding NOT completed: a fresh install. The new default is
         *    persisted, so the install carries a stored value from its first construction on.
         *
         * **Why the fresh install is written and not left to the read's fallback** (4.8.0 review,
         * findings 6-8). `PreferencesManager` is built once per process. A fresh install's first
         * construction sees no stored value and onboarding not completed; the user then finishes
         * onboarding in that same process, which sets `onboarding_completed` and touches nothing
         * else here (the only other writer of this key is the Settings toggle). Its SECOND
         * construction — the next process start, which Android brings about routinely — would
         * then see no stored value and onboarding completed: the second bullet, byte for byte,
         * and the new user would be flipped to always-on for good with the toggle showing ON as
         * if they had chosen it. Writing the default down at the first construction is what lets
         * the second one tell the two apart. Nothing reads the key's presence except this rule,
         * so a stored `false` has no user-visible meaning beyond its value.
         *
         * Onboarding completion is the existing-install signal because it is the one flag every
         * pre-4.8 install that reached a bubble has set, and no fresh install has AT ITS FIRST
         * CONSTRUCTION — which, with the write above, is the only construction that asks. A fresh
         * install that is interrupted mid-onboarding and later resumed already holds its `false`
         * and gets the new default, which is right.
         *
         * Tested in `PreferencesBubbleAlwaysOnTest` over all four inputs AND over the
         * two-construction sequence (fresh → onboarding completes → built again → still off);
         * the wiring (that `init` asks this before the flow is built) is pinned there as source.
         */
        fun bubbleAlwaysOnBackfill(hasStoredValue: Boolean, onboardingCompleted: Boolean): Boolean? =
            when {
                hasStoredValue -> null
                onboardingCompleted -> true
                else -> BUBBLE_ALWAYS_ON_DEFAULT
            }

        private const val KEY_API_KEY = "openai_api_key"
        private const val KEY_LEGACY_PURGED = "legacy_credential_stores_purged_v1"
        private const val KEY_VIBRATION_ENABLED = "vibration_enabled"
        private const val KEY_BUBBLE_ENABLED = "bubble_enabled"
        private const val KEY_BUBBLE_ALWAYS_ON = "bubble_always_on"
        // RETIRED preference keys — a value stored under any of these is simply ignored, and the
        // name is never reused for a new preference (an old value would come back as a surprise):
        //   "dictation_first_keyboard" — the dictation-first keyboard (owner UX 2026-08-01),
        //     removed by owner ruling 2026-09-17 ("we're not gonna need it").
        private const val KEY_PREFER_DEVICE_AUDIO = "prefer_device_audio"
        private const val KEY_BUBBLE_X = "bubble_x"
        private const val KEY_BUBBLE_Y = "bubble_y"
        private const val KEY_BUBBLE_TEXT_WIDTH_DP = "bubble_text_width_dp"
        private const val KEY_BUBBLE_TEXT_HEIGHT_DP = "bubble_text_height_dp"
        private const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"
        private const val KEY_SELECTED_MODEL_ID = "selected_model_id"
        private const val KEY_SELECTED_LANGUAGE = "selected_language"
        private const val KEY_OVERLAY_PINNED = "overlay_pinned"
        private const val KEY_CLOUD_NOTE_DISMISSED = "cloud_note_dismissed"
        private const val KEY_TTS_SPEED = "tts_speed"
        private const val KEY_TTS_VOICE_ID = "tts_voice_id"
        private const val KEY_GPU_MULTI_EXPERIMENT = "gpu_multilingual_experiment"
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
         * v1 is deliberately left in place rather than migrated: an unset later version re-prompts.
         *
         * Bumped v2 -> v3 for the cloud read-aloud release. The MEANING changed again: selected
         * read-aloud TEXT now leaves the device (a NEW data class) when a cloud voice is chosen, so
         * the disclosure gains "text you select for read-aloud is also sent." Per the same rule that
         * drove v1 -> v2, the version bumps on the meaning change; v2 stays in the store and an unset
         * v3 re-prompts everyone who accepted the audio-only v2.
         */
        /**
         * The CURRENT disclosure version. Bumped in lockstep with [CLOUD_DISCLOSURE_KEY] whenever
         * the disclosure's MEANING changes (see the history above). Kept as its own constant so a
         * test can assert the key still carries this version — a meaning edit that forgets to bump
         * either one breaks that assertion and forces a deliberate decision.
         */
        internal const val CLOUD_DISCLOSURE_VERSION = 3
        internal const val CLOUD_DISCLOSURE_KEY = "cloud_disclosure_accepted_v3"

        /**
         * The one production read of cloud-disclosure consent, as a pure function of a
         * `getBoolean(key, default)` accessor so it is unit-testable without a Context. Two
         * invariants the entire cloud triad rests on live here: the default is **false** (an unset
         * store never authorizes egress), and the read is scoped to [CLOUD_DISCLOSURE_KEY] — a value
         * written under a PRIOR version key does not satisfy it, so a meaning change that bumps the
         * version re-prompts everyone instead of silently inheriting stale consent.
         */
        internal fun readCloudDisclosureAccepted(getBoolean: (String, Boolean) -> Boolean): Boolean =
            getBoolean(CLOUD_DISCLOSURE_KEY, false)

        /**
         * The device-local store's file (4.15) — see `deviceLocal`. Deliberately NOT
         * `whisper_everywhere_prefs`, and deliberately absent from both backup allowlists.
         */
        internal const val DEVICE_LOCAL_PREFS = "whisper_device_local"
        internal const val KEY_NPU_REDOWNLOAD_TIER = "npu_redownload_tier"
        internal const val KEY_NPU_REDOWNLOAD_CENSUS = "npu_redownload_census"
        internal const val KEY_NPU_REFRESH_NOTIFIED_CENSUS = "npu_refresh_notified_census"

        /**
         * The one production read of the re-download record, as a pure function of a
         * `getString(key, default)` accessor so a map can stand in for the store — the
         * [readCloudDisclosureAccepted] seam, for the same reason. A record is BOTH halves or
         * nothing: a tier with no census key (or the reverse) is a torn write, and a torn write
         * must read as "no event" rather than as an event nobody can name.
         */
        internal fun readNpuRedownload(getString: (String, String?) -> String?): NpuRedownload? {
            val tier = getString(KEY_NPU_REDOWNLOAD_TIER, null) ?: return null
            val census = getString(KEY_NPU_REDOWNLOAD_CENSUS, null) ?: return null
            return NpuRedownload(tier, census)
        }

        /** The driver check's stored verdict (P2) — device-local, like the re-download record. */
        internal const val KEY_NPU_APU_VERDICT = "npu_apu_verdict"

        /**
         * The one production read of the stored driver verdict, as a pure function of a
         * `getString(key, default)` accessor — the [readNpuRedownload] seam, so a map stands in for
         * the store in the round-trip test. Anything `NpuApuVerdict.decode` refuses reads as no
         * record, which is the safe answer: probe again.
         */
        internal fun readNpuApuVerdict(getString: (String, String?) -> String?): NpuApuVerdict? =
            NpuApuVerdict.decode(getString(KEY_NPU_APU_VERDICT, null))
        private const val KEY_STT_PROVIDER = "stt_provider_id"
        private const val KEY_STT_LIVE_MODE = "stt_live_mode"
        /** Gemini's own live flag (4.3.4, default on); the shared key above never applies to Gemini. */
        private const val KEY_STT_LIVE_MODE_GEMINI = "stt_live_mode_gemini"
        /** The previewer's switch (4.4.0, R3: default on). Read in exactly one place. */
        private const val KEY_LOCAL_PREVIEW_ENABLED = "local_preview_enabled"
        private const val KEY_DETECT_SPEAKERS = "detect_speakers"
        private const val KEY_SPEAKER_LABELS_IN_EXPORT = "speaker_labels_in_export"
        // (4.5.1 Task 2) The bubble's three user-owned presentation facts. Int keys: two ARGB
        // colours and one percent. Every rule about the VALUES lives in BubbleColours.
        private const val KEY_BUBBLE_LIVE_COLOUR = "bubble_live_colour"
        private const val KEY_BUBBLE_COMMITTED_COLOUR = "bubble_committed_colour"
        private const val KEY_BUBBLE_OPACITY_PERCENT = "bubble_opacity_percent"
        /**
         * The previewer's per-language "the user said no" store (4.4.1 acquisition amendment).
         * A PREFIX, not a key: the language code is the rest of it, so one language's decision
         * can never be another's. See [livePreviewDeclined].
         */
        private const val KEY_LIVE_PREVIEW_DECLINED_PREFIX = "live_preview_declined_"

        /**
         * The stored key for one language's refusal. Spelled once, and `internal` so the test
         * that holds the per-language property can name the same key the writer uses rather than
         * a retyped copy of it.
         */
        internal fun livePreviewDeclinedKey(languageCode: String): String =
            KEY_LIVE_PREVIEW_DECLINED_PREFIX + languageCode

        /**
         * The one production read of a language's refusal, as a pure function of a
         * `getBoolean(key, default)` accessor so it is unit-testable without a Context — the
         * [readCloudDisclosureAccepted] seam, for the same reason. The default is **false**: an
         * unset store has declined nothing, and shipped the other way round the feature would
         * silently never arrive for anyone.
         */
        internal fun readLivePreviewDeclined(
            languageCode: String,
            getBoolean: (String, Boolean) -> Boolean,
        ): Boolean = getBoolean(livePreviewDeclinedKey(languageCode), false)

        private const val KEY_LIVE_PREVIEW_ARMED_ONCE = "live_preview_armed_once"
        private const val KEY_LIVE_PREVIEW_AUTOFETCH_FAILED_AT = "live_preview_autofetch_failed_at"
        private const val KEY_TTS_PROVIDER_ID = "tts_provider_id"

        /**
         * The picker's own display name for a language code, or null for a code it does not
         * offer — the ONE place a code becomes a word (4.4.1 acquisition amendment).
         *
         * The previewer's copy is parameterised by language, so something has to turn `"en"` into
         * "English", and [SUPPORTED_LANGUAGES] is already the app's single answer to that: the
         * Settings picker renders from it, the onboarding step's rows are a permutation of it
         * ([com.whispereverywhere.ui.onboarding.OnboardingLogic.languageRows]), and a second
         * table would let a card and a picker name the same language differently.
         */
        fun languageDisplayName(code: String): String? =
            SUPPORTED_LANGUAGES.firstOrNull { it.first == code }?.second

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
