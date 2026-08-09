package com.whispereverywhere.transcription

import java.io.File

/**
 * On-device transcription history (TEXT ONLY — audio is deliberately never retained).
 *
 * One UTF-8 file per session in [dir], named "<startedAtMs>.txt". Retention is a rolling
 * buffer applied by [sweep]: entries older than [MAX_AGE_MS] are removed, then oldest-first
 * eviction until the total size fits [MAX_TOTAL_BYTES]. Long transcriptions therefore stay
 * recoverable "for a while, not forever" (user decision 2026-07-17).
 */
class TranscriptStore(
    private val dir: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    data class Entry(
        val file: File,
        val startedAtMs: Long,
        val preview: String,
        val sizeBytes: Long,
    )

    init { dir.mkdirs() }

    fun save(startedAtMs: Long, text: String): File {
        // A non-positive stamp names the file "0.txt" — which the next sweep() deletes as
        // decades stale, silently erasing the session (shipped bug: the service's stats
        // block zeroed sessionStartMs before the history persist read it). History must
        // outlive its caller's bookkeeping: fall back to the clock rather than self-destruct.
        val stamp = if (startedAtMs > 0) startedAtMs else clock()
        val f = File(dir, "$stamp.txt")
        f.writeText(text)
        return f
    }

    /** Newest first. Ignores non-conforming files. */
    fun list(): List<Entry> =
        (dir.listFiles() ?: emptyArray())
            .mapNotNull { f ->
                val ts = f.name.removeSuffix(".txt").toLongOrNull() ?: return@mapNotNull null
                Entry(
                    file = f,
                    startedAtMs = ts,
                    preview = runCatching {
                        f.bufferedReader().use { it.readText().take(120) }
                    }.getOrDefault(""),
                    sizeBytes = f.length(),
                )
            }
            .sortedByDescending { it.startedAtMs }

    fun read(entry: Entry): String = entry.file.readText()

    fun delete(entry: Entry) {
        entry.file.delete()
    }

    fun sweep(maxAgeMs: Long = MAX_AGE_MS, maxTotalBytes: Long = MAX_TOTAL_BYTES) {
        val now = clock()
        val entries = list().toMutableList()   // newest first
        // Age limit.
        entries.removeAll { e ->
            if (now - e.startedAtMs > maxAgeMs) { e.file.delete(); true } else false
        }
        // Size cap: evict oldest-first until we fit.
        var total = entries.sumOf { it.sizeBytes }
        while (total > maxTotalBytes && entries.isNotEmpty()) {
            val oldest = entries.removeAt(entries.lastIndex)
            total -= oldest.sizeBytes
            oldest.file.delete()
        }
    }

    companion object {
        /** "A short period, not forever": two weeks. */
        const val MAX_AGE_MS: Long = 14L * 24 * 60 * 60 * 1000
        /** Text is tiny — 10 MB holds months of heavy use. */
        const val MAX_TOTAL_BYTES: Long = 10L * 1024 * 1024
    }
}
