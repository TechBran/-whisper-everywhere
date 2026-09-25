package com.whispereverywhere.npu

import com.google.android.play.core.assetpacks.AssetPackState
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A FETCH TASK'S ANSWER THAT ARRIVES AFTER A CANCEL IS DROPPED (the P2c review, a LATER item).
 *
 * The fetch Task's own answer (`onFetchAnswered`, the P2b review's FIX-NOW) is dropped only when
 * its generation is stale, and until this fix only `start` moved the generation — so an answer
 * arriving after the user tapped Cancel still folded in: it could flip Cancelled back to Pending,
 * or, when Play already held every part (its answer says COMPLETED), to Verifying — and run the
 * install the user had cancelled. Reachable on Qualcomm as much as MediaTek since the Task success
 * listener. `cancel()` now advances the generation first, under the monitor the fetch bump and the
 * answer's check both take.
 *
 * EXECUTED, not pinned: the controller is Play-bound (its `start` needs a Context and Play's
 * manager), so the in-flight fetch `start` leaves behind is set up by reflection — this fetch's
 * parts, one unanswered reading, the tier, Pending — and the fetch Task's answer is delivered the
 * way Play delivers it, through the private `onFetchAnswered`, with an `AssetPackState` built here
 * (the class is abstract with a public constructor). The manager stays null, so nothing reaches
 * Play and an install, if one were launched, would return at its first line. The control half
 * delivers the same kind of answer to the CURRENT generation and watches it fold, so the drop is
 * the generation's doing and not a broken harness.
 */
class NpuPackControllerCancelTest {

    private val cls = NpuPackController::class.java

    private fun field(name: String) = cls.getDeclaredField(name).apply { isAccessible = true }

    @Suppress("UNCHECKED_CAST")
    private fun <T> read(name: String): T = field(name).get(null) as T

    private var generation: Int
        get() = field("fetchGeneration").getInt(null)
        set(value) = field("fetchGeneration").setInt(null, value)

    private val state: MutableStateFlow<NpuPackFetch.FetchState> get() = read("_state")

    private fun answer(generation: Int, states: Map<String, AssetPackState>) {
        val method = cls.getDeclaredMethod("onFetchAnswered", Int::class.javaPrimitiveType, Map::class.java)
        method.isAccessible = true
        method.invoke(NpuPackController, generation, states)
    }

    /** One pack's state as Play answers it — only the four numbers the fold reads matter. */
    private fun packState(name: String, status: Int, soFar: Long, total: Long) = object : AssetPackState() {
        override fun name(): String = name
        override fun status(): Int = status
        override fun errorCode(): Int = 0
        override fun bytesDownloaded(): Long = soFar
        override fun totalBytesToDownload(): Long = total
        override fun transferProgressPercentage(): Int = 0
        override fun updateAvailability(): Int = 0
        override fun availableVersionTag(): String = ""
        override fun installedVersionTag(): String = ""
    }

    /** What `start` leaves behind for a Qualcomm turbo fetch: its one part, unanswered, Pending. */
    private fun aFetchInFlight(): List<PackPart> {
        val parts = NpuPackFetch.packsFor("npu-turbo", NpuFleetCensus.familyById("8gen3"))
        field("activeParts").set(null, parts)
        val readings: MutableList<NpuPackFetch.PartReading?> = read("readings")
        synchronized(NpuPackController) {
            readings.clear()
            repeat(parts.size) { readings += null }
        }
        read<MutableStateFlow<String?>>("_activeTier").value = "npu-turbo"
        state.value = NpuPackFetch.FetchState.Pending
        return parts
    }

    @After
    fun backToRest() {
        field("activeParts").set(null, emptyList<PackPart>())
        read<MutableList<NpuPackFetch.PartReading?>>("readings").clear()
        read<MutableStateFlow<String?>>("_activeTier").value = null
        state.value = NpuPackFetch.FetchState.Idle
        field("job").set(null, null)
    }

    @Test
    fun aFetchTasksAnswerArrivingAfterACancelIsDropped() {
        val parts = aFetchInFlight()
        assertEquals("one part, the Qualcomm turbo pack", listOf("npu_turbo"), parts.map { it.packName })
        val fetchesGeneration = generation
        NpuPackController.cancel()
        assertEquals("the card reads Cancelled the moment the user says so", NpuPackFetch.FetchState.Cancelled, state.value)

        // The Task's answer to the CANCELLED fetch lands now: Play already held the pack.
        answer(fetchesGeneration, mapOf("npu_turbo" to packState("npu_turbo", NpuPackFetch.STATUS_COMPLETED, 0L, 981_968_552L)))
        assertEquals(
            "the answer to a cancelled fetch is dropped — it may not flip Cancelled back to Verifying",
            NpuPackFetch.FetchState.Cancelled,
            state.value,
        )
        assertNull("…and no install was launched for a fetch the user cancelled", read<Any?>("job"))
        val readings: List<NpuPackFetch.PartReading?> = read("readings")
        assertEquals("…and it filled no reading", listOf<NpuPackFetch.PartReading?>(null), readings.toList())
        assertTrue("…because the cancel ended the fetch's generation", generation != fetchesGeneration)

        // CONTROL: the same kind of answer to the CURRENT generation does fold — the drop above is
        // the generation's doing, not a harness that never reaches the fold. (DOWNLOADING, so the
        // control launches no install either.)
        aFetchInFlight()
        answer(generation, mapOf("npu_turbo" to packState("npu_turbo", NpuPackFetch.STATUS_DOWNLOADING, 10L, 100L)))
        assertEquals(NpuPackFetch.FetchState.Downloading(10L, 100L), state.value)
    }

    @Test
    fun theCancelAdvancesTheGenerationFirstUnderTheMonitor() {
        val src = File(
            generateSequence(File(System.getProperty("user.dir") ?: ".").absoluteFile) { it.parentFile }
                .flatMap { sequenceOf(File(it, "src/main/java/com/whispereverywhere/npu/NpuPackController.kt"), File(it, "app/src/main/java/com/whispereverywhere/npu/NpuPackController.kt")) }
                .first { it.isFile }
                .path,
        ).readText().replace("\r\n", "\n")
        val cancel = src.substringAfter("    fun cancel() {\n").substringBefore("\n    }\n")
        val first = cancel.lines().first { it.isNotBlank() }.trim()
        assertEquals(
            "the cancel's FIRST statement ends the generation, under the monitor start and " +
                "onFetchAnswered take — so an answer landing while the cancel runs is already stale",
            "synchronized(this) { fetchGeneration++ }",
            first,
        )
        assertEquals(
            "the generation moves at exactly two sites: start's bump and the cancel's",
            listOf(1, 1),
            listOf("++fetchGeneration", "fetchGeneration++").map { needle -> src.split(needle).size - 1 },
        )
    }
}
