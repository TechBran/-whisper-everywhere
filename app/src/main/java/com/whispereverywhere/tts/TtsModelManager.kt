package com.whispereverywhere.tts

import android.app.DownloadManager
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * Downloads and installs the Kokoro voice model (Track F). One pinned asset — the sherpa-onnx
 * `kokoro-multi-lang-v1_1` fp32 tarball (fp32 deliberately: int8 measured 1.5x SLOWER on this
 * device class, see the Track F plan bench table) — sha256-verified, then extracted atomically
 * (temp dir + rename) so a mid-extraction kill can never leave a half-installed voice.
 *
 * Mirrors WhisperModelManager's hardening: completed-file reuse, free-space gate before
 * network, stale DownloadManager row cleanup, verify-then-install.
 */
class TtsModelManager(private val context: Context) {

    /** context.filesDir/tts, created if missing. */
    fun ttsRoot(): File {
        val dir = File(context.filesDir, "tts")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun finalDir(): File = File(ttsRoot(), DIR_NAME)

    /** Marker written as the last step of install; its presence defines "installed". */
    private fun markerFile(): File = File(finalDir(), ".installed")

    fun isInstalled(): Boolean = markerFile().exists() && File(finalDir(), MODEL_FILE).exists()

    fun installedDir(): File? = if (isInstalled()) finalDir() else null

    /**
     * Download + verify + extract + atomically install. [onProgress] gets (soFar, total) for
     * the network phase; [onExtracting] fires once when the ~30 s verify+extract phase starts.
     * Main-safe (everything on Dispatchers.IO).
     */
    suspend fun download(
        onProgress: (soFar: Long, total: Long) -> Unit,
        onExtracting: () -> Unit = {},
    ): Unit = withContext(Dispatchers.IO) {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val tarDest = externalTarDest()

        // Reuse a fully-downloaded tar from a prior killed attempt; sha256 below rejects bad ones.
        if (tarDest.exists() && sizeWithinTolerance(tarDest.length())) {
            onProgress(tarDest.length(), tarDest.length())
            onExtracting()
            verifyExtractInstall(tarDest)
            return@withContext
        }

        // Free-space gate: transiently needs tar (external) + extracted tree ~1.2x tar (internal).
        val extRequired = (TAR_BYTES * 1.1).toLong()
        val intRequired = (TAR_BYTES * 1.4).toLong()
        val extFree = runCatching {
            StatFs(tarDest.parentFile!!.apply { mkdirs() }.absolutePath).availableBytes
        }.getOrDefault(Long.MAX_VALUE)
        val intFree = runCatching { StatFs(ttsRoot().absolutePath).availableBytes }
            .getOrDefault(Long.MAX_VALUE)
        if (extFree < extRequired || intFree < intRequired) {
            throw TtsDownloadException(
                "Not enough free storage: the voice needs about " +
                    "${(extRequired + intRequired) / 1_000_000} MB free during install."
            )
        }

        removeStaleDownloads(dm)
        if (tarDest.exists()) tarDest.delete()

        val request = DownloadManager.Request(TAR_URL.toUri())
            .setTitle("Read-aloud voice")
            .setDescription("Downloading the on-device voice")
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "tts/$TAR_NAME")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)

        val id = dm.enqueue(request)
        // Coroutine cancellation (user leaves Settings) must NOT abort the network transfer:
        // DownloadManager keeps going, and the next download() call reuses the completed file
        // (review fix I4). The row is removed only on completion or terminal failure.
        var keepRow = false
        try {
            var done = false
            while (!done) {
                dm.query(DownloadManager.Query().setFilterById(id)).use { c: Cursor ->
                    if (c.moveToFirst()) {
                        val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                        val soFar = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                        val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                        onProgress(soFar, if (total > 0) total else TAR_BYTES)
                        when (status) {
                            DownloadManager.STATUS_SUCCESSFUL -> {
                                val localUri = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
                                val src = localUri?.let { File(Uri.parse(it).path ?: "") }
                                if (src == null || !src.exists()) {
                                    throw TtsDownloadException("Cannot resolve downloaded file: $localUri")
                                }
                                if (src.absolutePath != tarDest.absolutePath) {
                                    if (tarDest.exists()) tarDest.delete()
                                    if (!src.renameTo(tarDest)) { src.copyTo(tarDest, overwrite = true); src.delete() }
                                }
                                onExtracting()
                                verifyExtractInstall(tarDest)
                                done = true
                            }
                            DownloadManager.STATUS_FAILED -> {
                                val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                                throw TtsDownloadException("Voice download failed (reason=$reason)")
                            }
                            else -> Unit // PENDING / RUNNING / PAUSED
                        }
                    } else {
                        throw TtsDownloadException("Voice download entry disappeared")
                    }
                }
                if (!done) delay(POLL_INTERVAL_MS)
            }
        } catch (ce: kotlinx.coroutines.CancellationException) {
            keepRow = true
            throw ce
        } finally {
            if (!keepRow) dm.remove(id)
        }
    }

    /** sha256-gate the tar, extract to a temp dir, atomically swap into place, delete the tar. */
    private fun verifyExtractInstall(tar: File) {
        try {
            if (!sizeWithinTolerance(tar.length())) {
                throw TtsDownloadException("Voice archive size mismatch (${tar.length()} bytes)")
            }
            val actual = sha256HexFile(tar)
            if (KNOWN_GOOD_TAR_SHA256.none { it.equals(actual, ignoreCase = true) }) {
                throw TtsDownloadException("Voice archive failed integrity verification")
            }
            val tmp = File(ttsRoot(), "$DIR_NAME.tmp")
            extractTarBz2(tar, tmp, stripLeadingComponent = true)
            val marker = File(tmp, ".installed")
            marker.writeText(actual.lowercase())
            val final = finalDir()
            if (final.exists()) final.deleteRecursively()
            if (!tmp.renameTo(final)) {
                tmp.deleteRecursively()
                throw TtsDownloadException("Could not finalize voice install")
            }
            tar.delete()
        } catch (e: Exception) {
            tar.delete()
            File(ttsRoot(), "$DIR_NAME.tmp").deleteRecursively()
            throw e
        }
    }

    fun delete() {
        finalDir().deleteRecursively()
        File(ttsRoot(), "$DIR_NAME.tmp").deleteRecursively()
        externalTarDest().delete()
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        removeStaleDownloads(dm)
    }

    private fun externalTarDest(): File =
        File(File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "tts"), TAR_NAME)

    private fun removeStaleDownloads(dm: DownloadManager) {
        try {
            dm.query(DownloadManager.Query()).use { c ->
                val idIdx = c.getColumnIndex(DownloadManager.COLUMN_ID)
                val uriIdx = c.getColumnIndex(DownloadManager.COLUMN_URI)
                if (idIdx < 0 || uriIdx < 0) return
                while (c.moveToNext()) {
                    if (c.getString(uriIdx) == TAR_URL) dm.remove(c.getLong(idIdx))
                }
            }
        } catch (t: Throwable) {
            android.util.Log.w("WE-TTS", "removeStaleDownloads failed", t)
        }
    }

    class TtsDownloadException(message: String) : Exception(message)

    companion object {
        // v1_0, deliberately NOT v1_1: v1_1 is the Chinese-focused v1.1-zh export with only
        // THREE English voices (af_maple/af_sol/bf_vale); v1_0 carries the full classic Kokoro
        // roster (af_heart, af_bella, am_michael, …53 voices) matching the user's server. The
        // TtsVoices catalog indexes THIS model's voices.bin order.
        const val DIR_NAME = "kokoro-v1_0"
        const val MODEL_FILE = "model.onnx"

        /**
         * The archive's name — public since 4.4.0's amendment (Task 2b), because it is now also
         * the name of the ONE file the `tts_kokoro` Play asset pack carries: the pack's payload
         * is this archive placed AS-IS, so the build script's placement row, the bundle gate and
         * [packTarIn]'s read all key on this one string. `TtsPackLayoutTest` holds them equal.
         */
        const val TAR_NAME = "kokoro-multi-lang-v1_0.tar.bz2"

        /**
         * The Play asset pack that carries [TAR_NAME], and therefore also the name of the
         * directory it arrives in: Play strips a `#group_<g>` suffix on delivery and this pack has
         * none (one untargeted variant, every device), so the delivered path is
         * `<AssetPackLocation.assetsPath()>/<PACK_NAME>/<TAR_NAME>` — the same read the NPU packs
         * and `preview_en` perform, and 4.2 F8's entry-clash rule is why the directory carries the
         * pack's name in the first place.
         */
        const val PACK_NAME = "tts_kokoro"

        private const val TAR_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/$TAR_NAME"
        /**
         * PRODUCTION INCIDENT, 2026-09-08 to 2026-09-10. The GitHub release tag `tts-models` is a
         * ROLLING tag: k2-fsa re-uploaded this archive on 2026-09-08 04:35 GMT (Last-Modified;
         * the HF mirror csukuangfj/kokoro-multi-lang-v1_0 commit f7b96bb6 "add models" the same
         * day). The new archive is 349,906,910 B — inside the +-5 % size band, so [sizeWithinTolerance]
         * passed — and its sha256 is not the one pinned on 2026-07-18, so EVERY fresh voice install
         * on EVERY build failed "integrity verification" for two days, production included. Verified
         * on 2026-09-10 before re-pinning: the new voices.bin is the old 53 slots byte-for-byte plus
         * ONE appended slot (`em_santa`, speaker id 53; the archive's own README: "Existing speaker
         * IDs 0 through 52 remain unchanged"), so [TtsVoices]' order is intact; model.onnx was
         * re-exported with n_speakers = 54 (325,630,829 -> 325,560,556 B).
         *
         * The gate now accepts a KNOWN-GOOD SET: the 2026-07-18 archive (a CDN may still serve it,
         * and it is a valid, compatible install) and the 2026-09-08 one. A third re-upload fails
         * again, loudly, by design — silent acceptance of an unknown archive is worse than a failed
         * download. The installed marker records WHICH archive was extracted.
         */
        val KNOWN_GOOD_TAR_SHA256: List<String> = listOf(
            "c5f7e2d2caf082bc1d20fb70334a61d99d20b484500aad32e7cf84c128ea3298", // 2026-09-08 upload
            "c133d26353d776da730870dac7da07dbfc9a5e3bc80cc5e8e83ab6e823be7046", // 2026-07-18 pin
        )
        /** The current archive: 349,906,910 B. The 2026-07-18 one (349,418,188 B) is inside the band. */
        const val TAR_BYTES = 349_906_910L
        private const val POLL_INTERVAL_MS = 300L

        /** ±5% band, same policy as the whisper downloads. */
        fun sizeWithinTolerance(actual: Long, expected: Long = TAR_BYTES): Boolean {
            val lo = (expected * 0.95).toLong()
            val hi = (expected * 1.05).toLong()
            return actual in lo..hi
        }

        fun sha256HexFile(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { ins ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        /**
         * Extract [tar] (tar.bz2) into [destDir] (created fresh). With [stripLeadingComponent]
         * the archive's single top-level directory is dropped, so files land directly in
         * [destDir]. Rejects path-traversal entries (zip-slip) and non-file/dir entry types.
         * Pure JVM — unit-tested.
         */
        fun extractTarBz2(tar: File, destDir: File, stripLeadingComponent: Boolean) {
            if (destDir.exists()) destDir.deleteRecursively()
            destDir.mkdirs()
            val destCanonical = destDir.canonicalPath + File.separator
            TarArchiveInputStream(
                BZip2CompressorInputStream(BufferedInputStream(FileInputStream(tar)))
            ).use { tin ->
                while (true) {
                    val entry = tin.nextEntry ?: break
                    val relPath = if (stripLeadingComponent) {
                        entry.name.substringAfter('/', missingDelimiterValue = "")
                    } else entry.name
                    if (relPath.isEmpty()) continue
                    val out = File(destDir, relPath)
                    if (!out.canonicalPath.startsWith(destCanonical)) {
                        throw TtsDownloadException("Archive entry escapes destination: ${entry.name}")
                    }
                    when {
                        entry.isDirectory -> out.mkdirs()
                        entry.isFile -> {
                            out.parentFile?.mkdirs()
                            out.outputStream().use { tin.copyTo(it) }
                        }
                        else -> Unit // symlinks/devices: skip — the model archive has none
                    }
                }
            }
        }
    }
}
