package com.alexdremov.notate.data.io

import com.alexdremov.notate.util.Logger
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException

/**
 * Manages exclusive locks on files to prevent concurrent modification by multiple processes.
 *
 * Usage:
 * val lock = FileLockManager.acquire(file)
 * try {
 *     // Critical section
 * } finally {
 *     lock.release()
 * }
 */
object FileLockManager {
    class LockedFileHandle(
        private val channel: FileChannel,
        private val lock: FileLock,
        val file: File,
        private val raf: RandomAccessFile,
    ) : AutoCloseable {
        private var released = false

        @Synchronized
        override fun close() {
            if (released) return
            try {
                if (lock.isValid) lock.release()
            } catch (e: Exception) {
                Logger.e("FileLockManager", "Error releasing lock for ${file.name}", e)
            }
            try {
                channel.close()
            } catch (e: Exception) {
                Logger.e("FileLockManager", "Error closing channel for ${file.name}", e)
            }
            try {
                raf.close()
            } catch (e: Exception) {
                Logger.e("FileLockManager", "Error closing RandomAccessFile for ${file.name}", e)
            }
            released = true
            Logger.d("FileLockManager", "Released lock: ${file.absolutePath}")
        }

        fun isActive(): Boolean = !released && lock.isValid
    }

    /**
     * Attempts to acquire an exclusive lock on the given file.
     * Throws [IllegalStateException] if the lock cannot be acquired.
     */
    fun acquire(path: String): LockedFileHandle {
        val file = File(path)
        // Ensure file exists or create it (for new projects)
        if (!file.exists()) {
            file.parentFile?.mkdirs()
            file.createNewFile()
        }

        try {
            val raf = RandomAccessFile(file, "rw")
            val channel = raf.channel

            Logger.d("FileLockManager", "Attempting to lock: ${file.absolutePath}")
            // tryLock() is non-blocking. Returns null if overlapping lock in THIS JVM.
            // If locked by another Process, it might throw or return null depending on OS.
            // On Android/Linux, it usually works fine for process isolation.
            val lock =
                channel.tryLock()
                    ?: throw IllegalStateException("File is already locked by this process or another thread: $path")

            return LockedFileHandle(channel, lock, file, raf)
        } catch (e: OverlappingFileLockException) {
            throw IllegalStateException("File is currently locked by this JVM: $path")
        } catch (e: Exception) {
            throw IllegalStateException("Failed to acquire lock on $path. Is it open in another app?", e)
        }
    }
}
