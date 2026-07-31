package com.whispereverywhere.transcription.cloud

import com.whispereverywhere.audio.WavWriter
import com.whispereverywhere.net.HttpResult
import com.whispereverywhere.net.HttpTransport
import com.whispereverywhere.provider.ProviderCatalog
import com.whispereverywhere.provider.ProviderId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** `response_format=json` returns `{"text": "..."}`. Defaulted so a missing field is "" not a throw. */
@Serializable
private data class OpenAiTranscription(val text: String = "")

/**
 * OpenAI transcription. One multipart POST per committed segment.
 *
 * Three details are load-bearing and easy to get wrong:
 *  - RAW PCM IS REJECTED. The endpoint accepts containers only, so the segment is WAV-wrapped.
 *  - THE FILENAME DECIDES THE FORMAT. OpenAI infers it from the multipart filename, not the
 *    Content-Type, so the part must be named "audio.wav".
 *  - 429 IS AMBIGUOUS. It means transient rate limiting OR permanently exhausted credit, and only
 *    the body distinguishes them. Treating the latter as transient retries against an empty wallet
 *    forever, burning battery and the user's remaining goodwill.
 *
 * Never log the key, the headers, or the transcript.
 */
class OpenAiStt(
    private val transport: HttpTransport,
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
) : SttProvider {

    override val id = ProviderId.OPENAI

    /** 25 MB request cap; ~13 minutes of 16 kHz PCM16. */
    override val maxRequestBytes = 25L * 1024 * 1024

    override suspend fun transcribe(pcm: ByteArray, language: String?): SttResult {
        // Fail locally rather than spending an upload to be told it was too big.
        if (pcm.size.toLong() + WAV_HEADER_BYTES > maxRequestBytes) {
            return SttResult.Failed(SttError.BadSegment)
        }

        val provider = ProviderCatalog.byId(ProviderId.OPENAI)
        val headers = mapOf(provider.authHeaderName to provider.authHeaderValue(apiKey))
        val fields = buildMap {
            put("model", model)
            put("response_format", "json")
            // Omit entirely for auto-detect — sending an empty value is a 400.
            if (!language.isNullOrBlank() && language != "auto") put("language", language)
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
        // A 200 whose body will not parse is NOT an empty transcript. Returning "" would look like
        // silence, suppress fallback, and silently lose the user's sentence.
        SttResult.Text(JSON.decodeFromString<OpenAiTranscription>(body).text)
    } catch (_: Throwable) {
        // Undecodable, NOT Transient: the 200 was billed, so a batch retry would just re-bill for
        // the same unreadable answer. Fall to local for this chunk instead.
        SttResult.Failed(SttError.Undecodable)
    }

    private fun classify(code: Int, body: String): SttError {
        // The STATUS CODE alone — never the body (it can echo request details) and never a header.
        // Without this line a failing provider is invisible in logcat and the local fallback masks
        // the failure completely: the 2026-07-29 device test ran a whole session of latched fatals
        // that looked, on screen, like cloud working.
        android.util.Log.w("WE-DIAG", "openai stt http $code")
        return when (code) {
            401 -> SttError.Fatal(FatalKind.INVALID_KEY, "Key rejected")
            403 -> SttError.Fatal(FatalKind.FORBIDDEN, "Access denied for this key")
            400, 413 -> SttError.BadSegment
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
        /**
         * kotlinx.serialization, NOT org.json, and the reason is load-bearing: org.json ships in
         * android.jar, and this project sets `unitTests.isReturnDefaultValues = true`
         * (app/build.gradle.kts:166), so JSONObject's methods return type defaults under plain
         * JVM unit tests. `optString("text", "")` would return "" for every input — the parsing
         * tests would fail for a reason invisible in the code, exactly as android.util.Base64 did
         * in C1. kotlinx-serialization-json 1.7.3 is already a dependency with the plugin applied
         * and is pure JVM.
         */
        private val JSON = Json { ignoreUnknownKeys = true }

        /**
         * OpenAI's current high-accuracy transcription model. Verified against live docs
         * 2026-07-28: supported on v1/audio/transcriptions, $0.0045/min.
         *
         * This is an ALIAS, not a dated snapshot, and that is deliberate — see commit
         * "spec: OpenAI STT model -> gpt-transcribe, verified against live docs" (55cbb29), which
         * explicitly reversed this plan's earlier "pin a dated snapshot" instruction. The two
         * risks run in opposite directions:
         *   - A dated snapshot pins behaviour but IS eventually retired. That is not theoretical:
         *     gpt-4o-mini-transcribe-2025-03-20 shut down on 23 Jul 2026. In a shipped APK that a
         *     user may not update for months, retirement is a hard outage.
         *   - An alias is repointed forward by OpenAI rather than retired, so it stays reachable,
         *     at the cost of behaviour drifting under us.
         * For a consumer app that cannot force an update, staying REACHABLE wins. A drifting
         * transcript is a worse transcript; a retired model is a broken feature. There is also no
         * choice to make here — gpt-transcribe currently publishes no dated snapshot.
         *
         * Bonus for C4: this same id is also supported on v1/realtime, so adding streaming later
         * does not mean changing models.
         */
        const val DEFAULT_MODEL = "gpt-transcribe"
        private const val ENDPOINT = "https://api.openai.com/v1/audio/transcriptions"
        private const val WAV_HEADER_BYTES = 44
        private val QUOTA_MARKERS = listOf("insufficient_quota", "quota_exceeded")
    }
}
