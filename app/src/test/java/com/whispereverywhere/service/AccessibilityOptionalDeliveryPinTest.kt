package com.whispereverywhere.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.File
import org.junit.Test

/**
 * DELIVERY WITHOUT THE ACCESSIBILITY SERVICE (4.3.3, accessibility-optional-spec §4): when
 * `WhisperAccessibilityService.isEnabled()` is false, `deliverFinalTranscript` attempts no
 * injection and goes straight to the clipboard write the CLIPBOARD_ONLY branch already performs,
 * then that branch's toast. `FinalDeliveryPolicyTest` executes the RULE (the service-off row);
 * what it cannot see is whether the service asks it, and whether the read happens BEFORE either
 * injection site — an order, which a count cannot express.
 *
 * A source pin, the house instrument for `FloatingBubbleService` (an Android service no JVM test
 * can construct): live lines only, symbol-scoped, LF-normalised. The other half of the brief's
 * §4 — "no new behaviour when the service IS enabled" — is held here too: both injection sites
 * and all three clipboard writes are counted exactly as they were before this build.
 */
class AccessibilityOptionalDeliveryPinTest {

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

    /** Byte offset of a needle's first LIVE occurrence, or -1 — the order instrument. */
    private fun offsetOfLive(scope: String, needle: String): Int {
        var at = 0
        for (line in scope.split("\n")) {
            val trimmed = line.trimStart()
            val commented =
                trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*")
            if (!commented && line.contains(needle)) return at
            at += line.length + 1
        }
        return -1
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

    private val policy: String by lazy {
        source("src/main/java/com/whispereverywhere/transcription/FinalDeliveryPolicy.kt")
    }

    private val delivery: String by lazy {
        memberBody(service, "    private fun deliverFinalTranscript(full: String) {")
    }

    @Test
    fun the_service_off_read_precedes_every_injection_attempt_and_reaches_the_one_decision() {
        val read = offsetOfLive(delivery, "val serviceEnabled = WhisperAccessibilityService.isEnabled()")
        val firstInject = offsetOfLive(delivery, "WhisperAccessibilityService.injectTextWithResult(full)")
        assertTrue("deliverFinalTranscript reads isEnabled() into a named value", read >= 0)
        assertTrue("and it still has an injection site to precede", firstInject >= 0)
        assertTrue(
            "THE ORDER: the service-off read comes before the first injection attempt " +
                "(read at $read, first inject at $firstInject)",
            read < firstInject,
        )
        // It reaches the policy as its input — the rule decides, the service only asks.
        assertEquals(
            "the read is handed to the ONE decision point",
            1,
            liveLines(delivery, "serviceEnabled = serviceEnabled,").size,
        )
        assertEquals(
            "and there is exactly one decision point",
            1,
            liveLines(delivery, "FinalDeliveryPolicy.decide(").size,
        )
        val decide = offsetOfLive(delivery, "FinalDeliveryPolicy.decide(")
        assertTrue("the decision itself precedes the first injection attempt", decide < firstInject)
    }

    @Test
    fun the_policy_answers_no_injection_and_one_copy_when_the_service_is_off() {
        // The pure half, held as source beside its executed test: the service-off row exists,
        // is live, and is the FIRST parameter — the shape the service's named call above pins.
        assertEquals(
            "the service-off row: no injection, one consolidated copy",
            1,
            liveLines(policy, "!serviceEnabled -> FinalDeliveryPlan(inject = null, copyWholeToClipboard = true)").size,
        )
        val param = offsetOfLive(policy, "serviceEnabled: Boolean,")
        val next = offsetOfLive(policy, "isTextFieldSession: Boolean,")
        assertTrue("serviceEnabled is a declared parameter", param >= 0)
        assertTrue("and it leads the signature", param < next)
        // Blank still wins: no service does not invent a copy of nothing.
        val blank = offsetOfLive(policy, "transcriptBlank -> FinalDeliveryPlan(inject = null, copyWholeToClipboard = false)")
        val off = offsetOfLive(policy, "!serviceEnabled -> ")
        assertTrue("the blank row is decided before the service-off row", blank in 0 until off)
    }

    @Test
    fun with_the_service_enabled_the_delivery_body_is_the_w2_body_site_for_site() {
        // Brief §4: "No new behaviour when the service IS enabled". The two injection sites
        // (SESSION_BOUND, FINALIZE_FOCUS) and the three clipboard writes (FAILED fallback,
        // FINALIZE_FOCUS's copy, the target-less consolidated copy) are exactly the W2 set —
        // a site added or removed here changes the with-service behaviour and trips this.
        assertEquals(
            "two injection sites, as before",
            2,
            liveLines(delivery, "WhisperAccessibilityService.injectTextWithResult(full)").size,
        )
        assertEquals(
            "three clipboard writes, as before",
            3,
            liveLines(delivery, "clip.setPrimaryClip(android.content.ClipData.newPlainText(\"Transcript\", full))").size,
        )
        assertEquals(
            "the target-less branch is still the consolidated copy the service-off plan lands in",
            1,
            liveLines(delivery, "null -> if (plan.copyWholeToClipboard) {").size,
        )
    }

    @Test
    fun the_clipboard_branch_the_service_off_plan_lands_in_says_the_text_was_copied() {
        // Brief §4: "check its wording; it must say the text was copied". The plan without the
        // service is (inject = null, copy = true), which lands in the `null ->` arm; both of that
        // arm's toasts name the copy. Held as live lines so a reworded toast is a decision.
        val arm = delivery.substring(delivery.indexOf("null -> if (plan.copyWholeToClipboard) {"))
        assertEquals(1, liveLines(arm, "\"Full transcription copied to clipboard\"").size)
        assertEquals(1, liveLines(arm, "\"Transcription copied to clipboard\"").size)
        assertEquals(
            "and the arm writes the clipboard before it says so",
            1,
            liveLines(arm, "clip.setPrimaryClip(android.content.ClipData.newPlainText(\"Transcript\", full))").size,
        )
        assertTrue(
            offsetOfLive(arm, "clip.setPrimaryClip(") <
                offsetOfLive(arm, "\"Transcription copied to clipboard\""),
        )
    }
}
