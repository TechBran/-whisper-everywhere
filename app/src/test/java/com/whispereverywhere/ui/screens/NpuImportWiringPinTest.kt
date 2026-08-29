package com.whispereverywhere.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * THE ASSET-PAIR IMPORT'S UI WIRING, pinned structurally (4.0, Q8).
 *
 * `NpuAssetImportTest` proves every DECISION the import makes, because those live in a pure object.
 * What no JVM test in this project can see is the delivery around them: `MainActivity` is an
 * `Activity` with a Compose body, `OnboardingModelScreen` is a `@Composable`, Compose UI testing is
 * `androidTest`-only here (no Robolectric, no mocking framework on the unit-test classpath), and
 * instrumented runs are forbidden in this environment. So the CALLS are pinned, structurally — the
 * same instrument and the same argument as `ChooserSteerWiringPinTest` and
 * `UnsupportedTierGatePinTest`.
 *
 * **The mutations this class closes.** All compile, and all leave every other test in the suite
 * green:
 *
 *  - *The installed branch deleted.* Every tier card's action reverts to a Download button —
 *    including for a tier already on disk. On `npu` that button is the one action the tier can
 *    never perform: `download()` refuses it at the sink (Q7b fix round, C1), so the card becomes a
 *    dead end and the imported pair can never be SELECTED from this screen at all.
 *  - *The import entry gated on the offer gate.* Covered in `ChooserSteerWiringPinTest`, and named
 *    here because it is the same defect seen from the delivery side: an import affordance that
 *    only appears once the files it fetches have arrived.
 *  - *The launcher's result dropped on the floor.* `rememberLauncherForActivityResult` returning a
 *    `Uri` that nothing consumes is a picker that opens, closes, and does nothing — with no error,
 *    because nothing failed.
 *  - *The unavailable note disconnected.* `NpuWhisperBackend.unavailableReason` gets its only
 *    reader here; unwire it and a tier that fell back to the CPU keeps claiming the AI chip, which
 *    is the exact failure `NpuDiag.unavailable` exists to make impossible in the log.
 *
 * **The source is read LF-NORMALISED** (`core.autocrlf=true` checks this repo out with CRLF), and
 * everything is symbol-scoped with no line numbers — every anchor this workstream inherited from
 * the plan had drifted.
 */
class NpuImportWiringPinTest {

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

    private val activity: String by lazy {
        read("src/main/java/com/whispereverywhere/MainActivity.kt")
    }

    private val picker: String by lazy {
        read("src/main/java/com/whispereverywhere/ui/screens/OnboardingModelScreen.kt")
    }

    private fun count(haystack: String, needle: String) = haystack.split(needle).size - 1

    private fun block(vararg lines: String) = lines.joinToString("\n")

    private fun indexOfOrFail(haystack: String, what: String, needle: String): Int {
        val i = haystack.indexOf(needle)
        assertTrue("missing from $what: <<$needle>>", i >= 0)
        return i
    }

    @Test
    fun theDocumentPickerLauncherLivesInTheActivityAndItsResultReachesTheImporter() {
        assertEquals(
            "the launcher is registered from the activity's composition, following the batch " +
                "audio picker's precedent — a screen that may be off the back stack when the " +
                "result returns cannot own one",
            1,
            count(
                activity,
                block(
                    "    val npuAssetImportLauncher = rememberLauncherForActivityResult(",
                    "        ActivityResultContracts.OpenDocument()",
                    "    ) { uri: Uri? ->",
                ),
            ),
        )
        assertEquals(
            "the picked Uri is handed to the manager's importer WITH the tier id the launching " +
                "button chose (4.1 L6 — the import is per-tier). A launcher whose result nothing " +
                "consumes is a picker that opens, closes, and silently does nothing",
            1,
            count(activity, ".importNpuAssetPair(tierId, uri, onProgress)"),
        )
        assertEquals(
            "and the screen is handed both halves: the state, and the way to start an import",
            1,
            count(activity, block("                npuImportState = npuImportState,")),
        )
    }

    /**
     * Fix round 1, I3 — **the composition may not own a 358 MB copy.**
     *
     * `MainActivity` declares no `android:configChanges`, so a rotation, a theme change or a
     * font-size change destroys and rebuilds the entire Compose tree. The first draft kept the
     * import's state in `remember` and launched it from `rememberCoroutineScope()` in the nav
     * composition: both die with that tree, so a rotation three minutes into the copy cancelled it
     * and returned the panel to "Import model pair…" **with nothing said**. Nothing failed loudly
     * because nothing failed — the owner simply stopped existing, which is the one failure shape
     * this import is written to make impossible.
     *
     * The state machine itself is proved by execution in `NpuImportControllerTest`. What only
     * source can say is that the activity delegates to it rather than owning the work again.
     */
    @Test
    fun theImportIsOwnedByTheProcessScopedControllerAndNotByTheComposition() {
        assertEquals(
            "the panel's state is COLLECTED from the process-scoped owner, so a recreation " +
                "re-subscribes and finds the import exactly where it was",
            1,
            count(activity, "val npuImportState by NpuImportController.state.collectAsState()"),
        )
        assertEquals(
            "and the work is started through that owner",
            1,
            count(activity, "NpuImportController.start { onProgress ->"),
        )
        assertEquals(
            "the import state is NEVER held in a `remember`: that is the composition owning it " +
                "again, and it dies on the next rotation",
            0,
            count(activity, "mutableStateOf<NpuAssetImport.ImportState>"),
        )
        assertEquals(
            "nor launched from the composition's scope, which is cancelled with the tree",
            0,
            count(activity, "scope.launch {\n                npuImportState"),
        )
        // A dismissed picker must not clear the state: wiping it erases the refusal message the
        // user re-opened the picker BECAUSE of.
        // Scoped to THIS launcher: the batch audio picker a few lines above has the same guard
        // spelling, and counting it too would make this assertion pass for the wrong reason.
        assertEquals(
            "a dismissed picker leaves the previous state alone — clearing it would wipe the " +
                "refusal message the user re-opened the picker because of. The tier id is read " +
                "into a local FIRST (4.1 L6), pairing the picked zip with the tier whose button " +
                "launched the picker",
            1,
            count(
                activity,
                block(
                    "        if (uri != null) {",
                    "            val tierId = npuImportTierId",
                    "            NpuImportController.start { onProgress ->",
                ),
            ),
        )
        // The tier id must survive the SAF round trip the same way the import itself must survive
        // recreation: the picker is a separate activity, so a rotation (or a process death) behind
        // it rebuilds this composition before the result arrives. A plain `remember` resets to
        // the default and the result would import the picked zip under the WRONG tier's names and
        // numbers — rememberSaveable is the difference.
        assertEquals(
            "the pending tier id is rememberSaveable, defaulted to the npu tier",
            1,
            count(
                activity,
                "var npuImportTierId by rememberSaveable { mutableStateOf(NpuAssetImport.TIER_ID) }",
            ),
        )
        assertEquals(
            "and a running import can be abandoned — the copy is minutes long and survives leaving " +
                "the screen, so there has to be a way to stop it",
            1,
            count(picker, "onClick = { NpuImportController.cancel() },"),
        )
    }

    @Test
    fun anInstalledTierIsNeverOfferedADownload() {
        // The Q7a §9.3 concern, and the reason `download()`'s refusal is only the BACKSTOP. Before
        // this branch existed, `else -> Download` was every card's action area, so an installed
        // tier — the only state in which the npu card renders at all — was offered the one
        // operation that would destroy it.
        assertEquals(
            "the card has an installed branch, and it comes from a live on-disk check rather than " +
                "from the transient \"a download just finished\" state",
            1,
            count(picker, block("                installed -> {")),
        )
        assertEquals(
            "the installed branch offers to USE the tier",
            1,
            count(picker, "Button(onClick = onUse, modifier = Modifier.fillMaxWidth()) {"),
        )
        assertEquals(
            "and names the repair for what it actually is on this tier: a re-fetch for a URL tier, " +
                "a re-import for the paired one",
            1,
            count(picker, "onClick = if (downloadable) onSelect else onImport,"),
        )
        assertEquals(
            "a tier that cannot be downloaded is offered the import instead — the Download button " +
                "is not merely wrong there, it is the one action that tier can never perform",
            1,
            count(picker, block("                !downloadable -> {")),
        )
        // ORDER. `else -> Download` must be the LAST arm: hoisted above either branch above it, it
        // swallows both and the defect is restored with every count still satisfied.
        val downloadArm = indexOfOrFail(picker, "the card", "Text(\"Download\")")
        assertTrue(
            "THE DOWNLOAD ARM MUST COME LAST. `when` takes the first matching branch, so hoisting " +
                "it above the installed / not-downloadable arms restores the exact defect while " +
                "leaving every presence assertion in this test green.",
            indexOfOrFail(picker, "the card", "                installed -> {") < downloadArm &&
                indexOfOrFail(picker, "the card", "                !downloadable -> {") < downloadArm,
        )
        // The SECOND ordering claim, and it is a different one. The assertion above says both
        // branches precede `else`; this says which of the two comes first. Swap them — both
        // spellings intact, both counts satisfied, `else` still last — and an INSTALLED npu card
        // matches `!downloadable` first, so the one tier that can only be adopted from this screen
        // shows "Import model pair…" and never "Use this model". Battery row X13 approached this
        // from the presence side and was killed by a needle rather than by an order, which is how
        // the gap was found.
        assertTrue(
            "the INSTALLED arm must precede the not-downloadable arm: `installed` is the narrower " +
                "and more specific state, and `when` takes the first match, so the broader arm " +
                "first swallows it",
            indexOfOrFail(picker, "the card", "                installed -> {") <
                indexOfOrFail(picker, "the card", "                !downloadable -> {"),
        )
        assertEquals(
            "downloadability is the CATALOG's predicate, the same one download() refuses on, so " +
                "the card and the sink cannot disagree about which tiers have a URL",
            1,
            count(picker, "val downloadable = WhisperCatalog.isInstallableByDownload(model)"),
        )
        assertEquals(
            "and tapping an installed card adopts it rather than re-fetching up to 574 MB",
            1,
            count(picker, "Modifier.clickable(onClick = if (installed) onUse else onSelect)"),
        )
    }

    @Test
    fun theInstalledFlagIsALiveOffMainStatKeyedOnTheInstallGeneration() {
        assertEquals(
            "which tiers are on disk is read off Main — isInstalled stats one or two files per " +
                "tier and the caller is a composition",
            1,
            count(
                picker,
                block(
                    "        value = withContext(Dispatchers.IO) {",
                    "            models.filter { manager.isInstalled(it) }.map { it.id }.toSet()",
                    "        }",
                ),
            ),
        )
        assertEquals(
            "and keyed on the install generation, so the import that just landed changes the card " +
                "it landed for",
            1,
            count(picker, "key1 = installGeneration,"),
        )
        assertEquals(
            "the card is told the answer for ITS tier",
            1,
            count(picker, "installed = installedIds.contains(model.id),"),
        )
    }

    @Test
    fun theUnavailableNoteIsReadFromTheBackendsOwnReasonAndUsesTheWarningSurface() {
        assertEquals(
            "the tier cards subscribe to the process-scoped mirror of " +
                "NpuWhisperBackend.unavailableReason — the per-tier MAP since 4.1 L8, because " +
                "two npu-class tiers can decline independently and one shared reason wore " +
                "whichever card asked first",
            1,
            count(picker, "val npuTierReasons by NpuTierStatus.reasons.collectAsState()"),
        )
        assertEquals(
            "and each card renders ITS OWN tier's record — reasonFor(model.id) in its Compose " +
                "spelling — so the note appears on the tier the decline is ABOUT, never on its " +
                "sibling, and cardNote(null) keeps every undeclined tier silent with no id check " +
                "to forget when a third npu-class tier arrives",
            1,
            count(
                picker,
                "                    unavailableNote = NpuTierStatus.cardNote(npuTierReasons[model.id]),",
            ),
        )
        assertEquals(
            "it is rendered only when there is a decline to report, and through the RAM-gated " +
                "note's Warning surface — the pattern that already reads as \"this device, this " +
                "tier\", rather than the retired-model card, which is about a tier being " +
                "withdrawn from everybody. Asserted as one block so the condition, the surface " +
                "and the text cannot be separated from each other",
            1,
            count(
                picker,
                block(
                    "            if (unavailableNote != null) {",
                    "                Spacer(modifier = Modifier.height(8.dp))",
                    "                Surface(",
                    "                    color = Warning.copy(alpha = 0.12f),",
                    "                    shape = RoundedCornerShape(8.dp)",
                    "                ) {",
                    "                    Text(",
                    "                        text = unavailableNote,",
                ),
            ),
        )
        assertEquals(
            "and both device-scoped notes in this card use the SAME surface, so neither drifts " +
                "into looking like a different class of message than the other",
            2,
            count(
                picker,
                block(
                    "                Surface(",
                    "                    color = Warning.copy(alpha = 0.12f),",
                    "                    shape = RoundedCornerShape(8.dp)",
                    "                ) {",
                ),
            ),
        )
    }
}
