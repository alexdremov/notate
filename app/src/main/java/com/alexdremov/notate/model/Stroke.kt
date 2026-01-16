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
    var renderCache: Any? = null
}
