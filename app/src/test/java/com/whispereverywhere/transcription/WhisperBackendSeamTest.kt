package com.whispereverywhere.transcription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the WhisperBackend interface DEFAULTS every existing fake and any future backend
 * inherit. These defaults are what keep the 3.6.0 additions (language pinning, partial
 * streaming) opt-in: a backend that doesn't implement them behaves byte-for-byte like 3.5.0.
 */
class WhisperBackendSeamTest {

    /** Minimal backend: overrides ONLY the 3.5.0 surface. */
    internal class MinimalBackend : WhisperBackend {
        var loadedPath: String? = null
        var loadCalls = 0
        override fun load(modelPath: String): Long {
            loadedPath = modelPath
            loadCalls++
            return 1L
        }
        override fun transcribe(ctx: Long, samples: FloatArray, lang: String?, useVad: Boolean): String = "text"
        override fun release(ctx: Long) = Unit
    }

    /** A two-artifact backend: overrides the 4.0 two-arg form as well as the one-arg one. */
    internal class PairedBackend : WhisperBackend {
        var seen: Pair<String, String?>? = null
        override fun load(modelPath: String): Long = load(modelPath, "/derived/companion.bin")
        override fun load(modelPath: String, companionPath: String?): Long {
            seen = modelPath to companionPath
            return 7L
        }
        override fun transcribe(ctx: Long, samples: FloatArray, lang: String?, useVad: Boolean): String = "text"
        override fun release(ctx: Long) = Unit
    }

    @Test
    fun detectedLanguage_defaultsToNull_soNothingPinsAccidentally() {
        assertNull(MinimalBackend().detectedLanguage(1L))
    }

    @Test
    fun transcribeStreaming_defaultsToPlainTranscribe_withNoDeltas() {
        var callbacks = 0
        val text = MinimalBackend().transcribeStreaming(1L, FloatArray(4), lang = null) { callbacks++ }
        assertEquals("text", text)
        assertEquals(0, callbacks)   // the default streams nothing: byte-for-byte 3.5.0 behavior
    }

    @Test
    fun lastSegmentStats_defaultsToNull_soTheTimingLineDegradesInsteadOfLying() {
        // A backend with no native counters must report NOTHING, not zeros: `ctxFrames=0` is a
        // real and meaningful reading (whisper_full never ran), so a fake must not forge it.
        assertNull(MinimalBackend().lastSegmentStats(1L))
    }

    /**
     * The 4.0 two-arg [WhisperBackend.load] default DELEGATES to the one-arg form, with the
     * MODEL path — and the companion is dropped, because a one-file backend has no use for it.
     *
     * This is the member the whole npu seam is organised around, and it was the one new default
     * with nothing behind it. Two mutations it kills, both of which compiled green against the
     * rest of the suite:
     *  - `= 0L` — every backend that has not opted in reports "load failed" the moment any caller
     *    routes through the two-arg form. Silent: the caller sees a failed load, not a wrong one;
     *  - `= load(companionPath ?: modelPath)` — a plausible "fix" for a future reader who thinks
     *    the companion should win. It routes every one-file tier through the WRONG path, and on
     *    the npu tier it would hand whisper.cpp the decoder context binary.
     */
    @Test
    fun loadTwoArg_defaultsToTheOneArgForm_withTheModelPathNotTheCompanion() {
        val backend = MinimalBackend()
        assertEquals(
            "the default returns whatever the one-arg override returns",
            1L,
            backend.load("/models/ggml-small-q5_1.bin", "/models/decoder_qairt_context.bin"),
        )
        assertEquals(
            "the one-arg override must actually be reached — the default is a DELEGATION, not a " +
                "stub, which is exactly what `= 0L` would satisfy while reporting a failed load",
            1,
            backend.loadCalls,
        )
        assertEquals(
            "and it must be handed the MODEL path. The companion is dropped, not preferred: a " +
                "one-file backend handed the paired artifact would load the wrong file entirely.",
            "/models/ggml-small-q5_1.bin",
            backend.loadedPath,
        )
        val nullCompanion = MinimalBackend()
        assertEquals(1L, nullCompanion.load("/models/x.bin", null))
        assertEquals("a null companion changes nothing", "/models/x.bin", nullCompanion.loadedPath)
    }

    /**
     * An implementor that DOES override the two-arg form is reached by the two-arg call, and its
     * override shadows the default rather than sitting beside it.
     *
     * `NpuWhisperBackend` is that implementor. If the default body ever won here, the tier would
     * load its encoder through whisper.cpp's one-path loader and never see its decoder at all.
     */
    @Test
    fun loadTwoArg_overrideShadowsTheDefault_andReceivesBothPaths() {
        val paired = PairedBackend()
        assertEquals(
            "the override's return value is what the caller gets — not the default's delegation",
            7L,
            paired.load("/models/encoder_qairt_context.bin", "/models/decoder_qairt_context.bin"),
        )
        assertEquals(
            "/models/encoder_qairt_context.bin" to "/models/decoder_qairt_context.bin",
            paired.seen,
        )
        // …and the one-arg form still works, because a two-artifact backend resolves its own
        // companion. That is the arrangement that keeps every existing caller untouched.
        val viaOneArg = PairedBackend()
        assertEquals(7L, viaOneArg.load("/models/encoder_qairt_context.bin"))
        assertEquals(
            "/models/encoder_qairt_context.bin" to "/derived/companion.bin",
            viaOneArg.seen,
        )
    }

    /**
     * Both 4.0 [ModelPathProvider] members default to NULL, and the whole "the npu tier refuses
     * cleanly until Q8 wires it" story rests on exactly that.
     *
     * A default of `""` rather than `null` would compile, and `NpuWhisperBackend.load` would take
     * its `mel-donor` branch on an elvis that never fires — handing `initMelOnly("")` a path that
     * cannot exist and turning the cheapest possible refusal into a native failure one stage later.
     */
    @Test
    fun modelPathProvider_bothTwoArtifactMembers_defaultToNull() {
        val oneFileTier = object : ModelPathProvider {
            override fun installedModelPath(): String? = "/models/ggml-small-q5_1.bin"
        }
        assertEquals("/models/ggml-small-q5_1.bin", oneFileTier.installedModelPath())
        assertNull(
            "companionModelPath defaults to null: a one-file tier has no paired artifact, and a " +
                "two-artifact backend must read that null as \"this tier cannot come up\" rather " +
                "than as a missing optional extra",
            oneFileTier.companionModelPath(),
        )
        assertNull(
            "cpuTierModelPath defaults to null, which is what makes the npu tier decline at " +
                "stage=mel-donor before any of the 358 MB of NPU assets is touched",
            oneFileTier.cpuTierModelPath(),
        )
    }

    @Test
    fun nativeSegmentStats_carriesTheThreeNativeCounters() {
        val s = NativeSegmentStats(ctxFrames = 512, vadInSamples = 48_000, vadOutSamples = 32_000)
        assertEquals(512, s.ctxFrames)
        assertEquals(48_000, s.vadInSamples)
        assertEquals(32_000, s.vadOutSamples)
    }
}
