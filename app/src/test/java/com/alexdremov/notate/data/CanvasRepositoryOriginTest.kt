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
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class CanvasRepositoryOriginTest {
    private lateinit var context: Context
    private lateinit var repository: CanvasRepository
    private lateinit var testDir: File

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        repository = CanvasRepository(context)
        testDir = File(context.cacheDir, "test_origin")
        if (testDir.exists()) testDir.deleteRecursively()
        testDir.mkdirs()
    }

    @Test
    fun `test zombie session fix - recreated file should wipe cache`() =
        runBlocking {
            val path = File(testDir, "zombie_test.notate").absolutePath

            // 1. Create a document and scribble
            val session1 = repository.openCanvasSession(path)!!

            // Update metadata to simulate change
            val customUuid = UUID.randomUUID().toString()
            session1.updateMetadata(session1.metadata.copy(uuid = customUuid, offsetX = 123f))
            repository.saveCanvasSession(path, session1)

            val savedFile = File(path)
            assertTrue(savedFile.exists())
            val firstTimestamp = savedFile.lastModified()

            // 2. Close session
            repository.releaseCanvasSession(session1)

            // 3. Delete file
            savedFile.delete()
            assertFalse(savedFile.exists())

            // Wait a bit to ensure timestamp change
            Thread.sleep(1100)

            // 4. Create NEW file with same name
            savedFile.createNewFile()
            assertTrue(savedFile.exists())
            val secondTimestamp = savedFile.lastModified()
            assertNotEquals("Timestamps should differ", firstTimestamp, secondTimestamp)

            // 5. Open session again
            val session2 = repository.openCanvasSession(path)!!

            // 6. Verify it's a NEW session (wiped cache)
            assertNotEquals("Should NOT have the old UUID", customUuid, session2.metadata.uuid)
            assertEquals("Should have default offsetX", 0f, session2.metadata.offsetX)

            repository.releaseCanvasSession(session2)
        }

    @Test
    fun `test stale detection - size change should reload`() =
        runBlocking {
            val path = File(testDir, "size_test.notate").absolutePath

            val session1 = repository.openCanvasSession(path)!!
            val uuid1 = UUID.randomUUID().toString()
            session1.updateMetadata(session1.metadata.copy(uuid = uuid1))
            repository.saveCanvasSession(path, session1)

            // Change it in cache but NOT on disk
            val uuid2 = UUID.randomUUID().toString()
            session1.updateMetadata(session1.metadata.copy(uuid = uuid2))
            repository.saveCanvasSession(path, session1, commitToZip = false)

            // Release it so it's not in activeSessions but remains on disk cache
            repository.releaseCanvasSession(session1)

            val file = File(path)
            val originalSize = file.length()

            // Modify file size externally
            file.appendText("extra data")

            // Re-open. Cache exists (with uuid2) but size mismatch.
            val session2 = repository.openCanvasSession(path)!!

            // Should have reloaded from disk (uuid1) NOT cache (uuid2)
            assertEquals("Should have reloaded uuid1 from disk", uuid1, session2.metadata.uuid)
            assertNotEquals("Should NOT have resumed stale cache uuid2", uuid2, session2.metadata.uuid)

            repository.releaseCanvasSession(session2)
        }

    @Test
    fun `test stale detection - timestamp change should reload`() =
        runBlocking {
            val path = File(testDir, "time_test.notate").absolutePath

            val session1 = repository.openCanvasSession(path)!!
            val uuid1 = UUID.randomUUID().toString()
            session1.updateMetadata(session1.metadata.copy(uuid = uuid1))
            repository.saveCanvasSession(path, session1)

            // Change it in cache but NOT on disk
            val uuid2 = UUID.randomUUID().toString()
            session1.updateMetadata(session1.metadata.copy(uuid = uuid2))
            repository.saveCanvasSession(path, session1, commitToZip = false)

            repository.releaseCanvasSession(session1)

            val file = File(path)
            val originalTime = file.lastModified()

            // Wait and update timestamp
            Thread.sleep(1100)
            file.setLastModified(System.currentTimeMillis())

            // Re-open.
            val session2 = repository.openCanvasSession(path)!!
            assertEquals("Should have reloaded uuid1 from disk due to timestamp change", uuid1, session2.metadata.uuid)

            repository.releaseCanvasSession(session2)
        }

    @Test
    fun `test crash recovery - manifest newer than file should resume`() =
        runBlocking {
            val path = File(testDir, "recovery_test.notate").absolutePath

            // 1. Create a document and scribble
            val session1 = repository.openCanvasSession(path)!!
            val customUuid = UUID.randomUUID().toString()
            session1.updateMetadata(session1.metadata.copy(uuid = customUuid, offsetX = 456f))

            // Simulate crash: we DON'T save to zip, but we DO update the manifest in the cache
            repository.saveCanvasSession(path, session1, commitToZip = false)

            // MUST release for this test to ensure it's not a hot handoff, but a cold resume
            repository.releaseCanvasSession(session1)

            // 2. Re-open.
            val session2 = repository.openCanvasSession(path)!!

            // 3. Verify it resumed
            assertEquals("Should HAVE the old UUID", customUuid, session2.metadata.uuid)
            assertEquals("Should HAVE the old offsetX", 456f, session2.metadata.offsetX)

            repository.releaseCanvasSession(session2)
        }

    @Test
    fun `test external modification during session - conflict copy`() =
        runBlocking {
            val path = File(testDir, "conflict_test.notate").absolutePath

            val session1 = repository.openCanvasSession(path)!!
            repository.saveCanvasSession(path, session1) // Establish baseline

            // Modify file externally while session is open
            Thread.sleep(1100)
            File(path).setLastModified(System.currentTimeMillis())

            // Save session. Should detect conflict and save to a new file.
            val result = repository.saveCanvasSession(path, session1)

            assertNotEquals("Should have saved to a different path due to conflict", path, result.savedPath)
            assertTrue("Conflict file should contain timestamp", result.savedPath.contains("_conflict_"))

            repository.releaseCanvasSession(session1)
        }

    @Test
    fun `test manifest older than file - should reload`() =
        runBlocking {
            val path = File(testDir, "manifest_older.notate").absolutePath

            // 1. Create document
            val session1 = repository.openCanvasSession(path)!!
            val uuid1 = UUID.randomUUID().toString()
            session1.updateMetadata(session1.metadata.copy(uuid = uuid1))
            repository.saveCanvasSession(path, session1)
            repository.releaseCanvasSession(session1)

            // 2. Open again, make changes but only in cache (sim crash)
            val session2 = repository.openCanvasSession(path)!!
            val uuid2 = UUID.randomUUID().toString()
            session2.updateMetadata(session2.metadata.copy(uuid = uuid2))
            repository.saveCanvasSession(path, session2, commitToZip = false)
            repository.releaseCanvasSession(session2) // Release but it's on disk cache

            // 3. Modify origin file EXTERNALLY to be NEWER than cache manifest
            Thread.sleep(1100)
            File(path).setLastModified(System.currentTimeMillis())

            // 4. Open again
            val session3 = repository.openCanvasSession(path)!!

            // Cache should be treated as stale because File is newer than Manifest
            assertNotEquals("Should NOT have uuid2 from stale cache", uuid2, session3.metadata.uuid)
            assertEquals("Should have reloaded uuid1 from file", uuid1, session3.metadata.uuid)

            repository.releaseCanvasSession(session3)
        }
}
