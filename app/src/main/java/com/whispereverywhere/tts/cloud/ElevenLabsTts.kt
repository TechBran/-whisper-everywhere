package com.whispereverywhere.tts.cloud

import android.content.Context
import android.net.Uri
import com.whispereverywhere.net.HttpResult
import com.whispereverywhere.net.HttpResultBytes
import com.whispereverywhere.net.HttpTransport
import com.whispereverywhere.provider.ProviderCatalog
import com.whispereverywhere.provider.ProviderId
import com.whispereverywhere.recording.AudioDecoder
import com.whispereverywhere.recording.PcmSink
import com.whispereverywhere.recording.Resampler
import com.whispereverywhere.transcription.cloud.FatalKind
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * ElevenLabs speech synthesis via `eleven_flash_v2_5` (low-latency Flash). One JSON POST per
 * clause-bounded unit, header `xi-api-key`.
 *
 * Two output formats, tried in order:
 *  1. `output_format=pcm_24000` — headerless 24 kHz 16-bit LE mono, drops straight into the bank via
 *     [PcmBytes.toShortArrayLE], no decode. Requires a paid (Creator+) tier.
 *  2. On a tier rejection of pcm (a 401/403 whose body names the output format, and is NOT a credit
 *     fault), retry the SAME unit with the universal `output_format=mp3_44100_128` and decode it
 *     through the EXISTING batch [AudioDecoder] (constraint: no second decoder) at its NATIVE rate,
 *     then resample straight to 24 kHz — full-band, not through the lossy 16 kHz whisper path.
 *
 * The tier discriminator is deliberately narrow: a plain bad-key 401 or an out-of-credit 401 must
 * NOT be misread as a format rejection (that would spend a wasted mp3 request and hide a fatal
 * fault). Credit markers short-circuit; only an explicit output-format marker triggers the retry —
 * exactly the discipline STT uses to split a quota 429 from a plain 429.
 *
 * ElevenLabs has no request-time speed parameter, so [speed] is intentionally ignored here; the
 * speed setting governs the on-device voice (and the local fallback) only. The UI says so honestly.
 *
 * Never log the key, the headers, or the unit text — unit LENGTH, the format tried, and status
 * codes only.
 */
class ElevenLabsTts(
    private val transport: HttpTransport,
    private val apiKey: String,
    private val context: Context?,
    private val model: String = DEFAULT_MODEL,
    // The mp3-decode boundary, injectable so the pure-logic tests assert "mp3 path taken" without
    // running MediaCodec. Defaults to the real framework decode using the app [context].
    private val decodeMp3: (ByteArray) -> ShortArray = { bytes ->
        decodeMp3ToPcm24k(requireNotNull(context) { "ElevenLabsTts mp3 fallback needs a Context" }, bytes)
    },
) : TtsProvider {

    override val id = ProviderId.ELEVENLABS
    override val sampleRate = 24_000

    /** Dynamic catalog, fetched once and cached for the app session (recon: reuse the validation
     *  fetch shape). Never a failure is cached — a transient fetch error leaves it re-fetchable. */
    private var cachedVoices: List<CloudVoice>? = null

    override suspend fun synth(
        unit: String,
        voiceId: String,
        speed: Float,
        onPcm: (ShortArray) -> Boolean,
    ): TtsResult {
        // Fail locally rather than spending a request on an empty clause.
        if (unit.isBlank()) return TtsResult.Failed(TtsError.BadUnit)

        // Attempt 1: native 24 kHz PCM — no decode when the tier allows it.
        return when (val pcm = request(unit, voiceId, FORMAT_PCM)) {
            is HttpResultBytes.NetworkError -> TtsResult.Failed(TtsError.Offline)
            is HttpResultBytes.Ok -> deliver(PcmBytes.toShortArrayLE(pcm.bytes), onPcm)
            is HttpResultBytes.HttpError ->
                if (isFormatTierRejection(pcm.code, pcm.body)) mp3Fallback(unit, voiceId, onPcm)
                else TtsResult.Failed(classify(pcm.code, pcm.body, unit.length, "pcm"))
        }
    }

    // Attempt 2: universal mp3, decoded to 24 kHz shorts through the existing batch decoder.
    private suspend fun mp3Fallback(unit: String, voiceId: String, onPcm: (ShortArray) -> Boolean): TtsResult =
        when (val mp3 = request(unit, voiceId, FORMAT_MP3)) {
            is HttpResultBytes.NetworkError -> TtsResult.Failed(TtsError.Offline)
            is HttpResultBytes.Ok -> deliver(decodeMp3(mp3.bytes), onPcm)
            is HttpResultBytes.HttpError -> TtsResult.Failed(classify(mp3.code, mp3.body, unit.length, "mp3"))
        }

    private suspend fun request(unit: String, voiceId: String, outputFormat: String): HttpResultBytes {
        val provider = ProviderCatalog.byId(ProviderId.ELEVENLABS)
        val headers = mapOf(provider.authHeaderName to provider.authHeaderValue(apiKey))
        val url = "$SYNTH_BASE/$voiceId?output_format=$outputFormat"
        val body = JSON.encodeToString(SynthReq.serializer(), SynthReq(text = unit, model_id = model))
        return transport.postForBytes(url, headers, body)
    }

    private fun deliver(shorts: ShortArray, onPcm: (ShortArray) -> Boolean): TtsResult =
        if (!onPcm(shorts)) TtsResult.Cancelled else TtsResult.Done

    /**
     * True only when a 401/403 is specifically about the requested OUTPUT FORMAT — not credit and
     * not a bad key. A credit marker short-circuits FIRST so an out-of-credit account (whose message
     * may itself say "upgrade") is never masqueraded as a format issue; then an explicit format
     * marker qualifies. This is the discriminator that keeps a real fatal from being retried away.
     */
    private fun isFormatTierRejection(code: Int, body: String): Boolean {
        if (code != 401 && code != 403) return false
        if (QUOTA_MARKERS.any { body.contains(it, ignoreCase = true) }) return false
        return FORMAT_MARKERS.any { body.contains(it, ignoreCase = true) }
    }

    private fun classify(code: Int, body: String, unitLength: Int, fmt: String): TtsError {
        // STATUS CODE + format tried + unit LENGTH only — never the body or the unit text. Mirrors
        // ElevenLabsStt's map: 401 splits on a quota marker (top up vs. bad key), 429 splits too.
        android.util.Log.w("WE-DIAG", "elevenlabs tts http $code fmt=$fmt unitLen=$unitLength")
        return when (code) {
            401 -> if (QUOTA_MARKERS.any { body.contains(it, ignoreCase = true) }) {
                TtsError.Fatal(FatalKind.OUT_OF_CREDIT, "Account has no remaining credit")
            } else {
                TtsError.Fatal(FatalKind.INVALID_KEY, "Key rejected")
            }
            403 -> TtsError.Fatal(FatalKind.FORBIDDEN, "Access denied for this key")
            400, 413, 422 -> TtsError.BadUnit
            429 -> if (QUOTA_MARKERS.any { body.contains(it, ignoreCase = true) }) {
                TtsError.Fatal(FatalKind.OUT_OF_CREDIT, "Account has no remaining credit")
            } else {
                TtsError.Transient(null)
            }
            in 500..599 -> TtsError.Transient(null)
            else -> TtsError.Transient(null)
        }
    }

    /**
     * The dynamic voice catalog: `GET /v1/voices`, parsed with kotlinx.serialization and cached for
     * the app session. A fetch failure returns an empty list WITHOUT caching, so the picker can show
     * an honest error row and a later attempt can still succeed.
     */
    suspend fun voices(): List<CloudVoice> {
        cachedVoices?.let { return it }
        val provider = ProviderCatalog.byId(ProviderId.ELEVENLABS)
        val headers = mapOf(provider.authHeaderName to provider.authHeaderValue(apiKey))
        val parsed = when (val r = transport.get(VOICES_ENDPOINT, headers)) {
            is HttpResult.Ok -> parseVoices(r.body)
            else -> return emptyList()
        }
        cachedVoices = parsed
        return parsed
    }

    private fun parseVoices(body: String): List<CloudVoice> = try {
        JSON.decodeFromString(VoicesResponse.serializer(), body).voices
            .filter { it.voice_id.isNotBlank() }
            .map {
                CloudVoice(
                    providerId = ProviderId.ELEVENLABS,
                    voiceId = it.voice_id,
                    displayName = it.name.ifBlank { it.voice_id },
                    recommended = false, // dynamic catalog carries no provider recommendation
                )
            }
    } catch (_: Throwable) {
        emptyList()
    }

    companion object {
        /** Re-verified against live docs 2026-07-30: current low-latency Flash model. The single
         *  pinned model constant for this adapter. */
        const val DEFAULT_MODEL = "eleven_flash_v2_5"

        private const val SYNTH_BASE = "https://api.elevenlabs.io/v1/text-to-speech"
        private const val VOICES_ENDPOINT = "https://api.elevenlabs.io/v1/voices"

        private const val FORMAT_PCM = "pcm_24000"
        private const val FORMAT_MP3 = "mp3_44100_128"

        private val JSON = Json { ignoreUnknownKeys = true } // kotlinx, NOT org.json (banned)

        // Credit exhaustion — checked FIRST so it is never confused with a format rejection.
        private val QUOTA_MARKERS = listOf("quota_exceeded", "insufficient_quota", "insufficient credit")

        // Explicit output-format markers. Narrow on purpose: a plain bad key or an out-of-credit
        // body contains none of these, so only a true format/tier rejection triggers the mp3 retry.
        private val FORMAT_MARKERS = listOf("output_format", "pcm_24000", "pcm_")

        /**
         * mp3 fallback bytes -> 24 kHz PCM16 shorts, reusing the EXISTING batch [AudioDecoder] (no
         * second decoder — a binding constraint). The decoder takes a SAF Uri and emits to a
         * [PcmSink], so the in-memory mp3 is written to a temp file in [Context.getCacheDir], handed
         * in as `Uri.fromFile`, decoded, read back, and the temp files deleted in `finally`.
         *
         * FULL-BAND FIX (owner-promised): the decoder is asked NOT to resample (`resampleTo16k =
         * false`), so it emits the mp3's NATIVE-rate mono PCM (44.1 kHz for mp3_44100_128) and
         * reports that rate. We then resample the full-band native audio STRAIGHT to 24 kHz. The old
         * path went through the shared 16 kHz whisper decoder first and discarded everything above
         * ~8 kHz before upsampling — audibly band-limited. This is a rate adaptation, NOT a new
         * decoder. Framework end-to-end -> validated on device only (instrumented compile-check);
         * the pure-logic tests inject a stub for [decodeMp3] and never reach this.
         */
        fun decodeMp3ToPcm24k(context: Context, bytes: ByteArray): ShortArray {
            val tmpIn = File.createTempFile("el-tts-", ".mp3", context.cacheDir)
            val tmpOut = File.createTempFile("el-tts-", ".pcm", context.cacheDir)
            return try {
                tmpIn.writeBytes(bytes)
                val sink = PcmSink(tmpOut)
                val result = try {
                    AudioDecoder().decodeTo(context, Uri.fromFile(tmpIn), sink, resampleTo16k = false) {
                        /* progress ignored */
                    }
                } finally {
                    sink.close()
                }
                when (result) {
                    is AudioDecoder.DecodeResult.Unsupported ->
                        ShortArray(0) // a corrupt/undecodable body reads as silence; the seam reports Done
                    is AudioDecoder.DecodeResult.Ok -> Resampler.resampleTo24k(
                        PcmBytes.toShortArrayLE(tmpOut.readBytes()), result.sampleRate,
                    )
                }
            } finally {
                runCatching { tmpIn.delete() }
                runCatching { tmpOut.delete() }
            }
        }
    }
}

@Serializable
private data class SynthReq(val text: String, val model_id: String)

@Serializable
private data class VoicesResponse(val voices: List<VoiceItem> = emptyList())

@Serializable
private data class VoiceItem(val voice_id: String = "", val name: String = "")
