package com.whispereverywhere.transcription

import android.content.Context
import com.whispereverywhere.npu.NpuAssetImport

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
     * @param declinedThisSession whether an NPU stage has already declined in this process, i.e.
     *        `NpuTierStatus.unavailableReason != null`. True means the CPU tier, however open the
     *        gate: re-arming a tier that just refused costs a 342 MiB load to reach the same answer.
     */
    fun routesToNpu(tierId: String?, npuAvailable: Boolean, declinedThisSession: Boolean): Boolean =
        tierId == NpuAssetImport.TIER_ID && npuAvailable && !declinedThisSession

    /**
     * [routesToNpu], resolved to the backend itself. [npuBackend] is invoked **only** on the npu
     * arm, so the CPU path never constructs anything.
     */
    internal fun backendFor(
        tierId: String?,
        npuAvailable: Boolean,
        declinedThisSession: Boolean,
        paths: ModelPathProvider,
        npuBackend: (ModelPathProvider) -> WhisperBackend,
    ): WhisperBackend =
        if (routesToNpu(tierId, npuAvailable, declinedThisSession)) npuBackend(paths)
        else WhisperNativeBackend

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
        backendFor(tierId, npuAvailable, declinedThisSession, paths) { NpuWhisperBackend(it, appContext) }
}
