# 3.6.0 — Local speed: real-time feel for the 190 MB tiers

**Date:** 2026-08-19 · **Target release:** 3.6.0 (versionCode 77) · **Status:** owner-approved direction
**Origin:** owner reports (~17 s to first text on multi, "hard mode"; stop still feels slow) +
the 2026-08-19 five-layer deep-analysis workflow (code-verified, `wf_a6d82114-85d`). Every claim
below carries the analysis's file:line evidence; the report lives with the session record.

**Goal:** make the 190 MB tiers (pro / multi) fast enough that cloud keys feel optional — first
visible text within ~5 s under continuous speech, words appearing DURING inference, stops that
cost only the true tail work — and gather the on-device numbers that decide the future
one-download-no-choices UX (explicitly deferred, see Decision Gates).

**Baseline (from the analysis, all code-verified):**
- First text under continuous speech = the 15 s wall cap (`MAX_SEGMENT_WALL_MS`,
  `FloatingBubbleService.kt:275`, fires at `:1609-1613`) + full-segment inference. The 800 ms
  pause cut (`SpeechSegmenter.kt:47`) never fires without a clean pause below 250 RMS.
- Multi pays a throwaway language-detect encoder pass EVERY segment when language = auto
  (`whisper_jni.cpp:251-257`, `whisper.cpp:6833-6836` + `:7041`), and is CPU-only ≤4 threads
  forever (`GpuPolicy.isGpuSafeModel` requires ".en" — the empirical corruption gate documented
  in its docblock). Pro forces "en" (`FloatingBubbleService.kt:2076-2077`) and rides
  the GPU.
- Model switch never re-prewarms (`prewarm()` fills only an empty slot,
  `LocalWhisperEngine.kt:333`), so the first session after a switch pays the ~7 s load inline.
- The stop-tap's unconditional flush hands up to ~15 s of never-transcribed audio to the engine
  (`FloatingBubbleService.kt:2227-2232`) — the "empty bucket" that isn't. On live-cloud stops the
  3.5.0 skip structurally never fires (tail rescue always outstanding).
- `LocalWhisperEngine` never emits `onDelta` (verified) — no words during inference.
- No measured RTF for either 190 MB tier exists anywhere in the repo.

---

## Workstream A — Segmentation latency (the 15-second wall)

1. **First-commit cap:** the session's FIRST segment cuts at **4,000 ms** (new
   `FIRST_SEGMENT_WALL_MS`); every later segment keeps the existing 15,000 ms cap. Applies to
   the wall-cap path only — the 800 ms pause cut stays untouched and still wins when a real
   pause happens. Mechanism: the cap compared at `FloatingBubbleService.kt:1609` becomes
   first-commit-aware (state resets per session at RECORDING start, `:2089` anchor).
2. **Consequence, free of charge:** a shorter first cap also shrinks the stop-tap tail for short
   sessions, and later 15 s caps bound it for long ones — no separate stop-path change in A.
3. **Permanent RTF instrumentation:** one WE-DIAG line per segment around `backend.transcribe`
   (`LocalWhisperEngine.kt:261-263`): `"segment-timing: audio=<ms> transcribe=<ms> rtf=<x.xx>"`.
   This converts the analysis's last big ESTIMATE (multi's real RTF) into MEASURED data on the
   owner's device and closes that blind spot for every future report.
4. Pure-logic JVM tests for the cap policy (first vs later, per-session reset); the segmenter's
   existing pause semantics must be pinned unchanged.

## Workstream B — Multilingual double-encode (language pinning)

1. When the user's language setting is **auto** (and only then — an explicit language already
   passes through today, `PreferencesManager.getLanguageForApi():174-177`), the FIRST resolved
   segment of a session detects the language as today; the detected id (exposed via
   `whisper_full_lang_id` through `whisper_jni.cpp`) is then **pinned for the remainder of the
   session** and passed to subsequent transcribes, eliminating the throwaway detect-encode on
   every later segment (~50 % of multi's steady-state native cost).
2. Session-scoped: the pin resets at session start. Per-session detection is preserved (a user
   switching languages BETWEEN sessions is unaffected; switching mid-session re-detects next
   session — accepted trade, recorded here).
3. JNI surface: expose the detected language id from a completed transcribe; Kotlin keeps the
   pin state in the engine (session-lifecycle object, cleared on connect()).
4. JVM tests for the pin state machine (auto → detect → pinned → reset on new session; explicit
   language never pins/overrides).

## Workstream C — GPU re-evaluation for multi (validation-gated, corruption-safe)

The current ban is EMPIRICAL (garbage-token corruption on multilingual models via OpenCL — the
empirical-corruption docblock above `GpuPolicy.isGpuSafeModel`) — it is not lifted blind. Design:
**canary-validated enablement**.

1. Ship a tiny bundled known-audio sample (~1 s, spoken digits) + its expected token set.
2. First time a non-".en" model would use the GPU on this device: load on GPU, transcribe the
   canary, compare. Pass → GPU allowed for that model+device (persisted latch). Fail → permanent
   CPU latch (persisted) + WE-DIAG line; the session proceeds on CPU exactly as today.
3. The canary runs on the native executor during prewarm/connect (adds one ~1 s inference to one
   cold load, once per device+model), never during a user's live session audio.
4. Rollout: the code path ships OFF by default behind a Settings developer toggle
   ("Try GPU for multilingual (experimental)") until the owner's device validates it — flipping
   the default is a data-driven follow-up, not part of this release's default behavior.
5. Owner acceptance: enable the toggle, run the canary + real sessions on the Fold6, compare
   segment-timing RTF vs CPU. WhisperBenchTest harness extended to run per-tier GPU/CPU A-B.

## Workstream D — Native partial streaming (the real-time feel)

1. Wire whisper.cpp's **new-segment callback** through JNI: during a single `whisper_full` call,
   each decoded segment's text is delivered incrementally to Kotlin.
2. `LocalWhisperEngine` forwards them as `onDelta(text)` — the existing preview delta path
   (delta-strip/reclamp already handles cloud-live deltas). **Preview-only, load-bearing:** the
   final-only commit contract is untouched — deltas render in the bubble preview; committed text
   still comes exclusively from segment resolution. No delta ever reaches the external field or
   history.
3. Throttle callback→UI delivery (post at most every ~150 ms) so JNI churn never floods Main.
4. Effect: during a 4-15 s segment's inference the user watches words appear — the "what is this
   model doing" dead air evaporates even where wall time is unchanged.
5. Cancellation safety: callbacks from a transcribe that outlives its session are dropped by the
   existing stale-listener guard semantics; verify explicitly in review.

## Workstream E — Warm paths & honest waits (quick wins)

1. **Re-prewarm on model switch:** every `selectedModelId` writer (`ModelDownloadViewModel.kt:58`,
   `OnboardingSetupViewModel.kt:91,109`, `SettingsScreen.kt:316,323,759`) triggers a debounced
   engine re-prewarm so the NEXT session opens warm instead of paying ~7 s inline.
2. **TTS preload at cold call sites:** `SpeakTextActivity.onCreate` and the Home guide read call
   `TtsController.preload()` (today only the clipboard path warms, ~2 s cold load each time).
3. **CONNECTING label honesty:** cold loads show "Loading speech model…" instead of generic
   connecting (engine already knows which branch it took).
4. **FINALIZING elapsed ticker:** wire the existing-but-dead `startProcessingTimer()`
   (`FloatingBubbleService.kt:2711-2730`) into FINALIZING so long drains visibly count up
   alongside the 3.5.0 status line.

## Workstream F — Drain budget floor (no silent starvation)

`FallbackTranscriptionEngine.awaitIdle` shares one deadline between cloud and local; a
near-timeout cloud tail can starve rescued local segments into silent drops (analysis §3,
batch-mode worst case). Change: reserve a **minimum local budget** (e.g. `min(60 s, budget/5)`)
carved from `FINALIZE_TIMEOUT_MS` — cloud's share is capped at `budget - reserve` when retries
are outstanding. Drain ORDER, reconcile semantics, orderer rules: untouched. JVM test with real
background executor: a cloud drain that exhausts its share still leaves local its reserve and
every rescued segment delivers.

## Workstream G — Tail inference floor (validation-gated; may be dropped)

The stop-tail fragment pays a 768-frame minimum `audio_ctx` for ~119 frames of audio
(`whisper_jni.cpp:264-277`) — 6.45× overshoot on the finalize critical path. The floor was
raised 256→768 for a documented accuracy regression: **lower it only if the WhisperBenchTest
harness proves per-tier accuracy holds at a smaller floor.** If the bench says no, this
workstream ships nothing and records the numbers. Never lowered blind.

## Workstream H — Ship mechanics + acceptance

- 3.6.0 / versionCode 77. No new permissions, no FGS/Data-Safety/disclosure changes.
- Owner acceptance protocol (WE-DIAG, before = 3.5.0 build, after = 3.6.0 build, per tier
  pro+multi, per mode local/batch/live):
  - First visible text under CONTINUOUS speech: multi ≤ ~6 s (was ~17 s).
  - `segment-timing` RTF captured for both tiers (CPU, and GPU-canary if enabled).
  - Warm local stop ≈ tail inference only; live stop documented (tail rescue is by design).
  - Streaming deltas visibly render during local inference.
- Release notes: factual, our-own-before/after framing only; NO absolute or cloud speed claims.

## Constraints (binding, unchanged from 3.5.0)

- UNTOUCHABLE: `EmptyExpected`/`FallbackPolicy.reconcile` semantics, `SegmentOrderer` release
  rules, disclosure texts, the final-only commit contract (deltas are preview-only), the how-to
  guide's pinned invariants (guide copy may gain a streaming line ONLY if its tests are updated
  in the same task — prefer leaving it untouched).
- `best_of=1` explicitly REJECTED (whisper.cpp anti-repetition fallback is load-bearing,
  `whisper_jni.cpp:220-226`).
- Concurrency-adjacent JVM tests use real background executors. TDD throughout.
- Copy discipline: no cloud speed claims; relative on-device claims must be our-before/after.

## Decision Gates (owner, post-measurement — explicitly OUT of 3.6.0 scope)

1. **Tier consolidation** ("one whisper download, no choices"): decided AFTER the owner's
   before/after numbers land. If multi hits the targets, eco/base retire in 3.7 via the existing
   `retired` catalog mechanism (retired tiers stay resolvable; existing users unaffected).
2. **GPU default for multi:** flips only on canary + bench + owner-device evidence.
3. **Bigger model tier** (e.g. medium): revisit only after 1-2 land.
