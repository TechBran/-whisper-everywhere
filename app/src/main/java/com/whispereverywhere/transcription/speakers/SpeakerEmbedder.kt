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
 * ### The model, and why it is an asset
 *
 * 3D-Speaker **CAM++**, `speaker_campplus_en_16k.onnx`: 29_596_978 bytes, sha256
 * `357a834f702b80161e5b981182c038e18553c1f2ca752ed6cec2052365d4129b`. The owner ruled on
 * 2026-09-18 that it is **bundled in the APK** ("let's bundle it so users don't have to download
 * another thing"), which is exactly what sherpa's `AssetManager` constructor is for: no download
 * flow, no Play pack, no network dependency, and no first-run state where a core behaviour is
 * missing. `SpeakerEmbedderPinTest` holds the length and the digest against the bytes that ship,
 * because a bundled model is the one input to this class that nothing on device can check.
 *
 * Its licence clearance is a PRODUCTION gate, not a build gate (spec §3.4): the spike may run on
 * the uncleared model, the store build may not.
 *
 * ### Two ways in, in this order
 *
 * 1. **`app.assets`** — the bundling ruling's own path. The APK's copy is read straight out of the
 *    package; nothing is written to the device.
 * 2. **[NpuAssetStage]** — the contingency. If the `AssetManager` constructor refuses on some
 *    device (a finding the plan's device session would produce), the asset is staged into
 *    `filesDir`, verified by length AND digest, and handed over as an absolute path with a null
 *    `AssetManager` — the same shape `SherpaPreviewRecognizer` and `TtsEngine.buildTts` use for
 *    every model they load. It stays second because it costs 29.6 MB of the user's storage to fix
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
 * re-attempted once per committed chunk — 29.6 MB of asset read every few seconds, forever, for a
 * capability that has already been established to be unavailable.
 *
 * ### Lifecycle and threading
 *
 * The model loads LAZILY, on the first [embed], on the caller's thread — one resident copy for the
 * session (roughly 40-60 MB, spec §3.3), [release]d with the session. Not synchronised: one
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
class SpeakerEmbedder(private val app: Application) {

    private var extractor: SpeakerEmbeddingExtractor? = null

    /** Sticky: a load that failed once is not retried for the life of this instance. */
    private var failed = false

    /**
     * Fingerprints [pcm] — one VAD segment's samples from the ORIGINAL chunk timeline, mono float
     * in [-1, 1] at [sampleRate]. Returns the embedding, or null if the model is unavailable, the
     * stream never became ready, or anything at all threw.
     */
    fun embed(pcm: FloatArray, sampleRate: Int = 16_000): FloatArray? {
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
    fun release() {
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

        /** The bundled CAM++ model. Its length and digest are pinned in `SpeakerEmbedderPinTest`. */
        const val ASSET = "speaker_campplus_en_16k.onnx"
        const val ASSET_BYTES = 29_596_978L
        const val ASSET_SHA256 = "357a834f702b80161e5b981182c038e18553c1f2ca752ed6cec2052365d4129b"
    }
}
