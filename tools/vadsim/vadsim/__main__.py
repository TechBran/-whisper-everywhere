"""`python -m vadsim <wav> [options]` — the endpointer report, as Markdown or JSON."""

from __future__ import annotations

import argparse
import json
import sys
from dataclasses import replace
from typing import List, Optional, Sequence

from . import analyze, machine, probe as probe_mod
from .machine import FRAME_MS, BASE_MS, Tuning


def _tier_floor(tier: Optional[str]) -> int:
    """`CommitCadencePolicy.minCommitIntervalMs` (CommitCadencePolicy.kt:162-209)."""
    return {
        "eco": 1_200,
        "base": 1_200,
        "npu": 1_200,
        "npu-turbo": 2_000,
        "pro": 6_000,
        "multi": 6_000,
        "extreme": 8_000,
        "ultra": 8_000,
        "cloud": 3_000,
    }.get(tier or "npu-turbo", 8_000)


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        prog="python -m vadsim",
        description=(
            "Offline simulator for Whisper Everywhere's streaming endpointer. Feeds a local "
            "wav through Silero VAD frame by frame and replays the app's own state machine "
            "and 15 s cap over the resulting probabilities."
        ),
    )
    p.add_argument("wav", help="path to a local wav (any rate; resampled to 16 kHz mono)")

    g = p.add_argument_group("tuning (defaults = the shipped 4.3.1 table)")
    g.add_argument("--onset", type=float, default=None, help="ONSET_THRESHOLD (0.50)")
    g.add_argument("--release", type=float, default=None, help="RELEASE_THRESHOLD (0.35)")
    g.add_argument("--hangover", type=int, default=None, help="HANGOVER_MS (350)")
    g.add_argument("--min-speech", type=int, default=None, help="MIN_SPEECH_MS (300)")
    g.add_argument("--micro-pause", type=int, default=None, help="MICRO_PAUSE_MS (98)")
    g.add_argument("--floor", type=int, default=None,
                   help="minCommitIntervalMs; overrides --tier (npu-turbo = 2000)")
    g.add_argument("--tier", default="npu-turbo",
                   help="tier id for the cadence floor (eco|base|npu|npu-turbo|pro|multi|"
                        "extreme|ultra|cloud)")
    g.add_argument("--first-cap", type=int, default=None, help="FIRST_SEGMENT_WALL_MS (4000)")
    g.add_argument("--cap", type=int, default=None, help="MAX_SEGMENT_WALL_MS (15000)")
    g.add_argument("--cap-retain", type=int, default=None,
                   help="CAP_CUT_MAX_RETAIN_MS (3000)")
    g.add_argument("--cloud", action="store_true",
                   help="cloud session: the 4 s first-cap window is closed at onOpen AND the "
                        "cadence floor is the flat 3000 ms request floor for every tier "
                        "(CommitCadencePolicy.kt:163); --floor overrides")

    x = p.add_argument_group(
        "the FLATLINE trigger — a PROPOSAL, default OFF (machine.SileroEndpointerSim._on_flat)"
    )
    x.add_argument("--flatline-rms", type=int, default=None,
                   help="ENABLES the trigger. Chunk-RMS floor in AudioMath's 0..32767 "
                        f"units; a frame strictly below it is 'flat' (default when only "
                        f"--flatline-hold is given: {machine.DEFAULT_TUNING.flatline_rms})")
    x.add_argument("--flatline-hold", type=int, default=None,
                   help="ENABLES the trigger. How long the flat run must hold before it "
                        "closes the utterance, measured exactly as the hangover measures a "
                        "dip (default when only --flatline-rms is given: "
                        f"{machine.DEFAULT_TUNING.flatline_hold_ms})")
    x.add_argument("--no-flat-sweep", action="store_true",
                   help="skip the 26-row flatline sweep (section 12)")
    x.add_argument("--phone-capture", default=None,
                   help="a threadtime logcat txt: print the phone's inter-commit intervals "
                        "(from the native `encode:` lines) beside the simulator's for the "
                        "same audio, aligned at the first commit. A CROSS-CHECK, not a fit")

    f = p.add_argument_group("front end")
    f.add_argument("--chunk-ms", type=int, default=probe_mod.MIC_CHUNK_MS,
                   help="capture chunk the RMS is measured over; 32 on BOTH of the app's "
                        "paths (mic StreamingAudioRecorder.kt:80, device audio "
                        "PlaybackAudioCapturer.kt:64 + the 3:1 decimator)")
    f.add_argument("--resample", choices=("auto", "sinc", "device48k"), default="auto",
                   help="auto = the app's own 3-tap decimator for 48 kHz input, sinc otherwise")
    f.add_argument("--context", choices=probe_mod.CONTEXT_MODES, default="carry",
                   help="Silero left-context mode; see the model-delta note in the report")
    f.add_argument("--model", default=None, help="path to a silero_vad.onnx to use instead")
    f.add_argument("--base-ms", type=int, default=BASE_MS,
                   help="wall-clock BASE the frames are stamped from (JVM fixtures use 1000000)")

    o = p.add_argument_group("output")
    o.add_argument("--json", action="store_true", help="machine-readable output")
    o.add_argument("--no-sweep", action="store_true", help="skip the 36-row sweep")
    o.add_argument("--coupled", action="store_true",
                   help="also run the HONEST simulation, resetting the probe LSTM on every "
                        "commit as the app does, and report the delta")
    o.add_argument("--max-dips", type=int, default=25,
                   help="longest N dips to list in the dips table (0 = all)")
    o.add_argument("--save-trace", default=None, help="write the p-trace to this CSV")
    o.add_argument("--load-trace", default=None,
                   help="read the p-trace from a CSV instead of probing the wav")
    return p


#: `CommitCadencePolicy.MIN_COMMIT_INTERVAL_CLOUD_MS` (CommitCadencePolicy.kt:139).
CLOUD_FLOOR_MS = 3_000


def tuning_from_args(a: argparse.Namespace) -> Tuning:
    # `minCommitIntervalMs(tierId, isCloudBatch)`: "Cloud batch wins outright — a FLAT 3 000
    # for every tier" (CommitCadencePolicy.kt:151, the `if (isCloudBatch) return` at :163). The
    # service passes `isCloudBatch = cloudWrapper != null`, the same predicate `--cloud`
    # models, so a cloud session's floor is 3 000 whatever --tier says. --floor still wins.
    if a.floor is not None:
        floor = a.floor
    elif a.cloud:
        floor = CLOUD_FLOOR_MS
    else:
        floor = _tier_floor(a.tier)
    # THE FLATLINE TRIGGER IS OPT-IN, AND EITHER FLAG OPTS IN. Naming one constant and
    # leaving the other at its default is the common case ("what does a hold of 224 do?"),
    # so requiring both would make every such run a two-flag ritual. Naming NEITHER leaves
    # `flatline_enabled = False`, which is what makes a bare run byte-identical to 50d7466.
    flat_on = a.flatline_rms is not None or a.flatline_hold is not None
    return machine.with_overrides(
        Tuning(),
        onset=a.onset,
        release=a.release,
        hangover_ms=a.hangover,
        min_speech_ms=a.min_speech,
        micro_pause_ms=a.micro_pause,
        min_commit_interval_ms=floor,
        first_cap_ms=a.first_cap,
        cap_ms=a.cap,
        cap_cut_max_retain_ms=a.cap_retain,
        flatline_enabled=flat_on or None,
        flatline_rms=a.flatline_rms,
        flatline_hold_ms=a.flatline_hold,
        chunk_ms=a.chunk_ms,
    )


# ---------------------------------------------------------------------------------------
# Markdown
# ---------------------------------------------------------------------------------------

def _table(headers: Sequence[str], rows: Sequence[Sequence[object]]) -> str:
    out = ["| " + " | ".join(headers) + " |",
           "|" + "|".join("---" for _ in headers) + "|"]
    for r in rows:
        out.append("| " + " | ".join(str(c) for c in r) + " |")
    return "\n".join(out)


def _ms(v: Optional[int]) -> str:
    return "—" if v is None else f"{v:,}"


def render_markdown(a: argparse.Namespace, t: Tuning, trace, result, dips, hist,
                    forensics, sweep_rows, coupled, rms_hist=None, dip_rms_rows=None,
                    cap_flat_rows=None, flat_rows=None, phone=None,
                    flat_hist_rows=None) -> str:
    L: List[str] = []
    add = L.append

    add("# vadsim — endpointer report")
    add("")
    add(f"`{trace.path}`")
    add("")

    # 1 -----------------------------------------------------------------------------
    add("## 1. Input")
    add("")
    add(_table(
        ["field", "value"],
        [
            ["source format", trace.source_format],
            ["source rate", f"{trace.source_rate:,} Hz"],
            ["resample", f"{trace.resample_mode}"
                         + (" (the app's own Pcm48kTo16kDecimator, 3-tap boxcar)"
                            if trace.resample_mode == "device48k" else " -> 16 kHz PCM16")],
            ["frames (512 samples / 32 ms)", f"{trace.n_frames:,}"],
            ["wall time", f"{trace.wall_ms / 1000:.2f} s"],
            ["silero-vad package", trace.package_version],
            ["onnx model", trace.model_path],
            ["left-context mode", trace.context_mode],
        ],
    ))
    add("")
    add("<details><summary>Silero version delta — read before quoting an absolute p</summary>")
    add("")
    add("```")
    add(probe_mod.SILERO_DELTA.rstrip())
    add("```")
    add("</details>")
    add("")

    # 2 -----------------------------------------------------------------------------
    add("## 2. Tuning in force")
    add("")
    add(_table(
        ["knob", "value", "derived"],
        [
            ["ONSET_THRESHOLD", f"{t.onset:.2f}", ""],
            ["RELEASE_THRESHOLD", f"{t.release:.2f}",
             f"dead band = [{t.release:.2f}, {t.onset:.2f})"],
            ["HANGOVER_MS", f"{t.hangover_ms}",
             f"cuts on dip frame {t.hangover_frames()} "
             f"({t.hangover_frames() * FRAME_MS} ms of quiet audio), "
             f"reported trailMs = {t.hangover_trail_ms()}"],
            ["MIN_SPEECH_MS", f"{t.min_speech_ms}",
             f"a run must exceed {t.min_speech_ms} ms or it is DISCARDED"],
            ["MICRO_PAUSE_MS", f"{t.micro_pause_ms}",
             f"promotes on dip frame {t.micro_pause_frames()}"],
            ["minCommitIntervalMs", f"{t.min_commit_interval_ms}",
             ("cloud batch: the flat request floor, every tier (CommitCadencePolicy.kt:163)"
              if a.cloud and a.floor is None else f"tier {a.tier}")
             + "; endpoints inside this window MERGE"],
            ["FIRST_SEGMENT_WALL_MS", f"{t.first_cap_ms}",
             "closed at onOpen in a cloud session" if a.cloud else "local session"],
            ["MAX_SEGMENT_WALL_MS", f"{t.cap_ms}", "the dump"],
            ["CAP_CUT_MAX_RETAIN_MS", f"{t.cap_cut_max_retain_ms}",
             "an offer older than this is stale -> retain 0"],
            ["FLATLINE (proposal)",
             "ON" if t.flatline_enabled else "**off** (shipped behaviour)",
             (f"rms < {t.flatline_rms} held {t.flatline_hold_ms} ms -> "
              f"fires on flat frame {t.flatline_frames()}; the hold rounds to "
              f"{t.flatline_hold_chunks()} chunk(s) of {t.chunk_ms} ms = "
              f"{t.flatline_effective_hold_ms()} ms"
              if t.flatline_enabled
              else "nothing in the app implements it; --flatline-rms/--flatline-hold "
                   "turn it on here for measurement only")],
        ],
    ))
    add("")
    if t.flatline_enabled:
        add(f"**THE FLATLINE TRIGGER IS ON — this is a PROPOSAL, not the app.** It is "
            f"evaluated only after Silero's `onProb` has declined a frame, so a hangover "
            f"close and a flat hold coming due together produce ONE commit and it is "
            f"Silero's. One RMS covers a whole {t.chunk_ms} ms chunk "
            f"(`AudioMath.amplitude` per capture buffer — `StreamingAudioRecorder.kt:87`, "
            f"`PlaybackAudioCapturer.kt:81`), so the hold can only be satisfied in whole "
            f"chunks: **{t.flatline_hold_ms} ms resolves to "
            f"{t.flatline_effective_hold_ms()} ms**, and two holds inside one chunk cannot "
            f"be told apart.")
        add("")
        add(f"**What this hold actually needs: {t.flatline_fire_chunks()} consecutive flat "
            f"chunks** ({t.flatline_fire_chunks() * t.chunk_ms} ms of flat audio — the run's "
            f"first chunk is age 0). A gap of digital silence supplies that many whole chunks "
            f"only when it is **>= {t.flatline_gap_aligned_ms()} ms AND starts on a chunk "
            f"boundary**; at an arbitrary alignment against the capture grid — which is "
            f"where a real editor's gate falls — it must be **>= {t.flatline_gap_any_ms()} "
            f"ms** to be certain, because the gap's first and last chunks straddle speech "
            f"and one millisecond of speech in a chunk already reads hundreds of RMS. "
            f"Compare that number, not the knob, against the 100-300 ms an editor leaves. "
            f"A port should count **{t.flatline_fire_chunks()} chunks** rather than "
            f"re-deriving a wall-clock hold: the phone stamps chunks with "
            f"`System.currentTimeMillis()` at delivery, which is bursty, and a hold that is "
            f"an exact multiple of {t.chunk_ms} sits on a band edge there.")
        add("")
        if t.chunk_ms != FRAME_MS:
            add(f"**`--chunk-ms {t.chunk_ms}` LIMITATION.** The RMS grouping is modelled at "
                f"this chunk size but the clock is not: this simulator stamps every frame "
                f"32 ms apart, while the app stamps ONE `nowMs` on every frame of a chunk "
                f"(`FloatingBubbleService.kt:2009`). The hold can therefore fire up to one "
                f"chunk EARLIER here than on the device (a 96 ms hold at a 128 ms chunk fires "
                f"inside the first flat chunk here and needs a second on the phone). Both of "
                f"the app's paths deliver 32 ms chunks; use this flag to reason, not to "
                f"choose.")
            add("")
    add(f"**A pause must hold {t.hangover_frames()} consecutive frames below "
        f"{t.release:.2f} — {t.hangover_frames() * FRAME_MS} ms of audio — before the "
        f"hangover can cut.** Anything shorter, and anything that never drops below "
        f"{t.release:.2f} at all, is invisible to it.")
    add("")
    band_lo = (t.hangover_frames() - 2) * FRAME_MS
    band_hi = (t.hangover_frames() - 1) * FRAME_MS
    add(f"On this simulator's exact 32 ms grid **every HANGOVER_MS in ({band_lo}, {band_hi}] "
        f"behaves identically** to {t.hangover_ms}: the guard `nowMs - tempEndMs < HANGOVER_MS` "
        f"(SileroEndpointer.kt:579) is only ever evaluated at multiples of 32 ms from the "
        f"pending end. Two values inside one band cannot be told apart here. The phone stamps "
        f"`nowMs` on the CHUNK (`System.currentTimeMillis()` at `onAudioChunk`), so its frames "
        f"sit a few ms off the grid and a value near a band edge ({band_hi} here) will sometimes "
        f"cut one frame later than this report says — never earlier.")
    add("")

    # 3 -----------------------------------------------------------------------------
    s = analyze.prob_summary(trace.probs, t)
    add("## 3. The p-trace")
    add("")
    if s:
        add(_table(
            ["metric", "value"],
            [
                ["frames", f"{int(s['n_frames']):,}"],
                ["no-verdict frames (p < 0)", f"{int(s['n_no_verdict']):,}"],
                ["mean p", f"{s['mean']:.3f}"],
                ["p05 / p50 / p95", f"{s['p05']:.3f} / {s['p50']:.3f} / {s['p95']:.3f}"],
                ["min / max", f"{s['min']:.3f} / {s['max']:.3f}"],
                [f"frames at or above ONSET ({t.onset:.2f})",
                 f"{s['frac_at_or_above_onset'] * 100:.1f} %"],
                [f"**DEAD BAND** [{t.release:.2f}, {t.onset:.2f})",
                 f"**{s['frac_dead_band'] * 100:.1f} %**"],
                [f"frames below RELEASE ({t.release:.2f})",
                 f"{s['frac_below_release'] * 100:.1f} %"],
            ],
        ))
    add("")

    # 4 -----------------------------------------------------------------------------
    n_dead = sum(1 for d in dips if d.kind == "dead-band")
    n_quiet = len(dips) - n_dead
    n_cuttable = sum(1 for d in dips if d.cut_ms is not None)
    n_shut = sum(1 for d in dips if not d.gate_open)
    n_capped = sum(1 for d in dips if d.gate_open and "cap" in d.outcome.split("+")
                   and d.cut_ms is None)
    by_outcome = {}
    for d in dips:
        by_outcome[d.outcome] = by_outcome.get(d.outcome, 0) + 1
    add("## 4. Dips — every stretch where the gate is not being held open")
    add("")
    add(f"{len(dips)} dips below ONSET: **{n_quiet} true silence**, "
        f"**{n_dead} dead-band** (never went below {t.release:.2f}, so no pending end is "
        f"ever stamped — SileroEndpointer.kt:555 returns having written nothing). "
        f"{n_shut} began with the gate already SHUT and are structurally uncuttable "
        f"(SileroEndpointer.kt:559). **{n_cuttable} dip(s) actually reach the hangover** "
        f"(outcomes: " + ", ".join(f"{k or 'none'} {v}" for k, v in sorted(by_outcome.items()))
        + f"). {n_capped} pause(s) had the CAP fire into them before the hangover elapsed.")
    add("")
    listed = sorted(dips, key=lambda d: -d.span_ms)
    if a.max_dips > 0:
        listed = listed[: a.max_dips]
    add(_table(
        ["start (ms)", "span (ms)", "frames", "kind", "min p", "quiet frames",
         "max age (ms)", "gate", "outcome"],
        [
            [f"{d.start_ms - trace_base(a):,}", d.span_ms, d.n_frames, d.kind,
             f"{d.min_p:.3f}", d.below_release_frames, d.max_age_ms,
             "open" if d.gate_open else "SHUT", d.outcome]
            for d in listed
        ],
    ))
    add("")
    add("`start (ms)` is relative to the start of the audio. `quiet frames` is how many of "
        "the dip's frames were below RELEASE — the only ones the hangover counts. "
        "`max age (ms)` is the largest `nowMs - tempEndMs` the machine measured on this dip "
        "(the number the guard at SileroEndpointer.kt:579 compares against HANGOVER_MS), "
        "clipped at the frame the machine stopped measuring it. `gate` is whether an "
        "utterance was open when the dip began: with it SHUT the frame takes `onProb`'s "
        "`if (!speaking) return false` and no length of silence can cut. `outcome` is what "
        "the machine DID: `vad` (committed here), `merge` (reached the hangover, governor "
        "declined it), `discard` (reached the hangover, burst under MIN_SPEECH_MS), `cap` "
        "(the wall clock ran out INSIDE this pause, before the hangover — the cap's "
        "`endpointer.reset()` then kills the pending end), `none` (never reached it) or "
        "`gate-shut`.")
    add("")

    # 5 -----------------------------------------------------------------------------
    add("## 5. Pause-length histogram")
    add("")
    add(_table(
        ["bucket (ms)", "dips by full span", "of those, dead-band", "dips by QUIET span"],
        [[h.label, h.below_onset, h.dead_band, h.quiet_span] for h in hist],
    ))
    add("")
    add(f"The left column is how long a listener would call the pause. The right column is "
        f"how much of it the machine can count. The line that decides everything sits "
        f"between `320-384` and `384-512`: at HANGOVER_MS = {t.hangover_ms} a pause needs "
        f"{t.hangover_frames() * FRAME_MS} ms of QUIET span to cut. Every dip in the right "
        f"column above that boundary is a cut the endpointer can make; everything below it "
        f"rides the cap.")
    add("")

    # 6 -----------------------------------------------------------------------------
    summ = analyze.summarise(result, trace.probs)
    add("## 6. Commits — the default simulation")
    add("")
    add(_table(
        ["metric", "value"],
        [
            ["commits", int(summ["commits"])],
            ["VAD cuts", f"{int(summ['vad'])} ({summ['vad_pct']:.0f} %)"],
            ["CAP cuts", f"{int(summ['cap'])} ({summ['cap_pct']:.0f} %)"],
            ["FLAT cuts (the proposal)",
             f"{int(summ['flat'])} ({summ['flat_pct']:.0f} %)"
             + ("" if t.flatline_enabled else " — trigger off")],
            ["mean chunk", f"{summ['mean_chunk_ms']:.0f} ms"],
            ["p95 chunk", f"{summ['p95_chunk_ms']:.0f} ms"],
            ["governor merges", int(summ["merges"])],
            ["MIN_SPEECH discards", int(summ["discards"])],
            ["uncommitted tail at end of trace",
             f"{int(summ['tail_ms'])} ms (the stop flush takes it)"],
            ["estimated turbo duty", f"{summ['turbo_duty'] * 100:.0f} %"],
        ],
    ))
    add("")
    if result.commits:
        add(_table(
            ["t (ms)", "kind", "chunk (ms)", "speech (ms)", "trail (ms)", "retain (ms)",
             "merged inside", "discarded inside", "cap", "p", "rms"],
            [
                [f"{c.t_ms - trace_base(a):,}", c.kind, f"{c.chunk_ms:,}",
                 _ms(c.speech_ms), _ms(c.trail_ms), c.retain_ms,
                 c.merged_endpoints_inside, c.discarded_bursts_inside, _ms(c.cap_ms),
                 "—" if c.prob is None else f"{c.prob:.3f}", _ms(c.rms)]
                for c in result.commits
            ],
        ))
    else:
        add("_No commit at all in this trace: the stop flush is the only exit._")
    add("")

    # 7 -----------------------------------------------------------------------------
    add("## 7. Cap-cut forensics — what would it have taken to cut this chunk?")
    add("")
    if not forensics:
        add("_No cap cut fired._")
    else:
        add(_table(
            ["t (ms)", "cap", "chunk (ms)", "retain", "dips", "gate shut",
             "longest quiet (ms)", "best age (ms)", "frames short",
             "longest dead-band (ms)", "merged", "discarded"],
            [
                [f"{f.t_ms - trace_base(a):,}", _ms(f.cap_ms), f"{f.chunk_ms:,}",
                 f.retain_ms, f.n_dips, f.n_gate_shut, f.longest_silence_ms, f.best_age_ms,
                 _ms(f.frames_short), f.longest_dead_band_ms,
                 f.merged_inside, f.discarded_inside]
                for f in forensics
            ],
        ))
        add("")
        for f in forensics:
            add(f"* **cap @ {f.t_ms - trace_base(a):,} ms** — {f.verdict}")
    add("")

    # 8 -----------------------------------------------------------------------------
    if sweep_rows:
        add("## 8. Sweep — hangover x release x cap at the turbo floor "
            f"({t.min_commit_interval_ms} ms)")
        add("")
        add(_table(
            ["hangover", "release", "cap", "commits", "vad %", "cap %", "mean chunk",
             "p95 chunk", "merges", "discards", "dead band", "turbo duty"],
            [
                [r.hangover_ms, f"{r.release:.2f}", r.cap_ms, r.commits,
                 f"{r.vad_pct:.0f}", f"{r.cap_pct:.0f}", f"{r.mean_chunk_ms:.0f}",
                 f"{r.p95_chunk_ms:.0f}", r.merges, r.discards,
                 f"{r.dead_band_frac * 100:.1f} %",
                 f"{r.turbo_duty * 100:.0f} %"]
                for r in sweep_rows
            ],
        ))
        add("")
        add("`turbo duty` is `commits x 2 050 ms / wall time` — the arithmetic "
            "`CommitCadencePolicy.MIN_COMMIT_INTERVAL_TURBO_MS`'s KDoc states "
            "(CommitCadencePolicy.kt:76-91), applied to THIS audio. Over 100 % means the "
            "segment queue grows on this clip; the shipped 2 000 ms floor is 98 % at "
            "SATURATION, which this clip need not reach.")
        add("")
        add("`dead band` moves with `release` and nothing else: it is the fraction of all "
            "frames parked between the two thresholds, and lowering RELEASE is the only "
            "knob here that shrinks it.")
        add("")

    # 9 -----------------------------------------------------------------------------
    if coupled is not None:
        cres, cprobs = coupled
        csum = analyze.summarise(cres, cprobs)
        add("## 9. Coupled run — the probe LSTM reset on every commit, as the app does")
        add("")
        add(_table(
            ["metric", "fixed trace", "coupled"],
            [
                ["commits", int(summ["commits"]), int(csum["commits"])],
                ["VAD cuts", int(summ["vad"]), int(csum["vad"])],
                ["CAP cuts", int(summ["cap"]), int(csum["cap"])],
                ["mean chunk (ms)", f"{summ['mean_chunk_ms']:.0f}",
                 f"{csum['mean_chunk_ms']:.0f}"],
                ["merges", int(summ["merges"]), int(csum["merges"])],
                ["discards", int(summ["discards"]), int(csum["discards"])],
            ],
        ))
        add("")
        add("A large delta here means the sweep above is directionally right but not "
            "quantitatively transferable: the commit pattern feeds back into the "
            "probabilities through `probeReset` (SileroEndpointer.kt:664).")
        add("")

    # 10 ----------------------------------------------------------------------------
    if rms_hist is not None:
        add("## 10. RMS — the amplitude the endpointer ignores")
        add("")
        add(f"One `AudioMath.amplitude` per {trace.chunk_ms} ms capture chunk "
            f"(`AudioMath.kt:21-36`), applied to every 512-sample frame that chunk "
            f"completes. The app computes exactly this number and hands it to "
            f"`endpointer.onFrame(chunk, amp, now)`, which **ignores it** "
            f"(`SileroEndpointer.kt:245`) — and feeds the same number to the waveform "
            f"(`FloatingBubbleService.kt:1998`). That is why the bubble can visibly "
            f"flatline where nothing gets cut.")
        add("")
        add(_table(
            ["rms bucket", "speech (p>=%.2f)" % t.onset, "dead band", "silence", "no verdict",
             "total"],
            [[r.label, r.speech, r.dead_band, r.silence, r.no_verdict, r.total]
             for r in rms_hist],
        ))
        add("")
        add("**Read the `speech` column first.** A candidate `flatline_rms` is only safe "
            "where that column is empty below it: a speech frame under the threshold is a "
            "mid-word cut waiting to happen, and `no_context = true` "
            "(`whisper_jni.cpp:846`) makes such a cut unrepairable. Natural room tone "
            "measures 50-300 here; an editor's gate measures 0.")
        add("")
        if dip_rms_rows:
            listed = sorted(dip_rms_rows, key=lambda d: -d.span_ms)
            if a.max_dips > 0:
                listed = listed[: a.max_dips]
            add(_table(
                ["start (ms)", "span (ms)", "kind", "gate", "outcome", "quiet frames",
                 "min rms (quiet)", "median rms (quiet)", "min rms (all)",
                 "median rms (all)"],
                [[f"{d.start_ms - trace_base(a):,}", d.span_ms, d.kind,
                  "open" if d.gate_open else "SHUT", d.outcome, d.quiet_frames,
                  _ms(d.min_rms), _ms(d.median_rms), _ms(d.min_rms_all),
                  _ms(d.median_rms_all)]
                 for d in listed],
            ))
            add("")
            add("`min rms (quiet)` / `median rms (quiet)` are over the dip's "
                "below-RELEASE frames only — the ones the hangover counts. The `(all)` "
                "pair is over the whole dip, so a dead-band dip (no quiet frames at all) "
                "still reports an amplitude.")
            add("")

    # 11 ----------------------------------------------------------------------------
    if cap_flat_rows is not None:
        add("## 11. Flat runs — what each hold has to work with")
        add("")
        if flat_hist_rows:
            labels = list(flat_hist_rows[0].counts)
            add(_table(
                ["threshold", "runs"] + [f"{lb} fr" for lb in labels]
                + [f">={k} (hold {(k - 1) * FRAME_MS})" for k in (4, 5, 6, 8, 11)],
                [[("==0" if r.threshold <= 0 else f"<{r.threshold}"), r.n_runs]
                 + [r.counts[lb] for lb in labels]
                 + [r.at_least[k] for k in (4, 5, 6, 8, 11)]
                 for r in flat_hist_rows],
            ))
            add("")
            add("Every maximal run of consecutive frames under the threshold, over the WHOLE "
                "clip, by length in frames (32 ms each). The right-hand columns are the "
                "runs a hold needing that many flat chunks can fire on — `>=5` is what a "
                "128 ms hold sees, `>=4` a 96 ms hold. Runs of `>=12` are the ones Silero's "
                "own 350 ms hangover already cuts when `p` also drops (it does on digital "
                "silence). The band a hold BUYS is therefore the runs between its column and "
                "`>=12`; a threshold whose short runs (1-3) are numerous while the speech "
                "column of section 10 is non-empty under it is reading soft speech, not gates.")
            add("")
        add("### Per CAP chunk")
        add("")
        if not cap_flat_rows:
            add("_No cap cut fired._")
        else:
            cands = list(analyze.FLAT_CANDIDATE_RMS)
            add(_table(
                ["t (ms)", "cap", "chunk (ms)", "frames", "min rms"]
                + [("==0" if c <= 0 else f"<{c}") for c in cands],
                [[f"{r.t_ms - trace_base(a):,}", _ms(r.cap_ms), f"{r.chunk_ms:,}",
                  r.n_frames, _ms(r.min_rms)]
                 + [f"{r.runs[c]}f / {r.runs_ms[c]}ms" for c in cands]
                 for r in cap_flat_rows],
            ))
            add("")
            add(f"Each threshold column is the LONGEST run of consecutive frames under it "
                f"inside that chunk — what a flat hold would have had to work with. A hold "
                f"of H ms needs `H/32 + 1` frames, so at 128 ms that is 5 frames "
                f"(160 ms of audio). The `==0` column is exact digital silence: a strict "
                f"`rms < 0` can never fire, so 0 is reported as the zero run instead.")
            add("")

    # 12 ----------------------------------------------------------------------------
    if flat_rows:
        add("## 12. Flatline sweep — `flatline_rms` x `hold`, with MID-WORD RISK")
        add("")
        add(_table(
            ["flatline_rms", "hold", "flat chunks", "gap (any align)", "commits", "flat %",
             "vad %", "cap %", "mean chunk", "p95 chunk", "duty", "RISK (p, 3 frames)",
             "SPLITS", "**BRIDGED**", "**BRIDGED, not digital 0**", "flat merges",
             "flat discards"],
            [
                [("**OFF (baseline)**" if not r.enabled else r.flatline_rms),
                 _ms(r.hold_ms),
                 "—" if not r.enabled else replace(
                     t, flatline_hold_ms=r.hold_ms).flatline_fire_chunks(),
                 "—" if not r.enabled else _ms(replace(
                     t, flatline_hold_ms=r.hold_ms).flatline_gap_any_ms()),
                 r.commits,
                 f"{r.flat_pct:.0f}", f"{r.vad_pct:.0f}", f"{r.cap_pct:.0f}",
                 f"{r.mean_chunk_ms:.0f}", f"{r.p95_chunk_ms:.0f}",
                 f"{r.turbo_duty * 100:.0f} %",
                 r.mid_word_risk, r.mid_word_splits,
                 r.bridged,
                 f"**{r.bridged_nonzero}**" if r.bridged_nonzero else "0",
                 r.flat_merges, r.flat_discards]
                for r in flat_rows
            ],
        ))
        add("")
        add("The first row is the SHIPPED machine with the trigger off — every other row "
            "is a delta against it. Hangover, release and cap stay at their defaults "
            f"throughout, at the {t.min_commit_interval_ms} ms cadence floor. `flat "
            "chunks` is how many consecutive flat chunks the hold needs; `gap (any align)` "
            "is the shortest digital-silence gap that reliably supplies them when the gap "
            "does not start on a chunk boundary (a real editor's gate does not).")
        add("")
        add("**BRIDGED, not digital 0 is the column that decides whether any of this "
            "ships.** `BRIDGED` counts flat cuts after which Silero speech (`p >= %.2f`) "
            "resumes within the hangover (%d frames) — boundaries the shipped machine would "
            "NOT have made. On edited audio that is every intended cut, so the count is "
            "expected to equal the flat cuts; what separates a good bridge from a bad one "
            "is the audio it bridged. **`BRIDGED, not digital 0`** is the subset whose flat "
            "run was not exact silence — a boundary Silero would not have made, across "
            "audio that was not an editor's gate: a soft word, a breath, room tone under a "
            "threshold set too high. `no_context = true` (`whisper_jni.cpp:846`) makes such "
            "a cut unrepairable. Any value above 0 here rejects the row; on the phone a "
            "decoded silent stream may read 1-3 RMS rather than 0, so also read the run "
            "maxima in the JSON before trusting a 0." % (t.onset, t.hangover_frames()))
        add("")
        add("`RISK (p, 3 frames)` and `SPLITS` are kept for continuity but are WEAK "
            "instruments: they read `p` on the cut frame and its neighbours, and on flat "
            "audio `p` is ~0 whether the flatness is an editor's gate or a soft stretch "
            "inside a word — so they read 0 by construction on gated audio, miss a "
            "dead-band soft segment inside a word entirely, and flip with hold parity "
            "(a 96 ms hold reads 0 and a 128 ms hold reads N-1 on the same gaps). They "
            "are not evidence of safety.")
        add("")

    # 13 ----------------------------------------------------------------------------
    if phone is not None:
        encodes, align = phone
        add("## 13. Phone cross-check — the `encode:` lines beside the simulation")
        add("")
        add(f"`{a.phone_capture}` — {len(encodes)} native `encode:` line(s), "
            f"{max(0, len(encodes) - 1)} interval(s).")
        add("")
        add("**A CROSS-CHECK, NOT A FIT.** The timestamp on an `encode:` line is when the "
            "encode FINISHED, not when the endpointer cut: a commit precedes its line by "
            "the encode cost (~1.78 s on npu-turbo, near constant because the input is a "
            "fixed 30 s window) plus any queue wait. Only the INTERVALS survive that "
            "offset, and only while the queue is not backing up. Nothing here is tuned to "
            "make the two columns agree.")
        add("")
        if align is None:
            add("_No commit on one of the two sides — nothing to align._")
        else:
            add(_table(
                ["metric", "value"],
                [
                    ["phone commits (`encode:` lines)", align.n_phone],
                    ["simulator commits", align.n_sim],
                    ["index-paired", align.n_pairs],
                    ["matched within 1 s", align.within_1s],
                    ["matched within 3 s", align.within_3s],
                    ["unmatched (outside 3 s, plus the surplus)", align.unmatched],
                ],
            ))
            add("")
            rows = []
            for k in range(align.n_pairs):
                rows.append([
                    k,
                    f"{align.phone_intervals_ms[k - 1]:,}" if k else "—",
                    f"{align.sim_intervals_ms[k - 1]:,}" if k else "—",
                    f"{align.delta_ms[k]:+,}",
                ])
            add(_table(
                ["#", "phone interval (ms)", "sim interval (ms)",
                 "cumulative delta (ms)"],
                rows,
            ))
            add("")
            add("`cumulative delta` is `(sim - sim[0]) - (phone - phone[0])`: positive "
                "means the simulator cut LATER than the phone, relative to each side's own "
                "first commit. A steadily growing delta means one side is producing commits "
                "the other is not — read the interval columns to see which.")
            add("")

    add("---")
    add("")
    add("Generated by `tools/vadsim`. The state machine is ported branch-by-branch from "
        "`SileroEndpointer.kt` and the cap branch from "
        "`FloatingBubbleService.kt:2008-2111`; every branch in `vadsim/machine.py` carries "
        "the Kotlin line it came from.")
    return "\n".join(L)


def trace_base(a: argparse.Namespace) -> int:
    return a.base_ms


def main(argv: Optional[Sequence[str]] = None) -> int:
    # The report is full of em-dashes and the Windows console defaults to cp1252, which
    # replaces them with `?` and makes a redirected report unreadable in a Markdown viewer.
    try:
        sys.stdout.reconfigure(encoding="utf-8", newline="\n")
    except Exception:                                    # pragma: no cover - non-tty stdout
        pass

    a = build_parser().parse_args(argv)
    t = tuning_from_args(a)

    if a.load_trace:
        probs, rms = probe_mod.load_trace_csv_full(a.load_trace)
        trace = probe_mod.Trace(
            probs=probs,
            path=f"{a.wav} (p-trace from {a.load_trace})",
            source_rate=16_000,
            source_format="p-trace csv",
            resample_mode="n/a",
            context_mode="n/a",
            model_path="n/a",
            package_version=probe_mod.silero_package_version(),
            rms=rms,
            chunk_ms=a.chunk_ms,
        )
        if t.flatline_enabled and not trace.has_rms:
            sys.stderr.write(
                f"vadsim: {a.load_trace} has no `rms` column, so the flatline trigger "
                "cannot fire on it — an unknown amplitude is never treated as flat. "
                "Re-save the trace (--save-trace) to get the column.\n"
            )
    else:
        trace = probe_mod.probe_wav(
            a.wav, resample=a.resample, context=a.context, model_path=a.model,
            chunk_ms=a.chunk_ms,
        )

    if a.save_trace:
        probe_mod.write_trace_csv(trace, a.save_trace, a.base_ms)

    # The RMS trace is passed to the machine ALWAYS and gated inside it by
    # `Tuning.flatline_enabled` — a default run therefore behaves exactly as it did
    # before the RMS existed, and there is one place (`_on_flat`'s first line) where that
    # is true rather than one per call site.
    rms_trace = trace.rms if trace.has_rms else None

    result = machine.simulate(
        trace.probs, t, base_ms=a.base_ms, is_cloud_session=a.cloud, rms=rms_trace
    )
    dips = analyze.find_dips(
        trace.probs, t, base_ms=a.base_ms, is_cloud_session=a.cloud
    )
    hist = analyze.pause_histogram(dips)
    forensics = analyze.cap_forensics(result, dips, t)
    sweep_rows = None if a.no_sweep else analyze.sweep(
        trace.probs, t, base_ms=a.base_ms, floor_ms=t.min_commit_interval_ms,
        is_cloud_session=a.cloud,
    )

    rms_hist = dip_rms_rows = cap_flat_rows = flat_rows = flat_hist_rows = None
    if rms_trace is not None:
        rms_hist = analyze.rms_histogram(trace.probs, rms_trace, t)
        dip_rms_rows = analyze.dip_rms(dips, trace.probs, rms_trace, t, base_ms=a.base_ms)
        cap_flat_rows = analyze.cap_chunk_flat_runs(result, rms_trace, base_ms=a.base_ms)
        flat_hist_rows = analyze.flat_run_histogram(rms_trace)
        if not a.no_flat_sweep:
            flat_rows = analyze.flat_sweep(
                trace.probs, rms_trace, t, base_ms=a.base_ms,
                floor_ms=t.min_commit_interval_ms, is_cloud_session=a.cloud,
            )

    phone = None
    if a.phone_capture:
        encodes = analyze.parse_phone_capture_file(a.phone_capture)
        phone = (encodes, analyze.align_phone_and_sim(encodes, result))

    coupled = None
    if a.coupled and not a.load_trace:
        frames, crms, _mode, _wav = probe_mod.frames_and_rms_from_wav(
            a.wav, resample=a.resample, chunk_ms=a.chunk_ms
        )
        p2 = probe_mod.SileroProbe(model_path=a.model, context=a.context)
        coupled = machine.simulate_coupled(
            frames, p2, t, base_ms=a.base_ms, is_cloud_session=a.cloud, rms=crms
        )

    if a.json:
        out = {
            "input": trace.to_dict(),
            "tuning": {
                **{k: v for k, v in vars(t).items()},
                "hangover_frames": t.hangover_frames(),
                "micro_pause_frames": t.micro_pause_frames(),
                "hangover_trail_ms": t.hangover_trail_ms(),
            },
            "prob_summary": analyze.prob_summary(trace.probs, t),
            "dips": [d.to_dict() for d in dips],
            "histogram": [h.to_dict() for h in hist],
            "commits": [vars(c) for c in result.commits],
            "summary": analyze.summarise(result, trace.probs),
            "cap_forensics": [f.to_dict() for f in forensics],
            "sweep": None if sweep_rows is None else [r.to_dict() for r in sweep_rows],
            "silero_delta": probe_mod.SILERO_DELTA,
            "rms_histogram": None if rms_hist is None else [r.to_dict() for r in rms_hist],
            "dip_rms": None if dip_rms_rows is None else [r.to_dict() for r in dip_rms_rows],
            "cap_flat_runs": (
                None if cap_flat_rows is None else [r.to_dict() for r in cap_flat_rows]
            ),
            "flat_run_histogram": (
                None if flat_hist_rows is None else [r.to_dict() for r in flat_hist_rows]
            ),
            "flat_sweep": None if flat_rows is None else [r.to_dict() for r in flat_rows],
            "mid_word_risk_frames": analyze.mid_word_risk_frames(
                result, trace.probs, t, base_ms=a.base_ms
            ),
            "mid_word_split_frames": analyze.mid_word_split_frames(
                result, trace.probs, t, base_ms=a.base_ms
            ),
            "mid_word_bridge_frames": analyze.mid_word_bridge_frames(
                result, trace.probs, t, base_ms=a.base_ms
            ),
            "flat_run_max_rms": (
                None if rms_trace is None
                else {str(k): v for k, v in analyze.flat_run_max_rms(
                    result, rms_trace, t, base_ms=a.base_ms).items()}
            ),
            "flatline_fire_chunks": t.flatline_fire_chunks(),
            "flatline_gap_aligned_ms": t.flatline_gap_aligned_ms(),
            "flatline_gap_any_ms": t.flatline_gap_any_ms(),
        }
        if phone is not None:
            encodes, align = phone
            out["phone_capture"] = {
                "path": a.phone_capture,
                "encodes": [e.to_dict() for e in encodes],
                "alignment": None if align is None else align.to_dict(),
            }
        if coupled is not None:
            cres, cprobs = coupled
            out["coupled"] = {
                "summary": analyze.summarise(cres, cprobs),
                "commits": [vars(c) for c in cres.commits],
                "probs": [round(p, 6) for p in cprobs],
            }
        json.dump(out, sys.stdout, indent=2, default=str)
        sys.stdout.write("\n")
    else:
        sys.stdout.write(
            render_markdown(
                a, t, trace, result, dips, hist, forensics, sweep_rows, coupled,
                rms_hist=rms_hist, dip_rms_rows=dip_rms_rows,
                cap_flat_rows=cap_flat_rows, flat_rows=flat_rows, phone=phone,
                flat_hist_rows=flat_hist_rows,
            )
            + "\n"
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
