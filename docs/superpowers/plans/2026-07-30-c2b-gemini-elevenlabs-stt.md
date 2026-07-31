# Release C2b — Gemini + ElevenLabs live STT adapters

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add two more `SttProvider` adapters — Gemini and ElevenLabs — behind the exact interface OpenAI already implements, then widen the selector, the preference-resolver, and both service construction sites so a user who configured either provider gets it (still wrapped in the one-way local fallback). No new engines, no new UX, no protocol changes. Two adapters plus wiring.

**Architecture:** Unchanged from C2a. `SttProvider.transcribe(pcm, language): SttResult` is the whole contract. `CloudTranscriptionEngine`, `FallbackTranscriptionEngine`, `SourceRoutedTranscriptionEngine`, and `SegmentOrderer` are untouched — only the *construction* of which `SttProvider` they wrap changes, funnelled through one new `SttProviderFactory`.

**Tech Stack:** Kotlin 2.0.21, OkHttp 4.12.0 (**pinned**), kotlinx-serialization-json 1.7.3, JUnit 4. No new dependencies.

## Global Constraints (carried from C2a/C1, still binding)

- **No credential, header, transcript content, or URL-carrying-a-key in logcat — status codes and lengths only.** GEMINI TRAP: the key rides the header `x-goog-api-key`. NEVER build a URL containing the key (no `?key=…`), never log a URL that could. `classify` logs the status code alone, exactly as `OpenAiStt` does.
- **OkHttp PINNED to 4.12.0. No new dependencies.** 5.x forces `kotlin-stdlib` 2.2.21 and breaks the 2.0.21 compiler.
- **A 200 with an unparseable body is Transient, NEVER an empty transcript.** Returning `""` looks like silence, suppresses fallback, and loses the sentence.
- **Provider fatal errors LATCH** (handled in `CloudTranscriptionEngine`, not the adapter). Each adapter only maps its own markers → `FatalKind`. 429-with-quota-body → Fatal `OUT_OF_CREDIT`; plain 429 → Transient.
- **`unitTests.isReturnDefaultValues = true`** (`app/build.gradle.kts:166`): `android.jar` classes return type defaults in JVM tests. **`org.json` is BANNED** (JSONObject returns `""` for everything); **`android.util.Base64` is BANNED** (returns defaults — this exact trap cost a task in C1). Use `kotlinx.serialization` for JSON and **`java.util.Base64`** for base64.
- **Do NOT touch** `SegmentOrderer`, the `SourceRouted`/`Fallback`/`Cloud` engines' logic (construction wiring only), `TtsEngine`, or **batch mode's engine — it stays OpenAI-only this wave** (see Task 4; widening `resolveSttProvider` would otherwise silently pull it in).
> Superseded 2026-07-31 by docs/superpowers/plans/2026-07-31-batch-all-providers.md (batch now all-providers).
- **No speed claims in any user-facing copy. Consent triad unchanged** — disclosure v2's meaning does not change by adding providers; per-provider training lines already exist (Task 5).
- **`java` is NOT on PATH.** PowerShell: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio1\jbr"`; `.\gradlew.bat --no-daemon`. `assembleRelease` must stay green (R8, release-only failure history).
- **NEVER run `connectedAndroidTest` or `installDebug`** (they uninstall — destroyed user models twice). Instrumented = compile-check only.
- **Branch `main`, no new branches. Commit ONLY named files, never `git add -A`. Retry once on `index.lock`.**
- **Baseline: 404 tests / 0 failures**, `assembleDebug` and `assembleRelease` both green. Three "OPENAI-only" assertions invert in Task 4 and must be re-greened there.

---

## Pinned Gemini facts (verified against live docs, 2026-07-30)

**Decision: use `generateContent`, not `interactions`. Header auth, never a URL key.**

- **Endpoint (chosen):** `POST https://generativelanguage.googleapis.com/v1beta/models/gemini-3.6-flash:generateContent`, auth via header `x-goog-api-key: <key>` (already `ProviderCatalog.GEMINI.authHeaderName`, `authHeaderValue = { it }`, bare).
- **Model:** `gemini-3.6-flash` — matches the spec's model table and the live REST reference.
- **Request body** (quoted from the REST reference, inline audio form):
  ```json
  {
    "contents": [{
      "parts": [
        {"text": "Transcribe this audio"},
        {"inline_data": {"mime_type": "audio/mpeg", "data": "BASE64_ENCODED_AUDIO_DATA"}}
      ]
    }]
  }
  ```
  We send `"mime_type": "audio/wav"` (WAV supported) and the fixed instruction below.
- **Response path** (quoted): `response.candidates[0].content.parts[0].text`.
- **Error bodies** (quoted): invalid key → **HTTP 400** `{"error":{"code":400,"message":"API key not valid","status":"INVALID_ARGUMENT"}}` (the C1 trap — Gemini rejects bad keys with **400, not 401**, and `details[].reason` carries `API_KEY_INVALID`); quota → **HTTP 429** `{"error":{"code":429,"message":"Resource exhausted","status":"RESOURCE_EXHAUSTED"}}`; forbidden → **HTTP 403** `PERMISSION_DENIED`.

**Why `generateContent` over `interactions`, with evidence:** the C2b brief notes docs "now recommend" `interactions` with `interaction.output_text`, but requires the planner to prefer whichever is *stable/GA for plain-key clients*. The entire existing key-validation stack is already built on and live-verified against `generateContent`: `ProviderCatalog.GEMINI.validationUrl = "…/v1beta/models"`, and `KeyValidator` already classifies exactly `generateContent`'s taxonomy — its `INVALID_KEY_MARKERS` include `"API_KEY_INVALID"` and `"API key not valid"`, and `QUOTA_MARKERS` already include `"RESOURCE_EXHAUSTED"`. Adopting `interactions` would fork the request shape, the response path (`output_text` vs `candidates[].content.parts[].text`), and the auth/error handling away from what is already proven, buying nothing for a one-shot audio POST. `generateContent` is the GA plain-key path; the `interactions` `output_text` shape is newer and unverified here. **Choose `generateContent`.** (The docs also show a `?key=` query form — **rejected**: the key must ride the header per the logging constraint.)

**Transcription-prompt discipline:** Gemini is a general model, not an STT endpoint, so the instruction is fixed and load-bearing:
> `Transcribe this audio verbatim. Output only the spoken words, nothing else.`

Refusals / annotations: a 200 can return a candidate with no text part (safety `finishReason`, no `parts`). No text part → **Transient**, never `Text("")` — a refusal is not silence. A present-but-blank text (`""`) is a legitimate empty transcript.

---

## File Structure

| File | Responsibility |
|---|---|
| `app/src/main/java/com/whispereverywhere/net/HttpTransport.kt` | **Modify.** Add `postJson`. |
| `app/src/test/java/com/whispereverywhere/net/FakeHttpTransport.kt` | **Modify.** Record `lastJsonBody`; add `postJson` override. |
| `app/src/main/java/com/whispereverywhere/transcription/cloud/ElevenLabsStt.kt` | **Create.** Multipart, `scribe_v2`. |
| `app/src/test/java/com/whispereverywhere/transcription/cloud/ElevenLabsSttTest.kt` | **Create.** Mirrors `OpenAiSttTest`. |
| `app/src/main/java/com/whispereverywhere/transcription/cloud/GeminiStt.kt` | **Create.** JSON body, base64 inline WAV. |
| `app/src/test/java/com/whispereverywhere/transcription/cloud/GeminiSttTest.kt` | **Create.** Mirrors `OpenAiSttTest` + JSON-body asserts. |
| `app/src/main/java/com/whispereverywhere/transcription/cloud/SttProviderFactory.kt` | **Create.** One `ProviderId + key → SttProvider` map. |
| `app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt` | **Modify.** Widen `resolveSttProvider`; use factory at construction. |
| `app/src/main/java/com/whispereverywhere/service/BatchTranscriptionService.kt` | **Modify.** Clamp batch to OpenAI-only. |
| `app/src/main/java/com/whispereverywhere/ui/screens/CloudProvidersScreen.kt` | **Modify.** Widen `STT_CAPABLE_PROVIDERS`. |
| `app/src/test/java/com/whispereverywhere/service/EngineSelectionTest.kt` | **Modify.** Invert the two "resolves null" asserts. |
| `app/src/test/java/com/whispereverywhere/ui/screens/CloudProvidersScreenLogicTest.kt` | **Modify.** Invert the two "OPENAI-only" asserts. |

Docs: **none.** See Task 5.

---

## Task 1: `HttpTransport.postJson` — a JSON-body POST for Gemini

**Files:**
- Modify: `app/src/main/java/com/whispereverywhere/net/HttpTransport.kt`
- Modify: `app/src/test/java/com/whispereverywhere/net/FakeHttpTransport.kt`

**Interfaces:**
- Consumes: existing `HttpResult`, `Call.await()`.
- Produces, used by Task 3: `suspend fun postJson(url: String, headers: Map<String, String>, jsonBody: String, timeoutMs: Long = DEFAULT_UPLOAD_TIMEOUT_MS): HttpResult`

**Why new:** the recon confirms only `get` + `postMultipart` exist. Gemini `:generateContent` needs an `application/json` body POST. ElevenLabs needs NO new transport — it is multipart (reuse `postMultipart`).

- [ ] **Step 1: Extend the interface**

In `HttpTransport.kt`, add to the interface:

```kotlin
    /**
     * POST a raw JSON body. Separate from [postMultipart]: Gemini's generateContent takes an
     * application/json body, not form-data. Same long upload-timeout profile — the body embeds
     * base64 audio.
     */
    suspend fun postJson(
        url: String,
        headers: Map<String, String>,
        jsonBody: String,
        timeoutMs: Long = DEFAULT_UPLOAD_TIMEOUT_MS,
    ): HttpResult
```

- [ ] **Step 2: Implement in `OkHttpTransport`**

```kotlin
    override suspend fun postJson(
        url: String,
        headers: Map<String, String>,
        jsonBody: String,
        timeoutMs: Long,
    ): HttpResult {
        return try {
            // Body + headers built INSIDE the try, exactly as postMultipart/get: OkHttp's
            // Headers.checkValue embeds the raw header value in the IllegalArgumentException for
            // every header except the four it redacts — x-goog-api-key is NOT one of them, so an
            // uncaught throw here would put the API key in a crash trace.
            val body = jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder().url(url).post(body).apply {
                headers.forEach { (k, v) -> header(k, v) }
            }.build()
            val call = client.newBuilder()
                .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .build()
                .newCall(request)
            val response = call.await()
            val respBody = response.use { it.body?.string().orEmpty() }
            if (response.isSuccessful) HttpResult.Ok(response.code, respBody)
            else HttpResult.HttpError(response.code, respBody)
        } catch (c: kotlinx.coroutines.CancellationException) {
            // Rethrow FIRST — CancellationException extends Exception, so the broad catch below
            // would otherwise report a cancelled request as a network failure and never unwind.
            throw c
        } catch (e: Exception) {
            HttpResult.NetworkError(e)
        }
    }
```

Imports already present from `postMultipart`: `okhttp3.MediaType.Companion.toMediaType`, `okhttp3.RequestBody.Companion.toRequestBody`.

- [ ] **Step 3: Extend the fake**

In `FakeHttpTransport.kt`, add the recorder and override:

```kotlin
    var lastJsonBody: String? = null
        private set

    override suspend fun postJson(
        url: String,
        headers: Map<String, String>,
        jsonBody: String,
        timeoutMs: Long,
    ): HttpResult {
        lastUrl = url
        lastHeaders = headers
        lastJsonBody = jsonBody
        callCount++
        return script(url, headers)
    }
```

- [ ] **Step 4: Verify**

```powershell
.\gradlew.bat :app:testDebugUnitTest --no-daemon
.\gradlew.bat :app:assembleRelease --no-daemon
```
Expected: 404 tests, 0 failures; release green (interface + fake compile, no behaviour change yet).

- [ ] **Step 5: Commit**

```powershell
git add app/src/main/java/com/whispereverywhere/net/HttpTransport.kt app/src/test/java/com/whispereverywhere/net/FakeHttpTransport.kt
git commit -m "feat(net): HttpTransport.postJson for Gemini's JSON-body POST"
```

---

## Task 2: `ElevenLabsStt` — multipart adapter (scribe_v2)

**Files:**
- Create: `app/src/main/java/com/whispereverywhere/transcription/cloud/ElevenLabsStt.kt`
- Test: `app/src/test/java/com/whispereverywhere/transcription/cloud/ElevenLabsSttTest.kt`

**Interfaces:**
- Consumes: `HttpTransport.postMultipart` + `FilePart`, `WavWriter`, `ProviderCatalog`, `SttProvider`/`SttResult`/`SttError`/`FatalKind` (all from C2a).
- Produces: `class ElevenLabsStt(transport: HttpTransport, apiKey: String, model: String = DEFAULT_MODEL)`

**Provider facts (verified 2026-07-30):** `POST https://api.elevenlabs.io/v1/speech-to-text`, auth header `xi-api-key` (bare, in catalog). Multipart: `file` (binary WAV), `model_id = scribe_v2`, optional `language_code` (omit for auto). Response transcript in `.text`. Errors: 401 invalid key, 422 validation, 429 rate/quota. 5 GB cap — never reached at segment sizes.

- [ ] **Step 1: Write the failing test** — mirrors `OpenAiSttTest` one-for-one. Create `ElevenLabsSttTest.kt`:

```kotlin
package com.whispereverywhere.transcription.cloud

import com.whispereverywhere.net.FakeHttpTransport
import com.whispereverywhere.net.HttpResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ElevenLabsSttTest {

    private fun provider(result: HttpResult, fake: FakeHttpTransport = FakeHttpTransport { _, _ -> result }) =
        fake to ElevenLabsStt(fake, "xi-test")

    private val pcm = ByteArray(3200) { (it % 127).toByte() }

    @Test fun a_200_yields_the_transcript_text() = runBlocking {
        val (_, p) = provider(HttpResult.Ok(200, """{"text":"hello world"}"""))
        assertEquals(SttResult.Text("hello world"), p.transcribe(pcm, null))
    }

    @Test fun the_upload_is_a_wav_container_in_the_file_part() = runBlocking {
        val fake = FakeHttpTransport { _, _ -> HttpResult.Ok(200, """{"text":"x"}""") }
        ElevenLabsStt(fake, "xi-test").transcribe(pcm, null)
        assertEquals("file", fake.lastFilePart?.fieldName)
        val sent = fake.lastFilePart!!.bytes
        assertEquals("RIFF", String(sent, 0, 4, Charsets.US_ASCII))
        assertEquals(pcm.size + 44, sent.size)
    }

    @Test fun the_xi_api_key_header_is_sent_bare_no_bearer() = runBlocking {
        val fake = FakeHttpTransport { _, _ -> HttpResult.Ok(200, """{"text":"x"}""") }
        ElevenLabsStt(fake, "xi-abc").transcribe(pcm, null)
        assertEquals("xi-abc", fake.lastHeaders["xi-api-key"])
    }

    @Test fun the_scribe_v2_model_id_is_sent() = runBlocking {
        val fake = FakeHttpTransport { _, _ -> HttpResult.Ok(200, """{"text":"x"}""") }
        ElevenLabsStt(fake, "xi-test").transcribe(pcm, null)
        assertEquals("scribe_v2", fake.lastFields["model_id"])
    }

    @Test fun a_language_hint_is_forwarded_as_language_code_and_omitted_when_null() = runBlocking {
        val f1 = FakeHttpTransport { _, _ -> HttpResult.Ok(200, """{"text":"x"}""") }
        ElevenLabsStt(f1, "xi-test").transcribe(pcm, "en")
        assertEquals("en", f1.lastFields["language_code"])
        val f2 = FakeHttpTransport { _, _ -> HttpResult.Ok(200, """{"text":"x"}""") }
        ElevenLabsStt(f2, "xi-test").transcribe(pcm, null)
        assertTrue(!f2.lastFields.containsKey("language_code"))
    }

    @Test fun a_401_is_fatal_invalid_key() = runBlocking {
        val (_, p) = provider(HttpResult.HttpError(401, ""))
        val r = p.transcribe(pcm, null) as SttResult.Failed
        assertEquals(FatalKind.INVALID_KEY, (r.error as SttError.Fatal).kind)
    }

    @Test fun a_422_validation_error_is_a_bad_segment() = runBlocking {
        val (_, p) = provider(HttpResult.HttpError(422, "unprocessable"))
        val r = p.transcribe(pcm, null) as SttResult.Failed
        assertEquals(SttError.BadSegment, r.error)
    }

    @Test fun a_429_with_a_quota_body_is_fatal_out_of_credit() = runBlocking {
        val body = """{"detail":{"status":"quota_exceeded"}}"""
        val (_, p) = provider(HttpResult.HttpError(429, body))
        val r = p.transcribe(pcm, null) as SttResult.Failed
        assertEquals(FatalKind.OUT_OF_CREDIT, (r.error as SttError.Fatal).kind)
    }

    @Test fun a_plain_429_is_transient() = runBlocking {
        val (_, p) = provider(HttpResult.HttpError(429, "slow down"))
        val r = p.transcribe(pcm, null) as SttResult.Failed
        assertTrue(r.error is SttError.Transient)
    }

    @Test fun a_403_is_fatal_forbidden() = runBlocking {
        val (_, p) = provider(HttpResult.HttpError(403, ""))
        val r = p.transcribe(pcm, null) as SttResult.Failed
        assertEquals(FatalKind.FORBIDDEN, (r.error as SttError.Fatal).kind)
    }

    @Test fun a_5xx_is_transient() = runBlocking {
        val (_, p) = provider(HttpResult.HttpError(503, "upstream"))
        val r = p.transcribe(pcm, null) as SttResult.Failed
        assertTrue(r.error is SttError.Transient)
    }

    @Test fun a_network_error_is_offline() = runBlocking {
        val (_, p) = provider(HttpResult.NetworkError(IOException("no route")))
        val r = p.transcribe(pcm, null) as SttResult.Failed
        assertEquals(SttError.Offline, r.error)
    }

    @Test fun a_200_with_an_unparseable_body_is_transient_not_silently_empty() = runBlocking {
        val (_, p) = provider(HttpResult.Ok(200, "not json"))
        val r = p.transcribe(pcm, null) as SttResult.Failed
        assertTrue(r.error is SttError.Transient)
    }

    @Test fun a_200_with_an_empty_text_field_is_a_legitimate_empty_transcript() = runBlocking {
        val (_, p) = provider(HttpResult.Ok(200, """{"text":""}"""))
        assertEquals(SttResult.Text(""), p.transcribe(pcm, null))
    }
}
```

- [ ] **Step 2: Run to verify it fails** — `Unresolved reference: ElevenLabsStt`.

```powershell
.\gradlew.bat :app:testDebugUnitTest --no-daemon --tests "com.whispereverywhere.transcription.cloud.ElevenLabsSttTest"
```

- [ ] **Step 3: Implement.** Create `ElevenLabsStt.kt` — same shape as `OpenAiStt`, own `ENDPOINT`/`DEFAULT_MODEL`/`QUOTA_MARKERS`/`classify`:

```kotlin
package com.whispereverywhere.transcription.cloud

import com.whispereverywhere.audio.WavWriter
import com.whispereverywhere.net.HttpResult
import com.whispereverywhere.net.HttpTransport
import com.whispereverywhere.provider.ProviderCatalog
import com.whispereverywhere.provider.ProviderId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** ElevenLabs returns the transcript in `.text`. Defaulted so a missing field is "" not a throw. */
@Serializable
private data class ElevenLabsTranscription(val text: String = "")

/**
 * ElevenLabs speech-to-text. One multipart POST per committed segment, mirroring [OpenAiStt].
 *
 *  - The audio is WAV-wrapped (the `file` part carries a real container).
 *  - Auth is the bare `xi-api-key` header (no Bearer), from the catalog.
 *  - 422 is a validation error for THIS segment (BadSegment), NOT an account fault.
 *  - 429 splits: a quota marker in the body is exhausted credit (Fatal), plain 429 is Transient.
 *
 * Never log the key, the headers, or the transcript — status codes only.
 */
class ElevenLabsStt(
    private val transport: HttpTransport,
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
) : SttProvider {

    override val id = ProviderId.ELEVENLABS

    /** 5 GB provider cap; never reached at ~480 KB segment sizes. Still enforced before upload. */
    override val maxRequestBytes = 5L * 1024 * 1024 * 1024

    override suspend fun transcribe(pcm: ByteArray, language: String?): SttResult {
        if (pcm.size.toLong() + WAV_HEADER_BYTES > maxRequestBytes) {
            return SttResult.Failed(SttError.BadSegment)
        }
        val provider = ProviderCatalog.byId(ProviderId.ELEVENLABS)
        val headers = mapOf(provider.authHeaderName to provider.authHeaderValue(apiKey))
        val fields = buildMap {
            put("model_id", model)
            if (!language.isNullOrBlank() && language != "auto") put("language_code", language)
        }
        val result = transport.postMultipart(
            url = ENDPOINT,
            headers = headers,
            filePart = HttpTransport.FilePart(
                fieldName = "file",
                fileName = "audio.wav",
                contentType = "audio/wav",
                bytes = WavWriter.wrap(pcm),
            ),
            fields = fields,
        )
        return when (result) {
            is HttpResult.NetworkError -> SttResult.Failed(SttError.Offline)
            is HttpResult.HttpError -> SttResult.Failed(classify(result.code, result.body))
            is HttpResult.Ok -> parse(result.body)
        }
    }

    private fun parse(body: String): SttResult = try {
        SttResult.Text(JSON.decodeFromString<ElevenLabsTranscription>(body).text)
    } catch (_: Throwable) {
        SttResult.Failed(SttError.Transient(null))
    }

    private fun classify(code: Int, body: String): SttError {
        android.util.Log.w("WE-DIAG", "elevenlabs stt http $code")   // status code ONLY
        return when (code) {
            401 -> SttError.Fatal(FatalKind.INVALID_KEY, "Key rejected")
            403 -> SttError.Fatal(FatalKind.FORBIDDEN, "Access denied for this key")
            400, 413, 422 -> SttError.BadSegment
            429 -> if (QUOTA_MARKERS.any { body.contains(it, ignoreCase = true) }) {
                SttError.Fatal(FatalKind.OUT_OF_CREDIT, "Account has no remaining credit")
            } else {
                SttError.Transient(null)
            }
            in 500..599 -> SttError.Transient(null)
            else -> SttError.Transient(null)
        }
    }

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }   // kotlinx, NOT org.json (banned)
        const val DEFAULT_MODEL = "scribe_v2"
        private const val ENDPOINT = "https://api.elevenlabs.io/v1/speech-to-text"
        private const val WAV_HEADER_BYTES = 44
        private val QUOTA_MARKERS = listOf("quota_exceeded", "insufficient_quota")
    }
}
```

- [ ] **Step 4: Verify** — `--tests "…ElevenLabsSttTest"` PASS (14 tests). Full suite 418.

- [ ] **Step 5: Commit**

```powershell
git add app/src/main/java/com/whispereverywhere/transcription/cloud/ElevenLabsStt.kt app/src/test/java/com/whispereverywhere/transcription/cloud/ElevenLabsSttTest.kt
git commit -m "feat(cloud): ElevenLabs STT adapter (scribe_v2, multipart)"
```

---

## Task 3: `GeminiStt` — JSON body, base64 inline WAV

**Files:**
- Create: `app/src/main/java/com/whispereverywhere/transcription/cloud/GeminiStt.kt`
- Test: `app/src/test/java/com/whispereverywhere/transcription/cloud/GeminiSttTest.kt`

**Interfaces:**
- Consumes: `HttpTransport.postJson` (Task 1), `WavWriter`, `ProviderCatalog`, `java.util.Base64`.
- Produces: `class GeminiStt(transport: HttpTransport, apiKey: String, model: String = DEFAULT_MODEL)`

**Size math:** 20 MB TOTAL request cap including base64 overhead. base64 ≈ 1.333× raw, plus WAV header + JSON envelope. Gate raw PCM at **14 MB** (`maxRequestBytes`): base64(≈14 MB) ≈ 18.7 MB + envelope < 20 MB. A ≤15 s segment is ~480 KB — 3% of the gate.

- [ ] **Step 1: Write the failing test** — mirrors `OpenAiSttTest` plus JSON-body asserts (uses the new `lastJsonBody`). Create `GeminiSttTest.kt`:

```kotlin
package com.whispereverywhere.transcription.cloud

import com.whispereverywhere.net.FakeHttpTransport
import com.whispereverywhere.net.HttpResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class GeminiSttTest {

    private val ok = """{"candidates":[{"content":{"parts":[{"text":"hello world"}]}}]}"""

    private fun provider(result: HttpResult, fake: FakeHttpTransport = FakeHttpTransport { _, _ -> result }) =
        fake to GeminiStt(fake, "g-test")

    private val pcm = ByteArray(3200) { (it % 127).toByte() }

    @Test fun a_200_yields_the_transcript_text() = runBlocking {
        val (_, p) = provider(HttpResult.Ok(200, ok))
        assertEquals(SttResult.Text("hello world"), p.transcribe(pcm, null))
    }

    @Test fun the_key_rides_the_header_and_is_never_in_the_url() = runBlocking {
        val fake = FakeHttpTransport { _, _ -> HttpResult.Ok(200, ok) }
        GeminiStt(fake, "g-secret").transcribe(pcm, null)
        assertEquals("g-secret", fake.lastHeaders["x-goog-api-key"])
        assertFalse("key must never appear in the URL", fake.lastUrl!!.contains("g-secret"))
        assertFalse("no ?key= query param", fake.lastUrl!!.contains("key="))
    }

    @Test fun the_body_carries_base64_wav_inline_data_and_the_verbatim_instruction() = runBlocking {
        val fake = FakeHttpTransport { _, _ -> HttpResult.Ok(200, ok) }
        GeminiStt(fake, "g-test").transcribe(pcm, null)
        val body = fake.lastJsonBody!!
        assertTrue(body.contains("inline_data"))
        assertTrue(body.contains("audio/wav"))
        assertTrue(body.contains("Transcribe this audio verbatim"))
        // base64 of the WAV-wrapped bytes must be present (RIFF -> "UklGR" prefix).
        assertTrue("expected base64 WAV in body", body.contains("UklGR"))
    }

    @Test fun the_pinned_model_is_in_the_endpoint_path() = runBlocking {
        val fake = FakeHttpTransport { _, _ -> HttpResult.Ok(200, ok) }
        GeminiStt(fake, "g-test").transcribe(pcm, null)
        assertTrue(fake.lastUrl!!.contains("gemini-3.6-flash:generateContent"))
    }

    @Test fun a_400_with_an_api_key_invalid_marker_is_fatal_invalid_key() = runBlocking {
        // The C1 trap: Gemini rejects a bad key with 400, not 401.
        val body = """{"error":{"code":400,"message":"API key not valid","status":"INVALID_ARGUMENT"}}"""
        val (_, p) = provider(HttpResult.HttpError(400, body))
        val r = p.transcribe(pcm, null) as SttResult.Failed
        assertEquals(FatalKind.INVALID_KEY, (r.error as SttError.Fatal).kind)
    }

    @Test fun a_plain_400_without_the_marker_is_a_bad_segment_not_a_key_fault() = runBlocking {
        val (_, p) = provider(HttpResult.HttpError(400, """{"error":{"message":"malformed request"}}"""))
        val r = p.transcribe(pcm, null) as SttResult.Failed
        assertEquals(SttError.BadSegment, r.error)
    }

    @Test fun a_429_resource_exhausted_is_fatal_out_of_credit() = runBlocking {
        val body = """{"error":{"code":429,"status":"RESOURCE_EXHAUSTED"}}"""
        val (_, p) = provider(HttpResult.HttpError(429, body))
        val r = p.transcribe(pcm, null) as SttResult.Failed
        assertEquals(FatalKind.OUT_OF_CREDIT, (r.error as SttError.Fatal).kind)
    }

    @Test fun a_plain_429_is_transient() = runBlocking {
        val (_, p) = provider(HttpResult.HttpError(429, "slow down"))
        val r = p.transcribe(pcm, null) as SttResult.Failed
        assertTrue(r.error is SttError.Transient)
    }

    @Test fun a_403_is_fatal_forbidden() = runBlocking {
        val (_, p) = provider(HttpResult.HttpError(403, "PERMISSION_DENIED"))
        val r = p.transcribe(pcm, null) as SttResult.Failed
        assertEquals(FatalKind.FORBIDDEN, (r.error as SttError.Fatal).kind)
    }

    @Test fun a_5xx_is_transient() = runBlocking {
        val (_, p) = provider(HttpResult.HttpError(503, "upstream"))
        val r = p.transcribe(pcm, null) as SttResult.Failed
        assertTrue(r.error is SttError.Transient)
    }

    @Test fun a_network_error_is_offline() = runBlocking {
        val (_, p) = provider(HttpResult.NetworkError(IOException("no route")))
        val r = p.transcribe(pcm, null) as SttResult.Failed
        assertEquals(SttError.Offline, r.error)
    }

    @Test fun oversized_audio_fails_locally_without_a_request() = runBlocking {
        val fake = FakeHttpTransport { _, _ -> HttpResult.Ok(200, ok) }
        val huge = ByteArray(15 * 1024 * 1024)
        val r = GeminiStt(fake, "g-test").transcribe(huge, null) as SttResult.Failed
        assertEquals(SttError.BadSegment, r.error)
        assertEquals("must not hit the network", 0, fake.callCount)
    }

    @Test fun a_200_with_an_unparseable_body_is_transient_not_silently_empty() = runBlocking {
        val (_, p) = provider(HttpResult.Ok(200, "not json"))
        val r = p.transcribe(pcm, null) as SttResult.Failed
        assertTrue(r.error is SttError.Transient)
    }

    @Test fun a_200_refusal_with_no_text_part_is_transient_not_empty() = runBlocking {
        // A safety refusal returns a candidate with no parts. That is NOT silence.
        val (_, p) = provider(HttpResult.Ok(200, """{"candidates":[{"finishReason":"SAFETY"}]}"""))
        val r = p.transcribe(pcm, null) as SttResult.Failed
        assertTrue(r.error is SttError.Transient)
    }

    @Test fun a_200_with_a_blank_text_part_is_a_legitimate_empty_transcript() = runBlocking {
        val (_, p) = provider(HttpResult.Ok(200, """{"candidates":[{"content":{"parts":[{"text":""}]}}]}"""))
        assertEquals(SttResult.Text(""), p.transcribe(pcm, null))
    }
}
```

- [ ] **Step 2: Run to verify it fails** — `Unresolved reference: GeminiStt`.

- [ ] **Step 3: Implement.** Create `GeminiStt.kt`:

```kotlin
package com.whispereverywhere.transcription.cloud

import com.whispereverywhere.audio.WavWriter
import com.whispereverywhere.net.HttpResult
import com.whispereverywhere.net.HttpTransport
import com.whispereverywhere.provider.ProviderCatalog
import com.whispereverywhere.provider.ProviderId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Base64

// --- response DTOs: every field defaulted so a missing node is "" / empty, never a throw ---
@Serializable private data class GeminiResponse(val candidates: List<GeminiCandidate> = emptyList())
@Serializable private data class GeminiCandidate(val content: GeminiContent = GeminiContent())
@Serializable private data class GeminiContent(val parts: List<GeminiPart> = emptyList())
@Serializable private data class GeminiPart(val text: String? = null)

/**
 * Gemini transcription via `generateContent` (the GA plain-key path — see the plan's "Pinned
 * Gemini facts" for why not `interactions`). One JSON POST per committed segment.
 *
 * Load-bearing details:
 *  - THE KEY RIDES THE HEADER `x-goog-api-key`. The URL NEVER carries it (no ?key=), and nothing
 *    logs a URL that could — status codes only.
 *  - Gemini is a general model, so the instruction is fixed: "Transcribe this audio verbatim…".
 *  - THE BAD-KEY STATUS IS 400, NOT 401 (the C1 trap). 400 is only INVALID_KEY when the body
 *    carries an api-key-invalid marker; a plain 400 is a BadSegment (malformed request), not an
 *    account fault.
 *  - A 200 refusal (candidate with no text part) is Transient, NOT Text("") — a refusal is not
 *    silence. A present-but-blank text IS a legitimate empty transcript.
 *  - base64 uses java.util.Base64; android.util.Base64 returns defaults under JVM tests (C1 trap).
 */
class GeminiStt(
    private val transport: HttpTransport,
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
) : SttProvider {

    override val id = ProviderId.GEMINI

    /** Raw-PCM gate. 20 MB TOTAL request cap incl. base64 (~1.333x) + envelope -> 14 MB raw is safe. */
    override val maxRequestBytes = 14L * 1024 * 1024

    override suspend fun transcribe(pcm: ByteArray, language: String?): SttResult {
        if (pcm.size.toLong() + WAV_HEADER_BYTES > maxRequestBytes) {
            return SttResult.Failed(SttError.BadSegment)
        }
        val provider = ProviderCatalog.byId(ProviderId.GEMINI)
        val headers = mapOf(provider.authHeaderName to provider.authHeaderValue(apiKey))
        val b64 = Base64.getEncoder().encodeToString(WavWriter.wrap(pcm))
        // Built with the serializer so the base64 payload and instruction are correctly escaped.
        val requestBody = JSON.encodeToString(
            GeminiRequest.serializer(),
            GeminiRequest(
                contents = listOf(
                    GeminiReqContent(
                        parts = listOf(
                            GeminiReqPart(text = INSTRUCTION),
                            GeminiReqPart(inline_data = GeminiInlineData(mime_type = "audio/wav", data = b64)),
                        )
                    )
                )
            )
        )
        val result = transport.postJson(url = ENDPOINT + model + GENERATE, headers = headers, jsonBody = requestBody)
        return when (result) {
            is HttpResult.NetworkError -> SttResult.Failed(SttError.Offline)
            is HttpResult.HttpError -> SttResult.Failed(classify(result.code, result.body))
            is HttpResult.Ok -> parse(result.body)
        }
    }

    private fun parse(body: String): SttResult = try {
        val text = JSON.decodeFromString<GeminiResponse>(body)
            .candidates.firstOrNull()?.content?.parts
            ?.firstOrNull { it.text != null }?.text
        // No text part at all (refusal / empty candidates) is Transient, not an empty transcript.
        if (text == null) SttResult.Failed(SttError.Transient(null)) else SttResult.Text(text)
    } catch (_: Throwable) {
        SttResult.Failed(SttError.Transient(null))
    }

    private fun classify(code: Int, body: String): SttError {
        android.util.Log.w("WE-DIAG", "gemini stt http $code")   // status code ONLY — never the URL
        return when (code) {
            // 400 is INVALID_KEY only with the marker; otherwise a malformed-request BadSegment.
            400 -> if (INVALID_KEY_MARKERS.any { body.contains(it, ignoreCase = true) }) {
                SttError.Fatal(FatalKind.INVALID_KEY, "Key rejected")
            } else {
                SttError.BadSegment
            }
            401 -> SttError.Fatal(FatalKind.INVALID_KEY, "Key rejected")
            403 -> SttError.Fatal(FatalKind.FORBIDDEN, "Access denied for this key")
            413 -> SttError.BadSegment
            429 -> if (QUOTA_MARKERS.any { body.contains(it, ignoreCase = true) }) {
                SttError.Fatal(FatalKind.OUT_OF_CREDIT, "Account has no remaining credit")
            } else {
                SttError.Transient(null)
            }
            in 500..599 -> SttError.Transient(null)
            else -> SttError.Transient(null)
        }
    }

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = false }
        const val DEFAULT_MODEL = "gemini-3.6-flash"
        private const val INSTRUCTION =
            "Transcribe this audio verbatim. Output only the spoken words, nothing else."
        // Key rides the x-goog-api-key header — this URL never carries it.
        private const val ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models/"
        private const val GENERATE = ":generateContent"
        private const val WAV_HEADER_BYTES = 44
        private val INVALID_KEY_MARKERS = listOf("API_KEY_INVALID", "API key not valid")
        private val QUOTA_MARKERS = listOf("RESOURCE_EXHAUSTED", "quota_exceeded", "insufficient_quota")
    }
}

// --- request DTOs (snake_case matches the REST body verbatim) ---
@Serializable private data class GeminiRequest(val contents: List<GeminiReqContent>)
@Serializable private data class GeminiReqContent(val parts: List<GeminiReqPart>)
@Serializable private data class GeminiReqPart(val text: String? = null, val inline_data: GeminiInlineData? = null)
@Serializable private data class GeminiInlineData(val mime_type: String, val data: String)
```

- [ ] **Step 4: Verify** — `--tests "…GeminiSttTest"` PASS (15 tests). Full suite 433. Then `assembleRelease` (base64 + serializer must survive R8).

- [ ] **Step 5: Commit**

```powershell
git add app/src/main/java/com/whispereverywhere/transcription/cloud/GeminiStt.kt app/src/test/java/com/whispereverywhere/transcription/cloud/GeminiSttTest.kt
git commit -m "feat(cloud): Gemini STT adapter (generateContent, base64 inline WAV)"
```

---

## Task 4: `SttProviderFactory` + widen the wiring (batch stays OpenAI-only)

**Files:**
- Create: `app/src/main/java/com/whispereverywhere/transcription/cloud/SttProviderFactory.kt`
- Modify: `FloatingBubbleService.kt` (widen `resolveSttProvider`; use factory at the construction site)
- Modify: `BatchTranscriptionService.kt` (clamp to OpenAI-only)
- Modify: `CloudProvidersScreen.kt` (widen `STT_CAPABLE_PROVIDERS`)
- Modify: `EngineSelectionTest.kt`, `CloudProvidersScreenLogicTest.kt` (invert the three OPENAI-only asserts)

**Interfaces:**
- Produces: `object SttProviderFactory { fun create(id: ProviderId, transport: HttpTransport, apiKey: String): SttProvider }` — the ONE place a `ProviderId` maps to an adapter, used by both services.

- [ ] **Step 1: Create the factory** — single construction point:

```kotlin
package com.whispereverywhere.transcription.cloud

import com.whispereverywhere.net.HttpTransport
import com.whispereverywhere.provider.ProviderId

/**
 * The ONE place a ProviderId becomes an SttProvider. Both the live service and (for OpenAI only)
 * the batch service route through here, so a new adapter is wired in exactly once. `when` is
 * exhaustive over ProviderId — a new provider will not compile until it is mapped or explicitly
 * rejected, which is the safety we want.
 */
object SttProviderFactory {
    fun create(id: ProviderId, transport: HttpTransport, apiKey: String): SttProvider = when (id) {
        ProviderId.OPENAI -> OpenAiStt(transport, apiKey)
        ProviderId.GEMINI -> GeminiStt(transport, apiKey)
        ProviderId.ELEVENLABS -> ElevenLabsStt(transport, apiKey)
    }
}
```
> If `ProviderId` has members without an STT adapter, map them to `error("<id> has no STT adapter")` so the `when` stays exhaustive. Confirm the enum's full membership before writing this — the recon lists OPENAI/GEMINI/ELEVENLABS as the STT-capable set.

- [ ] **Step 2: Widen `resolveSttProvider`** in `FloatingBubbleService.kt` (L92-100). Replace the OpenAI-only body and update the KDoc:

```kotlin
/**
 * The stored preference names a usable provider when it matches one of the STT adapters this
 * release ships — OpenAI, Gemini, or ElevenLabs. Anything else (null, a foreign/stale/corrupt
 * value) resolves to null, which [decideEngineChoice] then treats exactly like "no key".
 */
internal fun resolveSttProvider(raw: String?): ProviderId? =
    raw?.let { runCatching { ProviderId.valueOf(it) }.getOrNull() }
        ?.takeIf { it in STT_PROVIDERS }

private val STT_PROVIDERS = setOf(ProviderId.OPENAI, ProviderId.GEMINI, ProviderId.ELEVENLABS)
```

- [ ] **Step 3: Switch the construction site** in `FloatingBubbleService.resolveTranscriptionEngine()` (CLOUD_WITH_FALLBACK branch, L1542). Only the `stt` line changes:

```kotlin
val providerId = resolveSttProvider(/* the stored stt provider id */) ?: ProviderId.OPENAI
val stt = SttProviderFactory.create(providerId, sharedTransport(), requireNotNull(key))
```
> Use whatever expression already feeds `decideEngineChoice` for the provider id at this site — do not re-read prefs a second way. The rest of the branch (`CloudTranscriptionEngine`, `lastCloudEngine`, the `SourceRoutedTranscriptionEngine`/`FallbackTranscriptionEngine` wrap) is unchanged.

- [ ] **Step 4: Clamp batch to OpenAI-only** in `BatchTranscriptionService.resolveCloud` (L279-297). Widening `resolveSttProvider` now makes a Gemini/ElevenLabs selection resolve non-null here too, and the method would build `OpenAiStt` with that provider's key — wrong. Add an explicit guard right after the resolve, and keep `OpenAiStt` hardcoded:

```kotlin
val providerId = resolveSttProvider(prefs.sttProviderId)
// Batch mode stays OpenAI-only this wave. Gemini/ElevenLabs are live-mode only until batch is
// widened deliberately (its own engineUsed decision) — never silently, via resolveSttProvider.
if (providerId != ProviderId.OPENAI) return null
```
The existing `return OpenAiStt(transport(), key!!)` stays. **Decision recorded:** batch's `engineUsed` is not widened in C2b; batch reaching a non-OpenAI selection returns null (falls to local) rather than mis-keying another provider.

- [ ] **Step 5: Widen the selector** in `CloudProvidersScreen.kt` (L131):

```kotlin
internal val STT_CAPABLE_PROVIDERS: Set<ProviderId> =
    setOf(ProviderId.OPENAI, ProviderId.GEMINI, ProviderId.ELEVENLABS)
```
The UI rows auto-populate (`sttSelectableProviders` filters catalog ∩ capable ∩ configured). `sttSelectionCaption` needs **no** change — "Audio is sent to $providerDisplayName. If it fails, the on-device model takes over." is already provider-neutral and carries no speed claim.

- [ ] **Step 6: Invert the now-false assertions.**
  - `EngineSelectionTest.kt` (L70-75): `resolveSttProvider("GEMINI")` and `("ELEVENLABS")` now return their `ProviderId`, not null. Change both asserts to `assertEquals(ProviderId.GEMINI, resolveSttProvider("GEMINI"))` etc. Keep `("OPENAI")→OPENAI` and a garbage/`null`→null case.
  - `CloudProvidersScreenLogicTest.kt`: `only_openai_is_stt_capable_in_this_release` (L216) now asserts the three-member set — rename to `stt_capable_set_is_openai_gemini_elevenlabs` and assert `setOf(OPENAI, GEMINI, ELEVENLABS)`. `a_configured_provider_with_no_stt_adapter_is_not_offered` (L303) uses ELEVENLABS as its "no adapter" example — pick a genuinely non-STT `ProviderId` if one exists, else delete the case (all three catalog providers are now STT-capable) and note why in a comment.

- [ ] **Step 7: Verify** — full suite green after the inversions, both variants:

```powershell
.\gradlew.bat :app:testDebugUnitTest --no-daemon
.\gradlew.bat :app:assembleRelease --no-daemon
```
Expected: 433 tests, 0 failures (404 baseline + 14 ElevenLabs + 15 Gemini; the three inverted asserts change meaning, not count); release green.

- [ ] **Step 8: Commit**

```powershell
git add app/src/main/java/com/whispereverywhere/transcription/cloud/SttProviderFactory.kt app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt app/src/main/java/com/whispereverywhere/service/BatchTranscriptionService.kt app/src/main/java/com/whispereverywhere/ui/screens/CloudProvidersScreen.kt app/src/test/java/com/whispereverywhere/service/EngineSelectionTest.kt app/src/test/java/com/whispereverywhere/ui/screens/CloudProvidersScreenLogicTest.kt
git commit -m "feat(cloud): wire Gemini + ElevenLabs into selection (batch stays OpenAI-only)"
```

---

## Task 5: Docs — deliberately none

State the reasoning explicitly so an audit checks the decision rather than missing it:

- **No compliance/disclosure doc change is required.** C2a already shipped the present-tense disclosure, the Play Data Safety declaration, and the privacy policy for "audio leaves the device." Adding two more BYO-key providers does not change *what* the disclosure means: audio still leaves the device only when the user selects a cloud provider and supplies their own key, still with the one-way local fallback.
- **The consent triad is unchanged.** Disclosure v2's meaning is provider-count-independent, and per-provider training lines (Gemini/ElevenLabs `trainsOnDataByDefault = true`) already exist in `CloudProvidersScreenLogicTest` and the catalog.
- **No new user-facing copy.** The STT caption is provider-neutral; no speed claims are introduced anywhere.

If an audit disagrees and wants a doc touch, that is a follow-up, not a blocker for the adapters.

---

## Self-review (done inline)

- Every Global Constraint maps to a task/pin: header-only key (Tasks 1/3 redaction + status-only logs), no URL key (Task 3 test asserts it), OkHttp pin / no deps (uses existing transport + kotlinx + java.util.Base64), 200-unparseable→Transient (all three adapters + tests), 429 both directions (each adapter's test has quota + plain), fatal latch (unchanged `CloudTranscriptionEngine`), org.json/android.util.Base64 bans (kotlinx + java.util.Base64), batch OpenAI-only (Task 4 clamp + recorded decision), no speed claim / disclosure unchanged (Task 5).
- Gemini endpoint decided with quoted evidence (generateContent, header auth) and the C1 400-not-401 trap mapped with a marker guard (plain 400 = BadSegment, not INVALID_KEY).
- Signatures consistent: `postJson` matches Task 1 across interface/OkHttp/fake; `SttProviderFactory.create(id, transport, apiKey)` is the single call both services use; adapters implement the exact C2a `SttProvider`.
- Per-provider tests cover BOTH 429 directions and the unparseable-200 (ElevenLabs + Gemini), plus Gemini's refusal-with-no-text→Transient and blank-text→Text("").
- No placeholders; the two "read the id at this site" notes (Task 4 Steps 3) point at existing code rather than inventing a new prefs read.
