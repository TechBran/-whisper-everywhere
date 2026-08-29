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

    private fun count(haystack: String, needle: String) = haystack.split(needle).size - 1

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
                    "        ModelTierCopy.orderedForLanguageTagFor(languageTag, npuAvailable)",
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
                "val steerId = ModelTierCopy.steerIdForLanguageTagFor(languageTag, npuAvailable)",
            ),
        )
        assertGateAnswerReachesBothCallsOffMain(
            flow,
            "the guided flow",
            block(
                "        val npuAvailable by produceState(initialValue = false) {",
                "            value = withContext(Dispatchers.IO) {",
                "                WhisperEverywhereApp.getInstance().isNpuTierOffered()",
                "            }",
                "        }",
            ),
        )
    }

    /**
     * The 4.0 half, shared by both surfaces: the gate answer is produced OFF Main, and the SAME
     * answer feeds the steer and the ordering.
     *
     * Two assertions, and neither is redundant. The producer needle pins WHERE the answer comes
     * from — `isNpuTierOffered()` composes the SoC gate, the QNN probe and the two-file installed
     * check, and its first call dlopens `libQnnSystem.so` and `libQnnHtp.so`, so
     * `withContext(Dispatchers.IO)` inside `produceState` is the difference between a background
     * load and one on the UI thread (`produceState`'s block runs in the composition's context,
     * which is Main). The count of TWO pins that the answer is not then dropped at one of the two
     * places that consume it: a `false` literal in either call is compile-clean and separates the
     * STEER badge from the card the lineup actually led with.
     */
    private fun assertGateAnswerReachesBothCallsOffMain(
        source: String,
        surface: String,
        producer: String,
    ) {
        assertEquals(
            "$surface's npu gate answer is produced off Main from the process-memoised app gate",
            1,
            count(source, producer),
        )
        assertEquals(
            "$surface passes the SAME gate answer to the steer AND the ordering — a `false` " +
                "literal in either one badges a card the lineup did not lead with",
            2,
            count(source, "(languageTag, npuAvailable)"),
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
                    "    val models = ModelTierCopy.orderedForLanguageTagFor(languageTag, npuAvailable)",
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
                "val steerId = ModelTierCopy.steerIdForLanguageTagFor(languageTag, npuAvailable)",
            ),
        )
        assertGateAnswerReachesBothCallsOffMain(
            picker,
            "the Settings picker",
            block(
                "    val npuAvailable by produceState(initialValue = false) {",
                "        value = withContext(Dispatchers.IO) { app.isNpuTierOffered() }",
                "    }",
            ),
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
}
