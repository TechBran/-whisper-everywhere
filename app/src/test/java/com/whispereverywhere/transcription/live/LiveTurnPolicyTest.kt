package com.whispereverywhere.transcription.live

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "chunk-based paths untouched" contract, pinned as a unit. [LiveTurnPolicy.runClientVad] is the
 * ONE predicate the service's VAD/commit gate reads: batch / local / Gemini segment sessions keep the
 * client VAD (byte-identical to before the server-driven inversion), and only live sessions bypass it.
 */
class LiveTurnPolicyTest {

    @Test fun non_live_sessions_keep_the_client_vad() {
        // batch, local, and Gemini segment mode all run with sessionIsLive == false.
        assertTrue("batch/local/Gemini keep client VAD", LiveTurnPolicy.runClientVad(sessionIsLive = false))
    }

    @Test fun live_sessions_bypass_the_client_vad() {
        // The open-mic live modes (OpenAI / ElevenLabs / Soniox) let the SERVER cut turns.
        assertFalse("live sessions bypass client VAD", LiveTurnPolicy.runClientVad(sessionIsLive = true))
    }
}
