package com.alexdremov.notate.model

import android.graphics.RectF

/**
 * Common interface for all entities renderable on the Infinite Canvas.
 * Adheres to SOLID principles by allowing polymorphic handling of Strokes, Images, etc.
 */
interface CanvasItem {
    /**
     * The Axis-aligned bounding box (AABB) in World Coordinates.
     * This MUST encompass the entire visual representation of the item, including rotation.
     * This property is used for spatial indexing (Quadtree) and culling.
     */
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
