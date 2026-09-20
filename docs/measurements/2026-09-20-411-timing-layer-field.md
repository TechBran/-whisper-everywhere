# 4.11.0 in the field — the timing layer on both tiers, one night

**Build:** 4.11.0 / versionCode 102, on BOTH devices — a sideloaded release APK on the tablet
(installed 00:55) and the internal-track build on the phone.
**Devices:** Galaxy Tab S10+ (the experiment device, never had the Play copy) for the CPU tiers,
and the Z Fold6 (Play-installed from the internal track) for the NPU tier.
**Tier:** CPU throughout on the tablet — every `speaker:` line there carries `route=geom`. The
NPU route is answered separately below, from the **Z Fold6** running the same 102 off the internal
track the same night.
**Source:** 829 WE-DIAG lines, deduplicated from a live capture and the device ring buffer —
257 `speaker:` lines over five sessions between 00:56 and 01:48, 1,240 windows in total.

Owner-driven, unscripted material. Session 1 is the one-voice narrator clip; sessions 2-5 are
long mixed sessions of 14, 24, 8 and 1 minutes.

## What was being asked

Layer 1 gave the CPU tier per-token times and, with them, a rule that bisects any speaker window
still spanning `TOKEN_CUT_SECONDS` (4.0 s) at the token edge nearest its midpoint. That rule
applies to the tier that already worked, and it is a geometric guess rather than a detected voice
change, so the risk carried into this session was **a spurious speaker on one-voice material**.

## The answers

| question | answer |
|---|---|
| Did the bisection invent a speaker on one voice? | **No.** Session 1: 9 chunks, 1 speaker, 0 id changes, never even reached a second-speaker confirmation. |
| What does the embedding cost per chunk? | p50 **320 ms**, p90 **444 ms**, worst **600 ms**. |
| Does stopping feel slower? | **No.** All four stops drained in **178-448 ms** and every one reported `settled=true`, against the 3,000 ms finalize fence. |
| Are the windows the size the design wanted? | p50 **2.2 s**, p95 **3.5 s**. Only 4 of 1,240 exceeded 4.0 s. |
| Did the tier lose its timing anywhere? | **No.** Zero `token-times: segment N dropped` lines — whisper's per-segment equality check passed every time. |

**The four over-long windows are the fail-downhill path working, not a miss.** Three spanned
6.6-11.0 s. `bisect` only cuts at a token edge that leaves both halves at least
`MIN_WINDOW_SECONDS` (1.0 s), so a stretch the endpointer kept but whisper put no words inside —
music, applause, a held note — has no interior edge to cut at and stays whole. None of the four
opened a speaker. The alternative, cutting them at a geometric midpoint, would fingerprint two
halves of the same wordless noise; the spec's rule is the right one and it fired correctly.

## What the session found instead

Every new speaker was opened on an ordinary 1.6-3.6 s window at a best similarity of
**0.09-0.29** — below `T_NEW` (0.30), which is exactly the decision that threshold exists to
make. Long windows opened none:

| window length | windows | opened a new speaker |
|---|---|---|
| 0.0-1.5 s | 239 | 0 |
| 1.5-2.5 s | 531 | 17 |
| 2.5-4.0 s | 462 | 12 |
| over 4.0 s | 8 | 0 |

Across all 1,240 windows the best-match similarity ran p10 **0.40**, p50 **0.63**, p90 **0.77**.
**16% of decisions landed in the unsure band** between `T_NEW` and `T_SAME` — that population is
precisely what layer 2's gate is meant to take, and it is the number to size that gate against.

## The defect this session exposed

Session 3 logged `speaker-recluster: n=505 clusters=11 confirmed=11` — **eleven clusters against
a cap of eight** — and stayed over the cap (10, 10, 10) for its remaining four minutes.

`SpeakerReclusterer.MAX_RECLUSTER_FINGERPRINTS` caps the pass's SEEDS and says so in its own
KDoc; nothing capped its ANSWER. `SpeakerAssigner` reseeds the tracker with one live voice per
cluster, and the tracker opens a new speaker only while `liveCount < maxSpeakers`. Over the cap
that guard is dead for the rest of the session: every later unheard voice is handed to the
closest voice already known and, per that line's own comment, teaches it nothing. The tracker's
own corrective merge in `endChunk` is inert at the same moment, because it skips CONFIRMED
voices and a reseed marks every voice confirmed.

Retrospective labelling still recovers, since `recluster` reads the stored fingerprint vectors
rather than tracker state — so the panel can be re-cut. What degrades is the live label between
passes, which is also the one that drives paragraph breaks in text typed into other apps.

**Fixed 2026-09-20:** the cap is now a parameter of `recluster`, defaulted to
`SpeakerTracker.MAX_SPEAKERS` and passed by the assigner as the tracker's OWN `maxSpeakers`, so
the two halves cannot disagree. Over the cap the speakers who spoke longest keep their identity
and the rest fall into the absorption loop every sub-bar cluster already goes through, so no
window is dropped.

**What that fix earns is narrower than it first appears, and the source says so.** At exactly the
cap the opening guard is false as well, so capping to eight does not hand the tracker back the
ability to open a ninth voice. What it earns is the bound the tracker documents about itself, a
label space that stops drifting upward, and displacement — every pass re-decides which speakers
survive, so a person who out-speaks the weakest survivor takes that slot at the next pass rather
than being locked out for the session. What it costs is a genuine ninth speaker on material the
cap is too small for.

**And that turned out to be his material, so the number moved the same day.** Told that these
sessions had been run deliberately on multi-speaker podcasts — *"certain podcasts will have, like,
almost ten people. And I did that intentionally, and that part did work pretty well"* — the answer
is that eight was never tested against the owner's own audio. The sessions he liked worked
*because* 102 left the retrospective pass uncapped and it returned ten and eleven clusters where
it found them; capping at eight would have merged the ninth and tenth people away. `MAX_SPEAKERS`
is **16** from 4.11.2, his ruling: clear headroom over ten, with cost no object (an extra live
voice is five 192-float vectors and five more dot products against a 320 ms embedding) and the
real limit being that a phantom needs six seconds of misattributed speech to earn a label, so a
higher cap leaves more room for one on music or crowd noise. On his material the trim is now a
backstop rather than something that fires.

**A second defect surfaced while fixing the first and is fixed with it.** `Cluster.longest` is
the seed set the tracker rebuilds a live voice from, and it was read after absorption — yet
absorption merges two things the pass has just proved are NOT one voice, since every pair
reaching the 0.30 merge threshold was already merged a step earlier. A sub-bar leftover is short
and rarely won the duration sort, but a cluster the cap trims cleared the six-second mass bar and
lost only on relative mass, so its long windows would routinely have taken the survivor's seed
slots. The tracker would then answer near 1.0 to the wrong person, and crediting that match would
evict the survivor from its own id. Identity is now snapshotted from a cluster's own
pre-absorption windows, which also keeps the file's rule that a one-second window is labelled and
never a voter. Pinned by `aTrimmedClustersWindowsDoNotBecomeTheSurvivorsVoice`, which fails
against the previous line. Pinned by `theAnswerHonoursTheSameSpeakerCapTheTrackerDoes` (twelve orthogonal voices,
capped to eight) and `theCapIsTakenFromTheTrackerAndCanBeLowered`.

## Throughput, since token timing is new on this tier

257 committed chunks. The tier keeps up with room to spare, which is the question
`token_timestamps = true` had to answer on the tier that already worked:

| | |
|---|---|
| decode wall per commit | p50 **5.6 s**, p90 **10.5 s** |
| audio per commit | p50 **11.6 s** |
| wall ÷ audio | p50 **0.62**, p90 **0.75** |
| chunks that took longer than their own audio | **7 of 257** |

Four of those seven are a session's first chunk, which carries the model load and is expected.
**Two are not, and they are the one thing in this session I would watch**: at 01:15:13, 81
seconds into session 3, a chunk spent **39.0 s on 4.3 s of audio** (`segments=1`,
`audio_ctx=512`), and the chunk a minute later spent 14.3 s on 9.7 s. Then it recovered and the
remaining 120 chunks of that session stayed under 0.8. A reclustering pass ran at the same
second but cost 0 ms, so it is not the cause. One stall in 257 chunks is not a verdict either
way; it needs a second long session before it is called thermal, contention, or a decoder loop.

## Reclustering cost, for the record

The pass grows with the session: 0-2 ms early, **96 ms** at n=566. `MAX_RECLUSTER_FINGERPRINTS`
(600) bounds it, and it runs on the speaker thread below delivered text, so it is not near
mattering yet. Worth re-reading on a session long enough to sit at the seed cap.

## The Fold6, the same night — the NPU route answers

**Build:** the same 4.11.0 / 102, installed from the **internal track** (`installerPackageName=
com.android.vending`), so it carries Google's app-signing key and is the owner's own test path.
**Session:** 01:50:30 to 01:55:43, **5 minutes 13 seconds**, 45 committed chunks, every one
`route=vad` — the NPU tier. 192 WE-DIAG lines from the device ring buffer.

**The one line that says the fix is live.** 4.10.1 gave that tier exactly ONE window per chunk,
always, because its standalone endpointer returned one 9-15 s segment on hard-cut media. Now:

| | 4.10.1 | this session |
|---|---|---|
| windows per chunk | **1, always** | **p50 2, max 8** |
| single-window chunks | all of them | **12 of 45**, and those are the 2-3 s chunks where one window is right |
| a speaker change inside one chunk | impossible | **seen**: `segs=1 windows=6 ids=[2,2,2,1,1,1]` |

`segs=1 windows=8` on another chunk is the same thing at full stretch — one unbroken stretch of
speech, no pause anywhere in it, cut into eight windows by the sentence bounds the QNN decoder now
emits. That is the capability 4.11.0 was built for, working on the device that reported the
failure.

**And it held for the whole session, which is the original complaint.** The report that started
this work was *"after about two minutes, they just stopped, and everything just becomes one
speaker."* This session ran more than five minutes with ids alternating throughout — 9 changes
across 45 chunks — and the retrospective pass answered **2 clusters at every one of its ten
passes**, changing at most 3 labels. No collapse, no over-split, no drift.

**AO10 passes outright — nothing ran away when the timestamp range was un-suppressed.** This was
the real risk on this tier: dropping `<|notimestamps|>` re-conditions the decode and every emitted
timestamp spends one of the 197 budget positions.

| | |
|---|---|
| decodes that terminated by EOT | **45 of 45** — none hit the budget |
| steps used | p50 **25**, worst **75** of 197 |
| repetition-guard rung reached | **0 on all 45** — the re-based cut never tripped |
| no-speech probability | **0.00 throughout**, so the 4.3.2 silence gate still reads a sane value |
| encode / decode | p50 **1,874 ms** / **209 ms** |
| fingerprinting | p50 **104 ms**, worst **343 ms** |
| stop drain | **258 ms**, `settled=true` |

Similarities on this material ran 0.69-0.89 — clean separation with nothing in the unsure band,
the opposite of the tablet's mixed sessions. Two voices, cleanly cut, on a tier that could not cut
them at all two builds ago.

## Still open

- **The tablet's 39 s stall** (above) wants a second long session before it is called anything.
- **AO11(b)** is still the owner's turn-by-turn read of two-voice text against 4.10.1.
- **Layer 2's gate** should be sized against the tablet's 16% unsure band, not against a guess —
  and the Fold6 session is the reminder that on clean material there is nothing for it to do.
