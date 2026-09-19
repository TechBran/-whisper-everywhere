package com.whispereverywhere.transcription.speakers

import com.whispereverywhere.text.TextJoin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `SpeakerRuns` — spans to runs, and the three patches a session's runs are corrected by (plan
 * Task 5 step 1; the per-window one is spike session 6).
 *
 * The load-bearing test in this file is [spans_that_do_not_reproduce_the_text_lose_their_labels]:
 * it is what makes "one speaker all session = today's output byte for byte" a property of the
 * TYPE rather than of whisper's cleaning behaving the same per segment as it does per chunk.
 *
 * Since spike session 4 the index a run carries is its FINGERPRINT WINDOW, not its VAD segment,
 * and the two differ exactly when a long segment was split. The gate above is unchanged by that
 * and is asserted to be: a split chunk whose spans do not add up to its text still keeps its
 * text and loses its labels.
 */
class SpeakerRunsTest {

    private fun span(index: Int, text: String) = SpeakerSpan(windowIndex = index, text = text)

    // ------------------------------------------------------------------ of()

    @Test fun no_spans_is_one_plain_run_carrying_the_whole_chunk() {
        val runs = SpeakerRuns.of(seq = 7, text = "Hello world.", spans = null)
        assertEquals(1, runs.size)
        assertEquals(Run(seq = 7, windowIndex = SpeakerRuns.NO_WINDOW_INDEX, text = "Hello world."), runs[0])
        assertNull("a plain run is never assigned", runs[0].speakerId)
    }

    @Test fun an_empty_span_list_is_also_one_plain_run() {
        val runs = SpeakerRuns.of(seq = 1, text = "Hello world.", spans = emptyList())
        assertEquals(listOf(Run(1, SpeakerRuns.NO_WINDOW_INDEX, "Hello world.")), runs)
    }

    @Test fun blank_text_yields_no_runs_at_all() {
        assertEquals(emptyList<Run>(), SpeakerRuns.of(seq = 1, text = "   ", spans = null))
        assertEquals(
            emptyList<Run>(),
            SpeakerRuns.of(seq = 1, text = "", spans = listOf(span(0, "anything"))),
        )
    }

    @Test fun one_run_per_span_in_text_order_keeping_the_window_index() {
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
        assertEquals(listOf(Run(3, SpeakerRuns.NO_WINDOW_INDEX, "Hello there.")), runs)
    }

    @Test fun spans_that_all_clean_away_fall_back_to_the_plain_run() {
        val runs = SpeakerRuns.of(seq = 4, text = "Hello.", spans = listOf(span(0, "   "), span(1, "")))
        assertEquals(listOf(Run(4, SpeakerRuns.NO_WINDOW_INDEX, "Hello.")), runs)
    }

    // ------------------------------------------------------------------ applyAssignment()

    @Test fun an_assignment_stamps_each_run_from_its_own_window_index() {
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

    // ------------------------------------- the split chunk (spike session 4)

    @Test fun two_spans_cut_out_of_ONE_vad_segment_take_the_ids_of_their_OWN_windows() {
        // FAILURE MODE B's payoff, at the last step of the pipeline. The chunk's ids arrive one
        // per WINDOW, and a long segment is several windows — so spans 0 and 1, which under the
        // pre-session-4 rule shared a vad index and were a single merged span, now carry one
        // speaker each. Reading `ids` positionally by VAD segment here would put speaker 1's
        // number on both sentences, which is precisely the bug the split exists to remove.
        val runs = SpeakerRuns.of(
            seq = 8,
            text = "Hello there. Hi, how are you?",
            spans = listOf(span(0, "Hello there."), span(1, "Hi, how are you?")),
        )
        SpeakerRuns.applyAssignment(runs, seq = 8, ids = listOf(1, 2))
        assertEquals(listOf(1, 2), runs.map { it.speakerId })
        assertEquals(listOf(0, 1), runs.map { it.windowIndex })
    }

    @Test fun a_split_chunk_whose_spans_do_not_reproduce_its_text_still_loses_only_its_labels() {
        // The text-exactness gate is untouched by the split, and that is worth an assertion of
        // its own: more windows means more per-segment cleans, so more chances for the join to
        // disagree with the whole-chunk clean by a character. Text is the product either way.
        val runs = SpeakerRuns.of(
            seq = 9,
            text = "Hello there. Hi, how are you?",
            spans = listOf(span(0, "Hello there."), span(1, "Hi, how are you? [noise")),
        )
        assertEquals(
            listOf(Run(9, SpeakerRuns.NO_WINDOW_INDEX, "Hello there. Hi, how are you?")),
            runs,
        )
        SpeakerRuns.applyAssignment(runs, seq = 9, ids = listOf(1, 2))
        assertNull("a plain run can never be assigned, split or not", runs[0].speakerId)
    }

    @Test fun a_remap_reaches_every_window_of_a_split_segment_not_just_the_first() {
        // The merge pass decides a speaker was never a separate person, and the ids it corrects
        // may all sit inside ONE long VAD segment now — three windows of the same six seconds.
        val runs = SpeakerRuns.of(
            seq = 2,
            text = "A B C",
            spans = listOf(span(0, "A"), span(1, "B"), span(2, "C")),
        )
        SpeakerRuns.applyAssignment(runs, seq = 2, ids = listOf(1, 3, 3))
        SpeakerRuns.applyRemap(runs, mapOf(3 to 1))
        assertEquals(listOf(1, 1, 1), runs.map { it.speakerId })
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

    // ------------------------------------------------------------------ applyWindowLabels()

    @Test fun window_labels_split_an_id_that_swallowed_two_voices() {
        // The correction no id-level map can make (spike session 6): the online matcher gave
        // every window of a conversation the same number, so a remap keyed on that number could
        // only move all of them together. The retrospective pass answers per WINDOW.
        val first = SpeakerRuns.of(1, "A B", listOf(span(0, "A"), span(1, "B")))
        val second = SpeakerRuns.of(2, "C D", listOf(span(0, "C"), span(1, "D")))
        val all = first + second
        SpeakerRuns.applyAssignment(all, 1, listOf(1, 1))
        SpeakerRuns.applyAssignment(all, 2, listOf(1, 1))
        assertEquals(listOf(1, 1, 1, 1), all.map { it.speakerId })

        SpeakerRuns.applyWindowLabels(
            all,
            mapOf(
                WindowKey(1L, 0) to 1,
                WindowKey(1L, 1) to 1,
                WindowKey(2L, 0) to 2,
                WindowKey(2L, 1) to 2,
            ),
        )

        assertEquals(listOf(1, 1, 2, 2), all.map { it.speakerId })
    }

    @Test fun a_window_the_pass_did_not_look_at_keeps_the_label_it_has() {
        // Windows older than SpeakerReclusterer.MAX_RECLUSTER_FINGERPRINTS are absent from the
        // map. Absent must mean "unchanged", which is the whole reason the cap is safe.
        val runs = SpeakerRuns.of(1, "A B", listOf(span(0, "A"), span(1, "B")))
        SpeakerRuns.applyAssignment(runs, 1, listOf(3, 3))
        SpeakerRuns.applyWindowLabels(runs, mapOf(WindowKey(1L, 1) to 2))
        assertEquals(listOf(3, 2), runs.map { it.speakerId })
    }

    @Test fun a_plain_run_and_a_zero_label_are_both_left_alone() {
        // A run with no window behind it (cloud, the NPU tier, a chunk whose spans could not
        // reproduce its text) can never be labelled, and 0 is "unattributed", never speaker 1.
        val plain = SpeakerRuns.of(4, "whole chunk", spans = null)
        SpeakerRuns.applyWindowLabels(plain, mapOf(WindowKey(4L, SpeakerRuns.NO_WINDOW_INDEX) to 2))
        assertNull(plain.single().speakerId)

        val runs = SpeakerRuns.of(5, "A", listOf(span(0, "A")))
        SpeakerRuns.applyAssignment(runs, 5, listOf(1))
        SpeakerRuns.applyWindowLabels(runs, mapOf(WindowKey(5L, 0) to 0))
        assertEquals(1, runs.single().speakerId)
    }

    @Test fun window_labels_are_idempotent_and_an_empty_map_changes_nothing() {
        val runs = SpeakerRuns.of(1, "A", listOf(span(0, "A")))
        SpeakerRuns.applyAssignment(runs, 1, listOf(1))
        SpeakerRuns.applyWindowLabels(runs, mapOf(WindowKey(1L, 0) to 2))
        SpeakerRuns.applyWindowLabels(runs, mapOf(WindowKey(1L, 0) to 2))
        assertEquals(2, runs.single().speakerId)
        SpeakerRuns.applyWindowLabels(runs, emptyMap())
        assertEquals(2, runs.single().speakerId)
    }

    @Test fun window_labels_are_keyed_on_the_chunk_too_so_two_chunks_never_collide() {
        // Every chunk numbers its windows from 0, so a map keyed on the index alone would put
        // chunk 2's speaker on chunk 1's first sentence.
        val all = SpeakerRuns.of(1, "A", listOf(span(0, "A"))) +
            SpeakerRuns.of(2, "B", listOf(span(0, "B")))
        SpeakerRuns.applyWindowLabels(all, mapOf(WindowKey(2L, 0) to 2))
        assertNull(all[0].speakerId)
        assertEquals(2, all[1].speakerId)
    }

    // ------------------------------- the NPU tier's one run (4.10, the Fold6 defect)

    @Test fun a_whole_chunk_run_learns_its_window_and_its_speaker_after_the_text() {
        // The NPU chunk arrives with no spans, so `of` gives it one run at NO_WINDOW_INDEX. The
        // embed thread answers ~60 ms later with the dominant window and its id, and both are
        // written here — the index because the retrospective pass addresses runs by window.
        val runs = SpeakerRuns.of(1, "the whole chunk", spans = null)
        assertEquals(SpeakerRuns.NO_WINDOW_INDEX, runs.single().windowIndex)
        SpeakerRuns.applyWholeChunk(runs, seq = 1, windowIndex = 2, id = 3)
        assertEquals(2, runs.single().windowIndex)
        assertEquals(3, runs.single().speakerId)
        // …and that is exactly what makes the second look reach it.
        SpeakerRuns.applyWindowLabels(runs, mapOf(WindowKey(1L, 2) to 1))
        assertEquals(1, runs.single().speakerId)
    }

    @Test fun a_whole_chunk_stamp_never_touches_a_run_that_already_has_a_window() {
        // The CPU tier's runs are born with their window indices. This route must not reach
        // them — a chunk labelled per sentence collapsing to one speaker is the exact regression
        // the geometry route exists to avoid — and the same guard makes a second call a no-op.
        val cpu = SpeakerRuns.of(1, "A B", listOf(span(0, "A"), span(1, "B")))
        SpeakerRuns.applyAssignment(cpu, 1, listOf(1, 2))
        SpeakerRuns.applyWholeChunk(cpu, seq = 1, windowIndex = 0, id = 9)
        assertEquals(listOf(0, 1), cpu.map { it.windowIndex })
        assertEquals(listOf(1, 2), cpu.map { it.speakerId })
    }

    @Test fun a_whole_chunk_stamp_is_scoped_to_its_own_chunk() {
        val all = SpeakerRuns.of(1, "A", spans = null) + SpeakerRuns.of(2, "B", spans = null)
        SpeakerRuns.applyWholeChunk(all, seq = 2, windowIndex = 0, id = 4)
        assertEquals(SpeakerRuns.NO_WINDOW_INDEX, all[0].windowIndex)
        assertNull(all[0].speakerId)
        assertEquals(0, all[1].windowIndex)
        assertEquals(4, all[1].speakerId)
    }

    @Test fun an_unattributed_whole_chunk_still_gets_its_window_but_never_speaker_one() {
        // 0 is the tracker's "could not attribute this at all" and is dropped at the door, as in
        // applyAssignment. The INDEX is written anyway: the run must stay nameable so a later
        // pass can label it, which is the whole reason the index is carried.
        val runs = SpeakerRuns.of(1, "A", spans = null)
        SpeakerRuns.applyWholeChunk(runs, seq = 1, windowIndex = 1, id = 0)
        assertEquals(1, runs.single().windowIndex)
        assertNull(runs.single().speakerId)
    }

    @Test fun a_negative_window_index_is_the_caller_saying_it_has_no_answer() {
        val runs = SpeakerRuns.of(1, "A", spans = null)
        SpeakerRuns.applyWholeChunk(runs, seq = 1, windowIndex = -1, id = 2)
        assertEquals(SpeakerRuns.NO_WINDOW_INDEX, runs.single().windowIndex)
        assertNull(runs.single().speakerId)
    }
}
