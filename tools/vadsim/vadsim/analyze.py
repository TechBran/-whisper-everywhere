"""What the p-trace says about why the pauses are not being cut.

Four questions, in the order the owner asks them:

  (a) Where are the dips, and is each one QUIET or merely NOT-LOUD? A dip that never goes
      below RELEASE is a DEAD-BAND dip: `SileroEndpointer.onProb` returns having written no
      field at all (SileroEndpointer.kt:555), so no pending end is ever stamped and the
      hangover cannot start, at ANY hangover value. That is the shape edited speech takes
      when the editor has cut the room tone out and left the music bed in.
  (b) How long are the pauses? The hangover needs `hangover_frames()` consecutive
      below-RELEASE frames — 12 at 350 ms — so the histogram's 320-384 / 384-512 boundary is
      the one that decides everything.
  (c) For each CAP-cut chunk: what would it have taken to cut it at a pause instead?
  (d) What does the whole grid of tunings do?

Then, from 2026-09-03, the AMPLITUDE questions the flatline proposal needs answered — the
`(e)` block at the bottom of this file:

  (e) What does the RMS the endpointer ignores actually look like, split by Silero's own
      three states; how quiet does each pause really get; what run of near-zero frames
      would the trigger have had inside every chunk the wall cap had to dump; and what
      does each `flatline_rms x hold` pair cost, MID-WORD RISK included?
  (f) And does any of it match the phone? The logcat cross-check.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, asdict
from datetime import datetime
from typing import Dict, List, Optional, Sequence, Tuple

from .machine import (
    FRAME_MS,
    BASE_MS,
    Commit,
    SimResult,
    Tuning,
    simulate,
)

#: Owner-facing pause buckets. Edges in ms; the last is open-ended.
PAUSE_BUCKETS: Tuple[Tuple[int, Optional[int]], ...] = (
    (32, 96),
    (96, 192),
    (192, 320),
    (320, 384),
    (384, 512),
    (512, 1000),
    (1000, None),
)

#: `CommitCadencePolicy.MIN_COMMIT_INTERVAL_TURBO_MS`'s own KDoc (CommitCadencePolicy.kt:70):
#: "Work per commit on turbo is ~2.05 s and 87 % of it is the encode, which is FIXED".
TURBO_WORK_PER_COMMIT_MS = 2_050


# ---------------------------------------------------------------------------------------
# (a) Dips
# ---------------------------------------------------------------------------------------

@dataclass
class Dip:
    """One run of consecutive frames BELOW ONSET — i.e. one stretch in which the gate is
    not being held open. Classified by how quiet it actually got."""

    start_frame: int
    n_frames: int
    start_ms: int
    #: physical audio duration of the run, `n_frames * FRAME_MS`
    span_ms: int
    min_p: float
    max_p: float
    kind: str                     # 'dead-band' | 'silence'
    #: `tempEndMs` — the ms of the FIRST below-RELEASE frame in the run, or None if there
    #: is none (SileroEndpointer.kt:565). Never re-stamped inside the run: only a frame at
    #: or above ONSET clears it (:540), and by construction this run has none.
    temp_end_ms: Optional[int]
    #: the largest `nowMs - tempEndMs` any BELOW-RELEASE frame in this run reaches — and
    #: below-release is the whole point: a dead-band frame returns at
    #: SileroEndpointer.kt:555, above the hangover test, so the age is never COMPARED on
    #: one. A dip whose quiet frames come early and whose tail is mumble therefore has a
    #: small `max_age_ms` and a large `span_ms`, and cannot cut.
    max_age_ms: int
    #: total below-RELEASE frames in the run (they need not be contiguous — a dead-band
    #: frame between two quiet ones does NOT reset the timer, :549-554)
    below_release_frames: int
    #: the ms of the frame on which the MACHINE's hangover elapsed inside this dip — i.e. the
    #: frame `onProb` got past `nowMs - tempEndMs < HANGOVER_MS` (SileroEndpointer.kt:579)
    #: and went on to commit, merge or discard — or None. None when the gate was shut at the
    #: dip's start (`if (!speaking) return false`, :559: no pending end is ever stamped), when
    #: the dip never got that old, and when a CAP cut landed inside the dip first: the cap's
    #: `endpointer.reset()` (FloatingBubbleService.kt:2110) zeroes `tempEndMs` and shuts the
    #: gate, so the rest of the dip is structurally uncuttable. Read from `machine.event_track`,
    #: not re-derived from the ages, so it cannot disagree with the simulation.
    cut_ms: Optional[int]
    #: whether the gate was open when this dip began (see `machine.gate_track`)
    gate_open: bool = True
    #: `start_ms + span_ms`: the ms at which the run ended (the first frame back at/above ONSET)
    end_ms: int = 0
    #: what the machine DID with this dip — the first event inside it: `'vad'`, `'merge'`,
    #: `'discard'`, `'cap'` (the wall clock ran out INSIDE this pause, before the hangover),
    #: `'discard+cap'` / `'merge+cap'` (both on one frame), `'none'` (gate open, never reached
    #: the hangover) or `'gate-shut'` (no utterance to end).
    outcome: str = "none"
    #: the ms of the frame that carried `outcome`, or None
    event_ms: Optional[int] = None
    #: below-RELEASE frames from the dip's start up to and including the event frame — what
    #: the machine had actually counted when it decided (or when the cap took the decision
    #: away). Equals `below_release_frames` when there is no event.
    quiet_frames_at_event: int = 0
    #: `nowMs - tempEndMs` on the event frame — the age the guard at :579 saw. 0 when the cap
    #: landed before any quiet frame, or when there is no event.
    age_at_event_ms: int = 0

    def to_dict(self) -> dict:
        return asdict(self)


def find_dips(
    probs: Sequence[float],
    tuning: Tuning,
    base_ms: int = BASE_MS,
    *,
    gate: Optional[Sequence[bool]] = None,
    events: Optional[Sequence[str]] = None,
    is_cloud_session: bool = False,
) -> List[Dip]:
    """Every run of frames below ONSET, classified.

    A NO_VERDICT frame (p < 0) is treated as part of the run but cannot stamp the pending
    end and cannot be the quiet minimum — the app keeps the previous state exactly
    (SileroEndpointer.kt:534) and the hangover still counts through it
    (`no_verdict_frames_do_not_short_circuit_the_hangover`).

    `gate` and `events` are `machine.event_track`'s two outputs; both are computed here when
    not supplied. The gate is what stops leading silence — and every dip that follows a
    MIN_SPEECH discard — being reported as a cut the hangover missed. The events are what
    stop a pause the CAP fired into being reported as a pause the hangover should have cut:
    `cut_ms`, `outcome`, `max_age_ms` and the two `*_at_event` fields all come from what the
    machine did, and the age arithmetic below is only ever used up to the event frame.
    """
    from .machine import event_track

    if events is None or gate is None:
        g2, e2 = event_track(
            probs, tuning, base_ms=base_ms, is_cloud_session=is_cloud_session
        )
        gate = g2 if gate is None else gate
        events = e2 if events is None else events
    dips: List[Dip] = []
    i = 0
    n = len(probs)
    while i < n:
        if probs[i] >= tuning.onset:
            i += 1
            continue
        j = i
        while j < n and probs[j] < tuning.onset:
            j += 1
        run = [float(p) for p in probs[i:j]]
        real = [p for p in run if p >= 0.0]
        gate_open = bool(gate[i]) if i < len(gate) else False

        # The machine's verdict on this dip: the FIRST frame inside it that did anything.
        event_k: Optional[int] = None
        for k in range(i, j):
            if k < len(events) and events[k]:
                event_k = k - i
                break

        temp_end_ms: Optional[int] = None
        below = 0
        below_at_event = 0
        max_age = 0
        age_at_event = 0
        for k, p in enumerate(run):
            if p < 0.0:
                continue
            t = base_ms + (i + k) * FRAME_MS
            if p < tuning.release:
                below += 1
                if temp_end_ms is None:
                    temp_end_ms = t
                age = t - temp_end_ms
                # The age is only meaningful while the machine is still measuring this dip:
                # after the event frame it has either committed/merged/discarded (gate shut,
                # `closeGate()` :633) or been reset by a cap (:2110). Clip there.
                if event_k is None or k <= event_k:
                    if age > max_age:
                        max_age = age
                if event_k is not None and k <= event_k:
                    below_at_event += 1
        if event_k is not None:
            t_event = base_ms + (i + event_k) * FRAME_MS
            if temp_end_ms is not None and temp_end_ms <= t_event:
                age_at_event = t_event - temp_end_ms
        else:
            below_at_event = below

        if event_k is None:
            outcome = "none" if gate_open else "gate-shut"
            event_ms: Optional[int] = None
            cut_ms: Optional[int] = None
        else:
            outcome = events[i + event_k]
            event_ms = base_ms + (i + event_k) * FRAME_MS
            parts = set(outcome.split("+"))
            # `flat` joins the three: a flatline commit (the proposal) is a cut TAKEN
            # inside this dip, so the dip did reach a decision — see `machine._on_flat`.
            reached = bool(parts & {"vad", "merge", "discard", "flat"})
            cut_ms = event_ms if reached else None

        min_p = min(real) if real else -1.0
        max_p = max(real) if real else -1.0
        kind = "silence" if (real and min_p < tuning.release) else "dead-band"
        dips.append(
            Dip(
                start_frame=i,
                n_frames=j - i,
                start_ms=base_ms + i * FRAME_MS,
                span_ms=(j - i) * FRAME_MS,
                min_p=min_p,
                max_p=max_p,
                kind=kind,
                temp_end_ms=temp_end_ms,
                max_age_ms=max_age,
                below_release_frames=below,
                cut_ms=cut_ms,
                gate_open=gate_open,
                end_ms=base_ms + j * FRAME_MS,
                outcome=outcome,
                event_ms=event_ms,
                quiet_frames_at_event=below_at_event,
                age_at_event_ms=age_at_event,
            )
        )
        i = j
    return dips


def dead_band_fraction(probs: Sequence[float], tuning: Tuning) -> float:
    """Fraction of ALL frames parked in [RELEASE, ONSET) — the band that is neither an onset
    nor a silence and therefore writes nothing (SileroEndpointer.kt:549-555)."""
    if not probs:
        return 0.0
    n = sum(1 for p in probs if tuning.release <= p < tuning.onset)
    return n / len(probs)


def prob_summary(probs: Sequence[float], tuning: Tuning) -> Dict[str, float]:
    real = sorted(p for p in probs if p >= 0.0)
    if not real:
        return {}

    def pct(q: float) -> float:
        return real[min(len(real) - 1, int(q * (len(real) - 1) + 0.5))]

    total = len(probs)
    return {
        "n_frames": float(total),
        "n_no_verdict": float(sum(1 for p in probs if p < 0.0)),
        "mean": sum(real) / len(real),
        "p05": pct(0.05),
        "p50": pct(0.50),
        "p95": pct(0.95),
        "min": real[0],
        "max": real[-1],
        "frac_at_or_above_onset": sum(1 for p in probs if p >= tuning.onset) / total,
        "frac_dead_band": dead_band_fraction(probs, tuning),
        "frac_below_release": sum(1 for p in probs if 0.0 <= p < tuning.release) / total,
    }


# ---------------------------------------------------------------------------------------
# (b) Pause-length histogram
# ---------------------------------------------------------------------------------------

@dataclass
class HistRow:
    label: str
    lo: int
    hi: Optional[int]
    below_onset: int      # dips (any kind) whose span falls in this bucket
    dead_band: int        # of those, the ones that never went below RELEASE
    quiet_span: int       # dips whose BELOW-RELEASE span falls in this bucket

    def to_dict(self) -> dict:
        return asdict(self)


def pause_histogram(dips: Sequence[Dip]) -> List[HistRow]:
    """Two histograms in one table, and the difference between the columns IS the diagnosis.

    `below_onset` bins each dip by its physical span (`n_frames * 32`) — how long a listener
    would call the pause. `quiet_span` bins the same dips by `below_release_frames * 32` —
    how much of that pause the machine can actually count. A pause that is 500 ms long and
    120 ms quiet lands in 384-512 on the left and 96-192 on the right, and never cuts.
    """
    rows: List[HistRow] = []
    for lo, hi in PAUSE_BUCKETS:
        label = f"{lo}-{hi}" if hi is not None else f">{lo}"

        def in_bucket(v: int) -> bool:
            return v >= lo and (hi is None or v < hi)

        rows.append(
            HistRow(
                label=label,
                lo=lo,
                hi=hi,
                below_onset=sum(1 for d in dips if in_bucket(d.span_ms)),
                dead_band=sum(1 for d in dips if in_bucket(d.span_ms) and d.kind == "dead-band"),
                quiet_span=sum(
                    1 for d in dips if in_bucket(d.below_release_frames * FRAME_MS)
                ),
            )
        )
    return rows


# ---------------------------------------------------------------------------------------
# (c) Cap-cut forensics
# ---------------------------------------------------------------------------------------

@dataclass
class CapForensics:
    """"What would it have taken to cut this chunk at a pause?" for ONE cap-cut chunk."""

    t_ms: int
    cap_ms: Optional[int]
    chunk_ms: int
    retain_ms: int
    consumed_window: Optional[bool]
    n_dips: int
    #: of those, dips that began with the gate SHUT — structurally uncuttable
    n_gate_shut: int
    merged_inside: int
    discarded_inside: int
    #: the longest TRUE-SILENCE dip inside the chunk, measured as its below-RELEASE span
    longest_silence_ms: int
    longest_silence_at_ms: Optional[int]
    #: the largest hangover age any gate-open dip inside this chunk reached
    #: (`nowMs - tempEndMs`, clipped at the frame the machine stopped measuring it)
    best_age_ms: int
    #: how many more dip frames that best dip needed for its age to reach HANGOVER_MS
    #: (dead-band frames count — they do not reset the clock); 0 when a pause DID reach it
    frames_short: Optional[int]
    #: the longest DEAD-BAND dip inside the chunk — a pause that is not quiet
    longest_dead_band_ms: int
    longest_dead_band_at_ms: Optional[int]
    verdict: str

    def to_dict(self) -> dict:
        return asdict(self)


def _frames_short_for_age(age_ms: int, tuning: Tuning) -> int:
    """How many MORE dip frames the machine needed, given the largest age it saw.

    The k-th frame of a dip has age `(k - 1) * FRAME_MS` (the pending end is stamped AT the
    first sub-RELEASE frame, SileroEndpointer.kt:565), so an age of A means the machine had
    counted `A / FRAME_MS + 1` frames of this dip — dead-band frames INCLUDED, because they do
    not reset the clock (:549-555) — against the `hangover_frames()` it needs.
    """
    seen = age_ms // FRAME_MS + 1
    return max(0, tuning.hangover_frames() - seen)


def _has(d: Dip, event: str) -> bool:
    return event in d.outcome.split("+")


def cap_forensics(
    result: SimResult, dips: Sequence[Dip], tuning: Tuning
) -> List[CapForensics]:
    """One row per CAP commit: why did the wall clock, and not a pause, end this chunk?

    The verdict is decided from the machine's own per-dip OUTCOME (`Dip.outcome`, read off
    `machine.event_track`), not from re-derived frame counts, in this order:

      1. no dip with the gate open → the cap is the only exit;
      2. the cap fired INTO a pause (a dip whose first event is this very cap) → the cap's
         timing, not the hangover — and if the cap took the pause as its retain offer, say so;
      3. a pause reached the hangover and the GOVERNOR merged it → the cadence floor;
      4. a pause reached the hangover but the burst before it was under MIN_SPEECH_MS → the
         known gap;
      5. every pause was dead-band → RELEASE;
      6. otherwise the best pause was N frames short → the hangover that would have cut it.

    3 and 4 come BEFORE 6 deliberately: a dip with dead-band frames inside it reaches the
    hangover with fewer than `hangover_frames()` QUIET frames, so a frame-count test would
    call a discarded pause "N frames short" and send the owner after the hangover for a
    pause the machine did judge.
    """
    hf = tuning.hangover_frames()
    out: List[CapForensics] = []
    for c in result.of_kind("cap"):
        lo = c.buffer_start_ms
        hi = c.t_ms + FRAME_MS
        inside = [d for d in dips if lo <= d.start_ms < hi]
        # A dip that began with the gate SHUT can never stamp a pending end
        # (SileroEndpointer.kt:559), so it is not a cut the hangover missed.
        live = [d for d in inside if d.gate_open]
        silences = [d for d in live if d.kind == "silence"]
        deads = [d for d in live if d.kind == "dead-band"]
        # The pause THIS cap fired into, if any: its first event is this cap.
        straddle = next(
            (d for d in live if d.event_ms == c.t_ms and _has(d, "cap")), None
        )
        merged = [d for d in live if _has(d, "merge")]
        discarded = [d for d in live if _has(d, "discard")]
        # The best pause by the quantity the guard at :579 actually compares.
        best_quiet = max(
            silences,
            key=lambda d: (d.max_age_ms, d.below_release_frames),
            default=None,
        )
        best_age = best_quiet.max_age_ms if best_quiet else 0
        longest_dead = max(deads, key=lambda d: d.span_ms, default=None)

        frames_short: Optional[int] = None
        if best_quiet is not None:
            frames_short = _frames_short_for_age(best_quiet.max_age_ms, tuning)

        merge_note = (
            f"{c.merged_endpoints_inside} governor merge(s) inside at the "
            f"{tuning.min_commit_interval_ms} ms floor"
            if c.merged_endpoints_inside > 0 else None
        )
        discard_note = (
            f"{c.discarded_bursts_inside} MIN_SPEECH discard(s) inside"
            if c.discarded_bursts_inside > 0 else None
        )
        also: List[Optional[str]] = [merge_note, discard_note]

        if not live:
            if inside:
                verdict = (
                    f"{len(inside)} dip(s), all with the gate already SHUT — no pending end "
                    f"could be stamped; nothing about the hangover would change this chunk"
                )
            else:
                verdict = "no dip at all — p never left the speech band; the cap is the only exit"
        elif straddle is not None and straddle.quiet_frames_at_event > 0:
            age = straddle.age_at_event_ms
            need = tuning.hangover_ms
            frames_short = _frames_short_for_age(age, tuning)
            if c.retain_ms > 0 and c.cut_point_ms == straddle.temp_end_ms:
                verdict = (
                    f"the cap fired {age} ms INTO a pause that needed {need} ms, and cut AT "
                    f"that pause's start (retained {c.retain_ms} ms for the next chunk): the "
                    f"boundary is right, only the timing is the cap's. A hangover <= {age} ms "
                    f"would have made it a VAD cut {frames_short} frame(s) earlier; nothing "
                    f"about the hangover changes the boundary"
                )
            elif age == 0:
                verdict = (
                    f"the cap landed on this pause's FIRST quiet frame — {hf - 1} more quiet "
                    f"frame(s) and the hangover would have cut it; the cap won the race by the "
                    f"whole hangover ({need} ms). Not a hangover problem"
                )
            else:
                offer = (
                    "too young for the retain (under MICRO_PAUSE_MS)"
                    if age <= tuning.micro_pause_ms
                    else f"not taken (retain {c.retain_ms} ms)"
                )
                verdict = (
                    f"the cap fired {age} ms INTO a pause that needed {need} ms — the cap won "
                    f"the race by {need - age} ms ({frames_short} frame(s)). A hangover <= "
                    f"{age} ms would have made it a VAD cut; the offer was {offer}. Not a "
                    f"hangover problem"
                )
        elif merged:
            frames_short = 0
            verdict = (
                f"a cuttable pause existed and the GOVERNOR merged it "
                f"({c.merged_endpoints_inside} merge(s) at a "
                f"{tuning.min_commit_interval_ms} ms floor) — the hangover is not the problem"
            )
            also[0] = None
        elif discarded:
            frames_short = 0
            verdict = (
                f"a cuttable pause existed but the burst before it was under "
                f"MIN_SPEECH_MS ({tuning.min_speech_ms} ms) — "
                f"{c.discarded_bursts_inside} discard(s); THE KNOWN GAP "
                f"(EndpointerTuning.kt:134-140), not a hangover problem"
            )
            also[1] = None
        elif not silences:
            verdict = (
                f"{len(deads)} dip(s), ALL dead-band: nothing below RELEASE, so no pending "
                f"end was ever stamped and NO hangover value could have cut this chunk. "
                f"Lower RELEASE (currently {tuning.release:.2f}) or nothing changes."
            )
        elif frames_short == 0:
            verdict = (
                "a pause reached the hangover with the gate open and produced no event — "
                "INVESTIGATE (this should be unreachable)"
            )
        else:
            assert best_quiet is not None
            verdict = (
                f"best pause reached an age of {best_quiet.max_age_ms} ms "
                f"({best_quiet.below_release_frames} quiet frame(s), "
                f"{best_quiet.below_release_frames * FRAME_MS} ms of quiet audio) — "
                f"{frames_short} frame(s) short; a hangover of <= "
                f"{max(FRAME_MS, best_quiet.max_age_ms)} ms would have cut it"
            )
        notes = [a for a in also if a]
        if notes:
            verdict += " (also: " + "; ".join(notes) + ")"

        out.append(
            CapForensics(
                t_ms=c.t_ms,
                cap_ms=c.cap_ms,
                chunk_ms=c.chunk_ms,
                retain_ms=c.retain_ms,
                consumed_window=c.consumed_window,
                n_dips=len(inside),
                n_gate_shut=len(inside) - len(live),
                merged_inside=c.merged_endpoints_inside,
                discarded_inside=c.discarded_bursts_inside,
                longest_silence_ms=(
                    best_quiet.below_release_frames * FRAME_MS if best_quiet else 0
                ),
                longest_silence_at_ms=best_quiet.start_ms if best_quiet else None,
                best_age_ms=best_age,
                frames_short=frames_short,
                longest_dead_band_ms=longest_dead.span_ms if longest_dead else 0,
                longest_dead_band_at_ms=longest_dead.start_ms if longest_dead else None,
                verdict=verdict,
            )
        )
    return out


# ---------------------------------------------------------------------------------------
# (d) The sweep
# ---------------------------------------------------------------------------------------

SWEEP_HANGOVERS = (320, 350, 400, 500)
SWEEP_RELEASES = (0.25, 0.30, 0.35)
SWEEP_CAPS = (8_000, 10_000, 15_000)


@dataclass
class SweepRow:
    hangover_ms: int
    release: float
    cap_ms: int
    commits: int
    vad: int
    cap: int
    vad_pct: float
    cap_pct: float
    mean_chunk_ms: float
    p95_chunk_ms: float
    merges: int
    discards: int
    dead_band_frac: float
    turbo_duty: float
    tail_ms: int

    def to_dict(self) -> dict:
        return asdict(self)


def _percentile(values: Sequence[float], q: float) -> float:
    if not values:
        return 0.0
    s = sorted(values)
    if len(s) == 1:
        return float(s[0])
    pos = q * (len(s) - 1)
    lo = int(pos)
    hi = min(len(s) - 1, lo + 1)
    return float(s[lo] + (s[hi] - s[lo]) * (pos - lo))


def summarise(result: SimResult, probs: Sequence[float]) -> Dict[str, float]:
    chunks = [float(c.chunk_ms) for c in result.commits]
    wall = max(1, result.wall_ms)
    n_vad = len(result.of_kind("vad"))
    n_cap = len(result.of_kind("cap"))
    #: the flatline PROPOSAL's own commits — always 0 unless `Tuning.flatline_enabled`
    n_flat = len(result.of_kind("flat"))
    total = len(result.commits)
    # THE SPEECH EVIDENCE (4.3.2, Layer 1): the commits `LocalWhisperEngine` would resolve
    # EmptyExpected WITHOUT an encode at the floor, and the decoder work that saves — one
    # `service_ms` job per skipped commit (the ~1.78 s encode is 87 % of it; the decode and the
    # detect pass are the rest, and a skipped segment pays none of them). `turbo_duty_encoded`
    # is the duty rule's number over the commits that still run.
    skipped = result.skipped()
    n_skipped = len(skipped)
    return {
        "commits": total,
        "min_evidence_ms": result.tuning.min_evidence_ms,
        "skipped_at_min_evidence": n_skipped,
        "skipped_pct": 100.0 * n_skipped / total if total else 0.0,
        "skip_work_saved_ms": n_skipped * result.tuning.service_ms,
        "encoded": total - n_skipped,
        "unknown_evidence": sum(1 for c in result.commits if c.speech_evidence_ms is None),
        "tail_evidence_ms": -1 if result.tail_evidence_ms is None else result.tail_evidence_ms,
        "tail_skipped": result.tail_skipped(),
        "turbo_duty_encoded": (total - n_skipped) * TURBO_WORK_PER_COMMIT_MS / wall,
        "vad": n_vad,
        "cap": n_cap,
        "flat": n_flat,
        "flat_pct": 100.0 * n_flat / total if total else 0.0,
        "flat_merges": result.flat_merges_total,
        "flat_discards": result.flat_discards_total,
        "vad_pct": 100.0 * n_vad / total if total else 0.0,
        "cap_pct": 100.0 * n_cap / total if total else 0.0,
        "mean_chunk_ms": sum(chunks) / len(chunks) if chunks else 0.0,
        "p95_chunk_ms": _percentile(chunks, 0.95),
        "merges": result.merges_total,
        "discards": result.discards_total,
        "turbo_duty": total * TURBO_WORK_PER_COMMIT_MS / wall,
        "tail_ms": result.tail_ms,
    }


def sweep(
    probs: Sequence[float],
    base: Tuning,
    *,
    base_ms: int = BASE_MS,
    hangovers: Sequence[int] = SWEEP_HANGOVERS,
    releases: Sequence[float] = SWEEP_RELEASES,
    caps: Sequence[int] = SWEEP_CAPS,
    floor_ms: int = 2_000,
    is_cloud_session: bool = False,
) -> List[SweepRow]:
    """hangover x release x cap at the turbo floor.

    `floor_ms` defaults to 2 000 — `CommitCadencePolicy.MIN_COMMIT_INTERVAL_TURBO_MS`, the
    2026-09-03 owner ruling — because that is the row the device under test is on.

    Build 85: every row inherits `base.slow_commit_interval_ms` and `base.service_ms` unchanged,
    so a CLI run at turbo sweeps WITH the backpressure governor armed (3 200 once the modelled
    decoder queue reaches 2) and a test that builds its `Tuning()` bare sweeps without it.

    `turbo_duty` is `commits * 2050 ms / wall ms`: the saturated-duty arithmetic that
    constant's own KDoc states (CommitCadencePolicy.kt:76-91), applied to THIS audio rather
    than to the worst case. Over 1.0 means the segment queue grows.
    """
    from dataclasses import replace

    rows: List[SweepRow] = []
    for hg in hangovers:
        for rel in releases:
            for cap in caps:
                t = replace(
                    base,
                    hangover_ms=hg,
                    release=rel,
                    cap_ms=cap,
                    min_commit_interval_ms=floor_ms,
                )
                res = simulate(probs, t, base_ms=base_ms, is_cloud_session=is_cloud_session)
                s = summarise(res, probs)
                rows.append(
                    SweepRow(
                        hangover_ms=hg,
                        release=rel,
                        cap_ms=cap,
                        commits=int(s["commits"]),
                        vad=int(s["vad"]),
                        cap=int(s["cap"]),
                        vad_pct=s["vad_pct"],
                        cap_pct=s["cap_pct"],
                        mean_chunk_ms=s["mean_chunk_ms"],
                        p95_chunk_ms=s["p95_chunk_ms"],
                        merges=int(s["merges"]),
                        discards=int(s["discards"]),
                        dead_band_frac=dead_band_fraction(probs, t),
                        turbo_duty=s["turbo_duty"],
                        tail_ms=int(s["tail_ms"]),
                    )
                )
    return rows


# =======================================================================================
# (e) THE RMS SIDE — the amplitude the endpointer ignores, and the flatline PROPOSAL.
#
# `SileroEndpointer.kt:245` takes `amp` and documents it as "ignored here". The waveform
# the owner watches is that same number (`waveformView.updateAmplitude(amp)`,
# FloatingBubbleService.kt:1998), which is why the bubble can visibly flatline at an edit
# point while the endpointer sails past it: the hangover needs 12 consecutive
# below-RELEASE frames (352 ms, EndpointerTuning.kt:87) and an editor leaves 100-300 ms.
#
# Everything below is MEASUREMENT, not behaviour: it tells the owner what the two
# constants of `machine._on_flat` would have to be, and what they would cost.
# =======================================================================================

#: RMS buckets, in AudioMath's 0..32767 units. `(0, 1)` is the exact-zero bucket — digital
#: silence, which natural speech never produces (room tone measures 50-300).
RMS_BUCKETS: Tuple[Tuple[int, Optional[int]], ...] = (
    (0, 1),
    (1, 10),
    (10, 20),
    (20, 40),
    (40, 80),
    (80, 160),
    (160, 320),
    (320, 640),
    (640, 1280),
    (1280, None),
)

#: The candidate `flatline_rms` values the cap-chunk table measures runs under, and the
#: sweep's own axis (which drops 0 — a strict `rms < 0` can never fire).
FLAT_CANDIDATE_RMS: Tuple[int, ...] = (0, 10, 20, 40, 80, 160)
FLAT_SWEEP_RMS: Tuple[int, ...] = (10, 20, 40, 80, 160)

#: Holds in ms. 96 is three frames, 320 is ten — the band either side of the 100-300 ms
#: the owner measured on the edited video.
FLAT_SWEEP_HOLDS: Tuple[int, ...] = (96, 128, 160, 224, 320)


def silero_state(p: float, tuning: Tuning) -> str:
    """Which of `onProb`'s three bands this frame is in — `speech` (>= ONSET, :539),
    `dead-band` ([RELEASE, ONSET), :555, inert) or `silence` (< RELEASE, :559 onward).
    A no-verdict frame (p < 0, :534) is its own state: it writes nothing at all."""
    if p < 0.0:
        return "no-verdict"
    if p >= tuning.onset:
        return "speech"
    if p >= tuning.release:
        return "dead-band"
    return "silence"


@dataclass
class RmsHistRow:
    label: str
    lo: int
    hi: Optional[int]
    speech: int          # frames with p >= ONSET
    dead_band: int       # frames in [RELEASE, ONSET)
    silence: int         # frames with 0 <= p < RELEASE
    no_verdict: int      # frames with p < 0
    total: int

    def to_dict(self) -> dict:
        return asdict(self)


def rms_histogram(
    probs: Sequence[float], rms: Sequence[int], tuning: Tuning
) -> List[RmsHistRow]:
    """(a) The RMS distribution, SPLIT BY SILERO STATE.

    The split is the whole point. If a threshold is to separate "the editor cut the room
    tone out" from "someone is talking", the `speech` column must be empty below it and
    the `silence` column must have mass there. A threshold with speech frames under it is
    a mid-word cut waiting to happen — which is what the sweep's MID-WORD RISK column
    then prices.
    """
    n = min(len(probs), len(rms))
    rows: List[RmsHistRow] = []
    for lo, hi in RMS_BUCKETS:
        if hi is None:
            label = f">={lo}"
        elif hi == lo + 1:
            label = f"{lo}"
        else:
            label = f"{lo}-{hi - 1}"
        counts = {"speech": 0, "dead-band": 0, "silence": 0, "no-verdict": 0}
        for i in range(n):
            v = int(rms[i])
            if v >= lo and (hi is None or v < hi):
                counts[silero_state(float(probs[i]), tuning)] += 1
        rows.append(
            RmsHistRow(
                label=label,
                lo=lo,
                hi=hi,
                speech=counts["speech"],
                dead_band=counts["dead-band"],
                silence=counts["silence"],
                no_verdict=counts["no-verdict"],
                total=sum(counts.values()),
            )
        )
    return rows


@dataclass
class DipRms:
    """(b) One dip's amplitude story: how quiet the QUIET frames actually got."""

    start_ms: int
    span_ms: int
    kind: str
    gate_open: bool
    outcome: str
    quiet_frames: int             # frames below RELEASE in this dip
    min_rms: Optional[int]        # over the dip's below-RELEASE frames
    median_rms: Optional[int]     # over the same frames
    min_rms_all: Optional[int]    # over EVERY frame of the dip, dead band included
    median_rms_all: Optional[int]

    def to_dict(self) -> dict:
        return asdict(self)


def _median_int(values: Sequence[int]) -> Optional[int]:
    """Integer median, low side on an even count — these are integer RMS readings and a
    .5 would be an amplitude no `AudioMath.amplitude` call ever returned."""
    if not values:
        return None
    s = sorted(int(v) for v in values)
    return s[(len(s) - 1) // 2]


def dip_rms(
    dips: Sequence[Dip],
    probs: Sequence[float],
    rms: Sequence[int],
    tuning: Tuning,
    base_ms: int = BASE_MS,
) -> List[DipRms]:
    """(b) Per dip: the min and median chunk-RMS of its below-RELEASE frames.

    "Below RELEASE" is the population that matters because those are the only frames the
    hangover counts (`onProb` returns at `:555` for a dead-band frame, above the hangover
    test). The `*_all` pair carries the same two numbers over the whole dip so a dead-band
    dip — which has no quiet frames at all — still reports an amplitude.
    """
    out: List[DipRms] = []
    n = min(len(probs), len(rms))
    for d in dips:
        lo = d.start_frame
        hi = min(n, d.start_frame + d.n_frames)
        quiet = [int(rms[i]) for i in range(lo, hi)
                 if 0.0 <= float(probs[i]) < tuning.release]
        every = [int(rms[i]) for i in range(lo, hi)]
        out.append(
            DipRms(
                start_ms=d.start_ms,
                span_ms=d.span_ms,
                kind=d.kind,
                gate_open=d.gate_open,
                outcome=d.outcome,
                quiet_frames=len(quiet),
                min_rms=min(quiet) if quiet else None,
                median_rms=_median_int(quiet),
                min_rms_all=min(every) if every else None,
                median_rms_all=_median_int(every),
            )
        )
    return out


def longest_run_under(rms: Sequence[int], threshold: int) -> int:
    """Longest run of consecutive frames the flat trigger would have counted.

    The predicate is the machine's own — `rms < threshold` (`machine._on_flat`) — with ONE
    documented exception: at `threshold = 0` a strict `<` can never be true, so the run of
    EXACT ZEROS is reported instead. That is the number worth seeing (digital silence is
    the signature of an editor's gate), and it is labelled `==0` everywhere it appears so
    it cannot be mistaken for a value the trigger would accept.
    """
    best = run = 0
    for v in rms:
        hit = (int(v) == 0) if threshold <= 0 else (int(v) < threshold)
        run = run + 1 if hit else 0
        best = max(best, run)
    return best


def flat_run_lengths(rms: Sequence[int], threshold: int) -> List[int]:
    """Every maximal run of consecutive frames under `threshold` (same predicate and the
    same `==0` exception as `longest_run_under`), as a list of lengths in FRAMES."""
    out: List[int] = []
    run = 0
    for v in rms:
        hit = (int(v) == 0) if threshold <= 0 else (int(v) < threshold)
        if hit:
            run += 1
        elif run:
            out.append(run)
            run = 0
    if run:
        out.append(run)
    return out


#: Run-length buckets in FRAMES for `flat_run_histogram`. 4 and 5 are the two the sweep's
#: 96 / 128 holds need; 12 is where Silero's own hangover takes over at 350 ms.
FLAT_RUN_BUCKETS: Tuple[Tuple[int, Optional[int]], ...] = (
    (1, 2), (2, 3), (3, 4), (4, 5), (5, 6), (6, 7), (7, 8), (8, 12), (12, None),
)


@dataclass
class FlatRunHistRow:
    """How many flat runs of each length the clip has under one threshold — the direct
    instrument for the HOLD: a hold that needs `k` flat chunks catches exactly the runs
    of length >= k, and the runs of length >= 12 (at HANGOVER_MS 350) are the ones
    Silero's hangover cuts on its own when `p` also drops. Counted on the whole clip,
    gate open or shut, so it describes the AUDIO and not one tuning's outcome."""

    threshold: int
    n_runs: int
    counts: Dict[str, int]
    #: runs of length >= k for k = 1..12 — what a hold needing k chunks has to work with
    at_least: Dict[int, int]

    def to_dict(self) -> dict:
        return asdict(self)


def flat_run_histogram(
    rms: Sequence[int], candidates: Sequence[int] = FLAT_CANDIDATE_RMS
) -> List[FlatRunHistRow]:
    rows: List[FlatRunHistRow] = []
    for thr in candidates:
        lengths = flat_run_lengths(rms, thr)
        counts: Dict[str, int] = {}
        for lo, hi in FLAT_RUN_BUCKETS:
            label = f"{lo}" if hi == lo + 1 else (f"{lo}-{hi - 1}" if hi else f">={lo}")
            counts[label] = sum(1 for n in lengths if n >= lo and (hi is None or n < hi))
        rows.append(
            FlatRunHistRow(
                threshold=thr,
                n_runs=len(lengths),
                counts=counts,
                at_least={k: sum(1 for n in lengths if n >= k) for k in range(1, 13)},
            )
        )
    return rows


@dataclass
class CapChunkFlatRuns:
    """(c) For ONE cap-cut chunk: what the flatline trigger would have had to work with."""

    t_ms: int
    cap_ms: Optional[int]
    chunk_ms: int
    first_frame: int
    n_frames: int
    #: threshold -> longest consecutive run of frames under it, in FRAMES
    runs: Dict[int, int]
    #: the same runs in ms (`frames * FRAME_MS`)
    runs_ms: Dict[int, int]
    min_rms: Optional[int]

    def to_dict(self) -> dict:
        return asdict(self)


def cap_chunk_flat_runs(
    result: SimResult,
    rms: Sequence[int],
    *,
    candidates: Sequence[int] = FLAT_CANDIDATE_RMS,
    base_ms: int = BASE_MS,
) -> List[CapChunkFlatRuns]:
    """(c) Per CAP-cut chunk, the longest run under each candidate threshold.

    A cap cut is the app dumping the buffer because no pause was cuttable, so this table
    answers "would the flatline trigger have found a boundary in the audio the cap had to
    dump, and at which threshold?" A row whose longest run at 40 is 5 frames is a chunk a
    128 ms hold would have cut; a row whose runs are 0 everywhere is a chunk no flatline
    value could rescue — that audio never went quiet in AMPLITUDE either.

    The window is the commit's own buffer span, `[buffer_start_ms, t_ms + FRAME_MS)`, the
    same interval `cap_forensics` uses, converted to frame indices off `base_ms`.
    """
    out: List[CapChunkFlatRuns] = []
    for c in result.of_kind("cap"):
        first = max(0, (c.buffer_start_ms - base_ms) // FRAME_MS)
        last = min(len(rms), (c.t_ms - base_ms) // FRAME_MS + 1)
        window = [int(v) for v in rms[first:last]]
        runs = {t: longest_run_under(window, t) for t in candidates}
        out.append(
            CapChunkFlatRuns(
                t_ms=c.t_ms,
                cap_ms=c.cap_ms,
                chunk_ms=c.chunk_ms,
                first_frame=int(first),
                n_frames=len(window),
                runs=runs,
                runs_ms={t: v * FRAME_MS for t, v in runs.items()},
                min_rms=min(window) if window else None,
            )
        )
    return out


def mid_word_risk_frames(
    result: SimResult, probs: Sequence[float], tuning: Tuning, base_ms: int = BASE_MS
) -> List[int]:
    """THE NUMBER THE OWNER MUST SEE: flat cuts that land in or beside Silero speech.

    A flat cut is RISKY when either holds:
      * the cut frame itself, or the frame either side of it, has `p >= ONSET` — Silero
        still calls that instant speech, so the trigger is cutting through a word the
        model can hear; or
      * the cut SPLITS a Silero speech run: both neighbours are speech. (Strictly a subset
        of the first test — it is kept as its own clause because it is the failure the
        owner asked to be counted, and a future ONSET change must not silently drop it.)

    `no_context = true` (`whisper_jni.cpp:846`) makes such a cut UNREPAIRABLE in both
    directions, which is why this is a ship gate and not a statistic.

    IT IS DELIBERATELY CONSERVATIVE, and the report says so: a cut on the LAST flat frame
    of an editor's gap — the frame immediately before speech resumes — trips the `+1`
    clause even though that boundary is exactly the one the owner wants. Use it with
    `mid_word_split_frames` below, which is the strict subset (BOTH neighbours speech)
    that can only mean a cut THROUGH a word. Two columns, so the owner can tell a
    well-placed cut from a mangled one instead of reading one number that mixes them.

    AND IT UNDERCOUNTS, in exactly the place the danger lives (verifier, 2026-09-03):
    it only looks at `p` on three frames, and the trigger can only have fired on a frame
    whose chunk RMS was under the floor for `flatline_fire_chunks()` chunks running. On
    true digital silence Silero's `p` is ~0 from the first frame (measured on
    `jfk-gated.wav`: max `p` on a zero-RMS frame 0.075), so on gated audio RISK and SPLITS
    read 0 essentially by construction — they are not evidence of safety there. And the
    mid-word case that matters — a soft, low-RMS stretch INSIDE a word (a long fricative,
    a breathy closure, a quiet talker) where Silero also sits in the dead band — has
    `p < ONSET` on all three frames, so neither column sees it
    (`test_the_risk_metric_is_blind_to_a_dead_band_soft_segment_inside_a_word`). Its
    value also flips with hold parity: on the same gaps a 96 ms hold reads 0 and a 128 ms
    hold reads N-1, because one fires a frame before speech resumes and the other on the
    frame before that. Read `mid_word_bridge_frames` for the structural question the
    owner is actually asking.
    """
    risky: List[int] = []
    n = len(probs)
    for c in result.of_kind("flat"):
        i = (c.t_ms - base_ms) // FRAME_MS
        if not (0 <= i < n):
            continue
        near = [probs[j] for j in (i - 1, i, i + 1) if 0 <= j < n]
        adjacent_speech = any(float(p) >= tuning.onset for p in near)
        splits_run = _splits_speech_run(probs, i, tuning)
        if adjacent_speech or splits_run:
            risky.append(int(i))
    return risky


def _splits_speech_run(probs: Sequence[float], i: int, tuning: Tuning) -> bool:
    """Both frames around `i` are speech — the cut lands INSIDE a Silero speech run."""
    n = len(probs)
    return (
        0 <= i - 1 and i + 1 < n
        and float(probs[i - 1]) >= tuning.onset
        and float(probs[i + 1]) >= tuning.onset
    )


def mid_word_split_frames(
    result: SimResult, probs: Sequence[float], tuning: Tuning, base_ms: int = BASE_MS
) -> List[int]:
    """The strict subset of `mid_word_risk_frames`: flat cuts with speech on BOTH sides.

    A cut here is not "close to a word", it is INSIDE one. There is no reading of this
    number other than a mangled word, so a tuning with any splits at all is rejected
    before its risk column is even discussed.
    """
    out: List[int] = []
    for c in result.of_kind("flat"):
        i = (c.t_ms - base_ms) // FRAME_MS
        if 0 <= i < len(probs) and _splits_speech_run(probs, i, tuning):
            out.append(int(i))
    return out


def mid_word_bridge_frames(
    result: SimResult, probs: Sequence[float], tuning: Tuning, base_ms: int = BASE_MS
) -> List[int]:
    """BRIDGED flat cuts: Silero speech (`p >= ONSET`) resumes within `hangover_frames()`
    frames AFTER the cut — i.e. the cut landed inside a stretch the shipped machine would
    have kept as ONE utterance, because the dip was shorter than the hangover.

    This is the structural question the RISK column only approximates with three frames:
    "did the trigger create a boundary Silero would not have?" It does not look at `p`
    ON the flat frames (which is ~0 on digital silence and in the dead band on a soft
    segment alike, and therefore says nothing); it asks whether speech came back before
    the hangover would have elapsed. A cut with speech before it (the gate was open, by
    construction) and speech again within 352 ms is a bridge — the only kind of cut the
    trigger exists to make on EDITED audio, and the only kind that can harm NATURAL
    audio. So on gated audio this column is expected to equal the flat-cut count, and
    `bridged_nonzero` (below) is what separates the two cases: a bridge across DIGITAL
    ZERO is an editor's gate, a bridge across anything else is a word.
    """
    out: List[int] = []
    n = len(probs)
    win = tuning.hangover_frames()
    for c in result.of_kind("flat"):
        i = (c.t_ms - base_ms) // FRAME_MS
        if not (0 <= i < n):
            continue
        if any(float(probs[j]) >= tuning.onset for j in range(i + 1, min(n, i + 1 + win))):
            out.append(int(i))
    return out


def flat_run_max_rms(
    result: SimResult, rms: Sequence[int], tuning: Tuning, base_ms: int = BASE_MS
) -> Dict[int, int]:
    """For every flat cut, the LARGEST chunk RMS inside the run that fired it, keyed by
    the cut frame. At the shipped 32 ms chunk the firing run is exactly the
    `flatline_frames()` frames ending on the cut frame (the run fires the moment its age
    reaches the hold, and a merge or discard closes the gate, so no run is ever longer
    at the instant it fires). 0 means the whole run was exact digital silence."""
    out: Dict[int, int] = {}
    k = tuning.flatline_frames()
    for c in result.of_kind("flat"):
        i = (c.t_ms - base_ms) // FRAME_MS
        lo = max(0, i - k + 1)
        window = [int(v) for v in rms[lo:i + 1]]
        out[int(i)] = max(window) if window else 0
    return out


def mid_word_bridge_nonzero_frames(
    result: SimResult,
    probs: Sequence[float],
    rms: Sequence[int],
    tuning: Tuning,
    base_ms: int = BASE_MS,
    digital_floor: int = 0,
) -> List[int]:
    """Bridged cuts whose firing run was NOT digital silence — `max rms in run >
    digital_floor`. THIS is the ship gate on natural audio: a boundary Silero would not
    have made, across audio that was not an editor's gate. `digital_floor` is 0 (exact
    zero) by default; on the phone a decoded "silent" stream may read 1-3 RMS rather than
    0 (codec floor, format conversion), which is a reason to read this column at a floor
    of a few units as well as at 0 — never a reason to raise `flatline_rms`."""
    peaks = flat_run_max_rms(result, rms, tuning, base_ms=base_ms)
    return [i for i in mid_word_bridge_frames(result, probs, tuning, base_ms=base_ms)
            if peaks.get(i, 0) > digital_floor]


@dataclass
class FlatSweepRow:
    """One row of the flatline sweep. `enabled=False` is the BASELINE — the shipped
    machine, trigger off — and it is the row every other row must be read against."""

    enabled: bool
    flatline_rms: Optional[int]
    hold_ms: Optional[int]
    effective_hold_ms: Optional[int]
    commits: int
    flat: int
    vad: int
    cap: int
    flat_pct: float
    vad_pct: float
    cap_pct: float
    mean_chunk_ms: float
    p95_chunk_ms: float
    turbo_duty: float
    #: flat cuts with Silero speech at or beside the cut frame — see `mid_word_risk_frames`
    mid_word_risk: int
    #: the strict subset: speech on BOTH sides, i.e. a cut THROUGH a word
    mid_word_splits: int
    #: flat cuts after which Silero speech resumes within the hangover — a boundary the
    #: shipped machine would NOT have made (`mid_word_bridge_frames`); on edited audio this
    #: is every intended cut, on natural audio it is every harmful one
    bridged: int = 0
    #: of those, the ones whose flat run was not exact digital silence — the ship gate
    bridged_nonzero: int = 0
    merges: int = 0
    discards: int = 0
    flat_merges: int = 0
    flat_discards: int = 0
    tail_ms: int = 0

    def to_dict(self) -> dict:
        return asdict(self)


def flat_sweep(
    probs: Sequence[float],
    rms: Sequence[int],
    base: Tuning,
    *,
    base_ms: int = BASE_MS,
    rms_values: Sequence[int] = FLAT_SWEEP_RMS,
    holds: Sequence[int] = FLAT_SWEEP_HOLDS,
    floor_ms: int = 2_000,
    is_cloud_session: bool = False,
) -> List[FlatSweepRow]:
    """(d) `flatline_rms x hold` at the DEFAULT hangover/release/cap and the 2 000 floor.

    The hangover, release and cap are deliberately NOT swept here: this table asks one
    question — "what does adding the trigger to the shipped machine do?" — and the first
    row answers it with the trigger OFF so every other row has a baseline. Sweeping four
    knobs at once would make the flat column unreadable.

    `floor_ms` is `CommitCadencePolicy.MIN_COMMIT_INTERVAL_TURBO_MS` (2 000, the owner
    ruling) because that is the tier the device under test is on; a flat endpoint arriving
    inside that window MERGES exactly as a Silero one does. Build 85: rows inherit the base's
    slow floor and decoder service time, as `sweep` does — a flat close consults the same
    `_current_floor_ms` the Silero close does.
    """
    from dataclasses import replace

    rows: List[FlatSweepRow] = []

    def row(t: Tuning, enabled: bool, r: Optional[int], hold: Optional[int]) -> FlatSweepRow:
        res = simulate(
            probs, t, base_ms=base_ms, is_cloud_session=is_cloud_session,
            rms=rms if enabled else None,
        )
        s = summarise(res, probs)
        return FlatSweepRow(
            enabled=enabled,
            flatline_rms=r,
            hold_ms=hold,
            effective_hold_ms=t.flatline_effective_hold_ms() if enabled else None,
            commits=int(s["commits"]),
            flat=int(s["flat"]),
            vad=int(s["vad"]),
            cap=int(s["cap"]),
            flat_pct=s["flat_pct"],
            vad_pct=s["vad_pct"],
            cap_pct=s["cap_pct"],
            mean_chunk_ms=s["mean_chunk_ms"],
            p95_chunk_ms=s["p95_chunk_ms"],
            turbo_duty=s["turbo_duty"],
            mid_word_risk=len(mid_word_risk_frames(res, probs, t, base_ms=base_ms)),
            mid_word_splits=len(mid_word_split_frames(res, probs, t, base_ms=base_ms)),
            bridged=len(mid_word_bridge_frames(res, probs, t, base_ms=base_ms)),
            bridged_nonzero=len(
                mid_word_bridge_nonzero_frames(res, probs, rms, t, base_ms=base_ms)
            ),
            merges=int(s["merges"]),
            discards=int(s["discards"]),
            flat_merges=int(s["flat_merges"]),
            flat_discards=int(s["flat_discards"]),
            tail_ms=int(s["tail_ms"]),
        )

    # (e) THE BASELINE ROW: the trigger OFF, everything else identical.
    rows.append(
        row(replace(base, flatline_enabled=False, min_commit_interval_ms=floor_ms),
            False, None, None)
    )
    for r in rms_values:
        for hold in holds:
            t = replace(
                base,
                flatline_enabled=True,
                flatline_rms=r,
                flatline_hold_ms=hold,
                min_commit_interval_ms=floor_ms,
            )
            rows.append(row(t, True, r, hold))
    return rows


# =======================================================================================
# (f) THE PHONE CROSS-CHECK — logcat `encode:` timestamps beside the simulator's commits.
# =======================================================================================

#: threadtime logcat: `MM-DD HH:MM:SS.mmm  PID  TID LEVEL TAG : message`. adb right-pads
#: the tag, so the space before the colon is real and must be tolerated.
_LOGCAT_RE = re.compile(
    r"^(?P<mon>\d{2})-(?P<day>\d{2})\s+"
    r"(?P<h>\d{2}):(?P<m>\d{2}):(?P<s>\d{2})\.(?P<ms>\d{3})\s+"
    r"(?P<pid>\d+)\s+(?P<tid>\d+)\s+(?P<lvl>[VDIWEFAS])\s+"
    r"(?P<tag>\S+)\s*:\s*(?P<msg>.*)$"
)


@dataclass
class PhoneEncode:
    """One native `encode:` line — the phone's evidence that a segment was committed."""

    t_ms: int             # the line's own timestamp, as an ordinal in ms
    encode_ms: float      # the duration the line reports ("OK in 1791.9 ms")
    raw: str

    def to_dict(self) -> dict:
        return asdict(self)


def _ordinal_ms(t: datetime) -> int:
    """Naive ms since 2000-01-01 — a stable ordinal, never a real epoch. Only DIFFERENCES
    between two of these are ever used (threadtime carries no year)."""
    return int((t - datetime(2000, 1, 1)).total_seconds() * 1000)


def parse_phone_capture(text: str) -> List[PhoneEncode]:
    """Every `encode:` line in a threadtime logcat capture, in order.

    THE LINE: `09-03 12:01:02.366 27133 27925 I WE-DIAG : encode: graphExecute OK in
    1791.9 ms (vote: ...)`. One such line is emitted per committed segment, from the
    engine thread, so the SEQUENCE of them is the phone's commit sequence.

    WHAT THE TIMESTAMP IS, honestly: the instant the encode FINISHED, not the instant the
    endpointer cut. A commit's audio therefore precedes its line by the encode duration
    (~1.78 s on npu-turbo, and the fixed `[1,melBins,3000]` window makes that near
    constant — EndpointerTuning.kt:70-78) plus any queue wait. INTERVALS between
    consecutive lines are what survive that offset while the queue is not backing up,
    which is why this compares intervals rather than instants — and why it is a
    CROSS-CHECK, not a fit. A backed-up queue shows as intervals pinned near the encode
    cost, and a growing backlog invalidates the comparison outright.

    There is no YEAR in threadtime output, so the clock is reconstructed on a fixed year.
    A capture crossing a year boundary is out of scope.

    AND NOT EVERY COMMIT HAS A LINE. A commit whose audio the batch VAD filter empties
    returns before any encode runs (`whisper_jni.cpp:815-820`, "skipping whisper entirely
    makes silence-only commits ... essentially free"), so a silence-only cap cut — a paused
    video, a music bed the VAD rejects — leaves NO `encode:` line. The owner's own
    `capture-yt-2000-0903-1207.txt` shows it: two encode gaps of 128 s and 66 s in a
    session whose wall cap is 15 s. The simulator counts every commit, so the phone's
    sequence is a SUBSET of the simulator's, and index pairing drifts by one pair per
    silent commit from that point on. Pair counts and deltas must be read with that in
    mind; they cannot be made to agree by any tuning.
    """
    out: List[PhoneEncode] = []
    for line in text.splitlines():
        m = _LOGCAT_RE.match(line.strip())
        if not m or not m.group("msg").startswith("encode:"):
            continue
        t = datetime(
            2000, int(m.group("mon")), int(m.group("day")),
            int(m.group("h")), int(m.group("m")), int(m.group("s")),
            int(m.group("ms")) * 1000,
        )
        dur = re.search(r"in\s+([0-9.]+)\s*ms", m.group("msg"))
        out.append(
            PhoneEncode(
                t_ms=_ordinal_ms(t),
                encode_ms=float(dur.group(1)) if dur else float("nan"),
                raw=line.strip(),
            )
        )
    return out


def parse_phone_capture_file(path: str) -> List[PhoneEncode]:
    from pathlib import Path

    return parse_phone_capture(Path(path).read_text(encoding="utf-8", errors="replace"))


def intervals_ms(values: Sequence[int]) -> List[int]:
    """Consecutive differences: N timestamps give N-1 intervals."""
    return [int(values[i] - values[i - 1]) for i in range(1, len(values))]


@dataclass
class PhoneAlignment:
    """The simulator's commits beside the phone's `encode:` lines, ALIGNED AT THE FIRST of
    each. Index-paired: pair `k` is the phone's k-th commit and the simulator's k-th.

    `delta_ms[k]` is `(sim_t[k] - sim_t[0]) - (phone_t[k] - phone_t[0])` — positive means
    the simulator cut LATER than the phone did, relative to their own first cuts.
    """

    n_phone: int
    n_sim: int
    n_pairs: int
    phone_intervals_ms: List[int]
    sim_intervals_ms: List[int]
    delta_ms: List[int]
    within_1s: int
    within_3s: int
    unmatched: int
    phone_first_ms: int
    sim_first_ms: int

    def to_dict(self) -> dict:
        return asdict(self)


def align_phone_and_sim(
    encodes: Sequence[PhoneEncode], result: SimResult
) -> Optional[PhoneAlignment]:
    """Index-pair the two commit sequences from their first commit and score the deltas.

    Pairing by INDEX is the honest choice for a cross-check: it makes a missing or an
    extra commit show up as a growing delta instead of being absorbed by a
    nearest-neighbour match. A pair inside 1 s is matched, inside 3 s loosely matched, and
    UNMATCHED counts both the pairs outside 3 s and the surplus commits one side has and
    the other does not.
    """
    if not encodes or not result.commits:
        return None
    phone = [e.t_ms for e in encodes]
    sim = [c.t_ms for c in result.commits]
    k = min(len(phone), len(sim))
    deltas = [int((sim[i] - sim[0]) - (phone[i] - phone[0])) for i in range(k)]
    return PhoneAlignment(
        n_phone=len(phone),
        n_sim=len(sim),
        n_pairs=k,
        phone_intervals_ms=intervals_ms(phone),
        sim_intervals_ms=intervals_ms(sim),
        delta_ms=deltas,
        within_1s=sum(1 for d in deltas if abs(d) <= 1_000),
        within_3s=sum(1 for d in deltas if abs(d) <= 3_000),
        unmatched=abs(len(phone) - len(sim)) + sum(1 for d in deltas if abs(d) > 3_000),
        phone_first_ms=phone[0],
        sim_first_ms=sim[0],
    )
