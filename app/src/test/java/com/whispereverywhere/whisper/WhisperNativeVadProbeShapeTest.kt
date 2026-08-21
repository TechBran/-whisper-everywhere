package com.whispereverywhere.whisper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.nio.ByteBuffer

/**
 * Pins the SHAPE of the four 3.7 VAD-probe externs.
 *
 * WhisperNative's init block calls System.loadLibrary("whisper_jni"), which throws
 * UnsatisfiedLinkError on a plain JVM — so this loads the class WITHOUT running its static
 * initializer (Class.forName(..., initialize = false)) and inspects declared methods only. That is
 * enough to catch every failure this seam can have on the Kotlin side: JNI resolves BY NAME, so
 * neither a rename nor a changed parameter type is a compile error anywhere. A rename becomes an
 * UnsatisfiedLinkError on the audio capture thread at the first frame of the next dictation; a
 * parameter-type change does not even get that far — the short name still resolves and the same
 * native function is called with arguments it was not written for. See [method] for the full shape
 * of both failures.
 *
 * This is one half of a pair. NativeVadSourceContractTest anchors the C++ side on
 * "Java_com_whispereverywhere_whisper_WhisperNative_<name>(" for the same four names, so between
 * the two classes both ends of every JNI name are pinned and a one-sided rename fails here.
 */
class WhisperNativeVadProbeShapeTest {

    private val clazz: Class<*> =
        Class.forName("com.whispereverywhere.whisper.WhisperNative", false, javaClass.classLoader)

    /**
     * getDeclaredMethod pins name AND signature in one call — but it throws a bare
     * NoSuchMethodException, which is the failure this class produces for its two most likely
     * mutations and says nothing about why either matters. Translated here into the two on-device
     * outcomes instead, with the declared surface printed so the diff is visible in the failure.
     */
    private fun method(name: String, vararg params: Class<*>): Method =
        try {
            clazz.getDeclaredMethod(name, *params)
        } catch (e: NoSuchMethodException) {
            val declared = clazz.declaredMethods
                .filter { it.name.startsWith("vadProbe") }
                .map { m -> "${m.name}(${m.parameterTypes.joinToString { it.simpleName }})" }
                .sorted()
            throw AssertionError(
                "WhisperNative declares no ${name}(${params.joinToString { it.simpleName }}), and " +
                    "nothing on the Kotlin side makes that a compile error. The two ways to get " +
                    "here fail DIFFERENTLY on-device. A RENAME leaves the native symbol " +
                    "Java_com_whispereverywhere_whisper_WhisperNative_$name matching no declared " +
                    "method, and the first frame of the next dictation throws UnsatisfiedLinkError " +
                    "on the audio capture thread. A SIGNATURE change is quieter and worse: JNI " +
                    "looks the implementation up by the SHORT name first and only falls back to " +
                    "the signature-mangled long name for overloaded natives, of which this class " +
                    "has none — so the same native function is bound and called with arguments it " +
                    "was not written for. Hand vadProbeFrame a FloatArray and " +
                    "GetDirectBufferAddress is specified to return NULL for an object that is not " +
                    "a direct Buffer, which whisper_jni's null guard turns into -1.0f on every " +
                    "frame of every session: no exception, no crash, and no fallback either, " +
                    "because vadProbeInit still returned true. " +
                    "Declared instead: $declared",
                e
            )
        }

    @Test
    fun vadProbeInit_takesAModelPath_andReportsReadinessAsBoolean() {
        val m = method("vadProbeInit", String::class.java)
        assertTrue("vadProbeInit must be declared `external`", Modifier.isNative(m.modifiers))
        assertEquals(
            "false is a normal outcome (model missing / unloadable), which is why this is a " +
                "Boolean and not an exception — the caller falls back to AmplitudeEndpointer",
            java.lang.Boolean.TYPE, m.returnType
        )
    }

    @Test
    fun vadProbeFrame_takesADirectBufferAndAByteCount_andReturnsARawProbability() {
        val m = method("vadProbeFrame", ByteBuffer::class.java, Integer.TYPE)
        assertTrue("vadProbeFrame must be declared `external`", Modifier.isNative(m.modifiers))
        // Inert by construction: method() above matched this exact parameter list, so this can
        // never be the assertion that fails — the guard is the lookup. It stays as the written
        // record of WHY the parameter is a ByteBuffer, which the lookup cannot say.
        assertEquals(
            "a ByteBuffer, not a ByteArray: the buffer is direct and reused for the whole " +
                "session, so no per-frame allocation or JNI array copy happens at 31.25 Hz",
            ByteBuffer::class.java, m.parameterTypes[0]
        )
        assertEquals(
            "returns a RAW probability — threshold/hysteresis/hangover policy stays in Kotlin " +
                "where it is JVM-pinnable (SileroEndpointer), not behind the JNI boundary. Float " +
                "also carries the -1.0f no-verdict sentinel, which a Boolean could not.",
            java.lang.Float.TYPE, m.returnType
        )
    }

    @Test
    fun vadProbeResetAndFree_takeNoArguments_andReturnNothing() {
        listOf("vadProbeReset", "vadProbeFree").forEach { name ->
            val m = method(name)
            assertTrue("$name must be declared `external`", Modifier.isNative(m.modifiers))
            assertEquals("$name returns nothing", java.lang.Void.TYPE, m.returnType)
            // Inert by construction: method() above matched this exact parameter list (empty), so
            // this can never be the assertion that fails — the guard is the lookup.
            assertEquals("$name takes no arguments", 0, m.parameterTypes.size)
        }
    }

    @Test
    fun theProbeSurfaceIsExactlyFourMethods_soNoPolicyLeaksAcrossTheJniBoundary() {
        val probe = clazz.declaredMethods.map { it.name }.filter { it.startsWith("vadProbe") }.sorted()
        assertEquals(
            "four and only four: init, frame, reset, free. Anything else here would be endpointing " +
                "policy that belongs in Kotlin.",
            listOf("vadProbeFrame", "vadProbeFree", "vadProbeInit", "vadProbeReset"), probe
        )
    }
}
