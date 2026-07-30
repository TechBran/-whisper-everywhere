package com.whispereverywhere.recording

import android.os.StatFs
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Appends raw PCM16 straight to a file as the recorder produces it. Raw, not WAV: the 44-byte
 * header would be dead weight because chunks are cut by byte offset and WAV-wrapped in memory only
 * at dispatch. Buffered; the caller MUST close() to flush the tail.
 */
class PcmSink(file: File) {
    private val out = BufferedOutputStream(FileOutputStream(file))
    private var written = 0L

    /** Writes exactly [len] bytes of [pcm]. The decoder's buffers vary per codec frame. */
    fun append(pcm: ByteArray, len: Int) {
        out.write(pcm, 0, len)
        written += len
    }

    fun bytesWritten(): Long = written

    fun close() {
        out.flush()
        out.close()
    }
}

/**
 * Free-space gate for the audio write path — the first one the app has (model/TTS downloads have
 * their own). The decision is a pure function so it is unit-testable; the StatFs read is a thin
 * wrapper because StatFs returns type defaults under unit tests.
 */
object StorageGuard {
    /** Requires required*1.1 to fit — a bare fit risks a truncated recording at end of disk. */
    fun enoughSpace(availableBytes: Long, requiredBytes: Long): Boolean =
        availableBytes >= (requiredBytes.toDouble() * 1.1).toLong()

    fun availableBytesAt(path: File): Long =
        runCatching { StatFs(path.absolutePath).availableBytes }.getOrDefault(Long.MAX_VALUE)
}
