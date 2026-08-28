package com.whispereverywhere.npu

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
 * `src/main/cpp/qnn_asr.cpp`, `src/main/AndroidManifest.xml` and the root `.gitignore` are declared
 * as explicit inputs of the test task in `app/build.gradle.kts` — without that, an edit confined to
 * any of them leaves `:app:testDebugUnitTest` UP-TO-DATE and these guards pass against stale
 * evidence.
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

    private val cpp: String by lazy { source("src/main/cpp/qnn_asr.cpp") }

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
}
