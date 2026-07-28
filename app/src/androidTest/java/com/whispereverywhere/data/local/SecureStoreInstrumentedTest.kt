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
