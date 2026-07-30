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
  > exactly three things: (1) detecting that an editable text field is focused so the floating
  > microphone bubble can appear, (2) inserting the transcribed text into that field
  > (ACTION_SET_TEXT / clipboard paste), and (3) reading the text the user has highlighted so
  > that, ONLY when the user taps the speaker bubble, it can be spoken aloud (user-initiated
  > read-aloud; the app never acts autonomously) — by the on-device voice by default, or by a
  > cloud voice the user has independently selected for read-aloud (see Section 5). The service
  > reads only the focused input node's text/selection state. No screen content is collected,
  > logged, stored, or transmitted; the app has no analytics and no server. By default, audio and
  > text never leave the device; the exceptions are dictated audio and, separately, text selected
  > for read-aloud, each of which — starting the release that adds it — can be sent off-device,
  > but only if the user has independently added their own API key for a cloud provider AND
  > explicitly selected that provider as their transcription engine or read-aloud voice
  > respectively (see Section 5). This AccessibilityService itself plays no part in either
  > choice and never sees or transmits screen content either way. A prominent disclosure with
  > explicit consent is shown in-app before the user is directed to enable the service. (A
  > permissionless ACTION_PROCESS_TEXT "Speak" toolbar entry provides the same read-aloud,
  > cloud voice included, without accessibility involvement.)

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
  > (started by an explicit tap on the floating bubble) and transcribes it — on-device by
  > default, or via a cloud provider the user has independently added a key for and selected
  > as their transcription engine (see Section 5), with on-device transcription as the automatic
  > fallback if that provider fails. The service must survive the user switching to the target
  > app (that is where the text is typed), which is only possible as a foreground service with
  > the microphone type.

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

**As of Release C2a, this is the first release in which audio actually leaves the device** — the
declaration below must ship in the SAME release as the code that makes it true, not a follow-up.

**Release ledger — Cloud TTS voice picker (2026-07-30):** three cloud voice providers (OpenAI,
Google Gemini, ElevenLabs) behind the same one-way local-fallback philosophy as cloud STT;
in-app disclosure bumped `cloud_disclosure_accepted_v2` -> `_v3` (meaning changed, so it
re-prompts everyone, v2 acceptance stays in the store unused); both privacy-policy copies' §6
read-aloud carve-out flipped from "not part of this release" to the two-step qualified form; and
Data Safety gains the "Other user-generated content" entry below (Shared = Yes) for text the
user selects to be read aloud through a cloud voice.

- **Audio files → Voice or sound recordings:** Collected **Yes**, Shared **Yes**, purpose
  **App functionality**, **Optional**. The user must take two independent, deliberate actions
  before any audio is shared: add their own API key for a cloud provider, AND select that
  provider as the active transcription engine in the app's Cloud Providers settings. On-device
  transcription remains fully functional — and the default — with neither action taken.
- **Other user-generated content → text you select for read-aloud:** Collected **Yes**, Shared
  **Yes**, purpose **App functionality**, **Optional**. This is a NEW shared data type as of the
  cloud TTS voice picker release — read-aloud text did not leave the device before. Shared only
  when the user takes two deliberate actions — save a provider key AND select a cloud voice for
  read-aloud — after accepting disclosure v3. On-device read-aloud remains the default with
  neither action taken. The ephemeral-processing exemption is **NOT** claimed (same reasoning as
  audio: the third-party provider sets its own retention). **Determination made 2026-07-30:
  category "Other user-generated content", Shared = Yes, Optional** — chosen over "Other in-app
  messages" because selected read-aloud text is arbitrary user-selected content, not a message
  to another person.
- **The ephemeral-processing exemption is NOT claimed.** That exemption is available only when
  the recipient retains the shared data no longer than necessary to service the user's real-time
  request and does not use it for any other purpose. Whichever provider the user configures
  (OpenAI, Google Gemini, ElevenLabs) sets its own retention and training practices — see the
  privacy policy for how they differ — and this app has no way to bind, verify, or assert that
  behavior on a third party's behalf. Declare Shared = Yes without applying the exemption.
- **MediaProjection device audio (the media-transcription feature — podcasts, videos, other
  apps' playback) is never sent to a cloud provider, under any configuration.** It is
  transcribed on-device only; see Section 3.
- **On-device transcription remains the default.** No key, no account, and no network (beyond
  the one-time model download) are required to use the app; cloud transcription is strictly
  additive and opt-in.
- The `INTERNET` permission is used to download the whisper model (one-time, from a pinned
  Hugging Face commit URL) and, only if the user has configured a cloud provider, to verify a
  newly-added key once, to send dictated audio to the provider the user selected for
  transcription, and — only if the user has also accepted disclosure v3 and selected a cloud
  voice for read-aloud — to send the text they select to be read aloud to that same provider. No
  telemetry or analytics is ever uploaded — the app has no analytics SDK and no server of its
  own.
- Advertising ID: removed via `tools:node="remove"` in the manifest — declare "app does not
  use advertising ID".
- Transcription history is stored in app-private storage (`filesDir/transcripts`), text-only,
  auto-pruned (14 days / 10 MB), deletable in-app; it never leaves the device, regardless of
  which transcription engine produced it. Declare under "data stored on device" narrative if
  asked, but it is NOT "collected" in Play's sense (never transmitted off device by us).
- Provider API keys are stored only on-device, encrypted via the Android Keystore, and are
  never collected by us — the only transmission is the user's own device sending the key to the
  provider they chose, under that provider's own account and terms.

## 6. Odds and ends

- Crash reporting: none (no SDK). Play vitals native symbolication works via
  `debugSymbolLevel = "SYMBOL_TABLE"` in the AAB.
- Release builds strip ALL `android.util.Log` calls (R8 `-assumenosideeffects`), so no
  operational logging ships either.
- Target API 35; edge-to-edge enforced and handled (Material3 Scaffold insets).
