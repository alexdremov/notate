package com.alexdremov.notate.model

import android.graphics.Path
import android.graphics.RectF
import com.alexdremov.notate.util.StrokeGeometry
import com.onyx.android.sdk.data.note.TouchPoint
import kotlin.jvm.Transient

data class Stroke(
    val path: Path,
    val points: List<TouchPoint>, // Store raw points for pressure
    val color: Int,
    val width: Float,
    val style: StrokeType,
    override val bounds: RectF,
    val strokeOrder: Long = 0,
    override val zIndex: Float = 0f,
) : CanvasItem {
    override val order: Long get() = strokeOrder

    override fun distanceToPoint(
        x: Float,
        y: Float,
    ): Float {
        val centerDist = StrokeGeometry.distPointToStroke(x, y, this)
        // Subtract radius to get distance to the visual edge
        // If centerDist < width/2, result is negative (inside), which is valid for "0 distance" checks
        return (centerDist - (width / 2f)).coerceAtLeast(0f)
    }

    /**
     * Generic cache for renderer-specific data (e.g., Fountain Pen Path, Charcoal Vertices).
     * Renderers should check if this is null or of their expected type before using.
     * Not serialized.
     */
    @Transient
    var renderCache: RenderCache? = null

    /**
     * Explicitly releases native resources (Path).
     * Call this when the Stroke is no longer needed (e.g. evicted from cache).
     */
    fun recycle() {
        path.reset()
        renderCache = null
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Stroke

        // Identity check based on unique ID for persisted items
        if (strokeOrder != 0L && strokeOrder == other.strokeOrder) return true

        // If orders differ (and one is not 0), they are different
        if (strokeOrder != other.strokeOrder) return false

        // Value equality for transient items (Order == 0)
        // Explicitly IGNORING Path and RenderCache as they are transient/reconstructed
        if (color != other.color) return false
        if (width != other.width) return false
        if (style != other.style) return false
        if (bounds != other.bounds) return false
        if (zIndex != other.zIndex) return false
        if (points != other.points) return false

        return true
    }

    override fun hashCode(): Int {
        if (strokeOrder != 0L) return strokeOrder.hashCode()
        var result = color
        result = 31 * result + width.hashCode()
        result = 31 * result + style.hashCode()
        result = 31 * result + bounds.hashCode()
        result = 31 * result + zIndex.hashCode()
        result = 31 * result + points.hashCode()
        return result
    }
}
