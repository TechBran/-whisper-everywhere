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
     * The pickable tier an unsupported model's users should land on. It must never be WORSE for
     * the language the user actually speaks — moving a MULTILINGUAL user to an ENGLISH-only
     * default silently breaks dictation in every other language with no warning, which was the MF3
     * bug and is the reason this function takes a scope at all.
     *
     * **4.6 — BOTH ARMS NOW ANSWER `multi`, and that is the correct answer to both.** The owner's
     * ruling of 2026-09-13 retires the last English-only rung (`pro`), so
     * [WhisperCatalog.DEFAULT_MODEL_ID] is `multi` and the ENGLISH arm resolves there too. The
     * collapse is safe in exactly one direction and this is that direction: an ENGLISH-scope user
     * landing on a MULTILINGUAL rung loses nothing — `multi` is the same 190 MB of whisper-small
     * weights with a multilingual vocab head and transcribes English perfectly — whereas the
     * reverse is MF3. The scope parameter therefore still earns its place: it is what makes the
     * unsafe direction impossible to reach, and the next time an English-only rung is offered the
     * two arms separate again without anyone rediscovering why.
     *
     * **The two constants stay SEPARATE even though they are equal today.** Folding
     * [MULTILINGUAL_TARGET_ID] into `DEFAULT_MODEL_ID` would mean a future English default
     * silently becomes the multilingual target as well — which is MF3 reintroduced by a
     * refactor, not by a decision.
     *
     * Pinned exhaustively by `ModelMigrationTest`: over every [ModelScope], the target must be
     * pickable, must not be retired, must not be a [WhisperModel.instrument], and must be able to
     * transcribe the scope it is a target for.
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
        //
        // 4.6 leans on this line harder than any release has: `pro` joins the retired set and it
        // is the tier the LARGEST number of English users are on. Every one of them must fall
        // through here to Action.None — nobody dictating happily on small.en gets told to fetch
        // 190 MB they never asked for, for a model whose only difference is its vocab head.
        if (!selected.unsupported) return Action.None
        val target = targetIdFor(selected.scope)
        // Target on disk wins regardless of connectivity — nothing left to download.
        if (targetInstalled) return Action.SwapAndDelete(selected.id, target)
        return if (online) Action.OfferDownload else Action.WaitForNetwork
    }
}
