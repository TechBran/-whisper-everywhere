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

    @Test fun masked_key_shows_only_the_last_4_characters_and_never_the_raw_value() {
        // The UI (CloudProvidersScreen) is expected to hold ONLY this, never accounts.key(),
        // for display -- so the raw decrypted value never crosses into Compose state.
        val secret = "sk-super-secret-provider-key"
        accounts.setKey(ProviderId.OPENAI, secret)
        val masked = accounts.maskedKey(ProviderId.OPENAI)
        assertEquals("••••-key", masked)
        assertTrue("must not contain the raw key body", masked?.contains(secret.dropLast(4)) != true)
    }

    @Test fun masked_key_is_null_when_nothing_is_stored() {
        assertNull(accounts.maskedKey(ProviderId.GEMINI))
    }
}
