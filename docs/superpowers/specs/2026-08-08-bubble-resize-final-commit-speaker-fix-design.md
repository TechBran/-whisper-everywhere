# 3.4.0 — Resizable transcript window, final-only commit, speaker-morph removal

**Date:** 2026-08-08 · **Target release:** 3.4.0 (versionCode 74) · **Status:** owner-approved design
**Origin:** a Google Play review (two requests) + one owner-reported bug. All file:line references
verified against HEAD `22cbbbd` by three independent adversarial verification passes.

The three workstreams are independent and individually shippable, but ship together as 3.4.0.

---

## Workstream 1 — Remove the selection-driven speaker morph (bug)

### Problem (verified root cause)

On certain text fields, placing the cursor / typing converts the main bubble from mic to
speaker. Root cause: `onTextSelected` (`FloatingBubbleService.kt:523-539`) is the **only**
non-tap path into speaker mode, fed exclusively by `WhisperAccessibilityService.
handleSelectionChanged` (`:140-184`) on `TYPE_VIEW_TEXT_SELECTION_CHANGED` with a non-empty
range. The heuristic has no editability check, no select-all-on-focus guard (the injection
side guards exactly this — `InjectionAnchor.kt:42-51` — the morph side doesn't), and trusts
a stale-node fallback range (`:151-155`) the code itself labels "often -1/stale". Confirmed
real-world triggers: URL bars / search boxes that auto-select-all on focus; inline
autocomplete selecting the suggested suffix per keystroke; autofill; selection-state restore.
The morph also fires while the bubble is hidden, so it can pop up already speaker-shaped
from a selection made earlier in another app. Owner rule: **the main bubble is always a mic;
speaker behavior only via the speaker lobe, a clipboard copy, or the selection toolbar.**

### Changes

**Delete outright:**
- `FloatingBubbleService`: `speakModeText` (`:155`) and all writers (`:533`, `:545`, `:559`,
  `:1805`); `onTextSelected` (`:523-539`); `onSelectionCleared` (`:541-548`);
  `scheduleMorphRevert` + `MORPH_REVERT_MS` (`:554-563`, `:265`); the `speakModeText` case in
  `handleBubbleClick` (`:1366`); the `speakModeText != null → ic_speaker` arm of the IDLE
  render branch (`:2291-2297`) — IDLE icon becomes `isSpeakingNow ? ic_stop_speech : ic_mic`.
- `WhisperAccessibilityService`: `handleSelectionChanged` (`:140-184`) and its call site
  (`:270-272`); `setSelectionListener` plumbing (`:438-442` registration and the interface);
  `SELECTION_DEBOUNCE_MS` / `CLEAR_DEBOUNCE_MS` (`:1336-1337`).

**Preserve untouched:**
- The caret-tracking catch-all (`WhisperAccessibilityService.kt:290-307`) — it consumes the
  same event type (empty-range events); only the morph consumer dies. The event dispatch at
  `:267-309` keeps flowing into the focus/caret logic.
- Speaker lobe → `readClipboardAndSpeak` (`FloatingBubbleService.kt:570-588`, wired `:1128`);
  clipboard-copy summon + lobe pulse (`:451-481`, `:483-511`); `SpeakTextActivity`
  (PROCESS_TEXT); pause/resume during an active read (`:1358-1364`, driven by `isSpeakingNow`).

**Companion fixes (same theme: TTS must never hijack dictation):**
1. **Own-voice unification.** `TtsController` becomes the single authority for "our TTS is
   audible" (an active-playback flag covering every entry point, including
   `speakFromTrigger` from `SpeakTextActivity.kt:23`). `MediaSessionDetector`'s
   `selfAudioActive` check (`FloatingBubbleService.kt:352`, poll at
   `MediaSessionDetector.kt:126-164`) consults it. Today only service-driven TTS is covered,
   so a toolbar-triggered read gets classified as media → bubble summoned + "Tap bubble to
   transcribe audio" toast over its own voice (`:807-815`, `:930`), and a tap mid-read starts
   recording our own TTS.
2. **Preload relocation.** The `TtsController.preload` call at `:537` dies with
   `onTextSelected`. Re-home it in `onClipboardChanged` (`:451-481`) so a detected copy warms
   the model before a likely lobe tap.

### Error handling
Pure removal; no new failure modes. The own-voice flag must reset on TTS completion/error so
media detection isn't permanently suppressed (mirror how `isSpeakingNow` resets at `:651`).

### Testing
- Removal is verified by compilation + full JVM suite (no tests reference the deleted code —
  verified: zero test hits for `speakMode|onTextSelected|selectionChanged`).
- Own-voice fix: if the detector's decision seam is extractable as pure logic, add a JVM case
  "controller-level TTS active ⇒ self audio, not media"; otherwise document as owner check.
- Owner on-device checks: URL bar focus → stays mic; type with autocomplete → stays mic;
  selection-toolbar read-aloud → no transcribe prompt, tap during read doesn't record.

---

## Workstream 2 — Final-only commit (unified preview pipeline)

### Problem (verified)

Review request: text should reach the target field once, at stop — not in chunks. Today every
resolved segment (VAD pause, 15 s wall cap `:270/:1418-1422`, live server turn) injects
immediately via the single funnel `handleTranscriptionResult` →
`WhisperAccessibilityService.injectTextWithResult` (`FloatingBubbleService.kt:2231`), gated
on `sessionContext == TEXT_FIELD` (frozen at tap, `:1817`). Verified consequences of the
current design: per-segment clipboard clobbering in document/social strategies (11
`setPrimaryClip` sites, never restored — `clearPrimaryClip` count is zero); per-segment caret
grabs; and mid-session writes can land in a field focused after the tap when the session node
dies (`resolveInjectionTarget` fallback `:457-473`, root-focus paste `:1134-1138`).

**Critical verified fact:** for non-live TEXT_FIELD sessions the preview container is GONE all
session (`:1904-1906`) and the sink/accumulator pipeline is never created for any TEXT_FIELD
session (`:1876-1878` sits in the `!= TEXT_FIELD` branch). Live TEXT_FIELD sessions show only
the delta strip, cleared at each turn injection (`:1894-1903`, `:2201-2209`). So this feature
is *two* changes: suppress mid-session injection AND give TEXT_FIELD sessions the
accumulating window (which becomes the user's only live feedback).

### Behavior (owner decision: final-only is the ONLY behavior — no setting)

**During RECORDING and FINALIZING, for every session context:**
- `TranscriptSink` is created for every session; the preview container and accumulating
  `transcriptionEditText` are VISIBLE for every session (one pipeline — the current
  three-mode branching at `:1867-1906` collapses); the delta strip additionally renders when
  `sessionIsLive`. Existing autoscroll math (`:1884-1891`, `:1933-1938`) is size-relative and
  survives unchanged. FINALIZING shows "Finishing transcript…" for all sessions (`:2003-2006`
  loses its context gate).
- **No external writes.** `handleTranscriptionResult` appends to `sessionTranscript` + sink
  for all contexts and never calls `injectTextWithResult`; no clipboard writes; no caret
  pinning. FINALIZING counts as in-session — drain-released segments (`:2026-2042`) and
  orderer flushes (`:2042`, `:2151`) accumulate, never inject.
- The live-turn delta-clear special case (`:2201-2209`) is replaced by standard accumulate
  (turn text appends to the window; strip continues as the in-flight line).

**At stop (single delivery block in `stopRecording`, after drain + flush):**
- `TEXT_FIELD`: one `injectTextWithResult(fullTranscript)` **before** `endInjectionSession()`
  — teardown reordered (today `:2158` kills the binding before the `:2117` write) so the
  write resolves the **session-bound** target captured at `beginInjectionSession`
  (`WhisperAccessibilityService.kt:426-444`). Dead node → existing focused-field fallback;
  no target → clipboard once + existing toast. Document/social apps get their paste-based
  strategy exactly once. The full transcript is read from the sink file (the `:2105-2110`
  pattern), making TEXT_FIELD and non-TEXT_FIELD share one source.
- Non-TEXT_FIELD: semantics unchanged (clipboard copy `:2111-2112`, opportunistic inject
  into a finalize-time-focused field `:2117-2119` — that targeting is BY DESIGN here).
- Degraded TEXT_FIELD (`sessionClipboardFallback`): one consolidated copy (`:2126-2134`),
  now genuinely the only clipboard write of the session.
- The playback-capture stop trigger (`onTextFieldFocused` → `stopRecording`, `:769-775`)
  follows the same flow unchanged.
- Service teardown mid-session (`onDestroy`) routes through the same final-delivery block
  best-effort, so a killed service delivers what it accumulated.

**Spacing:** `TextJoin` now governs segment joins in the accumulator instead of per-segment
injection — the final string must read identically to what today's sequential injections
produce. `InjectionAnchor` plan/commit runs once per session.

**Dead code deleted** (verified zero callers; prevents resurrection):
`injectViaClipboardPreservingContent` (`WhisperAccessibilityService.kt:875`),
`injectViaClipboardForDocumentApp` (`:1032`), companion `injectText` (`:1431`),
`FloatingBubbleService.copyToClipboard` (`:2261`). (Note: `tryPasteOnAnyNode` and the other
strategies stay — the final write still needs them.)

**Untouched:** the `EmptyExpected`/`FallbackPolicy.reconcile` contract, `SegmentOrderer`,
`decideEngineChoice`/`sttLiveMode` (transport-level, orthogonal), disclosure texts (no
meaning change → no v3 bump; the known v4 landmine stays dormant).

### Error handling
- Final injection failure → clipboard fallback + toast (reuse the existing
  `InjectionResult` handling at `:2233-2258`).
- Async paste stragglers: the gesture-paste callbacks fire ~100 ms post-call
  (`WhisperAccessibilityService.kt:911-921`, `:996-1006`) — with exactly one write at stop
  there is no second write to race, but the implementation must not fire the final write
  twice (stop is idempotent; `stopRecording` re-entry already guarded by state).
- History persistence (`TranscriptStore`, `:2096-2101`) unchanged — the transcript survives
  even if delivery fails entirely.

### Testing
- New pure class `FinalDeliveryPolicy`: (sessionContext, target liveness, degraded flag,
  transcript emptiness) → delivery action (INJECT_SESSION_TARGET / INJECT_FOCUSED /
  CLIPBOARD_ONLY / NOTHING). JVM-test the full table, including the dead-node and
  no-speech rows.
- `TextJoin`: add accumulation-equivalence cases (N segments joined at once == joined
  incrementally).
- Concurrency (house rule): any test around drain/flush/delivery ordering uses a real
  background executor, not `SameThreadExecutorService`.
- Owner on-device: WhatsApp (SET_TEXT), Google Docs (document paste), a social app, URL
  bar; on-device mode + cloud live mode; mid-session mic↔device-audio switch; verify
  clipboard is touched at most once per session.

---

## Workstream 3 — Resizable transcript window + size-aware clamping

### Problem

Review request: resize the text window via a double-arrow handle at its top-right; size
persists. Verified: both transcript TextViews are hard-coded 280dp wide
(`floating_bubble.xml:28`, `:53`), `maxHeight=120dp` (`:30`), `maxLines=5` (`:56`); nothing
at runtime writes these. Window is WRAP_CONTENT, `Gravity.TOP|START` (`:1136-1145`);
`params.width/height` never assigned. The preview sits ABOVE the mic pill (vertical
LinearLayout `xml:2-8`), so wider → grows right, taller → pushes the pill down. **Latent bug
being fixed here:** every position clamp assumes the 56–64dp pill (`:885-894`, `:947-959`,
`:1007-1010`, `:1153`, `:1248`), and `bubblePositionX` defaults to 0.9 — the 280dp window
already hangs up to ~272dp off-screen right at the default position, and the two y-clamps
disagree on the navbar term (`:959` vs `:894`).

### Changes

**Handle.** New child view at the top-right of `transcription_preview_container`: 28dp touch
target, 45° double-arrow glyph, visible whenever the container is (the container only exists
during RECORDING/FINALIZING — resizing mid-dictation is the intended flow). Child-first
touch dispatch is verified: a handle consuming ACTION_DOWN cleanly beats the root drag
listener and suppresses the root's long-press-to-pin arming (`:1279-1286`).

**Drag semantics.** Horizontal drag sets width; vertical drag sets height. Because the window
is top-left anchored and grows down, vertical resize compensates `params.y -= Δheight` each
step so the **top edge follows the finger** and the pill stays put. Live-apply per move
event; clamp live; persist on ACTION_UP. Long-press on the handle resets to defaults
(280×120 dp) — insurance against wedging the window tiny.

**Size application.** New prefs `bubbleTextWidthDp` (default 280f) / `bubbleTextHeightDp`
(default 120f) via the plain-var `PreferencesManager` idiom (`:179-189` pattern; keys
`bubble_text_width_dp`, `bubble_text_height_dp`). Applied at `createBubbleView` and every
preview-show site: both TextViews' `layoutParams.width` = width·density;
`transcriptionEditText.setMaxHeight(height·density)`. Remove `android:maxHeight` from the
edit text in XML (runtime owns it; `maxHeight` only binds with wrap_content height, which is
preserved). Delta strip keeps its `maxLines=5`; its width follows the pref.

**Bounds.** Width ∈ [200dp, min(0.95·screenW, 560dp)]; height ∈ [80dp, 0.60·screenH].
Re-derived per `displayMetrics` at clamp time (rotation-safe).

**Clamp unification (the bundled fix).** `clampToBounds(x, y, viewW, viewH)` (`:1231-1236`)
becomes the only clamp. All show/restore/drag/rotation sites feed **real dimensions**:
measured `bubbleView.width/height` when laid out; otherwise an estimate = pill size, plus
(persisted preview size + paddings) when the preview is visible. Unify the navbar term into
the shared clamp. Re-clamp on every resize step and on preview show/hide (both are geometry
changes). `reclampAfterConfigChange` (`:1247-1265`) stops persisting position (`:1258-1264`
removed) — persisted fractions are written only by user drag (`:1320-1321`) and pin
(`:1188-1189`); restores are clamped anyway, so rotation can no longer silently rewrite the
saved position using transient preview-inflated dimensions.

**Known-minor, accepted:** handle hit-testing during the 200 ms entrance scale animation;
pill-width animation (`setBubbleWidth`) re-measuring the window concurrently with a resize
(different children of one WRAP_CONTENT window — extra re-measures, no conflict).

### Error handling
All sizes clamped at read time (a corrupt/stale pref can't produce an off-screen or
zero-size window); reset gesture recovers from anything else.

### Testing
- New pure class `ResizeMath`: (current dp size, drag Δpx, density, screen metrics) →
  (clamped dp size, Δy compensation, persist values). JVM tests: min/max at both ends,
  y-follow correctness, default-position 0.9 off-screen scenario resolving on-screen with
  real dims, rotation re-clamp, reset.
- Owner on-device: resize during dictation in WhatsApp; rotate mid-session; stop/restart
  service; size persists; long-press reset works; whole window stays on-screen at default
  position (the pre-existing clip is gone).

---

## Ship notes

- **3.4.0 / versionCode 74.** No new permissions, no FGS type changes, no Data Safety
  changes, no disclosure meaning change (v3 stays; the documented v4 engine-seam landmine
  remains dormant and untouched).
- Build/test per the house rules: `gradlew.bat :app:testDebugUnitTest --no-daemon`, install
  ONLY via `adb.exe install -r` (never `installDebug` — it wipes the owner's models).
- Release notes should credit the Play review (resize + final-only commit were asked for
  verbatim); reply to that review after rollout.
- The how-to guide (`HowToGuide.kt`, pinned by `HowToGuideTest`) mentions "transcribed and
  typed at the cursor" — the wording still holds under final-only commit (typed at stop),
  but review the guide's bubble section for the new resize handle and update the pinned test
  alongside any copy change.
