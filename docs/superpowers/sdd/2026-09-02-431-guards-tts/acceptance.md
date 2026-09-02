# 4.3.1 — device acceptance (owner session)

Build under test: **4.3.1 / versionCode 83** (`feat/4.3.1-guards-and-tts`). Everything below is the
OWNER's device session; the implementer prepared this sheet and claims none of it as done.

Install — two options:

1. **Diagnostic build (recommended):**
   `C:\Users\bastr\AppData\Local\Android\Sdk\platform-tools\adb.exe install -r C:\Users\bastr\.androidbuild\WhisperEverywhere\app\outputs\apk\debug\app-debug.apk`
   (the APK `.\gradlew.bat :app:assembleDebug --no-daemon` writes from this branch; its timestamp
   must postdate the branch's last code commit). The debug build is signed with the release key on
   this machine, so it installs OVER the Play build without uninstalling (models and settings in
   app storage persist); every `npu:`, `bubble hide:` and `TTSDIAG` line exists on it; the next
   track build (versionCode 84) replaces it. If the AI-chip model shows as not installed
   afterwards (Play-delivered packs are Play's), fetch it again from the model chooser.
   **NEVER `gradlew installDebug`** (it uninstalls, and the models with it).
2. **The internal-track build:** R8 strips the app's own `Log.i` lines in release, so only §A is
   observable there, via the NATIVE line
   `decode: N tokens in X ms (Y ms/token), terminated by <EOT|the token budget|the position cap|the repetition cut> … nsp= lp= ent= rung= steps=`;
   §B by eye only; §C not at all — mark those rows N/A on the track build.

Capture: on the PC, in **PowerShell** (the `*>>` redirect is PowerShell-only — cmd.exe rejects
it),
`C:\Users\bastr\AppData\Local\Android\Sdk\platform-tools\adb.exe logcat -s WE-DIAG WE-TTS *>> C:\Users\bastr\.androidbuild\capture-431.txt`
(append, never clear; leave it running for the whole session).

## A — decode guards (NPU turbo tier)
BEFORE A1: transcript text is never logged, so A1's "unchanged from 4.3.0" can only be judged by
eye — on the 4.3.0 build (or from a phrase set you know by heart), dictate the same five sentences
first and write down exactly what each one typed; A1 compares against that written record.

A1. Dictate five ordinary sentences. Expect text unchanged from 4.3.0 and, per segment, a line
    `npu: encode=… decode=… tokens=N lang=… nsp=0.0x lp=-0.x ent=… rung=0 term=eot` — the field
    set is `encode= decode= tokens= lang= nsp= lp= ent= rung= term=`, and clean speech ends
    `rung=0 term=eot`. FAIL if any `rung>0` on clean speech, or any transcript differs from what
    4.3.0 typed for the same sentence.
    `[ ] PASS  [ ] FAIL`
A2. Repeat the utterances that used to run away (the "70-80 repeats" ones). Expect NO repeated
    block, and tokens well under 196. A runaway the ladder RESCUED shows `rung>=1`; one the last
    rung CUT shows `term=cut` with `ent=` below 2.40 — **both are PASS**. `steps=` on the native
    `decode:` line is the segment's total decode cost across rungs (it counts every rung, so it
    exceeds `tokens=` whenever the ladder climbed — that is not a fault). Grep:
    `Select-String "terminated by the token budget" C:\Users\bastr\.androidbuild\capture-431.txt`
    → expected: no hits.
    `Select-String "terminated by the repetition cut" C:\Users\bastr\.androidbuild\capture-431.txt`
    → native's spelling of `term=cut`: each hit is a segment the last rung cut (a PASS shape, and
    the only §A evidence the track build can show).
    `[ ] PASS  [ ] FAIL`
A3. Open the mic, breathe/"um"/think for 2-3 s without words, close. Expect nothing typed and a
    line with `nsp>0.60` AND `lp<-1.00`. FAIL if "Thank you" (or any text) appears. A line with
    `nsp>0.60` but `lp>=-1.00` is NOT a failure — confident words beat the silence vote, which is
    whisper.cpp's rule and the reason both halves are required. If no `decode:` line appears the
    endpointer never opened — say "um"/"hmm" until one does; nothing typed only counts once a line
    exists.
    `[ ] PASS  [ ] FAIL`
A4. If ANY line shows `nsp=-1.00`: the logits scale was unreadable on this asset — report it;
    the entropy guard still ran (A2 must still pass) and every such line shows `rung=0` (with no
    scale there is nothing to sample from, so the greedy rung is the only rung).
    `[ ] PASS  [ ] FAIL`
A5. The list row. Dictate "one, two, three, … twenty-five" WITH the commas. Expect the full list
    typed, `rung=0` (native `rung=0`) and no `cut`: a comma list is low-entropy and legitimate,
    and the trip needs a cycle signature (at most 8 distinct ids in the 32-id window), which a
    list never has. FAIL if the list is truncated, or `term=cut` / `rung>0` appears on it.
    `[ ] PASS  [ ] FAIL`
A6. Cost. From A1's five clean sentences take the native `decode:` line's `(Y ms/token)`; compare
    with a 4.3.0 capture if one exists; RECORD THE NUMBER: 4.3.1 ______ ms/token, 4.3.0 ______.
    The per-token log-softmax costs ~5–12 % by estimate and `t0` now includes a per-rung self-KV
    memset, so a small rise is expected — report it; this row cannot FAIL on the number alone.
    `[ ] PASS  [ ] FAIL`

## B — the bubble survives a read
B1. Auto pop-up mode, bubble hidden. Copy a paragraph in another app; tap the pulsing speaker
    lobe within 2 s. PASS = the pill (aurora + scrubber + ✕) stays for the whole read; a
    `bubble hide: … decision=DEFER` line IF any hide was attempted — none is a PASS too (a copy
    from a non-editable app parks nothing). Grep:
    `Select-String "bubble hide:" C:\Users\bastr\.androidbuild\capture-431.txt`
    → if present, a line reading
    `bubble hide: reason=<r> decision=DEFER state=IDLE context=… speaking=true`; **WRITE THE
    REASON DOWN** (it names the trigger for the follow-up). After the read, expect either a
    `deferred:<r>` line (context NONE) or no further hide at all (context TEXT_FIELD or
    MEDIA_PLAYBACK, or always-on — the replay fires only from a NONE context).
    `[ ] PASS  [ ] FAIL`
B2. Same, tapping after 6 s. Expect identical behaviour.
    `[ ] PASS  [ ] FAIL`
B3. Always-on mode: same copy→tap; the bubble never leaves.
    `[ ] PASS  [ ] FAIL`
B4. Copy FROM a focused text field, tap the lobe within 2 s → the bubble stays for the read and
    after it. Expect a DEFER line with `reason=field-unfocused` or `reason=media-stopped` — this
    is the row that is guaranteed to park a hide, which is why it is in the merge gate.
    `[ ] PASS  [ ] FAIL`
B5. Uninstall the voice (Settings) and tap the lobe: toast, bubble returns to idle, no stuck pill.
    `[ ] PASS  [ ] FAIL`

## C — projected-complete playback
C1. Local voice, a ~2-minute article. Expect the ring + a scrubber whose gray region grows and
    white shrinks, first word at roughly half generated. The `TTSDIAG start … rule=projected` line
    is logged at the FIRST PLAY, not at the gate release — then `TTSDIAG end … underN=0`. FAIL if
    `underN>0`, or if no `start` line is logged for the read.
    `[ ] PASS  [ ] FAIL`
C2. Same on a cloud voice: `underN=0`; the `start` line's `rtf=` is the cloud's, not 0.75.
    `[ ] PASS  [ ] FAIL`
C3. A one-sentence read: starts within ~2 s (`rule=done`). `rule=done` is the expected rule for
    ANY read of 20 s or less — such a read is generated in full before it starts.
    `[ ] PASS  [ ] FAIL`
C4. Stop (✕) during the wait: instant, no audio afterwards. Scrub back mid-read: works; scrubbing
    past the gray edge lands at the frontier. During the hold the scrubber is already live: a drag
    to the bar's end lands at the gray frontier and the hold continues — that is expected, not a
    stuck seek.
    `[ ] PASS  [ ] FAIL`
C5. A read longer than about 10.7 minutes on the local voice: `rule=cap` after a 12 s no-growth
    wait is the EXPECTED outcome, because the 5-minute AHEAD_CAP bounds what the hold can bank —
    the projection can never be satisfied past that length. Not a failure.
    `[ ] PASS  [ ] FAIL`

## D — the screen-capture dialog asks at most twice
D1. Device-audio preference ON. Play a YouTube video, tap the bubble to transcribe. The share
    dialog appears: CANCEL. It appears once more (the video resumed): CANCEL again. Expect: no
    third dialog; the toast "Using the microphone for this session — screen capture was
    declined"; transcription continues from the microphone. Grep:
    `Select-String "projection consent:" C:\Users\bastr\.androidbuild\capture-431.txt`
    → `asked=1/2`, `asked=2/2`, `budget spent -> microphone for this session`. FAIL if a third
    dialog appears, or if the "Using the microphone for this session — screen capture was
    declined" toast appears more than once. The per-cancel toast "Using microphone (capture
    permission declined)" fires once per cancel — twice in this run — and is expected.
    `[ ] PASS  [ ] FAIL`
D2. Same start; CANCEL the first dialog, GRANT the second. Expect device audio captured
    ("Capturing device audio" toast) and the video's words transcribed.
    `[ ] PASS  [ ] FAIL`
D3. After D1, stop the session (tap the bubble) and tap again with the video still playing.
    Expect the dialog to return (a new session, a fresh budget).
    `[ ] PASS  [ ] FAIL`

---

Merge gate: fast-forward `feat/4.3.1-guards-and-tts` onto `main` only after **A2, A3, B4, C1 and D1**
are marked PASS.
