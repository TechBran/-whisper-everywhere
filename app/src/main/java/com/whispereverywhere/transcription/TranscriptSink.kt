package com.whispereverywhere.transcription

import com.whispereverywhere.text.TextJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter

/**
 * Bounded-memory transcript accumulator for indefinite-length sessions.
 * Every finalized segment is appended to [sessionFile]; only the last [previewCapChars]
 * characters are retained in memory for the live preview, so a multi-hour session never
 * grows RAM. The full transcript is always the file on disk.
 */
class TranscriptSink(
    private val sessionFile: File,
    private val previewCapChars: Int = 4000,
) {
    private val _preview = MutableStateFlow("")
    val preview: StateFlow<String> = _preview

    private val writer: BufferedWriter = BufferedWriter(FileWriter(sessionFile, /* append = */ true))
    private val tail = StringBuilder()

    @Synchronized
    fun append(segment: String) {
        val s = TextJoin.normalize(segment)
        if (s.isEmpty()) return
        writer.write(s)
        writer.write(" ")
        writer.flush()
        tail.append(s).append(' ')
        if (tail.length > previewCapChars) {
            tail.delete(0, tail.length - previewCapChars)
        }
        _preview.value = tail.toString()
    }

    fun fullTextFile(): File = sessionFile

    @Synchronized
    fun close() {
        try {
            writer.flush()
            writer.close()
        } catch (_: Exception) {
        }
    }
}
