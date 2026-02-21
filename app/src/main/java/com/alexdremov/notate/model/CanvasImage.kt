package com.alexdremov.notate.model

import android.graphics.RectF
import com.alexdremov.notate.util.StrokeGeometry
import kotlin.jvm.Transient

/**
 * Represents an image on the canvas.
 * Implements [CanvasItem] with correct AABB calculation for rotated images.
 */
data class CanvasImage(
    val uri: String,
    /**
     * The unrotated, axis-aligned bounds of the image in its local coordinate system.
     * Used for rendering.
     */
    val logicalBounds: RectF,
    /**
     * The actual Axis-aligned bounding box (AABB) in World Coordinates.
     * Calculated from [logicalBounds] and [rotation].
     */
    override val bounds: RectF,
    override val zIndex: Float = 0f,
    override val order: Long = 0,
    val rotation: Float = 0f,
    val opacity: Float = 1.0f,
) : CanvasItem {
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

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as CanvasImage

        if (order != 0L && order == other.order) return true
        if (order != other.order) return false
        if (uri != other.uri) return false
        if (logicalBounds != other.logicalBounds) return false
        if (bounds != other.bounds) return false
        if (zIndex != other.zIndex) return false
        if (rotation != other.rotation) return false
        if (opacity != other.opacity) return false

        return true
    }

    override fun hashCode(): Int {
        if (order != 0L) return order.hashCode()
        var result = uri.hashCode()
        result = 31 * result + logicalBounds.hashCode()
        result = 31 * result + bounds.hashCode()
        result = 31 * result + zIndex.hashCode()
        result = 31 * result + rotation.hashCode()
        result = 31 * result + opacity.hashCode()
        return result
    }
}
