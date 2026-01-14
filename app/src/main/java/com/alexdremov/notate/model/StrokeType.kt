package com.alexdremov.notate.model

import com.alexdremov.notate.config.CanvasConfig
import com.onyx.android.sdk.pen.TouchHelper
import kotlinx.serialization.Serializable

@Serializable
enum class StrokeType(
    val touchHelperStyle: Int,
    val displayName: String,
    val maxWidthMm: Float = CanvasConfig.TOOLS_MAX_STROKE_MM,
    val alphaMultiplier: Float = 1.0f,
    val defaultZIndex: Float = 0.0f,
) {
    FOUNTAIN(TouchHelper.STROKE_STYLE_FOUNTAIN, "Fountain"),
    BALLPOINT(TouchHelper.STROKE_STYLE_PENCIL, "Ballpoint"),
    FINELINER(TouchHelper.STROKE_STYLE_PENCIL, "Fineliner"),
    HIGHLIGHTER(
        TouchHelper.STROKE_STYLE_MARKER,
        "Highlighter",
        maxWidthMm = 10.0f,
        alphaMultiplier = 0.5f,
        defaultZIndex = -1.0f,
    ),
    BRUSH(TouchHelper.STROKE_STYLE_NEO_BRUSH, "Brush"),
    CHARCOAL(TouchHelper.STROKE_STYLE_CHARCOAL, "Charcoal"),
    DASH(TouchHelper.STROKE_STYLE_DASH, "Dash"),
}
