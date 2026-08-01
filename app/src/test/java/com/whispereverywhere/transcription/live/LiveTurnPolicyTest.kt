package com.whispereverywhere.transcription.live

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "chunk-based paths untouched" contract, pinned as a unit. [LiveTurnPolicy.runClientVad] is the
 * ONE predicate the service's VAD/commit gate reads: batch / local / Gemini segment sessions keep the
 * client VAD (byte-identical to before the server-driven inversion), and live sessions bypass it —
 * for EVERY capture source, since device audio follows the provider selection onto the same live
 * socket (owner decision 2026-08-01) and the server VAD cuts its turns too.
 */
class LiveTurnPolicyTest {

    @Test fun non_live_sessions_keep_the_client_vad() {
        // batch, local, and Gemini segment mode all run with sessionIsLive == false — including
        // device-audio capture in those sessions, whose segments this VAD is what cuts.
        assertTrue("batch/local/Gemini keep client VAD", LiveTurnPolicy.runClientVad(sessionIsLive = false))
    }

    @Test fun live_sessions_bypass_the_client_vad() {
        // The open-mic live modes (OpenAI / ElevenLabs / Soniox) let the SERVER cut turns — mic
        // and device audio alike, one engine per session.
        assertFalse("live sessions bypass client VAD", LiveTurnPolicy.runClientVad(sessionIsLive = true))
    }
}
