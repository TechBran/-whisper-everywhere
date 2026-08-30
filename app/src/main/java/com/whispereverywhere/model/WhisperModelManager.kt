package com.whispereverywhere.model

import android.app.ActivityManager
import android.app.DownloadManager
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import androidx.core.net.toUri
import com.whispereverywhere.WhisperEverywhereApp
import com.whispereverywhere.data.local.PreferencesManager
import com.whispereverywhere.npu.NpuAssetImport
import com.whispereverywhere.npu.NpuDiag
import com.whispereverywhere.npu.NpuFleetCensus
import com.whispereverywhere.npu.NpuModelSpec
import com.whispereverywhere.npu.NpuPackFetch
import com.whispereverywhere.npu.NpuPackMetadata
import com.whispereverywhere.npu.NpuSocFamily
import com.whispereverywhere.transcription.ModelPathProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Android shell over [WhisperCatalog]. Holds no decision logic beyond framework wiring
 * (Context/ActivityManager/DownloadManager/File). All pure logic lives in [WhisperCatalog]
 * and is unit-tested there; download()/verify-on-disk is covered by an instrumented test.
 */
class WhisperModelManager(
    private val context: Context,
    private val prefs: PreferencesManager,
) : ModelPathProvider {

    val catalog: List<WhisperModel> = WhisperCatalog.entries

    fun modelById(id: String): WhisperModel? = WhisperCatalog.byId(id)

    /** context.filesDir/models, created if missing. */
    fun modelsDir(): File {
        val dir = File(context.filesDir, "models")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun fileFor(model: WhisperModel): File = File(modelsDir(), model.fileName)

    /**
     * Installed = every file this tier needs is present at its own size, within ±5% of the
     * DEVICE FAMILY'S census bytes (4.2 F5 — the F3 carry, by name), or of the catalog's
     * reference record when the family cannot answer.
     *
     * The reference selection is [NpuAssetImport.installedGateBytes], a pure decision with its
     * own tests, because the F3 measurement proved the catalog reference is not one number per
     * tier: both 7gen4 encoders sit outside ±5% of it (+11.0%/+9.1%), so around the catalog a
     * CORRECT 7gen4 import verified exactly and then failed THIS predicate inside the
     * finalise's own verification and rolled itself back. The family memo is the F2 chain
     * (`WhisperEverywhereApp.npuSocFamily` — the one resolution, never re-derived), and a
     * single-file tier never resolves an artifact row at all, so the six ggml tiers keep the
     * predicate they have always had.
     *
     * The primary is gated against the gate's `primaryBytes`, NOT `approxBytes`: for a paired
     * tier `approxBytes` is the sum of both files, and comparing the encoder alone against the
     * pair's total is 63% out — the tier would read as "not installed" forever no matter what
     * the owner imported.
     */
    fun isInstalled(model: WhisperModel): Boolean {
        val artifact = (context.applicationContext as? WhisperEverywhereApp)?.npuSocFamily
            ?.let { family -> NpuFleetCensus.artifactFor(family.id, model.id) }
        val gate = NpuAssetImport.installedGateBytes(model, artifact)
        val f = fileFor(model)
        if (!f.exists() || !WhisperCatalog.sizeWithinTolerance(f.length(), gate.primaryBytes)) return false
        val paired = gate.paired ?: return true
        val pf = File(modelsDir(), paired.fileName)
        return pf.exists() && WhisperCatalog.sizeWithinTolerance(pf.length(), paired.bytes)
    }

    /** The selected model, if it is actually installed on disk. */
    fun installedModel(): WhisperModel? {
        val model = WhisperCatalog.byId(prefs.selectedModelId) ?: return null
        return if (isInstalled(model)) model else null
    }

    /**
     * The selected tier when it is UNSUPPORTED — still resolvable and possibly still installed,
     * but one the app wants users off of. Drives the migration prompt. Merely RETIRED tiers
     * (eco, base since 3.7) deliberately return null here: they are hidden from the chooser and
     * otherwise untouched. Returns null in the normal case.
     */
    fun unsupportedInstalledModel(): WhisperModel? {
        val model = WhisperCatalog.byId(prefs.selectedModelId) ?: return null
        return if (model.unsupported) model else null
    }

    /**
     * EVERY file this tier occupies: the primary, plus [WhisperModel.pairedArtifact] when it has
     * one (4.0, final review F1). One list, so the three things that must agree about a tier's
     * footprint — what deletion removes, what the delete dialog promises, and what the storage row
     * shows — cannot answer differently.
     */
    private fun tierFiles(model: WhisperModel): List<File> =
        listOfNotNull(
            fileFor(model),
            model.pairedArtifact?.let { File(modelsDir(), it.fileName) },
        )

    /**
     * Bytes this tier occupies on disk right now, or [WhisperModel.approxBytes] when none of its
     * files are present.
     *
     * Both size reads in Settings go through here. Reading `fileFor(model).length()` directly was
     * the F1 defect on the UI side: for `npu` it reports the 132,927,488 B encoder and omits the
     * 225,316,864 B decoder, so the delete dialog promised *"Frees 127 MB"* for a 342 MiB install
     * while the "Model storage" walk directly above it on the same screen disagreed by 215 MB.
     */
    fun installedBytes(model: WhisperModel): Long {
        val present = tierFiles(model).filter { it.exists() }
        return if (present.isEmpty()) model.approxBytes else present.sumOf { it.length() }
    }

    /**
     * Delete a tier's files from disk. Returns true if anything was actually removed.
     *
     * **Both files, and that is the whole fix (F1).** This removed `model.fileName` only, which for
     * the paired `npu` tier stranded a 225,316,864 B decoder in `filesDir/models` with **no
     * affordance that could ever remove it**: once the encoder is gone `installedModel()` is null,
     * the Settings delete row disappears, and the only route left is clearing app data. The
     * symmetry with the import is deliberate — that path installs both or neither, so removal has
     * to take both or the tier's lifecycle is not closed.
     *
     * A file that refuses to delete is reported loudly rather than swallowed: the caller's Boolean
     * cannot express "one of two", and a silent survivor is how this defect looked in the first
     * place.
     */
    fun deleteModelFile(model: WhisperModel): Boolean {
        var removed = false
        tierFiles(model).forEach { f ->
            if (!f.exists()) return@forEach
            if (f.delete()) removed = true
            else android.util.Log.w("WEModelDL", "delete failed, file remains: ${f.name}")
        }
        return removed
    }

    /** Absolute path to the installed selected model file, or null. */
    override fun installedModelPath(): String? {
        val model = installedModel() ?: return null
        return fileFor(model).absolutePath
    }

    /**
     * The paired artefact of the SELECTED installed tier — npu's decoder context binary (4.0, Q8).
     *
     * It resolves off [installedModel] rather than off the `npu` entry directly, and that is the
     * invariant rather than a convenience: the seam's contract is that this is the companion **of
     * the file [installedModelPath] just returned**. Resolving the two from different tiers would
     * hand `nativeInit` an encoder from one tier and a decoder from another, which is a native-side
     * failure with no Kotlin-side symptom. Null for every one-file tier, which is all six ggml ones.
     */
    override fun companionModelPath(): String? {
        val model = installedModel() ?: return null
        val paired = model.pairedArtifact ?: return null
        return File(modelsDir(), paired.fileName).absolutePath
    }

    /**
     * An installed **80-bin whisper ggml model**: the NPU tier's mel-filterbank donor and the CPU
     * backend it falls back to (4.0, Q8; the D-Q6-1 ruling — one file, two uses, donor ⊂ fallback).
     *
     * **Both exclusions are load-bearing and both are spelled out.**
     *  - `ultra` (`large-v3-turbo`) carries a **128-bin** filterbank. `pcmToMel` refuses it by bin
     *    count, so as a donor it is not a degraded choice, it is a failed load — and as a *fallback*
     *    it would otherwise be preferred by an `ultra` user purely because it is what they have.
     *  - `npu` is not a ggml file at all: it is the encoder context binary this tier is trying to
     *    arm. Handing it to `initMelOnly` is handing a QAIRT blob to a whisper.cpp loader.
     *
     * The `pairedArtifact == null` clause excludes `npu` a second time, structurally, and would
     * catch a future two-artefact tier nobody thought to name here — the same "the id says why, the
     * structure catches the next one" pairing `isInstallableByDownload` uses. The residual risk is
     * stated rather than hidden: a future SINGLE-file 128-bin tier would qualify by both clauses,
     * because the catalog records no mel-bin count. Adding one is the real fix and it is a catalog
     * change, not a manager change.
     *
     * The **selected** tier is preferred when it qualifies, so a session that falls back falls back
     * to the model the user actually chose; otherwise the first installed eligible tier in catalog
     * order answers, because any 80-bin filterbank is byte-identical to any other and for the
     * fallback something is unambiguously better than nothing.
     *
     * Null means the NPU tier cannot come up AND cannot fall back — a clean refusal at
     * `stage=mel-donor`, before any of the 358 MB is touched.
     */
    override fun cpuTierModelPath(): String? {
        val preferred = WhisperCatalog.byId(prefs.selectedModelId)
        val candidates = listOfNotNull(preferred) + WhisperCatalog.entries
        val donor = candidates.firstOrNull { isMelDonorEligible(it) && isInstalled(it) }
            ?: return null
        return fileFor(donor).absolutePath
    }

    /**
     * The selected tier's catalog id (4.0, Q9 fix round). Straight off preferences and NOT through
     * [installedModel]: the question the batch path asks is *which tier was chosen*, so that it can
     * decide whether the file [installedModelPath] just handed it is a whisper.cpp ggml at all.
     * Filtering by installedness here would answer null for the exact case that needs an answer.
     */
    override fun selectedTierId(): String? = prefs.selectedModelId

    /**
     * May [model]'s file serve as the 80-bin mel donor and CPU fallback? See [cpuTierModelPath] for
     * why each clause is here; `retired` tiers are deliberately eligible — eco and base are ordinary
     * 80-bin whisper models, and an installed one is a perfectly good donor and a real fallback.
     *
     * **The first clause is STRUCTURAL, not a literal** (4.1 L3). It was `model.id != "npu"`, which
     * excluded exactly one id: `npu-turbo` is also a QAIRT context binary rather than a ggml file,
     * and a second literal is something somebody has to remember to add for every npu-class tier
     * that ever ships. `NpuModelSpec.forTier(model.id) == null` asks the question the exclusion is
     * actually about — *is this an NPU tier?* — at the one table that answers it, so the next row
     * is excluded by the clause that already excludes this one.
     *
     * `ultra` stays excluded **by name**, and that is not an oversight. It is a 128-bin *ggml*,
     * which is a different fact from being an NPU tier: it is a perfectly real whisper model and a
     * perfectly good CPU fallback, and it is refused here only because its filterbank is the wrong
     * width for the 80-bin arm. The real fix is a mel-bin count in the catalog, which is a catalog
     * change rather than a manager change.
     */
    private fun isMelDonorEligible(model: WhisperModel): Boolean =
        NpuModelSpec.forTier(model.id) == null && model.id != "ultra" && model.pairedArtifact == null

    /** Total device RAM in bytes (ActivityManager.MemoryInfo.totalMem). */
    fun deviceTotalRamBytes(): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.totalMem
    }

    /** True when this device has at least the model's recommended RAM. */
    fun isRecommendedForDevice(model: WhisperModel): Boolean =
        WhisperCatalog.isRecommendedForDevice(model, deviceTotalRamBytes())

    /**
     * Download [model] via Android DownloadManager into modelsDir, reporting (soFar, total)
     * as it progresses; [onVerifying] fires once when the network part is done and the
     * move + sha256 verification begins. Main-safe: the entire body runs on Dispatchers.IO
     * (the move + streaming hash of up to ~574 MB used to freeze the UI for 10-20 s when a
     * caller invoked this from the main dispatcher).
     * On completion, size-gate + sha256 verify; delete + throw on mismatch.
     *
     * **Refuses a tier it structurally cannot install, BEFORE touching anything** (4.0, Q7b fix
     * round). See the guard below: the order of those two statements is the difference between a
     * loud no-op and a data-destroying one.
     */
    suspend fun download(
        model: WhisperModel,
        onProgress: (soFar: Long, total: Long) -> Unit,
        onVerifying: () -> Unit = {},
    ): Unit = withContext(Dispatchers.IO) {
        // REFUSAL FIRST — before the DownloadManager handle, before `dest`, and above all before
        // the `if (dest.exists()) dest.delete()` two lines down. THE ORDER IS THE FIX, not the
        // check: run this after that delete and the guard is decoration on a file that is already
        // gone.
        //
        // What it prevents, on the only device that can reach it — a gate-passing phone with the
        // npu pair imported by hand: the card's Download button deletes the 132,927,488-byte
        // encoder immediately, fetches ~423 MB of provenance zip over a possibly-metered link,
        // fails the size gate against the PAIR's 358,244,352 bytes, deletes that too, and leaves
        // `isInstalled(npu)` false — so the tier's card silently vanishes on the next composition
        // and the only way back is re-importing. `download()` never reads `pairedArtifact`, so this
        // path could never have installed the tier even with a correct URL.
        //
        // At the SINK on purpose. THREE call sites reach it, not the two an earlier draft of this
        // comment claimed: the picker's Download button (via `ModelDownloadViewModel.download`),
        // `OnboardingSetupViewModel.ensureSpeech()` — which serves Home's missing-engine row for
        // whatever `prefs.selectedModelId` names — and `SettingsScreen.kt`'s
        // `ModelMigration.Action.OfferDownload` handler. The third cannot reach `npu` today,
        // because every migration target is `pro`/`multi` by construction and that is pinned; it is
        // named anyway, because it is the argument FOR the sink: a guard placed at the call sites
        // someone had enumerated would have left open the one they had not, and Q8 adds a fourth
        // install path (`importNpuAssetPair`) that deliberately does not come through here at all.
        if (!WhisperCatalog.isInstallableByDownload(model)) {
            android.util.Log.w("WE-DIAG", WhisperCatalog.notInstallableByDownloadReason(model))
            throw ModelDownloadException(
                "${model.displayName} is installed by importing its files, not by downloading. " +
                    "Nothing on this device was changed."
            )
        }

        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val dest = fileFor(model)
        if (dest.exists()) dest.delete()

        val downloadDest = externalDownloadDest(model)

        // Fast path: a prior attempt fully downloaded the file but the process died before the
        // move+verify (or the user backed out). Don't burn another 60-570 MB of network — verify
        // what's already on disk; the sha256 gate below rejects it if it's actually bad.
        if (downloadDest.exists() &&
            WhisperCatalog.sizeWithinTolerance(downloadDest.length(), model.approxBytes)
        ) {
            android.util.Log.i("WE-DIAG", "download: reusing completed file at ${downloadDest.absolutePath}")
            onProgress(downloadDest.length(), downloadDest.length())
            onVerifying()
            moveVerified(downloadDest, dest, model)
            return@withContext
        }

        // Free-space gate BEFORE burning network: the flow transiently needs the model at the
        // external destination AND the internal move target. Checked per-filesystem with a
        // small safety margin; a mid-download disk-full otherwise surfaces as opaque "reason=1006".
        val required = (model.approxBytes * 1.1).toLong()
        val extFree = runCatching {
            StatFs(downloadDest.parentFile!!.apply { mkdirs() }.absolutePath).availableBytes
        }.getOrDefault(Long.MAX_VALUE)
        val intFree = runCatching { StatFs(modelsDir().absolutePath).availableBytes }
            .getOrDefault(Long.MAX_VALUE)
        if (extFree < required || intFree < required) {
            val needMb = required / 1_000_000
            throw ModelDownloadException(
                "Not enough free storage: this model needs about ${needMb} MB free. " +
                    "Clear some space and try again."
            )
        }

        // Clear any leftover state from a prior attempt at this model BEFORE enqueue. Otherwise a
        // stale DownloadManager row or a leftover file at the EXTERNAL download destination makes
        // DownloadManager stall at 0 bytes / fail with ERROR_FILE_ALREADY_EXISTS — the "downloads
        // fine the first time but sticks at 0 MB after a delete + retry" bug. (The old guard above
        // only cleared the INTERNAL destination, never the external one DownloadManager writes to.)
        removeStaleDownloads(dm, model)
        android.util.Log.i("WE-DIAG", "download prep: extDest=${downloadDest.absolutePath} existed=${downloadDest.exists()}")
        if (downloadDest.exists()) downloadDest.delete()

        val request = DownloadManager.Request(model.url.toUri())
            .setTitle(model.displayName)
            .setDescription("Downloading speech model")
            .setDestinationInExternalFilesDir(
                context,
                Environment.DIRECTORY_DOWNLOADS,
                "models/${model.fileName}",
            )
            .setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED,
            )
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)

        val id = dm.enqueue(request)
        android.util.Log.i("WE-DIAG", "download enqueued id=$id url=${model.url}")
        try {
            var done = false
            var lastLoggedStatus = -1
            var lastLoggedMs = 0L
            while (!done) {
                val query = DownloadManager.Query().setFilterById(id)
                val cursor: Cursor = dm.query(query)
                cursor.use { c ->
                    if (c.moveToFirst()) {
                        val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                        val soFar = c.getLong(
                            c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR),
                        )
                        val total = c.getLong(
                            c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES),
                        )
                        onProgress(soFar, if (total > 0) total else model.approxBytes)
                        val nowMs = System.currentTimeMillis()
                        if (status != lastLoggedStatus || nowMs - lastLoggedMs >= 2000) {
                            android.util.Log.i("WE-DIAG", "download poll: status=$status soFar=$soFar total=$total")
                            lastLoggedStatus = status; lastLoggedMs = nowMs
                        }

                        when (status) {
                            DownloadManager.STATUS_SUCCESSFUL -> {
                                val localUri = c.getString(
                                    c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI),
                                )
                                onVerifying()
                                moveToModelsDir(localUri, dest)
                                done = true
                            }
                            DownloadManager.STATUS_FAILED -> {
                                val reason = c.getInt(
                                    c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON),
                                )
                                android.util.Log.w("WE-DIAG", "download FAILED reason=$reason")
                                throw ModelDownloadException("Download failed (reason=$reason)")
                            }
                            else -> Unit // PENDING / RUNNING / PAUSED -> keep polling
                        }
                    } else {
                        // Row gone (e.g. user cancelled via notification).
                        throw ModelDownloadException("Download entry disappeared")
                    }
                }
                if (!done) delay(POLL_INTERVAL_MS)
            }
        } finally {
            // The DownloadManager row is no longer needed once we've moved the file.
            dm.remove(id)
        }

        verifyDest(dest, model)
    }

    /** Move [src] into place at [dest] and run the full verification gates. */
    private fun moveVerified(src: File, dest: File, model: WhisperModel) {
        if (dest.exists()) dest.delete()
        if (!src.renameTo(dest)) {
            src.copyTo(dest, overwrite = true)
            src.delete()
        }
        verifyDest(dest, model)
    }

    /**
     * Verify [dest] on disk: size gate THEN sha256 (streaming — never loads the whole file).
     * Any exception (expected or unexpected) deletes the partial/corrupt file before rethrowing.
     */
    private fun verifyDest(dest: File, model: WhisperModel) {
        try {
            val actualLen = dest.length()

            // (1) Size gate — always applied.
            if (!WhisperCatalog.sizeWithinTolerance(actualLen, model.approxBytes)) {
                dest.delete()
                throw ModelDownloadException(
                    "Size verification failed for ${model.fileName}: " +
                        "got $actualLen bytes, expected ~${model.approxBytes}"
                )
            }

            // (2) SHA-256 gate — only when the digest is a real 64-char hex value (not "PENDING").
            val expected = model.sha256.trim().lowercase()
            val isRealDigest = expected.length == 64 && expected.all { it in "0123456789abcdef" }
            if (isRealDigest) {
                val actual = WhisperCatalog.sha256HexFile(dest)
                if (!actual.equals(expected, ignoreCase = true)) {
                    dest.delete()
                    throw ModelDownloadException(
                        "SHA-256 verification failed for ${model.fileName}"
                    )
                }
            }
        } catch (e: ModelDownloadException) {
            throw e                  // already deleted dest above; just rethrow
        } catch (e: Exception) {
            dest.delete()            // unexpected IO or other error — clean up partial file
            throw e
        }
        // 3.6.0 (Workstream E1): the model is now verifiably ON DISK, which is the moment the
        // bubble's native context became stale. Emitted here — the one point both download paths
        // funnel through (the poll loop's STATUS_SUCCESSFUL branch calls verifyDest directly, the
        // reuse-a-completed-file fast path reaches it through moveVerified) — and AFTER the size
        // and sha256 gates, so a signal is never sent for a file that gets deleted a line later.
        // The selectedModelId flow cannot cover this: onboarding writes the id BEFORE the file
        // exists and rewrites the SAME id after, which a StateFlow conflates away entirely.
        prefs.notifyModelInstalled()
    }

    private fun moveToModelsDir(localUri: String?, dest: File) {
        val src = localUri?.let { File(Uri.parse(it).path ?: return@let null) }
        if (src == null) {
            throw ModelDownloadException(
                "Cannot resolve local download path from URI: $localUri"
            )
        }
        if (dest.exists()) dest.delete()
        if (!src.renameTo(dest)) {
            src.copyTo(dest, overwrite = true)
            src.delete()
        }
    }

    /** The path DownloadManager writes to (external app files dir); the file is later moved internal. */
    private fun externalDownloadDest(model: WhisperModel): File =
        File(File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "models"), model.fileName)

    /**
     * Remove any DownloadManager rows (and their files) for this model's URL left by a prior,
     * possibly process-killed, attempt, so a fresh enqueue starts from a clean destination.
     */
    private fun removeStaleDownloads(dm: DownloadManager, model: WhisperModel) {
        try {
            dm.query(DownloadManager.Query()).use { c ->
                val idIdx = c.getColumnIndex(DownloadManager.COLUMN_ID)
                val uriIdx = c.getColumnIndex(DownloadManager.COLUMN_URI)
                if (idIdx < 0 || uriIdx < 0) return
                while (c.moveToNext()) {
                    if (c.getString(uriIdx) == model.url) dm.remove(c.getLong(idIdx))
                }
            }
        } catch (t: Throwable) {
            android.util.Log.w("WEModelDL", "removeStaleDownloads failed", t)
        }
    }

    /**
     * Remove EVERY installed file for [model], plus any external download leftovers / stale rows.
     *
     * Paired-aware since the final review's F1 — see [deleteModelFile] for why leaving the second
     * artefact behind was unrecoverable rather than untidy. The external-destination and
     * DownloadManager sweeps below stay single-file on purpose: a paired tier is not installable by
     * download at all ([WhisperCatalog.isInstallableByDownload]), so it can have no leftovers there.
     */
    fun delete(model: WhisperModel) {
        tierFiles(model).forEach { if (it.exists()) it.delete() }
        // Also clear the external download destination + stale DownloadManager rows, so a later
        // re-download can't stall at 0 bytes on a leftover-file collision.
        val ext = externalDownloadDest(model)
        if (ext.exists()) ext.delete()
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        removeStaleDownloads(dm, model)
    }

    /**
     * Install a paired tier from the asset-pair zip the owner picked with the document picker
     * (4.0 Q8; per-tier and sha256-verified since 4.1 L6). The **second** install path in this
     * app, and the only one that is not a download.
     *
     * ```
     * resolve tierId  ->  reconcile .prev debris  ->  free-space precheck
     *   ->  inflate each allowed entry to <name>.part, BOUNDED to expected+1 bytes,
     *       sha256 streamed from the same buffers the write takes
     *   ->  size-verify each  ->  digest-verify each  ->  both present?
     *   ->  PHASE 1 park each installed file as <name>.prev
     *   ->  PHASE 2 rename each .part into place
     *   ->  isInstalled?          (any failure above: roll back, report the real state)
     *   ->  drop the .prev copies  ->  notifyModelInstalled()
     * ```
     *
     * **Per-tier since L6.** [tierId] names which of `NpuAssetImport.PAIRED_TIER_IDS` this zip is
     * for; every number below — the allow-list names, both exact lengths, both digests, the
     * free-space budget — scales off that tier's own catalog entry, so turbo's ~1.07 GB pair flows
     * through the identical transaction npu's 358 MB pair proved out. The digest verification is
     * STREAMED during the copy (never a second read of a ~GB file) and a hash failure lands in
     * the same refusal path as a size failure, before anything is parked. The owner's `adb push`
     * dev route never enters this function and stays hash-exempt by design — the run-book states
     * it where it prescribes the push.
     *
     * **Both-or-neither, and a re-import is non-destructive.** Every entry lands as `.part`
     * alongside whatever is already installed, so an existing pair survives a zip that turns out to
     * be truncated, wrong, oversized or not a zip at all — nothing is finalised until BOTH files
     * exist at their exact published lengths.
     *
     * **A second import over an existing pair parks the old files rather than deleting them**
     * (fix round 1, I2). The previous encoder and decoder are renamed to `.prev` and kept until the
     * whole transaction — both renames *and* the `isInstalled` verification — has succeeded, so a
     * failure at any point puts them back. Deleting each destination first, as the first draft did,
     * meant a failed SECOND rename left the device with neither pair while the message still said
     * "Nothing was installed"; a rename cannot be rolled back onto a file that has already been
     * unlinked, which is why the fix is the parking and not a smarter undo.
     *
     * `isInstalled(npu)` is therefore true before the transaction and true after a successful one.
     * A MIXED pair exists only between the two phase-2 renames — a pair of `renameTo` calls on one
     * directory with nothing between them — and observing it requires arming the tier inside that
     * window **or killing the process inside it**. The second case used to outlive the window:
     * per-file reconciliation would then have *rebuilt* the mix on the next launch and left it
     * there. It no longer can — [reconcileStagingDebris] finishes an interrupted finalise in one
     * direction for the whole tier (micro-round 2, N3) — so the window is again only as long as
     * the two calls it spans.
     *
     * If the roll-back itself fails, the message names exactly which of the two files are on the
     * device and which are gone; see [rollBackFinalise].
     *
     * **`prefs.notifyModelInstalled()` fires inside the shared finalise, after its verification
     * and never before — the order is the contract** (Q7b fix round, I1; the announce moved into
     * [finalizeVerifiedPair] at 4.2 F5 so both arrival routes announce through one funnel). It
     * bumps `ModelInstallSignal.generation`, the key both chooser producers re-read the offer
     * gate on; without it the tier card stays hidden in the very composition that just imported
     * its assets. After the verification and never before, for the same reason `verifyDest`
     * announces last: a signal sent for files that get deleted a line later is worse than no
     * signal.
     *
     * Main-safe by construction (`Dispatchers.IO`): 358 MB of inflate on the main thread is an ANR,
     * not a jank. Cancellation is honoured between buffers and leaves no `.part` behind, so a retry
     * starts clean.
     *
     * @param source the `content://` URI from `ACTION_OPEN_DOCUMENT`. Read as a stream; never copied.
     * @param onProgress (uncompressed bytes written, total expected), throttled to ~4 MB ticks —
     *        the caller is Compose state and 5,500 updates would cost more than the copy.
     * @return [NpuAssetImport.ImportState.Installed], or `Refused` **naming the reason**. Every
     *         refusal is also one `WE-DIAG` line. It does not throw for a bad file: a refusal is a
     *         normal outcome of letting a user pick any file on the device.
     */
    suspend fun importNpuAssetPair(
        tierId: String,
        source: Uri,
        onProgress: (soFar: Long, total: Long) -> Unit = { _, _ -> },
    ): NpuAssetImport.ImportState = withContext(Dispatchers.IO) {
        // The ARGUMENT resolves the tier — never the npu constant. The card that launched the
        // picker passed its own id, and everything below is that one tier's names and numbers.
        val model = WhisperCatalog.byId(tierId)
        // THE FAMILY ANSWERS FIRST (4.2 F3). Which digests these bytes must hash to is a
        // property of the DEVICE's silicon family, not of the reference family the catalog
        // records — so the map below is built from the family's own artifact row. The import
        // affordance is capability-gated, so a null family here is a belt for a suspenders
        // failure: refused by name, never defaulted to the reference row, which would refuse
        // every correct non-reference zip with a "corrupted download" story.
        val family = (context.applicationContext as? WhisperEverywhereApp)?.npuSocFamily
            ?: return@withContext refuseImport(
                "this device's silicon family could not be resolved, so imported model files " +
                    "could not be verified against the family's published digests. Nothing " +
                    "was installed."
            )
        val artifact = NpuFleetCensus.artifactFor(family.id, tierId)
        val required = NpuAssetImport.requiredEntriesFor(model, artifact)
        if (model == null || artifact == null || required.isEmpty()) {
            return@withContext refuseImport(
                "this build's catalog has no importable model pair for that tier, so there is " +
                    "nothing to import into."
            )
        }

        val dir = modelsDir()
        val dirCanonical = runCatching { dir.canonicalPath }.getOrNull()
            ?: return@withContext refuseImport(
                "Could not resolve the app's models folder. Nothing was installed."
            )

        // A previous import that died between parking a file and moving the new one in leaves a
        // `.prev` behind. Settle that FIRST, so both the free-space budget and the
        // already-installed question below are answered about the directory as it really is.
        reconcileStagingDebris(dir, required.keys)

        // The transient, checked BEFORE a byte is read: 358 MB of .part files, doubled when a pair
        // is already installed because those stay on disk until the renames at the very end.
        val total = NpuAssetImport.pairBytes(required)
        val usable = runCatching { StatFs(dir.absolutePath).availableBytes }
            .getOrDefault(Long.MAX_VALUE)
        val needed = NpuAssetImport.requiredFreeBytes(total, isInstalled(model))
        NpuAssetImport.freeSpaceRefusal(usable, needed)?.let { return@withContext refuseImport(it) }

        val callerContext = currentCoroutineContext()
        val parts = LinkedHashMap<String, File>()
        try {
            val accepted = mutableSetOf<String>()
            var written = 0L
            var lastTick = 0L
            val input = context.contentResolver.openInputStream(source)
                ?: return@withContext refuseImport(
                    NpuAssetImport.unreadableRefusal("the picker returned nothing to read")
                )
            input.use { raw ->
                java.util.zip.ZipInputStream(
                    java.io.BufferedInputStream(raw, COPY_BUFFER_BYTES)
                ).use { zis ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    while (true) {
                        val entry = zis.nextEntry ?: break
                        if (entry.isDirectory) {
                            zis.closeEntry()
                            continue
                        }
                        // THE METADATA PEEK (4.2 F3). Our packs write metadata.json as the
                        // FIRST entry, so a wrong-family zip is refused HERE, from its own
                        // declaration, in one second — instead of "sha256 mismatch" after
                        // 776 MB has inflated and hashed to learn the same thing. Bounded to a
                        // metadata-sized entry (a ~GB entry wearing the name is never buffered)
                        // and IDENTITY-only: the streamed digest below stays the integrity
                        // gate. A zip WITHOUT metadata (the 4.0/4.1 published zips) never
                        // enters this block and proceeds straight to the digest gate,
                        // unchanged — legacy zips stay importable on the reference family.
                        val isPackMetadata = entry.name == NpuPackMetadata.ENTRY_NAME &&
                            entry.size in 0L..NpuPackMetadata.MAX_BYTES.toLong()
                        if (isPackMetadata) {
                            val metaBuffer = ByteArray(NpuPackMetadata.MAX_BYTES)
                            var metaLen = 0
                            while (metaLen < metaBuffer.size) {
                                val n = zis.read(metaBuffer, metaLen, metaBuffer.size - metaLen)
                                if (n <= 0) break
                                metaLen += n
                            }
                            val meta = try {
                                NpuPackMetadata.parse(String(metaBuffer, 0, metaLen, Charsets.UTF_8))
                            } catch (badMeta: IllegalStateException) {
                                return@withContext refuseImport(
                                    NpuAssetImport.unreadableRefusal(
                                        "metadata.json: ${badMeta.message}"
                                    )
                                )
                            }
                            NpuPackMetadata.crossCheckRefusal(meta, family, artifact, tierId)
                                ?.let { return@withContext refuseImport(it) }
                            android.util.Log.i(
                                NpuDiag.TAG,
                                "npu: import metadata cross-check ok family=${family.id} " +
                                    "tier=$tierId"
                            )
                            // Fall through: classifyEntry Ignores the metadata entry itself —
                            // it is not a model file and is never written to disk.
                        }
                        val verdict =
                            NpuAssetImport.classifyEntry(required, entry.name, entry.size, accepted)
                        if (verdict is NpuAssetImport.EntryVerdict.Ignore) {
                            android.util.Log.i(NpuDiag.TAG, "npu: import ${verdict.reason}")
                            zis.closeEntry()
                            continue
                        }
                        if (verdict is NpuAssetImport.EntryVerdict.Refuse) {
                            return@withContext refuseImport(verdict.reason)
                        }
                        val accept = verdict as NpuAssetImport.EntryVerdict.Accept

                        // Guard 2. The allow-list already makes a separator unrepresentable; this
                        // is the independent check that the resolved destination is really inside
                        // the models directory.
                        val dest = File(dir, accept.fileName)
                        if (NpuAssetImport.escapesTargetDir(dirCanonical, dest.canonicalPath)) {
                            return@withContext refuseImport(
                                "An entry in that zip resolved outside the app's models folder. " +
                                    "Nothing was installed."
                            )
                        }

                        val part = File(dir, accept.fileName + NpuAssetImport.PART_SUFFIX)
                        parts[accept.fileName] = part
                        // THE DIGEST RIDES THE COPY (4.1 L6). One MessageDigest per entry, fed
                        // the exact buffer slice the write just took — never a second pass:
                        // re-reading a 776 MB entry to hash it would double the import's I/O to
                        // learn what the first pass already knew, and it would verify what LANDED
                        // rather than what ARRIVED.
                        val digest = java.security.MessageDigest.getInstance("SHA-256")
                        var got = 0L
                        var overLength = false
                        java.io.FileOutputStream(part).use { out ->
                            while (true) {
                                callerContext.ensureActive()
                                // BOUNDED READ (fix round 1, I1). The entry's declared size is not
                                // evidence — `-1` is legal and a stated size can be a lie — so a
                                // copy that only checks the total afterwards writes until the
                                // filesystem is full and is then refused for being the wrong size.
                                // The read is capped at one byte past what this entry is allowed
                                // to be: reaching that byte proves it is too big, and nothing
                                // smaller proves it.
                                val room = accept.expectedBytes - got + 1L
                                val n = zis.read(buffer, 0, minOf(buffer.size.toLong(), room).toInt())
                                if (n <= 0) break
                                out.write(buffer, 0, n)
                                digest.update(buffer, 0, n)
                                got += n
                                written += n
                                if (got > accept.expectedBytes) {
                                    overLength = true
                                    break
                                }
                                if (written - lastTick >= PROGRESS_TICK_BYTES) {
                                    lastTick = written
                                    onProgress(if (written > total) total else written, total)
                                }
                            }
                        }
                        // THE REFUSAL COMES BEFORE closeEntry() (micro-round 2, N1), and the order
                        // is the whole of it. `ZipInputStream.closeEntry()` skips to the end of the
                        // current entry, which for a DEFLATED entry means inflating and discarding
                        // every remaining byte. Below that call the capped read has stopped the
                        // WRITE, so the disk is safe — and the 40 KB bomb is still fully
                        // decompressed on the IO thread, with progress frozen and Cancel dead,
                        // which is most of the attack the cap was added to stop. Nothing needs
                        // closing here: the enclosing `zis.use { }` closes the stream on the way
                        // out, entry and all.
                        if (overLength) {
                            // The `finally` deletes the oversize `.part`, so at most one byte more
                            // than the tier's own file was ever on disk.
                            return@withContext refuseImport(
                                NpuAssetImport.overLengthRefusal(
                                    accept.fileName, accept.expectedBytes
                                )
                            )
                        }
                        zis.closeEntry()
                        if (got != accept.expectedBytes) {
                            return@withContext refuseImport(
                                NpuAssetImport.wrongSizeRefusal(
                                    accept.fileName, got, accept.expectedBytes
                                )
                            )
                        }
                        // IMMEDIATELY AFTER THE SIZE CHECK, BEFORE the entry counts as arrived
                        // (4.1 L6): a hash failure leaves through exactly the door a size failure
                        // does — the .part dies in the finally, nothing has been parked yet, and
                        // a previously installed pair is untouched. Below the accepted line it
                        // would satisfy missingEntriesRefusal and reach the finalise.
                        NpuAssetImport.wrongDigestRefusal(
                            accept.fileName, accept.expectedSha256, hexOf(digest.digest())
                        )?.let { return@withContext refuseImport(it) }
                        accepted += accept.fileName
                    }
                }
            }

            NpuAssetImport.missingEntriesRefusal(required.keys, accepted)
                ?.let { return@withContext refuseImport(it) }

            // Both files exist at their exact published lengths and digests. The finalise is
            // THE ONE PARKING TRANSACTION both arrival routes share (4.2 F5) — park, rename,
            // verify, roll back, announce — extracted so the Play pack route lands through the
            // same machinery rather than a second install path free to drift.
            val outcome = finalizeVerifiedPair(model, parts)
            if (outcome is NpuAssetImport.ImportState.Refused) {
                return@withContext refuseImport(outcome.reason)
            }
            onProgress(total, total)
            android.util.Log.i(NpuDiag.TAG, NpuAssetImport.okLine(accepted.size, written))
            outcome
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled // the finally below still clears the .part files
        } catch (t: Throwable) {
            refuseImport(
                NpuAssetImport.unreadableRefusal("${t.javaClass.simpleName}: ${t.message}")
            )
        } finally {
            // Failure, refusal or cancellation — a retry starts clean. On success there is nothing
            // here, because every .part was renamed away.
            parts.values.forEach { if (it.exists()) it.delete() }
        }
    }

    /**
     * THE ONE PARKING TRANSACTION — the finalise both arrival routes share (4.2 F5; the
     * transaction itself is fix round 1, I2 / micro-round 2 unchanged, extracted verbatim from
     * the import so the Play pack route cannot grow a second, drifting copy of it).
     *
     * Precondition: every file in [staged] has ALREADY verified — exact bytes, streamed digest
     * — under its `.part` path. This function is only the landing: PHASE 1 parks each installed
     * destination under `.prev`, PHASE 2 renames each `.part` into place, the [isInstalled]
     * verification joins the same transaction, any failure leaves through [rollBackFinalise]
     * with the message that is true of the device afterwards, and only a committed transaction
     * drops the parked copies and announces.
     *
     * **`prefs.notifyModelInstalled()` lives HERE, after the verification and never before —
     * the order is the contract** (Q7b fix round, I1): it bumps the generation both chooser
     * producers re-read the offer gate on, and a signal sent for files a rollback then removes
     * is worse than no signal. With two arrival routes the one safe home is the transaction
     * itself; neither route announces on its own.
     *
     * The caller keeps its own route-shaped skin: the import route logs `npu: import refused`/
     * `npu: import ok`, the pack route's controller logs `pack: refused`/`pack: ok` — one
     * transaction, one refusal vocabulary, two narrations.
     *
     * @param staged verified `.part` files by their catalog delivery name — the tier's own
     *        names, whichever route staged them.
     */
    private fun finalizeVerifiedPair(
        model: WhisperModel,
        staged: LinkedHashMap<String, File>,
    ): NpuAssetImport.ImportState {
        val dir = modelsDir()
        val parked = LinkedHashMap<File, File>()   // destination -> the parked previous file
        val renamed = mutableListOf<File>()
        var finaliseFailure: String? = null

        for (name in staged.keys) {
            val dest = File(dir, name)
            if (!dest.exists()) continue
            val previous = File(dir, name + NpuAssetImport.PREVIOUS_SUFFIX)
            if (previous.exists()) previous.delete()
            if (dest.renameTo(previous)) {
                parked[dest] = previous
            } else {
                finaliseFailure = "The previously installed files could not be set aside"
                break
            }
        }
        if (finaliseFailure == null) {
            for ((name, part) in staged) {
                val dest = File(dir, name)
                if (part.renameTo(dest)) {
                    renamed += dest
                } else {
                    finaliseFailure = "The imported files could not be moved into place"
                    break
                }
            }
        }
        // The verification is part of the SAME transaction: a pair that does not read as
        // installed is not a successful install that happens to look odd, it is a failed one,
        // and leaving it on disk would make the next install's "is a pair already installed"
        // reasoning — and its free-space budget — answer about garbage. Since 4.2 F5 the gate
        // reads the family's census bytes, which is what lets a correct 7gen4 pair pass here.
        if (finaliseFailure == null && !isInstalled(model)) {
            finaliseFailure = "The imported files did not verify on disk"
        }
        if (finaliseFailure != null) {
            return NpuAssetImport.ImportState.Refused(
                rollBackFinalise(finaliseFailure, staged.keys, renamed, parked)
            )
        }
        // Committed. The parked copies are now genuinely superseded.
        parked.values.forEach { if (it.exists()) it.delete() }
        // LAST, and only now. See the KDoc: the chooser's producers key on this.
        prefs.notifyModelInstalled()
        return NpuAssetImport.ImportState.Installed
    }

    /**
     * Install a paired tier from a DELIVERED Play asset pack (4.2 F5) — the third arrival
     * route, verifying in the import's exact order and landing through the import's exact
     * transaction. `NpuPackController` calls this when Play reports COMPLETED, which means
     * DELIVERED and nothing more: everything below is what makes it installed.
     *
     * ```
     * <packName>/metadata.json exists?  (absent = the EMPTY default variant, refused by name)
     *   ->  strict parse  ->  crossCheckRefusal   (IDENTITY: the wrong pack dies here by name)
     *   ->  reconcile .prev debris  ->  free-space precheck
     *   ->  stream-copy each bin to <name>.part, sha256 riding the copy   (INTEGRITY)
     *   ->  size-verify  ->  digest-verify  ->  both present?
     *   ->  finalizeVerifiedPair   (the SHARED parking transaction; announces on success)
     * ```
     *
     * The free-space arithmetic is [NpuAssetImport.requiredFreeBytes] unchanged: the pack copy
     * is already on disk (Play's storage) and staging adds one more pair — plus the parked pair
     * when one is installed — which is exactly the `copies` model the import already budgets.
     *
     * No zip-slip guards here, and honestly so: the entry NAMES come from
     * [NpuAssetImport.requiredEntriesFor] — the catalog's own literals — never from anything
     * the network delivered, so a hostile name is unrepresentable on this route.
     *
     * A refusal leaves the delivered pack IN PLACE (the controller emits `pack: refused` and
     * keeps the pack for a costless retry); only a successful install lets the controller call
     * `removePack`, strictly after this returns Installed — a remove that runs early deletes
     * the only copy mid-verify.
     *
     * @param family the device's resolved census family — the controller read it from the app
     *        memo (the F2 chain) and refuses a null itself, so this parameter is non-null by
     *        construction.
     * @param packAssetsPath `AssetPackLocation.assetsPath()` — the delivered pack's assets
     *        root, containing a directory named after the PACK with exactly three files in it
     *        (F4's layout as F8 re-spelled it). Play strips the `#group_<g>` suffix on
     *        delivery, so `assets/npu_turbo#group_soc_8gen3/` arrives as `npu_turbo/`; the
     *        directory carries the pack's name because two modules may not ship the same entry
     *        path with different bytes, which is what an AAB build refuses by name.
     */
    suspend fun installFromPack(
        tierId: String,
        family: NpuSocFamily,
        packAssetsPath: String,
        onProgress: (soFar: Long, total: Long) -> Unit = { _, _ -> },
    ): NpuAssetImport.ImportState = withContext(Dispatchers.IO) {
        val model = WhisperCatalog.byId(tierId)
        val artifact = NpuFleetCensus.artifactFor(family.id, tierId)
        val required = NpuAssetImport.requiredEntriesFor(model, artifact)
        // (4.2 F8) The delivered pack's assets arrive in a directory named after the PACK, and
        // the map that decides which pack serves this tier is the one that names it — so the
        // fetch and the read cannot disagree about which pack this is. A tier with no pack row
        // is a catalog fact, not a delivery fact, so it joins the catalog guard below rather
        // than inventing a second empty-delivery site (the refusal has exactly one, and a pin
        // says so).
        val packDirName = NpuPackFetch.PACK_BY_TIER[tierId]
        if (model == null || artifact == null || required.isEmpty() || packDirName == null) {
            return@withContext NpuAssetImport.ImportState.Refused(
                "this build's catalog has no installable model pair for that tier, so there " +
                    "is nothing to install into."
            )
        }

        // THE EMPTY-DEFAULT SIGNATURE. Every real variant carries metadata.json (F4 writes it
        // and self-verifies it); a delivered pack WITHOUT one is the empty default variant —
        // Play's answer for a device in no census group — refused by name, with the import
        // fallback named as the path forward.
        val packModelDir = File(packAssetsPath, packDirName)
        val metaFile = File(packModelDir, NpuPackMetadata.ENTRY_NAME)
        if (!metaFile.isFile) {
            return@withContext NpuAssetImport.ImportState.Refused(
                NpuPackFetch.emptyDeliveryRefusal()
            )
        }
        // The peek's own bound, kept on this route too: a huge file wearing the metadata name
        // is never read into memory on the way to refusing it.
        if (metaFile.length() > NpuPackMetadata.MAX_BYTES.toLong()) {
            return@withContext NpuAssetImport.ImportState.Refused(
                NpuAssetImport.unreadableRefusal(
                    "metadata.json is ${metaFile.length()} B, larger than the " +
                        "${NpuPackMetadata.MAX_BYTES} B bound"
                )
            )
        }
        // IDENTITY before a byte of binary is touched — the import peek's exact order: a
        // wrong-group delivery (Play's one plausible wrongness) dies here by name, in
        // milliseconds, not as a sha256 mismatch after ~GB of hashing.
        val meta = try {
            NpuPackMetadata.parse(metaFile.readText(Charsets.UTF_8))
        } catch (badMeta: IllegalStateException) {
            return@withContext NpuAssetImport.ImportState.Refused(
                NpuAssetImport.unreadableRefusal(
                    "metadata.json: ${badMeta.message}"
                )
            )
        }
        NpuPackMetadata.crossCheckRefusal(meta, family, artifact, tierId)
            ?.let { return@withContext NpuAssetImport.ImportState.Refused(it) }

        val dir = modelsDir()
        // A previous install that died mid-finalise is settled FIRST, exactly as the import
        // settles it, so the budget and the already-installed answer below are about reality.
        reconcileStagingDebris(dir, required.keys)

        val total = NpuAssetImport.pairBytes(required)
        val usable = runCatching { StatFs(dir.absolutePath).availableBytes }
            .getOrDefault(Long.MAX_VALUE)
        val needed = NpuAssetImport.requiredFreeBytes(total, isInstalled(model))
        NpuAssetImport.freeSpaceRefusal(usable, needed)
            ?.let { return@withContext NpuAssetImport.ImportState.Refused(it) }

        val callerContext = currentCoroutineContext()
        val parts = LinkedHashMap<String, File>()
        try {
            val accepted = mutableSetOf<String>()
            var written = 0L
            var lastTick = 0L
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            for ((name, entry) in required) {
                val src = File(packModelDir, name)
                if (!src.isFile) continue   // missingEntriesRefusal names it below
                // The file's own length is its declared size; wrong dies before the copy.
                val srcLen = src.length()
                if (srcLen != entry.bytes) {
                    return@withContext NpuAssetImport.ImportState.Refused(
                        NpuAssetImport.wrongSizeRefusal(name, srcLen, entry.bytes)
                    )
                }
                val part = File(dir, name + NpuAssetImport.PART_SUFFIX)
                parts[name] = part
                // THE DIGEST RIDES THE COPY, same as the import: one MessageDigest per entry,
                // fed the exact buffer slice the write takes — never a second read of ~GB.
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                var got = 0L
                java.io.FileInputStream(src).use { input ->
                    java.io.FileOutputStream(part).use { out ->
                        while (true) {
                            callerContext.ensureActive()
                            val n = input.read(buffer)
                            if (n <= 0) break
                            out.write(buffer, 0, n)
                            digest.update(buffer, 0, n)
                            got += n
                            written += n
                            if (written - lastTick >= PROGRESS_TICK_BYTES) {
                                lastTick = written
                                onProgress(if (written > total) total else written, total)
                            }
                        }
                    }
                }
                if (got != entry.bytes) {
                    return@withContext NpuAssetImport.ImportState.Refused(
                        NpuAssetImport.wrongSizeRefusal(name, got, entry.bytes)
                    )
                }
                // Size verdict, then digest verdict, before the entry counts as arrived — the
                // import's order, the import's vocabulary.
                NpuAssetImport.wrongDigestRefusal(name, entry.sha256, hexOf(digest.digest()))
                    ?.let { return@withContext NpuAssetImport.ImportState.Refused(it) }
                accepted += name
            }
            NpuAssetImport.missingEntriesRefusal(required.keys, accepted)
                ?.let { return@withContext NpuAssetImport.ImportState.Refused(it) }

            // THE SHARED PARKING TRANSACTION — the same function the SAF import lands through,
            // which is the whole point: one transaction, two arrival routes.
            val outcome = finalizeVerifiedPair(model, parts)
            if (outcome is NpuAssetImport.ImportState.Installed) onProgress(total, total)
            outcome
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled // the finally below still clears the .part files
        } catch (t: Throwable) {
            NpuAssetImport.ImportState.Refused(
                NpuAssetImport.unreadableRefusal("${t.javaClass.simpleName}: ${t.message}")
            )
        } finally {
            // Failure, refusal or cancellation — a retry starts clean. On success there is
            // nothing here, because every .part was renamed away. The DELIVERED pack is never
            // touched on this path: it stays for the costless retry.
            parts.values.forEach { if (it.exists()) it.delete() }
        }
    }

    /**
     * Undo a failed finalise, and return the message that is TRUE of the device afterwards
     * (fix round 1, I2).
     *
     * **The order is the invariant, and it is the seventh application of that rule on this branch.**
     * Every file this import moved in is removed FIRST, and only then is each parked previous file
     * moved back — because both claim the same path. Restore-then-remove would put the old file
     * back and then delete it as "one of ours", which is the failure mode this function exists to
     * prevent, spelled with the same two statements in the other order.
     *
     * The message it returns is the point of the whole exercise. If everything went back, the user
     * is told their pair is unchanged, which is now true. If it did not, they are told **exactly
     * which files are on the device and which are gone** — because "Nothing was installed" is a
     * promise, and on this one path it was a false one.
     *
     * @param names THIS tier's files (4.1 L6). It used to read the npu constant's names, which
     *        for a turbo import would have reported the wrong tier's files as the device's state.
     */
    private fun rollBackFinalise(
        what: String,
        names: Set<String>,
        renamed: List<File>,
        parked: Map<File, File>,
    ): String {
        // Every step's RESULT counts, not just the restore loop's (micro-round 2, N2). `delete()`
        // returns a Boolean and the first draft dropped it, so on a first import — where `parked`
        // is empty and the restore loop therefore says nothing at all — a file that refused to be
        // deleted still produced "nothing new was installed" while the new file sat on disk.
        var undoneCleanly = true
        renamed.forEach { if (it.exists() && !it.delete()) undoneCleanly = false }
        parked.forEach { (dest, previous) ->
            if (!previous.exists()) return@forEach
            if (dest.exists() || !previous.renameTo(dest)) undoneCleanly = false
        }
        if (undoneCleanly) {
            // A first import has no previous pair, and telling that user their existing install is
            // unchanged is a sentence about something that never existed (N2).
            return if (parked.isEmpty()) NpuAssetImport.rolledBackFreshRefusal(what)
            else NpuAssetImport.rolledBackRefusal(what)
        }
        // Report on the tier's own two files, by name, as they actually are right now.
        val live = names.filter { File(modelsDir(), it).exists() }
        val gone = names.filterNot { File(modelsDir(), it).exists() }
        return NpuAssetImport.rollbackFailedRefusal(what, live, gone)
    }

    /**
     * Finish the transaction a dead process left half-done (fix round 1, I2; **semantic corrected
     * in micro-round 2, N3**). Runs BEFORE the free-space budget and the already-installed check,
     * so both see the true state of the directory.
     *
     * **The decision is made for the TIER, never per file.** The first draft decided each name on
     * its own — destination present, drop the parked copy; destination missing, restore it — and
     * that rule *synthesizes* a pair nothing ever wrote. Process death between the two phase-2
     * renames leaves `dest1 = new`, `prev1`, `prev2`, `part2`; per-file reconciliation then drops
     * `prev1` and restores `prev2`, producing **a new encoder beside an old decoder** with
     * `isInstalled` true and no record anywhere that the two came from different imports. Today
     * that mix is bounded — both halves are the same published asset version, and a cross-version
     * mix would be caught at load by C7's alias guard — but it is a state no code path intended,
     * and the "only window" claim in [importNpuAssetPair]'s KDoc was false while it was possible.
     *
     * So: either the dead transaction reached the end of phase 2 for **every** file, in which case
     * finishing it forward is correct and the pair on disk is internally consistent, or it did not,
     * in which case it is finished in the **roll-back** direction. Never half.
     *
     * **`.part` presence is what distinguishes the two, and it is load-bearing.** Phase 1 runs only
     * after every copy has completed, so at the moment a `.prev` first exists, every name has a
     * full `.part`. A `.part` that is now *gone* therefore proves phase 2 consumed it — which is
     * exactly "this destination is the new file". Without that test, an interrupted **phase 1**
     * (some names parked, the rest still holding their originals) would be misread as a completed
     * phase 2 and the untouched originals would be deleted as though this import had placed them.
     *
     * With no parked file at all there is no interrupted finalise, and any `.part` is debris from
     * an interrupted COPY — swept here, which is also what stops a process death mid-copy leaving
     * 358 MB behind forever.
     */
    private fun reconcileStagingDebris(dir: File, names: Set<String>) {
        val parked = names.filter { File(dir, it + NpuAssetImport.PREVIOUS_SUFFIX).exists() }.toSet()
        val movedIn = names.filter {
            !File(dir, it + NpuAssetImport.PART_SUFFIX).exists() && File(dir, it).exists()
        }.toSet()
        when (NpuAssetImport.reconcileDecision(names, parked, movedIn)) {
            NpuAssetImport.Reconcile.NOTHING -> Unit
            NpuAssetImport.Reconcile.COMPLETE_FORWARD ->
                parked.forEach { File(dir, it + NpuAssetImport.PREVIOUS_SUFFIX).delete() }
            NpuAssetImport.Reconcile.ROLL_BACK -> {
                // Remove what phase 2 placed FIRST, then put the parked files back — the same
                // order, for the same reason, as rollBackFinalise: both claim the same paths.
                movedIn.forEach { File(dir, it).delete() }
                parked.forEach { name ->
                    val previous = File(dir, name + NpuAssetImport.PREVIOUS_SUFFIX)
                    val dest = File(dir, name)
                    if (dest.exists()) previous.delete() else previous.renameTo(dest)
                }
            }
        }
        names.forEach { File(dir, it + NpuAssetImport.PART_SUFFIX).delete() }
    }

    /**
     * Settle every paired tier's staging debris — called from `Application.onCreate` (4.1 L6,
     * Q8 M1 + m4), not only from inside a later import of the same tier.
     *
     * Before this existed, a process death between the park and the rename left `isInstalled`
     * false with the primary parked under `.prev`: *the tier silently vanished from the chooser
     * and nothing on screen explained why*, until the owner happened to start another import of
     * exactly that tier. The orphaned `.part` half is the same story in storage terms — the
     * `StatFs` precheck counted reusable space as unavailable. One pass per tier through the SAME
     * [reconcileStagingDebris] the import runs closes both halves; a second rule here would be a
     * second chance to synthesize a mixed pair.
     *
     * Cost on a healthy launch: a handful of `File` stats (no parked files, nothing to do).
     * Renames or deletes happen only after a mid-finalise death, which is the launch that needs
     * them.
     */
    fun reconcileNpuStagingDebris() {
        val dir = modelsDir()
        NpuAssetImport.PAIRED_TIER_IDS.forEach { tierId ->
            // NAMES, not the verifying map (4.2 F3): sweeping parked `.prev`/`.part` debris
            // settles paths and needs no digests, so it must not depend on the family
            // resolution the map now requires — debris is swept even on a device whose family
            // answer is null or changed between launches.
            val names = NpuAssetImport.pairedFileNames(WhisperCatalog.byId(tierId))
            if (names.isNotEmpty()) reconcileStagingDebris(dir, names)
        }
    }

    /** Lowercase hex of a digest's raw bytes — the import's one rendering of a hash. */
    private fun hexOf(digestBytes: ByteArray): String =
        digestBytes.joinToString("") { "%02x".format(it) }

    /** One refusal shape: the WE-DIAG line and the state the card renders come from one place. */
    private fun refuseImport(reason: String): NpuAssetImport.ImportState.Refused {
        android.util.Log.w(NpuDiag.TAG, NpuAssetImport.refusedLine(reason))
        return NpuAssetImport.ImportState.Refused(reason)
    }

    class ModelDownloadException(message: String) : Exception(message)

    companion object {
        private const val POLL_INTERVAL_MS = 300L

        /** 64 KB, the spike's buffer. */
        private const val COPY_BUFFER_BYTES = 1 shl 16

        /** ~4 MB between progress callbacks: the callback writes Compose state. */
        private const val PROGRESS_TICK_BYTES = 4L shl 20
    }
}
