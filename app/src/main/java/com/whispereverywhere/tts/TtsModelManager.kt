package com.whispereverywhere.tts

import android.app.DownloadManager
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import androidx.core.net.toUri
import com.whispereverywhere.BuildConfig
import com.whispereverywhere.npu.NpuPackFetch
import com.whispereverywhere.play.PlayPacks
import com.whispereverywhere.transcription.stream.StreamingPackCatalog
import com.whispereverywhere.transcription.stream.StreamingPackInstall
import com.whispereverywhere.transcription.stream.StreamingPackState
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
 * Which source an install of the voice would come from — the routing answer the Settings row's one
 * action needs, derived from [StreamingPackState] so the voice and the previewer read ONE state
 * machine (4.4.0, the 2026-09-10 amendment, Task 2b).
 */
enum class VoiceInstallRoute {
    /** Already installed: the row offers nothing to install. */
    None,

    /** Play has delivered the pack — verify + extract, no network at any point. */
    FromPack,

    /** Ask Play for the pack ([TtsPackController]). The ordinary Play-store path. */
    Fetch,

    /** The commit-… well, the RELEASE-pinned GitHub download: the one place it is still offered. */
    Download,
}

/**
 * Installs the Kokoro voice model (Track F) from whichever source this install has — **pack-first**
 * since the 2026-09-10 amendment (Task 2b). One pinned asset — the sherpa-onnx
 * `kokoro-multi-lang-v1_0` fp32 tarball (fp32 deliberately: int8 measured 1.5x SLOWER on this
 * device class, see the Track F plan bench table) — sha256-verified, then extracted atomically
 * (temp dir + rename) so a mid-extraction kill can never leave a half-installed voice.
 *
 * ```
 * state()  ->  Installed        TtsEngine opens filesDir/tts/kokoro-v1_0/
 *          ->  PackDelivered    Play already has the 350 MB: installFromPack(), no network
 *          ->  PackFetchable    ask Play (TtsPackController, which lands here)
 *          ->  Downloadable     no Play here: download() from the GitHub release
 *          ->  Repair(via …)    bytes present, verdict withdrawn — repair from the same source
 * ```
 *
 * **The pack route exists to close the 2026-09-08 incident structurally** (see
 * [KNOWN_GOOD_TAR_SHA256]): the archive's upstream home is a ROLLING release tag, so the bytes the
 * download route pulls are whatever was uploaded last. Riding `tts_kokoro` in the AAB makes a
 * voice update a deliberate release, and the download stays alive only where there is no Play to
 * ask — a debug build or a sideload, exactly the previewer's rule and, deliberately, the SAME
 * discriminator function ([StreamingPackInstall.playCanDeliver]).
 *
 * BOTH routes land through [verifyExtractInstall] unchanged: the same ±5 % size gate, the same
 * known-good hash set, the same extract + atomic swap, the same marker recording WHICH archive
 * landed. The one difference is who owns the source bytes — a download's tar is ours to delete,
 * Play's delivered copy is the only copy until `removePack` — which is the `ownsSource` flag and
 * nothing more (`StreamingPackInstall.install`'s `moveSource`, same amendment, same reason).
 *
 * Mirrors WhisperModelManager's hardening: completed-file reuse, free-space gate before
 * network, stale DownloadManager row cleanup, verify-then-install.
 */
class TtsModelManager(private val context: Context) {

    /**
     * Latched when Google Play has named THIS INSTALL as the reason it will not deliver — the
     * sideload family, classified by [StreamingPackInstall.playRefusedThisInstall], which is
     * `NpuPackFetch`'s own table and not a second opinion about it. In-memory on purpose: the
     * refusal is instant and recurs on the next attempt, so nothing is gained by persisting it and
     * a user who moves their install to Play must not stay stuck on the fallback.
     */
    @Volatile
    private var playRefused = false

    /**
     * ONE INSTALL AT A TIME, whichever route it arrived by (fix round 1, B1). Both routes end in
     * [verifyExtractInstall], which begins by deleting and recreating `filesDir/tts/<dir>.tmp`
     * ([extractTarBz2]'s first two statements) and ends by swapping it over [finalDir] — so two
     * of them running at once wipe each other's partial tree, and whichever renames second lands
     * a TRUNCATED model.onnx under its own `.installed` marker. [isInstalled] then answers true
     * and `TtsEngine` opens a corrupt 325 MB ONNX, with nothing but Delete + a 350 MB re-fetch to
     * escape it. This manager is process-scoped (`WhisperEverywhereApp.ttsModelManager`), so one
     * lock covers every caller: the Settings row, the fetch shell's own install after a delivery,
     * and onboarding's auto-setup.
     *
     * A plain monitor rather than a `Mutex` because the guarded region is BLOCKING code with no
     * suspension point in it (hash, extract, rename) and every caller is already on
     * `Dispatchers.IO`: a waiter parks an IO thread for the ~30 s of an extract it would
     * otherwise have raced, and the holder can never be suspended while holding it.
     */
    private val installLock = Any()

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
     * The DELIVERED pack's assets root, or null when Play has not delivered it (or there is no
     * Play and no pack module). Read through the shared [PlayPacks] helper — the one spelling of
     * "where a delivered pack's assets are" that the NPU tiers and the previewer use too.
     */
    fun packAssetsRoot(): File? =
        runCatching { PlayPacks.assetsPath(context, PACK_NAME) }.getOrNull()?.let { File(it) }

    /**
     * Whether Play may be asked at all. A debug build carries no asset packs — packs exist only in
     * an AAB install — so its install path IS the download, with no wasted refusal; and a release
     * build Play has already refused by name flips here for the rest of the process. The SAME
     * function the previewer's pack manager reads, deliberately: two spellings is how one feature
     * ends up pulling from a third party on a build where the other one fetches.
     */
    fun playCanDeliver(): Boolean =
        StreamingPackInstall.playCanDeliver(isDebugBuild = BuildConfig.DEBUG, playRefused = playRefused)

    /**
     * Record a Play fetch failure so the offer can move to the download if — and only if — the
     * refusal was about this install. Keyed by Play's own ERROR CODE, not by words: the caller is a
     * shell holding an `AssetPackException`, and a caller that handed over its own sentence would
     * silently never latch. `NpuPackFetch.failureReason` — the one table that turns a code into
     * words — is applied here, so the classifier still compares that family's own text and there is
     * no second opinion about which failures are the install's own fault.
     *
     * Called by [TtsPackController] on every Failed, from the listener and from a `fetch` Task that
     * failed before any `AssetPackState` existed (the sideload's own failure).
     */
    fun notePlayFailure(errorCode: Int) {
        if (StreamingPackInstall.playRefusedThisInstall(NpuPackFetch.failureReason(errorCode))) {
            playRefused = true
        }
    }

    /**
     * What the Settings voice row offers, through the previewer's own four-way machine — one state
     * machine for both packs, so no surface can render a situation neither produces.
     */
    fun state(): StreamingPackState = StreamingPackInstall.resolve(
        installed = isInstalled(),
        installDirPresent = finalDir().exists(),
        packComplete = isPackComplete(packAssetsRoot()),
        playCanDeliver = playCanDeliver(),
    )

    /**
     * Install from the DELIVERED Play pack: verify, extract, land, then hand the pack back. No
     * network at any point. [onExtracting] fires once when the verify+extract phase starts, the
     * same contract [download] has. Main-safe (Dispatchers.IO). Throws [TtsDownloadException].
     *
     * FREE SPACE IS GATED FIRST ([hasRoomToExtract]) — before a byte is hashed and before the
     * extractor opens a stream. Out of space, the extract fails partway with Play's own 350 MB
     * still on the device beside a half-written `.tmp`; a refusal that costs nothing is the point.
     * ONE volume, not two: nothing is staged externally on this route.
     *
     * `remove` runs STRICTLY AFTER [verifyExtractInstall] returns — the remove-after-land rule the
     * NPU fetch flow owns, for the same reason: the delivered pack is the ONLY copy of those bytes
     * until the rename commits, and a failed verify leaves it in place so the retry costs nothing.
     */
    suspend fun installFromPack(
        onProgress: (soFar: Long, total: Long) -> Unit,
        onExtracting: () -> Unit = {},
    ): Unit = withContext(Dispatchers.IO) {
        val assetsRoot = packAssetsRoot()
            ?: throw TtsDownloadException("Google Play has not delivered the read-aloud voice to this device yet.")
        val tar = packTarIn(assetsRoot)
        if (!tar.isFile) {
            throw TtsDownloadException("The delivered read-aloud voice is missing its archive.")
        }
        val intFree = runCatching { StatFs(ttsRoot().absolutePath).availableBytes }
            .getOrDefault(Long.MAX_VALUE)
        if (!hasRoomToExtract(intFree)) {
            throw TtsDownloadException(
                "Not enough free storage: unpacking the voice needs about " +
                    "${extractRequiredBytes() / 1_000_000} MB free."
            )
        }
        onProgress(0L, tar.length())
        onExtracting()
        // ownsSource = false: those bytes are Play's until the give-back below, so the installer's
        // own sweep must not touch them — least of all on a failed verify, which would turn a free
        // retry into a 350 MB re-fetch.
        verifyExtractInstall(tar, ownsSource = false)
        onProgress(tar.length(), tar.length())
        PlayPacks.remove(context, PACK_NAME)
    }

    /**
     * Download + verify + extract + atomically install — the NON-PLAY fallback since the
     * 2026-09-10 amendment (a debug build, a sideload, or an install Play refused by name).
     * [onProgress] gets (soFar, total) for the network phase; [onExtracting] fires once when the
     * ~30 s verify+extract phase starts. Main-safe (everything on Dispatchers.IO).
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

        // Free-space gate: transiently needs tar (external) + extracted tree ~1.4x tar (internal).
        // Both numbers come from the two companion functions the PACK route asks as well, so the
        // two arrival routes cannot disagree about what "enough space" means.
        val extRequired = stagedTarRequiredBytes()
        val intRequired = extractRequiredBytes()
        val extFree = runCatching {
            StatFs(tarDest.parentFile!!.apply { mkdirs() }.absolutePath).availableBytes
        }.getOrDefault(Long.MAX_VALUE)
        val intFree = runCatching { StatFs(ttsRoot().absolutePath).availableBytes }
            .getOrDefault(Long.MAX_VALUE)
        if (!hasRoomToStageTar(extFree) || !hasRoomToExtract(intFree)) {
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

    /**
     * sha256-gate the tar, extract to a temp dir, atomically swap into place, delete the tar.
     *
     * SERIALIZED on [installLock] (fix round 1, B1): this is the region both routes share and the
     * only one that writes `<dir>.tmp` and swaps it over the install, so it is the one region two
     * callers must never be inside at once. Every present and future call site is covered by
     * being inside this function rather than around its three call sites.
     *
     * UNCHANGED by the 2026-09-10 amendment in everything that decides whether an archive is
     * acceptable — the ±5 % size gate, the [KNOWN_GOOD_TAR_SHA256] set, the extract, the
     * marker-last atomic swap — because ONE verification serving both arrival routes is the point:
     * a corrupt pack must not be installable on a route a corrupt download could not survive.
     *
     * @param ownsSource true for a tar WE downloaded, which is ours to delete once it has been
     *        consumed (and to sweep on failure, so a bad 350 MB archive is not left behind). FALSE
     *        for Play's delivered pack: those bytes are the only copy until `removePack`, so
     *        deleting them here would race the give-back on success and, on a failed verify, would
     *        turn a costless retry into a 350 MB re-fetch. Exactly the reason
     *        `StreamingPackInstall.install` takes `moveSource`.
     */
    private fun verifyExtractInstall(tar: File, ownsSource: Boolean = true) {
        synchronized(installLock) {
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
                if (ownsSource) tar.delete()
            } catch (e: Exception) {
                if (ownsSource) tar.delete()
                File(ttsRoot(), "$DIR_NAME.tmp").deleteRecursively()
                throw e
            }
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

        // -------------------------------------------------------------- the pack route's decisions
        // (4.4.0, Task 2b. Pure, so `TtsModelManagerTest` executes every one of them: the shell
        // above is Context/AssetPackManager/StatFs-bound and no JVM test can construct it.)

        /**
         * The delivered archive's path under Play's assets root. Play strips a `#group_<g>` suffix
         * on delivery and this pack carries none (one untargeted variant, every device), so the
         * archive is at `<assetsPath>/tts_kokoro/kokoro-multi-lang-v1_0.tar.bz2` — the 4.2 F8
         * entry-clash rule is why the directory is named after the pack.
         */
        fun packTarIn(assetsRoot: File): File = File(File(assetsRoot, PACK_NAME), TAR_NAME)

        /**
         * Whether a delivered pack can serve as an install SOURCE: the archive is there, at a
         * length inside the same ±5 % band the download route has always used. Deliberately a
         * LENGTH read — this runs on the Settings row's render path; the sha256 is
         * [verifyExtractInstall]'s and runs once per install, on either route's copy.
         *
         * @param expectedBytes a parameter only so a JVM test can assert the true branch without
         *        materialising 350 MB; production always takes the default.
         */
        fun isPackComplete(assetsRoot: File?, expectedBytes: Long = TAR_BYTES): Boolean =
            assetsRoot != null &&
                packTarIn(assetsRoot).let { it.isFile && sizeWithinTolerance(it.length(), expectedBytes) }

        /** The headroom the DOWNLOAD route needs on the external volume it stages the tar on. */
        fun stagedTarRequiredBytes(): Long = (TAR_BYTES * 1.1).toLong()

        /**
         * The headroom the extract needs on `filesDir` — 1.4 × the archive, the rule this file has
         * always used, now spelled ONCE so the download route and the pack route gate at the same
         * number. (Truncation is real: 349,906,910 × 1.4 is 489,869,673.99999994.)
         */
        fun extractRequiredBytes(): Long = (TAR_BYTES * 1.4).toLong()

        fun hasRoomToStageTar(availableBytes: Long): Boolean =
            availableBytes >= stagedTarRequiredBytes()

        fun hasRoomToExtract(availableBytes: Long): Boolean =
            availableBytes >= extractRequiredBytes()

        /**
         * Which source the Settings row's ONE install action uses — total over
         * [StreamingPackState], so a state added to that machine must be routed here rather than
         * fall through a wildcard into the third-party download. A [StreamingPackState.Repair]
         * takes the route a first install would have taken, which is what lets a delivered pack
         * repair a half install without touching the network.
         */
        fun installRoute(state: StreamingPackState): VoiceInstallRoute = when (state) {
            StreamingPackState.Installed -> VoiceInstallRoute.None
            StreamingPackState.PackDelivered -> VoiceInstallRoute.FromPack
            StreamingPackState.PackFetchable -> VoiceInstallRoute.Fetch
            StreamingPackState.Downloadable -> VoiceInstallRoute.Download
            is StreamingPackState.Repair -> installRoute(state.via)
        }

        /**
         * The install row's title for each route — so the row NAMES THE SOURCE IT WILL ACTUALLY
         * USE. Before Task 2b every build read "Download the read-aloud voice"; on a Play install
         * that is now false (the archive is fetched from Play, not pulled from a third party) and
         * it was already stale (the archive is 350 MB, the row said 365).
         *
         * TODO(Task 6): the amendment gives Task 6 this row's final wording ("the voice row
         * likewise" — the previewer's row says "included with the app" on Play builds). These two
         * functions are where that edit lands; they are pure and route-keyed so it is one table.
         */
        fun installRowTitle(route: VoiceInstallRoute): String = when (route) {
            VoiceInstallRoute.None -> "Kokoro voice — installed"
            VoiceInstallRoute.FromPack -> "Install the read-aloud voice"
            VoiceInstallRoute.Fetch -> "Get the read-aloud voice"
            VoiceInstallRoute.Download -> "Download the read-aloud voice"
        }

        /** The install row's subtitle for each route; see [installRowTitle]. */
        fun installRowSubtitle(route: VoiceInstallRoute): String = when (route) {
            VoiceInstallRoute.None ->
                "Speaks highlighted or copied text aloud, fully on-device."
            VoiceInstallRoute.FromPack ->
                "Kokoro, already on this device: speaks highlighted text aloud, entirely " +
                    "on-device"
            VoiceInstallRoute.Fetch ->
                "Kokoro (${StreamingPackCatalog.sizeBadge(TAR_BYTES)} from Google Play): speaks " +
                    "highlighted text aloud, entirely on-device"
            VoiceInstallRoute.Download ->
                "Kokoro (${StreamingPackCatalog.sizeBadge(TAR_BYTES)} download): speaks " +
                    "highlighted text aloud, entirely on-device"
        }

        /**
         * The ONE voice sentence that promises anything, and it is promised exactly where
         * [StreamingPackInstall.playRefusedThisInstall] latches: by the time a row renders this,
         * `playCanDeliver()` has gone false and [state] has already moved to
         * [StreamingPackState.Downloadable], so the retry really does come from the GitHub
         * release. Held to that by
         * `TtsModelManagerTest.theVoicePromisesTheDirectDownloadExactlyWhereTheLatchFlips`.
         */
        private const val SIDELOAD_ANSWER: String =
            "Google Play can't deliver the read-aloud voice to this install — it wasn't " +
                "installed from Play. Retry: the voice is downloaded directly instead."

        /**
         * THE VOICE'S OWN REFUSAL COPY — the words the read-aloud row shows when Play will not
         * deliver the pack — keyed by Play's error CODE and total over Int,
         * `NpuPackFetch.failureReason`'s shape with this feature's affordances.
         *
         * Why it exists when that table already answers the same question: the table is the NPU
         * MODEL CHOOSER's copy, and `NpuPackFetch.FetchState.Failed`'s own contract is *"[reason]
         * is user-facing copy, rendered verbatim by the card"* — which is how both NPU surfaces
         * treat it. Six of its codes name a control this row does not have: the four sideload
         * codes render one sentence ending *"Use 'Import model pair…' below instead"*,
         * APP_UNAVAILABLE and PACK_UNAVAILABLE append the same phrase, and INSUFFICIENT_STORAGE
         * offers to fetch *"the model pair"* for a voice archive. Rendering any of them here would
         * point a user at the whisper model chooser's SAF importer, which cannot read this file.
         *
         * The seam is narrow on purpose: the CLASSIFIER is still that family's own
         * ([notePlayFailure]), so the two features cannot disagree about which failures are the
         * install's own fault. Only the WORDS are ours.
         *
         * TODO(Task 6): the amendment gives Task 6 the voice row's copy ("the voice row
         * likewise"). These sentences, [fetchLine]'s, and the four `TtsDownloadException`
         * messages in [installFromPack]/[download] want the same sweep — they live here until
         * then because a pure, JVM-executed home beats a `Failed` carrying the other feature's
         * copy, so the move is a relocation and not a re-decision.
         *
         * @param downloadBytes Play's own `totalBytesToDownload`, used by the storage refusal to
         *        name a real number — 0 when Play never said, in which case none is invented.
         */
        fun packRefusal(errorCode: Int, downloadBytes: Long = 0L): String = when (errorCode) {
            NpuPackFetch.ERROR_NO_ERROR ->
                "Google Play reported a failure without naming a reason. Retry the download."
            NpuPackFetch.ERROR_APP_UNAVAILABLE ->
                "Google Play says this app is currently unavailable, so it can't deliver the " +
                    "read-aloud voice right now. Try again later."
            NpuPackFetch.ERROR_PACK_UNAVAILABLE ->
                "This version of the app doesn't offer the read-aloud voice on Google Play. " +
                    "Update the app from Play, then retry."
            NpuPackFetch.ERROR_INVALID_REQUEST ->
                "Google Play rejected the download request as invalid. Restart the app and retry."
            NpuPackFetch.ERROR_DOWNLOAD_NOT_FOUND ->
                "Google Play lost track of this download. Retry it."
            NpuPackFetch.ERROR_NETWORK_ERROR ->
                "The download couldn't reach Google Play. Check your connection and retry."
            NpuPackFetch.ERROR_ACCESS_DENIED ->
                "Google Play refused this app access to the download. Check that the Play Store " +
                    "is signed in, then retry."
            NpuPackFetch.ERROR_INSUFFICIENT_STORAGE ->
                if (downloadBytes > 0L) {
                    "Not enough free storage to download the read-aloud voice: it needs about " +
                        "${StreamingPackCatalog.sizeBadge(downloadBytes)}. Free some space and retry."
                } else {
                    "Not enough free storage to download the read-aloud voice. Free some space " +
                        "and retry."
                }
            NpuPackFetch.ERROR_CONFIRMATION_NOT_REQUIRED ->
                "Google Play answered that no confirmation was needed. Retry the download."
            // The sideload family — the one path the amendment keeps the direct download alive
            // for, so it is the first refusal a release sideload reads.
            NpuPackFetch.ERROR_API_NOT_AVAILABLE,
            NpuPackFetch.ERROR_PLAY_STORE_NOT_FOUND,
            NpuPackFetch.ERROR_APP_NOT_OWNED,
            NpuPackFetch.ERROR_UNRECOGNIZED_INSTALLATION,
            -> SIDELOAD_ANSWER
            NpuPackFetch.ERROR_INTERNAL_ERROR ->
                "Google Play hit an internal error while delivering the read-aloud voice. Retry " +
                    "the download."
            else ->
                "Google Play reported error $errorCode while delivering the read-aloud voice."
        }

        /**
         * [packRefusal] for a whole `AssetPackState` reading, so the shell has one call to make.
         *
         * `NpuPackFetch.advance` produces `Failed` from three places: Play's FAILED status, which
         * carries an error code; the UNKNOWN status; and any status the library adds later. The
         * last two carry no code at all, so they are named by their NUMBER here rather than
         * mis-attributed to whatever `errorCode()` happened to return beside them (0 reads "a
         * failure without naming a reason", which would be a guess dressed as a fact).
         */
        fun deliveryRefusal(status: Int, errorCode: Int, downloadBytes: Long): String =
            if (status == NpuPackFetch.STATUS_FAILED) {
                packRefusal(errorCode, downloadBytes)
            } else {
                "Google Play stopped the read-aloud voice download at an unexpected status " +
                    "($status). Retry the download."
            }

        /**
         * What the voice row says while a fetch is in Play's hands or ours — total over
         * `NpuPackFetch.FetchState`, and null for exactly the three states at rest, where the row
         * goes back to its own offer (a stale "fetching…" under an installed voice is a lie the
         * user cannot dismiss).
         *
         * A `Failed` is shown VERBATIM: the shell has already re-told it in this feature's words
         * through [deliveryRefusal] / [packRefusal], so re-wording it here would be a second copy
         * of the copy.
         */
        fun fetchLine(state: NpuPackFetch.FetchState): String? = when (state) {
            is NpuPackFetch.FetchState.Idle,
            is NpuPackFetch.FetchState.Installed,
            is NpuPackFetch.FetchState.Cancelled,
            -> null
            is NpuPackFetch.FetchState.Pending -> "Asking Google Play for the voice…"
            is NpuPackFetch.FetchState.Downloading ->
                if (state.total > 0L) {
                    "Fetching the voice: ${state.soFar / 1_000_000} of " +
                        "${state.total / 1_000_000} MB"
                } else {
                    "Fetching the voice…"
                }
            is NpuPackFetch.FetchState.Transferring ->
                "Google Play is moving the voice into place…"
            is NpuPackFetch.FetchState.Verifying -> "Verifying and unpacking…"
            is NpuPackFetch.FetchState.NeedsConfirmation ->
                "Google Play needs your confirmation to download the voice — tap to answer."
            is NpuPackFetch.FetchState.Failed -> state.reason
        }

        /**
         * Whether a TAP on the row showing [fetchLine] does anything — the guard that keeps the
         * in-flight row from starting a SECOND install (fix round 1, B1).
         *
         * The row renders that line for every state a fetch passes through, in-flight ones
         * included, and `SettingsItem` makes itself clickable whenever it is given an `onClick`.
         * A tap during the ~30 s extract therefore used to re-enter the row's one action, whose
         * route at that instant is still [VoiceInstallRoute.FromPack] — a second
         * [installFromPack] into the same temp dir. The retry the branch was written for is the
         * TERMINAL one: a [NpuPackFetch.FetchState.Failed] the user can act on. The one in-flight
         * state that stays tappable is [NpuPackFetch.FetchState.NeedsConfirmation], where the tap
         * re-shows PLAY'S OWN dialog and starts no install of ours.
         *
         * Total over the machine, with the three at-rest states spelled out even though they
         * render no line at all: a state added to that machine must be answered here rather than
         * fall through a wildcard into "tappable, mid-extract".
         */
        fun fetchLineTappable(state: NpuPackFetch.FetchState): Boolean = when (state) {
            // The terminal the retry exists for, and Play's own dialog — neither is our install.
            is NpuPackFetch.FetchState.Failed,
            is NpuPackFetch.FetchState.NeedsConfirmation,
            -> true
            // Work in flight: Play's or ours. A tap here can only duplicate it.
            is NpuPackFetch.FetchState.Pending,
            is NpuPackFetch.FetchState.Downloading,
            is NpuPackFetch.FetchState.Transferring,
            is NpuPackFetch.FetchState.Verifying,
            -> false
            // At rest, where fetchLine is null and the row shows its own offer instead.
            is NpuPackFetch.FetchState.Idle,
            is NpuPackFetch.FetchState.Installed,
            is NpuPackFetch.FetchState.Cancelled,
            -> false
        }

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
