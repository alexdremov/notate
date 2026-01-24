package com.alexdremov.notate.data

import android.content.Context
import com.alexdremov.notate.data.region.RegionManager
import com.alexdremov.notate.data.region.RegionStorage
import com.alexdremov.notate.model.BackgroundStyle
import com.alexdremov.notate.util.ZipUtils
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class CanvasRepositoryRecoveryTest {
    private lateinit var context: Context
    private lateinit var repository: CanvasRepository
    private lateinit var testDir: File

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        repository = CanvasRepository(context)
        testDir = File(context.cacheDir, "test_recovery")
        if (testDir.exists()) testDir.deleteRecursively()
        testDir.mkdirs()
    }

    @Test
    fun `test crash recovery loads newer local session`() {
        runBlocking {
            // 1. Setup Initial State (Version 1)
            val path = File(testDir, "crash_test.notate").absolutePath
            val metadataV1 =
                CanvasData(
                    pageWidth = 1000f,
                    backgroundStyle = BackgroundStyle.Blank(),
                )

            // Create V1 file manually
            val tempV1 = File(testDir, "temp_v1")
            tempV1.mkdirs()
            File(tempV1, "manifest.bin").writeBytes(
                kotlinx.serialization.protobuf.ProtoBuf
                    .encodeToByteArray(CanvasData.serializer(), metadataV1),
            )
            ZipUtils.zip(tempV1, File(path))
            tempV1.deleteRecursively()

            // Ensure V1 file has an older timestamp
            File(path).setLastModified(System.currentTimeMillis() - 10000)

            // 2. Open Session and Modify (Version 2)
            val session1 = repository.openCanvasSession(path)
            assertNotNull(session1)
            assertEquals(1000f, session1!!.metadata.pageWidth, 0.1f)

            // Modify metadata (Simulate work)
            val metadataV2 = metadataV1.copy(pageWidth = 2000f)
            session1.updateMetadata(metadataV2)

            // Auto-save (Flush only, NO ZIP COMMIT)
            repository.saveCanvasSession(path, session1, commitToZip = false)

            val sessionDir = session1.sessionDir
            assertTrue(sessionDir.exists())

            // 3. Simulate Crash
            repository.releaseCanvasSession(session1)

            // 4. Re-open (Recovery)
            val session2 = repository.openCanvasSession(path)
            assertNotNull(session2)

            // 5. Verify Loaded State is Version 2
            assertEquals("Should recover V2 width from hot cache", 2000f, session2!!.metadata.pageWidth, 0.1f)

            session2.close()
        }
    }

    @Test
    fun `test recovery heals missing source_path file`() {
        runBlocking {
            // 1. Setup Initial State
            val path = File(testDir, "heal_test.notate").absolutePath
            val metadataV1 = CanvasData(pageWidth = 1000f)

            // Create V1 file
            val tempV1 = File(testDir, "temp_v1_heal")
            tempV1.mkdirs()
            File(tempV1, "manifest.bin").writeBytes(
                kotlinx.serialization.protobuf.ProtoBuf
                    .encodeToByteArray(CanvasData.serializer(), metadataV1),
            )
            ZipUtils.zip(tempV1, File(path))
            tempV1.deleteRecursively()
            File(path).setLastModified(System.currentTimeMillis() - 10000)

            // 2. Open and Save to Cache
            val session1 = repository.openCanvasSession(path)!!
            val metadataV2 = metadataV1.copy(pageWidth = 2000f)
            session1.updateMetadata(metadataV2)
            repository.saveCanvasSession(path, session1, commitToZip = false)

            val sessionDir = session1.sessionDir
            repository.releaseCanvasSession(session1)

            // 3. SABOTAGE: Delete source_path.txt
            val sourcePathFile = File(sessionDir, "source_path.txt")
            assertTrue(sourcePathFile.exists())
            sourcePathFile.delete()
            assertFalse(sourcePathFile.exists())

            // 4. Re-open
            // Before fix: This would wipe session and load V1 (1000f).
            // After fix: This should trust hash, heal file, and load V2 (2000f).
            val session2 = repository.openCanvasSession(path)!!

            // 5. Verify Recovery and Healing
            assertEquals("Should recover V2 despite missing source_path.txt", 2000f, session2.metadata.pageWidth, 0.1f)
            assertTrue("source_path.txt should be healed/recreated", sourcePathFile.exists())
            assertEquals("Healed path should match", path, sourcePathFile.readText())

            session2.close()
        }
    }

    @Test
    fun `test ignores stale session if file is newer`() {
        runBlocking {
            // 1. Setup Initial State (Version 1)
            val path = File(testDir, "stale_test.notate").absolutePath
            val metadataV1 = CanvasData(pageWidth = 1000f)

            // Create V1 file
            val tempV1 = File(testDir, "temp_v1")
            tempV1.mkdirs()
            File(tempV1, "manifest.bin").writeBytes(
                kotlinx.serialization.protobuf.ProtoBuf
                    .encodeToByteArray(CanvasData.serializer(), metadataV1),
            )
            ZipUtils.zip(tempV1, File(path))
            tempV1.deleteRecursively()

            // Set file time to OLD
            File(path).setLastModified(System.currentTimeMillis() - 20000)

            // 2. Open and create a local session cache (Version 1)
            val session1 = repository.openCanvasSession(path)!!
            repository.saveCanvasSession(path, session1, commitToZip = false) // Create cache on disk
            val sessionDir = session1.sessionDir

            // Simulate that this cache is OLD (e.g. from last week)
            // We modify the cache files to be very old
            sessionDir.listFiles()?.forEach {
                it.setLastModified(System.currentTimeMillis() - 50000)
            }
            // Also explicitly set manifest time
            File(sessionDir, "manifest.bin").setLastModified(System.currentTimeMillis() - 50000)

            repository.releaseCanvasSession(session1)

            // 3. Update the external file (Simulate Sync update or manual replace)
            // Version 2 File
            val tempV2 = File(testDir, "temp_v2")
            tempV2.mkdirs()
            val metadataV2 = metadataV1.copy(pageWidth = 3000f) // NEW value
            File(tempV2, "manifest.bin").writeBytes(
                kotlinx.serialization.protobuf.ProtoBuf
                    .encodeToByteArray(CanvasData.serializer(), metadataV2),
            )
            ZipUtils.zip(tempV2, File(path))
            tempV2.deleteRecursively()

            // Ensure File is NEWER than the cache we created
            File(path).setLastModified(System.currentTimeMillis())

            // 4. Re-open
            val session2 = repository.openCanvasSession(path)!!

            // 5. Verify it loaded from FILE (V2), ignoring the stale cache (V1)
            assertEquals("Should load V2 from file because cache was stale", 3000f, session2.metadata.pageWidth, 0.1f)

            session2.close()
        }
    }
}
