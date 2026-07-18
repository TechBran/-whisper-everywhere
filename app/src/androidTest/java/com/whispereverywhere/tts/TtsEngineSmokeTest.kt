package com.whispereverywhere.tts

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * On-device smoke for the read-aloud engine: needs the voice installed in app storage
 * (files/tts/kokoro-v1_1 + .installed marker — seeded via run-as during development, or by a
 * real TtsModelManager.download()). Speaks a short sentence audibly and asserts completion.
 */
@RunWith(AndroidJUnit4::class)
class TtsEngineSmokeTest {

    @Test
    fun speaks_short_text_to_completion() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = TtsModelManager(ctx)
        assumeTrue("Voice not installed; skipping", manager.isInstalled())

        val engine = TtsEngine(ctx, manager)
        try {
            val done = CountDownLatch(1)
            val accepted = engine.speak("Read aloud is now part of Whisper Everywhere.") {
                done.countDown()
            }
            assertTrue("speak() rejected despite installed voice", accepted)
            // Load (~2 s) + synth + playback of a ~3 s sentence — 60 s is generous.
            assertTrue("speech did not complete in time", done.await(60, TimeUnit.SECONDS))
            assertTrue("engine should be loaded after speaking", engine.isReady())
        } finally {
            engine.shutdown()
        }
    }

    @Test
    fun stop_interrupts_speech_quickly() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = TtsModelManager(ctx)
        assumeTrue("Voice not installed; skipping", manager.isInstalled())

        val engine = TtsEngine(ctx, manager)
        try {
            val done = CountDownLatch(1)
            engine.speak(
                "This is a deliberately long passage that would take many seconds to read " +
                    "in full, sentence after sentence, so that stopping midway is observable. " +
                    "If cancellation works, completion arrives long before the text ends. " +
                    "The quick brown fox jumps over the lazy dog again and again and again.",
            ) { done.countDown() }
            Thread.sleep(4000) // let load + first sentence begin
            val t0 = System.currentTimeMillis()
            engine.stop()
            assertTrue("stop() did not end speech promptly", done.await(5, TimeUnit.SECONDS))
            val stopMs = System.currentTimeMillis() - t0
            android.util.Log.i("WE-TTS", "stop latency=${stopMs}ms")
        } finally {
            engine.shutdown()
        }
    }
}
