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

    // Bitmap Pool to reduce GC churn
    // Guarded by this TileCache instance lock
    private val bitmapPool = ArrayList<Bitmap>()
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
                        if (bitmapPool.size < MAX_POOL_SIZE) {
                            bitmapPool.add(oldBitmap)
                        }
                    }
                }
            }
    }

    @Synchronized
    fun get(key: TileKey): Bitmap? = memoryCache.get(key)?.bitmap

    @Synchronized
    fun getVersion(key: TileKey): Int = memoryCache.get(key)?.version ?: -1

    @Synchronized
    fun put(
        key: TileKey,
        bitmap: Bitmap,
        version: Int,
    ) {
        memoryCache.put(key, CachedTile(bitmap, version))
    }

    @Synchronized
    fun remove(key: TileKey) {
        memoryCache.remove(key)
    }

    @Synchronized
    fun clear() {
        memoryCache.evictAll()
        bitmapPool.clear()
    }

    @Synchronized
    fun obtainBitmap(): Bitmap {
        var bitmap: Bitmap? = null
        if (bitmapPool.isNotEmpty()) {
            bitmap = bitmapPool.removeAt(bitmapPool.size - 1)
        }

        if (bitmap == null) {
            bitmap = Bitmap.createBitmap(tileSize, tileSize, Bitmap.Config.ARGB_8888)
        }

        bitmap = bitmap ?: throw OutOfMemoryError("Failed to allocate tile bitmap")
        bitmap.eraseColor(android.graphics.Color.TRANSPARENT)
        return bitmap
    }

    /**
     * Checks if we have enough budget to cache a new tile.
     * Can trigger a resize if needed.
     */
    @Synchronized
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

    @Synchronized
    fun isFull(
        generatingCount: Int,
        thresholdPercent: Double = 0.85,
    ): Boolean {
        val currentUsage = memoryCache.size()
        val anticipatedUsage = generatingCount * tileByteCount
        val threshold = (memoryCache.maxSize() * thresholdPercent).toInt()
        return (currentUsage + anticipatedUsage) > threshold
    }

    @Synchronized
    fun snapshot(): Map<TileKey, CachedTile> = memoryCache.snapshot()

    @Synchronized
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
