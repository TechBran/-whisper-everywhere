package com.whispereverywhere.model

import com.whispereverywhere.service.CommitCadencePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * THE THROUGHPUT GATE, executed (4.6 Task 3; the record filled in by 4.7). *"A rung with no
 * recorded throughput verdict may not be production-promotable."*
 *
 * **Why this suite exists, and the habit it is aimed at.** 4.5.0 and 4.5.1 were internal-only and
 * 4.5.2 was promoted the same day it was built. 4.6 added six rungs to the chooser that nobody had
 * ever timed, on purpose, so the owner could measure them — and **the previewer will hide a rung
 * that cannot keep up**, because since 4.4.0 words land on the floating strip about 0.4 s behind
 * the voice whatever the finalizer is doing. So the failure this suite exists to prevent is not a
 * bug anyone would see in review: it is a release promoted by habit, after which a production user
 * picks a heavy rung on a 6 GB phone and watches the typed text fall a paragraph behind text that
 * keeps appearing. On 2026-09-17 the Tab S10+ session saw exactly that on `medium-q5`.
 *
 * **4.7 — the record is filled in and the switch is empty.** Five rungs were timed on the owner's
 * Galaxy Tab S10+ (`docs/measurements/2026-09-17-tab-cpu-ladder.md`) and the owner ruled the same
 * day — Q8 for everything, every Q5 rung retired. Three Q8 rungs are pickable, all three measured,
 * none authorised: the accuracy pass on small and medium comes first. The gate's committed answer
 * was `Withheld([small-q8, medium-q8, ultra-q8])`, and `ultra-q8` could not enter the switch even
 * if the owner reached for it, because its verdict is [KeepUp.KEPT_UP_WITHOUT_MARGIN].
 *
 * **4.9 — the owner ruled, and the gate reports Promotable for the first time.** After his own
 * dictation on all three rungs (2026-09-17: *"all three actually work very well"*) the switch names
 * all three, and `ultra-q8` clears on a [ThroughputVerdict.OwnerRuling] recorded BESIDE its
 * unchanged KEPT_UP_WITHOUT_MARGIN row — a decision written next to the evidence it overrides,
 * never an edit to the evidence. Two new requirements hold that shape: a ruling clears ONLY the row
 * it is on, and a ruling nobody signed or dated is refused at construction.
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
 * | requirement | test |
 * |---|---|
 * | no selectable rung may have a blank verdict | [no_measured_verdict_is_blank_and_every_measurement_names_a_real_measurer] |
 * | the production switch may never name a rung whose verdict is missing or does not clear | [authorising_a_rung_whose_verdict_does_not_clear_is_a_red_suite] |
 * | the instrument set equals the pickable rungs whose verdict does not clear (empty since 4.9) | [the_instrument_set_is_the_pickable_rungs_whose_verdict_does_not_clear] |
 * | the committed state is Promotable: three rows, one ruling, the switch naming all three | [the_committed_state_is_promotable_on_three_rows_one_ruling_and_the_owners_word] |
 * | an owner ruling clears only the row it is on, and must be signed and dated | [an_owner_ruling_clears_only_the_row_it_is_recorded_on_and_must_be_signed_and_dated] |
 * | a rung gained by the chooser cannot slip past the record; a retired rung keeps its row | [every_pickable_rung_has_a_row_and_every_row_resolves_in_the_catalogue] |
 * | a verdict is measured AT A FLOOR, and moving the floor invalidates it | [every_measurement_is_pinned_to_the_commit_floor_it_was_measured_at] |
 * | an unmeasured rung is paced at the conservative floor | [an_unmeasured_rung_is_paced_at_the_conservative_large_floor] |
 * | MEASURED is not CLEARED — behind, in pauses, or without margin may not promote | [a_rung_that_was_measured_and_did_not_clear_does_not_promote] |
 * | the 2026-09-17 rows are pinned exactly, numbers from the doc | [the_tab_rows_are_pinned_exactly_to_the_measurement_doc] |
 * | every state of the gate is exercised, including the one the committed values cannot reach | [the_gate_answers_every_state_including_promotable] |
 *
 * **What this suite deliberately cannot do**, stated in the same place its clearance twin states
 * it: it cannot tell a real measurement from an invented one. No test can read a stopwatch. What it
 * can do is make an invention cost three separate edits — the row, the switch, and the pinned
 * numbers in [the_tab_rows_are_pinned_exactly_to_the_measurement_doc], which are copied from a
 * committed document with primary logcat lines — and make a MISLABELLED measurement fail on its
 * own words.
 */
class TierThroughputTest {

    private val ladder: List<String> = WhisperCatalog.pickable.map { it.id }

    private val tab = "Galaxy Tab S10+ (SM-X828U, Dimensity 9300+)"
    private val tabMeasurer = "Claude Fable 5.1, over remote adb on Brandon Slacum's tablet at his instruction"
    private val tabDoc = "docs/measurements/2026-09-17-tab-cpu-ladder.md"

    /**
     * **Every pickable rung has a row; every row resolves; no row is for a gated tier.**
     *
     * The strict direction is unchanged from 4.6: a rung the chooser can offer with no throughput
     * row fails the build on arrival. The subject is [WhisperCatalog.pickable] — the rungs offered
     * on EVERY device — and that boundary defends itself: un-gate an NPU tier, or un-retire a Q5
     * rung, and it enters `pickable` and this test fails until somebody records what it does to
     * the typed text.
     *
     * **The reverse direction was RELAXED in 4.7, deliberately.** 4.6 held "no row outlives the
     * ladder" because every row was for a pickable rung. The Q8 ruling retired four rungs on the
     * strength of their rows — `multi`'s Fold6 anchor and Tab number, `medium-q5`'s
     * NEVER_CAUGHT_UP — and deleting the evidence a ruling was made on, on the day it was made,
     * would leave the record unable to say why the ladder looks the way it does. So a retired rung
     * KEEPS its row. What still fails: a row for an id the catalogue cannot resolve, and a row for
     * a gated tier (measured under its own ship gate, on the Hexagon).
     */
    @Test fun every_pickable_rung_has_a_row_and_every_row_resolves_in_the_catalogue() {
        ladder.forEach {
            assertNotNull("'$it' is selectable with no throughput row at all", TierThroughputRecord.forTier(it))
        }
        TierThroughputRecord.RECORD.forEach { row ->
            val model = WhisperCatalog.byId(row.tierId)
            assertNotNull("row '${row.tierId}' is for an id the catalogue cannot resolve", model)
            assertFalse(
                "row '${row.tierId}' is for a GATED tier — the NPU tiers are measured under their " +
                    "own ship gate, where this commit-floor arithmetic does not describe the cost",
                model!!.gated,
            )
        }
        // In catalogue order, so §AN and this record read the same way down.
        val catalogueOrder = WhisperCatalog.entries.map { it.id }
        assertEquals(
            "the record is not in catalogue order",
            TierThroughputRecord.RECORD.map { it.tierId }.sortedBy { catalogueOrder.indexOf(it) },
            TierThroughputRecord.RECORD.map { it.tierId },
        )
        // The rows that are for RETIRED rungs, named — the evidence the Q8 ruling was made on.
        assertEquals(
            listOf("multi", "medium-q5", "ultra", "large-v3"),
            TierThroughputRecord.RECORD.map { it.tierId }.filter { WhisperCatalog.byId(it)!!.retired },
        )
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
     * nobody can be asked about. The 2026-09-17 rows name the person at the keyboard AND the
     * person whose device it was, because those were two different people.
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
                    assertFalse(
                        "${row.tierId}: 'the owner' is not a name, even inside a longer attribution",
                        Regex("\\bthe owner\\b").containsMatchIn(m.measuredBy.lowercase()),
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
     * **THE COUPLING RULE, GENERALISED: the instrument set is exactly the pickable rungs whose
     * verdict does not clear production.**
     *
     * 4.6 held the instrument set equal to the record's UNMEASURED rungs, because "unmeasured" was
     * the only reason a rung was ever offered without advocacy. 2026-09-17 produced a second
     * reason: `ultra-q8` is measured and kept up, but with no margin — 0.99 of its floor at the
     * worst commit, on a flagship — and that does not clear. So the flag now tracks the thing it
     * was always about: **a rung the app offers without a verdict that clears**. Clearing the flag
     * on such a rung is a red suite, and so is leaving it on a rung whose verdict clears.
     *
     * **A SUBAGENT MUST NOT EDIT THIS TEST TO MAKE A ROW CLEAR.** The three numbers that decide
     * these verdicts are in a committed document with primary logcat lines; the pin below reads
     * them back. The 0.90 line — a rung's outcome is KEPT_UP_WITHOUT_MARGIN once its worst commit
     * reaches 0.90 of its floor on the measured device — is a hand-applied convention used when
     * each row's outcome was assigned, NOT a gate the code enforces: `ThroughputMeasurement` has
     * no worst-commit field, so nothing computes it. `ultra-q8`'s worst was 7,930 against 8,000
     * and its outcome was assigned on that.
     *
     * **4.9 — the set is EMPTY, and the outcome did not move.** `ultra-q8` clears because the
     * owner's ruling is recorded beside its row (`ownerRuling`), not because anyone touched the
     * number: this test pins the outcome as still KEPT_UP_WITHOUT_MARGIN, so a row edited to
     * KEPT_UP to "tidy up" fails here.
     */
    @Test fun the_instrument_set_is_the_pickable_rungs_whose_verdict_does_not_clear() {
        val measured = TierThroughputRecord.RECORD
            .filter { it.verdict is ThroughputVerdict.Measured }
            .map { it.tierId }
        assertEquals(
            "a rung earned a verdict, or lost one — this is not a test to edit to fit a prediction",
            listOf("multi", "small-q8", "medium-q5", "medium-q8", "ultra-q8"),
            measured,
        )
        assertEquals(
            "the two rungs the Q8 ruling retired UNTIMED",
            listOf("ultra", "large-v3"),
            TierThroughputRecord.RECORD
                .filter { it.verdict is ThroughputVerdict.Unmeasured }
                .map { it.tierId },
        )
        // THE COUPLING: the catalogue's `instrument` flag and this record's verdict are one claim.
        val notClearing = WhisperCatalog.pickable
            .filter { !TierThroughputRecord.forTier(it.id)!!.verdict.clearsProduction }
            .map { it.id }
        assertEquals(
            "the `instrument` flag and the throughput record disagree about which pickable rungs " +
                "lack a clearing verdict — a rung was flagged with a clearing verdict, or a rung " +
                "whose verdict does not clear lost the flag",
            WhisperCatalog.instruments.map { it.id },
            notClearing,
        )
        // And since 4.9 that set is EMPTY: the optional top rung still kept up without margin —
        // the outcome is pinned — and clears on the owner's ruling recorded beside it.
        assertEquals(emptyList<String>(), WhisperCatalog.instruments.map { it.id })
        val ultra = TierThroughputRecord.forTier("ultra-q8")!!.verdict as ThroughputVerdict.Measured
        assertEquals(KeepUp.KEPT_UP_WITHOUT_MARGIN, ultra.measurement.outcome)
        assertNotNull("ultra-q8 clears on a RULING, and the ruling must be on the row", ultra.ownerRuling)
        assertTrue(ultra.clearsProduction)
        // The default is a measured rung that clears on its NUMBER, and so is the medium tier —
        // neither needs a ruling, and neither has one.
        listOf(WhisperCatalog.DEFAULT_MODEL_ID, "medium-q8").forEach {
            val v = TierThroughputRecord.forTier(it)!!.verdict as ThroughputVerdict.Measured
            assertTrue("'$it' must carry a clearing verdict", v.clearsProduction)
            assertEquals("'$it' clears on KEPT_UP, not on a ruling", KeepUp.KEPT_UP, v.measurement.outcome)
            assertNull("'$it' needs no ruling and must carry none", v.ownerRuling)
            assertFalse("'$it' must not be an instrument", WhisperCatalog.byId(it)!!.instrument)
        }
        assertFalse("ultra-q8 is no instrument since 4.9", WhisperCatalog.byId("ultra-q8")!!.instrument)
    }

    /**
     * **AN OWNER RULING CLEARS ONLY THE ROW IT IS RECORDED ON, AND MUST BE SIGNED AND DATED.**
     *
     * The ruling is the gate's narrowest key: it is a field on ONE measured row, it changes that
     * row's `clearsProduction` and nothing else, and it leaves the measurement it sits beside
     * byte-identical. Three things are held: (1) the committed ruling is the owner's, dated
     * 2026-09-17, in his words; (2) removing it from `ultra-q8`'s row makes that row — and only
     * that row — stop clearing, and the gate over the real ladder go back to Overreached on the
     * switch entry that depended on it; (3) a ruling with a blank `by` or `words`, a `by` of "the
     * owner", or a non-ISO date is refused at construction — the clearance record's `grantedBy`
     * rule, one axis over: a decision nobody is named for is a decision nobody can be asked about.
     */
    @Test fun an_owner_ruling_clears_only_the_row_it_is_recorded_on_and_must_be_signed_and_dated() {
        val ultra = TierThroughputRecord.ULTRA_Q8.verdict as ThroughputVerdict.Measured
        val ruling = ultra.ownerRuling
        assertNotNull(ruling)
        assertEquals("2026-09-17", ruling!!.on)
        assertEquals("Brandon Slacum", ruling.by)
        assertTrue("the ruling carries his words on turbo", ruling.words.contains("we definitely wanna keep that one"))
        assertTrue(ruling.words.contains("six to maybe nine second drain time"))
        assertTrue(ruling.words.contains("totally manageable and doable"))
        assertTrue(ruling.words.contains("users would definitely like to select between these"))

        // (2) Strip the ruling: the number is the same, the row no longer clears, and the real
        // ladder's gate reports the overreach on exactly that switch entry.
        val unruled = ultra.copy(ownerRuling = null)
        assertEquals("the measurement is untouched by the ruling", ultra.measurement, unruled.measurement)
        assertEquals(ultra.because, unruled.because)
        assertFalse("without the ruling the row is KEPT_UP_WITHOUT_MARGIN and does not clear", unruled.clearsProduction)
        assertTrue("with it, it does", ultra.clearsProduction)
        val recordWithoutRuling = TierThroughputRecord.RECORD.map {
            if (it.tierId == "ultra-q8") TierThroughput("ultra-q8", unruled) else it
        }
        assertEquals(
            "the switch entry that depends on the ruling overreaches the moment it is gone",
            ThroughputGateState.Overreached(listOf("ultra-q8")),
            TierThroughputRecord.state(TierThroughputRecord.PRODUCTION_PROMOTABLE, ladder, recordWithoutRuling),
        )
        // ...and a ruling on one row does not reach any other: put it on medium-q5's NEVER_CAUGHT_UP
        // row instead and only THAT row's answer changes.
        val mediumQ5 = TierThroughputRecord.MEDIUM_Q5.verdict as ThroughputVerdict.Measured
        val ruledElsewhere = TierThroughputRecord.RECORD.map {
            when (it.tierId) {
                "ultra-q8" -> TierThroughput("ultra-q8", unruled)
                "medium-q5" -> TierThroughput("medium-q5", mediumQ5.copy(ownerRuling = ruling))
                else -> it
            }
        }
        assertTrue(ruledElsewhere.first { it.tierId == "medium-q5" }.verdict.clearsProduction)
        assertFalse(ruledElsewhere.first { it.tierId == "ultra-q8" }.verdict.clearsProduction)
        assertEquals(
            ThroughputGateState.Overreached(listOf("ultra-q8")),
            TierThroughputRecord.state(TierThroughputRecord.PRODUCTION_PROMOTABLE, ladder, ruledElsewhere),
        )
        // The ruling is the only thing that clears a non-KEPT_UP outcome — over the whole enum.
        KeepUp.entries.filter { it != KeepUp.KEPT_UP }.forEach { outcome ->
            val row = unruled.copy(measurement = unruled.measurement.copy(outcome = outcome))
            assertFalse("$outcome does not clear unruled", row.clearsProduction)
            assertTrue("$outcome clears with a ruling on the row", row.copy(ownerRuling = ruling).clearsProduction)
        }

        // (3) A ruling nobody signed is not a ruling.
        listOf(
            Triple("2026-09-17", "", "words"),
            Triple("2026-09-17", "   ", "words"),
            Triple("2026-09-17", "the owner", "words"),
            Triple("2026-09-17", "The Owner", "words"),
            Triple("2026-09-17", "Brandon Slacum", ""),
            Triple("2026-09-17", "Brandon Slacum", "  "),
            Triple("", "Brandon Slacum", "words"),
            Triple("yesterday", "Brandon Slacum", "words"),
            Triple("17/09/2026", "Brandon Slacum", "words"),
        ).forEach { (on, by, words) ->
            try {
                ThroughputVerdict.OwnerRuling(on = on, by = by, words = words)
                fail("OwnerRuling(on='$on', by='$by', words='$words') must be refused — a ruling nobody signed, dated or worded is not a ruling")
            } catch (expected: IllegalArgumentException) {
                // refused at construction, as the KDoc promises
            }
        }
        // And the committed one passes the same bar, so the bar is not a bar nothing can clear.
        assertEquals(ruling, ThroughputVerdict.OwnerRuling(ruling.on, ruling.by, ruling.words))
    }

    /**
     * **`multi`'s row, pinned exactly** — every field of the Fold6 anchor, because this is the row
     * the whole 4.6 research was scaled from, and 4.7 must not lose it in the act of retiring the
     * rung. The Tab number joins its `because`; the Fold6 provenance is untouched.
     *
     * Two residuals stay pinned with it, both recorded as ACCEPTED rather than resolved:
     *
     *  - **the Fold6 measurement predates the previewer.** It was taken on versionCode 77 (3.6.0);
     *    the previewer shipped in 4.4.0 at versionCode 90. So `previewerArmed` is false and cannot
     *    be anything else — the previewer did not exist. (The Tab row of 2026-09-17 was taken with
     *    it ARMED, which the `because` now says.)
     *  - **one device.** F = 2.3 s is the Fold6, which the research called the upper bound of the
     *    fleet.
     */
    @Test fun the_multi_row_is_pinned_exactly_including_what_it_does_not_establish() {
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
            "the row must record that the Fold6 measurement was taken without the previewer",
            verdict.because.contains("previewer"),
        )
        // 4.7: the Tab number, added beside the Fold6 provenance rather than replacing it.
        assertTrue("the Tab S10+ session must be cited in the evidence", m.evidence.contains(tabDoc))
        assertTrue("the Tab median (2,618 ms, from the doc) must be in the reading", verdict.because.contains("2,618"))
        assertTrue("and the ratio to its floor (0.63)", verdict.because.contains("0.63"))
        assertTrue("and that the rung is retired", verdict.because.contains("RETIRED"))
        assertTrue("`multi` is retired in the catalogue", WhisperCatalog.byId("multi")!!.retired)
        // Its verdict still clears — a retired rung's evidence is not rewritten by its retirement.
        assertTrue(verdict.clearsProduction)
    }

    /**
     * **The four 2026-09-17 rows, pinned exactly to the measurement document.** Every number here
     * is copied from `docs/measurements/2026-09-17-tab-cpu-ladder.md` (its summary table, computed
     * from the full per-rung logcat samples the document carries), and each row names the device,
     * the date, the measurer, the doc, the build (versionCode 95 + b54fc1b) and the previewer's
     * state. A row that drifts from the doc fails here; a doc that drifts from these rows is a
     * doc someone edited without re-running anything.
     */
    @Test fun the_tab_rows_are_pinned_exactly_to_the_measurement_doc() {
        data class Expected(
            val id: String,
            val medianMs: Long,
            val worstMs: String,
            val floorMs: Long,
            val outcome: KeepUp,
            val window: String,
        )
        listOf(
            // small-q8's floor is the one it is paced on SINCE 4.7.0 (the MULTI row), which is
            // also the floor the doc's table computes its duty against; on versionCode 95 it
            // paced at 8 000 — its `because` carries both readings.
            Expected("small-q8", 1_217L, "1,993", 6_000L, KeepUp.KEPT_UP, "07:15"),
            Expected("medium-q8", 1_341L, "2,508", 8_000L, KeepUp.KEPT_UP, "07:04"),
            Expected("ultra-q8", 4_849L, "7,930", 8_000L, KeepUp.KEPT_UP_WITHOUT_MARGIN, "06:54"),
            Expected("medium-q5", 9_294L, "11,782", 8_000L, KeepUp.NEVER_CAUGHT_UP, "08:30"),
        ).forEach { e ->
            val verdict = TierThroughputRecord.forTier(e.id)!!.verdict as ThroughputVerdict.Measured
            val m = verdict.measurement
            assertEquals("${e.id}: device", tab, m.device)
            assertEquals("${e.id}: the MEDIAN wallMs / 1000, from the doc", e.medianMs / 1000.0, m.finalizeSeconds, 0.0005)
            assertEquals("${e.id}: floor", e.floorMs, m.commitFloorMs)
            assertTrue("${e.id}: every Tab row was taken with the previewer ARMED", m.previewerArmed)
            assertEquals("${e.id}: outcome", e.outcome, m.outcome)
            assertEquals("${e.id}: date", "2026-09-17", m.measuredOn)
            assertEquals("${e.id}: measurer — the person at the keyboard AND whose device it was", tabMeasurer, m.measuredBy)
            assertTrue("${e.id}: evidence must cite the doc by path", m.evidence.contains(tabDoc))
            assertTrue("${e.id}: evidence must name the build", m.evidence.contains("versionCode 95") && m.evidence.contains("b54fc1b"))
            assertTrue("${e.id}: evidence must give the logcat window", m.evidence.contains(e.window))
            assertTrue("${e.id}: evidence must say the previewer was armed", m.evidence.contains("ARMED"))
            assertTrue("${e.id}: the reading must state the worst commit (${e.worstMs})", verdict.because.contains(e.worstMs))
            // The caveats are on the row, in words a promotion decision will read.
            assertTrue("${e.id}: one device", verdict.because.lowercase().contains("one device"))
            assertTrue("${e.id}: one talk", verdict.because.lowercase().contains("one talk"))
            assertTrue("${e.id}: previewer armed", verdict.because.lowercase().contains("previewer"))
        }
        // The Q5-vs-Q8 findings, as the doc states them, are on the Q8 rows.
        val small = TierThroughputRecord.forTier("small-q8")!!.verdict as ThroughputVerdict.Measured
        assertTrue("small-q8 must state the ratio to multi (2.2x)", small.because.contains("2.2x"))
        val mediumQ5 = TierThroughputRecord.forTier("medium-q5")!!.verdict as ThroughputVerdict.Measured
        assertTrue("medium-q5 must state the ratio to medium-q8 (6.9x)", mediumQ5.because.contains("6.9x"))
        assertTrue("medium-q5 must record the runaway regime", mediumQ5.because.contains("queue"))
        // And the two untimed Q5 rungs say WHY they have no number.
        listOf("ultra", "large-v3").forEach {
            val u = TierThroughputRecord.forTier(it)!!.verdict as ThroughputVerdict.Unmeasured
            assertTrue("'$it' must record that it was retired unmeasured on 2026-09-17", u.action.contains("RETIRED UNMEASURED") && u.action.contains("2026-09-17"))
            assertTrue("'$it' must cite the doc", u.action.contains(tabDoc))
            assertTrue("'$it' is retired", WhisperCatalog.byId(it)!!.retired)
        }
    }

    /**
     * **A VERDICT IS MEASURED AT A FLOOR, NOT ABOUT A TIER**, and this is the exact analogue of the
     * clearance record's `pinnedCommit`: *"a clearance is granted over BYTES, not over a repository
     * name."*
     *
     * `F = 1.217 s` is not a fact about whisper-small. It is a fact about whisper-small at the
     * floor the app paced it at, and the app's own eligibility rule is a RATIO —
     * `CommitCadencePolicy`: *"a tier keeps a floor only while its full-segment F is MEASURED and
     * `F/floor + m <= 0.70` at saturation."* So the floor is recorded in the measurement and held
     * equal to the app's own table: **re-pace a measured rung and its verdict must be re-earned,
     * not inherited.** 4.7.0 made exactly that move for `small-q8` — onto the 6 000 ms MULTI row,
     * by controller ruling — and this test is why its row was re-read at 6 000 (worst 1,993 ms =
     * 0.33 of the floor, F/floor + m ~0.24) rather than carried over from the 8 000 reading.
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
        // The one row whose sample was taken while the app paced a DIFFERENT floor, stated so
        // nobody rediscovers it: on versionCode 95 `small-q8` paced at 8 000 (worst 0.25); the doc's
        // table reads it against 6 000, and since 4.7.0 the app paces it there too. Its row carries
        // the floor it is paced at now and gives both readings.
        val small = TierThroughputRecord.forTier("small-q8")!!.verdict as ThroughputVerdict.Measured
        assertEquals(6_000L, small.measurement.commitFloorMs)
        assertEquals(CommitCadencePolicy.MIN_COMMIT_INTERVAL_MULTI_MS, small.measurement.commitFloorMs)
        assertTrue("small-q8's reading must give the 6 000 ms reading", small.because.contains("6 000"))
        assertTrue("small-q8's reading must record the 8 000 ms floor it was measured under", small.because.contains("8 000"))
        assertTrue("small-q8's reading must name the build that paced it at 8 000", small.because.contains("versionCode 95"))
    }

    /**
     * **An unmeasured rung is paced at the conservative floor.** The coupling runs the other way
     * too. Since 4.7 the only unmeasured rows are the two retired Q5 rungs, and they are on the
     * 8 s row via `else` exactly as 4.6 placed them; their users are still paced.
     */
    @Test fun an_unmeasured_rung_is_paced_at_the_conservative_large_floor() {
        val unmeasured = TierThroughputRecord.RECORD.filter { it.verdict is ThroughputVerdict.Unmeasured }
        assertTrue("the rule has subjects", unmeasured.isNotEmpty())
        unmeasured.forEach { row ->
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
     * **The committed state: PROMOTABLE — three measured rows, one owner ruling, and the switch
     * naming all three.**
     *
     * From 4.7 to 4.8 this test held `Withheld([small-q8, medium-q8, ultra-q8])` over an empty
     * switch, on the owner's word that the accuracy pass came first. 4.9 records that pass — his
     * report after dictating on all three on the Tab S10+, *"all three actually work very well"*
     * — and his ruling on turbo's margin, and the switch flips on those words. It is still not a
     * measurement arriving that flipped it: `ultra-q8`'s row is unchanged, and the test above
     * pins its outcome.
     */
    @Test fun the_committed_state_is_promotable_on_three_rows_one_ruling_and_the_owners_word() {
        // THE PROPERTY FIRST: whatever the switch says, the COMMITTED state may never be one of
        // the two defect states. This is what fails when somebody adds a rung to
        // PRODUCTION_PROMOTABLE without a clearing verdict — and it fails saying so.
        when (val committed = TierThroughputRecord.stateOfLadder()) {
            is ThroughputGateState.Overreached -> fail(
                "PRODUCTION_PROMOTABLE names ${committed.tiers}, whose throughput verdict does " +
                    "not clear them, or which are not selectable. A rung may enter the switch " +
                    "only on a clearing verdict (KEPT_UP, or a recorded owner ruling) AND the " +
                    "owner's word",
            )
            is ThroughputGateState.Unrecorded -> fail(
                "${committed.tiers} are in the chooser with no throughput row at all",
            )
            else -> Unit
        }
        // ...and then today's exact value.
        assertEquals(
            "the switch names the three Q8 rungs on the owner's word of 2026-09-17 — \"all three " +
                "actually work very well\"",
            setOf("small-q8", "medium-q8", "ultra-q8"),
            TierThroughputRecord.PRODUCTION_PROMOTABLE,
        )
        assertEquals(ThroughputGateState.Promotable, TierThroughputRecord.stateOfLadder())
        assertEquals("the ladder is the three Q8 rungs", listOf("small-q8", "medium-q8", "ultra-q8"), ladder)
        // The switch covers the ladder exactly — no stale entry, nothing missing.
        assertEquals(ladder.toSet(), TierThroughputRecord.PRODUCTION_PROMOTABLE)
    }

    /**
     * **A production switch that names a rung whose verdict does not clear FAILS.** The brief's
     * own acceptance, and the half of the gate that does the work: evidence arriving does not
     * promote anything, and the switch may not run ahead of the evidence either.
     *
     * Three ways to overreach, all held — and since 4.7 two of them are REAL rows rather than
     * constructed ones:
     *  - naming a rung whose verdict is [KeepUp.KEPT_UP_WITHOUT_MARGIN] with no ruling beside it
     *    (`ultra-q8`'s committed row with its 4.9 ruling stripped — the number alone);
     *  - naming a rung that is not in the ladder — `multi`, whose verdict CLEARS and which is
     *    RETIRED: the exact stale authorisation a switch left at `setOf("multi")` would have been
     *    on 2026-09-17;
     *  - naming a rung whose verdict is [ThroughputVerdict.Unmeasured] (`ultra`, retired).
     */
    @Test fun authorising_a_rung_whose_verdict_does_not_clear_is_a_red_suite() {
        val ultraUnruled = (TierThroughputRecord.ULTRA_Q8.verdict as ThroughputVerdict.Measured).copy(ownerRuling = null)
        val recordWithoutRuling = TierThroughputRecord.RECORD.map {
            if (it.tierId == "ultra-q8") TierThroughput("ultra-q8", ultraUnruled) else it
        }
        assertEquals(
            "KEPT_UP_WITHOUT_MARGIN is not a clearance on its number",
            ThroughputGateState.Overreached(listOf("ultra-q8")),
            TierThroughputRecord.state(authorised = setOf("small-q8", "ultra-q8"), tiers = ladder, record = recordWithoutRuling),
        )
        // ...and with the committed ruling on the row, the same switch is merely incomplete.
        assertEquals(
            ThroughputGateState.Withheld(listOf("medium-q8")),
            TierThroughputRecord.state(authorised = setOf("small-q8", "ultra-q8"), tiers = ladder),
        )
        assertEquals(
            "a switch entry for a rung that is no longer offered is stale, and reported rather " +
                "than ignored — this is what 4.6's setOf(\"multi\") became on the day of the ruling",
            ThroughputGateState.Overreached(listOf("multi")),
            TierThroughputRecord.state(authorised = setOf("small-q8", "multi"), tiers = ladder),
        )
        assertEquals(
            ThroughputGateState.Overreached(listOf("ultra")),
            TierThroughputRecord.state(authorised = setOf("medium-q8", "ultra"), tiers = ladder),
        )
        // ...and the two rungs that DO clear are accepted, which is what makes the refusals above
        // refusals rather than a gate that says no to everything.
        assertEquals(
            ThroughputGateState.Withheld(listOf("ultra-q8")),
            TierThroughputRecord.state(authorised = setOf("small-q8", "medium-q8"), tiers = ladder),
        )
    }

    /**
     * **MEASURED IS NOT CLEARED.** The gate's most mistakable property: a rung with a perfectly
     * good measurement showing that the typed text did not keep up — or kept up with no margin —
     * must not promote.
     *
     * All three non-clearing outcomes are held, and the middle two are the ones a short trial
     * reports as a pass. [KeepUp.RECOVERED_ONLY_IN_PAUSES]: under this app's `audio_ctx` floor the
     * cost per commit is constant, so a queue that drains only while the user is silent grows
     * whenever the user is not. [KeepUp.KEPT_UP_WITHOUT_MARGIN]: the typed text stayed with the
     * voice, but the worst commit sat at 0.99 of the floor on a flagship — the margin that would
     * survive a slower device is not there, and `ultra-q8`'s committed row is exactly this.
     */
    @Test fun a_rung_that_was_measured_and_did_not_clear_does_not_promote() {
        val base = (TierThroughputRecord.SMALL_Q8.verdict as ThroughputVerdict.Measured)
        listOf(KeepUp.NEVER_CAUGHT_UP, KeepUp.RECOVERED_ONLY_IN_PAUSES, KeepUp.KEPT_UP_WITHOUT_MARGIN).forEach { outcome ->
            val fell = base.copy(measurement = base.measurement.copy(outcome = outcome))
            assertFalse(
                "$outcome is a verdict, not a clearance — a measured non-pass may not promote",
                fell.clearsProduction,
            )
            assertEquals(
                "a switch naming a rung measured at $outcome must OVERREACH",
                ThroughputGateState.Overreached(listOf("small-q8")),
                TierThroughputRecord.state(
                    authorised = setOf("small-q8"),
                    tiers = listOf("small-q8"),
                    record = listOf(TierThroughput("small-q8", fell)),
                ),
            )
        }
        assertTrue("...and the recorded KEPT_UP row does clear", base.clearsProduction)
        // KEPT_UP is the ONLY outcome that clears on its own — stated over the whole enum so a
        // fifth value cannot arrive clearing by default. (4.9: an owner ruling on the row is the
        // one other way, and the ruling test holds it separately.)
        assertEquals(
            listOf(KeepUp.KEPT_UP),
            KeepUp.entries.filter { base.copy(measurement = base.measurement.copy(outcome = it)).clearsProduction },
        )
        // The committed instance of the new value: on its NUMBER it does not clear; it clears on
        // the owner's ruling recorded beside it, and on nothing else.
        val ultra = TierThroughputRecord.ULTRA_Q8.verdict as ThroughputVerdict.Measured
        assertFalse(ultra.copy(ownerRuling = null).clearsProduction)
        assertTrue(ultra.clearsProduction)
    }

    /**
     * **Every state, including the [ThroughputGateState.Promotable] cell the committed values
     * cannot reach.** A gate whose only tested value is "no" is not a gate that has been tested —
     * the same reason `PackClearanceRecord.state` takes all three inputs as parameters.
     *
     * The severity ORDER is asserted with them, and it matters: an [ThroughputGateState
     * .Unrecorded] rung is reported ahead of a [ThroughputGateState.Withheld] one, so a rung
     * that slipped into the chooser with no row at all can never read as a reassuring "not yet
     * authorised".
     */
    @Test fun the_gate_answers_every_state_including_promotable() {
        val cleared = TierThroughputRecord.SMALL_Q8.verdict
        val one = listOf(TierThroughput("small-q8", cleared))

        assertEquals(
            ThroughputGateState.Promotable,
            TierThroughputRecord.state(setOf("small-q8"), listOf("small-q8"), one),
        )
        assertEquals(
            ThroughputGateState.Withheld(listOf("small-q8")),
            TierThroughputRecord.state(emptySet(), listOf("small-q8"), one),
        )
        assertEquals(
            ThroughputGateState.Unrecorded(listOf("large-v3")),
            TierThroughputRecord.state(setOf("small-q8"), listOf("small-q8", "large-v3"), one),
        )
        // SEVERITY: a rung with no row outranks an unauthorised one, even when the switch is empty
        // and Withheld would also be true.
        assertEquals(
            "an incomplete record must never read as a reassuring 'not yet authorised'",
            ThroughputGateState.Unrecorded(listOf("large-v3")),
            TierThroughputRecord.state(emptySet(), listOf("small-q8", "large-v3"), one),
        )
        // ...and it outranks an overreaching switch too.
        assertEquals(
            ThroughputGateState.Unrecorded(listOf("large-v3")),
            TierThroughputRecord.state(setOf("nope"), listOf("small-q8", "large-v3"), one),
        )
        // Promotable over the REAL ladder: all three authorised over the committed record, which
        // is 4.9's shape — and, without ultra-q8's ruling, the two clearing rungs authorised with
        // ultra-q8 gone from the chooser, which was the only shape 4.7 could have taken.
        assertEquals(
            ThroughputGateState.Promotable,
            TierThroughputRecord.state(setOf("small-q8", "medium-q8", "ultra-q8"), ladder),
        )
        assertEquals(
            ThroughputGateState.Promotable,
            TierThroughputRecord.state(setOf("small-q8", "medium-q8"), listOf("small-q8", "medium-q8")),
        )
    }

    // ----------------------------------------------- THE GATE'S BOUNDARY WITH THE APP

    /**
     * **THE GATE MUST NOT REACH THE CHOOSER, AND THIS IS THE ONLY TEST THAT CAN PROVE IT.**
     *
     * The obvious implementation of *"a rung whose verdict does not clear may not be promoted"* is
     * to filter those rungs out of [WhisperCatalog.pickable]. That would destroy the branch: the
     * owner asked to *"see all the models there so I can just select between them and try each
     * one"*, and he cannot measure — or, since 4.7, choose — a rung the app declines to offer him.
     * The failure would also be SILENT — cards simply absent. The rungs that left the chooser in
     * 4.7 left through [WhisperModel.retired], by his ruling.
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
            "ThroughputGateState",
            "PRODUCTION_PROMOTABLE",
            "KeepUp",
            "OwnerRuling",
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
                        "runtime. Every pickable rung in it is selectable and downloadable — " +
                        "the owner cannot choose a rung the app will not offer him. A COMMENT " +
                        "pointing at it is fine.",
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
     * and came back BEHIND or WITHOUT MARGIN, states the flag alone cannot express.
     *
     * The blast radius is the reason: [WhisperCatalog.DEFAULT_MODEL_ID] is what every path with no
     * pick on record falls back to, so a non-clearing rung here reaches precisely the users who
     * never made a choice. Since 4.7 both resolve to `small-q8`, KEPT_UP on the Tab.
     */
    @Test fun the_default_and_every_migration_target_carry_a_clearing_verdict() {
        val default = TierThroughputRecord.forTier(WhisperCatalog.DEFAULT_MODEL_ID)
        assertNotNull("the default rung has no throughput row at all", default)
        assertTrue(
            "DEFAULT_MODEL_ID is '${WhisperCatalog.DEFAULT_MODEL_ID}', whose throughput verdict " +
                "does not clear production. It is the fallback for every path with no pick on " +
                "record, so this hands an unproven finalizer to the users who made no choice",
            default!!.verdict.clearsProduction,
        )
        assertEquals("small-q8", WhisperCatalog.DEFAULT_MODEL_ID)
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
            assertFalse("the $scope migration target '$target' is retired", WhisperCatalog.byId(target)!!.retired)
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
     *  - **the three keep-up answers a session records.** The middle one is the point: a sheet
     *    offering only pass/fail would collect "fine" for every rung that recovered in a pause,
     *    which is the exact reading this gate exists to reject. (KEPT_UP_WITHOUT_MARGIN is derived
     *    from the numbers, not asked as a question.)
     *  - **a row per selectable rung, identified by the app's OWN size badge** rather than by a
     *    number typed into prose. Re-pin a rung and the sheet goes visibly stale instead of quietly
     *    wrong — the same rationale the clearance test gives for pinning each pack's badge.
     *  - **the two named comparisons**, without which the session is downloads rather than an
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
        assertTrue("§AN must exist — the device session needs its own rows", sheet.contains("## AN —"))
        val an = sheet.substringAfter("## AN —", "")
        assertTrue("§AN is where the rung rows live and it is not in the sheet", an.isNotBlank())
        // 4.9: the accuracy pass is on the sheet as the owner's REPORT, not as a WER measurement
        // (none exists), and the ruling that clears turbo is named where the gate reads it.
        assertTrue("§AN0 must record the owner's accuracy report verbatim", an.contains("all three actually work very well"))
        assertTrue("§AN0 must say it is his report, not a WER", an.contains("not as a WER"))
        assertTrue("§AN0 must name the ruling that clears ultra-q8", an.contains("ThroughputVerdict.OwnerRuling"))
        assertTrue("§AN0 must state the gate's current answer", an.contains("Promotable"))

        // The three answers, and the middle one especially: a pass/fail sheet would collect "fine"
        // for a rung that only drained while the owner was silent.
        listOf("KEPT UP", "RECOVERED IN PAUSES", "NEVER CAUGHT UP").forEach {
            assertTrue("§AN must offer the answer '$it'", an.contains(it))
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
            // rung, because the ladder does not share a floor and the owner has to compare
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
