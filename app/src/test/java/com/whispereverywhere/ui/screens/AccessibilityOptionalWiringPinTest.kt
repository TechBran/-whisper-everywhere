package com.whispereverywhere.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.File
import org.junit.Test

/**
 * THE ACCESSIBILITY SERVICE IS OPTIONAL — the three screens' wiring, plus what the bubble service
 * itself reads, pinned structurally (4.3.3, accessibility-optional-spec §§1-3, §5; review nits
 * N1-N3).
 *
 * `OnboardingLogicTest`, `AccessibilityAvailabilityTest` and `HomeGateTest` execute the RULES.
 * What no test in this suite can see is whether the screens ask them: every surface here is
 * `@Composable`, Compose UI testing is `androidTest`-only in this project, and instrumented runs
 * are forbidden in this environment. So this pins the CALLS — the same instrument and the same
 * argument as `ChooserSteerWiringPinTest`, whose helpers it borrows.
 *
 * **The mutations this class closes**, all compile-clean and all green everywhere else:
 *  - *The footer's gate re-widened.* `permissionsContinueEnabled(mic, overlay, accessibility)`
 *    no longer exists, but a `&& accessibility` bolted onto either tap's `enabled =` compiles,
 *    and re-wedges every Galaxy XR user on the first step.
 *  - *The two taps gated differently.* The card's `Continue without it` and the footer's
 *    `Continue` must advance on the SAME rule; a card that advances past a missing mic is a hole
 *    the footer was built to close.
 *  - *The note hardcoded.* `note = OnboardingLogic.ACCESSIBILITY_WITHOUT_IT` compiles and tells
 *    a headset user to paste a transcript that "Enable" can never produce — acceptance H3 reads
 *    the blocked sentence, and only the probe-fed rule can choose it.
 *  - *The returned-from-Settings signal never armed.* Without `accessibilitySettingsOpened =
 *    true` on the Enable tap, the RESTRICTED_SETTINGS sentence is unreachable — a rule with no
 *    input, which is decoration.
 *  - *Home's gate re-widened.* `canEnable = HomeGate.canEnable(...) && hasAccessibilityEnabled`
 *    compiles; the pure test stays green because nothing calls it that way.
 *  - *Settings' subtitle reverted to "Required for text injection".* One string; §5 exists
 *    because it was there.
 *  - *The resting bubble made conditional on the service again.* `alwaysOnMode()` back to the
 *    bare preference compiles and leaves a clipboard-mode user with an empty screen (N1).
 *  - *The keyboard lobe shown on the preference alone.* Its one action returns false with no
 *    bound service, so that is a control that does nothing when tapped (N2).
 *
 * The source is read LF-NORMALISED (`core.autocrlf=true` checks this repo out with CRLF).
 * Symbol-scoped, no line numbers.
 */
class AccessibilityOptionalWiringPinTest {

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

    private val home: String by lazy {
        read("src/main/java/com/whispereverywhere/ui/screens/HomeScreen.kt")
    }

    private val settings: String by lazy {
        read("src/main/java/com/whispereverywhere/ui/screens/SettingsScreen.kt")
    }

    private val strings: String by lazy {
        read("src/main/res/values/strings.xml")
    }

    private val service: String by lazy {
        read("src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt")
    }

    private val accessibilityService: String by lazy {
        read("src/main/java/com/whispereverywhere/service/WhisperAccessibilityService.kt")
    }

    private fun count(haystack: String, needle: String) = haystack.split(needle).size - 1

    /** [count] over LIVE lines only — a truthful comment naming a retired spelling stays legal. */
    private fun liveLineCount(haystack: String, needle: String): Int =
        haystack.lineSequence().count { line ->
            val trimmed = line.trimStart()
            val commented = trimmed.startsWith("//") || trimmed.startsWith("/*") ||
                trimmed.startsWith("*")
            !commented && line.contains(needle)
        }

    /** A multi-line needle, written as its own source lines so indentation is part of the match. */
    private fun block(vararg lines: String) = lines.joinToString("\n")

    // ------------------------------------------------------------------ the flow (§1, §2)

    @Test
    fun theFooterAndTheCardAdvanceOnTheSameTwoPermissionRule() {
        assertEquals(
            "the footer's Continue is gated on mic + overlay, through the pure rule",
            1,
            count(flow, "                        enabled = OnboardingLogic.permissionsContinueEnabled(mic, overlay),"),
        )
        assertEquals(
            "the footer's sub-line counts the same two",
            1,
            count(flow, "                    val missing = OnboardingLogic.missingBubblePermissions(mic, overlay)"),
        )
        assertEquals(
            "the card's `Continue without it` is gated on the SAME rule — it cannot let a user " +
                "past a missing mic or overlay that the footer would hold them on",
            1,
            count(flow, "                        continueWithoutEnabled = OnboardingLogic.permissionsContinueEnabled(mic, overlay),"),
        )
        assertEquals(
            "the rule is asked exactly twice — once per tap — and by no third spelling",
            2,
            count(flow, "OnboardingLogic.permissionsContinueEnabled(mic, overlay)"),
        )
        // Both taps are the ONE advance — next(step) — each in its own exact call form (the
        // language footer spells the same advance, so a bare count of it would read 3).
        assertEquals(
            "the footer's Continue advances with next(step)",
            1,
            count(flow, "                        onClick = { OnboardingLogic.next(step)?.let { next -> step = next } },"),
        )
        assertEquals(
            "and the card's Continue without it is that SAME advance, not a second navigation",
            1,
            count(flow, "                        onContinueWithout = { OnboardingLogic.next(step)?.let { next -> step = next } },"),
        )
        // The three-argument spellings are gone from live code — a comment may still name them.
        assertEquals(0, liveLineCount(flow, "permissionsContinueEnabled(mic, overlay, accessibility)"))
        assertEquals(0, liveLineCount(flow, "missingBubblePermissions(mic, overlay, accessibility)"))
    }

    @Test
    fun theAccessibilityCardRendersThePinnedCopyWithEnablePrimaryAndWithoutItSecondary() {
        assertEquals(
            "the Recommended chip renders from the pinned constant",
            1,
            count(flow, "OnboardingLogic.ACCESSIBILITY_RECOMMENDED_BADGE,"),
        )
        assertEquals(
            "the why line renders from the pinned constant",
            1,
            count(flow, "OnboardingLogic.ACCESSIBILITY_WHY,"),
        )
        assertEquals(
            "the without-it action's label renders from the pinned constant",
            1,
            count(flow, "Text(OnboardingLogic.CONTINUE_WITHOUT_ACCESSIBILITY)"),
        )
        // No dark pattern in either direction: Enable keeps the outlined (primary) button the
        // other rows have; the without-it path is a text button, gated, and never styled up.
        assertEquals(
            "Enable stays the primary, outlined action",
            1,
            count(flow, "OutlinedButton(onClick = onEnable) { Text(\"Enable\") }"),
        )
        assertEquals(
            "Continue without it is a plain TextButton, enabled on the gate the flow hands down",
            1,
            count(
                flow,
                block(
                    "                TextButton(",
                    "                    onClick = onContinueWithout,",
                    "                    enabled = continueWithoutEnabled,",
                ),
            ),
        )
    }

    @Test
    fun theCardsNoteIsThePlatformAwareSentenceFedByTheOneProbe() {
        assertEquals(
            "the row renders the note the flow hands it — never a hardcoded constant",
            1,
            count(flow, "        note = accessibilityNote,"),
        )
        assertEquals(
            "and the flow chooses that note through the pure rule, from the availability",
            1,
            count(flow, "accessibilityNote = OnboardingLogic.accessibilityNote(accessibilityAvailability),"),
        )
        assertEquals(
            "the availability comes from the ONE adapter, keyed on the two inputs that move",
            1,
            count(
                flow,
                block(
                    "    val accessibilityAvailability = remember(accessibility, returnedFromAccessibilitySettings) {",
                    "        AccessibilityAvailabilityProbe.classify(",
                    "            context,",
                    "            returnedFromSettings = returnedFromAccessibilitySettings,",
                    "            serviceEnabled = accessibility,",
                    "        )",
                    "    }",
                ),
            ),
        )
        assertEquals(
            "the flow never bypasses the rule with the ENABLEABLE constant",
            0,
            liveLineCount(flow, "note = OnboardingLogic.ACCESSIBILITY_WITHOUT_IT"),
        )
    }

    @Test
    fun theReturnedFromSettingsSignalIsArmedByTheEnableTapAndReadOnResume() {
        // The bounce half of the Restricted Settings suspicion (the install-source half is the
        // probe's, pinned in AccessibilityAvailabilityTest). Both ends are pinned: a flag nothing
        // sets makes RESTRICTED_SETTINGS unreachable; a flag nothing reads makes it decoration.
        assertEquals(
            "the Enable tap arms the flag BEFORE launching Settings",
            1,
            count(
                flow,
                block(
                    "        onEnable = {",
                    "            onAccessibilitySettingsOpened()",
                    "            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))",
                    "        },",
                ),
            ),
        )
        assertEquals(
            "the flow's handler is the one write site of the flag",
            1,
            liveLineCount(flow, "accessibilitySettingsOpened = true"),
        )
        assertEquals(
            "and the ON_RESUME refresh turns the armed flag into the returned signal",
            1,
            count(
                flow,
                block(
                    "        notifListener = MediaNotificationListener.isEnabled()",
                    "        if (accessibilitySettingsOpened) returnedFromAccessibilitySettings = true",
                ),
            ),
        )
        assertEquals(
            "both flags are durable screen state, starting unarmed",
            2,
            count(flow, "var accessibilitySettingsOpened by remember { mutableStateOf(false) }") +
                count(flow, "var returnedFromAccessibilitySettings by remember { mutableStateOf(false) }"),
        )
    }

    // ------------------------------------------------------------------ home (§3)

    @Test
    fun homeStartsTheBubbleThroughHomeGateAndShowsTypingAsAStatus() {
        assertEquals(
            "the control's gate is the pure rule, fully named",
            1,
            count(
                home,
                block(
                    "                canEnable = HomeGate.canEnable(",
                    "                    hasSpeechModel = hasSpeechModel,",
                    "                    hasMicrophonePermission = hasMicrophonePermission,",
                    "                    hasOverlayPermission = hasOverlayPermission,",
                    "                ),",
                ),
            ),
        )
        assertEquals(
            "no live conjunction re-widens the gate with the service",
            0,
            liveLineCount(home, "&& hasAccessibilityEnabled"),
        )
        assertEquals(
            "the status line renders through the rule, from the live service state",
            1,
            count(home, "HomeGate.typingStatusLine(hasAccessibilityEnabled)?.let { status ->"),
        )
        assertEquals(
            "and that state is still refreshed on resume — a status nothing updates is a lie " +
                "after the first trip to Settings",
            1,
            count(home, "hasAccessibilityEnabled = WhisperAccessibilityService.isEnabled()"),
        )
        assertEquals(
            "both the chip and the status line tap through to Settings",
            2,
            count(
                home,
                block(
                    "                        .clip(RoundedCornerShape(8.dp))",
                    "                        .clickable { onNavigateToSettings() }",
                    "                        .padding(vertical = 8.dp),",
                ),
            ),
        )
        assertEquals(
            "the chip counts the two required permissions and no third",
            1,
            count(
                home,
                block(
                    "                com.whispereverywhere.ui.onboarding.OnboardingLogic.missingBubblePermissions(",
                    "                    mic = hasMicrophonePermission,",
                    "                    overlay = hasOverlayPermission,",
                    "                )",
                ),
            ),
        )
    }

    // ------------------------------------------------------------------ settings + strings (§5)

    @Test
    fun settingsCallsTheServiceRecommendedThroughTheSameRuleAndNowhereRequired() {
        assertEquals(
            "the old subtitle is gone from live code",
            0,
            liveLineCount(settings, "Required for text injection"),
        )
        assertEquals(
            "the off-state subtitle comes from the pure rule, by availability",
            1,
            count(
                settings,
                "subtitle = if (hasAccessibility) \"Enabled\" else " +
                    "OnboardingLogic.accessibilitySettingsSubtitle(accessibilityAvailability),",
            ),
        )
        assertEquals(
            "Settings reads the device through the ONE adapter, with no returned-from signal " +
                "(it has no Enable-then-resume of its own)",
            1,
            count(
                settings,
                block(
                    "                val accessibilityAvailability = AccessibilityAvailabilityProbe.classify(",
                    "                    context,",
                    "                    returnedFromSettings = false,",
                    "                    serviceEnabled = hasAccessibility,",
                    "                )",
                ),
            ),
        )
        // §5's grep, held: no live line on any of the three surfaces (or the resource file)
        // calls the accessibility service required.
        for ((name, src) in listOf("flow" to flow, "home" to home, "settings" to settings, "strings" to strings)) {
            val offenders = src.lineSequence().filter { line ->
                val trimmed = line.trimStart()
                val commented = trimmed.startsWith("//") || trimmed.startsWith("/*") ||
                    trimmed.startsWith("*") || trimmed.startsWith("<!--")
                !commented && line.contains("ccessib") && line.lowercase().contains("required")
            }.toList()
            assertEquals("$name calls the accessibility service required: $offenders", emptyList<String>(), offenders)
        }
        assertEquals(
            "the resource title reads Recommended",
            1,
            count(strings, "<string name=\"permission_accessibility_title\">Accessibility Service Recommended</string>"),
        )
    }

    // -------------------------------------------------------------- the bubble itself (N1, N2)

    @Test
    fun theRestingBubbleIsNeverInvisibleWithNoServiceToSummonIt() {
        // N1: auto pop-up has exactly two summons — the accessibility service's focus callback
        // and media. With the service off and always-on OFF the user got "Bubble is active" and
        // an empty screen: nothing to tap, no way to dictate. The mode read is therefore the
        // preference OR the absence of the service. An OR, never a gate — with the service ON
        // `!isEnabled()` is false and every caller sees the preference alone, unchanged.
        assertEquals(
            "always-on is the preference OR the absence of the service",
            1,
            count(
                service,
                block(
                    "    private fun alwaysOnMode(): Boolean =",
                    "        app.preferencesManager.isBubbleAlwaysOn() || !WhisperAccessibilityService.isEnabled()",
                ),
            ),
        )
        assertEquals(
            "and that is the ONE place the display mode is decided",
            1,
            liveLineCount(service, "private fun alwaysOnMode()"),
        )
        assertEquals(
            "the preference is read nowhere else in the service — a second read would answer " +
                "the same question without the service-off clause",
            1,
            liveLineCount(service, "isBubbleAlwaysOn()"),
        )
        assertEquals(
            "and start-at-rest still hangs off that one rule, so a clipboard-mode user has a " +
                "bubble to tap the moment the service starts",
            1,
            count(
                service,
                block(
                    "        if (alwaysOnMode()) {",
                    "            bubbleView.post { showBubbleAtRest() }",
                    "        }",
                ),
            ),
        )
    }

    @Test
    fun theKeyboardLobeIsNeverOfferedWhenNothingCanSummonAKeyboard() {
        // N2: the lobe's ONE action is toggleSummonedKeyboard(), which returns false the instant
        // the service is unbound — the premise, pinned at its source so this test cannot outlive
        // it.
        assertEquals(
            "the summon returns false with no bound service",
            1,
            count(
                accessibilityService,
                block(
                    "        fun toggleSummonedKeyboard(): Boolean {",
                    "            val svc = instance ?: return false",
                ),
            ),
        )
        assertEquals(
            "so the lobe shows on the pref AND a bound service — never the pref alone",
            1,
            count(
                service,
                block(
                    "                    keyboardLobe.visibility =",
                    "                        if (app.preferencesManager.isDictationFirstKeyboard() &&",
                    "                            WhisperAccessibilityService.isEnabled()",
                    "                        ) View.VISIBLE",
                    "                        else View.GONE",
                ),
            ),
        )
        assertEquals(
            "and that is the lobe's ONE show: the other two writes are the GONE resets",
            0,
            liveLineCount(service, "keyboardLobe.visibility = View.VISIBLE"),
        )
        assertEquals(
            "three visibility writes in all — two resets, one conditional show",
            3,
            liveLineCount(service, "keyboardLobe.visibility"),
        )
        assertEquals(
            "the tap is still that one service call",
            1,
            count(service, "val shown = WhisperAccessibilityService.toggleSummonedKeyboard()"),
        )
    }
}
