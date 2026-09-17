package com.whispereverywhere.model

/**
 * WHO TIMED THIS RUNG — the per-rung throughput record, the switch that authorises a production
 * release, and the one gate that reads them (4.6 Task 3; measured and ruled on in 4.7).
 *
 * The owner's instruction 4.6 served, verbatim (2026-09-13):
 *
 * > *"We will need an APK for the internal testing track at least because I don't see any other
 * > models but the hundred and ninety megabyte model. I wanna see all the models there so I can
 * > just select between them and try each one."*
 *
 * So 4.6 put six rungs in the chooser that **nobody had ever timed**, deliberately, and none of
 * them was recommended, defaulted or migrated to (see [WhisperModel.instrument]). This file was the
 * other half of that decision: **offering an unmeasured rung on the internal track is the point;
 * promoting one to production is the defect**, and the two must not be the same act.
 *
 * **4.7 — the session happened.** On 2026-09-17 five rungs were timed on the owner's Galaxy Tab S10+
 * (`docs/measurements/2026-09-17-tab-cpu-ladder.md`) and the owner ruled the same day: *"Q8 for
 * everything." — "Q5 is definitely off the table."* The rows below carry the numbers; the switch is
 * EMPTY, because the ruling was about which rungs to keep, not about production — *"the rest of
 * the testing now will be to prove the accuracy of the small and medium model … before we actually
 * give it a go."* Two of the six 4.6 instruments were retired untimed by that ruling and keep an
 * `Unmeasured` row that says so.
 *
 * ### WHY A GATE, AND NOT A JUDGEMENT CALL
 *
 * **Habit is the risk.** 4.5.0 and 4.5.1 were internal-only and 4.5.2 was promoted the same day it
 * was built. Nothing about 4.6 or 4.7 looks different from the outside: the chooser has different
 * cards.
 *
 * **And the previewer hides the failure.** Since 4.4.0 a streaming Zipformer puts words on the
 * floating strip about 0.4 s behind the voice *whatever the finalizer is doing*. A rung that cannot
 * keep up therefore looks FINE — words keep appearing — right up until the typed text is a
 * sentence behind the strip, and then a paragraph. Worse, the one on-screen signal of a growing
 * backlog is DISPLACED while the previewer is armed: `inFlightStripLabel` returns null on
 * `sessionHasLocalPreview`, and the `queue:` diagnostic is stripped from release builds by R8. So
 * on a production build with live words on, a rung that is losing shows **nothing** except late
 * text. The 2026-09-17 session saw exactly this shape on `medium-q5`: the previewer kept painting
 * while the finalizer's chunks lengthened to 9-14 s.
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
 * RTF-against-audio is how a bad tier ships. The measurement doc reads its logcat lines in exactly
 * these terms: `wallMs` of one `whisper_full` call per VAD chunk, against the rung's floor.
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
 * A measurement arriving does not promote anything — 4.7 is the proof: three rungs measured, none
 * authorised, because the owner is running the accuracy pass first. And [state] refuses the
 * reverse mistake, where the switch names a rung whose verdict is missing or does not clear. That
 * refusal is the whole gate.
 *
 * ### WHAT THIS IS NOT: a switch that removes a rung from the chooser
 *
 * **The obvious implementation is FORBIDDEN.** Filtering [WhisperCatalog.pickable] by this record
 * would destroy the only thing 4.6 existed to do — the owner cannot measure a rung the app will not
 * offer him — and it would do it silently. The precedent is exact and deliberate: the language
 * clearance gate reaches neither the bundle, the catalogue nor the app, and
 * `StreamingPackClearanceTest.theClearanceStateReachesNothingTheAppRuns` proves the negative by
 * reading the sources. `TierThroughputTest.the_gate_reaches_nothing_the_app_runs` does the same
 * here. The rungs that LEFT the chooser in 4.7 left by the owner's ruling, through
 * [WhisperModel.retired] — not through this record.
 *
 * **The app never asks whether a rung is measured. Only a human promoting a release does.** Every
 * pickable rung in this record is selectable, downloadable and installable on every device that is
 * offered the CPU ladder at all.
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
     * The catalogue row this record is about — [WhisperModel.id], so the two cannot drift.
     *
     * **Held one-to-one against [WhisperCatalog.pickable] in the STRICT direction by the test:
     * every pickable rung has a row**, so a new rung in the chooser with no row fails the build.
     * The other direction is deliberately looser since 4.7: **a retired rung may KEEP its row**,
     * because evidence is never deleted — `multi`'s Fold6 anchor and `medium-q5`'s NEVER_CAUGHT_UP
     * are the two facts the Q8 ruling was made on, and a record that forgot them the day the
     * ruling landed would be a record of nothing. What the test still refuses is a row for an id
     * the catalogue cannot resolve at all, or for a gated tier (which is measured under its own
     * ship gate, on the Hexagon, where this arithmetic does not describe the cost).
     */
    val tierId: String,
    /** Timed, or not — and, either way, the thing that makes it actionable. */
    val verdict: ThroughputVerdict,
)

/**
 * What the typed text actually did over a LONG dictation. **Four answers, not two**, because the
 * two middle ones are the ones a short trial reports as a pass.
 */
enum class KeepUp {
    /** The typed text stayed with the voice for the whole run, with margin. The only outcome that clears. */
    KEPT_UP,

    /**
     * The typed text stayed with the voice for the whole run — but the worst commit reached at
     * least 0.90 of the rung's floor, on a FLAGSHIP. **This does NOT clear**, and the reason is
     * arithmetic rather than caution: the floor is the line past which the queue grows, so a run
     * whose worst commit sits at 0.99 of it has measured the flagship's headroom, not the rung's
     * — and the margin that would survive a slower device, a hotter one, or a longer chunk is
     * simply not there. Recorded on 2026-09-17 for `ultra-q8` (worst 7,930 ms against 8,000 on a
     * Dimensity 9300+). The honest responses are the same as for the value below: keep the rung
     * an instrument, or retire it. Never promote it on this.
     */
    KEPT_UP_WITHOUT_MARGIN,

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
     * decides the whole question. For the 2026-09-17 rows it is the MEDIAN `wallMs` of the rung's
     * sample, divided by 1000; the worst commit is in `because`.
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
     * The one measured row of 4.6 predated the previewer by two months, so this field was added
     * for the axis the device session would have to record. **Every 2026-09-17 row was taken with
     * it armed**, which is the configuration most sessions run in and the harder one.
     */
    val previewerArmed: Boolean,
    /** What the typed text did. See [KeepUp] — and note that the two middle values do not clear. */
    val outcome: KeepUp,
    /** The ISO date. A measurement nobody dated is a measurement nobody can re-do. */
    val measuredOn: String,
    /**
     * Who measured it, BY NAME. *"The owner"* is not a name and the test rejects it, for the same
     * reason the clearance record rejects an unsigned grant: a number nobody is named for is a
     * number nobody can be asked about. When the person at the keyboard was not the person who
     * owns the device, say both — the 2026-09-17 rows do.
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
     * responses to it are to retire the rung or to keep the build on the internal track. So does
     * a rung at [KeepUp.KEPT_UP_WITHOUT_MARGIN]. The gate is about the RELEASE, never about
     * whether the measurement happened.
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
     * In 4.6 this was the normal state of an instrument and **not** a defect. Since 4.7 the two
     * rows that carry it are RETIRED rungs, and their `action` records that the ruling closed
     * them without a number rather than pretending one is coming.
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
 *
 * **DO NOT RENAME THIS TO `ThroughputPromotionState`.** It was called that for one commit and it
 * broke a live gate: `StreamingPackClearanceTest.theClearanceStateReachesNothingTheAppRuns` scans
 * every file under `app/src/main/java` for a live reference to the SUBSTRING `PromotionState`, to
 * prove the language-clearance state reaches nothing the app runs — and
 * `ThroughputPromotionState` contains it, so this file tripped a legal gate from across the
 * codebase. Observed, not theorised: 2827 tests, one failure, at
 * `StreamingPackClearanceTest.kt:845`.
 *
 * The collision was fixed HERE rather than by loosening that scan to a word boundary. A coarse
 * needle guarding a store promotion is the safe direction for that test to be wrong in, and the
 * cost of keeping it coarse is exactly this: one name in one new file. The four state names
 * (`Promotable`, `Withheld`, `Overreached`, `Unrecorded`) deliberately match
 * [com.whispereverywhere.transcription.stream.PromotionState]'s, because the two gates ARE the
 * same shape and a reader should see that — it is only the umbrella type that had to differ.
 */
sealed interface ThroughputGateState {
    /**
     * Every selectable rung is measured, kept up, AND authorised. **Unreachable on the committed
     * values so far**, which is the property that makes it worth reporting: it becomes reachable
     * only after the owner puts a measured, clearing rung in the switch, and unreachable again the
     * moment a rung joins the chooser without one.
     */
    data object Promotable : ThroughputGateState

    /**
     * **The normal state, and NOT a failure.** Production promotion is not authorised for the
     * rungs named — they are unmeasured, measured without clearing, or the owner has not put them
     * in the switch. The internal track is entirely unaffected: every one of these rungs is in the
     * chooser and downloadable. That is what the build is for.
     *
     * @property missing the selectable rungs the switch does not authorise, in catalogue order.
     */
    data class Withheld(val missing: List<String>) : ThroughputGateState

    /**
     * **A defect.** The switch authorises a rung whose verdict does not clear it — unmeasured, or
     * measured and behind, or measured without margin — or names a rung that is not selectable at
     * all, which is a stale authorisation left behind by a retired row. This is the state the gate
     * exists to produce: it is what "promoting by habit" looks like from inside the build.
     *
     * @property tiers the switch entries with no clearing verdict behind them, in switch order.
     */
    data class Overreached(val tiers: List<String>) : ThroughputGateState

    /**
     * **A worse defect, and it outranks the other three.** A rung the chooser offers has no
     * throughput row at all, so the switch cannot be checked against evidence that does not exist.
     * Reported ahead of [Withheld] so an incomplete record can never read as a reassuring "not yet
     * authorised".
     *
     * @property tiers the selectable rungs with no row, in catalogue order.
     */
    data class Unrecorded(val tiers: List<String>) : ThroughputGateState
}

/**
 * THE RECORD AND THE SWITCH.
 *
 * **Five rows carry a measurement and two do not, and that is the truthful state of this
 * project's knowledge on 2026-09-17.** The research 4.6 served
 * (`docs/superpowers/research/2026-09-13-cpu-tier-upgrade.md`) predicted that the real lever was
 * quantisation — `Q5_0`/`Q5_1` are the only two quantisations absent from ggml's ARM i8mm repack
 * path and the only two this app had ever shipped, while the build already compiles `+i8mm` — and
 * the 2026-09-17 session on the Tab S10+ measured it: small Q8_0 2.2x faster per commit than small
 * Q5_1, medium Q8_0 6.9x faster than medium Q5_0. The medium band the research derived at
 * F = 6.0-9.2 s came in at 9.3 s median for Q5_0 and 1.3 s for Q8_0.
 *
 * **What the record still does not know, stated plainly:** one device (a 12 GB Dimensity 9300+
 * flagship), one talk, one session per rung, minutes long, previewer armed throughout. No number
 * here describes a 6 GB phone. That is why [PRODUCTION_PROMOTABLE] is empty, and why the owner's
 * accuracy pass comes before any of it is promoted.
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
     * every pickable rung in [RECORD] is selectable and downloadable whether it is named here or
     * not.
     *
     * **It is EMPTY since 4.7.** The only rung it ever named (`multi`) was retired by the Q8 ruling
     * of 2026-09-17, and a switch that names a rung nobody can select is the stale-authorisation
     * defect [state] reports as Overreached. The three Q8 rungs are MEASURED and not yet
     * AUTHORISED, on the owner's own words: *"the rest of the testing now will be to prove the
     * accuracy of the small and medium model … before we actually give it a go."* This switch
     * flips only on his word, after that pass — and `ultra-q8` cannot enter it at all while its
     * verdict is [KeepUp.KEPT_UP_WITHOUT_MARGIN].
     */
    val PRODUCTION_PROMOTABLE: Set<String> = emptySet()

    /**
     * **`multi` (small Q5_1) — the 4.6 anchor, RETIRED on 2026-09-17, its evidence kept.**
     *
     * F = 2.3 s on the Fold6, from the owner's own 2026-08-20 device session on versionCode 77
     * (3.6.0), taken after the `audio_ctx` floor moved to 512 and with production backends. It was
     * the anchor every number in the research was scaled from and the only measured rung in the
     * catalogue until the Tab session — which then timed it beside its Q8_0 twin on one device
     * and found the twin 2.2x faster. The Fold6 provenance stays exactly as recorded; the Tab
     * number is added to `because` so a reader finds both.
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
                    "than a clearance URL and is recorded as accepted, not resolved. A SECOND " +
                    "measurement of this rung, with a primary log, is in " +
                    "docs/measurements/2026-09-17-tab-cpu-ladder.md (Tab S10+, 07:11-07:14, " +
                    "versionCode 95 + b54fc1b): n=20, median 2,618 ms, worst 3,802, " +
                    "previewer armed",
            ),
            because = "F = 2.3 s against this rung's own 6 000 ms commit floor is a duty of 0.42 " +
                "(F/floor + m, at the m ~ 0.04 the repo reads while the audio_ctx floor binds) " +
                "against the app's own 0.70 ceiling — a 1.7x margin, and until 2026-09-17 the " +
                "only rung in the catalogue that cleared that rule at all. It was the shipped " +
                "multilingual tier through every release from 3.7 to 4.6. ON THE TAB S10+ " +
                "(2026-09-17, previewer ARMED, the same talk as every other row): median " +
                "2,618 ms per commit, worst 3,802 — 0.63 of its 6 000 ms floor — KEPT_UP. Its " +
                "Q8_0 twin, small-q8, measured 1,217 ms in the same session on the same device, " +
                "which is the number the owner retired this rung on. TWO THINGS THE FOLD6 ROW " +
                "DOES NOT ESTABLISH, both accepted rather than resolved: (1) it was measured " +
                "with NO previewer, because the previewer did not exist at versionCode 77 — it " +
                "shipped in 4.4.0 at versionCode 90 and holds 2 threads and +169 MB whenever " +
                "armed; the Tab row is the first with it on; and (2) it is ONE device, the one " +
                "the research calls the fleet's upper bound. RETIRED 2026-09-17 by the owner's " +
                "Q8 ruling; the row stays because evidence is never deleted",
        ),
    )

    /**
     * **`small-q8` — the decisive one, decided.** The same whisper-small weights as [MULTI], 40%
     * larger, ON the ARM i8mm repack path that `Q5_1` is absent from. It was FASTER — 2.2x per
     * commit on one device in one session — and that is the most valuable result the session
     * produced. THE DEFAULT and the floor for every device since 4.7.
     */
    val SMALL_Q8 = TierThroughput(
        tierId = "small-q8",
        verdict = ThroughputVerdict.Measured(
            measurement = ThroughputMeasurement(
                device = "Galaxy Tab S10+ (SM-X828U, Dimensity 9300+)",
                // Median wallMs 1,217 over n=12 chunks.
                finalizeSeconds = 1.217,
                // The row this rung is paced on in CommitCadencePolicy — the LARGE row via
                // `else`, where 4.6 put every instrument. See `because` for the 6 000 reading.
                commitFloorMs = 8_000L,
                previewerArmed = true,
                outcome = KeepUp.KEPT_UP,
                measuredOn = "2026-09-17",
                measuredBy = "Claude Fable 5.1, over remote adb on Brandon Slacum's tablet at his instruction",
                evidence = "docs/measurements/2026-09-17-tab-cpu-ladder.md, section `small-q8` " +
                    "(logcat 07:15:42-07:17:42, WE-DIAG `finalize:` lines), on versionCode 95 " +
                    "(4.6.0) + commit b54fc1b (the native finalize timing line), sideloaded; " +
                    "device audio from one TEDx talk captured through the app's own screen-share " +
                    "consent; threads=4; English previewer ARMED",
            ),
            because = "n=12 chunks: median wallMs 1,217, mean 1,324, worst 1,993, ctx=512 median " +
                "1,116. THE DUTY ARITHMETIC: the app paces this rung at 8 000 ms (the LARGE row, " +
                "where 4.6 placed it unmeasured), so the worst commit is 0.25 of the floor and " +
                "F/floor + m is ~0.19 against the 0.70 rule; the measurement doc reads it " +
                "against the 6 000 ms small-rung floor instead, which gives 0.33 worst-case and " +
                "~0.24 duty — it clears with margin at EITHER floor, and moving it onto the " +
                "6 000 row is a cadence decision this record does not make. Against `multi` in " +
                "the same session (2,618 ms) it is 2.2x faster per commit for the same weights " +
                "and +74 MB. The typed text stayed with the voice throughout: KEPT_UP. CAVEATS, " +
                "accepted not resolved: ONE device, a 12 GB Dimensity 9300+ flagship; ONE talk, " +
                "one speaker, minutes long; the previewer was ARMED throughout (2 threads, " +
                "+169 MB), which is the harder configuration; the smallest sample of the five " +
                "rungs (n=12). No number here describes a 6 GB phone",
        ),
    )

    /**
     * **`medium-q5` — the multilingual medium the app never had, and the rung that NEVER CAUGHT
     * UP.** Its only medium had been `extreme` (medium.en). The research put it at F = 6.0-9.2 s
     * on the Fold6 by derivation; the Tab measured 9.3 s median. RETIRED 2026-09-17; the row is
     * kept because this number is half of the ruling.
     */
    val MEDIUM_Q5 = TierThroughput(
        tierId = "medium-q5",
        verdict = ThroughputVerdict.Measured(
            measurement = ThroughputMeasurement(
                device = "Galaxy Tab S10+ (SM-X828U, Dimensity 9300+)",
                // Median wallMs 9,294 over n=20 chunks.
                finalizeSeconds = 9.294,
                commitFloorMs = 8_000L,
                previewerArmed = true,
                outcome = KeepUp.NEVER_CAUGHT_UP,
                measuredOn = "2026-09-17",
                measuredBy = "Claude Fable 5.1, over remote adb on Brandon Slacum's tablet at his instruction",
                evidence = "docs/measurements/2026-09-17-tab-cpu-ladder.md, section `medium-q5` " +
                    "(logcat 08:30:01-08:33:33, WE-DIAG `finalize:` lines), on versionCode 95 " +
                    "(4.6.0) + commit b54fc1b, sideloaded; device audio from the same TEDx talk; " +
                    "threads=4; English previewer ARMED; YouTube in a picture-in-picture window " +
                    "during this run (hardware video decode — a small CPU share, stated in the doc)",
            ),
            because = "n=20 chunks: median wallMs 9,294, mean 9,202, worst 11,782, ctx=512 median " +
                "7,575 — 1.47x the 8 000 ms floor at the worst commit and over it at the median. " +
                "THE RUNAWAY REGIME, observed: once wallMs exceeded the floor the endpointer " +
                "handed over longer chunks (audioMs 9-14 s) and each cost more, so the queue " +
                "grew without bound while the previewer kept painting words on the strip. " +
                "NEVER_CAUGHT_UP. The same weights at Q8_0 (medium-q8) finalized in 1,341 ms in " +
                "the same session — 6.9x — which is the quantisation finding stated as a " +
                "measurement. CAVEATS: one device, one talk, previewer armed, PiP video during " +
                "this run (does not account for a 6-8x gap). RETIRED 2026-09-17 by the Q8 " +
                "ruling; the row stays as the evidence it was made on",
        ),
    )

    /**
     * **`medium-q8` — medium on the repack path, and it passed.** Byte-for-byte the same
     * hyperparameters as [MEDIUM_Q5] off the file's own ggml header, differing only in `ftype`.
     * THE MEDIUM TIER since 4.7, recommended above a provisional 5.5 GB RAM threshold.
     */
    val MEDIUM_Q8 = TierThroughput(
        tierId = "medium-q8",
        verdict = ThroughputVerdict.Measured(
            measurement = ThroughputMeasurement(
                device = "Galaxy Tab S10+ (SM-X828U, Dimensity 9300+)",
                // Median wallMs 1,341 over n=38 chunks — the largest sample of the five.
                finalizeSeconds = 1.341,
                commitFloorMs = 8_000L,
                previewerArmed = true,
                outcome = KeepUp.KEPT_UP,
                measuredOn = "2026-09-17",
                measuredBy = "Claude Fable 5.1, over remote adb on Brandon Slacum's tablet at his instruction",
                evidence = "docs/measurements/2026-09-17-tab-cpu-ladder.md, section `medium-q8` " +
                    "(logcat 07:04:52-07:11:20, WE-DIAG `finalize:` lines), on versionCode 95 " +
                    "(4.6.0) + commit b54fc1b, sideloaded; device audio from the same TEDx talk; " +
                    "threads=4; English previewer ARMED",
            ),
            because = "n=38 chunks: median wallMs 1,341, mean 1,470, worst 2,508, ctx=512 median " +
                "1,140. THE DUTY ARITHMETIC: worst commit 0.31 of the 8 000 ms floor; F/floor + m " +
                "~0.21 at the median against the 0.70 rule — clears with margin, and lands within " +
                "10% of small-q8 (1,217) on this tablet despite 24 layers at 1024 against 12 at " +
                "768. The typed text stayed with the voice throughout: KEPT_UP. CAVEATS, accepted " +
                "not resolved: ONE device, a 12 GB flagship — the RAM threshold this rung is " +
                "recommended above (5.5e9) is the repo's `extreme` precedent, NOT a measurement, " +
                "and the owner's weakest-device run is the open item; ONE talk; previewer ARMED " +
                "throughout. An 823 MB model on a 6 GB phone has not been timed by anyone",
        ),
    )

    /**
     * **`ultra` (large-v3-turbo Q5_0) — un-retired in 4.6 with zero device minutes, and RETIRED
     * AGAIN in 4.7 with zero device minutes.** The session did not time it: its Q8_0 twin kept up
     * on the tablet with no margin, and the only Q5_0 rung timed (`medium-q5`) never caught up, so
     * the owner took every Q5 rung off the table without running this one.
     */
    val ULTRA = TierThroughput(
        tierId = "ultra",
        verdict = ThroughputVerdict.Unmeasured(
            action = "RETIRED UNMEASURED on 2026-09-17 by the owner's Q8 ruling (\"Q5 is " +
                "definitely off the table\"), per docs/measurements/2026-09-17-tab-cpu-ladder.md, " +
                "which names it as one of the two rungs the session did not time. Nothing closes " +
                "this row now — it is not pickable, so the gate does not ask about it — and it " +
                "stays so that the CommitCadencePolicy census and this record agree the rung " +
                "was never timed on the CPU. Do not confuse it with `npu-turbo`, which is the " +
                "same model on the Hexagon and IS measured (F = 1.89 s, Fold6)",
        ),
    )

    /**
     * **`ultra-q8` — turbo on the repack path: it kept up, with NO margin.** [ULTRA]'s own encoder
     * and 4-layer decoder at 874 MB. THE OPTIONAL TOP RUNG since 4.7 — offered for its accuracy
     * on the owner's words, never recommended — and the one instrument left, because this verdict
     * does not clear.
     */
    val ULTRA_Q8 = TierThroughput(
        tierId = "ultra-q8",
        verdict = ThroughputVerdict.Measured(
            measurement = ThroughputMeasurement(
                device = "Galaxy Tab S10+ (SM-X828U, Dimensity 9300+)",
                // Median wallMs 4,849 over n=24 chunks.
                finalizeSeconds = 4.849,
                commitFloorMs = 8_000L,
                previewerArmed = true,
                outcome = KeepUp.KEPT_UP_WITHOUT_MARGIN,
                measuredOn = "2026-09-17",
                measuredBy = "Claude Fable 5.1, over remote adb on Brandon Slacum's tablet at his instruction",
                evidence = "docs/measurements/2026-09-17-tab-cpu-ladder.md, section `ultra-q8` " +
                    "(logcat 06:54:55-06:58:46, WE-DIAG `finalize:` lines), on versionCode 95 " +
                    "(4.6.0) + commit b54fc1b, sideloaded; device audio from the same TEDx talk; " +
                    "threads=4; English previewer ARMED",
            ),
            because = "n=24 chunks: median wallMs 4,849, mean 5,194, worst 7,930, ctx=512 median " +
                "4,659. THE DUTY ARITHMETIC: the worst commit is 0.99 of the 8 000 ms floor — " +
                "one long chunk (audio_ctx 756, 13.8 s of audio) came within 70 ms of the line " +
                "past which the queue grows — and F/floor + m at the median is ~0.65 against " +
                "the 0.70 rule, which is inside it by five points on a Dimensity 9300+ with " +
                "nothing else running. The typed text stayed with the voice for the whole run, " +
                "so it is not RECOVERED_ONLY_IN_PAUSES and not NEVER_CAUGHT_UP; it is " +
                "KEPT_UP_WITHOUT_MARGIN, and that does not clear production, because the margin " +
                "that would survive a slower SoC, thermal throttling, or a run of long chunks is " +
                "not there. Offered as the optional top rung on the owner's ruling (\"it's " +
                "doable, it's actually workable\"), not advocated. CAVEATS: one device, a " +
                "flagship — on anything slower this rung is expected to fall behind; one talk; " +
                "previewer armed throughout",
        ),
    )

    /**
     * **`large-v3` — the accuracy ceiling and the throughput floor, RETIRED UNMEASURED.** [ULTRA]'s
     * same 32-layer, 1280-dim encoder plus the complete 32-layer decoder, at 1,081,140,203 bytes:
     * the largest single file this app can be asked to download, and a Q5_0 rung on the day the
     * owner ruled Q5 out.
     */
    val LARGE_V3 = TierThroughput(
        tierId = "large-v3",
        verdict = ThroughputVerdict.Unmeasured(
            action = "RETIRED UNMEASURED on 2026-09-17 by the owner's Q8 ruling (\"Q5 is " +
                "definitely off the table\"), per docs/measurements/2026-09-17-tab-cpu-ladder.md, " +
                "which names it as one of the two rungs the session did not time. Its Q8_0 twin " +
                "(ultra-q8, the same encoder with a 4-layer decoder) kept up on the tablet with " +
                "no margin, so a rung with eight times that decoder at a coarser quantisation " +
                "was not worth a run. Not pickable, so the gate does not ask about it; the row " +
                "stays so the record and the catalogue tell the same story about why",
        ),
    )

    /**
     * The record — one row per CPU rung the catalogue has ever offered on the ladder, in
     * [WhisperCatalog.entries]' own order. The test holds the STRICT direction: every rung in
     * [WhisperCatalog.pickable] has a row here, so a rung that enters the chooser with no row
     * fails the build. Retired rungs keep their rows (see [TierThroughput.tierId]); a row for an
     * id the catalogue cannot resolve, or for a gated tier, still fails.
     */
    val RECORD: List<TierThroughput> = listOf(MULTI, SMALL_Q8, MEDIUM_Q5, MEDIUM_Q8, ULTRA, ULTRA_Q8, LARGE_V3)

    /** The record for one rung, or null — which [state] reports as [ThroughputGateState.Unrecorded]. */
    fun forTier(tierId: String): TierThroughput? = RECORD.firstOrNull { it.tierId == tierId }

    /**
     * **THE GATE.** Whether a release offering [tiers] may be promoted to production, given the
     * [authorised] switch and the [record].
     *
     * Pure, total, and taking all three inputs as parameters rather than reading the committed ones
     * — so the suite can exercise every state, including the [ThroughputGateState.Promotable]
     * cell the committed values do not reach. A gate whose only tested value is "no" is not a gate
     * that has been tested.
     *
     * The order of the checks is the order of severity, and it matters: a rung with no row is
     * reported ahead of an unauthorised one, so a missing verdict can never read as a reassuring
     * "not yet authorised".
     */
    fun state(
        authorised: Set<String>,
        tiers: List<String>,
        record: List<TierThroughput> = RECORD,
    ): ThroughputGateState {
        val unrecorded = tiers.filter { tier -> record.none { it.tierId == tier } }
        if (unrecorded.isNotEmpty()) return ThroughputGateState.Unrecorded(unrecorded)

        val cleared = record
            .filter { it.verdict.clearsProduction }
            .map { it.tierId }
            .toSet()
        // In the switch's own order, because the message names entries the reader has to go delete.
        val overreached = authorised.filter { it !in cleared || it !in tiers }
        if (overreached.isNotEmpty()) return ThroughputGateState.Overreached(overreached)

        // In the ladder's order, because the message is a census of what is still unauthorised.
        val missing = tiers.filterNot { it in authorised }
        return if (missing.isEmpty()) ThroughputGateState.Promotable else ThroughputGateState.Withheld(missing)
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
     * un-gate either one, or un-retire a Q5 rung, and it enters `pickable` and the test fails
     * until somebody records what it does to the typed text.
     *
     * Over the three Q8 rungs 4.7 offers it answers `Withheld([small-q8, medium-q8, ultra-q8])`:
     * three rows, three measurements, an empty switch.
     */
    fun stateOfLadder(
        tiers: List<String> = WhisperCatalog.pickable.map { it.id },
    ): ThroughputGateState = state(PRODUCTION_PROMOTABLE, tiers, RECORD)
}
