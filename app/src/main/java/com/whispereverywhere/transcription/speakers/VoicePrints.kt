package com.whispereverywhere.transcription.speakers

/**
 * A slice of audio in, a voice fingerprint out — the ONE seam between the speaker pipeline's
 * decisions and the library that computes them (4.10 Task 3).
 *
 * [SpeakerEmbedder] is the production implementation and the only file in the app that names
 * sherpa's embedding extractor at all. This interface exists so that [SpeakerAssigner] — which
 * decides WHICH samples are fingerprinted, what happens when one cannot be, and how the answers
 * become speaker numbers — is reachable from a JVM unit test at all: sherpa's extractor loads
 * `libsherpa-onnx-jni.so` in its companion initialiser, which is not on the unit-test classpath,
 * and `SpeakerEmbedderPinTest` asserts that no test so much as references the adapter.
 *
 * The seam is deliberately two methods and no state. Everything that could be decided here —
 * a minimum length, a cache, a retry, a fallback model — is decided one level up, where it is
 * testable.
 */
interface VoicePrints {

    /**
     * Fingerprints [pcm]: mono float samples in [-1, 1] at [sampleRate], one VAD speech segment
     * taken from the ORIGINAL chunk timeline.
     *
     * Returns null for EVERY failure — no model, a refused load, a stream that never became
     * ready, anything thrown from native code. Null is a first-class answer, not an error: the
     * caller's contract is that a session which cannot fingerprint loses LABELS and never text.
     */
    fun embed(pcm: FloatArray, sampleRate: Int = 16_000): FloatArray?

    /** Frees whatever the implementation holds resident. Must be safe to call twice. */
    fun release()
}
