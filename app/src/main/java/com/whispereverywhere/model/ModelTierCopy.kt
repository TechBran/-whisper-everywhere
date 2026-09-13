package com.whispereverywhere.model

/**
 * The single source of truth for tier descriptions — consumed by BOTH the guided onboarding's
 * model-choice cards and the Settings manual picker (OnboardingModelScreen), so the two surfaces
 * can never drift apart. Pattern: HowToGuide — pure, Compose- and Android-free, every string a
 * JVM test subject.
 *
 * Copy discipline, pinned by [ModelTierCopyTest] (the test that would have prevented the Bengali
 * review): every offered tier states a size, a speed-vs-accuracy position, and its language
 * coverage as a badge — "English only" on every ENGLISH-scope tier, "90+ languages" on every
 * MULTILINGUAL tier. Since 3.7 the lineup is two tiers, so the copy positions each one directly
 * instead of by comparison to a retired card
 * ([ModelTierCopyTest.no_offered_tier_names_a_retired_one]); the app-wide no-speed-claims rule
 * constrains CLOUD claims, which this copy never makes.
 */
object ModelTierCopy {

    /** One tier's card copy: a positioning headline, badge chips, and one honest sentence. */
    data class TierCopy(val headline: String, val badges: List<String>, val body: String)

    /**
     * **The warning every heavy CPU rung carries** (4.6). One string, one place, so five cards
     * cannot say it five ways and so a test can pin the wording.
     *
     * It is deliberately NOT a speed claim in reverse. It states the failure MODE and the remedy,
     * which is what a user who hits it needs, and it is the one caution the previewer makes
     * necessary: since 4.4.0 a streaming Zipformer puts words on the floating strip about 0.4 s
     * behind the voice whatever the finalizer is doing, so **a finalizer that cannot keep up looks
     * fine** — words keep appearing — right up until the typed text lags the strip by a sentence
     * and then a paragraph. The user cannot see a growing queue; they can see text arriving late.
     *
     * "May not keep up with continuous speech" is the honest shape of the risk: this app's
     * `audio_ctx` floor makes the cost per commit constant, so what a heavy rung runs out of is
     * COMMITS PER SECOND, and only sustained speech exposes it. A ten-second trial will not.
     */
    const val KEEP_UP_NOTE: String =
        "This model may not keep up with continuous speech on this device. If the typed text " +
            "falls behind your voice, a smaller rung is the fix."

    private val copyById: Map<String, TierCopy> = mapOf(
        // 4.6 — `pro`'s card is GONE, because `pro` is retired (owner ruling 2026-09-13: no
        // English-only rungs at all) and a retired tier has no card. `forId` answering null for it
        // is the contract `retired_and_unknown_tiers_have_no_copy` states, and the screens' own
        // fallback row handles the null. Its copy was "Best English accuracy" / "The sharpest
        // on-device English dictation this app ships." — both still true of the file, which is
        // exactly why `pro` is retired rather than `unsupported`: nobody is being told to leave it.
        "multi" to TierCopy(
            // 4.6: was "Best multilingual accuracy" (3.7, owner-approved) — TRUE while `multi` was
            // one of two rungs and the only multilingual one, and FALSE the moment five larger
            // multilingual rungs are offered beside it. Correcting it is required by the same
            // discipline that wrote the 3.7 string: a card may not claim a position it no longer
            // holds. What `multi` uniquely holds now is that it is the only rung on the ladder
            // anyone has MEASURED (F = 2.3 s, duty 0.42, Fold6, this repo's audio-ctx bench of
            // 2026-08-20) — which is exactly why it is the default and the migration target.
            headline = "Everyday accuracy, smallest download",
            badges = listOf("90+ languages", "190 MB"),
            body = "The 190 MB model this app has shipped from the start, and the one rung on " +
                "this list with a measured verdict behind it.",
        ),
        // ============================================ 4.6 — THE INSTRUMENT RUNGS' CARDS
        //
        // Six cards for the six `WhisperModel.instrument` rungs. Two rules govern every one of
        // them, and neither is timidity:
        //
        //  1. **No rung here claims speed, in either direction.** Nothing on this ladder has been
        //     measured on the owner's hardware and he is about to measure it on six devices. A
        //     card that predicts the winner is worse than one that stays quiet — it is a claim he
        //     has to catch instead of a finding he makes. The arithmetic also says a ranking would
        //     probably be wrong: this app feeds whisper's FIXED-window encoder short VAD-cut
        //     chunks with `audio_ctx` clamped to at least 512, so cost per commit is constant and
        //     the workload is encoder-dominated — the regime published benchmark tables, which run
        //     long files where decode dominates, do not measure. `large-v3-turbo` is large-v3's
        //     entire 32-layer/1280-dim encoder with the decoder cut to 4 layers against medium's
        //     24 at 1024: it wins on decode and loses on encode.
        //  2. **Accuracy IS rankable and these cards rank it**, because whisper's own size
        //     ordering is not a claim about this app's hardware. So each card says what its model
        //     IS — size, depth, and the quantisation wherever that is the only thing separating it
        //     from the card beside it — and lets six devices answer the rest.
        //
        // The quantisation has to be on the card or the session cannot interpret its own results:
        // three of these rungs are the SAME MODEL as a neighbour at a different quantisation, and
        // "the 539 MB one was slower than the 823 MB one" is only a finding if the reader can see
        // that those two are the same weights. Every hyperparameter quoted below was read off each
        // file's own ggml header on 2026-09-13, so every sentence is checkable.
        //
        // The NPU cards further down KEEP their measured "fastest": that claim is true, owner-
        // ruled, and scoped to silicon this app has benchmarked. Removing a true claim would be
        // the regression.
        "small-q8" to TierCopy(
            headline = "Same model, finer quantisation",
            badges = listOf("90+ languages", "264 MB"),
            body = "Whisper small — the same weights as the 190 MB rung, stored at Q8_0 instead " +
                "of Q5_1, so its accuracy should track that rung's and only the arithmetic " +
                "differs. Offered for measurement: its throughput on this device is unknown.",
        ),
        "medium-q5" to TierCopy(
            headline = "Sharper accuracy, larger download",
            badges = listOf("90+ languages", "539 MB"),
            body = "Whisper medium: 24 encoder layers at 1024 dims against the 190 MB rung's 12 " +
                "at 768, and the multilingual medium this app has not offered before. " + KEEP_UP_NOTE,
        ),
        "medium-q8" to TierCopy(
            headline = "The same medium, finer quantisation",
            badges = listOf("90+ languages", "823 MB"),
            body = "The same whisper medium as the 539 MB rung — identical depth, dims and " +
                "vocabulary — stored at Q8_0 instead of Q5_0, so the accuracy should match and " +
                "only the arithmetic differs. " + KEEP_UP_NOTE,
        ),
        "ultra" to TierCopy(
            headline = "Large-v3 accuracy, trimmed decoder",
            badges = listOf("90+ languages", "574 MB"),
            body = "Large-v3's own 32-layer encoder with its decoder cut to 4 layers, which is " +
                "why it downloads at about half the size of the full model. " + KEEP_UP_NOTE,
        ),
        "ultra-q8" to TierCopy(
            headline = "The same turbo, finer quantisation",
            badges = listOf("90+ languages", "874 MB"),
            body = "The same large-v3-turbo as the 574 MB rung — same encoder, same 4-layer " +
                "decoder — stored at Q8_0 instead of Q5_0. Its accuracy should match; the " +
                "arithmetic differs. " + KEEP_UP_NOTE,
        ),
        "large-v3" to TierCopy(
            headline = "Highest accuracy, largest download",
            badges = listOf("90+ languages", "1081 MB"),
            body = "Whisper large-v3 at full depth: the 574 MB rung's encoder plus its complete " +
                "32-layer decoder. " + KEEP_UP_NOTE,
        ),
        // 4.0: the gated tier. Only devices that pass the SoC gate AND have both context binaries
        // installed ever see this card, so the copy may speak about "this device" in the present
        // tense. The comparison is OUR OWN before/after on THAT device — same model as Multilingual,
        // different processor — never a claim about another app and never an absolute ("instant",
        // "real-time"), which the app-wide no-speed-claims rule forbids everywhere.
        "npu" to TierCopy(
            headline = "Fastest multilingual",
            // 358 MB = the PAIR (encoder 132,927,488 + decoder 225,316,864). The badge states what
            // the user downloads and stores, not the one file WhisperModel.fileName names.
            badges = listOf("90+ languages", "358 MB"),
            body = "Runs on your phone's AI chip. Same model as Multilingual, much faster on this device.",
        ),
        // 4.1 wrote this card as "Best quality, slower" against the OTHER NPU card, back when no
        // WER existed for any w8a16 variant and the two NPU tiers were offered side by side. Both
        // premises are gone. The owner's on-device A/B (2026-08-29) resolved accuracy — "V3 Turbo
        // is clearly the winner, much more accurate" — and 4.3's one-tier-per-device means the
        // user who sees this card never sees "Multilingual on NPU", so a comparison to it was the
        // exact mistake the pro card fixed in 3.7 (a tier the user cannot see). The comparison is
        // now OUR OWN CPU tier the user would otherwise run, on THIS device, and both halves are
        // measured: encode 1.78 s fixed per commit on the Fold6 against Multilingual's 2.3 s
        // (docs/measurements, 2026-09-02) and ~6 s per 17.6 s chunk on the Tab S10+ (2026-09-09).
        // Owner ruling 2026-09-10: "it's actually the fastest one we have and most accurate".
        // Still no absolute — "fastest" and "most accurate" rank our lineup, not the world.
        "npu-turbo" to TierCopy(
            headline = "Best accuracy, fastest",
            // 1072 MB = the PAIR (encoder 775,831,552 + decoder 295,854,080), same rule as npu's
            // badge: what the user installs, not the one file WhisperModel.fileName names.
            badges = listOf("90+ languages", "1072 MB"),
            body = "Large-v3's own encoder, on your phone's AI chip. The most accurate model this " +
                "app ships, and the fastest on this device — ahead of the 190 MB Multilingual " +
                "model on both counts.",
        ),
    )

    /**
     * Copy for an offered tier id; null for retired or unknown ids (callers fall back).
     *
     * "Offered" includes the gated npu-class tiers (`npu` and `npu-turbo`), which are out of
     * [WhisperCatalog.pickable] by design — copy exists for every tier a chooser can render, and
     * the per-tier gate decides whether each renders.
     */
    fun forId(id: String): TierCopy? = copyById[id]

    /**
     * The tier a fresh install is steered to, from the device's primary language tag (3.7,
     * Workstream H). It is a STEER, never a lock: every card stays tappable and
     * [com.whispereverywhere.ui.onboarding.OnboardingLogic.TIER_SWITCH_HINT] still promises the
     * switch.
     *
     * **4.6 — THE ENGLISH BRANCH IS GONE, because the tier it pointed at is retired.** It read
     * `if (primary == "en") "pro" else "multi"`, and `pro` was the last English-only rung the app
     * offered; the owner's ruling of 2026-09-13 retires it (*"we should really only be showing
     * only multi language models, period"*). A steer at a retired tier is not a steer — it is a
     * card the chooser does not render, so `orderedForLanguageTagFor` would lift nothing to the
     * front and [STEER_BADGE] would appear on no card at all for every English user.
     *
     * **The 3.7 rule this replaces is SATISFIED, not abandoned.** Its point was the Bengali
     * review: never land a user on a tier that is worse for the language they actually speak.
     * With every offered rung multilingual there is no worse-for-your-language rung left to land
     * on, so the rule holds structurally rather than by a branch — and the English user's
     * replacement is the same 190 MB of whisper-small weights with a multilingual vocab head, not
     * a downgrade.
     *
     * The parameter stays, and so does the tag parsing in the callers' contract: this is still
     * "the steer FOR a language", the gated overload still reads the tag's answer, and the next
     * language-specific rung — a previewer-style per-language pack, or a re-offered English tier —
     * has one place to be added. Accepts either separator ("en-US", "en_GB") and any case, because
     * callers pass whatever `Locale.toLanguageTag()` / `Locale.getLanguage()` handed them.
     */
    @Suppress("UNUSED_PARAMETER")
    fun steerIdForLanguageTag(languageTag: String): String = MULTILINGUAL_STEER_ID

    /**
     * The rung every locale is steered to since 4.6 — `multi`, the only rung with a measured
     * throughput verdict and therefore the only one the app is entitled to point at. Deliberately
     * NOT spelled `WhisperCatalog.DEFAULT_MODEL_ID`, even though they agree today: the steer is
     * what a fresh install is POINTED at and the default is what an absent pick FALLS BACK to, and
     * collapsing them would mean the next time either moves, both move silently.
     */
    private const val MULTILINGUAL_STEER_ID = "multi"

    /**
     * [steerIdForLanguageTag] with the gated tiers folded in — and, since 4.1 L9, THE OWNER'S
     * PICK codified.
     *
     * **`npu-turbo` HEADS the steer wherever it is offered (L9 — decision 8 re-specified by
     * measurement).** Decision 8 refused turbo a promotion because its accuracy claim was
     * unproved; the owner's on-device A/B (2026-08-29) resolved it: *"V3 Turbo is clearly the
     * winner — much more accurate, at only about half a second slower."* The condition the old
     * rule named is met, so the steer follows the verdict — for EVERY locale, because
     * large-v3-turbo is multilingual and the accuracy win was measured on the owner's own
     * speech, English included. `pro`-for-English remains the rule only where turbo is not
     * offered: the Bengali-review discipline was about never handing a user a WORSE model for
     * their language, and turbo is not that.
     *
     * **`npu` substitutes for the MULTILINGUAL steer and nothing else, exactly as before, when
     * turbo is absent.** It carries `multi`'s weights on faster silicon, so for the user `multi`
     * was already the right answer for, it is a strictly better one. An English locale keeps
     * `pro` in that state: "the device is fast" is still not a reason to hand someone the less
     * accurate model for their language.
     *
     * **This is a STEER, not a selection — untouched by the pick.** Nothing here writes
     * `prefs.selectedModelId`; both chooser surfaces still require a tap,
     * `WhisperCatalog.DEFAULT_MODEL_ID` stays `pro` and `ModelMigration`'s multilingual target
     * stays `multi`. A gated tier that could become the default by locale alone would be
     * selected on devices whose assets are absent.
     *
     * @param offeredGatedIds the caller's gate answer — the ids of gated tiers this device's
     *        chooser may SHOW. Two producers since 4.2 F6: routing surfaces still pass
     *        `WhisperEverywhereApp.offeredNpuTierIds()` alone (the SoC gate, the QNN probe AND
     *        that tier's own files on disk), while the two chooser surfaces pass the UNION
     *        `offeredNpuTierIds() + fetchableNpuTierIds()` — a DISPLAY/steer set, so a capable
     *        fresh Play install steers to the tier it can fetch before any pair is on disk (the
     *        promotion's condition is capability plus a deliverable, census-measured pack; the
     *        pick still writes nothing until the user taps). `emptySet()` reproduces
     *        [steerIdForLanguageTag] exactly, and so does any set naming neither npu-class tier.
     */
    fun steerIdForLanguageTagFor(languageTag: String, offeredGatedIds: Set<String>): String {
        // The pick: the set is the caller's SHOW set — routing surfaces pass installed AND
        // gate-passing; the chooser surfaces since 4.2 F6 also name fetchable,
        // census-deliverable tiers, where capability plus a measured pack is the promotion's
        // authority (the widened @param above). Everything else is the pre-pick rule, verbatim.
        if ("npu-turbo" in offeredGatedIds) return "npu-turbo"
        val cpuSteer = steerIdForLanguageTag(languageTag)
        return if ("npu" in offeredGatedIds && cpuSteer == "multi") "npu" else cpuSteer
    }

    /**
     * Every offered tier id with the [steerIdForLanguageTag] one FIRST (3.7, Workstream H). Both
     * chooser surfaces render this list, so the steer is one rule rather than two. It is a
     * permutation of [WhisperCatalog.pickable] by construction — a tier this object has never
     * heard of still reaches the user, just not at the top.
     *
     * The ungated contract, unchanged: this is [orderedForLanguageTagFor] with the gate answered
     * with the empty set, which is the answer for every device that cannot run a gated tier.
     * Delegating rather than duplicating is deliberate — two copies of an ordering rule drift, and
     * the one that drifts is always the one nobody is reading.
     */
    fun orderedForLanguageTag(languageTag: String): List<String> =
        orderedForLanguageTagFor(languageTag, emptySet())

    /**
     * [orderedForLanguageTag] over the tiers THIS device can pick — `WhisperCatalog.pickableFor`,
     * so each gated tier is in the lineup exactly where the caller's gate says yes for it.
     *
     * **Three ordering keys since 4.1 L9, and each earns its place.** First the steer
     * ([steerIdForLanguageTagFor] — `npu-turbo` wherever it is offered, per the owner's measured
     * pick); then — ONLY when turbo heads — `npu`, the pick's runner-up, so the two npu-class
     * tiers the A/B compared sit together at the top; then the tier the locale would have been
     * steered to WITHOUT the gate; then catalog order. The language key is still the point it
     * was in 3.7: without it a Bengali user on a capable device would read the English-only
     * tier promoted above the multilingual one it was demoted below, by a change that was
     * supposed to be about silicon — and the npu key is CONDITIONAL on turbo heading for the
     * same discipline, so a turbo-absent lineup is EXACTLY the pre-pick order (npu does not
     * jump `multi`/`pro` on the strength of a verdict that was about turbo). The sort is
     * stable, so every tier no key names keeps the order the catalog declares it in.
     *
     * The result is a permutation of `pickableFor(offeredGatedIds, installedIds)` — of the
     * caller's OWN input list, not of [WhisperCatalog.pickable] — so a gate-passing device never
     * loses a card to a rule written for the ungated lineup.
     *
     * **4.3 — the ordering rules are UNCHANGED IN BODY; the LIST they order got shorter.** The
     * owner's ruling ("only the multilingual v3 turbo" where the NPU can run it) lives entirely in
     * [WhisperCatalog.pickableFor], which this delegates to: on a capable device that list is
     * `npu-turbo` plus whatever is already installed, so the three keys below sort one or two
     * cards instead of four. The keys still earn their place for every OTHER device — the whole
     * non-capable fleet still reads the 3.7 language ordering, and the L9 npu-runner-up key still
     * fires on a device offered `npu` without turbo. Not one key was deleted to make the lineup
     * short: the SET is what shrank, which is why the gate-fail path is byte-identical.
     *
     * @param alsoOfferedIds forwarded verbatim to [WhisperCatalog.pickableFor] — the ids that join
     *        a capable device's one-card lineup anyway: what is already on disk (an existing
     *        install keeps its card) and, on the onboarding surface, the CPU tiers once the one
     *        tier's delivery has failed (`OnboardingLogic.chooserAlsoOfferedIds` — the no-wedge
     *        escape). Defaulted to empty: the ungated delegate and every caller that cannot answer
     *        the question keep exactly the lineup they had.
     */
    fun orderedForLanguageTagFor(
        languageTag: String,
        offeredGatedIds: Set<String>,
        alsoOfferedIds: Set<String> = emptySet(),
    ): List<String> {
        val ids = WhisperCatalog.pickableFor(offeredGatedIds, alsoOfferedIds).map { it.id }
        val steer = steerIdForLanguageTagFor(languageTag, offeredGatedIds)
        val languageSteer = steerIdForLanguageTag(languageTag)
        return ids.sortedBy {
            when {
                it == steer -> 0
                // The pick's runner-up rides directly below the pick — and only then (L9).
                it == "npu" && steer == "npu-turbo" -> 1
                it == languageSteer -> 2
                else -> 3
            }
        }
    }

    /**
     * The chip marking the steered card. Names the REASON — "Default" alone never explained why
     * this card and not the other one, and for a non-English user the catalog default and the
     * right answer are different tiers.
     */
    const val STEER_BADGE = "Best match for your language"
}
