package com.whispereverywhere.ui.screens

import com.whispereverywhere.provider.KeyStatus
import com.whispereverywhere.provider.ProviderCatalog
import com.whispereverywhere.provider.ProviderId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure (non-Composable) logic in CloudProvidersScreen.kt: key masking, the
 * KeyStatus -> user-facing message mapping, the save-or-not decision, and the per-provider
 * training-disclosure copy. Deliberately kept Compose-free so these run as plain JVM unit tests.
 */
class CloudProvidersScreenLogicTest {

    // --- maskedKeyPlaceholder: NEVER the raw key, only derived from its last 4 characters ---

    @Test fun masks_a_normal_length_key_to_its_last_4_characters() {
        assertEquals("••••test", maskedKeyPlaceholder("sk-test"))
    }

    @Test fun masks_a_long_key_to_only_its_last_4_characters() {
        assertEquals("••••1234", maskedKeyPlaceholder("sk-abcdefgh1234"))
    }

    @Test fun a_key_no_longer_than_4_characters_is_masked_entirely() {
        // Nothing beyond "length" should leak for a short key — nothing IS the last 4 chars.
        assertEquals("••••", maskedKeyPlaceholder("abcd"))
        assertEquals("••", maskedKeyPlaceholder("ab"))
    }

    @Test fun the_placeholder_never_contains_the_raw_key_body() {
        val key = "sk-supersecretvalue9999"
        val masked = maskedKeyPlaceholder(key)
        assertFalse(masked.contains(key.dropLast(4)))
    }

    // --- statusMessage: the exact copy specified for each KeyStatus ---

    @Test fun valid_status_message() {
        assertEquals("Key verified ✓", statusMessage(KeyStatus.Valid, "OpenAI"))
    }

    @Test fun invalid_status_message() {
        assertEquals(
            "That key was rejected. Check you copied all of it.",
            statusMessage(KeyStatus.Invalid, "OpenAI"),
        )
    }

    @Test fun no_credit_status_message() {
        assertEquals(
            "The key works, but the account has no credit.",
            statusMessage(KeyStatus.NoCredit, "OpenAI"),
        )
    }

    @Test fun rate_limited_status_message() {
        assertEquals(
            "Rate limited — try again in a moment.",
            statusMessage(KeyStatus.RateLimited, "OpenAI"),
        )
    }

    @Test fun offline_status_message_names_the_provider_and_never_says_rejected() {
        val message = statusMessage(KeyStatus.Offline, "ElevenLabs")
        assertEquals("Couldn't reach ElevenLabs. Check your connection.", message)
        assertFalse(message.contains("rejected"))
    }

    @Test fun unknown_status_message_carries_the_detail_and_offers_to_save() {
        assertEquals(
            "Couldn't verify (HTTP 500: boom). Save anyway?",
            statusMessage(KeyStatus.Unknown("HTTP 500: boom"), "OpenAI"),
        )
    }

    // --- shouldPersistKey: the two mappings the brief calls out as easy to get backwards ---

    @Test fun valid_should_persist() {
        assertTrue(shouldPersistKey(KeyStatus.Valid))
    }

    @Test fun no_credit_should_still_persist_because_the_key_is_genuinely_valid() {
        assertTrue(shouldPersistKey(KeyStatus.NoCredit))
    }

    @Test fun invalid_must_not_persist() {
        assertFalse(shouldPersistKey(KeyStatus.Invalid))
    }

    @Test fun rate_limited_must_not_persist() {
        assertFalse(shouldPersistKey(KeyStatus.RateLimited))
    }

    @Test fun offline_must_not_persist() {
        assertFalse(shouldPersistKey(KeyStatus.Offline))
    }

    @Test fun unknown_must_not_auto_persist_without_explicit_save_anyway() {
        assertFalse(shouldPersistKey(KeyStatus.Unknown("HTTP 500: boom")))
    }

    // --- providerTrainingDisclosure: per-provider copy, because one generic line would lie ---

    @Test fun openai_line_matches_its_trainsOnDataByDefault_false_flag() {
        val openai = ProviderCatalog.byId(ProviderId.OPENAI)
        assertFalse(openai.trainsOnDataByDefault)
        assertEquals(
            "OpenAI does not train on data sent through the API.",
            providerTrainingDisclosure(openai),
        )
    }

    @Test fun gemini_line_calls_out_the_free_tier_and_human_review() {
        val gemini = ProviderCatalog.byId(ProviderId.GEMINI)
        assertTrue(gemini.trainsOnDataByDefault)
        assertEquals(
            "Google's free tier uses what you send to improve its products, and human " +
                "reviewers may read it. Paid tiers do not.",
            providerTrainingDisclosure(gemini),
        )
    }

    @Test fun elevenlabs_line_calls_out_the_default_opt_out() {
        val elevenlabs = ProviderCatalog.byId(ProviderId.ELEVENLABS)
        assertTrue(elevenlabs.trainsOnDataByDefault)
        assertEquals(
            "ElevenLabs trains on API data by default; you can opt out in your " +
                "ElevenLabs account settings.",
            providerTrainingDisclosure(elevenlabs),
        )
    }

    @Test fun every_catalog_provider_has_a_training_disclosure_line() {
        // Guards against a silently-missing branch if a fourth provider is ever added.
        ProviderCatalog.all.forEach { provider ->
            assertTrue(providerTrainingDisclosure(provider).isNotBlank())
        }
    }
}
