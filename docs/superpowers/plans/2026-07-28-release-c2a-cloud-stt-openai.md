# Release C2a — Cloud STT (OpenAI) with Local Fallback

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Transcribe VAD-committed segments through OpenAI using the user's own key, falling back to the on-device model whenever cloud fails — and ship the Play Data Safety change in the same release, because this is the first time audio leaves the device.

**Architecture:** A `SttProvider` interface turns one PCM segment into text. `CloudTranscriptionEngine` implements the existing `TranscriptionEngine` contract, so the service is unchanged: it still calls `commit()` and receives `onSegmentResolved(seq, outcome)`. `FallbackTranscriptionEngine` decorates cloud with local, keeping its **own** copy of each segment's PCM because the cloud path consumes and discards it. Release A's `SegmentOrderer` makes concurrent completion safe.

**Tech Stack:** Kotlin 2.0.21, OkHttp 4.12.0, kotlinx-serialization-json 1.7.3, JUnit 4, Android `ConnectivityManager`.

## Global Constraints

- **This is the first release in which audio leaves the device.** The Play Data Safety declaration, the privacy policy, and the C1 disclosure copy (currently future-tense) MUST all change in this same release. Shipping the code without them is a policy violation, not a follow-up.
- **`sendAudio()` runs on the audio capture thread every ~32 ms and must NEVER block.** Any network I/O, TLS handshake, or backpressure wait inside it stalls `AudioRecord` and freezes the waveform, which is driven by the same thread. Hand off to a queue immediately.
- **The fallback valve is ONE-WAY: never escalate local → cloud.** A user who chose on-device must never have audio shipped off-device because a local load failed. Audit specifically for that.
- **An empty transcript must NOT trigger fallback.** The local engine already treats blanks as expected silence; re-running a silent segment burns seconds and money to produce the same empty string.
- **OkHttp is PINNED to 4.12.0.** 5.x's Android artifact forces `kotlin-stdlib` to 2.2.21 project-wide and breaks the Kotlin 2.0.21 compiler. Do not upgrade it. Do not add `okhttp-coroutines`.
- **No credential may reach logcat.** Never log a request carrying an auth header, a key even partially, or an exception message that could embed one. Never add `HttpLoggingInterceptor`.
- **Never log transcript content.** Lengths only, as the existing code does.
- **`LocalWhisperEngine`'s executor stays single-threaded.** All native `whisper_context` access is serialised there.
- **Unit tests run with `unitTests.isReturnDefaultValues = true`** (`app/build.gradle.kts:166`) — anything from `android.jar` returns a type default instead of throwing in a JVM unit test, so broken code can PASS silently or fail inexplicably. **This includes `org.json`**, which ships in `android.jar`: `JSONObject.optString` would return `""` for every input. Use `kotlinx.serialization` (already a dependency, plugin applied, pure JVM) for all JSON. This exact trap cost a task in C1 via `android.util.Base64`.
- **`java` is NOT on PATH.** PowerShell: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio1\jbr"`. `.\gradlew.bat --no-daemon`.
- **NEVER run `connectedAndroidTest` or `installDebug`.** AGP's instrumented task uninstalls the app on teardown and has twice destroyed the user's 500+ MB of models. To run an instrumented test: `adb install -r` both APKs, then `adb shell am instrument -w -e class <FQCN> com.whispereverywhere.test/androidx.test.runner.AndroidJUnitRunner` — that path does not uninstall.
- **Baseline: 205 tests / 0 failures**, `assembleDebug` and `assembleRelease` both green.
- **Do not touch** `TtsEngine.kt`, `TtsDiag.kt`, `TtsDiagMath.kt`, the model catalog, or `SegmentOrderer`/`SegmentOutcome` (Release A, audit-verified).
- **`SegmentQuality` stays UNWIRED.** It is dormant in the tree with a known mis-calibration (gate 2.4 rejects "no no no no…" at 2.69 while its target scores 23.0). Wiring it is out of scope here.

---

## What C2a deliberately does NOT do

Gemini and ElevenLabs adapters are C2b. Graduated degradation UX, spend tracking, the rate-limit gate, request coalescing, and PCM spill-to-disk are C2c. WebSocket streaming is C4. Resist all of them — an unreviewed half-dispatcher is worse than none.

---

## Provider facts for OpenAI (from spec §3.9)

- Endpoint: `POST https://api.openai.com/v1/audio/transcriptions`
- Auth: `Authorization: Bearer sk-…` (already in `ProviderCatalog`)
- **Raw PCM is REJECTED.** Accepted containers only: mp3, mp4, mpeg, mpga, m4a, wav, webm. The app captures raw PCM16, so a 44-byte WAV header is mandatory.
- **OpenAI infers format from the multipart FILENAME** — the file part must be named `audio.wav`.
- 25 MB request cap ≈ **13 minutes** of PCM16 at 16 kHz (32 KB/s). A 15 s segment is ~480 KB, 1.9% of the cap.
- Model: pin a dated snapshot. `gpt-4o-mini-transcribe-2025-12-15` — the `-2025-03-20` snapshot shut down 23 Jul 2026.
- Response: `{"text": "..."}` for `response_format=json`.
- **429 means EITHER rate limiting OR exhausted credit**, distinguishable only from the body (`insufficient_quota`). Retrying the latter burns battery forever.

---

## File Structure

| File | Responsibility |
|---|---|
| `app/src/main/java/com/whispereverywhere/net/HttpTransport.kt` | **Modify.** Add `postMultipart`. |
| `app/src/main/java/com/whispereverywhere/audio/WavWriter.kt` | **Create.** PCM16 → WAV container. Pure. |
| `app/src/test/java/com/whispereverywhere/audio/WavWriterTest.kt` | **Create.** Byte-level header tests. |
| `app/src/main/java/com/whispereverywhere/transcription/cloud/SttProvider.kt` | **Create.** Provider interface + error taxonomy. Pure. |
| `app/src/main/java/com/whispereverywhere/transcription/cloud/OpenAiStt.kt` | **Create.** OpenAI adapter. |
| `app/src/test/java/com/whispereverywhere/transcription/cloud/OpenAiSttTest.kt` | **Create.** Via `FakeHttpTransport`. |
| `app/src/main/java/com/whispereverywhere/transcription/cloud/CloudTranscriptionEngine.kt` | **Create.** Implements `TranscriptionEngine`. |
| `app/src/main/java/com/whispereverywhere/transcription/cloud/FallbackTranscriptionEngine.kt` | **Create.** Cloud→local decorator holding its own PCM. |
| `app/src/test/java/com/whispereverywhere/transcription/cloud/FallbackPolicyTest.kt` | **Create.** Pure fallback-decision tests. |
| `app/src/main/java/com/whispereverywhere/net/ConnectivityMonitor.kt` | **Create.** Validated-network check. |
| `app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt` | **Modify.** Engine selection at session start. |
| `app/src/main/java/com/whispereverywhere/ui/screens/CloudProvidersScreen.kt` | **Modify.** STT engine selector + present-tense disclosure. |
| `app/src/main/assets/privacy_policy.html`, `docs/privacy.html`, `docs/PLAY-DECLARATIONS.md` | **Modify.** Compliance. |

---

## Task 1: `WavWriter` — wrap PCM16 in a container OpenAI will accept

**Files:**
- Create: `app/src/main/java/com/whispereverywhere/audio/WavWriter.kt`
- Test: `app/src/test/java/com/whispereverywhere/audio/WavWriterTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces, used by Task 3: `WavWriter.wrap(pcm: ByteArray, sampleRate: Int = 16_000, channels: Int = 1): ByteArray`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/whispereverywhere/audio/WavWriterTest.kt`:

```kotlin
package com.whispereverywhere.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class WavWriterTest {

    private fun le32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)

    private fun le16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun ascii(b: ByteArray, off: Int, len: Int) = String(b, off, len, Charsets.US_ASCII)

    @Test fun header_is_exactly_44_bytes_before_the_samples() {
        val pcm = ByteArray(100)
        assertEquals(144, WavWriter.wrap(pcm).size)
    }

    @Test fun riff_and_wave_magic_are_present() {
        val w = WavWriter.wrap(ByteArray(4))
        assertEquals("RIFF", ascii(w, 0, 4))
        assertEquals("WAVE", ascii(w, 8, 4))
        assertEquals("fmt ", ascii(w, 12, 4))
        assertEquals("data", ascii(w, 36, 4))
    }

    @Test fun riff_size_is_total_minus_eight() {
        // The RIFF chunk size counts everything AFTER the first 8 bytes. Getting this wrong is the
        // classic WAV bug: some decoders accept it, OpenAI's does not.
        val w = WavWriter.wrap(ByteArray(100))
        assertEquals(w.size - 8, le32(w, 4))
    }

    @Test fun fmt_chunk_declares_pcm16_mono_16k() {
        val w = WavWriter.wrap(ByteArray(2))
        assertEquals(16, le32(w, 16))        // fmt chunk size for PCM
        assertEquals(1, le16(w, 20))         // audioFormat 1 = PCM
        assertEquals(1, le16(w, 22))         // channels
        assertEquals(16_000, le32(w, 24))    // sample rate
        assertEquals(16, le16(w, 34))        // bits per sample
    }

    @Test fun byte_rate_and_block_align_are_derived_not_guessed() {
        // byteRate = rate * channels * bytesPerSample; blockAlign = channels * bytesPerSample.
        // A wrong byteRate makes a decoder compute the wrong duration.
        val w = WavWriter.wrap(ByteArray(2))
        assertEquals(16_000 * 1 * 2, le32(w, 28))
        assertEquals(1 * 2, le16(w, 32))
    }

    @Test fun data_size_equals_the_pcm_length() {
        val w = WavWriter.wrap(ByteArray(640))
        assertEquals(640, le32(w, 40))
    }

    @Test fun the_pcm_payload_is_copied_verbatim() {
        val pcm = byteArrayOf(1, 2, 3, 4, 5, 6)
        val w = WavWriter.wrap(pcm)
        assertArrayEquals(pcm, w.copyOfRange(44, 44 + pcm.size))
    }

    @Test fun empty_pcm_still_produces_a_valid_header() {
        // A zero-length segment should never reach the wire, but producing a malformed file here
        // would turn a guard failure into a confusing 400 from the provider.
        val w = WavWriter.wrap(ByteArray(0))
        assertEquals(44, w.size)
        assertEquals(0, le32(w, 40))
        assertEquals(36, le32(w, 4))
    }

    @Test fun a_different_sample_rate_is_honoured() {
        val w = WavWriter.wrap(ByteArray(2), sampleRate = 24_000)
        assertEquals(24_000, le32(w, 24))
        assertEquals(24_000 * 2, le32(w, 28))
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
.\gradlew.bat :app:testDebugUnitTest --no-daemon --tests "com.whispereverywhere.audio.WavWriterTest"
```
Expected: FAIL — `Unresolved reference: WavWriter`.

- [ ] **Step 3: Implement**

Create `app/src/main/java/com/whispereverywhere/audio/WavWriter.kt`:

```kotlin
package com.whispereverywhere.audio

/**
 * Wraps raw PCM16 in a canonical 44-byte WAV container.
 *
 * Required because OpenAI's transcription endpoint REJECTS raw PCM — it accepts containers only
 * (mp3, mp4, mpeg, mpga, m4a, wav, webm) and infers the format from the multipart FILENAME, so the
 * part must additionally be named "audio.wav".
 *
 * Pure and Android-free. Every field is little-endian; the RIFF size counts everything after the
 * first 8 bytes, which is the classic place to get this wrong.
 */
object WavWriter {

    private const val HEADER_BYTES = 44
    private const val BITS_PER_SAMPLE = 16
    private const val PCM_FORMAT: Short = 1

    fun wrap(pcm: ByteArray, sampleRate: Int = 16_000, channels: Int = 1): ByteArray {
        val bytesPerSample = BITS_PER_SAMPLE / 8
        val byteRate = sampleRate * channels * bytesPerSample
        val blockAlign = channels * bytesPerSample
        val out = ByteArray(HEADER_BYTES + pcm.size)

        fun ascii(off: Int, s: String) {
            for (i in s.indices) out[off + i] = s[i].code.toByte()
        }
        fun le32(off: Int, v: Int) {
            out[off] = (v and 0xFF).toByte()
            out[off + 1] = ((v ushr 8) and 0xFF).toByte()
            out[off + 2] = ((v ushr 16) and 0xFF).toByte()
            out[off + 3] = ((v ushr 24) and 0xFF).toByte()
        }
        fun le16(off: Int, v: Int) {
            out[off] = (v and 0xFF).toByte()
            out[off + 1] = ((v ushr 8) and 0xFF).toByte()
        }

        ascii(0, "RIFF")
        le32(4, 36 + pcm.size)          // everything after these first 8 bytes
        ascii(8, "WAVE")
        ascii(12, "fmt ")
        le32(16, 16)                    // PCM fmt chunk size
        le16(20, PCM_FORMAT.toInt())
        le16(22, channels)
        le32(24, sampleRate)
        le32(28, byteRate)
        le16(32, blockAlign)
        le16(34, BITS_PER_SAMPLE)
        ascii(36, "data")
        le32(40, pcm.size)
        pcm.copyInto(out, HEADER_BYTES)
        return out
    }
}
```

- [ ] **Step 4: Verify**

```bash
.\gradlew.bat :app:testDebugUnitTest --no-daemon --tests "com.whispereverywhere.audio.WavWriterTest"
```
Expected: PASS, 9 tests. Full suite: **214 tests**, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/whispereverywhere/audio/WavWriter.kt \
        app/src/test/java/com/whispereverywhere/audio/WavWriterTest.kt
git commit -m "feat(audio): WavWriter — PCM16 to a container OpenAI will accept

OpenAI's transcription endpoint REJECTS raw PCM; it takes containers only
and infers the format from the multipart filename, so the part must also
be named audio.wav.

Every header field is asserted at the byte level, including the two that
are classically wrong: the RIFF size counts everything AFTER the first 8
bytes, and byteRate/blockAlign are derived rather than guessed — a wrong
byteRate makes a decoder compute the wrong duration."
```

---

## Task 2: `HttpTransport.postMultipart`

**Files:**
- Modify: `app/src/main/java/com/whispereverywhere/net/HttpTransport.kt`
- Modify: `app/src/test/java/com/whispereverywhere/net/FakeHttpTransport.kt`

**Interfaces:**
- Consumes: existing `HttpResult`, `Call.await()`.
- Produces, used by Task 3:
  - `suspend fun postMultipart(url: String, headers: Map<String, String>, filePart: FilePart, fields: Map<String, String>, timeoutMs: Long = 60_000): HttpResult`
  - `data class FilePart(val fieldName: String, val fileName: String, val contentType: String, val bytes: ByteArray)`

- [ ] **Step 1: Extend the interface**

In `HttpTransport.kt`, add to the interface:

```kotlin
    /**
     * A file in a multipart/form-data body. [fileName] is load-bearing for OpenAI, which infers
     * the audio format from it rather than from [contentType].
     */
    data class FilePart(
        val fieldName: String,
        val fileName: String,
        val contentType: String,
        val bytes: ByteArray,
    )

    /**
     * Upload one file plus form fields. Separate from [get] because the timeout profile is
     * completely different: a 15-second audio segment on a slow cellular link needs far longer
     * than a key-validation GET.
     */
    suspend fun postMultipart(
        url: String,
        headers: Map<String, String>,
        filePart: FilePart,
        fields: Map<String, String>,
        timeoutMs: Long = DEFAULT_UPLOAD_TIMEOUT_MS,
    ): HttpResult
```

and to its companion: `const val DEFAULT_UPLOAD_TIMEOUT_MS = 60_000L`

- [ ] **Step 2: Implement in `OkHttpTransport`**

```kotlin
    override suspend fun postMultipart(
        url: String,
        headers: Map<String, String>,
        filePart: HttpTransport.FilePart,
        fields: Map<String, String>,
        timeoutMs: Long,
    ): HttpResult {
        return try {
            // Body construction stays INSIDE the try for the same reason as get(): OkHttp's
            // header validation throws IllegalArgumentException whose message embeds the raw
            // header value for every header except Authorization/Cookie/Proxy-Authorization/
            // Set-Cookie — and an uncaught throw here would put a credential in a crash trace.
            val body = MultipartBody.Builder().setType(MultipartBody.FORM).apply {
                fields.forEach { (k, v) -> addFormDataPart(k, v) }
                addFormDataPart(
                    filePart.fieldName,
                    filePart.fileName,
                    filePart.bytes.toRequestBody(filePart.contentType.toMediaType()),
                )
            }.build()
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
            // Rethrow BEFORE the broad catch — CancellationException extends
            // IllegalStateException -> RuntimeException -> Exception, so the catch below would
            // otherwise swallow it and report a cancelled upload as a network failure while the
            // coroutine never unwinds.
            throw c
        } catch (e: Exception) {
            HttpResult.NetworkError(e)
        }
    }
```

Add imports: `okhttp3.MediaType.Companion.toMediaType`, `okhttp3.MultipartBody`, `okhttp3.RequestBody.Companion.toRequestBody`.

- [ ] **Step 3: Extend the fake**

In `app/src/test/java/com/whispereverywhere/net/FakeHttpTransport.kt`, add recording fields and the override:

```kotlin
    var lastFilePart: HttpTransport.FilePart? = null
        private set
    var lastFields: Map<String, String> = emptyMap()
        private set

    override suspend fun postMultipart(
        url: String,
        headers: Map<String, String>,
        filePart: HttpTransport.FilePart,
        fields: Map<String, String>,
        timeoutMs: Long,
    ): HttpResult {
        lastUrl = url
        lastHeaders = headers
        lastFilePart = filePart
        lastFields = fields
        callCount++
        return script(url, headers)
    }
```

- [ ] **Step 4: Verify**

```bash
.\gradlew.bat :app:testDebugUnitTest --no-daemon
.\gradlew.bat :app:assembleRelease --no-daemon
```
Expected: 214 tests, 0 failures; release green.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/whispereverywhere/net/HttpTransport.kt \
        app/src/test/java/com/whispereverywhere/net/FakeHttpTransport.kt
git commit -m "feat(net): HttpTransport.postMultipart for audio upload

Separate from get() because the timeout profile is completely different —
a 15-second segment on slow cellular needs far longer than a key check.

Body construction stays inside the try for the same reason as get():
OkHttp's header validation throws IllegalArgumentException whose message
embeds the raw value for every header except the four it redacts, so an
uncaught throw would put a credential in a crash trace. Cancellation is
rethrown before the broad catch."
```

---

## Task 3: `SttProvider` + `OpenAiStt`

**Files:**
- Create: `app/src/main/java/com/whispereverywhere/transcription/cloud/SttProvider.kt`
- Create: `app/src/main/java/com/whispereverywhere/transcription/cloud/OpenAiStt.kt`
- Test: `app/src/test/java/com/whispereverywhere/transcription/cloud/OpenAiSttTest.kt`

**Interfaces:**
- Consumes: `HttpTransport` + `FilePart` (Task 2), `WavWriter` (Task 1), `ProviderCatalog`.
- Produces, used by Task 4:
  - `sealed interface SttResult { data class Text(val text: String); data class Failed(val error: SttError) }`
  - `sealed interface SttError { data object Offline; data class Fatal(val kind: FatalKind, val message: String); data class Transient(val retryAfterMs: Long?); data object BadSegment }`
  - `enum class FatalKind { INVALID_KEY, OUT_OF_CREDIT, FORBIDDEN }`
  - `interface SttProvider { val id: ProviderId; val maxRequestBytes: Long; suspend fun transcribe(pcm: ByteArray, language: String?): SttResult }`
  - `class OpenAiStt(transport: HttpTransport, apiKey: String, model: String = DEFAULT_MODEL)`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/whispereverywhere/transcription/cloud/OpenAiSttTest.kt`:

```kotlin
package com.whispereverywhere.transcription.cloud

import com.whispereverywhere.net.FakeHttpTransport
import com.whispereverywhere.net.HttpResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class OpenAiSttTest {

    private fun provider(result: HttpResult, fake: FakeHttpTransport = FakeHttpTransport { _, _ -> result }) =
        fake to OpenAiStt(fake, "sk-test")

    private val pcm = ByteArray(3200) { (it % 127).toByte() }   // 100 ms of 16 kHz PCM16

    @Test fun a_200_yields_the_transcript_text() = runBlocking {
        val (_, p) = provider(HttpResult.Ok(200, """{"text":"hello world"}"""))
        assertEquals(SttResult.Text("hello world"), p.transcribe(pcm, null))
    }

    @Test fun the_upload_is_named_audio_wav_because_openai_infers_format_from_the_filename() = runBlocking {
        val fake = FakeHttpTransport { _, _ -> HttpResult.Ok(200, """{"text":"x"}""") }
        OpenAiStt(fake, "sk-test").transcribe(pcm, null)
        assertEquals("audio.wav", fake.lastFilePart?.fileName)
        assertEquals("file", fake.lastFilePart?.fieldName)
    }

    @Test fun the_uploaded_bytes_are_a_wav_container_not_raw_pcm() = runBlocking {
        // OpenAI REJECTS raw PCM outright.
        val fake = FakeHttpTransport { _, _ -> HttpResult.Ok(200, """{"text":"x"}""") }
        OpenAiStt(fake, "sk-test").transcribe(pcm, null)
        val sent = fake.lastFilePart!!.bytes
        assertEquals("RIFF", String(sent, 0, 4, Charsets.US_ASCII))
        assertEquals(pcm.size + 44, sent.size)
    }

    @Test fun a_bearer_header_is_sent() = runBlocking {
        val fake = FakeHttpTransport { _, _ -> HttpResult.Ok(200, """{"text":"x"}""") }
        OpenAiStt(fake, "sk-abc").transcribe(pcm, null)
        assertEquals("Bearer sk-abc", fake.lastHeaders["Authorization"])
    }

    @Test fun a_pinned_model_snapshot_is_sent() = runBlocking {
        // An undated model id can be retired under a shipped APK; -2025-03-20 already was.
        val fake = FakeHttpTransport { _, _ -> HttpResult.Ok(200, """{"text":"x"}""") }
        OpenAiStt(fake, "sk-test").transcribe(pcm, null)
        val model = fake.lastFields["model"] ?: ""
        assertTrue("model must be a dated snapshot, got '$model'", Regex("\\d{4}-\\d{2}-\\d{2}$").containsMatchIn(model))
    }

    @Test fun a_language_hint_is_forwarded_and_omitted_when_null() = runBlocking {
        val f1 = FakeHttpTransport { _, _ -> HttpResult.Ok(200, """{"text":"x"}""") }
        OpenAiStt(f1, "sk-test").transcribe(pcm, "en")
        assertEquals("en", f1.lastFields["language"])

        val f2 = FakeHttpTransport { _, _ -> HttpResult.Ok(200, """{"text":"x"}""") }
        OpenAiStt(f2, "sk-test").transcribe(pcm, null)
        assertTrue("auto-detect must omit the field", !f2.lastFields.containsKey("language"))
    }

    @Test fun a_401_is_fatal_invalid_key() = runBlocking {
        val (_, p) = provider(HttpResult.HttpError(401, ""))
        val r = p.transcribe(pcm, null) as SttResult.Failed
        assertEquals(FatalKind.INVALID_KEY, (r.error as SttError.Fatal).kind)
    }

    @Test fun a_429_with_a_quota_body_is_fatal_out_of_credit_not_transient() = runBlocking {
        // Backing off exponentially against an empty wallet retries forever.
        val body = """{"error":{"code":"insufficient_quota"}}"""
        val (_, p) = provider(HttpResult.HttpError(429, body))
        val r = p.transcribe(pcm, null) as SttResult.Failed
        assertEquals(FatalKind.OUT_OF_CREDIT, (r.error as SttError.Fatal).kind)
    }

    @Test fun a_plain_429_is_transient() = runBlocking {
        val (_, p) = provider(HttpResult.HttpError(429, "slow down"))
        val r = p.transcribe(pcm, null) as SttResult.Failed
        assertTrue(r.error is SttError.Transient)
    }

    @Test fun a_413_is_a_bad_segment_not_a_fatal_account_problem() = runBlocking {
        // Too-large audio is this segment's problem; it must not disable the provider.
        val (_, p) = provider(HttpResult.HttpError(413, ""))
        val r = p.transcribe(pcm, null) as SttResult.Failed
        assertEquals(SttError.BadSegment, r.error)
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
        // 25 MB cap. Spending an upload to learn it is too big wastes the user's data and money.
        val fake = FakeHttpTransport { _, _ -> HttpResult.Ok(200, """{"text":"x"}""") }
        val huge = ByteArray(26 * 1024 * 1024)
        val r = OpenAiStt(fake, "sk-test").transcribe(huge, null) as SttResult.Failed
        assertEquals(SttError.BadSegment, r.error)
        assertEquals("must not hit the network", 0, fake.callCount)
    }

    @Test fun a_200_with_an_unparseable_body_is_transient_not_silently_empty() = runBlocking {
        // Returning "" here would look like silence and suppress fallback.
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

- [ ] **Step 2: Run to verify it fails**

```bash
.\gradlew.bat :app:testDebugUnitTest --no-daemon --tests "com.whispereverywhere.transcription.cloud.OpenAiSttTest"
```
Expected: FAIL — `Unresolved reference: OpenAiStt`.

- [ ] **Step 3: Write `SttProvider.kt`**

```kotlin
package com.whispereverywhere.transcription.cloud

import com.whispereverywhere.provider.ProviderId

/** Why a cloud transcription attempt failed. The distinctions drive very different handling. */
sealed interface SttError {
    /** No usable network. Do not retry in a tight loop; do not blame the key. */
    data object Offline : SttError
    /** The account or key is the problem. Retrying cannot help — stop using this provider. */
    data class Fatal(val kind: FatalKind, val message: String) : SttError
    /** Worth retrying. [retryAfterMs] is the server's own stated wait, when it gave one. */
    data class Transient(val retryAfterMs: Long?) : SttError
    /** THIS segment is unacceptable (too large, malformed). Does not disable the provider. */
    data object BadSegment : SttError
}

enum class FatalKind { INVALID_KEY, OUT_OF_CREDIT, FORBIDDEN }

sealed interface SttResult {
    data class Text(val text: String) : SttResult
    data class Failed(val error: SttError) : SttResult
}

/**
 * Turns one committed PCM16 segment into text. Deliberately narrow: no session, no streaming, no
 * state. Everything above it (ordering, fallback, retry policy) is provider-agnostic.
 */
interface SttProvider {
    val id: ProviderId
    /** Hard upper bound on one request's audio payload, enforced BEFORE any upload. */
    val maxRequestBytes: Long
    suspend fun transcribe(pcm: ByteArray, language: String?): SttResult
}
```

- [ ] **Step 4: Write `OpenAiStt.kt`**

```kotlin
package com.whispereverywhere.transcription.cloud

import com.whispereverywhere.audio.WavWriter
import com.whispereverywhere.net.HttpResult
import com.whispereverywhere.net.HttpTransport
import com.whispereverywhere.provider.ProviderCatalog
import com.whispereverywhere.provider.ProviderId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** `response_format=json` returns `{"text": "..."}`. Defaulted so a missing field is "" not a throw. */
@Serializable
private data class OpenAiTranscription(val text: String = "")

/**
 * OpenAI transcription. One multipart POST per committed segment.
 *
 * Three details are load-bearing and easy to get wrong:
 *  - RAW PCM IS REJECTED. The endpoint accepts containers only, so the segment is WAV-wrapped.
 *  - THE FILENAME DECIDES THE FORMAT. OpenAI infers it from the multipart filename, not the
 *    Content-Type, so the part must be named "audio.wav".
 *  - 429 IS AMBIGUOUS. It means transient rate limiting OR permanently exhausted credit, and only
 *    the body distinguishes them. Treating the latter as transient retries against an empty wallet
 *    forever, burning battery and the user's remaining goodwill.
 *
 * Never log the key, the headers, or the transcript.
 */
class OpenAiStt(
    private val transport: HttpTransport,
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
) : SttProvider {

    override val id = ProviderId.OPENAI

    /** 25 MB request cap; ~13 minutes of 16 kHz PCM16. */
    override val maxRequestBytes = 25L * 1024 * 1024

    override suspend fun transcribe(pcm: ByteArray, language: String?): SttResult {
        // Fail locally rather than spending an upload to be told it was too big.
        if (pcm.size.toLong() + WAV_HEADER_BYTES > maxRequestBytes) {
            return SttResult.Failed(SttError.BadSegment)
        }

        val provider = ProviderCatalog.byId(ProviderId.OPENAI)
        val headers = mapOf(provider.authHeaderName to provider.authHeaderValue(apiKey))
        val fields = buildMap {
            put("model", model)
            put("response_format", "json")
            // Omit entirely for auto-detect — sending an empty value is a 400.
            if (!language.isNullOrBlank() && language != "auto") put("language", language)
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
        // A 200 whose body will not parse is NOT an empty transcript. Returning "" would look like
        // silence, suppress fallback, and silently lose the user's sentence.
        SttResult.Text(JSON.decodeFromString<OpenAiTranscription>(body).text)
    } catch (_: Throwable) {
        SttResult.Failed(SttError.Transient(null))
    }

    private fun classify(code: Int, body: String): SttError = when (code) {
        401 -> SttError.Fatal(FatalKind.INVALID_KEY, "Key rejected")
        403 -> SttError.Fatal(FatalKind.FORBIDDEN, "Access denied for this key")
        400, 413 -> SttError.BadSegment
        429 -> if (QUOTA_MARKERS.any { body.contains(it, ignoreCase = true) }) {
            SttError.Fatal(FatalKind.OUT_OF_CREDIT, "Account has no remaining credit")
        } else {
            SttError.Transient(null)
        }
        in 500..599 -> SttError.Transient(null)
        else -> SttError.Transient(null)
    }

    companion object {
        /**
         * kotlinx.serialization, NOT org.json, and the reason is load-bearing: org.json ships in
         * android.jar, and this project sets `unitTests.isReturnDefaultValues = true`
         * (app/build.gradle.kts:166), so JSONObject's methods return type defaults under plain
         * JVM unit tests. `optString("text", "")` would return "" for every input — the parsing
         * tests would fail for a reason invisible in the code, exactly as android.util.Base64 did
         * in C1. kotlinx-serialization-json 1.7.3 is already a dependency with the plugin applied
         * and is pure JVM.
         */
        private val JSON = Json { ignoreUnknownKeys = true }

        /**
         * Pinned dated snapshot. An undated id can be retired under a shipped APK — the
         * -2025-03-20 snapshot of this model shut down on 23 Jul 2026.
         */
        const val DEFAULT_MODEL = "gpt-4o-mini-transcribe-2025-12-15"
        private const val ENDPOINT = "https://api.openai.com/v1/audio/transcriptions"
        private const val WAV_HEADER_BYTES = 44
        private val QUOTA_MARKERS = listOf("insufficient_quota", "quota_exceeded")
    }
}
```

- [ ] **Step 5: Verify**

```bash
.\gradlew.bat :app:testDebugUnitTest --no-daemon --tests "com.whispereverywhere.transcription.cloud.OpenAiSttTest"
```
Expected: PASS, 15 tests. Full suite: **229 tests**, 0 failures.

> **Verify before shipping:** `DEFAULT_MODEL` and the endpoint path were taken from research, not
> re-confirmed against live docs when this plan was written. Task 7's on-device check exercises
> them with a real key. A 404 or "model not found" means the pin is wrong — fix it there and report,
> do not silently substitute another model.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/whispereverywhere/transcription/cloud/SttProvider.kt \
        app/src/main/java/com/whispereverywhere/transcription/cloud/OpenAiStt.kt \
        app/src/test/java/com/whispereverywhere/transcription/cloud/OpenAiSttTest.kt
git commit -m "feat(cloud): SttProvider + OpenAI adapter

Three details are load-bearing: raw PCM is rejected so segments are
WAV-wrapped; OpenAI infers the format from the multipart FILENAME rather
than Content-Type, so the part must be audio.wav; and 429 means EITHER
rate limiting OR exhausted credit, distinguishable only from the body —
treating the latter as transient retries against an empty wallet forever.

Oversized audio fails locally without an upload, and a 200 with an
unparseable body is Transient rather than an empty transcript: returning
'' would look like silence, suppress fallback, and lose the sentence."
```

---

## Task 4: `CloudTranscriptionEngine`

**Files:**
- Create: `app/src/main/java/com/whispereverywhere/transcription/cloud/CloudTranscriptionEngine.kt`

**Interfaces:**
- Consumes: `SttProvider` (Task 3), `TranscriptionEngine`/`SegmentOutcome` (Release A).
- Produces, used by Task 5: `class CloudTranscriptionEngine(provider: SttProvider, scope: CoroutineScope, maxInFlight: Int = 3)` implementing `TranscriptionEngine`, plus `fun lastFatal(): SttError.Fatal?`

- [ ] **Step 1: Implement**

The critical constraints, restated because they are the whole design:

- `sendAudio` runs on the **audio capture thread every ~32 ms** and must never block. Append under a lock and return; do nothing else.
- `commit()` allocates the seq **inside** the same lock as the PCM snapshot, exactly as `LocalWhisperEngine` does, then launches the request. Ordering is a function of audio order, not launch order — `SegmentOrderer` handles the rest.
- **Every seq returned by `commit()` must reach `onSegmentResolved` exactly once**, including on cancellation and on fatal errors.
- `awaitIdle` must genuinely await outstanding requests — it is the fence the service uses before detaching the listener.
- A **fatal** error latches: once the key is rejected or credit exhausted, stop issuing requests for the rest of the session. One fatal error must cost one request, not forty.

```kotlin
package com.whispereverywhere.transcription.cloud

import com.whispereverywhere.transcription.SegmentOutcome
import com.whispereverywhere.transcription.TranscriptionEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream

class CloudTranscriptionEngine(
    private val provider: SttProvider,
    private val scope: CoroutineScope,
    maxInFlight: Int = DEFAULT_MAX_IN_FLIGHT,
) : TranscriptionEngine {

    private val bufferLock = Any()
    private val buffer = ByteArrayOutputStream()
    private var nextSeq = 0L
    private var language: String? = null
    @Volatile private var listener: TranscriptionEngine.Listener? = null
    @Volatile private var fatal: SttError.Fatal? = null

    private val gate = Semaphore(maxInFlight)
    private val inFlight = java.util.concurrent.ConcurrentHashMap<Long, Job>()

    fun lastFatal(): SttError.Fatal? = fatal

    override fun connect(language: String?, listener: TranscriptionEngine.Listener) {
        this.language = language
        this.listener = listener
        synchronized(bufferLock) { buffer.reset(); nextSeq = 0L }
        fatal = null
        listener.onOpen()
    }

    /** Runs on the AUDIO CAPTURE THREAD every ~32 ms. Append and return — nothing else. */
    override fun sendAudio(pcm: ByteArray) {
        synchronized(bufferLock) { buffer.write(pcm) }
    }

    override fun commit(): Long {
        val myListener = listener ?: return -1L
        val (seq, pcm) = synchronized(bufferLock) {
            val snapshot = buffer.toByteArray()
            if (snapshot.isEmpty()) return -1L
            buffer.reset()
            (nextSeq++) to snapshot
        }
        // A latched fatal means the key or account is the problem; issuing more requests cannot
        // help and costs the user money. Resolve immediately so the orderer never stalls.
        fatal?.let {
            myListener.onSegmentResolved(seq, SegmentOutcome.Lost(it.message))
            return seq
        }
        val job = scope.launch {
            val outcome = try {
                gate.withPermit { runOne(pcm) }
            } catch (c: kotlinx.coroutines.CancellationException) {
                // Session torn down mid-flight. Resolve rather than leaving the seq dangling —
                // an unresolved seq stalls the orderer head forever.
                SegmentOutcome.Lost("cancelled")
            } catch (t: Throwable) {
                SegmentOutcome.Lost(t.message ?: "cloud transcription failed")
            }
            inFlight.remove(seq)
            if (listener === myListener) myListener.onSegmentResolved(seq, outcome)
        }
        inFlight[seq] = job
        return seq
    }

    private suspend fun runOne(pcm: ByteArray): SegmentOutcome =
        when (val r = provider.transcribe(pcm, language)) {
            is SttResult.Text ->
                // An empty transcript is expected silence, NOT a loss: the user said nothing.
                if (r.text.isBlank()) SegmentOutcome.EmptyExpected else SegmentOutcome.Text(r.text.trim())
            is SttResult.Failed -> {
                (r.error as? SttError.Fatal)?.let { fatal = it }
                SegmentOutcome.Lost(describe(r.error))
            }
        }

    private fun describe(e: SttError): String = when (e) {
        SttError.Offline -> "offline"
        is SttError.Fatal -> e.message
        is SttError.Transient -> "temporary provider error"
        SttError.BadSegment -> "segment rejected"
    }

    override fun close() {
        listener = null
        synchronized(bufferLock) { buffer.reset() }
        inFlight.values.forEach { it.cancel() }
        inFlight.clear()
    }

    override fun awaitIdle(timeoutMs: Long): Boolean = kotlinx.coroutines.runBlocking {
        withTimeoutOrNull(timeoutMs) {
            inFlight.values.toList().forEach { it.join() }
            true
        } ?: false
    }

    override fun shutdown() = close()

    companion object { const val DEFAULT_MAX_IN_FLIGHT = 3 }
}
```

- [ ] **Step 2: Verify it compiles and nothing regressed**

```bash
.\gradlew.bat :app:testDebugUnitTest --no-daemon
.\gradlew.bat :app:assembleRelease --no-daemon
```
Expected: 229 tests, 0 failures; release green.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/whispereverywhere/transcription/cloud/CloudTranscriptionEngine.kt
git commit -m "feat(cloud): CloudTranscriptionEngine implementing the existing contract

The service is unchanged: it still calls commit() and receives
onSegmentResolved(seq, outcome). Release A's SegmentOrderer makes
concurrent completion safe, so up to 3 requests run in flight.

seq is allocated inside the same lock as the PCM snapshot, so ordering is
a function of AUDIO order rather than launch order. Every seq resolves
exactly once — including on cancellation, which would otherwise leave the
orderer head stalled forever.

A fatal error (bad key, no credit) LATCHES: one fatal error must cost one
request, not forty. An empty transcript is expected silence, not a loss."
```

---

## Task 5: `FallbackTranscriptionEngine` — cloud with local as the safety net

**Files:**
- Create: `app/src/main/java/com/whispereverywhere/transcription/cloud/FallbackTranscriptionEngine.kt`
- Test: `app/src/test/java/com/whispereverywhere/transcription/cloud/FallbackPolicyTest.kt`

**Interfaces:**
- Consumes: `CloudTranscriptionEngine` (Task 4), `LocalWhisperEngine`, `SegmentOutcome`.
- Produces, used by Task 6: `class FallbackTranscriptionEngine(cloud: TranscriptionEngine, local: TranscriptionEngine, scope: CoroutineScope)` implementing `TranscriptionEngine`; plus pure `FallbackPolicy.shouldFallBack(outcome: SegmentOutcome): Boolean`

**Why this class must keep its own PCM copy:** the cloud engine consumes and discards each segment's audio on `commit()`. Once cloud reports a loss, that audio is gone — you cannot fall back to audio you no longer have. This decorator therefore mirrors every `sendAudio` into its own buffer and retains the snapshot until the segment resolves.

- [ ] **Step 1: Write the failing policy test**

Create `app/src/test/java/com/whispereverywhere/transcription/cloud/FallbackPolicyTest.kt`:

```kotlin
package com.whispereverywhere.transcription.cloud

import com.whispereverywhere.transcription.SegmentOutcome
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FallbackPolicyTest {

    @Test fun a_lost_segment_falls_back_to_local() {
        assertTrue(FallbackPolicy.shouldFallBack(SegmentOutcome.Lost("offline")))
    }

    @Test fun successful_text_never_falls_back() {
        assertFalse(FallbackPolicy.shouldFallBack(SegmentOutcome.Text("hello")))
    }

    @Test fun an_empty_transcript_does_NOT_fall_back() {
        // The user said nothing. Re-running silence locally burns 2-6 seconds to produce the same
        // empty string, and on cloud it would have cost money too.
        assertFalse(FallbackPolicy.shouldFallBack(SegmentOutcome.EmptyExpected))
    }

    @Test fun an_unexpected_empty_does_fall_back() {
        // Real voiced audio that came back empty is a lost sentence worth a second attempt.
        assertTrue(FallbackPolicy.shouldFallBack(SegmentOutcome.EmptyUnexpected))
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
.\gradlew.bat :app:testDebugUnitTest --no-daemon --tests "com.whispereverywhere.transcription.cloud.FallbackPolicyTest"
```
Expected: FAIL — `Unresolved reference: FallbackPolicy`.

- [ ] **Step 3: Implement**

Create `app/src/main/java/com/whispereverywhere/transcription/cloud/FallbackTranscriptionEngine.kt` containing both the pure policy and the decorator:

```kotlin
package com.whispereverywhere.transcription.cloud

import com.whispereverywhere.transcription.SegmentOutcome
import com.whispereverywhere.transcription.TranscriptionEngine
import kotlinx.coroutines.CoroutineScope
import java.io.ByteArrayOutputStream

/** Pure decision: is this outcome worth a second attempt on the local engine? */
object FallbackPolicy {
    fun shouldFallBack(outcome: SegmentOutcome): Boolean = when (outcome) {
        is SegmentOutcome.Lost -> true
        SegmentOutcome.EmptyUnexpected -> true
        // The user said nothing. Re-running silence burns seconds to produce the same empty
        // string — and would have cost money on the cloud path too.
        SegmentOutcome.EmptyExpected -> false
        is SegmentOutcome.Text -> false
    }
}

/**
 * Runs [cloud] first and retries failed segments on [local], preserving seq so the orderer still
 * releases in order.
 *
 * THE VALVE IS ONE-WAY. This decorator only ever goes cloud -> local, never local -> cloud. A user
 * who chose on-device must never have audio shipped off-device because a local load failed.
 *
 * It keeps its OWN copy of each segment's PCM because [cloud] consumes and discards the audio on
 * commit(); once cloud reports a loss the audio is gone, and you cannot fall back to audio you no
 * longer have.
 */
class FallbackTranscriptionEngine(
    private val cloud: TranscriptionEngine,
    private val local: TranscriptionEngine,
    private val scope: CoroutineScope,
) : TranscriptionEngine {

    private val mirrorLock = Any()
    private val mirror = ByteArrayOutputStream()
    private val retained = java.util.concurrent.ConcurrentHashMap<Long, ByteArray>()
    @Volatile private var downstream: TranscriptionEngine.Listener? = null

    override fun connect(language: String?, listener: TranscriptionEngine.Listener) {
        downstream = listener
        synchronized(mirrorLock) { mirror.reset() }
        retained.clear()
        local.connect(language, LocalRelay())
        cloud.connect(language, CloudRelay())
    }

    override fun sendAudio(pcm: ByteArray) {
        // Capture-thread hot path: two cheap appends, no allocation beyond the copy already made
        // upstream, no I/O.
        synchronized(mirrorLock) { mirror.write(pcm) }
        cloud.sendAudio(pcm)
    }

    override fun commit(): Long {
        val snapshot = synchronized(mirrorLock) {
            val s = mirror.toByteArray(); mirror.reset(); s
        }
        val seq = cloud.commit()
        if (seq >= 0 && snapshot.isNotEmpty()) retained[seq] = snapshot
        return seq
    }

    private inner class CloudRelay : TranscriptionEngine.Listener {
        override fun onOpen() { downstream?.onOpen() }
        override fun onDelta(text: String) { downstream?.onDelta(text) }
        override fun onError(message: String) { downstream?.onError(message) }
        override fun onClosed() { downstream?.onClosed() }

        override fun onSegmentResolved(seq: Long, outcome: SegmentOutcome) {
            val pcm = retained.remove(seq)
            if (!FallbackPolicy.shouldFallBack(outcome) || pcm == null) {
                downstream?.onSegmentResolved(seq, outcome)
                return
            }
            // Retry this seq locally. The orderer holds later segments until it resolves, which is
            // exactly the intended behaviour: bursty-but-correct beats fast-but-deleted.
            localRetry(seq, pcm, outcome)
        }
    }

    /**
     * The local engine owns its own seq counter, so its resolutions cannot be forwarded directly.
     * Retries are tracked here and re-labelled with the ORIGINAL seq.
     */
    private inner class LocalRelay : TranscriptionEngine.Listener {
        override fun onOpen() {}
        override fun onDelta(text: String) {}
        override fun onError(message: String) {}
        override fun onClosed() {}
        override fun onSegmentResolved(seq: Long, outcome: SegmentOutcome) {
            val original = retryMap.remove(seq) ?: return
            downstream?.onSegmentResolved(original, outcome)
        }
    }

    private val retryMap = java.util.concurrent.ConcurrentHashMap<Long, Long>()

    private fun localRetry(originalSeq: Long, pcm: ByteArray, cloudOutcome: SegmentOutcome) {
        local.sendAudio(pcm)
        val localSeq = local.commit()
        if (localSeq < 0) {
            // Local could not accept it — surface the original cloud outcome rather than stalling.
            downstream?.onSegmentResolved(originalSeq, cloudOutcome)
            return
        }
        retryMap[localSeq] = originalSeq
    }

    override fun close() { cloud.close(); local.close(); retained.clear(); retryMap.clear() }
    override fun prewarm() { local.prewarm() }
    override fun shutdown() { cloud.shutdown(); local.shutdown() }
    override fun releaseContext() { local.releaseContext() }
    override fun awaitIdle(timeoutMs: Long): Boolean {
        val half = timeoutMs / 2
        val a = cloud.awaitIdle(half)
        val b = local.awaitIdle(timeoutMs - half)
        return a && b
    }
}
```

- [ ] **Step 4: Verify**

```bash
.\gradlew.bat :app:testDebugUnitTest --no-daemon
.\gradlew.bat :app:assembleRelease --no-daemon
```
Expected: **233 tests**, 0 failures; release green.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/whispereverywhere/transcription/cloud/FallbackTranscriptionEngine.kt \
        app/src/test/java/com/whispereverywhere/transcription/cloud/FallbackPolicyTest.kt
git commit -m "feat(cloud): FallbackTranscriptionEngine — local as the safety net

Keeps its OWN copy of each segment's PCM because the cloud engine consumes
and discards the audio on commit(); once cloud reports a loss the audio is
gone, and you cannot fall back to audio you no longer have.

THE VALVE IS ONE-WAY: cloud -> local only, never the reverse. A user who
chose on-device must never have audio shipped off-device because a local
load failed.

An empty transcript does NOT trigger fallback — the user said nothing, and
re-running silence burns seconds to produce the same empty string."
```

---

## Task 6: Engine selection, connectivity, and wiring

**Files:**
- Create: `app/src/main/java/com/whispereverywhere/net/ConnectivityMonitor.kt`
- Modify: `app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt`
- Modify: `app/src/main/java/com/whispereverywhere/ui/screens/CloudProvidersScreen.kt`
- Modify: `app/src/main/java/com/whispereverywhere/data/local/PreferencesManager.kt`

**Interfaces:**
- Consumes: everything above.
- Produces: a working feature.

- [ ] **Step 1: `ConnectivityMonitor`**

```kotlin
package com.whispereverywhere.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * One `NET_CAPABILITY_VALIDATED` lookup — no round trip. The app already declares
 * ACCESS_NETWORK_STATE but had ZERO connectivity awareness before this.
 *
 * VALIDATED rather than merely connected: a captive-portal wifi reports connected while every
 * request fails, which would otherwise present to the user as "your key is broken".
 */
class ConnectivityMonitor(private val context: Context) {
    fun hasValidatedNetwork(): Boolean = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }.getOrDefault(false)
}
```

- [ ] **Step 2: Add the STT engine preference**

In `PreferencesManager`:

```kotlin
    /**
     * Which engine transcribes. null = on-device (the default and the shipped behaviour).
     * A ProviderId NAME selects cloud with local as fallback.
     */
    var sttProviderId: String?
        get() = prefs.getString(KEY_STT_PROVIDER, null)
        set(value) {
            if (value == null) prefs.edit().remove(KEY_STT_PROVIDER).apply()
            else prefs.edit().putString(KEY_STT_PROVIDER, value).apply()
        }
```
and `private const val KEY_STT_PROVIDER = "stt_provider_id"` in the companion.

- [ ] **Step 3: Select the engine at session start**

In `FloatingBubbleService`, where `LocalWhisperEngine` is constructed, choose instead:

```
- prefs.sttProviderId == null                      -> LocalWhisperEngine (unchanged path)
- provider selected but no key stored              -> LocalWhisperEngine + one-time toast
- provider selected, key present, NO network       -> LocalWhisperEngine + one-time toast
                                                      ("Offline — using the on-device model")
- provider selected, key present, network OK       -> FallbackTranscriptionEngine(
                                                        CloudTranscriptionEngine(OpenAiStt(...)),
                                                        LocalWhisperEngine(...))
```

The local engine must still be constructed in the fallback case — it is the safety net.

**Do not** change `commit()`/`onSegmentResolved` handling; the whole point of Release A is that the service does not care which engine it is talking to.

- [ ] **Step 4: STT engine selector in the UI**

In `CloudProvidersScreen`, above the provider cards, add a "Transcribe with" selector: **On-device (free, private)** — the default — and one row per provider that has a stored key. Selecting a provider writes `prefs.sttProviderId`; selecting on-device clears it.

Show a plain caption under a selected cloud provider: *"Audio is sent to {provider}. If it fails, the on-device model takes over."*

- [ ] **Step 5: Verify**

```bash
.\gradlew.bat :app:assembleDebug --no-daemon
.\gradlew.bat :app:testDebugUnitTest --no-daemon
.\gradlew.bat :app:assembleRelease --no-daemon
```

Then prove the one-way valve holds:
```bash
grep -rn "sttProviderId" app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt
```
Read every branch and confirm none can select a cloud engine when the preference is null.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/whispereverywhere/net/ConnectivityMonitor.kt \
        app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt \
        app/src/main/java/com/whispereverywhere/ui/screens/CloudProvidersScreen.kt \
        app/src/main/java/com/whispereverywhere/data/local/PreferencesManager.kt
git commit -m "feat(cloud): engine selection, connectivity check, and wiring

On-device stays the default; cloud is opt-in per provider and always
wrapped in the local fallback. Offline or key-less selections fall back to
local with one toast rather than failing.

ConnectivityMonitor checks NET_CAPABILITY_VALIDATED, not merely connected:
a captive-portal wifi reports connected while every request fails, which
would otherwise present to the user as 'your key is broken'. The app
already declared ACCESS_NETWORK_STATE but had zero connectivity awareness."
```

---

## Task 7: Compliance — the declaration must flip in this release

**Files:**
- Modify: `docs/PLAY-DECLARATIONS.md`
- Modify: `app/src/main/assets/privacy_policy.html`
- Modify: `docs/privacy.html`
- Modify: `app/src/main/java/com/whispereverywhere/ui/screens/CloudProvidersScreen.kt` (disclosure copy)

**This task is not optional and cannot be deferred.** C1 deliberately wrote the disclosure in future tense because no audio moved. This is the release where it does.

- [ ] **Step 1: Data Safety declaration**

In `docs/PLAY-DECLARATIONS.md`, change the audio entry to:

- **Audio files → Voice or sound recordings:** Collected **Yes**, Shared **Yes**, purpose **App functionality**, **Optional** (user must add a key and select a cloud provider).
- State plainly that the ephemeral-processing exemption is **not** claimed, because it requires retention no longer than needed to service the request in real time, which is not something this app can assert on a third party's behalf.
- Note that MediaProjection device audio is **never** sent to a cloud provider, and that on-device transcription remains the default.

- [ ] **Step 2: Privacy policy, both copies**

In `app/src/main/assets/privacy_policy.html`, change the cloud section from future to **present** tense: when a cloud provider is selected, dictated audio is sent to that provider's servers for transcription; it is off by default; the user's own key means the provider bills them under their own terms; the on-device model handles it if the provider fails; MediaProjection device audio is never sent.

Mirror the identical change into `docs/privacy.html` — that is the URL the Play Console serves, and it is currently stale.

- [ ] **Step 3: Disclosure copy**

In `CloudProvidersScreen`, change the modal from the C1 future-tense wording to present tense. Keep `dismissOnBackPress = false` / `dismissOnClickOutside = false` and the accept-button-only flag — dismissal must never count as consent. Keep the per-provider training lines.

- [ ] **Step 4: Verify the two policy copies agree**

```bash
diff <(sed -e 's/<[^>]*>//g' app/src/main/assets/privacy_policy.html | tr -s ' \n' ' ') \
     <(sed -e 's/<[^>]*>//g' docs/privacy.html | tr -s ' \n' ' ') && echo "OK: policies agree" || echo "DIFFER — reconcile before commit"
```
They are hand-synced with nothing enforcing it; report any residual difference rather than leaving it.

- [ ] **Step 5: Commit**

```bash
git add docs/PLAY-DECLARATIONS.md app/src/main/assets/privacy_policy.html docs/privacy.html \
        app/src/main/java/com/whispereverywhere/ui/screens/CloudProvidersScreen.kt
git commit -m "docs(compliance): audio now leaves the device — flip the declaration

This is the first release in which audio is transmitted, so the Data
Safety declaration, both privacy policy copies, and the in-app disclosure
all change in the SAME release as the code. C1 deliberately wrote the
disclosure in future tense because nothing moved yet.

Voice recordings become Collected=Yes, Shared=Yes, Optional. The
ephemeral-processing exemption is NOT claimed: it requires retention no
longer than needed to service the request in real time, which this app
cannot assert on a third party's behalf.

MediaProjection device audio is still never sent, and on-device remains
the default."
```

---

## Task 8: On-device verification

**Files:** none.

- [ ] **Step 1: Signature preflight, then install**

Follow the preflight in `2026-07-27-tts-diagnostics-release-0.md`. Use `adb install -r`. **Never `connectedAndroidTest`.**

- [ ] **Step 2: The checks only a real key can make**

1. **Local unchanged.** With no cloud provider selected, dictate — must behave exactly as today.
2. **Cloud happy path.** Select OpenAI, dictate a sentence. Text appears. **This is the only thing that proves the endpoint and pinned model id**, neither of which was re-confirmed against live docs.
3. **Fallback on airplane mode.** Enable airplane mode mid-session and keep talking: transcription must continue via the on-device model, not fail.
4. **Fallback on a bad key.** Temporarily save a mangled key, dictate: local must take over, and the fatal must latch (no repeated failed requests).
5. **Ordering under load.** Speak several short utterances quickly. With up to 3 requests in flight they will complete out of order — the text must still land **in the order you said it**. This is what Release A exists for.
6. **No stray markers.** No `[…]` in ordinary use.
7. **Long segment.** Speak continuously past 15 s and confirm the wall-clock cap segments cleanly.

- [ ] **Step 3: Record the outcome** in the plan or the ledger, including anything deferred.

---

## Self-Review

**Spec coverage** (spec §5.4, §5.5, §8.1-8.2, Release C):

| Spec requirement | Task |
|---|---|
| WAV wrapper; filename `audio.wav`; 25 MB cap | Tasks 1, 3 |
| Multipart upload with its own timeout profile | Task 2 |
| Pinned dated model snapshot | Task 3 |
| Fatal vs Transient vs BadSegment vs Offline classification | Task 3 |
| 429 quota-vs-rate-limit split | Task 3 |
| Cloud engine on the existing `TranscriptionEngine` contract | Task 4 |
| Every seq resolves exactly once, incl. cancellation | Task 4 |
| Fatal latches — one fatal costs one request | Task 4 |
| Local fallback retaining its own PCM | Task 5 |
| One-way valve, never local → cloud | Task 5 + Task 6 Step 5 grep |
| Empty transcript does not trigger fallback | Task 5 |
| `connect()` refuses / degrades when offline | Task 6 |
| Data Safety flip, both policy copies, present-tense disclosure | Task 7 |
| **Graduated degradation UX (badge/toast/sticky)** | **C2c** — Task 6 ships a single honest toast; the three-tier scheme needs the dispatcher |
| **RateLimitGate, token bucket, coalescing, PCM spill** | **C2c** |
| **Gemini + ElevenLabs adapters** | **C2b** |
| **Spend tracking / monthly cap** | **C2c** |
| **Streaming** | **C4** |

**Placeholder scan:** none. Every code step carries complete code except Task 6 Steps 3-4 and Task 7, which specify behaviour and copy precisely but must match existing screens the implementer will read.

**Type consistency:** `SttResult`/`SttError`/`FatalKind` are used identically in `OpenAiStt`, `CloudTranscriptionEngine` and the tests. `HttpTransport.FilePart` field names match between the interface, `OkHttpTransport`, `FakeHttpTransport` and `OpenAiStt`. `commit(): Long` returning `-1L` is handled consistently in both new engines and matches `LocalWhisperEngine`. `FallbackPolicy.shouldFallBack(SegmentOutcome)` covers all four outcome cases exhaustively.

**Two risks recorded rather than hidden.** First, the endpoint path and `DEFAULT_MODEL` were taken from research and not re-confirmed against live docs — Task 8 Step 2 check 2 is the only thing that proves them, and the ElevenLabs endpoint in C1 is precedent for getting this wrong. Second, `FallbackTranscriptionEngine` re-labels local retries with the original seq via `retryMap`; if a local retry ever fails to resolve, that seq stalls the orderer permanently. The audit should attack that path specifically.
