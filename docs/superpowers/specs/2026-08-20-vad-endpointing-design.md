# 3.7 — Real VAD endpointing: utterance cadence on pro, paced cadence on multi

**Date:** 2026-08-20 · **Target release:** 3.7.0 (versionCode 78) · **Status:** owner-approved direction
**Origin:** owner mandate ("with the VAD activity, we want a real VAD… that's how we get real-time
speech to text — that's what we want, for sure") + the five-layer investigation (`wf_13954b27-8c7`,
code-verified, ten layer errors corrected) + the 2026-08-20 on-device measurement session
(records: `2026-08-19-audio-ctx-floor-bench.md` RESULT: PASS floor=512 EXECUTED,
`2026-08-19-gpu-ab-bench.md` GPU-VERDICT: BAN reason=slower for multi / .en GPU validated).

**Goal:** replace amplitude-threshold segmentation with the vendored streaming Silero VAD so
segments cut when the user actually stops talking. Pro runs true per-utterance cadence
(speech-end → visible text ≈ 1.3–1.8 s, constant); multi runs the same endpointing at a paced
commit interval that its measured cost supports. Stops on an idle queue become near-instant
(the tail is pure silence). Wall caps survive unchanged as backstops, so every pathological
case degrades to exactly today's behavior.

**Measured baseline (Fold6, vc77, floor 512, production backends — all MEASURED, none modeled):**
- Fixed per-commit cost F: **pro-GPU ≈ 0.77–1.0 s · multi-CPU ≈ 2.3 s** (was 3.5 s at floor 768).
- Steady rtf (8 s slice): pro-GPU 0.28–0.31 · multi-CPU 0.41–0.48.
- Speech-end → text at utterance cadence: pro ≈ hangover 0.5 s + ~0.8–1.3 s = **~1.3–1.8 s, GO**.
- Multi at true utterance cadence: arrival-RTF > 1 — NO-GO; the sustainable governor is
  arithmetic: `F·N + m·S ≤ 0.70·60 s` with F=2.3, m≈0.45, S=38.4 s/min speech →
  **N ≤ ~10.7 commits/min → min commit interval ≈ 6 s: paced GO**.
- The streaming primitive ships today: `whisper_vad_detect_speech_no_reset` carries LSTM state
  across calls (graph-level `ggml_cpy` write-back), the Silero model (885 KB) is bundled, and
  the mic delivers exactly one 512-sample frame per 32 ms callback. Probe cost ≈ 1.7 ms worst
  case against a 32 ms budget; second VAD context ≈ 2.6 MB RSS; battery ≈ 1–3 % of what the
  feature already draws.

---

## Workstream A — Native probe surface (whisper_jni.cpp)

1. Four externs beside the existing six in `WhisperNative.kt`:
   `vadProbeInit(path): Boolean` · `vadProbeFrame(buf: ByteBuffer, nBytes: Int): Float` ·
   `vadProbeReset()` · `vadProbeFree()`.
2. Dedicated `g_probe_ctx` + `g_probe_mutex` — **never** sharing `g_vad_ctx` (the batch filter
   resets LSTM state on entry, clobbers `probs`, and its sched is not thread-safe: three
   independent corruptions if shared).
3. `vcp.n_threads = 1` (field is `n_threads`, `whisper.h:683` — the `.n_thread` initializer
   comment is wrong). Without it every 32 ms graph compute spawns+joins 3 pthreads
   (disposable-threadpool path, `ggml-cpu.c:3319-3324`) — 93.75 create/join per second.
4. **Outside `NativeComputeGate`, provably safe:** VAD is forced CPU-only at
   `whisper.cpp:4671-4674` regardless of params; own backend, own work buffers; 2.6 MB is no
   OOM risk. Routing 32 ms frames through the fair gate would queue them behind 4–15 s
   `whisper_full` calls or Batch's ~54 s gate holds — recreating the stall being fixed.
5. Frame contract: `nBytes != 1024` → return **−1.0f** ("no verdict", never "silence") — a short
   frame zero-padded still advances the LSTM and poisons the recurrence, so the caller
   accumulates to exact 512-sample boundaries. PCM16→float natively via
   `GetDirectBufferAddress`; reusable direct buffer; no per-frame allocation, no callback.
6. Probe runs **inline on the capture thread** (≥128 ms driver ring slack = ≥4 frames of burst
   tolerance; a handoff thread adds a silent-drift failure mode with no inline analogue). An
   overrun counter records any frame that missed its budget; promotion to a dedicated thread is
   a measured decision, not a default.

## Workstream B — Fork hygiene (we/v1.9.1-android) + the free win

1. **B′ (ship first, independently):** `vcp.n_threads = 1` at `whisper_jni.cpp:136` — the
   existing batch VAD burns 375–1,407 pthread create/join cycles per chunk today. Verify by
   diffing successive cumulative `vad time` lines (the counter accumulates with `+=` — per-call
   cost is the delta, not the printed value).
2. Demote the five per-call `WHISPER_LOG_INFO` lines (`whisper.cpp:5104,5105,5108,5117,5176`)
   to `WHISPER_LOG_DEBUG`: at frame rate they are ~125 logd writes/second on the audio thread —
   likely exceeding the probe's inference cost — and they evict the WE-DIAG lines the owner's
   acceptance greps depend on.
3. Optional, only if memory dashboards complain: `graph_size = 256` for the VAD sched
   (−1.5 MB RSS, −78 MB uncommitted VA).

## Workstream C — SileroEndpointer (pure Kotlin, JVM-pinned)

1. 512-sample accumulator + Schmitt trigger + hangover + min-speech + per-tier min-commit
   interval, ported faithfully from `whisper.cpp:5270-5340` **including the dead-band rule**: a
   frame with `RELEASE ≤ p < ONSET` neither clears `tempEnd` nor counts as silence — only a
   frame ≥ `ONSET` resets the hangover clock. That is what makes the hangover a hard timer.
2. **Micro-pause memory (from the vendored `max_speech_duration_s` design):** track `prevEnd` =
   the most recent ≥3-frame dip below `RELEASE`. When the 15 s wall cap fires with the gate
   open, the endpointer offers `prevEnd` as the cut point instead of an arbitrary millisecond —
   a strictly better boundary for the same latency bound (`no_context = true` makes mid-word
   cuts unrepairable).
3. Latched slow-probe cutout: N consecutive frames over `PROBE_BUDGET_MS` → amplitude fallback
   for the rest of the session, never per-frame retried (the `we_on_new_segment` latch
   discipline).
4. `-1.0f` sentinel handling: "no verdict" frames keep the previous state — they never open or
   close the gate.
5. All constants in one object, pinned by unit tests: hysteresis, hangover, min-speech,
   min-interval, dead-band clock, sentinel, micro-pause offer, cutout latch.

## Workstream D — Integration seam, fallback, cadence policy

1. Interface at the existing decision site (`FloatingBubbleService.kt:1689-1724` — the cap check
   is ALREADY the `else if`, so caps survive structurally with zero restructuring):
   ```kotlin
   interface Endpointer {
       fun onFrame(chunk: ByteArray, amp: Int, nowMs: Long): Boolean  // true => commit now
       fun hasPendingSpeech(): Boolean
       fun reset()
   }
   ```
   `AmplitudeEndpointer` wraps today's `SpeechSegmenter` byte-identically (ignores `chunk`);
   `SileroEndpointer` is Workstream C. Chosen once at construction on `VadModel.path() != null`.
2. `sendAudio` at `:1668` stays unconditional and FIRST. The stop flush at `:2388` stays
   unconditional (gating it on pending-speech discarded whole sessions for soft talkers — a
   better VAD does not make that gate safe). Live bypass (`RealtimeTurnPolicy`) untouched —
   `CLOUD_LIVE` never sees the endpointer.
3. **Per-tier commit cadence** (the cost governor, from measurement — one policy object):
   - pro (and any tier whose measured F ≤ ~1.2 s): `MIN_COMMIT_INTERVAL_MS = 1200` (below
     ~1.1 s the commit is zero-padded to the same encoder cost anyway — merge, don't commit).
   - multi: `MIN_COMMIT_INTERVAL_MS = 6000` (derived: F=2.3 s, m≈0.45 → ≤10.7 commits/min at
     0.70 duty). The endpointer still cuts at real pauses — it just merges utterances until the
     interval elapses. Predictable ~2.8 s speech-end→text at the paced boundary, no 15 s walls.
   - extreme/ultra (539–574 MB, unmeasured): 8000 conservative; H2 measurement may revise.
   - cloud batch: `3000` (every commit is one HTTP POST; `Semaphore(3)` in-flight, shed at 24 —
     the same reasoning that made the 4 s first cap LOCAL-only, `FBS.kt:2228-2238`).
4. `hasPendingSpeech()` semantics upgrade rides free: the soft talker in a noisy room flips from
   permanently-false to true, the LOCAL-silence re-arm stops mis-firing, and the branch itself
   is untouched — only the predicate under it gets honest.
5. `vadProbeReset()` wired into ALL five reset sites: cap cut (`:1722`), `switchSource`
   (`:1819` — carrying LSTM state across a mic↔device-audio swap is a correctness bug), onOpen
   (`:2224`), stopRecording (`:2393`), and the endpointer's internal post-commit reset.
6. Collapse `SpeechSegmenter.maxSegmentMs` (a dead, differently-anchored duplicate of
   `MAX_SEGMENT_WALL_MS`). One wall clock: `SegmentCapPolicy`.
7. Fallback tiers, all with precedent: model missing → AmplitudeEndpointer (byte-identical to
   today, existing "running without VAD" path); probe slow → latched cutout (C.3); caps always
   (structural floor). With the probe forced unavailable, a full session must be byte-identical
   to 3.6.0 — pinned by the regression suite running unchanged.

## Workstream E — Capture-thread hardening (pre-existing bugs, now load-bearing)

1. `StreamingAudioRecorder.stop()` joins BEFORE `audioRecord.stop()` (`:107-108`) — backwards;
   `PlaybackAudioCapturer.kt:90-93` does it right and documents why. With a native probe in the
   callback this graduates from latent to ANR-vector (called from Main at `FBS.kt:2344`/`:1822`)
   and can release the AudioRecord under a live `sendAudio`. **Reorder: stop, then join.**
2. `THREAD_PRIORITY_URGENT_AUDIO` on both capture threads (`TtsEngine.kt:302` is the in-repo
   precedent).
3. Overrun counter surfaced in the `probe:` diagnostic line.

## Workstream F — Endpoint diagnostics (the mandate is unmeasurable without this)

One greppable family; `commit()` already returns seq at every call site (currently discarded):
```
endpoint:       seq=N cut=vad|cap|stop|switch speechMs=… trailMs=… p=…
segment-timing: seq=N audio=… transcribe=… rtf=… vadIn=… vadOut=… ctxFrames=…
queue:          depth=N
perceived:      seq=N speechEndToVisible=…ms
probe:          frames=N p50=…µs p99=…µs overruns=N
```
`seq=` is PREPENDED to the existing `segment-timing` shape (every `findstr segment-timing` grep
keeps working; the 6 `SegmentTimingTest` assertions update in the same task). `ctxFrames=`
exposes the encoder-context cost driver, currently invisible from Kotlin. `speechEndToVisible`
stamps where the
preview actually renders (`deliverReleasedText`). `cut=vad … vadOut=0` instruments the rare
probe/batch-filter disagreement. The `wall-clock cap -> commit` line is reworded as the
VAD-failure signature it becomes.

## Workstream G — UX: the in-flight state (replaces D4's role for local)

Local native deltas arrive in one burst at ~100 % of transcribe wall time (both callback sites
sit after the window's decode), so the delta strip renders and clears within a single frame —
the "flicker" H2 filed as cosmetic IS the feature at utterance cadence. Retire native deltas as
the local strip driver; drive the strip from commit/resolve: per-utterance `"transcribing…"`
plus queue depth when >1. This is the only surface that makes a growing multi backlog visible
while it happens. Bonus: removes the per-utterance reclamp/scroll churn. Cloud-live deltas
unchanged.

## Workstream H — Tier retirement: eco + base (owner decision 2026-08-20)

Retire the 60 MB tiers via the existing `retired` catalog mechanism (retired tiers stay
resolvable; existing users unaffected; no re-download forced). Owner: "pretty much useless at
this point… because of the accuracy." Post-3.7 lineup: **pro = flagship** (English, GPU, real
time), **multi = the international tier** (paced, CPU; NPU is its long game), extreme/ultra
unchanged. Onboarding/model-chooser copy steers English-locale users to pro and non-English to
multi; no forced single download in 3.7 (the one-download UX question stays open until this
lineup has field data).

## Workstream I — Ship mechanics + owner acceptance

- 3.7.0 / versionCode 78. No new permissions, no FGS/Data-Safety/disclosure changes (the VAD
  probe consumes the same mic stream the session already records).
- Owner acceptance (H2-style sheet, before = 3.6.0+G4 build):
  - Frame-by-frame Silero probs over the owner's 8 s "zero dips below −22 dB" clip — converts
    the noise-robustness premise (currently ESTIMATE: the vendored tree contains zero
    robustness evidence) and the soft-talker fix from ESTIMATE to MEASURED in one pass.
  - Per-tier `perceived: speechEndToVisible` p50/p95 — pro target ≈ 1.3–1.8 s constant; multi
    paced boundary ≈ 2.8 s; variance is the headline (today's later-segment latency is uniform
    over 0–15 s).
  - Stop on idle queue ≈ tens of ms (pure-silence tail short-circuits before `whisper_full`);
    `finalize-timing: local-drain` becomes the honest backlog diagnostic under load.
  - Cloud batch: request count per minute bounded by the 3 s interval; `cap=4000ms` still
    absent in cloud sessions (the 3.6.0 regression signature stays valid).
  - Threshold/hangover A/B on owner recordings (vendored Silero reflect-pads instead of using
    n_context — published thresholds do not transfer verbatim; budget for tuning).
  - Probe overruns = 0 on the Fold6.

## Tuning constants (one object; A/B ranges are owner-acceptance knobs)

| Constant | Value | Rationale (full derivations in the investigation report) |
|---|---|---|
| `ONSET_THRESHOLD` | 0.50 | Native default. The batch filter's 0.40 compensates onset clipping with `suppress_nst` at the token layer — endpointing has no token layer; different job, different knob. Batch filter keeps 0.40 untouched. |
| `RELEASE_THRESHOLD` | 0.35 | Schmitt hysteresis (native: threshold−0.15) — the exact mechanism whose absence causes today's 251–499 RMS dead band. Widen to 0.30 if mid-word splits appear. |
| `HANGOVER_MS` | 500 | Native 100 ms is a file-segmentation value. Inter-clause pauses run 200–500 ms; the cost asymmetry is lopsided (too short = an extra full encoder pass + an unrepairable mid-clause boundary). A/B 350–800. Also feeds `speech_pad_ms=150` trailing audio. |
| `MIN_SPEECH_MS` | 300 | Native filter already drops <250 ms commits before `whisper_full`; 300 keeps client and native agreeing instead of fighting. |
| `MIN_COMMIT_INTERVAL_MS` | per-tier: 1200 pro / 6000 multi / 8000 extreme+ultra / 3000 cloud | The measured cost governor (Workstream D.3). |
| Smoothing | NONE | The reference deliberately doesn't smooth; hysteresis + duration + hangover already low-pass. An EMA adds lag and a second thing to tune. |
| `FIRST_SEGMENT_WALL_MS` / `MAX_SEGMENT_WALL_MS` | 4000 / 15000 unchanged | Backstops, now failure signatures rather than the normal path. The 15 s cap cuts at the endpointer's remembered micro-pause when one exists (C.2). |
| `PROBE_BUDGET_MS` / cutout N | 8 ms / 16 frames | Latched cutout trigger; generous against 0.2–1.5 ms expected. |

## Constraints (binding)

- UNTOUCHABLE: wall caps in the `else if` (byte-identical with a never-firing endpointer,
  pinned by test); cloud 4 s-cap suppression (`cap=4000ms` in cloud = regression); unconditional
  stop flush; unconditional-and-first `sendAudio`; `no_context = true` final-only commit;
  live bypass; `EmptyExpected`/reconcile semantics; `SegmentOrderer` release rules; disclosure
  texts; the 3.5.0 skip + delivery fence; drain reserve economics (the reserve is a bound, not
  a cost — do not "optimise it away" on the strength of the empty-tail win);
  `NativeComputeGate` wraps every whisper call (the probe alone bypasses, with the safety
  argument recorded in Workstream A).
- The batch `we_vad_filter` keeps its own 0.40/150 ms tuning — probe decides WHEN to cut, the
  filter decides WHAT reaches the encoder. Independent knobs.
- Concurrency-adjacent JVM tests use real background executors. TDD throughout. Claim rules:
  our-own-before/after only, no absolutes, no cloud speed claims.

## Decision gates (owner, explicitly OUT of 3.7 scope)

1. **NPU/QNN track (4.0-scale):** ONNX Runtime + QNN PoC with Qualcomm AI Hub Whisper-Small-En
   on the Fold6; encoder-on-NPU + decoder-on-CPU hybrid targets multi's F. Qualcomm-only
   (fragmentation is the cost); NNAPI deprecated. Justified only if the PoC encoder lands at a
   few hundred ms where CPU pays 2.3 s.
2. **GPU experiment Settings row:** the C8 verdict closed the question (correct but 9× slower;
   canary is corruption-only by design, so a pass routes toggle-ON users to the slow backend).
   Recommendation: remove the row in 3.7, keep GpuPolicy machinery + latches inert. Owner call.
3. **One-download UX:** revisit after the pro-flagship/multi-international lineup has field
   data.
4. **TranscriptSink flush cadence** (sync Main-thread flush per commit — 6× rate under 3.7):
   move off Main if H2 shows jank; not pre-optimised.
