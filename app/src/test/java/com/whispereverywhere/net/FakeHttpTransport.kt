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
