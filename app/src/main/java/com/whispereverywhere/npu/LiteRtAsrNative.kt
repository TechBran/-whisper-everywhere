package com.whispereverywhere.npu

/**
 * JNI seam onto LiteRT 2.1.1's C API for the MediaTek APU — `liblitertasr.so`, the `npu-turbo`
 * tier's second engine (design `docs/superpowers/specs/2026-09-24-mediatek-apu-tier-design.md`
 * §2.3, §2.5, §2.6).
 *
 * **The same shape as [QnnAsrNative], on purpose.** The same entry points in the same roles, the
 * same argument lists where the two engines agree, the same return conventions, the same
 * process-global session behind one mutex, the same arming epoch — so the policy body in
 * `NpuWhisperBackend` reads both engines one way once P1a's seam lands. What differs is stated at
 * each member and summarised here:
 *
 *  - **The mel is FLOAT.** [nativeEncode] takes the float32 spectrogram whisper.cpp's `pcmToMel`
 *    writes (`melBins × 3000 × 4` bytes, direct, native order). The pair is f32 at its boundary and
 *    computes in fp16 inside the APU; there is no `nativeInputQuant` and no `NpuQuantize` step.
 *  - **The dispatch directory.** [nativeProbe] and [nativeInit] take `dispatchDir`: the directory
 *    holding exactly `libLiteRtDispatch_MediaTek.so` (`filesDir/litert_dispatch/`, staged by P2's
 *    prepare). The LiteRT environment is bound to it for the life of the process.
 *  - **The driver and the chip.** [nativeProbe] takes `wantMajor` (the family's
 *    `neuronMajor`) and [nativeInit] takes `socStamp` (the family's `socStamp`, `"mt6989"`) and
 *    `wantMajor` again: the Neuron driver's major is judged before any file is opened, and each model
 *    file's own `LiteRtStamp` must name this chip before LiteRT opens it.
 *  - **The performance mode — INERT on LiteRT 2.1.1.** [nativeInit] takes `performanceMode`: `-1`
 *    for LiteRT's default, or a `LiteRtMediatekNeuronAdapterPerformanceMode` value (0
 *    `PreferLowPower`, 1 `PreferFastSingleAnswer`, 2 `PreferSustainedSpeed`, 3 `PreferTurboBoost`).
 *    It is validated and handed to LiteRT's MediaTek options, and with AOT files the v2.1.1 dispatch
 *    never reads it: its bytecode load hard-codes `NEURON_PRIORITY_HIGH`,
 *    `NEURON_PREFER_SUSTAINED_SPEED` and an execution boost hint of 100, so every run is in that mode
 *    whatever is passed, and "PreferSustainedSpeed vs default" cannot be measured on this runtime.
 *    Kept so P2 can pin a value for a runtime that reads it; the init line says `(inert on LiteRT
 *    2.1.1 AOT)` beside it, and no encode or decode line reports it.
 *  - **The self-KV strategy.** [nativeInit] takes `selfKvStrategy`, how the decode loop advances the
 *    cache after each step: `0` swaps two buffer sets by re-binding (no byte moves, but the dispatch
 *    re-registers every re-bound buffer on the next run — 16 per step on turbo), `1` keeps one input
 *    set and copies the step's output back into it (~8 MB per step, no binding ever changes). Both
 *    exist so P1's device gate can time them; the faster one becomes the default.
 *  - **Two accelerator sets.** The encoder is created on the NPU alone; the decoder on NPU + CPU,
 *    because its two embedding lookups stay on the CPU and LiteRT 2.1.1 refuses a partly delegated
 *    model without CPU in the set. (Kotlin's `CompiledModel` adds CPU to a lone NPU silently, which
 *    is how every tablet run so far had it for both.) Init then times the decoder's first step.
 *  - **No quantisation anywhere.** The decode loop is its own float loop: the mask's `-infinity` is
 *    the real one, the logits' scale is 1.0 and never 0 (so p(nospeech), avg_logprob and the ladder
 *    are always live), and a non-finite logit fails the step (`-4`).
 *
 * ERROR CONVENTION: every `String` return is `""` on success or `"stage: detail"` on failure —
 * never null, never an exception — and the same text stays readable through [nativeLastError].
 *
 * THREADING: one process-global session behind one mutex. Safe from any thread; none of it may run
 * on Main. [nativeProbe]'s FIRST call walks the adapter — 169–239 ms at P1's device gate — with
 * bionic's loader lock held, and it holds this seam's mutex for the whole of it. (P0's "5 s adapter
 * wait" was LiteRT's magic-number read through `libneuron_sys_util.mtk.so`, which the product does
 * not declare; the gate saw no wait at all — design §2.3.)
 *
 * LIFECYCLE: [nativeInit] is idempotent (a live session is released first); every successful one
 * issues an arming epoch ([nativeEpoch]) and [nativeRelease] ignores any epoch that is not the live
 * one, exactly as [QnnAsrNative.nativeRelease]. **Unlike QNN, release frees less than init
 * created:** the Neuron adapter handles, `libLiteRt.so` and the LiteRT environment are process state
 * and are never closed or destroyed, so a re-arm after a trim pays the bytecode restores and never
 * re-loads the adapter.
 *
 * LOADING: the `init` block throws `UnsatisfiedLinkError` when `liblitertasr.so` is absent (the
 * CMake target is skipped when the vendored LiteRT headers are missing). `libLiteRt.so` itself is
 * dlopened by the native side, from the `libDir` passed in or by SONAME, so its absence is a
 * `"probe: runtime: …"` string rather than a link error. As with [QnnAsrNative], no JVM unit test
 * may name this object — `LiteRtNativeContractTest` asserts over its SOURCE TEXT.
 */
object LiteRtAsrNative {
    init {
        System.loadLibrary("litertasr")
    }

    /**
     * THE DRIVER CHECK (design §2.3), and the tier's warm-up: the owner's "version checker … to make
     * sure we're hitting the right chip for the driver".
     *
     * Walks LiteRT v2.1.1's Neuron adapter candidates in LiteRT's own order —
     * `libneuronusdk_adapter.mtk.so`, `libneuronusdk_adapter.9.mtk.so`, `libneuron_adapter_mgvi.so`,
     * `<dispatchDir>/libneuron_adapter.so` — with `RTLD_NOW | RTLD_NODELETE` (NOT LiteRT's flags,
     * which are `RTLD_LAZY | RTLD_LOCAL`; on bionic the two admit exactly the same candidates, since
     * `RTLD_LAZY` is unsupported there and `RTLD_LOCAL` is the default), keeps every handle that
     * loads, and takes the LAST one as the winner, because that is the one LiteRT's loop uses. Reads
     * its `Neuron_getVersion` (and the device names for the diag line), judges it, and on a pass
     * loads `libLiteRt.so` from [libDir] and resolves every entry point the engine calls.
     *
     * The walk runs ONCE per process — the first call pays it, 169–239 ms on the Tab S10+ — and later
     * calls re-judge the cached walk against [wantMajor]. No environment is created and no model is
     * opened.
     *
     * The `apu:` line is logged on `WE-DIAG` on every call:
     * `apu: driver=libneuronusdk_adapter.mtk.so 8.2.26 want=8 devices=… device=… walk=…ms pass`.
     *
     * @param dispatchDir the staged dispatch directory; its `libneuron_adapter.so` is the fourth
     *        candidate. Must be the same directory [nativeInit] is later given.
     * @param libDir `applicationInfo.nativeLibraryDir`, where `libLiteRt.so` is packaged (loaded by
     *        SONAME when the packaging leaves no real file there).
     * @param wantMajor the family's `neuronMajor` (8 for `mt6989`).
     * @return `""` on a pass; otherwise `"probe: <reason>"` where reason is one of
     *         `adapter-missing`, `adapter-<name>` (a winner other than the adapter the tier was
     *         measured on), `driver-version-unreadable`, `driver-major-<got>-want-<want>`, or
     *         `runtime: <detail>` (libLiteRt.so or one of its symbols unavailable).
     */
    external fun nativeProbe(dispatchDir: String, libDir: String, wantMajor: Int): String

    /**
     * Arms the session, in this order, each stage refusing with `"init: <stage>: <detail>"`:
     *
     *  1. the five spec scalars, [performanceMode], [selfKvStrategy], [socStamp] and [wantMajor] are
     *     validated — BEFORE any live session is released, so a mistyped argument costs an error
     *     string, not a working tier (QnnAsrNative's rule);
     *  2. the driver verdict ([nativeProbe]'s, walking the adapter now if no probe has run), then
     *     `libLiteRt.so` and the LiteRT environment — created on the first init of the process with
     *     `kLiteRtEnvOptionTagDispatchLibraryDir = dispatchDir`, and never destroyed; a later init
     *     naming a different [dispatchDir] is refused. These are the PROCESS's state and need no file,
     *     so they too are judged before a live session is released: a wrong directory or a refused
     *     driver costs an error string, never the working session;
     *  3. (a live session is released here, and everything below refuses by releasing what it built)
     *  4. **the chip**: each file's `LiteRtStamp` metadata (vendor, then SoC, two NUL-padded
     *     125-byte fields) is read from the flatbuffer by native code before LiteRT opens the file,
     *     and must be `MediaTek` / [socStamp];
     *  5. both models, their `encode` / `decode` signatures and the IO census: every input by name
     *     and every output at its position (its semantic name or `output_<i>`), each with the exact
     *     element type and dims [melBins] … [maxPositions] imply — the encoder's eight cross-KV
     *     outputs reach the decoder's inputs by export order alone, so that order is asserted here;
     *  6. the options — the encoder on the NPU ALONE (it compiled to one `DISPATCH_OP`, so a refusal
     *     to delegate is an error), the decoder on NPU + CPU (its two embedding lookups and their
     *     bounds guards stay on the CPU; LiteRT 2.1.1 refuses a partly delegated model without CPU in
     *     the set) — plus [performanceMode], inert; and both compiled models — the bytecode restores,
     *     ~1.3 s and ~0.9 s on the Tab S10+;
     *  7. every buffer, typed and sized by the compiled models' own requirements but made WITHOUT
     *     their strides — the v2.1.1 dispatch refuses a strided buffer, and its requirements always
     *     carry strides. The 27 buffers a `DISPATCH_OP` reads or writes (the mel, the eight cross-KV,
     *     the sixteen self-KV, `attention_mask`, the logits) are AHardwareBuffer, else DMA-BUF — the
     *     only memory the dispatch registers; the two no `DISPATCH_OP` sees, **`input_ids` and
     *     `position_ids`** (read only by the CPU-side lookups), are host memory. The eight cross-KV
     *     buffers come from the JOIN of the encoder's output and the decoder's input requirements,
     *     bound to both; two self-KV sets from the join of each `_in` / `_out` pair. A failed join or
     *     a stride mismatch between the two sides refuses the session;
     *  8. **the APU check**: the decoder's first step, at position 0 over zeroed caches, timed — a
     *     step over 250 ms (an APU step is 19–24 ms) refuses with `"init: decoder ran without the APU
     *     (step N ms)"`, and any failure of the decoder's first run surfaces here, at arm time.
     *
     * The scalars are [QnnAsrNative.nativeInit]'s five, with the same bounds; `headDim = 64`,
     * `audioCtx = 1500` and `melFrames = 3000` stay native literals, pinned against [NpuModelSpec].
     * Pass them from one [NpuModelSpec] (`NpuModelSpec.TURBO`).
     *
     * @param encoderPath the AOT-compiled encoder (`…_MediaTek_MT6989_apply_plugin.tflite`, under
     *        the catalog name `turbo_encoder_qairt_context.bin` once P2 installs it).
     * @param decoderPath the AOT-compiled decoder.
     * @param dispatchDir as for [nativeProbe].
     * @param libDir as for [nativeProbe].
     * @param socStamp the family's `socStamp` — `"mt6989"`.
     * @param wantMajor the family's `neuronMajor` — 8.
     * @param performanceMode `-1` (LiteRT's default) or `0..3`, see the object KDoc. **INERT on LiteRT
     *        2.1.1 with AOT files**: validated and passed through, never read by the dispatch, whose
     *        bytecode load hard-codes `NEURON_PREFER_SUSTAINED_SPEED`.
     * @param selfKvStrategy `0` (two self-KV sets re-bound per step) or `1` (one set, the step's
     *        output copied back into it), see the object KDoc.
     * @return `""` on success, else `"init: <stage>: <detail>"`.
     */
    external fun nativeInit(
        encoderPath: String,
        decoderPath: String,
        dispatchDir: String,
        libDir: String,
        melBins: Int,
        decLayers: Int,
        heads: Int,
        vocab: Int,
        maxPositions: Int,
        socStamp: String,
        wantMajor: Int,
        performanceMode: Int,
        selfKvStrategy: Int,
    ): String

    /**
     * Runs the encoder over one 30 s window.
     *
     * @param melF32 the **float** mel — `melBins × 3000` float32 values (1,536,000 bytes for turbo),
     *        a DIRECT buffer in native order, exactly what whisper.cpp's `pcmToMel` produces. Not the
     *        quantised block [QnnAsrNative.nativeEncode] takes; that one is refused by capacity.
     * @return `""` on success, else `"encode: <detail>"`.
     *
     * The mel is copied into the encoder's input buffer under lock and the encoder is run; its eight
     * cross-KV outputs are already the decoder's inputs, nothing is copied between the passes. Sets
     * the encode-validity flag [nativeDecodeSegment] and [nativeDetectLanguage] require; clears it on
     * entry, so a failed run leaves the tier refusing to decode rather than decoding half a window.
     * ~1.7-1.8 s on the Tab S10+ APU. Holds the session mutex for the whole run.
     */
    external fun nativeEncode(melF32: java.nio.ByteBuffer): String

    /**
     * Decodes one segment — the whole loop native, in one call, for [QnnAsrNative.nativeDecodeSegment]'s
     * reason (the suppression mask is pre-argmax; a per-step Kotlin loop would only ever see an
     * argmax with no runner-up).
     *
     * **The same contract, argument for argument and slot for slot, as
     * [QnnAsrNative.nativeDecodeSegment]** — read its KDoc for each parameter — implemented as its own
     * float loop: the prompt fed through the step path, the always-on and begin masks written as
     * `-infinity` before the argmax, timestamps emitted (and kept out of the text-only entropy window
     * and out of avg_logprob), the temperature ladder re-decoding against the same encode, positions
     * `0..maxPositions-2` executing (the 199-slot window), the self-KV cache advanced after every step
     * by [nativeInit]'s `selfKvStrategy` — the two sets swapping roles by re-binding (`0`), or set 0
     * staying the input and the step's output copied back into it (`1`) — both sets zeroed at every
     * rung's start.
     *
     * What differs from the QNN engine is below the contract: p(nospeech) is always computed (scale
     * 1.0, never "unreadable"), so `NO_SPEECH_PROB` is never `-1` on this engine; every step's raw
     * logits are checked for NaN/infinity first; and the three stats the decode line prints are held
     * to [NpuDecodeStats]' contract — a finite number, or NaN exactly where it says "not measured"
     * (`AVG_LOGPROB` when nothing was scored, `ENTROPY` when the text-only window was never reached,
     * the same NaNs `qnn_asr.cpp` writes). The line prints such a NaN with its reason, e.g.
     * `ent=nan(unmeasured: 26 text ids, the window needs 33)`.
     *
     * @return the number of ids written (`>= 0`; `0` = EOT came first, i.e. silence), or `< 0` with
     *         the text in [nativeLastError]: `-1` arguments or state, `-2` a run or lock failure,
     *         `-3` every logit at the floor, `-4` non-finite logits or a non-finite stat outside the
     *         contract (the segment falls back loudly; the session stays armed).
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
        cycleMaxDistinct: Int,
        stats: FloatArray,
    ): Int

    /**
     * One decode step at position 0 with `<|startoftranscript|>`, the argmax restricted to this
     * session's language band (derived from `vocab`), through `band_scan.h`'s float scan; then both
     * self-KV sets are zeroed. [QnnAsrNative.nativeDetectLanguage]'s contract and refusals, and the
     * same always-on `detect:` line prefix; the margin on it is already in log-odds.
     *
     * @return a `<|xx|>` token id inside the band, or `< 0` with the reason in [nativeLastError].
     */
    external fun nativeDetectLanguage(): Int

    /**
     * Turns the native `npu-debug:` instrumentation on or off; off until this says otherwise. The
     * lines it gates are content-safe by construction (every id below EOT prints as `text-token`),
     * and on this engine they include a `steptime` line for the device gate — for the first four
     * steps of each segment and its last, never more (qnn_asr.cpp's four-lines-per-segment rule).
     * Call it with `BuildConfig.DEBUG`.
     */
    external fun nativeSetDiag(enabled: Boolean)

    /** The last `"stage: detail"` any entry point recorded, or `""`; cleared by every success. */
    external fun nativeLastError(): String

    /** The live session's arming epoch, or `0L` when there is none. A reader and nothing else. */
    external fun nativeEpoch(): Long

    /**
     * Releases the session — every tensor buffer, the requirement joins, both compiled models, their
     * options and both models — **if [epoch] names the live session**; `0L` and stale epochs are
     * logged on `WE-DIAG` and ignored ([QnnAsrNative.nativeRelease]'s 4.1 L1 guard, for the same
     * interleaving). The environment, `libLiteRt.so` and the Neuron adapter are NOT released: they
     * are process state (design §2.6).
     *
     * @param epoch the value [nativeEpoch] answered when this caller armed; never a fresh read.
     */
    external fun nativeRelease(epoch: Long)
}
