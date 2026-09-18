package com.whispereverywhere.transcription.speakers

import com.whispereverywhere.text.TextJoin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `SpeakerRuns` — spans to runs, and the two patches an assignment applies (plan Task 5 step 1).
 *
 * The load-bearing test in this file is [spans_that_do_not_reproduce_the_text_lose_their_labels]:
 * it is what makes "one speaker all session = today's output byte for byte" a property of the
 * TYPE rather than of whisper's cleaning behaving the same per segment as it does per chunk.
 */
class SpeakerRunsTest {

    private fun span(index: Int, text: String) = SpeakerSpan(vadIndex = index, text = text)

    // ------------------------------------------------------------------ of()

    @Test fun no_spans_is_one_plain_run_carrying_the_whole_chunk() {
        val runs = SpeakerRuns.of(seq = 7, text = "Hello world.", spans = null)
        assertEquals(1, runs.size)
        assertEquals(Run(seq = 7, vadIndex = SpeakerRuns.NO_VAD_INDEX, text = "Hello world."), runs[0])
        assertNull("a plain run is never assigned", runs[0].speakerId)
    }

    @Test fun an_empty_span_list_is_also_one_plain_run() {
        val runs = SpeakerRuns.of(seq = 1, text = "Hello world.", spans = emptyList())
        assertEquals(listOf(Run(1, SpeakerRuns.NO_VAD_INDEX, "Hello world.")), runs)
    }

    @Test fun blank_text_yields_no_runs_at_all() {
        assertEquals(emptyList<Run>(), SpeakerRuns.of(seq = 1, text = "   ", spans = null))
        assertEquals(
            emptyList<Run>(),
            SpeakerRuns.of(seq = 1, text = "", spans = listOf(span(0, "anything"))),
        )
    }

    @Test fun one_run_per_span_in_text_order_keeping_the_vad_index() {
        val runs = SpeakerRuns.of(
            seq = 12,
            text = "Hello there. Hi, how are you?",
            spans = listOf(span(0, "Hello there."), span(1, "Hi, how are you?")),
        )
        assertEquals(
            listOf(
                Run(12, 0, "Hello there."),
                Run(12, 1, "Hi, how are you?"),
            ),
            runs,
        )
    }

    @Test fun the_runs_of_a_chunk_join_back_to_exactly_its_committed_text() {
        val text = "Hello there. Hi, how are you?"
        val runs = SpeakerRuns.of(12, text, listOf(span(0, "Hello there."), span(1, "Hi, how are you?")))
        assertEquals(text, TextJoin.assemble(runs.map { it.text }))
    }

    @Test fun spans_that_do_not_reproduce_the_text_lose_their_labels_and_keep_the_text() {
        // The per-segment clean kept a marker the whole-chunk clean removed: the spans no longer
        // add up to the committed text, so this chunk renders as plain text and cannot be
        // labelled. Text is the product; a label is a garnish.
        val runs = SpeakerRuns.of(
            seq = 3,
            text = "Hello there.",
            spans = listOf(span(0, "Hello"), span(1, "there. [noise")),
        )
        assertEquals(listOf(Run(3, SpeakerRuns.NO_VAD_INDEX, "Hello there.")), runs)
    }

    @Test fun spans_that_all_clean_away_fall_back_to_the_plain_run() {
        val runs = SpeakerRuns.of(seq = 4, text = "Hello.", spans = listOf(span(0, "   "), span(1, "")))
        assertEquals(listOf(Run(4, SpeakerRuns.NO_VAD_INDEX, "Hello.")), runs)
    }

    // ------------------------------------------------------------------ applyAssignment()

    @Test fun an_assignment_stamps_each_run_from_its_own_vad_index() {
        val runs = SpeakerRuns.of(5, "A B", listOf(span(0, "A"), span(1, "B")))
        SpeakerRuns.applyAssignment(runs, seq = 5, ids = listOf(2, 1))
        assertEquals(listOf(2, 1), runs.map { it.speakerId })
    }

    @Test fun an_assignment_never_touches_another_chunks_runs() {
        val mine = SpeakerRuns.of(5, "A", listOf(span(0, "A")))
        val other = SpeakerRuns.of(6, "B", listOf(span(0, "B")))
        val all = mine + other
        SpeakerRuns.applyAssignment(all, seq = 5, ids = listOf(4))
        assertEquals(4, mine[0].speakerId)
        assertNull(other[0].speakerId)
    }

    @Test fun the_trackers_unlabelled_zero_is_never_written_as_a_speaker() {
        val runs = SpeakerRuns.of(5, "A B", listOf(span(0, "A"), span(1, "B")))
        SpeakerRuns.applyAssignment(runs, seq = 5, ids = listOf(0, 3))
        assertNull("0 is not a speaker — the run inherits at render time", runs[0].speakerId)
        assertEquals(3, runs[1].speakerId)
    }

    @Test fun an_index_past_the_end_of_a_stale_assignment_is_ignored() {
        val runs = SpeakerRuns.of(5, "A B", listOf(span(0, "A"), span(3, "B")))
        SpeakerRuns.applyAssignment(runs, seq = 5, ids = listOf(1, 2))
        assertEquals(1, runs[0].speakerId)
        assertNull(runs[1].speakerId)
    }

    @Test fun a_plain_run_can_never_be_assigned() {
        val runs = SpeakerRuns.of(5, "A", spans = null)
        SpeakerRuns.applyAssignment(runs, seq = 5, ids = listOf(1, 2, 3))
        assertNull(runs[0].speakerId)
    }

    // ------------------------------------------------------------------ applyRemap()

    @Test fun a_remap_moves_every_run_of_the_session_including_earlier_chunks() {
        val first = SpeakerRuns.of(1, "A", listOf(span(0, "A")))
        val second = SpeakerRuns.of(2, "B", listOf(span(0, "B")))
        val all = first + second
        SpeakerRuns.applyAssignment(all, 1, listOf(3))
        SpeakerRuns.applyAssignment(all, 2, listOf(1))
        SpeakerRuns.applyRemap(all, mapOf(3 to 1))
        assertEquals(listOf(1, 1), all.map { it.speakerId })
    }

    @Test fun a_remap_is_idempotent_and_leaves_unassigned_runs_alone() {
        val runs = SpeakerRuns.of(1, "A B", listOf(span(0, "A"), span(1, "B")))
        SpeakerRuns.applyAssignment(runs, 1, listOf(2, 0))
        SpeakerRuns.applyRemap(runs, mapOf(2 to 1))
        SpeakerRuns.applyRemap(runs, mapOf(2 to 1))
        assertEquals(1, runs[0].speakerId)
        assertNull(runs[1].speakerId)
    }

    @Test fun an_empty_remap_changes_nothing() {
        val runs = SpeakerRuns.of(1, "A", listOf(span(0, "A")))
        SpeakerRuns.applyAssignment(runs, 1, listOf(5))
        SpeakerRuns.applyRemap(runs, emptyMap())
        assertEquals(5, runs[0].speakerId)
    }
}
