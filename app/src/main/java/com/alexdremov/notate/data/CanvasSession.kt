package com.alexdremov.notate.data

import com.alexdremov.notate.data.io.FileLockManager
import com.alexdremov.notate.data.region.RegionManager
import com.alexdremov.notate.util.Logger
import kotlinx.coroutines.sync.Mutex
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Represents an active editing session for a canvas.
 * Thread-safe lifecycle management.
 * Uses reference counting to share session across recreations.
 *
 * NOTE: This is NOT a data class because we need mutable state with proper
 * synchronization. The atomics must be shared across all references to the
 * same session, not copied.
 */
class CanvasSession(
    val sessionDir: File,
    val regionManager: RegionManager,
    originLastModified: Long = 0L,
    originSize: Long = 0L,
    metadata: CanvasData,
    private val lockHandle: FileLockManager.LockedFileHandle? = null,
) {
    // Mutable state - updated in place, not copied
    @Volatile
    var metadata: CanvasData = metadata
        private set

    @Volatile
    var originLastModified: Long = originLastModified
        private set

    @Volatile
    var originSize: Long = originSize
        private set

    // Track active operations (saves) to prevent premature cleanup
    private val activeOperations = AtomicInteger(0)
    private val closed = AtomicBoolean(false)

    // Reference Counting for Active Clients (Activities)
    private val refCount = AtomicInteger(1)

    // Mutex to synchronize save operations across shared sessions
    val saveMutex = Mutex()

    // Background Initialization Job (e.g. JIT Unzip remainder)
    var initializationJob: kotlinx.coroutines.Job? = null

    /**
     * Updates the metadata for this session.
     * Thread-safe.
     */
    fun updateMetadata(newMetadata: CanvasData) {
        metadata = newMetadata
    }

    /**
     * Waits for any pending initialization (like background unzip) to complete.
     */
    suspend fun waitForInitialization() {
        initializationJob?.join()
    }

    /**
     * Updates the origin file information for conflict detection.
     * Thread-safe.
     */
    fun updateOrigin(
        lastModified: Long,
        size: Long,
    ) {
        originLastModified = lastModified
        originSize = size
    }

    /**
     * Increment reference count.
     * Returns the new reference count.
     */
    fun retain(): Int {
        if (closed.get()) {
            Logger.w("CanvasSession", "Retaining closed session! Race condition likely.")
        }
        val count = refCount.incrementAndGet()
        Logger.d("CanvasSession", "Session retained. RefCount: $count")
        return count
    }

    /**
     * Decrement reference count.
     * Returns true if this was the last client (count reached 0).
     */
    fun release(): Boolean {
        val count = refCount.decrementAndGet()
        Logger.d("CanvasSession", "Session released. RefCount: $count")
        return count <= 0
    }

    /**
     * Returns the current client reference count.
     */
    fun getRefCount(): Int = refCount.get()

    /**
     * Marks the start of an operation (e.g., save) that requires the session directory.
     * Returns false if the session is already closed.
     */
    fun acquireForOperation(): Boolean {
        // First check without modifying
        if (closed.get()) return false

        // Increment operation count
        activeOperations.incrementAndGet()

        // Double-check after increment (handles race with close())
        if (closed.get()) {
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
     * Closes the session resources.
     * Waits for active operations to complete (with timeout).
     * DOES NOT DELETE DIRECTORY (it persists as cache).
     *
     * This method is idempotent - calling it multiple times is safe.
     */
    fun close() {
        if (!closed.compareAndSet(false, true)) {
            // Already closed by another thread
            return
        }

        Logger.i("CanvasSession", "Closing session (Memory cleanup). Directory persists: ${sessionDir.name}")

        // Wait for active operations to complete (max 10 seconds)
        val startTime = System.currentTimeMillis()
        val timeoutMs = 10_000L

        while (activeOperations.get() > 0) {
            if (System.currentTimeMillis() - startTime > timeoutMs) {
                Logger.e(
                    "CanvasSession",
                    "Timeout waiting for ${activeOperations.get()} operations to complete.  Force closing.",
                )
                break
            }
            try {
                Thread.sleep(100)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }

        // Clear memory caches
        regionManager.clear()

        // Release File Lock
        try {
            lockHandle?.close()
        } catch (e: Exception) {
            Logger.e("CanvasSession", "Failed to release file lock", e)
        }
    }

    /**
     * Returns true if this session has been closed.
     */
    fun isClosed(): Boolean = closed.get()

    /**
     * Returns the number of active operations.
     * Useful for debugging.
     */
    fun getActiveOperationCount(): Int = activeOperations.get()
}
