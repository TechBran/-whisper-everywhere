# Real-time chunks — review of the 350 ms retune, the accumulated floor, and the 25 s finalizer

Build under review: 4.3.1 / versionCode 83, local `main` = `9141ce3`, tree clean, carrying `51f4655`
(the retune). Four analysts, four refuters, one synthesis. Every `file:line` below was re-read at
`9141ce3` for this document. Every measurement below was recomputed from
`C:/Users/bastr/.androidbuild/capture-vad-headroom.txt` (4.3.0/82 on the Fold6, 57 segments):
encode n=57 mean **1,778.9 ms** sd 20.1 min 1,722.4 max 1,863.4; decode = **44.4 + 10.083 × tokens** ms;
tokens mean 23.3, max 196; detect tokens 50259 (en) ×45, 50260 (zh) ×6, 50275 (id) ×3, 50264 (ko) ×2,
50262 (es) ×1; 12 in-run inter-commit gaps under 3,200 ms; 6 gaps in [14,900, 15,100] ms; one
`terminated by the token budget` line (196 tokens, 2,019.4 ms, capture 16:06:35.718). Where the brief,
a memory note, an in-source comment, or an analyst disagreed with the code, the code won and §7 lists it.

---

## 1. VERDICT ON versionCode 83

**GO-WITH-CHANGES.** The AAB built from `9141ce3` goes to the internal track unchanged. The changes are
two edits to the acceptance sheet, and they must land **before §E is run**, because the sheet is the
promotion gate (`docs/superpowers/sdd/2026-09-02-431-guards-tts/acceptance.md:274-277`) and, as written,
it passes on the one behaviour that decides the owner's goal.

### Why the build itself is safe (each a code fact)

- **The state machine did not change.** `git show 51f4655 --numstat` gives `SileroEndpointer.kt` +6/−4,
  and every one of those ten lines is a `//` comment (the diff has zero non-comment `+`/`-` lines). The
  behavioural surface of the retune is: `HANGOVER_MS` 500 → 350 (`EndpointerTuning.kt:87`),
  `HANGOVER_MIN_MS = 300` (`:106`), the new turbo row `MIN_COMMIT_INTERVAL_TURBO_MS = 3_200L`
  (`CommitCadencePolicy.kt:104`, routed at `:176`) replacing the deleted
  `"eco", "base", "pro", "npu", "npu-turbo" -> MIN_COMMIT_INTERVAL_FAST_MS` row, `pro` moved to the 6,000 ms
  MULTI row (`:202`), and **one** added code line in the service
  (`FloatingBubbleService.kt:2070`, `endpointer.pendingCutPointMs() > Endpointer.NO_CUT_POINT ||`).
- **No audio is lost.** `engine.sendAudio(chunk)` is unconditional and first (`FloatingBubbleService.kt:1987`,
  pinned once-and-first by `CapSeamPinTest.kt:68-73`). A MIN_SPEECH discard and a governor merge both run
  `closeGate()` only (`SileroEndpointer.kt:592-594`, `:614-616`; `closeGate` writes three gate fields,
  `:633-637`) and keep `pendingSpeech`/`prevEndMs`, which die only in `clearForNextSegment` (`:654-665`).
  Uncommitted audio is bounded by the 15 s cap (`SegmentCapPolicy.kt:57`) and the engine's 30 s forced
  commit (`LocalWhisperEngine.kt:78`, `:216-221`).
- **Duty is bounded.** VAD commits are ≥ 3,200 ms apart by `SileroEndpointer.kt:596`; a cap cut re-anchors
  the governor through `reset()` (`:365-366`). Modelled duty is 35-60 % across the owner's target grid
  (§3.1) against the 0.70 ceiling every cadence row is derived from (`CommitCadencePolicy.kt:11`,
  `:26-28`) and the 62 % saturated figure the turbo row was set at (`:79`).
- **The music must-keep survives for a structural reason.** A frame at p ≥ 0.50 zeroes `tempEndMs`
  (`:540`); a dead-band frame writes no field at all (`:555`); neither can reach the hangover test at `:579`
  at any hangover value. Pinned by `SileroEndpointerTest.kt:601`.
- **The wall-cap evidence fix is correct and complete.** `prevEndMs` has one non-sentinel writer (`:577`)
  reachable only past `if (!speaking) return false` (`:559`); it is cleared at `:662` from every commit
  (`:651`), reset (`:367`) and session start (`:423`); both service reads (`:2069-2070`, `:2090`) precede
  `endpointer.reset()` at `:2110`. Pinned verbatim by `CapSeamPinTest.kt:91-101` and by
  `SileroEndpointerTest.kt:639`. Every inter-burst gap length lands on a covered disjunct (§6, R8 for the
  one bounded cost).

### The changes that must land first (sheet only — zero code, no rebuild)

**A. Add row E7 to §E of `acceptance.md` (after `:247`).** Proposed text:

> E7. **The language goal, on the phone.** Language = **Auto** (a picked language never runs detection:
> `NpuWhisperBackend.kt:613`). Dictate four short sentences alternating English and Spanish, ~2 s each,
> natural pauses. EXPECTED on turbo: the window updates every ~5 s, each update carrying TWO sentences,
> one per language; one sentence of each pair is decoded under the other's language token (garbled or
> drifted). That is the cost governor (3,200 ms) merging every second endpoint — NOT the VAD missing:
> the VAD found the boundary and the floor declined to pay for it. Only sentences of ~3 s or more arrive
> one per chunk. RECORD: sentences per update ______ ; what the second-language half looks like ______ ;
> is this acceptable? ______ `[ ] RULED`

Cost if the row turns out unnecessary: two minutes of the device session. Cost if it is omitted: E1's
criterion "cuts land on sentence ends AND zero VAD-MISS" (`:203-208`) is **satisfied by a merged pair**
(the pair's boundary is a sentence end and no 15 s stretch passes), the gate at `:274-277` passes, and the
regression against 4.3.0 in the ≥ 544 ms pause band (§3.2) is promoted to production unseen and found as
a complaint.

**B. Rewrite the rollback line at `:285-286`.** It names `HANGOVER_MS` only ("one line; nothing else in
the retune is coupled to it"). A 4.3.0-equivalent rollback is **two** lines: `EndpointerTuning.kt:87`
350 → 500 **and** `CommitCadencePolicy.kt:176` `"npu-turbo"` back to `MIN_COMMIT_INTERVAL_FAST_MS`. (The
`pro` move at `:202` is inert on turbo.) Cost if omitted and E1 fails: a hangover-only revert produces a
500 / 3,200 build that is identical to 4.3.1 in the ≥ 544 ms band (pairs) and worse than it in the
[~384, ~544) band (no VAD cut fires; 15 s chunks) — not a return to 4.3.0 at all.

**C. Two device-run preconditions, written into the §E preamble.** (i) Language must be Auto for E1/E7:
`"auto"` → `null` (`PreferencesManager.kt:178-181`) is the only value on which `nativeDetectLanguage`
runs (`NpuWhisperBackend.kt:613`). (ii) The track build strips every Kotlin `WE-DIAG` line (sheet `:38`;
plan `2026-09-02-vad-hangover-retune.md:529`), but the native 4.3.1 `decode:` line carries
`nsp= lp= ent= rung= steps=` (`qnn_asr.cpp:3388-3393`) — so run E4 on a **genuinely percussive** source
(drums, clicks, applause — not a pad) and keep the `decode:` lines. That capture is the baseline the
banked floor will be judged against (§2) and it costs nothing extra now.

### Not changes for 83 (recorded so they are not re-derived)

- Lowering the turbo floor to buy per-sentence chunks at 2 s sentences: 2.05 s / T of duty = 73 % at
  T = 2.8 s, 82 % at 2.5 s, 89 % at 2.3 s — all past the 0.70 rule, with "no thermal guard anywhere"
  (`CommitCadencePolicy.kt:82`) and an unbounded single-thread queue (`LocalWhisperEngine.kt:50`; plan
  `:552-556`). The strict-formula 2,830 helps only T ∈ [2,830, 3,200).
- The accumulated-speech floor (§2).
- The onboarding copy (§3.7): a standing owner ruling carried verbatim (`OnboardingLogic.kt:42-48`,
  pinned `OnboardingLogicTest.kt:107-115`); two strings plus one test once the owner re-rules.
- Adding the language-token band to the NPU suppress list (§6, R10) — a decode change; needs its own
  device pass.
- Two stale comments (`FloatingBubbleService.kt:3105`; `SegmentOrderer.kt:12-14`) — harmless.

---

### 1.1 Owner ruling, 2026-09-03 — the turbo floor is 2,000, not 3,200

The owner read §1 and §3 and ruled for per-sentence chunks over the duty margin, on this reasoning:
the 25 s finalizer's language boundaries come from the fast chunks' per-chunk labels, and a bilingual
pair carries ONE label — the boundary is destroyed before any finalizer can use it, and recovering it
inside a window would cost one fixed encode per probe. Monolingual fast chunks are therefore a
precondition of the language-boundary design, not a nicety. At 2,000 the saturated duty is 98 % at
the measured F (110 % at the throttled 2,140), which this review's §1 refused under the 0.70 rule;
the ruling accepts it with the arithmetic recorded in `CommitCadencePolicy.MIN_COMMIT_INTERVAL_TURBO_MS`'s
KDoc, the strip's "(3+ in queue)" label as the only field guard, and a queue-depth BACKPRESSURE
governor (this floor while depth <= 1, ~3,200-3,900 once it reaches 2) as the next task — a
precondition for the finalizer's extra ~11 %. E7 was rewritten as a PASS/FAIL row on the ruled value;
the proportionate rollback is 2,000 -> 3,200 (pairs), not the hangover. Everything else in §1 stands.

## 2. THE FLOOR

**Ruling: ship 350 alone in versionCode 83. Do not build the accumulated-speech floor before the upload.
Build it after the owner rules R4 with the phone in hand, with the semantics below.**

### Why not now

1. **R4 is an open owner ruling, by the code's own words.** `EndpointerTuning.kt:138-140`: "needs its own
   decision about whether two 288 ms drum hits should merge when two 288 ms words should"; plan
   `:627-629` says the same; sheet row E5 (`acceptance.md:234-241`) exists to obtain that ruling.
2. **The bank inverts promotion-gate row E4 by construction.** E4 (`:224-233`, gate row at `:277`,
   "the one behaviour a hangover change can silently invert" `:279-280`) passes only if a percussive bed
   rides the 15 s cap. On the E4 fixture (16 × 9-frame hits over 13-frame gaps; `BURST_FRAMES = 9` at
   `SileroEndpointerTest.kt:539`, gap `HANGOVER_FRAMES + 1 = 13` at `:565`, `HANGOVER_FRAMES = 12` at
   `EndpointerGrid.kt:61-63`) the n-th hit closes at `704n − 64` ms and the bank stands at `288n`. At
   `MIN_SPEECH_MS = 300` on the 3,200 floor: n=2 → 1,344 ms (the session's first cut is free,
   `hasCommitted == false` at `:596`), n=7 → 4,864 (3,520 ≥ 3,200), n=12 → 8,384: **3 commits in 11.26 s**
   (4 at 250: 640 / 4,160 / 7,680 / 11,200). The shipped machine commits 0 (`:584` discards each 288 ms
   span). 3-in-11.3 s is exactly the rejected draft's number (`EndpointerTuning.kt:121-122`).
3. **The percussion cost is unmeasured and only conditionally bounded.** Steady state one commit per
   5 hits = 3,520 ms → 60,000 / 3,520 = 17.0 commits/min × 1.89 s = 32.2 s/min = **54 %** (today 4/min ×
   1.89 = 12.6 %), rising to the governor's 62 % if closes align with the 3,200 boundary. That bound holds
   only if the bed resolves at rung 0. The no-speech gate runs AFTER the decode (`NpuWhisperBackend.kt:653`
   after `decodeMs` at `:647`; text blanked at `:665`), and a rung that is low-confidence but not silent
   (`avgLogprob < -1.0 && noSpeechProb < 0.6`, `qnn_asr.cpp:3350-3352`, `:3356`) runs to `maxTokens`
   (`:3338`) and then advances to the next of six rungs (`:3185`, `NpuDecodePolicy.kt:169`): worst case
   1.78 + 6 × (0.044 + 10.083 × 0.196 = 2.02) = **13.9 s per commit** at a 3.5 s cadence into a queue with no
   shed rule. The capture's one 196-token line took 2,019.4 ms — the model's 2,020 ms to within a
   millisecond. A confident bed (lp ≥ −1.0) passes the gate and its text is typed. Which row real music
   lands in is untested (guards spec `2026-09-02-431-decode-guards-bubble-tts-design.md:153-156` names only
   "dead-time segments show nsp>0.6").
4. **The cost of waiting is latency only.** Word-by-word audio is never lost; it lands at the 15 s cap.
   That is shipped 3.7 behaviour (`EndpointerTuning.kt:134-137`), pinned as a known limit at
   `SileroEndpointerTest.kt:561`.
5. **Nothing in the field can see the bank misbehave.** Release strips every Kotlin `WE-DIAG` line
   (plan `:529`): no `endpoint:`, no `queue: depth=`; Play vitals and the three native lines are the
   whole instrument.

Cost if this ruling is wrong (bank later wanted): the owner sees E5 — "It. Is. Not. That. Simple." typed
~15 s late — and a 4.3.2 carries the bank after the ruling. Cost if the opposite ruling is wrong (bank in
83, promoted): device-audio sessions over drums commit at 17/min at 54-62 % NPU duty with no thermal
guard, possibly typing hallucinated bed text, on a production build whose only instrument is Play vitals,
with R2 ("background music rides the wall cap", `EndpointerTuning.kt:123-125`) inverted. Asymmetric.

### The semantics that survived review

| Item | Ruling | Why |
|---|---|---|
| What banks | `bankedSpeechMs += FRAME_MS` (32, `EndpointerTuning.kt:38`) **in the ONSET branch only** (`SileroEndpointer.kt:539-547`). Dead-band frames (`:555`) and silence (`:559+`) never touch it; `NO_VERDICT` returns at `:534`. | bank = 32 × N(p ≥ 0.50) since the last clear, so silence can never satisfy the floor — the merge pass's flaw (`EndpointerTuning.kt:116-120`, two frames 416 ms apart committing 64 ms of speech) is impossible by construction: two frames bank 64 < 300 → discard. Dead-band banking would let a bed that never closes the gate pre-pay the floor for the first drum break. |
| Weight by p | No. | Silero's p is a gate probability the Schmitt trigger already collapsed to three classes; a weighted floor cannot be named in frames. |
| Threshold | **`MIN_SPEECH_MS` stays 300.** | "Client stricter than native (250)" is a CPU-tier fact: `we_vad_filter` has exactly one caller, `whisper_jni.cpp:815`, inside the CPU `transcribeRaw`; the NPU ignores `useVad` by design (`NpuWhisperBackend.kt:470-473`). On turbo `MIN_SPEECH_MS` is the only speech-duration floor in the system. On the 32 ms grid 300 → 10 onset frames, 250 → 8; neither value is reachable, so `<` vs `<=` is invisible in the machine (write `<` as the brief proposes and say why the shipped `<=` at `:584` no longer matters). 250 flips `SileroEndpointerTest.kt:515-525` (256 ≥ 250 → free commit), lowers the isolated-click threshold from 10 clicks to 8, and edits the pins `EndpointerTuningTest.kt:29` and `:187-192`. (It does **not** flip `:701-716` — the 8-frame burst merges under the 8,000 pre-session floor at `:200`; the floor analyst had that wrong.) |
| Discard site | `:584` becomes `if (bankedSpeechMs < MIN_SPEECH_MS) { closeGate(); return false }`. `closeGate()` (`:633-637`) never touches the bank. | Item 2 of the brief, corrected for the grid. |
| What latches `pendingSpeech` | `:545` becomes `if (bankedSpeechMs >= MIN_SPEECH_MS) pendingSpeech = true`, evaluated **after** the increment on the same frame. | Latches on the **10th** onset frame (320 ms), one frame earlier than today's 11th (today's span test reads `(n−1) × 32`). Pin the increment-then-latch order explicitly; the brief leaves it open and the two orders give different tests. |
| What clears it | **One line in `clearForNextSegment()` (`:654-665`)**, beside `pendingSpeech = false` (`:656`) and the `prevEndMs` sentinel (`:662`) — reached from `commitAt` (`:651`), `reset()` (`:367`), `onSessionStart` (`:423`). | A clear in `closeGate()` kills the feature silently and the suite stays green (`:561` asserts today's behaviour). A `commitAt`-only clear leaves a full bank after every wall-cap `reset()` (`FloatingBubbleService.kt:2110`) so the next window's first click commits; `reset_clears_pending_speech` (`:387-401`) catches that one: 11 frames → reset → 1 frame → 384 ≥ 300 → red. |
| The merge branch | Keeps the bank (as `:596-616` keeps `pendingSpeech`, `:598-600`, and `prevEndMs`). | After any merge the floor is already met, so the next hangover close of **any** burst — one frame — commits as soon as the interval elapses. That closes the untested merge → short burst → discard → merge path in which the cap was the only exit. It also means the `endpoint:` line's `speechMs` (from `EndpointCut`, `:689`, printed at `EndpointDiag.kt:52-54`) can read 32 for a buffer holding seconds of speech — item 6 (`bankedMs=`) is required for that line to be honest. |
| Per-burst minimum | Not in the first cut. **Record this:** a 3-frame (96 ms) per-burst minimum separates single-frame clicks from words (ten 32 ms clicks 448 ms apart bank 320 and commit at 4,416 ms today-under-the-bank; no word is one frame) — it does **not** separate a 9-frame drum hit from a 9-frame word. The click case is fixable; the hit case is R4 and no duration rule resolves it. | Build the plain bank with the click cost pinned (test #4); add B only if the owner's E4b/E5 ruling says click beds matter. |
| Threading | `@Volatile`; enrolled in both censuses (`SileroEndpointerTest.kt:1795`; the set-equality of 13 names at `SileroEndpointerConcurrencyTest.kt:58-68`). | A Main-thread `reset()` racing the capture thread's `+= 32` loses the **whole** pre-reset bank, not one frame as for `fill` (`:51-53`; `fill` is zeroed every frame at `:279`). Only `switchSource` is a live race (`reset()` at `FloatingBubbleService.kt:2235` precedes `audioRecorder.stop()` at `:2238`); `stopRecording` stops at `:3019`/`:3021` before `reset()` at `:3068` and `onSessionStart` clears again. Cost: one early `cut=vad`, still paced by the governor (`:365-366`). The KDoc must say this in these words, not borrow `fill`'s sentence. |

### Tests that must exist (frames at 32 ms; S = 9 frames at 0.9, G = 13 at 0.1, D = 12 at 0.1, C = one frame at 0.9)

1. `two_frames_416ms_apart_bank_64ms_and_are_discarded` — `C G C G`: `commits == 0`, `hasPendingSpeech() == false`. The merge-pass killer as a permanent pin.
2. `word_by_word_bursts_bank_the_floor_and_commit_at_the_second_close` (replaces `:561`) — `5 × (S G)`, no session start: `commits == 1` at BASE + 1,344; pending true after.
   2b. `…_and_the_governor_paces_the_rest` — `onSessionStart(BASE, 3200)`; `8 × (S G)`: commits at BASE + 1,344 and BASE + 4,864.
3. `a_percussive_bed_is_bounded_by_the_governor_not_by_the_floor` — the replacement `:558-559` mandates: `onSessionStart(BASE, 3200)`; `16 × (S G)`: `commits == 3` at BASE + {1,344, 4,864, 8,384}; without session start `commits == 2`. Its KDoc must cite the owner's R2 re-ruling by date — it cannot be written honestly before that ruling exists.
4. `ten_isolated_clicks_bank_the_floor` (or `…_do_not_bank` under a per-burst minimum) — `10 × (C G)`: `commits == 1` at BASE + 4,416; nine clicks → 0.
5. `a_reset_clears_the_bank` — `S G` (bank 288), `reset()`, `C D`: `commits == 0`, pending false.
6. `a_merge_keeps_the_bank_so_the_next_close_commits` — `onSessionStart(BASE, 3200)`; 20 speech + D (commit 1); 11 speech + D (merge); silence past 3,200; `C D`: `commits == 2` on the click's close.
7. `the_latch_counts_frames_not_span` (replaces `:316`) — direct `onFrame` at BASE, BASE+1, … BASE+9: pending true after the 10th frame though the span is 9 ms.
8. `a_discard_does_not_clear_the_bank` — `S G` then `C`: pending false after the discard, true after the click.
9. `EndpointerGrid.BANK_FRAMES_TO_FLOOR = ceil(MIN_SPEECH_MS / FRAME_MS)` and a grid clause `BURST_FRAMES < BANK_FRAMES_TO_FLOOR`, so no fixture hardcodes 10.

Restaged (go red or lose their premise): `:292` (10 frames → false must become 9 → false, 10 → true);
`:316` (delete; #7); `:336` (**restaging it IS the onset-only ruling**: after 640 ms of dead band the latch
is still false; the 10th onset frame latches); `:561` (red by design; #2/#2b/#3); `:639` (its second
9-frame burst now commits — restage onto `9 × (C G)`: `commits == 0`, pending false, cut point offered);
`:663` (delete with a KDoc note — the `<=`/`>=` asymmetry it pins no longer exists); `:701` (stays green at
300; rename to "a commit clears the bank"); both censuses; `EndpointerTuningTest.kt:361` keeps its three
sentences — amend `EndpointerTuning.kt:134-140` around them; rewrite the `MIN_SPEECH_MS` KDoc
`:148-150` ("a floor on EACH RUN") and `hasPendingSpeech`'s `:292-294`. Keep unchanged: `:601` (the
dead-band half of E4), `:515`, `:883`, the governor family, `:387` (it now guards the `reset()` clear),
`CapSeamPinTest`, `CapCutBookkeepingTest`, `CommitCadencePolicyTest:327`.

### Device-sheet rows to rewrite when the bank lands

- **E4 → E4a + E4b.** E4a (sustained / dead-band bed — a pad, a held chord, crowd): unchanged, rides the
  cap; no floor change touches it (`:601` proves it at any hangover). E4b (percussive bed): PASS = commits
  no faster than one per 3.2 s AND every `decode:` line during the bed shows `rung=0` with `nsp>0.6` or a
  short EOT AND no bed text in the window AND the strip never shows "(3+ in queue)". **R2 must be re-ruled
  by the owner in its own words**, because on percussion "music rides the wall cap" is no longer literally
  true.
- **E5 → PASS/FAIL.** Expected: the first pair of words within ~1.5 s (word 2 closes at 1,344 ms), the
  rest paced at 3.2 s. It stops being "cannot fail".
- **E6 → new precondition.** E5's words now commit, so E6 must start from a sub-floor burst (a 3-4-frame
  cough) and confirm later segments still run to the 15 s cap. Its JVM twin (`:639`) is the restaged
  `9 × (C G)` fixture. The property itself is untouched: the bank only makes `hasPendingSpeech()` true more
  often and never writes `prevEndMs`, so disjunct 2 (`:2070`) is unaffected and the LOCAL-silence re-arm
  (`:2031-2032`, `:2077`) survives because silence cannot bank.
- **On 83, now:** E4 on a genuinely percussive source with the `decode:` lines kept, and E5's answer.
  Those are the whole input the R4 ruling needs.

---

## 3. WILL SMALL CHUNKS ACTUALLY SERVE MULTI-LANGUAGE

Short answer: on turbo, **the VAD does not decide when a chunk arrives — the cost governor does**, and for
sentences under ~2.9 s it merges every second endpoint, so the chunk that reaches the decoder is bilingual
before any language logic sees it. Separately, per-chunk language detection is a thresholdless argmax that
labels the whole chunk with one token. The 350 ms hangover is real and correct; it is not the bottleneck.

### 3.1 The cadence model on turbo

Every qualifying endpoint (a dip of ≥ 350 ms after > 300 ms of speech, `SileroEndpointer.kt:579`, `:584`)
reaches the governor at `:596`: `if (hasCommitted && nowMs - lastCommitMs < minCommitIntervalMs)` → MERGE
(`closeGate()` only; `lastCommitMs` is not moved). `minCommitIntervalMs` is 3,200 on `npu-turbo`
(`CommitCadencePolicy.kt:104`, `:176`), handed over per session at `FloatingBubbleService.kt:2860-2866`.
So with sentence period T = L + P:

- **commit interval = ceil(3,200 / T) × T**, and each chunk holds ceil(3,200 / T) sentences;
- T ≥ 3,200 → one sentence per chunk; T ∈ [1,600, 3,200) → **two, always**; T ∈ [1,067, 1,600) → three;
- the supremum of the visible cadence is just under **6.4 s** (T → 3,200⁻), e.g. 6,016 ms at L = 2.5 s /
  P = 0.5 s; the minimum is 3.2 s.

60 s sessions, EN/ES alternating, Silero p = 0.9 during speech / 0.1 during pauses, 32 ms frames
(model reproduced frame-for-frame by an independent port of `:533-624`, `:633-665`, `:364-368`, `:413-424`,
`FloatingBubbleService.kt:2008-2111`, `SegmentCapPolicy.kt`, `capCutRetainMs :216-221`):

```
cfg            L     P | commits VAD CAP merges | steady chunks bilingual/mono | mean chunk | duty
NEW 350/3200  1500  500 |  15  15   0   14       |   14 / 0                     |  3,891 ms  | 50 %
NEW 350/3200  1500  800 |  13  13   0   13       |   12 / 0                     |  4,428     | 44 %
NEW 350/3200  2000  500 |  12  12   0   11       |   11 / 0                     |  4,821     | 41 %
NEW 350/3200  2000  800 |  11  11   0   10       |   10 / 0                     |  5,353     | 38 %
NEW 350/3200  2500  500 |  10  10   0    9       |    9 / 0                     |  5,738     | 35 %
NEW 350/3200  2500  800 |  18  18   0    0       |    0 / 17                    |  3,303     | 60 %
NEW 350/3200  3000  500 |  17  17   0    0       |    0 / 16                    |  3,522     | 57 %
NEW 350/3200  4000  800 |  12  11   1    1       |    1 / 10                    |  4,805     | 41 %
NEW 350/3200  any   250 |   4   0   4    0       |    3 / 0  (15 s cap chunks)  | 11,880     | 15 %
NEW 350/3200  any   300 |   4   0   4    0       |    3 / 0                     | 12,128     | 15 %
OLD 500/1200  1500  800 |  25  25   0    0       |    0 / 24                    |  2,309     | 82 %
OLD 500/1200  2000  500 |   4   0   4    0       |    3 / 0  (no VAD cut fires) | 11,880     | 15 %
OLD 500/1200  2000  800 |  21  21   0    0       |    0 / 20                    |  2,811     | 69 %
OLD 500/1200  2500  800 |  18  18   0    0       |    0 / 17                    |  3,312     | 60 %
350/1200 (retune alone) 2000 500 | 23 23 0 0     |    0 / 22                    |  2,515     | 76 %
```

Work per commit W = 1,778.9 + 48 + 44.4 + 10.083 × (1.84 + 3.208 × D) ms ≈ 2,050 ms at any D in play; the
encode is 87 % of it and fixed (`qnn_asr.cpp:484-485` `kAudioCtx = 1500; kMelFrames = 3000`;
`NpuWhisperBackend.kt:470-473`; `whisper_jni.cpp:563`, `:667-673` pad/truncate to 480,000 samples).

### 3.2 The governor-merge finding, frame by frame (L = 2,000 ms, P = 500 ms, NEW)

Gate opens 512; `pendingSpeech` at 832 (`:545`, 320 ≥ 300); first dip frame 2,528 stamps `tempEndMs`
(`:565`); 5th dip frame 2,656 promotes `prevEndMs` (`:577`, 128 > 98); 12th dip frame 2,880: hangover
passes (352 ≥ 350), `speechMs = 2,016 > 300`, `hasCommitted == false` → **CUT** → chunk 1 = [0, 2,880],
EN, monolingual. Gate reopens 3,008; dip 5,024; 12th dip frame 5,376: `5,376 − 2,880 = 2,496 < 3,200` →
**MERGE** (`:614`): gate shut, `pendingSpeech` kept, `lastCommitMs` still 2,880, buffer still holds from
2,880. Gate reopens 5,504 on a NEW clock (`:543`); dip 7,520; `prevEndMs` overwritten 7,648; 12th dip
frame 7,872: `speechMs = 2,016` (the ES sentence is not in this number), `7,872 − 2,880 = 4,992 ≥ 3,200` →
**CUT** → chunk 2 = [2,880, 7,872] = **ES + EN, bilingual**. Steady state: one commit every ~5 s, every
chunk two sentences, two languages. The merged boundary at 5,024 lives in `prevEndMs` for 2,624 ms and is
then overwritten; nothing remembers it.

Timing the owner will feel: a merged sentence's text lands ≈ L + P + 0.352 + ~2.05 = **L + P + 2.4 s**
after it ended (4.9 s at L = 2 / P = 0.5; 5.2 s at P = 0.8); an unmerged sentence lands at ≈ 2.4 s.
Sub-second arrival is unreachable on turbo at any hangover value (brief H5, confirmed).

**Where NEW is better, worse, and the same than the shipping 4.3.0 (500 / 1,200):**

| VAD-visible pause | 4.3.0 | 4.3.1 | For the language goal |
|---|---|---|---|
| < ~384 ms (incl. the owner's 300 and a 250 fast talker) | no VAD cut; 15 s caps, 5-7 sentences per chunk | identical | same, both bad |
| ~384 – ~544 ms | no VAD cut; 15 s caps | VAD fires; pairs (T < 3.2 s) or singles | NEW better: 2 per chunk beats 6 |
| ≥ ~544 ms, T < 3,200 (L ≤ ~2.4 s) | **one sentence per chunk, monolingual**, 60-82 % duty | **pairs, bilingual**, 38-50 % duty | **NEW worse on the goal, better on duty — the adversarial band, and ordinary read prose** |
| ≥ ~544 ms, T ≥ 3,200 (L ≥ ~2.5-3 s) | one per chunk | one per chunk | same, both good |

The capture corroborates that 4.3.0 really was in that regime on real speech: 12 of the in-run gaps are
1,938-2,603 ms — VAD cuts 4.3.1 would merge — and three of those pairs carry different detect tokens
(7 id / 8 en, 38 en / 39 ko, 56 en / 57 es). That is also why the floor was introduced: 4.3.0 at
L = 1.5 s / P = 0.8 s runs at 82 %, over the ceiling, into an unbounded queue.

**There is no code lever inside the duty rule.** Per-sentence monolingual chunks at period T cost
2.05 / T: 73 % at 2.8 s, 82 % at 2.5 s, 89 % at 2.3 s. The strict 2,830 floor changes only
T ∈ [2,830, 3,200). Turbo cannot deliver per-sentence chunks for sentences shorter than ~2.9 s. The
remedy is a cheaper fast producer (the small model, §4.6), not the hangover and not the floor. One
caveat on framing: the complaint `51f4655` was tuned against (faint/fast pauses) is the < 512 ms band,
where NEW is strictly better; the alternating-language goal is the brief's, and that is the band where
NEW is worse.

### 3.3 The hangover floor (both builds)

The hangover fires on the frame with `(k − 1) × 32 ≥ 350` → k = 12, i.e. 352 ms after the first
sub-RELEASE frame (`:565`, `:579`; `EndpointerGrid.kt:61-63`, `:81`). The acoustic pause must therefore
exceed 352 ms (best phase) to ~384 ms (worst phase); at 500 it was 17 frames = 512-544 ms; at the named
floor `HANGOVER_MIN_MS = 300` (`EndpointerTuning.kt:106`) it would be 11 frames = 320 ms. Dead-band
frames inside the dip do not reset the clock (`:555` writes nothing); a frame at p ≥ 0.50 does (`:540`).
A 300 ms pause and a 250 ms fast talker produce **zero** VAD cuts in either build and ride the 4 s / 15 s
caps (§3.1 rows P = 250/300).

### 3.4 What per-chunk language detection actually is

On the NPU tier, only when the session language is `null` (Auto — `PreferencesManager.kt:178-181`;
`NpuWhisperBackend.kt:613`): `nativeDetectLanguage` refuses without a live encode (`qnn_asr.cpp:3423-3427`),
zeroes the self-KV (`:3431`), runs **one** decoder step at position 0 with input SOT (`:3432`,
`kSotToken = 50258` at `:575`), reads the raw `uint16` logits (`:3437`), and takes
`argmaxInRange(logits, langTokenFirst, langTokenLast + 1)` (`:3438-3439`) — a strict-`>` scan from
`kLogitFloor = 0` (`:1951-1961`, `:536`), so **ties resolve to the first id, `<|en|>` = 50259**; the source
says so at `:3441-3448`. The band is 50259..50358 (100 codes) on turbo (`:974-975`;
`WhisperTokens.kt:230-231`). The runner-up and margin exist only under `g.diag` (`:3450`), which is
`nativeSetDiag(BuildConfig.DEBUG)` (`NpuWhisperBackend.kt:457`) — absent in release and absent from both
captures. The only refusal is every logit at the bottom rail (`:3489-3492`). **No confidence threshold,
no probability, no cross-segment memory**: `detectsPerUtterance = fallbackBackend == null` (`:743`), so
`LocalWhisperEngine` hands `lang` through unchanged (`:379-380`) and never feeds `LanguagePin` (`:472-475`).
The detected id goes straight into slot 1 of the four-token prompt `[sot, lang, transcribe, noTimestamps]`
(`NpuDecodePolicy.kt:297-302` → `:75-80`), so a label is not a label — the chunk is **decoded under it**.
The detect pass itself costs a median 9 ms (encode→detect line gap, min 6, max 13) = 0.5 % of the encode;
it is free **given an encode**, and never otherwise. Its input is the same zero-padded 30 s window: a
1.5 s chunk is 5 % audio, 95 % zeros.

**The capture evidence, read honestly.** 12/57 segments (21 %) carried a non-English token; 12 of 56
consecutive pairs change label (at segments 6, 8, 9, 10, 39, 40, 44, 45, 46, 47, 52, 57) over ~383 s of
speech — one label change every ~32 s, about the period of the owner's proposed 25 s window. There is no
transcript and no ground truth, and the plan says the material was "a multi-language YouTube video"
(`2026-09-02-vad-hangover-retune.md:536`), so the clusters (id ×2 at #6-7, zh ×5 at #47-51 with
31/7/14/23/6 tokens) may well be real content. The isolated short ones are the short-chunk shape: #39 ko on
3 tokens between English segments, #57 es on 2 tokens at session end, and the baseline capture's one ja on
a 1-token segment. By token bucket, the non-majority rate is 2/5 (40 %) at ≤ 4 tokens vs 5/29 (17 %)
above 16 — direction, not significance. The one budget-terminated runaway (#44, 196 tokens, 2,019.4 ms)
is on a zh-labelled segment, the shape a wrong label produces whether or not this one was wrong.

**A merged chunk gets one token.** Which of two sentences "wins" a 2 s + 2 s window is not decidable from
the code (one argmax over the whole window's cross-KV); what is decidable is that **the other sentence is
decoded under the wrong `<|xx|>`**, that a low-confidence result re-runs up to six rungs under the **same**
token (`qnn_asr.cpp:3185-3188`, `:3202` re-feeds the prompt), and that the 196-token ceiling
(`WhisperTokens.kt:70`; `NpuDecodePolicy.kt:130-138`) is closer. Whether the model then translates,
code-switches, or garbles is not verifiable from this repo; the model-lab acceptance row that would
measure it is blank (`docs/superpowers/sdd/2026-08-29-npu-model-lab/acceptance.md:229-235`).

**A "language switch = boundary" rule fed by these labels** has no consumer to attach to today —
`SegmentOutcome.Text` carries only text (`SegmentOutcome.kt:13`), `Release` carries `(text, lostSegments)`
(`SegmentOrderer.kt:38`), `lastReportedLanguage` (`NpuWhisperBackend.kt:673`) is read only by
`detectedLanguage` (`:766-770`), which the engine calls only when `!detectsPerUtterance` (`:472-475`), i.e.
never while the NPU is live — and if it existed it would have fired 12 times on this capture. Two-chunk
hysteresis does **not** neutralise it: 4 boundaries survive (in at #6, out at #10, in at #47, out at #52).

### 3.5 What the owner should expect to SEE on the phone (Auto, turbo, 4.3.1)

- Two-second sentences with natural pauses: the window updates every ~5 s carrying **two sentences**; one
  of each pair is in the wrong language or garbled. That is E7 — the governor, not the VAD. Sentences of
  ≥ 3 s arrive one per chunk, ~2.4 s after they end.
- A first sentence of ≥ 3.5 s is cut mid-sentence at 4,000 ms by the first-segment cap (`SegmentCapPolicy.kt:45`
  `>=`, `:54`) with `retainMs = 0` (no dip yet → sentinel → `capCutRetainMs` returns 0, `:217`); `reset()`
  sets `hasCommitted` (`:366`), so the next endpoint 864 ms later is merged and chunk 2 = fragment tail +
  the next sentence. **Pre-existing in both builds**; not the retune.
- 15 s dumps when speaking fast with pauses under ~380 ms: **both builds**, the hangover floor, not a
  regression (§3.3). A false alarm for the retune.
- An isolated wrong-language blip on a 1-3-token chunk (a "yes", a cough, a session-end fragment): the
  thresholdless detector; present on 4.3.0 (the baseline capture's ja). A false alarm for the retune.
- E1 passing ("cuts land on sentence ends") while the pairs above are happening — the sheet's blind spot.
- In release only `encode:` / `detect:` / `decode:` lines survive; `detect:` lines absent entirely means the
  session is not Auto and nothing will ever switch.

### 3.6 The onboarding copy steers users off the only mode that can switch

`OnboardingLogic.kt:40`: "Choosing a language makes multilingual transcription faster." `:48`:
"Slower on multilingual models — detects per session." (a 3.8 owner ruling carried verbatim, `:42-46`,
pinned `OnboardingLogicTest.kt:107-115`). The device-language row leads (`:74-82`) and Continue needs a
pick (`:89`). On the shipping tier both sentences describe the CPU path (`LanguagePin.kt:6-9`): on turbo
detection is per utterance at ~9 ms of a ~2,050 ms commit, and a user who follows the copy picks "English",
`getLanguageForApi()` returns `"en"`, `NpuWhisperBackend.kt:613` never detects, and every Spanish sentence
is decoded under `<|en|>` with no `(detected)` note anywhere (`NpuDecodePolicy.kt:291-296`, `SELECTED`). The
owner's "one sentence English, one sentence Spanish" is unreachable in the configuration the app
recommends. Two strings and one test pin, gated on the owner re-ruling their own 3.8 sentence. Highest
leverage per line in this whole review.

---

## 4. THE 25 s FINALIZER

### 4.1 What works

- **The encode is fixed, so a 25 s window costs the same encode as a 1 s chunk.** `[1, melBins, 3000]` by
  graph shape (`qnn_asr.cpp:484-485`; `NpuModelSpec.kt:274`, `:277`); `pcmToMel` pads or truncates to
  480,000 samples (`whisper_jni.cpp:563`, `:667-673`). Measured: 1,778.9 ± 20.1 ms over utterances of every
  length. Brief H1 holds on the NPU tier in duty terms.
- **H2's hard problem is already solved by the shipped delivery model.** Nothing reaches a third-party
  field, the clipboard, or history until `stopRecording`'s single `deliverFinalTranscript`:
  `handleTranscriptionResult` writes only `sessionTranscript` and the sink (`FloatingBubbleService.kt:3433-3441`,
  KDoc `:3425-3432`); the only two `injectTextWithResult` call sites are `:3473` and `:3505` and the only
  three `setPrimaryClip` writes are `:3486`, `:3503`, `:3519`, all inside `deliverFinalTranscript`
  (`:3454-3456`, guarded once by `finalDelivered`, `:602`). Live dictation **already waits for finals**.
  The finalizer never retracts anything from another app; it only has to revise the bubble's model before
  `:3137-3139` reads the sink file.
- **Every seam it needs is additive.** New listener/backend members take default bodies by house rule
  (`TranscriptionEngine.kt:135-142`); the ladder is per-call data (`NpuWhisperBackend.kt:636`); the cadence
  floor is documented as the number to raise (`CommitCadencePolicy.kt:85-87`).

### 4.2 The cost arithmetic

One 25 s final on turbo: 1,779 (encode) + 48 (pre-graph) + 44.4 + 10.083 × 80-85 tokens (25 s × 3.2-3.4
tok/s) = **2,678-2,728 ms = 10.7-10.9 % of 25 s**. (EOT-only token density is 2.95-3.08 tok/s → 2,618-2,648
ms; inside rounding.)

The fast path it sits beside, per 25 s at the 3,200 ms floor: F = 1.779 + 0.048 + 0.0444 + 1.84 × 0.01008 =
1.890 s; N = 25 / 3.2 = 7.81 commits; conserved per-token work 25 × 3.2 × 0.01008 = 0.81 s; fast = 7.81 ×
1.890 + 0.81 = **15.57 s = 62.3 %** — the KDoc's own saturated figure (`:79`). Add the final: **73 %**, over
the 0.70 ceiling (`:11`); effective F = 1.890 + 2.70 / 7.81 = 2.24 s, past the 2.14 s the 13 % thermal
margin was sized for (`:80-83`). Restoring it by the KDoc's rule (`:85-87`): 0.70 → (17.5 − 0.81 − 2.70) /
1.89 = 7.40 commits → **≥ 3,376 ms**; the shipped 62 % margin → (15.5 − 0.81 − 2.70) / 1.89 = 6.34 →
**≥ 3,938 ms**. **The finalizer's honest price on turbo is the floor: 3,200 → ~3,900 ms.**

Duty is not the binding number; the stall is. `LocalWhisperEngine`'s executor "MUST be single-threaded"
(`:22-25`, `:46-50`) and `NativeComputeGate` holds a process-global fair lock across every NPU transcribe
end to end (`NativeComputeGate.kt:25`, `:33-37`; `NpuWhisperBackend.kt:483-484`). A window pass blocks the
next fast chunk for its full ~2.7 s: at 3,200 ms with ~2.05 s of work there is 1.15 s of slack per
interval, so one pass consumes 2.70 / 1.15 = **2.35 intervals** (queue depth 2, ~7.5 s to drain; the sheet's
E2 bound is 0-2, `acceptance.md:211-218`); at 3,900 ms the slack is 1.85 s and a pass consumes ~1.5
intervals. The fast chunk right after a pass lands ~4.8 s after its speech instead of ~2.4 s.

**CPU tiers: H1 refuted.** `audio_ctx = clamp(filteredSamples / 320 + 64, 512, 1500)` (`whisper_jni.cpp:897-908`)
on the VAD-filtered audio (`:815`), and the file's own 768 → 512 measurement (`:57-58`) shows the cost is
near-linear in `audio_ctx`. A 25 s all-speech window is 25 × 50 + 64 = 1,314 frames = 2.57 × a fast chunk's
512: **5.9 s at F = 2.3 s** (`CommitCadencePolicy.kt:107` derivation), 6.4-9.2 s at the owner-measured
2.5-3.6 s. **The finalizer is an NPU-only feature**; `detectsPerUtterance` (`:743`, read per segment at
`LocalWhisperEngine.kt:380`) already tells the engine when the NPU is live, so a mid-session fallback
switches it off from the next window.

Two things that make a final **worse** than the fast text it replaces: a terminator that is not EOT (the
196-token budget, `qnn_asr.cpp:3338`, `:3371`; the entropy/cycle cut, `:3328-3336`, `:3363`) returns a
truncated window; and the six-rung ladder (`:3185-3188`; `NpuDecodePolicy.kt:169`) turns 2.7 s into up to
~7 s. The ladder is a parameter of the native call (`NpuWhisperBackend.kt:636`) — pass a 1-2 rung array
for finals; the terminator is computed per call (`:3382`) but does not cross the `WhisperBackend` seam
today, so gating on it needs one seam addition.

### 4.3 THE hard problem — the output side, stated bluntly

It is not the accessibility layer. It is that **nothing in the pipeline records which characters came
from which segment**. `SegmentOrderer.Release` is `(text, lostSegments)` (`SegmentOrderer.kt:38`); `drain()`
removes the outcome from the map (`:75`) and concatenates into one `StringBuilder` (`:80-90`) — the seq is
gone. Downstream is bare `String` all the way: `handleTranscriptionResult(text)` (`:3433`),
`TranscriptSink.append(segment)` (`TranscriptSink.kt:27`). The sink is **append-only** — `FileWriter(…,
append = true)` (`:23`), `append` is the only mutator (the class has `append`, `fullTextFile`, `close`:
`:27`, `:54`, `:57`), and its in-memory tail truncates from the **front** only (`:48-50`). Delivery reads the
sink **file** (`:3137-3139`); history reads `sessionTranscript` (`:3206-3210`); the window is repainted from
`sink.preview` as a whole string (`:2701-2702`), so the **render** is already replace-shaped and the
**model** is not. `onDelta` is not the mechanism: it returns at its first statement for every non-live
session (`:2898`, `deltaOwnsPreviewStrip(live) = live` at `:276`), and the NPU emits zero deltas anyway
(`NpuWhisperBackend.kt:703-705`, `:711-722`). The 4.4 memory's "the bubble's delta strip already has replace
semantics via onDelta" (`whisper-everywhere-ship-track.md:169-170`) is false for the sessions it is about.

So the finalizer's whole feature is **a per-seq span record plus a replace-capable transcript journal that
also rewrites the sink file** — or delivery ships the fast text and history the final (the late-segment
divergence at `:3442-3444` already exists in the other direction).

### 4.4 Recommended architecture

- **Buffer:** a second `ByteArrayOutputStream` (`windowBuffer`) inside `LocalWhisperEngine`, written in
  `sendAudio` inside the existing `bufferLock` critical section beside `buffer.write(pcm)` (`:198-200`),
  untouched by `commit()`'s `buffer.reset()` (`:254`) and by `commitRetainingTailMs`'s reset/rewrite
  (`:303-307`), cleared by its own window cut and by `close()` (`:533`). `MAX_BUFFER_BYTES` (`:78`, `:216`)
  guards `buffer` only; the window needs its own ceiling and it must be **< 30 s**, because past 480,000
  samples the NPU truncates silently with one `LOGDIAG` (`whisper_jni.cpp:667-673`). 25 s target, 28 s hard
  bound. Memory: 800,000 bytes beside the fast buffer's ≤ 960,000.
- **Marks:** `windowMarks += Mark(seq, byteOffset, cutKind)` inside `commit()`'s lock at the seq allocation
  (`:255`) — every fast segment's byte span inside the window is then known by construction, with the same
  "identity fixed by audio order" argument the seq relies on (`:242-247`). The retained-tail path
  (`:306-308`) must mark the retained bytes as belonging to the next seq.
- **A service-level accumulator dead-ends**: `TranscriptionEngine` has no "transcribe this blob" entry point
  (`commit()` cuts what the engine holds), and the only blob path, `BatchTranscriber`, is "NOT a
  TranscriptionEngine" with its own single-thread dispatcher (`BatchTranscriber.kt:37-40`, `:47-53`).
- **Never a second `NpuWhisperBackend`.** `nativeInit` is idempotent by releasing the previous session
  first (`qnn_asr.cpp:2637-2639`) and issues a fresh epoch (`:2772`); the live instance's next `transcribe`
  then fails the epoch check (`NpuWhisperBackend.kt:503-514`) and falls the whole session back to the CPU
  tier, once and permanently (`:880-881`). The finalizer runs on the same executor through the same backend
  instance — it must take the instance, not resolve one.
- **Trigger** at the one commit funnel, `commitSegment` (`FloatingBubbleService.kt:3307-3313`), which already
  reads the VAD cut record right after the verdict (`:3322`): a `WindowPolicy.onCommit(seq, cut, lang?,
  audioMs, nowMs)` — pure, Context-free, JVM-pinned like `SegmentCapPolicy`. Window-full: when the window
  holds ≥ ~20 s, close at the **newest VAD mark**, never mid-segment; if every boundary was a cap cut (music,
  R2), close at the newest cap mark whose retain split found a micro-pause (`:2090`, `capCutRetainMs`
  `:216-221`), else at the 28 s bound. Stop: the STOP commit (`:3063`) is followed by the `awaitIdle` fence
  (`:3108-3110`) before the sink is detached (`:3134-3136`), so a tail-window final enqueued from it lands in
  time — at a cost of ≤ 2.7 s on the stop drain, or skip the tail; product choice.
- **Replace contract, by seq range, never by text** (the fast chunks were decoded context-free — no
  `<|startofprev|>` block in the prompt, `NpuDecodePolicy.kt:75-80`; `no_context = true` on CPU,
  `whisper_jni.cpp:846`) and never by time (the CPU VAD filter time-compresses):
  `onWindowFinal(WindowFinal(windowId, fromSeq, toSeq, outcome, lang, terminator))`, a new listener member
  with a default body. Rules, each a bug if omitted: (1) apply only after every seq in the range has been
  released; (2) replace **iff** `outcome is Text && terminator == eot` — a budget/cut final, a blank, or a
  `Lost` keeps the fast text, and a window whose fast segments all resolved `EmptyExpected` is never
  finalized (music beds; 2.7 s to blank what was blank); (3) the replaced span is the union of the seqs'
  spans **including** any `[…]` marker the orderer emitted for a `Lost`/`EmptyUnexpected` seq in the range
  (`SegmentOrderer.kt:95-109`) — the finalizer had that audio; (4) the revision reaches **both** the sink
  file and `sessionTranscript`; (5) a final never consumes a fast seq — `SegmentOutcome` is a closed
  vocabulary with no replace member (`SegmentOutcome.kt:12-20`), and resolving a window as `Text` through
  `onSegmentResolved` would append it after the chunks it supersedes.
- **The span record:** `Release(parts: List<Part(seq, text)>, lostSegments)` and a `TranscriptJournal`
  (`append(seq, text)`, `supersede(fromSeq, toSeq, text)`, `materialize()`) that keeps `preview` and
  `fullTextFile()` and rewrites the file on supersede (a few KB every 25 s). `sessionTranscript` becomes
  `journal.materialize()` at `:3208`. The sink's bounded-memory rationale (`TranscriptSink.kt:10-15`) is
  already moot: `sessionTranscript` (`:573`) holds the whole session in RAM.

### 4.5 Staging — what to build first

- **Stage 0 (83):** ship as is. Record in the 4.4 memory that the delta-strip premise is false on-device
  and that the recorded 0.7 s / turbo-at-VAD-close split does not close (§4.6).
- **Stage 1 — the replace contract on one model (turbo fast + turbo 25 s finals), NPU-only:** journal +
  per-seq `Release` + journal-backed delivery read (pure, JVM-testable, no native change); `windowBuffer` +
  marks + `finalizeWindow` + `onWindowFinal` + a `DecodeProfile` with a 2-rung ladder for finals;
  `WindowPolicy` at the funnel; turbo floor 3,200 → ~3,900 with the derivation in the KDoc; an E2-style
  queue-depth row on the sheet (debug build for `queue: depth=`; native `encode:`/`decode:` pairs on the
  track build — a window pass is one encode followed by a ~80-90-token decode). Finals decoded under the
  window's own detect (Auto) or the forced code; **no language splitting yet**. This already repairs a
  single misdetected chunk inside a monolingual window: 25 s of English detects `en` and the replace
  overwrites the garbage.
- **Stage 2 — language runs (Auto only):** `lang` beside `SegmentOutcome.Text` (kept `reportable`, never a
  `(locale)`/`(fallback)` guess, `NpuDecodePolicy.kt:240-244`); ≥ 2-chunk hysteresis in `WindowPolicy`;
  per-run finals with a minimum run length; **a cost cap**, because every run pays the fixed encode
  regardless of its length: 3 runs per 25 s = 3 × (1.779 + 0.048 + 0.0444) + 0.81 + 0.06 = **6.5 s = 26 %**,
  5 runs = **10.2 s = 41 %** — on top of 50-62 % fast work, both over 0.70. Promote the detect margin to a
  production stat if single-chunk flips survive hysteresis on the device (on this capture 4 do).
  A genuinely bilingual window decoded under one token is the one case where the finalizer is worse than
  per-utterance detection; that is why runs are the precondition, not a nicety.
- **Stage 3 — the two-model split** (R18's task 1): a second QNN session, a second mel context, a
  per-session gate; then swap the fast producer to small at its 1,200 ms floor. The journal, the window and
  `onWindowFinal` are unchanged.

### 4.6 One model or two

Per 25 s of continuous speech at ~3.2 tok/s:

| Split | Fast producer | Final producer | Fast | Final | Total |
|---|---|---|---|---|---|
| A. turbo + turbo finalizer, 3,200 | 7.81 × 1.89 + 0.81 = 15.6 s | 2.7 s | 62 % | 11 % | **73 % — over** |
| A′. same, floor 3,900 | 6.41 × 1.89 + 0.81 = 12.9 s | 2.7 s | 52 % | 11 % | **62 %** |
| B. the recorded 4.4 plan (small partials every 0.7 s + turbo finals at every VAD close) | 35.7 × 0.5 = 17.9 s | 7.8 × 1.89 = 14.7 s | 71 % | 59 % | **~130 % — impossible**, and 0.7 s is under the `npu` row's 1,200 ms floor (`CommitCadencePolicy.kt:59`, `:175`) |
| C. small fast at 1,200 + turbo 25 s finals ONLY | 20.8 × 0.495 + (80 + 20.8 × 1.84 = 118) × 0.011 = 11.6 s | 2.7 s | 46 % | 11 % | **57 %** |
| D. BlackBox loop on turbo alone (re-transcribe every 1.5 s) | 2.05 s per pass > 1.5 s | — | > 100 % | — | dead on paper, as the memory says |

C is the only arrangement that reaches ~1 s-class visible text under the ceiling with a finalizer — and
its small-side inputs are **PROVISIONAL** (`:51-58`: a spike-measured 404.6 ms encoder pass,
`2026-08-28-npu-spike-g1-results.md:15`, `:27`, not a measured full-segment F; the small-vs-turbo A/B sheet
is blank, `2026-08-29-npu-model-lab/acceptance.md:206-209`). It is blocked on a JNI redesign, not a spike:
one global `NpuState g` (`qnn_asr.cpp:716`) with one encoder and one decoder slot (`:598-599`) behind one
mutex (`:586`); `nativeInit` releases the previous session first (`:2637-2639`); the epoch guard exists to
make cross-model contamination loud (`NpuWhisperBackend.kt:489-497`, `:503-514`); `pcmToMel` overwrites a
shared mel context (`:476-477`); ~1.43 GB of context binaries resident (358 MB + 1.07 GB,
`app/build.gradle.kts:707`, `:711`) and two mel passes at 80 and 128 bins (`NpuModelSpec.kt:322`, `:353`).
`npu_small` is wired, packaged and deliverable (`build.gradle.kts:236`) but not offered on a turbo-capable
device (`WhisperModel.kt:316`, `:368-375`; "STAYS CATALOGUED (the streaming arc needs it)" `:333-337`).

**Ruling: one model first.** Build the replace contract on A′ (turbo does both, floor ~3,900); it is
byte-for-byte the machinery C needs. Swap the fast producer when co-residency lands. Small on the NPU
gives "about a second" (0.35 + a provisional 0.5-1.0 s), not sub-second; nothing on the NPU stack gives
sub-second visible text.

### 4.7 What the current commit must not foreclose

1. The floor is the knob, and `51f4655` documented it so (`CommitCadencePolicy.kt:85-87`). Leave the
   hangover alone when the finalizer lands.
2. `engine.sendAudio(chunk)` first and exactly once (`CapSeamPinTest.kt:68-73`) — the window buffer lives
   in the engine; no service change at the feed.
3. Do **not** add a replace member to `SegmentOutcome` or route finals through `onSegmentResolved`;
   finals need their own channel (§4.4 rule 5).
4. `SegmentOutcome.Text` carries no language (`SegmentOutcome.kt:13`); the switch trigger needs `lang`
   beside the outcome — additive, `reportable` only.
5. Delivery reads the sink file (`:3137-3139`); history reads `sessionTranscript` (`:3208`). Any revision
   must reach both, or the delivered text and history split.
6. If the banked floor lands first, it multiplies per-chunk detects (each word-by-word commit is a fresh
   1-step detect on a mostly-silent window). Build hysteresis (Stage 2) before the bank if "switch =
   boundary" is ever to be derived from chunk labels.
7. The 30 s truncation is silent (`whisper_jni.cpp:673`); the policy enforces the bound, not the encoder.
8. Fix the two stale comments before anyone designs from them: `FloatingBubbleService.kt:3105` ("Each
   result injects live as it finishes" — it does not, `:3433-3441`) and `SegmentOrderer.kt:12-14`
   ("`ACTION_SET_SELECTION` is never used" — it is, `WhisperAccessibilityService.kt:774`, `:963`; the
   conclusion "retroactive repair is impossible" still holds on its other two reasons).

---

## 5. SPEAKER SEPARATION SETUP

**What a diarizer consumes:** continuous 16 kHz PCM with a **session-timeline coordinate**, returning
`(startSample, endSample, speakerId)` at tens-of-ms resolution; and, to attribute text, either ASR
timestamps or at minimum each ASR segment's `[start, end)` on the same timeline. It does not consume text,
it does not benefit from VAD chunking (the gaps the VAD cuts away are where the turns are), and it must not
run on the engine's single thread (`LocalWhisperEngine.kt:22-25`) or inside `NativeComputeGate`
(`NativeComputeGate.kt:25` serializes all native whisper work) — a CPU ONNX diarizer runs beside the NPU
only on its own thread outside the gate.

**What is hostile to it today (every item a code fact):**

- `commit()` snapshots, **resets** the buffer and allocates the seq (`LocalWhisperEngine.kt:248-256`); the
  PCM is referenced once more at `pcm16ToFloat` (`:366`) and is unreferenced after resolution. No copy of
  a committed segment's audio survives.
- **No session sample counter exists.** A grep over `app/src/main` (`*.kt`) for `sampleOffset | sessionSample
  | absoluteMs | segmentStartMs | startSample | pcmStart | audioStartMs` returns nothing; the `commit:` line
  (`:257`) prints a length, not an offset.
- `EndpointCut` is durations only — `(speechMs, trailMs, prob)` (`SileroEndpointer.kt:689`).
- The seq dies at `SegmentOrderer.drain()` (`:38`, `:71-90`); nothing downstream knows which characters
  came from which segment (§4.3).
- **No timestamps on either tier.** NPU: `<|notimestamps|>` in the prompt (`NpuDecodePolicy.kt:79`) and all
  1,501 timestamp ids suppressed (`:100-102`). CPU: `print_timestamps = false` (`whisper_jni.cpp:842`),
  `token_timestamps` never set (the only mention, `:918`, is a comment; default `false` at
  `whisper.cpp:6206`). Tinydiarize is off (`tdrz_enable = false`, `:6216`) and `<|startoflm|>` is in the NPU
  suppress set (`WhisperTokenFamily.kt:193-197`); it is not a route to multilingual turns anyway.
- **A 350 ms VAD cut is not a speaker turn.** Two speakers back-to-back within 350 ms (`:579`) land in
  one chunk with one text and no coordinate to split at; a single speaker's clause pause yields two chunks
  and no speaker information. Chunk granularity is the wrong granularity for this problem.

**The contract to preserve now (so the finalizer's plumbing is also the diarizer's):**

1. The window buffer of §4.4 carries a **monotonically increasing session sample index**; `commit()` and
   `commitRetainingTailMs` record `[startSample, endSample)` per seq under `bufferLock`, the retained tail
   accounted to the next seq. One buffer, two readers.
2. The seq survives to the journal (`Release.parts`, `journal.append(seq, text)`), so a turn `[s, e)` can be
   attributed to text by overlap with the seq spans. Word-level attribution inside a chunk needs timestamps
   the models do not emit today; do not promise it.
3. Keep every cut kind as a mark (VAD / CAP / STOP / SWITCH) with its sample offset — the diarizer's
   candidate boundaries are the VAD's cut points, and it will want the ones the governor merged, which
   today survive ~2.6 s in `prevEndMs` and are then overwritten (`:577`).
4. `lang` rides beside the outcome, `reportable` only. Never derive "speaker changed" or "language
   changed" from a single chunk's label.
5. The diarizer is a consumer of `windowBuffer` spans, never of the engine executor; the finalizer must
   not be designed on the assumption of one `NpuWhisperBackend` per process forever.

---

## 6. RISK REGISTER (ranked)

| # | What it is | What it costs if it bites | How you would know on the device |
|---|---|---|---|
| 1 | **Governor merge makes 2 s alternating sentences bilingual by construction** (T < 3.2 s → pairs; `SileroEndpointer.kt:596`, `CommitCadencePolicy.kt:104`/`:176`). Regression vs 4.3.0 in the ≥ 544 ms pause band; no in-build remedy under the duty rule. | The owner's motivating case ships worse than production on quality (no data loss); text lands L + P + 2.4 s late; one sentence of each pair decoded under the wrong token. | Window updates every ~5 s carrying two sentences; one half garbled/translated. E7 (§1.A). |
| 2 | **The promotion gate cannot see #1.** E1 passes on merged pairs (`acceptance.md:203-208`); no alternating-language row; rollback names one line (`:285-286`). | #1 is promoted unseen; a hangover-only rollback lands on the worst 4-combination. | Nothing — the sheet is the instrument. Fix the sheet before §E (§1.A-B). |
| 3 | **Per-chunk detection: 100-way argmax, ties → `en`, no threshold, per chunk, no memory** (`qnn_asr.cpp:3438-3448`, `:1951-1961`; `NpuWhisperBackend.kt:613`, `:743`; `LocalWhisperEngine.kt:379-380`). | Short chunks decoded under a wrong `<|xx|>`: fluent wrong text; a runaway to the 196-token budget (2.0 s decode); up to six re-decodes under the same wrong token. | `detect: language token` flipping on 1-3-token segments; a `terminated by the token budget` line; `rung>0` on `decode:`. |
| 4 | **Onboarding steers users to a fixed language**; Auto is the only mode where detection runs (`OnboardingLogic.kt:40`, `:48`, `:74-82`, `:89`; `NpuWhisperBackend.kt:613`). | Multi-language is unreachable for every user who follows the copy; Spanish decoded under `<|en|>` with no diagnostic. | In release, no `detect:` lines at all in a session = not Auto. Needs an owner re-ruling of the 3.8 sentence. |
| 5 | **Temperature-ladder worst case into an unbounded queue**: a low-confidence, not-silent rung runs to 196 tokens and then advances, up to six rungs (`qnn_asr.cpp:3338`, `:3350-3356`, `:3185`); ~12 s of decode + 1.8 s encode on one segment; `LocalWhisperEngine.kt:50` has no shed rule. | Text lag ≈ depth × 2.1 s and climbing; `awaitIdle` gives up at 300 s and drops the tail (plan `:554`). | The in-flight strip "Transcribing… (3+ in queue)" persisting; `decode:` lines with `rung=5` and thousands of ms; `encode:` climbing past ~2.1 s sustained (thermal). |
| 6 | **Pauses under ~352-384 ms never fire the hangover** — 12 consecutive sub-RELEASE frames (`:565`, `:579`; `EndpointerGrid.kt:61-63`); `HANGOVER_MIN_MS = 300` forbids the value that would reach 300 ms pauses (and even 300 needs 320). Both builds. | Fast talkers ride the 4 s / 15 s caps with 5-7 sentences per chunk. | A 15 s silent stretch then a paragraph — in **both** builds; a false alarm for the retune. |
| 7 | **The 4 s first cap spends the free first cut** (`SegmentCapPolicy.kt:45` `>=`, `:54`; `retainMs = 0` when no dip yet, `CommitCadencePolicy.kt:217`; `reset()` sets `hasCommitted`, `SileroEndpointer.kt:366`). Pre-existing. | A first sentence ≥ 3.5 s arrives as a fragment, then fragment-tail + next sentence (bilingual if alternating). | First chunk cut mid-word at ~4 s; second chunk starts with the tail. |
| 8 | **The wall-cap fix's one cost**: a single p ≥ 0.50 frame plus a ≥ 128 ms dip in an otherwise silent first stretch writes `prevEndMs` (`:577`) and consumes the 4 s window (`:2075`) where it used to re-arm (`:2077`). | The first real sentence waits for its own VAD cut (usual) or the 15 s cap (long first sentence). Bounded; a note. | A slow first cut after a click/door/lip-smack. |
| 9 | **A mid-session NPU → CPU fallback silently ends per-utterance detection** (`NpuWhisperBackend.kt:743` flips false; `LanguagePin.kt:35`, `:41-46` latch the first detection; `:16-18` "accepted trade"). | Language switching stops for the rest of the session with no signal. | `encode:`/`detect:`/`decode:` lines stop appearing mid-session; multi-second commits on the CPU. |
| 10 | **The NPU loop leaves the language-token band unsuppressed** (suppress set `WhisperTokenFamily.kt:193-197` + timestamps `NpuDecodePolicy.kt:100-102`; whisper.cpp masks them every step, `whisper.cpp:6513-6516`). A mid-stream `<|es|>` is stored (`qnn_asr.cpp:3322`), counted (`:3338`), fed back (`:3339`), dropped only at render (`WhisperBpeDecoder.kt:178`). The in-source reason (`:157-159`, "masking would break detection") is stale: detection reads raw logits before any mask (`:3437-3438`; masks are written only inside `suppressThenArgmax`/`Sample`, `:1926-1933`, `:3300-3302`). | Rare and silent for English; one place turbo is less constrained than the reference at exactly the code-switch. | Not visible on the device. Cheapest alignment: add the band to `suppressList`; needs a device pass, not for 83. |
| 11 | **Release observability**: R8 strips every Kotlin `WE-DIAG` line (plan `:529`, sheet `:38`); only `encode:`/`detect:`/`decode:` survive; `queue: depth=` and `endpoint:` are gone. | E2 cannot be judged on the track build; VAD-MISS is inferred from a 15 s silent stretch. | The strip label is the only leading indicator; run the debug build for anything else. |
| 12 | **If the bank is built**: E4 inverted on percussion (3 commits / 11.26 s); 17 commits/min → 54-62 % duty on a bed; confident bed text typed; row-3 ladder unbounded (§2). | R2 inverted in production with Play vitals as the only instrument. | `cut=vad` every ~3.5 s over drums (debug) / `decode:` lines every ~3.5 s during a bed (release). |
| 13 | **If the finalizer is built without its rules**: 73 % at 3,200; a bilingual window under one token; a budget/cut-terminated final replacing complete fast text; a second backend instance releasing the live session (`qnn_asr.cpp:2637-2639`, `NpuWhisperBackend.kt:503-514`); a > 30 s window silently truncated (`whisper_jni.cpp:667-673`). | Fluent wrong-language text **replacing** correct fast text — "the worst failure shape this tier has" (`NpuWhisperBackend.kt:493-494`); or the whole session on the CPU. | `encode:` pairs 2.7 s apart with the strip backing up; a final shorter than the text it replaced; sudden CPU-speed commits. |
| 14 | **If the bank is built — the cross-thread race**: a Main `reset()` racing the capture thread's `+= 32` leaves the whole pre-reset bank (§2); only `switchSource` is live (`:2235` before `:2238`). | One early `cut=vad` after a source switch, still governor-paced. | A `cut=vad` within a second of switching mic ↔ device audio (debug build). |

---

## 7. WHAT WAS REFUTED (so it is not re-derived)

- **Brief:** encode "~1,752 ms" — 1,752 is the baseline capture's mean; this capture is 1,778.9 ± 20.1.
  "Decode ~15.7 ms/token" — the marginal cost is 10.083 ms/token with a 44.4 ms intercept; the two agree
  within 3 % at the 18-25-token chunks in play, so no conclusion moves. `whisper.cpp:4455` → the default is
  at `:4708`. "`whisper_jni.cpp` ~661-673" is the NPU **mel pad/truncate**, not the graph shape
  (`qnn_asr.cpp:484-485`); `whisper_jni.cpp`'s own `audio_ctx` **scales** (`:897-908`). Discard block is
  `:584-594` with an inclusive `<=`; `closeGate` is `:633-637`.
- **Brief H2's premise** — nothing is typed into a third-party field mid-session (`:3425-3432`, inject
  sites `:3473`/`:3505` only). The conclusion (do not build retraction) survives; the escape clause
  ("unless dictation waits for finals") is the shipped product.
- **Brief "native 250 makes the client stricter"** — CPU-only: `we_vad_filter`'s one caller is
  `whisper_jni.cpp:815`; the NPU ignores `useVad` (`NpuWhisperBackend.kt:470-473`). On turbo there is no
  second floor. And native's floor is a **span** to the final dip with a 200 ms post-merge re-checked
  against `min_speech` (`whisper.cpp:5586-5590`, `:5612`) — the accumulated floor has no native precedent;
  "matching native" can only describe the number 250, and on turbo not even that.
- **Brief's expected outcome "second word's close (~576 ms banked)"** — that is the 300 figure; at the
  brief's own 250 the first 288 ms word commits at its own close (640 ms). "Percussion banks 4,608 and
  commits at each close" — 3 commits in 11.26 s at 300, the rejected draft's number.
- **Memory (ship-track `:169-170`)** "the bubble's delta strip already has replace semantics via onDelta" —
  false on-device (`:2898`, `:276`; NPU emits zero deltas `:703-705`). **Memory `:155-157`** "small partials at
  ~0.7 s cadence, turbo finals at VAD close" — 0.7 s is under the `npu` row's 1,200 ms floor and the duty is
  ~130 %.
- **In-source:** `WhisperBpeDecoder.kt:157-159`'s rationale (masking language ids would break detection)
  is stale; `FloatingBubbleService.kt:3105` and `SegmentOrderer.kt:12-14` describe the pre-`370e1d7` world.
  Nine 3.7-era `whisper.cpp` line citations in `SileroEndpointer.kt:15-16`, `:536`, `:558`, `:582`, `:585`
  and `EndpointerTuning.kt:52`, `:67`, `:146` point into the VAD model loader at HEAD; they were correct
  when written (gitlink `d111526`, 2026-08-21) and drifted +253 lines with the four NPU mel bumps of
  2026-08-28/29 (the vendored tree is a submodule, gitlink `8772322`; "no git history, cannot be dated"
  was a misread `fatal: Pathspec … is in submodule`).
- **Ship-gate C10** "hangover-only rollback is worse than BOTH 4.3.0 and 4.3.1 in the ≥ 544 ms band" — in
  that band 500 / 3,200 is **identical** to 4.3.1 (L = 2 / P = 0.8: 11 commits, 10 bilingual, 38 %); it is
  worse than 4.3.1 in [384, 544) and worse than 4.3.0 in ≥ 544. Dominated overall; the two-line rollback
  stands.
- **Ship-gate C9** "one commit per 3.2-5.4 s" — the supremum is just under 6.4 s (6,016 ms at L = 2.5 /
  P = 0.5); 5.4 was a mean-chunk figure mistaken for the bound.
- **Ship-gate C8** "at most a one-deep transient queue" — the worst-case ladder is 6 rungs × up to 196
  tokens (~12 s + 1.8 s); the typical case (2.95 s / 3.85 s) stands.
- **Ship-gate C5** "the second sentence is decoded under the FIRST sentence's language" — which sentence
  wins is undecidable from the code; "one token for two languages" is what stands. "12/57 = the detector
  flipping on English" — the count is verified, the misdetection reading is not (no transcript; the plan
  names a multi-language video; the zh ×5 cluster carries real token counts).
- **Ship-gate C12** "+10/−10" — `--numstat` is +6/−4.
- **Floor F3** "`:701-716` flips at 250" — false; the 8-frame burst merges under the 8,000 pre-session
  floor (`:200`) and the test stays green. Keep-300 stands on its other reasons.
- **Floor F10** "no duration-based per-burst minimum can separate ten clicks from ten function words" — a
  3-frame minimum does; it cannot separate 9-frame hits from 9-frame words. That is R4, not the click case.
- **Floor F2's gate citations** `:255-262` → the gate is `:274-277` (E4 at `:277`), the E4 rationale
  `:279-280`.
- **Finalizer C9** "≥ 2-chunk hysteresis yields zero spurious boundaries on this capture" — four survive
  (id ×2 at #6-7, zh ×5 at #47-51).
- **Finalizer C14** "3 runs = +23 %, 5 runs = +38 %" — 26 % and 41 % with the per-token decode included;
  the conclusion (a cost cap is required) is stronger.
- **Finalizer C11** "small gives ~0.85-1.35 s, not sub-second" — self-contradictory and rests on a
  PROVISIONAL small F (`:51-58`); "about a second, unmeasured" is what can be said.
- **Language L11** "under a mismatched token the trained behaviour is cross-lingual output" — not
  verifiable from the repo; three shapes are possible; the Cantonese/Thai CER figures in the research docs
  are in-language accuracy, not evidence about mismatch.
- **Language L14** "every non-English label was to a language outside an en/es set" — #57 is `es`;
  and a restricted-set detect is not "a small change" (JNI return shape, onboarding, single-string prefs).
- **Language L3** "cannot be transcribed as two languages" — overreach; what the mechanism proves is "not
  under their own language tokens" (code-switched output under one token is possible).
- **Language cite errors** (facts real, lines wrong): suppress set is `WhisperTokenFamily.kt:193-197`
  (not `:425-429`); `SegmentOutcome.Text` is `SegmentOutcome.kt:13` (not `:65`); the 50358 table is
  `WhisperTokenFamily.kt:17-23`; the wrong-family warning is `:33-39`.
- **Ship-gate's "motivating case"** framing — `51f4655`'s stated complaint (faint/fast pauses) is the
  < 512 ms band where NEW is better; the alternating-language goal is the brief's, and that is the band
  where NEW is worse. Both are true; the sentence "regression on the motivating case" needs the second
  reading to be precise.
