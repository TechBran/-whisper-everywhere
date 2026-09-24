package com.whispereverywhere.npu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-level contract guards for the MediaTek APU tier's seam (P1b): `litert_asr.cpp`, its Kotlin
 * half `LiteRtAsrNative.kt`, and the CMake target that builds `liblitertasr.so`.
 *
 * WHY SOURCE, as in [NpuNativeContractTest]: `LiteRtAsrNative` carries
 * `init { System.loadLibrary("litertasr") }`, so a JVM test that NAMED the object would die with
 * `UnsatisfiedLinkError`, and the native side's first execution is the probe app's device gate. The
 * constructs that would each cost a tablet round trip are pinned here instead, anchored to content
 * and scoped to LIVE lines (a commented-out call satisfies `contains()` as happily as the call).
 *
 * The three files are explicit inputs of the test task in `app/build.gradle.kts`; without that, an
 * edit confined to any of them would leave `:app:testDebugUnitTest` UP-TO-DATE and these guards would
 * pass against stale evidence.
 */
class LiteRtNativeContractTest {

    private fun source(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            for (candidate in listOf(File(dir, relative), File(dir, "app/$relative"))) {
                if (candidate.isFile) return candidate.readText().replace("\r\n", "\n")
            }
            dir = dir.parentFile
        }
        throw AssertionError("cannot locate $relative from ${System.getProperty("user.dir")}")
    }

    /** C++/Kotlin comment lines; with [hash], CMake's `#` lines too (never for C++: `#include`). */
    private fun isComment(line: String, hash: Boolean = false): Boolean {
        val t = line.trimStart()
        return t.startsWith("//") || t.startsWith("/*") || t.startsWith("*") || (hash && t.startsWith("#"))
    }

    /** The LIVE lines of [scope] containing [needle], trimmed. */
    private fun liveLines(scope: String, needle: String, hash: Boolean = false): List<String> =
        scope.split("\n").filterNot { isComment(it, hash) }.map { it.trim() }.filter { it.contains(needle) }

    /** Character offsets of the live lines of [scope] containing [needle]. */
    private fun liveOffsets(scope: String, needle: String): List<Int> {
        val out = mutableListOf<Int>()
        var at = 0
        for (line in scope.split("\n")) {
            if (!isComment(line) && line.contains(needle)) out += at
            at += line.length + 1
        }
        return out
    }

    /** [scope] with comment lines dropped and all whitespace collapsed to single spaces. */
    private fun collapsed(scope: String, hash: Boolean = false): String =
        scope.split("\n").filterNot { isComment(it, hash) }.joinToString(" ").replace(Regex("\\s+"), " ")

    /** One free function's body; every function in `litert_asr.cpp` closes at column 0. */
    private fun functionBody(anchor: String): String {
        val start = cpp.indexOf(anchor)
        assertTrue("anchor \"$anchor\" is missing from litert_asr.cpp", start >= 0)
        val body = cpp.substring(start)
        assertTrue("no column-0 closing brace follows \"$anchor\"", body.contains("\n}\n"))
        return body.substringBefore("\n}\n")
    }

    private val cpp: String by lazy { source("src/main/cpp/litert_asr.cpp") }
    private val seam: String by lazy { source("src/main/java/com/whispereverywhere/npu/LiteRtAsrNative.kt") }
    private val cmake: String by lazy { source("src/main/cpp/CMakeLists.txt") }
    private val bandScan: String by lazy { source("src/main/cpp/band_scan.h") }
    private val stamp: String by lazy { source("src/main/cpp/litert_stamp.h") }
    private val gradle: String by lazy { source("build.gradle.kts") }

    /**
     * **The CMake target: `litertasr`, one source, linked to `log` and `dl` and nothing else, and
     * skipped loudly when the vendored headers are missing** (design §2.5).
     *
     * The link set is the property that matters most and the one a tidy-up would break: a link edge
     * to libLiteRt.so would turn "the runtime is not in this APK" from a readable
     * `probe: runtime: …` string into a load-time crash of the library — the failure shape the
     * dlopen portal exists to refuse. The guard is the qnnasr precedent: a tree without the headers
     * must still build the CPU and GPU tiers, with a WARNING that names the directory.
     */
    @Test
    fun theCMakeTargetIsLitertasrLinkedToLogAndDlOnlyAndGuardedOnTheVendoredHeaders() {
        val flat = collapsed(cmake, hash = true)
        assertTrue(
            "CMakeLists.txt must declare `add_library(litertasr SHARED litert_asr.cpp)` on a live line",
            liveLines(cmake, "add_library(litertasr SHARED litert_asr.cpp)", hash = true).size == 1
        )
        val link = Regex("target_link_libraries\\(litertasr ([^)]*)\\)").find(flat)
        assertTrue("litertasr must have a target_link_libraries call", link != null)
        assertEquals(
            "litertasr links log and dl and NOTHING else - every LiteRT entry point is dlsym()ed",
            "log dl",
            link!!.groupValues[1].trim()
        )
        assertTrue(
            "the vendored LiteRT 2.1.1 headers are the include root",
            flat.contains(
                "set(LITERT_INCLUDE_DIR \${CMAKE_CURRENT_SOURCE_DIR}/third_party/litert-2.1.1)"
            ) && flat.contains("target_include_directories(litertasr PRIVATE \${LITERT_INCLUDE_DIR}")
        )
        val guard = flat.indexOf("if(EXISTS \${LITERT_INCLUDE_DIR}/litert/c/litert_compiled_model.h")
        val add = flat.indexOf("add_library(litertasr SHARED")
        val warn = flat.indexOf("SKIPPING liblitertasr.so")
        assertTrue(
            "the target must sit INSIDE a header-presence guard (guard at $guard, add_library at " +
                "$add) whose else branch warns that it is skipping liblitertasr.so (at $warn)",
            guard >= 0 && add > guard && warn > add &&
                flat.substring(add, warn).contains("else() message(WARNING")
        )
        assertTrue(
            "the build_config.h litert_common.h includes is GENERATED from the vendored template, " +
                "with the GPU declarations off (they would pull <CL/cl.h> into the build)",
            flat.contains("set(LITERT_BUILD_CONFIG_DISABLE_GPU 1)") &&
                flat.contains("configure_file( \${LITERT_INCLUDE_DIR}/litert/build_common/build_config.h.in")
        )
    }

    /**
     * **Every JNI entry point of `litert_asr.cpp` is an external of `LiteRtAsrNative`, and every
     * external has one, with the same number of arguments.**
     *
     * A JNI name that matches no external is dead code the device would never reach; an external with
     * no JNI function is an `UnsatisfiedLinkError` on the first call — on the tablet, not here. And an
     * arity mismatch is the worst of the three: JNI does not check it, so a `nativeInit` that grew a
     * parameter on one side only reads a jint out of whatever register the missing argument would
     * have been in. Compared as name -> argument count (the JNI side minus `env` and `this`).
     */
    @Test
    fun everyJniEntryPointMatchesAnExternalOfLiteRtAsrNativeWithTheSameArity() {
        val jni = Regex("Java_com_whispereverywhere_npu_LiteRtAsrNative_(\\w+)\\(([^)]*)\\)")
            .findAll(collapsed(cpp))
            .associate { m -> m.groupValues[1] to m.groupValues[2].split(",").size - 2 }
        val kotlin = Regex("external fun (\\w+)\\(([^)]*)\\)")
            .findAll(collapsed(seam))
            .associate { m ->
                val params = m.groupValues[2].trim().trimEnd(',').trim()
                m.groupValues[1] to (if (params.isEmpty()) 0 else params.split(",").size)
            }
        val expected = setOf(
            "nativeProbe", "nativeInit", "nativeEncode", "nativeDecodeSegment", "nativeDetectLanguage",
            "nativeSetDiag", "nativeLastError", "nativeEpoch", "nativeRelease",
        )
        assertEquals("the JNI surface is exactly the nine entry points of the contract", expected, jni.keys)
        assertEquals("LiteRtAsrNative declares exactly those nine externals", expected, kotlin.keys)
        assertEquals("every entry point has the same argument count on both sides", kotlin, jni)
        assertTrue(
            "the library the object loads is the target CMake builds",
            liveLines(seam, "System.loadLibrary(\"litertasr\")").size == 1
        )
        assertTrue(
            "the decode entry point keeps QnnAsrNative's argument list, name for name",
            collapsed(seam).contains(
                "external fun nativeDecodeSegment( prompt: IntArray, suppress: IntArray, beginSuppress: " +
                    "IntArray, maxTokens: Int, out: IntArray, temperatures: FloatArray, entropyThold: Float, " +
                    "logprobThold: Float, noSpeechThold: Float, noSpeechToken: Int, cycleMaxDistinct: Int, " +
                    "stats: FloatArray, ): Int"
            )
        )
        assertTrue(
            "the encoder takes the FLOAT mel as a direct ByteBuffer - there is no quantisation " +
                "transport on this engine",
            liveLines(seam, "external fun nativeEncode(melF32: java.nio.ByteBuffer): String").size == 1 &&
                liveLines(seam, "nativeInputQuant").isEmpty()
        )
    }

    /**
     * **The LiteRT environment is never destroyed, and nothing is ever dlclose()d** (design §2.6).
     *
     * The environment, libLiteRt.so and the Neuron adapter handles are process state: the adapter's
     * first dlopen is MediaTek's own 5 s constructor, and a re-arm after a trim must pay only the
     * bytecode restores. The environment also cannot be rebuilt against a different dispatch
     * directory. So `LiteRtDestroyEnvironment` is not even RESOLVED - it is absent from the symbol
     * list, which makes the call unwritable - and release frees the session and nothing else.
     */
    @Test
    fun theEnvironmentIsNeverDestroyedAndNothingIsEverDlclosed() {
        assertTrue(
            "no live line of litert_asr.cpp may name LiteRtDestroyEnvironment. Found: " +
                liveLines(cpp, "LiteRtDestroyEnvironment"),
            liveLines(cpp, "LiteRtDestroyEnvironment").isEmpty()
        )
        assertTrue(
            "no live line may dlclose anything - the adapter and the runtime stay mapped. Found: " +
                liveLines(cpp, "dlclose"),
            liveLines(cpp, "dlclose").isEmpty()
        )
        val release = functionBody("void releaseLocked() {")
        listOf("rt.env", "rt.lib", "adapter.", "LiteRtCreateEnvironment").forEach {
            assertTrue(
                "releaseLocked must not touch process state - `$it` on a live line of it: " +
                    liveLines(release, it),
                liveLines(release, it).isEmpty()
            )
        }
        listOf("LiteRtDestroyTensorBuffer(", "LiteRtDestroyCompiledModel(", "LiteRtDestroyModel(",
               "LiteRtDestroyOptions(", "LiteRtDestroyTensorBufferRequirements(").forEach {
            assertTrue("releaseLocked frees the session: `$it` on a live line", liveLines(release, it).isNotEmpty())
        }
        val ensure = functionBody("std::string ensureEnvironmentLocked(")
        assertTrue(
            "the environment is created once, with the dispatch directory as its one option",
            liveLines(ensure, "opt.tag = kLiteRtEnvOptionTagDispatchLibraryDir;").size == 1 &&
                liveLines(ensure, "if (rt.env) {").size == 1
        )
        assertTrue(
            "and the probe does NOT create it - it waits for init, after P2's prepare has staged the " +
                "dispatch into the directory the environment scans",
            liveLines(functionBody("std::string probeLocked("), "ensureEnvironmentLocked").isEmpty() &&
                liveLines(
                    functionBody("Java_com_whispereverywhere_npu_LiteRtAsrNative_nativeProbe("),
                    "ensureEnvironmentLocked"
                ).isEmpty()
        )
    }

    /**
     * **The Neuron adapter is walked in LiteRT v2.1.1's order, with `RTLD_NOW | RTLD_NODELETE`, every
     * loaded handle is kept, and the LAST one that loads is the winner** (design §2.3). The flags are
     * not LiteRT's (`RTLD_LAZY | RTLD_LOCAL`), and on bionic they admit the same candidates: `RTLD_LAZY`
     * is unsupported there and `RTLD_LOCAL` is the default; `RTLD_NODELETE` is what keeps the handle.
     *
     * v2.1.1's candidate loop has no `break`, so the adapter LiteRT ends up using is the last loadable
     * name; a driver check that stopped at the first hit would judge a different library from the one
     * that runs the bytecode. The fourth candidate is `<dispatch dir>/libneuron_adapter.so`.
     */
    @Test
    fun theAdapterIsWalkedInLiteRtsOrderWithRtldNodeleteAndTheLastLoaderWins() {
        val flat = collapsed(cpp)
        assertTrue(
            "the three named candidates, in LiteRT's order",
            flat.contains(
                "constexpr const char *kAdapterCandidates[] = { \"libneuronusdk_adapter.mtk.so\", " +
                    "\"libneuronusdk_adapter.9.mtk.so\", \"libneuron_adapter_mgvi.so\", };"
            )
        )
        assertTrue(
            "the fourth is the dispatch directory's libneuron_adapter.so, appended after the three",
            flat.contains("constexpr const char *kDispatchDirAdapter = \"libneuron_adapter.so\";")
        )
        val walk = functionBody("void walkAdapterCandidatesLocked(")
        val flatWalk = collapsed(walk)
        assertTrue(
            "the walk appends <dispatchDir>/libneuron_adapter.so after the named three",
            flatWalk.contains(
                "std::vector<std::string> names(std::begin(kAdapterCandidates), std::end(kAdapterCandidates)); " +
                    "names.push_back(dispatchDir + \"/\" + kDispatchDirAdapter);"
            )
        )
        assertTrue(
            "every candidate is opened RTLD_NOW | RTLD_NODELETE",
            liveLines(walk, "dlopen(name.c_str(), RTLD_NOW | RTLD_NODELETE)").size == 1 &&
                liveLines(walk, "dlopen(").size == 1
        )
        assertTrue(
            "every handle that loads is retained, and the last one is the winner - no break in the loop",
            liveLines(walk, "adapter.handles.push_back(h);").size == 1 &&
                liveLines(walk, "adapter.winner = h;").size == 1 &&
                liveLines(walk, "break").isEmpty()
        )
        assertTrue(
            "the version comes from the WINNER's Neuron_getVersion",
            liveLines(walk, "dlsym(adapter.winner, \"Neuron_getVersion\")").size == 1
        )
        assertTrue(
            "the walk happens once per process",
            liveLines(walk, "if (adapter.walked) return;").size == 1
        )
        val verdict = collapsed(functionBody("std::string verdictFor("))
        listOf(
            "if (winnerName.empty()) return \"adapter-missing\";",
            "if (base != kMeasuredAdapter) return \"adapter-\" + base;",
            "return \"driver-major-\" + std::to_string(gotMajor) + \"-want-\" + std::to_string(wantMajor);",
        ).forEach { assertTrue("the verdict table must carry: $it", verdict.contains(it)) }
        assertTrue(
            "the measured adapter is the first candidate",
            flat.contains("constexpr const char *kMeasuredAdapter = \"libneuronusdk_adapter.mtk.so\";")
        )
        assertTrue(
            "nativeInit re-judges the driver before any model is opened",
            functionBody("Java_com_whispereverywhere_npu_LiteRtAsrNative_nativeInit(").let { init ->
                val probe = init.indexOf("probeLocked(dispatchDir, wantMajor)")
                val open = init.indexOf("openModelLocked(")
                probe in 0 until open
            }
        )
    }

    /**
     * **The decoder's outputs are asserted by name AND position before any restore** (design §2.5,
     * "Output identity").
     *
     * The export's outputs are positional (`output_0..N`), and the encoder's eight cross-KV tensors
     * reach the decoder's inputs by export order alone. So each output is checked AT ITS POSITION
     * against the semantic name or its positional alias, with its exact dims - a swapped k/v pair has
     * different dims and cannot pass - through `LiteRtGetSignatureOutputName`. Inputs are bound by
     * NAME, because the f32 export lists them alphabetically and the compiled file in export order.
     */
    @Test
    fun theOutputsAreAssertedByNameAndPositionBeforeTheRestore() {
        assertTrue(
            "LiteRtGetSignatureOutputName is on the symbol list and read into the slot",
            liveLines(cpp, "X(LiteRtGetSignatureOutputName)").size == 1 &&
                liveLines(cpp, "rt.api.LiteRtGetSignatureOutputName(sig, i, &nm)").size == 1
        )
        val check = functionBody("std::string checkIoLocked(")
        assertTrue(
            "every output at position i must carry the semantic name or the positional alias",
            liveLines(check, "if (got != outs[i].name && got != outs[i].alias) {").size == 1 &&
                liveLines(check, "if (!matches(slot.outTypes[i], outs[i])) {").size == 1
        )
        assertTrue(
            "every input is found BY NAME",
            liveLines(check, "auto it = slot.inIndex.find(e.name);").size == 1
        )
        val census = collapsed(functionBody("std::string derivePairCensus("))
        listOf(
            "out.decOut.push_back({\"logits\", \"output_0\", kLiteRtElementTypeFloat32, {1, vocab, 1, 1}});",
            "out.decOut.push_back({\"k_cache_self_\" + n + \"_out\", \"output_\" + std::to_string(1 + 2 * i),",
            "out.decOut.push_back({\"v_cache_self_\" + n + \"_out\", \"output_\" + std::to_string(2 + 2 * i),",
            "out.encOut.push_back({\"k_cache_cross_\" + n, \"output_\" + std::to_string(2 * i),",
            "out.encOut.push_back({\"v_cache_cross_\" + n, \"output_\" + std::to_string(2 * i + 1),",
        ).forEach { assertTrue("the expected output order must carry: $it", census.contains(it)) }
        val init = functionBody("Java_com_whispereverywhere_npu_LiteRtAsrNative_nativeInit(")
        val ioDec = init.indexOf("checkIoLocked(g.dec, census.decIn, census.decOut)")
        val restore = init.indexOf("compileLocked(g.enc, &encMs)")
        assertTrue(
            "the census runs on both models before the first restore (census at $ioDec, restore at $restore)",
            init.contains("checkIoLocked(g.enc, census.encIn, census.encOut)") && ioDec in 0 until restore
        )
    }

    /**
     * **The shared buffers come from JOINED requirements, and the self-KV cache advances by the
     * strategy `nativeInit` names** (P1b review, finding 4).
     *
     * A cross-KV buffer is ONE buffer for the encoder's output and the decoder's input only if both
     * sides' requirements join with equal strides (the layout each compilation reads the bytes in); the
     * sizes may differ and the buffer takes the larger, the join's own rule. Each self-KV set plays both
     * roles only through the join of its `_in` / `_out` requirements.
     *
     * The advance is one switch with two arms, both kept until the device gate has timed them:
     * `kSelfKvRebind` swaps the two sets' roles - no byte moves, but it is NOT the free pointer swap the
     * code once claimed, because the v2.1.1 dispatch kernel re-registers every re-bound buffer at the
     * next run - and `kSelfKvCopy` keeps set 0 the input for good and copies the step's output back into
     * it under lock. The loop reaches either only through `advanceSelfKvLocked`, so the two cannot mix.
     */
    @Test
    fun theSharedBuffersAreJoinedAndTheSelfKvCacheAdvancesByTheStrategyInitNames() {
        val alloc = functionBody("std::string allocateLocked() {")
        assertTrue(
            "the cross-KV buffer is created from the join of the encoder output and decoder input",
            liveLines(alloc, "err = joinLocked(er, g.enc.outTypes[j], dr, g.dec.inTypes[di], name, &jr);").size == 1 &&
                liveLines(alloc, "g.encOut[j] = g.cross[j];").size == 1 &&
                liveLines(alloc, "g.decIn[di] = g.cross[j];").size == 1
        )
        assertTrue(
            "each self-KV pair is joined _in with _out and gets TWO buffers",
            liveLines(alloc, "joinLocked(ir, g.dec.inTypes[di], orq, g.dec.outTypes[dout], name + \"_in/_out\", &jr);")
                .size == 1 && liveLines(alloc, "for (int set = 0; set < 2 && err.empty(); ++set) {").size == 1
        )
        val join = functionBody("std::string joinLocked(")
        assertTrue(
            "a failed join or a stride mismatch refuses; the sizes may differ, and the joined size must cover " +
                "the larger side (the old equal-size refusal is gone)",
            liveLines(join, "LiteRtJoinTensorBufferRequirements(a, b, &j)").size == 1 &&
                liveLines(join, "strides differ").size == 1 &&
                liveLines(join, "sj < std::max(sa, sb)").size == 1 &&
                liveLines(join, "if (sa != sb)").isEmpty()
        )
        val bind = collapsed(functionBody("void bindSelfKvLocked(int inSet) {"))
        assertTrue(
            "the bind re-points the run arrays at the two sets",
            bind.contains("g.decIn[g.selfInIdx[j]] = g.selfKv[inSet][j];") &&
                bind.contains("g.decOut[g.selfOutIdx[j]] = g.selfKv[outSet][j];")
        )
        assertTrue(
            "the two strategies are the two literals nativeInit accepts",
            liveLines(cpp, "constexpr int kSelfKvRebind = 0;").size == 1 &&
                liveLines(cpp, "constexpr int kSelfKvCopy = 1;").size == 1
        )
        assertTrue(
            "the advance is the one switch: strategy 1 copies, strategy 0 re-binds",
            collapsed(functionBody("std::string advanceSelfKvLocked() {")).contains(
                "if (g.selfKvStrategy == kSelfKvCopy) return copySelfKvLocked(); bindSelfKvLocked(1 - g.selfInSet); " +
                    "return \"\";"
            )
        )
        val loop = functionBody("Java_com_whispereverywhere_npu_LiteRtAsrNative_nativeDecodeSegment(")
        assertEquals(
            "the loop advances the cache after the prompt steps and after every generated token",
            2,
            liveLines(loop, "err = advanceSelfKvLocked();").size
        )
        assertTrue(
            "and only through the advance: the loop neither re-binds nor copies by itself",
            liveLines(loop, "bindSelfKvLocked(").isEmpty() && liveLines(loop, "copySelfKvLocked(").isEmpty()
        )
        assertEquals(
            "the swap is written exactly once, inside the advance",
            1,
            liveLines(cpp, "bindSelfKvLocked(1 - g.selfInSet);").size
        )
        assertEquals(
            "the copy is defined once and called once - from the advance",
            2,
            liveLines(cpp, "copySelfKvLocked(").size
        )
        val copy = functionBody("std::string copySelfKvLocked() {")
        assertTrue(
            "strategy 1's copy reads set 1 (the outputs) and writes set 0 (the inputs), both under lock, the " +
                "packed bytes",
            liveLines(copy, "rt.api.LiteRtLockTensorBuffer(g.selfKv[1][j], &src, kLiteRtTensorBufferLockModeRead);")
                .size == 1 &&
                liveLines(copy, "rt.api.LiteRtLockTensorBuffer(g.selfKv[0][j], &dst, kLiteRtTensorBufferLockModeWrite);")
                    .size == 1 &&
                liveLines(copy, "memcpy(dst, src, g.selfKvBytes[j]);").size == 1
        )
        assertTrue(
            "no live line reads a self-KV set back to the host any other way",
            liveLines(cpp, "readLocked(g.selfKv").isEmpty()
        )
        assertTrue(
            "both sets are zeroed at every rung's start",
            liveLines(loop, "err = zeroSelfKvLocked();").size == 1
        )
        val init = functionBody("Java_com_whispereverywhere_npu_LiteRtAsrNative_nativeInit(")
        assertTrue(
            "the strategy is validated with the other scalars and stored on the session",
            liveLines(init, "if (err.empty() && selfKvStrategy != kSelfKvRebind && selfKvStrategy != kSelfKvCopy) {")
                .size == 1 && liveLines(init, "g.selfKvStrategy = selfKvStrategy;").size == 1
        )
        assertTrue(
            "and the Kotlin half declares it",
            liveLines(seam, "selfKvStrategy: Int,").size == 1
        )
    }

    /**
     * **Every buffer is made UNSTRIDED, of a type and size its requirements offer - never from the
     * requirements whole** (P1b review, finding 2).
     *
     * In 2.1.1 `LiteRtCreateManagedTensorBufferFromRequirements` copies the requirements' strides into
     * the buffer's layout whenever there are any; the MediaTek dispatch's requirements always carry them,
     * and the same dispatch refuses a strided buffer when it registers it ("Tensor strides are not
     * supported") - every one of the 27 dispatch-facing buffers would have been refused at the first run.
     * So that call is not even resolved, and the one maker switches strides off before
     * `LiteRtCreateManagedTensorBuffer`, the call the Kotlin API made on every tablet run. The dispatch's
     * buffers are AHWB before DMA-BUF; `input_ids` and `position_ids`, which only the CPU-side embedding
     * lookups read, are host memory - and the `buffers:` line prints what was made for them too.
     */
    @Test
    fun everyBufferIsMadeUnstridedFromItsRequirementsTypeAndSizeNeverFromTheRequirementsWhole() {
        assertTrue(
            "no live line may name LiteRtCreateManagedTensorBufferFromRequirements - not a call, not a symbol. " +
                "Found: " + liveLines(cpp, "LiteRtCreateManagedTensorBufferFromRequirements"),
            liveLines(cpp, "LiteRtCreateManagedTensorBufferFromRequirements").isEmpty()
        )
        val create = functionBody("std::string createBufferLocked(")
        assertTrue(
            "LiteRtCreateManagedTensorBuffer is resolved, and called exactly once - in createBufferLocked",
            liveLines(cpp, "X(LiteRtCreateManagedTensorBuffer)").size == 1 &&
                liveLines(cpp, "rt.api.LiteRtCreateManagedTensorBuffer(").size == 1 &&
                liveLines(create, "rt.api.LiteRtCreateManagedTensorBuffer(rt.env, bufferType, &unstrided, size, &b);")
                    .size == 1
        )
        assertTrue(
            "and no buffer wraps host memory by hand",
            liveLines(cpp, "LiteRtCreateTensorBufferFromHostMemory").isEmpty()
        )
        val noStrides = liveOffsets(create, "unstrided.layout.has_strides = false;")
        val call = liveOffsets(create, "rt.api.LiteRtCreateManagedTensorBuffer(")
        assertTrue(
            "the tensor type loses its strides BEFORE the create (no strides at $noStrides, create at $call)",
            noStrides.size == 1 && call.size == 1 && noStrides[0] < call[0]
        )
        assertTrue(
            "the size is the requirements' own - for a shared buffer, the join's",
            liveLines(create, "rt.api.LiteRtGetTensorBufferRequirementsBufferSize(req, &size)").size == 1
        )
        val pick = functionBody("std::string pickBufferType(")
        val ahwb = liveOffsets(pick, "if (ahwb) *out = kLiteRtTensorBufferTypeAhwb;")
        val dmaBuf = liveOffsets(pick, "else if (dmaBuf) *out = kLiteRtTensorBufferTypeDmaBuf;")
        assertTrue(
            "a DISPATCH_OP's buffer is AHWB before DMA-BUF, and nothing else",
            ahwb.size == 1 && dmaBuf.size == 1 && ahwb[0] < dmaBuf[0] &&
                liveLines(pick, "the only two the v2.1.1 MediaTek dispatch registers").size == 1
        )
        assertTrue(
            "the CPU's two take host memory when it is offered",
            liveLines(pick, "if (host) *out = kLiteRtTensorBufferTypeHostMemory;").size == 1
        )
        val alloc = functionBody("std::string allocateLocked() {")
        val flatAlloc = collapsed(alloc)
        assertTrue(
            "input_ids and position_ids - and only they - are the CPU's; the mask is the dispatch's",
            liveLines(alloc, "Consumer::Cpu").size == 2 &&
                flatAlloc.contains("{kInputIds, &g.inputIds, &g.decInputIdsIdx, Consumer::Cpu}") &&
                flatAlloc.contains("{kPositionIds, &g.positionIds, &g.decPositionIdsIdx, Consumer::Cpu}") &&
                flatAlloc.contains("{kAttentionMask, &g.mask, &g.decMaskIdx, Consumer::Dispatch}")
        )
        assertTrue(
            "the buffers: line prints what was made for input_ids and position_ids too",
            liveLines(alloc, "\"input_ids %s; position_ids %s\"").size == 1 &&
                flatAlloc.contains("madeDesc(g.inputIds, idsReq).c_str(), madeDesc(g.positionIds, posReq).c_str()")
        )
    }

    /**
     * **The float loop keeps the scale at 1.0, the floor at -infinity, and checks every step.**
     *
     * qnn_asr.cpp's `logitsScaleLocked` answers 0 for a tensor it cannot read, and 0 means "no
     * probability gate this segment". On a float engine there is nothing to read, and a 0 would
     * silently switch off p(nospeech), avg_logprob and the ladder - the 4.3.2 silence fix - on every
     * segment. The floor is the real -inf, written by the mask before the argmax (the C2 rule), and
     * a non-finite RAW logit fails the step with `-4` instead of being argmaxed.
     */
    @Test
    fun theFloatLoopKeepsTheScaleAtOneTheFloorAtMinusInfinityAndChecksEveryStep() {
        assertTrue(
            "constexpr float kLogitScale = 1.0f; with a static_assert that it is positive",
            liveLines(cpp, "constexpr float kLogitScale = 1.0f;").size == 1 &&
                liveLines(cpp, "static_assert(kLogitScale > 0.0f").size == 1
        )
        assertTrue(
            "the floor is -infinity",
            liveLines(cpp, "constexpr float kLogitFloor = -std::numeric_limits<float>::infinity();").size == 1
        )
        val argmax = functionBody("int32_t suppressThenArgmaxF(")
        val mask = liveOffsets(argmax, "logits[id] = kLogitFloor;")
        val scan = liveOffsets(argmax, "for (uint32_t i = 0; i < vocab; ++i) {")
        assertTrue(
            "the masks are written BEFORE the scan, in the same function",
            mask.size == 2 && scan.size == 1 && mask.max() < scan.first()
        )
        val loop = functionBody("Java_com_whispereverywhere_npu_LiteRtAsrNative_nativeDecodeSegment(")
        val step = liveOffsets(loop, "err = decodeStepLocked(tokenIn, position, &stepRunMs);")
        val finite = liveOffsets(loop, "err = checkFiniteLocked(position);")
        val nsp = liveOffsets(loop, "noSpeechProb = noSpeechProbabilityF(logits, g.vocab, noSpeechToken);")
        assertTrue(
            "every step is checked for non-finite logits right after it runs, before p(nospeech) reads them",
            step.size == 1 && finite.size == 1 && nsp.size == 1 && step[0] < finite[0] && finite[0] < nsp[0]
        )
        assertTrue("a non-finite step returns -4", collapsed(loop).contains("failure(\"decode: \" + err); return -4;"))
        assertTrue(
            "no gate is conditioned on a scale: the low-confidence rung and the average read no `scale >`",
            liveLines(loop, "scale > 0").isEmpty() && liveLines(loop, "scale <= 0").isEmpty()
        )
        assertTrue(
            "the timestamps stay out of avg_logprob, as in qnn_asr.cpp (4.11 fix round 2)",
            liveLines(loop, "if (tok < timestampBegin) {").size == 1
        )
        assertTrue(
            "the prompt walks the same step path and the window is 0..maskLen-2",
            liveLines(loop, "const uint32_t lastPosition = g.maskLen - 2;").size == 1 &&
                liveLines(loop, "const int32_t tokenIn = (position < promptLen) ? prompt[position] : next;").size == 1
        )
        val stepFn = functionBody("std::string decodeStepLocked(")
        assertTrue(
            "the mask opens the LAST position+1 columns (the right-aligned window)",
            liveLines(stepFn, "const uint32_t firstLive = (position < g.maskLen) ? (g.maskLen - 1 - position) : 0;")
                .size == 1 &&
                liveLines(stepFn, "g.maskHost[i] = (i >= firstLive) ? kMaskAttend : kMaskBlocked;").size == 1
        )
        assertTrue(
            "the logits are read under lock",
            liveLines(stepFn, "err = readLocked(g.logitsBuf, g.logits.data(), g.vocab * sizeof(float), \"logits\");")
                .size == 1
        )
    }

    /**
     * **The encoder is created on the NPU alone, the decoder on NPU | CPU, and init times the decoder's
     * first step** (P1b review, finding 1).
     *
     * LiteRT 2.1.1 refuses a model with any op outside every delegate unless CPU is in the set, and
     * the compiled decoder keeps its two embedding lookups and their guards on the CPU - with the NPU
     * alone for both, init would fail at the decoder after the encoder's ~8 s create (the Kotlin API
     * had added CPU to a lone NPU silently on every tablet run). The encoder is one DISPATCH_OP, so it
     * keeps the NPU alone and a refusal there stays an error. The APU check is the measured line: the
     * decoder's first step at position 0 on zeroed caches, refused over 250 ms with the text the
     * backend's loud fallback logs, after the buffers exist and before the session is armed.
     */
    @Test
    fun theEncoderIsOnTheNpuAloneTheDecoderOnNpuPlusCpuAndInitTimesTheDecodersFirstStep() {
        assertTrue(
            "the two accelerator sets",
            liveLines(cpp, "constexpr LiteRtHwAcceleratorSet kEncoderAccelerators = kLiteRtHwAcceleratorNpu;").size == 1 &&
                liveLines(
                    cpp,
                    "constexpr LiteRtHwAcceleratorSet kDecoderAccelerators = kLiteRtHwAcceleratorNpu | kLiteRtHwAcceleratorCpu;"
                ).size == 1
        )
        val init = functionBody("Java_com_whispereverywhere_npu_LiteRtAsrNative_nativeInit(")
        assertTrue(
            "the encoder's options take kEncoderAccelerators and the decoder's kDecoderAccelerators",
            liveLines(init, "err = buildOptionsLocked(g.enc, kEncoderAccelerators, performanceMode);").size == 1 &&
                liveLines(init, "if (err.empty()) err = buildOptionsLocked(g.dec, kDecoderAccelerators, performanceMode);")
                    .size == 1
        )
        val options = functionBody("std::string buildOptionsLocked(")
        assertTrue(
            "the set handed to LiteRT is the argument - no accelerator literal inside the builder",
            liveLines(options, "s = rt.api.LiteRtSetOptionsHardwareAccelerators(slot.options, accelerators);").size == 1 &&
                liveLines(options, "kLiteRtHwAccelerator").isEmpty()
        )
        assertTrue(
            "the init line names both sets",
            collapsed(init).contains("accelName(g.enc.accelerators).c_str()") &&
                collapsed(init).contains("accelName(g.dec.accelerators).c_str()")
        )
        assertTrue("the ceiling is 250 ms", liveLines(cpp, "constexpr double kApuStepCeilingMs = 250.0;").size == 1)
        val check = functionBody("std::string apuCheckLocked(")
        val zeroFirst = liveOffsets(check, "std::string err = zeroSelfKvLocked();")
        val stepAt = liveOffsets(check, "if (err.empty()) err = decodeStepLocked(kSotToken, 0, runMs);")
        val zeroAfter = liveOffsets(check, "if (err.empty()) err = zeroSelfKvLocked();")
        assertTrue(
            "the check zeroes the caches, runs one step at position 0, and zeroes the self-KV again",
            zeroFirst.size == 1 && stepAt.size == 1 && zeroAfter.size == 1 && zeroFirst[0] < stepAt[0] &&
                stepAt[0] < zeroAfter[0] &&
                liveLines(check, "writeLocked(g.cross[j], nullptr, tensorBytes(g.enc.outTypes[j]), \"cross-KV (zero)\")")
                    .size == 1
        )
        assertTrue(
            "a step over the ceiling is refused with the text the backend's fallback logs",
            liveLines(check, "if (*runMs > kApuStepCeilingMs) {").size == 1 &&
                liveLines(check, "\"decoder ran without the APU (step %.0f ms)\"").size == 1
        )
        val alloc = init.indexOf("err = allocateLocked();")
        val apu = init.indexOf("err = apuCheckLocked(&apuMs);")
        val armed = init.indexOf("g.initialised = true;")
        assertTrue(
            "the check runs after the buffers exist and before the session is armed (buffers at $alloc, " +
                "check at $apu, armed at $armed), and refuses through the releasing path",
            alloc in 0 until apu && apu < armed &&
                collapsed(init).contains("err = apuCheckLocked(&apuMs); if (!err.empty()) return refuse(err);")
        )
    }

    /**
     * **The performance mode is passed through and SAID TO BE INERT; no run line claims it** (P1b
     * review, finding 3).
     *
     * On the v2.1.1 runtime with AOT files the MediaTek dispatch never reads the mode - the adapter
     * loader reads only the SDK version type, and the bytecode load hard-codes NEURON_PRIORITY_HIGH,
     * NEURON_PREFER_SUSTAINED_SPEED and a boost hint of 100. The argument stays (P2 may pin a value for
     * a runtime that reads it), still through LiteRT's own setter, but both init lines mark it inert and
     * neither the encode nor the decode line may print it as the mode the APU ran in.
     */
    @Test
    fun thePerformanceModeIsPassedThroughAndMarkedInertAndNoRunLineClaimsIt() {
        val init = functionBody("Java_com_whispereverywhere_npu_LiteRtAsrNative_nativeInit(")
        assertEquals(
            "both init lines print perfmode=N (inert on LiteRT 2.1.1 AOT)",
            2,
            liveLines(init, "perfmode=%d (inert on LiteRT 2.1.1 AOT)").size
        )
        listOf(
            "Java_com_whispereverywhere_npu_LiteRtAsrNative_nativeEncode(",
            "Java_com_whispereverywhere_npu_LiteRtAsrNative_nativeDecodeSegment(",
        ).forEach { anchor ->
            val body = functionBody(anchor)
            listOf("performanceMode", "perfMode", "performance mode", "perfmode").forEach {
                assertTrue(
                    "$anchor must not print a performance mode - the dispatch never ran in one: `$it` on " +
                        liveLines(body, it),
                    liveLines(body, it).isEmpty()
                )
            }
        }
        assertTrue(
            "the value still goes through LiteRT's own setter, so a runtime that reads it gets it",
            liveLines(functionBody("std::string buildOptionsLocked("), "rt.api.LiteRtMediatekOptionsSetPerformanceMode(")
                .size == 1
        )
        assertTrue(
            "the Kotlin KDoc says it is inert, on the object and on the parameter",
            seam.contains("**The performance mode — INERT on LiteRT 2.1.1.**") &&
                seam.contains("never read by the dispatch")
        )
    }

    /**
     * **The adapter directory, the driver and the environment directory are judged BEFORE a live
     * session is released** (P1b review, nit c).
     *
     * They are the process's state and need none of the new files, so a caller naming the wrong
     * dispatch directory, or a driver the family refuses, must cost an error string - never the
     * working session. Before the release nothing may call the releasing `refuse`.
     */
    @Test
    fun theAdapterDirTheDriverAndTheEnvironmentDirAreJudgedBeforeALiveSessionIsReleased() {
        val init = functionBody("Java_com_whispereverywhere_npu_LiteRtAsrNative_nativeInit(")
        val release = init.indexOf("if (g.initialised) {")
        assertTrue("nativeInit releases a live session behind `if (g.initialised) {`", release > 0)
        listOf(
            "if (adapter.walked && adapter.dispatchDir != dispatchDir) {",
            "const std::string reason = probeLocked(dispatchDir, wantMajor);",
            "if (err.empty()) err = loadRuntimeLocked(libDir);",
            "if (err.empty()) err = ensureEnvironmentLocked(dispatchDir);",
        ).forEach {
            val at = init.indexOf(it)
            assertTrue("`$it` must come before the release (at $release); found at $at", at in 0 until release)
        }
        assertTrue(
            "and a refusal there releases nothing: no live `refuse(` before the release",
            liveLines(init.substring(0, release), "refuse(").isEmpty()
        )
    }

    /**
     * **The `steptime` line is bounded: the first four steps of a segment and its last** (P1b review,
     * nit d) - qnn_asr.cpp's four-lines-per-segment rule, so a 124-token segment writes five lines,
     * not 124.
     */
    @Test
    fun theSteptimeLineIsBoundedToTheFirstFourStepsOfASegmentAndItsLast() {
        assertTrue(
            "constexpr uint32_t kStepTimeLines = 4;",
            liveLines(cpp, "constexpr uint32_t kStepTimeLines = 4;").size == 1
        )
        val loop = functionBody("Java_com_whispereverywhere_npu_LiteRtAsrNative_nativeDecodeSegment(")
        assertTrue(
            "the in-loop line is gated on the first rung's first kStepTimeLines positions",
            liveLines(loop, "if (g.diag && rung == 0 && position < kStepTimeLines) {").size == 1
        )
        assertTrue(
            "the segment's last step gets the one line after the ladder, unless the loop already logged it",
            liveLines(loop, "if (g.diag && stepsRun > 0 && (rungUsed > 0 || lastStep.position >= kStepTimeLines)) {")
                .size == 1
        )
        assertEquals(
            "two steptime lines in all: the bounded one in the loop, the segment's last after it",
            2,
            liveLines(loop, "npu-debug: steptime").size
        )
    }

    /**
     * **The chip is read from the file's own LiteRtStamp, before LiteRT opens the file** (design §2.3,
     * rule 4): vendor `MediaTek`, SoC equal to the family's `socStamp`, the two NUL-padded 125-byte
     * fields measured on both of the pair's files.
     */
    @Test
    fun theChipIsReadFromTheFilesOwnStampBeforeLiteRtOpensIt() {
        assertTrue(
            "the stamp's key and geometry live in the Android-free parser, the vendor beside the check",
            liveLines(stamp, "constexpr const char *kStampKey = \"LiteRtStamp\";").size == 1 &&
                liveLines(stamp, "constexpr size_t kStampFieldBytes = 125;").size == 1 &&
                liveLines(stamp, "constexpr size_t kStampBytes = 2 * kStampFieldBytes;").size == 1 &&
                liveLines(cpp, "constexpr const char *kStampVendor = \"MediaTek\";").size == 1
        )
        val init = functionBody("Java_com_whispereverywhere_npu_LiteRtAsrNative_nativeInit(")
        val read = init.indexOf("err = readLiteRtStamp(*path, &vendor, &soc);")
        val open = init.indexOf("openModelLocked(g.enc, encoderPath, kEncodeSignature)")
        assertTrue("the stamp is read before either model is opened", read in 0 until open)
        assertTrue(
            "and both halves are compared",
            liveLines(init, "if (vendor != kStampVendor || soc != socStamp) {").size == 1
        )
        val reader = functionBody("std::string readLiteRtStamp(")
        assertTrue(
            "the reader maps the file read-only and hands it to litert_stamp.h; LiteRT is not involved",
            liveLines(reader, "mmap(nullptr").size == 1 &&
                liveLines(reader, "litert_stamp::parseLiteRtStamp(").size == 1 &&
                liveLines(reader, "rt.api.").isEmpty() &&
                liveLines(cpp, "#include \"litert_stamp.h\"").size == 1
        )
        listOf("<jni.h>", "<android/", "litert/c/", "__android_log_print", "rt.", "g.").forEach {
            assertTrue(
                "litert_stamp.h must stay Android- and LiteRT-free - `$it` on a live line breaks the host " +
                    "check (tools/mtk-apu/litert_stamp_check.cpp) that is its only test: " + liveLines(stamp, it),
                liveLines(stamp, it).isEmpty()
            )
        }
    }

    /**
     * **The literals the native side keeps for itself equal the Kotlin they mirror**: the three
     * universal shape factors against `NpuModelSpec.TURBO`, the six stats slots and four terminator
     * codes against [NpuDecodeStats], the entropy window against [NpuDecodePolicy], and the EOT
     * against [WhisperTokens] - the same pins `NpuNativeContractTest` holds for qnn_asr.cpp, because
     * a slot that moves on one side only is the no-speech gate reading a rung index.
     */
    @Test
    fun theNativeLiteralsEqualTheKotlinTheyMirror() {
        listOf(
            "constexpr uint32_t kHeadDim = ${NpuModelSpec.TURBO.headDim};",
            "constexpr uint32_t kAudioCtx = ${NpuModelSpec.TURBO.audioCtx};",
            "constexpr uint32_t kMelFrames = ${NpuModelSpec.TURBO.melFrames};",
            "constexpr int kStatNoSpeechProb = ${NpuDecodeStats.NO_SPEECH_PROB};",
            "constexpr int kStatAvgLogprob = ${NpuDecodeStats.AVG_LOGPROB};",
            "constexpr int kStatEntropy = ${NpuDecodeStats.ENTROPY};",
            "constexpr int kStatRung = ${NpuDecodeStats.RUNG};",
            "constexpr int kStatTerminator = ${NpuDecodeStats.TERMINATOR};",
            "constexpr int kStatSteps = ${NpuDecodeStats.STEPS};",
            "constexpr int kStatSize = ${NpuDecodeStats.SIZE};",
            "constexpr float kTermEot = ${NpuDecodeStats.TERM_EOT.toInt()}.0f;",
            "constexpr float kTermBudget = ${NpuDecodeStats.TERM_BUDGET.toInt()}.0f;",
            "constexpr float kTermCap = ${NpuDecodeStats.TERM_CAP.toInt()}.0f;",
            "constexpr float kTermCut = ${NpuDecodeStats.TERM_CUT.toInt()}.0f;",
            "constexpr int32_t kEntropyWindow = ${NpuDecodePolicy.ENTROPY_WINDOW};",
            "constexpr float kStatUnreadable = -1.0f;",
            "constexpr int32_t kEotToken = ${WhisperTokens.EOT};",
        ).forEach { line ->
            assertEquals("litert_asr.cpp must declare exactly: $line", 1, liveLines(cpp, line).size)
        }
        assertTrue(
            "the band scan is band_scan.h's float twin, with -infinity as its floor",
            liveLines(cpp, "#include \"band_scan.h\"").size == 1 &&
                liveLines(bandScan, "inline BandTop2F scanBandTop2(const float *logits, uint32_t lo, uint32_t hi, float floor)")
                    .size == 1 &&
                collapsed(cpp).contains("static_cast<uint32_t>(g.langTokenLast) + 1, kLogitFloor);")
        )
    }

    /**
     * **The five pinned sources are inputs of the test task.** A pin over a file the task does not
     * list is a pin that stops re-running the day an edit is confined to that file.
     */
    @Test
    fun thePinnedSourcesAreInputsOfTheTestTask() {
        listOf(
            "\"src/main/cpp/litert_asr.cpp\",",
            "\"src/main/cpp/litert_stamp.h\",",
            "\"src/main/cpp/band_scan.h\",",
            "\"src/main/cpp/CMakeLists.txt\",",
            "\"src/main/java/com/whispereverywhere/npu/LiteRtAsrNative.kt\",",
        ).forEach {
            assertTrue("app/build.gradle.kts must list $it among sourcePinnedInputs", liveLines(gradle, it).size == 1)
        }
    }
}
