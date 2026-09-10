# Amendment to the 4.4.0 plan — the startup ring (owner ruling 2026-09-10)

> Two extra tasks in the SAME build as the previewer, placed BEFORE the certification task so the four-pack bundle and
> the acceptance sheet cover them. Authority order: this amendment, then
> `docs/superpowers/research/2026-09-10-startup-cutoff-investigation.md` (the measured trace — read it in full before
> writing anything), then the previewer plan/spec. Where the investigation and this amendment disagree about a number,
> the investigation wins; where they disagree about scope, this does.

## The ruling

> Owner, 2026-09-10, the report: "if I tap the transcribe button and start speaking right away, sometimes the first
> chunks, like, first maybe couple seconds of what I say gets cut off. So the listener should be listening when
> everything is fully ready to start transcribing."
> Owner, on the fix: "you can roll it into the build before it gets released, before it gets finished."

## The mechanism (measured; do not re-derive)

The ready haptic fires at the tap (`FloatingBubbleService.kt:2847`) but `startAudioInput()` — `AudioRecord` plus the
capture thread — is nested inside the engine's `onOpen()` (`:3010`), and `LocalWhisperEngine.onOpen` waits for the
native context load (`LocalWhisperEngine.kt:154-190`). The audio is never captured. Cold npu-turbo load = **4,107 ms**
(`capture-yt-84-flatline-0903-1936.txt` 19:29:48.854 → 52.961); CPU small 237 ms (Tab) / 1.5–1.9 s (Fold6) / 11.7 s
first-in-process. Nothing re-prewarms after `onTrimMemory` (`:3400-3415`), which is why it is intermittent. The cloud
tiers fire `onOpen` synchronously (`LiveTranscriptionEngine.kt:216`) and lose nothing outright — pre-handshake frames
are discarded at `RealtimeTransport.kt:272`, the first turn resolves `Lost(BACKLOG)`, and the local mirror
re-transcribes it.

## Task S1 — re-arm the prewarm after a memory trim (0.5 day, no capture-seam change)

The cheapest half of the problem and independent of S2: after `onTrimMemory` unloads the model
(`FloatingBubbleService.kt:3400-3415`), nothing warms it again, so the next tap pays the full cold load. Re-arm the
same prewarm path `warmLocalEngine` already uses, on the same conditions it already respects (a local tier selected,
no session in flight, not on a metered/battery-saver constraint if the existing prewarm respects one — match it, do
not invent policy). One diag line. Tests: a pure policy function for "should re-arm after trim" if the existing prewarm
has one, else a source pin that the trim handler reaches the prewarm. Nothing else changes.

## Task S2 — the startup ring (3 days)

**The shape.** `startRecording` opens the recorder FIRST and the capture thread starts immediately; every PCM chunk
goes into a bounded ring; the engine's `connect()` runs in parallel; when the engine reports ready, the ring is
DRAINED into the engine through the same `sendAudio` path live audio uses, then live audio continues straight through.
The user taps, speaks immediately, and nothing is lost.

**The five things that decide whether it is correct** (all named by the investigation; each needs a test):

1. **`probeArm()` must precede the capture-thread start** (`VadProbeLifecycle.kt:102-116`) — otherwise the Silero VAD
   is off for the whole session with no error. Today the ordering is implied by the nesting; when the nesting goes, the
   ordering must be explicit and pinned.
2. **`onSessionStart` must stay above `setActiveSource`** — otherwise the 4.4 flatline cut is permanently disarmed for
   device audio. Pin it.
3. **The drain must be PACED, not dumped.** A 5 s ring is ~360 ms of solid probe work on the capture thread, which is
   also servicing an `AudioRecord` ring that overflows in ≥128 ms. Drain in bounded slices interleaved with live reads
   (or on a dedicated drain path that cannot starve the reader), and prove no live audio is lost during a worst-case
   drain (a test that feeds live chunks while a full ring drains and asserts every chunk reaches the engine exactly
   once, in order).
4. **Replayed chunks carry OLD `nowMs` stamps.** `SileroEndpointer`'s own KDoc says a burst delivery makes the hangover
   fire LATE, never early — verify that holds for a 4 s burst, and check the cadence governor (`lastCommitMs`), the
   wall caps (`SegmentCapPolicy`), the flatline trigger and the speech-evidence gate against a replayed burst. State
   the choice explicitly: replay with the ORIGINAL stamps (the audio's true clock, so the endpointer's arithmetic is
   about real time) unless a test shows a concrete failure — then document the alternative and why.
5. **The device-audio mic leak the ring newly makes reachable.** With the recorder open before the source is settled,
   prove the mic can never contribute to a device-audio session (the latch rule) — there is no pin for this today;
   write one.

**Pins that fail BY DESIGN and must be re-specced, not deleted:** `EndpointerLifecyclePinTest:103` and
`BackpressureWiringPinTest:77` both `indexOf` the literal `val started = startAudioInput()` inside `onOpen`;
`CapSeamPinTest:75`'s exactly-once census needs updating for the drain path (sendAudio must still be first and exactly
once per chunk — the drain is the same path, so the census counts sites, not calls).

**Ring size:** cover the measured worst case with margin — 4,107 ms measured, so 6 s at 16 kHz PCM16 = 192 KB. Bounded
and documented; on overflow drop the OLDEST audio (the newest speech matters more) and log one line with the dropped
duration.

**The haptic (B, folded in):** the ready cue moves onto true readiness — the tap gets its own distinct short
acknowledgement if the house style wants one, but the "listening" cue fires when the engine is ready. With the ring in
place the user is already being recorded, so this is honesty about state, not a gate on capture.

**By-product to fix in the same task:** `NpuWhisperBackend.kt:418`'s "~525 ms" comment understates the shipped tier
about eightfold (it is an encoder-only spike figure) — replace it with the measured 4,107 ms cold load and its capture
citation.

## Acceptance rows (fold into the certification task's sheet section)

- **S1.** Tap, speak immediately, on a warm app: every word appears. Repeat after backgrounding the app for a few
  minutes (a memory trim): still every word — this is the row the report was filed about.
- **S2.** Same on each local tier the device offers, and on device audio (start a video and tap): nothing lost, and the
  transcript's first chunk is not a fragment.
- **S3.** The "listening" cue does not fire before the app is actually ready; the delay between tap and cue on a cold
  NPU load is a few seconds and the words spoken in that gap still arrive.
