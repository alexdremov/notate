package com.alexdremov.notate.model

import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import kotlin.jvm.Transient

data class TextItem(
    val text: String, // Markdown content
    val fontSize: Float,
    val color: Int,
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
        if (rotation == 0f) {
            if (bounds.contains(x, y)) return 0f
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

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as TextItem

        if (order != 0L && order == other.order) return true
        if (order != other.order) return false

        if (text != other.text) return false
        if (fontSize != other.fontSize) return false
        if (color != other.color) return false
        if (bounds != other.bounds) return false
        if (alignment != other.alignment) return false
        if (backgroundColor != other.backgroundColor) return false
        if (zIndex != other.zIndex) return false
        if (rotation != other.rotation) return false
        if (opacity != other.opacity) return false

        return true
    }

    override fun hashCode(): Int {
        if (order != 0L) return order.hashCode()
        var result = text.hashCode()
        result = 31 * result + fontSize.hashCode()
        result = 31 * result + color
        result = 31 * result + bounds.hashCode()
        result = 31 * result + alignment.hashCode()
        result = 31 * result + backgroundColor
        result = 31 * result + zIndex.hashCode()
        result = 31 * result + rotation.hashCode()
        result = 31 * result + opacity.hashCode()
        return result
    }
}
