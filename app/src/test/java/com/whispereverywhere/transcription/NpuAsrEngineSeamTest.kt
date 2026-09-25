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

    @Test
    fun theFilesAndDirsAreNamedFieldsEncoderFirst() {
        assertEquals(
            1,
            liveLines(seamFile, "data class NpuEngineFiles(val encoderPath: String, val decoderPath: String)").size,
        )
        assertEquals(
            1,
            liveLines(seamFile, "data class NpuEngineDirs(val libDir: String, val filesDir: String)").size,
        )
        val files = NpuEngineFiles(encoderPath = "/models/enc.bin", decoderPath = "/models/dec.bin")
        assertEquals("/models/enc.bin", files.encoderPath)
        assertEquals("/models/dec.bin", files.decoderPath)
        val dirs = NpuEngineDirs(libDir = "/data/app/lib/arm64", filesDir = "/data/user/0/files")
        assertEquals("/data/app/lib/arm64", dirs.libDir)
        assertEquals("/data/user/0/files", dirs.filesDir)
    }
}
