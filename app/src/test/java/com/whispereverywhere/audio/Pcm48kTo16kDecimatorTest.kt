package com.whispereverywhere.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class Pcm48kTo16kDecimatorTest {

    private fun pcm(vararg samples: Int): ByteArray {
        val out = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            out[i * 2] = (samples[i] and 0xFF).toByte()
            out[i * 2 + 1] = ((samples[i] shr 8) and 0xFF).toByte()
        }
        return out
    }

    @Test fun `three samples average to one`() {
        val out = Pcm48kTo16kDecimator().process(pcm(300, 600, 900), 6)
        assertArrayEquals(pcm(600), out)
    }

    @Test fun `partial group carries across calls`() {
        val d = Pcm48kTo16kDecimator()
        // 5 samples: one full group (100,200,300)->200, leftovers (400,500) carried.
        val first = d.process(pcm(100, 200, 300, 400, 500), 10)
        assertArrayEquals(pcm(200), first)
        // +1 sample completes the carried group: (400,500,600)->500.
        val second = d.process(pcm(600), 2)
        assertArrayEquals(pcm(500), second)
    }

    @Test fun `too little input yields empty output`() {
        assertEquals(0, Pcm48kTo16kDecimator().process(pcm(42), 2).size)
    }

    @Test fun `negative samples decimate correctly`() {
        val out = Pcm48kTo16kDecimator().process(pcm(-300, -600, -900), 6)
        assertArrayEquals(pcm(-600), out)
    }
}
