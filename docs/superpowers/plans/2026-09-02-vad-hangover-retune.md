Everything is implemented and verified. Here is the plan.

---

# 4.4 — The VAD closes on faint pauses: `HANGOVER_MS` 500 → 350, paired

**Repo:** `C:/Users/bastr/OneDrive/Desktop/whisper Everywhere` · **Baseline:** `main` @ `1cb1c00` (159 suites / 1,891 tests / 0 failures) · **After:** 160 suites / **1,898 tests / 0 failures**

> **State of the tree.** The change described below is already applied in the working tree (uncommitted, 10 files) and has been run green, swept and mutation-tested exactly as §5 records. Read this document as the specification *and* as the review guide for that diff; every code block is what is on disk.

---

## 1. The decision

Six changes ship together. The first is what the owner asked for; the other five are what make it safe, and three of them exist **only** because adversarial review found the first one broken on its own. Nothing here is papered over — where a reviewer's finding changed the design, it is named in the heading.

| # | Change | File | Value |
|---|---|---|---|
| 1 | Hangover retune | `EndpointerTuning.kt` | `HANGOVER_MS` **500 → 350** |
| 2 | Acoustic floor, named and enforced | `EndpointerTuning.kt` + `EndpointerGridTest.kt` | `HANGOVER_MIN_MS = 300L` (new) |
| 3 | **The missing merge pass** (refuter 2) | `EndpointerTuning.kt`, `SileroEndpointer.kt` | `REOPEN_GAP_MS = HANGOVER_MS * 2` (= 700) + one new field |
| 4 | Turbo's own cadence floor | `CommitCadencePolicy.kt` | `MIN_COMMIT_INTERVAL_TURBO_MS = 3_200L` (new, **not** 2 800) |
| 5 | **`pro` leaves the FAST row** (refuter 1) | `CommitCadencePolicy.kt` | `"pro"` → `MIN_COMMIT_INTERVAL_MULTI_MS` (6 000) |
| 6 | **The wall cap keeps its evidence** (refuter 3) | `FloatingBubbleService.kt` | one added disjunct, no new field |

Plus the test-side instrument that makes the knob turnable forever: a new test-source object `EndpointerGrid` + `EndpointerGridTest`, and every hangover-dependent fixture in the suite re-derived from it.

### The mechanism, in one paragraph

`HANGOVER_MS` has **exactly one production read** — `SileroEndpointer.kt:600`, `if (nowMs - tempEndMs < EndpointerTuning.HANGOVER_MS) return false`. Every other mention in `app/src/main` is prose. Moving 500 → 350 changes one comparison and nothing structural. The dip's *k*-th frame has age `(k-1)*32 ms` (the pending end is stamped once, at the dip's first sub-`RELEASE` frame) and the guard is inclusive on the cutting side (native continues while `< min_silence_samples`, `whisper.cpp:5333`), so the cut moves from the dip's **17th frame (512 ms of trail)** to its **12th (352 ms)**. Minimum gate-open-to-cut goes 813 ms → **653 ms**.

### Why the other five are not optional

**`HANGOVER_MS` decides whether a boundary EXISTS; `CommitCadencePolicy.minCommitIntervalMs` decides how often one may be PAID for.** At 500 the floor had never once fired — measured cuts arrive 5.4–9.6 s apart against a 1 200 ms floor. At 350 it starts binding, so it has to be right first, and it was not: `npu-turbo` rode `MIN_COMMIT_INTERVAL_FAST_MS = 1_200L` on *published* 8 Gen 3 figures, marked provisional, against a measured **F = 1.89 s**. A 1 200 ms floor admits 50 commits/min = **173 % duty** into `LocalWhisperEngine.kt:50`'s single-thread executor over an **unbounded queue with no shed rule**.

Three findings from adversarial review changed the design and are incorporated, not argued away:

- **Refuter 1 (serious, upheld).** The same defect exists on `pro`, and by 5×. `pro` is `ggml-small.en-q5_1` (190,098,681 bytes); `multi` is `ggml-small-q5_1` (190,085,487) — the same whisper-small encoder, 13 KB apart. The 0.77–1.0 s that bought `pro` the FAST row is the **GPU** figure; this repo's own measured cost for those weights **without** the GPU is multi's F = 2.3 s. `GpuPolicy.kt:140` allows only `Adreno (TM) (7\d\d|8\d\d|X\d)`, and the chooser is GPU-blind (`pro`'s eligibility is RAM-only), so most of the fleet runs `pro` on 4 CPU threads — including exactly the devices `NpuFleetCensus` declines. Fixed by moving `pro` onto multi's row. The cost is named: an Adreno-7xx/8xx user now merges endpoints they could have afforded. Splitting on the load-time GPU verdict is the follow-up; it is not done here because that verdict is computed at `TranscriptionEngine.kt:257` and stored nowhere this function can reach.
- **Refuter 2 (serious, upheld).** The 3.7 port took whisper.cpp's *cut* and left its *merge pass* behind. Upstream applies `min_speech` **after** gluing segments separated by less than `2 × min_silence_duration_ms` (`whisper.cpp:5607-5626`, then `:5628-5640`). Ours discards each short burst immediately, and `closeGate()` re-anchors `speechStartMs`, so on emphatic word-by-word delivery ("It. Is. Not. That. Simple.") every word is found and thrown away, and time-to-close goes **backwards** — from one commit at ~3.0 s to none before the wall cap. This is invisible at 500 (a gap must reach 512 ms before the machine even asks) and reachable at 350. Fixed by porting the merge pass: one field, one condition, the ratio taken from upstream rather than invented.
- **Refuter 3 (serious, upheld).** Past the hangover there are two more gates, and the design's risk register named only one. Inside the newly-cutting band, when the above-`ONSET` run is ≤ `MIN_SPEECH_MS`, the outcome is not a VAD cut — it is the discard, and the discard leaves `hasPendingSpeech()` pinned **false**. `FloatingBubbleService.kt` then reads `(false, false)` at every cap cut, takes `segmentCapPolicy.onSessionStart(now)`, and the wall cap **collapses from 15 s to the 4 s first-segment window for the rest of the session** — the owner's one must-keep behaviour inverted, at ~3.75× the encoder passes, on audio that decodes to nothing. Change 3 makes most such runs mergeable; change 6 closes the residue (bursts separated by gaps too long to merge) by supplying the evidence `pendingSpeech` can no longer carry.
- **Refuter 4 (not fatal, incorporated).** Three KDoc sentences in the converted fixtures went false in the same commit that added a guard advertising them as protected, and one fixture precondition was covered only by a numeric coincidence. All four are repaired in §4.

**Deliberately NOT in this change** (each with its trigger written down): the leading-edge pre-roll retain (§6, *Known residual*), the debug-only dip histogram (it is a Kotlin log line and R8 strips those from release builds, so it cannot serve the owner's own capture), a per-tier hangover, the clear-pause nested Schmitt timer (its `CLEAR_THRESHOLD = 0.20` has no native provenance and nothing in the capture can fit it), and an eco/base re-bench.

---

## 2. Root cause and measurement

### Why the gate holds today

Three regimes, and only the third is hangover-sensitive:

1. **Probe ≥ 0.50 (music/speech holding the gate).** Every frame zeroes `tempEndMs` at `SileroEndpointer.kt:559`. The dip clock never starts. `SegmentCapPolicy.MAX_SEGMENT_WALL_MS = 15_000L` is the only exit — **at any hangover value.**
2. **Probe parked in the dead band 0.35–0.49.** `SileroEndpointer.kt:593` returns having written no field at all. Same conclusion, same reason.
3. **Probe dips below 0.35.** The clock starts, and the cut fires at the first sub-`RELEASE` frame at least `HANGOVER_MS` after the stamp. This is the only place the retune acts.

The owner's protected behaviour lives in (1) and (2) and is *structurally* untouched. The exposure is a bounded 160 ms band in (3): gaps whose sub-`RELEASE` span lands in **[352, 511] ms** flip from cap-held to VAD-cut — or, when the preceding run is under 300 ms, to the discard path that changes 3 and 6 now handle.

### The numbers (recomputed from `C:/Users/bastr/.androidbuild/capture-vad-headroom.txt`, not inherited)

Shipped release build, Galaxy Fold 6, npu-turbo, three runs, 57 segments:

| Quantity | Value |
|---|---|
| encode `graphExecute` | mean **1 778.9 ms**, sd **19.9 ms** (1.1 % — this is what *fixed* looks like), min 1 722.4, max **1 863.4** |
| decode | mean 279.3 ms over 23.30 tokens/segment ⇒ `44.4 ms + 10.08 ms/token` |
| pre-encode overhead (`pcmToMel` + quantise + handoff) | ~48 ms (minimum observed back-to-back interval) |
| per-segment token intercept | `tokens = 1.84 + 3.208·D` ⇒ 18.5 ms every commit pays regardless of length |
| **F (fixed per commit)** | **1.89 s** |
| in-session inter-commit gaps (n = 55) | min 1 938 ms, p25 3 735, **median 6 203**, p75 10 191, mean **7 277 ms** = **8.2 commits/min** |
| gaps in [14.9, 15.1] s | **6** — the wall cap firing to the millisecond |
| gaps already under 3 200 ms | **12** |
| duty cycle | 25 % / 28 % / 39 % — idle **61–75 %** |
| distinct language tokens | 5 across 57 segments (50259 ×45, 50260 ×6, 50275 ×3, 50264 ×2, 50262 ×1) |

**The central cost fact:** on npu/npu-turbo the encoder input is a fixed `[1,melBins,3000]` 30-second window (`whisper_jni.cpp:661-673`), so a 1-second utterance costs the same ~1.78 s as a 15-second one. An extra encoder pass is a **duty-cycle** cost, not a per-cut one. That reverses 3.7's written derivation for 500, which argued from a symmetric per-cut cost — so the constant's KDoc is rewritten, not just its number.

**Why the owner cares:** every commit is a fresh decode *and* a fresh per-utterance language detection (`NpuWhisperBackend.kt:613`, ~4.5 ms, bypassing `LanguagePin` because `detectsPerUtterance = true`). At 8.2 commits/min a mid-video language switch is caught in a measured mean 7.3 s — and never at all inside a 15 s wall stretch.

**Honest scope:** `LanguagePin.kt:41-46` latches the first detection for the whole session on every CPU tier. The flat 350 buys eco/base/pro/npu extra encoder passes and returns **nothing** on the owner's stated motivation. That is the named residual for the next round.

---

## 3. Production changes

### 3.1 `app/src/main/java/com/whispereverywhere/audio/EndpointerTuning.kt`

**(a) `HANGOVER_MS` — value and derivation together.** `EndpointerTuningTest` pins KDoc *sentences*, and the sentence it pinned argues for 500. The decision reversed, so value + sentence + pin move in one commit.

```kotlin
    /**
     * Trailing silence that ends an utterance. NOT the native 100 ms (`whisper.cpp:4456`), which is
     * a file-segmentation value with a 200 ms merge pass behind it (`whisper.cpp:5359`).
     *
     * RETUNED 500 —> 350 on a device measurement, and the derivation changed with the number.
     * 3.7 argued from a SYMMETRIC cost: cutting early bought one extra full encoder pass, so 500
     * bought margin cheaply. 57 shipped-build segments on the Fold6 falsified the premise on the
     * tier the app now ships: the npu/npu-turbo encoder input is a fixed `[1,melBins,3000]`
     * 30-second window (`whisper_jni.cpp:661-673`), so a pass costs the same ~1.78 s whether it
     * carries one second of speech or fifteen, and the pipeline measured IDLE 61-75% of the time
     * with a 15 s wall cap firing in every run because this hangover never elapsed. An extra
     * encoder pass is a DUTY-CYCLE cost, and duty cycle is
     * `CommitCadencePolicy.minCommitIntervalMs`'s job — not this constant's.
     *
     * What this constant still decides, on every tier, is the cost no governor can pay back:
     * inter-clause pauses run 200-500 ms, and a mid-clause boundary is one `no_context = true`
     * makes unrepairable. 350 clears word junctures and stop closures (50-200 ms) with margin and
     * intrudes only on the bottom of the inter-clause band; below ~200 ms it would reach inside
     * words. Also feeds the batch filter's `speech_pad_ms = 150`, which needs trailing audio to
     * expand into. At 350 a commit still carries 352 ms of it. Owner A/B range 350-800.
     */
    const val HANGOVER_MS = 350L
```

Two sentences survive verbatim and keep their existing pins (*"Also feeds the batch filter's `speech_pad_ms = 150`…"* and *"Owner A/B range 350-800."*).

**(b) `HANGOVER_MIN_MS` — the acoustic floor.** New. The suite already enforced the *machine* floor (`> MICRO_PAUSE_MS`) and the *batch-pad* floor (trail ≥ 150); neither is the phonetic one. A sweep to 250 was fully green before this existed.

```kotlin
    /**
     * The ACOUSTIC floor under [HANGOVER_MS] — the value below which the hangover stops ending
     * utterances and starts cutting inside them.
     *
     * The suite already enforces two other floors and NEITHER of them is this one: the machine
     * floor ([MICRO_PAUSE_MS] = 98, below which the cost governor's merge branch silently stops
     * offering the wall cap a cut point) and the batch-pad floor (a commit's trailing silence must
     * outlast `speech_pad_ms = 150`). Both are satisfied at 250 ms, and 250 ms reaches into
     * inter-word junctures in fast connected speech (100-200 ms) and stop closures (50-150 ms).
     * `no_context = true` (`whisper_jni.cpp:846`) plus `commit()`'s buffer reset makes a split
     * there unrepairable in both directions, so the failure is a permanently mangled word rather
     * than a latency regression.
     *
     * Enforced by `EndpointerGridTest.the_fixture_grid_is_valid_for_this_hangover` and nowhere
     * else. Without a NAMED floor an A/B downward fails as four unrelated-looking micro-pause
     * fixtures instead of with the reason attached.
     */
    const val HANGOVER_MIN_MS = 300L
```

**(c) `REOPEN_GAP_MS` — the merge pass.** New, and **derived** so an owner A/B carries its own merge window.

```kotlin
    /**
     * THE MERGE PASS, ported: how long a gate that has just DISCARDED a too-short burst still
     * remembers where that burst started, so speech resuming inside this window re-opens the same
     * utterance instead of starting a new one.
     *
     * The reference applies its minimum speech duration AFTER merging adjacent segments
     * (`whisper.cpp:5607-5626`, then `:5628-5640`) — it glues segments separated by less than
     * `2 x min_silence_duration_ms` and only then sweeps for short ones, so short adjacent bursts
     * become ONE qualifying segment and are never individually erased. The 3.7 streaming port took
     * the cut and left the merge behind. That was invisible while [HANGOVER_MS] was 500, because a
     * gap had to reach 512 ms before the machine ever ASKED; at 350 it asks about individual
     * words, and without this the machine finds five good boundaries in "It. Is. Not. That.
     * Simple." and throws all five away — [MIN_SPEECH_MS] discards each burst, `closeGate()`
     * re-anchors the clock, and the only exit left is the 15 s wall cap. Time-to-close goes
     * BACKWARDS, on exactly the fast speech the retune exists to serve.
     *
     * The ratio is the reference's, not an invention: `2 x` the silence that cuts (200 ms against
     * a native 100 ms `min_silence_duration_ms`). Derived rather than spelled, so an owner A/B of
     * the hangover carries its own merge window with it.
     */
    const val REOPEN_GAP_MS = HANGOVER_MS * 2
```

`MIN_SPEECH_MS` gains one paragraph: *"It is a floor on the MERGED utterance, not on each burst: see [REOPEN_GAP_MS]…"*.

> **Naming constraints, both live.** `EndpointerTuningTest:249` bans any `val` matching `COMMIT|INTERVAL|CADENCE`; the same test bans the quoted tier ids `"eco" "base" "pro" "extreme" "multi" "ultra"` anywhere in the file. `HANGOVER_MIN_MS` and `REOPEN_GAP_MS` clear both.

### 3.2 `app/src/main/java/com/whispereverywhere/audio/SileroEndpointer.kt`

**One new field** (the 14th — `SileroEndpointerConcurrencyTest`'s census is set-*equality*, so this is a deliberate enrolment, which is exactly what that census exists to force):

```kotlin
    /**
     * THE MERGE MEMORY: where the most recently DISCARDED burst started, or 0 when the last thing
     * that closed the gate was not a discard. Native's post-hoc merge pass
     * (`whisper.cpp:5607-5626`), which the 3.7 port left behind, expressed as one field on the
     * streaming path — see [EndpointerTuning.REOPEN_GAP_MS].
     *
     * NON-ZERO ONLY WHILE THE GATE IS SHUT, and that invariant is what keeps it one field instead
     * of a second state machine: the discard branch is the only writer, the onset branch consumes
     * it on the very next gate-open, and [clearForNextSegment] drops it with the rest of the
     * buffer's bookkeeping. It is never read while [speaking] is true.
     *
     * It carries no clock of its own. Staleness is bounded by [prevEndMs], which the micro-pause
     * promotion has ALREADY written on the discarding frame — the discard is reachable only past
     * `nowMs - tempEndMs >= HANGOVER_MS` and the promotion fires at `> MICRO_PAUSE_MS`, the same
     * argument the cost governor's branch below rests on. A second timestamp field would have been
     * a second thing to reset.
     */
    @Volatile private var reopenFromMs = 0L
```

**Onset branch** (`onProb`), the consumer:

```kotlin
            if (!speaking) {
                speaking = true
                // THE MERGE PASS (whisper.cpp:5607-5626, ported to the streaming path). If the
                // gate was last closed by a MIN_SPEECH_MS discard and speech has resumed inside
                // REOPEN_GAP_MS of that dip's start, this is the same utterance continuing, not a
                // new one: re-open from the discarded burst's start so the run is measured across
                // the gap, exactly as native measures a merged segment. Without it, word-by-word
                // delivery ("It. Is. Not. That. Simple.") discards every burst, re-anchors the
                // clock at each one and commits NOTHING until the wall cap.
                //
                // `prevEndMs` is the discarding dip's start and is the staleness bound; its
                // NO_CUT_POINT sentinel is 0, so an unset offer makes `nowMs - prevEndMs`
                // astronomically large under a wall clock and the re-open cannot fire on it.
                speechStartMs =
                    if (reopenFromMs != 0L &&
                        nowMs - prevEndMs < EndpointerTuning.REOPEN_GAP_MS
                    ) {
                        reopenFromMs
                    } else {
                        nowMs
                    }
                reopenFromMs = 0L
            }
```

**Discard branch**, the writer — `reopenFromMs = speechStartMs` must precede `closeGate()`, which zeroes it:

```kotlin
            reopenFromMs = speechStartMs
            closeGate()
            return false
```

**`clearForNextSegment()`** gains one line beside `pendingSpeech = false` (buffer knowledge dies here, **not** in `closeGate()`, which is the very path that sets it):

```kotlin
        reopenFromMs = 0L
```

**Three prose sites, no logic** (they named 500 and would have gone false without going red): `:564` *"a 500 ms hangover survive a talker…"* → *"the hangover survive…"*; `:581` *"the hangover's own 500 ms is silence"* → *"the hangover's own trailing window is silence"*; the merge branch's *"350-800 owner range"* argument now cites the `EndpointerGridTest` assertion that enforces `HANGOVER_MS > MICRO_PAUSE_MS` instead of leaving the range as unenforced prose.

### 3.3 `app/src/main/java/com/whispereverywhere/service/CommitCadencePolicy.kt`

**(a) The turbo row — 3 200, not the strict-formula 2 830.**

```kotlin
    /**
     * npu-turbo, and turbo alone: derived from the MEASURED F = 1.89 s at the same 0.70 duty
     * ceiling every other row uses, then set at the WORST sustained F rather than the median one.
     *
     * The arithmetic, in the object KDoc's own units. 57 shipped-build segments on the Fold6
     * (`capture-vad-headroom.txt`, three runs): encoder 1 778.9 ms with a standard deviation of
     * 19.9 ms — a 1.1% spread, which is what FIXED looks like — plus a decode of
     * 44 ms + 10.08 ms/token, plus ~48 ms of pcmToMel/quantise/handoff ahead of the graph, plus a
     * per-SEGMENT token intercept of 1.84 tokens (regressing tokens against the inter-commit
     * interval gives `tokens = 1.84 + 3.208*D`; that intercept is 18.5 ms of decode every commit
     * pays whatever it carries). So F = 1.89 s, and the only length-varying term is per TOKEN —
     * and tokens are conserved when the same speech is cut into more pieces (3.2 tokens per second
     * of audio, so m*S = 60 * 3.2 * 0.01008 = 1.9 s/min).
     *
     * `1.89*N + 1.9 <= 0.70*60` gives `N <= 21.2` commits/min, i.e. a mean interval of 2.83 s.
     * THIS ROW IS 3 200, NOT 2 830, and the difference is the whole point. At the strict answer a
     * saturated session sits at 70% duty with ZERO margin against the ceiling it was derived
     * from — on ONE device, in ONE thermal state, with every encode logging `vote: OK sustained`.
     * At 3 200 the saturated duty is 18.75 * 1.89 + 1.9 = 37.3 s/min = 62%, which tolerates F
     * rising 13% to 2.14 s before the ceiling is touched: past the capture's own worst observed
     * encode (1 863 ms) plus a decode tail, and enough to absorb a concurrent batch job holding
     * `NativeComputeGate`'s fair lock. The app has no thermal guard anywhere — this margin IS the
     * thermal policy. The cost is ~2.6 commits/min of owner benefit.
     *
     * IF F EVER DOES BREACH, THIS FLOOR IS THE NUMBER TO RAISE, not the hangover: the hangover
     * decides whether a boundary exists at a place a listener would agree with, and no duty
     * problem is solved by putting boundaries in worse places.
     *
     * THIS IS THE TRIGGER THE FAST ROW NAMED, FIRED. [...] The measurement came in at
     * 2.06 s/segment — above the whole published range — so turbo gets its own constant.
     *
     * It had never fired: at `EndpointerTuning.HANGOVER_MS = 500` the endpointer produced cuts
     * 5.4-9.6 s apart and a 1 200 ms floor is unreachable from there. The 4.4 hangover retune is
     * exactly the change that makes it bind, which is why the two land together [...] Ship one
     * without the other and a 1 200 ms floor admits 50 commits/min against a 1.89 s fixed cost
     * — 173% duty, into a local executor queue that is unbounded and never sheds.
     */
    const val MIN_COMMIT_INTERVAL_TURBO_MS = 3_200L
```

**(b) The tier map** — `pro` moves, turbo splits off, and the FAST row is **not** widened:

```kotlin
            "eco", "base", "npu" -> MIN_COMMIT_INTERVAL_FAST_MS
            "npu-turbo" -> MIN_COMMIT_INTERVAL_TURBO_MS
            // `pro` LEFT the FAST row in 4.4, on evidence this repo already contained. It is
            // ggml-small.en-q5_1 (190 098 681 bytes); `multi` is ggml-small-q5_1 (190 085 487) —
            // the same whisper-small encoder, 13 KB apart, differing only in the vocab head. The
            // 0.77-1.0 s that bought pro the FAST row is the GPU figure; the MEASURED cost of
            // those same weights WITHOUT the GPU is multi's F = 2.3 s, whose duty-safe floor is
            // this 6 s. And the GPU is not most devices': `GpuPolicy.ALLOWED_RENDERERS` is
            // `Adreno (TM) (7dd|8dd|Xd)`, so every Mali, PowerVR, Xclipse and pre-7xx Adreno runs
            // pro on `n_threads = 4` — and the CHOOSER cannot see any of that (pro's eligibility
            // is RAM-only), so pro is offered to, and installable on, the whole fleet, including
            // exactly the devices the NPU gate declines.
            //
            // At HANGOVER_MS = 500 this was inert: cuts arrived 6-11/min against pro-on-CPU's
            // 26/min service rate, so the floor was unreachable and its wrongness never surfaced.
            // The 4.4 retune is what makes it reachable, which is why it is corrected here rather
            // than left for the field. The cost is real and one-sided: an Adreno-7xx/8xx user
            // loses cadence they could have afforded. The refinement is to route on the GPU
            // verdict `GpuPolicy.decideUseGpuForLoad` already computes at load
            // (`TranscriptionEngine.kt:257`) — deliberately NOT done here, because that verdict is
            // not currently stored anywhere this function can reach [...]
            "pro", "multi" -> MIN_COMMIT_INTERVAL_MULTI_MS
```

**(c) Two KDoc records.** The object KDoc gains **the eligibility rule** the next author needs — *"a tier keeps a floor only while its full-segment F is MEASURED and `F/floor + m <= 0.70` at saturation"* — and names the two rows that did not meet it (`pro`, moved; eco/base, kept at 1 200 on a bench that predates the 768 → 512 `audio_ctx` move and therefore *overstates* today's cost, which is the safe direction for a floor but is not a measurement). `MIN_COMMIT_INTERVAL_CLOUD_MS`'s KDoc records that **the number did not change and its meaning did**: it now binds for the first time, and paces *billable* requests.

### 3.4 `app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt`

One added disjunct in the cap branch — **no new field, no new accessor**, reading a value the very next lines already read:

```kotlin
                if (capCutConsumesWindow(
                        hasPendingSpeech = endpointer.hasPendingSpeech() ||
                            endpointer.pendingCutPointMs() > Endpointer.NO_CUT_POINT ||
                            (endpointer as? SileroEndpointer)?.isProbeCutout() == true,
                        isCloudSession = cloudWrapper != null,
                    )
                ) {
```

`prevEndMs` is written at exactly one place, reachable only past `onProb`'s `if (!speaking) return false`, so a non-sentinel offer means *"the gate really opened and a dip really outlived `MICRO_PAUSE_MS` inside this window"*. It is cleared by every commit and every reset, so it cannot leak across windows. **The 3.5.0 LOCAL-silence parity guarantee is untouched:** a user who opens the session and says nothing never reaches that write, so the 4 s window still re-arms for their first real speech — the guarantee is scoped to *silence*, and silence still satisfies it. `capCutConsumesWindow` itself stays symmetric with its 4-row truth table unchanged, which is the reason the `isProbeCutout` OR already lives at the call site.

---

## 4. Test changes

### 4.1 The instrument: `app/src/test/java/com/whispereverywhere/audio/EndpointerGrid.kt` (new, test source set only)

Production reads `HANGOVER_MS` once and knows nothing about frame **counts**; counts are a property of the 32 ms pump the fixtures drive. The object also cannot live in `EndpointerTuning`, whose own test bans `COMMIT|INTERVAL|CADENCE` names.

| Derived value | Formula | @350 | @500 | @800 |
|---|---|---|---|---|
| `HANGOVER_FRAMES` | `ceil(H/32) + 1` | **12** | 17 | 26 |
| `HOLD_FRAMES` | `HANGOVER_FRAMES - 1` | **11** | 16 | 25 |
| `HOLD_TRAIL_MS` | `(HOLD_FRAMES-1)*32` | **320** | 480 | 768 |
| `HANGOVER_TRAIL_MS` | `HOLD_FRAMES*32` | **352** | 512 | 800 |
| `MICRO_PAUSE_FRAMES` | `floor(98/32) + 2` | **5** | 5 | 5 |
| `DEAD_BAND_FRAMES` | `(HOLD_FRAMES-1)/2` | **5** | 7 | 12 |
| `SPEECH_FRAMES_OVER_MIN` | `floor(MIN_SPEECH_MS/32) + 2` | **11** | 11 | 11 |
| `FIXTURE_INTERVAL_MS` | `(SPEECH+HANGOVER_FRAMES)*32` | **736** | 896 | 1184 |
| `BELOW_LARGE_MS` / `ABOVE_LARGE_MS` | fixed brackets around 8 000 | 6 976 / 8 128 | — | — |

`SPEECH_FRAMES_OVER_MIN` is `+2` and not `+1` deliberately: `+1` is the shortest run that clears `MIN_SPEECH_MS` strictly (10 frames = 320 ms), and a fixture standing on that boundary co-owns a boundary that already has its own test. It is derived rather than left at `11` so a `MIN_SPEECH_MS` A/B cannot re-open the identical fifteen-fixture problem on the other constant.

**THE DISCIPLINE: exactly one absolute pin in the whole suite** — `EndpointerTuningTest.the_shipped_tuning_table_is_pinned_verbatim`'s `assertEquals(350L, …)`. Everything else derives.

### 4.2 `EndpointerGridTest.kt` (new suite, 2 tests)

- `the_grid_matches_the_machine` — drives a real endpointer and cross-checks the grid's integer arithmetic against the state machine's own `<`. Two independent derivations agreeing is the property; either alone is a restatement.
- `the_fixture_grid_is_valid_for_this_hangover` — **the vacuity guard.** Each clause names the fixtures it protects in its own failure message: `H > MICRO_PAUSE_MS`; `HANGOVER_TRAIL_MS >= 150`; `MICRO_PAUSE_FRAMES <= HOLD_FRAMES`; `DEAD_BAND_FRAMES >= 1` on both sides; `PROBE_CUTOUT_FRAMES < H`; `FIXTURE_INTERVAL_MS < 1200 <= 2×`; `FIXTURE_INTERVAL_MS % 32 == 0`; `H % 32 != 0` (fails loudly on an on-grid value, naming the two KDocs that would otherwise go false without going red). **Five clauses added by this plan:**
  - `H >= HANGOVER_MIN_MS` — the acoustic floor.
  - `SESSION_ANCHORED_MS >= 1_200` — refuter 4's missing precondition for `reset_anchors_the_governor_on_the_last_frame_seen`, which was covered only because two unrelated derivations happen to land on the same integer boundary today.
  - `SPEECH_FRAMES_OVER_MIN * 32 > MIN_SPEECH_MS`.
  - `(ABOVE_LARGE_MS - BELOW_LARGE_MS)/32 >= HANGOVER_FRAMES + SPEECH_FRAMES_OVER_MIN` — makes `before_any_session_start…`'s `coerceAtLeast(0)` a decision rather than a silent degradation from EXACT to OVERSHOOT.
  - `(HANGOVER_FRAMES + 1) * 32 < REOPEN_GAP_MS` — the merge fixture's own precondition.

### 4.3 The fifteen that break at 350 — every one, named

The brief said fourteen. It is **fifteen**: the count was measured inside `SileroEndpointerTest` plus the tuning pin, and `CommitCadencePolicyTest` drives a real `SileroEndpointer` from another package.

**Eleven mechanical, in `SileroEndpointerTest` — all converted to the grid, no assertion weakened:**

| # | Test | What changed |
|---|---|---|
| 1 | `the_hangover_cuts_at_exactly_500ms_of_trailing_silence` | **renamed** `…_at_exactly_HANGOVER_MS_…`; `16/1` → `HOLD_FRAMES/1`; `BASE+640+512` → `BASE+640+HANGOVER_TRAIL_MS`; both messages interpolate |
| 2 | `dead_band_frames_do_not_stall_the_hangover_hard_timer` | `1 + DEAD_BAND_FRAMES + silence = HOLD_FRAMES`, so the fixture always keeps one of each; literal `10` dead-band frames would overshoot at 350 |
| 3 | `a_frame_back_above_ONSET_resets_the_hangover_clock` | restart captured from `pump.t` instead of spelled `BASE+992`; both runs `HOLD_FRAMES` |
| 4 | `no_verdict_frames_do_not_short_circuit_the_hangover` | `HOLD_FRAMES/1`; `BASE+1600+HANGOVER_TRAIL_MS` |
| 5 | `a_second_utterance_is_measured_from_its_OWN_start` | `HOLD_FRAMES` then 1; trailing run `maxOf(40, HANGOVER_FRAMES)` so it cannot pass by never asking |
| 6 | `a_commit_resets_the_probe_and_clears_the_accumulator` | `HOLD_FRAMES` parks the pump one frame short so the 452-byte partial can be planted |
| 7 | `pro_merges_an_utterance_that_endpoints_inside_1200ms` | `17`→`HANGOVER_FRAMES`, `11`→`SPEECH_FRAMES`; `BASE+2944` → `commit1 + 2*FIXTURE_INTERVAL_MS`; cut point captured from `pump.t` |
| 8 | `exactly_the_interval_commits_and_one_millisecond_more_merges` | `896L/897L` → `FIXTURE_INTERVAL_MS`/`+1`; `BASE+2048` → `BASE+640+HANGOVER_TRAIL_MS+iv` |
| 9 | `a_vad_cut_records_what_it_cut` | `trailMs = 512L` → `HANGOVER_TRAIL_MS` |
| 10 | `a_merged_endpoint_is_not_a_cut` | same |
| 11 | `the_cut_record_survives_reset_and_is_cleared_only_by_a_new_session` | same |

**Twelfth — the only structural one:** `the_frame_that_trips_the_latch_has_its_verdict_discarded`. Its KDoc promised a retune would break it **loudly**; it kept the promise. At 500 it exploited a coincidence — `PROBE_CUTOUT_FRAMES = 16` is exactly one less than the cut frame (17) — so the frame that trips the latch is the frame that would cut. At 350 the cut is frame 12 and the coincidence dissolves.

*Restaged off the pump, so the collision is **built** rather than found and the two constants are permanently decoupled:*

```kotlin
    private fun latchBoundary(slowProbe: Boolean): LatchRun {
        val clock = FakeClock()
        val probe = FakeProbe()
        probe.clock = clock
        val ep = SileroEndpointer(probe = probe, nanoClock = clock)
        val speech = EndpointerTuning.MIN_SPEECH_MS + 100
        probe.next = 0.9f
        assertFalse(ep.onFrame(ByteArray(B), 0, BASE))
        assertFalse(ep.onFrame(ByteArray(B), 0, BASE + speech))
        assertTrue("$speech ms of speech is comfortably committable", ep.hasPendingSpeech())
        probe.next = 0.1f
        assertFalse(ep.onFrame(ByteArray(B), 0, BASE + speech))   // pending end == BASE + speech
        if (slowProbe) probe.costUs = EndpointerTuning.PROBE_BUDGET_US + 1
        repeat(EndpointerTuning.PROBE_CUTOUT_FRAMES - 1) { i ->
            assertFalse(
                "one millisecond of trail cannot reach any hangover in the owner range",
                ep.onFrame(ByteArray(B), 0, BASE + speech + 1 + i),
            )
        }
        assertFalse("one short of the run is not the latch", ep.isProbeCutout())
        val fired = ep.onFrame(ByteArray(B), 0, BASE + speech + EndpointerTuning.HANGOVER_MS)
        return LatchRun(ep, fired)
    }
```

The dip's first frame is always **fast** (it stamps the pending end; charging it would put the run one frame early). The next `PROBE_CUTOUT_FRAMES - 1` frames sit **one millisecond apart**, where no hangover in the owner range can reach them. The boundary frame lands at exactly `HANGOVER_MS` past the pending end. **The property pinned is identical** — *the frame that trips the latch has its verdict discarded, cut and all* (`SileroEndpointer.kt:283-286`, `if (probeCutout) return false` between `timedProbe` and `onProb`) — and it is now **non-vacuous**, because the same script runs twice:

```kotlin
        val fast = latchBoundary(slowProbe = false)
        assertTrue("the twin: with a healthy probe this very frame is exactly HANGOVER_MS of " +
            "trailing silence and it CUTS — without this half, the assertion below would be " +
            "satisfied by a frame that was never going to commit", fast.fired)
        assertFalse("a healthy probe never latches", fast.ep.isProbeCutout())
        val slow = latchBoundary(slowProbe = true)
        assertFalse("the same frame, the same audio, one PROBE_CUTOUT_FRAMES-th consecutive " +
            "overrun: the latch discards the verdict it just paid for", slow.fired)
        assertTrue(slow.ep.isProbeCutout())
        assertTrue("onProb never ran on that frame, so nothing cleared the buffer either",
            slow.ep.hasPendingSpeech())
```

Without the twin, `assertFalse` is satisfied by *"no cut was due anyway"* — precisely how a future retune could hollow it out in silence.

**Correcting the brief on the other two probe-cutout tests.** `a_latched_cutout_stops_probing_for_the_rest_of_the_session` and `the_latch_survives_reset_and_is_re_armed_only_by_a_new_session` do **not** depend on `HANGOVER_MS` at all: both feed `0.1f` with the gate never opened, so every frame takes `onProb`'s `if (!speaking) return false` and the hangover branch is unreachable. **UNCHANGED**, verified green at 350 before anything was touched.

**Thirteenth — `EndpointerTuningTest`:** `assertEquals(500L, …)` → `350L`, and the pinned cost-asymmetry sentence is replaced by **three** new pins (the retune's premise, the object that now owns the cost, the cost this constant still owns). The test's own failure message demands exactly this: *"or — if the DECISION changed — change the value, the sentence and this pin together."*

**Fourteenth — `SileroEndpointerConcurrencyTest`:** `cutOn 17` → `HANGOVER_FRAMES`, `trailMs 512L` → `HANGOVER_TRAIL_MS`, search bound `maxOf(40, HANGOVER_FRAMES + 1)` so a machine that stopped cutting fails on the assertion rather than by running out of frames. Plus the census enrolment of `reopenFromMs` (14 names).

**Fifteenth — `CommitCadencePolicyTest.theEndpointersPreSessionFloorIsThisObjectsLargeInterval`:** it drives a real `SileroEndpointer` through `secondCutAttemptAfter` and asserted `CROSS_CHECK_BASE + 1_152L` twice. Now a derived `FIRST_COMMIT_MS = CROSS_CHECK_BASE + 640L + HANGOVER_TRAIL_MS`, `FIXTURE_FLOOR_MS = EndpointerGrid.FIXTURE_INTERVAL_MS`, and the two `run(SILENCE, 17)` calls become `HANGOVER_FRAMES`. The 32 ms grid guard still holds because `FIXTURE_INTERVAL_MS` is a multiple of the frame at every hangover.

### 4.4 Eight range-hardened fixtures (green at 350, converted anyway)

They would have re-opened all of this at the next A/B: `silence_below_RELEASE_does_not_clear_the_uncommitted_buffer` (its 10 literal frames start cutting at H ≤ 288 — the exact failure its own KDoc warns about), `a_commit_clears_the_micro_pause_memory`, `reset_clears_the_micro_pause_memory`, `a_discarded_short_burst_keeps_the_remembered_pause`, `the_sessions_first_endpoint_is_never_merged`, `multi_paces_three_utterances_into_one_commit`, `onSessionStart_re_arms_the_first_free_cut`, `reset_anchors_the_governor_on_the_last_frame_seen`, and `before_any_session_start_the_floor_is_the_conservative_8000` — the last **inverted**, so the 6 976 / 8 128 brackets are the fixed quantities and the silence padding is solved for.

### 4.5 Five new tests

| Test | File | Property |
|---|---|---|
| `a_discarded_burst_is_re_opened_when_speech_resumes_inside_the_merge_gap` | `SileroEndpointerTest` | Two bursts of `BURST_FRAMES` (9 = 288 ms, under `MIN_SPEECH_MS`) separated by `HANGOVER_FRAMES + 1` (13 = 416 ms). Without the merge: 0 commits, ever. With it: **1 commit** at `BASE + (2·9+13)·32 + HANGOVER_TRAIL_MS` |
| `a_discarded_burst_is_NOT_re_opened_after_a_gap_longer_than_the_merge_window` | `SileroEndpointerTest` | Resumption one frame past `REOPEN_GAP_MS` (22 frames = 704 ms) → each burst is discarded on its own terms. 0 commits. The window is bounded |
| `music_that_never_dips_below_RELEASE_leaves_the_wall_cap_as_the_only_exit` | `SileroEndpointerTest` | After speech: 500 frames (16 s, past the wall) at `0.42f`, then 500 at `0.9f`. `assertFalse` throughout **and** `pendingCutPointMs() == NO_CUT_POINT`. Pins the owner's non-negotiable at **any** hangover |
| `a_run_of_unmergeable_short_bursts_still_leaves_the_wall_cap_a_cut_point` | `SileroEndpointerTest` | 8 cycles of 9 speech frames + 22 silent. `hasPendingSpeech()` **false**, `pendingCutPointMs() > NO_CUT_POINT` **true** — the JVM half of the cap fix |
| `the_merge_window_is_twice_the_hangover_as_the_reference_merges` | `EndpointerTuningTest` | `REOPEN_GAP_MS == 2 * HANGOVER_MS`, pinned as the *identity*, never as `700L` |

Plus the call-site half of the cap fix: **`CapSeamPinTest.theCapBranchKeepsItsBookkeepingAndItsUnconditionalCommit`** grows one line in its exact-match needle.

### 4.6 Refuter 4's prose repairs (three sentences that went false without going red)

1. `the_hangover_fires_at_exactly_HANGOVER_MS` — *"[Pump] steps the elapsed silence 480 → 512 ms"* → *"steps the elapsed silence `[EndpointerGrid.HOLD_TRAIL_MS]` → `[EndpointerGrid.HANGOVER_TRAIL_MS]`"*. That sentence is this test's entire justification for being driven off the pump.
2. `exactly_the_interval_commits_and_one_millisecond_more_merges` — *"none of 300 / 500 / 98 is a multiple of the 32 ms frame"* and *"896 ms … commit 1 at BASE+1152, endpoint 2 at BASE+2048"* → derivation-by-name, plus a cross-reference to the grid guard that now asserts the off-gridness.
3. `reset_anchors_the_governor_on_the_last_frame_seen` — *"lands 896 ms after the cap cut but 1504 ms after the session opened"* → named quantities, plus the explicit note that the pro_merges clause does **not** imply this one (they reduce to the same integer boundary by coincidence).

And one in a file the retune had no other reason to open: `NativeVadSourceContractTest.kt:296`, *"HANGOVER_MS's 500 ms is chosen partly…"* → *"HANGOVER_MS is chosen partly…"*. It asserts the C++ `speech_pad_ms = 150`, so it would never have gone red — it would just have started lying.

---

## 5. Verification

### Commands (PowerShell; `java` is not on PATH; the build dir is relocated off OneDrive)

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'
Remove-Item -Recurse -Force 'C:\Users\bastr\.androidbuild\WhisperEverywhere\app\test-results\testDebugUnitTest' -ErrorAction SilentlyContinue
.\gradlew.bat :app:testDebugUnitTest --no-daemon
```

Count from the raw XML, never from the console summary:

```powershell
$d='C:\Users\bastr\.androidbuild\WhisperEverywhere\app\test-results\testDebugUnitTest'
$f=Get-ChildItem $d -Filter *.xml; $t=0;$fa=0;$e=0
foreach($x in $f){ $c=[xml][System.IO.File]::ReadAllText($x.FullName)
  $t+=[int]$c.testsuite.tests; $fa+=[int]$c.testsuite.failures; $e+=[int]$c.testsuite.errors }
"suites=$($f.Count) tests=$t failures=$fa errors=$e"
```

> **Purge the result dir before every run.** The task goes `UP-TO-DATE` on unchanged inputs and will leave you counting a stale directory — or none at all.
> **NEVER run `:app:installDebug` or `:app:connectedDebugAndroidTest`.** Both uninstall first and wipe the owner's 500+ MB of downloaded models. This has happened twice. Install with `adb.exe install -r <apk>`.

### Expected counts

| Point | suites | tests | failures |
|---|---|---|---|
| `main` @ `1cb1c00` (baseline) | 159 | 1 891 | 0 |
| after `EndpointerGrid` + `EndpointerGridTest` | 160 | 1 893 | 0 |
| **after this change, complete** | **160** | **1 898** | **0** |

`+1` suite (`EndpointerGridTest`), `+7` tests (2 grid + 4 endpointer + 1 tuning). `SileroEndpointerTest` runs **61**.

### Mutation battery — every row below was executed, not reasoned about

| # | Mutation | Killed by | Result |
|---|---|---|---|
| **M1** | delete `reopenFromMs = speechStartMs` from the discard branch | `SileroEndpointerTest.a_discarded_burst_is_re_opened_when_speech_resumes_inside_the_merge_gap` | 61 tests, **1 failed** — exactly the merge test |
| **M2** | delete the `pendingCutPointMs() > NO_CUT_POINT` disjunct in `FloatingBubbleService` | `CapSeamPinTest.theCapBranchKeepsItsBookkeepingAndItsUnconditionalCommit` | failed ✔ |
| **M3** | `MIN_COMMIT_INTERVAL_TURBO_MS` 3 200 → 2 800 | `CommitCadencePolicyTest`: `theShippedIntervalsAreTheMeasuredOnes`, `npuTurboHasItsOwnFloorBecauseTheDeviceMeasurementCameIn` (the **thermal-margin** assertion: 21 × 2 140 + 1 900 = 46 840 > 42 000), `everyCatalogTierIsNamedExplicitly` | failed ✔ |
| **M4** | `"pro"` back onto the FAST row | `proIsPacedAsTheSmallClassTierItIsWheneverTheGpuIsNotThere`, `everyCatalogTierIsNamedExplicitly` | failed ✔ (M2+M3+M4 together: 24 tests, 5 failed) |
| **M5** | dead-band guard `RELEASE_THRESHOLD` → `ONSET_THRESHOLD` (the dead band stops being inert) | **only** `music_that_never_dips_below_RELEASE_leaves_the_wall_cap_as_the_only_exit` | 61 tests, **1 failed**. Note what this proves: `dead_band_frames_do_not_stall_the_hangover_hard_timer` **cannot see** this mutation (the stamp is already set, so the cut lands identically). Nothing in 1,898 tests killed it before |

### The sweep — the deliverable's real claim

Turning the knob costs **two lines**: the constant, and its one pin in `EndpointerTuningTest`.

| `HANGOVER_MS` | Result |
|---|---|
| **250** | 1 898 tests, **1 failed** — `EndpointerGridTest.the_fixture_grid_is_valid_for_this_hangover`, naming the acoustic floor. *Before this plan added `HANGOVER_MIN_MS`, 250 was fully green.* |
| **350** (shipped) | **1 898 / 0** |
| **500** | **fully green**, two-line edit, no fixture change — the A/B is reversible in both directions |
| **800** | 1 898 tests, **1 failed** — the same grid guard, on-grid clause, naming both KDocs that would otherwise go false |

Nothing in CI holds the sweep; it is recorded here and in the commit message the way this codebase writes down every other measurement.

### Commit message

The commit body must record: the sweep and its verified values; that `LanguagePin.kt:41-46` latches the first detection per session on every CPU tier, so the flat 350 buys eco/base/pro/npu extra encoder passes and returns nothing on the owner's motivation; and that eco/base keep 1 200 ms on a bench predating the `audio_ctx` 768 → 512 move. Those are the named residuals, not unlogged consequences.

---

## 6. On-device acceptance (Galaxy Fold 6, npu-turbo, release build)

**What survives R8 and what does not.** `app/proguard-rules.pro:86` strips **all** `android.util.Log` calls from release — every Kotlin `WE-DIAG` line (`endpoint:`, `cap:`, `probe:`, `queue: depth=`) is **gone**. Native `__android_log_print` from `qnn_asr.cpp` survives: `encode:`, `detect:`, `decode:`. Acceptance is therefore built on those three lines plus what is on screen.

```powershell
C:\Users\bastr\AppData\Local\Android\Sdk\platform-tools\adb.exe logcat -c
C:\Users\bastr\AppData\Local\Android\Sdk\platform-tools\adb.exe logcat -s WE-DIAG > capture-vad-440.txt
```

Play a multi-language YouTube video with fast speech and faint pauses for 5–10 minutes, then a passage with a music bed. Compare against `capture-vad-headroom.txt`.

### SUCCESS — all five

1. **No more 15.0 s stretches during speech.** The baseline has **6** inter-`encode:` gaps in [14.9, 15.1] s. Expect **zero** during speech (music stretches may keep them — that is correct, see 4).
2. **Median gap falls from 6.2 s toward 3.2–4 s.** Baseline: min 1 938 / p25 3 735 / median 6 203 / p75 10 191 / mean 7 277 ms.
3. **No gap below ~3 200 ms.** That is the new floor doing its job. Baseline had **12** gaps under 3 200 ms; they will now merge.
4. **The music passage still shows long gaps.** Under a bed that never dips below the release threshold the wall cap is still the only exit — unchanged at any hangover, and now pinned by a test.
5. **`detect: language token` changes within ~3 s of a spoken language switch,** rather than a measured mean 7.3 s and never inside a wall stretch.

### The honest framing for the owner, before he tests

- This is a **rate improvement — 8.2 → up to 18.75 commits (and language detections) per minute — not "every pause now cuts."** The hardware forbids that: 1.87–1.89 s of fixed encoder cost per commit whatever the utterance length.
- **The governor is new visible behaviour.** It has never once fired on this tier, and 12 of 55 measured gaps are already under 3.2 s. The app will sometimes decline to cut at a pause he can plainly hear. That is the merge path working — audio kept, gate re-armed, boundary preserved — but unbriefed it reads as the same complaint he filed.
- Worst-case time-to-close under fast speech goes from 15 s to **~3.2 s**.

### ABORT signature

**`Transcribing… (3+ in queue)` on the in-flight strip, persisting and climbing and not coming back down** (`FloatingBubbleService.kt:287-291`). It is the **only** leading indicator: `LocalWhisperEngine.kt:50` is a single-thread executor over an unbounded `LinkedBlockingQueue` with **no shed rule and no back-pressure**, and the repo's written justification for that (*"the local engine drains faster than real time"*) is an RTF argument the fixed-cost NPU tier falsifies. Text lag ≈ depth × 2.1 s. Downstream, `awaitIdle` gives up at `FINALIZE_TIMEOUT_MS = 300_000L` and the tail is dropped.

Also abort on: `cap=4000ms` behaviour returning — i.e. commits every ~4 s through a music passage (that is the failure change 6 exists to prevent), or `encode:` times climbing past ~2.1 s sustained (thermal; the remedy is to raise `MIN_COMMIT_INTERVAL_TURBO_MS`, **not** to touch the hangover).

### The pre-ship acoustic gate

Run `VadProbeBenchTest`'s `longestGapMs` sweep on a fast-speech clip before promoting to production: **any `longestGapMs >= 350` is a mid-utterance cut at the shipped value.** It is the only instrument that quantifies the one cost no arithmetic here can touch — an unrepairable mid-clause split under `no_context = true`. Run it by the documented adb recipe and **never** via `:app:connectedDebugAndroidTest`.

### Known residual, with the fix already specified

Dips of 12–16 frames now cut, leaving the **next** segment only `dipSpan − 352` ms of lead-in: {32, 64, 96, 128, 160} ms, all at or below the batch filter's `speech_pad_ms = 150`. **Nothing is clipped** — `sendAudio` is unconditional and first, the commit runs to the cut instant, and the resumed word's attack is wholly inside the next segment — but the pre-roll is short, and on CPU tiers `whisper.cpp:5654-5657` clamps the back-pad silently. If the A/B shows word-initial errors, the fix is upstream's own split-the-difference padding (`whisper.cpp:5665-5670`): pass `retainMs = HANGOVER_TRAIL_MS / 2` (176 ms) at the VAD commit site, which leaves the committed segment 176 ms of trail and hands the next one ≥ 176 ms of lead-in — both above 150 at every hangover ≥ `HANGOVER_MIN_MS`. It is deferred because it changes every VAD commit on every tier and updates `CommitFunnelPinTest`'s needle, and this release is meant to be revertible in one line.

---

## 7. Rollback

**One line.** In `app/src/main/java/com/whispereverywhere/audio/EndpointerTuning.kt`:

```kotlin
    const val HANGOVER_MS = 500L
```

and the single matching pin in `EndpointerTuningTest.the_shipped_tuning_table_is_pinned_verbatim`:

```kotlin
        assertEquals(500L, EndpointerTuning.HANGOVER_MS)
```

**Verified:** the full suite is green at 500 with exactly that two-line edit — no fixture change, no test rewrite. `REOPEN_GAP_MS` follows automatically (it is `HANGOVER_MS * 2`), the merge pass becomes near-unreachable again (a gap must reach 512 ms before the machine asks), and the retune is undone.

The other five changes are **independently correct at any hangover** and should not be reverted with it:
- `MIN_COMMIT_INTERVAL_TURBO_MS = 3_200L` fixes a floor that was wrong on published figures before this release existed.
- `"pro"` on multi's row fixes a 5× error the repo's own measurement already condemned.
- The cap-window disjunct closes a hole that exists at 500 too (for troughs ≥ 512 ms after bursts ≤ 300 ms).
- The merge pass restores a reference behaviour the 3.7 port dropped.
- The grid and its guards are the reason the rollback is one line.

If the device session shows a **duty/queue** problem rather than a **boundary-quality** problem, do not roll back the hangover: raise `MIN_COMMIT_INTERVAL_TURBO_MS`. That is what the constant's KDoc says, and it is the difference between putting fewer boundaries in and putting boundaries in worse places.