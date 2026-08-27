package com.whispereverywhere.transcription

import com.whispereverywhere.transcription.cloud.CloudTranscriptionEngine
import com.whispereverywhere.transcription.cloud.FallbackTranscriptionEngine
import com.whispereverywhere.transcription.live.LiveTranscriptionEngine
import com.whispereverywhere.util.RetryPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 3.7 Workstream D: when the 15 s wall cap fires with the gate open, the commit may keep the
 * trailing audio since the endpointer's remembered micro-pause, so the boundary lands in a pause
 * instead of mid-word (`no_context = true` makes a mid-word cap boundary unrepairable).
 *
 * REAL background executor — LocalWhisperEngine's own single-thread default. The split happens
 * under bufferLock while the capture thread is still calling sendAudio, so a same-thread stub
 * would prove nothing about the contract this test exists to pin.
 */
class LocalWhisperEngineCapSplitTest {

    /**
     * Records the SAMPLE COUNT of every segment the engine hands the backend.
     *
     * [LocalWhisperEngine.runSegment] calls `transcribeStreaming` (3.6.0 partial streaming), never
     * `transcribe`, so that is where the recording lives. Both are implemented and NEITHER
     * delegates to the other: whichever one a future refactor routes through records exactly once,
     * and a route through a third method would empty [sampleCounts] and fail loudly rather than
     * silently under-count. The streaming callback is deliberately never invoked — zero deltas is
     * an explicit part of [WhisperBackend.transcribeStreaming]'s contract, and this test is about
     * the SPLIT, not the preview.
     */
    private class SizeRecordingBackend(private val done: CountDownLatch) : WhisperBackend {
        val sampleCounts = CopyOnWriteArrayList<Int>()

        private fun record(samples: FloatArray): String {
            sampleCounts.add(samples.size)
            done.countDown()
            return "seg"
        }

        override fun load(modelPath: String): Long = 42L

        override fun transcribe(ctx: Long, samples: FloatArray, lang: String?, useVad: Boolean): String =
            record(samples)

        override fun transcribeStreaming(
            ctx: Long,
            samples: FloatArray,
            lang: String?,
            useVad: Boolean,
            onNewSegment: (String) -> Unit,
        ): String = record(samples)

        // Inert stubs. Both already default to null on the interface; spelled out so this fake
        // keeps saying "no detection, no counters" even if a default ever changes underneath it.
        override fun detectedLanguage(ctx: Long): String? = null
        override fun lastSegmentStats(ctx: Long): NativeSegmentStats? = null

        override fun release(ctx: Long) = Unit
    }

    /**
     * Every engine here runs on the REAL default executor, so every engine here is also SHUT DOWN,
     * not merely closed. `close()` detaches the listener and clears the buffer; it does not stop a
     * thread. This is the only LocalWhisperEngine test file that does not inject
     * `SameThreadExecutorService`, so it is the only one that would otherwise leave live
     * non-daemon threads behind for the rest of the suite's JVM.
     */
    private fun engine(backend: WhisperBackend) = LocalWhisperEngine(
        modelPathProvider = FakeModelPathProvider("/models/pro.bin"),
        retry = RetryPolicy(maxAttempts = 1, baseDelayMs = 0, maxDelayMs = 0, rng = { 0.0 }),
        backend = backend,
        // executor deliberately left at its default: a REAL single-thread background executor.
    )

    /** 32 bytes of PCM16 mono @16 kHz is exactly 1 ms. */
    private fun pcm(ms: Int) = ByteArray(ms * 32) { 0x11 }

    @Test
    fun retainZeroIsExactlyCommit() {
        val doneA = CountDownLatch(1)
        val doneB = CountDownLatch(1)
        val a = SizeRecordingBackend(doneA)
        val b = SizeRecordingBackend(doneB)
        val ea = engine(a); val eb = engine(b)
        ea.connect(null, RecordingListener()); eb.connect(null, RecordingListener())

        ea.sendAudio(pcm(500)); eb.sendAudio(pcm(500))
        val seqA = ea.commit()
        val seqB = eb.commitRetainingTailMs(0L)

        assertTrue(doneA.await(10, TimeUnit.SECONDS) && doneB.await(10, TimeUnit.SECONDS))
        assertEquals(seqA, seqB)
        assertEquals(a.sampleCounts.toList(), b.sampleCounts.toList())
        assertEquals(listOf(8_000), b.sampleCounts.toList())     // 500 ms * 16 samples/ms
        ea.close(); eb.close(); ea.shutdown(); eb.shutdown()
    }

    /**
     * The interface contract is `retainMs <= 0`, and its two halves are pinned by DIFFERENT
     * tests because they fail differently — a battery finding, not a hunch.
     *
     * At exactly 0 the guard is DOCUMENTARY: delete it and the body below still computes a full
     * commit (`retain` 0, `cut` the whole snapshot, a zero-length retained write), so
     * [retainZeroIsExactlyCommit] cannot see its absence. A NEGATIVE retain is where the guard
     * earns its keep: `cut` becomes `size - (-n)`, which runs PAST the end of the snapshot, and
     * the call throws instead of committing.
     *
     * Not hypothetical. D3's M5b showed that deleting the guard in `CommitCadencePolicy`
     * `.capCutRetainMs` makes a future cut point produce a NEGATIVE retain — the exact value that
     * would arrive here. This is the second lock on that seam, and the one that fails loudly.
     */
    @Test
    fun aNegativeRetainIsAlsoExactlyCommit() {
        val done = CountDownLatch(1)
        val backend = SizeRecordingBackend(done)
        val e = engine(backend)
        e.connect(null, RecordingListener())

        e.sendAudio(pcm(500))
        assertEquals(0L, e.commitRetainingTailMs(-500L))
        assertTrue(done.await(10, TimeUnit.SECONDS))
        assertEquals(listOf(8_000), backend.sampleCounts.toList())
        // The whole buffer went, exactly as commit() would have sent it: nothing was retained.
        assertEquals(-1L, e.commit())
        e.close(); e.shutdown()
    }

    @Test
    fun theRetainedTailStaysBufferedAndRidesTheNextSegment() {
        val done = CountDownLatch(2)
        val backend = SizeRecordingBackend(done)
        val e = engine(backend)
        e.connect(null, RecordingListener())

        e.sendAudio(pcm(5_000))
        assertEquals(0L, e.commitRetainingTailMs(800L))          // cut at the micro-pause
        e.sendAudio(pcm(200))
        assertEquals(1L, e.commit())

        assertTrue(done.await(10, TimeUnit.SECONDS))
        // 4 200 ms committed, then the retained 800 ms + the new 200 ms.
        assertEquals(listOf(67_200, 16_000), backend.sampleCounts.toList())
        e.close(); e.shutdown()
    }

    @Test
    fun aRetainLongerThanTheBufferCommitsEverythingRatherThanDeferringIt() {
        val done = CountDownLatch(1)
        val backend = SizeRecordingBackend(done)
        val e = engine(backend)
        e.connect(null, RecordingListener())

        e.sendAudio(pcm(100))
        assertEquals(0L, e.commitRetainingTailMs(3_000L))
        assertTrue(done.await(10, TimeUnit.SECONDS))
        assertEquals(listOf(1_600), backend.sampleCounts.toList())
        // and the buffer really is empty: a cap that already fired never defers its whole window.
        assertEquals(-1L, e.commit())
        e.close(); e.shutdown()
    }

    @Test
    fun anEmptyBufferStillReturnsNoSegment() {
        val backend = SizeRecordingBackend(CountDownLatch(1))
        val e = engine(backend)
        e.connect(null, RecordingListener())
        assertEquals(-1L, e.commitRetainingTailMs(800L))
        assertEquals(0, backend.sampleCounts.size)
        e.close(); e.shutdown()
    }

    @Test
    fun everyOtherEngineKeepsPlainCommitBehaviour() {
        // The default on the interface is a plain commit(), so cloud / live / fallback engines are
        // byte-unchanged: retaining PCM behind a wrapper's back would desynchronise its mirror.
        val calls = mutableListOf<String>()
        val plain = object : TranscriptionEngine {
            override fun connect(language: String?, listener: TranscriptionEngine.Listener) = Unit
            override fun sendAudio(pcm: ByteArray) = Unit
            override fun commit(): Long { calls += "commit"; return 7L }
            override fun close() = Unit
        }
        assertEquals(7L, plain.commitRetainingTailMs(1_234L))
        assertEquals(listOf("commit"), calls)
    }

    // ------------------------------------------------------------------ the no-override census

    /**
     * The shipped engines must INHERIT the interface default, and the test above only proves that
     * an ad-hoc anonymous implementation does. This is the census over the real ones.
     *
     * WHY `declaredMethods` alone cannot answer it. This project compiles with Kotlin 2.0.21 at
     * the default `-Xjvm-default` mode, so an interface member with a body is emitted as a static
     * on `TranscriptionEngine$DefaultImpls` AND as a delegating BRIDGE in every implementing
     * class. Verified on the shipped bytecode before this test was written:
     * `CloudTranscriptionEngine` declares `prewarm()` and `releaseContext()` — both `ACC_PUBLIC`,
     * neither `ACC_SYNTHETIC`, each a one-line `invokestatic DefaultImpls.…` — while its Kotlin
     * source overrides neither. A `declaredMethods.none { it.name == … }` census would therefore
     * be VACUOUSLY FALSE for every class and could never fail.
     *
     * THE DISCRIMINATOR IS `@kotlin.Metadata.d2`, the string table Kotlin's own reflection reads.
     * It lists what the KOTLIN SOURCE declares, and the compiler's bridge is not a Kotlin
     * declaration, so it is absent from it. Same bytecode, same two classes:
     * `LocalWhisperEngine`'s d2 contains "prewarm" (it really overrides it),
     * `CloudTranscriptionEngine`'s does not (it only carries the bridge).
     * The annotation is RUNTIME-retained, so this needs no kotlin-reflect dependency.
     */
    @Test
    fun noWrapperEngineOverridesTheRetainingCommit() {
        val engines = listOf(
            FallbackTranscriptionEngine::class.java,
            CloudTranscriptionEngine::class.java,
            LiveTranscriptionEngine::class.java,
        )

        // THE CENSUS. Assertions first, mechanism tripwire last, so a toolchain change can never
        // stop the contract itself from being checked.
        for (clazz in engines) {
            assertFalse(
                "${clazz.simpleName} now declares commitRetainingTailMs. The interface default is a " +
                    "plain commit() ON PURPOSE — read TranscriptionEngine.commitRetainingTailMs's " +
                    "KDoc before changing this line. A cloud commit is an HTTP POST, and " +
                    "FallbackTranscriptionEngine mirrors PCM per COMMITTED seq, so holding bytes " +
                    "back behind either one desynchronises a mirror instead of improving a " +
                    "boundary. Nothing in the language enforces that; this census is the enforcement.",
                kotlinDeclares(clazz, "commitRetainingTailMs"),
            )
        }

        // CONTROL 1: the discriminator can see a REAL override of this exact member. Without this
        // the census could pass because the d2 lookup is broken rather than because nothing
        // overrides.
        assertTrue(
            "the d2 discriminator stopped seeing LocalWhisperEngine's real override — the census " +
                "above is worthless until this is fixed",
            kotlinDeclares(LocalWhisperEngine::class.java, "commitRetainingTailMs"),
        )

        // CONTROL 2: and it sees a real override ON THE CENSUSED CLASSES THEMSELVES, so a false
        // negative above cannot be the helper failing on those particular classes. All three
        // override commit().
        for (clazz in engines) {
            assertTrue(
                "${clazz.simpleName} should declare commit() — the d2 discriminator is broken",
                kotlinDeclares(clazz, "commit"),
            )
        }

        // MECHANISM TRIPWIRE, and NOT a contract assertion. It records the bytecode fact the KDoc
        // above rests on. If this fires, the census assertions have already passed: the toolchain's
        // -Xjvm-default mode changed, the DefaultImpls bridge is gone, and this test's mechanism
        // note (not the product) needs re-deriving.
        assertTrue(
            "no DefaultImpls bridge for prewarm() on CloudTranscriptionEngine — Kotlin's " +
                "-Xjvm-default mode changed. The census above still passed; only this test's " +
                "explanation of WHY declaredMethods cannot be the discriminator is now stale.",
            CloudTranscriptionEngine::class.java.declaredMethods.any { it.name == "prewarm" },
        )
    }

    /**
     * True when [clazz]'s KOTLIN SOURCE declares a member called [name]. See the census KDoc.
     *
     * `data2` is the Kotlin property; `@get:JvmName("d2")` is why the bytecode and every write-up
     * of this annotation call it `d2`.
     */
    private fun kotlinDeclares(clazz: Class<*>, name: String): Boolean {
        val metadata = clazz.getAnnotation(Metadata::class.java)
        assertNotNull("${clazz.name} carries no @kotlin.Metadata — not a Kotlin class?", metadata)
        return metadata!!.data2.any { it == name }
    }

    // ------------------------------------------------------------- the frame-alignment guarantee

    /**
     * The retained tail is aligned DOWN to a whole PCM16 frame, and an ODD buffer is the only way
     * to reach that alignment at all: `retainMs * 32` is always even, so the pre-alignment retain
     * can only be odd when the coerce clamps it to an odd BUFFER SIZE. [sendAudio] takes raw
     * bytes, so that state is constructible even though production capture never produces it.
     *
     * 33 bytes is 16 whole frames plus one orphan half-sample. With the alignment the split keeps
     * 32 bytes (even) and flushes the orphan byte into the committed segment — which is why the
     * first segment is 0 SAMPLES, not a mid-sample fragment. Without it the retain clamps to the
     * full 33, `cut` lands at 0, and the whole buffer degrades into one 16-sample commit with
     * nothing retained.
     */
    @Test
    fun theRetainedTailIsAlignedDownToAWholeFrameOnAnOddBuffer() {
        val done = CountDownLatch(2)
        val backend = SizeRecordingBackend(done)
        val e = engine(backend)
        e.connect(null, RecordingListener())

        e.sendAudio(ByteArray(33) { 0x11 })        // 16 frames + one orphan byte
        assertEquals(0L, e.commitRetainingTailMs(2L))   // 2 ms * 32 = 64 B > 33 B: the coerce bites
        e.sendAudio(pcm(1))                        // 32 B on top of whatever was retained
        assertEquals(1L, e.commit())

        assertTrue(done.await(10, TimeUnit.SECONDS))
        assertEquals(
            "the orphan byte rides the committed segment (0 samples); the retained tail is the " +
                "32-byte remainder, so the next segment is 32 retained + 32 new = 32 samples",
            listOf(0, 32),
            backend.sampleCounts.toList(),
        )
        val retainedBytes = backend.sampleCounts[1] * 2 - 32
        assertEquals("the retained tail must be a whole number of PCM16 frames", 0, retainedBytes % 2)
        assertEquals(32, retainedBytes)
        e.close(); e.shutdown()
    }

    // ----------------------------------------------------------------- the split is atomic

    /**
     * The snapshot, the seq and the rewrite are computed INSIDE bufferLock, for the same reason
     * [LocalWhisperEngine.commit] allocates its seq there: the capture thread is still calling
     * sendAudio. A snapshot taken outside the lock would let a chunk land between the read and the
     * `buffer.reset()` that follows it, and those bytes would be silently destroyed.
     *
     * The invariant is exact and holds with no timing assumption at all: every byte handed to
     * sendAudio is either committed in some segment or still buffered, and the final commit()
     * flushes what is buffered — so the samples the backend saw must account for EVERY byte sent.
     * Correct code satisfies that deterministically; only the mutant can lose a byte, and it can
     * only lose one when the storm actually interleaves. Modelled on C9's capture-thread storm
     * (SileroEndpointerConcurrencyTest.main_thread_resets_never_corrupt_the_capture_thread_pump).
     */
    @Test
    fun theSplitLosesNoAudioUnderAConcurrentCaptureStorm() {
        val backend = SizeRecordingBackend(CountDownLatch(1))
        val e = engine(backend)
        e.connect(null, RecordingListener())

        val stormChunks = AtomicLong(0)
        val stormRunning = CountDownLatch(1)
        val stop = AtomicBoolean(false)
        val storm = Thread {
            val chunk = ByteArray(STORM_CHUNK_BYTES) { 0x11 }
            var i = 0
            while (!stop.get() && i < STORM_MAX_CHUNKS) {
                e.sendAudio(chunk)
                stormChunks.incrementAndGet()
                stormRunning.countDown()
                i++
            }
        }
        storm.start()
        // The storm must be ON THE WIRE before the first split, or main races to the end alone and
        // the test silently un-storms — C9's own D2 finding, where Main finished all 5 000 resets
        // before the capture thread was ever scheduled.
        assertTrue("the storm thread never started", stormRunning.await(10, TimeUnit.SECONDS))

        var mainBytes = 0L
        repeat(SPLITS) {
            // A big main-thread refill each round keeps the window the mutant would open WIDE:
            // the snapshot copy it hoists out of the lock is ~38 kB, not a few bytes.
            e.sendAudio(pcm(1_000))
            mainBytes += 1_000L * STORM_CHUNK_BYTES
            e.commitRetainingTailMs(200L)
        }
        stop.set(true)
        storm.join(10_000)
        assertFalse("the storm thread did not finish", storm.isAlive)
        e.commit()                                   // flush the retained tail
        assertTrue("segments did not drain", e.awaitIdle(10_000))

        // The storm ran ALONGSIDE the splits, not before or after them. Without this the test
        // could pass on a run where the capture thread contributed a handful of chunks and never
        // came near the window — green, and proving nothing.
        assertTrue(
            "the storm sent only ${stormChunks.get()} chunks across $SPLITS splits — it stopped " +
                "storming, so this test is no longer exercising the interleaving it exists for",
            stormChunks.get() >= STORM_MIN_CHUNKS,
        )
        assertEquals(
            "audio was lost: the split must snapshot, allocate the seq and rewrite the buffer " +
                "under ONE bufferLock hold",
            mainBytes + stormChunks.get() * STORM_CHUNK_BYTES,
            backend.sampleCounts.sumOf { it.toLong() } * 2,
        )
        e.close(); e.shutdown()
    }

    private companion object {
        /** One 1 ms capture chunk — the smallest write that is still a whole PCM16 frame. */
        const val STORM_CHUNK_BYTES = 32

        /**
         * Hard bound on the storm so a scheduling anomaly cannot hang the suite; the stop flag
         * normally ends it far sooner. 200 000 * 32 B is well clear of anything the splits drain.
         */
        const val STORM_MAX_CHUNKS = 200_000

        /**
         * Floor for "it really stormed". A chunk is a lock acquire plus a 32-byte write (~100 ns),
         * and the 50 splits take milliseconds, so a healthy run clears this by orders of magnitude
         * — it is a did-not-run tripwire, never a throughput assertion.
         */
        const val STORM_MIN_CHUNKS = 1_000L

        /** Splits attempted mid-storm — each one is an independent chance to hit the window. */
        const val SPLITS = 50
    }
}
