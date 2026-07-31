package com.whispereverywhere.transcription

import org.junit.Assert.assertEquals
import org.junit.Test

class SegmentOrdererTest {

    private fun text(s: String) = SegmentOutcome.Text(s)

    @Test fun in_order_results_release_immediately() {
        val o = SegmentOrderer()
        assertEquals("one", o.onResolved(0, text("one")).text)
        assertEquals("two", o.onResolved(1, text("two")).text)
    }

    @Test fun an_out_of_order_result_is_held_until_its_predecessor_lands() {
        // THE reason this class exists. Injection is a full-field read-modify-write, so releasing
        // seq 1 before seq 0 would make the next write read pre-seq-0 text and write it back —
        // silently DELETING seq 0 rather than merely reordering it.
        val o = SegmentOrderer()
        assertEquals("", o.onResolved(1, text("second")).text)
        assertEquals(1, o.pendingCount())
        assertEquals("first second", o.onResolved(0, text("first")).text)
        assertEquals(0, o.pendingCount())
    }

    @Test fun a_whole_held_run_releases_at_once_as_one_string() {
        // One injection per burst is strictly better than one per segment: fewer full-field
        // rewrites, fewer chances to clobber concurrent typing, fewer cursor jumps.
        val o = SegmentOrderer()
        o.onResolved(3, text("d"))
        o.onResolved(1, text("b"))
        o.onResolved(2, text("c"))
        assertEquals("a b c d", o.onResolved(0, text("a")).text)
    }

    @Test fun expected_empties_contribute_nothing_but_still_unblock_the_head() {
        // A silence-only segment is not a loss — it must resolve so later segments can release.
        val o = SegmentOrderer()
        assertEquals("", o.onResolved(0, SegmentOutcome.EmptyExpected).text)
        assertEquals("after", o.onResolved(1, text("after")).text)
    }

    @Test fun an_unexpected_empty_emits_the_lost_marker() {
        // Real voiced audio that came back empty IS a lost sentence and must be visible.
        val o = SegmentOrderer()
        assertEquals("[…]", o.onResolved(0, SegmentOutcome.EmptyUnexpected).text)
    }

    @Test fun consecutive_losses_collapse_to_a_single_marker() {
        // A 90-second outage must produce one ellipsis, not thirty.
        val o = SegmentOrderer()
        val sb = StringBuilder()
        repeat(5) { sb.append(o.onResolved(it.toLong(), SegmentOutcome.Lost("offline")).text) }
        assertEquals("[…]", sb.toString())
    }

    @Test fun a_loss_between_two_texts_yields_exactly_one_marker() {
        val o = SegmentOrderer()
        val sb = StringBuilder()
        sb.append(o.onResolved(0, text("before")).text)
        sb.append(o.onResolved(1, SegmentOutcome.Lost("timeout")).text)
        sb.append(o.onResolved(2, text("after")).text)
        assertEquals("before […] after", sb.toString().trim().replace(Regex("\\s+"), " "))
    }

    @Test fun skip_unblocks_a_seq_that_will_never_resolve() {
        // A merged-away or wrong-generation segment must not stall the head forever.
        val o = SegmentOrderer()
        o.onResolved(1, text("b"))
        assertEquals("b", o.skip(0).text)
    }

    @Test fun flush_releases_everything_held_and_skips_the_holes() {
        // Held text is uniquely fragile: unlike per-segment injection, the buffer deliberately
        // accumulates finished work in RAM whose only exit is this call.
        val o = SegmentOrderer()
        o.onResolved(1, text("b"))
        o.onResolved(3, text("d"))
        val r = o.flush()
        assertEquals("b d", r.text)
        assertEquals(0, o.pendingCount())
    }

    @Test fun flush_on_an_empty_orderer_is_harmless() {
        assertEquals("", SegmentOrderer().flush().text)
    }

    @Test fun a_duplicate_seq_is_ignored_rather_than_double_injected() {
        // A retry that timed out client-side but succeeded server-side would otherwise inject twice.
        val o = SegmentOrderer()
        assertEquals("one", o.onResolved(0, text("one")).text)
        assertEquals("", o.onResolved(0, text("one")).text)
    }

    @Test fun release_reports_how_many_segments_were_lost() {
        val o = SegmentOrderer()
        val r = o.onResolved(0, SegmentOutcome.Lost("offline"))
        assertEquals(1, r.lostSegments)
    }

    @Test fun a_punctuation_only_segment_attaches_without_a_stray_space() {
        // Melt-proof routing: within one released burst, "a" then "." must read "a.", not "a .".
        val o = SegmentOrderer()
        o.onResolved(1, text("."))
        assertEquals("a.", o.onResolved(0, text("a")).text)
    }
}
