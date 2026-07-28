package com.whispereverywhere.net

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
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

    companion object { const val DEFAULT_TIMEOUT_MS = 10_000L }
}

class OkHttpTransport(private val client: OkHttpClient = defaultClient()) : HttpTransport {

    override suspend fun get(url: String, headers: Map<String, String>, timeoutMs: Long): HttpResult {
        val request = Request.Builder().url(url).apply {
            headers.forEach { (k, v) -> header(k, v) }
        }.build()
        val call = client.newBuilder()
            .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()
            .newCall(request)
        return try {
            val response = call.await()
            // Read the body ONCE; it is a one-shot stream.
            val body = response.use { it.body?.string().orEmpty() }
            if (response.isSuccessful) HttpResult.Ok(response.code, body)
            else HttpResult.HttpError(response.code, body)
        } catch (io: IOException) {
            HttpResult.NetworkError(io)
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
