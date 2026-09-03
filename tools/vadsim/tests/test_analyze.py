"""The analysis layer, on hand-built traces whose answers are arithmetic.

These are not ports of JVM fixtures — the app has no analysis layer. They exist because the
report's three headline claims ("this dip was dead-band", "this dip was N frames short",
"the gate was shut") are the ones the owner will act on, and a silently wrong one sends him
after the wrong knob.
"""

from __future__ import annotations

from vadsim import analyze
from vadsim.machine import BASE_MS, FRAME_MS, Tuning, gate_track, simulate

T = Tuning()
HANGOVER_FRAMES = T.hangover_frames()          # 12
MICRO_PAUSE_FRAMES = T.micro_pause_frames()    # 5


def test_a_dead_band_dip_is_classified_as_dead_band():
    """The owner's phrase: "pauses that are not quiet". A run parked at 0.42 never goes
    below RELEASE, so it stamps no pending end and cannot cut at any hangover."""
    probs = [0.9] * 20 + [0.42] * 30 + [0.9] * 20
    dips = analyze.find_dips(probs, T)
    assert len(dips) == 1
    d = dips[0]
    assert d.kind == "dead-band"
    assert d.below_release_frames == 0
    assert d.temp_end_ms is None
    assert d.cut_ms is None
    assert d.max_age_ms == 0
    assert d.gate_open is True, "the gate WAS open — that is what makes this a lost cut"


def test_a_true_silence_dip_reports_its_quiet_span_not_its_full_span():
    """The exact shape the owner is chasing, and the one the two histogram columns exist to
    separate: a 640 ms pause with only 192 ms of quiet in the middle of it.

    The age is only ever COMPARED on a below-RELEASE frame — a dead-band frame returns at
    SileroEndpointer.kt:555 before the hangover test — so the pending end stamped at the
    first quiet frame is never tested again once the mumble resumes. 160 ms of age against a
    350 ms hangover: no cut, at a pause a listener would call half a second long.
    """
    probs = [0.9] * 20 + [0.42] * 7 + [0.10] * 6 + [0.42] * 7 + [0.9] * 20
    (d,) = analyze.find_dips(probs, T)
    assert d.kind == "silence"
    assert d.n_frames == 20 and d.span_ms == 640
    assert d.below_release_frames == 6, "192 ms of the 640 is countable"
    assert d.temp_end_ms == BASE_MS + 27 * FRAME_MS       # the first quiet frame
    assert d.max_age_ms == (32 - 27) * FRAME_MS == 160    # the LAST quiet frame's age
    assert d.cut_ms is None, "160 ms of age cannot clear a 350 ms hangover"
    # And the machine agrees: 60 frames = 1 920 ms, inside the 4 s first cap, no commit.
    assert simulate(probs, T).commits == []
    # Raise RELEASE above the mumble and the whole 640 ms becomes countable — the same pause
    # now cuts. THAT is the knob this shape responds to, and it is not the hangover.
    (d2,) = analyze.find_dips(probs, Tuning(release=0.45))
    assert d2.below_release_frames == 20
    assert d2.cut_ms is not None
    assert len(simulate(probs, Tuning(release=0.45)).of_kind("vad")) == 1


def test_leading_silence_is_reported_as_gate_shut_and_uncuttable():
    """`onProb`'s `if (!speaking) return false` (SileroEndpointer.kt:559). Without the gate
    track the report would call a 20-frame leading silence a cut the hangover missed."""
    probs = [0.01] * 20 + [0.9] * 20
    dips = analyze.find_dips(probs, T)
    assert dips[0].gate_open is False
    assert dips[0].below_release_frames == 20, "it really is 640 ms of quiet"
    assert dips[0].cut_ms is None, "and it still cannot cut: there is no utterance to end"
    assert simulate(probs, T).commits == []


def test_a_dip_after_a_min_speech_discard_is_gate_shut_too():
    """The discard calls `closeGate()` (SileroEndpointer.kt:592), so every frame of the
    silence that follows takes the `!speaking` return. This is THE KNOWN GAP's signature."""
    burst = T.min_speech_ms // FRAME_MS               # 9 frames = 288 ms, under the floor
    probs = [0.9] * burst + [0.01] * 60 + [0.9] * burst + [0.01] * 60
    gate = gate_track(probs, T)
    dips = analyze.find_dips(probs, T, gate=gate)
    assert len(dips) == 2
    for d in dips:
        assert d.gate_open is True, "each dip BEGINS with the gate open"
        assert d.cut_ms is not None, "and each reaches the hangover"
    # ...and each of those endpoints is a DISCARD, not a VAD commit. 138 frames = 4 416 ms,
    # so the 4 s first cap is the only thing that ever fires.
    r = simulate(probs, T)
    assert r.of_kind("vad") == []
    assert len(r.of_kind("cap")) == 1
    assert r.discards_total == 2
    # The gate is shut for the tail of each dip, which is what the track records.
    assert gate[burst] is True and gate[burst + 20] is False


def test_the_histogram_splits_full_span_from_quiet_span():
    """One dip, 640 ms long, 192 ms quiet: `384-512` on the left is EMPTY and `512-1000`
    holds it, while the right column puts it in `192-320`."""
    probs = [0.9] * 20 + [0.42] * 7 + [0.10] * 6 + [0.42] * 7 + [0.9] * 20
    hist = analyze.pause_histogram(analyze.find_dips(probs, T))
    rows = {h.label: h for h in hist}
    assert rows["512-1000"].below_onset == 1
    assert rows["192-320"].quiet_span == 1
    assert rows["512-1000"].quiet_span == 0
    assert sum(h.below_onset for h in hist) == 1
    assert sum(h.quiet_span for h in hist) == 1


def test_the_dead_band_fraction_moves_only_with_release():
    probs = [0.42] * 10 + [0.9] * 10
    assert analyze.dead_band_fraction(probs, Tuning(release=0.35)) == 0.5
    assert analyze.dead_band_fraction(probs, Tuning(release=0.45)) == 0.0
    assert analyze.dead_band_fraction(probs, Tuning(hangover_ms=800)) == 0.5


def test_cap_forensics_names_the_dead_band_as_the_reason():
    """The chunk the owner is chasing: a cap cut over audio whose only pauses are not
    quiet. The verdict must say RELEASE, not hangover."""
    probs = [0.9] * 20 + ([0.42] * 10 + [0.9] * 10) * 40
    r = simulate(probs, T)
    dips = analyze.find_dips(probs, T)
    fx = analyze.cap_forensics(r, dips, T)
    assert fx, "the cap must have fired"
    first = fx[0]
    assert first.longest_silence_ms == 0
    assert first.longest_dead_band_ms > 0
    assert "ALL dead-band" in first.verdict and "RELEASE" in first.verdict


def test_cap_forensics_counts_how_many_frames_short_the_best_pause_was():
    """A pause of HANGOVER_FRAMES - 2 quiet frames inside a capped window: the verdict must
    say exactly how many frames it lacked and what hangover would have taken it."""
    quiet = HANGOVER_FRAMES - 2                       # 10 frames, max age 288 ms
    probs = [0.9] * 20 + ([0.01] * quiet + [0.9] * 20) * 20
    r = simulate(probs, T)
    dips = analyze.find_dips(probs, T)
    fx = analyze.cap_forensics(r, dips, T)
    assert fx
    first = fx[0]
    assert first.longest_silence_ms == quiet * FRAME_MS          # 320 ms
    assert first.best_age_ms == (quiet - 1) * FRAME_MS           # 288 ms
    assert first.frames_short == 2
    assert "2 frame(s) short" in first.verdict
    assert "<= 288 ms" in first.verdict


def test_cap_forensics_blames_the_governor_when_a_cuttable_pause_was_merged():
    """The other reason a chunk is long: the pause WAS cuttable and the 2 000 ms turbo floor
    merged it. The report must not send the owner after the hangover for this one."""
    # Endpoints every 384 + 320 = 704 ms — inside the 2 000 ms floor, so 2 of every 3 merge.
    probs = [0.9] * 10 + ([0.01] * HANGOVER_FRAMES + [0.9] * 10) * 40
    r = simulate(probs, T)
    dips = analyze.find_dips(probs, T)
    fx = analyze.cap_forensics(r, dips, T)
    merged = [f for f in fx if "GOVERNOR merged" in f.verdict]
    assert r.merges_total > 0
    if fx:
        assert all(f.frames_short == 0 for f in fx), "the pauses were long enough"
        assert merged or all(f.merged_inside == 0 for f in fx)


def test_the_sweep_covers_the_whole_grid_and_is_monotone_in_the_hangover():
    """36 rows, and a longer hangover can never produce MORE VAD cuts on the same trace."""
    probs = [0.9] * 20 + ([0.01] * HANGOVER_FRAMES + [0.9] * 20) * 30
    rows = analyze.sweep(probs, T)
    assert len(rows) == len(analyze.SWEEP_HANGOVERS) * len(analyze.SWEEP_RELEASES) * len(
        analyze.SWEEP_CAPS
    ) == 36
    by_hangover = {}
    for r in rows:
        if r.release == 0.35 and r.cap_ms == 15_000:
            by_hangover[r.hangover_ms] = r.vad
    ordered = [by_hangover[h] for h in sorted(by_hangover)]
    assert ordered == sorted(ordered, reverse=True), (
        f"a longer hangover must not cut more often: {by_hangover}"
    )
    assert all(r.turbo_duty >= 0.0 for r in rows)


def test_the_sweep_uses_the_turbo_floor_by_default():
    """`CommitCadencePolicy.MIN_COMMIT_INTERVAL_TURBO_MS` = 2 000 (CommitCadencePolicy.kt:107)."""
    probs = [0.9] * 20 + ([0.01] * HANGOVER_FRAMES + [0.9] * 20) * 30
    tight = analyze.sweep(probs, T, floor_ms=0)
    paced = analyze.sweep(probs, T, floor_ms=2_000)
    assert sum(r.commits for r in tight) > sum(r.commits for r in paced)
    assert sum(r.merges for r in paced) > 0


# =======================================================================================
# Verifier additions (2026-09-03): the analysis layer must explain what the MACHINE did, not
# re-derive it from the trace. These are the three places it disagreed with the simulation.
# =======================================================================================

def test_a_pause_the_cap_fired_into_is_not_reported_as_a_cut_the_hangover_missed():
    """The straddle: the dip is 3 frames old when the 4 s cap fires. The machine's
    `endpointer.reset()` (FloatingBubbleService.kt:2110) zeroes `tempEndMs` and shuts the
    gate, so the remaining 20 quiet frames cannot cut — `simulate()` emits exactly one cap
    commit and no VAD. The old analysis called this dip "reaches the hangover" and the
    forensics said INVESTIGATE; the truth is "the cap won the race by 254 ms"."""
    speech = 122
    probs = [0.9] * speech + [0.1] * 24 + [0.9] * 40
    r = simulate(probs, T)
    assert [c.kind for c in r.commits] == ["cap"]
    (d,) = analyze.find_dips(probs, T)
    assert d.gate_open is True
    assert d.outcome == "cap"
    assert d.event_ms == BASE_MS + 4_000
    assert d.cut_ms is None, "the machine never got past the hangover guard on this dip"
    assert d.quiet_frames_at_event == 4 and d.age_at_event_ms == 96
    assert d.max_age_ms == 96, "the age is clipped where the machine stopped measuring"
    assert d.below_release_frames == 24, "the PHYSICAL quiet span is still the whole dip"
    (f,) = analyze.cap_forensics(r, analyze.find_dips(probs, T), T)
    assert "INTO a pause" in f.verdict and "cap won the race by 254 ms" in f.verdict
    assert "INVESTIGATE" not in f.verdict
    assert f.frames_short == HANGOVER_FRAMES - 4
    assert "hangover <= 96 ms" in f.verdict


def test_a_cap_that_cut_at_a_fresh_micro_pause_says_the_boundary_is_right():
    """Same race, dip 224 ms old at the cap: the offer stands and the cap cuts AT the pause's
    start (retain 224). The verdict must say the boundary is right and only the timing is
    the cap's — this is the shipped mechanism working, not a tuning problem."""
    speech = 118
    probs = [0.9] * speech + [0.1] * 30 + [0.9] * 40
    r = simulate(probs, T)
    assert r.commits[0].retain_ms == 224
    dips = analyze.find_dips(probs, T)
    (d,) = dips
    assert d.outcome == "cap" and d.age_at_event_ms == 224 and d.cut_ms is None
    (f,) = analyze.cap_forensics(r, dips, T)
    assert "cut AT that pause's start (retained 224 ms" in f.verdict
    assert "boundary is right" in f.verdict
    assert f.retain_ms == 224


def test_the_dips_table_reports_the_machines_outcome_for_every_dip():
    """Five dips, five different outcomes — read off `machine.event_track`, so the table
    cannot say `cuts? YES` about a pause that was discarded or merged."""
    t6 = Tuning(min_commit_interval_ms=6_000)
    burst = T.min_speech_ms // FRAME_MS                    # 9 frames, under MIN_SPEECH_MS
    probs = (
        [0.01] * 5                                         # leading silence: gate-shut
        + [0.9] * 20 + [0.1] * HANGOVER_FRAMES             # -> vad
        + [0.9] * 20 + [0.1] * HANGOVER_FRAMES             # -> merge (inside 6 000)
        + [0.9] * burst + [0.1] * HANGOVER_FRAMES          # -> discard
        + [0.9] * 20 + [0.1] * 4                           # -> none (too young)
        + [0.9] * 5
    )
    dips = analyze.find_dips(probs, t6)
    assert [d.outcome for d in dips] == ["gate-shut", "vad", "merge", "discard", "none"]
    assert [d.cut_ms is not None for d in dips] == [False, True, True, True, False]
    r = simulate(probs, t6)
    assert [c.kind for c in r.commits] == ["vad"] and r.merges_total == 1 and r.discards_total == 1


def test_a_discard_verdict_survives_dead_band_frames_inside_the_dip():
    """The ordering bug in the old forensics: a dip with dead-band frames INSIDE it reaches
    the hangover (dead-band frames do not reset the clock, SileroEndpointer.kt:549-555) with
    fewer than `hangover_frames()` QUIET frames. The old code tested `frames_short == 0` before
    looking at the discard and would have said "3 frame(s) short — a hangover of <= 256 ms
    would have cut it". The machine DID judge this pause: it discarded a 288 ms burst."""
    burst = T.min_speech_ms // FRAME_MS                    # 9 frames = 288 ms
    dip = [0.01] * 4 + [0.42] * 4 + [0.01] * 5             # 13 frames: age 384 on the last
    probs = [0.9] * 20 + [0.1] * HANGOVER_FRAMES + ([0.9] * burst + dip) * 3 + [0.42] * 600
    r = simulate(probs, T)
    assert r.discards_total == 3
    dips = analyze.find_dips(probs, T)
    discarded = [d for d in dips if d.outcome == "discard"]
    assert len(discarded) == 3
    assert all(d.below_release_frames == 9 < HANGOVER_FRAMES for d in discarded)
    assert all(d.max_age_ms == 352 for d in discarded), "reached the hangover THROUGH the mumble"
    fx = analyze.cap_forensics(r, dips, T)
    assert fx
    assert "KNOWN GAP" in fx[0].verdict and "frame(s) short" not in fx[0].verdict
    assert fx[0].frames_short == 0


def test_frames_short_counts_age_not_quiet_frames():
    """A pause of 6 quiet + 3 dead-band + 2 quiet frames (gate open, well inside a cap
    window): the machine saw an age of 320 ms on its last quiet frame, so it was ONE frame
    short of 350, not `12 - 8 = 4`."""
    dip = [0.01] * 6 + [0.42] * 3 + [0.01] * 2
    probs = [0.9] * 20 + dip + [0.9] * 20 + [0.42] * 600
    r = simulate(probs, T)
    dips = analyze.find_dips(probs, T)
    d = next(x for x in dips if x.kind == "silence")
    assert d.below_release_frames == 8 and d.max_age_ms == 320 and d.outcome == "none"
    fx = analyze.cap_forensics(r, dips, T)
    assert len(fx) == 2, "651 frames = 20.8 s: the 4 s cap (consumed) then the 15 s cap"
    f = fx[0]
    assert f.frames_short == 1
    assert "1 frame(s) short" in f.verdict and "<= 320 ms" in f.verdict


def test_event_track_agrees_with_simulate_frame_for_frame():
    from vadsim.machine import event_track

    probs = [0.9] * 20 + ([0.01] * HANGOVER_FRAMES + [0.9] * 20) * 30
    r = simulate(probs, T)
    gate, events = event_track(probs, T)
    assert len(gate) == len(events) == len(probs)
    stamped = {(c.kind, (c.t_ms - BASE_MS) // FRAME_MS) for c in r.commits}
    tracked = {(e.split("+")[-1], i) for i, e in enumerate(events) if e and e.split("+")[-1] in ("vad", "cap")}
    assert stamped == tracked
    assert sum(1 for e in events if "merge" in e) == r.merges_total
