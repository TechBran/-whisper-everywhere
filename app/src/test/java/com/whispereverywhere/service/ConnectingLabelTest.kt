package com.whispereverywhere.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConnectingLabelTest {

    @Test
    fun aColdLocalSessionNamesTheModelLoad() {
        assertEquals(
            "Loading speech model…",
            connectingStatusLabel(isCloudSession = false, localEngineWarm = false),
        )
    }

    @Test
    fun aWarmLocalSessionKeepsTheBareSpinner() {
        assertNull(connectingStatusLabel(isCloudSession = false, localEngineWarm = true))
    }

    @Test
    fun cloudSessionsNeverClaimAModelLoad() {
        // Their CONNECTING wait is the socket/handshake — "loading speech model" would be a lie.
        assertNull(connectingStatusLabel(isCloudSession = true, localEngineWarm = false))
        assertNull(connectingStatusLabel(isCloudSession = true, localEngineWarm = true))
    }
}
