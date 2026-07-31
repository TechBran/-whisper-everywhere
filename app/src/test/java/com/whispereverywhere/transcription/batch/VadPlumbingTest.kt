package com.whispereverywhere.transcription.batch

import com.whispereverywhere.recording.RecordingMeta
import com.whispereverywhere.recording.RecordingStore
import com.whispereverywhere.transcription.ModelPathProvider
import com.whispereverywhere.transcription.WhisperBackend
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Pins the BATCH-ONLY VAD bypass at the plumbing seam: batch's local path must ask the backend for
 * NO VAD (transcribe a user-chosen file in full), while a caller that omits `useVad` — the live
 * contract — gets VAD on by the interface default. No native/CMake touch; this is pure Kotlin
 * threading, so nothing here is device-verification-required.
 */
class VadPlumbingTest {

    @get:Rule val tmp = TemporaryFolder()

    private val modelPath = object : ModelPathProvider { override fun installedModelPath() = "/models/x.bin" }

    private class RecordingBackend : WhisperBackend {
        val vadFlags = mutableListOf<Boolean>()
        override fun load(modelPath: String) = 1L
        override fun transcribe(ctx: Long, samples: FloatArray, lang: String?, useVad: Boolean): String {
            vadFlags += useVad; return "x"
        }
        override fun release(ctx: Long) {}
    }

    private fun storeWith(pcmBytes: Int): Pair<RecordingStore, String> {
        val s = RecordingStore(File(tmp.root, "batch"))
        val id = "clip1"
        s.audioFile(id).writeBytes(ByteArray(pcmBytes) { (it % 251).toByte() })
        s.save(RecordingMeta(id = id, createdAtMs = 1L, durationMs = 3000L, displayName = "clip.m4a", byteLength = pcmBytes))
        return s to id
    }

    @Test fun batch_local_path_requests_no_vad() = runBlocking {
        val (store, id) = storeWith(pcmBytes = 8000)
        val backend = RecordingBackend()
        BatchTranscriber(store, cloud = null, backend = backend, modelPathProvider = modelPath)
            .apply { testCloudCeiling = 3000; testLocalChunk = 3000 }.transcribe(id)
        assertTrue("every batch local transcribe asked for NO vad", backend.vadFlags.isNotEmpty())
        assertTrue("no batch local transcribe may enable VAD", backend.vadFlags.all { it == false })
    }

    @Test fun default_signature_keeps_vad_on_for_live() {
        // The interface default is the live contract: a caller that omits useVad gets VAD.
        val b = RecordingBackend()
        b.transcribe(1L, FloatArray(0), "en")
        assertEquals(listOf(true), b.vadFlags)
    }
}
