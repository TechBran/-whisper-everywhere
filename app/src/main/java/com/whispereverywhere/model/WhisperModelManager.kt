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
import com.whispereverywhere.transcription.ModelPathProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

    /** Installed = file present on disk with a size within tolerance of approxBytes. */
    fun isInstalled(model: WhisperModel): Boolean {
        val f = fileFor(model)
        return f.exists() && WhisperCatalog.sizeWithinTolerance(f.length(), model.approxBytes)
    }

    /** The selected model, if it is actually installed on disk. */
    fun installedModel(): WhisperModel? {
        val model = WhisperCatalog.byId(prefs.selectedModelId) ?: return null
        return if (isInstalled(model)) model else null
    }

    /** Absolute path to the installed selected model file, or null. */
    override fun installedModelPath(): String? {
        val model = installedModel() ?: return null
        return fileFor(model).absolutePath
    }

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
     */
    suspend fun download(
        model: WhisperModel,
        onProgress: (soFar: Long, total: Long) -> Unit,
        onVerifying: () -> Unit = {},
    ): Unit = withContext(Dispatchers.IO) {
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

    class ModelDownloadException(message: String) : Exception(message)

    companion object {
        private const val POLL_INTERVAL_MS = 300L
    }
}
