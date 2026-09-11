package com.whispereverywhere.transcription.stream

import java.io.File
import java.io.RandomAccessFile

/**
 * Reads an ONNX model's `metadata_props` — the `key`/`value` pairs the icefall exporter writes and
 * sherpa-onnx reads back at load — WITHOUT loading the model, without a protobuf dependency and
 * without reading the weights.
 *
 * ### Why this exists in the test sources
 *
 * Three of a pack's properties are facts about a FILE, not decisions the app gets to make:
 * `model_type` (whose mismatch is an uncatchable `_Exit(-1)` — qualification table §6(2)),
 * `decode_chunk_len` (the cadence) and `T` (the frames one forward pass consumes, which
 * [StreamingPreviewTuning.padMsFor] derives the commit pad from). The catalogue records all three
 * as literals, the same discipline as the byte counts and the sha256s; this reader is what holds
 * the literals equal to the bytes wherever the payload is on disk.
 *
 * ### The parse
 *
 * `ModelProto` is a plain protobuf message. Only the top-level fields are walked: **1** `ir_version`
 * (varint), **2/3** producer strings, **7** `graph` (the 71 MB of weights — SEEKED PAST, never read),
 * **8** `opset_import`, **14** `metadata_props` (repeated `StringStringEntryProto`, `key` = 1,
 * `value` = 2). So the cost is a handful of reads regardless of model size.
 *
 * **The trap this avoids, recorded by the auditor who nearly lost a row to it** (qualification
 * table §9): a naive 4-character string scan over the tail aligns `decode_chunk_len` to `77`,
 * because the real value `64` is two characters and the key `T` is one. The protobuf is PARSED
 * here, never regex-scraped.
 */
object OnnxMetadata {

    /** `metadata_props` as a map, in file order. Empty when the file carries none. */
    fun read(file: File): Map<String, String> {
        RandomAccessFile(file, "r").use { raf ->
            val props = LinkedHashMap<String, String>()
            val end = raf.length()
            while (raf.filePointer < end) {
                val tag = raf.readVarint()
                val field = (tag shr 3).toInt()
                when ((tag and 7L).toInt()) {
                    0 -> raf.readVarint()
                    1 -> raf.seek(raf.filePointer + 8)
                    5 -> raf.seek(raf.filePointer + 4)
                    2 -> {
                        val len = raf.readVarint()
                        if (field == METADATA_PROPS) {
                            val entry = ByteArray(len.toInt()).also { raf.readFully(it) }
                            val (k, v) = parseEntry(entry)
                            if (k != null) props[k] = v ?: ""
                        } else {
                            // The graph and everything else: skipped without being read.
                            raf.seek(raf.filePointer + len)
                        }
                    }
                    else -> throw AssertionError("unsupported wire type in ${file.name} at ${raf.filePointer}")
                }
            }
            return props
        }
    }

    private const val METADATA_PROPS = 14

    /** `StringStringEntryProto`: field 1 is the key, field 2 the value; both length-delimited UTF-8. */
    private fun parseEntry(bytes: ByteArray): Pair<String?, String?> {
        var i = 0
        var key: String? = null
        var value: String? = null
        while (i < bytes.size) {
            var shift = 0
            var tag = 0L
            while (true) {
                val b = bytes[i++].toInt() and 0xFF
                tag = tag or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) break
                shift += 7
            }
            val field = (tag shr 3).toInt()
            when ((tag and 7L).toInt()) {
                2 -> {
                    shift = 0
                    var len = 0
                    while (true) {
                        val b = bytes[i++].toInt() and 0xFF
                        len = len or ((b and 0x7F) shl shift)
                        if (b and 0x80 == 0) break
                        shift += 7
                    }
                    val s = String(bytes, i, len, Charsets.UTF_8)
                    i += len
                    if (field == 1) key = s else if (field == 2) value = s
                }
                0 -> while (bytes[i++].toInt() and 0x80 != 0) Unit
                1 -> i += 8
                5 -> i += 4
                else -> throw AssertionError("unsupported wire type in a metadata entry")
            }
        }
        return key to value
    }

    private fun RandomAccessFile.readVarint(): Long {
        var shift = 0
        var result = 0L
        while (true) {
            val b = read()
            if (b < 0) throw AssertionError("truncated varint")
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
            if (shift > 63) throw AssertionError("varint too long")
        }
    }
}
