package com.whispereverywhere.tts.cloud

import android.content.Context
import com.whispereverywhere.net.HttpTransport
import com.whispereverywhere.provider.ProviderId

/**
 * The ONE place a [ProviderId] becomes a [TtsProvider] — the read-aloud mirror of
 * [com.whispereverywhere.transcription.cloud.SttProviderFactory]. The [TtsController] resolves the
 * user's cloud-voice selection and routes construction through here, so a new adapter is wired in
 * exactly once.
 *
 * The `when` is exhaustive over [ProviderId]: a provider added to the enum will not compile until it
 * is mapped to an adapter here — precisely the safety we want, so a provider can never be silently
 * selectable with no TTS adapter behind it. It stays in lockstep with
 * [com.whispereverywhere.provider.ProviderCatalog.TTS_CAPABLE_PROVIDERS].
 *
 * [context] is threaded only because [ElevenLabsTts]'s mp3-tier fallback needs [Context.getCacheDir]
 * for its temp file; OpenAI, Gemini, and Soniox need nothing from it (all emit headerless PCM with
 * no decode tier). It is nullable so the three context-free adapters stay JVM-unit-testable and
 * because [ElevenLabsTts] already tolerates a null context off the on-device decode path (the
 * default decode lambda requireNotNull-checks it only when invoked).
 */
object TtsProviderFactory {
    fun create(
        id: ProviderId,
        transport: HttpTransport,
        apiKey: String,
        context: Context?,
    ): TtsProvider = when (id) {
        ProviderId.OPENAI -> OpenAiTts(transport, apiKey)
        ProviderId.GEMINI -> GeminiTts(transport, apiKey)
        ProviderId.ELEVENLABS -> ElevenLabsTts(transport, apiKey, context?.applicationContext)
        // Soniox emits headerless pcm_s16le @ 24 kHz — no mp3 tier, so context is deliberately NOT passed.
        ProviderId.SONIOX -> SonioxTts(transport, apiKey)
    }
}
