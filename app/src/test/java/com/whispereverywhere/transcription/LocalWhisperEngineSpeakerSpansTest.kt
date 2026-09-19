package com.whispereverywhere.transcription

import com.whispereverywhere.transcription.speakers.SpeakerSpan
import com.whispereverywhere.transcription.speakers.SpeakerWindow
import com.whispereverywhere.util.RetryPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 4.10 Task 1, the seam half: the local engine reads the native segment geometry through
 * [WhisperBackend.lastGeometry] and hands the service `(windowIndex, text)` spans BESIDE the text
 * it already delivered — `SegmentOutcome.Text.text` is untouched, byte for byte.
 *
 * Since spike session 4 the engine also builds the chunk's FINGERPRINT WINDOWS here, once, and
 * carries them on the same outcome: the spans are cut against that list and the assigner
 * fingerprints it, so a second list computed anywhere else could not disagree with this one.
 *
 * WHY THE DEFAULT MATTERS AS MUCH AS THE WIRING: every backend without native geometry (the NPU
 * tier while it is live, the cloud engines, every fake in this suite) inherits
 * `lastGeometry(ctx) = null`, and the engine must then produce the pre-4.10 outcome exactly —
 * `SegmentOutcome.Text("hello world")` with both new fields null. Dozens of assertions in this
 * package compare outcomes by VALUE, so an engine that attached empty lists instead of nulls
 * would turn them all red, and one that attached a single all-text span would turn them red in a
 * way that reads like a text regression.
 */
class LocalWhisperEngineSpeakerSpansTest {

    private fun fastRetry() = RetryPolicy(maxAttempts = 1, baseDelayMs = 0, maxDelayMs = 0, rng = { 0.0 })

    private val pcm = byteArrayOf(0x10, 0x00, 0x20, 0x00)

    /**
     * A backend that returns [text] and publishes [geometry] for it, the way
     * `WhisperNativeBackend` publishes its ctx-tagged snapshot after a transcribe.
     */
    private class GeometryBackend(
        private val text: String,
        private val geometry: SegmentGeometry?,
    ) : WhisperBackend {
        var geometryQueries = 0
        override fun load(modelPath: String): Long = 42L
        override fun transcribe(ctx: Long, samples: FloatArray, lang: String?, useVad: Boolean): String = text
        override fun lastGeometry(ctx: Long): SegmentGeometry? {
            geometryQueries++
            return geometry
        }
        override fun release(ctx: Long) = Unit
    }

    private fun resolveOne(backend: WhisperBackend): SegmentOutcome {
        val engine = LocalWhisperEngine(
            modelPathProvider = FakeModelPathProvider("/models/small-q8.bin"),
            retry = fastRetry(),
            backend = backend,
            executor = SameThreadExecutorService(),
        )
        val listener = RecordingListener()
        engine.connect(language = "en", listener = listener)
        engine.sendAudio(pcm)
        engine.commit()
        return listener.resolved.single().second
    }

    @Test
    fun theGeometryBecomesSpansOnTheOutcome_withTheTextUnchanged() {
        // Two VAD segments; one decoded segment inside each. The byte ranges are into the UTF-8
        // of " Hello world." — 6 bytes then 7.
        val backend = GeometryBackend(
            text = " Hello world.",
            geometry = SegmentGeometry(
                vadSegments = intArrayOf(0, 32_000, 0, 32_000, 40_000, 72_000, 33_600, 65_600),
                whisperSegments = intArrayOf(0, 200, 0, 6, 210, 410, 6, 13),
            ),
        )

        val outcome = resolveOne(backend) as SegmentOutcome.Text

        assertEquals("the committed text is what it always was", "Hello world.", outcome.text)
        assertEquals(
            listOf(SpeakerSpan(windowIndex = 0, text = "Hello"), SpeakerSpan(1, "world.")),
            outcome.spans,
        )
        assertEquals(
            "two short segments, so one window each and the window index IS the vad index",
            listOf(SpeakerWindow(0, 0, 32_000), SpeakerWindow(1, 40_000, 72_000)),
            outcome.windows,
        )
        assertEquals("read once per segment, after the transcribe", 1, backend.geometryQueries)
    }

    @Test
    fun aBackendWithoutGeometryProducesThePre410OutcomeExactly() {
        val outcome = resolveOne(FakeWhisperBackend(text = "hello world"))
        assertEquals(SegmentOutcome.Text("hello world"), outcome)
        assertNull((outcome as SegmentOutcome.Text).spans)
        assertNull(outcome.windows)
    }

    @Test
    fun anEmptyVadArrayAttachesNothing_becauseNoVadRanIsNotOneSpeaker() {
        // whisper_jni publishes an EMPTY vad array when the VAD never ran (no model path, an init
        // failure) — which the spec forbids reading as "one speaker". With no timeline there is
        // nothing to attribute text to, so the outcome must be indistinguishable from a backend
        // that has no geometry at all.
        val backend = GeometryBackend(
            text = " no vad here",
            geometry = SegmentGeometry(
                vadSegments = IntArray(0),
                whisperSegments = intArrayOf(0, 200, 0, 12),
            ),
        )

        val outcome = resolveOne(backend) as SegmentOutcome.Text
        assertEquals("no vad here", outcome.text)
        assertNull(outcome.spans)
        assertNull(outcome.windows)
    }

    @Test
    fun aLongSegmentArrivesAsSEVERALWindowsAndItsSpansDoNotMerge() {
        // Spike session 4 end to end through the engine seam. ONE twelve-second VAD segment with
        // two sentences in it: two windows, two spans, and the second span's index is 1 — which
        // under the pre-session-4 rule would have been 0 for both, one merged span, one label.
        val backend = GeometryBackend(
            text = " Hello there. Hi, how are you?",
            geometry = SegmentGeometry(
                vadSegments = intArrayOf(0, 192_000, 0, 192_000),
                whisperSegments = intArrayOf(0, 550, 0, 13, 560, 1_200, 13, 30),
            ),
        )

        val outcome = resolveOne(backend) as SegmentOutcome.Text

        assertEquals("Hello there. Hi, how are you?", outcome.text)
        assertEquals(
            listOf(SpeakerWindow(0, 0, 89_600), SpeakerWindow(0, 89_600, 192_000)),
            outcome.windows,
        )
        assertEquals(
            listOf(SpeakerSpan(0, "Hello there."), SpeakerSpan(1, "Hi, how are you?")),
            outcome.spans,
        )
    }

    @Test
    fun aBlankTranscribeStaysEmptyExpected_withNoSpansInvented() {
        // The blank branch is reached BEFORE any span work, and the geometry of a chunk whose text
        // cleaned away describes nothing the user will ever see.
        val backend = GeometryBackend(
            text = " [BLANK_AUDIO] ",
            geometry = SegmentGeometry(
                vadSegments = intArrayOf(0, 32_000, 0, 32_000),
                whisperSegments = intArrayOf(0, 200, 0, 15),
            ),
        )

        assertEquals(SegmentOutcome.EmptyExpected, resolveOne(backend))
    }
}
