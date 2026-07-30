package com.whispereverywhere.transcription.batch

/**
 * The ONE cloud-vs-local decision for a batch job, as a pure predicate the service delegates to.
 *
 * The per-job engine choice the user made on the Ready screen ([useCloud]) is a HARD FLOOR, not an
 * implication of global prefs: if the user picked "On-device", this returns false immediately and
 * NOTHING — no network probe, no notification check, no cost math — can upgrade the job to cloud.
 * The bug this closes: the Ready-screen radio used to gate only whether the cost dialog appeared,
 * while the service re-derived cloud purely from the global triad, so an explicit "On-device" pick
 * on a sub-threshold file was silently uploaded and billed. The user's selection is now authoritative.
 *
 * Cloud is allowed ONLY when ALL hold, evaluated in this order (the two side-effecting probes are
 * lazy so a local job never touches the network or the notification manager):
 *   1. [useCloud] — the user asked for cloud on this job,
 *   2. [BatchCloudGate] triad — a resolvable provider, a stored key, accepted disclosure,
 *   3. a validated network,
 *   4. notifications enabled (the §6.5 spend indicator must be visible while charging),
 *   5. below the cost threshold, OR the explicit cost confirm rode the intent.
 */
object BatchEngineDecision {
    fun cloudAllowed(
        useCloud: Boolean,
        providerName: String?,
        key: String?,
        disclosureAccepted: Boolean,
        byteLength: Long,
        costConfirmed: Boolean,
        hasValidatedNetwork: () -> Boolean,
        notificationsEnabled: () -> Boolean,
    ): Boolean {
        if (!useCloud) return false
        if (!BatchCloudGate.cloudEligible(providerName, key, disclosureAccepted)) return false
        if (!hasValidatedNetwork()) return false
        if (!notificationsEnabled()) return false
        if (BatchCostEstimator.needsConfirmation(byteLength) && !costConfirmed) return false
        return true
    }
}
