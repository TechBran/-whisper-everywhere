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
        assertEquals(ModelMigration.Action.None, decide("eco"))
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
        assertEquals(
            ModelMigration.Action.SwapAndDelete("ultra", "eco"),
            decide("ultra", targetInstalled = true),
        )
    }

    @Test fun swap_happens_offline_too_once_the_target_is_installed() {
        // No network needed to swap a file that is already downloaded.
        assertEquals(
            ModelMigration.Action.SwapAndDelete("extreme", "eco"),
            decide("extreme", targetInstalled = true, online = false),
        )
    }

    @Test fun a_retired_tier_whose_file_is_already_gone_still_offers_the_download() {
        // User cleared storage. Nothing to delete, but they still need a working model.
        assertEquals(ModelMigration.Action.OfferDownload, decide("ultra", selectedInstalled = false))
    }

    @Test fun migration_target_is_the_catalog_default() {
        val a = decide("ultra", targetInstalled = true) as ModelMigration.Action.SwapAndDelete
        assertEquals(WhisperCatalog.DEFAULT_MODEL_ID, a.toId)
    }
}
