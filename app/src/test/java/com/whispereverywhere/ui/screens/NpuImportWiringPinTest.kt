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

    /**
     * 4.2 F7 — THE FETCH AFFORDANCE (the brief's named red). Where the gate passes and the
     * tier's files have not arrived, the card's action is the Play fetch, and every wire of it
     * runs through the process-scoped F5 controller: start with THIS card's own tier id (never
     * a literal — turbo's card must never fetch npu's pack, nor npu's the ~860 MB one), one
     * real cancel, and Play's own consent dialog with no custom re-ask. The affordance is
     * Compose-bound, so the calls are pinned as source — the house instrument.
     */
    @Test
    fun theFetchAffordanceRoutesThroughNpuPackController() {
        assertEquals(
            "the fetchable card's button names the storefront, exactly",
            1,
            count(picker, "Text(\"Get on Google Play\")"),
        )
        assertEquals(
            "ONE start site in this file — shared by the Get button, Retry and the card-body " +
                "tap, so no second site can drift",
            1,
            count(picker, "NpuPackController.start("),
        )
        assertEquals(
            "and that one site passes the card's own model.id — never a literal. RE-SPELLED at " +
                "fix round 1 (in-commit — the old one-line form tripped this test by name " +
                "first): the site became a block because it now CONSUMES start()'s answer, " +
                "which is what turned the refused tap from silent into spoken (I-1)",
            1,
            count(picker, "                    val started = NpuPackController.start(app, model.id)"),
        )
        assertEquals(
            "a real cancel, at one site, through the same owner",
            1,
            count(picker, "onClick = { NpuPackController.cancel() },"),
        )
        assertEquals(
            "Play's own dialog at one site — no custom re-ask anywhere on this screen",
            1,
            count(picker, "NpuPackController.confirm("),
        )
    }

    /**
     * 4.2 F7 — the fetch card SPEAKS THE MACHINE'S WORDS. Progress carries Play's own numbers;
     * failure renders `FetchState.Failed.reason` VERBATIM — the F5 carrier rule, which holds
     * unrewritten on THIS surface because the import affordance the ruled adjacency copy
     * points at ("below") really is below: the SAF panel sits under the lineup on this screen.
     * The onboarding surface's per-surface rewrite exists precisely because THAT surface lacks
     * the affordance (F6 fix round 1, I-1) — so this surface must never borrow it.
     */
    @Test
    fun theFetchCardSpeaksTheMachinesWordsAndBridgesToTheImportPanel() {
        assertEquals(
            "the downloading line names the source and the percentage",
            1,
            count(picker, "line = \"Downloading from Google Play… ${'$'}pct%  \" +"),
        )
        assertEquals(
            "and carries Play's own byte counts, formatted like every other progress line",
            1,
            count(picker, "\"(${'$'}{formatBytes(fetch.soFar)} / ${'$'}{formatBytes(fetch.total)})\","),
        )
        assertEquals(
            "the failure branch renders the machine's reason, verbatim — the same " +
                "render-what-the-machine-said rule the import panel above is pinned to",
            1,
            count(picker, "text = fetch.reason,"),
        )
        assertEquals(
            "with Retry as the primary action — start is single-flight and safe after any " +
                "terminal state, and a failed verify left the delivered pack on disk",
            1,
            count(picker, "TextButton(onClick = onFetch) {"),
        )
        assertEquals(
            "and the honest bridge to the panel that has always existed, one plain sentence",
            1,
            count(picker, "\"You can also import the model pair from a zip below.\""),
        )
        assertEquals(
            "the reason is never rewritten on this surface — the onboarding rewrite belongs " +
                "to the surface without the affordance",
            0,
            count(picker, "onboardingFetchRefusal"),
        )
        assertEquals(
            "nor mapped through the onboarding card vocabulary — the chooser renders the " +
                "fetch machine's own state",
            0,
            count(picker, "engineStateForFetch"),
        )
    }

    /**
     * 4.2 F7 — WHICH card wears the fetch is the CONTROLLER'S fact, and an installed card can
     * never wear it at all. The fetch arm lives in the not-downloadable arm, which the
     * installed arm precedes (the order pinned in [anInstalledTierIsNeverOfferedADownload]),
     * and the installed rendering reads the DISK through the generation-keyed producers — so a
     * post-removePack listener replay (the F5 review's watch item) can at worst re-offer a
     * card the disk already contradicts for one recomposition, never overwrite an installed
     * one, and another tier's fetch can never bleed onto this card.
     */
    @Test
    fun theFetchStateRendersOnlyOnTheTierTheFetchIsFor() {
        assertEquals(
            "the card is handed the fetch ONLY while the controller names ITS tier",
            1,
            count(picker, "fetch = if (fetchable && fetchTierId == model.id) fetchState else null,"),
        )
        assertEquals(
            "fetchable is gated-and-not-on-disk — exactly the union's fetchable half, and the " +
                "SELECTION answer stays installedIds (a fetchable card has nothing to select)",
            1,
            count(picker, "val fetchable = model.gated && !installedIds.contains(model.id)"),
        )
        assertEquals(
            "the controller's active tier is collected from the process-scoped owner",
            1,
            count(picker, "val fetchTierId by NpuPackController.activeTier.collectAsState()"),
        )
        // RE-SPELLED at fix round 1, m-2 (in-commit — the old live-zero tripped by name first).
        // The claim was "Installed needs no rendering", and the arm's ABSENCE delivered it by
        // falling into `else` — which renders a LIVE Get button. In the window between the pair
        // landing and the generation-keyed producers re-rendering this card as installed, a tap
        // there re-fetches a pack that just installed. The claim is unchanged and now enforced
        // by an arm that renders NOTHING, which is what "needs no rendering" always meant.
        assertEquals(
            "Installed renders nothing at all: no control exists in the window before the " +
                "install signal turns this into the installed card",
            1,
            count(picker, "        is NpuPackFetch.FetchState.Installed -> Unit"),
        )
        assertEquals(
            "and it can never reach the resting arm's live button by falling through",
            0,
            count(picker, "is NpuPackFetch.FetchState.Installed -> {"),
        )
    }

    /**
     * F7 fix round 1, I-1 — **no tap on this screen is a silent no-op.** `start()` is
     * single-flight, so tapping Get on the npu card while turbo fetches returns false and
     * publishes NOTHING; before this fix the tapped card kept rendering its enabled Get button
     * and the user learned nothing. The Boolean is now consumed at the one start site and the
     * refusal — decided by the SHARED rule, worded for this surface — is remembered against the
     * tier that earned it and rendered on that card alone.
     */
    @Test
    fun noTapOnAFetchableCardIsASilentNoOp() {
        assertEquals(
            "the one start site CONSUMES start()'s answer and turns it into words through the " +
                "shared rule — a discarded Boolean is the silent no-op itself",
            1,
            count(
                picker,
                block(
                    "                val startFetch: () -> Unit = {",
                    "                    val started = NpuPackController.start(app, model.id)",
                    "                    fetchRefusal = OnboardingLogic.chooserFetchRefusal(",
                    "                        started, NpuPackController.activeTier.value, model.id,",
                    "                    )?.let { model.id to it }",
                    "                }",
                ),
            ),
        )
        assertEquals(
            "the refusal is remembered at screen level, so it survives the recomposition the " +
                "tap itself causes",
            1,
            count(picker, "var fetchRefusal by remember { mutableStateOf<Pair<String, String>?>(null) }"),
        )
        assertEquals(
            "and it renders on the card that EARNED it — never on the tier that is actually " +
                "fetching, which is already saying its own piece",
            1,
            count(picker, "refusal = fetchRefusal?.takeIf { it.first == model.id }?.second,"),
        )
        assertEquals(
            "the card renders the refusal in the action area, in the error voice",
            1,
            count(picker, "refusal != null -> {"),
        )
        assertEquals(
            "the chooser never borrows onboarding's sentence, which names a Retry button this " +
                "surface does not have",
            0,
            count(picker, "FETCH_BUSY_WITH_ANOTHER_MODEL"),
        )
    }

    /**
     * F7 fix round 1, I-2 — **the failure copy's import route must exist for the tier the card
     * is about.** F7 made the uninstalled gated card reachable here for the first time and, in
     * the same stroke, replaced that arm's per-tier Import button with the fetch affordance —
     * leaving turbo's failure copy ("import the model pair from a zip below", and the ruled
     * "Use 'Import model pair…' below instead") pointing at a panel that hardcodes npu and would
     * refuse a turbo zip on entry-name validation. The import machinery itself has been per-tier
     * since 4.1 L6 (`importNpuAssetPair(tierId, …)`, `PAIRED_TIER_IDS`), so the honest fix is to
     * give the affordance back to the card, which already passes its own `model.id`.
     */
    @Test
    fun everyFetchableCardCarriesItsOwnTiersImportRouteSoTheFailureCopyIsTrue() {
        assertEquals(
            "the fetchable card offers ITS OWN tier's import — the control the ruled adjacency " +
                "copy names, on the card whose tier it would install",
            1,
            count(
                picker,
                block(
                    "private fun FetchImportRoute(onImport: () -> Unit) {",
                    "    TextButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) {",
                    "        Text(\"Import model pair…\")",
                    "    }",
                    "}",
                ),
            ),
        )
        assertEquals(
            "it is rendered from ONE declaration, reached by each of the three arms in which " +
                "no fetch of this tier's is running — refused, failed, resting — so the route " +
                "the copy names is present in every state that names it, and the three cannot " +
                "drift apart. The in-flight arms deliberately do NOT offer it: nothing there " +
                "promises it, and a second route mid-fetch would be a competing button",
            3,
            count(picker, "            FetchImportRoute(onImport)"),
        )
        assertEquals(
            "the resting arm carries it, under the Get button",
            1,
            count(
                picker,
                block(
                    "        else -> {",
                    "            FetchGetButton(onFetch)",
                    "            FetchImportRoute(onImport)",
                    "        }",
                ),
            ),
        )
        assertEquals(
            "and the failed arm carries it directly under the sentence that promises it",
            1,
            count(
                picker,
                block(
                    "                text = \"You can also import the model pair from a zip below.\",",
                    "                style = MaterialTheme.typography.bodySmall,",
                    "                color = MaterialTheme.colorScheme.onSurfaceVariant,",
                    "            )",
                    "            FetchImportRoute(onImport)",
                ),
            ),
        )
        assertEquals(
            "the card's import still passes its OWN tier id — the whole point of L6, and what " +
                "makes the copy true for turbo rather than only for npu",
            1,
            count(picker, "onImport = { onImportNpuAssets(model.id) },"),
        )
        assertEquals(
            "the fetch area is handed that per-tier import",
            1,
            count(picker, "FetchActionArea(fetch = fetch, refusal = refusal, onFetch = onFetch, onImport = onImport)"),
        )
    }

    /**
     * F7 fix round 1, I-3 — **the panel may not contradict its own leading sentence.** With a
     * fetch card above, "Its files are not downloaded in the app" is false: they download from
     * the card, which is what the leading sentence says. The clause becomes the conditional it
     * always meant — the import is what you reach for when Play cannot deliver — and the
     * no-fetch-card state keeps today's copy verbatim, because there it is still true.
     */
    @Test
    fun theImportPanelDoesNotContradictItsOwnLeadingSentence() {
        assertEquals(
            "with a fetch card above, the body says import is the fallback FOR a Play failure",
            1,
            count(
                picker,
                block(
                    "                    fetchAbove ->",
                    "                        \"The multilingual model can run on this device's AI chip, which is \" +",
                    "                            \"much faster than the CPU. If Google Play can't deliver the files, \" +",
                    "                            \"get the model pair zip from the release page and import it here. \" +",
                    "                            \"It needs about 358 MB once installed, and roughly twice that free \" +",
                    "                            \"while importing.\"",
                ),
            ),
        )
        assertEquals(
            "the flat claim never renders beside the sentence that falsifies it: it survives " +
                "ONLY in the arm where no fetch card exists, where it is still true",
            1,
            count(picker, "\"faster than the CPU. Its files are not downloaded in the app: get the \" +"),
        )
        // ORDER: the `offered` arm must stay first (an installed pair is the narrowest state),
        // and `fetchAbove` must precede the flat `else`, or the contradiction returns with every
        // presence count above still satisfied.
        val offeredArm = indexOfOrFail(picker, "the panel", "                    offered ->")
        val fetchArm = indexOfOrFail(picker, "the panel", "                    fetchAbove ->")
        val flatArm = indexOfOrFail(picker, "the panel", "\"faster than the CPU. Its files are not downloaded in the app: get the \" +")
        assertTrue(
            "the panel's body arms are ordered installed -> fetchable -> neither",
            offeredArm < fetchArm && fetchArm < flatArm,
        )
    }

    /**
     * 4.2 F7 — a fetchable card's BODY TAP fetches, through the same one start site as the Get
     * button, and never reaches the URL download (whose sink refuses gated tiers — the
     * delete-first behaviour that once destroyed a hand-imported encoder must stay unreachable
     * from a card that fetches from Play).
     */
    @Test
    fun aFetchableCardsBodyTapStartsTheFetchAndNeverTheUrlDownload() {
        assertEquals(
            "the body-tap redirect: a fetchable card fetches; every other card keeps today's " +
                "select — asserted as one block so the redirect and the download call cannot " +
                "be separated",
            1,
            count(
                picker,
                block(
                    "                    onSelect = {",
                    "                        if (fetchable) startFetch() else {",
                    "                            activeModelId = model.id",
                    "                            viewModel.download(model)",
                    "                        }",
                    "                    },",
                ),
            ),
        )
        assertEquals(
            "the clickable itself is untouched — installed adopts, uninstalled selects (which " +
                "for a fetchable card now means the Play fetch, via the redirect above)",
            1,
            count(picker, "Modifier.clickable(onClick = if (installed) onUse else onSelect)"),
        )
    }

    /**
     * 4.2 F7 — the import panel: SAME GATE, SAME JOB, one new sentence. It stays
     * capability-gated (the sideload path and the Play-failure fallback both need it exactly
     * when the chooser cannot show an installed card), and its idle copy gains one leading
     * sentence exactly while a Get-on-Google-Play card renders above, so the two affordances
     * read as one story — primary and fallback — rather than two competing buttons.
     */
    @Test
    fun theImportPanelKeepsItsGateAndGainsTheOneStorySentence() {
        assertEquals(
            "the one leading sentence, verbatim, present exactly once",
            1,
            count(
                picker,
                "text = \"On Google Play installs the model downloads right from the card \" +",
            ),
        )
        assertEquals(
            "the sentence's second half, unbroken",
            1,
            count(picker, "\"above — importing is the manual route.\","),
        )
        assertEquals(
            "and it renders exactly while a fetch card exists above — derived from the same " +
                "two sets the cards themselves render from, so the sentence and the card " +
                "cannot disagree",
            1,
            count(picker, "fetchAbove = npuTierIds.any { it !in installedIds },"),
        )
        assertEquals(
            "the panel's installed-wording gate reads the DISK set: since the F7 union, " +
                "lineup membership no longer means installed",
            1,
            count(picker, "offered = NpuAssetImport.TIER_ID in installedIds,"),
        )
        assertEquals(
            "and the union spelling of that gate is gone — it would claim a fetchable pair " +
                "was already installed",
            0,
            count(picker, "offered = NpuAssetImport.TIER_ID in npuTierIds,"),
        )
    }

    /**
     * 4.2 F7 — the byte badge tells the census's truth (the F3 §7.3 residual, landed by name):
     * on a census family a gated card's size is THE FAMILY'S measured pair — encoder plus
     * decoder — because the catalog's approximation understates a 7gen4 pair by ~4%. Every
     * tier the census cannot answer for keeps the catalog figure.
     */
    @Test
    fun theByteBadgeStatesTheFamilysMeasuredPairBytesWhereTheFamilyAnswers() {
        assertEquals(
            "the census map is produced off Main from the family's own rows",
            1,
            count(
                picker,
                block(
                    "    val censusPairBytes by produceState(initialValue = emptyMap<String, Long>()) {",
                    "        value = withContext(Dispatchers.IO) {",
                ),
            ),
        )
        assertEquals(
            "the pair bytes are the family row's encoder plus decoder — the honest number",
            1,
            count(picker, "?.let { m.id to (it.encoder.bytes + it.decoder.bytes) }"),
        )
        assertEquals(
            "the badge renders the census figure where the family answers, the catalog " +
                "approximation otherwise",
            1,
            count(picker, "text = formatBytes(pairBytes ?: model.approxBytes),"),
        )
        assertEquals(
            "and each card is handed ITS tier's figure",
            1,
            count(picker, "pairBytes = censusPairBytes[model.id],"),
        )
    }
}
