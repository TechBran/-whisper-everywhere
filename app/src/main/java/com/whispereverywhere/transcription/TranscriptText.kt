package com.whispereverywhere.transcription

/**
 * Cleans whisper.cpp output for dictation. whisper emits bracketed non-speech / blank markers for
 * silent or non-speech segments — e.g. "[BLANK_AUDIO]", "[ Silence ]", "(upbeat music)",
 * "(applause)" — which must NOT be typed into the user's text field. This strips them.
 *
 * Pure logic, unit-testable.
 */
object TranscriptText {

    // whisper uses square brackets almost exclusively for sound events + blank markers, so any
    // [ ... ] group is stripped. (Timestamp/special tokens are already disabled in the JNI.)
    private val SQUARE = Regex("\\[[^\\]]*\\]")

    // Parenthesized SOUND annotations only — keep other parentheses, which can be genuine speech.
    private val PAREN_NONSPEECH = Regex(
        "\\([^)]*\\b(?:BLANK_?AUDIO|blank|silence|music|applause|laughter|laughs?|sighs?|" +
            "groans?|noise|inaudible|unintelligible|pause|no\\s+audio|no\\s+speech|coughs?|" +
            "clears\\s+throat|beep|static|wind|footsteps|typing|ringing)\\b[^)]*\\)",
        RegexOption.IGNORE_CASE,
    )

    private val WHITESPACE = Regex("\\s+")

    /** Returns [raw] with whisper non-speech markers removed and whitespace collapsed/trimmed. */
    fun clean(raw: String): String {
        var s = SQUARE.replace(raw, " ")
        s = PAREN_NONSPEECH.replace(s, " ")
        return WHITESPACE.replace(s, " ").trim()
    }
}
