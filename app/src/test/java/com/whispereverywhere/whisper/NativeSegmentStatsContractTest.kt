package com.whispereverywhere.whisper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier

/**
 * Contract guards for the 3.7 Workstream F cost counters — `g_last_ctx_frames`, `g_last_vad_in`,
 * `g_last_vad_out` — and for their single reader, `lastSegmentStats()`.
 *
 * WHY A TEST THAT READS C++: `System.loadLibrary("whisper_jni")` throws `UnsatisfiedLinkError` on a
 * plain JVM, so no unit test can CALL any of this; `:app:assembleDebug` is the only gate that even
 * compiles it, and a compiler cannot check an ordering, an array order or a log tag. Everything
 * this class pins is therefore invisible to the rest of the suite and free to regress silently —
 * and each item below is one where the regression is SILENT on-device too: wrong numbers in a
 * diagnostics line read like a surprising measurement, not like a bug.
 *
 * A SIBLING of NativeVadSourceContractTest rather than an extension of it, deliberately: two of the
 * three counters are the batch VAD filter's before/after, but `ctxFrames` is the ENCODER audio
 * context from `transcribeRaw` and is not a VAD fact at all, and the reset-at-top honesty rule is a
 * claim about `transcribeRaw`'s early returns. The file locator below is duplicated from that class
 * on purpose: it is the house idiom, and `CaptureThreadPolicyTest`, `ProbeStatsTest`,
 * `SegmentTimingTest` and `NativeVadSourceContractTest` each already carry their own copy.
 *
 * Every assertion is anchored to CONTENT, never to a line number — the 3.7 spec's own line anchors
 * for this very task were stale before it started.
 */
class NativeSegmentStatsContractTest {

    private fun repoFile(relative: String): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            File(dir, relative).let { if (it.isFile) return it }
            File(dir, "app/$relative").let { if (it.isFile) return it }
            dir = dir.parentFile
        }
        throw AssertionError(
            "could not locate $relative from ${System.getProperty("user.dir")}"
        )
    }

    /**
     * Line endings are normalized to LF at the single read site so every helper below can anchor on
     * "\n" regardless of how the file is checked out. whisper_jni.cpp is CRLF in this repo and
     * Kotlin's readText() does not normalize: without this, substringBefore("\n}\n") never matches
     * (the real byte sequence at a column-0 brace is "\r\n}\r\n") and silently falls through to
     * returning the WHOLE FILE, so every "body" assertion becomes a whole-file assertion.
     */
    private fun readNormalized(relative: String): String =
        repoFile(relative).readText().replace("\r\n", "\n")

    private val jni: String by lazy { readNormalized("src/main/cpp/whisper_jni.cpp") }

    private val kt: String by lazy {
        readNormalized("src/main/java/com/whispereverywhere/whisper/WhisperNative.kt")
    }

    /** One C++ function's body. Every function this class scopes closes at column 0. */
    private fun cppBody(anchor: String): String {
        val start = jni.indexOf(anchor)
        assertTrue(
            "anchor \"$anchor\" is missing from whisper_jni.cpp. indexOf() returns -1 when the " +
                "anchor is absent, so substring(start) silently rebases the scope to the TOP of " +
                "the file and every claim below is then answered by unrelated code hundreds of " +
                "lines away — passing on borrowed text instead of failing here.",
            start >= 0
        )
        val body = jni.substring(start)
        assertTrue(
            "no column-0 \"\\n}\\n\" follows \"$anchor\". substringBefore() returns its RECEIVER " +
                "when the delimiter is absent, so a mangled or re-indented closing brace silently " +
                "widens the scope into the FOLLOWING functions, and ordering claims would then be " +
                "measured across code that has nothing to do with this one.",
            body.contains("\n}\n")
        )
        return body.substringBefore("\n}\n")
    }

    private fun jniBody(name: String): String =
        cppBody("Java_com_whispereverywhere_whisper_WhisperNative_$name(")

    /**
     * The COMMENT block immediately above one JNI export — a different scope from [jniBody], which
     * starts AT the export and therefore contains none of the prose.
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
                "previous blank line\", so DELETING the prose does not fail the phrase assertions " +
                "— it silently rebases the scope onto whatever declaration precedes it, and the " +
                "phrases then fail in a way that reads like drift rather than deletion.",
            block.trimStart('\n').lineSequence().first().trimStart().startsWith("//")
        )
        assertTrue(
            "the comment scope for $name widened past a previous function body (it contains a " +
                "column-0 closing brace), so the phrase assertions would be answered by prose " +
                "belonging to a different function",
            !block.contains("\n}")
        )
        return block
    }

    /**
     * The KDoc block immediately above [declaration] in WhisperNative.kt. (Spelling the opening
     * marker out in this comment is not possible: Kotlin NESTS block comments, so it would open one
     * that never closes.)
     */
    private fun ktDocFor(declaration: String): String {
        val at = kt.indexOf(declaration)
        assertTrue("WhisperNative.kt does not declare \"$declaration\"", at >= 0)
        val head = kt.substring(0, at)
        val open = head.lastIndexOf("/**")
        assertTrue("no KDoc block opens above \"$declaration\"", open >= 0)
        val block = head.substring(open)
        assertTrue(
            "the KDoc scope for \"$declaration\" widened past a previous member: lastIndexOf " +
                "(\"/**\") finds the NEAREST KDoc above the declaration, so deleting this member's " +
                "KDoc outright silently borrows the previous member's — and a phrase assertion " +
                "could then be satisfied by documentation written about a different function.",
            !block.contains("external fun")
        )
        return block
    }

    /**
     * True when [needle] appears on a line of LIVE code inside [scope]; a commented-out line does
     * not count. A `// g_last_vad_in.store(...)` left behind by a refactor would keep a plain
     * contains() green while the counter it describes is never written.
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
     * The position of a LIVE line matching [pattern] inside [scope], as a match so callers get an
     * index. EVERY index used in an ordering assertion in this class comes from here and never from
     * indexOf(): a literal search measures a COMMENTED-OUT mention exactly as happily as the real
     * statement, so a comment that drifts above the code it describes silently satisfies "X comes
     * first" — the false green already proved twice on this file.
     */
    private fun live(scope: String, pattern: String, what: String): MatchResult {
        val m = Regex("(?m)^[ \\t]*$pattern").find(scope)
        assertTrue(
            "$what must appear on a LIVE line (pattern: $pattern). Without it there is no index, " +
                "so every ordering claim about it below would be comparing against a position " +
                "that does not exist.",
            m != null
        )
        return m!!
    }

    // -----------------------------------------------------------------------------------------
    // The three counters and their reader
    // -----------------------------------------------------------------------------------------

    @Test
    fun theCountersAreZeroInitialisedProcessGlobalAtomics_notPlainInts() {
        listOf("g_last_ctx_frames", "g_last_vad_in", "g_last_vad_out").forEach { name ->
            assertTrue(
                "$name must be a zero-initialised std::atomic<int>. ATOMIC because the writers " +
                    "run inside NativeComputeGate but lastSegmentStats is its own JNI entry point " +
                    "and nothing in the type system forces its caller onto the writing thread — a " +
                    "plain int is a formal data race the day someone reads it off-gate, which is " +
                    "exactly the mistake the probe surface already had to correct once " +
                    "(we_install_native_logging). ZERO-INITIALISED because a process that has " +
                    "never transcribed must report \"whisper_full never ran\", not garbage.",
                Regex("""static\s+std::atomic<int>\s+$name\{0\};""").containsMatchIn(jni)
            )
        }
    }

    @Test
    fun lastSegmentStatsIsDeclaredOnBothSidesOfTheBoundary_becauseJniResolvesByName() {
        val clazz =
            Class.forName("com.whispereverywhere.whisper.WhisperNative", false, javaClass.classLoader)
        val m = try {
            clazz.getDeclaredMethod("lastSegmentStats")
        } catch (e: NoSuchMethodException) {
            throw AssertionError(
                "WhisperNative declares no lastSegmentStats(), and nothing on the Kotlin side " +
                    "makes that a compile error. A rename leaves the native symbol " +
                    "Java_com_whispereverywhere_whisper_WhisperNative_lastSegmentStats matching no " +
                    "declared method and throws UnsatisfiedLinkError at the first read; a " +
                    "signature change is quieter, because JNI binds by the SHORT name first and " +
                    "the same native function is then called with arguments it was not written " +
                    "for. Declared instead: " +
                    clazz.declaredMethods.map { it.name }.sorted(),
                e
            )
        }
        assertTrue("lastSegmentStats must be declared `external`", Modifier.isNative(m.modifiers))
        assertEquals(
            "an IntArray, not three separate calls: the three counters describe ONE transcribe " +
                "and must be snapshotted in ONE JNI round trip, or a batch chunk interleaving " +
                "between two calls hands the reader half of each segment's numbers.",
            IntArray::class.java, m.returnType
        )
        assertEquals(
            "no arguments: the counters are process-global and describe the LAST transcribeRaw, " +
                "not a particular ctx",
            0, m.parameterTypes.size
        )
        assertTrue(
            "the C++ side must export exactly " +
                "Java_com_whispereverywhere_whisper_WhisperNative_lastSegmentStats returning " +
                "jintArray. JNI matches this mangled name character for character and neither " +
                "side's compiler checks the other's.",
            Regex(
                """extern "C" JNIEXPORT jintArray JNICALL\n""" +
                    """Java_com_whispereverywhere_whisper_WhisperNative_lastSegmentStats\("""
            ).containsMatchIn(jni)
        )
        assertTrue(
            "the export must take the INSTANCE shape (JNIEnv *env, jobject). WhisperNative is a " +
                "Kotlin `object`, so its externs are instance methods and the JVM passes the " +
                "receiver as the second argument; a (JNIEnv*, jclass) signature would still bind " +
                "and still read the same stack slot, so this is a discipline the compiler cannot " +
                "enforce on either side.",
            Regex("""lastSegmentStats\(\s*JNIEnv \*env, jobject""").containsMatchIn(jni)
        )
    }

    @Test
    fun lastSegmentStatsReturnsTheThreeCountersInContractOrder_ctxFramesVadInVadOut() {
        val body = jniBody("lastSegmentStats")
        val ctxFrames = live(
            body, """static_cast<jint>\(g_last_ctx_frames\.load\(""", "the ctxFrames slot"
        )
        val vadIn = live(body, """static_cast<jint>\(g_last_vad_in\.load\(""", "the vadIn slot")
        val vadOut = live(body, """static_cast<jint>\(g_last_vad_out\.load\(""", "the vadOut slot")
        assertTrue(
            "the array is [ctxFrames, vadIn, vadOut] and the ORDER is the whole contract: three " +
                "ints carry no names across JNI, so a permutation here is not a compile error, " +
                "not a runtime error, and not visible in any log — it silently relabels the " +
                "encoder's cost as a sample count. The diagnostic then reads as a strange " +
                "measurement rather than as a bug, which is the worst failure mode a diagnostic " +
                "can have.",
            ctxFrames.range.first < vadIn.range.first && vadIn.range.first < vadOut.range.first
        )
        assertTrue(
            "the payload must be a 3-element jint buffer",
            containsLiveLine(body, "jint values[3]")
        )
        assertTrue(
            "the array is allocated with exactly 3 elements",
            containsLiveLine(body, "NewIntArray(3)")
        )
        assertTrue(
            "all 3 elements are written from offset 0 in one SetIntArrayRegion — a partial write " +
                "would leave a JVM-zeroed slot that reads exactly like an honest \"never ran\"",
            containsLiveLine(body, "SetIntArrayRegion(out, 0, 3, values)")
        )
    }

    @Test
    fun lastSegmentStatsChecksItsAllocation_soAFailedNewIntArrayCannotBecomeAPendingException() {
        val body = jniBody("lastSegmentStats")
        val nullCheck = live(body, """if \(out == nullptr\)""", "the NewIntArray null check")
        val write = live(body, """env->SetIntArrayRegion\(""", "the SetIntArrayRegion write")
        assertTrue(
            "the null check must come BEFORE the write. A failed NewIntArray leaves an " +
                "OutOfMemoryError PENDING, and calling SetIntArrayRegion with an exception in " +
                "flight is what CheckJNI turns into a process abort — the same hazard the " +
                "new-segment trampoline in this file already documents. A diagnostics read must " +
                "never be able to kill the process.",
            nullCheck.range.first < write.range.first
        )
        assertTrue(
            "the failure path returns nullptr, which Kotlin sees as null — the caller's problem " +
                "to guard, not a silently fabricated all-zero reading",
            containsLiveLine(body, "return nullptr;")
        )
    }

    // -----------------------------------------------------------------------------------------
    // Where the counters are written
    // -----------------------------------------------------------------------------------------

    @Test
    fun theCountersAreResetAtTheTopOfTranscribeRaw_aboveEveryEarlyReturn() {
        val body = jniBody("transcribeRaw")
        val resets = listOf("g_last_ctx_frames", "g_last_vad_in", "g_last_vad_out").map { name ->
            name to live(
                body,
                """${Regex.escape(name)}\.store\(0, std::memory_order_relaxed\);""",
                "the reset of $name"
            )
        }
        val firstReturn = live(body, """return\b""", "transcribeRaw's first return statement")
        resets.forEach { (name, reset) ->
            assertTrue(
                "$name must be reset ABOVE every return in transcribeRaw. FOUR returns leave " +
                    "whisper_full unrun — a null ctx, a null sample array, \"VAD found zero " +
                    "speech\", and the no-VAD energy gate — and none of them reaches the " +
                    "audio_ctx block. A reset placed below any of them lets the PREVIOUS " +
                    "segment's numbers answer for this one, which makes an encoder-free commit " +
                    "look like it paid for a 512-frame encode. The honesty of both documented " +
                    "readings depends on this ordering and on nothing else: ctxFrames=0 means " +
                    "\"whisper_full never ran\", vadIn=0 vadOut=0 means \"no VAD ran at all\". " +
                    "This assertion is stated against the FIRST return rather than against a " +
                    "list of known paths, so a new early return added above the resets fails " +
                    "here instead of quietly inheriting stale values.",
                reset.range.first < firstReturn.range.first
            )
        }
    }

    @Test
    fun theVadFilterRecordsBeforeAndAfter_aboveTheSwapThatDestroysTheBeforeCount() {
        val body = cppBody("static bool we_vad_filter(")
        val vadIn = live(
            body,
            """g_last_vad_in\.store\(static_cast<int>\(pcm\.size\(\)\)""",
            "the vadIn store"
        )
        val vadOut = live(
            body,
            """g_last_vad_out\.store\(static_cast<int>\(filtered\.size\(\)\)""",
            "the vadOut store"
        )
        val swap = live(body, """pcm\.swap\(filtered\);""", "the filtered-for-raw swap")
        assertTrue(
            "vadIn must be read from pcm.size() BEFORE pcm.swap(filtered). After the swap pcm IS " +
                "the filtered audio, so a store moved below it reports vadIn == vadOut on every " +
                "chunk — and the one signature these two counters exist to make visible, " +
                "vadIn > 0 with vadOut == 0 (the probe cut on speech the batch filter then threw " +
                "away entirely), becomes unreachable. Nothing would fail; the counters would " +
                "simply always agree.",
            vadIn.range.first < swap.range.first
        )
        assertTrue(
            "vadOut must be read from filtered.size() before the swap, for the same reason: " +
                "afterwards `filtered` holds the RAW audio and the two numbers trade places",
            vadOut.range.first < swap.range.first
        )
        assertTrue(
            "we_vad_filter must not touch the encoder counter — ctxFrames is published by the " +
                "audio_ctx block in transcribeRaw and a second writer here would race the reset",
            !body.contains("g_last_ctx_frames")
        )
    }

    @Test
    fun ctxFramesIsPublishedAfterBothClamps_soItReportsWhatTheEncoderActuallyPaid() {
        val body = jniBody("transcribeRaw")
        val floor = live(
            body,
            """if \(neededFrames < floorFrames\) neededFrames = floorFrames;""",
            "the audio_ctx floor clamp"
        )
        val cap = live(
            body,
            """if \(neededFrames > 1500\) neededFrames = 1500;""",
            "the audio_ctx 1500-frame cap"
        )
        val store = live(
            body,
            """g_last_ctx_frames\.store\(neededFrames, std::memory_order_relaxed\);""",
            "the ctxFrames publish"
        )
        assertTrue(
            "ctxFrames must be published AFTER the floor clamp. The floor is the entire point of " +
                "this counter: the production default is 512 frames, so a 2.4 s utterance needs " +
                "~64 + 120 frames and still pays for 512 — publishing the pre-clamp value would " +
                "report the cost the arithmetic hoped for instead of the cost the encoder was " +
                "billed, and every conclusion drawn from the diagnostic would be wrong in the " +
                "same direction.",
            store.range.first > floor.range.first
        )
        assertTrue(
            "ctxFrames must be published AFTER the 1500-frame cap as well, or a long commit " +
                "reports a context the model cannot even hold",
            store.range.first > cap.range.first
        )
        assertTrue(
            "ctxFrames must be the SAME value handed to params.audio_ctx",
            containsLiveLine(body, "params.audio_ctx = neededFrames;")
        )
    }

    // -----------------------------------------------------------------------------------------
    // The log tag, and the prose that no signature can carry
    // -----------------------------------------------------------------------------------------

    @Test
    fun theVadFilterLineCarriesTheWeDiagTag_withItsTextUnchanged() {
        assertTrue(
            "whisper_jni.cpp must define LOGDIAG on the literal tag \"WE-DIAG\". The owner's " +
                "acceptance greps are `adb logcat -s WE-DIAG`; a native line that belongs to that " +
                "story but prints on the whisper_jni tag is invisible to the capture that is " +
                "supposed to answer for it.",
            Regex(
                """#define LOGDIAG\(\.\.\.\) """ +
                    """__android_log_print\(ANDROID_LOG_INFO, "WE-DIAG", __VA_ARGS__\)"""
            ).containsMatchIn(jni)
        )
        val body = cppBody("static bool we_vad_filter(")
        live(
            body,
            """LOGDIAG\("VAD: %zu -> %zu samples \(%d segments\) wallMs=%\.1f",""",
            "the VAD filter's summary line"
        )
        assertTrue(
            "the format string is deliberately BYTE-IDENTICAL to the one this line has always " +
                "carried — only the tag moved — so every existing grep for `VAD: ` still matches " +
                "and the B' wallMs measurement is not silently dropped in the move. No LOGI " +
                "spelling of it may survive.",
            !containsLiveLine(body, "LOGI(\"VAD: ")
        )
    }

    @Test
    fun theProseOnlyContractIsStatedOnBothSidesOfTheJniBoundary() {
        // Left phrase = how whisper_jni.cpp spells it, right = how WhisperNative.kt's KDoc spells
        // it; the casing and wording differ on purpose and each side is matched against its own.
        // Both audiences are real and they read different files: whoever moves the native code
        // reads the .cpp, and Workstream F4 — which writes the only legitimate caller — reads the
        // KDoc and never opens the .cpp at all.
        val comment = jniCommentFor("lastSegmentStats")
        val doc = ktDocFor("external fun lastSegmentStats(): IntArray")
        listOf(
            Triple(
                "the counters are PROCESS-GLOBAL, not per-ctx and not per-call — the same trap " +
                    "detectedLanguage already documents on this surface",
                "Process-global", "PROCESS-GLOBAL"
            ),
            Triple(
                "the serialisation that makes them readable at all is NativeComputeGate, which " +
                    "is what stands between these statics and a second transcribe overwriting " +
                    "them mid-read",
                "NativeComputeGate", "NativeComputeGate"
            ),
            Triple(
                "the read must happen INSIDE that same gate hold, tagged with its ctx — a batch " +
                    "chunk interleaving after the gate is released is otherwise misread as the " +
                    "bubble segment's numbers, and nothing about the values would look wrong",
                "INSIDE that same gate hold", "inside the gate"
            ),
            Triple(
                "vadIn > 0 with vadOut == 0 is the probe-vs-batch-filter disagreement — the one " +
                    "thing a `cut=vad` endpoint cannot tell you on its own, and the reason these " +
                    "two counters are worth carrying at all",
                "probe-vs-batch-filter disagreement", "probe-vs-batch-filter"
            ),
            Triple(
                "these numbers are DIAGNOSTICS and must never be read for a decision: they are " +
                    "process-global and lag by exactly one transcribe, so any control flow built " +
                    "on them would be correct only by accident",
                "never read for a decision", "never read for a decision"
            ),
        ).forEach { (item, inCpp, inKt) ->
            assertTrue(
                "whisper_jni.cpp's lastSegmentStats comment must state $item. Expected \"$inCpp\".",
                comment.contains(inCpp)
            )
            assertTrue(
                "WhisperNative.kt's lastSegmentStats KDoc must state $item. Expected \"$inKt\".",
                doc.contains(inKt)
            )
        }
    }
}
