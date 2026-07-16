package com.whispereverywhere.model

import android.app.ActivityManager
import android.app.DownloadManager
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import androidx.core.net.toUri
import com.whispereverywhere.data.local.PreferencesManager
import com.whispereverywhere.transcription.ModelPathProvider
import kotlinx.coroutines.delay
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
     * as it progresses. On completion, size-gate + sha256 verify; delete + throw on mismatch.
     * Suspends until the download terminates. (Instrumented test only — needs DownloadManager.)
     */
    suspend fun download(model: WhisperModel, onProgress: (soFar: Long, total: Long) -> Unit) {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val dest = fileFor(model)
        if (dest.exists()) dest.delete()

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
        try {
            var done = false
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

                        when (status) {
                            DownloadManager.STATUS_SUCCESSFUL -> {
                                val localUri = c.getString(
                                    c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI),
                                )
                                moveToModelsDir(localUri, dest)
                                done = true
                            }
                            DownloadManager.STATUS_FAILED -> {
                                val reason = c.getInt(
                                    c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON),
                                )
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

        // Verify on disk: size gate THEN sha256. Delete + fail on mismatch.
        val actualLen = dest.length()
        val bytes = dest.readBytes()
        val ok = WhisperCatalog.verify(actualLen, model.approxBytes, bytes, model.sha256)
        if (!ok) {
            dest.delete()
            throw ModelDownloadException("Verification failed for ${model.fileName}")
        }
    }

    private fun moveToModelsDir(localUri: String?, dest: File) {
        val src = localUri?.let { File(Uri.parse(it).path ?: return@let null) }
        if (src != null && src.exists()) {
            if (dest.exists()) dest.delete()
            if (!src.renameTo(dest)) {
                src.copyTo(dest, overwrite = true)
                src.delete()
            }
        }
    }

    /** Remove the installed file for [model] (no-op if absent). */
    fun delete(model: WhisperModel) {
        val f = fileFor(model)
        if (f.exists()) f.delete()
    }

    class ModelDownloadException(message: String) : Exception(message)

    companion object {
        private const val POLL_INTERVAL_MS = 300L
    }
}
