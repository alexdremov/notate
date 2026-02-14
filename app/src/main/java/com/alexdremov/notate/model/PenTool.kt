@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.alexdremov.notate.model

import android.graphics.Color
import com.onyx.android.sdk.pen.TouchHelper
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@Serializable
enum class ToolType {
    PEN,
    ERASER,
    SELECT,
    TEXT,
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
    @ProtoNumber(1)
    val id: String,
    @ProtoNumber(2)
    val name: String,
    @ProtoNumber(3)
    val type: ToolType,
    @ProtoNumber(4)
    var color: Int = Color.BLACK,
    @ProtoNumber(5)
    var width: Float = 3f, // Acts as FontSize for TEXT tool
    @ProtoNumber(6)
    var strokeType: StrokeType = StrokeType.FOUNTAIN,
    @ProtoNumber(7)
    var eraserType: EraserType = EraserType.STANDARD,
    @ProtoNumber(8)
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
                PenTool("pen_0", "Ballpoint", ToolType.PEN, Color.BLACK, 10f, StrokeType.BALLPOINT),
                PenTool("pen_1", "Pen 1", ToolType.PEN, Color.BLACK, 10f, StrokeType.FINELINER),
                PenTool("pen_2", "Pen 2", ToolType.PEN, Color.parseColor("#1A237E"), 10f, StrokeType.FOUNTAIN),
                PenTool("pen_3", "Pen 3", ToolType.PEN, Color.parseColor("#fff9c47c"), 60f, StrokeType.HIGHLIGHTER),
                PenTool("eraser_std", "Standard Eraser", ToolType.ERASER, Color.WHITE, 30f, StrokeType.FINELINER, EraserType.STANDARD),
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
                PenTool("text_tool", "Text", ToolType.TEXT, Color.BLACK, 40f, StrokeType.FINELINER),
            )
    }
}
