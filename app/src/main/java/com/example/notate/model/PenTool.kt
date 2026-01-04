package com.example.notate.model

import android.graphics.Color
import com.onyx.android.sdk.pen.TouchHelper
import kotlinx.serialization.Serializable

@Serializable
enum class ToolType {
    PEN,
    ERASER,
}

@Serializable
enum class EraserType {
    STROKE, // Erases entire stroke
    LASSO, // Erases strokes inside selection
    STANDARD, // Erases parts of strokes
}

@Serializable
data class PenTool(
    val id: String,
    val name: String,
    val type: ToolType,
    var color: Int = Color.BLACK,
    var width: Float = 3f,
    var strokeType: StrokeType = StrokeType.FOUNTAIN,
    var eraserType: EraserType = EraserType.STANDARD,
) {
    val displayColor: Int
        get() {
            val alpha = (Color.alpha(color) * strokeType.alphaMultiplier).toInt()
            return (color and 0x00FFFFFF) or (alpha shl 24)
        }

    companion object {
        fun defaultPens(): List<PenTool> =
            listOf(
                PenTool("pen_1", "Pen 1", ToolType.PEN, Color.BLACK, 7f, StrokeType.FOUNTAIN),
                PenTool("pen_2", "Pen 2", ToolType.PEN, Color.parseColor("#1A237E"), 7f, StrokeType.FOUNTAIN),
                PenTool("pen_3", "Pen 3", ToolType.PEN, Color.parseColor("#FFF9C4"), 60f, StrokeType.HIGHLIGHTER),
                PenTool("eraser_std", "Standard Eraser", ToolType.ERASER, Color.WHITE, 30f, StrokeType.PENCIL, EraserType.STANDARD),
            )
    }
}
