package com.whispereverywhere.ui.screens

import com.whispereverywhere.provider.KeyStatus
import com.whispereverywhere.provider.ProviderCatalog
import com.whispereverywhere.provider.ProviderId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
        // OpenAI, Gemini, and Soniox have no per-endpoint key scoping, so the scoping hint would be
        // noise — only ElevenLabs gets the restricted-key variant.
        assertEquals(
            "That key was rejected. Check you copied all of it.",
            statusMessage(KeyStatus.Invalid, "OpenAI", ProviderId.OPENAI),
        )
        assertEquals(
            "That key was rejected. Check you copied all of it.",
            statusMessage(KeyStatus.Invalid, "Google Gemini", ProviderId.GEMINI),
        )
        assertEquals(
            "That key was rejected. Check you copied all of it.",
            statusMessage(KeyStatus.Invalid, "Soniox", ProviderId.SONIOX),
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

    // --- STT_CAPABLE_PROVIDERS: C2b shipped Gemini + ElevenLabs; Soniox adds a 4th ---

    @Test fun stt_capable_set_includes_soniox() {
        assertEquals(
            setOf(ProviderId.OPENAI, ProviderId.GEMINI, ProviderId.ELEVENLABS, ProviderId.SONIOX),
            STT_CAPABLE_PROVIDERS,
        )
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

    @Test fun cloud_disclosure_main_text_discloses_read_aloud_text_is_sent() {
        // Task 7 (v3): cloud read-aloud shipped in Task 5/6 — TtsController now has a real cloud
        // path, so selected read-aloud TEXT is a NEW data class leaving the device. The disclosure
        // meaning changed (MF3 rule), the flag bumped v2 -> v3, and this modal must say so.
        val text = cloudDisclosureMainText()
        assertTrue(text, text.contains("read aloud"))
        assertTrue(text, text.contains("text you select"))
    }

    @Test fun cloud_disclosure_read_aloud_clause_asserts_TRANSMISSION_not_just_vocabulary() {
        // Regression-hardening (review finding): the two vocabulary checks above pass even for a
        // REVERSED disclosure such as "the text you select for read aloud stays on device" — both
        // phrases survive, and the global present-tense guard (contains "is sent") is already
        // satisfied by the STT sentence alone, so nothing forced the read-aloud clause itself to
        // assert egress. Pin the MEANING: the read-aloud sentence must say the selected text is
        // ALSO sent to the provider. "is also sent" is absent from any on-device reversal.
        val text = cloudDisclosureMainText()
        assertTrue(text, text.contains("is also sent"))

        // Stronger still: isolate the sentence that talks about read-aloud and require BOTH the
        // subject ("text you select") and the transmission verb ("sent") to co-occur INSIDE it, so
        // a rewrite that keeps the phrases but moves "sent" to an unrelated clause cannot pass.
        val readAloudSentence = text.split('.', '!', '?')
            .firstOrNull { it.contains("read aloud", ignoreCase = true) }
        assertNotNull("no sentence mentions read aloud: $text", readAloudSentence)
        readAloudSentence!!
        assertTrue(readAloudSentence, readAloudSentence.contains("text you select", ignoreCase = true))
        assertTrue(readAloudSentence, readAloudSentence.contains("sent", ignoreCase = true))
        // And that same sentence must not smuggle in an on-device reassurance that would negate it.
        assertFalse(readAloudSentence, readAloudSentence.contains("stays on device", ignoreCase = true))
        assertFalse(readAloudSentence, readAloudSentence.contains("never leaves", ignoreCase = true))
    }

    @Test fun cloud_disclosure_main_text_still_makes_no_speed_claim_after_read_aloud_sentence() {
        // Explicit guard: the new read-aloud sentence must not smuggle in a speed claim even
        // though it is describing the TTS path, where "fast" language would be easy to slip in.
        val text = cloudDisclosureMainText()
        listOf("faster", "quicker", "speed", "instant", "quick").forEach { word ->
            assertFalse(text, text.contains(word, ignoreCase = true))
        }
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

    // The SAME rule governs the read-aloud (TTS) engine selection, which onKeyRemoved now clears
    // through this very function — a cloud VOICE selection sends selected TEXT off-device, so it
    // must not outlive its credential any more than the STT audio selection does. (The stored
    // per-provider voice is dropped unconditionally alongside; that is a pref side-effect the
    // Compose closure performs, verified by compile + review, but the engine-clear decision is
    // this pure witness.)

    @Test fun removing_the_selected_read_aloud_providers_key_returns_to_on_device_voice() {
        assertNull(selectionAfterKeyRemoval(ProviderId.ELEVENLABS.name, ProviderId.ELEVENLABS))
        assertNull(selectionAfterKeyRemoval(ProviderId.OPENAI.name, ProviderId.OPENAI))
    }

    @Test fun removing_a_different_providers_key_leaves_the_read_aloud_selection_alone() {
        // A user reading aloud through OpenAI who removes an unused Gemini key keeps their voice.
        assertEquals(
            ProviderId.OPENAI.name,
            selectionAfterKeyRemoval(ProviderId.OPENAI.name, ProviderId.GEMINI),
        )
    }

    // ---- sttSelectableProviders: selecting is what starts transmission, so it needs consent ----

    @Test fun a_configured_provider_is_offered_once_the_disclosure_is_accepted() {
        val offered = sttSelectableProviders(setOf(ProviderId.OPENAI), disclosureAccepted = true)
        assertEquals(listOf(ProviderId.OPENAI), offered.map { it.id })
    }

    @Test fun no_provider_is_offered_before_the_disclosure_is_accepted() {
        // The upgrade case: a user holding only C1's future-tense acceptance has a stored key but
        // has never been told audio is sent NOW. Selecting must be unreachable for them.
        assertTrue(sttSelectableProviders(setOf(ProviderId.OPENAI), disclosureAccepted = false).isEmpty())
    }

    @Test fun a_provider_with_no_key_is_never_offered_even_after_acceptance() {
        assertTrue(sttSelectableProviders(emptySet(), disclosureAccepted = true).isEmpty())
    }

    // (Removed) `a_configured_provider_with_no_stt_adapter_is_not_offered`: C2b shipped the Gemini
    // and ElevenLabs adapters, and Soniox's adapter shipped too — so all four catalog providers are
    // now STT-capable, leaving no configured-but-adapter-less provider to exclude. That
    // STT_CAPABLE_PROVIDERS now equals the full catalog is pinned by stt_capable_set_includes_soniox
    // above, and that each configured member IS offered is covered by
    // a_configured_provider_is_offered_once_the_disclosure_is_accepted.

    @Test fun every_provider_can_deselect_itself() {
        // Guards the comparison being by enum NAME rather than by ordinal or display name: a
        // mismatch here would leave cloud selected with no key, which decideEngineChoice reports
        // as "no key saved" forever.
        ProviderId.entries.forEach { id ->
            assertNull(id.name, selectionAfterKeyRemoval(id.name, id))
        }
    }

    // ---- live-mode selector row: per streaming-capable provider, price-bearing, no speed claim ----

    @Test fun live_mode_row_visible_for_any_streaming_capable_provider_with_key_and_disclosure() {
        assertTrue(
            liveModeRowVisible(
                selectedProviderId = ProviderId.OPENAI.name,
                configured = setOf(ProviderId.OPENAI),
                disclosureAccepted = true,
            ),
        )
        assertTrue(
            liveModeRowVisible(
                selectedProviderId = ProviderId.ELEVENLABS.name,
                configured = setOf(ProviderId.ELEVENLABS),
                disclosureAccepted = true,
            ),
        )
        assertTrue(
            liveModeRowVisible(
                selectedProviderId = ProviderId.SONIOX.name,
                configured = setOf(ProviderId.SONIOX),
                disclosureAccepted = true,
            ),
        )
    }

    @Test fun live_mode_row_hidden_for_gemini_which_cannot_stream() {
        // Gemini has no client-usable realtime path (its Live API wants ephemeral backend-minted
        // tokens this app has no server for) — a provider limitation, not a defect.
        assertFalse(liveModeRowVisible(ProviderId.GEMINI.name, setOf(ProviderId.GEMINI), true))
    }

    @Test fun live_mode_row_hidden_without_disclosure_key_or_active_selection() {
        // No v3 disclosure -> hidden: live adds a cost tier, not a new consent surface, so it
        // stays behind the SAME gate that lets audio leave at all.
        assertFalse(liveModeRowVisible(ProviderId.OPENAI.name, setOf(ProviderId.OPENAI), false))
        // Key gone -> hidden: a live toggle must not outlive the credential it would stream with.
        assertFalse(liveModeRowVisible(ProviderId.OPENAI.name, emptySet(), true))
        // OpenAI configured but on-device is the selected engine -> hidden: the row is a
        // sub-option of transcribing THROUGH the selected provider, not a standalone switch.
        assertFalse(liveModeRowVisible(null, setOf(ProviderId.OPENAI), true))
        // A garbage/unresolvable selection -> hidden, never throws.
        assertFalse(liveModeRowVisible("not-a-real-provider", setOf(ProviderId.OPENAI), true))
    }

    @Test fun live_mode_label_surfaces_the_price_where_the_mode_is_chosen() {
        assertTrue(liveModeLabel(ProviderId.OPENAI), liveModeLabel(ProviderId.OPENAI).contains("\$0.017/min"))
        assertTrue(
            liveModeLabel(ProviderId.ELEVENLABS),
            liveModeLabel(ProviderId.ELEVENLABS).contains("\$0.007/min"),
        )
        assertTrue(liveModeLabel(ProviderId.SONIOX), liveModeLabel(ProviderId.SONIOX).contains("\$0.002/min"))
    }

    @Test fun live_mode_label_names_the_selected_provider() {
        assertTrue(liveModeLabel(ProviderId.OPENAI).contains("OpenAI"))
        assertTrue(liveModeLabel(ProviderId.ELEVENLABS).contains("ElevenLabs"))
        assertTrue(liveModeLabel(ProviderId.SONIOX).contains("Soniox"))
    }

    @Test fun live_mode_copy_says_word_for_word_and_never_claims_speed() {
        // The mode is honest about WHAT it does (word-for-word), never that it is faster —
        // measured on-device transcription is a tie at best, so a speed claim would be a lie.
        // Pins ALL THREE streaming-capable providers, Soniox included: it is the cheapest of the
        // three, but the copy never says "fastest"/"cheapest" either.
        listOf(ProviderId.OPENAI, ProviderId.ELEVENLABS, ProviderId.SONIOX).forEach { id ->
            val text = liveModeLabel(id) + " " + liveModeCaption()
            assertTrue(text, text.contains("word-for-word", ignoreCase = true))
            listOf("faster", "quicker", "speed", "instant", "quick", "fastest", "cheapest").forEach { word ->
                assertFalse(text, text.contains(word, ignoreCase = true))
            }
        }
    }

    @Test fun live_mode_caption_states_the_honest_continuous_billing_model() {
        // Continuous streaming keeps the mic open and bills the whole open-mic time including silence.
        // The copy must say so plainly — and still make no speed claim.
        val caption = liveModeCaption()
        assertTrue(caption, caption.contains("billed per minute while the mic is open"))
        listOf("faster", "quicker", "speed", "instant", "quick", "fastest", "cheapest").forEach { word ->
            assertFalse(caption, caption.contains(word, ignoreCase = true))
        }
    }
}
