"""The JVM fixtures, re-expressed as p-traces, asserting IDENTICAL commit timings.

Every expected number below is derived from the Kotlin tests' OWN arithmetic — the
`EndpointerGrid` values and the `Pump` cadence — and each test names the JVM test it
mirrors. Nothing here is a magic constant copied off a passing run; where a literal appears
it is also written out as the grid expression that produces it, so a tuning A/B moves both
sides together (that is the whole discipline `EndpointerGrid`'s KDoc argues for).

THE GRID at the shipped tuning (HANGOVER_MS = 350, MIN_SPEECH_MS = 300, MICRO_PAUSE_MS = 98):
    HANGOVER_FRAMES      = ceil(350/32) + 1 = 12       EndpointerGrid.kt:61-63
    HOLD_FRAMES          = 11                          EndpointerGrid.kt:72
    HOLD_TRAIL_MS        = 10 * 32 = 320               EndpointerGrid.kt:75
    HANGOVER_TRAIL_MS    = 11 * 32 = 352               EndpointerGrid.kt:81
    MICRO_PAUSE_FRAMES   = 98//32 + 2 = 5              EndpointerGrid.kt:89-90
    SPEECH_FRAMES_OVER_MIN = 300//32 + 2 = 11          EndpointerGrid.kt:54-55
    FIXTURE_INTERVAL_MS  = (11 + 12) * 32 = 736        EndpointerGrid.kt:107-108
    BURST_FRAMES         = 300//32 = 9  (288 ms)       SileroEndpointerTest.kt:539
"""

from __future__ import annotations

import pytest

from vadsim.machine import (
    BASE_MS,
    FRAME_MS,
    NO_CUT_POINT,
    NO_VERDICT,
    SileroEndpointerSim,
    Tuning,
    cap_cut_retain_ms,
    gate_track,
    simulate,
)

T = Tuning()

HANGOVER_FRAMES = T.hangover_frames()
HOLD_FRAMES = T.hold_frames()
HOLD_TRAIL_MS = (HOLD_FRAMES - 1) * FRAME_MS
HANGOVER_TRAIL_MS = T.hangover_trail_ms()
MICRO_PAUSE_FRAMES = T.micro_pause_frames()
SPEECH_FRAMES = T.speech_frames_over_min()
FIXTURE_INTERVAL_MS = T.fixture_interval_ms()
#: `SileroEndpointerTest.kt:539` — a burst strictly UNDER MIN_SPEECH_MS, on the frame grid.
BURST_FRAMES = T.min_speech_ms // FRAME_MS


def test_the_grid_matches_the_shipped_tuning():
    """`EndpointerGridTest.the_fixture_grid_is_valid_for_this_hangover`, in spirit: the two
    independent derivations — this integer arithmetic and the state machine's `<` — must
    agree, and the hangover must be OFF the 32 ms grid or every boundary test goes vacuous.
    """
    assert (HANGOVER_FRAMES, HOLD_FRAMES, HANGOVER_TRAIL_MS) == (12, 11, 352)
    assert (MICRO_PAUSE_FRAMES, SPEECH_FRAMES, FIXTURE_INTERVAL_MS) == (5, 11, 736)
    assert BURST_FRAMES == 9 and BURST_FRAMES * FRAME_MS == 288 < T.min_speech_ms
    assert T.hangover_ms % FRAME_MS != 0, "an on-grid hangover makes `<` vs `<=` invisible"
    assert T.micro_pause_ms % FRAME_MS != 0
    assert T.min_speech_ms % FRAME_MS != 0
    # `EndpointerTuning.HANGOVER_MIN_MS` (EndpointerTuning.kt:106) and the machine floor.
    assert T.hangover_ms >= 300 and T.hangover_ms > T.micro_pause_ms


class Pump:
    """`SileroEndpointerTest.Pump` (SileroEndpointerTest.kt:110-131), verbatim in behaviour:
    feeds whole frames of one probability at the 32 ms cadence and holds the clock between
    stretches."""

    def __init__(self, ep: SileroEndpointerSim, t: int = BASE_MS) -> None:
        self.ep = ep
        self.t = t
        self.commits = 0
        self.last_commit_ms = -1

    def run(self, p: float, frames: int) -> bool:
        fired = False
        for _ in range(frames):
            if self.ep.on_frame(p, self.t):
                fired = True
                self.commits += 1
                self.last_commit_ms = self.t
            self.t += FRAME_MS
        return fired


def endpointer() -> SileroEndpointerSim:
    """A bare endpointer, no session started — exactly how every `SileroEndpointerTest`
    fixture that does not exercise the governor constructs one. `minCommitIntervalMs` is then
    the conservative 8 000 (SileroEndpointer.kt:200) and `hasCommitted` is false, so the
    first cut is free."""
    return SileroEndpointerSim(T)


# =======================================================================================
# 1. The canonical utterance.
# =======================================================================================

def test_the_hangover_cuts_at_exactly_HANGOVER_MS_of_trailing_silence():
    """`SileroEndpointerTest.the_hangover_cuts_at_exactly_HANGOVER_MS_of_trailing_silence`
    (:417-429). 20 speech frames, then HOLD_FRAMES of silence that must NOT cut, then one
    more that must."""
    ep = endpointer()
    pump = Pump(ep)
    pump.run(0.9, 20)                                   # speech BASE..BASE+608, gate at BASE
    assert not pump.run(0.1, HOLD_FRAMES), (
        f"{HOLD_FRAMES} silent frames reach only {HOLD_TRAIL_MS} ms — under the hangover"
    )
    assert pump.run(0.1, 1), f"the next silent frame is {HANGOVER_TRAIL_MS} ms in"
    assert pump.commits == 1
    assert pump.last_commit_ms == BASE_MS + 640 + HANGOVER_TRAIL_MS       # == BASE + 992
    cut = ep.last_cut
    assert cut is not None
    # `EndpointCut` (SileroEndpointer.kt:689): speech is measured to the PENDING END.
    assert cut.speech_ms == 640
    assert cut.trail_ms == HANGOVER_TRAIL_MS


def test_the_canonical_utterance_through_the_service_seam():
    """The same fixture driven through `simulate()`, so the service's cap branch is in the
    loop. 20 + 12 = 32 frames = 1 024 ms of wall time, well inside the 4 s first cap, so the
    VAD cut is the only exit and the commit lands where the JVM fixture says."""
    probs = [0.9] * 20 + [0.1] * HANGOVER_FRAMES
    r = simulate(probs, T)
    assert len(r.commits) == 1
    c = r.commits[0]
    assert (c.kind, c.t_ms) == ("vad", BASE_MS + 640 + HANGOVER_TRAIL_MS)
    assert (c.speech_ms, c.trail_ms) == (640, HANGOVER_TRAIL_MS)
    assert c.retain_ms == 0
    # The buffer holds every ms from the session anchor to the frame that fired the cut.
    assert c.chunk_ms == 992 + FRAME_MS
    assert c.merged_endpoints_inside == 0 and c.discarded_bursts_inside == 0


def test_the_hangover_boundary_is_inclusive_off_the_frame_grid():
    """`SileroEndpointerTest.the_hangover_fires_at_exactly_HANGOVER_MS` (:445-464). The Pump
    steps a dip's age 320 -> 352, so `<` versus `<=` is invisible to it; this drives
    `on_frame` directly, as the JVM fixture does."""
    ep = endpointer()
    speech = T.min_speech_ms + 100
    assert not ep.on_frame(0.9, BASE_MS)
    assert not ep.on_frame(0.9, BASE_MS + speech)
    assert not ep.on_frame(0.1, BASE_MS + speech)              # pending end == BASE + speech
    assert not ep.on_frame(0.1, BASE_MS + speech + T.hangover_ms - 1), \
        "one millisecond under the hangover is not the hangover"
    assert ep.on_frame(0.1, BASE_MS + speech + T.hangover_ms), \
        "the boundary is inclusive: HANGOVER_MS of silence IS the hangover"


# =======================================================================================
# 2. The dead band never closes the gate.
# =======================================================================================

def test_music_that_never_dips_below_RELEASE_leaves_the_wall_cap_as_the_only_exit():
    """`SileroEndpointerTest.music_that_never_dips_below_RELEASE_leaves_the_wall_cap_as_the_
    only_exit` (:601-621) — the owner's one non-negotiable. Both regimes: parked in the dead
    band, and held above ONSET. Neither can stamp `tempEndMs`, so neither can reach the
    hangover check at ANY hangover value."""
    ep = endpointer()
    pump = Pump(ep)
    past_the_wall = 16_000 // FRAME_MS                  # 500 frames, past the 15 s wall
    pump.run(0.9, 20)
    assert ep.has_pending_speech(), "real speech opened the gate"
    assert not pump.run(0.42, past_the_wall), "a dead-band bed is not silence"
    assert not pump.run(0.9, past_the_wall), "and neither is a bed above ONSET"
    assert pump.commits == 0
    assert ep.pending_cut_point_ms() == NO_CUT_POINT


@pytest.mark.parametrize("hangover", [100, 320, 350, 500, 800])
def test_a_dead_band_bed_cannot_cut_at_any_hangover(hangover):
    """The claim the 4.4 retune makes to the owner, executable: the dead band's inertness is
    independent of HANGOVER_MS, because it is `onProb`'s :555 return and not a timer."""
    t = Tuning(hangover_ms=hangover)
    ep = SileroEndpointerSim(t)
    pump = Pump(ep)
    pump.run(0.9, 20)
    assert not pump.run(0.42, 1_000)
    assert pump.commits == 0


def test_dead_band_frames_do_not_stall_the_hangover_hard_timer():
    """`SileroEndpointerTest.dead_band_frames_do_not_stall_the_hangover_hard_timer`
    (:466-483). Half the non-cutting dip is mumble; the cut still lands at
    `BASE + 640 + HANGOVER_TRAIL_MS`, because the clock runs on wall time from the pending
    end and only a frame at or above ONSET resets it."""
    ep = endpointer()
    pump = Pump(ep)
    mumble = (HOLD_FRAMES - 1) // 2                    # EndpointerGrid.DEAD_BAND_FRAMES = 5
    silence = HOLD_FRAMES - 1 - mumble                 # 5
    pump.run(0.9, 20)
    assert not pump.run(0.1, 1)                        # pending end stamped at BASE+640
    assert not pump.run(0.42, mumble), "dead-band frames never commit themselves"
    assert not pump.run(0.1, silence)
    assert pump.run(0.1, 1), "the timer counted the dead band"
    assert pump.last_commit_ms == BASE_MS + 640 + HANGOVER_TRAIL_MS


def test_a_frame_back_above_ONSET_resets_the_hangover_clock():
    """`SileroEndpointerTest.a_frame_back_above_ONSET_resets_the_hangover_clock` (:485-499).
    The HARD reset: cleared, not paused."""
    ep = endpointer()
    pump = Pump(ep)
    pump.run(0.9, 20)
    assert not pump.run(0.1, HOLD_FRAMES)
    assert not pump.run(0.9, 1)
    restart_ms = pump.t
    assert not pump.run(0.1, HOLD_FRAMES)
    assert pump.run(0.1, 1)
    assert pump.last_commit_ms == restart_ms + HANGOVER_TRAIL_MS


def test_no_verdict_frames_do_not_short_circuit_the_hangover():
    """`SileroEndpointerTest.no_verdict_frames_do_not_short_circuit_the_hangover` (:501-513).
    960 ms of nothing at all, then the pending end is stamped by the first REAL silence."""
    ep = endpointer()
    pump = Pump(ep)
    pump.run(0.9, 20)
    assert not pump.run(NO_VERDICT, 30)
    assert not pump.run(0.1, HOLD_FRAMES)
    assert pump.run(0.1, 1)
    assert pump.last_commit_ms == BASE_MS + 1600 + HANGOVER_TRAIL_MS


# =======================================================================================
# 3. The micro-pause memory.
# =======================================================================================

def test_no_micro_pause_is_offered_until_a_dip_outlives_98ms():
    """`SileroEndpointerTest.no_micro_pause_is_offered_until_a_dip_outlives_98ms` (:758-778).
    The 98 ms floor is exclusive (whisper.cpp:5581), so the 5th dip frame is the first to
    qualify: 128 > 98, while the 4th is only 96 ms old."""
    ep = endpointer()
    pump = Pump(ep)
    pump.run(0.9, 20)
    assert ep.pending_cut_point_ms() == NO_CUT_POINT
    pump.run(0.1, 1)                                    # dip starts at BASE+640, 0 ms old
    assert ep.pending_cut_point_ms() == NO_CUT_POINT
    pump.run(0.1, 3)                                    # 32, 64, 96 — 96 is not > 98
    assert ep.pending_cut_point_ms() == NO_CUT_POINT
    pump.run(0.1, 1)                                    # 128 ms old
    assert ep.pending_cut_point_ms() == BASE_MS + 640


def test_the_micro_pause_floor_is_exclusive_at_exactly_MICRO_PAUSE_MS():
    """`SileroEndpointerTest.the_micro_pause_floor_is_exclusive_at_exactly_MICRO_PAUSE_MS`
    (:796-822). Off the frame grid, so `>` versus `>=` is visible."""
    ep = endpointer()
    dip_start = BASE_MS + T.min_speech_ms + 100
    assert not ep.on_frame(0.9, BASE_MS)
    assert not ep.on_frame(0.1, dip_start)
    assert ep.pending_cut_point_ms() == NO_CUT_POINT
    assert not ep.on_frame(0.1, dip_start + T.micro_pause_ms)
    assert ep.pending_cut_point_ms() == NO_CUT_POINT, \
        "the floor is exclusive: exactly MICRO_PAUSE_MS is not MORE than MICRO_PAUSE_MS"
    assert not ep.on_frame(0.1, dip_start + T.micro_pause_ms + 1)
    assert ep.pending_cut_point_ms() == dip_start, \
        "one millisecond past the floor promotes the dip's START, not the current frame"


def test_the_micro_pause_survives_a_re_onset_within_the_same_stretch():
    """`SileroEndpointerTest.the_micro_pause_survives_a_re_onset_within_the_same_stretch`
    (:825-850). The offer only ever moves FORWARD, never back to "none"."""
    ep = endpointer()
    pump = Pump(ep)
    pump.run(0.9, 20)
    pump.run(0.1, MICRO_PAUSE_FRAMES)                   # dip at BASE+640 remembered
    assert ep.pending_cut_point_ms() == BASE_MS + 640
    pump.run(0.9, 10)                                   # speech resumes: memory survives
    assert ep.pending_cut_point_ms() == BASE_MS + 640
    pump.run(0.1, 4)                                    # the new dip is 0/32/64/96 — too young
    assert ep.pending_cut_point_ms() == BASE_MS + 640, \
        "a dip too young to qualify must not erase the older boundary it cannot yet replace"
    pump.run(0.1, 1)                                    # 128 ms: the NEWER dip replaces it
    assert ep.pending_cut_point_ms() == BASE_MS + 1120
    assert pump.commits == 0


def test_a_commit_clears_the_micro_pause_memory():
    """`SileroEndpointerTest.a_commit_clears_the_micro_pause_memory` (:852-870)."""
    ep = endpointer()
    pump = Pump(ep)
    pump.run(0.9, 20)
    pump.run(0.1, MICRO_PAUSE_FRAMES)
    assert ep.pending_cut_point_ms() == BASE_MS + 640
    assert pump.run(0.1, HANGOVER_FRAMES - MICRO_PAUSE_FRAMES)
    assert ep.pending_cut_point_ms() == NO_CUT_POINT


def test_a_discarded_short_burst_keeps_the_remembered_pause():
    """`SileroEndpointerTest.a_discarded_short_burst_keeps_the_remembered_pause` (:883-921).
    The deliberate divergence from whisper.cpp:5594: `clearForNextSegment` runs on the commit
    path only, `closeGate` runs on both — so a 200 ms cough cannot erase a good boundary."""
    ep = endpointer()
    pump = Pump(ep)
    pump.run(0.9, 20)
    assert pump.run(0.1, HANGOVER_FRAMES)
    pump.run(0.9, 5)                                    # 160 ms burst — under MIN_SPEECH_MS
    cough_dip = pump.t
    assert not pump.run(0.1, HANGOVER_FRAMES), "discarded, no commit"
    assert pump.commits == 1
    assert ep.pending_cut_point_ms() == cough_dip
    pump.run(0.1, max(20, HANGOVER_FRAMES))
    assert ep.pending_cut_point_ms() == cough_dip, \
        "silence with the gate SHUT must not erase the offer either"


# =======================================================================================
# 4. The MIN_SPEECH discard — a 9-frame burst.
# =======================================================================================

def test_a_nine_frame_burst_is_discarded_without_a_commit():
    """`SileroEndpointerTest.a_burst_under_MIN_SPEECH_MS_is_discarded_without_a_commit`
    (:515-526), on the BURST_FRAMES grid (:539): 9 frames = 288 ms, strictly under
    MIN_SPEECH_MS = 300, so the endpoint is FOUND and DISCARDED."""
    ep = endpointer()
    pump = Pump(ep)
    pump.run(0.9, BURST_FRAMES)                         # 288 ms of speech, gate at BASE
    assert not pump.run(0.1, 40), "288 ms is under MIN_SPEECH_MS"
    assert pump.commits == 0
    assert not ep.has_pending_speech(), \
        "the pending-speech latch needs 300 ms of wall time since the gate opened"
    assert ep.discards == 1
    # The pending end was BASE + 288 and the dip promoted at its 5th frame.
    assert ep.pending_cut_point_ms() == BASE_MS + BURST_FRAMES * FRAME_MS


def test_exactly_MIN_SPEECH_MS_does_not_cut_but_does_count_as_pending_speech():
    """`SileroEndpointerTest.exactly_MIN_SPEECH_MS_does_not_cut_but_does_count_as_pending_
    speech` (:663-687). The commit gate is strict (`<=` discards), the latch is inclusive
    (`>=`), and they differ only at exactly 300 ms."""
    ep = endpointer()
    ms = T.min_speech_ms
    assert not ep.on_frame(0.9, BASE_MS)
    assert not ep.on_frame(0.9, BASE_MS + ms)
    assert ep.has_pending_speech()
    assert not ep.on_frame(0.1, BASE_MS + ms)           # pending end exactly MIN_SPEECH later
    assert not ep.on_frame(0.1, BASE_MS + ms + T.hangover_ms + 100), \
        "comfortably past the hangover: the only reason this does not cut is the strict gate"
    assert ep.probe_resets == 0, "no commit at exactly MIN_SPEECH_MS"
    assert ep.has_pending_speech(), "but the buffer is not empty"


def test_word_by_word_bursts_under_MIN_SPEECH_MS_still_commit_nothing():
    """`SileroEndpointerTest.word_by_word_bursts_under_MIN_SPEECH_MS_still_commit_nothing`
    (:561-583) — THE KNOWN GAP, pinned as a fact. Five good boundaries, five discards,
    nothing committed."""
    ep = endpointer()
    pump = Pump(ep)
    gap_frames = HANGOVER_FRAMES + 1
    for _ in range(5):
        pump.run(0.9, BURST_FRAMES)
        assert not pump.run(0.1, gap_frames)
    assert pump.commits == 0
    assert not ep.has_pending_speech()
    assert ep.discards == 5


def test_a_run_of_short_discarded_bursts_still_leaves_the_wall_cap_a_cut_point():
    """`SileroEndpointerTest.a_run_of_short_discarded_bursts_still_leaves_the_wall_cap_a_cut_
    point` (:639-661). `pendingCutPointMs()` is the evidence `pendingSpeech` can no longer
    carry — which is what makes the cap cut consume its window."""
    ep = endpointer()
    pump = Pump(ep)
    for _ in range(8):
        pump.run(0.9, BURST_FRAMES)
        assert not pump.run(0.1, HANGOVER_FRAMES + 1)
    assert pump.commits == 0
    assert not ep.has_pending_speech()
    assert ep.pending_cut_point_ms() > NO_CUT_POINT


# =======================================================================================
# 5. The governor MERGE branch — onSessionStart with 2 000 (npu-turbo).
# =======================================================================================

def test_turbo_merges_two_close_endpoints_then_commits_the_third():
    """`SileroEndpointerTest.pro_merges_an_utterance_that_endpoints_inside_1200ms` (:937-967)
    and `multi_paces_three_utterances_into_one_commit` (:969-991), retargeted at
    `CommitCadencePolicy.MIN_COMMIT_INTERVAL_TURBO_MS` = 2 000 (CommitCadencePolicy.kt:107).

    The fixture's endpoints land FIXTURE_INTERVAL_MS = 736 ms apart, so with a 2 000 ms floor
    endpoints 2 (+736) and 3 (+1 472) MERGE and endpoint 4 (+2 208) clears it. That is the
    arithmetic the constant's own KDoc states: the visible interval is `ceil(floor/T) * T`.
    """
    iv = FIXTURE_INTERVAL_MS
    floor = 2_000
    ep = SileroEndpointerSim(T)
    ep.on_session_start(now_ms=BASE_MS, min_commit_interval_ms=floor)
    pump = Pump(ep)

    pump.run(0.9, 20)
    assert pump.run(0.1, HANGOVER_FRAMES), "the session's FIRST endpoint is never merged"
    commit1 = pump.last_commit_ms
    assert commit1 == BASE_MS + 640 + HANGOVER_TRAIL_MS

    # endpoint 2, at +736 ms — inside the floor.
    pump.run(0.9, SPEECH_FRAMES)
    cut_point2 = pump.t
    assert not pump.run(0.1, HANGOVER_FRAMES), f"an endpoint at +{iv} ms is inside {floor}"
    assert pump.commits == 1
    assert ep.has_pending_speech(), "the merged audio is still uncommitted"
    assert ep.pending_cut_point_ms() == cut_point2, \
        "the merged endpoint becomes the best known cut point"

    # endpoint 3, at +1472 ms — still inside.
    pump.run(0.9, SPEECH_FRAMES)
    assert not pump.run(0.1, HANGOVER_FRAMES), f"and +{2 * iv} ms is still inside {floor}"
    assert pump.commits == 1
    assert ep.merges == 2

    # endpoint 4, at +2208 ms — clears it.
    pump.run(0.9, SPEECH_FRAMES)
    assert pump.run(0.1, HANGOVER_FRAMES), f"+{3 * iv} ms clears the {floor} ms floor"
    assert pump.commits == 2
    assert pump.last_commit_ms == commit1 + 3 * iv
    assert ep.merges == 2


def test_exactly_the_interval_commits_and_one_millisecond_more_merges():
    """`SileroEndpointerTest.exactly_the_interval_commits_and_one_millisecond_more_merges`
    (:1012-1038). The guard is `nowMs - lastCommitMs < minCommitIntervalMs`, so exactly the
    interval has ELAPSED and commits."""

    def second_endpoint_under(interval_ms: int) -> Pump:
        ep = SileroEndpointerSim(T)
        ep.on_session_start(now_ms=BASE_MS, min_commit_interval_ms=interval_ms)
        pump = Pump(ep)
        pump.run(0.9, 20)
        assert pump.run(0.1, HANGOVER_FRAMES), "commit 1 is the session's free first cut"
        pump.run(0.9, SPEECH_FRAMES)
        pump.run(0.1, HANGOVER_FRAMES)
        return pump

    iv = FIXTURE_INTERVAL_MS
    elapsed = second_endpoint_under(iv)
    assert elapsed.commits == 2, "exactly the interval has ELAPSED"
    assert elapsed.last_commit_ms == BASE_MS + 640 + HANGOVER_TRAIL_MS + iv
    assert second_endpoint_under(iv + 1).commits == 1, "one millisecond more and it merges"


def test_a_merge_really_closes_the_gate():
    """`multi_paces_three_utterances_into_one_commit`'s tail (:986-990): the governor is a
    floor on commits, NOT a timer that fires when it expires. Six seconds of silence after
    the merge must not produce a cut on a boundary already judged."""
    ep = SileroEndpointerSim(T)
    ep.on_session_start(now_ms=BASE_MS, min_commit_interval_ms=6_000)
    pump = Pump(ep)
    pump.run(0.9, 20)
    assert pump.run(0.1, HANGOVER_FRAMES)
    pump.run(0.9, SPEECH_FRAMES)
    assert not pump.run(0.1, HANGOVER_FRAMES)
    pump.run(0.9, SPEECH_FRAMES)
    assert not pump.run(0.1, HANGOVER_FRAMES)
    assert pump.commits == 1
    assert ep.has_pending_speech()
    assert not pump.run(0.1, 200), "an elapsed interval is not itself a cut"
    assert pump.commits == 1


def test_before_any_session_start_the_floor_is_the_conservative_8000():
    """`SileroEndpointerTest.before_any_session_start_the_floor_is_the_conservative_8000`
    (:1138). SileroEndpointer.kt:200's literal is
    `CommitCadencePolicy.MIN_COMMIT_INTERVAL_LARGE_MS` (CommitCadencePolicy.kt:126)."""
    assert Tuning().pre_session_floor_ms == 8_000
    assert SileroEndpointerSim(T).min_commit_interval_ms == 8_000


def test_reset_anchors_the_governor_on_the_last_frame_seen():
    """`SileroEndpointerTest.reset_anchors_the_governor_on_the_last_frame_seen` (:1040).
    `reset()` carries no clock (SileroEndpointer.kt:364-368), so an external commit
    re-anchors on `lastFrameMs` — which `onFrame` stamped BEFORE probing (:267)."""
    ep = SileroEndpointerSim(T)
    ep.on_session_start(now_ms=BASE_MS, min_commit_interval_ms=8_000)
    pump = Pump(ep)
    pump.run(0.9, 5)
    last_frame = pump.t - FRAME_MS
    ep.reset()
    assert ep.last_commit_ms == last_frame
    assert ep.has_committed is True
    assert ep.pending_cut_point_ms() == NO_CUT_POINT


# =======================================================================================
# 6. The first-segment 4 s cap — the SERVICE, not the endpointer.
# =======================================================================================

def test_the_first_segment_cap_fires_at_4000ms_and_the_later_cap_at_15000():
    """`SegmentCapPolicyTest.firstUncommittedStretchCapsAt4000ms` /
    `laterStretchesKeepThe15000msCap` (SegmentCapPolicyTest.kt:20-35), driven through the
    whole seam.

    Real speech, then a dead-band bed forever: no VAD cut can ever fire
    (`music_that_never_dips_below_RELEASE...`), so the cap is the only exit.

      * cap 1 at BASE + 4 000 — 4 000/32 = 125 exactly, so the frame grid lands ON it. The
        window is CONSUMED (`pendingSpeech` latched during the speech), so the later cap
        governs next: `capCutConsumesWindow(true, false)` (FloatingBubbleService.kt:2068-2075).
      * cap 2 at the first frame with `now - 4000 >= 15000`: 32k >= 19 000 -> k = 594 ->
        BASE + 19 008.
      * cap 2's bookkeeping RE-ARMS: by then `pendingSpeech` is false (the reset cleared it
        and a dead-band bed can never re-latch it) and there is no micro-pause offer, so
        `(false, false)` takes `segmentCapPolicy.onSessionStart(now)` (:2077) and the 4 s
        window re-opens — the 3.5.0 LOCAL-silence parity guarantee.
      * cap 3 therefore fires 4 000 ms later: 32k >= 23 008 -> k = 719 -> BASE + 23 008.
    """
    probs = [0.9] * 20 + [0.42] * 700                    # 720 frames = 23 040 ms
    r = simulate(probs, T)
    caps = r.of_kind("cap")
    assert r.of_kind("vad") == [], "a dead-band bed cannot produce a VAD cut"
    assert [c.t_ms - BASE_MS for c in caps] == [4_000, 19_008, 23_008]
    assert [c.cap_ms for c in caps] == [4_000, 15_000, 4_000]
    assert [c.consumed_window for c in caps] == [True, False, False]
    assert [c.retain_ms for c in caps] == [0, 0, 0], "no offer -> commit everything (3.6.0)"
    # chunk = every ms from the buffer's start to the frame that fired, inclusive.
    assert [c.chunk_ms for c in caps] == [4_032, 15_008, 4_000]


def test_a_cloud_session_never_sees_the_4000ms_cap():
    """FloatingBubbleService.kt:2831 — the 4 s first cap is LOCAL-ONLY; a cloud session
    closes the window at `onOpen`, so every segment keeps the pre-existing 15 s cap."""
    probs = [0.9] * 20 + [0.42] * 700
    r = simulate(probs, T, is_cloud_session=True)
    caps = r.of_kind("cap")
    assert caps, "the cap must still fire"
    assert caps[0].cap_ms == 15_000
    assert caps[0].t_ms - BASE_MS == 15_008              # 32k >= 15 000 -> k = 469
    assert all(c.consumed_window for c in caps), "cloud silence consumes the window too"


def test_a_cap_cut_takes_the_micro_pause_offer_when_it_is_fresh():
    """FloatingBubbleService.kt:2090 + `CommitCadencePolicy.capCutRetainMs`
    (CommitCadencePolicy.kt:219-224): when the endpointer remembers a micro-pause inside the
    window, the cap cuts THERE and retains the tail.

    A dip of MICRO_PAUSE_FRAMES promotes an offer and then speech resumes above ONSET, so
    the gate never closes and the cap is still the exit. The dip is placed so the offer is
    FRESH (younger than CAP_CUT_MAX_RETAIN_MS = 3 000) at the 4 s cap.
    """
    # 100 speech frames (3 200 ms), a 5-frame dip, then speech again to the cap.
    probs = [0.9] * 100 + [0.1] * MICRO_PAUSE_FRAMES + [0.9] * 100
    r = simulate(probs, T)
    caps = r.of_kind("cap")
    assert len(caps) == 1 and caps[0].t_ms - BASE_MS == 4_000
    dip_start_ms = BASE_MS + 100 * FRAME_MS             # BASE + 3 200
    assert caps[0].cut_point_ms == dip_start_ms
    assert caps[0].retain_ms == 800                     # 4 000 - 3 200, inside the 3 000 window
    assert caps[0].chunk_ms == 4_032 - 800
    assert caps[0].consumed_window is True


def test_a_stale_micro_pause_offer_retains_nothing():
    """`capCutRetainMs`'s stale branch (CommitCadencePolicy.kt:223) and its own KDoc reason:
    an offer older than CAP_CUT_MAX_RETAIN_MS would defer most of the window into the next
    one and push the effective wall bound from 15 s to ~28 s."""
    assert cap_cut_retain_ms(10_000, 9_000, 3_000) == 1_000
    assert cap_cut_retain_ms(10_000, 7_000, 3_000) == 3_000, "exactly the max is NOT stale"
    assert cap_cut_retain_ms(10_000, 6_999, 3_000) == 0, "one ms staler and the offer dies"
    assert cap_cut_retain_ms(10_000, NO_CUT_POINT, 3_000) == 0
    assert cap_cut_retain_ms(10_000, 10_000, 3_000) == 0, "an equal offer is not an offer"
    assert cap_cut_retain_ms(10_000, 11_000, 3_000) == 0, "nor is a future one"


def test_a_vad_commit_also_ends_the_first_cap_window():
    """`SegmentCapPolicyTest.aPauseCommitAlsoEndsTheFirstCapWindow` (:38-47) through the
    seam: the VAD cut at 992 ms flips the policy to the 15 s cap, so the next cap fires
    15 s after THAT commit and not 4 s after the session."""
    probs = [0.9] * 20 + [0.1] * HANGOVER_FRAMES + [0.42] * 700
    r = simulate(probs, T)
    assert r.commits[0].kind == "vad" and r.commits[0].t_ms - BASE_MS == 992
    caps = r.of_kind("cap")
    assert caps, "the bed must eventually hit a cap"
    assert caps[0].cap_ms == 15_000
    # 32k - 992 >= 15 000 -> 32k >= 15 992 -> k = 500 -> 16 000.
    assert caps[0].t_ms - BASE_MS == 16_000


def test_the_cap_is_checked_only_when_the_endpointer_did_not_cut():
    """The `else if` at FloatingBubbleService.kt:2014. A frame that fires a VAD cut never
    also fires a cap cut, even when the cap was already exceeded on that very frame."""
    # 4 000 ms of speech (125 frames) so the first cap is due exactly as the dip's cut lands.
    speech = 4_000 // FRAME_MS
    probs = [0.9] * speech + [0.1] * HANGOVER_FRAMES + [0.9] * 5
    r = simulate(probs, T)
    # The cap is exceeded from frame 125 (BASE+4000) onward, and that frame is silence, so
    # the cap wins first — the hangover has not elapsed yet.
    assert r.commits[0].kind == "cap"
    assert r.commits[0].t_ms - BASE_MS == 4_000
    # And exactly one commit was emitted on that frame, never two.
    stamps = [c.t_ms for c in r.commits]
    assert len(stamps) == len(set(stamps))


# =======================================================================================
# 7. Verifier additions (2026-09-03): the SileroEndpointerTest fixtures the builder did not
#    port, driven as p-traces and compared to the Kotlin tests' asserted timings; then the
#    service-seam cases the port's own doubts named.
# =======================================================================================

def test_exactly_ONSET_opens_the_gate_and_the_11th_frame_latches_pending_speech():
    """`SileroEndpointerTest.ONSET_opens_the_gate_and_MIN_SPEECH_MS_latches_pending_speech`
    (:292-302). p == ONSET exactly must open (`>=`, SileroEndpointer.kt:539); 10 frames put
    the newest at +288 (under 300), the 11th at +320 latches."""
    ep = endpointer()
    pump = Pump(ep)
    assert not pump.run(T.onset, 10)
    assert ep.speaking, "exactly ONSET opens the gate — the comparison is >="
    assert not ep.has_pending_speech(), "288 ms of speech is under MIN_SPEECH_MS"
    assert not pump.run(T.onset, 1)
    assert ep.has_pending_speech(), "the 11th frame is 320 ms in — the latch must set"


def test_the_latch_is_inclusive_at_exactly_MIN_SPEECH_MS():
    """`SileroEndpointerTest.the_latch_fires_at_exactly_MIN_SPEECH_MS` (:316-326), off the
    grid: 299 ms does not latch, 300 ms does (`>=`, SileroEndpointer.kt:545)."""
    ep = endpointer()
    assert not ep.on_frame(T.onset, BASE_MS)
    assert not ep.has_pending_speech(), "the frame that opens the gate is 0 ms in"
    assert not ep.on_frame(T.onset, BASE_MS + T.min_speech_ms - 1)
    assert not ep.has_pending_speech(), "299 ms is not yet MIN_SPEECH_MS"
    assert not ep.on_frame(T.onset, BASE_MS + T.min_speech_ms)
    assert ep.has_pending_speech(), "the boundary is inclusive: 300 ms IS MIN_SPEECH_MS"


@pytest.mark.parametrize("p", [0.49, 0.42])
def test_a_frame_under_ONSET_never_opens_the_gate(p):
    """`a_frame_below_ONSET_never_opens_the_gate` (:284) and
    `the_dead_band_alone_never_opens_the_gate` (:328): 3.2 s of it is still not speech."""
    ep = endpointer()
    pump = Pump(ep)
    assert not pump.run(p, 100)
    assert not ep.speaking and not ep.has_pending_speech()
    assert ep.pending_cut_point_ms() == NO_CUT_POINT


def test_dead_band_frames_do_not_close_the_gate_or_move_the_speech_start():
    """`SileroEndpointerTest.dead_band_frames_do_not_close_an_open_gate_or_move_the_speech_
    start` (:336-346). 5 speech frames (128 ms, no latch), 640 ms of dead band, then ONE onset
    frame at BASE+800 latches — because `speechStartMs` survived the dead band untouched."""
    ep = endpointer()
    pump = Pump(ep)
    pump.run(0.9, 5)
    assert not ep.has_pending_speech()
    assert not pump.run(0.42, 20)
    assert ep.speaking, "inert, gate stays open"
    assert not ep.has_pending_speech(), "a dead-band frame never runs the latch itself"
    assert not pump.run(0.9, 1)
    assert ep.has_pending_speech(), "800 ms since speechStart: speechStart survived"


def test_no_verdict_frames_do_not_close_an_open_gate():
    """`SileroEndpointerTest.no_verdict_frames_do_not_close_an_open_gate` (:348-356)."""
    ep = endpointer()
    pump = Pump(ep)
    pump.run(0.9, 11)
    assert ep.has_pending_speech()
    assert not pump.run(NO_VERDICT, 100)
    assert ep.has_pending_speech() and ep.speaking


def test_silence_below_RELEASE_does_not_clear_the_uncommitted_buffer():
    """`SileroEndpointerTest.silence_below_RELEASE_does_not_clear_the_uncommitted_buffer`
    (:374-385): HOLD_FRAMES of silence — the longest run that cannot commit."""
    ep = endpointer()
    pump = Pump(ep)
    pump.run(0.9, 11)
    assert ep.has_pending_speech()
    assert not pump.run(0.0, HOLD_FRAMES)
    assert ep.has_pending_speech(), "a silent frame is not a commit"


def test_reset_clears_pending_speech_and_closes_the_gate():
    """`SileroEndpointerTest.reset_clears_pending_speech` (:387-402): the gate closes with the
    latch, so the next onset frame starts a FRESH clock rather than latching instantly off a
    stale `speechStartMs`."""
    ep = endpointer()
    pump = Pump(ep)
    pump.run(0.9, 11)
    assert ep.has_pending_speech()
    ep.reset()
    assert not ep.has_pending_speech()
    assert not pump.run(0.9, 1)
    assert not ep.has_pending_speech(), "reset closed the gate too"


def test_a_second_utterance_is_measured_from_its_OWN_start():
    """`SileroEndpointerTest.a_second_utterance_is_measured_from_its_OWN_start` (:701-716).
    After a commit, an 8-frame burst is 256 ms on the new clock (1 440 on the old one): the
    dip that follows must DISCARD it, not commit."""
    ep = endpointer()
    pump = Pump(ep)
    pump.run(0.9, 20)
    assert not pump.run(0.1, HOLD_FRAMES)
    assert pump.run(0.1, 1), "the first utterance commits"
    pump.run(0.9, 8)
    assert not pump.run(0.1, max(40, HANGOVER_FRAMES)), \
        "a stale speech start would measure this burst from the FIRST utterance and cut"
    assert pump.commits == 1
    assert ep.discards == 1


def test_reset_clears_the_micro_pause_memory():
    """`SileroEndpointerTest.reset_clears_the_micro_pause_memory` (:872-881)."""
    ep = endpointer()
    pump = Pump(ep)
    pump.run(0.9, 20)
    pump.run(0.1, MICRO_PAUSE_FRAMES)
    assert ep.pending_cut_point_ms() == BASE_MS + 640
    ep.reset()
    assert ep.pending_cut_point_ms() == NO_CUT_POINT


def test_the_sessions_first_endpoint_is_never_merged():
    """`SileroEndpointerTest.the_sessions_first_endpoint_is_never_merged` (:923-935) at the
    8 000 LARGE floor: `hasCommitted` is a FLAG (SileroEndpointer.kt:596)."""
    ep = SileroEndpointerSim(T)
    ep.on_session_start(now_ms=BASE_MS, min_commit_interval_ms=8_000)
    pump = Pump(ep)
    pump.run(0.9, 20)
    assert pump.run(0.1, HANGOVER_FRAMES)
    assert pump.commits == 1 and ep.merges == 0


def test_reset_anchors_the_governor_on_the_last_frame_seen_behaviourally():
    """`SileroEndpointerTest.reset_anchors_the_governor_on_the_last_frame_seen` (:1040-1083),
    the WHOLE fixture (the builder's version read the fields; this one drives the merge).

    A 1 200 floor. `reset()` after 20 frames anchors on BASE+608 (the last frame seen,
    SileroEndpointer.kt:365 via `lastFrameMs` stamped at :267). The next endpoint lands
    FIXTURE_INTERVAL_MS = 736 after it — inside 1 200 → MERGE. Measured from the SESSION it
    would be 20 + 11 + 12 frames + trail = 1 344 ≥ 1 200 and would cut; only a floor between
    those two tells the anchors apart. Then a reset BEFORE a new session's first frame anchors
    on the session open (:415), not the minute-old frame."""
    ep = SileroEndpointerSim(T)
    ep.on_session_start(now_ms=BASE_MS, min_commit_interval_ms=1_200)
    pump = Pump(ep)
    pump.run(0.9, 20)                                   # last frame at BASE+608
    ep.reset()                                          # the wall-cap cut in onAudioChunk
    assert not ep.has_pending_speech()
    pump.run(0.9, SPEECH_FRAMES)
    assert not pump.run(0.1, HANGOVER_FRAMES), \
        f"{FIXTURE_INTERVAL_MS} ms after the cap cut is inside 1200 — measured from the " \
        f"SESSION it would be {pump.t - FRAME_MS - BASE_MS} and would cut"
    assert pump.commits == 0 and ep.merges == 1
    pump.t = BASE_MS + 60_000
    ep.on_session_start(now_ms=BASE_MS + 60_000, min_commit_interval_ms=1_200)
    ep.reset()                                          # switchSource, before a single frame
    pump.run(0.9, SPEECH_FRAMES)
    assert not pump.run(0.1, HANGOVER_FRAMES), \
        "the anchor is this session's open, not the minute-old frame the last one left"
    assert pump.commits == 0


def test_onSessionStart_re_arms_the_first_free_cut_and_empties_the_buffer_bookkeeping():
    """`SileroEndpointerTest.onSessionStart_re_arms_the_first_free_cut` (:1085-1122): the
    governor is shown ENGAGED (a merge at multi's 6 000) before a new session — opened still
    inside that floor — gets a free first cut, with `pendingSpeech` and the micro-pause offer
    both cleared by `onSessionStart`'s `clearForNextSegment()` (SileroEndpointer.kt:423)."""
    ep = SileroEndpointerSim(T)
    ep.on_session_start(now_ms=BASE_MS, min_commit_interval_ms=6_000)
    pump = Pump(ep)
    pump.run(0.9, 20)
    assert pump.run(0.1, HANGOVER_FRAMES)               # commit 1: the free first cut
    pump.run(0.9, SPEECH_FRAMES)
    merged_cut_point = pump.t
    assert not pump.run(0.1, HANGOVER_FRAMES), "still inside multi's 6 s"
    assert ep.has_pending_speech()
    assert ep.pending_cut_point_ms() == merged_cut_point
    next_session = pump.t + 1_000                       # still far inside the 6 s floor
    pump.t = next_session
    ep.on_session_start(now_ms=next_session, min_commit_interval_ms=6_000)
    assert not ep.has_pending_speech(), "a new session starts with an empty buffer"
    assert ep.pending_cut_point_ms() == NO_CUT_POINT
    pump.run(0.9, SPEECH_FRAMES)
    assert pump.run(0.1, HANGOVER_FRAMES), "a new session's first cut is free again"
    assert pump.commits == 2


def test_before_any_session_start_the_floor_brackets_8000_behaviourally():
    """`SileroEndpointerTest.before_any_session_start_the_floor_is_the_conservative_8000`
    (:1138-1160), the WHOLE fixture: an endpoint `BELOW_LARGE_MS` = 6 976 after the first
    commit merges, one `ABOVE_LARGE_MS` = 8 128 after it commits (EndpointerGrid.kt:118-119).
    The padding is solved for, as the Kotlin does."""
    BELOW, ABOVE = 6_976, 8_128                         # EndpointerGrid.BELOW/ABOVE_LARGE_MS
    ep = endpointer()
    pump = Pump(ep)
    pad1 = BELOW // FRAME_MS - HANGOVER_FRAMES - SPEECH_FRAMES
    pad2 = (ABOVE - BELOW) // FRAME_MS - HANGOVER_FRAMES - SPEECH_FRAMES
    pump.run(0.9, 20)
    assert pump.run(0.1, HANGOVER_FRAMES), "the first cut is free here too"
    first = pump.last_commit_ms
    pump.run(0.1, pad1)                                 # gate shut: only the clock moves
    pump.run(0.9, SPEECH_FRAMES)
    assert not pump.run(0.1, HANGOVER_FRAMES), f"{BELOW} ms after the commit is inside 8000"
    assert pump.t - FRAME_MS - first == BELOW, "the fixture really landed on its bracket"
    pump.run(0.1, max(0, pad2))
    pump.run(0.9, SPEECH_FRAMES)
    assert pump.run(0.1, HANGOVER_FRAMES), f"{ABOVE} ms clears it"
    assert pump.last_commit_ms - first == ABOVE
    assert pump.commits == 2


def test_the_pre_session_floor_seam_exactly_8000_commits_and_one_frame_less_merges():
    """`CommitCadencePolicyTest.theEndpointersPreSessionFloorIsThisObjectsLargeInterval`
    (CommitCadencePolicyTest.kt:337-360) via its `secondCutAttemptAfter` (:375-400): the
    second endpoint exactly 8 000 = 250 x 32 after the first COMMITS (`<` at
    SileroEndpointer.kt:596); one frame earlier it merges."""

    def second_cut_attempt_after(interval_ms: int) -> Pump:
        assert (interval_ms - FIXTURE_INTERVAL_MS) % FRAME_MS == 0, "on the 32 ms grid"
        ep = endpointer()
        pump = Pump(ep)
        pump.run(0.9, 20)
        assert pump.run(0.1, HANGOVER_FRAMES), "the session's first cut is free on every floor"
        assert pump.last_commit_ms == BASE_MS + 640 + HANGOVER_TRAIL_MS
        pump.run(0.1, (interval_ms - FIXTURE_INTERVAL_MS) // FRAME_MS)
        pump.run(0.9, SPEECH_FRAMES)
        pump.run(0.1, HANGOVER_FRAMES)
        return pump

    on = second_cut_attempt_after(8_000)
    assert on.commits == 2, "exactly the interval has ELAPSED — the endpoint is not INSIDE"
    assert on.last_commit_ms == BASE_MS + 640 + HANGOVER_TRAIL_MS + 8_000
    assert second_cut_attempt_after(8_000 - FRAME_MS).commits == 1, "one frame earlier merges"


def test_the_cut_record_is_written_by_a_vad_cut_and_cleared_only_by_a_new_session():
    """`a_vad_cut_records_what_it_cut` (:1449), `a_merged_endpoint_is_not_a_cut` (:1462),
    `the_cut_record_survives_reset_and_is_cleared_only_by_a_new_session` (:1495),
    `onSessionStart_clears_the_cut_record` (:1509) — `EndpointCut(640, HANGOVER_TRAIL_MS, 0.1)`
    written at SileroEndpointer.kt:621 BEFORE `commitAt`, cleared at :420 and nowhere else."""
    from vadsim.machine import EndpointCut

    ep = endpointer()
    pump = Pump(ep)
    assert ep.last_cut is None, "nothing has been cut yet"
    pump.run(0.9, 20)
    assert pump.run(0.1, HANGOVER_FRAMES)
    expected = EndpointCut(speech_ms=640, trail_ms=HANGOVER_TRAIL_MS, prob=0.1)
    assert ep.last_cut == expected
    ep.reset()
    assert ep.last_cut == expected, "an external commit is not a VAD cut, and must not erase one"
    ep.on_session_start(now_ms=pump.t, min_commit_interval_ms=1_200)
    assert ep.last_cut is None

    ep2 = SileroEndpointerSim(T)
    ep2.on_session_start(now_ms=BASE_MS, min_commit_interval_ms=1_200)
    p2 = Pump(ep2)
    p2.run(0.9, 20)
    assert p2.run(0.1, HANGOVER_FRAMES)
    p2.run(0.9, SPEECH_FRAMES)
    assert not p2.run(0.1, HANGOVER_FRAMES), "merged"
    assert ep2.last_cut == expected, "the merge changed nothing about the last CUT"


# -- the service seam ---------------------------------------------------------------------

def test_a_hangover_elapsing_exactly_on_the_cap_frame_is_a_VAD_cut_not_a_cap_cut():
    """The `else if` at FloatingBubbleService.kt:2014 on the one frame the builder could not
    construct: the dip's 12th frame IS the first-cap frame (BASE+4000 = frame 125). The
    endpointer returns true, so the cap is never consulted, and the commit is a VAD cut with
    the endpointer's own record — not a cap cut with `retain`."""
    dip_start = 4_000 // FRAME_MS - (HANGOVER_FRAMES - 1)          # frame 114
    probs = [0.9] * dip_start + [0.1] * HANGOVER_FRAMES + [0.9] * 5
    r = simulate(probs, T)
    assert len(r.commits) == 1
    c = r.commits[0]
    assert (c.kind, c.t_ms - BASE_MS) == ("vad", 4_000)
    assert (c.speech_ms, c.trail_ms) == (dip_start * FRAME_MS, HANGOVER_TRAIL_MS)
    assert c.chunk_ms == 4_032 and c.retain_ms == 0 and c.cap_ms is None


def test_a_cap_firing_into_a_pause_kills_that_pauses_pending_end():
    """FloatingBubbleService.kt:2110 `endpointer.reset()` → `clearForNextSegment` → `closeGate`
    (SileroEndpointer.kt:367/:655/:636): `tempEndMs = 0`, `speaking = false`. The dip that
    was 96 ms old at the cap continues for 20 more frames and reaches an ANALYTIC age of
    736 ms, but every one of those frames takes `if (!speaking) return false` (:559). Exactly
    one commit — the cap — and no VAD cut at what would have been the dip's 12th frame."""
    speech = 122                                                   # 3 904 ms, gate open
    probs = [0.9] * speech + [0.1] * 24 + [0.9] * 40
    r = simulate(probs, T)
    assert [(c.kind, c.t_ms - BASE_MS) for c in r.commits] == [("cap", 4_000)]
    c = r.commits[0]
    # The dip was 3 frames old (96 ms <= MICRO_PAUSE_MS 98, strict `>`): NO offer, retain 0,
    # but `pendingSpeech` latched during the speech so the window is consumed.
    assert (c.cut_point_ms, c.retain_ms, c.consumed_window) == (NO_CUT_POINT, 0, True)
    assert c.chunk_ms == 4_032
    # The rest of the dip could not cut; the gate reopens on the speech after it.
    gate = gate_track(probs, T)
    assert gate[speech + 3] is True and gate[speech + 4] is False, "reset shut the gate"
    assert all(g is False for g in gate[speech + 4: speech + 24])
    assert gate[speech + 24 + 1] is True


def test_a_cap_firing_into_a_pause_older_than_MICRO_PAUSE_MS_cuts_at_that_pauses_start():
    """The same race with the dip 224 ms old at the cap: the offer stands (`>` 98 at :577),
    `capCutRetainMs(4000, 3776) = 224` (CommitCadencePolicy.kt:221-223), the committed chunk
    ends at the dip's first quiet frame and the 224 ms tail waits in the buffer."""
    speech = 118                                                   # dip starts BASE+3776
    probs = [0.9] * speech + [0.1] * 30 + [0.9] * 40
    r = simulate(probs, T)
    assert [(c.kind, c.t_ms - BASE_MS) for c in r.commits] == [("cap", 4_000)]
    c = r.commits[0]
    assert c.cut_point_ms == BASE_MS + speech * FRAME_MS
    assert c.retain_ms == 4_000 - speech * FRAME_MS == 224
    assert c.chunk_ms == 4_032 - 224
    assert c.consumed_window is True
    # tail = everything after the committed chunk: 188 frames of trace, buffer restarts at
    # cut + FRAME_MS (the retained audio begins at the dip's SECOND frame).
    assert r.tail_ms == len(probs) * FRAME_MS - (speech * FRAME_MS + FRAME_MS)


def test_the_retained_tail_lands_in_the_next_chunk_while_the_cap_anchor_does_not_move():
    """The asymmetry the builder flagged (report §5.1), pinned as the app has it:
    `SegmentCapPolicy.onCommit(now)` anchors on the CAP INSTANT (SegmentCapPolicy.kt:36-39)
    while `commitRetainingTailMs` leaves `retain` ms of audio behind (LocalWhisperEngine.kt
    :271-300). The next cap therefore fires 15 000 ms after the cap instant and its chunk
    carries 15 000 + 32 + retain ms of audio."""
    speech = 118
    probs = [0.9] * speech + [0.1] * 7 + [0.42] * 700                # dead band: no VAD exit
    r = simulate(probs, T)
    caps = r.of_kind("cap")
    assert [c.t_ms - BASE_MS for c in caps[:2]] == [4_000, 19_008]
    assert caps[0].retain_ms == 224
    assert caps[1].chunk_ms == 15_008 + 224, "the retained tail is in the NEXT chunk"
    assert caps[1].buffer_start_ms == BASE_MS + speech * FRAME_MS + FRAME_MS
    assert caps[1].retain_ms == 0 and caps[1].consumed_window is False


def test_merged_endpoints_make_the_cap_consume_its_window_and_a_stale_offer_retains_nothing():
    """A multi-floor (6 000) session: one free VAD cut, six endpoints 736 ms apart all merged
    (`hasPendingSpeech` stays TRUE through a merge — SileroEndpointer.kt:614 is `closeGate`
    only), then a dead-band bed to the cap. The cap consumes the window on `pendingSpeech`,
    counts the six merges, and the offer — the sixth merged dip's start, 10 944 ms old — is
    STALE (> 3 000, CommitCadencePolicy.kt:223) so it retains nothing."""
    t6 = Tuning(min_commit_interval_ms=6_000)
    probs = [0.9] * 20 + [0.1] * HANGOVER_FRAMES + ([0.9] * SPEECH_FRAMES + [0.1] * HANGOVER_FRAMES) * 6 + [0.42] * 600
    r = simulate(probs, t6)
    assert [(c.kind, c.t_ms - BASE_MS) for c in r.commits] == [("vad", 992), ("cap", 16_000)]
    cap = r.commits[1]
    assert cap.merged_endpoints_inside == 6 and r.merges_total == 6
    assert cap.consumed_window is True and cap.cap_ms == 15_000
    sixth_dip_start = 992 + 6 * FIXTURE_INTERVAL_MS - HANGOVER_TRAIL_MS
    assert cap.cut_point_ms - BASE_MS == sixth_dip_start == 5_056
    assert cap.retain_ms == 0, "16 000 - 5 056 = 10 944 > CAP_CUT_MAX_RETAIN_MS: stale"
    assert cap.chunk_ms == 15_008


def test_a_discard_and_a_cap_can_share_one_frame_and_the_event_track_records_both():
    """`onProb`'s discard is `closeGate(); return false` (SileroEndpointer.kt:592-593), so
    `onFrame` returns false and the `else if capExceeded` (FloatingBubbleService.kt:2014) runs
    on the SAME frame. A 288 ms burst whose dip's 12th frame is the cap frame: one discard, one
    cap commit, on frame 125."""
    from vadsim.machine import event_track

    burst_start = 4_000 // FRAME_MS - (HANGOVER_FRAMES - 1) - BURST_FRAMES   # frame 105
    probs = [0.01] * burst_start + [0.9] * BURST_FRAMES + [0.1] * HANGOVER_FRAMES + [0.9] * 5
    r = simulate(probs, T)
    assert [(c.kind, c.t_ms - BASE_MS) for c in r.commits] == [("cap", 4_000)]
    assert r.discards_total == 1
    gate, events = event_track(probs, T)
    assert events[4_000 // FRAME_MS] == "discard+cap"
    assert sum(1 for e in events if e) == 1
    # The cap consumed the window on the OFFER, not on pendingSpeech (288 ms never latched).
    assert r.commits[0].consumed_window is True
    assert r.commits[0].cut_point_ms == BASE_MS + (burst_start + BURST_FRAMES) * FRAME_MS
