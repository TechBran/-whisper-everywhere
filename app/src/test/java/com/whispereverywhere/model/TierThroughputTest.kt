package com.whispereverywhere.model

import com.whispereverywhere.service.CommitCadencePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * THE THROUGHPUT GATE, executed (4.6 Task 3). *"A rung with no recorded throughput verdict may not
 * be production-promotable."*
 *
 * **Why this suite exists, and the habit it is aimed at.** 4.5.0 and 4.5.1 were internal-only and
 * 4.5.2 was promoted the same day it was built. 4.6 adds six rungs to the chooser that nobody has
 * ever timed, on purpose, so the owner can measure them on six devices — and **the previewer will
 * hide a rung that cannot keep up**, because since 4.4.0 words land on the floating strip about
 * 0.4 s behind the voice whatever the finalizer is doing. So the failure this suite exists to
 * prevent is not a bug anyone would see in review: it is a release promoted by habit, after which
 * a production user picks `large-v3` on a 6 GB phone and watches the typed text fall a paragraph
 * behind text that keeps appearing.
 *
 * It is built in the shape the owner has already accepted for language clearance
 * (`StreamingPackClearanceTest`), one axis over, and the correspondence is exact:
 *
 * | clearance | throughput |
 * |---|---|
 * | [com.whispereverywhere.transcription.stream.PackClearance] per language | [TierThroughput] per rung |
 * | `Cleared` / `Outstanding` | [ThroughputVerdict.Measured] / [ThroughputVerdict.Unmeasured] |
 * | granted over BYTES, via `pinnedCommit` | measured AT A FLOOR, via [ThroughputMeasurement.commitFloorMs] |
 *
 * | requirement (controller brief, Task 3) | test |
 * |---|---|
 * | no selectable rung may have a blank verdict | [no_measured_verdict_is_blank_and_every_measurement_names_a_real_measurer] |
 * | the production switch may never name a rung whose verdict is missing | [authorising_a_rung_whose_throughput_is_unmeasured_is_a_red_suite] |
 * | `multi` carries its measured pass; every new rung carries UNMEASURED | [the_one_measured_rung_is_multi_and_the_other_six_are_unmeasured] |
 * | the gate withholds promotion while any selectable rung is unmeasured | [the_committed_state_withholds_promotion_and_names_the_six_unmeasured_rungs] |
 * | a rung gained or lost by the chooser cannot slip past the record | [every_pickable_rung_has_a_row_and_no_row_outlives_the_ladder] |
 * | a verdict is measured AT A FLOOR, and moving the floor invalidates it | [every_measurement_is_pinned_to_the_commit_floor_it_was_measured_at] |
 * | an unmeasured rung is paced at the conservative floor | [an_unmeasured_rung_is_paced_at_the_conservative_large_floor] |
 * | MEASURED is not CLEARED — a rung that fell behind may not promote | [a_rung_that_was_measured_and_fell_behind_does_not_clear_production] |
 * | every state of the gate is exercised, including the one this branch cannot reach | [the_gate_answers_every_state_including_promotable] |
 *
 * **What this suite deliberately cannot do**, stated in the same place its clearance twin states
 * it: it cannot tell a real measurement from an invented one. No test can read a stopwatch. What it
 * can do is make an invention cost three separate edits — the row, the switch, and
 * [the_one_measured_rung_is_multi_and_the_other_six_are_unmeasured], whose docblock says why a
 * subagent must not write one — and make a MISLABELLED measurement fail on its own words.
 */
class TierThroughputTest {

    private val ladder: List<String> = WhisperCatalog.pickable.map { it.id }

    /**
     * **One row per selectable rung, in both directions.** A rung the chooser can offer with no
     * throughput row fails the build on arrival, and a row for a rung nobody can select fails too.
     *
     * The subject is [WhisperCatalog.pickable] — the rungs offered on EVERY device — and that is
     * the boundary rather than a hand-written exemption list. The two gated NPU tiers are out of it
     * by the same `gated` flag that keeps them out of every download path, and **the boundary
     * defends itself**: un-gate one, or un-retire a 60 MB tier, and it enters `pickable` and this
     * test fails until somebody records what it does to the typed text.
     */
    @Test fun every_pickable_rung_has_a_row_and_no_row_outlives_the_ladder() {
        assertEquals(
            "a rung entered or left the chooser — give it a throughput row, or delete its row",
            ladder,
            TierThroughputRecord.RECORD.map { it.tierId },
        )
        ladder.forEach {
            assertNotNull("'$it' is selectable with no throughput row at all", TierThroughputRecord.forTier(it))
        }
        assertEquals("forTier must not resolve a rung nobody can pick", null, TierThroughputRecord.forTier("npu"))
        assertEquals(null, TierThroughputRecord.forTier("npu-turbo"))
        assertEquals(null, TierThroughputRecord.forTier("pro"))
    }

    /**
     * **A blank verdict fails the build** — the brief's own acceptance. "Measured" with no device,
     * no measurer, no date and no finalize time is the shape an invented verdict takes, so each of
     * those is held non-blank and shaped.
     *
     * `measuredBy` is held to a NAME for the same reason the clearance record holds `grantedBy` to
     * one: *"the owner"* is not a name, and a measurement nobody is named for is a measurement
     * nobody can be asked about.
     */
    @Test fun no_measured_verdict_is_blank_and_every_measurement_names_a_real_measurer() {
        TierThroughputRecord.RECORD.forEach { row ->
            when (val verdict = row.verdict) {
                is ThroughputVerdict.Measured -> {
                    val m = verdict.measurement
                    assertTrue("${row.tierId}: a measurement with no device", m.device.isNotBlank())
                    assertTrue("${row.tierId}: a measurement nobody is named for", m.measuredBy.isNotBlank())
                    assertFalse(
                        "${row.tierId}: 'the owner' is not a name — a measurement nobody is named " +
                            "for is one nobody can be asked about",
                        m.measuredBy.trim().lowercase() == "the owner",
                    )
                    assertTrue(
                        "${row.tierId}: measuredOn must be an ISO date — a measurement nobody " +
                            "dated is one nobody can re-do",
                        m.measuredOn.matches(Regex("""\d{4}-\d{2}-\d{2}""")),
                    )
                    assertTrue(
                        "${row.tierId}: a finalize time of ${m.finalizeSeconds} s is not a measurement",
                        m.finalizeSeconds > 0.0,
                    )
                    assertTrue("${row.tierId}: a measurement with no evidence to re-read", m.evidence.isNotBlank())
                    assertTrue("${row.tierId}: a verdict with no reason", verdict.because.isNotBlank())
                }
                is ThroughputVerdict.Unmeasured -> assertTrue(
                    "${row.tierId}: UNMEASURED on its own leaves the owner nothing to do — say " +
                        "what closes it",
                    verdict.action.isNotBlank(),
                )
            }
        }
    }

    /**
     * **`multi` carries the one measured pass; the other six carry UNMEASURED.** The brief:
     * *"`multi` carries its measured pass (F 2.3 s on the Fold6, from this repo's own audio-ctx
     * bench of 2026-08-20). Every new rung carries UNMEASURED."*
     *
     * **A SUBAGENT MUST NOT EDIT THIS TEST TO ADD A MEASURED ROW.** Six devices are about to be
     * measured and the research this branch serves *predicts* — from ONE Fold6 anchor and ONE Tab
     * datapoint — that medium, turbo and large-v3 all fail. A prediction is not a verdict, and the
     * research's own numbers are DERIVED: its medium band is three arithmetic derivations off a
     * measured anchor, not a stopwatch. Recording one here as a pass would hand a production user
     * exactly the rung the owner bought six devices to rule out.
     *
     * The relation to [WhisperCatalog.instruments] is asserted rather than restated: T1's
     * `the_instrument_set_is_exactly_the_unmeasured_rungs_of_the_ladder` wrote that list by hand
     * because there was no record to read. There is now, so **the flag and the verdict are held
     * equal** and the two cannot drift.
     */
    @Test fun the_one_measured_rung_is_multi_and_the_other_six_are_unmeasured() {
        val measured = TierThroughputRecord.RECORD
            .filter { it.verdict is ThroughputVerdict.Measured }
            .map { it.tierId }
        assertEquals(
            "a rung earned a verdict, or lost one — this is not a test to edit to fit a prediction",
            listOf("multi"),
            measured,
        )
        assertEquals(
            listOf("small-q8", "medium-q5", "medium-q8", "ultra", "ultra-q8", "large-v3"),
            TierThroughputRecord.RECORD
                .filter { it.verdict is ThroughputVerdict.Unmeasured }
                .map { it.tierId },
        )
        // THE COUPLING: the catalogue's `instrument` flag and this record's verdict are one claim.
        // An instrument is a rung the app offers without advocating, and the reason it does not
        // advocate is that nobody has timed it. If those two ever disagree, one of them is lying.
        assertEquals(
            "the `instrument` flag and the throughput record disagree about which rungs are " +
                "unmeasured — a rung was flagged without a row, or measured without losing the flag",
            WhisperCatalog.instruments.map { it.id },
            TierThroughputRecord.RECORD
                .filter { it.verdict is ThroughputVerdict.Unmeasured }
                .map { it.tierId },
        )
        // And the measured rung is the one the app stands behind: the default, and the only
        // non-instrument in the ladder.
        assertEquals(listOf(WhisperCatalog.DEFAULT_MODEL_ID), measured)
    }

    /**
     * **`multi`'s row, pinned exactly** — every field, because this is the one row a reader will
     * take on trust and the one row an invention would imitate.
     *
     * Two residuals are pinned along with it, both recorded as ACCEPTED rather than resolved,
     * because they are what a promotion decision needs to know about the only pass in the record:
     *
     *  - **the measurement predates the previewer.** It was taken on versionCode 77 (3.6.0); the
     *    previewer shipped in 4.4.0 at versionCode 90 and holds 2 threads and +169 MB whenever
     *    armed. So `previewerArmed` is false and cannot be anything else — the previewer did not
     *    exist. No row in this record has ever been measured with it on.
     *  - **one device.** F = 2.3 s is the Fold6, which the research calls the upper bound of the
     *    fleet, and it derives 0.48-0.54 duty for the Tab from it rather than measuring it.
     */
    @Test fun the_measured_row_is_pinned_exactly_including_what_it_does_not_establish() {
        val verdict = TierThroughputRecord.forTier("multi")!!.verdict as ThroughputVerdict.Measured
        val m = verdict.measurement
        assertEquals(2.3, m.finalizeSeconds, 0.0001)
        assertEquals(6_000L, m.commitFloorMs)
        assertEquals("2026-08-20", m.measuredOn)
        assertEquals("Brandon Slacum", m.measuredBy)
        assertEquals(KeepUp.KEPT_UP, m.outcome)
        assertTrue("the device must name the Fold6 this was measured on", m.device.contains("Fold6"))
        assertFalse(
            "the previewer did not exist at versionCode 77 — this row may not claim it was armed",
            m.previewerArmed,
        )
        assertTrue(
            "the row must name the versionCode it was measured on: a finalize time is about a " +
                "build, and this one is two months older than the previewer",
            m.evidence.contains("77"),
        )
        assertTrue(
            "the row must record that no measurement in this record was taken with the previewer " +
                "armed — it is the one axis the session adds that 2026-08-20 could not have",
            verdict.because.contains("previewer"),
        )
    }

    /**
     * **A VERDICT IS MEASURED AT A FLOOR, NOT ABOUT A TIER**, and this is the exact analogue of the
     * clearance record's `pinnedCommit`: *"a clearance is granted over BYTES, not over a repository
     * name."*
     *
     * `F = 2.3 s` is not a fact about whisper-small. It is a fact about whisper-small at a 6 000 ms
     * commit floor, and the app's own eligibility rule is a RATIO — `CommitCadencePolicy`:
     * *"a tier keeps a floor only while its full-segment F is MEASURED and `F/floor + m <= 0.70` at
     * saturation."* Move `multi` to a 3 000 ms floor and the same measured 2.3 s stops clearing that
     * rule, while every prose sentence about it still reads true. So the floor is recorded in the
     * measurement and held equal to the app's own table: **re-pace a measured rung and its verdict
     * must be re-earned, not inherited.**
     */
    @Test fun every_measurement_is_pinned_to_the_commit_floor_it_was_measured_at() {
        TierThroughputRecord.RECORD.forEach { row ->
            val verdict = row.verdict
            if (verdict is ThroughputVerdict.Measured) {
                assertEquals(
                    "${row.tierId}: this rung's commit floor moved since it was measured. The " +
                        "verdict is about F/floor, so it does not survive the move — re-measure " +
                        "it or revert the floor",
                    CommitCadencePolicy.minCommitIntervalMs(row.tierId, isCloudBatch = false),
                    verdict.measurement.commitFloorMs,
                )
            }
        }
    }

    /**
     * **An unmeasured rung is paced at the conservative floor.** The coupling runs the other way
     * too, and it closes the one tempting edit T1 wrote down and declined to make.
     *
     * `CommitCadencePolicy`'s census records the 4.6 decision: all five new rungs take the LARGE
     * row, 8 000 ms, *"because 8 s is the conservative direction for a rung nobody has measured and
     * this task may not invent a verdict"* — including `small-q8`, which is `multi`'s own weights
     * and does not look heavy. Its comment also names the confound that creates: `multi` paces at
     * 6 s and `small-q8` at 8 s, so **the cheapest decisive experiment in the whole session runs
     * its two arms at different commit floors.** Moving `small-q8` onto the 6 s row is the change
     * the measurement exists to justify, and this test is what makes doing it early a red suite.
     */
    @Test fun an_unmeasured_rung_is_paced_at_the_conservative_large_floor() {
        TierThroughputRecord.RECORD
            .filter { it.verdict is ThroughputVerdict.Unmeasured }
            .forEach { row ->
                assertEquals(
                    "${row.tierId}: an UNMEASURED rung may not be paced faster than the " +
                        "conservative floor. Either measure it and record the verdict, or leave " +
                        "it on the 8 s row",
                    CommitCadencePolicy.MIN_COMMIT_INTERVAL_LARGE_MS,
                    CommitCadencePolicy.minCommitIntervalMs(row.tierId, isCloudBatch = false),
                )
            }
    }

    // --------------------------------------------------------------------- THE GATE

    /**
     * **The committed state: promotion is WITHHELD, and it names the six rungs holding it.**
     *
     * This is the normal state of this branch and it is **not a failure**. Every one of those six
     * rungs is in the chooser, downloadable on every device, and that is what the build is FOR.
     * What is withheld is a store promotion, which is a different act.
     */
    @Test fun the_committed_state_withholds_promotion_and_names_the_six_unmeasured_rungs() {
        // THE PROPERTY FIRST, and it is the one the brief asks for: whatever the switch says, the
        // COMMITTED state may never be one of the two defect states. This is what fails when
        // somebody adds a rung to PRODUCTION_PROMOTABLE without measuring it — and it fails
        // saying so, rather than saying "expected setOf(multi)".
        when (val committed = TierThroughputRecord.stateOfLadder()) {
            is ThroughputPromotionState.Overreached -> fail(
                "PRODUCTION_PROMOTABLE names ${committed.tiers}, whose throughput verdict does " +
                    "not clear them. A rung with no recorded throughput verdict may not be " +
                    "production-promotable: either run §AN's long-dictation row and record the " +
                    "measurement, or take it out of the switch",
            )
            is ThroughputPromotionState.Unrecorded -> fail(
                "${committed.tiers} are in the chooser with no throughput row at all",
            )
            else -> Unit
        }
        // ...and then today's exact value, which is the normal, non-failing state of this branch.
        assertEquals(setOf("multi"), TierThroughputRecord.PRODUCTION_PROMOTABLE)
        assertEquals(
            ThroughputPromotionState.Withheld(
                listOf("small-q8", "medium-q5", "medium-q8", "ultra", "ultra-q8", "large-v3"),
            ),
            TierThroughputRecord.stateOfLadder(),
        )
    }

    /**
     * **A production switch that names a rung whose verdict is missing FAILS.** The brief's own
     * acceptance, and the half of the gate that does the work: evidence arriving does not promote
     * anything, and the switch may not run ahead of the evidence in the other direction either.
     *
     * Three ways to overreach, all held:
     *  - naming a rung whose verdict is [ThroughputVerdict.Unmeasured];
     *  - naming a rung that is not in the ladder at all — a stale authorisation left behind by a
     *    retired row, which is how a switch outlives the thing it authorised;
     *  - naming a rung that WAS measured and did not keep up (see
     *    [a_rung_that_was_measured_and_fell_behind_does_not_clear_production]).
     */
    @Test fun authorising_a_rung_whose_throughput_is_unmeasured_is_a_red_suite() {
        assertEquals(
            ThroughputPromotionState.Overreached(listOf("large-v3")),
            TierThroughputRecord.state(
                authorised = setOf("multi", "large-v3"),
                tiers = ladder,
            ),
        )
        // A switch entry for a rung that is no longer offered: stale, and reported rather than
        // ignored. `pro` is retired, so it is not in the ladder — there is nothing to promote.
        assertEquals(
            ThroughputPromotionState.Overreached(listOf("pro")),
            TierThroughputRecord.state(
                authorised = setOf("multi", "pro"),
                tiers = ladder,
                record = TierThroughputRecord.RECORD +
                    TierThroughput("pro", TierThroughputRecord.MULTI.verdict),
            ),
        )
    }

    /**
     * **MEASURED IS NOT CLEARED.** The gate's most mistakable property: a rung with a perfectly
     * good measurement showing that the typed text never caught up must not promote.
     *
     * And the middle outcome is held with it, because it is the one a short trial reports as a
     * pass. [KeepUp.RECOVERED_ONLY_IN_PAUSES] does not clear: under this app's `audio_ctx` floor
     * the cost per commit is constant, so a queue that drains only while the user is silent is a
     * queue that grows whenever the user is not. What such a run measured is the length of the
     * pauses, not the throughput of the rung.
     */
    @Test fun a_rung_that_was_measured_and_fell_behind_does_not_clear_production() {
        val base = (TierThroughputRecord.MULTI.verdict as ThroughputVerdict.Measured)
        listOf(KeepUp.NEVER_CAUGHT_UP, KeepUp.RECOVERED_ONLY_IN_PAUSES).forEach { outcome ->
            val fell = base.copy(measurement = base.measurement.copy(outcome = outcome))
            assertFalse(
                "$outcome is a verdict, not a clearance — a measured failure may not promote",
                fell.clearsProduction,
            )
            assertEquals(
                "a switch naming a rung that was measured and did not keep up must OVERREACH",
                ThroughputPromotionState.Overreached(listOf("multi")),
                TierThroughputRecord.state(
                    authorised = setOf("multi"),
                    tiers = listOf("multi"),
                    record = listOf(TierThroughput("multi", fell)),
                ),
            )
        }
        assertTrue("...and the recorded KEPT_UP row does clear", base.clearsProduction)
    }

    /**
     * **Every state, including the [ThroughputPromotionState.Promotable] cell this branch's own
     * values can never reach.** A gate whose only tested value is "no" is not a gate that has been
     * tested — the same reason `PackClearanceRecord.state` takes all three inputs as parameters.
     *
     * The severity ORDER is asserted with them, and it matters: an [ThroughputPromotionState
     * .Unrecorded] rung is reported ahead of a [ThroughputPromotionState.Withheld] one, so a rung
     * that slipped into the chooser with no row at all can never read as a reassuring "not yet
     * authorised".
     */
    @Test fun the_gate_answers_every_state_including_promotable() {
        val cleared = TierThroughputRecord.MULTI.verdict
        val one = listOf(TierThroughput("multi", cleared))

        assertEquals(
            ThroughputPromotionState.Promotable,
            TierThroughputRecord.state(setOf("multi"), listOf("multi"), one),
        )
        assertEquals(
            ThroughputPromotionState.Withheld(listOf("multi")),
            TierThroughputRecord.state(emptySet(), listOf("multi"), one),
        )
        assertEquals(
            ThroughputPromotionState.Unrecorded(listOf("large-v3")),
            TierThroughputRecord.state(setOf("multi"), listOf("multi", "large-v3"), one),
        )
        // SEVERITY: a rung with no row outranks an unauthorised one, even when the switch is empty
        // and Withheld would also be true.
        assertEquals(
            "an incomplete record must never read as a reassuring 'not yet authorised'",
            ThroughputPromotionState.Unrecorded(listOf("large-v3")),
            TierThroughputRecord.state(emptySet(), listOf("multi", "large-v3"), one),
        )
        // ...and it outranks an overreaching switch too.
        assertEquals(
            ThroughputPromotionState.Unrecorded(listOf("large-v3")),
            TierThroughputRecord.state(setOf("nope"), listOf("multi", "large-v3"), one),
        )
    }

    // ----------------------------------------------- THE GATE'S BOUNDARY WITH THE APP

    /**
     * **THE GATE MUST NOT REACH THE CHOOSER, AND THIS IS THE ONLY TEST THAT CAN PROVE IT.**
     *
     * The obvious implementation of *"an unmeasured rung may not be promoted"* is to filter the
     * unmeasured rungs out of [WhisperCatalog.pickable]. That would destroy the entire branch: the
     * owner asked to *"see all the models there so I can just select between them and try each
     * one"*, and he cannot measure a rung the app declines to offer him. The failure would also be
     * SILENT — six cards simply absent, exactly the symptom he reported that started this work.
     *
     * So the record is a PROMOTION artefact with no runtime reader, and the negative is proved the
     * way its clearance twin proves it: by reading the app's own sources
     * (`StreamingPackClearanceTest.theClearanceStateReachesNothingTheAppRuns`). A COMMENT pointing
     * at this record is fine and welcome; a live reference is not.
     */
    @Test fun the_gate_reaches_nothing_the_app_runs() {
        val vocabulary = listOf(
            "TierThroughput",
            "ThroughputVerdict",
            "ThroughputMeasurement",
            "ThroughputPromotionState",
            "PRODUCTION_PROMOTABLE",
            "KeepUp",
        )
        val home = "TierThroughput.kt"
        val scanned = mutableListOf<String>()
        for (file in appSources()) {
            if (file.name == home) continue
            scanned += file.name
            val text = file.readText().replace("\r\n", "\n")
            for (needle in vocabulary) {
                val live = text.lineSequence().filter { line ->
                    val trimmed = line.trimStart()
                    val commented = trimmed.startsWith("//") || trimmed.startsWith("*") ||
                        trimmed.startsWith("/*")
                    !commented && line.contains(needle)
                }.toList()
                assertEquals(
                    "${file.name} has a LIVE reference to '$needle'. The throughput record is a " +
                        "PROMOTION record: it must not reach the chooser, the catalogue, the " +
                        "download paths, the commit pipeline or anything the app reads at " +
                        "runtime. Every rung in it is selectable and downloadable on every " +
                        "device — that is the whole point of 4.6, and the owner cannot measure a " +
                        "rung the app will not offer him. A COMMENT pointing at it is fine.",
                    emptyList<String>(),
                    live.map { it.trim() },
                )
            }
        }
        // The scan has to have found the app, or it proves nothing: a broken path would make this
        // test pass over zero files.
        assertTrue("the source scan found only ${scanned.size} files — it is not reading the app", scanned.size > 100)
        assertTrue(
            "the scan must cover the files that would do the filtering if anyone tried",
            scanned.containsAll(listOf("WhisperModel.kt", "ModelTierCopy.kt", "ModelMigration.kt")),
        )
    }

    /**
     * **The default and every migration target carry a CLEARING verdict**, over the full input
     * cross product.
     *
     * T1 already pins both against [WhisperModel.instrument], which was the strongest statement
     * available before this record existed. This is the stronger one, and it is not a duplicate:
     * the flag says *"we are not advocating this"*, while the verdict says *"here is what it did
     * to the typed text"* — so this assertion also catches a target whose throughput was measured
     * and came back BEHIND, a state the flag cannot express at all.
     *
     * The brief calls a migration target that is retired or unmeasured *"the one unrecoverable
     * defect"* in the ladder task, and the reason is the blast radius: [WhisperCatalog
     * .DEFAULT_MODEL_ID] is what every path with no pick on record falls back to, so an unmeasured
     * rung here reaches precisely the users who never made a choice.
     */
    @Test fun the_default_and_every_migration_target_carry_a_clearing_verdict() {
        val default = TierThroughputRecord.forTier(WhisperCatalog.DEFAULT_MODEL_ID)
        assertNotNull("the default rung has no throughput row at all", default)
        assertTrue(
            "DEFAULT_MODEL_ID is '${WhisperCatalog.DEFAULT_MODEL_ID}', whose throughput verdict " +
                "does not clear production. It is the fallback for every path with no pick on " +
                "record, so this hands an untimed finalizer to the users who made no choice",
            default!!.verdict.clearsProduction,
        )
        // Exhaustive over the migration function's whole input domain.
        ModelScope.entries.forEach { scope ->
            val target = ModelMigration.targetIdFor(scope)
            val row = TierThroughputRecord.forTier(target)
            assertNotNull("the $scope migration target '$target' has no throughput row", row)
            assertTrue(
                "the $scope migration target is '$target', whose throughput verdict does not " +
                    "clear production — a migration MOVES a user who did not ask to be moved, so " +
                    "it may not move them onto a rung nobody has timed",
                row!!.verdict.clearsProduction,
            )
        }
    }

    // ------------------------------------- THE SHEET THE PROMOTION DECISION IS MADE ON

    /**
     * **A GATE IS ONLY A GATE IF THE PROMOTION DECISION READS IT.** The acceptance sheet is where a
     * release decision actually gets made, so §AN has to name the record it consults — exactly what
     * `StreamingPackClearanceTest` holds about §AL and `PRODUCTION_CLEARED`.
     *
     * The rest of what is asserted is the sheet's own honesty about the app, and each item is here
     * because a stale sheet is worse than no sheet:
     *
     *  - **the three keep-up answers, verbatim from [KeepUp]'s own semantics.** The middle one is
     *    the point: a sheet offering only pass/fail would collect "fine" for every rung that
     *    recovered in a pause, which is the exact reading this gate exists to reject.
     *  - **a row per selectable rung, identified by the app's OWN size badge** rather than by a
     *    number typed into prose. Re-pin a rung and the sheet goes visibly stale instead of quietly
     *    wrong — the same rationale the clearance test gives for pinning each pack's badge.
     *  - **the two named comparisons**, without which the session is six downloads rather than an
     *    experiment.
     *  - **the standing caution**, because the one command that would answer this numerically is
     *    the one that erases the models.
     *
     * The sheet is in the test task's `sourcePinnedInputs` (`app/build.gradle.kts`) already, on
     * §AL's account, so this pin actually re-runs when the document is edited. Without that entry
     * a pin over a document reports green over a document that has been edited out from under it.
     */
    @Test fun the_sheet_the_promotion_decision_is_made_on_names_this_gate() {
        val sheet = repoFile("docs/superpowers/sdd/2026-09-02-431-guards-tts/acceptance.md")
            .readText().replace("\r\n", "\n")
        assertTrue(
            "the acceptance sheet must name the record a promotion consults",
            sheet.contains("TierThroughputRecord.PRODUCTION_PROMOTABLE"),
        )
        assertTrue("§AN must exist — the six-device session needs its own rows", sheet.contains("## AN —"))
        val an = sheet.substringAfter("## AN —", "")
        assertTrue("§AN is where the rung rows live and it is not in the sheet", an.isNotBlank())

        // The three answers, and the middle one especially: a pass/fail sheet would collect "fine"
        // for a rung that only drained while the owner was silent.
        listOf("KEPT UP", "RECOVERED IN PAUSES", "NEVER CAUGHT UP").forEach {
            assertTrue("§AN must offer the answer '$it' — all three of KeepUp's outcomes", an.contains(it))
        }

        // One row per selectable rung, found by the app's own size badge so a re-pin shows up.
        WhisperCatalog.pickable.forEach { model ->
            val badge = ModelTierCopy.forId(model.id)?.badges?.firstOrNull { it.endsWith(" MB") }
            assertNotNull("'${model.id}' has no size badge to identify its §AN row by", badge)
            assertTrue(
                "§AN has no row for '${model.id}' (${badge}) — every rung the chooser offers needs " +
                    "one, or the session leaves a selectable rung unmeasured and the gate can " +
                    "never open",
                an.contains(badge!!),
            )
            // ...and the floor it is paced at, ON THE SAME LINE as the badge that identifies the
            // rung, because AN8's two arms do not share a floor and the owner has to compare
            // wall-clock lag rather than duty.
            //
            // The same-line requirement is what makes this bind. There are only TWO distinct floor
            // values on this ladder and both appear somewhere in §AN, so a bare
            // `an.contains("8 000 ms")` would pass for every rung no matter which floor it was
            // actually on — measured: with `small-q8` moved to multi's 6 s row, that weaker form
            // stayed green while the cadence table and the sheet openly disagreed.
            val floor = CommitCadencePolicy.minCommitIntervalMs(model.id, isCloudBatch = false)
            val written = "${floor / 1000} 000 ms"
            assertTrue(
                "§AN must state '${model.id}'s commit floor ($written) beside its $badge badge: a " +
                    "verdict is earned AT A FLOOR, this ladder does not share one, and the sheet " +
                    "and CommitCadencePolicy now disagree about this rung",
                an.lineSequence().any { it.contains(badge) && it.contains(written) },
            )
        }

        // The two comparisons that carry the most information.
        assertTrue("§AN must name the quantisation comparison (small Q5_1 vs Q8_0)", an.contains("THE QUANTISATION AXIS"))
        assertTrue("§AN must name the medium comparison", an.contains("medium Q5_0 against medium Q8_0"))

        // The numeric route exists and needs no new code; the command that would wipe the models
        // must be named as forbidden in the same breath.
        assertTrue("§AN must record that the bench already exists", an.contains("bench_whisper_rtf_across_slices"))
        assertTrue("§AN must give the logcat tag", an.contains("WE-BENCH"))
        assertTrue(
            "§AN must carry the standing caution — connectedDebugAndroidTest uninstalls and " +
                "wipes every downloaded model",
            an.contains("connectedDebugAndroidTest"),
        )
        // The metric, and the one that would ship a bad tier.
        assertTrue("§AN must require a LONG dictation, not a feel test", an.contains("five minutes"))
        assertTrue("§AN must state the audio_ctx floor that makes cost per commit constant", an.contains("8.96 s"))
    }

    private fun repoFile(relative: String): File {
        val file = File(repoRoot(), relative)
        assertTrue("$relative does not exist under ${repoRoot()}", file.isFile)
        return file
    }

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        throw AssertionError("cannot locate the repository root from ${System.getProperty("user.dir")}")
    }

    private fun appSources(): List<File> =
        File(repoRoot(), "app/src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
}
