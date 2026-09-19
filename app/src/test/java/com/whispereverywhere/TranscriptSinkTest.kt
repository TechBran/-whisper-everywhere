package com.whispereverywhere

import com.whispereverywhere.text.TextJoin
import com.whispereverywhere.transcription.TranscriptSink
import com.whispereverywhere.transcription.speakers.SpeakerLabels
import com.whispereverywhere.transcription.speakers.SpeakerRuns
import com.whispereverywhere.transcription.speakers.SpeakerSpan
import java.util.concurrent.Executors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TranscriptSinkTest {
    private fun tmp(): File = File.createTempFile("transcript", ".txt").apply { deleteOnExit() }

    /** 4.9's append: a chunk with no speaker geometry behind it. */
    private fun TranscriptSink.append(text: String) = append(seq = 0L, spans = null, text = text)

    @Test fun appends_full_text_to_file() {
        val f = tmp()
        val sink = TranscriptSink(f)
        sink.append("Hello world.")
        sink.append("Second sentence.")
        sink.close()
        val text = f.readText()
        assertTrue(text.contains("Hello world."))
        assertTrue(text.contains("Second sentence."))
    }

    @Test fun preview_is_capped_but_keeps_newest_tail() {
        val f = tmp()
        val sink = TranscriptSink(f, previewCapChars = 20)
        sink.append("aaaaaaaaaa")   // 10
        sink.append("bbbbbbbbbb")   // +10
        sink.append("cccccccccc")   // pushes past the cap
        val preview = sink.preview.value
        assertTrue("preview must stay capped", preview.length <= 20)
        assertTrue("preview keeps the newest text", preview.contains("cccccccccc"))
        sink.close()
    }

    @Test fun blank_segments_are_ignored() {
        val f = tmp()
        val sink = TranscriptSink(f)
        sink.append("   ")
        sink.append("")
        sink.close()
        assertEquals("", f.readText().trim())
    }

    @Test
    fun append_after_close_is_a_safe_no_op() {
        val f = tmp()
        val sink = TranscriptSink(f)
        sink.append("hello")
        sink.close()
        sink.append("late segment")  // must not throw
        assertEquals("hello", sink.fullTextFile().readText().trim())
        assertEquals("and it is not in the runs either", 1, sink.runCount)
    }

    // --- TextJoin-governed joins (W2 final-only commit) -------------------------
    // The sink file IS the transcript the final delivery ships, so its joins must follow the
    // same melt policy sequential injection followed: punctuation attaches, CJK grows no stray
    // space, and there is no trailing separator.

    @Test fun sink_joins_under_the_textjoin_policy_not_blind_spaces() {
        val f = tmp()
        val sink = TranscriptSink(f)
        sink.append("Hello")
        sink.append(".")      // closing punctuation attaches: 'Hello.', never 'Hello . '
        sink.append("你好")
        sink.append("世界")   // CJK boundary: no stray space
        sink.close()
        assertEquals("Hello. 你好世界", f.readText())
    }

    @Test fun sink_file_equals_textjoin_assemble_of_the_segments() {
        val f = tmp()
        val sink = TranscriptSink(f)
        val segs = listOf("Hello world", "this is a test", ".", "  ", "OK then", "?")
        segs.forEach { sink.append(it) }
        sink.close()
        assertEquals(TextJoin.assemble(segs), f.readText())
    }

    @Test fun appends_from_a_background_executor_land_complete_and_joined() {
        // House rule: concurrency-adjacent tests run on a REAL background executor. The bubble
        // appends from engine threads; the file must still equal the assemble of the segments.
        val f = tmp()
        val sink = TranscriptSink(f)
        val exec = Executors.newSingleThreadExecutor()
        try {
            val segs = (1..50).map { "segment$it" }
            segs.forEach { s -> exec.submit { sink.append(s) } }
            exec.submit { }.get() // fence: every queued append has completed
            sink.close()
            assertEquals(TextJoin.assemble(segs), f.readText())
        } finally {
            exec.shutdown()
        }
    }

    // --- 4.10: runs, the late assignment, and the two renders -------------------

    private val twoVoices = listOf(
        SpeakerSpan(windowIndex = 0, text = "Hello there."),
        SpeakerSpan(windowIndex = 1, text = "Hi, how are you?"),
    )
    private val twoVoicesText = "Hello there. Hi, how are you?"

    @Test fun spans_become_one_run_each_and_a_chunk_with_none_becomes_one_plain_run() {
        val sink = TranscriptSink(tmp())
        sink.append(seq = 1, spans = twoVoices, text = twoVoicesText)
        sink.append(seq = 2, spans = null, text = "A cloud chunk.")
        assertEquals(3, sink.runCount)
        val runs = sink.runs()
        assertEquals(listOf(0, 1, SpeakerRuns.NO_WINDOW_INDEX), runs.map { it.windowIndex })
        assertEquals(listOf(1L, 1L, 2L), runs.map { it.seq })
        assertTrue("nothing is assigned until the embedder answers", runs.all { it.speakerId == null })
        sink.close()
    }

    @Test fun the_panel_is_4_9s_text_until_a_second_speaker_is_confirmed() {
        val sink = TranscriptSink(tmp())
        sink.append(seq = 1, spans = twoVoices, text = twoVoicesText)
        sink.assign(seq = 1, ids = listOf(1, 2), remap = emptyMap())
        assertEquals("ids alone change nothing on screen", twoVoicesText, sink.preview.value)
        sink.close()
    }

    @Test fun the_latch_relabels_the_panel_from_the_session_start() {
        val sink = TranscriptSink(tmp())
        sink.append(seq = 1, spans = twoVoices, text = twoVoicesText)
        sink.assign(seq = 1, ids = listOf(1, 2), remap = emptyMap())
        assertTrue("the first flip moves the latch", sink.setLabelsVisible(true))
        assertEquals(
            "Speaker 1: Hello there.\n\nSpeaker 2: Hi, how are you?",
            sink.preview.value,
        )
        assertFalse("and it only moves once — the assigner republishes it every chunk", sink.setLabelsVisible(true))
        sink.close()
    }

    @Test fun an_assignment_that_lands_after_the_latch_repaints_the_panel() {
        val sink = TranscriptSink(tmp())
        sink.append(seq = 1, spans = twoVoices, text = twoVoicesText)
        sink.assign(seq = 1, ids = listOf(1, 2), remap = emptyMap())
        sink.setLabelsVisible(true)
        sink.append(seq = 2, spans = listOf(SpeakerSpan(0, "Fine, thanks.")), text = "Fine, thanks.")
        assertEquals(
            "an unassigned run inherits until its embedding lands",
            "Speaker 1: Hello there.\n\nSpeaker 2: Hi, how are you? Fine, thanks.",
            sink.preview.value,
        )
        sink.assign(seq = 2, ids = listOf(1), remap = emptyMap())
        assertEquals(
            "Speaker 1: Hello there.\n\nSpeaker 2: Hi, how are you?\n\nSpeaker 1: Fine, thanks.",
            sink.preview.value,
        )
        sink.close()
    }

    @Test fun a_merge_that_lands_late_moves_every_run_of_the_session() {
        val sink = TranscriptSink(tmp())
        sink.append(seq = 1, spans = twoVoices, text = twoVoicesText)
        sink.assign(seq = 1, ids = listOf(1, 3), remap = emptyMap())
        sink.setLabelsVisible(true)
        sink.assign(seq = 2, ids = emptyList(), remap = mapOf(3 to 1))
        assertEquals("Speaker 1: Hello there. Hi, how are you?", sink.preview.value)
        sink.close()
    }

    @Test fun the_panels_tail_never_splits_a_label_from_its_paragraph() {
        val sink = TranscriptSink(tmp(), previewCapChars = 40)
        for (i in 1..12) {
            sink.append(seq = i.toLong(), spans = listOf(SpeakerSpan(0, "turn$i")), text = "turn$i")
            sink.assign(seq = i.toLong(), ids = listOf(if (i % 2 == 0) 2 else 1), remap = emptyMap())
            sink.setLabelsVisible(true)
            val preview = sink.preview.value
            assertTrue("over the cap at i=$i: <<$preview>>", preview.length <= 40)
            for (paragraph in preview.split(SpeakerLabels.PARAGRAPH_BREAK)) {
                assertTrue(
                    "half a label survived at i=$i: <<$paragraph>>",
                    paragraph.startsWith("Speaker "),
                )
            }
        }
        sink.close()
    }

    @Test fun the_file_is_the_field_render_at_close_breaks_and_never_a_label() {
        val f = tmp()
        val sink = TranscriptSink(f)
        sink.append(seq = 1, spans = twoVoices, text = twoVoicesText)
        sink.assign(seq = 1, ids = listOf(1, 2), remap = emptyMap())
        sink.setLabelsVisible(true)
        sink.close()
        assertEquals("Hello there.\n\nHi, how are you?", f.readText())
        assertFalse("a text field is not a transcript", f.readText().contains("Speaker"))
    }

    @Test fun without_the_latch_the_file_is_never_rewritten_at_all() {
        val f = tmp()
        val sink = TranscriptSink(f)
        sink.append(seq = 1, spans = twoVoices, text = twoVoicesText)
        sink.assign(seq = 1, ids = listOf(1, 1), remap = emptyMap())
        sink.close()
        assertEquals(twoVoicesText, f.readText())
    }

    @Test fun the_field_render_is_what_the_formatter_says_it_is() {
        val f = tmp()
        val sink = TranscriptSink(f)
        sink.append(seq = 1, spans = twoVoices, text = twoVoicesText)
        sink.assign(seq = 1, ids = listOf(1, 2), remap = emptyMap())
        sink.setLabelsVisible(true)
        val runs = sink.runs()
        val confirmed = sink.confirmedSpeakers
        sink.close()
        assertEquals(
            SpeakerLabels.render(runs, SpeakerLabels.Mode.Field, confirmed),
            f.readText(),
        )
    }

    @Test fun a_second_close_rewrites_nothing() {
        val f = tmp()
        val sink = TranscriptSink(f)
        sink.append(seq = 1, spans = twoVoices, text = twoVoicesText)
        sink.assign(seq = 1, ids = listOf(1, 2), remap = emptyMap())
        sink.setLabelsVisible(true)
        sink.close()
        val afterFirst = f.readText()
        sink.assign(seq = 1, ids = listOf(2, 2), remap = emptyMap())
        sink.close()
        assertEquals("the delivered file is decided once", afterFirst, f.readText())
    }

    @Test fun the_runs_snapshot_is_a_copy_and_not_a_window_onto_the_session() {
        val sink = TranscriptSink(tmp())
        sink.append(seq = 1, spans = twoVoices, text = twoVoicesText)
        val before = sink.runs()
        sink.assign(seq = 1, ids = listOf(1, 2), remap = emptyMap())
        assertNull("the snapshot describes the moment it was taken", before[0].speakerId)
        assertEquals(1, sink.runs()[0].speakerId)
        sink.close()
    }

    @Test fun ids_that_land_after_close_still_reach_the_runs_for_history() {
        val sink = TranscriptSink(tmp())
        sink.append(seq = 1, spans = twoVoices, text = twoVoicesText)
        sink.close()
        sink.assign(seq = 1, ids = listOf(1, 2), remap = emptyMap())
        assertEquals(listOf(1, 2), sink.runs().map { it.speakerId })
    }

    @Test fun the_confirmed_count_is_the_number_the_latch_certifies() {
        val sink = TranscriptSink(tmp())
        assertEquals(0, sink.confirmedSpeakers)
        sink.setLabelsVisible(true)
        assertEquals(SpeakerLabels.MIN_CONFIRMED_SPEAKERS, sink.confirmedSpeakers)
        sink.close()
    }
}
