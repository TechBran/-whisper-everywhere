"""The front end: RIFF reading, the two resample paths, the frame contract, the CLI.

The one thing these must not do is assert an absolute `p` — see `probe.SILERO_DELTA`.
They assert CONTRACTS: frame size, PCM16 quantisation, the app's own decimator arithmetic,
and that the CLI's Markdown and JSON both come out whole.
"""

from __future__ import annotations

import json
import struct
import subprocess
import sys
from pathlib import Path

import numpy as np
import pytest

from vadsim import probe as probe_mod
from vadsim.machine import FRAME_SAMPLES

REPO = Path(__file__).resolve().parents[3]
JFK = REPO / "app" / "src" / "main" / "cpp" / "whisper.cpp" / "samples" / "jfk.wav"
CANARY = REPO / "app" / "src" / "main" / "assets" / "canary_digits.wav"


def write_wav(path: Path, samples: np.ndarray, rate: int, bits: int = 16,
              channels: int = 1) -> Path:
    if bits == 16:
        payload = np.clip(np.rint(samples * 32768.0), -32768, 32767).astype("<i2").tobytes()
        fmt, bps = 1, 2
    elif bits == 32:
        payload = samples.astype("<f4").tobytes()
        fmt, bps = 3, 4
    else:
        raise ValueError(bits)
    block = bps * channels
    hdr = b"RIFF" + struct.pack("<I", 36 + len(payload)) + b"WAVEfmt " + struct.pack(
        "<IHHIIHH", 16, fmt, channels, rate, rate * block, block, bits * 1
    ) + b"data" + struct.pack("<I", len(payload))
    path.write_bytes(hdr + payload)
    return path


def test_the_repo_wavs_are_where_the_smoke_test_expects_them():
    assert JFK.is_file(), JFK
    assert CANARY.is_file(), CANARY


def test_a_16k_pcm16_wav_round_trips_unchanged():
    """16 kHz in means no resampling, and the PCM16 re-quantisation must be a no-op on a
    file that was already PCM16."""
    x = (np.sin(np.arange(4096) * 0.05) * 0.4).astype(np.float32)
    p = write_wav(Path(_tmp()) / "a.wav", x, 16_000)
    wav = probe_mod.read_wav(p)
    assert wav.rate == 16_000 and wav.channels == 1
    y, mode = probe_mod.to_16k_pcm16(wav, "auto")
    assert mode == "sinc"
    q = np.clip(np.rint(x * 32768.0), -32768, 32767).astype(np.int16).astype(np.float32) / 32768.0
    assert np.array_equal(y, q)


def test_pcm16_semantics_are_applied_after_resampling():
    """The native probe reads int16 and divides by 32768 (whisper_jni.cpp:441-445), so every
    sample this tool hands the model must be an exact multiple of 1/32768."""
    x = (np.sin(np.arange(48_000) * 0.01) * 0.3).astype(np.float32)
    p = write_wav(Path(_tmp()) / "b.wav", x, 48_000)
    y, mode = probe_mod.to_16k_pcm16(probe_mod.read_wav(p), "sinc")
    scaled = y * 32768.0
    assert np.allclose(scaled, np.rint(scaled), atol=1e-4)
    assert mode == "sinc"


def test_the_48k_path_defaults_to_the_apps_own_decimator():
    """`Pcm48kTo16kDecimator` (Pcm48kTo16kDecimator.kt:20-32): triplet average, integer
    division truncating TOWARD ZERO. Reproduced, not improved on — device-audio capture on a
    48 kHz device is the source the owner is chasing."""
    ints = np.array([100, 200, 301, -100, -200, -301, 0, 0, 1], dtype=np.int32)
    x = (ints.astype(np.float32) / 32768.0)
    p = write_wav(Path(_tmp()) / "c.wav", x, 48_000)
    wav = probe_mod.read_wav(p)
    y, mode = probe_mod.to_16k_pcm16(wav, "auto")
    assert mode == "device48k", "48 kHz input takes the app's own path by default"
    got = np.rint(y * 32768.0).astype(np.int32)
    # (100+200+301)/3 = 200.33 -> 200 ; (-601)/3 = -200.33 -> -200 (toward zero) ; 1/3 -> 0
    assert list(got) == [200, -200, 0]


def test_device48k_refuses_a_non_48k_input():
    x = np.zeros(1024, dtype=np.float32)
    p = write_wav(Path(_tmp()) / "d.wav", x, 44_100)
    with pytest.raises(ValueError, match="48 kHz"):
        probe_mod.to_16k_pcm16(probe_mod.read_wav(p), "device48k")


def test_float32_and_stereo_are_read_and_folded_to_mono():
    left = np.full(1024, 0.5, dtype=np.float32)
    right = np.full(1024, -0.1, dtype=np.float32)
    inter = np.empty(2048, dtype=np.float32)
    inter[0::2] = left
    inter[1::2] = right
    p = write_wav(Path(_tmp()) / "e.wav", inter, 16_000, bits=32, channels=2)
    wav = probe_mod.read_wav(p)
    assert wav.channels == 2 and wav.source_format == "float32"
    assert np.allclose(wav.samples, 0.2)


def test_frames_are_exactly_512_samples_and_a_partial_tail_is_dropped():
    """`whisper_jni.cpp:425-427` refuses any frame that is not 1024 bytes; a zero-padded
    short frame would still advance the LSTM and poison the recurrence."""
    x = np.zeros(FRAME_SAMPLES * 3 + 7, dtype=np.float32)
    frames = list(probe_mod.frames_of(x))
    assert len(frames) == 3
    assert all(len(f) == FRAME_SAMPLES for f in frames)


def test_a_short_frame_gets_the_no_verdict_sentinel_not_silence():
    p = probe_mod.SileroProbe()
    assert p(np.zeros(100, dtype=np.float32)) == -1.0


@pytest.mark.parametrize("context", list(probe_mod.CONTEXT_MODES))
def test_every_context_mode_produces_one_probability_per_frame_in_range(context):
    tr = probe_mod.probe_wav(CANARY, context=context)
    assert tr.n_frames == 80, "2.56 s of 16 kHz audio is 80 whole frames"
    assert all(0.0 <= p <= 1.0 for p in tr.probs)
    assert tr.package_version == "5.1.2", (
        "the venv must pin silero-vad 5.1.2 to match the app's ggml-silero-v5.1.2.bin asset"
    )


def test_the_probe_carries_state_across_frames_and_reset_clears_it():
    """The streaming premise: `vadProbeFrame` runs `whisper_vad_detect_speech_no_reset`
    (whisper_jni.cpp:449) so the LSTM carries, and `vadProbeReset` (whisper_jni.cpp:465)
    zeroes it. If the state did not carry, resetting would change nothing."""
    frames, _mode, _wav = probe_mod.frames_from_wav(CANARY)
    p = probe_mod.SileroProbe(context="carry")
    straight = [p(f) for f in frames[:20]]
    p.reset()
    per_frame = []
    for f in frames[:20]:
        p.reset()
        per_frame.append(p(f))
    assert straight != per_frame, "state that never carried would make reset a no-op"


def test_the_trace_csv_round_trips():
    tr = probe_mod.probe_wav(CANARY)
    out = Path(_tmp()) / "t.csv"
    probe_mod.write_trace_csv(tr, out, 1_000_000)
    back = probe_mod.load_trace_csv(out)
    assert len(back) == tr.n_frames
    assert all(abs(a - b) < 1e-6 for a, b in zip(back, tr.probs))


# ---------------------------------------------------------------------------------------
# The CLI, end to end.
# ---------------------------------------------------------------------------------------

def _run_cli(*args: str) -> str:
    proc = subprocess.run(
        [sys.executable, "-m", "vadsim", *args],
        cwd=str(Path(__file__).resolve().parents[1]),
        capture_output=True,
        text=True,
        encoding="utf-8",
    )
    assert proc.returncode == 0, proc.stderr
    return proc.stdout


def test_the_cli_emits_every_markdown_section():
    out = _run_cli(str(JFK), "--max-dips", "5")
    for heading in (
        "## 1. Input",
        "## 2. Tuning in force",
        "## 3. The p-trace",
        "## 4. Dips",
        "## 5. Pause-length histogram",
        "## 6. Commits",
        "## 7. Cap-cut forensics",
        "## 8. Sweep",
    ):
        assert heading in out, heading
    assert "ggml-silero-v5.1.2.bin" in out, "the version delta must be in every report"


def test_the_cli_json_is_parseable_and_complete():
    out = _run_cli(str(CANARY), "--json", "--no-sweep")
    doc = json.loads(out)
    assert set(doc) >= {
        "input", "tuning", "prob_summary", "dips", "histogram", "commits",
        "summary", "cap_forensics", "silero_delta",
    }
    assert doc["sweep"] is None
    assert doc["input"]["n_frames"] == len(doc["input"]["probs"]) == 80
    assert doc["tuning"]["hangover_frames"] == 12
    assert doc["tuning"]["min_commit_interval_ms"] == 2_000, "--tier defaults to npu-turbo"


def test_the_cli_honours_the_tuning_flags():
    out = _run_cli(str(JFK), "--json", "--no-sweep", "--hangover", "800",
                   "--release", "0.25", "--tier", "multi", "--cap", "8000")
    doc = json.loads(out)
    assert doc["tuning"]["hangover_ms"] == 800
    assert doc["tuning"]["release"] == 0.25
    assert doc["tuning"]["cap_ms"] == 8_000
    assert doc["tuning"]["min_commit_interval_ms"] == 6_000, "multi's floor"
    assert doc["tuning"]["hangover_frames"] == 26, "ceil(800/32) + 1"


def test_load_trace_skips_the_probe_entirely():
    tmp = Path(_tmp())
    csv = tmp / "saved.csv"
    _run_cli(str(CANARY), "--json", "--no-sweep", "--save-trace", str(csv))
    assert csv.is_file()
    out = _run_cli(str(CANARY), "--json", "--no-sweep", "--load-trace", str(csv))
    doc = json.loads(out)
    assert doc["input"]["n_frames"] == 80
    assert doc["input"]["model_path"] == "n/a"


_TMP: str | None = None


def _tmp() -> str:
    global _TMP
    if _TMP is None:
        import tempfile
        _TMP = tempfile.mkdtemp(prefix="vadsim-test-")
    return _TMP


# =======================================================================================
# Verifier additions (2026-09-03).
# =======================================================================================

def test_a_cloud_session_takes_the_flat_3000ms_request_floor_for_every_tier():
    """`CommitCadencePolicy.minCommitIntervalMs` — "Cloud batch wins outright — a FLAT 3 000
    for every tier" (CommitCadencePolicy.kt:151, `if (isCloudBatch) return` at :163). The
    service passes `isCloudBatch = cloudWrapper != null`, the predicate `--cloud` models, so
    `--cloud --tier multi` is 3 000 in the app and must be 3 000 here. `--floor` still wins."""
    from vadsim.__main__ import build_parser, tuning_from_args

    def floor(*args: str) -> int:
        return tuning_from_args(build_parser().parse_args(["x.wav", *args])).min_commit_interval_ms

    assert floor("--cloud") == 3_000
    assert floor("--cloud", "--tier", "multi") == 3_000
    assert floor("--cloud", "--tier", "npu-turbo") == 3_000
    assert floor("--tier", "multi") == 6_000, "and a local multi session keeps its own floor"
    assert floor("--cloud", "--floor", "1200") == 1_200, "--floor overrides everything"


def test_the_context_modes_differ_only_in_the_left_64_samples():
    """`carry` feeds the previous frame's last 64 samples (utils_vad.py: `_context = x[..., -64:]`);
    `reflect` feeds `x[64], ..., x[1]` (numpy 'reflect' excludes the edge, as
    `ggml_pad_reflect_1d` does); `zero` feeds zeros. On the FIRST frame `carry` and `zero`
    are identical by construction."""
    frames, _mode, _wav = probe_mod.frames_from_wav(CANARY)
    carry = probe_mod.SileroProbe(context="carry")
    zero = probe_mod.SileroProbe(context="zero")
    assert carry(frames[0]) == zero(frames[0]), "no previous frame: carry == zero"
    x = frames[0]
    left = np.pad(x, (64, 0), mode="reflect")[:64]
    assert np.array_equal(left, x[64:0:-1])
