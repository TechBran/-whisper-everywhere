package com.whispereverywhere.model

/**
 * Decides how to move a user off a retired model tier. Pure and Android-free so every state is
 * unit-testable — this logic decides whether a shipped user can still dictate, so it must not be
 * reachable only through a device.
 *
 * ORDERING IS THE WHOLE POINT: download the replacement, verify it on disk, THEN switch the
 * selection, THEN delete the old file. Any other order can leave a user with no usable model,
 * because `installedModel()` requires the selected tier's file to exist and the entire app is
 * gated on that being non-null — with onboarding offering no way back.
 */
object ModelMigration {

    sealed interface Action {
        /** Nothing to do: current tier, no selection, or an id this build does not know. */
        data object None : Action
        /** On a retired tier, online, replacement not yet downloaded. */
        data object OfferDownload : Action
        /** On a retired tier but offline. KEEP the old model working and retry later. */
        data object WaitForNetwork : Action
        /** Replacement verified on disk. Safe to switch and reclaim the old file. */
        data class SwapAndDelete(val fromId: String, val toId: String) : Action
    }

    fun decide(
        selectedId: String?,
        selectedInstalled: Boolean,
        targetInstalled: Boolean,
        online: Boolean,
    ): Action {
        val selected = selectedId?.let { WhisperCatalog.byId(it) } ?: return Action.None
        if (!selected.retired) return Action.None
        val target = WhisperCatalog.DEFAULT_MODEL_ID
        // Target on disk wins regardless of connectivity — nothing left to download.
        if (targetInstalled) return Action.SwapAndDelete(selected.id, target)
        return if (online) Action.OfferDownload else Action.WaitForNetwork
    }
}
