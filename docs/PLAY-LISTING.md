# Play Store Listing — 3.2.0 Generational Release

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
