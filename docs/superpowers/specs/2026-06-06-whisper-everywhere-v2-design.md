# Whisper Everywhere v2 — Design Spec

**Date:** 2026-06-06
**Status:** Approved (design), pending implementation plan

## Summary

Upgrade Whisper Everywhere from batch transcription to **realtime streaming
speech-to-text**, add a **reactive EQ waveform** for live visual feedback, and
add a **"Read aloud" (TTS)** action that appears in the system text-selection
toolbar. All features use the user's existing OpenAI API key.

Three features, one cohesive release:

1. Realtime streaming transcription (replaces batch `whisper-1`).
2. Reactive EQ waveform shown while recording.
3. TTS "Read aloud" via the Android `PROCESS_TEXT` selection action.

## Goals

- Text appears **live** in the focused field as the user speaks, injected per
  sentence/phrase; falls back to the clipboard when no field is focused.
- A polished, voice-reactive waveform gives clear "we're recording" feedback.
- Users can select any text anywhere and have it read aloud in an HD voice,
  with a floating mini-player to control playback.
- Same single OpenAI API key for STT and TTS.

## Non-goals

- WebRTC transport (rejected — heavy native stack, browser-oriented).
- Batch transcription fallback on stream failure (explicit decision: show error
  + retry instead).
- Auto-stop on silence (explicit decision: manual stop only).
- Offline/on-device transcription.

---

## Decisions locked during brainstorming

| Topic | Decision |
|---|---|
| Live injection granularity | **Per sentence/phrase** (driven by server VAD `completed` events) |
| Recording stop | **Manual stop only** (tap to start, tap to stop) |
| STT model (default) | **`gpt-realtime-whisper`** (lowest latency) |
| Stream-failure behavior | **Show error + let user retry** (no batch fallback) |
| Waveform style | **Reactive EQ bars** (evolve current `WaveformView`) |
| TTS playback UX | **Mini player overlay** (play/pause, stop, speed) |
| TTS quality | **HD voice model** (`gpt-4o-mini-tts`) |
| TTS voice selection | **Dropdown in main menu/Settings**, persisted |
| Transport | **OkHttp WebSocket** (already a dependency) |

---

## A. Architecture & approach

**Approach: OkHttp WebSocket streaming.** OkHttp is already a dependency, so no
new native libraries. Raw PCM from the mic streams to OpenAI's realtime
transcription endpoint; the same audio buffer feeds the waveform.

### Components

- **`StreamingAudioRecorder`** — replaces the WAV-writing behavior in the
  current `AudioRecorder`. Captures `AudioRecord` PCM16 and emits:
  - PCM chunks (~50–100 ms) as a `Flow<ByteArray>` for the WebSocket.
  - An `amplitude: StateFlow<Int>` for the waveform (computed from the same
    buffer it streams — no double read of the mic).
- **`RealtimeTranscriptionClient`** — owns the WebSocket lifecycle and the
  realtime JSON event protocol. Exposes a callback/Flow of transcription events
  (delta, completed, error, connection state).
- **`BarWaveformView`** — the reactive EQ waveform (evolves
  `ui/components/WaveformView.kt`).
- **`FloatingBubbleService`** — rewired from the batch flow to the streaming
  flow; drives the waveform and routes finished sentences to injection.
- **TTS subsystem** — `ReadAloudActivity` (PROCESS_TEXT entry point),
  `TtsService` (foreground, calls speech API + plays audio),
  `TtsPlayerOverlay` (mini player).
- **Retired:** the batch transcribe path in `data/api/WhisperApiService.kt`. The
  file may be deleted or left unused; the realtime client supersedes it.

---

## B. Feature 1 — Realtime streaming transcription

### Audio capture

- `AudioRecord`, **24 kHz mono PCM16** (the realtime API's expected format; up
  from today's 16 kHz).
- Streams ~50–100 ms chunks via a `Flow`; computes amplitude from the same
  buffer for the waveform.

### WebSocket session

- Connect: `wss://api.openai.com/v1/realtime?intent=transcription`
  (GA form may use `?model=…`; beta builds also send header
  `OpenAI-Beta: realtime=v1`).
- Header: `Authorization: Bearer <api key>`.
- Session config (sent as a session-update event after open):
  - session type `transcription`
  - `input_audio_format: pcm16`
  - transcription model `gpt-realtime-whisper`
  - `turn_detection: server_vad` (used purely to segment sentences for
    injection — recording itself still ends only on manual stop)
  - `language` from the existing language dropdown (`null` for auto)

### Event flow

- **Client → server:** `input_audio_buffer.append` (base64 PCM) continuously
  while recording; `input_audio_buffer.commit` on stop.
- **Server → client:**
  - `conversation.item.input_audio_transcription.delta` → drives a subtle
    "transcribing" cue (not injected, to avoid flicker from partial revisions).
  - `conversation.item.input_audio_transcription.completed` → inject that
    finished sentence/phrase into the focused field via the existing
    `WhisperAccessibilityService.injectTextWithResult()`, or copy to clipboard
    if no field is focused (reuse existing `BubbleContext` routing).

### Stop & teardown

- Tap to stop → stop the mic, send `commit`, wait for the final `completed`,
  then close the socket.

### Error handling

- Connection failure or mid-session drop → bubble enters ERROR state + toast;
  user taps to retry. No batch fallback. Any sentences already injected remain.

### Bubble state machine

`IDLE → CONNECTING (brief) → RECORDING (waveform live) → FINALIZING → IDLE`.
The long batch "PROCESSING" spinner largely disappears because text arrives
live; a short FINALIZING state covers the wait for the last `completed`.

---

## C. Feature 2 — Reactive EQ waveform

- `BarWaveformView`: ~13–15 bars, red→purple→blue logo gradient, **spring-physics
  smoothing** so bars settle naturally, a gentle idle shimmer before sound, and
  sharp reactions to amplitude peaks.
- The bubble **expands into a pill** while recording to give the bars room, then
  collapses back to the circular bubble on stop (animated).
- Driven by `StreamingAudioRecorder.amplitude`.

---

## D. Feature 3 — TTS "Read aloud"

### Trigger

- `ReadAloudActivity` registered with an `android.intent.action.PROCESS_TEXT`
  intent-filter, label **"Read aloud"**. This makes it appear in the system
  Copy/Cut/Share text-selection toolbar across apps. The activity is transparent
  (no visible UI), reads `EXTRA_PROCESS_TEXT`, and hands off to `TtsService`.

### Speech synthesis & playback

- `TtsService` (foreground service, `mediaPlayback` type) calls
  `/v1/audio/speech` with:
  - model **`gpt-4o-mini-tts`** (HD voice)
  - the user-selected `voice`
  - the user-selected `speed`
- Streams the returned audio and plays it. Player: Media3/ExoPlayer for clean
  streaming, or `MediaPlayer` to keep dependencies minimal (decided at plan
  time).

### Mini player overlay

- `TtsPlayerOverlay`: floating control styled like the bubble, with
  **play/pause, stop, and a speed toggle**. Auto-dismisses when playback ends or
  on stop.

---

## E. Settings & UX additions

- **Voice dropdown** in the main menu (OpenAI voices: alloy, ash, ballad, coral,
  echo, fable, nova, onyx, sage, shimmer, verse), persisted in
  `PreferencesManager`. Speed also persisted.
- Keep the existing **language dropdown** — now feeds the realtime `language`
  hint.
- **Manifest:** add the `PROCESS_TEXT` activity; add a `mediaPlayback`
  foreground-service type for `TtsService`. Overlay/mic permissions already
  present.

---

## F. Testing

- **Unit tests:**
  - Realtime event building/parsing (session config JSON, delta/completed
    parsing, error events).
  - Amplitude computation from PCM buffer.
  - Sentence-routing logic (focused field vs clipboard).
  - `PROCESS_TEXT` text extraction.
- **On-device manual:**
  - Live per-sentence injection across several apps (chat, notes, browser).
  - No-field → clipboard path.
  - Selection → "Read aloud" → mini-player controls (play/pause/stop/speed).
  - Error/retry path (airplane mode mid-session).

---

## G. Suggested implementation sequencing

1. **Streaming STT + reactive waveform** — tightly coupled core; ship first.
2. **TTS "Read aloud"** — independent subsystem; can land after.

Each sub-feature can be validated on-device before moving to the next.
