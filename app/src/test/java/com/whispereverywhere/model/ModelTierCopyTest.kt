package com.whispereverywhere.model

import com.whispereverywhere.npu.NpuFleetCensus
import com.whispereverywhere.npu.NpuSocFamily
import com.whispereverywhere.npu.NpuVendor
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
        // The three Q8 cards, pinned exactly. 4.9.1 — THE BODIES ARE PLAIN (owner, 2026-09-17:
        // "It's too technical for people that don't know anything about it. Our headlines are
        // perfectly fine, and just about everything else doesn't need to be shown"): one to
        // three short sentences, and every measurement, twin fact and dated report the old
        // bodies carried is in the KDoc beside its card — `the_evidence_lives_in_the_kdoc_
        // beside_each_card` reads the source and pins that nothing was lost.
        //
        // 4.9 — THE HEADLINES ARE THE OWNER'S WORDS (2026-09-17: "For small, we say fast —
        // fastest, less accurate. Medium: balanced speed and accuracy. V3 turbo: highest
        // accuracy, slightly slower than both other tiers."), with ONE amended by controller
        // ruling: turbo's "slightly" is not a word the doc supports (the doc's medians: 4,849 ms
        // per commit is 3.6× medium's 1,341 and 4.0× small's 1,217 — over three times either;
        // his own reported drain on turbo was six to nine seconds, against the doc's 1.2-1.3 s
        // per-commit medians for the other two — the doc's figure, not one he reported), so the
        // card says "slower than the other two"; his report is in the KDoc beside turbo's card
        // as his report, dated, on his tablet. Read together they are a ladder: fastest /
        // balanced / highest accuracy.
        assertEquals("Fastest, less accurate", ModelTierCopy.forId("small-q8")!!.headline)
        // Small's last sentence is "Fits every device", not "Recommended on every device" (4.9
        // review): over the 4.5 GB gate the steer is medium, so on that device small renders one
        // card under "Our pick" — a body calling itself the recommendation there contradicts
        // the chip above it. The RAM floor is a fit (small's is 0, so it fits every device); the
        // recommendation is the steer's chip alone, the same rule medium's and turbo's "Needs
        // at least" sentences follow.
        assertEquals(
            "The light one. Quick to respond and fine for everyday notes. Fits every device.",
            ModelTierCopy.forId("small-q8")!!.body,
        )
        assertTrue(ModelTierCopy.forId("small-q8")!!.body.endsWith("Fits every device."))
        assertFalse(
            "no CPU card may call itself the recommendation — that is the steer chip's word",
            ModelTierCopy.forId("small-q8")!!.body.contains("Recommended"),
        )
        assertEquals("Balanced speed and accuracy", ModelTierCopy.forId("medium-q8")!!.headline)
        // Medium's body, word by word: "more accurate than the light one" is what whisper's
        // size order earns (no transcript comparison exists to earn "much"); "still quick" is
        // the plain reading of the doc's 10%, no rank; "Needs at least" is a RAM fit, because
        // the recommendation is the steer's "Our pick".
        assertEquals(
            "The all-rounder. More accurate than the light one and still quick. " +
                "Needs at least 4.5 GB of memory.",
            ModelTierCopy.forId("medium-q8")!!.body,
        )
        assertEquals("Highest accuracy, slower than the other two", ModelTierCopy.forId("ultra-q8")!!.headline)
        assertEquals(
            "The most accurate one. It takes longer to catch up after you stop talking, " +
                "so give it a moment. Needs at least 4.5 GB of memory.",
            ModelTierCopy.forId("ultra-q8")!!.body,
        )
        // Neither RAM-floored card RECOMMENDS itself on RAM: the floor is a fit ("Needs at
        // least"), and turbo's own body tells the user to expect a wait — a card cannot say
        // that and "Recommended" in one breath. The recommendation is the steer's chip alone.
        listOf("medium-q8", "ultra-q8").forEach {
            val body = ModelTierCopy.forId(it)!!.body
            assertTrue("'$it' states its RAM floor as a fit, in the user's units", body.endsWith("Needs at least 4.5 GB of memory."))
            assertFalse("'$it' recommends itself on RAM", body.lowercase().contains("recommended"))
        }
        // The three headlines read as a ladder: each names its axis in the owner's vocabulary.
        assertTrue(ModelTierCopy.forId("small-q8")!!.headline.startsWith("Fastest"))
        assertTrue(ModelTierCopy.forId("medium-q8")!!.headline.startsWith("Balanced"))
        assertTrue(ModelTierCopy.forId("ultra-q8")!!.headline.startsWith("Highest accuracy"))
        assertFalse(
            "turbo's card may not say 'slightly' — the doc's medians are 3.6× medium and 4.0× " +
                "small per commit, and the owner's own reported drain was six to nine seconds " +
                "(against the doc's 1.2-1.3 s medians for the other two); restoring the word is " +
                "his call, one word",
            ModelTierCopy.forId("ultra-q8")!!.headline.lowercase().contains("slightly"),
        )
        // 4.9.1: no body cites the measurement any more — the tablet, the date and the three
        // medians are in the KDoc beside each card (`the_evidence_lives_in_the_kdoc_beside_
        // each_card` pins them there, doc path and number), and the body says none of it.
        // Turbo's KDoc ALSO carries the owner's dated report, as his report; the body carries
        // its plain-words consequence.
        listOf("small-q8", "medium-q8", "ultra-q8").forEach {
            val body = ModelTierCopy.forId(it)!!.body
            assertFalse("'$it' names the tablet — that scope lives in the KDoc now", body.contains("tablet"))
            assertFalse("'$it' dates a measurement — that lives in the KDoc now", Regex("\\d{4}-\\d{2}-\\d{2}").containsMatchIn(body))
        }
        val turbo = ModelTierCopy.forId("ultra-q8")!!.body
        assertTrue("turbo's body tells the user, plainly, to expect a wait after speech", turbo.contains("takes longer to catch up") && turbo.contains("give it a moment"))
        // 4.9: no card says "offered for measurement", "instrument" or "not recommended" — the
        // ladder ships, and the RAM badge is what says whether a device can carry a rung.
        listOf("small-q8", "medium-q8", "ultra-q8").forEach {
            val all = (ModelTierCopy.forId(it)!!.headline + " " + ModelTierCopy.forId(it)!!.body).lowercase()
            listOf("instrument", "for measurement", "not recommended", "offered for").forEach { phrase ->
                assertFalse("'$it' still carries the retired sentence <<$phrase>>", all.contains(phrase))
            }
        }
        // 4.8.0: the owner's 4.5 GB (was 4.7's provisional 5.5 GB), on the row and on the card
        // — and since 4.9 on turbo's card too, at its own floor.
        assertEquals(4_500_000_000L, WhisperCatalog.byId("medium-q8")!!.minRamBytes)
        assertTrue(ModelTierCopy.forId("medium-q8")!!.body.contains("at least 4.5 GB"))
        assertFalse(ModelTierCopy.forId("medium-q8")!!.body.contains("5.5 GB"))
        assertEquals(4_500_000_000L, WhisperCatalog.byId("ultra-q8")!!.minRamBytes)
        assertTrue(ModelTierCopy.forId("ultra-q8")!!.body.contains("at least 4.5 GB"))
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
    //   1. A CPU rung may rank its siblings by speed ONLY in its headline, as measured on the
    //      tablet, with the measurement cited in the KDoc beside the card (4.9 allowed the rank
    //      with the scope on the card; 4.9.1 moved the scope into the KDoc on the owner's
    //      plain-copy ruling; until 4.9 no rank at all — the three Q8 rungs were not measured
    //      against one another on the user's device, and still have not been). A CPU BODY ranks
    //      nothing by speed: a plain speed word at most, never a superlative. What stays
    //      forbidden is the ABSOLUTE: a speed word about every device, or about the device in
    //      the user's hand. The two NPU cards KEEP their measured, owner-ruled "fastest on this
    //      device": that is a true claim and removing it would be the regression, so the census
    //      asserts BOTH halves.
    //   2. Every rung whose verdict does not clear warns, plainly, that it may not keep up with
    //      continuous speech, and names the remedy; a rung whose verdict clears does not carry
    //      the note. Since 4.9 no rung's verdict fails to clear, so the note renders nowhere.
    //   3. A q8_0 rung still carries its quantisation in its NAME (the displayName above the
    //      headline), and its retired q5 twin is named in the KDoc beside the card, because the
    //      twins are installed on the devices of everyone who tried one on the internal track.
    //      Since 4.9.1 the BODY carries no technical token at all (the census below).

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
     * **A CPU RUNG RANKS SPEED ONLY IN ITS HEADLINE, WITH THE MEASUREMENT CITED BESIDE THE CARD —
     * AND THE TWO NPU CARDS STILL CLAIM THEIRS.** One test for both halves, on purpose: they are
     * the same rule seen from its two ends, and a census that only forbade would be satisfied by
     * scrubbing the lineup silent — which would delete two claims that are measured, owner-ruled
     * and true.
     *
     * **The forbidding half, AMENDED in 4.9 and RESTATED in 4.9.1, never deleted.** From 4.6 to
     * 4.8 no CPU card could use a speed word at all: the three Q8 rungs were measured on ONE
     * tablet and never against one another on the user's device, so a rank in either direction
     * was a prediction. 4.9 allowed exactly one thing more, on the owner's ruling that the tiers
     * be labelled as a ladder ("fastest, less accurate" / "balanced speed and accuracy" /
     * "highest accuracy, slower"): a RANK AMONG THE THREE, in the headline, when the card named
     * the device it was measured on and the date. 4.9.1 (the owner's plain-copy ruling) keeps
     * the headline rank and moves the scope OFF the card into the KDoc beside it — which
     * [the_evidence_lives_in_the_kdoc_beside_each_card] reads and pins — so the BODY may now
     * carry a plain speed word ("quick") but never a superlative (that would be a second rank,
     * unscoped where the user reads it), and never in the same sentence as an absolute scope.
     * What stays forbidden, and is the reason the rule exists: the ABSOLUTE — "fastest" about
     * every device, or about the device in the user's hand ("this device"), which the app has
     * not measured. The absolute is a SHAPE, [ABSOLUTE_SCOPE] — any quantifier or deictic before
     * a device noun, any bare plural device noun, "everywhere" — not a phrase list, because the
     * list this test carried until the 4.9.1 review passed "Quick on any phone"; and the rank is
     * [speedSuperlativeIn], which knows "the most responsive" as well as "fastest".
     * [the_cpu_body_guard_catches_the_phrasings_its_kdoc_forbids] pins both against probes. See
     * [SPEED_CLAIM_WORDS] for the vocabulary and for the three words that are deliberately not
     * in it.
     *
     * **The requiring half.** `npu` and `npu-turbo` are gated tiers whose speed was measured on
     * our own devices (encode 1.78 s fixed per commit on the Fold6 against Multilingual's 2.3 s;
     * ~6 s per 17.6 s chunk on the Tab S10+) and ruled on by the owner (2026-09-10). Their cards
     * must go on saying so. This is the assertion that makes a future "scrub every speed word"
     * pass fail loudly instead of quietly costing the app a true claim.
     */
    @Test fun a_cpu_rung_ranks_speed_only_in_its_headline_never_absolutely_and_the_two_npu_cards_still_claim_theirs() {
        val cpuRungs = offeredTiers.filter { !it.gated }
        // Guard the census's own reach: if the ladder ever loses its CPU rows, this test must not
        // pass by iterating nothing.
        assertEquals("the CPU ladder is not three rungs any more", 3, cpuRungs.size)
        cpuRungs.forEach { model ->
            val copy = ModelTierCopy.forId(model.id)!!
            // The displayName rides on the same card (OnboardingModelScreen.kt:686), above the
            // headline, so it is part of what the user reads as this rung's claim — and it may
            // never carry a speed word (it names the model and its quantisation, nothing else).
            SPEED_CLAIM_WORDS.forEach { word ->
                assertFalse(
                    "CPU rung '${model.id}' claims speed in its NAME with '$word'",
                    Regex("\\b" + Regex.escape(word) + "\\b").containsMatchIn(model.displayName.lowercase()),
                )
                copy.badges.forEach { badge ->
                    assertFalse("CPU rung '${model.id}' claims speed in a badge: '$badge'", Regex("\\b" + Regex.escape(word) + "\\b").containsMatchIn(badge.lowercase()))
                }
            }
            // The body: a plain speed word is legal; a SUPERLATIVE is a rank, and the only rank a
            // CPU card may state is the headline's, backed by the KDoc's citation.
            val bodySentences = copy.body.split(". ").map { it.lowercase() }
            bodySentences.forEach { sentence ->
                assertNull(
                    "CPU rung '${model.id}' ranks speed with '${speedSuperlativeIn(sentence)}' in its BODY: " +
                        "<<$sentence>>. The rank is the headline's, and the measurement behind it is " +
                        "cited in the KDoc beside the card, not on it",
                    speedSuperlativeIn(sentence),
                )
                // And NEVER the absolute, in any sentence: a speed word beside "every device",
                // "any phone", "phones" or "this device" is a claim about hardware nobody has
                // measured. The scope is a SHAPE ([ABSOLUTE_SCOPE]), not a list of phrases —
                // `the_cpu_body_guard_catches_the_phrasings_its_kdoc_forbids` pins its reach.
                assertFalse(
                    "CPU rung '${model.id}' makes an absolute speed claim: <<$sentence>> pairs a " +
                        "speed word with '${ABSOLUTE_SCOPE.find(sentence)?.value}'",
                    isAbsoluteSpeedClaim(sentence),
                )
            }
            // The headline's rank is legal because it is pinned exactly and the KDoc beside the
            // card cites the measurement (`the_evidence_lives_in_the_kdoc_beside_each_card`) —
            // and it may not itself claim a scope it has not earned.
            assertFalse(
                "CPU rung '${model.id}' headline claims '${ABSOLUTE_SCOPE.find(copy.headline.lowercase())?.value}'",
                ABSOLUTE_SCOPE.containsMatchIn(copy.headline.lowercase()),
            )
        }
        // The rank the three headlines state is the DOC'S order (1,217 < 1,341 < 4,849 ms per
        // commit, docs/measurements/2026-09-17-tab-cpu-ladder.md): small is the one card that
        // says "fastest", turbo the one that says "slower", medium neither.
        assertTrue(ModelTierCopy.forId("small-q8")!!.headline.lowercase().contains("fastest"))
        assertFalse(ModelTierCopy.forId("medium-q8")!!.headline.lowercase().let { h -> listOf("fastest", "slower", "faster", "slow").any { Regex("\\b$it\\b").containsMatchIn(h) } })
        assertTrue(ModelTierCopy.forId("ultra-q8")!!.headline.lowercase().contains("slower"))
        assertEquals(
            "exactly one CPU card may call itself the fastest of the three",
            listOf("small-q8"),
            cpuRungs.filter { Regex("\\bfastest\\b").containsMatchIn(ModelTierCopy.forId(it.id)!!.headline.lowercase()) }.map { it.id },
        )
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
     * **The guard above reaches as far as its own KDoc says it does.** The 4.9.1 review found
     * that after the tablet-scope requirement left the CPU bodies (correctly — the scope moved
     * into the KDoc beside each card) the absolute-scope check was a fixed eight-phrase list, and
     * every one of these probes shipped green through it while the rule's KDoc promised they
     * were forbidden. So the predicates are pinned against the probes themselves — the census's
     * reach is under test, not only the five bodies that happen to be on the ladder today.
     */
    @Test fun the_cpu_body_guard_catches_the_phrasings_its_kdoc_forbids() {
        // Absolute scopes: a speed word beside any way of saying "on hardware in general" or "on
        // the hardware in your hand".
        listOf(
            "quick on any phone", "fast on every phone", "fast on all phones", "snappy on phones",
            "quick on most devices", "responsive on this tablet", "fast everywhere", "quick anywhere",
            "quick on your phone", "fast on a phone", "quick enough for the phone", "snappy on this device",
            "fast on every device", "quick on any device", "fast on all devices", "quick on your device",
        ).forEach { probe ->
            assertTrue("<<$probe>> is an absolute speed claim and must be caught", isAbsoluteSpeedClaim(probe))
        }
        // Superlatives: the one-word forms and the "most X" shape that dodges them.
        listOf("the most responsive one", "the fastest", "the quickest of the three", "least sluggish", "the most rapid", "the swiftest").forEach { probe ->
            assertNotNull("<<$probe>> ranks speed and must be caught", speedSuperlativeIn(probe))
        }
        // And the owner's plain copy passes: a speed word with no scope and no rank, a scope
        // with no speed word, and a wait described without a speed word at all.
        listOf(
            "quick to respond and fine for everyday notes",
            "more accurate than the light one and still quick",
            "fits every device",
            "it takes longer to catch up after you stop talking, so give it a moment",
            "needs at least 4.5 gb of memory.",
        ).forEach { probe ->
            assertFalse("<<$probe>> is the owner's copy and makes no absolute speed claim", isAbsoluteSpeedClaim(probe))
            assertNull("<<$probe>> is the owner's copy and ranks nothing", speedSuperlativeIn(probe))
        }
        // The probe in the brief — "fastest on any phone" on a CPU card — is caught TWICE, by
        // the rank and by the scope, so neither check is carrying the other.
        assertNotNull(speedSuperlativeIn("fastest on any phone"))
        assertTrue(isAbsoluteSpeedClaim("fastest on any phone"))
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
     * rung, which said "throughput unknown" instead). 4.7 — the instrument set was one rung and
     * it warned: `ultra-q8` kept up on the owner's flagship tablet with no margin
     * (`KeepUp.KEPT_UP_WITHOUT_MARGIN`). 4.9 — **the instrument set is EMPTY and the note renders
     * on no card.** The owner's ruling of 2026-09-17 cleared turbo for production (recorded beside
     * its measurement in `TierThroughputRecord`), so by the coupling rule it is no longer an
     * instrument; the rule here is unchanged — every instrument ends with the note, nothing with a
     * clearing verdict carries it — and over an empty set its first half is vacuous, so this test
     * ALSO holds that turbo's card still states the measured shape of its risk in its own words:
     * the doc's "no margin", the fall-behind caution and the remedy. A rung that clears on a
     * ruling rather than a margin is still a rung the user is owed that sentence about.
     *
     * Nothing with a clearing verdict carries the NOTE: a caution on a measured pass would train
     * the user to ignore the caution. (`medium-q8` and `ultra-q8` on a device under their RAM
     * floor get the picker's own "High-end devices only" line instead, which is a statement about
     * RAM and is true.)
     */
    @Test fun every_instrument_warns_and_nothing_with_a_clearing_verdict_does() {
        val note = ModelTierCopy.KEEP_UP_NOTE
        val instruments = WhisperCatalog.entries.filter { it.instrument }
        assertEquals("the instrument set is not empty any more — the new rung must end with the note", emptyList<String>(), instruments.map { it.id })

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
        }

        (offeredTiers - instruments.toSet()).forEach { model ->
            assertFalse(
                "'${model.id}' has a clearing verdict and still carries the keep-up warning — a " +
                    "caution on a measured pass teaches the user to ignore cautions",
                ModelTierCopy.forId(model.id)!!.body.contains(note),
            )
        }

        // The rung that clears by RULING and not by margin still states the risk — in PLAIN
        // words on the card since 4.9.1, and in the doc's words in the KDoc beside it. "Kept up
        // with no margin" is the doc's finding (docs/measurements/2026-09-17-tab-cpu-ladder.md,
        // 0.99 of the floor at the worst commit); "it takes longer to catch up after you stop
        // talking, so give it a moment" is what 0.99 and the owner's six-to-nine-second drain
        // feel like to the user; the remedy — a smaller Whisper, which is `medium-q8` or
        // `small-q8`, both offered one card up — is in the KDoc, with the finding.
        val turbo = ModelTierCopy.forId("ultra-q8")!!.body
        assertTrue("turbo must tell the user to expect a wait after speech", turbo.contains("takes longer to catch up after you stop talking"))
        assertTrue("...and that the wait is the shape of it, not a fault", turbo.contains("give it a moment"))
        val turboKdoc = cardSource("ultra-q8")
        assertTrue("turbo's KDoc must keep the doc's 'no margin' finding", turboKdoc.contains("no margin"))
        assertTrue("turbo's KDoc must keep the fall-behind caution", turboKdoc.contains("expect the typed text to fall behind"))
        assertTrue("turbo's KDoc must keep the remedy's axis — a smaller Whisper", turboKdoc.contains("a smaller Whisper is the fix"))
        assertFalse("turbo is no instrument and does not carry the note itself", turbo.contains(note))
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
            // 4.9.1: the token is on the card in its NAME (the displayName rides above the
            // headline on both surfaces), and the body — plain words only — no longer repeats
            // it. The user comparing "the one I had" with this one still has the token to read.
            assertEquals("'${q8.id}' NAME does not carry its own quantisation", "Q8_0", quantOf(q8))
            assertFalse("'${q8.id}' body carries a quantisation token — plain words only since 4.9.1", copy.body.contains("Q8"))
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
        // `small-q8`'s KDoc names the retired twin by SIZE and quantisation ("the retired 190 MB
        // Q5_1 model" — the body's own words until 4.9.1), because that is the model every 4.6.0
        // production user is on and "the same weights" is the sentence a reader of the code
        // needs to recognise the 264 MB card as that model. This is the one stated exception to
        // the header rule in ModelTierCopy (a retired tier named by size, never by id — the id
        // is forbidden three assertions up). The plain body says "The light one." and nothing
        // about the twin.
        val small = ModelTierCopy.forId("small-q8")!!.body
        val smallKdoc = cardSource("small-q8")
        assertTrue(smallKdoc.contains("190 MB") && smallKdoc.contains("Q5_1") && smallKdoc.contains("retired"))
        assertTrue(smallKdoc.contains("the same weights"))
        assertTrue(small.startsWith("The light one."))
        // What no card may say is that two quantisations transcribe alike. The measurement doc
        // records wallMs only, nothing in the repo compares Q8_0 and Q5_1 (or w8a16) transcripts,
        // and the owner has named that comparison as the open gate ("prove the accuracy of the
        // small and medium model"). A card that announces a result the accuracy pass has not
        // produced is the defect the review of 138be0b found; this pins its absence — on
        // small's card and on `npu`'s, whose KDoc carries the same-weights fact (the card itself
        // names no other model since the 4.9.1 review — see the block beside it) and which must
        // never say "the same accuracy".
        listOf("small-q8", "npu").forEach { id ->
            assertFalse(
                "$id's card claims an accuracy equivalence no measurement supports",
                Regex("accuracy (matches|is the same|is identical|equals)|same accuracy").containsMatchIn(ModelTierCopy.forId(id)!!.body.lowercase()),
            )
        }
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
     * threshold"). **4.9: `ultra-q8` is the second such rung** — cleared by the owner's recorded
     * ruling, no longer an instrument, and carrying a floor of its own constant (equal to
     * medium's today, so he can raise turbo's alone). The default stays at 0 — the floor for
     * every device — so no phone reads a chooser with nothing recommended on it.
     */
    @Test fun no_instrument_is_ram_gated_and_the_two_thresholds_are_on_measured_rungs() {
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
        // The two offered rungs with a threshold, and both are measured — neither an instrument.
        assertEquals(
            listOf("medium-q8", "ultra-q8"),
            offeredTiers.filter { !it.gated && it.minRamBytes > 0L }.map { it.id },
        )
        assertFalse(WhisperCatalog.byId("medium-q8")!!.instrument)
        assertFalse(WhisperCatalog.byId("ultra-q8")!!.instrument)
        assertEquals(4_500_000_000L, WhisperCatalog.byId("medium-q8")!!.minRamBytes)
        assertEquals(WhisperCatalog.MEDIUM_Q8_MIN_RAM_BYTES, WhisperCatalog.byId("medium-q8")!!.minRamBytes)
        assertEquals(WhisperCatalog.ULTRA_Q8_MIN_RAM_BYTES, WhisperCatalog.byId("ultra-q8")!!.minRamBytes)
        // ...and each card states its threshold in the user's units, so the screen's RAM line and
        // the card's own sentence agree about why.
        assertTrue(ModelTierCopy.forId("medium-q8")!!.body.contains("4.5 GB"))
        assertTrue(ModelTierCopy.forId("ultra-q8")!!.body.contains("4.5 GB"))
        // 4.8.0: the number is the owner's, not the retired `extreme` precedent's any more —
        // that row is still resolvable and still 5.5e9, and the two no longer agree, which is
        // the point: medium-q8's threshold was RULED, not inherited.
        assertTrue(WhisperCatalog.byId("extreme")!!.retired)
        assertEquals(5_500_000_000L, WhisperCatalog.byId("extreme")!!.minRamBytes)
        assertTrue(WhisperCatalog.byId("extreme")!!.minRamBytes != WhisperCatalog.byId("medium-q8")!!.minRamBytes)
    }

    /**
     * 4.6 — was `english_locales_are_steered_to_pro`. The English branch is GONE because the tier
     * it pointed at is retired (owner ruling 2026-09-13: multilingual rungs only), and a steer at
     * a retired tier is not a steer — the chooser does not render that card, so nothing would be
     * lifted to the front and the steer chip would sit on no card at all for every English user.
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

    @Test fun the_steer_badge_names_no_reason_because_language_is_not_one() {
        // 4.8.0: the guided flow's steer is OnboardingLogic.firstRunSteer — medium over the RAM
        // gate, small under it, the chip's tier on a capable device — and since the round after,
        // so is the Settings picker's. Language decides none of those, so the ONE chip must not
        // say "language" (the retired "Best match for your language" is deleted, not kept: no
        // surface could truthfully show it since 4.6); and it must not say "device" either,
        // because the green RAM chip ("Fits your device" — a RAM fit, on every rung whose
        // floor the device meets) already does, with a reason the user can check. Nor may it
        // say "recommended": the RAM chip is not a recommendation, and this one is the only
        // chip that is. Reason-neutral, and distinct from both.
        assertEquals("Our pick", ModelTierCopy.FIRST_RUN_STEER_BADGE)
        listOf("language", "device", "faster", "fastest", "quicker", "instant", "recommended").forEach {
            assertFalse(
                "the first-run chip claims <<$it>>",
                ModelTierCopy.FIRST_RUN_STEER_BADGE.lowercase().contains(it),
            )
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
        // two ever disagree, the lineup leads with one card while the steer chip ("Our pick")
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
        // 338 MB since 4.15: the v0.63.0 pair (113,123,776 + 225,298,736). It read 358 MB for the
        // 4.0-4.14 pair, and the tolerance check below is what forced the move.
        assertEquals(listOf("90+ languages", "338 MB"), copy.badges)
        // Not "English only" and not a bespoke wording: the SAME string every multilingual tier
        // carries, so the two cards are comparable at a glance.
        assertTrue(copy.badges.contains("90+ languages"))
        // 338 MB is the PAIR (encoder + decoder). A future edit that badges only the encoder's
        // 113 MB — or a catalog edit that changes the pair — fires here.
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
        // 4.9.1: plain words, 4.0's shape ("Runs on your phone's AI chip. Same model as
        // Multilingual, much faster on this device.") with the retired comparand's name gone and
        // the measured, comparative, device-scoped speed claim byte-identical. "Fastest" stays
        // in the headline where it was owner-approved and does not join the body; the measured
        // comparand (the 190 MB Q5_1 twin on the Fold6) is in the KDoc beside the card.
        //
        // The body names NO OTHER MODEL. The first 4.9.1 draft read "the same model as the light
        // one, much faster on this device", and the review found what that juxtaposition does: it
        // re-points the measured claim at `small-q8` — a model on the same screen that has never
        // been timed on an NPU-capable device, and that the repo's own numbers project to be the
        // FASTER of the two on the Fold6 (the KDoc beside the card walks the arithmetic). The 4.7
        // controller ruling forbade exactly that re-pointing; a sentence can do it by shape as
        // well as by name, so the same-weights fact lives in the KDoc and not beside the speed
        // claim.
        assertEquals("Runs on this phone's AI chip, much faster on this device.", copy.body)
        // The claim is scoped to the hardware in the user's hand, and the comparand it was
        // measured against is recorded beside the card — the two things that make "much faster"
        // a statement someone could check.
        assertTrue(copy.body.contains("much faster on this device"))
        listOf("the light one", "small", "light model", "the all-rounder", "the most accurate one").forEach { other ->
            assertFalse(
                "npu's body names another card (<<$other>>) beside its speed claim — that re-points " +
                    "a measurement made against the retired Q5_1 twin at weights it was never made " +
                    "against, until a Fold6 session times small-q8 beside the NPU tiers",
                copy.body.lowercase().contains(other),
            )
        }
        val npuKdoc = cardSource("npu")
        assertTrue("npu's KDoc keeps the same-weights fact the card no longer states", npuKdoc.contains("whisper-small's weights") && npuKdoc.contains("Q8_0"))
        assertTrue("npu's KDoc says why the card names no comparand", npuKdoc.contains("re-points") && npuKdoc.contains("never been timed on an NPU-capable device"))
        assertFalse("the body may not promote the comparative to a superlative", copy.body.lowercase().contains("fastest"))
        val kdoc = cardSource("npu")
        assertTrue("npu's KDoc keeps the 4.0 body verbatim", kdoc.contains("Same model as Multilingual, much faster on this"))
        assertTrue("npu's KDoc keeps the Fold6 measurement", kdoc.contains("1.78 s") && kdoc.contains("2.3 s") && kdoc.contains("Fold6"))
        assertTrue("npu's KDoc names the comparand the measurement was made against", kdoc.contains("190 MB"))
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
        // 981 MB since 4.15: the v0.63.0 pair (686,112,520 + 295,856,032). It read 1072 MB for
        // the 4.1-4.14 pair.
        assertEquals(listOf("90+ languages", "981 MB"), copy.badges)
        // The SAME coverage string every multilingual tier carries, so the cards stay comparable
        // at a glance.
        assertTrue(copy.badges.contains("90+ languages"))
        // 981 MB is the PAIR (encoder + decoder), within the census's ±5 MB of approxBytes...
        val turbo = WhisperCatalog.byId("npu-turbo")!!
        val statedMb = copy.badges.first { it.endsWith(" MB") }.removeSuffix(" MB").toInt()
        val expectedMb = (turbo.approxBytes / 1_000_000L).toInt()
        assertTrue(
            "npu-turbo badge says $statedMb MB but the install is ~$expectedMb MB",
            kotlin.math.abs(statedMb - expectedMb) <= 5,
        )
        // ...and STRICTLY greater than the encoder alone, so a future edit that badges only the
        // 686 MB primary fires here even before the approxBytes tolerance does.
        assertTrue(
            "the badge must state the pair, not just the encoder",
            statedMb > turbo.primaryBytes / 1_000_000L,
        )
    }

    @Test fun the_npu_turbo_body_keeps_both_measured_claims_at_their_scope_and_the_comparand_lives_in_the_kdoc() {
        val copy = ModelTierCopy.forId("npu-turbo")!!
        // 4.6 T2: "The most accurate model this app ships" became "The most accurate model that
        // runs there" — the same claim with its real subject restored, because `large-v3` was
        // offered and outranked turbo. 4.9.1 keeps 4.6 T2's sentence — the scope is the SET the
        // superlative ranks against (the models that run on the chip), stated in the clause
        // that carries the superlative — so `exactly_one_card_claims_the_top_of_the_accuracy_order`
        // reads it as scoped and `ultra-q8` stays the one unscoped claimant. The first 4.9.1
        // draft said "our most accurate model" and the review caught it: an app-wide claim
        // beside `ultra-q8`'s "The most accurate one." on the same screen (a turbo device with
        // ultra-q8 installed, or the CPU tiers joining via `chooserAlsoOfferedIds` after a
        // delivery failure) — two cards claiming the top in plain words, held off the census
        // only because the em-dash joined the claim to "AI chip" in one `. `-split sentence.
        // `sentencesOf` now splits at the dash as well, so the clause has to carry its own scope.
        // The speed half is byte-identical to 4.6's: "the fastest on this device". The measured
        // comparand ("ahead of the 190 MB Multilingual model on both counts") is in the KDoc
        // beside the card, VERBATIM, per the 4.7 controller ruling — the plain body names no
        // comparand, which is neither the re-pointing that ruling forbade nor a deletion of the
        // measured claim.
        // P3a: "this phone's AI chip" became "this device's AI chip" — the census reaches tablets on
        // both vendors (the Tab S8 family on 8gen1, the Tab S10+ on mt6989), and a card may not
        // call a tablet a phone. Nothing else in the sentence moved. (A MediaTek family never reads
        // this card: forIdOn hands it its own — the_mediatek_turbo_card_... below.)
        assertEquals(
            "Runs on this device's AI chip — the most accurate model that runs there, and the fastest " +
                "on this device. The best choice on this device.",
            copy.body,
        )
        assertFalse("the turbo body is device-neutral since P3a", copy.body.lowercase().contains("phone"))
        // The scope is a word, and the word has to be in the SAME clause as the superlative:
        // without it the clause is a second unscoped claimant.
        val accuracySentences = sentencesOf(copy).filter { SUPERLATIVE.containsMatchIn(it) && ACCURACY_WORD.containsMatchIn(it) }
        assertEquals("the headline and the body's accuracy clause both rank accuracy, scoped", 2, accuracySentences.size)
        accuracySentences.forEach { sentence ->
            assertTrue("turbo's accuracy superlative must carry its scope in its own clause: <<$sentence>>", ACCURACY_SCOPE_MARKERS.any { sentence.contains(it) })
        }
        // And the scope has to be a SET, not a place: the accuracy clause names the models it
        // is ranked against ("that runs there"), not just where this one runs.
        val accuracyClause = accuracySentences.first { it != copy.headline.lowercase() }
        assertTrue("turbo's accuracy clause must name the set it ranks against: <<$accuracyClause>>", accuracyClause.contains("that runs there"))
        assertFalse(
            "the card claims the app-wide accuracy top in the old unscoped words",
            copy.body.contains("most accurate model this app ships"),
        )
        assertFalse(
            "the card claims the app-wide accuracy top in the first 4.9.1 draft's words — " +
                "ultra-q8's card says 'The most accurate one.' on the same screen",
            copy.body.lowercase().contains("our most accurate"),
        )
        // The speed claim is scoped to "this device", like the npu card's — never an absolute.
        assertTrue(copy.body.contains("the fastest on this device"))
        // The comparison the measurement was made against is OUR OWN tier — the 190 MB
        // Multilingual model, what this user would otherwise have run — and it is recorded
        // beside the card, not on it: the body names no comparand at all, and never the hidden
        // npu card (4.3's one-tier-per-device makes that a comparison to an invisible card).
        assertFalse(copy.body.contains("Multilingual"))
        assertFalse(copy.body.contains("190 MB"))
        val kdoc = cardSource("npu-turbo")
        assertTrue("turbo's KDoc keeps the measured comparison verbatim", kdoc.contains("ahead of the 190 MB Multilingual") && kdoc.contains("model on both counts"))
        assertTrue("turbo's KDoc keeps the Fold6 measurement", kdoc.contains("1.78 s") && kdoc.contains("2.3 s"))
        assertTrue("turbo's KDoc keeps the owner's A/B and ruling, dated", kdoc.contains("2026-08-29") && kdoc.contains("2026-09-10"))
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

    // ------------------------------------------------- 4.9.1 — THE BODIES ARE PLAIN
    //
    // The owner's ruling of 2026-09-17: "The copy for each one of the local models, I think we
    // could simplify that pretty dramatically. It's too technical for people that don't know
    // anything about it. Our headlines are perfectly fine, and just about everything else
    // doesn't need to be shown." Three censuses hold it: no rendered body carries a technical
    // token, every body is one to three short sentences, and every technical claim the old
    // bodies made is still in the repo — in the KDoc beside its card, which these tests READ.

    @Test fun no_rendered_body_carries_a_technical_token() {
        offeredTiers.forEach { model ->
            val body = ModelTierCopy.forId(model.id)!!.body
            TECHNICAL_TOKENS.forEach { token ->
                assertFalse(
                    "tier '${model.id}' body carries the technical token <<$token>> — the owner ruled " +
                        "the cards too technical; the fact belongs in the KDoc beside the card",
                    body.contains(token),
                )
            }
            assertFalse("tier '${model.id}' body carries a date", Regex("\\d{4}-\\d{2}-\\d{2}").containsMatchIn(body))
            assertFalse("tier '${model.id}' body names the owner", body.lowercase().contains("owner"))
            assertFalse("tier '${model.id}' body says 'Recommended' — that is the steer chip's word", body.lowercase().contains("recommended"))
        }
    }

    @Test fun every_body_is_one_to_three_short_sentences() {
        offeredTiers.forEach { model ->
            val body = ModelTierCopy.forId(model.id)!!.body
            val sentences = body.split(". ").filter { it.isNotBlank() }
            assertTrue("tier '${model.id}' body has ${sentences.size} sentences; one to three is the ruling", sentences.size in 1..3)
            assertTrue("tier '${model.id}' body ends a sentence", body.endsWith("."))
            assertTrue("tier '${model.id}' body is ${body.length} chars — not short", body.length <= 140)
        }
    }

    /**
     * **NOTHING WAS LOST.** Every measurement, twin fact and dated owner report the 4.9 bodies
     * carried is in the KDoc beside its card — the doc it is checkable against by path, the
     * number the headline's rank rests on, the retired twin's size and quantisation, the Fold6
     * comparand. This reads `ModelTierCopy.kt` itself, so deleting a comment block to tidy the
     * file fails here rather than silently orphaning a headline from its evidence.
     */
    @Test fun the_evidence_lives_in_the_kdoc_beside_each_card() {
        val doc = "docs/measurements/2026-09-17-tab-cpu-ladder.md"
        mapOf(
            "small-q8" to listOf(doc, "1,217", "190 MB", "Q5_1", "the same weights", "Q8_0"),
            "medium-q8" to listOf(doc, "1,341", "24 encoder layers at 1024 dims", "4.5 GB", "Q8_0"),
            "ultra-q8" to listOf(doc, "4,849", "no margin", "six to nine second", "totally manageable and doable", "a smaller Whisper is the fix", "4.5 GB", "Q8_0"),
            "npu" to listOf("Same model as Multilingual, much faster on this", "1.78 s", "2.3 s", "Fold6", "190 MB"),
            "npu-turbo" to listOf("ahead of the 190 MB Multilingual", "1.78 s", "2.3 s", "2026-08-29", "2026-09-10", "fastest one we have and most accurate"),
        ).forEach { (id, needles) ->
            val kdoc = cardSource(id)
            val body = ModelTierCopy.forId(id)!!.body
            needles.forEach { needle ->
                assertTrue("'$id': the KDoc beside the card no longer carries <<$needle>> — the evidence moved off the card in 4.9.1 and may not leave the file", kdoc.contains(needle))
            }
            // ...and the body itself is checkable only through that KDoc: a fact stated on the
            // card is one the user has to read, and the ruling is that they need not.
            listOf(doc, "1,217", "1,341", "4,849", "1.78 s", "2.3 s").forEach { needle ->
                assertFalse("'$id' body still renders <<$needle>>", body.contains(needle))
            }
        }
        // The census covers every offered card — a sixth card added without an evidence block
        // must reach here.
        assertEquals(offeredTiers.map { it.id }.toSet(), setOf("small-q8", "medium-q8", "ultra-q8", "npu", "npu-turbo"))
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

    // ------------------------------------------------- P3a — THE CARD A DEVICE'S FAMILY READS
    //
    // The census gained a MediaTek family (mt6989: the Galaxy Tab S10+ / S10 Ultra). Both chooser
    // surfaces render ModelTierCopy.forIdOn(id, family): a MediaTek family reads its own turbo
    // card — accuracy only, because the Qualcomm card's "fastest" is false on the tablet — and
    // every gated card's size badge states the family's own pair. These pins run every claim rule
    // above over EVERY card a census family reads, and hold the copy exactly.

    /** Every card a chooser can render on [family] — the offered tiers, through [ModelTierCopy.forIdOn]. */
    private fun cardsOn(family: NpuSocFamily?): Map<String, ModelTierCopy.TierCopy> =
        offeredTiers.associate { it.id to ModelTierCopy.forIdOn(it.id, family)!! }

    private val mediatekFamilies: List<NpuSocFamily> =
        NpuFleetCensus.families.filter { it.vendor == NpuVendor.MEDIATEK }

    /**
     * THE MEDIATEK TURBO CARD, EXACTLY — and the claim rules over it. Accuracy only: "The most
     * accurate model this device can run, on its AI chip" is the design's proposal (§2.8), a
     * superlative scoped to THIS DEVICE that the claim rules allow with a measurement behind it —
     * the tablet's ladder in the owner's words ("V3 turbo: highest accuracy",
     * docs/measurements/2026-09-17-tab-cpu-ladder.md) and the APU running that model word-perfect
     * in the product's engine (docs/measurements/2026-09-24-tab-apu-turbo-encoder.md §6); both are
     * cited in the KDoc beside the card (held below). The badge is the mt6989 pair, 1,302,606,488 +
     * 584,862,184 = 1,887,468,672 B, by the badge rule (SI MB, truncated): "1887 MB".
     */
    @Test fun the_mediatek_turbo_card_is_pinned_exactly_and_claims_accuracy_alone() {
        assertEquals("the census has the mt6989 MediaTek family", listOf("mt6989"), mediatekFamilies.map { it.id })
        for (family in mediatekFamilies) {
            val card = ModelTierCopy.forIdOn("npu-turbo", family)!!
            assertEquals("Best AI-chip accuracy", card.headline)
            assertEquals(listOf("90+ languages", "1887 MB"), card.badges)
            assertEquals("The most accurate model this device can run, on its AI chip.", card.body)
            val all = (card.headline + " " + card.body + " " + card.badges.joinToString(" ")).lowercase()
            // The position the 3.7 census demands, in the headline where the eye lands first.
            assertTrue("the MediaTek turbo headline takes no position", POSITION_WORDS.any { card.headline.lowercase().contains(it) })
            // No speed claim of any kind — the ruling is pending (the TODO(owner) pin below).
            SPEED_CLAIM_WORDS.forEach { word ->
                assertFalse(
                    "the MediaTek turbo card claims speed with <<$word>> — its speed claim awaits the owner's wording",
                    Regex("\\b" + Regex.escape(word) + "\\b").containsMatchIn(all),
                )
            }
            sentencesOf(card).forEach { sentence ->
                assertFalse("<<$sentence>> is an absolute speed claim", isAbsoluteSpeedClaim(sentence))
                assertNull("<<$sentence>> ranks speed", speedSuperlativeIn(sentence))
                assertFalse(
                    "<<$sentence>> claims the top of the accuracy order unscoped — ultra-q8 is the one card that may",
                    isUnscopedAccuracyTopClaim(sentence),
                )
            }
            // The accuracy superlative carries its scope in its own clause (the device, and the
            // AI chip it runs on) — the claim the evidence below is cited for.
            val accuracyClause = sentencesOf(card).single { it != card.headline.lowercase() }
            assertTrue(accuracyClause.contains("this device can run") && accuracyClause.contains("ai chip"))
            // The plain-body rules (4.9.1), the absolutes and the cross-app list.
            TECHNICAL_TOKENS.forEach { token -> assertFalse("the MediaTek body carries <<$token>>", card.body.contains(token)) }
            assertTrue(card.body.split(". ").filter { it.isNotBlank() }.size in 1..3)
            assertTrue(card.body.endsWith(".") && card.body.length <= 140)
            assertFalse(Regex("\\d{4}-\\d{2}-\\d{2}").containsMatchIn(card.body))
            listOf("owner", "recommended", "phone", "tablet").forEach { assertFalse("the MediaTek card says <<$it>>", all.contains(it)) }
            listOf(
                "instant", "real-time", "realtime", "no delay", "no lag", "zero lag", "guaranteed", "always", "never", "unlimited",
                "other app", "any app", "every app", "any other", "than other", "competitor", "gboard", "google", "apple", "siri",
                "otter", "dragon", "whisperkit", "times faster", "x faster",
            ).forEach { assertFalse("the MediaTek card says <<$it>>", all.contains(it)) }
            assertTrue("the coverage badge every multilingual card carries", card.badges.contains("90+ languages"))
            // The badge IS the family's pair, by the one rule — the literal and the derivation agree.
            val pair = NpuFleetCensus.artifactFor(family.id, "npu-turbo")!!
            assertEquals(1_887_468_672L, pair.encoder.bytes + pair.decoder.bytes)
            assertEquals(ModelTierCopy.sizeBadge(pair.encoder.bytes + pair.decoder.bytes), card.badges.last())
        }
    }

    /**
     * TODO(owner): THE SPEED CLAIM ON MEDIATEK FAMILIES AWAITS THE OWNER'S WORDING (plan P3-2;
     * design §2.8 and §7 q3). The Qualcomm turbo card's "fastest" / "the fastest on this device" is
     * measured and owner-ruled on Qualcomm silicon and FALSE on the Tab S10+ — its CPU commits
     * `small-q8` in 1,217 ms and `medium-q8` in 1,341 against the APU's ≈ 2.3 s (the two sheets the
     * card's KDoc cites). Until he rules, the MediaTek card carries NO speed claim, and this pin
     * fails the moment the Qualcomm speed sentence renders on a MediaTek row — through the card, or
     * through a chooser surface that stops asking for the family's card. His wording replaces the
     * MediaTek body and this pin together.
     */
    @Test fun todo_owner_the_qualcomm_speed_sentence_never_renders_on_a_mediatek_row() {
        val qualcomm = ModelTierCopy.forId("npu-turbo")!!
        assertTrue("the Qualcomm card still carries its measured speed claim", qualcomm.body.contains("the fastest on this device"))
        for (family in mediatekFamilies) {
            cardsOn(family).forEach { (id, card) ->
                assertFalse(
                    "$id on ${family.id} renders the Qualcomm speed sentence",
                    (card.headline + " " + card.body).lowercase().contains("the fastest on this device"),
                )
            }
            val turbo = ModelTierCopy.forIdOn("npu-turbo", family)!!
            assertFalse(
                "the turbo card on ${family.id} says 'fastest' — its speed claim awaits the owner's wording",
                Regex("\\bfastest\\b").containsMatchIn((turbo.headline + " " + turbo.body).lowercase()),
            )
            assertTrue("the MediaTek turbo card is not the Qualcomm one", turbo.headline != qualcomm.headline && turbo.body != qualcomm.body)
        }
        // The render path: both chooser surfaces ask for THE FAMILY'S card. A surface that went
        // back to the family-blind forId would render the Qualcomm sentence on a Tab S10+ with
        // every card test above still green.
        val picker = appSource("src/main/java/com/whispereverywhere/ui/screens/OnboardingModelScreen.kt")
        val flow = appSource("src/main/java/com/whispereverywhere/ui/screens/OnboardingFlowScreen.kt")
        assertEquals("the Settings picker's card reads the family's copy", 1, liveCount(picker, "val copy = ModelTierCopy.forIdOn(model.id, family)"))
        assertEquals("…with the device's family memo handed to every card", 1, liveCount(picker, "family = npuFamily,"))
        assertEquals("…remembered once from the app's census resolution", 1, liveCount(picker, "val npuFamily: NpuSocFamily? = remember { app.npuSocFamily }"))
        assertEquals("the guided flow's card reads the family's copy", 1, liveCount(flow, "copy = ModelTierCopy.forIdOn(model.id, npuFamily),"))
        assertEquals("…from the same memo", 1, liveCount(flow, "val npuFamily = remember { WhisperEverywhereApp.getInstance().npuSocFamily }"))
        assertEquals("and neither surface calls the family-blind forId", 0, liveCount(picker, "ModelTierCopy.forId(") + liveCount(flow, "ModelTierCopy.forId("))
    }

    /**
     * THE SIZE BADGE IS THE FAMILY'S PAIR, BY THE ONE RULE, ON EVERY FAMILY. Before P3a the turbo
     * badge was the 8gen3 pair's literal on every device — "981 MB" on a Tab S10+ that downloads
     * 1,887,468,672 B. [ModelTierCopy.forIdOn] derives each gated card's badge from the family's own
     * census row ([ModelTierCopy.familyPairBytes]) by [ModelTierCopy.sizeBadge] — SI megabytes,
     * truncated, the rule every literal badge already followed — and nothing else on a Qualcomm
     * card moves.
     */
    @Test fun every_familys_gated_cards_badge_its_own_pair_and_nothing_else_moves() {
        // The rule, against the literals it was read off.
        assertEquals("981 MB", ModelTierCopy.sizeBadge(981_968_552L))
        assertEquals("338 MB", ModelTierCopy.sizeBadge(338_422_512L))
        assertEquals("264 MB", ModelTierCopy.sizeBadge(264_464_607L))
        assertEquals("1887 MB", ModelTierCopy.sizeBadge(1_887_468_672L))
        // The reference card (and any family the census cannot answer for) keeps its literals.
        assertEquals(listOf("90+ languages", "981 MB"), ModelTierCopy.forIdOn("npu-turbo", null)!!.badges)
        assertEquals(ModelTierCopy.forId("npu")!!, ModelTierCopy.forIdOn("npu", null))
        for (family in NpuFleetCensus.families) {
            val cards = cardsOn(family)
            for (tier in family.tiers) {
                val pair = NpuFleetCensus.artifactFor(family.id, tier)!!
                val bytes = pair.encoder.bytes + pair.decoder.bytes
                assertEquals("${family.id}/$tier: the family's pair bytes", bytes, ModelTierCopy.familyPairBytes(family, tier))
                val badge = cards.getValue(tier).badges.single { it.endsWith(" MB") }
                assertEquals("${family.id}/$tier's badge is its own pair, by the rule", ModelTierCopy.sizeBadge(bytes), badge)
                assertTrue("${family.id}/$tier: and states the PAIR, not the encoder", badge.removeSuffix(" MB").toLong() > pair.encoder.bytes / 1_000_000L)
            }
            // The CPU rungs are no family's: their cards are the reference cards, byte for byte.
            offeredTiers.filter { !it.gated }.forEach {
                assertEquals("${family.id}/${it.id} is the reference card", ModelTierCopy.forId(it.id), cards.getValue(it.id))
                assertNull(ModelTierCopy.familyPairBytes(family, it.id))
            }
            // On a Qualcomm row only the badge moves: headline and body are the reference card's.
            if (family.vendor == NpuVendor.QUALCOMM) {
                listOf("npu", "npu-turbo").forEach {
                    assertEquals("${family.id}/$it headline", ModelTierCopy.forId(it)!!.headline, cards.getValue(it).headline)
                    assertEquals("${family.id}/$it body", ModelTierCopy.forId(it)!!.body, cards.getValue(it).body)
                }
            }
        }
        // The spread the move buys, stated: every Qualcomm family's turbo pair at v0.63.0.
        assertEquals(
            mapOf("8gen3" to "981 MB", "8elite_galaxy" to "981 MB", "8elite5_galaxy" to "983 MB", "7gen4" to "999 MB",
                "qcs8550" to "981 MB", "8gen1" to "976 MB", "mt6989" to "1887 MB"),
            NpuFleetCensus.families.associate { it.id to cardsOn(it).getValue("npu-turbo").badges.last() },
        )
    }

    /**
     * The census rules that range over the lineup — one unscoped accuracy claimant, one headline
     * per card — hold on EVERY family's lineup, not only on the reference cards: a MediaTek device
     * with `ultra-q8` installed renders both turbo cards together (the checkpoint tie is stated in
     * the MediaTek card's KDoc; its clause carries the AI-chip scope, so ultra-q8 stays the one
     * unscoped claimant).
     */
    @Test fun the_lineup_censuses_hold_on_every_familys_cards() {
        for (family in NpuFleetCensus.families + listOf(null)) {
            val cards = cardsOn(family)
            assertEquals(
                "${family?.id}: exactly one card claims the top of the accuracy order, unscoped",
                listOf("ultra-q8"),
                cards.filter { (_, c) -> sentencesOf(c).any { isUnscopedAccuracyTopClaim(it) } }.keys.toList(),
            )
            val headlines = cards.values.map { it.headline }
            assertEquals("${family?.id}: two cards share a headline: $headlines", headlines.size, headlines.toSet().size)
            cards.forEach { (id, c) ->
                assertTrue("${family?.id}/$id has a size badge", c.badges.any { it.endsWith(" MB") })
                assertTrue("${family?.id}/$id has the coverage badge", c.badges.contains("90+ languages"))
            }
        }
    }

    /**
     * NOTHING WAS INVENTED: the MediaTek card's one claim is cited beside it — the owner's ladder
     * words and the doc, the APU sheet's §6 and its verdict, the tie with ultra-q8 — and the
     * pending speed ruling is a TODO(owner) there, with the numbers that make the Qualcomm claim
     * false on the tablet. Read out of ModelTierCopy.kt itself (a declared input of the test task).
     */
    @Test fun the_mediatek_cards_evidence_lives_in_the_kdoc_beside_it() {
        val src = MODEL_TIER_COPY_SOURCE
        val start = src.indexOf("private val mediatekCopyById")
        assertTrue("the MediaTek card map is declared", start >= 0)
        val block = src.substring(src.lastIndexOf("/**", start), src.indexOf("\n    )\n", start))
        listOf(
            "docs/measurements/2026-09-17-tab-cpu-ladder.md", "V3 turbo: highest accuracy", "1,217", "1,341",
            "docs/measurements/2026-09-24-tab-apu-turbo-encoder.md", "§6", "matches_reference=true",
            "`ultra-q8` IS large-v3-turbo", "TODO(owner)", "plan P3-2", "1,887,468,672",
        ).forEach { needle ->
            assertTrue("the MediaTek card's KDoc no longer carries <<$needle>>", block.contains(needle))
        }
        assertEquals("the MediaTek map carries exactly the turbo card", 1, Regex("\" to TierCopy\\(").findAll(block).count())
    }

    /** `app/<relative>`'s text, LF-normalised — the same walk [MODEL_TIER_COPY_SOURCE] uses. */
    private fun appSource(relative: String): String {
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            for (candidate in listOf(java.io.File(dir, relative), java.io.File(dir, "app/$relative"))) {
                if (candidate.isFile) return candidate.readText().replace("\r\n", "\n")
            }
            dir = dir.parentFile
        }
        throw AssertionError("cannot locate $relative from ${System.getProperty("user.dir")}")
    }

    /** Occurrences of [needle] on LIVE lines of [src] — a comment quoting a call is not the call. */
    private fun liveCount(src: String, needle: String): Int =
        src.lines().filterNot { it.trimStart().let { t -> t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") } }
            .sumOf { line -> line.split(needle).size - 1 }

    private companion object {
        /** The 3.7 census's position vocabulary, shared so the npu pin cannot drift from the loop. */
        val POSITION_WORDS = listOf("fastest", "fast", "slower", "accuracy")

        /**
         * **4.6 T2 — the vocabulary a CPU rung may not use unscoped, in EITHER direction.** A
         * slow claim is as unearned as a fast one: in 4.6 nothing on the ladder was measured;
         * since 4.7 the three Q8 rungs are measured on ONE tablet and never against one another
         * on the user's device. Since 4.9 a card MAY use these words to rank the three — the
         * owner's ladder labels — but only in a headline pinned beside a body that names the
         * tablet and the date, and never beside "every device" or "this device": a rank the
         * user can check against a document, not a claim the owner has to catch.
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
         * 4.9.1 — the subset a CPU BODY may never use. A plain speed word ("quick") is the
         * owner's plain copy; a superlative is a RANK, and the only rank a CPU card states is
         * its headline's, whose measurement is cited in the KDoc beside the card. The one-word
         * forms are listed; the periphrastic ones ("the most responsive", "least sluggish") are
         * [SPEED_SUPERLATIVE_PHRASE], so a speed word that has no "-est" cannot rank by adding
         * "most" (the 4.9.1 review's probe).
         */
        val SPEED_SUPERLATIVES = listOf("fastest", "quickest", "slowest", "swiftest")
        val SPEED_SUPERLATIVE_PHRASE = Regex(
            "\\b(most|least)\\s+(" + SPEED_CLAIM_WORDS.joinToString("|") { Regex.escape(it) } + ")\\b",
        )

        /** The speed superlative a sentence carries — a listed word or a "most X" phrase — or null. */
        fun speedSuperlativeIn(sentence: String): String? =
            SPEED_SUPERLATIVES.firstOrNull { Regex("\\b" + Regex.escape(it) + "\\b").containsMatchIn(sentence) }
                ?: SPEED_SUPERLATIVE_PHRASE.find(sentence)?.value

        /**
         * **The ABSOLUTE scope a CPU speed word may never share a sentence with** — a claim about
         * hardware nobody has measured. It is a SHAPE, not a phrase list, because the 4.9.1
         * review found the list ("every device", "any device", "all devices", "everywhere",
         * "this device", "your device", "your phone", "this phone") let through the phrasings the
         * rule's own KDoc forbids: "Quick on any phone", "Fast on every phone", "Snappy on
         * phones". So: any quantifier or deictic before a device noun, any bare plural device
         * noun, and the two adverbs. "Fits every device" on small's card is not caught — that
         * sentence carries no speed word, and the RAM fit IS true of every device.
         */
        val ABSOLUTE_SCOPE = Regex(
            "\\b(every|any|all|each|this|that|these|those|your|most|a|an|the)\\s+(phone|device|tablet|handset|hardware)s?\\b" +
                "|\\b(phones|devices|tablets|handsets)\\b" +
                "|\\b(everywhere|anywhere)\\b",
        )

        /** A speed word and an absolute scope in the same sentence: the claim the rule forbids. */
        fun isAbsoluteSpeedClaim(sentence: String): Boolean =
            SPEED_CLAIM_WORDS.any { Regex("\\b" + Regex.escape(it) + "\\b").containsMatchIn(sentence) } &&
                ABSOLUTE_SCOPE.containsMatchIn(sentence)

        /**
         * 4.9.1 — the vocabulary no rendered body may carry, because the owner ruled the cards
         * "too technical for people that don't know anything about it". Each token is one the
         * old bodies actually used; the KDoc beside each card still does.
         */
        val TECHNICAL_TOKENS = listOf("Q8", "Q5", "layer", "dims", " ms", "encoder", "decoder", "quantis", "tablet", "ggml", ".bin", "w8a16")

        /**
         * `ModelTierCopy.kt`'s own text, read off disk so the tests can pin what the KDoc beside
         * each card carries — the evidence the 4.9.1 plain bodies no longer show. Same lookup as
         * the other source pins (`LivePreviewDeclinedPinTest.repoFile`): walk up from the working
         * directory trying `relative` and `app/relative`, because Gradle runs the unit tests from
         * the module directory and an IDE may run them from the root.
         */
        val MODEL_TIER_COPY_SOURCE: String by lazy {
            val relative = "src/main/java/com/whispereverywhere/model/ModelTierCopy.kt"
            var dir: java.io.File? = java.io.File(System.getProperty("user.dir") ?: ".").absoluteFile
            while (dir != null) {
                for (candidate in listOf(java.io.File(dir, relative), java.io.File(dir, "app/$relative"))) {
                    if (candidate.isFile) return@lazy candidate.readText().replace("\r\n", "\n")
                }
                dir = dir.parentFile
            }
            throw AssertionError("cannot locate $relative from ${System.getProperty("user.dir")}")
        }

        /**
         * The source of ONE card — its `"id" to TierCopy(` entry plus the comment block above
         * it (everything after the previous entry's closing `),`, or after the map's opening
         * for the first card). This is where a card's evidence lives since 4.9.1, and what the
         * pins below read.
         */
        fun cardSource(id: String): String {
            val src = MODEL_TIER_COPY_SOURCE
            val entry = src.indexOf("\"$id\" to TierCopy(")
            assertTrue("no TierCopy entry for '$id' in ModelTierCopy.kt", entry >= 0)
            val close = "\n        ),"
            val end = src.indexOf(close, entry)
            assertTrue("unterminated TierCopy entry for '$id'", end >= 0)
            val previousClose = src.lastIndexOf(close, entry)
            val start = if (previousClose >= 0) previousClose else src.indexOf("copyById")
            return src.substring(start, end)
        }

        /**
         * A card's claims, one per sentence — the headline plus the body split at sentence ends
         * AND at em-dashes, lowercased. Per-SENTENCE and not per-card on purpose: a scope named
         * in one sentence does not license an unscoped superlative in the next one, and turbo's
         * card is exactly that shape (the AI chip is named in its opening clause, the accuracy
         * claim lives in the clause after the dash, and that clause has to carry its own scope).
         * The dash split is the 4.9.1 review's: "Runs on this phone's AI chip — our most accurate
         * model" passed the census as one sentence because the opener's "AI chip" counted as the
         * claim's scope, when it only names where the model RUNS, not the set it is ranked
         * against.
         */
        fun sentencesOf(copy: ModelTierCopy.TierCopy): List<String> =
            (listOf(copy.headline) + copy.body.split(". ").flatMap { it.split(" — ") }).map { it.lowercase() }

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
