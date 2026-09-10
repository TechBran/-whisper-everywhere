package com.whispereverywhere.transcription.live

import com.whispereverywhere.transcription.cloud.FatalKind
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The one optional LIVE smoke for the Gemini protocol, run from the PC against the real service
 * through the REAL transport (OkHttp) — the whole seam end to end: upgrade header, setup,
 * setupComplete, a lazily-opened activity carrying ~3 s of jfk, activityEnd, one final, the ack.
 *
 * Guarded on the presence of the developer's key file, which is read at runtime and handed to
 * `connect()` ONLY. Nothing here prints, logs, or asserts on the key; no fixture contains it; the
 * test SKIPS (assumption) on any machine without the file. It is not a correctness pin — the
 * protocol's 45 JVM tests are — it is the proof that the recorded frames still match the wire.
 */
class GeminiLiveSmokeTest {

    private class Rec : RealtimeTransport.Listener {
        val finals = Collections.synchronizedList(mutableListOf<String>())
        val deltas = Collections.synchronizedList(mutableListOf<String>())
        val fatals = Collections.synchronizedList(mutableListOf<Pair<FatalKind, Int>>())
        val connected = CountDownLatch(1)
        val completed = CountDownLatch(1)
        override fun onConnected() { connected.countDown() }
        override fun onDelta(itemId: String, text: String) { deltas += text }
        override fun onCompleted(itemId: String, transcript: String) { finals += transcript; completed.countDown() }
        override fun onCommitted(itemId: String) {}
        override fun onTranscriptionFailed(itemId: String) {}
        override fun onErrorEvent(code: String?, messageLength: Int) {}
        override fun onDisconnected() {}
        override fun onFatal(kind: FatalKind, code: Int) { fatals += kind to code; completed.countDown() }
    }

    private fun locate(relative: String): File? {
        var dir: File? = File(System.getProperty("user.dir")!!).absoluteFile
        while (dir != null) {
            for (c in listOf(File(dir, relative), File(dir, "app/$relative"))) if (c.isFile) return c
            dir = dir.parentFile
        }
        return null
    }

    @Test fun setup_then_three_seconds_of_jfk_then_one_final() {
        val keyFile = File(KEY_PATH)
        assumeTrue("no Gemini key on this machine — live smoke skipped", keyFile.isFile)
        val wav = locate("src/androidTest/assets/jfk.wav")
        assumeTrue("jfk.wav not found", wav != null)
        val key = keyFile.readText().trim() // never printed, never logged, never asserted on
        assumeTrue("empty key file", key.isNotEmpty())

        val pcm = wav!!.readBytes().let { it.copyOfRange(44, it.size) } // 16 kHz mono PCM16, header skipped
        val threeSeconds = pcm.copyOfRange(0, minOf(pcm.size, 3 * 16_000 * 2))

        val rec = Rec()
        val scheduler = ExecutorReconnectScheduler()
        val transport = RealtimeTransport(
            factory = OkHttpWebSocketFactory(OkHttpClient()),
            scheduler = scheduler,
            listener = rec,
            protocol = GeminiRealtimeProtocol(),
        )
        try {
            transport.connect(key, null)
            assertTrue("101 + setup within 10 s", rec.connected.await(10, TimeUnit.SECONDS))
            // Real-time pace, 32 ms frames, as the engine's sender delivers them. The first frame
            // rides the pre-setup ring; the protocol opens the activity on it once setupComplete lands.
            val frame = 1_024
            var sentAny = false
            var off = 0
            while (off < threeSeconds.size) {
                val end = minOf(off + frame, threeSeconds.size)
                if (transport.sendAppend(threeSeconds.copyOfRange(off, end))) sentAny = true
                off = end
                Thread.sleep(32)
            }
            assertTrue("audio was accepted by the live socket", sentAny)
            assertTrue("the commit (activityEnd) was sent", transport.sendCommit())
            assertTrue("one final (or a fatal) within 10 s", rec.completed.await(10, TimeUnit.SECONDS))
            assertTrue("no fatal: ${rec.fatals}", rec.fatals.isEmpty())
            assertEquals("exactly one final for one activity", 1, rec.finals.size)
            val text = rec.finals.single()
            assertFalse("the final carries text", text.isBlank())
            assertTrue("jfk's opening words", text.contains("fellow", ignoreCase = true) || text.contains("Americans", ignoreCase = true))
            assertTrue("interims arrived before the final", rec.deltas.isNotEmpty())
        } finally {
            transport.close()
            scheduler.shutdown()
        }
    }

    private companion object {
        const val KEY_PATH = "C:/Users/bastr/.androidbuild/gemini.key"
    }
}
