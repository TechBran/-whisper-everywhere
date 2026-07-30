package com.whispereverywhere.transcription.cloud

import com.whispereverywhere.audio.WavWriter
import com.whispereverywhere.net.HttpResult
import com.whispereverywhere.net.HttpTransport
import com.whispereverywhere.provider.ProviderCatalog
import com.whispereverywhere.provider.ProviderId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Base64

// --- response DTOs: every field defaulted so a missing node is "" / empty, never a throw ---
@Serializable private data class GeminiResponse(val candidates: List<GeminiCandidate> = emptyList())
@Serializable private data class GeminiCandidate(val content: GeminiContent = GeminiContent())
@Serializable private data class GeminiContent(val parts: List<GeminiPart> = emptyList())
@Serializable private data class GeminiPart(val text: String? = null)

/**
 * Gemini transcription via `generateContent` (the GA plain-key path — see the plan's "Pinned
 * Gemini facts" for why not `interactions`). One JSON POST per committed segment.
 *
 * Load-bearing details:
 *  - THE KEY RIDES THE HEADER `x-goog-api-key`. The URL NEVER carries it (no ?key=), and nothing
 *    logs a URL that could — status codes only.
 *  - Gemini is a general model, so the instruction is fixed: "Transcribe this audio verbatim…".
 *  - THE BAD-KEY STATUS IS 400, NOT 401 (the C1 trap). 400 is only INVALID_KEY when the body
 *    carries an api-key-invalid marker; a plain 400 is a BadSegment (malformed request), not an
 *    account fault.
 *  - A 200 refusal (candidate with no text part) is Transient, NOT Text("") — a refusal is not
 *    silence. A present-but-blank text IS a legitimate empty transcript.
 *  - base64 uses java.util.Base64; android.util.Base64 returns defaults under JVM tests (C1 trap).
 */
class GeminiStt(
    private val transport: HttpTransport,
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
) : SttProvider {

    override val id = ProviderId.GEMINI

    /** Raw-PCM gate. 20 MB TOTAL request cap incl. base64 (~1.333x) + envelope -> 14 MB raw is safe. */
    override val maxRequestBytes = 14L * 1024 * 1024

    override suspend fun transcribe(pcm: ByteArray, language: String?): SttResult {
        if (pcm.size.toLong() + WAV_HEADER_BYTES > maxRequestBytes) {
            return SttResult.Failed(SttError.BadSegment)
        }
        val provider = ProviderCatalog.byId(ProviderId.GEMINI)
        val headers = mapOf(provider.authHeaderName to provider.authHeaderValue(apiKey))
        val b64 = Base64.getEncoder().encodeToString(WavWriter.wrap(pcm))
        // Built with the serializer so the base64 payload and instruction are correctly escaped.
        val requestBody = JSON.encodeToString(
            GeminiRequest.serializer(),
            GeminiRequest(
                contents = listOf(
                    GeminiReqContent(
                        parts = listOf(
                            GeminiReqPart(text = INSTRUCTION),
                            GeminiReqPart(inline_data = GeminiInlineData(mime_type = "audio/wav", data = b64)),
                        )
                    )
                )
            )
        )
        val result = transport.postJson(url = ENDPOINT + model + GENERATE, headers = headers, jsonBody = requestBody)
        return when (result) {
            is HttpResult.NetworkError -> SttResult.Failed(SttError.Offline)
            is HttpResult.HttpError -> SttResult.Failed(classify(result.code, result.body))
            is HttpResult.Ok -> parse(result.body)
        }
    }

    private fun parse(body: String): SttResult = try {
        val text = JSON.decodeFromString<GeminiResponse>(body)
            .candidates.firstOrNull()?.content?.parts
            ?.firstOrNull { it.text != null }?.text
        // No text part at all (refusal / empty candidates) is Transient, not an empty transcript.
        if (text == null) SttResult.Failed(SttError.Transient(null)) else SttResult.Text(text)
    } catch (_: Throwable) {
        SttResult.Failed(SttError.Transient(null))
    }

    private fun classify(code: Int, body: String): SttError {
        android.util.Log.w("WE-DIAG", "gemini stt http $code")   // status code ONLY — never the URL
        return when (code) {
            // 400 is INVALID_KEY only with the marker; otherwise a malformed-request BadSegment.
            400 -> if (INVALID_KEY_MARKERS.any { body.contains(it, ignoreCase = true) }) {
                SttError.Fatal(FatalKind.INVALID_KEY, "Key rejected")
            } else {
                SttError.BadSegment
            }
            401 -> SttError.Fatal(FatalKind.INVALID_KEY, "Key rejected")
            403 -> SttError.Fatal(FatalKind.FORBIDDEN, "Access denied for this key")
            413 -> SttError.BadSegment
            429 -> if (QUOTA_MARKERS.any { body.contains(it, ignoreCase = true) }) {
                SttError.Fatal(FatalKind.OUT_OF_CREDIT, "Account has no remaining credit")
            } else {
                SttError.Transient(null)
            }
            in 500..599 -> SttError.Transient(null)
            else -> SttError.Transient(null)
        }
    }

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = false }
        const val DEFAULT_MODEL = "gemini-3.6-flash"
        private const val INSTRUCTION =
            "Transcribe this audio verbatim. Output only the spoken words, nothing else."
        // Key rides the x-goog-api-key header — this URL never carries it.
        private const val ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models/"
        private const val GENERATE = ":generateContent"
        private const val WAV_HEADER_BYTES = 44
        private val INVALID_KEY_MARKERS = listOf("API_KEY_INVALID", "API key not valid")
        private val QUOTA_MARKERS = listOf("RESOURCE_EXHAUSTED", "quota_exceeded", "insufficient_quota")
    }
}

// --- request DTOs (snake_case matches the REST body verbatim) ---
@Serializable private data class GeminiRequest(val contents: List<GeminiReqContent>)
@Serializable private data class GeminiReqContent(val parts: List<GeminiReqPart>)
@Serializable private data class GeminiReqPart(val text: String? = null, val inline_data: GeminiInlineData? = null)
@Serializable private data class GeminiInlineData(val mime_type: String, val data: String)
