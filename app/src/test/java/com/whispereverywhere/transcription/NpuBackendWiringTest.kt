package com.whispereverywhere.transcription

import com.whispereverywhere.npu.NpuDiag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * THE TIER'S ROUTING, in the two halves it actually has (4.0, Q9).
 *
 * **Half one is executable.** [NpuBackendSelector] is a pure truth table over three inputs, so
 * every cell of it is run here — including the cell that answers "the NPU", which is exercised
 * through the lambda-taking overload. That overload exists precisely so this test can assert on
 * the npu arm **without naming `NpuWhisperBackend`**: that class touches `QnnAsrNative`, whose
 * `init` block runs `System.loadLibrary("qnnasr")`, and there is no `libqnnasr.so` on the unit-test
 * classpath — a test that named it would not fail, it would die. The doctrine is Q6's and it is
 * kept rather than probed.
 *
 * **Half two cannot be.** `FloatingBubbleService` is a `Service`: no JVM test can instantiate one,
 * there is no Robolectric on this classpath, and `warmLocalEngine()` is the ONE place in the bubble
 * path where a `LocalWhisperEngine` is constructed. So the wiring is pinned as SOURCE TEXT, by the
 * house instrument (`UnsupportedTierGatePinTest`, `ChooserSteerWiringPinTest`,
 * `NpuImportWiringPinTest`, `NpuNativeContractTest`), and the file is declared as an explicit input
 * of `:app:testDebugUnitTest` so a comment-only edit cannot leave these guards passing against
 * stale evidence.
 *
 * **The mutations these pins close**, all of which compile and all of which leave the rest of the
 * suite green:
 *  - *The `backend =` argument dropped.* `LocalWhisperEngine(app.whisperModelManager)` is the shape
 *    this file shipped with for three minor versions; it compiles, because `backend` has a default,
 *    and the entire npu tier becomes unreachable with no symptom anywhere but a device.
 *  - *The rebuild inverted.* Constructing the replacement BEFORE `shutdown()`ing the stale engine
 *    is compile-clean, passes every presence check, and puts a 60-190 MB `WhisperNative.init` in
 *    flight beside an NPU teardown that has not run — the ~570 MB transient I11 exists to forbid,
 *    arriving through the service instead of through the backend. This is the NINTH
 *    presence-vs-ORDER pin on this branch and the reasoning has not changed once.
 *  - *The offer gate read inline, on Main.* `npuAvailable = app.isNpuTierOffered()` inside
 *    `warmLocalEngine` compiles and is correct in every respect except that it `dlopen`s two QNN
 *    libraries on the UI thread — the same mutation `ChooserSteerWiringPinTest` closes on the two
 *    chooser screens, arriving at the third surface.
 *  - *The gate re-derived.* `npuCapableDevice && manager.isInstalled(npu)` written out here is a
 *    second composition of a predicate that already exists, free to drift from it.
 *  - *The fallback line moved into the segment path.* Once per session is the contract; once per
 *    segment buries the `npu: encode=` / `segment-timing:` pair it is supposed to sit beside.
 */
class NpuBackendWiringTest {

    // ------------------------------------------------------------------ source access

    /** The house locator: walk up from the test's working directory. */
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

    private val service: String by lazy {
        read("src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt")
    }

    private val selector: String by lazy {
        read("src/main/java/com/whispereverywhere/transcription/NpuBackendSelector.kt")
    }

    private val engine: String by lazy {
        read("src/main/java/com/whispereverywhere/transcription/LocalWhisperEngine.kt")
    }

    private val diag: String by lazy {
        read("src/main/java/com/whispereverywhere/npu/NpuDiag.kt")
    }

    private fun count(haystack: String, needle: String) = haystack.split(needle).size - 1

    /** A multi-line needle written as its own source lines, so indentation is part of the match. */
    private fun block(vararg lines: String) = lines.joinToString("\n")

    /**
     * Character offsets of every LIVE (non-comment) line of [scope] containing [needle]. Ordering
     * claims must never be built on `indexOf`: it measures a commented-out mention exactly as
     * happily as the code, so a comment drifting above the line it describes silently satisfies
     * "X comes first". Same helper, same reason, as `NpuNativeContractTest.liveOffsets`.
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

    /**
     * One Kotlin member function's body. Members of `FloatingBubbleService` close at a four-space
     * indent. Both failure modes are loud rather than silent, for the two reasons the house helper
     * spells out: a missing anchor would rebase the scope to the top of the file, and a missing
     * terminator would widen it into the following member.
     */
    private fun memberBody(kt: String, anchor: String): String {
        val start = kt.indexOf(anchor)
        assertTrue(
            "anchor \"$anchor\" is missing. indexOf() returns -1 when the anchor is absent, so " +
                "substring(start) would silently rebase the scope to the top of the file and every " +
                "claim below would be answered by unrelated code instead of failing.",
            start >= 0,
        )
        val body = kt.substring(start)
        assertTrue(
            "no four-space-indented \"\\n    }\\n\" follows \"$anchor\". substringBefore() returns " +
                "its RECEIVER when the delimiter is absent, so a re-indented closing brace would " +
                "silently widen the scope into the FOLLOWING member.",
            body.contains("\n    }\n"),
        )
        return body.substringBefore("\n    }\n")
    }

    // ------------------------------------------------------------------ the truth table

    /** A stand-in for the tier, so the table's npu arm is observable without naming the real one. */
    private object StandInNpuBackend : WhisperBackend {
        override fun load(modelPath: String): Long = 1L
        override fun transcribe(ctx: Long, samples: FloatArray, lang: String?, useVad: Boolean) = ""
        override fun release(ctx: Long) = Unit
    }

    private val paths = FakeModelPathProvider("/models/encoder_qairt_context.bin")

    private fun route(
        tierId: String?,
        npuAvailable: Boolean,
        declined: Boolean = false,
    ): WhisperBackend =
        NpuBackendSelector.backendFor(tierId, npuAvailable, declined, paths) { StandInNpuBackend }

    /**
     * THE ONE CELL THE WHOLE TASK EXISTS FOR. Everything else on this branch — the backend, the
     * gate, the importer, the catalog entry, the chooser card — was already in place and nothing
     * constructed the tier.
     */
    @Test
    fun npuTierWithAvailableGateSelectsNpuBackend() {
        assertSame(
            "tier `npu` with the offer gate open must route to the NPU backend. This is the only " +
                "cell of the table that constructs anything, and until Q9 nothing in the app " +
                "reached it: NpuWhisperBackend had no construction site at all.",
            StandInNpuBackend,
            route(tierId = "npu", npuAvailable = true),
        )
        assertTrue(
            "the predicate and the resolver must agree — they are one truth table with two readers",
            NpuBackendSelector.routesToNpu("npu", npuAvailable = true, declinedThisSession = false),
        )
    }

    @Test
    fun npuTierWithoutTheOfferGateStaysOnTheCpuBackend() {
        assertSame(
            "the gate is not advisory. `npu` selected on a device whose SoC, QNN probe or 358 MB " +
                "pair says no must run whisper.cpp — the tier being SELECTABLE and the tier being " +
                "RUNNABLE are different questions and only the second one is asked here.",
            WhisperNativeBackend,
            route(tierId = "npu", npuAvailable = false),
        )
        assertTrue(
            !NpuBackendSelector.routesToNpu("npu", npuAvailable = false, declinedThisSession = false),
        )
    }

    @Test
    fun everyOtherTierStaysOnTheCpuBackendWhateverTheGateSays() {
        // Every id the catalog can hand this function, plus the two shapes that are not ids at all.
        // `null` is a real value here: selectedModelId is nullable and is null before onboarding.
        listOf("multi", "pro", "eco", "base", "ultra", "", "NPU", "npu ", null).forEach { tier ->
            listOf(true, false).forEach { available ->
                assertSame(
                    "tier `$tier` (gate=$available) must route to whisper.cpp. Only the exact id " +
                        "`npu` may reach the NPU arm — a prefix, a case variant or a stray space " +
                        "is a different tier, and `ultra` in particular is a 128-bin model the " +
                        "NPU tier cannot even take a mel filterbank from.",
                    WhisperNativeBackend,
                    route(tierId = tier, npuAvailable = available),
                )
            }
        }
    }

    /**
     * REBUILD-ON-FALLBACK, half one: the decision. Half two — that the service acts on it by
     * dropping the cached engine — is [theRebuildOnFallbackSiteShutsTheStaleEngineDownFirst].
     */
    @Test
    fun aDeclinedSessionRoutesToTheCpuBackendUntilTheEngineIsRebuilt() {
        assertSame(
            "once a stage has declined, the gate being open is no longer a reason to re-arm: a " +
                "second attempt pays a 342 MiB load to reach the same answer, and would overwrite " +
                "the unavailableReason naming the FIRST stage — the one that is true.",
            WhisperNativeBackend,
            route(tierId = "npu", npuAvailable = true, declined = true),
        )
        assertTrue(
            !NpuBackendSelector.routesToNpu("npu", npuAvailable = true, declinedThisSession = true),
        )
        // The decline is a SEPARATE input, never folded into `npuAvailable`. Folding them would
        // invent a second composition of the offer gate, which is the one thing Q7b's KDoc forbids
        // by name — and it would make a session-scoped measurement look like a device fact.
        assertEquals(
            "routesToNpu takes the decline as its own parameter",
            1,
            count(
                selector,
                "fun routesToNpu(tierId: String?, npuAvailable: Boolean, declinedThisSession: Boolean)",
            ),
        )
        // And the id it compares against is the app's ONE constant, never a fresh literal. A typo
        // in a second copy routes silently to the CPU backend, which is the whole tier failing with
        // nothing to see.
        assertEquals(
            "the tier id comes from NpuAssetImport.TIER_ID, the constant WhisperModelManager and " +
                "the Settings picker already read",
            1,
            count(selector, "tierId == NpuAssetImport.TIER_ID"),
        )
        assertEquals(
            "and the selector mints no `\"npu\"` literal of its own",
            0,
            count(selector, "\"npu\""),
        )
    }

    // ------------------------------------------------------------------ the wiring

    /**
     * The construction site, pinned: the selected backend is passed, once, at the one place a
     * `LocalWhisperEngine` is built in this file.
     */
    @Test
    fun theServicePassesTheSelectedBackendAtConstructionExactlyOnce() {
        val warm = memberBody(
            service, "    private fun warmLocalEngine(allowRebuild: Boolean = false): LocalWhisperEngine {"
        )

        assertEquals(
            "warmLocalEngine passes `backend =` exactly once. Zero is this file's own history — " +
                "`LocalWhisperEngine(app.whisperModelManager)` compiles, because the parameter has " +
                "a default, and silently makes the whole tier unreachable.",
            1,
            count(warm, "backend = NpuBackendSelector.backendFor("),
        )
        assertEquals(
            "the arguments are NAMED. backendFor takes `npuAvailable` and `declinedThisSession` " +
                "adjacently, both Boolean; a positional call that transposed them would compile " +
                "and would re-arm a declined tier on a device that never offered it.",
            1,
            count(
                warm,
                block(
                    "            backend = NpuBackendSelector.backendFor(",
                    "                tierId = tierId,",
                    "                npuAvailable = npuTierOffered,",
                    "                declinedThisSession = reason != null,",
                    "                paths = app.whisperModelManager,",
                    "                appContext = applicationContext,",
                    "            ),",
                ),
            ),
        )
        assertEquals(
            "exactly one LocalWhisperEngine( is constructed on a LIVE line in this whole file — " +
                "the cached one. A second construction site is a second native context and a " +
                "second tier decision. (Live-scoped: the KDoc quotes the pre-Q9 expression.)",
            1,
            liveOffsets(service, "LocalWhisperEngine(").size,
        )
        assertEquals(
            "the tier routed on is `selectedModelId` — the id the chooser WRITES, which Q8's D-2 " +
                "gave the npu tier a path into. Routing on the installed model instead would ask a " +
                "different question (what is on disk, not what was chosen) and would answer `npu` " +
                "for a user who has the pair imported and has selected something else.",
            1,
            count(warm, "val tierId = app.preferencesManager.selectedModelId"),
        )
        assertEquals(
            "the memo keeps its @Volatile. It is DEFENSIVE, not load-bearing, and this message " +
                "used to claim otherwise: withContext(Dispatchers.IO) returns to the CALLER's " +
                "context and both callers are on Dispatchers.Main, so the write and every read " +
                "are alike Main-confined today. It is pinned because it costs one Boolean read and " +
                "it is the difference between correct and correct-until-someone-moves-the-refresh " +
                "onto a background scope — a one-line change with no other symptom.",
            1,
            count(service, "@Volatile private var npuTierOffered: Boolean = false"),
        )
        // The gate, read from the ONE composed predicate and never re-derived. `isNpuTierOffered()`
        // is `npuCapableDevice && isInstalled(npu)`; spelling either half out here would be a
        // second composition, free to drift from the one the two chooser screens obey.
        assertEquals(
            "the offer gate is consulted through isNpuTierOffered() exactly once in this file",
            1,
            count(service, "app.isNpuTierOffered()"),
        )
        listOf("npuCapableDevice", "isInstalled(").forEach { half ->
            assertEquals(
                "`$half` must appear NOWHERE in this file: it is half of a gate that already " +
                    "exists, and a service that composes its own answer is a third opinion",
                0,
                count(service, half),
            )
        }
        assertEquals(
            "and the gate is evaluated OFF MAIN. warmLocalEngine runs on Main from " +
                "startRecording; isNpuTierOffered() forces the memoised probe, which dlopens " +
                "libQnnSystem.so and libQnnHtp.so, and QnnAsrNative forbids Main everywhere.",
            1,
            count(service, "npuTierOffered = withContext(Dispatchers.IO) { app.isNpuTierOffered() }"),
        )
        assertEquals(
            "the memo is refreshed at BOTH points that can change it, and nowhere else: three " +
                "live mentions — the declaration plus its two call sites. The second call site is " +
                "the one a start-time memo cannot see, because Q8's importer writes the 358 MB " +
                "pair into files/models under a live service and the gate's installed half is a " +
                "live stat.",
            3,
            liveOffsets(service, "refreshNpuTierOffer()").size,
        )
        assertEquals(
            "the refresh precedes the prewarm it decides, adjacently — an ORDER claim written as " +
                "adjacency, because a refresh that ran after the engine was built would build the " +
                "first engine of every process on the CPU backend and then pay a rebuild for it",
            1,
            count(
                service,
                block(
                    "            refreshNpuTierOffer()",
                    "            delay(1500)",
                    "            warmLocalEngine().prewarm()",
                ),
            ),
        )
        // The engine-side half of the same contract: the field the service passes is a val, so the
        // service's rebuild is the ONLY mechanism by which a tier change can take effect.
        assertEquals(
            "LocalWhisperEngine.backend stays a val",
            1,
            count(engine, "    private val backend: WhisperBackend = WhisperNativeBackend,"),
        )
        assertEquals(
            "and it keeps its whisper.cpp default, so every caller but the bubble service is " +
                "untouched by this task",
            0,
            count(engine, "private var backend: WhisperBackend"),
        )
    }

    /**
     * C1 (Q9 review) — **only a session start may tear the cached engine down.**
     *
     * `warmLocalEngine` used to be `localEngine ?: LocalWhisperEngine(…)`: pure, idempotent, safe
     * from anywhere. Q9 gave it a destructive branch and did not re-audit its callers, and
     * `LocalWhisperEngine.shutdown()` calls `executor.shutdown()` — after which `commit()`'s
     * unguarded `executor.execute { runSegment(…) }` throws `RejectedExecutionException` from the
     * **capture thread**, which has no handler, while `sendAudio` keeps filling a buffer nobody will
     * ever cut. Two callers could reach it mid-session: the boot prewarm (no gate at all) and the
     * switch collector (whose IDLE gate now sits above a suspension point). The sharpest instance is
     * the Q10a script itself — service starts, user taps inside 1500 ms, the tier declines, and the
     * prewarm coroutine shuts the live engine down.
     *
     * The permission travels with the caller because it **cannot** be read from state:
     * `startRecording` sets `CONNECTING` before resolving the engine, so a `currentState` guard
     * would block the one rebuild that must happen and permit none. That trap is pinned too.
     */
    @Test
    fun onlyAProvablyIdleCallerMayTearTheCachedEngineDown() {
        assertEquals(
            "the permission DEFAULTS to false, so a caller added later is safe by omission — the " +
                "property Q9 lost by making a pure function destructive without changing its shape",
            1,
            count(service, "private fun warmLocalEngine(allowRebuild: Boolean = false): LocalWhisperEngine {"),
        )
        assertEquals(
            "exactly TWO callers pass it, each named at the call site and each with its own proof " +
                "that no session can be in flight (fix round 2 added the second)",
            2,
            count(service, "warmLocalEngine(allowRebuild = true)"),
        )
        val resolve = memberBody(service, "    private fun resolveTranscriptionEngine(): TranscriptionEngine {")
        assertEquals(
            "the first is resolveTranscriptionEngine — session start, before any audio exists and " +
                "before any commit() can be queued, which is the entire safety argument",
            1,
            count(resolve, "val local = warmLocalEngine(allowRebuild = true)"),
        )
        assertEquals(
            "the second is the model-switch collector, and it must reach prewarmModelSwitch() " +
                "THROUGH the rebuild. Without that the collector prewarms the NEW tier's file on " +
                "the OLD tier's backend — an npu-backed engine given a ggml path resolves a null " +
                "companion and publishes a FALSE `unavailable stage=companion`.",
            1,
            count(service, "warmLocalEngine(allowRebuild = true).prewarmModelSwitch()"),
        )
        assertEquals(
            "the boot prewarm does NOT have the permission: it only ever fills an empty slot, so " +
                "it has nothing to tear down, and a rebuild there is precisely the un-gated one " +
                "the Critical was about",
            1,
            count(service, "warmLocalEngine().prewarm()"),
        )
        assertEquals(
            "four live mentions of warmLocalEngine( in this file — the declaration and its three " +
                "call sites. A fifth cannot be added without moving this number, which is what " +
                "makes the assertions above a complete audit rather than a spot check.",
            4,
            liveOffsets(service, "warmLocalEngine(").size,
        )
        // THE ORDER, and it is the invariant rather than the presence: a permission check that runs
        // after shutdown() has already run is not a check. Every statement survives the swap.
        val warm = memberBody(
            service, "    private fun warmLocalEngine(allowRebuild: Boolean = false): LocalWhisperEngine {"
        )
        val permission = liveOffsets(
            warm, "if (cached != null && (onNpu == localEngineOnNpu || !allowRebuild)) return cached"
        )
        val teardown = liveOffsets(warm, "cached.shutdown()")
        assertTrue("the permission guard must be on a live line", permission.isNotEmpty())
        assertTrue("the teardown must be on a live line", teardown.isNotEmpty())
        assertTrue(
            "the guard (${permission.first()}) must precede the teardown (${teardown.first()})",
            permission.first() < teardown.first(),
        )
        // THE TRAP. startRecording sets CONNECTING at its top and resolves the engine afterwards, so
        // a state-keyed guard inside this function would refuse the only rebuild that is allowed.
        assertEquals(
            "warmLocalEngine must not read currentState: startRecording has already left IDLE by " +
                "the time it calls in, so a state guard permits nothing and blocks everything",
            0,
            count(warm, "currentState"),
        )

        // THE COLLECTOR'S OWN PROOF, and it is an ORDER claim (fix round 2). Its liveness gate must
        // be re-read BELOW refreshNpuTierOffer()'s `withContext(Dispatchers.IO)` and ABOVE the
        // rebuild. The gate at the top of the block is not enough and its insufficiency is
        // invisible: every statement is present, the read is spelled identically, and only its
        // position decides whether it describes the state at the moment of the teardown or the
        // state as it was before a thread hop. That is the TOCTOU the Critical named.
        val refresh = liveOffsets(service, "                refreshNpuTierOffer()")
        val reRead = liveOffsets(service, "session started while re-reading the gate")
        val rebuild = liveOffsets(service, "warmLocalEngine(allowRebuild = true).prewarmModelSwitch()")
        assertEquals("the collector refreshes the gate on exactly one live line", 1, refresh.size)
        assertEquals("and re-reads its liveness gate on exactly one live line", 1, reRead.size)
        assertEquals("and rebuilds on exactly one live line", 1, rebuild.size)
        assertTrue(
            "the re-read (${reRead.first()}) must come AFTER the suspension it exists to survive " +
                "(${refresh.first()}) — above it, it is the stale read the Critical was about",
            refresh.first() < reRead.first(),
        )
        assertTrue(
            "and BEFORE the rebuild it authorises (${rebuild.first()}) — a liveness check taken " +
                "after the teardown is not a check",
            reRead.first() < rebuild.first(),
        )
    }

    /**
     * Rebuild-on-fallback, half two — and the ORDER, which is the part presence cannot see.
     *
     * `shutdown()` queues the stale engine's `backend.release`, and for `NpuWhisperBackend` that
     * release frees the NPU's ~376 MiB FIRST (pinned inside that class by `NpuNativeContractTest`).
     * Constructing the replacement before that call is issued would let the new engine's
     * `WhisperNative.init` — 60-190 MB — be in flight beside an NPU teardown that has not started:
     * the same ~570 MB transient the backend's own fallback path is ordered to avoid, rebuilt
     * around the outside of it. Every presence count is satisfied by the swap; only the offsets
     * are not.
     */
    @Test
    fun theRebuildOnFallbackSiteShutsTheStaleEngineDownFirst() {
        val warm = memberBody(
            service, "    private fun warmLocalEngine(allowRebuild: Boolean = false): LocalWhisperEngine {"
        )

        val shutdown = liveOffsets(warm, "cached.shutdown()")
        val construct = liveOffsets(warm, "val built = LocalWhisperEngine(")
        assertTrue(
            "the rebuild must shut the stale engine down on a live line — this is the mechanism " +
                "by which `fall back to multi for the session` happens at all, because backend is " +
                "a val and there is nothing to reassign",
            shutdown.isNotEmpty(),
        )
        assertTrue("the rebuild must construct a replacement on a live line", construct.isNotEmpty())
        assertTrue(
            "shutdown (${shutdown.first()}) must precede construction (${construct.first()}). " +
                "Presence is not the invariant: a replacement built first is a second path around " +
                "I11, and I11 is the only reason the fallback path is ordered the way it is.",
            shutdown.first() < construct.first(),
        )
        assertEquals(
            "the cached engine is invalidated when — and only when — the routing decision changed. " +
                "Comparing against the recorded decision is what makes a tier change and a " +
                "fallback the same event, handled once.",
            1,
            count(
                warm,
                block(
                    "        val cached = localEngine",
                    "        if (cached != null && (onNpu == localEngineOnNpu || !allowRebuild)) return cached",
                ),
            ),
        )
        assertEquals(
            "the decision is the SELECTOR's, not a second copy of the truth table written here",
            1,
            count(warm, "val onNpu = NpuBackendSelector.routesToNpu(tierId, npuTierOffered, reason != null)"),
        )
        // The decision must be RECORDED beside the engine it describes. Dropping this write is the
        // sharpest mutation on this function and it does not look like one: `localEngineOnNpu`
        // stays false, so on an npu device the guard above never matches, and every single call —
        // every session start, every prewarm — shuts the engine down and builds another. A warm
        // engine that is never warm, paying a full model load per session, with no test, no log
        // line and no crash to say so.
        assertEquals(
            "the engine and the decision that produced it are recorded together",
            1,
            count(
                warm,
                block(
                    "        localEngine = built",
                    "        localEngineOnNpu = onNpu",
                    "        return built",
                ),
            ),
        )
        assertEquals(
            "and the decline is read from the mirror NpuWhisperBackend publishes from its own " +
                "setter, so the engine layer and the tier card state the same fact from the same " +
                "source",
            1,
            count(warm, "val reason = NpuTierStatus.unavailableReason.value"),
        )
        // ONCE PER SESSION. The line sits on the transition, inside the branch that has already
        // decided to rebuild — so it cannot fire per segment, and it cannot fire on the ordinary
        // first build of a process (`cached` is null there, and nothing declined).
        val line = liveOffsets(warm, "NpuDiag.fallbackRebuild(")
        assertEquals(
            "the fallback line is emitted exactly once, from the rebuild transition. A copy in " +
                "the segment path would print on every utterance and bury the `npu: encode=` and " +
                "`segment-timing:` lines it is meant to sit beside.",
            1,
            line.size,
        )
        assertTrue(
            "and it is emitted from INSIDE the rebuild branch, after the guard that requires a " +
                "cached engine to exist — offset ${line.first()} must follow ${shutdown.first()}'s " +
                "own branch opening",
            line.first() < shutdown.first(),
        )
        assertEquals(
            "the line names the same stage the backend's own `npu: unavailable` line named, read " +
                "through the one parser that exists for it",
            1,
            count(warm, "NpuDiag.fallbackRebuild(NpuTierStatus.stageOf(reason))"),
        )
        // The format itself, assertable because it is a builder rather than an inline Log.i format
        // string — the same F-rule split SegmentTiming and the rest of NpuDiag already use.
        assertEquals(
            "npu: fallback rebuild stage=encode (the cached local engine is rebuilt on the CPU tier)",
            NpuDiag.fallbackRebuild("encode"),
        )
        assertEquals(
            "a null stage is REPORTED, never hidden behind an empty word",
            "npu: fallback rebuild stage=unknown (the cached local engine is rebuilt on the CPU tier)",
            NpuDiag.fallbackRebuild(null),
        )
        assertTrue(
            "it carries the `npu: ` prefix every line of this tier's diagnostics carries, so one " +
                "grep finds the tier's whole story",
            NpuDiag.fallbackRebuild("init").startsWith("npu: "),
        )
        assertEquals(
            "and that prefix is ONE contiguous literal in NpuDiag.kt — the same rule, checked the " +
                "same way, as `npu: encode=`. Assembling it from parts produces identical output, " +
                "is invisible to the compiler and to review, and breaks every grep and every " +
                "parser written against the shipped format.",
            1,
            count(diag, "\"npu: fallback rebuild stage="),
        )
        // The production overload's single construction call — the fact the executable half of this
        // class deliberately cannot assert, because it may not name the type.
        assertEquals(
            "the selector's production overload constructs the tier exactly once, from the paths " +
                "and context it was handed",
            1,
            count(selector, "NpuWhisperBackend(it, appContext)"),
        )
    }
}
