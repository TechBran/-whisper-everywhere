package com.whispereverywhere.ui.onboarding

import com.whispereverywhere.data.local.PreferencesManager
import com.whispereverywhere.model.ModelTierCopy
import com.whispereverywhere.model.WhisperCatalog
import com.whispereverywhere.npu.NpuPackFetch
import com.whispereverywhere.ui.onboarding.OnboardingLogic.Step
import com.whispereverywhere.ui.onboarding.OnboardingSetupViewModel.EngineState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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

    /**
     * THE LANGUAGE COPY IS THE OWNER'S 2026-09-03 RULING, PINNED VERBATIM — keep both options,
     * make Auto TRUE for the chip tier, keep the one-language lock rationale. It re-rules the 3.8
     * text, whose two clauses ("faster" for a pick; "slower … detects per session" for Auto)
     * described the CPU path and were false on the shipping AI chip model, where detection is per
     * utterance at ~5-9 ms of a ~2 s commit and a picked language never runs it at all. The pin
     * exists so a copy change is a decision; this one was the owner's.
     */
    @Test fun the_language_hint_is_the_owners_2026_09_03_lock_sentence() {
        // Scoped to the multilingual models (review MC-1): there an explicit language passes
        // through unchanged and nothing detects. On an ENGLISH-scope tier — pro (small.en),
        // pickable on every non-NPU device — FloatingBubbleService replaces the pick with "en",
        // so an unscoped "locks every phrase" was false. Our-own-app relative, no cross-app claim.
        assertEquals(
            "On the multilingual models, choosing one language locks every phrase to it — the " +
                "most accurate choice if you only ever speak one, and the right one for dictation.",
            OnboardingLogic.LANGUAGE_HINT,
        )
        assertEquals("Your device's language", OnboardingLogic.DEVICE_LANGUAGE_BADGE)
    }

    @Test fun the_auto_subtitle_is_the_owners_2026_09_03_ruling_verbatim() {
        // Scoped to the AI chip model on purpose: the step runs before the model is picked, so
        // nothing here can tell the tier, and the CPU tiers' per-session pin (LanguagePin) is
        // neither claimed nor denied. "each phrase" and "no cost you will notice" are both true
        // there — NpuWhisperBackend runs the detect pass every segment when lang == null.
        assertEquals(
            "On the AI chip model, detects the language of each phrase as you speak — " +
                "mixed-language audio (a video, a bilingual conversation) comes out in each " +
                "language, at no cost you will notice.",
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
        // (P2-7, the P2b review's small 3) The empty delivery has two sentences since the
        // MediaTek pair's untargeted modules — a targeted pair's device-group cause and an
        // untargeted pair's "not delivered" — and both belong to this family.
        val undeliveredReasons = listOf(
            NpuPackFetch.failureReason(NpuPackFetch.ERROR_APP_UNAVAILABLE),
            NpuPackFetch.failureReason(NpuPackFetch.ERROR_PACK_UNAVAILABLE),
            NpuPackFetch.emptyDeliveryRefusal(
                NpuPackFetch.packsFor("npu-turbo", com.whispereverywhere.npu.NpuFleetCensus.familyById("8gen3")),
            ),
            NpuPackFetch.emptyDeliveryRefusal(
                NpuPackFetch.packsFor("npu-turbo", com.whispereverywhere.npu.NpuFleetCensus.familyById("mt6989")),
            ),
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
        for (family in listOf("8gen3", "mt6989")) {
            assertTrue(
                "and the empty delivery, targeted or not ($family)",
                OnboardingLogic.showChooseDifferentModel(
                    OnboardingLogic.engineStateForFetch(
                        NpuPackFetch.FetchState.Failed(
                            NpuPackFetch.emptyDeliveryRefusal(
                                NpuPackFetch.packsFor(
                                    "npu-turbo",
                                    com.whispereverywhere.npu.NpuFleetCensus.familyById(family),
                                ),
                            ),
                        )
                    )
                ),
            )
        }
        // No other state grows the escape — Working keeps its guard, Ready needs none, and
        // the mandatory gate is untouched either way.
        assertFalse(OnboardingLogic.showChooseDifferentModel(EngineState.Ready))
        assertFalse(OnboardingLogic.showChooseDifferentModel(EngineState.Pending))
        assertFalse(OnboardingLogic.showChooseDifferentModel(EngineState.Working(50, "x")))
        // The chooser the escape returns to is completable on EVERY device: the CPU tiers are
        // in the lineup whatever the gate answered — a sideloaded capable device's union
        // included — and a fresh CPU pick re-arms Download.
        //
        // 4.3 RE-SPELL, and it is the branch's ONE behavioural interaction: 4.3 narrows a capable
        // device's chooser to `npu-turbo` alone, which would have re-opened exactly the wedge I-1
        // closed — a sideloaded capable device is OFFERED turbo (the census knows the family has
        // a pack, not that Play will serve this install), Play refuses, and the escape returns to
        // one undeliverable card on a MANDATORY step. The narrowing is therefore suspended once
        // the delivery has failed, and the walk below composes the rule the screen composes,
        // rather than asserting a lineup the screen no longer asks for.
        for (gateSet in listOf(emptySet<String>(), setOf("npu"), setOf("npu", "npu-turbo"))) {
            for (tag in listOf("en-US", "bn-BD")) {
                val alsoOffered = OnboardingLogic.chooserAlsoOfferedIds(
                    installedIds = emptySet(), oneTierDeliveryFailed = true,
                )
                val lineup = ModelTierCopy.orderedForLanguageTagFor(tag, gateSet, alsoOffered)
                // 4.6: the escape restores the whole LADDER, not "the CPU tiers" as a pair.
                // `pro` is retired now, so naming it here would assert a card that no longer
                // exists; the claim that matters is unchanged and stronger stated structurally —
                // every rung `pickable` holds is back in the lineup, so the mandatory step is
                // completable whatever the gate answered.
                assertTrue("small-q8 pickable ($gateSet, $tag)", "small-q8" in lineup)
                assertFalse("the retired 190 MB rung came back through the escape", "multi" in lineup)
                WhisperCatalog.pickable.forEach {
                    assertTrue("${it.id} pickable after the escape ($gateSet, $tag)", it.id in lineup)
                }
                assertFalse("a retired tier came back through the escape", "pro" in lineup)
                // The escape does not cost the user the tier they came for: turbo is still there
                // where it was offered, still at the head, so Retry-by-re-picking stays possible.
                if ("npu-turbo" in gateSet) {
                    assertEquals("turbo still heads the suspended lineup", "npu-turbo", lineup.first())
                }
            }
        }
        // And the suspension is EARNED, never the default: before a delivery failure a capable
        // device still sees exactly one card, which is the whole ruling.
        for (tag in listOf("en-US", "bn-BD")) {
            assertEquals(
                "the one-tier rule must hold until a delivery actually fails ($tag)",
                listOf("npu-turbo"),
                ModelTierCopy.orderedForLanguageTagFor(
                    tag,
                    setOf("npu", "npu-turbo"),
                    OnboardingLogic.chooserAlsoOfferedIds(emptySet(), oneTierDeliveryFailed = false),
                ),
            )
        }
        // A non-capable device is untouched by the suspension in either direction: its lineup was
        // never narrowed, so adding the CPU ids to `alsoOfferedIds` changes nothing at all.
        for (tag in listOf("en-US", "bn-BD")) {
            assertEquals(
                "the suspension leaked into the gate-fail lineup ($tag)",
                ModelTierCopy.orderedForLanguageTagFor(tag, emptySet()),
                ModelTierCopy.orderedForLanguageTagFor(
                    tag, emptySet(), OnboardingLogic.chooserAlsoOfferedIds(emptySet(), true),
                ),
            )
        }
        // The rule itself: installed ids always pass; the CPU ids join only after a failure.
        assertEquals(
            setOf("multi"),
            OnboardingLogic.chooserAlsoOfferedIds(setOf("multi"), oneTierDeliveryFailed = false),
        )
        assertTrue(
            OnboardingLogic.chooserAlsoOfferedIds(setOf("multi"), true)
                .containsAll(setOf("multi") + WhisperCatalog.pickable.map { it.id }),
        )
    }

    // ------------------------------------------------------- 4.3 fix round: the narrowed latch

    @Test fun only_a_real_delivery_failure_of_the_gated_tier_restores_the_menu() {
        // I-2. The escape is offered for EVERY Failed terminal (unchanged — that IS the no-wedge
        // contract), but the first shipping version LATCHED on every one of them. So a user on a
        // perfectly deliverable Play device who cancelled the pack download once, or hit one
        // transient refusal, and then tapped "Choose a different model" had the 190 MB and 358 MB
        // tiers restored for the rest of onboarding — permanently, since the latch is never
        // cleared. A transient event undoing the ruling the branch exists to apply.

        // The two NON-DELIVERIES. Neither is Play answering about this install: one is a fetch
        // that never began, the other is the user's own cancel. Neither may cost the owner the
        // ruling, and this is the finding's hard requirement.
        assertFalse(
            "a busy refusal is a fetch that NEVER STARTED — it says nothing about delivery",
            OnboardingLogic.oneTierDeliveryFailed(
                "npu-turbo", OnboardingLogic.FETCH_BUSY_WITH_ANOTHER_MODEL,
            ),
        )
        assertFalse(
            "the user's OWN cancel must never restore the menu they were not complaining about",
            OnboardingLogic.oneTierDeliveryFailed(
                "npu-turbo", OnboardingLogic.FETCH_CANCELLED_MESSAGE,
            ),
        )
        // Composed through the real mapping, so the constants above cannot drift from the states
        // that actually produce them.
        listOf(
            NpuPackFetch.FetchState.Cancelled,
        ).forEach { fetch ->
            val mapped = OnboardingLogic.engineStateForFetch(fetch)
            val reason = (mapped as EngineState.Failed).message
            assertTrue("the escape is still offered for $fetch", OnboardingLogic.showChooseDifferentModel(mapped))
            assertFalse("but $fetch must not latch", OnboardingLogic.oneTierDeliveryFailed("npu-turbo", reason))
        }

        // The DELIVERY OUTCOMES — the sideload family, the undeliverable family, and (one step
        // wider, deliberately) a pack that arrives and fails verification: a delivery that did
        // not produce a model, and one that can be persistent. Narrowing past it would re-open
        // the very wedge the latch was added inside of.
        listOf(
            OnboardingLogic.ONBOARDING_FETCH_REFUSAL,
            OnboardingLogic.ONBOARDING_FETCH_UNDELIVERED,
            "verify: the delivered encoder does not match the census digest",
        ).forEach { reason ->
            assertTrue(
                "a real delivery outcome must suspend the rule: <<$reason>>",
                OnboardingLogic.oneTierDeliveryFailed("npu-turbo", reason),
            )
        }
        // Every Play error code, through the real refusal builder and the real mapping.
        listOf(0, -1, -2, -3, -4, -5, -6, -7, -10, -11, -13, -14, -15, -100, 12345).forEach { code ->
            val mapped = OnboardingLogic.engineStateForFetch(
                NpuPackFetch.FetchState.Failed(NpuPackFetch.failureReason(code))
            )
            assertTrue(
                "Play error $code is a delivery answer and must latch",
                OnboardingLogic.oneTierDeliveryFailed("npu-turbo", (mapped as EngineState.Failed).message),
            )
        }

        // THE TIER CLAUSE. A CPU tier's own download exception says nothing about Play's ability
        // to deliver a pack — and on a capable device a CPU tier can only have been picked from
        // an ALREADY suspended lineup, so latching on it would be circular.
        listOf("pro", "multi", "eco", null, "nope").forEach { tier ->
            assertFalse(
                "a non-gated (or unresolvable) tier's failure must never latch: $tier",
                OnboardingLogic.oneTierDeliveryFailed(tier, "Download failed"),
            )
        }
        assertTrue("the other gated tier latches too", OnboardingLogic.oneTierDeliveryFailed("npu", "boom"))

        // And the composition the screen performs: a cancel leaves a capable device on ONE card,
        // an undeliverable answer restores the menu with turbo still at its head.
        val cancelLatch = OnboardingLogic.oneTierDeliveryFailed(
            "npu-turbo", OnboardingLogic.FETCH_CANCELLED_MESSAGE,
        )
        assertEquals(
            "a cancel must leave the ruling intact",
            listOf("npu-turbo"),
            ModelTierCopy.orderedForLanguageTagFor(
                "en-US",
                setOf("npu", "npu-turbo"),
                OnboardingLogic.chooserAlsoOfferedIds(emptySet(), cancelLatch),
            ),
        )
        val realLatch = OnboardingLogic.oneTierDeliveryFailed(
            "npu-turbo", OnboardingLogic.ONBOARDING_FETCH_REFUSAL,
        )
        val restored = ModelTierCopy.orderedForLanguageTagFor(
            "en-US",
            setOf("npu", "npu-turbo"),
            OnboardingLogic.chooserAlsoOfferedIds(emptySet(), realLatch),
        )
        assertTrue(
            "an undeliverable answer restores the CPU ladder",
            restored.containsAll(WhisperCatalog.pickable.map { it.id }),
        )
        assertEquals("with turbo still at the head", "npu-turbo", restored.first())
    }

    // -------------------------------------------- 4.3 fix round: the pick the narrowing outran

    @Test fun a_pick_does_not_survive_the_card_it_was_made_on_leaving_the_lineup() {
        // I-3. Both producers on the engines step are async, so a capable device renders
        // [pro, multi] for that window and then narrows to [npu-turbo]. A tap inside the window
        // used to survive: the card vanished, pickedTierId kept its value, tierPicked stayed
        // true, and Download wrote prefs.selectedModelId = pro|multi ON A CAPABLE DEVICE with no
        // card on screen for it — the exact outcome the ruling forbids, reached by a user who
        // did nothing wrong.
        // 4.6: the CPU subject is `medium-q5` where it used to be `pro` — `pro` is retired, so a
        // pick could no longer be made on its card at all. The race is unchanged and the ladder
        // makes its WINDOW WIDER: a capable device renders the whole seven-rung lineup for that
        // async window before narrowing to [npu-turbo], so there are six more cards a tap inside
        // the window can land on than there were.
        assertNull(
            "THE RACE: a CPU pick made before the gate answered must not survive the narrowing",
            OnboardingLogic.revalidatePick("medium-q5", listOf("npu-turbo")),
        )
        assertNull(OnboardingLogic.revalidatePick("multi", listOf("npu-turbo")))
        // ...over every rung, because every one of them is on screen inside that window.
        WhisperCatalog.pickable.forEach {
            assertNull(
                "a '${it.id}' pick must not survive the narrowing either",
                OnboardingLogic.revalidatePick(it.id, listOf("npu-turbo")),
            )
        }
        // A pick whose card is still there is untouched — the guard must not eat live picks.
        assertEquals("npu-turbo", OnboardingLogic.revalidatePick("npu-turbo", listOf("npu-turbo")))
        assertEquals("medium-q5", OnboardingLogic.revalidatePick("medium-q5", listOf("medium-q5", "multi")))
        assertEquals("multi", OnboardingLogic.revalidatePick("multi", listOf("multi", "medium-q5")))
        // Nothing picked stays nothing; the initial empty lineup (before either producer answers)
        // drops nothing that was never there.
        assertNull(OnboardingLogic.revalidatePick(null, listOf("npu-turbo")))
        assertNull(OnboardingLogic.revalidatePick(null, emptyList()))
        // It DROPS rather than re-points: choosing for the user is the one thing this chooser has
        // never done, and Download simply returns to disabled — a fresh capable install's state.
        assertNull(
            "the guard must not silently re-point the pick at the surviving card",
            OnboardingLogic.revalidatePick("medium-q5", listOf("npu-turbo")),
        )
        // The suspended lineup keeps a CPU pick alive, because its card is back on screen — and
        // that now holds for every rung of the ladder, instruments included.
        val restored = ModelTierCopy.orderedForLanguageTagFor(
            "en-US", setOf("npu", "npu-turbo"),
            OnboardingLogic.chooserAlsoOfferedIds(emptySet(), true),
        )
        WhisperCatalog.pickable.forEach {
            assertEquals(
                "a '${it.id}' pick survives the suspension, because its card is back",
                it.id,
                OnboardingLogic.revalidatePick(it.id, restored),
            )
        }
        assertNull(
            "but a RETIRED tier's pick does not come back through the suspension",
            OnboardingLogic.revalidatePick("pro", restored),
        )
    }

    // ------------------------------- 4.3 fix round: the recovery keeps the screen that explains

    @Test fun only_the_recovery_download_keeps_the_user_on_the_chooser() {
        // I-1(c). Done is the screen's global model-ready signal and the activity pops the picker
        // to Home on it — right for a model the user CHOSE, wrong for a repair of a broken one,
        // which would eject them from the screen mid-explanation, before the switch note could be
        // read and before the note and button they were looking at retire.
        // 4.7: the recovery tier is `small-q8` (was `multi`, retired by the Q8 ruling).
        assertFalse(
            "the recovery must NOT navigate away from its own explanation",
            OnboardingLogic.downloadLeavesTheChooser("small-q8", recoveryTapped = true),
        )
        // The entire non-capable fleet's normal path: an ordinary Download tap on the small-q8
        // card completes with the SAME Done(modelId) and must keep navigating exactly as it
        // always has. This is why the rule is keyed on the tap and not on the tier id alone.
        assertTrue(
            "an ordinary small-q8 download must still finish onboarding",
            OnboardingLogic.downloadLeavesTheChooser("small-q8", recoveryTapped = false),
        )
        listOf("pro", "multi", "npu-turbo", "npu", null, "nope").forEach { id ->
            assertTrue(
                "every non-recovery tier navigates, tapped or not: $id",
                OnboardingLogic.downloadLeavesTheChooser(id, recoveryTapped = false),
            )
            assertTrue(
                "and a recovery tap does not suppress OTHER tiers' navigation: $id",
                OnboardingLogic.downloadLeavesTheChooser(id, recoveryTapped = true),
            )
        }
        // The tier it suppresses for is the one NpuTierStatus names, never a second literal.
        assertFalse(
            OnboardingLogic.downloadLeavesTheChooser(
                com.whispereverywhere.npu.NpuTierStatus.RECOVERY_TIER_ID, recoveryTapped = true,
            ),
        )
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

    // ---------------------------------------- the first-run RAM gate (4.8.0, owner 2026-09-17)
    //
    // "For NPU, we're good … you shouldn't see any other models because that's just v3 turbo.
    // For other devices … I'd say we do four point five gigs minimum. If you have under that,
    // then you get pushed to the smallest model; anything above, then you're gonna choose from
    // the medium or v3 turbo." And: "we wanna discourage people from the [small] model."
    //
    // 4.9 (the same day, later, after his own dictation): "If their phone can't handle the RAM,
    // then we shouldn't offer that model to them, pretty much like we're already doing. If you
    // can fit the medium model, you should also be able to see the small model based on your
    // RAM. And if you can see v3 turbo, of course, you should see all three tiers, and you
    // choose what you want." — the lineup is CUMULATIVE by each rung's own floor; the steer
    // (medium over the gate) is where the discouragement lives.

    private val gate = OnboardingLogic.FIRST_RUN_RAM_GATE_BYTES
    private val cpuLineup = ModelTierCopy.orderedForLanguageTagFor("en-US", emptySet())
    private val cpuSteer = ModelTierCopy.steerIdForLanguageTagFor("en-US", emptySet())

    @Test fun the_gate_is_the_owners_4_5_gb_and_is_the_same_number_the_medium_card_badges_on() {
        assertEquals(4_500_000_000L, OnboardingLogic.FIRST_RUN_RAM_GATE_BYTES)
        // One fact, two readers: the card's "Fits your device" badge and the card's
        // presence in the first-run lineup must answer the same question.
        assertEquals(WhisperCatalog.byId("medium-q8")!!.minRamBytes, OnboardingLogic.FIRST_RUN_RAM_GATE_BYTES)
        // 4.9: turbo carries a floor of its own, one constant per rung so it can be raised
        // alone; today the two are the same number, and the lineup reads each rung's own.
        assertEquals(WhisperCatalog.MEDIUM_Q8_MIN_RAM_BYTES, WhisperCatalog.byId("medium-q8")!!.minRamBytes)
        assertEquals(WhisperCatalog.ULTRA_Q8_MIN_RAM_BYTES, WhisperCatalog.byId("ultra-q8")!!.minRamBytes)
        assertEquals(4_500_000_000L, WhisperCatalog.ULTRA_Q8_MIN_RAM_BYTES)
        assertEquals(0L, WhisperCatalog.byId("small-q8")!!.minRamBytes)
        // The fixture premise: the ungated CPU lineup is the three Q8 rungs, smallest first.
        assertEquals(listOf("small-q8", "medium-q8", "ultra-q8"), cpuLineup)
        assertEquals("small-q8", cpuSteer)
    }

    @Test fun under_the_gate_the_first_run_chooser_is_the_smallest_rung_alone() {
        // "you get pushed to the smallest model" — a nominal 4 GB phone reports ~3.7e9.
        assertEquals(listOf("small-q8"), OnboardingLogic.firstRunLineup(cpuLineup, 3_700_000_000L, emptySet()))
        assertEquals(listOf("small-q8"), OnboardingLogic.firstRunLineup(cpuLineup, 0L, emptySet()))
        assertEquals(listOf("small-q8"), OnboardingLogic.firstRunLineup(cpuLineup, gate - 1, emptySet()))
    }

    @Test fun under_the_gate_an_installed_larger_rung_keeps_its_card() {
        // The non-disturbance rule rides through: a model already on disk is never hidden from
        // the user who downloaded it, whatever the gate says. Lineup ORDER is preserved.
        assertEquals(
            listOf("small-q8", "medium-q8"),
            OnboardingLogic.firstRunLineup(cpuLineup, 3_700_000_000L, setOf("medium-q8")),
        )
        assertEquals(
            listOf("small-q8", "medium-q8", "ultra-q8"),
            OnboardingLogic.firstRunLineup(cpuLineup, 3_700_000_000L, setOf("medium-q8", "ultra-q8")),
        )
    }

    @Test fun over_the_gate_the_first_run_chooser_is_all_three_rungs_in_ladder_order() {
        // 4.9: "if you can see v3 turbo, of course, you should see all three tiers, and you
        // choose what you want" — small is NOT dropped any more (4.8.0 dropped it on "we wanna
        // discourage people from the [small] model"; the later ruling keeps it on the card and
        // moves the discouragement to the steer). A nominal 6 GB phone reports ~5.6e9; the
        // owner's tablet 12e9.
        assertEquals(listOf("small-q8", "medium-q8", "ultra-q8"), OnboardingLogic.firstRunLineup(cpuLineup, 5_600_000_000L, emptySet()))
        assertEquals(listOf("small-q8", "medium-q8", "ultra-q8"), OnboardingLogic.firstRunLineup(cpuLineup, 12_000_000_000L, emptySet()))
        // The steer is still medium, and it is now the SECOND card of the cut — which is why the
        // flow lifts (steerFirst): the chip and the lead card must agree.
        val over = OnboardingLogic.firstRunLineup(cpuLineup, 12_000_000_000L, emptySet())
        val steer = OnboardingLogic.firstRunSteer(over, cpuSteer, 12_000_000_000L)
        assertEquals("medium-q8", steer)
        assertNotEquals("the cumulative cut leaves small at the head; the lift moves the steer up", steer, over.first())
        assertEquals(listOf("medium-q8", "small-q8", "ultra-q8"), OnboardingLogic.steerFirst(over, steer))
    }

    @Test fun the_lineup_is_cumulative_by_each_rungs_own_floor() {
        // The rule reads each rung's own minRamBytes, not the one gate constant: a device that
        // meets a rung's floor sees it AND every rung under it. Driven over a synthetic split
        // of the two floors so the test binds to the mechanism, not to today's equal numbers.
        val small = WhisperCatalog.byId("small-q8")!!
        val medium = WhisperCatalog.byId("medium-q8")!!
        val ultra = WhisperCatalog.byId("ultra-q8")!!
        assertTrue("small is the floor for every device", small.minRamBytes == 0L)
        assertTrue("medium's floor is the owner's gate", medium.minRamBytes == gate)
        assertTrue("turbo's floor is at least medium's — the ladder never inverts", ultra.minRamBytes >= medium.minRamBytes)
        // At every RAM the cut is a PREFIX of the ladder: the rungs whose floor the device meets.
        for (ram in listOf(0L, gate - 1, gate, ultra.minRamBytes - 1, ultra.minRamBytes, 12_000_000_000L, Long.MAX_VALUE)) {
            val expected = cpuLineup.filter { ram >= WhisperCatalog.byId(it)!!.minRamBytes }
            assertEquals("ram=$ram", expected, OnboardingLogic.firstRunLineup(cpuLineup, ram, emptySet()))
            assertEquals("ram=$ram: the cut is a prefix of the ladder", cpuLineup.take(expected.size), expected)
            // ...and every card the flow shows is one the catalogue would badge on this device.
            expected.forEach {
                assertTrue("ram=$ram: '$it' is in the lineup but not recommended", WhisperCatalog.isRecommendedForDevice(WhisperCatalog.byId(it)!!, ram))
            }
        }
    }

    @Test fun over_the_gate_an_installed_small_rung_keeps_its_card() {
        // A big phone whose user already downloaded small-q8 (an upgrade from 4.7, or a decline
        // recovery) still sees it — dropping a card for a model on disk is the disturbance the
        // 4.3 rule forbids. Since 4.9 it sees it anyway (the lineup is cumulative); the
        // non-disturbance rule is what would keep it if turbo's floor were ever raised above
        // an installed turbo's device.
        val kept = OnboardingLogic.firstRunLineup(cpuLineup, 12_000_000_000L, setOf("small-q8"))
        assertEquals(listOf("small-q8", "medium-q8", "ultra-q8"), kept)
        // And THIS is why the flow lifts (the round after 4.8.0, review): the steer over that
        // list is medium, and medium is its SECOND card. Unlifted, the chip sits on card two
        // while card one wears nothing. The cut alone does not leave the steer at the head.
        val steer = OnboardingLogic.firstRunSteer(kept, cpuSteer, 12_000_000_000L)
        assertEquals("medium-q8", steer)
        assertNotEquals("the cut does NOT put the steer at the head here", steer, kept.first())
        assertEquals(listOf("medium-q8", "small-q8", "ultra-q8"), OnboardingLogic.steerFirst(kept, steer))
        // An installed rung under its floor keeps its card too — the rule the non-disturbance
        // clause is actually for.
        assertEquals(
            listOf("small-q8", "ultra-q8"),
            OnboardingLogic.firstRunLineup(cpuLineup, gate - 1, setOf("ultra-q8")),
        )
    }

    @Test fun the_boundary_at_exactly_the_gate_is_at_or_above() {
        // ">= gate" is over, "gate - 1" is under — the same `>=` the catalogue's own
        // isRecommendedForDevice uses, so the badge and the lineup flip on the same byte.
        assertEquals(listOf("small-q8", "medium-q8", "ultra-q8"), OnboardingLogic.firstRunLineup(cpuLineup, gate, emptySet()))
        assertEquals(listOf("small-q8"), OnboardingLogic.firstRunLineup(cpuLineup, gate - 1, emptySet()))
        assertEquals("medium-q8", OnboardingLogic.firstRunSteer(cpuLineup, cpuSteer, gate))
        assertEquals("small-q8", OnboardingLogic.firstRunSteer(cpuLineup, cpuSteer, gate - 1))
        listOf("medium-q8", "ultra-q8").forEach {
            assertTrue(WhisperCatalog.isRecommendedForDevice(WhisperCatalog.byId(it)!!, gate))
            assertFalse(WhisperCatalog.isRecommendedForDevice(WhisperCatalog.byId(it)!!, gate - 1))
        }
    }

    @Test fun an_npu_capable_lineup_is_untouched_by_the_gate_in_either_direction() {
        // "For NPU, we're good … you shouldn't see any other models because that's just v3
        // turbo." Already true by the 4.3 one-tier rule; the RAM rule returns the list as given
        // — at every RAM, and with an installed CPU tier riding alongside turbo.
        val turboOnly = ModelTierCopy.orderedForLanguageTagFor("en-US", setOf("npu", "npu-turbo"))
        assertEquals(listOf("npu-turbo"), turboOnly)
        for (ram in listOf(0L, 3_700_000_000L, gate - 1, gate, 12_000_000_000L, Long.MAX_VALUE)) {
            assertEquals(turboOnly, OnboardingLogic.firstRunLineup(turboOnly, ram, emptySet()))
        }
        val turboPlusInstalled = ModelTierCopy.orderedForLanguageTagFor("en-US", setOf("npu", "npu-turbo"), setOf("small-q8"))
        assertTrue("npu-turbo" in turboPlusInstalled && "small-q8" in turboPlusInstalled)
        assertEquals(turboPlusInstalled, OnboardingLogic.firstRunLineup(turboPlusInstalled, 0L, setOf("small-q8")))
        assertEquals(turboPlusInstalled, OnboardingLogic.firstRunLineup(turboPlusInstalled, Long.MAX_VALUE, setOf("small-q8")))
        // And the suspended lineup (the no-wedge escape restored the CPU ladder beside turbo)
        // is likewise untouched: turbo is in it, so the one-tier clause returns it whole.
        val suspended = ModelTierCopy.orderedForLanguageTagFor(
            "en-US", setOf("npu", "npu-turbo"), OnboardingLogic.chooserAlsoOfferedIds(emptySet(), true),
        )
        assertEquals(suspended, OnboardingLogic.firstRunLineup(suspended, 0L, emptySet()))
    }

    @Test fun the_steer_is_medium_over_the_gate_small_under_it_and_unchanged_for_npu_class_lineups() {
        // Over the gate: MEDIUM — a controller ruling on the measurements (medium-q8 1,341 ms
        // per commit at 0.31 of its floor; ultra-q8 4,849 ms at 0.99: no headroom for a slower
        // SoC, and a RAM gate says nothing about speed). The constant is one obvious val.
        assertEquals("medium-q8", OnboardingLogic.FIRST_RUN_STEER_ABOVE_GATE_ID)
        assertEquals("medium-q8", OnboardingLogic.firstRunSteer(cpuLineup, cpuSteer, 5_600_000_000L))
        assertEquals("medium-q8", OnboardingLogic.firstRunSteer(cpuLineup, cpuSteer, 12_000_000_000L))
        // Under it: small.
        assertEquals("small-q8", OnboardingLogic.firstRunSteer(cpuLineup, cpuSteer, 3_700_000_000L))
        assertEquals("small-q8", OnboardingLogic.firstRunSteer(cpuLineup, cpuSteer, 0L))
        // NPU-class lineups keep today's steer — turbo where offered, npu where offered alone —
        // at every RAM.
        val turboLineup = ModelTierCopy.orderedForLanguageTagFor("en-US", setOf("npu", "npu-turbo"))
        val turboSteer = ModelTierCopy.steerIdForLanguageTagFor("en-US", setOf("npu", "npu-turbo"))
        assertEquals("npu-turbo", turboSteer)
        val npuLineup = ModelTierCopy.orderedForLanguageTagFor("en-US", setOf("npu"))
        val npuSteer = ModelTierCopy.steerIdForLanguageTagFor("en-US", setOf("npu"))
        assertEquals("npu", npuSteer)
        for (ram in listOf(0L, gate - 1, gate, Long.MAX_VALUE)) {
            assertEquals(turboSteer, OnboardingLogic.firstRunSteer(turboLineup, turboSteer, ram))
            assertEquals(npuSteer, OnboardingLogic.firstRunSteer(npuLineup, npuSteer, ram))
        }
        // The steer is always a card in the filtered lineup — a badge on a card that is not on
        // screen is the Bengali-review shape, one axis over. Every RAM, every lineup above.
        for (ram in listOf(0L, 3_700_000_000L, gate - 1, gate, 5_600_000_000L, 12_000_000_000L)) {
            for ((lineup, steer) in listOf(cpuLineup to cpuSteer, turboLineup to turboSteer, npuLineup to npuSteer)) {
                val filtered = OnboardingLogic.firstRunLineup(lineup, ram, emptySet())
                assertTrue(
                    "steer must be on screen (ram=$ram, lineup=$lineup)",
                    OnboardingLogic.firstRunSteer(filtered, steer, ram) in filtered,
                )
            }
        }
    }

    @Test fun an_npu_only_lineup_keeps_its_npu_card_through_the_gate_and_cuts_only_the_cpu_ladder() {
        // A device offered `npu` without turbo (a family with the small pack measured and the
        // turbo pack not) is not narrowed by the one-tier rule, so the RAM rule applies to it —
        // to its CPU RUNGS. The gated npu-class id is not a Q8 rung and rides through, so the
        // steer that names it (`npu`, per L9) always has a card.
        val npuLineup = ModelTierCopy.orderedForLanguageTagFor("en-US", setOf("npu"))
        assertTrue("npu" in npuLineup)
        val under = OnboardingLogic.firstRunLineup(npuLineup, 3_700_000_000L, emptySet())
        assertTrue("npu" in under)
        assertTrue("small-q8" in under)
        assertFalse("medium-q8" in under)
        assertFalse("ultra-q8" in under)
        val over = OnboardingLogic.firstRunLineup(npuLineup, 12_000_000_000L, emptySet())
        assertTrue("npu" in over)
        // 4.9: cumulative — small stays on the card over the gate.
        assertTrue("small-q8" in over)
        assertTrue("medium-q8" in over && "ultra-q8" in over)
    }

    // ---------------------------------------------------------------- steerFirst (the lift, both surfaces)

    @Test fun steer_first_lifts_the_steered_card_and_keeps_every_other_card_in_place() {
        // The Settings picker's case: the full ladder, steered at medium over the gate. The
        // ordering rule's head is small (the language/gate steer); the RAM rule's answer must
        // lead, or the chip sits on the second card while the first wears nothing.
        assertEquals(listOf("small-q8", "medium-q8", "ultra-q8"), cpuLineup)
        val steer = OnboardingLogic.firstRunSteer(cpuLineup, cpuSteer, 12_000_000_000L)
        assertEquals("medium-q8", steer)
        assertEquals(listOf("medium-q8", "small-q8", "ultra-q8"), OnboardingLogic.steerFirst(cpuLineup, steer))
        // Under the gate the steer is already the head: a no-op, byte for byte.
        val under = OnboardingLogic.firstRunSteer(cpuLineup, cpuSteer, 3_700_000_000L)
        assertEquals("small-q8", under)
        assertEquals(cpuLineup, OnboardingLogic.steerFirst(cpuLineup, under))
        // The last card lifted: the two it passed keep their relative order (stable).
        assertEquals(listOf("ultra-q8", "small-q8", "medium-q8"), OnboardingLogic.steerFirst(cpuLineup, "ultra-q8"))
    }

    @Test fun steer_first_is_a_permutation_and_a_steer_off_the_lineup_changes_nothing() {
        for (steer in cpuLineup + listOf("npu-turbo", "npu", "large-v3", "")) {
            val out = OnboardingLogic.steerFirst(cpuLineup, steer)
            assertEquals("same cards (steer=$steer)", cpuLineup.sorted(), out.sorted())
            if (steer in cpuLineup) assertEquals("steer leads (steer=$steer)", steer, out.first())
            else assertEquals("unknown steer is a no-op (steer=$steer)", cpuLineup, out)
            assertEquals("the rest keep their order (steer=$steer)", cpuLineup.filter { it != steer }, out.filter { it != steer })
        }
        assertEquals(emptyList<String>(), OnboardingLogic.steerFirst(emptyList(), "medium-q8"))
    }

    @Test fun on_both_surfaces_the_same_device_is_steered_to_the_same_card_and_it_leads() {
        // The whole point of the round: the flow (RAM-cut lineup, lifted) and the picker (full
        // lineup, lifted) name ONE card per device, and it heads BOTH lists. Walked over the RAM
        // classes, every gate answer the two surfaces can be handed, and every installed subset
        // of the CPU ladder — the review of this round found the flow's head wrong for exactly
        // one of those cells (over the gate, small on disk: the cut keeps small at the head
        // while the steer is medium), and the earlier spelling of this test drove only the
        // empty subset and asserted membership, not the head, for the flow. Both now hold.
        val rams = listOf(0L, 3_700_000_000L, gate - 1, gate, 5_600_000_000L, 12_000_000_000L)
        val gates = listOf(emptySet(), setOf("npu"), setOf("npu-turbo"), setOf("npu", "npu-turbo"))
        val installedSubsets = listOf(
            emptySet(), setOf("small-q8"), setOf("medium-q8"), setOf("ultra-q8"),
            setOf("small-q8", "medium-q8"), setOf("medium-q8", "ultra-q8"), cpuLineup.toSet(),
        )
        var flowLiftMattered = 0
        for (ram in rams) for (offered in gates) for (installed in installedSubsets) {
            val tag = "(ram=$ram, offered=$offered, installed=$installed)"
            val ordered = ModelTierCopy.orderedForLanguageTagFor("en-US", offered, installed)
            val cpu = ModelTierCopy.steerIdForLanguageTagFor("en-US", offered)
            // The flow: cut, then steer over the cut, then lift.
            val flowCut = OnboardingLogic.firstRunLineup(ordered, ram, installed)
            val flowSteer = OnboardingLogic.firstRunSteer(flowCut, cpu, ram)
            val flowLineup = OnboardingLogic.steerFirst(flowCut, flowSteer)
            // The picker: steer over the full list, then lift.
            val pickerSteer = OnboardingLogic.firstRunSteer(ordered, cpu, ram)
            val pickerLineup = OnboardingLogic.steerFirst(ordered, pickerSteer)
            assertEquals("one steer per device $tag", flowSteer, pickerSteer)
            assertEquals("the picker leads with it $tag", pickerSteer, pickerLineup.first())
            assertEquals("the picker keeps the whole lineup $tag", ordered.sorted(), pickerLineup.sorted())
            assertEquals("the flow leads with it $tag", flowSteer, flowLineup.first())
            assertEquals("the flow's lift keeps the cut's cards $tag", flowCut.sorted(), flowLineup.sorted())
            if (flowCut.first() != flowSteer) flowLiftMattered++
        }
        // The lift is not decoration on the flow: the cell the review named is in the walk —
        // and since 4.9's cumulative cut it is every over-the-gate CPU cell, installed or not.
        assertTrue("the flow's cut left the steer off the head somewhere in the walk", flowLiftMattered > 0)
        val reviewCell = OnboardingLogic.firstRunLineup(cpuLineup, 12_000_000_000L, setOf("small-q8"))
        assertEquals("small-q8", reviewCell.first())
        assertEquals("medium-q8", OnboardingLogic.firstRunSteer(reviewCell, cpuSteer, 12_000_000_000L))
        val freshCell = OnboardingLogic.firstRunLineup(cpuLineup, 12_000_000_000L, emptySet())
        assertEquals("small-q8", freshCell.first())
        assertEquals("medium-q8", OnboardingLogic.firstRunSteer(freshCell, cpuSteer, 12_000_000_000L))
    }

    @Test fun the_gate_never_adds_a_card_and_never_reorders_one() {
        // A permutation-preserving FILTER, nothing more: whatever comes out was in, in the same
        // relative order. Walked over every RAM class and every installed subset of the ladder.
        val rams = listOf(0L, 3_700_000_000L, gate - 1, gate, 5_600_000_000L, 12_000_000_000L, Long.MAX_VALUE)
        val subsets = listOf(
            emptySet(), setOf("small-q8"), setOf("medium-q8"), setOf("ultra-q8"),
            setOf("small-q8", "medium-q8"), setOf("medium-q8", "ultra-q8"), cpuLineup.toSet(),
        )
        for (ram in rams) for (installed in subsets) {
            val out = OnboardingLogic.firstRunLineup(cpuLineup, ram, installed)
            assertTrue("subset (ram=$ram, installed=$installed)", cpuLineup.containsAll(out))
            assertEquals("order kept (ram=$ram, installed=$installed)", cpuLineup.filter { it in out }, out)
            assertTrue("never empty", out.isNotEmpty())
            assertTrue("installed rungs always keep their card", out.containsAll(installed))
        }
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

    @Test fun the_chip_counts_only_the_two_required_bubble_permissions() {
        // 4.3.3 (accessibility-optional-spec §1, §3): the accessibility service left the count.
        // It is recommended, not required — a user without it is not "missing" anything the
        // bubble needs to START; their transcripts are copied instead of typed. The signature
        // itself is the pin: there is no third parameter to count.
        assertEquals(0, OnboardingLogic.missingBubblePermissions(mic = true, overlay = true))
        assertEquals(1, OnboardingLogic.missingBubblePermissions(mic = true, overlay = false))
        assertEquals(1, OnboardingLogic.missingBubblePermissions(mic = false, overlay = true))
        assertEquals(2, OnboardingLogic.missingBubblePermissions(mic = false, overlay = false))
    }

    @Test fun the_chip_is_absent_when_everything_is_granted() {
        // The clean dashboard stays clean — the chip exists only while something is actually
        // wrong (owner report 2026-08-01: granted permissions were visible only in Settings,
        // missing ones nowhere at all).
        assertNull(OnboardingLogic.homePermissionChipText(0))
    }

    @Test fun the_chip_text_counts_honestly() {
        assertEquals("1 permission still needed — tap to review", OnboardingLogic.homePermissionChipText(1))
        // Two is the most the count can now reach (mic + overlay) — 4.3.3.
        assertEquals("2 permissions still needed — tap to review", OnboardingLogic.homePermissionChipText(2))
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

    // ------------------------------------------- permissions gate (3.5.x; two-of-three at 4.3.3)

    @Test fun permissions_continue_unlocks_on_mic_and_overlay_alone() {
        // 4.3.3 (accessibility-optional-spec §1): the three-permission gate became two. The mic
        // and the overlay are what the BUBBLE needs to exist; the accessibility service is what
        // TYPING needs, and typing has a clipboard fallback that works on every device —
        // including the one (Galaxy XR) whose device policy permits no third-party accessibility
        // service at all, where the old gate made the whole product unreachable. The signature
        // is the pin: Continue cannot consult a parameter it no longer takes.
        assertTrue(OnboardingLogic.permissionsContinueEnabled(mic = true, overlay = true))
        assertFalse(OnboardingLogic.permissionsContinueEnabled(mic = false, overlay = true))
        assertFalse(OnboardingLogic.permissionsContinueEnabled(mic = true, overlay = false))
        assertFalse(OnboardingLogic.permissionsContinueEnabled(mic = false, overlay = false))
        // And the gate is the count, so the footer's hint and its button can never disagree.
        for (mic in listOf(true, false)) for (overlay in listOf(true, false)) {
            assertEquals(
                "gate == (nothing required missing) for mic=$mic overlay=$overlay",
                OnboardingLogic.missingBubblePermissions(mic, overlay) == 0,
                OnboardingLogic.permissionsContinueEnabled(mic, overlay),
            )
        }
    }

    @Test fun permissions_hint_counts_whats_missing_and_stays_silent_when_nothing_is() {
        assertNull(OnboardingLogic.permissionsContinueHint(0))
        // 4.3.3: the tail names BOTH optional rows now. "notification access is optional" alone
        // implied the accessibility row was required, which is the sentence this build retires.
        assertEquals(
            "1 required permission still needed — the accessibility service and notification " +
                "access are optional.",
            OnboardingLogic.permissionsContinueHint(1),
        )
        assertEquals(
            "2 required permissions still needed — the accessibility service and notification " +
                "access are optional.",
            OnboardingLogic.permissionsContinueHint(2),
        )
    }

    // ------------------------------------- the accessibility service is recommended (4.3.3)

    /**
     * THE ACCESSIBILITY STEP'S COPY IS THE 4.3.3 BRIEF, PINNED VERBATIM (accessibility-optional-
     * spec §1: "the step's copy says what the service buys ('types your words into the app you're
     * using') and what happens without it ('your transcript is copied — paste it where you need
     * it')", plus the plain secondary `Continue without it`). The pin exists so a copy change is
     * a decision; this one was the brief's.
     */
    @Test fun the_accessibility_step_copy_is_the_4_3_3_brief_verbatim() {
        assertEquals("Recommended", OnboardingLogic.ACCESSIBILITY_RECOMMENDED_BADGE)
        assertEquals(
            "Types your words into the app you're using",
            OnboardingLogic.ACCESSIBILITY_WHY,
        )
        assertEquals(
            "Without it, your transcript is copied — paste it where you need it.",
            OnboardingLogic.ACCESSIBILITY_WITHOUT_IT,
        )
        assertEquals("Continue without it", OnboardingLogic.CONTINUE_WITHOUT_ACCESSIBILITY)
        // The Settings row's off-state subtitle (brief §5): "recommended", never "required".
        assertEquals(
            "Recommended — without it, transcripts are copied to the clipboard",
            OnboardingLogic.ACCESSIBILITY_SETTINGS_OFF,
        )
        // No sentence on this step may call the service required — that is the whole change.
        for (s in listOf(
            OnboardingLogic.ACCESSIBILITY_RECOMMENDED_BADGE,
            OnboardingLogic.ACCESSIBILITY_WHY,
            OnboardingLogic.ACCESSIBILITY_WITHOUT_IT,
            OnboardingLogic.CONTINUE_WITHOUT_ACCESSIBILITY,
            OnboardingLogic.ACCESSIBILITY_SETTINGS_OFF,
        )) {
            assertFalse("<<$s>> calls the service required", s.lowercase().contains("required"))
        }
    }

    /**
     * THE PLATFORM NOTES, PINNED VERBATIM (accessibility-optional-spec §2; the restricted one
     * re-worded in fix round 1, B1) — one sentence per [AccessibilityAvailability.Availability],
     * chosen by the pure rule and rendered by the flow's accessibility card. The blocked sentence
     * is what a Galaxy XR user reads (acceptance H3); the restricted sentence is GUIDANCE toward
     * the App-info remedy for a SUSPECTED Android 13+ Restricted Settings block — the rule behind
     * it is an inference, so the sentence says "may be" and names the one thing the user can see
     * (a greyed-out toggle), never a diagnosis. Total over the enum, so a new availability cannot
     * render nothing.
     */
    @Test fun the_platform_notes_are_the_4_3_3_brief_verbatim_one_per_availability() {
        assertEquals(
            "This device doesn't allow apps to type for you. Your transcript will be copied instead.",
            OnboardingLogic.ACCESSIBILITY_BLOCKED_BY_DEVICE,
        )
        assertEquals(
            "If the toggle is greyed out, Android may be blocking it for a sideloaded-style install — " +
                "open App info → ⋮ → Allow restricted settings, then try again.",
            OnboardingLogic.ACCESSIBILITY_RESTRICTED_SETTINGS,
        )
        // B1: hedged, and stays hedged — the sentence may suspect, never assert.
        assertTrue(OnboardingLogic.ACCESSIBILITY_RESTRICTED_SETTINGS.contains("may be blocking"))
        assertFalse(OnboardingLogic.ACCESSIBILITY_RESTRICTED_SETTINGS.contains("is blocking"))
        assertEquals(
            OnboardingLogic.ACCESSIBILITY_WITHOUT_IT,
            OnboardingLogic.accessibilityNote(AccessibilityAvailability.Availability.ENABLEABLE),
        )
        assertEquals(
            OnboardingLogic.ACCESSIBILITY_BLOCKED_BY_DEVICE,
            OnboardingLogic.accessibilityNote(AccessibilityAvailability.Availability.BLOCKED_BY_DEVICE),
        )
        assertEquals(
            OnboardingLogic.ACCESSIBILITY_RESTRICTED_SETTINGS,
            OnboardingLogic.accessibilityNote(AccessibilityAvailability.Availability.RESTRICTED_SETTINGS),
        )
        // Settings' row (brief §5) reads the same rule: the device veto is a device fact and
        // shows there too; everything else reads "Recommended", never "required".
        assertEquals(
            OnboardingLogic.ACCESSIBILITY_SETTINGS_OFF,
            OnboardingLogic.accessibilitySettingsSubtitle(AccessibilityAvailability.Availability.ENABLEABLE),
        )
        assertEquals(
            OnboardingLogic.ACCESSIBILITY_BLOCKED_BY_DEVICE,
            OnboardingLogic.accessibilitySettingsSubtitle(AccessibilityAvailability.Availability.BLOCKED_BY_DEVICE),
        )
        assertEquals(
            OnboardingLogic.ACCESSIBILITY_RESTRICTED_SETTINGS,
            OnboardingLogic.accessibilitySettingsSubtitle(AccessibilityAvailability.Availability.RESTRICTED_SETTINGS),
        )
        for (availability in AccessibilityAvailability.Availability.values()) {
            for (s in listOf(
                OnboardingLogic.accessibilityNote(availability),
                OnboardingLogic.accessibilitySettingsSubtitle(availability),
            )) {
                assertTrue("$availability renders a sentence", s.isNotBlank())
                assertFalse("<<$s>> calls the service required", s.lowercase().contains("required"))
            }
        }
    }
}
