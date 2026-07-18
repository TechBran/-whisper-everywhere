# Play Console Declarations — Whisper Everywhere

Everything the Play Console review will ask for, in one place. Copy the declaration texts
verbatim (edit tone as needed); the technical claims in them are true of the current code and
must be kept true.

## 1. AccessibilityService API declaration

- Manifest posture: `android:isAccessibilityTool="false"` (declared explicitly in
  `accessibility_service_config.xml`). The app is a productivity/dictation tool, not primarily
  an assistive tool, so Play's *prominent disclosure* requirement applies — and is implemented:
  the HomeScreen dialog (shown BEFORE routing to accessibility settings) states what is
  accessed, why, and that nothing is collected, stored, or shared, with explicit
  consent/cancel buttons.
- Console form — "Why does your app use the AccessibilityService API?":

  > Whisper Everywhere transcribes the user's speech entirely on-device and types the result
  > into the text field the user is dictating into. The AccessibilityService API is used for
  > exactly two things: (1) detecting that an editable text field is focused so the floating
  > microphone bubble can appear, and (2) inserting the transcribed text into that field
  > (ACTION_SET_TEXT / clipboard paste). The service reads only the focused input node's
  > text/selection state to place the cursor correctly. No screen content is collected,
  > logged, stored, or transmitted; the app has no analytics and no server — audio and text
  > never leave the device. A prominent disclosure with explicit consent is shown in-app
  > before the user is directed to enable the service.

- Core functionality justification: text injection IS the product (system-wide voice typing);
  no narrower API can insert text into other apps' fields without replacing the user's IME.

## 2. Foreground service — `microphone`

- Declared type: `microphone` on `FloatingBubbleService`; permission
  `FOREGROUND_SERVICE_MICROPHONE` (Android 14+).
- Console asks for a demo video: record the Fold 6 screen showing — enable bubble → focus a
  text field → tap bubble → dictate → text appears in the field → notification visible while
  recording. Keep it under ~30 s.
- Declaration text:

  > The foreground service captures microphone audio while the user is actively dictating
  > (started by an explicit tap on the floating bubble) and transcribes it on-device. The
  > service must survive the user switching to the target app (that is where the text is
  > typed), which is only possible as a foreground service with the microphone type.

## 3. Foreground service — `mediaProjection`

- Declared type: `mediaProjection` on the same service; used ONLY for AudioPlaybackCapture
  (transcribing device media audio — YouTube, podcasts). The mic is fully stopped during
  playback capture. Consent is the system MediaProjection dialog, requested lazily per grant.
- Declaration text:

  > With the user's explicit consent via the system screen-capture dialog, the service
  > captures the device's media audio output (AudioPlaybackCapture) to transcribe videos or
  > podcasts the user is playing. No video/screen content is captured — audio only — and the
  > microphone is switched off for the duration. Transcription is entirely on-device.

## 4. Notification access (NotificationListenerService)

- `MediaNotificationListener` exists solely because `MediaSessionManager.getActiveSessions()`
  and `addOnActiveSessionsChangedListener()` require an enabled notification-listener
  component. The listener callback bodies read NO notification content (verified: only a
  connection flag is set). Used to detect that media playback started/stopped so the bubble
  can offer media transcription and hand over mic→stream mid-session.
- If Play questions it, the alternative (polling `AudioManager.isMusicActive`) already exists
  as a fallback but cannot identify the playing package or state transitions reliably.

## 5. Data safety form

- Data collected: **none**. Data shared: **none**. All processing on-device.
- The `INTERNET` permission exists only to download the whisper model (one-time, from a pinned
  Hugging Face commit URL); no audio, text, or telemetry is ever uploaded.
- Advertising ID: removed via `tools:node="remove"` in the manifest — declare "app does not
  use advertising ID".
- Transcription history is stored in app-private storage (`filesDir/transcripts`), text-only,
  auto-pruned (14 days / 10 MB), deletable in-app; it never leaves the device. Declare under
  "data stored on device" narrative if asked, but it is NOT "collected" in Play's sense
  (never transmitted off device).

## 6. Odds and ends

- Crash reporting: none (no SDK). Play vitals native symbolication works via
  `debugSymbolLevel = "SYMBOL_TABLE"` in the AAB.
- Release builds strip ALL `android.util.Log` calls (R8 `-assumenosideeffects`), so no
  operational logging ships either.
- Target API 35; edge-to-edge enforced and handled (Material3 Scaffold insets).
