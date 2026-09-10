package com.whispereverywhere.transcription.live

import com.whispereverywhere.provider.ProviderId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "chunk-based paths untouched" contract, pinned as a unit. [LiveTurnPolicy.runClientVad] is the
 * ONE predicate the service's VAD/commit gate reads: batch / local sessions keep the client VAD
 * (byte-identical to before the server-driven inversion), server-driven live sessions bypass it —
 * for EVERY capture source, since device audio follows the provider selection onto the same live
 * socket (owner decision 2026-08-01) and the server VAD cuts its turns too — and, since 4.3.4, a
 * GEMINI live session keeps it: Gemini's own VAD drops most speech after the first sentence (T0
 * 2026-09-10), so the app's endpointer cuts its manual-VAD activities.
 */
class LiveTurnPolicyTest {

    @Test fun non_live_sessions_keep_the_client_vad() {
        // batch and local all run with sessionIsLive == false — including device-audio capture in
        // those sessions, whose segments this VAD is what cuts. The provider is irrelevant there.
        assertTrue("batch/local keep client VAD", LiveTurnPolicy.runClientVad(sessionIsLive = false))
        for (p in ProviderId.entries) {
            assertTrue("$p batch keeps client VAD", LiveTurnPolicy.runClientVad(sessionIsLive = false, liveProvider = p))
        }
    }

    @Test fun server_driven_live_sessions_bypass_the_client_vad() {
        // The open-mic live modes (OpenAI / ElevenLabs / Soniox) let the SERVER cut turns — mic
        // and device audio alike, one engine per session.
        for (p in listOf(ProviderId.OPENAI, ProviderId.ELEVENLABS, ProviderId.SONIOX)) {
            assertFalse("$p live bypasses client VAD", LiveTurnPolicy.runClientVad(sessionIsLive = true, liveProvider = p))
            assertFalse(LiveTurnPolicy.clientCutsLiveTurns(p))
        }
        // The one-argument form (every pre-4.3.4 caller) still reads as server-driven.
        assertFalse("live sessions bypass client VAD", LiveTurnPolicy.runClientVad(sessionIsLive = true))
    }

    @Test fun a_gemini_live_session_keeps_the_client_vad() {
        // Manual VAD from the app's endpointer: each commit becomes an activityEnd on the socket.
        assertTrue(LiveTurnPolicy.clientCutsLiveTurns(ProviderId.GEMINI))
        assertTrue("Gemini live keeps client VAD", LiveTurnPolicy.runClientVad(sessionIsLive = true, liveProvider = ProviderId.GEMINI))
    }
}
