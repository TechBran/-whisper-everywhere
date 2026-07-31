package com.whispereverywhere.transcription.cloud

import com.whispereverywhere.audio.WavWriter
import com.whispereverywhere.net.HttpResult
import com.whispereverywhere.net.HttpTransport
import com.whispereverywhere.provider.ProviderCatalog
import com.whispereverywhere.provider.ProviderId
import com.whispereverywhere.text.TextJoin
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// --- DTOs: every field defaulted so a missing node is ""/empty, never a throw ---
@Serializable private data class SonioxFile(val id: String = "")
@Serializable private data class SonioxCreated(val id: String = "", val status: String = "")
@Serializable private data class SonioxStatus(
    val status: String = "",
    val error_type: String? = null,
    val error_message: String? = null,
)
@Serializable private data class SonioxTranscript(val tokens: List<SonioxToken> = emptyList())
@Serializable private data class SonioxToken(val text: String = "")
@Serializable private data class SonioxCreateReq(
    val model: String,
    val file_id: String,
    val language_hints: List<String>? = null,
)

/**
 * Soniox multilingual transcription. UNLIKE the other three adapters — which each do a single POST —
 * Soniox has NO synchronous endpoint, so one committed segment is an async job:
 *   upload (POST /v1/files) -> create (POST /v1/transcriptions) -> poll (GET, 1 s) -> fetch
 *   (GET .../transcript) -> DELETE the transcription AND the file.
 *
 * Load-bearing details:
 *  - THE KEY RIDES THE `Authorization: Bearer` HEADER. No URL carries it; logs are status-code-only.
 *  - MULTILINGUAL AUTO-DETECT IS THE DEFAULT — language_hints is omitted for null/blank/"auto", so
 *    Soniox detects the language itself; a specific preference narrows it.
 *  - THE ASYNC PATH STORES the audio + transcript server-side until deleted, so cleanup runs on
 *    EVERY exit — success, error, and cancellation (NonCancellable) — never leaking user audio.
 *  - THE POLL LOOP IS BOUNDED: a job that has not completed within maxPolls (or the wall-clock
 *    deadline) returns ProviderTimedOut — NON-retryable — so the segment falls to on-device rather
 *    than hanging the dictation turn AND without the batch layer re-billing a fresh async job.
 *  - 404 IS STEP-SENSITIVE: on CREATE it means the model id is wrong/retired -> Fatal
 *    MODEL_UNAVAILABLE (latched); on a later call it is odd server state -> Transient.
 *  - AMBIGUOUS STATUS SPLIT: a 401/429 whose body carries a balance marker is OUT_OF_CREDIT, not a
 *    bad key / not a plain rate-limit. A 200 whose transcript will not parse is Transient, not "".
 */
class SonioxStt(
    private val transport: HttpTransport,
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
    private val pollIntervalMs: Long = 1_000L,
    private val maxPolls: Int = 40,
    // The DOCUMENTED ~40 s bound made real: a wall-clock deadline over the whole poll loop, so a run
    // whose per-poll GETs are individually slow cannot drift far past 40 s before falling local.
    // maxPolls alone bounds the ITERATION count, not elapsed time.
    private val maxPollWallClockMs: Long = 40_000L,
    private val now: () -> Long = System::currentTimeMillis,
) : SttProvider {

    override val id = ProviderId.SONIOX

    /** ~25 MB request cap. A <=15 s segment is ~480 KB; the gate only guards against a runaway. */
    override val maxRequestBytes = 25L * 1024 * 1024

    private enum class Step { UPLOAD, CREATE, POLL, FETCH }

    override suspend fun transcribe(pcm: ByteArray, language: String?): SttResult {
        if (pcm.size.toLong() + WAV_HEADER_BYTES > maxRequestBytes) {
            return SttResult.Failed(SttError.BadSegment)
        }
        val provider = ProviderCatalog.byId(ProviderId.SONIOX)
        val headers = mapOf(provider.authHeaderName to provider.authHeaderValue(apiKey))

        // 1. Upload the WAV. Nothing is stored server-side yet, so a failure here needs no cleanup.
        val fileId = when (val up = transport.postMultipart(
            url = FILES_URL,
            headers = headers,
            filePart = HttpTransport.FilePart("file", "audio.wav", "audio/wav", WavWriter.wrap(pcm)),
            fields = emptyMap(),
        )) {
            is HttpResult.NetworkError -> return SttResult.Failed(SttError.Offline)
            is HttpResult.HttpError -> return SttResult.Failed(classify(up.code, up.body, Step.UPLOAD))
            is HttpResult.Ok -> parse<SonioxFile>(up.body)?.id?.takeIf { it.isNotBlank() }
                ?: return leakedUpload() // unparseable-200: file stored but its id can't be read
        }

        // From here the file EXISTS on Soniox's servers — it MUST be deleted on every path out.
        var transcriptionId: String? = null
        try {
            // 2. Create the job.
            val reqBody = JSON.encodeToString(
                SonioxCreateReq.serializer(),
                SonioxCreateReq(model = model, file_id = fileId, language_hints = hints(language)),
            )
            val tid = when (val cr = transport.postJson(TRANSCRIPTIONS_URL, headers, reqBody)) {
                is HttpResult.NetworkError -> return SttResult.Failed(SttError.Offline)
                is HttpResult.HttpError -> return SttResult.Failed(classify(cr.code, cr.body, Step.CREATE))
                is HttpResult.Ok -> parse<SonioxCreated>(cr.body)?.id?.takeIf { it.isNotBlank() }
                    ?: return leakedCreate() // unparseable-201: transcription may exist under an id we can't read
            }
            transcriptionId = tid

            // 3. Poll to a terminal state, BOUNDED by BOTH an iteration budget (maxPolls) AND an
            //    outer wall-clock deadline (maxPollWallClockMs). Past either -> ProviderTimedOut
            //    (falls local, no re-bill), so a job whose per-poll GETs are slow still honors ~40 s.
            var completed = false
            var polls = 0
            val pollDeadline = now() + maxPollWallClockMs
            while (polls < maxPolls && now() < pollDeadline) {
                delay(pollIntervalMs)
                when (val st = transport.get("$TRANSCRIPTIONS_URL/$tid", headers)) {
                    is HttpResult.NetworkError -> return SttResult.Failed(SttError.Offline)
                    is HttpResult.HttpError -> return SttResult.Failed(classify(st.code, st.body, Step.POLL))
                    is HttpResult.Ok -> {
                        val status = parse<SonioxStatus>(st.body)
                            ?: return SttResult.Failed(SttError.Transient(null))
                        val state = status.status.lowercase()
                        when {
                            state in IN_FLIGHT -> { polls++; continue }
                            state == COMPLETED -> { completed = true }
                            // Terminal failure ("error"/"failed"/anything else): fall local. An
                            // unsupported-audio error_type is this segment's fault (BadSegment); any
                            // other job failure is Transient. Both still clean up in `finally`.
                            else -> return SttResult.Failed(mapJobFailure(status))
                        }
                    }
                }
                if (completed) break
            }
            // Poll budget exhausted (iteration OR wall-clock): the job was accepted and may already
            // be billing, but we abandon it. ProviderTimedOut (NOT Transient) so the batch retry
            // layer falls straight to local instead of re-issuing a fresh — again-abandoned, again-
            // billed — async job up to the retry budget for the same 10-min chunk.
            if (!completed) return SttResult.Failed(SttError.ProviderTimedOut)

            // 4. Fetch + assemble from tokens (NO reliance on an unconfirmed top-level `text`).
            return when (val tr = transport.get("$TRANSCRIPTIONS_URL/$tid/transcript", headers)) {
                is HttpResult.NetworkError -> SttResult.Failed(SttError.Offline)
                is HttpResult.HttpError -> SttResult.Failed(classify(tr.code, tr.body, Step.FETCH))
                is HttpResult.Ok -> {
                    val parsed = parse<SonioxTranscript>(tr.body)
                        ?: return SttResult.Failed(SttError.Transient(null)) // unparseable-200
                    // Defensive melt-proof join (the brief's "make it melt-proof regardless"): Soniox
                    // tokens are ASSUMED to carry their own spacing, but the docs sample shows a bare
                    // "Hello". TextJoin.join is a proven no-op over an already-spaced stream and only
                    // inserts a space where two alphanumerics would otherwise touch, so it cannot
                    // regress a spaced stream while closing the melt if a bare token ever arrives.
                    SttResult.Text(parsed.tokens.fold("") { acc, t -> TextJoin.join(acc, t.text) })
                }
            }
        } finally {
            // 5. Cleanup — NonCancellable so a coroutine cancelled mid-poll still deletes the audio
            //    Soniox is storing. Delete failures are swallowed: they must never mask the result,
            //    and a stray undeleted file is a quota problem, not a user-visible one.
            withContext(NonCancellable) {
                transcriptionId?.let { runCatching { transport.delete("$TRANSCRIPTIONS_URL/$it", headers) } }
                runCatching { transport.delete("$FILES_URL/$fileId", headers) }
            }
        }
    }

    /** Multilingual auto-detect is the default: omit hints unless a concrete language is set. */
    private fun hints(language: String?): List<String>? =
        if (language.isNullOrBlank() || language == "auto") null else listOf(language)

    private fun mapJobFailure(status: SonioxStatus): SttError {
        android.util.Log.w("WE-DIAG", "soniox stt job ${status.status} type=${status.error_type}")
        val marker = "${status.error_type.orEmpty()} ${status.error_message.orEmpty()}"
        return if (AUDIO_ERROR_MARKERS.any { marker.contains(it, ignoreCase = true) }) {
            SttError.BadSegment
        } else {
            SttError.Transient(null)
        }
    }

    private fun classify(code: Int, body: String, step: Step): SttError {
        android.util.Log.w("WE-DIAG", "soniox stt http $code step=$step") // status + step ONLY
        return when (code) {
            401 -> if (BALANCE_MARKERS.any { body.contains(it, ignoreCase = true) }) {
                SttError.Fatal(FatalKind.OUT_OF_CREDIT, "Account has no remaining credit")
            } else {
                SttError.Fatal(FatalKind.INVALID_KEY, "Key rejected")
            }
            402 -> SttError.Fatal(FatalKind.OUT_OF_CREDIT, "Account has no remaining credit")
            403 -> SttError.Fatal(FatalKind.FORBIDDEN, "Access denied for this key")
            400, 413 -> SttError.BadSegment
            // Model id lives in the create body: a 404 there = wrong/retired model (permanent,
            // latch). A 404 on poll/fetch = missing file/transcription = odd server state (Transient).
            404 -> if (step == Step.CREATE) {
                SttError.Fatal(FatalKind.MODEL_UNAVAILABLE, "Transcription model unavailable")
            } else {
                SttError.Transient(null)
            }
            409 -> SttError.Transient(null)
            429 -> if (BALANCE_MARKERS.any { body.contains(it, ignoreCase = true) }) {
                SttError.Fatal(FatalKind.OUT_OF_CREDIT, "Account has no remaining credit")
            } else {
                SttError.Transient(null)
            }
            in 500..599 -> SttError.Transient(null)
            else -> SttError.Transient(null)
        }
    }

    private inline fun <reified T> parse(body: String): T? =
        runCatching { JSON.decodeFromString<T>(body) }.getOrNull()

    /**
     * A 200 upload whose id will not parse (Soniox nested/renamed the field — the "changed response
     * shape" case). The file IS stored server-side but its id was never read, so cleanup can never
     * delete it: this transcription's audio is leaked on the way to falling local. Log the leak
     * (status-shape only — no body, key, or URL) so an unverified upload shape is at least
     * observable; the resolve phase lists confirming this shape as ship-blocking. Falls Transient.
     */
    private fun leakedUpload(): SttResult {
        android.util.Log.w("WE-DIAG", "soniox stt upload 200 unparseable id — file may be leaked server-side")
        return SttResult.Failed(SttError.Transient(null))
    }

    /**
     * A create whose id will not parse. The uploaded FILE is still cleaned up by the caller's
     * finally, but a transcription resource created under an unreadable id would leak. Same
     * status-shape-only diagnostic; falls Transient.
     */
    private fun leakedCreate(): SttResult {
        android.util.Log.w("WE-DIAG", "soniox stt create unparseable id — transcription may be leaked server-side")
        return SttResult.Failed(SttError.Transient(null))
    }

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = false } // kotlinx, NOT org.json
        const val DEFAULT_MODEL = "stt-async-v5" // async multilingual; v4 auto-routes here
        private const val BASE = "https://api.soniox.com/v1"
        private const val FILES_URL = "$BASE/files"
        private const val TRANSCRIPTIONS_URL = "$BASE/transcriptions"
        private const val WAV_HEADER_BYTES = 44
        private val IN_FLIGHT = setOf("queued", "processing", "downloading", "transcribing")
        private const val COMPLETED = "completed"
        // Balance/credit markers Soniox may put under an ambiguous 401/429 (fact sheet: 402 is the
        // clean code, but the split is cheap insurance and matches the ElevenLabs lesson). TIGHTENED
        // to the specific insufficient-balance class: the old broad "balance"/"budget"/"exhausted"
        // matched incidental words (a bad-key body that merely mentions an account "balance" page),
        // mislabeling a plain INVALID_KEY as an empty wallet. These underscored error_type-class
        // markers only appear when the account genuinely has no credit.
        private val BALANCE_MARKERS = listOf("insufficient_balance", "insufficient_funds")
        // A job-failure error_type that blames the audio -> this segment's fault (BadSegment).
        private val AUDIO_ERROR_MARKERS = listOf("audio", "decode", "unsupported", "invalid_request")
    }
}
