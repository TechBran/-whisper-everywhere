package com.whispereverywhere.transcription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * THE ENGINE SEAM'S SHAPE (P1a; design §2.4) — [NpuAsrEngine], pinned as the design states it.
 *
 * The seam is only worth having if it stays NARROW: every member added to it is a place a vendor's
 * shape can leak back into the one policy body, and the first leak would be the one this refactor
 * exists to remove — a quantised block, a quant pair, a skel path. So the member set, each
 * signature, the absence of defaults and the absence of any vendor's name are pinned here, over
 * SOURCE for the declaration properties no call can observe (a default argument, like the `spec`
 * and `family` ones before it, is invisible to every caller that passes the argument).
 *
 * And the one member whose contract is "unchanged" — [NpuAsrEngine.decodeSegment] — is held
 * argument for argument to BOTH native seams' `nativeDecodeSegment`, the only place the three
 * spellings of one contract can be compared before a device runs them.
 */
class NpuAsrEngineSeamTest {

    private fun source(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            for (candidate in listOf(File(dir, relative), File(dir, "app/$relative"))) {
                if (candidate.isFile) return candidate.readText().replace("\r\n", "\n")
            }
            dir = dir.parentFile
        }
        throw AssertionError("cannot locate $relative from ${System.getProperty("user.dir")}")
    }

    private fun isComment(line: String): Boolean {
        val t = line.trimStart()
        return t.startsWith("//") || t.startsWith("/*") || t.startsWith("*")
    }

    /** [scope]'s live lines, whitespace collapsed to single spaces: the pins are about text, not wrapping. */
    private fun collapsed(scope: String): String =
        scope.split("\n").filterNot { isComment(it) }.joinToString(" ").replace(Regex("\\s+"), " ")

    private fun liveLines(scope: String, needle: String): List<String> =
        scope.split("\n").filterNot { isComment(it) }.map { it.trim() }.filter { it.contains(needle) }

    private val seamFile: String by lazy {
        source("src/main/java/com/whispereverywhere/transcription/NpuAsrEngine.kt")
    }

    /** The interface's own body: its declaration to the first column-0 closing brace. */
    private val iface: String by lazy {
        val start = seamFile.indexOf("interface NpuAsrEngine {")
        assertTrue("`interface NpuAsrEngine {` is missing from NpuAsrEngine.kt", start >= 0)
        val body = seamFile.substring(start)
        assertTrue("no column-0 closing brace follows the interface", body.contains("\n}\n"))
        body.substringBefore("\n}\n")
    }

    /** The parameter list of the first `anchor(` in [scope], collapsed. */
    private fun parameters(scope: String, anchor: String): String {
        val flat = collapsed(scope)
        val at = flat.indexOf(anchor)
        assertTrue("`$anchor` is missing", at >= 0)
        return flat.substring(at + anchor.length).substringBefore(")").trim().trimEnd(',').trim()
    }

    @Test
    fun theSeamIsTheDesignsTenMembersAndNothingVendorShaped() {
        val members = Regex("(?m)^\\s*fun (\\w+)\\(").findAll(
            iface.split("\n").filterNot { isComment(it) }.joinToString("\n")
        ).map { it.groupValues[1] }.toList()
        assertEquals(
            "the interface is exactly design 2.4's ten members, in its order. A member added here " +
                "is a vendor's shape one step from the policy body — the quant pair and the " +
                "quantised block are the ones this seam exists to keep out.",
            listOf(
                "probe", "prepare", "init", "encode", "decodeSegment",
                "detectLanguage", "epoch", "release", "lastError", "setDiag",
            ),
            members,
        )
        listOf("QnnAsrNative", "LiteRtAsrNative", "NpuQuantize", "uant", "U16", "Skel", "skel").forEach {
            assertEquals(
                "no live line of NpuAsrEngine.kt names `$it` — the seam is vendor-neutral, and the " +
                    "vendor's words belong in its engine. Found: ${liveLines(seamFile, it)}",
                emptyList<String>(),
                liveLines(seamFile, it),
            )
        }
    }

    @Test
    fun eachMemberIsTheDesignsSignatureWithNoDefaultAnywhere() {
        val flat = collapsed(iface)
        listOf(
            "fun probe(dispatchOrLibDir: String): String",
            "fun prepare(appContext: Context, family: NpuSocFamily): Refusal?",
            "fun init(spec: NpuModelSpec, files: NpuEngineFiles, dirs: NpuEngineDirs): Refusal?",
            "fun encode(melF32: ByteBuffer): Refusal?",
            "fun detectLanguage(): Int",
            "fun epoch(): Long",
            "fun release(epoch: Long)",
            "fun lastError(): String",
            "fun setDiag(on: Boolean)",
        ).forEach { signature ->
            assertTrue("the interface declares `$signature` exactly", flat.contains(signature))
        }
        assertEquals(
            "no parameter of the seam has a default — the `spec` and `family` doctrine (4.1 L2, " +
                "4.2 F2): a default is invisible to every caller that passes the argument, and it is " +
                "the one edit that arms one model's assets under another's census, or stages one " +
                "family's runtime under another's silicon. Found: ${liveLines(iface, "=")}",
            emptyList<String>(),
            liveLines(iface, "="),
        )
    }

    /**
     * "nativeDecodeSegment's contract, unchanged" (design 2.4) — held as TEXT, three ways. Every
     * one of the twelve is an array or a scalar the native loop reads positionally, and the two
     * native seams already agree with each other (`LiteRtNativeContractTest`); this puts the
     * engine's spelling on the same line, so an engine cannot quietly drop, add or transpose a
     * guard argument between the backend and either runtime.
     */
    @Test
    fun decodeSegmentIsBothNativeSeamsContractArgumentForArgument() {
        val engine = parameters(iface, "fun decodeSegment(")
        val qnn = parameters(
            source("src/main/java/com/whispereverywhere/npu/QnnAsrNative.kt"),
            "external fun nativeDecodeSegment(",
        )
        val liteRt = parameters(
            source("src/main/java/com/whispereverywhere/npu/LiteRtAsrNative.kt"),
            "external fun nativeDecodeSegment(",
        )
        assertEquals(
            "prompt, suppress, beginSuppress, maxTokens, out, temperatures, entropyThold, " +
                "logprobThold, noSpeechThold, noSpeechToken, cycleMaxDistinct, stats — the engine's " +
                "list is QnnAsrNative's, name for name and type for type",
            qnn,
            engine,
        )
        assertEquals("…and LiteRtAsrNative's", liteRt, engine)
        assertTrue(
            "and it answers the native count, an Int (negative = failure, the words in lastError)",
            collapsed(iface).contains("cycleMaxDistinct: Int, stats: FloatArray, ): Int"),
        )
    }

    /**
     * ENCODER FIRST, and it is a fact about WHERE the two paths come from, so it is checked there
     * (P1a review: the getter round-trip that stood here tested only that a data class stores its
     * arguments). The declaration names the fields encoder first; the backend fills them from
     * `load`'s two paths by name; and those two paths are the catalog's delivery names, as the
     * model manager resolves them — the tier row's own `fileName` for the first, its
     * `pairedArtifact` for the second. So the engine's `encoderPath` is the encoder only if, for
     * every tier the spec table routes, the row's primary file IS the encoder and its paired
     * artifact IS the decoder — executed here against the catalog, not assumed.
     */
    @Test
    fun theEncoderPathIsTheCatalogsEncoderAndTheDecoderPathItsPairedDecoder() {
        assertEquals(
            1,
            liveLines(seamFile, "data class NpuEngineFiles(val encoderPath: String, val decoderPath: String)").size,
        )
        assertEquals(
            1,
            liveLines(seamFile, "data class NpuEngineDirs(val libDir: String, val filesDir: String)").size,
        )
        val backend = source("src/main/java/com/whispereverywhere/transcription/NpuWhisperBackend.kt")
        assertEquals(
            "the backend's one NpuEngineFiles names load's first path the encoder and its second the " +
                "decoder, by field name — a positional call could swap them and still compile",
            1,
            liveLines(backend, "NpuEngineFiles(encoderPath = modelPath, decoderPath = companionPath)").size,
        )
        assertEquals(
            "load's second path is the ModelPathProvider's companion — the one-path form resolves it",
            1,
            liveLines(backend, "override fun load(modelPath: String): Long = load(modelPath, paths.companionModelPath())").size,
        )
        val manager = collapsed(source("src/main/java/com/whispereverywhere/model/WhisperModelManager.kt"))
        assertTrue(
            "the first path is the installed row's own fileName (installedModelPath -> fileFor)",
            manager.contains("private fun fileFor(model: WhisperModel): File = File(modelsDir(), model.fileName)") &&
                manager.contains(
                    "override fun installedModelPath(): String? { val model = installedModel() ?: " +
                        "return null return fileFor(model).absolutePath }"
                ),
        )
        assertTrue(
            "…and the second the SAME row's pairedArtifact (companionModelPath)",
            manager.contains(
                "override fun companionModelPath(): String? { val model = installedModel() ?: return " +
                    "null val paired = model.pairedArtifact ?: return null return File(modelsDir(), " +
                    "paired.fileName).absolutePath }"
            ),
        )
        // Every catalog row the spec table routes — derived, so a third npu-class row is checked the
        // day it lands rather than the day someone remembers this list.
        val routed = com.whispereverywhere.model.WhisperCatalog.entries.filter {
            com.whispereverywhere.npu.NpuModelSpec.forTier(it.id) != null
        }
        assertTrue(
            "the routed rows include both shipping npu-class tiers (got ${routed.map { it.id }})",
            routed.map { it.id }.containsAll(listOf("npu", "npu-turbo")),
        )
        routed.forEach { row ->
            val primary = row.fileName
            val paired = row.pairedArtifact?.fileName
            assertTrue(
                "`${row.id}`'s primary file is its ENCODER, so it is what reaches encoderPath: $primary",
                primary.contains("encoder") && !primary.contains("decoder"),
            )
            assertTrue(
                "…and its paired artifact is its DECODER, so it is what reaches decoderPath: $paired",
                paired != null && paired.contains("decoder") && !paired.contains("encoder"),
            )
        }
    }
}
