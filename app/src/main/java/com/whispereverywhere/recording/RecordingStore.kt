package com.whispereverywhere.recording

import android.content.Context
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The TRANSIENT decode workspace: one directory per in-flight batch job under [root], each holding
 * audio.pcm (decoded 16 kHz mono PCM16LE) and manifest.json (a [RecordingMeta]).
 *
 * NOT a library. Nothing here outlives its job: the service deletes the workspace on Done and on
 * user dismissal, and [sweepStale] collects anything orphaned by a crash. In production [root] is
 * ONLY ever cacheDir/batch via the [forApp] factory — cache is non-backed-up and OS-clearable,
 * which is exactly the contract a transient workspace wants. The raw [root] ctor exists for unit
 * tests.
 *
 * Separate from TranscriptStore on purpose: that store is TEXT-ONLY and keeps its "audio never
 * retained" contract — the finished transcript is saved THERE (Task 7), while this directory and
 * its audio are deleted.
 */
class RecordingStore(
    private val root: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    init { root.mkdirs() }

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    // dir(id) is a PURE path accessor — no mkdirs side effect. It must stay pure because
    // delete()'s contract ("the whole workspace directory is gone") is observed by calling
    // dir(id).exists() again after delete(); an auto-creating dir() would make that assertion
    // unsatisfiable by construction (deviation from the plan's literal dir(), which mkdirs()'d
    // unconditionally — see RecordingStoreTest.delete_removes_the_whole_workspace_directory and
    // the task report). The two write paths that need the directory to exist ensure it themselves.
    fun dir(id: String): File = File(root, id)
    fun audioFile(id: String): File = File(dir(id).also { it.mkdirs() }, "audio.pcm")
    private fun manifestFile(id: String): File = File(dir(id), "manifest.json")

    fun save(meta: RecordingMeta) {
        // Whole-file write of a tiny manifest; last write wins. The per-chunk checkpoint in
        // BatchTranscriber calls this after each chunk, so a killed job resumes from the last save.
        dir(meta.id).mkdirs()
        manifestFile(meta.id).writeText(json.encodeToString(RecordingMeta.serializer(), meta))
    }

    fun read(id: String): RecordingMeta? {
        val f = manifestFile(id)
        if (!f.exists()) return null
        return runCatching { json.decodeFromString(RecordingMeta.serializer(), f.readText()) }.getOrNull()
    }

    /** Newest first. Directories without a readable manifest are ignored. */
    fun list(): List<RecordingMeta> =
        (root.listFiles() ?: emptyArray())
            .filter { it.isDirectory }
            .mapNotNull { read(it.name) }
            .sortedByDescending { it.createdAtMs }

    /** Recursively removes the whole <uuid>/ directory — audio.pcm included. */
    fun delete(id: String) { File(root, id).deleteRecursively() }

    /**
     * The transcript so far: Done chunks, in index order, joined with ONE space.
     *
     * The join is " " and never "" because chunk text arrives TRIMMED — TranscriptText.clean
     * collapses whitespace runs and .trim()s the result (TranscriptText.kt:27-31), so a ""
     * join would glue chunk N's last word to chunk N+1's first word ("…meeting toMorrow…").
     * Blank chunks (silence) are dropped so they cannot double the spacing.
     */
    fun assembledText(meta: RecordingMeta): String =
        meta.chunkPlan.sortedBy { it.index }
            .filter { it.status == ChunkStatus.Done && it.text.isNotBlank() }
            .joinToString(" ") { it.text.trim() }

    /**
     * The only sweep: collect workspaces orphaned by a crash. Normal completion deletes eagerly;
     * anything older than [STALE_MS] belongs to a process that died mid-job.
     *
     * Two kinds of orphan, both reaped here:
     *  - a directory WITH a manifest older than the window (a job that saved its manifest, then died);
     *  - a directory WITHOUT a readable manifest older than the window, by directory mtime. This is
     *    the decode-phase orphan: the service writes audio.pcm (up to hundreds of MB) BEFORE it
     *    writes the manifest, so a crash mid-decode leaves the largest footprint with no manifest.
     *    [list] intentionally ignores manifest-less dirs, so this sweep is the ONLY path that reaps
     *    them; without the mtime branch they survived every future sweep. The age gate protects a
     *    live job — an in-flight decode is far younger than [STALE_MS].
     */
    fun sweepStale(maxAgeMs: Long = STALE_MS) {
        val now = clock()
        (root.listFiles() ?: emptyArray())
            .filter { it.isDirectory }
            .forEach { d ->
                val meta = read(d.name)
                if (meta != null) {
                    if (now - meta.createdAtMs > maxAgeMs) delete(meta.id)
                } else if (now - d.lastModified() > maxAgeMs) {
                    d.deleteRecursively()
                }
            }
    }

    companion object {
        /** The subdirectory name under cacheDir. Centralized so it can't drift. */
        const val DIR_NAME = "batch"

        /** A workspace this old was orphaned by a crash — no live job runs for a day. */
        const val STALE_MS: Long = 24L * 60 * 60 * 1000

        /**
         * The ONLY production entry point. Hard-codes cacheDir/batch — non-backed-up and
         * OS-clearable, the right contract for a transient workspace. EVERY caller — the service
         * and the ViewModel — uses this; passing a bare File is a unit-test-only affordance.
         */
        fun forApp(context: Context, clock: () -> Long = System::currentTimeMillis): RecordingStore =
            RecordingStore(File(context.cacheDir, DIR_NAME), clock)
    }
}
