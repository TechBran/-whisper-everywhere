"""A faithful offline port of Whisper Everywhere's streaming endpointer.

Two things are ported here, and they live in two different Kotlin files in the app:

  1. `SileroEndpointerSim` — `SileroEndpointer.onProb` and everything around it
     (`app/src/main/java/com/whispereverywhere/audio/SileroEndpointer.kt`).
     The Schmitt gate, the dead band, `tempEndMs`, the micro-pause memory `prevEndMs`,
     the MIN_SPEECH discard, and the cost governor's MERGE branch.

  2. `ServiceSim` — the cap/commit branch of `FloatingBubbleService.onAudioChunk`
     (`app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt:2008-2111`),
     plus `SegmentCapPolicy` and the two pure helpers it calls.
     **The cap fires in the SERVICE, not in the endpointer**, and on a cap cut the service
     calls `endpointer.reset()`.

Every branch below carries the Kotlin file:line it was ported from. The comparison
OPERATORS (`<` vs `<=`, `>` vs `>=`) and the ORDER of the micro-pause promotion versus the
hangover test are the two things this port has to get exactly right, and they are called
out where they happen.

CLOCK: ONE clock, `now_ms`, advancing FRAME_MS (32) per frame from a BASE — exactly as the
JVM fixtures drive it (`SileroEndpointerTest.Pump`, `BASE = 1_000_000`).
"""

from __future__ import annotations

from dataclasses import dataclass, field, replace
from typing import Callable, List, Optional, Sequence, Tuple

# ---------------------------------------------------------------------------------------
# The frame grid and the shipped tuning table.
# EndpointerTuning.kt — FRAME_SAMPLES:21, FRAME_BYTES:32, FRAME_MS:38, NO_VERDICT:49,
# ONSET_THRESHOLD:55, RELEASE_THRESHOLD:64, HANGOVER_MS:87, MIN_SPEECH_MS:152,
# MICRO_PAUSE_MS:166.
# ---------------------------------------------------------------------------------------

FRAME_SAMPLES = 512          # EndpointerTuning.kt:21
FRAME_BYTES = 1024           # EndpointerTuning.kt:32
FRAME_MS = 32                # EndpointerTuning.kt:38
NO_VERDICT = -1.0            # EndpointerTuning.kt:49

#: `SileroEndpointerTest.kt:34` — non-zero, because 0 is the "no micro-pause" sentinel.
BASE_MS = 1_000_000

#: `Endpointer.kt:92` — Endpointer.NO_CUT_POINT.
NO_CUT_POINT = 0


@dataclass(frozen=True)
class Tuning:
    """Every knob the simulator turns, defaulted to the SHIPPED 4.3.1 values."""

    onset: float = 0.50                    # EndpointerTuning.kt:55
    release: float = 0.35                  # EndpointerTuning.kt:64
    hangover_ms: int = 350                 # EndpointerTuning.kt:87  (retuned 500 -> 350 in 4.4)
    min_speech_ms: int = 300               # EndpointerTuning.kt:152
    micro_pause_ms: int = 98               # EndpointerTuning.kt:166
    # CommitCadencePolicy.kt:107 — npu-turbo, the owner ruling.
    min_commit_interval_ms: int = 2_000
    first_cap_ms: int = 4_000              # SegmentCapPolicy.kt:54
    cap_ms: int = 15_000                   # SegmentCapPolicy.kt:57
    cap_cut_max_retain_ms: int = 3_000     # CommitCadencePolicy.kt:146

    # The endpointer's pre-session floor. SileroEndpointer.kt:200 spells it as a literal
    # 8000 (Workstream C compiles without the service package); it only ever applies to a
    # frame that arrives before onSessionStart, which this simulator never produces because
    # `ServiceSim` always opens a session first. Kept so the value is not invented twice.
    pre_session_floor_ms: int = 8_000

    def hangover_frames(self) -> int:
        """`EndpointerGrid.HANGOVER_FRAMES` (EndpointerGrid.kt:61-63): the dip frame that CUTS.

        `ceil(HANGOVER_MS / FRAME_MS) + 1` — 12 at 350 ms, 17 at 500 ms. A dip's k-th frame
        has age `(k - 1) * FRAME_MS` (the pending end is stamped AT the first sub-RELEASE
        frame, SileroEndpointer.kt:565), and the guard is inclusive on the cutting side.
        """
        return (self.hangover_ms + FRAME_MS - 1) // FRAME_MS + 1

    def hold_frames(self) -> int:
        """`EndpointerGrid.HOLD_FRAMES` (EndpointerGrid.kt:72): longest dip that cannot cut."""
        return self.hangover_frames() - 1

    def hangover_trail_ms(self) -> int:
        """`EndpointerGrid.HANGOVER_TRAIL_MS` (EndpointerGrid.kt:81): 352 at 350, 512 at 500."""
        return self.hold_frames() * FRAME_MS

    def micro_pause_frames(self) -> int:
        """`EndpointerGrid.MICRO_PAUSE_FRAMES` (EndpointerGrid.kt:89-90): the 5th dip frame at 98."""
        return self.micro_pause_ms // FRAME_MS + 2

    def speech_frames_over_min(self) -> int:
        """`EndpointerGrid.SPEECH_FRAMES_OVER_MIN` (EndpointerGrid.kt:54-55): 11 at 300 ms."""
        return self.min_speech_ms // FRAME_MS + 2

    def fixture_interval_ms(self) -> int:
        """`EndpointerGrid.FIXTURE_INTERVAL_MS` (EndpointerGrid.kt:107-108): 736 at 350."""
        return (self.speech_frames_over_min() + self.hangover_frames()) * FRAME_MS


DEFAULT_TUNING = Tuning()


# ---------------------------------------------------------------------------------------
# The two pure service helpers.
# ---------------------------------------------------------------------------------------

def cap_cut_consumes_window(has_pending_speech: bool, is_cloud_session: bool) -> bool:
    """`FloatingBubbleService.kt:202-203` — SYMMETRIC, a plain OR. The three-way disjunct
    that feeds `has_pending_speech` lives at the CALL SITE (`:2069-2071`), not in here."""
    return has_pending_speech or is_cloud_session


def cap_cut_retain_ms(now_ms: int, cut_point_ms: int, max_retain_ms: int) -> int:
    """`CommitCadencePolicy.capCutRetainMs` (CommitCadencePolicy.kt:219-224).

    Returns 0 — "commit everything, exactly as 3.6.0 did" — for no offer, a future/equal
    offer, and a STALE offer (older than CAP_CUT_MAX_RETAIN_MS).
    """
    if cut_point_ms <= NO_CUT_POINT:                    # :220
        return 0
    retain = now_ms - cut_point_ms                      # :221
    if retain <= 0:                                     # :222
        return 0
    return 0 if retain > max_retain_ms else retain      # :223


# ---------------------------------------------------------------------------------------
# SegmentCapPolicy — SegmentCapPolicy.kt:22-58.
# ---------------------------------------------------------------------------------------

class SegmentCapPolicySim:
    """`SegmentCapPolicy` (SegmentCapPolicy.kt). The wall-clock cap for one session."""

    def __init__(self, first_segment_cap_ms: int, later_segment_cap_ms: int) -> None:
        self.first_segment_cap_ms = first_segment_cap_ms   # :23
        self.later_segment_cap_ms = later_segment_cap_ms   # :24
        self.anchor_ms = 0                                 # :26
        self.first_commit_done = False                     # :27

    def on_session_start(self, now_ms: int) -> None:       # :30-33
        self.anchor_ms = now_ms
        self.first_commit_done = False

    def on_commit(self, now_ms: int) -> None:              # :36-39
        self.anchor_ms = now_ms
        self.first_commit_done = True

    def current_cap_ms(self) -> int:                       # :42
        return self.later_segment_cap_ms if self.first_commit_done else self.first_segment_cap_ms

    def cap_exceeded(self, now_ms: int) -> bool:           # :45  — INCLUSIVE (`>=`)
        return now_ms - self.anchor_ms >= self.current_cap_ms()


# ---------------------------------------------------------------------------------------
# SileroEndpointer — SileroEndpointer.kt:91-666.
# ---------------------------------------------------------------------------------------

@dataclass(frozen=True)
class EndpointCut:
    """`EndpointCut` (SileroEndpointer.kt:689). What a VAD-decided commit actually cut."""

    speech_ms: int
    trail_ms: int
    prob: float


class SileroEndpointerSim:
    """The state machine, one frame at a time.

    NOT ported, deliberately: the accumulator (`fill`/`frame`, SileroEndpointer.kt:102/124),
    `timedProbe` and the slow-probe cutout latch (`:503-517`, `:220-222`), and `ProbeStats`.
    This simulator drives EXACTLY one 512-sample frame per call, so the accumulator is a
    no-op, and the cutout is a device-thermal effect with no offline analogue — it is
    modelled only as `probe_cutout=False` wherever the service reads it.
    """

    def __init__(self, tuning: Tuning, probe_reset: Optional[Callable[[], None]] = None) -> None:
        self.t = tuning
        self._probe_reset_cb = probe_reset

        self.last_frame_ms = 0            # :125
        self.speaking = False             # :128
        self.speech_start_ms = 0          # :131
        self.pending_speech = False       # :134
        self.temp_end_ms = 0              # :148  (bare 0L: private "not in a dip" bookkeeping)
        self.prev_end_ms = NO_CUT_POINT   # :169  (SENTINEL, not arithmetic zero)
        self.last_commit_ms = 0           # :189
        self.has_committed = False        # :191
        self.min_commit_interval_ms = tuning.pre_session_floor_ms   # :200
        self.last_cut: Optional[EndpointCut] = None                # :241

        # Instrumentation the Kotlin does not have (the release build strips the diag lines,
        # which is the whole reason this simulator exists).
        self.probe_resets = 0
        self.merges = 0
        self.discards = 0
        self.did_probe_reset = False      # set per frame, read by the coupled-probe driver

    # -- the three lifecycle entry points ------------------------------------------------

    def reset(self) -> None:
        """`SileroEndpointer.reset` (:364-368). An external commit: the cap cut, switchSource,
        stopRecording. Carries NO clock, so the governor re-anchors on the last frame seen."""
        self.last_commit_ms = self.last_frame_ms      # :365
        self.has_committed = True                     # :366
        self._clear_for_next_segment()                # :367

    def on_session_start(self, now_ms: int, min_commit_interval_ms: int) -> None:
        """`SileroEndpointer.onSessionStart` (:413-424). Order is load-bearing: every re-arm
        ABOVE `clearForNextSegment()`, which stays the last word."""
        self.min_commit_interval_ms = min_commit_interval_ms   # :414
        self.last_frame_ms = now_ms                            # :415
        self.last_commit_ms = now_ms                           # :416  ANCHORS, does not zero
        self.has_committed = False                             # :417
        # slowRun = 0 / probeCutout = false (:418-419) — not modelled, see the class docstring.
        self.last_cut = None                                   # :420
        self._clear_for_next_segment()                         # :423

    def on_frame(self, p: float, now_ms: int) -> bool:
        """`SileroEndpointer.onFrame` (:259-289), collapsed to one exact frame per call.

        `lastFrameMs = nowMs` is stamped BEFORE the probe runs (:267) — that is what makes a
        cap cut's `reset()` anchor the governor on THIS frame and not the previous one.
        """
        self.did_probe_reset = False
        self.last_frame_ms = now_ms          # :267
        return self._on_prob(p, now_ms)      # :286

    # -- the per-frame verdict -----------------------------------------------------------

    def _on_prob(self, p: float, now_ms: int) -> bool:
        """`SileroEndpointer.onProb` (:533-624). THE state machine."""
        # :534 — the guard is `p < 0f`, NOT an equality test against NO_VERDICT. Keeps the
        # previous state exactly: it can neither open nor close the gate, and it does not
        # stall the hangover (that clock is wall time from the pending end).
        if p < 0.0:
            return False

        # :539-546 — ONSET. A frame at or above ONSET clears the pending end (the HARD reset
        # that makes the hangover a TIMER, not a decay) and opens the gate if it is shut.
        # Comparison is `>=` (whisper.cpp:5536/:5544 `curr_prob >= threshold`).
        if p >= self.t.onset:
            self.temp_end_ms = 0                                       # :540
            if not self.speaking:                                      # :541
                self.speaking = True                                   # :542
                self.speech_start_ms = now_ms                          # :543
            # :545 — the pending-speech latch is INCLUSIVE (`>=`), deliberately asymmetric
            # with the strict commit gate at :584. Err toward "there IS speech".
            if now_ms - self.speech_start_ms >= self.t.min_speech_ms:
                self.pending_speech = True
            return False                                               # :546

        # :555 — THE DEAD BAND (RELEASE <= p < ONSET) is INERT. It writes no field at all:
        # not an onset, not a silence, and it does NOT clear the pending end, so the
        # hangover below counts straight THROUGH a mumble.
        if p >= self.t.release:
            return False

        # :559 — below RELEASE is SILENCE, and only after speech (`&& is_speech_segment`,
        # whisper.cpp:5575). With the gate shut there is no utterance to end.
        if not self.speaking:
            return False

        # :565 — the pending end is stamped ONCE, at the FIRST frame of the dip. Nothing in
        # this branch re-stamps it: hard timer, not decaying.
        if self.temp_end_ms == 0:
            self.temp_end_ms = now_ms

        # :577 — THE MICRO-PAUSE MEMORY. STRICT (`>`), as native is (whisper.cpp:5581).
        # *** ORDER IS LOAD-BEARING ***: this promotion sits ABOVE the hangover test, as
        # native does, so the frame that ends an utterance promotes BEFORE it decides —
        # which is what leaves a good boundary standing when the decision is a DISCARD.
        if now_ms - self.temp_end_ms > self.t.micro_pause_ms:
            self.prev_end_ms = self.temp_end_ms

        # :579 — the hangover. STRICT `<` on the continuing side, so exactly HANGOVER_MS of
        # silence CUTS (whisper.cpp:5586 continues while `< min_silence_samples`).
        if now_ms - self.temp_end_ms < self.t.hangover_ms:
            return False

        # :583 — the utterance is measured to the PENDING END, not to now: the hangover's own
        # trailing window is silence, not speech.
        speech_ms = self.temp_end_ms - self.speech_start_ms

        # :584 — the MIN_SPEECH discard. STRICT (`<=` here means "exactly MIN_SPEECH_MS is
        # discarded", matching native's strict `>` to keep, whisper.cpp:5590).
        # `pendingSpeech` is NOT cleared — the audio is still in the caller's buffer.
        if speech_ms <= self.t.min_speech_ms:
            self.discards += 1
            self._close_gate()                                         # :592
            return False                                               # :593

        # :596 — THE COST GOVERNOR. A real endpoint, but committing it now would outrun the
        # tier's measured per-commit cost. STRICT `<`: exactly the interval has ELAPSED and
        # commits; one millisecond more of floor merges the same endpoint
        # (`exactly_the_interval_commits_and_one_millisecond_more_merges`).
        # The session's FIRST cut is never merged — `hasCommitted` is a FLAG, not an
        # arithmetic test against `lastCommitMs` (:178-188).
        # Nothing writes `prevEndMs` here: the promotion at :577 has ALREADY written exactly
        # this value in this same call (:604-613).
        if self.has_committed and now_ms - self.last_commit_ms < self.min_commit_interval_ms:
            self.merges += 1
            self._close_gate()                                         # :614
            return False                                               # :615

        # :621 — record BEFORE the commit: `commitAt` runs `clearForNextSegment`, which
        # zeroes `tempEndMs` and `speechStartMs`.
        self.last_cut = EndpointCut(speech_ms=speech_ms, trail_ms=now_ms - self.temp_end_ms, prob=p)
        self._commit_at(now_ms)                                        # :622
        return True                                                    # :623

    # -- the three clears ----------------------------------------------------------------

    def _close_gate(self) -> None:
        """`closeGate` (:633-637). The utterance gate ONLY — the pending buffer's bookkeeping
        (`pendingSpeech`, `prevEndMs`) survives. The discard path comes through here."""
        self.speaking = False
        self.speech_start_ms = 0
        self.temp_end_ms = 0

    def _commit_at(self, now_ms: int) -> None:
        """`commitAt` (:648-652). A real endpoint, taken."""
        self.last_commit_ms = now_ms
        self.has_committed = True
        self._clear_for_next_segment()

    def _clear_for_next_segment(self) -> None:
        """`clearForNextSegment` (:654-665). Speaks for the BUFFER. Runs on the commit path
        and on reset/onSessionStart — NOT on the discard path, which is the deliberate
        divergence from whisper.cpp:5594 that keeps a 200 ms cough from erasing a good
        boundary (SileroEndpointer.kt:313-321, :657-661)."""
        self._close_gate()
        self.pending_speech = False
        self.prev_end_ms = NO_CUT_POINT      # SENTINEL, not arithmetic zero
        # fill = 0 — no accumulator here.
        self.probe_resets += 1
        self.did_probe_reset = True
        if self._probe_reset_cb is not None:
            self._probe_reset_cb()

    # -- the three predicates the service reads ------------------------------------------

    def has_pending_speech(self) -> bool:
        """`hasPendingSpeech` (:308). Describes the UNCOMMITTED BUFFER, not the gate."""
        return self.pending_speech

    def pending_cut_point_ms(self) -> int:
        """`pendingCutPointMs` (:327). The wall-clock ms at which the most recent qualifying
        micro-pause BEGAN, or NO_CUT_POINT."""
        return self.prev_end_ms

    def is_probe_cutout(self) -> bool:
        """`isProbeCutout` (:348). Always False offline — see the class docstring."""
        return False


# ---------------------------------------------------------------------------------------
# The service seam — FloatingBubbleService.onAudioChunk, cap/commit branch.
# ---------------------------------------------------------------------------------------

@dataclass
class Commit:
    """One commit the app would have made."""

    t_ms: int
    kind: str                       # 'vad' | 'cap'
    speech_ms: Optional[int]        # EndpointCut.speechMs — VAD commits only
    trail_ms: Optional[int]         # EndpointCut.trailMs  — VAD commits only
    chunk_ms: int                   # audio actually handed to the engine
    retain_ms: int                  # trailing ms held back for the NEXT chunk (cap cuts only)
    merged_endpoints_inside: int    # governor MERGE branch hits since the last commit
    discarded_bursts_inside: int    # MIN_SPEECH discards since the last commit
    frame_index: int
    buffer_start_ms: int            # where this chunk's audio begins
    cap_ms: Optional[int] = None    # the cap that fired (cap commits only)
    cut_point_ms: Optional[int] = None      # the micro-pause offer at cap time
    consumed_window: Optional[bool] = None  # capCutConsumesWindow's verdict
    prob: Optional[float] = None    # p of the frame that fired a VAD cut


@dataclass
class SimResult:
    commits: List[Commit] = field(default_factory=list)
    n_frames: int = 0
    base_ms: int = BASE_MS
    tuning: Tuning = DEFAULT_TUNING
    merges_total: int = 0
    discards_total: int = 0
    #: ms of audio still uncommitted when the trace ran out. The app's UNCONDITIONAL stop
    #: flush (FloatingBubbleService.kt:3059-3068) commits it; it is not a commit the tuning
    #: produced, so it is reported separately and excluded from the commit statistics.
    tail_ms: int = 0

    @property
    def wall_ms(self) -> int:
        return self.n_frames * FRAME_MS

    def of_kind(self, kind: str) -> List[Commit]:
        return [c for c in self.commits if c.kind == kind]


class ServiceSim:
    """`FloatingBubbleService.onAudioChunk`'s VAD/cap branch (:2008-2113).

    One `step()` is one captured chunk of exactly one frame. `sendAudio` is unconditional
    and first in the app (:1987); it has no offline analogue, so the only thing modelled
    here is the CUT.
    """

    def __init__(
        self,
        tuning: Tuning,
        *,
        session_open_ms: int,
        is_cloud_session: bool = False,
        probe_reset: Optional[Callable[[], None]] = None,
    ) -> None:
        self.t = tuning
        self.is_cloud_session = is_cloud_session
        self.endpointer = SileroEndpointerSim(tuning, probe_reset=probe_reset)
        self.cap = SegmentCapPolicySim(tuning.first_cap_ms, tuning.cap_ms)

        # onOpen (FloatingBubbleService.kt:2819-2831 and the onSessionStart below it).
        self.cap.on_session_start(session_open_ms)                       # :2820
        if is_cloud_session:
            # :2831 — the 4 s first cap is LOCAL-ONLY; a cloud session closes the window at
            # onOpen and keeps 15 s for every segment.
            self.cap.on_commit(session_open_ms)
        self.endpointer.on_session_start(
            now_ms=session_open_ms,
            min_commit_interval_ms=tuning.min_commit_interval_ms,
        )

        #: Where the engine's uncommitted buffer begins. Not a field in the app — the app's
        #: buffer is a byte array — but it is what turns a commit instant into a chunk LENGTH.
        self.buffer_start_ms = session_open_ms
        self._merges_at_last_commit = 0
        self._discards_at_last_commit = 0
        self.frame_index = -1

    def step(self, p: float, now_ms: int) -> Optional[Commit]:
        self.frame_index += 1
        ep = self.endpointer

        if ep.on_frame(p, now_ms):                       # :2010
            self.cap.on_commit(now_ms)                   # :2012
            cut = ep.last_cut
            return self._emit(
                t_ms=now_ms,
                kind="vad",
                retain_ms=0,
                speech_ms=None if cut is None else cut.speech_ms,
                trail_ms=None if cut is None else cut.trail_ms,
                prob=None if cut is None else cut.prob,
            )                                            # :2013 commitSegment(VAD)

        if self.cap.cap_exceeded(now_ms):                # :2014  — `else if`
            # :2018 — currentCapMs() is read BEFORE onCommit flips first->later.
            cap_ms = self.cap.current_cap_ms()

            # :2069-2071 — the THREE-way disjunct, at the call site. Both reads happen
            # BEFORE endpointer.reset() (:2110), which clears them.
            consumes = cap_cut_consumes_window(
                has_pending_speech=(
                    ep.has_pending_speech()
                    or ep.pending_cut_point_ms() > NO_CUT_POINT
                    or ep.is_probe_cutout()
                ),
                is_cloud_session=self.is_cloud_session,
            )
            if consumes:
                self.cap.on_commit(now_ms)               # :2075
            else:
                self.cap.on_session_start(now_ms)        # :2077

            cut_point = ep.pending_cut_point_ms()
            # :2090 — capCutRetainWindowMs(nowMs = now, endpointer = endpointer).
            retain = cap_cut_retain_ms(now_ms, cut_point, self.t.cap_cut_max_retain_ms)
            commit = self._emit(                          # :2103 commitSegment(CAP)
                t_ms=now_ms,
                kind="cap",
                retain_ms=retain,
                speech_ms=None,
                trail_ms=None,
                cap_ms=cap_ms,
                cut_point_ms=cut_point,
                consumed_window=consumes,
            )
            ep.reset()                                    # :2110
            return commit

        return None

    def _emit(
        self,
        *,
        t_ms: int,
        kind: str,
        retain_ms: int,
        speech_ms: Optional[int],
        trail_ms: Optional[int],
        cap_ms: Optional[int] = None,
        cut_point_ms: Optional[int] = None,
        consumed_window: Optional[bool] = None,
        prob: Optional[float] = None,
    ) -> Commit:
        ep = self.endpointer
        # The engine holds every ms since the buffer's start; a retained tail stays behind
        # for the NEXT chunk (LocalWhisperEngine.commitRetainingTailMs, :271-300). The frame
        # that fires the cut is itself in the buffer, hence the + FRAME_MS.
        window_ms = t_ms + FRAME_MS - self.buffer_start_ms
        chunk_ms = max(0, window_ms - retain_ms)
        commit = Commit(
            t_ms=t_ms,
            kind=kind,
            speech_ms=speech_ms,
            trail_ms=trail_ms,
            chunk_ms=chunk_ms,
            retain_ms=retain_ms,
            merged_endpoints_inside=ep.merges - self._merges_at_last_commit,
            discarded_bursts_inside=ep.discards - self._discards_at_last_commit,
            frame_index=self.frame_index,
            buffer_start_ms=self.buffer_start_ms,
            cap_ms=cap_ms,
            cut_point_ms=cut_point_ms,
            consumed_window=consumed_window,
            prob=prob,
        )
        self.buffer_start_ms = t_ms + FRAME_MS - retain_ms
        self._merges_at_last_commit = ep.merges
        self._discards_at_last_commit = ep.discards
        return commit


def simulate(
    probs: Sequence[float],
    tuning: Tuning = DEFAULT_TUNING,
    *,
    base_ms: int = BASE_MS,
    is_cloud_session: bool = False,
) -> SimResult:
    """Drive one p-trace through the endpointer + the service's cap branch.

    `probs[i]` is the probability of the frame stamped `base_ms + i * FRAME_MS`, exactly as
    `SileroEndpointerTest.Pump` drives it. The session opens at `base_ms` — i.e. the first
    frame arrives at the same instant the session anchor is stamped, which is the
    conservative direction (the cap clock starts no later than the audio).
    """
    sim = ServiceSim(tuning, session_open_ms=base_ms, is_cloud_session=is_cloud_session)
    result = SimResult(n_frames=len(probs), base_ms=base_ms, tuning=tuning)
    for i, p in enumerate(probs):
        commit = sim.step(float(p), base_ms + i * FRAME_MS)
        if commit is not None:
            result.commits.append(commit)
    result.merges_total = sim.endpointer.merges
    result.discards_total = sim.endpointer.discards
    end_ms = base_ms + len(probs) * FRAME_MS
    result.tail_ms = max(0, end_ms - sim.buffer_start_ms)
    return result


#: Per-frame outcome codes `event_track` emits. A VAD cut and a cap cut are an `if` /
#: `else if` (FloatingBubbleService.kt:2010/:2014) and never share a frame; but a merge or a
#: discard is an early `return false` out of `onProb` (SileroEndpointer.kt:593/:615) that the
#: cap check then follows on the SAME frame, so `"discard+cap"` and `"merge+cap"` are legal
#: values — the endpointer judged the pause AND the wall clock ran out on one frame.
EVENT_NONE = ""
EVENT_VAD = "vad"
EVENT_CAP = "cap"
EVENT_MERGE = "merge"
EVENT_DISCARD = "discard"


def event_track(
    probs: Sequence[float],
    tuning: Tuning = DEFAULT_TUNING,
    *,
    base_ms: int = BASE_MS,
    is_cloud_session: bool = False,
) -> Tuple[List[bool], List[str]]:
    """The machine's own account of every frame: `(gate_before, event)` per frame.

    `gate_before[i]` is `speaking` as it stands BEFORE frame `i` is processed.
    `event[i]` is what frame `i` DID: `'vad'`, `'cap'`, `'merge'`, `'discard'`, or `''`.

    `analyze.find_dips` needs both and can derive neither from the trace alone:
      * a dip only stamps a pending end when the gate is already open (`onProb`'s
        `if (!speaking) return false`, SileroEndpointer.kt:559), so leading silence, and every
        dip after a discard, is structurally uncuttable no matter how long it is;
      * a cap cut landing INSIDE a dip runs `endpointer.reset()` (FloatingBubbleService.kt:2110),
        which zeroes `tempEndMs` and shuts the gate, so the rest of that dip can never reach
        the hangover — the age arithmetic on the raw trace says it does. Without the events
        the report calls that pause "cuttable, INVESTIGATE" when the honest answer is "the cap
        fired first".

    The gate depends on the commits, and the commits depend on the cap, so this runs the
    whole seam rather than the endpointer alone.
    """
    sim = ServiceSim(tuning, session_open_ms=base_ms, is_cloud_session=is_cloud_session)
    ep = sim.endpointer
    gate: List[bool] = []
    events: List[str] = []
    for i, p in enumerate(probs):
        gate.append(ep.speaking)
        merges0, discards0 = ep.merges, ep.discards
        commit = sim.step(float(p), base_ms + i * FRAME_MS)
        parts: List[str] = []
        if ep.merges > merges0:
            parts.append(EVENT_MERGE)
        if ep.discards > discards0:
            parts.append(EVENT_DISCARD)
        if commit is not None:
            parts.append(commit.kind)                  # 'vad' | 'cap', always last
        events.append("+".join(parts))
    return gate, events


def gate_track(
    probs: Sequence[float],
    tuning: Tuning = DEFAULT_TUNING,
    *,
    base_ms: int = BASE_MS,
    is_cloud_session: bool = False,
) -> List[bool]:
    """`speaking` as it stands BEFORE each frame is processed — `event_track`'s first half."""
    return event_track(probs, tuning, base_ms=base_ms, is_cloud_session=is_cloud_session)[0]


def simulate_coupled(
    frames,
    probe,
    tuning: Tuning = DEFAULT_TUNING,
    *,
    base_ms: int = BASE_MS,
    is_cloud_session: bool = False,
):
    """The HONEST simulation: the probe's LSTM state is reset on every commit, as the app
    does (`probeReset` fires from `clearForNextSegment`, SileroEndpointer.kt:664).

    `simulate()` above runs one FIXED trace through every tuning, which is what makes the
    sweep comparable — but it is not what the app produces, because the commit pattern feeds
    back into the probabilities. Use this to measure how large that feedback is.

    Returns `(SimResult, probs)` where `probs` is the trace this run actually saw.
    """
    sim = ServiceSim(
        tuning,
        session_open_ms=base_ms,
        is_cloud_session=is_cloud_session,
        probe_reset=probe.reset,
    )
    probs: List[float] = []
    result = SimResult(base_ms=base_ms, tuning=tuning)
    for i, frame in enumerate(frames):
        p = probe(frame)
        probs.append(p)
        commit = sim.step(p, base_ms + i * FRAME_MS)
        if commit is not None:
            result.commits.append(commit)
    result.n_frames = len(probs)
    result.merges_total = sim.endpointer.merges
    result.discards_total = sim.endpointer.discards
    end_ms = base_ms + len(probs) * FRAME_MS
    result.tail_ms = max(0, end_ms - sim.buffer_start_ms)
    return result, probs


def with_overrides(tuning: Tuning, **kw) -> Tuning:
    return replace(tuning, **{k: v for k, v in kw.items() if v is not None})
