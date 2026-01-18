package com.alexdremov.notate.util

import android.graphics.Bitmap
import android.util.LruCache
import com.alexdremov.notate.config.CanvasConfig
import java.util.Collections
import kotlin.math.min

/**
 * Handles low-level Bitmap caching and pooling for TileManager.
 * Adheres to SRP by isolating memory management.
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

    // Bitmap Pool to reduce GC churn
    private val bitmapPool = Collections.synchronizedList(ArrayList<Bitmap>())
    private val MAX_POOL_SIZE = 128 // Cap pool to prevent OOM

    // Bytes per tile (512*512*4 for ARGB_8888)
    private val tileByteCount = tileSize * tileSize * CanvasConfig.TILE_BYTES_PER_PIXEL

    // Placeholder for failed tilesd
    val errorBitmap: Bitmap =
        Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.MAGENTA)
        }

    // Main LRU Cache
    private val memoryCache: LruCache<TileKey, Bitmap>

    private val maxSafeSize: Int by lazy {
        (Runtime.getRuntime().maxMemory() * CanvasConfig.CACHE_MEMORY_PERCENT).toInt()
    }

    init {
        // Calculate initial cache size using config (e.g., 25% for starting buffer)
        val maxMemory = Runtime.getRuntime().maxMemory()
        val initialSize = (maxMemory * CanvasConfig.CACHE_MEMORY_PERCENT * 0.3).toInt() // Start with 30% of total budget

        Logger.i("TileCache", "Initializing with ${initialSize / (1024 * 1024)} MB")

        memoryCache =
            object : LruCache<TileKey, Bitmap>(initialSize) {
                override fun sizeOf(
                    key: TileKey,
                    value: Bitmap,
                ): Int = value.byteCount

                override fun entryRemoved(
                    evicted: Boolean,
                    key: TileKey?,
                    oldValue: Bitmap?,
                    newValue: Bitmap?,
                ) {
                    if (evicted && oldValue != null && oldValue != errorBitmap && !oldValue.isRecycled) {
                        synchronized(bitmapPool) {
                            if (bitmapPool.size < MAX_POOL_SIZE) {
                                bitmapPool.add(oldValue)
                            }
                            // If pool is full, we simply drop the reference and let GC collect it.
                        }
                    }
                }
            }
    }

    fun get(key: TileKey): Bitmap? = memoryCache.get(key)

    fun put(
        key: TileKey,
        bitmap: Bitmap,
    ) {
        memoryCache.put(key, bitmap)
    }

    fun remove(key: TileKey) {
        memoryCache.remove(key)
    }

    fun clear() {
        memoryCache.evictAll()
        synchronized(bitmapPool) {
            bitmapPool.clear()
        }
    }

    fun obtainBitmap(): Bitmap {
        var bitmap: Bitmap? = null
        synchronized(bitmapPool) {
            if (bitmapPool.isNotEmpty()) {
                bitmap = bitmapPool.removeAt(bitmapPool.size - 1)
            }
        }

        if (bitmap == null) {
            bitmap = Bitmap.createBitmap(tileSize, tileSize, Bitmap.Config.ARGB_8888)
        }

        bitmap!!.eraseColor(android.graphics.Color.TRANSPARENT)
        return bitmap!!
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

    fun snapshot(): Map<TileKey, Bitmap> = memoryCache.snapshot()
}
