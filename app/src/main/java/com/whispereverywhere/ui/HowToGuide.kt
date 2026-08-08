package com.whispereverywhere.ui

/**
 * The in-app "How to use Whisper Everywhere" guide — ONE source of truth rendered two ways: as
 * the expandable card below Home's stats panel, and as plain text for the guide's own read-aloud
 * button (the app reading its own manual is the fastest possible demo of read-aloud).
 *
 * Copy discipline is the same as everywhere else in the app, pinned by [HowToGuideTest]: no speed
 * claims, prices always "about", the cost line always "estimate". Kept Compose- and Android-free
 * so the whole guide is a JVM test subject.
 */
object HowToGuide {

    data class Section(val title: String, val body: String)

    val sections: List<Section> = listOf(
        Section(
            "The bubble",
            "The floating bubble is the heart of the app. Tap it to start dictating and tap " +
                "again to stop. Drag it anywhere — it remembers your spot. Press and hold to " +
                "lock it in place (a lock flashes to confirm), and press and hold again to " +
                "unlock. While you dictate, your words collect in a text window above the " +
                "bubble — drag the double arrow at its top right corner to make it any size " +
                "you like, and it stays that size; press and hold the arrow to go back to the " +
                "standard size. In Settings you can keep it always on screen, or let it pop " +
                "up on its own whenever a text field or keyboard appears and hide when idle.",
        ),
        Section(
            "Dictating into any app",
            "Tap into any text field, then tap the bubble and speak. Your words collect in " +
                "the bubble's text window as you talk, and when you tap the bubble again to " +
                "stop, the whole transcription is typed at the cursor in one go — no " +
                "half-finished chunks landing while you think. Dictation works in over 90 " +
                "languages. Everything runs on your phone by default — audio never leaves " +
                "your device unless you set up a cloud provider yourself.",
        ),
        Section(
            "Cloud providers and real-time streaming",
            "In Engines & voices you can add your own API keys for OpenAI, Google Gemini, " +
                "ElevenLabs, or Soniox. Selecting one as your engine sends the audio you " +
                "transcribe to that provider, billed to your own account — the app shows the " +
                "provider's approximate price right on the selector. Streaming-capable " +
                "providers transcribe in real time as you speak, and the on-device model takes " +
                "over automatically whenever a provider fails, so dictation always completes.",
        ),
        Section(
            "Transcribing videos and podcasts",
            "Play some media, then tap the bubble and allow screen capture. The device's own " +
                "audio is transcribed — no room noise, no echo. If you have a cloud engine " +
                "selected it transcribes that audio too; on-device stays the default. The " +
                "screen share ends by itself when the session finishes.",
        ),
        Section(
            "Transcribing audio files",
            "From the home screen, choose Transcribe audio file and pick any recording. " +
                "You choose the engine per file — on-device is free; a cloud provider shows " +
                "its approximate cost up front and asks before anything expensive.",
        ),
        Section(
            "Read-aloud",
            "Select text in any app and choose Read aloud, or copy text — the bubble appears " +
                "with a pulsing speaker; tap it to hear the clipboard. A scrubber lets you " +
                "skip around, and you can choose the on-device voice or a cloud voice per " +
                "provider in Engines & voices. The button on this guide reads it to you the " +
                "same way.",
        ),
        Section(
            "Dictation-first keyboard",
            "An optional setting for people who mostly dictate: when it is on, tapping a text " +
                "field raises the bubble instead of the keyboard. The keyboard button on the " +
                "bubble brings your normal keyboard back whenever you want it. Some keyboards " +
                "may ignore this — the setting says so honestly.",
        ),
        Section(
            "Your transcriptions and your stats",
            "Every session is saved under Transcriptions for 14 days, then cleaned up " +
                "automatically. The stats panel above shows today's transcription time, how " +
                "many engines you have configured, and your total transcription count. If you " +
                "use cloud providers, a line underneath estimates this month's spend from the " +
                "app's own tracking — an estimate, not your provider's bill.",
        ),
        Section(
            "If something is missing",
            "The home screen tells you when something needs attention: a red line when a " +
                "permission is missing, and a card with a Download button if the speech model " +
                "or read-aloud voice is not installed. Everything can also be managed in " +
                "Settings.",
        ),
    )

    /** The whole guide as one plain-text read — what the pinned read-aloud button speaks. */
    fun plainText(): String = buildString {
        append("How to use Whisper Everywhere. ")
        for (s in sections) {
            append(s.title)
            append(". ")
            append(s.body)
            append(" ")
        }
    }.trim()
}
