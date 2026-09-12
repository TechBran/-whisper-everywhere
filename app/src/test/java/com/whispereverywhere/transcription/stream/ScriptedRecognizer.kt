package com.whispereverywhere.transcription.stream

/**
 * A scripted stand-in for the sherpa `OnlineRecognizer`, shaped by the measurements: the FIRST
 * decode needs `T = 45` frames (7,200 samples, encoder metadata `T=45`, rung 3 §7) and every
 * later one 320 ms (5,120 samples, `decode_chunk_len=32`) — so 9 of every 10 32 ms feeds leave
 * `isReady` false, exactly as measured (rung 1 §3.1). [texts] is the cumulative text after the
 * k-th decode (1-based; the last entry repeats); tokens carry the AAR's LEADING SPACE, never
 * U+2581 (rung 3 §2.2), and [timestampsOf] supplies the per-token seconds for the trim tests.
 * NEVER references com.k2fsa: the AAR's static init loads a native library.
 */
class ScriptedRecognizer(
    private val texts: List<String>,
    private val timestampsOf: (String) -> FloatArray = { t -> FloatArray(tokensOf(t).size) { i -> 0.32f * (i + 1) } },
    private val failDecodesFrom: Int = Int.MAX_VALUE,   // every decode whose 1-based global index >= this throws
    private val canaryText: String? = null,             // when set, the FIRST stream ever created answers this text (the canary's)
    // (4.5.0 T3) The tokens are a SEPARATE answer from the text, because on a CJK-classified pack
    // the AAR's two answers are different strings: the tokens carry a space per word and
    // `RemoveSpaceBetweenCjk` takes those spaces out of the text. The default derives one from
    // the other — right for every Latin/Cyrillic pack — and the Korean tests override it, which
    // is the only way a test can tell which of the two the reader under test actually read.
    private val tokensFor: (String) -> List<String> = { t -> tokensOf(t) },
) : PreviewRecognizer {

    class Stream : PreviewStream {
        var samples = 0L
        var consumed = 0L
        var decodes = 0
        var finished = false
        var released = false
        val fed = mutableListOf<Int>()               // per-call sample counts, in order
        var fedAtFinish = -1                         // fed.size WHEN inputFinished() ran; -1 = never ran. Pins feed/finish ORDER
        override fun acceptWaveform(samples: FloatArray) { this.samples += samples.size; fed += samples.size }
        override fun inputFinished() { finished = true; fedAtFinish = fed.size }
        override fun release() { released = true }
    }

    val streams = mutableListOf<Stream>()
    var totalDecodes = 0
    var released = false

    override fun createStream(): PreviewStream = Stream().also { streams += it }

    override fun isReady(stream: PreviewStream): Boolean {
        val s = stream as Stream
        if (s.released) throw IllegalStateException("released stream")
        val need = if (s.decodes == 0) FIRST_DECODE_SAMPLES else CHUNK_SHIFT_SAMPLES
        return s.samples - s.consumed >= need
    }

    override fun decode(stream: PreviewStream) {
        val s = stream as Stream
        val need = if (s.decodes == 0) FIRST_DECODE_SAMPLES else CHUNK_SHIFT_SAMPLES
        s.consumed += need
        s.decodes++
        totalDecodes++
        if (totalDecodes >= failDecodesFrom) throw IllegalStateException("scripted decode failure")
    }

    override fun result(stream: PreviewStream): PreviewResult {
        val s = stream as Stream
        if (s.decodes == 0) return PreviewResult.EMPTY
        // NOTHING may remove from [streams]: this identifies the first stream STILL IN THE LIST.
        if (canaryText != null && s === streams.firstOrNull()) {
            return PreviewResult(canaryText, tokensFor(canaryText), timestampsOf(canaryText))
        }
        if (texts.isEmpty()) return PreviewResult.EMPTY
        val text = texts[minOf(s.decodes, texts.size) - 1]
        return PreviewResult(text, tokensFor(text), timestampsOf(text))
    }

    override fun release() { released = true }

    companion object {
        const val FIRST_DECODE_SAMPLES = 7_200L
        const val CHUNK_SHIFT_SAMPLES = 5_120L
        /** The AAR's shape: each word piece arrives with a LEADING SPACE. */
        fun tokensOf(text: String): List<String> = text.split(' ').filter { it.isNotEmpty() }.map { " $it" }
    }
}

/**
 * A factory over one scripted recognizer, or one that throws at load. [next] is returned from the
 * SECOND load on, so a two-pack sequence gets two distinguishable recognizers — without it a
 * language switch would hand back the same object and no test could tell a reload from a
 * short-circuit. [packs] records what each load was asked for, in order, which is the pack
 * identity itself under test.
 */
class ScriptedFactory(
    private val recognizer: PreviewRecognizer?,
    private val throwAtLoad: Boolean = false,
    private val next: PreviewRecognizer? = null,
) : PreviewRecognizerFactory {
    var loads = 0
    var lastThreads = -1
    val packs = mutableListOf<StreamingPack>()
    override fun load(dir: java.io.File, pack: StreamingPack, numThreads: Int): PreviewRecognizer {
        loads++
        lastThreads = numThreads
        packs += pack
        if (throwAtLoad) throw IllegalStateException("scripted load failure")
        return if (loads > 1 && next != null) next else requireNotNull(recognizer)
    }
    override fun sherpaVersion(): String = "1.13.7"
    override fun ortVersion(): String = "1.27.1"
}
