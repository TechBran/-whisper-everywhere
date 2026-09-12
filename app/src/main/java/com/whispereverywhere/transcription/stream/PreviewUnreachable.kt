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
 * already been decided cannot arm on it.** With no on-device speech model no word can reach the
 * bubble — for any language, on any connection, with any pack installed and the switch on — and
 * THE MECHANISM section below states exactly why, because the first version of this KDoc named
 * the wrong reason. 4.4.1 closed exactly one of the sentences that promises otherwise
 * (`PreviewAutoFetch.card`'s `Card.INSTALLED` announcement, pass 3's ITEM 3) and
 * `PreviewAutoFetch.decide` refuses to SPEND for such a device — but the Settings offer row, its
 * three per-source subtitles, the three repair subtitles, the picker's deal sentence, the per-row
 * size badges and the Auto caveat all still promised words to it, and the offer row's tap still
 * spent the 73 MB.
 *
 * ### THE MECHANISM — the arming axis is the SESSION, not the tier (fix round 1, review r1's B1)
 *
 * This KDoc, and every site that took its sentence from it, said *"with no on-device whisper tier
 * every session is a cloud session, so `localPreviewArms` can never fire"*. **That is false, and
 * it matters because the enumeration this task delivers was built on it.** `decideEngineChoice`
 * answers `EngineChoice.LOCAL_ONLY` whenever `sttProviderId` is null — the enum's own KDoc calls
 * that *"the only reachable outcome"* — the `LOCAL_ONLY` arm never assigns `cloudWrapper`, so
 * `isCloudSession` is false; and `localPreviewArms` has **no tier term at all** among its six
 * inputs. Two facts are needed where the feature used to name one:
 *
 *  1. **Nothing can transcribe on this device.** `installedModelPath()` is null exactly when
 *     `installedModel()` is, so `LocalWhisperEngine.connect` answers
 *     `onError("No speech model installed")` while the bubble is still `CONNECTING` (`RECORDING`
 *     is set only from `onOpen`) and the service takes the FATAL arm of `onError` —
 *     `updateBubbleState(ERROR)` + `teardownRealtime()`. The session ends before the capture
 *     thread's startup ring is ever drained into an engine, so no partial is composed and no word
 *     is rendered. **This is the reason that holds when there is no provider either**, and it is
 *     the reason every sentence this enum selects is true.
 *  2. **And where a provider IS selected and keyed, every session is ADDITIONALLY a cloud
 *     session**, which `localPreviewArms` refuses on `!isCloudSession`.
 *
 * So {tier} × {a usable cloud provider} has FOUR cells, and the gate ARMS in one of them:
 *
 *  - **a tier, a provider** — cloud sessions, so the gate refuses on `!isCloudSession`. This is
 *    the open ruling below, and it is reversible per session.
 *  - **a tier, no provider** — the validated happy path: the words appear.
 *  - **no tier, a provider** — reason 2, with reason 1 underneath it. **Its own enumeration row,
 *    and not a footnote to the one below** (fix round 2, review r2's B1): it is plausibly the
 *    MAJORITY of the no-tier population, because onboarding's ENGINES step is mandatory and
 *    uncompletable without a tier, so a no-tier device is one a deliberate *"Delete <tier>"* made
 *    — under a dialog warning that on-device transcription will stop — and the user for whom that
 *    warning is acceptable is the one who transcribes in the cloud.
 *  - **no tier, NO provider** — **reason 1 ALONE, and the gate ARMS here**: `LOCAL_ONLY`,
 *    `cloudWrapper == null`, and no tier term to refuse on. Not an exotic cell — it is the
 *    DEFAULT shape of a modelless install (a tier deleted in Settings, an Auto-Backup restore) on
 *    a phone where no provider was ever configured, which is the population this whole axis was
 *    built for. The first enumeration of this axis declared it impossible.
 *
 * **A cell is checked by its SENTENCES, never by its reason.** The first version of this table
 * asked of each cell only *"does reason 1 still hold here?"* — and every cell passed that, which
 * is why the table sat next to the enum for a whole review round while the one sentence this axis
 * ADDED was false in the cell above. The brief's test is *true or absent*, and it is asked of each
 * sentence the cell SELECTS: `StreamingPackCopy.NO_TIER_SUBTITLE` instructed the reader to
 * download a speech model, which lands a user in *"a tier, a provider"* — where no live word
 * appears on any of their normal sessions and this caveat is gone, so the instruction moved them
 * from one true sentence to the whole 4.4.1 copy. It is a conditional now, true in both sub-cells
 * and in both directions of the open ruling. That is what this table is FOR, and it is next to the
 * function that selects those sentences so the two get read together.
 *
 * **The dependency that fourth cell creates is pinned, not assumed.** Every sentence selected
 * here is true because the session dies at connect; if a later change let a modelless session
 * survive to `RECORDING` with the previewer armed, words WOULD appear — the previewer is a second
 * engine beside whisper and needs no tier of its own — and all of them would become false.
 * `LocalPreviewWiringPinTest.aModellessSessionDiesAtConnectAndThatIsWhatMakesTheseSentencesTrue`
 * holds the lines that make it so, and
 * `LocalPreviewGateTest.theGateItselfArmsWithNoTierAndNoProviderConfigured` executes the cell.
 *
 * **And the fourth cell is why the announcement's flag is no longer written from this gate's
 * answer.** `FloatingBubbleService` wrote `livePreviewArmedOnce` wherever the gate armed, which on
 * that phone is every session — on a device that has never rendered a live word. The flag is
 * global and permanent and `PreviewAutoFetch.card` reads it as *"the user has already watched the
 * words appear"*, so the day such a user followed this feature's own instruction and downloaded a
 * speech model, `Card.INSTALLED` (*"Live words are on"*) would be suppressed forever — for exactly
 * the reader the announcement exists for. The write moved to `onOpen`, the first instant a word
 * can have appeared.
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
 * **The one axis this cannot see is stated rather than hidden, and the question it asks is WHICH
 * DEVICES MAY BE TOLD ABOUT THIS FEATURE — not a cloud predicate** (fix round 1: the earlier
 * wording derived the open ruling from the same false axis THE MECHANISM corrects, so the ruling
 * would have been taken on the wrong fact). A device WITH a tier whose user has selected a
 * provider and saved a key runs cloud sessions, and the previewer does not arm for those. That is
 * not the same fact as reason 1: it is reversible (turning the provider off, or losing the
 * network, makes `decideEngineChoice` answer a LOCAL leaf and the previewer arms again on the very
 * next session) and per session, where reason 1 is standing — nothing on that phone transcribes at
 * all, ever. So what is open is a DISCOVERY decision: may a user whose every configured session is
 * a cloud session be shown this feature's copy at all, given they can undo the condition by
 * walking into a lift? Withdrawing the whole copy from every cloud user is a product call, not a
 * truth fix. If it is ruled, it becomes a SECOND standing input here rather than a widening of
 * [NO_LOCAL_TIER]'s, and no sentence in the feature has to change, because `StreamingPackCopy`'s
 * wording for this case names the RULE (*"live words appear only while transcription runs on this
 * device"*) and not the missing file.
 */
enum class PreviewUnreachable {
    /**
     * No on-device speech model, so nothing transcribes here at all: the session dies at connect
     * with *"No speech model installed"* (THE MECHANISM above — the tier is NOT a term of
     * `localPreviewArms`, and with no provider configured the gate itself arms), and no pack, no
     * pick, no connection and no switch can put a word on the bubble. The feature has exactly one
     * true thing to say on this device, and it says it once.
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
