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

    @Test
    fun theGuidedFlowOffersTheOrderedListAndNeverRawCatalogOrder() {
        assertEquals(
            "the guided chooser renders orderedForLanguageTagFor, resolved through the catalog",
            1,
            count(
                flow,
                block(
                    "        ModelTierCopy.orderedForLanguageTagFor(languageTag, npuTierIds)",
                    "            .mapNotNull { WhisperCatalog.byId(it) }",
                ),
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
        assertGateAnswerReachesBothCallsOffMain(
            flow,
            "the guided flow",
            block(
                "        val installGeneration by ModelInstallSignal.generation.collectAsState()",
                "        val npuTierIds by produceState(initialValue = emptySet<String>(), key1 = installGeneration) {",
                "            value = withContext(Dispatchers.IO) {",
                "                WhisperEverywhereApp.getInstance().offeredNpuTierIds()",
                "            }",
                "        }",
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
        booleanKeyedProducers: Int = 0,
    ) {
        assertEquals(
            "$surface's npu gate answer is produced off Main from the app gate",
            1,
            count(source, producer),
        )
        assertEquals(
            "$surface passes the SAME gate answer to the steer AND the ordering — an " +
                "`emptySet()` literal in either one badges a card the lineup did not lead with",
            2,
            count(source, "(languageTag, npuTierIds)"),
        )
        // 4.0 Q7b fix round, I1. The producer needle above already contains `key1 =`, but this
        // says WHY out loud, because the tempting "simplification" is to delete the key rather
        // than the whole block. `produceState` with no key desugars to `remember { … }` +
        // `LaunchedEffect(Unit)`: the producer runs ONCE PER COMPOSITION ENTRY and never again.
        // `offeredNpuTierIds()` deliberately re-stats each gated tier's files on every call so an
        // import is visible immediately — and an unkeyed producer throws that away one layer up,
        // in the very composition Q8's import affordance lives in. The user imports the pair and
        // the lineup does not change.
        assertEquals(
            "$surface keys the gate producer on the install generation, so an import that lands " +
                "while the chooser is on screen re-reads the gate instead of being invisible until " +
                "the user navigates away and back",
            1,
            count(source, "produceState(initialValue = emptySet<String>(), key1 = installGeneration)"),
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
                    "    val models = ModelTierCopy.orderedForLanguageTagFor(languageTag, npuTierIds)",
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
        assertGateAnswerReachesBothCallsOffMain(
            picker,
            "the Settings picker",
            block(
                "    val installGeneration by ModelInstallSignal.generation.collectAsState()",
                "    val npuTierIds by produceState(initialValue = emptySet<String>(), key1 = installGeneration) {",
                "        value = withContext(Dispatchers.IO) { app.offeredNpuTierIds() }",
                "    }",
            ),
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
        assertEquals(
            "the panel's installed-wording gate is the npu tier's OWN membership",
            1,
            count(picker, "offered = NpuAssetImport.TIER_ID in npuTierIds,"),
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
}
