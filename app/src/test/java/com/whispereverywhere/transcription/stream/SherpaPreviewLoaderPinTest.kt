package com.whispereverywhere.transcription.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `SherpaPreviewRecognizer.kt` pinned as SOURCE, because it cannot be pinned any other way: it is
 * the one file in the app that imports `com.k2fsa.sherpa.onnx`, whose static initialiser loads
 * `libsherpa-onnx-jni.so`, so no JVM test may reference it (TtsEngineSeamTest's standing rule).
 *
 * ### The one mutation this class exists to catch
 *
 * `modelType = ""` looks like an omission and reads like a bug. A future reader "fixing" it to
 * `"zipformer2"` — or, worse, helpfully wiring it to [StreamingPack.modelType] — ships an
 * **uncatchable process kill** on every `zipformer` v1 pack (French, both zh-en rows):
 * `OnlineTransducerModel::Create` short-circuits on a non-empty string
 * (`online-transducer-model.cc:146`), forces the v2 model, and its `query_head_dims` read misses
 * into `SHERPA_ONNX_EXIT(-1)` = `_Exit(-1)`. Nothing catches that — not `warm()`, not
 * `onLoadFailure`, not `markCorrupt`, not `disabled` — and it leaves no crash to find. The app
 * disappears mid-load and the next launch does it again.
 *
 * The value is one token, the consequence is process death, and no behavioural test in this suite
 * can see either. So the literal, the ZERO count on the old literal, and the reason itself are all
 * held here. The file is in the test task's `sourcePinnedInputs` (app/build.gradle.kts) — without
 * that entry, an edit confined to this file would leave `testDebugUnitTest` UP-TO-DATE and this
 * pin green against the source as it used to be.
 */
class SherpaPreviewLoaderPinTest {

    private val source: String by lazy {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        val relative = "src/main/java/com/whispereverywhere/transcription/stream/SherpaPreviewRecognizer.kt"
        while (dir != null) {
            for (candidate in listOf(File(dir, relative), File(dir, "app/$relative"))) {
                if (candidate.isFile) return@lazy candidate.readText().replace("\r\n", "\n")
            }
            dir = dir.parentFile
        }
        throw AssertionError("cannot locate $relative from ${System.getProperty("user.dir")}")
    }

    /**
     * The file split at the class that opens it: the docblock's job is to CARRY the warning (and
     * therefore to quote the literal it warns against), the code's job is not to contain it. A
     * whole-file count cannot tell those two apart and would be satisfied by deleting the reason.
     */
    private val doc: String by lazy { source.substringBefore(CLASS) }
    private val code: String by lazy { source.substringAfter(CLASS) }

    private fun count(haystack: String, needle: String) = haystack.split(needle).size - 1

    @Test fun theModelTypeIsPassedEmptySoTheEncodersOwnMetadataChoosesTheFamily() {
        assertEquals("exactly one modelType assignment, and it is empty", 1, count(code, "modelType = \"\""))
        assertEquals("and there is no other", 1, count(code, "modelType ="))
    }

    @Test fun theOldLiteralIsAbsentFromTheCodeAndPresentOnlyAsTheWarning() {
        // Not "is not assigned" — ABSENT from the code. A commented-out `modelType = "zipformer2"`
        // beside the real line is a copy waiting to be uncommented, and a pack's family has no
        // business in this file at all: the catalogue records it (StreamingPack.modelType).
        assertEquals(0, count(code, "\"zipformer2\""))
        assertEquals(0, count(code, "\"zipformer\""))
        // Nor may it be wired to the pack. That reads like the careful fix and is the same kill:
        // the pack says what the FILE will answer, and passing it re-arms the short-circuit.
        assertEquals(0, count(code, "pack.modelType"))
        assertEquals(0, count(code, ".modelType"))
        // And the docblock must still name what it is warning against.
        assertTrue("the warning names the literal it forbids", "\"zipformer2\"" in doc)
    }

    @Test fun theReasonIsInTheFileWithTheValue() {
        // A value this counter-intuitive survives only while the reason sits next to it. Each of
        // these is the specific fact that makes the empty string correct rather than lazy.
        assertTrue("the exit call is named", "_Exit(-1)" in doc)
        assertTrue("and that it cannot be caught", "uncatchable" in doc)
        assertTrue("the short-circuit's own source line", "online-transducer-model.cc:146" in doc)
        assertTrue("the key whose miss kills the process", "query_head_dims" in doc)
        assertTrue("and which packs it would kill", "zipformer` v1" in doc)
    }

    @Test fun theRestOfTheProbesVerbatimConfigIsUntouched() {
        // The owner validated 4.4.1 on device with exactly this config. None of it is this task's
        // to change, and all of it is a one-token edit away from being changed by accident while
        // the line above it is being edited.
        assertEquals(1, count(code, "provider = \"cpu\""))
        assertEquals(1, count(code, "enableEndpoint = false"))
        assertEquals(1, count(code, "decodingMethod = \"greedy_search\""))
        assertEquals(1, count(code, "dither = 0f"))
        assertEquals(1, count(code, "featureDim = 80"))
    }

    private companion object {
        const val CLASS = "class SherpaPreviewRecognizerFactory"
    }
}
