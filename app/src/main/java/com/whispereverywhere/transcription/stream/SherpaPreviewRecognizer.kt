package com.whispereverywhere.transcription.stream

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.VersionInfo
import java.io.File

/**
 * THE ONE ADAPTER over the AAR's streaming classes (sherpa-onnx 1.13.7, ORT 1.27.1 —
 * app/build.gradle.kts:596-598). The config is the probe's verbatim (rung 3 §2): 16 kHz / 80-bin
 * features with dither 0, `provider = "cpu"` (NNAPI is compiled out
 * of this AAR — rung 3 §5.6), `enableEndpoint = false` (the app's Silero cuts, never sherpa's),
 * `decodingMethod = "greedy_search"` (beam costs +40 % and retracts — rung 1 §7),
 * `assetManager = null` (absolute paths, as TtsEngine.buildTts does). Never referenced from a
 * test: the AAR's static init loads a native library — so its contract is pinned as SOURCE, by
 * `SherpaPreviewLoaderPinTest`.
 *
 * ### `modelType` is EMPTY on purpose, and restoring `"zipformer2"` ships a process kill
 *
 * 4.4.0 passed the literal `"zipformer2"`, which is true of the shipping English encoder and of
 * nothing else the catalogue is about to carry: **French and both bilingual zh-en exports are
 * `zipformer` v1**. `OnlineTransducerModel::Create` short-circuits on a NON-EMPTY config string
 * (`online-transducer-model.cc:146`) and forces `OnlineZipformer2TransducerModel`, which reads
 * `query_head_dims` through `SHERPA_ONNX_READ_META_DATA_VEC` — and that macro's miss path is
 * `SHERPA_ONNX_EXIT(-1)` = **`_Exit(-1)`**. An **uncatchable process kill**: it reaches neither
 * [StreamingPreviewEngine.warm]'s `catch`, nor `onLoadFailure`, nor `markCorrupt`, nor any
 * disabled flag, and leaves no crash for a sentinel to find. The app simply vanishes.
 *
 * Empty hands the decision back to the file, where it belongs: `OnlineZipformerTransducerModel`
 * reads exactly seven keys and **all seven are present in the French encoder**, English keeps
 * loading because its own metadata says `zipformer2`, and empty is valid for **every** candidate
 * in the qualification table (§6(2), whose finding this is). [StreamingPack.modelType] records
 * what each encoder will answer; nothing passes it here.
 *
 * R8: `-keep class com.k2fsa.sherpa.onnx.** { *; }` (proguard-rules.pro) covers these classes;
 * a narrowed keep is a GetFieldID SIGABRT in release builds (the rule's comment says so).
 */
class SherpaPreviewRecognizerFactory : PreviewRecognizerFactory {

    override fun sherpaVersion(): String = runCatching { VersionInfo.version }.getOrDefault("?")

    // 1.13.5+ carries the getter (rung 3 §1.2: javap on classes.jar); the probe read it by reflection
    // only to survive the 1.13.4 arm. If the Kotlin property name differs on this AAR, use the
    // probe's `VersionInfo.Companion.javaClass.getMethod("getOnnxruntimeVersion")` form.
    override fun ortVersion(): String = runCatching { VersionInfo.onnxruntimeVersion }.getOrDefault("?")

    override fun load(dir: File, pack: StreamingPack, numThreads: Int): PreviewRecognizer {
        val config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = StreamingPreviewTuning.SAMPLE_RATE, featureDim = 80, dither = 0f),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = File(dir, pack.encoder.name).absolutePath,
                    decoder = File(dir, pack.decoder.name).absolutePath,
                    joiner = File(dir, pack.joiner.name).absolutePath,
                ),
                tokens = File(dir, pack.tokens.name).absolutePath,
                numThreads = numThreads,
                debug = false,
                provider = "cpu",
                // EMPTY, never the pack's own model_type and never a literal: a non-empty string
                // short-circuits the family choice and a wrong one is _Exit(-1), uncatchable.
                // The encoder's metadata decides. See the docblock above before editing this.
                modelType = "",
            ),
            enableEndpoint = false,
            decodingMethod = "greedy_search",
        )
        return SherpaPreviewRecognizer(OnlineRecognizer(null, config))
    }
}

private class SherpaStream(val inner: OnlineStream) : PreviewStream {
    override fun acceptWaveform(samples: FloatArray) = inner.acceptWaveform(samples, StreamingPreviewTuning.SAMPLE_RATE)
    override fun inputFinished() = inner.inputFinished()
    override fun release() = inner.release()
}

class SherpaPreviewRecognizer(private val inner: OnlineRecognizer) : PreviewRecognizer {
    override fun createStream(): PreviewStream = SherpaStream(inner.createStream(""))
    override fun isReady(stream: PreviewStream): Boolean = inner.isReady((stream as SherpaStream).inner)
    override fun decode(stream: PreviewStream) = inner.decode((stream as SherpaStream).inner)
    override fun result(stream: PreviewStream): PreviewResult {
        val r = inner.getResult((stream as SherpaStream).inner)
        return PreviewResult(r.text, r.tokens.toList(), r.timestamps)
    }
    override fun release() = inner.release()
}
