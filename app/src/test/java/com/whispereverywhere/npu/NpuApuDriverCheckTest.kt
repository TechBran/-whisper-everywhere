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

    /** When the tablet's install last changed — `PackageInfo.lastUpdateTime` (P2-7, L3). */
    private val installedAt = 1_789_000_000_000L

    /** The tablet's key: this ROM, build 112, this install, major 8. */
    private val tabKey = NpuApuKey(tabFingerprint, 112, installedAt, 8)

    private fun verdict(
        refusal: String? = null,
        fingerprint: String = tabFingerprint,
        appBuild: Int = 112,
        wantMajor: Int = 8,
        probedAtMs: Long = 1_790_000_000_000L,
        appUpdatedAtMs: Long = installedAt,
    ) = NpuApuVerdict(fingerprint, appBuild, appUpdatedAtMs, wantMajor, refusal, probedAtMs)

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
            // (P2-7) FORMAT is 2 since the install-update key joined it: a format-1 record — the
            // one every P2a build wrote — cannot say which install took it, so it reads as none.
            good.replace("\"format\":2", "\"format\":1"),
            good.replace("\"format\":2", "\"format\":3"),
            good.replace(",\"appUpdatedAtMs\":$installedAt", ""),
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
        // (P2-7) The key is an NpuApuKey now, and it carries a fourth fact — the install's
        // lastUpdateTime — whose own rows are in aNewInstallOfTheSameVersionCodeProbesAgain.
        val stored = verdict(refusal = null)
        assertSame(
            "same ROM, same build, same install, same major: the stored verdict answers, no probe",
            stored,
            NpuApuDriverCheck.reusableOrNull(stored, tabKey)
        )
        assertNull(
            "an OTA moved the fingerprint: the driver may be another one — probe again",
            NpuApuDriverCheck.reusableOrNull(stored, tabKey.copy(fingerprint = "$tabFingerprint.OTA"))
        )
        assertNull(
            "a new build: its manifest and packaging decide whether the adapter loads — probe again",
            NpuApuDriverCheck.reusableOrNull(stored, tabKey.copy(appBuild = 113))
        )
        assertNull(
            "a census that wants another major: the old verdict answered a different question",
            NpuApuDriverCheck.reusableOrNull(stored, tabKey.copy(wantMajor = 9))
        )
        assertNull("nothing stored: probe", NpuApuDriverCheck.reusableOrNull(null, tabKey))
        val refused = verdict(refusal = "adapter-missing")
        assertSame(
            "a refusal is reused on the same terms — the answer cannot change until one of the four does",
            refused,
            NpuApuDriverCheck.reusableOrNull(refused, tabKey)
        )
    }

    /**
     * THE P2a REVIEW'S L3, first half: a stored refusal can outlive its cause when two builds share
     * a versionCode — and on the tablet every debug and internal-sharing build of a cycle does
     * (112, then 113). An `adapter-missing` stored by an earlier build would be inherited by a
     * later one that fixed it, and a stale REFUSAL never corrects itself (a stale pass does, at
     * `nativeInit`). So the install's `lastUpdateTime` is part of the key: every install and every
     * update re-probes once.
     */
    @Test
    fun aNewInstallOfTheSameVersionCodeProbesAgain() {
        val refused = verdict(refusal = "adapter-missing")
        assertNull(
            "the same versionCode, installed again (a new lastUpdateTime): probe again",
            NpuApuDriverCheck.reusableOrNull(refused, tabKey.copy(appUpdatedAtMs = installedAt + 60_000L))
        )
        assertEquals("the record carries the key it was taken under", tabKey, refused.key)
        assertEquals(
            "and a verdict built from a key IS that key's",
            tabKey,
            NpuApuVerdict(tabKey, "adapter-missing", 1L).key,
        )
        val store = mutableMapOf<String, String>()
        store[PreferencesManager.KEY_NPU_APU_VERDICT] = refused.encode()
        assertEquals(
            "…and the install time survives the store's round trip",
            installedAt,
            PreferencesManager.readNpuApuVerdict { key, default -> store[key] ?: default }?.appUpdatedAtMs,
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
            key = tabKey,
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
                filesDir = File("f"), libDir = "l", key = tabKey, clock = { 1L },
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
                filesDir = File("f"), libDir = "l", key = tabKey, clock = { 1L },
            )
            assertFalse("${cause.javaClass.simpleName} is not a pass", refused.passed)
            assertTrue(
                "the refusal names what failed: ${refused.refusal}",
                refused.refusal!!.startsWith("runtime: ${cause.javaClass.simpleName}: ")
            )
        }
    }

    // ------------------------------------------------------------------ the settle (P2-7: L3, L4)

    /**
     * The device-local store as a map — what `PreferencesManager` is to the settle — recording the
     * ORDER of its writes, because the crash-loop guard is an order claim: the marker must be on
     * disk before the walk, and retired with the verdict.
     */
    private class FakeStore(
        override var npuApuVerdict: NpuApuVerdict? = null,
        override var npuApuProbeInFlight: String? = null,
    ) : NpuApuVerdictStore {
        val writes = mutableListOf<String>()

        override fun markNpuApuProbeInFlight(marker: String) {
            writes += "mark"
            npuApuProbeInFlight = marker
        }

        override fun recordNpuApuVerdict(verdict: NpuApuVerdict) {
            writes += "record"
            npuApuVerdict = verdict
            npuApuProbeInFlight = null
        }

        override fun clearNpuApuProbeInFlight() {
            writes += "clear"
            npuApuProbeInFlight = null
        }
    }

    private fun settle(store: FakeStore, key: NpuApuKey = tabKey, probe: () -> String) =
        NpuApuDriverCheck.settle(
            store = store,
            key = key,
            probe = { _, _, _ -> store.writes += "walk"; probe() },
            filesDir = File("f"),
            libDir = "l",
            clock = { 7L },
        )

    @Test
    fun aStoredVerdictThatStillAnswersIsReusedWithoutAWalk() {
        val stored = verdict(refusal = null)
        val store = FakeStore(npuApuVerdict = stored)
        val settled = settle(store) { throw AssertionError("a reusable verdict must not walk") }
        assertSame(stored, settled.verdict)
        assertTrue("and it is announced as stored", settled.reused)
        assertEquals("no write at all", emptyList<String>(), store.writes)
    }

    /**
     * THE P2a REVIEW'S L4 — the crash-loop guard. `runCatching` cannot catch a native crash inside
     * the adapter walk: a ROM whose adapter crashes on load would re-probe and die on every launch,
     * taking the CPU tiers with it. So the walk is marked first, synchronously, and a launch that
     * finds its OWN key's marker with no verdict records `refuse(probe-crashed)` and never walks
     * again under that key.
     */
    @Test
    fun theMarkerIsOnDiskBeforeTheWalkAndRetiredWithTheVerdict() {
        val store = FakeStore()
        val settled = settle(store) { "" }
        assertEquals(
            "ORDER: mark, walk, then the verdict and the marker's retirement in ONE write",
            listOf("mark", "walk", "record"),
            store.writes,
        )
        assertTrue(settled.verdict.passed)
        assertEquals("a probed verdict is announced as probed", false, settled.reused)
        assertEquals("taken under the key", tabKey, settled.verdict.key)
        assertEquals("stored", settled.verdict, store.npuApuVerdict)
        assertNull("and the marker is gone", store.npuApuProbeInFlight)
        assertEquals("the marker is the key's one spelling", tabKey.marker(), tabKey.copy().marker())
    }

    @Test
    fun aWalkThatNeverFinishedIsRecordedAsProbeCrashedAndNeverWalkedAgainOnThatKey() {
        // The previous launch marked the walk and died inside it: no verdict, the marker standing.
        val store = FakeStore(npuApuProbeInFlight = tabKey.marker())
        val settled = settle(store) { throw AssertionError("a crashed key must never be walked again") }
        assertEquals(NpuApuDriverCheck.PROBE_CRASHED, settled.verdict.refusal)
        assertEquals("probe-crashed", NpuApuDriverCheck.PROBE_CRASHED)
        assertFalse(settled.verdict.passed)
        assertEquals("recorded — and so the marker retired — without a walk", listOf("record"), store.writes)
        // …and the NEXT launch reuses it: never a walk under that key again.
        val next = settle(store) { throw AssertionError("the recorded crash is reused, never re-walked") }
        assertSame(store.npuApuVerdict, next.verdict)
        assertTrue(next.reused)
        // A marker of ANOTHER key is not this launch's crash: an update moved the key, so it walks.
        val updated = FakeStore(npuApuProbeInFlight = tabKey.marker())
        val fresh = settle(updated, key = tabKey.copy(appUpdatedAtMs = installedAt + 1)) { "" }
        assertTrue("a new install walks, whatever the old key's marker says", fresh.verdict.passed)
        assertEquals(listOf("mark", "walk", "record"), updated.writes)
    }

    /**
     * THE P2a REVIEW'S L3, second half: a refusal that came from an EXCEPTION — an out-of-memory
     * inside the probe, a missing `liblitertasr.so` — is this process's answer (the tier is not
     * offered) and never a stored one: stored, it would outlive its cause for the life of the build.
     * The marker is retired all the same: a throw is not a crash, and the next launch probes again.
     */
    @Test
    fun aRefusalFromAThrowIsAnsweredButNeverStoredAndTheNextLaunchProbesAgain() {
        val store = FakeStore()
        val settled = settle(store) { throw OutOfMemoryError("Failed to allocate") }
        assertFalse("the process holds a refusal", settled.verdict.passed)
        assertTrue(
            "named: ${settled.verdict.refusal}",
            settled.verdict.refusal!!.startsWith("runtime: OutOfMemoryError: "),
        )
        assertEquals("mark, walk, then only the marker's retirement", listOf("mark", "walk", "clear"), store.writes)
        assertNull("nothing stored", store.npuApuVerdict)
        assertNull("no marker left to read as a crash", store.npuApuProbeInFlight)
        val next = settle(store) { "" }
        assertTrue("the next launch probes again, and this time it passes", next.verdict.passed)
        // A refusal the probe ANSWERED — native's own — is a verdict, and is stored.
        val answered = FakeStore()
        settle(answered) { "probe: adapter-missing" }
        assertEquals("adapter-missing", answered.npuApuVerdict?.refusal)
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

    /** (P2-7) The one settle both callers share — the launch thread and the boot prewarm. */
    private val await: String by lazy {
        app.substringAfter("    fun awaitApuDriverVerdict() {").substringBefore("\n    }\n")
    }

    /** (P2-7) The key's one derivation. */
    private val keyOf: String by lazy {
        app.substringAfter("    private fun apuVerdictKey(needs: NpuRuntimeNeeds.LiteRtMediatek): NpuApuKey = NpuApuKey(")
            .substringBefore("\n    )\n")
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
        // (P2-7) The key moved into its one derivation, apuVerdictKey — Build.FINGERPRINT is read
        // there — so "before anything else" is asserted against the key's first use.
        assertTrue(
            "…and both returns come before anything else the function does",
            liveOffset(settle, "?: return") in 0 until liveOffset(settle, "apuVerdictKey(needs)")
        )
        assertEquals(
            "the settle both callers share returns on the same two lines — a Qualcomm process " +
                "that asks it (the service's boot prewarm does, on every device) touches nothing",
            listOf(1, 1),
            listOf(
                liveLineCount(await, "val family = npuSocFamily ?: return"),
                liveLineCount(await, "val needs = family.runtime as? NpuRuntimeNeeds.LiteRtMediatek ?: return"),
            ),
        )
        assertTrue(
            "…before it takes the lock or reads the flow",
            liveOffset(await, "?: return") < liveOffset(await, "synchronized(apuVerdictLock) {") &&
                liveOffset(await, "synchronized(apuVerdictLock) {") < liveOffset(await, "NpuApuDriverCheck.verdict.value")
        )
    }

    /**
     * RE-SPECCED AT P2-7 (the P2a review's L1, L3, L4, L5), claim for claim. The reuse still comes
     * before the thread and is still published on Main; the probe still runs only off Main — now
     * inside the ONE settle ([NpuApuDriverCheck.settle]) the launch thread and the service's boot
     * prewarm share, through `LiteRtAsrEngine.probe` (the engine is the one caller of
     * `LiteRtAsrNative` in the app: `LiteRtAsrEngineContractTest`); the store is the settle's
     * (marker, verdict), and the verdict line goes out through native logging, once.
     */
    @Test
    fun aStoredVerdictIsReusedBeforeAnyProbeAndTheProbeRunsOffMain() {
        assertEquals(
            "the stored record is judged by the one reuse rule, against THIS process's key",
            1,
            liveLineCount(settle, "NpuApuDriverCheck.reusableOrNull(preferencesManager.npuApuVerdict, apuVerdictKey(needs))")
        )
        assertEquals("keyed on Build.FINGERPRINT", 1, liveLineCount(keyOf, "fingerprint = Build.FINGERPRINT,"))
        assertEquals("and on this build", 1, liveLineCount(keyOf, "appBuild = BuildConfig.VERSION_CODE,"))
        assertEquals("and on this install (L3)", 1, liveLineCount(keyOf, "appUpdatedAtMs = installUpdatedAtMs(),"))
        assertEquals("and on the family's major", 1, liveLineCount(keyOf, "wantMajor = needs.neuronMajor,"))
        assertTrue(
            "…read from lastUpdateTime",
            liveLineCount(app, "packageManager.getPackageInfo(packageName, 0).lastUpdateTime") == 1
        )
        assertEquals(
            "the key has ONE derivation, and both halves of the settle read it",
            listOf(1, 1, 1),
            listOf(
                liveLineCount(app, "Build.FINGERPRINT"),
                liveLineCount(settle, "apuVerdictKey(needs)"),
                liveLineCount(await, "key = apuVerdictKey(needs),"),
            ),
        )
        val reuse = liveOffset(settle, "NpuApuDriverCheck.reusableOrNull(")
        val thread = liveOffset(settle, "val thread = Thread(")
        val awaited = liveOffset(settle, "runCatching { awaitApuDriverVerdict() }")
        assertTrue(
            "ORDER: reuse ($reuse) before the thread ($thread), and the settle ($awaited) only inside it",
            reuse in 0 until thread && thread < awaited
        )
        assertEquals(
            "the probe is the LiteRT engine's, handed the dispatch directory and the lib dir " +
                "probeNow gives it — inside the one settle, never on the onCreate path",
            listOf(1, 0, 0),
            listOf(
                liveLineCount(await, "probe = { dispatchDir, lib, _ -> LiteRtAsrEngine(family, lib).probe(dispatchDir) },"),
                liveLineCount(settle, ".probe("),
                liveLineCount(app, "LiteRtAsrNative"),
            ),
        )
        assertEquals("a daemon thread of its own", 1, liveLineCount(settle, "thread.isDaemon = true"))
        assertEquals("…with a name a thread dump can read", 1, liveLineCount(settle, "\"npu-apu-driver-check\","))
        assertEquals(
            "the verdict is published on both routes — reused (on Main, in settle) and settled " +
                "(in the one settle)",
            listOf(1, 1),
            listOf(
                liveLineCount(settle, "if (stored != null) NpuApuDriverCheck.publish(stored)"),
                liveLineCount(await, ").also { NpuApuDriverCheck.publish(it.verdict) }"),
            ),
        )
        assertEquals(
            "the settle's store is the device-local one — the marker and the verdict live there",
            1,
            liveLineCount(await, "store = preferencesManager,")
        )
        assertEquals(
            "one settle per process: the lock both callers take, and the flag it guards",
            listOf(1, 1, 1),
            listOf(
                liveLineCount(await, "synchronized(apuVerdictLock) {"),
                liveLineCount(await, "if (apuVerdictAnnounced) return"),
                liveLineCount(await, "apuVerdictAnnounced = true"),
            ),
        )
    }

    /**
     * THE P2a REVIEW'S L5, and the pin moved with the line. The `apu: verdict` line was `Log.i`,
     * which `proguard-rules.pro` strips from every release build — and a launch that REUSES a
     * stored verdict runs no native probe, so a Play build left no trace at all of the verdict it
     * was acting on. It goes out through `WhisperNative.diag` now (native logging, under the same
     * `WE-DIAG` tag), the stale-pair sweep's route: once per process, from the settle — off Main,
     * because it loads the whisper JNI library.
     */
    @Test
    fun theVerdictLineGoesOutThroughNativeLoggingOncePerProcessAndNeverOnMain() {
        assertEquals(
            "one emission, native, inside the one settle, announcing whichever route answered",
            1,
            liveLineCount(await, "runCatching { WhisperNative.diag(NpuDiag.apuVerdict(settled.verdict, reused = settled.reused)) }")
        )
        assertEquals(
            "and no other emission of the line anywhere in the app — none on android.util.Log, " +
                "none on Main in settleApuDriverVerdict",
            listOf(1, 0, 0),
            listOf(
                liveLineCount(app, "NpuDiag.apuVerdict("),
                liveLineCount(app, "Log.i(NpuDiag.TAG, NpuDiag.apuVerdict("),
                liveLineCount(settle, "WhisperNative"),
            ),
        )
        assertTrue(
            "it is emitted after the flag is set, under the lock — once per process",
            liveOffset(await, "apuVerdictAnnounced = true") < liveOffset(await, "WhisperNative.diag(")
        )
        val proguard = read("proguard-rules.pro")
        assertTrue(
            "and the reason still stands: release builds strip android.util.Log.i",
            proguard.contains("-assumenosideeffects class android.util.Log {") &&
                proguard.contains("public static int i(...);")
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
            "…which is what the probe is handed (P2-7: with the key's major)",
            1,
            liveLineCount(check, "probe(dispatchDir(filesDir).absolutePath, libDir, key.wantMajor)")
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
            "\"proguard-rules.pro\",",
        )) {
            assertEquals("app/build.gradle.kts must list $path among sourcePinnedInputs", 1, liveLineCount(gradle, path))
        }
    }
}
