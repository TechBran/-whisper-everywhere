package com.whispereverywhere.npu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [NpuStage] — the closed set of stages an NPU-class tier declines at — held to the decline sites
 * it names (P1a, the engine seam).
 *
 * **The derivation moved here with the enum.** Until P1a, `NpuDiagTest` re-derived the stage list
 * from the backend's own `fallBackToCpuTier("…")` / `fallBackAndRun("…")` call sites and held
 * `NpuDiag.unavailable`'s KDoc equal to it (4.1 L1 m5, closed at 4.2 F5 — the stale list it
 * replaced was off by seven). The enum is now the stage names' one home, so the same derivation
 * holds the ENUM equal to the sites here, and `NpuDiagTest` holds the KDoc equal to the enum: the
 * KDoc, the enum and the source cannot drift apart pairwise.
 *
 * Source-anchored for the house reason: the backend reaches native code, so no JVM test may name
 * it; the enum itself is plain Kotlin and is executed.
 */
class NpuStageTest {

    /** The house locator — walk up from the test's working directory; line endings normalised. */
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

    /** [scope] without its comment lines: a decline site in a comment is not a decline site. */
    private fun live(scope: String): String =
        scope.split("\n").filterNot { isComment(it) }.joinToString("\n")

    /** How many LIVE lines of [scope] contain [needle]. */
    private fun liveLineCount(scope: String, needle: String): Int =
        scope.split("\n").count { !isComment(it) && it.contains(needle) }

    private val backend: String by lazy {
        source("src/main/java/com/whispereverywhere/transcription/NpuWhisperBackend.kt")
    }

    private val stages: String by lazy {
        source("src/main/java/com/whispereverywhere/npu/NpuStage.kt")
    }

    /**
     * The stages a session can decline at, in the order it reaches them: every decline funnels
     * through `fallBackToCpuTier` / `fallBackAndRun` with the stage as its first argument (the
     * one-funnel pins in `NpuDiagTest` prove the funnel), collected in source order, first
     * occurrence wins — `NpuDiagTest`'s 4.2 F5 regex, verbatim, over live lines.
     */
    private fun derivedDeclineOrder(): List<String> {
        val declineSite = Regex("fallBack(?:ToCpuTier|AndRun)\\(\\s*\"([a-z-]+)\"")
        return declineSite.findAll(live(backend)).map { it.groupValues[1] }.distinct().toList()
    }

    @Test
    fun theEnumIsTheDeclineSitesReDerivedInDeclineOrderPlusTheReservedDispatch() {
        val derived = derivedDeclineOrder()
        assertTrue(
            "the derivation found a real population (got $derived)",
            derived.size >= 10 && "encode" in derived && "decode" in derived,
        )
        assertEquals(
            "NpuStage, less the reserved DISPATCH, is EXACTLY the stages the decline sites produce, " +
                "in the order a session reaches them — re-derived, never retyped. A stage added to " +
                "the backend and not to the enum, one renamed on either side, or one retired from " +
                "the backend and left in the enum all fail here by name.",
            derived,
            NpuStage.entries.filter { it != NpuStage.DISPATCH }.map { it.wire },
        )
        assertEquals(
            "DISPATCH sits directly after SKEL: the two are ONE stage of load for two vendors — the " +
                "runtime's own staging, after every cheap refusal and before the expensive init",
            NpuStage.entries.indexOf(NpuStage.SKEL) + 1,
            NpuStage.entries.indexOf(NpuStage.DISPATCH),
        )
    }

    /**
     * RESERVED, and said so where it can fail. `dispatch` is the LiteRT engine's prepare stage
     * (P1b's `LiteRtAsrEngine`, staged by P2-6's helper); no Qualcomm session can produce it, and
     * a live `NpuStage.DISPATCH` on the Qualcomm path would be a refusal naming a runtime this
     * device does not have. THE NAMED TRIGGER: when an engine that stages a dispatch lands, the
     * derivation above must learn that engine's file and this exemption goes — re-spelled, not
     * deleted.
     */
    @Test
    fun dispatchIsReservedForTheLiteRtEngineAndNoQualcommSiteProducesIt() {
        assertFalse(
            "no decline site on the Qualcomm path produces `dispatch` today",
            "dispatch" in derivedDeclineOrder(),
        )
        assertEquals(
            "and the backend names NpuStage.DISPATCH on no live line",
            0,
            liveLineCount(backend, "NpuStage.DISPATCH"),
        )
    }

    /**
     * THE WORDS A DEVICE PRINTS, as hard literals on purpose — NOT derived from the enum or the
     * source. The derivation above proves the enum and the source agree; only this proves that
     * both still say what every device said before the seam, which is the property the owner's
     * Qualcomm regression gate compares (`npu: unavailable stage=…`, character for character). A
     * co-mutation that renamed a stage in the backend and the enum together passes the derivation
     * and dies here.
     */
    @Test
    fun everyWireWordIsTheWordADeviceHasAlwaysPrinted() {
        assertEquals(
            "the fourteen stage words the 4.2 F5 derivation produced before the seam, plus the " +
                "reserved `dispatch` — and nothing else",
            setOf(
                "companion", "mel-donor", "mel-asset", "mel-init", "vocab", "skel", "init",
                "epoch", "session", "mel", "quant", "encode", "lang", "decode", "dispatch",
            ),
            NpuStage.entries.map { it.wire }.toSet(),
        )
        assertEquals(
            "no two constants share a word — the card and the log join on it",
            NpuStage.entries.size,
            NpuStage.entries.map { it.wire }.toSet().size,
        )
    }

    /**
     * The word is spelled where it is defined, and by one rule. A `wire` computed from `name`
     * would make `"mel-donor"` appear nowhere in the source — the grep a support reply starts
     * from — and `name` itself (`MEL_DONOR`) is not the word at all: a funnel handed
     * `stage.name` prints `stage=MEL_DONOR` on every device.
     */
    @Test
    fun eachWireWordIsALiteralBesideItsConstantAndIsItsNameLowerCased() {
        NpuStage.entries.forEach { stage ->
            assertEquals(
                "`${stage.name}(\"${stage.wire}\"),` on exactly one live line of NpuStage.kt",
                1,
                liveLineCount(stages, "${stage.name}(\"${stage.wire}\"),"),
            )
            assertEquals(
                "`${stage.name}`'s word is its name lower-cased, '_' to '-' — one spelling rule, so " +
                    "no constant can carry a neighbour's word",
                stage.name.lowercase().replace('_', '-'),
                stage.wire,
            )
        }
        assertEquals(
            "Refusal is the design's shape exactly (§2.4) — a stage from the closed set and a " +
                "non-null one-line detail; a String stage would reopen the set at every call site",
            1,
            liveLineCount(stages, "data class Refusal(val stage: NpuStage, val detail: String)"),
        )
    }

    /** The rendering the diag readers see: a refusal's word is the line's word, unchanged. */
    @Test
    fun aRefusalRendersTheUnavailableLineAndTheCardStageExactlyAsBeforeTheSeam() {
        assertEquals(
            "npu: unavailable stage=mel-donor detail=no installed 80-bin whisper model to take the " +
                "mel filterbank from",
            NpuDiag.unavailable(
                NpuStage.MEL_DONOR.wire,
                "no installed 80-bin whisper model to take the mel filterbank from",
            ),
        )
        val refusal = Refusal(NpuStage.SKEL, "libQnnHtpV75Skel.so (family 8gen3) could not be staged")
        assertEquals(
            "npu: unavailable stage=skel detail=libQnnHtpV75Skel.so (family 8gen3) could not be staged",
            NpuDiag.unavailable(refusal.stage.wire, refusal.detail),
        )
        assertEquals(
            "and the card recovers the same one word from the published reason",
            "skel",
            NpuTierStatus.stageOf("${refusal.stage.wire}: ${refusal.detail}"),
        )
    }

    /**
     * The seam's sources are inputs of the test task — the list's own rule, membership follows
     * what the tests READ. Each is read as text here or beside its pins, and a comment-shaped
     * edit compiles to identical bytes, so without its entry the one edit a pin exists to catch
     * is the one that leaves `:app:testDebugUnitTest` UP-TO-DATE.
     */
    @Test
    fun theSeamsSourcesAreInputsOfTheTestTask() {
        val gradle = source("build.gradle.kts")
        listOf(
            "\"src/main/java/com/whispereverywhere/npu/NpuStage.kt\",",
            "\"src/main/java/com/whispereverywhere/transcription/NpuAsrEngine.kt\",",
        ).forEach {
            assertEquals(
                "app/build.gradle.kts must list $it among sourcePinnedInputs",
                1,
                liveLineCount(gradle, it),
            )
        }
    }
}
