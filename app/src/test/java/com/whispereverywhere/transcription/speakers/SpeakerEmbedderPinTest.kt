package com.whispereverywhere.transcription.speakers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * `SpeakerEmbedder.kt` and the model it loads, pinned as SOURCE and as BYTES (4.10 Task 2).
 *
 * ### Why source, and why this file can never be referenced from a test
 *
 * `SpeakerEmbeddingExtractor`'s companion initialiser loads `libsherpa-onnx-jni.so`, which is not
 * on the unit-test classpath and never will be. Touching the class from the JVM suite is an
 * `UnsatisfiedLinkError` in whichever test ran first — the same standing rule
 * `SherpaPreviewLoaderPinTest` and `TtsEngineSeamTest` follow for the previewer's and the TTS
 * engine's adapters. So the adapter's contract is held here, as text, and the rule itself is
 * asserted: exactly one file in `src/main/java` may name `SpeakerEmbeddingExtractor`, and no test
 * may name `SpeakerEmbedder`.
 *
 * ### The model, asserted against the bytes that ship
 *
 * The 40.3 MB NeMo **TitaNet-small** model is BUNDLED (owner ruling, spec §3.4) — there is no
 * download flow, so the one thing that can go wrong is the file itself: copied from the wrong
 * release, truncated by an interrupted copy, or left as one of the four OTHER embedding models the
 * spike's session 2 scored beside it (`docs/measurements/2026-09-18-speaker-spike.md`) without the
 * constants following it. That last one is not hypothetical: the swap this file now pins replaced
 * CAM++, and all five candidates sat in the same directory under the same 26-40 MB. A binary asset
 * is an input to no compile task, so [theModelAssetIsTheTitaNetModel] and the digest in the
 * adapter's own KDoc are the only readers of those bytes in this repo — and the asset is declared
 * in the test task's `sourcePinnedInputs` (asserted below) so that replacing it actually re-runs
 * them.
 *
 * Assets are NOT on the JVM test classpath (`unitTests.isIncludeAndroidResources` is at its default
 * `false`), so the file is read from `app/src/main/assets` by path, as BYTES, with the house
 * `source(relative)` walker — never `readText`, whose `\r\n` normalisation would rewrite the
 * middle of an ONNX graph.
 */
class SpeakerEmbedderPinTest {

    /** The house walker, stopping at the `File` — `MelbankAssetTest`'s, for the same binary reason. */
    private fun source(relative: String): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            for (candidate in listOf(File(dir, relative), File(dir, "app/$relative"))) {
                if (candidate.isFile) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError("cannot locate $relative from ${System.getProperty("user.dir")}")
    }

    /** The same walker, stopping at a DIRECTORY — for the two source trees this class scans. */
    private fun sourceDir(relative: String): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            for (candidate in listOf(File(dir, relative), File(dir, "app/$relative"))) {
                if (candidate.isDirectory) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError("cannot locate $relative/ from ${System.getProperty("user.dir")}")
    }

    private fun text(relative: String): String = source(relative).readText().replace("\r\n", "\n")

    private val adapter: String by lazy { text(ADAPTER) }

    /**
     * The file split at the class that opens it — `SherpaPreviewLoaderPinTest`'s device, for its
     * reason: the docblock's job is to CARRY the config's justification, and therefore to quote the
     * values it justifies; the code's job is to hold each of them exactly once. A whole-file count
     * cannot tell those two apart, and would be satisfied by deleting the reason.
     */
    private val doc: String by lazy { adapter.substringBefore(CLASS) }
    private val code: String by lazy { adapter.substringAfter(CLASS) }

    private fun count(haystack: String, needle: String) = haystack.split(needle).size - 1

    /** First index of an ASCII [needle] in [haystack], or -1 — the graph is bytes, not text. */
    private fun indexOf(haystack: ByteArray, needle: String): Int {
        val pattern = needle.toByteArray(Charsets.US_ASCII)
        outer@ for (start in 0..haystack.size - pattern.size) {
            for (i in pattern.indices) if (haystack[start + i] != pattern[i]) continue@outer
            return start
        }
        return -1
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun kotlinFilesUnder(relative: String): List<File> =
        sourceDir(relative).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    // ---------------------------------------------------------------- the seam rule

    @Test
    fun theAdapterIsTheOnlyFileInTheAppThatNamesSherpasEmbeddingExtractor() {
        val importers = kotlinFilesUnder("src/main/java")
            .filter { it.readText().contains("SpeakerEmbeddingExtractor") }
            .map { it.name }
            .sorted()
        assertEquals(
            "Exactly one file may name SpeakerEmbeddingExtractor. Its companion initialiser loads " +
                "libsherpa-onnx-jni.so, so a second importer is a second way for the JVM suite to " +
                "reach a native library it does not have — and for a release build to need a keep " +
                "rule nobody wrote. Found: $importers",
            listOf("SpeakerEmbedder.kt"),
            importers,
        )
    }

    @Test
    fun noTestReferencesTheAdapter() {
        val offenders = kotlinFilesUnder("src/test/java")
            .filter { it.name != "SpeakerEmbedderPinTest.kt" }
            .filter { it.readText().contains("SpeakerEmbedder") }
            .map { it.name }
            .sorted()
        assertEquals(
            "No test may name SpeakerEmbedder: constructing it — or merely resolving it in a " +
                "mock — pulls SpeakerEmbeddingExtractor's class initialiser and its " +
                "System.loadLibrary into the JVM suite. The tracker beside it is pure and is where " +
                "the decisions live; this class is a seam and is pinned as text. Found: $offenders",
            emptyList<String>(),
            offenders,
        )
    }

    // ---------------------------------------------------------------- the config

    @Test
    fun theExtractorIsConfiguredExactlyAsTheOtherTwoSherpaAdaptersAre() {
        assertEquals(
            "provider must be \"cpu\", exactly once. NNAPI is compiled OUT of this AAR (rung 3 " +
                "§5.6), so any other string is a silent fallback at best.",
            1, count(code, "provider = \"cpu\""),
        )
        assertEquals(
            "numThreads must be 1, and there must be ONE config for both load arms — two configs " +
                "are two places for the next edit to land in only one of. The embedder owns one " +
                "executor and runs beside the whisper thread; a second thread inside it competes " +
                "with the decode for the same cores, and the commit floors were measured without it.",
            1, count(code, "numThreads = 1"),
        )
        assertEquals("debug off: sherpa's debug prints model structure to logcat", 1, count(code, "debug = false"))
        assertEquals(
            "the model is named ONCE, as the asset constant — never inline at a config",
            1, count(code, "\"$ASSET_NAME\""),
        )
    }

    @Test
    fun theAssetIsLoadedThroughTheAssetManagerFirstAndTheStagedPathOnlyAsAFallback() {
        // The AssetManager constructor is what the bundling ruling rests on — it is why no download
        // flow and no filesDir copy is needed for a 40.3 MB model. The staged path is the
        // contingency the plan named (a Task 4 finding), and it must stay a contingency: staging
        // writes 40.3 MB into filesDir on a device that had no need of it.
        assertTrue("the AssetManager arm exists", "app.assets" in code)
        assertTrue("the fallback goes through the house stager", "NpuAssetStage" in code)
        assertTrue(
            "…and the fallback passes a null AssetManager with an absolute path, the way " +
                "SherpaPreviewRecognizer and TtsEngine.buildTts do",
            "SpeakerEmbeddingExtractor(null," in code,
        )
        assertTrue(
            "…and the asset arm is tried FIRST: staging writes 40.3 MB into filesDir to fix a " +
                "problem most devices do not have, so it may only run once the bundled read has " +
                "actually refused",
            code.indexOf("fromAsset()") < code.indexOf("fromStagedFile()"),
        )
    }

    @Test
    fun aFailedLoadIsRememberedSoThatEveryChunkDoesNotRetryThirtyMegabytes() {
        // sherpa's constructor throws IllegalArgumentException when the native handle comes back 0
        // (verified in the shipped jar: `require(ptr != 0L)`). Without a latch, a model that cannot
        // load at all would be re-attempted once per committed chunk, on the embedder's thread,
        // forever — 40.3 MB of asset read per chunk for a capability that is already off.
        assertTrue("the latch is read before a load is attempted", "if (failed) return null" in code)
        assertTrue("…and set when one fails", "failed = true" in code)
        assertTrue(
            "…and embed() answers null rather than throwing on the assigner's executor: sherpa " +
                "requires a non-zero native handle and throws IllegalArgumentException otherwise, " +
                "and ONNX can throw from native code on a graph it will not load",
            "catch (cause: Throwable)" in code,
        )
    }

    // ---------------------------------------------------------------- the model itself

    @Test
    fun theModelAssetIsTheTitaNetModel() {
        val asset = source("src/main/assets/$ASSET_NAME")
        assertEquals(
            "$ASSET_NAME must be exactly $ASSET_BYTES bytes — the length of the NeMo " +
                "TitaNet-small graph the spike's session 2 chose on 2026-09-18 " +
                "(docs/measurements/2026-09-18-speaker-spike.md). A short file is a copy that was " +
                "interrupted, and ONNX will refuse it on device with a message nobody reads; a " +
                "different length is a different model, which the bands in SpeakerTracker were " +
                "not set for.",
            ASSET_BYTES, asset.length(),
        )
        val digest = sha256(asset.readBytes())
        assertEquals(
            "…and its sha256 must be the one the measurement doc recorded. This is the only check " +
                "in the repo that the bundled 40.3 MB is TitaNet-small and not one of the four " +
                "other models session 2 scored beside it — CAM++ VoxCeleb (29.6 MB), WeSpeaker " +
                "CAM++-LM (29.3 MB), WeSpeaker ResNet34-LM (26.5 MB), ERes2Net-base (39.6 MB) — " +
                "all of which sat in one directory on the PC while this swap was made, and only " +
                "one of which separates one, two and three voices.",
            ASSET_SHA256, digest,
        )
        assertTrue(
            "and the adapter's own KDoc must record that digest, so the file and the code that " +
                "loads it can be compared without a build (the plan's Task 2 asks for it there)",
            ASSET_SHA256 in doc,
        )
        assertTrue("…along with its length", "40_257_283" in doc || ASSET_BYTES.toString() in doc)
        assertEquals(
            "…and the runtime must stage against that same digest, so the fallback arm cannot " +
                "verify a different file than this test just hashed",
            1, count(code, ASSET_SHA256),
        )
        assertFalse(
            "…and no trace of the model it replaced may be left in the adapter's constants: the " +
                "CAM++ digest naming a TitaNet asset is the one wrong pairing that would stage a " +
                "file this test never hashed",
            CAMPLUS_SHA256 in code,
        )
    }

    /**
     * CC-BY-4.0 asks for attribution, and this repo's habit is that a licence obligation is
     * written down where the obligated file is named — the discipline `docs/LANGUAGE-CLEARANCE.md`
     * already applies to every streaming pack.
     *
     * The swap traded CAM++'s Apache-2.0 for a licence with a USER-VISIBLE condition, and the
     * attribution line is deliberately NOT written in this task. So the debt itself is pinned: the
     * adapter has to say that the line is owed in `oss_licenses.html` and that the clearance-sheet
     * row — not this test, and not the build — is what gates production. A production gate rather
     * than a build gate because the spike must still be runnable on an uncleared model; that is
     * the whole reason the debt is allowed to exist at all.
     */
    @Test
    fun theAdapterStatesTheAttributionThatIsSTILLOWEDForACcByModel() {
        for (phrase in listOf(
            "CC-BY-4.0",
            "oss_licenses.html",
            "clearance-sheet row is the PRODUCTION GATE",
        )) {
            assertTrue(
                "SpeakerEmbedder.kt's KDoc must state: <<$phrase>>. TitaNet-small is CC-BY-4.0 " +
                    "and its attribution is not in the OSS notices yet; the sentence that says so " +
                    "is the only thing standing between that debt and a store build.",
                phrase in doc,
            )
        }
        assertFalse(
            "and the notices must not be claimed as done while they are not: this task did not " +
                "edit oss_licenses.html, and a KDoc saying it did is worse than one saying nothing",
            "already in oss_licenses.html" in doc,
        )
    }

    @Test
    fun theDocumentedEmbeddingWidthIsTheOneTheShippedGraphAnnounces() {
        // The one number about this model that nothing at runtime reads, and that nothing at
        // runtime can therefore correct: SpeakerTracker measures every embedding against the width
        // of the fingerprints it already holds, never against a constant, so a wrong width in the
        // adapter's KDoc is invisible on device and still wrong for everything that is sized from
        // it — the spike's diag line, the jsonl header's `dim`, the per-segment cost, spec §3.3's
        // resident budget.
        //
        // This exact pin already caught one wrong width: the adapter said 192 while the CAM++
        // graph it then loaded said 512. The model swap of this commit makes 192 the RIGHT answer,
        // which is the nastiest possible sequel — the old wrong number and the new right one are
        // the same digits — so the width is re-derived from the asset's own `metadata_props` here
        // rather than reasoned about from the family name. `python onnx` reads the same thing off
        // the graph: output `embs` is [*, 192], beside a `logits` [*, 16681] training head that
        // sherpa does not use.
        val bytes = source("src/main/assets/$ASSET_NAME").readBytes()
        val key = indexOf(bytes, "output_dim")
        assertTrue("the graph must carry an output_dim annotation to be pinned against", key >= 0)
        val annotation = String(bytes, key, minOf(24, bytes.size - key), Charsets.ISO_8859_1)
        assertTrue(
            "the shipped graph's own output_dim must be $EMBEDDING_DIM. Found near the key: " +
                annotation.filter { it.isLetterOrDigit() || it == '_' },
            Regex("output_dim.{0,4}$EMBEDDING_DIM").containsMatchIn(annotation),
        )
        assertTrue(
            "…and the adapter's KDoc must state that same width — it is the sentence the device " +
                "spike and plan Task 3 read to size the embedding",
            "$EMBEDDING_DIM-float" in doc,
        )
        assertFalse(
            "…and must not describe the fingerprint as 512 floats: 512 was the width of the " +
                "CAM++ VoxCeleb graph this model replaced, and the paragraph that describes the " +
                "embedding is exactly what a model swap leaves behind",
            "512-float" in doc,
        )
    }

    @Test
    fun theAdapterAndTheModelAreDeclaredInputsOfTheTestTask() {
        // D5, the stale-evidence hazard, in both of its shapes at once. The adapter is a plain
        // Kotlin file whose pins above are KDoc-phrase and literal-count assertions, so a
        // comment-only edit compiles to a byte-identical class; the model is a binary asset that is
        // an input to no compile task at all. Without these two entries, replacing the model or
        // rewriting the reasons above leaves :app:testDebugUnitTest UP-TO-DATE and every assertion
        // in this class passes against the repo as it used to be.
        val gradle = text("build.gradle.kts")
        assertTrue(
            "app/build.gradle.kts must list \"$ADAPTER\" in sourcePinnedInputs",
            gradle.contains("\"$ADAPTER\""),
        )
        assertTrue(
            "app/build.gradle.kts must list \"src/main/assets/$ASSET_NAME\" in sourcePinnedInputs",
            gradle.contains("\"src/main/assets/$ASSET_NAME\""),
        )
    }

    private companion object {
        const val ADAPTER = "src/main/java/com/whispereverywhere/transcription/speakers/SpeakerEmbedder.kt"
        const val CLASS = "class SpeakerEmbedder("
        const val ASSET_NAME = "speaker_titanet_small_16k.onnx"
        const val ASSET_BYTES = 40_257_283L
        const val ASSET_SHA256 = "ad4a1802485d8b34c722d2a9d04249662f2ece5d28a7a039063ca22f515a789e"

        /**
         * The digest of the CAM++ VoxCeleb model this one replaced. Here only to be asserted
         * ABSENT: a half-finished swap that renames the asset and leaves the old digest in the
         * staging arm verifies a file nothing in this repo has ever hashed.
         */
        const val CAMPLUS_SHA256 = "357a834f702b80161e5b981182c038e18553c1f2ca752ed6cec2052365d4129b"

        /** The graph's own `output_dim`. Asserted against the asset bytes, not taken on trust. */
        const val EMBEDDING_DIM = 192
    }

    /**
     * The final whole-branch review of 2026-09-18 found the 29.6 MB CAM++ asset undeclared, so AAPT2
     * would have DEFLATED it: sherpa opens assets through the AssetManager, and a compressed
     * asset can be neither mapped nor read as a file. The rule lives in app/build.gradle.kts and
     * this pin reads it there.
     */
    @Test
    fun theModelAssetIsStoredUncompressed() {
        val gradle = text("build.gradle.kts")
        val block = gradle.substringAfter("androidResources {", "").substringBefore("}")
        assertTrue("androidResources.noCompress must name onnx", block.contains("noCompress") && block.contains("\"onnx\""))
    }
}
