"""THE SPEECH EVIDENCE (4.3.2, Layer 1) — `SileroEndpointerEvidenceTest`, test for test by name,
plus the service seam the JVM cannot reach.

The endpointer counts, per uncommitted buffer, the frames the probe scored at or above ONSET;
the commit funnel reads that count once before the engine's commit and re-bases it after; the
engine skips the ENCODE of a KNOWN count under `Tuning.min_evidence_ms` (the Kotlin's
`MIN_SPEECH_EVIDENCE_MS = 192`). Two properties carry this file, as they carry the Kotlin's:
the count is EVIDENCE ONLY (it never moves a cut — shown on the grid fixtures and at every
floor), and it is BUFFER knowledge (a discard or a merge leaves it standing; only the funnel and
a session start re-base it; a retaining cap cut hands the next buffer exactly the tail's onset
frames).

THE GRID at the shipped tuning: HANGOVER_FRAMES = 12, MICRO_PAUSE_FRAMES = 5,
MIN_SPEECH_EVIDENCE_MS = 192 = 6 frames (retuned from 256 = 8 by nit N1, 2026-09-04).
"""

from __future__ import annotations

import json
import subprocess
import sys
from dataclasses import replace
from pathlib import Path

import pytest

from vadsim import analyze, probe as probe_mod
from vadsim.machine import (
    BASE_MS,
    FRAME_MS,
    NO_CUT_POINT,
    NO_VERDICT,
    UNKNOWN_SPEECH_EVIDENCE_MS,
    ServiceSim,
    SileroEndpointerSim,
    Tuning,
    simulate,
)

T = Tuning()
HANGOVER_FRAMES = T.hangover_frames()
MICRO_PAUSE_FRAMES = T.micro_pause_frames()
FLOOR = T.min_evidence_ms
#: The floor as a FRAME COUNT — six at 192 ms. Every fixture built to sit just under or just over
#: the floor derives from this, never from a literal: N1 moved the constant and a fixture spelled
#: `8` would have gone on passing while testing nothing.
FLOOR_FRAMES = FLOOR // FRAME_MS
#: The period of the owner's flickering bed, DERIVED so that the widest cap window (15 s) can never
#: accumulate FLOOR_FRAMES flickers — at 192 that is at most five per window, one short of the
#: floor, which is what makes "the silent bed is SKIPPED" a property of the tuning and not of an
#: 80-frame literal that happened to work at 256.
FLICKER_PERIOD_FRAMES = T.cap_ms // ((FLOOR_FRAMES - 1) * FRAME_MS) + 1
FLICKER_GAP_FRAMES = FLICKER_PERIOD_FRAMES - 1
UNKNOWN = UNKNOWN_SPEECH_EVIDENCE_MS

P_SPEECH = 0.9
P_DEAD_BAND = 0.40
P_SILENCE = 0.1
SPEECH_RMS = 3_000
GATED_RMS = 0

JFK = Path(__file__).resolve().parents[3] / "app/src/main/cpp/whisper.cpp/samples/jfk.wav"


class Pump:
    """`SileroEndpointerEvidenceTest.EvidencePump`: whole frames of one `(p, rms)` at the
    32 ms cadence; the commit frames are kept, not just counted."""

    def __init__(self, ep: SileroEndpointerSim, t: int = BASE_MS) -> None:
        self.ep = ep
        self.t = t
        self.commit_frames: list[int] = []

    def run(self, p: float, frames: int, rms: int = SPEECH_RMS) -> bool:
        fired = False
        for _ in range(frames):
            if self.ep.on_frame(p, self.t, rms):
                fired = True
                self.commit_frames.append(self.t)
            self.t += FRAME_MS
        return fired


def fresh(min_commit_interval_ms: int = 0, tuning: Tuning = T):
    ep = SileroEndpointerSim(tuning)
    ep.on_session_start(now_ms=BASE_MS, min_commit_interval_ms=min_commit_interval_ms)
    return ep, Pump(ep)


def test_the_grid_matches_the_kotlin_floor():
    assert FLOOR == 192 == 6 * FRAME_MS == FLOOR_FRAMES * FRAME_MS
    assert FLOOR <= T.min_speech_ms, "a VAD cut that passed the span floor is not skippable in the normal case"
    # Six ENCODE, five do not: the N1 boundary, and below native's 250 ms run floor on purpose.
    assert 6 * FRAME_MS >= FLOOR > 5 * FRAME_MS
    assert FLOOR < 250, "a TOTAL at 0.50 is the smaller count: it may not match native's run floor"
    # The bed fixtures are built from the floor, not around it.
    assert (FLOOR_FRAMES - 1) * FLICKER_PERIOD_FRAMES * FRAME_MS > T.cap_ms


# =======================================================================================
# 1. What is counted.
# =======================================================================================

def test_evidence_counts_only_frames_at_or_above_ONSET_and_the_dead_band_and_silence_add_nothing():
    ep, pump = fresh()
    pump.run(P_SPEECH, 10)
    assert ep.speech_evidence_ms() == 10 * FRAME_MS
    pump.run(P_DEAD_BAND, 5)
    assert ep.speech_evidence_ms() == 10 * FRAME_MS
    pump.run(P_SILENCE, 5)
    assert ep.speech_evidence_ms() == 10 * FRAME_MS
    pump.run(T.onset, 1)                                   # exactly ONSET counts: inclusive
    assert ep.speech_evidence_ms() == 11 * FRAME_MS
    assert pump.commit_frames == []


def test_a_bare_endpointer_is_UNKNOWN_until_its_probe_scores_a_frame_and_a_scored_silence_is_KNOWN_zero():
    ep, pump = fresh()
    assert ep.speech_evidence_ms() == UNKNOWN
    pump.run(NO_VERDICT, 50)
    assert ep.speech_evidence_ms() == UNKNOWN, "a probe that never answers leaves the whole buffer UNKNOWN"
    pump.run(P_SILENCE, 1)
    assert ep.speech_evidence_ms() == 0
    assert ep.speech_evidence_ms() < FLOOR


# =======================================================================================
# 2. Buffer knowledge: survives the gate closing, dies only with the buffer.
# =======================================================================================

def test_the_count_survives_a_MIN_SPEECH_discard_because_that_audio_is_still_in_the_buffer():
    ep, pump = fresh()
    pump.run(P_SPEECH, 9)                                  # 288 ms span: under the 300 ms floor
    assert not pump.run(P_SILENCE, HANGOVER_FRAMES)        # DISCARDED, not committed
    assert ep.discards == 1 and not ep.has_pending_speech()
    assert ep.speech_evidence_ms() == 9 * FRAME_MS
    assert ep.speech_evidence_ms() >= FLOOR, "288 >= 192: the cap's commit of a lone quiet word is encoded"


def test_the_count_survives_a_governor_merge():
    ep, pump = fresh(min_commit_interval_ms=8_000)
    pump.run(P_SPEECH, 20)
    assert pump.run(P_SILENCE, HANGOVER_FRAMES)            # the free first cut
    ep.on_buffer_committed(tail_retained=False)
    pump.run(P_SPEECH, 20)
    assert not pump.run(P_SILENCE, HANGOVER_FRAMES)        # MERGED inside the 8 s floor
    assert ep.merges == 1 and len(pump.commit_frames) == 1
    assert ep.speech_evidence_ms() == 20 * FRAME_MS


def test_a_VAD_cut_leaves_the_count_readable_until_the_funnel_re_bases_it():
    """THE ORDER the design turns on: `on_frame` runs `_commit_at` -> `_clear_for_next_segment`
    and THEN returns True; the funnel reads the count after that."""
    ep, pump = fresh()
    pump.run(P_SPEECH, 20)
    assert pump.run(P_SILENCE, HANGOVER_FRAMES)
    assert ep.speech_evidence_ms() == 20 * FRAME_MS, "readable after the cut"
    ep.on_buffer_committed(tail_retained=False)
    assert ep.speech_evidence_ms() == UNKNOWN, "re-based: the next buffer has no scored frame yet"
    pump.run(P_SPEECH, 1)
    assert ep.speech_evidence_ms() == FRAME_MS


def test_a_flat_cut_leaves_the_count_readable_too():
    flat = replace(T, flatline_enabled=True, flatline_rms=11, flatline_hold_ms=128)
    ep, pump = fresh(tuning=flat)
    pump.run(P_SPEECH, 12)
    assert pump.run(P_DEAD_BAND, flat.flatline_fire_chunks(), rms=GATED_RMS)
    assert ep.last_cut is not None and ep.last_cut.kind == "flat"
    assert ep.speech_evidence_ms() == 12 * FRAME_MS


def test_without_a_retained_tail_the_re_base_is_UNKNOWN_not_zero():
    ep, pump = fresh()
    pump.run(P_SPEECH, 20)
    assert pump.run(P_SILENCE, HANGOVER_FRAMES)
    ep.on_buffer_committed(tail_retained=False)
    assert ep.speech_evidence_ms() == UNKNOWN


def test_on_session_start_opens_the_count_UNKNOWN():
    ep, pump = fresh()
    pump.run(P_SPEECH, 10)
    ep.on_session_start(now_ms=pump.t, min_commit_interval_ms=0)
    assert ep.speech_evidence_ms() == UNKNOWN


def test_reset_leaves_the_count_standing_because_the_funnel_already_re_based_it():
    ep, pump = fresh()
    pump.run(P_SPEECH, 10)
    ep.reset()
    assert ep.speech_evidence_ms() == 10 * FRAME_MS


# =======================================================================================
# 3. The floor, on the two fixtures the constant was chosen for.
# =======================================================================================

def test_the_shortest_burst_that_commits_ten_onset_frames_is_also_encoded():
    ep, pump = fresh()
    pump.run(P_SPEECH, 10)                                 # speech_ms = 320 > 300: commits
    assert pump.run(P_SILENCE, HANGOVER_FRAMES)
    assert ep.speech_evidence_ms() == 10 * FRAME_MS >= FLOOR


def test_a_fifteen_second_bed_where_silero_flickered_for_five_frames_reads_160_under_the_floor():
    ep, pump = fresh()
    for _ in range(FLOOR_FRAMES - 1):                      # one frame short of the floor
        pump.run(P_SILENCE, FLICKER_GAP_FRAMES)            # ~3 s of room tone
        pump.run(P_SPEECH, 1)                              # one flicker
    pump.run(P_SILENCE, 12)
    assert pump.commit_frames == [], "every flicker was discarded"
    assert ep.discards == FLOOR_FRAMES - 1
    assert ep.speech_evidence_ms() == (FLOOR_FRAMES - 1) * FRAME_MS == 160 < FLOOR
    assert pump.t - BASE_MS > 15_000


# =======================================================================================
# 4. The retained tail.
# =======================================================================================

def test_a_retaining_cap_cut_carries_exactly_the_tails_onset_frames_into_the_next_count():
    ep, pump = fresh()
    pump.run(P_SPEECH, 20)
    pump.run(P_SILENCE, MICRO_PAUSE_FRAMES)                # the fifth dip frame promotes
    offer = ep.pending_cut_point_ms()
    assert offer > NO_CUT_POINT
    pump.run(P_SPEECH, FLOOR_FRAMES)                       # the tail is EXACTLY the floor
    assert ep.pending_cut_point_ms() == offer
    assert ep.speech_evidence_ms() == (20 + FLOOR_FRAMES) * FRAME_MS
    ep.on_buffer_committed(tail_retained=True)
    assert ep.speech_evidence_ms() == FLOOR_FRAMES * FRAME_MS == FLOOR
    ep.reset()                                             # the cap site's reset: the carry survives
    assert ep.speech_evidence_ms() == FLOOR_FRAMES * FRAME_MS
    pump.run(P_SPEECH, 1)
    assert ep.speech_evidence_ms() == (FLOOR_FRAMES + 1) * FRAME_MS


def test_a_flicker_before_the_offered_dip_carries_nothing():
    ep, pump = fresh()
    pump.run(P_SILENCE, 30)
    pump.run(P_SPEECH, 1)
    pump.run(P_SILENCE, MICRO_PAUSE_FRAMES)
    assert ep.pending_cut_point_ms() > NO_CUT_POINT
    assert ep.speech_evidence_ms() == FRAME_MS
    ep.on_buffer_committed(tail_retained=True)
    assert ep.speech_evidence_ms() == 0 < FLOOR


def test_an_offer_that_outlived_a_re_base_carries_the_whole_next_buffer_not_a_stale_difference():
    ep, pump = fresh()
    pump.run(P_SPEECH, 20)
    pump.run(P_SILENCE, MICRO_PAUSE_FRAMES)
    ep.on_buffer_committed(tail_retained=False)            # the consent flush: no reset
    assert ep.pending_cut_point_ms() > NO_CUT_POINT
    pump.run(P_SPEECH, 4)
    ep.on_buffer_committed(tail_retained=True)
    assert ep.speech_evidence_ms() == 4 * FRAME_MS, "4, not max(0, 4 - 20)"


def test_a_retain_on_an_unscored_buffer_stays_UNKNOWN():
    ep, _ = fresh()
    ep.on_buffer_committed(tail_retained=True)
    assert ep.speech_evidence_ms() == UNKNOWN


# =======================================================================================
# 5. EVIDENCE ONLY: no cut decision reads it.
# =======================================================================================

def test_the_count_changes_no_cut_on_the_grid_fixtures_whether_or_not_the_funnel_re_bases_it():
    def trace(rebase: bool) -> list[int]:
        ep, pump = fresh(min_commit_interval_ms=2_000)

        def cut() -> bool:
            fired = pump.run(P_SILENCE, HANGOVER_FRAMES)
            if fired and rebase:
                ep.on_buffer_committed(tail_retained=False)
            return fired

        pump.run(P_SPEECH, 20); cut()                      # free first cut
        pump.run(P_SPEECH, 9); cut()                       # discard
        pump.run(P_SPEECH, 20); cut()                      # merge (inside 2 s)
        pump.run(P_DEAD_BAND, 30)                          # inert
        pump.run(P_SPEECH, 40); cut()                      # a real cut
        pump.run(P_SPEECH, 20); pump.run(P_SILENCE, MICRO_PAUSE_FRAMES)
        if rebase:
            ep.on_buffer_committed(tail_retained=True)     # a retaining cap's re-base
        ep.reset()
        pump.run(P_SPEECH, 60); cut()                      # past the 2 s floor from reset
        return pump.commit_frames

    with_rebase, without = trace(True), trace(False)
    assert with_rebase == without and len(with_rebase) == 3


def test_the_cuts_are_byte_identical_at_every_floor():
    probs = ([P_SILENCE] * 80 + [P_SPEECH]) * 6 + [P_SPEECH] * 40 + [P_SILENCE] * 12
    baseline = [(c.t_ms, c.kind, c.chunk_ms, c.retain_ms) for c in simulate(probs, T).commits]
    for floor in (0, 32, FLOOR, 10_000):
        r = simulate(probs, replace(T, min_evidence_ms=floor))
        assert [(c.t_ms, c.kind, c.chunk_ms, c.retain_ms) for c in r.commits] == baseline
        assert all(c.speech_frames is not None for c in r.commits), "the count is taken whatever the floor"
    assert len(baseline) >= 2


# =======================================================================================
# 6. The seam — FloatingBubbleService.commitSegment, `ServiceSim._emit`.
# =======================================================================================

def test_the_seam_skips_the_cap_commit_of_a_flickering_silent_window_and_the_next_window_opens_at_zero():
    """The owner's report end to end: room tone with a flicker every FLICKER_PERIOD_FRAMES. Every
    commit is a cap cut, every one carries fewer onset frames than the floor asks for — that is
    the derivation of the period, not a coincidence of the fixture — every one is SKIPPED, and
    because each flicker precedes the dip the cap retains, the carry is always zero."""
    probs = ([P_SILENCE] * FLICKER_GAP_FRAMES + [P_SPEECH]) * 16
    r = simulate(probs, T)
    assert r.commits and all(c.kind == "cap" for c in r.commits)
    assert all(c.speech_frames is not None and c.speech_frames < FLOOR_FRAMES for c in r.commits)
    assert r.skipped() == r.commits
    assert any(c.retain_ms > 0 for c in r.commits), "the fixture exercises a retaining cap cut"
    s = analyze.summarise(r, probs)
    assert s["skipped_at_min_evidence"] == s["commits"] and s["encoded"] == 0
    assert s["skip_work_saved_ms"] == s["commits"] * T.service_ms
    assert s["turbo_duty_encoded"] == 0.0
    # And a floor of 0 skips nothing — the report knob, not the machine.
    assert simulate(probs, replace(T, min_evidence_ms=0)).skipped() == []


def test_a_speaker_whose_last_words_fall_in_a_retained_tail_is_not_skipped_at_the_stop_flush():
    """118 frames of speech, a 5-frame dip (offered), then speech through the 4 s first cap, which
    fires on frame 125 and retains the tail from the offer (224 ms). Then FLOOR_FRAMES - 3 more
    speech frames and silence too short to cut. The tail the stop flush commits holds 3 carried
    plus those new ones — exactly the floor — and is ENCODED; without the carry it would read the
    new frames alone, under the floor, and the speaker's last words would be skipped. The split
    derives from the constant: 3 + 5 at the old 256, 3 + 3 at 192, same property either way."""
    carried, new = 3, FLOOR_FRAMES - 3
    probs = [P_SPEECH] * 118 + [P_SILENCE] * 5 + [P_SPEECH] * (carried + new) + [P_SILENCE] * 6
    r = simulate(probs, T)
    assert len(r.commits) == 1
    c = r.commits[0]
    assert c.kind == "cap" and c.retain_ms == 224 and c.cap_ms == T.first_cap_ms
    assert c.speech_frames == 118 + carried, "the committed part is credited with the WHOLE count"
    assert not c.skipped_at(FLOOR)
    assert r.tail_ms > 0
    assert r.tail_evidence_ms == FLOOR_FRAMES * FRAME_MS == FLOOR
    assert not r.tail_skipped()
    # The counter-factual: the same tail judged without the carry would have been skipped.
    assert new * FRAME_MS < FLOOR


def test_the_stop_flush_of_a_silent_tail_is_skipped():
    probs = [P_SPEECH] * 20 + [P_SILENCE] * 12 + [P_SILENCE] * 30
    r = simulate(probs, T)
    assert len(r.commits) == 1 and r.commits[0].kind == "vad"
    assert r.commits[0].speech_frames == 20 and not r.commits[0].skipped_at(FLOOR)
    assert r.tail_ms > 0 and r.tail_evidence_ms == 0 and r.tail_skipped()


def test_the_flatline_fixture_is_never_skipped():
    """The flatline fixtures — gated words, 12 speech frames each — every flat commit carries
    384 ms of evidence and encodes; the trigger's cuts are untouched."""
    from test_flatline import flat_tuning, gated_trace

    probs, rms = gated_trace(words=6)
    r = simulate(probs, flat_tuning(40, 128, min_commit_interval_ms=0), rms=rms)
    assert len(r.of_kind("flat")) == 6
    assert r.skipped() == []
    assert all(c.speech_frames == 12 for c in r.of_kind("flat"))


@pytest.mark.skipif(not JFK.is_file(), reason=f"{JFK} not checked out")
def test_jfk_has_no_skippable_commit_and_every_commit_carries_known_evidence():
    trace = probe_mod.probe_wav(str(JFK))
    r = simulate(trace.probs, T)
    assert r.commits, "jfk cuts"
    assert all(c.speech_frames is not None for c in r.commits)
    assert r.skipped() == [], [(c.t_ms, c.kind, c.speech_frames) for c in r.commits]
    assert all(c.speech_evidence_ms >= FLOOR for c in r.commits)
    s = analyze.summarise(r, trace.probs)
    assert s["skipped_at_min_evidence"] == 0 and s["encoded"] == s["commits"]


def test_the_cli_reports_the_skips_and_honours_the_flag(tmp_path):
    probs = ([P_SILENCE] * FLICKER_GAP_FRAMES + [P_SPEECH]) * 8
    csv = tmp_path / "flicker.csv"
    csv.write_text(
        "frame,t_ms,p\n" + "".join(f"{i},{BASE_MS + i * FRAME_MS},{p:.6f}\n" for i, p in enumerate(probs)),
        encoding="utf-8",
    )

    def run(*extra: str) -> str:
        proc = subprocess.run(
            [sys.executable, "-m", "vadsim", "x.wav", "--no-sweep", "--load-trace", str(csv), *extra],
            cwd=str(Path(__file__).resolve().parents[1]),
            capture_output=True, text=True, encoding="utf-8",
        )
        assert proc.returncode == 0, proc.stderr
        return proc.stdout

    doc = json.loads(run("--json"))
    assert doc["tuning"]["min_evidence_ms"] == FLOOR == 192
    assert doc["summary"]["commits"] > 0
    assert doc["summary"]["skipped_at_min_evidence"] == doc["summary"]["commits"]
    assert all("speech_frames" in c and "speech_evidence_ms" in c for c in doc["commits"])

    loose = json.loads(run("--json", "--min-evidence-ms", "0"))
    assert loose["tuning"]["min_evidence_ms"] == 0
    assert loose["summary"]["skipped_at_min_evidence"] == 0
    assert [c["t_ms"] for c in loose["commits"]] == [c["t_ms"] for c in doc["commits"]], "a report knob: no cut moved"

    md = run()
    assert "MIN_SPEECH_EVIDENCE_MS" in md
    assert "commits SKIPPED at the evidence floor" in md
    assert "evidence (fr)" in md and "**SKIP**" in md
