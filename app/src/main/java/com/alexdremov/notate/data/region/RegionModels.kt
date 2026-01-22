package com.alexdremov.notate.data.region

import android.graphics.RectF
import com.alexdremov.notate.model.CanvasItem
import com.alexdremov.notate.model.Stroke
import com.alexdremov.notate.util.Quadtree
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class RegionId(
    val x: Int,
    val y: Int,
) {
    override fun toString(): String = "${x}_$y"

    fun getBounds(regionSize: Float): RectF = RectF(x * regionSize, y * regionSize, (x + 1) * regionSize, (y + 1) * regionSize)

    companion object {
        fun fromString(s: String): RegionId? {
            val parts = s.split("_")
            if (parts.size != 2) return null
            return try {
                RegionId(parts[0].toInt(), parts[1].toInt())
            } catch (e: NumberFormatException) {
                null
            }
        }
    }
}

@Serializable
data class RegionData(
    val id: RegionId,
    val items: MutableList<CanvasItem> = ArrayList(),
    @Volatile var isDirty: Boolean = false,
) {
    @Transient
    var quadtree: Quadtree? = null

    @Transient
    val contentBounds = RectF()

    @Transient
    var cachedThumbnail: android.graphics.Bitmap? = null

    @Transient
    private var lastCalculatedSize: Long = -1L

    /**
     * Returns the size in bytes, using a cached value if available.
     * This is critical for LruCache consistency.
     */
    fun getSizeCached(): Long {
        if (lastCalculatedSize == -1L) {
            lastCalculatedSize = sizeBytes()
        }
        return lastCalculatedSize
    }

    /**
     * Invalidates the cached size. Call this before putting the region back into LruCache
     * after modification.
     */
    fun invalidateSize() {
        lastCalculatedSize = -1L
    }

    fun rebuildQuadtree(regionSize: Float) {
        val rBounds = id.getBounds(regionSize)

        var qt = Quadtree(0, rBounds)
        contentBounds.setEmpty()

        for (item in items) {
            qt = qt.insert(item)
            if (contentBounds.isEmpty) {
                contentBounds.set(item.bounds)
            } else {
                contentBounds.union(item.bounds)
            }
        }
        quadtree = qt
    }

    fun sizeBytes(): Long {
        // Approximate size calculation for LRU
        var size = 0L
        for (item in items) {
            size +=
                when (item) {
                    is Stroke -> {
                        item.points.size * 28L
                    }

                    // 7 values (x,y,p,s,tx,ty,ts) * 4 bytes
                    is com.alexdremov.notate.model.CanvasImage -> {
                        // Object overhead (~64) + RectF (~32) + URI String (2 bytes/char + header)
                        256L + (item.uri.length * 2L)
                    }

                    else -> {
                        100L
                    }
                }
        }

        val thumbSize = cachedThumbnail?.allocationByteCount?.toLong() ?: 0L
        return size + 1024 + thumbSize // Base overhead + thumbnail
    }
}
