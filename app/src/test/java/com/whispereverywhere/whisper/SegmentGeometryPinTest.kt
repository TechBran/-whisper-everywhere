package com.whispereverywhere.whisper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier

/**
 * Source pin for the segment-geometry exports — `lastVadSegments()` and `lastWhisperSegments()` —
 * which carry, per committed chunk, WHERE each VAD segment sat in the raw audio and WHICH bytes of
 * the returned text came out of it. Speaker labels are built on nothing else: the embedder slices
 * the ORIGINAL pcm by the first array and the runs are cut by the second.
 *
 * 4.11 adds a third, `lastTokenTimes()`, at token grain, and with it two new silent failures worth
 * pinning: `dtw_token_timestamps`, the other way to get token times, kills the live words strip
 * with no error at all; and a token walk that started AUTHORING the returned text instead of
 * measuring it would move the transcript, which timing is not allowed to do.
 *
 * WHY A TEST THAT READS C++: `System.loadLibrary("whisper_jni")` throws `UnsatisfiedLinkError` on a
 * plain JVM, so no unit test can CALL any of this, and a compiler cannot check an ORDERING. Two of
 * the three claims below are orderings, and both fail silently on device:
 *
 *  - the trimmed offsets are read off `filtered.size()`, so a write moved BELOW `pcm.swap(filtered)`
 *    measures the raw buffer instead and every trimmed bound is wrong by however much silence the
 *    VAD removed — which reads like a mediocre embedding, not like a bug;
 *  - the vectors are cleared at the TOP of `transcribeRaw`, above every early return, so the four
 *    paths that leave `whisper_full` unrun (null ctx, null samples, "VAD found zero speech", the
 *    no-VAD energy gate) report "no geometry" rather than the PREVIOUS chunk's geometry. Stale
 *    bounds would slice a different chunk's audio and hand the tracker a fingerprint of the wrong
 *    voice.
 *
 * A SIBLING of NativeSegmentStatsContractTest rather than an extension of it: those three counters
 * are diagnostics that may never be read for a decision, and these two arrays are the opposite —
 * load-bearing input to what the user reads. The file locator and the LIVE-line helper below are
 * duplicated from that class on purpose; it is the house idiom (four other test classes each carry
 * their own copy).
 *
 * Every assertion is anchored to CONTENT, never to a line number.
 */
class SegmentGeometryPinTest {

    private fun repoFile(relative: String): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            File(dir, relative).let { if (it.isFile) return it }
            File(dir, "app/$relative").let { if (it.isFile) return it }
            dir = dir.parentFile
        }
        throw AssertionError("could not locate $relative from ${System.getProperty("user.dir")}")
    }

    /**
     * Line endings normalized to LF at the single read site: whisper_jni.cpp is CRLF in this repo
     * and Kotlin's readText() does not normalize, so a helper anchoring on "\n" would silently
     * measure nothing. (The same trap is documented at length on NativeSegmentStatsContractTest.)
     */
    private fun readNormalized(relative: String): String =
        repoFile(relative).readText().replace("\r\n", "\n")

    private val jni: String by lazy { readNormalized("src/main/cpp/whisper_jni.cpp") }

    private val kt: String by lazy {
        readNormalized("src/main/java/com/whispereverywhere/whisper/WhisperNative.kt")
    }

    /**
     * The position of a LIVE line matching [pattern] inside [scope]. Every index used in an
     * ordering assertion here comes from this and never from indexOf(): a literal search measures
     * a COMMENTED-OUT mention exactly as happily as the real statement, so a comment that drifts
     * above the code it describes silently satisfies "X comes first" — a false green already proved
     * twice on this very file.
     */
    private fun live(scope: String, pattern: String, what: String): MatchResult {
        val m = Regex("(?m)^[ \\t]*$pattern").find(scope)
        assertTrue(
            "$what must appear on a LIVE line (pattern: $pattern). Without it there is no index, " +
                "so every ordering claim about it below would compare against a position that " +
                "does not exist.",
            m != null
        )
        return m!!
    }

    /**
     * [scope] with every comment removed — block comments and `//` tails alike.
     *
     * Needed for exactly one kind of claim, the ABSENCE of a statement, and the `live` helper
     * above cannot make it: `live` proves a pattern IS on a code line, while "this must never be
     * written" has to prove no code line carries it. A plain `contains` cannot, because
     * whisper_jni.cpp deliberately NAMES `dtw_token_timestamps` in the LANDMINE comment that
     * explains why it is never set — so the honest form of the pin would be permanently red and
     * the dishonest form (drop the comment) would delete the warning the pin exists to keep.
     *
     * Strips more than it must — a `//` inside a string literal takes the rest of that line with
     * it. That direction is safe here: over-stripping can only make an absence claim pass, and
     * every absence claim in this file is backed by a positive assertion elsewhere.
     */
    private fun codeOnly(scope: String): String = scope
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
        .replace(Regex("""(?m)//.*$"""), "")

    /** `transcribeRaw`'s body. Every JNI function in whisper_jni.cpp closes at column 0. */
    private fun transcribeRawBody(): String {
        val marker = "Java_com_whispereverywhere_whisper_WhisperNative_transcribeRaw("
        val start = jni.indexOf(marker)
        assertTrue(
            "transcribeRaw is not declared in whisper_jni.cpp. indexOf() returns -1 when the " +
                "marker is absent, so substring(start) would silently rebase the scope to the TOP " +
                "of the file and every claim below would be answered by unrelated code hundreds " +
                "of lines away instead of failing here.",
            start >= 0
        )
        val body = jni.substring(start)
        assertTrue(
            "no column-0 \"\\n}\\n\" follows transcribeRaw: substringBefore() returns its " +
                "RECEIVER when the delimiter is absent, so a re-indented closing brace would " +
                "widen this scope into the FOLLOWING functions and the ordering claims would be " +
                "measured across code that has nothing to do with this one.",
            body.contains("\n}\n")
        )
        return body.substringBefore("\n}\n")
    }

    @Test
    fun bothExportsAreDeclaredInKotlinAndCpp() {
        assertTrue(
            "WhisperNative must declare `external fun lastVadSegments(): IntArray` — JNI binds by " +
                "name, so a rename on either side is an UnsatisfiedLinkError at the first read " +
                "and nothing at compile time",
            kt.contains("external fun lastVadSegments(): IntArray")
        )
        assertTrue(
            "WhisperNative must declare `external fun lastWhisperSegments(): IntArray`",
            kt.contains("external fun lastWhisperSegments(): IntArray")
        )
        assertTrue(
            "whisper_jni.cpp must export Java_..._lastVadSegments",
            jni.contains("Java_com_whispereverywhere_whisper_WhisperNative_lastVadSegments")
        )
        assertTrue(
            "whisper_jni.cpp must export Java_..._lastWhisperSegments",
            jni.contains("Java_com_whispereverywhere_whisper_WhisperNative_lastWhisperSegments")
        )
    }

    @Test
    fun bothExportsAreNoArgumentIntArrayNatives_becauseJniBindsByTheShortNameFirst() {
        val clazz = Class.forName(
            "com.whispereverywhere.whisper.WhisperNative", false, javaClass.classLoader
        )
        listOf("lastVadSegments", "lastWhisperSegments").forEach { name ->
            val m = try {
                clazz.getDeclaredMethod(name)
            } catch (e: NoSuchMethodException) {
                throw AssertionError(
                    "WhisperNative declares no $name(). A signature change is the quiet failure " +
                        "here: JNI binds by the SHORT name first, so the same native function is " +
                        "then called with arguments it was not written for. Declared instead: " +
                        clazz.declaredMethods.map { it.name }.sorted(),
                    e
                )
            }
            assertTrue("$name must be declared `external`", Modifier.isNative(m.modifiers))
            assertEquals(
                "ONE IntArray per call: a chunk's segments describe ONE transcribe and must be " +
                    "snapshotted in ONE JNI round trip, or a second transcribe interleaving " +
                    "between two calls hands the reader half of each chunk's geometry.",
                IntArray::class.java, m.returnType
            )
            assertEquals(
                "no arguments: the arrays are process-global and describe the LAST transcribeRaw, " +
                    "not a particular ctx",
                0, m.parameterCount
            )
        }
    }

    @Test
    fun theGeometryIsClearedAtTheTopOfTranscribeRawAndWrittenBeforeTheSwap() {
        val top = jni.indexOf("Java_com_whispereverywhere_whisper_WhisperNative_transcribeRaw")
        assertTrue("transcribeRaw must exist for the ordering below to mean anything", top >= 0)
        val clear = jni.indexOf("g_last_vad_segments.clear()", top)
        val swap = live(jni, """pcm\.swap\(filtered\);""", "the filtered-for-raw swap").range.first
        val write = live(jni, """g_last_vad_segments\.push_back""", "the VAD geometry writer")
            .range.first
        assertTrue("cleared inside transcribeRaw", clear > top)
        assertTrue(
            "the VAD geometry must be written BEFORE pcm.swap(filtered), because the trimmed " +
                "offsets are positions in `filtered` and are read off filtered.size(). After the " +
                "swap `filtered` holds the RAW audio, so a write moved below it records the " +
                "unfiltered buffer's offsets — every trimmed bound then off by however much " +
                "silence the VAD removed, with nothing about the numbers looking wrong.",
            write in 1 until swap
        )
        assertEquals(
            "one writer of the whisper geometry: the four ints of a segment are pushed by a " +
                "SINGLE statement, so no future edit can add a fifth push in one place and leave " +
                "the stride-4 readers on the Kotlin side reading fields out of phase.",
            1, Regex("""g_last_whisper_segments\.push_back""").findAll(jni).count()
        )
    }

    // ------------------------------------------------------------- the NPU tier's substitute

    /** `vadSegmentsOf`'s body. Every JNI function in whisper_jni.cpp closes at column 0. */
    private fun vadSegmentsOfBody(): String {
        val marker = "Java_com_whispereverywhere_whisper_WhisperNative_vadSegmentsOf("
        val start = jni.indexOf(marker)
        assertTrue(
            "vadSegmentsOf is not declared in whisper_jni.cpp. indexOf() returns -1 when the " +
                "marker is absent, so substring(start) would silently rebase the scope to the " +
                "TOP of the file and every claim below would be answered by unrelated code.",
            start >= 0
        )
        val body = jni.substring(start)
        assertTrue("no column-0 \"\\n}\\n\" follows vadSegmentsOf", body.contains("\n}\n"))
        return body.substringBefore("\n}\n")
    }

    @Test
    fun theStandaloneSegmenterIsDeclaredOnBothSidesWithTheShapeTheNpuRouteIndexesWith() {
        assertTrue(
            "WhisperNative must declare `external fun vadSegmentsOf(samples: FloatArray, " +
                "vadModelPath: String): IntArray` — JNI binds by name, so a rename on either " +
                "side is an UnsatisfiedLinkError at the first NPU-tier chunk and nothing at " +
                "compile time",
            kt.contains(
                "external fun vadSegmentsOf(samples: FloatArray, vadModelPath: String): IntArray"
            )
        )
        assertTrue(
            "whisper_jni.cpp must export Java_..._vadSegmentsOf",
            jni.contains("Java_com_whispereverywhere_whisper_WhisperNative_vadSegmentsOf")
        )
        val clazz = Class.forName(
            "com.whispereverywhere.whisper.WhisperNative", false, javaClass.classLoader
        )
        val m = try {
            clazz.getDeclaredMethod("vadSegmentsOf", FloatArray::class.java, String::class.java)
        } catch (e: NoSuchMethodException) {
            throw AssertionError(
                "WhisperNative declares no vadSegmentsOf(FloatArray, String). JNI binds by the " +
                    "SHORT name first, so a signature change calls the same native function " +
                    "with arguments it was not written for. Declared instead: " +
                    clazz.declaredMethods.map { it.name }.sorted(),
                e
            )
        }
        assertTrue("vadSegmentsOf must be declared `external`", Modifier.isNative(m.modifiers))
        assertEquals(
            "ONE IntArray of [start, end] SAMPLE PAIRS — two ints per segment, not four. There " +
                "is no stitched buffer on this path, so there is no second timeline and a " +
                "trimmed pair would be a copy of the first pretending to be a measurement.",
            IntArray::class.java, m.returnType
        )
    }

    @Test
    fun bothVadCallersShareOneSegmenterSoTheOnsetKnobsCannotDrift() {
        // The NPU tier re-runs the VAD to recover the bounds its decoder cannot give it. If it
        // ran its own copy of `threshold = 0.40f` / `speech_pad_ms = 150`, the two would drift the
        // first time either is tuned and the symptom would be a speaker label disagreeing with
        // the paragraph it sits on — on the one tier nobody develops on.
        assertEquals(
            "the VAD threshold is written ONCE in whisper_jni.cpp, inside we_vad_segment",
            1, Regex("""(?m)^[ \t]*vp\.threshold\s*=""").findAll(jni).count()
        )
        assertEquals(
            "the speech pad is written ONCE in whisper_jni.cpp, inside we_vad_segment",
            1, Regex("""(?m)^[ \t]*vp\.speech_pad_ms\s*=""").findAll(jni).count()
        )
        assertEquals(
            "there is ONE call to whisper_vad_segments_from_samples: two would be two segmenters",
            1, Regex("""whisper_vad_segments_from_samples\(""").findAll(jni).count()
        )
        listOf("we_vad_filter", "vadSegmentsOf").forEach { caller ->
            val body =
                if (caller == "we_vad_filter") {
                    jni.substring(jni.indexOf("static bool we_vad_filter(")).substringBefore("\n}\n")
                } else {
                    vadSegmentsOfBody()
                }
            live(body, """we_vad_segment\(""", "$caller's call to the shared segmenter")
        }
    }

    @Test
    fun theStandaloneSegmenterTakesTheVadLockAndWritesNoTranscribeGeometry() {
        val body = vadSegmentsOfBody()
        live(
            body, """std::lock_guard<std::mutex> lock\(g_vad_mutex\);""",
            "vadSegmentsOf's hold of the batch filter's own mutex"
        )
        listOf("g_last_vad_segments", "g_last_whisper_segments", "g_last_vad_in", "g_last_vad_out")
            .forEach { name ->
                assertEquals(
                    "vadSegmentsOf must not touch $name. Those globals describe the LAST " +
                        "transcribeRaw and are read for a DECISION by the CPU tier's span " +
                        "cutter; this function runs on the speaker thread, outside " +
                        "NativeComputeGate, so a write here could overwrite the bounds a CPU " +
                        "chunk's spans are about to be cut against — a speaker's label on " +
                        "another speaker's sentence, with every number still looking plausible.",
                    0, Regex(Regex.escape(name)).findAll(body).count()
                )
            }
        assertEquals(
            "…and it must not take the geometry mutex either: nothing it touches is under it, " +
                "and taking two locks on a path that takes neither today is how a lock order " +
                "gets invented by accident.",
            0, Regex("g_geom_mutex").findAll(body).count()
        )
    }

    @Test
    fun theProbeSafetyBlockNAMESTheFifthUnGatedCallerItNowHas() {
        // The PROBE SAFETY block is the audit anything un-gated owes, and its point 5 says in
        // terms that "the argument above is only as good as this list is complete". Round 1 of
        // review found the list stale: `vadSegmentsOf` is a fifth un-gated whisper_vad entry
        // point and, unlike the four probe functions, it SHARES the batch filter's cached
        // context — process state that before 4.10 was only ever touched inside a
        // NativeComputeGate hold. The next person auditing the gate reads that block, so the
        // block has to say so.
        val start = jni.indexOf("// PROBE SAFETY:")
        assertTrue("the PROBE SAFETY block is gone from whisper_jni.cpp", start >= 0)
        val block = jni.substring(start, jni.indexOf("static std::mutex", start))
        listOf(
            "vadSegmentsOf" to
                "the name of the fifth un-gated caller",
            "g_vad_mutex" to
                "what replaces the gate's serialisation for it — the one thing the shared " +
                    "g_vad_ctx needs and the gate used to supply",
            "g_vad_mutex -> g_geom_mutex" to
                "the lock order it must never invert",
        ).forEach { (needle, what) ->
            assertTrue(
                "the PROBE SAFETY block must record $what. Without it the block reads as " +
                    "\"these four functions\" plus \"nothing else may follow the probe through " +
                    "this hole\", and a reader concludes no such caller exists.",
                block.contains(needle),
            )
        }
    }

    @Test
    fun theClearSitsAboveEveryReturnInTranscribeRaw_soNoChunkInheritsThePreviousChunksBounds() {
        val body = transcribeRawBody()
        val firstReturn = live(body, """return\b""", "transcribeRaw's first return statement")
        listOf("g_last_vad_segments", "g_last_whisper_segments", "g_last_token_times")
            .forEach { name ->
            val clear = live(
                body, """${Regex.escape(name)}\.clear\(\);""", "the clear of $name"
            )
            assertTrue(
                "$name must be cleared ABOVE every return in transcribeRaw, for the reason the " +
                    "three F counters beside it are: FOUR returns leave whisper_full unrun (null " +
                    "ctx, null samples, \"VAD found zero speech\", the no-VAD energy gate) and a " +
                    "clear placed below any of them lets the PREVIOUS chunk's bounds answer for " +
                    "this one. Those bounds index a different chunk's audio, so the embedder " +
                    "would fingerprint the wrong seconds of sound and the tracker would be told " +
                    "about a voice that was never in this commit. Empty means \"no geometry\", " +
                    "and it means that because of this ordering and nothing else.",
                clear.range.first < firstReturn.range.first
            )
        }
    }

    // ------------------------------------------------- 4.11 the timing layer: per-token times

    @Test
    fun theTokenTimesAreDeclaredOnBothSidesWithTheShapeItsSiblingsHave() {
        assertTrue(
            "WhisperNative must declare `external fun lastTokenTimes(): IntArray` — JNI binds by " +
                "name, so a rename on either side is an UnsatisfiedLinkError at the first chunk " +
                "of a speaker session and nothing at compile time",
            kt.contains("external fun lastTokenTimes(): IntArray")
        )
        assertTrue(
            "whisper_jni.cpp must export Java_..._lastTokenTimes",
            jni.contains("Java_com_whispereverywhere_whisper_WhisperNative_lastTokenTimes")
        )
        val clazz = Class.forName(
            "com.whispereverywhere.whisper.WhisperNative", false, javaClass.classLoader
        )
        val m = try {
            clazz.getDeclaredMethod("lastTokenTimes")
        } catch (e: NoSuchMethodException) {
            throw AssertionError(
                "WhisperNative declares no lastTokenTimes(). JNI binds by the SHORT name first, " +
                    "so a signature change calls the same native function with arguments it was " +
                    "not written for. Declared instead: " +
                    clazz.declaredMethods.map { it.name }.sorted(),
                e
            )
        }
        assertTrue("lastTokenTimes must be declared `external`", Modifier.isNative(m.modifiers))
        assertEquals(
            "ONE IntArray per call, for the reason its two siblings are: a chunk's token times " +
                "describe ONE transcribe and must be snapshotted in ONE JNI round trip, or a " +
                "second transcribe interleaving between two calls hands the reader half of each " +
                "chunk's timing.",
            IntArray::class.java, m.returnType
        )
        assertEquals(
            "no arguments: the array is process-global and describes the LAST transcribeRaw, " +
                "not a particular ctx",
            0, m.parameterCount
        )
        assertEquals(
            "one writer of the token times: the four ints of a token are pushed by a SINGLE " +
                "statement, so no future edit can add a fifth push in one place and leave the " +
                "stride-4 readers on the Kotlin side reading fields out of phase.",
            1, Regex("""g_last_token_times\.push_back""").findAll(jni).count()
        )
    }

    @Test
    fun tokenTimingIsAskedForAndTheDtwFlagIsNeverSet() {
        live(
            transcribeRawBody(), """params\.token_timestamps\s*=\s*true;""",
            "the request for whisper's heuristic per-token timestamps"
        )
        // The OTHER way to get token times, and the one that must never be taken. whisper.cpp
        // gates the new-segment callback on !dtw_token_timestamps, so setting it silently turns
        // local partial streaming — the live words strip — OFF, with no error anywhere; the
        // heuristic path asked for above does not. Asserted over code only, because the LANDMINE
        // comment in whisper_jni.cpp names the flag on purpose and must keep naming it.
        assertFalse(
            "dtw_token_timestamps must never be SET in whisper_jni.cpp. It kills the live words " +
                "strip silently — no exception, no log, just no partial previews ever again — " +
                "and the DTW branch's own callback loop is buggy upstream. The heuristic " +
                "`params.token_timestamps` gives this feature everything it needs. (The " +
                "LANDMINE comment naming the flag is fine and required; this assertion reads " +
                "code with the comments stripped.)",
            codeOnly(jni).contains("dtw_token_timestamps")
        )
    }

    @Test
    fun onlyTheSegmentTextEverReachesTheReturnedBytes_soTimingCannotChangeTheTranscript() {
        // The plan for the timing layer offered a second way to build the returned text: append
        // each TOKEN's text and take the byte offsets from that walk. whisper.cpp makes the two
        // identical — a segment's `text` is exactly the concatenation of its non-special tokens
        // (whisper.cpp:7896, `text += whisper_token_to_str(...)` over the same i0..i range that
        // becomes `result_all.back().tokens`) — but "identical today" is not the same guarantee
        // as "cannot differ", and the transcript is the one thing this feature may not touch.
        // So the segment text stays the sole author of the bytes and the token walk only
        // MEASURES it. This pin is what keeps that true: a future edit that starts appending
        // token text to `result` fails here rather than on a user's screen.
        val body = transcribeRawBody()
        val appends = Regex("""(?m)^[ \t]*result \+= (\w+);""").findAll(codeOnly(body)).toList()
        assertEquals(
            "exactly ONE statement may append to `result` in transcribeRaw — the segment-text " +
                "append. Found: " + appends.map { it.groupValues[1] },
            1, appends.size
        )
        assertEquals(
            "…and what it appends must be the SEGMENT text (`seg`), whisper's own authoritative " +
                "string, not a string rebuilt from tokens. Timing is additive: it may read the " +
                "bytes, never write them.",
            "seg", appends.single().groupValues[1]
        )
    }
}
