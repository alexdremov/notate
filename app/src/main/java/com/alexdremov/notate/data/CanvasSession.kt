package com.alexdremov.notate.data

import com.alexdremov.notate.data.region.RegionManager
import com.alexdremov.notate.util.Logger
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Represents an active editing session for a canvas.
 * Thread-safe lifecycle management to prevent deletion during active saves.
 */
data class CanvasSession(
    val sessionDir: File,
    val metadata: CanvasData,
    val regionManager: RegionManager,
    val originLastModified: Long = 0L,
    val originSize: Long = 0L,
) {
    // Track active operations (saves) to prevent premature cleanup
    private val activeOperations = AtomicInteger(0)
    private val isClosed = AtomicBoolean(false)

    /**
     * Marks the start of an operation (e.g., save) that requires the session directory.
     * Returns false if the session is already closed.
     */
    fun acquireForOperation(): Boolean {
        if (isClosed.get()) return false
        activeOperations.incrementAndGet()
        // Double-check after increment
        if (isClosed.get()) {
            activeOperations.decrementAndGet()
            return false
        }
        return true
    }

    /**
     * Marks the end of an operation.
     */
    fun releaseOperation() {
        val remaining = activeOperations.decrementAndGet()
        if (remaining < 0) {
            Logger.e("CanvasSession", "Operation count went negative!  Resetting to 0.")
            activeOperations.set(0)
        }
    }

    /**
     * Closes the session and deletes the session directory.
     * Waits for active operations to complete (with timeout).
     */
    fun close() {
        if (!isClosed.compareAndSet(false, true)) {
            // Already closed
            return
        }

        // Wait for active operations to complete (max 10 seconds)
        val startTime = System.currentTimeMillis()
        val timeoutMs = 10_000L

        while (activeOperations.get() > 0) {
            if (System.currentTimeMillis() - startTime > timeoutMs) {
                Logger.e("CanvasSession", "Timeout waiting for ${activeOperations.get()} operations to complete.  Force closing.")
                break
            }
            try {
                Thread.sleep(100)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }

        // Now safe to delete
        try {
            if (sessionDir.exists()) {
                val deleted = sessionDir.deleteRecursively()
                if (deleted) {
                    Logger.d("CanvasSession", "Session directory deleted: ${sessionDir.name}")
                } else {
                    Logger.w("CanvasSession", "Failed to delete session directory: ${sessionDir.absolutePath}")
                }
            }
        } catch (e: Exception) {
            Logger.e("CanvasSession", "Error closing session", e)
        }
    }

    fun isClosed(): Boolean = isClosed.get()
}
