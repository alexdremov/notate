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
        if (bounds.contains(x, y)) return 0f

        // Calculate distance to the rectangle
        val dx = kotlin.math.max(bounds.left - x, x - bounds.right)
        val dy = kotlin.math.max(bounds.top - y, y - bounds.bottom)
        return kotlin.math.max(dx, dy).coerceAtLeast(0f)
    }
}
