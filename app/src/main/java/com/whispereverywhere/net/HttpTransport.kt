package com.whispereverywhere.net

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Outcome of one HTTP call. Deliberately three cases, not a nullable success: a 401 from a
 * provider and a dead radio need completely different handling, and collapsing them into
 * "failed" is how a client ends up retrying forever against an invalid key.
 */
sealed interface HttpResult {
    data class Ok(val code: Int, val body: String) : HttpResult
    data class HttpError(val code: Int, val body: String) : HttpResult
    data class NetworkError(val cause: Throwable) : HttpResult
}

/**
 * The seam that makes every provider client unit-testable without a network. Production uses
 * [OkHttpTransport]; tests use FakeHttpTransport in the test source set.
 */
interface HttpTransport {
    suspend fun get(
        url: String,
        headers: Map<String, String>,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): HttpResult

    /**
     * A file in a multipart/form-data body. [fileName] is load-bearing for OpenAI, which infers
     * the audio format from it rather than from [contentType].
     */
    data class FilePart(
        val fieldName: String,
        val fileName: String,
        val contentType: String,
        val bytes: ByteArray,
    )

    /**
     * Upload one file plus form fields. Separate from [get] because the timeout profile is
     * completely different: a 15-second audio segment on a slow cellular link needs far longer
     * than a key-validation GET.
     */
    suspend fun postMultipart(
        url: String,
        headers: Map<String, String>,
        filePart: FilePart,
        fields: Map<String, String>,
        timeoutMs: Long = DEFAULT_UPLOAD_TIMEOUT_MS,
    ): HttpResult

    /**
     * POST a raw JSON body. Separate from [postMultipart]: Gemini's generateContent takes an
     * application/json body, not form-data. Same long upload-timeout profile — the body embeds
     * base64 audio.
     */
    suspend fun postJson(
        url: String,
        headers: Map<String, String>,
        jsonBody: String,
        timeoutMs: Long = DEFAULT_UPLOAD_TIMEOUT_MS,
    ): HttpResult

    companion object {
        const val DEFAULT_TIMEOUT_MS = 10_000L
        const val DEFAULT_UPLOAD_TIMEOUT_MS = 60_000L
    }
}

class OkHttpTransport(private val client: OkHttpClient = defaultClient()) : HttpTransport {

    override suspend fun get(url: String, headers: Map<String, String>, timeoutMs: Long): HttpResult {
        return try {
            // Request.Builder().header(k, v) MUST stay inside this try. OkHttp's
            // Headers.checkValue throws IllegalArgumentException for any header value with a
            // char outside 0x20-0x7e, and that exception's message embeds the raw value for
            // every header except Authorization/Cookie/Proxy-Authorization/Set-Cookie — so for
            // xi-api-key / x-goog-api-key a malformed paste would otherwise put the plaintext
            // API key into an uncaught crash trace. KeyValidator rejects those chars first, but
            // this must not rely on every caller doing that.
            val request = Request.Builder().url(url).apply {
                headers.forEach { (k, v) -> header(k, v) }
            }.build()
            val call = client.newBuilder()
                .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .build()
                .newCall(request)
            val response = call.await()
            // Read the body ONCE; it is a one-shot stream.
            val body = response.use { it.body?.string().orEmpty() }
            if (response.isSuccessful) HttpResult.Ok(response.code, body)
            else HttpResult.HttpError(response.code, body)
        } catch (c: kotlinx.coroutines.CancellationException) {
            // MUST be rethrown, and MUST come before the Exception catch below.
            // CancellationException extends IllegalStateException -> RuntimeException ->
            // Exception, so the broad catch would otherwise swallow it and report a cancelled
            // call as a network failure. That breaks structured concurrency: the coroutine never
            // unwinds, the caller sets a bogus "couldn't reach the provider" state, and it may
            // touch UI state belonging to a scope that has already gone away.
            throw c
        } catch (e: Exception) {
            HttpResult.NetworkError(e)
        }
    }

    override suspend fun postMultipart(
        url: String,
        headers: Map<String, String>,
        filePart: HttpTransport.FilePart,
        fields: Map<String, String>,
        timeoutMs: Long,
    ): HttpResult {
        return try {
            // Body construction stays INSIDE the try for the same reason as get(): OkHttp's
            // header validation throws IllegalArgumentException whose message embeds the raw
            // header value for every header except Authorization/Cookie/Proxy-Authorization/
            // Set-Cookie — and an uncaught throw here would put a credential in a crash trace.
            val body = MultipartBody.Builder().setType(MultipartBody.FORM).apply {
                fields.forEach { (k, v) -> addFormDataPart(k, v) }
                addFormDataPart(
                    filePart.fieldName,
                    filePart.fileName,
                    filePart.bytes.toRequestBody(filePart.contentType.toMediaType()),
                )
            }.build()
            val request = Request.Builder().url(url).post(body).apply {
                headers.forEach { (k, v) -> header(k, v) }
            }.build()
            val call = client.newBuilder()
                .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .build()
                .newCall(request)
            val response = call.await()
            val respBody = response.use { it.body?.string().orEmpty() }
            if (response.isSuccessful) HttpResult.Ok(response.code, respBody)
            else HttpResult.HttpError(response.code, respBody)
        } catch (c: kotlinx.coroutines.CancellationException) {
            // Rethrow BEFORE the broad catch — CancellationException extends
            // IllegalStateException -> RuntimeException -> Exception, so the catch below would
            // otherwise swallow it and report a cancelled upload as a network failure while the
            // coroutine never unwinds.
            throw c
        } catch (e: Exception) {
            HttpResult.NetworkError(e)
        }
    }

    override suspend fun postJson(
        url: String,
        headers: Map<String, String>,
        jsonBody: String,
        timeoutMs: Long,
    ): HttpResult {
        return try {
            // Body + headers built INSIDE the try, exactly as postMultipart/get: OkHttp's
            // Headers.checkValue embeds the raw header value in the IllegalArgumentException for
            // every header except the four it redacts — x-goog-api-key is NOT one of them, so an
            // uncaught throw here would put the API key in a crash trace.
            val body = jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder().url(url).post(body).apply {
                headers.forEach { (k, v) -> header(k, v) }
            }.build()
            val call = client.newBuilder()
                .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .build()
                .newCall(request)
            val response = call.await()
            val respBody = response.use { it.body?.string().orEmpty() }
            if (response.isSuccessful) HttpResult.Ok(response.code, respBody)
            else HttpResult.HttpError(response.code, respBody)
        } catch (c: kotlinx.coroutines.CancellationException) {
            // Rethrow FIRST — CancellationException extends Exception, so the broad catch below
            // would otherwise report a cancelled request as a network failure and never unwind.
            throw c
        } catch (e: Exception) {
            HttpResult.NetworkError(e)
        }
    }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}

/**
 * Hand-rolled rather than taking `okhttp-coroutines`, which pulls kotlinx-coroutines 1.11.0 —
 * Kotlin metadata 2.2.0, unreadable by this project's 2.0.21 compiler. (The same constraint pins
 * OkHttp itself to 4.12.0; see the dependency comment in app/build.gradle.kts.)
 *
 * The cancellation handler is load-bearing: without it a cancelled coroutine leaves the HTTP call
 * running to completion, holding a connection and — once cloud STT lands — continuing to spend
 * the user's money on a request nobody is waiting for.
 */
suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) = cont.resume(response)
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}
