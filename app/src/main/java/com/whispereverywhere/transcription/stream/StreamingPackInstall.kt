package com.whispereverywhere.transcription.stream

import com.whispereverywhere.npu.NpuPackFetch
import java.io.File
import java.security.MessageDigest

/** `verify`'s answer. A mismatch names the FILE — a filename is not transcript content. */
sealed class PackVerdict {
    object Ok : PackVerdict()
    data class Missing(val name: String) : PackVerdict()
    data class SizeMismatch(val name: String, val actual: Long, val expected: Long) : PackVerdict()
    data class HashMismatch(val name: String) : PackVerdict()
}

class StreamingPackException(message: String) : Exception(message)

/**
 * What the previewer's gate reads and what the Settings row offers — ONE type, so no surface can
 * render a situation this machine never produces (the `NpuPackFetch.FetchState` discipline).
 *
 * The order the machine answers in is the order the user's disk answers in: an install under
 * `filesDir` wins over everything (it is what the recognizer opens), a DELIVERED pack is a local
 * install with no network at all, and the commit-pinned download is offered in exactly one place
 * — an install Google Play will not serve.
 */
sealed interface StreamingPackState {

    /** True for exactly one state: the previewer may load. A half install never opens (spec §6). */
    val isInstalled: Boolean get() = this is Installed

    /** Marker present and all four files at their exact byte counts under `filesDir`. */
    object Installed : StreamingPackState

    /** Play has delivered the pack: verify + install is local, costs no network, cannot fail slowly. */
    object PackDelivered : StreamingPackState

    /** Not delivered, but this install can ask Play for it — the ordinary Play-store path. */
    object PackFetchable : StreamingPackState

    /**
     * Play cannot serve this install (a debug build, a sideload, or a refusal Play already named
     * as the install's own fault), so the four files come from the commit-pinned Hugging Face
     * base. The ONE place the fallback is offered.
     */
    object Downloadable : StreamingPackState

    /**
     * Bytes are present under `filesDir` but the verdict is withdrawn — `markCorrupt` removed the
     * marker after a load failure, or a file went short. The row reads "Repair", and [via] is the
     * source the repair would use: the same triage as a first install, so a delivered pack
     * repairs without touching the network.
     */
    data class Repair(val via: StreamingPackState) : StreamingPackState
}

/**
 * The pure half of pack installation — `java.io.File` only, JVM-tested with a TemporaryFolder.
 * The Android half ([StreamingPackManager]) locates the delivered Play pack or downloads into a
 * staging dir, and hands EITHER here.
 *
 * `isInstalled` is a LENGTH read (marker + four exact byte counts), never a hash: it runs on the
 * session-start path. [verify] is the hash, and it runs once per install — on the pack's copy and
 * on the download's copy alike, because ONE verification for both sources is the whole point (the
 * 2026-09-10 amendment): a corrupt pack must not be installable on a route a corrupt download
 * could not survive. The marker is written LAST and the temp dir is renamed over the previous
 * install, so a kill at any instant leaves the old install intact or nothing — never a half pack
 * (the `TtsModelManager` precedent).
 *
 * The ONE thing the two sources do not share is who owns the source bytes: a download's staging
 * dir is ours to empty, so [install] moves out of it; Play's delivered directory is the only copy
 * of those bytes until `removePack`, so the pack route passes `moveSource = false`.
 */
object StreamingPackInstall {

    fun installDir(root: File, pack: StreamingPack): File = File(root, pack.dirName)
    fun tmpDir(root: File, pack: StreamingPack): File = File(root, pack.dirName + StreamingPackCatalog.TMP_SUFFIX)
    fun marker(dir: File): File = File(dir, StreamingPackCatalog.MARKER)

    fun isInstalled(dir: File, pack: StreamingPack): Boolean =
        marker(dir).isFile && pack.files.all { f -> File(dir, f.name).let { it.isFile && it.length() == f.bytes } }

    /**
     * The delivered pack's directory under Play's assets root, or null for a row with no pack
     * module. Play strips the `#group_<g>` suffix on delivery and this pack carries none, so the
     * directory is the pack's own name — the same read `WhisperModelManager.installFromPack`
     * performs for the NPU packs, and the 4.2 F8 entry-clash rule is why it is named that way.
     */
    fun packSourceDir(assetsRoot: File, pack: StreamingPack): File? =
        pack.packName?.let { File(assetsRoot, it) }

    /**
     * Whether [dir] can serve as an install SOURCE: the four files at their exact byte counts.
     * Deliberately not [isInstalled] — a delivered pack carries no `.installed` marker, because
     * the marker is the installer's own last write at the DESTINATION.
     */
    fun isPackComplete(dir: File?, pack: StreamingPack): Boolean =
        dir != null && pack.files.all { f -> File(dir, f.name).let { it.isFile && it.length() == f.bytes } }

    /**
     * The state machine, total over its four Booleans (spec §6; the amendment's {pack present,
     * pack absent, fallback present, corrupt} table).
     *
     * @param installed [isInstalled] under `filesDir` — the recognizer's own precondition.
     * @param installDirPresent any bytes at all under the install dir; with [installed] false
     *        that is a half or disowned install, which reads Repair rather than a fresh download.
     * @param packComplete [isPackComplete] of the DELIVERED pack directory.
     * @param playCanDeliver this install may ask Play at all: it came from the store AND Play has
     *        not already refused it by name ([playRefusedThisInstall]).
     */
    fun resolve(
        installed: Boolean,
        installDirPresent: Boolean,
        packComplete: Boolean,
        playCanDeliver: Boolean,
    ): StreamingPackState {
        if (installed) return StreamingPackState.Installed
        val source = when {
            // A pack already on disk needs no Play and no network, whatever the store thinks.
            packComplete -> StreamingPackState.PackDelivered
            playCanDeliver -> StreamingPackState.PackFetchable
            else -> StreamingPackState.Downloadable
        }
        return if (installDirPresent) StreamingPackState.Repair(source) else source
    }

    /**
     * Whether a Play failure reason means Play will NEVER serve this install — the previewer's
     * half of the discriminator the amendment calls `isPlayInstall()`-style, and it is REUSED
     * rather than forked: the family is [NpuPackFetch]'s own (API_NOT_AVAILABLE,
     * PLAY_STORE_NOT_FOUND, APP_NOT_OWNED, UNRECOGNIZED_INSTALLATION all render one sentence),
     * so the previewer and the NPU tiers cannot disagree about which failures are the install's
     * own fault. The NPU sentence is used here as a CLASSIFIER only; the previewer's own words
     * live in `StreamingPackCopy` (spec §9).
     *
     * A transient failure (network, storage, an internal Play error) is deliberately NOT in the
     * family: the fetch stays the offer and a retry costs nothing.
     */
    fun playRefusedThisInstall(reason: String): Boolean =
        reason == NpuPackFetch.failureReason(NpuPackFetch.ERROR_APP_NOT_OWNED)

    /**
     * The fetch shell's SINGLE-FLIGHT predicate: whether the fetch of a pack is still in Play's
     * hands or ours. Total over [NpuPackFetch.FetchState] on purpose — a state added to that
     * machine later must be classified here rather than fall through a wildcard into "idle", or a
     * second `fetch` would be issued on top of a live one.
     *
     * `NeedsConfirmation` counts as in flight: Play is waiting for the user's answer to Play's own
     * dialog, and a second fetch would ask it twice.
     */
    fun fetchInFlight(state: NpuPackFetch.FetchState): Boolean = when (state) {
        is NpuPackFetch.FetchState.Pending,
        is NpuPackFetch.FetchState.Downloading,
        is NpuPackFetch.FetchState.Transferring,
        is NpuPackFetch.FetchState.NeedsConfirmation,
        is NpuPackFetch.FetchState.Verifying,
        -> true
        is NpuPackFetch.FetchState.Idle,
        is NpuPackFetch.FetchState.Installed,
        is NpuPackFetch.FetchState.Failed,
        is NpuPackFetch.FetchState.Cancelled,
        -> false
    }

    /**
     * THE discriminator, and the only one: whether Google Play may be asked for this pack at all.
     * A debug build carries no asset packs — they exist only in an AAB install, which is exactly
     * why the amendment keeps the commit-pinned download alive for "the probe and dev sideloads"
     * — and a release install Play has refused by name ([playRefusedThisInstall]) will be refused
     * again. Everywhere else the model is fetched, never downloaded from a third party.
     */
    fun playCanDeliver(isDebugBuild: Boolean, playRefused: Boolean): Boolean =
        !isDebugBuild && !playRefused

    /**
     * The headroom an install of [pack] needs on the volume it writes: 1.1 × the pack's bytes —
     * the `TtsModelManager.kt:66-76` rule, spelled ONCE so both arrival routes gate at the same
     * number. The 10 % covers the marker, the filesystem's own overhead and the fact that a
     * `.tmp` directory and the previous install briefly coexist.
     */
    fun requiredFreeBytes(pack: StreamingPack): Long = (pack.totalBytes * 1.1).toLong()

    /**
     * Whether [availableBytes] on the volume about to be written admits an install of [pack].
     *
     * The PACK route needs this as much as the download does — more, since the amendment made it
     * the primary route on the shipping build: it copies 72,654,782 B into `filesDir`, and out of
     * space that copy fails partway with the destination half written and Play's own 73 MB still
     * on the device. A refusal costing nothing is the whole point of gating first (spec §6).
     */
    fun hasRoomFor(pack: StreamingPack, availableBytes: Long): Boolean =
        availableBytes >= requiredFreeBytes(pack)

    /** Every file's length must EQUAL its pin before anything is hashed (a cheap refusal); then sha256 per file. */
    fun verify(staged: File, pack: StreamingPack): PackVerdict {
        for (f in pack.files) {
            val file = File(staged, f.name)
            if (!file.isFile) return PackVerdict.Missing(f.name)
            if (file.length() != f.bytes) return PackVerdict.SizeMismatch(f.name, file.length(), f.bytes)
        }
        for (f in pack.files) {
            if (!sha256Hex(File(staged, f.name)).equals(f.sha256, ignoreCase = true)) return PackVerdict.HashMismatch(f.name)
        }
        return PackVerdict.Ok
    }

    /**
     * Moves (or, for a delivered pack, COPIES) the VERIFIED files from [source] into place
     * atomically; the marker is written LAST.
     *
     * A copy that fails partway — out of space is the case that matters, and it is the one the
     * caller's storage gate exists to make unreachable — takes the whole `.tmp` directory with
     * it and surfaces as a [StreamingPackException], the type this object's callers' KDoc names.
     * Both halves are load-bearing: without the sweep up to 73 MB of dead bytes stay under
     * `filesDir` in a directory `state()` does not read (the next [install] reclaims them, but
     * nothing else does), and without the wrap a raw `java.io.IOException` walks straight past a
     * `catch (e: StreamingPackException)`. The previous install is untouched either way — it is
     * only removed once the marker is written, which is the last thing inside the `try`.
     *
     * @param moveSource true for a download's staging dir, which is ours to empty. FALSE for
     *        Play's delivered pack: those bytes are the only copy until `removePack`, and the
     *        rename-then-copy-then-delete fallback below would delete Play's file mid-install
     *        the moment the rename crossed a filesystem — which it always does.
     */
    fun install(source: File, root: File, pack: StreamingPack, moveSource: Boolean = true) {
        val tmp = tmpDir(root, pack)
        if (tmp.exists()) tmp.deleteRecursively()
        tmp.mkdirs()
        try {
            for (f in pack.files) {
                val src = File(source, f.name)
                val dst = File(tmp, f.name)
                if (!moveSource) {
                    src.copyTo(dst, overwrite = true)
                } else if (!src.renameTo(dst)) {
                    src.copyTo(dst, overwrite = true)
                    src.delete()
                }
            }
            marker(tmp).writeText(StreamingPackCatalog.markerText(pack))
        } catch (t: Throwable) {
            tmp.deleteRecursively()
            throw if (t is StreamingPackException) {
                t
            } else {
                StreamingPackException(
                    "The preview model could not be written to storage: " +
                        "${t.javaClass.simpleName}: ${t.message}"
                )
            }
        }
        val final = installDir(root, pack)
        if (final.exists()) final.deleteRecursively()
        if (!tmp.renameTo(final)) {
            tmp.deleteRecursively()
            throw StreamingPackException("Could not finalize the preview model install")
        }
    }

    fun delete(root: File, pack: StreamingPack) {
        installDir(root, pack).deleteRecursively()
        tmpDir(root, pack).deleteRecursively()
    }

    /** Withdraws the verdict only: `isInstalled` answers false, the Settings row reads Repair, the bytes stay. */
    fun markCorrupt(root: File, pack: StreamingPack) {
        marker(installDir(root, pack)).delete()
    }

    fun sha256Hex(f: File): String {
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
}
