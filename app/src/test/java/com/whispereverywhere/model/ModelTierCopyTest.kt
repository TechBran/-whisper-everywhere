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

    // ------------------------------------------------------ 4.0/4.1: steering the gated tiers
    //
    // The 3.7 rules above answer "which tier for this language". These answer "...and does this
    // device have a faster way to run it" — without letting the answer to the second question
    // change the answer to the first. Since 4.1 the gate answer is a SET of offered gated tier
    // ids (two gated tiers can be independently installed); `setOf("npu")` below is the exact
    // 4.0 `true`, and `emptySet()` the exact 4.0 `false` — identical assertions, new spelling.

    @Test fun the_gated_tier_is_the_steer_only_where_the_device_runs_it_and_the_locale_needs_it() {
        // Capable device, and a language `multi` was already the right answer for: the same model
        // on the Hexagon is a strictly better version of the same answer.
        assertEquals("npu", ModelTierCopy.steerIdForLanguageTagFor("bn-BD", setOf("npu")))
        assertEquals("npu", ModelTierCopy.steerIdForLanguageTagFor("zh-Hans-CN", setOf("npu")))
        assertEquals("npu", ModelTierCopy.steerIdForLanguageTagFor("fr-CA", setOf("npu")))
        assertEquals("npu", ModelTierCopy.steerIdForLanguageTagFor("", setOf("npu")))
        // An ENGLISH locale keeps `pro` however fast the silicon is. Steering an English speaker
        // onto a multilingual tier because the device is capable is the Bengali review mirrored:
        // it trades the accuracy they came for against a speed they never asked about.
        assertEquals("pro", ModelTierCopy.steerIdForLanguageTagFor("en", setOf("npu")))
        assertEquals("pro", ModelTierCopy.steerIdForLanguageTagFor("en-US", setOf("npu")))
        assertEquals("pro", ModelTierCopy.steerIdForLanguageTagFor("EN-au", setOf("npu")))
        // Gate says no: 3.7's answer, unchanged, for every locale.
        assertEquals("multi", ModelTierCopy.steerIdForLanguageTagFor("bn-BD", emptySet()))
        assertEquals("pro", ModelTierCopy.steerIdForLanguageTagFor("en-US", emptySet()))
    }

    @Test fun the_gated_tier_leads_the_lineup_without_promoting_the_english_only_tier() {
        // The second ordering key, stated as the assertion it exists for: `multi` stays ahead of
        // `pro` for a non-English user. A one-key sort reads [npu, pro, multi] here and promotes
        // the English-only tier above the multilingual one 3.7 demoted it below — by a change
        // that was supposed to be about silicon.
        assertEquals(
            listOf("npu", "multi", "pro"),
            ModelTierCopy.orderedForLanguageTagFor("bn-BD", setOf("npu")),
        )
        // English locale on a capable device: `pro` leads, and the gated tier is LAST rather than
        // absent. The user can still reach it; they are simply not pushed at it.
        assertEquals(
            listOf("pro", "multi", "npu"),
            ModelTierCopy.orderedForLanguageTagFor("en-US", setOf("npu")),
        )
        assertEquals(listOf("multi", "pro"), ModelTierCopy.orderedForLanguageTagFor("bn-BD", emptySet()))
        assertEquals(listOf("pro", "multi"), ModelTierCopy.orderedForLanguageTagFor("en-US", emptySet()))
    }

    @Test fun the_lineup_is_a_permutation_of_this_devices_pickable_set_and_the_steer_leads_it() {
        // ORDER, not presence — the rule this branch has now paid for four times. Both chooser
        // surfaces make TWO calls: one for the cards, one for the badge and the highlight. If the
        // two ever disagree, the lineup leads with one card while "Best match for your language"
        // sits on another: every element present, every element in the wrong relationship to the
        // others, and nothing in the type system to notice. Every reachable gate answer is
        // driven: none, either tier alone, both.
        listOf("en", "en-US", "bn", "bn-BD", "de-AT", "zh-Hans-CN", "").forEach { tag ->
            listOf(
                emptySet(),
                setOf("npu"),
                setOf("npu-turbo"),
                setOf("npu", "npu-turbo"),
            ).forEach { offered ->
                val expected = WhisperCatalog.pickableFor(offered).map { it.id }
                val ordered = ModelTierCopy.orderedForLanguageTagFor(tag, offered)
                assertEquals(
                    "'$tag'/$offered lost or duplicated a tier",
                    expected.size,
                    ordered.size,
                )
                assertEquals(
                    "'$tag'/$offered is not a permutation of what this device can pick",
                    expected.toSet(),
                    ordered.toSet(),
                )
                assertEquals(
                    "'$tag'/$offered: the badged tier is not the one the lineup leads with",
                    ModelTierCopy.steerIdForLanguageTagFor(tag, offered),
                    ordered.first(),
                )
                ordered.forEach {
                    assertNotNull("ordered id '$it' does not resolve", WhisperCatalog.byId(it))
                    assertNotNull("ordered id '$it' has no card copy", ModelTierCopy.forId(it))
                }
            }
        }
    }

    @Test fun a_device_that_failed_the_gate_never_sees_a_gated_tier_at_all() {
        listOf("en", "en-US", "en_GB", "bn", "bn-BD", "de-AT", "zh-Hans-CN", "fr-CA", "").forEach { tag ->
            val ordered = ModelTierCopy.orderedForLanguageTagFor(tag, emptySet())
            assertFalse(
                "'$tag': a gated tier reached a device whose gate said no",
                ordered.contains("npu") || ordered.contains("npu-turbo"),
            )
            assertEquals("'$tag': the ungated lineup is 3.7's two tiers", 2, ordered.size)
            assertTrue(
                "'$tag': the ungated steer is not a tier this device can pick",
                WhisperCatalog.pickable.map { it.id }
                    .contains(ModelTierCopy.steerIdForLanguageTagFor(tag, emptySet())),
            )
            // The 3.7 spelling still answers identically: the new overload is the same rule with
            // one more input, not a second rule free to drift from it.
            assertEquals("'$tag': the two spellings disagree", ordered, ModelTierCopy.orderedForLanguageTag(tag))
        }
        // Being steered to is a position in a list and a chip. It is not selection, and nothing
        // about the gate moves the catalog default or lets a gated tier into `pickable`.
        assertEquals("pro", WhisperCatalog.DEFAULT_MODEL_ID)
        assertFalse(WhisperCatalog.pickable.map { it.id }.contains("npu"))
        assertFalse(WhisperCatalog.pickable.map { it.id }.contains("npu-turbo"))
    }

    // ------------------------------------------------------------ 4.1: the second gated tier
    //
    // Turbo's steering contract FLIPPED at L9, by measurement. Decision 8 refused it a promotion
    // while its accuracy claim was unproved; the owner's on-device A/B (2026-08-29) proved it —
    // "V3 Turbo is clearly the winner — much more accurate, at only about half a second slower"
    // — so turbo now HEADS the steered lineup exactly where it is offered, npu rides second, and
    // everything else (the default, auto-selection, the turbo-absent order) is byte-unchanged.

    @Test fun the_npu_turbo_tier_steers_exactly_when_offered_for_every_locale() {
        // RE-SPECCED at L9 (was: the_npu_turbo_tier_never_steers_for_any_locale_or_any_offer_set
        // — decision 8's negative, which named its own condition: "turbo's claim is unproved".
        // The owner's measured verdict met the condition, so the pin now asserts the pick).
        // Offered means installed AND gate-passing — the only state the promotion exists in;
        // any offer set without turbo steers exactly as before. (Never auto-SELECTS is
        // unchanged and pinned where selection lives: the chooser's pickedTierId starts null —
        // ChooserSteerWiringPinTest — and DEFAULT_MODEL_ID is `pro`.)
        listOf("en", "en-US", "bn", "bn-BD", "zh-Hans-CN", "fr-CA", "").forEach { tag ->
            listOf(setOf("npu-turbo"), setOf("npu", "npu-turbo")).forEach { offered ->
                assertEquals(
                    "'$tag'/$offered: the pick must steer to npu-turbo — for EVERY locale, " +
                        "English included: the accuracy win was measured on the owner's own " +
                        "speech and large-v3-turbo is multilingual, so this is not the " +
                        "Bengali-review shape (a worse model for the user's language)",
                    "npu-turbo",
                    ModelTierCopy.steerIdForLanguageTagFor(tag, offered),
                )
            }
            listOf(emptySet(), setOf("npu")).forEach { offered ->
                assertFalse(
                    "'$tag'/$offered: turbo absent from the offer set must mean turbo absent " +
                        "from the steer — the pick promotes an INSTALLED tier, never a card " +
                        "whose 1.07 GB pair is not on the device",
                    ModelTierCopy.steerIdForLanguageTagFor(tag, offered) == "npu-turbo",
                )
            }
        }
    }

    @Test fun the_pick_promotes_turbo_above_the_npu_steer() {
        // RE-SPECCED at L9 (was: the_npu_steer_survives_turbos_arrival, asserting these same
        // four calls answered "npu"/"pro" — the pre-pick truth). With both pairs installed the
        // A/B's winner heads; npu's own steer rule is intact underneath (the setOf("npu") rows
        // in the_gated_tier_is_the_steer_only_where... still bind, unchanged).
        assertEquals("npu-turbo", ModelTierCopy.steerIdForLanguageTagFor("bn-BD", setOf("npu", "npu-turbo")))
        assertEquals("npu-turbo", ModelTierCopy.steerIdForLanguageTagFor("zh-Hans-CN", setOf("npu", "npu-turbo")))
        assertEquals("npu-turbo", ModelTierCopy.steerIdForLanguageTagFor("", setOf("npu", "npu-turbo")))
        assertEquals("npu-turbo", ModelTierCopy.steerIdForLanguageTagFor("en-US", setOf("npu", "npu-turbo")))
    }

    @Test fun a_capable_device_is_offered_turbo_and_nothing_else_in_every_locale() {
        // RE-SPECCED at 4.3 (was: a_turbo_only_device_steers_to_turbo_and_keeps_the_cpu_order_
        // below_it — the pre-4.3 menu, which kept "the CPU tiers below it" in the exact order the
        // owner has now ruled out of existence on this hardware). The steer is UNCHANGED in body;
        // what changed is the lineup it heads, which is now one card long.
        assertEquals("npu-turbo", ModelTierCopy.steerIdForLanguageTagFor("bn-BD", setOf("npu-turbo")))
        assertEquals("npu-turbo", ModelTierCopy.steerIdForLanguageTagFor("en", setOf("npu-turbo")))
        assertEquals(
            listOf("npu-turbo"),
            ModelTierCopy.orderedForLanguageTagFor("bn-BD", setOf("npu-turbo")),
        )
        assertEquals(
            listOf("npu-turbo"),
            ModelTierCopy.orderedForLanguageTagFor("en-US", setOf("npu-turbo")),
        )
    }

    @Test fun the_npu_small_tier_is_hidden_from_a_capable_chooser_and_is_not_retired_for_it() {
        // RE-SPECCED at 4.3 (was: turbo_heads_the_lineup_and_the_npu_steer_rides_second, pinning
        // [npu-turbo, npu, multi, pro] / [npu-turbo, npu, pro, multi] — the four-card menu). The
        // owner's ruling: "Users shouldn't even see the 190 megabyte model or even the 358
        // megabyte model. They should just go straight to the one gig version." `npu` is HIDDEN,
        // not retired — the streaming arc needs it, and the catalogued-but-unoffered property is
        // pinned in WhisperCatalogHelpersTest.
        assertEquals(
            listOf("npu-turbo"),
            ModelTierCopy.orderedForLanguageTagFor("bn-BD", setOf("npu", "npu-turbo")),
        )
        assertEquals(
            listOf("npu-turbo"),
            ModelTierCopy.orderedForLanguageTagFor("en-US", setOf("npu", "npu-turbo")),
        )
    }

    @Test fun the_picks_truth_table_the_head_is_turbo_only_when_offered_else_the_pre_pick_order() {
        // L9's whole contract in one table: turbo-installed vs turbo-absent x English vs
        // non-English x gate-pass vs gate-fail. The head is turbo ONLY when installed+offered;
        // every turbo-absent arm is the PRE-PICK order to the element — npu does not jump
        // multi/pro on the strength of a verdict that was about turbo.
        val table = listOf(
            // offered set              tag      expected lineup
            // 4.3: every turbo-naming row collapsed to the single card the owner ruled — the
            // menu rows this table used to carry are the assertions the branch DELETES.
            Triple(setOf("npu", "npu-turbo"), "bn-BD", listOf("npu-turbo")),
            Triple(setOf("npu", "npu-turbo"), "en-US", listOf("npu-turbo")),
            Triple(setOf("npu-turbo"), "bn-BD", listOf("npu-turbo")),
            Triple(setOf("npu-turbo"), "en-US", listOf("npu-turbo")),
            Triple(setOf("npu"), "bn-BD", listOf("npu", "multi", "pro")),
            Triple(setOf("npu"), "en-US", listOf("pro", "multi", "npu")),
            Triple(emptySet(), "bn-BD", listOf("multi", "pro")),
            Triple(emptySet(), "en-US", listOf("pro", "multi")),
        )
        table.forEach { (offered, tag, expected) ->
            assertEquals(
                "'$tag'/$offered: the pick's truth table row",
                expected,
                ModelTierCopy.orderedForLanguageTagFor(tag, offered),
            )
            assertEquals(
                "'$tag'/$offered: the steer chip follows the head",
                expected.first(),
                ModelTierCopy.steerIdForLanguageTagFor(tag, offered),
            )
        }
        // And the pick changes STEERING only: the app-wide default fallback story is untouched.
        assertEquals("pro", WhisperCatalog.DEFAULT_MODEL_ID)
    }

    @Test fun the_lineup_with_both_npu_tiers_is_a_permutation_of_pickableFor_with_the_steer_leading() {
        // The brief's exact claim, stated against the both-tiers set specifically (the loop above
        // drives it too — this is the named case a reader will look for). The SIZE is 1 since 4.3:
        // the permutation claim is unchanged, the thing it is a permutation OF is one card.
        listOf("en-US", "bn-BD", "zh-Hans-CN", "").forEach { tag ->
            val both = setOf("npu", "npu-turbo")
            val ordered = ModelTierCopy.orderedForLanguageTagFor(tag, both)
            assertEquals(
                "'$tag': not a permutation of pickableFor(both)",
                WhisperCatalog.pickableFor(both).map { it.id }.toSet(),
                ordered.toSet(),
            )
            assertEquals("'$tag': lost or duplicated a tier", 1, ordered.size)
            assertEquals(
                "'$tag': the steered tier does not lead",
                ModelTierCopy.steerIdForLanguageTagFor(tag, both),
                ordered.first(),
            )
        }
    }

    // -------------------------------------------------------- 4.3: one tier per device
    //
    // The spec's own acceptance list: "the offer-set truth table (capable x installed-state x
    // locale)". The steer and the three ordering keys are UNCHANGED IN BODY — what changed is the
    // list they order, which `WhisperCatalog.pickableFor` now narrows on a capable device. These
    // drive the composition end to end, which is what the two chooser surfaces actually perform.

    @Test fun the_offer_set_truth_table_capable_x_installed_x_locale() {
        // rows: offered gate answer, installed ids, locale -> the exact lineup, in order
        data class Row(
            val offered: Set<String>,
            val installed: Set<String>,
            val tag: String,
            val expected: List<String>,
        )
        val table = listOf(
            // ---- CAPABLE. Fresh install: ONE card, whatever the locale. The owner's ruling.
            Row(setOf("npu", "npu-turbo"), emptySet(), "en-US", listOf("npu-turbo")),
            Row(setOf("npu", "npu-turbo"), emptySet(), "bn-BD", listOf("npu-turbo")),
            Row(setOf("npu", "npu-turbo"), emptySet(), "zh-Hans-CN", listOf("npu-turbo")),
            Row(setOf("npu", "npu-turbo"), emptySet(), "", listOf("npu-turbo")),
            Row(setOf("npu-turbo"), emptySet(), "en-US", listOf("npu-turbo")),
            Row(setOf("npu-turbo"), emptySet(), "bn-BD", listOf("npu-turbo")),
            // ---- CAPABLE, with history. The card for a model the user already has survives.
            Row(setOf("npu", "npu-turbo"), setOf("multi"), "bn-BD", listOf("npu-turbo", "multi")),
            Row(setOf("npu", "npu-turbo"), setOf("multi"), "en-US", listOf("npu-turbo", "multi")),
            Row(setOf("npu", "npu-turbo"), setOf("pro"), "en-US", listOf("npu-turbo", "pro")),
            Row(setOf("npu", "npu-turbo"), setOf("pro"), "bn-BD", listOf("npu-turbo", "pro")),
            Row(setOf("npu", "npu-turbo"), setOf("npu"), "bn-BD", listOf("npu-turbo", "npu")),
            Row(
                setOf("npu", "npu-turbo"), setOf("npu", "multi", "pro"), "bn-BD",
                listOf("npu-turbo", "npu", "multi", "pro"),
            ),
            Row(
                setOf("npu", "npu-turbo"), setOf("npu", "multi", "pro"), "en-US",
                listOf("npu-turbo", "npu", "pro", "multi"),
            ),
            // Turbo already installed: it is both the one offer and an existing install.
            Row(setOf("npu", "npu-turbo"), setOf("npu-turbo"), "en-US", listOf("npu-turbo")),
            // An installed RETIRED tier changes nothing — `!retired` runs first.
            Row(setOf("npu-turbo"), setOf("eco", "base"), "bn-BD", listOf("npu-turbo")),
            // ---- NOT CAPABLE. Byte-identical to 3.7/4.1, installed state irrelevant.
            Row(emptySet(), emptySet(), "en-US", listOf("pro", "multi")),
            Row(emptySet(), emptySet(), "bn-BD", listOf("multi", "pro")),
            Row(emptySet(), setOf("pro", "multi"), "bn-BD", listOf("multi", "pro")),
            Row(emptySet(), setOf("npu", "npu-turbo"), "en-US", listOf("pro", "multi")),
            // ---- CAPABLE FOR `npu` ONLY (no turbo row for this family). Unreachable on today's
            // census — every family carries both, pinned in NpuFleetCensusTest — but the rule
            // must still answer it, and its answer is the pre-4.3 one: turbo is what the ruling
            // is about, and a device that cannot be offered turbo keeps its menu.
            Row(setOf("npu"), emptySet(), "bn-BD", listOf("npu", "multi", "pro")),
            Row(setOf("npu"), emptySet(), "en-US", listOf("pro", "multi", "npu")),
            Row(setOf("npu"), setOf("npu"), "bn-BD", listOf("npu", "multi", "pro")),
        )
        table.forEach { (offered, installed, tag, expected) ->
            assertEquals(
                "$offered/$installed/'$tag': the 4.3 offer-set row",
                expected,
                ModelTierCopy.orderedForLanguageTagFor(tag, offered, installed),
            )
            // The two calls every chooser makes must agree: the badged card is the head.
            assertEquals(
                "$offered/$installed/'$tag': the steer chip is not on the card that leads",
                expected.first(),
                ModelTierCopy.steerIdForLanguageTagFor(tag, offered),
            )
            // A permutation of what this device can pick — never a card invented or lost.
            assertEquals(
                "$offered/$installed/'$tag': not a permutation of pickableFor",
                WhisperCatalog.pickableFor(offered, installed).map { it.id }.toSet(),
                expected.toSet(),
            )
            expected.forEach {
                assertNotNull("'$it' does not resolve", WhisperCatalog.byId(it))
                assertNotNull("'$it' has no card copy", ModelTierCopy.forId(it))
            }
        }
    }

    @Test fun a_fresh_capable_install_sees_exactly_one_card_and_makes_no_comparison() {
        // The spec's device acceptance, stated as the assertion an owner session verifies: "a
        // fresh capable install sees exactly one model card and reaches dictation without a
        // choice". Fresh = nothing installed; capable = the census union names turbo, which on a
        // fresh capable Play install is the fetchable half alone (4.2 F6).
        listOf("en", "en-US", "en_GB", "bn", "bn-BD", "de-AT", "zh-Hans-CN", "fr-CA", "").forEach { tag ->
            listOf(setOf("npu-turbo"), setOf("npu", "npu-turbo")).forEach { offered ->
                val ordered = ModelTierCopy.orderedForLanguageTagFor(tag, offered, emptySet())
                assertEquals("'$tag'/$offered: more than one card on a fresh capable install", 1, ordered.size)
                assertEquals("'$tag'/$offered", "npu-turbo", ordered.single())
                assertEquals(
                    "'$tag'/$offered: and it wears the steer chip",
                    ordered.single(),
                    ModelTierCopy.steerIdForLanguageTagFor(tag, offered),
                )
                // The two tiers the owner named are the ones that must NOT be there.
                assertFalse("'$tag'/$offered: the 190 MB tier is visible", ordered.contains("multi"))
                assertFalse("'$tag'/$offered: the 190 MB English tier is visible", ordered.contains("pro"))
                assertFalse("'$tag'/$offered: the 358 MB tier is visible", ordered.contains("npu"))
            }
        }
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

    // ---------------------------------------------------------------- the 4.1 npu-turbo card
    //
    // The second tier the census loops could never have reached through `pickable`, so the same
    // discipline is restated against the owner-approved strings — mirroring the npu pins Q7a's
    // I5 established.

    @Test fun the_npu_turbo_headline_is_pinned_exactly_and_takes_a_position() {
        val copy = ModelTierCopy.forId("npu-turbo")!!
        // "Best quality" is the spec's owner-approved framing (decision 8); ", slower" is the
        // disclosure the house rules require beside it — and it is already in POSITION_WORDS, so
        // the census passes WITHOUT the test's constant being edited to fit the copy, which
        // would be the wrong way round.
        assertEquals("Best quality, slower", copy.headline)
        assertTrue(
            "the npu-turbo headline takes no speed-vs-accuracy position",
            POSITION_WORDS.any { copy.headline.lowercase().contains(it) },
        )
    }

    @Test fun the_npu_turbo_badges_state_coverage_and_the_size_of_the_whole_pair() {
        val copy = ModelTierCopy.forId("npu-turbo")!!
        assertEquals(listOf("90+ languages", "1072 MB"), copy.badges)
        // The SAME coverage string every multilingual tier carries, so the cards stay comparable
        // at a glance.
        assertTrue(copy.badges.contains("90+ languages"))
        // 1072 MB is the PAIR (encoder + decoder), within the census's ±5 MB of approxBytes...
        val turbo = WhisperCatalog.byId("npu-turbo")!!
        val statedMb = copy.badges.first { it.endsWith(" MB") }.removeSuffix(" MB").toInt()
        val expectedMb = (turbo.approxBytes / 1_000_000L).toInt()
        assertTrue(
            "npu-turbo badge says $statedMb MB but the install is ~$expectedMb MB",
            kotlin.math.abs(statedMb - expectedMb) <= 5,
        )
        // ...and STRICTLY greater than the encoder alone, so a future edit that badges only the
        // 776 MB primary fires here even before the approxBytes tolerance does.
        assertTrue(
            "the badge must state the pair, not just the encoder",
            statedMb > turbo.primaryBytes / 1_000_000L,
        )
    }

    @Test fun the_npu_turbo_body_states_the_trade_and_claims_no_speed_win() {
        val copy = ModelTierCopy.forId("npu-turbo")!!
        assertEquals(
            "Large-v3's own encoder, on your phone's AI chip. Bigger and slower than " +
                "Multilingual on NPU — the reason to pick it is the words, not the speed.",
            copy.body,
        )
        // The comparison is OUR OWN other NPU card, named by its display family — the only
        // before/after this branch is entitled to. And the trade is stated against it honestly:
        // no WER has been measured for any w8a16 Whisper variant, so "the words" is a reason to
        // A/B, never a measured claim, and "faster" appears nowhere on this card.
        assertTrue(copy.body.contains("Multilingual on NPU"))
        assertTrue(copy.body.contains("slower"))
        assertFalse(
            "the turbo card may not claim a speed win anywhere",
            (copy.headline + " " + copy.body).lowercase().contains("faster"),
        )
    }

    @Test fun no_two_offered_tiers_share_a_headline() {
        // New with the second NPU card: the lineup now holds two tiers a user must tell apart at
        // a glance, and the headline is the glance. A copy-paste that leaves two cards reading
        // identically is exactly the mutation this census exists for.
        val headlines = offeredTiers.map { ModelTierCopy.forId(it.id)!!.headline }
        assertEquals(
            "two offered tiers share a headline: $headlines",
            headlines.size,
            headlines.toSet().size,
        )
    }

    private companion object {
        /** The 3.7 census's position vocabulary, shared so the npu pin cannot drift from the loop. */
        val POSITION_WORDS = listOf("fastest", "fast", "slower", "accuracy")
    }
}
