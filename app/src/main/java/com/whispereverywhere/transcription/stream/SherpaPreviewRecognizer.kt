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
 * features with dither 0, `modelType = "zipformer2"`, `provider = "cpu"` (NNAPI is compiled out
 * of this AAR — rung 3 §5.6), `enableEndpoint = false` (the app's Silero cuts, never sherpa's),
 * `decodingMethod = "greedy_search"` (beam costs +40 % and retracts — rung 1 §7),
 * `assetManager = null` (absolute paths, as TtsEngine.buildTts does). Never referenced from a
 * test: the AAR's static init loads a native library.
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
                modelType = "zipformer2",
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
