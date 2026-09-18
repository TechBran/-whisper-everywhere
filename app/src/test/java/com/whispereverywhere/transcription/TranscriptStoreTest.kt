package com.whispereverywhere.transcription

import com.whispereverywhere.transcription.speakers.Run
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TranscriptStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test fun `save then list returns newest first with preview`() {
        var now = 1_000_000L
        val store = TranscriptStore(tmp.root) { now }
        store.save(1_000_000L, "first session text")
        now = 2_000_000L
        store.save(2_000_000L, "second session text")
        val entries = store.list()
        assertEquals(2, entries.size)
        assertEquals(2_000_000L, entries[0].startedAtMs)
        assertTrue(entries[0].preview.startsWith("second session"))
    }

    @Test fun `read round-trips the saved text`() {
        val store = TranscriptStore(tmp.root) { 5L }
        store.save(5L, "hello transcription world")
        assertEquals("hello transcription world", store.read(store.list()[0]))
    }

    @Test fun `sweep evicts entries older than max age`() {
        var now = 0L
        val store = TranscriptStore(tmp.root) { now }
        store.save(0L, "ancient")
        now = TranscriptStore.MAX_AGE_MS + 1
        store.save(now, "fresh")
        store.sweep()
        val entries = store.list()
        assertEquals(1, entries.size)
        assertEquals("fresh", store.read(entries[0]))
    }

    @Test fun `sweep evicts oldest first when over the size cap`() {
        var now = 0L
        val store = TranscriptStore(tmp.root) { now }
        val big = "x".repeat(600)
        for (i in 0 until 5) {
            now = i * 1000L
            store.save(now, big)
        }
        store.sweep(maxTotalBytes = 2000L)   // fits 3 of the ~600-byte entries
        val entries = store.list()
        assertTrue(entries.size <= 3)
        // Newest survived; the evicted ones were the oldest.
        assertEquals(4000L, entries[0].startedAtMs)
    }

    @Test fun `save with a dead timestamp falls back to the clock so sweep cannot instantly evict it`() {
        // Regression pin for the 3.3.0/3.4.0 vanishing-history bug: the service zeroed
        // sessionStartMs (stats bookkeeping) BEFORE the history persist, so every session
        // saved as "0.txt" — which the very next sweep() deleted as 56 years stale. The
        // store must never accept a timestamp that self-destructs on the next sweep.
        val now = TranscriptStore.MAX_AGE_MS * 4
        val store = TranscriptStore(tmp.root) { now }
        store.save(0L, "the session the stats block tried to erase")
        store.sweep()
        val entries = store.list()
        assertEquals(1, entries.size)
        assertEquals(now, entries[0].startedAtMs)
        assertEquals("the session the stats block tried to erase", store.read(entries[0]))
    }

    @Test fun `delete removes exactly one entry`() {
        var now = 0L
        val store = TranscriptStore(tmp.root) { now }
        store.save(0L, "keep")
        now = 1000L
        store.save(1000L, "remove")
        store.delete(store.list()[0]) // newest = "remove"
        val entries = store.list()
        assertEquals(1, entries.size)
        assertEquals("keep", store.read(entries[0]))
    }

    // --- 4.10: the speaker sidecar ---------------------------------------------

    private fun runs(vararg pairs: Pair<Int, String>): List<Run> =
        pairs.map { (speaker, text) ->
            Run(seq = 0L, vadIndex = 0, text = text, speakerId = speaker.takeIf { it > 0 })
        }

    private fun sidecar(stamp: Long) = File(tmp.root, "$stamp${TranscriptStore.SIDECAR_SUFFIX}")

    @Test fun `the sidecar round-trips the runs and their speakers`() {
        val store = TranscriptStore(tmp.root) { 9L }
        store.save(
            startedAtMs = 9L,
            text = "First.\n\nSecond.",
            runs = runs(1 to "First.", 2 to "Second."),
            confirmedSpeakers = 2,
        )
        val read = store.readRuns(store.list()[0])
        assertNotNull(read)
        assertEquals(listOf("First.", "Second."), read!!.map { it.text })
        assertEquals(listOf(1, 2), read.map { it.speakerId })
    }

    @Test fun `a run nobody could attribute comes back unassigned and never as speaker one`() {
        val store = TranscriptStore(tmp.root) { 9L }
        store.save(9L, "A B", runs(0 to "A", 2 to "B"), confirmedSpeakers = 2)
        val read = store.readRuns(store.list()[0])!!
        assertNull(read[0].speakerId)
        assertEquals(2, read[1].speakerId)
    }

    @Test fun `a session with no sidecar reads back as null runs`() {
        val store = TranscriptStore(tmp.root) { 9L }
        store.save(9L, "one voice all session")
        assertNull(store.readRuns(store.list()[0]))
    }

    @Test fun `one confirmed speaker writes no sidecar at all`() {
        val store = TranscriptStore(tmp.root) { 9L }
        store.save(9L, "one voice", runs(1 to "one voice"), confirmedSpeakers = 1)
        assertFalse("a sidecar that cannot change an export is not written", sidecar(9L).exists())
        assertNull(store.readRuns(store.list()[0]))
    }

    @Test fun `an unreadable sidecar is a missing sidecar`() {
        val store = TranscriptStore(tmp.root) { 9L }
        store.save(9L, "text", runs(1 to "text"), confirmedSpeakers = 2)
        sidecar(9L).writeText("{ this is not json")
        assertNull(store.readRuns(store.list()[0]))
    }

    @Test fun `re-saving a session without speakers takes its old sidecar with it`() {
        val store = TranscriptStore(tmp.root) { 9L }
        store.save(9L, "A B", runs(1 to "A", 2 to "B"), confirmedSpeakers = 2)
        assertTrue(sidecar(9L).exists())
        store.save(9L, "A B")
        assertFalse("the sidecar must not outlive the text it described", sidecar(9L).exists())
    }

    @Test fun `the sidecar is not listed as a session of its own`() {
        val store = TranscriptStore(tmp.root) { 9L }
        store.save(9L, "A B", runs(1 to "A", 2 to "B"), confirmedSpeakers = 2)
        assertEquals(1, store.list().size)
        assertEquals(9L, store.list()[0].startedAtMs)
    }

    @Test fun `an entry weighs its transcript plus its sidecar`() {
        val store = TranscriptStore(tmp.root) { 9L }
        store.save(9L, "A B", runs(1 to "A", 2 to "B"), confirmedSpeakers = 2)
        val entry = store.list()[0]
        assertEquals(entry.file.length() + sidecar(9L).length(), entry.sizeBytes)
        assertTrue("the sidecar is part of the budget", entry.sizeBytes > entry.file.length())
    }

    @Test fun `delete removes the sidecar with its transcript`() {
        val store = TranscriptStore(tmp.root) { 9L }
        store.save(9L, "A B", runs(1 to "A", 2 to "B"), confirmedSpeakers = 2)
        store.delete(store.list()[0])
        assertFalse(sidecar(9L).exists())
        assertEquals(0, store.list().size)
    }

    @Test fun `sweep removes sidecars with the transcripts it evicts`() {
        var now = 0L
        val store = TranscriptStore(tmp.root) { now }
        store.save(0L, "ancient", runs(1 to "ancient"), confirmedSpeakers = 2)
        now = TranscriptStore.MAX_AGE_MS + 1
        store.save(now, "fresh", runs(1 to "fresh"), confirmedSpeakers = 2)
        store.sweep()
        assertFalse("an orphaned sidecar would outlive its session by two weeks", sidecar(0L).exists())
        assertTrue(sidecar(now).exists())
        assertEquals(1, store.list().size)
    }

    @Test fun `sweep over the size cap takes the oldest sidecars too`() {
        var now = 0L
        val store = TranscriptStore(tmp.root) { now }
        val big = "x".repeat(600)
        for (i in 0 until 5) {
            now = i * 1000L
            store.save(now, big, runs(1 to big), confirmedSpeakers = 2)
        }
        store.sweep(maxTotalBytes = 4000L)
        val surviving = store.list().map { it.startedAtMs }.toSet()
        for (i in 0 until 5) {
            val stamp = i * 1000L
            assertEquals(
                "sidecar of $stamp must exist iff its transcript does",
                surviving.contains(stamp),
                sidecar(stamp).exists(),
            )
        }
    }
}
