package com.whispereverywhere.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The audio-polling branch's media classification, tested PURE. The detector's polling loop
 * cannot run on the JVM (AudioManager + main-looper Handler), but the decision it applies can
 * and must: OUR OWN read-aloud voice is never transcribable media. Pre-fix, only service-driven
 * reads were covered, so a PROCESS_TEXT toolbar read got classified as media — bubble summoned
 * with a "Tap bubble to transcribe audio" toast over its own voice, and a tap mid-read recorded
 * our own TTS.
 */
class MediaPollPolicyTest {

    @Test fun controller_level_tts_active_is_self_audio_not_media() {
        // THE spec case: audio is audible (isMusicActive counts our AudioTrack) but it is ours.
        assertFalse(isPolledAudioMedia(isAudioActive = true, selfAudioActive = true, alreadyPlaying = false))
    }

    @Test fun real_audio_with_no_self_voice_is_media() {
        assertTrue(isPolledAudioMedia(isAudioActive = true, selfAudioActive = false, alreadyPlaying = false))
    }

    @Test fun silence_is_never_media() {
        assertFalse(isPolledAudioMedia(isAudioActive = false, selfAudioActive = false, alreadyPlaying = false))
        assertFalse(isPolledAudioMedia(isAudioActive = false, selfAudioActive = true, alreadyPlaying = false))
    }

    @Test fun an_already_flagged_episode_is_not_reannounced() {
        // The poll only STARTS a media episode; a running one must not re-fire
        // onMediaPlaybackStarted (duplicate-notification guard, preserved from the inline check).
        assertFalse(isPolledAudioMedia(isAudioActive = true, selfAudioActive = false, alreadyPlaying = true))
    }
}
