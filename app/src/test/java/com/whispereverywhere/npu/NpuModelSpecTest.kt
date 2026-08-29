package com.whispereverywhere.npu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [NpuModelSpec] — the census the tier CARRIES, checked against the census 4.0 COMPILED (4.1 L2).
 *
 * ### What makes this suite worth its length
 *
 * `qnn_asr.cpp` shipped four numbers per graph as `constexpr GraphExpectation`s —
 * `{"encoder", 1, 24, 480000, 27648000}` and `{"decoder", 51, 25, 31316376, 3771698}` — and the F2
 * guard refuses any asset whose own enumeration disagrees with them. Those numbers were **confirmed
 * on device**: they are the shape the tier actually ran at. This suite asserts that
 * [NpuModelSpec.SMALL], which computes all ten from eight factors, reproduces every one of them
 * **exactly**.
 *
 * That comparison is the whole point of the task. A formula that produced *different* numbers would
 * not be a refactor with a bug in it; it would be a session sized against an asset that does not
 * exist, and the symptom — per the guard's own message — arrives much later as a fluent and wrong
 * transcript. So the formula is admitted only on the evidence that it is a **second reading of the
 * same asset**.
 *
 * ### And why the formula has to exist at all
 *
 * Because the guard stops being a guard the moment a second asset exists. `npu-turbo` differs at
 * every one of these values *by construction* — 128 mel bins, 4 decoder layers, 20 heads, a
 * 51,866-entry vocabulary — so a file-scope constant would fire on a perfectly correct asset. The
 * expectation has to belong to the tier, and this is where it does.
 */
class NpuModelSpecTest {

    private val small = NpuModelSpec.SMALL

    /**
     * `npu-turbo`'s scalars, HAND-COMPUTED here and deliberately not a shipped row.
     *
     * `NpuModelSpec` has exactly one row today and `forTier` says so; L4 lands `TURBO` and replaces
     * this local with it. Until then it is the second input the formula is exercised at, because a
     * derivation checked at one point is a table of constants with extra steps — a transposed
     * factor (`heads` for `decLayers`, say) survives every assertion made at a row where the two
     * happen to be equal, and for `whisper-small` they are both 12.
     *
     * Every expected value below comes from the plan's asset block, which read them out of turbo's
     * own `metadata.json`.
     */
    private val turboShaped = NpuModelSpec(
        tierId = "hand-computed-second-row",
        melBins = 128,
        melFrames = NpuModelSpec.MEL_FRAMES,
        decLayers = 4,
        heads = 20,
        headDim = NpuModelSpec.HEAD_DIM,
        audioCtx = NpuModelSpec.AUDIO_CTX,
        tokens = WhisperTokenFamily(langCount = 100, maxPositions = 200),
        // 4.1 L3. A 128-bin tier has no donor — the only 128-bin model in the catalog is `ultra`,
        // 574 MB and not necessarily installed — so its filterbank ships as an asset. `melAsset`
        // has no default for the same reason `spec` and `family` have none: a row that does not
        // say where its mel comes from is a row whose author did not decide.
        melAsset = NpuModelSpec.MELBANK_128_ASSET,
    )

    // ---------------------------------------------------------------- the shipped census

    /**
     * **THE ASSERTION THIS TASK EXISTS FOR.** `kEncoderExpectation{"encoder", 1, 24, 480000,
     * 27648000}`, reproduced from the scalars.
     *
     * Each factor, named, because each is a different way to be wrong:
     *  - `encIn = 1` — the encoder has one input, `input_features`;
     *  - `encOut = 2 * decLayers` — a k and a v cross-KV tensor per decoder layer, 24 for twelve;
     *  - `encInBytes = melBins * melFrames * 2` — the `ufixed16` block, two bytes a code;
     *  - `encOutBytes = 2*decLayers * heads * headDim * audioCtx` — 24 tensors of
     *    `d_model x audioCtx`, where `d_model` is `heads * headDim`. It is the only one of the four
     *    with four factors in it, and the only one where a transposition is invisible in the total.
     */
    @Test
    fun smallReproducesTheShippedEncoderCensus() {
        assertEquals("kEncoderExpectation.numIn", 1, small.encIn)
        assertEquals("kEncoderExpectation.numOut — 2 x 12 cross-KV", 24, small.encOut)
        assertEquals("kEncoderExpectation.inBytes — 80 x 3000 x 2", 480_000, small.encInBytes)
        assertEquals(
            "kEncoderExpectation.outBytes — 24 x (12 x 64) x 1500. This is the device-confirmed " +
                "number the F2 guard compares an asset's own enumeration against; a formula that " +
                "disagrees with it here would size 27 MiB of cross-KV against an asset that does " +
                "not exist.",
            27_648_000,
            small.encOutBytes
        )
    }

    /**
     * `kDecoderExpectation{"decoder", 51, 25, 31316376, 3771698}`, reproduced.
     *
     *  - `decIn = 3 + 4*decLayers` — `input_ids`, `position_ids`, `attention_mask`, plus k/v cross
     *    and k/v self per layer;
     *  - `decOut = 1 + 2*decLayers` — `logits` plus the self-KV `_out` half;
     *  - `decInBytes` — the two int32 scalars, the 200-column `ufixed16` mask, one self-KV set, and
     *    the cross-KV read IN PLACE out of the encoder's own buffers;
     *  - `decOutBytes = vocab*2 + selfKvBytes` — this is the one that moves with the VOCABULARY, so
     *    it is the value that separates the two families at load.
     */
    @Test
    fun smallReproducesTheShippedDecoderCensus() {
        assertEquals("kDecoderExpectation.numIn — 3 + 4 x 12", 51, small.decIn)
        assertEquals("kDecoderExpectation.numOut — 1 + 2 x 12", 25, small.decOut)
        assertEquals(
            "kDecoderExpectation.inBytes — 4 + 4 + 200*2 + selfKv + crossKv",
            31_316_376,
            small.decInBytes
        )
        assertEquals(
            "kDecoderExpectation.outBytes — 51865 x 2 + selfKv. The vocabulary term is why this " +
                "number, and not any of the other seven, is the one a wrong token family shows up in.",
            3_771_698,
            small.decOutBytes
        )
    }

    /** The two buffer sizes `NpuQuantize` allocates, and the element count both sides share. */
    @Test
    fun smallReproducesTheShippedMelBufferSizes() {
        assertEquals("240,000 float32 values out of pcmToMel", 240_000, small.melValues)
        assertEquals("960,000 B — what WhisperNative.pcmToMel writes", 960_000, small.melFloatBytes)
        assertEquals(
            "480,000 B — the ufixed16 block nativeEncode copies into input_features",
            480_000,
            small.inputFeaturesBytes
        )
        assertEquals(
            "and it is the SAME number as the encoder census's inBytes, because the encoder has " +
                "exactly one input. Two names for one quantity, kept apart because one is a graph " +
                "census and the other is an allocation.",
            small.encInBytes,
            small.inputFeaturesBytes
        )
        assertEquals(
            "the float mel is exactly twice the quantised block — 4 bytes a value against 2",
            2 * small.inputFeaturesBytes,
            small.melFloatBytes
        )
    }

    /**
     * The ping-pong intermediate, 3,667,968 B a set.
     *
     * It is not one of the ten census values because native reads it off the asset's own tensors
     * rather than being told it; it is here because BOTH decoder byte totals are built on it, so a
     * wrong `maxPositions - 1` would move two of the four numbers above at once and the arithmetic
     * that relates them should be checkable on its own.
     */
    @Test
    fun theSelfKvIntermediateIsTheShippedPingPongSize() {
        assertEquals(
            "24 tensors x (12 x 64) x 199 slots — the cache is maxPositions-1 deep, never " +
                "maxPositions: the 200th mask column is the CURRENT token's own key, which is not " +
                "in the cache",
            3_667_968,
            small.selfKvBytes
        )
        assertEquals(
            "and both decoder totals are built on it",
            small.decInBytes - small.encOutBytes - small.selfKvBytes,
            4 + 4 + small.maxPositions * 2
        )
    }

    // ---------------------------------------------------------------- the formula, exercised twice

    /**
     * The same formula at `npu-turbo`'s scalars, against numbers hand-computed from the asset block.
     *
     * Four of the eight factors differ from `whisper-small` and every one of the ten values moves.
     * The pair `decLayers = 4` / `heads = 20` is the one that matters most: at `whisper-small` both
     * are 12, so a formula that had them transposed would pass every assertion above.
     */
    @Test
    fun theFormulaIsCheckedAgainstAHandComputedSecondRow() {
        assertEquals("encIn", 1, turboShaped.encIn)
        assertEquals("encOut — 2 x 4", 8, turboShaped.encOut)
        assertEquals("encInBytes — 128 x 3000 x 2", 768_000, turboShaped.encInBytes)
        assertEquals("encOutBytes — 8 x (20 x 64) x 1500", 15_360_000, turboShaped.encOutBytes)
        assertEquals("decIn — 3 + 4 x 4", 19, turboShaped.decIn)
        assertEquals("decOut — 1 + 2 x 4", 9, turboShaped.decOut)
        assertEquals("selfKvBytes — 8 x 1280 x 199", 2_037_760, turboShaped.selfKvBytes)
        assertEquals("decInBytes", 17_398_168, turboShaped.decInBytes)
        assertEquals("decOutBytes — 51866 x 2 + selfKv", 2_141_492, turboShaped.decOutBytes)
        assertEquals("melFloatBytes — 128 x 3000 x 4", 1_536_000, turboShaped.melFloatBytes)
        assertEquals("inputFeaturesBytes", 768_000, turboShaped.inputFeaturesBytes)
        assertEquals(
            "and the vocabulary term differs by exactly the two bytes one extra language costs",
            small.decOutBytes - small.selfKvBytes + 2,
            turboShaped.decOutBytes - turboShaped.selfKvBytes
        )
    }

    /**
     * **Every one of the eight factors is load-bearing**: perturb any single one and the census
     * changes.
     *
     * This is the assertion a transposition or a dropped factor cannot survive. A formula that read
     * `heads` where it meant `headDim`, or that quietly ignored `audioCtx`, would reproduce
     * `whisper-small` exactly (its factors are 12/12/64 — two of them equal) and would then be
     * silently wrong for every other asset. Rather than enumerate the ways to be wrong, this
     * enumerates the factors and demands that each one MATTERS.
     */
    @Test
    fun noFactorOfTheCensusCanBeDroppedOrTransposedUnnoticed() {
        val base = census(small)
        val perturbed = mapOf(
            "melBins" to small.copy(melBins = 128),
            "melFrames" to small.copy(melFrames = small.melFrames + 1),
            "decLayers" to small.copy(decLayers = small.decLayers + 1),
            "heads" to small.copy(heads = small.heads + 1),
            "headDim" to small.copy(headDim = small.headDim + 1),
            "audioCtx" to small.copy(audioCtx = small.audioCtx + 1),
            "maxPositions" to small.copy(
                tokens = WhisperTokenFamily(langCount = 99, maxPositions = small.maxPositions + 1)
            ),
            "vocab (langCount)" to small.copy(
                tokens = WhisperTokenFamily(langCount = 100, maxPositions = small.maxPositions)
            ),
        )
        perturbed.forEach { (factor, spec) ->
            assertNotEquals(
                "moving `$factor` by one must change the census. If it does not, that factor is " +
                    "not in the formula — and the formula would then reproduce whisper-small " +
                    "exactly while being wrong for every other asset, which is the only failure " +
                    "shape this seam cannot see.",
                base,
                census(spec)
            )
        }
        assertEquals("eight factors, and all eight are checked", 8, perturbed.size)
    }

    private fun census(spec: NpuModelSpec): List<Int> = listOf(
        spec.encIn, spec.encOut, spec.encInBytes, spec.encOutBytes,
        spec.decIn, spec.decOut, spec.decInBytes, spec.decOutBytes,
        spec.melFloatBytes, spec.inputFeaturesBytes,
    )

    // ---------------------------------------------------------------- the tier table

    /**
     * `forTier` answers for the one tier that exists TODAY, and null for everything else.
     *
     * **A red here at L4 is that task's own signal**, not a break: `npu-turbo` is in the list below
     * precisely so that adding the row makes this fail and the failure is read as the row landing.
     *
     * The null arm is what `NpuBackendSelector` routes on: a tier id with no spec cannot construct
     * `NpuWhisperBackend` at all, because that constructor takes a spec with no default.
     */
    @Test
    fun forTierAnswersForTheNpuTierAndNothingElseToday() {
        assertSame("the `npu` tier resolves to the SMALL row", small, NpuModelSpec.forTier("npu"))
        listOf(
            "npu-turbo", "turbo", "multi", "pro", "eco", "base", "ultra", "", "NPU", "npu ", null
        ).forEach {
            assertNull(
                "`$it` has no spec today. If this went red because `npu-turbo` gained one, that is " +
                    "4.1 L4 landing its row — delete this entry from the list rather than " +
                    "loosening the assertion.",
                NpuModelSpec.forTier(it)
            )
        }
        assertEquals(
            "and the row's own id is the app's ONE npu tier constant, never a fresh literal — a " +
                "typo in a second copy routes silently to the CPU backend with nothing to see",
            NpuAssetImport.TIER_ID,
            small.tierId
        )
    }

    // ---------------------------------------------------------------- the three that stay native

    /**
     * `headDim`, `audioCtx` and `melFrames` are **fields on the spec and constants in native**, and
     * the two halves must agree.
     *
     * They are not among `nativeInit`'s five scalars deliberately: all seven published Whisper AI
     * Hub assets in the research survey carry them identically, and an argument carrying a number
     * that cannot vary is a number a caller can get wrong. They are fields HERE so that the Kotlin
     * census is complete — the formula needs all eight factors — and `NpuNativeContractTest` pins
     * the native literals against these three values, which is what keeps the two derivations one
     * derivation.
     */
    @Test
    fun theThreeUniversalShapeFactsAreFieldsAndCarryTheValuesNativeKeeps() {
        assertEquals("kHeadDim", 64, NpuModelSpec.HEAD_DIM)
        assertEquals("kAudioCtx — the 30 s window's 1500 encoder frames", 1500, NpuModelSpec.AUDIO_CTX)
        assertEquals("kMelFrames", 3000, NpuModelSpec.MEL_FRAMES)
        assertEquals(64, small.headDim)
        assertEquals(1500, small.audioCtx)
        assertEquals(3000, small.melFrames)
        assertEquals(
            "the row takes them from the named constants rather than restating the numbers",
            listOf(NpuModelSpec.HEAD_DIM, NpuModelSpec.AUDIO_CTX, NpuModelSpec.MEL_FRAMES),
            listOf(small.headDim, small.audioCtx, small.melFrames)
        )
    }

    // ---------------------------------------------------------------- the refusals

    /**
     * An implausible scalar set is refused at construction — the Kotlin half of the table
     * `nativeInit` enforces before it opens a file.
     *
     * Native has the same table for the same reason stated the other way round: a garbage scalar
     * must not reach an allocation. Here the reason is narrower and just as real — every derived
     * value below is an `Int`, and these bounds are exactly what makes the worst case
     * (`2*64*64*64*1023 + 64*64*64*2*1500`) fit in one.
     */
    @Test
    fun anImplausibleScalarSetIsRefusedAtConstruction() {
        fun refused(factor: String, build: () -> NpuModelSpec) {
            try {
                val got = build()
                fail("$factor must be refused; got a spec claiming ${got.encOutBytes} encoder bytes")
            } catch (expected: IllegalArgumentException) {
                assertTrue(
                    "the refusal must name the factor it rejected. Got: ${expected.message}",
                    expected.message.orEmpty().contains(factor)
                )
            }
        }
        listOf(0, 1, 79, 81, 127, 129, -1).forEach { bad ->
            refused("melBins") { small.copy(melBins = bad) }
        }
        listOf(0, -1, 65, Int.MAX_VALUE).forEach { bad ->
            refused("decLayers") { small.copy(decLayers = bad) }
            refused("heads") { small.copy(heads = bad) }
        }
        listOf(0, -1, 16385).forEach { bad -> refused("melFrames") { small.copy(melFrames = bad) } }
        listOf(0, -1).forEach { bad ->
            refused("headDim") { small.copy(headDim = bad) }
            refused("audioCtx") { small.copy(audioCtx = bad) }
        }
        assertTrue(
            "80 and 128 are the only two mel widths any published whisper asset uses, so they are " +
                "the only two this seam accepts — a 96 would be a typo that allocated a buffer no " +
                "graph wants",
            NpuModelSpec.forTier("npu")!!.melBins == 80
        )

        // AND THE PRODUCTS, NOT ONLY THE FACTORS (4.1 L2 micro-round).
        //
        // The bounds above are per-factor and loose on purpose: `headDim <= 1024` and
        // `audioCtx <= 65536` exist to catch a typo, not to bound a product. At their extremes
        // `2 x 64 x 64 x 1024 x 65536` is ~5.5e11 — three orders of magnitude past Int.MAX_VALUE —
        // and it wraps to a SMALL POSITIVE number. That is the worst shape a census value can take:
        // nothing downstream reads as wrong, and the load-time guard then compares a real asset
        // against a total no arithmetic produced. Every factor in the two specs below is
        // individually legal, which is exactly why the guard has to be on the product.
        listOf<() -> NpuModelSpec>(
            { small.copy(headDim = 1024, audioCtx = 65536) },
            { small.copy(decLayers = 64, heads = 64, headDim = 1024, audioCtx = 65536) },
        ).forEach { build ->
            try {
                val got = build()
                fail("a census of ${got.encOutBytes} B must be refused rather than wrapped")
            } catch (expected: IllegalArgumentException) {
                assertTrue(
                    "the refusal must name the overflow and the widest term. Got: " +
                        "${expected.message}",
                    expected.message.orEmpty().contains("overflows a 32-bit Int")
                )
            }
        }
        assertTrue(
            "and the shipped row is nowhere near the rail — 31,316,376 B against 2,147,483,647",
            small.decInBytes < Int.MAX_VALUE / 64
        )
    }

    /**
     * `maxPositions` has exactly ONE home, and it is the token family.
     *
     * It belongs there because it is the decode loop's bound and `NpuDecodePolicy` — which owns
     * that loop's configuration — is family-parametrised: a spec-parametrised `maxTokensFor` would
     * be the only place in this design where answering one question took two objects. The spec
     * reads it THROUGH the family rather than restating it, so the census's `maxPositions - 1` and
     * the policy's `maxPositions - promptLen` can never be computed from two different numbers.
     */
    @Test
    fun maxPositionsHasExactlyOneHomeAndTheSpecReadsItThrough() {
        assertEquals(200, small.maxPositions)
        assertEquals(small.tokens.maxPositions, small.maxPositions)
        val widened = small.copy(
            tokens = WhisperTokenFamily(langCount = 99, maxPositions = 256)
        )
        assertEquals(
            "widening the family widens the spec, because there is nothing else to widen",
            256,
            widened.maxPositions
        )
        assertEquals(
            "and the self-KV depth follows it — 255 slots, not 256",
            2 * small.decLayers * small.heads * small.headDim * 255,
            widened.selfKvBytes
        )
    }
}
