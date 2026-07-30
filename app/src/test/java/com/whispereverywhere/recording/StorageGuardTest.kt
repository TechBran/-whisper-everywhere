package com.whispereverywhere.recording

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageGuardTest {

    @Test fun ample_space_is_allowed() {
        assertTrue(StorageGuard.enoughSpace(availableBytes = 100_000_000, requiredBytes = 10_000_000))
    }

    @Test fun a_ten_percent_headroom_is_required_not_a_bare_fit() {
        // required * 1.1 must fit. A bare fit (available == required) is rejected: writing to the
        // last byte of the filesystem is how a recording gets truncated mid-flight.
        assertFalse(StorageGuard.enoughSpace(availableBytes = 10_000_000, requiredBytes = 10_000_000))
        assertTrue(StorageGuard.enoughSpace(availableBytes = 11_000_001, requiredBytes = 10_000_000))
    }

    @Test fun zero_required_always_fits() {
        assertTrue(StorageGuard.enoughSpace(availableBytes = 0, requiredBytes = 0))
    }
}
