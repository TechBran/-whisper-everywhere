package com.whispereverywhere.tts.cloud

import com.whispereverywhere.net.FakeHttpTransport
import com.whispereverywhere.provider.ProviderCatalog
import com.whispereverywhere.provider.ProviderId
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The ONE place a [ProviderId] becomes a [TtsProvider] — mirror of SttProviderFactory. The `when` is
 * exhaustive over [ProviderId], so a new provider will not compile until it is mapped here (or
 * rejected). These tests pin that each id maps to the adapter with the MATCHING id (a copy-paste
 * swap in the `when` is exactly what they catch). Context is null here: only the ElevenLabs mp3
 * fallback needs it, and only on device — construction never touches it.
 */
class TtsProviderFactoryTest {

    private val fake = FakeHttpTransport()

    @Test fun openai_id_maps_to_the_openai_adapter() {
        assertEquals(ProviderId.OPENAI, TtsProviderFactory.create(ProviderId.OPENAI, fake, "k", null).id)
    }

    @Test fun gemini_id_maps_to_the_gemini_adapter() {
        assertEquals(ProviderId.GEMINI, TtsProviderFactory.create(ProviderId.GEMINI, fake, "k", null).id)
    }

    @Test fun elevenlabs_id_maps_to_the_elevenlabs_adapter() {
        assertEquals(ProviderId.ELEVENLABS, TtsProviderFactory.create(ProviderId.ELEVENLABS, fake, "k", null).id)
    }

    @Test fun every_adapter_emits_the_24k_bank_contract_rate() {
        // Iterate the providers that actually HAVE a TTS adapter, not the whole enum: Soniox is
        // STT-only, so TtsProviderFactory.create rejects it — it is deliberately not a member here.
        ProviderCatalog.TTS_CAPABLE_PROVIDERS.forEach { id ->
            assertEquals("$id sampleRate", 24_000, TtsProviderFactory.create(id, fake, "k", null).sampleRate)
        }
    }

    @Test fun tts_capable_set_is_openai_gemini_elevenlabs() {
        assertEquals(
            setOf(ProviderId.OPENAI, ProviderId.GEMINI, ProviderId.ELEVENLABS),
            ProviderCatalog.TTS_CAPABLE_PROVIDERS,
        )
    }
}
