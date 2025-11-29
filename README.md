# Whisper Everywhere

A universal voice-to-text Android app that provides transcription capabilities in any app using OpenAI's Whisper API.

## Features

- **Floating Bubble Overlay**: Works over any app with a draggable bubble interface
- **Universal Text Injection**: Inserts transcribed text into any text field via Accessibility Service
- **OpenAI Whisper API**: Best-in-class speech recognition with auto language detection
- **Freemium Model**: 2 minutes/day free, unlimited with Premium ($5/month)
- **Multiple Payment Options**: Google Play Billing or Stripe for direct card payments
- **Visual Feedback**: Animated bubble states and vibration feedback

## Requirements

- Android 8.0 (API 26) or higher
- OpenAI API key
- Internet connection for transcription

## Setup

### 1. Open in Android Studio

Open this project in Android Studio Arctic Fox or later.

### 2. Configure API Key

Users enter their OpenAI API key in Settings. The key is stored securely using EncryptedSharedPreferences.

To get an API key:
1. Go to https://platform.openai.com/api-keys
2. Create a new API key
3. Enter it in the app's Settings

### 3. Google Play Billing Setup

To enable Google Play subscriptions:

1. Create a subscription product in Google Play Console with ID: `whisper_everywhere_premium_monthly`
2. Set price to $5.00/month
3. Upload the app to internal testing track

### 4. Stripe Setup (Optional)

To enable direct card payments:

1. Get your Stripe publishable key from https://dashboard.stripe.com/apikeys
2. Replace `STRIPE_PUBLISHABLE_KEY` in `StripeManager.kt`
3. Set up a backend server to create payment intents (see Backend Requirements)

### Backend Requirements for Stripe

You'll need a backend server with endpoints:

- `POST /create-subscription` - Creates a Stripe PaymentIntent
- `POST /cancel-subscription` - Cancels a subscription
- `GET /subscription-status` - Checks subscription status

## Project Structure

```
app/src/main/java/com/whispereverywhere/
├── WhisperEverywhereApp.kt      # Application class
├── MainActivity.kt               # Main activity with navigation
├── billing/
│   ├── BillingManager.kt        # Google Play Billing
│   └── StripeManager.kt         # Stripe payments
├── data/
│   ├── api/
│   │   └── WhisperApiService.kt # OpenAI Whisper API client
│   └── local/
│       ├── PreferencesManager.kt # App preferences
│       └── UsageTracker.kt       # Usage tracking
├── service/
│   ├── FloatingBubbleService.kt      # Overlay service
│   └── WhisperAccessibilityService.kt # Text injection
├── ui/
│   ├── screens/
│   │   ├── HomeScreen.kt
│   │   ├── SettingsScreen.kt
│   │   └── PremiumScreen.kt
│   └── theme/
│       ├── Color.kt
│       ├── Theme.kt
│       └── Type.kt
└── util/
    └── AudioRecorder.kt          # Audio recording utility
```

## Permissions

The app requires the following permissions:

- `RECORD_AUDIO` - For voice recording
- `SYSTEM_ALERT_WINDOW` - For floating bubble overlay
- `INTERNET` - For API calls
- `VIBRATE` - For haptic feedback
- `FOREGROUND_SERVICE` - For overlay service
- `BILLING` - For Google Play purchases
- Accessibility Service - For text injection

## Building

```bash
# Debug build
./gradlew assembleDebug

# Release build (requires signing config)
./gradlew assembleRelease
```

## Testing

1. Install the debug APK on an Android device
2. Grant all required permissions
3. Enable Accessibility Service in device settings
4. Enter your OpenAI API key in Settings
5. Enable the floating bubble
6. Open any app with a text field
7. Tap the bubble to record, tap again to stop
8. Watch the transcribed text appear!

## Usage Limits

- **Free Tier**: 2 minutes per day
- **Premium**: Unlimited transcription

Daily usage resets at midnight local time.

## Known Limitations

- Requires internet connection for transcription
- Some apps may block accessibility services
- Overlay permission must be granted manually on some devices

## License

Proprietary - All rights reserved.

## Support

For issues and support, contact support@whispereverywhere.com
