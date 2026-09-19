package com.whispereverywhere.transcription.speakers

import android.app.Application
import android.util.Log
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.whispereverywhere.npu.NpuAssetStage

/**
 * THE ONE ADAPTER over sherpa's speaker-embedding extractor (sherpa-onnx 1.13.7) — a slice of PCM
 * in, a 192-float voice fingerprint out, `null` on every failure (4.10 Task 2, spec §3.2 step 2).
 *
 * It is the production [VoicePrints] (4.10 Task 3): that interface is the seam
 * [SpeakerAssigner] — which owns every decision about which samples are fingerprinted and what
 * becomes of the answer — is unit-tested through, precisely because nothing may reference THIS
 * class from a test (see "Why no test may name this class" below).
 *
 * ### The model, and why it is an asset
 *
 * NVIDIA NeMo **TitaNet-small**, `speaker_titanet_small_16k.onnx`: 40_257_283 bytes, sha256
 * `ad4a1802485d8b34c722d2a9d04249662f2ece5d28a7a039063ca22f515a789e`. The owner ruled on
 * 2026-09-18 that it is **bundled in the APK** ("let's bundle it so users don't have to download
 * another thing"), which is exactly what sherpa's `AssetManager` constructor is for: no download
 * flow, no Play pack, no network dependency, and no first-run state where a core behaviour is
 * missing. `SpeakerEmbedderPinTest` holds the length and the digest against the bytes that ship,
 * because a bundled model is the one input to this class that nothing on device can check.
 *
 * ### Why THIS model and not the CAM++ this file used to name
 *
 * `docs/measurements/2026-09-18-speaker-spike.md`, session 2. The owner ran three clips — exactly
 * one, two and three speakers — on the dump build; 132 segments' fingerprints and audio came back
 * to the PC, and **five** embedding models were scored on the same segments. The shipped
 * 3D-Speaker CAM++ scored its own single voice against itself as low as **0.04** (p5 0.26), so no
 * threshold and no tracker rule recovered 1/2/3 from it — *"the rules cannot rescue the signal"*.
 * TitaNet-small's same-voice minimum is **0.57** (p5 0.67), and twelve different tracker bands
 * give exactly 1/2/3 on the three clips; ERes2Net-base agrees with it on 100 % of segment pairs in
 * both multi-speaker clips, which is the best proxy for truth there is without hand labels. Cost
 * is unchanged (48 ms per fingerprint on the PC, the same as CAM++'s), so session 1's measured
 * 130-300 ms per fingerprint on the Tab carries over. It costs 10.7 MB more in the APK than CAM++
 * did — the one price of the swap, and the base module has the room (spec §3.4).
 *
 * ### Its licence is CC-BY-4.0, and the attribution is PAID
 *
 * NVIDIA NeMo TitaNet-small is **CC-BY-4.0**, which requires attribution — and unlike CAM++'s
 * Apache-2.0 that attribution has to be VISIBLE to the user, not merely permitted. So:
 *
 *  - the attribution line for NVIDIA NeMo TitaNet-small is **paid, see oss_licenses.html** — a
 *    **Speaker labels** section in `app/src/main/assets/oss_licenses.html` (4.10.0), naming
 *    NVIDIA, the NVIDIA NeMo toolkit, CC BY 4.0, both filenames with the shipped digest, what was
 *    changed (the name, and nothing else), the model card and the licence text.
 *    `SpeakerEmbedderPinTest` reads that page and fails the build if any of it leaves;
 *  - the **clearance-sheet row is the PRODUCTION GATE** (spec §3.4, the discipline
 *    `docs/LANGUAGE-CLEARANCE.md` already applies to every streaming pack): the spike may run on
 *    an uncleared model, the store build may not. A build gate would stop the measurement this
 *    model exists to serve; a production gate stops the only thing that actually matters. That row
 *    exists now and reads **PENDING OWNER SIGN-OFF**: paying the attribution discharges the
 *    licence's one term, which is not the same act as the owner accepting the basis — the seven
 *    language packs were cleared in exactly that order, the notice first and the decision after.
 *
 * ### 192 floats wide — read off the graph, not off the family name
 *
 * Read with `python onnx` off the bytes that ship: the graph's outputs are `logits`
 * `[MatMullogits_dim_0, 16681]` (the 16 681-speaker training head, which sherpa does not use) and
 * **`embs` `[Squeezeembs_dim_0, 192]`** — the fingerprint. Its input `audio_signal` is
 * `[N, 80, T]` (80-bin log-mel, FEATURE-major, the transpose of CAM++'s `[N, T, 80]`) beside a
 * `length` vector, and its ONNX `metadata_props` carry `output_dim = 192`, `feat_dim = 80`,
 * `sample_rate = 16000`, `framework = nemo` and
 * `url = catalog.ngc.nvidia.com/orgs/nvidia/teams/nemo/models/titanet_small`. **192, and this time
 * it is 192**: the same number that was WRONG for CAM++ (192 is the CN-Celeb CAM++, not the
 * en/voxceleb one this repo used to bundle) is right for this model, which is exactly the kind of
 * coincidence that makes a family-name guess look confirmed. Nothing at runtime depends on the
 * number ([SpeakerTracker] measures every embedding against the width of the fingerprints it
 * already holds, never against a constant), which is why a wrong one could sit here unnoticed: the
 * readers are people and plans — the spike's diag line, the per-segment cost, spec §3.3's resident
 * budget. So it is held from two sides instead.
 * `SpeakerEmbedderPinTest.theDocumentedEmbeddingWidthIsTheOneTheShippedGraphAnnounces` re-derives
 * it from the asset's own annotation, so swapping the model cannot leave this paragraph behind, and
 * both load arms log `dim()` so the device session reads the width off the loader too.
 *
 * ### Two ways in, in this order
 *
 * 1. **`app.assets`** — the bundling ruling's own path. The APK's copy is read straight out of the
 *    package; nothing is written to the device.
 * 2. **[NpuAssetStage]** — the contingency. If the `AssetManager` constructor refuses on some
 *    device (a finding the plan's device session would produce), the asset is staged into
 *    `filesDir`, verified by length AND digest, and handed over as an absolute path with a null
 *    `AssetManager` — the same shape `SherpaPreviewRecognizer` and `TtsEngine.buildTts` use for
 *    every model they load. It stays second because it costs 40.3 MB of the user's storage to fix
 *    a problem most devices do not have.
 *
 * ### Every failure is null, and a failure is remembered
 *
 * sherpa's constructor `require`s a non-zero native handle and throws `IllegalArgumentException`
 * otherwise; ONNX itself can throw from native code on a corrupt graph. All of it is caught: the
 * caller is the single-thread executor of plan Task 3, running beside the whisper thread, and an
 * exception escaping there would kill a thread the session depends on for a *label*.
 *
 * The [failed] latch is the other half. Without it a model that cannot load at all would be
 * re-attempted once per committed chunk — 40.3 MB of asset read every few seconds, forever, for a
 * capability that has already been established to be unavailable.
 *
 * ### Lifecycle and threading
 *
 * The model loads LAZILY, on the first [embed], on the caller's thread — one resident copy for the
 * session (spec §3.3 budgeted 40-60 MB against the 29.6 MB CAM++; this graph is 40.3 MB on its
 * own, so the floor of that range has moved up with it and the device session is what measures
 * the real figure), [release]d with the session. Not synchronised: one
 * instance per session, called only from the `speaker-embed` executor. `numThreads = 1` for the
 * same reason — the commit floors were measured without a second decoder-sized thread on these
 * cores.
 *
 * ### Why no test may name this class
 *
 * `SpeakerEmbeddingExtractor`'s companion initialiser calls `System.loadLibrary`, and
 * `libsherpa-onnx-jni.so` is not on the unit-test classpath. The decisions live next door in the
 * pure [SpeakerTracker]; this file is a seam and is pinned as SOURCE by `SpeakerEmbedderPinTest`,
 * the standing rule for all three of the app's sherpa adapters. R8 keeps the classes it touches
 * through `-keep class com.k2fsa.sherpa.onnx.** { *; }` (proguard-rules.pro) — never narrow it.
 *
 * The `Log` lines here are stripped from the release build by R8, so what the device session reads
 * is the assigner's one `WhisperNative.diag` line per chunk (plan Task 3), not these.
 */
class SpeakerEmbedder(private val app: Application) : VoicePrints {

    private var extractor: SpeakerEmbeddingExtractor? = null

    /** Sticky: a load that failed once is not retried for the life of this instance. */
    private var failed = false

    /**
     * Fingerprints [pcm] — one fingerprint window's samples from the ORIGINAL chunk timeline, mono float
     * in [-1, 1] at [sampleRate]. Returns the embedding, or null if the model is unavailable, the
     * stream never became ready, or anything at all threw.
     */
    override fun embed(pcm: FloatArray, sampleRate: Int): FloatArray? {
        if (pcm.isEmpty()) return null
        val loaded = extractor() ?: return null
        return try {
            val stream = loaded.createStream()
            try {
                stream.acceptWaveform(pcm, sampleRate)
                stream.inputFinished()
                if (loaded.isReady(stream)) loaded.compute(stream) else null
            } finally {
                stream.release()
            }
        } catch (cause: Throwable) {
            // Sample counts only — never a word of transcript in a log line.
            Log.w(TAG, "speaker: embed failed on ${pcm.size} samples (${cause.javaClass.simpleName})")
            null
        }
    }

    /** Frees the resident model. Called at session teardown; safe to call twice. */
    override fun release() {
        val loaded = extractor ?: return
        extractor = null
        runCatching { loaded.release() }
            .onFailure { Log.w(TAG, "speaker: release threw (${it.javaClass.simpleName})") }
    }

    // ------------------------------------------------------------------ the load

    private fun extractor(): SpeakerEmbeddingExtractor? {
        extractor?.let { return it }
        if (failed) return null
        val built = fromAsset() ?: fromStagedFile()
        if (built == null) {
            failed = true
            Log.w(TAG, "speaker: no embedder — labels stay off for this session")
        }
        extractor = built
        return built
    }

    private fun fromAsset(): SpeakerEmbeddingExtractor? = try {
        val started = System.currentTimeMillis()
        SpeakerEmbeddingExtractor(app.assets, config(ASSET)).also {
            Log.i(TAG, "speaker: model loaded from assets in ${System.currentTimeMillis() - started} ms, dim=${it.dim()}")
        }
    } catch (cause: Throwable) {
        Log.w(TAG, "speaker: asset load refused (${cause.javaClass.simpleName}) — trying a staged copy")
        null
    }

    private fun fromStagedFile(): SpeakerEmbeddingExtractor? = try {
        val path = NpuAssetStage.stagedPathWithMarker(app, ASSET, ASSET_BYTES, ASSET_SHA256)
        if (path == null) {
            null
        } else {
            val started = System.currentTimeMillis()
            SpeakerEmbeddingExtractor(null, config(path)).also {
                Log.i(TAG, "speaker: model loaded from filesDir in ${System.currentTimeMillis() - started} ms, dim=${it.dim()}")
            }
        }
    } catch (cause: Throwable) {
        Log.w(TAG, "speaker: staged load refused (${cause.javaClass.simpleName})")
        null
    }

    /** One config for both arms — [model] is either the asset name or an absolute path. */
    private fun config(model: String) = SpeakerEmbeddingExtractorConfig(
        model = model,
        numThreads = 1,
        debug = false,
        provider = "cpu",
    )

    private companion object {
        const val TAG = "WE-DIAG"

        /** The bundled TitaNet-small model. Length and digest are pinned in `SpeakerEmbedderPinTest`. */
        const val ASSET = "speaker_titanet_small_16k.onnx"
        const val ASSET_BYTES = 40_257_283L
        const val ASSET_SHA256 = "ad4a1802485d8b34c722d2a9d04249662f2ece5d28a7a039063ca22f515a789e"
    }
}
