package com.whispereverywhere.model

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.whispereverywhere.data.local.PreferencesManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WhisperModelManagerInstrumentedTest {

    private lateinit var manager: WhisperModelManager
    private lateinit var prefs: PreferencesManager

    @Before
    fun setUp() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        prefs = PreferencesManager(ctx)
        manager = WhisperModelManager(ctx, prefs)
        // Clean any leftover models from previous runs.
        manager.catalog.forEach { manager.delete(it) }
        prefs.selectedModelId = null
    }

    @Test
    fun modelsDir_isCreatedUnderFilesDir() {
        val dir = manager.modelsDir()
        assertTrue(dir.exists())
        assertTrue(dir.absolutePath.endsWith("/models"))
    }

    @Test
    fun installedModel_isNull_whenNothingSelectedOrDownloaded() {
        assertNull(manager.installedModel())
        assertNull(manager.installedModelPath())
    }

    @Test
    fun deviceRam_isPositive() {
        assertTrue(manager.deviceTotalRamBytes() > 0L)
    }

    @Test
    fun download_eco_thenInstalledAndPathResolves() = runBlocking {
        val eco = manager.modelById("eco")!!
        manager.download(eco) { soFar, total ->
            assertTrue(total > 0)
            assertTrue(soFar in 0..total)
        }
        prefs.selectedModelId = "eco"

        assertTrue(manager.isInstalled(eco))
        assertEquals(eco.id, manager.installedModel()?.id)
        val path = manager.installedModelPath()
        assertNotNull(path)
        assertTrue(path!!.endsWith(eco.fileName))
    }
}
