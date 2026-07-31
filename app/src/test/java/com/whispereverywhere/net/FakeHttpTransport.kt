package com.whispereverywhere.net

/**
 * Scripted HTTP for unit tests. Records the last request so tests can assert the auth header was
 * built correctly — the single most common provider-integration bug.
 */
class FakeHttpTransport(
    private val script: (String, Map<String, String>) -> HttpResult = { _, _ ->
        error("FakeHttpTransport: no HttpResult script configured — this test only stubs postForBytes")
    },
) : HttpTransport {

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

    private val queuedBytesResults = ArrayDeque<HttpResultBytes>()

    /** Queue one [HttpResultBytes] to be returned by the next [postForBytes] call, in FIFO order. */
    fun queueBytes(result: HttpResultBytes) {
        queuedBytesResults.addLast(result)
    }

    /** Every URL a [delete] was issued against, in order. Its own channel, separate from [script]. */
    val deletedUrls = mutableListOf<String>()

    /** Result the fake returns from [delete]; overridable per test (cleanup is a 204 by default). */
    var deleteResult: HttpResult = HttpResult.Ok(204, "")

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

    override suspend fun postForBytes(
        url: String,
        headers: Map<String, String>,
        jsonBody: String,
        timeoutMs: Long,
    ): HttpResultBytes {
        lastUrl = url
        lastHeaders = headers
        lastJsonBody = jsonBody
        callCount++
        return queuedBytesResults.removeFirstOrNull()
            ?: error("FakeHttpTransport.postForBytes called with no queued HttpResultBytes — call queueBytes() first")
    }

    override suspend fun delete(url: String, headers: Map<String, String>, timeoutMs: Long): HttpResult {
        // Deliberately does NOT touch lastUrl/lastHeaders/callCount: a test asserts on the poll or
        // fetch it made, and cleanup running afterward must not overwrite those. Deletes are their
        // own observation channel (deletedUrls), distinct from the URL-dispatched script — so a
        // poll-GET and a cleanup-DELETE on the same /transcriptions/{id} URL stay distinguishable.
        deletedUrls.add(url)
        return deleteResult
    }
}
