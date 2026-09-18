package com.whispereverywhere.transcription

import com.whispereverywhere.transcription.speakers.Run
import com.whispereverywhere.transcription.speakers.SpeakerLabels
import com.whispereverywhere.transcription.speakers.SpeakerRuns
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * On-device transcription history (TEXT ONLY — audio is deliberately never retained).
 *
 * One UTF-8 file per session in [dir], named "<startedAtMs>.txt". Retention is a rolling
 * buffer applied by [sweep]: entries older than [MAX_AGE_MS] are removed, then oldest-first
 * eviction until the total size fits [MAX_TOTAL_BYTES]. Long transcriptions therefore stay
 * recoverable "for a while, not forever" (user decision 2026-07-17).
 *
 * ### The speaker sidecar (4.10)
 *
 * A session that heard two or more confirmed speakers also gets "<startedAtMs>.speakers.json" —
 * its runs and their speaker ids. The `.txt` beside it is ALWAYS the label-free render (paragraph
 * breaks, no `Speaker N:`), so a user who never turns the export switch on sees exactly the file
 * 4.9 would have written plus its paragraphs; and the sidecar is what lets the switch be applied
 * at EXPORT time rather than at save time, which is the behaviour the setting's own KDoc promises
 * ("a user who flips this on wants it to be true of the transcripts they already have").
 *
 * The sidecar is a passenger, never a prerequisite: [readRuns] answers null for every session that
 * has none, and every caller renders the stored text in that case. It is deleted with its `.txt`
 * by both [delete] and [sweep], and it counts toward the size cap ([Entry.sizeBytes]) — history
 * must not be able to grow past its budget by keeping a second copy of itself.
 */
class TranscriptStore(
    private val dir: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    data class Entry(
        val file: File,
        val startedAtMs: Long,
        val preview: String,
        /** The entry's whole footprint: the transcript plus its speaker sidecar, if any. */
        val sizeBytes: Long,
    )

    init { dir.mkdirs() }

    /**
     * Persists one session.
     *
     * [text] is the transcript as it will be read back and displayed —
     * `SpeakerLabels.render(runs, Export(labels = false), …)` from the service, i.e. paragraphs
     * and no labels. [runs] and [confirmedSpeakers] are the sidecar's contents; a session with
     * fewer than [SpeakerLabels.MIN_CONFIRMED_SPEAKERS] confirmed speakers writes NO sidecar,
     * because its labelled and unlabelled renders are the same string and a file that cannot
     * change an export is a file worth not writing.
     */
    fun save(
        startedAtMs: Long,
        text: String,
        runs: List<Run>? = null,
        confirmedSpeakers: Int = 0,
    ): File {
        // A non-positive stamp names the file "0.txt" — which the next sweep() deletes as
        // decades stale, silently erasing the session (shipped bug: the service's stats
        // block zeroed sessionStartMs before the history persist read it). History must
        // outlive its caller's bookkeeping: fall back to the clock rather than self-destruct.
        val stamp = if (startedAtMs > 0) startedAtMs else clock()
        val f = File(dir, "$stamp.txt")
        f.writeText(text)
        val sidecar = sidecarFile(stamp)
        if (runs != null && runs.isNotEmpty() && confirmedSpeakers >= SpeakerLabels.MIN_CONFIRMED_SPEAKERS) {
            val payload = Sidecar(
                confirmed = confirmedSpeakers,
                runs = runs.map { SidecarRun(speaker = it.speakerId ?: UNLABELLED, text = it.text) },
            )
            runCatching { sidecar.writeText(JSON.encodeToString(Sidecar.serializer(), payload)) }
                .onFailure {
                    // The transcript is already saved; losing the sidecar costs the export
                    // switch, not the session. Never the other way round.
                    android.util.Log.w("WE-DIAG", "speaker sidecar not written (${runs.size} runs): $it")
                }
        } else if (sidecar.exists()) {
            // Re-saving a stamp whose previous save had speakers: the sidecar must not outlive
            // the text it described.
            sidecar.delete()
        }
        return f
    }

    /** Newest first. Ignores non-conforming files — the sidecars among them. */
    fun list(): List<Entry> =
        (dir.listFiles() ?: emptyArray())
            .mapNotNull { f ->
                if (!f.name.endsWith(".txt")) return@mapNotNull null
                val ts = f.name.removeSuffix(".txt").toLongOrNull() ?: return@mapNotNull null
                Entry(
                    file = f,
                    startedAtMs = ts,
                    preview = runCatching {
                        f.bufferedReader().use { it.readText().take(120) }
                    }.getOrDefault(""),
                    sizeBytes = f.length() + sidecarFile(ts).length(),
                )
            }
            .sortedByDescending { it.startedAtMs }

    fun read(entry: Entry): String = entry.file.readText()

    /**
     * This session's runs, or null when it has no usable sidecar — which is every one-speaker
     * session, every session recorded before 4.10, and every session whose sidecar is unreadable.
     *
     * **A non-null answer means the session confirmed at least [SpeakerLabels.MIN_CONFIRMED_SPEAKERS]
     * speakers**, checked here against the sidecar's own `confirmed` rather than assumed from the
     * file's existence. That is what lets a caller render these runs with
     * `confirmedCount = SpeakerLabels.MIN_CONFIRMED_SPEAKERS` and no second read of the file: the
     * §2 table's only threshold is "fewer than two", and this method has already answered it.
     *
     * The runs come back with no [Run.seq] and no [Run.vadIndex]: nothing can assign a saved
     * transcript again, and the only thing a caller does with these is render them.
     * [Run.speakerId] is null for a run that was never attributed, exactly as it was live.
     */
    fun readRuns(entry: Entry): List<Run>? {
        val f = sidecarFile(entry.startedAtMs)
        if (!f.isFile) return null
        val parsed = runCatching { JSON.decodeFromString(Sidecar.serializer(), f.readText()) }
            .getOrElse {
                android.util.Log.w("WE-DIAG", "speaker sidecar unreadable: $it")
                return null
            }
        if (parsed.runs.isEmpty()) return null
        if (parsed.confirmed < SpeakerLabels.MIN_CONFIRMED_SPEAKERS) return null
        return parsed.runs.map {
            Run(
                seq = 0L,
                vadIndex = SpeakerRuns.NO_VAD_INDEX,
                text = it.text,
                speakerId = it.speaker.takeIf { id -> id > 0 },
            )
        }
    }

    fun delete(entry: Entry) {
        entry.file.delete()
        sidecarFile(entry.startedAtMs).delete()
    }

    fun sweep(maxAgeMs: Long = MAX_AGE_MS, maxTotalBytes: Long = MAX_TOTAL_BYTES) {
        val now = clock()
        val entries = list().toMutableList()   // newest first
        // Age limit.
        entries.removeAll { e ->
            if (now - e.startedAtMs > maxAgeMs) { delete(e); true } else false
        }
        // Size cap: evict oldest-first until we fit.
        var total = entries.sumOf { it.sizeBytes }
        while (total > maxTotalBytes && entries.isNotEmpty()) {
            val oldest = entries.removeAt(entries.lastIndex)
            total -= oldest.sizeBytes
            delete(oldest)
        }
    }

    private fun sidecarFile(startedAtMs: Long): File = File(dir, "$startedAtMs$SIDECAR_SUFFIX")

    /** The sidecar's shape, and the whole of it. kotlinx.serialization — org.json is banned here. */
    @Serializable
    private data class Sidecar(val confirmed: Int, val runs: List<SidecarRun>)

    @Serializable
    private data class SidecarRun(val speaker: Int, val text: String)

    companion object {
        /** "A short period, not forever": two weeks. */
        const val MAX_AGE_MS: Long = 14L * 24 * 60 * 60 * 1000
        /** Text is tiny — 10 MB holds months of heavy use. */
        const val MAX_TOTAL_BYTES: Long = 10L * 1024 * 1024

        /** `<startedAtMs>.speakers.json`, beside `<startedAtMs>.txt`. */
        const val SIDECAR_SUFFIX: String = ".speakers.json"

        /** A run nobody could attribute. 0 is the tracker's own "unlabelled", never speaker 1. */
        private const val UNLABELLED: Int = 0

        private val JSON = Json { ignoreUnknownKeys = true }
    }
}
