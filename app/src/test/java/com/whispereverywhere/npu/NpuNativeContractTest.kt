package com.whispereverywhere.npu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-level contract guards for the NPU seam: `qnn_asr.cpp`, the FastRPC manifest declarations,
 * and the .gitignore entry that keeps Qualcomm's proprietary headers out of the repo.
 *
 * WHY A TEST THAT READS C++, XML AND .gitignore: `QnnAsrNative` carries
 * `init { System.loadLibrary("qnnasr") }`, so ANY JVM test that touched the object would die with
 * `UnsatisfiedLinkError` — there is no `libqnnasr.so` on the unit-test classpath and there never
 * will be. Native behaviour is device-verified at Q10a and nowhere earlier. That leaves four
 * constraints that each cost a real device round trip (or, for the header entry, would cost a
 * licence violation) with no automated guard at all between now and then. These assertions pin the
 * constructs themselves, so a regression fails here in seconds instead of on-device in a month.
 *
 * Each assertion is anchored to CONTENT, never to a line number, and every positive pin is scoped
 * to LIVE lines: a commented-out `// systemContextFree(...)` satisfies `contains()` exactly as
 * happily as the call, which is the false-green shape already proved twice on
 * `NativeVadSourceContractTest`.
 *
 * `src/main/cpp/qnn_asr.cpp`, `src/main/AndroidManifest.xml`, the root `.gitignore` and (4.0 Q6)
 * `NpuWhisperBackend.kt` are declared as explicit inputs of the test task in `app/build.gradle.kts`
 * — without that, an edit confined to any of them leaves `:app:testDebugUnitTest` UP-TO-DATE and
 * these guards pass against stale evidence. The Kotlin file is in that list for a narrower reason
 * than the others: its residency pin is a NEGATIVE assertion over the whole file INCLUDING
 * comments, and a comment-only edit produces identical bytecode, so without the entry the one
 * mutation the pin exists to catch is the one that never re-runs it.
 */
class NpuNativeContractTest {

    /**
     * Reads a repo file from the test's working directory — the locator `SegmentTimingTest`,
     * `NativeVadSourceContractTest` and `CaptureThreadPolicyTest` share. Line endings are
     * normalized at this single read site (the 3.7 N1 lesson: `readText()` does not normalize, and
     * a CRLF checkout silently defeats anything anchored on a newline — `qnn_asr.cpp` and the
     * manifest are both CRLF here).
     */
    private fun source(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            for (candidate in listOf(File(dir, relative), File(dir, "app/$relative"))) {
                if (candidate.isFile) return candidate.readText().replace("\r\n", "\n")
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "cannot locate $relative from ${System.getProperty("user.dir")}"
        )
    }

    /**
     * Character offsets of every LIVE (non-comment) line of [scope] containing [needle]. Ordering
     * assertions must never be built on `indexOf`: it measures a commented-out mention exactly as
     * happily as the code, so a comment that drifts above the line it describes silently satisfies
     * "X comes first".
     */
    private fun liveOffsets(scope: String, needle: String): List<Int> {
        val out = mutableListOf<Int>()
        var at = 0
        for (line in scope.split("\n")) {
            val trimmed = line.trimStart()
            val commented =
                trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*")
            if (!commented && line.contains(needle)) out += at
            at += line.length + 1
        }
        return out
    }

    /** The LIVE (non-comment) lines of [scope] containing [needle], trimmed. */
    private fun liveLines(scope: String, needle: String): List<String> =
        scope.split("\n").map { it.trimStart() }.filter { line ->
            !(line.startsWith("//") || line.startsWith("/*") || line.startsWith("*")) &&
                line.contains(needle)
        }

    /** One free function's body: every function in `qnn_asr.cpp` closes at column 0. */
    private fun functionBody(cpp: String, anchor: String): String {
        val start = cpp.indexOf(anchor)
        assertTrue(
            "anchor \"$anchor\" is missing from qnn_asr.cpp. indexOf() returns -1 when the anchor " +
                "is absent, so substring(start) would silently rebase the scope to the top of the " +
                "file and every claim below would be answered by unrelated code instead of failing.",
            start >= 0
        )
        val body = cpp.substring(start)
        assertTrue(
            "no column-0 \"\\n}\\n\" follows \"$anchor\". substringBefore() returns its RECEIVER " +
                "when the delimiter is absent, so a re-indented closing brace would silently widen " +
                "the scope into the FOLLOWING function and the assertions would pass on a " +
                "neighbour's code.",
            body.contains("\n}\n")
        )
        return body.substringBefore("\n}\n")
    }

    /**
     * One Kotlin member's body, bounded by the anchor's own INDENT rather than by a fixed
     * four-space closing brace (4.1 L1, folding Q6 M3).
     *
     * [functionBody]'s column-0 rule does not apply to class members, and the `"\n    }\n"` this
     * used to cut on was a rule about the CLASS, not about the member. Two consequences, both
     * silent:
     *
     *  - **a companion member's scope ran to the end of the companion.** `isTierAvailable` is eight
     *    spaces in, so the first four-space `}` after it is the COMPANION's closing brace. That
     *    made the anti-widening guard below *vacuous* for it: the assertion could only have failed
     *    if the companion had no closing brace at all. It passed because `isTierAvailable` happened
     *    to be the last member — and adding one after it would have widened the gate-before-probe
     *    pin onto a neighbour's code with nothing to say so. (There is now a member after it, so
     *    the fix is exercised rather than asserted.)
     *  - **an expression-bodied member had no terminator of its own at all.** `load(modelPath,
     *    companionPath)` is `= NativeComputeGate.serialized { … }`; under the old rule its "body"
     *    ran through the whole of `transcribe`.
     *
     * The rule instead: record the indent of the anchor's line, and end the body at the first
     * following NON-BLANK line indented no further than that. That is the member's own closing
     * brace when it has one, the enclosing block's when it does not, and the next member's first
     * line as soon as one exists. Both loud failures are kept, for the two reasons the originals
     * name.
     */
    private fun kotlinMemberBody(kt: String, anchor: String): String {
        val start = kt.indexOf(anchor)
        assertTrue(
            "anchor \"$anchor\" is missing from NpuWhisperBackend.kt. indexOf() returns -1 when " +
                "the anchor is absent, so substring(start) would silently rebase the scope to the " +
                "top of the file and every claim below would be answered by unrelated code " +
                "instead of failing.",
            start >= 0
        )
        val lineStart = kt.lastIndexOf('\n', start - 1) + 1
        val indent = kt.substring(lineStart, start).takeWhile { it == ' ' }.length
        val lines = kt.substring(start).split("\n")
        val body = StringBuilder(lines.first())
        var closed = false
        for (line in lines.drop(1)) {
            if (line.isNotBlank() && line.takeWhile { it == ' ' }.length <= indent) {
                closed = true
                break
            }
            body.append("\n").append(line)
        }
        assertTrue(
            "nothing at or left of \"$anchor\"'s own indent ($indent) follows it. Without a " +
                "terminator the scope runs to the end of the FILE, and every assertion below would " +
                "be answered by unrelated code — the same failure the old fixed-delimiter form had " +
                "when substringBefore() returned its whole receiver.",
            closed
        )
        return body.toString()
    }

    private val cpp: String by lazy { source("src/main/cpp/qnn_asr.cpp") }

    private val backend: String by lazy {
        source("src/main/java/com/whispereverywhere/transcription/NpuWhisperBackend.kt")
    }

    /**
     * The Kotlin half of the JNI seam, read as SOURCE for exactly the reason `NpuWhisperBackend` is:
     * `QnnAsrNative`'s `init` block runs `System.loadLibrary("qnnasr")`, so a test that *named* the
     * object would not fail on this classpath, it would die. The declarations are the only place
     * the Kotlin and native sides of `nativeRelease(epoch)` / `nativeEpoch()` can be compared at
     * all before a device runs them (4.1 L1).
     */
    private val seam: String by lazy {
        source("src/main/java/com/whispereverywhere/npu/QnnAsrNative.kt")
    }

    /** Where the FastRPC search path is built — one site, per Q1's review (4.0, Q8). */
    private val app: String by lazy {
        source("src/main/java/com/whispereverywhere/WhisperEverywhereApp.kt")
    }

    /**
     * THE FASTRPC SEARCH PATH LEADS WITH THE APP'S FILES DIRECTORY (4.0, Q8).
     *
     * This is the app-side half of Q1's open concern for Q10a, and it is a *packaging* fact wearing
     * a one-line disguise. `libQnnHtp.so` reaches the DSP by loading `libQnnHtpV75Skel.so` through
     * the FastRPC loader, which searches `ADSP_LIBRARY_PATH` **and nothing else**, and which needs a
     * REAL FILE on disk. This app is `extractNativeLibs="false"`, so `nativeLibraryDir` contains no
     * such file and the bundled skel is unreachable — see the comment at the `libQnnHtp.so` dlopen
     * in `qnn_asr.cpp`, which says the same thing from the other side of the seam.
     *
     * With the files directory first, both remaining answers work with no further code change: the
     * owner adding the skel to the published zip, or an `adb push` into `files/` during the Q10a
     * session. Without it, neither does — and the failure is **silent**: the dlopen succeeds and the
     * HTP simply never comes up.
     *
     * Measured as a survivor first. Battery row X19 removed the prepend and all 1,489 tests stayed
     * green, which is exactly the shape of hole this class exists to close.
     */
    @Test
    fun theFastRpcSearchPathLeadsWithTheAppsFilesDirectory() {
        assertTrue(
            "ADSP_LIBRARY_PATH is still built at exactly one site. Found: " +
                liveLines(app, "\"ADSP_LIBRARY_PATH\","),
            liveLines(app, "\"ADSP_LIBRARY_PATH\",").size == 1,
        )
        assertTrue(
            "the app's FILES directory is the first entry. It is the only directory on the list " +
                "this app can write to, so it is the only one an imported or pushed skel can ever " +
                "live in; anything ahead of it would shadow the one that can be fixed. Found: " +
                liveLines(app, "filesDir.absolutePath"),
            liveOffsets(app, "filesDir.absolutePath +").isNotEmpty(),
        )
        assertTrue(
            "and the app's native library dir still follows it rather than being replaced — the " +
                "bundled skel is unreachable under extractNativeLibs=false TODAY, and that is a " +
                "packaging decision that may yet be flipped",
            liveOffsets(app, "filesDir.absolutePath +").first() <
                liveOffsets(app, "\";\" + nativeLibDir +").first(),
        )
        assertTrue(
            "the stock vendor locations still come last, so a device that exposes its own HTP " +
                "skels keeps working",
            liveOffsets(app, "\";\" + nativeLibDir +").first() <
                liveOffsets(app, "\";/vendor/lib/rfsa/adsp\" +").first(),
        )
    }

    /**
     * THE RESIDENCY CONTRACT — the one invariant in this tier that has only ever been protected by
     * prose, and the one whose violation is byte-identical to correct behaviour.
     *
     * Three claims, each defending a different edit, and each of them an edit that compiles, runs,
     * and produces exactly the right transcript on a development device:
     *
     *  1. **`initMelOnly`, never the full loader.** The mel filterbank is model data, so the NPU
     *     tier needs a `whisper_context` for it. `initMelOnly` reads the contiguous
     *     magic -> hparams -> filterbank prefix — 64,320 B — and stops before a single tensor. The
     *     full loader returns a handle `pcmToMel` accepts just as happily and produces a
     *     **byte-identical mel**, while holding 60-190 MB of weights resident beside the NPU's own
     *     376 MiB. Nothing downstream can see the difference. The first symptom is an LMK kill on a
     *     mid-range device, months later, in a crash report with no stack. Measured at Q2b: the
     *     swap passed the entire suite green.
     *  2. **teardown BEFORE the CPU tier is loaded.** Presence is not the invariant here; ORDER is —
     *     the third time this branch has learned that (Q3's deleted guard call, Q4's swapped call
     *     site, Q4's hoisted flag write). Both statements are present either way round; the wrong
     *     order loads a 190 MB whisper model while 376 MiB of NPU contexts and a sustained power
     *     vote are still held, a ~570 MB+ transient on the one path that exists to be safe.
     *  3. **the SoC gate before the probe.** Also order. `nativeProbe` dlopens two Qualcomm
     *     backends and answers "is the HTP stack here", which a 7-series Snapdragon also answers
     *     yes to; only `NpuGate` can tell one Hexagon from another. Swapping the operands of the
     *     `&&` makes every device on earth dlopen a vendor backend to reach a foregone answer.
     *
     * Source-anchored because it can be nothing else: `NpuWhisperBackend` touches `QnnAsrNative`,
     * whose `init` block runs `System.loadLibrary("qnnasr")`, so naming the class from any JVM test
     * kills that test outright.
     */
    @Test
    fun theNpuBackendsResidencyAndTeardownOrderingArePinnedInSource() {
        assertTrue(
            "NpuWhisperBackend must obtain its mel context through WhisperNative.initMelOnly on a " +
                "live line — that is the 64 KB loader, and it is the only one this tier may use.",
            liveOffsets(backend, "WhisperNative.initMelOnly(").isNotEmpty()
        )
        assertTrue(
            "WhisperNative.init( must appear NOWHERE in NpuWhisperBackend.kt — not in code, not " +
                "in a comment. It is whisper.cpp's FULL model loader: it returns a handle pcmToMel " +
                "accepts, yields a byte-identical mel, and silently restores 60-190 MB of resident " +
                "weights beside the NPU's 376 MiB. There is no downstream check that can catch it " +
                "and no test that can observe it; this line is the whole defence. Found: " +
                backend.lineSequence().filter { it.contains("WhisperNative.init(") }.toList(),
            !backend.contains("WhisperNative.init(")
        )
        assertTrue(
            "the mel context must be FREED, on a live line — 64 KB held for the life of the " +
                "process is small, but the handle is also what makes a re-arm safe",
            liveOffsets(backend, "WhisperNative.free(").isNotEmpty()
        )

        val fallback = kotlinMemberBody(
            backend, "private fun fallBackToCpuTier(stage: String, detail: String): Long {"
        )
        val teardown = liveOffsets(fallback, "releaseNpuResources()")
        val cpuLoad = liveOffsets(fallback, "WhisperNativeBackend.load(")
        assertTrue(
            "fallBackToCpuTier must CALL releaseNpuResources() on a live line. Presence is " +
                "asserted separately from ordering because \"A precedes B\" is trivially true when " +
                "there is no A.",
            teardown.isNotEmpty()
        )
        assertTrue(
            "fallBackToCpuTier must load the CPU tier on a live line — a fallback that releases " +
                "the NPU and brings nothing up is not a fallback",
            cpuLoad.isNotEmpty()
        )
        assertTrue(
            "releaseNpuResources() (${teardown.first()}) must run BEFORE WhisperNativeBackend.load " +
                "(${cpuLoad.first()}). Swapping two adjacent statements compiles, keeps both " +
                "present, and puts a 190 MB whisper model beside 376 MiB of still-held NPU " +
                "contexts — ~570 MB+ transient, on the exact path whose purpose is to be safe.",
            teardown.first() < cpuLoad.first()
        )
        val release = kotlinMemberBody(backend, "private fun releaseEverything() {")
        val npuSide = liveOffsets(release, "releaseNpuResources()")
        // The CPU release moved from `fallbackBackend?.let { … }` to a local, so the guard can be
        // cleared BEFORE the handle is freed (the publication order I2 added, pinned separately in
        // theFallbackFunnelIsAtMostOnce…). The needle follows the statement; the invariant this
        // assertion carries — NPU side first — is unchanged.
        val cpuSide = liveOffsets(release, "previous?.release(fallbackCtx)")
        assertTrue("releaseEverything must free the NPU side on a live line", npuSide.isNotEmpty())
        assertTrue(
            "releaseEverything must release the CPU tier it fell back to, on a live line",
            cpuSide.isNotEmpty()
        )
        assertTrue(
            "the NPU side (${npuSide.first()}) must be freed before the CPU tier (" +
                "${cpuSide.first()}), for the same reason and in the same direction as the " +
                "fallback path: whichever way round it happened, two model-sized things resident " +
                "at once is the thing this tier's whole design avoids.",
            npuSide.first() < cpuSide.first()
        )

        val available = kotlinMemberBody(
            backend,
            "fun isTierAvailable(socModel: String?, socManufacturer: String?, libDir: String): Boolean ="
        )
        val gate = liveOffsets(available, "NpuGate.isSocSupported(")
        val probe = liveOffsets(available, "QnnAsrNative.nativeProbe(")
        assertTrue("tier visibility must consult NpuGate on a live line", gate.isNotEmpty())
        assertTrue("tier visibility must consult nativeProbe on a live line", probe.isNotEmpty())
        assertTrue(
            "NpuGate.isSocSupported (${gate.first()}) must be the LEFT operand, evaluated before " +
                "nativeProbe (${probe.first()}). The && short circuit is the mechanism: reversed, " +
                "every Tensor, Exynos and MediaTek device dlopens libQnnHtp.so to be told no, and " +
                "a 7-series Snapdragon is told yes by a probe that cannot tell one Hexagon from " +
                "another.",
            gate.first() < probe.first()
        )
    }

    /**
     * Lesson 3 — the one that cost a device round trip on the spike's run 6. `QNN_TENSOR_VERSION_1`
     * and `QNN_TENSOR_VERSION_2` are ENUM CONSTANTS, not macros, so `#ifdef QNN_TENSOR_VERSION_2`
     * is ALWAYS false: the whole v2 branch compiles out while the error text still claims the
     * reader knows 1 and 2. The asset's tensors are v2, so the spike rejected a version it
     * advertised as supported — and it did so at load time, on device, with no build-time signal
     * whatsoever.
     */
    @Test
    fun tensorVersionKnownReadsTheEnumeratorsDirectlyNeverThroughIfdef() {
        val body = functionBody(cpp, "bool tensorVersionKnown(")
        assertTrue(
            "tensorVersionKnown must accept QNN_TENSOR_VERSION_2 on a LIVE line — the shipped " +
                "asset's tensors ARE v2 (both context binaries report tensor v2), so a reader that " +
                "silently drops the v2 branch rejects every tensor in the model.",
            liveOffsets(body, "QNN_TENSOR_VERSION_2").isNotEmpty()
        )
        assertTrue(
            "tensorVersionKnown must accept QNN_TENSOR_VERSION_1 on a LIVE line too.",
            liveOffsets(body, "QNN_TENSOR_VERSION_1").isNotEmpty()
        )
        assertTrue(
            "tensorVersionKnown must contain NO preprocessor conditional at all. The v2 branch was " +
                "wrapped in `#ifdef QNN_TENSOR_VERSION_2` on the spike; because those names are " +
                "enumerators the guard was always false and the branch was silently compiled out. " +
                "Found: " + body.lineSequence().filter { it.trimStart().startsWith("#") }.toList(),
            body.lineSequence().none { it.trimStart().startsWith("#") }
        )
        // LIVE lines only, and this one is the exception that proves the rule. Everywhere else in
        // this file a NEGATIVE assertion uses plain contains(), because that is the stricter
        // reading: even a comment mentioning the forbidden construct is usually a claim with no
        // business being there. Here it is the opposite — the ported lesson comment above
        // tensorVersionKnown QUOTES `#ifdef QNN_TENSOR_VERSION_2` in order to explain why it must
        // never be written, and that prose is the most valuable line in the helper block. Demanding
        // the file never contain those characters would force the explanation to be deleted to
        // satisfy a guard about the thing it explains. Verified, not assumed: the whole-file form
        // failed on exactly that comment the first time this test ran green-path.
        val forbidden = Regex("""#\s*(ifdef|ifndef|if\s+!?defined)\s*\(?\s*QNN_TENSOR_VERSION""")
        val offenders = cpp.lineSequence().filter { line ->
            val trimmed = line.trimStart()
            val commented =
                trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*")
            !commented && forbidden.containsMatchIn(line)
        }.toList()
        assertTrue(
            "no LIVE #ifdef / #ifndef / #if defined may probe a QNN_TENSOR_VERSION_* enumerator " +
                "ANYWHERE in qnn_asr.cpp — the accessors below tensorVersionKnown (tensorRank, " +
                "tensorDims, tensorName, tensorDataType, tensorRepoint, tensorSetClientBuf) each " +
                "carry the same v1/v2 fork and would fail exactly the same silent way. Found: " +
                offenders,
            offenders.isEmpty()
        )
    }

    /**
     * Lesson 2 — the run-6 crash itself. `Qnn_Tensor_t` is not self-contained: `name` and
     * `dimensions` are POINTERS into storage owned by the system context. The spike shallow-copied
     * the descriptors, called `systemContextFree()` immediately, and then dereferenced
     * `dimensions` while binding buffers: a use-after-free, right after `graphRetrieve`.
     *
     * Two independent belts, and this pins both: every kept pointer is repointed at storage WE own
     * (`tensorRepoint`), AND the system context outlives the copy — the only `systemContextFree`
     * in the file sits in teardown, textually after the repoint.
     */
    @Test
    fun theSystemContextIsFreedOnlyAfterEveryDescriptorHasBeenDeepCopied() {
        val repoint = liveOffsets(cpp, "tensorRepoint(")
        assertTrue(
            "qnn_asr.cpp must both DEFINE and CALL tensorRepoint on live lines (found " +
                "${repoint.size} live mentions, expected at least 2: the definition plus at least " +
                "one call site). Without a call, nothing owns the names and dimension arrays the " +
                "descriptors point at, and the pointers dangle into the system context's storage " +
                "the moment it is freed.",
            repoint.size >= 2
        )
        val free = liveOffsets(cpp, "systemContextFree(")
        assertTrue(
            "qnn_asr.cpp must call systemContextFree on exactly one LIVE line — teardown. Found " +
                "${free.size}. A second call site is how the run-6 use-after-free came back: an " +
                "early free on an error path looks harmless and frees the storage every retained " +
                "descriptor points into.",
            free.size == 1
        )
        assertTrue(
            "systemContextFree must appear AFTER the last tensorRepoint site (free at " +
                "${free.first()}, last repoint at ${repoint.last()}). Both offsets come from LIVE " +
                "lines, never indexOf: the file's own prose names systemContextFree several times " +
                "while explaining why it must not run early, and a raw search would measure the " +
                "comment and report the ordering of code that does not exist.",
            free.first() > repoint.last()
        )
    }

    /**
     * C7 — the cross-KV alias guard, and the bind that consumes its proof.
     *
     * The whole zero-copy design rests on one claim: the encoder's 24 cross-KV output buffers can
     * be handed to the decoder as its cross-KV inputs untouched, because the two sides describe the
     * same tensor. If a future asset re-export shifts one side's scale, the decoder reads them
     * under the wrong affine transform — not a crash, not garbage, **plausible wrong text**, which
     * is the worst failure a dictation app has and the one a single-sentence acceptance run is
     * least likely to catch.
     *
     * MEASURED, NOT ASSUMED: at Q3 a mutant that deleted the whole `aliasGuardLocked()` call from
     * `nativeInit` compiled green and left the suite at 128/1379/0. This test is what makes that
     * mutation fail, and it asserts THREE things because each defeats a different edit:
     *  - **presence** — an ordering-only pin is vacuously satisfied when site A is deleted;
     *  - **order** — the guard must run before the bind, not after the first transcript;
     *  - **the population check** — the loop verifies the 24 pairs it knows about and can say
     *    nothing about a 25th, so both sides are counted and the count must equal the number
     *    verified. Deleting that ratified check would leave the guard silently covering less than
     *    it appears to, while presence and order still hold.
     */
    @Test
    fun theAliasGuardRunsBeforeTheCrossKvBindAndStillCountsTheWholePopulation() {
        val init = functionBody(cpp, "Java_com_whispereverywhere_npu_QnnAsrNative_nativeInit(")
        val guard = liveOffsets(init, "aliasGuardLocked()")
        assertTrue(
            "nativeInit must CALL aliasGuardLocked() on a live line. Presence is asserted " +
                "separately from ordering because \"A precedes B\" is trivially true when there is " +
                "no A — deleting the call is exactly the mutation measured green at Q3.",
            guard.isNotEmpty()
        )
        val bind = liveOffsets(init, "bindDecoderLocked()")
        assertTrue(
            "nativeInit must CALL bindDecoderLocked() on a live line — that is where the 24 " +
                "cross-KV inputs are pointed at the encoder's output buffers.",
            bind.isNotEmpty()
        )
        assertTrue(
            "aliasGuardLocked() must run BEFORE bindDecoderLocked() (guard at ${guard.first()}, " +
                "bind at ${bind.first()}). Verifying the alias after aliasing on it is not a " +
                "guard; and running it before the buffers are even allocated is why a refusal " +
                "costs nothing rather than 27 MiB.",
            guard.first() < bind.first()
        )

        val body = functionBody(cpp, "std::string aliasGuardLocked()")
        assertTrue(
            "aliasGuardLocked must compare the 24 pairs field by field via aliasCompare() on a " +
                "live line",
            liveOffsets(body, "aliasCompare(").isNotEmpty()
        )
        assertTrue(
            "aliasGuardLocked must still COUNT the cross-KV population — the pair loop verifies " +
                "the 24 tensors this seam knows about and is silent about a 25th, which a whisper " +
                "variant with more than 12 layers would carry and which bindDecoderLocked's " +
                "bind-by-name pass would happily alias unchecked. Expected a live countCross " +
                "definition scanning for the shared name fragment.",
            liveOffsets(body, "countCross = [](").isNotEmpty() &&
                liveOffsets(body, "_cache_cross_").isNotEmpty()
        )
        assertTrue(
            "countCross must be applied to BOTH sides — the encoder's outputs and the decoder's " +
                "inputs. Counting one side cannot detect an extra tensor on the other. Found " +
                "${liveOffsets(body, "countCross(").size} live call sites.",
            liveOffsets(body, "countCross(").size >= 2
        )
        assertTrue(
            "the counted population must be ASSERTED equal to the number of pairs verified, on a " +
                "live line. Counting without comparing is decoration. Live lines mentioning " +
                "encCross: " + liveLines(body, "encCross"),
            liveOffsets(body, "encCross != checked || decCross != checked").isNotEmpty()
        )
    }

    /**
     * C2 — the suppression mask is applied to the logits, and only THEN is the argmax scanned.
     *
     * Whisper's suppression is by construction a pre-argmax mask. Reverse these two and the code
     * still compiles, still runs, and still returns a token every step — it just returns the
     * suppressed one, because a scan that has already picked a winner has thrown the runner-up
     * away. Re-running the step is deterministic, so a caller holding only the argmax can neither
     * fix it nor detect it: the loop emits the suppressed token or hangs. That is the entire reason
     * the decode loop lives on the native side of the JNI boundary at all, and this is the pin that
     * keeps the two halves from drifting apart into a "helpful" per-step API.
     */
    @Test
    fun theSuppressionMaskIsAppliedToTheLogitsBeforeTheArgmaxIsScanned() {
        val body = functionBody(cpp, "int32_t suppressThenArgmax(")
        val mask = liveOffsets(body, "logits[id] = kLogitFloor;")
        assertTrue(
            "suppressThenArgmax must WRITE the mask into the logits buffer on a live line — both " +
                "for the always-on list and for the begin-suppress list. Found ${mask.size}, " +
                "expected at least 2.",
            mask.size >= 2
        )
        val scan = liveOffsets(body, "if (logits[i] > bestVal)")
        assertTrue(
            "suppressThenArgmax must scan the logits for the argmax on a live line",
            scan.isNotEmpty()
        )
        assertTrue(
            "the LAST mask write (${mask.last()}) must come before the FIRST scan comparison " +
                "(${scan.first()}). Both offsets are live lines, never indexOf: this file's prose " +
                "names the ordering several times while explaining why it matters, and a raw " +
                "search would measure the comment.",
            mask.last() < scan.first()
        )
        assertTrue(
            "the begin-suppress list must be applied CONDITIONALLY inside the same function — it " +
                "belongs to the first generated step only, and hoisting it into the always-on " +
                "list would mask EOT at every step and leave the loop with no terminator short of " +
                "the position cap.",
            liveOffsets(body, "if (applyBegin)").isNotEmpty()
        )

        // AND THE CALL SITE, which is the same mistake one level up. Everything above scopes to
        // suppressThenArgmax's own body; none of it says nativeDecodeSegment ever CALLS it.
        // Replacing that one call with `argmaxInRange(logits, 0, g.vocab)` compiles, reintroduces
        // C2 in full, and leaves every assertion above satisfied — exactly the shape of Q3's N1,
        // where the guard was present and the call was gone.
        val loop = functionBody(cpp, "Java_com_whispereverywhere_npu_QnnAsrNative_nativeDecodeSegment(")
        assertTrue(
            "nativeDecodeSegment must CALL suppressThenArgmax on a live line. The mask and the " +
                "scan being correctly ordered inside a function nobody calls is not a defence.",
            liveOffsets(loop, "suppressThenArgmax(").isNotEmpty()
        )
        assertTrue(
            "the begin-suppress predicate must be `position == promptLen - 1` on a live line in " +
                "nativeDecodeSegment. This is the brief's own named defect: BEGIN_SUPPRESS applies " +
                "at the FIRST GENERATED step, which is promptLen - 1, and NOT at position == 0 " +
                "(where the model is still being fed the prompt and its argmax is discarded). " +
                "`position == 0` compiles and mutes 220/EOT at a step whose output is thrown away, " +
                "leaving the real first token unguarded.",
            liveOffsets(loop, "position == promptLen - 1").isNotEmpty()
        )

        // The decode loop needs EOT for itself: the contract's argument list is fixed and a
        // terminator smuggled in through a data array is a terminator nobody can see. That makes
        // kEotToken a SECOND reading of an asset fact whose first reading is WhisperTokens.EOT,
        // and two readings that can drift are one reading with extra steps.
        val eot = liveLines(cpp, "constexpr int32_t kEotToken")
        assertTrue(
            "qnn_asr.cpp must define kEotToken on exactly one live line; found: $eot",
            eot.size == 1
        )
        assertTrue(
            "native's kEotToken and Kotlin's WhisperTokens.EOT (${WhisperTokens.EOT}) are two " +
                "readings of the same asset fact and must agree. If they ever disagree, the " +
                "decode loop terminates on an id the tokenizer does not call end-of-text and the " +
                "transcript runs to the position cap. Found: ${eot.first()}",
            eot.first().contains("= ${WhisperTokens.EOT};")
        )
    }

    /**
     * Q10a-D1 — the `npu-debug:` instrumentation: gated, greppable, and **incapable of printing
     * transcript content**.
     *
     * The device run says the decoder emits exactly one token and it detokenises to nothing, and
     * every hypothesis about why is a statement about numbers only the native loop can see. So the
     * loop now narrates itself. That creates a new hazard the rest of this tier does not have: these
     * lines are *about the model's output distribution*, and the ids in that distribution are the
     * words the user just said.
     *
     * Four things are pinned, and the third is the one that matters:
     *  - the lines carry the house `WE-DIAG` tag, or the owner's `adb logcat -s WE-DIAG` capture —
     *    run by someone else, on their behalf — silently does not contain them;
     *  - the `npu-debug: ` prefix is only ever emitted through `LOGDIAG`, so one grep finds all of
     *    it and no half of it leaks to another tag;
     *  - **`diagToken` is the only way a token id reaches a line, and it collapses everything below
     *    `kEotToken` to the constant `text-token`.** Content-safety is a property of that function
     *    rather than a rule each of the eight call sites has to remember;
     *  - the whole thing is behind `g.diag`, off until Kotlin says otherwise.
     */
    @Test
    fun theNpuDebugInstrumentationIsGatedAndCannotPrintTranscriptContent() {
        // EVERY LOGDIAG* macro, not just the first. This was `.single()` until the final review's
        // F2 added the error-level twin `LOGDIAGE` — and `.single()` would have failed on the
        // arrival of a SECOND correct macro while saying nothing about whether it was correct.
        // `all { }` is the property the test is actually about: no member of this family may be
        // defined against a tag the owner's capture cannot see.
        assertTrue(
            "every `#define LOGDIAG*` in qnn_asr.cpp must target the house WE-DIAG tag on a live " +
                "line — the owner's acceptance capture is `adb logcat -s WE-DIAG`, so a line under " +
                "WE-NPU is a line nobody will ever read. Found: " + liveLines(cpp, "#define LOGDIAG"),
            liveOffsets(cpp, "#define LOGDIAG").isNotEmpty() &&
                liveLines(cpp, "#define LOGDIAG").all { it.contains("\"WE-DIAG\"") }
        )

        // Every live mention of the prefix must be an emission through LOGDIAG. A stray
        // __android_log_print with the same prefix under a different tag would be invisible to the
        // capture while looking, in review, exactly like the lines that are not.
        val emissions = liveLines(cpp, "npu-debug:")
        assertTrue(
            "qnn_asr.cpp must emit npu-debug: lines; found none",
            emissions.isNotEmpty()
        )
        assertTrue(
            "every LIVE npu-debug: line must be emitted through LOGDIAG. Offenders: " +
                emissions.filterNot { it.contains("LOGDIAG(") },
            emissions.all { it.contains("LOGDIAG(") }
        )

        // THE PRIVACY RULE, as a property of one function.
        val token = functionBody(cpp, "const char *diagToken(")
        assertTrue(
            "diagToken must print an id verbatim ONLY when it is >= kEotToken — at or above EOT " +
                "the ids are prompt scaffolding, language tags, control tokens and timestamps, " +
                "which are configuration and metadata. Below it they are the words the user said.",
            liveOffsets(token, "id >= kEotToken").isNotEmpty()
        )
        assertTrue(
            "diagToken must collapse every other id to the constant string \"text-token\". A " +
                "diagnostic that prints one text id has logged transcript content; one that " +
                "prints a hundred has logged the transcript.",
            liveOffsets(token, "\"text-token\"").isNotEmpty()
        )
        val decode = functionBody(cpp, "Java_com_whispereverywhere_npu_QnnAsrNative_nativeDecodeSegment(")
        // EACH ID BY NAME, not a count of wrappers. A count is a weaker claim than it looks:
        // there are four distinct ids on these lines across six call sites, so "at least five"
        // is satisfied by unwrapping any one of them — measured, as row D2, which SURVIVED the
        // count form of this assertion and kills the form below.
        listOf(
            "tokenIn" to "the token fed to this step (a PROMPT id early, a GENERATED id after)",
            "h.argmax" to "the raw pre-mask argmax",
            "tok" to "the post-mask argmax — the token this step actually emits",
            "firstGenerated" to "the first generated token, on the result line",
        ).forEach { (id, what) ->
            assertTrue(
                "$what must reach its log line through diagToken($id, …). Printing it directly " +
                    "puts a raw vocabulary id in logcat, and every id below EOT is a word the " +
                    "user said. Live diagToken sites: " +
                    liveLines(decode, "diagToken(").map { it.trim() },
                liveOffsets(decode, "diagToken($id,").isNotEmpty()
            )
        }
        assertTrue(
            "the prompt echo must go through diagIdList, which applies the same rule per id. The " +
                "prompt is four specials today and would carry previous-segment TEXT if this tier " +
                "ever adopted whisper's <|startofprev|> form.",
            liveOffsets(decode, "diagIdList(").isNotEmpty()
        )

        // THE RAW READING MUST BE TAKEN BEFORE THE MASK, because the mask MUTATES the logits buffer.
        val raw = liveOffsets(decode, "scanLogitsRaw(")
        val masked = liveOffsets(decode, "suppressThenArgmax(")
        assertTrue("nativeDecodeSegment must scan the raw logits on a live line", raw.isNotEmpty())
        assertTrue(
            "the raw logits scan (${raw.first()}) must run BEFORE suppressThenArgmax " +
                "(${masked.first()}). suppressThenArgmax WRITES kLogitFloor into the logits " +
                "buffer, so a `raw[min= max= argmax=]` reading taken after it reports the MASKED " +
                "distribution while calling itself raw — and the whole point of this round is that " +
                "someone reads those two numbers and decides which hypothesis is true.",
            raw.first() < masked.first()
        )

        // The gate.
        assertTrue(
            "the instrumentation must be behind g.diag on live lines — a release build emits " +
                "nothing and pays for no full-vocabulary scans.",
            liveOffsets(decode, "g.diag").isNotEmpty()
        )
        val setter = functionBody(cpp, "Java_com_whispereverywhere_npu_QnnAsrNative_nativeSetDiag(")
        assertTrue(
            "nativeSetDiag must set g.diag on a live line",
            liveOffsets(setter, "g.diag =").isNotEmpty()
        )
        assertTrue(
            "NpuWhisperBackend must arm the instrumentation from BuildConfig.DEBUG on a live " +
                "line — Kotlin owns that decision because that is where the flag exists, and a " +
                "native build-type ifdef would be a second definition of \"debug build\" free to " +
                "disagree with the app's.",
            liveOffsets(backend, "nativeSetDiag(").isNotEmpty() &&
                liveLines(backend, "nativeSetDiag(").single().contains("BuildConfig.DEBUG")
        )

        // ---- Q10a-D2: the ENCODER read ---------------------------------------------------------
        //
        // Six native lines, and every one of them is a measurement whose VALUE DEPENDS ENTIRELY ON
        // WHERE IT IS TAKEN. That is what is pinned here: not that the lines exist — that they are
        // taken at the only moment and from the only pointer at which their numbers mean what the
        // line says they mean. An instrumentation round that reports the wrong buffer, or the right
        // buffer at the wrong instant, does not fail. It produces confident numbers, and the next
        // decision is made on them.
        val encode = functionBody(cpp, "Java_com_whispereverywhere_npu_QnnAsrNative_nativeEncode(")

        listOf(
            "npu-debug: quant " to "the quantisation params as read, with input_features' DIMS " +
                "and data format — the transpose hypothesis stated in full, and the first time " +
                "any of it reaches WE-DIAG (Q1 logged the shapes under WE-NPU, which the owner's " +
                "capture filters out)",
            "npu-debug: inbuf " to "the input block's distribution and rail counts",
            // NOT `npu-debug: layout `, and the difference was MEASURED. Battery row D10 deleted the
            // transpose detector's LOGDIAG outright and SURVIVED the prefix form of this assertion:
            // the `layout UNUSABLE` fallback in the else-branch contains the same prefix and
            // answered for it. A needle that a sibling line also satisfies pins nothing. So the two
            // SUMS are named instead — they are the reading, and the fallback carries neither.
            "sumFirstRow=" to "the first row's sum — half of the transpose detector, the decisive " +
                "reading of this round",
            "sumColStride=" to "the stride-frames column sum — the other half; without it there is " +
                "one number and nothing to compare it against",
            "npu-debug: dequant " to "three cells back through the affine transform, which is the " +
                "only reading that catches a sign or offset misapplication exactly",
            "npu-debug: crossKV " to "whether the encoder wrote its outputs at all",
        ).forEach { (needle, what) ->
            assertTrue(
                "qnn_asr.cpp must emit `$needle…` — $what. Deleting it removes a reading the " +
                    "next round's conclusion depends on, and nothing else in this suite would notice.",
                liveOffsets(encode, needle).isNotEmpty()
            )
        }
        assertTrue(
            "nativeDecodeSegment must emit `npu-debug: selfkv ` — the self-KV write check, which " +
                "settles D1's H2 for one buffer scan per segment.",
            liveOffsets(decode, "npu-debug: selfkv ").isNotEmpty()
        )
        // Q10a-D3: the field that decides the cache alignment, and therefore the mask fill.
        assertTrue(
            "the selfkv line must carry `slot=[%d..%d]` — D2 proved the graph writes exactly one " +
                "slot per step but not WHICH, and that index is the entire remaining question: " +
                "slot==position is left-aligned and the mask must enable columns 0..position, " +
                "while a fixed top slot is a right-aligned shift register and the mask must " +
                "enable the LAST position+1 columns. The two fills are disjoint.",
            liveOffsets(decode, "slot=[%d..%d]").isNotEmpty()
        )
        assertTrue(
            "the slot index must be derived from the tensor's OWN dims, not a constant: " +
                "k_cache_self_* is [12,1,64,199] (slot axis last, stride 1) and v_cache_self_* is " +
                "[12,1,199,64] (second-to-last, stride 64), so the wrong arithmetic reports an " +
                "index wrong by a factor of 64 that still looks entirely plausible.",
            liveOffsets(decode, "scanNonzeroSlots(").isNotEmpty() &&
                liveOffsets(decode, "sdm[srank - 1] == depth").isNotEmpty()
        )
        assertTrue(
            "the selfkv line must run for steps 0, 1 AND 2 — one step cannot distinguish a " +
                "fixed write slot from a moving one, and the two candidate layouts diverge from " +
                "the second step onward.",
            liveOffsets(decode, "position <= 2").isNotEmpty()
        )

        // POST-COPY, PRE-EXECUTE. Both halves, because both are silently wrong rather than absent.
        val copy = liveOffsets(encode, "memcpy(dst.p")
        val exec = liveOffsets(encode, "g.qnn.graphExecute(")
        assertTrue("nativeEncode must memcpy into dst.p on a live line", copy.isNotEmpty())
        assertTrue("nativeEncode must call graphExecute on a live line", exec.isNotEmpty())
        listOf("npu-debug: inbuf ", "sumFirstRow=", "npu-debug: dequant ").forEach { needle ->
            val at = liveOffsets(encode, needle).first()
            assertTrue(
                "`$needle` must be emitted AFTER the memcpy (${copy.first()}). The input buffer is " +
                    "session-scoped and never cleared, so a reading taken above the copy describes " +
                    "the PREVIOUS segment — with numbers that look entirely reasonable and belong " +
                    "to different audio. Found at $at.",
                at > copy.first()
            )
            assertTrue(
                "`$needle` must be emitted BEFORE graphExecute (${exec.first()}) — after it, the " +
                    "graph has run and the block is no longer provably the one it was handed. " +
                    "Found at $at.",
                at < exec.first()
            )
        }
        val cross = liveOffsets(encode, "npu-debug: crossKV ").first()
        assertTrue(
            "`npu-debug: crossKV ` must be emitted AFTER graphExecute (${exec.first()}) — it asks " +
                "whether the encoder WROTE those buffers, and asked beforehand the answer is " +
                "always AlignedBuf's zeros, i.e. the failure it is looking for, every time. " +
                "Found at $cross.",
            cross > exec.first()
        )

        // THE POINTER. This is the difference between confirming what Kotlin wrote and measuring
        // what the DSP will read, and they are the same address only if nothing is wrong.
        assertTrue(
            "nativeEncode's input-block scan must read `dst.p` — the pointer bound to the tensor " +
                "by tensorSetClientBuf — and never the JNI source address `src`. Reading `src` " +
                "would re-measure the buffer Kotlin just filled and prove nothing whatsoever " +
                "about what the graph sees, which is the entire question of this round.",
            liveOffsets(encode, "static_cast<const uint16_t *>(dst.p)").isNotEmpty()
        )

        // THE SET. Reading the input side would report the zeroed cache and manufacture the exact
        // failure the line exists to test for.
        assertTrue(
            "the self-KV write check must read the OUT set — `1 - inSetForStep` — because that is " +
                "the side bindSelfKvLocked pointed the outputs at for the step that just ran. " +
                "Reading inSetForStep reports the set the graph consumed, which at position 0 is " +
                "zeroed by construction, so the line would print nonzero=0.000 on a perfectly " +
                "healthy decoder and send the next round after a defect that is not there.",
            liveOffsets(decode, "1 - inSetForStep").isNotEmpty()
        )

        // THE GATE, on the encode side too. The decode-side gate is asserted above, and this block
        // is the more expensive one: two 1,152,000-byte cross-KV scans plus a 240,000-value input
        // scan, every segment, for lines a release build must not emit at all. Two sites — the
        // pre-execute block and the post-execute cross-KV block — so a gate deleted from either
        // fails here rather than shipping.
        assertTrue(
            "nativeEncode's instrumentation must sit behind g.diag at BOTH sites (pre-execute and " +
                "post-execute); found ${liveOffsets(encode, "g.diag").size} live mentions, " +
                "expected at least 2.",
            liveOffsets(encode, "g.diag").size >= 2
        )

        // The Kotlin half, and its gate.
        assertTrue(
            "NpuWhisperBackend must emit NpuDiag.melProbe on a live line — native's layout and " +
                "dequant readings are only decisive against an independently computed reference, " +
                "and this is that reference.",
            liveOffsets(backend, "NpuDiag.melProbe(").isNotEmpty()
        )
        assertTrue(
            "the melProbe emission must be behind BuildConfig.DEBUG, like nativeSetDiag. It " +
                "prints three spectrogram cells, and a release build has no business doing that.",
            liveOffsets(backend, "BuildConfig.DEBUG").size >= 2
        )
    }

    /**
     * **THE ATTENTION MASK'S COLUMN ARRANGEMENT — the Q10a defect, and the most consequential
     * single line on this branch.**
     *
     * It has its own test rather than living inside the load-time-guard pin, and that is the point:
     * this is RUNTIME behaviour, rewritten 199 times per segment, and it was folded in beside four
     * *load-time* guards where a maintainer trimming that test to its documented scope would have
     * deleted it without the test name telling them what they broke.
     *
     * THE LAYOUT. `attention_mask` is `[1,1,1,200]` over a 199-deep self-KV: the 200 columns are
     * the 199 cache slots plus **the current token's own key at column `maskLen-1`**, and the cache
     * is a **right-aligned shift register** — every write lands at the top slot and the earlier
     * entries move down. So at position `p` the live columns are `maskLen-1-p .. maskLen-1`: the
     * last `p+1`. Column 0 is the one never used (it would need `p >= 199`, and `lastPosition`
     * stops the loop at 198).
     *
     * WHY IT NEEDS A PIN AT ALL. The superseded fill was `i <= position` — the FIRST `p+1` columns,
     * a set disjoint from the live one at every position up to 197. It enabled only never-written
     * padding and never the current token, so self-attention saw nothing while cross-attention
     * stayed healthy; the decoder heard the audio, emitted a language token at its first scored
     * step, and then EOT. **Nothing detected it for four review rounds and roughly fifty battery
     * rows**, because the mask *codes* were pinned (`checkMaskCodesLocked` dequantises both through
     * the tensor's own scale and offset — correct, and it passed) while which columns received
     * which code was asserted nowhere. It was itself a plan-time sentence, implemented faithfully
     * and never re-derived, in a file whose doctrine is that such a sentence must become a guard.
     */
    @Test
    fun theAttentionMaskEnablesTheCurrentTokenAndTheHistoryAboveIt() {
        val step = functionBody(cpp, "std::string decodeStepLocked(")
        assertTrue(
            "decodeStepLocked must fill the attention mask from the TOP down — `i >= firstLive` " +
                "with `firstLive = maskLen - 1 - position` — so the current token's own key at " +
                "column maskLen-1 is ALWAYS enabled and the history is the p entries below it. " +
                "Live mask lines: " + liveLines(step, "mask[i] ="),
            liveOffsets(step, "g.maskLen - 1 - position").isNotEmpty() &&
                liveOffsets(step, "(i >= firstLive) ? kMaskAttend : kMaskBlocked").isNotEmpty()
        )
        assertTrue(
            "the superseded fill `(i <= position) ? kMaskAttend` must appear nowhere live in " +
                "decodeStepLocked — it is the Q10a defect and it reads as though it were right.",
            !step.contains("(i <= position) ? kMaskAttend")
        )
    }

    /**
     * The decode loop's four single-point invariants — each one line, each with no host-visible
     * signal, none of them defended before this pin existed.
     *
     * `position` is the single counter and every one of these edits compiles, runs, and produces a
     * plausible transcript:
     *  - `lastPosition = maskLen - 2` → `- 1` lets position 199 execute, one past the 199-slot
     *    self-KV. That is the brief's named overrun;
     *  - deleting the prompt-prefill swap makes every prompt step read set 0 and write set 1, so
     *    the prefill cache is thrown away and only the last prompt token's state survives;
     *  - deleting `next = tok` feeds the same token forever — the model repeats one word to the
     *    position cap;
     *  - deleting either terminator (`EOT`, the budget) removes the loop's ability to stop early.
     *
     * None of these is a defect in the shipped code. The pin exists because all four are a single
     * line, and Q10a is the first execution.
     */
    @Test
    fun theDecodeLoopsSinglePointInvariantsArePinned() {
        val loop = functionBody(cpp, "Java_com_whispereverywhere_npu_QnnAsrNative_nativeDecodeSegment(")
        assertTrue(
            "the last EXECUTING position must be `g.maskLen - 2` on a live line — 198 for this " +
                "asset. `- 1` runs position 199, one past the 199-slot self-KV, with the graph " +
                "doing the indexing and nothing on either side reporting it. Live lines " +
                "mentioning lastPosition: " + liveLines(loop, "lastPosition"),
            liveOffsets(loop, "const uint32_t lastPosition = g.maskLen - 2;").isNotEmpty()
        )
        val swaps = liveOffsets(loop, "bindSelfKvLocked(1 - g.selfInSet);")
        assertTrue(
            "the loop must swap the self-KV ping-pong on BOTH paths — after a discarded " +
                "prompt-prefill step and after an emitted token. Found ${swaps.size} live sites, " +
                "expected 2. Dropping the prefill swap makes every prompt step read set 0 and " +
                "write set 1, so three quarters of the prompt's cache is overwritten before the " +
                "first generated token ever sees it.",
            swaps.size >= 2
        )
        assertTrue(
            "the loop must feed the emitted token back as the next input (`next = tok;`) on a " +
                "live line. Without it `next` stays at prompt[0] and the model is asked to " +
                "continue from <|startoftranscript|> at every position.",
            liveOffsets(loop, "next = tok;").isNotEmpty()
        )
        assertTrue(
            "the loop must terminate on EOT on a live line — it is the only terminator short of " +
                "the position cap.",
            liveOffsets(loop, "if (tok == kEotToken)").isNotEmpty()
        )
        assertTrue(
            "the loop must terminate on the token budget on a live line, AFTER writing the token " +
                "that filled it.",
            liveOffsets(loop, "if (count >= maxTokens) break;").isNotEmpty()
        )
    }

    /**
     * The decoder's load-time guards on the asset's own numbers — step 1's doctrine applied to the
     * four facts the loop rests on besides the cross-KV alias, plus the encode-validity flag.
     *
     * Each of these is a guard whose deletion is a one-line edit with no host-visible signal, and
     * each defends a failure that presents as **fluent wrong text** rather than as an error:
     *  - **dtype equality, not byte width.** `elementSize()` collapses signed/unsigned/float, so an
     *    `SFIXED_POINT_16` `logits` re-export passes every size check and inverts the raw-code
     *    ordering the whole argmax argument rests on;
     *  - **the mask codes**, checked against `attention_mask`'s own quantisation. If a re-export
     *    flips its offset, 65535 becomes the *blocked* code and the decoder attends to nothing;
     *  - **the self-KV depth**, checked against the mask width. `lastPosition` is derived from one
     *    tensor and the "exact fit" claim is about another, and nothing else compares them;
     *  - **the encode-validity flag.** The decoder reads the cross-KV in place, so a decode with no
     *    preceding encode transcribes the *previous segment* — the worst failure shape in the tier
     *    and its most likely integration mistake.
     */
    @Test
    fun theDecodersLoadTimeGuardsOnTheAssetsOwnNumbersCannotBeDeletedSilently() {
        val bind = functionBody(cpp, "std::string bindDecoderLocked()")
        assertTrue(
            "bindDecoderLocked must assert the exact Qnn data TYPE of input_ids and position_ids " +
                "(QNN_DATATYPE_INT_32), not merely their 4-byte width — elementSize() maps " +
                "INT_32, UINT_32, SFIXED_32, UFIXED_32 and FLOAT_32 all to 4.",
            liveOffsets(bind, "QNN_DATATYPE_INT_32").size >= 2
        )
        assertTrue(
            "bindDecoderLocked must assert QNN_DATATYPE_UFIXED_POINT_16 for BOTH attention_mask " +
                "and logits. UNSIGNED is the load-bearing half: dequantisation is scale x (q - zp) " +
                "with scale > 0, so an argmax over the raw codes is exact — read as signed two's " +
                "complement the ordering inverts and every token this file picks is wrong, with " +
                "every size, bind and buffer still correct. Found " +
                "${liveOffsets(bind, "QNN_DATATYPE_UFIXED_POINT_16").size} live mentions, " +
                "expected 2.",
            liveOffsets(bind, "QNN_DATATYPE_UFIXED_POINT_16").size >= 2
        )
        val maskBind = liveOffsets(bind, "own(kAttentionMask,")
        val maskCheck = liveOffsets(bind, "checkMaskCodesLocked()")
        assertTrue(
            "bindDecoderLocked must CALL checkMaskCodesLocked() on a live line — the two mask " +
                "codes are written 199 times per segment and were, until this guard, a comment.",
            maskCheck.isNotEmpty()
        )
        assertTrue(
            "bindDecoderLocked must bind attention_mask through own(kAttentionMask, …) on a live " +
                "line — that call is what sets g.decMaskIdx.",
            maskBind.isNotEmpty()
        )
        assertTrue(
            "checkMaskCodesLocked() (${maskCheck.first()}) must be called AFTER own(kAttentionMask, " +
                "…) (${maskBind.first()}). The guard reads g.dec.inputs[g.decMaskIdx], and that " +
                "index is set BY that own() call: run it first and it inspects whatever tensor " +
                "index 0 happens to be, so a guard that looks like it passed would have checked " +
                "the wrong tensor's quantisation.",
            maskCheck.first() > maskBind.first()
        )
        val mask = functionBody(cpp, "std::string checkMaskCodesLocked()")
        assertTrue(
            "checkMaskCodesLocked must actually DEQUANTISE both codes through the tensor's own " +
                "scale and offset and compare the results. A guard that reads the quant params " +
                "and asserts nothing about them is decoration.",
            liveOffsets(mask, "kMaskAttend) + offset) * scale").isNotEmpty() &&
                liveOffsets(mask, "kMaskBlocked) + offset) * scale").isNotEmpty() &&
                liveOffsets(mask, "attend > -0.01 && attend < 0.01").isNotEmpty() &&
                liveOffsets(mask, "blocked <= -30.0").isNotEmpty()
        )
        // AND THE BLOCK THRESHOLD MUST MEAN SOMETHING (final review F5/I6). These are additive
        // pre-softmax biases and D1 measured this decoder's logit spread at ~12,500, so the old
        // `blocked <= -1.0` suppressed nothing: it would have passed an asset that leaks attention
        // across every masked column and produces exactly the fluent-and-wrong transcript the
        // guard's own message warns about. The shipped asset dequantises to -100.0, so -30.0 keeps
        // 2.5 orders of magnitude of slack. Pinned as a NEGATIVE too, because the weak form is one
        // character away and reads as a tightening rather than a loosening.
        assertTrue(
            "the -1.0 BLOCK threshold must not come back: against a ~12500 logit spread it is a " +
                "rounding error, and the column stays fully attended.",
            liveOffsets(mask, "blocked <= -1.0").isEmpty()
        )
        assertTrue(
            "bindDecoderLocked must compare the self-KV cache DEPTH against the mask width on a " +
                "live line. lastPosition comes from attention_mask; the 'exact fit' is a claim " +
                "about the self-KV; nothing else in the file relates the two.",
            liveOffsets(bind, "depth != g.maskLen - 1").isNotEmpty()
        )

        val enc = functionBody(cpp, "Java_com_whispereverywhere_npu_QnnAsrNative_nativeEncode(")
        val armed = liveOffsets(enc, "g.encoded = true;")
        val executed = liveOffsets(enc, "graphExecute")
        assertTrue(
            "nativeEncode must SET the encode-validity flag on a live line.",
            armed.isNotEmpty()
        )
        assertTrue(
            "nativeEncode must run the encoder's graphExecute on a live line.",
            executed.isNotEmpty()
        )
        assertTrue(
            "the flag must be set (${armed.first()}) AFTER graphExecute (${executed.first()}), " +
                "not before. Presence alone is not the invariant: hoisting that one line above " +
                "the execute is a one-line move that reinstates exactly what the flag prevents — " +
                "an execute that fails part way leaves the flag SET, and the next decode reads " +
                "half-written cross-KV and transcribes it fluently. Same ordering discipline as " +
                "the mask-before-scan and guard-before-bind pins, and for the same reason.",
            armed.first() > executed.first()
        )
        assertTrue(
            "nativeEncode must CLEAR the flag on entry on a live line — a graphExecute that " +
                "failed part way may have written some of the 24 cross-KV buffers, and half a " +
                "segment decodes as fluently as a whole one.",
            liveOffsets(enc, "g.encoded = false;").isNotEmpty()
        )
        val decode = functionBody(cpp, "Java_com_whispereverywhere_npu_QnnAsrNative_nativeDecodeSegment(")
        assertTrue(
            "nativeDecodeSegment must refuse a decode with no encoded segment, on a live line. " +
                "Without it the decoder reads the previous segment's cross-KV in place and " +
                "transcribes it — no crash, no error, no way for the caller to tell.",
            liveOffsets(decode, "if (!g.encoded)").isNotEmpty()
        )
        val detect = functionBody(cpp, "Java_com_whispereverywhere_npu_QnnAsrNative_nativeDetectLanguage(")
        assertTrue(
            "nativeDetectLanguage must refuse the same way — detecting off the previous segment's " +
                "encoder state picks the wrong prompt language for this one.",
            liveOffsets(detect, "if (!g.encoded)").isNotEmpty()
        )
        val release = functionBody(cpp, "void releaseLocked()")
        assertTrue(
            "releaseLocked must clear the flag on a live line, so a torn-down session cannot be " +
                "decoded against.",
            liveOffsets(release, "g.encoded = false;").isNotEmpty()
        )
    }

    /**
     * I1 — the seam gate. What leaves this tier as a "detected language" must be
     * `LangResolution.reportable`, never the bare `code`.
     *
     * `LocalWhisperEngine` hands `detectedLanguage(ctx)` straight to `LanguagePin.onDetected`, which
     * latches the FIRST usable code and never revises it. One character — `.reportable` to `.code` —
     * turns one failed detect pass on segment 1 into a session pinned to English: the detect pass
     * then stops running, and every later diag line prints the bare `en` note that
     * `NpuDecodePolicyTest` asserts means *the user chose English*. The policy's central invariant,
     * enforced carefully inside `NpuDecodePolicy` and discarded one call further out.
     *
     * The negative half is the sharper one: **`resolution.code` has no legitimate use in this
     * file.** The prompt takes `.token`, the diag line takes `.note`, the seam takes `.reportable`.
     * A live mention of `.code` anywhere is the laundering, whatever it is assigned to.
     */
    @Test
    fun theTiersReportedLanguageCrossesTheSeamOnlyWhenItWasActuallyDetected() {
        assertTrue(
            "the backend must store LangResolution.reportable for the seam, on a live line. " +
                "Live lines mentioning lastReportedLanguage: " +
                liveLines(backend, "lastReportedLanguage"),
            liveOffsets(backend, "lastReportedLanguage = resolution.reportable").isNotEmpty()
        )
        assertTrue(
            "`resolution.code` must appear on NO live line of NpuWhisperBackend.kt. The prompt uses " +
                ".token, the diag line uses .note, and the seam uses .reportable — there is nothing " +
                "left for the bare code to be right for, so any live use of it is a guess being " +
                "reported as a detection. Found: " + liveLines(backend, "resolution.code"),
            liveOffsets(backend, "resolution.code").isEmpty()
        )
        val detect = kotlinMemberBody(backend, "override fun detectedLanguage(ctx: Long): String? {")
        assertTrue(
            "detectedLanguage must answer from the gated field on a live line — it is the only " +
                "value in this class whose provenance has been checked.",
            liveOffsets(detect, "lastReportedLanguage").isNotEmpty()
        )
    }

    /**
     * I2 — the routing state: published guard-last, read under the gate, and armed AT MOST ONCE.
     *
     * `fallbackBackend` and `fallbackCtx` are the only shared mutable state two threads can reach
     * simultaneously, and all three failures below are silent:
     *
     *  - **read outside the gate** — two threads both observe `fallbackBackend == null`, both fall
     *    back, and the second `WhisperNativeBackend.load` overwrites a live handle. A whole CPU
     *    tier, 60-190 MB, leaked for the life of the process;
     *  - **guard published first** — a reader sees a non-null backend beside a still-zero handle and
     *    calls `transcribe(0L, …)`. `whisper_jni` guards the null ctx, so it is not a crash: it is a
     *    silently lost utterance on a dictation path;
     *  - **no at-most-once guard** — the same overwrite, reachable without any race at all if a
     *    future caller reaches the funnel twice.
     *
     * All three are ORDER or at-most-once claims, not presence claims: the statements are all still
     * there in every one of them. Same discipline as the mask-before-scan, guard-before-bind and
     * flag-after-execute pins, and for the same reason.
     */
    @Test
    fun theFallbackFunnelIsAtMostOnceAndItsRoutingStateIsPublishedGuardLast() {
        listOf(
            "@Volatile\n    private var fallbackCtx: Long = 0L",
            "@Volatile\n    private var fallbackBackend: WhisperBackend? = null",
        ).forEach { declaration ->
            assertTrue(
                "both routing fields must be @Volatile, declared exactly as:\n$declaration\n" +
                    "The gate gives mutual exclusion; @Volatile gives publication. Neither is the " +
                    "other, and the fields the hot path branches on need both.",
                backend.contains(declaration)
            )
        }

        // (4.0 Q9 rider) The GUARD paragraph must sit on the field it describes. This is the one
        // assertion in this class that is deliberately about COMMENT text, and it exists because
        // the failure already happened: a declaration-order swap left the whole "non-null is the
        // whole of the routing decision / written LAST when arming" block attached to fallbackCtx,
        // a Long whose 0L cannot be told from its own uninitialised value — so the field that
        // carries the invariant shipped undocumented and the field that cannot carry it claimed to.
        // Nothing above catches that: both declarations are present, both are @Volatile, and every
        // ordering pin below still passes. A KDoc that names the wrong field is not a cosmetic
        // defect on a routing pair two threads reach; it is the next reader being told, in the one
        // place they will look, that the guard is the thing that is not the guard.
        val ctxDecl = backend.indexOf("private var fallbackCtx: Long = 0L")
        val guardDoc = backend.indexOf("**This one is the GUARD**")
        val backendDecl = backend.indexOf("private var fallbackBackend: WhisperBackend? = null")
        assertTrue("the guard paragraph must exist at all", guardDoc >= 0)
        assertTrue(
            "the guard paragraph ($guardDoc) must sit AFTER fallbackCtx's declaration ($ctxDecl) " +
                "and BEFORE fallbackBackend's ($backendDecl) — i.e. in the KDoc attached to the " +
                "field it is about. Anywhere else and it documents a field that does not have the " +
                "property it claims.",
            ctxDecl in 0 until guardDoc && guardDoc < backendDecl
        )

        val fallback = kotlinMemberBody(
            backend, "private fun fallBackToCpuTier(stage: String, detail: String): Long {"
        )
        val guard = liveOffsets(fallback, "if (fallbackBackend != null) return HANDLE")
        val cpuLoad = liveOffsets(fallback, "WhisperNativeBackend.load(")
        assertTrue(
            "fallBackToCpuTier must open with an at-most-once guard on a live line. Without it a " +
                "second entry calls load again and overwrites fallbackCtx, and releaseEverything " +
                "only ever frees the current handle — the first whisper context is leaked.",
            guard.isNotEmpty()
        )
        assertTrue("the funnel must load the CPU tier on a live line", cpuLoad.isNotEmpty())
        assertTrue(
            "the guard (${guard.first()}) must precede the load (${cpuLoad.first()}). Presence is " +
                "not the invariant: a guard that runs after the load has already leaked the handle " +
                "it was meant to protect.",
            guard.first() < cpuLoad.first()
        )
        val handleWrite = liveOffsets(fallback, "fallbackCtx = handle")
        val guardWrite = liveOffsets(fallback, "fallbackBackend = WhisperNativeBackend")
        assertTrue("the funnel must record the handle on a live line", handleWrite.isNotEmpty())
        assertTrue("the funnel must arm the guard on a live line", guardWrite.isNotEmpty())
        assertTrue(
            "the handle (${handleWrite.first()}) must be published BEFORE the guard " +
                "(${guardWrite.first()}). fallbackBackend is what every reader branches on, so it " +
                "must never become visible before the handle it implies — the same written-first / " +
                "tag-last discipline as WhisperNativeBackend's lastStats/lastStatsCtx.",
            handleWrite.first() < guardWrite.first()
        )

        val release = kotlinMemberBody(backend, "private fun releaseEverything() {")
        val guardClear = liveOffsets(release, "fallbackBackend = null")
        val handleRelease = liveOffsets(release, "previous?.release(fallbackCtx)")
        assertTrue("teardown must clear the guard on a live line", guardClear.isNotEmpty())
        assertTrue("teardown must release the CPU handle on a live line", handleRelease.isNotEmpty())
        assertTrue(
            "teardown is the MIRROR: the guard (${guardClear.first()}) is cleared BEFORE the handle " +
                "is released (${handleRelease.first()}), so no reader can route onto a handle that " +
                "is already being freed.",
            guardClear.first() < handleRelease.first()
        )

        // Both entry points read the routing state INSIDE the hold, not in front of it.
        listOf(
            "override fun transcribe(ctx: Long, samples: FloatArray, lang: String?, useVad: Boolean): String {",
            "override fun detectedLanguage(ctx: Long): String? {",
        ).forEach { anchor ->
            val body = kotlinMemberBody(backend, anchor)
            val gate = liveOffsets(body, "NativeComputeGate.serialized {")
            val read = liveOffsets(body, "fallbackBackend?.let")
            assertTrue("$anchor must take NativeComputeGate on a live line", gate.isNotEmpty())
            assertTrue("$anchor must consult the routing state on a live line", read.isNotEmpty())
            assertTrue(
                "in $anchor the gate (${gate.first()}) must be taken BEFORE the routing state is " +
                    "read (${read.first()}). Hoisting the short-circuit in front of the hold is a " +
                    "one-line move that compiles, keeps both statements, and reinstates the " +
                    "stale-null read that leaks a whole whisper context. The lock is reentrant, so " +
                    "reading inside it costs nothing on the delegating path.",
                gate.first() < read.first()
            )
        }
    }

    /**
     * THE SUSTAINED VOTE, exactly as measured — and the highest-consequence invariant in this tier
     * that nothing else can see.
     *
     * An unvoted or mis-voted session is ~2.5x slower and is **indistinguishable from slow
     * silicon** from outside: spike run 7 measured a 1007 ms median with an 838–1275 ms spread and
     * cost a device round trip to explain. Every needle below was a one-line "improvement" that
     * compiles green and can only be caught on a device:
     *  - `dcvsEnable = 0` pins the clock (the burst recipe) and burns battery for +9.6% — which is
     *    the accepted trade, in the other direction, for a config held for minutes;
     *  - `MAX_VOLTAGE_CORNER` instead of `TURBO` asks for a corner a phone cannot hold;
     *  - an `RPC_POLLING_TIME` entry spins to dodge interrupt latency and burns power
     *    continuously, process-wide. It is a burst trick and it is not here;
     *  - deleting the `vote:` log line is what made run 7 unreadable in the first place.
     *
     * Plus the ordering: the vote is armed at the END of `nativeInit`, after the contexts are
     * loaded. The spike measured its 525 ms cold load **unvoted**, so hoisting the arm above
     * `loadGraphSlot` would make our cold load faster than the figure the plan quotes and quietly
     * invalidate it.
     */
    @Test
    fun theSustainedVoteIsTheMeasuredRecipeAndItsOutcomeIsAlwaysLogged() {
        val vote = functionBody(cpp, "std::string applySustainedVoteLocked(")
        assertTrue(
            "the vote must leave DCVS ENABLED (`d.dcvsEnable = 1`) on a live line. dcvsEnable = 0 " +
                "is the burst recipe: it pins the clock, which is exactly what must not happen to " +
                "a config held for the length of a dictation session. Live dcvs lines: " +
                liveLines(vote, "dcvsEnable"),
            liveOffsets(vote, "d.dcvsEnable = 1;").isNotEmpty()
        )
        assertTrue(
            "the vote must request PERFORMANCE_MODE on a live line — DCVS still governs but ramps " +
                "eagerly, so a segment arriving after idle does not spend its first inference " +
                "climbing.",
            liveOffsets(vote, "POWERMODE_PERFORMANCE_MODE").isNotEmpty()
        )
        assertTrue(
            "the vote must target the TURBO corner (DCVS_VOLTAGE_VCORNER_TURBO) on live lines — " +
                "bus and core, target and max, four of them.",
            liveOffsets(vote, "DCVS_VOLTAGE_VCORNER_TURBO").size >= 4
        )
        assertTrue(
            "MAX_VOLTAGE_CORNER must appear NOWHERE in applySustainedVoteLocked. The top corner " +
                "is a burst affordance; TURBO is the highest corner a phone can actually hold, " +
                "and the +9.6% against burst is the accepted trade rather than a defect to tune " +
                "out.",
            !vote.contains("MAX_VOLTAGE_CORNER")
        )
        assertTrue(
            "RPC_POLLING_TIME must appear NOWHERE in applySustainedVoteLocked. Polling spins to " +
                "avoid interrupt latency and burns power continuously, process-wide.",
            !vote.contains("RPC_POLLING_TIME")
        )

        val arm = functionBody(cpp, "void armSustainedVoteLocked()")
        assertTrue(
            "armSustainedVoteLocked must emit the `vote:` result line on a live line, " +
                "unconditionally. Without it an unvoted Hexagon and slow silicon are the same " +
                "observation, which is what cost the spike run 7.",
            liveOffsets(arm, "LOGI(\"vote: %s\"").isNotEmpty()
        )

        val init = functionBody(cpp, "Java_com_whispereverywhere_npu_QnnAsrNative_nativeInit(")
        val loads = liveOffsets(init, "loadGraphSlot(")
        val armed = liveOffsets(init, "armSustainedVoteLocked()")
        assertTrue("nativeInit must load both graph slots on live lines", loads.size >= 2)
        assertTrue("nativeInit must arm the vote on a live line", armed.isNotEmpty())
        assertTrue(
            "armSustainedVoteLocked() (${armed.first()}) must come AFTER the last loadGraphSlot " +
                "(${loads.last()}). The spike acquired the perf infrastructure before " +
                "contextCreateFromBinary but did not VOTE until after it, so its 525 ms cold-load " +
                "figure is an unvoted one — arming earlier would make our cold load faster than " +
                "the number the plan quotes and silently invalidate it. A failed init also never " +
                "leaves a vote armed.",
            armed.first() > loads.last()
        )
    }

    /**
     * The vote is released FIRST in teardown, before the backend that issued it is freed.
     *
     * `destroyPowerConfigId` hands a handle back to the HTP perf infrastructure obtained from the
     * device/backend. Relocating that line below `backendFree` is a use-after-free of the power
     * config against a dead backend — a one-line move, no compiler signal, and a crash that only
     * ever reproduces on teardown of a session that actually got a vote.
     */
    @Test
    fun theVoteIsReleasedBeforeTheBackendThatIssuedItIsFreed() {
        val body = functionBody(cpp, "void releaseLocked()")
        val vote = liveOffsets(body, "releaseSustainedVoteLocked();")
        assertTrue(
            "releaseLocked must CALL releaseSustainedVoteLocked() on a live line. A session that " +
                "armed a vote and never released it holds a governor setting for the life of the " +
                "process, long after the tier it was for has been torn down.",
            vote.isNotEmpty()
        )
        val backend = liveOffsets(body, "backendFree(")
        assertTrue(
            "releaseLocked must free the backend on a live line",
            backend.isNotEmpty()
        )
        assertTrue(
            "releaseSustainedVoteLocked() (${vote.first()}) must precede backendFree() " +
                "(${backend.first()}). The vote is a session-scoped request held against that " +
                "backend; handing the config id back afterwards is handing it to a dead one.",
            vote.first() < backend.first()
        )
    }

    /**
     * Lesson 1. Without these two declarations, Android 12+ refuses to let the app's linker
     * namespace resolve `libcdsprpc.so` / `libadsprpc.so` from the vendor namespace, the HTP
     * backend's FastRPC transport never comes up, and the failure is SILENT — the NPU simply never
     * appears, which is indistinguishable from unsupported silicon.
     *
     * `required="false"` on both, deliberately: this APK installs on every device, and a required
     * native library that the vendor does not ship makes the app uninstallable there.
     */
    @Test
    fun manifestDeclaresFastRpcLibraries() {
        val manifest = source("src/main/AndroidManifest.xml")
        val appOpen = manifest.indexOf("<application")
        val appClose = manifest.indexOf("</application>")
        assertTrue(
            "AndroidManifest.xml must have an <application> element for the declarations to live in",
            appOpen in 0 until appClose
        )
        val elements = Regex("""<uses-native-library\b[^>]*/>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(manifest)
            .toList()
        listOf("libcdsprpc.so", "libadsprpc.so").forEach { lib ->
            val element = elements.firstOrNull {
                Regex("""android:name\s*=\s*"${Regex.escape(lib)}"""").containsMatchIn(it.value)
            }
            assertTrue(
                "<uses-native-library android:name=\"$lib\" .../> is missing from " +
                    "AndroidManifest.xml. Android 12+ (targetSdk 31+) will not resolve a vendor " +
                    "native library the manifest does not name, so libQnnHtp.so's FastRPC " +
                    "transport fails to load and the HTP never comes up — with no exception, no " +
                    "crash and no log the app can see. It cost a device round trip on the spike. " +
                    "Declared uses-native-library entries found: " +
                    elements.map { it.value.replace("\n", " ") },
                element != null
            )
            assertTrue(
                "the $lib declaration must be inside <application>; a uses-native-library element " +
                    "placed at manifest top level is silently ignored by the platform.",
                element!!.range.first in appOpen until appClose
            )
            assertTrue(
                "$lib must be declared android:required=\"false\". This APK installs on every " +
                    "device — Tensor, Exynos, MediaTek, and every Snapdragon without an exposed " +
                    "cDSP — and required=\"true\" makes Play refuse the install outright on all of " +
                    "them to protect a tier they were never going to run. Found: " +
                    element.value.replace("\n", " "),
                Regex("""android:required\s*=\s*"false"""").containsMatchIn(element.value)
            )
        }
    }

    /**
     * Every header under `app/src/main/cpp/include/QNN` carries *"Confidential and Proprietary —
     * Qualcomm Technologies, Inc."*. Compiling against them is what they are shipped for;
     * redistributing them is not permitted, and this repo has a public remote. They are fetched by
     * `tools/fetch_qnn_headers.py` on demand and never committed — this is the entry that makes
     * "never" mechanical rather than a matter of remembering.
     */
    @Test
    fun gitignoreExcludesTheProprietaryQnnHeaderTree() {
        val ignore = source(".gitignore")
        val entry = "app/src/main/cpp/include/QNN/"
        assertTrue(
            "the root .gitignore must contain the line \"$entry\" (uncommented). Without it, the " +
                "first `git add -A` after a header fetch stages ~230 Qualcomm-proprietary headers " +
                "into a repo with a public remote — a licence violation that is trivial to commit " +
                "and impossible to un-publish. The trailing slash is deliberate: it matches the " +
                "directory and everything under it, and never a same-named file.",
            ignore.lineSequence().any { it.trim() == entry }
        )
    }

    /**
     * THE Q10a CRASH, closed at this tier's own entry point (4.0, Q9b).
     *
     * `-DGGML_BACKEND_DL=ON` means the ggml backend registry starts **empty** and only
     * `WhisperNative.loadBackends` populates it. Until Q9b the sole caller was inside
     * `WhisperNativeBackend.load` — the CPU tiers' path — so the registry was populated as a
     * SIDE EFFECT of a component this tier never touches. Every 3.7 session loaded a CPU tier
     * first, so it always happened to be full; the npu tier is the first session shape that loads
     * no CPU tier, and every one of its sessions died at the VAD probe:
     *
     * ```
     * vadProbeInit -> whisper_vad_init_with_params -> make_buft_list        (whisper.cpp:5126)
     *   -> ggml_backend_dev_by_type(CPU) == nullptr                         (:1387)
     *   -> ggml_backend_dev_backend_reg(nullptr) -> GGML_ASSERT -> SIGABRT  (:1388)
     * ```
     *
     * **The ORDER is the invariant, not the presence.** `ensureLoaded()` placed anywhere below
     * `initMelOnly` leaves every statement in this file intact and still lets the mel loader —
     * and, on the refusal paths, `WhisperNativeBackend.load` — be the first native whisper call
     * to meet an empty registry. It is the first statement of `load` so that no native entry
     * reachable from this tier can ever be the one that finds it empty.
     */
    @Test
    fun theTierPopulatesTheBackendRegistryBeforeAnyNativeWhisperCall() {
        val ensure = liveOffsets(backend, "GgmlBackends.ensureLoaded()")
        assertEquals(
            "the tier must populate the ggml backend registry exactly once, on a live line, in " +
                "load(). Zero is the Q10a SIGABRT: an npu session loads no CPU tier, so nothing " +
                "else on its path has ever called loadBackends.",
            1,
            ensure.size
        )

        val melInit = liveOffsets(backend, "WhisperNative.initMelOnly(")
        val release = liveOffsets(backend, "releaseEverything()")
        assertTrue("the mel donor must be loaded on a live line", melInit.isNotEmpty())
        assertTrue("the re-load teardown must run on a live line", release.isNotEmpty())
        assertTrue(
            "ensureLoaded (${ensure.first()}) must precede initMelOnly (${melInit.first()}). " +
                "Presence is not the invariant: below it, the registry is still empty for every " +
                "native whisper call this tier makes, which is the crash unchanged.",
            ensure.first() < melInit.first()
        )
        assertTrue(
            "and it must be the FIRST statement — above even releaseEverything " +
                "(${release.first()}) — so no path out of load(), including the CPU fallback's " +
                "own WhisperNativeBackend.load, can reach native code before it",
            ensure.first() < release.first()
        )
    }

    /**
     * F2 (final review) — THE ASSET-SHAPE GUARD IS LOUD ON THE RIGHT TAG AND RETURNS AN ERROR.
     *
     * `loadGraphSlot`'s IO census compared the loaded graph against the planned asset and, on a
     * mismatch, emitted `LOGW` — tag `WE-NPU` — and **continued**. Two failures in one line:
     *
     *  - `WE-NPU` is invisible to `adb logcat -s WE-DIAG`, which run-book 9.2 makes the owner's
     *    only capture. Third instance of that trap on this branch.
     *  - continuing does not avoid a mysterious failure, it manufactures one: every downstream
     *    buffer size in Q3/Q4 was derived from these figures, so the session runs on sizing that no
     *    longer describes the asset and the symptom arrives later as a fluent and wrong transcript.
     *
     * It is also the FIRST guard a re-exported asset meets — the model-lab lineup differs here by
     * construction — so it has to be both loud and structured: WE-DIAG for the human, a returned
     * stage error for the tier, which routes through `fallBackToCpuTier` to `npu: unavailable`.
     */
    @Test
    fun theIoCensusMismatchIsReportedOnWeDiagAndRefusesTheAsset() {
        val slot = functionBody(cpp, "std::string loadGraphSlot(")

        assertTrue(
            "the mismatch must be reported through the WE-DIAG seam (LOGDIAG/LOGDIAGE), not the " +
                "WE-NPU tag the owner never captures",
            liveOffsets(slot, "LOGDIAGE(\"%s: IO DIFFERS FROM THE PLANNED ASSET").isNotEmpty()
        )
        assertTrue(
            "and the WE-NPU form must not come back — it is one macro name away and looks " +
                "identical in review",
            liveOffsets(slot, "LOGW(\"%s: IO DIFFERS").isEmpty()
        )

        val report = liveOffsets(slot, "IO DIFFERS FROM THE PLANNED ASSET")
        val refuse = liveOffsets(slot, "io: differs from expected census")
        assertTrue("the census mismatch must be reported on a live line", report.isNotEmpty())
        assertTrue(
            "and it must RETURN a stage error naming the `io: differs from expected census` class. " +
                "Logging alone leaves nativeInit to continue on sizing that no longer describes " +
                "the asset, which is the fluent-and-wrong transcript this seam exists to refuse.",
            refuse.isNotEmpty()
        )
        assertTrue(
            "the report (${report.first()}) comes before the refusal (${refuse.first()}): the " +
                "human-readable numbers must be on the wire even when the tier declines, because " +
                "the returned string is what the card shows and the log is what the model lab acts on",
            report.first() < refuse.first()
        )
    }

    // ================================================================ 4.1 L1 — THE ARMING EPOCH
    //
    // Final review F4/I1. The QNN session is a PROCESS-GLOBAL, `nativeInit` releases any existing
    // one, and `LocalWhisperEngine.shutdown()` QUEUES the stale backend's release onto that
    // engine's own executor while the replacement loads on a different one. Source order does not
    // order two executors' effects, so an `npu → npu-class` rebuild has an interleaving in which
    // the stale release destroys the session the new init just built — leaving a backend with
    // `armed = true` and nothing behind it.
    //
    // The fix is IDENTITY, not ordering, and that distinction is the branch's second named lesson.
    // Everything below therefore pins two different kinds of claim, and they are not
    // interchangeable: that the epoch EXISTS and is threaded end to end (presence), and that each
    // of the four places it is read runs BEFORE the thing it is supposed to protect (order). A
    // guard that runs after the teardown is not a guard.

    /**
     * **The guard itself, and the one the brief names as this task's red.**
     *
     * `nativeRelease` used to take nothing, which is precisely why F4 was unfixable by rearranging
     * statements: a release with no argument cannot say which session it means, so the only
     * question it can answer is *"is there a session?"* — and there always is one, the successor's.
     *
     * Four claims, each defeating a different edit:
     *  - the **parameter** exists. Without it there is nothing to compare and the rest is vacuous;
     *  - the comparison **precedes** `releaseLocked()`. Both statements survive the swap, the code
     *    compiles, and a guard evaluated after the teardown reports on a session it has just
     *    destroyed. This is the ninth-and-something presence-vs-ORDER pin on this branch;
     *  - **epoch 0 is refused explicitly**. `0` is the value `nativeEpoch()` answers when nothing is
     *    live and the value an unarmed `NpuWhisperBackend` holds; without the `want == 0` arm, the
     *    first release after a teardown would compare `0 != 0` and tear down the *next* session;
     *  - the refusal is **reported on WE-DIAG**. The owner's only capture is
     *    `adb logcat -s WE-DIAG`, and a refusal nobody can see is indistinguishable on device from
     *    a release that did nothing because there was nothing to release.
     */
    @Test
    fun nativeReleaseIsGuardedByTheArmingEpoch() {
        val body = functionBody(cpp, "Java_com_whispereverywhere_npu_QnnAsrNative_nativeRelease(")
        assertTrue(
            "nativeRelease must TAKE the epoch — `jlong epoch` in its parameter list, on a live " +
                "line. A release with no argument cannot name the session it means; it can only " +
                "ask whether a session exists, and after an npu→npu-class rebuild the session " +
                "that exists is the SUCCESSOR's. Found: " + liveLines(body, "jlong"),
            liveOffsets(body, "jlong epoch").isNotEmpty()
        )
        val compare = liveOffsets(body, "want != g.epoch")
        val teardown = liveOffsets(body, "releaseLocked();")
        assertTrue(
            "nativeRelease must COMPARE the caller's epoch against the live one on a live line. " +
                "Presence is asserted separately from ordering because \"A precedes B\" is " +
                "trivially true when there is no A.",
            compare.isNotEmpty()
        )
        assertTrue(
            "nativeRelease must still CALL releaseLocked() on a live line — a guard that refuses " +
                "everything is not a teardown, and the tier's whole memory budget rests on this " +
                "call happening for the session that owns it.",
            teardown.isNotEmpty()
        )
        assertTrue(
            "the epoch comparison (${compare.first()}) must run BEFORE releaseLocked() " +
                "(${teardown.first()}). ORDER, not presence: both statements survive the swap, it " +
                "compiles, and a guard evaluated after the teardown has already destroyed the " +
                "session it was asked about — which is the F4 defect with a passing test beside it.",
            compare.first() < teardown.first()
        )
        assertTrue(
            "epoch 0 must be refused EXPLICITLY (`want == 0`) on a live line. 0 is what " +
                "nativeEpoch() answers when nothing is live and what an unarmed backend holds; " +
                "without this arm the comparison `0 != 0` reads as a match and the first stale " +
                "release after a teardown destroys the NEXT session instead.",
            liveOffsets(body, "want == 0").isNotEmpty()
        )
        val bail = liveOffsets(body, "return;")
        assertTrue(
            "the refusal must RETURN on a live line, before the teardown. Logging a refusal and " +
                "then tearing the session down anyway is the defect wearing the guard's clothes.",
            bail.isNotEmpty() && bail.first() < teardown.first()
        )
        assertTrue(
            "the refusal must be reported through LOGDIAG — the owner's only capture is " +
                "`adb logcat -s WE-DIAG`, and under WE-NPU a refused release is indistinguishable " +
                "from a release that found nothing to do. Third instance of that trap on this " +
                "branch. Found: " + liveLines(body, "LOGDIAG"),
            liveOffsets(body, "LOGDIAG(\"nativeRelease: epoch").isNotEmpty()
        )
    }

    /**
     * **An epoch is a receipt for a live session, not for an attempt — and it is never reused.**
     *
     * Two failures, both one line, both silent:
     *  - **issued too early.** `nativeInit` has ten failure paths and every one of them calls
     *    `releaseLocked()` and returns. An epoch assigned above any of them is handed to a backend
     *    whose session does not exist, and that backend's later release then names — and destroys —
     *    whatever session happens to be live by then;
     *  - **reused.** If the counter lived in `NpuState` it would sit one plausible line away from
     *    the `= 0` resets in `releaseLocked()`, and a re-issued epoch is a *stale* release that
     *    matches the *live* session and is obeyed. That is the original defect, restored, with the
     *    guard in place and passing. So the counter is process state, held outside `g`, and this
     *    pin counts its live mentions: the declaration and the one issue site, and nothing else.
     */
    @Test
    fun theArmingEpochIsIssuedOnlyByASuccessfulInitAndIsNeverReused() {
        val init = functionBody(cpp, "Java_com_whispereverywhere_npu_QnnAsrNative_nativeInit(")
        val issue = liveOffsets(init, "g.epoch = nextEpoch++;")
        assertEquals(
            "nativeInit must issue the epoch on exactly one live line, as `g.epoch = nextEpoch++;`",
            1,
            issue.size
        )
        val released = liveOffsets(init, "releaseLocked();")
        assertTrue(
            "nativeInit must still release on its failure paths, on live lines",
            released.size >= 2
        )
        assertTrue(
            "the epoch (${issue.first()}) must be issued AFTER the LAST failure-path " +
                "releaseLocked() (${released.last()}). Above it, a stage that declines still hands " +
                "out an epoch — and the backend holding it can then destroy a session it never " +
                "owned. The receipt is for a session, not for an attempt.",
            issue.first() > released.last()
        )
        val ok = liveOffsets(init, "LOGI(\"nativeInit OK")
        assertTrue("nativeInit must log its success on a live line", ok.isNotEmpty())
        assertTrue(
            "and the epoch must be issued BEFORE that success log (${ok.first()}) — the log is the " +
                "line the device session reads to learn which epoch this arm produced, so it " +
                "cannot precede the number it reports on.",
            issue.first() < ok.first()
        )
        assertTrue(
            "the counter must be declared at namespace scope as `uint64_t nextEpoch = 1;` on a " +
                "live line — 1 so that 0 can mean \"no session\" and nothing else. Found: " +
                liveLines(cpp, "nextEpoch = 1"),
            liveOffsets(cpp, "uint64_t nextEpoch = 1;").isNotEmpty()
        )
        val release = functionBody(cpp, "void releaseLocked()")
        assertTrue(
            "releaseLocked must NOT touch nextEpoch on any live line. It zeroes six other pieces " +
                "of session state on adjacent lines, so a reset here is the most plausible edit in " +
                "the file — and it re-issues an epoch a stale backend may still be holding, which " +
                "makes its release match the LIVE session and be obeyed. Found: " +
                liveLines(release, "nextEpoch"),
            liveOffsets(release, "nextEpoch").isEmpty()
        )
        assertEquals(
            "exactly TWO live mentions of nextEpoch in the whole file: its declaration and the one " +
                "site that consumes it. A third is either a second issue site or a reset, and both " +
                "of those are the same bug. Found: " + liveLines(cpp, "nextEpoch"),
            2,
            liveOffsets(cpp, "nextEpoch").size
        )
    }

    /**
     * The two ends of the epoch's lifetime: teardown forgets it, and Kotlin can read the live one.
     *
     *  - `releaseLocked` must zero `g.epoch`. Without that, a torn-down session's number stays
     *    matchable and the *next* release — from any instance — is accepted against a session that
     *    no longer exists, which is a double teardown of whatever was armed in between.
     *  - `nativeEpoch` must read under the **same mutex** every other entry point takes. An unlocked
     *    read is a torn read of the value the whole guard is keyed on, and it would be correct
     *    99.99% of the time on the one device this tier runs on.
     *  - and it must be a **reader**. An entry point that could assign `g.epoch` is a second issue
     *    site reachable from Kotlin, which is the reuse hazard arriving through the front door.
     */
    @Test
    fun teardownForgetsTheEpochAndTheLiveOneIsReadableUnderTheSameMutex() {
        val release = functionBody(cpp, "void releaseLocked()")
        assertTrue(
            "releaseLocked must zero g.epoch on a live line, beside the other session state it " +
                "clears. A number that outlives its session is a name a later release can still " +
                "match.",
            liveOffsets(release, "g.epoch = 0;").isNotEmpty()
        )
        val reader = functionBody(cpp, "Java_com_whispereverywhere_npu_QnnAsrNative_nativeEpoch(")
        assertTrue(
            "nativeEpoch must take the session mutex on a live line — the same one nativeInit and " +
                "nativeRelease hold. It is read on every segment and written on every arm, and an " +
                "unlocked read of the value the guard is keyed on is right almost always.",
            liveOffsets(reader, "std::lock_guard<std::mutex> lock(g.mu);").isNotEmpty()
        )
        assertTrue(
            "nativeEpoch must answer g.epoch itself on a live line",
            liveOffsets(reader, "g.epoch").isNotEmpty()
        )
        assertTrue(
            "and it must never ASSIGN it. nativeEpoch is the only way Kotlin learns its epoch; if " +
                "it could also set one, the process would have a second issue site reachable from " +
                "outside nativeInit and the \"never reused\" property would be a comment. Found: " +
                liveLines(reader, "g.epoch ="),
            liveOffsets(reader, "g.epoch =").isEmpty()
        )
    }

    /**
     * The Kotlin declarations — the only place the two sides of this JNI change can be compared
     * before a device runs them.
     *
     * A `jlong` parameter added native-side while Kotlin still declares `nativeRelease()` links
     * (the JNI name is unmangled for a non-overloaded method), reaches the guard with whatever
     * happens to be in the argument register, and refuses or accepts at random. There is no
     * compiler on either side of that seam.
     *
     * The negative is the sharper half: the **zero-argument form must be gone**. Leaving it as an
     * overload beside the new one compiles, and every existing caller keeps calling the unguarded
     * release — the fix present, landed, and bypassed.
     *
     * And `nativeInit`'s signature is pinned UNCHANGED. Widening its return to carry the epoch as
     * well as the stage error is the obvious alternative and it is the wrong one: it turns a string
     * whose whole purpose is to name a failed stage into a value with two readings, one of which
     * nobody reads.
     */
    @Test
    fun theKotlinSeamDeclaresTheEpochAccessorsAndLeavesNativeInitsStageErrorAlone() {
        assertTrue(
            "QnnAsrNative must declare `external fun nativeRelease(epoch: Long)` on a live line. " +
                "Found: " + liveLines(seam, "external fun nativeRelease"),
            liveOffsets(seam, "external fun nativeRelease(epoch: Long)").isNotEmpty()
        )
        assertTrue(
            "the zero-argument `external fun nativeRelease()` must be GONE from every live line. " +
                "Kept as an overload it compiles, links to the same unmangled JNI symbol, and " +
                "leaves every existing caller invoking the unguarded release — the fix landed and " +
                "bypassed in one edit.",
            liveOffsets(seam, "external fun nativeRelease()").isEmpty()
        )
        assertTrue(
            "QnnAsrNative must declare `external fun nativeEpoch(): Long` on a live line — it is " +
                "the only way Kotlin can learn the epoch it was armed with.",
            liveOffsets(seam, "external fun nativeEpoch(): Long").isNotEmpty()
        )
        assertTrue(
            "nativeInit must still ANSWER a String — `\"\"` or `\"stage: detail\"`. Widening it to " +
                "carry the epoch too is how a stage error becomes a value with two readings and " +
                "the failure text stops being read. THE NAMED TRIGGER: task L2 adds five scalars " +
                "to this declaration (melBins, decLayers, heads, vocab, maxPositions), so this " +
                "needle goes red THERE by construction — re-spell it with L2's parameter list and " +
                "keep the `: String`, because the claim is about the RETURN, not the arity. Found: " +
                liveLines(seam, "external fun nativeInit"),
            liveOffsets(
                seam,
                "external fun nativeInit(encoderPath: String, decoderPath: String, libDir: String): String"
            ).isNotEmpty()
        )
    }

    /**
     * **The backend records its epoch before it declares itself armed** — ORDER, and the window is
     * real rather than theoretical.
     *
     * `armed = true` is what makes this instance a live participant; `armedEpoch` is what lets its
     * release name the session it participates in. Between the two statements the instance is a
     * backend that will call `nativeRelease(0L)` — the unguarded shape, refused native-side only
     * because of the `want == 0` arm pinned above, and a shape that has no business existing at all.
     *
     * The other half is the clear: `releaseNpuResources` must put it back to `0L`. An instance that
     * kept its epoch after teardown would answer the transcribe guard with a number matching a
     * session it has already released.
     */
    @Test
    fun theBackendRecordsItsEpochBeforeItArmsItself() {
        assertTrue(
            "NpuWhisperBackend must declare `private var armedEpoch: Long = 0L` — 0L is \"this " +
                "instance owns no session\", which is the value the native guard refuses outright. " +
                "Found: " + liveLines(backend, "armedEpoch: Long"),
            backend.contains("private var armedEpoch: Long = 0L")
        )
        val load = kotlinMemberBody(
            backend, "override fun load(modelPath: String, companionPath: String?): Long ="
        )
        val armInit = liveOffsets(load, "QnnAsrNative.nativeInit(")
        val record = liveOffsets(load, "armedEpoch = QnnAsrNative.nativeEpoch()")
        val armed = liveOffsets(load, "armed = true")
        assertTrue("load must call nativeInit on a live line", armInit.isNotEmpty())
        assertTrue(
            "load must record the epoch it was armed with, from nativeEpoch(), on a live line",
            record.isNotEmpty()
        )
        assertTrue("load must set armed on a live line", armed.isNotEmpty())
        assertTrue(
            "the epoch must be read AFTER nativeInit (${armInit.first()}) returned — before it, " +
                "nativeEpoch() answers the PREVIOUS session's number or 0, and the instance would " +
                "spend its whole life holding a name that was never its own. Found at " +
                "${record.first()}.",
            record.first() > armInit.first()
        )
        assertTrue(
            "and BEFORE `armed = true` (${armed.first()}). ORDER: between those two statements " +
                "this instance is armed with epoch 0 — a backend whose release names no session, " +
                "which is exactly the unguarded shape this task removed.",
            record.first() < armed.first()
        )
        assertEquals(
            "load() must read the epoch EXACTLY ONCE. The count is the invariant — an arm records " +
                "the session it created, it does not re-ask — and it is also what makes " +
                "kotlinMemberBody's indent rule load-bearing rather than decorative: load() is " +
                "expression-bodied, so under the old fixed \"\\n    }\\n\" delimiter its \"body\" " +
                "ran through the whole of `transcribe`, which reads nativeEpoch() too. Every " +
                "ordering claim above would then have been answered partly by a neighbour's code. " +
                "Found: " + liveLines(load, "QnnAsrNative.nativeEpoch()"),
            1,
            liveOffsets(load, "QnnAsrNative.nativeEpoch()").size
        )
        val teardown = kotlinMemberBody(backend, "private fun releaseNpuResources() {")
        assertTrue(
            "releaseNpuResources must clear armedEpoch back to 0L on a live line — an instance " +
                "that kept its epoch after teardown answers the transcribe guard with the name of " +
                "a session it has already released.",
            liveOffsets(teardown, "armedEpoch = 0L").isNotEmpty()
        )
        // THE KOTLIN HALF OF "AN EPOCH HAS EXACTLY ONE ISSUE SITE" (L1 review, I1). Native pins this
        // property TWICE — nativeEpoch may not assign g.epoch, and nextEpoch has exactly two live
        // mentions in the whole file — and until this line the Kotlin side pinned it nowhere. The
        // count above is scoped to load(), so a "defensive" re-read anywhere ELSE in the class
        // passed all seven of this task's pins; MEASURED as row M1, which survived at 138/1539/0.
        assertEquals(
            "exactly TWO live `armedEpoch =` sites in the whole file: the record in load() and the " +
                "clear in releaseNpuResources(). A third is a second issue site, and it is not a " +
                "cosmetic edit — a stale instance that re-adopts the LIVE epoch as its own then " +
                "names the successor's session CORRECTLY, so its release is obeyed and F4 is back " +
                "in full with the guard present, threaded and green. WHEN THIS MATTERS MOST: L8, " +
                "which routes a second npu-class tier and is the named next editor of this class — " +
                "an npu→npu-turbo switch is the first transition in this app's history where a " +
                "stale instance and a live session of a DIFFERENT model exist at the same time. " +
                "Found: " + liveLines(backend, "armedEpoch ="),
            2,
            liveOffsets(backend, "armedEpoch =").size
        )
    }

    /**
     * **The release names this instance's own session, at one site, and does not load the library
     * for a session that never existed.**
     *
     * Two things in one function, and they are the same decision seen from two sides.
     *
     * *The epoch is passed.* One call site in the whole file, so there is no second, unguarded
     * spelling of the teardown to drift.
     *
     * *And the call is skipped when nothing was ever armed* — Q6 M1. `fallBackToCpuTier` runs
     * `releaseNpuResources()` first, and the cheapest refusal in `load` is `mel-donor`: no installed
     * 80-bin model, reached **before** anything native is touched. Every session on a device with
     * no ggml model installed took that path and then `dlopen`ed `libqnnasr.so` on its way out —
     * ~25 MiB of Qualcomm runtime mapped, in a `runCatching`, to release a session that was never
     * created. `armedEpoch != 0L || melCtx != 0L` is the whole test for "did this instance ever
     * touch anything", and it must run BEFORE the touch it guards.
     */
    @Test
    fun theBackendsReleaseNamesItsOwnSessionAndSkipsTheLibraryItNeverLoaded() {
        assertEquals(
            "exactly one live `QnnAsrNative.nativeRelease(` site in the whole file. A second " +
                "spelling of the teardown is a second chance to omit the epoch. Found: " +
                liveLines(backend, "QnnAsrNative.nativeRelease("),
            1,
            liveOffsets(backend, "QnnAsrNative.nativeRelease(").size
        )
        val teardown = kotlinMemberBody(backend, "private fun releaseNpuResources() {")
        val call = liveOffsets(teardown, "QnnAsrNative.nativeRelease(armedEpoch)")
        assertTrue(
            "releaseNpuResources must pass armedEpoch to nativeRelease on a live line — passing " +
                "0L, or a fresh nativeEpoch() read, would name the LIVE session rather than this " +
                "instance's own, which is the F4 teardown with an argument added to it. Found: " +
                liveLines(teardown, "nativeRelease("),
            call.isNotEmpty()
        )
        val guard = liveOffsets(teardown, "if (armedEpoch != 0L || melCtx != 0L)")
        assertTrue(
            "the native touch must be guarded by `if (armedEpoch != 0L || melCtx != 0L)` on a " +
                "live line (Q6 M1). Without it the mel-donor refusal — the cheapest refusal in " +
                "load(), and the one every device with no installed ggml model hits on every " +
                "session — dlopens libqnnasr.so on its way out for a session that never existed.",
            guard.isNotEmpty()
        )
        assertTrue(
            "the guard (${guard.first()}) must precede the native call (${call.first()}). ORDER, " +
                "and the same order as every other guard in this file: a check that runs after the " +
                "library has been loaded has not avoided loading it.",
            guard.first() < call.first()
        )
    }

    /**
     * **`transcribe` refuses a session that a newer arm replaced, before it computes anything.**
     *
     * This is the half of the epoch that is about *fluent wrong text* rather than about a crash.
     * The QNN session is a process-global: a stale `NpuWhisperBackend` whose session has been
     * replaced by a different tier's would encode its mel into — and decode its tokens out of —
     * **another model's session**. Nothing fails. The transcript is fluent, confident, and produced
     * by a model the caller did not select, which is the worst failure shape this tier has.
     *
     * Three ordering claims, and each is a one-line move:
     *  - **inside the gate, after the fallback short-circuit.** An instance that has already fallen
     *    back holds no epoch and must not consult one;
     *  - **before `pcmToMel`.** A refusal taken after the mel has been computed has paid for the
     *    segment it is about to hand to the CPU tier anyway — and, worse, `pcmToMel` mutates the
     *    shared mel context, so a check below it is a check taken after the side effect;
     *  - **guarded on `armedEpoch != 0L`.** The short-circuit keeps an unarmed instance from
     *    touching `QnnAsrNative` at all, which is the same Q6 M1 property as the release path.
     */
    @Test
    fun transcribeRefusesASessionThatANewerArmReplaced() {
        val body = kotlinMemberBody(
            backend,
            "override fun transcribe(ctx: Long, samples: FloatArray, lang: String?, useVad: Boolean): String {"
        )
        val gate = liveOffsets(body, "NativeComputeGate.serialized {")
        val shortCircuit = liveOffsets(body, "fallbackBackend?.let")
        val guard = liveOffsets(body, "if (armedEpoch != 0L)")
        val read = liveOffsets(body, "QnnAsrNative.nativeEpoch()")
        val session = liveOffsets(body, "if (!armed ||")
        val mel = liveOffsets(body, "WhisperNative.pcmToMel(")
        assertTrue("transcribe must take the gate on a live line", gate.isNotEmpty())
        assertTrue("transcribe must short-circuit to the fallback on a live line", shortCircuit.isNotEmpty())
        assertTrue(
            "transcribe must guard the epoch read on `if (armedEpoch != 0L)` on a live line — an " +
                "instance that never armed must not touch QnnAsrNative to find that out.",
            guard.isNotEmpty()
        )
        assertTrue(
            "transcribe must read the live epoch through QnnAsrNative.nativeEpoch() on a live line",
            read.isNotEmpty()
        )
        assertTrue("transcribe must still refuse an unarmed session on a live line", session.isNotEmpty())
        assertTrue("transcribe must still compute the mel on a live line", mel.isNotEmpty())
        assertTrue(
            "the epoch check (${guard.first()}) must sit INSIDE the gate (${gate.first()}) and " +
                "AFTER the fallback short-circuit (${shortCircuit.first()}): an instance that has " +
                "already fallen back holds no epoch, and the routing state may only be read under " +
                "the hold.",
            gate.first() < guard.first() && shortCircuit.first() < guard.first()
        )
        assertTrue(
            "and it must be transcribe's FIRST act after that short-circuit — above the armed/" +
                "buffers check (${session.first()}) and above pcmToMel (${mel.first()}). ORDER: " +
                "pcmToMel REPLACES the shared mel context's internal state, so a check taken below " +
                "it is a check taken after the side effect, on a segment already paid for. Found " +
                "at ${guard.first()}.",
            guard.first() < session.first() && read.first() < mel.first()
        )
        assertTrue(
            "the refusal must route through the tier's own funnel as stage `epoch`, so the user's " +
                "utterance still runs — on the CPU tier — and the card names the stage that " +
                "declined. Live fallBackAndRun lines: " + liveLines(body, "fallBackAndRun("),
            liveOffsets(body, "\"epoch\",").isNotEmpty()
        )
        assertTrue(
            "and the detail must name BOTH epochs — this instance's and the live one. \"replaced\" " +
                "with no numbers cannot be checked against the WE-DIAG capture, which carries " +
                "`nativeInit: session armed with epoch N` and the refusal's own two numbers.",
            liveOffsets(body, "sessionReplacedDetail(armedEpoch,").isNotEmpty()
        )
    }
}
