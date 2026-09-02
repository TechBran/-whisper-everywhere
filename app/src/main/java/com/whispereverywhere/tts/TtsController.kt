package com.whispereverywhere.tts

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.whispereverywhere.audio.AudioArbiter
import com.whispereverywhere.data.local.PreferencesManager
import com.whispereverywhere.net.OkHttpTransport
import com.whispereverywhere.provider.ProviderCatalog
import com.whispereverywhere.provider.ProviderId
import com.whispereverywhere.transcription.cloud.FatalKind
import com.whispereverywhere.tts.cloud.TtsError
import com.whispereverywhere.tts.cloud.TtsProviderFactory

/**
 * Process-wide mediator for read-aloud: owns the single TtsEngine, registers the speech side
 * of the AudioArbiter, and gives triggers (PROCESS_TEXT toolbar, bubble tap) one entry point.
 */
object TtsController {

    @Volatile private var engine: TtsEngine? = null

    // Shared HTTP transport for cloud synthesis, created lazily (only if a cloud voice is ever
    // selected) and reused — same lifecycle shape as FloatingBubbleService.sharedTransport().
    @Volatile private var httpTransport: OkHttpTransport? = null

    // At most one toast per latched fatal kind (reset each time the cloud selection is re-resolved,
    // i.e. per read). Non-fatal fall-backs never set this — they stay silent.
    @Volatile private var notifiedTtsFatal: FatalKind? = null

    private val main = Handler(Looper.getMainLooper())

    fun engine(context: Context): TtsEngine {
        engine?.let { return it }
        synchronized(this) {
            engine?.let { return it }
            val app = context.applicationContext
            val e = TtsEngine(app, TtsModelManager(app))
            AudioArbiter.isSpeaking = { e.isSpeaking() }
            AudioArbiter.stopSpeech = { e.stop() }
            engine = e
            return e
        }
    }

    fun isVoiceInstalled(context: Context): Boolean =
        TtsModelManager(context.applicationContext).isInstalled()

    /** Warm the model (e.g. when a clipboard copy makes a read-aloud likely). No-op if not installed. */
    fun preload(context: Context) {
        engine(context).preload()
    }

    /**
     * The single "our TTS is audible" authority: true while OUR read-aloud is playing (or
     * synthesizing toward playback), whatever the trigger — bubble tap, speaker lobe, or the
     * PROCESS_TEXT toolbar ([SpeakTextActivity]). Media detection consults this so a read is
     * never classified as transcribable media; pre-fix only service-driven reads were covered
     * and a toolbar read summoned the bubble with a transcribe prompt over its own voice.
     *
     * Delegates to the engine's generation-guarded speaking flag, which EVERY entry point
     * funnels through: [speakFromTrigger] → [TtsEngine.speak] sets it synchronously before
     * returning; it clears on completion and on error (the speak task's finally), and instantly
     * on [stop] — so media detection can never be permanently suppressed. A null engine (no
     * read ever started in this process) is simply "not speaking".
     */
    fun isSpeechActive(): Boolean = engine?.isSpeaking() == true

    /**
     * Speak [text] from any trigger. Handles the not-installed and capture-in-progress cases
     * with user-visible feedback instead of silence.
     *
     * Returns true iff the engine's `speak` ran and accepted the text; false on every bail, so
     * the caller can undo any speaking state it set optimistically.
     */
    fun speakFromTrigger(context: Context, text: String, onDone: () -> Unit = {}): Boolean {
        val app = context.applicationContext
        if (!isVoiceInstalled(app)) {
            Toast.makeText(
                app,
                "Download the read-aloud voice in Whisper Everywhere settings first.",
                Toast.LENGTH_LONG,
            ).show()
            return false
        }
        if (!AudioArbiter.requestSpeak()) {
            Toast.makeText(
                app,
                "Finishing your transcription — tap again in a moment.",
                Toast.LENGTH_SHORT,
            ).show()
            return false
        }
        val e = engine(app)
        runCatching {
            val prefs = (app as com.whispereverywhere.WhisperEverywhereApp).preferencesManager
            e.speed = prefs.ttsSpeed
            e.speakerId = prefs.ttsVoiceId
            applyCloudVoice(app, e, prefs)
        }
        return e.speak(text, onDone)
    }

    /**
     * Resolve the user's read-aloud engine selection into the [TtsEngine]'s cloud fields, or clear
     * them for the shipped on-device path. Clears FIRST so a stale cloud provider from a previous
     * read can never leak into a local read: cloud is used only when the provider resolves, a voice
     * is chosen for it, AND its key is stored — the same two-deliberate-actions gate as STT (the UI
     * additionally gates SELECTING a cloud voice on disclosure v3). null cloud fields ⇒ the engine
     * takes the exact prior local path (the regression contract).
     */
    private fun applyCloudVoice(app: Context, e: TtsEngine, prefs: PreferencesManager) {
        e.cloudProvider = null
        e.cloudVoiceId = null
        e.onCloudFallback = null
        e.onCloudSoftLatch = null
        notifiedTtsFatal = null

        val providerId = resolveTtsProvider(prefs.ttsProviderId) ?: return
        val voiceId = prefs.ttsCloudVoiceId(providerId)
        if (voiceId.isNullOrBlank()) return
        val key = prefs.providerAccounts.key(providerId)
        if (key.isNullOrBlank()) return

        e.cloudProvider = TtsProviderFactory.create(providerId, sharedTransport(), key, app)
        e.cloudVoiceId = voiceId
        e.onCloudFallback = { err -> onCloudFallback(app, err) }
        e.onCloudSoftLatch = { onCloudSoftLatch(app) }
    }

    /**
     * Called (on the engine's executor thread) ONCE per read when cloud has soft-latched to local
     * after repeated non-fatal failures — the circuit breaker that stops a persistent transient
     * (a Gemini no-audio refusal, a plain 429) silently re-billing every remaining clause. The read
     * already continued on the on-device voice; this makes the otherwise-invisible bleed visible.
     */
    private fun onCloudSoftLatch(context: Context) {
        main.post {
            Toast.makeText(
                context.applicationContext,
                "Cloud read-aloud kept failing — using the on-device voice for the rest.",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    /**
     * Called (on the engine's executor thread) after a cloud unit has ALREADY fallen back to the
     * local voice — reading never stopped. This only INFORMS the user, and only for a FATAL latch,
     * at most once per read. Non-fatal fall-backs (a single re-synthesized clause) stay silent,
     * matching the STT degradation philosophy. Reuses FloatingBubbleService's one-toast-per-latch
     * contract, adapted to read-aloud copy.
     */
    private fun onCloudFallback(context: Context, err: TtsError) {
        if (err is TtsError.Fatal && notifiedTtsFatal != err.kind) {
            notifiedTtsFatal = err.kind
            val msg = "${err.message} — used the on-device voice instead"
            main.post {
                Toast.makeText(context.applicationContext, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun sharedTransport(): OkHttpTransport =
        httpTransport ?: OkHttpTransport().also { httpTransport = it }

    fun stop() {
        engine?.stop()
    }
}

/**
 * Resolve the stored [PreferencesManager.ttsProviderId] to a cloud read-aloud [ProviderId], or null
 * for on-device Kokoro. null / a foreign name / a stale value all resolve to null — a build that
 * predates a provider's adapter can never route read-aloud text to it. Mirrors
 * [com.whispereverywhere.service.resolveSttProvider]; gated on
 * [ProviderCatalog.TTS_CAPABLE_PROVIDERS], so a non-null result is always constructible by
 * [TtsProviderFactory].
 */
internal fun resolveTtsProvider(raw: String?): ProviderId? =
    raw?.let { runCatching { ProviderId.valueOf(it) }.getOrNull() }
        ?.takeIf { it in ProviderCatalog.TTS_CAPABLE_PROVIDERS }

/**
 * The per-provider SharedPreferences key holding a stored cloud voice, namespaced by enum NAME
 * (never ordinal) so a reordered [ProviderId] can never repoint one provider's chosen voice at
 * another's — and so a provider voice string never collides with the Kokoro [PreferencesManager.ttsVoiceId]:Int.
 */
internal fun ttsCloudVoiceKey(id: ProviderId): String = "tts_cloud_voice_${id.name}"
