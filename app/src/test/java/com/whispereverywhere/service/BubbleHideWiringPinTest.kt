package com.whispereverywhere.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source pins on FloatingBubbleService for 4.3.1 B. The service is a 3,500-line Android class no
 * JVM test can construct, so the WIRING is held by text: every hide carries a reason and goes
 * through the one sink; the sink asks BubbleHidePolicy; a parked hide replays in the IDLE branch;
 * a bailed trigger resets the speaking flag. Live lines only — a comment cannot satisfy a pin.
 */
class BubbleHideWiringPinTest {

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
            !(line.startsWith("//") || line.startsWith("/*") || line.startsWith("*")) && line.contains(needle)
        }

    private fun liveOffsets(scope: String, needle: String): List<Int> {
        val out = mutableListOf<Int>()
        var at = 0
        for (line in scope.split("\n")) {
            val t = line.trimStart()
            val commented = t.startsWith("//") || t.startsWith("/*") || t.startsWith("*")
            if (!commented && line.contains(needle)) out += at
            at += line.length + 1
        }
        return out
    }

    /** A member's body: from the anchor line to the first non-blank line at or left of its indent. */
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

    private val service: String by lazy { source("src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt") }
    private val controller: String by lazy { source("src/main/java/com/whispereverywhere/tts/TtsController.kt") }

    @Test
    fun every_hide_carries_a_reason_and_there_is_exactly_one_sink() {
        assertEquals("no reasonless hide may remain", emptyList<String>(), liveLines(service, "hideBubble()"))
        assertEquals("one sink", 1, liveLines(service, "private fun hideBubble(reason: String) {").size)
        // four original callers + the deferred replay = five call sites, each with a reason literal
        val calls = liveLines(service, "hideBubble(\"")
        assertEquals("five reasoned calls, found: $calls", 5, calls.size)
        for (r in listOf("clipboard-autohide", "field-unfocused", "media-stopped", "idle-after-session", "deferred:")) {
            assertTrue("reason $r is used", calls.any { it.contains("hideBubble(\"$r") })
        }
    }

    @Test
    fun the_sink_asks_the_policy_logs_and_parks_before_it_animates() {
        val body = memberBody(service, "    private fun hideBubble(reason: String) {")
        val decide = liveOffsets(body, "BubbleHidePolicy.decide(")
        val log = liveOffsets(body, "\"bubble hide: reason=")
        val park = liveOffsets(body, "deferredHideReason = reason")
        val anim = liveOffsets(body, "hideAnimator = ValueAnimator.ofFloat(1f, 0f)")
        assertTrue("decide once", decide.size == 1)
        assertTrue("log once", log.size == 1)
        assertTrue("park once", park.size == 1)
        assertTrue("animate once", anim.size == 1)
        assertTrue("decide -> log -> park -> animate", decide.first() < log.first() && log.first() < park.first() && park.first() < anim.first())
        assertTrue("the old alwaysOn/visible early returns are gone — the policy owns them",
            liveLines(body, "if (alwaysOnMode()) return").isEmpty() && liveLines(body, "if (!isBubbleVisible) return").isEmpty())
    }

    @Test
    fun the_idle_branch_replays_a_parked_hide_through_the_policy_after_the_read() {
        val body = memberBody(service, "    private fun updateBubbleState(newState: BubbleState) {")
        val idle = liveOffsets(body, "BubbleState.IDLE -> {")
        val replay = liveOffsets(body, "BubbleHidePolicy.replay(")
        val guard = liveOffsets(body, "deferredHideReason?.let { parked ->")
        assertTrue("one replay in the IDLE arm", idle.size == 1 && replay.size == 1 && guard.size == 1)
        assertTrue("guard then replay, inside IDLE", idle.first() < guard.first() && guard.first() < replay.first())
        assertTrue("a replay never runs while still speaking", body.contains("if (!isSpeakingNow) {"))
    }

    @Test
    fun a_bailed_trigger_resets_the_speaking_flag_and_the_visuals() {
        val body = memberBody(service, "    private fun startSpeaking(text: String) {")
        assertTrue(liveLines(body, "val started = com.whispereverywhere.tts.TtsController.speakFromTrigger(this, text) {").size == 1)
        val bail = memberBody(body, "        if (!started) {")
        assertTrue(bail.contains("isSpeakingNow = false") && bail.contains("exitSpeakingVisuals()") &&
            bail.contains("engine.onPcmChunk = null") && bail.contains("engine.onBuffering = null") && bail.contains("engine.onProgress = null"))
        assertTrue("the controller reports whether speak() ran",
            liveLines(controller, "fun speakFromTrigger(context: Context, text: String, onDone: () -> Unit = {}): Boolean {").size == 1)
        assertTrue(liveLines(controller, "return e.speak(text, onDone)").size == 1)
    }
}
