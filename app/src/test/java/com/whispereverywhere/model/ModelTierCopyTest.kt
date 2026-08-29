package com.whispereverywhere.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelTierCopyTest {

    // The discipline that would have prevented the Bengali review: nobody can add or reword an
    // offered tier without stating a size, a speed-vs-accuracy position, and language coverage.

    /**
     * Every tier a chooser can render — [WhisperCatalog.pickable] PLUS the gated 4.0 `npu`, which
     * gate-passing devices see and which `pickable` therefore cannot reach.
     *
     * The census loops below iterate THIS list, not `pickable`: the copy rules are properties of a
     * card the user reads, and the gate decides whether the card renders, not whether the rules
     * apply. Iterating `pickable` would have let npu's copy say anything at all with the suite
     * still green.
     */
    private val offeredTiers = WhisperCatalog.entries.filter { !it.retired }

    @Test fun every_offered_tier_has_copy() {
        offeredTiers.forEach { model ->
            assertNotNull("no copy for offered tier '${model.id}'", ModelTierCopy.forId(model.id))
        }
    }

    @Test fun every_tier_states_its_size_as_a_badge() {
        offeredTiers.forEach { model ->
            val copy = ModelTierCopy.forId(model.id)!!
            assertTrue(
                "tier '${model.id}' has no size badge",
                copy.badges.any { it.endsWith(" MB") },
            )
        }
    }

    @Test fun the_size_badge_tells_the_truth_about_the_download() {
        // 60 MB tiers say 60, 190 MB tiers say 190 — the badge must track approxBytes. For a
        // PAIRED tier that is the sum of both files, which is what the user actually downloads.
        offeredTiers.forEach { model ->
            val copy = ModelTierCopy.forId(model.id)!!
            val expectedMb = (model.approxBytes / 1_000_000L).toInt()
            val statedMb = copy.badges.first { it.endsWith(" MB") }.removeSuffix(" MB").toInt()
            assertTrue(
                "tier '${model.id}' badge says $statedMb MB but the download is ~$expectedMb MB",
                kotlin.math.abs(statedMb - expectedMb) <= 5,
            )
        }
    }

    @Test fun every_tier_takes_a_speed_vs_accuracy_position() {
        val positionWords = POSITION_WORDS
        offeredTiers.forEach { model ->
            val copy = ModelTierCopy.forId(model.id)!!
            val all = (copy.headline + " " + copy.body).lowercase()
            assertTrue(
                "tier '${model.id}' copy takes no speed-vs-accuracy position",
                positionWords.any { all.contains(it) },
            )
        }
    }

    @Test fun language_coverage_is_a_badge_matching_the_catalog_scope() {
        // Coverage renders as a badge — visually impossible to miss. "English only" on every
        // ENGLISH tier; "90+ languages" on every MULTILINGUAL tier.
        offeredTiers.forEach { model ->
            val copy = ModelTierCopy.forId(model.id)!!
            when (model.scope) {
                ModelScope.ENGLISH -> assertTrue(
                    "ENGLISH tier '${model.id}' lacks the 'English only' badge",
                    copy.badges.contains("English only"),
                )
                ModelScope.MULTILINGUAL -> assertTrue(
                    "MULTILINGUAL tier '${model.id}' lacks the '90+ languages' badge",
                    copy.badges.contains("90+ languages"),
                )
            }
        }
    }

    @Test fun the_owner_approved_headlines_are_pinned_exactly() {
        assertEquals("Best English accuracy", ModelTierCopy.forId("pro")!!.headline)
        assertEquals("Best multilingual accuracy", ModelTierCopy.forId("multi")!!.headline)
    }

    @Test fun retired_and_unknown_tiers_have_no_copy() {
        // Retired tiers stay resolvable in WhisperCatalog but are not offered — no copy required.
        assertNull(ModelTierCopy.forId("extreme"))
        assertNull(ModelTierCopy.forId("ultra"))
        // 3.7 Workstream H: the 60 MB tiers joined them.
        assertNull(ModelTierCopy.forId("eco"))
        assertNull(ModelTierCopy.forId("base"))
        assertNull(ModelTierCopy.forId("nope"))
    }

    @Test fun no_offered_tier_names_a_retired_one() {
        // "Noticeably slower than Eco" was true and is now a dangling reference to a card the
        // user can no longer see. Copy may not describe a tier by comparison to a dead one.
        // The match is WORD-ANCHORED (H3 review, m1): retired ids are short common substrings —
        // "record"/"recording" contains "eco", "based"/"database" contains "base" — so a plain
        // `contains` fails ordinary dictation copy while naming a reference that is not there.
        val retiredIds = WhisperCatalog.entries.filter { it.retired }.map { it.id.lowercase() }
        offeredTiers.forEach { model ->
            val copy = ModelTierCopy.forId(model.id)!!
            val all = (copy.headline + " " + copy.body + " " + copy.badges.joinToString(" ")).lowercase()
            retiredIds.forEach { r ->
                assertFalse(
                    "tier '${model.id}' copy names retired tier '$r'",
                    Regex("\\b" + Regex.escape(r) + "\\b").containsMatchIn(all),
                )
            }
        }
    }

    @Test fun english_locales_are_steered_to_pro() {
        assertEquals("pro", ModelTierCopy.steerIdForLanguageTag("en"))
        assertEquals("pro", ModelTierCopy.steerIdForLanguageTag("en-US"))
        assertEquals("pro", ModelTierCopy.steerIdForLanguageTag("en_GB"))
        assertEquals("pro", ModelTierCopy.steerIdForLanguageTag("EN-au"))
    }

    @Test fun every_other_locale_is_steered_to_multi() {
        // The Bengali review is the reason this rule exists at all: an English-only tier must
        // never be the thing a non-English speaker lands on by default.
        assertEquals("multi", ModelTierCopy.steerIdForLanguageTag("bn"))
        assertEquals("multi", ModelTierCopy.steerIdForLanguageTag("bn-BD"))
        assertEquals("multi", ModelTierCopy.steerIdForLanguageTag("fr-CA"))
        assertEquals("multi", ModelTierCopy.steerIdForLanguageTag("zh-Hans-CN"))
        assertEquals("multi", ModelTierCopy.steerIdForLanguageTag(""))
    }

    @Test fun the_steer_always_lands_on_a_pickable_tier_of_the_right_scope() {
        val pickableIds = WhisperCatalog.pickable.map { it.id }
        listOf("en-US", "bn-BD", "de", "").forEach { tag ->
            val id = ModelTierCopy.steerIdForLanguageTag(tag)
            assertTrue("steer '$id' for '$tag' is not pickable", pickableIds.contains(id))
        }
        assertEquals(ModelScope.ENGLISH, WhisperCatalog.byId(ModelTierCopy.steerIdForLanguageTag("en"))!!.scope)
        assertEquals(ModelScope.MULTILINGUAL, WhisperCatalog.byId(ModelTierCopy.steerIdForLanguageTag("bn"))!!.scope)
    }

    @Test fun the_steer_badge_is_pinned_exactly_and_claims_nothing_about_speed() {
        assertEquals("Best match for your language", ModelTierCopy.STEER_BADGE)
        listOf("faster", "fastest", "quicker", "instant").forEach {
            assertFalse(ModelTierCopy.STEER_BADGE.lowercase().contains(it))
        }
    }

    @Test fun the_steered_tier_is_offered_first() {
        assertEquals(listOf("pro", "multi"), ModelTierCopy.orderedForLanguageTag("en-US"))
        assertEquals(listOf("multi", "pro"), ModelTierCopy.orderedForLanguageTag("bn-BD"))
    }

    @Test fun the_order_is_always_a_permutation_of_the_pickable_catalog() {
        // A future tier that nobody remembered to mention here must still reach the chooser —
        // dropping one silently would make it undownloadable.
        val pickable = WhisperCatalog.pickable.map { it.id }
        listOf("en", "bn", "de-AT", "").forEach { tag ->
            val ordered = ModelTierCopy.orderedForLanguageTag(tag)
            assertEquals("'$tag' lost or duplicated a tier", pickable.size, ordered.size)
            assertEquals("'$tag' is not a permutation", pickable.toSet(), ordered.toSet())
        }
    }

    @Test fun every_ordered_id_resolves_and_has_copy() {
        ModelTierCopy.orderedForLanguageTag("en").forEach {
            assertNotNull(WhisperCatalog.byId(it))
            assertNotNull(ModelTierCopy.forId(it))
        }
    }

    // ------------------------------------------------------------ 4.0: steering the gated tier
    //
    // The 3.7 rules above answer "which tier for this language". These four answer "...and does
    // this device have a faster way to run it" — without letting the answer to the second
    // question change the answer to the first.

    @Test fun the_gated_tier_is_the_steer_only_where_the_device_runs_it_and_the_locale_needs_it() {
        // Capable device, and a language `multi` was already the right answer for: the same model
        // on the Hexagon is a strictly better version of the same answer.
        assertEquals("npu", ModelTierCopy.steerIdForLanguageTagFor("bn-BD", true))
        assertEquals("npu", ModelTierCopy.steerIdForLanguageTagFor("zh-Hans-CN", true))
        assertEquals("npu", ModelTierCopy.steerIdForLanguageTagFor("fr-CA", true))
        assertEquals("npu", ModelTierCopy.steerIdForLanguageTagFor("", true))
        // An ENGLISH locale keeps `pro` however fast the silicon is. Steering an English speaker
        // onto a multilingual tier because the device is capable is the Bengali review mirrored:
        // it trades the accuracy they came for against a speed they never asked about.
        assertEquals("pro", ModelTierCopy.steerIdForLanguageTagFor("en", true))
        assertEquals("pro", ModelTierCopy.steerIdForLanguageTagFor("en-US", true))
        assertEquals("pro", ModelTierCopy.steerIdForLanguageTagFor("EN-au", true))
        // Gate says no: 3.7's answer, unchanged, for every locale.
        assertEquals("multi", ModelTierCopy.steerIdForLanguageTagFor("bn-BD", false))
        assertEquals("pro", ModelTierCopy.steerIdForLanguageTagFor("en-US", false))
    }

    @Test fun the_gated_tier_leads_the_lineup_without_promoting_the_english_only_tier() {
        // The second ordering key, stated as the assertion it exists for: `multi` stays ahead of
        // `pro` for a non-English user. A one-key sort reads [npu, pro, multi] here and promotes
        // the English-only tier above the multilingual one 3.7 demoted it below — by a change
        // that was supposed to be about silicon.
        assertEquals(
            listOf("npu", "multi", "pro"),
            ModelTierCopy.orderedForLanguageTagFor("bn-BD", true),
        )
        // English locale on a capable device: `pro` leads, and the gated tier is LAST rather than
        // absent. The user can still reach it; they are simply not pushed at it.
        assertEquals(
            listOf("pro", "multi", "npu"),
            ModelTierCopy.orderedForLanguageTagFor("en-US", true),
        )
        assertEquals(listOf("multi", "pro"), ModelTierCopy.orderedForLanguageTagFor("bn-BD", false))
        assertEquals(listOf("pro", "multi"), ModelTierCopy.orderedForLanguageTagFor("en-US", false))
    }

    @Test fun the_lineup_is_a_permutation_of_this_devices_pickable_set_and_the_steer_leads_it() {
        // ORDER, not presence — the rule this branch has now paid for four times. Both chooser
        // surfaces make TWO calls: one for the cards, one for the badge and the highlight. If the
        // two ever disagree, the lineup leads with one card while "Best match for your language"
        // sits on another: every element present, every element in the wrong relationship to the
        // others, and nothing in the type system to notice.
        listOf("en", "en-US", "bn", "bn-BD", "de-AT", "zh-Hans-CN", "").forEach { tag ->
            listOf(true, false).forEach { npuAvailable ->
                val expected = WhisperCatalog.pickableFor(npuAvailable).map { it.id }
                val ordered = ModelTierCopy.orderedForLanguageTagFor(tag, npuAvailable)
                assertEquals(
                    "'$tag'/$npuAvailable lost or duplicated a tier",
                    expected.size,
                    ordered.size,
                )
                assertEquals(
                    "'$tag'/$npuAvailable is not a permutation of what this device can pick",
                    expected.toSet(),
                    ordered.toSet(),
                )
                assertEquals(
                    "'$tag'/$npuAvailable: the badged tier is not the one the lineup leads with",
                    ModelTierCopy.steerIdForLanguageTagFor(tag, npuAvailable),
                    ordered.first(),
                )
                ordered.forEach {
                    assertNotNull("ordered id '$it' does not resolve", WhisperCatalog.byId(it))
                    assertNotNull("ordered id '$it' has no card copy", ModelTierCopy.forId(it))
                }
            }
        }
    }

    @Test fun a_device_that_failed_the_gate_never_sees_the_gated_tier_at_all() {
        listOf("en", "en-US", "en_GB", "bn", "bn-BD", "de-AT", "zh-Hans-CN", "fr-CA", "").forEach { tag ->
            val ordered = ModelTierCopy.orderedForLanguageTagFor(tag, npuAvailable = false)
            assertFalse(
                "'$tag': the gated tier reached a device whose gate said no",
                ordered.contains("npu"),
            )
            assertEquals("'$tag': the ungated lineup is 3.7's two tiers", 2, ordered.size)
            assertTrue(
                "'$tag': the ungated steer is not a tier this device can pick",
                WhisperCatalog.pickable.map { it.id }
                    .contains(ModelTierCopy.steerIdForLanguageTagFor(tag, false)),
            )
            // The 3.7 spelling still answers identically: the new overload is the same rule with
            // one more input, not a second rule free to drift from it.
            assertEquals("'$tag': the two spellings disagree", ordered, ModelTierCopy.orderedForLanguageTag(tag))
        }
        // Being steered to is a position in a list and a chip. It is not selection, and nothing
        // about the gate moves the catalog default or lets a gated tier into `pickable`.
        assertEquals("pro", WhisperCatalog.DEFAULT_MODEL_ID)
        assertFalse(WhisperCatalog.pickable.map { it.id }.contains("npu"))
    }

    // ---------------------------------------------------------------- the 4.0 gated tier
    //
    // npu is the first tier the census loops above could not have reached through `pickable`, so
    // 3.7's discipline is restated here explicitly against the owner-approved strings.

    @Test fun the_npu_headline_is_pinned_exactly_and_takes_a_position() {
        val copy = ModelTierCopy.forId("npu")!!
        assertEquals("Fastest multilingual", copy.headline)
        // The position is stated where the eye lands first, not buried in the body.
        assertTrue(
            "the npu headline takes no speed-vs-accuracy position",
            POSITION_WORDS.any { copy.headline.lowercase().contains(it) },
        )
    }

    @Test fun the_npu_badges_state_coverage_and_the_size_of_the_whole_pair() {
        val copy = ModelTierCopy.forId("npu")!!
        assertEquals(listOf("90+ languages", "358 MB"), copy.badges)
        // Not "English only" and not a bespoke wording: the SAME string every multilingual tier
        // carries, so the two cards are comparable at a glance.
        assertTrue(copy.badges.contains("90+ languages"))
        // 358 MB is the PAIR (encoder + decoder). A future edit that badges only the encoder's
        // 132 MB — or a catalog edit that changes the pair — fires here.
        val npu = WhisperCatalog.byId("npu")!!
        val statedMb = copy.badges.first { it.endsWith(" MB") }.removeSuffix(" MB").toInt()
        val expectedMb = (npu.approxBytes / 1_000_000L).toInt()
        assertTrue(
            "npu badge says $statedMb MB but the install is ~$expectedMb MB",
            kotlin.math.abs(statedMb - expectedMb) <= 5,
        )
        assertTrue("the badge must state the pair, not just the encoder", statedMb > npu.primaryBytes / 1_000_000L)
    }

    @Test fun no_offered_tiers_copy_compares_this_app_to_another_one() {
        // The claim rules: our own before/after is fair game, another product is not — nobody has
        // measured one. Universal, so it is a loop; npu is simply the first tier whose copy had a
        // reason to reach for a comparison at all.
        val crossApp = listOf(
            "other app", "any app", "every app", "any other", "than other", "competitor",
            "gboard", "google", "apple", "siri", "otter", "dragon", "whisperkit",
        )
        offeredTiers.forEach { model ->
            val copy = ModelTierCopy.forId(model.id)!!
            val all = (copy.headline + " " + copy.body + " " + copy.badges.joinToString(" ")).lowercase()
            crossApp.forEach { needle ->
                assertFalse("tier '${model.id}' copy compares this app to '$needle'", all.contains(needle))
            }
        }
    }

    @Test fun the_npu_body_is_our_own_tier_on_this_device_and_claims_no_absolute() {
        val copy = ModelTierCopy.forId("npu")!!
        assertEquals(
            "Runs on your phone's AI chip. Same model as Multilingual, much faster on this device.",
            copy.body,
        )
        // The comparison is OUR tier, and the claim is scoped to the hardware in the user's hand —
        // the two things that make "much faster" a statement someone could check.
        assertTrue(copy.body.contains("Multilingual"))
        assertTrue(copy.body.contains("this device"))
        // No absolutes, anywhere in the offered lineup. "Fastest multilingual" positions npu
        // within OUR lineup; "instant" or "real-time" would be a claim about the world.
        val absolutes = listOf(
            "instant", "real-time", "realtime", "no delay", "no lag", "zero lag",
            "guaranteed", "always", "never", "unlimited",
        )
        offeredTiers.forEach { model ->
            val c = ModelTierCopy.forId(model.id)!!
            val all = (c.headline + " " + c.body + " " + c.badges.joinToString(" ")).lowercase()
            absolutes.forEach { needle ->
                assertFalse("tier '${model.id}' copy makes the absolute claim '$needle'", all.contains(needle))
            }
        }
    }

    private companion object {
        /** The 3.7 census's position vocabulary, shared so the npu pin cannot drift from the loop. */
        val POSITION_WORDS = listOf("fastest", "fast", "slower", "accuracy")
    }
}
