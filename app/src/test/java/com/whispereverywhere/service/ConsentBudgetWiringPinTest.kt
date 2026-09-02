package com.whispereverywhere.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source pins on FloatingBubbleService for 4.3.1 D. The service is a 3,700-line Android class no
 * JVM test can construct, so the WIRING is held by text: both screen-capture consent requests are
 * counted against the per-session budget before they ask, the handover asks only under the budget,
 * startAudioInput hands the budget to the policy, a session starts with a fresh budget, and a spent
 * budget toasts once per session. Live lines only — a comment cannot satisfy a pin.
 */
class ConsentBudgetWiringPinTest {

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

    @Test
    fun both_consent_requests_are_budgeted_and_counted_and_the_budget_resets_per_session() {
        val asks = liveLines(service, "MediaProjectionGate.requestConsent(")
        assertEquals("exactly two ask sites, found: $asks", 2, asks.size)
        val notes = liveOffsets(service, "consentBudget.noteAsked()")
        val askOffsets = liveOffsets(service, "MediaProjectionGate.requestConsent(")
        assertEquals("every ask is counted once", 2, notes.size)
        for (i in 0..1) assertTrue("noteAsked precedes ask $i", notes[i] < askOffsets[i])
        val handover = memberBody(service, "    override fun onMediaPlaybackStarted(packageName: String, title: String?) {")
        assertEquals("the handover asks under the budget", 1, liveLines(handover, "consentBudget.mayAsk()").size)
        val input = memberBody(service, "    private fun startAudioInput(): Result<Unit> {")
        assertEquals("startAudioInput hands the budget to the policy", 1,
            liveLines(input, "consentAvailable = consentBudget.mayAsk(),").size)
        val start = memberBody(service, "    private fun startRecording() {")
        assertEquals("a session starts with a fresh budget", 1, liveLines(start, "consentBudget.reset()").size)
        assertEquals("one field", 1, liveLines(service, "private val consentBudget = com.whispereverywhere.audio.ProjectionConsentBudget()").size)
    }

    @Test
    fun a_spent_budget_toasts_once_per_session_not_once_per_media_event() {
        val handover = memberBody(service, "    override fun onMediaPlaybackStarted(packageName: String, title: String?) {")
        assertEquals(1, liveLines(handover, "if (!consentExhaustedToastShown) {").size)
        assertEquals(1, liveLines(handover, "consentExhaustedToastShown = true").size)
        val start = memberBody(service, "    private fun startRecording() {")
        assertEquals(1, liveLines(start, "consentExhaustedToastShown = false").size)
    }
}
