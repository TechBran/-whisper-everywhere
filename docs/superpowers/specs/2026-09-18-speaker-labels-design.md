# Speaker labels on committed text — design

**Status:** draft for the owner's review, 2026-09-18. Local models only; the cloud providers are untouched.

## 1. What the owner asked for (2026-09-18, verbatim fragments)

> "We want to be able to detect the speakers that are speaking and separate them … when text is committed, we should be able to detect when a new speaker or if a previous speaker was speaking and switch between speaker one, paragraph, then speaker two. Then when speaker one comes back, says maybe a few words, we want to be able to switch that. Right now the text just comes out as a big blob … If there's only one speaker then we keep that."

> "Live preview can just stay exactly as it is."

> "We stick to the local models for now. The cloud providers, they'll work just like they do. Our goal is we're trying to make our models better than the cloud providers."

> "The speakers only need to be in the live window. Otherwise we just give a new paragraph for every new speaker. In the window: first speaker speaks, nothing. Second speaker is detected, then we put the speaker label in there. When we copy it out, no need for that unless we see fit — maybe a setting where speaker labels go to the copied text or the saved text … if someone flips the toggle on, then they get the speaker labels."

## 2. The rules, stated once

| surface | one speaker heard | two or more heard |
|---|---|---|
| the transcript panel (the bubble's live window) | plain text, as today | a new paragraph at every speaker change, each starting `Speaker N:` — including the first paragraph, relabelled `Speaker 1:` the moment a second voice is confirmed (the panel's text is ours to rewrite) |
| text typed into another app's field | as today | a new paragraph at every speaker change; **no labels** (the app appends into fields and never rewrites what is already there, so a retroactive `Speaker 1:` is impossible and a forward-only label would be lopsided) |
| the clipboard copy and the saved transcript | as today | paragraph breaks always; `Speaker N:` labels **only when the new setting "Speaker labels in copied and saved text" is on** (default off) |
| the live preview strip | untouched | untouched |
| cloud sessions | untouched | untouched |

Speaker numbers are per session and restart at 1 each session. A speaker who returns after others have spoken keeps their number.

## 3. How it works

### 3.1 What already exists
- The native VAD cuts every chunk into speech segments before whisper sees it (`whisper_jni.cpp`, `whisper_vad_segments_from_samples`), and whisper's output carries per-segment timestamps on that same trimmed timeline. So "who spoke when" boundaries exist inside every commit already.
- The sherpa-onnx 1.13.7 library the app ships contains `SpeakerEmbeddingExtractor`, `SpeakerEmbeddingManager` and `OfflineSpeakerDiarization` (verified in the shipped jar). No new native library.

### 3.2 The pipeline, per committed chunk
1. **Segments.** The JNI returns, beside the text, the VAD segment boundaries (start/end sample in the trimmed timeline) and whisper's per-segment `(t0, t1, text)`.
2. **Fingerprints.** For each VAD segment of at least `MIN_EMBED_SECONDS` (1.0 s), a `SpeakerEmbedder` (sherpa `SpeakerEmbeddingExtractor`, its own single-thread executor, one model resident per session) produces an embedding vector. Segments shorter than that inherit the label of the segment before them.
3. **Matching — `SpeakerTracker` (pure Kotlin, no Android, unit-tested).** Cosine similarity of the embedding against each known speaker's running centroid:
   - best similarity ≥ `T_SAME` → that speaker; the centroid moves toward the new embedding (EMA, weight 0.2);
   - best similarity < `T_NEW` → a new speaker, if fewer than `MAX_SPEAKERS` (8) are known and the segment is at least `MIN_NEW_SPEAKER_SECONDS` (1.5 s) — otherwise the closest known speaker;
   - in between → the current speaker (hysteresis: a borderline segment never flips the label on its own).
   `T_SAME` / `T_NEW` start at 0.55 / 0.45 for CAM++ and are **set by the spike (§6)**, not by this document.
4. **Text to speakers.** Each whisper segment takes the label of the VAD segment it overlaps most; the commit becomes an ordered list of `(speakerId, text)` runs, adjacent runs with the same speaker merged.
5. **Emission.** The commit path renders those runs per the table in §2. The "two or more heard" state is a per-session latch that flips once the tracker holds two speakers with at least one segment each of `MIN_NEW_SPEAKER_SECONDS`; when it flips, the panel is rewritten with labels from the session start.

### 3.3 Cost and where it runs
- Embedding runs on the embedder's own thread, concurrently with the next chunk's whisper call. It never sits on the whisper thread, so the commit floor arithmetic (6 000 / 8 000 ms) is untouched and turbo's tight budget is unaffected.
- Expected cost: tens of milliseconds per segment on a phone CPU for CAM++; a 15 s chunk with three segments is well under a quarter of a second. **The spike measures this on the Tab before any threshold is set.**
- Memory: the model plus session state, roughly 30 MB resident while a session runs; released with the session like the previewer.

### 3.4 The model
- Candidate: 3D-Speaker **CAM++** (`3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx`, ~7 MB, from sherpa-onnx's speaker-embedding-models release). Language-independent speaker identity; the smallest option with a good VoxCeleb result.
- Fallbacks if the spike disagrees: WeSpeaker ResNet34 (~25 MB) or NeMo TitaNet-small (~26 MB).
- **Delivery: bundled in the APK** (7 MB against a 121 MB APK). No download flow, no Play pack, no network dependency for a core behaviour — the previewer's fetch machinery is not reused for this.
- **Licence: to be cleared through the same sheet as the preview packs before production** (the 3D-Speaker repository is Apache-2.0; the training data's terms are the item to check). The spike may run on the uncleared model; the production build may not.

### 3.5 What changes in the app (files)
- `app/src/main/cpp/whisper_jni.cpp`: return VAD segment boundaries and whisper segment timestamps beside the text (one struct, one JNI call; the diag line unchanged).
- `transcription/speakers/SpeakerEmbedder.kt` (the sherpa adapter, the one file that imports it — same discipline as the previewer's adapter), `SpeakerTracker.kt` (pure), `SpeakerRuns.kt` (whisper segments × VAD labels → runs, pure), `SpeakerLabels.kt` (the §2 table as a pure formatter for panel / field / export).
- `FloatingBubbleService`: the commit path calls the tracker and formatter; the panel rewrite on the latch flip.
- `PreferencesManager` + Settings: "Detect speakers" (default on) and "Speaker labels in copied and saved text" (default off).
- The saved-transcript store: paragraphs carry `speakerId` so the export toggle can be applied at export time, not at save time.
- Tests: `SpeakerTrackerTest` (same voice, different voices, the return of speaker 1, short segments inherit, the hysteresis band, the cap, EMA drift), `SpeakerRunsTest` (overlap mapping, merges), `SpeakerLabelsTest` (the §2 table, every cell), settings pins, a source pin that the live preview path never reads the tracker.

## 4. Non-goals (this version)
- Overlapping speech (two people at once inside one segment): the dominant voice wins. The library's pyannote segmentation can split these later, at more cost; not now.
- Naming speakers, or remembering a voice across sessions.
- The live preview strip.
- Cloud sessions — their providers keep behaving exactly as today.

## 5. Where it will be wrong, and what limits the damage
- Two similar voices can merge; one voice shifting register (calm → shouting, close → far from the mic) can split. Unsupervised matching cannot fully fix this; the hysteresis band, the minimum lengths and the cap keep a wrong split from cascading. A device with a bad mic will see more of it.
- The first label on the panel appears only after the second voice is CONFIRMED (a 1.5 s segment), so a two-word interjection by a new voice does not open a speaker on its own.

## 6. The spike (before the plan)
On the Tab S10+ and the Z Fold6: embedding cost per segment with the real chunk sizes; false-split and false-merge counts on (a) the TEDx talk (one voice: expect zero labels), (b) a two-person interview from YouTube, (c) a three-person podcast segment, (d) the owner dictating alone then with a second person in the room. Output: the two thresholds, the minimum lengths, and a go / no-go on CAM++.

## 7. Open for the owner
1. Default of "Detect speakers": on (proposed) or off.
2. The label text: `Speaker 1:` (proposed) or `Speaker 1 —`.
3. Whether the panel should also show a small marker at a speaker change while only one speaker has been confirmed (proposed: no — nothing until the second voice).
