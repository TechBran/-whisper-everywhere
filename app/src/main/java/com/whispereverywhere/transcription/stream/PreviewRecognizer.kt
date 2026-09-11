package com.whispereverywhere.transcription.stream

import java.io.File

/**
 * THE SEAM over sherpa-onnx's `OnlineRecognizer` / `OnlineStream` — the five calls the previewer
 * makes, and nothing else. Exists so the engine's loop, the canary and the tee are JVM-testable
 * with `ScriptedRecognizer`: the AAR's classes load `libsherpa-onnx-jni.so` in a static
 * initialiser, so no test may reference `com.k2fsa` (TtsEngineSeamTest's rule). The production
 * adapter is `SherpaPreviewRecognizer` (Task 7), the only file in the app that imports the AAR's
 * streaming classes.
 */
interface PreviewStream {
    /** Float32 samples in [-1, 1] at 16 kHz (AudioMath.pcm16ToFloat's output). */
    fun acceptWaveform(samples: FloatArray)
    fun inputFinished()
    fun release()
}

/**
 * `getResult()` — the CUMULATIVE text of the open stream, its tokens (each with the AAR's
 * LEADING SPACE, rung 3 §2.2) and per-token timestamps in seconds from the stream's start.
 */
class PreviewResult(val text: String, val tokens: List<String>, val timestamps: FloatArray) {
    companion object {
        val EMPTY = PreviewResult("", emptyList(), FloatArray(0))
    }
}

interface PreviewRecognizer {
    fun createStream(): PreviewStream
    fun isReady(stream: PreviewStream): Boolean
    fun decode(stream: PreviewStream)
    fun result(stream: PreviewStream): PreviewResult
    fun release()
}

interface PreviewRecognizerFactory {
    /** Loads the recognizer over the four files in [dir]; throws when the files are refused. */
    fun load(dir: File, pack: StreamingPack, numThreads: Int): PreviewRecognizer
    fun sherpaVersion(): String
    fun ortVersion(): String
}
