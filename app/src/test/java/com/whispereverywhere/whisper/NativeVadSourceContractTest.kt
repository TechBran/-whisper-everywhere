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

    /**
     * The Kotlin side of the same surface. Four items of the probe contract can be stated ONLY in
     * prose — no signature carries them — and they have two separate audiences that read two
     * separate files: whoever moves the native code reads whisper_jni.cpp, and Workstreams C/D,
     * who write every caller, read the KDoc in WhisperNative.kt and never open the .cpp at all.
     * An item present on one side only is invisible to half the people who must obey it, so the
     * assertions below pin each one on BOTH.
     */
    private val kt: String by lazy {
        readNormalized("src/main/java/com/whispereverywhere/whisper/WhisperNative.kt")
    }

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
     * The COMMENT block immediately above one JNI export — a different scope from
     * [jniFunctionBody], which starts AT the export and therefore contains none of the prose.
     */
    private fun jniCommentFor(name: String): String {
        val marker = "Java_com_whispereverywhere_whisper_WhisperNative_$name("
        val at = jni.indexOf(marker)
        assertTrue(
            "JNI export $name is not declared in whisper_jni.cpp. indexOf() returns -1 when the " +
                "marker is absent, so substring(0, at) would be substring(0, -1) — this assert " +
                "turns that into a sentence instead of an IndexOutOfBounds with no explanation.",
            at >= 0
        )
        val head = jni.substring(0, at)
        val blank = head.lastIndexOf("\n\n")
        assertTrue("no blank line precedes $name's declaration in whisper_jni.cpp", blank >= 0)
        val block = head.substring(blank)
        assertTrue(
            "no comment block sits immediately above $name. The scope here is \"back up to the " +
                "previous blank line\", so DELETING the prose does not fail on the phrase " +
                "assertions — it silently rebases the scope onto whatever declaration or function " +
                "body precedes it, and the phrases then fail for a reason that reads like drift " +
                "rather than deletion. This assert names the real cause: the contract prose that " +
                "must live at this surface is gone.",
            block.trimStart('\n').lineSequence().first().trimStart().startsWith("//")
        )
        assertTrue(
            "the comment scope for $name widened past a previous function body (it contains a " +
                "column-0 closing brace), so the phrase assertions below would be answered by " +
                "prose belonging to a different function",
            !block.contains("\n}")
        )
        return block
    }

    /**
     * The KDoc block immediately above [declaration] in WhisperNative.kt — from its opening marker
     * down to the declaration itself. (Spelling that marker out in this comment is not possible:
     * Kotlin NESTS block comments, so it would open one that never closes.)
     */
    private fun ktDocFor(declaration: String): String {
        val at = kt.indexOf(declaration)
        assertTrue(
            "WhisperNative.kt does not declare \"$declaration\". If the signature was changed " +
                "deliberately, WhisperNativeVadProbeShapeTest fails first and says far more about " +
                "what that costs on-device — fix that one first and this follows.",
            at >= 0
        )
        val head = kt.substring(0, at)
        val open = head.lastIndexOf("/**")
        assertTrue("no KDoc block opens above \"$declaration\"", open >= 0)
        val block = head.substring(open)
        assertTrue(
            "the KDoc scope for \"$declaration\" widened past a previous member: lastIndexOf " +
                "(\"/**\") finds the NEAREST KDoc above the declaration, so deleting this " +
                "member's KDoc outright silently borrows the previous member's — and a phrase " +
                "assertion could then be satisfied by documentation written about a different " +
                "function.",
            !block.contains("external fun")
        )
        return block
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

    /**
     * The LIVE line in [init] that assigns the probe context, as a match (so callers get its
     * index, not a literal's). Both ordering assertions over `vadProbeInit` compare against this:
     * an `indexOf("whisper_vad_init_from_file_with_params")` would measure a COMMENTED-OUT mention
     * just as happily as the real call, and a comment that drifts above the code it describes
     * would then silently satisfy "the pin/free comes first".
     */
    private fun probeContextCreation(init: String): MatchResult {
        val create = Regex("""(?m)^[ \t]*g_probe_ctx = whisper_vad_init_from_file_with_params""")
            .find(init)
        assertTrue(
            "vadProbeInit must assign g_probe_ctx from whisper_vad_init_from_file_with_params on " +
                "a LIVE line. Without it there is no create index, so every ordering claim below " +
                "would be comparing against a position that does not exist.",
            create != null
        )
        return create!!
    }

    /**
     * The `g_vad_ctx` twin of [probeContextCreation], for the batch filter's ordering assertion.
     * Same hazard, same fix: an `indexOf("whisper_vad_init_from_file_with_params")` measures a
     * COMMENTED-OUT mention exactly as happily as the live call, so a comment that drifts above the
     * code it describes silently satisfies "the pin comes first". This was proved empirically, not
     * assumed: a mutation aimed at `vadProbeInit` (commented-out mention above the create, pin
     * moved down) also landed in `we_vad_filter` and false-greened there under the raw `indexOf`.
     */
    private fun batchVadContextCreation(filter: String): MatchResult {
        val create = Regex("""(?m)^[ \t]*g_vad_ctx = whisper_vad_init_from_file_with_params""")
            .find(filter)
        assertTrue(
            "we_vad_filter must assign g_vad_ctx from whisper_vad_init_from_file_with_params on a " +
                "LIVE line. Without it there is no create index, so the ordering claim below would " +
                "be comparing against a position that does not exist.",
            create != null
        )
        return create!!
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
                "n_threads-1 real pthreads PER GRAPH COMPUTE (ggml-cpu.c:3320-3325, joined at " +
                ":3379) — and that " +
                "compute sits inside the per-window frame loop (whisper.cpp:5170). At the default " +
                "4 that is 375 create/join cycles per 4 s chunk and 1,407 per 15 s chunk, today, " +
                "on shipped behavior, for a ~74-node graph with a barrier between every node.",
            pin != null
        )
        assertTrue(
            "vcp.n_threads = 1 must be set BEFORE the init call it parameterises. BOTH indices " +
                "come from line-anchored regex matches, never indexOf(): a literal search on " +
                "EITHER side would happily measure the position of a COMMENTED-OUT line and " +
                "report the ordering of code that never executes.",
            pin!!.range.first < batchVadContextCreation(body).range.first
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

    @Test
    fun probeContext_pinsOneThread_soFrameRateProbingDoesNotForkPthreadsThirtyTimesASecond() {
        val init = jniFunctionBody("vadProbeInit")
        val pin = Regex("""(?m)^[ \t]*vcp\.n_threads = 1;""").find(init)
        assertTrue(
            "vadProbeInit must set vcp.n_threads = 1. This is the highest-leverage single line " +
                "in Workstream A: at 31.25 frames/second the default 4 means 93.75 pthread " +
                "create/join cycles per second, continuously, for a ~74-node / ~1.36 MFLOP graph " +
                "with a ggml_barrier between every node that cannot be split 4 ways at all.",
            pin != null
        )
        val create = probeContextCreation(init)
        assertTrue(
            "vcp.n_threads = 1 must be set BEFORE the init call it parameterises. BOTH indices " +
                "come from line-anchored regex matches, never indexOf(): a literal search on " +
                "EITHER side would happily measure the position of a COMMENTED-OUT line and " +
                "report the ordering of code that never executes.",
            pin!!.range.first < create.range.first
        )
        assertTrue(
            "the field is n_threads (whisper.h:683), not the .n_thread of the initializer comment",
            !Regex("""vcp\.n_thread\b""").containsMatchIn(init)
        )
    }

    @Test
    fun probeInit_isIdempotent_soARestartedSessionCannotLeakTheEarlierContext() {
        val init = jniFunctionBody("vadProbeInit")
        val free = Regex("""(?m)^[ \t]*whisper_vad_free\(g_probe_ctx\);""").find(init)
        assertTrue(
            "vadProbeInit must call whisper_vad_free(g_probe_ctx) on a LIVE line: a commented-out " +
                "free satisfies indexOf() while leaking the context it claims to release.",
            free != null
        )
        val freeAt = free!!.range.first
        val createAt = probeContextCreation(init).range.first
        assertTrue(
            "vadProbeInit must free any existing probe context BEFORE creating a new one — it is " +
                "called once per recording session and a session restart or model swap would " +
                "otherwise leak ~2.6 MB each time",
            freeAt in 0 until createAt
        )
        assertTrue(
            "vadProbeInit must take g_probe_mutex",
            containsLiveLine(init, "g_probe_mutex")
        )
        val postLoadNullCheck = Regex("""(?m)^[ \t]*if \(g_probe_ctx == nullptr\)""").find(init)
        assertTrue(
            "vadProbeInit must test g_probe_ctx == nullptr on a LIVE line. This index must come " +
                "from a line-anchored regex match and never from " +
                "indexOf(\"g_probe_ctx == nullptr\"), and unlike the pin/create pair the danger " +
                "here runs the other way: for a \"> createAt\" comparison a raw indexOf finds a " +
                "COMMENT just as happily as code, so deleting the live guard while leaving any " +
                "comment that mentions it below the create satisfies the ordering claim outright. " +
                "The paired \"return JNI_FALSE\" conjunct does not save it either — the earlier " +
                "modelPath == nullptr guard satisfies that one independently. Demonstrated, not " +
                "assumed: replacing the guard with `if (false)` plus a FIXME comment naming it " +
                "kept this entire class green.",
            postLoadNullCheck != null
        )
        assertTrue(
            "vadProbeInit must return JNI_FALSE on a live line, and must decide that by testing " +
                "g_probe_ctx AFTER the load. A probe that reports success with a null context is " +
                "worse than one that reports failure: the caller skips the amplitude fallback and " +
                "N5's vadProbeFrame inherits the null.",
            containsLiveLine(init, "return JNI_FALSE;") &&
                postLoadNullCheck!!.range.first > createAt
        )
        assertTrue(
            "vadProbeInit must not touch the batch filter's context",
            !init.contains("g_vad_ctx")
        )
    }

    @Test
    fun probeFrame_refusesAnythingButOneExactSileroWindow_withTheNoVerdictSentinel() {
        assertTrue(
            "the window is 512 samples (model header n_window=512) = 32 ms at 16 kHz",
            Regex("""kProbeFrameSamples\s*=\s*512""").containsMatchIn(jni)
        )
        assertTrue(
            "1024 bytes = 512 samples of 16-bit PCM = exactly one mic callback",
            Regex("""kProbeFrameBytes\s*=\s*kProbeFrameSamples\s*\*\s*2""").containsMatchIn(jni)
        )
        val frame = jniFunctionBody("vadProbeFrame")
        assertTrue(
            "a misaligned frame must be REFUSED with -1.0f, never zero-padded and never reported " +
                "as silence: whisper_vad_detect_speech_no_reset zero-pads a short frame " +
                "(whisper.cpp:5148-5159) and STILL advances the LSTM one step, poisoning the " +
                "recurrence for every frame after it. AudioRecord.read returns UP TO the buffer " +
                "size and the 48 kHz decimator emits \"~1024\" bytes, so one chunk = one frame is " +
                "the common case and never the contract.",
            frame.contains("nBytes != kProbeFrameBytes") && frame.contains("return -1.0f;")
        )
        assertTrue(
            "0.0f must never be returned as a failure value — it is a legitimate probability. " +
                "The pattern deliberately catches every spelling of zero a refactor might reach " +
                "for (0, 0.f, 0.0f, 0.0F), not just the one this function happens not to use.",
            !Regex("""return\s+0(\.\d*)?[fF]?\s*;""").containsMatchIn(frame)
        )
        assertTrue(
            "an uninitialised probe returns the sentinel too",
            frame.contains("g_probe_ctx == nullptr")
        )
        assertTrue(
            "the frame must reach native memory via GetDirectBufferAddress — no per-frame " +
                "FloatArray (2 KB x 31.25/s), no JNI array copy, no callback trampoline",
            frame.contains("GetDirectBufferAddress")
        )
        assertTrue(
            "a direct buffer SHORTER than nBytes must be refused BEFORE the read. " +
                "GetDirectBufferAddress reports where a buffer starts and never how big it is, so " +
                "without GetDirectBufferCapacity a caller that hands over a 512-byte buffer with " +
                "nBytes = 1024 reads 512 bytes past the end of a native allocation — an " +
                "out-of-bounds read no JVM exception will ever surface.",
            frame.contains("GetDirectBufferCapacity")
        )
        assertTrue(
            "the PCM16 -> float copy must NOT go through reinterpret_cast<const int16_t *>. Two " +
                "independent reasons: a direct ByteBuffer carries no int16 alignment guarantee, " +
                "and the cast breaks strict aliasing — at -O3 clang is entitled to assume an " +
                "int16_t* and the unsigned char* it came from never alias, and may reorder or " +
                "fold the loads on that assumption. std::memcpy is the well-defined spelling and " +
                "compiles down to the same halfword load, so the cast buys nothing at all.",
            !Regex("""reinterpret_cast\s*<\s*(const\s+)?int16_t\s*\*\s*>""").containsMatchIn(frame)
        )
        assertTrue(
            "PCM16 must be normalised by /32768.0f — the scale AudioMath.kt:52 " +
                "((sample / 32768f).coerceIn(-1f, 1f)) already prepares every other float audio " +
                "path in this app at. /32767.0f is the plausible-looking mistake: it would put " +
                "the probe's input on a ~0.003% different scale from the batch filter's, which is " +
                "invisible on-device and silently calibrated-against by whatever probability " +
                "threshold Workstream D lands on.",
            Regex("""/\s*32768\.0f""").containsMatchIn(frame)
        )
        assertTrue(
            "streaming MUST use the no_reset entry point; the resetting variant would wipe the " +
                "LSTM on every single frame and the recurrence would never accumulate",
            frame.contains("whisper_vad_detect_speech_no_reset(")
        )
        assertTrue(
            "vadProbeFrame must never call the resetting variant. The negative lookbehind says " +
                "the intent directly — \"this symbol, not one that merely ends with it\" — and " +
                "unlike [^_] it still bites when the call is the very first thing in the scope.",
            !Regex("""(?<!_)whisper_vad_detect_speech\(""").containsMatchIn(frame)
        )

        // The frame contract items that NO signature can carry, pinned on BOTH sides of the JNI
        // boundary. Left phrase = how whisper_jni.cpp spells it, right = how WhisperNative.kt's
        // KDoc spells it; the casing differs on purpose and each side is matched against its own.
        val frameComment = jniCommentFor("vadProbeFrame")
        val frameDoc = ktDocFor("external fun vadProbeFrame(pcm: ByteBuffer, nBytes: Int): Float")
        listOf(
            Triple(
                "the byte-order trap: ByteBuffer.allocateDirect returns a BIG_ENDIAN buffer on " +
                    "EVERY platform whatever the hardware, so a caller who fills it with putShort " +
                    "and never calls order(nativeOrder()) byte-swaps every sample — and the probe " +
                    "then reads plausible-looking noise with no exception and no sentinel",
                "BIG_ENDIAN", "BIG_ENDIAN"
            ),
            Triple(
                "the escape from that trap, named rather than left as an exercise: put(ByteArray) " +
                    "is byte-verbatim and unaffected by the buffer's order",
                "put(ByteArray)", "put(ByteArray)"
            ),
            Triple(
                "the buffer must not be refilled CONCURRENTLY with the call — nothing copies it " +
                    "and nothing locks it, so \"fill, then call, same thread\" is the entire " +
                    "safety argument for a zero-copy frame path",
                "must not refill it CONCURRENTLY", "must not refill it concurrently"
            ),
            Triple(
                "-1.0f does NOT cover a mid-graph compute failure — the call returns 0.0f on the " +
                    "first frame and the previous frame's value after it, so a caller cannot read " +
                    "\"not -1.0f\" as \"the probe is healthy\". It is the one hole in the sentinel " +
                    "and it stays documented until the fork ticket closes it.",
                "mid-graph compute failure", "mid-graph compute failure"
            ),
        ).forEach { (item, inCpp, inKt) ->
            assertTrue(
                "whisper_jni.cpp's vadProbeFrame comment must state $item. Expected \"$inCpp\".",
                frameComment.contains(inCpp)
            )
            assertTrue(
                "WhisperNative.kt's vadProbeFrame KDoc must state $item. Expected \"$inKt\". " +
                    "Workstreams C and D write every caller of this method and read only this " +
                    "KDoc — they never open the .cpp — so an item that lives solely on the native " +
                    "side is invisible to exactly the people who have to obey it.",
                frameDoc.contains(inKt)
            )
        }
    }

    @Test
    fun probeSurface_isFourFunctions_eachIsolatedFromTheBatchFilter() {
        listOf("vadProbeInit", "vadProbeFrame", "vadProbeReset", "vadProbeFree").forEach { fn ->
            val body = jniFunctionBody(fn)
            // POSITIVE pins go through containsLiveLine, NEGATIVE ones through plain contains, and
            // the asymmetry is deliberate. A commented-out `// std::lock_guard ... g_probe_mutex`
            // satisfies contains() while the lock it describes is gone — the exact shape of the
            // false-green already proved twice on this file. In the other direction plain
            // contains() is the STRICTER choice: even a comment mentioning g_vad_ctx inside one of
            // these bodies is a claim about the batch context that has no business here.
            assertTrue("$fn must take g_probe_mutex", containsLiveLine(body, "g_probe_mutex"))
            assertTrue(
                "$fn must not touch g_vad_ctx: the batch filter resets that context's LSTM on " +
                    "entry and clobbers probs[0], which is the slot the probe reads",
                !body.contains("g_vad_ctx")
            )
            assertTrue("$fn must not take g_vad_mutex", !body.contains("g_vad_mutex"))
        }
        assertTrue(
            "vadProbeReset must clear the LSTM state — it is the 'new utterance starts here' " +
                "signal, wired into all five reset sites by Workstream D. Must be a LIVE line: a " +
                "commented-out reset leaves the recurrence running across an utterance boundary, " +
                "which is precisely the bug this function exists to prevent.",
            containsLiveLine(jniFunctionBody("vadProbeReset"), "whisper_vad_reset_state(g_probe_ctx);")
        )

        // vadProbeFree's three prose-only items, pinned on both sides for the same reason as the
        // frame contract above: the blocking width and the ordering rule are lifecycle facts that
        // Workstream E has to design around, and E reads the KDoc.
        //
        // The blocking-width anchor is the SENTENCE ("Blocks until any in-flight"), not the word
        // "in-flight". Demonstrated, not assumed: with the bare word, deleting the blocking-width
        // sentence from the Kotlin KDoc left this class fully GREEN, because the ordering sentence
        // below it also says "in-flight" and satisfied the anchor on its own. Two contract items
        // sharing one distinctive word means the shorter anchor pins neither.
        val freeComment = jniCommentFor("vadProbeFree")
        val freeDoc = ktDocFor("external fun vadProbeFree()")
        listOf(
            Triple(
                "vadProbeFree BLOCKS until an in-flight frame — or, the wide case, an in-flight " +
                    "vadProbeInit MODEL LOAD — completes. \"Idempotent and cheap\" is the natural " +
                    "reading of a free() and it is wrong here.",
                "Blocks until any in-flight", "Blocks until any in-flight"
            ),
            Triple(
                "and therefore belongs on the capture-thread teardown path, never on Main: the " +
                    "init it can queue behind is file I/O plus tensor allocation, which is an ANR " +
                    "rather than a hiccup",
                "never Main", "never on Main"
            ),
            Triple(
                "free-after-init ordering is BINDING on the caller, because idempotent is not " +
                    "order-free: a free that takes the mutex before an in-flight init publishes " +
                    "frees nothing, and the init behind it then leaves a live context that " +
                    "nothing will ever release",
                "order free AFTER init", "AFTER any in-flight"
            ),
        ).forEach { (item, inCpp, inKt) ->
            assertTrue(
                "whisper_jni.cpp's vadProbeFree comment must state $item. Expected \"$inCpp\".",
                freeComment.contains(inCpp)
            )
            assertTrue(
                "WhisperNative.kt's vadProbeFree KDoc must state $item. Expected \"$inKt\".",
                freeDoc.contains(inKt)
            )
        }
    }
}
