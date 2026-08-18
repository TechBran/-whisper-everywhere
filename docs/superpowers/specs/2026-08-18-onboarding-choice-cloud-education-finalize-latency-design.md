# 3.5.0 — Onboarding model choice + cloud education, Home cloud-key note, finalize-latency fix

**Date:** 2026-08-18 · **Target release:** 3.5.0 (versionCode 76) · **Status:** owner-approved design
**Origin:** two Play reviews traced to the same root cause (a non-English user served by the weakest
multilingual tier with no idea better options existed), plus an owner-reported stop-lag: "the system
holds transcriptions open longer than necessary" when ending a session.

Four workstreams. A/B are UI + copy; C is an evidence-first performance investigation and fix;
D is release mechanics. A and B are independent; C is independent of both.

---

## Workstream A — Onboarding: choose a model, learn the system

### Problem

Guided onboarding auto-downloads a hardcoded tier (`OnboardingSetupViewModel.ONBOARDING_MODEL_ID =
"base"` — Base multilingual, the weakest multilingual model) with no choice and no explanation.
Users never learn that tiers differ, that models can be switched, or that cloud keys exist. A
Bengali-speaking reviewer got unusable accuracy and concluded the language "doesn't work"; a second
review followed. Owner decision: the user picks — with honest descriptions — and onboarding must
teach model switching and the cloud-key option up front.

### A1. The model-choice step

- The guided onboarding engines step no longer auto-starts a speech download. It opens with the
  four offered tiers (`WhisperCatalog.pickable`: eco / base / pro / multi) as selectable cards.
- **No preselection** (owner decision). The flow's continue/download action stays disabled until a
  card is picked. One tap on a picked card's confirm starts BOTH downloads (chosen speech tier +
  read-aloud voice) side by side via the existing `OnboardingSetupViewModel` machinery — the
  no-further-buttons contract from the 2026-08-01 owner decision is preserved after the single pick.
- `ONBOARDING_MODEL_ID` is deleted; `ensureSpeech()` takes the chosen tier (parameter or persisted
  selection via `prefs.selectedModelId` set at pick time — planner's choice, but exactly one source
  of truth). All existing idempotence/retry semantics (`Working` blocks re-entry, `Failed` is
  retryable, disk-truth on `Ready`) are preserved unchanged.
- The voice download (`ensureVoice()`) is unchanged and still starts at the same moment.

### A2. Tier descriptions — the actual fix

One pure, Android-free copy object (pattern: `HowToGuide`) is the single source of truth for tier
copy, consumed by BOTH the onboarding cards and the Settings manual picker
(`OnboardingModelScreen.kt`, which currently renders `WhisperCatalog.pickable`):

| Tier | Copy (headline · badges · body) |
|---|---|
| eco | **Fastest** · `English only` `60 MB` · Real-time dictation on any phone; the lightest download. |
| base | **Fast** · `90+ languages` `60 MB` · Quick everyday dictation in most languages; lighter accuracy than the big multilingual tier. |
| pro | **Best English accuracy** · `English only` `190 MB` · Noticeably slower than Eco, noticeably sharper. |
| multi | **Best multilingual accuracy** · `90+ languages` `190 MB` · The pick for non-English dictation. |

- Language coverage renders as a badge, visually impossible to miss; "English only" must appear on
  every ENGLISH-scope tier.
- JVM tests pin the discipline (the test that would have prevented the Bengali review): every
  offered tier's copy states (a) a size, (b) a speed-vs-accuracy position, (c) language coverage;
  every `ModelScope.ENGLISH` tier's copy contains "English only"; every MULTILINGUAL tier's copy
  contains "90+ languages". Copy for retired tiers is not required.
- Relative speed words between ON-DEVICE tiers ("fastest", "slower") are factual and allowed here;
  the no-speed-claims rule constrains CLOUD claims and marketing surfaces (guide/listing), which
  this change does not touch — `HowToGuideTest` must stay green without modification.

### A3. The two teaching lines

- Under the model cards: **"Not sure? Pick one — you can switch models anytime in Settings."**
  (plants the switching habit; lowers the stakes of the forced choice).
- The guided flow's existing cloud-keys step is promoted to a proper teaching card. Copy must say,
  in substance: you can plug in your own API key from OpenAI, Google Gemini, ElevenLabs, or Soniox;
  the big cloud models give top accuracy and the widest language coverage; usage is billed to YOUR
  provider account at provider rates; it is entirely optional — the on-device model always works
  and remains the default. No speed claims. (Planner: locate the existing step's composable —
  `OnboardingSetupViewModel`'s kdoc references it as "the cloud-keys step" — and rework copy in
  place; do not add a new navigation destination.)

### Error handling / unchanged behavior

- Download failure/retry, the `.installed` marker contract, activity-scoped ViewModel lifetime,
  and Home's missing-engine status rows (which share `ensureSpeech`) are all preserved. Home's
  missing-engine row re-download (no onboarding context) uses the user's already-selected tier —
  which it does today via the shared ViewModel — and must not regress.
- Existing installed users never see onboarding again; nothing changes for them in A.

---

## Workstream B — Home screen cloud-key note

- A dismissible card on the Home screen: **"Want top accuracy or more languages? Add your own API
  key — large cloud models from OpenAI, Gemini, ElevenLabs, or Soniox, billed to your own
  account."** Button: **"Open Engines & voices"** navigating via the existing route Home already
  uses to reach `EnginesAndVoicesScreen`.
- Visibility rule: shown only when (no cloud STT provider is configured/selected) AND (not
  dismissed). Dismissal is an X on the card, persisted in `PreferencesManager` (new boolean pref,
  plain-var idiom). Configuring a cloud key hides it permanently regardless of dismissal.
- No speed claim (owner decision). The visibility predicate is extracted pure and JVM-tested
  (configured × dismissed truth table); the card composable itself follows house convention
  (untested UI shell).

---

## Workstream C — Finalize latency: instrument, convict, fix

### Problem

Owner report: ending a session (especially with cloud keys) takes noticeably long — "the system is
holding the transcriptions open longer than necessary." Since 3.4.0's final-only commit, NOTHING
lands in the target field until the ENTIRE stop path completes, so any tail latency is now fully
user-visible where it used to be masked by per-segment streaming.

### Known structure (verified in code, 2026-08-18)

- `stopRecording`'s finalize coroutine blocks on `transcriptionEngine.awaitIdle(FINALIZE_TIMEOUT_MS)`
  before delivery.
- `FallbackTranscriptionEngine.awaitIdle` (cloud/FallbackTranscriptionEngine.kt:379) drains
  **cloud first, then local**, sharing one deadline; the kdoc records that local receives retries
  queued from cloud's resolution callbacks.
- The stop tap also triggers a final `commit()` — the tail segment's full provider round-trip
  happens inside the drain. Live sessions additionally run `finishServerTurns()` before the drain.
- Candidate holds (to be proven, not assumed): (1) the local drain waiting on local work whose
  cloud results already resolved; (2) the tail-segment cloud round-trip (irreducible, but must be
  the ONLY wait); (3) live server-turn close latency; (4) anything else the timings expose.

### C1. Instrument first (the Iron Law applies)

Add WE-DIAG timing logs bracketing each stop-path phase: stop-tap → final commit dispatched →
cloud drained → local drained → orderer flushed → delivery done → IDLE. One log line per phase
with elapsed ms. This ships in the same release regardless of the fix (permanent diagnosis
capability, matching existing WE-DIAG conventions). The owner runs one cloud session + one local
session + one live session and reports timings; the fix targets what the evidence convicts.

### C2. Fix directions (gated on C1 evidence; implement what is convicted)

- **If the local drain is the hold:** stop waiting on local work that exists only as a fallback
  shadow for already-resolved cloud segments — e.g. drain cloud, then drain local ONLY for
  segments whose cloud result did not resolve (the retry set), or cancel local's queue for
  cloud-resolved segments at stop. The `EmptyExpected`/`FallbackPolicy.reconcile` contract and the
  SegmentOrderer release rules are LOAD-BEARING (see cloud-track notes) and must not be loosened —
  any change here needs a real-background-executor JVM test proving no segment loss.
- **If the tail round-trip dominates:** it is irreducible for batch cloud, but verify no
  serial waits stack on top of it (e.g. commit not dispatched until after some unrelated await).
- **Live path:** verify `finishServerTurns` closes turns promptly; no fixed sleeps.
- **UX layer ships regardless:** during FINALIZING the preview's status line shows what is being
  waited on — "Finishing… (waiting on provider)" for the cloud drain phase, plain "Finishing
  transcript…" otherwise — so an honest 2-second wait never feels like a hang.

### Acceptance

Stop-to-text-in-field time ≈ the unavoidable tail work (cloud: one provider round-trip; local:
remaining backlog), with no wait attributable to work whose result is already known or discarded —
verified by the C1 timings on the owner's device, before/after.

---

## Workstream D — Ship notes

- 3.5.0 / versionCode 76. No new permissions, no FGS changes, no Data Safety changes, no
  disclosure-text changes (the v3→v4 landmine stays dormant).
- Release notes: lead with "pick your model with clear descriptions", mention faster session
  finish. Claim rules as always.
- Owner on-device checklist: fresh-onboarding walkthrough (clear app data on a spare profile or
  reinstall AFTER backing up models — NEVER a bare uninstall on the daily device), model switch in
  Settings, Home note dismiss + key-configured hiding, and the C1 before/after timing sessions.
- Testing summary: tier-copy discipline tests, Home-note visibility predicate tests, any C2
  concurrency change carries a real-background-executor test; full JVM suite green per commit.
