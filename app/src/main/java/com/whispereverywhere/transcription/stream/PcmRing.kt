package com.whispereverywhere.transcription.stream

/**
 * The last [capacityBytes] of PCM the previewer was fed — the retained-tail source for a cap cut
 * (spec §4.2). Written on the capture thread, read on the preview executor; both under the
 * monitor. `last` returns an EVEN byte count so a PCM16 sample is never split.
 */
class PcmRing(capacityBytes: Int) {
    private val buf = ByteArray(capacityBytes)
    private var total = 0L   // bytes ever written; the write cursor is total % size

    @Synchronized
    fun write(pcm: ByteArray) {
        val size = buf.size
        if (pcm.size >= size) {
            System.arraycopy(pcm, pcm.size - size, buf, 0, size)
            total = ((total + pcm.size) / size) * size   // the newest byte sits at index size − 1
            return
        }
        val start = (total % size).toInt()
        val first = minOf(pcm.size, size - start)
        System.arraycopy(pcm, 0, buf, start, first)
        if (first < pcm.size) System.arraycopy(pcm, first, buf, 0, pcm.size - first)
        total += pcm.size
    }

    @Synchronized
    fun last(bytes: Int): ByteArray {
        val size = buf.size
        val n = minOf(bytes.toLong(), total, size.toLong()).toInt() and 1.inv()
        if (n <= 0) return ByteArray(0)
        val out = ByteArray(n)
        val end = (total % size).toInt()
        val start = ((end - n) % size + size) % size
        val first = minOf(n, size - start)
        System.arraycopy(buf, start, out, 0, first)
        if (first < n) System.arraycopy(buf, 0, out, first, n - first)
        return out
    }

    @Synchronized
    fun clear() { total = 0L }
}
