package com.whispereverywhere.npu

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The process-scoped half of `NpuWhisperBackend.unavailableReason` — the bridge that gives that
 * property the consumer Q6 wrote it for (4.0, Q8, step 5).
 *
 * ### Why a bridge exists at all
 *
 * `unavailableReason` is an INSTANCE property of `NpuWhisperBackend`, and by design nothing in this
 * app holds that instance: the backend is constructed by the engine/service layer (Q9), lives for
 * the length of a transcription session, and is not reachable from a Compose screen. The card that
 * must say *"the NPU declined this session, and here is the stage"* therefore cannot read the
 * property directly, and the alternative — handing the chooser a backend reference — would put a
 * 376 MiB native session behind a UI dependency.
 *
 * So the backend PUBLISHES here, from the setter of that same property, and the card SUBSCRIBES.
 * Publishing from the setter rather than from each assignment site is deliberate and is the same
 * "one funnel" rule as `PreferencesManager.notifyModelInstalled`: there is no way to set the reason
 * and forget to announce it, including from a stage nobody has written yet.
 *
 * ### Why an object with no `Context`
 *
 * So it can be **executed** by a JVM test — the same argument, and the same shape, as
 * `ModelInstallSignal`. `NpuWhisperBackend` itself may not even be *named* by a unit test (its
 * `QnnAsrNative` reference runs `System.loadLibrary("qnnasr")` at class-init), so the wiring into
 * this object is pinned as source text and everything downstream of it is tested for real.
 *
 * ### What the value means
 *
 * `"<stage>: <detail>"` once the tier has declined, null while it is live or has never run. It is
 * **PROCESS state, not device state**, and that second distinction is the one that still holds:
 * a device that has never armed the tier shows no note at all, because "unavailable on this device"
 * is a claim about a measurement someone took, and until the tier runs, nobody took one.
 *
 * ### Why "process", corrected (4.0, final review F3 / I4)
 *
 * This said **session** state, on the reasoning that `load()` clears the value on every arm. True
 * of this class in isolation; **false in the shipped composition.** A tier's record feeds
 * `NpuBackendSelector.routesToNpu`'s declined set, which then answers false for that tier, so
 * `FloatingBubbleService` builds the CPU engine — so that tier's `NpuWhisperBackend` is never
 * constructed again, so `load()`, the only writer of `null`, never runs again for it.
 * `releaseEverything()` does not clear it either. **One decline therefore persists until process
 * death** (per tier, since 4.1 L8), and the card said "for this session" about it on every later
 * visit to the chooser, whatever tier was selected.
 *
 * **The wording was corrected rather than the lifetime, and that is a decision with a reason.** The
 * obvious alternative — clear it in `releaseEverything()` — does not deliver what its name promises
 * and is worse on two counts. (1) `releaseEverything()` does not run at session end: the engine
 * caches its native context across sessions and frees it only on `onTrimMemory` or service destroy,
 * so the lifetime would become *"until memory pressure"* — less predictable than process scope, not
 * more. (2) Every clear re-opens the retry loop that process scope exists to close: a device where
 * the tier reliably declines would pay a 342 MiB load and a guaranteed failure again on the next
 * trigger. Q6's at-most-once funnel keeps the FIRST stage's reason precisely because that is the
 * true one; carrying the same discipline out to the process is consistent with it. So the fix is to
 * say what is true — here, in [cardNote], and in `NpuBackendSelector.routesToNpu`'s parameter doc.
 */
object NpuTierStatus {

    private val _reasons = MutableStateFlow<Map<String, String>>(emptyMap())

    /**
     * Every declined tier's `"<stage>: <detail>"`, keyed by tier id (4.1 L8 — per-tier).
     *
     * **Per-tier because a shared reason was the worst possible coupling in an A/B lab.** Until
     * L8 this object held ONE process-wide reason, which was correct while one npu-class tier
     * existed; with two, a turbo decline — *"init: could not deserialise 740 MB"* — would have
     * banned `npu` for the rest of the process (the shared reason fed `routesToNpu`'s decline
     * input for every tier), and the card would have worn turbo's note on npu's card. The record
     * is now keyed under [NpuModelSpec.tierId], the routing reads [declinedTiers] membership, and
     * the card reads [reasonFor] its OWN id — so each tier's decline stands, and only stands, for
     * itself.
     *
     * Read as Compose state by the tier cards; written only by
     * `NpuWhisperBackend.unavailableReason`'s setter, under `spec.tierId`.
     */
    val reasons: StateFlow<Map<String, String>> = _reasons.asStateFlow()

    /**
     * Called from the backend's setter — every arm and every decline, no exceptions. A null
     * [reason] is the arm path clearing THIS tier's record; other tiers' records stand.
     */
    fun publish(tierId: String, reason: String?) {
        _reasons.value =
            if (reason == null) _reasons.value - tierId else _reasons.value + (tierId to reason)
    }

    /** The declined stage recorded for [tierId], or null — including for a null/unknown id. */
    fun reasonFor(tierId: String?): String? = if (tierId == null) null else _reasons.value[tierId]

    /**
     * The tiers with a live decline on record — `routesToNpu`'s per-tier decline input. The same
     * PROCESS lifetime as ever (see the class KDoc): membership ends at process death, by design.
     */
    val declinedTiers: Set<String> get() = _reasons.value.keys

    /**
     * The stage alone: `"init: nativeInit failed at 0"` -> `"init"`. Null in, null out.
     *
     * The stage is the greppable word `NpuDiag.unavailable` prints, and it is the part a user's
     * screenshot can usefully carry; the detail behind it is a native error string that means
     * nothing outside a bug report. Blank or malformed input degrades to the whole trimmed string
     * rather than to an empty label, because a card that says "unavailable ()" is worse than one
     * that repeats something odd.
     */
    fun stageOf(reason: String?): String? {
        val trimmed = reason?.trim() ?: return null
        if (trimmed.isEmpty()) return null
        val head = trimmed.substringBefore(':').trim()
        return if (head.isEmpty()) trimmed else head
    }

    /**
     * The tier the decline recovery downloads (4.3) — `multi`, the multilingual CPU tier, which is
     * both a legal 80-bin mel donor and the fallback `cpuTierModelPath()` will then find. Named
     * here, once, because the card's button and any test of it must mean the same tier.
     */
    const val RECOVERY_TIER_ID = "multi"

    /** The recovery button's label — the spec's own words for the one-tap action. */
    const val RECOVERY_ACTION = "Download the standard model"

    /**
     * What the user is told AT THE MOMENT OF THE SWITCH (4.3 fix round, I-1).
     *
     * The recovery rides `ModelDownloadViewModel.download`, which persists
     * `prefs.selectedModelId` on success — the right sink, because leaving the selection on a
     * declined npu-class tier routes a QAIRT context binary to `WhisperNativeBackend` and yields
     * no working backend at all. But the write is PERMANENT while the decline that provoked it is
     * only process-scoped, so without this sentence a capable phone comes back after a restart
     * showing `npu-turbo` at the head of its one-card chooser, badged "Best match for your
     * language", while quietly transcribing on the 190 MB CPU model — discoverable only in
     * Settings. The behaviour is right; the silence was not.
     *
     * Three things, because dropping any one leaves the switch half-explained: that the model
     * CHANGED, that the gigabyte the user already paid for is NOT gone, and where the way back is.
     */
    const val RECOVERY_SWITCH_NOTE =
        "Switched to the standard model. Your AI chip model stays installed — pick it again from " +
            "this screen any time."

    /**
     * The sentence the tier card shows, or null when there is nothing to say.
     *
     * It states four things because leaving any one out is how a silent fallback happens: that the
     * NPU is not being used, that the multilingual CPU model is running instead **so the app still
     * works**, which stage declined so the report is actionable, and — since the final review's F3
     * — **how to get back**. A fallback that quietly ran on the CPU while the card still promised
     * the AI chip is the failure this project has already paid for once.
     *
     * **"for this session" is gone, and the last sentence replaces it.** The decline outlives every
     * session (see the class KDoc), so the old copy told the user something false on every later
     * visit to this screen, including sessions they deliberately ran on another tier. The retry is
     * an app restart and nothing else — there is no in-app route back — so the note now says that
     * rather than leaving the user to discover it.
     *
     * ### 4.3 — the arm for a device with nothing to fall back to
     *
     * The paragraph above describes a decline on a device that HAS a CPU model. 4.3 creates the
     * state where that is false: the chooser offers a capable device `npu-turbo` alone, so a fresh
     * capable install can hold turbo and nothing else — and `fallBackToCpuTier` then returns `0L`
     * at `paths.cpuTierModelPath() ?: return 0L`, leaving the session with no backend at all.
     * Rendering the sentence above there would be a **lie in the load-bearing clause**: it
     * promises speech is running on the CPU model, and no CPU model exists.
     *
     * So [cpuFallbackInstalled] selects the arm, and the false arm says exactly that — the chip
     * declined, at which stage, that there is nothing installed to fall back to, and what to do
     * about it. This is the spec's "hidden until relevant": the CPU tier is absent from a capable
     * chooser and appears the moment a decline makes it the answer, through
     * [RECOVERY_ACTION] beside this note. [needsCpuRecovery] answers the same question the arm
     * split answers, so the button and the sentence cannot disagree.
     *
     * ### 4.3 micro-round — the remedy depends on WHERE THE SELECTION POINTS
     *
     * "Restart the app to try the AI chip again" is true for exactly one reason: the decline
     * record dies with the process, so the next launch re-routes to this tier. That reasoning has
     * a second premise nobody had written down — **the selection must still name this tier** —
     * and the recovery is precisely what breaks it. After the user taps "Download the standard
     * model", `selectedModelId` is `multi`; `hasCpuFallback` flips true, so this note silently
     * swaps to the fallback-installed arm, and that arm kept promising a restart that
     * `NpuBackendSelector.routesToNpu` would send straight to the CPU. Worse, it sat inches below
     * the green [RECOVERY_SWITCH_NOTE] carrying the CORRECT way back — two sentences on one
     * screen disagreeing about how to reach the same tier.
     *
     * So [stillSelected] chooses the remedy, on BOTH arms, from one place ([retryRemedy]): a
     * selected tier is re-tried by a restart, an unselected one is re-tried by picking it — which
     * is the same route [RECOVERY_SWITCH_NOTE] names, deliberately, so the two can never point
     * anywhere different. `NpuTierStatusTest` executes that agreement rather than trusting it.
     *
     * @param cpuFallbackInstalled `WhisperCatalog.hasCpuFallback(installedIds)` — the pure mirror
     *        of the `cpuTierModelPath() != null` the backend itself reads. NO DEFAULT, deliberately:
     *        a default would let a caller silently claim the CPU model is running, which is the one
     *        thing this parameter exists to stop being assumed.
     * @param stillSelected does `prefs.selectedModelId` still name THIS tier? Also no default, and
     *        for the same reason: assumed true, it reinstates the false restart promise the
     *        micro-round removed.
     */
    fun cardNote(reason: String?, cpuFallbackInstalled: Boolean, stillSelected: Boolean): String? {
        val stage = stageOf(reason) ?: return null
        if (!cpuFallbackInstalled) {
            // 4.3 fix round, I-1(b): the two remedies are stated as ALTERNATIVES, in that order,
            // because they are not compatible. The first shipping wording read "...download the
            // standard model below, OR restart the app to try the AI chip again" as if both
            // survived the tap — but the download persists `selectedModelId` onto the CPU tier,
            // so the restart clause went FALSE the instant the user acted on the button printed
            // directly beneath it. Naming the switch here (and again at
            // [RECOVERY_SWITCH_NOTE] when it happens) is what makes the two sentences true
            // together: the re-try FIRST, as the remedy that costs nothing and keeps the AI chip;
            // the download SECOND, with its consequence and its way back attached.
            return "The AI chip is unavailable on this device right now (stage: $stage), and no " +
                "CPU speech model is installed to fall back to — so dictation cannot run until " +
                "one is. ${retryRemedy(stillSelected)} Or download the standard " +
                "multilingual model below — that switches you to it, and leaves your AI chip " +
                "model installed and one tap away on this screen."
        }
        // "Accuracy is unchanged" was TRUE in 4.0, when the only NPU tier ran whisper-small's own
        // weights — the same checkpoint the CPU multilingual model carries, so a decline really did
        // cost nothing but speed. 4.1 shipped `npu-turbo` (large-v3-turbo) and the claim quietly
        // went false: the owner's own A/B called turbo "much more accurate", and their field report
        // from a declining device (2026-08-30, a Galaxy S23 Ultra and a MediaTek tablet, both
        // correctly on the CPU model in production) put it plainly — "accuracy just suffers a bit".
        // A fallback card that tells a user they lost only speed, when they also lost the accuracy
        // they chose the tier for, is the same class of comfortable falsehood this file has now
        // corrected twice. State the real trade; it is still a good outcome, and it is honest.
        return "The AI chip is unavailable on this device right now (stage: $stage), so speech is " +
            "running on the multilingual CPU model. It is slower, and a little less accurate than " +
            "the AI chip model. " + retryRemedy(stillSelected)
    }

    /**
     * How to make this device try the AI chip again — the ONE place either arm of [cardNote] gets
     * that sentence, so the two cannot drift into disagreeing (4.3 micro-round).
     *
     * A restart works only while the selection still names this tier: the decline record is
     * process state and dies with the process, but `routesToNpu` reads the SELECTION, so a restart
     * on a device whose selection has moved to `multi` re-routes to the CPU and tries nothing.
     * The honest remedy there is the one the recovery's own confirmation already names — pick the
     * tier again, on this screen.
     */
    private fun retryRemedy(stillSelected: Boolean): String =
        if (stillSelected) {
            "Restart the app to try the AI chip again."
        } else {
            "Pick it again on this screen to try the AI chip."
        }

    /**
     * Whether this tier's card must offer [RECOVERY_ACTION] (4.3): there is a decline on record
     * AND nothing on disk to fall back to.
     *
     * **It is the same predicate [cardNote] splits its arms on, spelled once**, so the button can
     * never appear beside the sentence that says the CPU model is already running, nor be missing
     * beside the sentence that tells the user to tap it. `NpuTierStatusTest` executes that
     * equivalence over the whole input space rather than trusting the two to be edited together.
     */
    fun needsCpuRecovery(reason: String?, cpuFallbackInstalled: Boolean): Boolean =
        !cpuFallbackInstalled && stageOf(reason) != null
}
