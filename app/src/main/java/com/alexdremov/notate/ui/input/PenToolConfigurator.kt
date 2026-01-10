package com.alexdremov.notate.ui.input

import android.content.Context
import com.alexdremov.notate.config.CanvasConfig
import com.alexdremov.notate.model.EraserType
import com.alexdremov.notate.model.PenTool
import com.alexdremov.notate.model.SelectionType
import com.alexdremov.notate.model.ToolType
import com.alexdremov.notate.util.ColorUtils
import com.onyx.android.sdk.device.Device
import com.onyx.android.sdk.pen.TouchHelper

/**
 * Encapsulates the logic for configuring the Onyx TouchHelper
 * based on the current tool state.
 */
object PenToolConfigurator {
    
    fun configure(
        touchHelper: TouchHelper,
        currentTool: PenTool,
        currentScale: Float,
        context: Context
    ): Boolean {
        // Returns: isLargeStrokeMode
        
        val pixelWidth = currentTool.width * currentScale
        val dm = context.resources.displayMetrics
        val physicalMm = (pixelWidth * 25.4f) / dm.xdpi
        val isLargeStrokeMode = physicalMm > CanvasConfig.EINK_RENDER_THRESHOLD_MM

        touchHelper.apply {
            when (currentTool.type) {
                ToolType.ERASER -> {
                    // Lasso Eraser -> HW Dash
                    if (currentTool.eraserType == EraserType.LASSO) {
                        setRawDrawingRenderEnabled(true)
                        Device.currentDevice().setEraserRawDrawingEnabled(true)
                        setStrokeStyle(TouchHelper.STROKE_STYLE_DASH)
                        setStrokeColor(android.graphics.Color.BLACK)
                        setStrokeWidth(5.0f)
                        Device.currentDevice().setStrokeParameters(TouchHelper.STROKE_STYLE_DASH, floatArrayOf(5.0f))
                    } else {
                        // Stroke/Standard Eraser -> SW Cursor
                        setRawDrawingRenderEnabled(false)
                        Device.currentDevice().setEraserRawDrawingEnabled(false)
                    }
                }
                ToolType.SELECT -> {
                    if (currentTool.selectionType == SelectionType.LASSO) {
                        // Lasso Select -> HW Dash
                        setRawDrawingRenderEnabled(true)
                        Device.currentDevice().setEraserRawDrawingEnabled(true) 
                        setStrokeStyle(TouchHelper.STROKE_STYLE_DASH)
                        setStrokeColor(android.graphics.Color.BLACK)
                        setStrokeWidth(2.0f)
                        Device.currentDevice().setStrokeParameters(TouchHelper.STROKE_STYLE_DASH, floatArrayOf(5.0f, 5.0f))
                    } else {
                        // Rect Select -> SW Cursor
                        setRawDrawingRenderEnabled(false)
                        Device.currentDevice().setEraserRawDrawingEnabled(false)
                    }
                }
                ToolType.PEN -> {
                    setStrokeColor(ColorUtils.adjustColorForHardware(currentTool.displayColor))
                    setStrokeStyle(currentTool.strokeType.touchHelperStyle)
                    setStrokeWidth(pixelWidth)

                    // Disable hardware render if stroke is too large
                    val enableHardware = !isLargeStrokeMode
                    setRawDrawingRenderEnabled(enableHardware)
                    
                    // Ensure eraser channel is off for pen
                    Device.currentDevice().setEraserRawDrawingEnabled(false)
                }
            }
        }
        return isLargeStrokeMode
    }
}
