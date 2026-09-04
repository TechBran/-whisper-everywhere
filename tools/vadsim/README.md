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

`tests/test_backpressure.py` (build 85) mirrors `SileroEndpointerBackpressureTest` BY NAME —
the two floors observed from the commit count at depth 0/1/2, the mid-interval change that takes
effect at the next endpoint and never retroactively, the flat path on the same floor, the
lifecycle rules, the inert default — and adds the decoder-queue model's own tests: the governor
bounds the queue where the fast floor alone lets it grow. `tests/fixtures/backpressure_cross_trace.csv`
is the trace-level twin: one `(p, depth-schedule)` trace, generated by
`tests/backpressure_fixture.py`, whose expected commits and mode steps BOTH suites replay.

```sh
./.venv/Scripts/python.exe -m pytest tests -q
```

## The backpressure governor (build 85)

`CommitCadencePolicy.MIN_COMMIT_INTERVAL_TURBO_MS = 2 000` is an owner ruling over the 0.70 duty
rule (one sentence per chunk at ~98 % saturated duty, 110 % throttled), and the guard that ruling
named is THE BACKPRESSURE GOVERNOR: the fast floor while the segment queue (committed and not yet
resolved) is at most one deep, a SLOW floor — 3 200, the bounded-duty value, 62 % saturated — once
it reaches two. Two states with hysteresis (enter at 2, leave at 1), stepped by the endpointer at
each real endpoint from the depth the service publishes. Only npu-turbo has a slow row that differs
from its fast one; every other tier's slow floor equals its fast floor, so the governor is inert
there by construction.

The simulator models the decoder as ONE server with a FIFO (`DecoderQueueSim`): every commit is a
job of `service_ms` (2 050 on turbo — 1 779 encode + 48 + 44 + ~10 x 18 tokens), and the depth at
any instant is the number of jobs not yet finished — exactly what `SegmentQueueDepth` counts on the
phone. The depth feeds the same two-state rule with the same constants.

```sh
# the shipped turbo behaviour: 2000 fast, 3200 slow, decoder at 2050 ms per commit
./.venv/Scripts/python.exe -m vadsim clip.wav

# a hot phone: the fast floor alone would let the queue grow; watch the governor bound it
./.venv/Scripts/python.exe -m vadsim clip.wav --service-ms 2500

# the governor OFF (slow == fast), for the before/after
./.venv/Scripts/python.exe -m vadsim clip.wav --slow-floor 2000
```

Section 2 gains a `slowCommitIntervalMs (backpressure)` row (ARMED or INERT, with the decoder's
service time); section 6 gains `backpressure transitions`, `time on the slow floor` and `max decoder
queue depth`, plus the mode-step table — the app's `backpressure: depth=2 -> slow floor 3200` lines
with the endpoint's clock beside them. `--json` carries the same under `backpressure`.

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

---

## The FLATLINE trigger (a PROPOSAL, default OFF)

Added 2026-09-03, to be **measured, not shipped**. Nothing in the app implements it.

### The problem it addresses

The endpointer decides on Silero's `p` alone. `SileroEndpointer.onFrame(chunk, amp, nowMs)`
receives the chunk's RMS and its own KDoc says so: *"@param amp the chunk's RMS, ignored
here"* (`SileroEndpointer.kt:245`). To cut, it needs 12 consecutive below-`RELEASE` frames —
**352 ms** at the shipped `HANGOVER_MS = 350`. On **edited video** an editor leaves 100-300 ms
of near-digital silence at a sentence boundary: the waveform visibly flatlines (the bubble is
driven from that same `amp`, `FloatingBubbleService.kt:1998`) and nothing gets cut.

Natural speech never reaches digital zero — room tone measures 50-300 RMS — so a trigger on
*"chunk RMS near zero, held briefly"* fires only on gated/edited audio, and there only at the
edit points. That is the proposal.

### The app's RMS facts (what the simulator models)

| fact | where |
|---|---|
| RMS = `sqrt(sum(sample^2)/count)`, **truncated** to int, clamped 0..32767 | `AudioMath.kt:21-37` (`:35`, `:36`) |
| MIC: 1024-byte read = 512 samples = **32 ms**; one `amp` per read, over `read` bytes | `StreamingAudioRecorder.kt:80`, `:85`, `:87`, `:97` |
| DEVICE AUDIO: `readSize` 1024 @16k / 3072 @48k; `out` is the **decimated** buffer and `amp` is measured on it → also 512 samples / **32 ms** | `PlaybackAudioCapturer.kt:64`, `:79`, `:81`; `Pcm48kTo16kDecimator.kt:20-36` |
| ONE amp per CHUNK reaches the endpointer, which splits the chunk into 512-sample FRAMES internally | `FloatingBubbleService.kt:1976`, `:2010`; `SileroEndpointer.kt:259-289` |

So the simulator models **one RMS per chunk, applied to every frame that chunk completes**
(`probe.frame_rms`), never a per-frame RMS. `--chunk-ms` parameterises it; the default 32 is
the value **both** capture paths deliver. The consequence to keep in mind: a hold can only
ever be satisfied in whole chunks, so `flatline_hold_ms` rounds up —
`Tuning.flatline_effective_hold_ms()`, printed in section 2.

### What a hold actually buys (verifier, 2026-09-03)

The "effective hold" is the run's **age** when it fires, and the run's first flat chunk is
age 0 — so a hold of 128 fires on the **fifth** consecutive flat chunk
(`Tuning.flatline_fire_chunks()`), 160 ms of flat audio in. A gap of digital silence
supplies five *whole* flat chunks only if it is at least 160 ms long **and starts on a chunk
boundary**; a real editor's gate does not know where the capture grid is, and one
millisecond of speech at the edge of a chunk already reads over 500 RMS, so in general a gap
of `G` ms holds only `floor(G/32) - 1` whole flat chunks. The gap a hold **reliably** catches
is therefore one chunk longer than the aligned figure:

| hold (ms) | flat chunks needed | gap cut when aligned | gap cut at ANY alignment |
|---|---|---|---|
| 96 | 4 | 128 | **160** |
| 128 | 5 | 160 | **192** |
| 160 | 6 | 192 | **224** |
| 224 | 8 | 256 | **288** |
| 320 | 11 | 352 | **384** |

(`Tuning.flatline_gap_aligned_ms()` / `flatline_gap_any_ms()`; section 2 and the sweep print
them.) Against "an editor leaves 100-300 ms", a 128 ms hold is certain only for the upper
half of that band. Every synthetic fixture and the chunk-gated `jfk-gated.wav` demo are
aligned by construction — `tests/test_flatline_verify.py` shows the same 160 ms gap shifted
by 16 ms yielding four flat chunks and no cut at 128.

Two more things the simulator's exact 32 ms grid cannot show, both of which argue for a port
that **counts chunks** rather than comparing a wall-clock hold: the phone stamps chunks with
`System.currentTimeMillis()` at delivery, which is bursty, so a hold that is an exact multiple
of 32 sits on a band edge and fires a chunk early or late as often as on time; and with a
chunk larger than 32 ms every frame of a chunk carries one `nowMs`
(`FloatingBubbleService.kt:2009`), which this tool does not model (`--chunk-ms` grouping is
modelled, the timestamp collapse is not — a 96 ms hold at a 128 ms chunk fires one chunk
earlier here than on a device).

### Semantics

Evaluated per frame **after** `onProb` has declined that frame, so Silero always wins a tie
and one frame produces at most one commit. Fires only while the gate is open. Counts
consecutive frames whose chunk RMS is **strictly below** `flatline_rms`; any frame at or above
resets the count, and an unknown RMS counts as at-or-above. When the run's age reaches
`flatline_hold_ms` it behaves **exactly like a hangover close arriving on that frame**: the
pending end is whatever `tempEndMs` already holds — Silero's own stamp when it has one,
whether earlier than the run (room tone, then zero) or later (a dead-band frame of LSTM
inertia first) — and the run's first flat frame only when `tempEndMs` is 0; then the same
`MIN_SPEECH` discard, the same governor merge, the same `commitAt` — `kind = 'flat'`. On
real digital silence the two coincide: Silero's `p` falls under `RELEASE` on the very first
zero frame (max `p` on any zero-RMS frame of `jfk-gated.wav`: 0.075). All eight design
decisions are written out in `machine.SileroEndpointerSim._on_flat`'s docstring.

### Running it

```sh
# the proposal, at a 40 RMS floor held 128 ms
./.venv/Scripts/python.exe -m vadsim clip.wav --flatline-rms 40 --flatline-hold 128

# either flag alone turns it on; the other takes its default
./.venv/Scripts/python.exe -m vadsim clip.wav --flatline-hold 224

# the phone's own commit sequence beside the simulator's
./.venv/Scripts/python.exe -m vadsim clip.wav --phone-capture ~/.androidbuild/capture-*.txt
```

A bare run leaves the trigger OFF and is behaviour-identical to the tool as committed at
`50d7466` — verified by diffing the whole JSON report (probs, dips, histogram, commits,
summary, forensics, the 36-row sweep) on `jfk.wav` and `canary_digits.wav`, local and cloud.

### New report sections

* **10. RMS** — the RMS histogram split by Silero state (speech / dead band / silence), then
  per dip the min and median chunk-RMS of its below-`RELEASE` frames. **Read the `speech`
  column first**: a threshold with speech frames under it is a mid-word cut waiting to happen.
* **11. Flat runs** — first a histogram of EVERY run of consecutive frames under each
  candidate threshold `{==0, 10, 20, 40, 80, 160}` by length, with `>=k` columns for the
  chunk counts the swept holds need (`>=5` is a 128 ms hold, `>=4` a 96 ms hold; `>=12` is
  what Silero's own hangover already cuts). This is the direct instrument for the hold: a
  hold buys exactly the runs between its column and `>=12`. Then per CAP chunk, the longest
  run under each threshold — what the trigger would have had inside every chunk the wall cap
  had to dump. (`0` is reported as the run of exact zeros: a strict `rms < 0` can never fire.)
* **12. Flatline sweep** — `flatline_rms {10,20,40,80,160} x hold {96,128,160,224,320}` at the
  default hangover/release/cap and the 2 000 ms turbo floor, with the **trigger-off baseline
  as the first row**, each row carrying the flat chunks the hold needs and the gap it catches
  at any alignment. Four risk columns. **BRIDGED** counts flat cuts after which Silero speech
  resumes within the hangover — boundaries the shipped machine would *not* have made; on
  edited audio that is every intended cut. **BRIDGED, not digital 0** is the subset whose flat
  run was not exact silence — a boundary Silero would not have made across audio that was not
  an editor's gate. **That column is the ship gate: any value above 0 rejects the row.**
  `RISK (p, 3 frames)` and `SPLITS` (Silero speech at/beside the cut frame; on both sides)
  are kept for continuity but are weak: `p` is ~0 on flat audio whether it is a gate or a soft
  stretch inside a word, so they read 0 by construction on gated audio, miss a dead-band soft
  segment inside a word entirely, and flip with hold parity.
* **13. Phone cross-check** (`--phone-capture`) — the native `encode:` lines' inter-commit
  intervals beside the simulator's, index-paired from each side's first commit, with per-pair
  cumulative deltas and a matched-within-1s / 3s / unmatched summary. The `encode:` timestamp
  is an encode **end**, so only intervals are comparable and only while the queue is not
  backing up. And **not every commit has a line**: a VAD-empty commit returns before the
  encoder (`whisper_jni.cpp:815-820`), so silent cap cuts leave no `encode:` — the owner's
  own capture shows 128 s and 66 s encode gaps under a 15 s cap. The phone's sequence is a
  *subset* of the simulator's. **A cross-check, not a fit.**

The trace CSV gains an `rms` column when one is available; a 3-column CSV written before this
change still loads, and the trigger simply cannot fire on it.

## The Kotlin now carries the trigger (4.4, `feat/flatline-cut`)

`SileroEndpointer.onFlat` ships the flatline cut: `EndpointerTuning.FLATLINE_RMS_MAX = 10` (chunk RMS at or below is flat; the sim's strict `<` at 11) and `FLATLINE_CHUNKS = 5` (a COUNT, fires on the fifth; the sim's `flatline_hold_ms = 128`), armed only while the active source is captured playback (`Endpointer.armFlatline`). Its diag line reads `cut=flat`.
`machine.py` is the REFERENCE TWIN: the eight numbered decisions in `_on_flat` are the Kotlin's semantics, cited from its KDoc, and `SileroEndpointerFlatlineTest` mirrors `test_flatline.py` / `test_flatline_verify.py` test for test by name on the same `(p, rms)` traces.
Keep them in step: a change to either `_on_flat` or `onFlat` is a change to both, in one commit, with the twin test on each side.

## The speech-evidence floor (4.3.2, Layer 1)

The owner's report on 85: "the hallucinations do show up on longer silence … maybe a little bit of
background sound, and then that produces words sometimes." The mechanism: silence with a little
background nudges Silero over ONSET for a few frames, the gate opens, the 15 s cap commits the
buffer, and the NPU tier — which has no pre-encode speech filter — spends a full ~1.8 s encode on
a 30 s window of padding and decodes Whisper's stock sign-offs out of it.

The fix the app carries: `SileroEndpointer` counts, per uncommitted buffer, the frames the probe
scored at or above ONSET (`evidenceFrames`, `SileroEndpointer.kt:395`), the commit funnel reads
that count once before the engine's commit (`FloatingBubbleService.kt:3360`) and re-bases it after
(`:3362`), and `LocalWhisperEngine` resolves a KNOWN count under
`EndpointerTuning.MIN_SPEECH_EVIDENCE_MS = 192` — six onset frames — as `EmptyExpected` without
an encode (`LocalWhisperEngine.kt:375`, the `commit: seq=N skipped=no-speech-evidence …` line).
**It changes no cut**: the count is EVIDENCE ONLY, read at the funnel and never by a branch of
the state machine — which is the whole difference from the merge memory the 4.4 review rejected
(that one fed the cut). UNKNOWN (no frame of the buffer scored; the amplitude fallback; the
slow-probe cutout) is never skipped.

`machine.py` is the reference twin again: `SileroEndpointerSim.evidence_frames` /
`evidence_frames_at_offer`, `speech_evidence_ms()` and `on_buffer_committed(tail_retained)`
(`SileroEndpointer.kt:513/:533`), read and re-based in `ServiceSim._emit` exactly where the
funnel does it. Two details the port carries because the Kotlin had to:

* **the funnel re-bases the count, not `_clear_for_next_segment`** — on the VAD-cut path the gate
  is cleared before `on_frame` returns `True`, and the funnel reads the count after that; cleared
  with the gate, every real utterance would read zero and be skipped;
* **a retaining cap cut carries the tail's evidence** — the frames after the offered cut point
  open the next buffer's count, so a speaker whose last words fell inside the tail and then stopped
  is not skipped at the stop flush. The committed part keeps the whole count (over-count is safe).

Each `Commit` now carries `speech_frames` / `speech_evidence_ms` (`None` = the endpointer could not
say); `SimResult.skipped()` and `tail_skipped()` apply the engine's rule. Section 2 gains the
`MIN_SPEECH_EVIDENCE_MS` row; section 6 gains **commits SKIPPED at the evidence floor**, the decoder
work those skips save (one `service_ms` job each — the ~1.78 s encode is 87 % of it), the turbo duty
over the commits that still encode, the tail's verdict, and two columns on the commit table
(`evidence (fr)`, `engine`). `--json` carries the same under `summary` and per commit.

```sh
# the shipped floor: which of this clip's commits would the engine skip?
./.venv/Scripts/python.exe -m vadsim clip.wav

# a stricter or looser floor — a report knob, the cuts do not move
./.venv/Scripts/python.exe -m vadsim clip.wav --min-evidence-ms 320
./.venv/Scripts/python.exe -m vadsim clip.wav --min-evidence-ms 0      # nothing skipped
```

`tests/test_evidence.py` mirrors `SileroEndpointerEvidenceTest` by name and adds the seam: the
flickering silent window is skipped at the cap, the retained tail's carry crosses the cap's
`reset()`, `jfk.wav` has no skippable commit, and the cuts are byte-identical at every floor. Its
bed fixtures are derived from the floor (`FLOOR_FRAMES`, `FLICKER_PERIOD_FRAMES`) rather than
spelled, so the retune 256 -> 192 (nit N1, 2026-09-04) moved them instead of quietly passing.

HONEST LIMIT, in both machines: a music bed Silero scores as SPEECH for seconds has evidence and
is not caught here. The no-speech gate and the stock-phrase blocklist (Layer 2, NPU tier,
`HallucinationPolicy`) remain the defence there; this floor catches silence, breath, room tone, a
fan, a paused video — the owner's report.
