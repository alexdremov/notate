package com.alexdremov.notate.data

import android.content.Context
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
class CanvasRepositoryZombieReproductionTest {
    private lateinit var context: Context
    private lateinit var repository: CanvasRepository
    private lateinit var testDir: File

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        repository = CanvasRepository(context)
        testDir = File(context.cacheDir, "test_zombie_repro")
        if (testDir.exists()) testDir.deleteRecursively()
        testDir.mkdirs()
    }

    @Test
    fun `reproduce zombie session - in memory cache should not return stale session after file recreation`() =
        runBlocking {
            val path = File(testDir, "zombie_repro.notate").absolutePath
            val file = File(path)

            // 1. Create a document and scribble
            val session1 = repository.openCanvasSession(path)!!
            val uuid1 = "original-uuid"
            session1.updateMetadata(session1.metadata.copy(uuid = uuid1))
            repository.saveCanvasSession(path, session1)

            // KEEP session1 open (don't release) to keep it in activeSessions

            // 2. Delete the file externally (simulating user action)
            file.delete()
            assertFalse(file.exists())

            // 3. Create a NEW file with same name
            // We'll give it a new UUID to simulate a truly new document
            val uuid2 = "new-uuid"
            val newSession = repository.openCanvasSession(path)!! // This is what the "New Canvas" button effectively does
            newSession.updateMetadata(newSession.metadata.copy(uuid = uuid2))
            repository.saveCanvasSession(path, newSession)
            repository.releaseCanvasSession(newSession)

            assertTrue(file.exists())

            // 4. Now attempt to "Open" the document again.
            // If the bug exists, it might return session1 because it's still in activeSessions!
            val session3 = repository.openCanvasSession(path)!!

            assertNotEquals("Should NOT have returned session1", uuid1, session3.metadata.uuid)
            assertEquals("Should have returned the newly created session2 content", uuid2, session3.metadata.uuid)

            repository.releaseCanvasSession(session1)
            repository.releaseCanvasSession(session3)
        }

    @Test
    fun `reproduce zombie session - identical timestamp and size should still detect change via UUID`() =
        runBlocking {
            val path = File(testDir, "uuid_repro.notate").absolutePath
            val file = File(path)

            // 1. Create document 1
            val session1 = repository.openCanvasSession(path)!!
            val uuid1 = "uuid-1"
            session1.updateMetadata(session1.metadata.copy(uuid = uuid1))
            repository.saveCanvasSession(path, session1)

            val info1 = file.lastModified() to file.length()
            repository.releaseCanvasSession(session1)

            // 2. Delete and recreate with SAME timestamp and size (hypothetically)
            file.delete()

            // Recreate with different UUID but FORCE same timestamp/size if possible
            // Actually, we'll just test if UUID check catches it when we DON'T sleep.
            val session2 = repository.openCanvasSession(path)!!
            val uuid2 = "uuid-2"
            session2.updateMetadata(session2.metadata.copy(uuid = uuid2))
            repository.saveCanvasSession(path, session2)

            // Force same timestamp as doc 1
            file.setLastModified(info1.first)
            // If size is also same (likely for empty/small docs), timestamp+size check fails!

            repository.releaseCanvasSession(session2)

            // 3. Open session 3. It has same path, time, size as session 1.
            // But session 1 is in disk cache.
            val session3 = repository.openCanvasSession(path)!!

            assertEquals("Should have detected change via UUID even if metadata matches", uuid2, session3.metadata.uuid)

            repository.releaseCanvasSession(session3)
        }
}
