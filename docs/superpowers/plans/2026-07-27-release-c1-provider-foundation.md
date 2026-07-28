# Release C1 — Provider Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a user add, validate, and securely store their own API key for OpenAI, Google Gemini, or ElevenLabs — with the Play-required disclosure — without any audio leaving the device yet.

**Architecture:** A pure `ProviderCatalog` describes the three providers. `ProviderAccounts` stores one credential per provider through the existing `SecureStore` (Keystore AES-256-GCM), keyed by enum *name*. A thin `HttpTransport` seam over OkHttp makes every network call fakeable in unit tests. Key validation is one cheap authenticated GET per provider. Nothing transcribes yet — that is C2.

**Tech Stack:** Kotlin 2.0.21, OkHttp 5.4.0, kotlinx-serialization-json 1.7.3 (already present), JUnit 4, Compose, Android Keystore.

## Global Constraints

- **Kotlin 2.0.21 caps dependency versions.** A 2.0.x compiler reads metadata at most one minor ahead (2.1.0). **UNUSABLE:** Ktor 3.5.1, kotlinx-serialization-json ≥1.9.0, kotlinx-coroutines 1.11.0, and `okhttp-coroutines` 5.4.0 (it pulls coroutines 1.11.0). **SAFE:** OkHttp 5.4.0, serialization-json 1.8.1, coroutines 1.10.2. Hand-roll the ~20-line `suspendCancellableCoroutine` `Call.await()` bridge instead of adding `okhttp-coroutines`.
- **`compileSdk` must be 36** — already done; OkHttp 5.4.0's AAR declares `minCompileSdk=36`.
- **No credential may ever reach logcat.** `WE-DIAG` and `WE-TTS` log liberally. Never log a request that carries an auth header, never log a key even partially, and never put a key in an exception message.
- **GPLv3: plain HTTPS only.** Per the FSF, posting to a documented HTTPS endpoint creates no combined work. Linking a proprietary vendor Android SDK into this GPLv3 binary would. **Do not add any vendor SDK.**
- **Never embed a developer-owned key.** Every credential is the user's own, entered by them.
- **Unit tests run with `unitTests.isReturnDefaultValues = true`** (`app/build.gradle.kts:166`), so an `android.*` call in JVM-unit-tested code returns a type default instead of throwing — a broken dependency can PASS silently. Keep JVM-unit-tested classes free of `android.*`.
- **`java` is NOT on PATH.** PowerShell: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio1\jbr"`. Use `.\gradlew.bat --no-daemon`.
- **NEVER run `connectedAndroidTest` or `installDebug` without the signature preflight** in `2026-07-27-tts-diagnostics-release-0.md`. `connectedAndroidTest` **uninstalls the app on teardown** and has twice destroyed the user's 500+ MB of downloaded models.
- **Baseline to preserve: 17 suites / 117 tests / 0 failures**, `assembleDebug` and `assembleRelease` both green.
- **Do not touch** `TtsEngine.kt`, `TtsDiag.kt`, `TtsDiagMath.kt`, or the model catalog files — completed, reviewed, shipped work.

---

## What C1 deliberately does NOT do

No audio or text is transcribed, synthesized, or uploaded. The only network calls are key-validation GETs. Cloud STT is C2, cloud TTS is C3, streaming is C4. Resist implementing any of them here — an unreviewed half-engine is worse than none.

---

## Provider facts (from the spec, §3.9 and the provider matrix)

| Provider | Auth header | Streams? |
|---|---|---|
| OpenAI | `Authorization: Bearer sk-…` | Yes (C4) |
| Google Gemini | `x-goog-api-key: <key>` | **No** — no BYOK streaming path exists |
| ElevenLabs | `xi-api-key: <key>` — **not** a Bearer scheme | Yes (C4) |

**Google means the Gemini API only.** Google Cloud STT/TTS authenticate via OAuth2/service accounts and embed a `PROJECT_ID`; they are unreachable with a pasted key. Do not add them.

**Gemini's free tier trains on user data with human review**, and its paid tier does not. This requires its own distinct disclosure copy — a generic "data goes to a third party" line is materially inaccurate for OpenAI, which retains nothing and trains on nothing.

---

## File Structure

| File | Responsibility |
|---|---|
| `app/build.gradle.kts` | **Modify.** Add OkHttp 5.4.0. |
| `app/proguard-rules.pro` | **Modify.** OkHttp keep rules. |
| `app/src/main/java/com/whispereverywhere/net/HttpTransport.kt` | **Create.** Interface + OkHttp impl + `Call.await()`. The seam that makes providers testable. |
| `app/src/main/java/com/whispereverywhere/net/FakeHttpTransport.kt` | **Create (test source set).** Scripted responses for unit tests. |
| `app/src/main/java/com/whispereverywhere/provider/ProviderCatalog.kt` | **Create.** Pure. `ProviderId`, capabilities, auth-header shape, validation endpoint. No Android. |
| `app/src/test/java/com/whispereverywhere/provider/ProviderCatalogTest.kt` | **Create.** JVM tests. |
| `app/src/main/java/com/whispereverywhere/provider/ProviderAccounts.kt` | **Create.** Per-provider credential storage over `SecureStore`. |
| `app/src/main/java/com/whispereverywhere/data/local/SecureStore.kt` | **Modify.** Harden — it gets its first real caller here. |
| `app/src/main/java/com/whispereverywhere/provider/KeyValidator.kt` | **Create.** One authenticated GET per provider; classifies the result. |
| `app/src/test/java/com/whispereverywhere/provider/KeyValidatorTest.kt` | **Create.** Via `FakeHttpTransport`. |
| `app/src/main/java/com/whispereverywhere/ui/screens/CloudProvidersScreen.kt` | **Create.** Key entry, validation state, disclosure gate. |
| `app/src/main/java/com/whispereverywhere/ui/screens/SettingsScreen.kt` | **Modify.** One row linking to the new screen. |
| `app/src/main/assets/privacy_policy.html` | **Modify.** Clauses the disclosure links to. |

---

## Task 1: OkHttp + the coroutine bridge

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `app/proguard-rules.pro`
- Create: `app/src/main/java/com/whispereverywhere/net/HttpTransport.kt`

**Interfaces:**
- Consumes: nothing.
- Produces, used by Tasks 4-5:
  - `interface HttpTransport { suspend fun get(url: String, headers: Map<String, String>, timeoutMs: Long = 10_000): HttpResult }`
  - `sealed interface HttpResult { data class Ok(val code: Int, val body: String); data class HttpError(val code: Int, val body: String); data class NetworkError(val cause: Throwable) }`
  - `class OkHttpTransport(client: OkHttpClient = defaultClient()) : HttpTransport`

- [ ] **Step 1: Record the baseline**

```bash
.\gradlew.bat :app:testDebugUnitTest --no-daemon --rerun-tasks
```
Then the authoritative count:
```bash
cd C:\Users\bastr\.androidbuild\WhisperEverywhere\app\test-results\testDebugUnitTest
grep -hoE 'tests="[0-9]+" skipped="[0-9]+" failures="[0-9]+" errors="[0-9]+"' TEST-*.xml | awk -F'"' '{t+=$2;s+=$4;f+=$6;e+=$8} END{printf "tests=%d failures=%d errors=%d\n",t,f,e}'
```
Expected: `tests=117 failures=0 errors=0`.

- [ ] **Step 2: Add the dependency**

In `app/build.gradle.kts`, in the `dependencies { }` block beside the other `implementation` lines:

```kotlin
    // OkHttp 5.4.0: requires compileSdk 36 (already set). Do NOT add okhttp-coroutines — it pulls
    // kotlinx-coroutines 1.11.0, whose Kotlin metadata 2.2.0 is unreadable by this project's
    // Kotlin 2.0.21 compiler. The Call.await() bridge below is hand-rolled for that reason.
    implementation("com.squareup.okhttp3:okhttp:5.4.0")
```

- [ ] **Step 3: Add R8 keep rules**

Append to `app/proguard-rules.pro`:

```proguard
# --- OkHttp 5.x -------------------------------------------------------------
# OkHttp references optional Conscrypt/BouncyCastle/OpenJSSE providers reflectively and ships
# Animal Sniffer + JSR-305 annotations that R8 warns about. These are the upstream-recommended
# rules; without them the RELEASE build fails or strips TLS provider lookup, which debug builds
# never reveal (this project has a history of release-only failures — see b19233c).
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn javax.annotation.**
-dontwarn org.codehaus.mojo.animal_sniffer.*
-keepclasseswithmembers class * {
    @okhttp3.* <methods>;
}
```

- [ ] **Step 4: Write the transport seam**

Create `app/src/main/java/com/whispereverywhere/net/HttpTransport.kt`:

```kotlin
package com.whispereverywhere.net

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Outcome of one HTTP call. Deliberately three cases, not a nullable success: a 401 from a
 * provider and a dead radio need completely different handling, and collapsing them into
 * "failed" is how a client ends up retrying forever against an invalid key.
 */
sealed interface HttpResult {
    data class Ok(val code: Int, val body: String) : HttpResult
    data class HttpError(val code: Int, val body: String) : HttpResult
    data class NetworkError(val cause: Throwable) : HttpResult
}

/**
 * The seam that makes every provider client unit-testable without a network. Production uses
 * [OkHttpTransport]; tests use FakeHttpTransport in the test source set.
 */
interface HttpTransport {
    suspend fun get(
        url: String,
        headers: Map<String, String>,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): HttpResult

    companion object { const val DEFAULT_TIMEOUT_MS = 10_000L }
}

class OkHttpTransport(private val client: OkHttpClient = defaultClient()) : HttpTransport {

    override suspend fun get(url: String, headers: Map<String, String>, timeoutMs: Long): HttpResult {
        val request = Request.Builder().url(url).apply {
            headers.forEach { (k, v) -> header(k, v) }
        }.build()
        val call = client.newBuilder()
            .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()
            .newCall(request)
        return try {
            val response = call.await()
            // Read the body ONCE; it is a one-shot stream.
            val body = response.use { it.body?.string().orEmpty() }
            if (response.isSuccessful) HttpResult.Ok(response.code, body)
            else HttpResult.HttpError(response.code, body)
        } catch (io: IOException) {
            HttpResult.NetworkError(io)
        }
    }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}

/**
 * Hand-rolled because okhttp-coroutines 5.4.0 pulls kotlinx-coroutines 1.11.0, whose Kotlin
 * metadata this project's 2.0.21 compiler cannot read.
 *
 * The cancellation handler is load-bearing: without it a cancelled coroutine leaves the HTTP call
 * running to completion, holding a connection and — once C2 lands — continuing to spend the
 * user's money on a request nobody is waiting for.
 */
suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) = cont.resume(response)
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}
```

- [ ] **Step 5: Verify both build variants**

```bash
.\gradlew.bat :app:assembleDebug --no-daemon
.\gradlew.bat :app:assembleRelease --no-daemon
```
Both must succeed. **`assembleRelease` is the one that matters here** — R8 rules only take effect there, and a missing OkHttp keep rule fails only in release.

Then confirm no test regression:
```bash
.\gradlew.bat :app:testDebugUnitTest --no-daemon
```
Expected: still 117, 0 failures.

- [ ] **Step 6: Commit**

```bash
git add app/build.gradle.kts app/proguard-rules.pro app/src/main/java/com/whispereverywhere/net/HttpTransport.kt
git commit -m "feat(net): OkHttp 5.4.0 + HttpTransport seam

First HTTP client in the app since OkHttp was removed in 1d869c8 when it
went fully on-device.

HttpResult is three cases, not a nullable success: a 401 and a dead radio
need different handling, and collapsing them is how a client retries
forever against an invalid key.

Call.await() is hand-rolled because okhttp-coroutines 5.4.0 pulls
coroutines 1.11.0, whose Kotlin metadata 2.0.21 cannot read. Its
cancellation handler is load-bearing — without it a cancelled coroutine
leaves the call running, and once cloud STT lands that is the user's
money being spent on a request nobody awaits."
```

---

## Task 2: `ProviderCatalog` — pure provider facts

**Files:**
- Create: `app/src/main/java/com/whispereverywhere/provider/ProviderCatalog.kt`
- Test: `app/src/test/java/com/whispereverywhere/provider/ProviderCatalogTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces, used by Tasks 3-5:
  - `enum class ProviderId { OPENAI, GEMINI, ELEVENLABS }`
  - `data class Provider(val id: ProviderId, val displayName: String, val authHeaderName: String, val authHeaderValue: (String) -> String, val validationUrl: String, val supportsStt: Boolean, val supportsTts: Boolean, val supportsStreaming: Boolean, val keyHelpUrl: String, val trainsOnDataByDefault: Boolean)`
  - `ProviderCatalog.all: List<Provider>`, `ProviderCatalog.byId(id: ProviderId): Provider`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/whispereverywhere/provider/ProviderCatalogTest.kt`:

```kotlin
package com.whispereverywhere.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderCatalogTest {

    @Test fun all_three_providers_are_present() {
        assertEquals(
            listOf(ProviderId.OPENAI, ProviderId.GEMINI, ProviderId.ELEVENLABS),
            ProviderCatalog.all.map { it.id },
        )
    }

    @Test fun openai_uses_a_bearer_authorization_header() {
        val p = ProviderCatalog.byId(ProviderId.OPENAI)
        assertEquals("Authorization", p.authHeaderName)
        assertEquals("Bearer sk-test", p.authHeaderValue("sk-test"))
    }

    @Test fun gemini_uses_the_goog_api_key_header_not_bearer() {
        val p = ProviderCatalog.byId(ProviderId.GEMINI)
        assertEquals("x-goog-api-key", p.authHeaderName)
        assertEquals("k123", p.authHeaderValue("k123"))
    }

    @Test fun elevenlabs_uses_xi_api_key_and_is_not_a_bearer_scheme() {
        // Sending "Bearer <key>" to ElevenLabs 401s. This is the most commonly got-wrong header
        // of the three, so it is pinned.
        val p = ProviderCatalog.byId(ProviderId.ELEVENLABS)
        assertEquals("xi-api-key", p.authHeaderName)
        assertEquals("k123", p.authHeaderValue("k123"))
        assertFalse(p.authHeaderValue("k123").startsWith("Bearer"))
    }

    @Test fun gemini_does_not_support_streaming() {
        // Not a preference: the Live API is preview, session-capped, and wants ephemeral tokens
        // from a backend this app does not have. The UI must not offer streaming for Gemini.
        assertFalse(ProviderCatalog.byId(ProviderId.GEMINI).supportsStreaming)
        assertTrue(ProviderCatalog.byId(ProviderId.OPENAI).supportsStreaming)
        assertTrue(ProviderCatalog.byId(ProviderId.ELEVENLABS).supportsStreaming)
    }

    @Test fun every_provider_supports_both_modalities() {
        ProviderCatalog.all.forEach {
            assertTrue("${it.id} STT", it.supportsStt)
            assertTrue("${it.id} TTS", it.supportsTts)
        }
    }

    @Test fun only_gemini_trains_on_data_by_default() {
        // Drives per-provider disclosure copy. A generic "your data goes to a third party" line
        // would be materially inaccurate for OpenAI, which retains nothing and trains on nothing.
        assertTrue(ProviderCatalog.byId(ProviderId.GEMINI).trainsOnDataByDefault)
        assertFalse(ProviderCatalog.byId(ProviderId.OPENAI).trainsOnDataByDefault)
    }

    @Test fun every_url_is_https() {
        // A credential must never travel over cleartext.
        ProviderCatalog.all.forEach {
            assertTrue("${it.id} validationUrl", it.validationUrl.startsWith("https://"))
            assertTrue("${it.id} keyHelpUrl", it.keyHelpUrl.startsWith("https://"))
        }
    }

    @Test fun ids_are_stable_by_name_not_ordinal() {
        // Storage keys off enum NAME. Reordering the enum must never repoint a user's saved
        // credential at a different provider.
        assertEquals("OPENAI", ProviderId.OPENAI.name)
        assertEquals("GEMINI", ProviderId.GEMINI.name)
        assertEquals("ELEVENLABS", ProviderId.ELEVENLABS.name)
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

```bash
.\gradlew.bat :app:testDebugUnitTest --no-daemon --tests "com.whispereverywhere.provider.ProviderCatalogTest"
```
Expected: FAIL — `Unresolved reference: ProviderCatalog`.

- [ ] **Step 3: Write the implementation**

Create `app/src/main/java/com/whispereverywhere/provider/ProviderCatalog.kt`:

```kotlin
package com.whispereverywhere.provider

/**
 * Stable identity for a cloud provider.
 *
 * Persistence keys off [name], NEVER the ordinal: reordering this enum would otherwise silently
 * repoint every user's stored credential at a different provider.
 */
enum class ProviderId { OPENAI, GEMINI, ELEVENLABS }

/**
 * Everything the app needs to know about a provider that is not a secret.
 *
 * @param authHeaderValue builds the header value from a raw key. Modelled as a function because
 *   the three providers genuinely differ: OpenAI prefixes "Bearer ", the other two send the key
 *   bare. Sending "Bearer <key>" to ElevenLabs 401s.
 * @param trainsOnDataByDefault drives per-provider disclosure copy. One generic sentence would be
 *   materially inaccurate for OpenAI.
 */
data class Provider(
    val id: ProviderId,
    val displayName: String,
    val authHeaderName: String,
    val authHeaderValue: (String) -> String,
    val validationUrl: String,
    val supportsStt: Boolean,
    val supportsTts: Boolean,
    val supportsStreaming: Boolean,
    val keyHelpUrl: String,
    val trainsOnDataByDefault: Boolean,
)

/** Pure, Android-free. JVM-unit-testable like WhisperCatalog. */
object ProviderCatalog {

    val all: List<Provider> = listOf(
        Provider(
            id = ProviderId.OPENAI,
            displayName = "OpenAI",
            authHeaderName = "Authorization",
            authHeaderValue = { "Bearer $it" },
            validationUrl = "https://api.openai.com/v1/models",
            supportsStt = true,
            supportsTts = true,
            supportsStreaming = true,
            keyHelpUrl = "https://platform.openai.com/api-keys",
            trainsOnDataByDefault = false,
        ),
        Provider(
            id = ProviderId.GEMINI,
            displayName = "Google Gemini",
            authHeaderName = "x-goog-api-key",
            authHeaderValue = { it },
            validationUrl = "https://generativelanguage.googleapis.com/v1beta/models",
            supportsStt = true,
            supportsTts = true,
            // NOT a preference. The Live API is preview, session-capped at 15 minutes, and
            // recommends ephemeral tokens minted by a backend this app does not have — so no
            // usable streaming path exists for a client holding only the user's own key.
            supportsStreaming = false,
            keyHelpUrl = "https://aistudio.google.com/apikey",
            // Unpaid tier: Google uses submitted content to improve its products and human
            // reviewers may read API input and output. Paid tier excludes this.
            trainsOnDataByDefault = true,
        ),
        Provider(
            id = ProviderId.ELEVENLABS,
            displayName = "ElevenLabs",
            // NOT a Bearer scheme — this is the most commonly got-wrong header of the three.
            authHeaderName = "xi-api-key",
            authHeaderValue = { it },
            validationUrl = "https://api.elevenlabs.io/v1/user",
            supportsStt = true,
            supportsTts = true,
            supportsStreaming = true,
            keyHelpUrl = "https://elevenlabs.io/app/settings/api-keys",
            trainsOnDataByDefault = true,
        ),
    )

    fun byId(id: ProviderId): Provider = all.first { it.id == id }
}
```

> **Verify before shipping:** the three `validationUrl` values are the conventional cheap
> authenticated GET for each provider, but they were not re-confirmed against live documentation
> when this plan was written. Task 5's manual check exercises each one against a real key. If any
> returns 404, correct the URL there and note it in your report — do NOT silently substitute a
> different endpoint.

- [ ] **Step 4: Run it to verify it passes**

```bash
.\gradlew.bat :app:testDebugUnitTest --no-daemon --tests "com.whispereverywhere.provider.ProviderCatalogTest"
```
Expected: PASS, 9 tests. Full suite: **126 tests**, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/whispereverywhere/provider/ProviderCatalog.kt \
        app/src/test/java/com/whispereverywhere/provider/ProviderCatalogTest.kt
git commit -m "feat(provider): ProviderCatalog — pure provider facts

authHeaderValue is a function because the three providers genuinely
differ: OpenAI prefixes 'Bearer ', Gemini and ElevenLabs send the key
bare. Sending 'Bearer <key>' to ElevenLabs 401s, so that is pinned by test.

Gemini.supportsStreaming = false is not a preference: the Live API is
preview, session-capped, and wants ephemeral tokens from a backend this
app does not have.

trainsOnDataByDefault drives per-provider disclosure copy — a single
generic sentence would be materially inaccurate for OpenAI, which retains
nothing and trains on nothing.

ProviderId persists by NAME, never ordinal: reordering would otherwise
repoint every user's stored credential at a different provider."
```

---

## Task 3: `ProviderAccounts` + hardening `SecureStore` for its first real caller

**Files:**
- Create: `app/src/main/java/com/whispereverywhere/provider/ProviderAccounts.kt`
- Modify: `app/src/main/java/com/whispereverywhere/data/local/SecureStore.kt`
- Modify: `app/src/main/java/com/whispereverywhere/data/local/PreferencesManager.kt`
- Test: `app/src/androidTest/java/com/whispereverywhere/provider/ProviderAccountsInstrumentedTest.kt`

**Interfaces:**
- Consumes: `SecureStore` (`put`/`get`/`remove`/`isAvailable`), `ProviderId` (Task 2).
- Produces, used by Tasks 4-5:
  - `class ProviderAccounts(secureStore: SecureStore)`
  - `fun key(id: ProviderId): String?`
  - `fun setKey(id: ProviderId, key: String)` — throws `SecureStoreException`
  - `fun clear(id: ProviderId)`
  - `fun configured(): Set<ProviderId>`

**Why `SecureStore` is hardened here:** it has had **zero production callers** until now, so its
encryption has never executed. A prior audit flagged real defects in it that were unreachable and
therefore carried. This task is the first caller, so they get fixed in context rather than
speculatively.

- [ ] **Step 1: Read `SecureStore` and identify the defects**

Open `app/src/main/java/com/whispereverywhere/data/local/SecureStore.kt` and confirm:
- `secretKey()` does an unsynchronised check-then-generate: two threads calling `put`/`get`
  concurrently can both miss the key and both call `generateKey()`, and the second overwrites the
  first — making everything written with the first key permanently undecryptable.
- There is no guard against an empty `KEY_ALIAS` collision or a non-`SecretKeyEntry` under the same
  alias.

- [ ] **Step 2: Harden `secretKey()`**

Two class-level fields, then a rewritten `secretKey()`. **The two `private val`/`private var`
declarations go at CLASS level, immediately after the existing `prefs` field — not inside the
function.** The existing `secretKey()` body is replaced entirely.

```kotlin
    private val keyLock = Any()

    @Volatile private var cachedKey: SecretKey? = null

    private fun secretKey(): SecretKey {
        cachedKey?.let { return it }
        return synchronized(keyLock) {
            cachedKey ?: loadOrGenerateKey().also { cachedKey = it }
        }
    }

    private fun loadOrGenerateKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        // A non-SecretKeyEntry under our alias means something else is squatting it. Treat that
        // as absent and generate ours rather than crashing on a bad cast.
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    /**
     * Drop the cached key so the next call re-resolves from the Keystore.
     *
     * Caching introduces a failure mode that did not exist before: Keystore keys are DESTROYED
     * when the user removes their screen lock or re-enrolls biometrics, and a cached SecretKey
     * reference to a destroyed key fails every time it is used. Without this, one invalidation
     * would break the store for the entire process lifetime even though re-resolving would
     * succeed. Call it from every crypto failure path.
     */
    private fun invalidateCachedKey() { synchronized(keyLock) { cachedKey = null } }
```

- [ ] **Step 2b: Clear the cache on every crypto failure**

In `put`, inside the existing `catch (t: Throwable)` block, call `invalidateCachedKey()` **before**
throwing `SecureStoreException`.

In `get`, inside the existing `catch (_: Throwable)` block, call `invalidateCachedKey()` before
returning null.

Without this, Step 2's cache turns a recoverable one-off (key invalidated → next call regenerates)
into a permanent process-lifetime failure.

Add to the class KDoc:

```
 * Key acquisition is synchronized and cached. An unsynchronised check-then-generate lets two
 * threads each generate a key, the second overwriting the first — which makes every value written
 * under the first key permanently undecryptable, with no error at write time. That was
 * unreachable while this class had no callers; it does now.
 *
 * The cache is dropped on any crypto failure, because Keystore keys are destroyed when the user
 * removes their screen lock or re-enrolls biometrics, and a cached reference to a destroyed key
 * fails forever otherwise.
```

- [ ] **Step 3: Write `ProviderAccounts`**

Create `app/src/main/java/com/whispereverywhere/provider/ProviderAccounts.kt`:

```kotlin
package com.whispereverywhere.provider

import com.whispereverywhere.data.local.SecureStore

/**
 * One credential per provider, stored through [SecureStore] (Keystore AES-256-GCM).
 *
 * Keys are namespaced by the provider's enum NAME, never its ordinal — reordering [ProviderId]
 * must never repoint a user's saved credential at a different provider.
 *
 * [setKey] propagates [com.whispereverywhere.data.local.SecureStoreException] deliberately. The
 * caller must tell the user the key was not saved; silently swallowing it is exactly the failure
 * this app already shipped once, when a failed encrypted write fell back to plaintext.
 */
class ProviderAccounts(private val secureStore: SecureStore) {

    fun key(id: ProviderId): String? = secureStore.get(prefKey(id))?.takeIf { it.isNotBlank() }

    fun setKey(id: ProviderId, key: String) {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) secureStore.remove(prefKey(id)) else secureStore.put(prefKey(id), trimmed)
    }

    fun clear(id: ProviderId) = secureStore.remove(prefKey(id))

    fun configured(): Set<ProviderId> = ProviderId.entries.filter { key(it) != null }.toSet()

    private fun prefKey(id: ProviderId) = "provider_key_${id.name}"
}
```

- [ ] **Step 4: Expose it from `PreferencesManager`**

Add beside the existing `secureStore` field:

```kotlin
    /** Per-provider cloud credentials (Release C1). Backed by the same SecureStore. */
    val providerAccounts: ProviderAccounts = ProviderAccounts(secureStore)
```

Leave the existing `apiKey` property alone — it is legacy, still unused, and removing it is not
this task's job.

- [ ] **Step 5: Write the instrumented test**

Create `app/src/androidTest/java/com/whispereverywhere/provider/ProviderAccountsInstrumentedTest.kt`:

```kotlin
package com.whispereverywhere.provider

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.whispereverywhere.data.local.SecureStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderAccountsInstrumentedTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefsName = "secure_store_provider_test"
    private val accounts = ProviderAccounts(SecureStore(ctx, prefsName))

    @After fun cleanUp() {
        ctx.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun round_trips_a_key_per_provider() {
        accounts.setKey(ProviderId.OPENAI, "sk-openai")
        accounts.setKey(ProviderId.ELEVENLABS, "el-key")
        assertEquals("sk-openai", accounts.key(ProviderId.OPENAI))
        assertEquals("el-key", accounts.key(ProviderId.ELEVENLABS))
        assertNull(accounts.key(ProviderId.GEMINI))
    }

    @Test fun stored_bytes_do_not_contain_the_plaintext_key() {
        // The actual security property. Whatever lands on disk must not be the credential.
        val secret = "sk-super-secret-provider-key"
        accounts.setKey(ProviderId.OPENAI, secret)
        val raw = ctx.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .getString("provider_key_OPENAI", "") ?: ""
        assertTrue("raw must not contain the plaintext", !raw.contains(secret))
        assertTrue("raw must be non-empty", raw.isNotEmpty())
    }

    @Test fun configured_reflects_what_is_stored() {
        assertTrue(accounts.configured().isEmpty())
        accounts.setKey(ProviderId.GEMINI, "g-key")
        assertEquals(setOf(ProviderId.GEMINI), accounts.configured())
    }

    @Test fun blank_key_clears_rather_than_storing_emptiness() {
        accounts.setKey(ProviderId.OPENAI, "sk-x")
        accounts.setKey(ProviderId.OPENAI, "   ")
        assertNull(accounts.key(ProviderId.OPENAI))
    }

    @Test fun keys_are_trimmed_because_users_paste_with_whitespace() {
        accounts.setKey(ProviderId.OPENAI, "  sk-padded  ")
        assertEquals("sk-padded", accounts.key(ProviderId.OPENAI))
    }
}
```

- [ ] **Step 6: Verify**

```bash
.\gradlew.bat :app:compileDebugAndroidTestKotlin --no-daemon
.\gradlew.bat :app:testDebugUnitTest --no-daemon
.\gradlew.bat :app:assembleRelease --no-daemon
```

**Do NOT run `connectedAndroidTest`** — it uninstalls the app and destroys the user's models.
Record the instrumented run as DEFERRED. Note in your report that `SecureStore`'s encryption still
has not executed anywhere, and that this test is the thing that will finally prove it when a device
run is scheduled.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/whispereverywhere/provider/ProviderAccounts.kt \
        app/src/main/java/com/whispereverywhere/data/local/SecureStore.kt \
        app/src/main/java/com/whispereverywhere/data/local/PreferencesManager.kt \
        app/src/androidTest/java/com/whispereverywhere/provider/ProviderAccountsInstrumentedTest.kt
git commit -m "feat(provider): per-provider credentials; harden SecureStore for its first caller

ProviderAccounts namespaces by ProviderId.name, never ordinal — reordering
the enum would otherwise repoint a user's saved credential at a different
provider. setKey propagates SecureStoreException deliberately: the caller
must tell the user the key was not saved, which is exactly what this app
failed to do when a failed encrypted write silently fell back to plaintext.

SecureStore had ZERO production callers until now, so its encryption has
never executed and an audit-flagged defect was unreachable: secretKey()
did an unsynchronised check-then-generate, so two threads could each
generate a key and the second would overwrite the first, making every
value written under the first permanently undecryptable with no error at
write time. Now synchronized, cached, and tolerant of a wrong-typed entry
squatting the alias."
```

---

## Task 4: `KeyValidator` — is this key real?

**Files:**
- Create: `app/src/main/java/com/whispereverywhere/provider/KeyValidator.kt`
- Create: `app/src/test/java/com/whispereverywhere/net/FakeHttpTransport.kt`
- Test: `app/src/test/java/com/whispereverywhere/provider/KeyValidatorTest.kt`

**Interfaces:**
- Consumes: `HttpTransport`/`HttpResult` (Task 1), `ProviderCatalog`/`ProviderId` (Task 2).
- Produces, used by Task 5:
  - `sealed interface KeyStatus { data object Valid; data object Invalid; data object NoCredit; data object RateLimited; data object Offline; data class Unknown(val detail: String) }`
  - `class KeyValidator(transport: HttpTransport)`
  - `suspend fun validate(id: ProviderId, key: String): KeyStatus`

- [ ] **Step 1: Write the fake transport**

Create `app/src/test/java/com/whispereverywhere/net/FakeHttpTransport.kt`:

```kotlin
package com.whispereverywhere.net

/**
 * Scripted HTTP for unit tests. Records the last request so tests can assert the auth header was
 * built correctly — the single most common provider-integration bug.
 */
class FakeHttpTransport(private val script: (String, Map<String, String>) -> HttpResult) : HttpTransport {

    var lastUrl: String? = null
        private set
    var lastHeaders: Map<String, String> = emptyMap()
        private set
    var callCount: Int = 0
        private set

    override suspend fun get(url: String, headers: Map<String, String>, timeoutMs: Long): HttpResult {
        lastUrl = url
        lastHeaders = headers
        callCount++
        return script(url, headers)
    }
}
```

- [ ] **Step 2: Write the failing test**

Create `app/src/test/java/com/whispereverywhere/provider/KeyValidatorTest.kt`:

```kotlin
package com.whispereverywhere.provider

import com.whispereverywhere.net.FakeHttpTransport
import com.whispereverywhere.net.HttpResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class KeyValidatorTest {

    private fun validator(result: HttpResult, capture: ((String, Map<String, String>) -> Unit)? = null) =
        KeyValidator(FakeHttpTransport { url, headers -> capture?.invoke(url, headers); result })

    @Test fun a_200_means_the_key_is_valid() = runBlocking {
        assertEquals(KeyStatus.Valid, validator(HttpResult.Ok(200, "{}")).validate(ProviderId.OPENAI, "sk-x"))
    }

    @Test fun a_401_means_the_key_is_invalid() = runBlocking {
        assertEquals(KeyStatus.Invalid, validator(HttpResult.HttpError(401, "")).validate(ProviderId.OPENAI, "sk-x"))
    }

    @Test fun a_403_also_means_invalid() = runBlocking {
        assertEquals(KeyStatus.Invalid, validator(HttpResult.HttpError(403, "")).validate(ProviderId.GEMINI, "k"))
    }

    @Test fun a_402_means_out_of_credit_not_invalid() = runBlocking {
        // Telling a user their key is wrong when their card declined sends them to regenerate a
        // perfectly good key.
        assertEquals(KeyStatus.NoCredit, validator(HttpResult.HttpError(402, "")).validate(ProviderId.ELEVENLABS, "k"))
    }

    @Test fun a_429_quota_body_means_out_of_credit() = runBlocking {
        // OpenAI returns 429 for BOTH transient rate limiting and permanently exhausted credit;
        // only the body distinguishes them. Backing off against an empty wallet retries forever.
        val body = """{"error":{"code":"insufficient_quota","message":"exceeded quota"}}"""
        assertEquals(KeyStatus.NoCredit, validator(HttpResult.HttpError(429, body)).validate(ProviderId.OPENAI, "sk-x"))
    }

    @Test fun a_429_without_a_quota_body_is_rate_limiting() = runBlocking {
        assertEquals(KeyStatus.RateLimited, validator(HttpResult.HttpError(429, "slow down")).validate(ProviderId.OPENAI, "sk-x"))
    }

    @Test fun a_network_error_is_offline_not_invalid() = runBlocking {
        // Marking a good key invalid because the user was on a train is the worst outcome here.
        assertEquals(
            KeyStatus.Offline,
            validator(HttpResult.NetworkError(IOException("no route"))).validate(ProviderId.OPENAI, "sk-x"),
        )
    }

    @Test fun an_unexpected_status_is_unknown_and_carries_detail() = runBlocking {
        val status = validator(HttpResult.HttpError(500, "boom")).validate(ProviderId.OPENAI, "sk-x")
        assertTrue(status is KeyStatus.Unknown)
    }

    @Test fun a_blank_key_is_invalid_without_any_network_call() = runBlocking {
        val fake = FakeHttpTransport { _, _ -> HttpResult.Ok(200, "{}") }
        assertEquals(KeyStatus.Invalid, KeyValidator(fake).validate(ProviderId.OPENAI, "   "))
        assertEquals("must not hit the network for a blank key", 0, fake.callCount)
    }

    @Test fun openai_gets_a_bearer_header_at_the_right_url() = runBlocking {
        var url: String? = null
        var headers: Map<String, String> = emptyMap()
        validator(HttpResult.Ok(200, "{}")) { u, h -> url = u; headers = h }
            .validate(ProviderId.OPENAI, "sk-abc")
        assertEquals("https://api.openai.com/v1/models", url)
        assertEquals("Bearer sk-abc", headers["Authorization"])
    }

    @Test fun elevenlabs_gets_a_bare_xi_api_key_header() = runBlocking {
        var headers: Map<String, String> = emptyMap()
        validator(HttpResult.Ok(200, "{}")) { _, h -> headers = h }
            .validate(ProviderId.ELEVENLABS, "el-abc")
        assertEquals("el-abc", headers["xi-api-key"])
        assertTrue("must not send an Authorization header", !headers.containsKey("Authorization"))
    }

    @Test fun gemini_gets_a_bare_goog_header() = runBlocking {
        var headers: Map<String, String> = emptyMap()
        validator(HttpResult.Ok(200, "{}")) { _, h -> headers = h }
            .validate(ProviderId.GEMINI, "g-abc")
        assertEquals("g-abc", headers["x-goog-api-key"])
    }
}
```

- [ ] **Step 3: Run to verify it fails**

```bash
.\gradlew.bat :app:testDebugUnitTest --no-daemon --tests "com.whispereverywhere.provider.KeyValidatorTest"
```
Expected: FAIL — `Unresolved reference: KeyValidator`.

- [ ] **Step 4: Write the implementation**

Create `app/src/main/java/com/whispereverywhere/provider/KeyValidator.kt`:

```kotlin
package com.whispereverywhere.provider

import com.whispereverywhere.net.HttpResult
import com.whispereverywhere.net.HttpTransport

/**
 * Outcome of checking a key. Deliberately more than valid/invalid: telling a user their key is
 * wrong when the real cause was an expired card, a rate limit, or a dead radio sends them off to
 * regenerate a perfectly good key.
 */
sealed interface KeyStatus {
    data object Valid : KeyStatus
    data object Invalid : KeyStatus
    data object NoCredit : KeyStatus
    data object RateLimited : KeyStatus
    data object Offline : KeyStatus
    data class Unknown(val detail: String) : KeyStatus
}

/**
 * Verifies a key with one cheap authenticated GET per provider.
 *
 * NEVER log the key, the header map, or the raw request. [KeyStatus.Unknown.detail] carries only
 * the status code and a truncated body, which providers do not echo credentials into — but keep
 * it short and never widen it to include the request.
 */
class KeyValidator(private val transport: HttpTransport) {

    suspend fun validate(id: ProviderId, key: String): KeyStatus {
        val trimmed = key.trim()
        // Short-circuit before touching the network: a blank key cannot be valid, and firing a
        // request for it wastes a round trip and can count against a rate limit.
        if (trimmed.isEmpty()) return KeyStatus.Invalid

        val provider = ProviderCatalog.byId(id)
        val headers = mapOf(provider.authHeaderName to provider.authHeaderValue(trimmed))

        return when (val result = transport.get(provider.validationUrl, headers)) {
            is HttpResult.Ok -> KeyStatus.Valid
            is HttpResult.NetworkError -> KeyStatus.Offline
            is HttpResult.HttpError -> classify(result.code, result.body)
        }
    }

    private fun classify(code: Int, body: String): KeyStatus = when (code) {
        401, 403 -> KeyStatus.Invalid
        402 -> KeyStatus.NoCredit
        // OpenAI returns 429 for BOTH transient rate limiting and permanently exhausted credit,
        // distinguishable only from the body. Collapsing them makes the client back off
        // exponentially against an empty wallet, forever.
        429 -> if (QUOTA_MARKERS.any { body.contains(it, ignoreCase = true) }) {
            KeyStatus.NoCredit
        } else {
            KeyStatus.RateLimited
        }
        else -> KeyStatus.Unknown("HTTP $code: ${body.take(200)}")
    }

    private companion object {
        val QUOTA_MARKERS = listOf("insufficient_quota", "quota_exceeded", "RESOURCE_EXHAUSTED")
    }
}
```

- [ ] **Step 5: Run to verify it passes**

```bash
.\gradlew.bat :app:testDebugUnitTest --no-daemon --tests "com.whispereverywhere.provider.KeyValidatorTest"
```
Expected: PASS, 12 tests. Full suite: **138 tests**, 0 failures.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/whispereverywhere/provider/KeyValidator.kt \
        app/src/test/java/com/whispereverywhere/net/FakeHttpTransport.kt \
        app/src/test/java/com/whispereverywhere/provider/KeyValidatorTest.kt
git commit -m "feat(provider): KeyValidator — one cheap authenticated GET per provider

KeyStatus is six cases, not a boolean. Telling a user their key is wrong
when the real cause was an expired card, a rate limit, or a dead radio
sends them off to regenerate a perfectly good key.

The 429 split matters most: OpenAI returns it for BOTH transient rate
limiting and permanently exhausted credit, and only the body
distinguishes them. Collapsing them makes the client back off
exponentially against an empty wallet, forever.

Blank keys short-circuit before the network — a test asserts zero calls.
FakeHttpTransport records the last request so the auth header shape is
pinned per provider, which is the most common integration bug: ElevenLabs
401s on a Bearer scheme."
```

---

## Task 5: Cloud Providers screen — entry, validation, and the Play-required disclosure

**Files:**
- Create: `app/src/main/java/com/whispereverywhere/ui/screens/CloudProvidersScreen.kt`
- Modify: `app/src/main/java/com/whispereverywhere/ui/screens/SettingsScreen.kt`
- Modify: `app/src/main/java/com/whispereverywhere/MainActivity.kt` (nav route)
- Modify: `app/src/main/assets/privacy_policy.html`

**Interfaces:**
- Consumes: `ProviderCatalog`, `ProviderAccounts`, `KeyValidator`, `KeyStatus`, `OkHttpTransport`.
- Produces: end of C1. C2 consumes `ProviderAccounts` and the disclosure-accepted flag.

**Read `SettingsScreen.kt` and `OnboardingModelScreen.kt` first** and reuse their existing card,
section, and dialog composables. Do not introduce a new visual language.

- [ ] **Step 1: Add the disclosure-accepted flag**

In `PreferencesManager`, beside the other preference accessors:

```kotlin
    /**
     * True once the user has seen the cloud disclosure and affirmatively accepted it.
     *
     * Play requires prominent in-app disclosure BEFORE any personal data is sent off-device, shown
     * during normal usage rather than buried in a menu, with affirmative action. Back-press or
     * tap-away must NOT count as acceptance — hence a persisted flag set only by the accept
     * button, never by dismissal.
     */
    var cloudDisclosureAccepted: Boolean
        get() = prefs.getBoolean(KEY_CLOUD_DISCLOSURE_ACCEPTED, false)
        set(value) { prefs.edit().putBoolean(KEY_CLOUD_DISCLOSURE_ACCEPTED, value).apply() }
```

and in the companion object:

```kotlin
        private const val KEY_CLOUD_DISCLOSURE_ACCEPTED = "cloud_disclosure_accepted_v1"
```

- [ ] **Step 2: Build the disclosure dialog**

In `CloudProvidersScreen.kt`, a modal shown before any key field becomes editable when
`cloudDisclosureAccepted` is false. Requirements, all Play-driven:

- `AlertDialog` with `properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)` — dismissal must not equal consent.
- Two symmetric buttons: **"I understand"** (sets the flag) and **"Not now"** (navigates back, flag untouched).
- Body copy, verbatim:

> **Cloud transcription sends your audio off this device.**
>
> Whisper Everywhere works entirely on your device by default. If you add a provider key below, the audio you dictate — and text you select for read-aloud — is sent to that company's servers to be processed.
>
> You are using your own account, so they bill you directly and their terms apply to your data.
>
> This is off until you add a key, and you can remove a key at any time.

- Below the body, a per-provider line rendered from `ProviderCatalog`, because one generic sentence would be inaccurate:
  - Providers with `trainsOnDataByDefault == false`: *"OpenAI does not train on data sent through the API."*
  - `ProviderId.GEMINI`: *"Google's free tier uses what you send to improve its products, and human reviewers may read it. Paid tiers do not."*
  - `ProviderId.ELEVENLABS`: *"ElevenLabs trains on API data by default; you can opt out in your ElevenLabs account settings."*
- A link opening `privacy_policy.html`.

- [ ] **Step 3: Build the provider rows**

One card per `ProviderCatalog.all` entry containing:

- Provider name, and a **"Streaming"** chip only when `supportsStreaming` — so nobody picks Gemini expecting live text.
- An `OutlinedTextField` for the key with `visualTransformation = PasswordVisualTransformation()` and a show/hide toggle. Never render the stored key back in full; on load show a masked placeholder such as `sk-…abcd` derived from the last 4 characters only.
- **Save & verify** button → calls `KeyValidator.validate` on `Dispatchers.IO`, then `ProviderAccounts.setKey` **only on `KeyStatus.Valid`**.
- **Remove** button when a key is stored.
- Status line mapped from `KeyStatus`:
  - `Valid` → "Key verified ✓"
  - `Invalid` → "That key was rejected. Check you copied all of it."
  - `NoCredit` → "The key works, but the account has no credit." — **and still save it**, because the key is genuinely valid.
  - `RateLimited` → "Rate limited — try again in a moment." Do not save; do not discard what the user typed.
  - `Offline` → "Couldn't reach {provider}. Check your connection." Do not save.
  - `Unknown` → "Couldn't verify (detail). Save anyway?" with an explicit save affordance.
- A link to `keyHelpUrl` labelled "Where do I get a key?"

Wrap `setKey` in try/catch for `SecureStoreException` and surface **"Couldn't save securely — your key was not stored."** Never silently swallow it.

- [ ] **Step 4: Wire navigation**

Add a route in `MainActivity.kt` matching the existing nav pattern, and a row in `SettingsScreen.kt`:

- Title: **"Cloud providers"**
- Subtitle: derived, never hardcoded —
  `if (configured.isEmpty()) "Use your own OpenAI, Gemini or ElevenLabs key" else "${configured.size} configured"`

- [ ] **Step 5: Update the privacy policy**

In `app/src/main/assets/privacy_policy.html`, add a **Cloud providers (optional)** section stating:
what is sent (dictated audio; text selected for read-aloud), that it is off by default and only
active once the user adds their own key, that the provider bills them directly under their own
terms, that OpenAI does not train on API data while Gemini's free tier does with human review and
ElevenLabs trains by default with an account-level opt-out, and that removing a key stops all
transmission immediately.

**Do not yet change the Data Safety declaration or `docs/PLAY-LISTING.md`.** No audio leaves the
device until C2, and the declaration must flip in the same release that actually transmits.
Note this explicitly in your report so it is not forgotten.

- [ ] **Step 6: Verify**

```bash
.\gradlew.bat :app:assembleDebug --no-daemon
.\gradlew.bat :app:testDebugUnitTest --no-daemon
.\gradlew.bat :app:assembleRelease --no-daemon
```
Expected: all green; 138 tests, 0 failures.

Then confirm no credential can reach logcat:
```bash
grep -rnE "Log\.(d|i|v|w|e).*(apiKey|providerKey|authHeader|xi-api-key|Bearer)" app/src/main --include=*.kt || echo "OK: no credential logging"
```
Expected: `OK: no credential logging`.

- [ ] **Step 7: Manual verification on device — the only way to test the real endpoints**

Run the **signature preflight** from the Release 0 plan, then `adb install -r` the debug APK.
**Do NOT run `connectedAndroidTest`.**

Confirm:
1. The disclosure appears before any key field is editable, and **back-press does not dismiss it**.
2. "Not now" leaves the flag unset — reopening shows the disclosure again.
3. A real key for at least one provider returns **"Key verified ✓"**. This is what proves the
   `validationUrl` values are correct. If any returns a 404, the URL is wrong — fix it in
   `ProviderCatalog`, add a test, and report it.
4. A deliberately corrupted key returns "That key was rejected."
5. Airplane mode returns "Couldn't reach…", **not** "rejected".
6. The stored key is never displayed in full after saving.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/whispereverywhere/ui/screens/CloudProvidersScreen.kt \
        app/src/main/java/com/whispereverywhere/ui/screens/SettingsScreen.kt \
        app/src/main/java/com/whispereverywhere/MainActivity.kt \
        app/src/main/java/com/whispereverywhere/data/local/PreferencesManager.kt \
        app/src/main/assets/privacy_policy.html
git commit -m "feat(ui): Cloud providers screen — key entry, verification, disclosure

Play requires prominent in-app disclosure before personal data leaves the
device, shown in normal usage and requiring affirmative action. Back-press
and tap-away are disabled so dismissal cannot be mistaken for consent, and
the accepted flag is set only by the accept button.

Disclosure copy is PER PROVIDER because one generic line would be
materially inaccurate: OpenAI does not train on API data, Gemini's free
tier does and human reviewers may read it, ElevenLabs trains by default
with an account-level opt-out.

NoCredit still saves the key — it is genuinely valid, the account is just
empty. Offline does not save and does not claim the key was rejected.

Data Safety and the Play listing are deliberately NOT changed yet: no
audio leaves the device until C2, and the declaration must flip in the
same release that actually transmits."
```

---

## Self-Review

**Spec coverage** (spec §7.1, §8.2, §3.4, §3.9, §3.11, and Release C's credential portion):

| Spec requirement | Task |
|---|---|
| OkHttp 5.4.0 + R8 rules | Task 1 |
| Hand-rolled `Call.await()` (no `okhttp-coroutines`) | Task 1 Step 4 |
| `HttpTransport` seam + fake for tests | Tasks 1, 4 |
| `ProviderId` keyed by name, never ordinal | Task 2, Task 3 |
| Per-provider auth header shapes | Task 2 |
| Gemini has no streaming | Task 2 (`supportsStreaming = false`) |
| Google Cloud STT/TTS excluded entirely | Task 2 (catalog has three entries) |
| One `ProviderAccount` per provider over `SecureStore` | Task 3 |
| `SecureStore` hardening at its first caller | Task 3 Step 2 |
| Key validation per provider | Task 4 |
| 429 quota-vs-rate-limit split | Task 4 |
| Prominent disclosure, affirmative action, no dismiss-as-consent | Task 5 Step 2 |
| Per-provider training disclosure | Task 5 Step 2 |
| Privacy policy clauses | Task 5 Step 5 |
| No credential to logcat | Task 5 Step 6 grep |
| GPLv3: plain HTTPS, no vendor SDK | Global Constraints; only OkHttp added |
| **Data Safety flip** | **Deliberately deferred to C2** — no audio leaves in C1 |
| **Settings + one-time Home nudge (D11)** | **Settings row in Task 5; the Home nudge lands in C2** with the feature it advertises |
| Cloud STT engine, fallback, TTS, streaming | **C2/C3/C4** — explicitly out of scope |

**Placeholder scan:** none. Every code step carries complete code. Task 5 describes UI structurally
rather than as a literal composable because it must match existing screens the implementer will
read; every string, behaviour, and state mapping is specified exactly.

**Type consistency:** `HttpTransport.get(url, headers, timeoutMs)` is used identically in Task 1's
impl, Task 4's fake, and `KeyValidator`. `HttpResult.{Ok,HttpError,NetworkError}` field names match
across the `when` in `KeyValidator`. `ProviderAccounts.{key,setKey,clear,configured}` signatures in
Task 3 match every call site in Task 5. `KeyStatus`'s six cases are exhaustively handled in Task 5's
status mapping.

**One risk recorded rather than hidden:** the three `validationUrl` values are conventional but were
not re-confirmed against live provider documentation when this plan was written. Task 5 Step 7
exercises each against a real key, which is the only way to prove them. A 404 there means the URL
is wrong — the plan says to fix and report it rather than substitute silently.
