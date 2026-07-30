package com.whispereverywhere.transcription.cloud

import com.whispereverywhere.audio.WavWriter
import com.whispereverywhere.net.HttpResult
import com.whispereverywhere.net.HttpTransport
import com.whispereverywhere.provider.ProviderCatalog
import com.whispereverywhere.provider.ProviderId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** ElevenLabs returns the transcript in `.text`. Defaulted so a missing field is "" not a throw. */
@Serializable
private data class ElevenLabsTranscription(val text: String = "")

/**
 * ElevenLabs speech-to-text. One multipart POST per committed segment, mirroring [OpenAiStt].
 *
 *  - The audio is WAV-wrapped (the `file` part carries a real container).
 *  - Auth is the bare `xi-api-key` header (no Bearer), from the catalog.
 *  - 422 is a validation error for THIS segment (BadSegment), NOT an account fault.
 *  - 429 splits: a quota marker in the body is exhausted credit (Fatal), plain 429 is Transient.
 *
 * Never log the key, the headers, or the transcript — status codes only.
 */
class ElevenLabsStt(
    private val transport: HttpTransport,
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
) : SttProvider {

    override val id = ProviderId.ELEVENLABS

    /** 5 GB provider cap; never reached at ~480 KB segment sizes. Still enforced before upload. */
    override val maxRequestBytes = 5L * 1024 * 1024 * 1024

    override suspend fun transcribe(pcm: ByteArray, language: String?): SttResult {
        if (pcm.size.toLong() + WAV_HEADER_BYTES > maxRequestBytes) {
            return SttResult.Failed(SttError.BadSegment)
        }
        val provider = ProviderCatalog.byId(ProviderId.ELEVENLABS)
        val headers = mapOf(provider.authHeaderName to provider.authHeaderValue(apiKey))
        val fields = buildMap {
            put("model_id", model)
            if (!language.isNullOrBlank() && language != "auto") put("language_code", language)
        }
        val result = transport.postMultipart(
            url = ENDPOINT,
            headers = headers,
            filePart = HttpTransport.FilePart(
                fieldName = "file",
                fileName = "audio.wav",
                contentType = "audio/wav",
                bytes = WavWriter.wrap(pcm),
            ),
            fields = fields,
        )
        return when (result) {
            is HttpResult.NetworkError -> SttResult.Failed(SttError.Offline)
            is HttpResult.HttpError -> SttResult.Failed(classify(result.code, result.body))
            is HttpResult.Ok -> parse(result.body)
        }
    }

    private fun parse(body: String): SttResult = try {
        SttResult.Text(JSON.decodeFromString<ElevenLabsTranscription>(body).text)
    } catch (_: Throwable) {
        SttResult.Failed(SttError.Transient(null))
    }

    private fun classify(code: Int, body: String): SttError {
        android.util.Log.w("WE-DIAG", "elevenlabs stt http $code")   // status code ONLY
        return when (code) {
            401 -> SttError.Fatal(FatalKind.INVALID_KEY, "Key rejected")
            403 -> SttError.Fatal(FatalKind.FORBIDDEN, "Access denied for this key")
            400, 413, 422 -> SttError.BadSegment
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
        private val JSON = Json { ignoreUnknownKeys = true }   // kotlinx, NOT org.json (banned)
        const val DEFAULT_MODEL = "scribe_v2"
        private const val ENDPOINT = "https://api.elevenlabs.io/v1/speech-to-text"
        private const val WAV_HEADER_BYTES = 44
        private val QUOTA_MARKERS = listOf("quota_exceeded", "insufficient_quota")
    }
}
