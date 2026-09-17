<!-- Filed from the 2026-09-17 root-cause workflow (35 agents: 3 investigators, 31 refuters, 1 synthesis). Device evidence: the owner's own re-run logcat showed the pack landing at 16:33:26, the bubble service starting at 16:33:43, the previewer library loading at 16:33:44.9 and session 1 at 16:33:51 - 8 s later, live words present. His failed first run was a tap inside the ~2.5-3.5 s post-toggle window this report describes. Owner ruling 2026-09-17: the 4.8.0/97 build stays as is; the fix below is for a later build on his word. -->

# First-session live words after a fresh install — root cause and fix options

Read-only investigation, 2026-09-17, against local main at 1619f05 (4.8.0 / versionCode 97, the build the owner sideloaded onto the Galaxy Tab S10+). No files edited, no gradle run.

## 1. The report

> "I wipe the app, cleared all the data, started over, ran through onboarding. After onboarding is completed I have to tap to download the [live-words preview] model, but the model isn't loading itself. I still have to do one dictation before the preview model actually takes effect."

Symptom: the FIRST dictation after the pack lands shows no live words; the SECOND does. Requirement: live words in the very first session after the install, on both the sideload (tap) route and the Play (silent) route.

## 2. Root cause

**The session gate is a one-shot synchronous snapshot of `isWarmFor()` taken in the same Main pass that posts the warm, and on a fresh install nothing has had a chance to warm the previewer before the first tap.**

Two facts combine:

### 2a. On a fresh install the previewer's first warm is the boot prewarm, and it is late by design

- Nothing in onboarding starts `FloatingBubbleService`. The only starters are Home's toggle (`HomeScreen.kt:239-244`), the Settings always-on restart (`SettingsScreen.kt:727-728`), the boot-notification extra (`MainActivity.kt:66-75`) and `BootReceiver` (which refuses when `bubbleEnabled` is false, i.e. always after a data wipe). `bubbleEnabled` defaults false (`PreferencesManager.kt:126`).
- Sideload: Play refuses the pack (`playRefused` latch), the Home card offers, the tap goes `PreviewAutoFetchController.start(TAP)` → `downloadFallback` → `StreamingPackManager.download` → `ours{}` notes `INSTALLED` (`PreviewAutoFetchController.kt:301-352`). Play: the pack lands silently at onboarding's language pick (AF5). **On both routes the pack is on disk before any service exists.**
- When the owner then toggles the bubble on, the install collector in `onCreate` (`FloatingBubbleService.kt:1574-1629`) explicitly `.drop(1)`s the board's replayed `{en: INSTALLED}` — the comment at 1578-1586 says the replay is left to the prewarm "to keep service startup/view inflation snappy". So the 4.5.1 "third warm trigger" (`warmOnPackInstalled`) never fires on the fresh-install path, by design.
- The boot prewarm (`FloatingBubbleService.kt:1418-1446`) is `refreshNpuTierOffer()` → `delay(1500)` → `warmLocalEngine().prewarm()` (whisper load posted to its executor) → `previewResidency(SERVICE_START)` → `warmStreamingPreview` → `StreamingPreviewEngine.warm` which only POSTS the sherpa load + canary to the single-thread `stream-preview` executor (`StreamingPreviewEngine.kt:274-342`; `warm = true` written at the very end). Earliest possible warmth: onCreate + 1.5 s + ~0.8-0.9 s load (Tab S10+ measurement, `docs/measurements/2026-09-10-tab-sherpa-rung3.md`) + canary — roughly 2.5-3.5 s after the toggle, longer while whisper's Q8 load runs beside it.

### 2b. The gate never re-checks

`startRecording` (`FloatingBubbleService.kt:4403-4475`):

```kotlin
val preview = if (residency is PreviewResidency.Warm) warmStreamingPreview(residency.pack) else streamingPreview
val previewReady = packToWarm != null && preview?.isWarmFor(packToWarm) == true   // :4428
val previewArmed = localPreviewArms(..., previewReady = previewReady)
sessionHasLocalPreview = previewArmed                                                 // :4453
val engine = if (previewArmed) PreviewTeeEngine(requireNotNull(preview), baseEngine).also { transcriptionEngine = it } else baseEngine
```

`warmStreamingPreview` posts the load and returns; `isWarmFor` is read in the same breath. `onOpen` (`:4590-4640`) only flips `engineReady`; nothing later re-reads the gate or re-points `transcriptionEngine`. The KDoc at `:3873-3876` states it outright: the wrap-site warm "arms NEXT session, not this one … a session started under a second after the service came up is exactly today's session, by design."

So: any tap that lands while the previewer's load is in flight loses the WHOLE session's live words, and the second session works only because the load posted at SERVICE_START (or at session 1's own SESSION_START) landed in the meantime. That is exactly "I have to do one dictation before the preview model takes effect."

### 2c. Why the owner reproduces it every time

- The bubble is tappable the instant the service starts (always-on mode on a fresh 4.8 install without the accessibility service, `FloatingBubbleService.kt:2056-2057`), and a tester who has just watched the download finish toggles the bubble and taps it. Also, Home's card says "Live words are on" and the strip says "English is ready" from the board record / `NoEngine` (H9) — the user is told to go, and goes.
- On the Tab the whisper prewarm (medium-q8 steered above 4.5 GB, or small-q8) is posted at the same instant as the sherpa load; the sherpa executor runs at `THREAD_PRIORITY_AUDIO` so it wins CPU, but the wall clock is still ≥ 2.3 s from the toggle.
- Whisper's connect in that first session is itself cold (its prewarm was posted 0.5-2 s earlier and its executor is FIFO), so `CONNECTING` lasts several seconds — during which the previewer's load finishes. The previewer is ready before the first word is transcribed; the gate just never asks again. **This is the observation that makes the fix cheap: on the reported path the previewer's warm is hidden behind whisper's own connect, and only the snapshot loses it.**

### 2d. What is NOT the cause (verified, see the refutation record)

Collector wiring, marker ordering, language mismatch, the switch default, canary/three-strike verdicts, R8 stripping, TRIM_MEMORY_UI_HIDDEN (a mic FGS process does not receive it), a cloud session, a stop/start between install and session 1, PACK_INSTALLED refused as busy. None of these fit "session 1 blank, session 2 words".

### 2e. Coverage gap

AF6 (`docs/superpowers/sdd/2026-09-02-431-guards-tts/acceptance.md:758-825`) was rewritten in 4.5.1 to expect first-session words, but its walk is scoped to "Do this row with the bubble RUNNING", it hands the bubble-not-running case to "the next bubble start arms it through the prewarm" and declares that not a failure, and its checkbox has been blank on 93-97. The fresh-install ordering (pack lands → bubble toggled → tap) was never a row, on either route. 4.5.1's verification was the JVM suite (`8cef544`: 221/2,777/0), which pins `warmOnPackInstalled` and `previewResidency` as pure functions — the wrap site's same-breath read is pinned as CORRECT by `LocalPreviewWiringPinTest`/`LocalPreviewGateTest` comments.

## 3. The executor fact the fix rests on

`StreamingPreviewEngine` runs everything on ONE single-thread FIFO executor:

- `warm()` posts load + canary (`:274-342`).
- `open()` posts stream creation and returns early on `recognizer == null` (`:344-355`).
- `sendAudio()` is non-blocking (ring write + `queue.offer`, capacity 128 chunks ≈ 4 s; overflow sheds the chunk and says so on the timing line).
- `commit()` freezes blank when `recognizer == null || stream == null` (`:364-370`).
- `drain()` clears the queue when there is no recognizer/stream (`:455-460`).
- `close()` / `release()` are safe on a cold engine.

`PreviewTeeEngine.connect` calls `preview.open(...)` and then `local.connect(...)` (`PreviewTeeEngine.kt:39-44`), and at the wrap site `warmStreamingPreview` runs BEFORE the tee is built. So **a warm posted at the wrap site is guaranteed to run before that session's `open()`**, and a tee built over a still-loading previewer is already safe at every entry point: audio is shed until the load lands, commits freeze blank, nothing throws. The typed transcript cannot be affected — the tee hands every chunk and every commit to `local` first, and `Relay` forwards `onOpen`/`onSegmentResolved`/`onError`/`onClosed` untouched.

The only thing a "still loading" previewer costs is the strip: `Relay` swallows `local`'s `onDelta` (the composer is the one delta source), so until the first partial the strip is blank rather than showing whisper's own bursts — the same look as the first ~0.5 s of any armed session today.

## 4. Fix options

### Option A — RECOMMENDED: arm on the POSTED warm, not the landed one ("the FIFO is the await")

**Change.** At the wrap site (`FloatingBubbleService.kt:4419-4428`) replace the readiness term:

```kotlin
// before
val previewReady = packToWarm != null && preview?.isWarmFor(packToWarm) == true
// after — the engine exists, a warm for THIS pack has been posted (warmStreamingPreview above, or
// earlier by any other member), and the pack's verdict is not against it. The engine's single
// FIFO executor orders that warm before this session's open(), so the tee is safe from its
// first chunk and the strip fills the moment the load lands.
val previewReady = packToWarm != null && preview != null && !preview.isDisabled(packToWarm)
```

Keep `localPreviewArms` and every other veto unchanged. Two companion edits:

1. `onOpen` (`:4641`): gate `livePreviewArmedOnce = true` on `previewArmed && preview.isWarmFor(packToWarm)` rather than `previewArmed` alone, so the "Live words are on" announcement still retires only when a word could actually have appeared (the flag's own KDoc claim). If the previewer is still loading at `onOpen`, the flag is written at the next session that opens warm — a one-session delay of a cosmetic retirement, never a wrong claim.
2. `StreamDiag.gateLine` (`StreamDiag.kt`) — add a `warm_now=` term (or rename `preview_ready=` to `preview_armable=`) so the "why no words?" grep still says whether the load had landed at the tap. Log-only.
3. KDoc: `warmStreamingPreview` (`:3873-3876`) and the `PreviewResidencyEvent` members' "reads isWarmFor() now" sentences (`:383-499`) are now false and must be rewritten; the acceptance sheet's AF6 gains the fresh-install row (see §6).

**Why it works on the reported path.** Toggle → tap at +2 s: SESSION_START's `warmStreamingPreview` posts (a no-op if SERVICE_START's is already queued), the tee is built, whisper's cold connect runs 2-10 s, the previewer's ~1 s load lands during it, `open()` runs behind it and finds the recognizer, the ring drains into the tee at `engineReady`, partials flow from the first replayed chunk. On a warm-whisper session (the rarer case, e.g. a second service start with the page cache hot) `engineReady` may precede the load by up to ~1 s: chunks queue (4 s capacity), `open()` clears the queue when it runs, so at most the first second of previewer audio is lost and words appear from the moment the load lands — still "live words in the first session".

**Files.**
- `app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt` (wrap site ~4419-4428; onOpen ~4641; KDoc 3873-3876 and the enum KDocs 383-499)
- `app/src/main/java/com/whispereverywhere/transcription/stream/StreamDiag.kt` (gate line term)
- `app/src/test/java/com/whispereverywhere/service/LocalPreviewWiringPinTest.kt` (needle for the wrap-site term; the "reads isWarmFor in the same breath" prose in the pins)
- `app/src/test/java/com/whispereverywhere/service/LocalPreviewGateTest.kt` (the AF6 sheet pin at ~918, the "same breath" comments)
- `app/src/test/java/com/whispereverywhere/transcription/stream/StreamingPreviewEngineTest.kt`, `PreviewTeeEngineTest.kt` (new tests, §5)
- `docs/superpowers/sdd/2026-09-02-431-guards-tts/acceptance.md` (AF6 fresh-install rows)

**Risk: low.**
- A session armed over a previewer whose load then FAILS or whose canary FAILS shows a blank strip for that one session (whisper's deltas are swallowed by `Relay`) — the identical shape to the already-accepted three-strike mid-session disable (`threeConsecutiveDecodeFailuresDisableThePreviewerAndBlankTheStrip`). The typed transcript is untouched by construction. This is a first-load-per-process event on a corrupt pack, which `markCorrupt` then withdraws; today that pack would have cost a session anyway.
- `isDisabled` is a `@Volatile` set read on Main; a verdict landing between the read and `open()` yields the same blank-strip session, never a crash.
- No change to which pack warms, when, or to any refusal: `previewPackToWarm` / `previewResidency` / `warmOnPackInstalled` are untouched, so the one-pack-per-process invariant, the busy refusal and the release rules are exactly 4.5.1's.
- The startup ring / endpointer statement ordering pinned by `StartupRingWiringPinTest`, `EndpointerLifecyclePinTest`, `BackpressureWiringPinTest` is untouched.

**Covers Play installs: yes** — the Play route lands the pack even earlier (during onboarding), so the first service start goes through the same drop(1)+prewarm path and the same wrap site.

### Option B — bounded await of the in-flight warm inside CONNECTING (candidate a)

**Change.** Give `StreamingPreviewEngine` a per-pack completion signal (e.g. `fun awaitWarm(pack, timeoutMs): Boolean` backed by a `CountDownLatch`/`CompletableDeferred` completed at the end of the warm task, in both the armed and the failed branch). Split `startRecording` so that everything from the wrap site down runs in `serviceScope.launch(Main)`: post the warm, `startAudioInput()` (so the ring captures from the tap), then `withTimeoutOrNull(3000) { preview.awaitWarm(pack) }`, then re-read `isWarmFor`, build the tee or not, then `engine.connect(...)`. Guard re-entrancy: abort if `currentState != CONNECTING` after the await (a stop tap or an error mid-wait).

**Files.** `StreamingPreviewEngine.kt` (latch), `FloatingBubbleService.kt` (startRecording split, ~4403-4590), `LocalPreviewWiringPinTest.kt`, `StartupRingWiringPinTest.kt` / `EndpointerLifecyclePinTest.kt` / `BackpressureWiringPinTest.kt` (the four-statement order now spans a suspension), `StreamingPreviewEngineTest.kt`.

**Risk: medium.** On a WARM whisper it delays `connect()` — and therefore `engineReady`, the "listening" haptic and the first typed words — by up to the previewer's load (~1 s) or the 3 s cap; on a cold whisper the wait is hidden but adds nothing over Option A. It moves `transcriptionEngine` assignment and `connect()` across a suspension point that three pin tests exist to keep un-suspended, and introduces a state-machine re-entrancy hazard (stop during the await, trim during the await, a second tap) that Option A does not have. Same outcome as A on the reported path with strictly more code.

**Covers Play installs: yes.**

### Option C — late arming: start unarmed, attach the tee when `onWarm` fires (candidate b)

**Change.** Always build the tee when a pack is selected and installed (even if cold), with `PreviewTeeEngine` taking a nullable/attachable preview; add `PreviewTeeEngine.attach(preview)` called from the service's `onWarm` hook on Main when a session is in CONNECTING/RECORDING for that pack; flip `sessionHasLocalPreview` and the strip rules mid-session; `Relay` swallows `local`'s deltas only once attached.

**Files.** `PreviewTeeEngine.kt`, `FloatingBubbleService.kt` (wrap site, onWarm hook at ~3935, strip rules), `PreviewTeeEngineTest.kt`, `LocalPreviewWiringPinTest.kt`, `InFlightStripWiringPinTest.kt`.

**Risk: medium-high.** It is Option A with the "attach" done by hand on Main instead of by the executor's FIFO order: a race between attach and the ring drain on the capture thread, a mid-session flip of `sessionHasLocalPreview` (four strip rules read it), and a tee that must be correct in two shapes. Nothing it achieves is unreachable by A.

**Covers Play installs: yes.**

### Option D — warm synchronously on the install thread before noting INSTALLED (candidate c): rejected

The engine is a private field of `FloatingBubbleService`, and on BOTH routes no service exists when the pack lands (sideload: the card tap happens before the toggle; Play: the pack lands during onboarding). Making the card's "ready" literally true would require an app-scoped engine (a second owner of the 169 MB recognizer, contradicting the one-owner design the service's release/trim rules rest on) or starting the FGS from the download's completion (a microphone-type FGS started from a possibly-backgrounded process on Android 14 → `ForegroundServiceStartNotAllowedException`; also needs overlay permission and `bubbleEnabled`). And it would not fix the fresh-install order at all: the service started LATER would still warm through the boot prewarm and the same snapshot would still miss. Does not cover the reported path.

### Option E — restart the bubble service after the install (the owner's suggestion): not needed, and it would not help

A restart re-runs `onCreate` → the same `refreshNpuTierOffer()` + `delay(1500)` + posted load, i.e. it re-opens the exact 2.5-3.5 s window, and tears down the overlay the user is looking at (the reason 4.5.1 rejected it, `f2e8892`). The Settings always-on toggle already does stop+start and would reproduce the miss today. With Option A the window no longer costs the session, so no restart is required on any route.

## 5. JVM tests that pin Option A

1. `StreamingPreviewEngineTest.openPostedBehindAnInFlightWarmCreatesTheStreamOnceTheWarmLands` — a latch-gated `ScriptedRecognizer` factory; call `warm()` then `open()` then `sendAudio()` while the load is held; release the latch; assert the stream exists, the queued audio is decoded, and a partial reaches `onPartial`. Pins the FIFO fact the fix rests on.
2. `StreamingPreviewEngineTest.openAndCommitBehindAWarmThatFailsAreSafeAndFreezeBlank` — factory throws / canary fails; `open()`, `sendAudio()`, `commit()` after it: no throw, `onFrozen(seq, "")`, `isDisabled(pack)` true, `onDisabled` handed the pack.
3. `StreamingPreviewEngineTest.audioThatArrivesBeforeOpenIsShedNotFedToTheNextStream` — chunks offered during the load are cleared by `open()` and never reach the recognizer as pre-session audio (bounded loss is by design, cross-session contamination is not).
4. `PreviewTeeEngineTest.aTeeWhosePreviewerIsStillLoadingForwardsLocalUntouchedAndTheStripStaysBlankUntilTheFirstPartial` — local receives every chunk, commit and connect in order; owner hears `onOpen`/`onSegmentResolved`; no `onDelta` until the preview's first partial.
5. `LocalPreviewWiringPinTest` — replace the wrap-site needle with the new term (`!preview.isDisabled(packToWarm)`), assert `isWarmFor` is no longer read at the wrap site (count in the `startRecording` body == 0), assert `warmStreamingPreview(residency.pack)` still precedes the tee construction in that body, and assert `onOpen`'s `livePreviewArmedOnce` write is gated on `isWarmFor`.
6. `LocalPreviewGateTest.aWarmInFlightForThisPackArmsTHISSession_theFreshInstallOrder` — a table over the three engine states (no engine / cold-with-warm-posted / warm) × disabled: only `preview == null`, `packToWarm == null` and `isDisabled` refuse. (The existing `everyOtherInputIsAVeto` keeps `previewReady=false` as a veto of `localPreviewArms`; what changes is which fact the wrap site feeds it.)
7. `LocalPreviewGateTest.theAcceptanceSheetsAF6NowAssertsTheOppositeAndStillShowsWhatItSaid` — extend the sheet pin to the new fresh-install sentences (§6).
8. `StreamDiagTest` — the gate line carries the `warm_now=` term and its value.

## 6. Acceptance rows to add (for the device walk that never happened)

AF6-fresh (sideload): wipe data → onboarding → Home → tap the card → wait for INSTALLED → toggle the bubble ON → tap the bubble **within 2 s** → speak. EXPECTED: live words in this first session (they may start a beat after the first syllable). FAIL: blank strip in session 1, words in session 2.
AF6-fresh (Play): same, without the card tap (the pack lands at language selection). Same expectation.
Both rows walked with the log open: the first `stream-gate:` line's timestamp against `stream-open: … loadMs= canary=` and `prewarm: ctx loaded`, and the new `warm_now=0` on the gate line is the proof the fix was exercised rather than missed.

## 7. Recommendation

Ship Option A. It is a one-term change at the site whose own KDoc names the defect, it changes nothing about which pack warms or when, it relies on an ordering the engine already guarantees (one FIFO executor: warm before open), it is safe at every engine entry point on a cold previewer, and on the reported path the previewer's load is hidden behind whisper's cold connect so the first session shows words from its first replayed chunk. Options B and C reach the same outcome with more code and new races; D cannot reach the reported path; E re-opens the window it is meant to close.

Owner-facing sentence: the model WAS loading itself — it was 1-2 seconds from ready when he tapped — but the app decided "no live words this session" at the instant of the tap and never looked again. The fix is to let the session use the previewer as soon as its load lands instead of deciding at the tap.
