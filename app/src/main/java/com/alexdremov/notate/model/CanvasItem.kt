package com.alexdremov.notate.model

import android.graphics.RectF

/**
 * Common interface for all entities renderable on the Infinite Canvas.
 * Adheres to SOLID principles by allowing polymorphic handling of Strokes, Images, etc.
 */
sealed interface CanvasItem {
    val bounds: RectF
    val zIndex: Float
    val order: Long

    /**
     * Returns the distance from the point (x, y) to the visual content of the item.
     * @return 0 if the point is inside the item. Positive value indicates distance to the nearest edge.
     * Negative values can be used to indicate depth inside (optional, treated as 0 for hit testing).
     */
    fun distanceToPoint(
        x: Float,
        y: Float,
    ): Float
}
