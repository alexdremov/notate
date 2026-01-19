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
class CanvasRepositorySafeWriteTest {
    private lateinit var context: Context
    private lateinit var repository: CanvasRepository
    private lateinit var testDir: File

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        repository = CanvasRepository(context)
        testDir = File(context.cacheDir, "test_safe_write")
        if (testDir.exists()) testDir.deleteRecursively()
        testDir.mkdirs()
    }

    @Test
    fun `test safe atomic write success`() =
        runBlocking {
            val path = File(testDir, "test.notate").absolutePath
            val data =
                CanvasData(
                    canvasType = CanvasType.INFINITE,
                    pageWidth = 1000f,
                    pageHeight = 1000f,
                    backgroundStyle = BackgroundStyle.Blank(),
                )

            // Initial write
            repository.saveCanvas(path, data)
            val targetFile = File(path)
            assertTrue("Target file should exist", targetFile.exists())

            // Modify data
            val newData = data.copy(pageWidth = 2000f)
            repository.saveCanvas(path, newData)

            assertTrue("Target file should still exist", targetFile.exists())
            assertFalse("Backup file should be deleted on success", File(path.replace(".notate", ".notate.bak")).exists())
            assertFalse("Temp file should be deleted", File(path.replace(".notate", ".notate.tmp")).exists())

            val loaded = repository.loadCanvas(path)
            assertNotNull(loaded)
            assertEquals(2000f, loaded!!.canvasState.pageWidth, 0.1f)
        }
}
