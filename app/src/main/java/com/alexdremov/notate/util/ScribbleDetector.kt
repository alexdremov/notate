package com.alexdremov.notate.util

import android.graphics.RectF
import com.onyx.android.sdk.data.note.TouchPoint
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

object ScribbleDetector {
    private const val MIN_POINTS = 10
    private const val MIN_REVERSALS = 5 // Minimum back-and-forth movements (e.g., M shape has 3 turns)
    private const val SHARP_TURN_THRESHOLD_DEGREES = 140.0 // Angle change to consider a "turn" (180 is perfect reversal)
    private const val MIN_STROKE_LENGTH = 80f // Minimum length in pixels
    private const val MIN_DENSITY = 1.2f // Ratio of path length to bounding box diagonal
    private const val MIN_AVERAGE_SPEED_PX_MS = 1.0f // Minimum speed to consider it a "fast" scribble

    fun isScribble(points: List<TouchPoint>): Boolean {
        if (points.size < MIN_POINTS) return false

        val filteredPoints = filterByDistance(points, 8f) // Filter noise/too close points
        if (filteredPoints.size < 4) return false

        var totalLength = 0f
        var reversals = 0
        val bounds = RectF(filteredPoints[0].x, filteredPoints[0].y, filteredPoints[0].x, filteredPoints[0].y)

        for (i in 0 until filteredPoints.size - 1) {
            val p1 = filteredPoints[i]
            val p2 = filteredPoints[i + 1]
            totalLength += hypot(p2.x - p1.x, p2.y - p1.y)
            bounds.union(p2.x, p2.y)
        }

        if (totalLength < MIN_STROKE_LENGTH) return false

        // Check Speed
        val duration = points.last().timestamp - points.first().timestamp
        if (duration > 0) {
            val avgSpeed = totalLength / duration
            if (avgSpeed < MIN_AVERAGE_SPEED_PX_MS) return false
        }

        // Check for turns
        for (i in 1 until filteredPoints.size - 1) {
            val pPrev = filteredPoints[i - 1]
            val pCurr = filteredPoints[i]
            val pNext = filteredPoints[i + 1]

            val angleChange = calculateAngleChange(pPrev, pCurr, pNext)

            // We look for sharp reversals
            if (abs(angleChange) > SHARP_TURN_THRESHOLD_DEGREES) {
                reversals++
            }
        }

        val diagonal = hypot(bounds.width(), bounds.height())
        val density = if (diagonal > 0) totalLength / diagonal else 0f

        // A scribble is defined by multiple reversals AND being relatively compact/dense
        // Or if it has A LOT of reversals (very messy)
        if (reversals >= 5) return true

        return reversals >= MIN_REVERSALS && density > MIN_DENSITY
    }

    private fun filterByDistance(
        points: List<TouchPoint>,
        minDist: Float,
    ): List<TouchPoint> {
        val result = ArrayList<TouchPoint>()
        if (points.isEmpty()) return result
        result.add(points[0])
        var lastP = points[0]
        for (i in 1 until points.size) {
            val curP = points[i]
            val dist = hypot(curP.x - lastP.x, curP.y - lastP.y)
            if (dist >= minDist) {
                result.add(curP)
                lastP = curP
            }
        }
        return result
    }

    private fun calculateAngleChange(
        p1: TouchPoint,
        p2: TouchPoint,
        p3: TouchPoint,
    ): Double {
        // Vector 1: p1 -> p2
        // Vector 2: p2 -> p3
        val angle1 = atan2((p2.y - p1.y).toDouble(), (p2.x - p1.x).toDouble())
        val angle2 = atan2((p3.y - p2.y).toDouble(), (p3.x - p2.x).toDouble())

        var diff = Math.toDegrees(angle1 - angle2)

        // Normalize to -180..180
        while (diff < -180) diff += 360
        while (diff > 180) diff -= 360

        return diff
    }
}
