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
        listOf("1:", "1::", ":::", "1:@@@:@@@", "1:AAAA", " ", "\u0000").forEach {
            SecureStoreCodec.decode(it)   // must not throw
        }
    }
}
