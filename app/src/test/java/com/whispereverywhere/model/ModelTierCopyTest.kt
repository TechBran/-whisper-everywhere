package com.whispereverywhere.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelTierCopyTest {

    // The discipline that would have prevented the Bengali review: nobody can add or reword an
    // offered tier without stating a size, a speed-vs-accuracy position, and language coverage.

    /**
     * Every tier a chooser can render — [WhisperCatalog.pickable] PLUS the gated 4.0 `npu`, which
     * gate-passing devices see and which `pickable` therefore cannot reach.
     *
     * The census loops below iterate THIS list, not `pickable`: the copy rules are properties of a
     * card the user reads, and the gate decides whether the card renders, not whether the rules
     * apply. Iterating `pickable` would have let npu's copy say anything at all with the suite
     * still green.
     */
    private val offeredTiers = WhisperCatalog.entries.filter { !it.retired }

    /**
     * 4.6 — **the ungated lineup, spelled out once.** The ladder's exact content and order are
     * pinned in `WhisperCatalogHelpersTest.pickable_is_exactly_the_ladder_in_order`; what the
     * ordering tests below are about is which card LEADS, so they compose against this rather
     * than restating the ids per row. `entries` order, with the steered card lifted to the
     * front — which is the whole of the 3.7 rule, over a longer list. 4.7: the list is the three
     * Q8 rungs, and the steered card (`small-q8`) is already first in catalogue order.
     *
     * **One list, not two, since `pro` was retired.** The 3.7 rule had an English lineup and an
     * everyone-else lineup because the steer differed; with every offered rung multilingual there
     * is one steer and therefore one order, and the locale no longer changes it. That is the
     * ruling's consequence, not a simplification of the test: the Bengali-review rule ("never land
     * a user on a tier that is worse for their language") is now satisfied structurally, because
     * there is no worse-for-your-language rung left in the chooser.
     */
    private val ladderLineup = listOf("small-q8", "medium-q8", "ultra-q8")

    @Test fun every_offered_tier_has_copy() {
        offeredTiers.forEach { model ->
            assertNotNull("no copy for offered tier '${model.id}'", ModelTierCopy.forId(model.id))
        }
    }

    @Test fun every_tier_states_its_size_as_a_badge() {
        offeredTiers.forEach { model ->
            val copy = ModelTierCopy.forId(model.id)!!
            assertTrue(
                "tier '${model.id}' has no size badge",
                copy.badges.any { it.endsWith(" MB") },
            )
        }
    }

    @Test fun the_size_badge_tells_the_truth_about_the_download() {
        // 60 MB tiers say 60, 190 MB tiers say 190 — the badge must track approxBytes. For a
        // PAIRED tier that is the sum of both files, which is what the user actually downloads.
        offeredTiers.forEach { model ->
            val copy = ModelTierCopy.forId(model.id)!!
            val expectedMb = (model.approxBytes / 1_000_000L).toInt()
            val statedMb = copy.badges.first { it.endsWith(" MB") }.removeSuffix(" MB").toInt()
            assertTrue(
                "tier '${model.id}' badge says $statedMb MB but the download is ~$expectedMb MB",
                kotlin.math.abs(statedMb - expectedMb) <= 5,
            )
        }
    }

    @Test fun every_tier_takes_a_speed_vs_accuracy_position() {
        val positionWords = POSITION_WORDS
        offeredTiers.forEach { model ->
            val copy = ModelTierCopy.forId(model.id)!!
            val all = (copy.headline + " " + copy.body).lowercase()
            assertTrue(
                "tier '${model.id}' copy takes no speed-vs-accuracy position",
                positionWords.any { all.contains(it) },
            )
        }
    }

    @Test fun language_coverage_is_a_badge_matching_the_catalog_scope() {
        // Coverage renders as a badge — visually impossible to miss. "English only" on every
        // ENGLISH tier; "90+ languages" on every MULTILINGUAL tier.
        offeredTiers.forEach { model ->
            val copy = ModelTierCopy.forId(model.id)!!
            when (model.scope) {
                ModelScope.ENGLISH -> assertTrue(
                    "ENGLISH tier '${model.id}' lacks the 'English only' badge",
                    copy.badges.contains("English only"),
                )
                ModelScope.MULTILINGUAL -> assertTrue(
                    "MULTILINGUAL tier '${model.id}' lacks the '90+ languages' badge",
                    copy.badges.contains("90+ languages"),
                )
            }
        }
    }

    @Test fun the_owner_approved_headlines_are_pinned_exactly() {
        // 4.6: `pro`'s "Best English accuracy" is GONE with `pro`'s card, because `pro` is retired
        // (owner ruling 2026-09-13 — no English-only rungs at all) and a retired tier has no copy.
        // The string was still TRUE of the file, which is exactly why the tier is `retired` and
        // not `unsupported`: nobody on it is being told to leave.
        assertNull(ModelTierCopy.forId("pro"))
        // 4.6: `multi` was "Best multilingual accuracy" — owner-approved in 3.7, TRUE while it was
        // one of two rungs and the only multilingual one, and FALSE the moment five larger
        // multilingual rungs were offered beside it; it became "Everyday accuracy, smallest
        // download" / "...the one rung on this list with a measured verdict behind it."
        // 4.7: `multi` is RETIRED (the Q8 ruling of 2026-09-17), so its card is gone the way
        // `pro`'s went — and its headline moved to `small-q8`, of which it is now true.
        assertNull(ModelTierCopy.forId("multi"))
        // The three Q8 cards, pinned exactly. Every sentence is checkable against
        // docs/measurements/2026-09-17-tab-cpu-ladder.md or a file's own ggml header.
        assertEquals("Everyday accuracy, smallest download", ModelTierCopy.forId("small-q8")!!.headline)
        assertEquals(
            "Whisper small — the same weights as the retired 190 MB Q5_1 model, stored at Q8_0. " +
                "Measured to keep up with margin on the owner's tablet, and recommended on " +
                "every device.",
            ModelTierCopy.forId("small-q8")!!.body,
        )
        assertEquals("Sharper accuracy, larger download", ModelTierCopy.forId("medium-q8")!!.headline)
        assertEquals(
            "Whisper medium at Q8_0: 24 encoder layers at 1024 dims against small's 12 at 768. " +
                "Measured to keep up with margin on the owner's tablet; recommended where the " +
                "device reports at least 5.5 GB of memory.",
            ModelTierCopy.forId("medium-q8")!!.body,
        )
        assertEquals("Highest accuracy, largest download", ModelTierCopy.forId("ultra-q8")!!.headline)
        assertEquals(
            "Large-v3-turbo at Q8_0 — large-v3's own 32-layer encoder with a 4-layer decoder: " +
                "the most accurate model on this ladder. On the owner's flagship tablet it kept " +
                "up with no margin to spare, so on a less capable device expect the typed text " +
                "to fall behind. Offered for its accuracy, not recommended. " +
                ModelTierCopy.KEEP_UP_NOTE,
            ModelTierCopy.forId("ultra-q8")!!.body,
        )
        // The measured sentences are SCOPED to the device they were measured on — the shape the
        // no-speed-claims rule allows — and the RAM sentence states the catalogue's own threshold.
        listOf("small-q8", "medium-q8").forEach {
            assertTrue("'$it' must scope its measured claim to the tablet", ModelTierCopy.forId(it)!!.body.contains("on the owner's tablet"))
        }
        assertTrue(ModelTierCopy.forId("ultra-q8")!!.body.lowercase().contains("on the owner's flagship tablet"))
        assertEquals(5_500_000_000L, WhisperCatalog.byId("medium-q8")!!.minRamBytes)
        assertTrue(ModelTierCopy.forId("medium-q8")!!.body.contains("at least 5.5 GB"))
    }

    @Test fun retired_and_unknown_tiers_have_no_copy() {
        // Retired tiers stay resolvable in WhisperCatalog but are not offered — no copy required.
        assertNull(ModelTierCopy.forId("extreme"))
        // 3.7 Workstream H: the 60 MB tiers joined them.
        assertNull(ModelTierCopy.forId("eco"))
        assertNull(ModelTierCopy.forId("base"))
        assertNull(ModelTierCopy.forId("nope"))
        // 4.6: `ultra` LEFT this list — it was offered again, so it had a card. 4.7: it is back,
        // with `multi`, `medium-q5` and `large-v3` — every Q5 rung, by the Q8 ruling. Stated as
        // the rule rather than as a list, so the next retirement or un-retirement cannot outlive
        // it.
        WhisperCatalog.entries.filter { it.retired }.forEach {
            assertNull("retired tier '${it.id}' still has copy", ModelTierCopy.forId(it.id))
        }
        listOf("multi", "medium-q5", "ultra", "large-v3").forEach {
            assertNull("'$it' is retired (Q8 ruling 2026-09-17) and must have no card", ModelTierCopy.forId(it))
        }
        assertNotNull("ultra-q8 is offered and must have a card", ModelTierCopy.forId("ultra-q8"))
    }

    @Test fun no_offered_tier_names_a_retired_one() {
        // "Noticeably slower than Eco" was true and is now a dangling reference to a card the
        // user can no longer see. Copy may not describe a tier by comparison to a dead one.
        // The match is WORD-ANCHORED (H3 review, m1): retired ids are short common substrings —
        // "record"/"recording" contains "eco", "based"/"database" contains "base" — so a plain
        // `contains` fails ordinary dictation copy while naming a reference that is not there.
        //
        // 4.7 — ONE STATED EXEMPTION: the retired id `large-v3` is ALSO the upstream checkpoint
        // family's name, and two offered cards carry it as the name of the model they ARE —
        // `ultra-q8` is "Large-v3-turbo" and the pinned `npu-turbo` body reads "Large-v3's own
        // encoder". That is the same reasoning SPEED_CLAIM_WORDS gives for "turbo": renaming
        // someone else's checkpoint to dodge a word census would make the cards harder to match
        // to the files they fetch. What the rule is about — positioning against a card the user
        // cannot see — is held for that rung by its BADGE instead: no offered card names the
        // retired large-v3 rung's 1081 MB.
        val familyNames = setOf("large-v3")
        val retiredIds = WhisperCatalog.entries.filter { it.retired }.map { it.id.lowercase() }
        offeredTiers.forEach { model ->
            val copy = ModelTierCopy.forId(model.id)!!
            val all = (copy.headline + " " + copy.body + " " + copy.badges.joinToString(" ")).lowercase()
            (retiredIds - familyNames).forEach { r ->
                assertFalse(
                    "tier '${model.id}' copy names retired tier '$r'",
                    Regex("\\b" + Regex.escape(r) + "\\b").containsMatchIn(all),
                )
            }
            assertFalse(
                "tier '${model.id}' copy positions itself against the retired 1081 MB large-v3 rung",
                all.contains("1081 mb") || all.contains("1,081"),
            )
        }
        // The exemption is exactly one id, and it is a retired one — a live `large-v3` rung would
        // make the family-name reading and the rung reading collide on a card the user CAN see.
        assertEquals(setOf("large-v3"), familyNames)
        assertTrue(WhisperCatalog.byId("large-v3")!!.retired)
    }

    // =================================================== 4.6 T2 — THE COPY TELLS THE TRUTH
    //
    // The ladder makes three copy rules load-bearing that were previously slack, and each gets a
    // census below rather than a comment:
    //
    //   1. No non-NPU rung RANKS another by speed, in either direction — the three Q8 rungs were
    //      not measured against one another on the user's device. A measured sentence scoped to
    //      the device it was measured on is allowed (4.7); a rank is not. The two NPU cards KEEP
    //      their measured, owner-ruled "fastest": that is a true claim and removing it would be
    //      the regression, so the census asserts BOTH halves.
    //   2. The one rung whose verdict does not clear warns, plainly, that it may not keep up
    //      with continuous speech, and names the remedy; a measured pass does not warn.
    //   3. A q8_0 rung still names its quantisation and its retired q5 twin's, because the
    //      twins are installed on the devices of everyone who tried one on the internal track.

    /**
     * **[ModelTierCopy.KEEP_UP_NOTE] has to say three things, and the third is the one 4.6 could
     * get wrong.** The failure mode ("may not keep up with continuous speech"), the symptom the
     * user can actually see ("the typed text falls behind your voice" — the only symptom there is,
     * because since 4.4.0 the previewer keeps putting words on the strip at 0.4 s whatever the
     * finalizer is doing), and the AXIS of the remedy.
     *
     * The axis is the third assertion and it is not pedantry. "A smaller rung is the fix" is
     * ambiguous on a ladder where three rungs are the same model at two quantisations: a user on
     * `medium-q8` (823 MB) reads it and can land on `medium-q5` (539 MB), a smaller FILE carrying
     * the same 24 layers at 1024 dims. So the note names the ARCHITECTURAL axis — a smaller
     * Whisper, one with fewer layers — under which a quantisation twin is excluded by construction
     * in both directions, because a twin is never a smaller Whisper.
     *
     * **The fourth assertion forbids the other failure, which is the one that actually shipped
     * (review round 1, B1): the note may not name the quantisation axis AT ALL.** A remedy that
     * names it ranks it. *"Not the same Whisper at a finer quantisation"* told a `medium-q5` user
     * that `medium-q8` is not the fix and an `ultra` user that `ultra-q8` is not the fix — the two
     * repack-path rungs, and one of the two comparisons the owner's six-device session exists to
     * run, pre-answered in the negative in the voice of advice. The app's own vocabulary settles
     * that reading: "finer quantisation" is the `Q8_0` side on three cards in the same list
     * ([ModelTierCopy] `small-q8`, `medium-q8`, `ultra-q8`), and `small-q8`'s card says of that
     * same axis that its throughput "is unknown". This note renders on FIVE production cards, so
     * whatever it says, it says five times.
     */
    @Test fun the_keep_up_note_states_the_failure_the_symptom_and_the_axis_of_the_remedy() {
        val note = ModelTierCopy.KEEP_UP_NOTE
        assertEquals(
            "This model may not keep up with continuous speech on this device. If the typed " +
                "text falls behind your voice, a smaller Whisper is the fix — one with fewer " +
                "layers.",
            note,
        )
        // 1. the failure mode, hedged — "may not", because nobody has measured it.
        assertTrue(note.contains("may not keep up with continuous speech"))
        // 2. the symptom the user can see, and the remedy.
        assertTrue(note.contains("falls behind your voice"))
        assertTrue(note.contains("is the fix"))
        // 3. the axis, named POSITIVELY: a smaller WHISPER — fewer layers, not a smaller file.
        assertTrue(
            "the remedy must name the architectural axis — 'a smaller rung' is ambiguous for a " +
                "medium-q8 user, whose smaller FILE is medium-q5: the same 24 layers at 1024 dims",
            note.contains("a smaller Whisper") && note.contains("fewer layers"),
        )
        // 4. and it must rank the quantisation axis in NEITHER direction (review round 1, B1).
        // 4.7: the axis IS measured now (Q8_0 won, twice, on one device), and the note still
        // must not name it — the remedy is a smaller Whisper, and every quantisation twin of an
        // offered rung is retired, so naming the axis would point at a card the user cannot see.
        assertFalse(
            "KEEP_UP_NOTE names quantisation in its remedy, which ranks that axis on the card — " +
                "the remedy is an architectural one (fewer layers), and the Q5 twins are retired",
            Regex("\\b(quantis\\w*|quantiz\\w*|q\\d_\\d)\\b").containsMatchIn(note.lowercase()),
        )
        // The note is one string, so a speed claim smuggled into it is a speed claim on every
        // card that carries it. The census below iterates the cards; this is the string itself.
        SPEED_CLAIM_WORDS.forEach {
            assertFalse(
                "KEEP_UP_NOTE claims speed with '$it' — it may state a failure MODE, never a rank",
                Regex("\\b" + Regex.escape(it) + "\\b").containsMatchIn(note.lowercase()),
            )
        }
    }

    /**
     * **NO NON-NPU RUNG CLAIMS SPEED, AND THE TWO NPU CARDS STILL DO.** One test for both halves,
     * on purpose: they are the same rule seen from its two ends, and a census that only forbade
     * would be satisfied by scrubbing the lineup silent — which would delete two claims that are
     * measured, owner-ruled and true.
     *
     * **The forbidding half.** The three Q8 rungs each carry a measured verdict, and each card
     * states it as a verdict scoped to the device it was measured on — never as a rank. None of
     * the three was measured against another on the user's device, so none may be ranked against
     * another in EITHER direction. (4.6's version of this paragraph said the intuitive ordering
     * was probably wrong because the LARGER `q8_0` file sits on ggml's ARM i8mm repack path; the
     * 2026-09-17 session measured exactly that, and the rule survives the measurement because it
     * is about the user's device, not the owner's tablet.) See [SPEED_CLAIM_WORDS] for the
     * vocabulary and for the three words that are deliberately not in it.
     *
     * **The requiring half.** `npu` and `npu-turbo` are gated tiers whose speed was measured on
     * our own devices (encode 1.78 s fixed per commit on the Fold6 against Multilingual's 2.3 s;
     * ~6 s per 17.6 s chunk on the Tab S10+) and ruled on by the owner (2026-09-10). Their cards
     * must go on saying so. This is the assertion that makes a future "scrub every speed word"
     * pass fail loudly instead of quietly costing the app a true claim.
     *
     * Note how this composes with the 3.7 census: [every_tier_takes_a_speed_vs_accuracy_position]
     * requires one of [POSITION_WORDS], and this one forbids the speed members of that list on
     * every CPU card — so a CPU rung has to satisfy the 3.7 rule with "accuracy", which is the one
     * axis whisper's own checkpoint ordering entitles these cards to rank.
     */
    @Test fun no_cpu_rung_claims_speed_and_the_two_npu_cards_still_do() {
        val cpuRungs = offeredTiers.filter { !it.gated }
        // Guard the census's own reach: if the ladder ever loses its CPU rows, this test must not
        // pass by iterating nothing.
        assertEquals("the CPU ladder is not three rungs any more", 3, cpuRungs.size)
        cpuRungs.forEach { model ->
            val copy = ModelTierCopy.forId(model.id)!!
            // The displayName rides on the same card (OnboardingModelScreen.kt:686), above the
            // headline, so it is part of what the user reads as this rung's claim.
            val all = (
                model.displayName + " " + copy.headline + " " + copy.body + " " +
                    copy.badges.joinToString(" ")
                ).lowercase()
            SPEED_CLAIM_WORDS.forEach { word ->
                assertFalse(
                    "CPU rung '${model.id}' claims speed with '$word'. The three Q8 rungs were " +
                        "measured on ONE tablet and never against one another on the user's " +
                        "device — a card may state its own measured verdict, scoped to that " +
                        "tablet, and may not rank the rungs in either direction",
                    Regex("\\b" + Regex.escape(word) + "\\b").containsMatchIn(all),
                )
            }
        }
        val npuCards = offeredTiers.filter { it.gated }
        assertEquals(listOf("npu", "npu-turbo"), npuCards.map { it.id })
        npuCards.forEach { model ->
            val copy = ModelTierCopy.forId(model.id)!!
            assertTrue(
                "gated tier '${model.id}' no longer claims the speed it measured. This claim is " +
                    "true, scoped to silicon this app has benchmarked, and owner-ruled " +
                    "(2026-09-10) — removing it is the regression, not the fix",
                listOf("fastest", "faster").any { (copy.headline + " " + copy.body).lowercase().contains(it) },
            )
        }
    }

    /**
     * **EVERY INSTRUMENT WARNS, AND NOTHING WITH A CLEARING VERDICT DOES.**
     *
     * The note has to be the LAST thing on the card, not a clause buried mid-paragraph — it is
     * the sentence a user comes back to after the typed text has fallen a paragraph behind, and
     * the previewer guarantees they will not notice before then (words keep landing on the strip
     * at 0.4 s whatever the finalizer is doing). It also may not be the WHOLE card: a rung that
     * only warns has not said what it is.
     *
     * 4.6 had five warners and one earned exemption (`small-q8`, the twin of the one measured
     * rung, which said "throughput unknown" instead). 4.7 — **the instrument set is one rung and
     * it warns**: `ultra-q8` kept up on the owner's flagship tablet with no margin
     * (`KeepUp.KEPT_UP_WITHOUT_MARGIN`), so "may not keep up with continuous speech on this
     * device" is the honest shape of what a less capable device will see, and the remedy the
     * note names — a smaller Whisper — is `medium-q8` or `small-q8`, both offered.
     *
     * Nothing with a clearing verdict warns at all: `small-q8`, `medium-q8` and the two NPU tiers
     * have numbers behind them, and a caution on a measured pass would train the user to ignore
     * the caution. (`medium-q8` on a device under its RAM threshold gets the screen's own "High-end
     * devices only" line instead, which is a statement about RAM and is true.)
     */
    @Test fun every_instrument_warns_and_nothing_with_a_clearing_verdict_does() {
        val note = ModelTierCopy.KEEP_UP_NOTE
        val instruments = WhisperCatalog.entries.filter { it.instrument }
        assertEquals("the instrument set is not one rung any more", listOf("ultra-q8"), instruments.map { it.id })

        val warners = instruments.filter { ModelTierCopy.forId(it.id)!!.body.endsWith(note) }
        assertEquals(
            "every instrument must end its card with the keep-up warning — an instrument is a " +
                "rung whose verdict does not clear, and the user is owed the failure mode",
            instruments.map { it.id },
            warners.map { it.id },
        )
        warners.forEach {
            val body = ModelTierCopy.forId(it.id)!!.body
            assertTrue(
                "'${it.id}' warns and says nothing else — a card that only cautions has not said " +
                    "what the model IS, which is the whole of what these cards are for",
                body.removeSuffix(note).trim().length > 40,
            )
            // The instrument's card states the measured shape of the risk in its own words too.
            // "Keeps up on this flagship with no margin" is the doc's finding — that sentence is in
            // docs/measurements/2026-09-17-tab-cpu-ladder.md. "Slower devices should expect to
            // fall behind" is the CARD's own inference from 0.99, not a sentence the doc contains.
            assertTrue("'${it.id}' must say it kept up with no margin", body.contains("no margin"))
            assertTrue("'${it.id}' must say it is not recommended", body.contains("not recommended"))
        }

        (offeredTiers - instruments.toSet()).forEach { model ->
            assertFalse(
                "'${model.id}' has a clearing verdict and still carries the keep-up warning — a " +
                    "caution on a measured pass teaches the user to ignore cautions",
                ModelTierCopy.forId(model.id)!!.body.contains(note),
            )
        }
    }

    /**
     * **EVERY OFFERED RUNG IS THE Q8_0 SIDE OF A QUANTISATION TWIN WHOSE Q5 SIDE IS RETIRED, AND
     * THE CATALOGUE STILL BACKS THE WORD "SAME".**
     *
     * 4.6 required each Q8_0 card to read as its twin at another quantisation, name the twin's
     * badge, and point only at a card the same user could SEE — the session could not interpret
     * "the 539 MB one was slower than the 823 MB one" otherwise. The session happened, the
     * finding was made (Q8_0 won, 2.2x and 6.9x on one device), and the owner retired every Q5
     * twin. So the visible-twin half of that rule is now unsatisfiable by construction — the
     * twin is a card nobody can see — and a Q8 card that positioned itself against it would be
     * the exact defect the 3.7 rule retired `pro`'s cross-reference for.
     *
     * What survives, and is held here: every offered CPU rung IS a Q8_0 twin (the ruling, at the
     * copy layer); its card names its own quantisation, because the retired Q5 files are still on
     * the devices of everyone who tried one and a user comparing "the one I had" with "this one"
     * needs the token; the card names NO retired id (the word-anchored census above); and the
     * CATALOGUE agrees the two rows are one model — equal mel width, equal scope, different
     * digest, the Q8_0 row the larger file, the twin retired.
     */
    @Test fun every_offered_rung_is_the_q8_side_of_a_twin_whose_q5_side_is_retired() {
        val pairs = WhisperCatalog.entries.mapNotNull { row -> twinOf(row)?.let { row to it } }
        assertEquals("three quantisation twins, so six rows in pairs", 6, pairs.size)
        val requantised = pairs.filter { quantOf(it.first) == "Q8_0" }
        assertEquals(
            "the Q8_0 side of each twin is the offered side — the owner's ruling, at the copy layer",
            listOf("small-q8", "medium-q8", "ultra-q8"),
            requantised.map { it.first.id },
        )
        assertEquals(
            "every offered CPU rung is a Q8_0 twin, and nothing else is offered",
            requantised.map { it.first.id },
            WhisperCatalog.pickable.map { it.id },
        )
        requantised.forEach { (q8, twin) ->
            val copy = ModelTierCopy.forId(q8.id)!!
            assertTrue("'${q8.id}' body does not name its own quantisation", copy.body.contains("Q8_0"))
            assertTrue("'${twin.id}' must be retired — the Q8 ruling", twin.retired)
            assertNull("'${twin.id}' is retired and must have no card", ModelTierCopy.forId(twin.id))
            assertFalse(
                "'${q8.id}' positions itself against '${twin.id}', a card the user cannot see",
                Regex("\\b" + Regex.escape(twin.id) + "\\b").containsMatchIn(copy.body.lowercase()),
            )
            // And the catalogue has to agree with the word "same" wherever a card uses it.
            assertEquals("mel width disagrees for '${q8.id}' and '${twin.id}'", twin.melBins, q8.melBins)
            assertEquals("scope disagrees for '${q8.id}' and '${twin.id}'", twin.scope, q8.scope)
            assertFalse("'${q8.id}' and '${twin.id}' share a digest", q8.sha256 == twin.sha256)
            assertTrue(
                "'${q8.id}' is not the larger file of its pair — Q8_0 stores more bits per weight " +
                    "than any Q5, so a pair where it does not is a wrong literal somewhere",
                q8.approxBytes > twin.approxBytes,
            )
        }
        // `small-q8`'s card names the retired twin by SIZE and quantisation ("the retired 190 MB
        // Q5_1 model"), because that is the model every 4.6.0 production user is on and "the
        // same weights" is the sentence they need to recognise the 264 MB card as their model.
        // This is the one stated exception to the header rule in ModelTierCopy (a retired tier
        // named by size, never by id — the id is forbidden three assertions up).
        val small = ModelTierCopy.forId("small-q8")!!.body
        assertTrue(small.contains("190 MB") && small.contains("Q5_1") && small.contains("retired"))
        assertTrue(small.contains("the same weights"))
        // What it may NOT say is that the two transcribe alike. The measurement doc records
        // wallMs only, nothing in the repo compares Q8_0 and Q5_1 transcripts, and the owner has
        // named that comparison as the open gate ("prove the accuracy of the small and medium
        // model"). A card that announces a result the accuracy pass has not produced is the
        // defect the review of 138be0b found; this pins its absence.
        assertFalse(
            "small-q8's card claims an accuracy equivalence no measurement supports",
            Regex("accuracy (matches|is the same|is identical|equals)").containsMatchIn(small.lowercase()),
        )
    }

    /**
     * **THE SPEED CLAIM THAT IS NOT IN THIS FILE.** `OnboardingModelScreen.kt:783` renders, on
     * any card where `model.minRamBytes > 0` and the device cannot recommend it: *"High-end
     * devices only — this tier needs more RAM than this device reports. You can still pick it,
     * but performance may suffer."*
     *
     * "Performance may suffer" is a speed claim, it is emitted by the SCREEN rather than by
     * [ModelTierCopy], and the census above therefore cannot see it. It is also exactly the
     * mechanism 4.6 rejected: the research proposed expressing "not recommended" as a
     * `minRamBytes` above any shipping phone (§6.3), and [WhisperModel.instrument] exists instead
     * because these rungs are unrecommended for a reason that is **not a fact about the device in
     * the user's hand** — nobody has measured their throughput. Wire them to the RAM gate and the
     * app would tell a 16 GB phone that its RAM is short of a 264 MB model, and predict the
     * outcome, in one sentence, on the rung the research says may be the FASTER of its pair.
     *
     * So in 4.6 no offered CPU rung was RAM-gated at all. **4.7 narrows that to its real subject:
     * no INSTRUMENT is RAM-gated**, because the instrument's caution is about the margin, not the
     * device — and a MEASURED rung may carry a threshold, because for it the screen's sentence is
     * true. `medium-q8` is that rung: it kept up on a 12 GB tablet, an 823 MB model on a 4 GB
     * phone is a real fact about the device in the user's hand, and "needs more RAM than this
     * device reports" is exactly what the owner's ruling says ("recommended above a RAM
     * threshold"). The threshold is PROVISIONAL (the `extreme` precedent, pending the owner's
     * weakest-device run), which the catalogue row says. The default stays at 0 — the floor for
     * every device — so no phone reads a chooser with nothing recommended on it.
     */
    @Test fun no_instrument_is_ram_gated_and_the_one_threshold_is_on_the_measured_medium_tier() {
        offeredTiers.filter { !it.gated && it.instrument }.forEach { model ->
            assertEquals(
                "instrument '${model.id}' is RAM-gated, so OnboardingModelScreen.kt:783 will " +
                    "print 'you can still pick it, but performance may suffer' on its card — " +
                    "attributing to the device's RAM a caution that is about the rung's margin",
                0L,
                model.minRamBytes,
            )
        }
        // The default is recommended everywhere.
        assertEquals(0L, WhisperCatalog.byId(WhisperCatalog.DEFAULT_MODEL_ID)!!.minRamBytes)
        // The one offered rung with a threshold, and it is measured — not an instrument.
        assertEquals(
            listOf("medium-q8"),
            offeredTiers.filter { !it.gated && it.minRamBytes > 0L }.map { it.id },
        )
        assertFalse(WhisperCatalog.byId("medium-q8")!!.instrument)
        assertEquals(5_500_000_000L, WhisperCatalog.byId("medium-q8")!!.minRamBytes)
        // ...and its card states the threshold in the user's units, so the screen's RAM line and
        // the card's own sentence agree about why.
        assertTrue(ModelTierCopy.forId("medium-q8")!!.body.contains("5.5 GB"))
        // The retired precedent the number came from is still a resolvable row with the same value.
        assertTrue(WhisperCatalog.byId("extreme")!!.retired)
        assertEquals(WhisperCatalog.byId("extreme")!!.minRamBytes, WhisperCatalog.byId("medium-q8")!!.minRamBytes)
    }

    /**
     * 4.6 — was `english_locales_are_steered_to_pro`. The English branch is GONE because the tier
     * it pointed at is retired (owner ruling 2026-09-13: multilingual rungs only), and a steer at
     * a retired tier is not a steer — the chooser does not render that card, so nothing would be
     * lifted to the front and [ModelTierCopy.STEER_BADGE] would sit on no card at all for every
     * English user.
     *
     * **The 3.7 rule is satisfied, not abandoned.** Its point was the Bengali review: never land a
     * user on a tier that is worse for the language they speak. With every offered rung
     * multilingual, there is no worse-for-your-language rung left to land on. And the English
     * user's replacement is not a downgrade — whisper-small's weights with a multilingual vocab
     * head. 4.7: the steer is `small-q8` (was `multi`, retired by the Q8 ruling).
     */
    @Test fun english_locales_are_steered_to_small_q8_now_that_the_english_rung_is_retired() {
        assertEquals("small-q8", ModelTierCopy.steerIdForLanguageTag("en"))
        assertEquals("small-q8", ModelTierCopy.steerIdForLanguageTag("en-US"))
        assertEquals("small-q8", ModelTierCopy.steerIdForLanguageTag("en_GB"))
        assertEquals("small-q8", ModelTierCopy.steerIdForLanguageTag("EN-au"))
        // And the tiers the steer used to name are not merely unsteered — they are out of the lineup.
        assertFalse(WhisperCatalog.pickable.map { it.id }.contains("pro"))
        assertFalse(WhisperCatalog.pickable.map { it.id }.contains("multi"))
    }

    @Test fun every_other_locale_is_steered_to_small_q8() {
        // The Bengali review is the reason this rule exists at all: an English-only tier must
        // never be the thing a non-English speaker lands on by default.
        assertEquals("small-q8", ModelTierCopy.steerIdForLanguageTag("bn"))
        assertEquals("small-q8", ModelTierCopy.steerIdForLanguageTag("bn-BD"))
        assertEquals("small-q8", ModelTierCopy.steerIdForLanguageTag("fr-CA"))
        assertEquals("small-q8", ModelTierCopy.steerIdForLanguageTag("zh-Hans-CN"))
        assertEquals("small-q8", ModelTierCopy.steerIdForLanguageTag(""))
    }

    /**
     * **THE STEER LANDS ON A RUNG THE APP STANDS BEHIND**, over a wide spread of tags rather than
     * four. 4.6 replaces the scope assertion with a stronger triple, because the old one — "the
     * English steer is ENGLISH-scope" — described a branch that no longer exists, while the risk
     * it guarded against (steering someone somewhere they should not be steered) got bigger: there
     * are now six rungs in the lineup that nobody may be steered to at all.
     */
    @Test fun the_steer_always_lands_on_a_pickable_measured_multilingual_tier() {
        val pickableIds = WhisperCatalog.pickable.map { it.id }
        listOf("en", "en-US", "en_GB", "EN-au", "bn", "bn-BD", "de", "fr-CA", "zh-Hans-CN", "", "xx").forEach { tag ->
            val id = ModelTierCopy.steerIdForLanguageTag(tag)
            assertTrue("steer '$id' for '$tag' is not pickable", pickableIds.contains(id))
            val model = WhisperCatalog.byId(id)!!
            assertEquals(
                "steer '$id' for '$tag' is not multilingual — the owner's ruling applies to the " +
                    "steer before it applies to anything else",
                ModelScope.MULTILINGUAL,
                model.scope,
            )
            assertFalse(
                "STEER '$id' FOR '$tag' IS AN INSTRUMENT. Steering is the one thing an instrument " +
                    "must never be the object of: it is offered so the owner can measure it, and " +
                    "the steer is the app telling a fresh install what to pick",
                model.instrument,
            )
        }
    }

    @Test fun the_steer_badge_is_pinned_exactly_and_claims_nothing_about_speed() {
        assertEquals("Best match for your language", ModelTierCopy.STEER_BADGE)
        listOf("faster", "fastest", "quicker", "instant").forEach {
            assertFalse(ModelTierCopy.STEER_BADGE.lowercase().contains(it))
        }
    }

    @Test fun the_steered_tier_is_offered_first() {
        // 4.6: one lineup, not two. The locale no longer changes the order because it no longer
        // changes the steer — every offered rung is multilingual.
        assertEquals(ladderLineup, ModelTierCopy.orderedForLanguageTag("en-US"))
        assertEquals(ladderLineup, ModelTierCopy.orderedForLanguageTag("bn-BD"))
        // 4.6: and no instrument ever LEADS. The ladder is offered, not promoted — adding it moves
        // no card to the top, in any locale.
        listOf("en", "en-US", "bn-BD", "de-AT", "zh-Hans-CN", "").forEach { tag ->
            val ordered = ModelTierCopy.orderedForLanguageTag(tag)
            assertFalse(
                "'$tag': an instrument leads the lineup — offering is not steering",
                WhisperCatalog.byId(ordered.first())!!.instrument,
            )
            assertEquals("'$tag': the head is the measured floor", "small-q8", ordered.first())
        }
    }

    @Test fun the_order_is_always_a_permutation_of_the_pickable_catalog() {
        // A future tier that nobody remembered to mention here must still reach the chooser —
        // dropping one silently would make it undownloadable.
        val pickable = WhisperCatalog.pickable.map { it.id }
        listOf("en", "bn", "de-AT", "").forEach { tag ->
            val ordered = ModelTierCopy.orderedForLanguageTag(tag)
            assertEquals("'$tag' lost or duplicated a tier", pickable.size, ordered.size)
            assertEquals("'$tag' is not a permutation", pickable.toSet(), ordered.toSet())
        }
    }

    @Test fun every_ordered_id_resolves_and_has_copy() {
        ModelTierCopy.orderedForLanguageTag("en").forEach {
            assertNotNull(WhisperCatalog.byId(it))
            assertNotNull(ModelTierCopy.forId(it))
        }
    }

    // ------------------------------------------------------ 4.0/4.1: steering the gated tiers
    //
    // The 3.7 rules above answer "which tier for this language". These answer "...and does this
    // device have a faster way to run it" — without letting the answer to the second question
    // change the answer to the first. Since 4.1 the gate answer is a SET of offered gated tier
    // ids (two gated tiers can be independently installed); `setOf("npu")` below is the exact
    // 4.0 `true`, and `emptySet()` the exact 4.0 `false` — identical assertions, new spelling.

    @Test fun the_gated_tier_is_the_steer_only_where_the_device_runs_it_and_the_locale_needs_it() {
        // Capable device, and a language `multi` was already the right answer for: the same model
        // on the Hexagon is a strictly better version of the same answer.
        assertEquals("npu", ModelTierCopy.steerIdForLanguageTagFor("bn-BD", setOf("npu")))
        assertEquals("npu", ModelTierCopy.steerIdForLanguageTagFor("zh-Hans-CN", setOf("npu")))
        assertEquals("npu", ModelTierCopy.steerIdForLanguageTagFor("fr-CA", setOf("npu")))
        assertEquals("npu", ModelTierCopy.steerIdForLanguageTagFor("", setOf("npu")))
        // **4.6 — AN ENGLISH LOCALE NOW TAKES THE `npu` SUBSTITUTION TOO, and the old rule's own
        // reasoning is what carries it.** These three rows asserted "pro" until 4.6: an English
        // locale kept the English-only tier however fast the silicon was, because steering an
        // English speaker onto a multilingual tier for speed was the Bengali review mirrored — it
        // traded the accuracy they came for against a speed they never asked about. `pro` is
        // retired now, so the English user's CPU rung IS `multi`, and `npu` carries `multi`'s own
        // weights on faster silicon. There is no accuracy being traded away: it is the same model.
        // The substitution's precondition (`cpuSteer == MULTILINGUAL_STEER_ID`; a `"multi"`
        // literal until 4.7) simply holds for every locale now — and since 4.7 it is spelled on
        // the steer constant, so the steer moving to `small-q8` did not switch it off.
        assertEquals("npu", ModelTierCopy.steerIdForLanguageTagFor("en", setOf("npu")))
        assertEquals("npu", ModelTierCopy.steerIdForLanguageTagFor("en-US", setOf("npu")))
        assertEquals("npu", ModelTierCopy.steerIdForLanguageTagFor("EN-au", setOf("npu")))
        // Gate says no: the CPU steer, for every locale.
        assertEquals("small-q8", ModelTierCopy.steerIdForLanguageTagFor("bn-BD", emptySet()))
        assertEquals("small-q8", ModelTierCopy.steerIdForLanguageTagFor("en-US", emptySet()))
    }

    /**
     * 4.6 — was `the_gated_tier_leads_the_lineup_without_promoting_the_english_only_tier`. The
     * second ordering key existed to keep `multi` ahead of `pro` for a non-English user (a one-key
     * sort read [npu, pro, multi] and promoted the English-only tier above the multilingual one
     * 3.7 had demoted it below, by a change that was supposed to be about silicon). With `pro`
     * retired there is no English-only tier in the lineup to promote, so the key is INERT on
     * today's catalogue — which is why it stays: it is a rule about what may not happen, and the
     * next language-specific rung reaches it again without anyone rediscovering the reasoning.
     *
     * What remains assertable, and is: the gated card leads where it is offered, and the whole
     * ladder rides below it in catalogue order, in every locale.
     */
    @Test fun the_gated_tier_leads_the_lineup_and_the_ladder_rides_below_it() {
        assertEquals(
            listOf("npu") + ladderLineup,
            ModelTierCopy.orderedForLanguageTagFor("bn-BD", setOf("npu")),
        )
        // 4.6: was `englishLineup + "npu"` — the gated tier LAST, because an English locale
        // steered to `pro` and npu was neither the steer nor the language steer. Now the English
        // locale takes the npu substitution (same weights, faster silicon, nothing traded), so the
        // gated card leads here too and the answer is identical to every other locale's.
        assertEquals(
            listOf("npu") + ladderLineup,
            ModelTierCopy.orderedForLanguageTagFor("en-US", setOf("npu")),
        )
        assertEquals(ladderLineup, ModelTierCopy.orderedForLanguageTagFor("bn-BD", emptySet()))
        assertEquals(ladderLineup, ModelTierCopy.orderedForLanguageTagFor("en-US", emptySet()))
    }

    @Test fun the_lineup_is_a_permutation_of_this_devices_pickable_set_and_the_steer_leads_it() {
        // ORDER, not presence — the rule this branch has now paid for four times. Both chooser
        // surfaces make TWO calls: one for the cards, one for the badge and the highlight. If the
        // two ever disagree, the lineup leads with one card while "Best match for your language"
        // sits on another: every element present, every element in the wrong relationship to the
        // others, and nothing in the type system to notice. Every reachable gate answer is
        // driven: none, either tier alone, both.
        listOf("en", "en-US", "bn", "bn-BD", "de-AT", "zh-Hans-CN", "").forEach { tag ->
            listOf(
                emptySet(),
                setOf("npu"),
                setOf("npu-turbo"),
                setOf("npu", "npu-turbo"),
            ).forEach { offered ->
                val expected = WhisperCatalog.pickableFor(offered).map { it.id }
                val ordered = ModelTierCopy.orderedForLanguageTagFor(tag, offered)
                assertEquals(
                    "'$tag'/$offered lost or duplicated a tier",
                    expected.size,
                    ordered.size,
                )
                assertEquals(
                    "'$tag'/$offered is not a permutation of what this device can pick",
                    expected.toSet(),
                    ordered.toSet(),
                )
                assertEquals(
                    "'$tag'/$offered: the badged tier is not the one the lineup leads with",
                    ModelTierCopy.steerIdForLanguageTagFor(tag, offered),
                    ordered.first(),
                )
                ordered.forEach {
                    assertNotNull("ordered id '$it' does not resolve", WhisperCatalog.byId(it))
                    assertNotNull("ordered id '$it' has no card copy", ModelTierCopy.forId(it))
                }
            }
        }
    }

    @Test fun a_device_that_failed_the_gate_never_sees_a_gated_tier_at_all() {
        listOf("en", "en-US", "en_GB", "bn", "bn-BD", "de-AT", "zh-Hans-CN", "fr-CA", "").forEach { tag ->
            val ordered = ModelTierCopy.orderedForLanguageTagFor(tag, emptySet())
            assertFalse(
                "'$tag': a gated tier reached a device whose gate said no",
                ordered.contains("npu") || ordered.contains("npu-turbo"),
            )
            // 4.6: was 2 — 3.7's two tiers. The ungated lineup is now the eight-rung ladder, and
            // the size is asserted against `pickable` rather than a literal so the two cannot
            // disagree about what a device that failed the gate is offered.
            assertEquals("'$tag': the ungated lineup is the whole ladder", WhisperCatalog.pickable.size, ordered.size)
            assertTrue(
                "'$tag': the ungated steer is not a tier this device can pick",
                WhisperCatalog.pickable.map { it.id }
                    .contains(ModelTierCopy.steerIdForLanguageTagFor(tag, emptySet())),
            )
            // The 3.7 spelling still answers identically: the new overload is the same rule with
            // one more input, not a second rule free to drift from it.
            assertEquals("'$tag': the two spellings disagree", ordered, ModelTierCopy.orderedForLanguageTag(tag))
        }
        // Being steered to is a position in a list and a chip. It is not selection, and nothing
        // about the gate moves the catalog default or lets a gated tier into `pickable`.
        assertEquals("small-q8", WhisperCatalog.DEFAULT_MODEL_ID)
        assertFalse(WhisperCatalog.pickable.map { it.id }.contains("npu"))
        assertFalse(WhisperCatalog.pickable.map { it.id }.contains("npu-turbo"))
    }

    // ------------------------------------------------------------ 4.1: the second gated tier
    //
    // Turbo's steering contract FLIPPED at L9, by measurement. Decision 8 refused it a promotion
    // while its accuracy claim was unproved; the owner's on-device A/B (2026-08-29) proved it —
    // "V3 Turbo is clearly the winner — much more accurate, at only about half a second slower"
    // — so turbo now HEADS the steered lineup exactly where it is offered, npu rides second, and
    // everything else (the default, auto-selection, the turbo-absent order) is byte-unchanged.

    @Test fun the_npu_turbo_tier_steers_exactly_when_offered_for_every_locale() {
        // RE-SPECCED at L9 (was: the_npu_turbo_tier_never_steers_for_any_locale_or_any_offer_set
        // — decision 8's negative, which named its own condition: "turbo's claim is unproved".
        // The owner's measured verdict met the condition, so the pin now asserts the pick).
        // Offered means installed AND gate-passing — the only state the promotion exists in;
        // any offer set without turbo steers exactly as before. (Never auto-SELECTS is
        // unchanged and pinned where selection lives: the chooser's pickedTierId starts null —
        // ChooserSteerWiringPinTest — and DEFAULT_MODEL_ID is `pro`.)
        listOf("en", "en-US", "bn", "bn-BD", "zh-Hans-CN", "fr-CA", "").forEach { tag ->
            listOf(setOf("npu-turbo"), setOf("npu", "npu-turbo")).forEach { offered ->
                assertEquals(
                    "'$tag'/$offered: the pick must steer to npu-turbo — for EVERY locale, " +
                        "English included: the accuracy win was measured on the owner's own " +
                        "speech and large-v3-turbo is multilingual, so this is not the " +
                        "Bengali-review shape (a worse model for the user's language)",
                    "npu-turbo",
                    ModelTierCopy.steerIdForLanguageTagFor(tag, offered),
                )
            }
            listOf(emptySet(), setOf("npu")).forEach { offered ->
                assertFalse(
                    "'$tag'/$offered: turbo absent from the offer set must mean turbo absent " +
                        "from the steer — the pick promotes an INSTALLED tier, never a card " +
                        "whose 1.07 GB pair is not on the device",
                    ModelTierCopy.steerIdForLanguageTagFor(tag, offered) == "npu-turbo",
                )
            }
        }
    }

    @Test fun the_pick_promotes_turbo_above_the_npu_steer() {
        // RE-SPECCED at L9 (was: the_npu_steer_survives_turbos_arrival, asserting these same
        // four calls answered "npu"/"pro" — the pre-pick truth). With both pairs installed the
        // A/B's winner heads; npu's own steer rule is intact underneath (the setOf("npu") rows
        // in the_gated_tier_is_the_steer_only_where... still bind, unchanged).
        assertEquals("npu-turbo", ModelTierCopy.steerIdForLanguageTagFor("bn-BD", setOf("npu", "npu-turbo")))
        assertEquals("npu-turbo", ModelTierCopy.steerIdForLanguageTagFor("zh-Hans-CN", setOf("npu", "npu-turbo")))
        assertEquals("npu-turbo", ModelTierCopy.steerIdForLanguageTagFor("", setOf("npu", "npu-turbo")))
        assertEquals("npu-turbo", ModelTierCopy.steerIdForLanguageTagFor("en-US", setOf("npu", "npu-turbo")))
    }

    @Test fun a_capable_device_is_offered_turbo_and_nothing_else_in_every_locale() {
        // RE-SPECCED at 4.3 (was: a_turbo_only_device_steers_to_turbo_and_keeps_the_cpu_order_
        // below_it — the pre-4.3 menu, which kept "the CPU tiers below it" in the exact order the
        // owner has now ruled out of existence on this hardware). The steer is UNCHANGED in body;
        // what changed is the lineup it heads, which is now one card long.
        assertEquals("npu-turbo", ModelTierCopy.steerIdForLanguageTagFor("bn-BD", setOf("npu-turbo")))
        assertEquals("npu-turbo", ModelTierCopy.steerIdForLanguageTagFor("en", setOf("npu-turbo")))
        assertEquals(
            listOf("npu-turbo"),
            ModelTierCopy.orderedForLanguageTagFor("bn-BD", setOf("npu-turbo")),
        )
        assertEquals(
            listOf("npu-turbo"),
            ModelTierCopy.orderedForLanguageTagFor("en-US", setOf("npu-turbo")),
        )
    }

    @Test fun the_npu_small_tier_is_hidden_from_a_capable_chooser_and_is_not_retired_for_it() {
        // RE-SPECCED at 4.3 (was: turbo_heads_the_lineup_and_the_npu_steer_rides_second, pinning
        // [npu-turbo, npu, multi, pro] / [npu-turbo, npu, pro, multi] — the four-card menu). The
        // owner's ruling: "Users shouldn't even see the 190 megabyte model or even the 358
        // megabyte model. They should just go straight to the one gig version." `npu` is HIDDEN,
        // not retired — the streaming arc needs it, and the catalogued-but-unoffered property is
        // pinned in WhisperCatalogHelpersTest.
        assertEquals(
            listOf("npu-turbo"),
            ModelTierCopy.orderedForLanguageTagFor("bn-BD", setOf("npu", "npu-turbo")),
        )
        assertEquals(
            listOf("npu-turbo"),
            ModelTierCopy.orderedForLanguageTagFor("en-US", setOf("npu", "npu-turbo")),
        )
    }

    @Test fun the_picks_truth_table_the_head_is_turbo_only_when_offered_else_the_pre_pick_order() {
        // L9's whole contract in one table: turbo-installed vs turbo-absent x English vs
        // non-English x gate-pass vs gate-fail. The head is turbo ONLY when installed+offered;
        // every turbo-absent arm is the PRE-PICK order to the element — npu does not jump
        // multi/pro on the strength of a verdict that was about turbo.
        val table = listOf(
            // offered set              tag      expected lineup
            // 4.3: every turbo-naming row collapsed to the single card the owner ruled — the
            // menu rows this table used to carry are the assertions the branch DELETES.
            Triple(setOf("npu", "npu-turbo"), "bn-BD", listOf("npu-turbo")),
            Triple(setOf("npu", "npu-turbo"), "en-US", listOf("npu-turbo")),
            Triple(setOf("npu-turbo"), "bn-BD", listOf("npu-turbo")),
            Triple(setOf("npu-turbo"), "en-US", listOf("npu-turbo")),
            Triple(setOf("npu"), "bn-BD", listOf("npu") + ladderLineup),
            Triple(setOf("npu"), "en-US", listOf("npu") + ladderLineup),
            Triple(emptySet(), "bn-BD", ladderLineup),
            Triple(emptySet(), "en-US", ladderLineup),
        )
        table.forEach { (offered, tag, expected) ->
            assertEquals(
                "'$tag'/$offered: the pick's truth table row",
                expected,
                ModelTierCopy.orderedForLanguageTagFor(tag, offered),
            )
            assertEquals(
                "'$tag'/$offered: the steer chip follows the head",
                expected.first(),
                ModelTierCopy.steerIdForLanguageTagFor(tag, offered),
            )
        }
        // And the pick changes STEERING only: the app-wide default fallback story is untouched.
        assertEquals("small-q8", WhisperCatalog.DEFAULT_MODEL_ID)
    }

    @Test fun the_lineup_with_both_npu_tiers_is_a_permutation_of_pickableFor_with_the_steer_leading() {
        // The brief's exact claim, stated against the both-tiers set specifically (the loop above
        // drives it too — this is the named case a reader will look for). The SIZE is 1 since 4.3:
        // the permutation claim is unchanged, the thing it is a permutation OF is one card.
        listOf("en-US", "bn-BD", "zh-Hans-CN", "").forEach { tag ->
            val both = setOf("npu", "npu-turbo")
            val ordered = ModelTierCopy.orderedForLanguageTagFor(tag, both)
            assertEquals(
                "'$tag': not a permutation of pickableFor(both)",
                WhisperCatalog.pickableFor(both).map { it.id }.toSet(),
                ordered.toSet(),
            )
            assertEquals("'$tag': lost or duplicated a tier", 1, ordered.size)
            assertEquals(
                "'$tag': the steered tier does not lead",
                ModelTierCopy.steerIdForLanguageTagFor(tag, both),
                ordered.first(),
            )
        }
    }

    // -------------------------------------------------------- 4.3: one tier per device
    //
    // The spec's own acceptance list: "the offer-set truth table (capable x installed-state x
    // locale)". The steer and the three ordering keys are UNCHANGED IN BODY — what changed is the
    // list they order, which `WhisperCatalog.pickableFor` now narrows on a capable device. These
    // drive the composition end to end, which is what the two chooser surfaces actually perform.

    @Test fun the_offer_set_truth_table_capable_x_installed_x_locale() {
        // rows: offered gate answer, installed ids, locale -> the exact lineup, in order
        data class Row(
            val offered: Set<String>,
            val installed: Set<String>,
            val tag: String,
            val expected: List<String>,
        )
        val table = listOf(
            // ---- CAPABLE. Fresh install: ONE card, whatever the locale. The owner's ruling.
            Row(setOf("npu", "npu-turbo"), emptySet(), "en-US", listOf("npu-turbo")),
            Row(setOf("npu", "npu-turbo"), emptySet(), "bn-BD", listOf("npu-turbo")),
            Row(setOf("npu", "npu-turbo"), emptySet(), "zh-Hans-CN", listOf("npu-turbo")),
            Row(setOf("npu", "npu-turbo"), emptySet(), "", listOf("npu-turbo")),
            Row(setOf("npu-turbo"), emptySet(), "en-US", listOf("npu-turbo")),
            Row(setOf("npu-turbo"), emptySet(), "bn-BD", listOf("npu-turbo")),
            // ---- CAPABLE, with history. The card for a model the user already has survives.
            // 4.7: the CPU floor is `small-q8`; `multi` is RETIRED and asserted below as keeping
            // no card — the 4.6.0 production default, the tier the 4.3 recovery used to download.
            Row(setOf("npu", "npu-turbo"), setOf("small-q8"), "bn-BD", listOf("npu-turbo", "small-q8")),
            Row(setOf("npu", "npu-turbo"), setOf("small-q8"), "en-US", listOf("npu-turbo", "small-q8")),
            // 4.6: was setOf("pro") — `pro` is RETIRED, so an installed one keeps no card (the
            // eco/base/pro row below asserts exactly that). An installed INSTRUMENT keeps its card
            // like any other offered tier — 4.7: `ultra-q8`, the one instrument left.
            Row(setOf("npu", "npu-turbo"), setOf("ultra-q8"), "en-US", listOf("npu-turbo", "ultra-q8")),
            Row(setOf("npu", "npu-turbo"), setOf("ultra-q8"), "bn-BD", listOf("npu-turbo", "ultra-q8")),
            Row(setOf("npu", "npu-turbo"), setOf("npu"), "bn-BD", listOf("npu-turbo", "npu")),
            // 4.6: `pro` dropped out of both expectations — retired, so `!it.retired` filters it
            // before `alsoOfferedIds` is ever consulted — and the two locales answer IDENTICALLY,
            // because the language key has no English-only tier left to order against the steer.
            // 4.7: `multi` drops out the same way, on the same rule.
            Row(
                setOf("npu", "npu-turbo"), setOf("npu", "small-q8", "multi", "pro"), "bn-BD",
                listOf("npu-turbo", "npu", "small-q8"),
            ),
            Row(
                setOf("npu", "npu-turbo"), setOf("npu", "small-q8", "multi", "pro"), "en-US",
                listOf("npu-turbo", "npu", "small-q8"),
            ),
            // Turbo already installed: it is both the one offer and an existing install.
            Row(setOf("npu", "npu-turbo"), setOf("npu-turbo"), "en-US", listOf("npu-turbo")),
            // An installed RETIRED tier changes nothing — `!retired` runs first. 4.7: the four Q5
            // rungs join the set, and `multi` is the member that matters.
            Row(setOf("npu-turbo"), setOf("eco", "base", "pro"), "bn-BD", listOf("npu-turbo")),
            Row(setOf("npu-turbo"), setOf("multi", "medium-q5", "ultra", "large-v3"), "en-US", listOf("npu-turbo")),
            // ---- NOT CAPABLE. The rule is byte-identical to 3.7/4.1 and the installed state is
            // still irrelevant; 4.6 only made the list it orders longer (the whole ladder).
            Row(emptySet(), emptySet(), "en-US", ladderLineup),
            Row(emptySet(), emptySet(), "bn-BD", ladderLineup),
            Row(emptySet(), setOf("pro", "multi"), "bn-BD", ladderLineup),
            Row(emptySet(), setOf("npu", "npu-turbo"), "en-US", ladderLineup),
            // ---- CAPABLE FOR `npu` ONLY (no turbo row for this family). Unreachable on today's
            // census — every family carries both, pinned in NpuFleetCensusTest — but the rule
            // must still answer it, and its answer is the pre-4.3 one: turbo is what the ruling
            // is about, and a device that cannot be offered turbo keeps its menu.
            Row(setOf("npu"), emptySet(), "bn-BD", listOf("npu") + ladderLineup),
            Row(setOf("npu"), emptySet(), "en-US", listOf("npu") + ladderLineup),
            Row(setOf("npu"), setOf("npu"), "bn-BD", listOf("npu") + ladderLineup),
        )
        table.forEach { (offered, installed, tag, expected) ->
            assertEquals(
                "$offered/$installed/'$tag': the 4.3 offer-set row",
                expected,
                ModelTierCopy.orderedForLanguageTagFor(tag, offered, installed),
            )
            // The two calls every chooser makes must agree: the badged card is the head.
            assertEquals(
                "$offered/$installed/'$tag': the steer chip is not on the card that leads",
                expected.first(),
                ModelTierCopy.steerIdForLanguageTagFor(tag, offered),
            )
            // A permutation of what this device can pick — never a card invented or lost.
            assertEquals(
                "$offered/$installed/'$tag': not a permutation of pickableFor",
                WhisperCatalog.pickableFor(offered, installed).map { it.id }.toSet(),
                expected.toSet(),
            )
            expected.forEach {
                assertNotNull("'$it' does not resolve", WhisperCatalog.byId(it))
                assertNotNull("'$it' has no card copy", ModelTierCopy.forId(it))
            }
        }
    }

    @Test fun a_fresh_capable_install_sees_exactly_one_card_and_makes_no_comparison() {
        // The spec's device acceptance, stated as the assertion an owner session verifies: "a
        // fresh capable install sees exactly one model card and reaches dictation without a
        // choice". Fresh = nothing installed; capable = the census union names turbo, which on a
        // fresh capable Play install is the fetchable half alone (4.2 F6).
        listOf("en", "en-US", "en_GB", "bn", "bn-BD", "de-AT", "zh-Hans-CN", "fr-CA", "").forEach { tag ->
            listOf(setOf("npu-turbo"), setOf("npu", "npu-turbo")).forEach { offered ->
                val ordered = ModelTierCopy.orderedForLanguageTagFor(tag, offered, emptySet())
                assertEquals("'$tag'/$offered: more than one card on a fresh capable install", 1, ordered.size)
                assertEquals("'$tag'/$offered", "npu-turbo", ordered.single())
                assertEquals(
                    "'$tag'/$offered: and it wears the steer chip",
                    ordered.single(),
                    ModelTierCopy.steerIdForLanguageTagFor(tag, offered),
                )
                // The two tiers the owner named are the ones that must NOT be there.
                assertFalse("'$tag'/$offered: the 190 MB tier is visible", ordered.contains("multi"))
                assertFalse("'$tag'/$offered: the 190 MB English tier is visible", ordered.contains("pro"))
                assertFalse("'$tag'/$offered: the 358 MB tier is visible", ordered.contains("npu"))
            }
        }
    }

    // ---------------------------------------------------------------- the 4.0 gated tier
    //
    // npu is the first tier the census loops above could not have reached through `pickable`, so
    // 3.7's discipline is restated here explicitly against the owner-approved strings.

    @Test fun the_npu_headline_is_pinned_exactly_and_takes_a_position() {
        val copy = ModelTierCopy.forId("npu")!!
        assertEquals("Fastest multilingual", copy.headline)
        // The position is stated where the eye lands first, not buried in the body.
        assertTrue(
            "the npu headline takes no speed-vs-accuracy position",
            POSITION_WORDS.any { copy.headline.lowercase().contains(it) },
        )
    }

    @Test fun the_npu_badges_state_coverage_and_the_size_of_the_whole_pair() {
        val copy = ModelTierCopy.forId("npu")!!
        assertEquals(listOf("90+ languages", "358 MB"), copy.badges)
        // Not "English only" and not a bespoke wording: the SAME string every multilingual tier
        // carries, so the two cards are comparable at a glance.
        assertTrue(copy.badges.contains("90+ languages"))
        // 358 MB is the PAIR (encoder + decoder). A future edit that badges only the encoder's
        // 132 MB — or a catalog edit that changes the pair — fires here.
        val npu = WhisperCatalog.byId("npu")!!
        val statedMb = copy.badges.first { it.endsWith(" MB") }.removeSuffix(" MB").toInt()
        val expectedMb = (npu.approxBytes / 1_000_000L).toInt()
        assertTrue(
            "npu badge says $statedMb MB but the install is ~$expectedMb MB",
            kotlin.math.abs(statedMb - expectedMb) <= 5,
        )
        assertTrue("the badge must state the pair, not just the encoder", statedMb > npu.primaryBytes / 1_000_000L)
    }

    @Test fun no_offered_tiers_copy_compares_this_app_to_another_one() {
        // The claim rules: our own before/after is fair game, another product is not — nobody has
        // measured one. Universal, so it is a loop; npu is simply the first tier whose copy had a
        // reason to reach for a comparison at all.
        val crossApp = listOf(
            "other app", "any app", "every app", "any other", "than other", "competitor",
            "gboard", "google", "apple", "siri", "otter", "dragon", "whisperkit",
        )
        offeredTiers.forEach { model ->
            val copy = ModelTierCopy.forId(model.id)!!
            val all = (copy.headline + " " + copy.body + " " + copy.badges.joinToString(" ")).lowercase()
            crossApp.forEach { needle ->
                assertFalse("tier '${model.id}' copy compares this app to '$needle'", all.contains(needle))
            }
        }
    }

    @Test fun the_npu_body_is_our_own_tier_on_this_device_and_claims_no_absolute() {
        val copy = ModelTierCopy.forId("npu")!!
        assertEquals(
            "Runs on your phone's AI chip. Same model as Multilingual, much faster on this device.",
            copy.body,
        )
        // The comparison is OUR tier, and the claim is scoped to the hardware in the user's hand —
        // the two things that make "much faster" a statement someone could check.
        assertTrue(copy.body.contains("Multilingual"))
        assertTrue(copy.body.contains("this device"))
        // No absolutes, anywhere in the offered lineup. "Fastest multilingual" positions npu
        // within OUR lineup; "instant" or "real-time" would be a claim about the world.
        val absolutes = listOf(
            "instant", "real-time", "realtime", "no delay", "no lag", "zero lag",
            "guaranteed", "always", "never", "unlimited",
        )
        offeredTiers.forEach { model ->
            val c = ModelTierCopy.forId(model.id)!!
            val all = (c.headline + " " + c.body + " " + c.badges.joinToString(" ")).lowercase()
            absolutes.forEach { needle ->
                assertFalse("tier '${model.id}' copy makes the absolute claim '$needle'", all.contains(needle))
            }
        }
    }

    // ---------------------------------------------------------------- the 4.1 npu-turbo card
    //
    // The second tier the census loops could never have reached through `pickable`, so the same
    // discipline is restated against the owner-approved strings — mirroring the npu pins Q7a's
    // I5 established.

    @Test fun the_npu_turbo_headline_is_pinned_exactly_and_takes_a_position() {
        val copy = ModelTierCopy.forId("npu-turbo")!!
        // 4.1 shipped "Best quality, slower" (decision 8: accuracy unproved, so no speed claim).
        // The owner's A/B of 2026-08-29 proved the accuracy and the 2026-09 measurements proved
        // the speed (encode 1.78 s fixed vs Multilingual's 2.3 s per commit on the same phone),
        // and the owner ruled on 2026-09-10: "it's actually the fastest one we have and most
        // accurate". Both words are already in POSITION_WORDS, so the census passes without the
        // constant being edited to fit the copy — which would be the wrong way round.
        //
        // 4.6 T2: was "Best accuracy, fastest". The accuracy half went false the moment the ladder
        // OFFERED `large-v3` (full 32-layer decoder against turbo's 4), so it is now SCOPED to the
        // silicon it is true on. The speed half is byte-identical: it is measured on two devices
        // and owner-ruled, and `exactly_one_card_claims_the_top_of_the_accuracy_order` asserts
        // both halves of that — one unscoped claimant, and turbo's scoped claim still present.
        assertEquals("Best AI-chip accuracy, fastest", copy.headline)
        assertTrue(
            "the npu-turbo headline takes no speed-vs-accuracy position",
            POSITION_WORDS.any { copy.headline.lowercase().contains(it) },
        )
        // The word the owner ruled on, still on the card where the eye lands first.
        assertTrue(
            "the npu-turbo headline dropped the measured speed claim",
            copy.headline.lowercase().contains("fastest"),
        )
    }

    @Test fun the_npu_turbo_badges_state_coverage_and_the_size_of_the_whole_pair() {
        val copy = ModelTierCopy.forId("npu-turbo")!!
        assertEquals(listOf("90+ languages", "1072 MB"), copy.badges)
        // The SAME coverage string every multilingual tier carries, so the cards stay comparable
        // at a glance.
        assertTrue(copy.badges.contains("90+ languages"))
        // 1072 MB is the PAIR (encoder + decoder), within the census's ±5 MB of approxBytes...
        val turbo = WhisperCatalog.byId("npu-turbo")!!
        val statedMb = copy.badges.first { it.endsWith(" MB") }.removeSuffix(" MB").toInt()
        val expectedMb = (turbo.approxBytes / 1_000_000L).toInt()
        assertTrue(
            "npu-turbo badge says $statedMb MB but the install is ~$expectedMb MB",
            kotlin.math.abs(statedMb - expectedMb) <= 5,
        )
        // ...and STRICTLY greater than the encoder alone, so a future edit that badges only the
        // 776 MB primary fires here even before the approxBytes tolerance does.
        assertTrue(
            "the badge must state the pair, not just the encoder",
            statedMb > turbo.primaryBytes / 1_000_000L,
        )
    }

    @Test fun the_npu_turbo_body_names_our_own_visible_tier_and_scopes_both_claims_to_this_device() {
        val copy = ModelTierCopy.forId("npu-turbo")!!
        // 4.6 T2: "The most accurate model this app ships" became "The most accurate model that
        // runs there" — the same claim with its real subject restored. `large-v3` is now offered
        // and is more accurate than turbo, so the app-wide superlative was false; the AI-chip
        // scope is exactly what the owner's 2026-08-29 A/B compared, so nothing measured was lost.
        assertEquals(
            "Large-v3's own encoder, on your phone's AI chip. The most accurate model " +
                "that runs there, and the fastest on this device — ahead of the 190 MB " +
                "Multilingual model on both counts.",
            copy.body,
        )
        // The scope is a word, and the word has to be there: without "that runs there" the
        // sentence is the app-wide claim again.
        assertTrue(copy.body.contains("that runs there"))
        assertFalse(
            "the card claims the app-wide accuracy top again, and `large-v3` outranks it",
            copy.body.contains("most accurate model this app ships"),
        )
        // The comparison is OUR OWN tier — and one the user can SEE: since 4.3's one-tier-per-
        // device, "Multilingual on NPU" is never offered beside turbo, so naming it (as 4.1 did)
        // was a comparison to an invisible card. The 190 MB Multilingual model is what this user
        // would otherwise run. Both claims are measured on our own devices (encode 1.78 s fixed
        // vs 2.3 s per commit on the Fold6; the owner's 2026-08-29 accuracy A/B) and the speed
        // claim is scoped to "this device", like the npu card's — never an absolute.
        assertTrue(copy.body.contains("Multilingual"))
        assertFalse(
            "the comparison must be to a tier the user can see, not the hidden npu card",
            copy.body.contains("Multilingual on NPU"),
        )
        assertTrue(copy.body.contains("this device"))
        assertTrue(copy.body.contains("190 MB"))
        assertFalse(
            "the card no longer disclaims speed — the ruling and the measurements say it is the fastest",
            (copy.headline + " " + copy.body).lowercase().contains("slower"),
        )
    }

    // ------------------------------------------------- 4.6 T2 — THE ACCURACY ORDER
    //
    // Accuracy is the ONE axis these cards are entitled to rank, and the ladder's own comment
    // block says why: whisper's size ordering is a property of the checkpoints, not a prediction
    // about the owner's six phones. Ranking it costs nothing — as long as exactly ONE card claims
    // the top. 4.6 broke that by offering `large-v3`, whose complete 32-layer decoder outranks
    // the turbo the NPU card had been calling "the most accurate model this app ships" since 4.1.

    /**
     * **Two cards may not both claim the top of the accuracy order.** The census walks every
     * sentence of every offered card, finds the ones that pair a superlative with an accuracy
     * word, and demands that each belongs either to the single lineup-wide claimant or to a
     * sentence that NAMES THE SCOPE it is true within.
     *
     * That shape is the fix, rather than deleting turbo's accuracy claim. The claim is TRUE of the
     * silicon it is about — `npu` and `npu-turbo` are the only two models that run on the AI chip
     * and turbo is the more accurate of them — and the owner ruled on it (2026-09-10, *"it's
     * actually the fastest one we have and most accurate"*). What `large-v3` falsified is only its
     * SCOPE. So the card keeps the claim and states where it holds, and `large-v3` carries the
     * unscoped one. Deleting a measured claim would have been the regression.
     *
     * **"On this device" is deliberately NOT a scope marker here.** It scopes a SPEED claim, which
     * is a fact about hardware; an accuracy claim is a fact about the checkpoint, so the only
     * scope that narrows it honestly is the set of models it is being ranked against — which is
     * what "on the AI chip" / "that runs there" names.
     *
     * Deliberately NOT asserted: that the claimant is derivably the most accurate row. **Byte
     * order is not accuracy order on this ladder** — `medium-q8` (823 MB) is larger than `ultra`
     * (574 MB) and less accurate than it, because one is whisper medium and the other is
     * large-v3's encoder — so there is nothing in the catalogue to derive the ranking from, and a
     * proxy that agrees today would be a worse test than a pinned id plus this census. The defect
     * class guarded here is the one that actually happened: a SECOND card claiming the same top.
     */
    @Test fun exactly_one_card_claims_the_top_of_the_accuracy_order() {
        val claimants = offeredTiers
            .filter { model ->
                sentencesOf(ModelTierCopy.forId(model.id)!!).any { isUnscopedAccuracyTopClaim(it) }
            }
            .map { it.id }
        assertEquals(
            "the top of the accuracy order is claimed by $claimants. Exactly one card may claim " +
                "it, and every other accuracy superlative must name the scope it holds within — " +
                "4.1's turbo card said 'the most accurate model this app ships', which 4.6's " +
                "large-v3 rung made false. 4.7: large-v3 is retired, so the unscoped claim moved " +
                "to ultra-q8 — large-v3-turbo, the most accurate model still on the ladder",
            listOf("ultra-q8"),
            claimants,
        )
        assertTrue("the claimant must be pickable, or the claim is about a card nobody sees", WhisperCatalog.pickable.any { it.id == "ultra-q8" })
        // The other half of the same rule: turbo's measured accuracy claim is STILL THERE, and
        // scoped. A future pass that scrubs superlatives app-wide would take a true, owner-ruled
        // claim with it, and this line is what stops that being silent.
        assertTrue(
            "npu-turbo no longer claims the accuracy it measured — the claim must survive, " +
                "scoped to the silicon it is true on",
            sentencesOf(ModelTierCopy.forId("npu-turbo")!!).any { s ->
                SUPERLATIVE.containsMatchIn(s) &&
                    ACCURACY_WORD.containsMatchIn(s) &&
                    ACCURACY_SCOPE_MARKERS.any { s.contains(it) }
            },
        )
    }

    @Test fun no_two_offered_tiers_share_a_headline() {
        // New with the second NPU card: the lineup now holds two tiers a user must tell apart at
        // a glance, and the headline is the glance. A copy-paste that leaves two cards reading
        // identically is exactly the mutation this census exists for.
        val headlines = offeredTiers.map { ModelTierCopy.forId(it.id)!!.headline }
        assertEquals(
            "two offered tiers share a headline: $headlines",
            headlines.size,
            headlines.toSet().size,
        )
    }

    private companion object {
        /** The 3.7 census's position vocabulary, shared so the npu pin cannot drift from the loop. */
        val POSITION_WORDS = listOf("fastest", "fast", "slower", "accuracy")

        /**
         * **4.6 T2 — the vocabulary a CPU rung may not use, in EITHER direction.** A slow claim
         * is as unearned as a fast one: in 4.6 nothing on the ladder was measured; since 4.7 the
         * three Q8 rungs are measured on ONE tablet and never against one another on the user's
         * device. A card that ranks the winner — or the loser — is a claim the owner has to catch
         * instead of a finding a user can check. A MEASURED verdict scoped to the tablet ("kept
         * up with margin on the owner's tablet") uses none of these words, by design.
         *
         * Every entry is matched WORD-ANCHORED, which is what lets the list keep "fast" without
         * failing "breakfast" and "slow" without failing "smallest".
         *
         * Three things are deliberately absent, and each absence is a decision:
         *
         *  * **"throughput" and "latency"** — neutral nouns. 4.6's `small-q8` card said *"its
         *    throughput on this device is unknown"*, the honest shape for an unmeasured rung and
         *    the opposite of a claim. Banning the noun would have forced the card into silence.
         *  * **"keep up"** — [ModelTierCopy.KEEP_UP_NOTE]'s own words. "May not keep up" is a
         *    hedged failure MODE, which is what the brief asks the heavy rungs to state; it is not
         *    a rank against another rung. "Kept up with margin" is its measured past tense.
         *  * **"turbo"** — the upstream model's NAME (`ggml-large-v3-turbo-q5_0.bin`), carried by
         *    `ultra`'s displayName and body. Renaming someone else's checkpoint to dodge a word
         *    census would make the cards harder to match to the files they fetch.
         */
        /**
         * A displayName that ends in `(family, QUANT)` — the shape 4.6 gave every ggml rung so
         * that two cards for the same weights are distinguishable on the card itself. The npu
         * rows' names (`Multilingual on NPU (small)`) carry no quantisation token and therefore
         * match nothing here, which is correct: their w8a16 conversion is not one of these.
         */
        val QUANT_IN_NAME = Regex("^(.*)\\((.+), (Q\\d_\\d)\\)$")

        /** The quantisation token a card's own displayName states, or null if it states none. */
        fun quantOf(model: WhisperModel): String? =
            QUANT_IN_NAME.matchEntire(model.displayName)?.groupValues?.get(3)

        /**
         * The row that is THE SAME MODEL as this one at a different quantisation — derived from
         * the displayNames rather than from a pair table, so a twin cannot be declared in the
         * test and absent from the catalogue. Two rows are twins when their names agree on
         * everything but the quantisation token AND the catalogue agrees they are the same
         * model (same mel width, same language scope).
         */
        fun twinOf(model: WhisperModel): WhisperModel? {
            val mine = QUANT_IN_NAME.matchEntire(model.displayName) ?: return null
            return WhisperCatalog.entries.firstOrNull { other ->
                if (other.id == model.id) return@firstOrNull false
                val theirs = QUANT_IN_NAME.matchEntire(other.displayName) ?: return@firstOrNull false
                theirs.groupValues[1] == mine.groupValues[1] &&
                    theirs.groupValues[2] == mine.groupValues[2] &&
                    theirs.groupValues[3] != mine.groupValues[3] &&
                    other.melBins == model.melBins &&
                    other.scope == model.scope
            }
        }

        val SPEED_CLAIM_WORDS = listOf(
            "fast", "faster", "fastest", "quick", "quicker", "quickest", "quickly",
            "speedy", "snappy", "swift", "swifter", "rapid", "rapidly",
            "instant", "instantly", "real-time", "realtime", "sooner", "responsive",
            "slow", "slower", "slowest", "slowly", "sluggish", "laggy",
        )

        /**
         * A card's claims, one per sentence — the headline plus the body split at sentence ends,
         * lowercased. Per-SENTENCE and not per-card on purpose: a scope named in one sentence does
         * not license an unscoped superlative in the next one, and turbo's card is exactly that
         * shape (the AI chip is named in its first sentence, the accuracy claim lives in its
         * second, and the second has to carry its own scope).
         */
        fun sentencesOf(copy: ModelTierCopy.TierCopy): List<String> =
            (listOf(copy.headline) + copy.body.split(". ")).map { it.lowercase() }

        /** Superlative forms only — a COMPARATIVE ("sharper accuracy") claims no top. */
        val SUPERLATIVE = Regex("\\b(best|highest|most|sharpest|top)\\b")
        val ACCURACY_WORD = Regex("\\b(accurate|accuracy|quality|sharp|sharper|sharpest)\\b")

        /**
         * The scopes an accuracy superlative may be narrowed by: the SET OF MODELS it is ranked
         * against. "On this device" is not one of them — see the census's own KDoc.
         */
        val ACCURACY_SCOPE_MARKERS = listOf("ai chip", "ai-chip", "runs there", "on the npu")

        fun isUnscopedAccuracyTopClaim(sentence: String): Boolean =
            SUPERLATIVE.containsMatchIn(sentence) &&
                ACCURACY_WORD.containsMatchIn(sentence) &&
                ACCURACY_SCOPE_MARKERS.none { sentence.contains(it) }
    }
}
