package com.whispereverywhere.transcription

import com.whispereverywhere.util.RetryPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors

/** installedModelPath() the test can repoint mid-test — the on-disk model switches in production. */
class SwitchableModelPathProvider(var path: String?) : ModelPathProvider {
    override fun installedModelPath(): String? = path
}

class LocalWhisperEngineWarmPathTest {

    private fun fastRetry() = RetryPolicy(maxAttempts = 3, baseDelayMs = 0, maxDelayMs = 0, rng = { 0.0 })

    private fun engineWith(provider: ModelPathProvider, backend: WhisperBackend) =
        LocalWhisperEngine(
            modelPathProvider = provider,
            retry = fastRetry(),
            backend = backend,
            executor = SameThreadExecutorService(),
        )

    // ===== prewarmModelSwitch(): the re-prewarm a tier switch needs (Workstream E1) =====

    @Test
    fun prewarmModelSwitch_afterATierSwitch_releasesTheOldContextAndLoadsTheNew() {
        val provider = SwitchableModelPathProvider("/models/pro.bin")
        val backend = FakeWhisperBackend()
        val engine = engineWith(provider, backend)
        engine.prewarm()
        assertEquals(listOf("/models/pro.bin"), backend.loadCalls)

        provider.path = "/models/multi.bin"     // the user switched tiers
        engine.prewarmModelSwitch()

        assertEquals("the stale context must be freed exactly once", 1, backend.releaseCalls)
        assertEquals(listOf("/models/pro.bin", "/models/multi.bin"), backend.loadCalls)
    }

    @Test
    fun prewarmModelSwitch_whenTheLoadedModelIsCurrent_doesNothing() {
        // e.g. OnboardingSetupViewModel's self-heal rewrite of the SAME id: no release, no reload.
        val provider = SwitchableModelPathProvider("/models/pro.bin")
        val backend = FakeWhisperBackend()
        val engine = engineWith(provider, backend)
        engine.prewarm()
        engine.prewarmModelSwitch()

        assertEquals("no reload for the same model", 1, backend.loadCalls.size)
        assertEquals(0, backend.releaseCalls)
    }

    @Test
    fun prewarmModelSwitch_withAnEmptySlot_behavesLikePrewarm() {
        val provider = SwitchableModelPathProvider("/models/pro.bin")
        val backend = FakeWhisperBackend()
        val engine = engineWith(provider, backend)
        engine.prewarmModelSwitch()
        assertEquals(listOf("/models/pro.bin"), backend.loadCalls)
        assertEquals(0, backend.releaseCalls)
    }

    @Test
    fun prewarmModelSwitch_withNoInstalledModel_doesNothing() {
        // The delete-model writer pushes null: the stale context stays loaded (same as today) and
        // nothing new loads — connect() reports the no-model error if a session actually starts.
        val provider = SwitchableModelPathProvider("/models/pro.bin")
        val backend = FakeWhisperBackend()
        val engine = engineWith(provider, backend)
        engine.prewarm()
        provider.path = null
        engine.prewarmModelSwitch()
        assertEquals(1, backend.loadCalls.size)
        assertEquals(0, backend.releaseCalls)
    }

    @Test
    fun prewarmModelSwitch_onARealBackgroundExecutor_leavesTheNextConnectWarm() {
        // The point of the whole workstream: after a switch + re-prewarm, connect() takes its
        // fast path (no load inside CONNECTING). Real single-thread executor, per house rules.
        val provider = SwitchableModelPathProvider("/models/pro.bin")
        val backend = FakeWhisperBackend()
        val executor = Executors.newSingleThreadExecutor()
        val engine = LocalWhisperEngine(
            modelPathProvider = provider,
            retry = fastRetry(),
            backend = backend,
            executor = executor,
        )
        try {
            engine.prewarm()
            assertTrue(engine.awaitIdle(5_000L))
            provider.path = "/models/multi.bin"
            engine.prewarmModelSwitch()
            assertTrue(engine.awaitIdle(5_000L))
            assertEquals(listOf("/models/pro.bin", "/models/multi.bin"), backend.loadCalls)
            assertEquals(1, backend.releaseCalls)

            val listener = RecordingListener()
            engine.connect(language = "en", listener = listener)
            // The fast path signals onOpen on the control executor; poll with a deadline.
            val deadline = System.currentTimeMillis() + 5_000L
            while (!listener.opened && System.currentTimeMillis() < deadline) Thread.sleep(10)
            assertTrue("connect() must open without a reload", listener.opened)
            assertTrue(engine.awaitIdle(5_000L))
            assertEquals("connect() must NOT have loaded again", 2, backend.loadCalls.size)
        } finally {
            engine.shutdown()
            executor.shutdownNow()
        }
    }

    // ===== isWarm(): the CONNECTING-label flag (Workstream E3) =====

    @Test
    fun isWarm_isFalseBeforeAnyLoad() {
        val engine = engineWith(SwitchableModelPathProvider("/models/pro.bin"), FakeWhisperBackend())
        org.junit.Assert.assertFalse(engine.isWarm())
    }

    @Test
    fun isWarm_isTrueOnceTheInstalledModelIsLoaded() {
        val engine = engineWith(SwitchableModelPathProvider("/models/pro.bin"), FakeWhisperBackend())
        engine.prewarm()
        assertTrue(engine.isWarm())
    }

    @Test
    fun isWarm_isFalseAfterReleaseContext() {
        // onTrimMemory freed the context: the next connect() is cold and the label must say so.
        val engine = engineWith(SwitchableModelPathProvider("/models/pro.bin"), FakeWhisperBackend())
        engine.prewarm()
        engine.releaseContext()
        org.junit.Assert.assertFalse(engine.isWarm())
    }

    @Test
    fun isWarm_isFalseWhenTheInstalledModelChangedSinceTheLoad() {
        // The same condition connect() checks: a loaded-but-stale context is a COLD start.
        val provider = SwitchableModelPathProvider("/models/pro.bin")
        val engine = engineWith(provider, FakeWhisperBackend())
        engine.prewarm()
        provider.path = "/models/multi.bin"
        org.junit.Assert.assertFalse(engine.isWarm())
    }

    @Test
    fun isWarm_isFalseWithNoInstalledModel() {
        val engine = engineWith(SwitchableModelPathProvider(null), FakeWhisperBackend())
        org.junit.Assert.assertFalse(engine.isWarm())
    }
}
