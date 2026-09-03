"""vadsim — an offline simulator for Whisper Everywhere's streaming endpointer.

The release build strips the app's `WE-DIAG` lines and a debug build cannot be sideloaded
over the Play copy, so the per-frame VAD probability trace cannot be read off the phone.
This package reproduces it on the PC: `probe` turns a local wav into the raw per-frame
Silero probability, `machine` is a branch-by-branch port of `SileroEndpointer` plus the cap
branch of `FloatingBubbleService.onAudioChunk`, and `analyze` answers "why was this pause
not cut" and sweeps every tuning.
"""

from .machine import (  # noqa: F401
    BASE_MS,
    FRAME_MS,
    FRAME_SAMPLES,
    Commit,
    EndpointCut,
    ServiceSim,
    SegmentCapPolicySim,
    SileroEndpointerSim,
    SimResult,
    Tuning,
    cap_cut_consumes_window,
    cap_cut_retain_ms,
    simulate,
    simulate_coupled,
    with_overrides,
)

__all__ = [
    "BASE_MS",
    "FRAME_MS",
    "FRAME_SAMPLES",
    "Commit",
    "EndpointCut",
    "ServiceSim",
    "SegmentCapPolicySim",
    "SileroEndpointerSim",
    "SimResult",
    "Tuning",
    "cap_cut_consumes_window",
    "cap_cut_retain_ms",
    "simulate",
    "simulate_coupled",
    "with_overrides",
]
