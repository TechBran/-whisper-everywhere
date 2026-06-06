# TTS "Read Aloud" — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a system-wide "Read aloud" action that appears in the text-selection toolbar (next to Copy/Cut); selecting it speaks the highlighted text in an HD OpenAI voice, controlled by a floating mini-player.

**Architecture:** A transparent `ReadAloudActivity` registered for `PROCESS_TEXT` receives selected text and starts `TtsService`. `TtsService` (foreground, `mediaPlayback`) calls `/v1/audio/speech` via `SpeechApiService`, plays the result with `MediaPlayer`, and shows a `TtsPlayerOverlay` (play/pause, stop, speed). Voice and default speed are chosen in Settings and persisted in `PreferencesManager`.

**Tech Stack:** Kotlin, Android, OkHttp (already a dependency), `MediaPlayer` (no new libs), Compose for the Settings dropdowns, JUnit 4.

**Spec:** `docs/superpowers/specs/2026-06-06-whisper-everywhere-v2-design.md`
**Prerequisite:** none on Plan 1 — this subsystem is independent and can ship separately.

---

## File structure

| File | Responsibility | Action |
|---|---|---|
| `app/src/main/java/com/whispereverywhere/data/api/SpeechApiService.kt` | Build + POST `/v1/audio/speech`, return audio bytes | Create |
| `app/src/main/java/com/whispereverywhere/service/ReadAloudActivity.kt` | PROCESS_TEXT entry point | Create |
| `app/src/main/java/com/whispereverywhere/service/TtsService.kt` | Foreground service: synthesize + play + overlay | Create |
| `app/src/main/java/com/whispereverywhere/ui/components/TtsPlayerOverlay.kt` | Floating mini-player (WindowManager) | Create |
| `app/src/main/res/layout/tts_player_overlay.xml` | Mini-player layout | Create |
| `app/src/main/java/com/whispereverywhere/data/local/PreferencesManager.kt` | Add voice + speed prefs and voice list | Modify |
| `app/src/main/java/com/whispereverywhere/ui/screens/SettingsScreen.kt` | Voice + speed + language dropdowns | Modify |
| `app/src/main/AndroidManifest.xml` | Register activity + service + permission | Modify |
| `app/src/main/res/values/strings.xml` | "Read aloud" label + TTS strings | Modify |
| `app/src/test/java/com/whispereverywhere/SpeechApiServiceTest.kt` | Unit tests for request body | Create |

**Test command (Windows):** `.\gradlew.bat testDebugUnitTest`
**Build command:** `.\gradlew.bat assembleDebug`

---

## Task 1: Speech request body (pure, TDD)

Isolate the request-body JSON so it is unit-testable without the network.

**Files:**
- Create: `app/src/main/java/com/whispereverywhere/data/api/SpeechApiService.kt`
- Test: `app/src/test/java/com/whispereverywhere/SpeechApiServiceTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/whispereverywhere/SpeechApiServiceTest.kt`:
```kotlin
package com.whispereverywhere

import com.whispereverywhere.data.api.SpeechApiService
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechApiServiceTest {

    @Test
    fun body_contains_model_voice_text_and_format() {
        val body = SpeechApiService.buildBody(
            text = "Hello there",
            voice = "nova",
            speed = 1.25f
        )
        assertTrue(body.contains("\"model\":\"gpt-4o-mini-tts\""))
        assertTrue(body.contains("\"voice\":\"nova\""))
        assertTrue(body.contains("\"input\":\"Hello there\""))
        assertTrue(body.contains("\"response_format\":\"mp3\""))
        assertTrue(body.contains("\"speed\":1.25"))
    }

    @Test
    fun body_escapes_quotes_in_text() {
        val body = SpeechApiService.buildBody(text = "say \"hi\"", voice = "alloy", speed = 1.0f)
        // Valid JSON escaping — the raw double quote must be backslash-escaped.
        assertTrue(body.contains("say \\\"hi\\\""))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.whispereverywhere.SpeechApiServiceTest"`
Expected: FAIL — `SpeechApiService` unresolved.

- [ ] **Step 3: Implement `SpeechApiService`**

Create `app/src/main/java/com/whispereverywhere/data/api/SpeechApiService.kt`:
```kotlin
package com.whispereverywhere.data.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** Calls OpenAI's text-to-speech endpoint and returns raw MP3 bytes. */
class SpeechApiService(private val apiKey: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    sealed class SpeechResult {
        data class Success(val mp3: ByteArray) : SpeechResult()
        data class Error(val message: String) : SpeechResult()
    }

    suspend fun synthesize(text: String, voice: String, speed: Float): SpeechResult =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) return@withContext SpeechResult.Error("API key not set")
            try {
                val request = Request.Builder()
                    .url(URL)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .post(buildBody(text, voice, speed).toRequestBody(JSON_MEDIA))
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val err = response.body?.string() ?: "Unknown error"
                        return@withContext when (response.code) {
                            401 -> SpeechResult.Error("Invalid API key")
                            429 -> SpeechResult.Error("Rate limit exceeded")
                            else -> SpeechResult.Error("TTS error (${response.code}): $err")
                        }
                    }
                    val bytes = response.body?.bytes()
                        ?: return@withContext SpeechResult.Error("Empty audio response")
                    SpeechResult.Success(bytes)
                }
            } catch (e: Exception) {
                SpeechResult.Error("TTS failed: ${e.message}")
            }
        }

    companion object {
        private const val URL = "https://api.openai.com/v1/audio/speech"
        private const val MODEL = "gpt-4o-mini-tts" // HD voice model
        private val JSON_MEDIA = "application/json".toMediaType()

        /** Pure builder so it can be unit-tested without the network. */
        fun buildBody(text: String, voice: String, speed: Float): String =
            buildJsonObject {
                put("model", MODEL)
                put("input", text)
                put("voice", voice)
                put("response_format", "mp3")
                // Round to 2 decimals; put(String, Number) serializes 1.25 as 1.25.
                put("speed", ((speed * 100).toInt() / 100.0))
            }.toString()
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.whispereverywhere.SpeechApiServiceTest"`
Expected: PASS (2 tests). If the `speed` assertion fails on formatting, confirm the value serializes as `1.25`; adjust the test's expected substring to the actual emitted form rather than weakening the builder.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/whispereverywhere/data/api/SpeechApiService.kt app/src/test/java/com/whispereverywhere/SpeechApiServiceTest.kt
git commit -m "feat: add SpeechApiService for OpenAI text-to-speech with tests"
```

---

## Task 2: Voice + speed preferences

Add persisted voice, default speed, and the voice list.

**Files:**
- Modify: `app/src/main/java/com/whispereverywhere/data/local/PreferencesManager.kt`

- [ ] **Step 1: Add state flows and accessors**

In `PreferencesManager`, after the `selectedLanguage` flow block (around line 70), add:
```kotlin
    private val _ttsVoice = MutableStateFlow(prefs.getString(KEY_TTS_VOICE, "alloy") ?: "alloy")
    val ttsVoice: StateFlow<String> = _ttsVoice.asStateFlow()

    private val _ttsSpeed = MutableStateFlow(prefs.getFloat(KEY_TTS_SPEED, 1.0f))
    val ttsSpeed: StateFlow<Float> = _ttsSpeed.asStateFlow()

    fun setTtsVoice(voice: String) {
        prefs.edit().putString(KEY_TTS_VOICE, voice).apply()
        _ttsVoice.value = voice
    }

    fun getTtsVoice(): String = _ttsVoice.value

    fun setTtsSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.25f, 4.0f)
        prefs.edit().putFloat(KEY_TTS_SPEED, clamped).apply()
        _ttsSpeed.value = clamped
    }

    fun getTtsSpeed(): Float = _ttsSpeed.value
```

- [ ] **Step 2: Add the keys and voice list to the companion object**

In the `companion object`, add the keys next to the existing ones:
```kotlin
        private const val KEY_TTS_VOICE = "tts_voice"
        private const val KEY_TTS_SPEED = "tts_speed"
```
and add the voice list (after `SUPPORTED_LANGUAGES`):
```kotlin
        // OpenAI TTS voices (gpt-4o-mini-tts)
        val TTS_VOICES = listOf(
            "alloy", "ash", "ballad", "coral", "echo",
            "fable", "nova", "onyx", "sage", "shimmer", "verse"
        )

        // Selectable playback speeds (default + mini-player toggle order)
        val TTS_SPEEDS = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
```

- [ ] **Step 3: Build**

Run: `.\gradlew.bat assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/whispereverywhere/data/local/PreferencesManager.kt
git commit -m "feat: persist TTS voice and speed preferences"
```

---

## Task 3: Mini-player overlay

A floating control with play/pause, speed, and stop. It exposes callbacks; `TtsService` owns it.

**Files:**
- Create: `app/src/main/res/layout/tts_player_overlay.xml`
- Create: `app/src/main/java/com/whispereverywhere/ui/components/TtsPlayerOverlay.kt`

- [ ] **Step 1: Create the layout**

Create `app/src/main/res/layout/tts_player_overlay.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:id="@+id/tts_player_root"
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    android:orientation="horizontal"
    android:gravity="center_vertical"
    android:background="@drawable/bubble_background_idle"
    android:elevation="8dp"
    android:padding="8dp">

    <ImageView
        android:id="@+id/tts_play_pause"
        android:layout_width="40dp"
        android:layout_height="40dp"
        android:padding="6dp"
        android:src="@drawable/ic_mic"
        android:contentDescription="Play or pause" />

    <TextView
        android:id="@+id/tts_speed"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:minWidth="44dp"
        android:gravity="center"
        android:text="1x"
        android:textColor="#FFFFFF"
        android:textStyle="bold"
        android:textSize="14sp"
        android:paddingHorizontal="8dp" />

    <ImageView
        android:id="@+id/tts_stop"
        android:layout_width="40dp"
        android:layout_height="40dp"
        android:padding="6dp"
        android:src="@drawable/ic_close"
        android:contentDescription="Stop" />

</LinearLayout>
```

> Uses existing drawables `bubble_background_idle`, `ic_mic`, `ic_close`. A dedicated play/pause icon is a nice-to-have; the play/pause control swaps `ic_mic`/`ic_close` tint at minimum and is functional as-is.

- [ ] **Step 2: Create the overlay controller**

Create `app/src/main/java/com/whispereverywhere/ui/components/TtsPlayerOverlay.kt`:
```kotlin
package com.whispereverywhere.ui.components

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import com.whispereverywhere.R

/**
 * Floating mini-player for TTS playback. The owning service supplies callbacks
 * and pushes state in via [setPlaying] / [setSpeedLabel].
 */
class TtsPlayerOverlay(
    private val context: Context,
    private val onPlayPause: () -> Unit,
    private val onCycleSpeed: () -> Unit,
    private val onStop: () -> Unit,
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: View? = null
    private var playPause: ImageView? = null
    private var speedLabel: TextView? = null

    fun show() {
        if (view != null) return
        val root = LayoutInflater.from(context).inflate(R.layout.tts_player_overlay, null)
        playPause = root.findViewById(R.id.tts_play_pause)
        speedLabel = root.findViewById(R.id.tts_speed)
        root.findViewById<ImageView>(R.id.tts_stop).setOnClickListener { onStop() }
        playPause?.setOnClickListener { onPlayPause() }
        speedLabel?.setOnClickListener { onCycleSpeed() }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = (96 * context.resources.displayMetrics.density).toInt()
        }
        windowManager.addView(root, params)
        view = root
    }

    fun setPlaying(playing: Boolean) {
        playPause?.setImageResource(if (playing) R.drawable.ic_close else R.drawable.ic_mic)
        // (ic_close used as a stand-in "pause" glyph; swap for a real pause icon if added.)
    }

    fun setSpeedLabel(label: String) { speedLabel?.text = label }

    fun remove() {
        view?.let {
            try { windowManager.removeView(it) } catch (e: Exception) { e.printStackTrace() }
        }
        view = null
    }
}
```

- [ ] **Step 3: Build**

Run: `.\gradlew.bat assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/res/layout/tts_player_overlay.xml app/src/main/java/com/whispereverywhere/ui/components/TtsPlayerOverlay.kt
git commit -m "feat: add TTS mini-player overlay"
```

---

## Task 4: TtsService (synthesize + play + control)

Foreground service that fetches speech, plays it via `MediaPlayer`, and wires the overlay. Speed changes apply live via `PlaybackParams`.

**Files:**
- Create: `app/src/main/java/com/whispereverywhere/service/TtsService.kt`

- [ ] **Step 1: Implement `TtsService`**

Create `app/src/main/java/com/whispereverywhere/service/TtsService.kt`:
```kotlin
package com.whispereverywhere.service

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.whispereverywhere.R
import com.whispereverywhere.WhisperEverywhereApp
import com.whispereverywhere.data.api.SpeechApiService
import com.whispereverywhere.ui.components.TtsPlayerOverlay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class TtsService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val app by lazy { WhisperEverywhereApp.getInstance() }

    private var mediaPlayer: MediaPlayer? = null
    private var overlay: TtsPlayerOverlay? = null
    private var speed = 1.0f
    private var audioFile: File? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopEverything(); return START_NOT_STICKY }

        val text = intent?.getStringExtra(EXTRA_TEXT)?.trim()
        if (text.isNullOrEmpty()) { stopSelf(); return START_NOT_STICKY }

        startForegroundCompat()
        speed = app.preferencesManager.getTtsSpeed()
        showOverlay()
        synthesizeAndPlay(text)
        return START_NOT_STICKY
    }

    private fun synthesizeAndPlay(text: String) {
        val voice = app.preferencesManager.getTtsVoice()
        val service = SpeechApiService(app.preferencesManager.apiKey)
        scope.launch {
            when (val result = service.synthesize(text, voice, speed)) {
                is SpeechApiService.SpeechResult.Success -> playBytes(result.mp3)
                is SpeechApiService.SpeechResult.Error -> {
                    toast(result.message); stopEverything()
                }
            }
        }
    }

    private suspend fun playBytes(mp3: ByteArray) {
        val file = withContext(Dispatchers.IO) {
            File(cacheDir, "tts_${System.currentTimeMillis()}.mp3").apply { writeBytes(mp3) }
        }
        audioFile = file
        mediaPlayer = MediaPlayer().apply {
            setDataSource(file.absolutePath)
            setOnPreparedListener {
                playbackParams = PlaybackParams().setSpeed(speed)
                start()
                overlay?.setPlaying(true)
            }
            setOnCompletionListener { stopEverything() }
            setOnErrorListener { _, _, _ -> stopEverything(); true }
            prepareAsync()
        }
    }

    private fun togglePlayPause() {
        val mp = mediaPlayer ?: return
        if (mp.isPlaying) { mp.pause(); overlay?.setPlaying(false) }
        else { mp.start(); overlay?.setPlaying(true) }
    }

    private fun cycleSpeed() {
        val speeds = com.whispereverywhere.data.local.PreferencesManager.TTS_SPEEDS
        val idx = speeds.indexOfFirst { it == speed }.let { if (it < 0) 1 else it }
        speed = speeds[(idx + 1) % speeds.size]
        overlay?.setSpeedLabel(speedLabel(speed))
        val mp = mediaPlayer
        if (mp != null && mp.isPlaying) {
            mp.playbackParams = PlaybackParams().setSpeed(speed)
        }
    }

    private fun showOverlay() {
        overlay = TtsPlayerOverlay(
            context = this,
            onPlayPause = ::togglePlayPause,
            onCycleSpeed = ::cycleSpeed,
            onStop = ::stopEverything
        ).also {
            it.show()
            it.setSpeedLabel(speedLabel(speed))
        }
    }

    private fun speedLabel(s: Float): String =
        if (s == s.toInt().toFloat()) "${s.toInt()}x" else "${s}x"

    private fun stopEverything() {
        try { mediaPlayer?.stop() } catch (e: Exception) { /* ignore */ }
        mediaPlayer?.release(); mediaPlayer = null
        overlay?.remove(); overlay = null
        audioFile?.delete(); audioFile = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun toast(msg: String) {
        scope.launch(Dispatchers.Main) {
            android.widget.Toast.makeText(applicationContext, msg, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun startForegroundCompat() {
        val notification: Notification =
            NotificationCompat.Builder(this, WhisperEverywhereApp.NOTIFICATION_CHANNEL_ID)
                .setContentTitle("Reading aloud")
                .setContentText("Tap the player to control playback")
                .setSmallIcon(R.drawable.ic_notification)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    companion object {
        private const val NOTIFICATION_ID = 4242
        const val EXTRA_TEXT = "com.whispereverywhere.tts.TEXT"
        const val ACTION_STOP = "com.whispereverywhere.tts.STOP"

        fun speak(context: Context, text: String) {
            val intent = Intent(context, TtsService::class.java).putExtra(EXTRA_TEXT, text)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }
    }
}
```

- [ ] **Step 2: Build**

Run: `.\gradlew.bat assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/whispereverywhere/service/TtsService.kt
git commit -m "feat: add TtsService (synthesize, play, live speed, overlay)"
```

---

## Task 5: ReadAloudActivity (PROCESS_TEXT entry point)

A transparent activity that grabs the selected text and starts `TtsService`.

**Files:**
- Create: `app/src/main/java/com/whispereverywhere/service/ReadAloudActivity.kt`

- [ ] **Step 1: Implement the activity**

Create `app/src/main/java/com/whispereverywhere/service/ReadAloudActivity.kt`:
```kotlin
package com.whispereverywhere.service

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import com.whispereverywhere.WhisperEverywhereApp

/**
 * Transparent entry point for the system "Read aloud" text-selection action.
 * Reads the selected text and hands it to TtsService, then finishes immediately.
 */
class ReadAloudActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val selected = intent
            ?.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
            ?.toString()
            ?.trim()
            .orEmpty()

        val app = WhisperEverywhereApp.getInstance()
        when {
            selected.isEmpty() ->
                Toast.makeText(this, "No text selected", Toast.LENGTH_SHORT).show()
            !app.preferencesManager.hasApiKey() ->
                Toast.makeText(this, "Set your OpenAI API key in Settings", Toast.LENGTH_LONG).show()
            !Settings.canDrawOverlays(this) ->
                Toast.makeText(this, "Grant overlay permission to use Read aloud", Toast.LENGTH_LONG).show()
            else ->
                TtsService.speak(this, selected)
        }
        finish()
    }
}
```

- [ ] **Step 2: Build**

Run: `.\gradlew.bat assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/whispereverywhere/service/ReadAloudActivity.kt
git commit -m "feat: add ReadAloudActivity PROCESS_TEXT entry point"
```

---

## Task 6: Manifest + strings registration

Register the activity (so it appears in the selection toolbar), the service, and the media-playback foreground permission.

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/res/values/strings.xml`

- [ ] **Step 1: Add the foreground media permission**

In `AndroidManifest.xml`, after the `FOREGROUND_SERVICE_MICROPHONE` permission line, add:
```xml
    <!-- Required for foregroundServiceType="mediaPlayback" on Android 14+ -->
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
```

- [ ] **Step 2: Register the ReadAloud activity**

Inside `<application>`, after the `MainActivity` `</activity>` block, add:
```xml
        <!-- Read Aloud: appears in the system text-selection toolbar (Copy/Cut/Read aloud) -->
        <activity
            android:name=".service.ReadAloudActivity"
            android:exported="true"
            android:label="@string/read_aloud_label"
            android:theme="@android:style/Theme.Translucent.NoTitleBar"
            android:excludeFromRecents="true"
            android:noHistory="true">
            <intent-filter>
                <action android:name="android.intent.action.PROCESS_TEXT" />
                <category android:name="android.intent.category.DEFAULT" />
                <data android:mimeType="text/plain" />
            </intent-filter>
        </activity>
```

- [ ] **Step 3: Register TtsService**

After the `FloatingBubbleService` `<service .../>` declaration, add:
```xml
        <!-- Text-to-Speech playback service -->
        <service
            android:name=".service.TtsService"
            android:exported="false"
            android:foregroundServiceType="mediaPlayback" />
```

- [ ] **Step 4: Add the strings**

In `strings.xml`, inside `<resources>`, add:
```xml
    <!-- Text to Speech -->
    <string name="read_aloud_label">Read aloud</string>
    <string name="tts_voice_title">Voice</string>
    <string name="tts_speed_title">Speech speed</string>
    <string name="language_title">Transcription language</string>
```

- [ ] **Step 5: Build**

Run: `.\gradlew.bat assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/AndroidManifest.xml app/src/main/res/values/strings.xml
git commit -m "feat: register Read aloud action, TtsService, and media permission"
```

---

## Task 7: Settings dropdowns (voice, speed, language)

Expose voice + default speed + transcription language in a new "Voice & Speech" section.

**Files:**
- Modify: `app/src/main/java/com/whispereverywhere/ui/screens/SettingsScreen.kt`

- [ ] **Step 1: Add a reusable dropdown composable**

At the end of `SettingsScreen.kt` (after `SettingsSwitchItem`), add:
```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDropdownItem(
    icon: ImageVector,
    title: String,
    options: List<Pair<String, String>>, // value to label
    selectedValue: String,
    onSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.first == selectedValue }?.second ?: selectedValue

    Box {
        SettingsItem(
            icon = icon,
            title = title,
            subtitle = selectedLabel,
            onClick = { expanded = true }
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (value, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = { onSelected(value); expanded = false }
                )
            }
        }
    }
}
```

- [ ] **Step 2: Collect the new state at the top of `SettingsScreen`**

After `val vibrationEnabled by app.preferencesManager.vibrationEnabled.collectAsState()`, add:
```kotlin
    val ttsVoice by app.preferencesManager.ttsVoice.collectAsState()
    val ttsSpeed by app.preferencesManager.ttsSpeed.collectAsState()
    val selectedLanguage by app.preferencesManager.selectedLanguage.collectAsState()
```

- [ ] **Step 3: Add the "Voice & Speech" section**

Immediately after the existing `SettingsSection(title = "Preferences") { ... }` block, add:
```kotlin
            SettingsSection(title = "Voice & Speech") {
                SettingsDropdownItem(
                    icon = Icons.Filled.RecordVoiceOver,
                    title = "Voice (Read aloud)",
                    options = com.whispereverywhere.data.local.PreferencesManager.TTS_VOICES
                        .map { it to it.replaceFirstChar { c -> c.uppercase() } },
                    selectedValue = ttsVoice,
                    onSelected = { app.preferencesManager.setTtsVoice(it) }
                )
                SettingsDropdownItem(
                    icon = Icons.Filled.Speed,
                    title = "Speech speed",
                    options = com.whispereverywhere.data.local.PreferencesManager.TTS_SPEEDS
                        .map { it.toString() to (if (it == it.toInt().toFloat()) "${it.toInt()}x" else "${it}x") },
                    selectedValue = ttsSpeed.toString(),
                    onSelected = { app.preferencesManager.setTtsSpeed(it.toFloat()) }
                )
                SettingsDropdownItem(
                    icon = Icons.Filled.Language,
                    title = "Transcription language",
                    options = com.whispereverywhere.data.local.PreferencesManager.SUPPORTED_LANGUAGES,
                    selectedValue = selectedLanguage,
                    onSelected = { app.preferencesManager.setSelectedLanguage(it) }
                )
            }
```

> Icons `RecordVoiceOver`, `Speed`, `Language` are in `material-icons-extended` (already a dependency) and resolve via the existing `import androidx.compose.material.icons.filled.*`.

- [ ] **Step 4: Build**

Run: `.\gradlew.bat assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: On-device verification**

Install: `.\gradlew.bat installDebug`
1. Open Settings → "Voice & Speech" → pick a voice (e.g. "Nova") and speed (e.g. "1.25x").
2. In any app, select some text → tap the overflow (⋮) in the selection toolbar → tap **Read aloud**.
3. Mini-player appears at the bottom; audio plays in the chosen voice.
4. Tap speed → playback speed changes live; tap play/pause; tap stop → overlay disappears and audio stops.
5. With no API key set, Read aloud shows the "set API key" toast instead of playing.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/whispereverywhere/ui/screens/SettingsScreen.kt
git commit -m "feat: add voice, speed, and language dropdowns to Settings"
```

---

## Done criteria

- Selecting text anywhere shows a "Read aloud" action that speaks it in the chosen HD voice.
- The mini-player controls play/pause, live speed, and stop.
- Voice, default speed, and transcription language are configurable in Settings and persist.
- `.\gradlew.bat testDebugUnitTest` passes; `.\gradlew.bat assembleDebug` succeeds.
