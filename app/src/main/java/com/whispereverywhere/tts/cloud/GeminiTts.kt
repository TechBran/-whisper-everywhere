package com.whispereverywhere.tts.cloud

import com.whispereverywhere.net.HttpResultBytes
import com.whispereverywhere.net.HttpTransport
import com.whispereverywhere.provider.ProviderCatalog
import com.whispereverywhere.provider.ProviderId
import com.whispereverywhere.transcription.cloud.FatalKind
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Base64

/**
 * Gemini speech synthesis via `generateContent` (the same GA plain-key path `GeminiStt` uses --
 * see the plan's "Pinned Gemini facts" for why not `interactions`). One JSON POST per
 * clause-bounded unit.
 *
 * Both TTS models are PREVIEW, so reachability is not guaranteed: [MODELS] is tried in order, and
 * a 404 (the model id lives in the URL path, so a 404 means THAT pinned model is retired, typo'd,
 * or not-yet-GA -- a permanent fault, not a hiccup) walks to the next model. Only after every
 * model in the list 404s does the provider latch [FatalKind.MODEL_UNAVAILABLE] (C2b pattern).
 *
 * Load-bearing details, mirrored from `GeminiStt`:
 *  - THE KEY RIDES THE HEADER `x-goog-api-key`. The URL NEVER carries it (no ?key=).
 *  - THE BAD-KEY STATUS IS 400, NOT 401 (the C1 trap). 400 is only INVALID_KEY when the body
 *    carries an api-key-invalid marker; a plain 400 is BadUnit (malformed request), not an
 *    account fault.
 *  - A 200 refusal (candidate with no inline audio part) is Transient, NOT a silently empty
 *    Done -- a refusal is not silence.
 *  - base64 uses java.util.Base64; android.util.Base64 returns defaults under JVM tests (C1 trap).
 *  - Gemini has no request-time speed parameter, so [speed] is intentionally ignored; the speed
 *    setting governs the on-device voice (and the local fallback) only. The UI says so honestly.
 *
 * NEVER log the key, the headers, or the unit text -- unit LENGTH and status codes only.
 */
class GeminiTts(
    private val transport: HttpTransport,
    private val apiKey: String,
    private val models: List<String> = MODELS,
) : TtsProvider {

    override val id = ProviderId.GEMINI
    override val sampleRate = 24_000

    override suspend fun synth(
        unit: String,
        voiceId: String,
        speed: Float,
        onPcm: (ShortArray) -> Boolean,
    ): TtsResult {
        // Fail locally rather than spending a request on an empty clause.
        if (unit.isBlank()) return TtsResult.Failed(TtsError.BadUnit)

        for (model in models) {
            when (val r = request(unit, voiceId, model)) {
                is HttpResultBytes.NetworkError -> return TtsResult.Failed(TtsError.Offline)
                is HttpResultBytes.Ok -> return parseAndDeliver(r.bytes, onPcm)
                is HttpResultBytes.HttpError ->
                    if (r.code == 404) continue // this model is unavailable -- try the next one
                    else return TtsResult.Failed(classify(r.code, r.body, unit.length))
            }
        }
        // Every pinned model 404'd. Latch Fatal rather than falling to Transient (which would
        // doom-POST both models again for every remaining unit of this read).
        android.util.Log.w("WE-DIAG", "gemini tts all models unavailable unitLen=${unit.length}")
        return TtsResult.Failed(TtsError.Fatal(FatalKind.MODEL_UNAVAILABLE, "Speech synthesis model unavailable"))
    }

    private suspend fun request(unit: String, voiceId: String, model: String): HttpResultBytes {
        val provider = ProviderCatalog.byId(ProviderId.GEMINI)
        val headers = mapOf(provider.authHeaderName to provider.authHeaderValue(apiKey))
        val body = JSON.encodeToString(
            GeminiTtsRequest.serializer(),
            GeminiTtsRequest(
                contents = listOf(GeminiTtsContent(parts = listOf(GeminiTtsPart(text = unit)))),
                generationConfig = GeminiTtsGenerationConfig(
                    responseModalities = listOf("AUDIO"),
                    speechConfig = GeminiTtsSpeechConfig(
                        voiceConfig = GeminiTtsVoiceConfig(
                            prebuiltVoiceConfig = GeminiTtsPrebuiltVoiceConfig(voiceName = voiceId),
                        ),
                    ),
                ),
            ),
        )
        // Key rides the x-goog-api-key header -- this URL never carries it.
        return transport.postForBytes(ENDPOINT + model + GENERATE, headers, body)
    }

    /**
     * The response is a JSON envelope (not a raw binary body): candidates[0].content.parts[0]
     * .inlineData.data is base64 PCM16 mono 24 kHz. No part at all (a refusal / safety
     * finishReason, or an unparseable body) is Transient, NOT a silently empty Done.
     */
    private fun parseAndDeliver(bytes: ByteArray, onPcm: (ShortArray) -> Boolean): TtsResult {
        val b64 = try {
            JSON.decodeFromString(GeminiTtsResponse.serializer(), String(bytes, Charsets.UTF_8))
                .candidates.firstOrNull()?.content?.parts
                ?.firstOrNull { it.inlineData?.data != null }?.inlineData?.data
        } catch (_: Throwable) {
            null
        }
        if (b64 == null) return TtsResult.Failed(TtsError.Transient(null))
        val shorts = PcmBytes.toShortArrayLE(Base64.getDecoder().decode(b64))
        return if (!onPcm(shorts)) TtsResult.Cancelled else TtsResult.Done
    }

    private fun classify(code: Int, body: String, unitLength: Int): TtsError {
        // STATUS CODE + unit LENGTH only -- never the body or the unit text.
        android.util.Log.w("WE-DIAG", "gemini tts http $code unitLen=$unitLength")
        return when (code) {
            // 400 is INVALID_KEY only with the marker (Gemini's C1 trap: a bad key is 400, not
            // 401); otherwise a malformed-request BadUnit, not an account fault.
            400 -> if (INVALID_KEY_MARKERS.any { body.contains(it, ignoreCase = true) }) {
                TtsError.Fatal(FatalKind.INVALID_KEY, "Key rejected")
            } else {
                TtsError.BadUnit
            }
            401 -> TtsError.Fatal(FatalKind.INVALID_KEY, "Key rejected")
            403 -> TtsError.Fatal(FatalKind.FORBIDDEN, "Access denied for this key")
            413 -> TtsError.BadUnit
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
         * Pinned model order, re-verified against live docs 2026-07-30: both are PREVIEW models.
         * The model id lives in the URL path, so a 404 on one is a permanent fault for THAT model
         * -- [synth] walks to the next one rather than treating it as a transient hiccup.
         */
        val MODELS = listOf("gemini-3.1-flash-tts-preview", "gemini-2.5-flash-preview-tts")

        // Key rides the x-goog-api-key header -- this URL never carries it.
        private const val ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models/"
        private const val GENERATE = ":generateContent"

        private val JSON = Json { ignoreUnknownKeys = true }
        private val INVALID_KEY_MARKERS = listOf("API_KEY_INVALID", "API key not valid")
        private val QUOTA_MARKERS = listOf("RESOURCE_EXHAUSTED", "quota_exceeded", "insufficient_quota")
    }
}

// --- request DTOs (camelCase matches the REST body verbatim) ---
@Serializable private data class GeminiTtsRequest(
    val contents: List<GeminiTtsContent>,
    val generationConfig: GeminiTtsGenerationConfig,
)
@Serializable private data class GeminiTtsContent(val parts: List<GeminiTtsPart>)
@Serializable private data class GeminiTtsPart(val text: String)
@Serializable private data class GeminiTtsGenerationConfig(
    val responseModalities: List<String>,
    val speechConfig: GeminiTtsSpeechConfig,
)
@Serializable private data class GeminiTtsSpeechConfig(val voiceConfig: GeminiTtsVoiceConfig)
@Serializable private data class GeminiTtsVoiceConfig(val prebuiltVoiceConfig: GeminiTtsPrebuiltVoiceConfig)
@Serializable private data class GeminiTtsPrebuiltVoiceConfig(val voiceName: String)

// --- response DTOs: every field defaulted so a missing node is null/empty, never a throw ---
@Serializable private data class GeminiTtsResponse(val candidates: List<GeminiTtsCandidate> = emptyList())
@Serializable private data class GeminiTtsCandidate(val content: GeminiTtsRespContent = GeminiTtsRespContent())
@Serializable private data class GeminiTtsRespContent(val parts: List<GeminiTtsRespPart> = emptyList())
@Serializable private data class GeminiTtsRespPart(val inlineData: GeminiTtsInlineData? = null)
@Serializable private data class GeminiTtsInlineData(val data: String? = null)

/**
 * The 30 static prebuilt voices (verified against live docs 2026-07-30). voiceId is exactly the
 * string `speechConfig.voiceConfig.prebuiltVoiceConfig.voiceName` expects -- the catalog is
 * static, not reshaped, so displayName is the same string.
 */
object GeminiTtsVoices {
    val ALL: List<CloudVoice> = listOf(
        "Zephyr", "Puck", "Charon", "Kore", "Fenrir", "Leda", "Orus", "Aoede", "Callirrhoe",
        "Autonoe", "Enceladus", "Iapetus", "Umbriel", "Algieba", "Despina", "Erinome", "Algenib",
        "Rasalgethi", "Laomedeia", "Achernar", "Alnilam", "Schedar", "Gacrux", "Pulcherrima",
        "Achird", "Zubenelgenubi", "Vindemiatrix", "Sadachbia", "Sadaltager", "Sulafat",
    ).map { name ->
        CloudVoice(
            providerId = ProviderId.GEMINI,
            voiceId = name,
            displayName = name,
            recommended = false, // Gemini documents no per-voice recommendation, unlike OpenAI's marin/cedar
        )
    }
}
