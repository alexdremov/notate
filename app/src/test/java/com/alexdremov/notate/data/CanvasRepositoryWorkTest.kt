package com.alexdremov.notate.data

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.alexdremov.notate.data.io.FileLockManager
import com.alexdremov.notate.model.StrokeType
import com.google.common.util.concurrent.ListenableFuture
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.After
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
class CanvasRepositoryWorkTest {
    private lateinit var context: Context
    private lateinit var repository: CanvasRepository
    private lateinit var testDir: File
    private lateinit var workManager: WorkManager

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()

        // Mock WorkManager to avoid database leaks and heavy initialization
        workManager = mockk(relaxed = true)
        mockkStatic(WorkManager::class)
        every { WorkManager.getInstance(any()) } returns workManager

        repository = CanvasRepository(context)
        testDir = File(context.cacheDir, "test_work_canvases")
        if (testDir.exists()) testDir.deleteRecursively()
        testDir.mkdirs()
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `saveAndCloseSession enqueues WorkManager task`() {
        runBlocking {
            val path = File(testDir, "work_save.notate").absolutePath
            // Create an empty file to start
            File(path).createNewFile()

            val session = repository.openCanvasSession(path)
            assertNotNull(session)

            // Make some changes
            session!!.regionManager.addItem(
                com.alexdremov.notate.model.Stroke(
                    path = android.graphics.Path(),
                    points = listOf(),
                    color = 0,
                    width = 1f,
                    style = StrokeType.BALLPOINT,
                    bounds = android.graphics.RectF(0f, 0f, 10f, 10f),
                    strokeOrder = 1,
                ),
            )

            // Mock the work info return
            val mockWorkInfo = mockk<WorkInfo>()
            every { mockWorkInfo.state } returns WorkInfo.State.ENQUEUED
            val mockFuture = mockk<ListenableFuture<List<WorkInfo>>>()
            every { mockFuture.get() } returns listOf(mockWorkInfo)
            every { workManager.getWorkInfosForUniqueWork(any()) } returns mockFuture

            // Trigger Save and Close
            repository.saveAndCloseSession(path, session)

            // Verify WorkManager request
            val uniqueWorkName = "SaveWorker_${session.sessionDir.name}"

            verify {
                workManager.enqueueUniqueWork(
                    eq(uniqueWorkName),
                    eq(ExistingWorkPolicy.REPLACE),
                    any<OneTimeWorkRequest>(),
                )
            }

            // Also verify session is closed
            assertTrue("Session should be closed", session.isClosed())
        }
    }

    @Test
    fun `openCanvasSession retries on locked file`() {
        runBlocking {
            mockkObject(FileLockManager)
            val path = File(testDir, "locked.notate").absolutePath

            // Fail twice, then succeed
            val validLock = mockk<FileLockManager.LockedFileHandle>(relaxed = true)
            every { FileLockManager.acquire(path) }
                .throws(IllegalStateException("Locked 1"))
                .andThenThrows(IllegalStateException("Locked 2"))
                .andThen(validLock)

            val session = repository.openCanvasSession(path)

            assertNotNull("Should succeed after retries", session)
            verify(atLeast = 3) { FileLockManager.acquire(path) }

            session?.close()
        }
    }
}
