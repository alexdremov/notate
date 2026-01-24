package com.alexdremov.notate.data.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.alexdremov.notate.data.SaveStatusManager
import com.alexdremov.notate.data.io.AtomicContainerStorage
import com.alexdremov.notate.data.io.FileLockManager
import com.alexdremov.notate.util.Logger
import java.io.File

class SaveWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val sessionPath = inputData.getString(KEY_SESSION_PATH)
        val targetPath = inputData.getString(KEY_TARGET_PATH)

        if (sessionPath == null || targetPath == null) {
            Logger.e("SaveWorker", "Missing input parameters")
            return Result.failure()
        }

        val sessionDir = File(sessionPath)
        if (!sessionDir.exists() || !sessionDir.isDirectory) {
            Logger.e("SaveWorker", "Session directory does not exist: $sessionPath")
            return Result.failure()
        }

        Logger.i("SaveWorker", "Starting background save for: $targetPath")
        SaveStatusManager.startSaving(targetPath)

        val atomicStorage = AtomicContainerStorage(applicationContext)
        var lockHandle: FileLockManager.LockedFileHandle? = null

        return try {
            // Attempt to acquire lock on target file to prevent concurrent modifications
            // Since we are writing to it, we want exclusive access.
            // Note: If the file is on SAF, locking might not work effectively or might not be supported by FileLockManager
            // but we try anyway if it's a local path logic.
            if (!targetPath.startsWith("content://")) {
                try {
                    lockHandle = FileLockManager.acquire(targetPath)
                } catch (e: Exception) {
                    Logger.w("SaveWorker", "Could not acquire lock for $targetPath, proceeding anyway if possible: ${e.message}")
                }
            }

            atomicStorage.pack(sessionDir, targetPath)
            Logger.i("SaveWorker", "Background save completed successfully")
            Result.success()
        } catch (e: Exception) {
            Logger.e("SaveWorker", "Background save failed", e)
            Result.retry()
        } finally {
            lockHandle?.close()
            SaveStatusManager.finishSaving(targetPath)
        }
    }

    companion object {
        const val KEY_SESSION_PATH = "session_path"
        const val KEY_TARGET_PATH = "target_path"
        const val TAG = "SaveWorker"
    }
}
