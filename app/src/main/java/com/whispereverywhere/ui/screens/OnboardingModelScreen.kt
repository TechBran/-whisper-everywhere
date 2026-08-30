package com.whispereverywhere.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.whispereverywhere.WhisperEverywhereApp
import com.whispereverywhere.model.ModelInstallSignal
import com.whispereverywhere.model.ModelScope
import com.whispereverywhere.model.ModelTierCopy
import com.whispereverywhere.model.WhisperCatalog
import com.whispereverywhere.model.WhisperModel
import com.whispereverywhere.npu.NpuAssetImport
import com.whispereverywhere.npu.NpuFleetCensus
import com.whispereverywhere.npu.NpuImportController
import com.whispereverywhere.npu.NpuPackController
import com.whispereverywhere.npu.NpuPackFetch
import com.whispereverywhere.npu.NpuTierStatus
import com.whispereverywhere.ui.onboarding.ModelDownloadViewModel
import com.whispereverywhere.ui.onboarding.ModelDownloadViewModel.DownloadState
import com.whispereverywhere.ui.theme.Primary
import com.whispereverywhere.ui.theme.Success
import com.whispereverywhere.ui.theme.Warning
import com.whispereverywhere.util.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * @param npuImportState the SAF import's progress, owned by [com.whispereverywhere.MainActivity]
 *        because the document-picker launcher lives there (4.0, Q8). Defaulted so the screen keeps
 *        rendering for any caller that does not have an importer.
 * @param onImportNpuAssets opens the document picker FOR the tier id it is handed (4.1 L6 — the
 *        import is per-tier, and each card passes its own id). Its GATE is the capability half
 *        alone — see the `npuCapable` producer below.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingModelScreen(
    onModelReady: () -> Unit,
    npuImportState: NpuAssetImport.ImportState = NpuAssetImport.ImportState.Idle,
    onImportNpuAssets: (String) -> Unit = {},
    viewModel: ModelDownloadViewModel = viewModel()
) {
    val app = WhisperEverywhereApp.getInstance()
    val manager = app.whisperModelManager
    // Only tiers still offered — retired tiers stay resolvable via WhisperCatalog.byId for
    // existing users but must not be selectable by anyone new. 3.7 Workstream H orders them by
    // locale: the steered tier is first and carries the badge.
    val languageTag = java.util.Locale.getDefault().toLanguageTag()
    // 4.0/4.1: a gated tier joins the lineup only where the SoC gate, the QNN probe AND its own
    // context binaries all say yes — per tier, which is why the answer is a SET of tier ids
    // (WhisperEverywhereApp.offeredNpuTierIds). The probe dlopens two QNN libraries and
    // QnnAsrNative forbids Main for every entry point, so the answer is produced OFF Main; empty
    // until it arrives, which is the ungated lineup every device that will never pass the gate
    // keeps rendering.
    //
    // KEYED on the install generation. `produceState` with no key runs its producer once per
    // composition ENTRY and never again, so the freshly-stat'd installed half would be sampled
    // once and frozen — and Q8's import affordance lives in THIS composition, so the user would
    // import the pair and watch the lineup not change. The key is bumped by
    // PreferencesManager.notifyModelInstalled(), which every install path calls.
    //
    // 4.2 F7: the set is offered UNION fetchable — the same DISPLAY/steer union the guided
    // flow adopted at F6. The offered half is installed-and-capable; the fetchable half is
    // the tiers the census says Play can deliver here whose files have NOT arrived — each of
    // those renders a card whose action is the Play fetch (below). Routing never reads the
    // union: everything that routes a session keeps reading the offered set alone, because a
    // fetchable tier has nothing on disk to run.
    val installGeneration by ModelInstallSignal.generation.collectAsState()
    val npuTierIds by produceState(initialValue = emptySet<String>(), key1 = installGeneration) {
        value = withContext(Dispatchers.IO) { app.offeredNpuTierIds() + app.fetchableNpuTierIds() }
    }
    // THE SECOND PRODUCER, and it is a different question from the first (4.0, Q8).
    // The offer gate requires each tier's files on disk, so on a gate-passing device with no
    // assets the set is empty — and no gated tier is in the lineup at all. That is correct for
    // the CHOOSER and fatal for the IMPORT: an import entry gated on the offer gate could only
    // ever appear after the files it exists to fetch had already arrived. So the import's gate is
    // the capability half alone, read here. `npuCapableDevice` is `by lazy`, so this costs one
    // memo read after the first; it is keyed identically anyway, because what the panel below
    // renders depends on the installed half and must re-read when an import lands.
    val npuCapable by produceState(initialValue = false, key1 = installGeneration) {
        value = withContext(Dispatchers.IO) { app.npuCapableDevice }
    }
    val steerId = ModelTierCopy.steerIdForLanguageTagFor(languageTag, npuTierIds)
    val models = ModelTierCopy.orderedForLanguageTagFor(languageTag, npuTierIds)
        .mapNotNull { WhisperCatalog.byId(it) }

    // Which tiers are actually on disk. Off Main — `isInstalled` stats one or two files per tier —
    // and keyed on the same generation, so a finished download or import updates the cards without
    // leaving the screen. A card for an installed tier must not offer to download it (Q7a §9.3).
    val installedIds by produceState(
        initialValue = emptySet<String>(),
        key1 = installGeneration,
        key2 = npuTierIds,
    ) {
        value = withContext(Dispatchers.IO) {
            models.filter { manager.isInstalled(it) }.map { it.id }.toSet()
        }
    }

    // 4.2 F7 — the F3 §7.3 residual, landed by name: a gated card's byte badge states THE
    // DEVICE FAMILY'S measured pair, because the catalog's approximation understates a 7gen4
    // pair by ~4% and the census carries the honest number. Catalog fallback everywhere the
    // family cannot answer (CPU tiers, off-census devices). The answer is immutable while the
    // process lives — family and census cannot change — so this producer deliberately has no
    // key; it runs off Main because npuSocFamily's first read resolves Build fields.
    val censusPairBytes by produceState(initialValue = emptyMap<String, Long>()) {
        value = withContext(Dispatchers.IO) {
            val family = app.npuSocFamily ?: return@withContext emptyMap<String, Long>()
            WhisperCatalog.entries.filter { it.gated }.mapNotNull { m ->
                NpuFleetCensus.artifactFor(family.id, m.id)
                    ?.let { m.id to (it.encoder.bytes + it.decoder.bytes) }
            }.toMap()
        }
    }

    // 4.2 F7 — THE FETCH AFFORDANCE's state, mirrored from the process-scoped owner (F5). The
    // composition may not own an ~860 MB fetch any more than it may own a 358 MB import (the
    // I3 lesson): a recreation re-subscribes and finds the fetch exactly where it was — and
    // WHICH card renders it is the controller's own answer, so the state can never wear a
    // sibling card, and a fetch started before a rotation still lands on the right one.
    val fetchState by NpuPackController.state.collectAsState()
    val fetchTierId by NpuPackController.activeTier.collectAsState()

    // Play's own consent dialog (wifi-wait and the >200 MB cellular consent both), shown once
    // per ENTRY into NeedsConfirmation — the F6-established idiom: the LaunchedEffect key is
    // the state VALUE, so entering fires once, staying re-fires nothing, leaving and
    // re-entering fires again. Deliberately no custom re-ask anywhere: the consent is Play's
    // to word and to size.
    val context = LocalContext.current
    LaunchedEffect(fetchState) {
        if (fetchState is NpuPackFetch.FetchState.NeedsConfirmation) {
            NpuPackController.confirm(context as ComponentActivity)
        }
    }

    // Each npu-class tier's own decline report, keyed by tier id (per-tier since 4.1 L8): absent
    // while a tier is live or has never run, `"<stage>: <detail>"` after one of ITS stages
    // declined and that session fell back to the CPU model. The backend publishes from the setter
    // of its `unavailableReason` under `spec.tierId` (Q6/Q8); this map is that property's
    // consumer, and each card renders the same fact its own `npu: unavailable` log line carried —
    // the note appears on the tier it is ABOUT, never on its sibling. (`NpuTierStatus.reasonFor`
    // is the same read for non-Compose callers; a Compose card reads the collected map so a
    // decline recomposes it.)
    val npuTierReasons by NpuTierStatus.reasons.collectAsState()

    val state by viewModel.state.collectAsState()

    // The tier the user tapped (drives which card shows progress / error).
    var activeModelId by remember { mutableStateOf<String?>(null) }

    // Fire the ready callback exactly once when the download completes.
    LaunchedEffect(state) {
        val s = state
        if (s is DownloadState.Done) {
            onModelReady()
        }
    }

    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Choose a Speech Model", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.primary
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(scrollState)
                .padding(16.dp)
        ) {
            Text(
                text = "Speech runs 100% on your device. Pick a model to download once " +
                    "— it works offline afterward, and no audio ever leaves your phone.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            models.forEach { model ->
                val recommended = manager.isRecommendedForDevice(model)
                // The highlighted card is the STEERED one, not the catalog default: for a
                // non-English user those are different tiers, and highlighting the English-only
                // default is exactly the mistake the Bengali review reported. The flag is named
                // `isSteered` rather than the old `isDefault` because that is now what it means
                // (H3 review, m2b) — a parameter whose name contradicts its value outlives every
                // comment that apologises for it.
                val isSteered = model.id == steerId
                val isActive = activeModelId == model.id
                // 4.2 F7: a gated tier in the lineup that is NOT on disk joined it through the
                // union's fetchable half (offered requires installed), so its card's action is
                // the Play fetch. Selection is untouched: installedIds stays the only answer
                // to "can this be selected", and a fetchable card has nothing on disk to select.
                val fetchable = model.gated && !installedIds.contains(model.id)
                // ONE start site, handed this card's own tier id. The Get button, Retry and
                // the card-body tap all share it; the controller is single-flight and safe to
                // call again after any terminal state, so sharing costs nothing.
                val startFetch: () -> Unit = { NpuPackController.start(app, model.id) }

                ModelTierCard(
                    model = model,
                    recommended = recommended,
                    isSteered = isSteered,
                    installed = installedIds.contains(model.id),
                    // Per-tier (4.1 L8): the note renders on the tier the decline is ABOUT.
                    // cardNote(null) is null, so every undeclined tier — CPU tiers included —
                    // shows nothing, with no id check to forget when a third npu tier arrives.
                    unavailableNote = NpuTierStatus.cardNote(npuTierReasons[model.id]),
                    state = if (isActive) state else DownloadState.Idle,
                    // The fetch renders ONLY on the card whose tier the controller itself
                    // names — a sibling card can never wear another tier's progress.
                    fetch = if (fetchable && fetchTierId == model.id) fetchState else null,
                    // The honest size where the family's census answers; catalog otherwise.
                    pairBytes = censusPairBytes[model.id],
                    // 4.2 F7: a fetchable card's body tap FETCHES — the same one start site as
                    // the Get button — never the URL download, whose sink refuses gated tiers.
                    // Every other card keeps today's select.
                    onSelect = {
                        if (fetchable) startFetch() else {
                            activeModelId = model.id
                            viewModel.download(model)
                        }
                    },
                    onRetry = {
                        activeModelId = model.id
                        viewModel.download(model)
                    },
                    onFetch = startFetch,
                    // The card's Import button passes ITS OWN tier id — turbo's card must never
                    // import under npu's names and numbers, or vice versa.
                    onImport = { onImportNpuAssets(model.id) },
                    onUse = {
                        activeModelId = model.id
                        viewModel.select(model)
                    },
                )

                Spacer(modifier = Modifier.height(12.dp))
            }

            // THE IMPORT ENTRY. Its gate is `npuCapable` — the CAPABILITY half, never the offer
            // gate — because the offer gate includes `isInstalled` and an import affordance behind
            // it could only appear once the files it exists to fetch had already arrived. On a
            // gate-passing device with no assets there is no npu card in the lineup above, so this
            // panel is the only route the pair has onto the device, and it must not depend on the
            // pair being on the device.
            if (npuCapable) {
                NpuImportPanel(
                    // The panel imports the npu (small) pair, so what it SAYS keys on that
                    // tier being ON DISK — not on lineup membership, which since the F7 union
                    // a fetchable (nothing-on-disk) npu also satisfies, and not on the set
                    // being non-empty, which a turbo-only device would satisfy too.
                    offered = NpuAssetImport.TIER_ID in installedIds,
                    // 4.2 F7: one leading sentence exactly while a Get-on-Google-Play card
                    // renders above — derived from the same two sets the cards render from,
                    // so the sentence and the card cannot disagree.
                    fetchAbove = npuTierIds.any { it !in installedIds },
                    state = npuImportState,
                    // The panel imports the npu (small) pair — its copy says so — so it passes
                    // that tier's id, not whatever card happens to be above it.
                    onImport = { onImportNpuAssets(NpuAssetImport.TIER_ID) },
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

/**
 * The npu tier's asset-pair import, on any device whose silicon can run the tier (4.0, Q8).
 *
 * Rendered for capability alone, so it is present in exactly the state the chooser cannot show
 * an installed card for: the right phone, and 358 MB that has not arrived yet. [offered] only
 * changes what it SAYS — "enable it" before the pair lands, "replace it" afterwards — never
 * whether it is there. Since 4.2 F7 it is the sideload path AND the Play-failure fallback both:
 * [fetchAbove] adds one leading sentence while a fetch card renders above, so the two
 * affordances read as one story — primary and fallback — rather than two competing buttons.
 */
@Composable
private fun NpuImportPanel(
    offered: Boolean,
    fetchAbove: Boolean,
    state: NpuAssetImport.ImportState,
    onImport: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = if (offered) "AI chip model files" else "This device has an AI chip (NPU)",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = Primary,
            )
            Spacer(modifier = Modifier.height(6.dp))
            if (fetchAbove) {
                // 4.2 F7: the one-story sentence — the card's Play fetch is the primary
                // route, this panel the manual one. Only while such a card actually exists.
                Text(
                    text = "On Google Play installs the model downloads right from the card " +
                        "above — importing is the manual route.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(6.dp))
            }
            Text(
                text = if (offered)
                    "The multilingual NPU model is installed. Import the pair again only if you " +
                        "are replacing it — your existing files stay in place until the new ones " +
                        "have been checked."
                else
                    "The multilingual model can run on this device's AI chip, which is much " +
                        "faster than the CPU. Its files are not downloaded in the app: get the " +
                        "model pair zip from the release page, then import it here. It needs " +
                        "about 358 MB once installed, and roughly twice that free while importing.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(12.dp))

            when (state) {
                is NpuAssetImport.ImportState.Running -> {
                    val pct =
                        if (state.total > 0L) (state.soFar * 100 / state.total).toInt() else 0
                    LinearProgressIndicator(
                        progress = pct / 100f,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(4.dp)),
                        color = Primary,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Importing… $pct%  " +
                            "(${formatBytes(state.soFar)} / ${formatBytes(state.total)})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // The copy is minutes long and it survives leaving this screen, so there has to
                    // be a way to stop it. The importer's own `finally` clears the staged files, so
                    // cancelling costs nothing but the time already spent.
                    TextButton(
                        onClick = { NpuImportController.cancel() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Cancel import")
                    }
                }

                is NpuAssetImport.ImportState.Refused -> {
                    // Loud, and it names the reason: wrong size, a missing half of the pair, no
                    // room, or a file that is not a zip. A silent "nothing happened" is the one
                    // outcome an import is never allowed to have.
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(
                            Icons.Filled.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = state.reason,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = onImport, modifier = Modifier.fillMaxWidth()) {
                        Text("Pick the model zip again")
                    }
                }

                else -> {
                    if (state is NpuAssetImport.ImportState.Installed) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.CheckCircle,
                                contentDescription = null,
                                tint = Success,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Model pair imported.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Success,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    Button(onClick = onImport, modifier = Modifier.fillMaxWidth()) {
                        Text(if (offered) "Re-import model pair…" else "Import model pair…")
                    }
                }
            }
        }
    }
}

/**
 * @param installed this tier's files are on disk **now** (not "a download just finished"). An
 *        installed tier is never offered a Download: for npu that button used to delete the
 *        imported encoder and then fail against the provenance zip, and for every other tier it is
 *        simply untrue. Repair is still reachable — it just says what it is.
 * @param unavailableNote the npu tier's session-time decline, or null. Rendered with the same
 *        Warning surface as the RAM-gated note below, which already reads as "this device, this
 *        tier".
 * @param onImport opens the document picker for the asset-pair zip. Only a tier with a paired
 *        artefact can reach it.
 * @param onUse adopt an already-installed tier without touching the network. Before 4.0 the only
 *        way to choose a tier here was to download it, which for `npu` is a refusal — so the one
 *        tier that can only be installed by import had no way to be SELECTED from this screen.
 * @param fetch the Play fetch's state, non-null ONLY while the controller's running fetch is
 *        THIS tier's (4.2 F7). Null renders the resting affordance for a fetchable card and
 *        nothing extra for every other card.
 * @param pairBytes the device family's measured pair size from the census, or null where the
 *        family cannot answer — the badge then falls back to the catalog's approximation.
 * @param onFetch starts (or retries) the Play fetch of this tier's pack (4.2 F7).
 */
@Composable
private fun ModelTierCard(
    model: WhisperModel,
    recommended: Boolean,
    isSteered: Boolean,
    installed: Boolean,
    unavailableNote: String?,
    state: DownloadState,
    fetch: NpuPackFetch.FetchState?,
    pairBytes: Long?,
    onSelect: () -> Unit,
    onRetry: () -> Unit,
    onImport: () -> Unit = {},
    onUse: () -> Unit = {},
    onFetch: () -> Unit = {},
) {
    // 3.5.0: same source of truth as the onboarding chooser (ModelTierCopy) — the headline takes
    // the speed-vs-accuracy position, the badges make language coverage impossible to miss, the
    // body is the honest one-liner. Null only for a tier without copy (ModelTierCopyTest pins
    // that every pickable tier has some), which falls back to the old catalog-scope row.
    val copy = ModelTierCopy.forId(model.id)
    val downloading = state as? DownloadState.Downloading
    val verifying = state is DownloadState.Verifying
    val error = state as? DownloadState.Error
    val done = state is DownloadState.Done
    val isBusy = downloading != null || verifying

    val ramGated = model.minRamBytes > 0L

    // A tier whose files arrive by import, not by URL — npu, and nothing else today. The predicate
    // is the catalog's own (`pairedArtifact == null`), the same one `download()` refuses on, so the
    // card and the sink can never disagree about which tiers are downloadable.
    val downloadable = WhisperCatalog.isInstallableByDownload(model)

    val borderColor = when {
        done -> Success
        isSteered -> Primary
        else -> MaterialTheme.colorScheme.outline
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                // Tapping an INSTALLED card adopts it; tapping an uninstalled one fetches it.
                // Before 4.0 both meant "download", which re-fetched 190 MB for a tier already on
                // disk and, on npu, hit the refusal instead of ever selecting the tier.
                if (!isBusy) Modifier.clickable(onClick = if (installed) onUse else onSelect)
                else Modifier
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (isSteered)
                Primary.copy(alpha = 0.05f)
            else
                MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(if (isSteered || done) 2.dp else 1.dp, borderColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {

            // Title row: name + size
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = model.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    // The family's measured pair where the census answers (4.2 F7 — the F3
                    // §7.3 residual: the approximation understates a 7gen4 pair by ~4%).
                    text = formatBytes(pairBytes ?: model.approxBytes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            if (copy != null) {
                Text(
                    text = copy.headline,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Primary,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    copy.badges.forEach { badge -> CopyBadge(text = badge) }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = copy.body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Language,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = when (model.scope) {
                            ModelScope.ENGLISH -> "English only"
                            ModelScope.MULTILINGUAL -> "Multilingual (99 languages)"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Badges
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isSteered) {
                    TierBadge(text = ModelTierCopy.STEER_BADGE, color = Primary)
                }
                if (recommended) {
                    TierBadge(text = "Recommended for your device", color = Success)
                }
            }

            // "Unavailable on this device" — an npu-class tier's decline, per tier (4.0 Q8; L8).
            // Same Warning surface as the RAM-gated note directly below, which is the pattern the
            // brief names: it already reads as "this device, this tier", unlike the retired-model
            // card, which is about a tier being withdrawn from everyone. The text comes from
            // NpuTierStatus.cardNote, which is fed by NpuWhisperBackend.unavailableReason — so the
            // card and the `npu: unavailable stage=…` log line state one fact from one source.
            if (unavailableNote != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    color = Warning.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = unavailableNote,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = Warning
                    )
                }
            }

            // High-end-only note for RAM-gated tiers the device can't recommend.
            if (ramGated && !recommended) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    color = Warning.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = "High-end devices only — this tier needs more RAM than " +
                            "this device reports. You can still pick it, but performance " +
                            "may suffer.",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = Warning
                    )
                }
            }

            // Progress / error / action area
            when {
                downloading != null -> {
                    Spacer(modifier = Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = downloading.pct / 100f,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(4.dp)),
                        color = Primary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Downloading… ${downloading.pct}%  " +
                            "(${formatBytes(downloading.soFar)} / ${formatBytes(downloading.total)})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                verifying -> {
                    Spacer(modifier = Modifier.height(12.dp))
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(4.dp)),
                        color = Primary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Verifying download…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                error != null -> {
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Filled.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = error.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = onRetry) {
                            Text("Retry")
                        }
                    }
                }

                done -> {
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = Success,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Ready to use",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Success,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                // THE INSTALLED BRANCH (4.0, Q8; the Q7a §9.3 concern). Before it existed, EVERY
                // card's action was a Download button — including a tier already on disk. On npu
                // that button deleted the hand-imported 132,927,488-byte encoder and then failed
                // the size gate against the provenance zip, which is why `download()` now refuses
                // that tier at the sink. The refusal is the backstop; THIS is the fix: a card for
                // an installed tier offers to USE it, and says how it would be replaced.
                installed -> {
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = Success,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Installed on this device",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Success,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = onUse, modifier = Modifier.fillMaxWidth()) {
                        Text("Use this model")
                    }
                    // Repair stays reachable, and it is named for what it actually does on this
                    // tier: re-fetch for a URL tier, re-import for the paired one.
                    TextButton(
                        onClick = if (downloadable) onSelect else onImport,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (downloadable) "Download again" else "Re-import model pair…")
                    }
                }

                // Not installed, and not installable from a URL: the gated pair. Since 4.2 F7
                // this card only joins the lineup when the census says Play can deliver the
                // pair (the union's fetchable half), so its action is the Play fetch — the
                // import panel below stays the manual route and the failure fallback, and the
                // failed branch names it in one sentence.
                !downloadable -> {
                    Spacer(modifier = Modifier.height(12.dp))
                    FetchActionArea(fetch = fetch, onFetch = onFetch)
                }

                else -> {
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = onSelect,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            Icons.Filled.CloudDownload,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Download")
                    }
                }
            }
        }
    }
}

/**
 * The fetchable card's action slot (4.2 F7): "Get on Google Play" while no fetch runs, the live
 * fetch state while the controller says this tier's fetch is in flight, and on failure the
 * machine's own words — [NpuPackFetch.FetchState.Failed]'s reason VERBATIM, the F5 carrier
 * rule, which holds on THIS surface because the import affordance the ruled copy points at
 * ("below") really is below: the SAF panel sits under the lineup on this screen.
 *
 * Installed deliberately renders nothing special: `notifyModelInstalled()` bumps the install
 * generation, the producers re-run, and the card becomes the installed card — the existing
 * machinery, nothing bespoke. That is also why a post-removePack listener replay (the F5
 * review's watch item) cannot overwrite an installed card here: the installed rendering reads
 * the DISK through the generation-keyed producers, never this flow.
 */
@Composable
private fun FetchActionArea(
    fetch: NpuPackFetch.FetchState?,
    onFetch: () -> Unit,
) {
    when (fetch) {
        is NpuPackFetch.FetchState.Pending,
        is NpuPackFetch.FetchState.Transferring,
        -> FetchProgress(
            pct = null,
            line = "Starting the download from Google Play…",
            cancellable = true,
        )

        is NpuPackFetch.FetchState.NeedsConfirmation -> FetchProgress(
            pct = null,
            line = "Waiting for your OK in the Google Play dialog…",
            cancellable = true,
        )

        is NpuPackFetch.FetchState.Downloading -> {
            val pct = NpuPackFetch.pct(fetch.soFar, fetch.total)
            FetchProgress(
                pct = pct,
                line = "Downloading from Google Play… $pct%  " +
                    "(${formatBytes(fetch.soFar)} / ${formatBytes(fetch.total)})",
                cancellable = true,
            )
        }

        is NpuPackFetch.FetchState.Verifying -> {
            val pct = NpuPackFetch.pct(fetch.soFar, fetch.total)
            // The download is over — our checks are running, and they either land the pair
            // or refuse loudly, so there is nothing here a cancel would honestly stop.
            FetchProgress(pct = pct, line = "Verifying… $pct%", cancellable = false)
        }

        is NpuPackFetch.FetchState.Failed -> {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                // The machine's words, verbatim — the same render-what-the-machine-said rule
                // the import panel is pinned to. Never rewritten on this surface: the ruled
                // adjacency copy's precondition (the import control below) holds here.
                Text(
                    text = fetch.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onFetch) {
                    Text("Retry")
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            // The honest bridge to the panel that has always existed — one plain sentence.
            Text(
                text = "You can also import the model pair from a zip below.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Idle, Cancelled, Installed (transiently, until the generation-keyed producers
        // re-render this card as the installed card), or another card's fetch (null): the
        // resting offer.
        else -> {
            Button(onClick = onFetch, modifier = Modifier.fillMaxWidth()) {
                Icon(
                    Icons.Filled.CloudDownload,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Get on Google Play")
            }
        }
    }
}

/**
 * One fetch-progress rendering: the bar (determinate when [pct] is known), the status line,
 * and — while Play still owns the phase — a real cancel. The fetch survives leaving this
 * screen (the controller is process-scoped), so there has to be a way to stop it; cancelling
 * is honest: Play stops the download, the install job dies, and its `.part` files die with it.
 */
@Composable
private fun FetchProgress(pct: Int?, line: String, cancellable: Boolean) {
    if (pct != null) {
        LinearProgressIndicator(
            progress = pct / 100f,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp)),
            color = Primary,
        )
    } else {
        LinearProgressIndicator(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp)),
            color = Primary,
        )
    }
    Spacer(modifier = Modifier.height(6.dp))
    Text(
        text = line,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (cancellable) {
        TextButton(
            onClick = { NpuPackController.cancel() },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Cancel download")
        }
    }
}

@Composable
private fun TierBadge(text: String, color: androidx.compose.ui.graphics.Color) {
    Surface(
        color = color.copy(alpha = 0.12f),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.Verified,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = color,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/** A plain copy chip (no icon) for ModelTierCopy badges — language coverage and size. */
@Composable
private fun CopyBadge(text: String) {
    Surface(
        color = Primary.copy(alpha = 0.12f),
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = Primary,
            fontWeight = FontWeight.Bold
        )
    }
}
