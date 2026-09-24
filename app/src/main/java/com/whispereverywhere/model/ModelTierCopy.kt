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
 * MULTILINGUAL tier. A card positions itself DIRECTLY or against a tier the same user can still
 * see, and never by a retired tier's ID ([ModelTierCopyTest.no_offered_tier_names_a_retired_one]
 * — the word-anchored id census). Since 4.7 that rule has three stated qualifications, each held
 * by its own assertion rather than by this sentence:
 *
 *  * A Q8_0 card's NAME carries its quantisation (`Multilingual (small, Q8_0)` — the displayName
 *    rides above the headline on both surfaces), and the fact that it is the retired Q5 twin's
 *    own weights lives in the KDoc beside the card, not in the body (4.9.1; until then the body
 *    said "the retired 190 MB Q5_1 model"). The twins are one set of weights, and the Q5 file is
 *    still installed on the devices of everyone who picked it — so the token stays where a user
 *    comparing "the one I had" with this one can still read it, the name
 *    ([ModelTierCopyTest.every_offered_rung_is_the_q8_side_of_a_twin_whose_q5_side_is_retired]
 *    holds the name's token, the KDoc's twin fact, and forbids the twin's ID on the card).
 *  * The id census exempts `large-v3`, which is the retired rung's id AND the upstream checkpoint
 *    family's name — `ultra-q8` IS large-v3-turbo and `npu-turbo` runs large-v3's own encoder,
 *    facts the KDoc beside each card states. What the rule is about is held for that rung by its
 *    badge instead: no offered card names the retired rung's 1081 MB.
 *  * `npu-turbo`'s measured comparison — "ahead of the 190 MB Multilingual model on both counts",
 *    against weights retired on 2026-09-17 — is recorded VERBATIM in the KDoc beside that card
 *    (4.9.1; it was the body until then): both halves of that claim were measured against those
 *    weights on the Fold6 and owner-ruled, and its replacement (`small-q8`) has never been timed
 *    on an NPU-capable device. The plain body names no comparand at all, which is neither a
 *    re-pointing nor a deletion of the measured claim; the open item is still a Fold6 session
 *    that times `small-q8` beside turbo (see the comment on that card).
 *
 * **4.6 — every offered rung is multilingual and the ENGLISH-scope badge rule is unreachable from
 * here.** Owner ruling 2026-09-13; the "English only" arm of that census now applies to no offered
 * tier. It stays because the rule is about a card the user reads, not about today's catalogue, and
 * a re-offered English rung must inherit it. The `90+ languages` arm carries every card there is.
 *
 * **4.7 — the CPU lineup is THREE Q8 rungs, every one measured.** The owner's Q8 ruling of
 * 2026-09-17 (*"Q8 for everything." — "Q5 is definitely off the table."*) retired the four Q5 rungs
 * on the Tab S10+ measurement in `docs/measurements/2026-09-17-tab-cpu-ladder.md`. `small-q8` is the
 * floor for every device and the default; `medium-q8` is the medium tier, recommended above a RAM
 * threshold; `ultra-q8` was the optional top rung — offered for its accuracy, not recommended —
 * and the one [WhisperModel.instrument] left until 4.9. Retired rungs have no card ([forId]
 * answers null), exactly as `pro` has had none since 4.6.
 *
 * **4.9 — THE LADDER READS AS A LADDER, in the owner's words.** Ruling 2026-09-17, after his own
 * dictation on all three: *"We should label each tier … in a simpler way to help users understand
 * them. For small, we say fast — fastest, less accurate. Medium: balanced speed and accuracy. V3
 * turbo: highest accuracy, slightly slower than both other tiers."* The three headlines are those
 * words, made true — and one of them is amended, by controller ruling, where the measurement does
 * not support the word: turbo's card says *"slower than the other two"*, not *"slightly slower"*,
 * because the doc's medians put 4,849 ms per commit at 3.6× medium's 1,341 and 4.0× small's
 * 1,217 — over three times either — and the owner's own report of turbo's drain on the tablet
 * (*"six to maybe nine second"*, his words) sits against the doc's 1.2-1.3 s per-commit medians
 * for the other two (the doc's numbers, not a figure he reported). That is not "slightly". His
 * report is on the card as HIS report, dated, on his tablet — which is the honest version of his
 * sentence. If he wants "slightly" back it is one word.
 *
 * **The no-speed-claims rule is AMENDED, not deleted, and what it still forbids is an ABSOLUTE.**
 * Since 4.7 it forbade any RANK among the three CPU rungs, because none was measured against
 * another on the user's device. 4.9 allows exactly one thing more: a ranking among the three
 * rungs, when the card names the device it was measured on — the tablet — and the date. That is a
 * finding with a document behind it (`docs/measurements/2026-09-17-tab-cpu-ladder.md`: 1,217 /
 * 1,341 / 4,849 ms per commit, one device, one talk, one session), and a headline that ranks
 * beside a body that scopes is the shape the owner asked for. What stays forbidden is "fastest" as
 * a claim about every device, or about the device in the user's hand: a CPU card may not pair a
 * speed word with "every device", "any device" or "this device". The two NPU cards KEEP their
 * measured, owner-ruled "fastest on this device": that claim is true and scoped to silicon this app
 * has benchmarked, and removing a true claim would be the regression. `ModelTierCopyTest` holds
 * every clause.
 *
 * **4.9.1 — THE BODIES ARE PLAIN, AND THE EVIDENCE MOVED INTO THE KDOC BESIDE EACH CARD.** Owner
 * ruling 2026-09-17, on the same tablet session: *"The copy for each one of the local models, I
 * think we could simplify that pretty dramatically. It's too technical for people that don't know
 * anything about it. Our headlines are perfectly fine, and just about everything else doesn't need
 * to be shown."* So: headlines unchanged, badges unchanged, and every body is one to three short
 * sentences a non-technical reader understands — no layer counts, no dims, no quantisation names,
 * no milliseconds, no dates, no tablet, no file names. NOTHING IS LOST: every measurement, every
 * twin-of-the-retired-rung fact and every dated owner report the old bodies carried is now in the
 * comment block beside its card, verbatim, so the reader of the code has what the reader of the
 * card no longer needs. The rules above are RESTATED for plain copy rather than deleted:
 *
 *  * The 4.9 rank still lives in the three HEADLINES (the owner's words, pinned exactly) and the
 *    measurement that backs it is cited beside each card, not on it — `ModelTierCopyTest` reads
 *    this source file and pins that the comment beside every CPU card names the doc and its
 *    median. A CPU body ranks nothing by speed: it may use a plain speed word ("quick"), never a
 *    superlative, and never in the same sentence as an absolute scope ("every device", "this
 *    device"). Accuracy is still the one axis the bodies rank, and they rank it only among the
 *    three plain names — "the light one" / "the all-rounder" / "the most accurate one", which is
 *    whisper's own size order.
 *  * The RAM sentence stays on both floored rungs, as a fit and in the user's units ("Needs at
 *    least 4.5 GB of memory."); small's still ends "Fits every device."; no body anywhere says
 *    "Recommended" — the recommendation is the steer chip's word alone.
 *  * The two NPU cards keep their measured, owner-ruled claims at EXACTLY the scope they had —
 *    "much faster on this device" on `npu` (measured against our own retired CPU tier, never an
 *    absolute, and the body names NO other model: naming one beside the speed claim re-points
 *    the measurement, see that card), "the fastest on this device" and 4.6 T2's chip-scoped
 *    accuracy superlative ("the most accurate model that runs there") on `npu-turbo` — and the
 *    comparands the measurements were made against are in the KDoc, not the body.
 */
object ModelTierCopy {

    /** One tier's card copy: a positioning headline, badge chips, and one honest sentence. */
    data class TierCopy(val headline: String, val badges: List<String>, val body: String)

    /**
     * **The warning every heavy CPU rung carries** (4.6). One string, one place, so five cards
     * could not say it five ways and so a test can pin the wording. Since 4.7 it rendered on ONE
     * card — `ultra-q8`, the rung measured to keep up with no margin — because a measured pass
     * does not warn (a caution on a measured pass teaches the user to ignore cautions), and the
     * remedy it names is still true: `small-q8` and `medium-q8` are smaller Whispers.
     *
     * **Since 4.9 it renders on NO card.** The owner's ruling cleared `ultra-q8` for production
     * and took the instrument flag off it, so the ladder has no rung whose verdict does not
     * clear; turbo's card states the measured shape of its risk in its own sentence instead (no
     * margin on the tablet; on a less capable device the typed text can fall behind, a smaller
     * Whisper is the fix). The constant is kept, pinned, for the next rung that needs it —
     * `ModelTierCopyTest` holds that it renders on every instrument and on nothing else, which
     * with an empty instrument set is "on nothing".
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
     *
     * **T2 — THE REMEDY NAMES ITS AXIS, AND NAMES IT POSITIVELY.** The note read *"a smaller rung
     * is the fix"*, which is unambiguous across whisper sizes and ambiguous across a quantisation
     * twin — and this ladder is three twins. A user on `medium-q8` (823 MB) told to go smaller can
     * land on `medium-q5` (539 MB), which is a smaller FILE carrying the same 24 layers at 1024
     * dims: not a smaller model at all. So the remedy names the ARCHITECTURAL axis instead — a
     * smaller Whisper, one with fewer layers, strictly less work per commit under this app's
     * `audio_ctx` floor. Under that wording a quantisation twin is excluded by construction, in
     * BOTH directions, because a twin is never a smaller Whisper.
     *
     * **What this note must never do — and did between `37d8b7c` and review round 1's B1 — is rank
     * the quantisation axis.** The clause *"not the same Whisper at a finer quantisation"* read as
     * a verdict, because the app defines "finer quantisation" itself, three cards further down this
     * same list, as the `Q8_0` side (`small-q8`, `medium-q8`, `ultra-q8` — the LARGER file). On
     * `medium-q5`'s card it therefore told a user whose typed text had fallen behind that
     * `medium-q8` is not the fix, and on `ultra`'s that `ultra-q8` is not the fix: the two
     * repack-path rungs, and one of the two comparisons the owner's six-device session exists to
     * run. Nothing on that axis is measured on his hardware — `small-q8`'s card says of it that its
     * throughput "is unknown", and the app may not say unknown on one card and not-the-fix on five.
     * The clause also failed at its own purpose: `medium-q5` is the COARSER quantisation, so "at a
     * finer quantisation" never named the mis-steer it was written to prevent, and "a smaller
     * Whisper" alone does. Keep the remedy positive; do not restore a contrast.
     */
    const val KEEP_UP_NOTE: String =
        "This model may not keep up with continuous speech on this device. If the typed text " +
            "falls behind your voice, a smaller Whisper is the fix — one with fewer layers."

    private val copyById: Map<String, TierCopy> = mapOf(
        // 4.6 — `pro`'s card is GONE, because `pro` is retired (owner ruling 2026-09-13: no
        // English-only rungs at all) and a retired tier has no card. `forId` answering null for it
        // is the contract `retired_and_unknown_tiers_have_no_copy` states, and the screens' own
        // fallback row handles the null. Its copy was "Best English accuracy" / "The sharpest
        // on-device English dictation this app ships." — both still true of the file, which is
        // exactly why `pro` is retired rather than `unsupported`: nobody is being told to leave it.
        // 4.7 — `multi`'s card is GONE, because `multi` is retired (the owner's Q8 ruling of
        // 2026-09-17) and a retired tier has no card; so are `medium-q5`'s, `ultra`'s and
        // `large-v3`'s. Same contract as `pro`'s in 4.6: `forId` answers null, the screens' own
        // fallback row handles the null, and nobody on those tiers is told to leave. `multi`'s
        // card was "Everyday accuracy, smallest download" / "The 190 MB model this app has
        // shipped from the start, and the one rung on this list with a measured verdict behind
        // it." — the second sentence stopped being true on 2026-09-17, when three more were.
        //
        // ============================================ 4.7 — THE Q8 LADDER'S CARDS
        //                                              4.9 — LABELLED AS A LADDER
        //                                              4.9.1 — THE BODIES ARE PLAIN
        //
        // Three cards for the three Q8 rungs, every one MEASURED on the owner's Galaxy Tab S10+
        // (docs/measurements/2026-09-17-tab-cpu-ladder.md — one device, one TEDx talk as device
        // audio, threads=4, previewer armed: 1,217 / 1,341 / 4,849 ms per commit). Three rules
        // govern every one of them:
        //
        //  1. **A rung here MAY rank its siblings by speed — in its HEADLINE, as measured on the
        //     tablet, with the measurement cited HERE.** (4.9 allowed the rank and put the scope
        //     on the card; 4.9.1 keeps the rank and moves the scope into this comment block, on
        //     the owner's plain-copy ruling.) The owner asked for the ladder in plain words —
        //     "fastest, less accurate" / "balanced speed and accuracy" / "highest accuracy,
        //     slower" — and the measurement supports the ORDER: it is one device, one talk, one
        //     session, and the arithmetic says the order is a property of this app's workload
        //     (encoder-dominated: `audio_ctx` clamped to at least 512, so cost per commit is
        //     constant) rather than of published benchmark tables. So the headline ranks, the
        //     KDoc beside each card scopes (ModelTierCopyTest reads this file and pins that each
        //     CPU card's comment names the doc and its median), and the BODY ranks nothing by
        //     speed: a plain speed word at most ("quick"), never a superlative, and never in the
        //     same sentence as an absolute — "fastest" about every device, or about the device
        //     in the user's hand, is still what NO card may say.
        //  2. **Accuracy IS rankable and these cards rank it**, because whisper's own size
        //     ordering is not a claim about this app's hardware. The plain bodies rank it by
        //     three plain names — "the light one" / "the all-rounder" / "the most accurate one"
        //     — which IS whisper's size order (small < medium < large-v3-turbo); what each model
        //     IS (size, depth, quantisation) is stated in the comment beside its card.
        //  3. **No card says "offered for measurement", "instrument" or "not recommended" any
        //     more** (4.9): the ladder ships on the owner's word, every rung is an ordinary tier
        //     with a RAM floor, and the RAM badge says what the device can carry.
        //
        // The quantisation stays on the CARD — in the displayName, which rides above the
        // headline on both surfaces ("Multilingual (small, Q8_0)") — because the retired Q5
        // rungs are still on the devices of everyone who picked one on the internal track, and
        // a user comparing "the 190 MB one I had" with "the 264 MB one" must be able to see they
        // are the same weights. Since 4.9.1 the BODY no longer repeats the token; the twin facts
        // are beside each card below. Every hyperparameter quoted below was read off each file's
        // own ggml header on 2026-09-13, so every sentence is checkable.
        //
        // The NPU cards further down KEEP their measured "fastest": that claim is true, owner-
        // ruled, and scoped to silicon this app has benchmarked. Removing a true claim would be
        // the regression.
        "small-q8" to TierCopy(
            // The owner's words, verbatim: "For small, we say fast — fastest, less accurate."
            // True on the tablet (1,217 ms per commit, the lowest of the three) and true of the
            // checkpoint (whisper small is the least accurate of the three); this comment scopes
            // it, the body no longer does (4.9.1).
            headline = "Fastest, less accurate",
            badges = listOf("90+ languages", "264 MB"),
            // THE EVIDENCE (was the body until 4.9.1; moved here verbatim, nothing lost):
            //   Whisper small at Q8_0 — the same weights as the retired 190 MB Q5_1 model. The
            //   fastest of the three on the owner's tablet and the least accurate: 1,217 ms per
            //   commit against medium's 1,341 and turbo's 4,849, measured 2026-09-17
            //   (docs/measurements/2026-09-17-tab-cpu-ladder.md, n=12 chunks, median wallMs;
            //   2.2x faster per commit than the Q5_1 twin on that tablet, 2,618 vs 1,217).
            //   12 encoder layers at 768 dims, read off the file's ggml header 2026-09-13.
            // What the card may NOT say — in this body or the old one — is that its accuracy
            // "matches" the Q5_1 model's. The measurement doc records wallMs only; Q8_0 and
            // Q5_1 are two quantisations of the same weights and nothing in this repo has
            // compared their transcripts. "The light one" names the checkpoint (whisper small,
            // the smallest of the three), which whisper's own size order entitles it to; it is
            // not a claim about the Q5_1 twin. "Quick to respond" is the plain reading of the
            // lowest median of the three — a plain speed word, not a superlative, and not in the
            // sentence that says "every device". "Fits every device", not "Recommended on every
            // device" (4.9 review): this rung's RAM floor is 0, so it FITS every device — but
            // over the 4.5 GB gate the app's steer is medium, and a card one rung under "Our
            // pick" may not call itself the recommendation on that same screen. The
            // recommendation is the steer's chip alone; the RAM floor is a fit, on every card
            // alike.
            body = "The light one. Quick to respond and fine for everyday notes. Fits every device.",
        ),
        "medium-q8" to TierCopy(
            // The owner's words, verbatim: "Medium: balanced speed and accuracy." On the tablet
            // it lands about a tenth slower than small for a model with twice the layers.
            headline = "Balanced speed and accuracy",
            badges = listOf("90+ languages", "823 MB"),
            // THE EVIDENCE (was the body until 4.9.1; moved here verbatim, nothing lost):
            //   Whisper medium at Q8_0: 24 encoder layers at 1024 dims against small's 12 at
            //   768. About a tenth slower than small on the owner's tablet (1,341 ms per commit
            //   against 1,217, measured 2026-09-17), and a more accurate model. Offered where
            //   the device reports at least 4.5 GB of memory.
            //   (docs/measurements/2026-09-17-tab-cpu-ladder.md, n=38 chunks, median wallMs;
            //   6.9x faster per commit than the Q5_0 twin on that tablet, 9,294 vs 1,341.)
            // 4.8.0: the threshold is the owner's 4.5 GB (was 4.7's provisional 5.5 GB). The
            // number here and `medium-q8.minRamBytes` are one fact; ModelTierCopyTest holds them
            // together. "Still quick" is the plain reading of the doc's own 10% (1,341 vs
            // 1,217) — a plain speed word, no rank, no scope needed. "More accurate than the
            // light one", not "much more": whisper's size order earns the comparative, and no
            // transcript comparison exists in this repo to earn the intensifier. "Needs at
            // least 4.5 GB of memory", not "Recommended where": the RAM floor is a fit, and the
            // recommendation is the steer's "Our pick".
            body = "The all-rounder. More accurate than the light one and still quick. " +
                "Needs at least 4.5 GB of memory.",
        ),
        "ultra-q8" to TierCopy(
            // The owner's words were "highest accuracy, slightly slower than both other tiers".
            // CONTROLLER RULING on one word: the doc's medians put 4,849 ms per commit at 3.6×
            // medium's 1,341 and 4.0× small's 1,217 (over three times either), and the owner's
            // own report of turbo's drain — six to nine seconds, his words — sits against the
            // doc's 1.2-1.3 s per-commit medians for the other two (the doc's numbers; he
            // reported no figure for those). So "slightly" is not a sentence the measurement
            // supports. "Slower than the other two" is; his report is recorded below as HIS
            // report, dated, on his tablet. If he wants "slightly" back it is one word.
            //
            // The unscoped accuracy superlative moved here from `large-v3`'s card when that rung
            // was retired; `exactly_one_card_claims_the_top_of_the_accuracy_order` holds that
            // exactly one card carries it — "The most accurate one." is that sentence.
            headline = "Highest accuracy, slower than the other two",
            badges = listOf("90+ languages", "874 MB"),
            // THE EVIDENCE (was the body until 4.9.1; moved here verbatim, nothing lost):
            //   Large-v3-turbo at Q8_0 — large-v3's own 32-layer encoder with a 4-layer decoder:
            //   the most accurate model on this ladder. Slower than the other two on the owner's
            //   tablet (4,849 ms per commit against 1,217 and 1,341, measured 2026-09-17), where
            //   it kept up with no margin to spare; his own report the same day, after dictating
            //   on it: a six to nine second drain, "totally manageable and doable". On a less
            //   capable device expect the typed text to fall behind —
            //   a smaller Whisper is the fix. Offered where the device reports at least 4.5 GB
            //   of memory.
            //   (docs/measurements/2026-09-17-tab-cpu-ladder.md, n=24 chunks, median wallMs
            //   4,849, worst 7,930 against the 8,000 ms floor: 0.99, KEPT_UP_WITHOUT_MARGIN,
            //   cleared for production by ThroughputVerdict.OwnerRuling in TierThroughputRecord.)
            // No KEEP_UP_NOTE since 4.9 (the rung clears production on the owner's ruling and is
            // not an instrument), and since 4.9.1 the measured shape of the risk is on the card
            // in PLAIN words — "It takes longer to catch up after you stop talking, so give it a
            // moment" is the six-to-nine-second drain and the no-margin finding as a user would
            // experience them — because 0.99 of the floor on a flagship is still what the doc
            // says, and a user on a slower phone is owed that sentence before they download
            // 874 MB. The remedy (a smaller Whisper — `medium-q8` or `small-q8`, both offered)
            // is one card up on the same screen, so the body no longer spells it. "Needs at
            // least 4.5 GB of memory", not "Recommended where": a card that tells the user to
            // expect a wait cannot recommend itself on RAM alone — the floor is a fit, and the
            // recommendation is the steer's "Our pick".
            body = "The most accurate one. It takes longer to catch up after you stop talking, " +
                "so give it a moment. Needs at least 4.5 GB of memory.",
        ),
        // 4.0: the gated tier. Only devices that pass the SoC gate AND have both context binaries
        // installed ever see this card, so the copy may speak about "this device" in the present
        // tense. The comparison is OUR OWN before/after on THAT device — same model as Multilingual,
        // different processor — never a claim about another app and never an absolute ("instant",
        // "real-time"), which the app-wide no-speed-claims rule forbids everywhere.
        "npu" to TierCopy(
            headline = "Fastest multilingual",
            // 338 MB = the PAIR (encoder 113,123,776 + decoder 225,298,736 — the v0.63.0 pair; it
            // was 358 MB before 4.15's refresh). The badge states what the user downloads and
            // stores, not the one file WhisperModel.fileName names.
            badges = listOf("90+ languages", "338 MB"),
            // THE EVIDENCE (was the body until 4.9.1; moved here verbatim, nothing lost):
            //   Runs on your phone's AI chip. Same model as Multilingual, much faster on this
            //   device.
            //   Measured on the Fold6: encode 1.78 s fixed per commit on the Hexagon against the
            //   190 MB Multilingual model's 2.3 s on the CPU (docs/measurements, 2026-09-02) —
            //   whisper-small's weights (w8a16) against the same weights at Q5_1.
            // 4.9.1 keeps the measured claim at EXACTLY its scope, in plain words. "Much faster on
            // this device" is the comparative claim the card has carried since 4.0 — scoped to
            // the device in the user's hand, never an absolute, and "fastest" stays in the
            // HEADLINE where it was owner-approved (3.7/4.0) rather than joining the body.
            //
            // **THE BODY NAMES NO COMPARAND, AND NAMES NO OTHER MODEL EITHER — the review of the
            // first 4.9.1 draft is why.** That draft read "the same model as the light one, much
            // faster on this device", and the juxtaposition re-points the measured claim: any
            // reader takes it as "much faster than the light one", i.e. than `small-q8`, on a
            // screen where `small-q8` is one card away (`WhisperCatalog.pickableFor` lists it
            // beside this card on every device offered `npu` without turbo). The measurement was
            // against the 190 MB Q5_1 twin (above), and `small-q8` — the same weights at Q8_0 —
            // has never been timed on an NPU-capable device: its only measurement is the Tab
            // S10+, which has no Hexagon. The repo's own numbers project that comparison the
            // OTHER way: Q8_0 sits on ggml's i8mm repack path and small Q8 ran 2.2x faster than
            // Q5_1 per commit on the Tab (1,217 vs 2,618 ms, docs/measurements/2026-09-17-tab-
            // cpu-ladder.md), and the Tab measured 1.14-1.30x slower than the Fold6 on one chunk
            // (docs/superpowers/research/2026-09-13-cpu-tier-upgrade.md §0.1), so `small-q8` on
            // the Fold6 projects to
            // roughly 0.9-1.1 s per commit against the Hexagon's fixed 1.78 s encode — the
            // AI-chip tier would be the SLOWER of the two. That is the re-pointing the 4.7
            // controller ruling forbade ("re-pointing the comparison ... would restate a measured
            // claim about weights it was not measured against"), reached by sentence shape
            // rather than by a name. So the same-weights fact (`npu` carries whisper-small's
            // weights; `small-q8` is those weights at Q8_0 — true of the checkpoint, no timing
            // needed) lives HERE and not on the card, and the speed sentence stands alone in
            // 4.0's shape with the retired comparand's name gone. It is still NOT "the same
            // accuracy" anywhere: the w8a16 conversion and the Q8_0 file have never had their
            // transcripts compared, and the small-q8 rule above applies. The open item is
            // unchanged: a Fold6 session that times `small-q8` beside the two NPU tiers, after
            // which this body may name what it beats — or the headline may have to move.
            body = "Runs on this phone's AI chip, much faster on this device.",
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
        //
        // **4.7 — the 190 MB Multilingual model this body compares against was RETIRED on
        // 2026-09-17, and the sentence is left VERBATIM, deliberately.** Both halves of the claim
        // were measured against THAT model on the Fold6, and the Q8 twin that replaced it
        // (`small-q8`) has not been timed on any NPU-capable device — its only measurement is the
        // Tab S10+, which has no Hexagon. Re-pointing the comparison at the 264 MB model would
        // restate a measured claim about weights it was not measured against; deleting it would
        // drop a true, owner-ruled claim. So the card still names the model the comparison was
        // made on, and the open item is a Fold6 session that times `small-q8` beside turbo.
        // CONTROLLER RULING (4.7 review): the "ahead of the 190 MB Multilingual model" body is
        // the measured comparison, and it stays VERBATIM until `small-q8` is timed beside turbo
        // on the Fold6. **4.9.1: it stays verbatim HERE**, in the evidence block below, and the
        // plain body names no comparand — which is neither the re-pointing that ruling forbade
        // nor a deletion of the measured claim (both halves are still on the card, at their
        // scope). The Fold6 session is still the open item.
        //
        // **4.6 T2 — THE ACCURACY HALF WAS SCOPED; THE SPEED HALF IS UNTOUCHED.** The card read
        // "Best accuracy, fastest" / "The most accurate model this app ships", and 4.6 falsified
        // the accuracy half by OFFERING `large-v3` — whisper's full checkpoint with its complete
        // 32-layer decoder, against turbo's 4. So the app now ships a more accurate model than
        // this one, and a card that says otherwise is wrong on the day the ladder lands.
        //
        // The fix is a SCOPE, not a deletion, and the distinction matters twice:
        //
        //  * The claim is true of what it is actually about. `npu` and `npu-turbo` are the only
        //    two models that run on the AI chip and turbo is the more accurate of them — the
        //    owner's own A/B (2026-08-29) is exactly that comparison. "The most accurate model
        //    that runs there" is the same sentence with its real subject restored.
        //  * **The SPEED claim is measured, owner-ruled, and stays verbatim.** Deleting a true
        //    claim to satisfy a rule about false ones would be the regression, and the rule 4.6
        //    adds — no CPU rung claims speed — is about rungs nobody has measured. This one has
        //    been measured twice, on two devices, and the owner ruled on it.
        //
        // `large-v3` now carries the unscoped accuracy superlative ("Highest accuracy"), and it
        // is the only card that may: `exactly_one_card_claims_the_top_of_the_accuracy_order`
        // fails the moment a second one does, which is precisely how this defect arrived.
        "npu-turbo" to TierCopy(
            headline = "Best AI-chip accuracy, fastest",
            // 981 MB = the PAIR (encoder 686,112,520 + decoder 295,856,032 — the v0.63.0 pair; it
            // was 1072 MB before 4.15's refresh), same rule as npu's badge: what the user
            // installs, not the one file WhisperModel.fileName names.
            badges = listOf("90+ languages", "981 MB"),
            // THE EVIDENCE (was the body until 4.9.1; moved here verbatim, nothing lost):
            //   Large-v3's own encoder, on your phone's AI chip. The most accurate model that
            //   runs there, and the fastest on this device — ahead of the 190 MB Multilingual
            //   model on both counts.
            //   Accuracy: the owner's on-device A/B, 2026-08-29 — "V3 Turbo is clearly the
            //   winner, much more accurate". Speed: encode 1.78 s fixed per commit on the Fold6
            //   against the 190 MB Multilingual model's 2.3 s (docs/measurements, 2026-09-02)
            //   and ~6 s per 17.6 s chunk on the Tab S10+ (2026-09-09). Owner ruling 2026-09-10:
            //   "it's actually the fastest one we have and most accurate".
            // 4.9.1 keeps BOTH claims at EXACTLY their scope, in plain words. The accuracy half
            // is 4.6 T2's own sentence, "the most accurate model that runs there": the SET it
            // ranks against is the models on the AI chip, stated in the clause that carries the
            // superlative, not borrowed from the opener. The first 4.9.1 draft said "our most
            // accurate model" — an app-wide claim — and the review caught it: `ultra-q8`'s body
            // is "The most accurate one." and the two cards render together whenever a turbo
            // device has `ultra-q8` installed or the CPU tiers join through
            // `OnboardingLogic.chooserAlsoOfferedIds` after a delivery failure, so that draft put
            // two cards in front of one user each claiming the top in plain words — the defect
            // class `exactly_one_card_claims_the_top_of_the_accuracy_order` exists to catch
            // (the checkpoint tie with `ultra-q8`, the same large-v3-turbo on the CPU, makes it
            // not-false, and the census still passed only because the em-dash joined the claim to
            // "AI chip" in one sentence; the census now splits on the dash too). "The fastest on
            // this device" is byte-identical to 4.6's speed half: measured twice, owner-ruled,
            // scoped to the device in the user's hand, never an absolute. "The best choice on
            // this device" is the steer — turbo HEADS the steer wherever it is offered
            // (`steerIdForLanguageTagFor`, the owner's pick) — stated where the user reads it.
            body = "Runs on this phone's AI chip — the most accurate model that runs there, " +
                "and the fastest on this device. The best choice on this device.",
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
     * front and the steer chip would appear on no card at all for every English user.
     *
     * **The 3.7 rule this replaces is SATISFIED, not abandoned.** Its point was the Bengali
     * review: never land a user on a tier that is worse for the language they actually speak.
     * With every offered rung multilingual there is no worse-for-your-language rung left to land
     * on, so the rule holds structurally rather than by a branch — and the English user's
     * replacement is the same whisper-small weights with a multilingual vocab head — since 4.7
     * the 264 MB `small-q8`, those weights at Q8_0 — not a downgrade.
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
     * The rung every locale is steered to — `small-q8` since 4.7 (was `multi` in 4.6): the floor
     * for every device on the owner's Q8 ruling of 2026-09-17, measured to keep up with margin on
     * his tablet (`docs/measurements/2026-09-17-tab-cpu-ladder.md`; `TierThroughputRecord.SMALL_Q8`)
     * and recommended everywhere — the rung the app is entitled to point a fresh install at.
     * Deliberately NOT spelled `WhisperCatalog.DEFAULT_MODEL_ID`, even though they agree today:
     * the steer is what a fresh install is POINTED at and the default is what an absent pick
     * FALLS BACK to, and collapsing them would mean the next time either moves, both move
     * silently.
     */
    private const val MULTILINGUAL_STEER_ID = "small-q8"

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
     * speech, English included. The Bengali-review discipline was about never handing a user a
     * WORSE model for their language, and turbo is not that.
     *
     * **`npu` substitutes for the MULTILINGUAL steer, exactly as before, when turbo is absent.**
     * It carries whisper-small's weights on faster silicon — the same weights as the CPU steer,
     * `multi` in 4.6 and its Q8_0 twin `small-q8` since 4.7 — so for the user the CPU steer was
     * already the right answer for, it is a strictly better one.
     *
     * **4.6 — that substitution now reaches an ENGLISH locale too, and the old rule's own
     * reasoning is what carries it there.** Until 4.6 an English locale kept `pro` in that state:
     * "the device is fast" was not a reason to hand someone the less accurate model for their
     * language. `pro` is retired now, so the English user's CPU rung IS the multilingual small
     * steer — and `npu` is those same weights on the Hexagon. There is no accuracy being traded
     * away because it is the same model. The condition in the body is spelled on
     * [MULTILINGUAL_STEER_ID] rather than on a literal since 4.7, so the steer moving from `multi`
     * to `small-q8` did not silently switch the substitution off; it holds for every locale, which
     * is the ruling's consequence rather than a new rule.
     *
     * **This is a STEER, not a selection — untouched by the pick.** Nothing here writes
     * `prefs.selectedModelId`; both chooser surfaces still require a tap,
     * `WhisperCatalog.DEFAULT_MODEL_ID` is `small-q8` (4.7; the default lives there, not here) and
     * `ModelMigration`'s multilingual target is `small-q8` too. A gated tier that could become the
     * default by locale alone would be selected on devices whose assets are absent.
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
        return if ("npu" in offeredGatedIds && cpuSteer == MULTILINGUAL_STEER_ID) "npu" else cpuSteer
    }

    /**
     * Every offered tier id with the [steerIdForLanguageTag] one FIRST (3.7, Workstream H). Both
     * chooser surfaces render this list, so the steer is one rule rather than two. It is a
     * permutation of [WhisperCatalog.pickable] by construction — a tier this object has never
     * heard of still reaches the user, just not at the top. (Since 4.8.0 both surfaces then pass
     * the steer through `OnboardingLogic.firstRunSteer` — the RAM rule — and the Settings picker
     * re-lifts that answer with `OnboardingLogic.steerFirst`; the language/gate head this list
     * leads with is the pre-RAM answer, which the flow's cut and the picker's lift both honour.)
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
     * steered to WITHOUT the gate; then catalog order. The npu key is CONDITIONAL on turbo
     * heading, so a turbo-absent lineup is EXACTLY the pre-pick order (npu does not jump the CPU
     * rungs on the strength of a verdict that was about turbo). The sort is stable, so every tier
     * no key names keeps the order the catalog declares it in.
     *
     * **4.6 — THE LANGUAGE KEY IS NOW INERT, AND IT STAYS.** Its point in 3.7 was that without it
     * a Bengali user on a capable device would read the English-only tier promoted above the
     * multilingual one it had just been demoted below, by a change that was supposed to be about
     * silicon. With `pro` retired there is no English-only tier in any lineup, and
     * [steerIdForLanguageTag] answers the one CPU steer for every tag — so `languageSteer == steer` whenever
     * the gate is silent, and the key selects nothing the first key did not. **It is a rule about
     * what may not happen, not an optimisation**: the next language-specific rung reaches it again
     * and gets the 3.7 answer without anyone rediscovering the reasoning. Deleting it because
     * today's catalogue cannot reach it is how the Bengali review happens twice.
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
     * The chip marking the steered card — on BOTH chooser surfaces, since the round after 4.8.0.
     *
     * Introduced for the first-run flow (4.8.0), hence the name: it names the first-run steer
     * RULE (`OnboardingLogic.firstRunSteer`), which both surfaces now steer by, and on no branch
     * of that rule is language why the card is steered: over the 4.5 GB gate it is `medium-q8`
     * by RAM and throughput margin, under it `small-q8` by RAM, and on an NPU-capable device the
     * chip. The card already carries the reason where there is one to read — the RAM chip
     * ("Fits your device": a RAM fit, not a recommendation — it lights on every rung whose
     * floor the device meets, turbo included, so it cannot be the recommendation; this chip
     * is), the body's RAM sentence, the NPU tier's own copy — so this chip says only what is
     * true on every branch: this is the app's pick, and the user still taps.
     *
     * **The retired chip, "Best match for your language" (3.7 `STEER_BADGE`), is deleted, not
     * kept.** It named a reason — "Default" alone never explained why this card and not the other
     * one, and for a non-English user the catalog default and the right answer were different
     * tiers — and the reason stopped being true at 4.6, when the last English-only rung retired
     * and the CPU steer became one answer for every locale. The Settings picker rendered it
     * until the same round that made its steer the RAM rule's; nothing read it after that, and
     * a constant whose text no surface may truthfully show is a lie waiting for a reader. Both
     * surfaces are pinned (ChooserSteerWiringPinTest) to render this constant and never that
     * text.
     */
    const val FIRST_RUN_STEER_BADGE = "Our pick"
}
