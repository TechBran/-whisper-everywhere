package com.whispereverywhere.audio

/**
 * THE FRAME GRID: what [EndpointerTuning]'s WALL-CLOCK durations mean in whole 32 ms frames.
 *
 * TEST SOURCE SET ONLY, and deliberately so. Production reads [EndpointerTuning.HANGOVER_MS] in
 * exactly one place (`SileroEndpointer.onProb`, the `nowMs - tempEndMs < HANGOVER_MS` guard) and
 * knows nothing about frame COUNTS; counts are a property of the 32 ms pump the fixtures drive, so
 * a constant expressing them belongs with the fixtures.
 *
 * ## Why this object exists
 * Before it, thirteen fixtures spelled the hangover as `17`, `16`, `512`, `896`, `BASE + 1152`,
 * `BASE + 2048`, `BASE + 2944` — arithmetic COINCIDENCES of the shipped 500, not properties. The
 * house rule is that tests pin properties, and the cost of breaking it was measured: an owner A/B
 * inside the constant's own documented 350-800 range turned fifteen green tests red, none of which
 * was about the hangover's value.
 *
 * ## The discipline this object enforces
 * ONE absolute pin, everywhere else derived. `EndpointerTuningTest.the_shipped_tuning_table_is_
 * pinned_verbatim` holds the only literal `500L`/`350L` in the suite; every fixture that needs to
 * know how many frames that is asks HERE. A purely differential suite would survive a mutation
 * that moved both sides, which is exactly why the absolute pin stays where it is and is not
 * re-derived here.
 *
 * ## The arithmetic, stated once
 * The dip's frames land on the 32 ms grid with the pending end stamped at the FIRST sub-RELEASE
 * frame (`SileroEndpointer.onProb`: `if (tempEndMs == 0L) tempEndMs = nowMs`), so the dip's k-th
 * frame has age `(k - 1) * FRAME_MS`, and the hangover's guard is INCLUSIVE on the cutting side
 * (native continues while `< min_silence_samples`, `whisper.cpp:5333`). The cut therefore lands on
 * the smallest k with `(k - 1) * FRAME_MS >= HANGOVER_MS`.
 *
 * `EndpointerGridTest.the_grid_matches_the_machine` is the cross-check that keeps this arithmetic honest: it drives a
 * real endpointer and asserts the cut lands where this object says. Two independent derivations —
 * this integer arithmetic and the state machine's `<` — agreeing is the property; either one alone
 * is a restatement.
 */
internal object EndpointerGrid {

    /**
     * The speech run every cadence fixture uses: `floor(MIN_SPEECH_MS / FRAME_MS) + 2` — 11 frames
     * = 352 ms at 300 ms.
     *
     * `+ 2` and not `+ 1` deliberately. `+ 1` is the shortest run that clears
     * [EndpointerTuning.MIN_SPEECH_MS] strictly (10 frames = 320 ms > 300), and a fixture standing
     * on that boundary is a fixture that also tests the boundary — which has its own test
     * (`SileroEndpointerTest.exactly_MIN_SPEECH_MS_does_not_cut_but_does_count_as_pending_speech`)
     * and does not want a dozen accidental co-owners. One whole frame of margin keeps these
     * fixtures about the CADENCE.
     *
     * Derived rather than left at 11 for the same reason everything else here is: a MIN_SPEECH_MS
     * A/B would otherwise re-open on this constant the identical fifteen-fixture problem the
     * hangover A/B opened on the other one.
     */
    val SPEECH_FRAMES_OVER_MIN: Int =
        (EndpointerTuning.MIN_SPEECH_MS / EndpointerTuning.FRAME_MS).toInt() + 2

    /**
     * The dip frame that CUTS: `ceil(HANGOVER_MS / FRAME_MS) + 1`. 17 at 500 ms, 12 at 350, 26 at
     * 800.
     */
    val HANGOVER_FRAMES: Int =
        ((EndpointerTuning.HANGOVER_MS + EndpointerTuning.FRAME_MS - 1) /
            EndpointerTuning.FRAME_MS).toInt() + 1

    /**
     * The LONGEST run of silence that cannot cut — one frame short of [HANGOVER_FRAMES].
     *
     * This is the count every "and it must NOT commit yet" fixture wants, and spelling it as its
     * own name is what stops those fixtures drifting into vacuity: a run chosen as a literal `10`
     * is a run that silently starts cutting the day the hangover drops under 352 ms.
     */
    val HOLD_FRAMES: Int = HANGOVER_FRAMES - 1

    /** The age of the LAST frame of a [HOLD_FRAMES] run: still under the hangover, by one frame. */
    val HOLD_TRAIL_MS: Long = (HOLD_FRAMES - 1) * EndpointerTuning.FRAME_MS

    /**
     * The trailing silence a pump-driven cut actually reports — `EndpointCut.trailMs`, and the
     * offset from the pending end to the committing frame. 512 ms at 500, 352 at 350.
     */
    val HANGOVER_TRAIL_MS: Long = HOLD_FRAMES * EndpointerTuning.FRAME_MS

    /**
     * The first dip frame that promotes the micro-pause: `floor(MICRO_PAUSE_MS / FRAME_MS) + 2`,
     * because the promotion is STRICT (`> MICRO_PAUSE_MS`). The 5th at 98 ms — 128 > 98, while the
     * 4th is only 96 ms old, which is the off-by-one `EndpointerTuning.MICRO_PAUSE_MS`'s own KDoc
     * records.
     */
    val MICRO_PAUSE_FRAMES: Int =
        (EndpointerTuning.MICRO_PAUSE_MS / EndpointerTuning.FRAME_MS).toInt() + 2

    /**
     * The dead-band run inside `SileroEndpointerTest.dead_band_frames_do_not_stall_the_hangover_hard_timer`: half of
     * what is left of a non-cutting dip once its first frame has stamped the pending end. The
     * remaining half stays silence, so the fixture always has at least one of each.
     */
    val DEAD_BAND_FRAMES: Int = (HOLD_FRAMES - 1) / 2

    /**
     * The cadence fixtures' shortest reachable inter-endpoint interval: 11 speech frames (352 ms,
     * over MIN_SPEECH_MS) plus one full dip. 896 ms at 500, 736 at 350, 1184 at 800.
     *
     * A multiple of [EndpointerTuning.FRAME_MS] at every hangover, which is what keeps
     * `CommitCadencePolicyTest`'s "this fixture can only reach cut attempts ON the 32 ms grid"
     * guard satisfied while the floors it pads out to (8000, 1200) stay literal.
     */
    val FIXTURE_INTERVAL_MS: Long =
        (SPEECH_FRAMES_OVER_MIN + HANGOVER_FRAMES) * EndpointerTuning.FRAME_MS

    /**
     * The two brackets `SileroEndpointerTest.before_any_session_start_the_floor_is_the_conservative_8000`
     * puts around `CommitCadencePolicy.MIN_COMMIT_INTERVAL_LARGE_MS` (8000, quoted rather than
     * imported: Workstream C compiles without the service package). ON the 32 ms grid, a
     * comfortable margin either side, so the fixture can hit them exactly by solving for its
     * silence padding.
     *
     * They live HERE, not in that fixture's own file, because
     * `EndpointerGridTest.the_fixture_grid_is_valid_for_this_hangover` has to assert that one
     * whole endpoint still FITS between them — the precondition that keeps the fixture's
     * `coerceAtLeast(0)` a decision rather than a silent degradation — and a second copy of the
     * two literals is exactly the drift this object exists to end.
     */
    const val BELOW_LARGE_MS = 6_976L
    const val ABOVE_LARGE_MS = 8_128L
}
