# Release B — Credentials + Toolchain Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the plaintext-API-key leak, replace the deprecated credential store with direct Android Keystore, and raise the toolchain to `targetSdk 36` before Google Play's 31 August 2026 deadline blocks all updates.

**Architecture:** A new `SecureStore` (Keystore-wrapped AES-256-GCM over a plain SharedPreferences blob) replaces `EncryptedSharedPreferences`. It fails loudly rather than silently degrading to plaintext. Backup rules move from a deny-one list to an explicit allowlist. The toolchain bump is mechanical but gates OkHttp, which Release C needs.

**Tech Stack:** Kotlin 2.0.21, AGP 8.13.2, Gradle 8.14.4, `compileSdk`/`targetSdk` 36, `minSdk` 26, Android Keystore (`AndroidKeyStore` provider), JUnit 4 + Robolectric-free JVM tests for the pure parts.

## Global Constraints

- **Unit tests run with `unitTests.isReturnDefaultValues = true`** (`app/build.gradle.kts:166`).
  Any `android.*` call in JVM-unit-tested code returns a type default (null / 0 / false) instead
  of throwing, so a broken dependency on an Android API can PASS silently or fail confusingly.
  Keep JVM-unit-tested classes free of `android.*` — every existing one in this repo is.
- **`java` is NOT on PATH.** In PowerShell, before any gradle command:
  `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio1\jbr"` (JDK 21, verified working).
  Use `.\gradlew.bat`, not `./gradlew`. Always pass `--no-daemon`.
- **Baseline to preserve: 15 suites / 88 tests / 0 failures.** Any task that lowers this has broken something.
- **Kotlin 2.0.21 caps dependency versions.** A 2.0.x compiler reads metadata at most one minor ahead (2.1.0). **Unusable:** Ktor 3.5.1, kotlinx-serialization-json ≥1.9.0, kotlinx-coroutines 1.11.0, `okhttp-coroutines` 5.4.0. **Safe:** serialization-json 1.8.1, coroutines 1.10.2, OkHttp 5.4.0.
- **Never write a credential to plaintext storage, and never silently fall back to it.** A store that cannot encrypt must fail and surface the failure.
- **No credential may ever reach logcat.** `WE-DIAG` and `WE-TTS` log liberally; any new logging near the store must be redacted by construction.
- **Do not touch `TtsEngine.kt`, `TtsDiag.kt`, or `TtsDiagMath.kt`** — Release 0 work, already reviewed and measured against.
- **Device safety:** before any `installDebug` or `connectedAndroidTest`, run the signature preflight from `docs/superpowers/plans/2026-07-27-tts-diagnostics-release-0.md`. `connectedAndroidTest` **uninstalls the app on teardown** and will destroy the user's 500+ MB of downloaded models.

---

## Why this release is time-critical

Google Play Console, notification dated 21 July 2026:

> **Action by Aug 31** — "Update your target API level by August 31, 2026 to release updates to your app… From August 31, 2026, if your target API level is not within 1 year of the latest Android release, you won't be able to update your app."

The app is on `targetSdk 35` and the warning is live, so 35 is no longer sufficient. **After 31 August 2026 no update ships at all** — including the cloud work, the TTS splitter, and the credential fix below. Task 1 therefore comes first even though Task 2 is the higher-severity defect.

**Note:** this notice concerns the *target API level* only. There is no Play Billing Library requirement here — the app has no billing dependency (verified: no `com.android.billingclient` in `app/build.gradle.kts`).

---

## File Structure

| File | Responsibility |
|---|---|
| `build.gradle.kts` | **Modify.** AGP 8.7.3 → 8.13.2 |
| `app/build.gradle.kts` | **Modify.** compileSdk/targetSdk 35 → 36; drop `androidx.security:security-crypto` |
| `app/src/main/java/com/whispereverywhere/data/local/SecureStore.kt` | **Create.** Keystore AES-256-GCM wrap/unwrap over a SharedPreferences blob. Fails loudly. |
| `app/src/main/java/com/whispereverywhere/data/local/SecureStoreCodec.kt` | **Create.** Pure IV+ciphertext framing/parsing. No Android. JVM-testable. |
| `app/src/test/java/com/whispereverywhere/data/local/SecureStoreCodecTest.kt` | **Create.** JVM unit tests for the framing. |
| `app/src/main/java/com/whispereverywhere/data/local/PreferencesManager.kt` | **Modify.** Replace `encryptedPrefs` with `SecureStore`; purge legacy files; delete the plaintext fallback path. |
| `app/src/androidTest/java/com/whispereverywhere/data/local/SecureStoreInstrumentedTest.kt` | **Create.** Round-trip + legacy-purge on a real Keystore. |
| `app/src/main/res/xml/backup_rules.xml` | **Modify.** Deny-one → explicit allowlist. |
| `app/src/main/res/xml/data_extraction_rules.xml` | **Modify.** Same. |

---

## Task 1: Toolchain bump to targetSdk 36

**Files:**
- Modify: `build.gradle.kts:3`
- Modify: `app/build.gradle.kts:22`, `:39`

**Interfaces:**
- Consumes: nothing.
- Produces: a `compileSdk 36` project, which is the precondition for OkHttp 5.4.0 in Release C (`okhttp-android-5.4.0.aar` declares `minCompileSdk=36`).

- [ ] **Step 1: Record the pre-change baseline**

Run:
```bash
.\gradlew.bat :app:testDebugUnitTest --no-daemon --rerun-tasks
```
Expected: BUILD SUCCESSFUL. Then confirm the count:
```bash
cd <buildDir>/app/test-results/testDebugUnitTest
grep -hoE 'tests="[0-9]+" skipped="[0-9]+" failures="[0-9]+" errors="[0-9]+"' TEST-*.xml \
  | awk -F'"' '{t+=$2;s+=$4;f+=$6;e+=$8} END{print "tests="t" skipped="s" failures="f" errors="e}'
```
Expected: `tests=88 skipped=0 failures=0 errors=0`. Record this — it is the regression bar.

- [ ] **Step 2: Bump AGP**

In `build.gradle.kts` line 3, change:
```kotlin
    id("com.android.application") version "8.7.3" apply false
```
to:
```kotlin
    id("com.android.application") version "8.13.2" apply false
```

Leave Kotlin at `2.0.21` and the Gradle wrapper at `8.14.4` — both are compatible and changing them widens the blast radius for no benefit.

- [ ] **Step 3: Bump the SDK levels**

In `app/build.gradle.kts`, line 22:
```kotlin
    compileSdk = 36
```
and line 39:
```kotlin
        targetSdk = 36
```

Leave `minSdk = 26` unchanged.

- [ ] **Step 4: Build and fix what breaks**

Run:
```bash
.\gradlew.bat :app:assembleDebug --no-daemon
```

If it fails, the likely causes in order:
1. **A new lint/API error from `compileSdk 36`.** Read the actual message; do not blanket-suppress.
2. **A dependency that needs a matching bump.** Report it rather than guessing at versions — Kotlin 2.0.21's metadata ceiling (see Global Constraints) makes naive upgrades hazardous.
3. **A `targetSdk 36` behaviour change.** Android 16 tightens foreground-service types and predictive back. This app declares `mediaProjection`, `microphone`, and `specialUse` foreground services in `AndroidManifest.xml` — if the build or a runtime check complains about FGS types, STOP and report; that is a behaviour change, not a version bump, and needs its own decision.

If the build succeeds with no source changes, say so explicitly in your report — that is the expected outcome and worth recording.

- [ ] **Step 5: Verify no test regression**

Run:
```bash
.\gradlew.bat :app:testDebugUnitTest --no-daemon --rerun-tasks
```
Expected: still `tests=88 skipped=0 failures=0 errors=0`.

- [ ] **Step 6: Verify the release build still assembles**

The release build has `isMinifyEnabled = true` and aggressive R8 rules, and the project has a history of release-only crashes (commit `b19233c`). A debug build passing proves little.

Run:
```bash
.\gradlew.bat :app:assembleRelease --no-daemon
```
Expected: BUILD SUCCESSFUL. If R8 fails, report the exact rule and class — do not add blanket `-keep` rules.

- [ ] **Step 7: Commit**

```bash
git add build.gradle.kts app/build.gradle.kts
git commit -m "build: targetSdk 36 + AGP 8.13.2 — Play deadline 31 Aug 2026

Play Console warns that from 31 August 2026 an app whose target API level
is not within 1 year of the latest Android release cannot ship updates at
all. The app was on targetSdk 35 with the warning already live.

Also the precondition for OkHttp 5.4.0 in Release C:
okhttp-android-5.4.0.aar declares minCompileSdk=36, and AGP <=8.9 caps at
API 35. Kotlin stays at 2.0.21 and the Gradle wrapper at 8.14.4."
```

---

## Task 2: `SecureStoreCodec` — pure IV + ciphertext framing

The encrypted blob needs a self-describing on-disk format. Keeping the framing pure makes the format testable without a device and without a Keystore.

**Files:**
- Create: `app/src/main/java/com/whispereverywhere/data/local/SecureStoreCodec.kt`
- Test: `app/src/test/java/com/whispereverywhere/data/local/SecureStoreCodecTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces, used by Task 3:
  - `SecureStoreCodec.encode(iv: ByteArray, ciphertext: ByteArray): String`
  - `SecureStoreCodec.decode(blob: String): SecureStoreCodec.Framed?` where
    `data class Framed(val version: Int, val iv: ByteArray, val ciphertext: ByteArray)`
  - `SecureStoreCodec.VERSION: Int` = 1
  - `SecureStoreCodec.GCM_IV_BYTES: Int` = 12

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/whispereverywhere/data/local/SecureStoreCodecTest.kt`:

```kotlin
package com.whispereverywhere.data.local

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SecureStoreCodecTest {

    private fun iv() = ByteArray(SecureStoreCodec.GCM_IV_BYTES) { it.toByte() }

    @Test fun round_trips_iv_and_ciphertext() {
        val ct = byteArrayOf(9, 8, 7, 6, 5)
        val framed = SecureStoreCodec.decode(SecureStoreCodec.encode(iv(), ct))
        assertEquals(SecureStoreCodec.VERSION, framed!!.version)
        assertArrayEquals(iv(), framed.iv)
        assertArrayEquals(ct, framed.ciphertext)
    }

    @Test fun round_trips_an_empty_ciphertext() {
        // An empty stored value is legal — it must not be confused with "absent".
        val framed = SecureStoreCodec.decode(SecureStoreCodec.encode(iv(), ByteArray(0)))
        assertEquals(0, framed!!.ciphertext.size)
    }

    @Test fun encoded_blob_is_ascii_safe_for_sharedpreferences() {
        // SharedPreferences stores XML; raw bytes would corrupt the file.
        val blob = SecureStoreCodec.encode(iv(), byteArrayOf(-1, 0, 127, -128))
        assertEquals(blob, blob.filter { it.code in 32..126 })
    }

    @Test fun decode_returns_null_on_garbage() {
        assertNull(SecureStoreCodec.decode("not-a-blob"))
        assertNull(SecureStoreCodec.decode(""))
    }

    @Test fun decode_returns_null_on_unknown_version() {
        // A future version must be treated as unreadable, never guessed at.
        val good = SecureStoreCodec.encode(iv(), byteArrayOf(1, 2, 3))
        val bumped = good.replaceFirst("${SecureStoreCodec.VERSION}:", "99:")
        assertNull(SecureStoreCodec.decode(bumped))
    }

    @Test fun decode_returns_null_on_wrong_iv_length() {
        // A truncated IV would otherwise be passed to GCMParameterSpec and throw at runtime.
        val shortIv = ByteArray(4) { 1 }
        assertNull(SecureStoreCodec.decode(SecureStoreCodec.encode(shortIv, byteArrayOf(1))))
    }

    @Test fun decode_never_throws_on_arbitrary_input() {
        // Corrupted prefs must degrade to "no value", never crash app start.
        listOf("1:", "1::", ":::", "1:@@@:@@@", "1:AAAA", " ").forEach {
            SecureStoreCodec.decode(it)   // must not throw
        }
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
.\gradlew.bat :app:testDebugUnitTest --no-daemon --tests "com.whispereverywhere.data.local.SecureStoreCodecTest"
```
Expected: FAIL — `Unresolved reference: SecureStoreCodec`.

- [ ] **Step 3: Write the implementation**

Create `app/src/main/java/com/whispereverywhere/data/local/SecureStoreCodec.kt`:

```kotlin
package com.whispereverywhere.data.local

import java.util.Base64

/**
 * On-disk framing for [SecureStore]: `"<version>:<base64 iv>:<base64 ciphertext>"`.
 *
 * Deliberately self-describing and version-prefixed. A blob written by a future version must be
 * REJECTED rather than reinterpreted — silently misreading a credential blob is worse than
 * reporting it unreadable, because the failure would surface as a mysterious auth error rather
 * than a storage error.
 *
 * [decode] is total: any malformed, truncated, or corrupted input yields null. Credential storage
 * sits on the app-start path, so a parse failure must degrade to "no value", never crash.
 *
 * Uses java.util.Base64, NOT android.util.Base64, for a load-bearing reason: this project sets
 * `unitTests.isReturnDefaultValues = true` (app/build.gradle.kts:166), so android.util.Base64
 * would return NULL under plain JVM unit tests rather than throwing — encode() would silently
 * produce the literal string "1:null:null" and the tests would fail confusingly. java.util.Base64
 * is available from API 26, which is exactly this app's minSdk, and the wire format is identical.
 * Keeping this file free of android.* also matches every other unit-tested class in the repo.
 */
object SecureStoreCodec {

    const val VERSION = 1

    /** AES-GCM standard IV length. A different length is a corrupt blob, not a variant. */
    const val GCM_IV_BYTES = 12

    data class Framed(val version: Int, val iv: ByteArray, val ciphertext: ByteArray)

    private val encoder: Base64.Encoder = Base64.getEncoder().withoutPadding()
    private val decoder: Base64.Decoder = Base64.getDecoder()

    fun encode(iv: ByteArray, ciphertext: ByteArray): String =
        "$VERSION:${encoder.encodeToString(iv)}:${encoder.encodeToString(ciphertext)}"

    fun decode(blob: String): Framed? {
        val parts = blob.split(':')
        if (parts.size != 3) return null
        val version = parts[0].toIntOrNull() ?: return null
        if (version != VERSION) return null
        return try {
            val iv = decoder.decode(parts[1])
            if (iv.size != GCM_IV_BYTES) return null
            Framed(version, iv, decoder.decode(parts[2]))
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
```

Note `Base64.getEncoder()` is the standard (non-URL-safe) alphabet, which can emit `+` and `/`.
Both are ASCII-printable and legal in SharedPreferences XML, so the ASCII-safety test still holds.
`=` padding is disabled so the blob cannot be confused by trailing-padding differences.

- [ ] **Step 4: Run the test to verify it passes**

```bash
.\gradlew.bat :app:testDebugUnitTest --no-daemon --tests "com.whispereverywhere.data.local.SecureStoreCodecTest"
```
Expected: PASS, 7 tests.

There is no `android.*` import in this file by design (see the KDoc), so it runs on a plain JVM
exactly like every other unit-tested class in this repo. If it somehow does not, STOP and report
rather than reaching for `unitTests.isReturnDefaultValues` behaviour — that setting is already
`true` at `app/build.gradle.kts:166` and is precisely what would mask a broken codec.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/whispereverywhere/data/local/SecureStoreCodec.kt \
        app/src/test/java/com/whispereverywhere/data/local/SecureStoreCodecTest.kt
git commit -m "feat(security): SecureStoreCodec — versioned IV+ciphertext framing

Self-describing and version-prefixed so a blob from a future version is
rejected rather than reinterpreted. decode() is total: corrupted prefs
degrade to 'no value' instead of crashing app start."
```

---

## Task 3: `SecureStore` — Keystore AES-256-GCM, no silent plaintext fallback

**Files:**
- Create: `app/src/main/java/com/whispereverywhere/data/local/SecureStore.kt`
- Test: `app/src/androidTest/java/com/whispereverywhere/data/local/SecureStoreInstrumentedTest.kt`

**Interfaces:**
- Consumes: `SecureStoreCodec` (Task 2).
- Produces, used by Task 4:
  - `class SecureStore(context: Context, prefsName: String = "secure_store")`
  - `fun put(key: String, value: String)` — throws `SecureStoreException` on failure
  - `fun get(key: String): String?` — returns null if absent or undecryptable
  - `fun remove(key: String)`
  - `fun isAvailable(): Boolean`
  - `class SecureStoreException(message: String, cause: Throwable?) : Exception(message, cause)`

- [ ] **Step 1: Write the implementation**

TDD note: the Keystore is not available under plain JVM unit tests, so this class is verified by the instrumented test in Step 2 rather than a red-green JVM cycle. Write the implementation first, then the instrumented test — and say so plainly in your report rather than claiming a TDD cycle you did not run.

Create `app/src/main/java/com/whispereverywhere/data/local/SecureStore.kt`:

```kotlin
package com.whispereverywhere.data.local

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureStoreException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Credential storage backed directly by the Android Keystore (AES-256-GCM), replacing
 * androidx.security:security-crypto — which shipped stable and fully deprecated simultaneously
 * (1.1.0, 2025-07-30) with no migration guide and no Jetpack successor.
 *
 * DESIGN RULE, and the reason this class exists: it NEVER falls back to plaintext. The previous
 * implementation caught its own initialisation failure and silently wrote to a MODE_PRIVATE file
 * named "encrypted_api_key_fallback" — a raw credential in a file whose name claims otherwise,
 * eligible for Google Drive backup. A store that cannot encrypt must fail loudly so the caller
 * can tell the user, not quietly downgrade the guarantee it advertises.
 *
 * setUserAuthenticationRequired is deliberately NOT set: it would make the key unobtainable while
 * the screen is locked, which breaks background dictation — the app's core use case.
 */
class SecureStore(
    private val context: Context,
    prefsName: String = PREFS_NAME,
) {
    private val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
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

    fun isAvailable(): Boolean = runCatching { secretKey() }.isSuccess

    fun put(key: String, value: String) {
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
            val ct = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            prefs.edit().putString(key, SecureStoreCodec.encode(cipher.iv, ct)).apply()
        } catch (t: Throwable) {
            // Never degrade to plaintext. Surface it.
            throw SecureStoreException("Secure storage unavailable; value not saved", t)
        }
    }

    fun get(key: String): String? {
        val framed = SecureStoreCodec.decode(prefs.getString(key, null) ?: return null) ?: return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, framed.iv))
            }
            String(cipher.doFinal(framed.ciphertext), Charsets.UTF_8)
        } catch (_: Throwable) {
            // Key invalidated (screen lock removed, biometrics re-enrolled) or blob corrupt.
            // Treat as absent — the user re-enters the credential.
            null
        }
    }

    fun remove(key: String) { prefs.edit().remove(key).apply() }

    companion object {
        const val PREFS_NAME = "secure_store"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "whisper_everywhere_secure_store"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
    }
}
```

- [ ] **Step 2: Write the instrumented test**

Create `app/src/androidTest/java/com/whispereverywhere/data/local/SecureStoreInstrumentedTest.kt`:

```kotlin
package com.whispereverywhere.data.local

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureStoreInstrumentedTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = SecureStore(ctx, prefsName = "secure_store_test")

    @After fun cleanUp() {
        ctx.getSharedPreferences("secure_store_test", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test fun keystore_is_available_on_this_device() {
        assertTrue(store.isAvailable())
    }

    @Test fun round_trips_a_credential() {
        store.put("k", "sk-test-value-123")
        assertEquals("sk-test-value-123", store.get("k"))
    }

    @Test fun absent_key_returns_null() {
        assertNull(store.get("never-written"))
    }

    @Test fun removed_key_returns_null() {
        store.put("k", "value")
        store.remove("k")
        assertNull(store.get("k"))
    }

    @Test fun stored_blob_does_not_contain_the_plaintext() {
        // The actual regression guard: whatever lands on disk must not be the credential.
        val secret = "sk-super-secret-value"
        store.put("k", secret)
        val raw = ctx.getSharedPreferences("secure_store_test", android.content.Context.MODE_PRIVATE)
            .getString("k", "") ?: ""
        assertTrue("raw blob must not contain the plaintext", !raw.contains(secret))
        assertTrue("raw blob must be non-empty", raw.isNotEmpty())
    }

    @Test fun corrupt_blob_reads_as_absent_rather_than_throwing() {
        ctx.getSharedPreferences("secure_store_test", android.content.Context.MODE_PRIVATE)
            .edit().putString("k", "1:AAAA:BBBB").commit()
        assertNull(store.get("k"))
    }
}
```

- [ ] **Step 3: Compile the instrumented test**

**Do NOT run `connectedAndroidTest`** — it uninstalls the app on teardown and would destroy the user's downloaded models. Verify compilation only:

```bash
.\gradlew.bat :app:compileDebugAndroidTestKotlin --no-daemon
```
Expected: BUILD SUCCESSFUL. Record the instrumented run as DEFERRED; the controller will schedule it when a wipe is acceptable.

- [ ] **Step 4: Confirm no unit-test regression**

```bash
.\gradlew.bat :app:testDebugUnitTest --no-daemon
```
Expected: 95 tests (88 + 7 from Task 2), 0 failures.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/whispereverywhere/data/local/SecureStore.kt \
        app/src/androidTest/java/com/whispereverywhere/data/local/SecureStoreInstrumentedTest.kt
git commit -m "feat(security): SecureStore — Keystore AES-256-GCM, never plaintext

Replaces androidx.security:security-crypto, which shipped stable and
fully deprecated simultaneously (1.1.0, 2025-07-30) with no successor.

The design rule is the point: this store NEVER falls back to plaintext.
The previous implementation caught its own init failure and silently
wrote to a MODE_PRIVATE file named 'encrypted_api_key_fallback' — a raw
credential in a file whose name claims otherwise. A store that cannot
encrypt now throws so the caller can tell the user.

setUserAuthenticationRequired is deliberately not set: it would make the
key unobtainable while the screen is locked, breaking background
dictation."
```

---

## Task 4: Migrate `PreferencesManager` and purge the legacy plaintext files

This is the task that actually closes the leak. **The purge matters as much as the migration**: a user who ran the 2.x cloud-era build may still have a real API key sitting in `encrypted_api_key_fallback` in plaintext right now, and nothing has ever deleted it.

**Files:**
- Modify: `app/src/main/java/com/whispereverywhere/data/local/PreferencesManager.kt` — lines 16-54 (the `masterKey`/`encryptedPrefs`/`createEncryptedPrefs` block) and 73-79 (the `apiKey` property)
- Modify: `app/build.gradle.kts:228` — remove the `androidx.security:security-crypto` dependency

**Interfaces:**
- Consumes: `SecureStore` (Task 3).
- Produces: `PreferencesManager.apiKey` backed by `SecureStore`; `PreferencesManager.secureStorageAvailable(): Boolean` for Release C's UI to gate the key-entry field.

- [ ] **Step 1: Read the current code**

Open `PreferencesManager.kt` and confirm:
- lines 16-20: `masterKey` via `MasterKey.Builder`
- lines 24-44: `encryptedPrefs` with the two-level `try`/`catch` whose innermost branch (line 41) returns `context.getSharedPreferences("encrypted_api_key_fallback", Context.MODE_PRIVATE)`
- lines 46-54: `createEncryptedPrefs()`
- lines 73-79: `var apiKey` and `fun hasApiKey()`
- line 184: `private const val KEY_API_KEY = "openai_api_key"`

Also confirm with `grep -rn "getApiKey\|setApiKey\|openai_api_key" app/src --include=*.kt` that **nothing outside this file reads or writes the key** — it is currently dead code left from the cloud era. That is why this migration has no downstream consumers to update.

- [ ] **Step 2: Replace the store**

Delete the `masterKey` property (16-20), the `encryptedPrefs` property (24-44), and `createEncryptedPrefs()` (46-54). Remove the now-unused imports `androidx.security.crypto.EncryptedSharedPreferences`, `androidx.security.crypto.MasterKey`, `android.os.Build`, and `java.io.File` **only if** nothing else in the file uses them — check first.

Add in their place:

```kotlin
    private val secureStore = SecureStore(context)

    /** True when the Keystore is usable. False means credentials cannot be stored at all. */
    fun secureStorageAvailable(): Boolean = secureStore.isAvailable()
```

- [ ] **Step 3: Rewrite the `apiKey` property**

Replace lines 73-79 with:

```kotlin
    /**
     * The user's own provider credential. Backed by [SecureStore] (Keystore AES-256-GCM).
     *
     * The setter THROWS [SecureStoreException] when secure storage is unavailable. That is
     * deliberate: the previous implementation swallowed the failure and wrote plaintext. Callers
     * must surface the error to the user rather than pretending the key was saved.
     */
    var apiKey: String
        get() = secureStore.get(KEY_API_KEY) ?: ""
        set(value) {
            if (value.isEmpty()) secureStore.remove(KEY_API_KEY) else secureStore.put(KEY_API_KEY, value)
        }

    fun hasApiKey(): Boolean = apiKey.isNotBlank()
```

- [ ] **Step 4: Add the legacy purge**

Add this method and call it from `init { }` at the top of the class body:

```kotlin
    /**
     * One-time cleanup of the pre-3.3 credential stores.
     *
     * "encrypted_api_key_fallback" is the dangerous one: it was a MODE_PRIVATE PLAINTEXT file
     * written whenever EncryptedSharedPreferences failed to initialise twice, and both backup
     * rule files excluded only "encrypted_api_key.xml" — so a raw key in it was eligible for
     * Google Drive backup and device-to-device transfer. Users who ran the 2.x cloud-era build
     * may still have a real key sitting there. Delete both files unconditionally; the migration
     * is one-way and a lost key is re-enterable, whereas a leaked one is not retractable.
     */
    private fun purgeLegacyCredentialStores() {
        if (prefs.getBoolean(KEY_LEGACY_PURGED, false)) return
        runCatching { context.deleteSharedPreferences("encrypted_api_key") }
        runCatching { context.deleteSharedPreferences("encrypted_api_key_fallback") }
        prefs.edit().putBoolean(KEY_LEGACY_PURGED, true).apply()
    }
```

and add to the companion object beside `KEY_API_KEY`:

```kotlin
        private const val KEY_LEGACY_PURGED = "legacy_credential_stores_purged_v1"
```

`context.deleteSharedPreferences` requires API 24; `minSdk` is 26, so no version guard is needed.

**Deliberate decision to record in your report:** this purge does NOT migrate an existing key into the new store. Reading the legacy value would mean instantiating `EncryptedSharedPreferences` one final time — reintroducing the dependency this release removes — and the only key that could be there is a cloud-era credential for a feature the app no longer has. Deleting is correct; re-entry is one paste.

- [ ] **Step 5: Drop the dependency**

In `app/build.gradle.kts`, delete line 228:
```kotlin
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
```

- [ ] **Step 6: Verify**

```bash
.\gradlew.bat :app:assembleDebug --no-daemon
.\gradlew.bat :app:testDebugUnitTest --no-daemon
.\gradlew.bat :app:assembleRelease --no-daemon
```
Expected: all BUILD SUCCESSFUL; tests still 95, 0 failures.

Then prove the dependency is gone:
```bash
grep -rn "security-crypto\|EncryptedSharedPreferences\|MasterKey" app/build.gradle.kts app/src/main --include=* || echo "OK: no security-crypto references remain"
```
Expected: `OK: no security-crypto references remain`.

And prove the plaintext path is gone:
```bash
grep -rn "encrypted_api_key_fallback" app/src/main --include=*.kt
```
Expected: exactly one hit — inside `purgeLegacyCredentialStores`, which deletes it.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/whispereverywhere/data/local/PreferencesManager.kt app/build.gradle.kts
git commit -m "fix(security): remove the plaintext API-key fallback; purge legacy stores

PreferencesManager silently wrote credentials to a MODE_PRIVATE plaintext
file named 'encrypted_api_key_fallback' whenever EncryptedSharedPreferences
failed to init twice. Both backup rule files excluded only
'encrypted_api_key.xml', so a raw key there was eligible for Google Drive
backup and device-to-device transfer.

Replaces it with SecureStore (Keystore AES-256-GCM), which throws rather
than degrading. Adds a one-time purge of BOTH legacy files — users who ran
the 2.x cloud-era build may still have a real key on disk today. The purge
does not migrate the old value: reading it would mean instantiating
EncryptedSharedPreferences one final time, reintroducing the dependency
this release removes, and the only possible key is a cloud-era credential
for a feature the app no longer has. Re-entry is one paste.

Drops androidx.security:security-crypto (pinned at a pre-deprecation
alpha, 1.1.0-alpha06)."
```

---

## Task 5: Backup rules — deny-one to explicit allowlist

**Files:**
- Modify: `app/src/main/res/xml/backup_rules.xml`
- Modify: `app/src/main/res/xml/data_extraction_rules.xml`

**Interfaces:**
- Consumes: nothing.
- Produces: nothing consumed by later tasks.

- [ ] **Step 1: Understand the current defect**

Both files currently `<include domain="sharedpref" path="."/>` — *everything* — and then exclude exactly one filename. Anything added later is backed up by default, which is how the plaintext fallback file became Drive-eligible. Invert it: name what may leave the device.

- [ ] **Step 2: Rewrite `backup_rules.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<!--
  ALLOWLIST, not a denylist. The previous version included all of `sharedpref` and excluded a
  single filename, so any prefs file added later was backed up by default — which is how a
  plaintext credential file became eligible for Google Drive backup.

  Name only what is safe to leave the device. Anything not listed here stays local.
  Never add `secure_store` (Keystore-wrapped credentials) or a broad `<include domain="file">`.
-->
<full-backup-content>
    <include domain="sharedpref" path="whisper_everywhere_prefs.xml" />
    <exclude domain="sharedpref" path="secure_store.xml" />
    <exclude domain="file" path="." />
    <exclude domain="database" path="." />
</full-backup-content>
```

- [ ] **Step 3: Rewrite `data_extraction_rules.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<!--
  Same allowlist discipline as backup_rules.xml, for both cloud backup and device-to-device
  transfer. Credentials must not travel by either route.
-->
<data-extraction-rules>
    <cloud-backup>
        <include domain="sharedpref" path="whisper_everywhere_prefs.xml" />
        <exclude domain="sharedpref" path="secure_store.xml" />
        <exclude domain="file" path="." />
        <exclude domain="database" path="." />
    </cloud-backup>
    <device-transfer>
        <include domain="sharedpref" path="whisper_everywhere_prefs.xml" />
        <exclude domain="sharedpref" path="secure_store.xml" />
        <exclude domain="file" path="." />
        <exclude domain="database" path="." />
    </device-transfer>
</data-extraction-rules>
```

The explicit `<exclude domain="file" path="."/>` matters beyond credentials: it also keeps the 190 MB whisper model and the 325 MB Kokoro voice out of the user's Drive quota, and pre-emptively excludes the session-audio archive planned for Release D.

- [ ] **Step 4: Verify the manifest still references both files**

```bash
grep -nE "fullBackupContent|dataExtractionRules|allowBackup" app/src/main/AndroidManifest.xml
```
Both must still be wired up. If `android:allowBackup` is `false`, note it in your report — the rules are then belt-and-braces rather than load-bearing, which is worth knowing but not a reason to skip them.

- [ ] **Step 5: Build and commit**

```bash
.\gradlew.bat :app:assembleDebug --no-daemon
```

```bash
git add app/src/main/res/xml/backup_rules.xml app/src/main/res/xml/data_extraction_rules.xml
git commit -m "fix(security): backup rules become an explicit allowlist

Both files included all of sharedpref and excluded a single filename, so
any prefs file added later was backed up by default — the mechanism by
which a plaintext credential file became Drive- and D2D-eligible.

Now names only what may leave the device. Also excludes domain=file
entirely, which keeps the 190 MB whisper model and 325 MB Kokoro voice
out of the user's Drive quota and pre-excludes the Release D session-audio
archive."
```

---

## Self-Review

**Spec coverage** (spec §3.3, §3.5, §7.1, §7.2, §9 Release B):

| Spec requirement | Task |
|---|---|
| AGP 8.7.3 → 8.13.2, compileSdk/targetSdk 35 → 36 | Task 1 |
| Kotlin stays 2.0.21, wrapper stays 8.14.4 | Task 1 Step 2 |
| Delete the plaintext fallback path | Task 4 Step 2 |
| Never silently degrade to plaintext | Task 3 (`SecureStore.put` throws) |
| Replace `EncryptedSharedPreferences` with direct Keystore AES-256-GCM | Task 3 |
| Drop `androidx.security:security-crypto` | Task 4 Step 5 |
| Delete the legacy file on migration (may already be in users' backups) | Task 4 Step 4 |
| Backup rules → explicit allowlist | Task 5 |
| `setUserAuthenticationRequired` NOT set (would break background dictation) | Task 3 Step 1, documented in KDoc |
| Logcat redaction rule before the first cloud request | **Deferred to Release C** — no credential is logged today because nothing outside `PreferencesManager` touches the key (verified). The rule belongs with the code that builds authenticated requests. |
| `ProviderAccount` per-provider record, keyed by enum name | **Release C** — this release fixes the store; the multi-provider record shape lands with the providers that need it |
| OkHttp 5.4.0 + R8 rules | **Release C** — Task 1 here only establishes the `compileSdk 36` precondition |

**Placeholder scan:** none. Every code step contains complete code. Task 3 Step 1 states plainly that it is not a red-green TDD cycle and why, rather than pretending otherwise.

**Type consistency:** `SecureStoreCodec.encode(iv, ciphertext): String` / `decode(blob): Framed?` are used identically in Task 2's tests and Task 3's `SecureStore`. `SecureStore.put/get/remove/isAvailable` signatures in Task 3 match every call site in Task 4. `KEY_API_KEY` remains `"openai_api_key"` (unchanged) so the new store's key name is stable; `KEY_LEGACY_PURGED` is new and namespaced `_v1` so a future purge can re-run.

**One risk called out rather than hidden:** Keystore keys are dropped when the user removes their screen lock or re-enrolls biometrics. `SecureStore.get` returns null in that case and the user re-enters the credential. That is the correct behaviour for a user-supplied key, but it must be surfaced in Release C's UI as "your key needs re-entering" rather than a silent auth failure — recorded here so it is not discovered in the field.
