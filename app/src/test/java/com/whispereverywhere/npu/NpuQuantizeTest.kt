package com.whispereverywhere.npu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer

/**
 * The mel quantiser, tested against the encoder's REAL published parameters.
 *
 * `input_features` is `ufixed16 [1,80,3000]` with scale `4.677007018472068e-05` and zero point
 * `32072` — the values `metadata.json` publishes for
 * `whisper_small_quantized-precompiled_qnn_onnx-w8a16` and the values the encoder's own tensor
 * metadata carries. They appear here as literals **for the test only**: production reads them from
 * the tensor through `QnnAsrNative.nativeInputQuant()`, never from a constant, so a regenerated
 * asset re-quantised at a different scale cannot silently keep working against a stale number.
 * That is also why every assertion below passes them in as arguments rather than reading them
 * from [NpuQuantize] — there is nothing to read.
 *
 * Why this matters more than it looks: a wrong zero point does not crash and does not produce
 * noise. It shifts the whole spectrogram by a constant in the log-mel domain, which the encoder
 * happily consumes and turns into a fluent, confident, WRONG transcript. Nothing downstream of
 * here can tell the difference — so it has to be pinned here.
 */
class NpuQuantizeTest {

    /** The encoder's published scale — exact `float`, exactly as `Qnn_ScaleOffset_t.scale` holds it. */
    private val scale = 4.677007018472068e-05f

    /** The encoder's published zero point. QNN stores this as `offset = -32072`; see [NpuQuantize]. */
    private val zeroPoint = 32072

    /** `float_value = scale * (q - zeroPoint)` — the inverse of the quantiser, in double. */
    private fun dequantise(q: Int): Double = scale.toDouble() * (q - zeroPoint)

    /**
     * THE NAMED RED. Silence — a mel value of exactly `0.0f` — must land exactly on the zero point,
     * because that is what "zero point" means: the quantised code whose dequantised value is 0.
     *
     * An implementation that forgets the `+ zeroPoint` term passes a round-trip test built on its
     * own (equally shifted) inverse, passes both rail tests, and produces a spectrogram displaced
     * by 32072 codes — 1.5 in the log-mel domain — on every single value. It is the single most
     * plausible mistake in five lines of arithmetic and the least visible one downstream.
     */
    @Test
    fun zeroMapsToZeroPoint() {
        assertEquals(
            "0.0f must quantise to the zero point exactly — dequantising the result has to give " +
                "back 0.0, and it is the only value in the domain for which an exact answer is " +
                "available to assert.",
            zeroPoint,
            NpuQuantize.quantise(0.0f, scale, zeroPoint)
        )
        assertEquals(
            "and the round trip through the zero point must be exactly 0.0, not almost",
            0.0,
            dequantise(NpuQuantize.quantise(0.0f, scale, zeroPoint)),
            0.0
        )
    }

    /**
     * Both rails, the two values that sit exactly ON them, and the three non-finite inputs.
     *
     * The rails are not decoration: the representable window is only
     * `scale * [-32072, 33463]` = `[-1.500, 1.565]`, whisper's log-mel is not clamped to that
     * window, and a `Short` that wrapped instead of clamping would turn the loudest bin of a
     * segment into the quietest — a spectral inversion that reads as a plausible different word.
     */
    @Test
    fun clampsAtBothRailsAndNeverEscapesTheUint16Domain() {
        assertEquals(
            "a value far below the representable window must clamp to 0, not wrap",
            0,
            NpuQuantize.quantise(-10.0f, scale, zeroPoint)
        )
        assertEquals(
            "a value far above the representable window must clamp to 65535, not wrap",
            65535,
            NpuQuantize.quantise(10.0f, scale, zeroPoint)
        )

        // The exact rails: the largest and smallest float the window can carry without clamping.
        val bottom = (scale.toDouble() * (0 - zeroPoint)).toFloat()
        val top = (scale.toDouble() * (65535 - zeroPoint)).toFloat()
        assertEquals(
            "the value that dequantises to code 0 must quantise back to 0 (a rail is reachable, " +
                "not merely approachable)",
            0,
            NpuQuantize.quantise(bottom, scale, zeroPoint)
        )
        assertEquals(
            "the value that dequantises to code 65535 must quantise back to 65535",
            65535,
            NpuQuantize.quantise(top, scale, zeroPoint)
        )

        // Non-finite inputs cannot arise from whisper's mel (it is a log of a clamped-positive
        // magnitude), but "cannot arise" is exactly what makes an undefined answer dangerous: it
        // would be undefined only on the path nobody tests. Every one of them stays in domain.
        for (x in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            val q = NpuQuantize.quantise(x, scale, zeroPoint)
            assertTrue("$x must still quantise into 0..65535, got $q", q in 0..65535)
        }
        assertEquals(
            "+Inf belongs at the top rail",
            65535,
            NpuQuantize.quantise(Float.POSITIVE_INFINITY, scale, zeroPoint)
        )
        assertEquals(
            "-Inf belongs at the bottom rail",
            0,
            NpuQuantize.quantise(Float.NEGATIVE_INFINITY, scale, zeroPoint)
        )
    }

    /**
     * The tie rule, pinned explicitly.
     *
     * **Why a scale of 1 and not the published one.** A tie needs `x / scale` to land EXACTLY on
     * `k + 0.5`. With `scale = 4.677007018472068e-05` no `Float` does: the quotient lands within
     * ~1e-4 of the midpoint and the answer is then decided by ordinary rounding, not by the tie
     * rule. A test written at the published scale would therefore assert the accident of float
     * division and pass against either rule. `scale = 1.0f` makes the division exact and asks the
     * real question — and the rule it asks about is scale-independent.
     *
     * **Which rule.** Round-half-to-even, as `QuantizeLinear` is specified in ONNX ("rounding to
     * nearest even") and as `numpy.round` and `torch.round` behave — the three implementations
     * that produced and validated this asset. `floor(x + 0.5)`, which is what
     * `Math.round(Float)` and most hand-written quantisers do, differs at every tie and biases
     * the whole spectrogram upward by half a code.
     */
    @Test
    fun roundsHalfToEvenAtTheTie() {
        val one = 1.0f
        val zp = 0
        assertEquals("0.5 ties to the even neighbour 0", 0, NpuQuantize.quantise(0.5f, one, zp))
        assertEquals("1.5 ties to the even neighbour 2", 2, NpuQuantize.quantise(1.5f, one, zp))
        assertEquals("2.5 ties to the even neighbour 2", 2, NpuQuantize.quantise(2.5f, one, zp))
        assertEquals("3.5 ties to the even neighbour 4", 4, NpuQuantize.quantise(3.5f, one, zp))

        // Off the tie the ordinary nearest-integer answer still has to hold, in both directions.
        assertEquals("0.4 is nearer 0", 0, NpuQuantize.quantise(0.4f, one, zp))
        assertEquals("0.6 is nearer 1", 1, NpuQuantize.quantise(0.6f, one, zp))
        assertEquals("2.6 is nearer 3", 3, NpuQuantize.quantise(2.6f, one, zp))

        // Negative ties, reached through a zero point that leaves room below it.
        assertEquals("-0.5 ties to the even neighbour 0", 100, NpuQuantize.quantise(-0.5f, one, 100))
        assertEquals("-1.5 ties to the even neighbour -2", 98, NpuQuantize.quantise(-1.5f, one, 100))
        assertEquals("-2.5 ties to the even neighbour -2", 98, NpuQuantize.quantise(-2.5f, one, 100))
    }

    /**
     * The round trip over the whole representable window: quantise then dequantise must land
     * within half a quantisation step of where it started. Half, not one — that is what
     * nearest-integer rounding guarantees, and asserting the loose bound would accept an
     * implementation that truncates instead of rounds (whose error reaches a full step).
     */
    @Test
    fun roundTripStaysWithinHalfAQuantisationStep() {
        val tolerance = scale.toDouble() * 0.5 + 1e-9
        // 4001 points across [-1.5, 1.56], the window the published parameters actually describe.
        val lo = scale.toDouble() * (0 - zeroPoint)
        val hi = scale.toDouble() * (65535 - zeroPoint)
        var worst = 0.0
        for (i in 0..4000) {
            val x = (lo + (hi - lo) * i / 4000.0).toFloat()
            val q = NpuQuantize.quantise(x, scale, zeroPoint)
            assertTrue("code $q for x=$x escaped the uint16 domain", q in 0..65535)
            val err = Math.abs(dequantise(q) - x)
            if (err > worst) worst = err
            assertTrue(
                "round trip of $x gave code $q -> ${dequantise(q)}, error $err exceeds half a " +
                    "step ($tolerance)",
                err <= tolerance
            )
        }
        assertTrue(
            "the sweep must actually exercise rounding — a worst-case error of 0 would mean every " +
                "sample landed exactly on a code and the assertion above proved nothing",
            worst > 0.0
        )
    }

    /**
     * The 65535 code has bit pattern `0xFFFF`, which is `-1` as a signed `Short`. The buffer
     * handed to the encoder is a byte-for-byte `uint16` block, so the top half of the domain MUST
     * be stored as negative shorts. An implementation that "protected" against that by clamping at
     * `Short.MAX_VALUE` would silently cut the spectrogram's whole upper half.
     */
    @Test
    fun melToU16WritesTheRawUnsignedBitPatternAcrossTheWholeDomain() {
        val values = floatArrayOf(
            (scale.toDouble() * (0 - zeroPoint)).toFloat(),      // code 0     -> 0x0000
            0.0f,                                               // code 32072 -> 0x7D48
            (scale.toDouble() * (32768 - zeroPoint)).toFloat(),  // code 32768 -> 0x8000 == Short.MIN
            (scale.toDouble() * (65535 - zeroPoint)).toFloat(),  // code 65535 -> 0xFFFF == -1
        )
        val mel = FloatBuffer.allocate(NpuQuantize.MEL_VALUES)
        for (i in 0 until NpuQuantize.MEL_VALUES) mel.put(i, values[i % values.size])
        val out = ShortBuffer.allocate(NpuQuantize.MEL_VALUES)

        NpuQuantize.melToU16(mel, scale, zeroPoint, out)

        val expected = intArrayOf(0, 32072, 32768, 65535)
        for (i in 0 until values.size) {
            assertEquals(
                "value ${values[i]} must be stored as the raw uint16 code ${expected[i]}",
                expected[i],
                out.get(i).toInt() and 0xFFFF
            )
        }
        assertEquals("code 32768 must be stored as Short.MIN_VALUE", Short.MIN_VALUE, out.get(2))
        assertEquals("code 65535 must be stored as -1", (-1).toShort(), out.get(3))
        assertNotEquals(
            "the top of the domain must NOT be clamped to Short.MAX_VALUE",
            Short.MAX_VALUE,
            out.get(3)
        )
    }

    /**
     * Order, completeness and non-consumption. The mel is bin-major with a dest stride of 3000
     * (`whisper_get_mel_segment`'s contract), and the encoder reads `input_features` in exactly
     * that layout — so the quantiser must be a straight elementwise map, index for index, over
     * all 240,000 values. It must also leave both buffer positions where it found them: the same
     * ByteBuffer is handed to `nativeEncode` immediately afterwards, and a consumed position
     * would send a zero-length view to the DSP.
     *
     * The factories are exercised here rather than in a test of their own because they exist for
     * exactly one reason — [java.nio.ByteBuffer.allocateDirect] defaults to BIG_ENDIAN, and a mel
     * read (or a `uint16` block written) without `nativeOrder()` is byte-swapped into
     * plausible-looking garbage that nothing downstream can detect.
     */
    @Test
    fun melToU16CopiesEveryValueInOrderAndLeavesBufferPositionsAlone() {
        val melBytes = NpuQuantize.newMelFloatBuffer()
        assertEquals("the mel buffer is 80 x 3000 float32", 960_000, melBytes.capacity())
        assertEquals(
            "and it must be in native order, or every float read back is byte-swapped",
            ByteOrder.nativeOrder(), melBytes.order()
        )
        assertTrue("pcmToMel requires a DIRECT buffer", melBytes.isDirect)

        val quantBytes = NpuQuantize.newInputFeaturesBuffer()
        assertEquals("input_features is 80 x 3000 uint16", 480_000, quantBytes.capacity())
        assertEquals(
            "and it must be in native order, or every uint16 reaches the DSP byte-swapped",
            ByteOrder.nativeOrder(), quantBytes.order()
        )
        assertTrue("nativeEncode requires a DIRECT buffer", quantBytes.isDirect)

        val mel = melBytes.asFloatBuffer()
        val out = quantBytes.asShortBuffer()
        // A different value per index, spread across the window, so a transposition or an
        // off-by-one cannot hide behind a constant fill.
        for (i in 0 until NpuQuantize.MEL_VALUES) {
            mel.put(i, (scale.toDouble() * (i % 65536 - zeroPoint)).toFloat())
        }

        NpuQuantize.melToU16(mel, scale, zeroPoint, out)

        assertEquals("melToU16 must not consume the mel buffer", 0, mel.position())
        assertEquals("melToU16 must not consume the output buffer", 0, out.position())
        assertEquals(
            "and it must not disturb the underlying ByteBuffer's position either",
            0, quantBytes.position()
        )
        for (i in 0 until NpuQuantize.MEL_VALUES) {
            assertEquals(
                "index $i must carry its own value, in place",
                i % 65536,
                out.get(i).toInt() and 0xFFFF
            )
        }
    }

    /**
     * The shapes are fixed by the asset — `[1,80,3000]`, no dynamic dimension — so anything else
     * is a wiring mistake, and the one that actually happens is handing over the 960,000-byte mel
     * where the 480,000-byte quantised block belongs (or its `asFloatBuffer` view where the
     * `asShortBuffer` view belongs). Refused by count, loudly, at the boundary, rather than
     * writing 240,000 codes into a 480,000-slot buffer and encoding half a spectrogram.
     */
    @Test
    fun melToU16RefusesBuffersThatAreNotTheEncodersExactShape() {
        val good = FloatBuffer.allocate(NpuQuantize.MEL_VALUES)
        val goodOut = ShortBuffer.allocate(NpuQuantize.MEL_VALUES)

        val shortMel = assertThrows(IllegalArgumentException::class.java) {
            NpuQuantize.melToU16(FloatBuffer.allocate(NpuQuantize.MEL_VALUES - 1), scale, zeroPoint, goodOut)
        }
        assertTrue(
            "the message must name both counts, or the caller cannot tell which side is wrong: " +
                shortMel.message,
            shortMel.message!!.contains("239999") && shortMel.message!!.contains("240000")
        )

        val longOut = assertThrows(IllegalArgumentException::class.java) {
            NpuQuantize.melToU16(good, scale, zeroPoint, ShortBuffer.allocate(NpuQuantize.MEL_VALUES * 2))
        }
        assertTrue("the message must name the output counts: " + longOut.message,
            longOut.message!!.contains("480000") && longOut.message!!.contains("240000"))

        // A non-positive scale is the other silent catastrophe: 0f divides every value to an
        // infinity and pins the entire spectrogram to a rail, which looks like a loud signal.
        assertThrows(IllegalArgumentException::class.java) {
            NpuQuantize.melToU16(good, 0.0f, zeroPoint, goodOut)
        }
        assertThrows(IllegalArgumentException::class.java) {
            NpuQuantize.melToU16(good, -scale, zeroPoint, goodOut)
        }
        // A zero point outside the domain cannot be honoured by a uint16 buffer at all.
        assertThrows(IllegalArgumentException::class.java) {
            NpuQuantize.melToU16(good, scale, -1, goodOut)
        }
        assertThrows(IllegalArgumentException::class.java) {
            NpuQuantize.melToU16(good, scale, 65536, goodOut)
        }

        // The happy path still runs after all that, on the same buffers: a guard that threw must
        // not have consumed, resized or partially written anything it rejected.
        NpuQuantize.melToU16(good, scale, zeroPoint, goodOut)
        assertEquals("a rejected call must leave the buffers usable", 0, good.position())
        assertEquals("a zero mel quantises to the zero point throughout", zeroPoint,
            goodOut.get(0).toInt() and 0xFFFF)
        assertEquals(zeroPoint, goodOut.get(NpuQuantize.MEL_VALUES - 1).toInt() and 0xFFFF)
    }
}
