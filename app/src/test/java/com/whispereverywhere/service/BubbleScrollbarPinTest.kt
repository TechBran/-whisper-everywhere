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
 *
 * The one thing 4.8.0's attributes gave for free and a custom view does not is a HEIGHT: a
 * scrubber that is 0px tall is neither drawn nor hit, and a green suite that pinned only
 * `match_parent` certified exactly that (the 99 review). So the pin here is the MECHANISM that
 * gives the scrubber its height — the [TranscriptScrubberFrame] around each pair, re-measuring
 * the scrubber EXACTLY to its TextView — and the geometry is `TranscriptScrubberMath
 * .scrubberHeight`, held by `TranscriptScrubberMathTest`.
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
    private val frameSource: String by lazy { read("src/main/java/com/whispereverywhere/ui/components/TranscriptScrubberFrame.kt") }

    private val SCRUBBER = "<com.whispereverywhere.ui.components.TranscriptScrubberView"
    private val FRAME = "<com.whispereverywhere.ui.components.TranscriptScrubberFrame"

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

    /** The innermost TranscriptScrubberFrame element around the element carrying [id]. */
    private fun wrapperOf(id: String): String {
        val idAt = layout.indexOf("android:id=\"@+id/$id\"")
        val open = layout.lastIndexOf(FRAME, idAt)
        val close = layout.indexOf("</com.whispereverywhere.ui.components.TranscriptScrubberFrame>", idAt)
        assertTrue("$id is not inside a TranscriptScrubberFrame", open >= 0 && close > idAt)
        // No plain FrameLayout opens between the frame and the view: the frame IS its parent.
        assertTrue("$id: a plain FrameLayout sits between it and its frame", layout.lastIndexOf("<FrameLayout", idAt) < open)
        return layout.substring(open, close)
    }

    /** The body of the one `override fun [name](` in [source], whitespace-normalised. */
    private fun body(source: String, name: String): String {
        val start = source.indexOf("override fun $name(")
        assertTrue("no override fun $name", start >= 0)
        val end = source.indexOf("\n    }\n", start)
        assertTrue(end > start)
        return source.substring(start, end).replace(Regex("\\s+"), " ")
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
    fun eachTextViewHasItsOwnScrubberBesideItInATranscriptScrubberFrame() {
        // A scrubber is a sibling of its TextView in a TranscriptScrubberFrame — the frame puts
        // it on the panel's right edge without a layer the resize maths would have to know
        // about, and (the next test) gives it the TextView's height.
        assertTrue(wrapperOf("transcription_edit_text").contains("android:id=\"@+id/transcript_scrubber\""))
        assertTrue(wrapperOf("transcription_delta_text").contains("android:id=\"@+id/delta_scrubber\""))
        assertEquals("exactly two scrubbers on the panel", 2, layout.split(SCRUBBER).size - 1)
        assertEquals("exactly two frames on the panel", 2, layout.split(FRAME).size - 1)
        for ((name, s) in listOf("committed" to committedScrubber, "live" to liveScrubber)) {
            // NOT match_parent: a lone match_parent child of a wrap_content FrameLayout is measured
            // ONCE, with an AT_MOST spec, and never re-measured to its sibling (FrameLayout does
            // that for two or more only) — 0px, the 99 review's finding. The height is the
            // frame's to give; wrap_content says the view claims none of its own.
            assertEquals("$name scrubber: no height of its own", "wrap_content", attr(s, "layout_height"))
            assertEquals("$name scrubber: on the right edge", "top|end", attr(s, "layout_gravity"))
            assertTrue("$name scrubber: a grabbable lane, at least 10dp", dp(attr(s, "layout_width")) >= 10)
            // It starts out of the way and decides for itself when to appear (sync()).
            assertEquals("$name scrubber: starts hidden", "invisible", attr(s, "visibility"))
        }
        // The strip keeps its own 4dp top margin and its scrubber carries NONE: a FrameLayout
        // measures every non-GONE child's margins into its height, and the scrubber is never
        // GONE (sync() chooses VISIBLE or INVISIBLE), so a margin on it would outlive a GONE
        // strip as 4dp of phantom panel. With no margin and a 0 height (scrubberHeight of a
        // GONE strip) the frame collapses to nothing, as the strip alone did in 4.9.0.
        assertEquals("4dp", attr(live, "layout_marginTop"))
        assertNull("the strip's scrubber carries no margin of its own", attr(liveScrubber, "layout_marginTop"))
        assertNull(attr(liveScrubber, "layout_margin"))
        assertNull(attr(liveScrubber, "layout_marginBottom"))
        assertEquals("the scrubber is never GONE: VISIBLE or INVISIBLE only",
            1, scrubberSource.split("visibility = if (show) VISIBLE else INVISIBLE").size - 1)
        assertFalse(scrubberSource.contains("visibility = GONE"))
    }

    @Test
    fun theFrameMeasuresEachScrubberExactlyToItsTextViewEveryPass() {
        // THE HEIGHT MECHANISM. The view itself answers 0 to any non-EXACT spec (the only answer
        // that does not balloon the panel to the screen on the frame's first pass) …
        val viewMeasure = body(scrubberSource, "onMeasure")
        assertTrue(viewMeasure.contains("resolveSize(suggestedMinimumHeight, heightMeasureSpec)"))
        assertFalse("no minimum height of its own", scrubberSource.contains("minimumHeight ="))
        // … and the frame, AFTER super has measured the TextView (so its measuredHeight is
        // fresh in this pass), measures every scrubber a second time, EXACTLY, to the height
        // TranscriptScrubberMath.scrubberHeight computes from that TextView. Every pass, not
        // once: View.measure caches a child whose spec is unchanged, and the scrubber's spec
        // never changes on its own when the strip grows, shrinks or goes GONE.
        val frameMeasure = body(frameSource, "onMeasure")
        val superAt = frameMeasure.indexOf("super.onMeasure(widthMeasureSpec, heightMeasureSpec)")
        val heightAt = frameMeasure.indexOf("val height = TranscriptScrubberMath.scrubberHeight( targetGone = target.visibility == GONE, targetMeasuredHeight = target.measuredHeight, targetTopMargin = TranscriptScrubberView.topMarginOf(target), ownTopMargin = TranscriptScrubberView.topMarginOf(scrubber), )")
        val measureAt = frameMeasure.indexOf("scrubber.measure( MeasureSpec.makeMeasureSpec(scrubber.measuredWidth, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY), )")
        assertTrue("super first", superAt >= 0)
        assertTrue("the height from the maths, after super", heightAt > superAt)
        assertTrue("the EXACT re-measure, after the height", measureAt > heightAt)
        assertTrue("to the TextView the scrubber is BOUND to", frameMeasure.contains("val target = scrubber.target ?: continue"))
        assertTrue(frameSource.contains(") : FrameLayout(context, attrs)"))
        // The bar is drawn and grabbed from the TextView's top inside the view (the strip's 4dp
        // margin, which its scrubber does not carry), never from 0 blindly.
        assertTrue(scrubberSource.contains("TranscriptScrubberMath.trackTop("))
        assertTrue(body(scrubberSource, "onDraw").contains("val trackY = trackTop"))
        assertTrue(body(scrubberSource, "onTouchEvent").contains("val y = event.y - trackTop"))
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
    fun thePanelFollowsTheNewestLineOnlyWhenTheReaderIsAlreadyThere() {
        // THE OWNER'S 2026-09-19 REPORT, the second half: "the earlier parts of the transcript
        // are disappearing … when I scroll back up". The panel is re-assigned its whole text on
        // every repaint, and since the retrospective reclusterer a repaint can carry no new
        // words at all (a relabel every few chunks, and one at stop) — so an unconditional
        // scroll-to-newest yanked a reader back to the bottom for a change they could not see,
        // and fought the scrubber they were holding.
        val start = serviceRaw.indexOf("            sink.preview.collectLatest { text ->")
        assertTrue("the panel's preview collector is gone or renamed", start >= 0)
        val collector = serviceRaw.substring(start, serviceRaw.indexOf("\n        }\n", start))
        val flat = collector.replace(Regex("\\s+"), " ")

        // 1. The position is read BEFORE the text changes — the only moment the question
        //    "was the reader at the bottom?" has an answer.
        val read = flat.indexOf("val was = transcriptionEditText.scrollY")
        val pinned = flat.indexOf("val pinned = com.whispereverywhere.ui.components.TranscriptScrubberMath.atBottom(")
        val setText = flat.indexOf("transcriptionEditText.text = text")
        assertTrue("the pre-change scroll is read first", read >= 0 && read < setText)
        assertTrue("and the at-bottom test is made on it, before the text changes", pinned in (read + 1) until setText)

        // 2. Exactly ONE scrollTo on the panel in the whole service, it is in this collector,
        //    and what it scrolls to is the maths' verdict — never a bare bottom.
        assertEquals("one place scrolls the panel", 1, service.split("transcriptionEditText.scrollTo(").size - 1)
        val scroll = flat.indexOf("transcriptionEditText.scrollTo(")
        assertTrue("the one scrollTo is in the collector, after the text is set", scroll > setText)
        assertTrue(
            "the scroll target is followScrollY of the pre-change verdict",
            flat.contains(
                "com.whispereverywhere.ui.components.TranscriptScrubberMath.followScrollY( " +
                    "wasAtBottom = pinned, previousScrollY = was, maxScroll = max, )",
            ),
        )

        // 3. A finger on the scrubber wins outright: the collector does not scroll at all while
        //    the bar is being dragged, and it asks the bar rather than keeping its own flag.
        val guard = flat.indexOf("if (layout != null && !transcriptScrubber.isScrubbing)")
        assertTrue("the scrubber guard precedes the scrollTo", guard in (setText + 1) until scroll)
        assertTrue("isScrubbing is the view's own dragging state, not a copy",
            scrubberSource.contains("val isScrubbing: Boolean get() = dragging"))
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
