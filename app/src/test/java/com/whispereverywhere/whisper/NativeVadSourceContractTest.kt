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

    private val jni: String by lazy { repoFile("src/main/cpp/whisper_jni.cpp").readText() }

    /** we_vad_filter's body: the only column-0 `}` in that function is its closing brace. */
    private fun weVadFilterBody(): String =
        jni.substringAfter("static bool we_vad_filter(").substringBefore("\n}\n")

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
            body.contains("vcp.n_threads = 1;")
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
}
