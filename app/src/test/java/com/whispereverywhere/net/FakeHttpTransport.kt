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
    var lastFilePart: HttpTransport.FilePart? = null
        private set
    var lastFields: Map<String, String> = emptyMap()
        private set
    var lastJsonBody: String? = null
        private set

    override suspend fun get(url: String, headers: Map<String, String>, timeoutMs: Long): HttpResult {
        lastUrl = url
        lastHeaders = headers
        callCount++
        return script(url, headers)
    }

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

    override suspend fun postJson(
        url: String,
        headers: Map<String, String>,
        jsonBody: String,
        timeoutMs: Long,
    ): HttpResult {
        lastUrl = url
        lastHeaders = headers
        lastJsonBody = jsonBody
        callCount++
        return script(url, headers)
    }
}
