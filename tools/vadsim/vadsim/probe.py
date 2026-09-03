"""WAV in, raw per-frame Silero probability out — the trace the release build strips.

This deliberately does NOT use the silero-vad package's `get_speech_timestamps`. That
convenience wraps its own thresholding, its own hangover and its own merge pass, and every
one of those decisions is what `machine.py` is here to reproduce from the app's own source.
What we need is the RAW `p` the app's `vadProbeFrame` returns, one per 512-sample window,
with the recurrent state carried across windows.

The frame contract is the app's, exactly:
  * 512 samples of 16 kHz mono per probe call (`EndpointerTuning.FRAME_SAMPLES` = 512,
    `FRAME_BYTES` = 1024, `FRAME_MS` = 32; `whisper_jni.cpp:377-379` declares the same
    `kProbeFrameSamples = 512` / `kProbeFrameBytes = 1024`).
  * PCM16 semantics: the native probe converts `int16 / 32768.0f`
    (`whisper_jni.cpp:441-445`), so this module quantises to int16 after resampling and
    divides by the same 32768.
  * A short frame is NEVER zero-padded into the model — the native side refuses it outright
    (`whisper_jni.cpp:425-427` returns -1.0f for `nBytes != kProbeFrameBytes`). A trailing
    partial frame is therefore DROPPED here, not padded.

MODEL VERSION — read `SILERO_DELTA` below before trusting an absolute `p`.
"""

from __future__ import annotations

import math
import struct
from dataclasses import dataclass
from pathlib import Path
from typing import Iterator, List, Optional, Sequence, Tuple

import numpy as np

from .machine import FRAME_MS, FRAME_SAMPLES

TARGET_RATE = 16_000

#: What the app ships versus what this tool runs. The verifier will read this.
SILERO_DELTA = """\
The app ships `app/src/main/assets/ggml-silero-v5.1.2.bin` (885,098 bytes) — Silero VAD
**v5.1.2**, converted to GGML by `whisper.cpp/models/convert-silero-vad-to-ggml.py` and run
by whisper.cpp's own re-implementation of the network.

This tool runs the **silero-vad 5.1.2** pip wheel's `silero_vad.onnx` (2,327,524 bytes,
sha256 2623a2953f6ff3d2...) through onnxruntime. Same upstream release, so the WEIGHTS
match. The 6.2.1 wheel ships a same-sized but different `silero_vad.onnx`
(sha256 1a153a22f4509e29...), which is why this venv pins 5.1.2 and must keep pinning it.

TWO KNOWN FRONT-END DIFFERENCES, and neither is cosmetic:

 1. LEFT CONTEXT. The upstream v5 ONNX graph takes 576 samples — 64 samples of context
    carried from the PREVIOUS window, concatenated ahead of the 512 new ones
    (`silero_vad/utils_vad.py`, `OnnxWrapper.__call__`: `x = torch.cat([self._context, x])`).
    whisper.cpp has no such carry: `whisper_vad_build_graph` declares a `frame` tensor of
    exactly `n_window` = 512 samples (whisper.cpp:4892) and
    `whisper_vad_build_stft_layer` substitutes `ggml_pad_reflect_1d(cur, 64, 64)`
    (whisper.cpp:4789) — a REFLECTION of the current frame's own edges where the real model
    wants the previous frame's tail.
 2. STFT TIME STEPS. whisper.cpp reflect-pads to 640 samples, runs a 256-point basis at hop
    128, then keeps only the FIRST time step (`ggml_view_2d(..., 1, 128, ...)`,
    whisper.cpp:4903). The ONNX graph produces its own sequence from the 576-sample input.
 3. PRECISION. The GGML conversion stores the encoder and final-decoder CONVOLUTION weights
    as float16 (`whisper.cpp/models/convert-silero-vad-to-ggml.py:147-152`, `is_conv_weight`);
    the STFT basis and the LSTM stay float32. This tool's ONNX runs everything in float32.
    Same values, less precision on the phone — a small, systematic difference, not noise.

The ggml-vs-ONNX delta itself has NOT been measured (it needs the app's native probe on a
PC); the carry/reflect/zero spread below is the best available proxy for its size.

So `--context carry` (the default) is the model as its authors stream it, and
`--context reflect` is the closest ONNX analogue of what the phone actually computes.
Neither is bit-identical to the device. READ THE TRACE FOR ITS SHAPE — dip lengths, the
dead-band fraction, where p crosses 0.35 and 0.50 — which is what every tuning question in
this tool asks. Do not quote an absolute p to three decimals as "what the phone saw".
"""

CONTEXT_MODES = ("carry", "reflect", "zero")


# ---------------------------------------------------------------------------------------
# WAV loading. Hand-rolled RIFF so a float32 or WAVE_FORMAT_EXTENSIBLE file from a phone
# recorder does not need scipy/torchaudio just to be read.
# ---------------------------------------------------------------------------------------

@dataclass
class Wav:
    samples: np.ndarray      # float32 in [-1, 1), mono, at `rate`
    rate: int
    channels: int
    source_rate: int
    source_bits: int
    source_format: str
    path: str

    @property
    def duration_s(self) -> float:
        return len(self.samples) / float(self.rate)


_FMT_PCM = 0x0001
_FMT_FLOAT = 0x0003
_FMT_EXTENSIBLE = 0xFFFE


def read_wav(path: str | Path) -> Wav:
    """Read a RIFF/WAVE file to mono float32 at its native rate.

    Supports PCM 8/16/24/32-bit and IEEE float 32/64-bit, plus WAVE_FORMAT_EXTENSIBLE
    (whose sub-format GUID's first two bytes carry the real tag). Channels are averaged
    down to mono, which is what both of the app's capture sources deliver
    (`AudioFormat.CHANNEL_IN_MONO`).
    """
    p = Path(path)
    raw = p.read_bytes()
    if len(raw) < 12 or raw[0:4] != b"RIFF" or raw[8:12] != b"WAVE":
        raise ValueError(f"{p}: not a RIFF/WAVE file")

    fmt_tag = None
    channels = 1
    rate = TARGET_RATE
    bits = 16
    data: Optional[bytes] = None

    pos = 12
    while pos + 8 <= len(raw):
        cid = raw[pos:pos + 4]
        (csize,) = struct.unpack_from("<I", raw, pos + 4)
        body = raw[pos + 8: pos + 8 + csize]
        if cid == b"fmt ":
            fmt_tag, channels, rate, _brate, _align, bits = struct.unpack_from("<HHIIHH", body, 0)
            if fmt_tag == _FMT_EXTENSIBLE and len(body) >= 26:
                (fmt_tag,) = struct.unpack_from("<H", body, 24)
        elif cid == b"data":
            data = body
        pos += 8 + csize + (csize & 1)   # chunks are word-aligned

    if fmt_tag is None or data is None:
        raise ValueError(f"{p}: missing fmt or data chunk")
    if channels < 1:
        raise ValueError(f"{p}: {channels} channels")

    if fmt_tag == _FMT_PCM:
        if bits == 8:
            a = np.frombuffer(data, dtype=np.uint8).astype(np.float32)
            a = (a - 128.0) / 128.0
            fmt_name = "pcm-u8"
        elif bits == 16:
            n = len(data) // 2 * 2
            a = np.frombuffer(data[:n], dtype="<i2").astype(np.float32) / 32768.0
            fmt_name = "pcm-s16"
        elif bits == 24:
            n = len(data) // 3 * 3
            b = np.frombuffer(data[:n], dtype=np.uint8).reshape(-1, 3).astype(np.int32)
            v = (b[:, 0] | (b[:, 1] << 8) | (b[:, 2] << 16)).astype(np.int32)
            v = np.where(v & 0x800000, v - 0x1000000, v)
            a = v.astype(np.float32) / 8388608.0
            fmt_name = "pcm-s24"
        elif bits == 32:
            n = len(data) // 4 * 4
            a = np.frombuffer(data[:n], dtype="<i4").astype(np.float32) / 2147483648.0
            fmt_name = "pcm-s32"
        else:
            raise ValueError(f"{p}: unsupported PCM width {bits}")
    elif fmt_tag == _FMT_FLOAT:
        if bits == 32:
            n = len(data) // 4 * 4
            a = np.frombuffer(data[:n], dtype="<f4").astype(np.float32)
            fmt_name = "float32"
        elif bits == 64:
            n = len(data) // 8 * 8
            a = np.frombuffer(data[:n], dtype="<f8").astype(np.float32)
            fmt_name = "float64"
        else:
            raise ValueError(f"{p}: unsupported float width {bits}")
    else:
        raise ValueError(f"{p}: unsupported WAVE format tag 0x{fmt_tag:04X}")

    if channels > 1:
        usable = len(a) // channels * channels
        a = a[:usable].reshape(-1, channels).mean(axis=1)

    return Wav(
        samples=np.ascontiguousarray(a, dtype=np.float32),
        rate=rate,
        channels=channels,
        source_rate=rate,
        source_bits=bits,
        source_format=fmt_name,
        path=str(p),
    )


# ---------------------------------------------------------------------------------------
# Resampling to 16 kHz.
# ---------------------------------------------------------------------------------------

def _resample_device48k(x: np.ndarray) -> np.ndarray:
    """The app's OWN 48 kHz path, replicated bit-for-bit.

    `Pcm48kTo16kDecimator` (Pcm48kTo16kDecimator.kt:20-32) averages sample TRIPLETS with
    integer division truncating toward zero, on int16 values — "a crude but sufficient
    anti-alias for speech-band transcription". Device-audio capture on a 48 kHz device is
    the source the owner is chasing, so this mode reproduces its aliasing rather than
    improving on it.
    """
    q = np.clip(np.rint(x * 32768.0), -32768, 32767).astype(np.int32)
    n = len(q) // 3 * 3
    trip = q[:n].reshape(-1, 3).sum(axis=1)
    # Kotlin `acc / 3` on Int truncates toward ZERO, which numpy's // does not.
    avg = np.trunc(trip / 3.0).astype(np.int32)
    return (avg.astype(np.float32) / 32768.0)


def _resample_sinc(x: np.ndarray, src_rate: int, taps_per_side: int = 32) -> np.ndarray:
    """Bandlimited windowed-sinc resample to 16 kHz, blocked so long files fit in RAM."""
    if src_rate == TARGET_RATE:
        return x
    ratio = TARGET_RATE / float(src_rate)
    n_out = int(math.floor(len(x) * ratio))
    if n_out <= 0:
        return np.zeros(0, dtype=np.float32)

    # Anti-alias cutoff: the lower of the two Nyquists, in input-sample units.
    cutoff = min(1.0, ratio)
    half = max(1, int(round(taps_per_side / cutoff)))
    beta = 8.6
    pad = np.zeros(len(x) + 2 * half, dtype=np.float64)
    pad[half:half + len(x)] = x

    out = np.empty(n_out, dtype=np.float64)
    block = 65_536
    offsets = np.arange(-half + 1, half + 1, dtype=np.float64)
    for start in range(0, n_out, block):
        stop = min(n_out, start + block)
        centres = np.arange(start, stop, dtype=np.float64) / ratio
        base = np.floor(centres).astype(np.int64)
        frac = centres - base
        # taps[j, k] = sinc-window value for input sample base[j] + offsets[k]
        t = offsets[None, :] - frac[:, None]
        w = np.i0(beta * np.sqrt(np.maximum(0.0, 1.0 - (t / half) ** 2))) / np.i0(beta)
        h = cutoff * np.sinc(cutoff * t) * w
        idx = base[:, None] + offsets[None, :].astype(np.int64) + half
        np.clip(idx, 0, len(pad) - 1, out=idx)
        num = (pad[idx] * h).sum(axis=1)
        den = h.sum(axis=1)
        out[start:stop] = num / np.where(den == 0.0, 1.0, den)
    return out.astype(np.float32)


def to_16k_pcm16(wav: Wav, mode: str = "auto") -> Tuple[np.ndarray, str]:
    """Resample to 16 kHz and re-quantise to PCM16 semantics.

    Returns `(float32 samples in [-1, 1), mode_used)`. The quantisation is not decoration:
    the native probe reads int16 and divides by 32768 (`whisper_jni.cpp:441-445`), so a
    float trace that skipped it would be measuring audio the phone can never see.

    Modes: `auto` (device48k for a 48 kHz input, sinc otherwise), `sinc`, `device48k`.
    """
    if mode not in ("auto", "sinc", "device48k"):
        raise ValueError(f"unknown resample mode {mode!r}")
    if mode == "auto":
        mode = "device48k" if wav.rate == 48_000 else "sinc"
    if mode == "device48k":
        if wav.rate != 48_000:
            raise ValueError(f"--resample device48k needs a 48 kHz input, got {wav.rate}")
        y = _resample_device48k(wav.samples)
    else:
        y = _resample_sinc(wav.samples, wav.rate)
    q = np.clip(np.rint(y * 32768.0), -32768, 32767).astype(np.int16)
    return q.astype(np.float32) / 32768.0, mode


def frames_of(samples: np.ndarray) -> Iterator[np.ndarray]:
    """Exactly 512-sample frames. A trailing partial frame is DROPPED, never padded —
    `whisper_jni.cpp:425-427` refuses any frame that is not 1024 bytes, and a zero-padded
    short frame would still advance the LSTM and poison the recurrence."""
    n = len(samples) // FRAME_SAMPLES
    for i in range(n):
        yield samples[i * FRAME_SAMPLES:(i + 1) * FRAME_SAMPLES]


# ---------------------------------------------------------------------------------------
# The probe.
# ---------------------------------------------------------------------------------------

def default_model_path() -> Path:
    import silero_vad
    return Path(silero_vad.__file__).parent / "data" / "silero_vad.onnx"


def silero_package_version() -> str:
    try:
        import importlib.metadata as md
        return md.version("silero-vad")
    except Exception:                                    # pragma: no cover
        return "unknown"


class SileroProbe:
    """One ONNX session driven ONE 512-sample window at a time, state carried across calls.

    Mirrors `WhisperNative.vadProbeFrame` / `vadProbeReset`: `__call__` is one frame,
    `reset()` is the LSTM zeroing the app fires on every commit
    (`SileroEndpointer.clearForNextSegment` -> `probeReset`, SileroEndpointer.kt:664).
    """

    CONTEXT_SAMPLES = 64      # utils_vad.py: `context_size = 64 if sr == 16000`

    def __init__(self, model_path: str | Path | None = None, context: str = "carry") -> None:
        import onnxruntime as ort

        if context not in CONTEXT_MODES:
            raise ValueError(f"context must be one of {CONTEXT_MODES}, got {context!r}")
        self.context_mode = context
        self.model_path = str(model_path or default_model_path())

        opts = ort.SessionOptions()
        opts.inter_op_num_threads = 1
        # n_threads = 1 on the device too, and for a stated reason:
        # whisper_jni.cpp:355-366 (`vcp.n_threads = 1`) — no ggml threadpool is installed for
        # a VAD context, so the default 4 would spawn and join 3 pthreads per 32 ms frame.
        opts.intra_op_num_threads = 1
        self.session = ort.InferenceSession(
            self.model_path, providers=["CPUExecutionProvider"], sess_options=opts
        )
        names = {i.name for i in self.session.get_inputs()}
        if not {"input", "state", "sr"} <= names:
            raise RuntimeError(
                f"{self.model_path}: unexpected ONNX inputs {sorted(names)} — this module "
                "targets the Silero v5 graph (input/state/sr)."
            )
        self._sr = np.array(TARGET_RATE, dtype=np.int64)
        self.reset()

    def reset(self) -> None:
        """`vadProbeReset` (whisper_jni.cpp:465-473) — zero the LSTM hidden/cell state."""
        self._state = np.zeros((2, 1, 128), dtype=np.float32)
        self._context = np.zeros((1, self.CONTEXT_SAMPLES), dtype=np.float32)
        self.calls = 0

    def __call__(self, frame: np.ndarray) -> float:
        if len(frame) != FRAME_SAMPLES:
            # `whisper_jni.cpp:425-427` returns -1.0f, and the endpointer treats any negative
            # as "no verdict" (SileroEndpointer.kt:534). Never silence.
            return -1.0
        x = np.asarray(frame, dtype=np.float32).reshape(1, FRAME_SAMPLES)
        if self.context_mode == "carry":
            left = self._context
        elif self.context_mode == "reflect":
            # whisper.cpp's substitute: ggml_pad_reflect_1d(frame, 64, 64) (whisper.cpp:4789).
            left = np.pad(x[0], (self.CONTEXT_SAMPLES, 0), mode="reflect")[
                : self.CONTEXT_SAMPLES
            ].reshape(1, self.CONTEXT_SAMPLES)
        else:
            left = np.zeros((1, self.CONTEXT_SAMPLES), dtype=np.float32)
        inp = np.concatenate([left, x], axis=1)
        out, state = self.session.run(
            None, {"input": inp, "state": self._state, "sr": self._sr}
        )
        self._state = state
        if self.context_mode == "carry":
            self._context = inp[:, -self.CONTEXT_SAMPLES:]
        self.calls += 1
        return float(out[0][0])


@dataclass
class Trace:
    """A p-trace plus everything needed to explain where it came from."""

    probs: List[float]
    path: str
    source_rate: int
    source_format: str
    resample_mode: str
    context_mode: str
    model_path: str
    package_version: str

    @property
    def n_frames(self) -> int:
        return len(self.probs)

    @property
    def wall_ms(self) -> int:
        return self.n_frames * FRAME_MS

    def to_dict(self) -> dict:
        return {
            "path": self.path,
            "source_rate": self.source_rate,
            "source_format": self.source_format,
            "resample_mode": self.resample_mode,
            "context_mode": self.context_mode,
            "model_path": self.model_path,
            "package_version": self.package_version,
            "frame_ms": FRAME_MS,
            "n_frames": self.n_frames,
            "probs": [round(p, 6) for p in self.probs],
        }


def probe_wav(
    path: str | Path,
    *,
    resample: str = "auto",
    context: str = "carry",
    model_path: str | Path | None = None,
) -> Trace:
    """The whole front half: wav -> 16 kHz PCM16 -> 512-sample frames -> one p per frame.

    The LSTM state is carried straight through, with NO resets. The app resets it on every
    commit, which couples the trace to the tuning — see `machine.simulate_coupled`. A single
    reset-free trace is what makes the sweep in `analyze.py` comparable across tunings, and
    the coupling is measured separately rather than assumed away.
    """
    wav = read_wav(path)
    samples, mode = to_16k_pcm16(wav, resample)
    probe = SileroProbe(model_path=model_path, context=context)
    probs = [probe(f) for f in frames_of(samples)]
    return Trace(
        probs=probs,
        path=str(path),
        source_rate=wav.source_rate,
        source_format=f"{wav.source_format} x{wav.channels}ch",
        resample_mode=mode,
        context_mode=context,
        model_path=probe.model_path,
        package_version=silero_package_version(),
    )


def frames_from_wav(
    path: str | Path, *, resample: str = "auto"
) -> Tuple[List[np.ndarray], str, Wav]:
    """The frames alone, for `machine.simulate_coupled`."""
    wav = read_wav(path)
    samples, mode = to_16k_pcm16(wav, resample)
    return list(frames_of(samples)), mode, wav


def write_trace_csv(trace: Trace, out_path: str | Path, base_ms: int) -> None:
    lines = ["frame,t_ms,p"]
    for i, p in enumerate(trace.probs):
        lines.append(f"{i},{base_ms + i * FRAME_MS},{p:.6f}")
    Path(out_path).write_text("\n".join(lines) + "\n", encoding="utf-8")


def load_trace_csv(path: str | Path) -> List[float]:
    out: List[float] = []
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        if not line or line.startswith("frame"):
            continue
        out.append(float(line.rsplit(",", 1)[1]))
    return out
