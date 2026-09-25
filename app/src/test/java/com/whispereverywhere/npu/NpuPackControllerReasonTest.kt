package com.whispereverywhere.npu

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * EVERY REFUSAL THE PACK CONTROLLER PUBLISHES IS WORDED FOR THE DEVICE'S IMPORT ROUTE (the P3a
 * review, small 1). The machine's sentences name "'Import model pair…' below"; a MediaTek row is
 * offered no import (no zip is published for its pair — `NpuAssetImport.panelOfferedOn`), so the
 * controller's one publish funnel words each Failed state through `NpuPackFetch.reasonFor` with
 * the device's rule before any surface sees it.
 *
 * EXECUTED through the controller: its private `publish` is invoked by reflection with a refusal
 * the machine writes — the Task failure listener's sideload answer and the install's empty
 * delivery — while the controller holds no application context, which resolves no family: the
 * rule then answers "no import route" (the off-census answer), and the published state must name
 * none. The Qualcomm half of the rule is the verbatim reason, executed in `NpuPackFetchTest`; the
 * wiring that reads the app's family memo is pinned below as source.
 */
class NpuPackControllerReasonTest {

    private val cls = NpuPackController::class.java

    private val state: MutableStateFlow<NpuPackFetch.FetchState>
        get() = cls.getDeclaredField("_state").apply { isAccessible = true }.get(null)
            .let { @Suppress("UNCHECKED_CAST") (it as MutableStateFlow<NpuPackFetch.FetchState>) }

    private fun publish(next: NpuPackFetch.FetchState) {
        val method = cls.getDeclaredMethod(
            "publish", String::class.java, String::class.java, NpuPackFetch.FetchState::class.java,
        )
        method.isAccessible = true
        method.invoke(NpuPackController, "npu-turbo", "npu_turbo_mt6989_enc+npu_turbo_mt6989_dec", next)
    }

    @After
    fun backToRest() {
        state.value = NpuPackFetch.FetchState.Idle
    }

    @Test
    fun aPublishedRefusalNamesNoImportTheDeviceIsNotOffered() {
        val appContext = cls.getDeclaredField("appContext").apply { isAccessible = true }.get(null)
        assertEquals("no application context: no family, so no import route — the rule's off-census answer", null, appContext)
        val mt6989 = NpuPackFetch.packsFor("npu-turbo", NpuFleetCensus.familyById("mt6989"))
        for (reason in listOf(
            NpuPackFetch.failureReason(NpuPackFetch.ERROR_APP_NOT_OWNED),
            NpuPackFetch.failureReason(NpuPackFetch.ERROR_APP_UNAVAILABLE),
            NpuPackFetch.failureReason(NpuPackFetch.ERROR_PACK_UNAVAILABLE),
            NpuPackFetch.emptyDeliveryRefusal(mt6989),
        )) {
            assertTrue("the machine's own sentence names the import: <<$reason>>", reason.contains("Import model pair"))
            publish(NpuPackFetch.FetchState.Failed(reason))
            val shown = state.value
            assertTrue("a refusal is still published as a refusal", shown is NpuPackFetch.FetchState.Failed)
            val words = (shown as NpuPackFetch.FetchState.Failed).reason
            assertEquals("…worded by the one rule", NpuPackFetch.reasonFor(reason, importRoute = false), words)
            assertFalse("…and names no import the device is not offered: <<$words>>", words.contains("Import model pair"))
        }
        // Any other state is published as it is.
        publish(NpuPackFetch.FetchState.Downloading(10L, 100L))
        assertEquals(NpuPackFetch.FetchState.Downloading(10L, 100L), state.value)
    }

    @Test
    fun theFunnelReadsTheDevicesImportRuleOffTheAppsFamilyMemo() {
        val src = File(
            generateSequence(File(System.getProperty("user.dir") ?: ".").absoluteFile) { it.parentFile }
                .flatMap {
                    sequenceOf(
                        File(it, "src/main/java/com/whispereverywhere/npu/NpuPackController.kt"),
                        File(it, "app/src/main/java/com/whispereverywhere/npu/NpuPackController.kt"),
                    )
                }
                .first { it.isFile }
                .path,
        ).readText().replace("\r\n", "\n")
        val publish = src.substringAfter("    private fun publish(tierId: String, packName: String, next: NpuPackFetch.FetchState) {\n")
            .substringBefore("\n    }\n")
        assertEquals(
            "the one publish funnel words a Failed state for the device",
            1,
            publish.split("NpuPackFetch.FetchState.Failed(NpuPackFetch.reasonFor(next.reason, importRouteOffered()))").size - 1,
        )
        assertEquals(
            "…from the panel's own rule over the app's family memo",
            1,
            src.split("NpuAssetImport.panelOfferedOn((appContext as? WhisperEverywhereApp)?.npuSocFamily)").size - 1,
        )
        assertEquals("…and the state is written once there", 1, publish.split("_state.value = ").size - 1)
    }
}
