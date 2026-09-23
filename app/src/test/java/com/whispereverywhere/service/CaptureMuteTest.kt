package com.whispereverywhere.service

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE MUTE, behaviourally (owner ruling 2026-09-22: *"you mute the microphone and mute everything
 * so another person walking in, audio won't enter your window"*). [CaptureMute.gate] is the one
 * seam every captured chunk passes before anything else sees it; these pin what it does to the
 * bytes, and the session-scoped lifecycle around it.
 */
class CaptureMuteTest {

    private fun chunk(n: Int = 1024) = ByteArray(n) { (it * 37 + 11).toByte() }

    @Test
    fun unmutedAudioPassesUntouched() {
        val mute = CaptureMute()
        val c = chunk()
        val before = c.copyOf()
        assertEquals("the level passes through", 4321, mute.gate(c, 4321))
        assertArrayEquals("and not one byte moves", before, c)
    }

    @Test
    fun mutedAudioIsSilenceOfTheSameLengthAtLevelZero() {
        // SILENCE, not a dropped chunk: the same number of bytes keeps audio time equal to wall
        // time, which the cap cut's retain window and the endpointer's stamps depend on, and it is
        // what lets the endpointer commit what was said before the tap.
        val mute = CaptureMute()
        mute.set(on = true, nowMs = 1_000, source = "MIC", state = "RECORDING")
        val c = chunk(1024)
        assertEquals("the level reads as silence", 0, mute.gate(c, 9000))
        assertEquals("same length", 1024, c.size)
        assertTrue("every byte zero", c.all { it == 0.toByte() })
    }

    @Test
    fun theEdgesLogOnceAndTheOffLineSaysHowMuchWasSilenced() {
        val mute = CaptureMute()
        assertEquals(
            "mute: on source=PLAYBACK state=RECORDING",
            mute.set(on = true, nowMs = 10_000, source = "PLAYBACK", state = "RECORDING"),
        )
        assertNull("a second 'on' is not an edge", mute.set(on = true, nowMs = 10_050, source = "PLAYBACK", state = "RECORDING"))
        // 32 bytes per ms at 16 kHz mono PCM16: three 1024-byte chunks are 96 ms.
        repeat(3) { mute.gate(chunk(1024), 500) }
        assertEquals(96L, mute.zeroedMs())
        assertEquals("mute: off heldMs=2500 zeroedMs=96", mute.set(on = false, nowMs = 12_500, source = "PLAYBACK", state = "RECORDING"))
        assertFalse(mute.muted)
        assertNull("a second 'off' is not an edge", mute.set(on = false, nowMs = 12_600, source = "MIC", state = "RECORDING"))
    }

    @Test
    fun aNewMuteStartsItsOwnCount() {
        val mute = CaptureMute()
        mute.set(on = true, nowMs = 0, source = "MIC", state = "RECORDING")
        mute.gate(chunk(3200), 1)
        mute.set(on = false, nowMs = 100, source = "MIC", state = "RECORDING")
        mute.set(on = true, nowMs = 200, source = "MIC", state = "RECORDING")
        assertEquals("the second mute has zeroed nothing yet", 0L, mute.zeroedMs())
    }

    @Test
    fun theSessionBoundaryAlwaysUnmutesAndOnlyReportsAMuteThatWasStillOn() {
        // Every session starts unmuted: a mute remembered into the next session would record
        // nothing and look like a broken app.
        val mute = CaptureMute()
        assertNull("an unmuted session ends with nothing to say", mute.endSession(nowMs = 5_000))
        mute.set(on = true, nowMs = 1_000, source = "MIC", state = "RECORDING")
        mute.gate(chunk(640), 1)
        assertEquals("mute: session ended muted heldMs=4000 zeroedMs=20", mute.endSession(nowMs = 5_000))
        assertFalse(mute.muted)
        val c = chunk()
        val before = c.copyOf()
        mute.gate(c, 7)
        assertArrayEquals("after the session ends audio flows again", before, c)
        // And a session still muted at the NEXT start is unmuted there too (belt and braces).
        mute.set(on = true, nowMs = 6_000, source = "MIC", state = "RECORDING")
        assertEquals("mute: session ended muted heldMs=1000 zeroedMs=0", mute.beginSession(nowMs = 7_000))
        assertFalse(mute.muted)
    }

    @Test
    fun theStopPathCanStillTellAMutedSessionFromASilentOne() {
        // The "nothing was transcribed" message is chosen AFTER teardown has unmuted, so the fact
        // that the session was muted at all must outlive endSession — and only beginSession, the
        // next session's start, clears it.
        val mute = CaptureMute()
        assertFalse(mute.mutedThisSession)
        mute.set(on = true, nowMs = 0, source = "MIC", state = "RECORDING")
        mute.set(on = false, nowMs = 10, source = "MIC", state = "RECORDING")
        mute.endSession(nowMs = 20)
        assertTrue("remembered past the teardown", mute.mutedThisSession)
        mute.beginSession(nowMs = 30)
        assertFalse("and forgotten by the next session", mute.mutedThisSession)
    }
}
