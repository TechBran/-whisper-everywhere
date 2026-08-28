# 3.7.0 Workstream I — Owner on-device acceptance sheet

Owner-run. **NEVER `:app:installDebug` or `:app:connectedDebugAndroidTest`** — both uninstall
first and wipe the downloaded models. adb is not on PATH:
`$adb = "C:\Users\bastr\AppData\Local\Android\Sdk\platform-tools\adb.exe"`.

**What this sheet certifies.** It certifies the **branch** `feat/3.7-vad-endpointing` for **merge**.
It is not a Play-release gate: the branch's ship gate is the NPU track (owner ruling), so a fully
filled sheet says "3.7 is correct and measurably better on this device", not "submit it". Read every
target below as a merge criterion, and a missed target as a decision-gate finding rather than a
blocker.

**Builds.** BEFORE = the installed **versionCode 77** (3.6.0 with G4's floor 512 — the build the
2026-08-20 measurement session characterised: pro-GPU F 0.77-1.0 s, multi-CPU F 2.3 s).
AFTER = the 3.7.0 branch build, **versionCode 78**. Archive the before-build first so it stays
reinstallable:

    $adb = "C:\Users\bastr\AppData\Local\Android\Sdk\platform-tools\adb.exe"
    & $adb shell pm path com.whispereverywhere     # note the /data/app/.../base.apk path
    & $adb pull <that path> C:\Users\bastr\.androidbuild\WhisperEverywhere\whisper-3.6.0-BEFORE-370.apk

Run the whole BEFORE column on the installed 77 FIRST. Then build and install the after-build
(data-preserving — debug over debug updates in place):

    $env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon
    & $adb install -r C:\Users\bastr\.androidbuild\WhisperEverywhere\app\outputs\apk\debug\app-debug.apk

**Read this before anything else: versionCode 78 CLEARS every GPU canary latch.** `GpuPolicy` keys
its permanent verdicts on `BuildConfig.VERSION_CODE` — the preference keys are literally
`gpu_canary_v${vc}_$m` and `gpu_validated_v${vc}_$m` (`GpuPolicy.kt:43,51`), so moving 77 -> 78
**renames every key** and the old verdicts are not cleared so much as no longer addressed. The
2026-08-20 bench's `GPU-VERDICT: BAN reason=slower` for multi therefore does not carry into 78, and
GpuPolicy's own read falls to the `null -> "canary not yet run"` branch (`GpuPolicy.kt:209`) on the
first 3.7 load. *(Naming precision, since two tokens are easy to conflate: `GPU-VERDICT:` is the C8
**bench sheet's** token, recorded in `docs/superpowers/specs/2026-08-19-gpu-ab-bench.md:108`; the
**device** latch lines are `GpuPolicy: canary PASSED/FAILED for v<vc>/<model>` at
`GpuPolicy.kt:105,111`. Grep for whichever you actually mean.)*

Multi stays on CPU regardless: nothing re-runs the canary, because the preference that used to arm
it now reads false everywhere.

**RESPLICED by the H-section close-out.** This paragraph used to end *"Leave the toggle off for
this grid"* — an instruction about a control that **no longer exists.** Task H5 removed the
"Try GPU for multilingual (experimental)" Settings row, and the toggle **never shipped to users**
in the first place (owner confirmation 2026-08-27: Play carries 3.3.0; 3.4-3.6 were local
owner-pending merges). So there is no cohort carrying a stored `true`, the preference is
**vestigial and unread-false everywhere**, and the multilingual GPU path cannot arm itself. The
machinery — the key, the getter, `GpuPolicy`'s read and the canary gate — is deliberately
preserved inert for the NPU era, and is pinned in that state by `GpuExperimentRowRetiredTest`.
**No GPU instruction is needed for this grid** beyond the canary section's existing
ASSET-ABSENT / ASSET-PRESENT paths.

Hygiene, unchanged from the 3.6.0 sheet: `& $adb logcat -c` between sessions; no batch
file-transcription job running (the compute gate is shared and inflates transcribe-ms); cloud OFF
for the local rows (rescue segments stream into the same log unlabelled).

The 3.7 diagnostic family — one grep gets the whole story of a session:

    & $adb logcat -d -s WE-DIAG | findstr /R "endpoint: segment-timing: queue: perceived: probe:"

---

## Reference rows — the owner's OWN measurements, 2026-08-27

**Compare the certification run against these, not against a guess.** Every number below was
measured by the owner on the Fold6 against `preview.2` (b65f4b7, versionCode 77 build carrying the
full 3.7 diagnostic family), multi tier, CPU, across three sessions including a YouTube device-audio
capture, a dictation session and a real message. They are the branch's KNOWN-GOOD, and a
certification run that lands materially outside them is a finding.

| Quantity | Owner-measured, 2026-08-27 (multi, CPU) | Notes |
| --- | --- | --- |
| **F** — fixed per-commit transcribe cost | **2.5-3.6 s** (short segments: 2.4-3.1 s of audio costing 2.5-3.6 s to transcribe) | Consistent with August's 2.3 s, slightly warmer device |
| **`speechEndToVisible`** | **2.8-5.8 s**, typical **3.6-4.3 s** | **DOMINATED by transcribe (3-3.5 s), not by the governor** |
| **`trailMs`** on every `cut=vad` | **512-539 ms** (hangover + one frame) | Tightening 500 -> 300 saves ~200 ms/cut: ~5%, cheap but small |
| **`probe:` cost** | **p50 2.3-2.4 ms, p99 5.8-6.1 ms, overruns 0.2-0.3% of frames** | Well inside the 8 ms budget; the 16-consecutive-overrun latch is unreachable on this hardware |

**The governor verdict that came out of that session, and it constrains Check 2's reading.** With
measured F, the 6 s multi floor is **at** the derived duty ceiling (F = 3.0 s implies a ~5.9 s
minimum interval), so **do not lower it on CPU**. The perceived speedup the owner felt is the VAD
cutting at real utterance ends — not headroom in the governor. The NPU projection in the owner's own
numbers (small-NPU F = 0.27 s -> perceived ~1-1.5 s, and near-utterance cadence unlocked) is what a
retune actually depends on; it is the NPU track, not a dial turn on this branch.

**Two consequences for the targets in Check 2, stated here so the owner is not sent chasing a
number their own device has already contradicted:**

- The plan's **pro** acceptance target of **~1.3-1.8 s, constant** is an *unmeasured* projection
  (hangover 500 ms + ~0.8-1.3 s inference at the 2026-08-20 GPU F). Pro has never been measured on a
  3.7 build. Treat it as the hypothesis Check 2 tests.
- The plan's **multi** acceptance target of **~2.8 s at the paced boundary** was derived from
  F = 2.3 s. The owner's measured F of 2.5-3.6 s puts the honest expectation at **3.6-4.3 s
  typical**, and Check 2's multi row is written against that. Multi landing at 3.6-4.3 s with a
  **tight spread** is a PASS; the property under test is the variance, not the mean.
- `p=0.00` on firing frames is by design, and VAD-MISS caps during continuous YouTube speech are
  correct behaviour (there are no pauses to cut at). Neither is a defect.

---

## How to read the 3.7 diagnostic family

**Pasted from Task F9's report, item (b) — the join rules, verbatim:**

> `perceived: seq=<n> speechEndToVisible=<n>ms`, under `WE-DIAG`, one line **per segment**. Join it
> to `endpoint:` and `segment-timing:` on `seq=`. **`queue:` carries no seq at all** and is read by
> ADJACENCY — it is emitted immediately after `endpoint:` on the commit side and immediately after
> `perceived:` on the resolve side. (Review **I3**: this block originally told you to join `queue:`
> on `seq=`, and that join cannot work.)
>
> **One caveat on the `segment-timing:` join.** `endpoint:` and `perceived:` both carry the
> service's seq; `segment-timing:` carries the ENGINE's, which is the same number on local and on
> direct cloud but **not** on a fallback-rescued segment — the nested local engine numbers from 0
> and will collide with real session seqs. The sheet's existing hygiene note (cloud OFF for the
> local rows) is what keeps this out of the way; do not join across a fallback session. (Review
> **m2**.)
>
> **Units are not uniform across this family, deliberately** (review **m4**): `speechEndToVisible=`
> and `p50=` carry the unit as a SUFFIX (`ms`, `µs`), `speechMs=` / `trailMs=` carry it in the field
> NAME, and `audio=` / `transcribe=` carry none at all. Every one of them is a documented grep, so
> none can be normalised. A parser must special-case per field.
>
> **The headline is the VARIANCE, not the average.** Pre-3.7 the number is uniformly distributed
> over 0–15 s depending on where in the cap window the user stopped talking; under 3.7 it should be
> a constant. Owner acceptance numbers: **pro ~1.3–1.8 s, constant**; **multi ~2.8 s at the paced
> boundary**. A grid that averages these numbers destroys the property being claimed — read p50 AND
> p95, per tier, and read the spread.
>
> **Absences are meaningful and are not gaps.** A line is emitted only when (i) the cut was
> `cut=vad` — cap/stop/switch cuts have no speech-end instant and deliberately report nothing;
> (ii) the segment's release actually carried non-blank text; and (iii) **the resolution was not an
> overtaking one.** On cloud and fallback tiers, resolutions arrive out of order (`Semaphore(3)`),
> and a segment the orderer is still holding resolves with a blank release: its own line is emitted
> later, under the seq of whichever resolution finally drains it. So a silent segment shows
> `endpoint:` and `queue:` with **no** `perceived:`, by design; and an out-of-order cloud pair
> produces ONE line covering the drained run rather than two. A session whose `perceived:` lines are
> missing while `endpoint: … cut=cap` lines are present is a VAD-MISS session, and the cap line's
> own suffix says so.
>
> **One known attribution limit, on cloud/fallback only.** When a SILENT head drains a later
> segment's text, the line is emitted under the HEAD's seq and times the head's speech-end against
> the tail's render — an over-report, never a lost line. Cross-check against `endpoint: seq=`'s
> `trailMs` if a row looks implausibly large. Local sessions cannot produce this (`maxInFlight = 1`,
> a provable in-order pass-through), so **Check 2's grid is unaffected.**

*(The multi figure quoted inside that block is the plan's derivation from F = 2.3 s. The reference
rows above supersede it with the owner's measured 3.6-4.3 s; the block is pasted whole because its
join rules and absence rules are the load-bearing part.)*

**The stamp's own boundaries, so this sheet does not over-read it (F9 item (c)).** The commit stamp
is the capture frame's clock; the visible stamp is Main at `deliverReleasedText`'s return — the
hand-off to the preview sink. **The TextView write itself follows one Main dispatch and one frame
later**, so the metric EXCLUDES that hop (single-digit to ~20 ms, biased low). It INCLUDES engine
latency, queueing and the Main hop to the resolution coroutine, and excludes injection, clipboard
and the accessibility round trip.

**Segments still in flight when you tap stop DO produce lines** — the reset is at session START
precisely so their stamps survive, and the FINALIZING drain runs the same `onSegmentResolved`
coroutine. **Those are the p95 rows and they must not be discarded.** Only a segment that never
resolves at all is never timed.

**Diag-reading note, changed in Workstream G (verdict m5).** For a *resolution*, `queue: depth=` now
**precedes** that seq's `perceived:` line, where it followed it before. No extraction breaks — the
filter is a prefix match and the joins are seq-keyed — but `queue: depth=` is the one WE-DIAG line
carrying **no seq**, so it is attributable only by adjacency, and its neighbour changed. A hand-read
log will look different from a 3.6.0 one even where nothing is wrong.

---

## PREREQUISITE for Check 1 — the owner's clip

> **Check 1 cannot start until this is done, and it is the owner's to do.** The bench needs a
> recording only the owner has, and the repo must never contain it.
>
> 1. **Record ~8 s of natural speech** in the acoustically hard condition the whole robustness
>    argument rests on — the "zero dips below -22 dB" noisy room, soft talker.
> 2. **Export it as 16 kHz mono PCM16 WAV.** This is the format the test *validates*, not one it
>    resamples: `CanaryAudio.formatIsValid` **FAILS** (it does not skip) on a 44.1 kHz or stereo
>    export, with "re-export it at 16 kHz mono before re-running". A wrong-format clip would still
>    decode to samples and still produce plausible probabilities that would then be recorded as the
>    verdict — which is exactly why it is rejected rather than accepted.
> 3. **Push it** with the exact command from the test's own SKIP message:
>
>        & $adb push <your-clip.wav> /sdcard/Android/data/com.whispereverywhere/files/owner-vad-clip.wav
>
> 4. **NEVER commit it.** It is personal audio and this repo has a public remote. `owner-vad-clip*`
>    appears nowhere in the tree today and must stay that way. It is a *different* clip from the
>    bundled `canary_digits.wav` — substituting the canary would answer a different question with
>    plausible numbers.
>
> Until the clip is on the device the run reports **skipped**, which is the correct reading of "not
> yet run" and **never a red bench**. A skip is not a pass, and Check 1 stays `PENDING`.

## Check 1 — Frame-by-frame Silero probs over the owner's clip (the ESTIMATE-to-MEASURED pass)

RESULT: PENDING

This is the one check that converts a premise rather than measuring a latency. Two things ride on
it: that Silero is categorically more noise-robust than an RMS gate (the vendored tree contains
zero robustness evidence — one README sentence), and that the soft talker in a noisy room is
fixed. Use the owner's own 8 s "zero dips below -22 dB" clip, 16 kHz mono PCM16.

**Decide the verdict rule before the run, not after** (the G4 sheet's discipline): Check 1 passes on
`crosstab` — does `ampSilentSileroSpeech` show real cuts the RMS gate could not make? — and on
`cost` (`overBudget` against the 8000 µs budget). The four `threshold=` rows *inform* the
ONSET/RELEASE tuning; they do not pass or fail the check.

The model-preserving run recipe (Task S2 §8, verified against the G4 recipe and the H2 sheet's
`$adb` form). **`adb install -r` reinstalls without touching app data, so the downloaded models
survive; `:app:installDebug` and `:app:connectedDebugAndroidTest` both uninstall first and wipe the
models — neither is ever run, by anyone, on this branch** (the prohibition at the top of this sheet,
restated where the temptation actually is).

    $adb = "C:\Users\bastr\AppData\Local\Android\Sdk\platform-tools\adb.exe"

    # 1. Build both APKs (no install task, no connected task)
    $env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon

    # 2. Reinstall in place — -r preserves app data and therefore the models
    & $adb install -r C:\Users\bastr\.androidbuild\WhisperEverywhere\app\outputs\apk\debug\app-debug.apk
    & $adb install -r C:\Users\bastr\.androidbuild\WhisperEverywhere\app\outputs\apk\androidTest\debug\app-debug-androidTest.apk

    # 3. Push the clip — 16 kHz mono PCM16, any other format is REJECTED not resampled
    & $adb push <your-clip.wav> /sdcard/Android/data/com.whispereverywhere/files/owner-vad-clip.wav

    # 4. Clear logcat, then run this ONE class
    & $adb logcat -c
    & $adb shell am instrument -w -e class com.whispereverywhere.whisper.VadProbeBenchTest com.whispereverywhere.test/androidx.test.runner.AndroidJUnitRunner

    # 5. Harvest
    & $adb logcat -d -s WE-BENCH | findstr "BENCH vadprobe"

> **Do NOT dictate, run a batch job, or leave the bubble recording while the bench runs**: the
> Silero probe context is process-global and the instrument shares the app process, so a live
> session's `vadProbeReset()` silently poisons the recorded probs. The corruption is silent — the
> bench still prints plausible probabilities, and those get recorded as the noise-robustness verdict
> the whole workstream rests on. (Review S2 finding M-1.)

Record the four summary lines verbatim (the ~250 `frame=` lines go in the paste block below them):

- [ ] `BENCH vadprobe summary` — `noVerdict` MUST be 0. Nonzero means frames reached the probe at
      a size other than 1024 bytes, which cannot happen from this bench and would mean the frame
      contract itself is wrong — a Workstream A defect, not a clip problem.
- [ ] `BENCH vadprobe crosstab` — **the settlement.** `ampSilentSileroSpeech` is the count of
      frames the shipped amplitude gate calls silence (<= 250 RMS) while Silero calls speech
      (>= 0.50). A materially nonzero count on a clip that never dips below -22 dB is the
      soft-talker fix, MEASURED. `ampDeadBandPct` is the fraction of the clip sitting in the
      251-499 band that can open a segment but never close one — the mechanism behind "it just
      waits for the wall cap". `ampVoiceSileroSilent` should be near zero; a large value is a
      finding worth reporting, not a pass.
- [ ] `BENCH vadprobe threshold=` (four lines) — `speechPct` at 0.50 should track how much of the
      clip is actually speech. **`longestGapMs` is the hangover check:** any value >= a
      `HANGOVER_MS` candidate (350 / 500 / 800) means that threshold+hangover pair would have cut
      INSIDE the utterance, and `no_context = true` makes such a cut unrepairable. If 0.50 shows a
      long gap that 0.35 does not, that is the spec's documented "widen RELEASE to 0.30" signal.
- [ ] `BENCH vadprobe cost` — `overBudget` MUST be 0 against the 8000 µs budget. `p50us`/`p99us`
      are the first measured probe cost **from a bench** in this repo (the estimate was 200-1500 µs;
      the owner's 2026-08-27 *production* numbers were p50 2.3-2.4 ms / p99 5.8-6.1 ms, so a bench
      p50 materially above those on an idle device would itself be the surprise).

Expect one `frame=` line per 32 ms of clip (~250 for an 8 s clip), then exactly one each of
`summary`, `crosstab` and `cost`, and exactly four `threshold=` rows (0.50 / 0.40 / 0.35 / 0.30):

    BENCH vadprobe frame=0 ms=0 rms=118 p=0.0121 us=743
    ...
    BENCH vadprobe summary frames=250 noVerdict=0 durationMs=8000 pMin=... p50=... p95=... pMax=...
    BENCH vadprobe crosstab ampSilentSileroSpeech=... ampVoiceSileroSilent=... ampDeadBand=... ampDeadBandPct=...
    BENCH vadprobe threshold=0.50 framesAbove=... speechPct=... longestGapMs=...
    BENCH vadprobe threshold=0.40 ...
    BENCH vadprobe threshold=0.35 ...
    BENCH vadprobe threshold=0.30 ...
    BENCH vadprobe cost frames=250 p50us=... p99us=... maxus=... overBudget=... budgetUs=8000

If the clip is unavailable the run SKIPS rather than fails (`assumeTrue`) — a skip is not a pass.
`am instrument` reporting the test as skipped means step 3 was not done; it is **not** a red bench.

    (paste the BENCH vadprobe lines here)

## Check 2 — Per-tier perceived latency: `speechEndToVisible` p50/p95

RESULT: PENDING

The headline. **Variance is the story, not the mean:** on 77 the later-segment wait is uniformly
distributed over 0-15 s depending on where in the cap window you stopped talking; on 78 it should
be near-constant. A tight p95 is the result even if p50 barely moves.

Grid: 2 tiers (pro, multi) x local mode x BEFORE/AFTER. Per cell, dictate **6-8 short sentences
with real pauses between them** (this is the opposite of the 3.6.0 sheet's continuous-speech
protocol — that one exercised the wall cap; this one exercises the endpointer). Then:

    $v = @(& $adb logcat -d -s WE-DIAG | Select-String 'speechEndToVisible=(\d+)ms' | ForEach-Object { [int]$_.Matches[0].Groups[1].Value } | Sort-Object)
    "n=$($v.Count) p50=$($v[[int][math]::Floor(0.50*($v.Count-1))]) p95=$($v[[int][math]::Floor(0.95*($v.Count-1))]) max=$($v[-1])"

- [ ] **pro, AFTER:** target p50 ~1.3-1.8 s and a p95 close to it (hangover 500 ms + ~0.8-1.3 s
      inference at the measured GPU F). **This target has never been measured on a 3.7 build** — it
      is the hypothesis, not a known-good. A p95 far above p50 means cuts are still landing on the
      cap, not the endpointer — cross-check `endpoint: cut=` below.
- [ ] **multi, AFTER:** the owner's measured expectation is **3.6-4.3 s typical (2.8-5.8 s range)**
      at the paced boundary, and **no 15 s outliers at all**. The pacing floor is
      `CommitCadencePolicy.MIN_COMMIT_INTERVAL_MULTI_MS = 6_000L` (`CommitCadencePolicy.kt:30`).
      **A tight spread at 3.6-4.3 s is a PASS.** The number is dominated by transcribe (3-3.5 s),
      not by the governor: with measured F = 2.5-3.6 s the 6 s floor is already AT the 0.70 duty
      ceiling (`F*N + m*S <= 0.70*60 s`; F = 3.0 s implies ~5.9 s), so **do not lower it on CPU**
      and do not read the residual latency as a governor fault.
- [ ] **BEFORE column, both tiers:** record the real numbers. The release notes' "text lands
      sooner, and at a steady pace" survives only if this column backs it (Task S4 contingency).
- [ ] **`endpoint:` cut mix.** `& $adb logcat -d -s WE-DIAG | findstr "endpoint:"` — on 78 the
      overwhelming majority must read `cut=vad`. **`cut=cap` is now a VAD-FAILURE SIGNATURE, not
      the normal path** — a handful is fine (humming, continuous media); a majority means the
      endpointer is not firing and the session degraded to 3.6.0 behaviour. Report the mix. (The
      four reason values are `vad|cap|stop|switch`, `EndpointDiag.kt:31-40`.)
- [ ] **`trailMs=` on the `cut=vad` lines.** The owner's reference is **512-539 ms** on every cut
      (hangover + one frame). A materially different value means the tuning changed under you.
- [ ] **`ctxFrames=` on `segment-timing:`.** At utterance cadence most commits should show a small
      encoder context, not the floor on every call. This is the cost driver that decides whether
      multi's pacing can ever be relaxed.

## Check 3 — Stop on an idle queue is near-instant

RESULT: PENDING

Under 3.7 the last utterance was already cut at its own endpoint, so the stop flush hands whisper
**pure trailing silence**: `we_vad_filter` finds no speech and the native side returns BEFORE
`whisper_full`. Cost should be tens of ms, against ~1.3 s (base) to ~4.5 s (multi) on 77.

- [ ] Local session, **stop during a real pause** (not mid-word), queue visibly idle: the
      `finalize-timing` total is ~tens of ms and `queue: depth=0` immediately before the stop.
- [ ] Local session, **stop mid-utterance**: the tail is real audio and costs one inference. This
      is expected and is NOT a regression — it is the same work 77 did, just less of it.
- [ ] **Under load, `finalize-timing: local-drain` is the honest number now.** On 77 it was
      near-zero on the batch happy path; at 6x the commit rate a real backlog can exist at stop,
      and the drain reserve is a BOUND, not a cost. A nonzero local-drain next to a nonzero
      `queue: depth=` before the stop is correct behaviour, not a timeout.

## Check 4 — Cloud batch: request count is bounded, and `cap=4000ms` is still absent

RESULT: PENDING

Every cloud-batch commit is one HTTP POST. `CommitCadencePolicy.MIN_COMMIT_INTERVAL_CLOUD_MS =
3_000L` (`CommitCadencePolicy.kt:49`) is a **flat floor for every tier** on cloud batch and bounds
it at <= 20 requests/minute of session; `Semaphore(3)` in flight sheds at 24.

- [ ] One ~2-minute cloud-batch session of normal paced speech. Count the commits:
      `& $adb logcat -d -s WE-DIAG | findstr "endpoint:" | measure` — the count divided by session
      minutes must be **<= 20/min**. Report the actual rate.
- [ ] **`cap=4000ms` must NEVER appear in a cloud session** (3.6.0 A2 regression signature, still
      valid). Run one cloud-batch session with a deliberately SILENT opening stretch (~20 s) and
      confirm the first `wall-clock cap -> commit` line reads `(cap=15000ms)`:
      `& $adb logcat -d -s WE-DIAG | findstr "wall-clock cap"`.
- [ ] **Cloud LIVE is untouched.** One cloud-live session: the server cuts turns, the endpointer
      never runs, and `endpoint:` lines must be ABSENT for the whole session.
- [ ] **The cloud `local-drain` is the evidence for the flat-3000 trade.** `CommitCadencePolicy`
      paces cloud batch at the cloud floor rather than the local tier's, on the argument that the
      failure path is bounded by the drain reserve and not by this interval. A multi-tier cloud
      session's `finalize-timing: local-drain` is named in the source as the reading that would
      reopen that decision (`CommitCadencePolicy.kt:66-67`) — record it here even when it is fine.

## Check 5 — Threshold / hangover A/B

RESULT: PENDING

The vendored Silero reflect-pads instead of using `n_context`, so published thresholds do not
transfer verbatim — budget for tuning. Check 1's `longestGapMs` table does the offline half over
the owner's clip; this is the on-device half, and it is only needed if Check 1 or Check 2 shows a
problem.

- [ ] **Only if mid-clause splits are audible in the transcripts:** widen `RELEASE_THRESHOLD`
      0.35 -> 0.30 in the tuning object, rebuild, re-run Check 2's pro cell, compare p50/p95.
- [ ] **Only if the wait feels long on pro:** try `HANGOVER_MS` 500 -> 350; re-run Check 2 AND
      re-read the transcripts. The trade is lopsided on purpose — 350 saves ~150 ms on a ~1.3 s
      inference and risks an unrepairable mid-clause boundary. (On multi the same change is worth
      ~200 ms against a 3.6-4.3 s number: ~5%, cheap but small, and it buys nothing structural.)
- [ ] Record every value tried and its p50/p95. A changed default lands as its own commit with
      this sheet's numbers quoted in the message.

## Check 6 — Probe overruns on the Fold6

RESULT: PENDING

- [ ] `& $adb logcat -d -s WE-DIAG | findstr "probe:"` after each session in Check 2. The owner's
      2026-08-27 reference is **overruns 0.2-0.3% of frames** — not zero, and well inside budget.
      The ring gives >= 128 ms of slack (>= 4 frames) against an expected 0.2-1.5 ms probe, so a
      count materially above that reference means something else is blocking the capture thread.
      The 16-consecutive-overrun latch was unreachable on this hardware.
- [ ] Compare `p50=`/`p99=` here against Check 1's `BENCH vadprobe cost` numbers, and both against
      the owner's production reference (**p50 2.3-2.4 ms, p99 5.8-6.1 ms**). A large gap between
      the bench (idle device) and production (mic open, inference running) is the real contention
      signal and is what would justify promoting the probe to its own thread — a measured decision,
      explicitly not a default.
- [ ] **The latched cutout must not have fired.** If a session silently reverted to amplitude
      behaviour, `endpoint: cut=` goes all-`cap` and the cutout logs once. Report it if seen.

## Check 7 — Fallback is byte-identical to 3.6.0

RESULT: PENDING

- [ ] With the probe forced unavailable (see the fallback tier in Workstream D — model missing or
      init failure), a full local session must behave exactly as 77 did: `cap=4000ms` first cut,
      `cap=15000ms` later cuts, `endpoint: cut=cap`, transcripts delivered once at stop. This is
      the structural guarantee that every pathological case degrades to today's behaviour.
- [ ] **Expect the `VAD-MISS` suffix here and do NOT report it as a failure.** On a 3.7 build the
      cap line reads `wall-clock cap -> commit (cap=<n>ms) VAD-MISS: no endpoint in this window`
      (`EndpointDiag.kt:76`). **On the amplitude-fallback tier that suffix is EXPECTED** — the
      Silero probe is not live, so of course no endpoint fired. It is a failure signature only when
      the probe is supposed to be live.

## Silence semantics — LOCAL (expected, not bugs)

RESULT: PENDING

- [ ] Repeated `cap=4000ms` lines in a **silent local session** are CORRECT (the silence re-arm;
      empty commits are near-free). A user who pauses to think still gets the 4 s first cut on
      their first real speech.
- [ ] **On the silence re-arm the ` VAD-MISS: no endpoint in this window` suffix is likewise
      EXPECTED and is not a failure.** Same rule as Check 7: the suffix is a signature only when
      the Silero probe is supposed to be live and a real utterance went uncut.
- [ ] VAD-MISS caps during **continuous** speech (reading aloud, YouTube playback) are correct
      behaviour — there are no pauses to cut at. The owner observed exactly this on 2026-08-27.

## In-flight strip and bubble behaviour (Workstream G)

RESULT: PENDING

- [ ] **The cloud-BATCH session now shows the in-flight line — this is INTENDED, not a
      regression.** A `CLOUD_WITH_FALLBACK` session displays "Transcribing…" / "Transcribing…
      (N in queue)" between the endpoint cut and the provider's response, where 3.6.0 showed an
      empty strip (`FloatingBubbleService.kt:286-287`). The backlog is real on that path,
      `commitSegment` is the one funnel for every engine, and the label names no provider and makes
      no speed claim by design. Confirm it appears and reads sensibly; do not file it as a bug.
- [ ] **No window jitter at utterance cadence.** Park the bubble at a screen edge and run a local
      session at utterance cadence. The claim is *one* reveal and *one* posted `reclampNow()` per
      **session** — so watch for window jitter every ~2.4 s and expect **none**.
- [ ] **The `INVISIBLE` strip between utterances reads as part of the panel, not as a gap.** It
      occupies one blank line; the window estimate over-allows ~80 dp there, which is the safe
      direction.

## Regression spot-checks

- [ ] Dictation into WhatsApp delivers ONCE at stop (final-only commit intact); Transcriptions
      history records the session; the resize handle works; the how-to guide reads unchanged.
- [ ] **Tier retirement (Workstream H):** eco/base are gone from the chooser, an already-installed
      eco/base still resolves and transcribes, and nothing forces a re-download.
- [ ] **Migration card, the positive half (Workstream H):** an installed **extreme/ultra** user
      still sees the migration card and can still complete `SwapAndDelete`. The line above covers
      only the eco/base *negative* half; nothing on device confirms the card still renders for the
      cohort H2 narrowed it to.
- [ ] **GPU row retired (Workstream H):** Settings shows no "Try GPU for multilingual
      (experimental)" row anywhere. `GpuExperimentRowRetiredTest` pins this at the source level
      only — it proves the code is absent from `SettingsScreen.kt`, and cannot prove no toggle
      renders. This line is the substitute for the instrumented test the environment forbids.
- [ ] **Steer (Workstream H):** on an English device the English tier is the top card and carries
      "Best match for your language"; switch the system language to a non-English one and confirm
      the multilingual tier takes the top card and the badge — both cards remain tappable. The
      wiring pins prove the ordered list is *computed, passed and named*; only this confirms Compose
      lays the steered card out first. (The badge string is one constant,
      `ModelTierCopy.STEER_BADGE` at `ModelTierCopy.kt:70`, so the quoted text and the rendered text
      cannot drift apart silently.)
- [ ] Device-audio (`switchSource`) mid-session: swap mic <-> playback and confirm the next
      `endpoint:` line reads sensibly. Carrying LSTM state across an acoustic-source change is a
      correctness bug; this is the check for it.

## OPEN — awaiting an OWNER RULING (not a check; a decision)

**m4 — the deleted-file cohort asymmetry.** Open and unowned since Task H1, and routed here because
this sheet is where the owner will actually see it.

An eco/base user **who has lost their model file** is offered two different things depending on
which surface they reach first:

- `ensureSpeech()` resolves `prefs.selectedModelId` first and offers them a **60 MB restore** of the
  tier they already chose;
- the onboarding chooser renders only `pickable` tiers — eco/base are no longer pickable — and its
  Download button **overwrites `selectedModelId`**, so it offers them a **190 MB adoption** of a
  different tier.

Pre-existing in *shape* (extreme/ultra have had it since their own retirement), but H1 moved it from
a RAM-gated minority to **the entire former-default cohort**. It needs a deliberate ruling — restore
the cheap tier, force the adoption, or offer both explicitly — not just a row on a sheet.

    OWNER RULING: PENDING

## Certification (filled by the branch-certification task, not by the owner)

    suites=      tests=      failures=      errors=      skipped=
    assembleDebug:             PENDING
    assembleDebugAndroidTest:  PENDING
    untouchable contracts:     PENDING

## Report

Report every `RESULT:` line filled, both columns of Check 2's grid, and the Check 1 paste. **A
missed target is a decision-gate finding, not a silent pass** — specifically, if Check 2's AFTER
numbers do not beat the BEFORE column, Task S4's release-notes contingency fires BEFORE Play
submission. Compare every number against the reference rows at the top: a result that matches the
owner's 2026-08-27 measurements is a pass even where it misses a projection that was never measured.
