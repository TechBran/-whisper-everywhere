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
 *  - *The offer gate read inline, on Main.* `offeredNpuTierIds = app.offeredNpuTierIds()` inside
 *    `warmLocalEngine` compiles and is correct in every respect except that it `dlopen`s two QNN
 *    libraries on the UI thread — the same mutation `ChooserSteerWiringPinTest` closes on the two
 *    chooser screens, arriving at the third surface.
 *  - *The gate re-derived.* `npuCapableDevice && manager.isInstalled(…)` written out here is a
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

    /**
     * The single-tier view of the table, for the cells whose question is "does THIS id route
     * under ITS OWN gate/decline state": `npuAvailable` offers the candidate itself, `declined`
     * declines it. The independence cells — one tier declined while the OTHER routes — call
     * [routeWith] with explicit sets instead.
     */
    private fun route(
        tierId: String?,
        npuAvailable: Boolean,
        declined: Boolean = false,
    ): WhisperBackend = routeWith(
        tierId,
        offered = if (npuAvailable && tierId != null) setOf(tierId) else emptySet(),
        declined = if (declined && tierId != null) setOf(tierId) else emptySet(),
    )

    private fun routeWith(
        tierId: String?,
        offered: Set<String>,
        declined: Set<String> = emptySet(),
    ): WhisperBackend =
        NpuBackendSelector.backendFor(tierId, offered, declined, paths) { _, _ ->
            StandInNpuBackend
        }

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
            NpuBackendSelector.routesToNpu("npu", offeredNpuTierIds = setOf("npu"), declinedTiers = emptySet()),
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
            !NpuBackendSelector.routesToNpu("npu", offeredNpuTierIds = emptySet(), declinedTiers = emptySet()),
        )
    }

    @Test
    fun everyOtherTierStaysOnTheCpuBackendWhateverTheGateSays() {
        // Every id the catalog can hand this function, plus the two shapes that are not ids at all.
        // `null` is a real value here: selectedModelId is nullable and is null before onboarding.
        listOf("multi", "pro", "eco", "base", "ultra", "", "NPU", "npu ", null).forEach { tier ->
            listOf(true, false).forEach { available ->
                assertSame(
                    "tier `$tier` (gate=$available) must route to whisper.cpp. Only the exact " +
                        "ids with a spec row — `npu` and `npu-turbo` — may reach the NPU arm; a " +
                        "prefix, a case variant or a stray space is a different tier. `ultra` in " +
                        "particular shares turbo's 128-bin mel and even its weights lineage, and " +
                        "STILL must not route: it is a ggml, not a context binary, and the NPU " +
                        "backend deserialises QAIRT context blobs — a ggml handed to it is a " +
                        "refusal at load bought with a 574 MB read.",
                    WhisperNativeBackend,
                    route(tierId = tier, npuAvailable = available),
                )
            }
        }
    }

    /**
     * THE INDEPENDENCE THE PER-TIER DECLINE BUYS (4.1 L8, step 1's whole reason). Until L8 the
     * decline record was ONE process-wide reason: a turbo decline — "init: could not deserialise
     * 740 MB" — fed the same Boolean that gated `npu`, banning the SMALL tier for the rest of the
     * process. In a lab whose purpose is A/B-ing the two, that is the worst possible coupling:
     * the first tier to hiccup would have silently removed the other from the comparison.
     */
    @Test
    fun aDeclinedNpuTurboNeverBlocksAnOfferedNpu() {
        val bothOffered = setOf("npu", "npu-turbo")
        assertSame(
            "with BOTH tiers offered and turbo declined, `npu` must still route to the NPU arm — " +
                "turbo's decline is a measurement about turbo, not about the device",
            StandInNpuBackend,
            routeWith("npu", offered = bothOffered, declined = setOf("npu-turbo")),
        )
        assertSame(
            "while `npu-turbo` itself — the tier the measurement IS about — stays on the CPU " +
                "backend for the rest of the process",
            WhisperNativeBackend,
            routeWith("npu-turbo", offered = bothOffered, declined = setOf("npu-turbo")),
        )
        assertTrue(
            NpuBackendSelector.routesToNpu("npu", bothOffered, declinedTiers = setOf("npu-turbo")),
        )
        assertTrue(
            !NpuBackendSelector.routesToNpu("npu-turbo", bothOffered, declinedTiers = setOf("npu-turbo")),
        )
    }

    @Test
    fun aDeclinedNpuNeverBlocksAnOfferedNpuTurbo() {
        val bothOffered = setOf("npu", "npu-turbo")
        assertSame(
            "the mirror image: npu declined, turbo offered — turbo routes",
            StandInNpuBackend,
            routeWith("npu-turbo", offered = bothOffered, declined = setOf("npu")),
        )
        assertSame(
            "and npu stays on the CPU backend",
            WhisperNativeBackend,
            routeWith("npu", offered = bothOffered, declined = setOf("npu")),
        )
        // Both declined: both on CPU — the record is per-tier, never per-class.
        assertSame(
            WhisperNativeBackend,
            routeWith("npu", offered = bothOffered, declined = bothOffered),
        )
        assertSame(
            WhisperNativeBackend,
            routeWith("npu-turbo", offered = bothOffered, declined = bothOffered),
        )
        // And a tier offered ALONE routes alone — the offer set is per-tier too (L5).
        assertSame(
            "a turbo-only device routes turbo with npu's pair absent from disk",
            StandInNpuBackend,
            routeWith("npu-turbo", offered = setOf("npu-turbo")),
        )
        assertSame(
            "and npu selected on that device stays on the CPU backend",
            WhisperNativeBackend,
            routeWith("npu", offered = setOf("npu-turbo")),
        )
    }

    /**
     * THE ROUTING CENSUS — the J10 pin, RE-SPECIFIED (4.1 L8), having fired exactly when and for
     * exactly the reason the 4.0 final review said it would.
     *
     * Its 4.0 form asserted `listOf("npu")` and its message promised that a second id would be a
     * DELIBERATE RE-SPEC once the epoch landed. L8 is that re-spec, and the candidate list lost
     * its hand-written `"npu-turbo"` literal in the same breath: that literal was planted to arm
     * this exact tripwire *while the id did not exist*, and L5 put the id in the catalog — so the
     * candidates carried it twice, `routed` came back `[npu, npu-turbo, npu-turbo]` (checkpoint B
     * measured exactly that), and a hand copy beside the catalog's would have been a second
     * source of truth for the thing this test measures. The six non-id shapes stay written out:
     * `"NPU"`, `"npu "`, `"turbo"`, `""`, `null`, `"nope"` are not ids and never will be, which
     * is exactly why no catalog can supply them.
     *
     * Why two ids are SAFE — the whole argument, carried in one place:
     *  1. **The arming epoch (4.1 L1).** The QNN session is a process-global and `nativeInit`
     *     releases any existing one, while `LocalWhisperEngine.shutdown()` QUEUES the stale
     *     engine's release on another executor — source order does not order two executors'
     *     effects. The epoch makes the stale release name a session that no longer exists (a
     *     WE-DIAG line, ignored) and makes a stale `transcribe` a refusal, so the npu -> npu-class
     *     rebuild's losing interleaving is contained by IDENTITY, not by ordering.
     *  2. **The rebuild guard compares tier IDS (L8, this task).** `routedNpuTierId ==
     *     localEngineNpuTierId` — a Boolean could not tell `npu` from `npu-turbo`, so the switch
     *     read as "no change", never rebuilt, and the user kept dictating on the tier they had
     *     just left with the card showing the other one.
     *
     * A THIRD id is a new spec row (`NpuModelSpec.forTier` is the membership test now) **plus a
     * deliberate re-spec of this census** — not a widening. Both halves above already hold for
     * any number of rows; what a third row must bring is its own census, its own vocabulary
     * decision and its own delivery-name uniqueness, none of which this predicate can check.
     */
    @Test
    fun exactlyTheTwoNpuClassTierIdsRoute() {
        // Every id the catalog can name, plus the shapes that are not ids at all. No hand-written
        // real ids: the catalog supplies those (see the KDoc for why the L5-era literal is gone).
        val candidates = com.whispereverywhere.model.WhisperCatalog.entries.map { it.id } +
            listOf(null, "", "NPU", "npu ", "turbo", "nope")
        // Each candidate is censused under ITS OWN open gate: offered = itself, nothing declined.
        // The census asks which IDS can route at all, not which are offered on some device.
        val routed = candidates.filter {
            NpuBackendSelector.routesToNpu(
                it,
                offeredNpuTierIds = if (it == null) emptySet() else setOf(it),
                declinedTiers = emptySet(),
            )
        }
        assertEquals(
            "EXACTLY the two npu-class tier ids route to the NPU backend: {npu, npu-turbo}, the " +
                "spec table's own rows. Two are safe BECAUSE the arming epoch (L1) makes a stale " +
                "instance's queued nativeRelease a no-op and its transcribe a refusal, AND " +
                "because the service's rebuild guard compares routed tier IDS " +
                "(routedNpuTierId == localEngineNpuTierId), not a Boolean. A THIRD id here is a " +
                "new NpuModelSpec row plus a deliberate re-spec of this census — never a " +
                "widening: the epoch and the id-guard already hold for any number of rows, but a " +
                "new row's census, vocabulary and delivery filenames are decisions this " +
                "predicate cannot take. Routed: $routed",
            listOf("npu", "npu-turbo"),
            routed,
        )
        // And the set is the SPEC TABLE's, by construction: forTier answers non-null for exactly
        // the routed ids. Asserted from the value side so the join cannot silently become two
        // lists that agree today.
        routed.forEach {
            assertTrue(
                "routed id '$it' must have a spec row — the predicate's own membership test",
                com.whispereverywhere.npu.NpuModelSpec.forTier(it) != null,
            )
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
            !NpuBackendSelector.routesToNpu("npu", offeredNpuTierIds = setOf("npu"), declinedTiers = setOf("npu")),
        )
        // The decline is a SEPARATE input, never folded into the offer set. Folding them would
        // invent a second composition of the offer gate, which is the one thing Q7b's KDoc forbids
        // by name — and it would make a process-scoped measurement look like a device fact.
        assertEquals(
            "routesToNpu takes the per-tier decline set as its own parameter, beside the offer " +
                "set — the signature is the independence the L8 re-thread bought, spelled out",
            1,
            count(
                selector,
                block(
                    "    fun routesToNpu(",
                    "        tierId: String?,",
                    "        offeredNpuTierIds: Set<String>,",
                    "        declinedTiers: Set<String>,",
                    "    ): Boolean =",
                ),
            ),
        )
        // And the routing set is the SPEC TABLE's rows, never a literal or a constant of this
        // class's own. A second home for "which ids are npu-class" is free to drift from the one
        // that resolves the census, the vocabulary and the mel source; forTier IS that home.
        assertEquals(
            "the membership test is NpuModelSpec.forTier — the predicate's first clause, on " +
                "exactly one live line of the predicate (the resolver's own forTier read below " +
                "it is the belt-and-braces second)",
            1,
            count(selector, "NpuModelSpec.forTier(tierId) != null &&"),
        )
        assertEquals(
            "the selector mints no `\"npu\"` literal of its own",
            0,
            count(selector, "\"npu\""),
        )
        assertEquals(
            "and no single-tier constant either: the 4.0 `NpuAssetImport.TIER_ID` clause is gone " +
                "with the single-tier routing it served — a constant naming ONE tier cannot " +
                "answer a two-tier membership question",
            0,
            count(selector, "NpuAssetImport"),
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
            "the arguments are NAMED — all five. backendFor takes `offeredNpuTierIds` and " +
                "`declinedTiers` adjacently, both Set<String>; a positional call that transposed " +
                "them would compile and would route a DECLINED tier on a device that never " +
                "offered it — the same hazard the 4.0 needle was written for, now with two sets " +
                "instead of two Booleans.",
            1,
            count(
                warm,
                block(
                    "            backend = NpuBackendSelector.backendFor(",
                    "                tierId = tierId,",
                    "                offeredNpuTierIds = npuTierIds,",
                    "                declinedTiers = declined,",
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
            "the memo keeps its @Volatile — and it is the SET now, under the chooser producers' " +
                "own name, so the value carries one name end to end (L8). @Volatile is " +
                "DEFENSIVE, not load-bearing: withContext(Dispatchers.IO) returns to the " +
                "CALLER's context and both callers are on Dispatchers.Main, so the write and " +
                "every read are alike Main-confined today. It is pinned because it costs one " +
                "reference read and it is the difference between correct and " +
                "correct-until-someone-moves-the-refresh onto a background scope.",
            1,
            count(service, "@Volatile private var npuTierIds: Set<String> = emptySet()"),
        )
        // The gate, read from the ONE composed producer and never re-derived. offeredNpuTierIds()
        // is the capability probe AND each tier's own files on disk; spelling either half out
        // here would be a second composition, free to drift from the one the chooser screens obey.
        assertEquals(
            "the offer gate is consulted through offeredNpuTierIds() exactly once in this file",
            1,
            count(service, "app.offeredNpuTierIds()"),
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
            "and the deleted 4.0 Boolean shim is consulted NOWHERE — L8 removed " +
                "isNpuTierOffered() with this file's re-thread, and a revival here would be a " +
                "second derivation of the gate that one bit cannot even express",
            0,
            count(service, "isNpuTierOffered"),
        )
        assertEquals(
            "and the gate is evaluated OFF MAIN. warmLocalEngine runs on Main from " +
                "startRecording; offeredNpuTierIds() forces the memoised probe, which dlopens " +
                "libQnnSystem.so and libQnnHtp.so, and QnnAsrNative forbids Main everywhere.",
            1,
            count(service, "npuTierIds = withContext(Dispatchers.IO) { app.offeredNpuTierIds() }"),
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
            warm, "if (cached != null && (routedNpuTierId == localEngineNpuTierId || !allowRebuild)) return cached"
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
            "the cached engine is invalidated when — and only when — the ROUTED TIER ID changed " +
                "(L8). A Boolean could not tell npu from npu-turbo, so the A/B's own switch read " +
                "as \"no change\" and never rebuilt; comparing recorded ids is what makes a tier " +
                "change and a fallback the same event, handled once — and null == null keeps " +
                "every CPU-to-CPU switch on the cached engine exactly as before.",
            1,
            count(
                warm,
                block(
                    "        val cached = localEngine",
                    "        if (cached != null && (routedNpuTierId == localEngineNpuTierId || !allowRebuild)) return cached",
                ),
            ),
        )
        assertEquals(
            "the decision is the SELECTOR's, not a second copy of the truth table written here — " +
                "the service only names WHICH id the yes was about",
            1,
            count(
                warm,
                "val routedNpuTierId = if (NpuBackendSelector.routesToNpu(tierId, npuTierIds, declined)) tierId else null",
            ),
        )
        // The decision must be RECORDED beside the engine it describes. Dropping this write is the
        // sharpest mutation on this function and it does not look like one: `localEngineNpuTierId`
        // stays null, so on an npu device the guard above never matches, and every single call —
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
                    "        localEngineNpuTierId = routedNpuTierId",
                    "        return built",
                ),
            ),
        )
        assertEquals(
            "and the decline set is read from the mirror NpuWhisperBackend publishes from its " +
                "own setter — PER TIER (L8), so the engine layer and each tier card state the " +
                "same fact from the same source",
            1,
            count(warm, "val declined = NpuTierStatus.declinedTiers"),
        )
        assertEquals(
            "the fallback narration reads the CACHED engine's own tier's reason — asking for the " +
                "routed tier's would print a stage the engine being torn down never declined at",
            1,
            count(warm, "val reason = NpuTierStatus.reasonFor(localEngineNpuTierId)"),
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
            "the selector's production overload constructs the tier exactly once, from the paths, " +
                "the context and the SPEC it resolved (4.1 L2). The spec has no default on the " +
                "constructor, so this is also the assertion that a tier id with no row in " +
                "NpuModelSpec cannot reach the NPU backend at all.",
            1,
            count(selector, "NpuWhisperBackend(p, appContext, spec)"),
        )
        assertEquals(
            "and it takes the spec from NpuModelSpec.forTier, on the npu arm only — a second " +
                "resolution site would be a second answer to \"which model is this tier\"",
            1,
            count(selector, "val spec = NpuModelSpec.forTier(tierId) ?: return WhisperNativeBackend"),
        )
    }

    /**
     * Q9 M3, folded at L8 — THE SILENT REBUILD GETS ITS NARRATION. A tier change away from an
     * npu-class tier with NO decline was correct behaviour with a missing line: the log showed a
     * session on one tier, then a session on another, with nothing saying the engine was torn
     * down in between — indistinguishable from a cached-engine bug. The A/B makes exactly that
     * switch the ORDINARY path (multi -> npu -> npu-turbo and back, once per sheet row), so
     * every rebuild now narrates itself: a decline keeps [NpuDiag.fallbackRebuild] with its
     * stage, and every other rebuild names the from-tier and the to-tier.
     */
    @Test
    fun theSilentRebuildIsNarratedWithFromAndToTiers() {
        val warm = memberBody(
            service, "    private fun warmLocalEngine(allowRebuild: Boolean = false): LocalWhisperEngine {"
        )
        // Emission: exactly one tierRebuild site, and it is the fallback line's else-arm — the
        // two narrations partition the rebuild transitions, so every rebuild prints exactly one.
        val line = liveOffsets(warm, "NpuDiag.tierRebuild(localEngineNpuTierId, routedNpuTierId)")
        assertEquals(
            "the switch narration is emitted exactly once, from the rebuild transition — a copy " +
                "in the segment path would print per utterance and bury the lines it sits beside",
            1,
            line.size,
        )
        val shutdown = liveOffsets(warm, "cached.shutdown()")
        assertTrue(
            "and it is emitted from INSIDE the rebuild branch, BEFORE the teardown it narrates " +
                "(offset ${line.first()} vs ${shutdown.first()}) — after it, a shutdown crash " +
                "would eat the only line saying a rebuild was in flight",
            line.first() < shutdown.first(),
        )
        assertEquals(
            "the decline arm and the switch arm are one if/else — one rebuild, one line, never " +
                "zero and never two — and the fallback arm ADDITIONALLY requires " +
                "`routedNpuTierId == null` (RE-SPECCED by the L8 review's I2; the old block " +
                "keyed on the decline alone). Without that conjunct, decline-then-switch — a " +
                "tier declines mid-A/B and the owner switches to the OTHER npu tier, the A/B's " +
                "natural next move — printed \"rebuilt on the CPU tier\" while the replacement " +
                "routed to the other NPU tier: false text on the sheet's own instrument, " +
                "contradicting §7's expected `tier rebuild` rhythm. With it, every arm tells " +
                "the truth in all four decline/switch combinations: the fallback line fires " +
                "exactly when the replacement IS the CPU tier, and every other rebuild names " +
                "the ACTUAL target through tierRebuild.",
            1,
            count(
                warm,
                block(
                    "            if (localEngineNpuTierId != null && reason != null && routedNpuTierId == null) {",
                    "                android.util.Log.w(NpuDiag.TAG, NpuDiag.fallbackRebuild(NpuTierStatus.stageOf(reason)))",
                    "            } else {",
                    "                android.util.Log.i(NpuDiag.TAG, NpuDiag.tierRebuild(localEngineNpuTierId, routedNpuTierId))",
                    "            }",
                ),
            ),
        )
        // The format itself, assertable because it is a builder (the F-rule split).
        assertEquals(
            "npu: tier rebuild from=npu to=npu-turbo (the cached local engine is rebuilt for the selected tier)",
            NpuDiag.tierRebuild("npu", "npu-turbo"),
        )
        assertEquals(
            "null means the shared CPU backend and is REPORTED as cpu — every CPU tier is one " +
                "backend, which is exactly what the null encodes",
            "npu: tier rebuild from=cpu to=npu (the cached local engine is rebuilt for the selected tier)",
            NpuDiag.tierRebuild(null, "npu"),
        )
        assertEquals(
            "npu: tier rebuild from=npu-turbo to=cpu (the cached local engine is rebuilt for the selected tier)",
            NpuDiag.tierRebuild("npu-turbo", null),
        )
        assertTrue(
            "it carries the `npu: ` prefix every line of this tier's diagnostics carries",
            NpuDiag.tierRebuild("npu", "npu-turbo").startsWith("npu: "),
        )
        assertEquals(
            "and that prefix is ONE contiguous literal in NpuDiag.kt — the same rule, checked " +
                "the same way, as `npu: fallback rebuild stage=`",
            1,
            count(diag, "\"npu: tier rebuild from="),
        )
    }
}
