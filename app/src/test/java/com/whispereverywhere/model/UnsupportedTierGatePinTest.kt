package com.whispereverywhere.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * THE UNSUPPORTED-TIER GATE AND THE CARD IT DRIVES, pinned structurally (3.7 Workstream H, Task H2).
 *
 * `ModelMigrationTest` pins `decide()` exhaustively, because it is pure. What no test in this suite
 * could see before this class is the two links BETWEEN that decision and the screen:
 *
 *  1. `WhisperModelManager.unsupportedInstalledModel()` — which flag the manager reads.
 *  2. `SettingsScreen`'s `retiredModel` derivation and the `!= null` that renders the card.
 *
 * Neither is reachable from a JVM unit test. `WhisperModelManager` takes a `Context` and a
 * `PreferencesManager(Context)`, calls `android.util.Log` and `DownloadManager`, and this project has
 * **no Robolectric and no mocking framework** on its unit-test classpath (`junit:4.13.2` and
 * `kotlinx-coroutines-test` are the whole of `testImplementation`), so the gate cannot be constructed
 * with a fake store. `SettingsScreen` is a `@Composable` and Compose UI testing is `androidTest`-only
 * here. Task H1's review measured exactly that hole — finding **m2**: `retiredInstalledModel()` was
 * asserted by *no* test in `app/src/test`, so the `retired` -> `unsupported` flip "would land with a
 * fully green 1336-test suite whether or not it is correct".
 *
 * The remedy is Task F8's and it is the house idiom — pin the CALL, structurally, not the thing being
 * called; the same instrument and the same argument as `CapSeamPinTest`, `CommitFunnelPinTest` and
 * `InFlightStripWiringPinTest`.
 *
 * **The two mutations this class closes**, both of which compile and both of which leave every other
 * test in the suite green:
 *  - *The gate reverted.* `if (model.unsupported)` -> `if (model.retired)` in the manager. That is
 *    the H1->H2 interim state itself: every installed eco/base user — the entire former-default
 *    cohort, on a working 60 MB model — is shown "This model is no longer supported" plus the copy
 *    "Pro (small.en) is much faster", which is FALSE for them (pro is the slower, 190 MB tier), above
 *    a button that does nothing at all because `decide()` correctly returns `None`. Renaming the
 *    function does not defend against this: the rename is caught by the compiler, the *predicate
 *    inside it* is caught by nothing else.
 *  - *The card's condition inverted.* `if (retiredModel != null)` -> `== null` hides the card from
 *    the extreme/ultra users it exists for and raises it, with a `!!` on a null tier, for everyone
 *    else. `assembleDebug` is happy either way.
 *
 * **The source is read LF-NORMALISED.** `core.autocrlf=true` checks this repo out with CRLF, so a
 * needle written with a bare `\n` finds nothing and every assertion would pass or fail for the wrong
 * reason. The normalisation happens once, at each read site below.
 *
 * **Everything here is SYMBOL-SCOPED and no line numbers are used** — every anchor this workstream
 * inherited from the plan had drifted, by up to ~155 lines.
 *
 * **4.0 (Q7a) adds a second manager predicate for the same reason.** `isInstalled` decides whether a
 * tier is on disk, and the npu tier is the first one made of TWO files; the mutation that reverts it
 * to the single-file, `approxBytes` form survived Q7a's full 1448-test battery. Same class because it
 * is the same hole — a `Context`-bound predicate the suite cannot execute — and the same instrument.
 */
class UnsupportedTierGatePinTest {

    private fun source(relative: String): File {
        var dir: File? = File(System.getProperty("user.dir")!!).absoluteFile
        while (dir != null) {
            for (candidate in listOf(File(dir, relative), File(dir, "app/$relative"))) {
                if (candidate.isFile) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError("cannot locate $relative from ${System.getProperty("user.dir")}")
    }

    private fun read(relative: String): String =
        source(relative).readText().replace("\r\n", "\n")

    private val manager: String by lazy {
        read("src/main/java/com/whispereverywhere/model/WhisperModelManager.kt")
    }

    private val settings: String by lazy {
        read("src/main/java/com/whispereverywhere/ui/screens/SettingsScreen.kt")
    }

    private fun count(haystack: String, needle: String) = haystack.split(needle).size - 1

    private fun indexOfOrFail(haystack: String, what: String, needle: String): Int {
        val i = haystack.indexOf(needle)
        assertTrue("missing from $what: <<$needle>>", i >= 0)
        return i
    }

    /**
     * [indexOfOrFail] over LIVE lines only — the same comment filter `NpuDiagTest.liveLineCount`
     * uses, for the same reason and one measured failure.
     *
     * An ordering assertion is a claim about where a STATEMENT sits, and a comment that quotes the
     * statement is not that statement. This bit immediately: the refusal in `download()` explains
     * itself by naming `if (dest.exists()) dest.delete()` in prose, two lines above the real one —
     * so the plain `indexOf` found the *explanation* first and the pin failed on correct code.
     * Rewording the comment would have "fixed" it and left the next comment free to break it
     * again, in a class whose entire value is that it fails only for real reasons.
     */
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

    /**
     * One declaration to its own closing brace, matched by INDENTATION rather than by counting
     * braces: the closer is the first line at the declaration's own nesting depth, which no nested
     * block can produce. Members of these classes close on four spaces.
     */
    private fun body(haystack: String, what: String, declaration: String): String {
        val start = indexOfOrFail(haystack, what, declaration)
        val close = haystack.indexOf("\n    }\n", start)
        assertTrue("the closing brace of <<$declaration>> moved", close > start)
        return haystack.substring(start, close + "\n    }\n".length)
    }

    @Test
    fun theManagerGateReadsUnsupportedAndNeverRetired() {
        // `retired` hides a tier from the chooser; `unsupported` is what moves people OFF one
        // (WhisperModel's KDoc, Task H1). The prompt gate must read the second flag: eco and base
        // carry only the first, and they still dictate perfectly well.
        val gate = body(
            manager,
            "WhisperModelManager.kt",
            "    fun unsupportedInstalledModel(): WhisperModel? {",
        )
        assertEquals(
            "the migration gate returns the selected tier only when it is UNSUPPORTED",
            1,
            count(gate, "return if (model.unsupported) model else null"),
        )
        assertEquals(
            "the manager never gates the migration prompt on `retired`: that is the H1->H2 interim " +
                "defect — it raises the card, and the false \"much faster\" claim, for every " +
                "installed eco/base user",
            0,
            count(manager, "model.retired"),
        )
        assertEquals(
            "the old name is gone, so no caller can be left on the retired-flag gate",
            0,
            count(manager, "retiredInstalledModel"),
        )
    }

    @Test
    fun theInstalledPredicateSizeGatesEachFileAgainstItsOwnBytes() {
        // 4.0 (Q7a). The same instrument, the same argument, one method further down the SAME class
        // — and a mutation the Q7a battery measured surviving a full 1448-test suite (row R14).
        //
        // npu is the first PAIRED tier: `fileName` is the encoder (132,927,488 B) and `approxBytes`
        // is the pair (358,244,352 B). Gate the primary against `approxBytes` — which is exactly
        // what this method did before Q7a, and exactly what a "simplification" would restore — and
        // the encoder is 63% under the ±5% window, so `isInstalled(npu)` is false FOREVER, on a
        // device where the owner imported both files correctly. The tier never arms, and Q8's card
        // tells the user their device is the problem.
        //
        // The catalog half is pinned in WhisperCatalogHelpersTest, which can execute. This half
        // cannot: the manager needs a Context (see this class's KDoc), so the CALL is pinned instead.
        val predicate = body(
            manager,
            "WhisperModelManager.kt",
            "    fun isInstalled(model: WhisperModel): Boolean {",
        )
        assertEquals(
            "the primary file is size-gated against primaryBytes, which is the file it names",
            1,
            count(predicate, "WhisperCatalog.sizeWithinTolerance(f.length(), model.primaryBytes)"),
        )
        assertEquals(
            "isInstalled never gates a file against approxBytes: for a paired tier that is the SUM " +
                "of two files, and no single file on disk can ever match it",
            0,
            count(predicate, "f.length(), model.approxBytes"),
        )
        assertEquals(
            "the paired artefact is required too, at ITS own size — half an npu install is not an " +
                "install, and arming the tier on a missing decoder is a native-side failure",
            1,
            count(predicate, "WhisperCatalog.sizeWithinTolerance(pf.length(), paired.approxBytes)"),
        )
        assertEquals(
            "a tier with no paired artefact still answers on its primary alone, so the six ggml " +
                "tiers keep the predicate they have always had",
            1,
            count(predicate, "model.pairedArtifact ?: return true"),
        )
        // ORDER, not merely presence — the same rule this branch has now hit four times. Hoisting
        // the early return above the primary gate satisfies every count above and is a different
        // function: `isInstalled` would return TRUE for all six ggml tiers with nothing on disk,
        // because the only file check left runs after a return that always fires for them. The
        // early return is a shortcut past work already done, so it must come second.
        assertTrue(
            "the primary file's size gate runs BEFORE the no-paired-artefact early return: hoisting " +
                "that return makes isInstalled() true for every single-file tier with no file on disk",
            indexOfOrFail(
                predicate,
                "isInstalled",
                "WhisperCatalog.sizeWithinTolerance(f.length(), model.primaryBytes)",
            ) < indexOfOrFail(predicate, "isInstalled", "model.pairedArtifact ?: return true"),
        )
    }

    @Test
    fun theDownloadRefusalRunsBeforeAnyFileOperation() {
        // 4.0 Q7b fix round, C1 — and this is an ORDER assertion for the FIFTH time on this
        // branch, because presence is again only half of it.
        //
        // `download()`'s third statement is `if (dest.exists()) dest.delete()`. For the npu tier
        // `dest` is the hand-imported 132,927,488-byte encoder, and the function goes on to fetch
        // the ~423 MB provenance zip over a possibly-metered link, fail the size gate against the
        // PAIR's 358,244,352 bytes, delete that too, and leave `isInstalled(npu)` false — so the
        // card silently vanishes and the only way back is re-importing. `download()` never reads
        // `pairedArtifact`, so this path could not have installed the tier even with a good URL.
        //
        // A guard placed anywhere AFTER that delete still refuses, still throws, still logs — and
        // is decoration on a file that is already gone. Only the order makes it a fix.
        val download = body(
            manager,
            "WhisperModelManager.kt",
            "    suspend fun download(",
        )
        assertEquals(
            "download() refuses a tier it structurally cannot install",
            1,
            count(download, "if (!WhisperCatalog.isInstallableByDownload(model)) {"),
        )
        assertEquals(
            "the refusal is loud: one WE-DIAG line naming the tier and the reason",
            1,
            count(download, "WhisperCatalog.notInstallableByDownloadReason(model)"),
        )
        // It leaves by the failure path every caller already handles — `download()` has four
        // `throw ModelDownloadException(` sites, so the refusal is identified by ITS message, not
        // by a count of the type. And the message is the assertion worth making: the refusal
        // promises the user nothing was touched, which is only true while the ORDER below holds.
        assertEquals(
            "the refusal tells the user nothing was changed, which the ordering below is what makes true",
            1,
            count(download, "\"Nothing on this device was changed.\""),
        )
        val refusal = liveIndexOfOrFail(
            download,
            "download",
            "if (!WhisperCatalog.isInstallableByDownload(model)) {",
        )
        assertTrue(
            "THE REFUSAL MUST PRECEDE THE FIRST FILE OPERATION. Below the delete it is a guard on " +
                "a destroyed file: the imported encoder is gone before the check that says it " +
                "should never have been touched.",
            refusal < liveIndexOfOrFail(download, "download", "if (dest.exists()) dest.delete()"),
        )
        // The other half of the same claim: nothing may sneak a file operation above the guard.
        // `fileFor(model)` resolves the path that gets deleted, so it is the earliest statement
        // that could be hoisted to reintroduce the defect.
        assertTrue(
            "`dest` must be resolved AFTER the refusal, so no code path can touch a file first",
            refusal < liveIndexOfOrFail(download, "download", "val dest = fileFor(model)"),
        )
    }

    @Test
    fun theMelDonorRefusesTheWrongFilterbankAndTheFileThatIsNotGgml() {
        // 4.0 Q8. `cpuTierModelPath` answers two questions with one file (the D-Q6-1 ruling): the
        // 80-bin mel filterbank the NPU tier borrows, and the CPU backend it falls back to. Both
        // exclusions are live defects, not hygiene:
        //  - `ultra` is large-v3-turbo, a 128-BIN model. `pcmToMel` refuses it by bin count, so as
        //    a donor it is a failed load — and an `ultra` user would otherwise be handed it as the
        //    preferred candidate purely because it is the tier they selected.
        //  - `npu` is not a ggml file at all. It is the QAIRT encoder context this tier is trying
        //    to arm; handing it to `initMelOnly` is handing a QNN blob to a whisper.cpp loader.
        // Neither is reachable from a JVM test (the manager needs a Context), which is the same
        // hole, and the same instrument, as the two pins above.
        val donor = body(
            manager,
            "WhisperModelManager.kt",
            "    override fun cpuTierModelPath(): String? {",
        )
        assertEquals(
            "the donor is chosen by an eligibility predicate, not by an inline filter that a " +
                "later reader could simplify one clause out of",
            1,
            count(donor, "candidates.firstOrNull { isMelDonorEligible(it) && isInstalled(it) }"),
        )
        assertEquals(
            "the selected tier is PREFERRED, so a session that falls back falls back to the model " +
                "the user actually chose",
            1,
            count(donor, "val preferred = WhisperCatalog.byId(prefs.selectedModelId)"),
        )
        assertEquals(
            "128-bin large-v3-turbo is excluded BY NAME, with the reason in the KDoc",
            1,
            count(manager, "model.id != \"ultra\""),
        )
        assertEquals(
            "and so is the tier being armed, which is not a ggml file",
            1,
            count(manager, "model.id != \"npu\""),
        )
        assertEquals(
            "plus the structural clause that catches the NEXT two-artefact tier nobody thought to " +
                "name here — the id says why, the structure catches the next one",
            1,
            count(manager, "model.pairedArtifact == null"),
        )
        // The companion must come from the SAME tier as installedModelPath, not from the npu entry
        // directly. Resolving the two independently hands nativeInit an encoder from one tier and
        // a decoder from another — a native-side failure with no Kotlin-side symptom.
        val companion = body(
            manager,
            "WhisperModelManager.kt",
            "    override fun companionModelPath(): String? {",
        )
        assertEquals(
            "the companion is the paired artefact OF THE INSTALLED SELECTED TIER",
            1,
            count(companion, "val model = installedModel() ?: return null"),
        )
        assertEquals(
            "and null for a tier that has no second file, which is all six ggml ones",
            1,
            count(companion, "val paired = model.pairedArtifact ?: return null"),
        )
    }

    @Test
    fun theImportAnnouncesTheInstallOnlyAfterThePairIsVerifiedOnDisk() {
        // 4.0 Q8, and the SIXTH application of the presence-vs-ORDER rule on this branch.
        //
        // `prefs.notifyModelInstalled()` bumps `ModelInstallSignal.generation`, the key both
        // chooser producers re-read the offer gate on. Omit it and the tier card stays hidden in
        // the very composition that just imported its assets (Q7b fix round, I1 — the correction
        // to that report's §9.2.4 is precisely this requirement). Hoist it above the renames and
        // the opposite defect appears: the signal fires for files still sitting in `.part`, so the
        // chooser re-stats, finds nothing, and caches a "not installed" answer for an import that
        // then succeeds.
        val import = body(
            manager,
            "WhisperModelManager.kt",
            "    suspend fun importNpuAssetPair(",
        )
        assertEquals(
            "the import announces the install exactly once — the SAME funnel the download path " +
                "uses, so a future consumer cannot be wired to one route and not the other",
            1,
            count(import, "prefs.notifyModelInstalled()"),
        )
        val announce = liveIndexOfOrFail(import, "importNpuAssetPair", "prefs.notifyModelInstalled()")
        assertTrue(
            "THE ANNOUNCEMENT MUST FOLLOW THE RENAMES. Bumping the generation while the files are " +
                "still `.part` makes the chooser re-read the gate against a directory the pair has " +
                "not landed in yet.",
            liveIndexOfOrFail(import, "importNpuAssetPair", "if (part.renameTo(dest)) {") < announce,
        )
        assertTrue(
            "AND THE VERIFICATION. `isInstalled` is the predicate the card itself keys on, so " +
                "announcing before it is announcing something not yet true. (Fix round 1, I2 " +
                "folded the verification into the finalise transaction, so it now sets the " +
                "failure rather than returning on its own — the ordering claim is unchanged.)",
            liveIndexOfOrFail(
                import, "importNpuAssetPair", "finaliseFailure == null && !isInstalled(model)"
            ) < announce,
        )
        assertTrue(
            "and the whole finalise — park, rename, verify, roll back — precedes it too",
            liveIndexOfOrFail(import, "importNpuAssetPair", "if (finaliseFailure != null) {") < announce,
        )
        // Both-or-neither. Every entry is staged under `.part` and the destinations are only
        // touched once both files exist at their exact published lengths; a half-renamed pair is
        // purged rather than left mismatched.
        assertEquals(
            "entries land as .part first, so an existing installed pair survives a bad zip",
            1,
            count(import, "File(dir, accept.fileName + NpuAssetImport.PART_SUFFIX)"),
        )
        assertTrue(
            "the missing-half check runs BEFORE anything is renamed into place: an encoder " +
                "without its decoder is a tier that arms halfway",
            liveIndexOfOrFail(import, "importNpuAssetPair", "NpuAssetImport.missingEntriesRefusal(") <
                liveIndexOfOrFail(import, "importNpuAssetPair", "if (part.renameTo(dest)) {"),
        )
        // Fix round 1, I2 REPLACED what this assertion used to check. It read
        // `count(import, "renamed.forEach { it.delete() }") == 1` — "a failed second rename purges
        // the first" — which was true and not enough: purging the first rename left the device with
        // NEITHER pair, because the previous files had already been deleted to make room. The claim
        // is kept and strengthened rather than dropped: every finalise failure now leaves through
        // one roll-back that restores the previous pair, and
        // `aFailedFinaliseRollsBackAndTellsTheTruthAboutWhatIsOnTheDevice` pins its internals.
        assertEquals(
            "a failed finalise leaves through the roll-back, which purges what this import moved " +
                "in AND puts back what it parked",
            1,
            count(import, "rollBackFinalise(finaliseFailure, renamed, parked)"),
        )
        assertEquals(
            "and every .part is cleared on failure, refusal OR cancellation, so a retry starts " +
                "clean — in a finally, because a return from inside the copy loop must not skip it",
            1,
            count(import, "parts.values.forEach { if (it.exists()) it.delete() }"),
        )
        assertEquals(
            "the free-space precheck runs before a byte is read, and it is the only one",
            1,
            count(import, "NpuAssetImport.freeSpaceRefusal(usable, needed)"),
        )
        assertTrue(
            "before the stream is even opened",
            liveIndexOfOrFail(import, "importNpuAssetPair", "NpuAssetImport.freeSpaceRefusal(usable, needed)") <
                liveIndexOfOrFail(import, "importNpuAssetPair", "context.contentResolver.openInputStream(source)"),
        )
        assertEquals(
            "both zip-slip guards are present: the allow-list AND the canonical-path check",
            1,
            count(import, "NpuAssetImport.escapesTargetDir(dirCanonical, dest.canonicalPath)"),
        )
        assertEquals(
            "the copy is cancellable between buffers — 358 MB is long enough for a user to leave",
            1,
            count(import, "callerContext.ensureActive()"),
        )
    }

    @Test
    fun theCopyIsBoundedByTheEntrysOwnLengthRatherThanByTheFilesystemFillingUp() {
        // Fix round 1, I1. The entry's declared size is not evidence: `-1` is legal (a streamed
        // archive) and a stated size can simply be a lie. Reading `zis.read(buffer)` unbounded and
        // comparing the total AFTERWARDS means a 40 KB zip declaring `-1` writes until ENOSPC and
        // is then refused for being the wrong size — true, and far too late. One byte of headroom
        // past the expected length is the detector, and nothing smaller can be.
        val import = body(
            manager,
            "WhisperModelManager.kt",
            "    suspend fun importNpuAssetPair(",
        )
        assertEquals(
            "the read is capped at what this entry is still allowed to produce, plus one",
            1,
            count(import, "val room = accept.expectedBytes - got + 1L"),
        )
        assertEquals(
            "and the cap is actually applied to the read, not merely computed beside it",
            1,
            count(import, "zis.read(buffer, 0, minOf(buffer.size.toLong(), room).toInt())"),
        )
        assertEquals(
            "an unbounded read must appear NOWHERE in the import: it is one deleted argument list " +
                "away, it compiles, and it restores the whole defect",
            0,
            count(import, "zis.read(buffer)"),
        )
        assertEquals(
            "the overflow is detected INSIDE the copy loop and stops it",
            1,
            count(import, "if (got > accept.expectedBytes) {"),
        )
        // ORDER: the overflow check must run before the progress tick, or the loop reports progress
        // for a byte it has already decided is illegitimate — and, more to the point, the `break`
        // is what bounds the write.
        assertTrue(
            "the over-length break precedes the progress tick inside the loop",
            liveIndexOfOrFail(import, "importNpuAssetPair", "if (got > accept.expectedBytes) {") <
                liveIndexOfOrFail(import, "importNpuAssetPair", "if (written - lastTick >= PROGRESS_TICK_BYTES) {"),
        )
        assertEquals(
            "and the refusal names it as an over-length entry rather than as a size mismatch",
            1,
            count(import, "NpuAssetImport.overLengthRefusal("),
        )
        // Battery row Z3, a MEASURED survivor: neutering this guard (`if (false && overLength)`)
        // leaves the cap, the detector and the builder all in place and every other assertion here
        // green — while the refusal falls through to the wrong-size branch, because `got` is then
        // `expected + 1`. The user is told their file is one byte too long when the truth is that
        // it never stopped. The naming was the review's explicit ask, so the guard is pinned too.
        assertEquals(
            "the over-length refusal is reached on the over-length flag ALONE, so it cannot fall " +
                "through to the wrong-size message",
            1,
            count(import, "if (overLength) {"),
        )
        // The staged file is still cleaned: the refusal returns from inside the try, so the
        // finally runs and at most one byte more than the tier's own file was ever on disk.
        assertTrue(
            "the over-length refusal returns from inside the try whose finally clears the .part",
            liveIndexOfOrFail(import, "importNpuAssetPair", "NpuAssetImport.overLengthRefusal(") <
                liveIndexOfOrFail(import, "importNpuAssetPair", "parts.values.forEach { if (it.exists()) it.delete() }"),
        )
        // MICRO-ROUND 2, N1 — the EIGHTH application of the presence-vs-ORDER rule on this branch,
        // and the sharpest illustration of it yet: the cap, the detector, the guard and the message
        // were all correct and correctly ordered relative to each other, and one call sitting above
        // them gave most of the attack back.
        //
        // `ZipInputStream.closeEntry()` skips to the end of the current entry, which for a DEFLATED
        // entry means inflating and discarding every remaining byte. Below the refusal, the capped
        // read has stopped the WRITE — the disk is safe — while the 40 KB bomb is still fully
        // decompressed on the IO thread with progress frozen and Cancel dead. Nothing needs closing
        // on this path: the enclosing `zis.use { }` closes the stream, entry and all.
        // Scoped to the ACCEPTED-ENTRY block: `zis.closeEntry()` appears three times in this method
        // (the directory skip, the ignored-entry branch, and the one after the copy), and the first
        // two legitimately precede the refusal. Comparing against the whole method would compare
        // against the wrong call and fail on correct code.
        val acceptedEntry = import.substringAfter("val part = File(dir, accept.fileName")
        assertTrue(
            "the accepted-entry block was found",
            acceptedEntry.isNotEmpty() && acceptedEntry.length < import.length,
        )
        assertTrue(
            "THE OVER-LENGTH REFUSAL MUST PRECEDE closeEntry(), which for a DEFLATED entry inflates " +
                "and discards everything still to come. Below it the write is stopped and the disk " +
                "is safe, while the bomb is decompressed in full on the IO thread with progress " +
                "frozen and Cancel dead.",
            liveIndexOfOrFail(acceptedEntry, "the accepted-entry block", "if (overLength) {") <
                liveIndexOfOrFail(acceptedEntry, "the accepted-entry block", "zis.closeEntry()"),
        )
    }

    @Test
    fun anInterruptedFinaliseIsReconciledForTheWholeTierAndNeverFileByFile() {
        // Micro-round 2, N3. `NpuAssetImportTest` proves the DECISION; this pins that the manager
        // asks for it instead of deciding per file again — which is what could synthesize a pair
        // nothing ever wrote (new encoder beside old decoder, isInstalled true).
        val reconcile = body(
            manager,
            "WhisperModelManager.kt",
            "    private fun reconcileStagingDebris(",
        )
        assertEquals(
            "the direction is decided once, for the tier, by the pure rule",
            1,
            count(reconcile, "when (NpuAssetImport.reconcileDecision(names, parked, movedIn))"),
        )
        // The restore statement itself survives — it is correct INSIDE the roll-back branch. What
        // is gone is deciding with it, per name, for the whole directory: that is the shape that
        // could synthesize a mixed pair, and its loop header is the thing to keep absent.
        assertEquals(
            "nothing iterates the tier's names deciding each one on its own any more",
            0,
            count(reconcile, "for (name in names) {"),
        )
        assertTrue(
            "and the restore is reached only THROUGH the tier-wide decision — it sits inside the " +
                "ROLL_BACK branch, not above the `when` where it would run unconditionally",
            indexOfOrFail(reconcile, "reconcileStagingDebris", "NpuAssetImport.Reconcile.ROLL_BACK -> {") <
                indexOfOrFail(reconcile, "reconcileStagingDebris", "if (dest.exists()) previous.delete() else previous.renameTo(dest)"),
        )
        assertEquals(
            "`.part` presence is what proves phase 2 consumed a staged file, and it is what stops " +
                "an interrupted PHASE 1 being misread as a completed phase 2 — which would delete " +
                "originals this import never placed",
            1,
            count(reconcile, "!File(dir, it + NpuAssetImport.PART_SUFFIX).exists() && File(dir, it).exists()"),
        )
        assertEquals(
            "a completed phase 2 is finished forward: the parked copies are simply dropped",
            1,
            count(reconcile, "parked.forEach { File(dir, it + NpuAssetImport.PREVIOUS_SUFFIX).delete() }"),
        )
        // The same ORDER invariant as rollBackFinalise, for the same reason: both statements claim
        // the same paths, and restoring before removing puts the previous file back and then
        // deletes it.
        assertTrue(
            "the roll-back branch REMOVES what phase 2 placed before restoring what it parked",
            liveIndexOfOrFail(reconcile, "reconcileStagingDebris", "movedIn.forEach { File(dir, it).delete() }") <
                liveIndexOfOrFail(reconcile, "reconcileStagingDebris", "if (dest.exists()) previous.delete() else previous.renameTo(dest)"),
        )
        assertEquals(
            "orphaned .part files are swept on the same pass, so a process death mid-copy does not " +
                "leave 358 MB behind for good",
            1,
            count(reconcile, "names.forEach { File(dir, it + NpuAssetImport.PART_SUFFIX).delete() }"),
        )
    }

    @Test
    fun aFailedFinaliseRollsBackAndTellsTheTruthAboutWhatIsOnTheDevice() {
        // Fix round 1, I2, and the SEVENTH application of the presence-vs-ORDER rule on this branch
        // — this time inside the roll-back itself.
        //
        // The first draft deleted each destination before renaming, so a failed SECOND rename left
        // the device with neither the new pair nor the old one while the message still read
        // "Nothing was installed". A destroyed working tier is not nothing, and a rename cannot be
        // undone onto a file that has already been unlinked — which is why the fix is to PARK the
        // previous file rather than to write a smarter undo.
        val import = body(
            manager,
            "WhisperModelManager.kt",
            "    suspend fun importNpuAssetPair(",
        )
        assertEquals(
            "the previously installed file is moved aside, not deleted",
            1,
            count(import, "if (dest.renameTo(previous)) {"),
        )
        assertEquals(
            "nothing in the finalise deletes a destination outright any more — that single " +
                "statement WAS the defect",
            0,
            count(import, "if (dest.exists()) dest.delete()"),
        )
        assertEquals(
            "the verification failure joins the same transaction rather than returning on its own: " +
                "a pair that does not read as installed must be rolled back, not left on disk for " +
                "the next import's already-installed logic to reason about",
            1,
            count(import, "finaliseFailure = \"The imported files did not verify on disk\""),
        )
        assertEquals(
            "and every finalise failure leaves through the one roll-back",
            1,
            count(import, "return@withContext refuseImport(rollBackFinalise(finaliseFailure, renamed, parked))"),
        )
        assertEquals(
            "the parked copies are dropped only after the whole transaction has succeeded",
            1,
            count(import, "parked.values.forEach { if (it.exists()) it.delete() }"),
        )
        assertTrue(
            "and that drop comes AFTER the verification, or a failed verify would have nothing " +
                "left to restore",
            liveIndexOfOrFail(import, "importNpuAssetPair", "if (finaliseFailure != null) {") <
                liveIndexOfOrFail(import, "importNpuAssetPair", "parked.values.forEach { if (it.exists()) it.delete() }"),
        )

        val rollback = body(
            manager,
            "WhisperModelManager.kt",
            "    private fun rollBackFinalise(",
        )
        // THE ORDER INVARIANT. Both statements claim the same paths: the imported file that was
        // moved in, and the previous file that must go back. Restore-then-remove puts the old file
        // back and then deletes it as "one of ours" — the exact failure this function exists to
        // prevent, spelled with the same two statements in the other order.
        assertTrue(
            "the roll-back REMOVES what this import moved in BEFORE restoring what it parked",
            liveIndexOfOrFail(rollback, "rollBackFinalise", "renamed.forEach { if (it.exists() && !it.delete()) undoneCleanly = false }") <
                liveIndexOfOrFail(rollback, "rollBackFinalise", "if (dest.exists() || !previous.renameTo(dest)) undoneCleanly = false"),
        )
        // Micro-round 2, N2. `delete()` returns a Boolean and the first draft dropped it, so on a
        // FIRST import — where `parked` is empty and the restore loop therefore falsifies nothing
        // at all — a file that refused to be deleted still produced "nothing new was installed"
        // while sitting on disk. Every step's result now feeds the one truthfulness decision.
        assertEquals(
            "a failed delete makes the roll-back UNCLEAN, exactly as a failed restore does",
            1,
            count(rollback, "renamed.forEach { if (it.exists() && !it.delete()) undoneCleanly = false }"),
        )
        assertEquals(
            "and a device with nothing parked gets the wording for a device that never had a pair",
            1,
            count(rollback, "return if (parked.isEmpty()) NpuAssetImport.rolledBackFreshRefusal(what)"),
        )
        assertEquals(
            "and a failed one reports the device's REAL state instead, read live off disk rather " +
                "than assumed from what the code believes it did",
            1,
            count(rollback, "val live = names.filter { File(modelsDir(), it).exists() }"),
        )
        assertEquals(
            "the staging debris of an import that died between parking and renaming is settled " +
                "before the free-space budget and the already-installed check are answered",
            1,
            count(import, "reconcileStagingDebris(dir, required.keys)"),
        )
        // The reconciliation's own semantic moved to
        // `anInterruptedFinaliseIsReconciledForTheWholeTierAndNeverFileByFile` in micro-round 2,
        // where the per-file rule this used to assert is now asserted ABSENT.
    }

    @Test
    fun theSettingsCardIsDrivenByTheUnsupportedGate() {
        // The rename's call site. A compiler catches a MISSING follow-up; nothing catches a call
        // site that was pointed back at a re-added `retiredInstalledModel()`.
        assertEquals(
            "the card's driver is the unsupported gate, read exactly once",
            1,
            count(settings, "modelManager.unsupportedInstalledModel()"),
        )
        assertEquals(
            "Settings never reads the retired-flag gate",
            0,
            count(settings, "retiredInstalledModel"),
        )
    }

    @Test
    fun theCardRendersOnAPresentUnsupportedTierAndIsNeverInverted() {
        // A separate defect from the derivation above, so a separate test: inverting this condition
        // hides the card from the extreme/ultra cohort it exists for and raises it — with a `!!` on
        // a null tier one line later — for everybody else.
        assertEquals(
            "the migration card renders when the gate returned a tier, not when it returned null",
            1,
            count(settings, "if (retiredModel != null) {"),
        )
        // The target resolution sits INSIDE that guard: it is a non-null read of `retiredModel`, so
        // an inverted or hoisted guard is a crash rather than a cosmetic slip.
        assertTrue(
            "the scope-matched target is resolved inside the card's guard",
            indexOfOrFail(settings, "SettingsScreen.kt", "if (retiredModel != null) {") <
                indexOfOrFail(
                    settings,
                    "SettingsScreen.kt",
                    "ModelMigration.targetIdFor(retiredModel.scope)",
                ),
        )
    }
}
