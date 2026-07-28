package com.whispereverywhere.tts

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Regression guard for the stranded-playback-thread bug: no thread named "tts-playback" may
 * outlive an utterance.
 *
 * Coverage is PARTIAL, not "any exit path": the two tests below drive normal completion and
 * stop() mid-utterance. They do NOT drive the exit path the underlying fix actually addressed —
 * `generateWithCallback()` throwing (OOM on sherpa's whole-utterance float[], or a native
 * error), guarded by the `finally { doneFlag.set(true) }` in `TtsEngine.speak()`. Forcing that
 * throw requires making sherpa's JNI layer fail on demand, which is out of scope for an
 * instrumented test running against the real native library. A leaked thread also leaks its
 * AudioTrack.
 */
class TtsPlaybackThreadTest {

    private fun livePlaybackThreads(): List<Thread> =
        Thread.getAllStackTraces().keys.filter { it.name == "tts-playback" && it.isAlive }

    @Test fun playback_thread_does_not_outlive_a_normal_utterance() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val mgr = TtsModelManager(ctx)
        assumeTrue("voice model not installed on this device", mgr.installedDir() != null)

        val engine = TtsEngine(ctx, mgr)
        val done = CountDownLatch(1)
        assertTrue(engine.speak("Short test sentence.") { done.countDown() })
        assertTrue("speak did not finish in 60s", done.await(60, TimeUnit.SECONDS))

        // onDone fires on the main thread from the finally block, which has already joined.
        Thread.sleep(500)
        val leaked = livePlaybackThreads()
        assertTrue("leaked playback threads: ${leaked.map { it.name }}", leaked.isEmpty())
        engine.shutdown()
    }

    @Test fun playback_thread_terminates_after_stop_mid_utterance() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val mgr = TtsModelManager(ctx)
        assumeTrue("voice model not installed on this device", mgr.installedDir() != null)

        val engine = TtsEngine(ctx, mgr)
        val done = CountDownLatch(1)
        engine.speak(
            "One. Two. Three. Four. Five. Six. Seven. Eight. Nine. Ten.",
        ) { done.countDown() }
        Thread.sleep(1500)
        engine.stop()
        assertTrue("stop did not settle in 30s", done.await(30, TimeUnit.SECONDS))

        Thread.sleep(500)
        val leaked = livePlaybackThreads()
        assertTrue("leaked playback threads after stop: ${leaked.map { it.name }}", leaked.isEmpty())
        engine.shutdown()
    }
}
