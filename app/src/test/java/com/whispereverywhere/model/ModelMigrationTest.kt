package com.whispereverywhere.model

import org.junit.Assert.assertEquals
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

    @Test fun a_retired_tier_online_without_the_target_offers_the_download() {
        assertEquals(ModelMigration.Action.OfferDownload, decide("ultra"))
        assertEquals(ModelMigration.Action.OfferDownload, decide("extreme"))
    }

    @Test fun a_retired_tier_offline_waits_and_keeps_the_old_model() {
        // THE load-bearing case. Deleting or switching here would leave an offline user with no
        // usable model and no way to get one — the app gate would dump them into onboarding.
        assertEquals(ModelMigration.Action.WaitForNetwork, decide("ultra", online = false))
    }

    @Test fun swap_only_happens_once_the_target_is_actually_on_disk() {
        // "ultra" is MULTILINGUAL, so its target is "multi", not the ENGLISH default "pro" —
        // see the MF3 tests below pinning the scope-aware mapping.
        assertEquals(
            ModelMigration.Action.SwapAndDelete("ultra", "multi"),
            decide("ultra", targetInstalled = true),
        )
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
        assertEquals(ModelMigration.Action.OfferDownload, decide("ultra", selectedInstalled = false))
    }

    // MF3: the target must match the retired model's language scope. "ultra" is MULTILINGUAL
    // (large-v3-turbo) — routing it to the ENGLISH-only default silently breaks dictation in every
    // other language. "extreme" is ENGLISH, so the English default is correct for it. Since 3.7 the
    // lineup is two tiers, so those targets are "multi" and "pro".
    // Replaces the old `migration_target_is_the_catalog_default`, which assumed every retired
    // tier maps to WhisperCatalog.DEFAULT_MODEL_ID regardless of scope — that assumption is the
    // bug MF3 fixes.
    @Test fun a_multilingual_unsupported_tier_migrates_to_multi_not_the_english_default() {
        val a = decide("ultra", targetInstalled = true) as ModelMigration.Action.SwapAndDelete
        assertEquals("multi", a.toId)
    }

    @Test fun an_english_unsupported_tier_migrates_to_pro() {
        val a = decide("extreme", targetInstalled = true) as ModelMigration.Action.SwapAndDelete
        assertEquals(WhisperCatalog.DEFAULT_MODEL_ID, a.toId)
        assertEquals("pro", a.toId)
    }
}
