package com.whispereverywhere.whisper

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-level contract guards for the native VAD surface (whisper_jni.cpp and the vendored
 * whisper.cpp fork).
 *
 * WHY A TEST THAT READS C++: neither file has any JVM-side behavior — there is no
 * libwhisper_jni.so on the unit-test classpath, and `:app:assembleDebug` is the only gate that
 * touches them. That leaves several binding 3.7 constraints (n_threads = 1 on every VAD context,
 * the -1.0f short-frame sentinel, the probe's dedicated context, the recorded
 * NativeComputeGate-bypass argument) invisible to the test suite and free to regress silently.
 * These assertions pin the constructs themselves, so a refactor that drops one fails here in
 * seconds instead of on-device in a month.
 *
 * Every assertion is anchored to CONTENT, never to a line number: the 3.7 spec's own line anchors
 * had already drifted by the time it was written.
 */
class NativeVadSourceContractTest {

    private fun repoFile(relative: String): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            File(dir, relative).let { if (it.isFile) return it }
            File(dir, "app/$relative").let { if (it.isFile) return it }
            dir = dir.parentFile
        }
        throw AssertionError(
            "could not locate $relative from ${System.getProperty("user.dir")} " +
                "(if this is the vendored fork, run: git submodule update --init --recursive)"
        )
    }

    /**
     * Line endings are normalized to LF at the single read site so every helper below can anchor on
     * "\n" regardless of how the file is checked out. whisper_jni.cpp is CRLF in this repo, and
     * Kotlin's readText() does not normalize: before this, substringBefore("\n}\n") never matched
     * (the real byte sequence at a column-0 brace is "\r\n}\r\n") and silently fell through to
     * returning the WHOLE FILE, so every "body" assertion was really a whole-file assertion.
     */
    private fun readNormalized(relative: String): String =
        repoFile(relative).readText().replace("\r\n", "\n")

    private val jni: String by lazy { readNormalized("src/main/cpp/whisper_jni.cpp") }

    private val fork: String by lazy { readNormalized("src/main/cpp/whisper.cpp/src/whisper.cpp") }

    /** we_vad_filter's body: the only column-0 `}` in that function is its closing brace. */
    private fun weVadFilterBody(): String {
        val anchor = "static bool we_vad_filter("
        val start = jni.indexOf(anchor)
        assertTrue(
            "anchor \"$anchor\" is missing from whisper_jni.cpp. substringAfter() returns its " +
                "RECEIVER when the delimiter is absent, so without this check a renamed function " +
                "would silently turn every assertion below into a whole-file assertion — passing " +
                "on text borrowed from unrelated functions instead of failing.",
            start >= 0
        )
        val body = jni.substring(start + anchor.length)
        assertTrue(
            "no column-0 \"\\n}\\n\" follows \"$anchor\". substringBefore() also returns its " +
                "receiver when the delimiter is absent, so the scope would silently widen to " +
                "everything from we_vad_filter to end-of-file.",
            body.contains("\n}\n")
        )
        return body.substringBefore("\n}\n")
    }

    /** The streaming VAD entry point's body: bounded by the resetting wrapper that follows it. */
    private fun streamingVadEntryPoint(): String {
        val anchor = "bool whisper_vad_detect_speech_no_reset("
        val terminator = "bool whisper_vad_detect_speech("
        val start = fork.indexOf(anchor)
        assertTrue(
            "anchor \"$anchor\" is missing from the vendored fork's whisper.cpp. substringAfter() " +
                "returns its RECEIVER when the delimiter is absent, so without this check an " +
                "upstream rename would silently scope the assertions below to the ENTIRE 8k-line " +
                "translation unit — where the demoted strings still appear in the batch paths, so " +
                "the guard would keep passing while the streaming path regressed.",
            start >= 0
        )
        val body = fork.substring(start + anchor.length)
        assertTrue(
            "terminator \"$terminator\" (the resetting wrapper) does not follow \"$anchor\". " +
                "substringBefore() returns its receiver when the delimiter is absent, so the " +
                "scope would silently widen to end-of-file.",
            body.contains(terminator)
        )
        return body.substringBefore(terminator)
    }

    @Test
    fun batchVadContext_pinsOneThread_soTheShippedFilterStopsForkingPthreadsPerChunk() {
        val body = weVadFilterBody()
        assertTrue(
            "we_vad_filter must set vcp.n_threads = 1 before creating the batch VAD context. " +
                "ggml_backend_cpu_set_threadpool is never called for a VAD context, so " +
                "ggml_graph_compute takes the disposable-threadpool path and spawns + joins " +
                "n_threads-1 real pthreads PER GRAPH COMPUTE (ggml-cpu.c:3319-3324) — and that " +
                "compute sits inside the per-window frame loop (whisper.cpp:5164). At the default " +
                "4 that is 375 create/join cycles per 4 s chunk and 1,407 per 15 s chunk, today, " +
                "on shipped behavior, for a ~74-node graph with a barrier between every node.",
            Regex("""(?m)^[ \t]*vcp\.n_threads = 1;""").containsMatchIn(body)
        )
        assertTrue(
            "vcp.n_threads = 1 must be set BEFORE the init call it parameterises",
            body.indexOf("vcp.n_threads = 1;") <
                body.indexOf("whisper_vad_init_from_file_with_params")
        )
        assertTrue(
            "the field is n_threads (whisper.h:683). `.n_thread` is the initializer COMMENT at " +
                "whisper.cpp:4445 and will not compile — this guard exists because that comment " +
                "misled an earlier investigation layer.",
            !Regex("""vcp\.n_thread\b""").containsMatchIn(body)
        )
    }

    @Test
    fun streamingVadEntryPoint_logsNothingAtInfo_soFrameRateProbingCannotFloodLogd() {
        val fn = streamingVadEntryPoint()
        listOf(
            "detecting speech in %d samples",
            "n_chunks: %d",
            "props size: %u",
            "chunk_len: %d < n_window: %d",
            "vad time = %.2f ms processing %d samples",
        ).forEach { message ->
            val line = fn.lineSequence().firstOrNull { it.contains(message) }
                ?: throw AssertionError("\"$message\" vanished from whisper_vad_detect_speech_no_reset")
            assertTrue(
                "\"$message\" must log at WHISPER_LOG_DEBUG, which is compiled out entirely " +
                    "(WHISPER_DEBUG is undefined at whisper.cpp:126). It fires per VAD call, and " +
                    "3.7 drives that call 31.25x/second from the capture thread — every INFO here " +
                    "is ~31 __android_log_write/second to logd, plausibly costing more than the " +
                    "1.36 MFLOP inference itself, and it evicts the WE-DIAG lines the owner's " +
                    "acceptance greps depend on. Demoting (not deleting) keeps the fork " +
                    "upstream-mergeable and keeps -DWHISPER_DEBUG useful. Found: $line",
                line.contains("WHISPER_LOG_DEBUG(")
            )
        }
        assertTrue(
            "no WHISPER_LOG_INFO may survive anywhere in whisper_vad_detect_speech_no_reset — " +
                "it is a per-call function and 3.7 makes it a per-frame one",
            !fn.contains("WHISPER_LOG_INFO")
        )
    }
}
