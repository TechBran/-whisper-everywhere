package com.whispereverywhere.npu

import com.whispereverywhere.model.WhisperCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE npu ASSET IMPORT'S DECISIONS (4.0, Q8) — the half that can be executed.
 *
 * `WhisperModelManager.importNpuAssetPair` needs a `Context`, a `ContentResolver` and 358 MB, so no
 * JVM test in this project can run it; its wiring is pinned as source by
 * `NpuImportWiringPinTest` and `UnsupportedTierGatePinTest`. Everything it DECIDES lives in
 * [NpuAssetImport] precisely so it can be proved here instead — the same split, for the same
 * reason, as `ModelInstallSignal` and `NpuDiag`.
 *
 * The zip-slip cases below are the point of the design rather than special cases in it: an
 * allow-list of two `equals()`-compared bare filenames makes a separator, a `..` segment and a
 * leading `/` **unrepresentable**, so they fall out as ordinary unknown names. A guard written as
 * "reject names containing `..`" would be a list of the attacks somebody thought of.
 */
class NpuAssetImportTest {

    private val npu = WhisperCatalog.byId("npu")!!
    private val required = NpuAssetImport.requiredEntriesFor(npu)

    private val encoder = "encoder_qairt_context.bin"
    private val decoder = "decoder_qairt_context.bin"
    private val encoderBytes = 132_927_488L
    private val decoderBytes = 225_316_864L

    /** `classifyEntry` at the entry's own correct size, whatever that entry is. */
    private fun classify(name: String, declared: Long = -1L) =
        NpuAssetImport.classifyEntry(required, name, declared)

    private fun assertNothingIsWritten(name: String, why: String) {
        val verdict = classify(name)
        assertFalse(
            "$why — <<$name>> must never be Accepted, or the importer writes a file the " +
                "allow-list does not know the correct size of",
            verdict is NpuAssetImport.EntryVerdict.Accept,
        )
        assertTrue(
            "$why — an entry that is not one of ours is SKIPPED and named, not fatal: the " +
                "published zip may legitimately carry a README or a checksum file, and a release " +
                "that gains one must not stop being importable",
            verdict is NpuAssetImport.EntryVerdict.Ignore,
        )
    }

    @Test
    fun theRequiredEntriesAndTheirSizesComeFromTheCatalogNotFromLiteralsInTheImporter() {
        // Asserted BOTH ways round on purpose. Against the catalog, so a tier whose files change
        // moves the importer with it; against literals, so the change is a decision somebody made
        // rather than a silent follow-on. This is the same census shape Q7a used on the entry.
        assertEquals(
            "the importer's allow-list IS the catalog's two artefacts",
            mapOf(encoder to encoderBytes, decoder to decoderBytes),
            required,
        )
        assertEquals("the primary entry is the catalog's fileName", npu.fileName, encoder)
        assertEquals(
            "the primary is held to primaryBytes — NOT approxBytes, which for a paired tier is " +
                "the SUM of both files and 63% above the encoder alone (the Q7a R14 trap)",
            npu.primaryBytes,
            required[encoder],
        )
        assertEquals(
            "the paired entry is the catalog's pairedArtifact, at its own size",
            npu.pairedArtifact!!.approxBytes,
            required[decoder],
        )
        assertEquals(
            "the two entries sum to the size the tier card advertises, so the import's progress " +
                "denominator and the badge cannot disagree",
            npu.approxBytes,
            NpuAssetImport.pairBytes(required),
        )
        assertEquals(
            "a catalog with no npu tier yields an EMPTY allow-list, which refuses every entry — " +
                "not a permissive one",
            emptyMap<String, Long>(),
            NpuAssetImport.requiredEntriesFor(null),
        )
        assertEquals(
            "a single-file tier has nothing to import: no paired artefact, no allow-list",
            emptyMap<String, Long>(),
            NpuAssetImport.requiredEntriesFor(WhisperCatalog.byId("pro")),
        )
    }

    @Test
    fun acceptsTheTwoKnownEntriesAtTheirCatalogSizes() {
        val enc = classify(encoder, encoderBytes)
        assertEquals(
            "the encoder is accepted, under its own bare name, at its own length",
            NpuAssetImport.EntryVerdict.Accept(encoder, encoderBytes),
            enc,
        )
        assertEquals(
            "and the decoder likewise — a two-artefact tier needs both halves",
            NpuAssetImport.EntryVerdict.Accept(decoder, decoderBytes),
            classify(decoder, decoderBytes),
        )
    }

    @Test
    fun rejectsPathTraversalEntry() {
        // The zip-slip archetype. It is refused for the mundane reason that it is not one of the
        // two names in the allow-list — which is exactly the guarantee wanted: the traversal is not
        // detected, it is unrepresentable.
        assertNothingIsWritten("../evil.bin", "a parent-directory escape")
        assertNothingIsWritten("../../../../data/data/com.whispereverywhere/files/x", "a deep escape")
        assertNothingIsWritten("$encoder/../../evil", "a traversal wearing a legal prefix")
    }

    @Test
    fun rejectsANestedPathEntry() {
        // Also the shape produced by zipping a FOLDER rather than the two files. It fails loudly
        // (missingEntriesRefusal names what was not found) rather than being silently accepted
        // into a subdirectory the loader would never look in.
        assertNothingIsWritten("a/b.bin", "a nested path")
        assertNothingIsWritten("models/$encoder", "the tempting one: the right file, one dir down")
    }

    @Test
    fun rejectsAnAbsolutePathEntry() {
        assertNothingIsWritten("/abs/path", "an absolute path")
        assertNothingIsWritten("/data/local/tmp/$encoder", "an absolute path ending in a legal name")
    }

    @Test
    fun rejectsAnUnknownEntry() {
        assertNothingIsWritten("README.txt", "an unrelated file riding the zip")
        assertNothingIsWritten("libQnnHtpV75Skel.so", "a plausible-looking neighbour")
        assertNothingIsWritten("", "an empty name")
        assertNothingIsWritten(encoder.uppercase(), "the right name in the wrong case")
    }

    @Test
    fun rejectsAKnownNameAtTheWrongDeclaredSize() {
        // Refuse, not Ignore: this IS one of ours, and the whole import fails rather than quietly
        // proceeding to a missing-entry error that would blame the wrong thing.
        val verdict = classify(encoder, encoderBytes - 1)
        assertTrue(
            "a known entry whose header declares the wrong length is REFUSED, before 132 MB is " +
                "inflated to learn the same thing",
            verdict is NpuAssetImport.EntryVerdict.Refuse,
        )
        val reason = (verdict as NpuAssetImport.EntryVerdict.Refuse).reason
        assertTrue("the refusal names the file: $reason", reason.contains(encoder))
        assertTrue("the refusal names what was found: $reason", reason.contains("${encoderBytes - 1}"))
        assertTrue("the refusal names what was expected: $reason", reason.contains("$encoderBytes"))
        assertTrue(
            "and it promises what the .part staging is what makes true: $reason",
            reason.contains("Nothing was installed"),
        )
        assertTrue(
            "the decoder is held to ITS size, not the encoder's — the two differ by 92 MB and a " +
                "single shared expectation would accept whichever file was checked second",
            classify(decoder, encoderBytes) is NpuAssetImport.EntryVerdict.Refuse,
        )
    }

    @Test
    fun acceptsAKnownNameWhoseZipHeaderDeclaresNoSize() {
        // ZipEntry.getSize() is -1 for an archive written as a stream. Treating that as a wrong
        // size would refuse a correctly built zip; the authoritative check is the count of bytes
        // actually written, which the manager applies after the copy.
        assertEquals(
            "an undeclared header size is deferred to the written-bytes check, not refused",
            NpuAssetImport.EntryVerdict.Accept(encoder, encoderBytes),
            classify(encoder, -1L),
        )
    }

    @Test
    fun theCanonicalPathCheckIsASecondIndependentGuard() {
        val dir = "/data/data/com.whispereverywhere/files/models"
        assertFalse(
            "the ordinary case resolves inside the models directory",
            NpuAssetImport.escapesTargetDir(dir, "$dir/$encoder", "/"),
        )
        assertTrue(
            "a destination outside it is refused even though the allow-list already passed — the " +
                "two guards fail for different reasons and neither is the other's comment",
            NpuAssetImport.escapesTargetDir(dir, "/data/data/com.whispereverywhere/files/$encoder", "/"),
        )
        assertTrue(
            "THE SEPARATOR MATTERS: without it a sibling directory whose name merely STARTS with " +
                "the target's would pass a prefix test",
            NpuAssetImport.escapesTargetDir(dir, dir + "EVIL/$encoder", "/"),
        )
        assertTrue(
            "the target directory itself is not a legal destination for a file",
            NpuAssetImport.escapesTargetDir(dir, dir, "/"),
        )
    }

    @Test
    fun theFreeSpacePrecheckRefusesAndNamesTheShortfall() {
        val pair = NpuAssetImport.pairBytes(required)
        val needed = NpuAssetImport.requiredFreeBytes(pair, pairAlreadyInstalled = false)
        assertNull(
            "there is no refusal when the space is there",
            NpuAssetImport.freeSpaceRefusal(needed, needed),
        )
        val refusal = NpuAssetImport.freeSpaceRefusal(100_000_000L, needed)
        assertNotNull("a device 294 MB short must be refused BEFORE anything is written", refusal)
        assertTrue("it names what is needed: $refusal", refusal!!.contains("${needed / 1_000_000}"))
        assertTrue("it names what there is: $refusal", refusal.contains("100"))
        assertTrue(
            "it names the SHORTFALL, which is the number that tells the user what to do: $refusal",
            refusal.contains("${(needed - 100_000_000L) / 1_000_000}"),
        )
        assertTrue("and that nothing was touched: $refusal", refusal.contains("Nothing was installed"))
    }

    @Test
    fun theTransientBudgetDoublesWhenAPairIsAlreadyInstalled() {
        val pair = NpuAssetImport.pairBytes(required)
        val fresh = NpuAssetImport.requiredFreeBytes(pair, pairAlreadyInstalled = false)
        val replacing = NpuAssetImport.requiredFreeBytes(pair, pairAlreadyInstalled = true)
        // Independently spelled arithmetic (integer, /10) rather than the implementation's own
        // expression, so the margin is asserted rather than restated.
        assertEquals("a fresh install stages one copy, plus the house 10% margin", (pair * 11) / 10, fresh)
        assertEquals(
            "REPLACING one stages a second: the installed pair stays on disk until the two " +
                "renames at the very end, and that is exactly what makes a re-import " +
                "non-destructive when the new zip turns out to be bad",
            (pair * 2 * 11) / 10,
            replacing,
        )
        assertTrue("so the budget is strictly larger when replacing", replacing > fresh)
        assertTrue("and a fresh install never demands the doubled budget", fresh < pair * 2)
    }

    @Test
    fun aMissingHalfOfThePairIsRefusedByName() {
        assertNull(
            "both halves present is the only silent outcome",
            NpuAssetImport.missingEntriesRefusal(required.keys, setOf(encoder, decoder)),
        )
        val refusal = NpuAssetImport.missingEntriesRefusal(required.keys, setOf(encoder))
        assertNotNull(
            "AN ENCODER WITHOUT ITS DECODER IS NOT A DEGRADED TIER, it is one that arms halfway " +
                "and fails inside nativeInit. Both or neither.",
            refusal,
        )
        assertTrue("the refusal names the file that was missing: $refusal", refusal!!.contains(decoder))
        assertFalse(
            "and does not name the one that arrived, which would send the owner looking in the " +
                "wrong place: $refusal",
            refusal.contains(encoder),
        )
        assertNotNull(
            "an empty zip is missing both, and says so",
            NpuAssetImport.missingEntriesRefusal(required.keys, emptySet()),
        )
    }

    @Test
    fun theImportGateIsStrictlyStricterThanTheInstalledPredicate() {
        // The direction matters. `isInstalled` is tolerance-based (±5%) because it must judge files
        // this app did not write; the import writes them and demands the exact published length.
        // Strict-inside-tolerant is the safe nesting: an import that reports success can never
        // leave `isInstalled` false, so the card cannot hide the tier the user just imported.
        required.forEach { (name, bytes) ->
            assertTrue(
                "everything the import accepts, isInstalled accepts ($name)",
                WhisperCatalog.sizeWithinTolerance(bytes, bytes),
            )
        }
        val offByOne = encoderBytes + 1
        assertTrue(
            "the tolerant predicate would have accepted this file",
            WhisperCatalog.sizeWithinTolerance(offByOne, encoderBytes),
        )
        assertTrue(
            "and the import refuses it anyway — the nesting is STRICT, not merely equal",
            classify(encoder, offByOne) is NpuAssetImport.EntryVerdict.Refuse,
        )
    }

    @Test
    fun everyDiagnosticLineIsGreppableAndCarriesNoContent() {
        assertEquals(
            "the refusal line is one contiguous greppable prefix",
            "npu: import refused reason=out of space",
            NpuAssetImport.refusedLine("out of space"),
        )
        assertEquals(
            "the success landmark states counts and bytes, which is all it may state",
            "npu: import ok entries=2 bytes=358244352",
            NpuAssetImport.okLine(2, 358_244_352L),
        )
        // Entry names come out of a file the user picked, so they are bounded before they are
        // echoed into a log line or a card.
        val hostile = "x".repeat(500)
        val ignored = classify(hostile) as NpuAssetImport.EntryVerdict.Ignore
        assertTrue(
            "a hostile entry name is truncated before it is echoed: ${ignored.reason.length} chars",
            ignored.reason.length < 200,
        )
    }
}
