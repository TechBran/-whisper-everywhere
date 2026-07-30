package com.whispereverywhere.recording

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder

/**
 * Decodes ANY audio Android can demux (mp3, m4a/aac, wav, ogg/opus, flac...) from a SAF Uri to
 * 16 kHz mono PCM16 streamed through [PcmSink]. Synchronous; the caller (BatchTranscriptionService)
 * runs it off-main and reports [onProgress] from extractor position / duration.
 *
 * Framework code end to end, so there is deliberately NO JVM unit test — under
 * unitTests.isReturnDefaultValues these classes are stubs and any test would pass vacuously. The
 * sample MATH is delegated to [Downmix]/[Resampler], which ARE unit-tested. Verified on-device
 * (Task 9) against a codec zoo.
 */
class AudioDecoder {

    sealed interface DecodeResult {
        data class Ok(val byteLength: Long, val durationMs: Long) : DecodeResult
        data class Unsupported(val reason: String) : DecodeResult
    }

    fun decodeTo(context: Context, uri: Uri, sink: PcmSink, onProgress: (Float) -> Unit): DecodeResult {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return DecodeResult.Unsupported("no audio track")
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION))
                format.getLong(MediaFormat.KEY_DURATION) else 0L
            extractor.selectTrack(track)

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            var sawInputEos = false
            var sawOutputEos = false
            // Output format is authoritative AFTER the first INFO_OUTPUT_FORMAT_CHANGED — the
            // track format's rate/channels can be wrong for some containers.
            var outRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var outChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            while (!sawOutputEos) {
                if (!sawInputEos) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        val n = extractor.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEos = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, n, extractor.sampleTime, 0)
                            if (durationUs > 0) onProgress(extractor.sampleTime / durationUs.toFloat())
                            extractor.advance()
                        }
                    }
                }
                when (val outIdx = codec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        outRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        outChannels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (outIdx >= 0) {
                        if (info.size > 0) {
                            val buf = codec.getOutputBuffer(outIdx)!!
                            val shorts = ShortArray(info.size / 2)
                            buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
                            val mono16k = Resampler.to16k(Downmix.toMono(shorts, outChannels), outRate)
                            val bytes = ByteArray(mono16k.size * 2)
                            for (i in mono16k.indices) {
                                bytes[i * 2] = (mono16k[i].toInt() and 0xFF).toByte()
                                bytes[i * 2 + 1] = (mono16k[i].toInt() shr 8).toByte()
                            }
                            sink.append(bytes, bytes.size)
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEos = true
                    }
                }
            }
            return DecodeResult.Ok(sink.bytesWritten(), durationUs / 1_000)
        } catch (t: Throwable) {
            // Corrupt file, unsupported codec, revoked Uri grant — one honest failure, no partials
            // presented as success. Message is generic; never log the Uri (it can embed a filename).
            return DecodeResult.Unsupported(t.javaClass.simpleName)
        } finally {
            runCatching { codec?.stop() }; runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }
}
