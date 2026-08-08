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
        // TextJoin governs the join (W2 final-only commit): this file IS the transcript the
        // final delivery ships, so 'Hello'+'.' must read 'Hello.' — exactly what sequential
        // per-segment injection used to produce — and a CJK boundary must not grow a stray
        // space. [tail]'s last char is always the last char written to the file (truncation
        // only eats the FRONT), so it is the left side of every boundary decision.
        val needsSpace = tail.isNotEmpty() && TextJoin.needsSpace(tail, s)
        try {
            if (needsSpace) writer.write(" ")
            writer.write(s)
            writer.flush()
        } catch (e: Exception) {
            // Post-close appends land here by design (benign); real I/O failures (disk full)
            // do too — log so a delivered-file-vs-history divergence is diagnosable.
            android.util.Log.w("WE-DIAG", "sink append dropped (${s.length} chars): $e")
            return
        }
        if (needsSpace) tail.append(' ')
        tail.append(s)
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
