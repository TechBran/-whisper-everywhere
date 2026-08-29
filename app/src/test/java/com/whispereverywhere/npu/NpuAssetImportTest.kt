package com.whispereverywhere.npu

import com.whispereverywhere.model.WhisperCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * THE PAIRED TIERS' ASSET IMPORT DECISIONS (4.0 Q8; per-tier and sha256-verified at 4.1 L6) —
 * the half that can be executed.
 *
 * `WhisperModelManager.importNpuAssetPair` needs a `Context`, a `ContentResolver` and up to
 * ~1.07 GB, so no JVM test in this project can run it; its UI wiring is pinned as source by
 * `NpuImportWiringPinTest` and its transaction shape by `UnsupportedTierGatePinTest`. Everything
 * it DECIDES lives in [NpuAssetImport] precisely so it can be proved here instead — the same
 * split, for the same reason, as `ModelInstallSignal` and `NpuDiag`. Since L6 this class also
 * carries a handful of SOURCE pins over the manager's import body, because the digest work is a
 * property of the copy loop itself — where the digest is computed and where it is checked are
 * ORDER facts about `Context`-bound code that no pure function can witness.
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

    // The npu pair's MEASURED digests (the spike staged and hashed the extracted files) — restated
    // here as literals so the catalog moving is a decision somebody made, not a silent follow-on.
    private val encoderSha = "3e92ac26545b6b9d22ecfab594ae57523134006e2722b09fa10e16b193e9e5ec"
    private val decoderSha = "fda23d731e6b0ab7fb0a50373a49efe2d1792faa5dad456837624d8b8e44b0e4"

    /** `classifyEntry` at the entry's own correct size, with nothing accepted yet. */
    private fun classify(name: String, declared: Long = -1L) =
        NpuAssetImport.classifyEntry(required, name, declared, alreadyAccepted = emptySet())

    // ------------------------------------------------------------------ source-pin helpers
    // The house locator + the live-line discipline (comments can neither satisfy nor break a pin).

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

    private val app: String by lazy {
        read("src/main/java/com/whispereverywhere/WhisperEverywhereApp.kt")
    }

    private val importObject: String by lazy {
        read("src/main/java/com/whispereverywhere/npu/NpuAssetImport.kt")
    }

    private fun count(haystack: String, needle: String) = haystack.split(needle).size - 1

    private fun lines(vararg text: String) = text.joinToString("\n")

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

    private val importBody: String by lazy {
        body(manager, "WhisperModelManager.kt", "    suspend fun importNpuAssetPair(")
    }

    // ------------------------------------------------------------------ the catalog census

    @Test
    fun theRequiredEntriesAndTheirSizesComeFromTheCatalogNotFromLiteralsInTheImporter() {
        // Asserted BOTH ways round on purpose. Against the catalog, so a tier whose files change
        // moves the importer with it; against literals, so the change is a decision somebody made
        // rather than a silent follow-on. This is the same census shape Q7a used on the entry —
        // extended at L6 to the digests, which ride the same map for the same one-home reason.
        assertEquals(
            "the importer's allow-list IS the catalog's two artefacts, each with its own exact " +
                "length AND its own measured digest",
            mapOf(
                encoder to NpuAssetImport.RequiredEntry(encoderBytes, encoderSha),
                decoder to NpuAssetImport.RequiredEntry(decoderBytes, decoderSha),
            ),
            required,
        )
        assertEquals("the primary entry is the catalog's fileName", npu.fileName, encoder)
        assertEquals(
            "the primary is held to primaryBytes — NOT approxBytes, which for a paired tier is " +
                "the SUM of both files and 63% above the encoder alone (the Q7a R14 trap)",
            npu.primaryBytes,
            required[encoder]!!.bytes,
        )
        assertEquals(
            "the paired entry is the catalog's pairedArtifact, at its own size",
            npu.pairedArtifact!!.approxBytes,
            required[decoder]!!.bytes,
        )
        assertEquals(
            "the primary's digest is the catalog's own",
            npu.sha256,
            required[encoder]!!.sha256,
        )
        assertEquals(
            "and the paired digest is the pairedArtifact's own — never the primary's restated",
            npu.pairedArtifact!!.sha256,
            required[decoder]!!.sha256,
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
            emptyMap<String, NpuAssetImport.RequiredEntry>(),
            NpuAssetImport.requiredEntriesFor(null),
        )
        assertEquals(
            "a single-file tier has nothing to import: no paired artefact, no allow-list",
            emptyMap<String, NpuAssetImport.RequiredEntry>(),
            NpuAssetImport.requiredEntriesFor(WhisperCatalog.byId("pro")),
        )
    }

    @Test
    fun everyPairedTierImportsItsOwnTwoEntriesWithDistinctDigests() {
        // 4.1 L6. The map is per-TIER: turbo's ~GB entries flow through the same derivation the
        // moment its id is passed, under the REPACKED turbo_* names — never the vendor names,
        // which are npu's installed files (the L5 handoff's exact warning).
        val digestsSeen = mutableListOf<String>()
        NpuAssetImport.PAIRED_TIER_IDS.forEach { id ->
            val model = WhisperCatalog.byId(id)
            assertNotNull("PAIRED_TIER_IDS must resolve in the catalog: $id", model)
            val entries = NpuAssetImport.requiredEntriesFor(model)
            val paired = model!!.pairedArtifact!!
            assertEquals("a pair is exactly two entries ($id)", 2, entries.size)
            assertEquals(
                "the primary is held to ITS exact bytes ($id)",
                model.primaryBytes,
                entries[model.fileName]!!.bytes,
            )
            assertEquals(
                "…and ITS digest ($id)",
                model.sha256,
                entries[model.fileName]!!.sha256,
            )
            assertEquals(
                "the paired file is held to ITS exact bytes ($id)",
                paired.approxBytes,
                entries[paired.fileName]!!.bytes,
            )
            assertEquals(
                "…and ITS digest ($id)",
                paired.sha256,
                entries[paired.fileName]!!.sha256,
            )
            assertTrue(
                "the two digests of a pair are DISTINCT ($id) — a copy-paste between the two " +
                    "would let a transposed encoder/decoder verify as published",
                entries[model.fileName]!!.sha256 != entries[paired.fileName]!!.sha256,
            )
            digestsSeen += entries.values.map { it.sha256 }
        }
        assertEquals(
            "all four digests across both tiers are pairwise distinct — the same claim the " +
                "catalog's own distinctness census makes, read from the importer's side",
            digestsSeen.size,
            digestsSeen.toSet().size,
        )
    }

    @Test
    fun thePairedTierIdsAreDerivedFromTheCatalogNotWrittenOut() {
        // Both ways round, like every census here: the exact value, so a tier arriving or leaving
        // is announced; and the derivation, so the list can never be a literal that a ninth tier
        // has to be remembered into.
        assertEquals(
            "exactly the two paired tiers, in catalog order",
            listOf("npu", "npu-turbo"),
            NpuAssetImport.PAIRED_TIER_IDS,
        )
        assertEquals(
            "and the list IS the catalog derivation — same filter, same projection",
            WhisperCatalog.entries.filter { it.pairedArtifact != null }.map { it.id },
            NpuAssetImport.PAIRED_TIER_IDS,
        )
        assertEquals(
            "the derivation is written STRUCTURALLY in NpuAssetImport, on a live line — a " +
                "hand-maintained list would be one more thing to remember for the next npu-class " +
                "tier, which is the exact defect the structural mel-donor clause already closed",
            1,
            liveLineCount(
                importObject,
                "WhisperCatalog.entries.filter { it.pairedArtifact != null }.map { it.id }",
            ),
        )
        assertEquals(
            "and no live line of NpuAssetImport.kt writes the turbo id out as a literal",
            0,
            liveLineCount(importObject, "\"npu-turbo\""),
        )
    }

    // ------------------------------------------------------------------ classifyEntry

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
    fun acceptsTheTwoKnownEntriesAtTheirCatalogSizes() {
        val enc = classify(encoder, encoderBytes)
        assertEquals(
            "the encoder is accepted, under its own bare name, at its own length, carrying its " +
                "own digest for the copy to stream against",
            NpuAssetImport.EntryVerdict.Accept(encoder, encoderBytes, encoderSha),
            enc,
        )
        assertEquals(
            "and the decoder likewise — a two-artefact tier needs both halves",
            NpuAssetImport.EntryVerdict.Accept(decoder, decoderBytes, decoderSha),
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
    fun theVendorZipShapeIsRefusedForBothTiers_prefixAndSharedBareNames() {
        // 4.1 L8 — the two MEASURED facts the delivery repack exists for, pinned from the
        // importer's side so nobody "helpfully" relaxes the allow-list to accept them:
        //
        //  (1) The AI Hub zips carry a top-level DIRECTORY prefix. A prefixed name cannot be
        //      equals()-equal to a bare allow-list name, so the vendor zip AS DOWNLOADED imports
        //      nothing — every entry Ignored, then the missing-entries refusal. Loud, not silent.
        val turbo = WhisperCatalog.byId("npu-turbo")!!
        val turboRequired = NpuAssetImport.requiredEntriesFor(turbo)
        val vendorPrefix =
            "whisper_large_v3_turbo_quantized-precompiled_qnn_onnx-w8a16-qualcomm_snapdragon_8gen3"
        listOf("encoder_qairt_context.bin", "decoder_qairt_context.bin").forEach { bare ->
            val verdict = NpuAssetImport.classifyEntry(
                turboRequired, "$vendorPrefix/$bare", declaredBytes = -1L, alreadyAccepted = emptySet(),
            )
            assertTrue(
                "a vendor entry under its directory prefix must be Ignored, never Accepted — " +
                    "accepting a path-carrying name is the exact allow-list relaxation that " +
                    "would reopen zip-slip AND install under a name isInstalled never checks",
                verdict is NpuAssetImport.EntryVerdict.Ignore,
            )
        }
        //  (2) Both families' entries carry the SAME bare names, and those names are the npu
        //      (small) tier's INSTALLED files. Against TURBO's allow-list the vendor bare names
        //      are strangers — because the catalog names turbo's files turbo_*. If this verdict
        //      ever became Accept, a turbo import would write over the owner's 358 MB npu pair.
        listOf("encoder_qairt_context.bin", "decoder_qairt_context.bin").forEach { npuName ->
            assertTrue(
                "the vendor bare name '$npuName' is npu's file and must be a STRANGER to " +
                    "turbo's import — the rename to turbo_* is what keeps the two pairs apart " +
                    "in the one directory both live in",
                NpuAssetImport.classifyEntry(
                    turboRequired, npuName, declaredBytes = -1L, alreadyAccepted = emptySet(),
                ) is NpuAssetImport.EntryVerdict.Ignore,
            )
        }
        // And the delivery names really are distinct across the two tiers — all four, the
        // no-overwrite invariant stated as a set cardinality.
        val allFour = NpuAssetImport.PAIRED_TIER_IDS.flatMap { id ->
            NpuAssetImport.requiredEntriesFor(WhisperCatalog.byId(id)).keys
        }
        assertEquals(
            "four delivery filenames across the two tiers, pairwise distinct — a collision is " +
                "an import that destroys the other tier's installed half",
            4,
            allFour.toSet().size,
        )
    }

    @Test
    fun thePackScriptCarriesBothTiersFourFilenamesAndAllFourDigests() {
        // 4.1 L8. tools/pack_npu_zip.py builds the delivery zips (prefix stripped, turbo's
        // entries renamed) and re-verifies its own output with the importer's logic — so ITS
        // table is a fourth reading of the catalog's values (catalog, this test's literals, the
        // importer map, the script), and the same both-ways census applies: the script must name
        // every delivery filename and every digest EXACTLY, as literals, so a catalog move is a
        // decision that shows up here rather than a repack that quietly re-hashes a ~GB artefact.
        val script = read("tools/pack_npu_zip.py")
        listOf(
            "encoder_qairt_context.bin",
            "decoder_qairt_context.bin",
            "turbo_encoder_qairt_context.bin",
            "turbo_decoder_qairt_context.bin",
        ).forEach { name ->
            assertTrue(
                "the pack script must name the delivery file '$name'",
                script.contains("\"$name\""),
            )
        }
        listOf(
            encoderSha,
            decoderSha,
            WhisperCatalog.byId("npu-turbo")!!.sha256,
            WhisperCatalog.byId("npu-turbo")!!.pairedArtifact!!.sha256,
        ).forEach { sha ->
            assertTrue(
                "the pack script must carry the catalog digest $sha as a literal — its " +
                    "self-verification is only worth the digests it checks against",
                script.contains("\"$sha\""),
            )
        }
        listOf("132_927_488", "225_316_864", "775_831_552", "295_854_080").forEach { bytes ->
            assertTrue(
                "and the exact byte length $bytes — the size half of the same gate",
                script.contains(bytes),
            )
        }
        // THE PAIRING, not just the presence — found by designing the battery: a script whose
        // tuples swapped two digests (or two lengths) still CONTAINS all four of each, so the
        // loops above stay green while the repack would refuse every correct vendor zip (or,
        // worse for a future edit, verify a wrong one). Each (name, bytes, sha) triple is pinned
        // as one block, indentation and trailing commas included.
        listOf(
            Triple(encoder, "132_927_488", encoderSha),
            Triple(decoder, "225_316_864", decoderSha),
            Triple(
                "turbo_encoder_qairt_context.bin", "775_831_552",
                WhisperCatalog.byId("npu-turbo")!!.sha256,
            ),
            Triple(
                "turbo_decoder_qairt_context.bin", "295_854_080",
                WhisperCatalog.byId("npu-turbo")!!.pairedArtifact!!.sha256,
            ),
        ).forEach { (name, bytes, sha) ->
            assertEquals(
                "the script pairs $name with ITS bytes and ITS digest in one tuple",
                1,
                count(
                    script,
                    lines(
                        "            \"$name\",",
                        "            $bytes,",
                        "            \"$sha\",",
                    ),
                ),
            )
        }
        assertTrue(
            "the script verifies its own output through the importer-equivalent walk — the " +
                "function is named for it and a rename here is a review flag",
            script.contains("def verify_like_the_importer("),
        )
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
            NpuAssetImport.EntryVerdict.Accept(encoder, encoderBytes, encoderSha),
            classify(encoder, -1L),
        )
    }

    @Test
    fun aDuplicateAllowedNameIsRefusedOutright() {
        // 4.1 L6, Q8 M3. A duplicate allowed entry used to double-count `written`, corrupting
        // `npu: import ok entries=2 bytes=…` — the one number the run-book greps as the success
        // landmark — and re-opening the same .part truncated the first copy's bytes under it.
        // With a digest per entry, a second copy of a name is a repack fault, refused by name.
        val verdict =
            NpuAssetImport.classifyEntry(required, encoder, encoderBytes, setOf(encoder))
        assertTrue(
            "a second copy of an allowed name is REFUSED — not skipped, which would silently " +
                "drop bytes, and not re-written, which double-counts the landmark",
            verdict is NpuAssetImport.EntryVerdict.Refuse,
        )
        val reason = (verdict as NpuAssetImport.EntryVerdict.Refuse).reason
        assertTrue("it names the file: $reason", reason.contains(encoder))
        assertTrue("it says the name appeared twice: $reason", reason.contains("twice"))
        assertTrue("and that nothing was installed, which is true: $reason", reason.contains("Nothing was installed"))
        assertTrue(
            "the decoder duplicates refuse identically",
            NpuAssetImport.classifyEntry(required, decoder, decoderBytes, setOf(decoder))
                is NpuAssetImport.EntryVerdict.Refuse,
        )
        assertTrue(
            "the allow-list is still consulted FIRST: an ignored rider appearing twice stays " +
                "Ignore both times — the duplicate rule is about OUR names, whose bytes count",
            NpuAssetImport.classifyEntry(required, "README.txt", -1L, setOf("README.txt"))
                is NpuAssetImport.EntryVerdict.Ignore,
        )
        // And the manager threads the LIVE accepted set — a call site passing emptySet() would
        // re-open the hazard with every assertion above still green, which is why the parameter
        // has no default and why this needle exists.
        assertEquals(
            "the manager's classify call passes the accepted-so-far set",
            1,
            liveLineCount(
                importBody,
                "NpuAssetImport.classifyEntry(required, entry.name, entry.size, accepted)",
            ),
        )
    }

    // ------------------------------------------------------------------ the digest verdicts

    @Test
    fun aCorrectlySizedEntryWithTheWrongDigestIsRefused() {
        // 4.1 L6 — the verification the two ~GB pairs arrive through. Size alone verified nothing
        // but truncation; a re-exported asset, a corrupted download or a substituted file all
        // arrive at the right length.
        val wrong = "f".repeat(64)
        val refusal = NpuAssetImport.wrongDigestRefusal(encoder, encoderSha, wrong)
        assertNotNull(
            "a correct name at the correct size with the WRONG digest must refuse",
            refusal,
        )
        assertTrue("the refusal names the file: $refusal", refusal!!.contains(encoder))
        assertTrue(
            "it names the digest it GOT, labelled as what the file hashed to: $refusal",
            refusal.contains("is $wrong"),
        )
        assertTrue(
            "and the digest it EXPECTED, labelled truthfully — a swapped pair of hashes would " +
                "send the owner comparing the wrong value against the release page: $refusal",
            refusal.contains("expected $encoderSha"),
        )
        assertTrue("and that nothing was installed, which the .part staging makes true: $refusal",
            refusal.contains("Nothing was installed"))
        assertFalse(
            "the refusal carries NO path — the name is an allow-listed bare name and nothing " +
                "else may leak into user-visible card copy: $refusal",
            refusal.contains("/") || refusal.contains("\\"),
        )
        assertNull(
            "a matching digest is silent — null is the pass value, like freeSpaceRefusal's",
            NpuAssetImport.wrongDigestRefusal(encoder, encoderSha, encoderSha),
        )
        // The refusal must be REACHABLE, and reachable at the right point: immediately after the
        // written-bytes check and BEFORE the entry counts as accepted. Below `accepted +=` a
        // hash-failed entry would satisfy missingEntriesRefusal and reach the finalise; above the
        // size check it would hash-refuse a truncated file that has a clearer size story. Between
        // the two, a digest failure leaves through exactly the refusal-and-rollback door a size
        // failure does — the .part dies in the finally, nothing has been parked, and a previously
        // installed pair is untouched.
        val sizeCheck = liveIndexOfOrFail(
            importBody, "importNpuAssetPair", "if (got != accept.expectedBytes) {"
        )
        val digestCheck = liveIndexOfOrFail(
            importBody, "importNpuAssetPair", "NpuAssetImport.wrongDigestRefusal("
        )
        val acceptedAdd = liveIndexOfOrFail(
            importBody, "importNpuAssetPair", "accepted += accept.fileName"
        )
        assertTrue(
            "ORDER: size check ($sizeCheck) -> digest check ($digestCheck) -> accepted " +
                "($acceptedAdd). Presence cannot see this — every statement survives any " +
                "permutation, and two of the three permutations are live defects.",
            sizeCheck < digestCheck && digestCheck < acceptedAdd,
        )
        // And the call hands the builder (expected, got) IN THAT ORDER. Swapped, every label in
        // the refusal lies — "expected <what the file hashed to>" — the equality still refuses,
        // every other assertion here stays green, and the owner compares the wrong value against
        // the release page. Found by designing the battery: this was the one mutation with no
        // killer.
        assertEquals(
            "the call site's argument order matches the builder's labels",
            1,
            count(importBody, "accept.fileName, accept.expectedSha256, hexOf(digest.digest())"),
        )
    }

    @Test
    fun theDigestIsStreamedDuringTheCopyNeverASecondRead() {
        // The digest is computed from the same buffers the write takes. A second pass over a
        // 776 MB entry would double the import's I/O to learn what the first pass already knew —
        // and it would verify what LANDED rather than what ARRIVED, which differs exactly when it
        // matters. Source-pinned because this is a property of the Context-bound copy loop.
        assertEquals(
            "one MessageDigest per entry, created in the import body",
            1,
            liveLineCount(importBody, "MessageDigest.getInstance(\"SHA-256\")"),
        )
        val write = liveIndexOfOrFail(importBody, "importNpuAssetPair", "out.write(buffer, 0, n)")
        val update = liveIndexOfOrFail(importBody, "importNpuAssetPair", "digest.update(buffer, 0, n)")
        val counted = liveIndexOfOrFail(importBody, "importNpuAssetPair", "got += n")
        assertTrue(
            "the digest update sits INSIDE the copy loop, fed the exact buffer slice the write " +
                "took (write=$write, update=$update, got+=$counted)",
            write < update && update < counted,
        )
        assertEquals(
            "and the import NEVER re-reads a staged file to hash it — sha256HexFile is the " +
                "download path's whole-file second read, and it must appear nowhere here",
            0,
            liveLineCount(importBody, "sha256HexFile"),
        )
    }

    // ------------------------------------------------------------------ per-tier resolution

    @Test
    fun theImportResolvesTheTierItWasAskedFor() {
        // 4.1 L6. The signature takes the tier id and EVERYTHING resolves off it — the allow-list
        // names, both lengths, both digests, the free-space budget, the debris sweep and the
        // rollback report. A fallback to the npu constant anywhere in the body is a turbo zip
        // installed (or rolled back, or reported) under the wrong tier's names.
        assertEquals(
            "the signature takes the tier id first",
            1,
            count(
                importBody,
                lines(
                    "    suspend fun importNpuAssetPair(",
                    "        tierId: String,",
                    "        source: Uri,",
                ),
            ),
        )
        assertEquals(
            "the catalog is asked for the ARGUMENT",
            1,
            liveLineCount(importBody, "WhisperCatalog.byId(tierId)"),
        )
        assertEquals(
            "and the npu constant appears on NO live line of the import body",
            0,
            liveLineCount(importBody, "NpuAssetImport.TIER_ID"),
        )
        assertEquals(
            "the debris reconciliation is scoped to THIS tier's names",
            1,
            liveLineCount(importBody, "reconcileStagingDebris(dir, required.keys)"),
        )
        assertEquals(
            "and the rollback reports THIS tier's files — it used to read the npu constant's " +
                "names, which for a turbo import would report the wrong tier's files as the " +
                "device's state",
            1,
            liveLineCount(
                importBody,
                "rollBackFinalise(finaliseFailure, required.keys, renamed, parked)",
            ),
        )
    }

    @Test
    fun theLaunchSweepSettlesEveryPairedTiersDebris() {
        // 4.1 L6, Q8 M1 + m4. Stale .prev/.part debris used to be swept ONLY from inside a
        // running import, so a process death between the park and the rename left isInstalled
        // false with the primary parked under .prev: the tier silently vanished from the chooser
        // and nothing on screen explained why — until the owner happened to start ANOTHER import
        // of the same tier. One call from Application.onCreate closes both halves (the .prev
        // disappearance and the .part orphan that makes the StatFs precheck count reusable space
        // as unavailable).
        assertEquals(
            "the app sweeps at launch, and a sweep failure can never cost the launch — the same " +
                "promise configureFastRpcLibraryPath documents",
            1,
            liveLineCount(app, "runCatching { whisperModelManager.reconcileNpuStagingDebris() }"),
        )
        assertTrue(
            "ORDER: the sweep runs after the manager it needs exists (preferencesManager feeds " +
                "the lazy WhisperModelManager)",
            liveIndexOfOrFail(app, "WhisperEverywhereApp", "preferencesManager = PreferencesManager(this)") <
                liveIndexOfOrFail(
                    app, "WhisperEverywhereApp",
                    "runCatching { whisperModelManager.reconcileNpuStagingDebris() }",
                ),
        )
        val sweep = body(
            manager, "WhisperModelManager.kt", "    fun reconcileNpuStagingDebris() {"
        )
        assertEquals(
            "the sweep covers EVERY paired tier, structurally — the next npu-class tier is swept " +
                "by the clause that already sweeps these two",
            1,
            liveLineCount(sweep, "NpuAssetImport.PAIRED_TIER_IDS.forEach"),
        )
        assertEquals(
            "and it settles each tier through the SAME reconciliation the import itself uses — " +
                "a second rule would be a second chance to synthesize a mixed pair",
            1,
            liveLineCount(sweep, "reconcileStagingDebris(dir, names)"),
        )
    }

    // ------------------------------------------------------------------ the second guard

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

    // ------------------------------------------------------------------ free space

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
    fun theFreeSpacePrecheckScalesToTurbosPair() {
        // 4.1 L6. The precheck's numbers are the TIER's: turbo's pair is 1,071,685,632 B, so the
        // replace-an-installed-pair transient is ~2.14 GB on disk and the budget ~2.36 GB with
        // the house margin. A precheck still budgeting npu's 358 MB would pass a device that the
        // renames then fill mid-import — the exact too-late failure the precheck exists to move
        // before the first byte.
        val turbo = WhisperCatalog.byId("npu-turbo")!!
        val entries = NpuAssetImport.requiredEntriesFor(turbo)
        val pair = NpuAssetImport.pairBytes(entries)
        assertEquals(
            "turbo's pair is the sum of its two published lengths",
            1_071_685_632L,
            pair,
        )
        val fresh = NpuAssetImport.requiredFreeBytes(pair, pairAlreadyInstalled = false)
        val replacing = NpuAssetImport.requiredFreeBytes(pair, pairAlreadyInstalled = true)
        assertEquals("one staged copy plus the margin", (pair * 11) / 10, fresh)
        assertEquals("the replace transient doubles it", (pair * 2 * 11) / 10, replacing)
        assertTrue(
            "the doubled budget covers the real ~2.14 GB transient of pair + parked pair",
            replacing > pair * 2,
        )
        val refusal = NpuAssetImport.freeSpaceRefusal(1_000_000_000L, replacing)
        assertNotNull("a 1 GB-free device replacing a turbo pair must refuse", refusal)
        assertTrue("it names the ~2.36 GB it needs: $refusal", refusal!!.contains("${replacing / 1_000_000}"))
        assertTrue("what the device has: $refusal", refusal.contains("1000"))
        assertTrue(
            "and the shortfall in real figures: $refusal",
            refusal.contains("${(replacing - 1_000_000_000L) / 1_000_000}"),
        )
    }

    // ------------------------------------------------------------------ both-or-neither

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
        required.forEach { (name, entry) ->
            assertTrue(
                "everything the import accepts, isInstalled accepts ($name)",
                WhisperCatalog.sizeWithinTolerance(entry.bytes, entry.bytes),
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
    fun anEntryThatKeepsProducingDataPastItsDeclaredLengthIsNamedAsSuch() {
        // Fix round 1, I1. The refusal that has to be reachable MID-COPY: a header size of -1 is
        // legal and a stated size can be a lie, so a copy bounded only by "check the total
        // afterwards" fills the device and is then refused for being the wrong size — a true
        // statement made far too late.
        val refusal = NpuAssetImport.overLengthRefusal(encoder, encoderBytes)
        assertTrue("it names the file: $refusal", refusal.contains(encoder))
        assertTrue(
            "it says WHY in the words the reviewer asked for, so the log and the card agree with " +
                "the review that found it: $refusal",
            refusal.contains("entry larger than declared"),
        )
        assertTrue("it names the length that was exceeded: $refusal", refusal.contains("$encoderBytes"))
        assertTrue(
            "and it is distinguishable from the wrong-size refusal, which is about a file that " +
                "ENDED at the wrong length rather than one that would not end",
            refusal != NpuAssetImport.wrongSizeRefusal(encoder, encoderBytes + 1, encoderBytes),
        )
        assertTrue("nothing was installed, and that is true: $refusal", refusal.contains("Nothing was installed"))
    }

    // ------------------------------------------------------------------ the finalise messages

    @Test
    fun aRolledBackFinaliseSaysThePreviousPairSurvivedAndAFailedRollbackSaysWhatIsLeft() {
        // Fix round 1, I2. The two messages differ in the one way that matters: only ONE of them
        // may promise that nothing changed.
        val what = "The imported files could not be moved into place"
        val rolledBack = NpuAssetImport.rolledBackRefusal(what)
        assertTrue("the rolled-back message names the step: $rolledBack", rolledBack.contains(what))
        assertTrue(
            "and promises the previous pair is intact — which the parking is what makes true: " +
                rolledBack,
            rolledBack.contains("previously installed model pair is unchanged"),
        )

        val stranded = NpuAssetImport.rollbackFailedRefusal(what, listOf(encoder), listOf(decoder))
        assertFalse(
            "THE ONE MESSAGE THAT MAY NOT SAY IT. Something WAS changed on this path, and a user " +
                "told otherwise while their working tier is half gone cannot even describe the " +
                "problem: $stranded",
            stranded.contains("unchanged") || stranded.contains("Nothing was installed"),
        )
        assertTrue("it names what is still there: $stranded", stranded.contains(encoder))
        assertTrue("and what is gone: $stranded", stranded.contains(decoder))
        assertTrue("and what to do about it: $stranded", stranded.contains("again"))

        val nothingLeft = NpuAssetImport.rollbackFailedRefusal(what, emptyList(), listOf(encoder, decoder))
        assertTrue(
            "an empty live-list reads as a sentence rather than as a blank: $nothingLeft",
            nothingLeft.contains("neither model file is present"),
        )
    }

    @Test
    fun aRollBackOnADeviceWithNoPreviousPairSaysSoInsteadOfReassuringAboutOne() {
        // Micro-round 2, N2. On a FIRST import there is nothing parked, so the "your previously
        // installed model pair is unchanged" wording is a sentence about something that never
        // existed — disorienting exactly when the user is least able to judge it.
        val what = "The imported files did not verify on disk"
        val fresh = NpuAssetImport.rolledBackFreshRefusal(what)
        assertTrue("it names the step: $fresh", fresh.contains(what))
        assertTrue("it is honest that nothing was installed: $fresh", fresh.contains("Nothing was installed"))
        assertTrue(
            "and it does not invent a previous install to reassure the user about: $fresh",
            fresh.contains("had no model pair before"),
        )
        assertFalse(
            "so it must not carry the had-a-pair wording",
            fresh.contains("previously installed model pair is unchanged"),
        )
        assertTrue(
            "the two roll-back messages are genuinely different sentences, not one with a tweak",
            fresh != NpuAssetImport.rolledBackRefusal(what),
        )
    }

    @Test
    fun anInterruptedFinaliseIsFinishedInONEDirectionAndNeverHalf() {
        // Micro-round 2, N3 — the semantic, executed. The per-file rule this replaces could
        // SYNTHESIZE a pair nothing ever wrote: process death between the two phase-2 renames left
        // dest1=new + prev1 + prev2 + part2, and deciding each name alone dropped prev1 and
        // restored prev2 — a new encoder beside an old decoder, with isInstalled true.
        val names = setOf(encoder, decoder)

        assertEquals(
            "nothing parked means no finalise was interrupted, whatever else is lying around",
            NpuAssetImport.Reconcile.NOTHING,
            NpuAssetImport.reconcileDecision(names, parked = emptySet(), movedIn = names),
        )
        assertEquals(
            "both new files landed: the pair on disk IS the new pair and is internally consistent, " +
                "so the transaction is finished FORWARD",
            NpuAssetImport.Reconcile.COMPLETE_FORWARD,
            NpuAssetImport.reconcileDecision(names, parked = names, movedIn = names),
        )
        assertEquals(
            "THE ROW THAT MATTERS: one landed and one did not, which is the state that used to " +
                "become a mixed pair. It is finished in the ROLL-BACK direction instead",
            NpuAssetImport.Reconcile.ROLL_BACK,
            NpuAssetImport.reconcileDecision(names, parked = names, movedIn = setOf(encoder)),
        )
        assertEquals(
            "phase 2 had not started at all: roll back, which restores both parked files",
            NpuAssetImport.Reconcile.ROLL_BACK,
            NpuAssetImport.reconcileDecision(names, parked = names, movedIn = emptySet()),
        )
        assertEquals(
            "and an interrupted PHASE 1 — only one file parked — is a roll-back too, never a " +
                "forward completion that would delete an original this import never placed",
            NpuAssetImport.Reconcile.ROLL_BACK,
            NpuAssetImport.reconcileDecision(names, parked = setOf(decoder), movedIn = setOf(encoder)),
        )
        assertEquals(
            "an empty tier cannot be 'complete' by vacuous truth — that would drop parked files " +
                "with nothing having landed",
            NpuAssetImport.Reconcile.ROLL_BACK,
            NpuAssetImport.reconcileDecision(emptySet(), parked = setOf(encoder), movedIn = emptySet()),
        )
    }

    @Test
    fun theStagingSuffixesAreDistinctSoNeitherSweepCanEatTheOther() {
        // `.part` is this import's own in-progress write and is always safe to delete; `.prev` is
        // the user's PREVIOUS installed file and may be the only copy on the device. Collapsing
        // them to one suffix would make the failure-path cleanup delete the thing it exists to
        // preserve.
        assertEquals(".part", NpuAssetImport.PART_SUFFIX)
        assertEquals(".prev", NpuAssetImport.PREVIOUS_SUFFIX)
        assertTrue(
            "the two suffixes are different, and neither is a suffix of the other",
            NpuAssetImport.PART_SUFFIX != NpuAssetImport.PREVIOUS_SUFFIX &&
                !NpuAssetImport.PART_SUFFIX.endsWith(NpuAssetImport.PREVIOUS_SUFFIX) &&
                !NpuAssetImport.PREVIOUS_SUFFIX.endsWith(NpuAssetImport.PART_SUFFIX),
        )
        required.keys.forEach { name ->
            assertFalse(
                "and no staging name collides with a real entry name ($name)",
                required.containsKey(name + NpuAssetImport.PART_SUFFIX) ||
                    required.containsKey(name + NpuAssetImport.PREVIOUS_SUFFIX),
            )
        }
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
        // Q8 M2 (4.1 L6): the unreadable refusal interpolates a THROWABLE's message into
        // user-visible card copy, and an IOException message typically carries the full internal
        // .part path. The cause is bounded at the builder — the one sink both callers (the
        // manager's catch and NpuImportController's) funnel through — with the same
        // SAFE_NAME_CHARS bound every other echo takes.
        val hostileCause = "y".repeat(500)
        val bounded = NpuAssetImport.unreadableRefusal(hostileCause)
        assertTrue(
            "a hostile cause is truncated before it reaches the card: ${bounded.length} chars",
            bounded.length < 250,
        )
        assertTrue("the truncation is visible, not silent: $bounded", bounded.contains("…"))
        assertTrue(
            "and the refusal still reads as the sentence it always was: $bounded",
            bounded.contains("Nothing was installed"),
        )
    }
}
