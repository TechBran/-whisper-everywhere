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
