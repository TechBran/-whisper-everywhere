package com.whispereverywhere.ui.onboarding

import com.whispereverywhere.data.local.PreferencesManager
import com.whispereverywhere.model.ModelTierCopy
import com.whispereverywhere.npu.NpuPackFetch
import com.whispereverywhere.ui.onboarding.OnboardingLogic.Step
import com.whispereverywhere.ui.onboarding.OnboardingSetupViewModel.EngineState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingLogicTest {

    // ---------------------------------------------------------------- step order

    @Test fun the_flow_walks_permissions_language_engines_cloud_and_ends() {
        assertEquals(Step.LANGUAGE, OnboardingLogic.next(Step.PERMISSIONS))
        assertEquals(Step.ENGINES, OnboardingLogic.next(Step.LANGUAGE))
        assertEquals(Step.CLOUD, OnboardingLogic.next(Step.ENGINES))
        assertNull(OnboardingLogic.next(Step.CLOUD))
    }

    @Test fun back_walks_the_flow_in_reverse_and_null_means_skip() {
        assertNull("back on the first step is a skip, never a block", OnboardingLogic.previous(Step.PERMISSIONS))
        assertEquals(Step.PERMISSIONS, OnboardingLogic.previous(Step.LANGUAGE))
        assertEquals(Step.LANGUAGE, OnboardingLogic.previous(Step.ENGINES))
        assertEquals(Step.ENGINES, OnboardingLogic.previous(Step.CLOUD))
    }

    // ---------------------------------------------------------------- language step (4.2 F6)

    @Test fun theLanguageStepSitsBetweenPermissionsAndEngines() {
        // The 3.8 owner ruling: language BEFORE model download — the step walks
        // PERMISSIONS -> LANGUAGE -> ENGINES, and back retraces the same road.
        assertEquals(Step.LANGUAGE, OnboardingLogic.next(Step.PERMISSIONS))
        assertEquals(Step.ENGINES, OnboardingLogic.next(Step.LANGUAGE))
        assertEquals(Step.LANGUAGE, OnboardingLogic.previous(Step.ENGINES))
        assertEquals(Step.PERMISSIONS, OnboardingLogic.previous(Step.LANGUAGE))
    }

    @Test fun the_language_rows_lead_with_the_device_language_when_the_list_carries_it() {
        val rows = OnboardingLogic.languageRows("es-MX")
        assertEquals("the device's language renders first", "es", rows[0].first)
        assertEquals("auto is one tap away, directly under it", "auto", rows[1].first)
        assertEquals(
            "the remainder is the supported list in its own order, minus the promoted rows",
            PreferencesManager.SUPPORTED_LANGUAGES.filter { it.first != "es" && it.first != "auto" },
            rows.drop(2),
        )
        // Either separator and any case — callers pass whatever the Locale handed them.
        assertEquals("es", OnboardingLogic.languageRows("es_ES")[0].first)
        assertEquals("es", OnboardingLogic.languageRows("ES")[0].first)
        assertEquals("es", OnboardingLogic.deviceLanguageCode("es-419"))
    }

    @Test fun the_language_rows_lead_with_auto_when_the_device_language_is_absent() {
        val rows = OnboardingLogic.languageRows("sq-AL") // Albanian: not in the 54-language list
        assertEquals("device language absent -> auto leads", "auto", rows[0].first)
        assertEquals(
            PreferencesManager.SUPPORTED_LANGUAGES.filter { it.first != "auto" },
            rows.drop(1),
        )
        assertNull(OnboardingLogic.deviceLanguageCode("sq-AL"))
        // "auto" is a list entry, never a device language: no tag can promote it twice.
        assertEquals("auto", OnboardingLogic.languageRows("auto")[0].first)
        assertEquals(1, OnboardingLogic.languageRows("auto").count { it.first == "auto" })
        assertNull(OnboardingLogic.deviceLanguageCode("auto"))
    }

    @Test fun the_language_rows_are_always_a_permutation_of_the_supported_list() {
        // The same 54-plus-auto set Settings' picker offers — nothing lost, nothing invented,
        // whatever the device reports (including degenerate tags).
        for (tag in listOf("en-US", "bn-BD", "sq-AL", "zh_CN", "auto", "")) {
            val rows = OnboardingLogic.languageRows(tag)
            assertEquals(
                "no row lost, none invented ($tag)",
                PreferencesManager.SUPPORTED_LANGUAGES.toSet(),
                rows.toSet(),
            )
            assertEquals(
                "no row duplicated ($tag)",
                PreferencesManager.SUPPORTED_LANGUAGES.size,
                rows.size,
            )
        }
    }

    @Test fun language_continue_stays_locked_until_a_row_is_picked() {
        // The 3.8 mandate is a FORCED choice — the same no-preselection discipline as the model
        // pick: the device-locale row renders first and badged, and the user still taps.
        assertFalse(OnboardingLogic.languageContinueEnabled(null))
        assertTrue(OnboardingLogic.languageContinueEnabled("en"))
        assertTrue(OnboardingLogic.languageContinueEnabled("auto"))
    }

    @Test fun the_language_hint_is_the_38_specs_own_sentence() {
        // Our-own-app relative — a fact about this app's multilingual models, no cross-app claim.
        assertEquals(
            "Choosing a language makes multilingual transcription faster.",
            OnboardingLogic.LANGUAGE_HINT,
        )
        assertEquals("Your device's language", OnboardingLogic.DEVICE_LANGUAGE_BADGE)
    }

    @Test fun the_auto_subtitle_is_the_ruled_text_verbatim() {
        // THE 3.8 OWNER RULING, CARRIED VERBATIM — the plan certification already restored this
        // text once after a softer substitute dropped the disclosed cost (cert round 1, revision
        // 5). It is the honest disclosure of the cost LANGUAGE_HINT beside it asserts; the
        // ruling stands unless the owner re-rules, and nothing here re-asks.
        assertEquals(
            "Slower on multilingual models — detects per session.",
            OnboardingLogic.AUTO_LANGUAGE_SUBTITLE,
        )
    }

    // ------------------------------------------- the pack fetch on the engine card (4.2 F6)

    @Test fun fetch_pending_transferring_and_idle_all_read_preparing() {
        val preparing = EngineState.Working(OnboardingSetupViewModel.INDETERMINATE, "Preparing")
        assertEquals(preparing, OnboardingLogic.engineStateForFetch(NpuPackFetch.FetchState.Pending))
        assertEquals(preparing, OnboardingLogic.engineStateForFetch(NpuPackFetch.FetchState.Transferring))
        // Idle mid-collect is a fetch that has not published yet (the collector only runs after
        // start()), not an error — it reads as preparing, never as a refusal.
        assertEquals(preparing, OnboardingLogic.engineStateForFetch(NpuPackFetch.FetchState.Idle))
    }

    @Test fun fetch_downloading_names_google_play_and_carries_the_pct() {
        assertEquals(
            EngineState.Working(25, "Downloading from Google Play"),
            OnboardingLogic.engineStateForFetch(
                NpuPackFetch.FetchState.Downloading(soFar = 225_443_840L, total = 901_775_360L)
            ),
        )
        // Total-safe like every pct in this codebase: an unknown total is 0%, never a crash.
        assertEquals(
            EngineState.Working(0, "Downloading from Google Play"),
            OnboardingLogic.engineStateForFetch(NpuPackFetch.FetchState.Downloading(0L, 0L)),
        )
    }

    @Test fun fetch_verifying_reads_verifying_with_the_pct() {
        // The same word the download path's verify phase uses — one vocabulary on the card.
        assertEquals(
            EngineState.Working(50, OnboardingSetupViewModel.VERIFYING),
            OnboardingLogic.engineStateForFetch(
                NpuPackFetch.FetchState.Verifying(soFar = 535_842_816L, total = 1_071_685_632L)
            ),
        )
    }

    @Test fun fetch_needs_confirmation_names_plays_dialog_and_installed_means_ready() {
        assertEquals(
            EngineState.Working(
                OnboardingSetupViewModel.INDETERMINATE,
                "Waiting for your OK in the Google Play dialog",
            ),
            OnboardingLogic.engineStateForFetch(NpuPackFetch.FetchState.NeedsConfirmation),
        )
        // Installed is only ever published after the pair is census-verified, renamed into
        // place and announced (the controller's contract) — so it IS Ready, nothing more to do.
        assertEquals(
            EngineState.Ready,
            OnboardingLogic.engineStateForFetch(NpuPackFetch.FetchState.Installed),
        )
    }

    @Test fun fetch_failures_flow_verbatim_and_cancelled_names_the_retry() {
        // Failed is the F5 refusal CARRIER: a reason flows verbatim — EXCEPT the ones naming
        // the import adjacency this surface does not have (F6 fix round 1, I-1; the
        // per-surface rewrite has its own tests below).
        val reason = NpuPackFetch.failureReason(NpuPackFetch.ERROR_NETWORK_ERROR)
        assertEquals(
            EngineState.Failed(reason),
            OnboardingLogic.engineStateForFetch(NpuPackFetch.FetchState.Failed(reason)),
        )
        assertEquals(
            EngineState.Failed("Download cancelled — tap Retry to start again."),
            OnboardingLogic.engineStateForFetch(NpuPackFetch.FetchState.Cancelled),
        )
    }

    @Test fun the_onboarding_surface_rewrites_refusals_that_name_an_affordance_it_lacks() {
        // F6 fix round 1, I-1 + the F6 re-review's F7 rider: onboarding has no import control,
        // so every adjacency-marked reason renders this surface's own copy — SPLIT BY MARKER
        // FAMILY since F7, so each reason's leading claim is true: "This install can't fetch"
        // only where the install IS the cause (the four sideload codes), the neutral
        // "couldn't deliver" where the cause is Play-side, app-version or device-group. The
        // CHOOSER keeps the ruled adjacency copy untouched (it HAS the affordance).
        assertEquals(
            "This install can't fetch from Google Play — finish setup with an on-device " +
                "model and import from Settings later.",
            OnboardingLogic.ONBOARDING_FETCH_REFUSAL,
        )
        assertEquals(
            "Google Play couldn't deliver this model — finish setup with an on-device model " +
                "and import from Settings later.",
            OnboardingLogic.ONBOARDING_FETCH_UNDELIVERED,
        )
        // The install-cause family: the four sideload codes, each carrying BOTH markers.
        val sideloadReasons = listOf(
            NpuPackFetch.ERROR_API_NOT_AVAILABLE,
            NpuPackFetch.ERROR_PLAY_STORE_NOT_FOUND,
            NpuPackFetch.ERROR_APP_NOT_OWNED,
            NpuPackFetch.ERROR_UNRECOGNIZED_INSTALLATION,
        ).map { NpuPackFetch.failureReason(it) }
        for (reason in sideloadReasons) {
            assertTrue(
                "fixture premise — the reason names the adjacency: $reason",
                reason.contains(OnboardingLogic.IMPORT_ADJACENCY_MARKER),
            )
            assertTrue(
                "fixture premise — the reason names the install as the cause: $reason",
                reason.contains(OnboardingLogic.SIDELOAD_MARKER),
            )
            assertEquals(
                EngineState.Failed(OnboardingLogic.ONBOARDING_FETCH_REFUSAL),
                OnboardingLogic.engineStateForFetch(NpuPackFetch.FetchState.Failed(reason)),
            )
        }
        // The not-the-install's-fault family: the two "or use …" alternatives (transient and
        // app-version causes) and the empty delivery (a device-group cause). "This install
        // can't fetch" would be FALSE for each — the F6 re-review's finding — so they render
        // the neutral copy whose leading claim is true for all of them.
        val undeliveredReasons = listOf(
            NpuPackFetch.failureReason(NpuPackFetch.ERROR_APP_UNAVAILABLE),
            NpuPackFetch.failureReason(NpuPackFetch.ERROR_PACK_UNAVAILABLE),
            NpuPackFetch.emptyDeliveryRefusal(),
        )
        for (reason in undeliveredReasons) {
            assertTrue(
                "fixture premise — the reason names the adjacency: $reason",
                reason.contains(OnboardingLogic.IMPORT_ADJACENCY_MARKER),
            )
            assertFalse(
                "fixture premise — the reason does NOT blame the install: $reason",
                reason.contains(OnboardingLogic.SIDELOAD_MARKER),
            )
            assertEquals(
                EngineState.Failed(OnboardingLogic.ONBOARDING_FETCH_UNDELIVERED),
                OnboardingLogic.engineStateForFetch(NpuPackFetch.FetchState.Failed(reason)),
            )
        }
        // The chooser's ruled copy is UNTOUCHED at its source — both rewrites live in the
        // onboarding mapping alone (NpuPackFetchTest pins the ruled words exactly).
        assertTrue(
            NpuPackFetch.failureReason(NpuPackFetch.ERROR_APP_NOT_OWNED)
                .contains("Use 'Import model pair…' below instead."),
        )
    }

    @Test fun a_fetch_the_controller_refused_for_another_tier_is_refused_by_name_never_mirrored() {
        // F6 review M-3, landed in F7 by the carry's own instruction: start() == false while
        // the controller's active fetch is some OTHER tier's means the chooser got there
        // first — a collector attached now would mirror that tier's states, and on its
        // Installed persist selectedModelId for a tier this card never fetched. The attach
        // rule is pure and executed here; the ViewModel's consult of it is pinned as source.
        assertEquals(
            "Another model is downloading from Google Play right now. Wait for it to " +
                "finish, then tap Retry.",
            OnboardingLogic.FETCH_BUSY_WITH_ANOTHER_MODEL,
        )
        // A successful start is OURS by definition — attach, whatever the tier field reads.
        assertNull(OnboardingLogic.fetchAttachRefusal(started = true, activeTierId = "npu", tierId = "npu"))
        assertNull(OnboardingLogic.fetchAttachRefusal(started = true, activeTierId = null, tierId = "npu"))
        // Denied with NO active tier: the controller's own no-pack refusal is already
        // published — attach and mirror ITS words, never bury them under a busy story.
        assertNull(OnboardingLogic.fetchAttachRefusal(started = false, activeTierId = null, tierId = "npu"))
        // Denied while OUR OWN tier is active: the re-attach path (double tap; relaunch onto
        // Play's surviving download).
        assertNull(OnboardingLogic.fetchAttachRefusal(started = false, activeTierId = "npu", tierId = "npu"))
        // Denied while ANOTHER tier's fetch runs: the M-3 edge — refused by name.
        assertEquals(
            OnboardingLogic.FETCH_BUSY_WITH_ANOTHER_MODEL,
            OnboardingLogic.fetchAttachRefusal(
                started = false, activeTierId = "npu-turbo", tierId = "npu",
            ),
        )
        // And the refusal wedges nothing: it is a Failed terminal like any other — Retry plus
        // the choose-different escape, with the mandatory-model gate untouched.
        val failed = EngineState.Failed(OnboardingLogic.FETCH_BUSY_WITH_ANOTHER_MODEL)
        assertTrue(OnboardingLogic.showChooseDifferentModel(failed))
        assertFalse(
            OnboardingLogic.enginesPrimaryAction(
                downloadsBegun = true, tierPicked = true, speechReady = false,
            ).enabled
        )
    }

    @Test fun no_failed_terminal_can_wedge_the_model_step() {
        // F6 fix round 1, I-1 — the executed no-wedge walk: EVERY Failed terminal the fetch
        // can produce leaves the model step completable, because Failed always offers the way
        // back to the chooser and the chooser always offers the CPU tiers.
        val everyErrorCode =
            listOf(0, -1, -2, -3, -4, -5, -6, -7, -10, -11, -13, -14, -15, -100, 12345)
        for (code in everyErrorCode) {
            val mapped = OnboardingLogic.engineStateForFetch(
                NpuPackFetch.FetchState.Failed(NpuPackFetch.failureReason(code))
            )
            assertTrue("code $code lands on the Failed card", mapped is EngineState.Failed)
            assertTrue(
                "and the Failed card offers the way back to the chooser (code $code)",
                OnboardingLogic.showChooseDifferentModel(mapped),
            )
        }
        assertTrue(
            "a cancelled fetch offers it too",
            OnboardingLogic.showChooseDifferentModel(
                OnboardingLogic.engineStateForFetch(NpuPackFetch.FetchState.Cancelled)
            ),
        )
        assertTrue(
            "and the empty delivery",
            OnboardingLogic.showChooseDifferentModel(
                OnboardingLogic.engineStateForFetch(
                    NpuPackFetch.FetchState.Failed(NpuPackFetch.emptyDeliveryRefusal())
                )
            ),
        )
        // No other state grows the escape — Working keeps its guard, Ready needs none, and
        // the mandatory gate is untouched either way.
        assertFalse(OnboardingLogic.showChooseDifferentModel(EngineState.Ready))
        assertFalse(OnboardingLogic.showChooseDifferentModel(EngineState.Pending))
        assertFalse(OnboardingLogic.showChooseDifferentModel(EngineState.Working(50, "x")))
        // The chooser the escape returns to is completable on EVERY device: the CPU tiers are
        // in the lineup whatever the gate answered — a sideloaded capable device's union
        // included — and a fresh CPU pick re-arms Download.
        for (gateSet in listOf(emptySet<String>(), setOf("npu"), setOf("npu", "npu-turbo"))) {
            for (tag in listOf("en-US", "bn-BD")) {
                val lineup = ModelTierCopy.orderedForLanguageTagFor(tag, gateSet)
                assertTrue("pro pickable ($gateSet, $tag)", "pro" in lineup)
                assertTrue("multi pickable ($gateSet, $tag)", "multi" in lineup)
            }
        }
        val cpuPick = OnboardingLogic.enginesPrimaryAction(
            downloadsBegun = false, tierPicked = true, speechReady = false,
        )
        assertTrue("a fresh CPU pick re-arms Download", cpuPick.enabled && cpuPick.startsDownloads)
        // And the escape never weakens the mandatory-model gate: Failed still holds Continue.
        assertFalse(
            OnboardingLogic.enginesPrimaryAction(
                downloadsBegun = true, tierPicked = true, speechReady = false,
            ).enabled
        )
    }

    @Test fun the_two_card_contention_is_refused_by_name_on_both_surfaces_from_the_one_rule() {
        // F7 fix round 1, I-1 — THE most reachable concurrency path in the feature: on a capable
        // fresh Play install BOTH gated cards render "Get on Google Play" at once, so tapping
        // the second while the first fetches is one tap away. Before this fix that tap was a
        // SILENT no-op (start() false, the controller publishes nothing, the card keeps its
        // enabled button). Walked here as the two surfaces actually call it.
        // Micro-round m-1: the sentence may name only controls that are ALWAYS there while it
        // shows. The earlier "Cancel that download" was false through Verifying — isBusy() is
        // true there, but the fetching card renders no Cancel in that arm, and a 1.07 GB turbo
        // verify is minutes long. "Wait for it to finish, then tap Get again" is true in both
        // windows, and the Get button it names is on the card the sentence is rendered on.
        assertEquals(
            "Another model is already downloading from Google Play. Wait for it to finish, " +
                "then tap Get again.",
            OnboardingLogic.CHOOSER_FETCH_BUSY,
        )
        assertFalse(
            "the chooser's sentence names no control that can be absent while it shows",
            OnboardingLogic.CHOOSER_FETCH_BUSY.contains("Cancel"),
        )
        // 1. Tap Get on turbo: start() true, the controller names turbo. Nothing is refused.
        assertNull(
            OnboardingLogic.chooserFetchRefusal(
                started = true, activeTierId = "npu-turbo", tierId = "npu-turbo",
            ),
        )
        // 2. Tap Get on the npu card while turbo fetches: start() false, the controller's active
        //    tier is turbo's. The tap is refused BY NAME on the chooser...
        assertEquals(
            OnboardingLogic.CHOOSER_FETCH_BUSY,
            OnboardingLogic.chooserFetchRefusal(
                started = false, activeTierId = "npu-turbo", tierId = "npu",
            ),
        )
        // ...and on onboarding, in that surface's own words — one RULE, two sentences, each
        // naming only controls its own surface has (the F6 per-surface-copy doctrine).
        assertEquals(
            OnboardingLogic.FETCH_BUSY_WITH_ANOTHER_MODEL,
            OnboardingLogic.fetchAttachRefusal(
                started = false, activeTierId = "npu-turbo", tierId = "npu",
            ),
        )
        assertTrue(
            "the chooser's sentence names this card's own button, never onboarding's Retry",
            OnboardingLogic.CHOOSER_FETCH_BUSY.contains("tap Get again") &&
                !OnboardingLogic.CHOOSER_FETCH_BUSY.contains("Retry"),
        )
        // 3. The two surfaces can never disagree about WHEN a tap is refused: the chooser's copy
        //    is non-null exactly where the shared rule is. Walked over every shape either
        //    surface can hand them.
        val shapes = listOf(
            Triple(true, null as String?, "npu"),
            Triple(true, "npu", "npu"),
            Triple(true, "npu-turbo", "npu"),
            Triple(false, null as String?, "npu"),
            Triple(false, "npu", "npu"),
            Triple(false, "npu-turbo", "npu"),
        )
        for ((started, active, tier) in shapes) {
            val onboarding = OnboardingLogic.fetchAttachRefusal(started, active, tier)
            val chooser = OnboardingLogic.chooserFetchRefusal(started, active, tier)
            assertEquals(
                "the two surfaces refuse on exactly the same condition ($started, $active, $tier)",
                onboarding == null,
                chooser == null,
            )
        }
        // 4. A denied start whose active tier IS this card's own is the re-attach path (a double
        //    tap on the SAME card, or a relaunch onto Play's surviving download) — never a
        //    refusal, because nothing went wrong.
        assertNull(
            OnboardingLogic.chooserFetchRefusal(
                started = false, activeTierId = "npu", tierId = "npu",
            ),
        )
        // 5. m-1 (taken because this fix makes it reachable from a second surface): the
        //    controller now names the requested tier BEFORE the no-pack branch returns false, so
        //    a no-pack denial reads activeTierId == tierId here and is NOT rewritten as a busy
        //    story — the controller's own published "no Google Play pack for the '<id>' tier"
        //    keeps its words on the card.
        assertNull(
            OnboardingLogic.chooserFetchRefusal(
                started = false, activeTierId = "npu-max", tierId = "npu-max",
            ),
        )
        assertNull(
            OnboardingLogic.fetchAttachRefusal(
                started = false, activeTierId = "npu-max", tierId = "npu-max",
            ),
        )
    }

    @Test fun the_chooser_refusal_stops_standing_the_moment_the_blocking_fetch_ends() {
        // F7 micro-round, m-2 of the re-review: the refusal claims another fetch is RUNNING.
        // It was cleared only by the next tap, so after the blocking fetch finished the card
        // kept rendering a sentence that had become false. It now stands exactly while the
        // controller is in a busy STATE — the half a screen can observe.
        for (busy in listOf(
            NpuPackFetch.FetchState.Pending,
            NpuPackFetch.FetchState.Downloading(1L, 2L),
            NpuPackFetch.FetchState.Transferring,
            NpuPackFetch.FetchState.NeedsConfirmation,
            NpuPackFetch.FetchState.Verifying(1L, 2L),
        )) {
            assertTrue(
                "the refusal is still true while the other fetch runs ($busy)",
                OnboardingLogic.chooserRefusalStillStands(busy),
            )
        }
        // Every terminal — and the rest state — makes it false, so the card stops claiming it.
        for (done in listOf(
            NpuPackFetch.FetchState.Installed,
            NpuPackFetch.FetchState.Cancelled,
            NpuPackFetch.FetchState.Failed("whatever the machine said"),
            NpuPackFetch.FetchState.Idle,
        )) {
            assertFalse(
                "a finished fetch cannot keep another card's refusal on screen ($done)",
                OnboardingLogic.chooserRefusalStillStands(done),
            )
        }
        // The rule tracks isBusy()'s STATE half exactly — the same vocabulary, so the sentence
        // and the controller's own single-flight answer cannot drift apart.
        assertEquals(
            "busy states and standing-refusal states are the same set",
            listOf(true, true, true, true, true, false, false, false, false),
            listOf(
                NpuPackFetch.FetchState.Pending,
                NpuPackFetch.FetchState.Downloading(0L, 0L),
                NpuPackFetch.FetchState.Transferring,
                NpuPackFetch.FetchState.NeedsConfirmation,
                NpuPackFetch.FetchState.Verifying(0L, 0L),
                NpuPackFetch.FetchState.Installed,
                NpuPackFetch.FetchState.Cancelled,
                NpuPackFetch.FetchState.Failed("x"),
                NpuPackFetch.FetchState.Idle,
            ).map { OnboardingLogic.chooserRefusalStillStands(it) },
        )
    }

    // ---------------------------------------------------------------- engines gating

    @Test fun continue_unlocks_only_once_the_speech_model_is_ready() {
        // Owner decision 2026-08-18 (mandatory model): rewritten from the earlier never-wedge
        // pinning — dictation needs the local model, so Continue now tracks speechReady alone.
        assertTrue(OnboardingLogic.enginesContinueEnabled(speechReady = true))
        assertFalse(OnboardingLogic.enginesContinueEnabled(speechReady = false))
    }

    @Test fun a_failed_download_holds_the_step_instead_of_unlocking_continue() {
        // Owner decision 2026-08-18 (mandatory model): the deliberate reversal of the old
        // never-wedge rule — a failed download now HOLDS the step; the row's Retry is the way
        // forward, not a bypass. (speechFailed is gone from the signature entirely.)
        val action = OnboardingLogic.enginesPrimaryAction(
            downloadsBegun = true, tierPicked = true, speechReady = false,
        )
        assertEquals("Continue", action.label)
        assertFalse(action.enabled)
        assertFalse(action.startsDownloads)
    }

    @Test fun the_background_voice_hint_shows_exactly_while_speech_is_ready_and_voice_is_not() {
        assertEquals(
            "The read-aloud voice keeps downloading in the background — no need to wait.",
            OnboardingLogic.enginesContinueHint(speechReady = true, voiceReady = false),
        )
        assertNull(OnboardingLogic.enginesContinueHint(speechReady = true, voiceReady = true))
        assertNull(OnboardingLogic.enginesContinueHint(speechReady = false, voiceReady = false))
    }

    // ---------------------------------------------------------------- home permission chip

    @Test fun the_chip_counts_only_bubble_blocking_permissions() {
        assertEquals(0, OnboardingLogic.missingBubblePermissions(mic = true, overlay = true, accessibility = true))
        assertEquals(1, OnboardingLogic.missingBubblePermissions(mic = true, overlay = true, accessibility = false))
        assertEquals(3, OnboardingLogic.missingBubblePermissions(mic = false, overlay = false, accessibility = false))
    }

    @Test fun the_chip_is_absent_when_everything_is_granted() {
        // The clean dashboard stays clean — the chip exists only while something is actually
        // wrong (owner report 2026-08-01: granted permissions were visible only in Settings,
        // missing ones nowhere at all).
        assertNull(OnboardingLogic.homePermissionChipText(0))
    }

    @Test fun the_chip_text_counts_honestly() {
        assertEquals("1 permission still needed — tap to review", OnboardingLogic.homePermissionChipText(1))
        assertEquals("3 permissions still needed — tap to review", OnboardingLogic.homePermissionChipText(3))
    }

    // ---------------------------------------------------------------- engines chooser (3.5.0)

    @Test fun no_preselection_means_the_download_action_starts_disabled() {
        // Owner decision: the user must make an informed pick — the disabled Download button is
        // what forces it.
        val action = OnboardingLogic.enginesPrimaryAction(
            downloadsBegun = false, tierPicked = false, speechReady = false,
        )
        assertEquals("Download", action.label)
        assertFalse(action.enabled)
        assertTrue(action.startsDownloads)
    }

    @Test fun picking_a_tier_is_all_it_takes_to_unlock_download() {
        val action = OnboardingLogic.enginesPrimaryAction(
            downloadsBegun = false, tierPicked = true, speechReady = false,
        )
        assertTrue(action.enabled)
        assertTrue(action.startsDownloads)
    }

    @Test fun once_downloads_begin_the_action_is_continue_gated_on_speech_ready() {
        // One pick, then no buttons: after the confirm the footer is Continue, gated on the
        // speech model reaching Ready (owner decision 2026-08-18: mandatory model). The Failed
        // case is pinned separately in
        // a_failed_download_holds_the_step_instead_of_unlocking_continue.
        val working = OnboardingLogic.enginesPrimaryAction(
            downloadsBegun = true, tierPicked = true, speechReady = false,
        )
        assertEquals("Continue", working.label)
        assertFalse(working.enabled)
        assertFalse(working.startsDownloads)
        assertTrue(
            OnboardingLogic.enginesPrimaryAction(
                downloadsBegun = true, tierPicked = true, speechReady = true,
            ).enabled
        )
    }

    @Test fun the_switch_anytime_hint_is_pinned_exactly() {
        // Spec A3: plants the switching habit and lowers the stakes of the forced choice.
        assertEquals(
            "Not sure? Pick one — you can switch models anytime in Settings.",
            OnboardingLogic.TIER_SWITCH_HINT,
        )
    }

    // ---------------------------------------------------------------- permissions gate (3.5.x)

    @Test fun permissions_continue_unlocks_only_when_all_three_bubble_permissions_are_granted() {
        assertTrue(OnboardingLogic.permissionsContinueEnabled(mic = true, overlay = true, accessibility = true))
        assertFalse(OnboardingLogic.permissionsContinueEnabled(mic = false, overlay = true, accessibility = true))
        assertFalse(OnboardingLogic.permissionsContinueEnabled(mic = true, overlay = false, accessibility = true))
        assertFalse(OnboardingLogic.permissionsContinueEnabled(mic = true, overlay = true, accessibility = false))
    }

    @Test fun permissions_hint_counts_whats_missing_and_stays_silent_when_nothing_is() {
        assertNull(OnboardingLogic.permissionsContinueHint(0))
        assertEquals(
            "1 required permission still needed — notification access is optional.",
            OnboardingLogic.permissionsContinueHint(1),
        )
        assertEquals(
            "3 required permissions still needed — notification access is optional.",
            OnboardingLogic.permissionsContinueHint(3),
        )
    }
}
