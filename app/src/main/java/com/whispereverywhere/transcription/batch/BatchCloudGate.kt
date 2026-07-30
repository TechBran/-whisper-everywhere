package com.whispereverywhere.transcription.batch

/**
 * Invariant #2 written as code: batch cloud transcription requires ALL of a selected provider, a
 * stored key, and accepted disclosure v2 — the same triad live dictation enforces.
 *
 * This exists because the live path's enforcement is not reusable here:
 * FloatingBubbleService.resolveTranscriptionEngine is a private member returning a fully-wired
 * engine, and it does NOT itself read cloudDisclosureAccepted (that gating lives upstream, in
 * provider setup, where a key cannot be stored and a provider cannot be selected without
 * acceptance). Batch constructs its own provider, so it re-asserts the whole triad in one pinned
 * predicate rather than trusting the upstream implication from a different screen's flow.
 *
 * Deliberately the ONLY gate class here: batch has no capture path, so there is no capture-source
 * dimension to gate on. Everything this predicate does not cover (cost confirm, notifications) is
 * a separate explicit check in the service, never an implication.
 */
object BatchCloudGate {
    fun cloudEligible(providerId: String?, key: String?, disclosureAccepted: Boolean): Boolean =
        providerId != null && !key.isNullOrBlank() && disclosureAccepted
}
