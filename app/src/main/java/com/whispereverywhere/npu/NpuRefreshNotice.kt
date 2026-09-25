package com.whispereverywhere.npu

/**
 * WHAT THE USER IS TOLD WHEN THE REFRESH TAKES THEIR AI-CHIP PAIR AWAY, AND WHEN (4.15).
 *
 * Owner ruling 2026-09-24, on the v0.63.0 refresh's one-time re-download: keep it — *"the users
 * get a 4 to 7x faster pack — that speed boost is definitely worth it"* — and *"do a notification
 * for this that tells you: open the app to download the new model — so if their bubble isn't
 * working they're not freaking out; they just see the notification in the shade"*. Until this
 * object the update was silent: `BootReceiver` found `installedModel() == null` after the update,
 * logged one line and returned, so the bubble simply did not come back.
 *
 * Pure and Android-free, the `ModelTierCopy` pattern: every string a user reads here is a JVM test
 * subject (`NpuRefreshNoticeTest` pins each one exactly and runs the claim rules over it), and the
 * receiver's post-or-skip decision is a truth table the same test executes. `BootReceiver` only
 * renders what [decide] answers.
 *
 * ### The claim, and what it rests on
 *
 * "Faster" is the ONE comparative in this copy and it is bounded three ways. It is a comparative,
 * never a superlative or an absolute ("fastest", "instant", "real-time" are refused by the test).
 * It is device-neutral: it names no phone and says nothing about "this device", because the
 * notice reaches six census families and the comparison it makes is between two versions of the
 * user's OWN model, not between devices or apps. And its evidence is recorded rather than
 * implied: Qualcomm AI Hub's own profiles of the v0.63.0 binaries show the turbo encoder 4-7x
 * faster on every family (the vendor's numbers; the versionName and `ReleaseIdentityTest` say so),
 * and the owner ruled on that figure. What is NOT yet true, stated so nobody widens the copy on
 * the strength of it: no AI Hub job of ours and no in-app device run has timed a v0.63.0 pair
 * (the refresh sheet's §8), and this repo records no v0.63.0 speed figure for the SMALL pair at
 * all — the notice uses the same words for both tiers because the brief and the ruling do, and a
 * small-tier variant would mean turning [NOTIFICATION_TITLE] and [IN_APP_NOTE] into functions of
 * the tier. No multiplier appears in the copy, and none may until our own measurement stands
 * behind it.
 *
 * ### The size, and why it is a derivation
 *
 * The number is the device family's vendor zip length for the tier ([downloadBytesFor] —
 * `PackArtifact.sourceBytes`, asserted at HEAD on every measure run), rounded to the nearest SI
 * megabyte: 823,721,812 B on an 8 Gen 3's turbo row is "about 824 MB", 285,197,039 B on its small
 * row "about 285 MB", and the 7 Gen 4's turbo row says 828. Derived, never a literal, so the next
 * census moves the notice with it instead of leaving a number for a pin to chase. "About" is
 * doing real work: Play deflates asset packs in transit, the vendor zip of the same pair is the
 * best measured proxy for the transfer, and Play's own transfer size is not measured (sheet §7).
 * The INSTALLED size (981,968,552 B for the 8 Gen 3 turbo pair) is deliberately not in the
 * sentence: the question a user in the shade has is what the download costs them.
 *
 * ### When it comes down (112)
 *
 * The notice is AUTO_CANCEL, so a tap clears it, and the tap is the route it advertises. But the
 * app's own gate is a second route to the same Download button, and on the owner's Fold6
 * (2026-09-24) the pack re-downloaded that way at about 19:50 while the notice stood in the shade
 * untouched at 21:10 — "download it" still showing for a model already installed. So the shared
 * finalise takes it down the moment the pair it asked for lands
 * (`WhisperModelManager.finalizeVerifiedPair` → `BootReceiver.cancelRefreshNotice`), on the same
 * line that clears the re-download record, and only when the record it announced was the one
 * that cleared: a landing for another tier leaves both standing.
 */
object NpuRefreshNotice {

    /** The notification's title — the shade's first line. */
    const val NOTIFICATION_TITLE: String = "Your AI-chip model has a faster version"

    /**
     * The in-app sentence (the onboarding flow the app-wide gate lands on shows it while the
     * record stands): the same fact as the notification, and the one instruction that fixes it.
     */
    const val IN_APP_NOTE: String =
        "Your AI-chip model has a faster version — download it again (one-time)."

    /**
     * The notification's text for a download of [downloadBytes] — "One-time download of about
     * 824 MB. Tap to open Whisper Everywhere and get it." on an 8 Gen 3's turbo row.
     */
    fun notificationText(downloadBytes: Long): String =
        "One-time download of about ${downloadMb(downloadBytes)} MB. " +
            "Tap to open Whisper Everywhere and get it."

    /** SI megabytes rounded to the nearest whole one — the same base-1000 unit every badge uses. */
    fun downloadMb(bytes: Long): Long = (bytes + 500_000L) / 1_000_000L

    /**
     * The tier's download on THIS device's family — its census row's vendor zip length
     * ([PackArtifact.sourceBytes]) — or null when there is no row: no family, no tier, or a
     * family the census has no pack for. Null is also the "nothing to get here" answer [decide]
     * and [showsInAppNote] refuse on, so neither surface can tell a user to download a pack their
     * phone cannot be sent. A LOCAL row has no vendor zip and answers null too: this notice is the
     * v0.63.0 vendor refresh's, and a pair compiled here was never part of that refresh.
     */
    fun downloadBytesFor(family: NpuSocFamily?, tierId: String?): Long? {
        if (family == null || tierId == null) return null
        return NpuFleetCensus.artifactFor(family.id, tierId)?.sourceBytes
    }

    /** Which broadcast is asking. */
    enum class Trigger {
        /** `MY_PACKAGE_REPLACED` — the update that brought the new census. Once per event. */
        PACKAGE_REPLACED,

        /** `BOOT_COMPLETED` (and the QUICKBOOT spellings) — again on every boot while the record stands. */
        BOOT_COMPLETED,
    }

    /** What the receiver does. */
    sealed interface Decision {
        /** Post this notification. */
        data class Post(val title: String, val text: String) : Decision

        /** Post nothing, for this one-word reason (one of the `SKIP_` tokens — greppable). */
        data class Skip(val reason: String) : Decision
    }

    /** The selected tier IS installed: nothing is missing, so nothing to say. */
    const val SKIP_INSTALLED = "installed"

    /** No re-download is recorded: a missing model with no stale event is not this notice's. */
    const val SKIP_NO_RECORD = "no-record"

    /** The record is for a tier the user has since moved off. */
    const val SKIP_NOT_SELECTED = "not-selected"

    /** This device's census has no pack for the tier, so "get it" would be false. */
    const val SKIP_NO_PACK = "no-pack"

    /** The update notice already went out for this exact event. */
    const val SKIP_ALREADY_POSTED = "already-posted"

    /** POST_NOTIFICATIONS is not granted — the restart notification's guard, the same answer. */
    const val SKIP_NO_PERMISSION = "no-permission"

    /**
     * Post the refresh notice, or not — the whole truth table, pure.
     *
     * **Post once per stale event on an update, and again on every boot while the record stands;
     * never without one.** The update is when the bubble failed to come back, so that is the
     * moment to explain it — once: a later update that finds the same record (the user has not
     * fetched yet) does not repeat itself, which is what [notifiedCensusKey] is for. A boot is
     * different: it is exactly when this user would otherwise have been handed "Restart your
     * dictation bubble", so while the record stands the notice takes that notification's place on
     * every boot. The receiver asks only after the bubble's own eligibility has passed (it was on,
     * overlay and microphone still granted), so this notice reaches exactly the users who would
     * have had their bubble back — it replaces the silent return, and nobody else is told
     * anything.
     *
     * The order of the arms decides only which reason a skip reports; every arm must pass to post.
     *
     * @param installedModelNull `installedModel() == null` — the eligibility verdict that routes
     *        the receiver here.
     * @param selectedTierId `prefs.selectedModelId`: the record is about the selected tier or it
     *        is about nothing.
     * @param record the re-download record, or null — and null never posts.
     * @param notifiedCensusKey the census key the update notice last went out for.
     * @param downloadBytes [downloadBytesFor] the record's tier on this device, null when there is
     *        no pack to get.
     * @param canPost the POST_NOTIFICATIONS guard the restart notification uses.
     */
    fun decide(
        trigger: Trigger,
        installedModelNull: Boolean,
        selectedTierId: String?,
        record: NpuRedownload?,
        notifiedCensusKey: String?,
        downloadBytes: Long?,
        canPost: Boolean,
    ): Decision = when {
        !installedModelNull -> Decision.Skip(SKIP_INSTALLED)
        record == null -> Decision.Skip(SKIP_NO_RECORD)
        record.tierId != selectedTierId -> Decision.Skip(SKIP_NOT_SELECTED)
        downloadBytes == null -> Decision.Skip(SKIP_NO_PACK)
        trigger == Trigger.PACKAGE_REPLACED && notifiedCensusKey == record.censusKey ->
            Decision.Skip(SKIP_ALREADY_POSTED)
        !canPost -> Decision.Skip(SKIP_NO_PERMISSION)
        else -> Decision.Post(NOTIFICATION_TITLE, notificationText(downloadBytes))
    }

    /**
     * Show [IN_APP_NOTE]? While the record stands for the SELECTED tier and this device has a pack
     * to get. It goes the moment the pair lands (the shared finalise clears the record) or the user
     * picks another tier, and it never names a download the phone cannot be sent.
     */
    fun showsInAppNote(record: NpuRedownload?, selectedTierId: String?, downloadBytes: Long?): Boolean =
        record != null && record.tierId == selectedTierId && downloadBytes != null
}
