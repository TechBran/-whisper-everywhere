"""THE BACKPRESSURE GOVERNOR (build 85): the simulator twin of
`app/src/test/java/com/whispereverywhere/audio/SileroEndpointerBackpressureTest.kt`.

Every test in the first two sections mirrors a JVM test BY NAME and drives the same
`(p, depth-schedule)` trace; the expected numbers are derived from the grid exactly as the
Kotlin derives them (`kFor`, `flatKFor`), never copied off a passing run. The third section is
the simulator's own: the decoder-queue model that feeds the depth in a whole-seam run, and the
acceptance property the phone sheet (row E9) asks for — intervals cluster at ~2.0 s while the
NPU keeps up and at ~3.2 s while it catches up; never a growing lag. The fourth section is the
trace-level twin: the fixture file both suites replay.

THE GRID (HANGOVER_MS = 350, MIN_SPEECH_MS = 300): endpoint k lands `k * 736` after the last
commit; `ceil(2000 / 736)` = 3 (2 208 ms) clears the fast floor and `ceil(3200 / 736)` = 5
(3 680 ms) the slow one, so the endpoint at 2 208 commits under one floor and MERGES under the
other — the floor in force is observable from the commit count alone.
"""

from __future__ import annotations

import pytest

import backpressure_fixture as fixture
from vadsim.machine import (
    BACKPRESSURE_ENTER_DEPTH,
    BACKPRESSURE_LEAVE_DEPTH,
    BASE_MS,
    FRAME_MS,
    DecoderQueueSim,
    SileroEndpointerSim,
    Tuning,
    floor_for,
    simulate,
    slow_floor_active,
    time_in_slow,
)

T = Tuning()
HANGOVER_FRAMES = T.hangover_frames()
HANGOVER_TRAIL_MS = T.hangover_trail_ms()
SPEECH_FRAMES = T.speech_frames_over_min()
IV = T.fixture_interval_ms()

FAST_MS = 2_000          # CommitCadencePolicy.MIN_COMMIT_INTERVAL_TURBO_MS
SLOW_MS = 3_200          # CommitCadencePolicy.MIN_COMMIT_INTERVAL_TURBO_SLOW_MS


def k_for(floor_ms: int) -> int:
    """The endpoint index that first CLEARS a floor: `ceil(floor / IV)`."""
    return -(-floor_ms // IV)


FAST_K = k_for(FAST_MS)
SLOW_K = k_for(SLOW_MS)

# The FLAT fixture: 12 speech frames (384 ms, over MIN_SPEECH) then FLATLINE_CHUNKS frames of
# digital silence in the DEAD BAND (p = 0.40 — Silero can never cut them). Period 17 frames.
WORD_FRAMES = 12
FLAT_CHUNKS = T.flatline_fire_chunks()
FLAT_IV = (WORD_FRAMES + FLAT_CHUNKS) * FRAME_MS
FLAT_FAST_K = -(-FAST_MS // FLAT_IV)
FLAT_SLOW_K = -(-SLOW_MS // FLAT_IV)

P_SPEECH, P_GAP_DEADBAND, P_SILENCE = 0.9, 0.40, 0.1
SPEECH_RMS, GATED_RMS = 3_000, 0

SLOW_LINE = "backpressure: depth=2 -> slow floor 3200"
FAST_LINE = "backpressure: depth=1 -> fast floor 2000"


class Pump:
    """`SileroEndpointerBackpressureTest.BpPump`: whole frames at the 32 ms cadence, one clock,
    an amplitude beside the probability for the flat fixture."""

    def __init__(self, ep: SileroEndpointerSim, t: int = BASE_MS) -> None:
        self.ep = ep
        self.t = t
        self.commits = 0
        self.last_commit_ms = -1

    def run(self, p: float, frames: int, rms=None) -> bool:
        fired = False
        for _ in range(frames):
            if self.ep.on_frame(p, self.t, rms):
                fired = True
                self.commits += 1
                self.last_commit_ms = self.t
            self.t += FRAME_MS
        return fired

    def first_cut(self) -> int:
        self.run(P_SPEECH, 20)
        assert self.run(P_SILENCE, HANGOVER_FRAMES), "the session's first cut is free on every floor"
        return self.last_commit_ms

    def endpoint(self) -> bool:
        self.run(P_SPEECH, SPEECH_FRAMES)
        return self.run(P_SILENCE, HANGOVER_FRAMES)

    def gated_word(self) -> bool:
        self.run(P_SPEECH, WORD_FRAMES, SPEECH_RMS)
        return self.run(P_GAP_DEADBAND, FLAT_CHUNKS, GATED_RMS)


def fresh(fast_ms: int = FAST_MS, slow_ms=SLOW_MS, flat: bool = False):
    """`fresh()` in the Kotlin: a session opened at BASE with the two floors; `slow_ms=None` is
    the two-argument call every existing caller makes."""
    ep = SileroEndpointerSim(Tuning(flatline_enabled=True) if flat else T)
    if slow_ms is None:
        ep.on_session_start(now_ms=BASE_MS, min_commit_interval_ms=fast_ms)
    else:
        ep.on_session_start(now_ms=BASE_MS, min_commit_interval_ms=fast_ms, slow_commit_interval_ms=slow_ms)
    return ep, Pump(ep)


def lines(ep: SileroEndpointerSim):
    return [tr.line for tr in ep.transitions]


# =======================================================================================
# 0. The rule and the grid.
# =======================================================================================

def test_the_grid_this_file_relies_on():
    assert (FAST_K, SLOW_K) == (3, 5)
    assert (FLAT_FAST_K, FLAT_SLOW_K) == (4, 6)
    assert FAST_K < SLOW_K and FLAT_FAST_K < FLAT_SLOW_K
    assert WORD_FRAMES * FRAME_MS > T.min_speech_ms, "a gated word must be over MIN_SPEECH_MS"
    assert FLAT_CHUNKS == 5, "EndpointerTuning.FLATLINE_CHUNKS at the shipped hold"


def test_the_rule_constants_and_the_exhaustive_mode_step():
    """`BackpressureRuleTest` + `CommitCadencePolicyTest.theModeStepIsPinnedExhaustively`."""
    assert (BACKPRESSURE_ENTER_DEPTH, BACKPRESSURE_LEAVE_DEPTH) == (2, 1)
    table = {
        (0, False): False, (0, True): False,
        (1, False): False, (1, True): False,
        (2, False): True, (2, True): True,
        (3, False): True, (3, True): True,
        (4, False): True, (4, True): True,
    }
    for (depth, before), after in table.items():
        assert slow_floor_active(depth, before) is after, (depth, before)
        floor, mode = floor_for(depth, before, FAST_MS, SLOW_MS)
        assert (floor, mode) == (SLOW_MS if after else FAST_MS, after)
    assert slow_floor_active(-1, True) is False
    # inert when the floors are equal: the mode still steps, the floor does not move
    assert floor_for(2, False, 6_000, 6_000) == (6_000, True)


def test_a_slow_floor_under_the_fast_floor_is_refused():
    """`CommitCadencePolicyTest.theSlowFloorIsNeverBelowTheFastFloor`, at the knob."""
    with pytest.raises(ValueError):
        Tuning(min_commit_interval_ms=3_200, slow_commit_interval_ms=2_000)
    assert Tuning(min_commit_interval_ms=2_000, slow_commit_interval_ms=2_000).backpressure_armed() is False
    assert Tuning(slow_commit_interval_ms=3_200).backpressure_armed() is True
    assert Tuning().slow_floor_ms() == Tuning().min_commit_interval_ms, "None == the fast floor"
    with pytest.raises(ValueError):
        Tuning(service_ms=-1)


# =======================================================================================
# 1. The two floors, observed from the commit count (SileroEndpointerBackpressureTest by name).
# =======================================================================================

def test_at_depth_0_and_1_a_real_endpoint_2000ms_after_the_last_commit_commits():
    ep, pump = fresh()
    commit1 = pump.first_cut()
    assert commit1 == BASE_MS + 640 + HANGOVER_TRAIL_MS
    for _ in range(FAST_K - 1):
        assert not pump.endpoint(), "inside 2000"
    assert pump.endpoint(), f"{FAST_K * IV} ms clears 2000"
    assert pump.last_commit_ms == commit1 + FAST_K * IV
    ep.on_queue_depth(1)
    commit2 = pump.last_commit_ms
    for _ in range(FAST_K - 1):
        assert not pump.endpoint()
    assert pump.endpoint()
    assert pump.last_commit_ms == commit2 + FAST_K * IV
    assert pump.commits == 3
    assert lines(ep) == []


def test_at_depth_2_the_endpointer_merges_until_3200ms():
    ep, pump = fresh()
    commit1 = pump.first_cut()
    ep.on_queue_depth(2)
    for k in range(1, SLOW_K):
        assert not pump.endpoint(), f"endpoint {k} at +{k * IV} ms is inside 3200"
    assert pump.commits == 1
    assert ep.has_pending_speech(), "the merged audio is still uncommitted"
    assert pump.endpoint(), f"{SLOW_K * IV} ms clears 3200"
    assert pump.commits == 2
    assert pump.last_commit_ms == commit1 + SLOW_K * IV
    assert lines(ep) == [SLOW_LINE]


def test_after_depth_returns_to_1_the_next_endpoint_at_2000ms_or_more_commits():
    ep, pump = fresh()
    pump.first_cut()
    ep.on_queue_depth(2)
    for _ in range(SLOW_K - 1):
        assert not pump.endpoint()
    assert pump.endpoint()
    commit2 = pump.last_commit_ms
    ep.on_queue_depth(1)
    for _ in range(FAST_K - 1):
        assert not pump.endpoint()
    assert pump.endpoint(), f"back on the fast floor: {FAST_K * IV} ms commits"
    assert pump.last_commit_ms == commit2 + FAST_K * IV
    assert pump.commits == 3
    assert lines(ep) == [SLOW_LINE, FAST_LINE]


def test_a_depth_change_mid_interval_takes_effect_at_the_next_endpoint_never_retroactively():
    # (a) SLOW -> FAST mid-interval: the 2 208 endpoint was merged under the slow floor; the
    # depth then drops; nothing commits until a NEW endpoint, which the FAST floor takes.
    ep, pump = fresh()
    commit1 = pump.first_cut()
    ep.on_queue_depth(2)
    for _ in range(FAST_K):
        assert not pump.endpoint()
    ep.on_queue_depth(1)
    pad = 4                                   # 2 944 + 128 = 3 072: still inside the slow floor
    assert not pump.run(P_SILENCE, pad), "a depth drop is not a commit"
    assert pump.commits == 1
    assert pump.endpoint(), "the NEXT endpoint is judged under the fast floor"
    assert pump.last_commit_ms == commit1 + (FAST_K + 1) * IV + pad * FRAME_MS
    assert pump.last_commit_ms - commit1 < SLOW_MS
    assert lines(ep) == [SLOW_LINE, FAST_LINE]
    # (b) FAST -> SLOW mid-interval: the 2 208 endpoint the fast floor would take MERGES.
    ep, pump = fresh()
    commit1 = pump.first_cut()
    for _ in range(FAST_K - 1):
        assert not pump.endpoint()
    ep.on_queue_depth(2)
    assert not pump.endpoint(), f"{FAST_K * IV} ms would clear 2000 - but the floor is 3200 now"
    for _ in range(SLOW_K - FAST_K - 1):
        assert not pump.endpoint()
    assert pump.endpoint()
    assert pump.last_commit_ms == commit1 + SLOW_K * IV
    assert lines(ep) == [SLOW_LINE]


def test_the_flat_path_obeys_the_same_floor_as_the_vad_path():
    ep, pump = fresh(flat=True)
    assert pump.gated_word()
    commit1 = pump.last_commit_ms
    assert commit1 == BASE_MS + (WORD_FRAMES + FLAT_CHUNKS - 1) * FRAME_MS
    assert ep.last_cut.kind == "flat"
    for _ in range(FLAT_FAST_K - 1):
        assert not pump.gated_word()
    assert pump.gated_word()
    assert pump.last_commit_ms == commit1 + FLAT_FAST_K * FLAT_IV
    assert ep.last_cut.kind == "flat"
    commit2 = pump.last_commit_ms
    ep.on_queue_depth(2)
    for k in range(1, FLAT_SLOW_K):
        assert not pump.gated_word(), f"flat close {k} at +{k * FLAT_IV} ms is inside 3200"
    assert pump.gated_word()
    assert pump.last_commit_ms == commit2 + FLAT_SLOW_K * FLAT_IV
    assert ep.last_cut.kind == "flat"
    assert pump.commits == 3
    assert ep.flat_merges == (FLAT_FAST_K - 1) + (FLAT_SLOW_K - 1)
    assert lines(ep) == [SLOW_LINE]


# =======================================================================================
# 2. Lifecycle, inert default, free first cut, the line.
# =======================================================================================

def test_onSessionStart_clears_the_mode_and_the_depth():
    ep, pump = fresh()
    pump.first_cut()
    ep.on_queue_depth(2)
    assert not pump.endpoint()
    assert lines(ep) == [SLOW_LINE]
    pump.t += 10_000
    ep.on_session_start(now_ms=pump.t, min_commit_interval_ms=FAST_MS, slow_commit_interval_ms=SLOW_MS)
    commit1 = pump.first_cut()
    for _ in range(FAST_K - 1):
        assert not pump.endpoint()
    assert pump.endpoint(), "a surviving depth of 2 would merge this endpoint"
    assert pump.last_commit_ms == commit1 + FAST_K * IV
    assert lines(ep) == [SLOW_LINE], "cleared, not stepped: no 'fast floor' line for a 2 -> 0"


def test_reset_keeps_the_mode_and_the_depth():
    ep, pump = fresh()
    pump.first_cut()
    ep.on_queue_depth(2)
    assert not pump.endpoint()
    anchor = pump.t - FRAME_MS
    ep.reset()
    assert not ep.has_pending_speech()
    for _ in range(SLOW_K - 1):
        assert not pump.endpoint()
    assert pump.endpoint()
    assert pump.last_commit_ms == anchor + SLOW_K * IV
    assert lines(ep) == [SLOW_LINE], "kept, not re-entered"


def test_the_governor_is_inert_when_slow_equals_fast_which_is_the_default_parameter():
    for slow in (None, FAST_MS):
        ep, pump = fresh(slow_ms=slow)
        commit1 = pump.first_cut()
        ep.on_queue_depth(2)
        for _ in range(FAST_K - 1):
            assert not pump.endpoint()
        assert pump.endpoint(), f"slow == fast: depth 2 changes nothing (slow={slow})"
        assert pump.last_commit_ms == commit1 + FAST_K * IV
        assert lines(ep) == [], "unarmed: the mode steps, nothing is announced"


def test_the_sessions_first_endpoint_is_free_at_any_depth():
    ep, pump = fresh()
    ep.on_queue_depth(2)
    pump.first_cut()
    assert pump.commits == 1
    assert lines(ep) == []


def test_the_transition_line_is_emitted_once_per_transition_and_names_the_new_floor():
    ep, pump = fresh()
    pump.first_cut()
    ep.on_queue_depth(2); pump.endpoint()
    assert lines(ep) == [SLOW_LINE]
    ep.on_queue_depth(3); pump.endpoint()
    assert lines(ep) == [SLOW_LINE], "deeper is not a transition"
    ep.on_queue_depth(0); pump.endpoint()
    assert lines(ep) == [SLOW_LINE, "backpressure: depth=0 -> fast floor 2000"]
    ep.on_queue_depth(2); pump.endpoint()
    assert len(lines(ep)) == 3 and lines(ep)[-1] == SLOW_LINE
    ep.on_queue_depth(1)
    assert len(lines(ep)) == 3, "published between endpoints: announced at the next one"
    pump.endpoint()
    assert len(lines(ep)) == 4 and lines(ep)[-1] == FAST_LINE
    # and the step carries the ENDPOINT's clock: the frame that consulted the floor
    assert ep.transitions[-1].t_ms == pump.t - FRAME_MS


# =======================================================================================
# 3. The decoder queue — the simulator's own model, and the acceptance property.
# =======================================================================================

def test_the_decoder_queue_is_a_single_server_with_a_fifo():
    q = DecoderQueueSim(service_ms=2_050)
    assert q.depth_at(0) == 0
    assert q.enqueue(1_000) == 1                    # runs 1000..3050
    assert q.enqueue(2_000) == 2                    # waits; runs 3050..5100
    assert q.depth_at(3_049) == 2
    assert q.depth_at(3_050) == 1, "a job finishing AT t has resolved"
    assert q.depth_at(5_100) == 0
    assert q.max_depth == 2
    assert q.enqueue(9_000) == 1                    # idle server: starts at once
    assert q.depth_at(11_049) == 1 and q.depth_at(11_050) == 0
    assert DecoderQueueSim(service_ms=0).enqueue(5) == 0, "an instant decoder never queues"


def _staccato(utterances: int):
    return fixture.build_trace(utterances)


def test_the_default_tuning_is_inert_and_a_bare_simulate_is_byte_identical():
    """`Tuning()` carries no slow row (None == the fast floor), so the decoder model — which
    always runs — can change nothing: same commits, no transitions, whatever `service_ms`."""
    probs = _staccato(60)
    base = simulate(probs, Tuning())
    instant = simulate(probs, Tuning(service_ms=0))
    hot = simulate(probs, Tuning(service_ms=2_500))
    strip = lambda r: [(c.t_ms, c.kind, c.chunk_ms) for c in r.commits]
    assert strip(base) == strip(instant) == strip(hot)
    assert base.transitions == [] and hot.transitions == []
    assert base.time_in_slow_ms == 0
    assert base.max_queue_depth >= 1 and hot.max_queue_depth > base.max_queue_depth


def _gaps(result):
    return [b.t_ms - a.t_ms for a, b in zip(result.commits, result.commits[1:])]


def test_the_governor_bounds_the_queue_where_the_fast_floor_alone_lets_it_grow():
    """Row E9's property in the model: on a hot phone (service 2 500 > the 2 208 the fast floor
    commits at) the ungoverned queue grows without bound; governed, it never exceeds ENTER.
    Never a growing lag.

    What the intervals look like is subtler than "2.0 s or 3.2 s", and this test says so. With
    ONE server, depth 2 means "one running, one waiting", and it falls back to 1 the moment the
    OLDER job finishes — at most one service time after the commit that queued behind it. So the
    slow mode is entered at the endpoint after a commit that found the decoder busy and released
    at the first endpoint after the older job drained; the 3 200 number itself binds only when
    the backlog outlives the 2 944 endpoint (the next test). At service 2 500 the governed
    intervals are 2 208 and 2 944: the queue clears within one endpoint, and the commit lands at
    the first endpoint past the clear."""
    probs = _staccato(120)
    off = simulate(probs, Tuning(service_ms=2_500))                                  # slow == fast
    on = simulate(probs, Tuning(service_ms=2_500, slow_commit_interval_ms=SLOW_MS))
    assert off.max_queue_depth >= 4, f"ungoverned: {off.max_queue_depth}"
    assert on.max_queue_depth == BACKPRESSURE_ENTER_DEPTH, f"governed: {on.max_queue_depth}"
    gaps = _gaps(on)
    allowed = {k * IV for k in range(FAST_K, SLOW_K + 1)}          # 2 208, 2 944, 3 680
    assert set(gaps) <= allowed, sorted(set(gaps))
    assert FAST_K * IV in gaps, "the fast row still commits while the decoder keeps up"
    assert max(gaps) > FAST_K * IV, "and the governor held at least one endpoint back"
    assert set(gaps) == {FAST_K * IV, (FAST_K + 1) * IV}, "at 2 500 the queue clears within one endpoint"
    assert len(on.commits) < len(off.commits), "fewer commits: that is the duty bought back"
    assert on.time_in_slow_ms > 0
    assert len(on.transitions) >= 4
    assert on.transitions[0].line == SLOW_LINE
    assert all(a.slow != b.slow for a, b in zip(on.transitions, on.transitions[1:]))
    # and every commit carries the depth the funnel would have logged
    assert all(c.queue_depth is not None and c.queue_depth <= 2 for c in on.commits)


def test_the_slow_floors_own_number_binds_only_while_the_backlog_outlives_the_grid():
    """The 3 680 interval — ceil(3200 / 736) endpoints, the "~3.2 s while it catches up" of row
    E9 — appears once the older job is still running at the 2 944 endpoint, i.e. once the
    service time is within a grid step of the slow floor. Below that the depth-based release
    does the whole job and 3 200 is never consulted as a number. Both are correct behaviour;
    the acceptance sheet should expect 3.2 s intervals only under a real backlog."""
    probs = _staccato(120)
    cool = simulate(probs, Tuning(service_ms=2_500, slow_commit_interval_ms=SLOW_MS))
    hot = simulate(probs, Tuning(service_ms=3_200, slow_commit_interval_ms=SLOW_MS))
    assert SLOW_K * IV not in _gaps(cool)
    assert SLOW_K * IV in _gaps(hot)
    assert hot.max_queue_depth == BACKPRESSURE_ENTER_DEPTH
    assert cool.time_in_slow_ms < hot.time_in_slow_ms


def test_beyond_the_slow_floor_the_governor_slows_the_growth_but_cannot_stop_it():
    """The slow row is the bounded-duty value at the MEASURED F (2 050). A decoder slower than
    the slow floor itself — 4 000 ms per commit, a phone throttled ~2x — is beyond what 3 200
    can pay for: the governed queue still grows, only far more slowly than the ungoverned one.
    Recorded so the E9 evidence line is read with this limit in mind."""
    probs = _staccato(120)
    off = simulate(probs, Tuning(service_ms=4_000))
    on = simulate(probs, Tuning(service_ms=4_000, slow_commit_interval_ms=SLOW_MS))
    assert on.max_queue_depth > BACKPRESSURE_ENTER_DEPTH, "beyond the slow floor the bound is gone"
    assert on.max_queue_depth < off.max_queue_depth // 3, (on.max_queue_depth, off.max_queue_depth)
    assert set(_gaps(on)) == {FAST_K * IV, SLOW_K * IV}


def test_when_the_decoder_keeps_up_the_governor_never_engages():
    """Service 2 050 against commits 2 208 apart: each job finishes before the next commit, the
    depth never reaches 2, and the governed run is the ungoverned run."""
    probs = _staccato(60)
    off = simulate(probs, Tuning())
    on = simulate(probs, Tuning(slow_commit_interval_ms=SLOW_MS))
    assert [c.t_ms for c in on.commits] == [c.t_ms for c in off.commits]
    assert on.transitions == [] and on.max_queue_depth == 1


def test_time_in_slow_closes_an_open_interval_at_the_trace_end():
    from vadsim.machine import Transition
    trs = [Transition(100, 2, True, 3_200), Transition(400, 1, False, 2_000), Transition(900, 2, True, 3_200)]
    assert time_in_slow(trs, end_ms=1_000) == 300 + 100
    assert time_in_slow([], end_ms=1_000) == 0


# =======================================================================================
# 4. The trace-level twin.
# =======================================================================================

def test_the_cross_trace_fixture_is_current():
    """The file on disk is exactly what the generator produces today; if the trace or the
    machine changes, regenerate it (`python tests/backpressure_fixture.py`) and the Kotlin side
    picks up the new expectations on its next run."""
    assert fixture.FIXTURE.is_file(), "run `python tests/backpressure_fixture.py` to create it"
    on_disk = fixture.FIXTURE.read_text(encoding="utf-8").replace("\r\n", "\n")
    assert on_disk == fixture.render(fixture.build_trace(), fixture.SCHEDULE)


def test_the_cross_trace_fixture_replays_to_its_own_expectations():
    """`SileroEndpointerBackpressureTest.the_cross_trace_fixture_commits_where_the_simulator_says`
    — the Kotlin reads this same file, drives `SileroEndpointer.onQueueDepth` from the depth
    column and asserts these same commit instants and mode steps."""
    header, probs, schedule = fixture.parse(fixture.FIXTURE.read_text(encoding="utf-8"))
    assert int(header["fast_ms"]) == FAST_MS and int(header["slow_ms"]) == SLOW_MS
    assert schedule == fixture.SCHEDULE
    commits, transitions = fixture.replay(probs, schedule, FAST_MS, SLOW_MS)
    assert commits == fixture.expected_commits(header)
    assert [("slow" if t.slow else "fast", t.t_ms) for t in transitions] == fixture.expected_transitions(header)
    # The trace exercises both floors: intervals of both grid values, several steps each way.
    gaps = {b - a for a, b in zip(commits, commits[1:])}
    assert FAST_K * IV in gaps and SLOW_K * IV in gaps
    assert sum(1 for t in transitions if t.slow) >= 2 and sum(1 for t in transitions if not t.slow) >= 2
