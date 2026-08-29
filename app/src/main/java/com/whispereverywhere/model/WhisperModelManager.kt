package com.whispereverywhere.model

import android.app.ActivityManager
import android.app.DownloadManager
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import androidx.core.net.toUri
import com.whispereverywhere.data.local.PreferencesManager
import com.whispereverywhere.npu.NpuAssetImport
import com.whispereverywhere.npu.NpuDiag
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
     * Installed = every file this tier needs is present at its own size, within ±5%.
     *
     * The primary is gated against [WhisperModel.primaryBytes], NOT `approxBytes`: for a paired
     * tier (npu) `approxBytes` is the sum of both files, and comparing the encoder alone against
     * the pair's total is 63% out — the tier would read as "not installed" forever no matter what
     * the owner imported. For every single-file tier the two are the same number, so this is the
     * predicate it has always been.
     */
    fun isInstalled(model: WhisperModel): Boolean {
        val f = fileFor(model)
        if (!f.exists() || !WhisperCatalog.sizeWithinTolerance(f.length(), model.primaryBytes)) return false
        val paired = model.pairedArtifact ?: return true
        val pf = File(modelsDir(), paired.fileName)
        return pf.exists() && WhisperCatalog.sizeWithinTolerance(pf.length(), paired.approxBytes)
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

    /** Delete a tier's file from disk. Returns true if a file was actually removed. */
    fun deleteModelFile(model: WhisperModel): Boolean {
        val f = fileFor(model)
        return f.exists() && f.delete()
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
     * May [model]'s file serve as the 80-bin mel donor and CPU fallback? See [cpuTierModelPath] for
     * why each clause is here; `retired` tiers are deliberately eligible — eco and base are ordinary
     * 80-bin whisper models, and an installed one is a perfectly good donor and a real fallback.
     */
    private fun isMelDonorEligible(model: WhisperModel): Boolean =
        model.id != "npu" && model.id != "ultra" && model.pairedArtifact == null

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

    /** Remove the installed file for [model], plus any external download leftovers / stale rows. */
    fun delete(model: WhisperModel) {
        val f = fileFor(model)
        if (f.exists()) f.delete()
        // Also clear the external download destination + stale DownloadManager rows, so a later
        // re-download can't stall at 0 bytes on a leftover-file collision.
        val ext = externalDownloadDest(model)
        if (ext.exists()) ext.delete()
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        removeStaleDownloads(dm, model)
    }

    /**
     * Install the npu tier from the asset-pair zip the owner picked with the document picker
     * (4.0, Q8). The **second** install path in this app, and the only one that is not a download.
     *
     * ```
     * free-space precheck  ->  inflate each allowed entry to <name>.part  ->  size-verify each
     *   ->  both present?  ->  rename BOTH into place  ->  isInstalled?  ->  notifyModelInstalled()
     * ```
     *
     * **Both-or-neither, and a re-import is non-destructive.** Every entry lands as `.part`
     * alongside whatever is already installed, so an existing pair survives a zip that turns out to
     * be truncated, wrong or not a zip at all — the two renames happen only after BOTH files exist
     * at their exact published lengths. On a second import over an existing pair the old files are
     * replaced one rename after the other with nothing in between; `isInstalled(npu)` is true
     * before, during and after, and the only way to observe a mixed pair is to arm the tier in the
     * microseconds between two `renameTo` calls on the same directory. If the second rename fails
     * anyway, both destinations are deleted rather than left mismatched: a tier that reads as
     * not-installed is recoverable by re-importing, and one that arms halfway is a native crash.
     *
     * **`prefs.notifyModelInstalled()` is the last thing it does, and the order is the contract**
     * (Q7b fix round, I1). It bumps `ModelInstallSignal.generation`, the key both chooser producers
     * re-read the offer gate on; without it the tier card stays hidden in the very composition that
     * just imported its assets. After the verification and never before, for the same reason
     * `verifyDest` announces last: a signal sent for files that get deleted a line later is worse
     * than no signal.
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
        source: Uri,
        onProgress: (soFar: Long, total: Long) -> Unit = { _, _ -> },
    ): NpuAssetImport.ImportState = withContext(Dispatchers.IO) {
        val model = WhisperCatalog.byId(NpuAssetImport.TIER_ID)
        val required = NpuAssetImport.requiredEntriesFor(model)
        if (model == null || required.isEmpty()) {
            return@withContext refuseImport(
                "this build's catalog has no importable npu tier, so there is nothing to import into."
            )
        }

        val dir = modelsDir()
        val dirCanonical = runCatching { dir.canonicalPath }.getOrNull()
            ?: return@withContext refuseImport(
                "Could not resolve the app's models folder. Nothing was installed."
            )

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
                        val verdict =
                            NpuAssetImport.classifyEntry(required, entry.name, entry.size)
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
                        var got = 0L
                        java.io.FileOutputStream(part).use { out ->
                            while (true) {
                                callerContext.ensureActive()
                                val n = zis.read(buffer)
                                if (n <= 0) break
                                out.write(buffer, 0, n)
                                got += n
                                written += n
                                if (written - lastTick >= PROGRESS_TICK_BYTES) {
                                    lastTick = written
                                    onProgress(if (written > total) total else written, total)
                                }
                            }
                        }
                        zis.closeEntry()
                        if (got != accept.expectedBytes) {
                            return@withContext refuseImport(
                                NpuAssetImport.wrongSizeRefusal(
                                    accept.fileName, got, accept.expectedBytes
                                )
                            )
                        }
                        accepted += accept.fileName
                    }
                }
            }

            NpuAssetImport.missingEntriesRefusal(required.keys, accepted)
                ?.let { return@withContext refuseImport(it) }

            // Both files exist at their exact published lengths. Move them into place back to back.
            val renamed = mutableListOf<File>()
            for ((name, part) in parts) {
                val dest = File(dir, name)
                if (dest.exists()) dest.delete()
                if (part.renameTo(dest)) {
                    renamed += dest
                } else {
                    renamed.forEach { it.delete() }
                    return@withContext refuseImport(
                        "Could not finalise the imported files. Nothing was installed."
                    )
                }
            }

            if (!isInstalled(model)) {
                return@withContext refuseImport(
                    "The imported files did not verify on disk. Nothing was installed."
                )
            }
            onProgress(total, total)
            android.util.Log.i(NpuDiag.TAG, NpuAssetImport.okLine(accepted.size, written))
            // LAST, and only now. See the KDoc: the chooser's producers key on this.
            prefs.notifyModelInstalled()
            NpuAssetImport.ImportState.Installed
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
