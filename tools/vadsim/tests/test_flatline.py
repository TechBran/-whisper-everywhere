"""THE FLATLINE PROPOSAL — synthetic (p, rms) traces at CHUNK granularity.

The trigger under test is `machine.SileroEndpointerSim._on_flat`, whose eight numbered
design decisions this file pins one at a time. It is a PROPOSAL: nothing in the app
implements it, it is OFF by default, and the last section here is the one that says so —
with the trigger disabled every fixture behaves exactly as it did before the RMS existed.

CHUNK GRANULARITY is the premise of every trace below. The app computes ONE
`AudioMath.amplitude` per capture buffer (`StreamingAudioRecorder.kt:87`,
`PlaybackAudioCapturer.kt:81`) and that buffer is 32 ms on both paths, so at the shipped
`chunk_ms = 32` one chunk is one frame and `rms[i]` is frame `i`'s amplitude. The hold is
therefore only ever satisfiable in whole chunks — `Tuning.flatline_effective_hold_ms()`.

AMPLITUDE VOCABULARY used throughout, in AudioMath's 0..32767 units:
    3 000   a spoken word
      100   room tone in a natural pause (50-300 is the band; never zero)
        0   an editor's gate — digital silence, the thing this trigger exists to see
"""

from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path

import pytest

from vadsim import analyze
from vadsim.machine import (
    BASE_MS,
    FRAME_MS,
    SileroEndpointerSim,
    Tuning,
    simulate,
)

T = Tuning()

SPEECH_RMS = 3_000
ROOM_TONE_RMS = 100
GATED_RMS = 0

#: p values. The GAP probability is deliberately in the DEAD BAND: a dead-band frame is
#: inert (`onProb` returns at SileroEndpointer.kt:555 having written nothing), so Silero
#: can never cut these traces at ANY hangover — every commit in them is the flat trigger's.
P_SPEECH = 0.9
P_GAP_DEADBAND = 0.40
P_SILENCE = 0.1

CAPTURE = Path("C:/Users/bastr/.androidbuild/capture-yt-2000-0903-1207.txt")


def flat_tuning(rms: int, hold: int, **kw) -> Tuning:
    """The shipped tuning with the trigger ON. `min_commit_interval_ms` is passed
    explicitly by the tests that care; where it is not, the turbo floor stands."""
    from dataclasses import replace

    return replace(T, flatline_enabled=True, flatline_rms=rms, flatline_hold_ms=hold, **kw)


class Pump:
    """`SileroEndpointerTest.Pump` with an amplitude beside the probability: feeds whole
    frames of one `(p, rms)` pair at the 32 ms cadence."""

    def __init__(self, ep: SileroEndpointerSim, t: int = BASE_MS) -> None:
        self.ep = ep
        self.t = t
        self.commits = 0
        self.last_commit_ms = -1

    def run(self, p: float, rms: int, frames: int) -> bool:
        fired = False
        for _ in range(frames):
            if self.ep.on_frame(p, self.t, rms):
                fired = True
                self.commits += 1
                self.last_commit_ms = self.t
            self.t += FRAME_MS
        return fired


def gated_trace(words: int = 4, word_frames: int = 12, gap_frames: int = 5):
    """EDITED VIDEO, the shape the owner described: words at full amplitude separated by
    gaps of DIGITAL SILENCE that are far too short for the hangover.

    `gap_frames = 5` is 160 ms — inside the 100-300 ms an editor leaves and less than half
    the 352 ms the hangover needs. The gap's `p` sits in the dead band, so Silero writes
    nothing at all during it: without the trigger this trace can only ever be cut by the
    wall cap.
    """
    probs, rms = [], []
    for _ in range(words):
        probs += [P_SPEECH] * word_frames
        rms += [SPEECH_RMS] * word_frames
        probs += [P_GAP_DEADBAND] * gap_frames
        rms += [GATED_RMS] * gap_frames
    return probs, rms


def natural_trace(words: int = 4, word_frames: int = 12, pause_frames: int = 11):
    """NATURAL SPEECH: real pauses, with real room tone in them.

    `pause_frames = 11` is the longest dip the shipped hangover CANNOT cut
    (`Tuning.hold_frames()`), so this trace commits nothing on its own — which makes any
    commit the trigger's doing, and there must not be one.
    """
    probs, rms = [], []
    for _ in range(words):
        probs += [P_SPEECH] * word_frames
        rms += [SPEECH_RMS] * word_frames
        probs += [P_SILENCE] * pause_frames
        rms += [ROOM_TONE_RMS] * pause_frames
    return probs, rms


# =======================================================================================
# 1. Natural speech is UNTOUCHED — the property the whole proposal rests on.
# =======================================================================================

@pytest.mark.parametrize("hold", [96, 128, 160, 224, 320, 1_000])
def test_natural_speech_with_room_tone_never_fires_the_flat_trigger(hold):
    """Room tone measures 50-300; a threshold of 40 sits under all of it, so the flat run
    can never even START. This is the claim that makes the trigger safe on unedited audio
    and it must hold at EVERY hold, not just the long ones."""
    probs, rms = natural_trace()
    off = simulate(probs, T)
    on = simulate(probs, flat_tuning(40, hold), rms=rms)
    assert [c.kind for c in on.commits] == [c.kind for c in off.commits]
    assert [c.t_ms for c in on.commits] == [c.t_ms for c in off.commits]
    assert on.of_kind("flat") == []


def test_the_sweep_straddles_the_room_tone_band_on_purpose():
    """WHERE THE SAFETY ARGUMENT ENDS. Room tone measures 50-300, so only the bottom of
    the swept axis — 10, 20, 40 — is under ALL of it and safe by construction. 80 and 160
    reach into the band: on a quiet talker in a quiet room they can fire on real silence,
    which is a different (and much better) failure than firing inside a word, but it is
    not the "cannot fire on natural audio" property the low values have. The sweep carries
    them anyway, because the histogram in section 10 is what says which side of the band a
    given clip's room tone is on."""
    safe = [v for v in analyze.FLAT_SWEEP_RMS if v <= 40]
    reaches_room_tone = [v for v in analyze.FLAT_SWEEP_RMS if v > 50]
    assert safe == [10, 20, 40]
    assert reaches_room_tone == [80, 160]
    # And the fixture's own room tone is above the safe band, which is what makes the
    # parametrised test above a real test of the threshold and not of the audio.
    assert ROOM_TONE_RMS > max(safe)


# =======================================================================================
# 2. Gated (edited) audio IS cut — at 128, and not at 224.
# =======================================================================================

def test_gated_audio_is_cut_at_hold_128_and_not_at_hold_224():
    """A 160 ms gap of digital silence is FIVE chunks. A hold of 128 ms fires on the run's
    fifth frame (ages 0/32/64/96/128), so it just fits; a hold of 224 needs eight, so it
    cannot. The governor is taken out of the picture (floor 0) — its own behaviour has its
    own fixture below."""
    probs, rms = gated_trace()
    cut = simulate(probs, flat_tuning(40, 128, min_commit_interval_ms=0), rms=rms)
    nocut = simulate(probs, flat_tuning(40, 224, min_commit_interval_ms=0), rms=rms)

    assert [c.kind for c in cut.commits] == ["flat"] * 4, "one per gap"
    # The first gap begins at frame 12 and the fifth of its frames is frame 16.
    assert cut.commits[0].t_ms == BASE_MS + 16 * FRAME_MS
    assert cut.commits[0].speech_ms == 12 * FRAME_MS      # measured to the FIRST flat frame
    assert cut.commits[0].trail_ms == 128                 # the hold, exactly
    assert cut.commits[0].rms == GATED_RMS

    assert nocut.commits == [], "eight flat chunks are not available in a five-chunk gap"


def test_the_same_gated_audio_commits_nothing_without_the_trigger():
    """The baseline the row above is a delta against: dead-band gaps mean Silero never
    stamps a pending end, so the shipped machine rides the wall cap — and this trace is
    shorter than the 4 s first cap, so it commits NOTHING."""
    probs, rms = gated_trace()
    off = simulate(probs, T, rms=rms)
    assert off.commits == []
    assert len(probs) * FRAME_MS < T.first_cap_ms, "no cap fires inside this fixture"


def test_the_hold_resolves_to_whole_chunks():
    """One RMS per chunk means two holds inside one chunk are the same rule. 100, 110 and
    128 all resolve to 128 ms at the shipped 32 ms chunk, and all three cut identically."""
    probs, rms = gated_trace()
    runs = [
        simulate(probs, flat_tuning(40, h, min_commit_interval_ms=0), rms=rms)
        for h in (100, 110, 128)
    ]
    assert {flat_tuning(40, h).flatline_effective_hold_ms() for h in (100, 110, 128)} == {128}
    assert all([c.t_ms for c in r.commits] == [c.t_ms for c in runs[0].commits] for r in runs)


# =======================================================================================
# 3. A stop closure inside a word must survive.
# =======================================================================================

def test_a_96ms_stop_closure_inside_a_word_is_not_cut_at_hold_128():
    """A plosive closure is 50-150 ms of near-silence in the MIDDLE of a word, and Silero
    keeps calling it speech. Three flat chunks (96 ms) are one short of the four a 96 ms
    hold needs and three short of the five a 128 ms hold needs, so neither cuts."""
    probs = [P_SPEECH] * 12 + [P_SPEECH] * 3 + [P_SPEECH] * 12
    rms = [SPEECH_RMS] * 12 + [GATED_RMS] * 3 + [SPEECH_RMS] * 12
    for hold in (96, 128):
        r = simulate(probs, flat_tuning(40, hold, min_commit_interval_ms=0), rms=rms)
        assert r.of_kind("flat") == [], f"hold {hold} cut inside a word"


def test_the_stop_closure_fixture_is_not_vacuous():
    """The same three chunks DO fire a hold of 64 ms (which needs three), so the fixture
    above is measuring the hold and not some other reason nothing happened. The cut lands
    on the third flat frame and Silero calls that frame speech — which is exactly what the
    MID-WORD RISK column is for, and it counts it."""
    probs = [P_SPEECH] * 12 + [P_SPEECH] * 3 + [P_SPEECH] * 12
    rms = [SPEECH_RMS] * 12 + [GATED_RMS] * 3 + [SPEECH_RMS] * 12
    t = flat_tuning(40, 64, min_commit_interval_ms=0)
    r = simulate(probs, t, rms=rms)
    assert [c.kind for c in r.commits] == ["flat"]
    assert r.commits[0].t_ms == BASE_MS + 14 * FRAME_MS
    assert analyze.mid_word_risk_frames(r, probs, t) == [14]


# =======================================================================================
# 4. The governor's MERGE branch, reached from the flat side.
# =======================================================================================

def test_a_flat_endpoint_inside_the_governor_window_merges_and_keeps_the_pending_buffer():
    """`onProb`'s merge branch (SileroEndpointer.kt:596-616) is shared, not copied: the
    flat close runs the same test, closes the gate the same way and leaves `pendingSpeech`
    standing because that audio really is still in the caller's buffer."""
    probs, rms = gated_trace(words=2)
    # At the shipped 2 000 ms turbo floor the second flat endpoint arrives 544 ms after
    # the first commit — well inside the window.
    r = simulate(probs, flat_tuning(40, 128, min_commit_interval_ms=2_000), rms=rms)
    assert [c.kind for c in r.commits] == ["flat"], "the first cut is free; the second merges"
    assert r.flat_merges_total == 1
    assert r.merges_total == 1, "a flat merge is counted in the shared merge total too"


def test_a_merged_flat_endpoint_leaves_pending_speech_true():
    ep = SileroEndpointerSim(flat_tuning(40, 128, min_commit_interval_ms=2_000))
    ep.on_session_start(now_ms=BASE_MS, min_commit_interval_ms=2_000)
    pump = Pump(ep)
    pump.run(P_SPEECH, SPEECH_RMS, 12)
    assert pump.run(P_GAP_DEADBAND, GATED_RMS, 5), "the first cut is free"
    pump.run(P_SPEECH, SPEECH_RMS, 12)
    assert not pump.run(P_GAP_DEADBAND, GATED_RMS, 5), "inside the floor: merged"
    assert ep.flat_merges == 1
    assert ep.has_pending_speech() is True, "the merged audio is still in the buffer"
    assert ep.speaking is False, "and the gate was closed, so the next pause is judged afresh"


# =======================================================================================
# 5. MIN_SPEECH, reached from the flat side.
# =======================================================================================

def test_a_flat_run_after_too_little_speech_is_discarded_like_a_short_burst():
    """`speechMs` is measured to the FIRST flat frame, exactly as `onProb` measures to the
    pending end (:583). Five frames of speech is 160 ms — under MIN_SPEECH_MS — so the
    flat close discards it and closes the gate, committing nothing."""
    ep = SileroEndpointerSim(flat_tuning(40, 128, min_commit_interval_ms=0))
    ep.on_session_start(now_ms=BASE_MS, min_commit_interval_ms=0)
    pump = Pump(ep)
    pump.run(P_SPEECH, SPEECH_RMS, 5)                      # 160 ms <= MIN_SPEECH_MS (300)
    assert not pump.run(P_GAP_DEADBAND, GATED_RMS, 5)
    assert ep.flat_discards == 1 and ep.discards == 1
    assert ep.speaking is False and ep.temp_end_ms == 0
    assert ep.has_pending_speech() is False


def test_exactly_MIN_SPEECH_MS_of_speech_is_discarded_by_the_flat_close_too():
    """STRICT, as `:584` is: exactly MIN_SPEECH_MS is discarded. Driven off the frame grid
    so `<=` versus `<` is visible — the gate opens at BASE and the flat run starts at
    BASE + 300."""
    t = flat_tuning(40, 128, min_commit_interval_ms=0)
    ep = SileroEndpointerSim(t)
    ep.on_session_start(now_ms=BASE_MS, min_commit_interval_ms=0)
    assert not ep.on_frame(P_SPEECH, BASE_MS, SPEECH_RMS)
    for k in range(5):
        fired = ep.on_frame(P_GAP_DEADBAND, BASE_MS + T.min_speech_ms + k * FRAME_MS,
                            GATED_RMS)
        assert not fired
    assert ep.flat_discards == 1


# =======================================================================================
# 6. Silero and the flat hold on ONE frame — Silero wins, one commit.
# =======================================================================================

def test_the_hangover_and_the_flat_hold_on_the_same_frame_produce_exactly_one_commit():
    """A dip that is BOTH below RELEASE and digitally silent, with the hold set to the
    hangover's own trail (352 ms) so both come due on dip frame 12. `onFrame` evaluates
    the flat trigger only after `onProb` declined the frame, so Silero takes it — and the
    commit that results is a `vad`, with the flat counters untouched."""
    probs = [P_SPEECH] * 20 + [P_SILENCE] * 12
    rms = [SPEECH_RMS] * 20 + [GATED_RMS] * 12
    t = flat_tuning(40, T.hangover_trail_ms(), min_commit_interval_ms=0)
    assert t.flatline_frames() == T.hangover_frames() == 12, "both fire on dip frame 12"

    r = simulate(probs, t, rms=rms)
    assert [c.kind for c in r.commits] == ["vad"], "exactly one commit, and Silero's"
    assert r.commits[0].t_ms == BASE_MS + (20 + 11) * FRAME_MS
    assert r.of_kind("flat") == []
    assert r.flat_merges_total == 0 and r.flat_discards_total == 0


def test_a_shorter_hold_lets_the_flat_trigger_win_the_same_dip_earlier():
    """The mirror of the test above: at a hold of 128 the flat close fires on dip frame 5,
    seven frames before Silero's hangover would have — one commit, kind `flat`."""
    probs = [P_SPEECH] * 20 + [P_SILENCE] * 12
    rms = [SPEECH_RMS] * 20 + [GATED_RMS] * 12
    r = simulate(probs, flat_tuning(40, 128, min_commit_interval_ms=0), rms=rms)
    assert [c.kind for c in r.commits] == ["flat"]
    assert r.commits[0].t_ms == BASE_MS + (20 + 4) * FRAME_MS


def test_a_commit_clears_the_flat_run_so_the_next_gap_starts_it_afresh():
    """`commitAt` -> `clearForNextSegment` -> `closeGate` takes the flat run with it
    (DECISION 3/7), so a run cannot be counted across a commit."""
    ep = SileroEndpointerSim(flat_tuning(40, 128, min_commit_interval_ms=0))
    ep.on_session_start(now_ms=BASE_MS, min_commit_interval_ms=0)
    pump = Pump(ep)
    pump.run(P_SPEECH, SPEECH_RMS, 12)
    assert pump.run(P_GAP_DEADBAND, GATED_RMS, 5)
    assert (ep.flat_run_frames, ep.flat_run_start_ms) == (0, 0)


def test_the_flat_run_is_not_counted_while_the_gate_is_shut():
    """DECISION 3. Leading digital silence must not arm the trigger: the run only starts
    once an onset has opened the gate, so `speechMs` can never come out negative."""
    ep = SileroEndpointerSim(flat_tuning(40, 128, min_commit_interval_ms=0))
    ep.on_session_start(now_ms=BASE_MS, min_commit_interval_ms=0)
    pump = Pump(ep)
    pump.run(P_SILENCE, GATED_RMS, 40)                     # 1.28 s of digital silence
    assert ep.flat_run_frames == 0 and not ep.speaking
    pump.run(P_SPEECH, SPEECH_RMS, 12)                     # then a real word
    assert not pump.run(P_GAP_DEADBAND, GATED_RMS, 4), "four chunks is one short of the hold"
    assert pump.run(P_GAP_DEADBAND, GATED_RMS, 1), "the fifth fires, measured from the gap"
    assert ep.last_cut is not None and ep.last_cut.speech_ms == 12 * FRAME_MS


def test_a_flat_run_that_ends_early_does_not_disturb_a_pending_end_silero_stamped():
    """DECISION 7. Silero stamps `tempEndMs` on the dip's first sub-RELEASE frame; a flat
    run that starts later and dies before its hold must leave that stamp exactly where it
    was, so the hangover still fires on its own twelfth frame."""
    # Dip frames 0-1 quiet with room tone, 2-4 digitally silent, 5-11 quiet again.
    probs = [P_SPEECH] * 20 + [P_SILENCE] * 12
    rms = [SPEECH_RMS] * 20 + [ROOM_TONE_RMS] * 2 + [GATED_RMS] * 3 + [ROOM_TONE_RMS] * 7
    t = flat_tuning(40, 128, min_commit_interval_ms=0)
    r = simulate(probs, t, rms=rms)
    assert [c.kind for c in r.commits] == ["vad"], "three flat chunks cannot reach a 128 ms hold"
    assert r.commits[0].t_ms == BASE_MS + (20 + 11) * FRAME_MS, "the hangover, untouched"
    assert r.commits[0].trail_ms == T.hangover_trail_ms()


# =======================================================================================
# 7. TRIGGER DISABLED — every existing fixture unchanged.
# =======================================================================================

DISABLED_FIXTURES = {
    "canonical utterance": ([0.9] * 20 + [0.1] * 12, [SPEECH_RMS] * 20 + [0] * 12),
    "dead-band bed": ([0.42] * 200, [0] * 200),
    "gated video": gated_trace(words=8),
    "natural speech": natural_trace(words=8),
    "all digital silence": ([0.1] * 400, [0] * 400),
    "loud continuous speech": ([0.95] * 500, [SPEECH_RMS] * 500),
}


@pytest.mark.parametrize("name", sorted(DISABLED_FIXTURES))
def test_with_the_trigger_disabled_an_rms_trace_changes_nothing(name):
    """THE DE-RISKING ARGUMENT, as an assertion. Every fixture is run twice at the SHIPPED
    tuning — once with no RMS at all, once with an RMS trace of digital silence throughout
    where possible — and the two commit sequences must be identical in every field. With
    `flatline_enabled = False` the amplitude cannot reach any decision."""
    probs, rms = DISABLED_FIXTURES[name]
    without = simulate(probs, T)
    with_rms = simulate(probs, T, rms=rms)
    assert [vars(c) for c in with_rms.commits] == [vars(c) for c in without.commits]
    assert (with_rms.merges_total, with_rms.discards_total) == (
        without.merges_total, without.discards_total
    )
    assert with_rms.tail_ms == without.tail_ms
    assert with_rms.flat_merges_total == 0 and with_rms.flat_discards_total == 0


def test_the_shipped_tuning_has_the_trigger_off():
    assert Tuning().flatline_enabled is False
    assert Tuning().chunk_ms == 32, "both capture paths deliver 32 ms chunks"


def test_an_enabled_trigger_with_no_rms_trace_still_cannot_fire():
    """DECISION 4: `None` is not zero. A p-trace loaded from a CSV without an `rms` column
    must never fire a cut, however the trigger is configured."""
    probs, _rms = gated_trace()
    r = simulate(probs, flat_tuning(0 + 40, 96, min_commit_interval_ms=0), rms=None)
    assert r.commits == []


def test_the_tuning_refuses_an_rms_outside_AudioMaths_range():
    """`AudioMath.amplitude` coerces into 0..32767 (AudioMath.kt:35), so a threshold
    outside it is either dead or unreachable — refuse it at construction."""
    from dataclasses import replace

    with pytest.raises(ValueError, match="32767"):
        replace(T, flatline_rms=40_000)
    with pytest.raises(ValueError, match="32767"):
        replace(T, flatline_rms=-1)


# =======================================================================================
# 8. The analysis layer.
# =======================================================================================

def test_the_rms_histogram_splits_by_silero_state():
    probs = [P_SPEECH] * 4 + [P_GAP_DEADBAND] * 4 + [P_SILENCE] * 4
    rms = [SPEECH_RMS] * 4 + [0] * 4 + [ROOM_TONE_RMS] * 4
    rows = {r.label: r for r in analyze.rms_histogram(probs, rms, T)}
    assert (rows["0"].dead_band, rows["0"].speech, rows["0"].silence) == (4, 0, 0)
    assert rows["80-159"].silence == 4
    assert rows[">=1280"].speech == 4


def test_the_longest_run_under_a_threshold_is_the_machines_own_predicate():
    """`rms < threshold`, with 0 reported as the run of EXACT zeros because a strict
    `< 0` can never fire."""
    assert analyze.longest_run_under([0, 0, 50, 0, 0, 0], 10) == 3
    assert analyze.longest_run_under([0, 0, 5, 0, 0, 0], 10) == 6, "5 < 10: one run"
    assert analyze.longest_run_under([0, 0, 5, 0, 0, 0], 0) == 3, "0 means EXACTLY zero"
    assert analyze.longest_run_under([1, 1, 1], 0) == 0
    assert analyze.longest_run_under([39, 39, 40, 39], 40) == 2, "strict `<`, as the machine is"


def test_the_dip_rms_rows_report_the_quiet_frames_own_amplitudes():
    probs = [P_SPEECH] * 12 + [P_SILENCE] * 4 + [P_GAP_DEADBAND] * 2 + [P_SPEECH] * 12
    rms = [SPEECH_RMS] * 12 + [10, 20, 30, 40] + [900, 900] + [SPEECH_RMS] * 12
    dips = analyze.find_dips(probs, T)
    rows = analyze.dip_rms(dips, probs, rms, T)
    row = next(r for r in rows if r.quiet_frames)
    assert (row.min_rms, row.median_rms) == (10, 20)
    assert row.min_rms_all == 10 and row.median_rms_all == 30


def test_the_cap_chunk_table_reports_what_the_trigger_would_have_had():
    """A cap cut is the app dumping audio it could not cut at a pause. This table says
    whether the flatline trigger would have found a boundary in that same audio."""
    probs = [P_GAP_DEADBAND] * 200
    rms = [900] * 100 + [0] * 6 + [900] * 94
    r = simulate(probs, T, rms=rms)
    rows = analyze.cap_chunk_flat_runs(r, rms)
    assert rows, "a 200-frame dead-band trace rides the wall cap"
    assert max(row.runs[40] for row in rows) == 6
    assert max(row.runs_ms[40] for row in rows) == 6 * FRAME_MS


def test_the_flat_sweep_carries_a_trigger_off_baseline_row_first():
    probs, rms = gated_trace(words=6)
    rows = analyze.flat_sweep(probs, rms, T, floor_ms=0)
    assert rows[0].enabled is False and rows[0].flat == 0
    assert len(rows) == 1 + len(analyze.FLAT_SWEEP_RMS) * len(analyze.FLAT_SWEEP_HOLDS)
    on = [r for r in rows if r.enabled and r.flatline_rms == 40 and r.hold_ms == 128]
    assert on and on[0].flat > rows[0].flat, "the trigger cuts what the baseline could not"

    # NOTHING here cuts through a word: every gap is five frames of digital silence and
    # the SPLITS column — speech on both sides — is the one that would say otherwise.
    assert all(r.mid_word_splits == 0 for r in rows)
    # RISK is not zero at a 128 ms hold, and the reason is the metric's conservatism, not
    # a bad cut: the hold fires on the gap's LAST frame, so the NEXT frame is the next
    # word. That is the boundary the owner is asking for, and the risk column still counts
    # it — which is exactly why the report prints SPLITS beside it.
    at_128 = [r for r in rows if r.enabled and r.hold_ms == 128]
    assert all(r.mid_word_risk == r.flat - 1 for r in at_128), (
        "every flat cut but the last lands one frame before speech resumes"
    )
    at_96 = [r for r in rows if r.enabled and r.hold_ms == 96]
    assert all(r.mid_word_risk == 0 for r in at_96), (
        "a 96 ms hold fires on the gap's fourth frame, with a gap frame either side"
    )


def test_mid_word_risk_counts_a_cut_silero_would_have_called_speech():
    """The ship gate. A flat cut whose neighbours are both speech SPLITS a Silero speech
    run, and `no_context = true` makes that unrepairable."""
    probs = [P_SPEECH] * 12 + [P_SPEECH] * 5 + [P_SPEECH] * 12
    rms = [SPEECH_RMS] * 12 + [0] * 5 + [SPEECH_RMS] * 12
    t = flat_tuning(40, 128, min_commit_interval_ms=0)
    r = simulate(probs, t, rms=rms)
    assert [c.kind for c in r.commits] == ["flat"]
    assert analyze.mid_word_risk_frames(r, probs, t) == [16]


# =======================================================================================
# 9. --phone-capture, against the real file the owner captured.
# =======================================================================================

@pytest.mark.skipif(not CAPTURE.is_file(), reason=f"{CAPTURE} not on this machine")
def test_the_real_phone_capture_parses_to_24_encodes_and_23_intervals():
    """`09-03 12:01:02.366 27133 27925 I WE-DIAG : encode: graphExecute OK in 1791.9 ms`
    — threadtime, one line per committed segment."""
    encodes = analyze.parse_phone_capture_file(str(CAPTURE))
    assert len(encodes) == 24
    ts = [e.t_ms for e in encodes]
    assert ts == sorted(ts), "logcat is in time order"
    assert len(analyze.intervals_ms(ts)) == 23
    assert encodes[0].encode_ms == pytest.approx(1791.9)
    assert all(1_600 < e.encode_ms < 2_000 for e in encodes), (
        "npu-turbo's encode is a fixed 30 s window, so every pass costs about the same"
    )


def test_a_line_that_is_not_an_encode_line_is_ignored():
    text = (
        "--------- beginning of main\n"
        "09-03 12:01:02.378 27133 27925 I WE-DIAG : detect: language token 50259\n"
        "09-03 12:01:02.366 27133 27925 I WE-DIAG : encode: graphExecute OK in 1791.9 ms\n"
        "09-03 12:01:02.429 27133 27925 I WE-DIAG : decode: 3 tokens in 50.1 ms\n"
        "not a logcat line at all\n"
    )
    assert len(analyze.parse_phone_capture(text)) == 1


def test_the_alignment_is_index_paired_from_each_sides_first_commit():
    """Aligned at the first commit, so only the SHAPE of the two sequences is compared —
    the `encode:` timestamp is an encode END and carries an offset this cannot see."""
    encodes = analyze.parse_phone_capture(
        "\n".join(
            f"09-03 12:0{m}:0{s}.000 1 2 I WE-DIAG : encode: graphExecute OK in 1780.0 ms"
            for m, s in ((1, 0), (1, 5), (2, 0))
        )
    )
    probs = [P_SPEECH] * 20 + [P_SILENCE] * 12
    r = simulate(probs, T)
    align = analyze.align_phone_and_sim(encodes, r)
    assert align is not None
    assert (align.n_phone, align.n_sim, align.n_pairs) == (3, 1, 1)
    assert align.delta_ms == [0] and align.within_1s == 1
    assert align.unmatched == 2, "the phone's two extra commits are unmatched"
    assert align.phone_intervals_ms == [5_000, 55_000]


@pytest.mark.skipif(not CAPTURE.is_file(), reason=f"{CAPTURE} not on this machine")
def test_the_cli_prints_the_phone_cross_check(tmp_path):
    """End to end through `--load-trace`, so the CLI test costs no probe run."""
    csv = tmp_path / "t.csv"
    probs, rms = gated_trace(words=6)
    lines = ["frame,t_ms,p,rms"] + [
        f"{i},{BASE_MS + i * FRAME_MS},{p:.6f},{rms[i]}" for i, p in enumerate(probs)
    ]
    csv.write_text("\n".join(lines) + "\n", encoding="utf-8")

    proc = subprocess.run(
        [sys.executable, "-m", "vadsim", "x.wav", "--json", "--no-sweep",
         "--load-trace", str(csv), "--flatline-rms", "40", "--flatline-hold", "128",
         "--floor", "0", "--phone-capture", str(CAPTURE)],
        cwd=str(Path(__file__).resolve().parents[1]),
        capture_output=True, text=True, encoding="utf-8",
    )
    assert proc.returncode == 0, proc.stderr
    doc = json.loads(proc.stdout)
    assert doc["tuning"]["flatline_enabled"] is True
    assert doc["tuning"]["flatline_rms"] == 40 and doc["tuning"]["flatline_hold_ms"] == 128
    assert len(doc["phone_capture"]["encodes"]) == 24
    assert len(doc["phone_capture"]["alignment"]["phone_intervals_ms"]) == 23
    assert doc["summary"]["flat"] == 6, "one cut per gap, with the governor floor at 0"
    assert doc["rms_histogram"] is not None and doc["flat_sweep"] is not None
