package com.whispereverywhere.transcription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

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
}
