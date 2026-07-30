package com.whispereverywhere.tts.cloud

import com.whispereverywhere.net.HttpResultBytes
import com.whispereverywhere.net.HttpTransport
import com.whispereverywhere.provider.ProviderCatalog
import com.whispereverywhere.provider.ProviderId
import com.whispereverywhere.transcription.cloud.FatalKind
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * OpenAI speech synthesis via `gpt-4o-mini-tts`. One JSON POST per clause-bounded unit.
 *
 * `response_format=pcm` returns a HEADERLESS 24 kHz 16-bit little-endian mono body — it drops
 * straight into the playback bank via [PcmBytes.toShortArrayLE]; no decoder is involved.
 *
 * Never log the key, the headers, or the unit text — unit LENGTH and status codes only.
 */
class OpenAiTts(
    private val transport: HttpTransport,
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
) : TtsProvider {

    override val id = ProviderId.OPENAI
    override val sampleRate = 24_000

    override suspend fun synth(
        unit: String,
        voiceId: String,
        speed: Float,
        onPcm: (ShortArray) -> Boolean,
    ): TtsResult {
        // Fail locally rather than spending a request on an empty clause.
        if (unit.isBlank()) return TtsResult.Failed(TtsError.BadUnit)

        val provider = ProviderCatalog.byId(ProviderId.OPENAI)
        val headers = mapOf(provider.authHeaderName to provider.authHeaderValue(apiKey))
        val body = JSON.encodeToString(
            SpeechReq.serializer(),
            SpeechReq(
                model = model,
                voice = voiceId,
                input = unit,
                response_format = "pcm",
                speed = speed.coerceIn(MIN_SPEED, MAX_SPEED),
            ),
        )

        return when (val r = transport.postForBytes(ENDPOINT, headers, body)) {
            is HttpResultBytes.NetworkError -> TtsResult.Failed(TtsError.Offline)
            is HttpResultBytes.HttpError -> TtsResult.Failed(classify(r.code, r.body, unit.length))
            is HttpResultBytes.Ok -> {
                val shorts = PcmBytes.toShortArrayLE(r.bytes)
                if (!onPcm(shorts)) TtsResult.Cancelled else TtsResult.Done
            }
        }
    }

    private fun classify(code: Int, body: String, unitLength: Int): TtsError {
        // STATUS CODE + unit LENGTH only — never the body or the unit text (class doc / global
        // constraint). Mirrors OpenAiStt's status map so the 429-quota discipline is identical.
        android.util.Log.w("WE-DIAG", "openai tts http $code unitLen=$unitLength")
        return when (code) {
            401 -> TtsError.Fatal(FatalKind.INVALID_KEY, "Key rejected")
            403 -> TtsError.Fatal(FatalKind.FORBIDDEN, "Access denied for this key")
            400, 422 -> TtsError.BadUnit
            429 -> if (QUOTA_MARKERS.any { body.contains(it, ignoreCase = true) }) {
                TtsError.Fatal(FatalKind.OUT_OF_CREDIT, "Account has no remaining credit")
            } else {
                TtsError.Transient(null)
            }
            in 500..599 -> TtsError.Transient(null)
            else -> TtsError.Transient(null)
        }
    }

    companion object {
        /**
         * Verified against live docs 2026-07-30: `response_format=pcm` -> headerless 24 kHz
         * 16-bit LE mono, dropping straight into the bank with no decode step.
         */
        const val DEFAULT_MODEL = "gpt-4o-mini-tts"
        private const val ENDPOINT = "https://api.openai.com/v1/audio/speech"

        // OpenAI's documented speed range; values outside it 400.
        private const val MIN_SPEED = 0.25f
        private const val MAX_SPEED = 4.0f

        private val JSON = Json { encodeDefaults = true }
        private val QUOTA_MARKERS = listOf("insufficient_quota", "quota_exceeded")
    }
}

@Serializable
private data class SpeechReq(
    val model: String,
    val voice: String,
    val input: String,
    val response_format: String,
    val speed: Float,
)

/**
 * The 13 voices `gpt-4o-mini-tts` supports (verified against live docs 2026-07-30). marin/cedar
 * are OpenAI's own documented recommendation, flagged so the picker can chip them — not a claim
 * about the other eleven, and not a speed claim either way.
 */
object OpenAiTtsVoices {
    private val RECOMMENDED = setOf("marin", "cedar")

    val ALL: List<CloudVoice> = listOf(
        "alloy", "ash", "ballad", "coral", "echo", "fable", "nova", "onyx", "sage", "shimmer",
        "verse", "marin", "cedar",
    ).map { voiceId ->
        CloudVoice(
            providerId = ProviderId.OPENAI,
            voiceId = voiceId,
            displayName = voiceId.replaceFirstChar { it.uppercase() },
            recommended = voiceId in RECOMMENDED,
        )
    }
}
