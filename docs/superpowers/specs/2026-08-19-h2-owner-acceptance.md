# 3.6.0 H2 — Owner on-device acceptance sheet (consolidated)

Owner-run. NEVER `:app:installDebug` or `:app:connectedDebugAndroidTest` (both uninstall first
and wipe the downloaded models). adb is not on PATH:
`$adb = "C:\Users\bastr\AppData\Local\Android\Sdk\platform-tools\adb.exe"`.

**Builds.** BEFORE = the installed 3.5.0 (versionCode 76). AFTER = the 3.6.0 branch build
(versionCode 77). Archive the before-build first so it stays reinstallable:

```powershell
$adb = "C:\Users\bastr\AppData\Local\Android\Sdk\platform-tools\adb.exe"
& $adb shell pm path com.whispereverywhere    # note the /data/app/.../base.apk path
& $adb pull <that path> C:\Users\bastr\.androidbuild\WhisperEverywhere\whisper-3.5.0-BEFORE-360.apk
```

Run the ENTIRE before grid on the installed 3.5.0 FIRST. Then build + install the after-build
(data-preserving — debug→debug updates in place):

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:assembleDebug --no-daemon
& $adb install -r C:\Users\bastr\.androidbuild\WhisperEverywhere\app\outputs\apk\debug\app-debug.apk
```

**The grid — 2 tiers × 3 modes × before/after = 12 sessions.** Per tier (pro, then multi —
switch in Settings) and per mode (local, cloud batch, cloud live): dictate the same ~3 sentences
of CONTINUOUS speech (read a paragraph aloud without pausing — the wall-cap path is under test),
then stop. Between sessions: `& $adb logcat -c`. After each session, save both greps labeled
tier/mode/build:

```powershell
& $adb logcat -d | Select-String "WE-DIAG" | Select-String "finalize-timing"
& $adb logcat -d | Select-String "WE-DIAG" | Select-String "segment-timing"
```

[Correction 2026-08-21 (3.7 F5 review): on a 3.7 build the segment-timing line reads
`segment-timing: seq=<n> audio=<ms> transcribe=<ms> rtf=<x.xx>[ vadIn=<n> vadOut=<n> ctxFrames=<n>]`
— seq= prepended (3.7 F2) and an optional stats suffix appended (3.7 F5/F6). The greps above
still match unchanged. Also: the native VAD summary line and its two failure lines now log
under the WE-DIAG tag, not whisper_jni — an `-s whisper_jni` capture habit no longer sees
them. On a 3.6.0 build the original shape applies as written.]

RTF-capture hygiene (A4 review): capture `segment-timing` with NO batch file-transcription job
running (the compute gate is shared and inflates transcribe-ms) and with cloud OFF for the
local rows (rescue segments stream into the same log unlabelled). For any outlier rtf, pull the
full `& $adb logcat -d -s WE-DIAG` context — a retried segment carries its retries in the
number and the adjacent `transcribe THREW` lines explain it.

## The checks

- [ ] **First visible text under continuous speech (headline) — LOCAL rows:** AFTER multi shows
  first words ≤ ~6 s from record start (BEFORE ≈ 17 s — record the real before number). Pro
  comfortably under. Stopwatch or screen recording, tap → first words. LOCAL-only target: cloud
  rows keep the 15 s cap on purpose (no extra billable round-trip) — in cloud rows check the
  OPPOSITE: the first `wall-clock cap -> commit` line reads `(cap=15000ms)`, and run one
  cloud-batch session with a deliberately SILENT opening stretch (~20 s) — it must NEVER show
  `cap=4000ms` (A2 fix-round regression signature).
- [ ] **Silence semantics — LOCAL (expected, not bugs):** repeated `cap=4000ms` lines in a
  silent local session are CORRECT (the silence re-arm; empty commits are near-free); a user who
  pauses to think still gets the 4 s first cut on their first real speech.
  **On a 3.7 build these cap lines carry a ` VAD-MISS: no endpoint in this window` suffix; on the
  silence re-arm above and on the amplitude-fallback tier that suffix is EXPECTED and is not a
  failure — it is a signature only when the Silero probe is supposed to be live.**
- [ ] **`segment-timing` RTF captured (decision-gate data):** save rtf values per tier (CPU).
  These are the repo's first measured 190 MB-tier RTFs — report verbatim; they feed the
  tier-consolidation and GPU-default gates. Compare multi segment-0 (detect) vs segments-1+
  (pinned) — the pin's win shows there.
- [ ] **Streaming deltas:** during a long local multi segment, words appear progressively WHILE
  transcribing; the external field still gets the text ONCE at stop; history unchanged.
  Watch for raw whisper markers (`[BLANK_AUDIO]`, `(music)`) flashing ≤150 ms on the strip
  during silence/music (D4 F1 — report if seen; a one-line clean is staged). Known accepted
  cosmetic: one hide/show flicker per segment boundary.
- [ ] **FINALIZING line through the stop:** tap stop mid-utterance — the strip keeps reading
  "Finishing transcript…" / "Finishing… (waiting on provider)" for the whole drain, never
  replaced by preview words, never blanked; E6's ticker counts up beside it. ALSO on cloud-live:
  provider partials arriving post-stop must NOT clobber the line (this changed in 3.6.0 — it
  reads as a fix).
- [ ] **Language pin (multi + auto):** `language-pin: detected=<code>` once per session on the
  first speech segment; later `transcribe START` lines carry `effective=<code>`. Watch item
  (B4): a WRONG first detection now locks the session (pre-3.6.0 it cost one segment) — if
  "wrong language" shows up in real use, report it; a one-line min-audio mitigation is staged.
  Mid-session language switching resolves at the next session (accepted spec trade).
- [ ] **Warm switch (E2 post-fix log shapes):** a Settings tier switch logs ONE prewarm line
  (the id-arm's — the install signal coalesces into it), then `prewarmModelSwitch: ctx loaded`;
  next session's CONNECTING is instant. On a FRESH download the prewarm line appears ~750 ms
  AFTER download verification (debounced — not a regression). A tier DELETE logs the line then
  correctly no-ops with NO `ctx loaded`.
- [ ] **Cold-load label:** forced cold start shows "Loading speech model…" until recording;
  warm/cloud sessions show the bare spinner. Known cosmetics (E5): the 28 dp resize grip
  renders above the label during cold CONNECTING (draggable; report if it bothers), and the
  panel visibly grows at CONNECTING→RECORDING (a reclamp keeps it on-screen).
- [ ] **TTS preload (E3):** the GUIDE read starts noticeably faster (~2 s hidden in think-time);
  the TOOLBAR read shows ~NO change — that is a PASS, not a failure (both paths serialize on
  the same executor; the win there is milliseconds).
- [ ] **Stop cost:** warm local stop ≈ tail inference only; live stop logs small nonzero
  `local-drain` (tail rescue — by design); batch happy path logs
  `local-drain=0ms (skipped: no outstanding retries)`. **Reading `drain timed out` (F3):** every
  stop logs `finalize-timing: cloud-budget=<n>ms (reserve=<r>ms)`. Timed-out warning + a
  cloud-budget SMALLER than the full budget = a CAPPED stop (cloud's share was shortened so an
  owed rescue kept its reserve — everything delivered); the warning's "300000ms" is a CONSTANT,
  not a measurement. Only timed-out + FULL budget is a real cloud timeout. Check the delivered
  text before reporting either as a failure.
- [ ] **Drain floor spot check (F):** one batch-cloud session, airplane mode ON mid-session,
  stop. Every spoken segment delivers (rescued on-device, no "[…]" for real speech), nonzero
  `local-drain`, stop completes within FINALIZE_TIMEOUT_MS + 60 s.
- [ ] **GPU canary (C — ships OFF; SHIP GATE: `canary_digits.wav` bundled at release cut OR the
  Settings row is pulled — never hedged copy):** enable the toggle → force a cold multi load →
  `& $adb logcat -d -s WE-DIAG | findstr gpu-canary` FIRST (the safe ordering — running the C8
  bench standalone with the toggle on lets its warm load write a fresh latch), then the verdict
  line. ASSET-ABSENT path (today): the only producible line is `gpu-canary: asset unavailable —
  no verdict recorded`, and after dictating one local segment there must be NO
  `GpuPolicy: GPU validated` line (C5b's sentinel fix — a validated line here is a regression).
  ASSET-PRESENT: PASS → two multi sessions, compare rtf vs CPU grid rows, report both; FAIL →
  session proceeded on CPU, permanent-latch line logged, transcripts clean. On a FAIL the log
  shows `GPU validated` immediately BEFORE `canary FAILED` — that adjacent pair is correct
  behavior, not a contradiction. Disable the toggle afterward unless keeping it.
- [ ] **Bench runs (the G/C decision data):** `bench_audio_ctx_floor_ab` (G3) fills
  `docs/superpowers/specs/2026-08-19-audio-ctx-floor-bench.md` (read its binding=false eyeball
  rule BEFORE recording a PASS; filling RESULT: unblocks G4; prune FLOOR_CANDIDATES below any
  new default before a post-change re-run). `bench_gpu_vs_cpu_ab` (C8) fills
  `docs/superpowers/specs/2026-08-19-gpu-ab-bench.md` (thermal rule: twice from cold, CPU arm
  runs first so a heated SoC biases the GPU arm — only a consistent large gap is signal; its
  verdict token is GPU-VERDICT:, separate from G4's RESULT:).
- [ ] **Regression spot-checks:** dictation into WhatsApp delivers once at stop; Transcriptions
  history records sessions; resize handle works; the how-to guide reads unchanged.
- [ ] **Report** both builds' saved greps + the before/after first-text numbers. If AFTER multi
  first-text misses ~6 s, that is a decision-gate finding, not a silent pass — the release
  notes' "lands sooner" sentence survives only if the numbers back it (H1 contingency).
