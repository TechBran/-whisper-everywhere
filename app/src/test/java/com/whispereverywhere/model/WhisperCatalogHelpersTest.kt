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
        // 4.0: 6 -> 7; 4.1: 7 -> 8; 4.6: 8 -> 13. The census pin fired for `npu`, again for
        // `npu-turbo`, and again for the five rungs of the instrument ladder — exactly as
        // designed, and resolved here rather than relaxed: the whole point of the pin is that a
        // tier cannot arrive unannounced.
        //
        // **ORDER IS LOAD-BEARING SINCE 4.6, and it is no longer arrival order.** `entries` is
        // what the chooser renders for every tier no steer key names
        // (`ModelTierCopy.orderedForLanguageTagFor` sorts stably over this list), so the ggml rows
        // are grouped BY MODEL FAMILY with each quantisation twin beside its sibling — small,
        // medium, turbo, large-v3. That is a measurement property, not decoration: the two
        // comparisons that carry the most information are small Q5_1 vs Q8_0 and medium Q5_0 vs
        // Q8_0, and ascending BYTES would have separated both (medium-Q8's 823 MB above turbo-Q5's
        // 574 MB). The three retired English rows keep the front in historical order, where
        // nothing renders them; both npu-class tiers keep the last two slots because they are
        // GATED — for them the device decides, not the size.
        val ids = WhisperCatalog.entries.map { it.id }
        assertEquals(13, WhisperCatalog.entries.size)
        assertEquals(
            listOf(
                "eco", "base", "pro", "extreme",
                "multi", "small-q8",
                "medium-q5", "medium-q8",
                "ultra", "ultra-q8",
                "large-v3",
                "npu", "npu-turbo",
            ),
            ids,
        )
    }

    /**
     * 4.6 — **the quantisation twins are ADJACENT, stated as the property rather than as a list
     * someone edited to match.** Each pair is the same model at two quantisations, and the session
     * that compares them has to find them side by side; an id reordered into the wrong slot passes
     * the list above only if the list was edited too, and passes nothing here.
     */
    @Test fun each_quantisation_twin_is_declared_beside_its_sibling() {
        val ids = WhisperCatalog.entries.map { it.id }
        listOf(
            "multi" to "small-q8",      // whisper small: Q5_1 / Q8_0 — the cheapest decisive test
            "medium-q5" to "medium-q8", // whisper medium: Q5_0 / Q8_0 — does the repack path rescue it
            "ultra" to "ultra-q8",      // large-v3-turbo: Q5_0 / Q8_0
        ).forEach { (a, b) ->
            assertEquals(
                "'$a' and '$b' are the same weights at two quantisations and must render as " +
                    "neighbours, or the comparison the session exists for is two scrolls apart",
                ids.indexOf(a) + 1,
                ids.indexOf(b),
            )
        }
        // And each twin really is the same model: the byte counts differ, the file names differ
        // only in the quantisation token, and the mel width — the one structural fact the
        // catalogue records about the weights — is identical.
        listOf("multi" to "small-q8", "medium-q5" to "medium-q8", "ultra" to "ultra-q8").forEach { (a, b) ->
            val ma = WhisperCatalog.byId(a)!!
            val mb = WhisperCatalog.byId(b)!!
            assertEquals("'$a'/'$b' must be the same model family — same filterbank", ma.melBins, mb.melBins)
            assertEquals("'$a'/'$b' must be the same language coverage", ma.scope, mb.scope)
            assertTrue("'$a'/'$b' must be different files", ma.sha256 != mb.sha256)
            assertTrue("the Q8_0 twin is the larger file", mb.approxBytes > ma.approxBytes)
        }
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
        // 4.6: was 7.0e9 (8 GB-class after totalMem slack), set in 3.7 when `ultra` was a retired
        // outlier. It became an INSTRUMENT, and an instrument states NO RAM threshold: it is
        // unrecommended for a reason that is not a fact about the device in the user's hand, and a
        // gate here would render the card as "needs more RAM than this device reports" on phones
        // where that is false. 4.7 retired it again; a retired row's threshold gates nothing, so
        // the 0 stays.
        assertEquals(0L, m("ultra").minRamBytes)

        // 4.6's five new rungs: every one MULTILINGUAL (the owner's ruling — "we should really
        // only be showing only multi language models, period").
        listOf("small-q8", "medium-q5", "medium-q8", "ultra-q8", "large-v3").forEach {
            assertEquals("rung '$it' must be multilingual", ModelScope.MULTILINGUAL, m(it).scope)
        }
        // 4.7 — RAM thresholds after the Q8 ruling. `small-q8` is the floor for every device and
        // states none; `ultra-q8` is the one instrument left and states none (the flag is the
        // reason, not the device); the retired rows gate nothing. `medium-q8` is the one rung with
        // a threshold. 4.8.0: it is THE OWNER'S NUMBER — "four point five gigs minimum"
        // (2026-09-17) — no longer 4.7's provisional 5.5e9 carried from the `extreme` precedent.
        // 4.5e9 sits between a nominal 4 GB phone's totalMem (~3.7e9) and a 6 GB one's (~5.6e9).
        listOf("small-q8", "medium-q5", "ultra-q8", "large-v3").forEach {
            assertEquals("rung '$it' claims no RAM class", 0L, m(it).minRamBytes)
        }
        assertEquals("medium-q8 is recommended above the owner's 4.5 GB", 4_500_000_000L, m("medium-q8").minRamBytes)
        assertTrue("...and it no longer inherits the extreme precedent", m("extreme").minRamBytes != m("medium-q8").minRamBytes)
        // ...and it is the SAME number the first-run gate reads, so the badge and the lineup agree.
        assertEquals(
            com.whispereverywhere.ui.onboarding.OnboardingLogic.FIRST_RUN_RAM_GATE_BYTES,
            m("medium-q8").minRamBytes,
        )
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

    /**
     * 4.6 — **every rung of the instrument ladder, pinned as its neighbours are**: id, file name,
     * URL at the pinned commit, exact byte count, digest, scope, mel width, the instrument flag,
     * and the retired/gated/unsupported flags that must all be false.
     *
     * **Where every number came from, and how.** Both halves of each row were read TWICE,
     * independently, on 2026-09-13:
     *  1. the git-LFS pointer at the commit `WhisperCatalog.BASE_URL` pins —
     *     `huggingface.co/ggerganov/whisper.cpp/raw/5359861c…/<file>` — whose `oid sha256:` line IS
     *     the digest of the LFS content and whose `size` line is the byte count; and
     *  2. a HEAD of the download URL itself, whose redirect carries `X-Linked-Size` and
     *     `X-Linked-ETag` equal to exactly those two values.
     *
     * The method was validated against two rows ALREADY in the catalogue before any new literal
     * was trusted — `ggml-small-q5_1.bin` → 190,085,487 / ae85e4a9… and
     * `ggml-large-v3-turbo-q5_0.bin` → 574,041,195 / 39422170… — and reproduced both exactly.
     *
     * Mel widths and every structural claim in the cards' copy (encoder depth, dims, text layers,
     * vocabulary size, quantisation) are read from each file's OWN ggml header: `n_mels` is the
     * tenth int32 and `ftype` the twelfth, so a 48-byte RANGE READ settles a rung without
     * downloading a gigabyte. **A wrong literal here ships a rung that can never install** (the
     * gate is ±5% on size and exact on the digest), which is why the numbers are read and not
     * copied — and `medium` vs `medium.en` is the live trap: 539,212,467 against 539,225,533, two
     * different files 13,066 bytes apart, well inside each other's ±5% window, so only the digest
     * would catch the mix-up.
     */
    @Test fun every_ladder_rung_states_its_twice_verified_lfs_values() {
        val base = "https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1/"
        // 4.7 — the three flags the Q8 ruling of 2026-09-17 set per rung, pinned beside the LFS
        // values: `retired` for every Q5 rung, `instrument` for the one Q8 rung that kept up
        // without margin, and the provisional RAM threshold on the medium tier.
        data class Rung(
            val id: String,
            val displayName: String,
            val file: String,
            val bytes: Long,
            val sha: String,
            val mels: Int,
            val retired: Boolean,
            val instrument: Boolean,
            val minRam: Long,
        )
        listOf(
            // whisper small, 12 encoder layers at 768 dims, n_vocab 51865, ftype 2007 = Q8_0.
            // THE DECISIVE ONE, decided: the same model as the 4.6 default, 40% larger, measured
            // 2.2x faster per commit beside it on the Tab S10+. The 4.7 default and floor.
            Rung(
                "small-q8", "Multilingual (small, Q8_0)", "ggml-small-q8_0.bin", 264_464_607L,
                "49c8fb02b65e6049d5fa6c04f81f53b867b5ec9540406812c643f177317f779f", 80,
                retired = false, instrument = false, minRam = 0L,
            ),
            // whisper medium, 24 encoder layers at 1024, n_vocab 51865, ftype 1008 = Q5_0. The
            // multilingual medium the app had never had — and the rung that NEVER caught up on
            // the tablet (9,294 ms median against an 8 000 ms floor). Retired.
            Rung(
                "medium-q5", "Multilingual (medium, Q5_0)", "ggml-medium-q5_0.bin", 539_212_467L,
                "19fea4b380c3a618ec4723c3eef2eb785ffba0d0538cf43f8f235e7b3b34220f", 80,
                retired = true, instrument = false, minRam = 0L,
            ),
            // The same medium, ftype 2007 = Q8_0. Identical hyperparameters off the header;
            // 1,341 ms median on the tablet. THE MEDIUM TIER, recommended above the owner's
            // 4.5e9 (4.8.0, ruling 2026-09-17; 4.7 carried a provisional 5.5e9).
            Rung(
                "medium-q8", "Multilingual (medium, Q8_0)", "ggml-medium-q8_0.bin", 823_369_779L,
                "42a1ffcbe4167d224232443396968db4d02d4e8e87e213d3ee2e03095dea6502", 80,
                retired = false, instrument = false, minRam = 4_500_000_000L,
            ),
            // large-v3-turbo, 32 encoder layers at 1280, 4 text layers, n_vocab 51866, ftype
            // 2007 = Q8_0. 128-bin, like everything in the large-v3 family. Kept up with NO
            // margin on the tablet (worst 7,930 against 8 000): the optional top rung, and the
            // one instrument left.
            Rung(
                "ultra-q8", "Ultra (large-v3-turbo, Q8_0)", "ggml-large-v3-turbo-q8_0.bin", 874_188_075L,
                "317eb69c11673c9de1e1f0d459b253999804ec71ac4c23c17ecf5fbe24e259a1", 128,
                retired = false, instrument = true, minRam = 0L,
            ),
            // large-v3 itself: the same 32-layer/1280-dim encoder plus a FULL 32-layer decoder,
            // ftype 2008 = Q5_0. The largest file the app can fetch. Retired untimed.
            Rung(
                "large-v3", "Multilingual (large-v3, Q5_0)", "ggml-large-v3-q5_0.bin", 1_081_140_203L,
                "d75795ecff3f83b5faa89d1900604ad8c780abd5739fae406de19f23ecd98ad1", 128,
                retired = true, instrument = false, minRam = 0L,
            ),
        ).forEach { r ->
            val m = WhisperCatalog.byId(r.id)!!
            assertEquals(r.id, r.displayName, m.displayName)
            assertEquals(r.id, r.file, m.fileName)
            assertEquals(r.id, base + r.file, m.url)
            assertEquals("${r.id}: the exact LFS size — a wrong literal cannot install", r.bytes, m.approxBytes)
            assertEquals("${r.id}: the LFS oid, lowercased hex", r.sha, m.sha256)
            assertEquals("${r.id}: the header's own n_mels", r.mels, m.melBins)
            assertEquals("${r.id}: the owner's ruling — multilingual rungs only", ModelScope.MULTILINGUAL, m.scope)
            assertEquals("${r.id}: the instrument flag, per the Q8 ruling", r.instrument, m.instrument)
            assertEquals("${r.id}: retired iff Q5, per the Q8 ruling", r.retired, m.retired)
            assertFalse("${r.id}: nobody is migrated off a rung that was never advocated, retired or not", m.unsupported)
            assertFalse("${r.id}: no device is refused a CPU rung by a gate", m.gated)
            assertEquals("${r.id}: the RAM threshold", r.minRam, m.minRamBytes)
            assertNull("${r.id}: one file", m.pairedArtifact)
            assertEquals("${r.id}: a single-file rung advertises its one file", m.approxBytes, m.primaryBytes)
        }
        // THE COPY-PASTE THIS PIN EXISTS FOR. `medium-q5` is 13,066 bytes from `extreme`, which is
        // 2.4% — inside the ±5% size gate in both directions — so a row that took the wrong number
        // would install nothing and the size gate would not be what caught it.
        val mediumQ5 = WhisperCatalog.byId("medium-q5")!!
        val extreme = WhisperCatalog.byId("extreme")!!
        assertTrue("medium and medium.en are different files", mediumQ5.approxBytes != extreme.approxBytes)
        assertTrue("and different weights", mediumQ5.sha256 != extreme.sha256)
        assertTrue(
            "the two sizes are inside each other's ±5% window, which is why the digest is the " +
                "real guard and why each row must state its OWN byte count",
            WhisperCatalog.sizeWithinTolerance(mediumQ5.approxBytes, extreme.approxBytes),
        )
        // And `large-v3` is the biggest thing the app can be asked to download.
        assertEquals(
            1_081_140_203L,
            WhisperCatalog.entries.filter { it.pairedArtifact == null }.maxOf { it.approxBytes },
        )
    }

    @Test
    fun modelById_returnsNull_forUnknownId() {
        assertNull(WhisperCatalog.byId("nope"))
    }

    @Test
    fun isRecommended_boundary_atMinRam() {
        // 4.6: the subject moved from `ultra` to `extreme`. `ultra` became an INSTRUMENT, whose
        // whole contract is that no RAM makes it recommended, so it can no longer demonstrate a
        // RAM BOUNDARY — the rule under test here. `extreme` still carries a real threshold
        // (5.5e9, genuine 6 GB-class after totalMem slack) and is still resolvable, so the `>=`
        // boundary is tested on a row that has one.
        //
        // 4.7: the boundary has a LIVE subject again — `medium-q8`, the medium tier. It is the
        // one PICKABLE rung with a threshold, so this is the row the chooser's "Recommended for
        // your device" badge actually turns on. 4.8.0: its threshold is the owner's 4.5e9, so the
        // two rows now demonstrate the `>=` boundary at two different numbers — each at its own.
        listOf("extreme" to 5_500_000_000L, "medium-q8" to 4_500_000_000L).forEach { (id, threshold) ->
            val model = WhisperCatalog.byId(id)!!
            assertEquals(id, threshold, model.minRamBytes)
            // just below -> not recommended
            assertFalse("$id just below", WhisperCatalog.isRecommendedForDevice(model, threshold - 1))
            // exactly at threshold -> recommended (>=)
            assertTrue("$id at the threshold", WhisperCatalog.isRecommendedForDevice(model, threshold))
            // above -> recommended
            assertTrue("$id above", WhisperCatalog.isRecommendedForDevice(model, 12_000_000_000L))
        }
        // The Tab S10+ the rung was measured on reports 12 GB; a nominal 4 GB phone (~3.7e9 as
        // totalMem reports it) is not told it is the right choice for it, and `small-q8` is
        // recommended there instead. A nominal 6 GB phone (~5.6e9) IS over the owner's gate —
        // which is the class 4.7's 5.5e9 would ALSO have admitted, and 4.5e9 admits with room.
        assertFalse(WhisperCatalog.isRecommendedForDevice(WhisperCatalog.byId("medium-q8")!!, 3_700_000_000L))
        assertFalse(WhisperCatalog.isRecommendedForDevice(WhisperCatalog.byId("medium-q8")!!, 4_000_000_000L))
        assertTrue(WhisperCatalog.isRecommendedForDevice(WhisperCatalog.byId("medium-q8")!!, 5_600_000_000L))
        assertTrue(WhisperCatalog.isRecommendedForDevice(WhisperCatalog.byId("small-q8")!!, 4_000_000_000L))
    }

    @Test
    fun isRecommended_zeroMinRam_alwaysRecommended() {
        val eco = WhisperCatalog.byId("eco")!!
        assertTrue(WhisperCatalog.isRecommendedForDevice(eco, 0L))
        assertTrue(WhisperCatalog.isRecommendedForDevice(eco, 2_000_000_000L))
    }

    // ------------------------------------------------------------- 4.6: the INSTRUMENT mechanism
    //
    // The owner has six devices and has chosen to MEASURE the CPU ladder rather than accept a
    // scaled prediction: *"I wanna see all the models there so I can just select between them and
    // try each one."* So a rung can be OFFERED without being ADVOCATED — an instrument a user can
    // pick up, never a path a user is led down. The precedent is `ModelTierCopy`'s own: 4.1
    // refused turbo a promotion while its accuracy claim was unproved and granted it only after an
    // on-device A/B. Same shape, one axis over — throughput instead of accuracy.
    //
    // These two tests pin the MECHANISM on constructed rows, before any catalogue row carries the
    // flag. That is deliberate: `minRamBytes` set above any shipping phone would ALSO make
    // `isRecommendedForDevice` answer false today, so a census over the real catalogue cannot tell
    // the two designs apart — and the difference shows on the 32 GB device nobody is testing on.

    /**
     * **An instrument is refused a recommendation at every RAM there is**, and the FLAG is what
     * refuses — not a threshold standing in for one.
     *
     * The rejected alternative is worth naming, because the research proposed it (§6.3: *"with
     * `minRamBytes` set above any shipping phone so no device is ever told it is recommended"*).
     * It fails twice. It is not even true — RAM keeps climbing and the literal would have to be
     * chased — and it is a LIE ON THE CARD: `OnboardingModelScreen` renders a RAM-gated,
     * unrecommended tier as *"High-end devices only — this tier needs more RAM than this device
     * reports"*, which on a 16 GB phone offered a 264 MB rung is simply false. The reason these
     * rungs are not recommended is that **nobody has measured them**, which is not a fact about
     * the device in the user's hand. So the flag says what is true and `minRamBytes` stays 0.
     */
    @Test fun an_instrument_is_never_recommended_at_any_ram_and_the_flag_is_what_refuses() {
        val everyRam = listOf(
            0L, 1_000_000_000L, 5_500_000_000L, 7_000_000_000L, 8_000_000_000L,
            12_000_000_000L, 16_000_000_000L, 24_000_000_000L, 64_000_000_000L, Long.MAX_VALUE,
        )
        val instrument = WhisperCatalog.byId("small-q8")!!.copy(id = "an-instrument", instrument = true)
        assertEquals("an instrument states no RAM threshold — it has nothing to claim", 0L, instrument.minRamBytes)
        everyRam.forEach { ram ->
            assertFalse(
                "an instrument was recommended at $ram bytes of RAM — no card may be badged " +
                    "'Recommended for your device' for a rung whose throughput nobody has measured",
                WhisperCatalog.isRecommendedForDevice(instrument, ram),
            )
        }
        // THE MUTATION THIS PIN EXISTS FOR: with the flag dropped, that same row — minRamBytes 0 —
        // is recommended on every device in the fleet. So the flag is load-bearing and the 0 is
        // not a second lock; a reader who deletes the `!instrument` clause breaks this line, not a
        // comment. (Belt-and-braces via a huge minRamBytes is what the KDoc above refuses.)
        val notAnInstrument = instrument.copy(instrument = false)
        everyRam.forEach { ram ->
            assertTrue(
                "the clause under test is `!instrument`: an ungated 0-RAM rung must still be " +
                    "recommended everywhere, or this test is passing for the wrong reason",
                WhisperCatalog.isRecommendedForDevice(notAnInstrument, ram),
            )
        }
    }

    /**
     * An instrument is **offered like any other rung**: the flag withholds the badge and nothing
     * else. It does not hide the card, does not gate the download, and does not survive into
     * `retired` — *"they appear in the chooser on every device, with no RAM threshold hiding
     * them. The owner must be able to run a heavy model on a modest phone: finding where it
     * breaks is the point."*
     */
    @Test fun the_instrument_flag_withholds_the_badge_and_touches_nothing_else() {
        val instrument = WhisperCatalog.byId("small-q8")!!.copy(id = "an-instrument", instrument = true)
        assertFalse("an instrument is not retired — a retired tier is not in the chooser at all", instrument.retired)
        assertFalse("nor unsupported: nobody is migrated off a rung they were invited to try", instrument.unsupported)
        assertFalse("nor gated: no device is refused the experiment", instrument.gated)
        assertTrue(
            "an instrument installs by ordinary download, like every other ggml rung",
            WhisperCatalog.isInstallableByDownload(instrument),
        )
        // A constructed row cannot be in `pickable` (that list is built from `entries`), so the
        // claim is made on the predicate `pickable` itself applies.
        assertTrue("an instrument clears the pickable filter", !instrument.retired && !instrument.gated)
    }

    /**
     * 4.6 declared **THE INSTRUMENT SET** as six rungs — the five the ladder added and `ultra`,
     * un-retired beside them — because nobody had timed any of them. 4.7 — **it is ONE rung.**
     * The Tab S10+ session of 2026-09-17 timed four of the six; the owner's Q8 ruling retired the
     * four Q5 rows (two of them untimed); `small-q8` and `medium-q8` kept up with margin and lost
     * the flag; `ultra-q8` kept up with NO margin (worst commit 0.99 of its floor on a flagship)
     * and keeps it. The flag now means what it always meant underneath: offered without a
     * throughput verdict that clears production. `TierThroughputTest` holds the coupling from
     * the record's side; this is the catalogue's side of the same fact.
     *
     * A new rung that forgets the flag fires here, which is the alarm worth having: the failure
     * mode is silent and its blast radius is a production user handed a 1 GB download badged
     * *"Recommended for your device"* on the strength of nothing.
     */
    @Test fun the_instrument_set_is_exactly_the_pickable_rungs_whose_verdict_does_not_clear() {
        assertEquals(
            "the instrument set changed — a rung was added without the flag, or a rung earned a " +
                "clearing verdict and nobody said so here",
            listOf("ultra-q8"),
            WhisperCatalog.instruments.map { it.id },
        )
        listOf("small-q8", "medium-q8").forEach {
            assertFalse(
                "'$it' must NOT be an instrument: it kept up with margin on the Tab S10+ " +
                    "(docs/measurements/2026-09-17-tab-cpu-ladder.md), which is exactly why the " +
                    "app may recommend it",
                WhisperCatalog.byId(it)!!.instrument,
            )
        }
        // The rung the 4.6 set was defined against is retired now, and a retired row is never an
        // instrument — an instrument is OFFERED, and a retired tier is not.
        assertFalse(WhisperCatalog.byId("multi")!!.instrument)
        WhisperCatalog.entries.filter { it.retired }.forEach {
            assertFalse("retired '${it.id}' cannot be an instrument", it.instrument)
        }
        // Derived, never a second list.
        assertEquals(WhisperCatalog.entries.filter { it.instrument }, WhisperCatalog.instruments)
        // THE COUPLING, from this side: the instruments are the pickable rungs whose verdict does
        // not clear, and nothing else.
        assertEquals(
            WhisperCatalog.pickable.filter { !TierThroughputRecord.forTier(it.id)!!.verdict.clearsProduction }.map { it.id },
            WhisperCatalog.instruments.map { it.id },
        )
    }

    /**
     * 4.6 — **no instrument is recommended on any device in the fleet**, asserted over the real
     * catalogue at every RAM a phone or tablet plausibly reports, and at `Long.MAX_VALUE` so the
     * claim cannot be outlived by hardware.
     *
     * This is the brief's own acceptance: *"`isRecommendedForDevice` must answer false for all of
     * them regardless of device RAM, so no card is ever badged as the right choice."*
     */
    @Test fun no_instrument_is_recommended_on_any_device_in_the_fleet() {
        val everyRam = listOf(
            0L, 2_000_000_000L, 4_000_000_000L, 5_500_000_000L, 6_000_000_000L, 7_000_000_000L,
            8_000_000_000L, 12_000_000_000L, 16_000_000_000L, 24_000_000_000L, Long.MAX_VALUE,
        )
        WhisperCatalog.instruments.forEach { model ->
            everyRam.forEach { ram ->
                assertFalse(
                    "instrument '${model.id}' was recommended at $ram bytes of RAM",
                    WhisperCatalog.isRecommendedForDevice(model, ram),
                )
            }
        }
        // The other half of the claim, and the one a RAM literal would have broken: `small-q8` —
        // the measured floor, `multi` until 4.7 — IS recommended everywhere, so the ladder did not
        // silently turn the chooser into a screen with no recommendation on it at all.
        everyRam.forEach { ram ->
            assertTrue(
                "the measured floor must be recommended at $ram — a chooser where NOTHING " +
                    "is recommended is a different defect from one where the wrong thing is",
                WhisperCatalog.isRecommendedForDevice(WhisperCatalog.byId("small-q8")!!, ram),
            )
        }
    }

    /**
     * 4.6 — **no instrument hides behind a RAM threshold.** The rejected design (research §6.3)
     * would have set `minRamBytes` above any shipping phone, which suppresses the badge as a
     * side effect and, on `OnboardingModelScreen`, raises *"High-end devices only — this tier
     * needs more RAM than this device reports"* on every device that renders the card. The ladder
     * exists so the owner can run a heavy model on a modest phone and find where it breaks; a
     * threshold that shouts at him for doing exactly that is the wrong mechanism, and a literal 0
     * here is what keeps `ramGated` false.
     */
    @Test fun no_instrument_hides_behind_a_ram_threshold() {
        WhisperCatalog.instruments.forEach {
            assertEquals(
                "instrument '${it.id}' carries a RAM threshold — the flag is what withholds the " +
                    "badge, and a threshold would make the card claim a reason that is not the " +
                    "real one (nobody has measured it, which is not about this device)",
                0L,
                it.minRamBytes,
            )
        }
    }

    /**
     * 4.6 — **every instrument is selectable and downloadable, with nothing about the DEVICE
     * hiding it.** *"They appear in the chooser on every device, with no RAM threshold hiding them.
     * The owner must be able to run a heavy model on a modest phone: finding where it breaks is the
     * point."*
     *
     * **"On every device" has one stated exception and it is not this test's: the 4.3 one-tier
     * narrowing.** A device offered [WhisperCatalog.ONE_TIER_ID] is offered no instrument at all —
     * see [a_device_offered_the_one_tier_is_offered_no_instrument] directly below, which is the
     * other half of this fact rather than a contradiction of it. What this test forbids is a rung
     * hidden by a predicate over the DEVICE ITSELF (RAM, or a per-rung capability gate); what that
     * one records is the owner's existing rule about capable hardware removing the whole menu.
     */
    @Test fun every_instrument_is_pickable_ungated_and_installable_by_download() {
        val pickableIds = WhisperCatalog.pickable.map { it.id }
        WhisperCatalog.instruments.forEach {
            assertTrue("instrument '${it.id}' is not offered at all", pickableIds.contains(it.id))
            assertFalse("instrument '${it.id}' is retired — it would not render", it.retired)
            assertFalse("instrument '${it.id}' is gated — some devices could not try it", it.gated)
            assertFalse("instrument '${it.id}' is unsupported — nobody is migrated off an offer", it.unsupported)
            assertTrue(
                "instrument '${it.id}' cannot be installed by download, so the offer is empty",
                WhisperCatalog.isInstallableByDownload(it),
            )
            assertNull("a ggml rung has one file", it.pairedArtifact)
            assertEquals("a single-file rung advertises exactly its one file", it.approxBytes, it.primaryBytes)
        }
        // And the gate answer no device can change: `pickableFor(emptySet())` — the whole
        // non-capable fleet — offers every instrument. This is the assertion that would fail if a
        // future edit tried to hide a heavy rung behind a device predicate.
        val everyDeviceSees = WhisperCatalog.pickableFor(emptySet()).map { it.id }
        WhisperCatalog.instruments.forEach {
            assertTrue("instrument '${it.id}' is hidden from a device that failed the NPU gate", everyDeviceSees.contains(it.id))
        }
    }

    /**
     * 4.6 — **A DEVICE OFFERED THE ONE TIER IS OFFERED NO INSTRUMENT. Recorded, not fixed.**
     *
     * The 4.3 narrowing in [WhisperCatalog.pickableFor] collapses a capable device's lineup to
     * [WhisperCatalog.ONE_TIER_ID] plus `alsoOfferedIds`, and `alsoOfferedIds` is the INSTALLED set
     * on both chooser surfaces (`OnboardingModelScreen`, and `OnboardingFlowScreen` via
     * `OnboardingLogic.chooserAlsoOfferedIds` before any delivery failure). So the six instrument
     * rungs 4.6 adds are unselectable and undownloadable on the whole 8 Gen 3-class fleet — the
     * Fold6 included, which is the device carrying the ladder's only measured anchor and therefore
     * the one on which `multi` vs `small-q8` would be the most interpretable experiment in the
     * research.
     *
     * **This test exists so that is a decision with a name rather than a hole.** It does not assert
     * that the collapse is right — it asserts what it does, in the terms the 4.6 session cares
     * about, so the next reader cannot mistake the branch's silence for the branch's intent. The
     * one-tier rule is the owner's ruling of 2026-08-30 and widening it to admit a measurement rung
     * is his call, not this branch's; what this branch owes him is the question asked out loud
     * before the APK is built, and the answer already having somewhere to land. The last block
     * below is that landing: the door is [WhisperCatalog.pickableFor]'s existing `alsoOfferedIds`
     * and it needs no new rule — which is also why nothing here would have to change if he opens
     * it, only the two producers of that argument.
     */
    @Test fun a_device_offered_the_one_tier_is_offered_no_instrument() {
        val instrumentIds = WhisperCatalog.instruments.map { it.id }.toSet()
        assertTrue("4.6 has no instruments, so this test is pinning nothing", instrumentIds.isNotEmpty())

        // Both spellings of "this device was offered the one tier": turbo alone (a capable device
        // whose `npu` pack the census cannot deliver) and both gated tiers (the Fold6, where both
        // are live). The collapse is keyed on ONE_TIER_ID's presence, so both narrow.
        listOf(setOf(WhisperCatalog.ONE_TIER_ID), setOf("npu", WhisperCatalog.ONE_TIER_ID)).forEach { offered ->
            // A fresh capable install: one card, and it is not an instrument.
            assertEquals(
                "$offered: the 4.3 one-card lineup moved",
                listOf(WhisperCatalog.ONE_TIER_ID),
                WhisperCatalog.pickableFor(offered).map { it.id },
            )
            // And with things on disk — the Settings surface's `alsoOfferedIds` IS `installedIds`,
            // so an install of `multi` or `npu` does not bring the ladder with it.
            listOf(emptySet(), setOf("multi"), setOf("npu"), setOf("npu", "multi")).forEach { installed ->
                val lineup = WhisperCatalog.pickableFor(offered, installed).map { it.id }
                instrumentIds.forEach { id ->
                    assertFalse(
                        "$offered/$installed: instrument '$id' is offered on a one-tier device — " +
                            "if that is now intended, this test is the thing to change, deliberately",
                        lineup.contains(id),
                    )
                }
                // The rendered surface agrees with the lineup rule, at every locale the 3.7 order
                // distinguishes — so this is a statement about what the owner SEES, not only about
                // a list. (Both chooser surfaces render `orderedForLanguageTagFor` over exactly
                // these two arguments.)
                listOf("en-US", "bn-BD", "zh-Hans-CN", "").forEach { tag ->
                    val rendered = ModelTierCopy.orderedForLanguageTagFor(tag, offered, installed)
                    instrumentIds.forEach { id ->
                        assertFalse(
                            "'$tag'/$offered/$installed: instrument '$id' has a card on a one-tier device",
                            rendered.contains(id),
                        )
                    }
                }
            }
        }

        // THE DOOR, proved to work before anyone needs it. If the owner rules that the instruments
        // should join the one-card lineup for this measurement session, it is the existing
        // `alsoOfferedIds` and nothing else: every instrument comes back, in catalog order, with
        // turbo still leading and no retired tier following it in. (The producer that would carry
        // the ids is `OnboardingLogic.chooserAlsoOfferedIds`, which already does exactly this with
        // `WhisperCatalog.pickable` on the delivery-failure path — pinned in `OnboardingLogicTest`.)
        val capable = setOf("npu", WhisperCatalog.ONE_TIER_ID)
        val ifOpened = WhisperCatalog.pickableFor(capable, instrumentIds).map { it.id }
        instrumentIds.forEach { id ->
            assertTrue("the alsoOfferedIds door does not admit instrument '$id'", ifOpened.contains(id))
        }
        assertEquals(
            "the door admits the ladder but not in catalog order, or lets something else in",
            WhisperCatalog.entries
                .filter { it.id == WhisperCatalog.ONE_TIER_ID || it.id in instrumentIds }
                .map { it.id },
            ifOpened,
        )
        WhisperCatalog.entries.filter { it.retired }.forEach {
            assertFalse(
                "retired '${it.id}' came back through the door — `!retired` must still run first",
                ifOpened.contains(it.id),
            )
        }
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
        // Stated as a loop over the retired SET since 4.6, so the rule cannot be outlived by its
        // examples — `ultra` was one of them and is a live rung again.
        assertNotNull(WhisperCatalog.byId("extreme"))
        WhisperCatalog.entries.filter { it.retired }.forEach {
            assertNotNull("retired tier '${it.id}' stopped resolving", WhisperCatalog.byId(it.id))
        }
    }

    @Test fun retired_tiers_are_not_pickable() {
        val ids = WhisperCatalog.pickable.map { it.id }
        assertFalse(ids.contains("extreme"))
        // 3.7 Workstream H (owner decision 2026-08-20): the 60 MB tiers join them. "Pretty much
        // useless at this point… because of the accuracy."
        assertFalse(ids.contains("eco"))
        assertFalse(ids.contains("base"))
        // 4.6: `ultra` LEFT this list — the owner's ruling of 2026-09-13 offered it again as an
        // instrument so it could be measured. 4.7: it is BACK, with the other three Q5 rungs —
        // the Q8 ruling of 2026-09-17 ("Q5 is definitely off the table"), and `ultra` was never
        // timed on the CPU after all: its Q8_0 twin kept up with no margin, and the only Q5_0
        // rung timed never caught up.
        listOf("multi", "medium-q5", "ultra", "large-v3").forEach {
            assertFalse("'$it' is a Q5 rung and the owner retired every one of them", ids.contains(it))
        }
        assertTrue("its Q8_0 twin is what is offered", ids.contains("ultra-q8"))
        // The rule itself, over the whole set rather than a list of names.
        WhisperCatalog.entries.filter { it.retired }.forEach {
            assertFalse("retired tier '${it.id}' is in the chooser", ids.contains(it.id))
        }
    }

    /**
     * 4.6 — **THE PICKABLE LADDER, EXACTLY AND IN ORDER.** Was
     * `pickable_is_exactly_pro_and_multi`: the post-3.7 lineup of pro (the English flagship) and
     * multi (the international tier). The seven-rung instrument ladder replaced it.
     *
     * 4.7 — **THE Q8 LADDER: three rungs, every one Q8_0, in catalogue order.** The owner's ruling
     * of 2026-09-17 on the Tab S10+ measurement: *"Q8 for everything." — "Q5 is definitely off
     * the table." — "We're only testing on models that we're actually gonna use."* So the chooser
     * on the test tablet shows ONLY these three (plus the gated NPU rows on capable devices,
     * unchanged).
     */
    @Test fun pickable_is_exactly_the_ladder_in_order() {
        assertEquals(
            listOf("small-q8", "medium-q8", "ultra-q8"),
            WhisperCatalog.pickable.map { it.id },
        )
        // One of the three is an instrument — `ultra-q8`, kept up with no margin. `small-q8` and
        // `medium-q8` are the rungs the app stands behind: measured, kept up with margin, which is
        // what makes the first the default, the steer and the migration target, and the second
        // the recommended medium tier — and what makes the third an offer rather than advice.
        assertEquals(1, WhisperCatalog.pickable.count { it.instrument })
        assertEquals(listOf("small-q8", "medium-q8"), WhisperCatalog.pickable.filterNot { it.instrument }.map { it.id })
        // Every pickable rung is Q8_0 — the ruling, at the list that enforces it.
        WhisperCatalog.pickable.forEach {
            assertTrue("'${it.id}' is not a Q8_0 file and the owner ruled Q8 for everything", it.fileName.endsWith("-q8_0.bin"))
        }
        // **THE OWNER'S RULING OF 2026-09-13, AT THE LIST THAT ENFORCES IT**: *"we should really
        // only be showing only multi language models, period. We shouldn't show English only at
        // all."* `pro` (small.en) was the last English-only rung offered; `eco` (base.en) and
        // `extreme` (medium.en) were already retired.
        WhisperCatalog.pickable.forEach {
            assertEquals(
                "'${it.id}' is ENGLISH-scope and offered — no English-only rung may be in the " +
                    "chooser at all",
                ModelScope.MULTILINGUAL,
                it.scope,
            )
        }
        // And the English-only rows are still CATALOGUED, all three of them, because retiring is
        // not deleting: byId() must keep answering or every installed .en user's installedModel()
        // goes null and the app-wide gate force-marches them into onboarding.
        assertEquals(
            listOf("eco", "pro", "extreme"),
            WhisperCatalog.entries.filter { it.scope == ModelScope.ENGLISH }.map { it.id },
        )
        WhisperCatalog.entries.filter { it.scope == ModelScope.ENGLISH }.forEach {
            assertTrue("English-only tier '${it.id}' must be retired", it.retired)
            assertNotNull("...and must still resolve", WhisperCatalog.byId(it.id))
        }
    }

    /**
     * 4.6 — **`pro` is RETIRED, NOT UNSUPPORTED, and that distinction is the care in this
     * change.** `pro` is the tier the largest number of English users are on. `unsupported` is the
     * only bit that raises Settings' *"This model is no longer supported"* card, so setting it
     * would have asked every one of them to re-download 190 MB they never requested — for a model
     * whose only difference from theirs is a multilingual vocab head, which makes the card's
     * implied promise false for an English-only user as well as unwanted.
     *
     * The 3.7 precedent is exact: `eco` and `base` were retired for accuracy and left completely
     * alone for the same reason, in the same words.
     */
    @Test fun the_last_english_rung_is_retired_but_nobody_on_it_is_disturbed() {
        val pro = WhisperCatalog.byId("pro")!!
        assertTrue("hidden from the chooser", pro.retired)
        assertFalse("but NOT a tier the app wants users off — no migration card", pro.unsupported)
        assertFalse("it is not the chooser's problem either way", pro.gated)
        assertFalse("and it is not an instrument: it was never an experiment", pro.instrument)
        assertNotNull("it must resolve forever, or installedModel() goes null", WhisperCatalog.byId("pro"))
        // Its file still works and is still a legal 80-bin mel donor / CPU fallback: retiring a
        // tier says nothing about the model, only about whether it is OFFERED.
        assertTrue(WhisperCatalog.isCpuFallbackEligible(pro))
        assertTrue(WhisperCatalog.hasCpuFallback(setOf("pro")))
        // The whole retired set, and the rule they all share. 4.7: the four Q5 rungs join it —
        // `multi` (the 4.6.0 default, in production), `medium-q5`, `ultra` and `large-v3` — by
        // the owner's Q8 ruling of 2026-09-17, and every one of them is retired the same gentle
        // way `pro` was: hidden, resolvable, untouched.
        assertEquals(
            listOf("eco", "base", "pro", "extreme", "multi", "medium-q5", "ultra", "large-v3"),
            WhisperCatalog.entries.filter { it.retired }.map { it.id },
        )
        assertEquals(
            "`extreme` is the ONLY tier the app still migrates anyone off — 4.6 removed `ultra` " +
                "rather than adding to it, and 4.7 retired four rungs without adding to it either",
            listOf("extreme"),
            WhisperCatalog.entries.filter { it.unsupported }.map { it.id },
        )
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
        // "existing users unaffected; no re-download forced". `extreme` keeps both flags.
        assertFalse(WhisperCatalog.byId("eco")!!.unsupported)
        assertFalse(WhisperCatalog.byId("base")!!.unsupported)
        assertTrue(WhisperCatalog.byId("extreme")!!.unsupported)
        // 4.6: `ultra` dropped BOTH flags together, necessarily. A tier the app OFFERS cannot also
        // be one it migrates people off — `decide()` gates on `unsupported` alone, so leaving that
        // bit set would raise "This model is no longer supported" on a rung the chooser is
        // simultaneously inviting the user to try. An `ultra` user who has carried that card since
        // 3.7 simply had a live tier again.
        //
        // 4.7: `ultra` is RETIRED again by the Q8 ruling — and `unsupported` stays FALSE, which is
        // THE split this test is about, seen on the rung that has now crossed it in both
        // directions. Retiring hides; it does not raise the card. Same for the other three.
        listOf("multi", "medium-q5", "ultra", "large-v3").forEach {
            assertTrue("'$it' is retired (Q8 ruling 2026-09-17)", WhisperCatalog.byId(it)!!.retired)
            assertFalse("'$it' is NOT unsupported — nobody on it is shown the migration card", WhisperCatalog.byId(it)!!.unsupported)
        }
    }

    /**
     * 4.6 — **no instrument is a tier the app wants users OFF of.** The coupling matters in this
     * direction too: `unsupported` is the only bit that raises the migration card, and a card that
     * tells a user to leave a rung the chooser just invited them onto is the ladder arguing with
     * itself.
     */
    @Test fun no_instrument_is_unsupported_or_retired() {
        WhisperCatalog.instruments.forEach {
            assertFalse("instrument '${it.id}' raises the migration card", it.unsupported)
            assertFalse("instrument '${it.id}' is hidden from the chooser", it.retired)
        }
    }

    @Test fun every_unsupported_tier_is_also_retired() {
        // Offering a tier the app wants to migrate people OFF of would be incoherent.
        WhisperCatalog.entries.filter { it.unsupported }.forEach {
            assertTrue("'${it.id}' is unsupported but still offered", it.retired)
        }
    }

    /**
     * 4.6 — was `default_is_pro`. It moved because `pro` was retired and a retired default is an
     * unshippable state, and it moved TO `multi` because `multi` was the only rung that cleared
     * the app's own eligibility rule ON EVIDENCE (F = 2.3 s, duty 0.42, Fold6, 2026-08-20).
     *
     * 4.7 — `default_is_small_q8`. It moved because `multi` is retired (the owner's Q8 ruling of
     * 2026-09-17), and it moved to `small-q8` because that is the rung the app can stand behind
     * on evidence now: the same whisper-small weights at Q8_0, measured on the Tab S10+ at a
     * median 1,217 ms per commit against `multi`'s 2,618 in the same session
     * (docs/measurements/2026-09-17-tab-cpu-ladder.md) — 2.2x faster for +74 MB, KEPT_UP with
     * margin, recommended on every device.
     */
    @Test fun default_is_small_q8() {
        assertEquals("small-q8", WhisperCatalog.DEFAULT_MODEL_ID)
        assertNotNull(WhisperCatalog.byId(WhisperCatalog.DEFAULT_MODEL_ID))
        // The default is the floor for every device: no RAM threshold, recommended everywhere.
        assertEquals(0L, WhisperCatalog.byId(WhisperCatalog.DEFAULT_MODEL_ID)!!.minRamBytes)
        // And the rung it replaced is retired — a retired default is what this move avoided.
        assertTrue(WhisperCatalog.byId("multi")!!.retired)
    }

    /**
     * **THE DEFAULT CLEARS THE APP'S OWN ELIGIBILITY RULE**, asserted as the four things that
     * makes it: pickable, not retired, MULTILINGUAL, and not an instrument.
     *
     * The last of those is the one 4.6 makes reachable, and it is the failure this whole task is
     * shaped to avoid. `DEFAULT_MODEL_ID` is the fallback for every path with no pick on record —
     * OnboardingSetupViewModel's auto-setup re-entry, OnboardingFlowScreen's download-phase
     * re-resolve, `ModelMigration`'s ENGLISH arm — so an instrument here would hand a finalizer
     * nobody has timed to the users who never made a choice at all. And the previewer would hide
     * it: words land on the floating strip 0.4 s behind the voice whatever the finalizer is doing.
     */
    @Test fun default_is_pickable_multilingual_and_not_an_instrument() {
        val default = WhisperCatalog.byId(WhisperCatalog.DEFAULT_MODEL_ID)!!
        // A retired default would be unreachable from the picker — an unshippable state.
        assertTrue(WhisperCatalog.pickable.any { it.id == WhisperCatalog.DEFAULT_MODEL_ID })
        assertFalse(default.retired)
        assertFalse("a gated default would be selected on devices whose assets are absent", default.gated)
        assertEquals(
            "the owner's ruling — multilingual rungs only — applies to the default first",
            ModelScope.MULTILINGUAL,
            default.scope,
        )
        assertFalse(
            "THE DEFAULT MAY NOT BE AN INSTRUMENT. It is what every path with no pick on record " +
                "falls back to, so an unmeasured rung here reaches exactly the users who made no " +
                "choice — and the previewer paints words 0.4 s behind the voice whatever the " +
                "finalizer is doing, so it would not look broken until the typed text was a " +
                "paragraph behind",
            default.instrument,
        )
        assertFalse("...so it cannot be one of them", WhisperCatalog.instruments.contains(default))
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
        assertEquals("small-q8", ModelMigration.targetIdFor(ModelScope.MULTILINGUAL))
    }

    @Test fun pickableFor_offers_a_gated_tier_exactly_when_its_id_is_in_the_set() {
        // 4.1: the Boolean became a set — two gated tiers can be independently installed, and one
        // bit cannot say which. The empty set is the every-other-device answer and must be
        // identical to `pickable`; each id ADDS its own tier and nothing else, in catalog order.
        assertEquals(WhisperCatalog.pickable, WhisperCatalog.pickableFor(emptySet()))
        // 4.6: the ungated lineup is the LADDER, whose exact content and order are pinned once in
        // `pickable_is_exactly_the_ladder_in_order`. What this test is about is the GATE — each
        // gated id adds its own tier and nothing else, in catalog order — so it composes against
        // that list rather than restating eight ids that would then have to be edited in two
        // places whenever the ladder changes.
        val ladder = WhisperCatalog.pickable.map { it.id }
        assertEquals(ladder, WhisperCatalog.pickableFor(emptySet()).map { it.id })
        assertEquals(
            ladder + "npu",
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
     * **EXISTING INSTALLS ARE NOT DISTURBED.** A capable device already running a live CPU rung
     * or `npu` keeps its card — the chooser offers turbo GOING FORWARD, it does not repossess a
     * model the user already downloaded. (Deleting a gigabyte someone paid bandwidth for is not
     * ours to do.)
     *
     * **4.7 — and a RETIRED rung on disk keeps working but keeps NO CARD, which now includes
     * `multi`.** `!it.retired` runs before `alsoOfferedIds`, so the 190 MB model a capable device
     * downloaded through the 4.3 decline recovery is still installed, still transcribing, still
     * the selection — and absent from the chooser, exactly as an installed `pro` has been since
     * 4.6. That is the retired-not-uninstalled rule doing what it says, and it is why the
     * recovery tier moved to `small-q8` (`NpuTierStatus.RECOVERY_TIER_ID`).
     */
    @Test fun a_capable_device_keeps_the_card_for_a_model_it_already_has() {
        val capable = setOf("npu", "npu-turbo")
        // Fresh capable install: exactly one card, in every locale. The spec's device acceptance.
        assertEquals(listOf("npu-turbo"), WhisperCatalog.pickableFor(capable).map { it.id })
        // The 264 MB CPU floor already on disk: still there, and turbo still leads.
        assertEquals(
            listOf("small-q8", "npu-turbo"),
            WhisperCatalog.pickableFor(capable, setOf("small-q8")).map { it.id },
        )
        assertEquals(
            listOf("npu-turbo", "small-q8"),
            ModelTierCopy.orderedForLanguageTagFor("bn-BD", capable, setOf("small-q8")),
        )
        assertEquals(
            listOf("npu-turbo", "small-q8"),
            ModelTierCopy.orderedForLanguageTagFor("en-US", capable, setOf("small-q8")),
        )
        // The 358 MB npu pair already imported: same promise, and the L9 runner-up key still puts
        // it directly below the pick.
        assertEquals(
            listOf("npu-turbo", "npu"),
            ModelTierCopy.orderedForLanguageTagFor("bn-BD", capable, setOf("npu")),
        )
        // Both, plus a live CPU rung — everything the user has, nothing they do not. (4.6 used
        // `medium-q5` here as an installed instrument; it is retired now, so `medium-q8` — the
        // medium tier — stands in, and the retired one is asserted absent below.)
        assertEquals(
            listOf("npu-turbo", "npu", "medium-q8"),
            ModelTierCopy.orderedForLanguageTagFor("en-US", capable, setOf("npu", "medium-q8")),
        )
        // A RETIRED tier on disk does NOT re-enter through this door: `!it.retired` runs first,
        // which is why the screens may stat the whole catalog for the fallback question. 4.7: the
        // four Q5 rungs join the set, and `multi` is the important member — the 4.6.0 production
        // default, and the tier the 4.3 recovery used to download — and this is the line that
        // says its card does not come back.
        assertEquals(
            listOf("npu-turbo"),
            WhisperCatalog.pickableFor(capable, setOf("eco", "base", "pro", "extreme", "multi", "medium-q5", "ultra", "large-v3"))
                .map { it.id },
        )
        assertEquals(
            "a capable device with the 874 MB instrument on disk keeps its card, exactly as it " +
                "keeps `small-q8`'s — the non-disturbance rule does not care how big the file is, " +
                "and an INSTRUMENT is an ordinary offered tier for every purpose but the badge",
            listOf("ultra-q8", "npu-turbo"),
            WhisperCatalog.pickableFor(capable, setOf("ultra-q8")).map { it.id },
        )
        // Nothing THE 4.3 GATE does selects anything: it changes what is OFFERED, never what is
        // chosen. (4.6 moved the default from `pro` to `multi`, 4.7 from `multi` to `small-q8` —
        // separate decisions, made where the default lives, because a retired default is
        // unreachable from the picker. The gate still does not touch it.)
        assertEquals("small-q8", WhisperCatalog.DEFAULT_MODEL_ID)
        assertEquals("small-q8", ModelMigration.targetIdFor(ModelScope.MULTILINGUAL))
        // And every tier a user could already be ON still RESOLVES, so `installedModel()` never
        // returns null and nobody is force-marched into onboarding with a model on disk. Stated
        // over the whole catalogue since 4.6, so five new rungs cannot be forgotten out of it.
        WhisperCatalog.entries.map { it.id }.forEach {
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

    // ------------------------------------------------------- 4.6: the catalog records mel width
    //
    // The ladder gains a SECOND 128-bin rung (`large-v3`), which is the tier the old by-name
    // exclusion named as its own residual. These pin the recorded widths and prove the predicate
    // now keys on the width rather than on a list of ids.

    /**
     * Every row's mel width, and where each 128 comes from. The values are read off the files
     * themselves — `n_mels` is the tenth int32 of a ggml header, 40 bytes in, so a 48-byte range
     * read settles a rung without downloading it. Verified 2026-09-13 at the pinned commit, one
     * range read per file: `ggml-small-q8_0.bin` 80, `ggml-medium-q5_0.bin` 80,
     * `ggml-medium-q8_0.bin` 80, `ggml-large-v3-turbo-q5_0.bin` 128,
     * `ggml-large-v3-turbo-q8_0.bin` 128, `ggml-large-v3-q5_0.bin` 128.
     *
     * The census is exhaustive on purpose: a new row inherits the 80 default, and a 128-bin row
     * that inherits it silently is the mute-failure this field exists to prevent — which 4.6 makes
     * reachable for the first time, since it adds two 128-bin ggml rows at once.
     */
    @Test fun every_row_records_its_mel_width_and_only_the_large_v3_family_is_128() {
        val byWidth = WhisperCatalog.entries.groupBy { it.melBins }.mapValues { (_, v) -> v.map { it.id } }
        assertEquals(
            "a tier gained or lost a mel width — 80 and 128 are the only two whisper has",
            setOf(80, 128),
            byWidth.keys,
        )
        assertEquals(
            "the 128-bin rows are exactly the large-v3 family: large-v3 itself, the two " +
                "quantisations of the turbo distillation of it, and the NPU graph of that turbo",
            listOf("ultra", "ultra-q8", "large-v3", "npu-turbo"),
            byWidth.getValue(128),
        )
        // The 80-bin rows are everything before large-v3 — the whole pre-v3 family — and 4.6's
        // three new ggml rungs that belong to it take the default rather than a literal.
        assertEquals(
            "the 80-bin rows are the pre-v3 family",
            listOf("eco", "base", "pro", "extreme", "multi", "small-q8", "medium-q5", "medium-q8", "npu"),
            byWidth.getValue(80),
        )
        // The gated rows take their width FROM the spec table, so the two descriptions of one
        // tier cannot disagree — and the ggml rows' 80 default is the same number the npu-small
        // graph demands of a donor, which is what makes the predicate below well-posed.
        assertEquals(NpuModelSpec.SMALL.melBins, WhisperCatalog.byId("npu")!!.melBins)
        assertEquals(NpuModelSpec.TURBO.melBins, WhisperCatalog.byId("npu-turbo")!!.melBins)
        assertEquals(NpuModelSpec.SMALL.melBins, WhisperCatalog.byId("multi")!!.melBins)
        WhisperCatalog.entries.filter { it.gated }.forEach {
            assertEquals(
                "gated tier '${it.id}': the catalog row and the spec row describe ONE tier",
                NpuModelSpec.forTier(it.id)!!.melBins,
                it.melBins,
            )
        }
    }

    /**
     * 4.3 — the CPU-fallback predicate, lifted out of `WhisperModelManager.isMelDonorEligible` so
     * the decline card and the backend ask ONE question. Clause for clause, the manager's own —
     * and since 4.6 the 128-bin clause is the catalog's recorded width, not the id `"ultra"`.
     */
    @Test fun the_cpu_fallback_predicate_admits_every_80_bin_ggml_and_nothing_else() {
        fun eligible(id: String) = WhisperCatalog.isCpuFallbackEligible(WhisperCatalog.byId(id)!!)
        // Retired tiers are eligible ON PURPOSE: an installed eco, base or pro is a real fallback.
        // 4.6 adds three more 80-bin ggml rungs, and every one of them is a legal donor —
        // OFFERING a rung and being able to FALL BACK to it are the same structural question.
        listOf("eco", "base", "pro", "extreme", "multi", "small-q8", "medium-q5", "medium-q8").forEach {
            assertTrue("'$it' is an 80-bin ggml and must be a legal fallback", eligible(it))
        }
        // 128-bin (refused by bin count); the npu class structurally.
        assertFalse("ultra is 128-bin — pcmToMel refuses it", eligible("ultra"))
        assertFalse("and so is its q8_0 twin, by the same recorded width", eligible("ultra-q8"))
        assertFalse(
            "THE DEFECT THE 4.6 MEL WIDTH CLOSES: `large-v3` is a single-file, ungated, PICKABLE " +
                "128-bin ggml — the exact tier the old `id != \"ultra\"` clause named as its own " +
                "residual. Admitting it hands pcmToMel a 128-bin donor under the 80-bin npu graph " +
                "(a failed load at arm), or falls a declining session back onto a file it cannot " +
                "compute a spectrogram with",
            eligible("large-v3"),
        )
        assertFalse("npu is a QAIRT context binary, not a ggml", eligible("npu"))
        assertFalse("and so is npu-turbo", eligible("npu-turbo"))
        // The set question, over the rungs 4.6 adds: three new donors, two new refusals.
        listOf("small-q8", "medium-q5", "medium-q8").forEach {
            assertTrue("an installed '$it' is a real 80-bin fallback", WhisperCatalog.hasCpuFallback(setOf(it)))
        }
        listOf("ultra", "ultra-q8", "large-v3").forEach {
            assertFalse("an installed '$it' cannot serve the 80-bin arm", WhisperCatalog.hasCpuFallback(setOf(it)))
        }
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
     * **THE 4.6 KEY CHANGE, PINNED AGAINST THE MUTATION IT REPLACES.** On today's catalog
     * `id != "ultra"` and `melBins == 80` agree on every row, so no census above can tell the two
     * predicates apart — the difference only shows on a tier that does not exist yet, which is
     * exactly when nobody will be reading the comment that explains it. Both shapes are therefore
     * constructed, the same discipline `downloadability_tracks_the_artefact_count_and_not_the_
     * device_gate` established for the download refusal.
     *
     * The dangerous half is the first: a single-file 128-bin rung that is not called "ultra" would
     * pass the old clause, become the mel donor under the 80-bin `npu` graph, and fail
     * `pcmToMel`'s band check at load — or be handed to a declining session as a CPU fallback it
     * cannot compute a spectrogram with.
     */
    @Test fun the_fallback_exclusion_keys_on_the_mel_width_and_not_on_the_id() {
        val otherNamed128 = WhisperCatalog.byId("multi")!!.copy(id = "future-128", melBins = 128)
        assertFalse(
            "a 128-bin single-file ggml is refused whatever it is called — keying on the id " +
                "`ultra` would admit it and fail pcmToMel's band check at load",
            WhisperCatalog.isCpuFallbackEligible(otherNamed128),
        )
        val eightyBinUltra = WhisperCatalog.byId("ultra")!!.copy(melBins = 80)
        assertTrue(
            "and the NAME carries nothing any more: were ultra's filterbank 80-bin it would be a " +
                "legal donor, because the filterbank is the whole reason it is refused",
            WhisperCatalog.isCpuFallbackEligible(eightyBinUltra),
        )
        // The width compared against is the npu-small graph's own input width, not a literal: that
        // is the tier that needs a donor, and pcmToMel refuses any donor that is not exactly it.
        assertEquals(80, NpuModelSpec.SMALL.melBins)
        WhisperCatalog.entries.forEach { m ->
            if (WhisperCatalog.isCpuFallbackEligible(m)) {
                assertEquals(
                    "'${m.id}' is admitted as a donor, so its filterbank must be the one " +
                        "pcmToMel computes for the arming tier",
                    NpuModelSpec.SMALL.melBins,
                    m.melBins,
                )
            }
        }
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
        // 4.6: the retired id named here is `extreme` — `ultra` is a LIVE rung now, so naming it
        // would make the claim vacuous (it is in `pickable` on its own merits, not resurrected).
        assertEquals(WhisperCatalog.pickable, WhisperCatalog.pickableFor(setOf("extreme")))
        assertEquals(WhisperCatalog.pickable, WhisperCatalog.pickableFor(setOf("eco", "nope")))
        assertEquals(
            WhisperCatalog.pickable.map { it.id } + "npu",
            WhisperCatalog.pickableFor(setOf("npu", "extreme", "nope")).map { it.id },
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
