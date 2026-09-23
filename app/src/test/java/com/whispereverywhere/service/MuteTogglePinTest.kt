package com.whispereverywhere.service

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE MUTE TOGGLE, pinned where it is visible and where it is wired (owner ruling 2026-09-22:
 * *"in the top left hand corner ... just like how we have our diagonal arrow on the right side, we
 * should have a mic toggle ... normal, it will sit pretty transparent but still visible ... when
 * you press it ... the microphone should be solid red with the X going through it"*).
 *
 * `CaptureMuteTest` holds what the mute does to audio. What no behavioural test can see is whether
 * the service actually routes every chunk through it FIRST, whether a tap on the toggle can reach
 * the window (where it would stop the session instead), and whether the icons are the colours the
 * owner asked for — so those are pinned here as source and resource text, the ResizeHandlePinTest
 * idiom: each view's own element, each body's own text, never a file-wide `contains` for a fact
 * that belongs to one place.
 */
class MuteTogglePinTest {

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

    private fun read(relative: String): String = source(relative).readText().replace("\r\n", "\n")

    private val layout by lazy { read("src/main/res/layout/floating_bubble.xml") }
    private val live by lazy { read("src/main/res/drawable/ic_mic_live.xml") }
    private val muted by lazy { read("src/main/res/drawable/ic_mic_muted.xml") }
    private val service by lazy {
        read("src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt")
    }
    private val serviceText by lazy { service.replace(Regex("\\s+"), " ") }

    private fun attr(element: String, name: String): String? =
        Regex("android:$name=\"([^\"]*)\"").find(element)?.groupValues?.get(1)

    private fun element(id: String): String {
        val idAt = layout.indexOf("android:id=\"@+id/$id\"")
        assertTrue("no $id in floating_bubble.xml", idAt >= 0)
        val open = layout.lastIndexOf("<", idAt)
        val close = layout.indexOf(">", idAt)
        return layout.substring(open, close + 1)
    }

    private fun body(declaration: String): String {
        val start = service.indexOf(declaration)
        assertTrue("missing from FloatingBubbleService.kt: <<$declaration>>", start >= 0)
        val close = service.indexOf("\n    }\n", start)
        assertTrue("the closing brace of <<$declaration>> moved", close > start)
        return service.substring(start, close)
    }

    @Test
    fun theToggleIsTheResizeArrowsMirror() {
        val toggle = element("mute_toggle")
        val handle = element("resize_handle")
        assertTrue("a self-closing ImageView", toggle.startsWith("<ImageView") && toggle.endsWith("/>"))
        assertEquals("top|start", attr(toggle, "layout_gravity"))
        assertEquals("top|end", attr(handle, "layout_gravity"))
        // Mirror means the SAME target: making the mute findable must not make it smaller than
        // the arrow opposite it.
        for (name in listOf("layout_width", "layout_height", "padding", "alpha", "background")) {
            assertEquals("the toggle's $name is the arrow's", attr(handle, name), attr(toggle, name))
        }
        assertEquals("@drawable/ic_mic_live", attr(toggle, "src"))
        assertEquals("Mute audio", attr(toggle, "contentDescription"))
    }

    @Test
    fun eachCornerControlHoldsItsOwnLittleBubble() {
        // Owner, on 108: they "should just hold their own little bubble ... like say a little
        // circle, for both". A 24dp disc (2dp inset) inside the unchanged 28dp target.
        for (id in listOf("mute_toggle", "resize_handle")) {
            assertEquals("$id sits on its disc", "@drawable/control_disc", attr(element(id), "background"))
        }
        val disc = read("src/main/res/drawable/control_disc.xml")
        assertTrue("an inset", disc.contains("<inset") && disc.contains("android:inset=\"2dp\""))
        assertTrue("of an oval", disc.contains("android:shape=\"oval\""))
        // The discs follow the fill, through the colour BubbleColours allows a disc.
        assertTrue(serviceText.contains("val disc = BubbleColours.controlDiscArgb(app.preferencesManager.bubbleOpacityPercent)"))
        assertTrue(serviceText.contains("for (control in listOf(muteToggle, resizeHandle))"))
        // And the text wraps around them: the collector sets the wrapped copy, a width change
        // re-wraps it, and the hint clears the mute disc too.
        assertTrue(serviceText.contains("transcriptionEditText.text = wrapPanel(text)"))
        // A resize re-wraps only when the breaks MOVE (a drag would otherwise lay the whole
        // transcript out twice per frame), and a session's text dies with the session.
        assertTrue(serviceText.contains("if (next != panelWrapping) transcriptionEditText.text = wrapPanel(panelRawText)"))
        assertTrue(body("    private fun teardownRealtime() {").contains("panelRawText = \"\""))
        assertTrue(serviceText.contains("transcriptionEditText.hint = CornerWrap.hint("))
    }

    @Test
    fun itLivesInTheCommittedFrameBesideTheArrow() {
        val frameAt = layout.indexOf("android:id=\"@+id/committed_frame\"")
        assertTrue("the committed frame has its id", frameAt >= 0)
        val frameEnd = layout.indexOf("</com.whispereverywhere.ui.components.TranscriptScrubberFrame>", frameAt)
        val toggleAt = layout.indexOf("android:id=\"@+id/mute_toggle\"")
        val handleAt = layout.indexOf("android:id=\"@+id/resize_handle\"")
        assertTrue("the toggle is inside the committed frame", toggleAt in frameAt..frameEnd)
        assertTrue("with the arrow", handleAt in frameAt..frameEnd)
        // NO header band: the text flows behind both corner controls (owner, on 108: "you can
        // let the text flow behind those items ... real estate is definitely the key here").
        assertNull(attr(element("transcription_edit_text"), "paddingTop"))
        assertFalse(
            "and the height is the user's own, with nothing added for a band",
            service.contains("height = heightPx + transcriptionEditText.paddingTop"),
        )
    }

    @Test
    fun theIconsAreTheColoursTheOwnerAskedFor() {
        // Flowing: the panel's own faint white, HINT_ARGB — "pretty transparent but still visible".
        val faint = "#%08X".format(BubbleColours.HINT_ARGB)
        assertEquals("#99FFFFFF", faint)
        assertEquals(faint, attr(live, "fillColor"))
        // Muted: SOLID red — the live red, the same red as the resize arrow opposite — with an X.
        val red = "#%06X".format(BubbleColours.LIVE_DEFAULT and 0xFFFFFF)
        assertEquals("the mic body is the live red", red, attr(muted, "fillColor"))
        assertTrue("and the X is drawn in it too", muted.contains("android:strokeColor=\"$red\""))
        assertTrue("an X: two crossing strokes", muted.contains("M5,4L19,18M19,4L5,18"))
    }

    @Test
    fun aTapOnTheToggleNeverReachesTheWindow() {
        // A tap that reached the root's handleTouch is a tap on the bubble — it STOPS the session.
        // The click listener consumes the toggle's own taps; the delegate covers the corner
        // padding a hurried near-miss would land in.
        assertTrue(serviceText.contains("muteToggle = bubbleView.findViewById(R.id.mute_toggle)"))
        assertTrue(serviceText.contains("muteToggle.setOnClickListener { toggleCaptureMute() }"))
        assertTrue(serviceText.contains("transcriptionPreviewContainer.touchDelegate = android.view.TouchDelegate("))
        assertEquals(
            "still exactly one root listener",
            1,
            serviceText.split("bubbleView.setOnTouchListener { _, event -> handleTouch(event) }").size - 1,
        )
        // And only a session with capture open can be muted.
        assertTrue(body("    private fun toggleCaptureMute() {").contains("if (!sessionStillWantsASource()) return"))
    }

    @Test
    fun everyChunkMeetsTheMuteBeforeAnythingElseSeesIt() {
        val onChunk = body("    private fun onAudioChunk(chunk: ByteArray, amp: Int) {")
        val gateAt = onChunk.indexOf("val amp = captureMute.gate(chunk, amp)")
        assertTrue("onAudioChunk gates the chunk", gateAt >= 0)
        assertTrue("before the startup seam routes it anywhere", gateAt < onChunk.indexOf("StartupSeam.route("))
        assertEquals("one gate, in one place", 1, service.split("captureMute.gate(").size - 1)
        // The ribbon's second feed skips onAudioChunk, so it takes the mute on its own — and it
        // must USE the gated level, not merely declare it.
        val feed = serviceText
            .substringAfter("audioRecorder.amplitude.collectLatest { amp ->")
            .substringBefore("amplitudeJob")
        assertTrue("the feed gates the level", feed.contains("val shown = if (captureMute.muted) 0 else amp"))
        assertTrue(feed.contains("waveformView.updateAmplitude(shown)"))
        assertTrue(feed.contains("blobView.updateAmplitude(shown)"))
        assertFalse("no raw level reaches the ribbon", feed.contains("updateAmplitude(amp)"))
        // The mute lives in the service's shared downstream, never in either capture source:
        // inside PlaybackAudioCapturer it would read as the silent-stream DRM case in the first
        // three seconds and hand the session to the MICROPHONE.
        for (capture in listOf(
            "src/main/java/com/whispereverywhere/util/StreamingAudioRecorder.kt",
            "src/main/java/com/whispereverywhere/audio/PlaybackAudioCapturer.kt",
        )) {
            assertFalse("$capture knows nothing of the mute", read(capture).contains("captureMute"))
            assertFalse("$capture knows nothing of the mute", read(capture).contains("CaptureMute"))
        }
    }

    @Test
    fun everySessionStartsAndEndsUnmuted() {
        val start = body("    private fun startRecording() {")
        val beginAt = start.indexOf("captureMute.beginSession(")
        assertTrue("startRecording unmutes", beginAt >= 0)
        val openAt = start.indexOf("val started = startAudioInput()")
        assertTrue("startRecording opens the recorder", openAt >= 0)
        assertTrue("above the recorder opening, like the other per-session capture state", beginAt < openAt)
        assertTrue("teardown unmutes", body("    private fun teardownRealtime() {").contains("captureMute.endSession("))
        assertEquals("one start", 1, service.split("captureMute.beginSession(").size - 1)
        assertEquals("one end", 1, service.split("captureMute.endSession(").size - 1)
    }

    @Test
    fun aMutedSessionIsNotToldToSpeakUp() {
        // "No speech detected — try again a bit louder" blames the microphone; a session the user
        // muted heard nothing because they asked it to.
        assertTrue(
            serviceText.contains(
                "if (captureMute.mutedThisSession) \"Nothing was transcribed — the audio was muted.\" " +
                    "else \"No speech detected — try again a bit louder or closer to the mic.\""
            )
        )
    }

    @Test
    fun theIconShowsTheMuteAndOnlyWhileCaptureIsOpen() {
        val toggle = body("    private fun toggleCaptureMute() {")
        val setAt = toggle.indexOf("captureMute.set(")
        assertTrue("the toggle flips the mute", setAt >= 0)
        assertTrue("and the icon follows the flip", toggle.indexOf("applyMuteIndicator()") > setAt)
        val indicator = body("    private fun applyMuteIndicator() {").replace(Regex("\\s+"), " ")
        assertTrue(indicator.contains("setImageResource(if (muted) R.drawable.ic_mic_muted else R.drawable.ic_mic_live)"))
        assertTrue(indicator.contains("contentDescription = if (muted) \"Unmute audio\" else \"Mute audio\""))
        // Capture is open in CONNECTING (the startup ring) and RECORDING, and only there.
        assertTrue(
            serviceText.contains(
                "muteToggle.visibility = if (newState == BubbleState.RECORDING || " +
                    "newState == BubbleState.CONNECTING) View.VISIBLE else View.INVISIBLE"
            )
        )
        // Every window show and the hide re-sync the icon: the declaration, the toggle, the two
        // window shows and the teardown.
        assertEquals(5, serviceText.split("applyMuteIndicator()").size - 1)
    }

    @Test
    fun theWindowAndTheBubbleAreOnePieceInTheLayout() {
        // The tab's flat top is drawn at the blob's y = 0, which is the panel's bottom edge only
        // while nothing separates them; and a shadow on either would show through the other's
        // translucent fill.
        for (id in listOf("transcription_preview_container", "lock_lobe", "speaker_lobe")) {
            val e = element(id)
            assertNull("$id casts no shadow across the translucent seam", attr(e, "elevation"))
            assertNull("$id has no bottom margin", attr(e, "layout_marginBottom"))
            assertNull("$id has no margin", attr(e, "layout_margin"))
        }
        // The corner controls keep their physical corners in every locale — the mute's widened
        // target is a fixed rect over the physical top-left.
        assertEquals("ltr", attr(element("committed_frame"), "layoutDirection"))
    }

    @Test
    fun theBubbleIsTheWindowsTabWhileTheWindowShows() {
        assertEquals("both window shows attach the tab", 2, serviceText.split("setBubbleAttached(true)").size - 1)
        assertEquals("the one hide detaches it", 1, serviceText.split("setBubbleAttached(false)").size - 1)
        assertTrue(body("    private fun teardownRealtime() {").contains("setBubbleAttached(false)"))
        // The three facts move together, in one place: the tab shape, the stack pulled up by the
        // tab's own inset (no neck under the window), and the seam clip.
        val attach = body("    private fun setBubbleAttached(attached: Boolean) {").replace(Regex("\\s+"), " ")
        assertTrue(attach.contains("blobView.attachedTop = attached"))
        assertTrue(attach.contains("val inset = if (attached) blobView.attachInsetPx else 0"))
        assertTrue(attach.contains("lp.topMargin = -inset"))
        assertTrue(attach.contains("stack.clipBounds = if (attached)"))
        assertTrue("the lock lobe moves down with the seam", attach.contains("lp.topMargin = inset"))
        // A density change re-seats the seam (the blob redraws its top from the new density).
        assertTrue(serviceText.contains("setBubbleAttached(transcriptionPreviewContainer.visibility == View.VISIBLE)"))
        // And the spinner's arc stays below the seam, so the clip never shaves it.
        val ring = read("src/main/res/drawable/ic_processing_ring.xml")
        assertTrue(ring.contains("M36,10 A26,26 0 0,1 62,36"))
        assertFalse("the old radius crossed the seam", ring.contains("A28,28"))
        assertEquals("the only writer of the tab flag", 1, serviceText.split("blobView.attachedTop =").size - 1)
    }

    @Test
    fun theDrawablesAreRegisteredAsPinnedInputs() {
        // A resource-only edit changes no .class file; without these entries a colour edit to
        // either icon would leave the suite UP-TO-DATE and this pin reading stale evidence.
        val gradle = read("build.gradle.kts")
        for (path in listOf(
            "\"src/main/res/drawable/ic_mic_live.xml\"",
            "\"src/main/res/drawable/ic_mic_muted.xml\"",
            "\"src/main/res/drawable/ic_resize_handle.xml\"",
            "\"src/main/res/drawable/control_disc.xml\"",
        )) {
            assertTrue("$path is a sourcePinnedInput", gradle.contains(path))
        }
    }
}
