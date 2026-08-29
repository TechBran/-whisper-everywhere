package com.whispereverywhere.transcription

import android.content.Context
import com.whispereverywhere.npu.NpuModelSpec

/**
 * Which [WhisperBackend] a session runs on — the NPU-class routing decision, as a truth table
 * (4.0 Q9; two npu-class tiers since 4.1 L8).
 *
 * ### Why this is a selector and not a factory
 *
 * There is **no backend factory in this app** and this class does not become one. The backend is a
 * *constructor default* on [LocalWhisperEngine] (`backend: WhisperBackend = WhisperNativeBackend`),
 * and the engine is **cached** for the whole life of `FloatingBubbleService`. So "route an npu
 * tier" is not a dispatch at call time; it is a decision taken **once, at engine construction**,
 * and changing it means building a different engine. That is why the only two members here are a
 * predicate and a constructor call: everything about *when* to ask is the service's, and the
 * mechanism by which an answer takes effect is the service rebuilding its cached engine.
 *
 * ### Why the decision lives in ONE predicate
 *
 * [routesToNpu] has two readers with two different needs — [backendFor] builds the object, and the
 * service asks the same question to decide whether the engine it already has is still the right
 * one (it records `tierId` as its `routedNpuTierId` exactly when this predicate says yes). Deriving
 * that second answer separately would be the same truth table written twice, and the two copies
 * would be free to disagree: the engine would be rebuilt on a decision the selector does not make,
 * or kept on one it no longer makes. One predicate, two callers.
 *
 * ### The three inputs, and why each is a set now
 *
 *  - **`tierId`** is `PreferencesManager.selectedModelId` — the id the model chooser writes.
 *    It routes to the NPU only when it is a **known npu-class tier**: [NpuModelSpec.forTier]
 *    answering non-null is the membership test, so the routing set is exactly the spec table's
 *    rows and this class mints no tier-id literal of its own. A typo'd or future id has no spec
 *    row, routes to the CPU backend, and cannot construct a backend it has no census for.
 *  - **`offeredNpuTierIds`** is `WhisperEverywhereApp.offeredNpuTierIds()` and **nothing else**:
 *    the memoised capability probe AND, per tier, that tier's own context binaries on disk. It
 *    became a set in 4.1 (L5) because two gated tiers can be independently installed and one bit
 *    cannot say which. Re-deriving any of it at a call site is how two answers drift apart, so
 *    this parameter is the gate's answer, carried — under the same name, end to end.
 *  - **`declinedTiers`** is `NpuTierStatus.declinedTiers` — the tiers whose own
 *    `NpuWhisperBackend` has already fallen back **in this process**, published from the
 *    backend's own setter under its spec's tier id. Per-tier since L8 for the reason that names
 *    the whole task: a turbo decline banning `npu` for the rest of the process is the worst
 *    possible coupling in a lab whose purpose is A/B-ing the two. It stays a separate input
 *    rather than a conjunct folded into the offer set because it answers a different question:
 *    the offer set is about the *device*, this is about a *measurement someone already took on
 *    it* — and folding them would quietly invent a second composition of the offer gate, which
 *    Q7b's KDoc forbids by name. Nothing clears a membership short of an app restart, by design
 *    — see `NpuTierStatus`'s KDoc for why a `releaseEverything()` clear was rejected.
 *
 * ### Testing
 *
 * The [backendFor] that takes a lambda is the JVM-testable form and it exists for one reason:
 * **no unit test may name `NpuWhisperBackend`** (its `QnnAsrNative` reference runs
 * `System.loadLibrary("qnnasr")`, and there is no `libqnnasr.so` on the test classpath), so a test
 * that asserted on the concrete type would be asserting by being killed. The table is therefore
 * executed against a stand-in backend, and the *production* overload's one construction call is
 * pinned as source text by `NpuBackendWiringTest` — the same split, for the same reason, that
 * `NpuDiag` uses for its format strings.
 */
object NpuBackendSelector {

    /**
     * Does this session run on the NPU? The whole truth table, in one expression.
     *
     * The routing set is **exactly the spec table's tier ids** — `{npu, npu-turbo}` today — via
     * [NpuModelSpec.forTier], never a literal of this class's own. Two ids are safe here
     * **because** the arming epoch (4.1 L1) makes a stale instance's queued `nativeRelease` a
     * no-op and a stale instance's `transcribe` a refusal, **and** because the service's rebuild
     * guard compares routed tier IDS rather than a Boolean; a third id is a new spec row plus a
     * deliberate re-spec of `NpuBackendWiringTest`'s routing census, not a widening.
     *
     * @param tierId `PreferencesManager.selectedModelId`.
     * @param offeredNpuTierIds `WhisperEverywhereApp.offeredNpuTierIds()` — capability AND that
     *        tier's own pair on disk, per tier.
     * @param declinedTiers `NpuTierStatus.declinedTiers` — tiers that already declined **in this
     *        process**. A member routes to the CPU tier however open the gate: re-arming a tier
     *        that just refused costs a model-sized load to reach the same answer.
     */
    fun routesToNpu(
        tierId: String?,
        offeredNpuTierIds: Set<String>,
        declinedTiers: Set<String>,
    ): Boolean =
        NpuModelSpec.forTier(tierId) != null &&
            tierId in offeredNpuTierIds &&
            tierId !in declinedTiers

    /**
     * [routesToNpu], resolved to the backend itself. [npuBackend] is invoked **only** on the npu
     * arm, so the CPU path never constructs anything.
     *
     * **The spec is resolved here and handed in (4.1 L2).** `NpuWhisperBackend` takes an
     * [NpuModelSpec] with no default, so a tier id the table has no row for cannot construct one —
     * and this function answers `WhisperNativeBackend` for it, which is the same answer it gives a
     * closed gate. That is a second, structural reason the routing decision lives in one place: the
     * predicate says *may* this tier run on the NPU, and the table says *which model* it would be.
     * Since L8 the predicate's own first clause IS the table membership, so the `?:` arm below is
     * belt-and-braces rather than a reachable branch — kept because a predicate edit must never be
     * able to hand this resolver an id it cannot resolve.
     */
    internal fun backendFor(
        tierId: String?,
        offeredNpuTierIds: Set<String>,
        declinedTiers: Set<String>,
        paths: ModelPathProvider,
        npuBackend: (ModelPathProvider, NpuModelSpec) -> WhisperBackend,
    ): WhisperBackend {
        if (!routesToNpu(tierId, offeredNpuTierIds, declinedTiers)) return WhisperNativeBackend
        val spec = NpuModelSpec.forTier(tierId) ?: return WhisperNativeBackend
        return npuBackend(paths, spec)
    }

    /**
     * The production form.
     *
     * `paths` is not optional and never was (Q9 brief, NEW-I1): `NpuWhisperBackend` resolves its
     * own companion artefact, its mel donor and its CPU fallback through a [ModelPathProvider], so
     * a selector that did not take one could not construct what it returns. `appContext` joins it
     * for the same structural reason — the tier reads its vocabulary asset out of the APK and its
     * `nativeLibraryDir` is the QNN backend search path.
     */
    fun backendFor(
        tierId: String?,
        offeredNpuTierIds: Set<String>,
        declinedTiers: Set<String>,
        paths: ModelPathProvider,
        appContext: Context,
    ): WhisperBackend =
        backendFor(tierId, offeredNpuTierIds, declinedTiers, paths) { p, spec ->
            NpuWhisperBackend(p, appContext, spec)
        }
}
