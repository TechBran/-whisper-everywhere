package com.whispereverywhere.transcription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    /**
     * 4.1 L7's member: default FALSE, so every backend that has not opted in keeps the 3.7
     * session latch byte-for-byte. `true` is a per-backend MEASUREMENT — "my detect pass is too
     * cheap to amortise" — and only the NPU tier has made it; on whisper.cpp the detect pass is
     * roughly half of multi's steady-state cost, which is the entire reason the latch exists.
     */
    @Test
    fun detectsPerUtterance_defaultsToFalse_soTheCpuLatchStaysByInheritance() {
        assertFalse(MinimalBackend().detectsPerUtterance)
        assertFalse(PairedBackend().detectsPerUtterance)
    }

    /**
     * THE INTERFACE CENSUS (4.1 L7). Two claims, one mechanism:
     *
     *  1. `detectsPerUtterance` has a DEFAULT BODY — the house seam rule. 4.0's NEW-C1 measured
     *     the alternative: a default PARAMETER value helps callers, not implementors, and would
     *     have un-overridden 23 overrides across 10 files with the interface's own body silently
     *     running instead of theirs.
     *  2. The set of members WITHOUT a default body is EXACTLY the original three. A name joining
     *     that set forces every implementor — all of the overrides' files, plus every test fake —
     *     to change, which is the cost the seam rule exists to refuse; and a new abstract member
     *     that arrives with its overrides already written everywhere would compile clean, so ONLY
     *     this census would name it.
     *
     * MECHANISM: this project compiles at the default `-Xjvm-default` mode (the bytecode story is
     * `LocalWhisperEngineCapSplitTest`'s census KDoc), so a member with a default body is emitted
     * as a static on `WhisperBackend$DefaultImpls` taking the interface as its first parameter —
     * and a member without one is not. The `$default` bridges that default PARAMETER VALUES emit
     * (`transcribe`/`transcribeStreaming`'s `useVad`) are name-suffixed, so the exact-name match
     * excludes them.
     */
    @Test
    fun theInterfaceCensus_membersWithoutADefaultBodyAreExactlyTheOriginalThree() {
        val iface = WhisperBackend::class.java
        val defaults = try {
            Class.forName("com.whispereverywhere.transcription.WhisperBackend\$DefaultImpls")
        } catch (e: ClassNotFoundException) {
            throw AssertionError(
                "WhisperBackend\$DefaultImpls is gone — Kotlin's -Xjvm-default mode changed. The " +
                    "product contract is UNVERIFIED until this census's mechanism is re-derived; " +
                    "LocalWhisperEngineCapSplitTest's census KDoc holds the discriminator story.",
                e,
            )
        }

        fun hasDefaultBody(m: java.lang.reflect.Method): Boolean =
            defaults.declaredMethods.any { d ->
                d.name == m.name &&
                    d.parameterTypes.size == m.parameterTypes.size + 1 &&
                    d.parameterTypes.first() == iface &&
                    d.parameterTypes.drop(1) == m.parameterTypes.toList()
            }

        val withoutDefaultBody = iface.declaredMethods
            .filter { !it.isSynthetic && !hasDefaultBody(it) }
            .map { m -> "${m.name}(${m.parameterTypes.joinToString(",") { it.simpleName }})" }
            .sorted()
        assertEquals(
            "the members WITHOUT a default body must stay exactly the original three. A name " +
                "APPEARING here is a new abstract member on the seam (every implementor must now " +
                "change — the exact cost the default-body rule refuses) or a deleted default " +
                "body; a name MISSING is a formerly-abstract member that grew a body existing " +
                "overrides now shadow.",
            listOf("load(String)", "release(long)", "transcribe(long,float[],String,boolean)"),
            withoutDefaultBody,
        )

        assertTrue(
            "detectsPerUtterance must exist on WhisperBackend WITH a default body (a " +
                "getDetectsPerUtterance static on DefaultImpls). Absent, the 4.1 L7 per-utterance " +
                "seam does not exist; declared abstract instead, it un-defaults the seam and " +
                "every existing implementor's file stops compiling — the widened-parameter-list " +
                "mistake in a different spelling.",
            iface.declaredMethods.any {
                !it.isSynthetic && it.name == "getDetectsPerUtterance" && hasDefaultBody(it)
            },
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
