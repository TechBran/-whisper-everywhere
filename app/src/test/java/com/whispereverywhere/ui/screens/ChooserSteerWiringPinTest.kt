package com.whispereverywhere.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.File
import org.junit.Test

/**
 * BOTH CHOOSERS' STEER WIRING, pinned structurally (3.7 Workstream H, Task H4).
 *
 * `ModelTierCopyTest` pins `orderedForLanguageTag` exhaustively, because it is pure. What no test
 * in this suite can see is whether the two SURFACES actually render it:
 *
 *  1. `OnboardingFlowScreen`'s guided card loop and its `TierChoiceCard` call.
 *  2. `OnboardingModelScreen`'s Settings picker loop and its `ModelTierCard` call.
 *
 * Both are `@Composable`; Compose UI testing is `androidTest`-only in this project (there is no
 * Robolectric and no mocking framework on the unit-test classpath), and instrumented runs are
 * forbidden in this environment. So this pins the CALL, structurally — the same instrument and the
 * same argument as `UnsupportedTierGatePinTest`, `CapSeamPinTest` and `InFlightStripWiringPinTest`.
 *
 * **The mutations this class closes**, all of which compile and all of which leave every other test
 * in the suite green:
 *  - *Either loop reverted to `WhisperCatalog.pickable`.* The order becomes catalog order, so a
 *    Bengali user is offered the English-only tier first again — the ordering function stays green
 *    because nothing calls it.
 *  - *The two adjacent Booleans transposed.* `TierChoiceCard` takes `steered: Boolean` immediately
 *    before `selected: Boolean`; a positional call swapping them is compile-clean and puts the
 *    STEER badge on whichever card the user just tapped while the selection highlight sits on the
 *    steered card regardless of the tap. Nothing in the type system sees it. The guard is that the
 *    needles below quote the fully NAMED call form, so a positional rewrite fails the pin.
 *  - *The highlight re-bound to the catalog default.* `isSteered = model.id ==
 *    WhisperCatalog.DEFAULT_MODEL_ID` restores exactly the Bengali-review defect: `DEFAULT_MODEL_ID`
 *    is `"pro"`, English-only, for everyone on earth.
 *  - *The parameter renamed back to `isDefault`.* It no longer means "default" — it means "steered",
 *    and after this task it drives `STEER_BADGE`. A name that contradicts its value outlives every
 *    comment, so the truthful name is pinned rather than merely commented (H3 review, m2b).
 *  - *The device locale dropped.* Both surfaces must pass a full `Locale.toLanguageTag()`; H3's
 *    battery row (a) measured that a bare language code hides separator/case bugs.
 *
 * **4.0 (Q7b) — the gated `npu` tier joins the lineup, and adds three more mutations of the same
 * family.** The ordering and steer calls are now the `…For(languageTag, npuAvailable)` pair, which
 * is why the two 3.7 needles below read differently from the ones this class shipped with; the
 * `WhisperCatalog.pickable` count stays at ZERO on both files, because that assertion encodes the
 * Bengali review and nothing about a new tier makes catalog order acceptable.
 *  - *The gate answer hardcoded at one of the two call sites.* `steerIdForLanguageTagFor(tag,
 *    false)` beside `orderedForLanguageTagFor(tag, npuAvailable)` compiles, and puts the STEER
 *    badge and the selection highlight on `multi` while `npu` sits above it wearing neither. The
 *    guard is that both calls are counted with the SAME argument list.
 *  - *`withContext(Dispatchers.IO)` dropped from the producer.* `produceState`'s block runs in the
 *    composition's context, i.e. Main. The gate answer dlopens `libQnnSystem.so` and
 *    `libQnnHtp.so`, and `QnnAsrNative`'s threading contract forbids Main for every entry point.
 *    Removing one wrapper is compile-clean and moves a real dynamic-link load onto the UI thread.
 *  - *`pickedTierId` given a non-null initial value.* The 4.0 steer can now name a tier whose two
 *    context binaries are 358 MB; a chooser that preselects the steered card turns "we suggest"
 *    into "we chose", which is the one thing the steer has never been allowed to do.
 *
 * **A THIRD file joins the two screens: `WhisperEverywhereApp.kt`,** which owns the gate both
 * surfaces consume. It is read here rather than in a class of its own because the wiring and the
 * value it carries are one subject — a needle proving the screens pass the gate answer around
 * correctly is worth little if the thing producing it has quietly lost half of itself.
 * See [theOfferGateKeepsBothHalvesAndGuardsEveryBuildFieldItReads].
 *
 * **4.1 (L5): the gate answer became `Set<String>`** — `offeredNpuTierIds()`, one id per gated
 * tier whose own files are on disk — because two gated tiers can be independently installed and a
 * Boolean cannot say which. Every needle that carried `npuAvailable` carries `npuTierIds` now;
 * the `WhisperCatalog.pickable` zero-counts are UNCHANGED, because they encode the Bengali review
 * and nothing about a second gated tier makes catalog order acceptable. The conjunction inside
 * the gate also flipped (installed half first, probe conditional) — see
 * [theProbeRunsOnlyWhenAGatedPairIsAlreadyOnDisk], which re-spells the pin that used to enforce
 * the eager form.
 *
 * **4.2 F6: the guided flow's producer became the UNION `offeredNpuTierIds() +
 * fetchableNpuTierIds()`** — a conscious re-spell of the flow's producer needle (the old
 * offered-only needle tripped first, as a pin should). The union is a DISPLAY/steer set: on a
 * capable fresh Play install the offered half is empty and the fetchable half names both gated
 * tiers, so L9's ordering puts turbo at the head with zero new rules. The `WhisperCatalog.pickable`
 * live-zeros are UNCHANGED (the Bengali-review encoding does not relax for a storefront), the
 * Build-read counts are unchanged, and two new pins join: the fetchable set must never reach a
 * routing surface, and the language step / gated-fetch wiring is held as source
 * ([theLanguagePickWritesTheOneStoreAndTheGatedFetchRidesThePackController]).
 *
 * **4.2 F7: the Settings picker's producer joins the union** — the same conscious re-spell the
 * flow's needle took at F6, tripped by name first — and the picker gains the fetch affordance,
 * whose wiring pins live in `NpuImportWiringPinTest` beside the import wiring they extend. Here:
 * the union needle, the panel's installed-wording gate re-spelled to the DISK set (lineup
 * membership stopped meaning installed), the picker's process-scoped fetch mirror and
 * confirm-once idiom, and the M-3 attach guard on the ViewModel's gated route. The
 * `WhisperCatalog.pickable` live-zeros and the routing files' `fetchable` live-zeros are
 * UNCHANGED — a fetchable tier still must never route.
 *
 * **The source is read LF-NORMALISED.** `core.autocrlf=true` checks this repo out with CRLF, so a
 * needle written with a bare `\n` finds nothing and every assertion would pass or fail for the wrong
 * reason. The normalisation happens once, at each read site below.
 *
 * **Everything here is SYMBOL-SCOPED and no line numbers are used** — every anchor this workstream
 * inherited from the plan had drifted, by up to ~155 lines.
 */
class ChooserSteerWiringPinTest {

    private fun source(relative: String): File {
        var dir: File? = File(System.getProperty("user.dir")!!).absoluteFile
        while (dir != null) {
            for (candidate in listOf(File(dir, relative), File(dir, "app/$relative"))) {
                if (candidate.isFile) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError("cannot locate $relative from ${System.getProperty("user.dir")}")
    }

    private fun read(relative: String): String =
        source(relative).readText().replace("\r\n", "\n")

    private val flow: String by lazy {
        read("src/main/java/com/whispereverywhere/ui/screens/OnboardingFlowScreen.kt")
    }

    private val picker: String by lazy {
        read("src/main/java/com/whispereverywhere/ui/screens/OnboardingModelScreen.kt")
    }

    /** The third file (4.0, Q7b micro-round): the gate BOTH surfaces above consume. */
    private val app: String by lazy {
        read("src/main/java/com/whispereverywhere/WhisperEverywhereApp.kt")
    }

    /** The fourth (4.0, Q7b fix round, I1): where the gate's re-read key is bumped. */
    private val prefs: String by lazy {
        read("src/main/java/com/whispereverywhere/data/local/PreferencesManager.kt")
    }

    /** The fifth (4.1, m2): the Settings row that opens the chooser must not describe its lineup. */
    private val settings: String by lazy {
        read("src/main/java/com/whispereverywhere/ui/screens/SettingsScreen.kt")
    }

    /** The sixth (4.2 F6): the gated fetch's ViewModel wiring — coroutine-bound, pinned as source. */
    private val setupVm: String by lazy {
        read("src/main/java/com/whispereverywhere/ui/onboarding/OnboardingSetupViewModel.kt")
    }

    private fun count(haystack: String, needle: String) = haystack.split(needle).size - 1

    /**
     * [count] over LIVE lines only — the comment filter `NpuDiagTest.liveLineCount` and
     * `UnsupportedTierGatePinTest` use, added here with the 4.1 L5 sweep. Zero-counts on retired
     * spellings MUST be live-scoped: a truthful comment naming the old form would otherwise turn
     * them red and invite pin deletion (the L3 review's false-RED class).
     */
    private fun liveLineCount(haystack: String, needle: String): Int =
        haystack.lineSequence().count { line ->
            val trimmed = line.trimStart()
            val commented = trimmed.startsWith("//") || trimmed.startsWith("/*") ||
                trimmed.startsWith("*")
            !commented && line.contains(needle)
        }

    /** A multi-line needle, written as its own source lines so indentation is part of the match. */
    private fun block(vararg lines: String) = lines.joinToString("\n")

    /**
     * Byte offset of a needle's first LIVE occurrence — `NpuDiagTest`'s idiom, borrowed for the
     * order claim below (F7 micro-round). A count cannot express an order, and the m-1 fix is
     * entirely an order.
     */
    private fun offsetOfLive(scope: String, needle: String): Int {
        var at = 0
        for (line in scope.split("\n")) {
            val trimmed = line.trimStart()
            val commented =
                trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*")
            if (!commented && line.contains(needle)) return at
            at += line.length + 1
        }
        return -1
    }

    @Test
    fun theGuidedFlowOffersTheOrderedListAndNeverRawCatalogOrder() {
        // 4.3 fix round (I-3) RE-SPELL: the ordering call is now bound to a name, because the
        // revalidation guard and the cards must be validated against ONE list — two calls could
        // drift and the guard would then clear a pick whose card is still on screen (or keep one
        // whose card is gone). The claim is unchanged: this chooser renders the ORDERED list,
        // resolved through the catalog, and never raw catalog order.
        assertEquals(
            "the guided chooser renders orderedForLanguageTagFor, resolved through the catalog",
            1,
            count(
                flow,
                block(
                    "        lineup",
                    "            .mapNotNull { WhisperCatalog.byId(it) }",
                ),
            ),
        )
        assertEquals(
            "and that name is bound from the ordering rule, not assembled some other way",
            1,
            count(
                flow,
                "        val lineup = ModelTierCopy.orderedForLanguageTagFor(languageTag, " +
                    "npuTierIds, alsoOfferedIds)",
            ),
        )
        assertEquals(
            "the guided chooser never iterates WhisperCatalog.pickable directly: catalog order " +
                "offers the English-only tier first to everyone, which is the Bengali review",
            0,
            count(flow, "WhisperCatalog.pickable"),
        )
        assertEquals(
            "the steer reads the device's full language tag, not a bare language code",
            1,
            count(flow, "java.util.Locale.getDefault().toLanguageTag()"),
        )
        // H4 review, n1. Everything else in this class pins what `steerId` DRIVES. Without this
        // needle, rebinding it to `WhisperCatalog.DEFAULT_MODEL_ID` (or the bare literal "pro")
        // compiles clean, leaves `languageTag` live — the ordering call still consumes it, so not
        // even an unused-variable warning — and kills nothing. The badge and the highlight then
        // land on the English-only tier for every user on earth while the ORDERING stays correct:
        // half-right, and harder to diagnose than the original Bengali-review defect.
        assertEquals(
            "the guided flow's steerId comes from steerIdForLanguageTagFor, not a catalog " +
                "default or a hardcoded tier id",
            1,
            count(
                flow,
                "val steerId = ModelTierCopy.steerIdForLanguageTagFor(languageTag, npuTierIds)",
            ),
        )
        // 4.2 F6: the producer is the UNION — offered (installed + capable) plus fetchable (the
        // family's measured, deliverable, not-yet-installed tiers). A DISPLAY/steer set; the
        // steer and ordering calls below consume it unchanged, which is the whole mechanism by
        // which a capable fresh Play install leads with turbo.
        assertGateAnswerReachesBothCallsOffMain(
            flow,
            "the guided flow",
            block(
                "        val installGeneration by ModelInstallSignal.generation.collectAsState()",
                "        val npuTierIds by produceState(initialValue = emptySet<String>(), key1 = installGeneration) {",
                "            value = withContext(Dispatchers.IO) {",
                "                val app = WhisperEverywhereApp.getInstance()",
                "                app.offeredNpuTierIds() + app.fetchableNpuTierIds()",
                "            }",
                "        }",
            ),
            // 4.3: this surface's third argument is the RULE's answer, not the raw disk set —
            // onboarding is the one place a narrowed lineup can wedge a mandatory step.
            alsoOfferedArg = "alsoOfferedIds",
        )
    }

    /**
     * 4.3 — **THE NO-WEDGE ESCAPE SURVIVES THE NARROWING**, wired on the surface that needs it.
     *
     * 4.3 narrows a capable device's chooser to `npu-turbo` alone. A SIDELOADED capable device is
     * offered that card — `fetchableNpuTierIds` asks the census whether the FAMILY has a measured
     * pack, and cannot know Play will refuse this install — so the fetch fails, F6's escape sends
     * the user back to the chooser, and a chooser holding one undeliverable card wedges a
     * MANDATORY step. `OnboardingLogicTest` executes the rule; what it cannot see is whether this
     * screen asks it, and whether the latch it depends on is ever set.
     *
     * **The mutations this closes**, both compile-clean and both green everywhere else:
     *  - *The latch never set.* `onChooseAgain` without `oneTierDeliveryFailed = true` leaves the
     *    rule permanently answering "not failed", which is the wedge with a rule bolted beside it.
     *  - *The rule bypassed.* Passing `installedIds` straight to the ordering call — the picker's
     *    own correct spelling — is one word, and it re-opens the wedge on this surface only.
     */
    @Test
    fun theOnboardingChooserSuspendsTheOneTierRuleOnceDeliveryHasFailed() {
        assertEquals(
            "the flow composes the pure rule rather than deciding the suspension itself",
            1,
            count(
                flow,
                block(
                    "        val alsoOfferedIds =",
                    "            OnboardingLogic.chooserAlsoOfferedIds(installedIds, " +
                        "oneTierDeliveryFailed)",
                ),
            ),
        )
        // 4.3 fix round (I-2) RE-SPELL: the latch is no longer set unconditionally. The escape is
        // still offered for every Failed terminal — that is the no-wedge contract and it did not
        // change — but only a genuine DELIVERY failure of the gated tier suspends the ruling, so
        // a user's own cancel or a busy-refusal can no longer permanently restore the menu the
        // owner just removed. The latch is OR-ed, never overwritten: one undeliverable answer
        // stands however many cancels follow it.
        assertEquals(
            "the escape sets the latch ONLY on a real delivery failure, and never clears it",
            1,
            count(
                flow,
                block(
                    "                        onChooseAgain = { deliveryFailed ->",
                    "                            if (deliveryFailed) oneTierDeliveryFailed = true",
                    "                            pickedTierId = null",
                    "                            setupVm.resetSpeechForReChoice()",
                    "                        },",
                ),
            ),
        )
        assertEquals(
            "and the answer comes from the pure rule, computed where the REASON and the TIER are " +
                "both in scope — nowhere above the engine card has either",
            1,
            count(
                flow,
                block(
                    "            val deliveryFailed = OnboardingLogic.oneTierDeliveryFailed(",
                    "                pickedTierId, (speech as? EngineState.Failed)?.message.orEmpty(),",
                    "            )",
                ),
            ),
        )
        assertEquals(
            "the latch has exactly ONE write site — the guarded one the block above pins. A " +
                "second, unguarded write anywhere would restore the menu for reasons the rule " +
                "just finished excluding, and the block needle alone could not see it",
            1,
            liveLineCount(flow, "oneTierDeliveryFailed = true"),
        )
        assertEquals(
            "the latch is DURABLE screen state, not a read of the engine state — " +
                "resetSpeechForReChoice() returns that state to Pending on the way back, so by " +
                "the time the chooser renders the failure is over",
            1,
            count(flow, "var oneTierDeliveryFailed by remember { mutableStateOf(false) }"),
        )
        assertEquals(
            "and it reaches the step it governs",
            1,
            count(flow, "oneTierDeliveryFailed = oneTierDeliveryFailed,"),
        )
        assertEquals(
            "the flow never iterates the ungated catalog to build that escape — the CPU ids join " +
                "through the pure rule, so catalog ORDER never reaches a card (the Bengali review)",
            0,
            count(flow, "WhisperCatalog.pickable"),
        )
    }

    /**
     * 4.3 fix round (I-3) — **THE PICK MUST NOT OUTLIVE ITS CARD.**
     *
     * Both producers on the engines step are async (the gate's first read dlopens ~7.9 MiB of
     * QNN), so a capable device renders the pre-4.3 `[pro, multi]` lineup for that window and
     * then narrows to `[npu-turbo]`. A tap inside the window survived the narrowing: the card
     * vanished, `pickedTierId` kept its value, `tierPicked` stayed true, and the footer's Download
     * wrote `prefs.selectedModelId = pro|multi` **on a capable device with no card on screen for
     * it** — the outcome the ruling forbids, reached by a user who did nothing wrong. Pre-4.3 the
     * race existed and was harmless, because a pick's card never left the list; 4.3 made a lineup
     * able to shrink under a pick, so 4.3 owns the guard.
     *
     * `OnboardingLogicTest` executes the rule. This holds the screen to (a) keying the guard on
     * the LINEUP — the thing that moves — and (b) actually being able to clear the pick, which a
     * non-null `onPick` signature cannot express.
     */
    @Test
    fun theTierPickIsRevalidatedWheneverTheLineupMovesUnderIt() {
        assertEquals(
            "the lineup is computed ONCE and named, so the guard and the cards cannot be " +
                "validated against two different lists",
            1,
            count(
                flow,
                "        val lineup = ModelTierCopy.orderedForLanguageTagFor(languageTag, " +
                    "npuTierIds, alsoOfferedIds)",
            ),
        )
        assertEquals(
            "the guard is keyed on the LINEUP, so it re-runs exactly when the list moves — " +
                "keyed on anything else (or unkeyed) it cannot see the narrowing it exists for",
            1,
            count(
                flow,
                block(
                    "        LaunchedEffect(lineup) {",
                    "            val kept = OnboardingLogic.revalidatePick(pickedTierId, lineup)",
                    "            if (kept != pickedTierId) onPick(kept)",
                    "        }",
                ),
            ),
        )
        assertEquals(
            "and the pick callback is NULLABLE — a guard that cannot clear the pick is not a " +
                "guard, and the type is what makes that impossible to regress",
            1,
            count(flow, "    onPick: (String?) -> Unit,"),
        )
        assertEquals(
            "the cards render the SAME named lineup the guard validated against",
            1,
            count(
                flow,
                block(
                    "        lineup",
                    "            .mapNotNull { WhisperCatalog.byId(it) }",
                ),
            ),
        )
    }

    /**
     * The 4.0 half, shared by both surfaces and re-spelled for the 4.1 set: the gate answer is
     * produced OFF Main, and the SAME answer feeds the steer and the ordering.
     *
     * Two assertions, and neither is redundant. The producer needle pins WHERE the answer comes
     * from — `offeredNpuTierIds()` composes the per-tier installed check, the SoC gate and the
     * QNN probe, and its first call on a device with assets dlopens `libQnnSystem.so` and
     * `libQnnHtp.so`, so `withContext(Dispatchers.IO)` inside `produceState` is the difference
     * between a background load and one on the UI thread (`produceState`'s block runs in the
     * composition's context, which is Main). The count of TWO pins that the answer is not then
     * dropped at one of the two places that consume it: an `emptySet()` literal in either call is
     * compile-clean and separates the STEER badge from the card the lineup actually led with.
     */
    private fun assertGateAnswerReachesBothCallsOffMain(
        source: String,
        surface: String,
        producer: String,
        alsoOfferedArg: String,
        booleanKeyedProducers: Int = 0,
    ) {
        assertEquals(
            "$surface's npu gate answer is produced off Main from the app gate",
            1,
            count(source, producer),
        )
        // 4.3 RE-SPELL (in-commit — the old exact-paren needle tripped by name first, as a pin
        // should): the ordering call gained a third argument, `installedIds`, so the two calls no
        // longer share a byte-identical tail. The claim is UNCHANGED and is the one that matters —
        // the same gate answer reaches BOTH calls — so the needle is the shared prefix.
        assertEquals(
            "$surface passes the SAME gate answer to the steer AND the ordering — an " +
                "`emptySet()` literal in either one badges a card the lineup did not lead with",
            2,
            count(source, "(languageTag, npuTierIds"),
        )
        // 4.3: and the ordering call is the one that carries the installed set. Without it a
        // capable device's lineup is `npu-turbo` ALONE even for a user who already has `multi` on
        // disk — the card for a model they downloaded vanishes, which is the exact disturbance
        // the branch's non-disturbance rule forbids. Compile-clean to drop (the parameter is
        // defaulted, so that the gate-fail path stays one argument shorter), so it is pinned.
        assertEquals(
            "$surface hands the ordering the ids that join a one-card lineup anyway, so an " +
                "existing install keeps its card on a device the 4.3 rule narrowed to one",
            1,
            count(source, "orderedForLanguageTagFor(languageTag, npuTierIds, $alsoOfferedArg)"),
        )
        // The set behind it: produced OFF Main (isInstalled stats files) and keyed on the same
        // install generation, so a landing download or import reaches the lineup without the user
        // leaving the screen. Both surfaces spell it identically — one shape, two files.
        assertEquals(
            "$surface produces the installed set off Main, over the WHOLE catalog (a retired " +
                "but installed eco/base is a legal CPU fallback and `pickableFor`'s own " +
                "`!retired` filter is what keeps it out of the lineup), keyed on the generation",
            1,
            count(
                source,
                "val installedIds by produceState(initialValue = emptySet<String>(), " +
                    "key1 = installGeneration)",
            ),
        )
        // 4.0 Q7b fix round, I1. The producer needle above already contains `key1 =`, but this
        // says WHY out loud, because the tempting "simplification" is to delete the key rather
        // than the whole block. `produceState` with no key desugars to `remember { … }` +
        // `LaunchedEffect(Unit)`: the producer runs ONCE PER COMPOSITION ENTRY and never again.
        // `offeredNpuTierIds()` deliberately re-stats each gated tier's files on every call so an
        // import is visible immediately — and an unkeyed producer throws that away one layer up,
        // in the very composition Q8's import affordance lives in. The user imports the pair and
        // the lineup does not change.
        // 4.3 RE-SPELL: `npuTierIds by` now leads the needle, because the installed-set producer
        // above is spelled identically from `produceState` onward and would otherwise raise this
        // count to 2. Naming the variable keeps the assertion about the GATE producer, which is
        // what it has always been about; the sibling producer has its own count directly above.
        assertEquals(
            "$surface keys the gate producer on the install generation, so an import that lands " +
                "while the chooser is on screen re-reads the gate instead of being invisible until " +
                "the user navigates away and back",
            1,
            count(
                source,
                "val npuTierIds by produceState(initialValue = emptySet<String>(), " +
                    "key1 = installGeneration)",
            ),
        )
        // [booleanKeyedProducers] is the count of the picker's OTHER keyed device question —
        // `npuCapableDevice` alone, the import entry's capability-only gate, which must not be
        // gated on the tier already being installed (4.0, Q8; its own test below). The flow asks
        // no second question. A producer that lost its key drops a count rather than raising one.
        assertEquals(
            "$surface's capability-only producers, keyed identically",
            booleanKeyedProducers,
            count(source, "produceState(initialValue = false, key1 = installGeneration)"),
        )
        assertEquals(
            "$surface never samples a gate producer unkeyed",
            0,
            count(source, "produceState(initialValue = false) {") +
                count(source, "produceState(initialValue = emptySet<String>()) {"),
        )
    }

    @Test
    fun theInstallGenerationIsBumpedAtTheOneFunnelEveryInstallPathGoesThrough() {
        // The other end of I1's mechanism. `ModelInstallSignalTest` proves the counter changes;
        // this proves something actually turns it. Both are needed: a key nothing bumps makes the
        // two producers above decoration, which is the same class of hole as a pin nothing runs.
        assertEquals(
            "notifyModelInstalled bumps the Compose re-read key",
            1,
            count(prefs, "ModelInstallSignal.bump()"),
        )
        assertEquals(
            "and still emits the SharedFlow the bubble's prewarm collects — the counter is an " +
                "addition, not a replacement; `Unit` cannot serve as a Compose key and a key " +
                "cannot be collected as an event",
            1,
            count(prefs, "_modelInstalled.tryEmit(Unit)"),
        )
        // One function, so a future install path (Q8's SAF importer) cannot deliver half the news
        // by calling the one it happened to know about.
        assertEquals(
            "there is exactly one place that announces an install",
            1,
            count(prefs, "fun notifyModelInstalled() {"),
        )
    }

    @Test
    fun theGuidedFlowCardCallIsFullyNamedSoSteeredCannotTransposeWithSelected() {
        // `steered` and `selected` are adjacent Booleans. A positional call compiles, passes the
        // whole suite, and swaps the badge with the highlight. The named form IS the guard.
        assertEquals(
            "TierChoiceCard is called with every argument named, `steered` distinct from `selected`",
            1,
            count(
                flow,
                block(
                    "                TierChoiceCard(",
                    "                    model = model,",
                    "                    copy = ModelTierCopy.forId(model.id),",
                    "                    steered = model.id == steerId,",
                    "                    selected = pickedTierId == model.id,",
                    "                    onClick = { onPick(model.id) },",
                    "                )",
                ),
            ),
        )
        assertEquals(
            "the declaration keeps both Booleans, so the named call above cannot be satisfied by " +
                "a signature that quietly dropped one",
            1,
            count(flow, block("    steered: Boolean,", "    selected: Boolean,")),
        )
        // 4.0 (Q7b). `steered` and `selected` are only genuinely different things while NOTHING
        // starts selected. The steer can now name `npu` — 358 MB of context binaries — and a
        // chooser that preselects the steered card has stopped suggesting and started choosing,
        // on a tier whose whole contract is that the device decides and then the user does.
        // Pinned here rather than commented because the mutation is one word: `null` -> `"npu"`.
        assertEquals(
            "the guided flow preselects NOTHING: the steer moves a card to the top and badges " +
                "it, and the user still has to tap it",
            1,
            count(flow, "var pickedTierId by remember { mutableStateOf<String?>(null) }"),
        )
    }

    @Test
    fun theSteerBadgeLeadsTheChipsOnTheSteeredCardOnly() {
        assertEquals(
            "STEER_BADGE is prepended to the tier's own chips, gated on `steered`",
            1,
            count(
                flow,
                "val chips = if (steered) listOf(ModelTierCopy.STEER_BADGE) + c.badges " +
                    "else c.badges",
            ),
        )
        assertEquals("the chip row renders that list", 1, count(flow, "chips.forEach { badge ->"))
        assertEquals(
            "the chip row no longer bypasses the steer by rendering c.badges directly",
            0,
            count(flow, "c.badges.forEach { badge ->"),
        )
    }

    @Test
    fun theSettingsPickerOffersTheSameOrderedListFromTheSameRule() {
        assertEquals(
            "the Settings picker's model list comes from orderedForLanguageTagFor",
            1,
            count(
                picker,
                block(
                    "    val models = ModelTierCopy.orderedForLanguageTagFor(languageTag, " +
                        "npuTierIds, installedIds)",
                    "        .mapNotNull { WhisperCatalog.byId(it) }",
                ),
            ),
        )
        assertEquals(
            "the Settings picker never iterates WhisperCatalog.pickable directly",
            0,
            count(picker, "WhisperCatalog.pickable"),
        )
        assertEquals(
            "the steer reads the device's full language tag, not a bare language code",
            1,
            count(picker, "java.util.Locale.getDefault().toLanguageTag()"),
        )
        // H4 review, n1 — the picker half. `theSettingsPickerHighlightsTheSteerAndNeverTheCatalog
        // Default` forbids the DEFAULT_MODEL_ID *spelling* in this file, but a string literal
        // (`val steerId = "pro"`) evades it entirely. This needle pins the source of the value,
        // so both spellings of the same defect die here.
        assertEquals(
            "the Settings picker's steerId comes from steerIdForLanguageTagFor, not a catalog " +
                "default or a hardcoded tier id",
            1,
            count(
                picker,
                "val steerId = ModelTierCopy.steerIdForLanguageTagFor(languageTag, npuTierIds)",
            ),
        )
        // 4.2 F7: the producer is the UNION — the same DISPLAY/steer set F6 gave the flow
        // (offered = installed-and-capable; fetchable = census-deliverable, not yet arrived).
        // A conscious re-spell of this needle, resolved in-commit: the old offered-only
        // spelling tripped this test by name first, exactly as a pin should.
        assertGateAnswerReachesBothCallsOffMain(
            picker,
            "the Settings picker",
            block(
                "    val installGeneration by ModelInstallSignal.generation.collectAsState()",
                "    val npuTierIds by produceState(initialValue = emptySet<String>(), key1 = installGeneration) {",
                "        value = withContext(Dispatchers.IO) { app.offeredNpuTierIds() + app.fetchableNpuTierIds() }",
                "    }",
            ),
            // 4.3: Settings' picker passes the DISK set directly and needs no suspension rule —
            // it has no mandatory gate to wedge (a user reaches it with a model already chosen),
            // and its own escape from an undeliverable tier is the per-card import route the
            // F7 fix round put on every gated card.
            alsoOfferedArg = "installedIds",
            // 4.0 Q8: the picker's second device question, capability-only. Its own test below.
            booleanKeyedProducers = 1,
        )
    }

    /**
     * 4.0 (Q8) — the picker's SECOND device question, and why it is a second one.
     *
     * The offer gate requires each tier's files on disk. The import entry cannot be gated on it:
     * the gate is empty precisely in the state the import exists to leave, so an affordance
     * behind it could only ever appear once the 358 MB it fetches had already arrived. That is the
     * chicken-and-egg the Q7a handoff flagged, and the fix is that the entry reads the CAPABILITY
     * half alone.
     *
     * The mutation this closes is one identifier: `app.npuCapableDevice` ->
     * `app.offeredNpuTierIds().isNotEmpty()` (or any offered-set spelling) in the second
     * producer. It compiles, it leaves every other assertion in this class green, and it makes
     * the tier permanently un-installable on every device that does not already have it.
     */
    @Test
    fun theImportEntryIsGatedOnCapabilityAloneAndNeverOnTheTierBeingInstalled() {
        assertEquals(
            "the picker produces the CAPABILITY half separately, off Main and memoised",
            1,
            count(
                picker,
                block(
                    "    val npuCapable by produceState(initialValue = false, key1 = installGeneration) {",
                    "        value = withContext(Dispatchers.IO) { app.npuCapableDevice }",
                    "    }",
                ),
            ),
        )
        assertEquals(
            "the import panel's gate is `npuCapable` — the hardware — and nothing else. Gated on " +
                "the offer gate instead, the only route the asset pair has onto a device would " +
                "require the asset pair to already be on that device",
            1,
            count(picker, block("            if (npuCapable) {", "                NpuImportPanel(")),
        )
        assertEquals(
            "the offer gate is asked exactly once, for the lineup — not a second time as the " +
                "import's gate",
            1,
            count(picker, "app.offeredNpuTierIds()"),
        )
        assertEquals(
            "and the 4.0 Boolean view is consulted by NEITHER surface: the screens consume the " +
                "set, and the shim exists only for the service's single-tier routing until L8 " +
                "deletes it",
            0,
            count(picker, "isNpuTierOffered") + count(flow, "isNpuTierOffered"),
        )
        // What the panel SAYS keys on the npu (small) pair specifically — the pair this importer
        // installs — not on the set being non-empty, which a turbo-only device would satisfy
        // while the npu pair is still absent, telling the user a model is installed that is not.
        // RE-SPELLED at F7 (in-commit — the old spelling tripped this test by name first): the
        // lineup set became the union, so membership in it stopped meaning installed — a
        // fetchable npu with nothing on disk is in the set, and the old gate would have told
        // the user their model was installed. The wording gate reads the DISK set now, which
        // is what "installed" has meant on this panel since 4.0.
        assertEquals(
            "the panel's installed-wording gate is the npu tier's own INSTALLED membership",
            1,
            count(picker, "offered = NpuAssetImport.TIER_ID in installedIds,"),
        )
    }

    @Test
    fun theSettingsPickerHighlightsTheSteerAndNeverTheCatalogDefault() {
        assertEquals(
            "the highlighted card is the steered tier",
            1,
            count(picker, "val isSteered = model.id == steerId"),
        )
        assertEquals(
            "the picker never highlights DEFAULT_MODEL_ID: it is `pro`, English-only, for every " +
                "user on earth — exactly the defect the Bengali review reported",
            0,
            count(picker, "WhisperCatalog.DEFAULT_MODEL_ID"),
        )
        assertEquals(
            "ModelTierCard is called with every argument named, and the flag's name says `steered`",
            1,
            count(
                picker,
                block(
                    "                ModelTierCard(",
                    "                    model = model,",
                    "                    recommended = recommended,",
                    "                    isSteered = isSteered,",
                ),
            ),
        )
    }

    @Test
    fun theFlagIsNamedForWhatItMeansAndDrivesTheSteerBadge() {
        // H3 review, m2b: the plan rebound the existing `isDefault` to mean "steered". The
        // rebinding is right; keeping the old NAME would have been a lie with a shelf life longer
        // than any comment. Both the declaration and every binding are pinned to the true name.
        assertEquals(
            "the declaration's flag is `isSteered`, adjacent to `recommended`",
            1,
            count(picker, block("    recommended: Boolean,", "    isSteered: Boolean,")),
        )
        listOf("isDefault: Boolean", "val isDefault", "isDefault = ", "isDefault ->").forEach {
            assertEquals(
                "the picker still declares, binds or passes something called <<$it>>, which now " +
                    "means \"steered\" — rename it rather than re-documenting it",
                0,
                count(picker, it),
            )
        }
        assertEquals(
            "the highlighted card's chip names the reason, from the single pinned constant",
            1,
            count(picker, "TierBadge(text = ModelTierCopy.STEER_BADGE, color = Primary)"),
        )
        assertTrue(
            "the bare \"Default\" chip is gone: it never explained why this card, and for a " +
                "non-English user it named the wrong tier",
            !picker.contains("TierBadge(text = \"Default\""),
        )
    }

    /**
     * The gate itself (4.0, Q7b micro-round; re-spelled for the 4.1 set) — the one thing both
     * surfaces above agree to obey.
     *
     * **Every assertion here closes a MEASURED survivor.** Neither half of the gate can be
     * executed by a JVM test — `npuCapableDevice` dlopens two QNN libraries and `isInstalled`
     * needs a `Context` — so the battery's T11 and T12 both survived all 1453 tests. That is the
     * same hole, for the same reason, as `UnsupportedTierGatePinTest`'s R14, and it is closed with
     * the same instrument: what cannot be run is pinned as source.
     */
    @Test
    fun theOfferGateKeepsBothHalvesAndGuardsEveryBuildFieldItReads() {
        assertEquals(
            "the offer gate keeps BOTH halves (battery T11's claim, re-spelled for the set): " +
                "without the capable conjunct the installed set is offered on any device whose " +
                "files happen to exist — `offered` must be gated on `capable == true`",
            1,
            count(app, "val offered: Set<String> = if (capable == true) installed else emptySet()"),
        )
        assertEquals("and the gate returns exactly that value", 1, count(app, "return offered"))
        assertEquals(
            "the installed half is a live per-tier stat over the GATED flag — no id literal, so " +
                "a card renders exactly when ITS pair is on disk and the next gated tier joins " +
                "by structure",
            1,
            count(app, ".filter { it.gated && whisperModelManager.isInstalled(it) }"),
        )
        // The reporting call must not become the decision. `NpuGate.isSocSupported` is called here
        // only to recover which HALF of `capable` answered, for the diagnostic line; routing the
        // gate through it would drop the probe entirely and offer the tiers on any SM8650 whose
        // QNN stack does not load.
        assertEquals(
            "NpuGate is consulted once, for reporting, and never as the gate itself",
            1,
            count(app, "socSupported = NpuGate.isSocSupported(npuSocModel, npuSocManufacturer),"),
        )
        // The two conjuncts' ORDER, unlike 4.0's, IS asserted now — but in its own test
        // (theProbeRunsOnlyWhenAGatedPairIsAlreadyOnDisk), because 4.1 made it load-bearing:
        // the probe's evaluation is conditional on the installed half, which is a cost claim,
        // not a truth-value claim. The 4.0 comment that declined to pin the order was right for
        // the shape it described.
        assertEquals(
            "the memo survives (battery T11's neighbour): without `by lazy` the probe's dlopen " +
                "runs again on every read, and the readers are recomposing choosers",
            1,
            count(app, "val npuCapableDevice: Boolean by lazy {"),
        )
        // battery T12. minSdk is 26 and both SOC fields arrived in API 31, so an unguarded read
        // throws NoSuchFieldError on every pre-S device that opens the chooser — a crash, on a
        // large share of the install base, on a screen that has nothing to do with the NPU tiers.
        // Counting the GUARDED form against the TOTAL is what closes it: a second, unguarded read
        // raises the total without raising the guarded count, so it cannot be added quietly.
        listOf("SOC_MODEL", "SOC_MANUFACTURER").forEach { field ->
            assertEquals(
                "Build.$field is read exactly once in this file",
                1,
                count(app, "Build.$field"),
            )
            assertEquals(
                "that one read of Build.$field carries the API-31 guard, handing NpuGate the null " +
                    "it denies for the whole pre-S population",
                1,
                count(
                    app,
                    "if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.$field else null",
                ),
            )
        }
    }

    /**
     * Q7b NEW-1 / m3, folded into L5 because the gate's shape changed anyway.
     *
     * The 4.0 conjunction read the capable half first and unconditionally, so every 8 Gen 3
     * paid the ~7.9 MiB QNN dlopen at bubble-service start — above the `delay(1500)` that exists
     * to keep boot work out of app launch — whether or not a pair was ever imported. And the OLD
     * pin in the test above (`val capable = npuCapableDevice` beside `return capable &&
     * installed`) ENFORCED that eager form, which is why NEW-1 was never a one-liner. This is
     * that pin, re-spelled against the flipped shape it had to become.
     */
    @Test
    fun theProbeRunsOnlyWhenAGatedPairIsAlreadyOnDisk() {
        assertEquals(
            "the installed half is computed FIRST, over every gated catalog tier",
            1,
            count(
                app,
                block(
                    "        val installed = WhisperCatalog.entries",
                    "            .filter { it.gated && whisperModelManager.isInstalled(it) }",
                    "            .map { it.id }",
                    "            .toSet()",
                ),
            ),
        )
        assertEquals(
            "the ~7.9 MiB QNN dlopen is CONDITIONAL on it: a device with no gated pair on disk " +
                "never pays the probe, and `null` records that it was NOT evaluated — which the " +
                "offer line reports as `skipped` rather than inventing a verdict",
            1,
            count(app, "val capable: Boolean? = if (installed.isEmpty()) null else npuCapableDevice"),
        )
        assertEquals(
            "the eager form is gone from live code",
            0,
            liveLineCount(app, "val capable = npuCapableDevice"),
        )
        assertEquals(
            "the old npu-entry resolution is gone with it: the gate iterates the gated FLAG, so " +
                "the next gated tier joins by structure rather than being remembered in by id",
            0,
            liveLineCount(app, "WhisperCatalog.byId(\"npu\")"),
        )
        assertEquals(
            "the 4.0 Boolean shim is GONE — this needle fired at L8 exactly as its own message " +
                "promised (\"4.1 L8's per-tier re-thread deletes both together\") and is " +
                "re-specified here as the zero it announced: routing takes the SET now, the " +
                "shim's one consumer went with it, and a revived Boolean view would be a second " +
                "derivation of the gate that one bit cannot even express (WHICH of two " +
                "independently-installed tiers?). Live-zero, so the deletion note in the KDoc " +
                "can say the name without resurrecting it",
            0,
            liveLineCount(app, "isNpuTierOffered"),
        )
    }

    /**
     * Q7b M2, folded here — hole (i) was called "the widest-blast-radius test hole on the
     * branch". The loop in the gate test counts QUALIFIED reads (`Build.SOC_MODEL`); a member
     * import lets a later reader spell the same read as bare `SOC_MODEL`, which raises neither
     * the total nor the guarded count — an unguarded `NoSuchFieldError` on every pre-S device
     * that opens the chooser, invisible to the pin. The star form un-qualifies both fields at
     * once, and an aliased import (`as X`) hides the read under a name no needle knows.
     */
    @Test
    fun noImportCanTurnABuildFieldReadBare() {
        listOf("SOC_MODEL", "SOC_MANUFACTURER").forEach { field ->
            assertEquals(
                "no member import of Build.$field — it would let a bare `$field` read bypass " +
                    "the qualified-read counts",
                0,
                count(app, "import android.os.Build.$field"),
            )
        }
        assertEquals(
            "no Build member import AT ALL — named, star or aliased. The class import " +
                "(`import android.os.Build`) is the only sanctioned spelling, because it is what " +
                "keeps every field read countable as `Build.<FIELD>`",
            0,
            count(app, "import android.os.Build."),
        )
    }

    /**
     * 4.1 m2. The Settings entry row's subtitle enumerated `WhisperCatalog.pickable`'s display
     * names — a device-INDEPENDENT constant describing a device-DEPENDENT lineup, wrong by two
     * tiers on a gate-passing device with both pairs imported. The row now invites rather than
     * enumerates; the same Bengali-review-encoding zero-count the two chooser surfaces carry.
     */
    @Test
    fun theSettingsEntryRowStopsEnumeratingALineupItCannotKnow() {
        assertEquals(
            "the Settings entry point renders no tier enumeration from the ungated catalog",
            0,
            count(settings, "WhisperCatalog.pickable"),
        )
        assertEquals(
            "the row keeps its invitation copy",
            1,
            count(settings, "subtitle = \"Pick a speech-model tier\","),
        )
    }

    /**
     * 4.2 F6 — the fetchable set is a CHOOSER fact, and it must never route.
     *
     * `offeredNpuTierIds` (installed AND capable) is what every routing surface reads: the
     * service's memo feeds the selector, and a session routed to a tier whose pair is not on
     * disk dies at load with a user-facing decline. `fetchableNpuTierIds` says only "Play could
     * deliver this" — it joins the two chooser lineups and NOTHING else. The mutation this
     * closes is one identifier in either routing file: compile-clean, green everywhere else,
     * and it routes sessions to models that do not exist.
     */
    @Test
    fun theFetchableSetIsAChooserFactAndNothingThatRoutesASessionReadsIt() {
        val service = read("src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt")
        val selector = read("src/main/java/com/whispereverywhere/transcription/NpuBackendSelector.kt")
        assertEquals(
            "the service's routing memo never reads the fetchable set",
            0,
            liveLineCount(service, "fetchable"),
        )
        assertEquals(
            "the selector never hears of it either",
            0,
            liveLineCount(selector, "fetchable"),
        )
        assertEquals(
            "the Android binding exists exactly once, in the app object beside the offer gate",
            1,
            count(app, "fun fetchableNpuTierIds(): Set<String> {"),
        )
        assertEquals(
            "and it derives from the census's executed truth table, never a second derivation",
            1,
            count(app, "return NpuFleetCensus.fetchableTierIds("),
        )
        // The family conjunct runs FIRST so the whole off-census fleet answers empty without
        // paying the ~7.9 MiB QNN dlopen — the offer gate's installed-first cost shape, kept.
        assertEquals(
            "the probe is conditional on a resolved census family",
            1,
            count(app, "capable = npuSocFamily != null && npuCapableDevice,"),
        )
        assertEquals(
            "the offer gate itself never consults the fetchable set — offered stays installed " +
                "AND capable, exactly as every routing reader assumes",
            0,
            count(
                app.substringAfter("fun offeredNpuTierIds(): Set<String> {")
                    .substringBefore("\n    }"),
                "fetchable",
            ),
        )
    }

    /**
     * 4.2 F6 — the language step writes the ONE store, and the gated fetch rides the pack
     * controller with PLAY'S OWN consent dialog. Both are Android-bound (a Compose step and a
     * viewModelScope branch), so the wiring is pinned as source — the house instrument, the
     * same one every other test in this class uses.
     */
    @Test
    fun theLanguagePickWritesTheOneStoreAndTheGatedFetchRidesThePackController() {
        // The 3.8 shape: the pick writes the EXISTING selected_language pref through the
        // existing setter, exactly once — no new storage anywhere in the flow.
        assertEquals(
            "Continue writes the one existing language store, once",
            1,
            count(flow, ".preferencesManager.setSelectedLanguage(picked)"),
        )
        assertEquals(
            "the language Continue is gated on a real pick — the 3.8 forced choice",
            1,
            count(flow, "enabled = OnboardingLogic.languageContinueEnabled(pickedLanguage),"),
        )
        assertEquals(
            "the language step preselects NOTHING: the device row renders first and badged, " +
                "and the user still taps",
            1,
            count(flow, "var pickedLanguage by remember { mutableStateOf<String?>(null) }"),
        )
        // The gated branch: ensureSpeech hands a gated, not-installed tier to the pack
        // controller — the download path below it stays byte-for-byte the non-gated route,
        // which is what keeps DownloadManager's delete-first behaviour away from a pair it
        // could never re-fetch.
        assertEquals(
            "ensureSpeech routes gated tiers to the pack flow before the download path",
            1,
            count(setupVm, "        if (model.gated) {"),
        )
        assertEquals(
            "and the gated branch dispatches and RETURNS — the download path is unreachable " +
                "for a gated tier",
            1,
            count(
                setupVm,
                block(
                    "            ensureGatedSpeech(model)",
                    "            return",
                ),
            ),
        )
        assertEquals(
            "the gated route starts the F5 controller on the selected tier",
            1,
            count(setupVm, "NpuPackController.start(appInstance, model.id)"),
        )
        // 4.2 F7 (F6 review M-3, landed by name): the start Boolean is CONSUMED and the attach
        // is guarded through the pure executed rule — a controller busy with ANOTHER tier's
        // fetch (the F7 chooser can start one) is refused by name and never mirrored; an
        // attached collector would, on that fetch's Installed, have persisted selectedModelId
        // for a tier this card never fetched.
        assertEquals(
            "the gated route consumes start()'s answer",
            1,
            count(setupVm, "val started = NpuPackController.start(appInstance, model.id)"),
        )
        assertEquals(
            "and refuses to attach to another tier's fetch, through the pure rule — asserted " +
                "as one block so the consult, the Failed publish and the return cannot be " +
                "separated",
            1,
            count(
                setupVm,
                block(
                    "        val refusal = OnboardingLogic.fetchAttachRefusal(",
                    "            started, NpuPackController.activeTier.value, model.id,",
                    "        )",
                    "        if (refusal != null) {",
                    "            _speechState.value = EngineState.Failed(refusal)",
                    "            return",
                    "        }",
                ),
            ),
        )
        assertEquals(
            "every fetch state reaches the card through the ONE pure mapping — no second " +
                "translation can drift from the tested table",
            1,
            count(setupVm, ".map(OnboardingLogic::engineStateForFetch)"),
        )
        assertEquals(
            "the collector stops at the first terminal state, so a later fetch for another " +
                "tier is never mirrored onto this card",
            1,
            count(setupVm, ".first { it is EngineState.Ready || it is EngineState.Failed }"),
        )
        // Play's own >200 MB cellular dialog: shown once per ENTRY into NeedsConfirmation
        // (LaunchedEffect keyed on the state VALUE), and no custom re-ask exists anywhere —
        // the consent is Play's to word and to size.
        assertEquals(
            "the flow shows Play's dialog once per NeedsConfirmation entry",
            1,
            count(
                flow,
                block(
                    "            LaunchedEffect(fetch) {",
                    "                if (fetch is NpuPackFetch.FetchState.NeedsConfirmation) {",
                    "                    NpuPackController.confirm(context as ComponentActivity)",
                    "                }",
                    "            }",
                ),
            ),
        )
        assertEquals(
            "the flow observes the controller's state for exactly that purpose",
            1,
            count(flow, "NpuPackController.state.collectAsState()"),
        )
        // F6 fix round 1 (I-1): the no-wedge escape. A Failed speech engine — a sideloaded
        // install's Play refusal included — offers the way back to the chooser, where the CPU
        // tiers are always pickable, so the mandatory step stays completable on every path.
        assertEquals(
            "the failed card renders the re-choice through the pure rule",
            1,
            count(flow, "if (OnboardingLogic.showChooseDifferentModel(speech)) {"),
        )
        // 4.3 RE-SPELL: the one-liner became a block when the escape gained the latch that keeps
        // it an escape on a capable device (see
        // [theOnboardingChooserSuspendsTheOneTierRuleOnceDeliveryHasFailed], which owns the latch
        // half). This assertion keeps its own claim, unchanged, over the two statements it names.
        assertEquals(
            "its tap clears the pick and resets the phase — the one legal way back to the " +
                "tier cards once downloads have begun",
            1,
            count(
                flow,
                block(
                    "                            pickedTierId = null",
                    "                            setupVm.resetSpeechForReChoice()",
                ),
            ),
        )
        assertEquals(
            "and the VM reset is guarded to Failed — a Working fetch keeps its double-tap " +
                "guard, a Ready model cannot be un-readied by a stray tap",
            1,
            count(
                setupVm,
                block(
                    "        if (_speechState.value is EngineState.Failed) {",
                    "            _speechState.value = EngineState.Pending",
                    "        }",
                ),
            ),
        )
    }

    /**
     * 4.2 F7 — the picker's fetch mirror is the PROCESS-SCOPED controller's, never the
     * composition's (the I3 lesson, fetch edition: what a rotation destroys may not own an
     * ~860 MB fetch), and the OTHER end of the wiring is held too: the controller actually
     * SETS the active tier at start, once — the same both-ends discipline as
     * [theInstallGenerationIsBumpedAtTheOneFunnelEveryInstallPathGoesThrough], because a
     * screen gating on a fact nothing writes is decoration.
     */
    @Test
    fun thePickerMirrorsTheProcessScopedFetchAndTheControllerNamesItsTier() {
        assertEquals(
            "the fetch state is collected from the process-scoped owner — a recreation " +
                "re-subscribes and finds the fetch exactly where it was",
            1,
            count(picker, "val fetchState by NpuPackController.state.collectAsState()"),
        )
        assertEquals(
            "and WHICH tier it is for comes from the same owner, so the state can never wear " +
                "a sibling card and a pre-rotation fetch still lands on the right one",
            1,
            count(picker, "val fetchTierId by NpuPackController.activeTier.collectAsState()"),
        )
        assertEquals(
            "the fetch state is never held in a remember — the composition owning it dies on " +
                "the next rotation",
            0,
            count(picker, "mutableStateOf<NpuPackFetch.FetchState>"),
        )
        val controller = read("src/main/java/com/whispereverywhere/npu/NpuPackController.kt")
        assertEquals(
            "the controller sets the active tier at exactly one place — start — so the " +
                "screens' \"is this fetch mine?\" answer is the fetch's own",
            1,
            count(controller, "_activeTier.value = tierId"),
        )
        // THE ORDER, and it is a different claim from the count (F7 micro-round, m-3 of the
        // re-review — a REAL pin gap, found by the reviewer re-scoring my own battery row).
        // The count above is position-blind: moving this write back BELOW the no-pack early
        // return leaves it at 1 and no executed test constructs the controller, so the m-1 fix
        // was unpinned in the only direction that matters. Below the branch, a no-pack denial
        // inherits the PREVIOUS tier's name, both refusal rules then read "another tier is
        // busy", and the controller's own just-published "This build has no Google Play pack
        // for the '<id>' tier." is buried under a false busy story.
        val namesTheTier = offsetOfLive(controller, "_activeTier.value = tierId")
        val noPackRefusal =
            offsetOfLive(controller, "\"This build has no Google Play pack for the '\$tierId' tier.\"")
        assertTrue("the no-pack refusal was found", noPackRefusal >= 0)
        assertTrue(
            "THE REQUESTED TIER IS NAMED BEFORE ANY REFUSAL CAN RETURN: the write ($namesTheTier) " +
                "must precede the no-pack refusal ($noPackRefusal), or a denial is attributed " +
                "to whichever tier fetched last",
            namesTheTier in 0 until noPackRefusal,
        )
    }

    /**
     * 4.3 — THE DECLINE'S CPU RECOVERY, wired to the EXISTING download path.
     *
     * The spec's one consequence and its resolution: a capable device is offered `npu-turbo`
     * alone, so it can reach a decline holding no ggml at all, and `fallBackToCpuTier` then finds
     * nothing. `NpuTierStatus` decides — both the arm of the sentence and whether the button
     * exists — and `NpuTierStatusTest` executes that decision exhaustively. What no JVM test can
     * see is whether the SCREEN asks it, and what the tap then does, so both are pinned here.
     *
     * **The mutations this closes, all compile-clean and all green everywhere else:**
     *  - *The note's second argument hardcoded `true`.* The card then tells a user with no CPU
     *    model that "speech is running on the multilingual CPU model" — false in the clause the
     *    whole note exists for, on exactly the device shape 4.3 creates.
     *  - *The `cpuFallbackInstalled` fact re-derived.* Anything other than
     *    `WhisperCatalog.hasCpuFallback(installedIds)` is a second derivation of the question the
     *    backend answers with `cpuTierModelPath()`, free to disagree with it.
     *  - *The recovery tap given its own downloader.* A bespoke `manager.download(...)` here would
     *    be a second download path in a screen that has exactly one; the pin holds the tap to
     *    `viewModel.download`, the same sink every CPU card's Download button uses.
     *  - *The recovery narrated by the declining card's own `state`.* The two downloads are only
     *    ever told apart by tier id; sharing one state renders turbo's progress under the note
     *    that asked for `multi`.
     */
    @Test
    fun theDeclineRecoveryAsksTheOneRuleAndRidesTheExistingDownloadPath() {
        assertEquals(
            "the fallback question is the catalog's pure mirror of cpuTierModelPath(), asked " +
                "once, over the installed set the screen already produces",
            1,
            count(picker, "val cpuFallbackInstalled = WhisperCatalog.hasCpuFallback(installedIds)"),
        )
        assertEquals(
            "the note is composed with that answer — never a hardcoded `true`, which is the one " +
                "mutation that makes the card lie in its load-bearing clause",
            1,
            count(
                picker,
                block(
                    "                    unavailableNote = NpuTierStatus.cardNote(",
                    "                        npuTierReasons[model.id], cpuFallbackInstalled,",
                    "                        stillSelected = model.id == selectedTierId,",
                    "                    ),",
                ),
            ),
        )
        listOf(
            "cardNote(npuTierReasons[model.id])",
            "cpuFallbackInstalled = true",
            // 4.3 micro-round: the third input's own assumed-true spelling, which would reinstate
            // the false restart promise on every card the selection has moved away from.
            "stillSelected = true",
        ).forEach {
            assertEquals(
                "the picker must not spell <<$it>> — the note's inputs are never assumed",
                0,
                liveLineCount(picker, it),
            )
        }
        // 4.3 micro-round — WHERE THE SELECTION POINTS, read off Main and keyed on BOTH the
        // install generation and the download state. The recovery's own write lands BETWEEN them
        // (manager.download bumps the generation, then the ViewModel writes selectedModelId, then
        // the state becomes Done), so a producer keyed on the generation alone re-reads one write
        // too early and keeps displaying the promise this round exists to remove.
        assertEquals(
            "the selection is a live off-Main read, keyed on the generation AND the state",
            1,
            count(
                picker,
                block(
                    "    val selectedTierId by produceState<String?>(",
                    "        initialValue = null,",
                    "        key1 = installGeneration,",
                    "        key2 = state,",
                    "    ) {",
                    "        value = withContext(Dispatchers.IO) { app.preferencesManager.selectedModelId }",
                    "    }",
                ),
            ),
        )
        assertEquals(
            "whether the button exists is the SAME rule the note's arms split on, so the " +
                "sentence and the control can never disagree",
            1,
            count(
                picker,
                "NpuTierStatus.needsCpuRecovery(npuTierReasons[model.id], cpuFallbackInstalled)",
            ),
        )
        assertEquals(
            "and the tap is the EXISTING download path — `viewModel.download`, the sink every " +
                "CPU card's Download button already calls — on the tier NpuTierStatus names",
            1,
            count(
                picker,
                block(
                    "                            activeModelId = recoveryModel.id",
                    "                            viewModel.download(recoveryModel)",
                ),
            ),
        )
        assertEquals(
            "the recovery tier comes from the one constant, never a literal beside it",
            1,
            count(picker, "WhisperCatalog.byId(NpuTierStatus.RECOVERY_TIER_ID)"),
        )
        assertEquals(
            "the recovery's progress is keyed on the RECOVERY tier, so it can never narrate the " +
                "declining card's own download",
            1,
            count(picker, "recoveryState = if (activeModelId == NpuTierStatus.RECOVERY_TIER_ID) {"),
        )
        assertEquals(
            "the button's label is the pinned constant — the spec's own words for the action",
            1,
            count(picker, "Text(NpuTierStatus.RECOVERY_ACTION)"),
        )
        assertEquals(
            "there is exactly ONE download sink on this screen: the recovery invents no second " +
                "one (the manager is reached only through the ViewModel here)",
            0,
            liveLineCount(picker, "manager.download("),
        )
    }

    /**
     * 4.3 fix round (I-1) — **THE SWITCH IS STATED, AND THE USER IS STILL THERE TO READ IT.**
     *
     * The recovery rides `viewModel.download`, which persists `prefs.selectedModelId` — the right
     * sink (leaving the selection on a declined npu-class tier routes a QAIRT blob to
     * `WhisperNativeBackend` and yields no working backend), but a PERMANENT write provoked by a
     * PROCESS-SCOPED decline. Shipped silently it meant: the note's own "restart the app to try
     * the AI chip again" went false at the instant the user acted on the button beneath it; the
     * `Done` navigation popped them to Home before anything could be seen; and after the next
     * process start the decline record was gone, so a capable phone showed `npu-turbo` at the
     * head of its one-card chooser, badged "Best match for your language", while transcribing on
     * the 190 MB CPU model — discoverable only in Settings. The behaviour was right; the silence
     * was not.
     *
     * **The mutations this closes:**
     *  - *The navigation suppression removed.* One `LaunchedEffect` condition; the user is ejected
     *    mid-explanation and the KDoc's "the note and this block retire together" describes a
     *    frame nobody is on the screen to see.
     *  - *The suppression widened to the tier id alone.* Then an ordinary Download tap on the
     *    `multi` card — the whole non-capable fleet's normal path — stops finishing onboarding.
     *  - *The confirmation moved inside the recovery block.* It would vanish in the same frame it
     *    appeared, because the landing install flips `hasCpuFallback` and retires that block.
     */
    @Test
    fun theRecoveryStatesItsSwitchAndKeepsTheScreenThatExplainsIt() {
        assertEquals(
            "the recovery tap is REMEMBERED — Done(modelId) alone cannot tell the recovery apart " +
                "from an ordinary Download tap on the multi card",
            1,
            count(picker, "var recoveryTapped by remember { mutableStateOf(false) }"),
        )
        assertEquals(
            "and the tap sets it, on the same one start site that runs the download",
            1,
            count(
                picker,
                block(
                    "                            recoveryTapped = true",
                    "                            activeModelId = recoveryModel.id",
                    "                            viewModel.download(recoveryModel)",
                ),
            ),
        )
        assertEquals(
            "the ready callback is gated by the PURE rule, so the recovery keeps the screen and " +
                "every other download navigates exactly as it always has",
            1,
            count(
                picker,
                block(
                    "        if (s is DownloadState.Done &&",
                    "            OnboardingLogic.downloadLeavesTheChooser(s.modelId, recoveryTapped)",
                    "        ) {",
                    "            onModelReady()",
                ),
            ),
        )
        assertEquals(
            "the unconditional pop is gone from live code",
            0,
            liveLineCount(picker, "if (s is DownloadState.Done) {"),
        )
        assertEquals(
            "the switch note is composed at SCREEN level, not inside the recovery block — the " +
                "landing install retires that block in the same frame the note would appear in",
            1,
            count(
                picker,
                block(
                    "    val recoverySwitched =",
                    "        recoveryTapped && (state as? DownloadState.Done)?.modelId == " +
                        "NpuTierStatus.RECOVERY_TIER_ID",
                ),
            ),
        )
        assertEquals(
            "and it renders the one pinned sentence, never a second wording",
            1,
            count(picker, "text = NpuTierStatus.RECOVERY_SWITCH_NOTE,"),
        )
        assertEquals(
            "gated on that one fact",
            1,
            count(picker, "            if (recoverySwitched) {"),
        )
    }

    /**
     * 4.2 F7 — Play's consent on the PICKER: the F6-established idiom, its second surface.
     * The flow's own block is pinned in
     * [theLanguagePickWritesTheOneStoreAndTheGatedFetchRidesThePackController] and is scoped
     * to the flow file, so this copy counts independently.
     */
    @Test
    fun thePickerShowsPlaysDialogOncePerNeedsConfirmationEntry() {
        assertEquals(
            "the picker shows Play's dialog once per ENTRY into NeedsConfirmation — the " +
                "LaunchedEffect key is the state VALUE: entering fires once, staying re-fires " +
                "nothing, leaving and re-entering fires again; no custom re-ask exists",
            1,
            count(
                picker,
                block(
                    "    LaunchedEffect(fetchState) {",
                    "        if (fetchState is NpuPackFetch.FetchState.NeedsConfirmation) {",
                    "            NpuPackController.confirm(context as ComponentActivity)",
                    "        }",
                    "    }",
                ),
            ),
        )
    }
}
