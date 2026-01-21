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
            val metadata =
                CanvasData(
                    canvasType = CanvasType.INFINITE,
                    pageWidth = 1000f,
                    pageHeight = 1000f,
                    backgroundStyle = BackgroundStyle.Blank(),
                )

            val sessionDir = File(context.cacheDir, "temp_session_safe")
            sessionDir.mkdirs()
            val storage = RegionStorage(sessionDir)
            storage.init()
            val regionManager = RegionManager(storage, metadata.regionSize)

            val session = CanvasSession(
                sessionDir = sessionDir,
                regionManager = regionManager,
                originLastModified = 0L,
                originSize = 0L,
                metadata = metadata
            )

            // Initial write
            val result1 = repository.saveCanvasSession(path, session)
            assertEquals(path, result1.savedPath)
            val targetFile = File(path)
            assertTrue("Target file should exist", targetFile.exists())

            // Modify data using the session's updateMetadata method
            val newMetadata = metadata.copy(pageWidth = 2000f)
            session.updateMetadata(newMetadata)
            val result2 = repository.saveCanvasSession(path, session)
            assertEquals(path, result2.savedPath)

            assertTrue("Target file should still exist", targetFile.exists())
            assertFalse("Backup file should be deleted on success", File(path + ".bak").exists())
            assertFalse("Temp file should be deleted", File(path + ".tmp").exists())

            // Close the session before reopening
            session.close()

            val loadedSession = repository.openCanvasSession(path)
            assertNotNull(loadedSession)
            assertEquals(2000f, loadedSession!!.metadata.pageWidth, 0.1f)
        }
}
