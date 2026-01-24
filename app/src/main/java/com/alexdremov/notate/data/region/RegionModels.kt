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

    /**
     * Releases resources held by this region's items.
     */
    fun recycle() {
        items.forEach { item ->
            if (item is Stroke) {
                item.recycle()
            }
        }
        items.clear()
        quadtree?.clear()
        quadtree = null
    }

    fun sizeBytes(): Long {
        // More precise size calculation for LRU
        // Object Headers (~16 bytes) + References (4-8 bytes) are accounted for.
        var size = 0L
        for (item in items) {
            size +=
                when (item) {
                    is Stroke -> {
                        // Base Stroke object (~64) + RectF (~32) + references
                        var itemSize = 128L

                        // Native Path Overhead Estimation:
                        // A simple line might be small, but a complex handwriting stroke has many verbs.
                        // We add a safety buffer of 1KB per stroke for Native Path backing.
                        itemSize += 1024L

                        // Points: List<TouchPoint>
                        // TouchPoint is an object.
                        // Per point: Object Header(16) + Fields(x,y,pressure,size,timestamp ~32) + Ref(4) = ~52 bytes
                        itemSize += item.points.size * 52L

                        // RenderCache - transient but occupies RAM
                        item.renderCache?.let { cache ->
                            itemSize +=
                                when (cache) {
                                    is com.alexdremov.notate.model.BallpointCache -> {
                                        // List overhead + per segment (Obj + Path wrapper)
                                        32L + cache.segments.size * (48L + 64L)
                                    }

                                    is com.alexdremov.notate.model.CharcoalCache -> {
                                        // Arrays (Header+Size) + Path wrapper
                                        64L + (cache.verts.size * 4L) + (cache.colors.size * 4L) + 64L
                                    }

                                    is com.alexdremov.notate.model.FountainCache -> {
                                        64L // Path wrapper approx
                                    }
                                }
                        }
                        itemSize
                    }

                    is com.alexdremov.notate.model.CanvasImage -> {
                        // Object overhead (~64) + RectF (~32) + URI String (Header+Ref ~48) + char array
                        144L + (item.uri.length * 2L)
                    }

                    else -> {
                        128L
                    }
                }
        }

        size += 128L + 128L // Base RegionData overhead + ArrayList overhead
        size += quadtree?.sizeBytes() ?: 0L
        return size
    }
}
