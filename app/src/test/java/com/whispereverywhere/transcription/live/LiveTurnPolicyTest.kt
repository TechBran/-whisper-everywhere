package com.whispereverywhere.transcription.live

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "chunk-based paths untouched" contract, pinned as a unit. [LiveTurnPolicy.runClientVad] is the
 * ONE predicate the service's VAD/commit gate reads: batch / local / Gemini segment sessions keep the
 * client VAD (byte-identical to before the server-driven inversion), live sessions bypass it for MIC
 * audio only — device-audio capture always keeps it, because that audio routes on-device where no
 * server VAD can ever cut it.
 */
class LiveTurnPolicyTest {

    @Test fun non_live_sessions_keep_the_client_vad() {
        // batch, local, and Gemini segment mode all run with sessionIsLive == false.
        assertTrue(
            "batch/local/Gemini keep client VAD",
            LiveTurnPolicy.runClientVad(sessionIsLive = false, playbackSource = false),
        )
    }

    @Test fun live_sessions_bypass_the_client_vad_for_mic_audio() {
        // The open-mic live modes (OpenAI / ElevenLabs / Soniox) let the SERVER cut turns.
        assertFalse(
            "live sessions bypass client VAD on the mic",
            LiveTurnPolicy.runClientVad(sessionIsLive = true, playbackSource = false),
        )
    }

    @Test fun device_audio_capture_keeps_the_client_vad_even_in_a_live_session() {
        // Owner-reported 2026-08-01: screen-capture transcription in a live session arrived as one
        // 30 s block per backstop trip. Device audio routes to the ON-DEVICE engine (it never
        // reaches the cloud), so the provider's server VAD never sees it — the client VAD is the
        // only committer that audio has, and bypassing it starved the local engine of commits for
        // the whole capture.
        assertTrue(
            "playback audio has no server VAD; the client VAD must cut its turns",
            LiveTurnPolicy.runClientVad(sessionIsLive = true, playbackSource = true),
        )
    }

    @Test fun playback_in_a_non_live_session_is_unchanged() {
        assertTrue(LiveTurnPolicy.runClientVad(sessionIsLive = false, playbackSource = true))
    }
}
