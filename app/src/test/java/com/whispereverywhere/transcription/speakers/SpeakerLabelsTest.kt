package com.whispereverywhere.transcription.speakers

import com.whispereverywhere.text.TextJoin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE SPEC'S §2 TABLE, cell by cell (plan Task 5).
 *
 * The strings below are written out in full rather than assembled from constants: this file is
 * where the owner's rules are legible, and a test that builds its expectation the same way the
 * code does can only prove the code is self-consistent.
 */
class SpeakerLabelsTest {

    private fun run(seq: Long, vad: Int, text: String, speaker: Int?) =
        Run(seq = seq, windowIndex = vad, text = text, speakerId = speaker)

    /** THE EXAMPLE: two speakers, three runs, the second speaker interrupting and the first back. */
    private fun twoSpeakersThreeRuns() = listOf(
        run(1, 0, "Hello there.", 1),
        run(1, 1, "Hi, how are you?", 2),
        run(2, 0, "I am well.", 1),
    )

    private val labelled =
        "Speaker 1: Hello there.\n\nSpeaker 2: Hi, how are you?\n\nSpeaker 1: I am well."
    private val broken = "Hello there.\n\nHi, how are you?\n\nI am well."
    private val plain = "Hello there. Hi, how are you? I am well."

    // ------------------------------------------------------------------ §2, one speaker

    @Test fun under_two_confirmed_speakers_every_mode_is_todays_output_byte_for_byte() {
        val runs = twoSpeakersThreeRuns()
        for (confirmed in 0..1) {
            for (mode in listOf(
                SpeakerLabels.Mode.Panel(labels = true),
                SpeakerLabels.Mode.Panel(labels = false),
                SpeakerLabels.Mode.Field,
                SpeakerLabels.Mode.Export(labels = true),
                SpeakerLabels.Mode.Export(labels = false),
            )) {
                assertEquals(
                    "confirmed=$confirmed mode=$mode must be 4.9's output",
                    plain,
                    SpeakerLabels.render(runs, mode, confirmedCount = confirmed),
                )
            }
        }
    }

    @Test fun and_that_output_is_exactly_what_textjoin_would_have_produced() {
        val runs = twoSpeakersThreeRuns()
        assertEquals(
            TextJoin.assemble(runs.map { it.text }),
            SpeakerLabels.render(runs, SpeakerLabels.Mode.Panel(labels = true), confirmedCount = 1),
        )
    }

    @Test fun a_single_speaker_session_is_one_paragraph_with_no_label_even_once_confirmed() {
        // Not reachable in production (the latch needs two), but the rule is "a change starts a
        // paragraph" and one speaker never changes: the shape must not depend on the count alone.
        val runs = listOf(run(1, 0, "One", 1), run(2, 0, "voice only.", 1))
        assertEquals(
            "Speaker 1: One voice only.",
            SpeakerLabels.render(runs, SpeakerLabels.Mode.Panel(labels = true), confirmedCount = 2),
        )
    }

    // ------------------------------------------------------------------ §2, two or more

    @Test fun the_panel_labels_every_paragraph_including_the_first() {
        assertEquals(
            labelled,
            SpeakerLabels.render(twoSpeakersThreeRuns(), SpeakerLabels.Mode.Panel(labels = true), 2),
        )
    }

    @Test fun the_panel_before_the_latch_flips_has_neither_breaks_nor_labels() {
        assertEquals(
            plain,
            SpeakerLabels.render(twoSpeakersThreeRuns(), SpeakerLabels.Mode.Panel(labels = true), 1),
        )
    }

    @Test fun the_relabel_is_the_same_runs_rendered_with_the_labels_turned_on() {
        // What the latch flip does to the panel, stated as the two renders it sits between: the
        // FIRST paragraph gains `Speaker 1: ` too, which is the half the owner asked for.
        val runs = twoSpeakersThreeRuns()
        val before = SpeakerLabels.render(runs, SpeakerLabels.Mode.Panel(labels = false), 2)
        val after = SpeakerLabels.render(runs, SpeakerLabels.Mode.Panel(labels = true), 2)
        assertEquals(broken, before)
        assertEquals(labelled, after)
        assertTrue("the first paragraph is relabelled", after.startsWith("Speaker 1: "))
    }

    @Test fun a_field_gets_the_paragraphs_and_never_a_label() {
        assertEquals(broken, SpeakerLabels.render(twoSpeakersThreeRuns(), SpeakerLabels.Mode.Field, 2))
        assertFalse(
            SpeakerLabels.render(twoSpeakersThreeRuns(), SpeakerLabels.Mode.Field, 9).contains("Speaker"),
        )
    }

    @Test fun an_export_breaks_always_and_labels_only_behind_the_switch() {
        val runs = twoSpeakersThreeRuns()
        assertEquals(broken, SpeakerLabels.render(runs, SpeakerLabels.Mode.Export(labels = false), 2))
        assertEquals(labelled, SpeakerLabels.render(runs, SpeakerLabels.Mode.Export(labels = true), 2))
    }

    // ------------------------------------------------------------------ the numbering

    @Test fun merged_ids_are_compacted_to_what_the_user_can_count() {
        // The tracker absorbed speaker 2, so the session's live ids are 1 and 3. The user must
        // still read 1 and 2 — a "Speaker 3:" in a two-voice conversation is a bug report.
        val runs = listOf(
            run(1, 0, "First.", 1),
            run(1, 1, "Second.", 3),
            run(2, 0, "First again.", 1),
        )
        assertEquals(
            "Speaker 1: First.\n\nSpeaker 2: Second.\n\nSpeaker 1: First again.",
            SpeakerLabels.render(runs, SpeakerLabels.Mode.Panel(labels = true), 2),
        )
    }

    @Test fun a_returning_speaker_keeps_its_number() {
        val runs = listOf(
            run(1, 0, "A one.", 5),
            run(2, 0, "B one.", 9),
            run(3, 0, "C one.", 7),
            run(4, 0, "A two.", 5),
        )
        assertEquals(
            "Speaker 1: A one.\n\nSpeaker 2: B one.\n\nSpeaker 3: C one.\n\nSpeaker 1: A two.",
            SpeakerLabels.render(runs, SpeakerLabels.Mode.Panel(labels = true), 3),
        )
    }

    @Test fun an_unassigned_run_inherits_the_previous_speaker_rather_than_opening_a_paragraph() {
        val runs = listOf(
            run(1, 0, "Assigned.", 1),
            run(2, -1, "Still waiting for its embedding.", null),
            run(3, 0, "Someone else.", 2),
        )
        assertEquals(
            "Speaker 1: Assigned. Still waiting for its embedding.\n\nSpeaker 2: Someone else.",
            SpeakerLabels.render(runs, SpeakerLabels.Mode.Panel(labels = true), 2),
        )
    }

    @Test fun an_unassigned_run_at_the_head_of_the_session_is_speaker_one() {
        val runs = listOf(
            run(1, -1, "Nobody has been identified yet.", null),
            run(2, 0, "A second voice.", 2),
        )
        assertEquals(
            "Speaker 1: Nobody has been identified yet.\n\nSpeaker 2: A second voice.",
            SpeakerLabels.render(runs, SpeakerLabels.Mode.Panel(labels = true), 2),
        )
    }

    // ------------------------------------------------------------------ the joining rules

    @Test fun runs_inside_a_paragraph_are_joined_by_the_apps_one_melt_policy() {
        val runs = listOf(
            run(1, 0, "Hello", 1),
            run(1, 1, ".", 1),
            run(2, 0, "你好", 1),
            run(2, 1, "世界", 1),
            run(3, 0, "Bye.", 2),
        )
        assertEquals(
            "Hello. 你好世界\n\nBye.",
            SpeakerLabels.render(runs, SpeakerLabels.Mode.Field, 2),
        )
    }

    @Test fun a_paragraph_boundary_never_gets_a_leading_space() {
        val runs = listOf(run(1, 0, "One", 1), run(2, 0, "Two", 2))
        val unlabelled = SpeakerLabels.render(runs, SpeakerLabels.Mode.Field, 2)
        assertEquals("One\n\nTwo", unlabelled)
        val withLabels = SpeakerLabels.render(runs, SpeakerLabels.Mode.Panel(labels = true), 2)
        assertEquals("Speaker 1: One\n\nSpeaker 2: Two", withLabels)
        for (paragraph in withLabels.split(SpeakerLabels.PARAGRAPH_BREAK)) {
            assertFalse("<<$paragraph>> starts with whitespace", paragraph.first().isWhitespace())
        }
    }

    @Test fun punctuation_opening_a_new_speakers_paragraph_still_starts_that_paragraph() {
        // TextJoin would attach a leading "." to the previous run; a paragraph break must win.
        val runs = listOf(run(1, 0, "Hello", 1), run(2, 0, ". Yes.", 2))
        assertEquals("Hello\n\n. Yes.", SpeakerLabels.render(runs, SpeakerLabels.Mode.Field, 2))
    }

    @Test fun an_empty_run_list_renders_nothing_in_every_mode() {
        for (confirmed in listOf(0, 2)) {
            assertEquals("", SpeakerLabels.render(emptyList(), SpeakerLabels.Mode.Field, confirmed))
            assertEquals(
                "",
                SpeakerLabels.render(emptyList(), SpeakerLabels.Mode.Panel(labels = true), confirmed),
            )
        }
    }

    // ------------------------------------------------------------------ the panel's window

    @Test fun the_tail_keeps_the_newest_text_inside_the_cap() {
        val runs = (1..40).map { run(it.toLong(), 0, "word$it", if (it % 2 == 0) 2 else 1) }
        val tail = SpeakerLabels.renderTail(runs, SpeakerLabels.Mode.Panel(labels = true), 2, maxChars = 60)
        assertTrue("within the cap: <<$tail>>", tail.length <= 60)
        assertTrue("the newest run is visible", tail.contains("word40"))
    }

    @Test fun the_tail_never_splits_a_label_from_its_paragraph() {
        val runs = (1..40).map { run(it.toLong(), 0, "word$it", if (it % 2 == 0) 2 else 1) }
        for (cap in 20..200) {
            val tail = SpeakerLabels.renderTail(runs, SpeakerLabels.Mode.Panel(labels = true), 2, cap)
            assertTrue("cap=$cap over the cap: <<$tail>>", tail.length <= cap)
            for (paragraph in tail.split(SpeakerLabels.PARAGRAPH_BREAK)) {
                if (paragraph.isEmpty()) continue
                assertFalse(
                    "cap=$cap half a label survived: <<$paragraph>>",
                    paragraph.contains("Speaker") && !paragraph.startsWith("Speaker "),
                )
                assertFalse(
                    "cap=$cap a label with no words: <<$paragraph>>",
                    Regex("^Speaker \\d+: $").matches(paragraph),
                )
            }
        }
    }

    @Test fun the_tail_numbers_speakers_over_the_whole_session_not_over_the_window() {
        // The first two speakers have scrolled out of the window; the third must not be renumbered
        // to 1 because it happens to be the first one visible.
        val runs = listOf(
            run(1, 0, "A".repeat(80), 1),
            run(2, 0, "B".repeat(80), 2),
            run(3, 0, "the third voice", 3),
        )
        val tail = SpeakerLabels.renderTail(runs, SpeakerLabels.Mode.Panel(labels = true), 3, maxChars = 40)
        assertEquals("Speaker 3: the third voice", tail)
    }

    @Test fun a_single_run_longer_than_the_window_is_cut_from_the_front_and_keeps_its_label() {
        val runs = listOf(run(1, 0, "first", 1), run(2, 0, "0123456789ABCDEFGHIJ", 2))
        val tail = SpeakerLabels.renderTail(runs, SpeakerLabels.Mode.Panel(labels = true), 2, maxChars = 22)
        assertEquals("Speaker 2: 9ABCDEFGHIJ", tail)
        assertTrue(tail.length <= 22)
    }

    @Test fun the_tail_under_two_confirmed_speakers_is_todays_capped_tail() {
        val runs = listOf(run(1, 0, "aaaaaaaaaa", 1), run(2, 0, "bbbbbbbbbb", 1), run(3, 0, "cccccccccc", 1))
        val whole = TextJoin.assemble(runs.map { it.text })
        assertEquals(
            whole.substring(whole.length - 20),
            SpeakerLabels.renderTail(runs, SpeakerLabels.Mode.Panel(labels = true), 1, maxChars = 20),
        )
    }

    // ------------------------------------------------------------------ the window is EXACT

    @Test fun the_tail_is_the_widest_suffix_that_RENDERS_inside_the_cap_not_the_widest_bound() {
        // THE 2026-09-19 DEFECT, as arithmetic. Twenty one-sentence runs from the same speaker:
        // each renders as 6 characters and a joining space, but the old upper bound charged each
        // one 6 + "\n\n" + "Speaker 1: " = 19 — an over-estimate of 73%, because a run that
        // follows the same speaker renders with neither a break nor a label.
        val runs = (1..20).map { run(it.toLong(), 0, "word%02d".format(it), 1) }
        val tail = SpeakerLabels.renderTail(runs, SpeakerLabels.Mode.Panel(labels = true), 2, maxChars = 60)
        // Exact: "Speaker 1: " (11) + k runs joined by single spaces (7k - 1) fits 60 at k = 7.
        assertEquals("Speaker 1: word14 word15 word16 word17 word18 word19 word20", tail)
        assertEquals(59, tail.length)
        assertTrue("still inside the cap", tail.length <= 60)
        // The bound-based walk stopped after THREE runs (19 + 19 + 19 = 57, a fourth would be 76)
        // and rendered 31 characters into a window with room for 59 — less than half of it.
        assertTrue("the exact fit must be strictly wider than the bound's", tail.length > 31)
        assertFalse("the bound's suffix started here", tail.startsWith("Speaker 1: word18"))
    }

    @Test fun the_exact_tail_is_maximal_at_every_cap_one_more_run_would_always_overflow() {
        // The property behind the arithmetic above, swept against a reference that does not go
        // near the formatter: one speaker, so the render IS the label plus TextJoin, and the
        // widest fitting suffix can be found by trying every start index in the test.
        val texts = (1..30).map { "w".repeat(1 + it % 7) + it + "." }
        val runs = texts.mapIndexed { i, t -> run(i + 1L, 0, t, 1) }
        val head = SpeakerLabels.label(1)
        fun suffixFrom(from: Int) = head + TextJoin.assemble(texts.subList(from, texts.size))
        for (cap in 12..400) {
            val tail = SpeakerLabels.renderTail(runs, SpeakerLabels.Mode.Panel(labels = true), 2, cap)
            assertTrue("cap=$cap over the cap: <<$tail>>", tail.length <= cap)
            val widest = texts.indices.firstOrNull { suffixFrom(it).length <= cap }
            val expected = if (widest != null) {
                suffixFrom(widest)
            } else {
                // Not even the newest run alone fits: it is cut from the FRONT by exactly the
                // overflow and keeps its label (cap > head.length here, so words survive).
                val lastText = texts.last()
                head + lastText.substring(head.length + lastText.length - cap)
            }
            assertEquals("cap=$cap", expected, tail)
        }
    }

    @Test fun one_run_is_never_dropped_however_small_the_cap() {
        val one = listOf(run(1, 0, "alone", 1))
        assertEquals("Speaker 1: alone", SpeakerLabels.renderTail(one, SpeakerLabels.Mode.Panel(labels = true), 2, 100))
        // Exactly on the cap, and one under it (the front truncation, label intact).
        assertEquals("Speaker 1: alone", SpeakerLabels.renderTail(one, SpeakerLabels.Mode.Panel(labels = true), 2, 16))
        assertEquals("Speaker 1: lone", SpeakerLabels.renderTail(one, SpeakerLabels.Mode.Panel(labels = true), 2, 15))
        // A cap too small for the label itself: nothing, rather than half a label. Unchanged
        // from 4.10 and unreachable in the app (the cap there is TranscriptSink's 20,000).
        for (cap in 1..11) {
            val degenerate = SpeakerLabels.renderTail(one, SpeakerLabels.Mode.Panel(labels = true), 2, cap)
            assertEquals("cap=$cap", "", degenerate)
        }
        assertEquals("Speaker 1: e", SpeakerLabels.renderTail(one, SpeakerLabels.Mode.Panel(labels = true), 2, 12))
    }

    @Test fun the_pre_latch_tail_is_a_plain_character_truncation_byte_for_byte() {
        // 4.9's incremental tail, which is a plain `takeLast` of the joined text and nothing
        // else: no run boundary, no label, no break — at ANY cap. This is the half of the panel
        // the overwhelming majority of sessions see, and it must not have moved.
        val runs = (1..40).map { run(it.toLong(), 0, "sentence number $it.", if (it % 2 == 0) 2 else 1) }
        val whole = TextJoin.assemble(runs.map { it.text })
        for (confirmed in 0..1) {
            for (cap in listOf(1, 7, 40, 199, 4_000, 20_000, whole.length, whole.length + 1)) {
                val expected = if (whole.length <= cap) whole else whole.substring(whole.length - cap)
                for (mode in listOf(
                    SpeakerLabels.Mode.Panel(labels = true),
                    SpeakerLabels.Mode.Panel(labels = false),
                    SpeakerLabels.Mode.Field,
                )) {
                    assertEquals(
                        "confirmed=$confirmed cap=$cap mode=$mode",
                        expected,
                        SpeakerLabels.renderTail(runs, mode, confirmed, cap),
                    )
                }
            }
        }
    }

    @Test fun a_twenty_thousand_character_window_shows_twenty_thousand_characters() {
        // The owner's session, at the new cap: three hundred one-sentence runs, two speakers.
        // The whole thing is under 20,000 characters, so NOTHING is thrown away — which is the
        // point of the number (the bound-based walk at 4,000 showed roughly a fifth of it).
        val runs = (1..300).map {
            run(it.toLong(), 0, "This is sentence number $it of the session.", if (it % 4 == 0) 2 else 1)
        }
        val whole = SpeakerLabels.render(runs, SpeakerLabels.Mode.Panel(labels = true), 2)
        assertTrue("the fixture must be inside the cap to prove the point", whole.length in 5_000..20_000)
        assertEquals(whole, SpeakerLabels.renderTail(runs, SpeakerLabels.Mode.Panel(labels = true), 2, 20_000))
    }

    @Test fun a_tail_of_nothing_is_empty_rather_than_an_exception() {
        assertEquals("", SpeakerLabels.renderTail(emptyList(), SpeakerLabels.Mode.Field, 2, 100))
        assertEquals(
            "",
            SpeakerLabels.renderTail(listOf(run(1, 0, "x", 1)), SpeakerLabels.Mode.Field, 2, 0),
        )
    }
}
