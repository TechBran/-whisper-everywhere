# Streaming STT + Reactive Waveform — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace batch `whisper-1` transcription with realtime WebSocket streaming so transcribed text lands in the focused field (or clipboard) per sentence as the user speaks, with a new reactive EQ waveform for live feedback.

**Architecture:** A `StreamingAudioRecorder` captures 24 kHz mono PCM16 and emits both audio chunks and an amplitude signal. A `RealtimeTranscriptionClient` (OkHttp WebSocket) streams those chunks to OpenAI's realtime transcription endpoint and surfaces `delta`/`completed`/`error` events. `FloatingBubbleService` orchestrates: it feeds amplitude to a new `BarWaveformView`, injects each finished sentence via the existing `WhisperAccessibilityService`, and manages the bubble state machine (manual stop, error+retry, no batch fallback).

**Tech Stack:** Kotlin, Android, OkHttp WebSocket (already a dependency), kotlinx.serialization (already a dependency), Coroutines/Flow, JUnit 4 (already configured).

**Spec:** `docs/superpowers/specs/2026-06-06-whisper-everywhere-v2-design.md`

---

## File structure

| File | Responsibility | Action |
|---|---|---|
| `app/src/main/java/com/whispereverywhere/util/AudioMath.kt` | Pure PCM16 → amplitude math (JVM-testable) | Create |
| `app/src/main/java/com/whispereverywhere/data/api/RealtimeEvents.kt` | Build client events + parse server events (pure, JVM-testable) | Create |
| `app/src/main/java/com/whispereverywhere/data/api/RealtimeTranscriptionClient.kt` | OkHttp WebSocket session lifecycle + event callbacks | Create |
| `app/src/main/java/com/whispereverywhere/util/StreamingAudioRecorder.kt` | Capture 24 kHz PCM16, emit chunks + amplitude | Create |
| `app/src/main/java/com/whispereverywhere/ui/components/BarWaveformView.kt` | Reactive EQ waveform view | Create |
| `app/src/main/res/layout/floating_bubble.xml` | Add expanding pill + BarWaveformView | Modify |
| `app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt` | Rewire batch → streaming, states, routing | Modify |
| `app/src/main/java/com/whispereverywhere/ui/components/WaveformView.kt` | Old bar view, superseded | Delete (Task 9) |
| `app/src/main/java/com/whispereverywhere/data/api/WhisperApiService.kt` | Batch transcribe path, superseded | Delete (Task 9) |
| `app/src/test/java/com/whispereverywhere/AudioMathTest.kt` | Unit tests for amplitude math | Create |
| `app/src/test/java/com/whispereverywhere/RealtimeEventsTest.kt` | Unit tests for event build/parse | Create |

**Test command (Windows):** `.\gradlew.bat testDebugUnitTest`
**Build command:** `.\gradlew.bat assembleDebug`

---

## Task 1: Lock the realtime API contract with a live smoke test

This de-risks the API-generation ambiguity (beta `transcription_session.update` vs GA `session.update`) before any app code depends on it. No app code is written here; the output is a confirmed JSON shape recorded in this plan.

**Files:** none (manual verification).

- [ ] **Step 1: Install a WebSocket CLI**

Run: `npm install -g wscat`
Expected: `wscat` is on PATH (`wscat --version` prints a version).

- [ ] **Step 2: Connect to the realtime transcription endpoint**

Run (replace `$OPENAI_API_KEY`):
```bash
wscat -c "wss://api.openai.com/v1/realtime?intent=transcription" \
  -H "Authorization: Bearer $OPENAI_API_KEY" \
  -H "OpenAI-Beta: realtime=v1"
```
Expected: connection opens; the server sends a JSON line whose `"type"` is a session-created event (e.g. `transcription_session.created` or `session.created`). Record the exact type string seen.

- [ ] **Step 3: Send the GA-style session config and confirm it is accepted**

Paste this line into the open wscat session:
```json
{"type":"session.update","session":{"type":"transcription","audio":{"input":{"format":{"type":"audio/pcm","rate":24000},"transcription":{"model":"gpt-realtime-whisper"},"turn_detection":{"type":"server_vad"}}}}}
```
Expected: a `session.updated` (or `transcription_session.updated`) event, NOT an `error` event.

- [ ] **Step 4: If GA shape errors, try the beta shape**

If Step 3 returns an `error`, paste instead:
```json
{"type":"transcription_session.update","session":{"input_audio_format":"pcm16","input_audio_transcription":{"model":"gpt-4o-transcribe"},"turn_detection":{"type":"server_vad"}}}
```
Expected: an updated event with no error.

- [ ] **Step 5: Record the confirmed contract in this plan**

Edit the table below to mark which shape and model were accepted, and the exact server event type strings observed for session-created, delta, and completed. Task 3's code MUST match this.

```
CONFIRMED CONTRACT (fill in):
- session update client event type: ________________________
- session JSON shape used:           GA / beta (circle one)
- model accepted:                    ________________________
- delta server event type:           ________________________
- completed server event type:       ________________________
```

- [ ] **Step 6: Commit the updated plan**

```bash
git add docs/superpowers/plans/2026-06-06-streaming-stt-and-waveform.md
git commit -m "docs: lock realtime transcription API contract via smoke test"
```

> The code in Tasks 2–8 below is written against the **GA shape** (`session.update`, `gpt-realtime-whisper`, `conversation.item.input_audio_transcription.delta` / `.completed`). If Step 5 confirmed the beta shape instead, adjust the literal strings in `RealtimeEvents.kt` (Task 3) only — no other task changes.

---

## Task 2: PCM amplitude math (pure, TDD)

Extract the amplitude calculation so it is testable without Android. Uses RMS over 16-bit little-endian samples, scaled to 0..32767.

**Files:**
- Create: `app/src/main/java/com/whispereverywhere/util/AudioMath.kt`
- Test: `app/src/test/java/com/whispereverywhere/AudioMathTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/whispereverywhere/AudioMathTest.kt`:
```kotlin
package com.whispereverywhere

import com.whispereverywhere.util.AudioMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioMathTest {

    @Test
    fun silence_returns_zero() {
        val buf = ByteArray(8) // all zeros = silence
        assertEquals(0, AudioMath.amplitude(buf, buf.size))
    }

    @Test
    fun fullscale_returns_near_max() {
        // Two samples at +32767 (0x7FFF) little-endian: 0xFF, 0x7F
        val buf = byteArrayOf(0xFF.toByte(), 0x7F, 0xFF.toByte(), 0x7F)
        val amp = AudioMath.amplitude(buf, buf.size)
        assertTrue("expected near max, got $amp", amp in 32000..32767)
    }

    @Test
    fun only_reads_validLength() {
        // Buffer larger than valid data; bytes past `length` must be ignored.
        val buf = ByteArray(16)
        buf[0] = 0xFF.toByte(); buf[1] = 0x7F // one loud sample in first 2 bytes
        // length = 2 means only that one sample counts
        val amp = AudioMath.amplitude(buf, 2)
        assertTrue("expected near max, got $amp", amp in 32000..32767)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.whispereverywhere.AudioMathTest"`
Expected: FAIL — `AudioMath` is unresolved / does not compile.

- [ ] **Step 3: Implement `AudioMath`**

Create `app/src/main/java/com/whispereverywhere/util/AudioMath.kt`:
```kotlin
package com.whispereverywhere.util

import kotlin.math.min
import kotlin.math.sqrt

/** Pure helpers for PCM16 audio. No Android dependencies. */
object AudioMath {

    /**
     * Root-mean-square amplitude of the first [length] bytes of [buffer],
     * interpreted as 16-bit little-endian mono samples, scaled to 0..32767.
     */
    fun amplitude(buffer: ByteArray, length: Int): Int {
        val end = min(length, buffer.size)
        if (end < 2) return 0
        var sumSquares = 0.0
        var count = 0
        var i = 0
        while (i + 1 < end) {
            val sample = (buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)
            sumSquares += sample.toDouble() * sample.toDouble()
            count++
            i += 2
        }
        if (count == 0) return 0
        val rms = sqrt(sumSquares / count)
        return rms.toInt().coerceIn(0, 32767)
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.whispereverywhere.AudioMathTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/whispereverywhere/util/AudioMath.kt app/src/test/java/com/whispereverywhere/AudioMathTest.kt
git commit -m "feat: add pure PCM16 amplitude math with tests"
```

---

## Task 3: Realtime event build/parse (pure, TDD)

Isolate all realtime JSON into one pure file so the API contract from Task 1 lives in exactly one place.

**Files:**
- Create: `app/src/main/java/com/whispereverywhere/data/api/RealtimeEvents.kt`
- Test: `app/src/test/java/com/whispereverywhere/RealtimeEventsTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/whispereverywhere/RealtimeEventsTest.kt`:
```kotlin
package com.whispereverywhere

import com.whispereverywhere.data.api.RealtimeEventFactory
import com.whispereverywhere.data.api.RealtimeEventParser
import com.whispereverywhere.data.api.ServerEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeEventsTest {

    @Test
    fun sessionUpdate_includes_model_and_pcm() {
        val json = RealtimeEventFactory.sessionUpdate(
            model = "gpt-realtime-whisper",
            language = "en"
        )
        assertTrue(json.contains("\"type\":\"session.update\""))
        assertTrue(json.contains("\"transcription\""))
        assertTrue(json.contains("gpt-realtime-whisper"))
        assertTrue(json.contains("\"server_vad\""))
        assertTrue(json.contains("\"en\""))
    }

    @Test
    fun sessionUpdate_omits_language_when_null() {
        val json = RealtimeEventFactory.sessionUpdate(model = "gpt-realtime-whisper", language = null)
        assertTrue(!json.contains("\"language\""))
    }

    @Test
    fun appendAudio_wraps_base64() {
        val json = RealtimeEventFactory.appendAudio("QUJD")
        assertTrue(json.contains("\"type\":\"input_audio_buffer.append\""))
        assertTrue(json.contains("\"audio\":\"QUJD\""))
    }

    @Test
    fun parse_delta_event() {
        val json = """{"type":"conversation.item.input_audio_transcription.delta","delta":"hello"}"""
        val event = RealtimeEventParser.parse(json)
        assertTrue(event is ServerEvent.Delta)
        assertEquals("hello", (event as ServerEvent.Delta).text)
    }

    @Test
    fun parse_completed_event() {
        val json = """{"type":"conversation.item.input_audio_transcription.completed","transcript":"Hello world."}"""
        val event = RealtimeEventParser.parse(json)
        assertTrue(event is ServerEvent.Completed)
        assertEquals("Hello world.", (event as ServerEvent.Completed).text)
    }

    @Test
    fun parse_error_event() {
        val json = """{"type":"error","error":{"message":"bad key"}}"""
        val event = RealtimeEventParser.parse(json)
        assertTrue(event is ServerEvent.Error)
        assertEquals("bad key", (event as ServerEvent.Error).message)
    }

    @Test
    fun parse_unknown_event_is_other() {
        val json = """{"type":"session.updated"}"""
        assertTrue(RealtimeEventParser.parse(json) is ServerEvent.Other)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.whispereverywhere.RealtimeEventsTest"`
Expected: FAIL — `RealtimeEventFactory` / `RealtimeEventParser` / `ServerEvent` unresolved.

- [ ] **Step 3: Implement `RealtimeEvents.kt`**

Create `app/src/main/java/com/whispereverywhere/data/api/RealtimeEvents.kt`:
```kotlin
package com.whispereverywhere.data.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Server → client events we care about, plus a catch-all. */
sealed class ServerEvent {
    data class Delta(val text: String) : ServerEvent()
    data class Completed(val text: String) : ServerEvent()
    data class Error(val message: String) : ServerEvent()
    data class Other(val type: String) : ServerEvent()
}

/**
 * Builds client → server JSON for OpenAI realtime transcription (GA shape).
 * If Task 1 confirmed the beta shape, change only the string literals here.
 */
object RealtimeEventFactory {

    fun sessionUpdate(model: String, language: String?): String =
        buildJsonObject {
            put("type", "session.update")
            putJsonObject("session") {
                put("type", "transcription")
                putJsonObject("audio") {
                    putJsonObject("input") {
                        putJsonObject("format") {
                            put("type", "audio/pcm")
                            put("rate", 24000)
                        }
                        putJsonObject("transcription") {
                            put("model", model)
                            if (language != null) put("language", language)
                        }
                        putJsonObject("turn_detection") {
                            put("type", "server_vad")
                        }
                    }
                }
            }
        }.toString()

    fun appendAudio(base64Pcm: String): String =
        buildJsonObject {
            put("type", "input_audio_buffer.append")
            put("audio", base64Pcm)
        }.toString()

    fun commit(): String =
        buildJsonObject { put("type", "input_audio_buffer.commit") }.toString()
}

/** Parses a single server JSON line into a [ServerEvent]. */
object RealtimeEventParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private const val DELTA = "conversation.item.input_audio_transcription.delta"
    private const val COMPLETED = "conversation.item.input_audio_transcription.completed"

    fun parse(raw: String): ServerEvent {
        val obj: JsonObject = try {
            json.parseToJsonElement(raw).jsonObject
        } catch (e: Exception) {
            return ServerEvent.Other("unparseable")
        }
        val type = obj["type"]?.jsonPrimitive?.content ?: return ServerEvent.Other("missing")
        return when (type) {
            DELTA -> ServerEvent.Delta(obj["delta"]?.jsonPrimitive?.content ?: "")
            COMPLETED -> ServerEvent.Completed(obj["transcript"]?.jsonPrimitive?.content ?: "")
            "error" -> {
                val msg = obj["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content ?: "Unknown error"
                ServerEvent.Error(msg)
            }
            else -> ServerEvent.Other(type)
        }
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.whispereverywhere.RealtimeEventsTest"`
Expected: PASS (7 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/whispereverywhere/data/api/RealtimeEvents.kt app/src/test/java/com/whispereverywhere/RealtimeEventsTest.kt
git commit -m "feat: add realtime transcription event build/parse with tests"
```

---

## Task 4: StreamingAudioRecorder

Captures 24 kHz mono PCM16 and exposes a chunk callback + amplitude `StateFlow`. Android-framework-bound (`AudioRecord`), so verified on-device.

**Files:**
- Create: `app/src/main/java/com/whispereverywhere/util/StreamingAudioRecorder.kt`

- [ ] **Step 1: Implement `StreamingAudioRecorder`**

Create `app/src/main/java/com/whispereverywhere/util/StreamingAudioRecorder.kt`:
```kotlin
package com.whispereverywhere.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Streams 24 kHz mono PCM16 from the mic. For each buffer read it invokes
 * [onChunk] (off the main thread) and updates [amplitude] for the waveform.
 * No file is written — audio goes straight to the realtime client.
 */
class StreamingAudioRecorder(private val context: Context) {

    companion object {
        const val SAMPLE_RATE = 24000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }

    private val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        .coerceAtLeast(4096)

    private var audioRecord: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var recording = false

    private val _amplitude = MutableStateFlow(0)
    val amplitude: StateFlow<Int> = _amplitude.asStateFlow()

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /** @param onChunk receives a freshly read PCM16 chunk (a copy of exactly [size] bytes). */
    fun start(onChunk: (ByteArray) -> Unit): Result<Unit> {
        if (!hasPermission()) return Result.failure(SecurityException("Microphone permission not granted"))
        if (recording) return Result.failure(IllegalStateException("Already recording"))

        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL, ENCODING, bufferSize
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return Result.failure(IllegalStateException("Failed to initialize AudioRecord"))
        }
        audioRecord = record
        recording = true
        record.startRecording()

        thread = Thread {
            val buffer = ByteArray(bufferSize)
            while (recording) {
                val read = record.read(buffer, 0, buffer.size)
                if (read > 0) {
                    _amplitude.value = AudioMath.amplitude(buffer, read)
                    onChunk(buffer.copyOf(read))
                }
            }
        }.also { it.start() }

        return Result.success(Unit)
    }

    fun stop() {
        if (!recording) return
        recording = false
        _amplitude.value = 0
        try {
            thread?.join(2000)
            audioRecord?.stop()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            audioRecord?.release()
            audioRecord = null
            thread = null
        }
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `.\gradlew.bat assembleDebug`
Expected: BUILD SUCCESSFUL (no on-device behavior yet; wired in Task 8).

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/whispereverywhere/util/StreamingAudioRecorder.kt
git commit -m "feat: add StreamingAudioRecorder (24kHz PCM16 chunks + amplitude)"
```

---

## Task 5: RealtimeTranscriptionClient

Wraps an OkHttp WebSocket: connect, send session config, stream base64 PCM, surface parsed events on a listener. Verified on-device in Task 8.

**Files:**
- Create: `app/src/main/java/com/whispereverywhere/data/api/RealtimeTranscriptionClient.kt`

- [ ] **Step 1: Implement `RealtimeTranscriptionClient`**

Create `app/src/main/java/com/whispereverywhere/data/api/RealtimeTranscriptionClient.kt`:
```kotlin
package com.whispereverywhere.data.api

import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * Streams microphone PCM16 to OpenAI's realtime transcription endpoint over a
 * WebSocket and reports transcription events via [Listener].
 */
class RealtimeTranscriptionClient(
    private val apiKey: String,
    private val model: String = "gpt-realtime-whisper",
) {
    interface Listener {
        fun onOpen()
        fun onDelta(text: String)
        fun onCompleted(text: String)
        fun onError(message: String)
        fun onClosed()
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS) // keep the socket alive while listening
        .readTimeout(0, TimeUnit.MILLISECONDS) // no read timeout on a streaming socket
        .build()

    private var webSocket: WebSocket? = null
    @Volatile private var listener: Listener? = null

    fun connect(language: String?, listener: Listener) {
        this.listener = listener
        val request = Request.Builder()
            .url("wss://api.openai.com/v1/realtime?intent=transcription")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("OpenAI-Beta", "realtime=v1")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                ws.send(RealtimeEventFactory.sessionUpdate(model, language))
                listener.onOpen()
            }

            override fun onMessage(ws: WebSocket, text: String) {
                when (val event = RealtimeEventParser.parse(text)) {
                    is ServerEvent.Delta -> listener.onDelta(event.text)
                    is ServerEvent.Completed -> listener.onCompleted(event.text)
                    is ServerEvent.Error -> listener.onError(event.message)
                    is ServerEvent.Other -> { /* session.updated, etc. — ignore */ }
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                listener.onError(t.message ?: "Connection failed")
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                listener.onClosed()
            }
        })
    }

    /** Send one PCM16 chunk. Safe to call rapidly from the recorder thread. */
    fun sendAudio(pcm: ByteArray) {
        val b64 = Base64.encodeToString(pcm, Base64.NO_WRAP)
        webSocket?.send(RealtimeEventFactory.appendAudio(b64))
    }

    /** Commit the buffer (call on stop) so the server emits the final completed event. */
    fun commit() {
        webSocket?.send(RealtimeEventFactory.commit())
    }

    fun close() {
        try {
            webSocket?.close(1000, "client closing")
        } catch (e: Exception) {
            e.printStackTrace()
        }
        webSocket = null
        listener = null
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `.\gradlew.bat assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/whispereverywhere/data/api/RealtimeTranscriptionClient.kt
git commit -m "feat: add RealtimeTranscriptionClient (OkHttp WebSocket streaming)"
```

---

## Task 6: BarWaveformView (reactive EQ bars)

A wider, smoother evolution of the current bars with spring-physics settling and idle shimmer. Visual, verified on-device.

**Files:**
- Create: `app/src/main/java/com/whispereverywhere/ui/components/BarWaveformView.kt`

- [ ] **Step 1: Implement `BarWaveformView`**

Create `app/src/main/java/com/whispereverywhere/ui/components/BarWaveformView.kt`:
```kotlin
package com.whispereverywhere.ui.components

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.abs
import kotlin.math.sin

/**
 * Reactive EQ waveform: ~15 bars with spring-style settling, red→purple→blue
 * gradient, idle shimmer when quiet and sharp jumps on amplitude peaks.
 * Drive it with [updateAmplitude] (0..32767); call [start]/[stop] around use.
 */
class BarWaveformView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val barCount = 15
    private val heights = FloatArray(barCount) { 0.1f }
    private val targets = FloatArray(barCount) { 0.1f }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val gradientColors = intArrayOf(
        Color.parseColor("#EF4444"), Color.parseColor("#EC4899"),
        Color.parseColor("#8B5CF6"), Color.parseColor("#3B82F6")
    )

    private var barWidth = 0f
    private var gap = 0f
    private var maxH = 0f
    private var minH = 0f
    private var radius = 0f

    private var phase = 0f
    private var animating = false
    private var ticker: ValueAnimator? = null
    private val spring = 0.4f

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        gap = (w * 0.18f) / (barCount + 1)
        barWidth = (w - gap * (barCount + 1)) / barCount
        maxH = h * 0.9f
        minH = h * 0.1f
        radius = barWidth / 2
        paint.shader = LinearGradient(0f, 0f, w.toFloat(), 0f, gradientColors, null, Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cy = height / 2f
        for (i in 0 until barCount) {
            heights[i] += (targets[i] - heights[i]) * spring
            val bh = minH + (maxH - minH) * heights[i]
            val left = gap + i * (barWidth + gap)
            canvas.drawRoundRect(left, cy - bh / 2, left + barWidth, cy + bh / 2, radius, radius, paint)
        }
        if (animating) invalidate()
    }

    fun updateAmplitude(amplitude: Int) {
        val norm = ((amplitude / 32767f) * 1.6f).coerceIn(0f, 1f)
        for (i in 0 until barCount) {
            val center = 1f - (abs(i - barCount / 2f) / (barCount / 2f)) * 0.35f
            val wiggle = sin((phase + i * 0.5f).toDouble()).toFloat() * 0.12f
            targets[i] = (norm * center + wiggle).coerceIn(0.06f, 1f)
        }
        phase += 0.25f
        invalidate()
    }

    fun start() {
        animating = true
        ticker?.cancel()
        ticker = ValueAnimator.ofFloat(0f, (Math.PI * 2).toFloat()).apply {
            duration = 1600
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                phase = it.animatedValue as Float
                for (i in 0 until barCount) {
                    if (targets[i] < 0.14f) {
                        val center = 1f - (abs(i - barCount / 2f) / (barCount / 2f)) * 0.4f
                        targets[i] = (0.1f + 0.07f * center *
                            sin((phase + i * 0.6f).toDouble()).toFloat()).coerceAtLeast(0.06f)
                    }
                }
                invalidate()
            }
            start()
        }
    }

    fun stop() {
        animating = false
        ticker?.cancel()
        ticker = null
        for (i in 0 until barCount) targets[i] = 0.06f
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stop()
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `.\gradlew.bat assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/whispereverywhere/ui/components/BarWaveformView.kt
git commit -m "feat: add BarWaveformView reactive EQ waveform"
```

---

## Task 7: Expanding pill layout

The bubble stays a 56dp circle when idle; while recording it expands into a pill that holds the wider waveform.

**Files:**
- Modify: `app/src/main/res/layout/floating_bubble.xml`

- [ ] **Step 1: Replace the layout**

Replace the entire contents of `app/src/main/res/layout/floating_bubble.xml` with:
```xml
<?xml version="1.0" encoding="utf-8"?>
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:id="@+id/bubble_root"
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    android:padding="8dp">

    <ImageView
        android:id="@+id/processing_ring"
        android:layout_width="72dp"
        android:layout_height="72dp"
        android:layout_gravity="center"
        android:src="@drawable/ic_processing_ring"
        android:visibility="gone" />

    <FrameLayout
        android:id="@+id/bubble_container"
        android:layout_width="56dp"
        android:layout_height="56dp"
        android:layout_gravity="center"
        android:background="@drawable/bubble_background_idle"
        android:elevation="8dp">

        <ImageView
            android:id="@+id/bubble_icon"
            android:layout_width="32dp"
            android:layout_height="32dp"
            android:layout_gravity="center"
            android:src="@drawable/ic_mic" />

        <com.whispereverywhere.ui.components.BarWaveformView
            android:id="@+id/waveform_view"
            android:layout_width="match_parent"
            android:layout_height="32dp"
            android:layout_gravity="center"
            android:layout_marginStart="10dp"
            android:layout_marginEnd="10dp"
            android:visibility="gone" />

        <TextView
            android:id="@+id/processing_time_text"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_gravity="center"
            android:textColor="#FFFFFF"
            android:textSize="12sp"
            android:textStyle="bold"
            android:visibility="gone" />

    </FrameLayout>

</FrameLayout>
```

The width animation (56dp ↔ ~160dp pill) is driven from the service in Task 8 by animating `bubble_container.layoutParams.width`.

- [ ] **Step 2: Verify it compiles**

Run: `.\gradlew.bat assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/res/layout/floating_bubble.xml
git commit -m "feat: bubble layout uses BarWaveformView and supports pill width"
```

---

## Task 8: Rewire FloatingBubbleService to streaming

The hub change. Recording now streams live; each `completed` sentence is injected immediately; manual stop closes the socket; errors show ERROR + retry. Verified on-device (the realtime path can't be meaningfully unit-tested).

**Files:**
- Modify: `app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt`

- [ ] **Step 1: Swap recorder/client fields and imports**

In `FloatingBubbleService.kt`, replace the import and field for the old recorder/API. Change:
```kotlin
import com.whispereverywhere.data.api.TranscriptionResult
import com.whispereverywhere.data.api.WhisperApiService
import com.whispereverywhere.ui.components.WaveformView
import com.whispereverywhere.util.AudioRecorder
```
to:
```kotlin
import com.whispereverywhere.data.api.RealtimeTranscriptionClient
import com.whispereverywhere.data.api.ServerEvent
import com.whispereverywhere.ui.components.BarWaveformView
import com.whispereverywhere.util.StreamingAudioRecorder
```

Change the declarations:
```kotlin
    private lateinit var waveformView: WaveformView
    ...
    private lateinit var audioRecorder: AudioRecorder
    private lateinit var whisperApi: WhisperApiService
```
to:
```kotlin
    private lateinit var waveformView: BarWaveformView
    ...
    private lateinit var audioRecorder: StreamingAudioRecorder
    private var realtimeClient: RealtimeTranscriptionClient? = null
```

- [ ] **Step 2: Update `onCreate` construction**

In `onCreate()`, replace:
```kotlin
        audioRecorder = AudioRecorder(this)
        whisperApi = WhisperApiService(app.preferencesManager.apiKey)
```
with:
```kotlin
        audioRecorder = StreamingAudioRecorder(this)
```

- [ ] **Step 3: Replace `startRecording()` and `stopRecording()`**

Replace the entire `startRecording()` and `stopRecording()` methods with the streaming versions:
```kotlin
    private fun startRecording() {
        if (!app.preferencesManager.hasApiKey()) {
            vibrateError(); showToast("Please set your OpenAI API key in Settings"); return
        }
        if (!audioRecorder.hasPermission()) {
            vibrateError(); showToast("Microphone permission required"); return
        }

        updateBubbleState(BubbleState.CONNECTING)
        vibrateStart()

        val client = RealtimeTranscriptionClient(app.preferencesManager.apiKey)
        realtimeClient = client

        client.connect(app.preferencesManager.getLanguageForApi(), object : RealtimeTranscriptionClient.Listener {
            override fun onOpen() {
                serviceScope.launch(Dispatchers.Main) {
                    if (currentState != BubbleState.CONNECTING) return@launch
                    val started = audioRecorder.start { chunk -> client.sendAudio(chunk) }
                    if (started.isFailure) {
                        showToast("Recording failed: ${started.exceptionOrNull()?.message}")
                        teardownRealtime(); updateBubbleState(BubbleState.ERROR)
                        return@launch
                    }
                    updateBubbleState(BubbleState.RECORDING)
                    amplitudeJob = serviceScope.launch {
                        audioRecorder.amplitude.collectLatest { amp ->
                            if (currentState == BubbleState.RECORDING) waveformView.updateAmplitude(amp)
                        }
                    }
                }
            }
            override fun onDelta(text: String) { /* live cue only; not injected */ }
            override fun onCompleted(text: String) {
                val trimmed = text.trim()
                if (trimmed.isNotEmpty()) {
                    serviceScope.launch(Dispatchers.Main) { handleTranscriptionResult(trimmed) }
                }
            }
            override fun onError(message: String) {
                serviceScope.launch(Dispatchers.Main) {
                    showToast(message); teardownRealtime(); updateBubbleState(BubbleState.ERROR)
                }
            }
            override fun onClosed() { /* expected on manual stop */ }
        })
    }

    private fun stopRecording() {
        vibrateStop()
        amplitudeJob?.cancel(); amplitudeJob = null
        waveformView.stop()
        audioRecorder.stop()

        updateBubbleState(BubbleState.FINALIZING)
        realtimeClient?.commit()

        // Give the server a moment to emit the final completed event, then close.
        serviceScope.launch {
            delay(1500)
            teardownRealtime()
            if (currentState == BubbleState.FINALIZING) {
                vibrateSuccess()
                updateBubbleState(BubbleState.IDLE)
            }
        }
    }

    private fun teardownRealtime() {
        realtimeClient?.close()
        realtimeClient = null
    }
```

- [ ] **Step 4: Update `handleBubbleClick()` for new states**

Replace `handleBubbleClick()` with:
```kotlin
    private fun handleBubbleClick() {
        when (currentState) {
            BubbleState.IDLE -> startRecording()
            BubbleState.RECORDING -> stopRecording()
            BubbleState.CONNECTING, BubbleState.FINALIZING, BubbleState.PROCESSING -> { /* ignore */ }
            BubbleState.ERROR -> updateBubbleState(BubbleState.IDLE)
        }
    }
```

- [ ] **Step 5: Extend the state enum and `updateBubbleState`**

Change the enum:
```kotlin
    enum class BubbleState {
        IDLE, CONNECTING, RECORDING, FINALIZING, PROCESSING, ERROR
    }
```

In `updateBubbleState`, add branches for `CONNECTING` and `FINALIZING` and expand/collapse the pill. Replace the `RECORDING` and `IDLE` branches and add the two new ones:
```kotlin
                BubbleState.IDLE -> {
                    bubbleIcon.visibility = View.VISIBLE
                    bubbleIcon.setImageResource(R.drawable.ic_mic)
                    waveformView.visibility = View.GONE
                    waveformView.stop()
                    setBubbleWidth(56)
                    bubbleContainer.setBackgroundResource(R.drawable.bubble_background_idle)
                    processingRing.visibility = View.GONE
                    processingRing.clearAnimation()
                    processingTimeText.visibility = View.GONE
                    stopProcessingTimer()
                    if (shouldHideOnIdle) {
                        shouldHideOnIdle = false
                        if (mediaDetector.isCurrentlyPlaying()) {
                            currentContext = BubbleContext.MEDIA_PLAYBACK; showBubbleForMedia()
                        } else {
                            currentContext = BubbleContext.NONE; hideBubble()
                        }
                    }
                }
                BubbleState.CONNECTING -> {
                    bubbleIcon.visibility = View.GONE
                    waveformView.visibility = View.GONE
                    processingRing.visibility = View.VISIBLE
                    bubbleContainer.setBackgroundResource(R.drawable.bubble_background_processing)
                    startRotationAnimation()
                }
                BubbleState.RECORDING -> {
                    bubbleIcon.visibility = View.GONE
                    processingRing.visibility = View.GONE
                    processingRing.clearAnimation()
                    setBubbleWidth(160)
                    waveformView.visibility = View.VISIBLE
                    waveformView.start()
                    bubbleContainer.setBackgroundResource(R.drawable.bubble_background_recording)
                    startPulseAnimation()
                }
                BubbleState.FINALIZING -> {
                    pulseAnimator?.cancel()
                    waveformView.stop()
                    waveformView.visibility = View.GONE
                    setBubbleWidth(56)
                    processingRing.visibility = View.VISIBLE
                    bubbleContainer.setBackgroundResource(R.drawable.bubble_background_processing)
                    startRotationAnimation()
                }
```

Leave the existing `PROCESSING` and `ERROR` branches in place (PROCESSING is now unused but harmless; ERROR already resets after 2s), but they reference the old method names — fixed in the next step.

- [ ] **Step 5b: Replace old waveform method names everywhere in the file**

`BarWaveformView` exposes `start()` / `stop()`, not the old `startAnimation()` / `stopAnimation()`. The existing `PROCESSING` and `ERROR` branches (and any other leftover references) still call the old names. Replace across the whole file:
- every `waveformView.stopAnimation()` → `waveformView.stop()`
- every `waveformView.startAnimation()` → `waveformView.start()`

Run to confirm none remain:
`git grep -n "waveformView.stopAnimation\|waveformView.startAnimation" app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt`
Expected: no output.

- [ ] **Step 6: Add the pill-width animator helper**

Add this method to the class (near `snapToEdge`):
```kotlin
    private fun setBubbleWidth(dp: Int) {
        val target = (dp * resources.displayMetrics.density).toInt()
        val lp = bubbleContainer.layoutParams
        if (lp.width == target) return
        ValueAnimator.ofInt(lp.width, target).apply {
            duration = 180
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                lp.width = it.animatedValue as Int
                bubbleContainer.layoutParams = lp
            }
            start()
        }
    }
```

- [ ] **Step 7: Update `onDestroy` and `createBubbleView`**

In `createBubbleView()`, the line `waveformView = bubbleView.findViewById(R.id.waveform_view)` stays valid (same id, new type). In `onDestroy()`, replace `audioRecorder.cleanup()` with:
```kotlin
        audioRecorder.stop()
        teardownRealtime()
```

- [ ] **Step 8: Build**

Run: `.\gradlew.bat assembleDebug`
Expected: BUILD SUCCESSFUL. (Remaining references to `WaveformView`/`WhisperApiService`/`AudioRecorder` are removed in Task 9.)

- [ ] **Step 9: On-device verification**

Install: `.\gradlew.bat installDebug`
Verify, in order:
1. Focus a chat text field, tap bubble → brief spinner → pill expands with live waveform.
2. Speak two sentences with a pause → each sentence appears in the field shortly after you finish it.
3. Tap to stop → pill collapses, brief spinner, success vibration, bubble returns to mic.
4. With NO field focused (home screen), repeat → text lands on the clipboard (toast confirms).
5. Enable airplane mode, tap bubble → ERROR state + toast; tap again returns to idle.

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt
git commit -m "feat: stream realtime transcription with live per-sentence injection"
```

---

## Task 9: Retire the batch path

Remove the now-dead batch transcription code and old waveform.

**Files:**
- Delete: `app/src/main/java/com/whispereverywhere/data/api/WhisperApiService.kt`
- Delete: `app/src/main/java/com/whispereverywhere/ui/components/WaveformView.kt`

- [ ] **Step 1: Confirm no remaining references**

Run: `git grep -n "WhisperApiService\|WaveformView\|TranscriptionResult\|AudioRecorder"` (PowerShell: `git grep -n "WhisperApiService"; git grep -n "WaveformView"`)
Expected: no hits in `app/src/main` other than the files about to be deleted. (`StreamingAudioRecorder` is a distinct name and is fine.)

- [ ] **Step 2: Delete the files**

```bash
git rm app/src/main/java/com/whispereverywhere/data/api/WhisperApiService.kt
git rm app/src/main/java/com/whispereverywhere/ui/components/WaveformView.kt
```

- [ ] **Step 3: Build and run unit tests**

Run: `.\gradlew.bat assembleDebug testDebugUnitTest`
Expected: BUILD SUCCESSFUL; all unit tests pass.

- [ ] **Step 4: Commit**

```bash
git commit -m "refactor: remove batch whisper transcription and legacy waveform"
```

---

## Done criteria

- Tapping the bubble streams audio live; sentences appear in the focused field as you speak, or on the clipboard if no field is focused.
- The pill-shaped waveform reacts to voice in real time.
- Manual stop finalizes cleanly; connection failure shows an error and retries on tap.
- `.\gradlew.bat testDebugUnitTest` passes; `.\gradlew.bat assembleDebug` succeeds.
