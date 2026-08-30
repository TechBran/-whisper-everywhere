package com.whispereverywhere.model

import com.whispereverywhere.npu.NpuAssetImport
import com.whispereverywhere.npu.NpuFleetCensus
import com.whispereverywhere.npu.NpuModelSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WhisperCatalogHelpersTest {

    @Test
    fun catalog_hasFiveEntries_withExpectedIds() {
        // 4.0: 6 -> 7; 4.1: 7 -> 8. The census pin fired for `npu` and again for `npu-turbo`,
        // exactly as designed, and is resolved here rather than relaxed — the whole point of the
        // pin is that a tier cannot arrive unannounced. Both npu-class tiers are last because
        // entries order is chronological, and both are GATED, so this list growing does not
        // change what any device is offered (see the pickable/pickableFor pair below).
        val ids = WhisperCatalog.entries.map { it.id }
        assertEquals(8, WhisperCatalog.entries.size)
        assertEquals(listOf("eco", "base", "pro", "extreme", "multi", "ultra", "npu", "npu-turbo"), ids)
    }

    @Test
    fun catalog_scopesAndMinRam_areCorrect() {
        fun m(id: String) = WhisperCatalog.byId(id)!!

        assertEquals(ModelScope.ENGLISH, m("eco").scope)
        assertEquals(0L, m("eco").minRamBytes)

        assertEquals(ModelScope.ENGLISH, m("pro").scope)
        assertEquals(0L, m("pro").minRamBytes)

        assertEquals(ModelScope.ENGLISH, m("extreme").scope)
        // 5.5e9, not 6e9: ActivityManager.totalMem under-reports physical RAM; the gate
        // targets genuine 6 GB-class hardware (2026-07-17 hardening).
        assertEquals(5_500_000_000L, m("extreme").minRamBytes)

        assertEquals(ModelScope.MULTILINGUAL, m("multi").scope)
        assertEquals(0L, m("multi").minRamBytes)

        assertEquals(ModelScope.MULTILINGUAL, m("ultra").scope)
        // 7.0e9 = genuine 8 GB-class hardware after totalMem slack.
        assertEquals(7_000_000_000L, m("ultra").minRamBytes)
    }

    @Test
    fun catalog_urlsAndApproxBytes_matchContract() {
        // Pinned to an immutable revision: /resolve/main is a mutable ref that could brick
        // every APK-pinned sha256 if upstream replaced a file (2026-07-17 hardening).
        val base = "https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1/"
        val eco = WhisperCatalog.byId("eco")!!
        assertEquals("ggml-base.en-q5_1.bin", eco.fileName)
        assertEquals(base + "ggml-base.en-q5_1.bin", eco.url)
        // Exact HF LFS byte sizes (the old rounded values sat needlessly close to the ±5% gate).
        assertEquals(59_721_011L, eco.approxBytes)

        assertEquals(190_098_681L, WhisperCatalog.byId("pro")!!.approxBytes)
        assertEquals(539_225_533L, WhisperCatalog.byId("extreme")!!.approxBytes)
        assertEquals(190_085_487L, WhisperCatalog.byId("multi")!!.approxBytes)
        assertEquals(574_041_195L, WhisperCatalog.byId("ultra")!!.approxBytes)
    }

    @Test
    fun modelById_returnsNull_forUnknownId() {
        assertNull(WhisperCatalog.byId("nope"))
    }

    @Test
    fun isRecommended_boundary_atMinRam() {
        val ultra = WhisperCatalog.byId("ultra")!! // minRam 7_000_000_000

        // just below -> not recommended
        assertFalse(WhisperCatalog.isRecommendedForDevice(ultra, 6_999_999_999L))
        // exactly at threshold -> recommended (>=)
        assertTrue(WhisperCatalog.isRecommendedForDevice(ultra, 7_000_000_000L))
        // above -> recommended
        assertTrue(WhisperCatalog.isRecommendedForDevice(ultra, 12_000_000_000L))
    }

    @Test
    fun isRecommended_zeroMinRam_alwaysRecommended() {
        val eco = WhisperCatalog.byId("eco")!!
        assertTrue(WhisperCatalog.isRecommendedForDevice(eco, 0L))
        assertTrue(WhisperCatalog.isRecommendedForDevice(eco, 2_000_000_000L))
    }

    @Test
    fun sizeWithinTolerance_fivePercent() {
        val approx = 100_000_000L
        // exactly equal
        assertTrue(WhisperCatalog.sizeWithinTolerance(100_000_000L, approx))
        // +5% edge (105,000,000) inclusive
        assertTrue(WhisperCatalog.sizeWithinTolerance(105_000_000L, approx))
        // -5% edge (95,000,000) inclusive
        assertTrue(WhisperCatalog.sizeWithinTolerance(95_000_000L, approx))
        // just over +5%
        assertFalse(WhisperCatalog.sizeWithinTolerance(105_000_001L, approx))
        // just under -5%
        assertFalse(WhisperCatalog.sizeWithinTolerance(94_999_999L, approx))
    }

    @Test fun retired_tiers_remain_resolvable_by_id() {
        // The app-wide gate is installedModel() != null, which starts with byId(). If byId
        // returns null for a retired tier, every user on it is force-marched into onboarding
        // with no back navigation and their model file is orphaned. Resolvable forever.
        assertNotNull(WhisperCatalog.byId("extreme"))
        assertNotNull(WhisperCatalog.byId("ultra"))
    }

    @Test fun retired_tiers_are_not_pickable() {
        val ids = WhisperCatalog.pickable.map { it.id }
        assertFalse(ids.contains("extreme"))
        assertFalse(ids.contains("ultra"))
        // 3.7 Workstream H (owner decision 2026-08-20): the 60 MB tiers join them. "Pretty much
        // useless at this point… because of the accuracy."
        assertFalse(ids.contains("eco"))
        assertFalse(ids.contains("base"))
    }

    @Test fun pickable_is_exactly_pro_and_multi() {
        // The post-3.7 lineup: pro = the English flagship, multi = the international tier.
        assertEquals(listOf("pro", "multi"), WhisperCatalog.pickable.map { it.id })
    }

    @Test fun the_sixty_megabyte_tiers_stay_resolvable_after_retirement() {
        // Same rule that protects extreme/ultra: byId() must keep answering or every installed
        // eco/base user's installedModel() goes null and the app-wide gate force-marches them
        // into onboarding with their model file orphaned on disk.
        assertNotNull(WhisperCatalog.byId("eco"))
        assertNotNull(WhisperCatalog.byId("base"))
    }

    @Test fun retiring_a_tier_does_not_by_itself_declare_it_unsupported() {
        // THE 3.7 split. `retired` hides a tier from the chooser (fresh installs only);
        // `unsupported` is what drives Settings' migration card. eco/base are retired but
        // perfectly usable, so their installed users must see nothing at all — the spec's
        // "existing users unaffected; no re-download forced". extreme/ultra keep both flags.
        assertFalse(WhisperCatalog.byId("eco")!!.unsupported)
        assertFalse(WhisperCatalog.byId("base")!!.unsupported)
        assertTrue(WhisperCatalog.byId("extreme")!!.unsupported)
        assertTrue(WhisperCatalog.byId("ultra")!!.unsupported)
    }

    @Test fun every_unsupported_tier_is_also_retired() {
        // Offering a tier the app wants to migrate people OFF of would be incoherent.
        WhisperCatalog.entries.filter { it.unsupported }.forEach {
            assertTrue("'${it.id}' is unsupported but still offered", it.retired)
        }
    }

    @Test fun default_is_pro() {
        assertEquals("pro", WhisperCatalog.DEFAULT_MODEL_ID)
        assertNotNull(WhisperCatalog.byId(WhisperCatalog.DEFAULT_MODEL_ID))
    }

    @Test fun default_is_pickable() {
        // A retired default would be unreachable from the picker — an unshippable state.
        assertTrue(WhisperCatalog.pickable.any { it.id == WhisperCatalog.DEFAULT_MODEL_ID })
    }

    @Test fun base_multilingual_tier_has_its_pinned_lfs_values() {
        // Exact LFS byte size and digest, fetched at the catalog's pinned commit. Rounding
        // approxBytes has previously left a correct download barely inside the +/-5% gate.
        val m = WhisperCatalog.byId("base")!!
        assertEquals("ggml-base-q5_1.bin", m.fileName)
        assertEquals(59_707_625L, m.approxBytes)
        assertEquals("422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898", m.sha256)
        assertEquals(ModelScope.MULTILINGUAL, m.scope)
    }

    @Test fun every_entry_has_a_distinct_id_and_filename() {
        assertEquals(WhisperCatalog.entries.size, WhisperCatalog.entries.map { it.id }.toSet().size)
        // Paired artefacts land in the SAME models dir as the primaries, so the distinctness rule
        // covers every file name any tier writes there — two tiers sharing one file on disk would
        // make one of them delete the other's model.
        val files = WhisperCatalog.entries.flatMap { listOfNotNull(it.fileName, it.pairedArtifact?.fileName) }
        assertEquals(files.size, files.toSet().size)
    }

    @Test fun every_sha256_is_lowercase_hex_of_the_right_length() {
        // Reads the PAIRED artefact's digest too (4.0): npu's decoder is 225 MB of the 358 MB the
        // user installs, and a "PENDING"/placeholder digest there would have been unverifiable
        // bytes shipped under a verified tier's name.
        val digests = WhisperCatalog.entries.flatMap { m ->
            listOfNotNull(m.id to m.sha256, m.pairedArtifact?.let { "${m.id}:${it.fileName}" to it.sha256 })
        }
        digests.forEach { (who, sha) ->
            assertEquals("$who sha256 length", 64, sha.length)
            assertTrue("$who sha256 must be lowercase hex", sha.matches(Regex("[0-9a-f]{64}")))
        }
        // 4.1: DISTINCT, all of them. With four npu-class digests in one file, a copy-paste
        // between two PairedArtifacts is a real mutation — and it would install the wrong half of
        // a pair WITH A PASSING VERIFICATION, because the digest it checks against would be the
        // digest of the file that arrived.
        assertEquals(
            "every catalog sha256 must be distinct — a duplicated digest verifies the wrong file",
            digests.size,
            digests.map { it.second }.toSet().size,
        )
    }

    @Test fun the_npu_tier_states_the_asset_pair_it_actually_needs() {
        // Both digests are MEASURED off the extracted files (the spike staged and hashed them),
        // not placeholders — the tier ships verified or not at all.
        val npu = WhisperCatalog.byId("npu")!!
        assertEquals(ModelScope.MULTILINGUAL, npu.scope)      // same whisper-small weights as multi
        assertEquals(0L, npu.minRamBytes)                     // the SoC gate is the real gate
        // OWNER-PENDING, and pinned for exactly that reason: nothing else in app/src names this
        // string, so without this line it could be reworded — or emptied — with a green suite. It
        // is the DownloadManager notification title and Settings' tier list entry; the card itself
        // renders ModelTierCopy, which is pinned separately.
        assertEquals("Multilingual on NPU (small)", npu.displayName)
        assertTrue(npu.gated)
        assertFalse(npu.retired)
        assertFalse(npu.unsupported)
        assertEquals("encoder_qairt_context.bin", npu.fileName)
        assertEquals("3e92ac26545b6b9d22ecfab594ae57523134006e2722b09fa10e16b193e9e5ec", npu.sha256)
        assertEquals(132_927_488L, npu.primaryBytes)

        val decoder = npu.pairedArtifact!!
        assertEquals("decoder_qairt_context.bin", decoder.fileName)
        assertEquals("fda23d731e6b0ab7fb0a50373a49efe2d1792faa5dad456837624d8b8e44b0e4", decoder.sha256)
        assertEquals(225_316_864L, decoder.approxBytes)

        // The advertised size is the PAIR: what the user downloads, stores, and reads on the badge.
        assertEquals(358_244_352L, npu.approxBytes)
        assertEquals(npu.approxBytes, npu.primaryBytes + decoder.approxBytes)
        // Both files come out of one archive, so one URL is the honest answer for both.
        assertEquals(npu.url, decoder.url)
    }

    @Test fun the_encoder_is_size_gated_against_its_own_bytes_not_the_pairs() {
        // THE reason primaryBytes exists. isInstalled() size-gates models/<fileName> — the ENCODER
        // — and npu.approxBytes is the sum of both files. Gate the encoder against the sum and the
        // tier reads "not installed" forever, whatever the owner imports.
        val npu = WhisperCatalog.byId("npu")!!
        assertFalse(
            "the encoder's own length must NOT satisfy the pair's advertised size",
            WhisperCatalog.sizeWithinTolerance(npu.primaryBytes, npu.approxBytes),
        )
        assertTrue(WhisperCatalog.sizeWithinTolerance(npu.primaryBytes, npu.primaryBytes))
        // Every single-file tier is untouched: primaryBytes defaults to approxBytes.
        WhisperCatalog.entries.filter { it.pairedArtifact == null }.forEach {
            assertEquals("${it.id} primaryBytes drifted from approxBytes", it.approxBytes, it.primaryBytes)
        }
    }

    @Test fun a_gated_tier_is_never_pickable_and_is_never_the_default() {
        // The offered lineup must not change on any device that cannot answer the gate question,
        // and the fallback every no-pick-on-record path lands on must stay device-independent.
        // 4.1: this census fired when npu-turbo arrived — the second gated tier, announced here.
        assertEquals(listOf("npu", "npu-turbo"), WhisperCatalog.entries.filter { it.gated }.map { it.id })
        assertFalse(WhisperCatalog.pickable.any { it.gated })
        assertFalse(WhisperCatalog.byId(WhisperCatalog.DEFAULT_MODEL_ID)!!.gated)
        assertEquals("multi", ModelMigration.targetIdFor(ModelScope.MULTILINGUAL))
    }

    @Test fun pickableFor_offers_a_gated_tier_exactly_when_its_id_is_in_the_set() {
        // 4.1: the Boolean became a set — two gated tiers can be independently installed, and one
        // bit cannot say which. The empty set is the every-other-device answer and must be
        // identical to `pickable`; each id ADDS its own tier and nothing else, in catalog order.
        assertEquals(WhisperCatalog.pickable, WhisperCatalog.pickableFor(emptySet()))
        assertEquals(listOf("pro", "multi"), WhisperCatalog.pickableFor(emptySet()).map { it.id })
        assertEquals(
            listOf("pro", "multi", "npu"),
            WhisperCatalog.pickableFor(setOf("npu")).map { it.id },
        )
        // 4.3 RE-SPEC — the owner's ruling, at the one place the lineup is built. A device that
        // can be offered `npu-turbo` is offered THAT AND NOTHING ELSE: not the 190 MB CPU tiers,
        // not the 358 MB `npu`. "Users shouldn't even see the 190 megabyte model or even the 358
        // megabyte model." The two rows below asserted the pre-4.3 menu and now assert the answer.
        assertEquals(
            listOf("npu-turbo"),
            WhisperCatalog.pickableFor(setOf("npu-turbo")).map { it.id },
        )
        assertEquals(
            listOf("npu-turbo"),
            WhisperCatalog.pickableFor(setOf("npu", "npu-turbo")).map { it.id },
        )
        // ...and the CPU tiers are no longer a subset of a capable device's lineup, which is the
        // whole change stated as the assertion it deletes.
        assertFalse(WhisperCatalog.pickableFor(setOf("npu", "npu-turbo")).containsAll(WhisperCatalog.pickable))
    }

    // --------------------------------------------------------- 4.3: one tier per device
    //
    // The owner's ruling, 2026-08-30: "If a phone is powerful enough with the NPU, we should only
    // support the multilingual v3 turbo... The only time we should show the 190 megabyte model is
    // if a user doesn't have an NPU." Three properties carry the whole branch — the non-capable
    // fleet is untouched, an existing install is not disturbed, and `npu` is HIDDEN, not retired.

    /**
     * **THE NON-CAPABLE BYTE-IDENTITY PROOF.** Not asserted in prose and not pinned as source: the
     * pre-4.3 body is written out below and the two are executed against each other over the
     * WHOLE input space a device that cannot be offered turbo can present — every offer set that
     * does not name `npu-turbo` (which is every non-capable device's answer, both producers
     * returning `emptySet()` off the census or on a failed probe) crossed with every installed
     * state, including installed states naming gated and retired tiers.
     *
     * The mutation it closes is the whole branch landing on the wrong fleet: a 4.3 rule that
     * filtered unconditionally would take the 190 MB tiers away from precisely the users the owner
     * ruled they are FOR — "if they don't have an NPU, that's the best model they can get".
     */
    @Test fun the_gate_fail_lineup_is_byte_identical_to_the_pre_4_3_construction() {
        val nonCapableOfferSets = listOf(
            emptySet(),
            setOf("npu"),
            setOf("ultra"),
            setOf("eco", "nope"),
            setOf("npu", "ultra", "nope"),
            setOf("NPU-TURBO"),
            setOf("npu-turbo-x"),
        )
        val installedStates = listOf(
            emptySet(),
            setOf("pro"),
            setOf("multi"),
            setOf("eco"),
            setOf("pro", "multi", "eco", "base"),
            setOf("npu"),
            setOf("npu", "npu-turbo"),
            WhisperCatalog.entries.map { it.id }.toSet(),
        )
        nonCapableOfferSets.forEach { offered ->
            // The pre-4.3 body, verbatim — the expression `pickableFor` returned before this
            // branch existed, and the one it still returns from its first line.
            val preChange = WhisperCatalog.entries.filter {
                !it.retired && (!it.gated || it.id in offered)
            }
            installedStates.forEach { installed ->
                assertEquals(
                    "$offered/$installed: the gate-fail path's set construction CHANGED",
                    preChange,
                    WhisperCatalog.pickableFor(offered, installed),
                )
                // ...and the installed set cannot reach it at all: a non-capable device's lineup
                // is a function of the offer set alone, exactly as it has always been.
                assertEquals(
                    "$offered/$installed: the installed set leaked into the gate-fail lineup",
                    WhisperCatalog.pickableFor(offered),
                    WhisperCatalog.pickableFor(offered, installed),
                )
                // The ordering surface too, at every locale the 3.7 rules distinguish.
                listOf("en-US", "bn-BD", "zh-Hans-CN", "").forEach { tag ->
                    assertEquals(
                        "'$tag'/$offered/$installed: the gate-fail ORDER changed",
                        ModelTierCopy.orderedForLanguageTagFor(tag, offered),
                        ModelTierCopy.orderedForLanguageTagFor(tag, offered, installed),
                    )
                }
            }
        }
        // And the two device-independent spellings still agree, which is what keeps the whole
        // ungated fleet — the overwhelming majority of installs — on one rule.
        listOf("en-US", "bn-BD", "de-AT", "").forEach { tag ->
            assertEquals(
                ModelTierCopy.orderedForLanguageTag(tag),
                ModelTierCopy.orderedForLanguageTagFor(tag, emptySet(), setOf("pro", "multi")),
            )
        }
    }

    /**
     * **EXISTING INSTALLS ARE NOT DISTURBED.** A capable device already running `multi` or `npu`
     * keeps its card — the chooser offers turbo GOING FORWARD, it does not repossess a model the
     * user already downloaded. (Deleting a gigabyte someone paid bandwidth for is not ours to do.)
     */
    @Test fun a_capable_device_keeps_the_card_for_a_model_it_already_has() {
        val capable = setOf("npu", "npu-turbo")
        // Fresh capable install: exactly one card, in every locale. The spec's device acceptance.
        assertEquals(listOf("npu-turbo"), WhisperCatalog.pickableFor(capable).map { it.id })
        // The 190 MB CPU tier already on disk: still there, and turbo still leads.
        assertEquals(
            listOf("multi", "npu-turbo"),
            WhisperCatalog.pickableFor(capable, setOf("multi")).map { it.id },
        )
        assertEquals(
            listOf("npu-turbo", "multi"),
            ModelTierCopy.orderedForLanguageTagFor("bn-BD", capable, setOf("multi")),
        )
        assertEquals(
            listOf("npu-turbo", "multi"),
            ModelTierCopy.orderedForLanguageTagFor("en-US", capable, setOf("multi")),
        )
        // The 358 MB npu pair already imported: same promise, and the L9 runner-up key still puts
        // it directly below the pick.
        assertEquals(
            listOf("npu-turbo", "npu"),
            ModelTierCopy.orderedForLanguageTagFor("bn-BD", capable, setOf("npu")),
        )
        // Both, plus pro — everything the user has, nothing they do not.
        assertEquals(
            listOf("npu-turbo", "npu", "pro"),
            ModelTierCopy.orderedForLanguageTagFor("en-US", capable, setOf("npu", "pro")),
        )
        // A RETIRED tier on disk does NOT re-enter through this door: `!it.retired` runs first,
        // which is why the screens may stat the whole catalog for the fallback question.
        assertEquals(
            listOf("npu-turbo"),
            WhisperCatalog.pickableFor(capable, setOf("eco", "base", "extreme", "ultra"))
                .map { it.id },
        )
        // Nothing here selects anything: the branch changes what is OFFERED, never what is
        // chosen. The default fallback and the migration target are untouched.
        assertEquals("pro", WhisperCatalog.DEFAULT_MODEL_ID)
        assertEquals("multi", ModelMigration.targetIdFor(ModelScope.MULTILINGUAL))
        // And every tier a user could already be ON still RESOLVES, so `installedModel()` never
        // returns null and nobody is force-marched into onboarding with a model on disk.
        listOf("eco", "base", "pro", "extreme", "multi", "ultra", "npu", "npu-turbo").forEach {
            assertNotNull("selected tier '$it' stopped resolving", WhisperCatalog.byId(it))
        }
    }

    /**
     * **`npu` IS HIDDEN, NOT RETIRED** — the constraint a future cleanup must not be able to
     * violate quietly. The streaming arc (partials on small, finals on turbo) needs the tier, and
     * every piece of machinery that carries it is asserted here rather than assumed: the catalog
     * row, the spec table, the import pair list, and all four census families' measured artifacts.
     * A "tidy-up" that deleted the tier because no chooser renders it any more fires here.
     */
    @Test fun the_npu_tier_stays_catalogued_and_fully_machined_while_no_chooser_offers_it() {
        val npu = WhisperCatalog.byId("npu")
        assertNotNull("npu left the catalog — hiding is not retiring", npu)
        assertFalse("npu must NOT be marked retired: it is hidden by the offer rule", npu!!.retired)
        assertFalse("nor unsupported — nobody is being migrated off it", npu.unsupported)
        assertTrue("it is still the gated, device-decides tier it has always been", npu.gated)
        assertNotNull("and still a PAIR — the import machinery's subject", npu.pairedArtifact)
        // Hidden from a capable chooser...
        assertFalse(
            "npu must not be offered on a device that can be offered turbo",
            WhisperCatalog.pickableFor(setOf("npu", "npu-turbo")).map { it.id }.contains("npu"),
        )
        // ...unless it is on disk, which is the non-disturbance rule, not an offer.
        assertTrue(
            WhisperCatalog.pickableFor(setOf("npu", "npu-turbo"), setOf("npu"))
                .map { it.id }.contains("npu"),
        )
        // ...and STILL offered where turbo is not, which is the state the census makes
        // unreachable today and which the rule must nonetheless answer correctly.
        assertTrue(WhisperCatalog.pickableFor(setOf("npu")).map { it.id }.contains("npu"))
        // The machinery, untouched: spec row, copy, import list, and every measured pack.
        assertNotNull("the streaming arc's spec row", NpuModelSpec.forTier("npu"))
        assertNotNull("a hidden tier still needs its card copy", ModelTierCopy.forId("npu"))
        assertTrue("the importer still knows the pair", NpuAssetImport.PAIRED_TIER_IDS.contains("npu"))
        NpuFleetCensus.families.forEach { family ->
            assertNotNull(
                "the census lost ${family.id}'s npu artifact — the pack machinery is untouched " +
                    "by 4.3, which hides a tier from the chooser and nothing else",
                NpuFleetCensus.artifactFor(family.id, "npu"),
            )
        }
    }

    /**
     * 4.3 — the CPU-fallback predicate, lifted out of `WhisperModelManager.isMelDonorEligible` so
     * the decline card and the backend ask ONE question. Clause for clause, the manager's own.
     */
    @Test fun the_cpu_fallback_predicate_admits_every_80_bin_ggml_and_nothing_else() {
        fun eligible(id: String) = WhisperCatalog.isCpuFallbackEligible(WhisperCatalog.byId(id)!!)
        // Retired tiers are eligible ON PURPOSE: an installed eco or base is a real fallback.
        listOf("eco", "base", "pro", "extreme", "multi").forEach {
            assertTrue("'$it' is an 80-bin ggml and must be a legal fallback", eligible(it))
        }
        // ultra by NAME (128-bin filterbank, refused by bin count); the npu class structurally.
        assertFalse("ultra is 128-bin — pcmToMel refuses it", eligible("ultra"))
        assertFalse("npu is a QAIRT context binary, not a ggml", eligible("npu"))
        assertFalse("and so is npu-turbo", eligible("npu-turbo"))
        // The set question the card asks.
        assertFalse("nothing installed, nothing to fall back to", WhisperCatalog.hasCpuFallback(emptySet()))
        assertFalse(
            "THE 4.3 STATE: turbo alone on a capable device has nothing to fall back INTO — " +
                "this false is what the decline card must say out loud instead of assuming",
            WhisperCatalog.hasCpuFallback(setOf("npu-turbo")),
        )
        assertFalse("both npu-class pairs are still not a ggml", WhisperCatalog.hasCpuFallback(setOf("npu", "npu-turbo")))
        assertFalse("an installed ultra cannot serve the 80-bin arm", WhisperCatalog.hasCpuFallback(setOf("ultra")))
        assertTrue(WhisperCatalog.hasCpuFallback(setOf("multi")))
        assertTrue(WhisperCatalog.hasCpuFallback(setOf("pro")))
        assertTrue("a retired-but-installed tier answers yes", WhisperCatalog.hasCpuFallback(setOf("eco")))
        assertTrue(WhisperCatalog.hasCpuFallback(setOf("npu-turbo", "multi")))
        assertFalse("an unresolvable id admits nothing", WhisperCatalog.hasCpuFallback(setOf("nope")))
    }

    /**
     * The one string the 4.3 rule turns on, and where it comes from. A literal here would be a
     * fourth spelling of a tier id that already has one home.
     */
    @Test fun the_one_offered_tier_is_turbos_own_id_from_the_spec_table() {
        assertEquals("npu-turbo", WhisperCatalog.ONE_TIER_ID)
        assertEquals(NpuModelSpec.TURBO.tierId, WhisperCatalog.ONE_TIER_ID)
        assertNotNull(WhisperCatalog.byId(WhisperCatalog.ONE_TIER_ID))
        assertTrue(
            "the one offered tier must be a GATED one, or the rule would fire on a device that " +
                "never passed a gate at all",
            WhisperCatalog.byId(WhisperCatalog.ONE_TIER_ID)!!.gated,
        )
    }

    @Test fun an_id_in_the_offer_set_never_resurrects_a_retired_or_unknown_tier() {
        // The set is the caller's GATE answer, not a general admission list: `!it.retired` still
        // applies first, so a retired id in the set changes nothing, and an id the catalog cannot
        // resolve admits nothing at all.
        assertEquals(WhisperCatalog.pickable, WhisperCatalog.pickableFor(setOf("ultra")))
        assertEquals(WhisperCatalog.pickable, WhisperCatalog.pickableFor(setOf("eco", "nope")))
        assertEquals(
            listOf("pro", "multi", "npu"),
            WhisperCatalog.pickableFor(setOf("npu", "ultra", "nope")).map { it.id },
        )
    }

    // ------------------------------------------------ 4.0 Q7b fix round: the download refusal

    @Test fun only_a_single_artefact_tier_can_be_installed_by_download() {
        // download() enqueues ONE request for `url` and writes ONE file, `fileName`; it never
        // reads pairedArtifact. So a paired tier is not merely "unlikely to work" — it cannot be
        // installed by that path at all, and trying destroys the file already at `fileName`.
        WhisperCatalog.entries.forEach { model ->
            assertEquals(
                "tier '${model.id}': downloadability must track the artefact COUNT, which is the " +
                    "thing download() cannot handle",
                model.pairedArtifact == null,
                WhisperCatalog.isInstallableByDownload(model),
            )
        }
        // Stated concretely for today's catalog so the general rule above cannot go vacuous.
        assertFalse(WhisperCatalog.isInstallableByDownload(WhisperCatalog.byId("npu")!!))
        assertFalse(WhisperCatalog.isInstallableByDownload(WhisperCatalog.byId("npu-turbo")!!))
        assertTrue(WhisperCatalog.isInstallableByDownload(WhisperCatalog.byId("pro")!!))
        assertTrue(WhisperCatalog.isInstallableByDownload(WhisperCatalog.byId("multi")!!))
        assertEquals(
            "exactly two tiers are refused today, and they are the paired ones — this census " +
                "fired when npu-turbo arrived (4.1), exactly as designed",
            listOf("npu", "npu-turbo"),
            WhisperCatalog.entries.filterNot { WhisperCatalog.isInstallableByDownload(it) }
                .map { it.id },
        )
    }

    @Test fun downloadability_tracks_the_artefact_count_and_not_the_device_gate() {
        // On TODAY's catalog `!gated` and `pairedArtifact == null` agree on all eight tiers — npu
        // and npu-turbo are the only tiers that are either, and each is both (two paired gated
        // tiers since 4.1 L5). So the census above CANNOT tell the two
        // candidate predicates apart, and swapping one for the other is an equivalent mutation
        // that a full-suite battery would report as a survivor. The choice only shows up on a tier
        // that does not exist yet, which is exactly when nobody will be reading the comment that
        // explains it. Both shapes are therefore constructed here, so the decision is PINNED
        // rather than coincidentally right.
        val gatedSingleFile = WhisperCatalog.byId("pro")!!.copy(id = "future-gated", gated = true)
        assertTrue(
            "a GATED single-file tier is still perfectly downloadable. `gated` answers 'may this " +
                "device be offered it', which is a different question from 'can download() " +
                "install it' — keying the refusal on `gated` would block a future tier that " +
                "downloads fine",
            WhisperCatalog.isInstallableByDownload(gatedSingleFile),
        )
        val ungatedPair = WhisperCatalog.byId("multi")!!.copy(
            id = "future-pair",
            pairedArtifact = WhisperCatalog.byId("npu")!!.pairedArtifact,
        )
        assertFalse(
            "an UNGATED two-artefact tier is NOT downloadable, and this is the dangerous half: " +
                "download() writes one file and never reads pairedArtifact, so keying the refusal " +
                "on `gated` would let it through — deleting the first file and failing on the " +
                "second, which is C1 all over again on a tier nobody thought to gate",
            WhisperCatalog.isInstallableByDownload(ungatedPair),
        )
    }

    @Test fun the_refusal_names_the_tier_and_both_files_it_actually_needs() {
        // The reader of this line is working out what to do INSTEAD, so "import these two" has to
        // be in it. Asserted here because download() needs a Context and no JVM test reaches it.
        val npu = WhisperCatalog.byId("npu")!!
        val reason = WhisperCatalog.notInstallableByDownloadReason(npu)
        assertTrue("the refusal does not name the tier", reason.contains("'npu'"))
        assertTrue("the refusal does not name the encoder", reason.contains(npu.fileName))
        assertTrue(
            "the refusal does not name the paired decoder",
            reason.contains(npu.pairedArtifact!!.fileName),
        )
        assertTrue(
            "the refusal must say import, not download",
            reason.contains("installs by import, not download"),
        )
        assertFalse("the refusal must never be a bare boolean stringified", reason == "false")
    }

    // ------------------------------------------------------------ 4.1 L5: the npu-turbo tier

    @Test fun the_npu_turbo_tier_states_the_asset_pair_it_actually_needs() {
        // Every value MEASURED (the plan's asset block, 2026-08-29): both digests streamed out of
        // the local vendor zip, both lengths read from its entry table. No placeholder ships —
        // the 4.0 I6 rule, applied to the second pair.
        val turbo = WhisperCatalog.byId("npu-turbo")!!
        assertEquals(ModelScope.MULTILINGUAL, turbo.scope)   // large-v3-turbo: 100 languages
        assertEquals(0L, turbo.minRamBytes)                  // the SoC gate is the real gate
        // OWNER-PENDING and pinned for the same reason npu's is: nothing else in app/src names
        // this string, so without this line it could be reworded — or emptied — with a green
        // suite. The parenthetical names the underlying model, mirroring every other tier.
        assertEquals("Multilingual on NPU (large-v3-turbo)", turbo.displayName)
        assertTrue(turbo.gated)
        assertFalse(turbo.retired)
        assertFalse(turbo.unsupported)
        // The REPACKED names. The vendor zip's entries carry the SAME bare names as the 4.0 npu
        // tier's installed files, in the same models directory — importing them un-renamed would
        // overwrite the owner's 358 MB pair, which is why the catalog states `turbo_*`.
        assertEquals("turbo_encoder_qairt_context.bin", turbo.fileName)
        assertEquals("f7d11c08a20ea671f59b3ace2f9421da00b06170ac9fe946f29092ee59be6bbe", turbo.sha256)
        assertEquals(775_831_552L, turbo.primaryBytes)

        val decoder = turbo.pairedArtifact!!
        assertEquals("turbo_decoder_qairt_context.bin", decoder.fileName)
        assertEquals("c19b067766180843fca6266531605bf037820c5e5ae178bd6dc03785df4c6ae4", decoder.sha256)
        assertEquals(295_854_080L, decoder.approxBytes)

        // The advertised size is the PAIR: what the user installs, stores, and reads on the badge.
        assertEquals(1_071_685_632L, turbo.approxBytes)
        assertEquals(turbo.approxBytes, turbo.primaryBytes + decoder.approxBytes)
        // Both files come out of one archive, so one URL is the honest answer for both.
        assertEquals(turbo.url, decoder.url)
    }

    @Test fun the_turbo_id_is_the_spec_tables_own_tier_id() {
        // The string has ONE home (4.1 L4 handoff): `NpuModelSpec.TURBO.tierId`. The catalog
        // entry resolves through the row's own field, so the two cannot disagree by construction
        // — and this pin states the value, so the field cannot drift either. Everything keyed on
        // the id — forTier, the mel-donor auto-exclusion, the L8 routing re-spec — reads this
        // exact string.
        assertEquals("npu-turbo", NpuModelSpec.TURBO.tierId)
        assertEquals(NpuModelSpec.TURBO.tierId, WhisperCatalog.byId("npu-turbo")!!.id)
    }

    @Test fun the_spec_table_and_the_gated_flag_agree_tier_by_tier() {
        // Both directions are load-bearing (the L3/L4 handoffs). A gated tier WITHOUT a spec row
        // could never construct the NPU backend — the constructor takes a spec and has no
        // default. A ggml tier WITH one would silently stop being a mel donor and a CPU fallback,
        // because `isMelDonorEligible` keys its npu-class exclusion on `forTier` — an accidental
        // row for `pro` would remove the English flagship from the donor pool with a green suite.
        WhisperCatalog.entries.forEach { model ->
            assertEquals(
                "tier '${model.id}': `gated` and `NpuModelSpec.forTier` must agree — every " +
                    "gated npu-class tier has a spec row, and no ggml tier may ever gain one",
                model.gated,
                NpuModelSpec.forTier(model.id) != null,
            )
        }
    }

    @Test fun the_turbo_encoder_is_size_gated_against_its_own_bytes_not_the_pairs() {
        // The same reason primaryBytes exists at all (Q7a R14, restated for the second pair): the
        // turbo encoder is ~28 % under the pair's advertised sum, so gating it against
        // approxBytes reads "not installed" forever, whatever the owner provisioned.
        val turbo = WhisperCatalog.byId("npu-turbo")!!
        assertFalse(
            "the encoder's own length must NOT satisfy the pair's advertised size",
            WhisperCatalog.sizeWithinTolerance(turbo.primaryBytes, turbo.approxBytes),
        )
        assertTrue(WhisperCatalog.sizeWithinTolerance(turbo.primaryBytes, turbo.primaryBytes))
        // And the two halves are 2.6x apart, so a transposed gate cannot quietly pass either.
        assertFalse(
            "the decoder's length must not satisfy the encoder's gate",
            WhisperCatalog.sizeWithinTolerance(turbo.pairedArtifact!!.approxBytes, turbo.primaryBytes),
        )
    }

    @Test fun both_gated_tiers_record_the_vendor_zip_they_actually_came_from() {
        // Q7a M2, folded here: the URL on a gated tier is provenance — the ONLY record of where
        // these bytes came from — and it was unpinned. Pinned by endsWith on the vendor path
        // (model id, release version, runtime, precision, chipset): the bucket host is the
        // vendor's to move, the release path is the identity.
        val npu = WhisperCatalog.byId("npu")!!
        assertTrue(
            "npu's URL must record the whisper_small_quantized v0.61.0 8gen3 release, got: ${npu.url}",
            npu.url.endsWith(
                "/qai-hub-models/models/whisper_small_quantized/releases/v0.61.0/" +
                    "whisper_small_quantized-precompiled_qnn_onnx-w8a16-qualcomm_snapdragon_8gen3.zip",
            ),
        )
        val turbo = WhisperCatalog.byId("npu-turbo")!!
        assertTrue(
            "npu-turbo's URL must record the whisper_large_v3_turbo_quantized v0.61.0 8gen3 " +
                "release, got: ${turbo.url}",
            turbo.url.endsWith(
                "/qai-hub-models/models/whisper_large_v3_turbo_quantized/releases/v0.61.0/" +
                    "whisper_large_v3_turbo_quantized-precompiled_qnn_onnx-w8a16-" +
                    "qualcomm_snapdragon_8gen3.zip",
            ),
        )
        // Each pair's two entries share their archive — pinned per tier so a paired artefact can
        // never point at a different provenance than its primary.
        assertEquals(npu.url, npu.pairedArtifact!!.url)
        assertEquals(turbo.url, turbo.pairedArtifact!!.url)
    }

    @Test fun the_turbo_refusal_names_the_tier_and_both_files_it_actually_needs() {
        // The same claim the npu refusal test makes, for the tier whose files are ~3x the size:
        // the reader of this line is working out what to do INSTEAD, and "import these two" has
        // to be in it. Asserted here because download() needs a Context no JVM test has.
        val turbo = WhisperCatalog.byId("npu-turbo")!!
        val reason = WhisperCatalog.notInstallableByDownloadReason(turbo)
        assertTrue("the refusal does not name the tier", reason.contains("'npu-turbo'"))
        assertTrue("the refusal does not name the encoder", reason.contains("turbo_encoder_qairt_context.bin"))
        assertTrue("the refusal does not name the paired decoder", reason.contains("turbo_decoder_qairt_context.bin"))
        assertTrue(
            "the refusal must say import, not download",
            reason.contains("installs by import, not download"),
        )
    }
}
