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
            "the picked Uri is handed to the manager's importer. A launcher whose result nothing " +
                "consumes is a picker that opens, closes, and silently does nothing",
            1,
            count(activity, ".importNpuAssetPair(uri) { soFar, total ->"),
        )
        assertEquals(
            "the returned state is what the screen renders — the importer REPORTS a refusal " +
                "rather than throwing one, so dropping the return value loses every error message",
            1,
            count(activity, "npuImportState = WhisperEverywhereApp.getInstance()"),
        )
        assertEquals(
            "and the screen is handed both halves: the state, and the way to start an import",
            1,
            count(activity, block("                npuImportState = npuImportState,")),
        )
        assertEquals(
            "the import runs in a coroutine, not on the result callback's thread",
            1,
            count(activity, block("            scope.launch {", "                npuImportState = NpuAssetImport.ImportState.Running(0L, 0L)")),
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
            "the tier card subscribes to the process-scoped mirror of " +
                "NpuWhisperBackend.unavailableReason — Q6 wrote that property for this reader and " +
                "until now it had none",
            1,
            count(picker, "val npuUnavailableReason by NpuTierStatus.unavailableReason.collectAsState()"),
        )
        assertEquals(
            "and the note is scoped to the npu tier's card, not shown on every tier",
            1,
            count(
                picker,
                block(
                    "                    unavailableNote = if (model.id == NpuAssetImport.TIER_ID)",
                    "                        NpuTierStatus.cardNote(npuUnavailableReason) else null,",
                ),
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
