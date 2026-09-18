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
