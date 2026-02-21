package com.alexdremov.notate.model

import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import kotlin.jvm.Transient

/**
 * Represents a text item on the canvas.
 * Implements [CanvasItem] with correct AABB calculation for rotated text.
 */
data class TextItem(
    val text: String, // Markdown content
    val fontSize: Float,
    val color: Int,
    /**
     * The unrotated, axis-aligned bounds of the text in world-space coordinates.
     * Used for layout, hit-testing, and as the pivot origin for [rotation].
     */
    val logicalBounds: RectF,
    /**
     * The actual Axis-aligned bounding box (AABB) in World Coordinates.
     * Calculated from [logicalBounds] and [rotation].
     */
    override val bounds: RectF,
    val alignment: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL,
    val backgroundColor: Int = android.graphics.Color.TRANSPARENT,
    override val zIndex: Float = 0f,
    override val order: Long = 0,
    val rotation: Float = 0f,
    val opacity: Float = 1.0f,
) : CanvasItem {
    @Transient
    var renderCache: StaticLayout? = null

    override fun distanceToPoint(
        x: Float,
        y: Float,
    ): Float {
        if (rotation % 360f == 0f) {
            if (logicalBounds.contains(x, y)) return 0f
            val dx = kotlin.math.max(logicalBounds.left - x, x - logicalBounds.right)
            val dy = kotlin.math.max(logicalBounds.top - y, y - logicalBounds.bottom)
            return kotlin.math.max(dx, dy).coerceAtLeast(0f)
        }

        // Rotate point (x,y) by -rotation around center to map it into the unrotated local space
        val cx = logicalBounds.centerX()
        val cy = logicalBounds.centerY()
        val rad = Math.toRadians(-rotation.toDouble())
        val cos = kotlin.math.cos(rad)
        val sin = kotlin.math.sin(rad)

        val dx = x - cx
        val dy = y - cy

        val localX = (cx + dx * cos - dy * sin).toFloat()
        val localY = (cy + dx * sin + dy * cos).toFloat()

        if (logicalBounds.contains(localX, localY)) return 0f
        val dLocalX = kotlin.math.max(logicalBounds.left - localX, localX - logicalBounds.right)
        val dLocalY = kotlin.math.max(logicalBounds.top - localY, localY - logicalBounds.bottom)
        return kotlin.math.max(dLocalX, dLocalY).coerceAtLeast(0f)
    }

    /**
     * Checks if two TextItems have the same visual layout properties.
     * Used for cache invalidation.
     */
    fun hasSameLayout(other: TextItem): Boolean =
        text == other.text &&
            fontSize == other.fontSize &&
            color == other.color &&
            logicalBounds.width() == other.logicalBounds.width() &&
            alignment == other.alignment &&
            backgroundColor == other.backgroundColor &&
            opacity == other.opacity

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as TextItem

        // Identity check based on unique ID
        if (order != 0L && order == other.order) return true
        if (order != other.order) return false

        // Structural fallback for new items
        return text == other.text &&
            fontSize == other.fontSize &&
            color == other.color &&
            logicalBounds == other.logicalBounds &&
            alignment == other.alignment &&
            backgroundColor == other.backgroundColor &&
            zIndex == other.zIndex &&
            rotation == other.rotation &&
            opacity == other.opacity
    }

    override fun hashCode(): Int {
        if (order != 0L) return order.hashCode()
        var result = text.hashCode()
        result = 31 * result + fontSize.hashCode()
        result = 31 * result + color
        result = 31 * result + logicalBounds.hashCode()
        result = 31 * result + alignment.hashCode()
        result = 31 * result + backgroundColor
        result = 31 * result + zIndex.hashCode()
        result = 31 * result + rotation.hashCode()
        result = 31 * result + opacity.hashCode()
        return result
    }
}
