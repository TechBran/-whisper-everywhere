package com.whispereverywhere.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderCatalogTest {

    @Test fun all_three_providers_are_present() {
        assertEquals(
            listOf(ProviderId.OPENAI, ProviderId.GEMINI, ProviderId.ELEVENLABS),
            ProviderCatalog.all.map { it.id },
        )
    }

    @Test fun openai_uses_a_bearer_authorization_header() {
        val p = ProviderCatalog.byId(ProviderId.OPENAI)
        assertEquals("Authorization", p.authHeaderName)
        assertEquals("Bearer sk-test", p.authHeaderValue("sk-test"))
    }

    @Test fun gemini_uses_the_goog_api_key_header_not_bearer() {
        val p = ProviderCatalog.byId(ProviderId.GEMINI)
        assertEquals("x-goog-api-key", p.authHeaderName)
        assertEquals("k123", p.authHeaderValue("k123"))
    }

    @Test fun elevenlabs_uses_xi_api_key_and_is_not_a_bearer_scheme() {
        // Sending "Bearer <key>" to ElevenLabs 401s. This is the most commonly got-wrong header
        // of the three, so it is pinned.
        val p = ProviderCatalog.byId(ProviderId.ELEVENLABS)
        assertEquals("xi-api-key", p.authHeaderName)
        assertEquals("k123", p.authHeaderValue("k123"))
        assertFalse(p.authHeaderValue("k123").startsWith("Bearer"))
    }

    @Test fun gemini_does_not_support_streaming() {
        // Not a preference: the Live API is preview, session-capped, and wants ephemeral tokens
        // from a backend this app does not have. The UI must not offer streaming for Gemini.
        assertFalse(ProviderCatalog.byId(ProviderId.GEMINI).supportsStreaming)
        assertTrue(ProviderCatalog.byId(ProviderId.OPENAI).supportsStreaming)
        assertTrue(ProviderCatalog.byId(ProviderId.ELEVENLABS).supportsStreaming)
    }

    @Test fun every_provider_supports_both_modalities() {
        ProviderCatalog.all.forEach {
            assertTrue("${it.id} STT", it.supportsStt)
            assertTrue("${it.id} TTS", it.supportsTts)
        }
    }

    @Test fun only_gemini_trains_on_data_by_default() {
        // Drives per-provider disclosure copy. A generic "your data goes to a third party" line
        // would be materially inaccurate for OpenAI, which retains nothing and trains on nothing.
        assertTrue(ProviderCatalog.byId(ProviderId.GEMINI).trainsOnDataByDefault)
        assertFalse(ProviderCatalog.byId(ProviderId.OPENAI).trainsOnDataByDefault)
    }

    @Test fun every_url_is_https() {
        // A credential must never travel over cleartext.
        ProviderCatalog.all.forEach {
            assertTrue("${it.id} validationUrl", it.validationUrl.startsWith("https://"))
            assertTrue("${it.id} keyHelpUrl", it.keyHelpUrl.startsWith("https://"))
        }
    }

    @Test fun ids_are_stable_by_name_not_ordinal() {
        // Storage keys off enum NAME. Reordering the enum must never repoint a user's saved
        // credential at a different provider.
        assertEquals("OPENAI", ProviderId.OPENAI.name)
        assertEquals("GEMINI", ProviderId.GEMINI.name)
        assertEquals("ELEVENLABS", ProviderId.ELEVENLABS.name)
    }
}
