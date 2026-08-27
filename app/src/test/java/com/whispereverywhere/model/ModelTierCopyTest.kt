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

    @Test fun every_pickable_tier_has_copy() {
        WhisperCatalog.pickable.forEach { model ->
            assertNotNull("no copy for offered tier '${model.id}'", ModelTierCopy.forId(model.id))
        }
    }

    @Test fun every_tier_states_its_size_as_a_badge() {
        WhisperCatalog.pickable.forEach { model ->
            val copy = ModelTierCopy.forId(model.id)!!
            assertTrue(
                "tier '${model.id}' has no size badge",
                copy.badges.any { it.endsWith(" MB") },
            )
        }
    }

    @Test fun the_size_badge_tells_the_truth_about_the_download() {
        // 60 MB tiers say 60, 190 MB tiers say 190 — the badge must track approxBytes.
        WhisperCatalog.pickable.forEach { model ->
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
        val positionWords = listOf("fastest", "fast", "slower", "accuracy")
        WhisperCatalog.pickable.forEach { model ->
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
        WhisperCatalog.pickable.forEach { model ->
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
        WhisperCatalog.pickable.forEach { model ->
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
}
