package com.alexdremov.notate.ui.input

import com.alexdremov.notate.controller.CanvasController
import com.alexdremov.notate.model.EraserType
import com.alexdremov.notate.model.StrokeType
import com.onyx.android.sdk.data.note.TouchPoint
import kotlin.math.hypot

/**
 * Handles real-time erasing logic (throttling, segmentation).
 */
class EraserGestureHandler(
    private val controller: CanvasController,
    private val strokeBuilder: StrokeBuilder,
) {
    private var lastErasedPoint: TouchPoint? = null
    private val MIN_ERASE_DISTANCE = 5f

    fun start(point: TouchPoint) {
        lastErasedPoint = point
    }

    fun processMove(
        currentPoint: TouchPoint,
        width: Float,
        eraserType: EraserType,
    ) {
        if (eraserType == EraserType.LASSO) return // Lasso handled separately

        val lastPoint = strokeBuilder.getLastPoint() ?: return

        val dist =
            hypot(
                currentPoint.x - (lastErasedPoint?.x ?: 0f),
                currentPoint.y - (lastErasedPoint?.y ?: 0f),
            )

        if (lastErasedPoint == null || dist > MIN_ERASE_DISTANCE) {
            val startP = lastErasedPoint ?: lastPoint

            val segmentStroke =
                strokeBuilder.buildSegment(
                    startP,
                    currentPoint,
                    width,
                    android.graphics.Color.BLACK,
                    StrokeType.FINELINER,
                )

            controller.previewEraser(segmentStroke, eraserType)

            lastErasedPoint = currentPoint
        }
    }

    fun reset() {
        lastErasedPoint = null
    }
}
