package com.whispereverywhere.tts.cloud

import com.whispereverywhere.net.HttpResultBytes
import com.whispereverywhere.net.HttpTransport
import com.whispereverywhere.provider.ProviderCatalog
import com.whispereverywhere.provider.ProviderId
import com.whispereverywhere.transcription.cloud.FatalKind
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Soniox real-time speech synthesis via `tts-rt-v1`. One JSON POST per clause-bounded unit to
 * `tts-rt.soniox.com/tts`, `Authorization: Bearer` (same header family as Soniox STT — the existing
 * account key). `audio_format=pcm_s16le` @ `sample_rate=24000` returns a HEADERLESS 24 kHz 16-bit
 * little-endian mono body that drops straight into the bank via [PcmBytes.toShortArrayLE] — no
 * decoder, no Resampler, so no [android.content.Context] is threaded here (unlike ElevenLabs' mp3
 * tier).
 *
 * MULTILINGUAL is the selling point, so [language] is NEVER hard-pinned: the request defaults to
 * "auto". "auto" is not documented for the TTS generate endpoint, so a 400 that names the language
 * field triggers ONE retry of the same unit with [defaultLanguage] — the adapter degrades to a code
 * rather than forcing one up front. (Verify "auto" acceptance against the owner's live key; the
 * fallback keeps every other language honest meanwhile.)
 *
 * Soniox DOES expose a request-time `speed` (0.7–1.3), so unlike ElevenLabs/Gemini it honors [speed]
 * (coerced) — it mirrors OpenAI, and the UI shows no "no speed control" note. No speed CLAIM is made
 * anywhere in the copy.
 *
 * NEVER log the key, the headers, or the unit text — unit LENGTH and status codes only.
 */
class SonioxTts(
    private val transport: HttpTransport,
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
    private val defaultLanguage: String = FALLBACK_LANGUAGE,
) : TtsProvider {

    override val id = ProviderId.SONIOX
    override val sampleRate = 24_000

    override suspend fun synth(
        unit: String,
        voiceId: String,
        speed: Float,
        onPcm: (ShortArray) -> Boolean,
    ): TtsResult {
        // Fail locally rather than spending a request on an empty or over-long clause. 5000 is the
        // documented text max; a ≤220-char clause never trips it, but guard defensively.
        if (unit.isBlank()) return TtsResult.Failed(TtsError.BadUnit)
        if (unit.length > MAX_TEXT) return TtsResult.Failed(TtsError.BadUnit)

        // Attempt 1: language "auto" (the multilingual default — never forced).
        return when (val r = request(unit, voiceId, speed, LANGUAGE_AUTO)) {
            is HttpResultBytes.NetworkError -> TtsResult.Failed(TtsError.Offline)
            is HttpResultBytes.Ok -> deliver(PcmBytes.toShortArrayLE(r.bytes), onPcm)
            is HttpResultBytes.HttpError ->
                // "auto" is undocumented for generate: a 400 that names the language field means this
                // host rejected "auto" — retry ONCE with a concrete code rather than forcing one up
                // front. Any other 400 (and every other status) classifies normally, no wasted retry.
                if (r.code == 400 && LANGUAGE_MARKERS.any { r.body.contains(it, ignoreCase = true) }) {
                    when (val r2 = request(unit, voiceId, speed, defaultLanguage)) {
                        is HttpResultBytes.NetworkError -> TtsResult.Failed(TtsError.Offline)
                        is HttpResultBytes.Ok -> deliver(PcmBytes.toShortArrayLE(r2.bytes), onPcm)
                        is HttpResultBytes.HttpError ->
                            TtsResult.Failed(classify(r2.code, r2.body, unit.length))
                    }
                } else {
                    TtsResult.Failed(classify(r.code, r.body, unit.length))
                }
        }
    }

    private suspend fun request(
        unit: String,
        voiceId: String,
        speed: Float,
        language: String,
    ): HttpResultBytes {
        val provider = ProviderCatalog.byId(ProviderId.SONIOX)
        val headers = mapOf(provider.authHeaderName to provider.authHeaderValue(apiKey))
        val body = JSON.encodeToString(
            SonioxTtsReq.serializer(),
            SonioxTtsReq(
                model = model,
                language = language,
                voice = voiceId,
                audio_format = AUDIO_FORMAT,
                text = unit,
                sample_rate = 24_000,
                speed = speed.coerceIn(MIN_SPEED, MAX_SPEED),
            ),
        )
        return transport.postForBytes(ENDPOINT, headers, body)
    }

    private fun deliver(shorts: ShortArray, onPcm: (ShortArray) -> Boolean): TtsResult {
        // A no-audio 200 (empty body, or a truncated sub-sample body that decodes to nothing) must
        // NOT be onPcm(empty)→Done: that is a SILENT SKIP (the clause's words vanish into a gap) and
        // it defeats the money circuit breaker — the engine maps Done→Cloud→consecutiveSoft=0,
        // resetting the soft-latch streak so cloud re-attempts/re-bills every remaining clause. Fail
        // Transient (not BadUnit) so it counts toward the soft-latch AND re-synthesizes this clause
        // on the local voice — mirroring Gemini's "a refusal is not silence". Covers both Ok branches
        // (attempt 1 and the language-retry), so neither can silently emit an empty clause.
        if (shorts.isEmpty()) return TtsResult.Failed(TtsError.Transient(null))
        return if (!onPcm(shorts)) TtsResult.Cancelled else TtsResult.Done
    }

    private fun classify(code: Int, body: String, unitLength: Int): TtsError {
        // STATUS CODE + unit LENGTH only — never the body or the unit text (class doc / global rule).
        android.util.Log.w("WE-DIAG", "soniox tts http $code unitLen=$unitLength")
        return when (code) {
            401 -> TtsError.Fatal(FatalKind.INVALID_KEY, "Key rejected")
            // 403 = temporary API-key session expired; treat as a forbidden/key fault, not transient.
            403 -> TtsError.Fatal(FatalKind.FORBIDDEN, "Access denied for this key")
            // Payment is 402 on this host (NOT 404) — matches Soniox STT's OUT_OF_CREDIT taxonomy.
            402 -> TtsError.Fatal(FatalKind.OUT_OF_CREDIT, "Account has no remaining credit")
            // Soniox folds unknown-voice AND unknown-model into one 400 bucket (docs carve no
            // distinct code). A model marker → MODEL_UNAVAILABLE (parity with the other adapters'
            // model-retirement latch); otherwise a malformed unit. NB: an "auto"-language 400 is
            // caught in synth() before it ever reaches here.
            400 -> if (MODEL_MARKERS.any { body.contains(it, ignoreCase = true) }) {
                TtsError.Fatal(FatalKind.MODEL_UNAVAILABLE, "Speech synthesis model unavailable")
            } else {
                TtsError.BadUnit
            }
            // No documented 404→MODEL_UNAVAILABLE on this host, but keep the guard defensively so a
            // future host change can't silently misclassify a retired model as a generic transient.
            404 -> TtsError.Fatal(FatalKind.MODEL_UNAVAILABLE, "Speech synthesis model unavailable")
            408 -> TtsError.Transient(null)
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
        /** Pinned against live docs 2026-07-31. The single model constant for this adapter. */
        const val DEFAULT_MODEL = "tts-rt-v1"

        private const val ENDPOINT = "https://tts-rt.soniox.com/tts"
        private const val AUDIO_FORMAT = "pcm_s16le" // raw signed 16-bit LE @ 24 kHz — no decode
        private const val MAX_TEXT = 5000            // documented text max; clause units clear it ~22×

        private const val LANGUAGE_AUTO = "auto"     // multilingual default — never forced
        private const val FALLBACK_LANGUAGE = "en"   // used only if the host rejects "auto"

        // Soniox's documented speed range; values outside it 400.
        private const val MIN_SPEED = 0.7f
        private const val MAX_SPEED = 1.3f

        private val JSON = Json { encodeDefaults = true } // kotlinx, NOT org.json (banned)

        private val LANGUAGE_MARKERS = listOf("language", "\"language\"", "lang")
        private val MODEL_MARKERS = listOf("model", "\"model\"")
        private val QUOTA_MARKERS = listOf("insufficient", "quota", "balance", "credit")
    }
}

@Serializable
private data class SonioxTtsReq(
    val model: String,
    val language: String,
    val voice: String,
    val audio_format: String,
    val text: String,
    val sample_rate: Int,
    val speed: Float,
)

/**
 * The confirmed built-in Soniox voices (live docs 2026-07-31): Maya (the documented default, used in
 * all primary examples) and Adrian. A full roster is NOT published and `GET /v1/voices` lists only
 * project/cloned voices, so this is a deliberate static set of the two confirmed presets — mirroring
 * Gemini's static shape (displayName == the literal voiceId the request expects). Maya is flagged
 * recommended (the documented default), not a speed or quality claim about Adrian.
 */
object SonioxTtsVoices {
    private val RECOMMENDED = setOf("Maya")

    val ALL: List<CloudVoice> = listOf("Maya", "Adrian").map { name ->
        CloudVoice(
            providerId = ProviderId.SONIOX,
            voiceId = name,
            displayName = name,
            recommended = name in RECOMMENDED,
        )
    }
}
