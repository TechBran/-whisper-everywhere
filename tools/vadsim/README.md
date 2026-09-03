# vadsim — the endpointer, on the PC

## Why this exists

The app decides where to cut a segment from a per-frame speech probability. On the phone
you cannot see that number: the release build strips the `WE-DIAG` lines, and a debug build
cannot be sideloaded over the Play copy. So there is no way to answer "why did it not cut
at that pause?" on the device.

This tool reproduces the whole decision offline. Feed it a wav; it runs Silero VAD frame by
frame at exactly the app's 512-sample / 32 ms cadence, then replays the app's own state
machine and its 15-second cap over the resulting probabilities, and tells you what the app
would have committed and when. Every tuning can then be tried here, for free, before
anything is changed in the build.

Three parts:

| file | what it is |
|---|---|
| `vadsim/probe.py` | wav → 16 kHz PCM16 → 512-sample frames → one raw probability per frame |
| `vadsim/machine.py` | a branch-by-branch port of `SileroEndpointer.kt` **and** the cap branch of `FloatingBubbleService.onAudioChunk` |
| `vadsim/analyze.py` | the four questions: where are the pauses, how long are they, why was this chunk not cut, and what does every tuning do |

`machine.py` cites the Kotlin `file:line` for every branch it ports. If the app changes, the
citations are how you find what to change here.

## Install

```sh
cd "tools/vadsim"
"C:/Users/bastr/AppData/Local/Programs/Python/Python313/python.exe" -m venv .venv
./.venv/Scripts/python.exe -m pip install -r requirements.txt
```

Installed and verified against: `silero-vad 5.1.2`, `onnxruntime 1.29.0`, `numpy 2.5.2`,
`pytest 9.1.1` (torch 2.14.0 / torchaudio 2.11.0 arrive as silero-vad dependencies and are
unused).

**The `==5.1.2` pin is load-bearing.** The app ships
`app/src/main/assets/ggml-silero-v5.1.2.bin` — Silero VAD v5.1.2. The 6.x wheels ship a
same-sized but *different* `silero_vad.onnx`, so an unpinned install silently starts probing
with different weights than the phone. The pip package pulls torch as a dependency; nothing
here uses it (the probe runs on onnxruntime), it is just along for the ride.

`.venv/` is gitignored. It is ~2 GB installed.

Check the install:

```sh
./.venv/Scripts/python.exe -m pytest tests -q
```

## Getting a wav

The tool takes a **local wav path** and nothing else. It never downloads anything.

**Any wav will do**: mono or stereo, any sample rate, PCM 8/16/24/32 or IEEE float. The tool
folds to mono, resamples to 16 kHz with a bandlimited sinc, and re-quantises to PCM16
because the native probe reads int16 and divides by 32768. Record or export the clip you
care about to a wav on this machine and point the tool at the file.

**If the source is a device-audio session**, hand over the **48 kHz** file unmodified rather
than converting it first. Playback capture arrives at 48 kHz and the app decimates it 3:1
with a 3-tap boxcar average (`Pcm48kTo16kDecimator`, `Pcm48kTo16kDecimator.kt:20-32`); the
tool detects a 48 kHz input and reproduces that exact decimator, aliasing included, instead
of resampling it more cleanly than the phone does. Force it either way with
`--resample device48k` / `--resample sinc`.

**There is no in-app capture dump today.** `WavWriter` is only the cloud-upload container
wrapper — it never writes to disk — so the phone's exact captured bytes cannot currently be
pulled with `adb`. Either record the same audio on this machine, or record it on the phone
with any recorder app and `adb pull` that file. Adding a debug capture dump to the app is
the change that would close the gap; it is not one this tool needs.

**A quick check that the whole thing works** uses a wav already in the repo:

```sh
./.venv/Scripts/python.exe -m vadsim "../../app/src/main/cpp/whisper.cpp/samples/jfk.wav"
```

## Running it

```sh
# the shipped tuning, full report as Markdown
./.venv/Scripts/python.exe -m vadsim clip.wav > report.md

# one knob at a time
./.venv/Scripts/python.exe -m vadsim clip.wav --hangover 500 --release 0.25

# a different tier's cadence floor
./.venv/Scripts/python.exe -m vadsim clip.wav --tier multi

# a cloud (CLOUD_WITH_FALLBACK) session: the 4 s first cap is closed at onOpen AND the
# cadence floor is the flat 3 000 ms request floor for every tier (CommitCadencePolicy.kt:163)
./.venv/Scripts/python.exe -m vadsim clip.wav --cloud

# machine-readable
./.venv/Scripts/python.exe -m vadsim clip.wav --json > report.json

# probe once, then re-run the machine over the saved trace as often as you like
./.venv/Scripts/python.exe -m vadsim clip.wav --save-trace clip.trace.csv
./.venv/Scripts/python.exe -m vadsim clip.wav --load-trace clip.trace.csv --hangover 400
```

`--help` lists every knob. Every default is the shipped value, so a bare run is "what the
phone would do today".

## What the report sections mean

### 1. Input, and the version delta

Which file, which rate, how it was resampled, and — collapsed, but read it once — how this
tool's Silero differs from the phone's. Short version: same upstream release and the same
weights, but the phone runs whisper.cpp's own re-implementation of the network front end,
which substitutes a reflection of the current frame where the real model wants the previous
frame's last 64 samples. Measured on `jfk.wav`, that moves an individual `p` by up to 0.25
and agrees with the reference on which side of 0.35 / 0.50 it falls **98 %** of the time, and
it produced the identical four cuts within one frame of the same instants.

**So: trust the SHAPE of the trace, not the third decimal of any single `p`.** Every question
this tool answers is a shape question.

### 2. Tuning in force

The knobs, plus what each one means in whole 32 ms frames. The line to read is the bold one:
at the shipped `HANGOVER_MS = 350`, **a pause needs 12 consecutive frames — 384 ms of audio —
below `RELEASE` before the machine will cut.** That is the number every other section is
measured against.

### 3. The p-trace

Distribution of the probability over the whole clip, and the three bands that matter:

* **at or above ONSET (0.50)** — speech, holding the gate open;
* **the DEAD BAND, [0.35, 0.50)** — *pauses that are not quiet*. This is the important one.
  A frame in this band does nothing at all: it is not an onset, and it is not a silence. It
  cannot start the pause timer, and it cannot stop it either. Music under speech, room tone,
  a breath, an editor's crossfade — all of it lands here;
* **below RELEASE (0.35)** — real quiet, the only frames the pause timer counts.

A high dead-band fraction is the signature of the problem this tool was built for.

### 4. Dips

Every stretch where the gate is not being held open, longest first.

* `span (ms)` — how long a listener would call the pause.
* `quiet frames` — how much of it the machine can count. **The gap between these two
  numbers is the whole diagnosis.** A 640 ms pause with 6 quiet frames is a pause the app
  cannot see.
* `kind` — `silence` if it ever went below RELEASE, `dead-band` if it never did. A
  dead-band dip cannot be cut at *any* hangover value; it needs a lower RELEASE or nothing.
* `gate` — whether an utterance was actually open when the dip began. With the gate SHUT
  (leading silence, or the silence after a burst too short to count) no length of quiet can
  cut, and the hangover is irrelevant.
* `max age (ms)` — the largest `nowMs - tempEndMs` the machine measured on this dip, clipped
  at the frame it stopped measuring (a cut, a merge, a discard, or a cap landing inside the
  pause). This is the number the hangover guard compares, so "would a hangover of X have cut
  it?" is answered by this column, not by `quiet frames` — dead-band frames inside a pause do
  not reset the clock, so a pause can reach the hangover with fewer quiet frames than the
  hangover's frame count.
* `outcome` — what the machine actually DID with this dip, read off the simulation and not
  re-derived: `vad` (committed here), `merge` (reached the hangover; the governor declined
  it), `discard` (reached the hangover; the speech before it was under `MIN_SPEECH_MS`),
  `cap` (the wall clock ran out *inside* this pause before the hangover elapsed — the cap's
  `endpointer.reset()` then kills the pending end, so the rest of the pause cannot cut),
  `discard+cap` / `merge+cap` (both on one frame), `none` (never reached the hangover) or
  `gate-shut`.

Section 2 also states the hangover's **equivalence band**: on the simulator's exact 32 ms grid
every `HANGOVER_MS` in `(320, 352]` behaves identically to 350, because the guard is only ever
evaluated at whole frames from the pending end. Two values inside one band cannot be told
apart here; on the phone, whose frame timestamps sit a few ms off the grid, a value near a
band edge will sometimes cut one frame later than this report says — never earlier. Compare
hangovers across bands (320 vs 350 vs 384), not within one.

### 5. Pause-length histogram

The same two measurements as columns: dips binned by full span on the left, by quiet span on
the right. The bucket boundary between `320-384` and `384-512` is where the shipped hangover
sits. Everything at or above it in the **right** column is a cut the app can make; everything
below rides the cap.

### 6. Commits

What the app would actually have handed the engine, in order. `kind` is `vad` (cut at a
pause) or `cap` (the wall clock ran out — **the 15 s dump**, or 4 s for the session's first
segment). `chunk (ms)` is how much audio that commit carried. `merged inside` counts real
pauses the cadence governor declined because they arrived too soon after the last commit;
`discarded inside` counts bursts of speech too short to be an utterance.

`estimated turbo duty` is `commits × 2 050 ms ÷ wall time` — the work-per-commit arithmetic
from `CommitCadencePolicy`, applied to this clip. Over 100 % means this audio would grow the
segment queue.

### 7. Cap-cut forensics

For each **cap** chunk: *what would it have taken to cut this at a pause instead?* Each row
ends in a verdict naming the actual cause, decided from the machine's own per-dip outcome, in
this order:

* the cap fired **into** a pause (the pause was N ms old when the wall clock ran out) → **the
  cap's timing, not the hangover** — and when the pause was older than `MICRO_PAUSE_MS` the
  cap took it as its retain offer and cut *at* that pause's start, which the verdict says:
  the boundary is right, only the timing is the cap's;
* a cuttable pause existed and the governor merged it → **the cadence floor, not the
  hangover**;
* a cuttable pause existed but the speech before it was under `MIN_SPEECH_MS` → the known
  word-by-word gap, documented in `EndpointerTuning.kt`;
* nothing below RELEASE at all → **lower RELEASE, or nothing changes**;
* otherwise the best pause reached an age of A ms, N frames short → **a hangover of <= A
  would have cut it**.

Merges and discards inside the chunk are appended to whichever verdict wins, so a chunk that
was both merged-into and then capped mid-pause says both.

### 8. Sweep

`hangover {320, 350, 400, 500} × release {0.25, 0.30, 0.35} × cap {8000, 10000, 15000}`, all
at the npu-turbo 2 000 ms floor: commits, the VAD/cap split, mean and p95 chunk length, and
the estimated duty. This is the table to read before changing a constant in the build.

Note that `cap` usually changes nothing when the VAD is already cutting, and that `release`
is the only knob in the grid that shrinks the dead band.

### 9. Coupled run (`--coupled`)

Sections 1-8 run one fixed trace through every tuning, which is what makes the sweep
comparable. The app is not like that: it zeroes the model's recurrent state on every commit,
so the commit pattern feeds back into the probabilities. `--coupled` runs it the app's way
and shows the delta. A small delta means the sweep transfers; a large one means read it for
direction only.

## Tests

`tests/test_machine.py` re-expresses the JVM fixtures from
`app/src/test/java/com/whispereverywhere/audio/SileroEndpointerTest.kt` and
`.../service/SegmentCapPolicyTest.kt` as p-traces and asserts identical commit timings —
the canonical utterance, the dead band that never closes the gate, the micro-pause memory,
the `MIN_SPEECH` discard, the governor merge at the turbo floor, and the first-segment 4 s
cap. Each test names the JVM test it mirrors, and every expected number is derived from
`EndpointerGrid`'s arithmetic rather than copied off a passing run, so a tuning A/B moves
both sides together.

Section 7 of `tests/test_machine.py` (the verifier's additions) ports the remaining
`SileroEndpointerTest` fixtures — the inclusive latch at exactly `ONSET` / `MIN_SPEECH_MS`,
the second utterance measured from its own start, the governor anchored on `reset()`'s last
frame, `onSessionStart` re-arming the free first cut, the 8 000 pre-session bracket, the
`EndpointCut` record — and the service-seam races the port's own doubts named: the hangover
elapsing exactly on the cap frame (the VAD wins, `else if`), a cap firing into a pause (its
`reset()` kills the pending end), the retained tail landing in the next chunk while the cap
anchor stays put, six merges consuming a window with a stale offer, and a discard and a cap
sharing one frame.

`tests/test_analyze.py` and `tests/test_probe.py` cover the analysis layer, the two resample
paths and the CLI.

```sh
./.venv/Scripts/python.exe -m pytest tests -q
```

## What this tool is NOT

* It is not the phone. See the version delta — and two more items it does not list:
  the GGML conversion stores Silero's encoder and final-decoder **convolution weights as
  float16** (`whisper.cpp/models/convert-silero-vad-to-ggml.py:147-152`), where this tool's
  ONNX runs them in float32; and the phone's frame timestamps are `System.currentTimeMillis()`
  on the delivered **chunk**, not a 32 ms grid — a burst delivery stamps several frames with
  one instant, which makes the hangover fire late (never early) by up to a frame.
* It does not model the probe's cost-budget cutout latch (a device thermal effect with no
  offline analogue) or the audio accumulator (it drives exact frames by construction).
* It does not touch the app. Nothing under `app/` is read except as source to port from.
