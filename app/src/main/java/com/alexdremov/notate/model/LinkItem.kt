package com.alexdremov.notate.model

import android.graphics.RectF
import com.alexdremov.notate.data.LinkType

/**
 * Represents a clickable link on the canvas.
 * Can be an internal link to another note (by UUID) or an external URL.
 */
data class LinkItem(
    val label: String,
    val target: String, // UUID for internal link, full URL for external
    val type: LinkType,
    val color: Int,
    val fontSize: Float,
    val logicalBounds: RectF, // Unrotated bounds (x, y, width, height)
    override val bounds: RectF, // Rotated AABB
    override val zIndex: Float = 0f,
    override val order: Long = 0,
    val rotation: Float = 0f,
) : CanvasItem {
    override fun distanceToPoint(
        x: Float,
        y: Float,
    ): Float {
        // For a link, we assume a hit if the point is within the (potentially rotated) bounds.
        // A more sophisticated implementation might check if the point is within the rendered text/icon.
        if (bounds.contains(x, y)) {
            return 0f // Inside the link item
        }
        // If not inside, return a large distance to indicate no hit.
        return Float.MAX_VALUE
    }

    // Standard equals and hashCode for data classes will work, but ensure order is considered for identity.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as LinkItem

        if (order != 0L && order == other.order) return true
        if (order != other.order) return false

        return label == other.label &&
            target == other.target &&
            type == other.type &&
            color == other.color &&
            fontSize == other.fontSize &&
            logicalBounds == other.logicalBounds &&
            bounds == other.bounds &&
            zIndex == other.zIndex &&
            rotation == other.rotation
    }

    override fun hashCode(): Int {
        if (order != 0L) return order.hashCode()
        var result = label.hashCode()
        result = 31 * result + target.hashCode()
        result = 31 * result + type.hashCode()
        result = 31 * result + color
        result = 31 * result + fontSize.hashCode()
        result = 31 * result + logicalBounds.hashCode()
        result = 31 * result + bounds.hashCode()
        result = 31 * result + zIndex.hashCode()
        result = 31 * result + rotation.hashCode()
        return result
    }
}
