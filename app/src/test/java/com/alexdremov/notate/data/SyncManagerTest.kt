package com.alexdremov.notate.data

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.InputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SyncManagerTest {
    private lateinit var context: Context
    private lateinit var canvasRepository: CanvasRepository
    private lateinit var syncManager: SyncManager
    private val testProjectId = "test_project_id"

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        // We can use a mock repository or just a dummy one since we won't really use it for this test
        canvasRepository = CanvasRepository(context)

        // Setup initial preferences
        val projectConfig = ProjectConfig(testProjectId, "Test Project", "/tmp/test")
        PreferencesManager.addProject(context, projectConfig)

        val storageConfig = RemoteStorageConfig("storage_id", "TestStorage", RemoteStorageType.WEBDAV, "http://localhost", "user")
        val syncConfig = ProjectSyncConfig(testProjectId, "storage_id", "/remote/path", isEnabled = true)

        // Save these to SharedPreferences so SyncManager can find them
        SyncPreferencesManager.saveRemoteStorages(context, listOf(storageConfig))
        SyncPreferencesManager.updateProjectSyncConfig(context, syncConfig)
        SyncPreferencesManager.savePassword(context, "storage_id", "password")
    }

    @Test
    fun `test sync interruption`() =
        runTest {
            // Create a provider that hangs forever
            val hangingProvider =
                object : RemoteStorageProvider {
                    override suspend fun listFiles(remotePath: String): List<RemoteFile> {
                        delay(Long.MAX_VALUE) // Hang forever
                        return emptyList()
                    }

                    override suspend fun uploadFile(
                        remotePath: String,
                        inputStream: InputStream,
                    ) = true

                    override suspend fun downloadFile(remotePath: String): InputStream? = null

                    override suspend fun createDirectory(remotePath: String) = true

                    override suspend fun deleteFile(remotePath: String) = true
                }

            val testDispatcher = UnconfinedTestDispatcher(testScheduler)
            syncManager = SyncManager(context, canvasRepository, testDispatcher) { _, _, _ -> hangingProvider }

            // Start sync in a separate job
            val syncJob =
                launch(testDispatcher) {
                    syncManager.syncProject(testProjectId)
                }

            // Unconfined dispatcher executes until first suspension (delay in provider),
            // so progress should be updated immediately.
            assertTrue("Sync should be reported in global progress", SyncManager.globalSyncProgress.value.containsKey(testProjectId))

            // Trigger cancellation
            SyncManager.cancelAllSyncs()

            // Wait for job to complete (cancel)
            syncJob.join()

            assertTrue("Job should be cancelled", syncJob.isCancelled)

            val interrupted = SyncManager.getInterruptedProjects()
            assertTrue("Project should be marked as interrupted", interrupted.contains(testProjectId))

            // Verify global progress is cleared
            assertTrue("Global progress should be empty", SyncManager.globalSyncProgress.value.isEmpty())
        }
}
