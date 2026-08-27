# 3.8 — Gemini 3.5 Transcribe + Language Routing (design)

Owner-approved direction 2026-08-26/27 (Gemini live: "we should definitely use this";
onboarding language: "we gotta force the user to select their target language on
onboarding"). Research basis: the 2026-08-26 Gemini API recon (ledger) and the language-
plumbing recon (ledger `[2026-08-26] 3.8 RECON`). Executes AFTER 3.7 ships; own branch
`feat/3.8-gemini-language`.

## Goal

Every engine — local and all four cloud providers — receives the user's language choice;
choosing a language is part of onboarding; Gemini becomes a first-class provider (live +
batch) on its new purpose-built STT models, replacing the generateContent prompt hack.

## Non-goals

NPU work (its own track); new local tiers; changing the 3.7 endpointer; provider UI
redesign beyond the rows this spec names.

## 1. The cloud-"en" leak fix (bug, ships first inside 3.8)

`FloatingBubbleService` computes the session language from the LOCAL installed model's
scope and feeds it to CLOUD engines too — a cloud user with the English-only default
`eco` installed gets `"en"` forced regardless of their selection. Fix: the ENGLISH-scope
override applies only when the engine that will transcribe is the local one (or the local
mirror of a fallback session — mirror language and cloud language diverge deliberately:
cloud gets the user's selection, the English-only local mirror keeps "en"). Pinned by a
JVM test on the extracted resolution function (extract `sessionLanguageFor(installedModel,
selection, engineKind)` beside the service's other pure helpers — the D7 pattern).

## 2. Onboarding language step (owner mandate)

- New onboarding page after model selection: "What language will you speak?" — the same
  57-entry list, but AUTO IS NOT THE DEFAULT-SELECTED ITEM: the list opens on the
  device's `Locale` language when we support it (auto remains available, one tap, with
  the honest subtitle "slower on multilingual models — detects per session").
- Selecting a concrete language = the existing `selected_language` pref; no new storage.
- The existing Home dashboard card stays (the one place to change it later); it gains
  the speed hint: "Choosing a language makes multilingual transcription faster."
- Existing installs (upgrade path): a one-time gentle prompt on the dashboard card if the
  setting is `auto` AND a multilingual tier is installed — never a blocking dialog.

## 3. Gemini batch: `gemini-3.5-transcribe`

Replace `GeminiStt`'s generateContent+prompt implementation with the purpose-built model
(HTTPS, same `x-goog-api-key` auth, same `SttProvider.transcribe(pcm, language)` seam).
Language: `languageCodes = [code]` when set, `[]` for auto. Keep the EmptyExpected
contract and error taxonomy identical to the other providers. Pricing note in the key
screen copy: ~$0.005/min paid tier, free tier exists.

## 4. Gemini live: `gemini-3.5-transcribe-live` (the headline)

- New `GeminiRealtimeProtocol` implementing the existing `RealtimeProtocol` seam over the
  Live API WebSocket (`wss://generativelanguage.googleapis.com/ws/...BidiGenerateContent`,
  `?key=` auth — BYOK works; the old ephemeral-token blocker is obsolete, remove the
  hard-error at the CLOUD_LIVE gate and flip `supportsStreaming = true` for GEMINI).
- Audio: PCM16/16kHz mono base64 chunks (`audio/pcm;rate=16000`) — our native format,
  ~100ms cadence from the existing capture path.
- Transcripts: `interimInputTranscription` → the partial/preview path;
  `inputTranscription` → committed text — mapping onto the exact seams OpenAI's protocol
  uses today (seq numbering, EmptyExpected, the finalize drain via `audioStreamEnd`).
- Language: `languageCodes: [code]` in the setup message; `[]` = auto (dynamic
  code-switching is native to the model — auto works mid-session, unlike local).
- THE 10-MINUTE SESSION CAP: transparent reconnect using the existing live-engine
  reconnect machinery (fresh setup message, seq numbering continues, no user-visible
  event; reconnect at ~9:30 proactively, not on the server's terminal close).
- Key handling: existing `ProviderAccounts`/SecureStore; the key rides the URL — never
  log the URL (the Soniox no-log discipline applies; `toString()` redaction).

## 5. Language to the other live providers

- Soniox live: already sends `language_hints` — unchanged.
- OpenAI + ElevenLabs live: their bootstraps discard the language today. Wire it IF the
  provider's current API has a field (verify at implementation against each API's docs);
  where no field exists, record provider-does-not-support in the provider catalog rather
  than silently dropping (the UI can then say "auto only" for that provider/mode cell).
- Batch file-transcription jobs: `RecordingMeta.language` is never set today — wire the
  selection through at job creation.

## 6. Code mapping

Our stored codes are bare ISO-639-1. Gemini accepts BCP-47; primary-subtag-only is valid
BCP-47 — send the bare code as-is, no region table. (Verify against the live API at
implementation; if a region is ever required, a minimal exceptions map, not a full table.)

## 7. Testing & acceptance

JVM: the resolution function truth table (engine kind x scope x selection); Gemini
protocol frame shapes pinned byte-exact (the RealtimeEvents test pattern); reconnect seq
continuity; languageCodes presence/absence per selection. Device (owner session): one
Gemini live session per language mode (auto + a concrete language), the 10-minute
rollover mid-dictation, the cloud-"en" fix verified with eco installed + cloud selected,
and the free-tier key path end to end. Listing copy claims wait for measured latency.

## Owner rulings (2026-08-27, closing the open questions)

1. Gemini live is NOT the default — one of four equals, with a **free-tier badge** on its
   provider row ("Free tier available — works with a free Gemini key"). Labeling, not
   defaulting.
2. Onboarding language step goes **BEFORE model download** ("after the model downloads,
   essentially everything is done") — the user locks language in first; auto stays in the
   list for those who want it.
3. **ORDERING (sovereignty-first):** within 3.8, the language routing + cloud-"en" leak
   fix + onboarding step execute FIRST; the Gemini live provider (§4) moves to the END of
   the plan — local sovereignty outranks cloud features, and the NPU track outranks
   Gemini live in the owner's roadmap. Gemini batch (§3) rides with the routing work
   since it shares the language seam.
