package com.whispereverywhere.service

import com.whispereverywhere.audio.EndpointCut
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The exact bytes of the 3.7 endpoint diagnostic family (Workstream F). The owner's acceptance
 * sheet greps these strings; a silent drift breaks every report that parses them.
 */
class EndpointDiagTest {

    @Test
    fun endpointLineMatchesTheGreppableFormatExactly() {
        assertEquals(
            "endpoint: seq=4 cut=vad speechMs=2400 trailMs=500 p=0.42",
            EndpointDiag.endpointLine(
                seq = 4L,
                cut = EndpointDiag.VAD,
                ec = EndpointCut(speechMs = 2_400L, trailMs = 500L, prob = 0.42f),
            ),
        )
    }

    @Test
    fun theCutVocabularyIsExactlyTheFourSpeccedValues() {
        assertEquals("vad", EndpointDiag.VAD)
        assertEquals("cap", EndpointDiag.CAP)
        assertEquals("stop", EndpointDiag.STOP)
        assertEquals("switch", EndpointDiag.SWITCH)
    }

    @Test
    fun aCutWithNoEndpointerStateBehindItReportsMinusOneProb_neverAFabricatedZero() {
        // p=0.00 would read as "the probe was certain there was no speech". The sentinel matches
        // the native frame contract: -1 is "no verdict", and it is never "silence". A null ec is
        // how cap/stop/switch cuts and the whole amplitude path arrive here.
        assertEquals(
            "endpoint: seq=0 cut=stop speechMs=0 trailMs=0 p=-1.00",
            EndpointDiag.endpointLine(seq = 0L, cut = EndpointDiag.STOP, ec = null),
        )
    }

    @Test
    fun probIsFormattedLocaleIndependently() {
        val prior = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY) // comma decimal separator
            assertEquals(
                "endpoint: seq=1 cut=vad speechMs=300 trailMs=520 p=0.51",
                EndpointDiag.endpointLine(
                    seq = 1L,
                    cut = EndpointDiag.VAD,
                    ec = EndpointCut(speechMs = 300L, trailMs = 520L, prob = 0.51f),
                ),
            )
        } finally {
            java.util.Locale.setDefault(prior)
        }
    }

    @Test
    fun theCapLineKeepsItsPre37PrefixAndItsCapField_verbatim() {
        val line = EndpointDiag.capCommitLine(4_000L)
        // `wall-clock cap -> commit` is the existing grep; `cap=4000ms` in a CLOUD session is the
        // documented 3.6.0 regression signature. Both must survive the reword byte for byte.
        assertTrue(line.startsWith("wall-clock cap -> commit (cap=4000ms)"))
        assertTrue(line.contains("cap=4000ms"))
    }

    @Test
    fun theCapLineNamesItselfAsAVadFailureSignature() {
        assertEquals(
            "wall-clock cap -> commit (cap=15000ms) VAD-MISS: no endpoint in this window",
            EndpointDiag.capCommitLine(15_000L),
        )
    }
}
