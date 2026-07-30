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

# DRAFT — 3.3.0 listing revisions (NOT LIVE — owner decision required)

> **Why this draft exists:** 3.3.0 ships optional cloud transcription (user's own OpenAI key)
> and flips the Data Safety form to voice recordings Collected=Yes / Shared=Yes / Optional.
> Google diffs the Data Safety form against the storefront: keeping "100% on-device",
> "No audio ever uploaded", and "Zero audio or text leaves your phone" beside that declaration
> is the textbook inconsistency rejection, and a false-claim metadata violation on its own.
>
> The on-device story is still substantially TRUE — it is the default, it is what every
> existing user gets, and it is unconditionally true for media/device audio — so both variants
> below keep it as the hero and add the cloud path honestly. **Neither is live. Nothing
> changes in the Console until the owner picks one and edits it there by hand.**
>
> ⚠️ **Checklist rule:** this file and the Play Console are TWO copies of the same copy.
> Any listing change happens in BOTH, in the same release. (This draft exists because the
> 3.2.0 listing was left untouched while the app gained cloud upload.)

## The four strings that must change

| Where | Live text (false once 3.3.0 ships) |
|---|---|
| Short description | "…100% on-device." |
| Full description, opener | "No audio ever uploaded — it works in airplane mode." |
| Privacy bullets | "Zero audio or text leaves your phone…" / "Internet used once: downloading your chosen model" |
| Feature graphic subline | "100% on your device." |

## Variant A — "on-device by default" (minimal change, defensive)

**Short (77 chars):**
> Offline voice typing, media transcription & read-aloud. On-device by default.

**Opener:** …All of it happens on your device by default. No account. No subscription. Your
audio is never uploaded unless you connect your own cloud provider key — and everything works
in airplane mode.

**Privacy block:**
• Speech recognition, voices, everything: on-device by default
• Your audio leaves your phone ONLY if you add your own cloud key and select it — verifiable, it's open source
• Audio captured from other apps never leaves your phone, in any configuration
• No account, no ads, no analytics, no tracking
• Internet used for: one model download — and nothing else unless you enable cloud

**Feature graphic subline:** "Private by default."

## Variant B — "your choice" (leans into the returning feature)

**Short (75 chars):**
> Voice typing, media transcription, read-aloud. On-device or your own cloud.

**Opener:** …All of it happens on your device by default — no account, no subscription, works
in airplane mode. Prefer the big cloud models? Bring your own key and switch anytime; your
audio then goes only to the provider you chose, under your own account.

**Adds a pillar block (this was the #1 review complaint when BYOK was removed):**
<b>🔑 Optional: bring your own cloud</b>
Add your own OpenAI API key and choose cloud transcription per session. Your key stays
encrypted on your phone, your audio goes only to your provider, and the on-device model takes
over automatically if the network drops. Remove the key and it all stops — instantly.

**Privacy block:** same as Variant A.

**Feature graphic subline:** "Your voice. Your device. Your choice."

## Draft release notes — 3.3.0 (within 500 chars)

> Cloud transcription is back — on your terms. Add your own OpenAI key and choose it per
> session; on-device stays the default and the automatic fallback. Audio from other apps is
> never uploaded, ever. Smoother long dictations and sharper accuracy on the models you
> already have. Your keys are stored encrypted; remove one and transmission stops instantly.

(No speed claims about cloud — measured on-device is 1.1–1.3 s for a 3 s utterance; cloud is
a tie at best. The pitch is accuracy and language coverage, never latency.)
