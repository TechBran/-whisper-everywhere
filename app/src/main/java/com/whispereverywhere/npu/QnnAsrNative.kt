package com.whispereverywhere.npu

/**
 * JNI seam onto Qualcomm's QAIRT (QNN) C API — `libqnnasr.so`, the 4.0 NPU tier.
 *
 * The encoder and decoder are driven through our own native library talking directly to
 * libQnnHtp.so / libQnnSystem.so, with no ONNX Runtime anywhere (ORT 1.29's QNN EP declined the AI
 * Hub EPContext node in every wrapper variant and both binding routes, so the layer was removed
 * rather than worked around). This mirrors how whisper.cpp is already driven at
 * [com.whispereverywhere.whisper.WhisperNative] — an owned JNI boundary with a narrow, explicit
 * surface.
 *
 * ERROR CONVENTION: every `String` return is `""` on success, or `"stage: detail"` on failure —
 * never null, never an exception. The owner has no adb, so a failure has to arrive as text that
 * names the stage it happened at. The same text stays readable afterwards via [nativeLastError].
 *
 * THREADING: the native side holds one process-global session behind one mutex. Every entry point
 * here is safe to call from any thread and none of them may be called from Main — [nativeInit]
 * reads 342 MB from disk and deserialises it.
 *
 * LIFECYCLE: [nativeInit] is idempotent (an existing session is released first), and
 * [nativeRelease] must be called before the CPU `multi` tier re-arms. The NPU path needs ~376 MiB
 * and the two are never co-resident, in either direction. Every successful [nativeInit] issues an
 * **arming epoch**, readable through [nativeEpoch]; [nativeRelease] takes that epoch and ignores
 * any release that does not name the live session, which is what makes two npu-class tiers safe to
 * switch between (4.1 L1 / final review F4).
 *
 * LOADING: the `init` block below throws `UnsatisfiedLinkError` when `libqnnasr.so` is absent from
 * the APK, which happens by design when the Qualcomm headers could not be fetched at build time
 * (see the guard in `app/src/main/cpp/CMakeLists.txt`). Callers reach this object only through the
 * NPU gate, which treats a load failure as "tier unavailable" — the CPU and GPU tiers never touch
 * it. That is also why no JVM unit test may reference this object at all: there is no
 * `libqnnasr.so` on the unit-test classpath, so merely naming `QnnAsrNative` in a test kills it.
 * `NpuNativeContractTest` therefore asserts over SOURCE TEXT, and the real behaviour is verified
 * on device at Q10a.
 */
object QnnAsrNative {
    init {
        System.loadLibrary("qnnasr")
    }

    /**
     * The gating probe: dlopens libQnnSystem.so and libQnnHtp.so from [libDir] and confirms each
     * exposes at least one provider through `QnnInterface_getProviders` /
     * `QnnSystemInterface_getProviders`.
     *
     * Cheap and side-effect-free in the sense that matters: it creates NO backend, NO device and NO
     * context, allocates nothing device-side, and votes no power configuration. It is the check the
     * backend selector runs before offering the tier at all, so it also runs on devices where the
     * answer is no.
     *
     * The two libraries do stay mapped afterwards — dlopen is refcounted and [nativeInit] reuses
     * the same handles, so probing then arming costs one real load rather than two.
     *
     * @param libDir the app's native library directory (`applicationInfo.nativeLibraryDir`), which
     *        is where the bundled QNN .so set lives.
     * @return `""` when the HTP stack is usable on this device, else `"probe: stage: detail"`.
     */
    external fun nativeProbe(libDir: String): String

    /**
     * Loads both precompiled QAIRT context binaries — 127 MB encoder, 215 MB decoder — creates the
     * backend, device and contexts, retrieves both graphs, and logs every graph's IO by name, dtype
     * and shape along with the binary-info, graph-info and tensor struct versions it read them
     * through.
     *
     * Costs ~342 MiB of resident memory and (spike-measured, encoder) ~525 ms of cold load; the
     * decoder's cost is first measured on device at Q10a. Never call this from Main.
     *
     * It also does everything else the session needs, exactly once:
     *  - runs the **cross-KV alias guard** (C7) before anything binds — the 24 `k/v_cache_cross_N`
     *    tensors must be identical in dtype, rank, every dimension, scale and offset between the
     *    encoder's outputs and the decoder's inputs, because the decoder is fed those buffers in
     *    place. A shifted scale after an asset re-export is not a crash; it is a fluent, confident,
     *    wrong transcript, so it fails here with `init: alias: <tensor> scale <a> != <b>`;
     *  - reads `input_features`' quantisation parameters from the tensor's own metadata (see
     *    [nativeInputQuant]) and refuses a session it cannot feed correctly;
     *  - allocates and binds the encoder's client buffers, including the 24 cross-KV outputs that
     *    Q4's decoder will read in place;
     *  - **arms the sustained power vote once**, and logs its outcome on a `vote:` line whatever
     *    that outcome is. A vote that failed silently is indistinguishable from slow silicon —
     *    the spike measured 1007 ms unvoted against 405 ms voted and spent a device round trip
     *    finding out which it was looking at. A failed vote does NOT fail this call.
     *
     * Idempotent: an already-initialised session is released first, so a model swap or a restarted
     * session cannot leak a context.
     *
     * ### The five scalars, and the three that are not here (4.1 L2)
     *
     * Native derives the graph census — both `GraphExpectation`s, the cross-KV layer count and the
     * language band's bounds — from [melBins], [decLayers], [heads], [vocab] and [maxPositions],
     * and then refuses any asset whose own enumeration disagrees with it. Those five are exactly
     * the factors that VARY between published Whisper AI Hub assets; `headDim = 64`,
     * `audioCtx = 1500` and `melFrames = 3000` are identical across all seven surveyed and stay
     * native `constexpr`s, because an argument carrying a number that cannot vary is a number a
     * caller can get wrong. [NpuModelSpec] carries all eight so the Kotlin census is complete, and
     * `NpuNativeContractTest` pins the three native literals against its fields.
     *
     * **They are validated before anything is opened or torn down.** An implausible set returns
     * `"init: spec: …"` without releasing the session the caller already had and without reaching
     * an allocation. Pass them from a single [NpuModelSpec]; assembling them at the call site is
     * how one of the five ends up describing a different model from the other four.
     *
     * @param melBins 80 or 128. Nothing else is accepted.
     * @param decLayers the decoder's layer count — 12 for `whisper-small`, 4 for turbo. `1..64`.
     * @param heads the attention-head count. `1..64`.
     * @param vocab the `logits` dimension. `1..65535`, because it bounds a `uint16` argmax.
     * @param maxPositions `attention_mask`'s width. `2..1024`.
     * @return `""` on success, else `"init: <stage>: <detail>"`.
     */
    external fun nativeInit(
        encoderPath: String,
        decoderPath: String,
        libDir: String,
        melBins: Int,
        decLayers: Int,
        heads: Int,
        vocab: Int,
        maxPositions: Int,
    ): String

    /**
     * The encoder's input quantisation as `[scale, zeroPoint]`, or an **empty array** on failure
     * (with the reason in [nativeLastError]). Valid only while a session is initialised.
     *
     * Feed both straight to [NpuQuantize.melToU16]. They come from `input_features`' own
     * `Qnn_QuantizeParams_t`, read once at [nativeInit].
     *
     * **This exists so that "from metadata, never hardcoded" is enforceable rather than
     * aspirational.** The two numbers are also written down in the plan's baked-facts block, and
     * without a declared transport for them the shortest path from that block to working code is
     * to paste the literals into the quantiser — where they would survive an asset re-export
     * unchanged and silently scale every spectrogram wrongly. There is no accuracy check anywhere
     * downstream that would notice; the encoder simply transcribes the wrong input fluently.
     *
     * `zeroPoint` arrives as a `Float` because integers below 2^24 are exact in one; take it with
     * `.toInt()`. Note the sign convention: QNN's metadata stores `offset = -zeroPoint`, and the
     * conversion (plus a range check that catches the convention flipping under us) happens native
     * side, so what arrives here is already the zero point [NpuQuantize] wants.
     */
    external fun nativeInputQuant(): FloatArray

    /**
     * Runs the encoder over one 30 s segment.
     *
     * @param quantisedMel the **already-quantised** `ufixed16` block — 480,000 bytes, direct,
     *        native order — produced by [NpuQuantize.melToU16] into
     *        [NpuQuantize.newInputFeaturesBuffer]. **Not** the 960,000-byte float mel; that one is
     *        refused by capacity rather than half-read.
     * @return `""` on success, else `"encode: <detail>"`.
     *
     * On success this also arms the decode side: the encode-validity flag [nativeDecodeSegment] and
     * [nativeDetectLanguage] check is set here and **only** here. It is cleared on entry, so a
     * failed encode leaves the tier refusing to decode rather than decoding half a segment.
     *
     * On success the encoder's 24 cross-KV output buffers hold this segment's state **and are
     * already the decoder's bound cross-KV inputs** — nothing is copied between the two passes,
     * which is what keeps a ~100-token decode from moving 2.6 GB. Call `nativeDecodeSegment` (Q4)
     * next; the cross-KV survives until the next `nativeEncode` overwrites it.
     *
     * ~405 ms on a voted Snapdragon 8 Gen 3. Holds the session mutex for the whole execute, so it
     * must never run on Main and never concurrently with a decode.
     */
    external fun nativeEncode(quantisedMel: java.nio.ByteBuffer): String

    /**
     * Decodes one segment: **the entire greedy loop runs native**, in this one call.
     *
     * **That boundary placement is a correctness requirement, not a performance one.** Whisper's
     * suppression is by construction a *pre-argmax* mask. A Kotlin loop calling a per-step native
     * `decodeStep` would only ever receive the argmax, and an argmax has no runner-up: on finding
     * that the winner is suppressed, the caller can do nothing useful, because re-running the step
     * is deterministic and returns the same token. Such a loop either emits the suppressed token or
     * hangs. (The JNI crossing was never the issue — a two-int transition is ~100 ns against a
     * ~4.5 ms `graphExecute`.)
     *
     * `position` is the single counter and the prompt consumes it: positions `0..promptLen-1` feed
     * the prompt through the same execute path, and the argmax at `position == promptLen - 1` is
     * the **first generated token**. Positions `0..198` execute — an exact fit for the 199-deep
     * self-KV — and 199 is the termination threshold, not an executing position. The loop ends on
     * [WhisperTokens.EOT], on [maxTokens], or at the position cap, whichever comes first.
     *
     * **[nativeEncode] must have succeeded first, and this is enforced, not documented.** The
     * decoder reads that segment's cross-KV **in place**, so a decode without a preceding encode
     * would transcribe whatever the last encode left there — fluently, with no other symptom.
     * Native holds an encode-validity flag: set by a successful [nativeEncode], cleared on entry to
     * every [nativeEncode] (a failed execute may leave the cross-KV half written), cleared by
     * [nativeRelease] and by a fresh [nativeInit]. Without it this returns `-1` and
     * `"decode: no encoded segment …"`.
     *
     * **A decode does NOT consume that flag.** One encode may serve a [nativeDetectLanguage] pass
     * and then one or more [nativeDecodeSegment] calls — which is the tier's actual flow (encode →
     * detect → decode against the same encode), and is also what makes a retry with a different
     * prompt possible without re-encoding. The flag says "the cross-KV holds a real segment", not
     * "a decode is still owed".
     *
     * @param prompt `NpuDecodePolicy.promptTokens(spec.tokens, lang)`.
     * @param suppress `NpuDecodePolicy.suppressList(spec.tokens)` — applied to the logits at **every** generated
     *        step, before the argmax scan.
     * @param beginSuppress `NpuDecodePolicy.beginSuppressList(spec.tokens)` — applied at the first generated
     *        step **only**.
     * @param maxTokens `NpuDecodePolicy.maxTokensFor(spec.tokens, prompt.size)`.
     * @param out receives the generated ids; must have `size >= maxTokens`. Bounds-checked native
     *        side rather than trusted, and untouched beyond the returned count.
     * @param temperatures `NpuDecodePolicy.TEMPERATURES` — the fallback ladder; `[0]` must be 0.
     * @param entropyThold `NpuDecodePolicy.ENTROPY_THOLD`: a rung whose last 32 ids have less
     *        histogram entropy than this is a repetition loop and is abandoned at that step.
     * @param logprobThold `NpuDecodePolicy.LOGPROB_THOLD`: a rung whose mean log-prob is below
     *        this (and whose no-speech probability is below [noSpeechThold]) is re-run hotter.
     * @param noSpeechThold `NpuDecodePolicy.NO_SPEECH_THOLD`.
     * @param noSpeechToken `spec.tokens.noSpeech` — this family's `<|nospeech|>` id; native reads
     *        its raw probability at the SOT step and never emits it.
     * @param stats OUT, `NpuDecodeStats.newArray()`; fully written on every `>= 0` return, by the
     *        [NpuDecodeStats] slots. `-1` in `NO_SPEECH_PROB` means the logits' scale was unreadable
     *        and no probability gate ran (the entropy guard still did). `AVG_LOGPROB` is NaN when
     *        the scale was unreadable or nothing was scored (EOT counts as scored, as
     *        `whisper_sequence_score` counts it); after a `cut` it is the failing rung's PRE-cut
     *        average, over every id that rung emitted before the prefix was kept. `ENTROPY` is NaN
     *        whenever the 32-id window was never reached on the returned rung.
     * @return the number of ids written (`>= 0`), or `< 0` on failure with the text in
     *         [nativeLastError]. `0` is a legitimate answer: it means EOT came first, i.e. silence.
     *
     * Holds the session mutex for the whole loop — never on Main, never concurrent with an encode.
     */
    external fun nativeDecodeSegment(
        prompt: IntArray,
        suppress: IntArray,
        beginSuppress: IntArray,
        maxTokens: Int,
        out: IntArray,
        temperatures: FloatArray,
        entropyThold: Float,
        logprobThold: Float,
        noSpeechThold: Float,
        noSpeechToken: Int,
        stats: FloatArray,
    ): Int

    /**
     * One decode step at `position = 0` with `input_ids = <|startoftranscript|>`, with the argmax
     * **restricted to THIS SESSION'S language block** — `50259..50357` for the 99-language
     * `npu` tier, one wider for a 100-language one, and native derives the bound from the
     * `vocab` scalar [nativeInit] received rather than from a constant (4.1 L2). Then both
     * self-KV sets are zeroed so a
     * following [nativeDecodeSegment] starts from an empty cache.
     *
     * The restriction lives native for the same reason the suppression mask does: an unrestricted
     * argmax handed back to Kotlin has already lost the information needed to decide whether the
     * winner was a language at all. Feed the result to [WhisperTokens.codeForToken], which returns
     * `null` for anything outside that block.
     *
     * **Requires a successful preceding [nativeEncode], enforced the same way** — this reads that
     * segment's cross-KV in place and would otherwise detect the *previous* segment's language,
     * which then becomes this segment's prompt. Returns `-1` and `"detect: no encoded segment …"`
     * without one. It does **not** consume the encode: call [nativeDecodeSegment] next against the
     * same encode, which is exactly what this function exists for.
     *
     * @return a `<|xx|>` token id inside this session's own language band, or `< 0` with the
     *         reason in [nativeLastError]. Feed it to [WhisperTokenFamily.codeForToken] for the
     *         SAME family the session was armed with: the band is per-family, and 50358 is a
     *         language in one vocabulary and `<|translate|>` in another.
     */
    external fun nativeDetectLanguage(): Int

    /**
     * Turns the native `npu-debug:` instrumentation on or off. **Off until this says otherwise**, so
     * a release build emits nothing and pays for nothing.
     *
     * The lines it gates (Q10a-D1) go to the house `WE-DIAG` tag and describe the decode loop's own
     * behaviour: the prompt as native actually received it, the raw pre-mask logits rails and argmax
     * at each prompt step, the self-KV set bound as input at each of those steps, the terminator,
     * and the language-detect band's winner, runner-up and margin.
     *
     * **They are content-safe by construction, not by care.** Native renders every token id through
     * one helper that prints ids `>= WhisperTokens.EOT` verbatim — prompt scaffolding, language
     * tags, control tokens, timestamps — and collapses everything below it to the constant string
     * `text-token`. No id that could be a word ever reaches a log line, including in the prompt echo
     * (which is four specials today, and would carry previous-segment text if this tier ever adopted
     * whisper's `<|startofprev|>` form).
     *
     * Call it with `BuildConfig.DEBUG`. Kotlin owns the decision because that is where the flag
     * exists; a build-type `#ifdef` native side would be a second definition of "debug build" that
     * could disagree with the app's.
     */
    external fun nativeSetDiag(enabled: Boolean)

    /**
     * The last `"stage: detail"` recorded by any entry point, or `""` if none. Exists because
     * [nativeDecodeSegment] and [nativeDetectLanguage] report failure as a negative number and need
     * somewhere to put the words. Cleared by every entry point that succeeds, so it is never a
     * stale message from an earlier stage.
     */
    external fun nativeLastError(): String

    /**
     * The live session's **arming epoch**, or `0L` when there is none (4.1 L1).
     *
     * Every successful [nativeInit] issues the next value of a process-monotonic counter and hands
     * it out here; [nativeRelease] refuses any epoch that is not this one. It is the only way
     * Kotlin can learn the name of the session it armed — [nativeInit] keeps returning `""` /
     * `"stage: detail"` unchanged, because widening a String whose whole job is to name a failed
     * stage so that it can also carry a number is how the failure text stops being read.
     *
     * Read under the session mutex, so it is safe from any thread. One JNI crossing is ~100 ns
     * against a ~405 ms encode, which is why `NpuWhisperBackend` can afford to check it once per
     * segment — and why a stale instance can never encode into, or decode out of, a session that
     * belongs to a different tier.
     */
    external fun nativeEpoch(): Long

    /**
     * Releases the sustained power vote, the client buffers, the contexts, the device and the
     * backend, and frees the system context that owns the tensor metadata — in that order —
     * **if [epoch] names the session that is actually live**. Safe to call twice and safe to call
     * on a partially-initialised session.
     *
     * The vote goes first because it is a request held against the backend that is about to be
     * freed. Everything the session holds is session-scoped, so this is also the only place any of
     * it is released: there is no per-segment allocation to reclaim.
     *
     * ### Why it takes an epoch (final review F4/I1)
     *
     * The QNN session is a **process-global** and [nativeInit] releases any existing one, while
     * `LocalWhisperEngine.shutdown()` *queues* the stale backend's release onto that engine's own
     * executor — a different one from the executor the replacement loads on. An `npu → npu-class`
     * rebuild therefore has an interleaving in which this call, made late by a backend whose
     * session is long gone, destroys the session its **successor** just built, leaving that
     * successor armed with nothing behind it.
     *
     * Source order cannot repair that: the two effects are not ordered by the two statements that
     * cause them. Identity can. Pass the value [nativeEpoch] answered when this instance armed; a
     * mismatch — and `0L`, which is "no session" and is refused outright — is logged on `WE-DIAG`
     * and **ignored**.
     *
     * The QNN libraries themselves stay mapped on purpose: dlclose on a backend holding process-wide
     * FastRPC state is not something the API promises is safe, and re-arming the tier is free.
     *
     * @param epoch the epoch this caller was armed with, from [nativeEpoch]. Never a fresh read —
     *        a fresh read names whatever is live *now*, which is the unguarded release with an
     *        argument added to it.
     */
    external fun nativeRelease(epoch: Long)
}
