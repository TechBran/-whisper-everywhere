package com.whispereverywhere.npu

/**
 * One NPU tier's model shape, and the graph census that follows from it (4.1 L2).
 *
 * ### The finding this closes
 *
 * 4.0 compiled its census in. `qnn_asr.cpp` carried
 * `constexpr GraphExpectation kEncoderExpectation{"encoder", 1, 24, 480000, 27648000}` and its
 * decoder twin, and the F2 guard refuses any asset whose own enumeration disagrees with them. That
 * is a good guard and it caught real things — but every one of those numbers is a property of
 * `whisper-small` in particular, so the guard stops being a guard the moment a second asset exists.
 * `npu-turbo` differs at **all** of them by construction (128 mel bins, 4 decoder layers, 20 heads,
 * a 51,866-entry vocabulary), so a file-scope constant would fire on a perfectly correct asset and
 * the only available repair would be to weaken or delete the check.
 *
 * So the expectation belongs to the TIER. This class is where it lives, `nativeInit` receives the
 * five scalars it varies by, and native derives the same census from them at the top of the call.
 *
 * ### The formula — eight factors, five of which vary
 *
 * ```
 *   encIn              = 1                                                   1          1
 *   encOut             = 2*decLayers                                        24          8
 *   encInBytes         = melBins * melFrames * 2                       480,000    768,000
 *   encOutBytes        = 2*decLayers * heads * headDim * audioCtx   27,648,000 15,360,000
 *   decIn              = 3 + 4*decLayers                                    51         19
 *   decOut             = 1 + 2*decLayers                                    25          9
 *   selfKvBytes        = 2*decLayers * heads * headDim * (maxPositions-1)
 *                                                                    3,667,968  2,037,760
 *   decInBytes         = 4 + 4 + maxPositions*2 + selfKvBytes + encOutBytes
 *                                                                   31,316,376 17,398,168
 *   decOutBytes        = vocab*2 + selfKvBytes                        3,771,698  2,141,492
 *   melFloatBytes      = melBins * melFrames * 4                        960,000  1,536,000
 *   inputFeaturesBytes = melBins * melFrames * 2                        480,000    768,000
 * ```
 *
 * Each factor, named once so the arithmetic is readable rather than merely correct:
 *
 *  - **`2*decLayers`** is k and v per decoder layer. It is the encoder's whole output (the
 *    cross-attention KV the decoder reads in place) and half the decoder's own IO.
 *  - **`heads * headDim`** is `d_model` — 768 for `whisper-small`, 1280 for `large-v3-turbo`. Every
 *    KV tensor is `d_model` wide, which is why those two factors always appear together and why a
 *    formula that transposed them would still be right for `whisper-small`, where `heads` is 12 and
 *    `decLayers` is also 12.
 *  - **`maxPositions - 1`** is the self-KV cache depth, and the minus one is load-bearing: the
 *    mask's last column is the CURRENT token's own key, which is not in the cache. 199 slots for a
 *    200-column mask.
 *  - **the `4 + 4`** in `decInBytes` is `input_ids` and `position_ids`, one int32 each. The
 *    `maxPositions*2` beside them is `attention_mask`, one `ufixed16` code per column.
 *  - **`vocab*2`** is `logits`, `ufixed16`. It is the only term that moves with the token family,
 *    which makes `decOutBytes` the value a wrong vocabulary shows up in first.
 *
 * ### Three factors that are NOT passed to native, and why
 *
 * `headDim = 64`, `audioCtx = 1500` and `melFrames = 3000` are identical across all seven published
 * Whisper AI Hub assets in the research survey. They are fields here — the Kotlin census needs all
 * eight — but native keeps them as `constexpr`, because an argument carrying a number that cannot
 * vary is a number a caller can get wrong. `NpuNativeContractTest` pins the three native literals
 * against these three fields, and that pin is what keeps the two derivations one derivation: they
 * exchange five numbers and agree about three, and nothing else relates them at compile time.
 *
 * ### Every derived value is an `Int`, and the refusal table is what makes that safe
 *
 * The per-factor bounds alone are **not** enough to promise that, and saying so would be the kind
 * of claim this file exists to refuse. `headDim ≤ 1024` and `audioCtx ≤ 65536` are deliberately
 * loose — they exist to catch a typo, not to bound a product — and at their extremes
 * `2 x 64 x 64 x 1024 x 65536` is far past `Int.MAX_VALUE`. An overflowed census does not throw:
 * it wraps to a smaller positive number that the guard would then compare an asset against.
 *
 * So the promise is a GUARD rather than an argument. `init` computes the three largest terms in
 * `Long` and refuses the spec if any of them will not fit an `Int` — which makes every derived
 * value below safe by construction, at every combination of bounds, including ones a future row
 * widens.
 */
data class NpuModelSpec(
    /** The catalog tier id this row describes. [forTier] is the only intended lookup. */
    val tierId: String,
    /** Mel bands: 80 for every `whisper-small`-class asset, 128 for `large-v3`-class. */
    val melBins: Int,
    /** Frames in the encoder's fixed 30 s window. Universal; see [MEL_FRAMES]. */
    val melFrames: Int,
    /** Decoder layers. Drives the whole cross-KV and self-KV population. */
    val decLayers: Int,
    /** Attention heads. With [headDim] this is `d_model`. */
    val heads: Int,
    /** Per-head width. Universal; see [HEAD_DIM]. */
    val headDim: Int,
    /** The encoder's output length — the 30 s window's frames after its stride. See [AUDIO_CTX]. */
    val audioCtx: Int,
    /** This tier's vocabulary and context window. See [WhisperTokenFamily]. */
    val tokens: WhisperTokenFamily,
) {

    init {
        require(melBins == 80 || melBins == 128) {
            "melBins $melBins is neither 80 nor 128. Those are the only two mel widths any " +
                "published whisper asset uses, so a third value is a typo that would allocate a " +
                "spectrogram buffer no graph wants — and the encoder does not reject a wrongly " +
                "shaped input so much as transcribe it."
        }
        require(melFrames in 1..16384) {
            "melFrames $melFrames is outside 1..16384; the 30 s window is 3000 frames on every " +
                "published asset and this bound exists so the byte totals below stay inside an Int."
        }
        require(decLayers in 1..64) { "decLayers $decLayers is outside 1..64" }
        require(heads in 1..64) { "heads $heads is outside 1..64" }
        require(headDim in 1..1024) { "headDim $headDim is outside 1..1024" }
        require(audioCtx in 1..65536) { "audioCtx $audioCtx is outside 1..65536" }

        // AND THE PRODUCTS MUST FIT THE Int THEY ARE STORED IN.
        //
        // The six bounds above are per-factor and loose on purpose — they catch a typo, they do not
        // bound a product. At their extremes the cross-KV term alone is 2 x 64 x 64 x 1024 x 65536,
        // which is ~5.5e11 and wraps. A wrapped census is the worst possible shape for this value:
        // it is a small POSITIVE number, so nothing downstream looks wrong, and the census guard
        // then refuses (or worse, accepts) an asset against a byte total that no arithmetic
        // produced. Computed in Long and refused here, once, so every derived value below is safe
        // by construction rather than by the row that happens to be in the table.
        val crossKv = 2L * decLayers * heads * headDim * audioCtx
        val selfKv = 2L * decLayers * heads * headDim * (tokens.maxPositions - 1)
        val widest = maxOf(
            crossKv,
            8L + tokens.maxPositions * 2L + selfKv + crossKv,
            tokens.vocab * 2L + selfKv,
            melBins.toLong() * melFrames * 4L,
        )
        require(widest <= Int.MAX_VALUE) {
            "this spec's census overflows a 32-bit Int: the widest term is $widest B, past " +
                "${Int.MAX_VALUE}. The factors are individually inside their bounds, which is " +
                "exactly why this is checked on the PRODUCTS — an overflowed byte total wraps to a " +
                "small positive number that reads like a plausible census and is compared against " +
                "a real asset as though it were one."
        }
    }

    /**
     * The decoder's context window, read through the token family — **one home, two readers**.
     *
     * It is the family's because it bounds the decode loop and [NpuDecodePolicy] is
     * family-parametrised. The census needs it too (`maxPositions - 1` is the cache depth,
     * `maxPositions * 2` is the mask), so it is read through rather than restated: the policy's
     * `maxPositions - promptLen` and this file's `maxPositions - 1` can then never be computed from
     * two numbers that drifted apart.
     */
    val maxPositions: Int get() = tokens.maxPositions

    // ---------------------------------------------------------------- intermediates

    /** 240,000 for an 80-bin mel — the element count both sides of the quantiser share. */
    val melValues: Int = melBins * melFrames

    /**
     * One ping-pong set's bytes. Not part of the census native compares (it reads the depth off the
     * asset's own tensors), but BOTH decoder byte totals are built on it, so a wrong
     * `maxPositions - 1` moves two of the four census numbers at once.
     */
    val selfKvBytes: Int = 2 * decLayers * heads * headDim * (maxPositions - 1)

    // ---------------------------------------------------------------- the ten census values

    /** The encoder's input count. One: `input_features`. */
    val encIn: Int = 1

    /** The encoder's output count: k and v cross-KV per decoder layer. */
    val encOut: Int = 2 * decLayers

    /** The encoder's input bytes — the `ufixed16` mel block. */
    val encInBytes: Int = melBins * melFrames * 2

    /** The encoder's output bytes — the whole cross-KV, which the decoder reads in place. */
    val encOutBytes: Int = 2 * decLayers * heads * headDim * audioCtx

    /** The decoder's input count: the three step inputs plus k/v cross and k/v self per layer. */
    val decIn: Int = 3 + 4 * decLayers

    /** The decoder's output count: `logits` plus the self-KV `_out` half. */
    val decOut: Int = 1 + 2 * decLayers

    /** The decoder's input bytes: the two int32 scalars, the mask, one self-KV set, the cross-KV. */
    val decInBytes: Int = 4 + 4 + maxPositions * 2 + selfKvBytes + encOutBytes

    /** The decoder's output bytes: `logits` plus the self-KV set it writes. */
    val decOutBytes: Int = tokens.vocab * 2 + selfKvBytes

    /** 960,000 B — the float32 mel `WhisperNative.pcmToMel` writes. */
    val melFloatBytes: Int = melBins * melFrames * 4

    /**
     * 480,000 B — the `ufixed16` block `nativeEncode` copies into `input_features`.
     *
     * The same number as [encInBytes], because the encoder has exactly one input. Both names exist
     * because they answer different questions: one is a graph census the load compares against, the
     * other is an allocation the quantiser fills.
     */
    val inputFeaturesBytes: Int = melBins * melFrames * 2

    companion object {

        /**
         * Per-head width. Identical on all seven surveyed Whisper AI Hub assets, which is why
         * native keeps it as a `constexpr` instead of taking it as a sixth `nativeInit` argument.
         */
        const val HEAD_DIM: Int = 64

        /** The encoder's output length for a 30 s window. Universal, same reasoning as [HEAD_DIM]. */
        const val AUDIO_CTX: Int = 1500

        /** Frames in a 30 s window at whisper's hop. Universal, same reasoning as [HEAD_DIM]. */
        const val MEL_FRAMES: Int = 3000

        /**
         * **The `npu` tier**, whose derived census reproduces 4.0's shipped, device-confirmed
         * `kEncoderExpectation` / `kDecoderExpectation` exactly — asserted value by value in
         * `NpuModelSpecTest`, which is what makes this a second reading of the asset rather than a
         * new source of truth.
         */
        val SMALL: NpuModelSpec = NpuModelSpec(
            tierId = NpuAssetImport.TIER_ID,
            melBins = 80,
            melFrames = MEL_FRAMES,
            decLayers = 12,
            heads = 12,
            headDim = HEAD_DIM,
            audioCtx = AUDIO_CTX,
            tokens = WhisperTokens.SMALL,
        )

        /**
         * The spec for a catalog tier id, or **null when that tier does not run on the NPU**.
         *
         * The null is the routing decision `NpuBackendSelector` makes: a tier with no spec cannot
         * construct `NpuWhisperBackend` at all, because that constructor takes a spec and has no
         * default. That is the same no-default rule [NpuDecodePolicy]'s family follows, for the
         * same reason — a default here would let a future call site arm one model's assets under
         * another model's census, and the best case is a refusal at load while the worst is
         * another model's transcript.
         *
         * One row today. L4 adds `npu-turbo`.
         */
        fun forTier(tierId: String?): NpuModelSpec? = when (tierId) {
            SMALL.tierId -> SMALL
            else -> null
        }
    }
}
