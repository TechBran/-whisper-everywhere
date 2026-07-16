package com.whispereverywhere.model

import org.junit.Assert.assertEquals
import org.junit.Test

class WhisperModelTest {

    @Test
    fun sha256Hex_ofEmptyInput_matchesKnownVector() {
        // SHA-256 of the empty byte array (RFC/NIST known vector)
        val expected = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        assertEquals(expected, WhisperCatalog.sha256Hex(ByteArray(0)))
    }

    @Test
    fun sha256Hex_ofAbc_matchesKnownVector() {
        // SHA-256("abc") known vector
        val expected = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        assertEquals(expected, WhisperCatalog.sha256Hex("abc".toByteArray(Charsets.US_ASCII)))
    }

    @Test
    fun sha256Hex_isLowercaseHex_64Chars() {
        val hex = WhisperCatalog.sha256Hex("whisper".toByteArray(Charsets.US_ASCII))
        assertEquals(64, hex.length)
        assertEquals(hex.lowercase(), hex)
        assertEquals(true, hex.all { it in "0123456789abcdef" })
    }
}
