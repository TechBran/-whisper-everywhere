package com.whispereverywhere.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioSourcePolicyTest {

    @Test fun `no media playing - mic`() =
        assertEquals(SourceDecision.UseMic,
            AudioSourcePolicy.decide(mediaPlaying = false, hasProjection = true, sdkInt = 34, preferDeviceAudio = true))

    @Test fun `media playing with projection - playback`() =
        assertEquals(SourceDecision.UsePlayback,
            AudioSourcePolicy.decide(mediaPlaying = true, hasProjection = true, sdkInt = 34, preferDeviceAudio = true))

    @Test fun `media playing without projection - request consent`() =
        assertEquals(SourceDecision.RequestConsent,
            AudioSourcePolicy.decide(mediaPlaying = true, hasProjection = false, sdkInt = 34, preferDeviceAudio = true))

    @Test fun `pre-Q device - always mic`() =
        assertEquals(SourceDecision.UseMic,
            AudioSourcePolicy.decide(mediaPlaying = true, hasProjection = true, sdkInt = 28, preferDeviceAudio = true))

    @Test fun `preference off - always mic`() =
        assertEquals(SourceDecision.UseMic,
            AudioSourcePolicy.decide(mediaPlaying = true, hasProjection = true, sdkInt = 34, preferDeviceAudio = false))
}
