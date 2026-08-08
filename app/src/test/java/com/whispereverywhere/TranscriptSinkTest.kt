package com.whispereverywhere

import com.whispereverywhere.transcription.TranscriptSink
import com.whispereverywhere.text.TextJoin
import java.util.concurrent.Executors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TranscriptSinkTest {
    private fun tmp(): File = File.createTempFile("transcript", ".txt").apply { deleteOnExit() }

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
}
