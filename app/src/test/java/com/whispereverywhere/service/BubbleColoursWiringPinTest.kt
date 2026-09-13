package com.whispereverywhere.service

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE REACH FROM THE PREFERENCE TO THE PIXEL, pinned structurally (4.5.1 Task 2) — the
 * `LocalPreviewWiringPinTest` idiom: `FloatingBubbleService` cannot be instantiated on the JVM,
 * so the CALLS are pinned as whitespace-normalised, symbol-scoped needles inside their declaring
 * bodies; never line numbers.
 *
 * The pin exists because the invariant is already a cross product in `BubbleColoursTest` and the
 * storage is already pinned in `PreferencesBubbleColoursTest`. What no other JVM test can see is
 * whether the three views on the actual bubble are handed those values at all — the defect being
 * a setting that persists perfectly and changes nothing on screen.
 */
class BubbleColoursWiringPinTest {

    private fun source(relative: String): File {
        var dir: File? = File(System.getProperty("user.dir")!!).absoluteFile
        while (dir != null) {
            for (candidate in listOf(File(dir, relative), File(dir, "app/$relative"))) {
                if (candidate.isFile) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError("cannot locate $relative from ${System.getProperty("user.dir")}")
    }

    /** Raw, LF-normalised — used where the pin is about a body's boundaries. */
    private val raw: String by lazy {
        source("src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt")
            .readText()
            .replace("\r\n", "\n")
    }

    /** Whitespace-collapsed: the pins are about which call is made, not how it is wrapped. */
    private val text: String by lazy { raw.replace(Regex("\\s+"), " ") }

    private val layout: String by lazy {
        source("src/main/res/layout/floating_bubble.xml").readText().replace("\r\n", "\n")
    }

    private fun count(haystack: String, needle: String) = haystack.split(needle).size - 1

    private fun indexOfOrFail(haystack: String, needle: String): Int {
        val i = haystack.indexOf(needle)
        assertTrue("missing from FloatingBubbleService.kt: <<$needle>>", i >= 0)
        return i
    }

    private fun body(declaration: String, closer: String): String {
        val start = indexOfOrFail(raw, declaration)
        val close = raw.indexOf(closer, start)
        assertTrue("the closing brace of <<$declaration>> moved", close > start)
        return raw.substring(start, close + closer.length).replace(Regex("\\s+"), " ")
    }

    private val applyColours: String by lazy {
        body("    private fun applyBubbleColours() {", "\n    }\n")
    }

    private val stripRole: String by lazy {
        body("    private fun applyStripRole(words: Boolean) {", "\n    }\n")
    }

    @Test
    fun allTHREEViewsAreHandedTheUsersOwnValueAndNONEOfThemIsALiteral() {
        // The live strip's colour is the ROLE's, because that one view has two jobs — see
        // `theStripsSTATUSLinesDoNotInheritTheLiveColour`. One colour on the words role covers
        // the on-device previewer AND all four live cloud providers, because
        // `deltaOwnsPreviewStrip` already hands this single view to whichever is running.
        assertTrue(applyColours.contains("applyStripRole(words = false)"))
        assertTrue(stripRole.contains("app.preferencesManager.bubbleLiveColour"))
        assertTrue(stripRole.contains("app.preferencesManager.bubbleCommittedColour"))
        assertTrue(
            applyColours.contains("transcriptionEditText.setTextColor(app.preferencesManager.bubbleCommittedColour)"),
        )
        // The hint is DERIVED from the committed colour, not a fourth setting and not left white.
        assertTrue(
            applyColours.contains("transcriptionEditText.setHintTextColor(BubbleColours.hintArgb(app.preferencesManager.bubbleCommittedColour))"),
        )
        // The panel behind both of them: black at the user's alpha, through the one function that
        // knows the ladder.
        assertTrue(
            applyColours.contains("BubbleColours.panelArgb(app.preferencesManager.bubbleOpacityPercent)"),
        )
        // ...and the panel's fill is set on the SHAPE, so the 16dp corners the drawable defines
        // survive. `mutate()` first: a shared drawable would otherwise repaint every view that
        // ever inflated it.
        assertTrue(applyColours.contains("mutate()"))
        assertTrue(applyColours.contains("GradientDrawable"))
    }

    @Test
    fun theServiceSetsThoseThreeThingsNOWHEREElse() {
        // One writer per fact. A second site would be a second opinion about a value the user
        // owns, and the one that ran last would win — which is exactly how the two `fillColor`
        // black literals came to exist.
        assertEquals("ONE body", 1, count(raw, "    private fun applyBubbleColours() {"))
        assertEquals("ONE role body", 1, count(raw, "    private fun applyStripRole(words: Boolean) {"))
        assertEquals("ONE strip-colour write", 1, count(text, "transcriptionDeltaText.setTextColor("))
        assertEquals("ONE committed-colour write", 1, count(text, "transcriptionEditText.setTextColor("))
        assertEquals("ONE hint write", 1, count(text, "transcriptionEditText.setHintTextColor("))
        assertEquals("ONE panel-fill write", 1, count(text, "BubbleColours.panelArgb("))
        // No decision here: the service reads the preference and paints. Every rule about which
        // values are allowed lives in BubbleColours, asserted by BubbleColoursTest.
        assertFalse("no contrast judgement in the service", text.contains("CONTRAST_FLOOR"))
        assertFalse("no legibility judgement in the service", text.contains("legibleEverywhere"))
        assertFalse("no ladder arithmetic in the service", text.contains("OPACITY_STEPS"))
    }

    @Test
    fun theColoursAreReAppliedEVERYTimeThePanelIsAboutToBeSeen() {
        // Three call sites, each immediately after the geometry re-clamp that already runs at
        // every one of them — so the read happens at SHOW time, not once at service start. The
        // user changes a colour in Settings and the next time the panel appears it is right;
        // there is no window in which a stale colour is on screen, because the panel is GONE
        // between sessions.
        // Counted as the adjacent PAIR rather than as the bare name: the name also appears in
        // the declaration and in prose, and a pin that counts mentions is a pin a comment can
        // break. The pair is the thing being asserted anyway — the colours are re-read wherever
        // the geometry is.
        assertEquals("three call sites", 3, count(text, "applyPreviewSize() applyBubbleColours()"))
        for (declaration in listOf(
            "    private fun createBubbleView() {",
            "    private fun showSessionPreview(live: Boolean) {",
        )) {
            val b = body(declaration, "\n    }\n")
            assertTrue(
                "$declaration must re-apply the colours",
                b.contains("applyPreviewSize() applyBubbleColours()"),
            )
        }
        // The CONNECTING label's own show path is the third: it makes the panel visible before
        // any session preview exists, and it writes to the live strip.
        assertTrue(text.contains("applyPreviewSize() applyBubbleColours() transcriptionEditText.visibility = View.GONE"))
    }

    @Test
    fun theStripsSTATUSLinesDoNotInheritTheLiveColour() {
        // The strip carries FOUR kinds of content and only one is what the ruling is about: the
        // preview words. "Connecting…", "Finishing transcript…" / "Finishing… (waiting on
        // provider)" and the in-flight queue-depth label are STATUS, they were near-white
        // (`#E6FFFFFF`), and painting them red would be a change a user who never opened the
        // setting did not ask for — with red on a status line reading as an error where nothing
        // has happened.
        //
        // The resting role is STATUS and only the words path raises it, so a writer added to
        // this strip later is a status line by default: the worst a forgotten call costs is
        // white words where red was wanted, never a red warning where nothing is wrong.
        assertEquals("the resting role is status", 1, count(applyColours, "applyStripRole(words = false)"))
        assertEquals("ONE site raises the words role", 1, count(text, "applyStripRole(words = true)"))
        // And it is the onDelta words write that raises it, immediately before the text lands.
        assertTrue(text.contains("applyStripRole(words = true) transcriptionDeltaText.text = text"))
        // The closing status line is the one place the role has to be put BACK: a live session
        // has just been writing words here in the live colour, and "Finishing…" is not words.
        assertTrue(
            text.contains(
                "applyStripRole(words = false) transcriptionDeltaText.text = if (cloudWrapper != null) {"
            )
        )
        // Four call sites total: three panel shows (through applyBubbleColours) plus the words
        // raise plus the closing reset — i.e. two direct `words = false` and two more via the
        // three applyBubbleColours() calls, which this counts as its own single site.
        assertEquals("two direct status resets", 2, count(text, "applyStripRole(words = false)"))
    }

    @Test
    fun theTWOBlackBLOBLiteralsAreFoldedToONEFact() {
        // The brief's *"two sites for one fact: fold them"*. Both were
        // `Color.parseColor("#000000")` for the recording pill; `@color/bubble_recording` is the
        // resource that state already has (the recording drawable is drawn from it) and it was
        // otherwise unread from code. One definition now, in colors.xml.
        assertEquals("no hardcoded blob black left", 0, count(text, "parseColor(\"#000000\")"))
        assertEquals(
            "both recording sites read the one resource",
            2,
            count(text, "R.color.bubble_recording"),
        )
        assertFalse("and no hex literal anywhere in the bubble's colours", text.contains("parseColor(\"#"))
    }

    @Test
    fun theLAYOUTSColoursAreTheDEFAULTSBeforeTheFirstApplyAndTheCodeSaysSo() {
        // The XML still carries #FFFFFF / #E6FFFFFF / #E6000000, and it must: they are what the
        // panel renders with for the frames before `applyBubbleColours` first runs, and on any
        // device where the background is not the shape drawable we expect. They are NOT the
        // authority, which is exactly the relationship the layout already documents for the
        // 280dp width ("RUNTIME-OWNED (applyPreviewSize)").
        assertTrue(layout.contains("android:textColor=\"#FFFFFF\""))
        assertTrue(layout.contains("android:textColor=\"#E6FFFFFF\""))
        assertTrue(layout.contains("RUNTIME-OWNED"))
        // And the fallback is silent rather than a crash: a background that is not a
        // GradientDrawable leaves the drawable's own #E6000000 in place.
        assertTrue(applyColours.contains("as? android.graphics.drawable.GradientDrawable"))
    }
}
