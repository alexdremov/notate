package com.alexdremov.notate.model

import android.graphics.Path
import android.graphics.RectF
import com.onyx.android.sdk.data.note.TouchPoint
import kotlin.jvm.Transient

data class Stroke(
    val path: Path,
    val points: List<TouchPoint>, // Store raw points for pressure
    val color: Int,
    val width: Float,
    val style: StrokeType,
    val bounds: RectF,
    val strokeOrder: Long = 0,
    val zIndex: Float = 0f,
) {
    /**
     * Generic cache for renderer-specific data (e.g., Fountain Pen Path, Charcoal Vertices).
     * Renderers should check if this is null or of their expected type before using.
     * Not serialized.
     */
    @Transient
    var renderCache: Any? = null
}
