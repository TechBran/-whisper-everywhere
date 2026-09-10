package com.whispereverywhere.play

import android.content.Context
import com.google.android.play.core.assetpacks.AssetPackManager
import com.google.android.play.core.assetpacks.AssetPackManagerFactory

/**
 * WHERE a delivered Play asset pack's files are, and how to give them back — the two
 * `AssetPackManager` reads that every pack family in this app shares, spelled ONCE (4.4.0, the
 * 2026-09-10 amendment: the previewer and the read-aloud voice join the two NPU tiers on Play
 * Asset Delivery, and the amendment's rule for their runtime side is "reuse the same helper for
 * the two new packs; do not fork it").
 *
 * Everything policy-shaped stays out: which pack to fetch, what a failure means, when a delivered
 * pack may be removed are decisions their own owners make and their own JVM tests execute
 * (`NpuPackFetch`, `StreamingPackInstall`). This object only answers the question Android is the
 * only one who can answer, which is why it has no test of its own — `AssetPackManagerFactory`
 * cannot be constructed off-device.
 *
 * `NpuPackController` locates through [assetsPath]'s manager overload with the instance it has
 * already registered its listener on; its own `removePack` call site stays where it is, because
 * the order invariant that call carries (STRICTLY after the staged pair is renamed into place) is
 * pinned as source text at that site.
 */
object PlayPacks {

    /**
     * The delivered pack's assets root (`AssetPackLocation.assetsPath()`), or null when Play has
     * not delivered it — which is also the honest answer on a build with no Play at all.
     *
     * The four files of an untargeted pack arrive in a directory named after the PACK: Play
     * strips a `#group_<g>` suffix on delivery, and 4.2 F8's entry-clash rule is why the
     * directory carries the pack's name in the first place.
     */
    fun assetsPath(context: Context, packName: String): String? =
        assetsPath(managerFor(context), packName)

    /** The same read against an already-created manager — the caller that registered a listener. */
    fun assetsPath(manager: AssetPackManager, packName: String): String? =
        manager.getPackLocation(packName)?.assetsPath()

    /**
     * Hand a delivered pack back to Play. ONLY ever after the bytes have been verified and landed
     * somewhere we own: a delivered pack is the only copy of those bytes until this returns, so a
     * remove that runs early deletes the source mid-install.
     */
    fun remove(context: Context, packName: String) {
        runCatching { managerFor(context).removePack(packName) }
    }

    private fun managerFor(context: Context): AssetPackManager =
        AssetPackManagerFactory.getInstance(context.applicationContext)
}
