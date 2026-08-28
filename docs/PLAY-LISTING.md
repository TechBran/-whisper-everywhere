# Play Store Listing — 3.2.0 Generational Release

> REPLACED 2026-07-18 via Play Console. The old listing (archived below) described the
> RETIRED cloud-API-key product ("audio goes directly to OpenAI", "Bring Your Own Key",
> "requires OpenAI API key") — factually wrong for the on-device app — and named a
> competitor (Otter AI) plus price-comparison claims, both metadata-policy hazards.
>
> **Old short:** "Floating voice-to-text bubble powered by Whisper. Bring Your Own Key."
> **Old full (2,645 chars):** "STOP PAYING $15/MONTH FOR VOICE-TO-TEXT..." — cloud-era text
> pitching wholesale OpenAI API access, BYO-key setup, $0.36/hour cost comparisons, and
> per-app feature bullets. Full text recoverable from Console change history if ever needed.
> **Old video:** youtube.com/shorts/Xsv-x4a6I5o (user replacing).

> Research-backed (workflow wf_240c6655, 2026-07-18): positioning is ARCHITECTURE-LED because
> every major competitor is adjective-led and cloud-backed. Four pillars in order: (1) speak
> into any app, (2) transcribe what the phone is playing (category-defining — nothing on Play
> does it), (3) on-device read-aloud in 53 voices, (4) the anti-subscription close. Policy
> guardrails respected: no ranking claims, no promo language in title/short, no keyword
> stuffing, no FREE badges on graphics.

## App title (30 chars max, plain text — pick one)

1. `Whisper Everywhere` (18 — keep as-is, cleanest)
2. `Whisper Everywhere: Dictation` (29)
3. `Whisper Everywhere Voice Type` (29)

## Short description (80 chars max, plain text)

> Offline voice typing, media transcription & natural read-aloud. 100% on-device.

(79 chars.)

## Full description (4000 chars max — <b> tags render on web, unicode bullets everywhere)

---

Your voice, your phone, your words — nothing in between. Whisper Everywhere types what you say into any app, transcribes what your phone is playing, and reads anything on your screen aloud. All of it happens on your device. No account. No subscription. No audio ever uploaded — it works in airplane mode.

<b>🎙️ Speak into any app</b>
A floating bubble follows you everywhere. Tap it and talk — your words are typed straight into whatever field you're in: messages, email, notes, search bars, any app. No switching keyboards, no special phone required. Recognition runs on YOUR phone's graphics chip using OpenAI's Whisper models, so it's fast, accurate, and completely private.

<b>📺 Transcribe what your phone is playing</b>
A video, a podcast, a voice note in another app — press the bubble while media plays and Whisper Everywhere captures the audio stream directly, microphone off. No room noise, no holding your phone up to a speaker. Watch the transcript build live, then get the whole thing on your clipboard — or drop it straight into any text field. Every session is saved in your private history.

<b>🔊 Have anything read aloud</b>
Highlight text and the bubble becomes a speaker. Copy a whole article and tap once. 53 natural voices — American, British, and international — speak it instantly, generated entirely on your device. Pause with a tap, scrub back and forward on the timeline, pick your voice and speed. No per-word pricing, no reading limits, ever.

<b>🔒 Private by architecture, not by promise</b>
• Speech recognition, voices, everything: on-device
• Zero audio or text leaves your phone — verifiable, it's open source
• No account, no ads, no analytics, no tracking
• Internet used once: downloading your chosen model

<b>⚡ Built for speed</b>
• GPU-accelerated Whisper on Snapdragon (Adreno) — with a fast CPU path for every other phone
• Five model tiers from light-and-quick to maximum accuracy, including multilingual
• Voice activity detection: silence costs nothing
• Instant start — the engine is warmed up before you tap

<b>📋 Everything stays yours</b>
• Rolling transcription history on your device — copy, share, delete
• One-time model download, yours offline forever
• Works on any Android 8.0+ phone, foldables included

Whisper Everywhere is free software (GPLv3). Source code: github.com/TechBran/-whisper-everywhere

Permissions, plainly: microphone (dictation you start), display over apps (the bubble), accessibility service (typing into fields and reading your selection aloud — used only for those actions, disclosed in-app), media capture (only when you transcribe playing media), notification access (detecting when media plays).

---

(~2,950 chars — room to grow with review quotes later.)

## Release notes — 3.2.0 (500 chars max)

---

The generational update. NEW: Read-aloud — highlight or copy any text and the bubble speaks it in 53 natural on-device voices, with pause, speed, and a scrub timeline. Faster dictation: GPU-accelerated Whisper with instant session start. Transcribe playing media with the mic off, watch it live, and send it to any field. Redesigned living bubble with lock and speaker controls. Fully offline, no account, now open source (GPLv3).

---

(~430 chars.)

## Screenshots (min 2, max 8; first 3 carry the pitch; 4+ at 1080px+ for featuring)

Order:
1. **Bubble dictation** — recording pill with aurora over a messaging app, caption overlay "Speak into any app".
2. **Media transcription** — preview window building a transcript over YouTube, "Transcribe what's playing — mic off".
3. **Read-aloud** — speaking pill (waveform + scrubber) over an article, "53 voices, all on your phone".
4. Voice picker dialog — "Pick your narrator".
5. Home screen — status card + transcriptions.
6. Settings read-aloud section / model tiers — "Five model tiers".
7. Transcript history.
8. The idle blob with lobes, macro shot.

Raw captures so far (Fold main display, good for the 7"/10" TABLET slots — foldables are
served tablet screenshots when unfolded): `C:\Users\bastr\.androidbuild\WhisperEverywhere\shots\`.
Phone-slot screenshots should be taken on the cover display or cropped 9:16 ≥1080x1920.

## Feature graphic (1024x500, no FREE badges, no rating claims)

Concept: deep-black field, the aurora pill center-left mid-wave (red→navy), speaker lobe
visible; right side headline "Voice typing. Media transcription. Read-aloud." subline
"100% on your device." App icon bottom-right.

## Rollout

Staged: start 10% → monitor vitals/reviews ~48h → 25% → 50% → 100%. Halting is possible,
decreasing isn't. versionCode 72 (3.2.0) — Play only requires higher-than-last.

---

# FINAL — 3.3.0 listing copy (owner-directed 2026-08-01, Variant B frame)

Set in the Console the same day, in the SAME rollout as the 73 AAB. The device-audio pillar and
privacy bullets are the post-policy-flip HONEST versions — the draft variants below still carried
"captured audio never leaves your phone, in any configuration", which the 2026-08-01 decision made
false; do not resurrect them.

## Short description (72 chars)

> Voice typing, transcription & read-aloud — on-device, or your own cloud.

## Full description (3907 chars)

Your voice, your phone, your words. Whisper Everywhere types what you say into any app, transcribes what your phone is playing, and reads anything on your screen aloud. All of it happens on your device by default — no account, no subscription, works in airplane mode. Want the big cloud models or deeper language coverage? Bring your own key and switch anytime; your audio then goes only to the provider you chose, under your own account.

<b>🎙️ Speak into any app</b>
A floating bubble follows you everywhere. Tap it and talk — your words are typed straight into whatever field you're in: messages, email, notes, search bars, any app. No switching keyboards, no special phone required. Recognition runs on YOUR phone's graphics chip using OpenAI's Whisper models, so it's fast, accurate, and completely private.

<b>🔑 Optional: bring your own cloud</b>
Add your own key for OpenAI, Google Gemini, ElevenLabs, or Soniox — a multilingual specialist — and cloud transcription streams your words in real time as you speak. Prefer a cloud voice for read-aloud? Pick one from the built-in voice picker. Your keys stay encrypted on your phone, your audio and selected text go only to the provider you chose, and the on-device engine takes over automatically if the provider fails. Remove the key and it all stops — instantly.

<b>📺 Transcribe what your phone is playing</b>
A video, a podcast, a voice note in another app — press the bubble while media plays and Whisper Everywhere captures the audio stream directly, microphone off. No room noise, no holding your phone up to a speaker. Watch the transcript build live, then get the whole thing on your clipboard — or drop it straight into any text field. Transcribed on-device by default — or by the cloud provider you selected, exactly like your dictation — and the screen share ends itself when the session is done.

<b>🎧 Transcribe audio files you already have</b>
Pick any recording on your phone and get a full transcript. Choose the engine per file — on-device is always free, and cloud prices are shown up front before anything starts.

<b>🔊 Have anything read aloud</b>
Highlight text and the bubble becomes a speaker — or copy text anywhere and the bubble appears with one; tap it to listen. 53 natural voices speak on your device, or choose a cloud voice from the picker. Pause with a tap, scrub back and forward, pick your voice and speed. On the on-device voice there's no per-word pricing and no reading limits. Even the built-in how-to guide can read itself to you.

<b>🔒 Private by architecture, not by promise</b>
• Speech recognition, voices, transcription: on-device by default
• Your audio — dictated, played, or a file you pick — leaves your phone ONLY if you add your own provider key and select that provider; it's open source, so you can verify it
• No account, no ads, no analytics, no tracking
• Internet used for a one-time model download — and nothing else unless you turn on a cloud provider

<b>⚡ Built for speed</b>
• GPU-accelerated Whisper on Snapdragon (Adreno) — with a fast CPU path for every other phone
• Model tiers from light-and-quick to maximum accuracy, including multilingual
• Voice activity detection: silence costs nothing
• Guided first-run setup — permissions, models, and keys in one pass

<b>📋 Everything stays yours</b>
• Rolling transcription history on your device — copy, share, delete
• One-time model download, yours offline forever
• Works on any Android 8.0+ phone, foldables included

Whisper Everywhere is free software (GPLv3). Source code: github.com/TechBran/-whisper-everywhere

Permissions, plainly: microphone (dictation you start), display over apps (the bubble), accessibility service (typing into fields and reading your selection aloud — used only for those actions, disclosed in-app), media capture (only when you transcribe playing media), notification access (detecting when media plays).

---

# SUPERSEDED DRAFT — 3.3.0 listing revisions (kept for history)

> **Why this draft exists:** 3.3.0 turns the app into a multi-provider product. It adds optional
> cloud transcription across FOUR providers the user keys themselves (OpenAI, Google Gemini,
> ElevenLabs, and Soniox — a multilingual specialist), optional cloud read-aloud voices chosen from
> an in-app picker, batch transcription of audio files the user already has, and a real-time streaming
> live-typing mode (OpenAI). With any of those enabled the Data Safety form flips to voice
> recordings Collected=Yes / Shared=Yes / Optional AND adds "text you select for read-aloud"
> Collected=Yes / Shared=Yes / Optional (see PLAY-DECLARATIONS.md §5 and the §7 Console checklist).
>
> Google diffs the Data Safety form against the storefront: keeping "100% on-device", "No audio
> ever uploaded", and "Zero audio or text leaves your phone" beside those declarations is the
> textbook inconsistency rejection, and a false-claim metadata violation on its own.
>
> The on-device story is still substantially TRUE — it is the default, it is what every existing
> user gets with no key, and it is unconditionally true for media/device audio in every
> configuration — so both variants below keep it as the hero and add the cloud paths honestly.
> **Neither is live. Nothing changes in the Console until the owner picks one, reconciles it with
> the declarations doc, and edits it there by hand.**
>
> ⚠️ **Checklist rule:** this file and the Play Console are TWO copies of the same copy. Any listing
> change happens in BOTH, in the SAME release as the AAB. (This draft exists because the 3.2.0
> listing was left untouched while the app gained cloud upload.)

## The live strings that go false when 3.3.0 ships

| Where | Live text (false once 3.3.0 ships) |
|---|---|
| Short description | "…100% on-device." |
| Full description, opener | "No audio ever uploaded — it works in airplane mode." |
| Read-aloud pillar | "generated entirely on your device" / "No per-word pricing, no reading limits, ever" — false once a cloud voice is picked |
| Privacy bullets | "Zero audio or text leaves your phone…" / "Internet used once: downloading your chosen model" |
| Feature graphic subline | "100% on your device." |

**Stays TRUE — do NOT soften:** the media-transcription pillar's "captures the audio stream
directly, microphone off" and "never leaves your phone" — device/playback audio is transcribed
on-device only, in every configuration (PLAY-DECLARATIONS.md §5). It is the one claim cloud does not
touch, and it should stay a headline.

## Variant A — "on-device by default" (minimal change, defensive)

**Short (77 chars):**
> Offline voice typing, media transcription & read-aloud. On-device by default.

**Opener:** …All of it happens on your device by default. No account. No subscription. Your dictated
audio — and any text you pick for read-aloud — is never uploaded unless you add your own cloud
provider key and select that provider; everything else works in airplane mode.

**Privacy block:**
• Speech recognition, voices, and transcription: on-device by default
• Your dictated audio — or text you choose for read-aloud — leaves your phone ONLY if you add your own cloud provider key and select that provider; it's open source, so you can verify it
• Audio captured from other apps (videos, podcasts) is transcribed on-device and never leaves your phone, in any configuration
• No account, no ads, no analytics, no tracking
• Internet used for: a one-time model download — and nothing else unless you turn on a cloud provider

**Feature graphic subline:** "Private by default."

## Variant B — "your choice" (leans into the returning feature)

**Short (72 chars):**
> Voice typing, transcription & read-aloud — on-device, or your own cloud.

**Opener:** …All of it happens on your device by default — no account, no subscription, works in
airplane mode. Want the big cloud models, or better coverage in another language? Bring your own key
and switch anytime; your audio then goes only to the provider you chose, under your own account.

**Adds a pillar block (bringing back BYOK was the #1 review request after it was removed):**
<b>🔑 Optional: bring your own cloud</b>
Add your own key for OpenAI, Google Gemini, ElevenLabs, or Soniox — a multilingual specialist — and
choose cloud transcription per session, including a real-time streaming live-typing mode on OpenAI. Prefer
a cloud voice for read-aloud? Pick one from the built-in voice picker. You can also transcribe audio
files you already have. Your keys stay encrypted on your phone, your audio and selected text go only
to the provider you chose, and the on-device engine takes over automatically if the network drops.
Remove the key and it all stops — instantly.

**Privacy block:** same as Variant A.

**Feature graphic subline:** "Your voice. Your device. Your choice."

## Draft release notes — 3.3.0 (within 500 chars)

> Cloud is back — on your terms. Bring your own key for OpenAI, Google Gemini, ElevenLabs, or Soniox and get real-time streaming transcription as you speak — on-device stays the default and the automatic fallback. New: transcribe audio files you already have, cloud read-aloud voices with a picker, guided first-run setup, and a built-in how-to guide that reads itself aloud. Audio goes only to the provider you choose, only when you choose one. Keys stored encrypted on your device.

(481 chars. The 3.2-era 'audio from other apps never leaves your phone' line is GONE — false since the 2026-08-01 device-audio policy flip; the honest scope is 'only to the provider you choose, only when you choose one'. No speed claims: measured on-device dictation is not beaten by cloud on latency, so the
cloud pitch is accuracy and language coverage — never speed. Soniox is positioned on multilingual
breadth, not throughput; the live mode is "real-time streaming as you speak," not "faster.")

## Release notes — 3.4.0 (within 500 chars)

> The two most-requested changes from your reviews. The transcript window is now resizable — drag the double-arrow handle at its top right; the size sticks, and press-and-hold resets it. Dictation now types into your field once, complete, when you stop — your words build live in the bubble window as you speak, nothing lands half-finished, and your clipboard stays yours. Also: the bubble always stays a microphone (read-aloud lives on the speaker button), and the window now stays fully on screen.

(Credits the Play review that asked for resize + final-only commit, per the 3.4.0 spec ship notes.
Owns the biggest behavior change existing users will feel — live-cloud turns no longer type into the
field word-by-word; "your words build live in the bubble window" is the reassurance, "once, complete,
when you stop" is the pitch. No speed claims, no absolute privacy claims — nothing here touches the
post-flip rules. Reply to the requesting review after rollout reaches them.)

## Release notes — 3.4.1 (within 500 chars; supersedes the 74 notes — most users jump 73→75)

> The two most-requested changes from your reviews, plus an important fix. The transcript window is now resizable — drag the double-arrow at its top right; the size sticks. Dictation now types into your field once, complete, when you stop — your words build live in the bubble window, and your clipboard stays yours. The bubble always stays a microphone. Fixed: saved sessions were vanishing from the Transcriptions list right after finishing — your history now keeps every session again.

(486 chars. Keeps the full 3.4.0 story since 74 was live for under a day — most updaters skip it —
and adds the history hotfix honestly ("were vanishing"). The bug shipped in 73's stats fix, rode
into 74, fixed in 75/fd9fe75. Same claim discipline as always.)

## Release notes — 3.5.0 (within 500 chars)

> You're in charge of your speech model now. Setup lets you pick your tier with plain-language descriptions — fastest, most accurate, English-only or 90+ languages — and you can switch anytime in Settings. Prefer the big cloud models? Add your own API key (OpenAI, Gemini, ElevenLabs, Soniox), billed to your account. Also: ending a session now finishes faster, and the app says what it's waiting on while it wraps up.

(Verify ≤500 chars in Step 2. Claim rules: no cloud speed claims — "finishes faster" refers to our
own fix vs our own previous version, which is factual and allowed. CONTINGENCY: that clause is
backed by the D2 before/after timings, which run AFTER this commit. If the before-build logs
falsify the C3 conviction — `local-drain` ≈ 0 everywhere — edit the notes BEFORE Play submission:
replace "Also: ending a session now finishes faster, and the app says what it's waiting on while
it wraps up." with "Also: the app now says what it's waiting on while a session wraps up." and
re-verify the count.)

## Release notes — 3.6.0 (within 500 chars)

> Our biggest on-device speed release. Words now appear in the bubble while the model is still transcribing — no more silent wait. The first line of a session lands sooner, multilingual mode no longer re-detects your language every segment, and switching models warms the new one in the background. Stopping now counts up while it finishes. Every claim is against our own previous version, measured on the same phone.

(415 chars)

---

# 3.7.0 — release notes + listing delta (PREPARED, NOT PUBLISHED)

> **Ship gate.** `versionCode 78` / `versionName 3.7.0` certifies the BRANCH. The Play release
> itself gates on the NPU track (owner ruling), so nothing below promises a date, says "now
> available", or assumes a rollout window — this copy is written to stay true however long that gate
> holds. When it does ship, the checklist rule at `:185-187` applies as always: this file and the
> Console are two copies of the same copy, and the listing delta goes in the SAME release as the
> 78 AAB.

## Release notes — 3.7.0 (within 500 chars)

**Variant A — the default, and the only one backed by evidence in hand.** Paste this one unless the
add-back gate below has actually been cleared. It contains no comparative, so it needs no
"measured against our own previous version" closing and it cannot go stale while the ship gate holds.

> Dictation now cuts where you stop talking. A real voice-activity model listens frame by frame, so each sentence goes to be transcribed the moment you finish it instead of waiting on a timer — and a long unbroken stretch is cut at a small pause rather than mid-word. A new line shows what's being transcribed and how many are queued, on device and with cloud providers alike. The two lightest model tiers are retired; models you already installed are untouched.

(**460 chars.** Re-count every variant in this file after ANY edit — an em dash flattened to a
hyphen or a lost `'` changes the count:
`@(Get-Content docs\PLAY-LISTING.md -Encoding UTF8 | Where-Object { $_ -like '> Dictation now cuts*' }) | ForEach-Object { $_.Substring(2).Length }`
→ expected `460`, `482`, `490`.)

### Claim tracing — every clause, and what it rests on

| Clause in variant A | What it traces to |
| --- | --- |
| "Dictation now cuts where you stop talking" / "a real voice-activity model listens frame by frame" | **Feature, not a speed claim.** `audio/SileroEndpointer.kt` — a streaming per-frame Silero probe; `endpointer: silero (streaming probe)` is the session's construction line (`EndpointerFactory.kt:121`). The owner's 2026-08-27 session measured the probe at p50 2.3-2.4 ms / p99 5.8-6.1 ms with 0.2-0.3% of frames overrunning — it runs, and it runs well inside its 8 ms budget. |
| "instead of waiting on a timer" | **Mechanism, no number.** The 3.6.0 wall caps still exist and still bound a stretch nothing else cut (`SegmentCapPolicy`, `FIRST_SEGMENT_WALL_MS = 4_000L` / `MAX_SEGMENT_WALL_MS = 15_000L`, byte-identical to `main`); what changed is that a real endpoint now usually gets there first. The caps stayed in the `else if` branch on purpose — an S5 untouchable. |
| "a long unbroken stretch is cut at a small pause rather than mid-word" | **Behavioural, no number.** `CommitCadencePolicy.capCutRetainMs` via `capCutRetainWindowMs` (`FloatingBubbleService.kt:225-226`), applied in the cap branch at `:1943-1954`; pinned by `CapCutRetainWindowTest`. Deliberately NOT "never mid-word": the retain window is bounded by `CAP_CUT_MAX_RETAIN_MS = 3_000L`, and with no offer inside it the cap commits exactly as 3.6.0 did. |
| "A new line shows what's being transcribed and how many are queued, on device and with cloud providers alike" | **New behaviour (Workstream G), named for BOTH paths on purpose.** Labels at `FloatingBubbleService.kt:286-287` — `"Transcribing…"` and `"Transcribing… ($depth in queue)"`; the cloud-BATCH path now shows it too (`:264`), where 3.6.0 showed an empty strip. Names no provider and claims no speed — the same copy rule the label itself is pinned against. Without this line the owner reads the cloud strip as a regression. |
| "The two lightest model tiers are retired" | **Workstream H.** `eco` and `base` carry `retired = true` (`model/WhisperModel.kt:89,102`); `WhisperCatalog.pickable = entries.filter { !it.retired }`, so both leave the chooser. Both are 60 MB — "the two lightest" is literal. |
| "models you already installed are untouched" | **The cohort walk, verified from source in Workstreams H1/H2.** `retired` and `unsupported` are separate flags (`WhisperModel.kt:34-44`); eco/base take `retired` only. `ModelMigration.decide("eco")` returns `None` (no prompt, no re-download), and Settings' "no longer supported" card gates on `unsupported` via `WhisperModelManager.unsupportedInstalledModel()` — which eco/base never satisfy. Nothing on the eco/base path changes. |

### Deliberately absent

- **Any comparative at all**, and therefore no closing disclaimer. See the add-back gate below.
- **The GPU experiment.** Ships off for multilingual (the 2026-08-20 bench banned it there); not
  mentioned either way.
- **Any request-count or billing statement about cloud**, and any comparison to another app.
- **Locale steering in the chooser** (`ModelTierCopy.steerIdForLanguageTag`,
  `STEER_BADGE = "Best match for your language"`, both choosers, Workstream H3/H4). True and
  claimable — cut for character budget only, and it is a first-run surface that updating users
  mostly do not revisit. The listing delta below carries an optional bullet variant for it.

### ADD-BACK GATE — this REPLACES the plan's deletion contingency

The plan's S4 contingency read: *if the AFTER p50/p95 do not beat the BEFORE column, delete the
latency clauses.* **That gate is unusable in both directions and must not be run as written.** The
phone's installed vc77 is **preview.2** (`b65f4b7`) — a 3.7 build wearing a 3.6.0 version string —
so the acceptance sheet's BEFORE column is a 3.7-against-3.7 comparison. It can neither FIRE the
contingency (on a false BEFORE ≈ AFTER) nor CLEAR it (the mirror-image error).
**No column on `docs/superpowers/specs/2026-08-20-i-owner-acceptance.md` measures a 3.6.0 build.**

So the gate is inverted: the comparatives are OUT by default, and go IN only against a named
measurement. What the sheet CAN license on its own is "no regression since preview.2, and the tuning
and pacing work held" — which is a reason to ship, not a sentence to print.

**Variant B — restores "text lands sooner, and at a steady pace".** Requires BOTH:

1. Check 2's AFTER grid recorded and holding (`RESULT: PENDING` today). The result is the
   *variance*: on 3.6.0 the later-segment wait was spread over 0-15 s depending on where in the cap
   window you stopped; a p95 tight against p50 is the finding even if p50 barely moves.
2. **A genuine 3.6.0 comparison**, which the sheet does not contain — either a real 3.6.0 build
   measured deliberately (by stopwatch and `wall-clock cap` lines: a 3.6.0 build cannot emit
   `speechEndToVisible=` at all), or the 3.6.0-era figures from the 2026-08-20 session, cited as
   such.

> Dictation now cuts where you stop talking. A real voice-activity model listens frame by frame, so each sentence goes to be transcribed the moment you finish it instead of waiting on a timer — text lands sooner, and at a steady pace. A new line shows what's being transcribed and how many are queued, on device and with cloud providers alike. The two lightest model tiers are retired; models you already installed are untouched. Measured against our own previous version, same phone.

(**482 chars.** The mid-word clause pays for it; the closing disclaimer comes back with the
comparative, because the house rule is that every comparative says outright what it is measured
against.)

**Variant C — restores "Stopping is quicker when nothing is still queued".** Requires Check 3
(`RESULT: PENDING`) to show the idle-queue stop returning in tens of ms against the amplitude-era
stop flush, and that "before" figure cited from where it was measured. The condition in the sentence
is load-bearing and must not be dropped: the win is that the tail is pure silence and returns before
the encoder runs, and it does NOT apply when a real backlog exists.

> Dictation now cuts where you stop talking. A real voice-activity model listens frame by frame, so each sentence goes to be transcribed the moment you finish it instead of waiting on a timer. A new line shows what's being transcribed and how many are queued, on device and with cloud providers alike. The two lightest model tiers are retired; models you already installed are untouched. Stopping is quicker when nothing is still queued. Measured against our own previous version, same phone.

(**490 chars.**)

**B and C do not both fit — together they are 532 chars.** The budget admits at most ONE measured
comparative. Paste the one that actually has a measurement behind it; if both clear, B is the
headline (it is the release's whole point) and C waits for 3.7.1. Multilingual stays out of every
variant on purpose: it is measurably the slower tier (owner's own 3.6-4.3 s typical), and the notes
must not imply otherwise.

## Listing delta — 3.7.0 (owner applies in the Console, SAME release as the 78 AAB)

The FINAL 3.3.0 full description above is what is LIVE and stays as the record of it. Two bullets in
the `⚡ Built for speed` block go stale in 3.7.0 — the tier list (eco/base retired) and the VAD
bullet (the amplitude gate replaced by real endpointing). Replace exactly these two lines, nothing
else:

OLD:
• Model tiers from light-and-quick to maximum accuracy, including multilingual
• Voice activity detection: silence costs nothing

NEW:
• Two on-device model tiers: sharpest English, or 90+ languages
• Real voice-activity detection: your sentence is transcribed when you stop talking, and silence costs nothing

Arithmetic: **127 chars out, 173 in, +46**. The live full description measures **3,914 chars** as it
stands in this file (its own heading says 3,907 — a 7-char discrepancy in the older count; the
larger, conservative figure is used here), so the projection is **3,960 of the 4,000-char limit —
40 to spare.** Any further listing edit this release must re-count first.

**Why the first bullet is not a ladder.** A "from light-and-quick to maximum accuracy" list claims a
top-accuracy tier the chooser no longer offers: `WhisperCatalog.pickable` is exactly `pro`
(small.en) and `multi` (small, 90+ languages). `extreme` and `ultra` have been `retired` since
3.6.0 and 3.7 adds `unsupported = true` to both; `eco` and `base` retire here. Two tiers is the
truth, and "sharpest English" matches the in-app card copy ("The sharpest on-device English
dictation this app ships.", `ModelTierCopy`).

**Optional — the locale steer, if the owner wants it in the listing.** Replace the first NEW bullet
with `• Two on-device model tiers: sharpest English, or 90+ languages — steered by your language`
(90 chars): the delta becomes **+73 → 3,987, only 13 to spare.** That is a thin margin on a hard
Play limit and the count MUST be re-verified against the Console's own character counter before
saving. The default bullet above is the safe one.

Untouched on purpose: the GPU bullet ("GPU-accelerated Whisper on Snapdragon (Adreno)") stays true —
the 2026-08-20 bench validated the GPU default for the English tier while banning it for
multilingual, and the bullet claims neither tier. The short description, the privacy block and every
disclosure text are unchanged: 3.7.0 adds no permission, no data flow and no cloud behaviour — the
VAD probe consumes the same mic stream the session already records, and "no declaration and no
permission moved on this branch" is one of the S5 certification untouchables.
