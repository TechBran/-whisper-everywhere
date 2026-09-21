# 4.11.3 on the Tab S10+ — the uncapped panel, and the 16-speaker cap in the field

**Build:** 4.11.3 / versionCode 105, sideloaded release APK.
**Device:** Galaxy Tab S10+ (the experiment device).
**Tier:** `ultra-q8` — large-v3-turbo Q8_0 on CPU, the owner's own choice for this session.
**Session:** owner-driven, a little over 30 minutes of a multi-speaker podcast including ad reads,
watched end to end. 144 committed chunks, 558 WE-DIAG lines, 94 `panel:` samples.

This is the session that answers the two questions 4.11.2 and 4.11.3 left open.

## 1. Removing the panel's character ceiling costs almost nothing

4.11.3 removed `PREVIEW_CAP_CHARS` so the panel renders the whole session, and the commit said
plainly that the cost was a risk to be measured rather than argued — two O(session) passes per
commit, the layout one on Main. Measured, across 20,164 to 31,569 characters:

| | |
|---|---|
| the sink building the whole string (`renderMs`) | p50 **0 ms**, max **1 ms** |
| the panel measuring the layout (`setTextMs`) | p50 **20 ms**, p90 **27 ms**, max **33 ms** |
| slope over the measured range | about **0.38 ms per 1,000 characters** |

Extrapolated on that slope: ~30 ms at an hour of speech, ~45 ms at two hours, ~62 ms at three.
Once every six to eight seconds, never per frame.

**Verdict: the ceiling stays off and incremental rendering is not needed.** The doubt recorded in
the 4.11.3 commit — "if it ever bites, the answer is incremental rendering" — is discharged; the
controller's estimate of the risk was higher than the hardware's answer.

**What the old ceiling was actually costing.** The first `panel:` line fires the moment a session
crosses 20,000 characters, which is where 104 began discarding from the front. This session went
on to 31,569, so roughly **11,500 characters — about fifteen minutes of speech — would have been
thrown off the head of it** on the shipped build. Owner, on 104: *"usually I notice it doesn't
make it even that far before it starts chopping the head off."*

## 2. Sixteen speakers was the right number, and this is the evidence

4.11.2 raised `SpeakerTracker.MAX_SPEAKERS` from 8 to 16 on the owner's ruling, because his own
podcast material runs to about ten voices. That ruling had no device measurement behind it. It
does now:

- The retrospective pass answered **13 clusters, all confirmed** (`clusters=13 confirmed=13`),
  with ids issued up to 16 across reseeds.
- The owner watched the whole session and counted **eleven or twelve speakers including ad
  reads** — voices that appear for a few seconds and never return.

Thirteen found against eleven or twelve observed is agreement, not over-splitting. **Under the
old cap of 8, five of those voices would have been absorbed into whoever they most resembled** by
the trim 4.11.1 added — so the raise is what makes this session correct, and the trim correctly
did nothing, 13 being under 16.

The over-splitting risk named when the number was chosen — that a higher cap leaves more room for
a phantom speaker to earn a label — did not appear on this material.

## 3. The seed cap was reached for the first time

`speaker-recluster: n=600 clusters=13 confirmed=13 changed=0 ms=120` — `n=600` is
`MAX_RECLUSTER_FINGERPRINTS` exactly, so a 30-minute session is enough to reach it. Past it a
window stops defining clusters and is labelled by the company it keeps (step 5), which is the
documented behaviour and is why that cap is on the seeds and not on the answer. Cost at the cap:
**120 ms**, up from 96 ms at n=566 the previous night. Bounded, on the speaker thread, below
delivered text.

## 4. Throughput, on the rung that has never had margin

144 commits on `ultra-q8`:

| | |
|---|---|
| decode wall per commit | p50 **9.7 s** |
| wall ÷ audio | p50 **0.79**, p90 **0.88**, max **2.82** |

It keeps up, with roughly 12% headroom at p90 and one chunk that fell behind. That is consistent
with the verdict already recorded against this rung — `KEPT_UP_WITHOUT_MARGIN`, cleared for
production by a dated owner ruling rather than by the measurement — and it is a longer run than
the 2026-09-17 row that verdict was written against. Nothing here changes the verdict; it
corroborates it.

## 5. Two device quirks seen while capturing, neither chased

- **Enabling wireless debugging stopped an in-flight transcription.** Reported by the owner and
  accepted; the same class as the rotation report of 2026-09-20, which stopped reproducing once
  the phone was on mains power — something outside the app revoking the capture. Recorded so a
  third instance is a pattern rather than a surprise.
- The rotation report itself remains unexplained and non-reproducing. The one durable finding
  from it stands: `MediaProjection.Callback.onStop` clears the token and tells the user nothing,
  so any revocation looks exactly like a silent stop. Still an open owner decision.

## Still open

- **AO10 and AO11(b)** — the two reads no log can make: whether the NPU tier's visible text is
  complete and free of `<|` markup, and the turn-by-turn two-voice comparison against 4.10.1.
- **`MAX_SPEAKERS`'s own KDoc still cites only the ruling**, not this session. Worth adding the
  citation in whatever build comes next; deliberately NOT edited here so the 105 artifact already
  built stays byte-identical to the source it was built from.
