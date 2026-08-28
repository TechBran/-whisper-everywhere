package com.whispereverywhere.npu

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer

/**
 * The mel quantiser: whisper's float32 log-mel to the NPU encoder's `ufixed16` `input_features`.
 *
 * The 4.0 NPU encoder is a **quantised** graph. Its input tensor is `ufixed16 [1,80,3000]`, which
 * means the 240,000 floats `WhisperNative.pcmToMel` produces have to be mapped through an affine
 * transform into `uint16` codes before the HTP will accept them:
 *
 * ```
 * q = clamp(round(x / scale) + zeroPoint, 0, 65535)
 * ```
 *
 * **`scale` and `zeroPoint` are NEVER constants here.** They belong to the asset, they are carried
 * in the encoder's own tensor metadata, and the only supported way to obtain them is
 * `QnnAsrNative.nativeInputQuant()`, which reads them off `Qnn_QuantizeParams_t` at load time.
 * The published values for the shipped asset happen to be `4.677007018472068e-05` and `32072`, and
 * they appear in this file **only in this sentence** — deliberately. Whisper models are
 * re-exported and re-calibrated periodically; a hardcoded scale would survive that re-export, keep
 * running, and produce a spectrogram that is wrong by a constant factor. The encoder does not
 * reject such input. It transcribes it, fluently, into different words. There is no downstream
 * check that can catch it, which is why the transport exists and why this object has no defaults.
 *
 * **This is pure Kotlin on purpose.** It is 240,000 multiply-round-adds — under a millisecond
 * against a ~405 ms `graphExecute` — and putting it here means the arithmetic that decides what
 * the model actually sees is JVM-testable to the last code, on a machine with no NPU in it. The
 * native side never quantises; it copies the block this object produced and executes.
 *
 * **Endianness is the silent one.** [ByteBuffer.allocateDirect] returns a BIG_ENDIAN buffer, and
 * both directions of this seam are little-endian native blocks — a float mel read back without
 * `nativeOrder()`, or a `uint16` block written without it, is byte-swapped into values that are
 * still perfectly finite and perfectly wrong. Use [newMelFloatBuffer] and
 * [newInputFeaturesBuffer]; never hand-roll the allocation.
 */
object NpuQuantize {

    /** Mel bands. 80 for every tier this app ships (`large-v3-turbo` is 128 and is refused upstream). */
    const val MEL_BINS = 80

    /** Frames in a 30 s window at whisper's hop — the encoder's fixed second dimension. */
    const val MEL_FRAMES = 3000

    /** 240,000: the element count of both sides of this transform. */
    const val MEL_VALUES = MEL_BINS * MEL_FRAMES

    /** 960,000 B — the float32 mel `WhisperNative.pcmToMel` writes. */
    const val MEL_FLOAT_BYTES = MEL_VALUES * 4

    /** 480,000 B — the `ufixed16` block `QnnAsrNative.nativeEncode` copies into `input_features`. */
    const val INPUT_FEATURES_BYTES = MEL_VALUES * 2

    /** Bottom rail of the `ufixed16` domain. */
    const val U16_MIN = 0

    /**
     * Top rail of the `ufixed16` domain. This constant is the reason `nativeInit` refuses to arm
     * a session whose `input_features` is not `ufixed16`: the domain is compile-time here, so a
     * re-exported asset with an 8-bit input would be clamped against rails 256 times too wide.
     */
    const val U16_MAX = 65_535

    /**
     * A correctly-shaped, correctly-ordered direct buffer for `WhisperNative.pcmToMel`'s output:
     * 960,000 bytes, native order. `pcmToMel` refuses any other capacity and *cannot* check the
     * byte order from JNI — that part is the caller's to get right, so it is done here once.
     */
    fun newMelFloatBuffer(): ByteBuffer =
        ByteBuffer.allocateDirect(MEL_FLOAT_BYTES).order(ByteOrder.nativeOrder())

    /**
     * A correctly-shaped, correctly-ordered direct buffer for `QnnAsrNative.nativeEncode`'s input:
     * 480,000 bytes, native order. Pass `asShortBuffer()` of this to [melToU16] and the buffer
     * itself to `nativeEncode`.
     */
    fun newInputFeaturesBuffer(): ByteBuffer =
        ByteBuffer.allocateDirect(INPUT_FEATURES_BYTES).order(ByteOrder.nativeOrder())

    /**
     * One value: `clamp(rint(x / scale) + zeroPoint, 0, 65535)`.
     *
     * **`Math.rint`, not `Math.round`.** `rint` is round-half-to-EVEN, which is how ONNX specifies
     * `QuantizeLinear` ("rounding to nearest even") and how `numpy.round` and `torch.round`
     * behave — the three implementations that quantised and validated this asset.
     * `Math.round(Float)` is `floor(x + 0.5)`, which resolves every tie upward and biases the
     * whole spectrogram by half a code. The difference is invisible in any single value and
     * systematic across 240,000 of them.
     *
     * The division is done in `Double`. `scale` is genuinely a `Float` — `Qnn_ScaleOffset_t.scale`
     * is a `float`, so the 32-bit value IS the one the DSP dequantises with — but performing the
     * divide in single precision would put the quotient's own rounding error on the same order as
     * the tie the line above is being careful about.
     *
     * Non-finite inputs cannot come out of whisper's mel (a log of a clamped-positive magnitude),
     * but the comparison order below is written so that they cannot produce an out-of-domain code
     * either: `NaN` fails both `>=` tests and lands on the bottom rail.
     */
    fun quantise(x: Float, scale: Float, zeroPoint: Int): Int {
        val q = Math.rint(x.toDouble() / scale) + zeroPoint
        return when {
            q >= U16_MAX.toDouble() -> U16_MAX
            q >= U16_MIN.toDouble() -> q.toInt()
            else -> U16_MIN
        }
    }

    /**
     * Quantises a whole 80x3000 mel into a `uint16` block, elementwise and in place.
     *
     * [mel] is bin-major with a stride of [MEL_FRAMES] — `whisper_get_mel_segment`'s destination
     * layout, which is already the layout `input_features` expects — so this is a straight
     * index-for-index map and never a transpose.
     *
     * Codes above 32767 are stored as **negative** `Short`s. That is not a bug to defend against:
     * the block is a raw `uint16` byte image, `Short` is merely the 16-bit container Java has, and
     * clamping at `Short.MAX_VALUE` would silently discard the upper half of the spectrogram.
     *
     * Neither buffer's position is disturbed: the caller hands the same [ByteBuffer] straight to
     * `QnnAsrNative.nativeEncode`, and a consumed position would present it as empty.
     *
     * @param mel exactly [MEL_VALUES] remaining float32 values.
     * @param scale from `QnnAsrNative.nativeInputQuant()[0]`. Never a literal.
     * @param zeroPoint from `QnnAsrNative.nativeInputQuant()[1]`. Never a literal.
     * @param out exactly [MEL_VALUES] remaining `uint16` slots — `newInputFeaturesBuffer()
     *        .asShortBuffer()`.
     * @throws IllegalArgumentException if any of the four is not the shape the asset fixes. These
     *         are all wiring mistakes with compile-time-known correct answers, and the one that
     *         actually happens is passing the 960,000-byte mel where the 480,000-byte quantised
     *         block belongs — so they are refused by count, at the boundary, rather than half-
     *         filling a buffer and encoding half a spectrogram.
     */
    fun melToU16(mel: FloatBuffer, scale: Float, zeroPoint: Int, out: ShortBuffer) {
        require(mel.remaining() == MEL_VALUES) {
            "mel must hold exactly $MEL_VALUES float32 values ($MEL_BINS x $MEL_FRAMES), " +
                "got ${mel.remaining()}"
        }
        require(out.remaining() == MEL_VALUES) {
            "out must hold exactly $MEL_VALUES uint16 slots ($MEL_BINS x $MEL_FRAMES), " +
                "got ${out.remaining()}"
        }
        require(scale > 0.0f && scale.isFinite()) {
            "scale must be strictly positive and finite, got $scale — a scale of 0 divides every " +
                "value to an infinity and pins the whole spectrogram to a rail"
        }
        require(zeroPoint in U16_MIN..U16_MAX) {
            "zeroPoint must lie inside the uint16 domain $U16_MIN..$U16_MAX, got $zeroPoint"
        }
        val melBase = mel.position()
        val outBase = out.position()
        for (i in 0 until MEL_VALUES) {
            out.put(outBase + i, quantise(mel.get(melBase + i), scale, zeroPoint).toShort())
        }
    }
}
