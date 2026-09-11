package com.whispereverywhere.transcription.stream

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import androidx.core.net.toUri
import com.whispereverywhere.BuildConfig
import com.whispereverywhere.npu.NpuPackFetch
import com.whispereverywhere.play.PlayPacks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Installs a streaming-previewer pack (spec §6) from whichever source this install has —
 * **pack-first** (the 2026-09-10 amendment).
 *
 * ```
 * state(pack)  ->  Installed        the recognizer opens filesDir/zf-stream/<dirName>/
 *              ->  PackDelivered    Play already has the 73 MB: installFromPack(), no network
 *              ->  PackFetchable    ask Play ([StreamingPackController.start], which lands here)
 *              ->  Downloadable     no Play here: download() from the commit-pinned HF base
 *              ->  Repair(via …)    bytes present, verdict withdrawn — repair from the same source
 * ```
 *
 * Both sources land through the SAME verification — exact byte counts, then sha256, then the
 * marker written LAST over an atomically renamed temp dir ([StreamingPackInstall.verify] and
 * [StreamingPackInstall.install], one function each, no per-source variant). The one difference
 * is ownership of the source bytes: a download's staging dir is ours to empty, Play's delivered
 * directory is not, so the pack route copies and then hands the pack back.
 *
 * This class is the ANDROID SHELL — `Context`, `DownloadManager`, `AssetPackManager`, `StatFs` —
 * and cannot be constructed by a JVM test. Every DECISION it makes is [StreamingPackInstall]'s
 * and is executed there (the house L6 split: `NpuPackController`/`NpuPackFetch`,
 * `NpuImportController`/`NpuAssetImport`).
 */
class StreamingPackManager(private val context: Context) {

    /**
     * Latched when Google Play has named THIS INSTALL as the reason it will not deliver — the
     * four sideload codes, classified by [StreamingPackInstall.playRefusedThisInstall], which is
     * `NpuPackFetch`'s own family and not a second opinion about it. In-memory on purpose: the
     * refusal is instant and recurs on the next attempt, so nothing is gained by persisting it
     * and a user who moves their install to Play must not stay stuck on the fallback.
     */
    @Volatile
    private var playRefused = false

    /** context.filesDir/zf-stream, created if missing. */
    fun root(): File {
        val dir = File(context.filesDir, StreamingPackCatalog.ROOT_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun installDir(pack: StreamingPack): File = StreamingPackInstall.installDir(root(), pack)
    fun isInstalled(pack: StreamingPack): Boolean = StreamingPackInstall.isInstalled(installDir(pack), pack)

    /**
     * WHICH previewer packs are installed, as their language codes — the previewer gate's one
     * language input since 4.4.1's acquisition amendment (owner rulings 2026-09-11: *"a user can
     * have multiple languages loaded onto their app. Since they're so small… maybe a person uses
     * three different languages"*).
     *
     * A SET because the store is one: installing Spanish must not remove English, and every
     * operation in this class is already keyed by the pack. It is also the ONE place the
     * catalogue decides which languages can be installed at all, which is what lets
     * `localPreviewArms` ask its question without a catalogue lookup of its own — a set built
     * from [StreamingPackCatalog.packs] can never contain a code the catalogue does not have.
     *
     * One [isInstalled] per catalogue row (one row today), i.e. the marker plus four exact byte
     * counts — the same read the gate already paid for English.
     */
    fun installedLanguages(): Set<String> =
        StreamingPackCatalog.packs.filterTo(mutableListOf()) { isInstalled(it) }
            .mapTo(mutableSetOf()) { it.language }
    fun installedDir(pack: StreamingPack): File? = if (isInstalled(pack)) installDir(pack) else null
    fun markCorrupt(pack: StreamingPack) = StreamingPackInstall.markCorrupt(root(), pack)

    /**
     * The DELIVERED pack's directory, or null when Play has not delivered it (or there is no Play
     * and no pack module). Read through the shared [PlayPacks] helper — the one spelling of
     * "where a delivered pack's assets are" the NPU tiers use too.
     */
    fun packSourceDir(pack: StreamingPack): File? {
        val packName = pack.packName ?: return null
        val assetsRoot = runCatching { PlayPacks.assetsPath(context, packName) }.getOrNull()
            ?: return null
        return StreamingPackInstall.packSourceDir(File(assetsRoot), pack)
    }

    /**
     * Whether Play may be asked at all. A debug build carries no asset packs — packs exist only
     * in an AAB install — so its install path IS the fallback, with no wasted refusal; and a
     * release build Play has already refused by name flips here for the rest of the process.
     */
    fun playCanDeliver(): Boolean =
        StreamingPackInstall.playCanDeliver(isDebugBuild = BuildConfig.DEBUG, playRefused = playRefused)

    /**
     * Record a Play fetch failure so the offer can move to the fallback if — and only if — the
     * refusal was about this install. Keyed by Play's own ERROR CODE, not by words: the caller is
     * a shell holding an `AssetPackException`, and a caller that handed over its own sentence
     * would silently never latch. `NpuPackFetch.failureReason` — the one table that turns a code
     * into words — is applied here, so the classifier still compares the NPU family's own text
     * and there is no second opinion about which failures are the install's own fault.
     *
     * Called by [StreamingPackController] on every Failed, from the listener and from a `fetch`
     * Task that failed before any `AssetPackState` existed (the sideload's own failure).
     */
    fun notePlayFailure(errorCode: Int) {
        if (StreamingPackInstall.playRefusedThisInstall(NpuPackFetch.failureReason(errorCode))) {
            playRefused = true
        }
    }

    /** What the Settings row offers and what the previewer's gate reads (spec §6). */
    fun state(pack: StreamingPack): StreamingPackState = StreamingPackInstall.resolve(
        installed = isInstalled(pack),
        installDirPresent = installDir(pack).exists(),
        packComplete = StreamingPackInstall.isPackComplete(packSourceDir(pack), pack),
        playCanDeliver = playCanDeliver(),
    )

    fun delete(pack: StreamingPack) {
        StreamingPackInstall.delete(root(), pack)
        stagingDir(pack).deleteRecursively()
        removeStaleDownloads(downloadManager(), pack)
    }

    /**
     * Install from the DELIVERED Play pack: verify, land, then hand the pack back. No network at
     * any point. Main-safe (Dispatchers.IO). Throws [StreamingPackException].
     *
     * `remove` runs STRICTLY AFTER the install returns — the remove-after-land rule the NPU fetch
     * flow owns, for the same reason: the delivered pack is the ONLY copy of those bytes until
     * the rename commits, and a failed verify leaves it in place so the retry costs nothing.
     *
     * FREE SPACE IS GATED FIRST, on the same 1.1 × rule the download uses
     * ([StreamingPackInstall.hasRoomFor]) — spec §6's "free-space gate first", and it belongs
     * here at least as much as on the download: this route copies 72,654,782 B into `filesDir`,
     * and on a full device that copy would fail partway with Play's own 73 MB still on the
     * device beside a half-written temp. One volume, not two: nothing external is staged here.
     */
    suspend fun installFromPack(pack: StreamingPack, onProgress: (soFar: Long, total: Long) -> Unit): Unit =
        withContext(Dispatchers.IO) {
            val source = packSourceDir(pack)
                ?: throw StreamingPackException("Google Play has not delivered the preview model to this device yet.")
            val free = runCatching { StatFs(root().absolutePath).availableBytes }.getOrDefault(Long.MAX_VALUE)
            if (!StreamingPackInstall.hasRoomFor(pack, free)) {
                throw StreamingPackException(
                    "Not enough free storage: the preview model needs about " +
                        "${StreamingPackInstall.requiredFreeBytes(pack) / 1_000_000} MB free to install."
                )
            }
            onProgress(0L, pack.totalBytes)
            when (val v = StreamingPackInstall.verify(source, pack)) {
                PackVerdict.Ok -> Unit
                is PackVerdict.Missing ->
                    throw StreamingPackException("The delivered preview model is missing a file: ${v.name}")
                is PackVerdict.SizeMismatch ->
                    throw StreamingPackException(
                        "The delivered preview model has the wrong size: ${v.name} (${v.actual} of ${v.expected} bytes)"
                    )
                is PackVerdict.HashMismatch ->
                    throw StreamingPackException("The delivered preview model failed integrity verification: ${v.name}")
            }
            // COPIES: those bytes are Play's until the remove below, and a rename across
            // filesystems would fall back to copy-then-delete and take Play's file with it.
            StreamingPackInstall.install(source, root(), pack, moveSource = false)
            onProgress(pack.totalBytes, pack.totalBytes)
            pack.packName?.let { PlayPacks.remove(context, it) }
        }

    /**
     * The NON-PLAY fallback: download + verify + atomically install, the `TtsModelManager` shape
     * (tts/TtsModelManager.kt:52-136) — free-space gate before the network, stale DownloadManager
     * rows removed, DownloadManager for the transport (it already follows HF `resolve/<sha>/`
     * redirects for the 190 MB whisper files), then the same pure verify + install. Four
     * sequential requests, one per pinned file, into an external staging dir; progress is the
     * cumulative byte count over the pack's total. Main-safe. Throws [StreamingPackException].
     */
    suspend fun download(pack: StreamingPack, onProgress: (soFar: Long, total: Long) -> Unit): Unit =
        withContext(Dispatchers.IO) {
            val dm = downloadManager()
            val staging = stagingDir(pack).apply { mkdirs() }
            // The same headroom rule as the pack route, from the same function: the download
            // needs it on BOTH volumes, because the staged copy and the install coexist.
            val required = StreamingPackInstall.requiredFreeBytes(pack)
            val extFree = runCatching { StatFs(staging.absolutePath).availableBytes }.getOrDefault(Long.MAX_VALUE)
            val intFree = runCatching { StatFs(root().absolutePath).availableBytes }.getOrDefault(Long.MAX_VALUE)
            if (!StreamingPackInstall.hasRoomFor(pack, extFree) || !StreamingPackInstall.hasRoomFor(pack, intFree)) {
                throw StreamingPackException(
                    "Not enough free storage: the preview model needs about ${(2 * required) / 1_000_000} MB free during install."
                )
            }
            removeStaleDownloads(dm, pack)
            var doneBytes = 0L
            for (f in pack.files) {
                val dest = File(staging, f.name)
                if (dest.exists()) dest.delete()
                fetchOne(dm, pack, f, dest) { soFar -> onProgress(doneBytes + soFar, pack.totalBytes) }
                doneBytes += f.bytes
                onProgress(doneBytes, pack.totalBytes)
            }
            when (val v = StreamingPackInstall.verify(staging, pack)) {
                PackVerdict.Ok -> Unit
                is PackVerdict.Missing -> fail(staging, "Preview model file missing after download: ${v.name}")
                is PackVerdict.SizeMismatch ->
                    fail(staging, "Preview model file size mismatch: ${v.name} (${v.actual} of ${v.expected} bytes)")
                is PackVerdict.HashMismatch -> fail(staging, "Preview model file failed integrity verification: ${v.name}")
            }
            StreamingPackInstall.install(staging, root(), pack)
            staging.deleteRecursively()
        }

    private fun fail(staging: File, message: String): Nothing {
        staging.deleteRecursively()
        throw StreamingPackException(message)
    }

    private suspend fun fetchOne(dm: DownloadManager, pack: StreamingPack, f: PackFile, dest: File, onSoFar: (Long) -> Unit) {
        val request = DownloadManager.Request(pack.urlOf(f).toUri())
            .setTitle("Live words preview model")
            .setDescription("Downloading ${f.name}")
            .setDestinationInExternalFilesDir(
                context, Environment.DIRECTORY_DOWNLOADS, "${StreamingPackCatalog.ROOT_DIR}/${pack.dirName}/${f.name}",
            )
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
        val id = dm.enqueue(request)
        var keepRow = false
        try {
            while (true) {
                val status = dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
                    if (!c.moveToFirst()) throw StreamingPackException("Preview model download entry disappeared")
                    onSoFar(c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)))
                    val s = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    when (s) {
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            val localUri = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
                            val src = localUri?.let { File(Uri.parse(it).path ?: "") }
                            if (src == null || !src.exists()) {
                                throw StreamingPackException("Cannot resolve downloaded file for ${f.name}")
                            }
                            if (src.absolutePath != dest.absolutePath) {
                                if (dest.exists()) dest.delete()
                                if (!src.renameTo(dest)) {
                                    src.copyTo(dest, overwrite = true)
                                    src.delete()
                                }
                            }
                        }
                        DownloadManager.STATUS_FAILED -> {
                            val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                            throw StreamingPackException("Preview model download failed (reason=$reason) on ${f.name}")
                        }
                        else -> Unit // PENDING / RUNNING / PAUSED
                    }
                    s
                }
                if (status == DownloadManager.STATUS_SUCCESSFUL) return
                delay(POLL_INTERVAL_MS)
            }
        } catch (ce: kotlinx.coroutines.CancellationException) {
            keepRow = true
            throw ce
        } finally {
            if (!keepRow) dm.remove(id)
        }
    }

    private fun stagingDir(pack: StreamingPack): File =
        File(File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), StreamingPackCatalog.ROOT_DIR), pack.dirName)

    private fun downloadManager(): DownloadManager =
        context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    private fun removeStaleDownloads(dm: DownloadManager, pack: StreamingPack) {
        val urls = pack.files.map { pack.urlOf(it) }.toSet()
        try {
            dm.query(DownloadManager.Query()).use { c ->
                val idIdx = c.getColumnIndex(DownloadManager.COLUMN_ID)
                val uriIdx = c.getColumnIndex(DownloadManager.COLUMN_URI)
                if (idIdx < 0 || uriIdx < 0) return
                while (c.moveToNext()) {
                    if (c.getString(uriIdx) in urls) dm.remove(c.getLong(idIdx))
                }
            }
        } catch (t: Throwable) {
            android.util.Log.w("WE-DIAG", "stream-pack: removeStaleDownloads failed", t)
        }
    }

    private companion object {
        const val POLL_INTERVAL_MS = 300L
    }
}
