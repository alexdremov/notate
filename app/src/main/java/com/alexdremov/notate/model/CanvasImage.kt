package com.alexdremov.notate.model

import android.graphics.RectF
import kotlin.jvm.Transient

data class CanvasImage(
    val uri: String,
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
        if (rotation == 0f) {
            if (bounds.contains(x, y)) return 0f
            // Calculate distance to the rectangle
            val dx = kotlin.math.max(bounds.left - x, x - bounds.right)
            val dy = kotlin.math.max(bounds.top - y, y - bounds.bottom)
            return kotlin.math.max(dx, dy).coerceAtLeast(0f)
        }

        // Rotate point (x,y) by -rotation around center to map it into the unrotated local space
        val cx = bounds.centerX()
        val cy = bounds.centerY()
        val rad = Math.toRadians(-rotation.toDouble())
        val cos = kotlin.math.cos(rad)
        val sin = kotlin.math.sin(rad)

        val dx = x - cx
        val dy = y - cy

        val localX = (cx + dx * cos - dy * sin).toFloat()
        val localY = (cy + dx * sin + dy * cos).toFloat()

        if (bounds.contains(localX, localY)) return 0f

        val dLocalX = kotlin.math.max(bounds.left - localX, localX - bounds.right)
        val dLocalY = kotlin.math.max(bounds.top - localY, localY - bounds.bottom)
        return kotlin.math.max(dLocalX, dLocalY).coerceAtLeast(0f)
    }
}
