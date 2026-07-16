package com.whispereverywhere.util

import org.junit.Assert.assertEquals
import org.junit.Test

class ByteFormatTest {

    @Test
    fun zeroAndNegativeClampToZeroBytes() {
        assertEquals("0 B", formatBytes(0L))
        assertEquals("0 B", formatBytes(-1L))
        assertEquals("0 B", formatBytes(-1_000_000L))
    }

    @Test
    fun bytesAndKilobytesHaveNoDecimals() {
        assertEquals("512 B", formatBytes(512L))
        assertEquals("999 B", formatBytes(999L))
        assertEquals("1 KB", formatBytes(1_000L))
        assertEquals("57 KB", formatBytes(57_000L))
    }

    @Test
    fun catalogSizesRenderAsWholeMegabytes() {
        assertEquals("57 MB", formatBytes(57_000_000L))
        assertEquals("190 MB", formatBytes(190_000_000L))
        assertEquals("539 MB", formatBytes(539_000_000L))
        assertEquals("574 MB", formatBytes(574_000_000L))
    }

    @Test
    fun megabytesKeepOneDecimalOnlyWhenNonZero() {
        assertEquals("1.5 MB", formatBytes(1_500_000L))
        assertEquals("2.3 MB", formatBytes(2_300_000L))
        assertEquals("1 MB", formatBytes(1_000_000L))
    }

    @Test
    fun gigabytesRollOverPastAThousandMegabytes() {
        assertEquals("1.5 GB", formatBytes(1_500_000_000L))
        assertEquals("2 GB", formatBytes(2_000_000_000L))
    }

    @Test
    fun terabytesAreTheLargestUnit() {
        assertEquals("1 TB", formatBytes(1_000_000_000_000L))
        assertEquals("1.2 TB", formatBytes(1_200_000_000_000L))
    }
}
