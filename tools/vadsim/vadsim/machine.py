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

    # ----------------------------------------------------------------------------------
    # THE FLATLINE TRIGGER — A PROPOSAL, NOT SHIPPED BEHAVIOUR. Default OFF, and with
    # `flatline_enabled = False` this whole mechanism is dead code: `_on_flat` returns on
    # its first line, so the DEFAULT simulation is behaviour-identical to the committed
    # 50d7466. Nothing in the app implements it; it exists here to be MEASURED.
    #
    # WHY: `SileroEndpointer.onFrame(chunk, amp, nowMs)` takes the chunk's RMS and
    # IGNORES it ("@param amp the chunk's RMS, ignored here", SileroEndpointer.kt:245),
    # so the endpointer decides on Silero's `p` alone and needs 12 consecutive
    # below-RELEASE frames (352 ms, EndpointerTuning.kt:87) to cut. On EDITED video the
    # editor leaves 100-300 ms of near-digital silence at a sentence boundary — the
    # waveform visibly flatlines — which is far too short for the hangover. Natural
    # speech never reaches digital zero (room tone 50-300 RMS), so a trigger on
    # "chunk-RMS near zero, HELD briefly" can only fire on gated/edited audio, and there
    # only at the edit points.
    # ----------------------------------------------------------------------------------

    #: OFF unless a caller says otherwise. This is the whole de-risking argument.
    flatline_enabled: bool = False

    #: The RMS floor, in AudioMath's own 0..32767 units (`AudioMath.amplitude`,
    #: AudioMath.kt:21-37; the truncate-and-clamp is `:36`). A frame whose CHUNK RMS is
    #: strictly below this is "flat".
    #:
    #: THE DEFAULT IS THE SHIPPED FLOOR, EXPRESSED IN THIS PREDICATE. The Kotlin's
    #: `EndpointerTuning.FLATLINE_RMS_MAX = 10` (EndpointerTuning.kt:190) is INCLUSIVE —
    #: `amp <= 10` is flat (`SileroEndpointer.kt:767` resets on `amp > MAX`) — so its exact
    #: twin under this strict `<` is 11: `rms < 11` and `amp <= 10` accept the same integers.
    #: A bare vadsim run therefore simulates what ships. 40 was the pre-decision sweep
    #: default and is no longer anyone's answer; the sweep axis still measures it
    #: (`analyze.FLAT_SWEEP_RMS`).
    flatline_rms: int = 11

    #: How long the flat run must be held before it closes an utterance. Measured
    #: EXACTLY as the hangover measures a dip: `nowMs - flatRunStartMs >= hold`, where
    #: the run start is stamped at its FIRST flat frame. So `hold = 128` fires on the
    #: run's FIFTH frame (ages 0, 32, 64, 96, 128) — 160 ms of audio.
    flatline_hold_ms: int = 128

    #: The CAPTURE chunk length the RMS is computed over — a property of the audio
    #: source, not of the endpointer, and 32 ms on BOTH of the app's paths:
    #: `StreamingAudioRecorder.kt:80` reads into a 1024-byte buffer (512 samples) and
    #: computes one `amp` over it at `:87`; `PlaybackAudioCapturer.kt:64` reads 1024 bytes
    #: at 16 kHz or 3072 at 48 kHz (which the 3:1 decimator turns into 1024) and computes
    #: one `amp` over the DECIMATED buffer at `:81`. It lives here because it is what
    #: makes `flatline_hold_ms` round: the same RMS applies to every frame of a chunk, so
    #: the hold can only ever be satisfied in whole chunks.
    chunk_ms: int = 32

    def __post_init__(self) -> None:
        if not (0 <= self.flatline_rms <= 32_767):
            raise ValueError(
                f"flatline_rms={self.flatline_rms} outside AudioMath's 0..32767 range "
                "(AudioMath.kt:36 coerces the result into exactly that band)"
            )
        if self.flatline_hold_ms < 0:
            raise ValueError(f"flatline_hold_ms={self.flatline_hold_ms} is negative")
        if self.chunk_ms < 1:
            raise ValueError(f"chunk_ms={self.chunk_ms} must be at least 1 ms")

    def flatline_frames(self) -> int:
        """Flat frames the run needs, at the 32 ms frame grid: `hold // 32 + 1`.

        The k-th flat frame has age `(k - 1) * FRAME_MS`, so the first frame whose age
        reaches `hold` is number `ceil(hold / 32) + 1`: 4 at 96, 5 at 128, 6 at 160,
        8 at 224, 11 at 320 — the same arithmetic `hangover_frames()` uses, because the
        flat close is deliberately the same shape of test as the hangover close.
        """
        return (self.flatline_hold_ms + FRAME_MS - 1) // FRAME_MS + 1

    def flatline_hold_chunks(self) -> int:
        """The hold in whole chunk INTERVALS: `ceil(hold / chunk_ms)`.

        This is the AGE the run must reach, in chunks — NOT the number of flat chunks that
        fire the trigger, which is one more (`flatline_fire_chunks`). The run's first flat
        chunk is stamped at age 0, so `k` intervals of `chunk_ms` are only ever measured on
        the `(k + 1)`-th chunk. One RMS per chunk means the age only ever takes the values
        `0, chunk_ms, 2 * chunk_ms, ...`, so a hold of 128 ms and a hold of 100 ms are the
        same rule at the 32 ms chunk the app ships.
        """
        return (self.flatline_hold_ms + self.chunk_ms - 1) // self.chunk_ms

    def flatline_effective_hold_ms(self) -> int:
        """The AGE at which the run fires: `flatline_hold_chunks() * chunk_ms`.

        At `chunk_ms = 32` a hold of 100, 110 or 128 all resolve to 128 ms of age, so two
        values inside one chunk cannot be told apart. Quote this number, not the knob, when
        comparing two holds — and quote `flatline_gap_aligned_ms()` /
        `flatline_gap_any_ms()` when asking what GAP a hold catches, because the age is
        reached on the FIFTH flat chunk at 128, not the fourth.
        """
        return self.flatline_hold_chunks() * self.chunk_ms

    def flatline_fire_chunks(self) -> int:
        """Consecutive FLAT CHUNKS the trigger needs before it fires: `hold_chunks + 1`.

        The chunk the run starts on is age 0; the chunk that first reaches `hold` is the
        `(ceil(hold / chunk_ms) + 1)`-th flat chunk in a row. 5 at (128, 32), 4 at (96, 32),
        6 at (160, 32), 8 at (224, 32), 11 at (320, 32) — and at a hypothetical 128 ms
        chunk a hold of 96 OR 128 both need TWO flat chunks (256 ms of flat audio), because
        the app stamps ONE `nowMs` per chunk (`FloatingBubbleService.kt:2009`) and the
        age therefore jumps straight from 0 to 128.

        Equal to `flatline_frames()` at the shipped `chunk_ms = 32`. This is the number a
        Kotlin port should COUNT rather than re-deriving a wall-clock hold: the phone's
        chunk timestamps are `System.currentTimeMillis()` at delivery, which is bursty, so
        `nowMs - flatRunStartMs >= hold` with `hold` an exact multiple of 32 sits on a band
        edge and fires on the 4th or the 6th flat chunk as often as on the 5th. A count of
        chunks is deterministic on the device in a way a wall-clock hold is not.
        """
        return self.flatline_hold_chunks() + 1

    def flatline_gap_aligned_ms(self) -> int:
        """The SHORTEST gap of digital silence this hold can cut — IF the gap happens to
        start exactly on a chunk boundary: `flatline_fire_chunks() * chunk_ms`.

        160 ms at (128, 32). Every synthetic fixture in `tests/test_flatline.py` and the
        chunk-gated `jfk-gated.wav` demonstration are aligned by construction, so this is
        the number those runs exhibit. A real editor's gate is not aligned to the capture
        chunk grid — see `flatline_gap_any_ms`.
        """
        return self.flatline_fire_chunks() * self.chunk_ms

    def flatline_gap_any_ms(self) -> int:
        """The shortest gap GUARANTEED to be cut at ANY alignment against the chunk grid:
        `(flatline_fire_chunks() + 1) * chunk_ms`.

        A gap of `G` ms starting at an arbitrary phase within a chunk contains only
        `floor(G / chunk) - 1` WHOLE chunks in the general case (its first and last chunks
        each straddle speech, and one millisecond of speech at 3 000 RMS in a 32 ms chunk
        already reads over 500 — see `test_flatline_verify`). So a hold that needs `k`
        flat chunks needs a gap of `(k + 1) * chunk_ms` to be certain: **192 ms at
        (128, 32)**, 160 at (96, 32), 224 at (160, 32), 288 at (224, 32), 384 at
        (320, 32). Against the brief's "an editor leaves 100-300 ms", a 128 ms hold is
        certain only for the upper half of that band, and a 96 ms hold for gaps of 160 and
        up. This number, not the knob, is what a hold buys.
        """
        return (self.flatline_fire_chunks() + 1) * self.chunk_ms

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
    #: NOT in the Kotlin record. The chunk RMS of the frame that fired the cut, present
    #: only for a `flat` cut (the proposal below), so the report can show the amplitude
    #: the trigger acted on beside the `p` Silero would have kept the gate open with.
    rms: Optional[int] = None
    #: `'vad'` (Silero's own hangover) or `'flat'` (the flatline proposal).
    kind: str = "vad"


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

        # THE FLATLINE TRIGGER's own bookkeeping (the PROPOSAL — see `Tuning`). Both
        # fields are dip bookkeeping, exactly like `temp_end_ms`, and die with the gate.
        # With the trigger disabled they are written by `_close_gate` and read nowhere.
        self.flat_run_start_ms = 0
        self.flat_run_frames = 0

        # Instrumentation the Kotlin does not have (the release build strips the diag lines,
        # which is the whole reason this simulator exists).
        self.probe_resets = 0
        self.merges = 0
        self.discards = 0
        self.did_probe_reset = False      # set per frame, read by the coupled-probe driver
        #: which mechanism fired the most recent commit: 'vad' or 'flat'
        self.last_fire_kind: Optional[str] = None
        self.flat_commits = 0
        self.flat_merges = 0
        self.flat_discards = 0

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

    def on_frame(self, p: float, now_ms: int, rms: Optional[int] = None) -> bool:
        """`SileroEndpointer.onFrame` (:259-289), collapsed to one exact frame per call.

        `lastFrameMs = nowMs` is stamped BEFORE the probe runs (:267) — that is what makes a
        cap cut's `reset()` anchor the governor on THIS frame and not the previous one.

        `rms` is the CHUNK's RMS amplitude — the `amp` argument the app already passes and
        `SileroEndpointer.kt:245` documents as "ignored here". It is ignored here too unless
        `Tuning.flatline_enabled`, and it is the CHUNK's value, not the frame's: one
        `AudioMath.amplitude` per capture buffer (`StreamingAudioRecorder.kt:87`,
        `PlaybackAudioCapturer.kt:81`), applied to every frame that buffer completes
        (`onFrame` splits the chunk into 512-sample frames internally, `:269-287`).
        `None` means "no RMS for this frame" and is treated as NOT flat — see `_on_flat`.
        """
        self.did_probe_reset = False
        self.last_frame_ms = now_ms          # :267
        if self._on_prob(p, now_ms):         # :286
            self.last_fire_kind = "vad"
            return True
        # SILERO WINS, ALWAYS. The flat trigger is evaluated only after `onProb` has
        # declined this frame, so a hangover close and a flat hold that come due on the
        # SAME frame produce exactly ONE commit and it is Silero's — and the flat run is
        # then cleared by `_commit_at` -> `_clear_for_next_segment` -> `_close_gate`.
        # Structurally the same precedence as the service's own `if` / `else if` between
        # the VAD cut and the wall cap (FloatingBubbleService.kt:2010/:2014).
        return self._on_flat(p, rms, now_ms)

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

    # -- THE FLATLINE TRIGGER (proposal, default OFF) -------------------------------------

    def _on_flat(self, p: float, rms: Optional[int], now_ms: int) -> bool:
        """The PROPOSED trigger: "chunk RMS near zero, held briefly" closes the utterance.

        NOTHING IN THE APP DOES THIS. It is a proposal to be measured, and every semantic
        below is a DESIGN DECISION rather than a port of an existing branch. Each is
        numbered here and repeated in the report:

        1. **OFF BY DEFAULT.** The first line returns unless `flatline_enabled`, so the
           default simulation is behaviour-identical to the committed 50d7466. There is no
           other guard anywhere: the trigger cannot leak into a default run.
        2. **AFTER SILERO, NEVER INSTEAD OF IT.** `on_frame` calls this only once `onProb`
           has returned False for this frame, so Silero's hangover always wins a tie and a
           frame can produce at most one commit. It also means a frame that MERGED or
           DISCARDED in `onProb` arrives here with the gate already shut (`closeGate`,
           :633) and therefore cannot also fire a flat cut — one verdict per pause.
        3. **ONLY WHILE SPEAKING.** With the gate shut there is no utterance to end — the
           same reason `onProb` returns at `:559`. The run counter is CLEARED while the
           gate is shut rather than merely blocked from firing: counting through leading
           silence would let the first word after a digitally silent lead-in fire a cut
           whose `speechMs` is negative, which the MIN_SPEECH test would then "discard" —
           a real word thrown away by bookkeeping. A run therefore always starts at or
           after the gate opened, so `speechMs >= 0` by construction.
        4. **CONSECUTIVE, AND PURELY AMPLITUDE-DRIVEN.** Any frame whose chunk RMS is at
           or above the floor resets the count to zero; `None` (no RMS available for this
           frame) counts as at-or-above, because an unknown amplitude must not be able to
           fire a cut. A frame at or above ONSET does NOT reset it: the trigger's whole
           premise is that Silero's `p` is the thing that fails to see an editor's cut, so
           letting `p` veto the count would restore exactly the blindness it exists to
           work around. That is also precisely why the sweep carries a MID-WORD RISK
           column — this decision is the one that makes such a cut possible, and the
           column is how the owner sees its cost before shipping any value.
        5. **THE HOLD IS MEASURED LIKE THE HANGOVER.** `nowMs - flatRunStartMs >= hold`,
           with the run start stamped at the run's FIRST flat frame — the same hard-timer
           shape as `nowMs - tempEndMs >= HANGOVER_MS` at `:579`, and inclusive on the
           firing side for the same reason. Because ONE RMS covers a whole chunk, the run
           length is only ever a whole number of chunks: the hold ROUNDS UP to
           `Tuning.flatline_effective_hold_ms()` and two holds inside one chunk cannot be
           told apart (32 ms chunks on both capture paths). The age is reached on the
           `Tuning.flatline_fire_chunks()`-th flat chunk — FIVE at 128 — and the gap that
           reliably supplies five whole flat chunks is `flatline_gap_any_ms()` = 192 ms,
           not 128 and not 160 (see those docstrings). THIS SIMULATOR'S CLOCK IS AN EXACT
           32 ms GRID; the phone's is `System.currentTimeMillis()` per chunk, so on the
           device a wall-clock hold that is an exact multiple of 32 fires one chunk early
           or late as often as on time. A port should count chunks.
        6. **THE FIRING FRAME BEHAVES EXACTLY LIKE A HANGOVER CLOSE.** The pending end is
           whatever `tempEndMs` holds when the hold is reached: **Silero's own stamp if it
           has one — whether that stamp is EARLIER than the run's first flat frame (room
           tone, then digital zero) or LATER (a dead-band frame or two of LSTM inertia
           before `p` dropped below RELEASE)** — and the run's first flat frame only when
           `tempEndMs` is 0. That is the Kotlin-compatible rule (`tempEndMs` is "stamped
           ONCE per dip", SileroEndpointer.kt:137-141; nothing here moves an existing
           stamp), and it is what shares `speechMs`, `trailMs` and the `prevEndMs`
           promotion with the Silero path instead of inventing a second set of
           bookkeeping. The consequence, pinned by `test_flatline_verify`: with a later
           Silero stamp `speechMs` INCLUDES the flat frames before it and `trailMs` is
           SHORTER than the hold; with an earlier one `trailMs` is LONGER. Measured on
           chunk-gated real audio (`jfk-gated.wav`) the two coincide — Silero's `p` falls
           under RELEASE on the very first digitally-silent frame (max `p` on any zero
           frame 0.075), so `tempEndMs` is already the run's first frame when the hold
           comes due. Then: the same MIN_SPEECH discard (`closeGate`, buffer untouched),
           the same governor merge (`closeGate`, `pendingSpeech` kept), the same
           `commitAt` on success. Only `EndpointCut.kind` differs — `'flat'`.
        7. **A RUN THAT ENDS EARLY DISTURBS NOTHING.** The counter is the only state a
           non-firing flat frame touches; `tempEndMs` is written at FIRE time and only when
           it is unset, so a flat run that dies before the hold cannot move a pending end
           Silero itself had stamped, and cannot shorten or lengthen Silero's hangover.
        8. **NO MICRO-PAUSE PROMOTION OF ITS OWN.** The promotion at `:577` runs only on
           sub-RELEASE frames, and this frame need not be one, so the flat path does not
           write `prevEndMs`. A flat close that MERGES therefore leaves the wall cap
           whatever offer Silero's own frames had already promoted — nothing more. (The
           alternative, promoting on the flat frame for symmetry with `:577`, is listed as
           an open question in the report: it would change what a cap cut retains, in flat
           mode only.)
        """
        if not self.t.flatline_enabled:                # DECISION 1
            return False

        if not self.speaking:                          # DECISION 3
            self._clear_flat_run()
            return False

        # DECISION 4. STRICT `<` (spelled as its negation here), and an unknown RMS counts
        # as at-or-above. The Kotlin writes the same predicate INCLUSIVE —
        # `if (amp > EndpointerTuning.FLATLINE_RMS_MAX)` at `SileroEndpointer.kt:767` — which
        # is why the shipped floor of 10 is `flatline_rms = 11` here: the two accept the same
        # integers, and the default above is set to the twin so a bare run simulates the app.
        if rms is None or rms >= self.t.flatline_rms:
            self._clear_flat_run()
            return False

        if self.flat_run_frames == 0:                   # DECISION 5 — stamped ONCE
            self.flat_run_start_ms = now_ms
        self.flat_run_frames += 1
        if now_ms - self.flat_run_start_ms < self.t.flatline_hold_ms:
            return False

        # ---- the flat close, from here identical in shape to onProb's :583-623 ----
        if self.temp_end_ms == 0:                       # DECISION 6/7
            self.temp_end_ms = self.flat_run_start_ms
        speech_ms = self.temp_end_ms - self.speech_start_ms

        if speech_ms <= self.t.min_speech_ms:            # :584 — the same discard
            self.discards += 1
            self.flat_discards += 1
            self._close_gate()
            return False

        if self.has_committed and now_ms - self.last_commit_ms < self.min_commit_interval_ms:
            self.merges += 1                             # :596 — the same governor merge
            self.flat_merges += 1
            self._close_gate()
            return False

        self.last_cut = EndpointCut(                     # :621 — recorded BEFORE the commit
            speech_ms=speech_ms,
            trail_ms=now_ms - self.temp_end_ms,
            prob=p,
            rms=rms,
            kind="flat",
        )
        self.flat_commits += 1
        self.last_fire_kind = "flat"
        self._commit_at(now_ms)
        return True

    def _clear_flat_run(self) -> None:
        self.flat_run_start_ms = 0
        self.flat_run_frames = 0

    # -- the three clears ----------------------------------------------------------------

    def _close_gate(self) -> None:
        """`closeGate` (:633-637). The utterance gate ONLY — the pending buffer's bookkeeping
        (`pendingSpeech`, `prevEndMs`) survives. The discard path comes through here."""
        self.speaking = False
        self.speech_start_ms = 0
        self.temp_end_ms = 0
        # The flat run is GATE state, and it dies with the gate for the same reason
        # `tempEndMs` does: a run measured across a closed gate would fire on the first
        # flat frame after the next onset (DECISION 3/7). Not in the Kotlin — nothing in
        # the app has a flat run yet — and inert while the trigger is disabled.
        self._clear_flat_run()

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
    kind: str                       # 'vad' | 'cap' | 'flat' (the proposal)
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
    prob: Optional[float] = None    # p of the frame that fired a VAD or FLAT cut
    #: chunk RMS of the frame that fired a FLAT cut (None otherwise) — the amplitude the
    #: proposal acted on, beside the `prob` Silero would have held the gate open with.
    rms: Optional[int] = None


@dataclass
class SimResult:
    commits: List[Commit] = field(default_factory=list)
    n_frames: int = 0
    base_ms: int = BASE_MS
    tuning: Tuning = DEFAULT_TUNING
    merges_total: int = 0
    discards_total: int = 0
    #: of `merges_total` / `discards_total`, the ones the FLAT close produced
    flat_merges_total: int = 0
    flat_discards_total: int = 0
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

    def step(self, p: float, now_ms: int, rms: Optional[int] = None) -> Optional[Commit]:
        self.frame_index += 1
        ep = self.endpointer

        if ep.on_frame(p, now_ms, rms):                  # :2010
            self.cap.on_commit(now_ms)                   # :2012
            cut = ep.last_cut
            # The app's `onFrame` returns a bare Boolean and the service commits the same
            # way whatever fired it (`commitSegment(engine, EndpointDiag.VAD)`, :2013) —
            # `kind` is SIMULATOR instrumentation, so a flat cut can be counted separately
            # in the sweep. A flat cut IS a VAD-path commit as far as the service is
            # concerned: same `SegmentCapPolicy.onCommit`, same retain-nothing.
            return self._emit(
                t_ms=now_ms,
                kind=ep.last_fire_kind or "vad",
                retain_ms=0,
                speech_ms=None if cut is None else cut.speech_ms,
                trail_ms=None if cut is None else cut.trail_ms,
                prob=None if cut is None else cut.prob,
                rms=None if cut is None else cut.rms,
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
        rms: Optional[int] = None,
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
            rms=rms,
        )
        self.buffer_start_ms = t_ms + FRAME_MS - retain_ms
        self._merges_at_last_commit = ep.merges
        self._discards_at_last_commit = ep.discards
        return commit


def rms_at(rms: Optional[Sequence[Optional[int]]], i: int) -> Optional[int]:
    """Frame `i`'s chunk RMS, or None when no RMS trace was supplied (or it is short).

    None is NOT zero: `_on_flat` treats an unknown amplitude as not-flat (DECISION 4), so
    a p-trace with no RMS beside it can never fire a flat cut even with the trigger on.
    """
    if rms is None or i >= len(rms):
        return None
    v = rms[i]
    return None if v is None else int(v)


def simulate(
    probs: Sequence[float],
    tuning: Tuning = DEFAULT_TUNING,
    *,
    base_ms: int = BASE_MS,
    is_cloud_session: bool = False,
    rms: Optional[Sequence[Optional[int]]] = None,
) -> SimResult:
    """Drive one p-trace through the endpointer + the service's cap branch.

    `probs[i]` is the probability of the frame stamped `base_ms + i * FRAME_MS`, exactly as
    `SileroEndpointerTest.Pump` drives it. The session opens at `base_ms` — i.e. the first
    frame arrives at the same instant the session anchor is stamped, which is the
    conservative direction (the cap clock starts no later than the audio).

    `rms[i]` is frame `i`'s CHUNK RMS (see `on_frame`). Omit it and the flatline proposal
    can never fire, whatever `Tuning.flatline_enabled` says.
    """
    sim = ServiceSim(tuning, session_open_ms=base_ms, is_cloud_session=is_cloud_session)
    result = SimResult(n_frames=len(probs), base_ms=base_ms, tuning=tuning)
    for i, p in enumerate(probs):
        commit = sim.step(float(p), base_ms + i * FRAME_MS, rms_at(rms, i))
        if commit is not None:
            result.commits.append(commit)
    result.merges_total = sim.endpointer.merges
    result.discards_total = sim.endpointer.discards
    result.flat_merges_total = sim.endpointer.flat_merges
    result.flat_discards_total = sim.endpointer.flat_discards
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
#: the flatline PROPOSAL's commit. A `'merge'` or `'discard'` the flat close produced is
#: reported under those same two words: they are the same two branches, reached from the
#: other side, and the per-run totals (`SimResult.flat_merges_total`) keep the split.
EVENT_FLAT = "flat"


def event_track(
    probs: Sequence[float],
    tuning: Tuning = DEFAULT_TUNING,
    *,
    base_ms: int = BASE_MS,
    is_cloud_session: bool = False,
    rms: Optional[Sequence[Optional[int]]] = None,
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
        commit = sim.step(float(p), base_ms + i * FRAME_MS, rms_at(rms, i))
        parts: List[str] = []
        if ep.merges > merges0:
            parts.append(EVENT_MERGE)
        if ep.discards > discards0:
            parts.append(EVENT_DISCARD)
        if commit is not None:
            parts.append(commit.kind)                  # 'vad' | 'cap' | 'flat', always last
        events.append("+".join(parts))
    return gate, events


def gate_track(
    probs: Sequence[float],
    tuning: Tuning = DEFAULT_TUNING,
    *,
    base_ms: int = BASE_MS,
    is_cloud_session: bool = False,
    rms: Optional[Sequence[Optional[int]]] = None,
) -> List[bool]:
    """`speaking` as it stands BEFORE each frame is processed — `event_track`'s first half."""
    return event_track(
        probs, tuning, base_ms=base_ms, is_cloud_session=is_cloud_session, rms=rms
    )[0]


def simulate_coupled(
    frames,
    probe,
    tuning: Tuning = DEFAULT_TUNING,
    *,
    base_ms: int = BASE_MS,
    is_cloud_session: bool = False,
    rms: Optional[Sequence[Optional[int]]] = None,
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
        commit = sim.step(p, base_ms + i * FRAME_MS, rms_at(rms, i))
        if commit is not None:
            result.commits.append(commit)
    result.n_frames = len(probs)
    result.merges_total = sim.endpointer.merges
    result.discards_total = sim.endpointer.discards
    result.flat_merges_total = sim.endpointer.flat_merges
    result.flat_discards_total = sim.endpointer.flat_discards
    end_ms = base_ms + len(probs) * FRAME_MS
    result.tail_ms = max(0, end_ms - sim.buffer_start_ms)
    return result, probs


def with_overrides(tuning: Tuning, **kw) -> Tuning:
    return replace(tuning, **{k: v for k, v in kw.items() if v is not None})
