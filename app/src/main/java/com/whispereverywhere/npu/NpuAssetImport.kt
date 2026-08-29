package com.whispereverywhere.npu

import com.whispereverywhere.model.WhisperCatalog
import com.whispereverywhere.model.WhisperModel
import java.io.File

/**
 * The npu tier's asset-pair import, as **pure decisions** (4.0, Q8).
 *
 * The tier is the only one in the catalog that is not installed by downloading: it is two QAIRT
 * context binaries, published together as one zip, and the owner brings them across with the system
 * document picker (`ACTION_OPEN_DOCUMENT` — no permission, and the app receives exactly the one
 * file that was picked). `WhisperModelManager.importNpuAssetPair` does the I/O; every decision it
 * makes is one of the functions below, so the decisions can be **executed** by a JVM test while the
 * `Context`-bound half is pinned as source. That split is this branch's standing answer to the two
 * battery survivors it has already paid for.
 *
 * ### Zip-slip, guarded twice — ported from the spike verbatim
 *
 * 1. **An allow-list of the two exact BARE filenames.** A name that must be `equals()`-equal to
 *    `encoder_qairt_context.bin` structurally cannot contain a path separator, a `..` segment or a
 *    leading `/` — the traversal cases are not special-cased, they are *unrepresentable*.
 * 2. **A canonical-path check** ([escapesTargetDir]) that the resolved destination really is inside
 *    the models directory. Redundant with (1) today, and kept because the two guards fail in
 *    different ways: (1) dies if someone widens the allow-list, (2) dies if a symlink or a
 *    filesystem quirk makes a legal name resolve somewhere illegal.
 *
 * ### The sizes are the CATALOG's, and there is exactly one record of them
 *
 * [requiredEntriesFor] reads `fileName`/`primaryBytes` and `pairedArtifact.fileName`/`approxBytes`
 * off the catalog entry rather than restating them. A second copy of `132_927_488` in this file
 * would be a second thing to keep true, and the one that drifted would be the one the importer
 * enforced.
 *
 * **The import gate is deliberately STRICTER than `WhisperModelManager.isInstalled`.** That
 * predicate is tolerance-based (±5 %, `WhisperCatalog.sizeWithinTolerance`) because it must accept
 * files this app did not write; the import writes them, knows their exact published length, and so
 * demands it exactly. Strictness in that direction is the safe one: everything the import accepts,
 * `isInstalled` accepts, so an import that reports success can never leave the tier reading as not
 * installed. Pinned by `theImportGateIsStrictlyStricterThanTheInstalledPredicate`.
 *
 * ### Nothing here logs content
 *
 * Zip entry names, byte counts and megabytes. Entry names come from a file the user chose, so they
 * are echoed truncated ([SAFE_NAME_CHARS]) and never interpolated into a path.
 */
object NpuAssetImport {

    /** The catalog id this importer exists for. There is exactly one paired tier. */
    const val TIER_ID: String = "npu"

    /** The `.part` suffix an entry is written under until its size verifies. */
    const val PART_SUFFIX: String = ".part"

    /**
     * The same 10 % headroom `WhisperModelManager.download` applies, for the same reason: a
     * filesystem that reports exactly enough free space still fails, because the writes are not the
     * only thing happening on the device while 358 MB inflates.
     */
    const val FREE_SPACE_MARGIN: Double = 1.1

    /** How much of a zip entry's name is ever echoed back into a log or a message. */
    const val SAFE_NAME_CHARS: Int = 80

    /**
     * What may be written, and at exactly what length. Derived from the catalog entry so the
     * pinned byte counts have one home; empty when the tier is absent, which refuses every entry.
     */
    fun requiredEntriesFor(model: WhisperModel?): Map<String, Long> {
        if (model == null) return emptyMap()
        val paired = model.pairedArtifact ?: return emptyMap()
        // primaryBytes, NOT approxBytes: for a paired tier approxBytes is the SUM of both files,
        // and the encoder is 63 % under it. The same trap Q7a's R14 measured surviving a full
        // battery in `isInstalled`.
        return mapOf(
            model.fileName to model.primaryBytes,
            paired.fileName to paired.approxBytes,
        )
    }

    /** The two entries of the shipped npu tier, or empty on a catalog that has lost it. */
    val requiredEntries: Map<String, Long> by lazy {
        requiredEntriesFor(WhisperCatalog.byId(TIER_ID))
    }

    /** Uncompressed bytes of the whole pair — the import's progress denominator. */
    fun pairBytes(required: Map<String, Long>): Long = required.values.sum()

    /** What one zip entry may do. */
    sealed interface EntryVerdict {
        /** Write it, under `[fileName]$PART_SUFFIX`, and hold it to exactly [expectedBytes]. */
        data class Accept(val fileName: String, val expectedBytes: Long) : EntryVerdict

        /**
         * Not one of ours — skipped, named, and the import continues. **Skipped rather than
         * fatal on purpose:** the published zip may carry a README, a checksum file or (should the
         * owner take that packaging decision) the HTP skel, and a release that gains a file must
         * not stop being importable by the build that shipped before it.
         */
        data class Ignore(val reason: String) : EntryVerdict

        /** One of ours, and wrong. The whole import fails; nothing is moved into place. */
        data class Refuse(val reason: String) : EntryVerdict
    }

    /**
     * The allow-list guard, and the header-size guard that rides with it.
     *
     * @param declaredBytes `ZipEntry.getSize()`. **`-1` is a legitimate value**, not a failure: a
     *        zip written as a stream carries no size in the local header, and refusing those would
     *        refuse a correctly-built archive. The authoritative check is the count of bytes
     *        actually written ([wrongSizeRefusal]); this one only lets a *declared* wrong size be
     *        refused before 132 MB is inflated to learn the same thing.
     */
    fun classifyEntry(
        required: Map<String, Long>,
        rawName: String,
        declaredBytes: Long,
    ): EntryVerdict {
        val expected = required[rawName]
            ?: return EntryVerdict.Ignore("unexpected entry: ${safeName(rawName)}")
        if (declaredBytes >= 0L && declaredBytes != expected) {
            return EntryVerdict.Refuse(wrongSizeRefusal(rawName, declaredBytes, expected))
        }
        return EntryVerdict.Accept(rawName, expected)
    }

    /**
     * The second zip-slip guard: does [destCanonicalPath] fall outside [dirCanonicalPath]?
     *
     * A pure string comparison so it is testable without a filesystem; the canonicalisation itself
     * happens at the call site, where the `File` is. The trailing separator matters — without it,
     * `/data/.../modelsEVIL` starts with `/data/.../models`.
     */
    fun escapesTargetDir(
        dirCanonicalPath: String,
        destCanonicalPath: String,
        separator: String = File.separator,
    ): Boolean = !destCanonicalPath.startsWith(dirCanonicalPath + separator)

    /**
     * Bytes that must be free before the import may start (I12 — the transient the earlier draft
     * ignored).
     *
     * The pair inflates to 358 MB of `.part` files, and when a pair is ALREADY installed those
     * 358 MB stay on disk until the two renames at the very end — that is what makes a re-import
     * non-destructive, and it is also what doubles the requirement. The picked zip (~280 MB) is
     * read as a stream and never copied by this app, but on a single-partition device it is
     * usually on this same filesystem, so the number the user must actually have free is higher
     * still; the margin is not a substitute for that and the refusal names real figures rather
     * than pretending otherwise.
     */
    fun requiredFreeBytes(pairBytes: Long, pairAlreadyInstalled: Boolean): Long {
        val copies = if (pairAlreadyInstalled) 2 else 1
        return (pairBytes.toDouble() * copies * FREE_SPACE_MARGIN).toLong()
    }

    /** Null when there is room; otherwise a refusal **naming the shortfall**. */
    fun freeSpaceRefusal(usableBytes: Long, requiredBytes: Long): String? {
        if (usableBytes >= requiredBytes) return null
        val shortMb = mb(requiredBytes - usableBytes)
        return "Not enough free storage to import the model pair: it needs about " +
            "${mb(requiredBytes)} MB free and this device has ${mb(usableBytes)} MB. " +
            "Free about $shortMb MB and try again. Nothing was installed."
    }

    /** The refusal for one of ours at the wrong length — stated in bytes, both numbers. */
    fun wrongSizeRefusal(fileName: String, actualBytes: Long, expectedBytes: Long): String =
        "${safeName(fileName)} is the wrong size: $actualBytes bytes, expected $expectedBytes. " +
            "The download may be incomplete. Nothing was installed."

    /**
     * Null when every required entry arrived; otherwise the refusal naming the missing one(s).
     *
     * **This is the both-or-neither rule's other half.** An encoder without its decoder is not a
     * degraded tier, it is a tier that arms halfway and fails inside `nativeInit`; the pair is
     * renamed into place only after this returns null.
     */
    fun missingEntriesRefusal(required: Set<String>, accepted: Set<String>): String? {
        val missing = required - accepted
        if (missing.isEmpty()) return null
        return "That zip did not contain ${missing.sorted().joinToString(" and ") { safeName(it) }}. " +
            "The npu tier needs both files, so nothing was installed."
    }

    /** The refusal for a file that could not be read as a zip at all. */
    fun unreadableRefusal(cause: String): String =
        "Could not read that file as a model zip ($cause). Pick the pair zip from the release, " +
            "and check the download finished. Nothing was installed."

    /** `npu: import refused reason=…` — one greppable line under the house tag. */
    fun refusedLine(reason: String): String = "npu: import refused reason=$reason"

    /** `npu: import ok entries=2 bytes=358244352` — the success landmark for the Q10a run-book. */
    fun okLine(entries: Int, bytes: Long): String = "npu: import ok entries=$entries bytes=$bytes"

    /** The import as the UI sees it. One type, so a screen cannot render a state the manager
     *  never produces and the manager cannot return one the screen cannot show. */
    sealed interface ImportState {
        /** Nothing has been picked yet, or the picker was dismissed. */
        object Idle : ImportState

        /** [soFar] of [total] uncompressed bytes written. */
        data class Running(val soFar: Long, val total: Long) : ImportState

        /** Both files verified and renamed into place. */
        object Installed : ImportState

        /** Refused, loudly, with the reason the user is shown. */
        data class Refused(val reason: String) : ImportState
    }

    private fun mb(bytes: Long): Long = bytes / 1_000_000

    /** Entry names come out of a file the user picked, so they are echoed bounded. */
    private fun safeName(raw: String): String =
        if (raw.length <= SAFE_NAME_CHARS) raw else raw.take(SAFE_NAME_CHARS) + "…"
}
