package com.whispereverywhere

import com.whispereverywhere.transcription.TranscriptSink
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
}
