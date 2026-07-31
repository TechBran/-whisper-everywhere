package com.whispereverywhere.transcription.live

import com.whispereverywhere.transcription.cloud.FatalKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The audit target. Soniox is the one provider whose key rides a MESSAGE (the first config frame)
 * rather than an upgrade header, so the no-log discipline is load-bearing and attacked directly here:
 * the key must be constructible and sendable while NEVER surviving as a field, a `toString`, an
 * exception, or any inbound-callback argument. The only object that may ever hold it is the config
 * frame handed to the transport.
 */
class SonioxNoLogDisciplineTest {

    private companion object {
        // A distinctive secret with a recognizable middle fragment, so a partial leak is caught too.
        const val KEY = "sx-live-PRIVATE-c0ffee-D34DB33F-tail"
        const val FRAGMENT = "c0ffee-D34DB33F"
    }

    private class RecordingControl : SessionControl {
        val frames = mutableListOf<Frame>()
        override fun send(frame: Frame): Boolean { frames += frame; return true }
        override fun rotate() {}
    }

    /** Records every STRING that crosses the inbound seam, so the key can be hunted across all of them. */
    private class RecordingListener : RealtimeTransport.Listener {
        val strings = mutableListOf<String>()
        override fun onConnected() {}
        override fun onDelta(itemId: String, text: String) { strings += itemId; strings += text }
        override fun onCompleted(itemId: String, transcript: String) { strings += itemId; strings += transcript }
        override fun onCommitted(itemId: String) { strings += itemId }
        override fun onTranscriptionFailed(itemId: String) { strings += itemId }
        override fun onErrorEvent(code: String?, messageLength: Int) { strings += code.orEmpty(); strings += messageLength.toString() }
        override fun onDisconnected() {}
        override fun onFatal(kind: FatalKind, code: Int) { strings += kind.name; strings += code.toString() }
    }

    private val noopScheduler = ReconnectScheduler { _, _ -> /* never fire */ }

    private fun leaks(s: String?): Boolean = s.orEmpty().let { it.contains(KEY) || it.contains(FRAGMENT) }

    // ---- the config frame is the SOLE carrier ---------------------------------------------------

    @Test fun config_string_carries_the_key_because_it_must_to_send_it() {
        // The one object that legitimately holds the key: the bytes on the wire, over TLS.
        assertTrue(SonioxEvents.config(KEY, null).contains(KEY))
    }

    @Test fun bootstrap_returns_the_key_bearing_config_and_nothing_else_holds_it() {
        val p = SonioxRealtimeProtocol(noopScheduler).apply { bind(RecordingControl(), RecordingListener()) }
        val frame = p.bootstrap(KEY, "en").single() as Frame.Text
        assertTrue("the config frame must carry the key to send it", frame.json.contains(KEY))
    }

    // ---- the key is never a field, and toString leaks nothing -----------------------------------

    @Test fun toString_of_the_protocol_never_contains_the_key() {
        val p = SonioxRealtimeProtocol(noopScheduler).apply { bind(RecordingControl(), RecordingListener()) }
        p.bootstrap(KEY, "en")
        assertFalse(leaks(p.toString()))
    }

    @Test fun no_declared_field_holds_the_key_after_bootstrap_and_reset() {
        val p = SonioxRealtimeProtocol(noopScheduler).apply { bind(RecordingControl(), RecordingListener()) }
        // Drive the key through the protocol the way a live session does…
        p.bootstrap(KEY, "en")
        p.onAppend(byteArrayOf(1, 2, 3, 4))
        p.onText("""{"tokens":[{"text":"hi","is_final":true}]}""")
        p.reset()
        // …then prove the class holds NO field that captured it (by construction it has no apiKey field).
        assertNoFieldLeaks(p)
        // And a second time before reset would have been the same — reflect a fresh instance mid-session.
        val live = SonioxRealtimeProtocol(noopScheduler).apply { bind(RecordingControl(), RecordingListener()) }
        live.bootstrap(KEY, null)
        assertNoFieldLeaks(live)
    }

    private fun assertNoFieldLeaks(p: SonioxRealtimeProtocol) {
        for (f in p.javaClass.declaredFields) {
            f.isAccessible = true
            val value = f.get(p)
            assertFalse("field '${f.name}' leaked the key", leaks(value?.toString()))
        }
    }

    // ---- a full session proves the key crosses NO inbound callback and NO other frame -----------

    @Test fun a_full_session_leaks_the_key_only_in_the_config_frame() {
        val control = RecordingControl()
        val sink = RecordingListener()
        val p = SonioxRealtimeProtocol(noopScheduler).apply { bind(control, sink) }

        // The transport would send the bootstrap frames; do it here so the config frame is recorded.
        val boot = p.bootstrap(KEY, "en")
        boot.forEach { control.send(it) }

        // A representative session: audio, a preview, a completion trigger, and an error — none of
        // which carry the key. (The error message deliberately does NOT contain the key.)
        p.onAppend(byteArrayOf(9, 9, 9, 9))
        p.onText("""{"tokens":[{"text":"hello ","is_final":true},{"text":"world","is_final":false}]}""")
        p.onText("""{"error_code":401,"error_message":"realtime scope missing"}""")

        val configJson = (boot.single() as Frame.Text).json
        // 1. The config frame is the sole carrier.
        assertTrue(configJson.contains(KEY))
        // 2. No inbound-callback argument carries any part of the key.
        assertTrue("a callback argument leaked the key", sink.strings.none { leaks(it) })
        // 3. No sent frame OTHER than the config carries the key (audio is binary; any text is clean).
        val nonConfigTexts = control.frames.filterIsInstance<Frame.Text>().map { it.json }.filter { it != configJson }
        for (text in nonConfigTexts) assertFalse("a non-config frame leaked the key", leaks(text))
    }
}
