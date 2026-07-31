package com.whispereverywhere.tts.cloud

import com.whispereverywhere.provider.ProviderId
import com.whispereverywhere.transcription.cloud.FatalKind   // reused, not redefined

/** Why a cloud synthesis attempt failed. Mirrors SttError; drives the same one-way fallback. */
sealed interface TtsError {
    /** No usable network. Do not retry in a tight loop; do not blame the key. */
    data object Offline : TtsError
    /** The account or key is the problem. Retrying cannot help — stop using this provider. */
    data class Fatal(val kind: FatalKind, val message: String) : TtsError
    /** Worth retrying. [retryAfterMs] is the server's own stated wait, when it gave one. */
    data class Transient(val retryAfterMs: Long?) : TtsError
    /** THIS unit is unacceptable (empty, too long, 422). Does NOT disable the provider. */
    data object BadUnit : TtsError
}

sealed interface TtsResult {
    /** All PCM for the unit was delivered through onPcm. */
    data object Done : TtsResult
    /** onPcm returned false — the caller cancelled; not an error. */
    data object Cancelled : TtsResult
    data class Failed(val error: TtsError) : TtsResult
}

/**
 * Turns ONE clause-bounded unit into 24 kHz PCM16 mono, streamed as ShortArray chunks through
 * [onPcm]. Deliberately narrow: no session, no state. Everything above (ordering, the one-way
 * local fallback, the latch, the toast) is provider-agnostic and lives at the TtsEngine seam.
 *
 * NEVER log the key, the headers, or the unit text — unit LENGTH and status codes only.
 */
interface TtsProvider {
    val id: ProviderId

    /**
     * Fixed output rate contract the bank relies on. All three providers emit 24 kHz. Consumed at
     * the engine seam (TtsEngine.cloudTrackRateMatches): a provider whose rate does not equal the
     * AudioTrack rate has its read fall to the local voice, so this is not a dead field.
     */
    val sampleRate: Int   // = 24_000

    /** @return false from onPcm to cancel (identical semantics to sherpa's 0). */
    suspend fun synth(unit: String, voiceId: String, speed: Float, onPcm: (ShortArray) -> Boolean): TtsResult
}

/** Shared little-endian PCM16 decode used by all three cloud adapters — unit-testable in JVM. */
object PcmBytes {
    /**
     * Decodes a headerless little-endian 16-bit PCM byte buffer into shorts. A trailing odd byte
     * (a malformed/truncated body) is dropped rather than thrown on — half a sample is not a
     * sample.
     */
    fun toShortArrayLE(bytes: ByteArray): ShortArray {
        val count = bytes.size / 2
        val out = ShortArray(count)
        for (i in 0 until count) {
            val lo = bytes[i * 2].toInt() and 0xFF
            val hi = bytes[i * 2 + 1].toInt() and 0xFF
            out[i] = ((hi shl 8) or lo).toShort()
        }
        return out
    }
}
