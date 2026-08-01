package com.whispereverywhere.transcription

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The one cleaning contract every injected transcript passes through — whisper's non-speech
 * markers, whitespace collapse, and (2026-07-31, found on-device) the markdown code fences
 * GPT-family transcription models sometimes wrap their live output in.
 */
class TranscriptTextTest {

    @Test fun strips_bracketed_non_speech_markers() {
        assertEquals("hello world", TranscriptText.clean("[BLANK_AUDIO] hello [ Silence ] world"))
    }

    @Test fun strips_parenthesized_sound_annotations_but_keeps_real_parentheses() {
        assertEquals(
            "meet me at five (the usual place)",
            TranscriptText.clean("(upbeat music) meet me at five (the usual place) (applause)"),
        )
    }

    @Test fun collapses_whitespace_and_trims() {
        assertEquals("a b c", TranscriptText.clean("  a\n b\t\tc  "))
    }

    // ---- the code-fence rule: nobody SPEAKS a fence; any ``` run is model chrome ----

    @Test fun strips_bare_code_fences() {
        assertEquals("hello world", TranscriptText.clean("``` hello world ```"))
    }

    @Test fun strips_fences_with_info_strings() {
        // gpt-transcribe has been observed emitting ```text-style fencing around live output.
        assertEquals("the meeting is at three", TranscriptText.clean("```text\nthe meeting is at three\n```"))
    }

    @Test fun strips_mid_transcript_fence_runs_of_any_length() {
        assertEquals("before after", TranscriptText.clean("before ````` after"))
    }

    @Test fun single_and_double_backticks_survive() {
        // Conceivably legitimate content; only 3+ runs are unambiguous model chrome.
        assertEquals("a `quoted` word and ``two``", TranscriptText.clean("a `quoted` word and ``two``"))
    }

    @Test fun a_transcript_that_is_only_chrome_cleans_to_empty() {
        // The live engine maps this to EmptyUnexpected -> the fallback re-runs the audio locally,
        // because the VAD proved there WAS speech the cloud failed to deliver.
        assertEquals("", TranscriptText.clean("```\n[BLANK_AUDIO]\n```"))
    }
}
