package com.whispereverywhere.ui.screens

import com.whispereverywhere.provider.KeyStatus
import com.whispereverywhere.provider.ProviderCatalog
import com.whispereverywhere.provider.ProviderId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    // --- looksLikeInvalidKey: suppresses "Save anyway" for a key already known to be bad ---

    @Test fun recognizes_geminis_api_key_invalid_marker() {
        assertTrue(looksLikeInvalidKey("HTTP 400: ...\"reason\":\"API_KEY_INVALID\"..."))
    }

    @Test fun recognizes_the_lowercase_invalid_api_key_marker() {
        assertTrue(looksLikeInvalidKey("HTTP 401: {\"error\":\"invalid_api_key\"}"))
    }

    @Test fun recognizes_the_api_key_not_valid_marker_case_insensitively() {
        assertTrue(looksLikeInvalidKey("api key not valid, please check it"))
    }

    @Test fun an_unrelated_detail_does_not_look_like_an_invalid_key() {
        assertFalse(looksLikeInvalidKey("HTTP 403: unsupported_country_region_territory"))
        assertFalse(looksLikeInvalidKey("HTTP 500: boom"))
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

    @Test fun elevenlabs_rejection_mentions_key_restrictions_not_just_a_bad_paste() {
        // ElevenLabs is the only one of the three with per-endpoint API-key restrictions, so a
        // 401 there is at least as likely to be a scoping problem as a typo. Blaming the paste
        // sends a user with a correctly locked-down key off to regenerate it. Observed in the
        // field 2026-07-28 against the old /v1/user endpoint.
        val msg = statusMessage(KeyStatus.Invalid, "ElevenLabs", ProviderId.ELEVENLABS)
        assertTrue(msg, msg.contains("restricted"))
        assertTrue(msg, msg.contains("ElevenLabs dashboard"))
    }

    @Test fun other_providers_keep_the_plain_rejection_copy() {
        // OpenAI and Gemini have no per-endpoint key scoping, so the scoping hint would be noise.
        assertEquals(
            "That key was rejected. Check you copied all of it.",
            statusMessage(KeyStatus.Invalid, "OpenAI", ProviderId.OPENAI),
        )
        assertEquals(
            "That key was rejected. Check you copied all of it.",
            statusMessage(KeyStatus.Invalid, "Google Gemini", ProviderId.GEMINI),
        )
    }

    @Test fun elevenlabs_validation_url_is_the_lower_privilege_voices_endpoint() {
        // /v1/user requires `user_read`, which a key scoped for speech work will not have —
        // a valid key 401s. /v1/voices still rejects a bad key (probed 2026-07-28) at a lower
        // privilege, and is what the voice picker will need anyway.
        assertEquals(
            "https://api.elevenlabs.io/v1/voices",
            ProviderCatalog.byId(ProviderId.ELEVENLABS).validationUrl,
        )
    }

    // --- sttSelectionCaption: names the provider, explains the fallback, never claims speed ---

    @Test fun stt_selection_caption_names_the_provider_and_explains_the_fallback() {
        assertEquals(
            "Audio is sent to OpenAI. If it fails, the on-device model takes over.",
            sttSelectionCaption("OpenAI"),
        )
    }

    @Test fun stt_selection_caption_never_makes_a_speed_claim() {
        // Measured on-device: a typical 3 s utterance transcribes locally in 1.1-1.3 s, so cloud
        // is roughly a tie at best. The copy must say what happens, not that it is faster.
        val caption = sttSelectionCaption("OpenAI")
        listOf("faster", "quicker", "speed", "instant", "quick").forEach { word ->
            assertFalse(caption, caption.contains(word, ignoreCase = true))
        }
    }

    // --- STT_CAPABLE_PROVIDERS: C2a ships only the OpenAI adapter; Gemini/ElevenLabs are C2b ---

    @Test fun only_openai_is_stt_capable_in_this_release() {
        assertEquals(setOf(ProviderId.OPENAI), STT_CAPABLE_PROVIDERS)
    }

    // --- cloudDisclosureMainText / cloudDisclosureOffUntilText (Release C2a Task 7): the
    // disclosure modal must speak in PRESENT tense now that audio actually leaves the device,
    // must require the explicit engine-selection step (not just adding a key), must say what
    // happens on provider failure, and must never claim speed. ---

    @Test fun cloud_disclosure_main_text_is_present_tense_not_future() {
        val text = cloudDisclosureMainText()
        assertTrue(text, text.contains("is sent"))
        assertFalse(text, text.contains("future update"))
        assertFalse(text, text.contains("will also be sent"))
        assertFalse(text, text.contains("will be sent"))
    }

    @Test fun cloud_disclosure_main_text_requires_selecting_the_engine_not_just_adding_a_key() {
        // A stored key alone sends nothing per-utterance (only a one-time verification call);
        // audio only flows once the provider is also selected as the transcription engine.
        val text = cloudDisclosureMainText()
        assertTrue(text, text.contains("select", ignoreCase = true))
    }

    @Test fun cloud_disclosure_main_text_mentions_the_on_device_fallback_on_provider_failure() {
        val text = cloudDisclosureMainText()
        assertTrue(text, text.contains("on-device"))
    }

    @Test fun cloud_disclosure_main_text_never_makes_a_speed_claim() {
        // Measured on-device: a typical 3 s utterance transcribes locally in 1.1-1.3 s, so cloud
        // is roughly a tie at best. The copy must say what happens, not that it is faster.
        val text = cloudDisclosureMainText()
        listOf("faster", "quicker", "speed", "instant", "quick").forEach { word ->
            assertFalse(text, text.contains(word, ignoreCase = true))
        }
    }

    @Test fun cloud_disclosure_main_text_does_not_overclaim_read_aloud_is_sent() {
        // Cloud read-aloud has no adapter in this release (TtsController is untouched) — the
        // modal must not claim text-you-select-for-read-aloud is transmitted anywhere yet.
        val text = cloudDisclosureMainText()
        assertFalse(text, text.contains("read-aloud"))
    }

    @Test fun cloud_disclosure_off_until_text_names_both_gating_steps() {
        val text = cloudDisclosureOffUntilText()
        assertTrue(text, text.contains("add a key"))
        assertTrue(text, text.contains("select"))
    }

    // ---- selectionAfterKeyRemoval: a selection must never outlive its own credential ----

    @Test fun removing_the_selected_providers_key_returns_the_app_to_on_device() {
        assertNull(selectionAfterKeyRemoval(ProviderId.OPENAI.name, ProviderId.OPENAI))
    }

    @Test fun removing_a_different_providers_key_leaves_the_selection_alone() {
        // Removing an unused ElevenLabs key must not silently switch a working OpenAI user back
        // to on-device.
        assertEquals(
            ProviderId.OPENAI.name,
            selectionAfterKeyRemoval(ProviderId.OPENAI.name, ProviderId.ELEVENLABS),
        )
    }

    @Test fun removing_a_key_while_already_on_device_stays_on_device() {
        assertNull(selectionAfterKeyRemoval(null, ProviderId.OPENAI))
    }

    @Test fun every_provider_can_deselect_itself() {
        // Guards the comparison being by enum NAME rather than by ordinal or display name: a
        // mismatch here would leave cloud selected with no key, which decideEngineChoice reports
        // as "no key saved" forever.
        ProviderId.entries.forEach { id ->
            assertNull(id.name, selectionAfterKeyRemoval(id.name, id))
        }
    }
}
