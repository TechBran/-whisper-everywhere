package com.whispereverywhere.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.File
import org.junit.Test

/**
 * THE DEVICE-AUDIO LATCH (owner rule, 2026-09-02): once a transcription session is capturing
 * device audio, **the microphone never enters that session**. Not when the video is paused, not
 * when it is scrubbed, not when it ends — capture holds until the user ends the transcription,
 * and only then is the projection released (which `stopRecording` already does).
 *
 * WHAT THIS PREVENTS, in the owner's words: "we're trying to keep the microphone out of it so
 * someone could transcribe a YouTube video without their actual spoken words being dictated."
 * Before this rule, scrubbing a video made YouTube's media session report a non-playing state,
 * `onMediaPlaybackStopped` fired, and the service called `switchSource(to = MIC)` **mid-session
 * with the projection still live** — so the room was recorded into the video's transcript until
 * playback resumed, and the resume cut a second segment. Two spurious cuts and a bleed, per scrub.
 *
 * A source pin, because the defence is the ABSENCE of a call: `FloatingBubbleService` is an
 * Android service no JVM test can construct, and a deleted behaviour has no runtime surface to
 * assert against. Live lines only — a commented-out handback must not satisfy it.
 */
class DeviceAudioLatchPinTest {

    private fun source(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            for (candidate in listOf(File(dir, relative), File(dir, "app/$relative"))) {
                if (candidate.isFile) return candidate.readText().replace("\r\n", "\n")
            }
            dir = dir.parentFile
        }
        throw AssertionError("cannot locate $relative from ${System.getProperty("user.dir")}")
    }

    private fun liveLines(scope: String, needle: String): List<String> =
        scope.split("\n").map { it.trimStart() }.filter { line ->
            !(line.startsWith("//") || line.startsWith("/*") || line.startsWith("*")) &&
                line.contains(needle)
        }

    /** A member's body: the anchor line to the first non-blank line at or left of its own indent. */
    private fun memberBody(kt: String, anchor: String): String {
        val start = kt.indexOf(anchor)
        assertTrue("anchor missing: $anchor", start >= 0)
        val lineStart = kt.lastIndexOf('\n', start - 1) + 1
        val indent = kt.substring(lineStart).substringBefore("\n").takeWhile { it == ' ' }.length
        val lines = kt.substring(start).split("\n")
        val body = StringBuilder(lines.first())
        var closed = false
        for (line in lines.drop(1)) {
            if (line.isNotBlank() && line.takeWhile { it == ' ' }.length <= indent) { closed = true; break }
            body.append("\n").append(line)
        }
        assertTrue("member never closes: $anchor", closed)
        return body.toString()
    }

    private val service: String by lazy {
        source("src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt")
    }

    @Test
    fun media_stopping_never_hands_a_live_capture_session_back_to_the_microphone() {
        val body = memberBody(
            service,
            "    override fun onMediaPlaybackStopped() {",
        )
        assertEquals(
            "a scrub, a pause or the video ending must not switch the source — found: " +
                liveLines(body, "switchSource("),
            emptyList<String>(),
            liveLines(body, "switchSource("),
        )
        assertEquals(
            "and it must not start the microphone by any other route either — found: " +
                liveLines(body, "startMicSource("),
            emptyList<String>(),
            liveLines(body, "startMicSource("),
        )
    }

    @Test
    fun the_session_still_releases_the_projection_when_the_user_ends_it() {
        // The latch holds capture for the session's life; ending the session is what frees the
        // phone (the owner's 2026-08-01 decision, unchanged by this rule — a live projection
        // interferes with mic capture and the sharing indicator must not outlive the transcript).
        val body = memberBody(service, "    private fun stopRecording() {")
        assertEquals(
            "stopRecording releases the projection exactly once",
            1,
            liveLines(body, "MediaProjectionGate.clear()").size,
        )
        assertEquals(
            "and stops the capturer",
            1,
            liveLines(body, "stopPlaybackCapturer()").size,
        )
    }

    @Test
    fun a_session_that_starts_while_media_plays_still_hands_the_mic_over_to_the_stream() {
        // The latch is one-way. Handing the MIC over TO device audio stays: that is the
        // "media transcription cuts the mic" decision, and it is what makes the latch reachable.
        val body = memberBody(
            service,
            "    override fun onMediaPlaybackStarted(packageName: String, title: String?) {",
        )
        assertEquals(
            "the handover to PLAYBACK is intact",
            1,
            liveLines(body, "switchSource(to = com.whispereverywhere.audio.ActiveSource.PLAYBACK)").size,
        )
    }
}
