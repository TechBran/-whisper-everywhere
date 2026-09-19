# Speaker-label spike, session 1 — Tab S10+, 2026-09-18 16:34-16:47

**Build:** 4.9.1/99 + the spike commits 8c8d313..0e8768b (native geometry exports, CAM++ embeddings on the `speaker-embed` executor, `SpeakerTracker` with `T_SAME 0.55 / T_NEW 0.45`, EMA 0.2, `MIN_EMBED 1.0 s`, `MIN_NEW_SPEAKER 1.5 s`, cap 8). Speech model: large-v3-turbo Q8 (loaded 16:33:17). Device audio from YouTube. The owner ran three of the four planned sessions (no second person available for D). One `speaker:` diag line per chunk: `seq segs embedMs ids best dur confirmed load`.

| session | chunks | fingerprints | speakers opened | true voices | best-sim p10 / median / p75 | share < 0.45 | ms per fingerprint | finalize median / max (turbo) |
|---|---|---|---|---|---|---|---|---|
| A — TEDx talk | 18 | 51 | **5** | 1 | 0.38 / 0.74 / 0.88 | 15 % | ~131 | 6,538 / 13,212 |
| B — two-person interview | 18 | 37 | **5** | 2 | 0.44 / 0.71 / 0.85 | 12 % | ~306 | 11,100 / 16,355 |
| C — podcast, three or more | 18 | 42 | **7** | 3+ | 0.28 / 0.73 / 0.89 | 19 % | ~247 | 11,036 / 15,105 |

**Cost: acceptable.** 130-310 ms per fingerprint on one thread, at most 1.06 s per chunk, all on the embedder's own executor. Turbo's finalize times (6.5-11 s medians) sit in the range the same tablet showed on turbo the evening before without the spike (7.2-10.8 s), so no regression is attributable to the embedder, though the two are confounded on this rung.

**Quality with fixed thresholds: not shippable.** One voice (A) was split into five speakers; 15 % of its segments scored below `T_NEW` against their own speaker's centroid (minimum 0.04). In B the two real voices did separate — ids 1 and 2 hold 32 of 37 segments — but three spurious singletons opened. In C one id absorbed 27 of 42 segments while seven were opened. The same-speaker similarity tail (0.04-0.45) overlaps the cross-speaker range seen in B (0.33-0.42), so no single pair of thresholds separates them.

**What the sequences say.** Spurious speakers are singletons or near-singletons (A: ids 4 and 5 hold 3 and 1 segments; B: ids 3, 4, 5 hold one each; C: 4, 5, 6, 7 hold one or two), opened on short segments (p10 duration 1.0-1.1 s; 7-10 segments under 1.5 s per session) or on segments with music, laughter or applause under the voice. The EMA centroid then absorbs those embeddings and drifts, which makes later genuine segments look foreign.

**Changes the numbers ask for (to be tested offline on dumped embeddings, not on the owner):**
1. Short (< 2 s) or low-confidence segments never open a speaker and never update a centroid; they are assigned to the current speaker.
2. Match against a speaker's recent fingerprints (max similarity over the last 5), not only a running centroid.
3. Lower band, provisionally `T_SAME ≈ 0.45`, `T_NEW ≈ 0.30`, to be set from the dump.
4. A speaker is CONFIRMED only after two segments ≥ 2 s; unconfirmed speakers are merged back into the nearest confirmed one at the end of each chunk, and the panel — which is rewritable — is relabelled. Online-then-refine fits the product: labels can appear a chunk late but must not flicker.
5. If CAM++ still splits one voice after 1-4, try WeSpeaker ResNet34 / ERes2Net on the same dumped audio.

**Next:** the spike writes every fingerprint (512 floats, duration, chunk, assigned id) to `filesDir/speaker-spike/<session>.jsonl`; segment audio is dumped only behind a hidden switch the owner turns on for a session he chooses. The tuning loop then runs on the PC against these three sessions.

## Session 2 — the fingerprint dump, and the offline model comparison (2026-09-18 17:24-17:35, three new clips: exactly one, two and three speakers)

The owner re-ran three sessions on the dump build (c3980cd..e4e51a4); 132 segments' fingerprints and audio were pulled to the PC. The PC's sherpa-onnx (1.13.8) reproduced the tablet's CAM++ fingerprints exactly (mean cosine 1.000 over the one-speaker session), so the failure below is the model's, not the pipeline's.

**Retrospective clustering (average linkage on segments ≥ 2 s) with the shipped CAM++:** no threshold yields 1/2/3; the one-speaker session's segments score 0.26 (p10) to 0.87 against each other, and the two/three-speaker sessions cut into 36/4 and 26/4/1. **The stricter online rules on CAM++ fingerprints:** 2/2/2 for truths 1/2/3 at every band tried. The rules cannot rescue the signal.

**Five models on the same 132 segments** (PC times are relative only):

| model | dim | size | same-speaker sim, one-voice session (min / p5) | one global threshold giving 1/2/3 | online tracker bands giving exactly 1/2/3 |
|---|---|---|---|---|---|
| CAM++ VoxCeleb (shipped) | 512 | 29.6 MB | 0.04 / 0.26 | none | none |
| WeSpeaker ResNet34-LM | 256 | 26.5 MB | 0.78 / 0.83 | 0.65 | none — merges speakers (cross-speaker p95 0.73-0.76 above same-speaker p5 0.63) |
| WeSpeaker CAM++-LM | 512 | 29.3 MB | — / 0.29 | 0.375-0.40 | not simulated |
| 3D-Speaker ERes2Net-base | 512 | 39.6 MB | 0.47 / 0.55 | 0.275-0.30 | one band only (0.45/0.30) |
| **NeMo TitaNet-small** | 192 | 40.3 MB | **0.57 / 0.67** | 0.275 | **twelve bands, 0.45-0.70 / 0.25-0.60** |

ERes2Net and TitaNet agree on 100 % of segment pairs in both multi-speaker sessions (25/15 and 15/11/5 on the ≥ 2 s segments); ResNet34 agrees with them on 95 % / 85 %. Two independent models finding the same structure is the best proxy for truth available without hand labels.

**Decision: TitaNet-small** (`nemo_en_titanet_small.onnx`, 40,257,283 B, sha256 ad4a1802485d8b34c722d2a9d04249662f2ece5d28a7a039063ca22f515a789e, CC-BY-4.0 — attribution goes into the OSS notices; the clearance sheet row is a production gate). Tracker rules that made it work, all verified in simulation on this data: a segment shorter than 2.0 s never opens a speaker and never updates one (it inherits the current speaker); matching is the maximum similarity over a speaker's last five fingerprints, not a running mean; a speaker is confirmed after two qualifying segments; after each chunk, an unconfirmed speaker whose centroid is within T_SAME of a confirmed one is merged into it. Band: **T_SAME 0.50, T_NEW 0.30** (the centre of the working region). Cost on the PC equals CAM++'s (48 ms per fingerprint), so the tablet's 130-300 ms per fingerprint carries over.

**Known limit, accepted by the owner ("if we can detect that, great; if not, we'll live with it"):** an interruption shorter than two seconds is labelled as the current speaker.

## Session 3 — TitaNet-small on the device (2026-09-18 18:44-19:1x, the same three clips as session 2)

Build 04dbd88..daccdbf (TitaNet-small bundled; the six tracker rules; band 0.50/0.30). Device audio, no audio dump. The device's own fingerprints (jsonl mirror) scored per session:

| clip | fingerprints | speakers opened | confirmed | notes |
|---|---|---|---|---|
| one voice | 23 | 1 | 1 | every fingerprint on Speaker 1; same-voice similarity min 0.59, median 0.85 |
| two voices | 38 | 3 | 2 | ids 1 (27) and 2 (10) alternate in conversational blocks; id 3 is one 4.2 s segment the owner identified as an advertisement that started mid-clip — a real third voice, left unconfirmed because it never got a second segment |
| three voices | 65 | 3 | 3 | ids 1 (21), 2 (12), 3 (32); a retrospective three-way clustering of the same fingerprints agrees with the online assignment on 86 % of pairs |

No spurious speaker opened in any session; the merge step never fired and was not needed. Embedding cost on the tablet ~290 ms per chunk (median) on the embedder's own thread.

**Verdict: the pipeline is ready for the labels.** Spec §3.4 is corrected to name TitaNet-small; the tracker constants are the ones committed in a9f0972.

## Session 4 — the labels build in the owner's hands (2026-09-18 20:21-20:41): two failure modes, neither the model

The owner ran "quite a few" sessions on the labels build (Task 5, dc122c4). The first ones worked (relabel lines at 20:24:27 and 20:27:50; the 20:23 and 20:27 dumps cut cleanly into two voices, 15/13 and 24/14 on the ≥ 2 s segments). Then "it just stopped working — I couldn't get any different speakers". The dumps say why:

| session | fingerprints | segment length median / p90 | under 2 s | over 6 s | pairwise sim median | two-way cut (≥ 2 s) | what happened |
|---|---|---|---|---|---|---|---|
| 20:23 | 47 | 2.7 / 7.8 s | 19 | 7 | 0.17 | 15 / 13, within 0.40, between 0.08 | worked |
| 20:27 | 50 | 3.1 / 7.2 s | 12 | 10 | 0.30 | 24 / 14, within 0.46, between 0.24 | worked |
| 20:31 | 22 | **6.3 / 14.3 s** | 1 | 13 | **0.84** | 20 / 1, within 0.86, between 0.79 | no separation in the embeddings at all — the same signature as the confirmed one-voice clip at 18:41 (0.85 / 0.71): either one voice, or two voices mixed inside 6-14 s segments |
| 20:35 | 38 | 3.1 / 7.3 s | 12 | 7 | 0.74 | 25 / 1, within 0.80, between 0.69 | weak separation; the tracker opened a second speaker late and never confirmed it |
| 20:39 | 15 | **1.8 / 4.4 s** | **11 of 15** | 0 | 0.20 | 2 / 2 | clearly distinct voices, but 11 of 15 segments were under the 2.0 s gate — nothing could open or confirm |

**Failure mode A — conversational audio with short turns (20:39):** the `MIN_OPEN 2.0 s` gate, chosen on session-2 data where segments ran 2.4-7 s, starves on rapid turn-taking. Fix: segments of 1.0-1.5 s may MATCH an existing speaker (assigned when the max similarity ≥ T_SAME, else the current speaker); segments ≥ 1.5 s may OPEN a speaker and count toward confirmation; only segments ≥ 2.0 s update a speaker's recent set.

**Failure mode B — long segments (20:31, possibly 20:35):** when the endpointer hands over 6-14 s of speech, two alternating voices share one segment and one fingerprint; the dominant voice wins and every fingerprint looks like every other. The owner's own reading ("the VAD doesn't seem to chunk on the boundaries") is this. Fix: a VAD segment longer than 5 s that contains two or more whisper segments is fingerprinted PER WHISPER SEGMENT (windows ≥ 1.5 s, adjacent short whisper segments coalesced), so the text-to-speaker mapping is per sentence rather than per pause. Cost: more fingerprints per long chunk (~130-300 ms each, still off the whisper thread); the finalize fence rises from 1.5 s to 2.5 s.

**Owner's answer (2026-09-18, later):** 20:31 was the one-voice clip — a long transcription of an Infographics Show video with a single narrator. So that session was the tracker being RIGHT (one speaker, no labels), and failure mode B is not demonstrated by tonight's data; it stays in the build as the design for long turns (a podcast guest's two-minute answer would otherwise be one fingerprint), bounded to VAD segments over 5 s with two or more whisper segments. Failure mode A (20:39) is the demonstrated one.

## Session 5 — the graded gates in the owner's hands (2026-09-18 late / 2026-09-19 02:12-02:27)

Owner: "it is working a lot better for quick back and forths … the boundaries is where the speaker switch is just not catching the beginning of when someone starts to speak … the bulk of it, yes, is correct." The logcat ring buffer had rolled past the sessions; the fingerprint dumps hold them. The 02:12 session: 218 fingerprint windows, median 3.0 s, 105 windows of 2.5-5 s and 26 over 5 s, 27 under 1.5 s. With `LONG_SEGMENT_SECONDS 5.0` the per-sentence windowing applied to only the 26 longest, so in the 2.5-5 s majority a new speaker's first sentence shared a window with the previous speaker's last one and took that speaker's label. The label change therefore lagged by up to one sentence.

**Change:** `LONG_SEGMENT_SECONDS 5.0 → 2.0` and `MIN_WINDOW_SECONDS 1.5 → 1.0` (a 1.0-1.5 s sentence window is matched to a known speaker by the graded gates but cannot open one). Boundaries then align to whisper's sentence boundaries in nearly every segment, which is where a new speaker usually begins. The remaining lag is inside a sentence (a genuine interruption mid-sentence), which only word-level change-point detection could catch — the next lever if this is not enough: whisper's token timestamps plus short sliding fingerprints across a detected change.

Cost: fingerprints per chunk ≈ sentences per chunk; on the Tab 130-300 ms each on the embedder's thread. A chunk with more than ~8 sentences may exceed the 2.5 s finalize fence, losing the last chunk's ids (accepted for now; measured next).

## Session 6 — per-sentence windows in the owner's hands (2026-09-19 03:02-03:30)

Owner: "working much better … still the same edge cases: every once in a while no speaker changes being detected at all, one big run-on paragraph; other videos work just fine." Three dumps under the 2.0 s / 1.0 s windowing (fingerprints per chunk median 4, max 8; embed cost per chunk median 336 ms, max 575 ms — no chunk near the 2.5 s finalize fence):

| session | windows | median window | online ids | retrospective two-way cut (≥ 1.5 s) | reading |
|---|---|---|---|---|---|
| 03:02 | 57 | 2.4 s | seven ids opened, four of them near-singletons | 45 / 3, within 0.31, between 0.05 | noisy fingerprints on short windows: the tracker chattered |
| 03:05 | 324 | 2.5 s | 1 (172) and 2 (115) alternating in blocks, plus a 25-window id 3 | 255 / 9, within 0.35, between 0.01 | the online blocks look like real turn-taking, but the global structure is weak: within-speaker similarity on 2-3 s windows is only ~0.35 |
| 03:27 | 64 | 2.6 s | 1 for the first 14 windows, then **2 for all remaining 50** | **40 / 14**, within 0.50, between 0.06 | the voices ARE separable retrospectively, but the online matcher locked onto id 2 and never let speaker 1 back — the "one big run-on" the owner saw |

**What this says.** Per-sentence windows bought boundary alignment at the price of noisier fingerprints (2-3 s of speech instead of 5-10), and the online matcher — greedy, max-over-the-last-five — can lock onto one id once a speaker's recent set holds a few mixed or noisy fingerprints, because a single similar fingerprint among five is enough to match. The 03:27 session is the clean demonstration: a retrospective clustering of the same fingerprints finds the two speakers (40/14) that the online pass merged. **Word-level timing would not have changed any of these three outcomes**: it refines WHERE a boundary falls when two voices are already being told apart; it cannot create a change the matcher never made.

**The next lever, therefore: online for display, retrospective for truth.** Every N chunks and once at finalize (before the fence), re-cluster the session's fingerprints (duration-weighted average linkage; a cluster is a speaker only above a minimum mass), map the online ids onto the clusters, and relabel the panel through the existing remap path; the exports at stop are rendered from the retrospective labels. The tracker's live state is re-seeded from the clusters (each speaker's recent set = its longest windows). Word-level change detection stays the lever for mid-sentence interruptions, after this.
