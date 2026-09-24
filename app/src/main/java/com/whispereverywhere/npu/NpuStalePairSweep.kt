package com.whispereverywhere.npu

import com.whispereverywhere.model.WhisperModel
import java.io.File

/**
 * "The selected AI-chip tier needs a re-download" — the one fact the 4.15 refresh leaves behind
 * on a phone that held the previous pair (owner ruling 2026-09-24: keep the one-time
 * re-download, and tell the user). Recorded by the launch sweep ([NpuStalePairSweep]), read by
 * the boot/update notice and the onboarding flow's sentence ([NpuRefreshNotice]), and cleared
 * by the shared finalise the moment a pair for [tierId] lands.
 *
 * @property tierId the paired tier whose files were removed — the SELECTED tier at the moment of
 *   removal, or no record is written at all ([NpuStalePairSweep.recordFor]).
 * @property censusKey the census row the removed files were stale against
 *   ([NpuStalePairSweep.censusKey]): which event this is. The update notice posts once per key,
 *   so a later refresh that makes a newer pair stale is a new event, and the same event is never
 *   announced twice by an app update.
 */
data class NpuRedownload(val tierId: String, val censusKey: String) {
    companion object {
        /**
         * Does a pair that just landed for [landedTierId] resolve [stored]? Only a landing for the
         * RECORDED tier does: a CPU download or the other NPU tier's pair leaves the record alone,
         * and every reader checks the selected tier anyway, so a record for a tier the user moved
         * off is inert rather than wrong.
         */
        fun clearedBy(stored: NpuRedownload?, landedTierId: String): Boolean =
            stored != null && stored.tierId == landedTierId
    }
}

/**
 * THE STALE-PAIR SWEEP (4.15) — the launch half of the AI chip's first rebuild.
 *
 * The v0.63.0 census moved every encoder by 11.5-22.1 %, so on a phone that already held the
 * 0.62.2 pair `WhisperModelManager.isInstalled` reads FALSE after the update (the Fold6's turbo
 * encoder is 775,831,552 B against the new row's 686,112,520, +13.1 %; `NpuFleetCensusTest` pins
 * it). That re-download is the owner's ruling. What it left behind was about 1 GB of dead weight:
 * the old pair stayed on disk until the new one landed, and the finalise parks ANY existing
 * destination as `.prev` BY EXISTENCE while its free-space precheck
 * (`NpuAssetImport.requiredFreeBytes(total, isInstalled(model))`) budgets ONE copy when
 * `isInstalled` is false — so on a tight phone the precheck passed and the copy could then run out
 * of room. This sweep removes those files at launch, before any fetch or import can start, so the
 * precheck's answer and the directory agree again.
 *
 * **Deleting is safe because only census-mismatched files can ever sit under these names.** The
 * two arrival routes write them only through the shared finalise, after the exact family bytes and
 * the streamed sha256 have both verified (`NpuAssetImport.requiredEntriesFor` — the import and the
 * pack route refuse everything else), so a file here that fails the installed gate is either a
 * pair from an earlier census or damage. Neither can arm the tier: `isInstalled` refuses both,
 * `installedModel()` is null, and the app-wide gate already treats the tier as absent. Removing
 * them loses nothing the app could use. (The owner's `adb push` dev route writes these names by
 * hand and is held to the same gate: a pushed pair that fails it is removed at the next launch,
 * which is no loss, because it could not have armed the tier either.)
 *
 * **The rule is `isInstalled`'s own, never a second copy.** Staleness is
 * `!NpuAssetImport.passesInstalledGate(...)` over the files' real lengths, against the gate
 * `NpuAssetImport.installedGateBytes(model, artifact)` builds from THE DEVICE FAMILY's census row —
 * the exact two calls `isInstalled` makes. So the sweep cannot delete a pair `isInstalled`
 * accepts, and it cannot keep one `isInstalled` refuses.
 *
 * **What it leaves alone, deliberately:**
 *  - **No census row, no sweep.** A null [PackArtifact] — the family did not resolve (a CPU-only
 *    device that somehow holds these files, the Tab S10+'s sideloaded experiments), or the family
 *    has no measured row for the tier — means there is no census to be stale against. The
 *    manager does not call in without a family at all, and this function answers null for a null
 *    row: files stay exactly where they are. (`isInstalled` falls back to the catalog's reference
 *    record in that case; a DELETION must not, because an answer about another family's bytes is
 *    not evidence about these.)
 *  - **`.part` files.** Staging debris is `reconcileStagingDebris`' business, which runs FIRST on
 *    the same launch pass and finishes or rolls back any interrupted finalise — so this judges a
 *    settled directory, and a `.prev` it still finds is parked stale bytes, removed with its pair.
 *  - **Everything that is not a paired tier.** Only `NpuAssetImport.PAIRED_TIER_IDS` are swept,
 *    and none of them is `unsupported`, so the retired/unsupported-tier migration
 *    (`ModelMigration`) never meets this sweep: its sources and targets are single-file ggml rungs,
 *    which have no pair to be stale. `NpuStalePairSweepTest` holds both halves of that.
 *
 * Pure over `java.io.File` — no `Context` — so the JVM suite drives it on a temp directory; the
 * manager supplies the directory, the family's row and the selected tier, and writes the
 * [NpuRedownload] record.
 */
object NpuStalePairSweep {

    /**
     * One tier's removal, as the diag line and the record need it.
     *
     * @property encoderBytes / [decoderBytes] the on-disk length of each file BEFORE removal, or
     *   null when that file was absent (a half pair is stale too — the tier cannot arm on it).
     * @property censusEncoderBytes / [censusDecoderBytes] the family row's bytes the files were
     *   judged against — the numbers the `census=` field prints.
     * @property left every name that could not be deleted (primary, paired, or a `.prev` of
     *   either). Empty on the ordinary path; non-empty is reported, never swallowed, and the next
     *   launch tries again.
     */
    data class Removed(
        val tierId: String,
        val encoderBytes: Long?,
        val decoderBytes: Long?,
        val censusEncoderBytes: Long,
        val censusDecoderBytes: Long,
        val censusKey: String,
        val left: List<String>,
    )

    /**
     * The census row an event was stale against: `<family>:<encoder sha256>`. The encoder digest
     * alone names the row — the census's twenty-four digests are pinned distinct — and the family
     * rides in front so the key reads as what it is in a pref dump.
     */
    fun censusKey(artifact: PackArtifact): String = "${artifact.familyId}:${artifact.encoder.sha256}"

    /**
     * Sweep ONE paired tier in [dir]: remove its files when either is present and together they
     * fail the installed gate against [artifact]; otherwise touch nothing.
     *
     * @param artifact THE DEVICE FAMILY's row for this tier (`NpuFleetCensus.artifactFor`), or
     *   null — and null means "no census to be stale against": nothing is removed.
     * @return the removal, or null when nothing was removed: no row, not a paired tier, nothing on
     *   disk, or the files pass the gate.
     */
    fun sweep(dir: File, model: WhisperModel, artifact: PackArtifact?): Removed? {
        if (artifact == null) return null
        val paired = model.pairedArtifact ?: return null
        val primary = File(dir, model.fileName)
        val second = File(dir, paired.fileName)
        val primaryLength = if (primary.exists()) primary.length() else null
        val pairedLength = if (second.exists()) second.length() else null
        if (primaryLength == null && pairedLength == null) return null
        val gate = NpuAssetImport.installedGateBytes(model, artifact)
        if (NpuAssetImport.passesInstalledGate(gate, primaryLength, pairedLength)) return null
        // Stale. Both names and a parked `.prev` of either — never a `.part`, which is the
        // reconcile's. Every result is kept: a file that refuses to go is named in `left`.
        val left = mutableListOf<String>()
        for (name in listOf(model.fileName, paired.fileName)) {
            for (file in listOf(File(dir, name), File(dir, name + NpuAssetImport.PREVIOUS_SUFFIX))) {
                if (file.exists() && !file.delete()) left += file.name
            }
        }
        return Removed(
            tierId = model.id,
            encoderBytes = primaryLength,
            decoderBytes = pairedLength,
            censusEncoderBytes = artifact.encoder.bytes,
            censusDecoderBytes = artifact.decoder.bytes,
            censusKey = censusKey(artifact),
            left = left,
        )
    }

    /**
     * The record a removal leaves — ONLY when the removed tier is the SELECTED one, because the
     * record means "the selected tier needs a re-download". A stale pair of a tier the user is not
     * on is dead weight and nothing more: it is removed and logged, and nobody is told to fetch
     * it again.
     */
    fun recordFor(removed: Removed, selectedTierId: String?): NpuRedownload? =
        if (removed.tierId == selectedTierId) NpuRedownload(removed.tierId, removed.censusKey) else null
}
