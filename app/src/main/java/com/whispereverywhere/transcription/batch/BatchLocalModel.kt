package com.whispereverywhere.transcription.batch

import com.whispereverywhere.model.WhisperCatalog
import com.whispereverywhere.model.WhisperModel

/**
 * Which file a **whisper.cpp** batch job loads for the selected tier, and what to say when there
 * isn't one (4.0, Q9 fix round, I1).
 *
 * ### The defect this exists to close
 *
 * `BatchTranscriptionService` constructs `BatchTranscriber(backend = WhisperNativeBackend, …)` —
 * hard-wired to whisper.cpp, and correctly so: a file job has no VAD window to honour, the NPU
 * tier's `useVad` is ignored and its window is a fixed 30 s, so routing batch work to the Hexagon
 * is a decision nobody has taken. But `loadCtx` then asked [ModelPathProvider.installedModelPath],
 * which since 4.0 does not necessarily answer with a ggml: for `npu` it answers with
 * `encoder_qairt_context.bin`, a QAIRT context binary. `WhisperNative.init` refuses it by magic
 * number and returns `0`, and the user gets a failed file transcription with nothing naming the
 * cause. Live since Q8's `select()` gave the tier a path into `selectedModelId`.
 *
 * ### The gate is `pairedArtifact`, and an unconditional substitution would have been a regression
 *
 * The obvious fix — "always load `cpuTierModelPath()` for batch" — is wrong, and quietly. That
 * provider deliberately **excludes `ultra`** from donor eligibility (`large-v3-turbo` is 128-bin and
 * cannot serve as a mel filterbank), so an `ultra` user's file job, which works today and loads the
 * model they chose, would silently start running on `small` instead. Nobody would see it: same
 * screen, same progress, different transcript.
 *
 * So the substitution is gated on the selected tier actually being one whisper.cpp cannot load, and
 * the structural test for that is [WhisperModel.pairedArtifact] — the same predicate
 * `isInstallableByDownload` uses, and for the same reason: it tracks the thing that actually breaks
 * rather than a list of ids, so the next two-artefact tier is handled by a rule nobody has to
 * remember to update. Every one-file ggml tier — `pro`, `multi`, `eco`, `base`, `ultra` — keeps the
 * path it has always been given, byte for byte.
 *
 * ### Null is a refusal that names the tier, never a silent wrong model
 *
 * `cpuTierModelPath()` can be null: a device with the 358 MB pair imported and no ggml installed at
 * all. That case must fail, and it must not fail with `loadCtx`'s existing *"No on-device model
 * installed"* — which would be a lie to a user staring at an installed model. [refusal] is that
 * sentence, and it names the tier and the way out.
 *
 * Pure, so the whole truth table is executed by `BatchLocalModelTest` against the **shipped
 * catalog** rather than a fixture — which is what makes "`ultra` is untouched" a fact about the app
 * instead of a fact about the test.
 */
object BatchLocalModel {

    /**
     * The path whisper.cpp should load, or null when there is none.
     *
     * @param tierId `ModelPathProvider.selectedTierId()`.
     * @param installedPath `ModelPathProvider.installedModelPath()` — the selected tier's own file.
     * @param ggmlSubstitutePath `ModelPathProvider.cpuTierModelPath()` — an installed 80-bin ggml,
     *        consulted **only** for a tier whisper.cpp cannot load.
     */
    fun pathFor(tierId: String?, installedPath: String?, ggmlSubstitutePath: String?): String? =
        if (needsGgmlSubstitute(tierId)) ggmlSubstitutePath else installedPath

    /**
     * Is the selected tier one whisper.cpp cannot load at all? Structural (`pairedArtifact`), never
     * a list of ids — see the class KDoc.
     */
    fun needsGgmlSubstitute(tierId: String?): Boolean =
        WhisperCatalog.byId(tierId)?.pairedArtifact != null

    /**
     * The message a failed [pathFor] carries.
     *
     * Two sentences, because the two causes need two different next actions. For a paired tier the
     * user HAS a model installed and the app still cannot use it here, so saying "no model
     * installed" would be false; the tier is named, the reason is named, and the fix is named.
     * Everything else keeps the string this path has always thrown, byte for byte.
     */
    fun refusal(tierId: String?): String {
        val model: WhisperModel? = WhisperCatalog.byId(tierId)
        return if (model?.pairedArtifact != null) {
            "\"${model.displayName}\" runs on the AI chip and cannot transcribe files. Install a " +
                "standard on-device model (Multilingual or Pro) to transcribe recordings."
        } else {
            NO_LOCAL_MODEL
        }
    }

    /** The pre-4.0 refusal, unchanged — every one-file tier still fails exactly as it used to. */
    const val NO_LOCAL_MODEL: String = "No on-device model installed — cannot transcribe locally"
}
