package com.alexdremov.notate.ui.interaction

import android.content.Context
import android.graphics.Matrix
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import com.alexdremov.notate.config.CanvasConfig
import com.alexdremov.notate.util.EpdFastModeController
import kotlin.math.hypot

/**
 * Handles Panning and Zooming interactions for the Canvas.
 * Replaces the fragmented logic in OnyxCanvasView.
 */
class ViewportInteractor(
    context: Context,
    private val matrix: Matrix,
    private val invalidateCallback: () -> Unit,
    private val onScaleChanged: () -> Unit,
    private val onInteractionStart: () -> Unit,
    private val onInteractionEnd: () -> Unit,
) {
    // State
    private var currentScale = 1.0f
    private var isPanning = false
    private var isInteracting = false
    private var hasPerformedScale = false

    private var lastTouchX = 0f
    private var lastTouchY = 0f

    // Config
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    // Scale Detector
    private val scaleDetector =
        ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                    hasPerformedScale = true
                    return super.onScaleBegin(detector)
                }

                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    handleScale(detector)
                    return true
                }
            },
        )

    fun onTouchEvent(event: MotionEvent): Boolean {
        // Pass to ScaleDetector
        scaleDetector.onTouchEvent(event)

        // Handle Panning
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Determine focus point (if multi-touch, though ACTION_DOWN is usually single)
                lastTouchX = event.x
                lastTouchY = event.y
                isPanning = false
                isInteracting = true
                hasPerformedScale = false
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // Update focus point to avoid jump
                updateFocusPoint(event)
            }

            MotionEvent.ACTION_POINTER_UP -> {
                // Update focus point to avoid jump
                updateFocusPoint(event)
            }

            MotionEvent.ACTION_MOVE -> {
                val focusX = getFocusX(event)
                val focusY = getFocusY(event)

                if (!isInteracting) {
                    // Safety: if we missed DOWN (e.g. intercepted), re-init
                    lastTouchX = focusX
                    lastTouchY = focusY
                    isInteracting = true
                }

                val dx = focusX - lastTouchX
                val dy = focusY - lastTouchY

                if (!isPanning) {
                    if (hypot(dx, dy) > touchSlop) {
                        isPanning = true
                        startInteraction()
                    }
                }

                if (isPanning) {
                    matrix.postTranslate(dx, dy)
                    invalidateCallback()
                    lastTouchX = focusX
                    lastTouchY = focusY
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isPanning || isInteracting) {
                    endInteraction()
                }
                isPanning = false
                isInteracting = false
            }
        }

        return isPanning || isInteracting
    }

    private fun handleScale(detector: ScaleGestureDetector) {
        var scaleFactor = detector.scaleFactor
        val newScale = currentScale * scaleFactor

        // Clamp scale
        if (newScale < CanvasConfig.MIN_SCALE) {
            scaleFactor = CanvasConfig.MIN_SCALE / currentScale
            currentScale = CanvasConfig.MIN_SCALE
        } else if (newScale > CanvasConfig.MAX_SCALE) {
            scaleFactor = CanvasConfig.MAX_SCALE / currentScale
            currentScale = CanvasConfig.MAX_SCALE
        } else {
            currentScale = newScale
        }

        matrix.postScale(scaleFactor, scaleFactor, detector.focusX, detector.focusY)
        invalidateCallback()
        onScaleChanged()
    }

    private fun updateFocusPoint(event: MotionEvent) {
        lastTouchX = getFocusX(event)
        lastTouchY = getFocusY(event)
    }

    private fun getFocusX(event: MotionEvent): Float {
        var sum = 0f
        val count = event.pointerCount
        val skip = if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) event.actionIndex else -1
        var div = 0
        for (i in 0 until count) {
            if (i == skip) continue
            sum += event.getX(i)
            div++
        }
        return if (div > 0) sum / div else event.x
    }

    private fun getFocusY(event: MotionEvent): Float {
        var sum = 0f
        val count = event.pointerCount
        val skip = if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) event.actionIndex else -1
        var div = 0
        for (i in 0 until count) {
            if (i == skip) continue
            sum += event.getY(i)
            div++
        }
        return if (div > 0) sum / div else event.y
    }

    private fun startInteraction() {
        onInteractionStart()
        EpdFastModeController.enterFastMode()
    }

    private fun endInteraction() {
        onInteractionEnd()
        EpdFastModeController.exitFastMode()
    }

    fun getCurrentScale() = currentScale

    fun setScale(scale: Float) {
        currentScale = scale
    }

    fun isInteracting() = isPanning || isInteracting

    fun isBusy() = isPanning || hasPerformedScale
}
