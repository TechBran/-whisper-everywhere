# 4.3.1 — device acceptance (owner session)

Build under test: **4.4.1 / versionCode 91** (the previewer pack now follows the language you pick — **§AF**, the rows to run first; 90 = 4.4.0 went to the internal track with the word-for-word previewer, the four-pack bundle and the startup ring — §Z and §S, whose rows carry over untested unless marked; 89 = 4.3.4 went to the internal track with Gemini Live, live-by-default, the turbo card and the voice-archive fix — §J and §K, whose rows carry over untested unless marked) (supersedes 88 on the internal track — the owner confirmed Gemini Live working in real time on 88; 89 adds live-by-default, the turbo card copy, and the voice-archive fix — §K; Gemini Live — §J; the sherpa runtime bump — §J6; 87 = 4.3.3, the accessibility service becomes optional — §H; 86 was PROMOTED TO PRODUCTION 2026-09-04 after E11 passed; 86 was the silence fix — §E11; 85 went to the internal track 2026-09-04 with the backpressure governor, the detect-margin line and the Auto copy — §E9/E10/G1; 84 was PROMOTED TO PRODUCTION 2026-09-03 after E8 passed; 85 added the backpressure governor — §E9 — the detect-margin line and the Auto copy — §G; 83 went to the internal track 2026-09-03 and was superseded there by 84, which added the flatline cut — §E8; the owner's 83 session already confirmed the 350 ms hangover "is doing a better job" and language boundaries "picked up better"), now on `main` (the owner chose a local merge). It
carries MORE than the branch this sheet was written for: three fixes found during the owner's own
device testing (§F) and the 4.4 VAD hangover retune (§E) landed on top of it. Everything below
is the OWNER's device session; the implementer prepared this sheet and claims none of it as done.

Install — **the internal track, and only the internal track.** The sideload option this sheet
opened with was WRONG, and it was disproved on this device on 2026-09-02:

`adb install -r` of a locally-built APK over the Play copy is REFUSED with
`INSTALL_FAILED_UPDATE_INCOMPATIBLE`. The phone's app came from Play
(`installerPackageName=com.android.vending`), so it carries GOOGLE's app-signing key, while
anything built on this machine carries the UPLOAD key. They are different keys and Android will
not update across them. **The only way to force it is to uninstall first — DO NOT.** That erases
app storage and with it ~1 GB of downloaded model packs, and re-fetching the turbo pack is the
most expensive mistake available in this session. (`gradlew installDebug` does exactly this
uninstall silently: never run it.)

So: upload the AAB to the **internal track**, install it from Play on the Fold6 (Play-signed,
versionCode 83 > the live 82, asset packs preserved), and run the session on that.

**What the track build can and cannot show.** `proguard-rules.pro:81-92` strips every
`android.util.Log` call from release, so NO Kotlin `WE-DIAG` / `WE-TTS` line exists on it — no
`npu:`, `endpoint:`, `queue:`, `bubble hide:`, `TTSDIAG`, `projection consent:` or `switchSource:`.
This was confirmed empirically: the 4.3.0 baseline capture contains zero of them and only native
lines. Native `__android_log_print` is untouched by R8 and survives.

That leaves the sheet executable, because the PASS criterion of nearly every row is a BEHAVIOUR
you can see, and the greps were only ever corroboration:

| section | on the track build |
|---|---|
| §A | fully readable, via the NATIVE line `decode: N tokens in X ms (Y ms/token), terminated by <EOT\|the token budget\|the position cap\|the repetition cut> … nsp= lp= ent= rung= steps=` |
| §B | by eye — "the pill stays for the whole read" needs no log |
| §C | by eye — the ring and the gray/white scrubber are the row; only C1's `underN=0` corroboration is lost |
| §D | by eye — "no third dialog, one toast" is the whole row |
| §E | E1 by eye (a 15-second silent stretch that then dumps a paragraph at once is unmistakable — that IS the VAD-MISS); E3-E6 by eye. **E2 is the one row the track build cannot judge**: watch instead for latency growing run-on-run between finishing a sentence and seeing it, and mark E2 N/A otherwise |
| §F | fully by eye — F2's real criterion is "nothing you said while the video was paused is in the transcript" |

Ignore any `Select-String` command below on the track build; they are written for the day a
diagnostic build is installable. If you want the full grep evidence on the phone instead, say so:
preserving `WE-DIAG` in release is one line in `proguard-rules.pro`, and the cost is that those
lines then exist in the production build you promote (they carry no transcript content — that is
removed at the call sites — so the cost is posture, not privacy).

Capture anyway, for §A and for crashes: on the PC, in **PowerShell** (the `*>>` redirect is
PowerShell-only — cmd.exe rejects it),
`C:\Users\bastr\AppData\Local\Android\Sdk\platform-tools\adb.exe logcat -s WE-DIAG WE-TTS *>> C:\Users\bastr\.androidbuild\capture-431.txt`
(append, never clear; leave it running for the whole session). §E and §F read the same
capture -- no second command, and no `-c` between sections, because §E's evidence is a COUNT
over the whole session and clearing the buffer destroys it.

## A — decode guards (NPU turbo tier)
BEFORE A1: transcript text is never logged, so A1's "unchanged from 4.3.0" can only be judged by
eye — on the 4.3.0 build (or from a phrase set you know by heart), dictate the same five sentences
first and write down exactly what each one typed; A1 compares against that written record.

**4.3.0 BASELINE — CAPTURED 2026-09-02** (owner's Fold6, Play build 4.3.0/82, turbo on the NPU;
`C:\Users\bastr\.androidbuild\capture-430-baseline.txt`, 29 segments / 239 tokens). The owner
holds the five sentences' TEXT in their own notes — one sentence per bubble session, the fifth
with a few extra technical terms — and A1 compares against that. The numbers are:

| | 4.3.0 baseline |
|---|---|
| decode | **15.69 ms/token** weighted (median 16.34, min 11.13, max 60.0 — the max is a one-token segment, i.e. fixed overhead over one token) |
| encode | **1,752 ms** mean (1,694–1,825) |
| terminators | 29/29 `EOT`; **zero** `terminated by the token budget` |

The five sentences were: (1) "The quick brown fox jumps over the lazy dog near the river."
(2) "Please schedule the meeting for Thursday afternoon at three fifteen." (3) "One, two, three,
four, five, six, seven, eight, nine, ten." — the comma-list case A5 re-tests. (4) "Send him the
invoice, the contract, and the revised estimate by Friday." (5) a sentence of the owner's with
technical terms. Dictate the same five on 4.3.1 for A1 and A6.

That same capture is the EVIDENCE for the install section above: it contains ZERO `npu:` and
ZERO `TTSDIAG` lines and only native ones, because R8 strips the app's own `Log.i` in release.
So on the track build §A is readable only through the native `decode:` line; every other section
is judged by the behaviour table above.

A1. Dictate five ordinary sentences. Expect text unchanged from 4.3.0 and, per segment, a line
    `npu: encode=… decode=… tokens=N lang=… nsp=0.0x lp=-0.x ent=… rung=0 term=eot`
    (**this Kotlin line does not exist on the track build** — there, read the same five fields off
    the native `decode:` line, which carries `nsp= lp= ent= rung= steps=` and the terminator) — the field
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
    with the measured baseline above; RECORD THE NUMBER: 4.3.1 ______ ms/token, **4.3.0 = 15.69**
    (weighted; encode 1,752 ms mean). A weighted figure over several segments is the comparable
    one — a single short segment reads high because the fixed overhead is divided by few tokens.
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

## E — the VAD hangover retune (4.4, on top of 4.3.1)

WHAT CHANGED, so the rows below are readable: `HANGOVER_MS` 500 -> **350** (`EndpointerTuning.kt:87`),
npu-turbo gained its own measured commit floor (3,200 ms), and a burst discarded under
`MIN_SPEECH_MS` no longer erases the wall cap's cut point. This is the owner's own report:
*"the VAD just doesn't close when we need to … we want the VAD to actually open and close on
utterances, but the utterances in between can be really fast."*

**THE 4.3.0 MEASUREMENT THIS IS TUNED AGAINST** (owner's Fold6, Play build 4.3.0/82, turbo on the
NPU; `C:\Users\bastr\.androidbuild\capture-vad-headroom.txt`, 3 runs / 57 segments):

| | 4.3.0 shipped |
|---|---|
| decode duty | **25 % / 28 % / 39 %** — the pipeline sat idle 61-75 % of the time |
| work per commit | ~**2,050 ms** (encode 1,752 ms of it, and that part is FIXED — the QNN mel window is 30 s whatever the utterance length) |
| the symptom | a ~**15 s stretch with no cut in EVERY run** — the wall cap firing because the 500 ms hangover never elapsed |

TWO PRECONDITIONS for §E, from the pre-upload review (`docs/superpowers/reviews/2026-09-02-realtime-chunks-review.md`).
(i) Language = **Auto** for E1 and E7: `"auto"` is the only setting on which per-utterance detection
runs at all (`PreferencesManager.kt:178-181` -> `NpuWhisperBackend.kt:613`); with a picked language
every sentence is decoded under that one token and nothing can ever switch. (ii) Run E4 on a
GENUINELY percussive source — drums, clicks, applause; not a pad or a held chord — and keep the
native `decode:` lines (they carry `nsp= lp= ent= rung=`). That capture is the baseline the
accumulated-speech floor will be judged against later, and it costs nothing extra now.

E1. **The headline.** Read a paragraph aloud at your ordinary pace, 60-90 s, with the faint
    between-sentence pauses that used to be missed. PASS = cuts land on sentence ends AND
    `Select-String "VAD-MISS" C:\Users\bastr\.androidbuild\capture-431.txt` returns **zero hits**
    across that stretch. Spot-check any `endpoint: seq=… cut=vad … trailMs=` line: `trailMs`
    should now sit near **352-384**, not near 500-530. FAIL if a 15-second stretch of continuous
    speech passes with no cut — that is exactly one `VAD-MISS` line, and it is the bug.
    NOT an E1 failure: a window update that carries TWO short sentences at once. The VAD cut them;
    the cost governor merged them. That is E7, and it is judged there.
    RECORD: VAD-MISS count ______ ; a typical `trailMs` ______ ms.
    `[ ] PASS  [ ] FAIL`
E2. **The cost.** Same run, no extra dictation.
    `Select-String "queue: depth=" C:\Users\bastr\.androidbuild\capture-431.txt`
    -> depth should stay in **0-2**, the owner's own observed bound on 4.3.0 (*"I've never seen
    more than two queued"*). At the 2,000 ms turbo floor a transient 3 on sustained fast speech is
    expected and must drain on the next longer pause; monotonic growth is the failure. FAIL if depth reaches 4+ or climbs run-on-run without coming back
    down — that is the queue outrunning the decoder, and the fix is the cadence floor
    (`CommitCadencePolicy` npu-turbo = 3,200 ms), NOT the hangover.
    RECORD: max depth ______.
    `[ ] PASS  [ ] FAIL`
E3. **No mid-word cuts — this is what 350 spends.** Read at speed, with hard stop consonants and
    no real pauses: "Pick up the packet, Pat, and put it back." PASS = no word arrives in halves
    and nothing typed is a fragment. If this fails, the answer is to raise toward 420-450, not to
    revert: the acoustic floor is NAMED (`HANGOVER_MIN_MS = 300`) and 350 already sits above it.
    `[ ] PASS  [ ] FAIL`
E4. **THE REGRESSION ROW — the behaviour that must NOT change.** Start a session over music or a
    video with a percussive bed and NO speech; let it run ~20 s. PASS = it rides the wall cap as
    before — at most one commit in 15 s, and the one that arrives is a `VAD-MISS` cap line, not
    `cut=vad`. FAIL if commits start landing every few seconds. The owner's rule: *"that means
    there's more background noise and the app just doesn't want to miss the audio. That's
    perfect."* An early draft of this retune failed here, committing 3 times in 11.3 s where the
    shipped build commits 0 in 15.9 s; the draft was rejected, and this row is how you know the
    shipped one did not inherit it.
    RECORD: commits in 20 s ______.
    `[ ] PASS  [ ] FAIL`
E5. **A KNOWN LIMIT — this row cannot fail, it only reports.** Dictate word-by-word with emphatic
    gaps: "It. Is. Not. That. Simple." Expected: nothing types until the 15 s wall cap, then all
    of it at once. Each word is shorter than `MIN_SPEECH_MS` (300 ms) and is discarded, and there
    is no merge across the gaps. This is SHIPPED 3.7 behaviour, unchanged by the retune — it is
    the one thing a smaller hangover does not fix. A fix exists but needs a ruling only the owner
    can give, because two 288 ms drum hits and two 288 ms words are indistinguishable by duration.
    RECORD: does this bother you in real use? ______
    `[ ] NOTED`
E6. **The wall cap keeps its evidence.** Right after E5, keep talking normally for another 30 s.
    PASS = when the VAD does miss, segments still run to the full 15 s cap. Before this fix a
    single discarded short burst left `hasPendingSpeech` false, which collapsed the cap from 15 s
    to the 4 s first-segment window for the REST OF THE SESSION — so a cough at the start made
    every later segment four seconds long.
    `[ ] PASS  [ ] FAIL`
E7. **The language goal, on the phone — the row the review added.** Language = **Auto**. Dictate
    four short sentences alternating English and Spanish, ~2 s each, natural pauses. EXPECTED on
    turbo AS BUILT (cadence floor **2,000 ms** — owner ruling 2026-09-03, over the review's 3,200):
    the window updates once per sentence, each sentence in its own language, ~2.4 s after it ends.
    A PAIR (two sentences in one update, one half garbled) means the sentence period was under
    2.0 s — rare, since the hangover itself needs a 384 ms pause. The price of this floor is duty:
    on sustained fast speech the strip may show "(3+ in queue)"; it MUST clear on the next longer
    pause. FAIL if any sentence arrives decoded in the wrong language when the pause before it was
    clearly audible, or if the queue label persists past ~30 s of ordinary speech.
    RECORD: sentences per update ______ ; longest time the queue label stayed up ______ s ;
    would you trade bounded duty (3,200: pairs) for this? ______
    `[ ] PASS  [ ] FAIL`
E8. **THE FLATLINE CUT (new in 84) — edited YouTube, device audio, Language Auto.** Play the same kind
    of video as the 2026-09-03 capture (the ones where you watched the waveform flatline and nothing
    cut). EXPECTED: chunks now land at the visible flatlines — commit intervals well under the 8.4 s
    mean measured on 83, far fewer 15 s dumps — and NO word arrives split in halves. The trigger is
    RMS <= 10 held for 5 chunks (160 ms), armed ONLY while the source is captured playback. Then run
    an OWN-VOICE session: it must behave exactly as 83 did (the trigger is never armed on the mic).
    FAIL if any word is split, if own-voice behaviour changed, or if a paused video (digital silence
    for seconds) produces more than the one cut its speech floor allows. On the track build the
    evidence is the native `encode:` interval sequence in the logcat, as on 83; a silent commit emits
    no `encode:` line, so the capture undercounts rather than overcounts.
    RECORD: intervals (s) ______ ; 15 s caps ______ of ______ ; any split word? ______ ;
    own-voice unchanged? ______
    `[ ] PASS  [ ] FAIL`
    **MEASURED 2026-09-03 (owner: "definitely much better on YouTube videos, it is actually chopping the
    transcription correctly"). Same kind of edited video, versionCode 84, from the native encode: lines
    (capture-yt-84-flatline-0903-1936.txt), one 5.4-minute run: 64 intervals, mean 5.05 s, median 3.94 s,
    min 2.04 s (the 2,000 ms governor floor, binding as designed), 4 at the 15 s cap = 6 %. On 83 the same
    kind of video gave mean 10.8 s, median 12.0 s, 35 % at the cap. Tokens per chunk 12 vs 23; 65/65 EOT,
    no runaways. Duty ~40 % (64 x ~2.0 s / 323 s), as predicted. Detect tokens: en 43, zh 20, ko 1, ms 1 -
    whether the 20 zh are real Chinese content or short-chunk misdetection is an OPEN QUESTION for the
    owner (the video), not a number this capture can settle. Split words and own-voice: owner to confirm.**
    **OWNER CONFIRMED 2026-09-03: the video was Chinese with an American speaker, so the 20 zh chunks are
    real content - the language boundaries landing on monolingual chunks, which was the point. No split
    words seen. Own voice: "works pretty well; this transcription here is my own voice." E8 = PASS.**
    `[x] PASS  [ ] FAIL`  (owner-reported; the ko and ms single-word blips are the hysteresis case, noted)
E9. **THE BACKPRESSURE GOVERNOR (new in 85).** Turbo, own voice or device audio. Read a list of short
    sentences FAST for ~2 minutes with no real pauses between them (a stress case, not a use case).
    EXPECTED: if the strip ever shows "(3+ in queue)" it clears within a few seconds — the floor rose
    from 2,000 to 3,200 ms while a second segment was waiting and dropped back once it drained —
    and ordinary speech right afterwards is per-sentence again. FAIL if the queue label persists, if
    lag between finishing a sentence and seeing it grows and keeps growing, or if ordinary speech
    after the stress stays paired (slow mode stuck on — the one failure this design must not have).
    On the track build the evidence is the native `encode:` intervals: ~2.0 s apart while the NPU
    keeps up, then longer (up to ~3.2 s) for the first endpoint after a backlog clears, never a
    growing lag. The 3,200 number itself binds only when a decode outlives the next endpoint.
    RECORD: longest time the queue label stayed up ______ s ; per-sentence again afterwards? ______
    `[ ] PASS  [ ] FAIL`
E10. **THE SILENCE-HALLUCINATION MEASUREMENT (85 measures; the fix is the next build).** Same
    Chinese/English video as E8, device audio, Language Auto, with the logcat capture running. Let
    it run through at least one stretch of silence or music with no speech. Nothing to PASS or FAIL
    here: the native `detect:` line now ends ` second=<id> margin=<log-odds gap>[ ties=N]` on every
    chunk (the prefix is unchanged), and the analysis
    pairs them with `nsp=`/`lp=`/tokens to separate real Chinese chunks from the hallucinated
    blocks ("a block of Chinese that wasn't spoken", the stray "you" at the end). RECORD nothing;
    just keep the capture and say when a hallucinated block appeared, roughly.
    `[ ] CAPTURED`
E11. **THE SILENCE FIX (new in 86 / 4.3.2).** Three parts, all by eye on the track build.
    (a) Mic session in a quiet room with a fan or some background: stay silent for ~20 s. EXPECTED:
    nothing is typed, no "Thank you", no "you", no Chinese block — and on the track build there are
    NO native `encode:` lines for that stretch, because the skip happens BEFORE the encoder (the one
    part of this fix that is visible in release). (b) Then say one quiet word alone — "yes" — and
    stop: it is typed (the evidence floor is 192 ms — 6 frames Silero scored as speech — chosen
    so a soft lone word clears it even with half its frames in the dead band). (c) The
    Chinese/English video through its silent or music-only stretch: no Chinese credit block, no
    stray "you". Real speech everywhere: unchanged. KNOWN LIMIT, not a failure: a music bed that
    Silero scores as speech for seconds still reaches the decoder; the no-speech gate and the
    stock-phrase blocklist are the defence there, and a bed that beats both will still produce text.
    FAIL if a lone real word is dropped, or if silence still produces text.
    RECORD: text on 20 s of silence? ______ ; lone "yes" typed? ______ ; Chinese block on the video? ______
    **OWNER 2026-09-04, on 86: "I can let it sit completely in silence with the app open and it does not
    transcribe anything until I start talking. It definitely works a lot better." E11 = PASS.**
    `[x] PASS  [ ] FAIL`  (owner-reported)

## H — the accessibility service is optional (87 / 4.3.3)

H1. **Phone, service ENABLED (the Fold6 as it is):** everything identical to 86 — onboarding, the
    bubble, typing into a field, the final delivery. FAIL on any difference.
    `[ ] PASS  [ ] FAIL`
H2. **Phone, service DISABLED:** turn the accessibility service OFF in Settings, then re-run onboarding
    (or open the app): the accessibility card reads "Recommended", offers "Continue without it", and
    Continue is enabled with mic + overlay alone; the home screen shows "Typing into apps: off —
    transcripts are copied to the clipboard"; the bubble starts and is VISIBLE at rest (auto mode
    included); a dictation ends with a "copied" toast and the transcript on the clipboard — paste it
    somewhere. FAIL if you cannot get past onboarding, if the bubble is invisible, or if nothing is
    copied.
    `[ ] PASS  [ ] FAIL`
H3. **Galaxy XR, debug sideload (the Play copy must be uninstalled first — it holds no model):**
    onboarding passes with the "This device doesn’t allow apps to type for you" card; then tap the
    bubble control. This is THE OVERLAY DRAW TEST — record exactly what happens: a bubble appears
    somewhere in your space / the app reports it cannot show the bubble / nothing. Any of the three
    is a result; a crash is a FAIL.
    RECORD: what appeared? ______
    `[ ] RECORDED`

## J — Gemini Live (88 / 4.3.4; J1 effectively PASSED on 88 — owner 2026-09-10: "Gemini is working in real time")

Precondition: the Gemini key is entered in Engines & voices (the same key as batch). **Live is ON by
default once the key is entered; the switch on the Gemini row turns it off** (your ruling
2026-09-10 — "we shouldn't even have to select"), so J1–J4 need no visit to that switch at all;
check in passing that the row shows it already on. Language = Auto or a picked language — both must
work.
J1. **Partials.** Own voice, three sentences with normal pauses. EXPECTED: the strip shows the current
    sentence growing about a second behind your voice, one final per pause, the transcript reads as
    the cloud providers do (cased, punctuated). FAIL if nothing arrives, if the second and later
    sentences never appear (the API’s own voice detection failing — the build drives it manually to
    avoid exactly that), or if a sentence is duplicated.
    `[ ] PASS  [ ] FAIL`
J2. **Ten minutes.** Keep dictating (or play a video with device audio) for 10+ minutes. EXPECTED: at
    about 9 minutes the connection rotates on Google’s GoAway with no lost words and no visible gap
    beyond one pause; the session continues past 10 minutes. FAIL if words are lost at the rotation or
    the session ends.
    `[ ] PASS  [ ] FAIL`
J3. **Bad key.** Temporarily edit the key to something wrong, start a session, speak. EXPECTED: a toast
    naming the key problem and the session continuing on the local model — not a silent stop. Restore
    the key afterwards.
    `[ ] PASS  [ ] FAIL`
J4. **Silence.** Leave the mic open in silence for 30 s, then speak. EXPECTED: nothing typed during
    the silence, the next sentence transcribed normally. KNOWN COST CHARACTERISTIC (recorded, not a
    failure): Google bills the audio inside each activity, and an activity opens with the first frame
    after the previous cut, so leading silence inside an activity is billed — the row copy says
    “billed per minute while the mic is open”; the follow-up is to open activities on the first
    speech frame.
    `[ ] PASS  [ ] FAIL`
J5. **The other three live providers unchanged.** One short session each on OpenAI Realtime,
    ElevenLabs and Soniox (whichever keys you hold): behaviour identical to 87 — and a session that
    outlives the provider’s own connection limit now reconnects instead of ending the cloud half
    silently (the transport fix applies to all four).
    `[ ] PASS  [ ] FAIL`
J6. **Read-aloud on the release build (the sherpa runtime bump).** One full read-aloud with the local
    Kokoro voice on THIS track build: audio starts, no buffering, the scrubber works, a second read
    after the idle unload re-loads cleanly. FAIL on any stall or crash — the historical crash class
    this guards against was release-only.
    `[ ] PASS  [ ] FAIL`

## G — the onboarding copy (85)

G1. Fresh install or re-run onboarding: the language step still offers BOTH Auto and a single
    language. The Auto line now says detection is per phrase on the AI-chip model and suits mixed-
    language audio; the single-language line says it locks every phrase and is the most accurate
    choice if you speak one language. FAIL if either option is missing, or if the copy steers you
    away from either. Your 2026-09-03 ruling: keep both, explain Auto honestly, keep the lock.
    `[ ] PASS  [ ] FAIL`

## F — the three fixes from the owner's own device testing

F1. **The ribbon.** Start a session and watch the aurora ribbon from its first moment. PASS = one
    speed throughout, tracking your voice immediately. FAIL if it starts fast and mellows out over
    the first second — the owner's report, and the cause was that the ribbon advanced per FRAME,
    so the Fold6's 120 Hz panel ran it at twice the intended rate until the panel dropped to 60 Hz.
    `[ ] PASS  [ ] FAIL`
F2. **The microphone never enters a device-audio session.** Device-audio preference ON, a YouTube
    video playing, GRANT the share dialog. Mid-session, PAUSE the video for ~10 s and talk, then
    resume it. PASS = nothing you said while it was paused is in the transcript, and
    `Select-String "switchSource:" C:\Users\bastr\.androidbuild\capture-431.txt` shows **no
    `-> MIC` line** for the whole session. The owner's rule, verbatim: *"No microphone should enter
    that conversation at all until after you end that transcription and then start a new one."*
    FAIL if any word you speak during the pause lands in the transcript.
    `[ ] PASS  [ ] FAIL`
F3. **The one allowed handover discloses itself.** An app that genuinely refuses capture (a DRM
    video app). PASS = the toast reads "This app blocks audio capture — using the microphone, so
    your voice is included too", i.e. it names the consequence instead of switching silently. This
    is the ONLY route from device audio to the microphone inside a granted session, and it is kept
    on purpose — it is what makes Teams and DRM-protected audio transcribable at all. A merely
    PAUSED video is not a blocked app; that case is F2's, and it must not produce this toast.
    `[ ] PASS  [ ] FAIL`

---

PROMOTION GATE. The merge already happened (locally, on the owner's instruction), so this sheet no
longer gates a merge -- it gates the step the owner named: *"if it all works out great, then I just
promote that build into production."* Promote the internal-track build to production only after
**A2, A3, B1, B4, C1, D1, E1, E4, E7, E8, E9, E11, H1, H2, J1, J3, J5, J6, K1 and K3** are marked PASS — E1 passes on merged pairs, so
without E7 the sheet cannot see the one behaviour that decides the language goal.

Why those two are the §E entries: **E1** is the change's whole purpose, and **E4** is the one
behaviour a hangover change can silently invert. E4 is not a formality -- the first draft of this
retune failed exactly there in review, committing 3 times in 11.3 s on a percussive bed where the
shipped build commits 0 in 15.9 s. E5 CANNOT fail; it records a known limit so the owner can rule
on it with the phone in their hand.

If a gate row FAILS, the rollback is proportionate and per-row. E2/E7 queue growth that does
not drain -> the turbo floor 2,000 -> 3,200 in `CommitCadencePolicy.kt` (the bounded-duty value the
review recommended; it pairs sentences under 3.2 s periods and the KDoc says so). E1/E7 -> a
4.3.0-equivalent rollback is TWO lines, not one: `EndpointerTuning.kt:87` HANGOVER_MS 350 -> 500 AND
`CommitCadencePolicy.kt:176` `"npu-turbo"` back to MIN_COMMIT_INTERVAL_FAST_MS (1,200). A
hangover-only revert produces a 500 / 3,200 build that still pairs sentences (identical to 4.3.1
for pauses >= 544 ms, worse for 384-544 ms) — it is not a return to 4.3.0 at all. The `pro` move is
inert on turbo. E3 -> raise the hangover toward 420-450 rather than reverting, since the named
acoustic floor is 300; E4 -> report it before changing anything, because nothing in this retune
should be able to cause it and the cause matters more than the number.

## K — the voice-archive fix (89)

K1. **Fresh voice install.** In Engines & voices, remove the local voice if installed, then install it
    again on THIS build. EXPECTED: the download completes and verifies, and a read-aloud plays. This
    was failing on every build since 2026-09-08 ("Voice archive failed integrity verification")
    because the upstream archive was silently replaced; 89 accepts the known-good set. FAIL if the
    verification error returns.
    `[ ] PASS  [ ] FAIL`
K2. **Turbo card.** Open the model chooser on the Fold6: the AI-chip card reads "Best accuracy,
    fastest" with the body naming the 190 MB Multilingual model as the comparison.
    `[ ] PASS  [ ] FAIL`
K3. **Live by default.** With the Gemini key entered and nothing else touched, a session is live
    (words streaming) without flipping any switch; the switch on the Gemini row is ON and turning it
    OFF falls back to batch.
    `[ ] PASS  [ ] FAIL`

## Z — 4.4.0: the word-for-word previewer, the four packs, and the startup ring

**READ THIS FIRST — THE BUILD.** Every row below judges **4.4.0 / versionCode 90**, the
controller's identity commit. The branch that produced this section still reads **89 / 4.3.4**
(`app/build.gradle.kts:53-54`) because the identity bump is not the implementer's, and 89 is
already spent on the internal track — so an AAB built from the branch commit cannot be uploaded at
all. **If the About screen says 4.3.4, you are on the wrong build and §Z is void.** (The amendment
moved 4.4.0 off the plan's 89 for exactly this reason:
`docs/superpowers/plans/2026-09-10-streaming-previewer-amendment-pad.md`, "Identity".)

**WHAT CHANGED, so the rows are readable.** Three things ride this build:

1. **The previewer.** A sherpa-onnx streaming Zipformer (English, 73 MB) runs on the CPU *beside*
   whisper as a **tee**: the same PCM goes to whisper first and then to the previewer, and the
   previewer paints lowercase unpunctuated words on the bubble strip while you talk. It **never
   commits**. The typed transcript is still whisper's, word for word — that is the one promise
   every sentence of the copy carries (`StreamingPackCopy.kt:56-57`).
2. **Four asset packs instead of two.** `npu_turbo`, `npu_small`, and now `preview_en` (the
   previewer's four ONNX files) and `tts_kokoro` (the read-aloud voice's 350 MB archive, carried
   as-is). The two new packs are on-demand and **not** device-targeted — one variant, every device
   (`app/build.gradle.kts:237-248`). This is what closes the 2026-09-08 voice incident
   structurally: the bytes ride the AAB, so a voice update becomes a deliberate release instead of
   an upstream re-upload breaking every fresh install (§K1 is its predecessor row).
3. **The startup ring.** The recorder now opens **at the tap**, before the engine's `connect()`,
   and the audio spoken during the model load goes into a bounded 6 s ring that is replayed —
   paced, in order, exactly once — the moment the engine is ready (`StartupRing.kt:191`,
   `:212`). This is the owner's own report: *"if I tap the transcribe button and start speaking
   right away, sometimes the first … couple seconds of what I say gets cut off."*

**RULINGS ASSUMED by the build** (spec §0): R1 canary-only + release note · R2 label displaced ·
R3 default-on (`PreferencesManager.kt:432` — `localPreviewEnabled` defaults **true**) · R4 Auto =
whisper only on multilingual tiers · R5 4.4.0 (versionCode **90**, per the amendment, not the
plan's 89) · R6 streaming first. Mark any you rule otherwise.

### What the track build can and cannot show — READ BEFORE GREPPING ANYTHING

**Every diagnostic line this feature emits is gone on the track build.** `stream-open:`,
`stream-gate:`, `stream-timing:`, `stream-pack:`, `startup ring:` and `trim re-prewarm:` are all
Kotlin `android.util.Log` calls, and `proguard-rules.pro:85-96`'s `-assumenosideeffects` block
strips **every** `android.util.Log` call from release. The plan's Task 8 sheet was written as a
sequence of greps; on a Play-signed track build **none of them will ever hit**, exactly as §A-§F
found empirically for 4.3.1. Native `__android_log_print` (whisper's `decode:` line) is untouched
by R8 and survives.

So each row below states its **eye** criterion first and names the grep only as the corroboration
you get on a debug build. One row is genuinely not executable on the track build:

| row | on the track build |
|---|---|
| Z1, Z2, Z4-Z10 | fully by eye — "words appear / do not appear on the strip" and "the typed text is 4.3.4's" need no log |
| Z3 | by eye for the lag, plus `dumpsys battery` / `dumpsys power` for the thermal read (adb-side, unaffected by R8). The `rtf=` and `shed=` numbers are lost — judge "the strip never falls further behind" instead |
| Z11 | **NOT EXECUTABLE.** The canary's verdict reaches the user through no surface at all: `StreamingPackCopy.SETTINGS_DISABLED_ON_DEVICE` is defined but rendered nowhere, and `SettingsScreen.kt:1079-1082` says so in as many words. On the track build a canary failure is indistinguishable from "the feature is off" — no words, no explanation. Run Z11 on a **debug build**, or accept Z1 as the only canary evidence and read the gap in "Known limitations" below |
| Z12, Z13 | fully by eye — the row's own TITLE is the evidence (see the rows) |
| S1-S3 | fully by eye; S3 is judged by **feel** (two distinguishable haptics) |

If you want the grep evidence on the phone instead, preserving `WE-DIAG` in release is one line in
`proguard-rules.pro` — the same offer §A-§F made, with the same cost (those lines then exist in the
build you promote; they carry no transcript content, so the cost is posture, not privacy).

Capture anyway, for the native `decode:` lines and for crashes, in **PowerShell**:
`C:\Users\bastr\AppData\Local\Android\Sdk\platform-tools\adb.exe logcat -s WE-DIAG WE-TTS *>> C:\Users\bastr\.androidbuild\capture-440.txt`
(append, never clear; leave it running for the whole session).

### Before Z1 — the setup, on BOTH the Fold6 and the Tab

Settings → Engines & voices. **The previewer's row will NOT say "Download".** On a Play install it
reads one of two things, and both are a PASS:

- **"Install the English preview model"** — Play has already delivered the pack; the subtitle says
  *"Included with the app (73 MB) and already on this device — nothing to fetch."* The tap verifies
  and copies, with **no network at any point**.
- **"Get the English preview model"** — the ordinary on-demand fetch; the subtitle names the size,
  Play and your connection, and Play may raise its own confirmation dialog for a transfer that
  size.

Then turn **"Show live words"** ON (it is on by default) and confirm the row's title becomes
**"Live words while you speak (English)"** once installed. The titles are
`StreamingPackCopy.kt:147-153`; the sentences `:95-116`.

Do the same for the voice: **"Install the read-aloud voice"** or **"Get the read-aloud voice"**
(`TtsModelManager.kt:512-517`). Language: **English picked**, not Auto, on both devices.

**The one word that FAILS the setup is "Download"** on either row. Both packs are delivered through
Play on a track build; a row offering a third-party download means the app decided Play cannot
serve this install, which is the whole amendment undone. Report it and stop — Z12 and Z13 are that
row, and the rest of §Z would be measuring the fallback path.

Z1. **Words appear while speaking.** Three configurations: Fold6 on `pro`; Fold6 on `npu-turbo`
    with English picked; Tab on `multi` with English picked. Dictate five ordinary sentences each.
    EXPECTED: lowercase, unpunctuated words on the strip roughly half a second behind your voice,
    the first word about 1.2-1.5 s after you start. FAIL: no words at all in a configuration whose
    row says installed and whose switch is ON. Debug-build corroboration: `stream-gate: lang=en
    pack=1 cloud=0 batch=0 enabled=1 ready=1 -> preview=1` once at session start
    (`StreamDiag.kt:24-29`), and one `stream-timing: seq=N … padMs=500 shed=0 retract=0` per
    sentence — any `retract>0` on clean speech or any `shed=1` is a FAIL you can only see there.
    `[ ] PASS  [ ] FAIL`
Z2. **The typed result is 4.3.4's.** Read the same five sentences as TYPED text, not as strip text.
    EXPECTED: cased, punctuated, numerals — exactly what 4.3.4 typed for the same audio. Record
    4.3.4's text for two of them beforehand so this is a comparison and not a memory. FAIL: any
    difference in the typed text. This is the row the whole tee exists to keep green.
    `[ ] PASS  [ ] FAIL`
Z3. **A five-minute read** (Tab on `multi`, English picked). Read a printed page for five minutes.
    EXPECTED: the strip never falls *further* behind than it was in the first sentence — a backlog
    that grows is the failure, not a constant half-second lag. `dumpsys battery` before and after:
    temperature moves **< 3.0 °C**; `dumpsys power | grep -i thermal` status stays 0. This is the
    streamer-beside-whisper thermal read rung 3 never ran. FAIL: a lag that grows; a thermal step.
    `[ ] PASS  [ ] FAIL`
Z4. **Auto on `multi` / `npu-turbo` is byte-identical to 4.3.4.** Language Auto, dictate two
    sentences. EXPECTED: the "Transcribing…" line, **no words on the strip**, and the same typed
    text as 4.3.4. The gate is `sessionLanguage == "en"` over the RESOLVED language
    (`FloatingBubbleService.kt:229-237`), and Auto resolves to nothing before the first decode.
    FAIL: any words on the strip. `[ ] PASS  [ ] FAIL`
Z5. **Spanish picked on `multi`.** Dictate two Spanish sentences. EXPECTED: no live words, and the
    onboarding language step explains why — open it once and read it: *"Live words on the bubble
    are English-only for now; other languages show a progress line while each sentence is
    transcribed."* (`StreamingPackCopy.kt:86-87`). FAIL: English words painted over Spanish speech.
    `[ ] PASS  [ ] FAIL`
Z6. **A device-audio session on edited video** (YouTube, English). EXPECTED: words streaming; the
    4.4 flatline cut still fires on the digital silence an edit leaves; and the typed text is
    whisper's. A boundary word the strip got wrong is EXPECTED here (rung 1 §6) — what must not
    happen is that word reaching the typed text. FAIL: a lost cut (a 15 s stretch with no commit);
    a sentence typed twice. `[ ] PASS  [ ] FAIL`
Z7. **Source switch mid-utterance.** Speak into the mic, switch to device audio mid-sentence, keep
    speaking. EXPECTED: the switch cuts the segment, words continue on the fresh stream, and no
    word is typed twice or dropped. This is the seam the ring newly touches — the flush runs behind
    the old source's stop+join (`FloatingBubbleService.kt:2709-2714`). FAIL: a duplicated or
    missing word across the switch. `[ ] PASS  [ ] FAIL`
Z8. **Read-aloud during CONNECTING and during RECORDING.** Copy a paragraph, then tap the mic and
    the speaker lobe at once (CONNECTING), and again mid-session. EXPECTED: both refused — no
    Kokoro playback — and no stutter on the strip. FAIL: audio overlap.
    `[ ] PASS  [ ] FAIL`
Z9. **A batch file job while dictating.** Start a file transcription, then tap the mic. EXPECTED: a
    plain 4.3.4 session — **no words on the strip** — and the batch job completing. The previewer
    yields the CPU to the batch decoder by refusing to arm at all. FAIL: contention, a crash, or
    words on the strip. Debug corroboration: `stream-gate: … batch=1 … -> preview=0`.
    `[ ] PASS  [ ] FAIL`
Z10. **Stop mid-sentence.** Stop while a word is on the strip. EXPECTED: the tail arrives in the
    typed text, the strip shows "Finishing transcript…" and then comes down with the session, and
    nothing is left parked on screen afterwards. FAIL: a lost tail; a strip left occupying the
    screen after the session ends. `[ ] PASS  [ ] FAIL`
Z11. **The canary, both devices, at service start.** NOT EXECUTABLE ON THE TRACK BUILD — see the
    table above. On a **debug** build, grep once per service start on BOTH devices:
    `stream-open: sherpa=1.13.7 ort=1.27.1 threads=2 provider=cpu loadMs=<n> canary=pass
    canaryMs=<n> outLen=<n> load=ok` (`StreamDiag.kt:13-14`). FAIL: `canary=fail`, `canary=none`
    or `load=fail` on either device — report the whole line. On the track build, mark **N/A** and
    read Z1 instead: words appearing IS the canary passing, and words absent with the row installed
    and the switch on is a canary failure you cannot distinguish from anything else.
    `[ ] PASS  [ ] FAIL  [ ] N/A (track build)`
Z12. **The voice installs from the pack** — the 2026-09-08 incident's row, and the reason the
    archive moved into the AAB. In Engines & voices, remove the local voice if it is installed,
    then install it again on THIS build. EXPECTED: the row's title is "Install the read-aloud
    voice" or "Get the read-aloud voice" (never "Download"); the verify + extract completes; a
    read-aloud plays. If the row said "Install", it completed with **no network transfer at all** —
    the bytes were already on the device. FAIL: *"Voice archive failed integrity verification"*
    returns; or the row offers a download on a Play install.
    `[ ] PASS  [ ] FAIL`
Z13. **The previewer model fetches from the pack.** Same test for the previewer's row: install it
    from the state the device is actually in. EXPECTED: "Install the English preview model" (no
    network) or "Get the English preview model" (Play fetches it, possibly after its own
    confirmation dialog for 73 MB); then "Verifying and installing…"; then the row becomes "Live
    words while you speak (English)" and Z1 works. FAIL: a "Download the English preview model"
    row; a Play refusal the row does not explain; or a fetch that completes and leaves the row
    still offering to install. `[ ] PASS  [ ] FAIL`

### The startup ring — S1-S3 (the 2026-09-10 startup amendment)

These three come from a separate owner report and a separate amendment
(`docs/superpowers/plans/2026-09-10-startup-ring-amendment.md`), folded in here because they ride
the same build and the same four-pack bundle. They are **independent of the previewer**: run them
with "Show live words" either way.

S1. **Tap and speak immediately.** On a warm app, tap the mic and start talking with no pause at
    all. EXPECTED: every word arrives, including the first one. Then **background the app for a few
    minutes** (long enough for Android to trim it — the report's *"every once in a while"*) and
    repeat. EXPECTED: still every word. This is the row the report was filed about: before this
    build the trim handler released the model and nothing warmed it again, so the next tap paid a
    full cold load — 4,107 ms measured on npu-turbo — with the recorder not yet open. FAIL: a
    missing first word or clause in either half. Debug corroboration: `trim re-prewarm: level=<n>
    state=<s> rearm=true` after the trim (`FloatingBubbleService.kt:4211`).
    `[ ] PASS  [ ] FAIL`
S2. **Every local tier, and device audio.** Repeat S1 on each local tier the device offers (Fold6:
    `pro` and `npu-turbo`; Tab: `multi`), and once on device audio — start a video, then tap.
    EXPECTED: nothing lost on any of them, and the transcript's FIRST chunk is a whole clause, not
    a fragment starting mid-word. The ring holds 6 s (`StartupRing.kt:191`) against a 4,107 ms
    measured worst case, so there is about 1.9 s of margin: if an engine ever takes longer than 6 s
    to become ready, the OLDEST audio is dropped by design and the first words really are gone —
    report the tier and the delay rather than marking a plain FAIL. `[ ] PASS  [ ] FAIL`
S3. **The two cues say two different things.** There are now two haptics and you must be able to
    tell them apart by feel: a **short 20 ms acknowledgement at the tap** ("got it, you are being
    recorded") and the ordinary **50 ms "listening" cue at true readiness** ("the engine has your
    words") — `FloatingBubbleService.kt:4719` and `:4736`. EXPECTED: on a cold npu-turbo load the
    gap between them is a few seconds, and **every word spoken in that gap still arrives**. FAIL:
    the "listening" cue fires before the engine is ready (i.e. immediately, on a cold load); or the
    two cues are indistinguishable; or words spoken between them are lost.
    `[ ] PASS  [ ] FAIL`

### Known limitations of §Z, stated rather than discovered later

- **The canary has no user-visible voice.** `StreamingPackCopy.SETTINGS_DISABLED_ON_DEVICE`
  ("Live words are off on this device: the preview model did not pass its start-up check. Your
  transcripts are unaffected.") is defined, pinned by its test, and rendered by nothing —
  `SettingsScreen.kt:1079-1082` records that the previewer's row deliberately does not render it
  and that the wiring belongs to the reader of `StreamingPreviewEngine.disabled`. Consequence: on a
  release build a device where the canary fails shows a row that says the model is installed, a
  switch that says live words are on, and no live words, with nothing explaining why. Z11 is
  N/A there for the same reason. This is a gap in the R1 ruling's user-facing half, not a defect in
  the guard itself — the guard works, it just cannot speak.
- **R1's release note ships with the promotion**, unchanged: *"On some 2026 flagships the live-words
  preview may stay blank; the typed transcript is unaffected."*
- **Promote when Z1, Z2, Z4, Z12, Z13 and S1 pass on both devices.** Z1/Z2/Z4 are the previewer's
  own contract (words appear; the typed text is unchanged; Auto is untouched), Z12/Z13 are the
  four-pack delivery the amendment exists for, and S1 is the report that pulled the ring into this
  build. Z11 is N/A on the track build by construction, so it cannot be a gate there.
- **If a gate row fails, the rollback is per-row and proportionate.** The previewer is additive and
  its own switch turns it off ("Show live words", default true at `PreferencesManager.kt:432`), so
  a Z1/Z3 failure is a preference default flip rather than a revert. Z12/Z13 are the pack wiring
  and have a working fallback on non-Play builds, so a failure there is diagnosable without
  touching the engine. S1-S3 are the one part of this build that changed the capture seam: a
  failure there is a revert of the ring, not a tuning change, and it must be reported with the tier
  and the tap-to-cue delay.

---

## AF — the pack follows the language you pick (91 / 4.4.1)

**Run these first: they are what 91 exists for.** Everything else in this sheet is 90's, carried over.
Nothing in §AF changes the typed transcript — the previewer is additive, whisper still cuts the utterances
and its final still replaces the preview. A failure in any AF row is a live-words failure only.

**The one thing to know before you start.** Z4 is RE-RULED in this build. 90 gave live words to an Auto
user whose speech model was English-only (whisper resolves Auto to "en" for its own benefit on those
tiers, and that leaked into the previewer). 91 closes it: **Auto gets no live words, on every tier,
without exception** — your ruling, now literal rather than nearly true. Z4's old wording describes
behaviour that is deliberately gone.

AF1. **The top-up, on wifi.** With the pack NOT installed, English picked, and a local tier selected,
    open the app on wifi. EXPECTED: a card appears on Home, the model fetches and installs with no taps,
    and live words then work. This is the row the whole release exists for — the user who had 4.4.0 and
    would never have found the setting.
    `[ ] PASS  [ ] FAIL`
AF2. **The same on cellular.** EXPECTED: the card appears with a one-tap fetch naming the size, and
    **nothing downloads until you tap it.** The app has never spent mobile data unasked and must not
    start here.
    `[ ] PASS  [ ] FAIL`
AF3. **A delete stays deleted.** Delete the model in Settings, then reopen the app. EXPECTED: it does
    NOT come back. A delete is a decision.
    `[ ] PASS  [ ] FAIL`
AF4. **A dismiss stays dismissed.** Dismiss the card, reopen. EXPECTED: it stays gone, and the Settings
    row still installs on demand. Also: dismissing a card that is mid-transfer **abandons** the transfer.
    `[ ] PASS  [ ] FAIL`
AF5. **Picking a language at onboarding.** On a fresh onboarding, pick English. EXPECTED: the fetch
    starts without you visiting Settings, onboarding continues while it runs, and the progress is on Home
    when you land there. NOTE the deliberate limit: the progress shows on HOME, not on the onboarding
    step itself — fetching 73 MB beside onboarding's mandatory 190 MB speech model is contention we
    refused. Not a failure.
    `[ ] PASS  [ ] FAIL`
AF6. **Changing language in the app.** Switch the language to English with the pack missing. EXPECTED:
    the fetch starts in place, no trip to Settings. **Then expect the FIRST session after the switch NOT
    to show live words, and the second to.** The model loads asynchronously and nothing was resident —
    this is the price of not pre-loading for Auto users and is EXPECTED, not a failure.
    `[ ] PASS  [ ] FAIL`
AF7. **Auto is honest.** On Auto: nothing downloads, no card nags you, and the Settings rows say live
    words need a picked language. Your transcript still arrives per utterance exactly as before.
    `[ ] PASS  [ ] FAIL`
AF8. **A language with no model is told, not sold.** Pick French (or any language but English).
    EXPECTED: the rows say live words are not available in that language yet, and **the English model is
    NOT offered to you.** 4.4.0 would have offered it and taken 73 MB for something that could never arm.
    `[ ] PASS  [ ] FAIL`
AF9. **Auto on an English-only tier.** On Auto with an `eco`/English-scope model selected and the pack
    installed: **no live words.** This is the Z4 re-ruling above. If words appear, 91's copy is lying.
    `[ ] PASS  [ ] FAIL`
AF10. **The bytes come back.** With English picked and the model installed, switch to Auto. EXPECTED: the
    resident recognizer is released — the app's memory drops by roughly 169 MB. (Only the first such
    switch shows the drop; there is nothing resident to release on later ones.)
    `[ ] PASS  [ ] FAIL`
AF11. **Auto can still reclaim storage.** On Auto with the pack installed: the Settings section shows the
    caveat and the **delete** row. Deleting frees the 73 MB. Two deliberate changes to notice and NOT
    report as failures: an Auto user can no longer *install* the pack from Settings at all (the picker is
    the single way in, and the copy says so), and the "Show live words" switch is not shown to a picker
    whose language has no model — a switch that governs nothing should not be offered.
    `[ ] PASS  [ ] FAIL`

**Known and deliberately not fixed in 91** (adjudicated into the next build, where the fix is one
unification rather than four guards — `ADJUDICATION-441.md`): on a build where Play cannot serve the
install, Settings cannot see a transfer the Home card started and could start a second one; and the delete
row can draw over a running *repair*. Both are 4.4.0 shapes that 91 makes newly reachable, not new
breakage. If you hit either, note it and move on — they are already scheduled.

**Promote 91 when AF1, AF2, AF3, AF7, AF8 and AF9 pass.** AF1/AF2 are the delivery promise and the data
promise; AF3 is the respect-a-decision promise; AF7/AF8/AF9 are the three sentences this build made
literal. AF5/AF6/AF10/AF11 are informative — a failure there is a bug report, not a gate.
