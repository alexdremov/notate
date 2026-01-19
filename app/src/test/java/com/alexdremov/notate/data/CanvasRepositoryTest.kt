package com.alexdremov.notate.data

import android.content.Context
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
    fun `test atomic write success`() =
        runBlocking {
            val path = File(testDir, "test.notate").absolutePath
            val data =
                CanvasData(
                    canvasType = CanvasType.INFINITE,
                    pageWidth = 1000f,
                    pageHeight = 1000f,
                    backgroundStyle = BackgroundStyle.Blank(),
                )

            repository.saveCanvas(path, data)

            val targetFile = File(path)
            assertTrue("Target file should exist", targetFile.exists())
            assertFalse("Temp file should not exist", File(testDir, "test.notate.tmp").exists())

            val loaded = repository.loadCanvas(path)
            assertNotNull(loaded)
            assertEquals(CanvasType.INFINITE, loaded!!.canvasState.canvasType)
        }

    @Test
    fun `test cleanup on write failure`() =
        runBlocking {
            val path = File(testDir, "test.notate").absolutePath
            val tmpFile = File(testDir, "test.notate.tmp")

            val data =
                CanvasData(
                    canvasType = CanvasType.INFINITE,
                    pageWidth = 1000f,
                    pageHeight = 1000f,
                    backgroundStyle = BackgroundStyle.Blank(),
                )

            repository.saveCanvas(path, data)
            assertFalse("Temp file should be gone", tmpFile.exists())
        }

    @Test
    fun `test load fails with combined exception`() =
        runBlocking {
            val path = File(testDir, "corrupted.notate").absolutePath
            File(path).writeBytes(byteArrayOf(0x01, 0x02, 0x03)) // Random garbage

            val result = repository.loadCanvas(path)
            assertNull("Should return null for corrupted file", result)
        }
}
