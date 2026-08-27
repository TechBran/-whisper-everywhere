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

    /**
     * The pickable tier an unsupported model's users should land on. MUST match the retired
     * model's [ModelScope] — moving a MULTILINGUAL user to the ENGLISH-only default silently
     * breaks dictation in every other language with no warning (that was the MF3 bug). Since 3.7
     * the lineup is two tiers: "pro" is the ENGLISH default and "multi" is its multilingual
     * counterpart.
     */
    fun targetIdFor(scope: ModelScope): String =
        if (scope == ModelScope.MULTILINGUAL) MULTILINGUAL_TARGET_ID else WhisperCatalog.DEFAULT_MODEL_ID

    private const val MULTILINGUAL_TARGET_ID = "multi"

    fun decide(
        selectedId: String?,
        selectedInstalled: Boolean,
        targetInstalled: Boolean,
        online: Boolean,
    ): Action {
        val selected = selectedId?.let { WhisperCatalog.byId(it) } ?: return Action.None
        // `unsupported`, not `retired` (3.7 Workstream H): a merely retired tier is hidden from
        // the chooser and otherwise left completely alone — its installed users are not prompted,
        // not migrated, and never asked to re-download.
        if (!selected.unsupported) return Action.None
        val target = targetIdFor(selected.scope)
        // Target on disk wins regardless of connectivity — nothing left to download.
        if (targetInstalled) return Action.SwapAndDelete(selected.id, target)
        return if (online) Action.OfferDownload else Action.WaitForNetwork
    }
}
