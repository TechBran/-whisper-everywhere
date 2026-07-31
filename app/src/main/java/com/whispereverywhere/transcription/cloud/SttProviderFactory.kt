package com.whispereverywhere.transcription.cloud

import com.whispereverywhere.net.HttpTransport
import com.whispereverywhere.provider.ProviderId

/**
 * The ONE place a [ProviderId] becomes an [SttProvider]. Both the live service
 * ([com.whispereverywhere.service.FloatingBubbleService]) and the batch service
 * ([com.whispereverywhere.service.BatchTranscriptionService]) route construction through here — the
 * full STT set for both as of 3.3.0 — so a new adapter is wired in exactly once.
 *
 * The `when` is exhaustive over [ProviderId]: a provider added to the enum will not compile until it
 * is mapped to an adapter here (or explicitly rejected), which is precisely the safety we want — a
 * new provider can never be silently selectable with no adapter behind it.
 */
object SttProviderFactory {
    fun create(id: ProviderId, transport: HttpTransport, apiKey: String): SttProvider = when (id) {
        ProviderId.OPENAI -> OpenAiStt(transport, apiKey)
        ProviderId.GEMINI -> GeminiStt(transport, apiKey)
        ProviderId.ELEVENLABS -> ElevenLabsStt(transport, apiKey)
        ProviderId.SONIOX -> SonioxStt(transport, apiKey)
    }
}
