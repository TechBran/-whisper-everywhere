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
"""

from __future__ import annotations

from dataclasses import dataclass, asdict
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
            reached = bool(parts & {"vad", "merge", "discard"})
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
    total = len(result.commits)
    return {
        "commits": total,
        "vad": n_vad,
        "cap": n_cap,
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
