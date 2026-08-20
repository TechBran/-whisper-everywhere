package com.whispereverywhere.whisper

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.whispereverywhere.model.WhisperCatalog
import com.whispereverywhere.transcription.GpuCanaryPolicy
import com.whispereverywhere.transcription.NativeComputeGate
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

                // Locale.US, like every other number this class prints: the default-locale
                // .format() here would emit rtf=0,123 on a comma-decimal device and break the
                // logcat read-back these lines exist for.
                android.util.Log.i(
                    TAG,
                    String.format(
                        java.util.Locale.US,
                        "BENCH stt tier=$tier slice=${seconds}s audioMs=$audioMs wallMs=$wallMs " +
                            "rtf=%.3f",
                        rtf,
                    ),
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
                String.format(
                    java.util.Locale.US,
                    "BENCH stt tier=$tier summary p50Rtf=%.3f p95Rtf=%.3f maxRtf=%.3f",
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
     * Workstream C (3.6.0): per-tier GPU/CPU A-B for the owner's decision gate ("GPU default for
     * multi flips only on canary + bench + owner-device evidence"). Bypasses GpuPolicy
     * DELIBERATELY — both arms are forced via WhisperNative.init(useGpu) — because this measures
     * the hardware, not the gate; no GpuPolicy sentinel is armed, and a GPU-arm crash kills only
     * this instrument run. Every native call holds NativeComputeGate: the instrument shares the
     * app process with the bubble/batch services.
     *
     * NOTE: on devices without OpenCL, ggml silently falls back to CPU inside the "GPU" arm —
     * the two arms then measure the same backend (visible in the log via matching loadMs/rtf).
     *
     * One assertion beyond load success: the CPU arm must pass GpuCanaryPolicy.canaryPasses on
     * the bundled clip — CPU is the trusted ground truth, so a failure there means the clip or
     * the match rule is broken, and the production canary gate would be meaningless.
     *
     * RECORDING THE RESULT (spec Decision Gate 2): read `canaryGpu=true` only ALONGSIDE
     * `gpuVsCpuWer` from the same line — the canary is a ~1 s corruption SCREEN, not an accuracy
     * measurement, and only the WER against the CPU arm shows the GPU decodes real speech the same
     * way. `canaryGpu=false` is equally a result: it reproduces the documented corruption and
     * closes the question for that model+driver. Both arms' numbers go in the decision record.
     *
     * One narrow PRODUCTION failure mode to know when reading a device's prefs next to these
     * numbers — it cannot happen in this harness, which arms no sentinel: in TranscriptionEngine
     * the canary's transcribe RETURNS (its finally has already written gpu_validated=true, meaning
     * "a GPU compute completed without killing us") a moment before GpuPolicy.onCanaryResult
     * records the verdict. A load-time GPU crash inside that window leaves keyValidated=true with
     * NO canary verdict recorded. That gap is inherent to two separate commits and is benign: the
     * missing verdict keeps needsCanary() true, so the next launch re-runs the canary and the
     * verdict it records clears the stale flag either way.
     */
    @Test
    fun bench_gpu_vs_cpu_ab() {
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

        val jfk = wavDataChunk(inst.context.assets.open("jfk.wav").use { it.readBytes() })
        assertTrue("jfk.wav decoded to no PCM samples", jfk.isNotEmpty())
        // The PRODUCTION canary clip (main assets, Workstream C) — targetContext, not inst.context.
        // SKIP rather than error when it is absent: the clip is an owner-supplied binary (C4) and
        // assets.open would throw a bare FileNotFoundException outside any assumption, turning
        // "the owner has not delivered the clip yet" into a red bench. Same assumeTrue idiom the
        // installed-model check above uses.
        val canaryBytes = runCatching {
            context.assets.open("canary_digits.wav").use { it.readBytes() }
        }.getOrNull()
        assumeTrue("canary_digits.wav not bundled", canaryBytes != null)
        val canaryPcm = wavDataChunk(canaryBytes!!)
        assertTrue("canary_digits.wav decoded to no PCM samples", canaryPcm.isNotEmpty())

        // One production-seam load/release first so ggml backends are registered exactly the way
        // production registers them (ensureBackendsLoaded); the A-B arms then force init() flags.
        run {
            val warm = WhisperNativeBackend.load(
                File(modelsDir, installed.first().fileName).absolutePath,
            )
            assertNotEquals("backend warm load failed", 0L, warm)
            WhisperNativeBackend.release(warm)
        }

        for (model in installed) {
            val path = File(modelsDir, model.fileName).absolutePath
            val cpu = benchArm(model.id, path, useGpu = false, jfk = jfk, canaryPcm = canaryPcm)
            val gpu = benchArm(model.id, path, useGpu = true, jfk = jfk, canaryPcm = canaryPcm)
            val wer = WerMath.wer(cpu.sliceText, gpu.sliceText)
            // Locale.US: these numbers are read back off logcat and compared, and a
            // comma-decimal device locale would emit gpuVsCpuWer=0,123.
            // canaryGpu rides on the SAME line as gpuVsCpuWer on purpose — the two are only
            // meaningful together (see the RECORDING note above).
            android.util.Log.i(
                TAG,
                String.format(
                    java.util.Locale.US,
                    "BENCH gpu-ab tier=${model.id} gpuVsCpuWer=%.3f canaryCpu=${cpu.canaryPassed} " +
                        "canaryGpu=${gpu.canaryPassed} cpuRtf8s=%.3f gpuRtf8s=%.3f",
                    wer, cpu.rtf8s, gpu.rtf8s,
                ),
            )
            // The CPU arm's canary is a GATE, not a measurement, and that is deliberate: CPU is
            // the trusted ground truth (the empirical ban is about the GPU only), and with the
            // alias-tolerant match rule and the verified bundled clip in place, a CPU-arm canary
            // failure means the HARNESS is broken — wrong clip, wrong asset, wrong rule. Every
            // number this bench prints would then be meaningless, and the production canary gate
            // it validates would be meaningless too. Failing loudly here is the correct outcome.
            assertTrue(
                "tier=${model.id}: CPU arm failed the canary — clip or match rule is broken",
                cpu.canaryPassed,
            )
            // The GPU arm is a MEASUREMENT: pass and fail are both valid, recordable results.
        }
    }

    private class ArmResult(val sliceText: String, val canaryPassed: Boolean, val rtf8s: Double)

    private fun benchArm(
        tier: String,
        modelPath: String,
        useGpu: Boolean,
        jfk: ByteArray,
        canaryPcm: ByteArray,
    ): ArmResult {
        val arm = if (useGpu) "gpu" else "cpu"
        val t0 = System.currentTimeMillis()
        val ctx = NativeComputeGate.serialized {
            com.whispereverywhere.whisper.WhisperNative.init(modelPath, useGpu)
        }
        val loadMs = System.currentTimeMillis() - t0
        assertNotEquals("init(useGpu=$useGpu) failed for tier=$tier", 0L, ctx)
        android.util.Log.i(TAG, "BENCH gpu-ab tier=$tier arm=$arm loadMs=$loadMs")
        try {
            var rtf8s = 0.0
            var text8s = ""
            for (seconds in listOf(3, 8)) {
                val samples = AudioMath.pcm16ToFloat(tileToDuration(jfk, seconds, SAMPLE_RATE))
                val audioMs = samples.size.toLong() * 1000L / SAMPLE_RATE
                val w0 = System.currentTimeMillis()
                val text = NativeComputeGate.serialized {
                    com.whispereverywhere.whisper.WhisperNative.transcribe(
                        ctx, samples, "en", translate = false, vadModelPath = null,
                    )
                }
                val wallMs = System.currentTimeMillis() - w0
                val rtf = wallMs.toDouble() / audioMs
                android.util.Log.i(
                    TAG,
                    String.format(
                        java.util.Locale.US,
                        "BENCH gpu-ab tier=$tier arm=$arm slice=${seconds}s wallMs=$wallMs rtf=%.3f",
                        rtf,
                    ),
                )
                if (seconds == 8) { rtf8s = rtf; text8s = text }
            }
            val canaryText = NativeComputeGate.serialized {
                com.whispereverywhere.whisper.WhisperNative.transcribe(
                    ctx, AudioMath.pcm16ToFloat(canaryPcm), "en",
                    translate = false, vadModelPath = null,
                )
            }
            return ArmResult(text8s, GpuCanaryPolicy.canaryPasses(canaryText), rtf8s)
        } finally {
            NativeComputeGate.serialized { com.whispereverywhere.whisper.WhisperNative.free(ctx) }
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
                // Clamped like CanaryAudio.dataChunk: an over-declared data size (real encoders
                // emit them) must truncate, not throw — jfk.wav is ours, but the canary clip is
                // an owner-supplied binary.
                return bytes.copyOfRange(i + 8, minOf(i + 8 + chunkSize, bytes.size))
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
