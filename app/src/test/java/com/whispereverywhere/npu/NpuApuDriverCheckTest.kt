package com.whispereverywhere.npu

import com.whispereverywhere.data.local.PreferencesManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * THE MEDIATEK DRIVER CHECK'S STORED VERDICT (P2 — the gate on the row; design
 * `docs/superpowers/specs/2026-09-24-mediatek-apu-tier-design.md` §2.3 items 1–3), executed where
 * it is pure and pinned where it is Android.
 *
 * Executed: the record's round trip through the store's own reader, the strictness of that
 * reader, the reuse rule (fingerprint, build, major), the probe wrapper over a fake probe —
 * including the throw a build without `liblitertasr.so` produces — the dispatch directory's one
 * name, the diag line, and the flow "unknown until probed" is read from.
 *
 * Pinned as source: the app's half, which no JVM test can run (it is an `Application`, and its
 * probe loads `liblitertasr.so`) — MediaTek rows only, a stored verdict reused before any probe,
 * the probe on a thread of its own, the verdict published and persisted, and the dispatch
 * directory taken from its one home. `WhisperEverywhereApp.kt` and `NpuApuDriverCheck.kt` are both
 * in `sourcePinnedInputs` because this class reads them.
 */
class NpuApuDriverCheckTest {

    @After
    fun forgetTheVerdict() {
        NpuApuDriverCheck.resetToUnknownForTest()
    }

    private val tabFingerprint =
        "samsung/gts10pxx/gts10p:14/UP1A.231005.007/X828USQU1AXH1:user/release-keys"

    private fun verdict(
        refusal: String? = null,
        fingerprint: String = tabFingerprint,
        appBuild: Int = 112,
        wantMajor: Int = 8,
        probedAtMs: Long = 1_790_000_000_000L,
    ) = NpuApuVerdict(fingerprint, appBuild, wantMajor, refusal, probedAtMs)

    // ------------------------------------------------------------------ the store's round trip

    @Test
    fun aVerdictRoundTripsThroughTheStoresOwnReaderPassAndRefusalAlike() {
        for (original in listOf(
            verdict(refusal = null),
            verdict(refusal = "adapter-missing"),
            verdict(refusal = "driver-major-9-want-8"),
            verdict(refusal = "runtime: UnsatisfiedLinkError: dlopen failed: \"libLiteRt.so\" not found"),
        )) {
            val store = mutableMapOf<String, String>()
            store[PreferencesManager.KEY_NPU_APU_VERDICT] = original.encode()
            val read = PreferencesManager.readNpuApuVerdict { key, default -> store[key] ?: default }
            assertEquals("the record the check wrote is the record the next process reads", original, read)
        }
    }

    @Test
    fun nothingStoredReadsAsNoVerdictWhichMeansProbeAgain() {
        assertNull(PreferencesManager.readNpuApuVerdict { _, default -> default })
    }

    @Test
    fun aRecordThatHalfParsesIsNoRecordAtAll() {
        val good = verdict(refusal = "adapter-missing").encode()
        val broken = listOf(
            "",
            "not json",
            "[]",
            good.replace("\"format\":1", "\"format\":2"),
            good.replace("\"appBuild\":112", "\"appBuild\":\"112\""),
            good.replace("\"refusal\":\"adapter-missing\"", "\"refusal\":\"\""),
            good.replace("\"refusal\":\"adapter-missing\"", "\"refusal\":7"),
            good.replace(",\"probedAtMs\":1790000000000", ""),
            good.replace("\"fingerprint\":\"$tabFingerprint\"", "\"fingerprint\":\"\""),
        )
        for (text in broken) {
            assertNull("'$text' must read as no record — probe again, never half a verdict", NpuApuVerdict.decode(text))
        }
        assertEquals("and the good one reads back", verdict(refusal = "adapter-missing"), NpuApuVerdict.decode(good))
    }

    // ------------------------------------------------------------------ the reuse rule

    @Test
    fun aStoredVerdictIsReusedOnlyForTheSameRomTheSameBuildAndTheSameMajor() {
        val stored = verdict(refusal = null)
        assertSame(
            "same ROM, same build, same major: the stored verdict answers, no probe",
            stored,
            NpuApuDriverCheck.reusableOrNull(stored, tabFingerprint, 112, 8)
        )
        assertNull(
            "an OTA moved the fingerprint: the driver may be another one — probe again",
            NpuApuDriverCheck.reusableOrNull(stored, "$tabFingerprint.OTA", 112, 8)
        )
        assertNull(
            "a new build: its manifest and packaging decide whether the adapter loads — probe again",
            NpuApuDriverCheck.reusableOrNull(stored, tabFingerprint, 113, 8)
        )
        assertNull(
            "a census that wants another major: the old verdict answered a different question",
            NpuApuDriverCheck.reusableOrNull(stored, tabFingerprint, 112, 9)
        )
        assertNull("nothing stored: probe", NpuApuDriverCheck.reusableOrNull(null, tabFingerprint, 112, 8))
        val refused = verdict(refusal = "adapter-missing")
        assertSame(
            "a refusal is reused on the same terms — the answer cannot change until one of the three does",
            refused,
            NpuApuDriverCheck.reusableOrNull(refused, tabFingerprint, 112, 8)
        )
    }

    // ------------------------------------------------------------------ the probe wrapper

    @Test
    fun theProbeIsHandedTheDispatchDirectoryTheLibDirAndTheFamilysMajor() {
        val files = File("C:/fake/files")
        var asked: Triple<String, String, Int>? = null
        val passed = NpuApuDriverCheck.probeNow(
            probe = { dispatchDir, libDir, wantMajor -> asked = Triple(dispatchDir, libDir, wantMajor); "" },
            filesDir = files,
            libDir = "/data/app/lib/arm64",
            fingerprint = tabFingerprint,
            appBuild = 112,
            wantMajor = 8,
            clock = { 42L },
        )
        assertEquals(
            Triple(File(files, "litert_dispatch").absolutePath, "/data/app/lib/arm64", 8),
            asked
        )
        assertEquals("an empty answer is a pass", verdict(refusal = null, probedAtMs = 42L), passed)
        assertTrue(passed.passed)
    }

    @Test
    fun aRefusalKeepsTheProbesReasonWithoutItsPrefix() {
        for (reason in listOf(
            "adapter-missing",
            "adapter-libneuron_adapter_mgvi.so",
            "driver-version-unreadable",
            "driver-major-9-want-8",
            "runtime: dlopen libLiteRt.so: not found",
        )) {
            val refused = NpuApuDriverCheck.probeNow(
                probe = { _, _, _ -> "probe: $reason" },
                filesDir = File("f"), libDir = "l", fingerprint = tabFingerprint, appBuild = 112,
                wantMajor = 8, clock = { 1L },
            )
            assertEquals(reason, refused.refusal)
            assertFalse(refused.passed)
        }
    }

    @Test
    fun aProbeThatThrowsIsANamedRefusalNeverACrash() {
        // A build whose CMake skipped liblitertasr.so: the first touch throws
        // UnsatisfiedLinkError, every later one NoClassDefFoundError. Both mean "no tier".
        for (cause in listOf<Throwable>(
            UnsatisfiedLinkError("dlopen failed: library \"liblitertasr.so\" not found"),
            NoClassDefFoundError("com/whispereverywhere/npu/LiteRtAsrNative"),
        )) {
            val refused = NpuApuDriverCheck.probeNow(
                probe = { _, _, _ -> throw cause },
                filesDir = File("f"), libDir = "l", fingerprint = tabFingerprint, appBuild = 112,
                wantMajor = 8, clock = { 1L },
            )
            assertFalse("${cause.javaClass.simpleName} is not a pass", refused.passed)
            assertTrue(
                "the refusal names what failed: ${refused.refusal}",
                refused.refusal!!.startsWith("runtime: ${cause.javaClass.simpleName}: ")
            )
        }
    }

    @Test
    fun theDispatchDirectoryHasOneNameUnderFilesDir() {
        assertEquals("litert_dispatch", NpuApuDriverCheck.DISPATCH_DIR_NAME)
        assertEquals(File("root", "litert_dispatch"), NpuApuDriverCheck.dispatchDir(File("root")))
    }

    // ------------------------------------------------------------------ the process's verdict

    @Test
    fun theFlowIsUnknownUntilTheCheckPublishes() {
        assertNull("unknown until the check answers", NpuApuDriverCheck.verdict.value)
        val refused = verdict(refusal = "adapter-missing")
        NpuApuDriverCheck.publish(refused)
        assertSame("then the verdict the check published", refused, NpuApuDriverCheck.verdict.value)
    }

    // ------------------------------------------------------------------ the diag line

    @Test
    fun theVerdictLineSaysWhichVerdictAndWhereItCameFrom() {
        assertEquals(
            "apu: verdict=pass want=8 source=probe build=112 probedAtMs=1790000000000",
            NpuDiag.apuVerdict(verdict(refusal = null), reused = false)
        )
        assertEquals(
            "apu: verdict=refuse(adapter-missing) want=8 source=stored build=112 probedAtMs=1790000000000",
            NpuDiag.apuVerdict(verdict(refusal = "adapter-missing"), reused = true)
        )
        assertFalse(
            "the line never carries the fingerprint — a verdict, a major, a build and a time",
            NpuDiag.apuVerdict(verdict(), reused = true).contains("samsung/")
        )
        val diag = read("src/main/java/com/whispereverywhere/npu/NpuDiag.kt")
        assertEquals(
            "the `apu: verdict=` prefix is ONE contiguous literal on one live line — the grep a " +
                "field report starts from",
            1,
            liveLineCount(diag, "\"apu: verdict=")
        )
    }

    // ------------------------------------------------------------------ the app's half, as source

    private fun read(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            for (candidate in listOf(File(dir, relative), File(dir, "app/$relative"))) {
                if (candidate.isFile) return candidate.readText().replace("\r\n", "\n")
            }
            dir = dir.parentFile
        }
        throw AssertionError("cannot locate $relative from ${System.getProperty("user.dir")}")
    }

    private fun liveLineCount(scope: String, needle: String): Int =
        scope.lineSequence().count { line ->
            val t = line.trimStart()
            !(t.startsWith("//") || t.startsWith("/*") || t.startsWith("*")) && line.contains(needle)
        }

    private fun liveOffset(scope: String, needle: String): Int {
        var at = 0
        for (line in scope.split("\n")) {
            val t = line.trimStart()
            if (!(t.startsWith("//") || t.startsWith("/*") || t.startsWith("*")) && line.contains(needle)) return at
            at += line.length + 1
        }
        return -1
    }

    private val app: String by lazy { read("src/main/java/com/whispereverywhere/WhisperEverywhereApp.kt") }

    private val settle: String by lazy {
        app.substringAfter("    private fun settleApuDriverVerdict() {").substringBefore("\n    }\n")
    }

    @Test
    fun onCreateStartsTheCheckAndOnlyAMediatekRowGetsPastItsFirstLines() {
        val onCreate = app.substringAfter("override fun onCreate() {").substringBefore("\n    }\n")
        assertEquals(
            "onCreate starts the check exactly once, wrapped so it can never cost the launch",
            1,
            liveLineCount(onCreate, "runCatching { settleApuDriverVerdict() }")
        )
        assertTrue(
            "after the managers it reads exist",
            liveOffset(onCreate, "preferencesManager = PreferencesManager(this)") <
                liveOffset(onCreate, "runCatching { settleApuDriverVerdict() }")
        )
        assertTrue("the settle function was found", settle.length in 1 until app.length)
        assertEquals(
            "no family: return — every off-census device leaves on the first line",
            1,
            liveLineCount(settle, "val family = npuSocFamily ?: return")
        )
        assertEquals(
            "not a MediaTek row: return — every Qualcomm device leaves on the second, and never " +
                "touches the LiteRT seam",
            1,
            liveLineCount(settle, "val needs = family.runtime as? NpuRuntimeNeeds.LiteRtMediatek ?: return")
        )
        assertTrue(
            "…and both returns come before anything else the function does",
            liveOffset(settle, "?: return") < liveOffset(settle, "Build.FINGERPRINT")
        )
    }

    @Test
    fun aStoredVerdictIsReusedBeforeAnyProbeAndTheProbeRunsOffMain() {
        assertEquals(
            "the stored record is judged by the one reuse rule, against THIS ROM, THIS build and " +
                "THIS family's major",
            1,
            liveLineCount(settle, "NpuApuDriverCheck.reusableOrNull(")
        )
        assertEquals("keyed on Build.FINGERPRINT", 1, liveLineCount(settle, "val fingerprint = Build.FINGERPRINT"))
        assertEquals("and on this build", 1, liveLineCount(settle, "val build = BuildConfig.VERSION_CODE"))
        val reuse = liveOffset(settle, "NpuApuDriverCheck.reusableOrNull(")
        val thread = liveOffset(settle, "val thread = Thread(")
        val probe = liveOffset(settle, "LiteRtAsrNative.nativeProbe(dispatchDir, lib, wantMajor)")
        assertTrue(
            "ORDER: reuse ($reuse) before the thread ($thread), and the probe ($probe) only inside it",
            reuse in 0 until thread && thread < probe
        )
        assertEquals("one probe call site in the whole app", 1, liveLineCount(app, "LiteRtAsrNative.nativeProbe("))
        assertEquals("a daemon thread of its own", 1, liveLineCount(settle, "thread.isDaemon = true"))
        assertEquals("…with a name a thread dump can read", 1, liveLineCount(settle, "\"npu-apu-driver-check\","))
        assertEquals(
            "the verdict is published on both routes — reused and probed",
            2,
            liveLineCount(settle, "NpuApuDriverCheck.publish(")
        )
        assertEquals(
            "and a probed one is persisted, device-locally, for the next process",
            1,
            liveLineCount(settle, "prefs.recordNpuApuVerdict(verdict)")
        )
        assertEquals(
            "each route says which verdict the process holds, on the house tag",
            2,
            liveLineCount(settle, "Log.i(NpuDiag.TAG, NpuDiag.apuVerdict(")
        )
    }

    @Test
    fun theDispatchDirectoryIsSpelledOnceAndTheAppNeverSpellsIt() {
        val check = read("src/main/java/com/whispereverywhere/npu/NpuApuDriverCheck.kt")
        assertEquals(
            "the name's one home: a constant, on exactly one live line",
            1,
            liveLineCount(check, "const val DISPATCH_DIR_NAME: String = \"litert_dispatch\"")
        )
        assertEquals(
            "…and the only live spelling of the name in that file",
            1,
            liveLineCount(check, "\"litert_dispatch\"")
        )
        assertEquals(
            "the probe's path is built from it, by the one function every later reader takes",
            1,
            liveLineCount(check, "fun dispatchDir(filesDir: File): File = File(filesDir, DISPATCH_DIR_NAME)")
        )
        assertEquals(
            "…which is what the probe is handed",
            1,
            liveLineCount(check, "probe(dispatchDir(filesDir).absolutePath, libDir, wantMajor)")
        )
        assertEquals(
            "and the app never spells the directory itself — it hands the check filesDir",
            0,
            liveLineCount(app, "litert_dispatch")
        )
    }

    @Test
    fun theFilesThisClassReadsAreInputsOfTheTestTask() {
        val gradle = read("build.gradle.kts")
        for (path in listOf(
            "\"src/main/java/com/whispereverywhere/npu/NpuApuDriverCheck.kt\",",
            "\"src/main/java/com/whispereverywhere/WhisperEverywhereApp.kt\",",
            "\"src/main/java/com/whispereverywhere/npu/NpuDiag.kt\",",
        )) {
            assertEquals("app/build.gradle.kts must list $path among sourcePinnedInputs", 1, liveLineCount(gradle, path))
        }
    }
}
