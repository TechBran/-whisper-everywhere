# Cloud TTS — three providers, voice picker, disclosure v3

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a user route *read-aloud* through a cloud voice (OpenAI, Google Gemini, ElevenLabs) they configured, behind the exact one-way local-fallback philosophy that already governs cloud STT. Local Kokoro stays the default and the fallback. Each clause unit from the just-landed `ClauseSplitter` feed becomes one bounded cloud synthesis request producing 24 kHz PCM16 that drops into the SAME playback bank. A cloud voice failure mid-read falls back to the local voice and KEEPS READING. Because selected read-aloud TEXT now leaves the device — a NEW data class — the disclosure meaning changes and the flag bumps `cloud_disclosure_accepted_v2` → `_v3` (re-prompt), the privacy read-aloud carve-out flips, and Play's Data Safety gains a text-sharing entry.

**Architecture:** Unchanged playback path. The insertion seam is one call — `TtsEngine.speak()`'s producer loop at line 414 (`engine.generateWithCallback(text = unit, …)`). A new `TtsProvider.synth(unit, voiceId, speed, onPcm)` mirrors `SttProvider`: narrow, stateless, one bounded unit in, PCM16 chunks out via a callback whose `false` return cancels (identical semantics to sherpa's `0`). The `for (unit in ClauseSplitter.plan(clean))` loop gains a per-unit `try`: on cloud failure it latches, toasts once, and **re-runs that same unit through the local sherpa path** — reading never stops. Bank, AudioTrack thread, cancel/generation machinery, backpressure, diagnostics: untouched.

**Tech Stack:** Kotlin 2.0.21, OkHttp 4.12.0 (**pinned**), kotlinx-serialization-json 1.7.3, JUnit 4. **No new dependencies.** ElevenLabs mp3 fallback decodes via the EXISTING `AudioDecoder` (batch), not a new decoder.

## Global Constraints (binding — most carried from C1/C2a/C2b)

- **Read-aloud TEXT leaving the device is a NEW data class.** Selected text may go ONLY to the provider the user chose for TTS, only with a stored key, and only after **disclosure v3**. The v3 selector gates a cloud VOICE.
- **Local Kokoro stays default AND fallback.** Cloud voice failure mid-read → local voice, KEEPS READING (one-way valve, same as STT). Fatal errors LATCH; one toast per latch (reuse `notifiedFatalKind`).
- **Every cloud TTS request is one clause-bounded unit from the `ClauseSplitter` feed** — never the whole selection.
- **No credential and no TEXT CONTENT in logcat** — lengths + status codes only. (STT logs status codes only; TTS adds unit *length*, never the unit.)
- **No new dependencies; OkHttp 4.12.0 pinned.** ElevenLabs mp3 fallback decodes via existing `AudioDecoder` (batch), not a new decoder.
- **No speed claims** in any user-facing copy. Voice previews cost real money — caption them honestly ("previews use your key").
- **`org.json` BANNED / `android.util.Base64` BANNED** (`unitTests.isReturnDefaultValues = true`). Use `kotlinx.serialization` + `java.util.Base64`. Framework classes (`AudioTrack`, `MediaCodec`, `android.util.Log`) are untestable in JVM — keep them out of the pure-logic units.
- **MediaProjection carve-out untouched. Live dictation/STT paths untouched. Batch untouched.**
- **`java` NOT on PATH.** PowerShell: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio1\jbr"`; `.\gradlew.bat --no-daemon`. `assembleRelease` must stay green (R8). **NEVER `connectedAndroidTest`/`installDebug`** — instrumented = compile-check only.
- **Branch `main`. Commit ONLY named files, never `git add -A`. Retry once on `index.lock`.**
- **Baseline: 459 tests / 0 failures, HEAD `8ecfd17`**, `assembleDebug` + `assembleRelease` both green. Three copy tests in `CloudProvidersScreenLogicTest` INVERT under v3 (Task 7) and must be re-greened there, not deleted.

---

## Pinned provider facts (verified against live docs 2026-07-30)

**OpenAI** — `POST https://api.openai.com/v1/audio/speech`, Bearer.
- Model **`gpt-4o-mini-tts`** (pinned). 13 voices: alloy ash ballad coral echo fable nova onyx sage shimmer verse **marin cedar** (docs recommend marin/cedar → flag "Recommended").
- `response_format=pcm` → **headerless 24 kHz 16-bit LE mono** — drops straight into the bank, no decode. Body is BINARY.
- **Price (verified 2026-07-30):** $0.60 / 1M input text tokens, $12 / 1M audio-output tokens, ≈ **$0.015 / min** of audio. Caption: "About $0.015 per minute — billed to your OpenAI key."

**ElevenLabs** — `POST https://api.elevenlabs.io/v1/text-to-speech/{voice_id}`, header `xi-api-key`.
- **Flash model id re-verified 2026-07-30: `eleven_flash_v2_5`** (current low-latency, `can_do_text_to_speech`, ~75 ms, 32 languages, 40k-char limit). Default fallback model `eleven_multilingual_v2`.
- `output_format=pcm_24000` **IF the account tier allows** (Creator+); otherwise the universal `mp3_44100_128` → decode via existing `AudioDecoder` → `Resampler` to 24 kHz. **Plan BOTH:** try `pcm_24000`; on 4xx tier rejection (401/403 with a format/tier marker) retry the same unit with `mp3_44100_128`. 422 = validation → BadUnit.
- Voices: `GET /v1/voices` (already fetched at key validation — reuse the fetch shape; **cache per app session**). Dynamic catalog.
- **Price (verified 2026-07-30):** `eleven_flash_v2_5` = **0.5 credit/char** (discounted API), `eleven_multilingual_v2` = 1 credit/char. Credit $ value by plan: Starter $0.20/1k, Creator $0.18/1k, Pro/Scale/Business $0.17/1k → flash ≈ **$0.085–0.10 per 1k chars** on Pro+. Caption: "≈ half a credit per character (your ElevenLabs plan) — previews use your key."

**Gemini** — `POST https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent`, header `x-goog-api-key` (NEVER `?key=`). Chosen over `interactions` for the SAME reason C2b chose `generateContent`: the whole key-validation + error taxonomy stack is already proven on it.
- **Model order (pinned, all PREVIEW → reachability risk):** `gemini-3.1-flash-tts-preview` → `gemini-2.5-flash-preview-tts`. A **404 / `MODEL_UNAVAILABLE`** on the first → try the second; both 404 → latch Fatal `MODEL_UNAVAILABLE` (C2b pattern). Honest failure copy — no promise a preview stays reachable.
- Request: `generationConfig.responseModalities:["AUDIO"]`, `speechConfig.voiceConfig.prebuiltVoiceConfig.voiceName`. Response: `candidates[0].content.parts[0].inlineData.data` = **base64 PCM16 mono 24 kHz** (decode with `java.util.Base64`, NOT `android.util.Base64`).
- 30 static voices: Zephyr Puck Charon Kore Fenrir Leda Orus Aoede Callirrhoe Autonoe Enceladus Iapetus Umbriel Algieba Despina Erinome Algenib Rasalgethi Laomedeia Achernar Alnilam Schedar Gacrux Pulcherrima Achird Zubenelgenubi Vindemiatrix Sadachbia Sadaltager Sulafat.
- **Price:** all TTS models are **PREVIEW** — no committed GA price on the public pricing page (published preview rate ≈ $0.50/1M text-in, $10/1M audio-out, **subject to change**). Caption honestly: "Preview model — Google's pricing is not final; billed to your Gemini key."

---

## File Structure

| File | Change |
|---|---|
| `net/HttpTransport.kt` | **+** `postForBytes(...) : HttpResultBytes` — binary-body JSON POST |
| `tts/cloud/TtsProvider.kt` | **NEW** — `TtsProvider` seam + `TtsError`/`TtsResult` (reuse `FatalKind`) |
| `tts/cloud/OpenAiTts.kt` | **NEW** — `gpt-4o-mini-tts`, pcm, 13-voice static catalog |
| `tts/cloud/ElevenLabsTts.kt` | **NEW** — `eleven_flash_v2_5`, pcm_24000 → mp3 fallback, dynamic catalog |
| `tts/cloud/GeminiTts.kt` | **NEW** — preview model order, base64 PCM, 30-voice static catalog |
| `tts/cloud/TtsVoiceCatalog.kt` | **NEW** — `CloudVoice(providerId, voiceId, displayName, recommended)`, session cache |
| `tts/cloud/TtsProviderFactory.kt` | **NEW** — mirror of `SttProviderFactory` |
| `tts/TtsEngine.kt` | seam at 412–417: per-unit cloud `try` + one-way local fallback |
| `tts/TtsController.kt` | resolve cloud vs local voice from prefs |
| `data/local/PreferencesManager.kt` | `ttsProviderId`, per-provider `ttsCloudVoiceId`; disclosure key → `_v3` |
| `provider/ProviderCatalog.kt` | **+** `TTS_CAPABLE_PROVIDERS` |
| `ui/screens/SettingsScreen.kt` | voice picker: provider tabs + cloud voice list + Preview + engine selector |
| `ui/screens/CloudProvidersScreen.kt` | `ttsSelectableProviders`, v3 gating, disclosure copy gains read-aloud sentence |
| `assets/privacy_policy.html` + `docs/privacy.html` | §6 read-aloud paragraph FLIPS (pair stays identical) |
| `docs/PLAY-DECLARATIONS.md` | ADD + RECORD text-sharing Data Safety determination |
| tests (5 new files + 3 inverted assertions) | see each task |

---

## Task 1 — `HttpTransport.postForBytes`: a binary-body POST

**The gap (recon §6):** `postJson` reads the body via `response.body?.string()` — lossy for the binary audio OpenAI/ElevenLabs/Gemini-decoded return. `HttpResult.Ok` carries only `String`. Add a bytes-carrying result and one method that reads `body.bytes()`. A clause unit is small, so a fully-buffered read is acceptable for v1 (no streaming overload).

### 1a. TDD — `HttpTransportBytesTest` (FakeHttpTransport already in test set)

Extend `FakeHttpTransport` with a `postForBytes` stub returning a queued `HttpResultBytes`. Tests:
- `ok_returns_raw_bytes_unmodified` — a byte array containing `0x00 0xFF 0x80` round-trips identical (proves no `.string()` corruption).
- `http_error_carries_code_and_string_body` — 401 → `HttpErrorBytes(401, "…")` (error bodies are text/JSON, kept as String for `classify`).
- `network_error_wraps_cause`.

### 1b. Implement

```kotlin
sealed interface HttpResultBytes {
    data class Ok(val code: Int, val bytes: ByteArray) : HttpResultBytes
    data class HttpError(val code: Int, val body: String) : HttpResultBytes
    data class NetworkError(val cause: Throwable) : HttpResultBytes
}

// in interface HttpTransport:
/**
 * POST a JSON body and read the response as RAW BYTES. Separate from [postJson] because a TTS
 * endpoint returns a binary audio body (headerless PCM16 / mp3) that `.string()` corrupts. On a
 * non-2xx the body is an error JSON, so it is read as String for classification.
 */
suspend fun postForBytes(
    url: String,
    headers: Map<String, String>,
    jsonBody: String,
    timeoutMs: Long = DEFAULT_TTS_TIMEOUT_MS,
): HttpResultBytes
```

`OkHttpTransport.postForBytes` mirrors `postJson` EXACTLY for the credential-safety discipline — **body + headers built INSIDE the `try`** (C1: `Headers.checkValue` embeds the raw header value for `xi-api-key`/`x-goog-api-key`), **`CancellationException` caught FIRST** and rethrown before the broad `catch`. Success path: `val bytes = response.use { it.body?.bytes() ?: ByteArray(0) }`; on `isSuccessful` → `Ok(code, bytes)`, else read `it.body?.string().orEmpty()` for `HttpError`. Add `const val DEFAULT_TTS_TIMEOUT_MS = 45_000L` — a generous read timeout per unit (one clause, but a slow cellular link + preview model can be slow); the per-slice bank keeps playback going meanwhile.

> **Self-review:** binary read only in the new method; `postJson`/`postMultipart`/`get` untouched → no STT regression. Pin: `ok_returns_raw_bytes_unmodified` is the guard that `.string()` was not reintroduced.

---

## Task 2 — `TtsProvider` seam + `OpenAiTts`

### 2a. The seam — `tts/cloud/TtsProvider.kt`

Mirror `SttProvider`'s narrowness. **Reuse `FatalKind`** (`INVALID_KEY`, `OUT_OF_CREDIT`, `FORBIDDEN`, `MODEL_UNAVAILABLE`) — it already carries exactly the TTS fatal cases. `onPcm` returns `false` to cancel (== sherpa's `0`).

```kotlin
package com.whispereverywhere.tts.cloud

import com.whispereverywhere.provider.ProviderId
import com.whispereverywhere.transcription.cloud.FatalKind   // reused, not redefined

/** Why a cloud synthesis attempt failed. Mirrors SttError; drives the same one-way fallback. */
sealed interface TtsError {
    data object Offline : TtsError
    data class Fatal(val kind: FatalKind, val message: String) : TtsError
    data class Transient(val retryAfterMs: Long?) : TtsError
    /** THIS unit is unacceptable (empty, too long, 422). Does NOT disable the provider. */
    data object BadUnit : TtsError
}

sealed interface TtsResult {
    /** All PCM for the unit was delivered through onPcm. */
    data object Done : TtsResult
    /** onPcm returned false — the caller cancelled; not an error. */
    data object Cancelled : TtsResult
    data class Failed(val error: TtsError) : TtsResult
}

/**
 * Turns ONE clause-bounded unit into 24 kHz PCM16 mono, streamed as ShortArray chunks through
 * [onPcm]. Deliberately narrow: no session, no state. Everything above (ordering, the one-way
 * local fallback, the latch, the toast) is provider-agnostic and lives at the TtsEngine seam.
 *
 * NEVER log the key, the headers, or the unit text — unit LENGTH and status codes only.
 */
interface TtsProvider {
    val id: ProviderId
    /** Fixed output rate contract the bank relies on. All three providers emit 24 kHz. */
    val sampleRate: Int   // = 24_000
    /** @return false from onPcm to cancel (identical semantics to sherpa's 0). */
    suspend fun synth(unit: String, voiceId: String, speed: Float, onPcm: (ShortArray) -> Boolean): TtsResult
}
```

Add a shared `PcmBytes.toShortArrayLE(bytes): ShortArray` helper (little-endian 16-bit) in this file — used by all three adapters; unit-testable in JVM.

### 2b. TDD — `OpenAiTtsTest`

FakeHttpTransport returns queued `HttpResultBytes`. Tests (no network, no framework):
- `pcm_body_is_delivered_as_le_short_chunks` — a 6-byte body `[0x00,0x00, 0xFF,0x7F, 0x00,0x80]` → shorts `[0, 32767, -32768]` collected from `onPcm`.
- `onPcm_returning_false_stops_and_returns_Cancelled`.
- `http_401_is_Fatal_INVALID_KEY`; `http_403_is_Fatal_FORBIDDEN`; `429_with_quota_marker_is_Fatal_OUT_OF_CREDIT`, `plain_429_is_Transient`; `400_or_422_is_BadUnit`; `network_error_is_Offline`.
- `request_body_pins_model_and_pcm_format` — asserts the JSON contains `"model":"gpt-4o-mini-tts"` and `"response_format":"pcm"` (parse the captured body with kotlinx.serialization, not string-match on the key).
- `voice_catalog_has_13_and_flags_marin_cedar_recommended`.

### 2c. Implement `OpenAiTts`

```kotlin
class OpenAiTts(
    private val transport: HttpTransport,
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
) : TtsProvider {
    override val id = ProviderId.OPENAI
    override val sampleRate = 24_000

    override suspend fun synth(unit, voiceId, speed, onPcm): TtsResult {
        if (unit.isBlank()) return TtsResult.Failed(TtsError.BadUnit)
        val provider = ProviderCatalog.byId(ProviderId.OPENAI)
        val headers = mapOf(provider.authHeaderName to provider.authHeaderValue(apiKey))
        val body = JSON.encodeToString(SpeechReq(model, voiceId, unit, "pcm", speed))
        return when (val r = transport.postForBytes(ENDPOINT, headers, body)) {
            is HttpResultBytes.NetworkError -> TtsResult.Failed(TtsError.Offline)
            is HttpResultBytes.HttpError    -> TtsResult.Failed(classify(r.code, r.body))
            is HttpResultBytes.Ok -> {
                val shorts = PcmBytes.toShortArrayLE(r.bytes)
                if (!onPcm(shorts)) TtsResult.Cancelled else TtsResult.Done
            }
        }
    }
    // classify: identical status-code map to OpenAiStt; logs `openai tts http $code` + unit length ONLY.
    companion object {
        const val DEFAULT_MODEL = "gpt-4o-mini-tts"   // verified 2026-07-30; response_format=pcm → 24k LE
        private const val ENDPOINT = "https://api.openai.com/v1/audio/speech"
        private val JSON = Json { encodeDefaults = true }
    }
}
```

`OpenAiTtsVoices.ALL` = the 13, each `CloudVoice(OPENAI, id, displayName, recommended = id in {"marin","cedar"})`. `speed` maps to the API `speed` field (0.25–4.0; clamp).

> **Self-review:** OpenAI PCM needs NO decoder → no `AudioDecoder` dependency here. `classify` reuses the STT status map so 429-quota discipline is identical. Pin: `request_body_pins_model_and_pcm_format`.

---

## Task 3 — `ElevenLabsTts` (pcm_24000 → mp3 fallback via existing `AudioDecoder`)

### 3a. The mp3-fallback seam gap (recon §6 secondary)

`AudioDecoder.decodeTo(context, uri, sink, onProgress)` takes a **SAF `Uri`**, not bytes. For in-memory mp3 fallback bytes, write them to a **temp file in `context.cacheDir`** and hand `Uri.fromFile(tmp)` to the existing decoder, then delete the temp file in a `finally`. This reuses the batch decoder verbatim (constraint) without a new Uri-less overload — the decoder → `Resampler` already lands 24 kHz PCM16. Isolate this in a `decodeMp3ToPcm24k(context, bytes): ShortArray` helper so `synth`'s logic stays testable and the framework call is quarantined. `ElevenLabsTts` therefore takes a `Context` (application context, held weakly is unnecessary — it is the app context).

### 3b. TDD — `ElevenLabsTtsTest`

Framework-free tests drive the pcm path and the fallback DECISION (not the MediaCodec decode, which is instrumented compile-check only):
- `pcm_24000_success_delivers_shorts`.
- `tier_rejection_on_pcm_triggers_mp3_retry` — first `postForBytes` → `HttpError(401, "…output_format… not available on your tier…")` (or the documented format/tier marker) → asserts a SECOND call is made with `output_format=mp3_44100_128` in the URL query. Fake returns mp3 bytes; the decode boundary is stubbed via an injected `decode: (ByteArray)->ShortArray` seam (defaulting to the real `decodeMp3ToPcm24k`) so the logic test asserts "mp3 path taken", the real decode is validated on device.
- `plain_401_without_format_marker_is_Fatal_INVALID_KEY` — a real bad key must NOT be misread as a tier rejection (the marker discriminates, exactly like STT's 429 quota-marker split).
- `422_is_BadUnit`; `voice_catalog_parses_v1_voices_json`; `voice_catalog_is_cached_per_session` (second `voices()` call makes no second GET).

### 3c. Implement

- Endpoint `…/v1/text-to-speech/{voiceId}?output_format=pcm_24000`, header `xi-api-key`, body `{"text": unit, "model_id": "eleven_flash_v2_5"}` (+ `voice_settings` if speed maps; ElevenLabs has no direct speed param → speed is applied by the LOCAL path only, so cloud EL ignores `speed` and the UI note says so honestly — no fake control).
- On tier-rejection marker → retry with `?output_format=mp3_44100_128`, decode → 24 kHz shorts.
- `classify`: 401/403 → Fatal (INVALID_KEY/FORBIDDEN) UNLESS the format/tier marker is present (→ mp3 retry, not fatal); 429-quota → OUT_OF_CREDIT; 422 → BadUnit; 5xx → Transient; network → Offline. Log `elevenlabs tts http $code fmt=pcm|mp3` + unit length, never the unit.
- Dynamic voices: `GET /v1/voices`, parse `voices[].voice_id` + `name` with kotlinx.serialization; **cache in the instance for the app session** (recon: reuse the validation fetch shape).

> **Self-review:** mp3 fallback uses the EXISTING `AudioDecoder` (constraint met) via a temp-file Uri; no new decoder, no new dep. Pin: `tier_rejection_on_pcm_triggers_mp3_retry` + `plain_401_without_format_marker_is_Fatal_INVALID_KEY` (the discriminator that stops a bad key masquerading as a tier issue). Flash id `eleven_flash_v2_5` is the single pinned constant.

---

## Task 4 — `GeminiTts` (preview model order, base64 PCM)

### 4a. TDD — `GeminiTtsTest`

- `flash_preview_success_decodes_base64_pcm` — response `candidates[0].content.parts[0].inlineData.data` = base64 of `[0,32767,-32768]` LE → those shorts (decode via **`java.util.Base64`**; a test that would silently pass under `android.util.Base64`'s JVM default is the exact C1 trap).
- `first_model_404_falls_back_to_second_model` — first `postForBytes` → `HttpError(404, "…MODEL_UNAVAILABLE…")` → SECOND call uses `gemini-2.5-flash-preview-tts`; fake returns audio → `Done`.
- `both_models_404_is_Fatal_MODEL_UNAVAILABLE` (latch).
- `gemini_400_API_KEY_INVALID_is_Fatal_INVALID_KEY` (Gemini rejects bad keys with **400, not 401** — the C1/C2b trap; discriminate on the `API_KEY_INVALID` marker, not the code).
- `no_audio_part_is_Transient_not_empty` (a refusal/safety finishReason is not silence).
- `voice_catalog_has_30`.

### 4b. Implement

- URL `…/v1beta/models/{model}:generateContent`, header `x-goog-api-key` — **NEVER a `?key=` URL** (logging constraint). Body: `contents:[{parts:[{text: unit}]}]`, `generationConfig:{responseModalities:["AUDIO"], speechConfig:{voiceConfig:{prebuiltVoiceConfig:{voiceName: voiceId}}}}`.
- Model order `MODELS = listOf("gemini-3.1-flash-tts-preview", "gemini-2.5-flash-preview-tts")`; walk on 404/`MODEL_UNAVAILABLE` (C2b pattern). Response path `candidates[0].content.parts[0].inlineData.data` → base64 → LE shorts.
- `classify` reuses the C2b Gemini 400-marker discipline. Gemini has no speed param → ignore `speed`, note honestly in UI.
- `GeminiTtsVoices.ALL` = the 30 static names.

> **Self-review:** `java.util.Base64` pinned; all-preview reachability handled by fallback + honest copy; header-auth only. Pin: `first_model_404_falls_back_to_second_model` + `both_models_404_is_Fatal_MODEL_UNAVAILABLE`.

---

## Task 5 — `TtsProviderFactory`, prefs, and the engine seam integration

### 5a. `TTS_CAPABLE_PROVIDERS` + factory

`ProviderCatalog.kt`: `val TTS_CAPABLE_PROVIDERS = setOf(OPENAI, GEMINI, ELEVENLABS)` (all three `supportsTts = true` already; `ProviderCatalogTest` still green). `TtsProviderFactory.create(id, transport, apiKey, context): TtsProvider` — exhaustive `when` over `ProviderId`, mirroring `SttProviderFactory` (context threaded only because ElevenLabs needs `cacheDir` for the mp3 temp file).

### 5b. Prefs

`PreferencesManager.kt`:
- `ttsProviderId: String?` (null = local Kokoro; key `KEY_TTS_PROVIDER_ID = "tts_provider_id"`) — parallel to `ttsVoiceId: Int` which stays Kokoro-only.
- Per-provider cloud voice: `ttsCloudVoiceId(id): String?` / `setTtsCloudVoiceId(id, voiceId)` namespaced `tts_cloud_voice_{id.name}` (a `speakerId: Int` cannot carry a provider voice string — recon §2).
- Disclosure key **`KEY_CLOUD_DISCLOSURE_ACCEPTED = "cloud_disclosure_accepted_v3"`** (was `_v2`). The MF3 rule: bump the constant only; `_v2` stays in the store; an unset `_v3` re-prompts everyone. `cloudDisclosureAccepted` reads/writes the new key.

TDD `PreferencesTtsCloudTest`: default provider is null (local); set/round-trip provider + per-provider voice; removing a provider's key clears its selection if selected (mirror `selectionAfterKeyRemoval`, extended to TTS).

### 5c. The seam — `TtsEngine.speak()` producer loop (recon §1, lines 412–417)

The ONLY behavioral change to the pipeline. Today the loop has **no per-unit catch**: a throw abandons the whole read. Wrap each unit so cloud failure **latches once, toasts once, and re-synthesizes THAT unit locally, then keeps going**. The callback, bank, AudioTrack thread, `cancelled()`, backpressure, and diagnostics are untouched — the cloud path appends to the SAME `store` via the same `ShortArray` shape.

```kotlin
// resolved once before the loop (from TtsController → prefs):
//   cloudProvider: TtsProvider?  cloudVoiceId: String?   (null,null ⇒ pure local, exact prior path)
var latchedFatal: FatalKind? = null   // one toast per latch, reuse the STT pattern

for (unit in ClauseSplitter.plan(clean)) {
    if (cancelled()) break
    val usedCloud = cloudProvider != null && latchedFatal == null
    if (usedCloud) {
        val res = runBlocking {              // executor thread; synth is suspend, bounded by DEFAULT_TTS_TIMEOUT_MS
            cloudProvider!!.synth(unit, cloudVoiceId!!, speed) { pcm -> appendToBank(pcm); !cancelled() }
        }
        when (res) {
            is TtsResult.Done, TtsResult.Cancelled -> { /* keep reading / stop */ }
            is TtsResult.Failed -> {
                // ONE-WAY VALVE: fall the FAILED unit back to local, and keep reading the rest locally.
                if (res.error is TtsError.Fatal) latchedFatal = res.error.kind   // latch: no more cloud this read
                onCloudFallback(res.error)                                        // one toast per latch (see 5d)
                engine.generateWithCallback(text = unit, sid = localSpeakerId, speed = speed, callback = callback)
            }
        }
    } else {
        engine.generateWithCallback(text = unit, sid = localSpeakerId, speed = speed, callback = callback)
    }
}
```

`appendToBank(pcm)` is exactly the `synchronized(store){ store.add; storeTotal += ; availableSamples = storeTotal }` body already in the callback (lines 381–385), factored so cloud and sherpa share ONE bank-append path — the AudioTrack thread stays the sole track owner (C2). The callback stays an explicit `Function1` (JNI, comment 360–362) — unchanged. `runBlocking` on the single-thread synthesis executor is acceptable: playback runs on its own thread and drains banked audio while a unit is in flight; `cancelled()` inside `onPcm` and the timeout bound keep `stop()` responsive.

> **Self-review:** cloud failure NEVER abandons the read — the failed unit is re-run locally and the loop continues (the precise behavior recon §1 said must change). A Transient/BadUnit failure falls THAT unit back but does NOT latch (cloud may recover next unit); only Fatal latches the rest of the read to local. `null` provider ⇒ byte-identical prior path (Kokoro default preserved). Pin: `TtsEngineSeamTest` (below).

### 5d. Latch + one toast

Reuse `FloatingBubbleService`'s `notifiedFatalKind` contract (recon §3, lines 170/1783–1788): `onCloudFallback` shows at most one toast per latched `FatalKind` — `"${error.message} — used the on-device voice instead"`. Non-fatal fallbacks (Transient/BadUnit) are silent (a single re-synthesized clause is not worth a toast), matching STT's philosophy.

TDD `TtsEngineSeamTest` (pure logic — extract the loop's decision into a testable `planUnitOutcome(hasCloud, latched, synthResult): UnitAction` returning `Cloud | LocalFallback(latchNow) | Local`):
- `null_provider_always_local`.
- `cloud_success_stays_cloud_next_unit`.
- `transient_failure_falls_this_unit_local_but_does_not_latch`.
- `fatal_failure_falls_local_and_latches_rest_of_read`.
- `after_latch_all_remaining_units_are_local`.
- `cancel_stops_the_loop`.

---

## Task 6 — Voice picker UI (existing voice-settings surface)

Extend `SettingsScreen.kt`'s Voice row (372–390) and picker dialog (751–793). The dialog gains, above the Kokoro `LazyColumn`:
1. **Engine selector** — "On-device (Kokoro)" [default] + one row per provider in `ttsSelectableProviders(configured, disclosureAcceptedV3)`. Gated identically to STT: **empty unless v3 accepted AND a key stored** (Task 7). Selecting a cloud engine writes `ttsProviderId`; selecting on-device clears it.
2. **Per-provider voice list** — OpenAI 13 (marin/cedar "Recommended" chip), Gemini 30, ElevenLabs dynamic (`/v1/voices`, session-cached, loading spinner + honest error row on fetch failure). Tap writes `ttsCloudVoiceId(provider)`.
3. **Preview button per voice** — synthesizes a FIXED short sample ("The quick brown fox jumps over the lazy dog.") through the selected provider+voice via `TtsController.speakFromTrigger`, exercising the real cloud path. **Honest caption under the list:** `"Previews use your key and cost real money."` **No speed claim.** Per-provider **price note** from the re-verified numbers above (OpenAI "≈ $0.015/min", ElevenLabs "≈ half a credit/char", Gemini "Preview — pricing not final").
4. For ElevenLabs + Gemini, a one-line honest note: "This provider has no speed control; the speed setting applies to on-device voices only."

The Kokoro speed row (380–388) is unchanged and continues to govern the local voice + the local fallback. `SettingsScreen` subtitle for the Voice row shows the cloud voice display name when a cloud engine is selected, else `TtsVoices.byId(...)`.

TDD `TtsVoicePickerLogicTest` (pure): `ttsSelectableProviders` empty without v3; empty without a key; intersection when both; `previewCaption` contains "your key" and no speed word; each provider's price note is non-empty and speed-claim-free.

---

## Task 7 — Compliance: disclosure v3, privacy flip, Play declaration, ledger

### 7a. Disclosure v3 gating + the three inverted copy tests

`ttsSelectableProviders(configured, disclosureAccepted): List<Provider>` in `CloudProvidersScreen.kt` mirrors `sttSelectableProviders` exactly (`[]` when `!disclosureAccepted`, else `TTS_CAPABLE_PROVIDERS ∩ configured`). Both STT and TTS selectors now read the **v3** flag.

`cloudDisclosureMainText()` gains ONE sentence — read-aloud text now leaves the device, so the copy must say so (present tense, no speed claim):
> "When you choose a cloud voice for read-aloud, the text you select to be read aloud is also sent to that same provider to be spoken."

**Invert the three pinned assertions in `CloudProvidersScreenLogicTest.kt` (the plan writes the new assertions, does not delete the tests):**
- Line 257–262 `cloud_disclosure_main_text_does_not_overclaim_read_aloud_is_sent` → rename `cloud_disclosure_main_text_discloses_read_aloud_text_is_sent`; body becomes `assertTrue(text, text.contains("read aloud"))` and `assertTrue(text, text.contains("text you select"))`. Keep the present-tense (228–234), select-step (236–241), fallback (243–246), no-speed-claim (248–255), and off-until (264–267) tests GREEN — the new sentence is present tense, names no speed, and does not disturb them (re-run to confirm).
- Add `cloud_disclosure_main_text_still_makes_no_speed_claim_after_read_aloud_sentence` as an explicit guard.

### 7b. Privacy §6 read-aloud paragraph FLIPS — both copies, kept identical

In `app/src/main/assets/privacy_policy.html:104` AND `docs/privacy.html:104`, replace the carve-out paragraph with the two-step qualified form (pair stays content-identical — diff-verify byte-for-byte):
> **Cloud read-aloud is off by default.** Read-aloud is produced entirely on-device unless you choose a cloud voice for a provider you have configured. **When you select a cloud voice, the text you choose to have read aloud is sent to that provider's own servers, using your own account with them, to be spoken** — only for the provider whose voice you selected, only while its key is saved, and only after you accept the updated disclosure. The on-device voice remains the default and takes over automatically if the cloud voice fails mid-read, so reading always completes.

### 7c. ToS mirror — RECORDED N/A

Recon §5: `terms_of_service.html` / `docs/terms.html` contain no read-aloud sentence (they speak only of media-transcription/dictation). Per the conditional constraint ("…the ToS mirror **if** it speaks of read-aloud"), **the ToS flip is a no-op. RECORDED: ToS N/A — no read-aloud clause exists to qualify.** Do not touch the ToS files.

### 7d. Play Data Safety — the text-sharing determination, RECORDED

`docs/PLAY-DECLARATIONS.md` §5 today declares only *Audio files → Voice or sound recordings* (Shared Yes). Read-aloud text leaving the device is a NEW shared data type. **ADD and RECORD explicitly:**

> - **Other user-generated content → text you select for read-aloud:** Collected **Yes**, Shared **Yes**, purpose **App functionality**, **Optional**. Shared only when the user takes two deliberate actions — save a provider key AND select a cloud voice for read-aloud — after accepting disclosure v3. On-device read-aloud remains the default with neither action taken. The ephemeral-processing exemption is **NOT** claimed (same reasoning as audio: the third-party provider sets its own retention). **Determination made 2026-07-30: category "Other user-generated content", Shared = Yes, Optional** — chosen over "Other in-app messages" because selected read-aloud text is arbitrary user-selected content, not a message to another person.

### 7e. Release ledger

Add a ledger entry (in the release notes / `PLAY-DECLARATIONS` changelog area, matching prior waves): "Cloud TTS voice picker — three providers behind the one-way local fallback; disclosure v2→v3 (re-prompt); privacy §6 read-aloud carve-out flipped; Data Safety gains Other user-generated content (text), Shared Yes." Diff-verify the privacy pair identical and the disclosure copy tests green.

> **Self-review:** every constraint has a task + pin. v2 key stays; only the constant bumps → re-prompt (MF3). Privacy pair edited together, ToS N/A recorded not silently skipped, Play determination made explicitly with the category rationale. No placeholders; signatures consistent across Tasks 1–5.

---

## Constraint → Task → Pin traceability

| Constraint | Task | Pinned test / record |
|---|---|---|
| Read-aloud text = NEW data class; v3 gates cloud voice | 5b, 6, 7a | `ttsSelectableProviders` empty without v3 |
| Kokoro default + fallback; keep reading; latch; one toast | 5c, 5d | `fatal_failure_falls_local_and_latches_rest_of_read` |
| Clause-bounded unit only | 5c | seam feeds `ClauseSplitter.plan` unit, never `clean` |
| No key / no text content in logcat | 2–4 | classify logs `http $code` + length only |
| No new deps; OkHttp 4.12.0; mp3 via existing AudioDecoder | 1, 3a | `tier_rejection_on_pcm_triggers_mp3_retry` |
| No speed claims; previews cost money | 6, 7a | `previewCaption` no-speed-word; price notes |
| ElevenLabs mp3→existing decoder, not new | 3a | temp-file Uri into `AudioDecoder.decodeTo` |
| Privacy §6 flip both copies + ToS check | 7b, 7c | pair diff-identical; ToS N/A recorded |
| Play Data Safety text determination RECORDED | 7d | §5 entry with category rationale |

## Verification

`$env:JAVA_HOME=…jbr; .\gradlew.bat --no-daemon testDebugUnitTest assembleDebug assembleRelease`. New tests green, 459 baseline intact (3 disclosure assertions inverted per 7a, re-greened), release R8 clean. Instrumented (`AudioDecoder`, `AudioTrack`) = compile-check only — NEVER `connectedAndroidTest`/`installDebug`.

## Commit (named files only)

```
git add "docs/superpowers/plans/2026-07-30-cloud-tts-voice-picker.md"
git commit -m "plan: cloud TTS — three providers, voice picker, disclosure v3"
```
