package com.alexdremov.notate.data

import android.content.Context
import com.alexdremov.notate.config.CanvasConfig
import com.alexdremov.notate.data.region.RegionManager
import com.alexdremov.notate.data.region.RegionStorage
import com.alexdremov.notate.util.ZipUtils
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class CanvasRepositorySafeSaveTest {
    private lateinit var context: Context
    private lateinit var repository: CanvasRepository
    private lateinit var testDir: File

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        repository = CanvasRepository(context)
        testDir = File(context.cacheDir, "test_safe_save")
        if (testDir.exists()) testDir.deleteRecursively()
        testDir.mkdirs()
    }

    @After
    fun teardown() {
        if (testDir.exists()) testDir.deleteRecursively()
    }

    private fun createValidCanvasZip(path: String): File =
        runBlocking {
            val tempDir = File(testDir, "temp_build_${System.nanoTime()}")
            tempDir.mkdirs()

            val metadata = CanvasData(version = 3, regionSize = CanvasConfig.DEFAULT_REGION_SIZE)
            val storage = RegionStorage(tempDir)
            storage.init()

            // Create one dummy region
            val regionManager = RegionManager(storage, metadata.regionSize)
            regionManager.saveAll()

            val manifestFile = File(tempDir, "manifest.bin")
            val metaBytes =
                kotlinx.serialization.protobuf.ProtoBuf
                    .encodeToByteArray(CanvasData.serializer(), metadata)
            manifestFile.writeBytes(metaBytes)

            val zipFile = File(path)
            ZipUtils.zip(tempDir, zipFile)
            tempDir.deleteRecursively()
            zipFile
        }

    @Test
    fun `test save throws IllegalStateException when background unzip fails`() =
        runBlocking {
            val path = File(testDir, "broken_unzip.notate").absolutePath
            createValidCanvasZip(path)

            // Mock ZipUtils to throw exception during unzipSkippingExisting
            mockkObject(ZipUtils)
            every { ZipUtils.unzipSkippingExisting(any(), any()) } throws RuntimeException("Simulated Unzip Failure")

            // Allow readManifest to work so we enter the optimized path
            every { ZipUtils.readManifest(any()) } returns CanvasData()
            every { ZipUtils.extractFile(any(), any(), any()) } returns true

            val session = repository.openCanvasSession(path)!!

            // Wait for the background job to complete (and fail)
            try {
                session.waitForInitialization()
            } catch (e: Exception) {
                // Expected that waitForInitialization might throw or the job just completes with failure
            }

            assertTrue("Initialization should be marked as failed", session.initializationFailed)

            // Attempt to save
            assertThrows(IllegalStateException::class.java) {
                runBlocking {
                    repository.saveCanvasSession(path, session)
                }
            }

            unmockkObject(ZipUtils)
            session.close()
        }
}
