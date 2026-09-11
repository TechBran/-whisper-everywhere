package com.whispereverywhere.transcription.stream

/**
 * WHY LIVE WORDS CANNOT APPEAR HERE AT ALL, or null when nothing standing is in the way — the
 * previewer's two SURFACE-LEVEL caveats, answered once (4.5.0 Task 4).
 *
 * ### The defect this retires: the feature had an answer for the LANGUAGE axis and none for the DEVICE
 *
 * 4.4.1 pass 3's ITEM 1 established the rule this enum generalises, and wrote it into the Settings
 * section in those words: **no language may be offered a model that has already been decided cannot
 * arm for it.** A user who picked French was being offered *"Get the English preview model"*, spent
 * 73 MB, and was then told *"Installed. Words appear on the bubble as you speak English"* — a
 * sentence owner ruling 1 had already decided could never be true for them.
 *
 * The same rule, one axis over, was never applied: **no DEVICE may be offered a model that has
 * already been decided cannot arm on it.** `localPreviewArms` conjoins `!isCloudSession`, and with
 * no on-device whisper tier installed every session is a cloud session
 * (`FloatingBubbleService.decideEngineChoice` answers a CLOUD leaf whenever a provider and a key
 * are present, and the LOCAL leaves have no model to run), so the previewer can never arm — for
 * any language, on any connection, with any pack installed and the switch on. 4.4.1 closed exactly
 * one of the sentences that promises otherwise (`PreviewAutoFetch.card`'s `Card.INSTALLED`
 * announcement, pass 3's ITEM 3) and `PreviewAutoFetch.decide` refuses to SPEND for such a device —
 * but the Settings offer row, its three per-source subtitles, the three repair subtitles, the
 * picker's deal sentence, the per-row size badges and the Auto caveat all still promised words to
 * it, and the offer row's tap still spent the 73 MB.
 *
 * It is not a rare cell. `WhisperModelManager.installedModel()` resolves the SELECTED tier and
 * answers null unless that tier's files are present, and the same Settings screen that draws the
 * previewer's rows carries *"Delete <tier>"* — which clears `selectedModelId` and says so in its
 * own words: *"On-device transcription will stop working until you download a model again."* So a
 * user can reach this state, in one gesture, three sections above the rows that then lie to them.
 *
 * ### Why the TIER outranks the SELECTION
 *
 * Both facts can hold at once (a cloud-only user standing on Auto), and only one order is honest.
 * The selection's own sentence INSTRUCTS — `StreamingPackCopy.noLiveWordsSubtitle(null)` says
 * *"pick your transcription language to see words on the bubble as you speak"* — and that
 * instruction is false on a device with no tier, whichever language they then pick. A fact no pick
 * can change outranks a fact a pick would change; naming the lesser one would send the user to a
 * control that cannot help them. (This is the same precedence argument `PreviewDeleteCase` makes
 * between its own reasons, run the other way: there the switch wins because one tap WOULD change
 * the answer.)
 *
 * ### What is deliberately NOT here
 *
 * Only STANDING facts about the device and the selection, because that is all a caveat row may be
 * drawn from. The momentary ones already have owners and a second answer here would be a second
 * opinion: a session in flight, a batch job and a transfer already running are
 * [PreviewAutoFetch.decide]'s; the *"Show live words"* switch and the previewer's per-process
 * verdict are `PreviewAutoFetch.card`'s and `StreamingPackCopy.selectorLine`'s. The switch in
 * particular is NOT a third value: it is the user's own off, said by the control itself, and a
 * caveat row explaining a switch one row above it is a screen arguing with itself.
 *
 * **The one axis this cannot see is stated rather than hidden: a device WITH a tier whose user has
 * selected a cloud provider and saved a key also runs cloud sessions, and the previewer will not
 * arm for those either.** That is not the same fact — it is reversible (turning the provider off,
 * or losing the network, makes `decideEngineChoice` answer a LOCAL leaf and the previewer arms
 * again on the very next session), it is per session rather than standing, and withdrawing the
 * feature's whole copy from every cloud user is a product decision about discovery rather than a
 * truth fix. It is Task 4's one open ruling; if it is ruled, it becomes a term of
 * [NO_LOCAL_TIER]'s input at the call sites and no sentence in the feature has to change, because
 * `StreamingPackCopy`'s wording for this case names the RULE (*"live words appear only while
 * transcription runs on this device"*) and not the missing file.
 */
enum class PreviewUnreachable {
    /**
     * No on-device whisper tier: every session is a cloud session, `localPreviewArms` refuses on
     * `!isCloudSession`, and no pack, no pick, no connection and no switch can put a word on the
     * bubble. The feature has exactly one true thing to say on this device, and it says it once.
     */
    NO_LOCAL_TIER,

    /**
     * The selected language has no catalogue row — Auto, where the user picked none at all, or a
     * language the app has no pack for yet (4.4.1 pass 3, ITEM 1). The two are told apart by
     * `StreamingPackCopy.noLiveWordsTitle`/`noLiveWordsSubtitle`'s own null arm, because a
     * deliberate Auto and a gap in the app are different facts about the world.
     */
    NO_PACK_FOR_SELECTION,

    ;

    companion object {
        /**
         * @param localTierInstalled an on-device whisper tier exists — `WhisperModelManager
         *        .installedModel() != null`, the same read `PreviewAutoFetch.decide` and
         *        `PreviewAutoFetch.card` already take as their own `localTierInstalled`, so the
         *        acquisition side, the announcement and the caveat cannot disagree about who this
         *        feature is for.
         * @param hasPackForSelection `StreamingPackCatalog.forLanguage(selected) != null`.
         */
        fun of(localTierInstalled: Boolean, hasPackForSelection: Boolean): PreviewUnreachable? =
            when {
                !localTierInstalled -> NO_LOCAL_TIER
                !hasPackForSelection -> NO_PACK_FOR_SELECTION
                else -> null
            }
    }
}
