package com.whispereverywhere.whisper

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.whispereverywhere.model.WhisperCatalog
import com.whispereverywhere.transcription.WhisperNativeBackend
import com.whispereverywhere.util.AudioMath
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Release A Task 6 GATE: the first MEASURED (not modelled) numbers for on-device whisper.cpp
 * transcription latency. Every local-vs-cloud latency claim in the spec so far has been
 * modelled, and `jfk.wav` has sat unused in androidTest/assets since the repo's first commit.
 * The TTS side was measured on 2026-07-27 and overturned five conclusions, including one wrong
 * figure that had already reached the spec. This is the STT side's turn.
 *
 * Run it against whatever model(s) the app already has downloaded on THIS device (installing
 * a model is done in-app; this test does not sideload one):
 *   adb install -r <buildDir>/app/outputs/apk/debug/app-debug.apk
 *   adb install -r <buildDir>/app/outputs/apk/androidTest/debug/app-debug-androidTest.apk
 *   adb shell am instrument -w -e class com.whispereverywhere.whisper.WhisperBenchTest \
 *     com.whispereverywhere.test/androidx.test.runner.AndroidJUnitRunner
 * `-r` reinstall does not touch app data, so previously-downloaded models survive and are what
 * gets benched. If nothing is installed, the test SKIPS (via assumeTrue) rather than failing.
 *
 * Goes through [WhisperNativeBackend] -- the SAME seam [com.whispereverywhere.transcription.
 * LocalWhisperEngine] uses in production (VAD trimming + GpuPolicy's CPU/GPU decision included)
 * -- not the raw JNI layer, so the RTF measured here is what a real dictation session actually
 * pays, not an optimistic lower bound.
 *
 * Results go to logcat tag WE-BENCH: grep "BENCH stt" for the per-slice lines, or "summary" for
 * the p50/p95/max rollup per tier.
 */
@RunWith(AndroidJUnit4::class)
class WhisperBenchTest {

    private companion object {
        const val TAG = "WE-BENCH"
        const val SAMPLE_RATE = 16000

        /**
         * Slice durations to bench, in whole seconds. jfk.wav is 11 s of continuous speech (no
         * leading/trailing silence), shorter than the largest slice -- see [tileToDuration].
         */
        val SLICE_SECONDS = listOf(1, 3, 8, 15)
    }

    @Test
    fun bench_whisper_rtf_across_slices() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        val modelsDir = File(context.filesDir, "models")

        // "Installed" mirrors WhisperModelManager.isInstalled: file present on disk, size within
        // the catalog's tolerance. Bench whichever tier(s) the USER actually has -- there is no
        // sideload arg here, exactly as the run command above shows.
        val installed = WhisperCatalog.entries.filter { model ->
            val f = File(modelsDir, model.fileName)
            f.exists() && WhisperCatalog.sizeWithinTolerance(f.length(), model.approxBytes)
        }
        assumeTrue(
            "No installed whisper model found under ${modelsDir.absolutePath}; " +
                "download one in-app first, then rerun this test",
            installed.isNotEmpty(),
        )

        // Bundled jfk.wav (androidTest asset) -- already 16 kHz mono PCM16, whisper's native rate.
        val wavBytes = inst.context.assets.open("jfk.wav").use { it.readBytes() }
        val pcm = wavDataChunk(wavBytes)
        assertTrue("jfk.wav decoded to no PCM samples", pcm.isNotEmpty())

        for (model in installed) {
            benchTier(model.id, File(modelsDir, model.fileName).absolutePath, pcm)
        }
    }

    /** Loads [modelPath] once, times each slice's transcription, then frees the context. */
    private fun benchTier(tier: String, modelPath: String, pcm: ByteArray) {
        val t0 = System.currentTimeMillis()
        val ctx = WhisperNativeBackend.load(modelPath)
        val loadMs = System.currentTimeMillis() - t0
        assertNotEquals("model load returned 0 (failed) for tier=$tier at $modelPath", 0L, ctx)
        android.util.Log.i(TAG, "load tier=$tier loadMs=$loadMs")

        try {
            val rtfs = mutableListOf<Double>()
            for (seconds in SLICE_SECONDS) {
                val slicePcm = tileToDuration(pcm, seconds, SAMPLE_RATE)
                val samples = AudioMath.pcm16ToFloat(slicePcm)
                val audioMs = samples.size.toLong() * 1000L / SAMPLE_RATE

                val w0 = System.currentTimeMillis()
                val text = WhisperNativeBackend.transcribe(ctx, samples, "en")
                val wallMs = System.currentTimeMillis() - w0
                val rtf = wallMs.toDouble() / audioMs.toDouble()
                rtfs += rtf

                android.util.Log.i(
                    TAG,
                    "BENCH stt tier=$tier slice=${seconds}s audioMs=$audioMs wallMs=$wallMs rtf=%.3f"
                        .format(rtf),
                )

                // Only that text came back is asserted -- a bench that fails on a slow device is
                // a flaky test, not a measurement. No latency threshold here.
                assertTrue(
                    "tier=$tier slice=${seconds}s produced blank transcription",
                    text.isNotBlank(),
                )
            }

            val sorted = rtfs.sorted()
            android.util.Log.i(
                TAG,
                "BENCH stt tier=$tier summary p50Rtf=%.3f p95Rtf=%.3f maxRtf=%.3f".format(
                    percentile(sorted, 0.50),
                    percentile(sorted, 0.95),
                    sorted.last(),
                ),
            )
        } finally {
            WhisperNativeBackend.release(ctx)
        }
    }

    /**
     * Locates the "data" chunk of a standard WAV file and returns its raw bytes, without
     * assuming a fixed 44-byte header. Mirrors WhisperNativeSmokeTest.wavToFloat's chunk walk
     * in this same package (that one decodes straight to float; this one keeps raw PCM16 bytes
     * so [tileToDuration] can slice/repeat it before the float conversion).
     */
    private fun wavDataChunk(bytes: ByteArray): ByteArray {
        var i = 12 // skip "RIFF"<size>"WAVE"
        while (i + 8 <= bytes.size) {
            val id = String(bytes, i, 4, Charsets.US_ASCII)
            val chunkSize = ByteBuffer.wrap(bytes, i + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (id == "data") {
                return bytes.copyOfRange(i + 8, i + 8 + chunkSize)
            }
            i += 8 + chunkSize + (chunkSize and 1)
        }
        return ByteArray(0)
    }

    /**
     * Builds an exact-duration PCM16 mono buffer by repeating [source] as needed. jfk.wav is
     * only 11 s -- shorter than the largest bench slice (15 s) -- so slices past 11 s wrap back
     * to the start of the clip rather than padding with silence. A silent pad would make the
     * longer slices' RTF look artificially good (whisper's VAD trims silence near-free) instead
     * of measuring sustained decode throughput, which is the point of benching a 15 s slice.
     */
    private fun tileToDuration(source: ByteArray, seconds: Int, sampleRate: Int): ByteArray {
        val neededBytes = seconds * sampleRate * 2 // PCM16 mono = 2 bytes/sample
        val out = ByteArray(neededBytes)
        var pos = 0
        while (pos < neededBytes) {
            val take = minOf(source.size, neededBytes - pos)
            System.arraycopy(source, 0, out, pos, take)
            pos += take
        }
        return out
    }

    /**
     * Linear-interpolated percentile over an already-sorted list. Nearest-rank would be too
     * coarse with only [SLICE_SECONDS].size data points to draw from.
     */
    private fun percentile(sorted: List<Double>, p: Double): Double {
        if (sorted.isEmpty()) return 0.0
        if (sorted.size == 1) return sorted[0]
        val idx = p * (sorted.size - 1)
        val lo = idx.toInt()
        val hi = minOf(lo + 1, sorted.size - 1)
        val frac = idx - lo
        return sorted[lo] + (sorted[hi] - sorted[lo]) * frac
    }
}
