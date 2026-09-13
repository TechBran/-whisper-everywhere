package com.whispereverywhere.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelMigrationTest {

    private fun decide(
        selectedId: String?,
        selectedInstalled: Boolean = true,
        targetInstalled: Boolean = false,
        online: Boolean = true,
    ) = ModelMigration.decide(selectedId, selectedInstalled, targetInstalled, online)

    @Test fun a_current_tier_needs_no_migration() {
        assertEquals(ModelMigration.Action.None, decide("pro"))
        assertEquals(ModelMigration.Action.None, decide("multi"))
    }

    @Test fun a_retired_but_supported_tier_is_left_completely_alone() {
        // 3.7 Workstream H: eco and base are retired (hidden from the chooser) but still work.
        // Raising the migration card for them would ask a user with a working 60 MB model to
        // download 190 MB they never asked for — and the card's own copy ("much faster") would
        // be false, since pro is slower than eco. This is the test that forces decide() to gate
        // on `unsupported` rather than `retired`, in the same task that retires them.
        assertEquals(ModelMigration.Action.None, decide("eco"))
        assertEquals(ModelMigration.Action.None, decide("base"))
        assertEquals(ModelMigration.Action.None, decide("eco", online = false))
        assertEquals(ModelMigration.Action.None, decide("base", targetInstalled = true))
    }

    @Test fun no_selection_needs_no_migration() {
        // First run. Onboarding handles this; migration must not interfere.
        assertEquals(ModelMigration.Action.None, decide(null))
    }

    @Test fun an_unknown_id_needs_no_migration() {
        // Downgrade from a future version. Onboarding will handle it; do not delete anything.
        assertEquals(ModelMigration.Action.None, decide("some-future-tier"))
    }

    /**
     * 4.6 — **`ultra` LEFT every arm of this test.** It was the only `unsupported` MULTILINGUAL
     * tier, so it was this file's subject for four cases; the owner's ruling of 2026-09-13
     * un-retires it as an instrument, and an offered tier cannot also be one the app migrates
     * people off. `extreme` (medium.en) is now the ONLY unsupported tier, so it carries the arms
     * that need a real catalogue row, and the MULTILINGUAL arm of `targetIdFor` is proved directly
     * (`the_multilingual_target_is_multi_whether_or_not_a_tier_currently_points_at_it`) rather than
     * through a tier that no longer exists in that state.
     */
    @Test fun a_retired_tier_online_without_the_target_offers_the_download() {
        assertEquals(ModelMigration.Action.OfferDownload, decide("extreme"))
    }

    @Test fun an_un_retired_tier_is_left_completely_alone_whatever_the_state() {
        // The user-visible half of the un-retirement, and the defect it would be if the
        // `unsupported` bit had been left set: an `ultra` user has been carrying "This model is no
        // longer supported" since 3.7, and the chooser is now simultaneously inviting them to try
        // that very rung. `decide` gates on `unsupported` alone, so dropping both flags together
        // is what makes the card go away.
        assertEquals(ModelMigration.Action.None, decide("ultra"))
        assertEquals(ModelMigration.Action.None, decide("ultra", online = false))
        assertEquals(ModelMigration.Action.None, decide("ultra", targetInstalled = true))
        assertEquals(ModelMigration.Action.None, decide("ultra", selectedInstalled = false))
        // ...and so is every other rung the ladder added. None of them is a tier the app wants
        // anyone off of, which is what `instrument` means.
        WhisperCatalog.instruments.forEach { m ->
            assertEquals(
                "instrument '${m.id}' raised a migration action — an offered rung may not also " +
                    "be one the app migrates people off",
                ModelMigration.Action.None,
                decide(m.id),
            )
            assertEquals(ModelMigration.Action.None, decide(m.id, targetInstalled = true))
            assertEquals(ModelMigration.Action.None, decide(m.id, online = false))
        }
    }

    @Test fun a_retired_tier_offline_waits_and_keeps_the_old_model() {
        // THE load-bearing case. Deleting or switching here would leave an offline user with no
        // usable model and no way to get one — the app gate would dump them into onboarding.
        assertEquals(ModelMigration.Action.WaitForNetwork, decide("extreme", online = false))
    }

    @Test fun swap_only_happens_once_the_target_is_actually_on_disk() {
        assertEquals(
            ModelMigration.Action.SwapAndDelete("extreme", WhisperCatalog.DEFAULT_MODEL_ID),
            decide("extreme", targetInstalled = true),
        )
        assertEquals(ModelMigration.Action.OfferDownload, decide("extreme", targetInstalled = false))
    }

    @Test fun swap_happens_offline_too_once_the_target_is_installed() {
        // No network needed to swap a file that is already downloaded.
        assertEquals(
            ModelMigration.Action.SwapAndDelete("extreme", "pro"),
            decide("extreme", targetInstalled = true, online = false),
        )
    }

    @Test fun a_retired_tier_whose_file_is_already_gone_still_offers_the_download() {
        // User cleared storage. Nothing to delete, but they still need a working model.
        assertEquals(ModelMigration.Action.OfferDownload, decide("extreme", selectedInstalled = false))
    }

    // MF3: the target must match the retired model's language scope. Routing a MULTILINGUAL user
    // to an ENGLISH-only default silently breaks dictation in every other language, with no
    // warning — that was the MF3 bug, and it is the reason `targetIdFor` takes a scope at all.
    // Replaces the old `migration_target_is_the_catalog_default`, which assumed every retired
    // tier maps to WhisperCatalog.DEFAULT_MODEL_ID regardless of scope.
    //
    // 4.6 — **the MULTILINGUAL arm no longer has a catalogue row pointing at it.** `ultra` was the
    // only unsupported MULTILINGUAL tier and it is a live rung again, so the arm is proved on the
    // function directly. That is not a weaker test: the arm is still REACHED by every future
    // multilingual retirement, and a mapping that only holds for today's rows is exactly the
    // assumption MF3 was.
    @Test fun the_multilingual_target_is_multi_whether_or_not_a_tier_currently_points_at_it() {
        assertEquals("multi", ModelMigration.targetIdFor(ModelScope.MULTILINGUAL))
        // The arm reached the way a real retirement would reach it: a MULTILINGUAL row carrying
        // the unsupported bit. No such row exists today, which is why it is constructed.
        assertEquals(ModelScope.MULTILINGUAL, WhisperCatalog.byId("ultra")!!.scope)
        assertEquals(
            "a multilingual retirement must land on a multilingual rung, or dictation breaks " +
                "silently in every language the user actually speaks",
            "multi",
            ModelMigration.targetIdFor(WhisperCatalog.byId("ultra")!!.scope),
        )
    }

    @Test fun an_english_unsupported_tier_migrates_to_pro() {
        val a = decide("extreme", targetInstalled = true) as ModelMigration.Action.SwapAndDelete
        assertEquals(WhisperCatalog.DEFAULT_MODEL_ID, a.toId)
        assertEquals("pro", a.toId)
    }

    @Test fun every_migration_target_is_a_tier_the_user_can_actually_pick() {
        // A target that is itself retired would move users from one dead end to another.
        listOf(ModelScope.ENGLISH, ModelScope.MULTILINGUAL).forEach { scope ->
            val target = ModelMigration.targetIdFor(scope)
            assertTrue(
                "target '$target' for $scope is not pickable",
                WhisperCatalog.pickable.any { it.id == target },
            )
            assertEquals(scope, WhisperCatalog.byId(target)!!.scope)
        }
    }
}
