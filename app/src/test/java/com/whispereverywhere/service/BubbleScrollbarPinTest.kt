package com.whispereverywhere.service

import com.whispereverywhere.ui.components.TranscriptScrubberView
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE TRANSCRIPT WINDOW'S SCROLLBARS ARE VISIBLE — AND GRABBABLE — pinned as layout and source
 * text. Two owner rulings on 2026-09-17, one build apart:
 *
 *  - 4.8.0 (the 96 tablet session): *"I see a scrolling bar on the right side for the preview and
 *    for the committed text … I wanna make sure that it's visible. That scrolling bar is very
 *    useful so people know that they can actually scroll previously."* That build kept the
 *    framework bar from fading (`fadeScrollbars=false`, 4dp, a drawn thumb on a faint track).
 *  - 4.9.1 (the 98 tablet session): *"if we touch the slider, we should be able to slide it up
 *    and down — and not only scroll by sliding the whole [text], which is fine as well."* The
 *    framework bar is a drawing that takes no touches, so the bar is now a
 *    [TranscriptScrubberView] beside each TextView and the TextViews' own scrollbars are NONE.
 *
 * `FloatingBubbleService` cannot be instantiated on the JVM and no JVM test can render the bar,
 * so the pins are structural, the `BubbleColoursWiringPinTest` way: each view's OWN element
 * (never a file-wide `contains`), whitespace-normalised call needles inside their declaring
 * bodies, and the constants read from the class. The visibility contract itself — the bar is
 * shown exactly when there is something to scroll — is `TranscriptScrubberMath.visible`, held
 * by `TranscriptScrubberMathTest`.
 */
class BubbleScrollbarPinTest {

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

    private fun exists(relative: String): Boolean = runCatching { source(relative) }.isSuccess

    private fun read(relative: String): String =
        source(relative).readText().replace("\r\n", "\n")

    private val layout: String by lazy { read("src/main/res/layout/floating_bubble.xml") }
    private val serviceRaw: String by lazy { read("src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt") }
    private val service: String by lazy { serviceRaw.replace(Regex("\\s+"), " ") }
    private val scrubberSource: String by lazy { read("src/main/java/com/whispereverywhere/ui/components/TranscriptScrubberView.kt") }

    private val SCRUBBER = "<com.whispereverywhere.ui.components.TranscriptScrubberView"

    /** The one element carrying [id], from its opening [tag] to its `/>`. */
    private fun element(id: String, tag: String): String {
        val idAt = layout.indexOf("android:id=\"@+id/$id\"")
        assertTrue("no view with id $id in floating_bubble.xml", idAt >= 0)
        val open = layout.lastIndexOf(tag, idAt)
        assertTrue("$id is not a $tag", open >= 0)
        val close = layout.indexOf("/>", idAt)
        assertTrue("$id's element never closes", close > idAt)
        return layout.substring(open, close + 2)
    }

    private val committed: String by lazy { element("transcription_edit_text", "<TextView") }
    private val live: String by lazy { element("transcription_delta_text", "<TextView") }
    private val committedScrubber: String by lazy { element("transcript_scrubber", SCRUBBER) }
    private val liveScrubber: String by lazy { element("delta_scrubber", SCRUBBER) }
    private val handle: String by lazy { element("resize_handle", "<ImageView") }

    private fun attr(element: String, name: String): String? =
        Regex("android:$name=\"([^\"]*)\"").find(element)?.groupValues?.get(1)

    private fun dp(value: String?): Int {
        assertTrue("expected a dp value, was $value", value != null && value.endsWith("dp"))
        return value!!.removeSuffix("dp").toInt()
    }

    /** The innermost `<FrameLayout … </FrameLayout>` around the element carrying [id]. */
    private fun wrapperOf(id: String): String {
        val idAt = layout.indexOf("android:id=\"@+id/$id\"")
        val open = layout.lastIndexOf("<FrameLayout", idAt)
        val close = layout.indexOf("</FrameLayout>", idAt)
        assertTrue("$id is not inside a FrameLayout", open >= 0 && close > idAt)
        return layout.substring(open, close)
    }

    @Test
    fun theTextViewsCarryNoFrameworkScrollbarAnyMore() {
        // The framework bar is gone in so many words — `none`, not merely absent — and with it
        // every attribute 4.8.0 used to make it visible. One bar per view, and it is the scrubber.
        for ((name, view) in listOf("committed" to committed, "live" to live)) {
            assertEquals("$name: scrollbars", "none", attr(view, "scrollbars"))
            for (gone in listOf("fadeScrollbars", "scrollbarSize", "scrollbarStyle", "scrollbarThumbVertical", "scrollbarTrackVertical")) {
                assertNull("$name: $gone belongs to the framework bar, which is gone", attr(view, gone))
            }
        }
        // And the 4.8.0 drawables went with it: a thumb nothing draws is a second opinion.
        assertFalse(exists("src/main/res/drawable/bubble_scrollbar_thumb.xml"))
        assertFalse(exists("src/main/res/drawable/bubble_scrollbar_track.xml"))
    }

    @Test
    fun eachTextViewHasItsOwnScrubberBesideItInTheSameWrapper() {
        // A scrubber is a sibling in the SAME FrameLayout as its TextView — that is what puts it
        // on the panel's right edge, `match_parent`-tall, without a layer the resize maths
        // would have to know about.
        assertTrue(wrapperOf("transcription_edit_text").contains("android:id=\"@+id/transcript_scrubber\""))
        assertTrue(wrapperOf("transcription_delta_text").contains("android:id=\"@+id/delta_scrubber\""))
        assertEquals("exactly two scrubbers on the panel", 2, layout.split(SCRUBBER).size - 1)
        for ((name, s) in listOf("committed" to committedScrubber, "live" to liveScrubber)) {
            assertEquals("$name scrubber: spans its TextView", "match_parent", attr(s, "layout_height"))
            assertEquals("$name scrubber: on the right edge", "top|end", attr(s, "layout_gravity"))
            assertTrue("$name scrubber: a grabbable lane, at least 10dp", dp(attr(s, "layout_width")) >= 10)
            // It starts out of the way and decides for itself when to appear (sync()).
            assertEquals("$name scrubber: starts hidden", "invisible", attr(s, "visibility"))
        }
        // The strip keeps its own 4dp top margin (on the wrapper it would outlive a GONE strip
        // as phantom panel), and its scrubber carries the same so the bar spans the text exactly.
        assertEquals(attr(live, "layout_marginTop"), attr(liveScrubber, "layout_marginTop"))
        assertEquals("4dp", attr(live, "layout_marginTop"))
    }

    @Test
    fun theCommittedScrubberStartsBelowTheResizeHandleSoTheHandleKeepsItsCorner() {
        // Both sit top|end in the one wrapper; the scrubber's top margin must clear the handle's
        // full touch target, or a finger for the handle lands on the bar.
        val handleDp = dp(attr(handle, "layout_height"))
        assertTrue(dp(attr(committedScrubber, "layout_marginTop")) >= handleDp)
        assertEquals("top|end", attr(handle, "layout_gravity"))
    }

    @Test
    fun theBarKeepsThe480ColoursAndWidth() {
        // The 4.8.0 ruling's look, now constants on the view: a 70% white thumb on a 20% white
        // track, 4dp. White at an alpha rather than a palette colour on purpose — the panel's
        // text colours are the user's (BubbleColours) and the bar is furniture, not content.
        assertEquals(0xB3FFFFFF.toInt(), TranscriptScrubberView.THUMB_COLOUR)
        assertEquals(0x33FFFFFF.toInt(), TranscriptScrubberView.TRACK_COLOUR)
        assertEquals(4f, TranscriptScrubberView.BAR_WIDTH_DP, 0f)
    }

    @Test
    fun theServiceBindsBothScrubbersAndLeavesTheRootDragListenerAlone() {
        val start = serviceRaw.indexOf("    private fun createBubbleView() {")
        assertTrue(start >= 0)
        val body = serviceRaw.substring(start, serviceRaw.indexOf("\n    }\n", start)).replace(Regex("\\s+"), " ")
        assertTrue(body.contains("transcriptScrubber = bubbleView.findViewById(R.id.transcript_scrubber) transcriptScrubber.bind(transcriptionEditText)"))
        assertTrue(body.contains("deltaScrubber = bubbleView.findViewById(R.id.delta_scrubber) deltaScrubber.bind(transcriptionDeltaText)"))
        // The root listener is untouched: one registration, the same lambda, still in this body.
        assertEquals(1, service.split("bubbleView.setOnTouchListener { _, event -> handleTouch(event) }").size - 1)
        assertTrue(body.contains("bubbleView.setOnTouchListener { _, event -> handleTouch(event) }"))
        // And the service never scrolls a SCRUBBER — it scrolls the TextViews, and they follow.
        assertFalse(service.contains("Scrubber.scrollTo("))
    }

    @Test
    fun theScrubberScrollsItsTextViewOnlyUnderAFinger() {
        // The panel auto-scrolls to its newest line on every commit and the strip to its newest
        // words on every delta. The scrubber must FOLLOW that, never fight it: its one scrollTo
        // lives in scrollToFinger, which returns unless a drag is in progress.
        assertEquals("one scrollTo in the view", 1, scrubberSource.split("scrollTo(").size - 1)
        val fn = scrubberSource.indexOf("private fun scrollToFinger(")
        val guard = scrubberSource.indexOf("if (!dragging) return", fn)
        val scroll = scrubberSource.indexOf("scrollTo(", fn)
        assertTrue("scrollTo is inside scrollToFinger", fn >= 0 && scroll > fn)
        assertTrue("the drag guard precedes the scrollTo", guard in (fn + 1) until scroll)
        // It owns its gesture: DOWN claims it from the root drag / long-press-to-pin.
        assertTrue(scrubberSource.contains("parent?.requestDisallowInterceptTouchEvent(true)"))
    }
}
