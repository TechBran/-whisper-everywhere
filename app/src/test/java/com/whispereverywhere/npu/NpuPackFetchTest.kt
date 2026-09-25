package com.whispereverywhere.npu

import com.google.android.play.core.assetpacks.model.AssetPackErrorCode
import com.google.android.play.core.assetpacks.model.AssetPackStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * THE PLAY FETCH FLOW'S PURE MACHINE (4.2 F5) — executed, the L5-precedent split.
 *
 * `NpuPackController` is Android-bound (AssetPackManager, Activity, a process-scoped coroutine
 * owner), so no JVM test can run it; its wiring is pinned as source by `NpuDiagTest`. Everything
 * the controller DECIDES lives in [NpuPackFetch] precisely so it can be proved here instead —
 * which status becomes which state, which error code becomes which sentence, when a progress
 * line may print. The same split, for the same reason, as `NpuImportController`/`NpuAssetImport`.
 *
 * **The library's own classes are the reference.** These tests enumerate
 * [AssetPackStatus]/[AssetPackErrorCode] REFLECTIVELY (both are plain constant-carrying classes,
 * loadable on the JVM — verified at this suite's red step), so a library upgrade that adds,
 * removes or renumbers a value fails a named assertion here rather than shipping a silent remap.
 */
class NpuPackFetchTest {

    /** Every public static int constant of a Play model class, by name. */
    private fun intConstants(clazz: Class<*>): Map<String, Int> =
        clazz.fields
            .filter { Modifier.isStatic(it.modifiers) && it.type == Integer.TYPE }
            .associate { it.name to it.getInt(null) }

    private val soFar = 105_906_176L
    private val total = 901_775_360L

    // ------------------------------------------------------------------ the status machine

    @Test
    fun everyAssetPackStatusMapsToExactlyOneFetchState() {
        // THE RED (4.2 F5). `advance` is total over Int: all TEN AssetPackStatus values map to
        // exactly one honest state, and completion of DELIVERY is the start of OUR verification
        // — never Installed. The table below is keyed by the library's own field NAMES, so a
        // value this build has never heard of fails as "unmapped status", loudly, here.
        val statuses = intConstants(AssetPackStatus::class.java)
        assertEquals(
            "the library carries exactly the ten documented statuses — an eleventh means the " +
                "dependency moved and this machine must be re-specified, not left to the else arm",
            10,
            statuses.size,
        )
        val expected: Map<String, NpuPackFetch.FetchState> = mapOf(
            // UNKNOWN is a real answer Play can give and silence is not a state: the same
            // discipline as the error table — named, numbered, rendered.
            "UNKNOWN" to NpuPackFetch.FetchState.Failed("Google Play reported status 0 (unknown)"),
            "PENDING" to NpuPackFetch.FetchState.Pending,
            "DOWNLOADING" to NpuPackFetch.FetchState.Downloading(soFar, total),
            "TRANSFERRING" to NpuPackFetch.FetchState.Transferring,
            // COMPLETED means DELIVERED. The pack sits on disk unverified; our metadata
            // cross-check and stream hash start here, so the state is Verifying from byte zero.
            "COMPLETED" to NpuPackFetch.FetchState.Verifying(0, total),
            "FAILED" to NpuPackFetch.FetchState.Failed(
                NpuPackFetch.failureReason(NpuPackFetch.ERROR_NETWORK_ERROR, total)
            ),
            "CANCELED" to NpuPackFetch.FetchState.Cancelled,
            // Both consent-shaped statuses land on the SAME state: the answer to each is Play's
            // own confirmation dialog, and the card offers exactly that.
            "WAITING_FOR_WIFI" to NpuPackFetch.FetchState.NeedsConfirmation,
            "REQUIRES_USER_CONFIRMATION" to NpuPackFetch.FetchState.NeedsConfirmation,
            "NOT_INSTALLED" to NpuPackFetch.FetchState.Idle,
        )
        assertEquals(
            "every library status is mapped by name — an unmapped one is a hole in this table",
            statuses.keys,
            expected.keys,
        )
        statuses.forEach { (name, value) ->
            assertEquals(
                "AssetPackStatus.$name ($value) maps to exactly its one honest state",
                expected.getValue(name),
                NpuPackFetch.advance(value, NpuPackFetch.ERROR_NETWORK_ERROR, soFar, total),
            )
        }
        // THE UNRECOGNIZED-INT ARM. `advance` is total over Int whatever the library documents:
        // a value off the table lands in Failed with its number, never in a `when` nobody wrote
        // an else for and never in silence.
        assertEquals(
            NpuPackFetch.FetchState.Failed("Google Play reported status 99"),
            NpuPackFetch.advance(99, 0, 0L, 0L),
        )
        assertEquals(
            "and a negative surprise is rendered the same way",
            NpuPackFetch.FetchState.Failed("Google Play reported status -3"),
            NpuPackFetch.advance(-3, 0, 0L, 0L),
        )
    }

    @Test
    fun theMirroredStatusConstantsEqualTheLibrarysOwn() {
        // The machine spells statuses as its own documented constants (so the pure half never
        // imports Play classes), and THIS is what keeps the mirror honest: each constant is
        // asserted equal to the library's same-named field, so a renumbering upgrade goes red.
        val library = intConstants(AssetPackStatus::class.java)
        val mirrored = mapOf(
            "UNKNOWN" to NpuPackFetch.STATUS_UNKNOWN,
            "PENDING" to NpuPackFetch.STATUS_PENDING,
            "DOWNLOADING" to NpuPackFetch.STATUS_DOWNLOADING,
            "TRANSFERRING" to NpuPackFetch.STATUS_TRANSFERRING,
            "COMPLETED" to NpuPackFetch.STATUS_COMPLETED,
            "FAILED" to NpuPackFetch.STATUS_FAILED,
            "CANCELED" to NpuPackFetch.STATUS_CANCELED,
            "WAITING_FOR_WIFI" to NpuPackFetch.STATUS_WAITING_FOR_WIFI,
            "NOT_INSTALLED" to NpuPackFetch.STATUS_NOT_INSTALLED,
            "REQUIRES_USER_CONFIRMATION" to NpuPackFetch.STATUS_REQUIRES_USER_CONFIRMATION,
        )
        assertEquals("one mirrored constant per library status", library.keys, mirrored.keys)
        mirrored.forEach { (name, value) ->
            assertEquals("STATUS_$name mirrors the library exactly", library.getValue(name), value)
        }
    }

    @Test
    fun completedMeansDeliveredNotInstalledForEveryStatusTheLibraryCanReport() {
        // No status Play can report — documented or not — may EVER answer Installed: that state
        // exists only past our own verification (`installFromPack` returning Installed), which
        // no Play callback can testify to. The delivered-means-installed collapse is the exact
        // mutant this test exists to kill.
        (intConstants(AssetPackStatus::class.java).values + listOf(99, -3)).forEach { status ->
            assertFalse(
                "status $status must not map to Installed — delivery is not installation",
                NpuPackFetch.advance(status, 0, soFar, total)
                    is NpuPackFetch.FetchState.Installed,
            )
        }
        assertEquals(
            "COMPLETED starts OUR verification at byte zero of the pair",
            NpuPackFetch.FetchState.Verifying(0, total),
            NpuPackFetch.advance(NpuPackFetch.STATUS_COMPLETED, 0, soFar, total),
        )
    }

    @Test
    fun downloadingCarriesTheBytesPlayReported() {
        val state = NpuPackFetch.advance(NpuPackFetch.STATUS_DOWNLOADING, 0, soFar, total)
        assertEquals(NpuPackFetch.FetchState.Downloading(soFar, total), state)
        assertEquals(
            "a fresh download starts at zero of an as-yet-unknown total and must still be a state",
            NpuPackFetch.FetchState.Downloading(0L, 0L),
            NpuPackFetch.advance(NpuPackFetch.STATUS_DOWNLOADING, 0, 0L, 0L),
        )
    }

    // ------------------------------------------------------------------ the failure table

    @Test
    fun theFailureTableNamesEveryAssetPackErrorCodeInUserWords() {
        // The library's own error class is the reference, and EVERY code it declares must
        // render as a sentence a user can act on — the generic numbered fallback is reserved
        // for codes this build has never heard of. A new code in an upgraded library fails
        // here by name rather than shipping as "error -16".
        val library = intConstants(AssetPackErrorCode::class.java)
        val mirrored = mapOf(
            "NO_ERROR" to NpuPackFetch.ERROR_NO_ERROR,
            "APP_UNAVAILABLE" to NpuPackFetch.ERROR_APP_UNAVAILABLE,
            "PACK_UNAVAILABLE" to NpuPackFetch.ERROR_PACK_UNAVAILABLE,
            "INVALID_REQUEST" to NpuPackFetch.ERROR_INVALID_REQUEST,
            "DOWNLOAD_NOT_FOUND" to NpuPackFetch.ERROR_DOWNLOAD_NOT_FOUND,
            "API_NOT_AVAILABLE" to NpuPackFetch.ERROR_API_NOT_AVAILABLE,
            "NETWORK_ERROR" to NpuPackFetch.ERROR_NETWORK_ERROR,
            "ACCESS_DENIED" to NpuPackFetch.ERROR_ACCESS_DENIED,
            "INSUFFICIENT_STORAGE" to NpuPackFetch.ERROR_INSUFFICIENT_STORAGE,
            "APP_NOT_OWNED" to NpuPackFetch.ERROR_APP_NOT_OWNED,
            "CONFIRMATION_NOT_REQUIRED" to NpuPackFetch.ERROR_CONFIRMATION_NOT_REQUIRED,
            "UNRECOGNIZED_INSTALLATION" to NpuPackFetch.ERROR_UNRECOGNIZED_INSTALLATION,
            "INTERNAL_ERROR" to NpuPackFetch.ERROR_INTERNAL_ERROR,
        )
        assertEquals(
            "one mirrored constant per library error code — a code added by an upgrade must be " +
                "given words here, not inherited by the numbered fallback. MEASURED at this " +
                "task's red step: 2.3.0 declares THIRTEEN codes — PLAY_STORE_NOT_FOUND is a " +
                "Play Core 1.x code the current class no longer carries (asserted below).",
            library.keys,
            mirrored.keys,
        )
        assertFalse(
            "the 1.x PLAY_STORE_NOT_FOUND is genuinely absent from the 2.3.0 class — if an " +
                "upgrade brings it back, fold it into the mirror map above",
            library.containsKey("PLAY_STORE_NOT_FOUND"),
        )
        assertEquals(
            "and the -11 arm is kept anyway — the table is total over Int, the service side " +
                "can still surface the 1.x number, and its meaning is the sideload truth",
            NpuPackFetch.failureReason(NpuPackFetch.ERROR_APP_NOT_OWNED, total),
            NpuPackFetch.failureReason(NpuPackFetch.ERROR_PLAY_STORE_NOT_FOUND, total),
        )
        mirrored.forEach { (name, value) ->
            assertEquals("ERROR_$name mirrors the library exactly", library.getValue(name), value)
        }
        library.forEach { (name, value) ->
            val reason = NpuPackFetch.failureReason(value, total)
            assertTrue("AssetPackErrorCode.$name renders words, not a blank", reason.isNotBlank())
            assertFalse(
                "AssetPackErrorCode.$name ($value) must NOT fall through to the numbered " +
                    "fallback — every known code gets user words: $reason",
                reason.contains("Google Play reported error"),
            )
        }
    }

    @Test
    fun theSideloadTruthIsTheExactCopyForTheCodesThatMeanNotInstalledFromPlay() {
        // APP_NOT_OWNED / PLAY_STORE_NOT_FOUND / API_NOT_AVAILABLE are what a sideloaded build
        // (the owner's own adb-installed debug APK included) sees on every fetch. The copy is
        // the truth stated as the PATH FORWARD — the SAF import — never a dead end, and it is
        // pinned exactly because it is the one failure the primary test device will actually
        // show. UNRECOGNIZED_INSTALLATION is the same fact in the library's newer spelling.
        val sideload = "Google Play can't deliver the model to this install — it wasn't " +
            "installed from Play. Use 'Import model pair…' below instead."
        listOf(
            NpuPackFetch.ERROR_APP_NOT_OWNED,
            NpuPackFetch.ERROR_PLAY_STORE_NOT_FOUND,
            NpuPackFetch.ERROR_API_NOT_AVAILABLE,
            NpuPackFetch.ERROR_UNRECOGNIZED_INSTALLATION,
        ).forEach { code ->
            assertEquals("code $code carries the sideload answer, exactly",
                sideload, NpuPackFetch.failureReason(code, total))
        }
    }

    @Test
    fun insufficientStorageNamesThePairsSize() {
        val reason = NpuPackFetch.failureReason(NpuPackFetch.ERROR_INSUFFICIENT_STORAGE, total)
        assertTrue(
            "the storage refusal names the download's real size — 901 MB here — because " +
                "'not enough space' without a number is not actionable: $reason",
            reason.contains("901 MB"),
        )
        assertTrue("and says what to do about it: $reason", reason.contains("retry"))
        val sizeless = NpuPackFetch.failureReason(NpuPackFetch.ERROR_INSUFFICIENT_STORAGE, 0L)
        assertFalse(
            "when Play reported no size there is no number to name, and inventing one would be " +
                "worse than omitting it: $sizeless",
            sizeless.contains(" 0 MB"),
        )
        assertTrue("the sizeless form is still a sentence: $sizeless", sizeless.isNotBlank())
    }

    /**
     * PACK_UNAVAILABLE (-2) IS WORDED TRUE FOR BOTH THINGS PLAY MEANS BY IT (4.16.1). The old
     * sentence — *"This version of the app doesn't offer that model pack on Google Play. Update the
     * app from Play"* — was false on 2026-09-25: the Tab S10+ was on the current version and Play
     * answered -2 for about three hours after the upload while it staged the 1.2 GB pack, then
     * served it on the owner's Retry. So the sentence states what both cases share (not available
     * for this version YET), the staging case's step (a few hours, hedged) and the missing pack's
     * (update the app, after a day), within the house claim rules: no promise, no comparative, no
     * superlative, no absolute, no invented number. Pinned exactly on both device shapes (the twin
     * without the import is pinned in [aRefusalWordedForADeviceWithoutTheImportRouteNeverNamesIt]).
     */
    @Test
    fun packUnavailableIsTrueWhilePlayStagesThePackAndWhenTheVersionLacksIt() {
        val withRoute = NpuPackFetch.failureReason(NpuPackFetch.ERROR_PACK_UNAVAILABLE, total)
        assertEquals(
            "That model pack isn't available from Google Play for this version of the app yet. If the " +
                "app was just updated, Play may still be preparing it — try again in a few hours. If " +
                "it's still unavailable after a day, update the app from Play, or use 'Import model " +
                "pair…' below.",
            withRoute,
        )
        val without = NpuPackFetch.reasonFor(withRoute, importRoute = false)
        for (sentence in listOf(withRoute, without)) {
            val l = sentence.lowercase()
            assertTrue("the fact both cases share — not available YET: <<$sentence>>", l.contains("for this version of the app yet"))
            assertTrue(
                "the staging case's step, hedged rather than promised: <<$sentence>>",
                l.contains("may still be preparing it") && l.contains("try again in a few hours"),
            )
            assertTrue("the missing pack's step, after a day: <<$sentence>>", l.contains("after a day, update the app from play"))
            assertFalse("no promise that the pack arrives: <<$sentence>>", Regex("\\b(will|guarantee[sd]?)\\b").containsMatchIn(l))
            assertFalse("no comparative: <<$sentence>>", Regex("\\b(faster|sooner|quicker|better)\\b").containsMatchIn(l))
            assertFalse("no superlative: <<$sentence>>", Regex("\\b(fastest|best|highest|most|quickest|top)\\b").containsMatchIn(l))
            for (absolute in listOf("instant", "always", "never", "unlimited")) {
                assertFalse("no absolute '$absolute': <<$sentence>>", l.contains(absolute))
            }
            assertFalse("no invented duration, as a number: <<$sentence>>", Regex("\\d").containsMatchIn(l))
            assertFalse("not the sideload family's sentence: <<$sentence>>", sentence.contains("it wasn't installed from Play"))
            assertFalse("and never the old advice alone: <<$sentence>>", sentence.contains("doesn't offer"))
        }
        assertTrue("with the route, the import below is named", withRoute.contains("'Import model pair…' below"))
    }

    @Test
    fun networkErrorCarriesTheExactRetryCopy() {
        assertEquals(
            "The download couldn't reach Google Play. Check your connection and retry.",
            NpuPackFetch.failureReason(NpuPackFetch.ERROR_NETWORK_ERROR, total),
        )
    }

    @Test
    fun anUnknownErrorCodeRendersItsNumberNeverSilence() {
        assertEquals("Google Play reported error -777", NpuPackFetch.failureReason(-777, total))
        assertEquals(
            "the fallback is total over Int in both directions",
            "Google Play reported error 42",
            NpuPackFetch.failureReason(42, 0L),
        )
    }

    @Test
    fun theFailedStateIsTheRefusalCarrierAndCarriesTheTablesWordsVerbatim() {
        // The certification's carrier ruling (supersession (b)): a fetch that fails — including
        // a fetched-but-corrupt pack — surfaces its reason through the fetch card's OWN Failed
        // state. The `unavailableReason` machinery keeps its existing job (a tier that
        // INSTALLED and then declined at load) and structurally cannot carry a pack that never
        // installed. So Failed.reason must be the error table's sentence VERBATIM — the card
        // renders it and nothing else.
        val sideload = NpuPackFetch.advance(
            NpuPackFetch.STATUS_FAILED, NpuPackFetch.ERROR_APP_NOT_OWNED, 0L, total,
        )
        assertEquals(
            NpuPackFetch.FetchState.Failed(
                NpuPackFetch.failureReason(NpuPackFetch.ERROR_APP_NOT_OWNED, total)
            ),
            sideload,
        )
        val storage = NpuPackFetch.advance(
            NpuPackFetch.STATUS_FAILED, NpuPackFetch.ERROR_INSUFFICIENT_STORAGE, 0L, total,
        ) as NpuPackFetch.FetchState.Failed
        assertTrue(
            "the pair's size survives into the carried state: ${storage.reason}",
            storage.reason.contains("901 MB"),
        )
    }

    // ------------------------------------------------------------------ the tier homes

    @Test
    fun packByTierSpellsTheTwoCommittedPackNamesThroughTheTierIdHomes() {
        // The pack names are committed facts (F4's modules pin `packName.set(…)`); the tier ids
        // are spelled through their HOMES — the npu constant and the turbo spec — so this map
        // cannot drift from either side without a compile error or this red.
        assertEquals(
            mapOf(
                NpuAssetImport.TIER_ID to "npu_small",
                NpuModelSpec.TURBO.tierId to "npu_turbo",
            ),
            NpuPackFetch.PACK_BY_TIER,
        )
        assertEquals(
            "every paired tier has a pack and no pack serves a tier the catalog cannot pair — " +
                "the next npu-class tier joins this map the day it joins PAIRED_TIER_IDS",
            NpuAssetImport.PAIRED_TIER_IDS.toSet(),
            NpuPackFetch.PACK_BY_TIER.keys,
        )
    }

    // ------------------------------------------------------------------ progress decisions

    @Test
    fun shouldLogProgressAllowsAtMostOneLinePerDecile() {
        // The throttle is the SHELL's, but the decision is pure and lives here: one `pack:`
        // line per status transition plus at most one per 10% of progress — a per-tick line
        // would bury the run-book's landmarks under ~200 lines per fetch.
        assertTrue("the first tick of a fetch is a landmark", NpuPackFetch.shouldLogProgress(-1, 0))
        assertFalse("4% after 0% is the same decile", NpuPackFetch.shouldLogProgress(0, 4))
        assertFalse("9% after 0% is still the same decile", NpuPackFetch.shouldLogProgress(0, 9))
        assertTrue("10% after 0% is a new decile", NpuPackFetch.shouldLogProgress(0, 10))
        assertFalse("19% after 10% is not", NpuPackFetch.shouldLogProgress(10, 19))
        assertTrue("20% after 10% is", NpuPackFetch.shouldLogProgress(10, 20))
        assertTrue("a decile can be SKIPPED and still logs once", NpuPackFetch.shouldLogProgress(10, 47))
        assertTrue("completion always logs", NpuPackFetch.shouldLogProgress(99, 100))
        assertFalse("but only once", NpuPackFetch.shouldLogProgress(100, 100))
    }

    @Test
    fun pctIsTotalSafeAndClamped()  {
        assertEquals("an unknown total is 0%, never a division by zero", 0, NpuPackFetch.pct(0L, 0L))
        assertEquals(0, NpuPackFetch.pct(0L, total))
        assertEquals(25, NpuPackFetch.pct(50L, 200L))
        assertEquals(11, NpuPackFetch.pct(soFar, total))
        assertEquals(100, NpuPackFetch.pct(total, total))
        assertEquals(
            "a soFar past total (a resumed fetch's bookkeeping) clamps rather than reporting 104%",
            100,
            NpuPackFetch.pct(total + 1L, total),
        )
    }

    @Test
    fun statusWordsAreOneGreppableWordPerState() {
        // The `pack:` line's status field — one lowercase greppable token per state, so
        // `pack: fetch tier=npu-turbo` lines parse on spaces forever.
        val words = mapOf<NpuPackFetch.FetchState, String>(
            NpuPackFetch.FetchState.Idle to "idle",
            NpuPackFetch.FetchState.Pending to "pending",
            NpuPackFetch.FetchState.Downloading(soFar, total) to "downloading",
            NpuPackFetch.FetchState.Transferring to "transferring",
            NpuPackFetch.FetchState.Verifying(0, total) to "verifying",
            NpuPackFetch.FetchState.Installed to "installed",
            NpuPackFetch.FetchState.Failed("x") to "failed",
            NpuPackFetch.FetchState.Cancelled to "cancelled",
            NpuPackFetch.FetchState.NeedsConfirmation to "needs-confirmation",
        )
        words.forEach { (state, word) ->
            assertEquals(word, NpuPackFetch.statusWord(state))
        }
        assertEquals(
            "every state has its own word — two states sharing one would make the line lie",
            words.size,
            words.values.toSet().size,
        )
        words.values.forEach { word ->
            assertTrue("'$word' is one token", !word.contains(" ") && word == word.lowercase())
        }
    }

    // ------------------------------------------------------------------ the parts machine (P2-4)

    /**
     * The eleven states ONE part can be in during a fetch: no reading yet (`-`: asked for, Play has
     * not answered), then the ten documented statuses. The pair table below crosses them.
     */
    private val partStates: List<String> =
        listOf("-", "IDLE", "PEND", "DOWN", "XFER", "DONE", "FAIL", "CANC", "WIFI", "CONF", "UNKN")

    private fun statusOf(code: String): Int = when (code) {
        "IDLE" -> NpuPackFetch.STATUS_NOT_INSTALLED
        "PEND" -> NpuPackFetch.STATUS_PENDING
        "DOWN" -> NpuPackFetch.STATUS_DOWNLOADING
        "XFER" -> NpuPackFetch.STATUS_TRANSFERRING
        "DONE" -> NpuPackFetch.STATUS_COMPLETED
        "FAIL" -> NpuPackFetch.STATUS_FAILED
        "CANC" -> NpuPackFetch.STATUS_CANCELED
        "WIFI" -> NpuPackFetch.STATUS_WAITING_FOR_WIFI
        "CONF" -> NpuPackFetch.STATUS_REQUIRES_USER_CONFIRMATION
        "UNKN" -> NpuPackFetch.STATUS_UNKNOWN
        else -> throw AssertionError("no status for part state '$code'")
    }

    /** Part 1 is the mt6989 encoder-sized part, part 2 the decoder-sized one (compressed sizes). */
    private val partTotals = listOf(1_310_000_000L, 590_000_000L)
    private val partSoFars = listOf(400_000_000L, 100_000_000L)

    /** Part 1 fails on the network, part 2 on storage — so F1 and F2 are told apart by their words. */
    private val partErrors = listOf(NpuPackFetch.ERROR_NETWORK_ERROR, NpuPackFetch.ERROR_INSUFFICIENT_STORAGE)

    private fun readingOf(part: Int, code: String): NpuPackFetch.PartReading? {
        if (code == "-") return null
        val status = statusOf(code)
        val soFar = if (status == NpuPackFetch.STATUS_COMPLETED) partTotals[part] else partSoFars[part]
        val error = if (status == NpuPackFetch.STATUS_FAILED) partErrors[part] else NpuPackFetch.ERROR_NO_ERROR
        return NpuPackFetch.PartReading(status, error, soFar, partTotals[part])
    }

    /**
     * THE PAIR TABLE: part 1's state down, part 2's across, and the ONE state the fetch card shows.
     * `P` Pending, `I` Idle, `D` Downloading (the pair's summed bytes), `T` Transferring, `V`
     * Verifying (the pair's summed total — delivery complete, the install begins), `N`
     * NeedsConfirmation, `C` Cancelled, `F1`/`F2` Failed with part 1's / part 2's own reason.
     * Written out, not derived: the worst status wins and the first failing part names it — by
     * Failed > Cancelled > NeedsConfirmation > Idle > Pending > Downloading > Transferring >
     * Verifying while either part is unanswered (the `-` row and column), and, RE-SPECCED by the
     * P2b review's small 1, by Failed > Cancelled > NeedsConfirmation > Downloading > Transferring
     * > Pending > Idle > Verifying once BOTH have answered: a part moving bytes outranks one queued
     * or idle, so sequential downloads show the pair's bar (the DOWN+PEND cell was a bar-less `P`
     * for the whole 1.3 GB encoder) and a part downloading beside an idle one is never the Get
     * button (IDLE+DOWN was `I`). Only cells inside the IDLE/PEND/DOWN/XFER square moved — ten of
     * its sixteen; every other row and column, the DONE ones included, is what P2-4 wrote.
     */
    private val pairTable: List<String> = listOf(
        //       -    IDLE PEND DOWN XFER DONE FAIL CANC WIFI CONF UNKN    <- part 2
        /* -    */ "P    I    P    P    P    P    F2   C    N    N    F2",
        /* IDLE */ "I    I    P    D    T    I    F2   C    N    N    F2",
        /* PEND */ "P    P    P    D    T    P    F2   C    N    N    F2",
        /* DOWN */ "P    D    D    D    D    D    F2   C    N    N    F2",
        /* XFER */ "P    T    T    D    T    T    F2   C    N    N    F2",
        /* DONE */ "P    I    P    D    T    V    F2   C    N    N    F2",
        /* FAIL */ "F1   F1   F1   F1   F1   F1   F1   F1   F1   F1   F1",
        /* CANC */ "C    C    C    C    C    C    F2   C    C    C    F2",
        /* WIFI */ "N    N    N    N    N    N    F2   C    N    N    F2",
        /* CONF */ "N    N    N    N    N    N    F2   C    N    N    F2",
        /* UNKN */ "F1   F1   F1   F1   F1   F1   F1   F1   F1   F1   F1",
    )

    @Test
    fun theTwoPartMachineFoldsEveryCombinationOfPartStatesToTheTablesOneState() {
        assertEquals("one table row per part-1 state", partStates.size, pairTable.size)
        var cells = 0
        for ((row, first) in partStates.withIndex()) {
            val expectedRow = pairTable[row].trim().split(Regex("\\s+"))
            assertEquals("row $first has one cell per part-2 state", partStates.size, expectedRow.size)
            for ((column, second) in partStates.withIndex()) {
                val readings = listOf(readingOf(0, first), readingOf(1, second))
                val soFar = readings.sumOf { it?.soFar ?: 0L }
                val total = readings.sumOf { it?.total ?: 0L }
                val failureOf = { part: Int ->
                    val reading = requireNotNull(readings[part])
                    if (reading.status == NpuPackFetch.STATUS_FAILED) {
                        NpuPackFetch.FetchState.Failed(NpuPackFetch.failureReason(reading.errorCode, total))
                    } else {
                        NpuPackFetch.FetchState.Failed("Google Play reported status 0 (unknown)")
                    }
                }
                val expected: NpuPackFetch.FetchState = when (val cell = expectedRow[column]) {
                    "P" -> NpuPackFetch.FetchState.Pending
                    "I" -> NpuPackFetch.FetchState.Idle
                    "D" -> NpuPackFetch.FetchState.Downloading(soFar, total)
                    "T" -> NpuPackFetch.FetchState.Transferring
                    "V" -> NpuPackFetch.FetchState.Verifying(0, total)
                    "N" -> NpuPackFetch.FetchState.NeedsConfirmation
                    "C" -> NpuPackFetch.FetchState.Cancelled
                    "F1" -> failureOf(0)
                    "F2" -> failureOf(1)
                    else -> throw AssertionError("unknown table cell '$cell'")
                }
                assertEquals(
                    "part 1 $first + part 2 $second folds to exactly the table's state",
                    expected,
                    NpuPackFetch.advance(readings),
                )
                cells++
            }
        }
        assertEquals("all 121 combinations were executed", 121, cells)
    }

    /**
     * The P2b review's small 1, as the two cells it named: Play downloading the parts one after
     * another must show the pair's bar, not a bar-less Pending for the whole encoder; and a part
     * downloading beside one Play reports idle must never show the Get button. Both only once
     * every part has answered — an unanswered part keeps the pair Pending.
     */
    @Test
    fun onceEveryPartHasAnsweredAPartMovingBytesNamesThePairsState() {
        assertEquals(
            "DOWN + PEND: the pair's bar, both parts' bytes",
            NpuPackFetch.FetchState.Downloading(400_000_000L + 100_000_000L, 1_900_000_000L),
            NpuPackFetch.advance(listOf(readingOf(0, "DOWN"), readingOf(1, "PEND"))),
        )
        assertEquals(
            "IDLE + DOWN: busy, never the Get button",
            NpuPackFetch.FetchState.Downloading(400_000_000L + 100_000_000L, 1_900_000_000L),
            NpuPackFetch.advance(listOf(readingOf(0, "IDLE"), readingOf(1, "DOWN"))),
        )
        assertEquals(
            "…but with part 2 unanswered the pair stays Pending — no bar over half its bytes",
            NpuPackFetch.FetchState.Pending,
            NpuPackFetch.advance(listOf(readingOf(0, "DOWN"), null)),
        )
        assertEquals(
            "and the gating is unchanged: one part delivered, the other queued, is not delivered",
            NpuPackFetch.FetchState.Pending,
            NpuPackFetch.advance(listOf(readingOf(0, "DONE"), readingOf(1, "PEND"))),
        )
    }

    @Test
    fun theInstallBeginsOnlyWhenEveryPartIsDeliveredNeverOnAPartialPair() {
        // Verifying is the state the controller launches installFromPack on. Across every one of
        // the 121 combinations it appears exactly once: both parts COMPLETED. One part delivered
        // and the other anything else — failed included — is never an install.
        for (first in partStates) {
            for (second in partStates) {
                val folded = NpuPackFetch.advance(listOf(readingOf(0, first), readingOf(1, second)))
                assertEquals(
                    "$first + $second: Verifying if and only if BOTH parts are delivered",
                    first == "DONE" && second == "DONE",
                    folded is NpuPackFetch.FetchState.Verifying,
                )
                assertFalse("and no fold is ever Installed", folded is NpuPackFetch.FetchState.Installed)
            }
        }
    }

    @Test
    fun onePartFailingAfterTheOtherDeliveredIsAFailureAndItsRetryMovesOnlyTheFailedPartsBytes() {
        val delivered = readingOf(0, "DONE")
        val failed = NpuPackFetch.advance(listOf(delivered, readingOf(1, "FAIL")))
        assertEquals(
            "the encoder delivered, the decoder failed: the card names the decoder's failure, and " +
                "the storage sentence names what the PAIR needs — 1,900 MB, not the part's 590",
            NpuPackFetch.FetchState.Failed(
                NpuPackFetch.failureReason(NpuPackFetch.ERROR_INSUFFICIENT_STORAGE, 1_900_000_000L)
            ),
            failed,
        )
        assertTrue((failed as NpuPackFetch.FetchState.Failed).reason.contains("1900 MB"))
        // The retry fetches the pair again; Play answers the part it still holds with COMPLETED at
        // once and moves bytes only for the failed one — which the fold shows as ONE progress bar
        // over the whole pair, the delivered part counted in full.
        assertEquals(
            "the retry, re-queried: the delivered part replays COMPLETED, the failed part is pending",
            NpuPackFetch.FetchState.Pending,
            NpuPackFetch.advance(listOf(delivered, readingOf(1, "PEND"))),
        )
        assertEquals(
            "…then downloads, and the bar starts at the delivered part's bytes",
            NpuPackFetch.FetchState.Downloading(1_310_000_000L + 100_000_000L, 1_900_000_000L),
            NpuPackFetch.advance(listOf(delivered, readingOf(1, "DOWN"))),
        )
        assertEquals(
            "…and the install begins only once the retried part is delivered too",
            NpuPackFetch.FetchState.Verifying(0, 1_900_000_000L),
            NpuPackFetch.advance(listOf(delivered, readingOf(1, "DONE"))),
        )
    }

    @Test
    fun reAttachAfterProcessDeathStartsFromEveryPartUnansweredAndReplaysEachPartsStatus() {
        // A new process has no readings: every part is re-queried (the controller fetches the
        // pair), and until Play answers each one the pair is Pending — never Idle (which would
        // offer a second Get), never a progress bar with half its bytes.
        assertEquals(NpuPackFetch.FetchState.Pending, NpuPackFetch.advance(listOf(null, null)))
        assertEquals(
            "one part replayed as delivered, the other not yet answered: still Pending",
            NpuPackFetch.FetchState.Pending,
            NpuPackFetch.advance(listOf(readingOf(0, "DONE"), null)),
        )
        assertEquals(
            "a three-part pair folds by the same rule — the machine is not written for two",
            NpuPackFetch.FetchState.Verifying(0, 30L),
            NpuPackFetch.advance(
                listOf(
                    NpuPackFetch.PartReading(NpuPackFetch.STATUS_COMPLETED, 0, 10L, 10L),
                    NpuPackFetch.PartReading(NpuPackFetch.STATUS_COMPLETED, 0, 10L, 10L),
                    NpuPackFetch.PartReading(NpuPackFetch.STATUS_COMPLETED, 0, 10L, 10L),
                )
            ),
        )
        assertEquals(
            "and an unrecognised status in either part is the loud Failed it always was",
            NpuPackFetch.FetchState.Failed("Google Play reported status 99"),
            NpuPackFetch.advance(
                listOf(readingOf(0, "DONE"), NpuPackFetch.PartReading(99, 0, 0L, 0L))
            ),
        )
        assertEquals(
            "no parts at all is nothing requested",
            NpuPackFetch.FetchState.Idle,
            NpuPackFetch.advance(emptyList()),
        )
    }

    // ------------------------------------------------ the fetch Task's own answer (P2b review FIX-NOW)

    /**
     * The controller's readings, as a list the test drives the way `NpuPackController` does: the
     * listener OVERWRITES a part's slot ([listener]); the fetch Task's answer fills only the slots
     * [NpuPackFetch.unansweredParts] names ([taskAnswered]). Every fold is the machine's own.
     */
    private class Readings(private val parts: List<PackPart>) {
        val slots: MutableList<NpuPackFetch.PartReading?> = MutableList(parts.size) { null }

        fun listener(name: String, reading: NpuPackFetch.PartReading) {
            slots[parts.indexOfFirst { it.packName == name }] = reading
        }

        fun taskAnswered(answer: Map<String, NpuPackFetch.PartReading>) {
            for (name in NpuPackFetch.unansweredParts(parts, slots, answer.keys)) {
                slots[parts.indexOfFirst { it.packName == name }] = answer.getValue(name)
            }
        }

        fun fold(): NpuPackFetch.FetchState = NpuPackFetch.advance(slots.toList())
    }

    private val mt6989Parts: List<PackPart> by lazy {
        NpuPackFetch.packsFor("npu-turbo", requireNotNull(NpuFleetCensus.familyById("mt6989")))
    }

    private fun reading(status: Int, soFar: Long, total: Long) = NpuPackFetch.PartReading(status, 0, soFar, total)

    /**
     * THE FIX-NOW, executed as the review described it: the encoder (1.3 GB) landed in an earlier
     * fetch, the decoder did not; the user retries. Play's listener fires on CHANGES, and a pack
     * already COMPLETED has none — so part 1's reading arrives ONLY through the fetch Task's result,
     * and part 2's through the listener. Folding the listener alone left [null, DONE] at Pending
     * for good (asserted first, as the defect it was); counting the Task's answer reaches Verifying.
     */
    @Test
    fun aPartPlayAlreadyHoldsIsCountedFromTheFetchTasksOwnAnswer() {
        val (enc, dec) = mt6989Parts.map { it.packName }
        val encTotal = 1_310_000_000L
        val decTotal = 590_000_000L
        // The defect: the listener alone never reports the finished encoder.
        val listenerOnly = Readings(mt6989Parts)
        listenerOnly.listener(dec, reading(NpuPackFetch.STATUS_COMPLETED, decTotal, decTotal))
        assertEquals(
            "listener alone: the delivered encoder is never counted — Pending forever, every tap refused",
            NpuPackFetch.FetchState.Pending,
            listenerOnly.fold(),
        )
        // The fix: the Task's answer (every requested pack, at the request) fills the encoder.
        val fixed = Readings(mt6989Parts)
        fixed.taskAnswered(
            mapOf(
                enc to reading(NpuPackFetch.STATUS_COMPLETED, encTotal, encTotal),
                dec to reading(NpuPackFetch.STATUS_PENDING, 0L, decTotal),
            ),
        )
        assertEquals("the retry is queued for the decoder alone", NpuPackFetch.FetchState.Pending, fixed.fold())
        fixed.listener(dec, reading(NpuPackFetch.STATUS_DOWNLOADING, 100_000_000L, decTotal))
        assertEquals(
            "the decoder moves through the listener, and the bar counts the delivered encoder in full",
            NpuPackFetch.FetchState.Downloading(encTotal + 100_000_000L, encTotal + decTotal),
            fixed.fold(),
        )
        fixed.listener(dec, reading(NpuPackFetch.STATUS_COMPLETED, decTotal, decTotal))
        assertEquals(
            "…and the install begins: part 1 from the Task's answer, part 2 from the listener",
            NpuPackFetch.FetchState.Verifying(0, encTotal + decTotal),
            fixed.fold(),
        )
    }

    @Test
    fun theTaskAnswerNeverOverwritesAReadingTheListenerAlreadyGave() {
        val (enc, dec) = mt6989Parts.map { it.packName }
        val moving = reading(NpuPackFetch.STATUS_DOWNLOADING, 700_000_000L, 1_310_000_000L)
        val r = Readings(mt6989Parts)
        r.listener(enc, moving)
        assertEquals(
            "only the part the listener has not spoken for is filled — a listener reading is never " +
                "older than the request's snapshot",
            listOf(dec),
            NpuPackFetch.unansweredParts(mt6989Parts, r.slots, setOf(enc, dec)),
        )
        r.taskAnswered(
            mapOf(
                enc to reading(NpuPackFetch.STATUS_PENDING, 0L, 1_310_000_000L),
                dec to reading(NpuPackFetch.STATUS_PENDING, 0L, 590_000_000L),
            ),
        )
        assertEquals("the encoder keeps its newer reading", moving, r.slots[0])
        assertEquals(
            "a pack the Task did not answer for is left unanswered",
            emptyList<String>(),
            NpuPackFetch.unansweredParts(mt6989Parts, listOf(null, null), emptySet()),
        )
        assertEquals(
            "…and a part name that is not this fetch's is ignored",
            emptyList<String>(),
            NpuPackFetch.unansweredParts(mt6989Parts, listOf(null, null), setOf("npu_turbo")),
        )
    }

    /**
     * The ONE-PART regression cases: a Qualcomm pair is one pack. When the listener answers first
     * (every fetch that moves bytes), the Task's answer changes nothing; when the pack was already
     * delivered — an install refused and retried, the pack left in place — the Task's COMPLETED is
     * what starts the verify (the same defect, one part wide).
     */
    @Test
    fun forOnePartTheTaskAnswerChangesNothingWhenTheListenerAnsweredFirst() {
        val one = NpuPackFetch.packsFor("npu-turbo", requireNotNull(NpuFleetCensus.familyById("8gen3")))
        val name = one.single().packName
        val first = Readings(one)
        first.listener(name, reading(NpuPackFetch.STATUS_DOWNLOADING, 5L, 10L))
        first.taskAnswered(mapOf(name to reading(NpuPackFetch.STATUS_PENDING, 0L, 10L)))
        assertEquals(
            "the listener's reading stands — the one-part path is what it was",
            NpuPackFetch.FetchState.Downloading(5L, 10L),
            first.fold(),
        )
        val held = Readings(one)
        held.taskAnswered(mapOf(name to reading(NpuPackFetch.STATUS_COMPLETED, 10L, 10L)))
        assertEquals(
            "a pack Play already holds is delivered by the Task's answer alone",
            NpuPackFetch.FetchState.Verifying(0, 10L),
            held.fold(),
        )
    }

    @Test
    fun forOnePartTheFoldIsThePerPartMappingStatusForStatus() {
        // The Qualcomm fleet's whole guarantee: a one-part pair folds to EXACTLY what the per-part
        // mapping answered before parts existed — for every documented status, two the library
        // never documented, and every error code the failure arm can carry.
        val statuses = intConstants(AssetPackStatus::class.java).values + listOf(99, -3)
        val errors = intConstants(AssetPackErrorCode::class.java).values + listOf(-777)
        for (status in statuses) {
            for (error in errors) {
                assertEquals(
                    "status $status / error $error: one part folds to the per-part mapping",
                    NpuPackFetch.advance(status, error, soFar, total),
                    NpuPackFetch.advance(listOf(NpuPackFetch.PartReading(status, error, soFar, total))),
                )
            }
        }
        assertEquals(
            "and one part with no reading yet is the Pending the controller publishes at start",
            NpuPackFetch.FetchState.Pending,
            NpuPackFetch.advance(listOf(null)),
        )
    }

    @Test
    fun packsForIsTheFamilysOwnPartsAndEmptyWhereThereIsNothingToFetch() {
        for (a in NpuFleetCensus.artifacts) {
            val family = requireNotNull(NpuFleetCensus.familyById(a.familyId))
            assertEquals(
                "${a.familyId}/${a.tierId}: the packs to fetch ARE the census row's parts",
                a.parts,
                NpuPackFetch.packsFor(a.tierId, family),
            )
        }
        val gen3 = requireNotNull(NpuFleetCensus.familyById("8gen3"))
        assertEquals(
            "a Qualcomm pair is ONE part — the tier's own pack, both entries — which is what the " +
                "single-pack machinery fetched before parts existed",
            listOf("npu_turbo"),
            NpuPackFetch.packsFor("npu-turbo", gen3).map { it.packName },
        )
        assertEquals(
            listOf(NpuPackFetch.PACK_BY_TIER.getValue("npu")),
            NpuPackFetch.packsFor("npu", gen3).map { it.packName },
        )
        val mt6989 = requireNotNull(NpuFleetCensus.familyById("mt6989"))
        assertEquals(
            "the MediaTek pair is two: the encoder's module, then the decoder's",
            listOf("npu_turbo_mt6989_enc", "npu_turbo_mt6989_dec"),
            NpuPackFetch.packsFor("npu-turbo", mt6989).map { it.packName },
        )
        assertEquals(
            "no Small was ever built for the tablet: nothing to fetch",
            emptyList<PackPart>(),
            NpuPackFetch.packsFor("npu", mt6989),
        )
        assertEquals("no family resolved: nothing to fetch", emptyList<PackPart>(), NpuPackFetch.packsFor("npu-turbo", null))
        assertEquals("a tier the census has no pair for has no pack", emptyList<PackPart>(), NpuPackFetch.packsFor("cpu", gen3))
    }

    @Test
    fun thePackLabelIsTheOnePacksNameAndTheJoinedPartsOtherwise() {
        val gen3 = requireNotNull(NpuFleetCensus.familyById("8gen3"))
        val mt6989 = requireNotNull(NpuFleetCensus.familyById("mt6989"))
        assertEquals(
            "a Qualcomm fetch's pack= field is byte-identical to the line it has always printed",
            "npu_turbo",
            NpuPackFetch.packLabel(NpuPackFetch.packsFor("npu-turbo", gen3)),
        )
        assertEquals(
            "a two-part pair names both, as ONE token — the line still parses on spaces",
            "npu_turbo_mt6989_enc+npu_turbo_mt6989_dec",
            NpuPackFetch.packLabel(NpuPackFetch.packsFor("npu-turbo", mt6989)),
        )
    }

    @Test
    fun eachEntryIsReadOutOfThePartThatCarriesItAndTheMetadataOutOfPartOne() {
        val mt6989 = requireNotNull(NpuFleetCensus.familyById("mt6989"))
        val parts = NpuPackFetch.packsFor("npu-turbo", mt6989)
        val paths = mapOf(
            "npu_turbo_mt6989_enc" to "/data/app/asset_packs/enc/assets",
            "npu_turbo_mt6989_dec" to "/data/app/asset_packs/dec/assets",
        )
        assertEquals(
            "the encoder from part 1's delivered directory, the decoder from part 2's",
            mapOf(
                "turbo_encoder_qairt_context.bin" to java.io.File("/data/app/asset_packs/enc/assets", "npu_turbo_mt6989_enc"),
                "turbo_decoder_qairt_context.bin" to java.io.File("/data/app/asset_packs/dec/assets", "npu_turbo_mt6989_dec"),
            ),
            NpuPackFetch.deliveredEntryDirs(parts, paths),
        )
        assertEquals(
            "and metadata.json — which lists BOTH entries — out of part 1's",
            java.io.File("/data/app/asset_packs/enc/assets", "npu_turbo_mt6989_enc"),
            NpuPackFetch.deliveredMetadataDir(parts, paths),
        )
        assertEquals(
            "a part Play gave no location for contributes nothing: its entry is missing, and the " +
                "install's both-present check refuses it by name",
            setOf("turbo_encoder_qairt_context.bin"),
            NpuPackFetch.deliveredEntryDirs(parts, paths - "npu_turbo_mt6989_dec").keys,
        )
        assertEquals(
            "no location for part 1 is no metadata: the empty delivery",
            null,
            NpuPackFetch.deliveredMetadataDir(parts, paths - "npu_turbo_mt6989_enc"),
        )
        // The Qualcomm pair: both entries out of the ONE pack's directory, exactly as before.
        val gen3 = requireNotNull(NpuFleetCensus.familyById("8gen3"))
        val one = NpuPackFetch.packsFor("npu-turbo", gen3)
        val dir = java.io.File("/assets", "npu_turbo")
        assertEquals(
            mapOf("turbo_encoder_qairt_context.bin" to dir, "turbo_decoder_qairt_context.bin" to dir),
            NpuPackFetch.deliveredEntryDirs(one, mapOf("npu_turbo" to "/assets")),
        )
        assertEquals(dir, NpuPackFetch.deliveredMetadataDir(one, mapOf("npu_turbo" to "/assets")))
    }

    // ------------------------------------------------------------------ the empty delivery

    @Test
    fun theEmptyDeliveryRefusalNamesTheTruthAndTheImportPath() {
        // A device outside every census group receives the EMPTY default variant (F4's
        // fail-safe): the pack "arrives" carrying no metadata and no model. The refusal states
        // that as Play's answer — not as corruption, not as a mystery — and names the way
        // forward, because a dead end on the fetch card is the failure the copy rules forbid.
        // (P2-7, the P2b review's small 3) The refusal takes the pair's parts; a Qualcomm pair is
        // device-targeted, and its sentence is the one this test has always read.
        val gen3 = NpuPackFetch.packsFor("npu-turbo", requireNotNull(NpuFleetCensus.familyById("8gen3")))
        val refusal = NpuPackFetch.emptyDeliveryRefusal(gen3)
        assertTrue(
            "the missing metadata IS the empty-default signature and the copy says so: $refusal",
            refusal.contains("Google Play delivered no model for this device"),
        )
        assertTrue(
            "the import fallback is named as the path forward: $refusal",
            refusal.contains("Import model pair…"),
        )
        assertTrue("and the no-install promise is stated, truthfully: $refusal",
            refusal.contains("Nothing was installed"))
    }

    /**
     * THE P2b REVIEW'S SMALL 3 — the empty delivery is worded by the pair's TARGETING. A targeted
     * (Qualcomm) pair's empty delivery is the F4 default variant, and "not in any device group" is
     * its truth; an untargeted (MediaTek) pair has no default variant and no group to be outside
     * of — its module carries the payload for every device — so that sentence would be false, and
     * the truth is that the pack was not delivered.
     */
    @Test
    fun theEmptyDeliveryIsWordedByThePairsTargeting() {
        val gen3 = NpuPackFetch.packsFor("npu-turbo", requireNotNull(NpuFleetCensus.familyById("8gen3")))
        val mt6989 = NpuPackFetch.packsFor("npu-turbo", requireNotNull(NpuFleetCensus.familyById("mt6989")))
        assertTrue("a Qualcomm pair is device-targeted", NpuPackFetch.isDeviceTargeted(gen3))
        assertFalse("the mt6989 pair's own modules are not", NpuPackFetch.isDeviceTargeted(mt6989))
        assertFalse("and no parts at all is no targeted pair", NpuPackFetch.isDeviceTargeted(emptyList()))
        for (a in NpuFleetCensus.artifacts) {
            val family = requireNotNull(NpuFleetCensus.familyById(a.familyId))
            assertEquals(
                "${a.familyId}/${a.tierId}: targeted exactly on the Qualcomm rows",
                family.vendor == NpuVendor.QUALCOMM,
                NpuPackFetch.isDeviceTargeted(a.parts),
            )
        }
        val targeted = NpuPackFetch.emptyDeliveryRefusal(gen3)
        val untargeted = NpuPackFetch.emptyDeliveryRefusal(mt6989)
        assertTrue("targeted: the device-group sentence", targeted.contains("not in any device group"))
        assertFalse("untargeted: never the device-group sentence — it would be false", untargeted.contains("device group"))
        assertTrue("untargeted: the pack was not delivered, and a retry is named", untargeted.contains("was not delivered") && untargeted.contains("Retry"))
        for (sentence in listOf(targeted, untargeted)) {
            assertTrue(sentence.startsWith("Google Play delivered no model for this device"))
            assertTrue("the import path is named: $sentence", sentence.contains("'Import model pair…' below"))
            assertTrue(sentence.endsWith("Nothing was installed."))
            assertFalse("never the sideload family's sentence", sentence.contains("it wasn't installed from Play"))
        }
    }

    // ------------------------------------------------ P3a review, small 1: no import on a MediaTek row

    /**
     * A REFUSAL NEVER NAMES AN IMPORT THE DEVICE IS NOT OFFERED (the P3a review, small 1). The
     * machine's sentences name "'Import model pair…' below" — true on the Settings picker of a
     * Qualcomm row, where that control is below, and false on a MediaTek row, which is offered no
     * import at all (no zip is published for its pair; `NpuAssetImport.panelOfferedOn`). The pack
     * controller publishes every refusal through [NpuPackFetch.reasonFor] with the device's rule.
     * This holds, over EVERY code the library declares and both empty-delivery shapes: with the
     * route, the reason is verbatim; without it, it names no import — and the four twins are
     * pinned exactly, each still stating the fact and the one path forward that exists.
     */
    @Test
    fun aRefusalWordedForADeviceWithoutTheImportRouteNeverNamesIt() {
        val gen3 = NpuPackFetch.packsFor("npu-turbo", requireNotNull(NpuFleetCensus.familyById("8gen3")))
        val mt6989 = NpuPackFetch.packsFor("npu-turbo", requireNotNull(NpuFleetCensus.familyById("mt6989")))
        val every = intConstants(AssetPackErrorCode::class.java).values
            .map { NpuPackFetch.failureReason(it, total) } +
            NpuPackFetch.failureReason(NpuPackFetch.ERROR_PLAY_STORE_NOT_FOUND, total) +
            NpuPackFetch.failureReason(NpuPackFetch.ERROR_INSUFFICIENT_STORAGE, 0L) +
            listOf(NpuPackFetch.emptyDeliveryRefusal(gen3), NpuPackFetch.emptyDeliveryRefusal(mt6989))
        assertTrue("the import-naming family is still there to be worded", every.any { it.contains("Import model pair") })
        for (reason in every) {
            assertEquals("with the route, verbatim: <<$reason>>", reason, NpuPackFetch.reasonFor(reason, importRoute = true))
            val without = NpuPackFetch.reasonFor(reason, importRoute = false)
            assertFalse("without the route, <<$without>> still names the import", without.lowercase().contains("import"))
            assertTrue("…and still says something: <<$without>>", without.isNotBlank())
            // …and the onboarding surface renders it verbatim: it carries no adjacency marker, so
            // it is never turned into "import from Settings later" there either.
            assertEquals(without, com.whispereverywhere.ui.onboarding.OnboardingLogic.onboardingFetchRefusal(without))
        }
        assertEquals(
            "Google Play can't deliver the model to this install — it wasn't installed from Play, " +
                "and on this device the model comes from Google Play only.",
            NpuPackFetch.reasonFor(NpuPackFetch.failureReason(NpuPackFetch.ERROR_APP_NOT_OWNED, total), importRoute = false),
        )
        assertEquals(
            "Google Play says this app is currently unavailable, so it can't deliver the model right " +
                "now. Try again later.",
            NpuPackFetch.reasonFor(NpuPackFetch.failureReason(NpuPackFetch.ERROR_APP_UNAVAILABLE, total), importRoute = false),
        )
        assertEquals(
            "That model pack isn't available from Google Play for this version of the app yet. If the " +
                "app was just updated, Play may still be preparing it — try again in a few hours. If " +
                "it's still unavailable after a day, update the app from Play.",
            NpuPackFetch.reasonFor(NpuPackFetch.failureReason(NpuPackFetch.ERROR_PACK_UNAVAILABLE, total), importRoute = false),
        )
        assertEquals(
            "Google Play delivered no model for this device — the model's pack was not delivered. " +
                "Retry the download. Nothing was installed.",
            NpuPackFetch.reasonFor(NpuPackFetch.emptyDeliveryRefusal(mt6989), importRoute = false),
        )
        assertTrue(
            "the sideload twin keeps the sideload family's own sentence",
            NpuPackFetch.reasonFor(NpuPackFetch.failureReason(NpuPackFetch.ERROR_API_NOT_AVAILABLE, total), importRoute = false)
                .contains("it wasn't installed from Play"),
        )
    }
}
