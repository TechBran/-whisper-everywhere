package com.whispereverywhere.service

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE RESIZE HANDLE IS RED AND OPAQUE, pinned as resource text (4.9.1, owner ruling 2026-09-17
 * on the tablet: *"the resizing arrow, we need to make that a color where we can actually see
 * it. I say red"*). The handle shipped white at 70% on a panel whose text is white; the owner
 * could not find it.
 *
 * The colour is the app's own red — the literal behind [BubbleColours.LIVE_DEFAULT], read here
 * from the constant so the handle and the live words can never drift into two reds. The touch
 * target and padding are pinned with it: making the handle visible must not make it smaller.
 * Same idiom as `BubbleScrollbarPinTest`: the view's own element, never a file-wide `contains`.
 */
class ResizeHandlePinTest {

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
    private val drawable: String by lazy { read("src/main/res/drawable/ic_resize_handle.xml") }

    private val handle: String by lazy {
        val idAt = layout.indexOf("android:id=\"@+id/resize_handle\"")
        assertTrue("no resize_handle in floating_bubble.xml", idAt >= 0)
        val open = layout.lastIndexOf("<ImageView", idAt)
        val close = layout.indexOf("/>", idAt)
        assertTrue(open >= 0 && close > idAt)
        layout.substring(open, close + 2)
    }

    private fun attr(element: String, name: String): String? =
        Regex("android:$name=\"([^\"]*)\"").find(element)?.groupValues?.get(1)

    @Test
    fun theHandleIsTheAppsOwnRed() {
        // The hex the vector carries must be the literal behind LIVE_DEFAULT (0xFFFF5252):
        // drop the alpha byte and compare as text, so a palette edit that moves the live red
        // fails here and not on a tablet.
        val liveRed = "#%06X".format(BubbleColours.LIVE_DEFAULT and 0xFFFFFF)
        assertEquals("#FF5252", liveRed)
        assertEquals("the vector's fill", liveRed, attr(drawable, "fillColor"))
    }

    @Test
    fun theHandleIsOpaqueAndKeepsItsTouchTarget() {
        assertEquals("1.0", attr(handle, "alpha"))
        assertEquals("@drawable/ic_resize_handle", attr(handle, "src"))
        assertEquals("28dp", attr(handle, "layout_width"))
        assertEquals("28dp", attr(handle, "layout_height"))
        assertEquals("6dp", attr(handle, "padding"))
        assertEquals("top|end", attr(handle, "layout_gravity"))
    }
}
