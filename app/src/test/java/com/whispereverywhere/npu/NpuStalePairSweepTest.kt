package com.whispereverywhere.npu

import com.whispereverywhere.data.local.PreferencesManager
import com.whispereverywhere.model.ModelMigration
import com.whispereverywhere.model.ModelScope
import com.whispereverywhere.model.WhisperCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * THE STALE-PAIR SWEEP (4.15) — the launch half of the owner-ruled re-download, EXECUTED.
 *
 * [NpuStalePairSweep] is pure over `java.io.File`, so the sweep's whole decision — remove, keep,
 * or leave alone — runs here on a temp directory, the `StreamingPackInstallTest` way. The files
 * are small: the gate the sweep judges against is built from a [PackArtifact] row, and a row
 * constructed at 1,000 / 400 bytes exercises the same `installedGateBytes` + `passesInstalledGate`
 * pair a real 686 MB row does, under the tier's REAL catalog names. The real refresh numbers are
 * then held against the real census rows through the pure predicate, with no file at all.
 *
 * What cannot run here — `WhisperModelManager` needs a `Context`, `PreferencesManager` a
 * `SharedPreferences` — is pinned as source at the bottom, the house split: the manager asks the
 * pure rule, logs the line, writes the record for the selected tier only, and the shared finalise
 * clears it in its committed branch.
 */
class NpuStalePairSweepTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val turbo = WhisperCatalog.byId("npu-turbo")!!
    private val small = WhisperCatalog.byId("npu")!!

    private val turboEncoder = "turbo_encoder_qairt_context.bin"
    private val turboDecoder = "turbo_decoder_qairt_context.bin"

    /** A census row for [tierId] at small, test-sized byte counts — the real names, the real rule. */
    private fun row(
        tierId: String = "npu-turbo",
        encoderBytes: Long = 1_000L,
        decoderBytes: Long = 400L,
        familyId: String = "test_family",
    ): PackArtifact {
        val model = WhisperCatalog.byId(tierId)!!
        return PackArtifact(
            familyId = familyId,
            tierId = tierId,
            sourceBytes = 1_234L,
            encoder = PackEntry(model.fileName, encoderBytes, "e".repeat(64)),
            decoder = PackEntry(model.pairedArtifact!!.fileName, decoderBytes, "d".repeat(64)),
            evidence = "constructed in NpuStalePairSweepTest",
        )
    }

    private fun write(dir: File, name: String, bytes: Int): File =
        File(dir, name).apply { writeBytes(ByteArray(bytes)) }

    private fun models(): File = tmp.newFolder("models")

    // ------------------------------------------------------------------ the sweep, executed

    @Test
    fun aStalePairIsRemovedAndTheSelectedTiersRecordIsTheEventItWasStaleAgainst() {
        // The Fold6's shape at test scale: the encoder 13.1% over the family row (775,831,552
        // against 686,112,520 in the field), the decoder inside the band. isInstalled answers
        // false for that pair, so the sweep removes BOTH files — the decoder too, because half a
        // pair is not an install and would only sit there as dead weight.
        val dir = models()
        write(dir, turboEncoder, 1_131)
        write(dir, turboDecoder, 400)
        val census = row()
        val removed = NpuStalePairSweep.sweep(dir, turbo, census)
        assertNotNull("a pair that fails the installed gate is removed", removed)
        assertFalse("the stale encoder is gone", File(dir, turboEncoder).exists())
        assertFalse("and its decoder with it, though the decoder alone sat inside the band", File(dir, turboDecoder).exists())
        assertEquals("npu-turbo", removed!!.tierId)
        assertEquals("what WAS on disk is reported, for the diag line", 1_131L, removed.encoderBytes)
        assertEquals(400L, removed.decoderBytes)
        assertEquals("and what the census says, beside it", 1_000L, removed.censusEncoderBytes)
        assertEquals(400L, removed.censusDecoderBytes)
        assertEquals("nothing refused to go", emptyList<String>(), removed.left)
        assertEquals(
            "the event is keyed on the row it was stale against — family and encoder digest",
            "test_family:" + "e".repeat(64),
            removed.censusKey,
        )
        // THE PREF: when the removed tier is the SELECTED one, the record is that tier and that
        // event — what the boot notice and the onboarding sentence read.
        assertEquals(
            NpuRedownload("npu-turbo", "test_family:" + "e".repeat(64)),
            NpuStalePairSweep.recordFor(removed, selectedTierId = "npu-turbo"),
        )
    }

    @Test
    fun aStalePairOfATierTheUserIsNotOnIsRemovedButRecordsNothing() {
        // "The selected tier needs a re-download" is the record's whole meaning. A stale turbo
        // pair on a phone whose user moved to a CPU rung (or to the other NPU tier) is dead
        // weight: removed, logged, and nobody is told to fetch it again.
        val dir = models()
        write(dir, turboEncoder, 1_131)
        write(dir, turboDecoder, 400)
        val removed = NpuStalePairSweep.sweep(dir, turbo, row())!!
        assertFalse(File(dir, turboEncoder).exists())
        assertNull(NpuStalePairSweep.recordFor(removed, selectedTierId = "small-q8"))
        assertNull(NpuStalePairSweep.recordFor(removed, selectedTierId = "npu"))
        assertNull("no selection on record is not the removed tier either", NpuStalePairSweep.recordFor(removed, selectedTierId = null))
    }

    @Test
    fun aMatchingPairIsKeptExactlyWhereItIs() {
        // The v0.63.0 pair the fetch lands, at its exact census bytes — and a pair anywhere
        // inside the ±5% band, which isInstalled accepts and the sweep therefore must too.
        for ((encoder, decoder) in listOf(1_000 to 400, 1_050 to 380, 950 to 420)) {
            val dir = tmp.newFolder("models_${encoder}_$decoder")
            write(dir, turboEncoder, encoder)
            write(dir, turboDecoder, decoder)
            assertNull(
                "a pair isInstalled accepts ($encoder/$decoder against 1000/400) is never swept",
                NpuStalePairSweep.sweep(dir, turbo, row()),
            )
            assertEquals(encoder.toLong(), File(dir, turboEncoder).length())
            assertEquals(decoder.toLong(), File(dir, turboDecoder).length())
        }
    }

    @Test
    fun noCensusRowLeavesEveryFileWhereItIs_theFamilyNullCase() {
        // A CPU-only device that somehow holds these files (the Tab S10+'s sideloaded
        // experiments), or a family with no row for the tier: there is no census to be stale
        // against, so NOTHING moves — not the pair, not a parked copy. The manager does not even
        // call in without a family; this is the pure half of the same promise.
        val dir = models()
        write(dir, turboEncoder, 1_131)
        write(dir, turboDecoder, 400)
        write(dir, "$turboEncoder.prev", 1_131)
        assertNull(NpuStalePairSweep.sweep(dir, turbo, artifact = null))
        assertTrue(File(dir, turboEncoder).exists())
        assertTrue(File(dir, turboDecoder).exists())
        assertTrue(File(dir, "$turboEncoder.prev").exists())
    }

    @Test
    fun theParkedPrevOfAStalePairGoesWithIt_andAPartIsLeftToTheReconcile() {
        // A `.prev` of either name that survives the reconcile is parked stale bytes, removed
        // with its pair. A `.part` is staging debris — the reconcile's business, which runs first
        // on the same pass — and the sweep never touches one.
        val dir = models()
        write(dir, turboEncoder, 1_131)
        write(dir, turboDecoder, 400)
        write(dir, "$turboEncoder.prev", 1_131)
        write(dir, "$turboDecoder.prev", 400)
        write(dir, "$turboEncoder.part", 10)
        val removed = NpuStalePairSweep.sweep(dir, turbo, row())
        assertNotNull(removed)
        listOf(turboEncoder, turboDecoder, "$turboEncoder.prev", "$turboDecoder.prev").forEach {
            assertFalse("$it must be removed with the stale pair", File(dir, it).exists())
        }
        assertTrue("the .part is the reconcile's, and it is still there", File(dir, "$turboEncoder.part").exists())
    }

    @Test
    fun aHalfPairIsStaleToo_andIsReportedAsAHalf() {
        // Only the decoder on disk (the encoder deleted by hand, a half-finished removal): the
        // tier cannot arm on it, isInstalled is false, and it is removed. The missing half
        // prints as `none` on the line, never as a 0-byte file.
        val dir = models()
        write(dir, turboDecoder, 400)
        val removed = NpuStalePairSweep.sweep(dir, turbo, row())!!
        assertFalse(File(dir, turboDecoder).exists())
        assertNull(removed.encoderBytes)
        assertEquals(400L, removed.decoderBytes)
        assertEquals(
            "npu: stale pair removed tier=npu-turbo encoder=none decoder=400 census=1000/400",
            NpuDiag.stalePairRemoved(removed),
        )
    }

    @Test
    fun nothingOnDiskIsNothingToSweep() {
        // The healthy launch on every phone that never held a pair: two stats and out.
        val dir = models()
        assertNull(NpuStalePairSweep.sweep(dir, turbo, row()))
        assertEquals(0, dir.listFiles()!!.size)
    }

    @Test
    fun eachTierIsSweptUnderItsOwnNamesAndNeverTouchesTheOther() {
        // npu and npu-turbo share a directory. A stale SMALL pair is removed under small's names
        // and a correct turbo pair beside it is untouched — the per-tier names are what keep a
        // sweep of one tier from reaching the other.
        val dir = models()
        write(dir, "encoder_qairt_context.bin", 1_175)
        write(dir, "decoder_qairt_context.bin", 400)
        write(dir, turboEncoder, 1_000)
        write(dir, turboDecoder, 400)
        assertNotNull(NpuStalePairSweep.sweep(dir, small, row(tierId = "npu")))
        assertFalse(File(dir, "encoder_qairt_context.bin").exists())
        assertFalse(File(dir, "decoder_qairt_context.bin").exists())
        assertNull(NpuStalePairSweep.sweep(dir, turbo, row(tierId = "npu-turbo")))
        assertTrue(File(dir, turboEncoder).exists())
        assertTrue(File(dir, turboDecoder).exists())
    }

    @Test
    fun aSingleFileTierIsNeverSwept() {
        // Only paired tiers have a census row to be stale against. A ggml rung has no pair, and
        // its own size gate is the catalog's; the sweep answers null before touching anything.
        val dir = models()
        val cpu = WhisperCatalog.byId("small-q8")!!
        write(dir, cpu.fileName, 10)
        assertNull(NpuStalePairSweep.sweep(dir, cpu, row()))
        assertTrue(File(dir, cpu.fileName).exists())
    }

    // ------------------------------------------------------------------ the one rule, executed

    @Test
    fun theInstalledPredicateIsExecutableAndItsOrderIsTheFunction() {
        // `NpuAssetImport.passesInstalledGate` is isInstalled's per-file rule AND the sweep's,
        // moved out of the manager at 4.15 so it can be run rather than read. Every clause the
        // source pin in UnsupportedTierGatePinTest names is executed here.
        val gate = NpuAssetImport.installedGateBytes(turbo, row())
        assertTrue("the census pair", NpuAssetImport.passesInstalledGate(gate, 1_000L, 400L))
        assertTrue("±5% inclusive at both edges", NpuAssetImport.passesInstalledGate(gate, 1_050L, 380L))
        assertTrue(NpuAssetImport.passesInstalledGate(gate, 950L, 420L))
        assertFalse("one byte past the band", NpuAssetImport.passesInstalledGate(gate, 1_051L, 400L))
        assertFalse("no primary on disk", NpuAssetImport.passesInstalledGate(gate, null, 400L))
        assertFalse(
            "HALF AN INSTALL IS NOT AN INSTALL: an encoder without its decoder arms halfway",
            NpuAssetImport.passesInstalledGate(gate, 1_000L, null),
        )
        assertFalse("the decoder at the wrong size", NpuAssetImport.passesInstalledGate(gate, 1_000L, 500L))
        // THE HOISTED-RETURN MUTATION, executed: a single-file gate with NOTHING on disk must be
        // false. Move `gate.paired ?: return true` above the primary clause and this is true for
        // every ggml rung, with no file anywhere.
        val cpu = WhisperCatalog.byId("small-q8")!!
        val single = NpuAssetImport.installedGateBytes(cpu, null)
        assertNull("a single-file tier's gate has no paired half", single.paired)
        assertFalse("nothing on disk is not installed", NpuAssetImport.passesInstalledGate(single, null, null))
        assertTrue(
            "and the file at its catalog size is — the six ggml tiers keep the predicate they had",
            NpuAssetImport.passesInstalledGate(single, cpu.approxBytes, null),
        )
        // THE Q7a R14 TRAP, executed: a paired tier's primary is gated against the ENCODER's
        // bytes, never against approxBytes (the pair's sum, 67% away from the encoder alone).
        val smallGate = NpuAssetImport.installedGateBytes(small, NpuFleetCensus.artifactFor("8gen3", "npu"))
        assertEquals("the primary reference is the encoder's own bytes", small.primaryBytes, smallGate.primaryBytes)
        assertTrue(
            "so the real encoder beside the real decoder is installed",
            NpuAssetImport.passesInstalledGate(smallGate, small.primaryBytes, small.pairedArtifact!!.approxBytes),
        )
    }

    @Test
    fun theRealRefreshIsStaleOnEveryPhoneThatHeldThePreviousPair_andTheNewPairIsNot() {
        // The numbers the sweep exists for, through the one rule and the real census rows. The
        // 0.62.2 turbo pair on the Fold6 (8gen3) and the S23 Ultra (qcs8550): encoders 13.1% over
        // the v0.63.0 rows, decoders inside the band — the pair is stale, so it is REMOVED at
        // launch rather than kept as ~1 GB of dead weight. The 0.62.2 small pair (the 4.0-4.14
        // catalog record, 132,927,488 + 225,316,864) likewise.
        for ((family, tier, oldPair) in listOf(
            Triple("8gen3", "npu-turbo", 775_831_552L to 295_854_080L),
            Triple("qcs8550", "npu-turbo", 775_843_840L to 295_854_080L),
            Triple("8gen3", "npu", 132_927_488L to 225_316_864L),
        )) {
            val model = WhisperCatalog.byId(tier)!!
            val gate = NpuAssetImport.installedGateBytes(model, NpuFleetCensus.artifactFor(family, tier))
            assertFalse(
                "$family/$tier: the 0.62.2 pair ${oldPair.first}/${oldPair.second} must fail the " +
                    "v0.63.0 gate — that failure is what the sweep removes",
                NpuAssetImport.passesInstalledGate(gate, oldPair.first, oldPair.second),
            )
        }
        // And the pair the fetch lands passes on EVERY family: the sweep can never remove a
        // correct v0.63.0 install, on any census row, at its exact bytes.
        for (a in NpuFleetCensus.artifacts) {
            val model = WhisperCatalog.byId(a.tierId)!!
            assertTrue(
                "${a.familyId}/${a.tierId}: the family's own v0.63.0 pair passes its own gate",
                NpuAssetImport.passesInstalledGate(
                    NpuAssetImport.installedGateBytes(model, a), a.encoder.bytes, a.decoder.bytes,
                ),
            )
        }
    }

    @Test
    fun theDiagLineCarriesWhatWasOnDiskBesideWhatTheCensusSays() {
        val removed = NpuStalePairSweep.Removed(
            tierId = "npu-turbo",
            encoderBytes = 775_831_552L,
            decoderBytes = 295_854_080L,
            censusEncoderBytes = 686_112_520L,
            censusDecoderBytes = 295_856_032L,
            censusKey = "8gen3:c9403eaa9c4b4313419d650e316be7cc1c9020cd8cd716ed909ddb0b61f0886a",
            left = emptyList(),
        )
        assertEquals(
            "npu: stale pair removed tier=npu-turbo encoder=775831552 decoder=295854080 " +
                "census=686112520/295856032",
            NpuDiag.stalePairRemoved(removed),
        )
        assertEquals(
            "a file that refused to go is named — the line's only optional field, present only " +
                "when it happened",
            "npu: stale pair removed tier=npu-turbo encoder=775831552 decoder=295854080 " +
                "census=686112520/295856032 left=turbo_encoder_qairt_context.bin",
            NpuDiag.stalePairRemoved(removed.copy(left = listOf(turboEncoder))),
        )
        assertTrue("the house prefix", NpuDiag.stalePairRemoved(removed).startsWith("npu: "))
    }

    @Test
    fun theCensusKeyNamesTheRowAndEveryRowHasItsOwn() {
        assertEquals(
            "8gen3:c9403eaa9c4b4313419d650e316be7cc1c9020cd8cd716ed909ddb0b61f0886a",
            NpuStalePairSweep.censusKey(NpuFleetCensus.artifactFor("8gen3", "npu-turbo")!!),
        )
        val keys = NpuFleetCensus.artifacts.map { NpuStalePairSweep.censusKey(it) }
        assertEquals("twelve rows, twelve distinct events", keys.size, keys.toSet().size)
    }

    // ------------------------------------------------------------------ the record's rules

    @Test
    fun onlyAPairForTheRecordedTierClearsTheRecord() {
        val record = NpuRedownload("npu-turbo", "8gen3:abc")
        assertTrue("turbo's pair landing resolves turbo's re-download", NpuRedownload.clearedBy(record, "npu-turbo"))
        assertFalse("the other NPU tier's pair does not", NpuRedownload.clearedBy(record, "npu"))
        assertFalse("nor does a CPU download", NpuRedownload.clearedBy(record, "small-q8"))
        assertFalse("and there is nothing to clear with no record", NpuRedownload.clearedBy(null, "npu-turbo"))
    }

    @Test
    fun theRecordReadsBackAsBothHalvesOrNothing() {
        // The store as the manager drives it, with a map standing in — the
        // readCloudDisclosureAccepted seam.
        fun read(store: Map<String, String>) =
            PreferencesManager.readNpuRedownload { key, default -> store[key] ?: default }
        assertNull("an empty store has no event", read(emptyMap()))
        assertEquals(
            NpuRedownload("npu-turbo", "8gen3:abc"),
            read(
                mapOf(
                    PreferencesManager.KEY_NPU_REDOWNLOAD_TIER to "npu-turbo",
                    PreferencesManager.KEY_NPU_REDOWNLOAD_CENSUS to "8gen3:abc",
                )
            ),
        )
        assertNull(
            "a torn write — a tier with no census key — reads as no event, never as one nobody can name",
            read(mapOf(PreferencesManager.KEY_NPU_REDOWNLOAD_TIER to "npu-turbo")),
        )
        assertNull(read(mapOf(PreferencesManager.KEY_NPU_REDOWNLOAD_CENSUS to "8gen3:abc")))
    }

    @Test
    fun theRecordLivesInAStoreNoBackupCarries() {
        // The record says "the pair THIS phone held was removed". Restored onto a new phone by
        // Auto Backup or a device transfer, it would tell a fresh install its AI-chip model "has
        // a faster version — download it again". Both rule files are ALLOWLISTS, so the device-
        // local file is excluded by being named in neither.
        assertFalse(
            "the device-local store is its own file, not the backed-up one",
            PreferencesManager.DEVICE_LOCAL_PREFS == "whisper_everywhere_prefs",
        )
        for (rules in listOf("src/main/res/xml/backup_rules.xml", "src/main/res/xml/data_extraction_rules.xml")) {
            val xml = read(rules)
            assertTrue("$rules is still an allowlist of the main prefs file", xml.contains("path=\"whisper_everywhere_prefs.xml\""))
            assertFalse(
                "$rules must never name the device-local store — it would travel to a phone that never held the pair",
                xml.contains(PreferencesManager.DEVICE_LOCAL_PREFS),
            )
        }
        val prefs = read("src/main/java/com/whispereverywhere/data/local/PreferencesManager.kt")
        // Any edit chain on the BACKED-UP store that reaches one of the record's keys — across
        // line breaks, whatever else the chain writes first — and any read of one from it.
        val backedUpWrite = Regex("""\bprefs\s*\.\s*edit\(\)(\s*\.\s*\w+\([^()]*\))*\s*\.\s*\w+\(\s*KEY_NPU_""")
        val backedUpRead = Regex("""\bprefs\s*\.\s*get\w+\(\s*KEY_NPU_""")
        assertEquals(
            "every write of the record goes to the device-local store",
            emptyList<String>(),
            backedUpWrite.findAll(prefs).map { it.value }.toList(),
        )
        assertEquals(
            "and every read",
            emptyList<String>(),
            backedUpRead.findAll(prefs).map { it.value }.toList(),
        )
        assertEquals(
            "the device-local store is the one getSharedPreferences call on DEVICE_LOCAL_PREFS",
            1,
            liveLineCount(prefs, "DEVICE_LOCAL_PREFS,"),
        )
    }

    // ------------------------------------------------------------------ non-interference

    @Test
    fun theMigrationPromptAndTheSweepCanNeverMeet() {
        // ModelMigration moves a user off an UNSUPPORTED tier, and its ordering rule — download,
        // verify, switch, THEN delete — is what keeps that user dictating. The sweep deletes only
        // PAIRED tiers, so the two meet only if a paired tier is unsupported or a migration target
        // is paired. Neither is true, and both are held here so the day one becomes true is a red
        // suite, not a user whose migration source vanished at launch.
        NpuAssetImport.PAIRED_TIER_IDS.forEach { id ->
            assertFalse("the paired tier '$id' is never an unsupported (migration) tier", WhisperCatalog.byId(id)!!.unsupported)
        }
        ModelScope.values().forEach { scope ->
            val target = WhisperCatalog.byId(ModelMigration.targetIdFor(scope))!!
            assertNull("the ${scope.name} migration target is a single-file rung the sweep cannot touch", target.pairedArtifact)
        }
    }

    // ------------------------------------------------------------------ the Context-bound wiring

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

    private val manager: String by lazy {
        read("src/main/java/com/whispereverywhere/model/WhisperModelManager.kt")
    }

    private fun count(haystack: String, needle: String) = haystack.split(needle).size - 1

    private fun liveLineCount(haystack: String, needle: String): Int =
        haystack.lineSequence().count { line ->
            val trimmed = line.trimStart()
            val commented = trimmed.startsWith("//") || trimmed.startsWith("/*") ||
                trimmed.startsWith("*")
            !commented && line.contains(needle)
        }

    private fun liveIndexOfOrFail(haystack: String, what: String, needle: String): Int {
        var offset = 0
        for (line in haystack.lineSequence()) {
            val trimmed = line.trimStart()
            val commented =
                trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*")
            val at = line.indexOf(needle)
            if (!commented && at >= 0) return offset + at
            offset += line.length + 1
        }
        throw AssertionError("missing from $what as a LIVE line: <<$needle>>")
    }

    /** One member to its own closer, by the four-space rule these classes close on. */
    private fun body(haystack: String, what: String, declaration: String): String {
        val start = haystack.indexOf(declaration)
        assertTrue("missing from $what: <<$declaration>>", start >= 0)
        val close = haystack.indexOf("\n    }\n", start)
        assertTrue("the closing brace of <<$declaration>> moved", close > start)
        return haystack.substring(start, close + "\n    }\n".length)
    }

    @Test
    fun theLaunchPassRemovesStalePairsAfterTheDebrisAndOnlyWithAResolvedFamily() {
        val pass = body(manager, "WhisperModelManager.kt", "    fun reconcileNpuStagingDebris() {")
        assertEquals(
            "the family is the F2 memo, read once for the pass — never a re-derivation",
            1,
            liveLineCount(pass, "val family = (context.applicationContext as? WhisperEverywhereApp)?.npuSocFamily"),
        )
        assertEquals(
            "the stale half runs ONLY with a resolved family: an off-census device keeps what it holds",
            1,
            liveLineCount(pass, "if (model != null && family != null) removeStalePair(dir, model, family)"),
        )
        assertTrue(
            "ORDER: the debris is settled FIRST, so the stale question is asked of a settled " +
                "directory — an interrupted finalise is finished or rolled back before anything is judged",
            liveIndexOfOrFail(pass, "reconcileNpuStagingDebris", "reconcileStagingDebris(dir, names)") <
                liveIndexOfOrFail(pass, "reconcileNpuStagingDebris", "removeStalePair(dir, model, family)"),
        )
        val shell = body(
            manager, "WhisperModelManager.kt",
            "    private fun removeStalePair(dir: File, model: WhisperModel, family: NpuSocFamily) {",
        )
        assertEquals(
            "the decision is the pure sweep's, against THE DEVICE FAMILY's own row",
            1,
            liveLineCount(shell, "NpuStalePairSweep.sweep(dir, model, NpuFleetCensus.artifactFor(family.id, model.id))"),
        )
        assertEquals(
            "every removal is one npu: line, through the NATIVE export — R8 strips android.util.Log " +
                "from the release build, and the device check this line serves runs one",
            1,
            liveLineCount(shell, "runCatching { WhisperNative.diag(NpuDiag.stalePairRemoved(removed)) }"),
        )
        assertEquals(
            "and never through android.util.Log, where a Play build would print nothing",
            0,
            liveLineCount(shell, "android.util.Log"),
        )
        assertEquals(
            "and the record is written for the SELECTED tier only, through the pure rule",
            1,
            liveLineCount(
                shell,
                "NpuStalePairSweep.recordFor(removed, prefs.selectedModelId)?.let { prefs.recordNpuRedownload(it) }",
            ),
        )
        assertEquals(
            "the shell deletes nothing itself — one rule decides and deletes, and it is executed above",
            0,
            liveLineCount(shell, ".delete()"),
        )
        val sweep = read("src/main/java/com/whispereverywhere/npu/NpuStalePairSweep.kt")
        assertEquals(
            "the sweep's verdict is isInstalled's own rule, never a second copy",
            1,
            liveLineCount(sweep, "if (NpuAssetImport.passesInstalledGate(gate, primaryLength, pairedLength)) return null"),
        )
        assertEquals(
            "against the gate isInstalled builds, from the same row",
            1,
            liveLineCount(sweep, "val gate = NpuAssetImport.installedGateBytes(model, artifact)"),
        )
        assertEquals(
            "and the sweep never touches a .part — that is the reconcile's",
            0,
            liveLineCount(sweep, "PART_SUFFIX"),
        )
    }

    @Test
    fun theSharedFinaliseClearsTheRecordInItsCommittedBranchAndNowhereElse() {
        // "Cleared by the same code path that runs notifyModelInstalled when a pair lands, and by
        // a fresh import": since 4.2 F5 those are ONE path — the Play pack and the SAF import both
        // land through finalizeVerifiedPair — so one line in its committed branch clears for both,
        // and a refusal or a rollback (which leave the tier absent) can never clear it.
        val finalise = body(manager, "WhisperModelManager.kt", "    private fun finalizeVerifiedPair(")
        assertEquals(
            "the finalise clears the landed tier's record exactly once",
            1,
            liveLineCount(finalise, "prefs.clearNpuRedownload(model.id)"),
        )
        val clear = liveIndexOfOrFail(finalise, "finalizeVerifiedPair", "prefs.clearNpuRedownload(model.id)")
        assertTrue(
            "AFTER the verification and the rollback branch — a failed landing leaves the tier as " +
                "absent as it was, and its record must stand",
            liveIndexOfOrFail(finalise, "finalizeVerifiedPair", "if (finaliseFailure != null) {") < clear,
        )
        assertTrue(
            "and BEFORE the announce, so a surface re-reading on the install signal already sees it gone",
            clear < liveIndexOfOrFail(finalise, "finalizeVerifiedPair", "prefs.notifyModelInstalled()"),
        )
        val import = body(manager, "WhisperModelManager.kt", "    suspend fun importNpuAssetPair(")
        val pack = body(manager, "WhisperModelManager.kt", "    suspend fun installFromPack(")
        assertEquals(
            "neither arrival route clears on its own — one funnel, so no route can clear before " +
                "its pair is verified on disk",
            0,
            liveLineCount(import, "clearNpuRedownload") + liveLineCount(pack, "clearNpuRedownload"),
        )
        assertEquals(
            "and nothing else in the manager clears it",
            1,
            liveLineCount(manager, "clearNpuRedownload("),
        )
    }
}
