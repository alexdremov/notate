package com.alexdremov.notate.ui.input

import android.graphics.Color
import com.alexdremov.notate.model.EraserType
import com.alexdremov.notate.model.Stroke
import com.alexdremov.notate.model.StrokeType
import com.alexdremov.notate.ui.controller.CanvasController
import com.alexdremov.notate.util.StrokeGeometry
import com.onyx.android.sdk.data.note.TouchPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.hypot

/**
 * Handles real-time erasing logic (throttling, segmentation) AND scribble-to-erase detection.
 */
class EraserGestureHandler(
    private val controller: CanvasController,
    private val strokeBuilder: StrokeBuilder,
    private val scope: CoroutineScope? = null,
) {
    // --- Real-time Eraser State ---
    private var lastErasedPoint: TouchPoint? = null
    private val MIN_ERASE_DISTANCE = 5f

    // --- Scribble Detection Constants ---
    private val MIN_REVERSALS = 3
    private val MIN_SPEED = 0.3f // px/ms
    private val REVERSAL_THRESHOLD = 130.0 // Degrees

    // --- Real-time Eraser Methods ---

    fun start(point: TouchPoint) {
        lastErasedPoint = point
    }

    suspend fun processMove(
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
                    Color.BLACK,
                    StrokeType.FINELINER,
                )

            controller.previewEraser(segmentStroke, eraserType)

            lastErasedPoint = currentPoint
        }
    }

    fun reset() {
        lastErasedPoint = null
    }

    // --- Scribble Detection Methods ---

    private fun isScribble(stroke: Stroke): Boolean {
        if (stroke.points.size < 10) return false

        // 1. Check Speed
        val duration = stroke.points.last().timestamp - stroke.points.first().timestamp
        if (duration <= 0) return false
        val length = StrokeGeometry.calculateLength(stroke.points)
        val speed = length / duration
        if (speed < MIN_SPEED) return false

        // 2. Count Directional Reversals
        var reversals = 0
        for (i in 2 until stroke.points.size) {
            val angle = StrokeGeometry.calculateAngle(
                stroke.points[i - 2],
                stroke.points[i - 1],
                stroke.points[i],
            )
            if (angle > REVERSAL_THRESHOLD) {
                reversals++
            }
        }

        // 3. Density Check
        val bounds = stroke.bounds
        val area = bounds.width() * bounds.height()
        if (area < 100) return false // Too small to be a meaningful scribble

        return reversals >= MIN_REVERSALS
    }
}
