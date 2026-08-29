package com.whispereverywhere.npu

import com.whispereverywhere.model.WhisperCatalog
import com.whispereverywhere.model.WhisperModel
import java.io.File

/**
 * The paired npu-class tiers' asset import, as **pure decisions** (4.0 Q8; per-tier and
 * hash-verified since 4.1 L6).
 *
 * A gated tier is not installed by downloading: it is two QAIRT context binaries, published
 * together as one zip, and the owner brings them across with the system document picker
 * (`ACTION_OPEN_DOCUMENT` — no permission, and the app receives exactly the one file that was
 * picked). `WhisperModelManager.importNpuAssetPair` does the I/O for whichever tier of
 * [PAIRED_TIER_IDS] it is asked for; every decision it makes is one of the functions below, so the
 * decisions can be **executed** by a JVM test while the `Context`-bound half is pinned as source.
 * That split is this branch's standing answer to the two battery survivors it has already paid for.
 *
 * ### Zip-slip, guarded twice — ported from the spike verbatim
 *
 * 1. **An allow-list of the tier's two exact BARE filenames.** A name that must be
 *    `equals()`-equal to `encoder_qairt_context.bin` structurally cannot contain a path separator,
 *    a `..` segment or a leading `/` — the traversal cases are not special-cased, they are
 *    *unrepresentable*.
 * 2. **A canonical-path check** ([escapesTargetDir]) that the resolved destination really is inside
 *    the models directory. Redundant with (1) today, and kept because the two guards fail in
 *    different ways: (1) dies if someone widens the allow-list, (2) dies if a symlink or a
 *    filesystem quirk makes a legal name resolve somewhere illegal.
 *
 * ### The sizes AND the digests are the CATALOG's, and there is exactly one record of them
 *
 * [requiredEntriesFor] reads `fileName`/`primaryBytes`/`sha256` and
 * `pairedArtifact.fileName`/`approxBytes`/`sha256` off the catalog entry rather than restating
 * them. A second copy of `132_927_488` in this file would be a second thing to keep true, and the
 * one that drifted would be the one the importer enforced. The digests were placeholders nowhere:
 * every one is MEASURED from the extracted vendor files, and the two ~GB turbo entries flow
 * through the same map the moment their tier id is passed.
 *
 * **The import gate is deliberately STRICTER than `WhisperModelManager.isInstalled`.** That
 * predicate is tolerance-based (±5 %, `WhisperCatalog.sizeWithinTolerance`) because it must accept
 * files this app did not write; the import writes them, knows their exact published length and
 * digest, and so demands both exactly. Strictness in that direction is the safe one: everything
 * the import accepts, `isInstalled` accepts, so an import that reports success can never leave the
 * tier reading as not installed. Pinned by
 * `theImportGateIsStrictlyStricterThanTheInstalledPredicate`. The one route that stays
 * hash-exempt is the owner's `adb push` (a dev route that never enters this code at all) — the
 * run-book says so where it prescribes it.
 *
 * ### Nothing here logs content
 *
 * Zip entry names, byte counts, digests and megabytes. Entry names come from a file the user
 * chose, so they are echoed truncated ([SAFE_NAME_CHARS]) and never interpolated into a path.
 */
object NpuAssetImport {

    /**
     * The npu (small) tier's catalog id. Since 4.1 L6 the importer serves every id in
     * [PAIRED_TIER_IDS]; this constant stays as npu's own because two OTHER readers key on it —
     * `NpuBackendSelector.routesToNpu`'s tier clause and `isMelDonorEligible`'s reasoning — and
     * neither of those is about "a paired tier", both are about THIS one.
     */
    const val TIER_ID: String = "npu"

    /**
     * Every catalog tier this importer can serve — STRUCTURAL, not a literal list: any tier with
     * a [WhisperModel.pairedArtifact] imports through the same transaction, so the next npu-class
     * tier joins by the clause that already admits these two rather than by a string somebody has
     * to remember. `by lazy` for the same initialization-order caution the 4.0 `requiredEntries`
     * value carried: this object must not force `WhisperCatalog.<clinit>` from its own.
     */
    val PAIRED_TIER_IDS: List<String> by lazy {
        WhisperCatalog.entries.filter { it.pairedArtifact != null }.map { it.id }
    }

    /** The `.part` suffix an entry is written under until its size verifies. */
    const val PART_SUFFIX: String = ".part"

    /**
     * The suffix a **previously installed** file is parked under while the new one is moved into
     * place (fix round 1, I2). It is what makes a failed second rename recoverable: the old file is
     * moved aside rather than deleted, so it can be moved back.
     */
    const val PREVIOUS_SUFFIX: String = ".prev"

    /**
     * The same 10 % headroom `WhisperModelManager.download` applies, for the same reason: a
     * filesystem that reports exactly enough free space still fails, because the writes are not the
     * only thing happening on the device while 358 MB inflates.
     */
    const val FREE_SPACE_MARGIN: Double = 1.1

    /** How much of a zip entry's name is ever echoed back into a log or a message. */
    const val SAFE_NAME_CHARS: Int = 80

    /**
     * One allow-listed entry: its exact published length, and the sha256 the copy must hash to.
     * Both come off the catalog; neither is ever restated here.
     */
    data class RequiredEntry(val bytes: Long, val sha256: String)

    /**
     * What may be written, at exactly what length, hashing to exactly what digest. Derived from
     * the catalog entry so the pinned values have one home; empty when the tier is absent or
     * unpaired, which refuses every entry.
     */
    fun requiredEntriesFor(model: WhisperModel?): Map<String, RequiredEntry> {
        if (model == null) return emptyMap()
        val paired = model.pairedArtifact ?: return emptyMap()
        // primaryBytes, NOT approxBytes: for a paired tier approxBytes is the SUM of both files,
        // and the encoder is 63 % under it. The same trap Q7a's R14 measured surviving a full
        // battery in `isInstalled`.
        return mapOf(
            model.fileName to RequiredEntry(model.primaryBytes, model.sha256),
            paired.fileName to RequiredEntry(paired.approxBytes, paired.sha256),
        )
    }

    /** Uncompressed bytes of the whole pair — the import's progress denominator. */
    fun pairBytes(required: Map<String, RequiredEntry>): Long = required.values.sumOf { it.bytes }

    /** What one zip entry may do. */
    sealed interface EntryVerdict {
        /**
         * Write it, under `[fileName]$PART_SUFFIX`, hold it to exactly [expectedBytes], and hold
         * its streamed digest to exactly [expectedSha256] before it counts as arrived.
         */
        data class Accept(
            val fileName: String,
            val expectedBytes: Long,
            val expectedSha256: String,
        ) : EntryVerdict

        /**
         * Not one of ours — skipped, named, and the import continues. **Skipped rather than
         * fatal on purpose:** the published zip may carry a README or a checksum file, and a
         * release that gains one must not stop being importable by the build that shipped before
         * it. (The HTP skel is no longer a candidate rider: 4.1 L6 retired that packaging
         * question — it ships in the APK's assets and never in a delivery zip.)
         */
        data class Ignore(val reason: String) : EntryVerdict

        /** One of ours, and wrong. The whole import fails; nothing is moved into place. */
        data class Refuse(val reason: String) : EntryVerdict
    }

    /**
     * The allow-list guard, the duplicate guard, and the header-size guard that rides with them.
     *
     * @param declaredBytes `ZipEntry.getSize()`. **`-1` is a legitimate value**, not a failure: a
     *        zip written as a stream carries no size in the local header, and refusing those would
     *        refuse a correctly-built archive. The authoritative check is the count of bytes
     *        actually written ([wrongSizeRefusal]); this one only lets a *declared* wrong size be
     *        refused before 132 MB is inflated to learn the same thing.
     * @param alreadyAccepted names whose copies have already verified in THIS import (4.1 L6,
     *        Q8 M3). A second copy of an allowed name is a **repack fault, refused outright**: it
     *        would double-count `written` — corrupting `npu: import ok entries=2 bytes=…`, the one
     *        number the run-book greps as the success landmark — and with a digest per entry there
     *        is no honest reading of "the same name twice". Deliberately NO default value: a call
     *        site that forgot to thread the set would silently re-open the duplicate hazard, which
     *        is the same reason `NpuDecodePolicy`'s family parameter has no default.
     */
    fun classifyEntry(
        required: Map<String, RequiredEntry>,
        rawName: String,
        declaredBytes: Long,
        alreadyAccepted: Set<String>,
    ): EntryVerdict {
        val expected = required[rawName]
            ?: return EntryVerdict.Ignore("unexpected entry: ${safeName(rawName)}")
        if (rawName in alreadyAccepted) {
            return EntryVerdict.Refuse(duplicateEntryRefusal(rawName))
        }
        if (declaredBytes >= 0L && declaredBytes != expected.bytes) {
            return EntryVerdict.Refuse(wrongSizeRefusal(rawName, declaredBytes, expected.bytes))
        }
        return EntryVerdict.Accept(rawName, expected.bytes, expected.sha256)
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
     * Null when the streamed digest matches; otherwise the refusal naming the file and BOTH
     * hashes, truthfully labelled (4.1 L6). Checked by the manager immediately after the
     * written-bytes check and before the entry counts as accepted, so a hash failure leaves
     * through exactly the refusal-and-rollback door a size failure does — the `.part` dies in the
     * `finally`, nothing has been parked, and a previously installed pair is untouched.
     *
     * The digest itself is computed DURING the copy, from the same buffers the write took: a
     * second pass over a 776 MB entry would double the import's I/O to learn what the first pass
     * already knew. No path ever enters this message — the file name is an allow-listed bare name,
     * echoed bounded like every other.
     */
    fun wrongDigestRefusal(fileName: String, expectedSha256: String, actualSha256: String): String? {
        if (actualSha256 == expectedSha256) return null
        return "${safeName(fileName)} is the right size but not the published file: its sha256 " +
            "is $actualSha256, expected $expectedSha256. The zip is not the published model " +
            "pair, or the download corrupted it. Nothing was installed."
    }

    /**
     * The refusal for a second copy of an allowed name (4.1 L6, Q8 M3). Refused outright rather
     * than skipped or double-written: a duplicate would double-count `written` — corrupting the
     * `okLine` byte total the run-book greps for — and with a digest per entry a second copy of a
     * name is a repack fault, not a bigger model.
     */
    fun duplicateEntryRefusal(fileName: String): String =
        "${safeName(fileName)} appears twice in that zip. Each model file must appear exactly " +
            "once, so this is not the published model pair. Nothing was installed."

    /**
     * Null when every required entry arrived; otherwise the refusal naming the missing one(s).
     *
     * **This is the both-or-neither rule's other half.** An encoder without its decoder is not a
     * degraded tier, it is a tier that arms halfway and fails inside `nativeInit`; the pair is
     * renamed into place only after this returns null. Worded per-pair rather than per-tier since
     * L6, because two tiers now import through it.
     */
    fun missingEntriesRefusal(required: Set<String>, accepted: Set<String>): String? {
        val missing = required - accepted
        if (missing.isEmpty()) return null
        return "That zip did not contain ${missing.sorted().joinToString(" and ") { safeName(it) }}. " +
            "This model pair needs both files, so nothing was installed."
    }

    /**
     * The refusal for an entry that keeps producing bytes past its expected length (fix round 1,
     * I1) — **the only refusal that must be reachable mid-copy rather than after it.**
     *
     * A zip entry's header size is not evidence. `-1` is legal (a streamed archive) and a stated
     * size can simply be a lie, so an importer that only compares the byte count *after* the copy
     * will happily write until the filesystem is full: a 40 KB zip bomb declaring `-1` fills a
     * phone and is then refused for being the wrong size, which is a true statement made far too
     * late. The copy is therefore bounded to `expected + 1` bytes — one byte of headroom, because
     * reaching it is the proof and nothing smaller is.
     */
    fun overLengthRefusal(fileName: String, expectedBytes: Long): String =
        "${safeName(fileName)}: entry larger than declared. It kept producing data past its " +
            "expected $expectedBytes bytes, so the copy was stopped. That file is not the " +
            "published model pair. Nothing was installed."

    /**
     * A finalise step failed and the previously installed pair was **put back** (fix round 1, I2).
     *
     * @param what the step, as a sentence opener — the user is told which half went wrong.
     */
    fun rolledBackRefusal(what: String): String =
        "$what, so the import was rolled back. Your previously installed model pair is unchanged, " +
            "and nothing new was installed."

    /**
     * The same roll-back on a device that **had no pair to begin with** (micro-round 2, N2).
     *
     * [rolledBackRefusal] promises that "your previously installed model pair is unchanged", which
     * on a first import is a sentence about something that never existed. It is not false so much
     * as disorienting — a user installing the tier for the first time is told their existing
     * install survived. The state is genuinely different and gets its own words.
     */
    fun rolledBackFreshRefusal(what: String): String =
        "$what, so the import was rolled back. Nothing was installed — this device had no model " +
            "pair before, and has none now. Try the import again."

    /**
     * A finalise step failed AND the roll-back failed — **the one message that may not say
     * "nothing was installed", because something was** (fix round 1, I2).
     *
     * It names the exact state of the device instead. A user who is told nothing changed, while
     * their working tier has in fact been dismantled, cannot even describe the problem; naming the
     * live and the missing files makes the next action obvious and the bug report possible.
     */
    fun rollbackFailedRefusal(what: String, live: List<String>, gone: List<String>): String {
        val liveText =
            if (live.isEmpty()) "neither model file is present"
            else "present: " + live.sorted().joinToString(" and ") { safeName(it) }
        val goneText =
            if (gone.isEmpty()) "" else "; missing: " + gone.sorted().joinToString(" and ") { safeName(it) }
        return "$what, and the previous files could not be put back. On this device now, " +
            "$liveText$goneText. Import the model pair zip again to repair this."
    }

    /**
     * What to do about `.prev` files found at the start of an import (micro-round 2, N3).
     *
     * They exist only when a previous import died **inside** the two-phase finalise, so the
     * question is never "tidy up" — it is "which direction did that transaction not get to
     * finish in".
     */
    enum class Reconcile {
        /** No parked file: nothing was interrupted mid-finalise. */
        NOTHING,

        /** Every new file landed. The pair on disk IS the new pair; drop the parked copies. */
        COMPLETE_FORWARD,

        /** The finalise was interrupted. Undo it: remove what landed, put the parked files back. */
        ROLL_BACK,
    }

    /**
     * The reconciliation semantic, as a pure decision (micro-round 2, N3).
     *
     * **The per-FILE rule this replaces could synthesize a pair that never existed.** Deciding each
     * name on its own — destination present, drop the parked copy; destination missing, restore it
     * — turns "the dead process renamed the encoder and then died" into *new encoder beside old
     * decoder*, with `isInstalled` true and nothing anywhere saying the two came from different
     * imports. That is not a recovery, it is a state no code path ever wrote.
     *
     * So the decision is made for the TIER: either the interrupted transaction reached the end of
     * phase 2 for every file — in which case finishing it forward is correct and the pair is
     * consistent — or it did not, in which case it is finished in the **roll-back** direction. The
     * one thing it may never do is finish half of it.
     *
     * @param names every file the tier needs.
     * @param parked names with a `.prev` on disk. Empty means no finalise was interrupted.
     * @param movedIn names whose staged `.part` is gone while the destination is present — the
     *        evidence that phase 2 already renamed that one into place. Meaningful only when
     *        [parked] is non-empty, because a settled installation looks identical.
     */
    fun reconcileDecision(
        names: Set<String>,
        parked: Set<String>,
        movedIn: Set<String>,
    ): Reconcile = when {
        parked.isEmpty() -> Reconcile.NOTHING
        names.isNotEmpty() && movedIn.containsAll(names) -> Reconcile.COMPLETE_FORWARD
        else -> Reconcile.ROLL_BACK
    }

    /**
     * The refusal for a file that could not be read as a zip at all.
     *
     * The cause is a THROWABLE's message headed for user-visible card copy (4.1 L6, Q8 M2), and
     * an `IOException` message typically carries the full internal `.part` path — so it is
     * bounded here, at the one builder both callers (the manager's catch and
     * `NpuImportController`'s) funnel through, with the same [SAFE_NAME_CHARS] bound every other
     * echo takes.
     */
    fun unreadableRefusal(cause: String): String =
        "Could not read that file as a model zip (${safeName(cause)}). Pick the pair zip from " +
            "the release, and check the download finished. Nothing was installed."

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
