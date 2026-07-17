# Device-Audio Capture (Media Transcription) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When media (YouTube, podcasts, any playback) is playing, transcribe the device's own audio stream via AudioPlaybackCapture instead of the open microphone — perfect signal, zero room noise, zero mic/speaker feedback — with seamless mid-session switching between mic and stream.

**Architecture:** A new `PlaybackAudioCapturer` mirrors the existing `StreamingAudioRecorder` chunk contract, fed by an `AudioRecord` built from an `AudioPlaybackCaptureConfiguration` (MediaProjection consent obtained lazily via a transparent trampoline activity + a `MediaProjectionGate` singleton). `FloatingBubbleService` gains a tiny source state machine driven by pure `AudioSourcePolicy` decisions and the existing `MediaSessionDetector` events: every switch commits the pending whisper segment, stops one source, starts the other. Downstream (engine, VAD, aurora visuals) is source-agnostic and unchanged.

**Tech Stack:** Kotlin, Android `MediaProjection` + `AudioPlaybackCaptureConfiguration` (API 29+), existing whisper.cpp engine pipeline, JUnit4 JVM unit tests.

## Global Constraints

- minSdk 26, targetSdk 35. Playback capture exists only on **API 29+ (`Build.VERSION_CODES.Q`)**; below Q every decision resolves to the microphone (current behavior unchanged).
- **The microphone is FULLY OFF during playback capture** (user decision 2026-07-17) — never both sources at once.
- Every source switch calls `transcriptionEngine?.commit()` **before** stopping the old source, so no buffered audio is lost.
- Chunk contract shared by both sources: `onChunk(chunk: ByteArray /* PCM16 mono 16 kHz */, amp: Int /* RMS 0..32767 */)`, delivered in 1024-byte (32 ms) chunks — identical to `StreamingAudioRecorder` after the 2.7.x work.
- Android 14+ ordering requirement: the service MUST be foregrounded with type `mediaProjection` **before** `MediaProjectionManager.getMediaProjection()` is called.
- Diagnostic logging uses the existing `android.util.Log.i("WE-DIAG", …)` idiom.
- JVM unit tests live under `app/src/test/…`; run with `.\gradlew.bat testReleaseUnitTest --no-daemon`. One gradle build at a time (parallel builds corrupt the shared build dir).
- Commit messages end with the project trailer:
  `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>` + `Claude-Session: https://claude.ai/code/session_011wfHNXHerXRKXGjxX5MvmU`

## File Structure

| File | Responsibility |
|---|---|
| `app/src/main/java/com/whispereverywhere/audio/AudioSourcePolicy.kt` (new) | Pure decision logic: which source a session should use right now |
| `app/src/main/java/com/whispereverywhere/audio/Pcm48kTo16kDecimator.kt` (new) | Stateful 48 kHz→16 kHz mono PCM16 decimator (fallback when the mixer refuses 16 kHz capture) |
| `app/src/main/java/com/whispereverywhere/audio/MediaProjectionGate.kt` (new) | Holds consent state + projection token; bridges trampoline activity ↔ service |
| `app/src/main/java/com/whispereverywhere/audio/ProjectionConsentActivity.kt` (new) | Transparent trampoline that shows the system consent dialog |
| `app/src/main/java/com/whispereverywhere/audio/PlaybackAudioCapturer.kt` (new) | AudioPlaybackCapture reader with silent-stream watchdog; same start/stop/chunk contract as `StreamingAudioRecorder` |
| `app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt` (modify) | Source state machine, mid-session switching, FGS type upgrade |
| `app/src/main/java/com/whispereverywhere/data/local/PreferencesManager.kt` (modify) | `preferDeviceAudio` preference (default ON) |
| `app/src/main/java/com/whispereverywhere/ui/screens/SettingsScreen.kt` (modify) | Settings toggle |
| `app/src/main/AndroidManifest.xml` (modify) | Projection permission, service type, trampoline activity |
| `app/src/test/java/com/whispereverywhere/audio/AudioSourcePolicyTest.kt` (new) | JVM tests |
| `app/src/test/java/com/whispereverywhere/audio/Pcm48kTo16kDecimatorTest.kt` (new) | JVM tests |
| `app/src/main/java/com/whispereverywhere/transcription/TranscriptStore.kt` (new) | Text-only session history with rolling age/size retention |
| `app/src/test/java/com/whispereverywhere/transcription/TranscriptStoreTest.kt` (new) | JVM tests |
| `app/src/main/java/com/whispereverywhere/ui/screens/TranscriptsScreen.kt` (new) | History list/detail UI (copy/share/delete) |
| `app/src/main/java/com/whispereverywhere/ui/screens/HomeScreen.kt` (modify) | "Transcriptions" entry card |
| `app/src/main/java/com/whispereverywhere/MainActivity.kt` (modify) | `"transcripts"` nav route |

---

### Task 1: AudioSourcePolicy (pure decision logic)

**Files:**
- Create: `app/src/main/java/com/whispereverywhere/audio/AudioSourcePolicy.kt`
- Test: `app/src/test/java/com/whispereverywhere/audio/AudioSourcePolicyTest.kt`

**Interfaces:**
- Consumes: nothing (pure Kotlin).
- Produces: `enum class ActiveSource { MIC, PLAYBACK }`; `sealed interface SourceDecision` with `UseMic`, `UsePlayback`, `RequestConsent`; `AudioSourcePolicy.decide(mediaPlaying: Boolean, hasProjection: Boolean, sdkInt: Int, preferDeviceAudio: Boolean): SourceDecision`. Task 5 calls `decide(...)` and branches on the three results.

- [ ] **Step 1: Write the failing tests**

```kotlin
// app/src/test/java/com/whispereverywhere/audio/AudioSourcePolicyTest.kt
package com.whispereverywhere.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioSourcePolicyTest {

    @Test fun `no media playing - mic`() =
        assertEquals(SourceDecision.UseMic,
            AudioSourcePolicy.decide(mediaPlaying = false, hasProjection = true, sdkInt = 34, preferDeviceAudio = true))

    @Test fun `media playing with projection - playback`() =
        assertEquals(SourceDecision.UsePlayback,
            AudioSourcePolicy.decide(mediaPlaying = true, hasProjection = true, sdkInt = 34, preferDeviceAudio = true))

    @Test fun `media playing without projection - request consent`() =
        assertEquals(SourceDecision.RequestConsent,
            AudioSourcePolicy.decide(mediaPlaying = true, hasProjection = false, sdkInt = 34, preferDeviceAudio = true))

    @Test fun `pre-Q device - always mic`() =
        assertEquals(SourceDecision.UseMic,
            AudioSourcePolicy.decide(mediaPlaying = true, hasProjection = true, sdkInt = 28, preferDeviceAudio = true))

    @Test fun `preference off - always mic`() =
        assertEquals(SourceDecision.UseMic,
            AudioSourcePolicy.decide(mediaPlaying = true, hasProjection = true, sdkInt = 34, preferDeviceAudio = false))
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\gradlew.bat testReleaseUnitTest --no-daemon --tests "com.whispereverywhere.audio.AudioSourcePolicyTest"`
Expected: FAIL — unresolved reference `AudioSourcePolicy`.

- [ ] **Step 3: Write the implementation**

```kotlin
// app/src/main/java/com/whispereverywhere/audio/AudioSourcePolicy.kt
package com.whispereverywhere.audio

/** Which physical audio source a recording session is using right now. */
enum class ActiveSource { MIC, PLAYBACK }

/** What the session SHOULD do, given the current world. Pure — JVM-unit-testable. */
sealed interface SourceDecision {
    data object UseMic : SourceDecision
    data object UsePlayback : SourceDecision
    data object RequestConsent : SourceDecision
}

object AudioSourcePolicy {
    /**
     * Decision table (user decisions 2026-07-17: media transcription cuts the mic entirely;
     * playback capture requires API 29+ and the user preference):
     *  - no media / pref off / pre-Q  -> mic (classic behavior)
     *  - media + projection token     -> playback capture
     *  - media, no token yet          -> ask for consent (caller launches the trampoline)
     */
    fun decide(
        mediaPlaying: Boolean,
        hasProjection: Boolean,
        sdkInt: Int,
        preferDeviceAudio: Boolean,
    ): SourceDecision = when {
        !mediaPlaying || !preferDeviceAudio || sdkInt < 29 -> SourceDecision.UseMic
        hasProjection -> SourceDecision.UsePlayback
        else -> SourceDecision.RequestConsent
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `.\gradlew.bat testReleaseUnitTest --no-daemon --tests "com.whispereverywhere.audio.AudioSourcePolicyTest"`
Expected: 5 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/whispereverywhere/audio/AudioSourcePolicy.kt app/src/test/java/com/whispereverywhere/audio/AudioSourcePolicyTest.kt
git commit -m "audio: AudioSourcePolicy — pure mic/playback/consent decision logic"
```

---

### Task 2: 48 kHz→16 kHz decimator (capture-format fallback)

**Files:**
- Create: `app/src/main/java/com/whispereverywhere/audio/Pcm48kTo16kDecimator.kt`
- Test: `app/src/test/java/com/whispereverywhere/audio/Pcm48kTo16kDecimatorTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `class Pcm48kTo16kDecimator { fun process(input: ByteArray, len: Int): ByteArray }` — stateful (carries a partial sample group across calls). Task 4 pipes captured 48 kHz chunks through it when 16 kHz capture is refused.

- [ ] **Step 1: Write the failing tests**

```kotlin
// app/src/test/java/com/whispereverywhere/audio/Pcm48kTo16kDecimatorTest.kt
package com.whispereverywhere.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class Pcm48kTo16kDecimatorTest {

    private fun pcm(vararg samples: Int): ByteArray {
        val out = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            out[i * 2] = (samples[i] and 0xFF).toByte()
            out[i * 2 + 1] = ((samples[i] shr 8) and 0xFF).toByte()
        }
        return out
    }

    @Test fun `three samples average to one`() {
        val out = Pcm48kTo16kDecimator().process(pcm(300, 600, 900), 6)
        assertArrayEquals(pcm(600), out)
    }

    @Test fun `partial group carries across calls`() {
        val d = Pcm48kTo16kDecimator()
        // 5 samples: one full group (100,200,300)->200, leftovers (400,500) carried.
        val first = d.process(pcm(100, 200, 300, 400, 500), 10)
        assertArrayEquals(pcm(200), first)
        // +1 sample completes the carried group: (400,500,600)->500.
        val second = d.process(pcm(600), 2)
        assertArrayEquals(pcm(500), second)
    }

    @Test fun `too little input yields empty output`() {
        assertEquals(0, Pcm48kTo16kDecimator().process(pcm(42), 2).size)
    }

    @Test fun `negative samples decimate correctly`() {
        val out = Pcm48kTo16kDecimator().process(pcm(-300, -600, -900), 6)
        assertArrayEquals(pcm(-600), out)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\gradlew.bat testReleaseUnitTest --no-daemon --tests "com.whispereverywhere.audio.Pcm48kTo16kDecimatorTest"`
Expected: FAIL — unresolved reference `Pcm48kTo16kDecimator`.

- [ ] **Step 3: Write the implementation**

```kotlin
// app/src/main/java/com/whispereverywhere/audio/Pcm48kTo16kDecimator.kt
package com.whispereverywhere.audio

import java.io.ByteArrayOutputStream

/**
 * 3:1 decimator: 48 kHz mono PCM16 -> 16 kHz mono PCM16 by averaging sample triplets
 * (the average is a crude but sufficient anti-alias for speech-band transcription).
 * Stateful: a trailing partial triplet is carried into the next call.
 */
class Pcm48kTo16kDecimator {

    private val carry = ByteArrayOutputStream()

    fun process(input: ByteArray, len: Int): ByteArray {
        carry.write(input, 0, len)
        val data = carry.toByteArray()
        val groups = (data.size / 2) / 3
        val out = ByteArray(groups * 2)
        for (g in 0 until groups) {
            var acc = 0
            for (k in 0 until 3) {
                val i = (g * 3 + k) * 2
                val sample = ((data[i].toInt() and 0xFF) or (data[i + 1].toInt() shl 8)).toShort()
                acc += sample.toInt()
            }
            val avg = acc / 3
            out[g * 2] = (avg and 0xFF).toByte()
            out[g * 2 + 1] = ((avg shr 8) and 0xFF).toByte()
        }
        carry.reset()
        val consumed = groups * 3 * 2
        if (consumed < data.size) carry.write(data, consumed, data.size - consumed)
        return out
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `.\gradlew.bat testReleaseUnitTest --no-daemon --tests "com.whispereverywhere.audio.Pcm48kTo16kDecimatorTest"`
Expected: 4 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/whispereverywhere/audio/Pcm48kTo16kDecimator.kt app/src/test/java/com/whispereverywhere/audio/Pcm48kTo16kDecimatorTest.kt
git commit -m "audio: 48k->16k PCM16 decimator (playback-capture format fallback)"
```

---

### Task 3: Consent plumbing — manifest, MediaProjectionGate, trampoline activity

No JVM-testable logic here (all framework calls); the verification step is a clean build.
The Android-14+ ordering rule lives in the Gate's KDoc so no future caller violates it.

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/java/com/whispereverywhere/audio/MediaProjectionGate.kt`
- Create: `app/src/main/java/com/whispereverywhere/audio/ProjectionConsentActivity.kt`

**Interfaces:**
- Consumes: nothing new.
- Produces (used by Tasks 4-5):
  - `MediaProjectionGate.hasProjection(): Boolean`
  - `MediaProjectionGate.projectionOrNull(): MediaProjection?`
  - `MediaProjectionGate.requestConsent(context: Context)` — fire-and-forget; result arrives via listener
  - `MediaProjectionGate.listener: MediaProjectionGate.Listener?` with
    `onConsentGranted(resultCode: Int, data: Intent)` / `onConsentDenied()`
  - `MediaProjectionGate.storeProjection(p: MediaProjection)` / `clear()`

- [ ] **Step 1: Manifest — permission, service type, trampoline**

In `app/src/main/AndroidManifest.xml`, next to the existing
`<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />` add:

```xml
    <!-- Device-audio capture for media transcription (AudioPlaybackCapture, Android 10+). -->
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION" />
```

On the `FloatingBubbleService` `<service>` element, change
`android:foregroundServiceType="microphone"` to:

```xml
            android:foregroundServiceType="microphone|mediaProjection"
```

Inside `<application>`, next to MainActivity, add:

```xml
        <!-- Transparent trampoline for the MediaProjection consent dialog (device-audio capture).
             Launched from the bubble service; finishes immediately after the system dialog. -->
        <activity
            android:name=".audio.ProjectionConsentActivity"
            android:excludeFromRecents="true"
            android:exported="false"
            android:taskAffinity=""
            android:theme="@android:style/Theme.Translucent.NoTitleBar" />
```

- [ ] **Step 2: MediaProjectionGate**

```kotlin
// app/src/main/java/com/whispereverywhere/audio/MediaProjectionGate.kt
package com.whispereverywhere.audio

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.util.Log

/**
 * Bridge between the consent trampoline and the bubble service.
 *
 * ANDROID 14+ ORDERING RULE: the service must ALREADY be foregrounded with type
 * mediaProjection when MediaProjectionManager.getMediaProjection() is called. Therefore the
 * Gate does NOT create the projection itself — it hands the raw (resultCode, data) to the
 * listener (the service), which upgrades its foreground type FIRST, creates the projection,
 * then calls [storeProjection].
 */
object MediaProjectionGate {

    interface Listener {
        fun onConsentGranted(resultCode: Int, data: Intent)
        fun onConsentDenied()
    }

    @Volatile var listener: Listener? = null
    @Volatile private var projection: MediaProjection? = null

    fun hasProjection(): Boolean = projection != null
    fun projectionOrNull(): MediaProjection? = projection

    fun storeProjection(p: MediaProjection) {
        projection = p
    }

    /** Drop the token (projection stopped / service destroyed). Consent must be re-requested. */
    fun clear() {
        projection?.let { runCatching { it.stop() } }
        projection = null
    }

    fun requestConsent(context: Context) {
        Log.i("WE-DIAG", "MediaProjectionGate: launching consent trampoline")
        context.startActivity(
            Intent(context, ProjectionConsentActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** Called only by ProjectionConsentActivity. */
    internal fun deliverResult(resultCode: Int, data: Intent?) {
        val l = listener
        if (resultCode == android.app.Activity.RESULT_OK && data != null && l != null) {
            l.onConsentGranted(resultCode, data)
        } else {
            Log.i("WE-DIAG", "MediaProjectionGate: consent denied/cancelled (code=$resultCode)")
            l?.onConsentDenied()
        }
    }
}
```

- [ ] **Step 3: ProjectionConsentActivity**

```kotlin
// app/src/main/java/com/whispereverywhere/audio/ProjectionConsentActivity.kt
package com.whispereverywhere.audio

import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/** Invisible one-shot host for the system MediaProjection consent dialog. */
class ProjectionConsentActivity : ComponentActivity() {

    private val launcher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        MediaProjectionGate.deliverResult(result.resultCode, result.data)
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        launcher.launch(mpm.createScreenCaptureIntent())
    }
}
```

- [ ] **Step 4: Verify it builds**

Run: `.\gradlew.bat assembleRelease --no-daemon`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/AndroidManifest.xml app/src/main/java/com/whispereverywhere/audio/MediaProjectionGate.kt app/src/main/java/com/whispereverywhere/audio/ProjectionConsentActivity.kt
git commit -m "audio: MediaProjection consent plumbing (gate + transparent trampoline + manifest)"
```

---

### Task 4: PlaybackAudioCapturer

**Files:**
- Create: `app/src/main/java/com/whispereverywhere/audio/PlaybackAudioCapturer.kt`

**Interfaces:**
- Consumes: `MediaProjection` (from Task 3), `Pcm48kTo16kDecimator` (Task 2), `AudioMath.amplitude(buffer, read)` (existing util — same one `StreamingAudioRecorder` uses).
- Produces (used by Task 5):
  - `class PlaybackAudioCapturer(projection: MediaProjection, onSilentStream: () -> Unit)`
  - `fun start(onChunk: (ByteArray, Int) -> Unit): Result<Unit>` — chunks are PCM16 mono 16 kHz, ~32 ms
  - `fun stop()`

- [ ] **Step 1: Write the implementation**

```kotlin
// app/src/main/java/com/whispereverywhere/audio/PlaybackAudioCapturer.kt
package com.whispereverywhere.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.whispereverywhere.util.AudioMath

/**
 * Captures the DEVICE's audio output (media playback) via AudioPlaybackCapture and delivers
 * it in the exact chunk contract of StreamingAudioRecorder: PCM16 mono 16 kHz, ~32 ms chunks,
 * (chunk, rmsAmplitude) callback. The microphone is never touched.
 *
 * Format: 16 kHz mono is requested directly (the platform mixer resamples for us on most
 * devices); if that AudioRecord refuses to initialize, we capture 48 kHz mono and decimate.
 *
 * Silent-stream watchdog: DRM-protected apps (Netflix etc.) opt out of capture — the stream
 * then arrives as digital silence. After [SILENT_TIMEOUT_MS] with no energy, [onSilentStream]
 * fires exactly once so the service can fall back to the microphone.
 */
@RequiresApi(Build.VERSION_CODES.Q)
class PlaybackAudioCapturer(
    private val projection: MediaProjection,
    private val onSilentStream: () -> Unit,
) {

    @Volatile private var recording = false
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    private var decimator: Pcm48kTo16kDecimator? = null

    fun start(onChunk: (ByteArray, Int) -> Unit): Result<Unit> {
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        var sampleRate = 16000
        var rec = buildRecord(config, sampleRate)
        if (rec == null) {
            sampleRate = 48000
            rec = buildRecord(config, sampleRate)
            decimator = Pcm48kTo16kDecimator()
        }
        if (rec == null) {
            return Result.failure(IllegalStateException("Playback-capture AudioRecord failed to initialize"))
        }
        record = rec
        Log.i("WE-DIAG", "PlaybackAudioCapturer: capturing at ${sampleRate}Hz (decimate=${decimator != null})")

        recording = true
        rec.startRecording()

        // 32 ms per read: 1024 bytes @16k, 3072 bytes @48k (decimates to ~1024).
        val readSize = if (sampleRate == 16000) 1024 else 3072
        thread = Thread {
            val buffer = ByteArray(readSize)
            var lastLoudMs = System.currentTimeMillis()
            var silentFired = false
            while (recording) {
                val read = record?.read(buffer, 0, buffer.size) ?: break
                if (read > 0) {
                    val out = decimator?.process(buffer, read) ?: buffer.copyOf(read)
                    if (out.isEmpty()) continue
                    val amp = AudioMath.amplitude(out, out.size)
                    val now = System.currentTimeMillis()
                    if (amp > 200) lastLoudMs = now
                    if (!silentFired && now - lastLoudMs > SILENT_TIMEOUT_MS) {
                        silentFired = true
                        Log.w("WE-DIAG", "PlaybackAudioCapturer: stream silent ${SILENT_TIMEOUT_MS}ms (DRM opt-out?)")
                        onSilentStream()
                    }
                    onChunk(out, amp)
                }
            }
            Log.i("WE-DIAG", "PlaybackAudioCapturer: capture thread stopped")
        }.also { it.start() }

        return Result.success(Unit)
    }

    fun stop() {
        recording = false
        thread?.join(2000)
        thread = null
        record?.let { r ->
            runCatching { r.stop() }
            runCatching { r.release() }
        }
        record = null
        decimator = null
    }

    @SuppressLint("MissingPermission") // capture config replaces the mic permission path
    private fun buildRecord(config: AudioPlaybackCaptureConfiguration, sampleRate: Int): AudioRecord? {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)
        val rec = runCatching {
            AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(format)
                .setBufferSizeInBytes(minBuf)
                .build()
        }.getOrNull() ?: return null
        return if (rec.state == AudioRecord.STATE_INITIALIZED) rec else {
            runCatching { rec.release() }
            null
        }
    }

    private companion object {
        const val SILENT_TIMEOUT_MS = 3000L
    }
}
```

- [ ] **Step 2: Verify it builds**

Run: `.\gradlew.bat assembleRelease --no-daemon`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/whispereverywhere/audio/PlaybackAudioCapturer.kt
git commit -m "audio: PlaybackAudioCapturer — device-audio capture with silent-stream watchdog"
```

---

### Task 5: Service integration — source state machine + mid-session switching

**Files:**
- Modify: `app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt`
- Modify: `app/src/main/java/com/whispereverywhere/data/local/PreferencesManager.kt`

**Interfaces:**
- Consumes: everything Tasks 1-4 produce (exact names above), plus existing members:
  `audioRecorder: StreamingAudioRecorder`, `mediaDetector: MediaSessionDetector`
  (`isCurrentlyPlaying(): Boolean`, listener callbacks `onMediaPlaybackStarted/Stopped`),
  `transcriptionEngine`, `speechSegmenter`, `waveformView`, `blobView`, `currentState`,
  `serviceScope`, `showToast(msg)`, `createNotification()`.
- Produces: nothing new for later tasks; this is the integration point.

- [ ] **Step 1: `preferDeviceAudio` preference**

In `PreferencesManager.kt`, next to the `bubbleAlwaysOn` block, add (same idiom):

```kotlin
    // Media transcription source: true (default) = capture the DEVICE's audio stream while
    // media is playing (mic fully off — no room noise / feedback); false = always microphone.
    private val _preferDeviceAudio = MutableStateFlow(prefs.getBoolean(KEY_PREFER_DEVICE_AUDIO, true))
    val preferDeviceAudio: StateFlow<Boolean> = _preferDeviceAudio.asStateFlow()

    fun setPreferDeviceAudio(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PREFER_DEVICE_AUDIO, enabled).apply()
        _preferDeviceAudio.value = enabled
    }

    fun isPreferDeviceAudio(): Boolean = _preferDeviceAudio.value
```

and in its companion:

```kotlin
        private const val KEY_PREFER_DEVICE_AUDIO = "prefer_device_audio"
```

- [ ] **Step 2: Service fields + chunk sink extraction**

In `FloatingBubbleService.kt` add fields next to `audioRecorder`:

```kotlin
    private var playbackCapturer: com.whispereverywhere.audio.PlaybackAudioCapturer? = null
    @Volatile private var activeSource = com.whispereverywhere.audio.ActiveSource.MIC
```

Extract the existing recording-callback body (currently the lambda passed to
`audioRecorder.start { chunk, amp -> ... }` in the `onOpen` handler) into a member function so
BOTH sources share one downstream path — move the lambda body verbatim:

```kotlin
    /** Shared downstream for BOTH audio sources (mic and playback capture). */
    private fun onAudioChunk(chunk: ByteArray, amp: Int) {
        val engine = transcriptionEngine ?: return
        engine.sendAudio(chunk)
        val bands = if (amp > 350) {
            com.whispereverywhere.util.AudioBands.analyze(chunk, chunk.size)
        } else {
            com.whispereverywhere.util.AudioBands.ZERO
        }
        waveformView.updateBands(bands)
        blobView.updateBands(bands)
        waveformView.updateAmplitude(amp)
        blobView.updateAmplitude(amp)
        val now = System.currentTimeMillis()
        if (speechSegmenter.onAmplitude(amp, now)) {
            android.util.Log.i("WE-DIAG", "VAD -> commit (rms=$amp)")
            lastCommitWallMs = now
            engine.commit()
        } else if (now - lastCommitWallMs >= MAX_SEGMENT_WALL_MS) {
            android.util.Log.i("WE-DIAG", "wall-clock cap -> commit")
            lastCommitWallMs = now
            engine.commit()
            speechSegmenter.reset()
        }
    }
```

(NOTE for the implementer: `waveformView.updateAmplitude(amp)` + `blobView.updateAmplitude(amp)`
currently live in the separate `amplitudeJob` StateFlow collector — keep that collector for the
waveform ribbon animation cadence if present, but calling them here too is harmless and keeps
the playback path identical. The VAD/commit lines are moved verbatim from the existing lambda.)

Replace the old `audioRecorder.start { chunk, amp -> ... }` call with:

```kotlin
                    val started = startAudioInput()
```

- [ ] **Step 3: The source state machine**

Add these member functions to `FloatingBubbleService`:

```kotlin
    /** Start the correct source for the current world (mic vs device-audio). */
    private fun startAudioInput(): Result<Unit> {
        val decision = com.whispereverywhere.audio.AudioSourcePolicy.decide(
            mediaPlaying = mediaDetector.isCurrentlyPlaying(),
            hasProjection = com.whispereverywhere.audio.MediaProjectionGate.hasProjection(),
            sdkInt = Build.VERSION.SDK_INT,
            preferDeviceAudio = app.preferencesManager.isPreferDeviceAudio(),
        )
        android.util.Log.i("WE-DIAG", "startAudioInput: decision=$decision")
        return when (decision) {
            com.whispereverywhere.audio.SourceDecision.UseMic -> startMicSource()
            com.whispereverywhere.audio.SourceDecision.UsePlayback -> startPlaybackSource()
            com.whispereverywhere.audio.SourceDecision.RequestConsent -> {
                // Ask once; recording begins when consent arrives (onConsentGranted below).
                // The mic is NOT opened meanwhile — media capture must never mix room audio.
                com.whispereverywhere.audio.MediaProjectionGate.listener = projectionListener
                com.whispereverywhere.audio.MediaProjectionGate.requestConsent(this)
                showToast("Allow screen capture to transcribe device audio")
                Result.success(Unit)
            }
        }
    }

    private fun startMicSource(): Result<Unit> {
        activeSource = com.whispereverywhere.audio.ActiveSource.MIC
        return audioRecorder.start(::onAudioChunk)
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun startPlaybackSource(): Result<Unit> {
        val projection = com.whispereverywhere.audio.MediaProjectionGate.projectionOrNull()
            ?: return startMicSource()
        val capturer = com.whispereverywhere.audio.PlaybackAudioCapturer(projection) {
            // DRM opt-out / silent stream: fall back to the microphone, on the main thread.
            serviceScope.launch(Dispatchers.Main) {
                if (activeSource == com.whispereverywhere.audio.ActiveSource.PLAYBACK &&
                    currentState == BubbleState.RECORDING
                ) {
                    showToast("This app blocks audio capture — using microphone")
                    switchSource(to = com.whispereverywhere.audio.ActiveSource.MIC)
                }
            }
        }
        val started = capturer.start(::onAudioChunk)
        return if (started.isSuccess) {
            playbackCapturer = capturer
            activeSource = com.whispereverywhere.audio.ActiveSource.PLAYBACK
            showToast("Capturing device audio")
            started
        } else {
            android.util.Log.w("WE-DIAG", "playback capture failed to start -> mic fallback")
            startMicSource()
        }
    }

    /** Commit the pending segment, stop the current source, start the other. Main thread only. */
    private fun switchSource(to: com.whispereverywhere.audio.ActiveSource) {
        if (activeSource == to || currentState != BubbleState.RECORDING) return
        android.util.Log.i("WE-DIAG", "switchSource: $activeSource -> $to")
        transcriptionEngine?.commit()
        speechSegmenter.reset()
        when (activeSource) {
            com.whispereverywhere.audio.ActiveSource.MIC -> audioRecorder.stop()
            com.whispereverywhere.audio.ActiveSource.PLAYBACK -> {
                playbackCapturer?.stop(); playbackCapturer = null
            }
        }
        when (to) {
            com.whispereverywhere.audio.ActiveSource.MIC -> startMicSource()
            com.whispereverywhere.audio.ActiveSource.PLAYBACK ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startPlaybackSource() else startMicSource()
        }
    }

    private val projectionListener = object : com.whispereverywhere.audio.MediaProjectionGate.Listener {
        override fun onConsentGranted(resultCode: Int, data: android.content.Intent) {
            serviceScope.launch(Dispatchers.Main) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@launch
                // ANDROID 14+ ORDERING: foreground with mediaProjection type BEFORE getMediaProjection.
                ServiceCompat.startForeground(
                    this@FloatingBubbleService,
                    WhisperEverywhereApp.NOTIFICATION_ID,
                    createNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
                )
                val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
                val projection = runCatching { mpm.getMediaProjection(resultCode, data) }.getOrNull()
                if (projection == null) {
                    android.util.Log.w("WE-DIAG", "getMediaProjection returned null -> mic")
                    if (currentState == BubbleState.RECORDING) startMicSource()
                    return@launch
                }
                projection.registerCallback(object : android.media.projection.MediaProjection.Callback() {
                    override fun onStop() {
                        com.whispereverywhere.audio.MediaProjectionGate.clear()
                    }
                }, null)
                com.whispereverywhere.audio.MediaProjectionGate.storeProjection(projection)
                if (currentState == BubbleState.RECORDING &&
                    activeSource != com.whispereverywhere.audio.ActiveSource.PLAYBACK
                ) {
                    startPlaybackSource()
                }
            }
        }

        override fun onConsentDenied() {
            serviceScope.launch(Dispatchers.Main) {
                if (currentState == BubbleState.RECORDING) {
                    showToast("Using microphone (capture permission declined)")
                    startMicSource()
                }
            }
        }
    }
```

- [ ] **Step 4: Mid-session media events (the press-mic-first flow)**

In `onMediaPlaybackStarted(...)` add, AFTER the existing context handling:

```kotlin
            // User decision: media transcription cuts the mic. If a mic recording is live when
            // playback begins (mic-button-first flow), hand over to the device stream.
            if (currentState == BubbleState.RECORDING &&
                activeSource == com.whispereverywhere.audio.ActiveSource.MIC &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                app.preferencesManager.isPreferDeviceAudio()
            ) {
                if (com.whispereverywhere.audio.MediaProjectionGate.hasProjection()) {
                    switchSource(to = com.whispereverywhere.audio.ActiveSource.PLAYBACK)
                } else {
                    // Flush + stop the mic NOW (never mix room audio into a media session),
                    // then ask; capture starts when consent lands.
                    transcriptionEngine?.commit()
                    audioRecorder.stop()
                    com.whispereverywhere.audio.MediaProjectionGate.listener = projectionListener
                    com.whispereverywhere.audio.MediaProjectionGate.requestConsent(this@FloatingBubbleService)
                    showToast("Allow screen capture to transcribe device audio")
                }
            }
```

In `onMediaPlaybackStopped()` add, after the existing handling:

```kotlin
            // Media ended while capturing the stream: hand back to the microphone seamlessly.
            if (currentState == BubbleState.RECORDING &&
                activeSource == com.whispereverywhere.audio.ActiveSource.PLAYBACK
            ) {
                switchSource(to = com.whispereverywhere.audio.ActiveSource.MIC)
            }
```

- [ ] **Step 5: Teardown paths**

In `stopRecording()`, right after `audioRecorder.stop()` add:

```kotlin
        playbackCapturer?.stop(); playbackCapturer = null
        activeSource = com.whispereverywhere.audio.ActiveSource.MIC
```

In `onDestroy()`, next to `audioRecorder.stop()` add:

```kotlin
        playbackCapturer?.stop(); playbackCapturer = null
        com.whispereverywhere.audio.MediaProjectionGate.listener = null
        com.whispereverywhere.audio.MediaProjectionGate.clear()
```

- [ ] **Step 6: Build + JVM tests**

Run: `.\gradlew.bat assembleRelease testReleaseUnitTest --no-daemon`
Expected: BUILD SUCCESSFUL, all unit tests pass.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt app/src/main/java/com/whispereverywhere/data/local/PreferencesManager.kt
git commit -m "service: mic<->device-audio source state machine with mid-session handover"
```

---

### Task 6: Settings toggle

**Files:**
- Modify: `app/src/main/java/com/whispereverywhere/ui/screens/SettingsScreen.kt`

**Interfaces:**
- Consumes: `app.preferencesManager.preferDeviceAudio: StateFlow<Boolean>` and
  `setPreferDeviceAudio(Boolean)` from Task 5.

- [ ] **Step 1: Collect the state**

Next to the existing `bubbleAlwaysOn` collection at the top of `SettingsScreen`:

```kotlin
    val preferDeviceAudio by app.preferencesManager.preferDeviceAudio.collectAsState()
```

- [ ] **Step 2: Add the switch (Preferences section, after the always-on-screen item)**

```kotlin
                SettingsSwitchItem(
                    icon = Icons.Filled.MusicNote,
                    title = "Capture device audio for media",
                    subtitle = "While a video or podcast plays, transcribe its audio stream " +
                        "directly (mic off — no room noise). Asks for screen-capture " +
                        "permission the first time",
                    checked = preferDeviceAudio,
                    onCheckedChange = { app.preferencesManager.setPreferDeviceAudio(it) }
                )
```

- [ ] **Step 3: Build**

Run: `.\gradlew.bat assembleRelease --no-daemon`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/whispereverywhere/ui/screens/SettingsScreen.kt
git commit -m "settings: 'Capture device audio for media' toggle (default ON)"
```

---

### Task 7: TranscriptStore (history storage + rolling retention)

**User decisions (2026-07-17):** transcriptions are SAVED to phone storage (text only — audio
retention explicitly declined); retention is a rolling buffer — evict OLDEST first when entries
exceed the age limit or the total-size cap ("a short period of time, not forever").

**Files:**
- Create: `app/src/main/java/com/whispereverywhere/transcription/TranscriptStore.kt`
- Test: `app/src/test/java/com/whispereverywhere/transcription/TranscriptStoreTest.kt`

**Interfaces:**
- Consumes: a `File` directory + injectable clock (pure-JVM testable).
- Produces (used by Tasks 9-10):
  - `class TranscriptStore(dir: File, clock: () -> Long = System::currentTimeMillis)`
  - `data class Entry(val file: File, val startedAtMs: Long, val preview: String, val sizeBytes: Long)`
  - `fun save(startedAtMs: Long, text: String): File`
  - `fun list(): List<Entry>` (newest first)
  - `fun read(entry: Entry): String`
  - `fun delete(entry: Entry)`
  - `fun sweep()` — applies `MAX_AGE_MS` (14 days) and `MAX_TOTAL_BYTES` (10 MB), oldest-first eviction

- [ ] **Step 1: Write the failing tests**

```kotlin
// app/src/test/java/com/whispereverywhere/transcription/TranscriptStoreTest.kt
package com.whispereverywhere.transcription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TranscriptStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test fun `save then list returns newest first with preview`() {
        var now = 1_000_000L
        val store = TranscriptStore(tmp.root) { now }
        store.save(1_000_000L, "first session text")
        now = 2_000_000L
        store.save(2_000_000L, "second session text")
        val entries = store.list()
        assertEquals(2, entries.size)
        assertEquals(2_000_000L, entries[0].startedAtMs)
        assertTrue(entries[0].preview.startsWith("second session"))
    }

    @Test fun `read round-trips the saved text`() {
        val store = TranscriptStore(tmp.root) { 5L }
        store.save(5L, "hello transcription world")
        assertEquals("hello transcription world", store.read(store.list()[0]))
    }

    @Test fun `sweep evicts entries older than max age`() {
        var now = 0L
        val store = TranscriptStore(tmp.root) { now }
        store.save(0L, "ancient")
        now = TranscriptStore.MAX_AGE_MS + 1
        store.save(now, "fresh")
        store.sweep()
        val entries = store.list()
        assertEquals(1, entries.size)
        assertEquals("fresh", store.read(entries[0]))
    }

    @Test fun `sweep evicts oldest first when over the size cap`() {
        var now = 0L
        val store = TranscriptStore(tmp.root) { now }
        val big = "x".repeat(600)
        for (i in 0 until 5) {
            now = i * 1000L
            store.save(now, big)
        }
        store.sweep(maxTotalBytes = 2000L)   // fits 3 of the ~600-byte entries
        val entries = store.list()
        assertTrue(entries.size <= 3)
        // Newest survived; the evicted ones were the oldest.
        assertEquals(4000L, entries[0].startedAtMs)
    }

    @Test fun `delete removes exactly one entry`() {
        var now = 0L
        val store = TranscriptStore(tmp.root) { now }
        store.save(0L, "keep")
        now = 1000L
        store.save(1000L, "remove")
        store.delete(store.list()[0]) // newest = "remove"
        val entries = store.list()
        assertEquals(1, entries.size)
        assertEquals("keep", store.read(entries[0]))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\gradlew.bat testReleaseUnitTest --no-daemon --tests "com.whispereverywhere.transcription.TranscriptStoreTest"`
Expected: FAIL — unresolved reference `TranscriptStore`.

- [ ] **Step 3: Write the implementation**

```kotlin
// app/src/main/java/com/whispereverywhere/transcription/TranscriptStore.kt
package com.whispereverywhere.transcription

import java.io.File

/**
 * On-device transcription history (TEXT ONLY — audio is deliberately never retained).
 *
 * One UTF-8 file per session in [dir], named "<startedAtMs>.txt". Retention is a rolling
 * buffer applied by [sweep]: entries older than [MAX_AGE_MS] are removed, then oldest-first
 * eviction until the total size fits [MAX_TOTAL_BYTES]. Long transcriptions therefore stay
 * recoverable "for a while, not forever" (user decision 2026-07-17).
 */
class TranscriptStore(
    private val dir: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    data class Entry(
        val file: File,
        val startedAtMs: Long,
        val preview: String,
        val sizeBytes: Long,
    )

    init { dir.mkdirs() }

    fun save(startedAtMs: Long, text: String): File {
        val f = File(dir, "$startedAtMs.txt")
        f.writeText(text)
        return f
    }

    /** Newest first. Ignores non-conforming files. */
    fun list(): List<Entry> =
        (dir.listFiles() ?: emptyArray())
            .mapNotNull { f ->
                val ts = f.name.removeSuffix(".txt").toLongOrNull() ?: return@mapNotNull null
                Entry(
                    file = f,
                    startedAtMs = ts,
                    preview = runCatching {
                        f.bufferedReader().use { it.readText().take(120) }
                    }.getOrDefault(""),
                    sizeBytes = f.length(),
                )
            }
            .sortedByDescending { it.startedAtMs }

    fun read(entry: Entry): String = entry.file.readText()

    fun delete(entry: Entry) {
        entry.file.delete()
    }

    fun sweep(maxAgeMs: Long = MAX_AGE_MS, maxTotalBytes: Long = MAX_TOTAL_BYTES) {
        val now = clock()
        val entries = list().toMutableList()   // newest first
        // Age limit.
        entries.removeAll { e ->
            if (now - e.startedAtMs > maxAgeMs) { e.file.delete(); true } else false
        }
        // Size cap: evict oldest-first until we fit.
        var total = entries.sumOf { it.sizeBytes }
        while (total > maxTotalBytes && entries.isNotEmpty()) {
            val oldest = entries.removeAt(entries.lastIndex)
            total -= oldest.sizeBytes
            oldest.file.delete()
        }
    }

    companion object {
        /** "A short period, not forever": two weeks. */
        const val MAX_AGE_MS: Long = 14L * 24 * 60 * 60 * 1000
        /** Text is tiny — 10 MB holds months of heavy use. */
        const val MAX_TOTAL_BYTES: Long = 10L * 1024 * 1024
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `.\gradlew.bat testReleaseUnitTest --no-daemon --tests "com.whispereverywhere.transcription.TranscriptStoreTest"`
Expected: 5 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/whispereverywhere/transcription/TranscriptStore.kt app/src/test/java/com/whispereverywhere/transcription/TranscriptStoreTest.kt
git commit -m "history: TranscriptStore — rolling text-only transcription retention"
```

---

### Task 8: Session capture — every session's text lands in history

**Files:**
- Modify: `app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt`

**Interfaces:**
- Consumes: `TranscriptStore` (Task 8).
- Produces: nothing new; wiring only.

- [ ] **Step 1: Session accumulator + store field**

Add fields near the other session state:

```kotlin
    private val transcriptStore by lazy {
        com.whispereverywhere.transcription.TranscriptStore(java.io.File(filesDir, "transcripts"))
    }
    private val sessionTranscript = StringBuilder()
    private var sessionStartMs = 0L
```

- [ ] **Step 2: Reset at recording start**

In `startRecording()` (where the session begins — next to `sessionProducedText = false`):

```kotlin
        sessionTranscript.setLength(0)
        sessionStartMs = System.currentTimeMillis()
```

- [ ] **Step 3: Accumulate every completed segment**

In `handleResult(...)` (the single place completed text flows through, for BOTH injection and
preview contexts), append at the top:

```kotlin
        if (text.isNotBlank()) {
            if (sessionTranscript.isNotEmpty()) sessionTranscript.append(' ')
            sessionTranscript.append(text.trim())
        }
```

- [ ] **Step 4: Persist at finalize**

In the finalize block of `stopRecording()` (inside the coroutine, right after
`teardownRealtime()`):

```kotlin
            // History: persist the session (text only) + apply rolling retention.
            if (sessionTranscript.isNotBlank()) {
                withContext(Dispatchers.IO) {
                    transcriptStore.save(sessionStartMs, sessionTranscript.toString())
                    transcriptStore.sweep()
                }
            }
```

- [ ] **Step 5: Build + commit**

Run: `.\gradlew.bat assembleRelease --no-daemon` — BUILD SUCCESSFUL, then:

```bash
git add app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt
git commit -m "history: every session's transcript persists via TranscriptStore"
```

---

### Task 9: Transcriptions UI — home-screen entry + list/detail screen

**Files:**
- Create: `app/src/main/java/com/whispereverywhere/ui/screens/TranscriptsScreen.kt`
- Modify: `app/src/main/java/com/whispereverywhere/MainActivity.kt` (navigation route)
- Modify: `app/src/main/java/com/whispereverywhere/ui/screens/HomeScreen.kt` (entry card)

**Interfaces:**
- Consumes: `TranscriptStore` (Task 8) via `TranscriptStore(File(context.filesDir, "transcripts"))`.
- Produces: composable `TranscriptsScreen(onNavigateBack: () -> Unit)`; nav route `"transcripts"`.

- [ ] **Step 1: TranscriptsScreen**

```kotlin
// app/src/main/java/com/whispereverywhere/ui/screens/TranscriptsScreen.kt
package com.whispereverywhere.ui.screens

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.whispereverywhere.transcription.TranscriptStore
import java.io.File
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranscriptsScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val store = remember { TranscriptStore(File(context.filesDir, "transcripts")) }
    var refresh by remember { mutableStateOf(0) }
    val entries = remember(refresh) { store.list() }
    var selected by remember { mutableStateOf<TranscriptStore.Entry?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Transcriptions") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = androidx.compose.ui.Alignment.Center) {
                Text(
                    "No transcriptions yet.\nSessions are kept for 14 days.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(entries, key = { it.startedAtMs }) { entry ->
                    Card(Modifier.fillMaxWidth().clickable { selected = entry }) {
                        Column(Modifier.padding(14.dp)) {
                            Text(
                                DateFormat.getDateTimeInstance().format(Date(entry.startedAtMs)),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                entry.preview.ifBlank { "(empty)" },
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                            )
                        }
                    }
                }
            }
        }
    }

    selected?.let { entry ->
        val fullText = remember(entry) { runCatching { store.read(entry) }.getOrDefault("") }
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(DateFormat.getDateTimeInstance().format(Date(entry.startedAtMs))) },
            text = {
                Column(Modifier.heightIn(max = 380.dp)) {
                    Text(fullText, style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.verticalScroll(rememberScrollState()))
                }
            },
            confirmButton = {
                Row {
                    IconButton(onClick = { clipboard.setText(AnnotatedString(fullText)) }) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = "Copy")
                    }
                    IconButton(onClick = {
                        context.startActivity(Intent.createChooser(
                            Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, fullText)
                            }, "Share transcription"))
                    }) {
                        Icon(Icons.Filled.Share, contentDescription = "Share")
                    }
                    IconButton(onClick = {
                        store.delete(entry); selected = null; refresh++
                    }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { selected = null }) { Text("Close") }
            },
        )
    }
}
```

(Implementer note: add the two missing imports this uses —
`androidx.compose.foundation.rememberScrollState`, `androidx.compose.foundation.verticalScroll`.)

- [ ] **Step 2: Navigation route**

In `MainActivity.kt`'s `WhisperEverywhereNavigation` NavHost, next to the existing
`composable("settings") { ... }` registration, add (mirror the exact pattern used there):

```kotlin
        composable("transcripts") {
            TranscriptsScreen(onNavigateBack = { navController.popBackStack() })
        }
```

- [ ] **Step 3: Home-screen entry**

In `HomeScreen.kt`, below the "Your Stats" card, add a full-width tappable card (mirror the
stats card's styling idiom used in that file):

```kotlin
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onNavigateToTranscripts() },
        ) {
            Row(
                Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.AutoMirrored.Filled.ReceiptLong, contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Transcriptions", style = MaterialTheme.typography.titleMedium)
                    Text("Your saved sessions — kept 14 days",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
            }
        }
```

`HomeScreen` gains a parameter `onNavigateToTranscripts: () -> Unit = {}` and the NavHost's
home registration passes `onNavigateToTranscripts = { navController.navigate("transcripts") }`
(mirror how `onNavigateToSettings` is already threaded — same file, same pattern).

- [ ] **Step 4: Build + commit**

Run: `.\gradlew.bat assembleRelease --no-daemon` — BUILD SUCCESSFUL, then:

```bash
git add app/src/main/java/com/whispereverywhere/ui/screens/TranscriptsScreen.kt app/src/main/java/com/whispereverywhere/ui/screens/HomeScreen.kt app/src/main/java/com/whispereverywhere/MainActivity.kt
git commit -m "history: Transcriptions screen + home entry (14-day rolling history)"
```

---

### Task 10: Version bump + on-device validation (Fold 6)

**Files:**
- Modify: `app/build.gradle.kts` (versionCode 47, versionName "2.8.0")

- [ ] **Step 1: Bump the version**

```kotlin
        versionCode = 47
        versionName = "2.8.0"  // device-audio capture for media transcription
```

- [ ] **Step 2: Build + install**

Run: `.\gradlew.bat assembleRelease --no-daemon` then
`adb install -r C:\Users\bastr\.androidbuild\WhisperEverywhere\app\outputs\apk\release\app-release.apk`
Expected: `Success`; `dumpsys package com.whispereverywhere` shows `versionName=2.8.0`.

- [ ] **Step 3: Manual validation checklist (with the user driving the phone)**

1. **Media-first flow:** play a YouTube video → tap the bubble → consent dialog appears →
   allow → toast "Capturing device audio" → transcript matches the VIDEO's speech even while
   talking over it in the room (the room voice must NOT appear).
2. **Mic-first flow (the graceful switch):** tap the bubble in a quiet room → speak a
   sentence (mic) → start a YouTube video → toast + handover → both the spoken sentence AND
   the video's speech appear, in order, with no mixed/garbled segment at the boundary.
3. **Media-stop handover:** while capturing, pause the video → speak → the spoken words
   appear (mic resumed).
4. **Consent denial:** revoke by force-stopping the app, retry flow 1 but DENY → toast +
   mic fallback works.
5. **Silent-stream fallback:** if a DRM app is available (Netflix), play it → capture goes
   silent → after ~3 s toast appears and mic takes over.
6. **Visuals:** aurora + blob react to the CAPTURED stream during media transcription.
7. **Regression:** plain dictation (no media) is unchanged; Settings toggle OFF restores
   pure-mic behavior everywhere.
8. **History:** after flows 1-3, HomeScreen → Transcriptions lists the sessions with correct
   timestamps/previews; detail view shows full text; copy, share, and delete all work; a
   deleted entry stays gone after reopening.
9. **Retention smoke:** confirm `filesDir/transcripts/` holds one `<timestamp>.txt` per
   session (via `adb shell run-as` not possible on release — verify via the UI count instead).

- [ ] **Step 4: Commit + push**

```bash
git add app/build.gradle.kts docs/superpowers/plans/2026-07-17-device-audio-capture.md docs/PLAN.md
git commit -m "release 2.8.0: device-audio capture for media transcription"
git push
```

Also tick Track A's checklist in `docs/PLAN.md` and move it to Shipped.

---

## Self-Review Notes

- **Spec coverage:** mic fully cut during capture (Tasks 4-5: capture never opens the mic;
  mid-session handler stops the mic before consent even resolves) ✅; graceful mic-first flow
  (Task 5 Step 4) ✅; YouTube auto-flag via `MediaSessionDetector` (existing, consumed by the
  policy) ✅; feedback prevention ✅; Android <10 unchanged ✅; DRM fallback ✅; plan tracked in
  `docs/PLAN.md` ✅.
- **Type consistency:** `ActiveSource`/`SourceDecision`/`decide(...)` (Task 1) match Task 5's
  call sites; `PlaybackAudioCapturer(projection, onSilentStream).start(::onAudioChunk)`
  (Task 4) matches Task 5; Gate API (Task 3) matches `projectionListener` usage (Task 5).
- **Known risk (documented, accepted):** `AudioMath.amplitude(out, out.size)` is the same
  helper the mic path uses; if its signature differs on inspection, the implementer adapts the
  call — it is used in exactly one new place.