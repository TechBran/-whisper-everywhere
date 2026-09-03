"""ADVERSARIAL VERIFICATION of the flatline proposal (2026-09-03).

Every test here is a trace the build's own suite did not run, chosen because the owner is
about to pick TWO production constants — `flatline_rms` and `flatline_hold_ms` — from this
tool's sweep, and the semantics fixed here will be ported into `SileroEndpointer.kt`.

Sections:
  1. The PENDING END when Silero and the flat run disagree about where speech ended.
  2. CHUNK ALIGNMENT — what gap a hold really catches, measured with the real RMS path.
  3. The MID-WORD metrics — where RISK is blind, and what BRIDGED sees instead.
  4. Hold-to-chunk arithmetic, including the 128 ms-chunk question.
  5. The default simulation is IDENTICAL to the tool as committed at 50d7466, checked
     against that tree's own `machine.py` rather than asserted.
  6. The phone capture, parsed a second way and sanity-checked against the governor.
"""

from __future__ import annotations

import importlib.util
from dataclasses import replace
from datetime import datetime
from pathlib import Path

import numpy as np
import pytest

from vadsim import analyze, probe
from vadsim.machine import (
    BASE_MS,
    FRAME_MS,
    FRAME_SAMPLES,
    SileroEndpointerSim,
    Tuning,
    simulate,
)

T = Tuning()
SPEECH_RMS = 3_000
ROOM_TONE_RMS = 100
P_SPEECH, P_DEADBAND, P_SILENCE = 0.9, 0.40, 0.1

CAPTURE = Path("C:/Users/bastr/.androidbuild/capture-yt-2000-0903-1207.txt")
BASELINE_MACHINE = Path(
    "C:/Users/bastr/AppData/Local/Temp/claude/"
    "C--Users-bastr-OneDrive-Desktop-whisper-Everywhere/"
    "f0fe6e9c-f5ba-497d-8286-004288122e7c/scratchpad/review/baseline/tools/vadsim/"
    "vadsim/machine.py"
)


def flat(rms: int, hold: int, **kw) -> Tuning:
    return replace(T, flatline_enabled=True, flatline_rms=rms, flatline_hold_ms=hold, **kw)


def frame_of(commit) -> int:
    return (commit.t_ms - BASE_MS) // FRAME_MS


# =======================================================================================
# 1. THE PENDING END — DECISION 6 as it actually behaves, not as it was first worded.
# =======================================================================================

def test_a_later_silero_stamp_is_kept_so_speech_includes_the_flat_frames_before_it():
    """Flat run starts at frame 20 while Silero is still in the DEAD BAND (LSTM inertia);
    Silero drops below RELEASE two frames later and stamps `tempEndMs` at 22. The hold
    (128, five flat frames) comes due at 24 with `tempEndMs` already set, so the flat
    close keeps Silero's LATER stamp: `speechMs` runs to 22 (it includes two digitally
    silent frames) and `trailMs` is 64 — SHORTER than the hold. The original wording
    ("the pending end is the run's first flat frame") was false here; the code is the
    Kotlin-compatible rule (`tempEndMs` is stamped once and never moved)."""
    probs = [P_SPEECH] * 20 + [P_DEADBAND] * 2 + [P_SILENCE] * 10
    rms = [SPEECH_RMS] * 20 + [0] * 12
    r = simulate(probs, flat(40, 128, min_commit_interval_ms=0), rms=rms)
    assert [c.kind for c in r.commits] == ["flat"]
    c = r.commits[0]
    assert frame_of(c) == 24, "fires on the fifth flat frame regardless of Silero"
    assert c.speech_ms == 22 * FRAME_MS, "measured to SILERO's stamp, two frames into the run"
    assert c.trail_ms == 2 * FRAME_MS, "trail is now - tempEndMs = 64, not the 128 ms hold"


def test_an_earlier_silero_stamp_is_kept_so_trail_is_longer_than_the_hold():
    """Room tone for three frames (Silero stamps at 20), THEN digital zero. The flat run
    starts at 23 and fires at 27; the pending end stays at 20, so `trailMs` is 224 — the
    hangover would have taken this same dip four frames later, at 31, with trail 352."""
    probs = [P_SPEECH] * 20 + [P_SILENCE] * 12
    rms = [SPEECH_RMS] * 20 + [ROOM_TONE_RMS] * 3 + [0] * 9
    r = simulate(probs, flat(40, 128, min_commit_interval_ms=0), rms=rms)
    assert [c.kind for c in r.commits] == ["flat"]
    c = r.commits[0]
    assert frame_of(c) == 27
    assert c.speech_ms == 20 * FRAME_MS
    assert c.trail_ms == 7 * FRAME_MS == 224


def test_silero_onset_frames_inside_the_run_do_not_reset_it_and_the_end_is_the_run_start():
    """DECISION 4 exercised the way it can actually happen: Silero calls the first two
    digitally silent frames SPEECH (`p >= ONSET`, which zeroes `tempEndMs` at :540) and
    then parks in the dead band for the rest, so it never stamps a pending end at all.
    The run must survive those two frames (else the trigger cannot fire in exactly the
    case it exists for) and, with `tempEndMs` 0 at fire time, the pending end is the run's
    first frame: speech 640, trail 128."""
    probs = [P_SPEECH] * 22 + [P_DEADBAND] * 10
    rms = [SPEECH_RMS] * 20 + [0] * 12
    r = simulate(probs, flat(40, 128, min_commit_interval_ms=0), rms=rms)
    assert [c.kind for c in r.commits] == ["flat"]
    c = r.commits[0]
    assert frame_of(c) == 24
    assert c.speech_ms == 20 * FRAME_MS
    assert c.trail_ms == 128


def test_the_shipped_machine_already_remembers_a_five_frame_zero_gap_for_the_cap():
    """Context for the whole proposal. On REAL digital silence Silero's `p` is ~0 from the
    first frame (measured on jfk-gated.wav), so a 160 ms gap is five sub-RELEASE frames to
    the shipped machine too: it stamps `tempEndMs`, PROMOTES `prevEndMs` on the fifth frame
    (128 > MICRO_PAUSE_MS, :577) and then loses the dip to the next onset. The wall cap
    therefore already cuts AT such a gap (retain > 0) when one fell in the last 3 s. What
    the flat trigger buys on such audio is LATENCY — a cut at the gap instead of at the
    cap — not a better boundary. A four-frame gap (128 ms) is remembered by neither, and
    an offer older than CAP_CUT_MAX_RETAIN_MS (3 000) is stale and retains nothing."""
    # Speech, a five-frame zero gap 1 920 ms in, then speech until the 4 s first cap:
    # the offer is 2 080 ms old at the cap, inside the 3 000 ms retain window.
    n = T.first_cap_ms // FRAME_MS                 # frame index at which the cap fires
    gap_at = 60
    probs = [P_SPEECH] * gap_at + [P_SILENCE] * 5 + [P_SPEECH] * (n - gap_at - 5 + 1)
    rms = [SPEECH_RMS] * gap_at + [0] * 5 + [SPEECH_RMS] * (n - gap_at - 5 + 1)
    off = simulate(probs, T, rms=rms)
    assert [c.kind for c in off.commits] == ["cap"]
    cap = off.commits[0]
    assert cap.cut_point_ms == BASE_MS + gap_at * FRAME_MS, "the gap's first frame is the offer"
    assert cap.retain_ms == cap.t_ms - cap.cut_point_ms > 0, "the cap cut AT the gap"

    probs4 = [P_SPEECH] * gap_at + [P_SILENCE] * 4 + [P_SPEECH] * (n - gap_at - 4 + 1)
    off4 = simulate(probs4, T)
    assert off4.commits[0].retain_ms == 0, "128 ms is under MICRO_PAUSE_MS's fifth frame"

    # And the same five-frame gap 3 360 ms before the cap is a STALE offer: retain 0.
    probs_stale = [P_SPEECH] * 20 + [P_SILENCE] * 5 + [P_SPEECH] * (n - 25 + 1)
    stale = simulate(probs_stale, T)
    assert stale.commits[0].cut_point_ms == BASE_MS + 20 * FRAME_MS
    assert stale.commits[0].retain_ms == 0, "older than CAP_CUT_MAX_RETAIN_MS"


# =======================================================================================
# 2. CHUNK ALIGNMENT — the gap a hold catches, through the real RMS path.
# =======================================================================================

def _speech(n_samples: int, seed: int) -> np.ndarray:
    """White noise at about SPEECH_RMS in AudioMath units, on the int16 grid."""
    g = np.random.default_rng(seed)
    x = g.standard_normal(n_samples) * (SPEECH_RMS / 32768.0)
    q = np.clip(np.rint(x * 32768.0), -32768, 32767).astype(np.int16)
    return q.astype(np.float32) / 32768.0


def _gated_clip(gap_ms: int, offset_samples: int, seed: int = 7) -> np.ndarray:
    """640 ms of speech, a digital-silence gap, 640 ms of speech — with the gap's START
    shifted `offset_samples` into a chunk. `offset_samples = 0` is chunk-aligned."""
    pre = _speech(20 * FRAME_SAMPLES + offset_samples, seed)
    gap = np.zeros(gap_ms * 16, dtype=np.float32)
    post = _speech(20 * FRAME_SAMPLES, seed + 1)
    return np.concatenate([pre, gap, post])


def _probs_from_rms(rms):
    """Silero sees digital zero immediately (measured), so p follows the amplitude."""
    return [P_SILENCE if v == 0 else P_SPEECH for v in rms]


def test_one_millisecond_of_speech_in_a_chunk_is_never_flat():
    """The reason alignment matters: a chunk that straddles the edge of a gate is not
    flat at ANY swept threshold. 16 samples (1 ms) of 3 000-RMS speech in 512 reads
    ~530 — over the 160 ceiling of the sweep, let alone the 40 default."""
    chunk = np.concatenate([_speech(16, 3), np.zeros(496, dtype=np.float32)])
    v = probe.rms_amplitude(chunk)
    assert v > max(analyze.FLAT_SWEEP_RMS), f"a 1 ms sliver of speech reads {v}"
    assert v >= 350


def test_an_aligned_160ms_gap_yields_five_flat_chunks_and_hold_128_cuts_it():
    samples = _gated_clip(160, offset_samples=0)
    rms = probe.frame_rms(samples)
    assert analyze.longest_run_under(rms, 40) == 5
    r = simulate(_probs_from_rms(rms), flat(40, 128, min_commit_interval_ms=0), rms=rms)
    assert [c.kind for c in r.commits] == ["flat"]


def test_the_same_160ms_gap_offset_by_half_a_chunk_yields_four_and_hold_128_does_not():
    """THE FINDING. Shift the gap by 16 ms and its first and last chunks each carry speech,
    so only FOUR whole chunks are flat. A 128 ms hold needs five and does nothing; a 96 ms
    hold needs four and cuts. The build's fixtures and its `jfk-gated.wav` demo were
    aligned by construction, so none of them could show this."""
    samples = _gated_clip(160, offset_samples=FRAME_SAMPLES // 2)
    rms = probe.frame_rms(samples)
    assert analyze.longest_run_under(rms, 40) == 4
    probs = _probs_from_rms(rms)
    assert simulate(probs, flat(40, 128, min_commit_interval_ms=0), rms=rms).commits == []
    assert [c.kind for c in simulate(
        probs, flat(40, 96, min_commit_interval_ms=0), rms=rms).commits] == ["flat"]


@pytest.mark.parametrize("offset", list(range(0, FRAME_SAMPLES, 64)))
def test_a_192ms_gap_is_cut_by_hold_128_at_every_alignment(offset):
    """`Tuning.flatline_gap_any_ms()` = 192 at (128, 32): six chunks of span always hold
    five whole flat chunks, whatever the phase."""
    t = flat(40, 128, min_commit_interval_ms=0)
    assert t.flatline_gap_any_ms() == 192
    samples = _gated_clip(192, offset_samples=offset)
    rms = probe.frame_rms(samples)
    assert analyze.longest_run_under(rms, 40) >= 5
    assert [c.kind for c in simulate(_probs_from_rms(rms), t, rms=rms).commits] == ["flat"]


@pytest.mark.parametrize("offset", [16, 100, 256, 400, 500])
def test_a_160ms_gap_is_missed_by_hold_128_at_every_unaligned_phase(offset):
    """The complement: below `flatline_gap_any_ms()` the cut depends on luck of phase."""
    samples = _gated_clip(160, offset_samples=offset)
    rms = probe.frame_rms(samples)
    assert analyze.longest_run_under(rms, 40) == 4
    assert simulate(_probs_from_rms(rms), flat(40, 128, min_commit_interval_ms=0),
                    rms=rms).commits == []


# =======================================================================================
# 3. THE MID-WORD METRICS.
# =======================================================================================

def test_the_risk_metric_is_blind_to_a_dead_band_soft_segment_inside_a_word():
    """THE UNDERCOUNT. A word with a 192 ms soft stretch in its middle — RMS 30 (under a
    40 floor) with Silero parked in the DEAD BAND, the shape a long voiceless fricative
    or a breathy closure takes on a quiet recording. The trigger fires on the fifth soft
    frame, one frame before the stretch ends. RISK looks at `p` on frames 15/16/17 — all
    0.40 — and sees nothing; SPLITS sees nothing. BRIDGED sees speech resume at 18, well
    inside the hangover, and `bridged_nonzero` sees that the run was not digital silence.
    This is the cut the owner is afraid of, and only the new column counts it."""
    probs = [P_SPEECH] * 12 + [P_DEADBAND] * 6 + [P_SPEECH] * 12
    rms = [SPEECH_RMS] * 12 + [30] * 6 + [SPEECH_RMS] * 12
    t = flat(40, 128, min_commit_interval_ms=0)
    r = simulate(probs, t, rms=rms)
    assert [frame_of(c) for c in r.of_kind("flat")] == [16]
    assert analyze.mid_word_risk_frames(r, probs, t) == []
    assert analyze.mid_word_split_frames(r, probs, t) == []
    assert analyze.mid_word_bridge_frames(r, probs, t) == [16]
    assert analyze.mid_word_bridge_nonzero_frames(r, probs, rms, t) == [16]
    assert analyze.flat_run_max_rms(r, rms, t) == {16: 30}


def _realistic_gated(words: int = 4, word_frames: int = 12, gap_frames: int = 5):
    """Gated audio as Silero actually sees it: `p` ~0 on digital zero (measured), so the
    gaps are sub-RELEASE, not dead-band."""
    probs, rms = [], []
    for _ in range(words):
        probs += [P_SPEECH] * word_frames + [P_SILENCE] * gap_frames
        rms += [SPEECH_RMS] * word_frames + [0] * gap_frames
    return probs, rms


def test_on_gated_audio_every_bridged_cut_is_across_digital_zero():
    """The expected reading on edited audio: BRIDGED equals the cuts that have a next
    word (the trigger's whole purpose), and `bridged_nonzero` is 0."""
    probs, rms = _realistic_gated()
    t = flat(40, 128, min_commit_interval_ms=0)
    r = simulate(probs, t, rms=rms)
    assert len(r.of_kind("flat")) == 4
    assert len(analyze.mid_word_bridge_frames(r, probs, t)) == 3, "the last gap has no next word"
    assert analyze.mid_word_bridge_nonzero_frames(r, probs, rms, t) == []


def test_risk_flips_with_hold_parity_while_bridged_does_not():
    """On the SAME four gaps a 96 ms hold fires a frame earlier than a 128 ms hold. RISK
    reads 0 at 96 and 3 at 128 — the difference is which frame sits at `i + 1`, not any
    difference in the cut. BRIDGED reads 3 at both. A metric that flips with hold parity
    cannot rank holds."""
    probs, rms = _realistic_gated()
    rows = {h: analyze.flat_sweep(probs, rms, T, holds=(h,), rms_values=(40,), floor_ms=0)[1]
            for h in (96, 128)}
    assert (rows[96].mid_word_risk, rows[128].mid_word_risk) == (0, 3)
    assert (rows[96].bridged, rows[128].bridged) == (3, 3)
    assert (rows[96].bridged_nonzero, rows[128].bridged_nonzero) == (0, 0)


def test_the_sweep_carries_the_bridged_columns_and_the_baseline_row_reads_zero():
    probs, rms = _realistic_gated(words=6)
    rows = analyze.flat_sweep(probs, rms, T, floor_ms=0)
    assert rows[0].enabled is False and rows[0].bridged == 0 and rows[0].bridged_nonzero == 0
    on = [r for r in rows if r.enabled and r.flatline_rms == 40 and r.hold_ms == 128][0]
    assert on.flat == 6 and on.bridged == 5 and on.bridged_nonzero == 0
    assert "bridged" in on.to_dict() and "bridged_nonzero" in on.to_dict()


def test_the_flat_run_histogram_counts_what_each_hold_can_fire_on():
    """The hold's instrument: runs by length over the whole clip. Runs of 1, 2, 4, 5 and
    13 frames under 40 -> a 128 ms hold (>=5) sees two of them, a 96 ms hold (>=4) three,
    and the 13-frame run is one Silero's hangover cuts by itself."""
    rms = ([0] * 1 + [900] * 3 + [0] * 2 + [900] * 3 + [20] * 4 + [900] * 3
           + [0] * 5 + [900] * 3 + [0] * 13 + [900] * 3)
    assert analyze.flat_run_lengths(rms, 40) == [1, 2, 4, 5, 13]
    assert analyze.flat_run_lengths(rms, 0) == [1, 2, 5, 13], "20 is not exact zero"
    rows = {r.threshold: r for r in analyze.flat_run_histogram(rms)}
    assert rows[40].n_runs == 5
    assert (rows[40].at_least[4], rows[40].at_least[5], rows[40].at_least[12]) == (3, 2, 1)
    assert rows[40].counts == {"1": 1, "2": 1, "3": 0, "4": 1, "5": 1, "6": 0, "7": 0,
                               "8-11": 0, ">=12": 1}
    assert rows[0].at_least[4] == 2


def test_flat_run_max_rms_reports_the_peak_inside_the_firing_run():
    probs = [P_SPEECH] * 12 + [P_SILENCE] * 5 + [P_SPEECH] * 12
    rms = [SPEECH_RMS] * 12 + [0, 0, 20, 0, 0] + [SPEECH_RMS] * 12
    t = flat(40, 128, min_commit_interval_ms=0)
    r = simulate(probs, t, rms=rms)
    assert analyze.flat_run_max_rms(r, rms, t) == {16: 20}
    assert analyze.mid_word_bridge_nonzero_frames(r, probs, rms, t) == [16]
    assert analyze.mid_word_bridge_nonzero_frames(r, probs, rms, t, digital_floor=20) == []


# =======================================================================================
# 4. HOLD-TO-CHUNK ARITHMETIC.
# =======================================================================================

@pytest.mark.parametrize(
    "hold,chunk,fire,aligned,any_",
    [
        (96, 32, 4, 128, 160),
        (128, 32, 5, 160, 192),
        (160, 32, 6, 192, 224),
        (224, 32, 8, 256, 288),
        (320, 32, 11, 352, 384),
        (100, 32, 5, 160, 192),
        (129, 32, 6, 192, 224),
        # A hypothetical 128 ms chunk: 96 and 128 are the SAME rule — two flat chunks.
        (96, 128, 2, 256, 384),
        (128, 128, 2, 256, 384),
        (129, 128, 3, 384, 512),
    ],
)
def test_fire_chunks_and_the_two_gap_numbers(hold, chunk, fire, aligned, any_):
    t = replace(T, flatline_enabled=True, flatline_hold_ms=hold, chunk_ms=chunk)
    assert t.flatline_fire_chunks() == fire
    assert t.flatline_gap_aligned_ms() == aligned
    assert t.flatline_gap_any_ms() == any_
    if chunk == FRAME_MS:
        assert t.flatline_fire_chunks() == t.flatline_frames()


def test_hold_128_at_a_128ms_chunk_means_two_flat_chunks_not_one():
    """The question asked directly. `flatline_hold_chunks()` says 1 — that is the number
    of chunk INTERVALS the age must span. The app stamps one `nowMs` per chunk, so the
    age is 0 on every frame of the first flat chunk and 128 on every frame of the second:
    the trigger fires on the SECOND flat chunk, 256 ms of flat audio in."""
    t = replace(T, flatline_enabled=True, flatline_hold_ms=128, chunk_ms=128)
    assert t.flatline_hold_chunks() == 1
    assert t.flatline_fire_chunks() == 2
    probs = [P_SPEECH] * 20 + [P_SILENCE] * 8
    rms = [SPEECH_RMS] * 20 + [0] * 8
    r = simulate(probs, replace(t, min_commit_interval_ms=0), rms=rms)
    assert [frame_of(c) for c in r.of_kind("flat")] == [24], (
        "frame 24 is the first frame of the SECOND 128 ms chunk of the run"
    )


def test_KNOWN_LIMITATION_hold_96_at_a_128ms_chunk_fires_inside_the_first_chunk_here():
    """Pinned so the divergence is visible, not hidden. The simulator's clock advances 32
    ms per FRAME, so at a 128 ms chunk a 96 ms hold is reached on the run's 4th frame —
    inside the FIRST flat chunk. On the phone every frame of that chunk carries the same
    `nowMs` (FloatingBubbleService.kt:2009), the age stays 0 until the second chunk, and
    the trigger fires there — one chunk LATER than this simulator says.
    `flatline_fire_chunks()` gives the phone's answer (2). Irrelevant at the shipped 32 ms
    chunk, where frame and chunk coincide; a future per-chunk clock model should move the
    expected frame below from 23 to 24 and delete the LIMITATION from this test's name."""
    t = replace(T, flatline_enabled=True, flatline_hold_ms=96, chunk_ms=128,
                min_commit_interval_ms=0)
    assert t.flatline_fire_chunks() == 2
    probs = [P_SPEECH] * 20 + [P_SILENCE] * 8
    rms = [SPEECH_RMS] * 20 + [0] * 8
    r = simulate(probs, t, rms=rms)
    assert [frame_of(c) for c in r.of_kind("flat")] == [23]


# =======================================================================================
# 5. THE DEFAULT SIMULATION IS THE 50d7466 MACHINE — checked against that tree's code.
# =======================================================================================

def _load_baseline_machine():
    import sys

    spec = importlib.util.spec_from_file_location("vadsim_baseline_machine", BASELINE_MACHINE)
    mod = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = mod   # dataclasses resolves annotations through sys.modules
    spec.loader.exec_module(mod)   # type: ignore[union-attr]
    return mod


def _strip(commit_dict: dict) -> dict:
    d = dict(commit_dict)
    d.pop("rms", None)              # the one field added to Commit
    return d


def _fixtures():
    g = np.random.default_rng(2026_09_03)
    out = {
        "canonical": [0.9] * 20 + [0.1] * 12,
        "dead-band bed": [0.42] * 200,
        "gated realistic": _realistic_gated(words=8)[0],
        "gated dead-band": sum(([0.9] * 12 + [0.4] * 5 for _ in range(8)), []),
        "natural": sum(([0.9] * 12 + [0.1] * 11 for _ in range(8)), []),
        "digital silence": [0.1] * 400,
        "loud": [0.95] * 600,
        "bursts": sum(([0.9] * 3 + [0.1] * 14 for _ in range(30)), []),
    }
    for k in range(12):
        # Markov-ish speech/pause alternation with dead-band mumble and no-verdict frames.
        p, state = [], 0.0
        for _ in range(700):
            state = state if g.random() < 0.9 else g.choice([0.05, 0.42, 0.9])
            v = float(np.clip(state + g.normal(0, 0.08), 0.0, 1.0))
            if g.random() < 0.01:
                v = -1.0
            p.append(v)
        out[f"random-{k}"] = p
    return out


@pytest.mark.skipif(not BASELINE_MACHINE.is_file(), reason="50d7466 tree not extracted here")
@pytest.mark.parametrize("cloud", [False, True])
def test_the_default_machine_matches_the_committed_50d7466_machine_frame_for_frame(cloud):
    """Not a diff of two JSON reports on two wavs (the build did that) but the two state
    machines side by side on twenty traces that exercise merges, discards, caps with and
    without a retain offer, no-verdict frames and the cloud first-cap rule — with an RMS
    trace of all zeros handed to the new machine and the trigger left OFF."""
    base = _load_baseline_machine()
    for name, probs in _fixtures().items():
        old = base.simulate(probs, base.Tuning(), is_cloud_session=cloud)
        new = simulate(probs, T, is_cloud_session=cloud, rms=[0] * len(probs))
        assert [vars(c) for c in old.commits] == [_strip(vars(c)) for c in new.commits], name
        assert (old.merges_total, old.discards_total, old.tail_ms) == (
            new.merges_total, new.discards_total, new.tail_ms), name
        old_g, old_e = base.event_track(probs, base.Tuning(), is_cloud_session=cloud)
        new_g, new_e = __import__("vadsim.machine", fromlist=["event_track"]).event_track(
            probs, T, is_cloud_session=cloud, rms=[0] * len(probs))
        assert (old_g, old_e) == (new_g, new_e), name


# =======================================================================================
# 6. THE PHONE CAPTURE — parsed a second way, and checked against the machine's own rules.
# =======================================================================================

def _independent_parse(path: Path):
    ts, durs = [], []
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        parts = line.split()
        if len(parts) > 7 and parts[5] == "WE-DIAG" and parts[7] == "encode:":
            ts.append(datetime.strptime(f"2000-{parts[0]} {parts[1]}", "%Y-%m-%d %H:%M:%S.%f"))
            durs.append(float(parts[11]))
    return ts, durs


@pytest.mark.skipif(not CAPTURE.is_file(), reason=f"{CAPTURE} not on this machine")
def test_the_regex_parser_agrees_with_a_whitespace_parser_on_the_real_capture():
    ts, durs = _independent_parse(CAPTURE)
    enc = analyze.parse_phone_capture_file(str(CAPTURE))
    assert len(ts) == len(enc) == 24
    mine = [int((t - datetime(2000, 1, 1)).total_seconds() * 1000) for t in ts]
    assert mine == [e.t_ms for e in enc]
    assert durs == pytest.approx([e.encode_ms for e in enc])


@pytest.mark.skipif(not CAPTURE.is_file(), reason=f"{CAPTURE} not on this machine")
def test_the_phone_intervals_obey_the_governor_and_show_the_missing_silent_commits():
    """Two things the capture must satisfy if it is what it claims to be, and one it
    reveals. (a) No two ENCODED commits closer than the 2 000 ms turbo floor (less the
    encode-duration jitter between the two lines) — the governor forbids it, and a cap cut
    is anchored on the previous commit so it cannot be closer either. (b) Cap cuts show as
    intervals of ~15 000 ms. (c) Intervals FAR longer than the 15 s cap exist: commits
    happened there (the cap fires regardless) but produced no `encode:` line, because a
    VAD-empty commit returns before the encoder (`whisper_jni.cpp:815-820`). The phone's
    sequence is a SUBSET of the simulator's; index pairing cannot be exact past such a gap."""
    enc = analyze.parse_phone_capture_file(str(CAPTURE))
    iv = analyze.intervals_ms([e.t_ms for e in enc])
    assert min(iv) >= T.min_commit_interval_ms - 200, iv
    assert sum(1 for x in iv if abs(x - T.cap_ms) <= 100) >= 5, "cap cuts at ~15 s"
    assert sum(1 for x in iv if x > T.cap_ms + 1_000) >= 2, "silent stretches with no encode"


@pytest.mark.skipif(not CAPTURE.is_file(), reason=f"{CAPTURE} not on this machine")
def test_alignment_against_a_trace_with_more_commits_than_the_phone_reports_the_surplus():
    """Plumbing only — there is no wav of this session — but the surplus arithmetic must
    hold in the direction the subset property predicts: simulator > phone."""
    enc = analyze.parse_phone_capture_file(str(CAPTURE))
    probs, rms = _realistic_gated(words=30)
    r = simulate(probs, flat(40, 128, min_commit_interval_ms=0), rms=rms)
    align = analyze.align_phone_and_sim(enc, r)
    assert align is not None
    assert align.n_sim == 30 and align.n_phone == 24 and align.n_pairs == 24
    assert align.unmatched >= 6
