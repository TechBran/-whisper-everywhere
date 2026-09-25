package com.whispereverywhere.transcription

import android.content.Context
import com.whispereverywhere.npu.LiteRtAsrNative
import com.whispereverywhere.npu.LiteRtRuntime
import com.whispereverywhere.npu.NpuApuDriverCheck
import com.whispereverywhere.npu.NpuModelSpec
import com.whispereverywhere.npu.NpuRuntimeNeeds
import com.whispereverywhere.npu.NpuSocFamily
import com.whispereverywhere.npu.NpuStage
import com.whispereverywhere.npu.Refusal
import java.io.File
import java.nio.ByteBuffer

/**
 * The MediaTek engine behind [NpuAsrEngine]: LiteRT 2.1.1 on the APU, through the Neuron driver,
 * over [LiteRtAsrNative] (P2-7 — P1b's Kotlin half; design
 * `docs/superpowers/specs/2026-09-24-mediatek-apu-tier-design.md` §2.4–2.6).
 *
 * **It owns what is LiteRT-shaped, and nothing else.** [NpuWhisperBackend]'s policy body is the
 * one the Qualcomm tier runs — the cheapest-refusal-first `load`, the arming epoch, the one loud
 * fallback, the mel, the language resolution, both blanks, the sentence slot, every diag line — and
 * this engine answers the seam's members for the other vendor:
 *
 *  - [prepare] stages the MediaTek dispatch — `libLiteRtDispatch_MediaTek.so`, an APK asset —
 *    into `filesDir/litert_dispatch/` through [LiteRtRuntime.stagedDispatchDir]: `NpuAssetStage`'s
 *    directory stage, digest-verified, the directory left holding exactly that one file and the
 *    stage's `.part` and `.staged` files kept OUTSIDE it (LiteRT scans the directory, and the
 *    adapter walk's fourth candidate is `<dir>/libneuron_adapter.so`). The directory's name has
 *    one home, [NpuApuDriverCheck.dispatchDir]: this stage, [init] and the app's driver-check
 *    probe all take it from there. A refusal is `dispatch` — the stage word the seam reserved for
 *    this engine;
 *  - [init] is `nativeInit`: the pair, the dispatch directory, the lib dir, the spec's five
 *    scalars in native's order, the row's chip stamp and Neuron major, and the two defaults P1's
 *    device gate settled (below). There is no quant stage on this vendor — the pair is float at its
 *    boundary — so every refusal of the arm, the chip-stamp mismatch included, is `init`, with
 *    native's own detail;
 *  - [encode] hands `nativeEncode` the FLOAT mel exactly as the backend's `pcmToMel` wrote it —
 *    the backend's one direct buffer, [NpuModelSpec.melFloatBytes] long — and nothing is
 *    quantised on the way (the QNN engine's `melToU16` is that engine's own);
 *  - every other member hands its call to [LiteRtAsrNative] one-to-one.
 *
 * **The two defaults, passed as literals at the one call** (`LiteRtAsrNative`'s KDoc records the
 * gate's numbers): `selfKvStrategy = 1` — one self-KV set with the step's output copied back into
 * it, chosen at P1's device gate over re-binding two sets (runs `p1b2_litertasr_kv1` / `_kv0`: step
 * mean 30.0 ms against 32.5, init 2,752 ms against 3,555, 29.7 ms/step after a re-arm against 45.7);
 * and `performanceMode = -1`, LiteRT's default — **inert on LiteRT 2.1.1 with AOT files**: the
 * dispatch never reads it and its bytecode load hard-codes `NEURON_PREFER_SUSTAINED_SPEED`, so no
 * value here is measurable on this runtime.
 *
 * **Lifecycle: [release] frees the compiled models and their buffers ONLY.** The LiteRT
 * environment (created by the process's first `nativeInit`, bound to the dispatch directory), the
 * dispatch it loaded and the Neuron adapter handles the probe kept are process state, and nothing
 * on this side of the seam can destroy them: [LiteRtAsrNative] declares no entry point that
 * would, and this engine calls nothing but `nativeRelease` to tear down (`LiteRtAsrEngineContractTest`
 * counts every `release(`). So a re-arm after `onTrimMemory` pays the two bytecode restores and
 * nothing else — no adapter walk, no environment: 3,354–3,558 ms at P1's gate
 * (`docs/measurements/2026-09-24-tab-apu-turbo-encoder.md` §6, the re-arm row; 3,410 ms on the
 * default's own run, `p1b3_litertasr_default`), against a cold process's 2,752–3,632 ms `init`
 * (the two P1 gate runs and the default's own, `p1b3_litertasr_default`, in §6's addendum) plus
 * the 169–239 ms walk when no probe ran first (§4b, §6).
 *
 * **The measured shape of one commit on the Tab S10+** (sheet §6): encode ≈ 1.72 s warm, a decode
 * step ≈ 30 ms (23–37), so a 20-token commit ≈ 2.3–2.4 s — S23 class.
 *
 * **Diag discipline, the QNN engine's:** the engine emits no line of its own. The `apu:` driver
 * line is native's, printed by the probe; `nativeInit`, the encode and the decode print theirs on
 * `WE-DIAG`; and every line the policy prints is the backend's.
 *
 * **Constructed by the selector with what the seam's members do not carry** — the census row
 * ([family]: its chip stamp and Neuron major, which [init] and [probe] pass) and
 * `nativeLibraryDir` ([libDir]: where `libLiteRt.so` is packaged, which [probe] passes; [init]
 * takes the backend's copy from [NpuEngineDirs]) — one engine per backend, like the QNN one. The
 * app's driver check constructs one too, for [probe] alone.
 *
 * **No JVM test may name this class**: it touches [LiteRtAsrNative], whose `init` block runs
 * `System.loadLibrary("litertasr")`. Its invariants are pinned as SOURCE TEXT
 * (`LiteRtAsrEngineContractTest`, `NpuStageTest`) and it is a declared input of the test task.
 */
class LiteRtAsrEngine(
    private val family: NpuSocFamily,
    private val libDir: String,
) : NpuAsrEngine {

    /**
     * THE ROW'S LITERT NEEDS — its chip stamp and its Neuron major — or null for a row of another
     * vendor, which each member that needs them refuses by name rather than casting. The selector
     * builds this engine for MediaTek rows only, and "safe by a property of a different object" is
     * the shape this stack has paid for twice; the `when` is exhaustive on purpose, so a third
     * runtime variant fails to compile HERE rather than falling through to a cast.
     */
    private val needs: NpuRuntimeNeeds.LiteRtMediatek? = when (val runtime = family.runtime) {
        is NpuRuntimeNeeds.LiteRtMediatek -> runtime
        is NpuRuntimeNeeds.Qnn -> null
    }

    /** What every member says about a row this engine cannot arm. */
    private fun notThisVendor(): String =
        "family ${family.id} is a ${family.vendor} row with no Neuron driver and no MediaTek " +
            "dispatch — the LiteRT engine cannot arm it"

    /**
     * THE DRIVER CHECK (design §2.3 items 1–2): `nativeProbe` over the staged dispatch directory,
     * this engine's lib dir and the row's Neuron major. `""` is a pass; otherwise native's own
     * refusal text — `probe: adapter-missing`, `probe: adapter-<name>`,
     * `probe: driver-major-<got>-want-<want>` and the rest of its list — never re-spelled here.
     * BLOCKING: the process's first call walks the adapter, 169–239 ms with bionic's loader lock
     * held, so never on Main. Not caught: its reader (the app's driver check) turns a throw into a
     * named refusal it does not store.
     *
     * @param dispatchOrLibDir the dispatch directory, [NpuApuDriverCheck.dispatchDir] — the one
     *        [init] hands `nativeInit` too, which refuses a walk taken against another.
     */
    override fun probe(dispatchOrLibDir: String): String {
        val needs = needs ?: return "probe: ${notThisVendor()}"
        return LiteRtAsrNative.nativeProbe(dispatchOrLibDir, libDir, needs.neuronMajor)
    }

    override fun prepare(appContext: Context, family: NpuSocFamily): Refusal? {
        // THE ROW'S LITERT NEEDS, or no stage at all — the QNN engine's own guard, mirrored: a
        // row of another vendor has no dispatch to stage, and handing it to THIS engine is a
        // wiring fault refused by name, at the stage it is, before anything is written.
        val liteRt = when (val runtime = family.runtime) {
            is NpuRuntimeNeeds.LiteRtMediatek -> runtime
            is NpuRuntimeNeeds.Qnn -> return Refusal(
                NpuStage.DISPATCH,
                "family ${family.id} is a ${family.vendor} row (QNN, HTP v${runtime.htpVersion}) " +
                    "with no MediaTek dispatch — the LiteRT engine cannot arm it"
            )
        }
        // ONE ROW, ONE READING. The row load stages for is the backend's; the row init arms
        // against (the chip stamp, the major) is the one this engine was built with. The
        // selector hands both the same object, and a mismatch is refused here rather than
        // surfacing as a stamp refusal naming the wrong chip.
        if (family !== this.family) {
            return Refusal(
                NpuStage.DISPATCH,
                "family ${family.id} was handed to a LiteRT engine built for family " +
                    "${this.family.id} — the row a session stages for and the row it arms " +
                    "against must be one row"
            )
        }
        // THE DISPATCH, staged into the ONE directory LiteRT is told to scan
        // (filesDir/litert_dispatch/, [NpuApuDriverCheck.dispatchDir] — the name's one home)
        // as the only file there, digest-verified against the pinned zip member, through
        // NpuAssetStage's directory overload: its .part and .staged marker live in the sibling
        // filesDir/litert_dispatch.staged/, never inside the scanned directory, and any stray
        // in it — a planted libneuron_adapter.so, the walk's fourth candidate, above all — is
        // removed first. The first arm writes the dispatch's bytes (LiteRtRuntime holds its size
        // and digest, once); every later one is a handful of stats against the marker.
        // Serialised twice over: the stage holds its own lock, and `load`
        // runs it under NativeComputeGate, the gate init — and so LiteRT's scan — runs under.
        // THE RETURN PATH IS DELIBERATELY UNUSED: init derives the same directory from
        // NpuEngineDirs.filesDir through the same function, so the call's value is its refusal.
        LiteRtRuntime.stagedDispatchDir(appContext) ?: return Refusal(
            NpuStage.DISPATCH,
            "${LiteRtRuntime.DISPATCH_ASSET} (family ${family.id}, ${liteRt.socStamp}) could not " +
                "be staged from the APK into filesDir/${NpuApuDriverCheck.DISPATCH_DIR_NAME}/ — " +
                "LiteRT would find no MediaTek dispatch to load"
        )
        return null
    }

    override fun init(spec: NpuModelSpec, files: NpuEngineFiles, dirs: NpuEngineDirs): Refusal? {
        val needs = needs ?: return Refusal(NpuStage.INIT, "init: ${notThisVendor()}")

        // THE DIRECTORY prepare staged, derived through its one home from the same filesDir —
        // never a second spelling. nativeInit binds the process's LiteRT environment to it on the
        // first arm and refuses any later arm naming another (and refuses one whose adapter walk
        // was taken against another), so this path and the probe's must be one path.
        val dispatchDir = NpuApuDriverCheck.dispatchDir(File(dirs.filesDir)).absolutePath

        // runCatching, not try/catch on a named type — the QNN engine's reason: liblitertasr.so
        // is absent by design on a build whose vendored LiteRT headers were missing, and the
        // FIRST touch throws UnsatisfiedLinkError while every touch after it throws
        // ExceptionInInitializerError / NoClassDefFoundError.
        //
        // THE FIVE VARYING SCALARS, in native's order, off THIS spec — the backend's required
        // one — so a spec that does not describe the files is refused at arm by native's IO
        // census, by name. `headDim`, `audioCtx` and `melFrames` stay native literals, as on
        // the QNN side. Then the row's chip stamp (each file's own LiteRtStamp must name it) and
        // its Neuron major (the driver is re-judged before any file is opened), and the two
        // defaults as LITERALS: performanceMode = -1 (LiteRT's default; inert on 2.1.1 AOT) and
        // selfKvStrategy = 1 (one self-KV set, copied back — P1's device gate chose it).
        //
        // No cleanup follows, unlike the QNN engine's arm, and none is needed: there is no
        // second stage after nativeInit on this vendor, and nativeInit releases everything it
        // built on every refusal past its own release point — so a non-empty answer leaves no
        // session this arm armed, and an empty one is a session the backend reads the epoch of
        // next.
        val initError = runCatching {
            LiteRtAsrNative.nativeInit(
                files.encoderPath,
                files.decoderPath,
                dispatchDir,
                dirs.libDir,
                spec.melBins,
                spec.decLayers,
                spec.heads,
                spec.tokens.vocab,
                spec.maxPositions,
                needs.socStamp,
                needs.neuronMajor,
                performanceMode = -1,
                selfKvStrategy = 1,
            )
        }.getOrElse { cause -> "init: ${cause.javaClass.simpleName}: ${cause.message}" }
        if (initError.isNotEmpty()) {
            // Every refusal of the arm is this stage — the chip-stamp mismatch
            // (`init: stamp: … was compiled for …`) included, with native's own detail.
            return Refusal(NpuStage.INIT, initError)
        }
        return null
    }

    override fun encode(melF32: ByteBuffer): Refusal? {
        // The float mel as pcmToMel wrote it, handed over as it is: native copies it into the
        // encoder's input buffer under lock and leaves the buffer's position alone. A buffer of
        // the wrong capacity (the QNN engine's ufixed16 block, say) is native's refusal.
        val encodeError = LiteRtAsrNative.nativeEncode(melF32)
        if (encodeError.isNotEmpty()) {
            return Refusal(NpuStage.ENCODE, encodeError)
        }
        return null
    }

    override fun decodeSegment(
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
    ): Int = LiteRtAsrNative.nativeDecodeSegment(
        prompt,
        suppress,
        beginSuppress,
        maxTokens,
        out,
        temperatures,
        entropyThold,
        logprobThold,
        noSpeechThold,
        noSpeechToken,
        cycleMaxDistinct,
        stats,
    )

    override fun detectLanguage(): Int = LiteRtAsrNative.nativeDetectLanguage()

    override fun epoch(): Long = LiteRtAsrNative.nativeEpoch()

    /**
     * The compiled models and their buffers, IF [epoch] names the live session — and nothing
     * else: the environment, the dispatch and the adapter stay for the next arm (the class KDoc).
     * This engine keeps no per-arm state of its own, so there is nothing to drop on this side.
     */
    override fun release(epoch: Long) = LiteRtAsrNative.nativeRelease(epoch)

    override fun lastError(): String = LiteRtAsrNative.nativeLastError()

    override fun setDiag(on: Boolean) = LiteRtAsrNative.nativeSetDiag(on)
}
