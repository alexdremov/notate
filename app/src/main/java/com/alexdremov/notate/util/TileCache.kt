package com.alexdremov.notate.util

import android.graphics.Bitmap
import android.util.LruCache
import com.alexdremov.notate.config.CanvasConfig
import java.util.Collections
import kotlin.math.min

/**
 * Handles low-level Bitmap caching and pooling for TileManager.
 * Adheres to SRP by isolating memory management.
 * Thread-Safe: All public operations are synchronized on this instance.
 */
class TileCache(
    private val tileSize: Int = CanvasConfig.TILE_SIZE,
) {
    // Unique key for tiles
    data class TileKey(
        val col: Int,
        val row: Int,
        val level: Int,
    )

    /**
     * Wrapper for cached bitmaps that includes the render version.
     */
    data class CachedTile(
        val bitmap: Bitmap,
        val version: Int,
    )

    // Bitmap Pool to reduce GC churn.
    // Lock-free: entryRemoved() runs while the LruCache holds its internal lock,
    // so a monitor-based pool there would nest the LruCache lock with a second
    // monitor on every eviction. A concurrent queue keeps that path allocation-
    // and contention-free.
    private val bitmapPool = java.util.concurrent.ConcurrentLinkedQueue<Bitmap>()
    private val MAX_POOL_SIZE = 32 // Cap pool to prevent OOM

    // Bytes per tile (512*512*4 for ARGB_8888)
    private val tileByteCount = tileSize * tileSize * CanvasConfig.TILE_BYTES_PER_PIXEL

    // Placeholder for failed tiles
    val errorBitmap: Bitmap =
        Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.MAGENTA)
        }

    // Main LRU Cache - now stores CachedTile objects
    private val memoryCache: LruCache<TileKey, CachedTile>

    private val maxSafeSize: Int by lazy {
        (Runtime.getRuntime().maxMemory() * CanvasConfig.CACHE_MEMORY_PERCENT).toInt()
    }

    init {
        // Calculate initial cache size using config (e.g., 25% for starting buffer)
        val maxMemory = Runtime.getRuntime().maxMemory()
        val initialSize = (maxMemory * CanvasConfig.CACHE_MEMORY_PERCENT * 0.8).toInt() // Start with 80% of total budget

        Logger.i("TileCache", "Initializing with ${initialSize / (1024 * 1024)} MB")

        memoryCache =
            object : LruCache<TileKey, CachedTile>(initialSize) {
                override fun sizeOf(
                    key: TileKey,
                    value: CachedTile,
                ): Int = value.bitmap.byteCount

                override fun entryRemoved(
                    evicted: Boolean,
                    key: TileKey?,
                    oldValue: CachedTile?,
                    newValue: CachedTile?,
                ) {
                    val oldBitmap = oldValue?.bitmap
                    val newBitmap = newValue?.bitmap

                    // Pool the old bitmap ONLY if it was evicted (panning/scrolling).
                    // If evicted=false (replacement/refresh), the bitmap might still be in use
                    // by the UI thread (double-buffering), so we must NOT recycle it immediately.
                    // We let GC handle the replaced bitmap safely.
                    // NOTE: This method is called from within LruCache operations (put, get, remove, trimToSize).
                    // Since all those operations are triggered by methods synchronized on 'this' TileCache instance,
                    // we hold the lock and can safely access bitmapPool.
                    if (evicted && oldBitmap != null && oldBitmap != errorBitmap && oldBitmap != newBitmap && !oldBitmap.isRecycled) {
                        // size() is O(n) but n <= MAX_POOL_SIZE (tiny).
                        if (bitmapPool.size < MAX_POOL_SIZE) {
                            bitmapPool.offer(oldBitmap)
                        }
                    }
                }
            }
    }

    //
    // LOCKING NOTE
    // ------------
    // Android's LruCache synchronizes every public method internally, so the
    // outer @Synchronized wrappers that used to guard get/put/snapshot/... gave
    // TWO monitor acquisitions per operation and serialized the render thread
    // (~30-110 gets per frame) against all background workers on one coarse
    // lock. They are gone; per-method internal synchronization is sufficient.
    // The bitmap pool is its own concurrent structure (see above).

    fun get(key: TileKey): Bitmap? = memoryCache.get(key)?.bitmap

    fun getVersion(key: TileKey): Int = memoryCache.get(key)?.version ?: -1

    fun put(
        key: TileKey,
        bitmap: Bitmap,
        version: Int,
    ) {
        memoryCache.put(key, CachedTile(bitmap, version))
    }

    fun remove(key: TileKey) {
        memoryCache.remove(key)
    }

    fun clear() {
        memoryCache.evictAll()
        bitmapPool.clear()
    }

    fun obtainBitmap(): Bitmap {
        val pooled = bitmapPool.poll()
        val bitmap = pooled ?: Bitmap.createBitmap(tileSize, tileSize, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.TRANSPARENT)
        return bitmap
    }

    /**
     * Checks if we have enough budget to cache a new tile.
     * Can trigger a resize if needed.
     */
    fun checkBudgetAndResizeIfNeeded(generatingCount: Int) {
        val currentUsage = memoryCache.size()
        val anticipatedUsage = generatingCount * tileByteCount

        // If we are pressured, check if we can expand
        val targetSize = (currentUsage + anticipatedUsage * 1.5).toInt()

        if (targetSize > memoryCache.maxSize() && memoryCache.maxSize() < maxSafeSize) {
            val newSize = min(targetSize, maxSafeSize)
            if (newSize != memoryCache.maxSize()) {
                memoryCache.resize(newSize)
                Logger.i("TileCache", "Resized cache to ${newSize / (1024 * 1024)} MB")
            }
        }
    }

    fun isFull(
        generatingCount: Int,
        thresholdPercent: Double = 0.85,
    ): Boolean {
        val currentUsage = memoryCache.size()
        val anticipatedUsage = generatingCount * tileByteCount
        val threshold = (memoryCache.maxSize() * thresholdPercent).toInt()
        return (currentUsage + anticipatedUsage) > threshold
    }

    fun snapshot(): Map<TileKey, CachedTile> = memoryCache.snapshot()

    fun getStats(): Map<String, String> {
        val sizeMb = memoryCache.size() / (1024 * 1024)
        val maxMb = memoryCache.maxSize() / (1024 * 1024)
        val entries = memoryCache.snapshot().size
        val pool = bitmapPool.size
        val hits = memoryCache.hitCount()
        val misses = memoryCache.missCount()
        val total = hits + misses
        val hitRate = if (total > 0) (hits.toDouble() / total) * 100.0 else 0.0

        return mapOf(
            "Size (MB)" to "$sizeMb / $maxMb",
            "Entries" to "$entries",
            "Pool Size" to "$pool",
            "Hit Rate" to String.format("%.1f%%", hitRate),
        )
    }
}
