# SDD Progress — Release 0: TTS diagnostics
Plan: docs/superpowers/plans/2026-07-27-tts-diagnostics-release-0.md
Branch: feature/multi-provider-cloud-stt-tts
Base commit: 249ceb6

## Environment (decided pre-flight, 2026-07-27)
- JAVA_HOME = "C:\Program Files\Android\Android Studio1\jbr" (JDK 21 — verified working)
- DEVICE NOW ONLINE (2026-07-27, mid-execution): SM-F956U (Galaxy Z Fold 6), Android 16,
  wireless debugging. NOTE: this is the same device class the RTF 0.577 bench was taken on,
  so gaps observed here are NOT a weak-hardware case.
- Installed com.whispereverywhere is the RELEASE build (not debuggable). Debug installs over it
  safely: app/build.gradle.kts:120-128 signs debug with the release key when keystore.properties
  is present (it is, at project root), so NO uninstall and NO data loss (model/voice/keys survive).
- Deferred instrumented steps (Task 1 Steps 3 & 6, Task 4 Step 9) can now RUN, after Task 4 lands.
  Do not run gradle concurrently with an active implementer subagent — file-lock conflict.
- Task 5 is an on-device measurement performed by the owner, not a subagent.

## Tasks
- [x] Task 1: complete (commits 95dc0fe..d9142a8, review clean — spec OK, quality approved)
- [x] Task 2: complete (commits d9142a8..f16346a, review clean — spec OK, quality approved)
- [x] Task 3: complete (commits f16346a..6f137bb, review clean — spec OK, quality approved)
      Reviewer raised one ⚠ (could not confirm per-test execution from console output).
      RESOLVED by controller: JUnit XML shows TtsDiagTest tests=8 skipped=0 failures=0 errors=0,
      all 8 named cases present. Authoritative full-suite rerun: 15 suites, 88 tests, 0 failures.
- [x] Task 4: complete (commits 6f137bb..7ce497d, review clean after one fix round)
      Review found 2 Important measurement-truth defects + 3 Minor; ONE fix subagent addressed
      all five (7ce497d); re-review Approved, no regressions.
        I1: diagT0 included the ~2s cold model load -> inflated ttfwMs/wallMs, deflated dutyPct.
        I2: end record fired on pre-anchor early exits, reading stale @Volatile availableSamples
            -> fabricated a perfect gapless utterance in the capture.
      Cumulative TtsEngine diff: 116 insertions, 0 deletions. Behaviour guard clean.

## Minor findings for the final whole-branch review
- Task 1: TtsPlaybackThreadTest never exercises the throw path it guards (would have passed
  before the fix too). Disclosed by both brief and report; mocking the sherpa JNI layer was
  out of scope. Verification-strength gap, not a code defect.
- Task 1: both tests use wall-clock Thread.sleep (500/1500 ms) rather than a deterministic
  settle signal. Specified verbatim by the brief.
- Task 2: TtsDiagMath.percentile rounds p*(n-1) rather than textbook 1-indexed ceil(p*n)
  nearest-rank. Mandated verbatim by the brief and correct for all tested values; flagged
  only if percentile is reused outside the diagnostics summary.
- Task 4 residual (cosmetic, non-blocking): `val slice = localTrack.sampleRate / 10` still sits
  above the try/finally that releases the track. newTrack() genuinely cannot move inside (finally
  needs localTrack in scope); slice could have. Same class as Minor 5, pre-dates it.
## INCIDENT 2026-07-27 — app uninstalled, user data destroyed
Running `connectedDebugAndroidTest` against a Play-installed release build caused Gradle's
AndroidTestApkInstallerPlugin to uninstall the package, then fail with
INSTALL_FAILED_UPDATE_INCOMPATIBLE — leaving neither build installed.
LOST: whisper model, Kokoro voice, transcript history (unrecoverable), prefs.
CAUSE: assumed keystore.properties + the build.gradle comment meant debug installs over release.
False when the release came from Play — Play App Signing re-signs with Google's key.
GUARD ADDED: mandatory signature preflight in the plan (commit 4e89479).
RECOVERY: debug APK installed directly via `adb install` (installer=null, signatures now match).

## Device state
- Debug (instrumented) build installed, 51.7 MB, locally signed.
- connectedDebugAndroidTest RUNS CLEANLY: 2 tests, both SKIPPED via assumeTrue (no voice model).
  Harness verified; tests will execute once the Kokoro voice is re-downloaded.
- SECOND GOTCHA (2026-07-27): connectedAndroidTest UNINSTALLS the app as teardown. An adb install
  followed by an instrumented-test run leaves no app — looks like a failed install. Correct order:
  instrumented tests FIRST (self-install, self-clean), THEN install for the capture. Documented in
  the plan alongside the signature preflight.
- Debug build REINSTALLED and verified present with a launcher entry (com.whispereverywhere/.MainActivity).
- BLOCKED ON USER: re-download whisper model + Kokoro voice, re-grant permissions, then capture.
  Do NOT run connectedDebugAndroidTest again before the capture is saved.


## RELEASE 0 COMPLETE — 2026-07-27
Final whole-branch review returned "Needs work" narrowly; ONE fix subagent closed all four
merge-gate items (a32d59a), controller verified each in the live code.

  I1 dutyPct printed 100 on the same line reporting a 9 s gap (upper clamp hid a 147% ratio).
     Fixed: clamp dropped so >100 self-identifies; playedMs= added; KDoc corrected.
  I2 RTF summary was unweighted and HAD ALREADY PUT A WRONG NUMBER IN THE SPEC. Controller
     claimed "~7% worse than bench" from rtfP50=0.62 (unweighted median) vs 0.577 (weighted
     aggregate). Recomputed: 56206/96357 = 0.583, a 1.1% difference. Spec corrected (a67a2da);
     rtfAgg= now emitted so the comparison cannot be got wrong again.
  I3 hwUnder was blind to HAL-side starvation outside an observed producer stall — would have
     read clean on the "after" capture once the producer stall is fixed. Now read once at loop
     exit in the playback thread's finally, before release(), covering every exit path.
  T5 TtsPlaybackThreadTest KDoc overclaimed "ANY exit path". Corrected.

FINAL STATE: 15 suites / 92 tests / 0 failures. Behaviour guard clean.
git diff -w 513f556..HEAD -- TtsEngine.kt = 153 insertions, 0 deletions (no existing line altered).

## OPEN BEFORE MERGE
- T6: TtsPlaybackThreadTest has NEVER EXECUTED — it has only ever SKIPPED via assumeTrue (no
  voice model at the time). Its assertions are unverified. Running it requires
  connectedAndroidTest, which UNINSTALLS the app and would wipe the user's re-downloaded
  190 MB whisper model + 325 MB Kokoro voice for the third time today. DEFERRED by controller
  pending the user's decision; recorded here as unverified rather than implied working.
- Carried Minors accepted for now (reviewer-triaged): wall-clock Thread.sleep in the device
  test (fails safe); percentile rounding convention (M4 supersedes — rtfP95 duplicates rtfMax
  at n<=11); `val slice` above the try/finally (cannot realistically throw).
- Reviewer's M1-M12 / T1-T4 remain unaddressed and are documented in its report; none are
  merge gates.


# ===== RELEASE B — Credentials + Toolchain =====
Plan: docs/superpowers/plans/2026-07-27-release-b-credentials-and-toolchain.md
Base commit: 993c3f9   Baseline: 15 suites / 92 tests / 0 failures

Pre-flight scan found 3 plan defects, all fixed before dispatch (993c3f9):
  - android.util.Base64 under unitTests.isReturnDefaultValues=true would return null,
    making encode() emit "1:null:null". Switched to java.util.Base64 (API 26 = minSdk).
  - Stale 88-test baselines (Release 0 ended at 92).
  - A literal NUL byte embedded in a Kotlin string literal in a Task 2 test case.

- [x] Task 1: complete (993c3f9..1acaa61, review clean). Exactly 3 version lines, NO source
      changes required. assembleDebug + assembleRelease (R8 + lintVital) both green, 92/92 tests.
      Review verified: no second source of truth for the versions (no version catalog, no
      buildSrc, no settings.gradle pins); CI uses JDK 17 which AGP 8.13.2 supports; CXX5304 is
      informational schema skew, not an NDK mismatch.
      CORRECTION to controller's brief: it claimed a `specialUse` FGS type. The manifest declares
      only `microphone|mediaProjection` (AndroidManifest.xml:108), both permission-backed. No
      specialUse exists anywhere. Neither type is invalidated by Android 16.
      OPEN: runtime smoke test on Android 16 — no build can catch FGS behaviour changes.
- [ ] Task 2: SecureStoreCodec (pure, 7 JVM tests)
- [ ] Task 3: SecureStore (Keystore AES-256-GCM, throws not degrades)
- [ ] Task 4: migrate PreferencesManager + PURGE legacy plaintext files
- [ ] Task 5: backup rules deny-one -> explicit allowlist


# ===== MODEL CATALOG TRIM + RELEASE B (Tasks 2-5) — COMPLETE =====
Plan: docs/superpowers/plans/2026-07-27-model-catalog-trim.md
      docs/superpowers/plans/2026-07-27-release-b-credentials-and-toolchain.md

- [x] Catalog T1 cb519a7  base tier added, extreme/ultra RETIRED (not deleted), default -> eco
- [x] Catalog T2 d69af09  ModelMigration pure state machine
- [x] Catalog T3 8f8c251  pickers read pickable; migration wired
- [x] Rel-B  T2 483bf52  SecureStoreCodec (java.util.Base64, versioned framing)
- [x] Rel-B  T3 6ee127d  SecureStore (Keystore AES-256-GCM, throws not degrades)
- [x] Rel-B  T4 160168a  PreferencesManager migrated + legacy plaintext purge
- [x] Rel-B  T5 fdc1d3e  backup rules -> allowlist

FINAL: 17 suites / 117 tests / 0 failures. assembleDebug + assembleRelease both green.

## 8-lens adversarial audit -> DO NOT MERGE -> 5 defects fixed
MF1 55538ea  CONTROLLER'S OWN DEFECT, from the Release B plan. The allowlist kept its old
             <exclude> lines; with an <include> present those name paths outside the scope, which
             is a FATAL FullBackupContent lint error. 9 errors, lintVitalRelease FAILED, NO release
             APK/AAB could be produced. Debug + all 116 tests stayed green — the same release-only
             shape as b19233c. Fix was to DELETE the excludes, not widen the include (widening
             restores the denylist that made the key Drive-eligible). Comments now say so.
MF2 39dba9f  Migration button had no re-entrancy guard. Double tap -> coroutine B's dest.delete()
             unlinks the freshly-verified Eco after A switched to it -> installedModel() null ->
             app gate -> onboarding with NO back nav, old 574 MB model already gone.
MF3 39dba9f  THE ONE THAT MATTERED MOST. Migration sent EVERYONE to eco (ENGLISH-only), including
             ultra users (MULTILINGUAL). A non-English user tapped "works well for everyday
             dictation", silently became English-only, and their multilingual model was deleted.
             This branch had ADDED the correct `base` multilingual target and never used it.
MF4 39dba9f  Settings still advertised "Eco / Pro / Extreme / Multilingual / Ultra" one row below
             the card saying two of those are retired. Now derived from pickable so it cannot drift.
CC1 39dba9f  runCatching swallowed CancellationException -> raw framework string toasted on exit.
CC3 39dba9f + 02ab328  Purge marked itself done even when the unlink failed. Controller hardened
             the follow-up: the absence check relied on the shared_prefs layout and failed in the
             DANGEROUS direction if that model were wrong (flag set, plaintext key kept forever).
             Now confirms the directory first; "cannot confirm" == "may still be there".

## CARRIED (not merge gates)
- CC2 retired model files unreclaimable if the user leaves the tier via the picker, not the card.
- CC4 SecureStore hardening (unsynchronised check-then-generate on the Keystore key, etc.) —
      genuinely latent: ZERO production call sites today. Fix alongside the first caller in Rel C.
- CC5 test gaps: decode_never_throws_on_arbitrary_input asserts nothing.
- CC6 migration card's offline state never un-sticks (no NetworkCallback).
- CC7 published docs/listing copy stale (pre-existing) — fix before the next Console rollout.
- ALL: no instrumented test has been RUN on device (would uninstall and destroy 500+ MB of models).

## OPEN BEFORE MERGE
- On-device smoke test of the migration flow (Task 4 of the catalog plan) — never executed.
- TtsPlaybackThreadTest still has never executed (assumeTrue-skipped).


## ON-DEVICE VALIDATION 2026-07-27 (SM-F956U, Android 16, build 21:12)
Owner-verified on the real device:
- Picker shows only pickable tiers; Extreme and Ultra are gone.
- NEW base multilingual tier downloaded, sha256+size verified, selected, and used to transcribe.
  ggml-base-q5_1.bin on disk = 59,707,625 bytes, EXACTLY the pinned approxBytes.
  This is hard proof BOTH pinned values are right: the download path enforces a +/-5% size gate
  AND a streaming sha256, deleting the file on failure. A wrong digest would have left nothing.
  Previously these values were only cross-validated against eco's existing pins.
- Owner reports transcription with the multilingual model is fast. Latency complaint addressed.
- legacy_credential_stores_purged_v1 = true in prefs: the purge RAN on launch and correctly
  confirmed absence. NOTE: this exercised only the ABSENCE branch — no legacy file existed on this
  device (fresh install after the earlier data loss). The DELETION branch remains unverified
  anywhere, and that is the case that actually matters for upgrading users.

STILL UNPROVEN ANYWHERE (do not claim these work):
- The retired-tier migration flow (owner is on a live tier, so it is inert for them).
- SecureStore's actual encryption — zero production callers, never executed.
- The purge's deletion branch.
- Every instrumented test (assumeTrue-skipped or never run; running them uninstalls the app).


# ===== RELEASE C1 — Provider Foundation =====
Plan: docs/superpowers/plans/2026-07-27-release-c1-provider-foundation.md
Base: 9bf55af (main, post-merge)   Baseline: 17 suites / 117 tests / 0 failures

Release C decomposed into C1 (this) / C2 cloud STT / C3 cloud TTS + chunker / C4 streaming.
C1 ships: add a provider key, verify it against the real API, store it encrypted, with the
Play-required disclosure. NO audio leaves the device in C1.

Pre-flight found 2 defects in the plan's Task 3, fixed before dispatch (9bf55af):
  - lock/cache shown inside a "replace the body of secretKey()" block, but they are CLASS fields;
    following it literally would not compile.
  - caching a SecretKey converts a recoverable Keystore invalidation (screen lock removed,
    biometrics re-enrolled) into a PERMANENT process-lifetime failure of the credential store.
    Added invalidateCachedKey() + Step 2b requiring it on every crypto failure path.

- [ ] C1 T1: OkHttp 5.4.0 + HttpTransport seam + hand-rolled Call.await()
- [ ] C1 T2: ProviderCatalog (pure)
- [ ] C1 T3: ProviderAccounts + SecureStore hardening (its FIRST production caller)
- [ ] C1 T4: KeyValidator (six-case KeyStatus; 429 quota-vs-ratelimit split)
- [ ] C1 T5: Cloud Providers screen + disclosure + privacy policy

NOTE: user has real API keys and will enter them THEMSELVES at T5 Step 7. Never ask for, handle,
log, or store the user's keys in this session.


## C1 COMPLETE + INSTRUMENTED EVIDENCE FINALLY OBTAINED (2026-07-28)
Commits: 467f429 (T1 OkHttp) .. 95ac9f5 (audit fixes) .. d6068f6 (cancellation fix)
JVM: 20 suites / 166 tests / 0 failures. assembleDebug + assembleRelease green.

8-lens audit returned BLOCK on 6 defects; all fixed in 95ac9f5:
  M1 CRITICAL. A non-ASCII char in a pasted key (U+200B from a web page — category Cf, so
     .trim() does NOT strip it) reached OkHttp's Headers.checkValue, which throws IAE OUTSIDE
     the transport's try and is not an IOException -> uncaught crash. Worse: OkHttp redacts that
     message only for Authorization/Cookie/Proxy-Authorization/Set-Cookie, so for xi-api-key and
     x-goog-api-key the PLAINTEXT KEY went into the crash trace -> Play Console crash reports.
  M2 CRITICAL. PasswordVisualTransformation masks only what Compose PAINTS. With no
     KeyboardOptions the IME saw autocorrecting text: the key appeared in Gboard's suggestion
     strip above the masked field and could enter the personal dictionary.
  M3 CRITICAL. No FLAG_SECURE on the only screen that can render a credential.
  M4 The policy + modal claimed audio is sent TODAY, contradicting Data Safety "nothing shared" —
     a reviewer diffing them finds the policy claiming MORE sharing than declared.
  M5 Gemini answers a bad key with HTTP 400 API_KEY_INVALID, not 401 -> fell to Unknown -> the UI
     offered "Save anyway" and persisted it. Gemini validation could NEVER say invalid. Mirror
     image: 403 (region-blocked / scope-restricted VALID key) was told "check you copied it".
  M6 Credential encryption had never executed anywhere.

CONTROLLER-FOUND REGRESSION in the M1 fix, fixed in d6068f6:
  Widening to catch(Exception) also swallowed CancellationException (it extends
  IllegalStateException -> RuntimeException -> Exception). Navigating away mid-validation was
  reported as a network failure, the coroutine never unwound, and it set state on a dead scope.
  Now rethrown, ordered before the broad catch.

INSTRUMENTED TESTS RUN AT LAST — via `adb shell am instrument` against an already-installed
build, which does NOT uninstall (only Gradle's connectedAndroidTest task does the teardown that
destroyed the user's models twice). Models verified intact before and after.
  SecureStoreInstrumentedTest        6 tests OK  <- FIRST EVER execution of the Keystore crypto,
                                                    including stored_blob_does_not_contain_the_plaintext
  ProviderAccountsInstrumentedTest   7 tests OK
  TtsPlaybackThreadTest              2 tests OK  <- resolves Release 0's carried T6; it had only
                                                    ever SKIPPED. 7.7s runtime + Kokoro voice
                                                    present confirms real execution, not assumeTrue.
=> M6 RESOLVED and Release 0 T6 RESOLVED.

REMAINING, USER-ONLY: real-key verification of the three validationUrls (never confirmed against
live docs). See userTestNeeded 1-10 in the audit output.


## C1 VALIDATED WITH REAL KEYS — 2026-07-28 (owner, SM-F956U)
All three providers verified end to end: OpenAI OK, Gemini OK, ElevenLabs OK after 64234bc.

THE FIELD FINDING that only a real key could produce:
  ElevenLabs rejected a VALID key. Cause was the controller's endpoint choice, not the key.
  ElevenLabs is the only one of the three with per-endpoint API-key restrictions; /v1/user needs
  `user_read`, which a key scoped for speech work does not have -> 401 on a good key.
  Probed 2026-07-28:  /v1/user   no key 401, bad key 401
                      /v1/voices no key 200, bad key 401  <- validates at lower privilege
  Switched to /v1/voices (also what the C3 voice picker will need). Copy fixed too — it had been
  blaming the user's paste for what was a scoping issue. 3 regression tests added.

  This is exactly the risk recorded in the C1 plan: the three validationUrls were never confirmed
  against live docs, and the plan said a rejection must be treated as a finding rather than worked
  around. Two of three were right; this one was not.

C1 FINAL: 20 suites / 169 tests / 0 failures. assembleDebug + assembleRelease green.
Instrumented: SecureStore 6 OK, ProviderAccounts 7 OK, TtsPlaybackThread 2 OK (via am instrument).
Device: secure_store.xml present — OpenAI + Gemini + ElevenLabs keys stored encrypted, survived
reinstall. Models intact throughout.

STILL OPEN (carried, not blockers):
- 18 canCarry items from the C1 audit, incl. main-thread Keystore work on first save (user test 5
  would measure it), SecureStore self-healing on a corrupt alias, and codec IV-length require().
- docs/privacy.html and the in-app policy are two hand-synced copies with no build-time check.
- docs/PLAY-DECLARATIONS.md §5 now slightly stale re: key+IP sent on verify.
- Remaining userTestNeeded items 5-10 (latency, Settings jank, disclosure end-to-end, offline,
  remove/re-add, on-device regression sweep).


# ===== RELEASE A — segment identity + ordering (2026-07-28) =====
Commits 357275b..449c31d. 205 unit tests / 0 failures. assembleDebug + assembleRelease green.

8-lens audit returned DO NOT MERGE on THREE user-visible riders — all three were CONTROLLER
design errors written into the plan, not implementer mistakes. Stripped in 449c31d:

B1  A failed local transcription emitted SegmentOutcome.Lost, which the orderer renders as "[…]"
    and the service injects into the user's field AND 14-day history. Pre-release those paths
    called onError(), which the service swallows mid-RECORDING — nothing typed. Now
    EmptyExpected + onError restored: byte-identical behaviour, seq still resolves exactly once.
B2  SegmentQuality rejected ORDINARY SPEECH. Controller reproduced independently with zlib level 6:
      "no no no no no no no no no no no no"  -> 2.69  REJECTED
      "The quick brown fox jumps..."          -> 0.86  accepted
      "thank you for watching " x40           -> 23.00 (the actual target)
    Gate was 2.4 while its target scores 23.0 — set ~10x below its target, inside the range of real
    speech. Root cause: transplanted whisper.cpp's threshold without its context (whisper applies it
    to a decoder segment with token history, not a short cleaned utterance string). Rejection was
    SILENT and, on a single-segment session, produced "No speech detected — try speaking louder"
    for clearly-spoken audio. Class kept in tree, fully UNWIRED, with recalibration notes
    (~4.0 gate, ~120 min chars) and the measured false positives to become ACCEPT tests.
B3  Adaptive silence floor fed on the USER'S OWN VOICE: the EMA update sat ABOVE the
    `if (!hasSpoken) return false` guard, so it sampled sub-500 inter-syllable amplitude rather
    than room tone — talking raised the bar for detecting that the user stopped. reset() also
    zeroed it every commit, so "converges over ~2 s" described nothing the code did. Reverted
    entirely; the 251-499 dead band is now a DOCUMENTED known limitation with a note on why the
    naive fix fails.

B4  FOURTH regression, caught by the fix implementer, also a controller plan error:
    the plan specified splitting blank results by AudioMath.peak < 0.005 into EmptyExpected vs
    EmptyUnexpected. WRONG for local: a blank from local means Silero VAD already decided there was
    nothing, and VAD is far stricter than amplitude — the ~800 ms of room tone between the last VAD
    commit and the user's stop tap is "no speech" to VAD while its PEAK is several times 0.005.
    That rule would have stamped "[…]" on the end of essentially EVERY dictation session.
    Local now always uses EmptyExpected for blanks; the peak split belongs to an engine with no VAD.

WHAT SURVIVED (audit-verified sound, this is what the release exists for):
  - commit(): Long with seq allocated INSIDE bufferLock (also fixes the pre-existing enqueue race)
  - every seq reaches onSegmentResolved exactly once, on every path incl. exceptions
  - SegmentOrderer strict in-order release; provable pass-through at local's single-thread executor
  - the four lifecycle methods lifted onto TranscriptionEngine; all downcasts gone
  - awaitIdle still fences before close() detaches the listener

ON-DEVICE SWEEP: PASSED (owner, 2026-07-28). Dictation, device-audio capture, record/stop cycles
and read-aloud all behave exactly as before. None of the three stripped regressions reappeared —
no "[…]" markers, no silently dropped words, no mid-sentence cuts. The orderer's pass-through
property is now confirmed in the field, not just argued from code.
Delivered via a temporary GitHub prerelease (repo had gone private mid-session, so the phone needed
an authenticated session); release deleted after install as agreed.

=> RELEASE A COMPLETE. C2's blocker is cleared: concurrent cloud results can now be ordered safely.


## OPEN ITEM — GPLv3 source pointer (owner decision 2026-07-28)
Repo went PRIVATE mid-session. The live Play listing (docs/PLAY-LISTING.md:65) and the in-app
licenses screen (oss_licenses.html:40) both point users at the repo URL for source, so those are
currently dead links for anyone who installs from Play. Owner's decision: keep private while dev
APKs are being published for testing, make public again once C2 testing is done. Revisit then.

## NEXT: Release C2 decomposed
C2a  cloud STT on OpenAI + local fallback + the compliance flip  <- planned, not started
C2b  Gemini + ElevenLabs adapters
C2c  graduated degradation UX, spend tracking, rate gate, coalescing, spill
C4   WebSocket streaming


# ===== BATCH TRANSCRIPTION MODE =====
Plan: docs/superpowers/plans/2026-07-29-batch-transcription-mode.md
Base: 067e279 (main)   Baseline: 339 tests / 0 failures, both variants green

RESCOPED 2026-07-29 by owner ruling: file picker only, no in-app recording, no saved-recordings
library, no retention, no capture path (BatchRouting cut). See plan header.

Tasks 1-7 (bf8b687..c3485a5): RecordingStore transient decode workspace, decode pipeline
(SampleMath/PcmSink/StorageGuard/AudioDecoder), SilenceScanner+ChunkPlanner, pure consent/cost
gates, BatchTranscriber (sequential/checkpointed/resumable), foreground BatchTranscriptionService,
BatchTranscribeScreen (SAF picker -> engine choice -> progress -> transcript).

## Task 8: Compliance — one clause in four documents, two NO-CHANGE determinations (2026-07-30)
Files: app/src/main/assets/privacy_policy.html + docs/privacy.html (§6);
       app/src/main/assets/terms_of_service.html + docs/terms.html (§3).

Privacy §6: "...a cloud provider you have selected only ever receives audio you dictate yourself
through the microphone" extended with ", or an audio file you explicitly choose to transcribe with
that provider." MediaProjection carve-out sentence left untouched (absolute; batch has no capture
path). Last-Updated bumped July 28 -> July 30, 2026 in both privacy copies.

ToS §3: "Your dictated audio is sent to a third party only if..." extended to "Your dictated audio,
or an audio file you choose to transcribe, is sent to a third party only if...". Last-Updated
bumped July 29 -> July 30, 2026 in both ToS copies.

Verified asset<->docs pairs content-identical: `diff --strip-trailing-cr` privacy_policy.html vs
privacy.html = empty (exit 0); terms_of_service.html vs terms.html = empty (exit 0).

DETERMINATION 1 — No Data Safety flip. The user-initiated audio upload to a user-keyed provider
was already declared in C2a; a picked file rides the identical, identically-gated transmission
(stored key AND explicit provider selection AND accepted disclosure v2, per BatchCloudGate).
Transient cache processing of a user-selected file is not new collection — recorded here as a
NO-CHANGE determination, not silently assumed.

DETERMINATION 2 — No disclosure v2->v3 bump. The consent dialog's meaning is unchanged: same
triad, same provider, same class of user-directed audio. The per-job engine row in
BatchTranscribeScreen, where the user pushes a NAMED file at a NAMED provider, is the explicit
per-file consent surface for batch. Contrast MF3 above (Model Catalog Trim), where the meaning of
an existing flow DID silently change and a version bump was mandatory there — this case is
distinguished from that one deliberately, not by default.


# ===== RELEASE COMPLIANCE PACKAGE — 3.3.0 (2026-07-31) =====
Docs-only wave on `main`. Three files refreshed for the 3.3.0 rollout, one commit each:
  - docs/PLAY-LISTING.md      — DRAFT variants refreshed (LIVE 3.2.0 copy byte-untouched)
  - docs/PLAY-DECLARATIONS.md — new §7 "3.3.0 Console checklist"
  - this ledger               — carried items + live-key checklists + acceptance warning
Release base 15eb9b0 (3.2.0). AAB target: versionCode 73 / versionName 3.3.0.
No app code touched; no new deps; OkHttp pinned 4.12.0. Post-audit suite: 687 tests / 0 failures
(release-fix-report.md), both variants green.

## Document-only carried items (no code — deferred, tracked here)
From the carried-minors sweep (sweep-report.md) and the release audit (release-fix-report.md):
- Batch checkpoint-after-upload re-bill on process death: a job killed after a cloud POST but before
  the per-chunk checkpoint re-POSTs (re-bills) that ONE chunk on resume. Bounded to a single chunk;
  a durable per-chunk in-flight marker is the real fix. DEFERRED.
- C4 too-short-turn buffer bleed: a sub-minimum committed turn can leave residual PCM in the live
  mirror buffer (its already-streamed appends prepend to the next turn). Observed benign; needs live
  evidence to characterize. DEFERRED (scope note, c4-fix-report.md).
- C4 committed-never-completed protocol stall: a Realtime turn committed but never returned by the
  server relies on the socket-drop/Lost path; no explicit per-turn deadline yet. DEFERRED.
- Gemini thinkingConfig: whether to pin it off for transcription pends live evidence it affects
  verbatim output/cost. DEFERRED.
- Clause-splitter CJK degradation: the splitter is tuned for Latin clause punctuation; Kokoro is
  English-only, so CJK read-aloud is out of scope this wave. DEFERRED.
- LIVE listing copy + DRAFT reconciliation: the LIVE 3.2.0 storefront copy is false once 3.3.0 ships
  (report-only in the audit — LIVE is off-limits; the fix is a Console action). Owner picks a DRAFT
  variant and edits BOTH the listing and the Data Safety form in the SAME rollout as the AAB.
  RELEASE BLOCKER — tracked in PLAY-DECLARATIONS.md §7.
- GPLv3 source pointer: repo is private; the storefront + in-app licenses screen link a now-dead repo
  URL. Owner makes the repo public again with the rollout (PLAY-DECLARATIONS.md §7, manual step 2).

## LIVE-KEY CHECKLIST — Soniox (owner, real key; NEVER handled/logged/stored in-session)
Soniox is code-complete + unit/compile verified (soniox-fix-report.md: 665 tests / 0 failures at
HEAD e598ced, assembleRelease green) but has NEVER run against a live Soniox key. Its endpoint /
response shapes carry the same real-key risk that flipped ElevenLabs from /v1/user to /v1/voices in
C1. Run these six on-device, in order, before trusting the provider:
  1. Add a real Soniox key in Cloud Providers -> confirm KeyValidator accepts it against
     api.soniox.com. A rejected VALID key is a FINDING about the validation endpoint, not the key —
     do not work around it; fix the URL/scope (C1 precedent).
  2. SHIP-BLOCKER: confirm `POST /v1/files` returns a readable, non-blank top-level `id`. If Soniox
     nests/renames it, fix `SonioxFile` BEFORE real audio flows — an unreadable id leaves audio
     stored server-side that can never be deleted (leakedUpload/leakedCreate fall Transient + log a
     status-shape-only WE-DIAG line, by design).
  3. Select Soniox as the STT engine, dictate, confirm the transcript injects into the field; then
     force a failure (bad key / airplane mode mid-call) and confirm fallback to the on-device model.
  4. Confirm delete-after cleanup on the happy path AND on cancel-mid-poll: both `/transcriptions/{id}`
     and `/files/{id}` are DELETEd after each job (the "deleted right after each transcription"
     privacy claim rests on this; the NonCancellable finally is unit-pinned but never live-verified).
  5. Confirm Soniox's multilingual strength on a non-English utterance (the reason it was added).
  6. Confirm the clamps hold with Soniox selected: batch stays OpenAI-only (`resolveBatchSttProvider`
     -> null for Soniox) and the live word-for-word mode stays CLOUD_WITH_FALLBACK (Soniox has no
     live path). Neither should light up for Soniox.

## LIVE-KEY CHECKLIST — C4 live transcribe (OpenAI Realtime) — STILL UNRUN
C4 word-for-word streaming passed unit + compile only (c4-gate-report.md 592 tests; c4-fix-report.md
608 after 8 root-cause fixes). No live real-key WebSocket session has been run. On-device:
  1. Real OpenAI key present; select "Cloud word-for-word (OpenAI)"; confirm the selector shows the
     ~$0.017/min price note and the "word-for-word as you speak" caption (no speed claim).
  2. Confirm the WebSocket handshake to gpt-live-transcribe succeeds (Authorization: Bearer; key
     never in a URL or log — logs carry code/kind/length only).
  3. Dictate: partial deltas render in the preview strip ONLY and are NEVER injected; final text
     injects exactly once per turn (onSegmentResolved -> deliverReleasedText).
  4. Force a drop mid-turn (airplane toggle): the outstanding turn resolves Lost and the session
     rides the local fallback; the reconnect ceiling (6) latches to local, not a loop.
  5. Two short back-to-back turns must not swap transcripts (commit-ack binding); a sub-minimum
     "cough" turn resolves without poisoning the next.

## ⚠️ FRESH-INSTALL FIRST-RUN TEST — MUST BE THE LAST STEP OF ACCEPTANCE
A genuine fresh-install / first-run pass (onboarding, model download, disclosure v3 re-prompt, key
entry from empty) requires UNINSTALLING the app first — which WIPES the ~190 MB whisper model, the
~325 MB Kokoro voice, the encrypted provider keys (secure_store.xml), and transcript history. This is
the exact teardown that destroyed the owner's data twice (Release 0 INCIDENT 2026-07-27). Therefore:
  - Run EVERY other acceptance step that needs the installed state FIRST (the Soniox + C4 live-key
    checklists above, on-device sweeps, migration/purge branches).
  - Do the fresh-install first-run test DEAD LAST, deliberately, accepting the wipe — never via
    Gradle `connectedAndroidTest` / `installDebug` (those uninstall as teardown); use explicit
    `adb uninstall` / `adb install` so the sequence is controlled.
  - Afterward the owner re-downloads model + voice and re-enters keys to return to a usable state.
