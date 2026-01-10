package com.alexdremov.notate.util

import android.graphics.Path
import com.alexdremov.notate.config.CanvasConfig
import com.alexdremov.notate.model.Stroke
import com.onyx.android.sdk.data.note.TouchPoint
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin

/**
 * Pure Kotlin implementation of a pressure-sensitive Fountain Pen renderer.
 * Generates a filled Path representing the variable-width stroke.
 *
 * Algorithm:
 * 1. Smoothing: Uses Catmull-Rom splines or similar smoothing on input points.
 * 2. Width Modulation: Interpolates width based on pressure.
 * 3. Outline Construction: Generates left/right envelope points based on normal vectors.
 */
object FountainPenRenderer {
    fun getPath(
        stroke: Stroke,
        maxPressure: Float,
    ): Path {
        val cached = stroke.renderCache
        if (cached is Path) {
            return cached
        }

        val generated = createStrokePath(stroke.points, stroke.width, maxPressure)
        stroke.renderCache = generated
        return generated
    }

    private fun createStrokePath(
        points: List<TouchPoint>,
        baseWidth: Float,
        maxPressure: Float,
    ): Path {
        if (points.size < 2) return Path()

        val path = Path()
        val processedPoints = preprocessPoints(points, maxPressure)

        if (processedPoints.size < 2) return Path()

        // Left and Right envelopes
        val rightSide = ArrayList<PointF>()
        val leftSide = ArrayList<PointF>()

        // Cap directions
        var startDirX = 0f
        var startDirY = 0f
        var endDirX = 0f
        var endDirY = 0f

        // 1. Generate Envelope Points
        for (i in 0 until processedPoints.size - 1) {
            val p1 = processedPoints[i]
            val p2 = processedPoints[i + 1]

            val vectorX = p2.x - p1.x
            val vectorY = p2.y - p1.y
            val distance = hypot(vectorX, vectorY)

            // Skip tiny segments to avoid artifacts
            if (distance < CanvasConfig.FOUNTAIN_TINY_SEGMENT_THRESHOLD) continue

            val dirX = (vectorX / distance)
            val dirY = (vectorY / distance)

            if (i == 0) {
                startDirX = dirX
                startDirY = dirY
            }
            if (i == processedPoints.size - 2) {
                endDirX = dirX
                endDirY = dirY
            }

            val angle = atan2(vectorY, vectorX)
            val sinAngle = sin(angle)
            val cosAngle = cos(angle)

            // Width at start and end of segment
            // Dynamic width based on pressure with non-linear response
            val p1Adj = p1.pressure.pow(CanvasConfig.FOUNTAIN_PRESSURE_POWER_EXPONENT)
            val p2Adj = p2.pressure.pow(CanvasConfig.FOUNTAIN_PRESSURE_POWER_EXPONENT)

            val w1 = (baseWidth * p1Adj * 0.5f).coerceAtLeast(CanvasConfig.FOUNTAIN_MIN_WIDTH)
            val w2 = (baseWidth * p2Adj * 0.5f).coerceAtLeast(CanvasConfig.FOUNTAIN_MIN_WIDTH)

            // Perpendicular offsets
            val dx1 = w1 * sinAngle
            val dy1 = w1 * cosAngle
            val dx2 = w2 * sinAngle
            val dy2 = w2 * cosAngle

            // Add points to envelopes
            if (i == 0) {
                leftSide.add(PointF(p1.x - dx1, p1.y + dy1))
                rightSide.add(PointF(p1.x + dx1, p1.y - dy1))
            }

            leftSide.add(PointF(p2.x - dx2, p2.y + dy2))
            rightSide.add(PointF(p2.x + dx2, p2.y - dy2))
        }

        // 2. Construct Closed Path
        if (leftSide.isEmpty()) return Path()

        path.moveTo(leftSide[0].x, leftSide[0].y)

        // Draw Left side forward
        for (i in 1 until leftSide.size) {
            val p = leftSide[i]
            val prev = leftSide[i - 1]
            val midX = (prev.x + p.x) / 2
            val midY = (prev.y + p.y) / 2
            path.quadTo(prev.x, prev.y, midX, midY)
        }
        path.lineTo(leftSide.last().x, leftSide.last().y)

        // Draw End Cap (Round)
        val pLast = processedPoints.last()
        val wLast =
            (
                baseWidth *
                    pLast.pressure.pow(
                        CanvasConfig.FOUNTAIN_PRESSURE_POWER_EXPONENT,
                    ) * 0.5f
            ).coerceAtLeast(CanvasConfig.FOUNTAIN_MIN_WIDTH)
        val capCtrlX = pLast.x + endDirX * wLast
        val capCtrlY = pLast.y + endDirY * wLast
        path.quadTo(capCtrlX, capCtrlY, rightSide.last().x, rightSide.last().y)

        // Draw Right side backward
        for (i in rightSide.size - 2 downTo 0) {
            val p = rightSide[i]
            val prev = rightSide[i + 1]
            val midX = (prev.x + p.x) / 2
            val midY = (prev.y + p.y) / 2
            path.quadTo(prev.x, prev.y, midX, midY)
        }
        path.lineTo(rightSide[0].x, rightSide[0].y)

        // Draw Start Cap (Round)
        val pFirst = processedPoints.first()
        val wFirst =
            (
                baseWidth *
                    pFirst.pressure.pow(
                        CanvasConfig.FOUNTAIN_PRESSURE_POWER_EXPONENT,
                    ) * 0.5f
            ).coerceAtLeast(CanvasConfig.FOUNTAIN_MIN_WIDTH)
        val startCapCtrlX = pFirst.x - startDirX * wFirst
        val startCapCtrlY = pFirst.y - startDirY * wFirst
        path.quadTo(startCapCtrlX, startCapCtrlY, leftSide[0].x, leftSide[0].y)

        path.close()

        return path
    }

    private data class PointF(
        val x: Float,
        val y: Float,
    )

    private data class ProcessedPoint(
        val x: Float,
        val y: Float,
        val pressure: Float,
    )

    private fun preprocessPoints(
        rawPoints: List<TouchPoint>,
        maxPressure: Float,
    ): List<ProcessedPoint> {
        val result = ArrayList<ProcessedPoint>(rawPoints.size)

        // Exponential Moving Average factor for pressure (0.0 = no change, 1.0 = instant)
        // Lower value = smoother pressure but more lag
        val pressureSmoothingFactor = CanvasConfig.FOUNTAIN_PRESSURE_SMOOTHING_FACTOR
        var currentSmoothedPressure =
            if (rawPoints.isNotEmpty()) {
                if (maxPressure > 0) rawPoints[0].pressure / maxPressure else 0.5f
            } else {
                0.5f
            }

        // Basic smoothing window (Moving Average) for coordinates
        val windowSize = CanvasConfig.FOUNTAIN_SMOOTHING_WINDOW_SIZE

        for (i in rawPoints.indices) {
            var sumX = 0f
            var sumY = 0f
            var count = 0

            if (windowSize > 0) {
                for (j in -windowSize..windowSize) {
                    val idx = i + j
                    if (idx in rawPoints.indices) {
                        val p = rawPoints[idx]
                        sumX += p.x
                        sumY += p.y
                        count++
                    }
                }
            } else {
                // No smoothing
                val p = rawPoints[i]
                sumX = p.x
                sumY = p.y
                count = 1
            }

            // Pressure Smoothing
            val rawP = rawPoints[i].pressure
            val normP = if (maxPressure > 0) rawP / maxPressure else 0.5f

            // EMA
            currentSmoothedPressure = (currentSmoothedPressure * (1 - pressureSmoothingFactor)) + (normP * pressureSmoothingFactor)

            result.add(ProcessedPoint(sumX / count, sumY / count, currentSmoothedPressure))
        }
        return result
    }
}
