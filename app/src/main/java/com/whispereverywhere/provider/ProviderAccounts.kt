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

    /**
     * A display stand-in for a stored key, safe to hold in UI state: derived from only the last
     * 4 characters, so even this can't reconstruct the credential. Lets the UI show "key saved"
     * without the raw decrypted value ever crossing into the UI layer for display purposes.
     */
    fun maskedKey(id: ProviderId): String? = key(id)?.let { raw ->
        if (raw.length <= 4) "•".repeat(raw.length) else "••••${raw.takeLast(4)}"
    }

    fun setKey(id: ProviderId, key: String) {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) secureStore.remove(prefKey(id)) else secureStore.put(prefKey(id), trimmed)
    }

    fun clear(id: ProviderId) = secureStore.remove(prefKey(id))

    fun configured(): Set<ProviderId> = ProviderId.entries.filter { key(it) != null }.toSet()

    private fun prefKey(id: ProviderId) = "provider_key_${id.name}"
}
