package com.alexdremov.notate.ui.input

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.alexdremov.notate.data.PreferencesManager
import com.alexdremov.notate.model.PenTool
import com.alexdremov.notate.model.ToolType
import com.onyx.android.sdk.data.note.TouchPoint
import kotlin.math.hypot

/**
 * Helper class to detect when the user holds the pen still ("dwell") during a stroke.
 * Used for triggering Shape Perfection or other hold-gestures.
 */
class DwellDetector(
    private val context: Context,
    private val strokeBuilder: StrokeBuilder,
    private val onDwellDetected: (List<TouchPoint>) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val distanceThreshold = 10f // Screen pixels

    private var lastDwellPoint: TouchPoint? = null
    private var lastDwellIndex: Int = -1

    @Volatile
    var isShapeRecognized: Boolean = false
        private set

    @Volatile
    var ignoreNextStroke: Boolean = false
        private set

    private val dwellRunnable =
        Runnable {
            var candidatePoints: List<TouchPoint>? = null
            // Create a thread-safe snapshot of points up to the last movement reset
            synchronized(strokeBuilder) {
                val points = strokeBuilder.getPoints()
                if (lastDwellIndex > 0 && lastDwellIndex <= points.size) {
                    candidatePoints = ArrayList(points.subList(0, lastDwellIndex))
                }
            }

            candidatePoints?.let {
                onDwellDetected(it)
            }
        }

    fun onStart(
        startPoint: TouchPoint,
        tool: PenTool,
    ) {
        cancel()
        isShapeRecognized = false
        lastDwellPoint = startPoint
        lastDwellIndex = 1

        scheduleDwell(tool)
    }

    fun onMove(
        point: TouchPoint,
        tool: PenTool,
    ) {
        if (isShapeRecognized) return

        lastDwellPoint?.let { last ->
            val dist = hypot(point.x - last.x, point.y - last.y)
            if (dist > distanceThreshold) {
                lastDwellPoint = point

                synchronized(strokeBuilder) {
                    lastDwellIndex = strokeBuilder.getPoints().size
                }

                // Reset timer
                handler.removeCallbacks(dwellRunnable)
                scheduleDwell(tool)
            }
        }
    }

    fun onStop() {
        cancel()
    }

    fun markRecognized() {
        isShapeRecognized = true
        ignoreNextStroke = true
    }

    fun consumeIgnoreNextStroke(): Boolean {
        val ignore = ignoreNextStroke
        ignoreNextStroke = false
        return ignore
    }

    private fun scheduleDwell(tool: PenTool) {
        if (tool.type != ToolType.ERASER && PreferencesManager.isShapePerfectionEnabled(context)) {
            val delay = PreferencesManager.getShapePerfectionDelay(context)
            handler.postDelayed(dwellRunnable, delay)
        }
    }

    private fun cancel() {
        handler.removeCallbacks(dwellRunnable)
    }
}
