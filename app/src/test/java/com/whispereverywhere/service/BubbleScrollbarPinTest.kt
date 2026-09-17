package com.whispereverywhere.service

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE TRANSCRIPT WINDOW'S SCROLLBARS ARE VISIBLE, pinned as layout text (4.8.0, owner ruling
 * 2026-09-17 after the 96 tablet session: *"I see a scrolling bar on the right side for the preview
 * and for the committed text. Previous builds don't seem to have that… I wanna see that there, and
 * I wanna make sure that it's visible. That scrolling bar is very useful so people know that they
 * can actually scroll previously."*).
 *
 * The bar is a framework drawing on two `TextView`s the service scrolls by hand
 * (`ScrollingMovementMethod` + `scrollTo`), so no JVM test can render it. What made it invisible
 * before was one DEFAULT: `fadeScrollbars` is true unless the layout says otherwise, and a bar that
 * fades to nothing at rest is a bar nobody sees. The other half was the framework's own thumb — a
 * faint grey hairline (the live strip had it at 2dp) that does not read as a bar on a dark panel.
 *
 * So this pins the four attributes on BOTH ids, the `BubbleColoursWiringPinTest` way: read the XML,
 * find each view's own element, and assert inside it — never a file-wide `contains`, which one view
 * could satisfy for the other.
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

    private fun read(relative: String): String =
        source(relative).readText().replace("\r\n", "\n")

    private val layout: String by lazy { read("src/main/res/layout/floating_bubble.xml") }

    /** The one `<TextView …/>` element carrying [id], from its `<TextView` to its `/>`. */
    private fun element(id: String): String {
        val idAt = layout.indexOf("android:id=\"@+id/$id\"")
        assertTrue("no view with id $id in floating_bubble.xml", idAt >= 0)
        val open = layout.lastIndexOf("<TextView", idAt)
        assertTrue("$id is not a TextView", open >= 0)
        val close = layout.indexOf("/>", idAt)
        assertTrue("$id's element never closes", close > idAt)
        return layout.substring(open, close + 2)
    }

    private val committed: String by lazy { element("transcription_edit_text") }
    private val live: String by lazy { element("transcription_delta_text") }

    private fun attr(element: String, name: String): String? =
        Regex("android:$name=\"([^\"]*)\"").find(element)?.groupValues?.get(1)

    @Test
    fun bothTextViewsKeepTheirScrollbarOnScreenInsteadOfFadingIt() {
        // THE DEFAULT THAT HID IT. `fadeScrollbars` defaults to true, so an attribute that is
        // merely absent is a bar that vanishes at rest — which is what "previous builds don't
        // seem to have that" was. Both views must say `false` in so many words.
        for ((name, view) in listOf("committed" to committed, "live" to live)) {
            assertEquals("$name: scrollbars must be vertical", "vertical", attr(view, "scrollbars"))
            assertEquals("$name: the bar must not fade", "false", attr(view, "fadeScrollbars"))
            // Overlay, explicitly: the bar sits on the text's right edge and takes no width from
            // the 280dp the resize maths owns. Stating the default keeps it a decision.
            assertEquals("$name: overlay style", "insideOverlay", attr(view, "scrollbarStyle"))
        }
    }

    @Test
    fun theBarIsWideEnoughToSeeAndDrawnWithTheSharedThumbOnAFaintTrack() {
        // 2dp — what the live strip shipped with at 4.4 — was "barely visible"; the ruling is
        // that the bar is SEEN. At least 4dp, on both, read as a number so a future "2dp" fails
        // here and not on a tablet.
        for ((name, view) in listOf("committed" to committed, "live" to live)) {
            val size = attr(view, "scrollbarSize")
            assertTrue("$name: scrollbarSize must be in dp, was $size", size != null && size.endsWith("dp"))
            val dp = size!!.removeSuffix("dp").toInt()
            assertTrue("$name: scrollbarSize must be at least 4dp, was ${dp}dp", dp >= 4)
            // The SAME thumb and track on both views — one drawable each, so the two bars on the
            // one panel cannot drift into two designs.
            assertEquals("$name: thumb", "@drawable/bubble_scrollbar_thumb", attr(view, "scrollbarThumbVertical"))
            assertEquals("$name: track", "@drawable/bubble_scrollbar_track", attr(view, "scrollbarTrackVertical"))
        }
        // And the fade delay is left alone: with the fade OFF it is inert, and a value here
        // would be a second opinion about a fade that does not happen.
        assertFalse(committed.contains("scrollbarDefaultDelayBeforeFade"))
        assertFalse(live.contains("scrollbarDefaultDelayBeforeFade"))
    }

    @Test
    fun theThumbIsBrightAndTheTrackIsFaint_bothRoundedRectangles() {
        // The drawables the layout names, and the two colours that make a bar READ as a bar on
        // a black panel at any opacity: a 70% white thumb over a 20% white track. Both are white
        // at an alpha rather than a palette colour on purpose — the panel's text colours are the
        // user's (BubbleColours) and the bar is furniture, not content.
        val thumb = read("src/main/res/drawable/bubble_scrollbar_thumb.xml")
        val track = read("src/main/res/drawable/bubble_scrollbar_track.xml")
        assertTrue(thumb.contains("android:shape=\"rectangle\""))
        assertTrue(thumb.contains("<solid android:color=\"#B3FFFFFF\" />"))
        assertTrue(thumb.contains("<corners android:radius=\"2dp\" />"))
        assertTrue(track.contains("android:shape=\"rectangle\""))
        assertTrue(track.contains("<solid android:color=\"#33FFFFFF\" />"))
        assertTrue(track.contains("<corners android:radius=\"2dp\" />"))
    }
}
