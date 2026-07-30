package com.whispereverywhere.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RecordingStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun store(now: Long = 1_000L) =
        RecordingStore(File(tmp.root, "batch"), clock = { now })

    private fun meta(id: String, createdAtMs: Long) =
        RecordingMeta(
            id = id,
            createdAtMs = createdAtMs,
            durationMs = 3_000L,
            displayName = "clip.m4a",
            byteLength = 96_000,
        )

    @Test fun save_then_read_round_trips_every_field() {
        val s = store()
        val m = meta("a", 500L).copy(
            status = BatchStatus.Done,
            engineUsed = EngineUsed.LOCAL,
            language = "en",
            chunkPlan = listOf(ChunkEntry(0, 0, 96_000, hardCut = false, ChunkStatus.Done, "hello")),
        )
        s.save(m)
        assertEquals(m, s.read("a"))
    }

    @Test fun list_is_newest_first_and_ignores_dirs_without_a_manifest() {
        val s = store()
        s.save(meta("old", 100L))
        s.save(meta("new", 900L))
        File(s.dir("garbage"), "").mkdirs()   // a stray dir with no manifest.json
        assertEquals(listOf("new", "old"), s.list().map { it.id })
    }

    @Test fun read_of_a_missing_id_is_null_not_a_throw() {
        assertNull(store().read("nope"))
    }

    @Test fun delete_removes_the_whole_workspace_directory() {
        val s = store()
        s.save(meta("d", 1L))
        s.audioFile("d").writeBytes(ByteArray(10))
        assertTrue(s.dir("d").exists())
        s.delete("d")
        assertFalse("cleanup must remove audio.pcm too — no audio outlives its job", s.dir("d").exists())
    }

    @Test fun assembled_text_joins_done_chunks_in_index_order_only() {
        val s = store()
        val m = meta("j", 1L).copy(chunkPlan = listOf(
            ChunkEntry(0, 0, 10, false, ChunkStatus.Done, "one "),
            ChunkEntry(1, 10, 20, false, ChunkStatus.Pending, "SHOULD-NOT-APPEAR"),
            ChunkEntry(2, 20, 30, true,  ChunkStatus.Done, "three"),
        ))
        assertEquals("one three", s.assembledText(m))
    }

    @Test fun sweepStale_collects_workspaces_orphaned_by_a_crash() {
        // The ONLY sweep. A workspace older than STALE_MS belongs to a job whose process died
        // without cleanup (normal completion deletes eagerly); a young one may be a live job.
        val now = 3L * 24 * 60 * 60 * 1000
        val s = RecordingStore(File(tmp.root, "b"), clock = { now })
        s.save(meta("orphan", now - RecordingStore.STALE_MS - 1))
        s.save(meta("live", now - 60_000L))
        s.sweepStale()
        assertEquals(listOf("live"), s.list().map { it.id })
    }
}
