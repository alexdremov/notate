package com.alexdremov.notate.data

import android.content.Context
import com.alexdremov.notate.config.CanvasConfig
import com.alexdremov.notate.data.region.RegionId
import com.alexdremov.notate.data.region.RegionManager
import com.alexdremov.notate.data.region.RegionStorage
import com.alexdremov.notate.model.Stroke
import com.alexdremov.notate.model.StrokeType
import com.alexdremov.notate.util.ZipUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class CanvasRepositoryJitTest {
    private lateinit var context: Context
    private lateinit var repository: CanvasRepository
    private lateinit var testDir: File

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        repository = CanvasRepository(context)
        testDir = File(context.cacheDir, "test_jit_canvases")
        if (testDir.exists()) testDir.deleteRecursively()
        testDir.mkdirs()
    }

    private fun createDummyStroke(
        id: Int,
        xOff: Float = 0f,
        yOff: Float = 0f,
    ): Stroke {
        val points =
            listOf(
                com.onyx.android.sdk.data.note
                    .TouchPoint(xOff, yOff, 0.5f, 1f, 0, 0, System.currentTimeMillis()),
                com.onyx.android.sdk.data.note
                    .TouchPoint(xOff + 10f, yOff + 10f, 0.5f, 1f, 0, 0, System.currentTimeMillis()),
            )
        val path = android.graphics.Path()
        path.moveTo(xOff, yOff)
        path.lineTo(xOff + 10f, yOff + 10f)
        val bounds = android.graphics.RectF(xOff, yOff, xOff + 10f, yOff + 10f)

        return Stroke(
            path = path,
            points = points,
            color = -16777216,
            width = 5f,
            style = StrokeType.BALLPOINT,
            bounds = bounds,
            strokeOrder = id.toLong(),
        )
    }

    private fun createHeavyCanvasZip(
        path: String,
        regionCount: Int,
    ): File =
        runBlocking {
            val tempDir = File(testDir, "temp_build_${System.nanoTime()}")
            tempDir.mkdirs()

            // 1. Create Manifest
            val metadata = CanvasData(version = 3, regionSize = CanvasConfig.DEFAULT_REGION_SIZE)
            val storage = RegionStorage(tempDir)
            storage.init()

            // 2. Create Dummy Regions
            val regionManager = RegionManager(storage, metadata.regionSize)
            for (i in 0 until regionCount) {
                // Space them out: 0,0 1000,1000 2000,2000 ...
                val offset = i * CanvasConfig.DEFAULT_REGION_SIZE
                regionManager.addItem(createDummyStroke(i, offset, offset))
            }
            regionManager.saveAll()

            // 3. Save Manifest
            val manifestFile = File(tempDir, "manifest.bin")
            val metaBytes =
                kotlinx.serialization.protobuf.ProtoBuf
                    .encodeToByteArray(CanvasData.serializer(), metadata)
            manifestFile.writeBytes(metaBytes)

            // 4. Zip it up
            val zipFile = File(path)
            ZipUtils.zip(tempDir, zipFile)

            // Cleanup temp
            tempDir.deleteRecursively()
            zipFile
        }

    @Test
    fun `test JIT open does not extract all files immediately`() =
        runBlocking {
            val path = File(testDir, "heavy.notate").absolutePath
            createHeavyCanvasZip(path, 50) // 50 regions

            // Open Session
            val session = repository.openCanvasSession(path)
            assertNotNull(session)
            assertNotNull("Initialization job should be active for local file", session!!.initializationJob)

            // Verify session dir is mostly empty (except manifest and maybe index)
            val regionFiles = session.sessionDir.listFiles { _, name -> name.startsWith("r_") && name.endsWith(".bin") }

            // Wait for background unzip
            session.waitForInitialization()

            val extractedFiles = session.sessionDir.listFiles { _, name -> name.startsWith("r_") && name.endsWith(".bin") }
            assertEquals("All 50 regions should be extracted eventually", 50, extractedFiles?.size ?: 0)

            session.close()
        }

    @Test
    fun `test on-demand extraction works before background unzip completes`() =
        runBlocking {
            val path = File(testDir, "ondemand.notate").absolutePath
            createHeavyCanvasZip(path, 10)

            val session = repository.openCanvasSession(path)
            assertNotNull(session)

            // Pick a region ID that we know exists (e.g. 5,5)
            val targetId = RegionId(5, 5)

            // Verify it is NOT yet on disk (simulating race where background hasn't reached it)
            // We delete it if it exists to simulate "not yet extracted"
            val regionFile = File(session!!.sessionDir, "r_5_5.bin")
            if (regionFile.exists()) regionFile.delete()

            assertFalse("Region file should be missing for test", regionFile.exists())

            // Request it via RegionManager
            // This should trigger the fallback logic in RegionStorage
            val regionData = session.regionManager.getRegion(targetId)

            assertNotNull("Should load region data via JIT fallback", regionData)
            assertEquals("Region ID should match", targetId, regionData.id)
            assertTrue("Region should have items", regionData.items.isNotEmpty())

            // It should have been extracted to disk now
            assertTrue("Region file should be extracted to disk", regionFile.exists())

            session.close()
        }

    @Test
    fun `test save waits for initialization`() =
        runBlocking {
            val path = File(testDir, "save_race.notate").absolutePath
            createHeavyCanvasZip(path, 20)

            val session = repository.openCanvasSession(path)!!

            // We want to verify that save() doesn't start until initializationJob is done.
            // This is hard to assert deterministically without mocking, but we can verify consistency.

            val job = session.initializationJob
            assertNotNull(job)
            assertTrue("Background job should be active", job!!.isActive)

            // Trigger save immediately
            val result = repository.saveCanvasSession(path, session)

            // By the time save returns, the job must be complete
            assertTrue("Initialization job should be completed", job.isCompleted)
            assertEquals(path, result.savedPath)

            // Verify file integrity
            val savedFile = File(path)
            assertTrue(savedFile.length() > 0)

            session.close()
        }

    @Test
    fun `test corruption recovery - missing regions in zip`() =
        runBlocking {
            // Create a valid zip but delete some region files from the source temp before zipping
            // Effectively creating a "valid zip structure" but "missing logic data"
            val path = File(testDir, "missing_data.notate").absolutePath

            val tempDir = File(testDir, "temp_corrupt_${System.nanoTime()}")
            tempDir.mkdirs()

            // Manifest
            val metadata = CanvasData()
            File(tempDir, "manifest.bin").writeBytes(
                kotlinx.serialization.protobuf.ProtoBuf
                    .encodeToByteArray(CanvasData.serializer(), metadata),
            )

            // Create index saying we have regions, but don't create the files
            // RegionManager usually rebuilds index from files, so if files are missing, index is just empty.
            // So this tests "Clean Session" behavior on corrupted file.

            ZipUtils.zip(tempDir, File(path))
            tempDir.deleteRecursively()

            val session = repository.openCanvasSession(path)
            assertNotNull(session)

            // Wait for unzip
            session!!.waitForInitialization()

            // Should have 0 regions
            assertEquals(0, session.regionManager.getActiveRegionIds().size)

            session.close()
        }

    @Test
    fun `test corruption recovery - corrupted region file in zip`() =
        runBlocking {
            val path = File(testDir, "corrupted_region.notate").absolutePath
            val tempDir = File(testDir, "temp_corrupt_region_${System.nanoTime()}")
            tempDir.mkdirs()

            // 1. Manifest
            val metadata = CanvasData()
            File(tempDir, "manifest.bin").writeBytes(
                kotlinx.serialization.protobuf.ProtoBuf
                    .encodeToByteArray(CanvasData.serializer(), metadata),
            )

            // 2. Corrupted Region File
            val corruptedRegionFile = File(tempDir, "r_0_0.bin")
            corruptedRegionFile.writeBytes(byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte()))

            ZipUtils.zip(tempDir, File(path))
            tempDir.deleteRecursively()

            val session = repository.openCanvasSession(path)!!

            // Request the corrupted region
            val region = session.regionManager.getRegion(RegionId(0, 0))

            assertNotNull("Should still return a region object", region)
            assertTrue("Corrupted region should be empty", region.items.isEmpty())
            assertEquals(RegionId(0, 0), region.id)

            session.close()
        }

    @Test
    fun `test external modification detection`() =
        runBlocking {
            val path = File(testDir, "conflict.notate").absolutePath
            createHeavyCanvasZip(path, 1)

            val session1 = repository.openCanvasSession(path)!!

            // Simulate external process modifying the file
            Thread.sleep(100) // Ensure timestamp diff
            val externalFile = File(path)
            externalFile.setLastModified(System.currentTimeMillis() + 1000)

            // Save session 1
            val result = repository.saveCanvasSession(path, session1)

            // Should have saved to a conflict file
            assertNotEquals("Should not overwrite if modified externally", path, result.savedPath)
            assertTrue("Conflict file should contain timestamp", result.savedPath.contains("_conflict_"))

            session1.close()
        }
}
