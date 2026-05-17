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

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class CanvasRepositoryCachingTest {
    private lateinit var context: Context
    private lateinit var repository: CanvasRepository
    private lateinit var testDir: File

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        repository = CanvasRepository(context)
        testDir = File(context.cacheDir, "test_caching")
        if (testDir.exists()) testDir.deleteRecursively()
        testDir.mkdirs()
    }

    @Test
    fun `test active session caching - same file should return same instance`() =
        runBlocking {
            val path = File(testDir, "cache_test.notate").absolutePath

            // 1. Open session first time
            val session1 = repository.openCanvasSession(path)!!

            // 2. Open session second time without releasing first
            val session2 = repository.openCanvasSession(path)!!

            // 3. They MUST be the exact same instance (cached)
            assertSame("Should return the exact same session instance from cache", session1, session2)

            repository.releaseCanvasSession(session1)
            repository.releaseCanvasSession(session2)
        }

    @Test
    fun `test session caching after release - should return same instance if not closed`() =
        runBlocking {
            val path = File(testDir, "hot_handoff_test.notate").absolutePath

            val session1 = repository.openCanvasSession(path)!!

            // Release session1, but it might still be in activeSessions if no close was triggered
            // Actually, release returns if it was the last client.
            repository.releaseCanvasSession(session1)

            // Since it was the only client, it should have been closed and removed from cache.
            // Let's verify that a NEW open returns a DIFFERENT instance (reloaded)
            val session2 = repository.openCanvasSession(path)!!
            assertNotSame("Should return a new instance after full release/close", session1, session2)

            repository.releaseCanvasSession(session2)
        }

    @Test
    fun `test session persistence - cold re-open should return different instance but same state`() =
        runBlocking {
            val path = File(testDir, "persistence_test.notate").absolutePath

            val session1 = repository.openCanvasSession(path)!!
            session1.updateMetadata(session1.metadata.copy(offsetX = 999f))
            repository.saveCanvasSession(path, session1)
            repository.releaseCanvasSession(session1)

            // Cold re-open (new instance, but reuses session directory if valid)
            val session2 = repository.openCanvasSession(path)!!

            assertNotSame("Should be a different instance", session1, session2)
            assertEquals("Should have persisted state", 999f, session2.metadata.offsetX)

            repository.releaseCanvasSession(session2)
        }
}
