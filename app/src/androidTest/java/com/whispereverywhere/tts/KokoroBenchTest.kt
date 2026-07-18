package com.whispereverywhere.tts

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Track F Task-0 GATE: measures Kokoro RTF + first-sentence latency on THIS device.
 *
 * Sideload a sherpa-onnx Kokoro model directory and run:
 *   adb push kokoro-multi-lang-v1_1 /data/local/tmp/
 *   ... -e ttsModelDir /data/local/tmp/kokoro-multi-lang-v1_1 [-e ttsInt8 true]
 *
 * Results go to logcat tag WE-TTS-BENCH; the plan's bench table records them.
 */
@RunWith(AndroidJUnit4::class)
class KokoroBenchTest {

    // ~12 s of speech at normal rate; multiple sentences so first-callback latency is the
    // realistic tap-to-audio number.
    private val PARAGRAPH =
        "The quick brown fox jumps over the lazy dog near the riverbank. " +
        "On-device speech synthesis has improved dramatically in recent years. " +
        "This benchmark measures how fast the model produces audio on this phone. " +
        "If the real-time factor stays below one, playback never has to pause."

    @Test
    fun bench_kokoro_rtf_and_first_sentence_latency() {
        val args = InstrumentationRegistry.getArguments()
        val dir = args.getString("ttsModelDir")
        assumeTrue("No sideloaded TTS model (pass -e ttsModelDir ...); skipping", dir != null)
        val modelDir = File(dir!!)
        assumeTrue("Model dir missing at $dir", modelDir.isDirectory)

        val int8 = args.getString("ttsInt8")?.toBoolean() ?: false
        val modelFile = File(modelDir, if (int8) "model.int8.onnx" else "model.onnx")
        assumeTrue("Model file missing at $modelFile", modelFile.exists())

        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                kokoro = OfflineTtsKokoroModelConfig(
                    model = modelFile.absolutePath,
                    voices = File(modelDir, "voices.bin").absolutePath,
                    tokens = File(modelDir, "tokens.txt").absolutePath,
                    dataDir = File(modelDir, "espeak-ng-data").absolutePath,
                    dictDir = "",
                    lexicon = listOf("lexicon-us-en.txt", "lexicon-gb-en.txt")
                        .map { File(modelDir, it) }
                        .filter { it.exists() }
                        .joinToString(",") { it.absolutePath },
                ),
                numThreads = 4,
                debug = false,
                provider = "cpu",
            ),
        )

        val t0 = System.currentTimeMillis()
        val tts = OfflineTts(config = config)
        val loadMs = System.currentTimeMillis() - t0
        try {
            android.util.Log.i("WE-TTS-BENCH", "variant=${if (int8) "int8" else "fp32"} loadMs=$loadMs numSpeakers=${tts.numSpeakers()} sampleRate=${tts.sampleRate()}")

            // First-sentence latency via callback timing (tap-to-audio proxy).
            var firstChunkMs = -1L
            val t1 = System.currentTimeMillis()
            var totalSamples = 0L
            // MUST be an explicit Function1 object, NOT a lambda: sherpa's JNI reflectively
            // calls invoke([F)Ljava/lang/Integer;, a specialized bridge that Kotlin 2.0's
            // default invokedynamic lambdas don't generate (proven by SIGABRT on-device).
            val callback = object : Function1<FloatArray, Int> {
                override fun invoke(samples: FloatArray): Int {
                    if (firstChunkMs < 0) firstChunkMs = System.currentTimeMillis() - t1
                    totalSamples += samples.size
                    return 1 // continue
                }
            }
            tts.generateWithCallback(text = PARAGRAPH, sid = 0, speed = 1.0f, callback = callback)
            val synthMs = System.currentTimeMillis() - t1

            val audioSec = totalSamples.toDouble() / tts.sampleRate()
            val rtf = (synthMs / 1000.0) / audioSec
            android.util.Log.i(
                "WE-TTS-BENCH",
                "variant=${if (int8) "int8" else "fp32"} synthMs=$synthMs audioSec=%.2f rtf=%.3f firstChunkMs=$firstChunkMs".format(audioSec, rtf)
            )

            assertTrue("synthesis produced no audio", totalSamples > 0)
            assertTrue("audio impossibly short for the paragraph", audioSec > 5.0)
        } finally {
            tts.release()
        }
    }
}
