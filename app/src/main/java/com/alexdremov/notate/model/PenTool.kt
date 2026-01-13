package com.alexdremov.notate.model

import android.graphics.Color
import com.onyx.android.sdk.pen.TouchHelper
import kotlinx.serialization.Serializable

@Serializable
enum class ToolType {
    PEN,
    ERASER,
    SELECT,
}

@Serializable
enum class EraserType {
    STROKE, // Erases entire stroke
    LASSO, // Erases strokes inside selection
    STANDARD, // Erases parts of strokes
}

@Serializable
enum class SelectionType {
    RECTANGLE,
    LASSO,
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
    var selectionType: SelectionType = SelectionType.RECTANGLE,
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
                PenTool("pen_3", "Pen 3", ToolType.PEN, Color.parseColor("#fff9c47c"), 60f, StrokeType.HIGHLIGHTER),
                PenTool("eraser_std", "Standard Eraser", ToolType.ERASER, Color.WHITE, 30f, StrokeType.PENCIL, EraserType.STANDARD),
                PenTool(
                    "select_tool",
                    "Select",
                    ToolType.SELECT,
                    Color.BLACK,
                    2f,
                    StrokeType.DASH,
                    EraserType.STANDARD,
                    SelectionType.RECTANGLE,
                ),
            )
    }
}
