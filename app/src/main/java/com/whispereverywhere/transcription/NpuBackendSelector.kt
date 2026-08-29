package com.whispereverywhere.transcription

import android.content.Context
import com.whispereverywhere.npu.NpuAssetImport
import com.whispereverywhere.npu.NpuModelSpec

/**
 * Which [WhisperBackend] a session runs on — the 4.0 NPU tier's routing decision, as a truth table
 * (Q9).
 *
 * ### Why this is a selector and not a factory
 *
 * There is **no backend factory in this app** and this class does not become one. The backend is a
 * *constructor default* on [LocalWhisperEngine] (`backend: WhisperBackend = WhisperNativeBackend`),
 * and the engine is **cached** for the whole life of `FloatingBubbleService`. So "route the npu
 * tier" is not a dispatch at call time; it is a decision taken **once, at engine construction**,
 * and changing it means building a different engine. That is why the only two members here are a
 * predicate and a constructor call: everything about *when* to ask is the service's, and the
 * mechanism by which an answer takes effect is the service rebuilding its cached engine.
 *
 * ### Why the decision lives in ONE predicate
 *
 * [routesToNpu] has two readers with two different needs — [backendFor] builds the object, and the
 * service asks the same question to decide whether the engine it already has is still the right
 * one. Deriving that second answer separately would be the same truth table written twice, and the
 * two copies would be free to disagree: the engine would be rebuilt on a decision the selector does
 * not make, or kept on one it no longer makes. One predicate, two callers.
 *
 * ### The three inputs, and why the third one exists
 *
 *  - **`tierId`** is `PreferencesManager.selectedModelId` — the id the model chooser writes, which
 *    can be the npu id since Q8's D-2.
 *  - **`npuAvailable`** is `WhisperEverywhereApp.isNpuTierOffered()` and **nothing else**: the
 *    memoised capability probe AND both context binaries on disk. Re-deriving either half at a call
 *    site is how the two answers drift apart, so this parameter is the gate's answer, carried.
 *  - **`declinedThisSession`** is whether `NpuWhisperBackend` has already fallen back — read from
 *    `NpuTierStatus.unavailableReason`, the process-scoped mirror of the property the backend
 *    publishes from its own setter. It is a separate input rather than a conjunct folded into
 *    `npuAvailable` because it answers a different question: `npuAvailable` is about the *device*,
 *    this is about a *measurement someone already took on it*. Folding them would also quietly
 *    invent a second composition of the offer gate, which Q7b's KDoc forbids by name.
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
     * The id is [NpuAssetImport.TIER_ID] and **not a literal of this class's own**. That constant
     * is already the app's single answer to "which tier is the npu one" — `WhisperModelManager`
     * and the Settings picker both read it from there — and a second bare literal spelled out here
     * would be a second source of truth for a string whose typo routes silently to the CPU backend
     * with nothing anywhere to see.
     *
     * @param tierId `PreferencesManager.selectedModelId`.
     * @param npuAvailable `WhisperEverywhereApp.isNpuTierOffered()` — capability AND installed.
     * @param declinedThisSession whether an NPU stage has already declined **in this process** —
     *        `NpuTierStatus.unavailableReason != null`. True means the CPU tier, however open the
     *        gate: re-arming a tier that just refused costs a 342 MiB load to reach the same answer.
     *        **The name says "session" and the scope is the PROCESS**, and the parameter keeps the
     *        name only because renaming it would touch every pin on this file for no behavioural
     *        gain; the lifetime is stated in `NpuTierStatus`'s KDoc and now in the card copy too
     *        (final review F3). Nothing clears it short of an app restart, by design — see that
     *        KDoc for why a `releaseEverything()` clear was rejected.
     */
    fun routesToNpu(tierId: String?, npuAvailable: Boolean, declinedThisSession: Boolean): Boolean =
        tierId == NpuAssetImport.TIER_ID && npuAvailable && !declinedThisSession

    /**
     * [routesToNpu], resolved to the backend itself. [npuBackend] is invoked **only** on the npu
     * arm, so the CPU path never constructs anything.
     *
     * **The spec is resolved here and handed in (4.1 L2).** `NpuWhisperBackend` takes an
     * [NpuModelSpec] with no default, so a tier id the table has no row for cannot construct one —
     * and this function answers `WhisperNativeBackend` for it, which is the same answer it gives a
     * closed gate. That is a second, structural reason the routing decision lives in one place: the
     * predicate says *may* this tier run on the NPU, and the table says *which model* it would be.
     * A tier that passed the first and failed the second used to be unrepresentable because there
     * was one model; it is representable now, and it is a CPU session rather than a wrong census.
     */
    internal fun backendFor(
        tierId: String?,
        npuAvailable: Boolean,
        declinedThisSession: Boolean,
        paths: ModelPathProvider,
        npuBackend: (ModelPathProvider, NpuModelSpec) -> WhisperBackend,
    ): WhisperBackend {
        if (!routesToNpu(tierId, npuAvailable, declinedThisSession)) return WhisperNativeBackend
        val spec = NpuModelSpec.forTier(tierId) ?: return WhisperNativeBackend
        return npuBackend(paths, spec)
    }

    /**
     * The production form.
     *
     * `paths` is not optional and never was (Q9 brief, NEW-I1): `NpuWhisperBackend` resolves its
     * own companion artefact, its mel donor and its CPU fallback through a [ModelPathProvider], so
     * a selector that did not take one could not construct what it returns. `appContext` joins it
     * for the same structural reason — the tier reads its 563 KB vocabulary out of the APK's assets
     * and its `nativeLibraryDir` is the QNN backend search path.
     */
    fun backendFor(
        tierId: String?,
        npuAvailable: Boolean,
        declinedThisSession: Boolean,
        paths: ModelPathProvider,
        appContext: Context,
    ): WhisperBackend =
        backendFor(tierId, npuAvailable, declinedThisSession, paths) { p, spec ->
            NpuWhisperBackend(p, appContext, spec)
        }
}
