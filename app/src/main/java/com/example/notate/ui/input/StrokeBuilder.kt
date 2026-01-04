package com.example.notate.ui.input

import android.graphics.Path
import com.example.notate.model.Stroke
import com.example.notate.model.StrokeType
import com.example.notate.util.StrokeGeometry
import com.onyx.android.sdk.data.note.TouchPoint
import java.util.ArrayList

class StrokeBuilder {
    private var currentPath: Path? = null
    private val currentPoints = ArrayList<TouchPoint>()

    fun start(startPoint: TouchPoint) {
        currentPath = Path()
        currentPoints.clear()
        currentPoints.add(startPoint)
        currentPath?.moveTo(startPoint.x, startPoint.y)
    }

    fun addPoint(point: TouchPoint) {
        currentPath?.let { path ->
            currentPoints.add(point)
            path.lineTo(point.x, point.y)
        }
    }

    fun getLastPoint(): TouchPoint? = if (currentPoints.isNotEmpty()) currentPoints.last() else null

    fun getPoints(): List<TouchPoint> = currentPoints

    fun hasPoints(): Boolean = currentPoints.size >= 2

    fun build(
        color: Int,
        width: Float,
        type: StrokeType,
    ): Stroke? {
        val path = currentPath ?: return null
        if (currentPoints.size < 2) return null

        val bounds = StrokeGeometry.computeStrokeBounds(path, width, type)

        return Stroke(
            path = path,
            points = ArrayList(currentPoints),
            color = color,
            width = width,
            style = type,
            bounds = bounds,
            zIndex = type.defaultZIndex,
        )
    }

    fun buildSegment(
        start: TouchPoint,
        end: TouchPoint,
        width: Float,
        color: Int,
        type: StrokeType,
    ): Stroke {
        val path = Path()
        path.moveTo(start.x, start.y)
        path.lineTo(end.x, end.y)

        val bounds = StrokeGeometry.computeStrokeBounds(path, width, type)

        return Stroke(
            path = path,
            points = listOf(start, end),
            color = color,
            width = width,
            style = type,
            bounds = bounds,
            zIndex = type.defaultZIndex,
        )
    }

    fun clear() {
        currentPath = null
        currentPoints.clear()
    }
}
