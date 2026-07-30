package com.whispereverywhere.transcription.batch

import com.whispereverywhere.net.FakeHttpTransport
import com.whispereverywhere.net.HttpResult
import com.whispereverywhere.recording.BatchStatus
import com.whispereverywhere.recording.ChunkStatus
import com.whispereverywhere.recording.EngineUsed
import com.whispereverywhere.recording.RecordingMeta
import com.whispereverywhere.recording.RecordingStore
import com.whispereverywhere.transcription.ModelPathProvider
import com.whispereverywhere.transcription.WhisperBackend
import com.whispereverywhere.transcription.cloud.OpenAiStt
import com.whispereverywhere.transcription.cloud.SttError
import com.whispereverywhere.transcription.cloud.SttProvider
import com.whispereverywhere.transcription.cloud.SttResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class BatchTranscriberTest {

    @get:Rule val tmp = TemporaryFolder()

    /** A fake native backend: every chunk transcribes to a fixed marker so order is checkable. */
    private class FakeBackend(private val text: String = "L") : WhisperBackend {
        var loads = 0; var releases = 0; var calls = 0
        override fun load(modelPath: String): Long { loads++; return 42L }
        override fun transcribe(ctx: Long, samples: FloatArray, lang: String?): String { calls++; return text }
        override fun release(ctx: Long) { releases++ }
    }
    private val modelPath = object : ModelPathProvider { override fun installedModelPath() = "/models/x.bin" }

    private fun storeWith(pcmBytes: Int): Pair<RecordingStore, String> {
        val s = RecordingStore(File(tmp.root, "batch"))
        val id = "clip1"
        s.audioFile(id).writeBytes(ByteArray(pcmBytes) { (it % 251).toByte() })
        s.save(RecordingMeta(id = id, createdAtMs = 1L, durationMs = 3000L, displayName = "clip.m4a", byteLength = pcmBytes))
        return s to id
    }

    // Small ceilings so tests make several chunks without megabytes of PCM. Injected via a subclass
    // of the planner call in BatchTranscriber (the transcriber exposes chunk-ceiling overrides for tests).

    @Test fun local_only_transcribes_every_chunk_and_marks_done() = runBlocking {
        val (store, id) = storeWith(pcmBytes = 20_000)
        val backend = FakeBackend("hi ")
        val t = BatchTranscriber(store, cloud = null, backend = backend, modelPathProvider = modelPath)
            .apply { testCloudCeiling = 6000; testLocalChunk = 6000 }
        t.transcribe(id)
        val m = store.read(id)!!
        assertEquals(BatchStatus.Done, m.status)
        assertTrue("multiple chunks", m.chunkPlan.size >= 3)
        assertTrue(m.chunkPlan.all { it.status == ChunkStatus.Done })
        assertEquals(backend.loads, backend.releases)               // ctx released
        assertEquals("hi hi hi hi ".trimEnd().length, store.assembledText(m).trimEnd().length)
    }

    @Test fun cloud_happy_path_uses_the_provider_and_marks_openai() = runBlocking {
        val (store, id) = storeWith(pcmBytes = 8000)
        val fake = FakeHttpTransport { _, _ -> HttpResult.Ok(200, """{"text":"C "}""") }
        val t = BatchTranscriber(store, cloud = OpenAiStt(fake, "sk-k"), backend = FakeBackend(),
            modelPathProvider = modelPath).apply { testCloudCeiling = 3000; testLocalChunk = 3000 }
        t.transcribe(id)
        val m = store.read(id)!!
        assertTrue(fake.callCount >= 2)
        assertEquals(EngineUsed.OPENAI, m.engineUsed)
        assertTrue(store.assembledText(m).trim().startsWith("C"))
    }

    @Test fun a_cloud_chunk_that_fails_transiently_falls_back_to_local_one_way() = runBlocking {
        val (store, id) = storeWith(pcmBytes = 4000)
        // 503 -> Transient; after retries the chunk falls to local.
        val fake = FakeHttpTransport { _, _ -> HttpResult.HttpError(503, "upstream") }
        val backend = FakeBackend("LOCAL-SAVED")
        val t = BatchTranscriber(store, cloud = OpenAiStt(fake, "sk-k"), backend = backend,
            modelPathProvider = modelPath).apply { testCloudCeiling = 5000; testLocalChunk = 5000; testMaxCloudRetries = 2 }
        t.transcribe(id)
        val m = store.read(id)!!
        assertEquals(BatchStatus.Done, m.status)
        assertTrue("local rescued the chunk", store.assembledText(m).contains("LOCAL-SAVED"))
    }

    @Test fun a_fatal_cloud_error_latches_stops_and_leaves_finished_chunks_for_resume() = runBlocking {
        val (store, id) = storeWith(pcmBytes = 9000)
        val n = AtomicInteger(0)
        // First chunk 200, then 401 (bad key) forever. The 401 must latch: no further requests.
        val fake = FakeHttpTransport { _, _ ->
            if (n.getAndIncrement() == 0) HttpResult.Ok(200, """{"text":"first "}""")
            else HttpResult.HttpError(401, "")
        }
        val t = BatchTranscriber(store, cloud = OpenAiStt(fake, "sk-bad"), backend = FakeBackend(),
            modelPathProvider = modelPath).apply { testCloudCeiling = 3000; testLocalChunk = 3000 }
        t.transcribe(id)
        val m = store.read(id)!!
        assertEquals(BatchStatus.Failed, m.status)
        assertEquals("chunk 0 kept as a checkpoint", ChunkStatus.Done, m.chunkPlan[0].status)
        assertTrue("chunk 1 not done", m.chunkPlan[1].status != ChunkStatus.Done)
        // Exactly two calls: the 200 and the single 401 that latched. Not one per remaining chunk.
        assertEquals(2, fake.callCount)
    }

    @Test fun retry_resumes_and_never_re_runs_a_done_chunk() = runBlocking {
        val (store, id) = storeWith(pcmBytes = 9000)
        // Pre-seed a plan where chunk 0 is already Done.
        val seeded = store.read(id)!!.copy(
            status = BatchStatus.PartiallyDone,
            chunkPlan = listOf(
                com.whispereverywhere.recording.ChunkEntry(0, 0, 3000, false, ChunkStatus.Done, "kept "),
                com.whispereverywhere.recording.ChunkEntry(1, 3000, 6000, false, ChunkStatus.Pending),
                com.whispereverywhere.recording.ChunkEntry(2, 6000, 9000, false, ChunkStatus.Pending),
            ),
        )
        store.save(seeded)
        val backend = FakeBackend("new ")
        val t = BatchTranscriber(store, cloud = null, backend = backend, modelPathProvider = modelPath)
        t.transcribe(id)  // resume
        val m = store.read(id)!!
        assertEquals(BatchStatus.Done, m.status)
        assertEquals("only the two pending chunks were transcribed", 2, backend.calls)
        assertEquals("kept ", m.chunkPlan[0].text)   // untouched
    }

    @Test fun reset_re_transcribes_every_chunk_from_scratch() = runBlocking {
        val (store, id) = storeWith(pcmBytes = 6000)
        store.save(store.read(id)!!.copy(chunkPlan = listOf(
            com.whispereverywhere.recording.ChunkEntry(0, 0, 6000, false, ChunkStatus.Done, "old"),
        )))
        val backend = FakeBackend("fresh ")
        val t = BatchTranscriber(store, cloud = null, backend = backend, modelPathProvider = modelPath)
        t.transcribe(id, reset = true)
        val m = store.read(id)!!
        assertTrue("plan re-computed and re-run", store.assembledText(m).contains("fresh"))
        assertTrue(backend.calls >= 1)
    }

    @Test fun cancel_stops_between_chunks_keeps_partial_and_never_deletes_audio() = runBlocking {
        val (store, id) = storeWith(pcmBytes = 30_000)
        val backend = object : WhisperBackend {
            var calls = 0
            override fun load(modelPath: String) = 1L
            override fun transcribe(ctx: Long, samples: FloatArray, lang: String?): String {
                calls++; return "c"
            }
            override fun release(ctx: Long) {}
        }
        val t = BatchTranscriber(store, cloud = null, backend = backend, modelPathProvider = modelPath)
            .apply { testCloudCeiling = 3000; testLocalChunk = 3000; onChunkDone = { cancel() } } // cancel after first
        t.transcribe(id)
        val m = store.read(id)!!
        assertEquals(BatchStatus.PartiallyDone, m.status)
        assertTrue("audio.pcm is preserved on cancel", store.audioFile(id).exists())
        assertTrue("stopped early", m.chunkPlan.count { it.status == ChunkStatus.Done } < m.chunkPlan.size)
    }

    @Test fun the_owned_native_executor_is_closed_when_the_job_ends() = runBlocking {
        // Review Important: the "batch-native" single-thread executor was leaked once per completed
        // job — created eagerly in the ctor, but closed only from the service's onDestroy, which the
        // normal completion path skips. The class now self-closes it in transcribe()'s finally.
        val (store, id) = storeWith(pcmBytes = 8000)
        val t = BatchTranscriber(store, cloud = null, backend = FakeBackend(), modelPathProvider = modelPath)
            .apply { testCloudCeiling = 3000; testLocalChunk = 3000 }
        assertTrue("executor is open before the job", !t.nativeExecutorClosed)
        t.transcribe(id)
        assertTrue("the class must close its own batch-native executor when the job ends", t.nativeExecutorClosed)
        // Idempotent: an external backstop shutdown() after self-close is a harmless no-op.
        t.shutdown()
        assertTrue(t.nativeExecutorClosed)
    }

    @Test fun a_fallback_cloud_chunk_is_re_sliced_to_local_sized_sub_chunks() = runBlocking {
        // THE OOM GUARD (review Critical): chunks are planned at the CLOUD ceiling when a provider
        // is present. A chunk that then falls back must never reach the native model whole —
        // pcm16ToFloat on a 20 MB chunk alone allocates a ~40 MB FloatArray. Every native call must
        // see at most LOCAL_CHUNK_BYTES/2 samples.
        val (store, id) = storeWith(pcmBytes = 24_000)
        val fake = FakeHttpTransport { _, _ -> HttpResult.HttpError(503, "down") } // always falls back
        val maxSamplesSeen = AtomicInteger(0)
        val backend = object : WhisperBackend {
            override fun load(modelPath: String) = 1L
            override fun transcribe(ctx: Long, samples: FloatArray, lang: String?): String {
                maxSamplesSeen.getAndUpdate { seen -> maxOf(seen, samples.size) }
                return "s"
            }
            override fun release(ctx: Long) {}
        }
        val t = BatchTranscriber(store, cloud = OpenAiStt(fake, "sk-k"), backend = backend,
            modelPathProvider = modelPath)
            .apply { testCloudCeiling = 24_000; testLocalChunk = 4000; testMaxCloudRetries = 0 }
        t.transcribe(id)
        val m = store.read(id)!!
        assertEquals(BatchStatus.Done, m.status)
        assertTrue(
            "native saw ${maxSamplesSeen.get()} samples; local ceiling is ${4000 / 2}",
            maxSamplesSeen.get() <= 4000 / 2,
        )
    }
}
