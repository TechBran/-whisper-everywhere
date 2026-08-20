package com.whispereverywhere.transcription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Workstream B (3.6.0): the session-scoped language pin, pure. auto -> detect -> pinned ->
 * reset; explicit languages bypass entirely and never pin.
 */
class LanguagePinTest {

    @Test
    fun autoSession_passesNullUntilADetectionArrives() {
        val pin = LanguagePin()
        assertNull(pin.languageFor(null))       // first segment: native auto-detect
        assertNull(pin.languageFor(null))       // still auto until something is detected
    }

    @Test
    fun detection_pinsForTheRestOfTheSession() {
        val pin = LanguagePin()
        pin.onDetected(sessionLanguage = null, detected = "de")
        assertEquals("de", pin.languageFor(null))
        assertEquals("de", pin.languageFor(null))
    }

    @Test
    fun firstDetectionWins() {
        val pin = LanguagePin()
        pin.onDetected(sessionLanguage = null, detected = "de")
        pin.onDetected(sessionLanguage = null, detected = "fr")   // a later, different detection
        assertEquals("de", pin.languageFor(null))
    }

    @Test
    fun reset_clearsThePin_soTheNextSessionReDetects() {
        val pin = LanguagePin()
        pin.onDetected(sessionLanguage = null, detected = "de")
        pin.reset()
        assertNull(pin.languageFor(null))
    }

    @Test
    fun explicitLanguage_passesThroughAndNeverPins() {
        val pin = LanguagePin()
        assertEquals("en", pin.languageFor("en"))
        pin.onDetected(sessionLanguage = "en", detected = "de")   // explicit sessions never pin
        assertNull(pin.languageFor(null))
        assertEquals("en", pin.languageFor("en"))
        pin.onDetected(sessionLanguage = null, detected = "de")  // a genuine auto pin now exists
        assertEquals("de", pin.languageFor(null))
        assertEquals("en", pin.languageFor("en"))                // explicit still beats the pin
    }

    @Test
    fun unusableDetections_neverPin() {
        val pin = LanguagePin()
        pin.onDetected(sessionLanguage = null, detected = null)
        pin.onDetected(sessionLanguage = null, detected = "")
        pin.onDetected(sessionLanguage = null, detected = "auto")
        assertNull(pin.languageFor(null))
    }
}
