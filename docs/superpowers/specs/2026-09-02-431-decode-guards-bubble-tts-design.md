# 4.3.1 — Decode guards, the bubble survives a read, projected-complete playback

**Date:** 2026-09-02 · **Target release:** 4.3.1 (versionCode 83) · **Status:** owner-approved design
**Origin:** three owner field reports against the shipped 4.3.0/82 (2026-09-01): (1) on the NPU turbo
tier "one single repeated word ... 70 or 80 times" every so often; (2) "thank you / thank you for
watching / you" from dead time; (3) the read-aloud bubble disappears when the speaker lobe is tapped
within ~4 s of a copy, and playback still buffers. All file:line references verified against HEAD
`3f982b9`.

The three workstreams are independent and ship together as 4.3.1 on `feat/4.3.1-guards-and-tts`.

---

## Workstream A — NPU decode guards (reports 1 and 2)

### Root cause (verified in source)

`nativeDecodeSegment` (`app/src/main/cpp/qnn_asr.cpp:2875-3115`) is a pure greedy argmax loop with
exactly two terminators: the model emitting EOT, or the 196-token budget
(`NpuDecodePolicy.maxTokensFor`, `app/src/main/java/com/whispereverywhere/npu/NpuDecodePolicy.kt:130`).
It has no quality gate of any kind. The CPU tier runs whisper.cpp, which has three, and the CPU tier's
own comment names report 1 verbatim: temperature fallback "is the sole defense against degenerate
repetition loops ('word word word x50'), which a 10-minute YouTube capture hit on-device
(2026-07-18) with the fallback disabled" (`app/src/main/cpp/whisper_jni.cpp:847-855`). The NPU tier
was built without any of them.

- **Report 1 (repetition):** greedy decoding enters a cycle; the argmax is deterministic so nothing
  can leave it; the loop runs to the 196-token budget. A repeated unit of " word," is 2–3 tokens, so
  196 tokens is 65–98 repeats — the owner's "70 or 80". Never the same word because the cycle starts
  wherever the distribution was near-tied. Turbo's 4-layer decoder is more loop-prone than
  large-v3's 32-layer one; w8a16 logits flatten near-ties further. **Falsifiable field signature:**
  every such segment logs `decode: 196 tokens ... terminated by the token budget` under tag
  `WE-DIAG` (native `LOGI`, `qnn_asr.cpp:91`, survives release).
- **Report 2 (silence hallucination):** `<|nospeech|>` is in the always-on suppress mask
  (`WhisperTokenFamily.kt:112`). Masking it for the argmax is right; never *reading* it is the bug.
  whisper.cpp reads its softmax probability at the SOT step (`app/src/main/cpp/whisper.cpp/src/whisper.cpp:7440`)
  and drops the segment when `no_speech_prob > 0.6 && avg_logprob < -1.0` (`:7865`). The NPU tier
  computes neither number, and `TranscriptText.clean` strips only bracketed markers, so "Thank you."
  is ordinary text and is typed. The input class is real: the 3.7 endpointer opens on one frame
  ≥ 0.50 and dead-band frames (0.35–0.50: breath, "um", thinking sounds) hold the gate open without
  counting as speech (`SileroEndpointer.kt:555`), so a segment of thinking-noise > 300 ms reaches the
  model. The CPU tier is protected by its own VAD filter (`whisper_jni.cpp:815`, `we_vad_filter`)
  rather than by whisper.cpp's no-speech gate — the fork's read of `no_speech_prob` is inert (the
  SOT block's logits are not extracted; a follow-up) — which is why the owner noticed only after
  moving to turbo.

### Reference (the working example, read completely)

whisper.cpp fork at `app/src/main/cpp/whisper.cpp/src/whisper.cpp`:
- defaults `temperature_inc 0.2`, `entropy_thold 2.4`, `logprob_thold -1.0`, `no_speech_thold 0.6` (`:6235-6238`);
- the temperature ladder `0.0, 0.2, ..., 1.0` (`:7134-7141`);
- `no_speech_prob` = softmax of the **unfiltered** logits at the SOT step, read at `<|nospeech|>` (`:7432-7441`);
- per-token `plog` from the **filtered** (suppressed) distribution, summed into `avg_logprobs`
  (`whisper_sequence_score`, `:6862-6876`);
- entropy = histogram entropy of the token **ids** in the last 32 tokens (`:6885-6905`) — id-based,
  needs no probabilities;
- rung fails on `result_len > 32 && entropy < entropy_thold` (`:7807`), or on
  `avg_logprobs < logprob_thold && no_speech_prob < no_speech_thold` (`:7835`); the next temperature
  is tried; the last rung's output is kept whatever it is;
- final: `is_no_speech = no_speech_prob > no_speech_thold && avg_logprobs < logprob_thold` drops the
  segment's text (`:7865-7883`).

### Design

**Policy is data, handed in; the loop applies it.** Exactly the shape the suppress lists already
have (`NpuDecodePolicy` KDoc: "these arrays are handed in, once per segment, and native does the
masking"). The thresholds and the ladder live in `NpuDecodePolicy` as constants, are JVM-pinned, and
cross the JNI boundary as arguments — native never carries a second copy.

```kotlin
object NpuDecodePolicy {
    const val ENTROPY_THOLD = 2.4f        // whisper.cpp default
    const val LOGPROB_THOLD = -1.0f       // whisper.cpp default
    const val NO_SPEECH_THOLD = 0.6f      // whisper.cpp default
    const val ENTROPY_WINDOW = 32         // whisper_sequence_score's n
    const val CYCLE_MAX_DISTINCT = 8      // ours: the entropy trip needs a cycle signature (final review)
    val TEMPERATURES = floatArrayOf(0.0f, 0.2f, 0.4f, 0.6f, 0.8f, 1.0f)
    fun isNoSpeech(noSpeechProb: Float, avgLogprob: Float): Boolean =
        noSpeechProb > NO_SPEECH_THOLD && avgLogprob < LOGPROB_THOLD
}
```

**Native contract change.** `nativeDecodeSegment` gains `temperatures: FloatArray`,
`entropyThold: Float`, `logprobThold: Float`, `noSpeechThold: Float`, and an OUT `stats: FloatArray`
of fixed length `NpuDecodeStats.SIZE` with named slots (`NO_SPEECH_PROB`, `AVG_LOGPROB`, `ENTROPY`,
`RUNG`, `TERMINATOR`, `STEPS`). Terminator codes: `0 eot`, `1 budget`, `2 cap`, `3 cut`. Return value
unchanged (count of ids, `< 0` on failure). `NpuNativeContractTest` pins slot indices and codes
against the native source text, the way it pins `kEotToken` today.

**The loop, per segment (native, under the session mutex):**

1. **Position 0** (SOT, fed during the prompt walk — the detect pass is untouched): compute the
   log-softmax of the **raw, pre-mask** logits and read `p(<|nospeech|>)`. The logits are
   `ufixed16` with per-tensor scale-offset (`tensorQuantParams`, `qnn_asr.cpp:229`); the offset
   cancels under softmax, so `v_i = scale * q_i` suffices. One 51,866-wide pass per segment.
2. **For each rung in `temperatures`:** zero self-KV, re-feed the prompt (the encode and cross-KV
   are kept — a rung is decode-only, ~10.7 ms/token). Generate with `suppressThenArgmax` at
   `T == 0`, or by sampling from the masked softmax at `T > 0` (`std::mt19937` seeded
   `0x5EED ^ rung`, deterministic per rung so a device repro is reproducible). Per generated token,
   accumulate the log-prob of the chosen id from the masked distribution (entries at `kLogitFloor`
   excluded from the normaliser — they are whisper.cpp's `-INFINITY`). **At every step once
   `count > ENTROPY_WINDOW`**, compute the histogram entropy of the last 32 ids; if
   `< entropyThold`, the rung has failed: stop generating (a runaway dies at ~33–40 tokens, not 196)
   and record the failing count `k`. After the loop, `avgLogprob = sum / count`; the rung also fails
   if `avgLogprob < logprobThold && noSpeechProb < noSpeechThold`. A rung that did not fail ends the
   ladder. On the **last** rung a log-prob failure keeps the output (the reference's behaviour —
   `:7834` gates the fallback on "not at the last temperature"); Kotlin's no-speech decision may
   still blank it. A rung fails on entropy only when the tripping window is a cycle signature
   (≤ `CYCLE_MAX_DISTINCT` = 8 distinct ids); a legitimate low-entropy list never enters the
   ladder (final review, 2026-09-02).
3. **If the last rung still failed by entropy:** keep the first `k - ENTROPY_WINDOW` tokens (the
   prefix before the window that tripped), terminator `cut`. This is the one deliberate deviation
   from the reference, whose last-rung behaviour — emit whatever came out — *is* report 1.
4. Fill `stats`; the existing `decode:` `LOGI` line (`qnn_asr.cpp:3105`) grows
   `nsp=%.2f lp=%.2f ent=%.2f rung=%d` (numbers only — the privacy rule in `diagToken` is
   unaffected).

**Kotlin (`NpuWhisperBackend.transcribe`, `NpuWhisperBackend.kt:482-662`):** pass the policy in;
after a successful decode, `if (NpuDecodePolicy.isNoSpeech(stats[NO_SPEECH_PROB], stats[AVG_LOGPROB]))`
return `""` — which reaches `LocalWhisperEngine.kt:480`'s existing blank branch and resolves as
`EmptyExpected`, the same outcome the CPU tier's VAD-empty takes: nothing typed, no marker. The
`npu:` diag line (`NpuDiag.line`) grows `nsp= lp= ent= rung= term=`; `NpuDiagTest` pins the format.
The language resolution is unaffected (the engine reads detection only for non-blank results, and
this tier is per-utterance, so no latch is fed).

**Cost.** One 51,866-entry softmax at position 0 and one per generated token (~0.1–0.5 ms each
against a 10.7 ms step: 1–5 %). A fallback rung costs its tokens' decode time (~0.4 s when entropy
kills it at 40 tokens; up to ~2 s for a full 196-token low-confidence rung), only on segments that
trip a gate. The ladder length is policy data so the device session can shorten it if noisy audio
shows the logprob rung firing often.

**Both NPU tiers.** Small-on-NPU runs the same loop and has the same missing guards; the fix is
per-loop, not per-model.

### Error handling
- Every new native argument is validated once on entry (lengths, finite thresholds,
  `temperatures[0] == 0`, ascending) with the existing `failure(...)` refusal shape.
- A `stats` array shorter than `SIZE` is refused like an undersized `out`.
- Sampling never draws a suppressed id (they are at the floor and excluded from the normaliser); a
  distribution with no live mass is the existing `-3` "graph produced no token" failure.
- The no-speech decision is Kotlin's; native only reports. A future tier that wants different
  thresholds changes one object.

### Testing
- `NpuDecodePolicyTest`: thresholds equal whisper.cpp's defaults (pinned to the fork's source text
  by `NpuNativeContractTest`, as `kEotToken` is); `isNoSpeech` truth table at both edges; the ladder
  starts at 0 and steps 0.2.
- `NpuNativeContractTest`: stats slot indices, terminator codes, `ENTROPY_WINDOW == 32` all
  cross-checked against `qnn_asr.cpp` text.
- `NpuDiagTest`: the grown `npu:` line, and that no field can carry transcript content.
- `NpuBackendWiringTest`: blank-on-no-speech reaches `EmptyExpected`; a non-blank result is
  untouched; the fallback arm is unaffected.
- **Device session (the only place the guards can be seen working):** the pre-fix `terminated by
  the token budget` signature must not recur on a set of the owner's runaway utterances; dead-time
  segments show `nsp>0.6` and type nothing; ordinary dictation shows `rung=0 term=eot` and
  unchanged text. Accuracy on clean speech must be byte-identical at rung 0 (greedy is unchanged).

---

## Workstream B — The bubble survives a read (report 3, part 1)

### Root cause (verified in source)

During a read-aloud `currentState` stays `IDLE`; the only mark of "reading" is `isSpeakingNow`
(`FloatingBubbleService.kt:358`). Two hide paths never consult it:

- `onTextFieldUnfocused` (`:1133-1150`): `TEXT_FIELD` context + `IDLE` → 200 ms → `hideBubble()`.
  It is driven by the accessibility service's window-state handler, which calls
  `notifyTextFieldUnfocused()` immediately for **any window event from a package that is neither
  the focused app nor ours** (`WhisperAccessibilityService.kt:274-277`), and that call nulls
  `lastFocusedEditText` first (`:527`), so `hasActiveFocusedField()` (`:1202-1209`) can never rescue
  the bubble afterwards.
- `onMediaPlaybackStopped` (`:1189-1200`): `MEDIA_PLAYBACK` context + `IDLE` → `hideBubble()`.

The ~4 s signature fits the SystemUI clipboard chip (on screen for a few seconds after every copy;
its *appearance* is the copy signal, `:266-272`). A speaker tap while it is up makes our overlay
focusable for 300 ms to read the clipboard (`FloatingBubbleService.kt:899-914`); the chip dismisses;
a SystemUI window event lands; "foreign package → unfocus"; the bubble hides with the read still
running. A tap after the chip is gone produces no such event. The exact SystemUI event is not
provable from source alone, so the fix is at the sink and the instrumentation names the trigger.

A latent cousin, same file: `startSpeaking` (`:944-983`) sets `isSpeakingNow = true` and enters the
speaking visuals **before** `TtsController.speakFromTrigger`'s own guards run (`TtsController.kt:74-108`);
a bailed trigger (voice not installed, arbiter busy) returns without `onDone`, and the pill is stuck
in speaking visuals with nothing playing.

### Design

- **`hideBubble(reason: String)`** — every caller passes a reason (`"clipboard-autohide"`,
  `"field-unfocused"`, `"media-stopped"`, `"idle-after-session"`). The function logs one `WE-DIAG`
  line, `bubble hide: reason=<r> state=<s> context=<c> speaking=<b>`, and **while `isSpeakingNow`
  it refuses**, parking the reason in `deferredHideReason` and logging `bubble hide DEFERRED`.
- **Replay** in the `IDLE` render branch next to `shouldHideOnIdle` (`:3478`): if a reason is parked
  and `!isSpeakingNow`, clear it and hide only if the bubble would have hidden anyway —
  `currentContext != TEXT_FIELD && !alwaysOnMode()` — setting `currentContext = NONE` first, as the
  original callers do. A clipboard-summoned bubble therefore still leaves after the read, exactly as
  the summon comment promises (`:825-829`); a bubble on a focused field stays.
- **`speakFromTrigger` returns `Boolean`** (true iff `TtsEngine.speak` was invoked and returned
  true). `startSpeaking` keeps its order but on `false` resets `isSpeakingNow`, clears the three
  engine callbacks and calls `exitSpeakingVisuals()`. `SpeakTextActivity` may ignore the return.
- The pure decision is extracted so it is JVM-testable: `BubbleHidePolicy.decide(speaking, alwaysOn,
  visible) -> Ignore | Defer | Hide` and `BubbleHidePolicy.replay(context, alwaysOn) -> Boolean`.
- **Not changed:** the accessibility service's classification of SystemUI windows as "a different
  app". The new diag line names the real trigger on the owner's first repro; if it is the clipboard
  chip, ignoring `com.android.systemui` in the foreign-package branch is a one-line follow-up with
  its own decision (it would also stop the notification shade from hiding the bubble).

### Error handling
- A parked reason is cleared on every replay and on service destroy; it cannot outlive a session.
- The deferral is a refusal, not a queue: a second hide while speaking overwrites the reason.

### Testing
- `BubbleHidePolicyTest`: the truth table (speaking → Defer; always-on → Ignore; hidden → Ignore;
  otherwise Hide; replay hides only off a non-field context, never in always-on).
- Owner device check: copy → tap the lobe within 2 s → the bubble stays for the whole read and
  logcat shows `bubble hide DEFERRED reason=...` naming the trigger; wait > 5 s → identical
  behaviour; toolbar read with the voice uninstalled → bubble returns to idle, no stuck pill.

---

## Workstream C — Projected-complete playback (report 3, part 2)

### Today (verified in source)

`TtsBufferPolicy.shouldProceed` (`app/src/main/java/com/whispereverywhere/tts/TtsBufferPolicy.kt`)
starts playback once `bufferedMs ≥ 1.25 × rtf × dMax` (clamped 1.2–4 s) or after 2.5 s of no bank
growth, then stalls whenever a unit outruns that lead; each stall raises the resume watermark by
500 ms. The playback thread applies it at `TtsEngine.kt:352-393`; the producer plans every unit up
front (`ClauseSplitter.plan`, `:614`) and appends per unit; only the on-device callback feeds the RTF
EWMA (`:535`) — a cloud read gates on the constant `DEFAULT_RTF`. The owner's decision
(2026-09-02): start only when the rest of the read is guaranteed to be generated before playback
reaches it — "projected-complete".

### Design

**Start rule** (new `TtsBufferPolicy.shouldStart`; `shouldProceed` remains the resume-after-stall
rule and is untouched):

```
shouldStart(bufferedMs, remainingMs, totalMs, noGrowthMs, done) ⟸
    bufferedMs > 0 && (
        done
        || (totalMs > SHORT_READ_MS && bufferedMs >= PROJECTED_SAFETY * rtfEwma * remainingMs)
        || noGrowthMs >= START_CAP_MS
    )
SHORT_READ_MS = 20_000     // a read this short is generated fully first; the wait is small
PROJECTED_SAFETY = 1.5
START_CAP_MS = 12_000      // the stuck-producer escape, counted on no-growth time only
```

Derivation: with lead `L` and remaining audio `R`, the producer needs `rtf·R` wall-seconds; playback
consumes the lead in `L` seconds; `L ≥ 1.5·rtf·R` means synthesis finishes before playback reaches
the frontier with a 50 % margin, independent of unit granularity. As a fraction of the read,
`L/T ≥ 1.5rtf / (1 + 1.5rtf)`: 47 % at the local voice's measured 0.58, 60 % at rtf 1, 75 % at rtf 2
— "mostly all", never degenerating to everything-first. `done` bypasses (the bank is all there will
be); the cap escapes a producer that has stopped growing the bank with audio already banked (a
cloud fetch waiting on its 45 s timeout), so the user hears the banked part while it resolves.

**Inputs the engine must publish** (volatile, written by the producer, read by the playback thread):
`plannedChars` (sum over the planned units) and `remainingChars` (decremented as each unit's
audio has been appended — after `generateWithCallback` returns on the local path, after `synth`
returns on the cloud path). `remainingMs = remainingChars × ClauseSplitter.MS_PER_CHAR`,
`totalMs = plannedChars × MS_PER_CHAR` (`ClauseSplitter.kt:23`). No-growth time is the existing
`gateSinceMs` clock (`TtsEngine.kt:366-373`).

**Cloud RTF.** The cloud path records `recordRtf(unitWallMs, unitAudioMs)` per completed unit,
skipping the first unit for the same reason the local path skips `seq == 0`. A latched-to-local
read keeps feeding from the local callback as today.

**Visible wait.** The gate already shows the processing ring (`onBuffering(true)`, `:377-380`).
Two additions so the wait reads as loading, not silence:
- `onProgress` grows a fourth argument, `estimatedTotal` (samples: `totalMs × rate / 1000` until
  `done`, then `available`); the playback thread emits it at ≤ 10 Hz **during the start gate** as
  well as during playback.
- `TtsScrubberView.setProgress(played, available, estimatedTotal, done)`: the bar spans
  `max(estimatedTotal, available)`; gray = ready `[0, available)`; white = still generating
  `[available, total)` (replacing the fixed 10 dp white tail when a total is known); red = played;
  seeks clamp to `available` as today. When `total ≤ available` the drawing is byte-for-byte today's.

**Unchanged:** the stall/resume machinery, the AudioTrack, the caps, the clause splitter, the cloud
seam, `PERFORMANCE_MODE_LOW_LATENCY`, instant stop.

### Error handling
- `remainingChars` can only decrease; a unit that fails and falls back locally decrements once, when
  its audio (from whichever path) is appended.
- `estimatedTotal < available` (the 45 ms/char estimate ran short) is clamped by the scrubber's
  `max`; the gate's `remainingMs` floors at 0, which reduces the rule to `bufferedMs > 0`.
- A producer that throws sets `doneFlag` in its existing `finally` (`:661-672`), releasing the gate.

### Testing
- `TtsBufferPolicyTest`: `shouldStart` at rtf 0.58 / 1.0 / 2.0 with the 47 % / 60 % / 75 % edges;
  the `SHORT_READ_MS` floor (19 s waits for `done`, 21 s does not); `done` bypass; the cap fires on
  no-growth time only; `bufferedMs == 0` never proceeds; `shouldProceed` unchanged (existing tests
  untouched).
- A pure `TtsRemainingEstimate` helper (chars → ms, clamp) with its own test, used by both the
  engine and the scrubber's total.
- **Device session:** a ~2-minute article on the local voice: `TTSDIAG end` shows `underN=0`,
  first word at ≈ 47 % generated; the scrubber's white region visibly shrinks during the wait; stop
  during the wait is instant. Same on a cloud voice. A one-sentence read still starts within the
  old ~1–2 s (it completes first).

---

## Workstream D — The screen-capture consent asks at most twice per session (report 4, added 2026-09-02)

### Report

Transcribing a YouTube video: the system "share your screen / share one app" dialog appears; if
the user cancels, the app immediately raises the dialog again, and the user is trapped unless
they tap the bubble fast enough to stop the session, or grant. Owner: *"we should only ask for
permission once ... I would even dare to say twice just in case someone makes a mistake and
cancels ... it would just default back to the open microphone."*

### Root cause (verified in source)

Two sites launch the consent trampoline and neither remembers that it already asked:

- `startAudioInput()` (`FloatingBubbleService.kt:2077-2101`) — `AudioSourcePolicy.decide` returns
  `RequestConsent` whenever media is playing, no projection is stored, API ≥ 29 and the device-audio
  preference is on (`AudioSourcePolicy.kt:22-31`); the session starts by asking.
- The handover in `onMediaPlaybackStarted` (`:1178-1196`) — a RECORDING session on the MIC with the
  preference on and no projection stops the mic and asks again.

`onConsentDenied` (`:2242-2249`) falls back to the mic, correctly — but the video is still playing,
and the consent activity's own appearance and disappearance drive the media detector: YouTube pauses
while the dialog covers it and resumes when it is dismissed, so the detector emits
`onMediaPlaybackStopped` then `onMediaPlaybackStarted` (`MediaSessionDetector.kt:255-261`, the
`!isMediaPlaying` edge). The handover site sees RECORDING + MIC + preference + no projection and asks
again. Every cancel resumes the video, every resume asks — the loop. Nothing in the session bounds
it.

### Design

A **per-session consent budget**, consulted at BOTH request sites, reset when a session starts:

```kotlin
class ProjectionConsentBudget(private val maxAsks: Int = MAX_ASKS_PER_SESSION) {
    var asked: Int = 0; private set
    fun mayAsk(): Boolean = asked < maxAsks
    fun noteAsked() { asked++ }
    fun reset() { asked = 0 }
    companion object { const val MAX_ASKS_PER_SESSION = 2 }
}
```

- **Two asks per session, not one.** The loop's own re-fire means the second dialog follows the
  first cancel almost immediately, which is exactly the owner's "in case someone makes a mistake"
  recovery: a second cancel pins the session to the microphone; a grant on the second ask captures
  device audio. After the budget is spent the session never asks again; a new session (the next
  bubble tap) starts a fresh budget.
- `AudioSourcePolicy.decide` gains `consentAvailable: Boolean`; `RequestConsent` is returned only when
  the budget allows, else `UseMic` — the decision table stays pure and tested.
- The handover site asks only if `mayAsk()`; when the budget is spent it stays on the microphone and
  shows ONE toast for the session ("Using the microphone for this session — screen capture was
  declined"), never one per media event.
- Every ask logs `WE-DIAG` `projection consent: asked=<n>/<max>`; the first blocked ask logs
  `projection consent: budget spent -> microphone for this session`.
- `onConsentDenied` is unchanged (mic + its toast). A grant makes the budget moot (`hasProjection()`).

### Error handling
- The budget is service state reset in `startRecording`; a service restart starts at 0.
- The counter counts ASKS launched, not answers — a dialog dismissed by the system (rotation,
  process death of the trampoline) still consumed an ask, which errs toward not re-prompting.

### Testing
- `ProjectionConsentBudgetTest`: 0→1→2 then `mayAsk()` false; `reset()`; `maxAsks` honoured.
- `AudioSourcePolicyTest`: `consentAvailable = false` turns `RequestConsent` into `UseMic`; every
  other row unchanged.
- `ConsentBudgetWiringPinTest` (source pins on the service): exactly two `requestConsent(` call sites;
  each is preceded, on a live line inside the same block, by `consentBudget.noteAsked()`; each sits
  under a `consentBudget.mayAsk()` guard; `consentBudget.reset()` is a live line inside
  `startRecording`.
- Owner device sheet §D: D1 cancel twice → microphone, no third dialog, the two diag lines; D2 cancel
  once then grant → device audio; D3 stop and start a new session → the dialog returns.

## Release

- `app/build.gradle.kts`: `versionCode = 83`, `versionName = "4.3.1"`; `ReleaseIdentityTest`
  renamed and re-pinned with a KDoc paragraph in the existing style (82 is spent in production).
- Branch `feat/4.3.1-guards-and-tts`; ledger `.superpowers/sdd/2026-09-02-431-guards-tts/progress.md`;
  the implementer → review package → reviewer → fix round → scoped re-review loop per task; the JVM
  suite green at every commit, measured from raw XML; fast-forward onto `main` when the device
  session passes.
- Play: internal track first (the owner's check list = the three device sessions above), then
  production.

## Out of scope
- Any change to the accessibility service's window classification (B's follow-up, gated on the diag
  evidence).
- The 4.4 streaming arc; 8 Gen 2 coverage research; the privacy §7 correction (separate, pending).
- A hallucination phrase list — the reference gate is the principled fix; revisit only if the device
  session shows stragglers.
