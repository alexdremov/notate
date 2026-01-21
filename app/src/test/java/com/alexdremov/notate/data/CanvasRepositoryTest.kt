package com.alexdremov.notate.data

import android.content.Context
import com.alexdremov.notate.data.region.RegionManager
import com.alexdremov.notate.data.region.RegionStorage
import com.alexdremov.notate.model.BackgroundStyle
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class CanvasRepositoryTest {
    private lateinit var context: Context
    private lateinit var repository: CanvasRepository
    private lateinit var testDir: File

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        repository = CanvasRepository(context)
        testDir = File(context.cacheDir, "test_canvases")
        if (testDir.exists()) testDir.deleteRecursively()
        testDir.mkdirs()
    }

    @Test
    fun `test session save and load success`() =
        runBlocking {
            val path = File(testDir, "test.notate").absolutePath
            val metadata =
                CanvasData(
                    canvasType = CanvasType.INFINITE,
                    pageWidth = 1000f,
                    pageHeight = 1000f,
                    backgroundStyle = BackgroundStyle.Blank(),
                )

            val sessionDir = File(context.cacheDir, "temp_session_save")
            sessionDir.mkdirs()
            val storage = RegionStorage(sessionDir)
            storage.init()
            val regionManager = RegionManager(storage, metadata.regionSize)

            val session = CanvasSession(sessionDir, metadata, regionManager)

            val saveResult = repository.saveCanvasSession(path, session)
            assertEquals(path, saveResult.savedPath)

            val targetFile = File(path)
            assertTrue("Target file should exist", targetFile.exists())

            val result = repository.loadCanvas(path)
            assertNotNull(result)
            assertEquals(CanvasType.INFINITE, result!!.canvasState.canvasType)
            assertNotNull(result.session)
        }

    @Test
    fun `test load fails with corrupted zip`() =
        runBlocking {
            val path = File(testDir, "corrupted.notate").absolutePath
            File(path).writeBytes(byteArrayOf(0x01, 0x02, 0x03)) // Random garbage (not a zip)

            val result = repository.loadCanvas(path)
            assertNull("Should return null for corrupted file", result)
        }
}
