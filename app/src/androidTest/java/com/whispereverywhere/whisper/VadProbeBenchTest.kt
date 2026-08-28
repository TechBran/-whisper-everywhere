package com.whispereverywhere.whisper

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.whispereverywhere.transcription.CanaryAudio
import com.whispereverywhere.transcription.VadModel
import com.whispereverywhere.util.AudioMath
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/**
 * Workstream I acceptance instrument: replay ONE clip through the streaming Silero probe, one
 * 512-sample frame at a time, and print every prob beside the RMS the SHIPPED amplitude gate would
 * have seen for the same frame.
 *
 * Why it exists. Two premises 3.7 rests on are ESTIMATE, not MEASURED (investigation report §6
 * risk 8): that Silero is categorically more noise-robust than an RMS gate, and that the soft
 * talker in a noisy room — the owner's field report, the 251-499 RMS dead band documented at
 * SpeechSegmenter.kt:22-30 — is fixed by it. The vendored tree contains ZERO robustness evidence:
 * one README sentence and functional tests. One pass over the owner's own 8 s "zero dips below
 * -22 dB" clip settles both, and the crosstab line below is the settlement: every frame the
 * amplitude gate calls silence while Silero calls speech is a cut 3.6.0 could never make.
 *
 * It also carries the threshold A/B (spec Workstream I bullet 5) WITHOUT a state machine, and
 * deliberately so: replaying the recorded probs through SileroEndpointer would be validating the
 * tuning with the code the tuning is for. `framesAbove` answers "does this threshold see the
 * speech at all"; `longestGapMs` answers "would a hangover of X ms have cut inside the utterance"
 * — any longestGapMs >= a HANGOVER_MS candidate is a mid-utterance cut at that pair, and
 * no_context = true (whisper_jni.cpp params.no_context) makes such a cut unrepairable.
 *
 * RUN (owner device; NEVER :app:installDebug / :app:connectedDebugAndroidTest — both uninstall
 * first and wipe the downloaded models; adb is not on PATH):
 *
 *   $adb = "C:\Users\bastr\AppData\Local\Android\Sdk\platform-tools\adb.exe"
 *   $env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon
 *   & $adb install -r C:\Users\bastr\.androidbuild\WhisperEverywhere\app\outputs\apk\debug\app-debug.apk
 *   & $adb install -r C:\Users\bastr\.androidbuild\WhisperEverywhere\app\outputs\apk\androidTest\debug\app-debug-androidTest.apk
 *   & $adb push <your-clip.wav> /sdcard/Android/data/com.whispereverywhere/files/owner-vad-clip.wav
 *   & $adb logcat -c
 *   & $adb shell am instrument -w -e class com.whispereverywhere.whisper.VadProbeBenchTest com.whispereverywhere.test/androidx.test.runner.AndroidJUnitRunner
 *   & $adb logcat -d -s WE-BENCH | findstr "BENCH vadprobe"
 *
 * The clip is 16 kHz mono PCM16 (any other format is REJECTED, not silently resampled) and is
 * PUSHED, never bundled: it is personal audio, and this repo has a public remote.
 * getExternalFilesDir is the one path `adb push` can write and the app can read with no storage
 * permission on API 30+.
 *
 * Results go to logcat tag WE-BENCH. The owner records them in
 * docs/superpowers/specs/2026-08-20-i-owner-acceptance.md, Check 1.
 */
@RunWith(AndroidJUnit4::class)
class VadProbeBenchTest {

    private companion object {
        const val TAG = "WE-BENCH"

        /** One Silero window. The mic delivers exactly this per 32 ms AudioRecord callback. */
        const val FRAME_SAMPLES = 512

        /**
         * PCM16 mono: the ONLY nBytes vadProbeFrame accepts. Anything else returns the -1.0f
         * "no verdict" sentinel, because a short frame zero-padded still advances the LSTM by one
         * step and poisons the recurrence. This bench walks whole frames only and drops the
         * remainder, so noVerdict SHOULD read 0 — a nonzero count is itself a finding.
         */
        const val FRAME_BYTES = FRAME_SAMPLES * 2

        const val FRAME_MS = 32

        const val CLIP = "owner-vad-clip.wav"

        /**
         * Spec tuning table (docs/superpowers/specs/2026-08-20-vad-endpointing-design.md):
         * ONSET 0.50, RELEASE 0.35, plus the documented "widen to 0.30 if mid-word splits appear"
         * and the batch filter's own 0.40 for contrast. Copied verbatim, and deliberately NOT
         * imported from the production tuning object — this bench is what validates that object.
         */
        val THRESHOLD_CANDIDATES = listOf(0.50f, 0.40f, 0.35f, 0.30f)

        /**
         * `com.whispereverywhere.audio.EndpointerTuning.PROBE_BUDGET_MS` = 8 ms — the production
         * constant this number mirrors — expressed in the microseconds this bench measures in.
         * Copied, not imported, for the same reason as THRESHOLD_CANDIDATES above: this bench is
         * what validates that tuning object, so importing it would make the check circular. If the
         * production budget ever moves, this line moves with it and the sheet records both.
         */
        const val PROBE_BUDGET_US = 8000L

        /** SpeechSegmenter's shipped defaults (SpeechSegmenter.kt:21,31) — the gate 3.7 replaces. */
        const val AMP_VOICE = 500
        const val AMP_SILENCE = 250
    }

    @Test
    fun bench_vad_probe_frames() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext

        val clip = File(context.getExternalFilesDir(null), CLIP)
        // SKIP, not fail, when the clip is absent: it is an owner-supplied binary exactly like
        // canary_digits.wav was, and "the owner has not pushed it yet" must not read as a red
        // bench. Same assumeTrue idiom WhisperBenchTest uses for a missing model.
        assumeTrue(
            "push the clip first:  adb push <your.wav> " +
                "/sdcard/Android/data/com.whispereverywhere/files/$CLIP",
            clip.exists(),
        )
        val bytes = clip.readBytes()
        // The same format gate the production canary uses. A 44.1 kHz or stereo export would still
        // decode to "samples" and would still produce probs — plausible-looking numbers that would
        // then be recorded as the noise-robustness verdict. Reject instead.
        assertTrue(
            "$CLIP is not 16 kHz mono PCM16 — re-export it at 16 kHz mono before re-running",
            CanaryAudio.formatIsValid(bytes),
        )
        val pcm = CanaryAudio.dataChunk(bytes)
        assertTrue("$CLIP decoded to no PCM samples", pcm.isNotEmpty())

        val vadPath = VadModel.path()
        assertTrue("VadModel.path() is null — the bundled Silero model did not extract", vadPath != null)
        assertTrue("vadProbeInit failed for $vadPath", WhisperNative.vadProbeInit(vadPath!!))
        try {
            WhisperNative.vadProbeReset()
            // Reused DIRECT buffer, exactly as the production probe uses one: the native side reads
            // it through GetDirectBufferAddress, so a heap ByteBuffer would not work at all.
            // LITTLE_ENDIAN is documentation — put(ByteArray) is byte-order-independent, and the
            // WAV bytes are already the little-endian PCM16 the native int16 read expects.
            val buf = ByteBuffer.allocateDirect(FRAME_BYTES).order(ByteOrder.LITTLE_ENDIAN)

            val probs = ArrayList<Float>()
            val costsUs = ArrayList<Long>()
            var noVerdict = 0
            var ampSilentSileroSpeech = 0
            var ampVoiceSileroSilent = 0
            var ampDeadBand = 0
            var frame = 0
            var offset = 0
            while (offset + FRAME_BYTES <= pcm.size) {
                buf.clear()
                buf.put(pcm, offset, FRAME_BYTES)
                // NOT wrapped in NativeComputeGate, on purpose: the probe is outside the gate in
                // production (VAD is forced CPU-only at whisper.cpp:4671-4674, own backend, own
                // buffers), and wrapping it here would measure the lock, not the probe.
                val t0 = System.nanoTime()
                val p = WhisperNative.vadProbeFrame(buf, FRAME_BYTES)
                val us = (System.nanoTime() - t0) / 1000L
                val rms = AudioMath.amplitude(pcm, offset, FRAME_BYTES)
                // Locale.US on every number: these lines are read back off logcat and pasted into
                // the acceptance sheet, and a comma-decimal device locale would emit p=0,5123.
                android.util.Log.i(
                    TAG,
                    String.format(
                        Locale.US,
                        "BENCH vadprobe frame=%d ms=%d rms=%d p=%.4f us=%d",
                        frame, frame * FRAME_MS, rms, p, us,
                    ),
                )
                costsUs += us
                if (p < 0f) {
                    noVerdict++
                } else {
                    probs += p
                    if (rms <= AMP_SILENCE && p >= 0.50f) ampSilentSileroSpeech++
                    if (rms >= AMP_VOICE && p < 0.35f) ampVoiceSileroSilent++
                }
                if (rms > AMP_SILENCE && rms < AMP_VOICE) ampDeadBand++
                frame++
                offset += FRAME_BYTES
            }
            assertTrue("no whole 512-sample frames in $CLIP", probs.isNotEmpty())

            val sortedProbs = probs.map { it.toDouble() }.sorted()
            android.util.Log.i(
                TAG,
                String.format(
                    Locale.US,
                    "BENCH vadprobe summary frames=%d noVerdict=%d durationMs=%d " +
                        "pMin=%.4f p50=%.4f p95=%.4f pMax=%.4f",
                    frame, noVerdict, frame.toLong() * FRAME_MS,
                    sortedProbs.first(), percentile(sortedProbs, 0.50),
                    percentile(sortedProbs, 0.95), sortedProbs.last(),
                ),
            )

            // THE SETTLEMENT LINE. ampSilentSileroSpeech = frames the shipped amplitude gate calls
            // silence (<= 250 RMS) while Silero calls speech (>= 0.50): the soft-talker fix,
            // counted. ampVoiceSileroSilent is the opposite direction and should be near zero.
            // ampDeadBand counts frames in the 251-499 band that can open a segment but can never
            // close one — the documented mechanism behind "it just waits for the 15 s cap".
            android.util.Log.i(
                TAG,
                String.format(
                    Locale.US,
                    "BENCH vadprobe crosstab ampSilentSileroSpeech=%d ampVoiceSileroSilent=%d " +
                        "ampDeadBand=%d ampDeadBandPct=%.1f",
                    ampSilentSileroSpeech, ampVoiceSileroSilent, ampDeadBand,
                    100.0 * ampDeadBand / frame,
                ),
            )

            for (t in THRESHOLD_CANDIDATES) {
                var above = 0
                var gap = 0
                var longestGap = 0
                for (p in probs) {
                    if (p >= t) {
                        above++
                        gap = 0
                    } else {
                        gap++
                        if (gap > longestGap) longestGap = gap
                    }
                }
                android.util.Log.i(
                    TAG,
                    String.format(
                        Locale.US,
                        "BENCH vadprobe threshold=%.2f framesAbove=%d speechPct=%.1f longestGapMs=%d",
                        t, above, 100.0 * above / probs.size, longestGap.toLong() * FRAME_MS,
                    ),
                )
            }

            val sortedCosts = costsUs.map { it.toDouble() }.sorted()
            android.util.Log.i(
                TAG,
                String.format(
                    Locale.US,
                    "BENCH vadprobe cost frames=%d p50us=%.0f p99us=%.0f maxus=%.0f " +
                        "overBudget=%d budgetUs=%d",
                    sortedCosts.size, percentile(sortedCosts, 0.50), percentile(sortedCosts, 0.99),
                    sortedCosts.last(), costsUs.count { it > PROBE_BUDGET_US }, PROBE_BUDGET_US,
                ),
            )
        } finally {
            // The probe context is process-global and this instrument shares the app process with
            // the bubble service. Never leak it.
            WhisperNative.vadProbeFree()
        }
    }

    /** Linear-interpolated percentile over an already-sorted list. Mirrors WhisperBenchTest. */
    private fun percentile(sorted: List<Double>, p: Double): Double {
        if (sorted.isEmpty()) return 0.0
        if (sorted.size == 1) return sorted[0]
        val idx = p * (sorted.size - 1)
        val lo = idx.toInt()
        val hi = minOf(lo + 1, sorted.size - 1)
        return sorted[lo] + (sorted[hi] - sorted[lo]) * (idx - lo)
    }
}
