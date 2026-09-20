# Speaker boundaries without pauses — design

**Status:** approved in conversation section by section, 2026-09-19; written for the owner's review before an implementation plan. Local models only. Both tiers.

## 1. The problem, in the owner's words

> "Detecting many different speakers, especially when it comes to short back-and-forths … that pause between speaker to speaker, it's not always being detected. I think it's because of our hangover with our VAD … literally every YouTube video where there's more than one person, they seem to cut the pauses between speakers dramatically, and we're not able to chunk them the way we want."

And, asked where a missed speaker actually starts: **mid-sentence too**, not only at sentence ends.

**The correction that shapes everything below.** A voice-activity detector finds absences of voice. When an editor cuts the gap to zero there is no absence, so no hangover setting — 350 ms today, 150 ms, 50 ms — can find the boundary: it is not in the signal as silence. It is in the signal as a *change of voice*. Boundaries for edited media have to come from a detector that answers "who is speaking now", not "is anyone speaking".

The current pipeline (4.10.1/101) derives every speaker window from pauses (the VAD's segments) subdivided by whisper's sentence boundaries. That is why clean-cut interviews work and hard-cut ones do not.

## 2. What this adds, in two layers

### Layer 1 — timing: every tier says *when*, as finely as it can

| tier | today | after |
|---|---|---|
| CPU (small/medium/turbo Q8) | segment times only (`whisper_full` segment t0/t1), already exported | **per-token times**: `params.token_timestamps = true`, read `whisper_full_get_token_data(...).t0/t1`, exported beside the existing geometry as `[t0cs, t1cs, byteStart, byteEnd]` per token |
| NPU (npu-turbo, npu) | **nothing** — `lastGeometry` is null; 4.10.1 substitutes a standalone VAD and gives the whole chunk one speaker | **sentence times**: stop prompting `<\|notimestamps\|>`, stop suppressing `timestampBegin until vocab` in `NpuDecodePolicy.suppressList`, and read the emitted timestamp tokens as segment bounds (1,501 slots, 20 ms resolution, already accounted for in `qnn_asr.cpp`) |

**`dtw_token_timestamps` must stay false.** whisper.cpp gates the new-segment callback on `!dtw_token_timestamps` (the landmine comment at `whisper_jni.cpp:1193`), so enabling DTW silently kills local partial streaming — the live words strip — with no error. The heuristic token path does not. A pin asserts the flag is never set.

Word-level timing on the NPU tier is **out of scope**: it needs cross-attention weights out of the QNN graph. That tier cuts text at sentence boundaries; a mid-sentence change there gives the sentence to the dominant voice.

Layer 1 on its own retires 4.10.1's chunk-granularity compromise: the NPU tier goes from one speaker per 6-8 s chunk to one per sentence, which is what the CPU tiers do today.

### Layer 2 — boundaries: where the voice changes, with or without a pause

Sherpa-onnx 1.13.7 (already shipped) exposes `OfflineSpeakerDiarization` — pyannote segmentation plus an embedding model plus clustering — as **one call**: `process(FloatArray): Array<OfflineSpeakerDiarizationSegment>` with `start`, `end`, `speaker`. The raw segmentation model is **not** separately reachable from the Kotlin API. That constrains the design:

- **We use its boundaries and discard its speaker numbers.** They are local to one call; identity is session-wide and stays with `SpeakerTracker` + `SpeakerReclusterer`.
- **Its internal embedding is pointed at the TitaNet model already bundled**, so the app gains one new asset: pyannote segmentation-3.0, ~6 MB, bundled and uncompressed like the others.
- **Cost is real and doubled**, because we cannot reach the segmentation alone: the call embeds internally and we embed again per resulting window. Estimated 1-2 s per chunk on the Tab. It never blocks text — it lives on the `speaker-embed` executor below delivered text — but it spends battery and eats the finalize fence.

**Therefore it is gated, not unconditional.** Run it on a chunk only when all of:
- `detectSpeakers` is on, and
- the chunk holds at least one window ≥ `SEGMENTATION_MIN_WINDOW_SECONDS` (2.0 s — a window too short to hide a second speaker cannot benefit), and
- that window's own fingerprint decision was not already confident (its best similarity fell in or below the hysteresis band, i.e. the tracker was unsure or opened a speaker).

The gate's constants come from the spike (§5), not from this document.

## 3. The pipeline, end to end

Per committed chunk, on the speaker thread:

1. **Text and times** from the tier (layer 1).
2. **Candidate windows** as today: VAD segments subdivided by sentence boundaries (`SpeakerSpans`, `LONG_SEGMENT_SECONDS 2.0`, `MIN_WINDOW_SECONDS 1.0`).
3. **Refinement, gated** (layer 2): for a qualifying window, `OfflineSpeakerDiarization.process` over that window's samples; its boundaries split the window further. Boundaries are snapped to the nearest token time (CPU) or sentence time (NPU) so a window edge is always a place the text can be cut.
4. **Fingerprint** each final window with TitaNet, as today.
5. **Identity**: `SpeakerTracker` online, `SpeakerReclusterer` retrospectively, both unchanged.
6. **Runs and labels**: `SpeakerRuns` / `SpeakerLabels`, unchanged — they key on window indices, which is what makes layers 1 and 2 affordable.

**Everything fails downhill.** Missing or failing pyannote → step 3 is skipped, today's windows stand. Missing token times → cuts fall on sentence boundaries. A failed fingerprint → the current speaker keeps the text. No path delays, alters or drops transcript text, and none of it runs on the whisper thread, so `CommitCadencePolicy`'s floors and the live strip are untouched.

**No new user-facing setting.** All of it lives under the existing "Detect speakers".

## 4. What this does not do

- **Overlapping speech.** Pyannote can report two people at once; there is one text stream, so the dominant voice takes the words. Recording it as a known limit, not solving it.
- **Word-level timing on the NPU tier** (cross-attention DTW).
- **Changing the endpointer.** Hangover, onset and release thresholds are untouched: the owner's case is edited media with no pause, where they cannot help. Live-conversation tuning is a separate, smaller piece of work if he wants it later.

## 5. The spike that precedes the plan

On the Tab S10+, with the owner running the clips and the controller reading the dump:
1. **Cost**: `OfflineSpeakerDiarization.process` over a single 2-8 s window — median and worst milliseconds, and the same for a whole chunk — against the 3,000 ms finalize fence.
2. **Benefit**: on a hard-cut interview, how many change points it finds that the current windows miss, and how many are spurious on the one-voice narrator clip.
3. **Token timing**: the added cost of `token_timestamps = true` on each CPU rung, and that the transcript text is byte-identical with it on and off.
4. **NPU timestamps**: that un-suppressing the range produces well-formed segment bounds and does not change the text.

The spike's numbers set the gate's constants and decide whether layer 2 ships at all. Layer 1 ships regardless — it is a prerequisite and it fixes the Fold6's granularity on its own.

**This spec therefore becomes two implementation plans, not one.** Plan A is layer 1 (both tiers' timing) plus the spike; plan B is layer 2 (gated segmentation), written only after the spike's numbers exist. Anything else would be planning work whose shape the measurement has not settled.

## 6. Two defects to diagnose BEFORE this work

Reported the same day, on 4.10.1: detection **stops partway through a long session**, and **does not always start** when it should. These are not design gaps and may change what gets built, so they are chased first, from the fingerprint dumps rather than from a rebuild. Prime suspects to check in order: the reclusterer's 600-seed cap and what happens to windows past it; the tracker's 8-speaker cap; the latch that can only rise; the embedder's remembered-failure rule; and the assigner's executor surviving a mid-session model switch.

## 7. Open questions for the owner

1. The pyannote model is a second bundled asset (~6 MB on top of TitaNet's 40 MB). Accepted in conversation; restated here for the record.
2. If the spike shows layer 2 costs more than ~1 s per chunk even gated, do we ship layer 1 alone and revisit, or tighten the gate further (for example, only on windows the retrospective pass has already disagreed about)?
