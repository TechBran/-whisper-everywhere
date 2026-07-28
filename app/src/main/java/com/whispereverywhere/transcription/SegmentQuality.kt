package com.whispereverywhere.transcription

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater

/**
 * Catches a transcript that is syntactically fine but semantically garbage.
 *
 * `TranscriptText.clean()` is the entire post-processing pipeline today: it strips bracketed groups
 * and a fixed keyword list. "Thank you for watching." matches neither, is not blank, and is typed
 * straight into the user's outgoing message — and it sets `sessionProducedText = true`, which
 * disarms the app's only remaining safety net.
 *
 * Pure and Android-free. Applied to EVERY engine, local and cloud: local has whisper's own
 * temperature fallback as a partial defence, but a cloud response is a finished string with no
 * equivalent.
 */
enum class QualityVerdict { ACCEPT, REJECT_REPETITION, REJECT_IMPLAUSIBLE }

object SegmentQuality {

    /** Above this, the text is compressing far too well to be natural language. */
    private const val COMPRESSION_GATE = 2.4

    /** Words per second above which the text cannot be speech at the stated duration. */
    private const val MAX_WORDS_PER_SECOND = 8.0

    /** Below this much text, ratios are meaningless — a short string always compresses badly. */
    private const val MIN_CHARS_FOR_RATIO = 32

    fun assess(text: String, voicedMs: Int): QualityVerdict {
        val trimmed = text.trim()
        // Emptiness is the orderer's business, not a quality failure. Claiming it here would
        // double-report the same condition.
        if (trimmed.isEmpty()) return QualityVerdict.ACCEPT

        // A short burst of distinct words (e.g. an enumerated list) can share enough substring
        // structure to compress modestly past the gate without being a real repetition loop — a
        // genuine loop compresses an order of magnitude harder. So when a segment trips both
        // gates, report whichever it violates by the larger margin relative to its own threshold,
        // rather than always favouring whichever check happens to run first.
        val ratio = if (trimmed.length >= MIN_CHARS_FOR_RATIO) compressionRatio(trimmed) else 0.0
        val ratioExceeded = ratio > COMPRESSION_GATE
        val ratioMargin = if (ratioExceeded) ratio / COMPRESSION_GATE else 0.0

        var wpsExceeded = false
        var wpsMargin = 0.0
        if (voicedMs > 0) {
            val words = trimmed.split(Regex("\\s+")).count { it.isNotBlank() }
            val wps = words / (voicedMs / 1000.0)
            wpsExceeded = wps > MAX_WORDS_PER_SECOND
            if (wpsExceeded) wpsMargin = wps / MAX_WORDS_PER_SECOND
        }

        return when {
            ratioExceeded && wpsExceeded ->
                if (ratioMargin >= wpsMargin) QualityVerdict.REJECT_REPETITION else QualityVerdict.REJECT_IMPLAUSIBLE
            ratioExceeded -> QualityVerdict.REJECT_REPETITION
            wpsExceeded -> QualityVerdict.REJECT_IMPLAUSIBLE
            else -> QualityVerdict.ACCEPT
        }
    }

    /**
     * Ratio of raw bytes to DEFLATE-compressed bytes. This is literally the heuristic whisper.cpp
     * trips on internally (see the entropy/compression note in whisper_jni.cpp), so the 2.4 gate is
     * pre-calibrated on the exact degenerate-loop failure this app hit on 2026-07-18.
     */
    fun compressionRatio(text: String): Double {
        val raw = text.toByteArray(Charsets.UTF_8)
        if (raw.isEmpty()) return 1.0
        val deflater = Deflater()
        val out = ByteArrayOutputStream(raw.size)
        val buf = ByteArray(1024)
        try {
            deflater.setInput(raw)
            deflater.finish()
            while (!deflater.finished()) {
                val n = deflater.deflate(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
            }
        } finally {
            deflater.end()
        }
        val compressed = out.size()
        return if (compressed == 0) 1.0 else raw.size.toDouble() / compressed.toDouble()
    }
}
