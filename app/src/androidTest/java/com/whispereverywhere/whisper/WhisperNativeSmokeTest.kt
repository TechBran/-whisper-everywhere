package com.whispereverywhere.whisper

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
class WhisperNativeSmokeTest {

    /**
     * Reads a 16-bit PCM mono WAV (any standard 44-byte header) into float32 [-1,1].
     * The bundled jfk.wav is 16 kHz mono, matching whisper's native rate.
     */
    private fun wavToFloat(bytes: ByteArray): FloatArray {
        // Locate the "data" chunk instead of assuming a fixed 44-byte header.
        var i = 12 // skip "RIFF"<size>"WAVE"
        var dataOffset = 44
        var dataLen = bytes.size - 44
        while (i + 8 <= bytes.size) {
            val id = String(bytes, i, 4, Charsets.US_ASCII)
            val chunkSize = ByteBuffer.wrap(bytes, i + 4, 4)
                .order(ByteOrder.LITTLE_ENDIAN).int
            if (id == "data") {
                dataOffset = i + 8
                dataLen = chunkSize
                break
            }
            i += 8 + chunkSize + (chunkSize and 1)
        }
        val sampleCount = dataLen / 2
        val out = FloatArray(sampleCount)
        val bb = ByteBuffer.wrap(bytes, dataOffset, dataLen).order(ByteOrder.LITTLE_ENDIAN)
        for (s in 0 until sampleCount) {
            out[s] = bb.short / 32768.0f
        }
        return out
    }

    @Test
    fun transcribes_bundled_jfk_to_nonempty_text() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val ctx = inst.targetContext

        // Sideloaded tiny model path is passed as an instrumentation arg:
        //   -e modelPath /data/local/tmp/ggml-tiny.en-q5_1.bin
        // (see Step 6 for the adb push + run command). Skip cleanly if absent so
        // the suite doesn't hard-fail on machines without the sideloaded model.
        val args = InstrumentationRegistry.getArguments()
        val modelPath = args.getString("modelPath")
        assumeTrue("No sideloaded model (pass -e modelPath ...); skipping", modelPath != null)
        val modelFile = File(modelPath!!)
        assumeTrue("Model file missing at $modelPath", modelFile.exists())

        // Copy bundled jfk.wav (androidTest asset) to a real file, decode to float32.
        val wavBytes = inst.context.assets.open("jfk.wav").use { it.readBytes() }
        val samples = wavToFloat(wavBytes)
        assertTrue("decoded samples should be non-empty", samples.isNotEmpty())

        val ctxPtr = WhisperNative.init(modelFile.absolutePath)
        assertNotEquals("init() returned 0 (model failed to load)", 0L, ctxPtr)
        try {
            val text = WhisperNative.transcribe(ctxPtr, samples, "en", false)
            assertTrue("transcription should be non-empty", text.trim().isNotEmpty())
            // jfk.wav says "...ask not what your country can do for you...".
            assertTrue(
                "expected recognizable JFK content, got: '$text'",
                text.lowercase().contains("country") || text.lowercase().contains("ask")
            )
        } finally {
            WhisperNative.free(ctxPtr)
        }
    }
}
