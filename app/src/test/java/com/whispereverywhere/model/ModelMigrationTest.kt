package com.whispereverywhere.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelMigrationTest {

    private fun decide(
        selectedId: String?,
        selectedInstalled: Boolean = true,
        targetInstalled: Boolean = false,
        online: Boolean = true,
    ) = ModelMigration.decide(selectedId, selectedInstalled, targetInstalled, online)

    @Test fun a_current_tier_needs_no_migration() {
        assertEquals(ModelMigration.Action.None, decide("pro"))
        assertEquals(ModelMigration.Action.None, decide("multi"))
    }

    @Test fun a_retired_but_supported_tier_is_left_completely_alone() {
        // 3.7 Workstream H: eco and base are retired (hidden from the chooser) but still work.
        // Raising the migration card for them would ask a user with a working 60 MB model to
        // download 190 MB they never asked for — and the card's own copy ("much faster") would
        // be false, since the 190 MB English tier is slower than the 60 MB one. This is the test
        // that forces decide() to gate on `unsupported` rather than `retired`.
        assertEquals(ModelMigration.Action.None, decide("eco"))
        assertEquals(ModelMigration.Action.None, decide("base"))
        assertEquals(ModelMigration.Action.None, decide("eco", online = false))
        assertEquals(ModelMigration.Action.None, decide("base", targetInstalled = true))

        // **4.6 — `pro` JOINS THEM, AND IT IS THE MEMBER THAT MATTERS.** The owner's ruling of
        // 2026-09-13 retires the last English-only rung, and `pro` is the tier the largest number
        // of English users are on. `retired` and NOT `unsupported` is the whole care in that
        // change: every one of those users must fall through to None here, or a release that was
        // about showing the owner more models would open by asking the installed base to
        // re-download 190 MB nobody requested — for a model whose only difference from theirs is
        // a multilingual vocab head, which makes the card's implied "this is better" false as
        // well as unwanted.
        listOf(true, false).forEach { online ->
            listOf(true, false).forEach { targetInstalled ->
                listOf(true, false).forEach { selectedInstalled ->
                    assertEquals(
                        "pro/online=$online/target=$targetInstalled/installed=$selectedInstalled",
                        ModelMigration.Action.None,
                        decide("pro", selectedInstalled, targetInstalled, online),
                    )
                }
            }
        }
    }

    @Test fun no_selection_needs_no_migration() {
        // First run. Onboarding handles this; migration must not interfere.
        assertEquals(ModelMigration.Action.None, decide(null))
    }

    @Test fun an_unknown_id_needs_no_migration() {
        // Downgrade from a future version. Onboarding will handle it; do not delete anything.
        assertEquals(ModelMigration.Action.None, decide("some-future-tier"))
    }

    /**
     * 4.6 — **`ultra` LEFT every arm of this test.** It was the only `unsupported` MULTILINGUAL
     * tier, so it was this file's subject for four cases; the owner's ruling of 2026-09-13
     * un-retires it as an instrument, and an offered tier cannot also be one the app migrates
     * people off. `extreme` (medium.en) is now the ONLY unsupported tier, so it carries the arms
     * that need a real catalogue row, and the MULTILINGUAL arm of `targetIdFor` is proved directly
     * (`the_multilingual_target_is_multi_whether_or_not_a_tier_currently_points_at_it`) rather than
     * through a tier that no longer exists in that state.
     */
    @Test fun a_retired_tier_online_without_the_target_offers_the_download() {
        assertEquals(ModelMigration.Action.OfferDownload, decide("extreme"))
    }

    @Test fun an_un_retired_tier_is_left_completely_alone_whatever_the_state() {
        // The user-visible half of the un-retirement, and the defect it would be if the
        // `unsupported` bit had been left set: an `ultra` user has been carrying "This model is no
        // longer supported" since 3.7, and the chooser is now simultaneously inviting them to try
        // that very rung. `decide` gates on `unsupported` alone, so dropping both flags together
        // is what makes the card go away.
        assertEquals(ModelMigration.Action.None, decide("ultra"))
        assertEquals(ModelMigration.Action.None, decide("ultra", online = false))
        assertEquals(ModelMigration.Action.None, decide("ultra", targetInstalled = true))
        assertEquals(ModelMigration.Action.None, decide("ultra", selectedInstalled = false))
        // ...and so is every other rung the ladder added. None of them is a tier the app wants
        // anyone off of, which is what `instrument` means.
        WhisperCatalog.instruments.forEach { m ->
            assertEquals(
                "instrument '${m.id}' raised a migration action — an offered rung may not also " +
                    "be one the app migrates people off",
                ModelMigration.Action.None,
                decide(m.id),
            )
            assertEquals(ModelMigration.Action.None, decide(m.id, targetInstalled = true))
            assertEquals(ModelMigration.Action.None, decide(m.id, online = false))
        }
    }

    @Test fun a_retired_tier_offline_waits_and_keeps_the_old_model() {
        // THE load-bearing case. Deleting or switching here would leave an offline user with no
        // usable model and no way to get one — the app gate would dump them into onboarding.
        assertEquals(ModelMigration.Action.WaitForNetwork, decide("extreme", online = false))
    }

    @Test fun swap_only_happens_once_the_target_is_actually_on_disk() {
        assertEquals(
            ModelMigration.Action.SwapAndDelete("extreme", WhisperCatalog.DEFAULT_MODEL_ID),
            decide("extreme", targetInstalled = true),
        )
        assertEquals(ModelMigration.Action.OfferDownload, decide("extreme", targetInstalled = false))
    }

    @Test fun swap_happens_offline_too_once_the_target_is_installed() {
        // No network needed to swap a file that is already downloaded. (4.6: the target is `multi`,
        // not `pro` — `pro` is retired, so the ENGLISH arm of targetIdFor resolves through
        // DEFAULT_MODEL_ID to the multilingual rung. Safe in this direction and only this one:
        // `multi` transcribes English perfectly, being the same whisper-small weights with a
        // multilingual vocab head; the reverse is the MF3 bug.)
        assertEquals(
            ModelMigration.Action.SwapAndDelete("extreme", "multi"),
            decide("extreme", targetInstalled = true, online = false),
        )
    }

    @Test fun a_retired_tier_whose_file_is_already_gone_still_offers_the_download() {
        // User cleared storage. Nothing to delete, but they still need a working model.
        assertEquals(ModelMigration.Action.OfferDownload, decide("extreme", selectedInstalled = false))
    }

    // MF3: the target must match the retired model's language scope. Routing a MULTILINGUAL user
    // to an ENGLISH-only default silently breaks dictation in every other language, with no
    // warning — that was the MF3 bug, and it is the reason `targetIdFor` takes a scope at all.
    // Replaces the old `migration_target_is_the_catalog_default`, which assumed every retired
    // tier maps to WhisperCatalog.DEFAULT_MODEL_ID regardless of scope.
    //
    // 4.6 — **the MULTILINGUAL arm no longer has a catalogue row pointing at it.** `ultra` was the
    // only unsupported MULTILINGUAL tier and it is a live rung again, so the arm is proved on the
    // function directly. That is not a weaker test: the arm is still REACHED by every future
    // multilingual retirement, and a mapping that only holds for today's rows is exactly the
    // assumption MF3 was.
    @Test fun the_multilingual_target_is_multi_whether_or_not_a_tier_currently_points_at_it() {
        assertEquals("multi", ModelMigration.targetIdFor(ModelScope.MULTILINGUAL))
        // The arm reached the way a real retirement would reach it: a MULTILINGUAL row carrying
        // the unsupported bit. No such row exists today, which is why it is constructed.
        assertEquals(ModelScope.MULTILINGUAL, WhisperCatalog.byId("ultra")!!.scope)
        assertEquals(
            "a multilingual retirement must land on a multilingual rung, or dictation breaks " +
                "silently in every language the user actually speaks",
            "multi",
            ModelMigration.targetIdFor(WhisperCatalog.byId("ultra")!!.scope),
        )
    }

    /**
     * 4.6 — was `an_english_unsupported_tier_migrates_to_pro`. With `pro` retired the ENGLISH arm
     * resolves through `DEFAULT_MODEL_ID` to `multi`, so **both arms of `targetIdFor` now answer
     * `multi`** — and that is the right answer to both.
     *
     * The collapse is safe in exactly ONE direction and this is that direction: an ENGLISH-scope
     * user landing on a MULTILINGUAL rung loses nothing, because `multi` is the same 190 MB of
     * whisper-small weights with a multilingual vocab head and transcribes English perfectly.
     * The reverse — a multilingual user routed to an English-only tier — is MF3, and the scope
     * parameter is what still makes it unreachable.
     */
    @Test fun an_english_unsupported_tier_migrates_to_the_multilingual_default() {
        val a = decide("extreme", targetInstalled = true) as ModelMigration.Action.SwapAndDelete
        assertEquals(WhisperCatalog.DEFAULT_MODEL_ID, a.toId)
        assertEquals("multi", a.toId)
        assertEquals("extreme", a.fromId)
        // The two arms agree TODAY, and the two constants behind them are still separate on
        // purpose: folding the multilingual target into DEFAULT_MODEL_ID would mean the next
        // English default silently becomes the multilingual target too, which is MF3
        // reintroduced by a refactor rather than by a decision.
        assertEquals(
            ModelMigration.targetIdFor(ModelScope.ENGLISH),
            ModelMigration.targetIdFor(ModelScope.MULTILINGUAL),
        )
    }

    /**
     * **THE ONE UNRECOVERABLE DEFECT IN THIS TASK, PROVED ABSENT OVER THE FULL CROSS PRODUCT:**
     * a migration target that is retired or an instrument.
     *
     * A RETIRED target moves users from one dead end to another — they land on a tier with no card
     * in the picker, so they cannot see or change what they are on. An INSTRUMENT target is worse:
     * `decide` migrates people without being asked, so it would move an installed base onto a
     * finalizer whose throughput nobody has measured, and the streaming previewer would hide the
     * failure because words land on the strip 0.4 s behind the voice whatever the finalizer is
     * doing. Neither is a state a user can get themselves out of.
     *
     * Exhaustive in both senses the brief asks for: every [ModelScope] through `targetIdFor`, and
     * every catalogue id through `decide` across all eight boolean combinations of its other three
     * inputs — so a target is checked on every path that can actually produce one.
     */
    @Test fun no_migration_target_is_ever_retired_or_an_instrument() {
        fun assertGoodTarget(target: String, how: String) {
            val model = WhisperCatalog.byId(target)
            assertNotNull("$how: target '$target' does not resolve at all", model)
            assertTrue(
                "$how: target '$target' is not pickable — users would land on a tier with no card",
                WhisperCatalog.pickable.any { it.id == target },
            )
            assertFalse("$how: target '$target' is RETIRED — one dead end to another", model!!.retired)
            assertFalse("$how: target '$target' is unsupported — it would migrate them again", model.unsupported)
            assertFalse(
                "$how: TARGET '$target' IS AN INSTRUMENT. `decide` migrates users without being " +
                    "asked, so this would move an installed base onto a rung whose throughput " +
                    "nobody has measured — and the previewer would hide it",
                model.instrument,
            )
            assertFalse("$how: target '$target' is gated — some devices have no such assets", model.gated)
            assertEquals(
                "$how: target '$target' is not multilingual, so it cannot serve every scope that " +
                    "resolves to it",
                ModelScope.MULTILINGUAL,
                model.scope,
            )
        }
        // Arm 1: every scope, through the function itself. This is the exhaustive half — the enum
        // has two members and both are driven, so no future retirement can reach an unchecked arm.
        ModelScope.values().forEach { scope ->
            assertGoodTarget(ModelMigration.targetIdFor(scope), "targetIdFor($scope)")
        }
        // Arm 2: every catalogue id, through `decide`, over its whole input space. Whatever
        // SwapAndDelete a real user state can produce, its target is checked.
        val ids: List<String?> = WhisperCatalog.entries.map { it.id } + listOf(null, "some-future-tier")
        ids.forEach { id ->
            listOf(true, false).forEach { selectedInstalled ->
                listOf(true, false).forEach { targetInstalled ->
                    listOf(true, false).forEach { online ->
                        val action = ModelMigration.decide(id, selectedInstalled, targetInstalled, online)
                        if (action is ModelMigration.Action.SwapAndDelete) {
                            assertGoodTarget(action.toId, "decide($id,$selectedInstalled,$targetInstalled,$online)")
                            assertEquals("the swap must name the tier it is moving OFF", id, action.fromId)
                        }
                    }
                }
            }
        }
        // And the set of ids that can produce ANY action at all is exactly the unsupported set —
        // one tier, `extreme`. Every instrument and every merely-retired tier answers None, which
        // is the property the two arms above are checking the consequences of.
        assertEquals(
            listOf("extreme"),
            WhisperCatalog.entries.filter { decide(it.id) != ModelMigration.Action.None }.map { it.id },
        )
    }

    @Test fun every_migration_target_is_a_tier_the_user_can_actually_pick() {
        // A target that is itself retired would move users from one dead end to another.
        listOf(ModelScope.ENGLISH, ModelScope.MULTILINGUAL).forEach { scope ->
            val target = ModelMigration.targetIdFor(scope)
            assertTrue(
                "target '$target' for $scope is not pickable",
                WhisperCatalog.pickable.any { it.id == target },
            )
            // 4.6: was `assertEquals(scope, byId(target)!!.scope)` — the target's scope must EQUAL
            // the source's. That is no longer the rule, because it cannot be: `pro` is retired, so
            // the ENGLISH arm resolves to the multilingual default and no ENGLISH-scope target
            // exists to resolve to. The rule that survives is the one the equality was a proxy for
            // — **the target must be able to transcribe the scope it serves** — and it is strictly
            // stronger stated that way: a MULTILINGUAL target serves an ENGLISH source (same
            // whisper-small weights, multilingual vocab head), while an ENGLISH target could never
            // serve a MULTILINGUAL source. That asymmetry IS the MF3 bug, so it is asserted rather
            // than left to the equality that happened to imply it.
            val model = WhisperCatalog.byId(target)!!
            assertEquals(
                "target '$target' for $scope must be multilingual: it is the only scope that can " +
                    "serve BOTH arms, and the ENGLISH arm now resolves here too",
                ModelScope.MULTILINGUAL,
                model.scope,
            )
            assertTrue(
                "target '$target' for $scope cannot transcribe that scope's language",
                model.scope == ModelScope.MULTILINGUAL || model.scope == scope,
            )
        }
    }
}
