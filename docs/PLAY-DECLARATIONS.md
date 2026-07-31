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

**Release ledger — C4 live transcribe (2026-07-30):** a third selectable STT mode, "Cloud
word-for-word (OpenAI)", streams mic audio to OpenAI's Realtime WebSocket
(`gpt-live-transcribe`) instead of the existing batch POST, so partial text renders as the user
speaks. **Determination: this sends the SAME mic audio to the SAME provider (OpenAI) already
covered by disclosure v3** (dictated-audio-to-provider, gated behind the existing key +
provider-selection + disclosure triad) — it adds NO new data class, only a new *transport*
(WebSocket vs POST) and a new *price tier* (about $0.017/min, roughly 4x the batch rate, since
per-word streaming costs more than a single batch call). Therefore **NO disclosure version
bump, NO re-prompt, and NO Data Safety form change** — the Audio files entry below already
covers this traffic; live mode does not add or alter a collected/shared data type. The only new
user-facing surface is the mode-selector row's price note (the honest cost disclosure — no
speed claims are made anywhere in the copy, per the same triad gate as batch cloud STT). Ledger
entry: **C4 live transcribe: transport+price only, v3 unchanged, price surfaced on selector.**

**Release ledger — Soniox STT provider (2026-07-31):** a fourth selectable cloud STT provider
(Soniox, `api.soniox.com`) joins OpenAI, Google Gemini, and ElevenLabs behind the identical
key + provider-selection + disclosure-v3 triad. **Determination: this sends the SAME data class
already declared — dictated (and user-chosen file) audio, to a cloud provider under the user's own
account** — to one additional recipient. It adds NO new Data Safety data class (still Audio files →
Voice or sound recordings, Shared = Yes, Optional) and NO new shared type; it does NOT alter the
read-aloud text entry (Soniox ships STT-only this wave — `supportsTts = false`). Therefore **NO
disclosure version bump and NO re-prompt** (same audio-to-a-provider meaning as v3), but the set of
recipients grows, so both privacy-policy copies' §6 enumeration and the exemption note above are
updated to name Soniox as a fourth recipient. Soniox's retention profile differs (the async path
stores audio + transcript server-side until the adapter DELETEs them after each transcription) and
is disclosed on its own privacy-policy line. Ledger entry: **Soniox STT: fourth audio recipient,
same Audio-files class, no new class, v3 unchanged; Console Data Safety narrative names all four.**

**Release ledger — Batch all-providers + VAD bypass (2026-07-31):** batch cloud STT widened from
OpenAI-only to all four providers (identical gates); per-provider chunk ceilings (from each
adapter's base64-aware maxRequestBytes) and per-provider "about" pricing (UNKNOWN -> most-expensive-
known for the confirm decision only). The batch on-device path now bypasses the Silero VAD so a
user-chosen file is transcribed in full; LIVE dictation's VAD is unchanged. No new recipient, no
disclosure-version change (same audio-to-a-provider meaning under v3). Two audit Minors folded in:
reconnect-scheduler shutdown; honest 3.3.0 INTERNET manifest comment.

**Release ledger — Realtime all-providers (2026-07-31):** live word-for-word widened from OpenAI-only
to every streaming-capable BYOK provider (OpenAI, ElevenLabs, Soniox) via a per-provider
RealtimeProtocol seam; OpenAI's wire is byte-identical (regression contract held). ElevenLabs =
xi-api-key header + 16 kHz base64 + commit-on-last-chunk with single-in-flight serialization; Soniox =
config-message key under the no-log discipline + raw s16le binary + client-assembled turns +
finalize/rotate under the reconnect ceiling. Gemini stays segment-only (no client realtime path), no
apology copy. Deltas never inject; mic-only via SourceRouted; Fallback(live, local) preserved. No new
recipient, no disclosure-version change (same audio-to-a-provider meaning under v3). Per-provider
"about" prices: OpenAI $0.017/min, ElevenLabs $0.007/min, Soniox $0.002/min. No speed claims.

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
  (OpenAI, Google Gemini, ElevenLabs, Soniox) sets its own retention and training practices — see the
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

## 7. 3.3.0 Console checklist

Everything that must be set, by hand, in the Play Console for the 3.3.0 rollout. Do all of it in the
SAME release as the AAB (versionCode 73 / versionName 3.3.0) — the Data Safety form, the storefront,
and the APK are diffed against each other, so a half-applied change is itself a rejection.

### Target API level — ALREADY SATISFIED, no action

The Console banner "App must target Android 16 (API level 36) or higher … from Aug 30, 2026" is
Google's standing advisory to all developers. This app has targeted API 36 SINCE 3.2.0 (Release B,
shipped July 2026, done specifically for this deadline); 3.3.0 carries `targetSdk = 36` /
`compileSdk = 36` (`app/build.gradle.kts:22,39`). Verified again 2026-07-31 against the owner's
Console notice. Nothing to change — recorded here so the banner is never mistaken for an open item.

### Data Safety — exact answers to set

Two shared data types are ON this release; both Optional; the ephemeral-processing exemption is NOT
claimed for either (every provider sets its own retention/training and the app cannot bind a third
party's behavior):

- **Audio files → Voice or sound recordings** — Collected **Yes**, Shared **Yes**, purpose **App
  functionality**, **Optional (users can use the app without it)**. First true as of C2a cloud STT.
  Two deliberate user actions gate every upload: add your own provider key AND select that provider
  as the transcription engine. On-device transcription is the default with neither done.
- **Other user-generated content → text you select for read-aloud** — Collected **Yes**, Shared
  **Yes**, purpose **App functionality**, **Optional**. New with the cloud-TTS voice-picker wave —
  read-aloud text did not leave the device before. Category chosen deliberately as "Other
  user-generated content" over "Other in-app messages" (selected text is arbitrary user content, not
  a message to a person). Gated by the same two actions — save a provider key AND pick a cloud voice
  — after accepting disclosure v3.
- **Ephemeral-processing exemption: do NOT claim it** on either type. Declare Shared = Yes plainly.
- **Advertising ID: not used** (removed via `tools:node="remove"`).
- **On-device transcription history** is text-only, app-private, auto-pruned, never transmitted — it
  is NOT "collected" in Play's sense; mention it only under the on-device narrative if asked.
- **Recipient narrative must name all four STT providers** (OpenAI, Google Gemini, ElevenLabs,
  Soniox) and the three TTS voice providers (OpenAI, Google Gemini, ElevenLabs) — the storefront,
  privacy policy §6, and this form must enumerate the same set. MediaProjection device audio is
  never sent to any of them (Section 3); say so if a reviewer probes the media-transcription feature.

### Corrections the release audit's compliance lens made (the Console copy must match these)

Fixed in code/docs during the 3.3.0 sweep; set the Console to the corrected reality, not the
pre-audit claims:
- **Soniox** is now enumerated as the fourth cloud STT recipient in privacy §6 (both copies) and in
  the §5 Data Safety recipient list. It is STT-only this wave (`supportsTts = false`) — do NOT list
  it as a read-aloud voice. Its retention differs (async path stores audio + transcript server-side
  until the adapter DELETEs them after each job) and has its own privacy-policy line.
- **No disclosure-version bump for Soniox or for C4 live transcribe.** Both are the same
  audio-to-a-provider meaning already covered by disclosure v3 (Soniox = one more recipient; C4 = a
  new WebSocket transport + price tier only). No re-prompt, no Data Safety change from either.
- **Live word-for-word is no longer OpenAI-only.** The mode-selector row now offers three providers
  (OpenAI, ElevenLabs, Soniox) — whichever streaming-capable provider is the globally selected STT
  engine, each behind its own per-provider price. Gemini still shows no live row (it has no
  client-usable realtime path — a provider limitation, not a defect) and gets no apology copy. Same
  audio-to-a-provider meaning already covered by disclosure v3; no re-prompt, no Data Safety change.
- **Home usage-stats footer** no longer claims "runs entirely on-device / no usage limits" to cloud
  users; no storefront "no limits" copy may contradict the honest per-engine footer.
- **Batch cloud STT now offers all four providers** (OpenAI, Gemini, ElevenLabs, Soniox) — the
  same set as live dictation, behind the identical triad + disclosure v3 + cost confirm +
  notifications, per job. The batch screen's cloud row and price reflect the GLOBALLY SELECTED
  STT provider (not always OpenAI). The §5/§6 recipient narrative already names all four for
  dictation — batch now uses the same recipients, so no recipient-list change is needed; confirm
  the batch sentence no longer implies OpenAI-only.

### Listing-copy step (BOTH copies, one release)

- Pick ONE PLAY-LISTING.md DRAFT variant (A defensive / B feature-forward), reconcile its wording
  with this doc, then edit the LIVE storefront to match: short description, full-description opener +
  privacy bullets + read-aloud pillar, and the feature-graphic subline. The live 3.2.0 "100%
  on-device / no audio ever uploaded" copy is FALSE once 3.3.0 ships and is a standalone false-claim
  violation — a release blocker, not a nicety.
- Mirror the same strings back into PLAY-LISTING.md when you set them in the Console (the file and
  the Console are two copies of one text; keep them in lockstep, same release).

### ⚠️ TWO owner-only manual steps — easy to forget, flagged loudly

1. **⚠️ RECORD & UPLOAD THE FOREGROUND-SERVICE DEMO VIDEO.** The `microphone` (and `mediaProjection`)
   FGS declarations (Sections 2–3) require a demo video the Console asks for. Only the owner has the
   device: record the Fold 6 screen — enable bubble → focus a field → tap → dictate → text appears →
   notification visible while recording — under ~30 s. No automation can produce this.
2. **⚠️ FLIP THE GITHUB REPO BACK TO PUBLIC.** The GPLv3 source pointer in the storefront
   (PLAY-LISTING.md full description) and the in-app licenses screen (`oss_licenses.html`) both link
   the repo URL. The repo went private mid-development, so those are DEAD LINKS for anyone installing
   from Play — a GPLv3 source-availability problem and a broken-listing-link problem at once. Make it
   public again before, or with, the rollout.
