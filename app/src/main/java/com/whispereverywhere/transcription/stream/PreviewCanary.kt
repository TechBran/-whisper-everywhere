package com.whispereverywhere.transcription.stream

import com.whispereverywhere.transcription.GpuCanaryPolicy

/** The canary's answer; `code` is what the `stream-open:` line prints. */
sealed class CanaryVerdict {
    abstract val code: String
    data class Pass(val outLen: Int, val decodes: Int) : CanaryVerdict() { override val code: String get() = "pass" }
    data class Fail(val outLen: Int, val decodes: Int) : CanaryVerdict() { override val code: String get() = "fail" }
    object NoClip : CanaryVerdict() { override val code: String get() = "none" }
}

/**
 * The load-time canary (spec §7.2) — RULING ASSUMED (R1): the ONLY guard against the FEAT_SME
 * silent-miscompute class. sherpa-onnx #3845 (SM8850, ORT 1.27.0): EMPTY text for the whole
 * stream, no crash, no NaN; #3791 (M4): `"MY WOMAN"` for a five-word clip. Neither test device
 * has SME (rung 3 §1.1), so on the devices we own this always passes — the rule exists for the
 * `8elite5_galaxy` census family (NpuFleetCensus.kt:142-145) we cannot test.
 *
 * Feeds the bundled `canary_digits.wav` (CanaryAudio.samples(), the GPU canary's own reader:
 * 2.560 s of "one two three four five") in the app's 512-sample chunks, pads PAD_MS, finishes,
 * drains, and scores with `GpuCanaryPolicy.canaryPasses` — unchanged, because it already
 * answers the three signatures (empty / garbage / runaway) and passes the measured
 * `ONE TWO THREE FOUR FIVE`. A null clip is NO VERDICT, not a failure — and, unlike the GPU
 * canary, nothing permanent hangs on it: the engine treats "none" as off for this process.
 *
 * The verdict is never persisted (no preference, no per-(versionCode, pack) latch): the tee is
 * additive, so a false negative costs one process of blank strips and nothing typed.
 */
object PreviewCanary {

    fun run(recognizer: PreviewRecognizer, clip: FloatArray?): CanaryVerdict {
        if (clip == null || clip.isEmpty()) return CanaryVerdict.NoClip
        val stream = recognizer.createStream()
        var decodes = 0
        try {
            var i = 0
            while (i < clip.size) {
                val n = minOf(StreamingPreviewTuning.CHUNK_SAMPLES, clip.size - i)
                stream.acceptWaveform(clip.copyOfRange(i, i + n))
                while (recognizer.isReady(stream)) {
                    recognizer.decode(stream)
                    decodes++
                }
                i += n
            }
            stream.acceptWaveform(FloatArray(StreamingPreviewTuning.padSamples()))
            stream.inputFinished()
            while (recognizer.isReady(stream)) {
                recognizer.decode(stream)
                decodes++
            }
            val text = recognizer.result(stream).text
            return if (passes(text)) CanaryVerdict.Pass(text.length, decodes) else CanaryVerdict.Fail(text.length, decodes)
        } finally {
            stream.release()
        }
    }

    /** The GPU canary's rule, verbatim (GpuCanaryPolicy.canaryPasses): its normaliser lowercases, so ALL CAPS passes. */
    fun passes(text: String): Boolean = GpuCanaryPolicy.canaryPasses(text)
}
