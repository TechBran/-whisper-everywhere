package com.whispereverywhere.audio

import com.whispereverywhere.util.SpeechSegmenter

/**
 * The 3.6.0 amplitude segmenter, wearing the 3.7 [Endpointer] interface and nothing more
 * (3.7, Task D2 — the fallback tier 1 that `EndpointerFactory` selects in Task D8).
 *
 * This is the fallback the service constructs whenever `VadModel.path()` returns null — the
 * existing "running without VAD" path, which already logs and already degrades gracefully. It
 * ignores [onFrame]'s `chunk`, overrides none of the interface's defaulted extension points, and
 * therefore offers no micro-pause cut point: a full session on this endpointer is byte-identical
 * to 3.6.0, which is the property the regression suite pins by running unchanged.
 */
class AmplitudeEndpointer(
    private val segmenter: SpeechSegmenter = SpeechSegmenter(),
) : Endpointer {

    override fun onFrame(chunk: ByteArray, amp: Int, nowMs: Long): Boolean =
        segmenter.onAmplitude(amp, nowMs)

    override fun hasPendingSpeech(): Boolean = segmenter.hasPendingSpeech()

    override fun reset() = segmenter.reset()
}
