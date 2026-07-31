# Soniox — the 4th STT provider (multilingual specialist)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a 4th `SttProvider` adapter — **Soniox** — behind the exact interface OpenAI/Gemini/ElevenLabs already implement, then widen the enum, catalog, selector, and preference-resolver so a user who configured Soniox gets it (still wrapped in the one-way local fallback). Soniox's selling point is **multilingual** transcription with automatic language detection — that is the owner's reason for adding it. No new UX, no new engines. One adapter (a poll-based async pipeline, unlike the single-POST trio), plus the enum cascade.

**Architecture:** Unchanged. `SttProvider.transcribe(pcm, language): SttResult` is the whole contract. `CloudTranscriptionEngine`, `FallbackTranscriptionEngine`, `SourceRoutedTranscriptionEngine`, and `SegmentOrderer` are untouched — only *which* `SttProvider` gets constructed changes, funnelled through the existing `SttProviderFactory`. Soniox rides the per-VAD-segment `CLOUD_WITH_FALLBACK` path exactly like Gemini/ElevenLabs; **its realtime WebSocket is a recorded follow-up, NOT v1.**

**Tech Stack:** Kotlin 2.0.21, OkHttp 4.12.0 (**pinned**), kotlinx-serialization-json 1.7.3, JUnit 4. **No new dependencies.**

## The hard reality this plan is written against

**The owner has NO Soniox key yet.** Nothing here will be live-verified before a user could touch it, so *every* protective pattern this codebase has already paid to learn MUST ship in v1, unverified-but-correct-by-construction:

- **fatal latching** (one request per latch — lives in `CloudTranscriptionEngine`, the adapter only maps its markers to `FatalKind`);
- **`MODEL_UNAVAILABLE` on a create-step 404** (the C2b Gemini lesson — a bad/retired model id is a permanent config fault, not a hiccup);
- **quota-vs-key split on ambiguous statuses** (the ElevenLabs 401 lesson — a body marker decides `OUT_OF_CREDIT` vs `INVALID_KEY`, so an empty wallet is never reported as a bad key);
- **unparseable-200 → `Transient`, never `Text("")`** (a mangled body is not silence — returning `""` suppresses fallback and eats the sentence);
- **status-code-only logging** (never the key, the header, the body, or a URL);
- **honest key-validation copy** (Soniox falls to the plain rejection line — no invented per-endpoint scoping story it does not have).

The **resolve phase** at the end of this plan lists exactly what the owner's eventual key must prove. Two facts are flagged there as *unverifiable without a live key* — they are handled defensively so either resolution is already correct.

## Global Constraints (carried from C2a/C2b, still binding)

- **No credential in logs OR URLs.** Soniox auth is `Authorization: Bearer <key>` — the **header only**. No Soniox URL ever carries the key; `classify` logs the status code and the pipeline step, nothing else.
- **OkHttp PINNED to 4.12.0. No new dependencies.** 5.x forces a stdlib the 2.0.21 compiler cannot read.
- **A 200 with an unparseable body is `Transient`, NEVER an empty transcript.**
- **Provider fatal errors LATCH** in `CloudTranscriptionEngine`, not the adapter. The adapter maps markers → `FatalKind`; a 402/quota-401 → `OUT_OF_CREDIT`, a plain-401 → `INVALID_KEY`, a create-step 404 → `MODEL_UNAVAILABLE`.
- **`unitTests.isReturnDefaultValues = true`** (`app/build.gradle.kts`): `android.jar` classes return type defaults under JVM tests. **`org.json` is BANNED**, **`android.util.Base64` is BANNED**. Use `kotlinx.serialization`. (Soniox needs no base64 — the WAV is a multipart file part, not an inline field.)
- **Batch stays OpenAI-only** this wave (`resolveBatchSttProvider` clamps to OPENAI — a Soniox selection auto-degrades to on-device in batch, unchanged, and is tested). **Live word-for-word stays OpenAI-only** (`decideEngineChoice`'s `CLOUD_LIVE` requires `OPENAI` — Soniox never shows the live toggle). No edit to either seam; both are re-asserted.
> Superseded 2026-07-31 by docs/superpowers/plans/2026-07-31-batch-all-providers.md (batch now all-providers; the live word-for-word OpenAI-only note still stands).
> Superseded (live) 2026-07-31 by docs/superpowers/plans/2026-07-31-realtime-all-providers.md (realtime now all streaming-capable providers — Soniox's own stt-rt-v5 ships behind SonioxRealtimeProtocol; the live word-for-word OpenAI-only note above no longer stands).
- **Multilingual is the point:** auto-detect is the DEFAULT — `language_hints` is **omitted** whenever the language preference is null/blank/`"auto"`, so Soniox auto-detects; a specific preference narrows it. This rides the app's existing `getLanguageForApi()` wiring with no change (see Task 5 for the one English-scope caveat worth documenting).
- **No speed claims.** Same consent triad — adding a provider does not change what the disclosure means.
- **`java` is NOT on PATH.** PowerShell: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio1\jbr"`; `.\gradlew.bat --no-daemon`. `assembleRelease` (R8) must stay green.
- **NEVER `connectedAndroidTest` or `installDebug`** (they uninstall — destroyed user models twice). Instrumented = compile-check only.
- **Commit ONLY named files, never `git add -A`. Retry once on `index.lock`.**
- **Baseline: 634 tests / 0 failures**, `assembleDebug` + `assembleRelease` green. Four `ProviderCatalogTest` assertions and one `CloudProvidersScreenLogicTest` assertion assert the 3-provider world and are updated in-plan; the count grows by the new suites, it never regresses.

---

## Pinned Soniox facts (from the fact sheet, live docs fetched 2026-07-30)

**Base URL `https://api.soniox.com`. There is NO synchronous transcribe endpoint** — the only path is the async job flow. This is the one structural difference from the other three adapters, which each do a single POST.

**Per-segment pipeline (5 calls):**
1. **Upload** — `POST /v1/files`, `multipart/form-data`, field `file` (the WAV-wrapped segment). → `{ "id": "<file-uuid>" }`.
2. **Create** — `POST /v1/transcriptions`, `application/json`, body `{ "model": "stt-async-v5", "file_id": "<file-uuid>", "language_hints"?: [...] }`. → `201 { "id": "<transcription-uuid>", "status": "queued" }`.
3. **Poll** — `GET /v1/transcriptions/{id}`, read `status`, **1 s interval**, until terminal. In-flight states: `queued | processing | downloading | transcribing`. Terminal success: `completed`. Terminal failure: `error` **or** `failed` (docs disagree — treat *both* as failure, and treat any status **not** in the in-flight set as terminal — do NOT hardcode one literal). On failure read `error_type` + `error_message`.
4. **Fetch** — `GET /v1/transcriptions/{id}/transcript`. → `{ "tokens": [ { "text": ... }, ... ] }`. **Assemble the transcript by concatenating `tokens[].text`.** A top-level `text` convenience field was seen in the OpenAPI machine-extract but is **unconfirmed in human docs — do NOT rely on it.**
5. **Cleanup** — `DELETE /v1/transcriptions/{id}` then `DELETE /v1/files/{file-id}` (both `204`). **The async path STORES your audio+transcript server-side until you delete them** — so deletion is part of every happy path AND every error path AND cancellation. Never leak a user's audio on Soniox's servers.

**Model:** `stt-async-v5` (async multilingual, 60+ languages, mid-sentence switching). Legacy `stt-async-v4` auto-routes to v5. The `stt-async-preview`/`stt-async-v3` literals in some doc examples are **stale — use `stt-async-v5`.**

**Auth:** `Authorization: Bearer <key>`. **Cheapest authenticated validation GET: `GET /v1/models`** (bad key → `401 unauthenticated`).

**Error taxonomy** (body: `status_code`, `error_type`, `message`, optional `validation_errors[]`):

| Condition | HTTP | marker |
|---|---|---|
| Bad/missing key | `401` | `unauthenticated` |
| Quota / balance exhausted | `402` | balance/budget exhausted |
| Bad request / unsupported audio | `400` | `invalid_request` |
| Not found (model / file / transcription) | `404` | — (disambiguate by **which** call) |
| Wrong state | `409` | invalid state |
| Rate limit | `429` | `limit_exceeded` |
| Server | `500`/`503` | — |

`400` is shared (malformed request **and** undecodable audio → both `BadSegment`). `404` is shared (bad **model** name on create → `MODEL_UNAVAILABLE`; missing file/transcription on a later call → odd server state → `Transient`) — **disambiguate by pipeline step.** A failed *job* surfaces as `status:"error"/"failed"` with `error_type`, not an HTTP error.

**Data/training (for disclosure), quote-backed from Security & Privacy:** *"your audio and transcripts are never used to improve Soniox models or services"* → `trainsOnDataByDefault = false`. Retention: nothing is stored *unless* the service supports storage — **the async API does store**, until you `DELETE`. So the disclosure line pairs "not trained on" with "deleted right after each transcription", which the pipeline's mandatory cleanup makes literally true.

**File constraints:** WAV accepted directly (auto-detected — upload the container as-is; raw headerless PCM is a *realtime* concern, not this file path, so keep WAV-wrapping). Max 300 min/file (never approached at ≤15 s). ≤2,000 total transcriptions stored — another reason **delete after every fetch**.

**Two items unverifiable without a live key** (both handled defensively — see resolve phase): (a) the exact terminal-failure literal (`error` vs `failed`) — we treat *both* as failure and everything-not-in-flight as terminal; (b) whether `/transcript` returns a top-level `text` — we ignore it and build from `tokens`.

**Sources:** soniox.com/docs/openapi.yaml · /stt/models · /stt/async/async-transcription · /stt/async/limits-and-quotas · /api-reference/stt/transcriptions/create_transcription · /security-and-privacy.

---

## File Structure

| File | Responsibility |
|---|---|
| `app/src/main/java/com/whispereverywhere/net/HttpTransport.kt` | **Modify.** Add `delete` (cleanup needs a DELETE verb — none exists today). |
| `app/src/test/java/com/whispereverywhere/net/FakeHttpTransport.kt` | **Modify.** Add `delete` override that records `deletedUrls` (separate from `script`, so a DELETE and a poll-GET on the same URL are distinguishable). |
| `app/src/main/java/com/whispereverywhere/provider/ProviderCatalog.kt` | **Modify.** `ProviderId.SONIOX` (last) + its `Provider` entry. Source of the compile cascade. |
| `app/src/test/java/com/whispereverywhere/provider/ProviderCatalogTest.kt` | **Modify.** The four 3-provider assertions. |
| `app/src/main/java/com/whispereverywhere/ui/screens/CloudProvidersScreen.kt` | **Modify.** `providerTrainingDisclosure` Soniox branch (Task 2); `STT_CAPABLE_PROVIDERS` (Task 4). |
| `app/src/main/java/com/whispereverywhere/tts/cloud/TtsProviderFactory.kt` | **Modify.** Exhaustive `when` — Soniox **rejects** (STT-only, no TTS adapter, unreachable). |
| `app/src/main/java/com/whispereverywhere/ui/screens/EnginesAndVoicesScreen.kt` | **Modify.** `staticVoices` when → `SONIOX -> null` (unreachable). |
| `app/src/main/java/com/whispereverywhere/ui/screens/SettingsScreen.kt` | **Modify.** Three legacy TTS formatters (`ttsProviderPriceNote`, `ttsNoSpeedControlNote`, `cloudVoiceDisplayName`) — Soniox branches, unreachable but must compile. |
| `app/src/main/java/com/whispereverywhere/transcription/cloud/SonioxStt.kt` | **Create.** The async upload→create→poll→fetch→delete pipeline. |
| `app/src/test/java/com/whispereverywhere/transcription/cloud/SonioxSttTest.kt` | **Create.** Mirrors `OpenAiSttTest`'s rigor + the pipeline/cleanup/poll-budget cases. |
| `app/src/main/java/com/whispereverywhere/transcription/cloud/SttProviderFactory.kt` | **Modify.** `SONIOX -> SonioxStt(...)` (Task 4). |
| `app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt` | **Modify.** Add `SONIOX` to the private `STT_PROVIDERS` set (Task 4). |
| `app/src/test/java/com/whispereverywhere/service/EngineSelectionTest.kt` | **Modify.** `resolves_soniox` + batch-clamps-soniox (Task 4). |
| `app/src/test/java/com/whispereverywhere/ui/screens/CloudProvidersScreenLogicTest.kt` | **Modify.** The STT-capable-set assertion (Task 4). |

`SttProvider`, `SttError`, `FatalKind`, `SttResult`, `WavWriter`, `CloudTranscriptionEngine`, the fallback/routed engines, `KeyValidator`, `BatchTranscriptionService`, batch/live clamps: **untouched** — re-asserted, not edited.

---

## Task 1: `HttpTransport.delete` — a DELETE verb for cleanup

**Files:** modify `HttpTransport.kt`, `FakeHttpTransport.kt`. Independent — compiles and stays green alone.

**Why new:** the transport has `get`, `postMultipart`, `postJson`, `postForBytes` — **no DELETE**. Soniox's mandatory cleanup (`DELETE /v1/transcriptions/{id}`, `DELETE /v1/files/{id}`) needs one.

- [ ] **Step 1: Interface** — add to `HttpTransport`:

```kotlin
    /**
     * DELETE a resource. Soniox's async STT stores the uploaded audio + transcript server-side
     * until the caller deletes them, so every segment MUST delete both on the way out (success,
     * error, or cancellation). Short-timeout profile like [get] — the body is a tiny 204/ack.
     */
    suspend fun delete(
        url: String,
        headers: Map<String, String>,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): HttpResult
```

- [ ] **Step 2: `OkHttpTransport`** — mirror `get`, with body construction INSIDE the try (the Headers.checkValue credential-leak reason, identical to every other method here):

```kotlin
    override suspend fun delete(url: String, headers: Map<String, String>, timeoutMs: Long): HttpResult {
        return try {
            val request = Request.Builder().url(url).delete().apply {
                headers.forEach { (k, v) -> header(k, v) }
            }.build()
            val call = client.newBuilder()
                .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .build()
                .newCall(request)
            val response = call.await()
            val body = response.use { it.body?.string().orEmpty() }
            if (response.isSuccessful) HttpResult.Ok(response.code, body)
            else HttpResult.HttpError(response.code, body)
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c   // rethrow FIRST — must unwind, not be reported as a network failure
        } catch (e: Exception) {
            HttpResult.NetworkError(e)
        }
    }
```

- [ ] **Step 3: `FakeHttpTransport`** — record deletes SEPARATELY from `script`. A poll-GET and a cleanup-DELETE hit the same `/v1/transcriptions/{id}` URL; routing DELETE through `script` (which sees only url+headers, not the verb) would make them indistinguishable. So delete gets its own recorder and its own settable result:

```kotlin
    val deletedUrls = mutableListOf<String>()
        // read-only to tests via the list itself
    var deleteResult: HttpResult = HttpResult.Ok(204, "")

    override suspend fun delete(url: String, headers: Map<String, String>, timeoutMs: Long): HttpResult {
        // Deliberately does NOT touch lastUrl/lastHeaders/callCount: a test asserts on the poll or
        // fetch it made, and cleanup running afterward must not overwrite those. Deletes are their
        // own observation channel.
        deletedUrls.add(url)
        return deleteResult
    }
```

- [ ] **Step 4: Verify** — `.\gradlew.bat :app:testDebugUnitTest --no-daemon` (634, 0 failures — interface + fake compile, no behaviour change) then `:app:assembleRelease --no-daemon` green.

- [ ] **Step 5: Commit**

```powershell
git add app/src/main/java/com/whispereverywhere/net/HttpTransport.kt app/src/test/java/com/whispereverywhere/net/FakeHttpTransport.kt
git commit -m "feat(net): HttpTransport.delete for Soniox async cleanup"
```

---

## Task 2: The enum cascade — `ProviderId.SONIOX` + catalog + every exhaustive `when`

Adding `SONIOX` to the enum breaks every exhaustive `when (ProviderId)` at compile time. This task makes the 4-provider world **compile and stay green** WITHOUT yet making Soniox selectable — it is added to neither STT set, so `resolveSttProvider("SONIOX")` still returns null and the factory's Soniox branch is unreachable. The adapter is built in Task 3, wired in Task 4.

**Files:** `ProviderCatalog.kt`, `ProviderCatalogTest.kt`, `CloudProvidersScreen.kt`, `TtsProviderFactory.kt`, `EnginesAndVoicesScreen.kt`, `SettingsScreen.kt`, `SttProviderFactory.kt`.

- [ ] **Step 1: Update the assertions that pin the 3-provider world** in `ProviderCatalogTest.kt` (they compile fine but will fail at 4 — update them to describe the intended world first, TDD-style):

```kotlin
    @Test fun all_four_providers_are_present_in_order() {
        assertEquals(
            listOf(ProviderId.OPENAI, ProviderId.GEMINI, ProviderId.ELEVENLABS, ProviderId.SONIOX),
            ProviderCatalog.all.map { it.id },
        )
    }

    // Replaces every_provider_supports_both_modalities: Soniox is STT-only, so the old
    // "everyone supports TTS" invariant is deliberately no longer true.
    @Test fun every_provider_is_stt_capable() {
        ProviderCatalog.all.forEach { assertTrue("${it.id} STT", it.supportsStt) }
    }

    @Test fun soniox_is_stt_only_no_tts_no_streaming() {
        val s = ProviderCatalog.byId(ProviderId.SONIOX)
        assertTrue(s.supportsStt)
        assertFalse("Soniox ships no TTS adapter this wave", s.supportsTts)
        assertFalse("Soniox realtime WS is a follow-up, not v1", s.supportsStreaming)
    }

    @Test fun soniox_uses_a_bearer_authorization_header() {
        val s = ProviderCatalog.byId(ProviderId.SONIOX)
        assertEquals("Authorization", s.authHeaderName)
        assertEquals("Bearer sk-test", s.authHeaderValue("sk-test"))
    }

    @Test fun soniox_does_not_train_on_data_by_default() {
        // Quote-backed: "never used to improve Soniox models or services."
        assertFalse(ProviderCatalog.byId(ProviderId.SONIOX).trainsOnDataByDefault)
    }

    @Test fun soniox_validation_url_is_the_models_endpoint() {
        assertEquals(
            "https://api.soniox.com/v1/models",
            ProviderCatalog.byId(ProviderId.SONIOX).validationUrl,
        )
    }
```
Also add `assertEquals("SONIOX", ProviderId.SONIOX.name)` to `ids_are_stable_by_name_not_ordinal`. `every_url_is_https` iterates `all`, so it auto-covers Soniox's (both https) — no edit. Keep the existing OpenAI/Gemini/ElevenLabs tests unchanged.

- [ ] **Step 2: The enum + catalog entry** in `ProviderCatalog.kt`. Add `SONIOX` **last** (persistence keys off `name`, never ordinal):

```kotlin
enum class ProviderId { OPENAI, GEMINI, ELEVENLABS, SONIOX }
```

Append to `all` (after ElevenLabs):

```kotlin
        Provider(
            id = ProviderId.SONIOX,
            displayName = "Soniox",
            // Bearer, like OpenAI — unlike ElevenLabs' bare xi-api-key / Gemini's bare header key.
            authHeaderName = "Authorization",
            authHeaderValue = { "Bearer $it" },
            // Cheapest authenticated GET; a bad key returns 401 unauthenticated, which the generic
            // KeyValidator already maps to Invalid — no per-provider marker needed (Soniox uses a
            // clean 401 for a bad key, and 402 for exhausted balance, both already handled).
            validationUrl = "https://api.soniox.com/v1/models",
            supportsStt = true,
            // STT-only this wave. Soniox has a TTS surface, but no adapter ships here; it stays out
            // of TTS_CAPABLE_PROVIDERS and TtsProviderFactory rejects it.
            supportsTts = false,
            // v1 is the per-VAD-segment async path (like Gemini/ElevenLabs). The realtime WebSocket
            // is a recorded follow-up, not this wave.
            supportsStreaming = false,
            // Verify against the live Console at resolve time; the API-keys page lives under the
            // Soniox Console.
            keyHelpUrl = "https://console.soniox.com/",
            // Quote-backed from Security & Privacy: audio + transcripts are "never used to improve
            // Soniox models or services." (The async path DOES store them until we DELETE — the
            // disclosure line pairs no-training with delete-after, which the adapter's cleanup makes
            // literally true.)
            trainsOnDataByDefault = false,
        ),
```
`TTS_CAPABLE_PROVIDERS` (hand-maintained, NOT compiler-forced) — **consciously leave Soniox out.**

- [ ] **Step 3: The compiler-forced `when` sites.** Each breaks until it gets a Soniox branch; fix every one properly, no TODO.

  1. `CloudProvidersScreen.kt` `providerTrainingDisclosure` — the researched line (sourced, present-tense, style-matched to the others):
     ```kotlin
    ProviderId.SONIOX ->
        "Soniox does not train on audio or transcripts sent through its API; uploaded " +
            "audio is deleted right after each transcription."
     ```
  2. `TtsProviderFactory.kt` — Soniox has no TTS adapter; **reject** (unreachable because `TTS_CAPABLE_PROVIDERS` excludes it, but the `when` is exhaustive):
     ```kotlin
        ProviderId.SONIOX -> error("Soniox has no TTS adapter — it is speech-to-text only")
     ```
  3. `EnginesAndVoicesScreen.kt` `staticVoices` when (~L435):
     ```kotlin
        ProviderId.SONIOX -> null // STT-only; never enters the TTS voice list
     ```
  4. `SettingsScreen.kt` `ttsProviderPriceNote` (~L64):
     ```kotlin
        ProviderId.SONIOX ->
            "Soniox is transcription-only; it has no read-aloud voices."
     ```
  5. `SettingsScreen.kt` `ttsNoSpeedControlNote` (~L79) — no TTS, so no note:
     ```kotlin
        ProviderId.SONIOX -> null
     ```
  6. `SettingsScreen.kt` `cloudVoiceDisplayName`'s `catalog = when` (~L97):
     ```kotlin
        ProviderId.SONIOX -> emptyList()
     ```

- [ ] **Step 4: Keep `SttProviderFactory` compiling — temporary reject.** The factory's `when` is now non-exhaustive. The adapter does not exist yet (Task 3) and Soniox is not selectable yet (not in either STT set, so this branch is unreachable). Add a temporary reject that Task 4 replaces:
     ```kotlin
        // Wired to SonioxStt in Task 4. Unreachable until then: SONIOX is in neither
        // STT_CAPABLE_PROVIDERS nor FloatingBubbleService.STT_PROVIDERS, so resolveSttProvider
        // never yields it and this branch is never constructed.
        ProviderId.SONIOX -> error("Soniox STT adapter is wired in Task 4")
     ```

- [ ] **Step 5: Verify** — full suite green (634 + the six new `ProviderCatalogTest` cases; the renamed one changes meaning, not count) and `assembleRelease` green (the enum cascade compiles everywhere).

```powershell
.\gradlew.bat :app:testDebugUnitTest --no-daemon
.\gradlew.bat :app:assembleRelease --no-daemon
```

- [ ] **Step 6: Commit**

```powershell
git add app/src/main/java/com/whispereverywhere/provider/ProviderCatalog.kt app/src/test/java/com/whispereverywhere/provider/ProviderCatalogTest.kt app/src/main/java/com/whispereverywhere/ui/screens/CloudProvidersScreen.kt app/src/main/java/com/whispereverywhere/tts/cloud/TtsProviderFactory.kt app/src/main/java/com/whispereverywhere/ui/screens/EnginesAndVoicesScreen.kt app/src/main/java/com/whispereverywhere/ui/screens/SettingsScreen.kt app/src/main/java/com/whispereverywhere/transcription/cloud/SttProviderFactory.kt
git commit -m "feat(provider): ProviderId.SONIOX + catalog entry + exhaustive-when cascade"
```

---

## Task 3: `SonioxStt` — the async upload→create→poll→fetch→delete adapter

**Files:** create `SonioxStt.kt` + `SonioxSttTest.kt`.

**Interfaces:**
- Consumes: `HttpTransport` (`postMultipart` + `postJson` + `get` + `delete`), `WavWriter`, `ProviderCatalog`, `SttProvider`/`SttResult`/`SttError`/`FatalKind`.
- Produces: `class SonioxStt(transport, apiKey, model = DEFAULT_MODEL, pollIntervalMs = 1000L, maxPolls = 40)`.

**Two design decisions that carry the risk:**

- **The poll loop is bounded and never hangs a turn.** 1 s interval, `maxPolls` cap (~40 s wall-clock). If the job has not `completed` within the budget, the segment returns **`Transient`** — the fallback engine takes it local. A dictation turn must never block on a stuck Soniox job. `pollIntervalMs`/`maxPolls` are constructor params (defaults 1000/40) purely so tests drive them at `0`/`2` — production always uses the defaults.
- **Cleanup ALWAYS runs — even on cancellation.** From the moment the file is uploaded, a `try { … } finally { … }` deletes the transcription (if created) and the file, inside `withContext(NonCancellable)` so a cancelled coroutine (user cancels mid-poll) still deletes the audio Soniox is storing. A leaked file counts against the 2,000-transcription cap AND leaves the user's audio on a third party's servers — both unacceptable.

- [ ] **Step 1: Write the failing test.** Create `SonioxSttTest.kt`. The `FakeHttpTransport` `script` dispatches by URL across the pipeline (`/files` = upload, `…/transcriptions` = create, `…/transcriptions/{id}/transcript` = fetch, `…/transcriptions/{id}` = poll); deletes are observed via `deletedUrls`. Use tiny `pollIntervalMs = 0L` so `runBlocking` is instant.

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

class SonioxSttTest {

    private val pcm = ByteArray(3200) { (it % 127).toByte() }

    private val fileOk = """{"id":"file-1"}"""
    private val createOk = """{"id":"job-1","status":"queued"}"""
    private val completed = """{"status":"completed"}"""
    private val transcriptOk = """{"tokens":[{"text":"hello "},{"text":"world"}]}"""

    /** A fake that walks the whole happy pipeline, dispatching by URL. */
    private fun happyFake() = FakeHttpTransport { url, _ ->
        when {
            url.endsWith("/files") -> HttpResult.Ok(200, fileOk)
            url.endsWith("/transcript") -> HttpResult.Ok(200, transcriptOk)
            url.endsWith("/transcriptions") -> HttpResult.Ok(201, createOk)
            url.contains("/transcriptions/") -> HttpResult.Ok(200, completed) // poll
            else -> error("unexpected url $url")
        }
    }

    private fun soniox(fake: FakeHttpTransport) = SonioxStt(fake, "soniox-key", pollIntervalMs = 0L)

    @Test fun happy_path_concatenates_tokens_into_the_transcript() = runBlocking {
        assertEquals(SttResult.Text("hello world"), soniox(happyFake()).transcribe(pcm, null))
    }

    @Test fun the_upload_is_a_wav_container_in_the_file_part() = runBlocking {
        val fake = happyFake()
        soniox(fake).transcribe(pcm, null)
        assertEquals("file", fake.lastFilePart?.fieldName)
        val sent = fake.lastFilePart!!.bytes
        assertEquals("RIFF", String(sent, 0, 4, Charsets.US_ASCII))
        assertEquals(pcm.size + 44, sent.size)
    }

    @Test fun every_call_sends_the_bearer_header_and_the_key_is_never_in_a_url() = runBlocking {
        val fake = happyFake()
        SonioxStt(fake, "soniox-secret", pollIntervalMs = 0L).transcribe(pcm, null)
        assertEquals("Bearer soniox-secret", fake.lastHeaders["Authorization"])
        assertFalse(fake.lastUrl!!.contains("soniox-secret"))
        fake.deletedUrls.forEach { assertFalse(it.contains("soniox-secret")) }
    }

    @Test fun create_sends_the_pinned_model_and_the_uploaded_file_id() = runBlocking {
        val fake = happyFake()
        soniox(fake).transcribe(pcm, null)
        val body = fake.lastJsonBody!! // last postJson body = the create call
        assertTrue(body.contains("stt-async-v5"))
        assertTrue(body.contains("file-1"))
    }

    @Test fun a_specific_language_becomes_a_hint_and_auto_omits_it() = runBlocking {
        val f1 = happyFake()
        SonioxStt(f1, "k", pollIntervalMs = 0L).transcribe(pcm, "fr")
        assertTrue(f1.lastJsonBody!!.contains("language_hints"))
        assertTrue(f1.lastJsonBody!!.contains("fr"))
        // Multilingual auto-detect is the default: null AND "auto" both omit the hint.
        val f2 = happyFake(); SonioxStt(f2, "k", pollIntervalMs = 0L).transcribe(pcm, null)
        assertFalse(f2.lastJsonBody!!.contains("language_hints"))
        val f3 = happyFake(); SonioxStt(f3, "k", pollIntervalMs = 0L).transcribe(pcm, "auto")
        assertFalse(f3.lastJsonBody!!.contains("language_hints"))
    }

    @Test fun happy_path_deletes_both_the_transcription_and_the_file() = runBlocking {
        val fake = happyFake()
        soniox(fake).transcribe(pcm, null)
        assertTrue(fake.deletedUrls.any { it.endsWith("/transcriptions/job-1") })
        assertTrue(fake.deletedUrls.any { it.endsWith("/files/file-1") })
    }

    @Test fun a_failed_create_still_deletes_the_uploaded_file() = runBlocking {
        val fake = FakeHttpTransport { url, _ ->
            when {
                url.endsWith("/files") -> HttpResult.Ok(200, fileOk)
                url.endsWith("/transcriptions") -> HttpResult.HttpError(400, "invalid_request")
                else -> error("unexpected $url")
            }
        }
        val r = soniox(fake).transcribe(pcm, null) as SttResult.Failed
        assertEquals(SttError.BadSegment, r.error)
        assertTrue("file must be cleaned up even though no job was created",
            fake.deletedUrls.any { it.endsWith("/files/file-1") })
    }

    @Test fun a_failed_job_returns_transient_and_deletes_both() = runBlocking {
        val fake = FakeHttpTransport { url, _ ->
            when {
                url.endsWith("/files") -> HttpResult.Ok(200, fileOk)
                url.endsWith("/transcriptions") -> HttpResult.Ok(201, createOk)
                url.contains("/transcriptions/") ->
                    HttpResult.Ok(200, """{"status":"error","error_type":"internal_error"}""")
                else -> error("unexpected $url")
            }
        }
        val r = soniox(fake).transcribe(pcm, null) as SttResult.Failed
        assertTrue(r.error is SttError.Transient)
        assertTrue(fake.deletedUrls.any { it.endsWith("/transcriptions/job-1") })
        assertTrue(fake.deletedUrls.any { it.endsWith("/files/file-1") })
    }

    @Test fun a_stuck_job_returns_transient_when_the_poll_budget_is_exhausted_and_cleans_up() = runBlocking {
        val fake = FakeHttpTransport { url, _ ->
            when {
                url.endsWith("/files") -> HttpResult.Ok(200, fileOk)
                url.endsWith("/transcriptions") -> HttpResult.Ok(201, createOk)
                url.contains("/transcriptions/") -> HttpResult.Ok(200, """{"status":"processing"}""")
                else -> error("unexpected $url")
            }
        }
        val stt = SonioxStt(fake, "k", pollIntervalMs = 0L, maxPolls = 2)
        val r = stt.transcribe(pcm, null) as SttResult.Failed
        assertTrue("a stuck job must fall local, never hang", r.error is SttError.Transient)
        assertTrue(fake.deletedUrls.any { it.endsWith("/files/file-1") })
    }

    @Test fun a_401_on_upload_is_fatal_invalid_key() = runBlocking {
        val fake = FakeHttpTransport { _, _ -> HttpResult.HttpError(401, "unauthenticated") }
        val r = soniox(fake).transcribe(pcm, null) as SttResult.Failed
        assertEquals(FatalKind.INVALID_KEY, (r.error as SttError.Fatal).kind)
    }

    @Test fun a_401_with_a_balance_marker_is_out_of_credit_not_a_bad_key() = runBlocking {
        val fake = FakeHttpTransport { _, _ -> HttpResult.HttpError(401, """{"error_type":"insufficient_balance"}""") }
        val r = soniox(fake).transcribe(pcm, null) as SttResult.Failed
        assertEquals(FatalKind.OUT_OF_CREDIT, (r.error as SttError.Fatal).kind)
    }

    @Test fun a_402_is_out_of_credit() = runBlocking {
        val fake = FakeHttpTransport { _, _ -> HttpResult.HttpError(402, "balance exhausted") }
        val r = soniox(fake).transcribe(pcm, null) as SttResult.Failed
        assertEquals(FatalKind.OUT_OF_CREDIT, (r.error as SttError.Fatal).kind)
    }

    @Test fun a_404_on_create_is_model_unavailable() = runBlocking {
        // 404 on the CREATE call means the pinned model id is wrong/retired — a permanent config
        // fault, latched, not a per-segment hiccup.
        val fake = FakeHttpTransport { url, _ ->
            when {
                url.endsWith("/files") -> HttpResult.Ok(200, fileOk)
                url.endsWith("/transcriptions") -> HttpResult.HttpError(404, "model not found")
                else -> error("unexpected $url")
            }
        }
        val r = soniox(fake).transcribe(pcm, null) as SttResult.Failed
        assertEquals(FatalKind.MODEL_UNAVAILABLE, (r.error as SttError.Fatal).kind)
    }

    @Test fun a_404_on_poll_is_transient_not_model_unavailable() = runBlocking {
        // Same status code, different step: a missing file/transcription is odd server state, not a
        // model fault. Must NOT latch the provider off.
        val fake = FakeHttpTransport { url, _ ->
            when {
                url.endsWith("/files") -> HttpResult.Ok(200, fileOk)
                url.endsWith("/transcriptions") -> HttpResult.Ok(201, createOk)
                url.contains("/transcriptions/") -> HttpResult.HttpError(404, "not found")
                else -> error("unexpected $url")
            }
        }
        val r = soniox(fake).transcribe(pcm, null) as SttResult.Failed
        assertTrue(r.error is SttError.Transient)
    }

    @Test fun a_400_is_a_bad_segment() = runBlocking {
        val fake = FakeHttpTransport { _, _ -> HttpResult.HttpError(400, "invalid_request") }
        assertEquals(SttError.BadSegment, (soniox(fake).transcribe(pcm, null) as SttResult.Failed).error)
    }

    @Test fun a_403_is_fatal_forbidden() = runBlocking {
        val fake = FakeHttpTransport { _, _ -> HttpResult.HttpError(403, "") }
        val r = soniox(fake).transcribe(pcm, null) as SttResult.Failed
        assertEquals(FatalKind.FORBIDDEN, (r.error as SttError.Fatal).kind)
    }

    @Test fun a_plain_429_is_transient_but_a_429_with_balance_is_out_of_credit() = runBlocking {
        val plain = FakeHttpTransport { _, _ -> HttpResult.HttpError(429, "limit_exceeded") }
        assertTrue((soniox(plain).transcribe(pcm, null) as SttResult.Failed).error is SttError.Transient)
        val quota = FakeHttpTransport { _, _ -> HttpResult.HttpError(429, "insufficient_balance") }
        val r = soniox(quota).transcribe(pcm, null) as SttResult.Failed
        assertEquals(FatalKind.OUT_OF_CREDIT, (r.error as SttError.Fatal).kind)
    }

    @Test fun a_5xx_is_transient() = runBlocking {
        val fake = FakeHttpTransport { _, _ -> HttpResult.HttpError(503, "upstream") }
        assertTrue((soniox(fake).transcribe(pcm, null) as SttResult.Failed).error is SttError.Transient)
    }

    @Test fun a_network_error_is_offline() = runBlocking {
        val fake = FakeHttpTransport { _, _ -> HttpResult.NetworkError(IOException("no route")) }
        assertEquals(SttError.Offline, (soniox(fake).transcribe(pcm, null) as SttResult.Failed).error)
    }

    @Test fun an_unparseable_transcript_body_is_transient_not_silently_empty() = runBlocking {
        val fake = FakeHttpTransport { url, _ ->
            when {
                url.endsWith("/files") -> HttpResult.Ok(200, fileOk)
                url.endsWith("/transcript") -> HttpResult.Ok(200, "not json")
                url.endsWith("/transcriptions") -> HttpResult.Ok(201, createOk)
                url.contains("/transcriptions/") -> HttpResult.Ok(200, completed)
                else -> error("unexpected $url")
            }
        }
        assertTrue((soniox(fake).transcribe(pcm, null) as SttResult.Failed).error is SttError.Transient)
    }

    @Test fun a_completed_job_with_no_tokens_is_a_legitimate_empty_transcript() = runBlocking {
        val fake = FakeHttpTransport { url, _ ->
            when {
                url.endsWith("/files") -> HttpResult.Ok(200, fileOk)
                url.endsWith("/transcript") -> HttpResult.Ok(200, """{"tokens":[]}""")
                url.endsWith("/transcriptions") -> HttpResult.Ok(201, createOk)
                url.contains("/transcriptions/") -> HttpResult.Ok(200, completed)
                else -> error("unexpected $url")
            }
        }
        assertEquals(SttResult.Text(""), soniox(fake).transcribe(pcm, null))
    }

    @Test fun oversized_audio_fails_locally_without_a_request() = runBlocking {
        val fake = happyFake()
        val huge = ByteArray(26 * 1024 * 1024)
        val r = SonioxStt(fake, "k", pollIntervalMs = 0L).transcribe(huge, null) as SttResult.Failed
        assertEquals(SttError.BadSegment, r.error)
        assertEquals(0, fake.callCount)
        assertTrue(fake.deletedUrls.isEmpty())
    }
}
```

- [ ] **Step 2: Run to verify it fails** — `Unresolved reference: SonioxStt`.

- [ ] **Step 3: Implement.** Create `SonioxStt.kt`:

```kotlin
package com.whispereverywhere.transcription.cloud

import com.whispereverywhere.audio.WavWriter
import com.whispereverywhere.net.HttpResult
import com.whispereverywhere.net.HttpTransport
import com.whispereverywhere.provider.ProviderCatalog
import com.whispereverywhere.provider.ProviderId
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// --- DTOs: every field defaulted so a missing node is ""/empty, never a throw ---
@Serializable private data class SonioxFile(val id: String = "")
@Serializable private data class SonioxCreated(val id: String = "", val status: String = "")
@Serializable private data class SonioxStatus(
    val status: String = "",
    val error_type: String? = null,
    val error_message: String? = null,
)
@Serializable private data class SonioxTranscript(val tokens: List<SonioxToken> = emptyList())
@Serializable private data class SonioxToken(val text: String = "")
@Serializable private data class SonioxCreateReq(
    val model: String,
    val file_id: String,
    val language_hints: List<String>? = null,
)

/**
 * Soniox multilingual transcription. UNLIKE the other three adapters — which each do a single POST —
 * Soniox has NO synchronous endpoint, so one committed segment is an async job:
 *   upload (POST /v1/files) -> create (POST /v1/transcriptions) -> poll (GET, 1 s) -> fetch
 *   (GET .../transcript) -> DELETE the transcription AND the file.
 *
 * Load-bearing details:
 *  - THE KEY RIDES THE `Authorization: Bearer` HEADER. No URL carries it; logs are status-code-only.
 *  - MULTILINGUAL AUTO-DETECT IS THE DEFAULT — language_hints is omitted for null/blank/"auto", so
 *    Soniox detects the language itself; a specific preference narrows it.
 *  - THE ASYNC PATH STORES the audio + transcript server-side until deleted, so cleanup runs on
 *    EVERY exit — success, error, and cancellation (NonCancellable) — never leaking user audio.
 *  - THE POLL LOOP IS BOUNDED: a job that has not completed within maxPolls returns Transient so
 *    the segment falls to on-device rather than hanging the dictation turn.
 *  - 404 IS STEP-SENSITIVE: on CREATE it means the model id is wrong/retired -> Fatal
 *    MODEL_UNAVAILABLE (latched); on a later call it is odd server state -> Transient.
 *  - AMBIGUOUS STATUS SPLIT: a 401/429 whose body carries a balance marker is OUT_OF_CREDIT, not a
 *    bad key / not a plain rate-limit. A 200 whose transcript will not parse is Transient, not "".
 */
class SonioxStt(
    private val transport: HttpTransport,
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
    private val pollIntervalMs: Long = 1_000L,
    private val maxPolls: Int = 40,
) : SttProvider {

    override val id = ProviderId.SONIOX

    /** ~25 MB request cap. A ≤15 s segment is ~480 KB; the gate only guards against a runaway. */
    override val maxRequestBytes = 25L * 1024 * 1024

    private enum class Step { UPLOAD, CREATE, POLL, FETCH }

    override suspend fun transcribe(pcm: ByteArray, language: String?): SttResult {
        if (pcm.size.toLong() + WAV_HEADER_BYTES > maxRequestBytes) {
            return SttResult.Failed(SttError.BadSegment)
        }
        val provider = ProviderCatalog.byId(ProviderId.SONIOX)
        val headers = mapOf(provider.authHeaderName to provider.authHeaderValue(apiKey))

        // 1. Upload the WAV. Nothing is stored server-side yet, so a failure here needs no cleanup.
        val fileId = when (val up = transport.postMultipart(
            url = FILES_URL,
            headers = headers,
            filePart = HttpTransport.FilePart("file", "audio.wav", "audio/wav", WavWriter.wrap(pcm)),
            fields = emptyMap(),
        )) {
            is HttpResult.NetworkError -> return SttResult.Failed(SttError.Offline)
            is HttpResult.HttpError -> return SttResult.Failed(classify(up.code, up.body, Step.UPLOAD))
            is HttpResult.Ok -> parse<SonioxFile>(up.body)?.id?.takeIf { it.isNotBlank() }
                ?: return SttResult.Failed(SttError.Transient(null)) // unparseable-200
        }

        // From here the file EXISTS on Soniox's servers — it MUST be deleted on every path out.
        var transcriptionId: String? = null
        try {
            // 2. Create the job.
            val reqBody = JSON.encodeToString(
                SonioxCreateReq.serializer(),
                SonioxCreateReq(model = model, file_id = fileId, language_hints = hints(language)),
            )
            val tid = when (val cr = transport.postJson(TRANSCRIPTIONS_URL, headers, reqBody)) {
                is HttpResult.NetworkError -> return SttResult.Failed(SttError.Offline)
                is HttpResult.HttpError -> return SttResult.Failed(classify(cr.code, cr.body, Step.CREATE))
                is HttpResult.Ok -> parse<SonioxCreated>(cr.body)?.id?.takeIf { it.isNotBlank() }
                    ?: return SttResult.Failed(SttError.Transient(null))
            }
            transcriptionId = tid

            // 3. Poll to a terminal state, BOUNDED. Not-completed-in-budget -> Transient (falls local).
            var completed = false
            var polls = 0
            while (polls < maxPolls) {
                delay(pollIntervalMs)
                when (val st = transport.get("$TRANSCRIPTIONS_URL/$tid", headers)) {
                    is HttpResult.NetworkError -> return SttResult.Failed(SttError.Offline)
                    is HttpResult.HttpError -> return SttResult.Failed(classify(st.code, st.body, Step.POLL))
                    is HttpResult.Ok -> {
                        val status = parse<SonioxStatus>(st.body)
                            ?: return SttResult.Failed(SttError.Transient(null))
                        val state = status.status.lowercase()
                        when {
                            state in IN_FLIGHT -> { polls++; continue }
                            state == COMPLETED -> { completed = true }
                            // Terminal failure ("error"/"failed"/anything else): fall local. An
                            // unsupported-audio error_type is this segment's fault (BadSegment); any
                            // other job failure is Transient. Both still clean up in `finally`.
                            else -> return SttResult.Failed(mapJobFailure(status))
                        }
                    }
                }
                if (completed) break
            }
            if (!completed) return SttResult.Failed(SttError.Transient(null))

            // 4. Fetch + assemble from tokens (NO reliance on an unconfirmed top-level `text`).
            return when (val tr = transport.get("$TRANSCRIPTIONS_URL/$tid/transcript", headers)) {
                is HttpResult.NetworkError -> SttResult.Failed(SttError.Offline)
                is HttpResult.HttpError -> SttResult.Failed(classify(tr.code, tr.body, Step.FETCH))
                is HttpResult.Ok -> {
                    val parsed = parse<SonioxTranscript>(tr.body)
                        ?: return SttResult.Failed(SttError.Transient(null)) // unparseable-200
                    SttResult.Text(parsed.tokens.joinToString("") { it.text })
                }
            }
        } finally {
            // 5. Cleanup — NonCancellable so a coroutine cancelled mid-poll still deletes the audio
            //    Soniox is storing. Delete failures are swallowed: they must never mask the result,
            //    and a stray undeleted file is a quota problem, not a user-visible one.
            withContext(NonCancellable) {
                transcriptionId?.let { runCatching { transport.delete("$TRANSCRIPTIONS_URL/$it", headers) } }
                runCatching { transport.delete("$FILES_URL/$fileId", headers) }
            }
        }
    }

    /** Multilingual auto-detect is the default: omit hints unless a concrete language is set. */
    private fun hints(language: String?): List<String>? =
        if (language.isNullOrBlank() || language == "auto") null else listOf(language)

    private fun mapJobFailure(status: SonioxStatus): SttError {
        android.util.Log.w("WE-DIAG", "soniox stt job ${status.status} type=${status.error_type}")
        val marker = "${status.error_type.orEmpty()} ${status.error_message.orEmpty()}"
        return if (AUDIO_ERROR_MARKERS.any { marker.contains(it, ignoreCase = true) }) {
            SttError.BadSegment
        } else {
            SttError.Transient(null)
        }
    }

    private fun classify(code: Int, body: String, step: Step): SttError {
        android.util.Log.w("WE-DIAG", "soniox stt http $code step=$step") // status + step ONLY
        return when (code) {
            401 -> if (BALANCE_MARKERS.any { body.contains(it, ignoreCase = true) }) {
                SttError.Fatal(FatalKind.OUT_OF_CREDIT, "Account has no remaining credit")
            } else {
                SttError.Fatal(FatalKind.INVALID_KEY, "Key rejected")
            }
            402 -> SttError.Fatal(FatalKind.OUT_OF_CREDIT, "Account has no remaining credit")
            403 -> SttError.Fatal(FatalKind.FORBIDDEN, "Access denied for this key")
            400, 413 -> SttError.BadSegment
            // Model id lives in the create body: a 404 there = wrong/retired model (permanent,
            // latch). A 404 on poll/fetch = missing file/transcription = odd server state (Transient).
            404 -> if (step == Step.CREATE) {
                SttError.Fatal(FatalKind.MODEL_UNAVAILABLE, "Transcription model unavailable")
            } else {
                SttError.Transient(null)
            }
            409 -> SttError.Transient(null)
            429 -> if (BALANCE_MARKERS.any { body.contains(it, ignoreCase = true) }) {
                SttError.Fatal(FatalKind.OUT_OF_CREDIT, "Account has no remaining credit")
            } else {
                SttError.Transient(null)
            }
            in 500..599 -> SttError.Transient(null)
            else -> SttError.Transient(null)
        }
    }

    private inline fun <reified T> parse(body: String): T? =
        runCatching { JSON.decodeFromString<T>(body) }.getOrNull()

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = false } // kotlinx, NOT org.json
        const val DEFAULT_MODEL = "stt-async-v5" // async multilingual; v4 auto-routes here
        private const val BASE = "https://api.soniox.com/v1"
        private const val FILES_URL = "$BASE/files"
        private const val TRANSCRIPTIONS_URL = "$BASE/transcriptions"
        private const val WAV_HEADER_BYTES = 44
        private val IN_FLIGHT = setOf("queued", "processing", "downloading", "transcribing")
        private const val COMPLETED = "completed"
        // Balance/credit markers Soniox may put under an ambiguous 401/429 (fact sheet: 402 is the
        // clean code, but the split is cheap insurance and matches the ElevenLabs lesson).
        private val BALANCE_MARKERS = listOf("insufficient_balance", "balance", "budget", "exhausted")
        // A job-failure error_type that blames the audio -> this segment's fault (BadSegment).
        private val AUDIO_ERROR_MARKERS = listOf("audio", "decode", "unsupported", "invalid_request")
    }
}
```
> Note the `encodeDefaults = false` on `JSON`: it keeps `language_hints` out of the create body when it is null (auto-detect), which the language test asserts.

- [ ] **Step 4: Verify** — `--tests "…SonioxSttTest"` PASS (~24 cases). Full suite green. Then `assembleRelease` (the kotlinx serializer + reified `parse` must survive R8 — DTOs are `@Serializable`, so the plugin keeps them).

- [ ] **Step 5: Commit**

```powershell
git add app/src/main/java/com/whispereverywhere/transcription/cloud/SonioxStt.kt app/src/test/java/com/whispereverywhere/transcription/cloud/SonioxSttTest.kt
git commit -m "feat(cloud): Soniox STT adapter (async upload/poll/fetch/delete, multilingual)"
```

---

## Task 4: Wire Soniox into selection (batch + live clamps re-asserted)

**Files:** `SttProviderFactory.kt`, `CloudProvidersScreen.kt`, `FloatingBubbleService.kt`, `EngineSelectionTest.kt`, `CloudProvidersScreenLogicTest.kt`.

- [ ] **Step 1: Factory — replace the Task 2 temporary reject** in `SttProviderFactory.kt`:
```kotlin
        ProviderId.SONIOX -> SonioxStt(transport, apiKey)
```

- [ ] **Step 2: Both parallel "has an adapter" sets gain SONIOX** (only one is compiler-checked; the other silently falls to on-device if missed):
  - `CloudProvidersScreen.kt` `STT_CAPABLE_PROVIDERS` (~L123):
    ```kotlin
    internal val STT_CAPABLE_PROVIDERS: Set<ProviderId> =
        setOf(ProviderId.OPENAI, ProviderId.GEMINI, ProviderId.ELEVENLABS, ProviderId.SONIOX)
    ```
  - `FloatingBubbleService.kt` private `STT_PROVIDERS` (~L112):
    ```kotlin
    private val STT_PROVIDERS =
        setOf(ProviderId.OPENAI, ProviderId.GEMINI, ProviderId.ELEVENLABS, ProviderId.SONIOX)
    ```

- [ ] **Step 3: Tests.** In `CloudProvidersScreenLogicTest.kt`, update the STT-capable-set assertion (~L217) to include SONIOX and rename to `stt_capable_set_includes_soniox`. In `EngineSelectionTest.kt` add:
    ```kotlin
    @Test fun resolves_soniox_now_that_its_adapter_ships() {
        assertEquals(ProviderId.SONIOX, resolveSttProvider("SONIOX"))
    }

    @Test fun batch_clamps_soniox_to_null_batch_stays_openai_only() {
        // Live mode gets Soniox; batch's engineUsed decision was NOT widened, so a Soniox selection
        // degrades to on-device here rather than mis-keying OpenAI with a Soniox key.
        assertNull(resolveBatchSttProvider("SONIOX"))
    }
    ```
  `every_catalog_provider_has_a_training_disclosure_line` and `every_provider_can_deselect_itself` iterate the catalog and auto-cover Soniox — no edit. `decideEngineChoice`, `liveModeRowVisible`, `dictationLiveActive`, and `resolveBatchSttProvider`'s `takeIf { it == OPENAI }` are byte-unchanged: Soniox routes to `CLOUD_WITH_FALLBACK`, never shows the live toggle, and auto-degrades in batch — all a consequence of existing logic, now confirmed by the new tests.

- [ ] **Step 4: Verify** — full suite green (baseline + Soniox suite + the two new selection tests + the six catalog tests; the two renamed asserts change meaning, not count). `assembleRelease` green.
```powershell
.\gradlew.bat :app:testDebugUnitTest --no-daemon
.\gradlew.bat :app:assembleRelease --no-daemon
```

- [ ] **Step 5: Commit**
```powershell
git add app/src/main/java/com/whispereverywhere/transcription/cloud/SttProviderFactory.kt app/src/main/java/com/whispereverywhere/ui/screens/CloudProvidersScreen.kt app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt app/src/test/java/com/whispereverywhere/service/EngineSelectionTest.kt app/src/test/java/com/whispereverywhere/ui/screens/CloudProvidersScreenLogicTest.kt
git commit -m "feat(cloud): wire Soniox into STT selection (batch + live stay OpenAI-only)"
```

---

## Task 5: Docs — the disclosure sourcing note + the multilingual caveat

No compliance/disclosure *dialog* change is required — the consent triad's meaning is provider-count-independent, and Soniox's per-provider training line already ships (Task 2). Two things ARE worth recording so an audit checks the decision rather than missing it:

- [ ] **Training-disclosure sourcing.** In whatever provider ledger/notes doc this branch keeps (e.g. `docs/superpowers/` provider notes; if none exists, a short `docs/notes/soniox-disclosure.md`), record the quote-backed basis: `trainsOnDataByDefault = false` from *"your audio and transcripts are never used to improve Soniox models or services"* (Security & Privacy), AND the nuance that the **async API stores** audio+transcript until the caller deletes — which the adapter's mandatory cleanup makes the "deleted right after" clause literally true. This is the honest reason the Soniox disclosure line differs from the "never leaves" framing.

- [ ] **The English-scope caveat (flag only, no code change).** `FloatingBubbleService` forces `lang = "en"` whenever the installed on-device model scope is ENGLISH, before `provider.transcribe(pcm, lang)`. A Soniox (multilingual) user who happens to have an English *local* model would therefore be pinned to `"en"`, defeating Soniox's auto-detect selling point. This is **existing** behaviour, not a compile break, and out of scope for v1 — but record it as the first thing to revisit if multilingual accuracy disappoints. Default path (auto local scope, `getLanguageForApi()` -> null -> "auto" -> hints omitted) already gives Soniox auto-detect.

- [ ] **Commit** (only if a doc file was created/edited):
```powershell
git add <the doc file>
git commit -m "docs: Soniox training-disclosure sourcing + multilingual English-scope caveat"
```

---

## Resolve phase — what the owner's first live key must prove

Everything above is correct-by-construction against the fact sheet, but **nothing is live-verified**. On the first real key, one ≤15 s transcription confirms all of it cheaply:

1. **Terminal-failure literal** — is it `error` or `failed`? (Handled: everything not in `{queued,processing,downloading,transcribing}` is terminal, both failure literals map the same. Either answer already works.)
2. **`/transcript` shape** — top-level `text` present, or tokens-only? (Handled: we build from `tokens` and ignore any `text`. If a top-level `text` exists it is simply unused — still correct.)
3. **Upload response (SHIP-BLOCKER)** — `POST /v1/files` returns `{"id":...}` with a readable, **non-blank** id at the top level. **This MUST be confirmed before any real audio flows.** If Soniox nests or renames the id, the adapter cannot read it, so the file it just stored can NEVER be deleted — every transcription then leaks the user's audio server-side (counts against the 2,000 cap, and is a privacy fact) while results silently fall local. Guarded in code: an unparseable upload id logs `WE-DIAG soniox stt upload 200 unparseable id — file may be leaked server-side` and falls `Transient` (covered by `an_unparseable_upload_id_falls_transient_and_leaks_the_file_it_cannot_delete`). If the shape differs, fix the `SonioxFile` DTO before shipping.
4. **Create response** — `id` + `status:"queued"` at `201`, as pinned. Same class as (3): a create whose id will not parse leaves an actually-created transcription undeletable — the uploaded **file** is still cleaned up by the finally, but a diagnostic (`create unparseable id — transcription may be leaked`) fires and the segment falls `Transient`. Confirm the id shape.
5. **`GET /v1/models`** validates a key (200 good / 401 bad) — confirms the catalog `validationUrl` + the generic `KeyValidator` path with no per-provider marker.
6. **`keyHelpUrl`** — confirm the Console API-keys page URL; adjust the catalog string if the live path differs.
7. **Ambiguous-status bodies** — confirm exhausted balance surfaces as `402` (and/or a balance marker under 401/429), so the quota-vs-key split lands on `OUT_OF_CREDIT`.
8. **Turnaround** — a ≤15 s clip completes within the 40-poll (~40 s) budget in 1–2 cycles; if real turnaround is slower, raise `maxPolls`.
9. **DELETE returns 204** and actually clears the file/transcription — verify no audio lingers in the Console.

---

## Self-review (done inline)

- **Every protective pattern ships in v1, unverified-but-correct:** fatal latch (unchanged `CloudTranscriptionEngine`; adapter maps markers), create-404 -> `MODEL_UNAVAILABLE` (step-sensitive, tested against a poll-404 that must NOT latch), quota-vs-key split on 401/429 (tested both directions), unparseable-200 -> `Transient` (tested on the transcript fetch AND the upload and create ids; the upload/create cases log a status-shape-only diagnostic that the stored resource may be leaked, since an id that can't be read can't be deleted — the upload shape is a resolve-phase ship-blocker), status-code-only logging (`classify` logs code+step, `mapJobFailure` logs status+type — never body/key/url), honest key copy (Soniox falls to the plain rejection `else`, no invented scoping).
- **No credential in logs or URLs:** Bearer header only; a test asserts the key appears in no URL and no delete URL.
- **The exhaustive whens ARE the checklist:** `ProviderId.SONIOX` forces `SttProviderFactory`, `providerTrainingDisclosure` (sourced line), `TtsProviderFactory` (reject), `staticVoices`, and the three `SettingsScreen` formatters — each fixed properly, no TODO. Both non-compiler-forced sets (`STT_CAPABLE_PROVIDERS`, `FloatingBubbleService.STT_PROVIDERS`) gain SONIOX in Task 4, the easy-to-miss one called out.
- **Batch OpenAI-only / live OpenAI-only:** unchanged and re-asserted (`resolveBatchSttProvider` clamp, `decideEngineChoice` CLOUD_LIVE requires OPENAI) — new tests pin both.
- **Multilingual is the point:** auto-detect is the default (hints omitted for null/blank/"auto"), a specific language narrows it; the English-scope coupling is flagged, not silently inherited.
- **No new deps, OkHttp pinned:** reuses `postMultipart`/`postJson`/`get`, adds only `delete` (a verb, not a dependency) + kotlinx serialization already present. `org.json`/`android.util.Base64` untouched (Soniox needs no base64).
- **The poll loop — the risk concentrate — is bounded (Transient on exhaustion, tested with `maxPolls=2`), cleans up on every path incl. cancellation (`try/finally` + `NonCancellable`, tested on happy/create-fail/job-fail/budget-exhausted paths AND a launch+cancel-mid-poll test that proves both resources are still deleted off the cancellable path), and never leaks server-side audio except when an id is unreadable — the one unavoidable leak, now logged and made a resolve-phase ship-blocker.**
- **Task ordering compiles at every boundary:** Task 1 transport is standalone; Task 2's enum cascade compiles with a temporary factory reject (unreachable — Soniox in neither STT set); Task 3 builds the adapter unwired; Task 4 flips the factory + sets to make it selectable. No task leaves the tree red.
- **The two live-only unknowns are handled so either resolution is already correct**, and the resolve phase lists exactly what the owner's first key must prove.
