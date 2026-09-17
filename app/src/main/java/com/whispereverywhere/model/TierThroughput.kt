package com.whispereverywhere.model

/**
 * WHO TIMED THIS RUNG — the per-rung throughput record, the switch that authorises a production
 * release, and the one gate that reads them (4.6 Task 3).
 *
 * The owner's instruction this branch serves, verbatim (2026-09-13):
 *
 * > *"We will need an APK for the internal testing track at least because I don't see any other
 * > models but the hundred and ninety megabyte model. I wanna see all the models there so I can
 * > just select between them and try each one."*
 *
 * So 4.6 puts six rungs in the chooser that **nobody has ever timed**, deliberately, and none of
 * them is recommended, defaulted or migrated to (see [WhisperModel.instrument]). This file is the
 * other half of that decision: **offering an unmeasured rung on the internal track is the point;
 * promoting one to production is the defect**, and the two must not be the same act.
 *
 * ### WHY A GATE, AND NOT A JUDGEMENT CALL
 *
 * **Habit is the risk.** 4.5.0 and 4.5.1 were internal-only and 4.5.2 was promoted the same day it
 * was built. Nothing about 4.6 looks different from the outside: the chooser has more cards.
 *
 * **And the previewer hides the failure.** Since 4.4.0 a streaming Zipformer puts words on the
 * floating strip about 0.4 s behind the voice *whatever the finalizer is doing*. A rung that cannot
 * keep up therefore looks FINE — words keep appearing — right up until the typed text is a
 * sentence behind the strip, and then a paragraph. Worse, the one on-screen signal of a growing
 * backlog is DISPLACED while the previewer is armed: `inFlightStripLabel` returns null on
 * `sessionHasLocalPreview`, and the `queue:` diagnostic is stripped from release builds by R8. So
 * on a production build with live words on, a rung that is losing shows **nothing** except late
 * text.
 *
 * That is not a thing a reviewer can catch by reading a diff, which is why it is a gate.
 *
 * ### THE METRIC, AND THE ONE THAT WOULD SHIP A BAD TIER
 *
 * **RTF against audio is NOT the metric.** This app clamps `audio_ctx` to
 * `max(samples/320 + 64, 512)` and that floor binds for every chunk under 8.96 s — which is every
 * VAD-cut chunk in ordinary dictation. So the encoder cost per COMMIT is CONSTANT, the ceiling is
 * COMMITS PER SECOND, and the queue grows iff finalize wall time exceeds the commit floor.
 * **Sparse speech buys no relief**: a one-second utterance costs what a nine-second one costs, so
 * someone who dictates in short bursts pays full price per burst. Any reasoning in terms of
 * RTF-against-audio is how a bad tier ships.
 *
 * The app already has the rule this record operationalises, and it already demands a MEASUREMENT.
 * `CommitCadencePolicy`, verbatim: *"a tier keeps a floor only while its full-segment F is
 * **MEASURED** and `F/floor + m <= 0.70` at saturation."* That rule has had no way to fail a build.
 * Now it has one.
 *
 * ### THE TWO KEYS, and why one would not do
 *
 * Exactly the shape the owner accepted for language clearance (`PackClearanceRecord`):
 *
 * | key | what it is | who moves it | when |
 * |---|---|---|---|
 * | [TierThroughput.verdict] | the EVIDENCE — what was measured, on what, by whom, when | whoever runs the device session | as devices are measured |
 * | [PRODUCTION_PROMOTABLE] | the AUTHORISATION — "this rung may go to the public" | the owner, explicitly | at promotion time |
 *
 * A measurement arriving does not promote anything: a rung could measure well on the Fold6 and the
 * owner could still decline to offer it to a fleet he has not tested. And [state] refuses the
 * reverse mistake, where the switch names a rung whose verdict is missing. That refusal is the
 * whole gate.
 *
 * ### WHAT THIS IS NOT: a switch that removes a rung from the chooser
 *
 * **The obvious implementation is FORBIDDEN.** Filtering [WhisperCatalog.pickable] by this record
 * would destroy the only thing 4.6 exists to do — the owner cannot measure a rung the app will not
 * offer him — and it would do it silently. The precedent is exact and deliberate: the language
 * clearance gate reaches neither the bundle, the catalogue nor the app, and
 * `StreamingPackClearanceTest.theClearanceStateReachesNothingTheAppRuns` proves the negative by
 * reading the sources. `TierThroughputTest.the_gate_reaches_nothing_the_app_runs` does the same
 * here.
 *
 * **The app never asks whether a rung is measured. Only a human promoting a release does.** Every
 * rung in this record is selectable, downloadable and installable on every device, at every RAM,
 * with no threshold hiding it — which is what makes the owner's six-device session possible.
 *
 * ### Where this is read
 *
 * Three places, all of them human: [TierThroughputTest] (which fails the build on a blank verdict,
 * on a switch that outran its evidence, on a new rung with no row, and on an unmeasured rung that
 * has been quietly re-paced), the acceptance sheet's §AN promotion gate
 * (`docs/superpowers/sdd/2026-09-02-431-guards-tts/acceptance.md`), and a release decision.
 * Nothing else, ever.
 */
data class TierThroughput(
    /**
     * The catalogue row this record is about — [WhisperModel.id], so the two cannot drift. Held
     * one-to-one against [WhisperCatalog.pickable] in BOTH directions by the test: a new rung in
     * the chooser with no row fails the build, and so does a row for a rung nobody can select.
     */
    val tierId: String,
    /** Timed, or not — and, either way, the thing that makes it actionable. */
    val verdict: ThroughputVerdict,
)

/**
 * What the typed text actually did over a LONG dictation. **Three answers, not two**, because the
 * middle one is the one a short trial reports as a pass.
 */
enum class KeepUp {
    /** The typed text stayed with the voice for the whole run. The only outcome that clears. */
    KEPT_UP,

    /**
     * It fell behind and then caught up — in a pause. **This does NOT clear**, and the reason is
     * the `audio_ctx` floor: the cost per commit is constant, so a queue that only drains while
     * the user is silent is a queue that grows whenever the user is not. What was measured there
     * is the length of the pauses, not the throughput of the rung. Recording it as a pass is the
     * single easiest way to ship a tier that fails on the first long dictation.
     */
    RECOVERED_ONLY_IN_PAUSES,

    /** It never caught up. The queue grew without bound. */
    NEVER_CAUGHT_UP,
}

/**
 * ONE DEVICE SESSION's evidence about one rung. Everything a promotion decision needs to ask
 * *"measured on what, by whom, and when"* — and nothing derived, so no field here can be computed
 * from a prediction.
 */
data class ThroughputMeasurement(
    /** The device, named well enough to be asked about again. */
    val device: String,
    /**
     * **F** — the full-segment finalize wall time, in seconds: the fixed cost the app pays per
     * COMMIT, which is the quantity `CommitCadencePolicy`'s eligibility rule is written in. Not an
     * RTF, and not a per-second-of-audio figure; see this file's KDoc for why that distinction
     * decides the whole question.
     */
    val finalizeSeconds: Double,
    /**
     * The commit floor [finalizeSeconds] was measured against, from
     * `CommitCadencePolicy.minCommitIntervalMs`.
     *
     * **This is the analogue of the clearance record's `pinnedCommit`, and it is load-bearing for
     * the same reason.** A clearance is granted over BYTES rather than a repository name; a
     * throughput verdict is earned AT A FLOOR rather than about a tier. The app's rule is a ratio
     * (`F/floor + m <= 0.70`), so re-pacing a rung invalidates its verdict while every prose
     * sentence about it still reads true. The test holds this equal to the app's own table, so the
     * verdict must be re-earned rather than inherited.
     */
    val commitFloorMs: Long,
    /**
     * Whether the streaming previewer was ARMED — 2 threads and +169 MB concurrent, outside the
     * whisper lock.
     *
     * **No measurement in this record has ever been taken with it on**, and the reason is not
     * neglect: the one measured row predates the previewer's existence by two months. This field
     * is here so the six-device session can record the axis its predecessor could not have.
     */
    val previewerArmed: Boolean,
    /** What the typed text did. See [KeepUp] — and note that the middle value does not clear. */
    val outcome: KeepUp,
    /** The ISO date. A measurement nobody dated is a measurement nobody can re-do. */
    val measuredOn: String,
    /**
     * Who measured it, BY NAME. *"The owner"* is not a name and the test rejects it, for the same
     * reason the clearance record rejects an unsigned grant: a number nobody is named for is a
     * number nobody can be asked about.
     */
    val measuredBy: String,
    /**
     * Where a reader can go and re-read this number, and **the build it is about** — a finalize
     * time is a fact about a versionCode, not about a model file.
     */
    val evidence: String,
)

/** Whether one rung's throughput is known, and the evidence or the gap behind it. */
sealed interface ThroughputVerdict {

    /**
     * Whether this verdict clears the rung for a PRODUCTION release.
     *
     * **Measured is not the same as cleared.** A rung measured at [KeepUp.NEVER_CAUGHT_UP] has a
     * perfectly good verdict and must not reach production while it is selectable; the honest
     * responses to it are to retire the rung or to keep the build on the internal track. The gate
     * is about the RELEASE, never about whether the measurement happened.
     */
    val clearsProduction: Boolean

    /**
     * **Timed on a device.** The only verdict that can clear, and only when its outcome is
     * [KeepUp.KEPT_UP].
     *
     * @property measurement the device session's own evidence.
     * @property because the reading, short enough to be read at promotion time — and where an
     *   uncertainty remains, this is where it is recorded as **accepted** rather than resolved.
     *   The clearance record's rule applies unchanged: an accepted risk is still a risk, and the
     *   value of this record at promotion time is that it says which is which.
     */
    data class Measured(val measurement: ThroughputMeasurement, val because: String) : ThroughputVerdict {
        override val clearsProduction: Boolean get() = measurement.outcome == KeepUp.KEPT_UP
    }

    /**
     * **Nobody has timed it** — and saying what closes it, because "unmeasured" on its own leaves
     * the owner nothing to do.
     *
     * This is the normal state of a 4.6 instrument and it is **not** a defect. It is the reason the
     * rung is in the chooser at all.
     *
     * @property action what closes this row, naming the comparison it belongs to where it has one.
     */
    data class Unmeasured(val action: String) : ThroughputVerdict {
        override val clearsProduction: Boolean get() = false
    }
}

/**
 * The answer to *"may this release be promoted to production?"* — four states, in the order
 * [TierThroughputRecord.state] checks them. Two are defects and two are not.
 */
sealed interface ThroughputPromotionState {
    /**
     * Every selectable rung is measured, kept up, AND authorised. **Unreachable for the whole life
     * of this branch**, which is the property that makes it worth reporting: it becomes reachable
     * only after a device session, and unreachable again the moment a rung joins the chooser
     * without one.
     */
    data object Promotable : ThroughputPromotionState

    /**
     * **The normal state, and NOT a failure.** Production promotion is not authorised for the
     * rungs named — they are unmeasured, or the owner has not put them in the switch. The internal
     * track is entirely unaffected: every one of these rungs is in the chooser, downloadable on
     * every device, with no RAM threshold hiding it. That is what the build is for.
     *
     * @property missing the selectable rungs the switch does not authorise, in catalogue order.
     */
    data class Withheld(val missing: List<String>) : ThroughputPromotionState

    /**
     * **A defect.** The switch authorises a rung whose verdict does not clear it — unmeasured, or
     * measured and behind — or names a rung that is not selectable at all, which is a stale
     * authorisation left behind by a retired row. This is the state the gate exists to produce: it
     * is what "promoting 4.6 by habit" looks like from inside the build.
     *
     * @property tiers the switch entries with no clearing verdict behind them, in switch order.
     */
    data class Overreached(val tiers: List<String>) : ThroughputPromotionState

    /**
     * **A worse defect, and it outranks the other three.** A rung the chooser offers has no
     * throughput row at all, so the switch cannot be checked against evidence that does not exist.
     * Reported ahead of [Withheld] so an incomplete record can never read as a reassuring "not yet
     * authorised".
     *
     * @property tiers the selectable rungs with no row, in catalogue order.
     */
    data class Unrecorded(val tiers: List<String>) : ThroughputPromotionState
}

/**
 * THE RECORD AND THE SWITCH.
 *
 * **One row carries a measurement and six do not, and that asymmetry is the truthful state of this
 * project's knowledge on 2026-09-16.** The research this branch serves
 * (`docs/superpowers/research/2026-09-13-cpu-tier-upgrade.md`) predicts that medium, turbo and
 * large-v3 all fail on throughput, and that the real lever is quantisation — `Q5_0`/`Q5_1` are the
 * only two quantisations absent from ggml's ARM i8mm repack path and the only two this app has
 * ever shipped, while the build already compiles `+i8mm`. **That prediction is scaled from ONE
 * Fold6 anchor and ONE Tab datapoint, its medium band is three arithmetic derivations rather than a
 * stopwatch, and the owner has six devices and has chosen to measure rather than accept it.**
 *
 * So **no row below is recorded as passing on a prediction, including the research's own.** A
 * predicted verdict in this file would defeat the entire purpose of the session it exists to serve:
 * the owner would be handed the answer he bought six devices to find out.
 */
object TierThroughputRecord {

    /**
     * **THE SWITCH.** The rungs the owner has authorised for a PRODUCTION release.
     *
     * One explicit, committed set; a production promotion requires it to cover every rung the
     * chooser offers. Flipping a rung in here is a DECISION, not a consequence: [state] refuses any
     * entry whose verdict does not clear it, so the switch can never outrun the evidence.
     *
     * **This set does not reach the build, the catalogue or the app.** It has exactly two readers:
     * the test suite and the acceptance sheet. Nothing about the internal track consults it, and
     * every rung in [RECORD] is selectable and downloadable on every device whether it is named
     * here or not.
     *
     * **It names `multi` alone** — the only rung in this app with a measured finalize time. The six
     * rungs 4.6 adds to the chooser are not in it and must not be added by anyone who has not run
     * §AN's long-dictation rows.
     */
    val PRODUCTION_PROMOTABLE: Set<String> = setOf("multi")

    /**
     * **`multi` (small q5_1) — the one measured rung, and the reason it is the default.**
     *
     * F = 2.3 s on the Fold6, from the owner's own 2026-08-20 device session on versionCode 77
     * (3.6.0), taken after the `audio_ctx` floor moved to 512 and with production backends. It is
     * the anchor every other number in the research is scaled from, and it is the only rung in the
     * catalogue that clears the app's own eligibility rule with margin.
     */
    val MULTI = TierThroughput(
        tierId = "multi",
        verdict = ThroughputVerdict.Measured(
            measurement = ThroughputMeasurement(
                device = "Galaxy Z Fold6 (SM8650)",
                finalizeSeconds = 2.3,
                commitFloorMs = 6_000L,
                // The previewer did not exist. See `because`.
                previewerArmed = false,
                outcome = KeepUp.KEPT_UP,
                measuredOn = "2026-08-20",
                measuredBy = "Brandon Slacum",
                evidence = "the owner's own device session of 2026-08-20 on versionCode 77 " +
                    "(3.6.0, the build that shipped the audio_ctx floor at 512), quoted in this " +
                    "repo in three places that agree: CommitCadencePolicy's object KDoc (\"the " +
                    "multi, npu and npu-turbo rows are MEASURED on the Fold6 at vc77 with the " +
                    "512 audio_ctx floor and production backends\"), its MIN_COMMIT_INTERVAL_" +
                    "MULTI_MS constant (\"derived from F = 2.3 s at a 0.70 duty ceiling\"), and " +
                    "docs/superpowers/research/2026-08-27-npu-whisper-turbo-research.md, which " +
                    "names the same session by date. ACCEPTED RESIDUAL: the raw WE-BENCH logcat " +
                    "lines were never pasted back into the repo — the 3.6.0 plan's results table " +
                    "is still its template — so the figure is carried by the policy table and two " +
                    "research documents rather than by a primary log. That is thinner provenance " +
                    "than a clearance URL and is recorded as accepted, not resolved",
            ),
            because = "F = 2.3 s against this rung's own 6 000 ms commit floor is a duty of 0.42 " +
                "(F/floor + m, at the m ~ 0.04 the repo reads while the audio_ctx floor binds) " +
                "against the app's own 0.70 ceiling — a 1.7x margin, and the only rung in the " +
                "catalogue that clears that rule at all. It has also been the shipped " +
                "multilingual tier through every release since, which is the strongest form the " +
                "keep-up observation takes. TWO THINGS THIS DOES NOT ESTABLISH, both accepted " +
                "rather than resolved: (1) it was measured with NO previewer, because the " +
                "previewer did not exist at versionCode 77 — it shipped in 4.4.0 at versionCode " +
                "90 and holds 2 threads and +169 MB whenever armed, so not one number in this " +
                "record describes the configuration most 4.6 sessions will run in; and (2) it is " +
                "ONE device, the one the research calls the fleet's upper bound — the Tab's " +
                "0.48-0.54 duty is DERIVED from this anchor and has never been measured. §AN's " +
                "own long-dictation row has never been run on this rung either",
        ),
    )

    /**
     * **`small-q8` — the decisive one.** The same whisper-small weights as [MULTI], 40% larger, and
     * ON the ARM i8mm repack path that `Q5_1` is absent from. If it is FASTER, the quantisation
     * finding is real and it is the most valuable result the session can produce.
     */
    val SMALL_Q8 = TierThroughput(
        tierId = "small-q8",
        verdict = ThroughputVerdict.Unmeasured(
            action = "THE CHEAPEST DECISIVE TEST IN THE SESSION: §AN's long-dictation row against " +
                "`multi` on the SAME device — same weights, same layers, same dims, only the " +
                "quantisation differs, so the difference is the quantisation. Mind the confound " +
                "recorded in CommitCadencePolicyTest.everyCatalogTierIsNamedExplicitly: this rung " +
                "is paced at 8 000 ms and `multi` at 6 000, so compare WALL-CLOCK lag, not duty",
        ),
    )

    /**
     * **`medium-q5` — the multilingual medium the app has never had.** Its only medium has been
     * `extreme` (medium.en), which spends the entire bill on the one language with the smallest
     * prize. The research puts it at F = 6.0-9.2 s on the Fold6 against a rule that demands
     * F <= 5.3 s at this floor — all three of those derivations, and none of them a measurement.
     */
    val MEDIUM_Q5 = TierThroughput(
        tierId = "medium-q5",
        verdict = ThroughputVerdict.Unmeasured(
            action = "§AN's long-dictation row, and it is the rung the research is most confident " +
                "fails — which is exactly why a measurement is worth having: its F band is three " +
                "arithmetic derivations off the `multi` anchor, not a stopwatch, and the owner " +
                "bought six devices rather than accept it",
        ),
    )

    /**
     * **`medium-q8` — medium on the repack path**, and the rung the research says could actually
     * pass despite being 823 MB. Byte-for-byte the same hyperparameters as [MEDIUM_Q5] off the
     * file's own ggml header, differing only in `ftype`.
     */
    val MEDIUM_Q8 = TierThroughput(
        tierId = "medium-q8",
        verdict = ThroughputVerdict.Unmeasured(
            action = "THE SECOND NAMED COMPARISON: §AN's long-dictation row against `medium-q5` " +
                "on the same device — whether the i8mm repack path rescues a rung that otherwise " +
                "fails. Both are paced at 8 000 ms, so this pair has no floor confound and is the " +
                "cleaner of the two comparisons",
        ),
    )

    /**
     * **`ultra` (large-v3-turbo q5_0) — un-retired in 4.6 with ZERO device minutes.** Nobody has
     * been able to select it since 3.7, so nobody has run large-v3-turbo on the CPU since VAD
     * chunking landed. `CommitCadencePolicy` has called it UNMEASURED since 3.7 in those words and
     * paces it at the 8 s placeholder; the research refutes it by arithmetic rather than by
     * measurement (turbo's whole saving is 28 removed DECODER layers in a workload that is 83-88%
     * encoder, and its encoder IS large-v3's). Arithmetic is exactly what the owner has chosen to
     * test.
     */
    val ULTRA = TierThroughput(
        tierId = "ultra",
        verdict = ThroughputVerdict.Unmeasured(
            action = "§AN's long-dictation row — the first device minutes this rung will ever " +
                "have on the CPU. Do not confuse it with `npu-turbo`, which is the same model on " +
                "the Hexagon and IS measured (F = 1.89 s, Fold6): that number says nothing about " +
                "this one, and the two differ by the entire processor",
        ),
    )

    /** **`ultra-q8` — turbo on the repack path.** [ULTRA]'s own encoder and 4-layer decoder at 874 MB. */
    val ULTRA_Q8 = TierThroughput(
        tierId = "ultra-q8",
        verdict = ThroughputVerdict.Unmeasured(
            action = "§AN's long-dictation row, ideally beside `ultra` on the same device — the " +
                "third quantisation twin, and worth running only if `ultra` is not already hopeless " +
                "on that device",
        ),
    )

    /**
     * **`large-v3` — the accuracy ceiling and the throughput floor.** [ULTRA]'s same 32-layer,
     * 1280-dim encoder plus the complete 32-layer decoder, at 1,081,140,203 bytes: the largest
     * single file this app can be asked to download.
     */
    val LARGE_V3 = TierThroughput(
        tierId = "large-v3",
        verdict = ThroughputVerdict.Unmeasured(
            action = "§AN's long-dictation row. Expect it to lose; record BY HOW MUCH, because " +
                "this is the rung that tells the owner where the wall actually is on each device, " +
                "and finding where it breaks is the stated point of offering it",
        ),
    )

    /**
     * The record — one row per selectable rung, in [WhisperCatalog.pickable]'s own order. The test
     * holds this one-to-one in BOTH directions: a rung that enters the chooser with no row fails
     * the build, and so does a row for a rung nobody can select.
     */
    val RECORD: List<TierThroughput> = listOf(MULTI, SMALL_Q8, MEDIUM_Q5, MEDIUM_Q8, ULTRA, ULTRA_Q8, LARGE_V3)

    /** The record for one rung, or null — which [state] reports as [ThroughputPromotionState.Unrecorded]. */
    fun forTier(tierId: String): TierThroughput? = RECORD.firstOrNull { it.tierId == tierId }

    /**
     * **THE GATE.** Whether a release offering [tiers] may be promoted to production, given the
     * [authorised] switch and the [record].
     *
     * Pure, total, and taking all three inputs as parameters rather than reading the committed ones
     * — so the suite can exercise every state, including the [ThroughputPromotionState.Promotable]
     * cell this branch's own values can never reach. A gate whose only tested value is "no" is not
     * a gate that has been tested.
     *
     * The order of the checks is the order of severity, and it matters: a rung with no row is
     * reported ahead of an unauthorised one, so a missing verdict can never read as a reassuring
     * "not yet authorised".
     */
    fun state(
        authorised: Set<String>,
        tiers: List<String>,
        record: List<TierThroughput> = RECORD,
    ): ThroughputPromotionState {
        val unrecorded = tiers.filter { tier -> record.none { it.tierId == tier } }
        if (unrecorded.isNotEmpty()) return ThroughputPromotionState.Unrecorded(unrecorded)

        val cleared = record
            .filter { it.verdict.clearsProduction }
            .map { it.tierId }
            .toSet()
        // In the switch's own order, because the message names entries the reader has to go delete.
        val overreached = authorised.filter { it !in cleared || it !in tiers }
        if (overreached.isNotEmpty()) return ThroughputPromotionState.Overreached(overreached)

        // In the ladder's order, because the message is a census of what is still unmeasured.
        val missing = tiers.filterNot { it in authorised }
        return if (missing.isEmpty()) ThroughputPromotionState.Promotable else ThroughputPromotionState.Withheld(missing)
    }

    /**
     * [state] over the committed switch and the committed record, for the rungs the chooser offers
     * on every device — the one call a release decision makes, and the one §AN quotes.
     *
     * **The subject is [WhisperCatalog.pickable], and that boundary defends itself.** The two gated
     * NPU tiers are out of it by the same `gated` flag that keeps them out of every download path,
     * and they belong out: they run on the Hexagon, where the `audio_ctx` commit-floor arithmetic
     * this verdict is written in does not describe the cost at all (their mel window is a
     * hard-compiled 30 s QNN graph), and they were promoted under their own ship gate in 4.0/4.1
     * with `npu-turbo`'s F = 1.89 s measured on the Fold6. But nothing here EXEMPTS them by name:
     * un-gate either one, or un-retire a 60 MB tier, and it enters `pickable` and the test fails
     * until somebody records what it does to the typed text.
     *
     * Over the seven rungs 4.6 offers it answers
     * `Withheld([small-q8, medium-q5, medium-q8, ultra, ultra-q8, large-v3])`.
     */
    fun stateOfLadder(
        tiers: List<String> = WhisperCatalog.pickable.map { it.id },
    ): ThroughputPromotionState = state(PRODUCTION_PROMOTABLE, tiers, RECORD)
}
