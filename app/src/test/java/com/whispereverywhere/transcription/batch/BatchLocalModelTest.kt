package com.whispereverywhere.transcription.batch

import com.whispereverywhere.model.WhisperCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * WHICH FILE A BATCH JOB LOADS (4.0, Q9 fix round, I1).
 *
 * The defect: `BatchTranscriptionService` is hard-wired to `WhisperNativeBackend`, and `loadCtx`
 * fed it `installedModelPath()` — which for the `npu` tier is `encoder_qairt_context.bin`, a QAIRT
 * context binary that `WhisperNative.init` refuses by magic number. A user who selected the tier and
 * then transcribed a file got a failed job with nothing naming the cause.
 *
 * The table is run against the **shipped catalog**, not a fixture, because the two claims that
 * matter are claims about the app: that `npu` is the only tier substituted, and that `ultra` — which
 * `cpuTierModelPath()` deliberately excludes from donor eligibility — is **not**. An unconditional
 * substitution would have silently downgraded an `ultra` user's file job to `small`: same screen,
 * same progress, different transcript, no error anywhere.
 */
class BatchLocalModelTest {

    private val encoder = "/data/data/com.whispereverywhere/files/models/encoder_qairt_context.bin"
    private val ggml = "/data/data/com.whispereverywhere/files/models/ggml-small-q5_1.bin"
    private val ultraFile = "/data/data/com.whispereverywhere/files/models/ggml-large-v3-turbo-q5_0.bin"

    @Test
    fun theNpuTierIsSubstitutedWithAnInstalledGgml() {
        assertEquals(
            "whisper.cpp cannot load a QAIRT context binary. The batch path is hard-wired to it, " +
                "so the tier's own file must never reach it.",
            ggml,
            BatchLocalModel.pathFor(tierId = "npu", installedPath = encoder, ggmlSubstitutePath = ggml),
        )
        assertTrue(BatchLocalModel.needsGgmlSubstitute("npu"))
    }

    @Test
    fun everyGgmlTierKeepsItsOwnFileIncludingUltra() {
        // ULTRA IS THE ROW THAT MATTERS. It is excluded from cpuTierModelPath()'s donor picker
        // (128-bin, unusable as a mel filterbank), so an unconditional substitution would hand an
        // ultra user `small` for their file job and nothing would say so.
        assertEquals(
            "ultra loads the model the user chose, exactly as it does today",
            ultraFile,
            BatchLocalModel.pathFor(tierId = "ultra", installedPath = ultraFile, ggmlSubstitutePath = ggml),
        )
        // And every other tier the catalog can name, so a future single-file tier inherits the
        // untouched path by default rather than by someone remembering to add it here.
        WhisperCatalog.entries.filter { it.pairedArtifact == null }.forEach { model ->
            val own = "/models/${model.fileName}"
            assertEquals(
                "tier `${model.id}` is a one-file ggml and must be handed its own file",
                own,
                BatchLocalModel.pathFor(tierId = model.id, installedPath = own, ggmlSubstitutePath = ggml),
            )
            assertTrue(
                "and it must not be flagged for substitution",
                !BatchLocalModel.needsGgmlSubstitute(model.id),
            )
        }
        // A null / unknown id is not a paired tier and must not be treated as one.
        listOf(null, "", "nope").forEach { id ->
            assertEquals(
                "an unresolvable id (`$id`) keeps the installed path",
                ggml,
                BatchLocalModel.pathFor(tierId = id, installedPath = ggml, ggmlSubstitutePath = null),
            )
        }
    }

    @Test
    fun theNpuTierWithNoGgmlInstalledFailsLoudlyAndNamesTheTier() {
        assertEquals(
            "no eligible ggml on disk means there is no path at all — null, so loadCtx refuses",
            null,
            BatchLocalModel.pathFor(tierId = "npu", installedPath = encoder, ggmlSubstitutePath = null),
        )
        val refusal = BatchLocalModel.refusal("npu")
        val npu = requireNotNull(WhisperCatalog.byId("npu"))
        assertTrue(
            "the refusal NAMES the tier. `No on-device model installed` would be a lie to a user " +
                "looking at 358 MB of installed model, and it would send them to re-download it.",
            refusal.contains(npu.displayName),
        )
        assertTrue(
            "and it names the way out rather than only the problem",
            refusal.contains("Install a standard on-device model"),
        )
        assertTrue(
            "it must NOT reuse the no-model sentence, which describes a different device",
            !refusal.contains(BatchLocalModel.NO_LOCAL_MODEL),
        )
        assertEquals(
            "every non-paired tier keeps the pre-4.0 refusal, byte for byte, so a user with no " +
                "model installed sees exactly the message this path has always given",
            BatchLocalModel.NO_LOCAL_MODEL,
            BatchLocalModel.refusal("multi"),
        )
        assertEquals(BatchLocalModel.NO_LOCAL_MODEL, BatchLocalModel.refusal(null))
    }

    /**
     * The wiring, pinned as source: `BatchTranscriber` is not JVM-constructible here (it owns an
     * executor and a `RecordingStore`), and the truth table above is worth nothing if `loadCtx`
     * still reads `installedModelPath()` straight.
     */
    @Test
    fun loadCtxRoutesThroughThePolicyAndNotStraightAtTheInstalledPath() {
        val src = read("src/main/java/com/whispereverywhere/transcription/batch/BatchTranscriber.kt")
        assertEquals(
            "loadCtx resolves through BatchLocalModel, with all three inputs named",
            1,
            src.split(
                listOf(
                    "        val path = BatchLocalModel.pathFor(",
                    "            tierId = tierId,",
                    "            installedPath = modelPathProvider.installedModelPath(),",
                    "            ggmlSubstitutePath = modelPathProvider.cpuTierModelPath(),",
                    "        ) ?: error(BatchLocalModel.refusal(tierId))",
                ).joinToString("\n")
            ).size - 1,
        )
        assertEquals(
            "the bare installed-path read is gone from loadCtx — leaving it is the whole defect, " +
                "and it is one deleted wrapper away",
            0,
            src.split("val path = modelPathProvider.installedModelPath()").size - 1,
        )
        assertEquals(
            "and the refusal string is not re-spelled here, where it could drift from the policy",
            0,
            src.split("No on-device model installed").size - 1,
        )

        // THE SEAM'S OWN ANSWER (fix-round battery row F13, a MEASURED survivor).
        //
        // `WhisperModelManager` needs a Context and cannot be executed by a JVM test, so this is the
        // only instrument available — and the mutation it closes is one identifier long, compiles,
        // and is *almost* right: `installedModel()?.id` agrees with preferences in every case except
        // the one this whole fix exists for. A user who has selected `npu`, has NOT yet imported the
        // 358 MB pair, and has `multi` installed gets null from it — so the substitution never fires,
        // the null installed path throws, and a file job that would have run perfectly well on the
        // model they already have is refused with "No on-device model installed", which is false.
        // Answering off preferences asks "which tier was CHOSEN", which is the question.
        val manager = read("src/main/java/com/whispereverywhere/model/WhisperModelManager.kt")
        assertEquals(
            "selectedTierId answers off preferences, never through installedModel()",
            1,
            manager.split("override fun selectedTierId(): String? = prefs.selectedModelId").size - 1,
        )
    }

    private fun read(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            for (candidate in listOf(File(dir, relative), File(dir, "app/$relative"))) {
                if (candidate.isFile) return candidate.readText().replace("\r\n", "\n")
            }
            dir = dir.parentFile
        }
        throw AssertionError("cannot locate $relative from ${System.getProperty("user.dir")}")
    }
}
