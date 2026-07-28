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
 *
 * Key acquisition is synchronized and cached. An unsynchronised check-then-generate lets two
 * threads each generate a key, the second overwriting the first — which makes every value written
 * under the first key permanently undecryptable, with no error at write time. That was
 * unreachable while this class had no callers; it does now.
 *
 * The cache is dropped on any crypto failure, because Keystore keys are destroyed when the user
 * removes their screen lock or re-enrolls biometrics, and a cached reference to a destroyed key
 * fails forever otherwise.
 */
class SecureStore(
    private val context: Context,
    prefsName: String = PREFS_NAME,
) {
    private val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

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

    fun isAvailable(): Boolean = runCatching { secretKey() }.isSuccess

    fun put(key: String, value: String) {
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
            val ct = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            prefs.edit().putString(key, SecureStoreCodec.encode(cipher.iv, ct)).apply()
        } catch (t: Throwable) {
            // Never degrade to plaintext. Surface it.
            invalidateCachedKey()
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
            // Treat as absent — the user re-enters the credential. Also drop the cached key: a
            // stale reference to a destroyed Keystore key would otherwise fail this way forever.
            invalidateCachedKey()
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
