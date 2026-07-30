package com.whispereverywhere.service

import com.whispereverywhere.net.FakeHttpTransport
import com.whispereverywhere.net.HttpResult
import com.whispereverywhere.provider.ProviderId
import com.whispereverywhere.transcription.cloud.SttProviderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for the pure (Context-free) decision logic FloatingBubbleService uses to pick a
 * transcription engine. Kept free of Android/Service state so it runs as a plain JVM test — the
 * Service itself cannot be unit-tested without Robolectric, but the branch table that decides
 * local vs. cloud can and must be, since it IS the one-way valve the brief calls out: cloud must
 * be reachable ONLY when a provider has been explicitly selected.
 */
class EngineSelectionTest {

    // --- decideEngineChoice: the one-way valve ---

    @Test fun no_provider_selected_is_always_local_regardless_of_key_or_network() {
        assertEquals(
            EngineChoice.LOCAL_ONLY,
            decideEngineChoice(sttProviderId = null, hasKey = true, hasValidatedNetwork = true),
        )
        assertEquals(
            EngineChoice.LOCAL_ONLY,
            decideEngineChoice(sttProviderId = null, hasKey = false, hasValidatedNetwork = false),
        )
    }

    @Test fun provider_selected_but_no_key_falls_back_local() {
        assertEquals(
            EngineChoice.LOCAL_NO_KEY,
            decideEngineChoice(sttProviderId = "OPENAI", hasKey = false, hasValidatedNetwork = true),
        )
    }

    @Test fun provider_and_key_but_no_validated_network_falls_back_local() {
        assertEquals(
            EngineChoice.LOCAL_OFFLINE,
            decideEngineChoice(sttProviderId = "OPENAI", hasKey = true, hasValidatedNetwork = false),
        )
    }

    @Test fun provider_key_and_validated_network_selects_cloud_with_fallback() {
        assertEquals(
            EngineChoice.CLOUD_WITH_FALLBACK,
            decideEngineChoice(sttProviderId = "OPENAI", hasKey = true, hasValidatedNetwork = true),
        )
    }

    @Test fun missing_key_and_missing_network_together_still_report_the_key_as_the_reason() {
        // Branch ORDER matters here: hasKey is checked before hasValidatedNetwork, so when both
        // are false the more actionable "no key" message is what the user sees, not "offline".
        assertEquals(
            EngineChoice.LOCAL_NO_KEY,
            decideEngineChoice(sttProviderId = "OPENAI", hasKey = false, hasValidatedNetwork = false),
        )
    }

    // --- decideEngineChoice: the C4 live-streaming branch, gated AFTER the local valve ---

    @Test fun live_flag_with_openai_and_key_and_net_gives_CLOUD_LIVE() {
        assertEquals(
            EngineChoice.CLOUD_LIVE,
            decideEngineChoice(
                sttProviderId = "OPENAI", hasKey = true, hasValidatedNetwork = true, liveMode = true,
            ),
        )
    }

    @Test fun live_flag_without_key_still_falls_to_LOCAL_NO_KEY() {
        // The live check sits AFTER the local guards: a dead key resolves to on-device, NEVER a
        // socket. Live must never bypass the one-way valve batch already obeys.
        assertEquals(
            EngineChoice.LOCAL_NO_KEY,
            decideEngineChoice(
                sttProviderId = "OPENAI", hasKey = false, hasValidatedNetwork = true, liveMode = true,
            ),
        )
    }

    @Test fun live_flag_offline_gives_LOCAL_OFFLINE() {
        // Same valve order: no validated network is on-device, not a socket that cannot open.
        assertEquals(
            EngineChoice.LOCAL_OFFLINE,
            decideEngineChoice(
                sttProviderId = "OPENAI", hasKey = true, hasValidatedNetwork = false, liveMode = true,
            ),
        )
    }

    @Test fun live_flag_with_non_openai_provider_stays_CLOUD_WITH_FALLBACK() {
        // Only OpenAI streams (BYOK Realtime WS); Gemini and ElevenLabs cannot, so the flag is
        // inert for them and the batch-POST path is unchanged.
        assertEquals(
            EngineChoice.CLOUD_WITH_FALLBACK,
            decideEngineChoice(
                sttProviderId = "GEMINI", hasKey = true, hasValidatedNetwork = true, liveMode = true,
            ),
        )
        assertEquals(
            EngineChoice.CLOUD_WITH_FALLBACK,
            decideEngineChoice(
                sttProviderId = "ELEVENLABS", hasKey = true, hasValidatedNetwork = true, liveMode = true,
            ),
        )
    }

    @Test fun live_flag_OFF_with_openai_stays_batch_so_the_default_path_is_unchanged() {
        // Additive-only guarantee: OpenAI + key + net with the flag off is the shipped batch path.
        assertEquals(
            EngineChoice.CLOUD_WITH_FALLBACK,
            decideEngineChoice(
                sttProviderId = "OPENAI", hasKey = true, hasValidatedNetwork = true, liveMode = false,
            ),
        )
    }

    // --- resolveSttProvider: C2b widens live mode to OpenAI, Gemini, and ElevenLabs ---

    @Test fun resolves_openai_by_its_stored_name() {
        assertEquals(ProviderId.OPENAI, resolveSttProvider("OPENAI"))
    }

    @Test fun resolves_gemini_and_elevenlabs_now_that_their_adapters_ship() {
        // C2b built the Gemini and ElevenLabs STT adapters, so their stored names now resolve to a
        // usable provider in live mode (both were null in C2a). SttProviderFactory maps each one.
        assertEquals(ProviderId.GEMINI, resolveSttProvider("GEMINI"))
        assertEquals(ProviderId.ELEVENLABS, resolveSttProvider("ELEVENLABS"))
    }

    @Test fun null_preference_resolves_to_null() {
        assertNull(resolveSttProvider(null))
    }

    @Test fun a_garbage_preference_value_resolves_to_null_without_throwing() {
        assertNull(resolveSttProvider("not-a-real-provider"))
    }

    // --- resolveBatchSttProvider: batch stays OpenAI-only this wave (C2b widens live mode only) ---

    @Test fun batch_resolves_openai_but_clamps_gemini_and_elevenlabs_to_null() {
        // resolveSttProvider now resolves all three, but batch's engineUsed decision was NOT
        // widened: a Gemini/ElevenLabs selection degrades to on-device (null) here rather than
        // mis-keying OpenAI with another provider's key.
        assertEquals(ProviderId.OPENAI, resolveBatchSttProvider("OPENAI"))
        assertNull(resolveBatchSttProvider("GEMINI"))
        assertNull(resolveBatchSttProvider("ELEVENLABS"))
        assertNull(resolveBatchSttProvider(null))
        assertNull(resolveBatchSttProvider("not-a-real-provider"))
    }

    // --- SttProviderFactory: the ONE ProviderId -> adapter construction point (both services) ---

    @Test fun factory_maps_each_provider_id_to_its_own_adapter() {
        val fake = FakeHttpTransport { _, _ -> HttpResult.Ok(200, "") }
        assertEquals(ProviderId.OPENAI, SttProviderFactory.create(ProviderId.OPENAI, fake, "k").id)
        assertEquals(ProviderId.GEMINI, SttProviderFactory.create(ProviderId.GEMINI, fake, "k").id)
        assertEquals(
            ProviderId.ELEVENLABS,
            SttProviderFactory.create(ProviderId.ELEVENLABS, fake, "k").id,
        )
    }
}
