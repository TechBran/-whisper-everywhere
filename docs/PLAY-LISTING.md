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
