package com.whispereverywhere.whisper

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.whispereverywhere.model.WhisperCatalog
import com.whispereverywhere.transcription.WhisperNativeBackend
import com.whispereverywhere.util.AudioMath
import com.whispereverywhere.util.WerMath
import org.junit.After
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Release A Task 6 GATE: the first MEASURED (not modelled) numbers for on-device whisper.cpp
 * transcription latency. Every local-vs-cloud latency claim in the spec so far has been
 * modelled, and `jfk.wav` has sat unused in androidTest/assets since the repo's first commit.
 * The TTS side was measured on 2026-07-27 and overturned five conclusions, including one wrong
 * figure that had already reached the spec. This is the STT side's turn.
 *
 * Run it against whatever model(s) the app already has downloaded on THIS device (installing
 * a model is done in-app; this test does not sideload one):
 *   adb install -r <buildDir>/app/outputs/apk/debug/app-debug.apk
 *   adb install -r <buildDir>/app/outputs/apk/androidTest/debug/app-debug-androidTest.apk
 *   adb shell am instrument -w -e class com.whispereverywhere.whisper.WhisperBenchTest \
 *     com.whispereverywhere.test/androidx.test.runner.AndroidJUnitRunner
 * `-r` reinstall does not touch app data, so previously-downloaded models survive and are what
 * gets benched. If nothing is installed, the test SKIPS (via assumeTrue) rather than failing.
 *
 * Goes through [WhisperNativeBackend] -- the SAME seam [com.whispereverywhere.transcription.
 * LocalWhisperEngine] uses in production (VAD trimming + GpuPolicy's CPU/GPU decision included)
 * -- not the raw JNI layer, so the RTF measured here is what a real dictation session actually
 * pays, not an optimistic lower bound.
 *
 * Results go to logcat tag WE-BENCH: grep "BENCH stt" for the per-slice lines, or "summary" for
 * the p50/p95/max rollup per tier.
 */
@RunWith(AndroidJUnit4::class)
class WhisperBenchTest {

    private companion object {
        const val TAG = "WE-BENCH"
        const val SAMPLE_RATE = 16000

        /**
         * Slice durations to bench, in whole seconds. jfk.wav is 11 s of continuous speech (no
         * leading/trailing silence), shorter than the largest slice -- see [tileToDuration].
         */
        val SLICE_SECONDS = listOf(1, 3, 8, 15)

        /**
         * The production audio_ctx floor (whisper_jni.cpp g_audio_ctx_floor default). If Task G4
         * ever lowers the native default, bump THIS in the same commit — the floor sweep uses it
         * as the A-B reference arm AND restores it in its finally.
         */
        const val PRODUCTION_FLOOR = 768

        /** Candidate floors below production; each is A-B'd against [PRODUCTION_FLOOR]. */
        val FLOOR_CANDIDATES = listOf(512, 384, 256)

        /**
         * Tail-fragment-sized slices for the floor sweep, in whole seconds.
         *
         * A slice MEASURES a candidate floor only when the floor is the BINDING term. whisper_jni
         * computes `neededFrames = samples/320 + 64` and then raises it to the floor, so the floor
         * binds only while `floor > neededFrames` — strictly greater, since equality changes
         * nothing. Per slice, neededFrames is:
         *   1 s -> 114, 2 s -> 164, 3 s -> 214, 8 s -> 464 frames.
         * So 1/2/3 s bind every candidate, and the 8 s slice binds 512 ONLY: at floors 384 and 256
         * the encoder runs at its natural 464 frames — not at the candidate — which is a
         * structural no-op that must never be read as "the low floor was safe". Every result line
         * therefore carries binding=true|false and only binding slices feed the verdict; see
         * [floorBinds]. 15 s (neededFrames 814) escapes every floor and would measure nothing.
         */
        val FLOOR_SLICE_SECONDS = listOf(1, 2, 3, 8)
    }

    @Test
    fun bench_whisper_rtf_across_slices() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        val modelsDir = File(context.filesDir, "models")

        // "Installed" mirrors WhisperModelManager.isInstalled: file present on disk, size within
        // the catalog's tolerance. Bench whichever tier(s) the USER actually has -- there is no
        // sideload arg here, exactly as the run command above shows.
        val installed = WhisperCatalog.entries.filter { model ->
            val f = File(modelsDir, model.fileName)
            f.exists() && WhisperCatalog.sizeWithinTolerance(f.length(), model.approxBytes)
        }
        assumeTrue(
            "No installed whisper model found under ${modelsDir.absolutePath}; " +
                "download one in-app first, then rerun this test",
            installed.isNotEmpty(),
        )

        // Bundled jfk.wav (androidTest asset) -- already 16 kHz mono PCM16, whisper's native rate.
        val wavBytes = inst.context.assets.open("jfk.wav").use { it.readBytes() }
        val pcm = wavDataChunk(wavBytes)
        assertTrue("jfk.wav decoded to no PCM samples", pcm.isNotEmpty())

        for (model in installed) {
            benchTier(model.id, File(modelsDir, model.fileName).absolutePath, pcm)
        }
    }

    /**
     * Workstream G (3.6.0): A-B the production audio_ctx floor against lower candidates, per
     * installed tier, with accuracy scoring. The stop-tail fragment pays a 768-frame minimum for
     * ~119 frames of audio; the floor was raised 256 -> 768 for a REAL accuracy regression, so
     * "no candidate qualifies" is a fully expected, shippable answer.
     *
     * MEASUREMENT, not a gate: the only assertions are that the swept floors are inside the
     * native clamp, that loads succeed, and that the reference arm transcribes non-blank.
     * Accuracy = WerMath.wer(same tier, same slice, reference floor vs candidate floor); the
     * per-floor verdict line applies the SAME WerMath.floorQualifies rule Task G4 is gated on, to
     * the BINDING slices only — see [FLOOR_SLICE_SECONDS] and [floorBinds]. The owner records the
     * lines in docs/superpowers/specs/2026-08-19-audio-ctx-floor-bench.md.
     */
    @Test
    fun bench_audio_ctx_floor_ab() {
        // CLAMP DESYNC TRAP: setAudioCtxFloor clamps to 64..1500 NATIVELY and reports nothing
        // back, so a floor outside that range would RUN at the clamped value while every log line
        // -- and the record built from those lines -- still named the requested one: a verdict
        // recorded against a floor that never executed. Asserting requested == effective is the
        // cheap half of the fix (the alternative is echoing the clamped value back across JNI).
        assertTrue(
            "every swept floor must sit inside whisper_jni's 64..1500 clamp, or the logged " +
                "floor is not the floor that ran",
            (FLOOR_CANDIDATES + PRODUCTION_FLOOR).all { it in 64..1500 },
        )

        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        val modelsDir = File(context.filesDir, "models")
        val installed = WhisperCatalog.entries.filter { model ->
            val f = File(modelsDir, model.fileName)
            f.exists() && WhisperCatalog.sizeWithinTolerance(f.length(), model.approxBytes)
        }
        assumeTrue(
            "No installed whisper model found under ${modelsDir.absolutePath}; " +
                "download one in-app first, then rerun this test",
            installed.isNotEmpty(),
        )
        val wavBytes = inst.context.assets.open("jfk.wav").use { it.readBytes() }
        val pcm = wavDataChunk(wavBytes)
        assertTrue("jfk.wav decoded to no PCM samples", pcm.isNotEmpty())

        for (model in installed) {
            benchFloorsForTier(model.id, File(modelsDir, model.fileName).absolutePath, pcm)
        }
    }

    private fun benchFloorsForTier(tier: String, modelPath: String, pcm: ByteArray) {
        val ctx = WhisperNativeBackend.load(modelPath)
        assertNotEquals("model load returned 0 (failed) for tier=$tier at $modelPath", 0L, ctx)
        try {
            // STRICTLY SEQUENTIAL set-then-transcribe, and it must stay that way: the floor is one
            // process-global int read ONCE at the start of each native transcribe, so a
            // parallelised sweep would have both arms racing that single variable and would score
            // whichever floor happened to be set last. Never run these arms concurrently -- and
            // nothing else on the device may transcribe while this runs (see the record file).
            //
            // Reference arm FIRST, at the production floor: its text is the "A" of every A-B.
            val refTexts = HashMap<Int, String>()
            com.whispereverywhere.whisper.WhisperNative.setAudioCtxFloor(PRODUCTION_FLOOR)
            for (seconds in FLOOR_SLICE_SECONDS) {
                val samples = AudioMath.pcm16ToFloat(tileToDuration(pcm, seconds, SAMPLE_RATE))
                val w0 = System.currentTimeMillis()
                val text = WhisperNativeBackend.transcribe(ctx, samples, "en")
                val wallMs = System.currentTimeMillis() - w0
                assertTrue(
                    "tier=$tier floor=$PRODUCTION_FLOOR slice=${seconds}s produced a blank " +
                        "reference — cannot score candidates against nothing",
                    text.isNotBlank(),
                )
                refTexts[seconds] = text
                android.util.Log.i(
                    TAG,
                    "BENCH audioctx tier=$tier floor=$PRODUCTION_FLOOR slice=${seconds}s " +
                        "wallMs=$wallMs wer=0.000 " +
                        "binding=${floorBinds(samples.size, PRODUCTION_FLOOR)} (reference)",
                )
            }
            for (floor in FLOOR_CANDIDATES) {
                com.whispereverywhere.whisper.WhisperNative.setAudioCtxFloor(floor)
                // ONLY binding slices are evidence about THIS floor; a non-binding slice ran at
                // its natural neededFrames, i.e. it never exercised the candidate at all.
                val bindingWers = mutableListOf<Double>()
                for (seconds in FLOOR_SLICE_SECONDS) {
                    val samples = AudioMath.pcm16ToFloat(tileToDuration(pcm, seconds, SAMPLE_RATE))
                    val binding = floorBinds(samples.size, floor)
                    val w0 = System.currentTimeMillis()
                    val text = WhisperNativeBackend.transcribe(ctx, samples, "en")
                    val wallMs = System.currentTimeMillis() - w0
                    val wer = WerMath.wer(refTexts.getValue(seconds), text)
                    if (binding) bindingWers += wer
                    // Locale.US: G4's gate and the decision record PARSE these numbers, and a
                    // comma-decimal device locale would emit wer=0,123 and break both.
                    android.util.Log.i(
                        TAG,
                        String.format(
                            java.util.Locale.US,
                            "BENCH audioctx tier=$tier floor=$floor slice=${seconds}s " +
                                "wallMs=$wallMs wer=%.3f binding=$binding",
                            wer,
                        ),
                    )
                }
                val qualifies = WerMath.floorQualifies(bindingWers)
                // bindingSlices=0 would print verdict=FAIL maxWer=0.000 — NO EVIDENCE, not a
                // measured regression. Unreachable with today's slices/candidates, and the record
                // file tells the owner how to read it if a future edit makes it reachable.
                android.util.Log.i(
                    TAG,
                    String.format(
                        java.util.Locale.US,
                        "BENCH audioctx tier=$tier floor=$floor " +
                            "verdict=${if (qualifies) "PASS" else "FAIL"} " +
                            "maxWer=%.3f gate=%.2f bindingSlices=%d",
                        bindingWers.maxOrNull() ?: 0.0,
                        WerMath.FLOOR_WER_GATE,
                        bindingWers.size,
                    ),
                )
            }
        } finally {
            // NEVER leak a lowered floor: the override is process-global and the bubble service
            // lives in this same process.
            com.whispereverywhere.whisper.WhisperNative.setAudioCtxFloor(PRODUCTION_FLOOR)
            WhisperNativeBackend.release(ctx)
        }
    }

    /**
     * Does [floor] actually change this slice's encoder context? whisper_jni computes
     * `neededFrames = samples/320 + 64` and then raises it to the floor, so the floor is the
     * binding term only when it is STRICTLY greater — at or below neededFrames the transcribe
     * runs identically with or without it.
     *
     * [sampleCount] is the PRE-VAD count this test hands the backend, while the native side
     * computes neededFrames from the VAD-FILTERED audio, which is never longer. So binding=true
     * here is definitive, and binding=false means "not provably binding" — a slice the VAD
     * trimmed hard could still have hit the floor. jfk.wav is continuous speech with no
     * leading/trailing silence, so trimming should be negligible; the record file tells the owner
     * to REPORT a binding=false line that scores badly rather than dismiss it.
     */
    private fun floorBinds(sampleCount: Int, floor: Int): Boolean =
        floor > (sampleCount / 320) + 64

    /**
     * Belt and braces on top of [benchFloorsForTier]'s per-tier finally: a leaked floor is
     * invisible and lasts the whole process lifetime (the bubble and batch services share this
     * process and this singleton), so the restore is repeated where JUnit guarantees it runs even
     * if a tier's finally is itself bypassed.
     *
     * Touching WhisperNative here force-loads libwhisper_jni.so on every test in this class,
     * including the assumeTrue "no model installed" SKIP path, where nothing else would have.
     * Harmless: this class only ever runs on-device, against an APK that ships the .so.
     */
    @After
    fun restoreProductionFloor() {
        com.whispereverywhere.whisper.WhisperNative.setAudioCtxFloor(PRODUCTION_FLOOR)
    }

    /** Loads [modelPath] once, times each slice's transcription, then frees the context. */
    private fun benchTier(tier: String, modelPath: String, pcm: ByteArray) {
        val t0 = System.currentTimeMillis()
        val ctx = WhisperNativeBackend.load(modelPath)
        val loadMs = System.currentTimeMillis() - t0
        assertNotEquals("model load returned 0 (failed) for tier=$tier at $modelPath", 0L, ctx)
        android.util.Log.i(TAG, "load tier=$tier loadMs=$loadMs")

        try {
            val rtfs = mutableListOf<Double>()
            for (seconds in SLICE_SECONDS) {
                val slicePcm = tileToDuration(pcm, seconds, SAMPLE_RATE)
                val samples = AudioMath.pcm16ToFloat(slicePcm)
                val audioMs = samples.size.toLong() * 1000L / SAMPLE_RATE

                val w0 = System.currentTimeMillis()
                val text = WhisperNativeBackend.transcribe(ctx, samples, "en")
                val wallMs = System.currentTimeMillis() - w0
                val rtf = wallMs.toDouble() / audioMs.toDouble()
                rtfs += rtf

                android.util.Log.i(
                    TAG,
                    "BENCH stt tier=$tier slice=${seconds}s audioMs=$audioMs wallMs=$wallMs rtf=%.3f"
                        .format(rtf),
                )

                // Only that text came back is asserted -- a bench that fails on a slow device is
                // a flaky test, not a measurement. No latency threshold here.
                assertTrue(
                    "tier=$tier slice=${seconds}s produced blank transcription",
                    text.isNotBlank(),
                )
            }

            val sorted = rtfs.sorted()
            android.util.Log.i(
                TAG,
                "BENCH stt tier=$tier summary p50Rtf=%.3f p95Rtf=%.3f maxRtf=%.3f".format(
                    percentile(sorted, 0.50),
                    percentile(sorted, 0.95),
                    sorted.last(),
                ),
            )
        } finally {
            WhisperNativeBackend.release(ctx)
        }
    }

    /**
     * Locates the "data" chunk of a standard WAV file and returns its raw bytes, without
     * assuming a fixed 44-byte header. Mirrors WhisperNativeSmokeTest.wavToFloat's chunk walk
     * in this same package (that one decodes straight to float; this one keeps raw PCM16 bytes
     * so [tileToDuration] can slice/repeat it before the float conversion).
     */
    private fun wavDataChunk(bytes: ByteArray): ByteArray {
        var i = 12 // skip "RIFF"<size>"WAVE"
        while (i + 8 <= bytes.size) {
            val id = String(bytes, i, 4, Charsets.US_ASCII)
            val chunkSize = ByteBuffer.wrap(bytes, i + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (id == "data") {
                return bytes.copyOfRange(i + 8, i + 8 + chunkSize)
            }
            i += 8 + chunkSize + (chunkSize and 1)
        }
        return ByteArray(0)
    }

    /**
     * Builds an exact-duration PCM16 mono buffer by repeating [source] as needed. jfk.wav is
     * only 11 s -- shorter than the largest bench slice (15 s) -- so slices past 11 s wrap back
     * to the start of the clip rather than padding with silence. A silent pad would make the
     * longer slices' RTF look artificially good (whisper's VAD trims silence near-free) instead
     * of measuring sustained decode throughput, which is the point of benching a 15 s slice.
     */
    private fun tileToDuration(source: ByteArray, seconds: Int, sampleRate: Int): ByteArray {
        val neededBytes = seconds * sampleRate * 2 // PCM16 mono = 2 bytes/sample
        val out = ByteArray(neededBytes)
        var pos = 0
        while (pos < neededBytes) {
            val take = minOf(source.size, neededBytes - pos)
            System.arraycopy(source, 0, out, pos, take)
            pos += take
        }
        return out
    }

    /**
     * Linear-interpolated percentile over an already-sorted list. Nearest-rank would be too
     * coarse with only [SLICE_SECONDS].size data points to draw from.
     */
    private fun percentile(sorted: List<Double>, p: Double): Double {
        if (sorted.isEmpty()) return 0.0
        if (sorted.size == 1) return sorted[0]
        val idx = p * (sorted.size - 1)
        val lo = idx.toInt()
        val hi = minOf(lo + 1, sorted.size - 1)
        val frac = idx - lo
        return sorted[lo] + (sorted[hi] - sorted[lo]) * frac
    }
}
