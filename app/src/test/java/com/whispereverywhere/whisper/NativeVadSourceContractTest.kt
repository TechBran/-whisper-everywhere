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
            "anchor \"$anchor\" is missing from whisper_jni.cpp. indexOf() returns -1 when the " +
                "anchor is absent, so substring(start + anchor.length) silently rebases the scope " +
                "to the top of the file — ~130 unrelated lines ahead of we_vad_filter — and every " +
                "assertion below then passes on text borrowed from unrelated functions instead of " +
                "failing.",
            start >= 0
        )
        val body = jni.substring(start + anchor.length)
        assertTrue(
            "no column-0 \"\\n}\\n\" follows \"$anchor\". substringBefore() returns its RECEIVER " +
                "when the delimiter is absent, so the scope would silently widen to everything " +
                "from we_vad_filter to end-of-file.",
            body.contains("\n}\n")
        )
        return body.substringBefore("\n}\n")
    }

    /** One JNI export's body: every JNI function in whisper_jni.cpp closes at column 0. */
    private fun jniFunctionBody(name: String): String {
        val marker = "Java_com_whispereverywhere_whisper_WhisperNative_$name("
        val start = jni.indexOf(marker)
        assertTrue(
            "JNI export $name is not declared in whisper_jni.cpp. indexOf() returns -1 when the " +
                "marker is absent, so substring(start) would silently rebase the scope to the top " +
                "of the file, and every claim about $name's body would then be answered by " +
                "unrelated code hundreds of lines away instead of failing here.",
            start >= 0
        )
        val body = jni.substring(start)
        assertTrue(
            "no column-0 \"\\n}\\n\" follows \"$marker\". substringBefore() returns its RECEIVER " +
                "when the delimiter is absent, so a mangled or re-indented closing brace silently " +
                "widens the scope past $name into the FOLLOWING function (and onward until some " +
                "later brace does sit at column 0). Presence checks then pass on a neighbour's " +
                "code, and \"must not touch g_vad_ctx\" fails for a reason unrelated to $name.",
            body.contains("\n}\n")
        )
        return body.substringBefore("\n}\n")
    }

    /**
     * True when [needle] appears on a line of LIVE code inside [scope]; a commented-out line does
     * not count. Same lesson as the log-demotion guard below: `// g_probe_ctx = nullptr;` left
     * behind by a refactor would keep a plain contains() green while the dangling pointer it
     * describes is real.
     */
    private fun containsLiveLine(scope: String, needle: String): Boolean =
        scope.lineSequence().any { line ->
            val trimmed = line.trimStart()
            line.contains(needle) &&
                !trimmed.startsWith("//") &&
                !trimmed.startsWith("/*") &&
                !trimmed.startsWith("*")
        }

    /** The streaming VAD entry point's body: bounded by the resetting wrapper that follows it. */
    private fun streamingVadEntryPoint(): String {
        val anchor = "bool whisper_vad_detect_speech_no_reset("
        val terminator = "bool whisper_vad_detect_speech("
        val start = fork.indexOf(anchor)
        assertTrue(
            "anchor \"$anchor\" is missing from the vendored fork's whisper.cpp. indexOf() returns " +
                "-1 when the anchor is absent, so substring(start + anchor.length) silently " +
                "rebases the scope to the top of the file — 5,100+ unrelated lines ahead of the " +
                "streaming function. That turns the \"no WHISPER_LOG_INFO survives\" claim from a " +
                "78-line claim into a near-whole-file one: it would fail loudly against the 87 " +
                "unrelated INFO call sites elsewhere in this 9,044-line translation unit, so a " +
                "real streaming regression would be buried behind a failure that has nothing to " +
                "do with it.",
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
        val pin = Regex("""(?m)^[ \t]*vcp\.n_threads = 1;""").find(body)
        assertTrue(
            "we_vad_filter must set vcp.n_threads = 1 before creating the batch VAD context. " +
                "ggml_backend_cpu_set_threadpool is never called for a VAD context, so " +
                "ggml_graph_compute takes the disposable-threadpool path and spawns + joins " +
                "n_threads-1 real pthreads PER GRAPH COMPUTE (ggml-cpu.c:3319-3324) — and that " +
                "compute sits inside the per-window frame loop (whisper.cpp:5170). At the default " +
                "4 that is 375 create/join cycles per 4 s chunk and 1,407 per 15 s chunk, today, " +
                "on shipped behavior, for a ~74-node graph with a barrier between every node.",
            pin != null
        )
        assertTrue(
            "vcp.n_threads = 1 must be set BEFORE the init call it parameterises. The index comes " +
                "from the line-anchored regex match, not indexOf(\"vcp.n_threads = 1;\"): a literal " +
                "search would happily measure the position of a COMMENTED-OUT pin and report the " +
                "ordering of a line that never executes.",
            pin!!.range.first < body.indexOf("whisper_vad_init_from_file_with_params")
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
            // A commented-out line does not count as a surviving log: `//WHISPER_LOG_DEBUG(...)`
            // already exists in this function (the upstream per-chunk probability trace), and
            // without this filter someone could satisfy "demote, don't delete" by commenting the
            // line out — which deletes it, losing -DWHISPER_DEBUG narration the rule exists to keep.
            // Both comment forms are excluded: `/*` because commenting a block out is the more
            // natural way to silence five adjacent lines at once.
            val line = fn.lineSequence()
                .firstOrNull {
                    it.contains(message) &&
                        !it.trimStart().startsWith("//") &&
                        !it.trimStart().startsWith("/*")
                }
                ?: throw AssertionError(
                    "\"$message\" vanished from whisper_vad_detect_speech_no_reset (deleted, or " +
                        "commented out — both break the demote-don't-delete rule)"
                )
            assertTrue(
                "\"$message\" must log at WHISPER_LOG_DEBUG, which is compiled out entirely " +
                    "(WHISPER_DEBUG is undefined at whisper.cpp:126). It fires per VAD call, and " +
                    "3.7 drives that call 31.25x/second from the capture thread — every INFO here " +
                    "is ~31 __android_log_write/second to logd, plausibly costing more than the " +
                    "1.36 MFLOP inference itself, and it evicts the WE-DIAG lines the owner's " +
                    "acceptance greps depend on. Do not replace the compile-out with a runtime " +
                    "log-level filter: we_native_log (whisper_jni.cpp) maps every non-WARN/ERROR " +
                    "level onto ANDROID_LOG_INFO, so filtering by level moves zero " +
                    "bytes. Demoting (not deleting) keeps the fork upstream-mergeable and keeps " +
                    "-DWHISPER_DEBUG useful. Found: $line",
                line.contains("WHISPER_LOG_DEBUG(")
            )
        }
        assertTrue(
            "no WHISPER_LOG_INFO may survive anywhere in whisper_vad_detect_speech_no_reset — " +
                "it is a per-call function and 3.7 makes it a per-frame one",
            !fn.contains("WHISPER_LOG_INFO")
        )
        assertTrue(
            "both WHISPER_LOG_ERROR lines (sched-alloc, graph-compute) must stay loud — they are " +
                "once-per-failure, not per-call — and there must be EXACTLY two. A third ERROR " +
                "here fails this test on purpose: a new failure path in a function 3.7 calls " +
                "31.25x/second needs a rate-limiting decision. If you added one deliberately, " +
                "bump this count.",
            Regex("""WHISPER_LOG_ERROR\(""").findAll(fn).count() == 2
        )
    }

    @Test
    fun probeSurface_recordsTheComputeGateBypassArgument_whereTheBypassActuallyLives() {
        val banner = "3.7 Workstream A"
        val terminator = "g_probe_mutex"
        assertTrue(
            "the section banner \"$banner\" is missing from whisper_jni.cpp. substringAfter() " +
                "returns its RECEIVER when the delimiter is absent, so the scope below would " +
                "silently become everything from the top of the file up to the first " +
                "\"$terminator\" — and the whole point of this test is that the argument lives AT " +
                "the probe surface, not merely somewhere in the translation unit.",
            jni.contains(banner)
        )
        val afterBanner = jni.substringAfter(banner)
        assertTrue(
            "\"$terminator\" does not follow \"$banner\". substringBefore() returns its receiver " +
                "when the delimiter is absent, so the scope would silently widen to end-of-file " +
                "and the claims below could be satisfied by prose written anywhere at all.",
            afterBanner.contains(terminator)
        )
        val header = afterBanner.substringBefore(terminator)
        listOf(
            "OUTSIDE NativeComputeGate",
            "whisper.cpp:4671-4674",
            "own CPU backend",
            "FAIR ReentrantLock",
            "2.6 MB",
            // "2.6 MB" alone is satisfied by the RSS sentence higher up in the banner, so deleting
            // the memory REASON from the bypass argument leaves the figure — and the test — intact.
            // This pins the argument the figure is doing work in, not just the figure.
            "is not an OOM risk",
        ).forEach { claim ->
            assertTrue(
                "NativeComputeGate wraps EVERY whisper call in this process; the probe alone " +
                    "bypasses it, so the argument for why that is safe must live at the surface " +
                    "itself, where anyone moving this code will read it. It must state \"$claim\". " +
                    "(If the whisper.cpp citation has drifted, re-verify the forced-CPU line " +
                    "rather than deleting the claim.)",
                header.contains(claim)
            )
        }
    }

    @Test
    fun probeContext_isDedicated_becauseSharingTheBatchContextCorruptsItThreeWays() {
        assertTrue(
            "the probe needs its OWN whisper_vad_context. we_vad_filter's path reaches " +
                "whisper_vad_detect_speech, which unconditionally resets the LSTM on entry " +
                "(whisper.cpp:5193) — wiping the recurrence the probe is riding — and resizes " +
                "probs to hundreds of entries from index 0, which is the slot the probe reads.",
            Regex("""static\s+whisper_vad_context\s*\*\s*g_probe_ctx\s*=\s*nullptr;""")
                .containsMatchIn(jni)
        )
        assertTrue(
            "the probe needs its OWN mutex. ggml_backend_sched is not thread-safe and both " +
                "callers would write the same \"frame\" input tensor; g_vad_mutex cannot fix the " +
                "state wipe or the probs clobber anyway.",
            Regex("""static\s+std::mutex\s+g_probe_mutex;""").containsMatchIn(jni)
        )
        val free = jniFunctionBody("vadProbeFree")
        assertTrue("vadProbeFree must take g_probe_mutex", containsLiveLine(free, "g_probe_mutex"))
        assertTrue("vadProbeFree must not touch g_vad_ctx", !free.contains("g_vad_ctx"))
        assertTrue(
            "vadProbeFree must be null-safe and idempotent",
            containsLiveLine(free, "g_probe_ctx = nullptr;")
        )
    }
}
